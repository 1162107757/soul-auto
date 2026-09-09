package com.soulbot.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val name: String,
    val theirMsg: String,
    val myMsg: String,
    val time: Long,
)

class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT, " +
                "their_msg TEXT, " +
                "my_msg TEXT, " +
                "time INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun record(name: String, theirMsg: String, myMsg: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("their_msg", theirMsg)
            put("my_msg", myMsg)
            put("time", System.currentTimeMillis())
        }
        db.insert("history", null, values)
    }

    fun getAll(): List<HistoryItem> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT name, their_msg, my_msg, time FROM history ORDER BY time DESC", null)
        val list = mutableListOf<HistoryItem>()
        while (cursor.moveToNext()) {
            list.add(
                HistoryItem(
                    name = cursor.getString(0) ?: "",
                    theirMsg = cursor.getString(1) ?: "",
                    myMsg = cursor.getString(2) ?: "",
                    time = cursor.getLong(3),
                )
            )
        }
        cursor.close()
        return list
    }

    companion object {
        fun formatTime(t: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
    }
}
