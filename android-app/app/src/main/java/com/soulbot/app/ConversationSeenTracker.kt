package com.soulbot.app

/**
 * Tracks handled incoming messages per conversation.
 *
 * Message text alone is not globally unique: different people often send the same greeting.
 * Keep the conversation key as the first dimension so one user's message never suppresses
 * another user's reply.
 */
internal class ConversationSeenTracker {
    private val handledByConversation = mutableMapOf<String, List<String>>()

    fun unseen(conversation: String, incoming: List<String>): List<String> {
        val handled = handledByConversation[conversation] ?: return incoming
        val maxOverlap = minOf(handled.size, incoming.size)
        val overlap = (maxOverlap downTo 1).firstOrNull { size ->
            handled.takeLast(size) == incoming.take(size)
        } ?: 0
        return incoming.drop(overlap)
    }

    fun markSeen(conversation: String, incoming: List<String>) {
        handledByConversation[conversation] = incoming.toList()
    }
}
