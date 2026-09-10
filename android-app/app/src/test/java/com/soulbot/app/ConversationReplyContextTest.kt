package com.soulbot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReplyContextTest {
    @Test
    fun recognizesShortAcknowledgements_butNotMeaningfulShortMessages() {
        assertTrue(ConversationReplyContext.isLowInformation("嗯嗯。"))
        assertTrue(ConversationReplyContext.isLowInformation("对！"))
        assertTrue(ConversationReplyContext.isLowInformation("哦~"))
        assertTrue(ConversationReplyContext.isLowInformation("嗯呐嗯呐"))
        assertTrue(ConversationReplyContext.isLowInformation("是的哇"))
        assertTrue(ConversationReplyContext.isLowInformation("[表情]"))
        assertFalse(ConversationReplyContext.isLowInformation("不对"))
        assertFalse(ConversationReplyContext.isLowInformation("下班了"))
    }

    @Test
    fun lowInformationLatestMessage_keepsTheEarlierTopicAndBothSides() {
        val analysis = ConversationReplyContext.analyze(
            listOf(
                "in" to "今天连着开了四个会，刚下班",
                "out" to "四个会也太折磨了，你现在到家没",
                "in" to "对",
                "in" to "嗯",
            ),
            focusIncoming = listOf("对", "嗯"),
        )

        assertTrue(analysis.latestIsLowInformation)
        assertTrue(analysis.supportingTranscript.contains("今天连着开了四个会"))
        assertTrue(analysis.supportingTranscript.contains("四个会也太折磨了"))
    }

    @Test
    fun normalTurnsRequireAHook_butExplicitClosingsDoNot() {
        assertTrue(ConversationReplyContext.needsEngagingHook(listOf("你多大呀")))
        assertTrue(ConversationReplyContext.needsEngagingHook(listOf("你周末一般去哪玩？")))
        assertTrue(ConversationReplyContext.needsEngagingHook(listOf("我刚下班")))
        assertTrue(ConversationReplyContext.needsEngagingHook(listOf("不好喝")))
        assertFalse(ConversationReplyContext.needsEngagingHook(listOf("我先睡了，晚安")))

        assertTrue(ConversationReplyContext.hasEngagingHook("我25，你周末也经常去爬山吗"))
        assertFalse(ConversationReplyContext.hasEngagingHook("我25，你呢"))
        assertFalse(ConversationReplyContext.hasEngagingHook("那今天上班没？"))
        assertFalse(ConversationReplyContext.hasEngagingHook("我25"))
    }
}
