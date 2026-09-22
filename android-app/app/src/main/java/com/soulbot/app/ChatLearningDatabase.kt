package com.soulbot.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatLearningDatabase(context: Context) :
    SQLiteOpenHelper(context, "chat_learning.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE style_samples (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "their_msg TEXT NOT NULL, " +
                "my_msg TEXT NOT NULL, " +
                "scene TEXT NOT NULL DEFAULT 'CHAT', " +
                "sample_key TEXT NOT NULL UNIQUE, " +
                "time INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE contact_memories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "contact TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "memory_key TEXT NOT NULL UNIQUE, " +
                "time INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE automated_replies (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "contact TEXT NOT NULL, " +
                "reply TEXT NOT NULL, " +
                "reply_key TEXT NOT NULL UNIQUE, " +
                "time INTEGER NOT NULL)",
        )
        createFeedbackTables(db)
        createOpeningPatternTable(db)
        db.execSQL("CREATE INDEX idx_contact_memory ON contact_memories(contact, time DESC)")
        db.execSQL("CREATE INDEX idx_auto_contact ON automated_replies(contact, time DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFeedbackTables(db, includeScene = false)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE style_samples ADD COLUMN scene TEXT NOT NULL DEFAULT 'CHAT'")
            db.execSQL("ALTER TABLE reply_feedback ADD COLUMN scene TEXT NOT NULL DEFAULT 'CHAT'")
            // Keep every legacy sample, but include its default scene in the unique key.
            db.execSQL("UPDATE style_samples SET sample_key = 'CHAT' || char(0) || sample_key")
            createOpeningPatternTable(db)
        }
    }

    fun recordManualSample(
        theirMessage: String,
        myMessage: String,
        scene: ReplyScene = ReplyScene.CHAT,
    ): Boolean {
        val their = theirMessage.trim().take(500)
        val mine = myMessage.trim().take(500)
        if (their.isEmpty() || mine.isEmpty()) return false
        val normalizedTheir = comparableText(their)
        val normalizedMine = comparableText(mine)
        if (normalizedTheir.isEmpty() && normalizedMine.isEmpty()) return false
        val key = scene.name + "\u0000" + normalizedTheir + "\u0000" + normalizedMine
        val values = ContentValues().apply {
            put("their_msg", their)
            put("my_msg", mine)
            put("scene", scene.name)
            put("sample_key", key)
            put("time", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "style_samples",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        prune("style_samples", 500)
        return inserted
    }

    fun importSamples(samples: List<ChatStyleSample>): Int {
        var added = 0
        writableDatabase.beginTransaction()
        try {
            for (sample in samples.take(200)) {
                if (recordManualSample(sample.theirMessage, sample.myMessage, sample.scene)) added++
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return added
    }

    fun styleSampleCount(): Int = scalarCount("style_samples")

    fun styleProfile(): ChatStyleProfile {
        val samples = allStyleSamples(200)
        if (samples.isEmpty()) return ChatStyleProfile(0, 0, 0, 0, 0)
        fun percent(count: Int): Int = count * 100 / samples.size
        val outgoing = samples.map { it.myMessage }
        val tokenCounts = linkedMapOf<String, Int>()
        outgoing.flatMap(::styleTokens).forEach { token ->
            tokenCounts[token] = (tokenCounts[token] ?: 0) + 1
        }
        val endingCounts = linkedMapOf<String, Int>()
        outgoing.mapNotNull(::styleEnding).forEach { ending ->
            endingCounts[ending] = (endingCounts[ending] ?: 0) + 1
        }
        val sentenceLengths = outgoing.flatMap { message ->
            message.split(Regex("[。！？!?\\n]+"))
                .map { it.trim().length }
                .filter { it > 0 }
        }
        return ChatStyleProfile(
            sampleCount = styleSampleCount(),
            averageLength = outgoing.sumOf { it.length } / samples.size,
            emojiPercent = percent(samples.count { sample ->
                sample.myMessage.codePoints().anyMatch { it > 0xFFFF }
            }),
            questionPercent = percent(samples.count { '?' in it.myMessage || '？' in it.myMessage }),
            multilinePercent = percent(samples.count { '\n' in it.myMessage }),
            punctuationPercent = percent(samples.count { sample ->
                sample.myMessage.any { it in "。！？!?，," }
            }),
            averageSentenceLength = sentenceLengths.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0,
            commonWords = tokenCounts.entries
                .filter { it.value >= 2 }
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key },
            commonEndings = endingCounts.entries
                .filter { it.value >= 2 }
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key },
        )
    }

    fun relevantStyleSamples(
        query: String,
        limit: Int = 5,
        scene: ReplyScene = ReplyScene.CHAT,
    ): List<ChatStyleSample> = StyleSampleMatcher.rank(query, allStyleSamples(200, scene), limit, scene)

    fun clearStyleSamples() {
        writableDatabase.delete("style_samples", null, null)
    }

    fun recordContactMemory(contact: String, content: String) {
        val cleanContact = contact.trim().take(100)
        val cleanContent = content.trim().take(500)
        if (cleanContact.isEmpty() || cleanContent.isEmpty()) return
        val values = ContentValues().apply {
            put("contact", cleanContact)
            put("content", cleanContent)
            put("memory_key", comparableText(cleanContact) + "\u0000" + comparableText(cleanContent))
            put("time", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "contact_memories",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        prune("contact_memories", 1000)
    }

    fun contactMemories(contact: String, limit: Int = 5): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT content FROM contact_memories WHERE contact=? ORDER BY time DESC LIMIT ?",
            arrayOf(contact, limit.coerceIn(1, 20).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0).orEmpty()
        }
        return result
    }

    fun contactMemoryCount(): Int = scalarCount("contact_memories")

    fun clearContactMemories() {
        writableDatabase.delete("contact_memories", null, null)
    }

    /**
     * Stores an explicit user judgment without ever treating an automated reply
     * as a positive style sample by accident. Positive corrections become real
     * training pairs; negative corrections remain a local rejection signal.
     */
    fun recordReplyFeedback(
        contact: String,
        incoming: String,
        reply: String,
        liked: Boolean,
        note: String = "",
        scene: ReplyScene = ReplyScene.CHAT,
    ): Boolean {
        val cleanContact = contact.trim().take(100)
        val cleanIncoming = incoming.trim().take(1000)
        val cleanReply = reply.trim().take(1000)
        if (cleanContact.isEmpty() || cleanIncoming.isEmpty() || cleanReply.isEmpty()) return false
        val legacyKey = listOf(cleanContact, cleanIncoming, cleanReply, liked).joinToString("\u0000")
        // Preserve v2 CHAT keys so importing existing feedback cannot duplicate it.
        val key = if (scene == ReplyScene.CHAT) legacyKey else scene.name + "\u0000" + legacyKey
        val feedbackKey = stableKey(key)
        val cleanNote = note.trim().take(300)
        val values = ContentValues().apply {
            put("contact", cleanContact)
            put("incoming", cleanIncoming)
            put("reply", cleanReply)
            put("liked", if (liked) 1 else 0)
            put("note", cleanNote)
            put("scene", scene.name)
            put("feedback_key", feedbackKey)
            put("time", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict(
                "reply_feedback", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
            var updated = false
            if (!inserted) {
                val incomingLabels = ReplyStyleFeedback.labels(listOf(cleanNote))
                if (incomingLabels.isNotEmpty()) {
                    db.rawQuery(
                        "SELECT note FROM reply_feedback WHERE feedback_key=? LIMIT 1",
                        arrayOf(feedbackKey),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val previousNote = cursor.getString(0).orEmpty()
                            val previousLabels = ReplyStyleFeedback.labels(listOf(previousNote))
                            val addedLabels = incomingLabels.filterNot { it in previousLabels }
                            if (addedLabels.isNotEmpty()) {
                                // Keep the existing judgment and free-form note. Only add new
                                // controlled reasons, so repeated clicks never inflate counts.
                                val update = ContentValues().apply {
                                    put("note", listOf(previousNote, addedLabels.joinToString("、"))
                                        .filter(String::isNotBlank).joinToString("\n"))
                                    put("time", System.currentTimeMillis())
                                }
                                updated = db.update(
                                    "reply_feedback", update, "feedback_key=?", arrayOf(feedbackKey),
                                ) > 0
                            }
                        }
                    }
                }
            }
            if (inserted && liked) recordManualSample(cleanIncoming, cleanReply, scene)
            prune("reply_feedback", 1000)
            db.setTransactionSuccessful()
            return inserted || updated
        } finally {
            db.endTransaction()
        }
    }

    fun dislikedReplies(contact: String, limit: Int = 20): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT reply FROM reply_feedback WHERE contact=? AND liked=0 ORDER BY time DESC LIMIT ?",
            arrayOf(contact.trim().take(100), limit.coerceIn(1, 50).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0).orEmpty()
        }
        return result
    }

    /** Global style feedback exposes controlled labels only, never another contact's text. */
    fun dislikedStyleHints(scene: ReplyScene): List<String> {
        val notes = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT note FROM reply_feedback WHERE liked=0 AND scene=? ORDER BY time DESC, id DESC LIMIT 100",
            arrayOf(scene.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                notes += cursor.getString(0).orEmpty()
            }
        }
        return ReplyStyleFeedback.labels(notes)
    }

    /** Call only after the UI confirms that this opening was actually sent. */
    fun recordSentOpening(scene: ReplyScene, fingerprint: OpeningFingerprint) {
        if (scene == ReplyScene.CHAT || fingerprint.normalized.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("scene", scene.name)
                put("normalized", fingerprint.normalized.take(500))
                put("tactic", fingerprint.tactic.take(100))
                put("time", System.currentTimeMillis())
            }
            db.insertOrThrow("sent_opening_patterns", null, values)
            prune("sent_opening_patterns", 40)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recentOpeningPatterns(limit: Int = 40): List<OpeningFingerprint> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<OpeningFingerprint>()
        readableDatabase.rawQuery(
            "SELECT normalized, tactic FROM sent_opening_patterns ORDER BY time DESC, id DESC LIMIT ?",
            arrayOf(limit.coerceAtMost(40).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += OpeningFingerprint(cursor.getString(0), cursor.getString(1))
            }
        }
        return result
    }

    fun recordQualityEvent(
        contact: String,
        candidate: String,
        accepted: Boolean,
        reason: String = "",
    ) {
        val cleanCandidate = candidate.trim().take(1000)
        if (cleanCandidate.isEmpty()) return
        val values = ContentValues().apply {
            put("contact", contact.trim().take(100))
            put("candidate", cleanCandidate)
            put("accepted", if (accepted) 1 else 0)
            put("reason", reason.trim().take(180))
            put("time", System.currentTimeMillis())
        }
        writableDatabase.insert("reply_quality_events", null, values)
        prune("reply_quality_events", 2000)
    }

    fun feedbackSummary(): ReplyFeedbackSummary {
        var positive = 0
        var negative = 0
        readableDatabase.rawQuery(
            "SELECT liked, COUNT(*) FROM reply_feedback GROUP BY liked",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) == 1) positive = cursor.getInt(1) else negative = cursor.getInt(1)
            }
        }
        var accepted = 0
        var rejected = 0
        readableDatabase.rawQuery(
            "SELECT accepted, COUNT(*) FROM reply_quality_events GROUP BY accepted",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) == 1) accepted = cursor.getInt(1) else rejected = cursor.getInt(1)
            }
        }
        return ReplyFeedbackSummary(positive, negative, accepted, rejected)
    }

    fun recordAutomatedReply(contact: String, reply: String) {
        for (part in automatedReplyParts(reply)) {
            val normalized = comparableText(part)
            if (normalized.isEmpty()) continue
            val values = ContentValues().apply {
                put("contact", contact.trim().take(100))
                put("reply", part.take(500))
                put("reply_key", comparableText(contact) + "\u0000" + normalized)
                put("time", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict(
                "automated_replies",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
        prune("automated_replies", 2000)
    }

    fun isAutomatedReply(contact: String, reply: String): Boolean {
        val key = comparableText(contact) + "\u0000" + comparableText(reply)
        if (key.endsWith("\u0000")) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM automated_replies WHERE reply_key=? LIMIT 1",
            arrayOf(key),
        ).use { return it.moveToFirst() }
    }

    fun seedAutomatedReplies(items: List<HistoryItem>) {
        writableDatabase.beginTransaction()
        try {
            for (item in items.take(1000)) recordAutomatedReply(item.name, item.myMsg)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun allStyleSamples(limit: Int, scene: ReplyScene? = null): List<ChatStyleSample> {
        val result = mutableListOf<ChatStyleSample>()
        readableDatabase.rawQuery(
            "SELECT their_msg, my_msg, time, scene FROM style_samples " +
                (if (scene == null) "" else "WHERE scene=? ") +
                "ORDER BY time DESC, id DESC LIMIT ?",
            if (scene == null) arrayOf(limit.toString()) else arrayOf(scene.name, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ChatStyleSample(
                    cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                    ReplyScene.values().firstOrNull { it.name == cursor.getString(3) } ?: ReplyScene.CHAT,
                )
            }
        }
        return result
    }

    private fun scalarCount(table: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun styleTokens(message: String): List<String> {
        val clean = message.lowercase()
        val result = mutableListOf<String>()
        Regex("[a-z0-9]{2,}").findAll(clean).forEach { result += it.value }
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(clean).forEach { match ->
            val value = match.value
            for (index in 0 until value.length - 1) {
                result += value.substring(index, index + 2)
            }
        }
        return result
    }

    private fun styleEnding(message: String): String? = message
        .trim()
        .trimEnd('。', '！', '？', '!', '?', ',', '，', '~', '～', '…')
        .takeLast(4)
        .takeIf { it.length >= 2 }

    private fun stableKey(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun createFeedbackTables(db: SQLiteDatabase, includeScene: Boolean = true) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reply_feedback (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "contact TEXT NOT NULL, incoming TEXT NOT NULL, reply TEXT NOT NULL, " +
                "liked INTEGER NOT NULL, note TEXT NOT NULL, feedback_key TEXT NOT NULL UNIQUE, " +
                (if (includeScene) "scene TEXT NOT NULL DEFAULT 'CHAT', " else "") +
                "time INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reply_quality_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, contact TEXT NOT NULL, " +
                "candidate TEXT NOT NULL, accepted INTEGER NOT NULL, reason TEXT NOT NULL, " +
                "time INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_feedback_contact ON reply_feedback(contact, time DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_quality_time ON reply_quality_events(time DESC)")
    }

    private fun createOpeningPatternTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sent_opening_patterns (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, scene TEXT NOT NULL, " +
                "normalized TEXT NOT NULL, tactic TEXT NOT NULL, time INTEGER NOT NULL)",
        )
    }

    private fun prune(table: String, keep: Int) {
        writableDatabase.execSQL(
            "DELETE FROM $table WHERE id NOT IN (SELECT id FROM $table ORDER BY time DESC, id DESC LIMIT $keep)",
        )
    }

    private fun automatedReplyParts(reply: String): List<String> {
        val clean = reply.trim()
        val pieces = clean.split(Regex("""(?<=[。！？!?…~])|\n+"""))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(3)
        return (listOf(clean) + pieces).filter(String::isNotEmpty).distinct()
    }
}

data class ReplyFeedbackSummary(
    val positive: Int,
    val negative: Int,
    val qualityAccepted: Int,
    val qualityRejected: Int,
)
