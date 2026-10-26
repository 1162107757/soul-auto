package com.soulbot.app

import java.security.MessageDigest
import java.util.UUID

enum class ReplayDataset { LEARNING, EVALUATION }
enum class ReplayChoice { A, B, NEITHER }

data class ReplayTurn(val role: String, val text: String)
data class ReplayCandidate(
    val text: String,
    val source: String,
    val durationMs: Long = 0,
    val error: String = "",
)

data class ReplyReplayCase(
    val id: String = UUID.randomUUID().toString(),
    val contact: String,
    val scene: ReplyScene = ReplyScene.CHAT,
    val turns: List<ReplayTurn>,
    val originalReply: String = "",
    val correctedReply: String = "",
    val reason: String = "",
    val dataset: ReplayDataset = ReplayDataset.LEARNING,
    val candidateA: ReplayCandidate? = null,
    val candidateB: ReplayCandidate? = null,
    val choice: ReplayChoice? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ReplaySummary(val learning: Int, val evaluation: Int, val reviewed: Int)
data class ReplayImportResult(val imported: Int, val skipped: Int)

/** Human-reviewed style examples, never a store of facts to borrow from another person. */
object ReplyReplayLogic {
    const val MAX_TURNS = 40
    const val MAX_TURN_CHARS = 2_000
    const val MAX_CONTEXT_CHARS = 16_000
    const val MAX_REPLY_CHARS = 2_000
    const val MAX_CASES = 2_000
    const val MAX_IMPORT_CHARS = 8 * 1024 * 1024
    private val turnPrefix = Regex("^(我|对方)\\s*[:：]\\s*(.*)$")
    private val whitespace = Regex("\\s+")
    private val punctuation = Regex("[\\s\\p{P}\\p{S}]+")
    private val credentialMetadata = Regex(
        "https?://|(?:api[_ -]?key|authorization|bearer)\\s*[:= ]|\\bsk-[A-Za-z0-9_-]{8,}",
        RegexOption.IGNORE_CASE,
    )
    private val fillerTokens = setOf("哈哈", "嗯嗯", "好的", "好吧", "是的", "对方", "然后", "就是", "这个", "那个")

    fun parseTranscript(text: String): List<ReplayTurn> {
        require(text.length <= MAX_CONTEXT_CHARS + MAX_TURNS * 8) { "聊天片段过长，请保留关键上下文" }
        val turns = mutableListOf<ReplayTurn>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val match = turnPrefix.matchEntire(line)
            if (match != null) {
                turns += ReplayTurn(if (match.groupValues[1] == "我") "out" else "in", match.groupValues[2].trim())
                require(turns.size <= MAX_TURNS) { "聊天片段最多 $MAX_TURNS 条消息" }
            } else {
                require(turns.isNotEmpty()) { "每段消息请以“我：”或“对方：”开头" }
                val previous = turns.removeAt(turns.lastIndex)
                turns += previous.copy(text = (previous.text + "\n" + line).trim())
            }
        }
        require(turns.all { it.text.isNotBlank() }) { "消息内容不能为空" }
        require(turns.all { it.text.length <= MAX_TURN_CHARS }) { "单条消息最多 $MAX_TURN_CHARS 字" }
        require(turns.sumOf { it.text.length } <= MAX_CONTEXT_CHARS) { "聊天片段过长，请保留关键上下文" }
        return turns
    }

    fun formatTranscript(turns: List<ReplayTurn>): String = turns.joinToString("\n") {
        "${if (it.role == "out") "我" else "对方"}：${it.text}"
    }

    fun contextKey(turns: List<ReplayTurn>, scene: ReplyScene): String = contextKeyNormalized(normalizeTurns(turns), scene)

    private fun contextKeyNormalized(turns: List<NormalizedTurn>, scene: ReplyScene): String {
        val source = buildString {
            append(scene.name).append('|')
            turns.forEach { turn ->
                append(turn.role).append(':').append(turn.text.length).append(':').append(turn.text).append('|')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun validationError(item: ReplyReplayCase): String? {
        if (!item.id.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return "记录编号无效"
        if (item.contact.length > 120 || item.contact.contains('\u0000')) return "联系人名称过长或无效"
        if (item.turns.isEmpty() || item.turns.size > MAX_TURNS) return "聊天片段须包含 1～$MAX_TURNS 条消息"
        if (item.turns.any { it.role != "in" && it.role != "out" }) return "消息方向仅支持我和对方"
        if (item.turns.none { it.role == "in" }) return "请至少保留一条对方消息"
        if (item.turns.any { it.text.isBlank() || it.text.length > MAX_TURN_CHARS || it.text.contains('\u0000') }) {
            return "消息不能为空，且单条不得超过 $MAX_TURN_CHARS 字"
        }
        if (item.turns.sumOf { it.text.length } > MAX_CONTEXT_CHARS) return "聊天上下文最多 $MAX_CONTEXT_CHARS 字"
        if (listOf(item.originalReply, item.correctedReply).any { it.length > MAX_REPLY_CHARS || it.contains('\u0000') }) {
            return "回复内容最多 $MAX_REPLY_CHARS 字，且不能包含空字符"
        }
        if (item.reason.length > 1_000 || item.reason.contains('\u0000')) return "修改原因最多 1000 字"
        if (item.createdAt < 0) return "记录时间无效"
        for (candidate in listOfNotNull(item.candidateA, item.candidateB)) {
            if (candidate.text.length > MAX_REPLY_CHARS || candidate.text.contains('\u0000')) return "候选回复过长或无效"
            if (candidate.source.length > 120 || candidate.source.contains('\u0000') || credentialMetadata.containsMatchIn(candidate.source)) {
                return "候选来源仅可记录模型显示名和策略，不能包含网址或密钥"
            }
            if (candidate.error.length > 500 || candidate.error.contains('\u0000') || credentialMetadata.containsMatchIn(candidate.error)) {
                return "候选错误说明过长或包含敏感配置"
            }
            if (candidate.durationMs < 0 || candidate.durationMs > 86_400_000) return "候选耗时无效"
            if (candidate.text.isBlank() && candidate.error.isBlank()) return "候选回复和错误说明不能同时为空"
        }
        val selected = when (item.choice) {
            ReplayChoice.A -> item.candidateA
            ReplayChoice.B -> item.candidateB
            else -> null
        }
        if (item.choice == ReplayChoice.A || item.choice == ReplayChoice.B) {
            if (selected == null || selected.text.isBlank() || selected.error.isNotBlank()) return "不能选择不存在或生成失败的候选"
        }
        return null
    }

    fun learningReply(item: ReplyReplayCase): String {
        if (item.dataset != ReplayDataset.LEARNING || validationError(item) != null) return ""
        if (item.correctedReply.isNotBlank()) return item.correctedReply.trim()
        return when (item.choice) {
            ReplayChoice.A -> item.candidateA?.text.orEmpty().trim()
            ReplayChoice.B -> item.candidateB?.text.orEmpty().trim()
            else -> ""
        }
    }

    fun rank(
        query: String,
        cases: List<ReplyReplayCase>,
        scene: ReplyScene,
        limit: Int = 3,
        excludeContextKey: String = "",
        excludeContact: String = "",
        excludeTurns: List<ReplayTurn> = emptyList(),
        checkCancelled: () -> Unit = {},
    ): List<ReplyReplayCase> {
        checkCancelled()
        if (limit <= 0) return emptyList()
        val queryTokens = tokens(query.take(MAX_CONTEXT_CHARS))
        if (queryTokens.size < 2) return emptyList()
        // Holdout status wins over every learning copy, independent of id, contact or review date.
        val excluded = mutableSetOf<String>()
        val overlapIndex = ContextOverlapIndex()
        for (item in cases) {
            checkCancelled()
            if (item.dataset != ReplayDataset.EVALUATION) continue
            val normalized = normalizeTurns(item.turns, checkCancelled)
            excluded += contextKeyNormalized(normalized, item.scene)
            overlapIndex.add(normalized)
        }
        overlapIndex.add(normalizeTurns(excludeTurns, checkCancelled))
        if (excludeContextKey.isNotBlank()) excluded += excludeContextKey
        val contactToExclude = excludeContact.trim()
        return cases.asSequence()
            .onEach { checkCancelled() }
            .filter { it.scene == scene && learningReply(it).isNotBlank() }
            // A different crop of this person's later history can otherwise reveal the target answer.
            .filter { contactToExclude.isBlank() || !it.contact.trim().equals(contactToExclude, ignoreCase = true) }
            .mapNotNull { item ->
                val normalized = normalizeTurns(item.turns, checkCancelled)
                val key = contextKeyNormalized(normalized, item.scene)
                if (key in excluded || overlapIndex.overlaps(normalized)) return@mapNotNull null
                checkCancelled()
                // Retrieve from the full situation, not only a short last message such as “嗯”.
                val contextTokens = tokens(item.turns.joinToString("\n") { it.text })
                val common = queryTokens.intersect(contextTokens).size
                val score = 2.0 * common / (queryTokens.size + contextTokens.size).coerceAtLeast(1)
                if (common < 2 || score < 0.20) null else RankedCase(item, score, key)
            }
            .sortedWith(compareByDescending<RankedCase> { it.score }.thenByDescending { it.item.createdAt })
            .distinctBy { it.key }
            .take(limit.coerceAtMost(5))
            .map { checkCancelled(); anonymize(it.item) }
            .toList()
    }

    /**
     * Conservatively separates held-out/current conversations from learning windows.
     * Compare consecutive role/text pairs, including a single-message target occurring
     * anywhere in a longer window. A shared short answer alone is not an overlap when
     * both windows provide different preceding context; same-contact filtering handles
     * the remaining future-history risk during replay retrieval.
     */
    fun overlapsContext(a: List<ReplayTurn>, b: List<ReplayTurn>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return ContextOverlapIndex().apply { add(normalizeTurns(a)) }.overlaps(normalizeTurns(b))
    }

    private data class RankedCase(val item: ReplyReplayCase, val score: Double, val key: String)

    /** `text` preserves the existing hash format; overlapText distinguishes punctuation-only turns. */
    private data class NormalizedTurn(val role: String, val text: String, val overlapText: String)

    private fun normalizeTurns(
        turns: List<ReplayTurn>,
        checkCancelled: () -> Unit = {},
    ): List<NormalizedTurn> = turns.map { turn ->
        checkCancelled()
        val lower = turn.text.lowercase()
        val clean = punctuation.replace(lower, "")
        NormalizedTurn(turn.role, clean, clean.ifBlank { lower.trim() })
    }

    /** One index shared by all candidates: no candidate × heldout-window normalization loop. */
    private class ContextOverlapIndex {
        private val allTurns = hashSetOf<NormalizedTurn>()
        private val singleTurnWindows = hashSetOf<NormalizedTurn>()
        private val adjacentPairs = hashSetOf<Pair<NormalizedTurn, NormalizedTurn>>()

        fun add(window: List<NormalizedTurn>) {
            allTurns.addAll(window)
            if (window.size == 1) singleTurnWindows += window[0]
            for (index in 1 until window.size) adjacentPairs += window[index - 1] to window[index]
        }

        fun overlaps(window: List<NormalizedTurn>): Boolean {
            if (window.isEmpty()) return false
            if (window.size == 1) return window[0] in allTurns
            if (window.any { it in singleTurnWindows }) return true
            for (index in 1 until window.size) {
                if ((window[index - 1] to window[index]) in adjacentPairs) return true
            }
            return false
        }
    }

    private fun tokens(text: String): Set<String> {
        val withoutRoles = text.replace(Regex("(?m)^(?:我|对方)\\s*[:：]\\s*"), "")
        return Regex("[\\p{IsHan}]+|[A-Za-z0-9]+")
            .findAll(whitespace.replace(withoutRoles.lowercase(), " "))
            .flatMap { match ->
                if (match.value.any { it in 'a'..'z' }) sequenceOf(match.value)
                else match.value.windowed(2).asSequence()
            }.filter { it.length >= 2 && it !in fillerTokens }.toSet()
    }

    private fun anonymize(item: ReplyReplayCase): ReplyReplayCase {
        val name = item.contact.trim()
        val plainName = punctuation.replace(name, "")
        fun clean(text: String): String {
            if (name.isBlank()) return text
            var cleaned = text.replace(name, "对方", ignoreCase = true)
            if (plainName.length >= 2 && plainName != name) cleaned = cleaned.replace(plainName, "对方", ignoreCase = true)
            return cleaned
        }
        fun cleanCandidate(candidate: ReplayCandidate?) = candidate?.copy(
            text = clean(candidate.text), source = clean(candidate.source), error = clean(candidate.error),
        )
        return item.copy(
            contact = "", turns = item.turns.map { it.copy(text = clean(it.text)) },
            originalReply = clean(item.originalReply), correctedReply = clean(item.correctedReply),
            reason = clean(item.reason), candidateA = cleanCandidate(item.candidateA), candidateB = cleanCandidate(item.candidateB),
        )
    }
}
