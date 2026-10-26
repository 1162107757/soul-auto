package com.soulbot.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class ModelCallCancellationTest {
    private val client = OkHttpClient()

    // Calls are constructed but never executed/enqueued: these tests do not use a network.
    private fun newCall() = client.newCall(Request.Builder().url("https://example.invalid/replay-test").build())

    @Test fun cancellationOnlyCancelsItsAttachedCallNotOtherCallsOnTheSharedClient() {
        val current = newCall()
        val unrelated = newCall()
        val owner = ModelCallCancellation()
        owner.attach(current)
        owner.cancel()
        assertTrue(owner.isCancelled())
        assertTrue(current.isCanceled())
        assertFalse(unrelated.isCanceled())
        assertThrows(CancellationException::class.java) { owner.throwIfCancelled() }
    }

    @Test fun correctlyDetachedCallIsNotCancelledLater() {
        val call = newCall()
        val owner = ModelCallCancellation()
        owner.attach(call)
        owner.detach(call)
        owner.cancel()
        assertFalse(call.isCanceled())
        assertTrue(owner.isCancelled())
    }

    @Test fun detachFromAnOlderCallCannotClearTheCurrentCall() {
        val previous = newCall()
        val current = newCall()
        val owner = ModelCallCancellation()
        owner.attach(previous)
        owner.attach(current)
        owner.detach(previous)
        owner.cancel()
        assertTrue(current.isCanceled())
        assertFalse(previous.isCanceled())
    }

    @Test fun attachAfterCancellationImmediatelyCancelsThatCall() {
        val owner = ModelCallCancellation()
        owner.cancel()
        val call = newCall()
        owner.attach(call)
        assertTrue(call.isCanceled())
        assertThrows(CancellationException::class.java) { owner.throwIfCancelled() }
    }

    @Test fun differentReplayOwnersHaveIndependentCancellationState() {
        val first = ModelCallCancellation()
        val second = ModelCallCancellation()
        val firstCall = newCall()
        val secondCall = newCall()
        first.attach(firstCall)
        second.attach(secondCall)
        first.cancel()
        assertTrue(firstCall.isCanceled())
        assertFalse(second.isCancelled())
        assertFalse(secondCall.isCanceled())
        second.throwIfCancelled()
    }

    @Test fun repeatedCancellationAndDetachAreSafe() {
        val call = newCall()
        val owner = ModelCallCancellation()
        owner.attach(call)
        owner.cancel()
        owner.cancel()
        owner.detach(call)
        owner.detach(call)
        assertTrue(call.isCanceled())
        assertTrue(owner.isCancelled())
    }

    @Test fun workerInterruptionIsRespectedWithoutChangingAnotherOwnersState() {
        val worker = Thread.currentThread()
        val initiallyInterrupted = worker.isInterrupted
        try {
            worker.interrupt()
            val owner = ModelCallCancellation()
            assertThrows(CancellationException::class.java) { owner.throwIfCancelled() }
            assertFalse(owner.isCancelled())
        } finally {
            Thread.interrupted()
            if (initiallyInterrupted) worker.interrupt()
        }
    }
}
