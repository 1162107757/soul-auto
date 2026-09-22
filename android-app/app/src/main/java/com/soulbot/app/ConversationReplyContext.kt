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
        "没啦", "没呢", "没有啦", "是的哇", "嗯呐", "嗯呐嗯呐",
        "啊", "啊啊", "额", "呃", "哈哈", "哈哈哈", "哈哈哈哈", "嘿嘿", "呵呵",
        "ok", "okay", "yes", "[表情]", "[图片]",
    )

    private val fillerOnly = Regex("""^(?:嗯|恩|哦|噢|啊|呐|呢|哇|哈|嘿|是的|对的|好的)+$""")

    fun isLowInformation(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("""[\s，。！？!?、,.~～…·]+"""), "")
        return normalized in exactFillers ||
            (normalized.length <= 10 && fillerOnly.matches(normalized))
    }

    private val questionClues = listOf(
        "什么", "为啥", "为什么", "怎么", "咋", "哪里", "哪儿",
        "哪个", "几岁", "多大", "多少", "有没有", "是不是", "要不要", "能不能",
        "会不会", "喜不喜欢", "做什么", "干嘛", "干什么", "怎么样",
    )

    private val genericHookEndings = listOf(
        "你呢", "那你呢", "你怎么样", "你咋样", "然后呢", "还有呢", "怎么说",
        "你平时干嘛", "你在干嘛", "今天上班吗", "今天上班没", "吃饭了吗",
        "睡了吗", "在吗", "最近怎么样", "平时做什么",
    ).map(::comparableText)

    private val closingClues = listOf(
        "晚安", "睡觉了", "先睡了", "准备睡了", "我要睡了", "先忙了", "去忙了",
        "开会去了", "先不聊了", "不聊了", "下次聊", "回头聊", "拜拜", "再见",
    )

    fun asksQuestion(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if ('?' in clean || '？' in clean) return true
        val comparable = comparableText(clean)
        if (comparable.endsWith("吗") || comparable.endsWith("么")) return true
        val statement = comparable.replace(Regex("(?:没什么|没啥|没怎么|不知道怎么|不知怎么|不管怎么|无论怎么|想怎么|怎么都|什么都)"), "")
        return questionClues.any { clue -> statement.contains(clue) }
    }

    fun isConversationClosing(text: String): Boolean {
        val comparable = comparableText(text)
        return closingClues.any { comparable.contains(comparableText(it)) }
    }

    /** Contextual advice only: never a schedule that forces a question. */
    fun responseGuidance(
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
    ): String {
        val latest = focusIncoming.lastOrNull(String::isNotBlank).orEmpty()
        if (isConversationClosing(latest)) return "对方正在收尾，简短回应即可，不开启新话题或追加问题。"
        if (focusIncoming.any(::asksQuestion)) return "先回答对方实际问的问题；回答完整即可，不附带对等盘问。"
        val recentOutgoing = thread.filter { it.first == "out" }.takeLast(2).map { it.second }
        if (recentOutgoing.any(::asksQuestion)) {
            return "最近已经问过问题，优先用具体反应或看法接住回答，给对方自由展开的空间，不再连续盘问。"
        }
        return "可以接一个细节、表达看法，确有必要时才问一个容易回答的问题；陈述句也可以自然接话，不必凑问号。"
    }

    fun hasEngagingHook(reply: String): Boolean {
        val comparable = comparableText(reply)
        if (isLowInformation(reply) || genericHookEndings.any(comparable::endsWith)) return false
        // A grounded observation can invite a reply without being a question.
        return asksQuestion(reply) || comparable.length >= 8
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
