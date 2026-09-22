package com.soulbot.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.Reader
import java.io.Writer
import java.security.MessageDigest
import java.util.UUID

data class ConversationMemoryImportResult(
    val added: Int,
    val skipped: Int,
    val invalid: Int,
)

class ConversationMemoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "conversation_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversation_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "event_key TEXT NOT NULL UNIQUE, " +
                "contact TEXT NOT NULL, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "observed_at INTEGER NOT NULL, " +
                "source TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX idx_conversation_contact_time " +
                "ON conversation_messages(contact, observed_at, id)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun hasHistory(contact: String): Boolean {
        val clean = cleanContact(contact) ?: return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM conversation_messages WHERE contact=? LIMIT 1",
            arrayOf(clean),
        ).use { return it.moveToFirst() }
    }

    fun syncVisibleThread(
        contact: String,
        visibleThread: List<Pair<String, String>>,
        source: String = "chat",
    ): Int {
        val cleanContact = cleanContact(contact) ?: return 0
        val cleanThread = visibleThread.mapNotNull { (role, content) ->
            cleanMessage(role, content)
        }
        if (cleanThread.isEmpty()) return 0
        val existingTail = recentEntries(cleanContact, 120)
            .map { it.role to it.content }
        val additions = ConversationMemoryLogic.messagesAfterOverlap(existingTail, cleanThread)
        if (additions.isEmpty()) return 0

        var added = 0
        val baseTime = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            additions.forEachIndexed { index, message ->
                if (insert(
                        db = writableDatabase,
                        eventKey = UUID.randomUUID().toString(),
                        contact = cleanContact,
                        role = message.first,
                        content = message.second,
                        observedAt = baseTime + index,
                        source = cleanSource(source),
                    )
                ) added++
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return added
    }

    fun recordMessage(
        contact: String,
        role: String,
        content: String,
        source: String = "chat",
    ): Boolean {
        val cleanContact = cleanContact(contact) ?: return false
        val cleanMessage = cleanMessage(role, content) ?: return false
        return insert(
            db = writableDatabase,
            eventKey = UUID.randomUUID().toString(),
            contact = cleanContact,
            role = cleanMessage.first,
            content = cleanMessage.second,
            observedAt = System.currentTimeMillis(),
            source = cleanSource(source),
        )
    }

    fun seedLegacyHistory(items: List<HistoryItem>): Int {
        if (items.isEmpty()) return 0
        readableDatabase.rawQuery(
            "SELECT 1 FROM conversation_messages WHERE source='legacy-history' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) return 0 }
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items.asReversed()) {
                val contact = cleanContact(item.name) ?: continue
                val incoming = cleanMessage("in", item.theirMsg)
                val outgoing = cleanMessage("out", item.myMsg)
                if (incoming != null && insert(
                        db,
                        stableImportKey(contact, incoming.first, incoming.second, item.time, "legacy-history"),
                        contact,
                        incoming.first,
                        incoming.second,
                        item.time,
                        "legacy-history",
                    )
                ) added++
                if (outgoing != null && insert(
                        db,
                        stableImportKey(contact, outgoing.first, outgoing.second, item.time + 1, "legacy-history"),
                        contact,
                        outgoing.first,
                        outgoing.second,
                        item.time + 1,
                        "legacy-history",
                    )
                ) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun relevantOlderMessages(
        contact: String,
        query: String,
        limit: Int = 6,
        recentWindow: Int = SoulBotService.CONTEXT_LEN,
    ): List<ConversationMemoryEntry> {
        val cleanContact = cleanContact(contact) ?: return emptyList()
        val candidates = mutableListOf<ConversationMemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT event_key, contact, role, content, observed_at, source " +
                "FROM conversation_messages WHERE contact=? " +
                "ORDER BY observed_at DESC, id DESC LIMIT 500 OFFSET ?",
            arrayOf(cleanContact, recentWindow.coerceAtLeast(0).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += ConversationMemoryEntry(
                    eventKey = cursor.getString(0),
                    contact = cursor.getString(1),
                    role = cursor.getString(2),
                    content = cursor.getString(3),
                    observedAt = cursor.getLong(4),
                    source = cursor.getString(5),
                )
            }
        }
        return ConversationMemoryLogic.rankRelevant(query, candidates, limit.coerceIn(1, 12))
    }

    fun messageCount(): Int = scalarCount("conversation_messages")

    fun contactCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT contact) FROM conversation_messages",
            null,
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun clearAll() {
        writableDatabase.delete("conversation_messages", null, null)
    }

    fun writeExport(writer: Writer) {
        JsonWriter(writer).use { json ->
            json.setIndent("  ")
            json.beginObject()
            json.name("schema").value(EXPORT_SCHEMA)
            json.name("version").value(EXPORT_VERSION.toLong())
            json.name("exportedAt").value(System.currentTimeMillis())
            json.name("messages").beginArray()
            readableDatabase.rawQuery(
                "SELECT event_key, contact, role, content, observed_at, source " +
                    "FROM conversation_messages ORDER BY observed_at, id",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    json.beginObject()
                    json.name("eventKey").value(cursor.getString(0))
                    json.name("contact").value(cursor.getString(1))
                    json.name("role").value(cursor.getString(2))
                    json.name("content").value(cursor.getString(3))
                    json.name("observedAt").value(cursor.getLong(4))
                    json.name("source").value(cursor.getString(5))
                    json.endObject()
                }
            }
            json.endArray()
            json.endObject()
        }
    }

    fun importFrom(reader: Reader): ConversationMemoryImportResult {
        var schema = ""
        var version = 0
        var added = 0
        var skipped = 0
        var invalid = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            JsonReader(reader).use { json ->
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "schema" -> schema = json.nextString()
                        "version" -> version = json.nextInt()
                        "messages" -> {
                            json.beginArray()
                            while (json.hasNext()) {
                                val entry = readEntry(json)
                                if (entry == null) {
                                    invalid++
                                } else {
                                    val inserted = insert(
                                        db = db,
                                        eventKey = entry.eventKey,
                                        contact = entry.contact,
                                        role = entry.role,
                                        content = entry.content,
                                        observedAt = entry.observedAt,
                                        source = entry.source,
                                    )
                                    if (inserted) added++ else skipped++
                                }
                            }
                            json.endArray()
                        }
                        else -> json.skipValue()
                    }
                }
                json.endObject()
            }
            require(schema == EXPORT_SCHEMA) { "不是聊天记忆备份文件" }
            require(version in 1..EXPORT_VERSION) { "不支持的备份版本：$version" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ConversationMemoryImportResult(added, skipped, invalid)
    }

    private fun readEntry(json: JsonReader): ConversationMemoryEntry? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return null
        }
        var eventKey = ""
        var contact = ""
        var role = ""
        var content = ""
        var observedAt = 0L
        var source = "import"
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "eventKey" -> eventKey = json.nextString()
                "contact" -> contact = json.nextString()
                "role" -> role = json.nextString()
                "content" -> content = json.nextString()
                "observedAt" -> observedAt = json.nextLong()
                "source" -> source = json.nextString()
                else -> json.skipValue()
            }
        }
        json.endObject()
        val cleanContact = cleanContact(contact) ?: return null
        val cleanMessage = cleanMessage(role, content) ?: return null
        val safeTime = observedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val safeKey = eventKey.trim().take(160).ifEmpty {
            stableImportKey(cleanContact, cleanMessage.first, cleanMessage.second, safeTime, source)
        }
        return ConversationMemoryEntry(
            eventKey = safeKey,
            contact = cleanContact,
            role = cleanMessage.first,
            content = cleanMessage.second,
            observedAt = safeTime,
            source = cleanSource(source),
        )
    }

    private fun recentEntries(contact: String, limit: Int): List<ConversationMemoryEntry> {
        val result = mutableListOf<ConversationMemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT event_key, contact, role, content, observed_at, source " +
                "FROM conversation_messages WHERE contact=? " +
                "ORDER BY observed_at DESC, id DESC LIMIT ?",
            arrayOf(contact, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ConversationMemoryEntry(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getString(5),
                )
            }
        }
        return result.asReversed()
    }

    private fun insert(
        db: SQLiteDatabase,
        eventKey: String,
        contact: String,
        role: String,
        content: String,
        observedAt: Long,
        source: String,
    ): Boolean {
        val values = ContentValues().apply {
            put("event_key", eventKey)
            put("contact", contact)
            put("role", role)
            put("content", content)
            put("observed_at", observedAt)
            put("source", source)
        }
        return db.insertWithOnConflict(
            "conversation_messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    private fun scalarCount(table: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun cleanContact(contact: String): String? =
        contact.trim().take(120).takeIf(String::isNotEmpty)

    private fun cleanMessage(role: String, content: String): Pair<String, String>? {
        val cleanRole = role.trim().lowercase()
        if (cleanRole != "in" && cleanRole != "out") return null
        val cleanContent = content.trim().take(2000)
        if (cleanContent.isEmpty()) return null
        return cleanRole to cleanContent
    }

    private fun cleanSource(source: String): String = source.trim().take(40).ifEmpty { "chat" }

    private fun stableImportKey(
        contact: String,
        role: String,
        content: String,
        observedAt: Long,
        source: String,
    ): String {
        val bytes = "$contact\u0000$role\u0000$content\u0000$observedAt\u0000$source".toByteArray()
        return "import-" + MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val EXPORT_SCHEMA = "soulbot-conversation-memory"
        private const val EXPORT_VERSION = 1
    }
}
