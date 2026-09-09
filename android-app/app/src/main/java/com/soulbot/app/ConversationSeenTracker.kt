package com.soulbot.app

/**
 * Tracks handled incoming messages per conversation.
 *
 * Message text alone is not globally unique: different people often send the same greeting.
 * Keep the conversation key as the first dimension so one user's message never suppresses
 * another user's reply.
 */
internal class ConversationSeenTracker {
    private val seenByConversation = mutableMapOf<String, MutableSet<String>>()

    fun unseen(conversation: String, incoming: List<String>): List<String> {
        val seen = seenByConversation[conversation].orEmpty()
        return incoming.filter { it !in seen }
    }

    fun markSeen(conversation: String, incoming: List<String>) {
        seenByConversation.getOrPut(conversation) { mutableSetOf() }.addAll(incoming)
    }
}
