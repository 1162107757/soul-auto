package com.soulbot.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.Reader
import java.io.Writer

/** Separate from existing history and style databases; replay never sends messages. */
class ReplyReplayDatabase(context: Context) : SQLiteOpenHelper(context, "reply_replay.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE replay_cases (id TEXT PRIMARY KEY, scene TEXT NOT NULL, " +
                "context_key TEXT NOT NULL, dataset TEXT NOT NULL, reviewed INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, payload_chars INTEGER NOT NULL, payload TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX idx_replay_created ON replay_cases(created_at DESC)")
        db.execSQL("CREATE INDEX idx_replay_context ON replay_cases(context_key, dataset)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(item: ReplyReplayCase): Boolean {
        if (ReplyReplayLogic.validationError(item) != null) return false
        val payload = encode(item).toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previousSize = payloadLength(db, item.id)
            if (previousSize == null && count(db) >= ReplyReplayLogic.MAX_CASES) return false
            if (totalPayloadLength(db) - (previousSize ?: 0) + payload.length > MAX_PAYLOAD_CHARS) return false
            val result = db.insertWithOnConflict("replay_cases", null, values(item, payload), SQLiteDatabase.CONFLICT_REPLACE) != -1L
            if (result) db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun get(id: String): ReplyReplayCase? = readableDatabase.rawQuery(
        "SELECT payload FROM replay_cases WHERE id=?", arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) decode(JSONObject(cursor.getString(0))) else null }

    fun list(limit: Int = 100): List<ReplyReplayCase> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<ReplyReplayCase>()
        readableDatabase.rawQuery(
            "SELECT payload FROM replay_cases ORDER BY created_at DESC, id DESC LIMIT ?",
            arrayOf(limit.coerceAtMost(ReplyReplayLogic.MAX_CASES).toString()),
        ).use { cursor -> while (cursor.moveToNext()) result += decode(JSONObject(cursor.getString(0))) }
        return result
    }

    fun delete(id: String): Boolean = writableDatabase.delete("replay_cases", "id=?", arrayOf(id)) > 0

    fun summary(): ReplaySummary {
        var learning = 0
        var evaluation = 0
        var reviewed = 0
        readableDatabase.rawQuery("SELECT dataset, COUNT(*), SUM(reviewed) FROM replay_cases GROUP BY dataset", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == ReplayDataset.LEARNING.name) learning += cursor.getInt(1)
                else evaluation += cursor.getInt(1)
                reviewed += cursor.getInt(2)
            }
        }
        return ReplaySummary(learning, evaluation, reviewed)
    }

    fun relevantExamples(
        query: String,
        scene: ReplyScene,
        limit: Int = 3,
        excludeContextKey: String = "",
        excludeContact: String = "",
        excludeTurns: List<ReplayTurn> = emptyList(),
        checkCancelled: () -> Unit = {},
    ): List<ReplyReplayCase> {
        // Read the bounded complete set so an older evaluation copy cannot escape holdout isolation.
        checkCancelled()
        val cases = list(ReplyReplayLogic.MAX_CASES)
        checkCancelled()
        return ReplyReplayLogic.rank(
            query, cases, scene, limit, excludeContextKey, excludeContact, excludeTurns, checkCancelled,
        )
    }

    fun writeExport(writer: Writer) {
        writer.write("{\"format\":\"$EXPORT_FORMAT\",\"version\":$EXPORT_VERSION,\"exportedAt\":${System.currentTimeMillis()},\"cases\":[")
        var first = true
        readableDatabase.rawQuery("SELECT payload FROM replay_cases ORDER BY created_at, id", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (!first) writer.write(",")
                writer.write(cursor.getString(0))
                first = false
            }
        }
        writer.write("]}")
        writer.flush()
    }

    /** Validate the entire file first. Malformed/oversized files never partially change the database. */
    fun importFrom(reader: Reader): ReplayImportResult {
        val input = readBounded(reader)
        val parser = JSONTokener(input)
        val root = parser.nextValue() as? JSONObject ?: throw IllegalArgumentException("回放文件必须是 JSON 对象")
        require(parser.nextClean() == '\u0000') { "回放文件末尾有多余内容" }
        require(requiredString(root, "format") == EXPORT_FORMAT) { "这不是聊天回放导出文件" }
        require(requiredLong(root, "version") == EXPORT_VERSION.toLong()) { "不支持此回放文件版本" }
        val entries = root.get("cases") as? JSONArray ?: throw IllegalArgumentException("回放文件缺少 cases 数组")
        require(entries.length() <= ReplyReplayLogic.MAX_CASES) { "一次最多导入 ${ReplyReplayLogic.MAX_CASES} 条回放" }
        val validated = (0 until entries.length()).map { index ->
            val entry = entries.get(index) as? JSONObject ?: throw IllegalArgumentException("第 ${index + 1} 条回放格式无效")
            val item = decode(entry)
            val error = ReplyReplayLogic.validationError(item)
            require(error == null) { "第 ${index + 1} 条回放：$error" }
            item to encode(item).toString()
        }
        var imported = 0
        var skipped = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            var totalCount = count(db)
            var totalChars = totalPayloadLength(db)
            for ((item, payload) in validated) {
                // Existing ids always win, including an existing evaluation/learning assignment.
                if (payloadLength(db, item.id) != null) {
                    skipped++
                    continue
                }
                require(totalCount < ReplyReplayLogic.MAX_CASES) { "回放库最多保留 ${ReplyReplayLogic.MAX_CASES} 条，请先导出并清理旧记录" }
                require(totalChars + payload.length <= MAX_PAYLOAD_CHARS) { "回放库已达到容量限制，请先导出并清理旧记录" }
                check(db.insertOrThrow("replay_cases", null, values(item, payload)) != -1L)
                totalCount++
                totalChars += payload.length
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ReplayImportResult(imported, skipped)
    }

    private fun values(item: ReplyReplayCase, payload: String) = ContentValues().apply {
        put("id", item.id)
        put("scene", item.scene.name)
        put("context_key", ReplyReplayLogic.contextKey(item.turns, item.scene))
        put("dataset", item.dataset.name)
        put("reviewed", if (item.choice != null || item.correctedReply.isNotBlank()) 1 else 0)
        put("created_at", item.createdAt)
        put("payload_chars", payload.length)
        put("payload", payload)
    }

    private fun count(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM replay_cases", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun totalPayloadLength(db: SQLiteDatabase): Long = db.rawQuery("SELECT COALESCE(SUM(payload_chars),0) FROM replay_cases", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    private fun payloadLength(db: SQLiteDatabase, id: String): Int? = db.rawQuery(
        "SELECT payload_chars FROM replay_cases WHERE id=?", arrayOf(id),
    ).use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun encode(item: ReplyReplayCase): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("contact", item.contact)
        put("scene", item.scene.name)
        put("turns", JSONArray().apply { item.turns.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) } })
        put("originalReply", item.originalReply)
        put("correctedReply", item.correctedReply)
        put("reason", item.reason)
        put("dataset", item.dataset.name)
        put("candidateA", item.candidateA?.let(::encodeCandidate) ?: JSONObject.NULL)
        put("candidateB", item.candidateB?.let(::encodeCandidate) ?: JSONObject.NULL)
        put("choice", item.choice?.name ?: JSONObject.NULL)
        put("createdAt", item.createdAt)
    }

    private fun encodeCandidate(candidate: ReplayCandidate): JSONObject = JSONObject()
        .put("text", candidate.text).put("source", candidate.source)
        .put("durationMs", candidate.durationMs).put("error", candidate.error)

    private fun decode(json: JSONObject): ReplyReplayCase {
        val turns = json.get("turns") as? JSONArray ?: throw IllegalArgumentException("聊天上下文须为数组")
        require(turns.length() <= ReplyReplayLogic.MAX_TURNS) { "聊天上下文条数过多" }
        return ReplyReplayCase(
            id = requiredString(json, "id"),
            contact = requiredString(json, "contact"),
            scene = enumValueOf(requiredString(json, "scene")),
            turns = (0 until turns.length()).map { index ->
                val turn = turns.get(index) as? JSONObject ?: throw IllegalArgumentException("消息格式无效")
                ReplayTurn(requiredString(turn, "role"), requiredString(turn, "text"))
            },
            originalReply = requiredString(json, "originalReply"),
            correctedReply = requiredString(json, "correctedReply"),
            reason = requiredString(json, "reason"),
            dataset = enumValueOf(requiredString(json, "dataset")),
            candidateA = decodeCandidate(json, "candidateA"),
            candidateB = decodeCandidate(json, "candidateB"),
            choice = if (!json.has("choice") || json.isNull("choice")) null else enumValueOf<ReplayChoice>(requiredString(json, "choice")),
            createdAt = requiredLong(json, "createdAt"),
        )
    }

    private fun decodeCandidate(json: JSONObject, name: String): ReplayCandidate? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.get(name) as? JSONObject ?: throw IllegalArgumentException("候选回复格式无效")
        return ReplayCandidate(
            text = requiredString(value, "text"), source = requiredString(value, "source"),
            durationMs = requiredLong(value, "durationMs"), error = requiredString(value, "error"),
        )
    }

    private fun requiredString(json: JSONObject, name: String): String = json.get(name) as? String
        ?: throw IllegalArgumentException("字段 $name 必须是文字")

    private fun requiredLong(json: JSONObject, name: String): Long {
        val value = json.get(name)
        require(value is Int || value is Long) { "字段 $name 必须是整数" }
        return (value as Number).toLong()
    }

    private fun readBounded(reader: Reader): String {
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(output.length + count <= ReplyReplayLogic.MAX_IMPORT_CHARS) { "回放文件过大，最多支持 8 Mi 字符" }
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

    companion object {
        const val EXPORT_FORMAT = "soulbot.reply-replay"
        const val EXPORT_VERSION = 1
        // Leave room for the export envelope and separators; every complete export remains importable.
        private const val MAX_PAYLOAD_CHARS = ReplyReplayLogic.MAX_IMPORT_CHARS - 65_536
    }
}
