package com.soulbot.app

import kotlin.math.abs

data class ChatStyleSample(
    val theirMessage: String,
    val myMessage: String,
    val time: Long = 0L,
)

data class ChatStyleProfile(
    val sampleCount: Int,
    val averageLength: Int,
    val emojiPercent: Int,
    val questionPercent: Int,
    val multilinePercent: Int,
) {
    fun promptLine(): String =
        "已学习 $sampleCount 条人工回复：平均约 $averageLength 字，" +
            "约 $emojiPercent% 使用表情，约 $questionPercent% 使用问句，" +
            "约 $multilinePercent% 会分行。只把这些数字当成倾向，不要机械套用。"
}

object ChatSampleParser {
    fun parse(raw: String, limit: Int = 200): List<ChatStyleSample> {
        val result = mutableListOf<ChatStyleSample>()
        var their = ""
        var mine = ""
        var activeRole = ""

        fun flush() {
            val cleanTheir = their.trim()
            val cleanMine = mine.trim()
            if (cleanTheir.isNotEmpty() && cleanMine.isNotEmpty()) {
                result += ChatStyleSample(cleanTheir, cleanMine)
            }
            their = ""
            mine = ""
            activeRole = ""
        }

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (their.isNotEmpty() && mine.isNotEmpty()) flush()
                continue
            }

            val tabParts = line.split('\t', limit = 2)
            if (tabParts.size == 2 && tabParts.all { it.isNotBlank() }) {
                flush()
                result += ChatStyleSample(tabParts[0].trim(), tabParts[1].trim())
                continue
            }

            when {
                line.startsWith("对方：") || line.startsWith("对方:") -> {
                    if (their.isNotEmpty() && mine.isNotEmpty()) flush()
                    their = line.substringAfterAny("对方：", "对方:").trim()
                    activeRole = "their"
                }

                line.startsWith("我：") || line.startsWith("我:") -> {
                    mine = line.substringAfterAny("我：", "我:").trim()
                    activeRole = "mine"
                }

                activeRole == "their" -> their = appendLine(their, line)
                activeRole == "mine" -> mine = appendLine(mine, line)
            }
        }
        flush()

        return result
            .distinctBy { comparableText(it.theirMessage) + "\u0000" + comparableText(it.myMessage) }
            .take(limit)
    }

    private fun String.substringAfterAny(vararg prefixes: String): String {
        val prefix = prefixes.firstOrNull { startsWith(it) } ?: return this
        return substring(prefix.length)
    }

    private fun appendLine(existing: String, line: String): String =
        if (existing.isEmpty()) line else "$existing\n$line"
}

object ManualStyleLearner {
    fun extract(
        thread: List<Pair<String, String>>,
        isAutomatedReply: (String) -> Boolean,
    ): List<ChatStyleSample> {
        val result = mutableListOf<ChatStyleSample>()
        val incoming = mutableListOf<String>()
        for ((role, rawText) in thread) {
            val text = rawText.trim()
            if (text.isEmpty()) continue
            if (role == "in") {
                incoming += text
                if (incoming.size > 3) incoming.removeAt(0)
            } else if (role == "out") {
                if (incoming.isNotEmpty() && !isAutomatedReply(text)) {
                    result += ChatStyleSample(incoming.joinToString("\n"), text)
                }
                // Any outgoing message closes the current reply turn. Without
                // this reset, the next sample would accidentally include the
                // previous conversation turn as part of its incoming context.
                incoming.clear()
            }
        }
        return result.distinctBy {
            comparableText(it.theirMessage) + "\u0000" + comparableText(it.myMessage)
        }
    }
}

object StyleSampleMatcher {
    fun rank(query: String, samples: List<ChatStyleSample>, limit: Int = 5): List<ChatStyleSample> {
        if (samples.isEmpty() || limit <= 0) return emptyList()
        val normalizedQuery = comparableText(query)
        return samples
            .sortedWith(
                compareByDescending<ChatStyleSample> { similarity(normalizedQuery, comparableText(it.theirMessage)) }
                    .thenByDescending { it.time },
            )
            .take(limit)
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 2.0
        val aTokens = tokens(a)
        val bTokens = tokens(b)
        val overlap = aTokens.intersect(bTokens).size.toDouble()
        val union = aTokens.union(bTokens).size.coerceAtLeast(1).toDouble()
        val containmentBonus = if (a.contains(b) || b.contains(a)) 0.35 else 0.0
        val lengthPenalty = abs(a.length - b.length).toDouble() / (a.length + b.length).coerceAtLeast(1)
        return overlap / union + containmentBonus - lengthPenalty * 0.08
    }

    private fun tokens(text: String): Set<String> = when {
        text.length <= 1 -> setOf(text)
        else -> text.windowed(2).toSet() + text.map(Char::toString)
    }
}

object ReplyNaturalness {
    private val artificialPhrases = listOf(
        "作为一个AI",
        "作为AI",
        "我理解你的感受",
        "听起来你",
        "如果你愿意的话",
        "随时可以找我",
        "我会一直陪着你",
        "我可以陪你做很多事情",
        "有什么想聊的都可以告诉我",
        "很高兴认识你",
    )

    private val flatClosers = listOf(
        "哈哈确实", "确实是", "确实不想", "听着就挺", "这种还好", "那就行",
        "那挺好", "基本没瘾了", "先睡够再说", "早点休息就好",
    )

    private val allowedTrailingLatin = setOf("ok", "lol", "emo", "ktv", "soul", "citywalk")

    fun sanitize(raw: String): String = raw.trim()
        .removePrefix("回复：")
        .removePrefix("回复:")
        .removeSurrounding("\"")
        .trim()

    fun rejectionReason(
        text: String,
        requireTopicContinuation: Boolean = false,
        requireEngagingHook: Boolean = false,
        forbidQuestion: Boolean = false,
    ): String? {
        val clean = text.trim()
        val trailingLatin = Regex("""([A-Za-z]{2,})$""").find(clean)
            ?.groupValues?.getOrNull(1)?.lowercase()
        val genericContinuation = setOf(
            "怎么了", "怎么啦", "然后呢", "还有呢", "那你呢", "真的吗", "是吗",
            "哦", "嗯", "嗯嗯", "好的", "行吧", "哈哈", "哈哈哈",
        ).map(::comparableText).toSet()
        return when {
            clean.isEmpty() -> "内容为空"
            clean.length > 100 -> "内容太长"
            artificialPhrases.any(clean::contains) -> "包含明显的 AI 套话"
            trailingLatin != null && trailingLatin !in allowedTrailingLatin ->
                "结尾包含疑似模型残片"
            requireEngagingHook && flatClosers.any(clean::contains) ->
                "回复只是在附和或替对方下结论"
            requireTopicContinuation &&
                (ConversationReplyContext.isLowInformation(clean) ||
                    comparableText(clean) in genericContinuation) ->
                "没有延续前文话题"
            requireEngagingHook && !ConversationReplyContext.hasEngagingHook(clean) ->
                "回答后缺少自然的话题引子"
            forbidQuestion && ConversationReplyContext.asksQuestion(clean) ->
                "这一轮不应继续追问"
            else -> null
        }
    }
}

internal fun comparableText(text: String): String =
    text.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
