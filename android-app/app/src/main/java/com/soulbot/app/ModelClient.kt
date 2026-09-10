package com.soulbot.app

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

object ModelClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastUsedModel: String = ""
        private set

    private data class Attempt(val content: String?, val error: String)

    fun buildSystemPrompt(profile: SelfProfile = SelfProfile()): String {
        val now = LocalDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        return "今天真实日期是 ${now.year}年${now.monthValue}月${now.dayOfMonth}日 $weekday，现在是 ${now.hour} 点左右。\n" +
            "\n" +
            "你是 Soul 上一个普通用户，正在用手机随手聊天。只输出准备直接发送的聊天内容，不解释、不加引号或标签。" +
            profile.promptContext() + "\n" +
            "回复首先要接住对方最新说的具体内容。像真人当下打字，可以短、可以不完整，也可以只是一个自然反应；不要把每条消息都写成完整、周到的回答。\n" +
            "不要默认安慰、总结、教育或主动提供陪伴，不要每次都附和、反问、使用语气词或表情。只有上下文自然需要时才使用这些表达。\n" +
            "避免客服腔和万能套话，例如“我理解你的感受”“如果你愿意”“随时可以找我”“我可以陪你”。聊不下去时宁可简短，也不要硬找新话题。\n" +
            "避免固定的“语气词+复述对方+反问”三段式，也不要为了显得热情而每句都叫对方昵称、加表情或追问。\n" +
            "提到时间时以上面的真实日期为准，不要编造节日或行程。"
    }

    fun chat(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>
    ): String? {
        val primary = requestOnce(apiKey, baseUrl, model, systemPrompt, messages)
        if (primary.content != null) {
            lastError = ""
            lastUsedModel = model
            return primary.content
        }

        // APINebula 的 Codex 通道偶尔会返回 get_channel_failed。此时使用同一
        // API Key 临时回退到价格更低的 terra，不改动用户保存的首选模型。
        if (baseUrl.contains("apinebula.ai", ignoreCase = true) &&
            model == "gpt-5.6-sol" &&
            isTemporaryChannelError(primary.error)
        ) {
            val fallbackModel = "gpt-5.6-terra"
            android.util.Log.d("SoulBot", "主模型暂不可用，尝试备用模型 $fallbackModel")
            val fallback = requestOnce(apiKey, baseUrl, fallbackModel, systemPrompt, messages)
            if (fallback.content != null) {
                lastError = ""
                lastUsedModel = fallbackModel
                return fallback.content
            }
            lastError = fallback.error
            return null
        }

        lastError = primary.error
        return null
    }

    private fun requestOnce(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
    ): Attempt {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, content) in messages) {
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject().put("model", model).put("messages", msgs)
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "SoulBot-Android/1.0")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val error = parseError(resp.code, responseBody)
                    android.util.Log.d("SoulBot", "模型请求失败: $error")
                    return@use Attempt(null, error)
                }
                val content = runCatching { parseContent(JSONObject(responseBody)) }.getOrNull()
                if (content == null) {
                    Attempt(null, "HTTP ${resp.code}：响应中没有可用文本")
                } else {
                    Attempt(content, "")
                }
            }
        } catch (e: Exception) {
            val error = "${e.javaClass.simpleName}：${e.message.orEmpty()}".take(180)
            android.util.Log.d("SoulBot", "模型请求异常: $error")
            Attempt(null, error)
        }
    }

    private fun isTemporaryChannelError(error: String): Boolean =
        error.contains("503") ||
            error.contains("get_channel_failed", ignoreCase = true) ||
            error.contains("无可用通道") ||
            error.contains("暂时不可用")

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

    // 兼容不同模型的返回格式
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
        // 去掉 <think>...</think> 思维链标记（reasoning 模型会带这个）
        val cleaned = raw
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        return cleaned.ifBlank { null }
    }
}
