package com.soulbot.app

import okhttp3.Call
import java.util.concurrent.CancellationException

/** Cancellation belongs to one request owner; never cancels the automation's other calls. */
class ModelCallCancellation {
    @Volatile private var cancelled = false
    private var call: Call? = null

    @Synchronized fun cancel() {
        cancelled = true
        call?.cancel()
    }

    fun isCancelled(): Boolean = cancelled

    fun throwIfCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("回放已取消")
        }
    }

    @Synchronized internal fun attach(value: Call) {
        if (cancelled) value.cancel() else call = value
    }

    @Synchronized internal fun detach(value: Call) {
        if (call === value) call = null
    }
}
