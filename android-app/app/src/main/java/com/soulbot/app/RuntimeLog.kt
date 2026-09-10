package com.soulbot.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent diagnostic trail for the automation state machine.
 *
 * Android's logcat buffer is often overwritten before a problem is reported. This
 * file intentionally stores only workflow/status information: never chat content,
 * profile values, API keys, or generated replies.
 */
object RuntimeLog {
    private const val TAG = "SoulBot"
    private const val FILE_NAME = "runtime-events.log"
    private const val MAX_BYTES = 96 * 1024
    private const val KEEP_BYTES = 64 * 1024
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun record(context: Context, event: String, error: Throwable? = null) {
        val safeEvent = event.replace('\n', ' ').replace('\r', ' ').take(500)
        if (error == null) {
            Log.d(TAG, safeEvent)
        } else {
            Log.e(TAG, safeEvent, error)
        }

        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            trimIfNeeded(file)
            val errorSummary = error?.let {
                " | ${it.javaClass.simpleName}: ${it.message.orEmpty().replace('\n', ' ').take(240)}"
            }.orEmpty()
            file.appendText("${timestamp.format(Date())} $safeEvent$errorSummary\n")
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val bytes = file.readBytes()
        val start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
        val tail = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
        val firstLineBreak = tail.indexOf('\n')
        file.writeText(if (firstLineBreak >= 0) tail.substring(firstLineBreak + 1) else tail)
    }
}
