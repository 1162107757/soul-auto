package com.soulbot.app

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class DiagnosticDispatchStats(val completed: Long, val dropped: Long, val durationMs: Long)

/** Event callbacks enqueue at most one sample and never wait for tree access or disk I/O. */
class CoalescingDiagnosticSampler(
    private val sample: () -> Unit,
    private val onComplete: (DiagnosticDispatchStats, Throwable?) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { action ->
        Thread(action, "SoulBot-Diagnostics").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    },
) {
    private val stateLock = Any()
    @Volatile private var closed = false
    private var inFlight = false
    private var nextAllowedAt = Long.MIN_VALUE
    private var completed = 0L
    private var dropped = 0L

    fun isClosed(): Boolean = closed

    fun request(intervalMs: Long): Boolean {
        synchronized(stateLock) {
            if (closed || inFlight || clock() < nextAllowedAt) {
                dropped++
                return false
            }
            inFlight = true
        }
        try {
            executor.execute {
                val started = clock()
                var failure: Throwable? = null
                try {
                    if (!closed) sample()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    val duration = (clock() - started).coerceAtLeast(0)
                    val stats = synchronized(stateLock) {
                        completed++
                        DiagnosticDispatchStats(completed, dropped, duration)
                    }
                    try {
                        if (!closed) onComplete(stats, failure)
                    } catch (_: Throwable) {
                        // Diagnostics and their logging must not terminate this worker.
                    } finally {
                        synchronized(stateLock) {
                            // Throttle after completion, not start: slow samples cannot trigger a scan storm.
                            nextAllowedAt = clock() + intervalMs.coerceAtLeast(0)
                            inFlight = false
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(stateLock) { inFlight = false; dropped++ }
            return false
        }
        return true
    }

    fun close() {
        synchronized(stateLock) { closed = true }
        executor.shutdownNow()
    }
}

data class DiagnosticTreeScan(val visited: Int, val truncated: Boolean)

/** The budget counts every visited node, including invisible nodes, and also bounds the queue. */
object DiagnosticTreeSampler {
    fun <T : Any> scan(
        root: T,
        maxNodes: Int,
        timeBudgetMs: Long,
        clock: () -> Long,
        cancelled: () -> Boolean,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?,
        visit: (T) -> Unit,
        release: (T) -> Unit = {},
    ): DiagnosticTreeScan {
        val started = clock()
        val pending = ArrayDeque<T>()
        pending.add(root)
        var visited = 0
        var truncated = false
        fun outOfTime() = cancelled() || clock() - started >= timeBudgetMs
        try {
            while (pending.isNotEmpty()) {
                if (visited >= maxNodes || outOfTime()) {
                    truncated = true
                    break
                }
                val node = pending.removeFirst()
                try {
                    visited++
                    visit(node)
                    if (outOfTime()) {
                        truncated = true
                        break
                    }
                    val children = childCount(node).coerceAtLeast(0)
                    for (index in 0 until children) {
                        if (visited + pending.size >= maxNodes || outOfTime()) {
                            truncated = true
                            break
                        }
                        childAt(node, index)?.let(pending::addLast)
                    }
                } finally {
                    release(node)
                }
            }
        } finally {
            while (pending.isNotEmpty()) release(pending.removeFirst())
        }
        return DiagnosticTreeScan(visited, truncated)
    }
}
