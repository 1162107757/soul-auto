package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class DiagnosticSamplingTest {
    private class ManualExecutor : AbstractExecutorService() {
        val queued = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            queued += command
        }
        fun runNext() = queued.removeAt(0).run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return queued.toMutableList().also { queued.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queued.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated()
    }

    @Test fun eventStormQueuesOnlyOneSampleAndDoesNotRunOnRequestingThread() {
        val executor = ManualExecutor()
        var now = 0L
        var samples = 0
        var report: DiagnosticDispatchStats? = null
        val sampler = CoalescingDiagnosticSampler(
            sample = { samples++; now += 25 }, onComplete = { stats, _ -> report = stats },
            clock = { now }, executor = executor,
        )
        assertTrue(sampler.request(5_000))
        repeat(200) { assertFalse(sampler.request(5_000)) }
        assertEquals(1, executor.queued.size)
        assertEquals(0, samples)
        executor.runNext()
        assertEquals(1, samples)
        assertEquals(DiagnosticDispatchStats(1, 200, 25), report)
        sampler.close()
    }

    @Test fun throttleStartsAfterCompletionAndDoesNotSchedulePollingWhileIdle() {
        val executor = ManualExecutor()
        var now = 0L
        val sampler = CoalescingDiagnosticSampler(sample = { now += 4_000 }, clock = { now }, executor = executor)
        assertTrue(sampler.request(5_000))
        executor.runNext()
        now = 6_000
        assertFalse(sampler.request(5_000))
        assertTrue(executor.queued.isEmpty())
        now = 9_000
        assertTrue(executor.queued.isEmpty())
        assertTrue(sampler.request(5_000))
        sampler.close()
    }

    @Test fun arrivalsDuringSampleAndCompletionLoggingAreCoalesced() {
        val executor = ManualExecutor()
        lateinit var sampler: CoalescingDiagnosticSampler
        sampler = CoalescingDiagnosticSampler(
            sample = { assertFalse(sampler.request(0)) },
            onComplete = { _, _ -> assertFalse(sampler.request(0)) },
            clock = { 0 }, executor = executor,
        )
        assertTrue(sampler.request(0))
        executor.runNext()
        assertTrue(executor.queued.isEmpty())
        assertTrue(sampler.request(0))
        sampler.close()
    }

    @Test fun closingDropsPendingWorkAndRejectsLaterEvents() {
        val executor = ManualExecutor()
        var samples = 0
        val sampler = CoalescingDiagnosticSampler(sample = { samples++ }, clock = { 0 }, executor = executor)
        sampler.request(0)
        sampler.close()
        assertTrue(sampler.isClosed())
        assertTrue(executor.queued.isEmpty())
        assertFalse(sampler.request(0))
        assertEquals(0, samples)
    }

    @Test fun sampleFailureIsReportedAndDoesNotLeaveTheSamplerPermanentlyBusy() {
        val executor = ManualExecutor()
        var reported = ""
        val sampler = CoalescingDiagnosticSampler(
            sample = { throw IllegalStateException("synthetic") },
            onComplete = { _, error -> reported = error?.javaClass?.simpleName.orEmpty() },
            clock = { 0 }, executor = executor,
        )
        assertTrue(sampler.request(0))
        executor.runNext()
        assertEquals("IllegalStateException", reported)
        assertTrue(sampler.request(0))
        sampler.close()
    }

    @Test fun rejectedExecutorDoesNotPropagateToTheAccessibilityCallback() {
        val executor = ManualExecutor().apply { shutdown() }
        val sampler = CoalescingDiagnosticSampler(sample = {}, clock = { 0 }, executor = executor)
        assertFalse(sampler.request(0))
        assertFalse(sampler.request(0))
        sampler.close()
    }

    @Test fun invisibleNodesStillConsumeTheVisitBudget() {
        var reads = 0
        var released = 0
        val result = DiagnosticTreeSampler.scan(
            root = 0, maxNodes = 100, timeBudgetMs = 100,
            clock = { 0 }, cancelled = { false },
            childCount = { 1 }, childAt = { parent, _ -> reads++; parent + 1 },
            visit = { /* All nodes are invisible: none are counted as visible. */ }, release = { released++ },
        )
        assertEquals(100, result.visited)
        assertEquals(99, reads)
        assertEquals(100, released)
        assertTrue(result.truncated)
    }

    @Test fun broadTreesCannotBuildAnUnboundedPendingQueue() {
        var childReads = 0
        val result = DiagnosticTreeSampler.scan(
            root = 0, maxNodes = 16, timeBudgetMs = 100,
            clock = { 0 }, cancelled = { false },
            childCount = { if (it == 0) 10_000 else 0 },
            childAt = { _, index -> childReads++; index + 1 }, visit = {},
        )
        assertEquals(16, result.visited)
        assertEquals(15, childReads)
        assertTrue(result.truncated)
    }

    @Test fun elapsedBudgetStopsBeforeAnotherChildLookup() {
        var now = 0L
        var childReads = 0
        var released = 0
        val result = DiagnosticTreeSampler.scan(
            root = 0, maxNodes = 100, timeBudgetMs = 25,
            clock = { now }, cancelled = { false },
            childCount = { 1 }, childAt = { parent, _ -> childReads++; parent + 1 },
            visit = { now += 10 }, release = { released++ },
        )
        assertEquals(3, result.visited)
        assertEquals(2, childReads)
        assertEquals(3, released)
        assertTrue(result.truncated)
    }

    @Test fun closingDuringTraversalStopsAndReleasesQueuedNodes() {
        var cancelled = false
        var released = 0
        val result = DiagnosticTreeSampler.scan(
            root = 0, maxNodes = 100, timeBudgetMs = 100,
            clock = { 0 }, cancelled = { cancelled },
            childCount = { if (it == 0) 5 else 0 }, childAt = { _, index -> index + 1 },
            visit = { if (it == 1) cancelled = true }, release = { released++ },
        )
        assertEquals(2, result.visited)
        assertEquals(6, released)
        assertTrue(result.truncated)
    }

    @Test fun alreadyClosedTraversalDoesNotReadTheTree() {
        var released = 0
        val result = DiagnosticTreeSampler.scan(
            root = 0, maxNodes = 100, timeBudgetMs = 100,
            clock = { 0 }, cancelled = { true },
            childCount = { error("unexpected child access") }, childAt = { _, _ -> error("unexpected child access") },
            visit = { error("unexpected visit") }, release = { released++ },
        )
        assertEquals(0, result.visited)
        assertEquals(1, released)
        assertTrue(result.truncated)
    }

    @Test fun completeSmallTreeIsNotMarkedTruncated() {
        val result = DiagnosticTreeSampler.scan(
            root = 0, maxNodes = 100, timeBudgetMs = 100,
            clock = { 0 }, cancelled = { false },
            childCount = { if (it < 4) 1 else 0 }, childAt = { parent, _ -> parent + 1 }, visit = {},
        )
        assertEquals(5, result.visited)
        assertFalse(result.truncated)
    }
}
