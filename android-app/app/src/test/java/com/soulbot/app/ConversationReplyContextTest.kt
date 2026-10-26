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
        assertFalse(ConversationReplyContext.asksQuestion("我不知道该怎么说"))
        assertFalse(ConversationReplyContext.asksQuestion("我也不知道为什么"))
        assertFalse(ConversationReplyContext.asksQuestion("不知道哪里好玩"))
        assertTrue(ConversationReplyContext.asksQuestion("这家店有什么好吃的"))
        assertTrue(ConversationReplyContext.asksQuestion("现在下班了吗"))
        assertTrue(ConversationReplyContext.asksQuestion("那今天上班没"))
        assertTrue(ConversationReplyContext.asksQuestion("你到家没有"))
    }

    @Test
    fun completeShortAnswerKeepsItsQuestionWithoutCallingItCold() {
        val thread = listOf(
            "in" to "最近一直没什么精神",
            "out" to "有猫陪着会好一点吗",
            "in" to "会",
        )
        val analysis = ConversationReplyContext.analyze(thread, listOf("会"))
        assertFalse(analysis.latestIsLowInformation)
        assertTrue(analysis.needsSupportingContext)
        assertTrue(analysis.supportingTranscript.contains("有猫陪着会好一点吗"))
        assertTrue(analysis.supportingTranscript.contains("最近一直没什么精神"))
        val guidance = ConversationReplyContext.responseGuidance(thread, listOf("会"))
        assertTrue(guidance.contains("短答不等于冷淡"))
        assertTrue(guidance.contains("先接住答案"))
        assertTrue(guidance.contains("不再连续盘问"))
    }

    @Test
    fun nightShiftAnswerUsesBothMessagesAndAllowsCorrection() {
        val thread = listOf(
            "out" to "那今天上班没？",
            "in" to "没啦",
            "in" to "晚上夜班",
        )
        val analysis = ConversationReplyContext.analyze(thread, listOf("没啦", "晚上夜班"))
        assertTrue(analysis.needsSupportingContext)
        assertTrue(analysis.supportingTranscript.contains("晚上夜班"))
        val guidance = ConversationReplyContext.responseGuidance(thread, listOf("没啦", "晚上夜班"))
        assertTrue(guidance.contains("本轮连续消息"))
        assertTrue(guidance.contains("补充、纠正"))
        assertTrue(guidance.contains("猜错了"))
    }

    @Test
    fun repeatedDislikeIsAllowedToCorrectOurPreviousJudgement() {
        val guidance = ConversationReplyContext.responseGuidance(
            listOf(
                "in" to "这个酒是真不好喝",
                "out" to "酒要先有点酸味后有点甜的好喝",
                "in" to "不好喝",
            ),
            listOf("不好喝"),
        )
        assertTrue(guidance.contains("纠正前面的判断"))
        assertTrue(guidance.contains("不替对方解释"))
        assertTrue(guidance.contains("不必凑问号"))
    }

    @Test
    fun shortExchangesDoNotAutomaticallyTriggerAnotherQuestion() {
        val guidance = ConversationReplyContext.responseGuidance(
            listOf("out" to "是啊", "in" to "嗯", "out" to "好", "in" to "对"),
            listOf("对"),
        )
        assertTrue(guidance.contains("允许自然停顿"))
        assertTrue(guidance.contains("不强行换题"))
        assertTrue(guidance.contains("不必凑问号"))
    }

    @Test
    fun farewellDoesNotForceAHookAndMentioningSleepIsNotAFarewell() {
        assertTrue(ConversationReplyContext.isConversationClosing("我先忙了，回头聊"))
        assertFalse(ConversationReplyContext.isConversationClosing("你准备睡了吗？"))
        assertFalse(ConversationReplyContext.isConversationClosing("我不想睡觉了"))
        assertFalse(ConversationReplyContext.isConversationClosing("他跟我说晚安"))
        val guidance = ConversationReplyContext.responseGuidance(
            listOf("out" to "今天加班吗", "in" to "先不聊了"),
            listOf("先不聊了"),
        )
        assertTrue(guidance.contains("不开启新话题"))
        assertFalse(guidance.contains("先接住答案"))
    }

    @Test
    fun incomingQuestionStillTakesPriorityOverOurPreviousQuestion() {
        val guidance = ConversationReplyContext.responseGuidance(
            listOf("out" to "到家没？", "in" to "到了，你多大呀"),
            listOf("到了，你多大呀"),
        )
        assertTrue(guidance.startsWith("先回答"))
        assertTrue(guidance.contains("不知道的不要编造"))
        assertTrue(guidance.contains("不附带对等盘问"))
    }

    @Test
    fun contextualAnswerRecognitionDoesNotMeanAllShortMessagesAreAcknowledgements() {
        listOf("会", "不会", "没", "没有啦", "有啊", "对", "不是", "不喜欢")
            .forEach { assertTrue(it, ConversationReplyContext.isContextDependentAnswer(it)) }
        listOf("晚上夜班", "不好喝", "我不会做饭", "下班了")
            .forEach { assertFalse(it, ConversationReplyContext.isContextDependentAnswer(it)) }
    }
}
