package com.soulbot.app

import kotlin.math.abs

data class TranscriptCandidate(
    val text: String,
    val bounds: UiRegion,
)

/** Associates a rendered transcript with its own voice bubble, not a nearby message. */
object VoiceTranscriptPolicy {
    fun transcriptFor(
        targetVoice: UiRegion,
        visibleVoices: List<UiRegion>,
        transcripts: List<TranscriptCandidate>,
        maxHorizontalDistance: Int,
        maxVerticalDistance: Int,
    ): String {
        val voices = visibleVoices.ifEmpty { listOf(targetVoice) }
        val targetIndex = voices.indices.minByOrNull { associationScore(voices[it], targetVoice) }
            ?: return ""

        return transcripts.asSequence()
            .filter { it.text.isNotBlank() }
            .filter { plausible(targetVoice, it.bounds, maxHorizontalDistance, maxVerticalDistance) }
            .filter { transcript ->
                val owner = voices.indices.minByOrNull {
                    associationScore(voices[it], transcript.bounds)
                }
                owner == targetIndex
            }
            .minByOrNull { associationScore(targetVoice, it.bounds) }
            ?.text
            ?.trim()
            .orEmpty()
    }

    private fun plausible(
        voice: UiRegion,
        transcript: UiRegion,
        maxHorizontalDistance: Int,
        maxVerticalDistance: Int,
    ): Boolean {
        val horizontal = abs(voice.left - transcript.left)
        val vertical = intervalDistance(voice.top, voice.bottom, transcript.top, transcript.bottom)
        return horizontal <= maxHorizontalDistance && vertical <= maxVerticalDistance
    }

    private fun associationScore(first: UiRegion, second: UiRegion): Long {
        val vertical = intervalDistance(first.top, first.bottom, second.top, second.bottom)
        val horizontal = abs(first.left - second.left)
        return vertical.toLong() * 4L + horizontal
    }

    private fun intervalDistance(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Int =
        when {
            firstEnd < secondStart -> secondStart - firstEnd
            secondEnd < firstStart -> firstStart - secondEnd
            else -> 0
        }
}
