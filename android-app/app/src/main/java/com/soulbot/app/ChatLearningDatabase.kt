package com.soulbot.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatLearningDatabase(context: Context) :
    SQLiteOpenHelper(context, "chat_learning.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE style_samples (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "their_msg TEXT NOT NULL, " +
                "my_msg TEXT NOT NULL, " +
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
        db.execSQL("CREATE INDEX idx_contact_memory ON contact_memories(contact, time DESC)")
        db.execSQL("CREATE INDEX idx_auto_contact ON automated_replies(contact, time DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun recordManualSample(theirMessage: String, myMessage: String): Boolean {
        val their = theirMessage.trim().take(500)
        val mine = myMessage.trim().take(500)
        if (their.isEmpty() || mine.isEmpty()) return false
        val key = comparableText(their) + "\u0000" + comparableText(mine)
        if (key == "\u0000") return false
        val values = ContentValues().apply {
            put("their_msg", their)
            put("my_msg", mine)
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
                if (recordManualSample(sample.theirMessage, sample.myMessage)) added++
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
        return ChatStyleProfile(
            sampleCount = styleSampleCount(),
            averageLength = samples.sumOf { it.myMessage.length } / samples.size,
            emojiPercent = percent(samples.count { sample ->
                sample.myMessage.codePoints().anyMatch { it > 0xFFFF }
            }),
            questionPercent = percent(samples.count { '?' in it.myMessage || '？' in it.myMessage }),
            multilinePercent = percent(samples.count { '\n' in it.myMessage }),
        )
    }

    fun relevantStyleSamples(query: String, limit: Int = 5): List<ChatStyleSample> =
        StyleSampleMatcher.rank(query, allStyleSamples(200), limit)

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

    private fun allStyleSamples(limit: Int): List<ChatStyleSample> {
        val result = mutableListOf<ChatStyleSample>()
        readableDatabase.rawQuery(
            "SELECT their_msg, my_msg, time FROM style_samples ORDER BY time DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ChatStyleSample(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
        }
        return result
    }

    private fun scalarCount(table: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun prune(table: String, keep: Int) {
        writableDatabase.execSQL(
            "DELETE FROM $table WHERE id NOT IN (SELECT id FROM $table ORDER BY time DESC LIMIT $keep)",
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

