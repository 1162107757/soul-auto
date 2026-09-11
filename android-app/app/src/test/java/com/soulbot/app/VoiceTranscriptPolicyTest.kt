package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptPolicyTest {
    @Test
    fun transcriptIsBoundToNearestVoiceInsteadOfAdjacentMessage() {
        val firstVoice = UiRegion(120, 200, 520, 300)
        val secondVoice = UiRegion(120, 600, 520, 700)
        val transcripts = listOf(
            TranscriptCandidate("第一条语音内容", UiRegion(140, 310, 560, 390)),
            TranscriptCandidate("第二条语音内容", UiRegion(140, 710, 560, 790)),
        )

        assertEquals(
            "第二条语音内容",
            VoiceTranscriptPolicy.transcriptFor(
                secondVoice,
                listOf(firstVoice, secondVoice),
                transcripts,
                maxHorizontalDistance = 300,
                maxVerticalDistance = 250,
            ),
        )
    }

    @Test
    fun farAwayTranscriptIsNotBorrowed() {
        val voice = UiRegion(100, 100, 400, 200)
        assertEquals(
            "",
            VoiceTranscriptPolicy.transcriptFor(
                voice,
                listOf(voice),
                listOf(TranscriptCandidate("别人的消息", UiRegion(700, 900, 1000, 980))),
                maxHorizontalDistance = 300,
                maxVerticalDistance = 250,
            ),
        )
    }
}
