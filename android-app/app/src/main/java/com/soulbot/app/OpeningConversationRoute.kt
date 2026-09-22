package com.soulbot.app

enum class OpeningConversationAction { OPEN, REPLY, SKIP }

/** Matching is not a message: never restart an already answered conversation. */
object OpeningConversationRoute {
    fun pendingIncoming(thread: List<Pair<String, String>>): List<String> =
        thread.takeLastWhile { it.first == "in" }.map { it.second }.filter(String::isNotBlank)

    fun action(thread: List<Pair<String, String>>, hasStoredHistory: Boolean): OpeningConversationAction = when {
        pendingIncoming(thread).isNotEmpty() -> OpeningConversationAction.REPLY
        thread.isNotEmpty() || hasStoredHistory -> OpeningConversationAction.SKIP
        else -> OpeningConversationAction.OPEN
    }
}
