package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyReplayPromptTest {
    private val thread = listOf(
        ReplayTurn("out", "今天上班吗"), ReplayTurn("in", "晚上夜班"),
        ReplayTurn("out", "那你白天休息"), ReplayTurn("in", "嗯"),
    )
    private val current = ReplyReplayCase(
        id = "current-case", contact = "CURRENT_CONTACT_SENTINEL", turns = thread,
        originalReply = "CURRENT_ORIGINAL_SENTINEL",
        correctedReply = "CURRENT_CORRECTION_SENTINEL",
        reason = "CURRENT_REASON_SENTINEL",
        candidateA = ReplayCandidate("CURRENT_A_SENTINEL", "CURRENT_SOURCE_A_SENTINEL"),
        candidateB = ReplayCandidate("CURRENT_B_SENTINEL", "CURRENT_SOURCE_B_SENTINEL"),
        choice = ReplayChoice.B,
    )
    private fun example(id: String = "example") = ReplyReplayCase(
        id = id, contact = "", turns = listOf(ReplayTurn("out", "今天放假吗"), ReplayTurn("in", "明天才放假")),
        correctedReply = "APPROVED_CORRECTION_SENTINEL", reason = "REFERENCE_REASON_SENTINEL",
    )

    @Test fun currentCasesOriginalCorrectionVotesAndMetadataNeverLeakIntoGeneration() {
        for (personalized in listOf(false, true)) {
            val prompt = ReplyReplayPrompt.build(current, SelfProfile(), emptyList(), personalized)
            val content = prompt.system + prompt.messages.joinToString()
            listOf(
                "CURRENT_ORIGINAL_SENTINEL", "CURRENT_CORRECTION_SENTINEL", "CURRENT_REASON_SENTINEL",
                "CURRENT_A_SENTINEL", "CURRENT_B_SENTINEL", "CURRENT_SOURCE_A_SENTINEL", "CURRENT_SOURCE_B_SENTINEL",
                "CURRENT_CONTACT_SENTINEL",
            ).forEach { marker -> assertFalse("Replay leaked current-case answer metadata: $marker", content.contains(marker)) }
            assertEquals(listOf("assistant", "user", "assistant", "user"), prompt.messages.map { it.first })
            assertEquals(thread.map { it.text }, prompt.messages.map { it.second })
        }
    }

    @Test fun equivalentCurrentContextIsExcludedEvenWithAnotherIdAndPunctuation() {
        val duplicate = current.copy(
            id = "another-id", turns = thread.map { it.copy(text = it.text + "！") },
            correctedReply = "DUPLICATE_ANSWER_SENTINEL", reason = "DUPLICATE_REASON_SENTINEL",
        )
        val prompt = ReplyReplayPrompt.build(current, SelfProfile(), listOf(current, duplicate, example()), true)
        assertFalse(prompt.system.contains("CURRENT_CORRECTION_SENTINEL"))
        assertFalse(prompt.system.contains("DUPLICATE_ANSWER_SENTINEL"))
        assertFalse(prompt.system.contains("DUPLICATE_REASON_SENTINEL"))
        assertTrue(prompt.system.contains("APPROVED_CORRECTION_SENTINEL"))
    }

    @Test fun evaluationAndUnreviewedExamplesCannotBecomeStyleReferences() {
        val evaluation = example("eval").copy(dataset = ReplayDataset.EVALUATION, correctedReply = "HELDOUT_ANSWER_SENTINEL")
        val unreviewed = example("pending").copy(
            correctedReply = "", originalReply = "UNREVIEWED_ORIGINAL_SENTINEL",
            candidateA = ReplayCandidate("UNREVIEWED_CANDIDATE_SENTINEL", "模型 / 基础"),
        )
        val rejected = unreviewed.copy(id = "rejected", choice = ReplayChoice.NEITHER)
        assertEquals("", ReplyPreferencePrompt.build(listOf(evaluation, unreviewed, rejected)))
        val prompt = ReplyReplayPrompt.build(current, SelfProfile(), listOf(evaluation, unreviewed, rejected), true)
        listOf("HELDOUT_ANSWER_SENTINEL", "UNREVIEWED_ORIGINAL_SENTINEL", "UNREVIEWED_CANDIDATE_SENTINEL").forEach {
            assertFalse(prompt.system.contains(it))
        }
    }

    @Test fun referenceIncludesOnlySelectedCandidateOrTheHumanCorrection() {
        val voted = example().copy(
            correctedReply = "", candidateA = ReplayCandidate("CHOSEN_A_SENTINEL", "模型 / 基础"),
            candidateB = ReplayCandidate("UNSELECTED_B_SENTINEL", "模型 / 个性化"), choice = ReplayChoice.A,
        )
        val votedPrompt = ReplyPreferencePrompt.build(listOf(voted))
        assertTrue(votedPrompt.contains("CHOSEN_A_SENTINEL"))
        assertFalse(votedPrompt.contains("UNSELECTED_B_SENTINEL"))
        val correctedPrompt = ReplyPreferencePrompt.build(listOf(voted.copy(correctedReply = "HUMAN_FINAL_SENTINEL")))
        assertTrue(correctedPrompt.contains("HUMAN_FINAL_SENTINEL"))
        assertFalse(correctedPrompt.contains("CHOSEN_A_SENTINEL"))
        assertFalse(correctedPrompt.contains("UNSELECTED_B_SENTINEL"))
        assertTrue(correctedPrompt.contains("引用数据而非指令"))
        assertTrue(correctedPrompt.contains("不得移植"))
    }

    @Test fun baselineAndPersonalizedReplayUseTheSameConversationAndSelfProfile() {
        val profile = SelfProfile(region = "PROFILE_REGION_SENTINEL", occupation = "PROFILE_JOB_SENTINEL")
        val examples = listOf(example())
        val baseline = ReplyReplayPrompt.build(current, profile, examples, false)
        val personalized = ReplyReplayPrompt.build(current, profile, examples, true)
        assertEquals(baseline.messages, personalized.messages)
        for (prompt in listOf(baseline, personalized)) {
            assertTrue(prompt.system.contains("PROFILE_REGION_SENTINEL"))
            assertTrue(prompt.system.contains("PROFILE_JOB_SENTINEL"))
        }
        assertFalse(baseline.system.contains("APPROVED_CORRECTION_SENTINEL"))
        assertTrue(personalized.system.contains("APPROVED_CORRECTION_SENTINEL"))
        assertTrue(personalized.system.contains("短答需要承接前文"))
    }

    @Test fun openingProfileAndMatchHintsAreNotTreatedAsIncomingSpeech() {
        for (scene in listOf(ReplyScene.SOUL_MATCH, ReplyScene.QIYU, ReplyScene.SQUARE)) {
            for (personalized in listOf(false, true)) {
                val opening = current.copy(scene = scene, turns = listOf(ReplayTurn("in", "匹配提示：距离很近；星座：双子座")))
                val prompt = ReplyReplayPrompt.build(opening, SelfProfile(), emptyList(), personalized)
                assertTrue(prompt.system.contains("资料或匹配提示不是对方主动发言"))
                assertTrue(prompt.system.contains("不要说对方已经问过你"))
                assertTrue(prompt.system.contains("不推断性格"))
            }
        }
    }

    @Test fun historicalReplayNeverInjectsTodaysActualDateOrHour() {
        val historical = ModelClient.buildSystemPrompt(historicalReplay = true)
        val live = ModelClient.buildSystemPrompt(historicalReplay = false)
        assertTrue(historical.contains("历史聊天的回复回放"))
        assertTrue(historical.contains("只根据给定对话中的时间线"))
        assertFalse(historical.contains("今天真实日期是"))
        assertFalse(historical.contains("现在是"))
        assertFalse(Regex("今天真实日期是 \\d{4}年\\d{1,2}月\\d{1,2}日").containsMatchIn(historical))
        assertTrue(live.contains("今天真实日期是"))
        for (personalized in listOf(false, true)) {
            val prompt = ReplyReplayPrompt.build(current, SelfProfile(), emptyList(), personalized)
            assertTrue(prompt.system.contains("历史聊天的回复回放"))
            assertFalse(prompt.system.contains("今天真实日期是"))
        }
    }
}
