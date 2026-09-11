package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLearningTest {
    @Test
    fun parsesLabeledAndTabSeparatedSamples() {
        val samples = ChatSampleParser.parse(
            "对方：下班了吗\n我：刚下，累死了\n\n今天吃啥\t随便整点呗",
        )

        assertEquals(2, samples.size)
        assertEquals("下班了吗", samples[0].theirMessage)
        assertEquals("刚下，累死了", samples[0].myMessage)
    }

    @Test
    fun extractsOnlyRepliesNotMarkedAsAutomated() {
        val thread = listOf(
            "in" to "下班没",
            "out" to "刚下班",
            "in" to "吃了吗",
            "out" to "还没呢",
        )

        val samples = ManualStyleLearner.extract(thread) { it == "刚下班" }

        assertEquals(1, samples.size)
        assertEquals("吃了吗", samples.single().theirMessage)
        assertEquals("还没呢", samples.single().myMessage)
    }

    @Test
    fun ranksSimilarIncomingMessageFirst() {
        val samples = listOf(
            ChatStyleSample("你下班了吗", "刚下"),
            ChatStyleSample("今天吃什么", "还没想好"),
        )

        val ranked = StyleSampleMatcher.rank("下班没呀", samples)

        assertEquals("刚下", ranked.first().myMessage)
    }

    @Test
    fun detectsArtificialStockPhrases() {
        assertTrue(ReplyNaturalness.rejectionReason("随时可以找我聊天") != null)
        assertFalse(ReplyNaturalness.rejectionReason("行吧你先玩😂") != null)
    }

    @Test
    fun rejectsEmptyAcknowledgement_whenTheLatestMessageNeedsTopicContinuation() {
        assertEquals(
            "没有延续前文话题",
            ReplyNaturalness.rejectionReason("嗯嗯", requireTopicContinuation = true),
        )
        assertEquals(
            "没有延续前文话题",
            ReplyNaturalness.rejectionReason("然后呢", requireTopicContinuation = true),
        )
        assertEquals(
            null,
            ReplyNaturalness.rejectionReason("那你这班上的确实够累", requireTopicContinuation = true),
        )
    }

    @Test
    fun aPlannedHookMustBeSpecificRatherThanGeneric() {
        assertEquals(
            "回答后缺少自然的话题引子",
            ReplyNaturalness.rejectionReason("我25", requireEngagingHook = true),
        )
        assertEquals(
            "回答后缺少自然的话题引子",
            ReplyNaturalness.rejectionReason("我25，你呢", requireEngagingHook = true),
        )
        assertEquals(
            null,
            ReplyNaturalness.rejectionReason(
                "我25，周末喜欢去爬山，你那边有没有适合看日落的地方？",
                requireEngagingHook = true,
            ),
        )
    }

    @Test
    fun nonQuestionTurnRejectsAnotherFollowUpQuestion() {
        assertEquals(
            "这一轮不应继续追问",
            ReplyNaturalness.rejectionReason(
                "这雨确实烦，你今天出门了吗？",
                forbidQuestion = true,
            ),
        )
        assertEquals(
            null,
            ReplyNaturalness.rejectionReason(
                "这雨确实烦，我鞋到现在都没晾干",
                forbidQuestion = true,
            ),
        )
    }

    @Test
    fun rejectsFlatRepliesSeenInRealConversations() {
        assertEquals(
            "回复只是在附和或替对方下结论",
            ReplyNaturalness.rejectionReason(
                "这天气确实不想出门",
                requireEngagingHook = true,
            ),
        )
        assertEquals(
            "回复只是在附和或替对方下结论",
            ReplyNaturalness.rejectionReason(
                "这种还好，基本没瘾了",
                requireEngagingHook = true,
            ),
        )
        assertEquals(
            "回答后缺少自然的话题引子",
            ReplyNaturalness.rejectionReason(
                "酒要先有点酸味后有点甜的好喝",
                requireEngagingHook = true,
            ),
        )
        assertEquals(
            null,
            ReplyNaturalness.rejectionReason(
                "怎么个不好喝法，是味道太冲还是有股怪味？",
                requireEngagingHook = true,
            ),
        )
    }

    @Test
    fun rejectsLikelyModelGarbageAtTheEnd() {
        assertEquals(
            "结尾包含疑似模型残片",
            ReplyNaturalness.rejectionReason("每天都差不多ele"),
        )
    }
}
