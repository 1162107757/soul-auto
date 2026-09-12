package com.soulbot.app

import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class ModelFailureType {
    TEMPORARY,
    RATE_LIMIT,
    CONFIGURATION,
}

data class ModelCallResult(
    val content: String?,
    val error: String = "",
    val failureType: ModelFailureType = ModelFailureType.TEMPORARY,
    val retryAfterMs: Long = 0,
    val httpStatus: Int? = null,
    val responseBytes: Int = 0,
    val durationMs: Long = 0,
)

object ModelClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastUsedModel: String = ""
        private set

    fun buildSystemPrompt(profile: SelfProfile = SelfProfile()): String {
        val now = LocalDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        return "今天真实日期是 ${now.year}年${now.monthValue}月${now.dayOfMonth}日 $weekday，现在是 ${now.hour} 点左右。\n" +
            "\n" +
            "你是 Soul 上一个普通用户，正在用手机随手聊天。只输出准备直接发送的聊天内容，不解释、不加引号或标签。" +
            profile.promptContext() + "\n" +
            "回复首先要接住对方最新说的具体内容。像真人当下打字，可以短、可以不完整，但不能只复述、附和或替对方下结论。\n" +
            "不要默认安慰、总结、教育或主动提供陪伴，也不要机械反问。除非对方明确说要睡觉、去忙或结束聊天，否则要从当前话题向前推进一点，留下一个对方容易接的具体话口。\n" +
            "避免客服腔和万能套话，例如“我理解你的感受”“如果你愿意”“随时可以找我”“我可以陪你”。不要突然换题；聊不动时就挖当前话题里的一个小细节。\n" +
            "避免固定的“语气词+复述对方+反问”三段式，也不要为了显得热情而每句都叫对方昵称、加表情或追问。\n" +
            "提到时间时以上面的真实日期为准，不要编造节日或行程。"
    }

    /** Compatibility wrapper for call sites that do not need failover metadata. */
    fun chat(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
    ): String? {
        val result = chatOnce(apiKey, baseUrl, model, systemPrompt, messages)
        lastError = result.error
        return result.content
    }

    fun chatOnce(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        timeoutMs: Long = 30_000L,
    ): ModelCallResult {
        val startedAt = SystemClock.elapsedRealtime()
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, content) in messages) {
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject().put("model", model).put("messages", msgs)
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "SoulBot-Android/1.0")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            call.timeout().timeout(timeoutMs.coerceIn(5_000L, 30_000L), TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = parseError(response.code, responseBody)
                    lastError = error
                    return@use ModelCallResult(
                        content = null,
                        error = error,
                        failureType = classifyHttpFailure(response.code, responseBody),
                        retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                        httpStatus = response.code,
                        responseBytes = responseBody.toByteArray(Charsets.UTF_8).size,
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                val content = runCatching { parseContent(JSONObject(responseBody)) }.getOrNull()
                if (content == null) {
                    val error = "HTTP ${response.code}：响应中没有可用文本"
                    lastError = error
                    ModelCallResult(
                        content = null,
                        error = error,
                        httpStatus = response.code,
                        responseBytes = responseBody.toByteArray(Charsets.UTF_8).size,
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                } else {
                    lastError = ""
                    lastUsedModel = model
                    ModelCallResult(
                        content = content,
                        httpStatus = response.code,
                        responseBytes = responseBody.toByteArray(Charsets.UTF_8).size,
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
            }
        } catch (e: Exception) {
            val error = "${e.javaClass.simpleName}：${e.message.orEmpty()}".take(180)
            lastError = error
            val type = if (e is IllegalArgumentException) {
                ModelFailureType.CONFIGURATION
            } else {
                ModelFailureType.TEMPORARY
            }
            ModelCallResult(
                content = null,
                error = error,
                failureType = type,
                durationMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
    }

    private fun classifyHttpFailure(statusCode: Int, body: String): ModelFailureType {
        if (statusCode == 429) return ModelFailureType.RATE_LIMIT
        if (statusCode in setOf(401, 403, 404, 405)) return ModelFailureType.CONFIGURATION
        if (statusCode in setOf(400, 422)) {
            val lower = body.lowercase()
            if ((lower.contains("model") || lower.contains("模型")) &&
                (lower.contains("invalid") || lower.contains("not found") ||
                    lower.contains("不存在") || lower.contains("无效"))
            ) {
                return ModelFailureType.CONFIGURATION
            }
        }
        return ModelFailureType.TEMPORARY
    }

    private fun parseRetryAfterMs(value: String?): Long =
        value?.trim()?.toLongOrNull()?.coerceIn(1, 600)?.times(1000) ?: 0

    private fun parseError(statusCode: Int, body: String): String {
        val detail = runCatching {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            listOfNotNull(
                error?.optString("code")?.takeIf { it.isNotBlank() },
                error?.optString("message")?.takeIf { it.isNotBlank() },
                json.optString("message").takeIf { it.isNotBlank() },
            ).distinct().joinToString("：")
        }.getOrDefault("")
        return if (detail.isBlank()) "HTTP $statusCode" else "HTTP $statusCode：$detail".take(180)
    }

    private fun parseContent(json: JSONObject): String? {
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.optJSONObject(0) ?: return null
        val msg = choice.optJSONObject("message")
        val contentValue = msg?.opt("content")
        val blockContent = (contentValue as? JSONArray)?.let { blocks ->
            buildString {
                for (index in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(index)
                    val text = block?.optString("text").orEmpty()
                    if (text.isNotBlank()) append(text)
                }
            }.takeIf { it.isNotBlank() }
        }
        val raw = (contentValue as? String)?.takeIf { it.isNotBlank() }
            ?: blockContent
            ?: choice.optString("text")?.takeIf { it.isNotBlank() }
            ?: msg?.optString("reasoning_content")?.takeIf { it.isNotBlank() }
            ?: return null
        val cleaned = raw
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        return cleaned.ifBlank { null }
    }
}
