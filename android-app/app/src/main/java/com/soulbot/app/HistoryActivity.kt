package com.soulbot.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

class HistoryActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        setupToolbar("互动记录")

        val items = HistoryDatabase(this).use { database ->
            database.getAll()
        }
        val texts = items.map { it.display() }
        val listView = findViewById<ListView>(R.id.listHistory)
        listView.adapter = ArrayAdapter(this, R.layout.item_history, R.id.tvItem, texts)
        findViewById<android.widget.TextView>(R.id.tvEmptyHistory).visibility =
            if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun HistoryItem.display(): String =
        "【${name}】${HistoryDatabase.formatTime(time)}\n" +
            "对方：${theirMsg}\n" +
            "我：${myMsg}"
}
