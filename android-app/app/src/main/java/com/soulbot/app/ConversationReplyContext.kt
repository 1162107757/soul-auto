package com.soulbot.app

data class ReplyContextAnalysis(
    val focusIncoming: List<String>,
    val latestIsLowInformation: Boolean,
    val supportingTranscript: String,
    val needsSupportingContext: Boolean = latestIsLowInformation,
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
    private val contextualAnswerPattern = Regex(
        "^(?:会|不会|有|没有|没|对|不对|是|不是|能|不能|可以|不行|还没|没有了|没去|去了|不去|喜欢|不喜欢|想|不想)(?:的|呀|啊|呢|啦|哦|哇|吧){0,2}$",
    )

    fun isLowInformation(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("""[\s，。！？!?、,.~～…·]+"""), "")
        return normalized in exactFillers ||
            (normalized.length <= 10 && fillerOnly.matches(normalized))
    }

    /** A complete answer can be short; this flag asks for context, not a longer reply. */
    fun isContextDependentAnswer(text: String): Boolean = contextualAnswerPattern.matches(comparableText(text))

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
        if (comparable.endsWith("吗")) return true
        val statement = comparable
            .replace(Regex("(?:不知|不知道|说不清|没想好|不确定)(?:道)?(?:该|要|能|是)?(?:为什么|怎么|咋|什么|哪里|哪儿|哪个|多少|几岁|多大)"), "")
            .replace(Regex("(?:没什么|没啥|没怎么|不管怎么|无论怎么|想怎么|怎么都|什么都)"), "")
        if (statement.endsWith("么")) return true
        if (Regex("(?:你|您).*(?:没|没有)$").containsMatchIn(statement) ||
            Regex("(?:下班|上班|吃饭|到家|睡觉|起床|回来|到了|吃了|睡了)(?:了)?(?:没|没有)$")
                .containsMatchIn(statement)) return true
        return questionClues.any { clue -> statement.contains(clue) }
    }

    fun isConversationClosing(text: String): Boolean {
        if (asksQuestion(text)) return false
        return text.split(Regex("[，。！？!?、,.\\n]"))
            .any { clause ->
                val comparable = comparableText(clause)
                val reportedOrNegated = Regex("(?:他说|她说|说了句|跟我说|说晚安|不想|不打算|不准备|还没|没想|不是要|不睡|别|不要)")
                    .containsMatchIn(comparable)
                !reportedOrNegated && closingClues.any { comparable.contains(comparableText(it)) }
            }
    }

    /** Contextual advice only: never a schedule that forces a question. */
    fun responseGuidance(
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
    ): String = ReplyRhythmPolicy.guidance(thread, focusIncoming)

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
        val needsContext = lowInformation || focus.any(::isContextDependentAnswer)
        if (!needsContext) return ReplyContextAnalysis(focus, false, "")

        val anchorIndex = recent.indexOfLast { (role, text) ->
            role == "in" && !isLowInformation(text) && !isContextDependentAnswer(text)
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
        return ReplyContextAnalysis(focus, lowInformation, transcript, needsContext)
    }
}
