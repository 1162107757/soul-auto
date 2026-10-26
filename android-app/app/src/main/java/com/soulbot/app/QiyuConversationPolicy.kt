package com.soulbot.app

enum class QiyuSendRecovery { CONFIRMED, RETRY_UNSENT, WAIT_CONFIRMATION }
enum class QiyuDraftRefresh { DISCARD_EMPTY, CLEAR_OWNED_UNSENT, KEEP_OTHER_INPUT, WAIT_CONFIRMATION }

/** Small deterministic guards; a failed read/send is never evidence of a completed encounter. */
object QiyuConversationPolicy {
    const val LOAD_TIMEOUT_MS = 8_000L
    const val MAX_SEND_ATTEMPTS = 2

    fun retryDelayMs(failures: Int): Long = when {
        failures <= 1 -> 1_500L
        failures == 2 -> 4_000L
        failures == 3 -> 15_000L
        else -> 60_000L
    }

    fun conversationName(primaryTitles: List<String>, genericHeaderTitles: List<String>): String {
        primaryTitles.map(String::trim).firstOrNull(::isContactTitle)?.let { return it }
        return genericHeaderTitles.map(String::trim).filter(::isContactTitle).distinct().singleOrNull().orEmpty()
    }

    fun sameContact(expected: String, displayed: String): Boolean {
        if (expected.isBlank() || displayed.isBlank()) return false
        val key = comparableText(expected)
        return if (key.isNotBlank()) key == comparableText(displayed) else expected.trim() == displayed.trim()
    }

    fun shouldWaitForHistory(thread: List<Pair<String, String>>, hasStoredHistory: Boolean): Boolean =
        thread.isEmpty() && hasStoredHistory

    /** Clearing requires exact text ownership; punctuation/whitespace edits belong to the user. */
    fun draftRefresh(inputText: String, writtenText: String?, clickedSend: Boolean): QiyuDraftRefresh = when {
        clickedSend -> QiyuDraftRefresh.WAIT_CONFIRMATION
        inputText.isEmpty() -> QiyuDraftRefresh.DISCARD_EMPTY
        !writtenText.isNullOrEmpty() && inputText == writtenText -> QiyuDraftRefresh.CLEAR_OWNED_UNSENT
        else -> QiyuDraftRefresh.KEEP_OTHER_INPUT
    }

    fun mayDiscardDraft(refresh: QiyuDraftRefresh, cleared: Boolean): Boolean =
        refresh == QiyuDraftRefresh.DISCARD_EMPTY || (refresh == QiyuDraftRefresh.CLEAR_OWNED_UNSENT && cleared)

    private fun isContactTitle(text: String): Boolean {
        if (text.isBlank() || text.length > 120) return false
        if (text in setOf("在线", "离线", "关注", "聊天", "私聊", "奇遇铃", "查看主页", "返回", "Soul")) return false
        if (listOf("Ta的", "TA的", "Ta仁爱星球", "匹配度", "你们的共同点", "Ta的共同点", "关注后")
                .any(text::startsWith)) return false
        return !Regex("(?:\\d+\\s*(?:分钟|小时|天)前|\\d{1,2}:\\d{2}|刚刚在线|刚刚活跃)").matches(text)
    }

    fun sendRecovery(
        outgoingIncreased: Boolean,
        clickedSend: Boolean,
        inputStillContainsDraft: Boolean,
        attempts: Int,
    ): QiyuSendRecovery = when {
        outgoingIncreased && clickedSend -> QiyuSendRecovery.CONFIRMED
        attempts < MAX_SEND_ATTEMPTS && (!clickedSend || inputStillContainsDraft) -> QiyuSendRecovery.RETRY_UNSENT
        else -> QiyuSendRecovery.WAIT_CONFIRMATION
    }
}
