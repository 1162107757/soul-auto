package com.soulbot.app

data class ReplyContextAnalysis(
    val focusIncoming: List<String>,
    val latestIsLowInformation: Boolean,
    val supportingTranscript: String,
)

/** Selects the real topic when the newest message is only an acknowledgement. */
object ConversationReplyContext {
    private val exactFillers = setOf(
        "哦", "哦哦", "噢", "噢噢", "嗯", "嗯嗯", "恩", "恩恩", "嗯哼",
        "对", "对的", "对对", "是", "是的", "是啊", "也是", "没错", "确实",
        "好", "好的", "好吧", "行", "行吧", "可以", "知道了", "收到",
        "啊", "啊啊", "额", "呃", "哈哈", "哈哈哈", "哈哈哈哈", "嘿嘿", "呵呵",
        "ok", "okay", "yes", "[表情]", "[图片]",
    )
    fun isLowInformation(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("""[\s，。！？!?、,.~～…·]+"""), "")
        return normalized in exactFillers
    }

    private val questionClues = listOf(
        "吗", "嘛", "么", "什么", "为啥", "为什么", "怎么", "咋", "哪里", "哪儿",
        "哪个", "几岁", "多大", "多少", "有没有", "是不是", "要不要", "能不能",
        "会不会", "喜不喜欢", "做什么", "干嘛", "干什么", "怎么样",
    )

    private val genericHookEndings = listOf(
        "你呢", "那你呢", "你怎么样", "你咋样", "然后呢", "还有呢", "怎么说",
    ).map(::comparableText)

    fun asksQuestion(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if ('?' in clean || '？' in clean) return true
        val comparable = comparableText(clean)
        return questionClues.any { clue -> comparable.contains(comparableText(clue)) } ||
            comparable.endsWith("呢")
    }

    fun needsEngagingHook(focusIncoming: List<String>): Boolean =
        focusIncoming.takeLast(2).any(::asksQuestion)

    fun hasEngagingHook(reply: String): Boolean {
        if (!asksQuestion(reply)) return false
        val comparable = comparableText(reply)
        return genericHookEndings.none(comparable::endsWith)
    }

    fun analyze(
        thread: List<Pair<String, String>>,
        focusIncoming: List<String> = emptyList(),
    ): ReplyContextAnalysis {
        val recent = thread.takeLast(20)
        val focus = focusIncoming.filter(String::isNotBlank).ifEmpty {
            recent.filter { it.first == "in" }.takeLast(1).map { it.second }
        }
        val lowInformation = focus.lastOrNull()?.let(::isLowInformation) == true
        if (!lowInformation) return ReplyContextAnalysis(focus, false, "")

        val anchorIndex = recent.indexOfLast { (role, text) ->
            role == "in" && !isLowInformation(text)
        }
        val selectedIndices = linkedSetOf<Int>()
        if (anchorIndex >= 0) {
            for (index in (anchorIndex - 2).coerceAtLeast(0)..minOf(anchorIndex + 2, recent.lastIndex)) {
                selectedIndices += index
            }
        }
        for (index in (recent.size - 8).coerceAtLeast(0)..recent.lastIndex) {
            selectedIndices += index
        }
        val transcript = selectedIndices.sorted().joinToString("\n") { index ->
            val (role, text) = recent[index]
            "${if (role == "in") "对方" else "我"}：$text"
        }
        return ReplyContextAnalysis(focus, true, transcript)
    }
}
