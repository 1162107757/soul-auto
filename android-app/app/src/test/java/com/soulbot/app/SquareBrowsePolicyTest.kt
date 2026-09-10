package com.soulbot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareBrowsePolicyTest {
    @Test
    fun sameTextAtDifferentPositions_doesNotCollapseDifferentCards() {
        val first = SquareBrowsePolicy.viewportKey("今天下班啦", 120, 48)
        val second = SquareBrowsePolicy.viewportKey("今天下班啦", 520, 48)

        assertNotEquals(first, second)
    }

    @Test
    fun transientFailure_getsOneRetryBeforeSkippingCurrentViewport() {
        assertTrue(SquareBrowsePolicy.shouldRetryTransientFailure(0))
        assertTrue(SquareBrowsePolicy.shouldRetryTransientFailure(1))
        assertFalse(SquareBrowsePolicy.shouldRetryTransientFailure(2))
    }
}
