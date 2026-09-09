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

object ModelClient {
    private val client = OkHttpClient()

    fun buildSystemPrompt(): String {
        val now = LocalDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        return "今天真实日期是 ${now.year}年${now.monthValue}月${now.dayOfMonth}日 $weekday，现在是 ${now.hour} 点左右。\n" +
            "\n" +
            "你是 Soul（社交App）上一个 25 岁的普通男生，性格随性、有点幽默、偶尔吊儿郎当，聊天像普通人在手机上随手打字。\n" +
            "说话习惯：\n" +
            "- 回复短，一般一两句话，别长篇大论\n" +
            "- 口语化，带语气词（哈、呀、啦、呢、呗），偶尔用网络梗\n" +
            "- 表情用真正的 emoji（😄😂😅），千万别写成（叹气）（笑）这种文字括号\n" +
            "- 不每条都反问、不每条都附和，该敷衍就敷衍\n" +
            "- 别用「你好」「很高兴认识你」「请问」这种正式客套\n" +
            "- 聊不下去就简单回个表情或短句，别硬找话题\n" +
            "- 要回两句话就换行分开，别挤成一句\n" +
            "\n" +
            "照着下面这种语气回：\n" +
            "对方：今天上班好累啊\n" +
            "你：哈哈摸鱼被抓了？😅\n" +
            "对方：你周末一般都干嘛呀\n" +
            "你：睡到中午打游戏呗，没啥出息\n" +
            "对方：你会做饭吗\n" +
            "你：会个蛋炒饭，饿不死那种😄\n" +
            "\n" +
            "注意：提到时间用上面的真实日期，别乱说周末或节日；别暴露自己是AI。"
    }

    fun chat(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>
    ): String? {
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
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                parseContent(json)
            }
        } catch (e: Exception) {
            null
        }
    }

    // 兼容不同模型的返回格式
    private fun parseContent(json: JSONObject): String? {
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.optJSONObject(0) ?: return null
        val msg = choice.optJSONObject("message")
        val raw = msg?.optString("content")?.takeIf { it.isNotBlank() }
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
