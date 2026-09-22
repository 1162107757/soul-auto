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
    fun questionsAreOptional_notScheduledAfterSeveralTurns() {
        assertTrue(
            ConversationReplyContext.responseGuidance(
                listOf("in" to "我刚下班"),
                listOf("我刚下班"),
            ).contains("不必凑问号"),
        )
        assertTrue(
            ConversationReplyContext.responseGuidance(
                listOf("out" to "你是刚下班吗", "in" to "对呀"),
                listOf("对呀"),
            ).contains("不再连续盘问"),
        )
        assertTrue(
            ConversationReplyContext.responseGuidance(
                listOf("in" to "你多大呀"),
                listOf("你多大呀"),
            ).contains("先回答"),
        )
        assertTrue(
            ConversationReplyContext.responseGuidance(
                listOf(
                    "out" to "这雨真没停过",
                    "in" to "是啊",
                    "out" to "鞋都快晾不干了",
                    "in" to "我也是",
                ),
                listOf("我也是"),
            ).contains("不必凑问号"),
        )
        assertTrue(
            ConversationReplyContext.responseGuidance(
                listOf("in" to "我先睡了，晚安"),
                listOf("我先睡了，晚安"),
            ).contains("收尾"),
        )

        assertTrue(ConversationReplyContext.hasEngagingHook("我25，你周末也经常去爬山吗"))
        assertFalse(ConversationReplyContext.hasEngagingHook("我25，你呢"))
        assertFalse(ConversationReplyContext.hasEngagingHook("那今天上班没？"))
        assertFalse(ConversationReplyContext.hasEngagingHook("我25"))
        assertTrue(ConversationReplyContext.hasEngagingHook("最烦吃饱以后还得面对一池子碗"))
    }

    @Test
    fun ordinaryStatementsContainingQuestionCharactersAreNotQuestions() {
        assertFalse(ConversationReplyContext.asksQuestion("这么近"))
        assertFalse(ConversationReplyContext.asksQuestion("没什么，刚下班"))
        assertFalse(ConversationReplyContext.asksQuestion("不知道怎么说，反正挺开心"))
        assertTrue(ConversationReplyContext.asksQuestion("这家店有什么好吃的"))
        assertTrue(ConversationReplyContext.asksQuestion("现在下班了吗"))
    }
}
