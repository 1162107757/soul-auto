package com.soulbot.app

/** Prompt advice, not a question quota or a gate that can cause generation retries. */
object ReplyRhythmPolicy {
    fun guidance(
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
    ): String {
        val focus = focusIncoming.filter(String::isNotBlank).ifEmpty {
            thread.lastOrNull { it.first == "in" }?.second?.let(::listOf).orEmpty()
        }
        val latest = focus.lastOrNull().orEmpty()
        val latestOutgoing = thread.lastOrNull { it.first == "out" }?.second.orEmpty()
        val recentOutgoing = thread.filter { it.first == "out" }.takeLast(2).map { it.second }
        val incomingQuestion = focus.any(ConversationReplyContext::asksQuestion)
        val contextualAnswer = focus.any(ConversationReplyContext::isContextDependentAnswer)

        if (ConversationReplyContext.isConversationClosing(latest)) {
            val answerFirst = if (incomingQuestion) "本轮若还有实际问题，先简短回答；" else ""
            return "${answerFirst}对方正在收尾，简短回应即可，不开启新话题或追加问题；不为留住对方强行续聊。"
        }
        if (incomingQuestion) {
            return "先回答对方实际问的问题；只用本人已确认的信息，不知道的不要编造。" +
                "回答完整即可，不附带对等盘问；有相关反应可以自然补一句，没有也不必加引子。"
        }

        return buildString {
            if (contextualAnswer || ConversationReplyContext.isLowInformation(latest)) {
                append("短答不等于冷淡。先找它承接的上一句具体提问或陈述，结合本轮连续消息理解，不只围绕最后几个字发挥。")
            }
            if (ConversationReplyContext.asksQuestion(latestOutgoing) && focus.isNotEmpty()) {
                append("对方可能正在回答或补充、纠正我刚才的问题，先接住答案，不把‘会、不会、没、有、对’当成没有内容；")
                append("若我之前猜错了，就自然承认理解错了，不替对方解释或重复原判断。")
            } else if (focus.any(::containsCorrection)) {
                append("对方可能在否定或纠正前面的判断，回看我刚说的话，接住不同意见，不替对方解释或劝对方改口。")
            }
            if (recentOutgoing.any(ConversationReplyContext::asksQuestion)) {
                append("最近已经问过问题，优先用具体反应或看法接住回答，给对方自由展开的空间，不再连续盘问。")
            }
            append("按这轮情境选择回答、接梗、补充一个相关看法或简短回应，不需要每样都做。")
            append("可以接一个细节，确有必要时才问一个容易回答的问题；陈述句也可以自然接话，不必凑问号。")
            append("双方短答时不强行换题、追问或硬造热闹，允许自然停顿；不能为了找共同点编造自己的经历。")
        }
    }

    private fun containsCorrection(text: String): Boolean {
        val clean = comparableText(text)
        return clean.startsWith("不是") || clean.startsWith("不对") || clean.startsWith("没有") ||
            clean.startsWith("其实") || clean.startsWith("没啦") || clean.startsWith("不好") ||
            clean.startsWith("不喜欢") || clean.startsWith("不想")
    }
}
