package com.soulbot.app

import kotlin.math.abs

data class ChatStyleSample(
    val theirMessage: String,
    val myMessage: String,
    val time: Long = 0L,
    val scene: ReplyScene = ReplyScene.CHAT,
)

data class ChatStyleProfile(
    val sampleCount: Int,
    val averageLength: Int,
    val emojiPercent: Int,
    val questionPercent: Int,
    val multilinePercent: Int,
    val punctuationPercent: Int = 0,
    val averageSentenceLength: Int = 0,
    val commonWords: List<String> = emptyList(),
    val commonEndings: List<String> = emptyList(),
) {
    fun promptLine(): String =
        "已学习 $sampleCount 条人工回复：平均约 $averageLength 字，" +
        "约 $emojiPercent% 使用表情，约 $questionPercent% 使用问句，" +
        "约 $multilinePercent% 会分行，约 $punctuationPercent% 会用句末标点。" +
        "平均每句约 $averageSentenceLength 字。只把这些数字当成倾向，不要机械套用。"

    fun compactStyleHints(): String = buildString {
        if (commonWords.isNotEmpty()) {
            append("常见口头词：").append(commonWords.joinToString("、")).append("。")
        }
        if (commonEndings.isNotEmpty()) {
            append("常见收尾：").append(commonEndings.joinToString("、")).append("。")
        }
    }
}

object ChatSampleParser {
    fun parse(raw: String, limit: Int = 200): List<ChatStyleSample> {
        val result = mutableListOf<ChatStyleSample>()
        var their = ""
        var mine = ""
        var activeRole = ""
        var scene = ReplyScene.CHAT

        fun flush() {
            val cleanTheir = their.trim()
            val cleanMine = mine.trim()
            if (cleanTheir.isNotEmpty() && cleanMine.isNotEmpty()) {
                result += ChatStyleSample(cleanTheir, cleanMine, scene = scene)
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

            if (line.startsWith("场景：") || line.startsWith("场景:")) {
                flush()
                scene = parseScene(line.substringAfterAny("场景：", "场景:").trim())
                continue
            }

            val tabParts = line.split('\t', limit = 2)
            if (tabParts.size == 2 && tabParts.all { it.isNotBlank() }) {
                flush()
                result += ChatStyleSample(tabParts[0].trim(), tabParts[1].trim(), scene = scene)
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
            .distinctBy { it.scene.name + "\u0000" + comparableText(it.theirMessage) + "\u0000" + comparableText(it.myMessage) }
            .take(limit)
    }

    private fun parseScene(label: String): ReplyScene = when (label) {
        "灵魂匹配", "SOUL_MATCH" -> ReplyScene.SOUL_MATCH
        "奇遇铃", "QIYU" -> ReplyScene.QIYU
        "广场私聊", "SQUARE" -> ReplyScene.SQUARE
        else -> ReplyScene.CHAT
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
    // Common connective phrases are not enough evidence that two topics match.
    private val genericBigrams = setOf(
        "今天", "最近", "现在", "你是", "我是", "这个", "那个", "什么", "怎么",
        "了吗", "了嘛", "呢吗", "哈哈", "呵呵", "嗯嗯", "好的", "是的",
        "有点", "感觉", "觉得", "真的", "就是", "一下", "可以", "喜欢",
    )

    fun rank(
        query: String,
        samples: List<ChatStyleSample>,
        limit: Int = 5,
        scene: ReplyScene = ReplyScene.CHAT,
    ): List<ChatStyleSample> {
        if (samples.isEmpty() || limit <= 0) return emptyList()
        val normalizedQuery = comparableText(query)
        if (normalizedQuery.length < 2 || tokens(normalizedQuery).isEmpty() ||
            ConversationReplyContext.isLowInformation(query)) return emptyList()
        return samples
            .filter { it.scene == scene }
            .map { it to similarity(normalizedQuery, comparableText(it.theirMessage)) }
            .filter { it.second >= 0.24 }
            .sortedWith(
                compareByDescending<Pair<ChatStyleSample, Double>> { it.second }
                    .thenByDescending { it.first.time },
            )
            .take(limit)
            .map { it.first }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 2.0
        val aTokens = tokens(a)
        val bTokens = tokens(b)
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        val overlap = aTokens.intersect(bTokens).size.toDouble()
        if (overlap == 0.0) return 0.0
        val union = aTokens.union(bTokens).size.coerceAtLeast(1).toDouble()
        val shorter = minOf(aTokens.size, bTokens.size).toDouble()
        val lengthPenalty = abs(a.length - b.length).toDouble() / (a.length + b.length).coerceAtLeast(1)
        return 0.7 * overlap / shorter + 0.3 * overlap / union - lengthPenalty * 0.08
    }

    private fun tokens(text: String): Set<String> =
        text.windowed(2).filterNot { it in genericBigrams }.toSet()
}

object ReplyStyleFeedback {
    private val allowedLabels = listOf("昵称硬聊", "同城套话", "连续盘问", "客服腔", "编造经历")

    fun labels(notes: List<String>): List<String> = notes
        .flatMap { note -> allowedLabels.filter(note::contains) }
        .distinct()
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
        "感谢你的分享",
        "希望对你有所帮助",
        "如果方便的话",
        "从你的描述来看",
        "总的来说",
        "综上所述",
        "我建议你",
        "你可以尝试",
        "这个问题很有意思",
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
        profile: ChatStyleProfile? = null,
        recentReplies: List<String> = emptyList(),
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
            recentReplies.any { comparableText(it) == comparableText(clean) } -> "与最近回复重复"
            trailingLatin != null && trailingLatin !in allowedTrailingLatin ->
                "结尾包含疑似模型残片"
            profile != null && profile.sampleCount >= 8 &&
                clean.length > profile.averageLength * 3 + 12 ->
                "长度明显偏离个人聊天习惯"
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
