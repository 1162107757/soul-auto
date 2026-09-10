package com.soulbot.app

object SquareBrowsePolicy {
    const val MAX_TRANSIENT_ATTEMPTS = 2

    fun viewportKey(text: String, cardTop: Int, bucketSizePx: Int): String {
        val safeBucket = bucketSizePx.coerceAtLeast(1)
        return "${comparableText(text).take(120)}@${cardTop / safeBucket}"
    }

    fun shouldRetryTransientFailure(attemptCount: Int): Boolean =
        attemptCount < MAX_TRANSIENT_ATTEMPTS
}
