package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QiyuConversationPolicyTest {
    @Test fun genericHeaderNameSupportsChatWithoutLegacyTitleId() {
        assertEquals("小雨", QiyuConversationPolicy.conversationName(emptyList(), listOf("在线", "小雨", "Ta的星座：双子座")))
        assertEquals("旧标题昵称", QiyuConversationPolicy.conversationName(listOf("旧标题昵称"), listOf("备用昵称")))
    }

    @Test fun metadataOrMissingTitleIsNotInventedAsAContact() {
        assertEquals("", QiyuConversationPolicy.conversationName(emptyList(), listOf("在线", "Ta的引力签：喜欢猫", "匹配度 96%", "3分钟前", "关注后可邀请通话")))
        assertEquals("", QiyuConversationPolicy.conversationName(listOf(""), emptyList()))
        assertEquals("", QiyuConversationPolicy.conversationName(emptyList(), listOf("小雨", "小雪")))
    }

    @Test fun titleRecoveryDoesNotRequireNicknameToHaveLetters() {
        assertEquals("♡圆圆♡", QiyuConversationPolicy.conversationName(emptyList(), listOf("♡圆圆♡")))
        assertEquals("123456", QiyuConversationPolicy.conversationName(listOf("123456"), emptyList()))
        assertEquals("612", QiyuConversationPolicy.conversationName(emptyList(), listOf("612", "Ta的星座：双子座", "你们的共同点：四川")))
    }

    @Test fun missingTitleAndDifferentEmojiNamesNeverConfirmTheRecipient() {
        assertEquals(false, QiyuConversationPolicy.sameContact("小雨", ""))
        assertEquals(false, QiyuConversationPolicy.sameContact("🐱", "🐶"))
        assertEquals(true, QiyuConversationPolicy.sameContact("🐱", "🐱"))
        assertEquals(false, QiyuConversationPolicy.sameContact("小雨", "小雪"))
    }

    @Test fun storedHistoryWithEmptyPageMustLoadRatherThanPretendTheEncounterWasAnswered() {
        assertTrue(QiyuConversationPolicy.shouldWaitForHistory(emptyList(), true))
        assertEquals(false, QiyuConversationPolicy.shouldWaitForHistory(emptyList(), false))
        val answered = listOf("in" to "你好", "out" to "刚到家")
        assertEquals(false, QiyuConversationPolicy.shouldWaitForHistory(answered, true))
        assertEquals(OpeningConversationAction.SKIP, OpeningConversationRoute.action(answered, true))
        val unanswered = answered + ("in" to "今天忙吗")
        assertEquals(OpeningConversationAction.REPLY, OpeningConversationRoute.action(unanswered, true))
    }

    @Test fun unknownSendWithClearedInputIsNeverBlindlySentAgain() {
        assertEquals(QiyuSendRecovery.WAIT_CONFIRMATION, QiyuConversationPolicy.sendRecovery(false, true, false, 1))
    }

    @Test fun confirmedBubbleCompletesEvenAfterRetriesWereExhausted() {
        assertEquals(QiyuSendRecovery.CONFIRMED, QiyuConversationPolicy.sendRecovery(true, true, false, 2))
        assertEquals(QiyuSendRecovery.RETRY_UNSENT, QiyuConversationPolicy.sendRecovery(true, false, false, 0))
    }

    @Test fun retainedDraftOrNoClickAllowsOnlyBoundedRetries() {
        assertEquals(QiyuSendRecovery.RETRY_UNSENT, QiyuConversationPolicy.sendRecovery(false, false, false, 1))
        assertEquals(QiyuSendRecovery.RETRY_UNSENT, QiyuConversationPolicy.sendRecovery(false, true, true, 1))
        assertEquals(QiyuSendRecovery.WAIT_CONFIRMATION, QiyuConversationPolicy.sendRecovery(false, true, true, 2))
    }

    @Test fun repeatedReadFailureBacksOffWithoutPretendingSuccess() {
        assertEquals(1_500L, QiyuConversationPolicy.retryDelayMs(1))
        assertEquals(4_000L, QiyuConversationPolicy.retryDelayMs(2))
        assertEquals(15_000L, QiyuConversationPolicy.retryDelayMs(3))
        assertTrue(QiyuConversationPolicy.retryDelayMs(20) >= 60_000L)
    }

    @Test fun changedIncomingCanWithdrawOnlyAnExactlyOwnedUnsentDraft() {
        assertEquals(QiyuDraftRefresh.CLEAR_OWNED_UNSENT, QiyuConversationPolicy.draftRefresh("刚到家", "刚到家", false))
        assertEquals(QiyuDraftRefresh.KEEP_OTHER_INPUT, QiyuConversationPolicy.draftRefresh("刚到家", null, false))
        assertEquals(QiyuDraftRefresh.KEEP_OTHER_INPUT, QiyuConversationPolicy.draftRefresh("刚到家。", "刚到家", false))
        assertEquals(QiyuDraftRefresh.KEEP_OTHER_INPUT, QiyuConversationPolicy.draftRefresh("刚到家 ", "刚到家", false))
    }

    @Test fun clickedDraftRemainsPendingEvenIfItsInputIsEmptyOrUnchanged() {
        assertEquals(QiyuDraftRefresh.WAIT_CONFIRMATION, QiyuConversationPolicy.draftRefresh("刚到家", "刚到家", true))
        assertEquals(QiyuDraftRefresh.WAIT_CONFIRMATION, QiyuConversationPolicy.draftRefresh("", "刚到家", true))
        assertEquals(false, QiyuConversationPolicy.mayDiscardDraft(QiyuDraftRefresh.WAIT_CONFIRMATION, true))
    }

    @Test fun clearingFailureRetainsThePreparedReplyRatherThanGeneratingOverTheOldInput() {
        val refresh = QiyuConversationPolicy.draftRefresh("刚到家", "刚到家", false)
        assertEquals(false, QiyuConversationPolicy.mayDiscardDraft(refresh, false))
        assertEquals(true, QiyuConversationPolicy.mayDiscardDraft(refresh, true))
        assertEquals(false, QiyuConversationPolicy.mayDiscardDraft(QiyuDraftRefresh.KEEP_OTHER_INPUT, true))
    }

    @Test fun emptyNeverClickedInputCanDiscardAnObsoletePreparedReplyWithoutWriting() {
        val refresh = QiyuConversationPolicy.draftRefresh("", "刚到家", false)
        assertEquals(QiyuDraftRefresh.DISCARD_EMPTY, refresh)
        assertEquals(true, QiyuConversationPolicy.mayDiscardDraft(refresh, false))
    }
}
