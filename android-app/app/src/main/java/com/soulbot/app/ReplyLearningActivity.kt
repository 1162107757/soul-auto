package com.soulbot.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

class ReplyLearningActivity : BaseSettingsActivity() {
    private lateinit var learningDb: ChatLearningDatabase
    private lateinit var tvStats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reply_learning)
        setupToolbar("回复与学习")
        learningDb = ChatLearningDatabase(applicationContext)
        tvStats = findViewById(R.id.tvLearningStats)
        val styleSwitch = findViewById<MaterialSwitch>(R.id.switchStyleLearning)
        val memorySwitch = findViewById<MaterialSwitch>(R.id.switchContactMemory)
        styleSwitch.isChecked = Prefs.getStyleLearningEnabled(this)
        memorySwitch.isChecked = Prefs.getContactMemoryEnabled(this)
        styleSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setStyleLearningEnabled(this, checked)
            refreshStats()
        }
        memorySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setContactMemoryEnabled(this, checked)
            refreshStats()
        }
        findViewById<android.view.View>(R.id.rowChatSamples).setOnClickListener {
            startActivity(Intent(this, ChatSamplesActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::learningDb.isInitialized) refreshStats()
    }

    override fun onDestroy() {
        learningDb.close()
        super.onDestroy()
    }

    private fun refreshStats() {
        val style = if (Prefs.getStyleLearningEnabled(this)) "开启" else "关闭"
        val memory = if (Prefs.getContactMemoryEnabled(this)) "开启" else "关闭"
        tvStats.text =
            "风格学习 $style · ${learningDb.styleSampleCount()} 条样本\n" +
                "记忆引用 $memory · ${learningDb.contactMemoryCount()} 条旧版摘要"
    }
}
