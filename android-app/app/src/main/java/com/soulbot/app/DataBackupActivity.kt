package com.soulbot.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DataBackupActivity : BaseSettingsActivity() {
    private lateinit var memoryDb: ConversationMemoryDatabase
    private lateinit var tvStats: TextView
    private lateinit var progress: CircularProgressIndicator
    private lateinit var btnExport: MaterialButton
    private lateinit var btnImport: MaterialButton
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) exportMemory(uri) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) importMemory(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_backup)
        setupToolbar("数据与备份")
        memoryDb = ConversationMemoryDatabase(applicationContext)
        HistoryDatabase(applicationContext).use { history ->
            memoryDb.seedLegacyHistory(history.getAll())
        }
        tvStats = findViewById(R.id.tvConversationMemoryStats)
        progress = findViewById(R.id.progressData)
        btnExport = findViewById(R.id.btnExportConversationMemory)
        btnImport = findViewById(R.id.btnImportConversationMemory)

        findViewById<View>(R.id.rowHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnExport.setOnClickListener {
            exportLauncher.launch("soulbot-chat-memory-${System.currentTimeMillis()}.json")
        }
        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        findViewById<MaterialButton>(R.id.btnClearConversationMemory).setOnClickListener {
            confirmClear(
                "清空完整聊天记忆？",
                "所有联系人的逐条聊天记录都会从本机删除。建议先导出备份；此操作不会删除 Soul 内的消息。",
            ) {
                memoryDb.clearAll()
                refreshStats()
                showMessage("完整聊天记忆已清空")
            }
        }
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        if (::memoryDb.isInitialized) refreshStats()
    }

    override fun onDestroy() {
        if (::memoryDb.isInitialized) {
            executor.execute { memoryDb.close() }
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun refreshStats() {
        val useState = if (Prefs.getContactMemoryEnabled(this)) "回复时会参考" else "仅保存、不用于回复"
        tvStats.text = "${memoryDb.contactCount()} 位联系人\n${memoryDb.messageCount()} 条消息 · $useState"
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnExport.isEnabled = !busy
        btnImport.isEnabled = !busy
    }

    private fun exportMemory(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                val output = contentResolver.openOutputStream(uri) ?: error("无法打开导出文件")
                output.bufferedWriter(Charsets.UTF_8).use(memoryDb::writeExport)
                memoryDb.messageCount()
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { showMessage("已导出 $it 条聊天记忆", Snackbar.LENGTH_LONG) }
                    .onFailure { showMessage("导出失败：${it.message ?: "未知错误"}", Snackbar.LENGTH_LONG) }
            }
        }
    }

    private fun importMemory(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                val input = contentResolver.openInputStream(uri) ?: error("无法读取导入文件")
                input.bufferedReader(Charsets.UTF_8).use(memoryDb::importFrom)
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { imported ->
                    refreshStats()
                    val invalid = if (imported.invalid > 0) "，无效 ${imported.invalid} 条" else ""
                    showMessage(
                        "导入完成：新增 ${imported.added} 条，跳过重复 ${imported.skipped} 条$invalid",
                        Snackbar.LENGTH_LONG,
                    )
                }.onFailure {
                    showMessage("导入失败：${it.message ?: "文件格式错误"}", Snackbar.LENGTH_LONG)
                }
            }
        }
    }
}
