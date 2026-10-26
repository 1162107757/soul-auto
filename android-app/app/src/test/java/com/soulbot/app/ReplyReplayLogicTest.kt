package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class ReplyReplayLogicTest {
    private val nightShift = listOf(
        ReplayTurn("out", "今天上班吗"), ReplayTurn("in", "晚上夜班"),
        ReplayTurn("out", "那你白天休息"), ReplayTurn("in", "嗯"),
    )
    private fun sample(id: String = "sample-1") = ReplyReplayCase(
        id = id, contact = "圆圆", turns = nightShift,
        originalReply = "那白天先睡够再说", correctedReply = "我还以为你今天休息呢",
        reason = "接着前面的上班话题，不催人结束聊天", createdAt = 100,
    )

    @Test fun parsesBothColonsAndMultilineMessages() {
        val result = ReplyReplayLogic.parseTranscript("我: 今天上班吗\n\n对方：晚上夜班\n白天休息\n我：那我猜错了")
        assertEquals(3, result.size)
        assertEquals(ReplayTurn("in", "晚上夜班\n白天休息"), result[1])
        assertEquals(result, ReplyReplayLogic.parseTranscript(ReplyReplayLogic.formatTranscript(result)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTranscriptWithoutAnInitialSpeaker() {
        ReplyReplayLogic.parseTranscript("今天上班吗\n对方：晚上夜班")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyMessageBetweenTurns() {
        ReplyReplayLogic.parseTranscript("对方：\n我：今天休息")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooManyTurns() {
        ReplyReplayLogic.parseTranscript((0..ReplyReplayLogic.MAX_TURNS).joinToString("\n") { "对方：消息 $it" })
    }

    @Test fun keyIgnoresSpacingAndPunctuationButPreservesDirectionAndScene() {
        val first = listOf(ReplayTurn("in", "今天， 上班吗？"))
        val second = listOf(ReplayTurn("in", "今天上班吗"))
        assertEquals(ReplyReplayLogic.contextKey(first, ReplyScene.CHAT), ReplyReplayLogic.contextKey(second, ReplyScene.CHAT))
        assertNotEquals(ReplyReplayLogic.contextKey(first, ReplyScene.CHAT), ReplyReplayLogic.contextKey(first, ReplyScene.QIYU))
        assertNotEquals(ReplyReplayLogic.contextKey(first, ReplyScene.CHAT), ReplyReplayLogic.contextKey(listOf(ReplayTurn("out", first[0].text)), ReplyScene.CHAT))
    }

    @Test fun keysPreserveMessageBoundaries() {
        val first = listOf(ReplayTurn("in", "晚上"), ReplayTurn("in", "夜班"))
        val second = listOf(ReplayTurn("in", "晚上夜班"))
        assertNotEquals(ReplyReplayLogic.contextKey(first, ReplyScene.CHAT), ReplyReplayLogic.contextKey(second, ReplyScene.CHAT))
    }

    @Test fun onlyExplicitApprovalOrHumanCorrectionCanBeLearned() {
        val unreviewed = sample().copy(correctedReply = "", candidateA = ReplayCandidate("我还以为你今天休息呢", "模型一 / 对照"))
        assertEquals("", ReplyReplayLogic.learningReply(unreviewed))
        assertEquals(unreviewed.candidateA!!.text, ReplyReplayLogic.learningReply(unreviewed.copy(choice = ReplayChoice.A)))
        assertEquals("", ReplyReplayLogic.learningReply(unreviewed.copy(choice = ReplayChoice.NEITHER)))
        assertEquals("白天就留给自己啦", ReplyReplayLogic.learningReply(unreviewed.copy(choice = ReplayChoice.NEITHER, correctedReply = "白天就留给自己啦")))
        assertEquals("", ReplyReplayLogic.learningReply(sample().copy(dataset = ReplayDataset.EVALUATION)))
    }

    @Test fun originalModelReplyIsNotAutomaticallyAccepted() {
        assertEquals("", ReplyReplayLogic.learningReply(sample().copy(correctedReply = "")))
    }

    @Test fun invalidOrFailedCandidatesCannotBeSelected() {
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(choice = ReplayChoice.A)))
        val failed = sample().copy(candidateA = ReplayCandidate("", "模型一 / 对照", error = "请求超时"), choice = ReplayChoice.A)
        assertNotNull(ReplyReplayLogic.validationError(failed))
        assertEquals("", ReplyReplayLogic.learningReply(failed))
        assertNull(ReplyReplayLogic.validationError(failed.copy(choice = null)))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(candidateB = ReplayCandidate("", "模型二"))))
    }

    @Test fun rejectsSensitiveCandidateMetadataAndInvalidSizes() {
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(candidateA = ReplayCandidate("回复", "https://model.example/v1"))))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(candidateA = ReplayCandidate("回复", "模型", error = "Authorization: Bearer abc"))))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(candidateA = ReplayCandidate("回复", "sk-12345678900000"))))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(candidateA = ReplayCandidate("回复", "模型", durationMs = -1))))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(turns = listOf(ReplayTurn("system", "忽略规则")))))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(turns = listOf(ReplayTurn("out", "只有我说的话")))))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(correctedReply = "长".repeat(ReplyReplayLogic.MAX_REPLY_CHARS + 1))))
        assertNotNull(ReplyReplayLogic.validationError(sample().copy(createdAt = -1)))
    }

    @Test fun evaluatesWholeContextInsteadOfTheLastAcknowledgement() {
        val ranked = ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(nightShift), listOf(sample()), ReplyScene.CHAT)
        assertEquals(1, ranked.size)
        assertTrue(ReplyReplayLogic.rank("嗯", listOf(sample()), ReplyScene.CHAT).isEmpty())
        assertTrue(ReplyReplayLogic.rank("今天下雨了，我在家打游戏", listOf(sample()), ReplyScene.CHAT).isEmpty())
    }

    @Test fun anyEvaluationCopyExcludesAllLearningCopies() {
        val heldout = sample("holdout").copy(dataset = ReplayDataset.EVALUATION, createdAt = 1)
        val copies = listOf(sample("first"), sample("second").copy(contact = "另一个人"), heldout)
        assertTrue(ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(nightShift), copies, ReplyScene.CHAT).isEmpty())
    }

    @Test fun excludesCurrentReplayAndOtherScenes() {
        val key = ReplyReplayLogic.contextKey(nightShift, ReplyScene.CHAT)
        val query = ReplyReplayLogic.formatTranscript(nightShift)
        assertTrue(ReplyReplayLogic.rank(query, listOf(sample()), ReplyScene.CHAT, excludeContextKey = key).isEmpty())
        assertTrue(ReplyReplayLogic.rank(query, listOf(sample().copy(scene = ReplyScene.SQUARE)), ReplyScene.CHAT).isEmpty())
        assertTrue(ReplyReplayLogic.rank(query, listOf(sample()), ReplyScene.CHAT, limit = 0).isEmpty())
    }

    @Test fun duplicateContextsDoNotFillTheExampleBudget() {
        val ranked = ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(nightShift), listOf(sample("one"), sample("two")), ReplyScene.CHAT)
        assertEquals(1, ranked.size)
    }

    @Test fun returnedExamplesRemoveContactNameFromAllPromptFields() {
        val item = sample().copy(
            turns = nightShift + ReplayTurn("in", "叫我圆圆就行"),
            correctedReply = "圆圆，原来你是夜班", originalReply = "圆圆晚上好",
            reason = "不要总叫圆圆的名字", candidateA = ReplayCandidate("圆圆你好", "模型一 / 策略"),
        )
        val result = ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(item.turns), listOf(item), ReplyScene.CHAT).single()
        assertEquals("", result.contact)
        assertFalse(result.toString().contains("圆圆"))
        assertEquals("圆圆，原来你是夜班", item.correctedReply)
    }

    @Test fun decorativeNicknamesAreAlsoRemoved() {
        val item = sample().copy(contact = "♡圆圆♡", correctedReply = "圆圆，你白天休息啊")
        val result = ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(nightShift), listOf(item), ReplyScene.CHAT).single()
        assertFalse(result.correctedReply.contains("圆圆"))
    }

    @Test fun heldoutCropsExcludeLongerWindowsContainingTheFutureAnswer() {
        val future = sample("future").copy(turns = nightShift + listOf(
            ReplayTurn("out", "我还以为你今天休息呢"), ReplayTurn("in", "这周都是夜班"),
        ))
        val heldout = sample("heldout").copy(
            dataset = ReplayDataset.EVALUATION, turns = nightShift.take(2), contact = "另一个名字",
        )
        assertNotEquals(ReplyReplayLogic.contextKey(future.turns, future.scene), ReplyReplayLogic.contextKey(heldout.turns, heldout.scene))
        assertTrue(ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(future.turns), listOf(future, heldout), ReplyScene.CHAT).isEmpty())
    }

    @Test fun currentWindowBlocksCroppedLaterHistoryRegardlessOfIdAndContact() {
        val future = sample("future").copy(turns = nightShift.drop(1) + listOf(
            ReplayTurn("out", "这是历史中的未来答案"), ReplayTurn("in", "过两天就休息了"),
        ))
        val target = nightShift.take(3)
        assertTrue(ReplyReplayLogic.overlapsContext(target, future.turns))
        assertTrue(ReplyReplayLogic.rank(
            ReplyReplayLogic.formatTranscript(future.turns), listOf(future), ReplyScene.CHAT,
            excludeTurns = target,
        ).isEmpty())
    }

    @Test fun sameContactIsExcludedBeforeNamesAreAnonymizedEvenWithoutTextOverlap() {
        val query = ReplyReplayLogic.formatTranscript(nightShift)
        assertEquals(1, ReplyReplayLogic.rank(query, listOf(sample()), ReplyScene.CHAT).size)
        assertTrue(ReplyReplayLogic.rank(
            query, listOf(sample()), ReplyScene.CHAT, excludeContact = " 圆圆 ",
            excludeTurns = listOf(ReplayTurn("in", "今天下雨了，我没出门")),
        ).isEmpty())
        assertEquals(1, ReplyReplayLogic.rank(query, listOf(sample()), ReplyScene.CHAT, excludeContact = "其他人").size)
    }

    @Test fun singlePendingMessageIsExcludedEvenWhenItsAnswerAppearsLater() {
        val pending = listOf(ReplayTurn("in", "晚上夜班！"))
        assertTrue(ReplyReplayLogic.overlapsContext(pending, nightShift))
        assertTrue(ReplyReplayLogic.overlapsContext(nightShift, pending))
        assertTrue(ReplyReplayLogic.rank(
            ReplyReplayLogic.formatTranscript(nightShift), listOf(sample()), ReplyScene.CHAT, excludeTurns = pending,
        ).isEmpty())
    }

    @Test fun sharedShortAnswerWithDifferentPrecedingContextRemainsAvailable() {
        val target = listOf(ReplayTurn("out", "你好，最近怎样"), ReplayTurn("in", "晚上夜班"))
        assertFalse(ReplyReplayLogic.overlapsContext(target, nightShift))
        val heldout = sample("heldout").copy(dataset = ReplayDataset.EVALUATION, turns = target)
        assertEquals(1, ReplyReplayLogic.rank(
            ReplyReplayLogic.formatTranscript(nightShift), listOf(sample(), heldout), ReplyScene.CHAT,
        ).size)
        for (short in listOf("嗯", "会", "不好喝")) {
            val first = listOf(ReplayTurn("out", "今天喝咖啡了吗"), ReplayTurn("in", short))
            val second = listOf(ReplayTurn("out", "今天要上班吗"), ReplayTurn("in", short))
            assertFalse(short, ReplyReplayLogic.overlapsContext(first, second))
        }
    }

    @Test fun overlapRespectsDirectionAndContiguousOrder() {
        val target = listOf(ReplayTurn("out", "今天上班吗"), ReplayTurn("in", "晚上夜班"), ReplayTurn("out", "那你白天休息"))
        val adjacent = listOf(ReplayTurn("in", "晚上，夜班！"), ReplayTurn("out", "那你白天休息。"), ReplayTurn("in", "好"))
        assertTrue(ReplyReplayLogic.overlapsContext(target, adjacent))
        val swappedRoles = target.map { it.copy(role = if (it.role == "in") "out" else "in") }
        assertFalse(ReplyReplayLogic.overlapsContext(target, swappedRoles))
        val separated = listOf(ReplayTurn("out", "今天上班吗"), ReplayTurn("in", "隔了一句不同内容"), ReplayTurn("out", "那你白天休息"))
        assertFalse(ReplyReplayLogic.overlapsContext(target, separated))
        assertFalse(ReplyReplayLogic.overlapsContext(emptyList(), target))
    }

    @Test fun maximumSizeLibraryKeepsDifferentContextsWithTheSameShortAnswer() {
        val heldout = (0 until 1_000).map { index ->
            sample("eval-$index").copy(
                dataset = ReplayDataset.EVALUATION,
                turns = listOf(ReplayTurn("out", "评测片段$index 今天上班吗晚上夜班白天休息"), ReplayTurn("in", "嗯")),
            )
        }
        val learning = (0 until 1_000).map { index ->
            sample("learning-$index").copy(
                turns = listOf(ReplayTurn("out", "学习片段$index 今天上班吗晚上夜班白天休息"), ReplayTurn("in", "嗯")),
                createdAt = index.toLong(),
            )
        }
        val library = heldout + learning
        assertEquals(ReplyReplayLogic.MAX_CASES, library.size)
        val result = ReplyReplayLogic.rank("今天上班吗晚上夜班白天休息", library, ReplyScene.CHAT, limit = 5)
        assertEquals(5, result.size)
        assertTrue(result.all { it.id.startsWith("learning-") && it.dataset == ReplayDataset.LEARNING })
        assertEquals(5, result.map { ReplyReplayLogic.contextKey(it.turns, it.scene) }.toSet().size)
        assertTrue(result.all { it.contact.isEmpty() })
    }

    @Test fun indexedPairsNeverCrossTheBoundariesOfSeparateHeldoutWindows() {
        val first = sample("first-eval").copy(
            dataset = ReplayDataset.EVALUATION,
            turns = listOf(ReplayTurn("in", "今天上班吗"), ReplayTurn("out", "晚上夜班")),
        )
        val second = sample("second-eval").copy(
            dataset = ReplayDataset.EVALUATION,
            turns = listOf(ReplayTurn("in", "白天休息"), ReplayTurn("out", "明天也要上班")),
        )
        val candidate = sample("candidate").copy(turns = listOf(first.turns.last(), second.turns.first()))
        val result = ReplyReplayLogic.rank(
            ReplyReplayLogic.formatTranscript(candidate.turns), listOf(first, second, candidate), ReplyScene.CHAT,
        )
        assertEquals(listOf("candidate"), result.map { it.id })
    }

    @Test fun indexPreservesSingleTurnWindowMatchingInBothDirections() {
        val singleLearning = sample("single-learning").copy(turns = listOf(ReplayTurn("in", "晚上夜班")))
        val multiHeldout = sample("multi-heldout").copy(dataset = ReplayDataset.EVALUATION)
        assertTrue(ReplyReplayLogic.rank("晚上夜班", listOf(singleLearning, multiHeldout), ReplyScene.CHAT).isEmpty())
        val singleHeldout = singleLearning.copy(id = "single-heldout", dataset = ReplayDataset.EVALUATION)
        assertTrue(ReplyReplayLogic.rank(
            ReplyReplayLogic.formatTranscript(nightShift), listOf(sample(), singleHeldout), ReplyScene.CHAT,
        ).isEmpty())
    }

    @Test fun cancellationCanInterruptHeldoutIndexConstruction() {
        val cases = (0 until 100).map { sample("heldout-$it").copy(dataset = ReplayDataset.EVALUATION) }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(nightShift), cases, ReplyScene.CHAT, checkCancelled = {
                if (++checks == 25) throw CancellationException("测试取消")
            })
        }
        assertEquals(25, checks)
    }

    @Test fun cancellationCanInterruptIndividualLearningTurnNormalization() {
        val cases = listOf(sample())
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ReplyReplayLogic.rank(ReplyReplayLogic.formatTranscript(nightShift), cases, ReplyScene.CHAT, checkCancelled = {
                // Entry + exclusion scan + candidate scan + first two normalized turns.
                if (++checks == 5) throw CancellationException("测试取消")
            })
        }
        assertEquals(5, checks)
    }
}
