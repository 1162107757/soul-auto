package com.soulbot.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class GreetDatabase(context: Context) : SQLiteOpenHelper(context, "greet.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE greeted (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "post_text TEXT NOT NULL, " +
                "time INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** 判断是否该给这个用户打招呼。 */
    fun shouldGreet(name: String, postText: String, intervalHours: Int = 24): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT post_text, time FROM greeted WHERE name=? ORDER BY time DESC LIMIT 1",
            arrayOf(name)
        )
        return try {
            if (!cursor.moveToFirst()) {
                true   // 没打过招呼
            } else {
                val lastText = cursor.getString(0)
                val lastTime = cursor.getLong(1)
                if (lastText == postText) {
                    false   // 内容一致，永不重复
                } else {
                    // 内容不同，至少隔 intervalHours 小时
                    (System.currentTimeMillis() - lastTime) >= intervalHours * 3600 * 1000L
                }
            }
        } finally {
            cursor.close()
        }
    }

    fun recordGreet(name: String, postText: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("post_text", postText)
            put("time", System.currentTimeMillis())
        }
        db.insert("greeted", null, values)
    }
}
