package com.soulbot.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        title = "互动记录"

        val db = HistoryDatabase(this)
        val items = db.getAll()
        val texts = items.map { it.display() }
        val listView = findViewById<ListView>(R.id.listHistory)
        listView.adapter = ArrayAdapter(this, R.layout.item_history, R.id.tvItem, texts)
    }

    private fun HistoryItem.display(): String =
        "【${name}】${HistoryDatabase.formatTime(time)}\n" +
            "对方：${theirMsg}\n" +
            "我：${myMsg}"
}
