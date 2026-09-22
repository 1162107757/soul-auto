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
    fun sceneHeadersApplyToFollowingBlocksAndPreserveLegacyDefault() {
        val samples = ChatSampleParser.parse(
            "对方：下班了\n我：总算自由了\n\n" +
                "场景：灵魂匹配\n对方：喜欢做饭\n我：洗碗才是难题\n\n" +
                "场景:奇遇铃\n喜欢做饭\t洗碗才是难题\n\n" +
                "场景：广场私聊\n对方：喜欢做饭\n我：洗碗才是难题\n\n" +
                "场景：聊天回复\n对方：喜欢做饭\n我：洗碗才是难题",
        )
        assertEquals(
            listOf(ReplyScene.CHAT, ReplyScene.SOUL_MATCH, ReplyScene.QIYU, ReplyScene.SQUARE, ReplyScene.CHAT),
            samples.map { it.scene },
        )
    }

    @Test
    fun sampleDeduplicationIncludesTheScene() {
        val samples = ChatSampleParser.parse(
            "场景：奇遇铃\n对方：喜欢做饭\n我：洗碗才是难题\n\n" +
                "对方：喜欢做饭\n我：洗碗才是难题\n\n" +
                "场景：灵魂匹配\n对方：喜欢做饭\n我：洗碗才是难题",
        )
        assertEquals(2, samples.size)
        assertEquals(listOf(ReplyScene.QIYU, ReplyScene.SOUL_MATCH), samples.map { it.scene })
    }

    @Test
    fun retrievalNeverBorrowsExamplesFromAnotherScene() {
        val samples = listOf(
            ChatStyleSample("喜欢做饭", "聊天样本", scene = ReplyScene.CHAT),
            ChatStyleSample("喜欢做饭", "奇遇样本", scene = ReplyScene.QIYU),
            ChatStyleSample("喜欢做饭", "匹配样本", scene = ReplyScene.SOUL_MATCH),
        )
        assertEquals(
            listOf("奇遇样本"),
            StyleSampleMatcher.rank("喜欢做饭", samples, scene = ReplyScene.QIYU).map { it.myMessage },
        )
        assertTrue(StyleSampleMatcher.rank("喜欢做饭", samples, scene = ReplyScene.SQUARE).isEmpty())
    }

    @Test
    fun unrelatedSamplesAreNotInjectedToFillTheLimit() {
        val samples = listOf(
            ChatStyleSample("今天吃什么", "饭点样本"),
            ChatStyleSample("你下班了吗", "下班样本"),
        )
        assertEquals(
            listOf("下班样本"),
            StyleSampleMatcher.rank("下班没呀", samples).map { it.myMessage },
        )
        assertTrue(StyleSampleMatcher.rank("今天打球", samples).isEmpty())
        assertTrue(StyleSampleMatcher.rank("我在看海", samples).isEmpty())
        assertTrue(StyleSampleMatcher.rank("", samples).isEmpty())
        assertTrue(StyleSampleMatcher.rank("嗯", samples).isEmpty())
        assertTrue(StyleSampleMatcher.rank("喜欢做饭", samples, limit = 0).isEmpty())
    }

    @Test
    fun oneSharedCommonCharacterCannotMakeASampleRelevant() {
        val samples = listOf(ChatStyleSample("今天想吃饭", "相关性不足"))
        assertTrue(StyleSampleMatcher.rank("明天去爬山", samples).isEmpty())
        assertTrue(StyleSampleMatcher.rank("今天打球", samples).isEmpty())
    }

    @Test
    fun negativeStyleFeedbackOnlyExportsControlledLabels() {
        assertEquals(
            listOf("昵称硬聊", "连续盘问", "同城套话"),
            ReplyStyleFeedback.labels(
                listOf("对方叫小李，昵称硬聊而且连续盘问", "同城套话，连续盘问", "私人原文不要传播"),
            ),
        )
        assertTrue(ReplyStyleFeedback.labels(listOf("任意用户指令和聊天内容")).isEmpty())
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
            null,
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

    @Test
    fun rejectsAiTemplateAndRecentDuplicate() {
        assertEquals(
            "包含明显的 AI 套话",
            ReplyNaturalness.rejectionReason("感谢你的分享，如果方便的话可以继续告诉我"),
        )
        assertEquals(
            "与最近回复重复",
            ReplyNaturalness.rejectionReason(
                "刚下班，准备吃饭",
                recentReplies = listOf("刚下班，准备吃饭"),
            ),
        )
    }

    @Test
    fun profileHintsDescribeHumanStyleWithoutCopyingFacts() {
        val profile = ChatStyleProfile(
            sampleCount = 12,
            averageLength = 14,
            emojiPercent = 20,
            questionPercent = 30,
            multilinePercent = 10,
            punctuationPercent = 40,
            averageSentenceLength = 9,
            commonWords = listOf("哈哈", "刚下"),
            commonEndings = listOf("呢", "呀"),
        )
        assertTrue(profile.promptLine().contains("12 条人工回复"))
        assertTrue(profile.compactStyleHints().contains("哈哈"))
    }
}
