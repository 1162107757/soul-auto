package com.soulbot.app

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent, privacy-safe diagnostic logging for the automation runtime.
 *
 * The current log and four archives are kept in app-private storage. Every public
 * operation is fail-safe: logging or exporting must never interrupt automation.
 * Chat text, profile values, generated replies and credentials should not be
 * supplied as fields. A final redaction pass also protects common credential
 * formats if one is accidentally included in a message or exception.
 */
object RuntimeLog {
    private const val TAG = "SoulBot"
    private const val FILE_NAME = "runtime-events.log"
    private const val MAX_ARCHIVES = 4
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_ACTION_CHARS = 1_000
    private const val MAX_FIELD_CHARS = 1_000
    private const val MAX_STACK_CHARS = 8_192
    private const val MAX_READ_CHARS = 3 * 1024 * 1024
    private const val COPY_BUFFER_SIZE = 8 * 1024

    private val sequence = AtomicLong(0L)
    private val sessionId = "${System.currentTimeMillis().toString(36)}-${Process.myPid()}"
    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    data class Stats(
        val fileCount: Int,
        val totalBytes: Long,
        val lineCount: Int,
        val lastModifiedAt: Long,
        val warningCount: Int = 0,
        val errorCount: Int = 0,
    )

    /** Keeps the original API used by existing workflow call sites. */
    fun record(context: Context, event: String, error: Throwable? = null) {
        event(
            context = context,
            category = inferredCategory(event),
            action = event,
            level = when {
                error != null -> Level.ERROR
                event.contains("failed", ignoreCase = true) ||
                    event.contains("crashed", ignoreCase = true) ||
                    event.contains("unconfirmed", ignoreCase = true) ||
                    event.contains("unavailable", ignoreCase = true) ||
                    event.contains("aborted", ignoreCase = true) ||
                    event.contains("cancelled", ignoreCase = true) ||
                    event.contains("rejected", ignoreCase = true) ||
                    event.contains("missing", ignoreCase = true) ||
                    event.contains("skipped", ignoreCase = true) ||
                    event.contains("exhausted", ignoreCase = true) ||
                    event.contains("stalled", ignoreCase = true) -> Level.WARN
                else -> Level.INFO
            },
            error = error,
        )
    }

    private fun inferredCategory(event: String): String {
        val value = event.lowercase(Locale.US)
        return when {
            "model" in value || "generation" in value || "reply quality" in value -> "MODEL"
            "voice" in value || "transcription" in value -> "VOICE"
            "message" in value || "conversation" in value || "unread" in value ||
                "greeting" in value || "memory" in value || "reply" in value -> "MESSAGE"
            "square" in value -> "SQUARE"
            "soul match" in value || "soul-match" in value -> "SOUL_MATCH"
            "floating" in value -> "FLOATING_UI"
            "screen" in value || "page" in value || "navigation" in value -> "PAGE"
            "service" in value || "worker" in value || "task" in value ||
                "application" in value || "accessibility" in value -> "SERVICE"
            else -> "AUTOMATION"
        }
    }

    fun debug(
        context: Context,
        category: String,
        action: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = event(context, category, action, Level.DEBUG, fields)

    fun info(
        context: Context,
        category: String,
        action: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = event(context, category, action, Level.INFO, fields)

    fun warn(
        context: Context,
        category: String,
        action: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = event(context, category, action, Level.WARN, fields, error)

    fun error(
        context: Context,
        category: String,
        action: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = event(context, category, action, Level.ERROR, fields, error)

    /**
     * Writes one structured diagnostic event.
     *
     * Field insertion order is retained to keep related values readable. Field
     * values are deliberately length-limited; callers should log counts, states,
     * durations and stable non-secret identifiers rather than payload content.
     */
    fun event(
        context: Context,
        category: String,
        action: String,
        level: Level = Level.INFO,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) {
        runCatching {
            val safeCategory = DiagnosticLogSanitizer.singleLine(category, 80).ifBlank { "general" }
            val safeAction = DiagnosticLogSanitizer.singleLine(action, MAX_ACTION_CHARS)
            val safeThread = DiagnosticLogSanitizer.singleLine(Thread.currentThread().name, 80)
            val safeFields = buildFieldText(fields)
            val safeError = buildErrorText(error)
            synchronized(this) {
                val eventSequence = sequence.incrementAndGet()
                val line = buildString {
                    append(timestamp.format(Date()))
                    append(' ')
                    append(level.name)
                    append(" category=")
                    append(quote(safeCategory))
                    append(" action=")
                    append(quote(safeAction))
                    append(" thread=")
                    append(quote(safeThread))
                    append(" session=")
                    append(sessionId)
                    append(" seq=")
                    append(eventSequence)
                    if (safeFields.isNotEmpty()) append(safeFields)
                    if (safeError.isNotEmpty()) append(safeError)
                }
                writeToLogcat(level, line)
                runCatching { appendLine(context.applicationContext, "$line\n") }
            }
        }.onFailure {
            // Logging is intentionally best-effort and can never fail the caller.
            runCatching { Log.w(TAG, "Diagnostic logging failed: ${it.javaClass.simpleName}") }
        }
    }

    @Synchronized
    fun stats(context: Context): Stats = runCatching {
        val files = existingLogFiles(context.applicationContext)
        Stats(
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            lineCount = files.sumOf { countLines(it).toLong() }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            lastModifiedAt = files.maxOfOrNull { it.lastModified() } ?: 0L,
            warningCount = countLevel(files, Level.WARN),
            errorCount = countLevel(files, Level.ERROR),
        )
    }.getOrDefault(Stats(0, 0L, 0, 0L, 0, 0))

    /** Returns the newest portion of all logs, in chronological order. */
    @Synchronized
    fun readRecent(context: Context, maxChars: Int = 120_000): String = runCatching {
        val limit = maxChars.coerceIn(0, MAX_READ_CHARS)
        if (limit == 0) return@runCatching ""

        val combined = StringBuilder(limit.coerceAtMost(256 * 1024))
        chronologicalLogFiles(context.applicationContext).forEach { file ->
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    combined.append(DiagnosticLogSanitizer.redactSecrets(line)).append('\n')
                    if (combined.length > limit * 2L) {
                        combined.delete(0, combined.length - limit)
                    }
                }
            }
        }
        trimToNewestCompleteLines(combined.toString(), limit)
    }.getOrDefault("")

    /**
     * Writes a self-contained text export, oldest archive first.
     *
     * The supplied writer remains owned by the caller. Returns false if any I/O
     * operation failed; failures are swallowed so export cannot stop automation.
     */
    @Synchronized
    fun writeExport(
        context: Context,
        writer: Writer,
        headerLines: List<String> = emptyList(),
    ): Boolean = runCatching {
        val appContext = context.applicationContext
        val currentStats = stats(appContext)
        writer.appendLine("# SoulBot diagnostic log")
        writer.appendLine("# exportedAt=${timestamp.format(Date())}")
        writer.appendLine("# session=$sessionId")
        writer.appendLine(
            "# files=${currentStats.fileCount} bytes=${currentStats.totalBytes} " +
                "events=${currentStats.lineCount}",
        )
        writer.appendLine("# Credentials are automatically redacted; avoid sharing logs publicly.")
        headerLines.forEach { header ->
            writer.append("# ")
            writer.appendLine(DiagnosticLogSanitizer.singleLine(header, 2_000))
        }

        chronologicalLogFiles(appContext).forEach { file ->
            writer.appendLine()
            writer.appendLine("--- ${file.name} ---")
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    writer.appendLine(DiagnosticLogSanitizer.redactSecrets(line))
                }
            }
        }
        writer.flush()
        true
    }.getOrDefault(false)

    /** Deletes only the known diagnostic log files in app-private storage. */
    @Synchronized
    fun clear(context: Context): Boolean = runCatching {
        allLogFiles(context.applicationContext).fold(true) { success, file ->
            (!file.exists() || file.delete()) && success
        }
    }.getOrDefault(false)

    private fun writeToLogcat(level: Level, line: String) {
        runCatching {
            when (level) {
                Level.DEBUG -> Log.d(TAG, line)
                Level.INFO -> Log.i(TAG, line)
                Level.WARN -> Log.w(TAG, line)
                Level.ERROR -> Log.e(TAG, line)
            }
        }
    }

    private fun buildFieldText(fields: Map<String, Any?>): String = buildString {
        fields.entries.take(32).forEach { (key, value) ->
            val safeKey = DiagnosticLogSanitizer.singleLine(key, 80)
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                .ifBlank { "field" }
            val rawValue = runCatching { value?.toString() ?: "null" }.getOrDefault("<unavailable>")
            val safeValue = DiagnosticLogSanitizer.singleLine(rawValue, MAX_FIELD_CHARS)
            append(' ')
            append(safeKey)
            append('=')
            append(quote(safeValue))
        }
        if (fields.size > 32) append(" fieldsTruncated=true")
    }

    private fun buildErrorText(error: Throwable?): String {
        if (error == null) return ""
        return runCatching {
            val type = DiagnosticLogSanitizer.singleLine(error.javaClass.name, 180)
            val message = DiagnosticLogSanitizer.singleLine(error.message.orEmpty(), 1_000)
            val rawStack = StringWriter().also { buffer ->
                PrintWriter(buffer).use { error.printStackTrace(it) }
            }.toString()
            val stack = DiagnosticLogSanitizer.multiLine(rawStack, MAX_STACK_CHARS)
                .replace("\r\n", "\\n")
                .replace('\n', ' ')
                .replace('\r', ' ')
            " errorType=${quote(type)} errorMessage=${quote(message)} stack=${quote(stack)}"
        }.getOrElse {
            " errorType=${quote(error.javaClass.name)} errorMessage=\"<unavailable>\""
        }
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun appendLine(context: Context, line: String) {
        val file = currentLogFile(context)
        val bytes = line.toByteArray(Charsets.UTF_8)
        rotateIfNeeded(context, bytes.size.toLong())
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { output ->
            output.write(line)
        }
    }

    private fun rotateIfNeeded(context: Context, incomingBytes: Long) {
        val current = currentLogFile(context)
        if (!current.exists() || current.length() == 0L || current.length() + incomingBytes <= MAX_FILE_BYTES) {
            return
        }

        for (index in MAX_ARCHIVES downTo 1) {
            val source = if (index == 1) current else archiveLogFile(context, index - 1)
            val target = archiveLogFile(context, index)
            if (!source.exists()) continue
            if (target.exists() && !target.delete()) continue
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                if (source == current) source.writeText("") else source.delete()
            }
        }
    }

    private fun countLines(file: File): Int {
        var count = 0L
        var hasData = false
        var lastByte = -1
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                hasData = true
                lastByte = buffer[read - 1].toInt()
                for (index in 0 until read) if (buffer[index] == '\n'.code.toByte()) count++
            }
        }
        if (hasData && lastByte != '\n'.code) count++
        return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun countLevel(files: List<File>, level: Level): Int {
        val marker = " ${level.name} category="
        var count = 0L
        files.forEach { file ->
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (marker in line) count++
                }
            }
        }
        return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun trimToNewestCompleteLines(value: String, limit: Int): String {
        if (value.length <= limit) return value
        val rawStart = value.length - limit
        val nextLine = value.indexOf('\n', rawStart)
        return if (nextLine in rawStart until value.lastIndex) value.substring(nextLine + 1) else value.takeLast(limit)
    }

    private fun currentLogFile(context: Context): File = File(logDirectory(context), FILE_NAME)

    private fun archiveLogFile(context: Context, index: Int): File =
        File(logDirectory(context), "$FILE_NAME.$index")

    /**
     * Logs live outside Auto Backup so diagnostic traces and accidental personal
     * identifiers are not copied to another device with the user's app data.
     */
    private fun logDirectory(context: Context): File {
        val directory = File(context.noBackupFilesDir, "logs")
        if (!directory.exists()) directory.mkdirs()
        migrateLegacyLog(context, directory)
        return directory
    }

    /** Migrates the pre-1.1 single log from filesDir on the first log access. */
    private fun migrateLegacyLog(context: Context, directory: File) {
        val legacy = File(context.filesDir, FILE_NAME)
        if (!legacy.exists() || legacy.length() == 0L) return

        val current = File(directory, FILE_NAME)
        val destination = if (!current.exists()) {
            current
        } else {
            // A legacy log predates every file in the new directory. Prefer the
            // oldest available archive slot without disturbing the active chain.
            (MAX_ARCHIVES downTo 1)
                .map { File(directory, "$FILE_NAME.$it") }
                .firstOrNull { !it.exists() }
                ?: return
        }
        if (!legacy.renameTo(destination)) {
            legacy.copyTo(destination, overwrite = false)
            legacy.delete()
        }
    }

    private fun allLogFiles(context: Context): List<File> = buildList {
        add(currentLogFile(context))
        (1..MAX_ARCHIVES).forEach { add(archiveLogFile(context, it)) }
    }

    private fun existingLogFiles(context: Context): List<File> = allLogFiles(context).filter(File::exists)

    private fun chronologicalLogFiles(context: Context): List<File> = buildList {
        (MAX_ARCHIVES downTo 1).forEach { add(archiveLogFile(context, it)) }
        add(currentLogFile(context))
    }.filter(File::exists)
}
