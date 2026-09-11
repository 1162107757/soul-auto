package com.soulbot.app

import android.os.Bundle
import android.widget.EditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

class ChatSamplesActivity : BaseSettingsActivity() {
    private lateinit var learningDb: ChatLearningDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_samples)
        setupToolbar("聊天样本管理")
        learningDb = ChatLearningDatabase(applicationContext)
        val samples = findViewById<EditText>(R.id.etStyleSamples)
        findViewById<MaterialButton>(R.id.btnImportStyle).setOnClickListener {
            val parsed = ChatSampleParser.parse(samples.text.toString())
            if (parsed.isEmpty()) {
                showMessage("没有识别到“对方：/我：”格式的样本", Snackbar.LENGTH_LONG)
                return@setOnClickListener
            }
            val added = learningDb.importSamples(parsed)
            samples.text.clear()
            showMessage("已新增 $added 条聊天样本")
        }
        findViewById<MaterialButton>(R.id.btnClearStyle).setOnClickListener {
            confirmClear(
                "清除聊天风格样本？",
                "导入和自动学习的人工回复样本都会删除，联系人聊天记忆不受影响。",
            ) {
                learningDb.clearStyleSamples()
                showMessage("聊天风格样本已清除")
            }
        }
        findViewById<MaterialButton>(R.id.btnClearContactMemory).setOnClickListener {
            confirmClear(
                "清除旧版联系人摘要？",
                "旧版提取的联系人内容摘要会被删除，完整聊天记忆不受影响。",
            ) {
                learningDb.clearContactMemories()
                showMessage("旧版联系人摘要已清除")
            }
        }
    }

    override fun onDestroy() {
        learningDb.close()
        super.onDestroy()
    }
}
