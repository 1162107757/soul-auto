package com.soulbot.app

data class ConversationMemoryEntry(
    val eventKey: String,
    val contact: String,
    val role: String,
    val content: String,
    val observedAt: Long,
    val source: String,
)

object ConversationMemoryLogic {
    fun messagesAfterOverlap(
        existingTail: List<Pair<String, String>>,
        visibleThread: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        if (visibleThread.isEmpty()) return emptyList()
        val existing = existingTail.map(::comparable)
        val visible = visibleThread.map(::comparable)
        val maximum = minOf(existing.size, visible.size)
        var overlap = 0
        for (size in maximum downTo 1) {
            if (existing.takeLast(size) == visible.take(size)) {
                overlap = size
                break
            }
        }
        return visibleThread.drop(overlap)
    }

    fun rankRelevant(
        query: String,
        candidates: List<ConversationMemoryEntry>,
        limit: Int,
    ): List<ConversationMemoryEntry> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty() || limit <= 0) return emptyList()
        return candidates
            .mapIndexed { index, entry ->
                val contentTokens = tokens(entry.content)
                val shared = queryTokens.intersect(contentTokens)
                val score = shared.sumOf { token -> token.length * token.length } * 100 - index
                entry to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .sortedBy { it.observedAt }
    }

    private fun comparable(message: Pair<String, String>): Pair<String, String> =
        message.first.trim().lowercase() to
            message.second.lowercase().replace(Regex("""[\s，。！？!?、~～…：:；;“”\"'（）()\[\]{}]+"""), "")

    private fun tokens(text: String): Set<String> {
        val normalized = text.lowercase()
        val result = linkedSetOf<String>()
        Regex("[a-z0-9]{2,}").findAll(normalized).forEach { result += it.value }
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(normalized).forEach { match ->
            val value = match.value
            for (index in 0 until value.length - 1) {
                result += value.substring(index, index + 2)
            }
        }
        return result
    }
}
