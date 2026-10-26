package com.soulbot.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Local, opt-in replay library. No Soul navigation or automatic model calls live here. */
class ChatReplayActivity : BaseSettingsActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var rows = emptyList<ReplyReplayCase>()
    private lateinit var list: ListView
    private lateinit var summary: TextView

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) transfer(uri, importing = false)
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) transfer(uri, importing = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_replay)
        setupToolbar("聊天回放与对比")
        list = findViewById(R.id.replayList)
        summary = findViewById(R.id.replaySummary)
        list.emptyView = findViewById(R.id.replayEmpty)
        list.adapter = ReplayRows()
        list.setOnItemClickListener { _, _, position, _ ->
            if (!busy) startActivity(Intent(this, ReplayEditorActivity::class.java).putExtra("replay_id", rows[position].id))
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (!busy) confirmDelete(rows[position])
            true
        }
        findViewById<View>(R.id.replayNew).setOnClickListener {
            startActivity(Intent(this, ReplayEditorActivity::class.java))
        }
        findViewById<View>(R.id.replayImport).setOnClickListener { confirmTransfer(importing = true) }
        findViewById<View>(R.id.replayExport).setOnClickListener { confirmTransfer(importing = false) }
        val root = findViewById<View>(R.id.replayListRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(edges.left, 0, edges.right, edges.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized && !busy) loadRows()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun loadRows() = runWork("读取记录") {
        ReplyReplayDatabase(applicationContext).use { db ->
            val items = db.list(100)
            val counts = db.summary()
            ui {
                rows = items
                (list.adapter as ReplayRows).notifyDataSetChanged()
                summary.text = "学习 ${counts.learning} · 仅测试 ${counts.evaluation} · 已评价 ${counts.reviewed}\n" +
                    "显示最近 100 条；点击查看，长按可删除。导出包含全部记录。"
            }
        }
    }

    private fun confirmTransfer(importing: Boolean) {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (importing) "导入回放记录？" else "导出完整回放记录？")
            .setMessage(if (importing) {
                "仅导入你信任的 JSON 文件。文件可能包含联系人、完整聊天片段、改写和评价；标为学习用途的已采纳记录可能用于后续参考。不会向 Soul 发送消息。"
            } else {
                "文件包含联系人、聊天片段、候选回复和你的评价，请妥善保管，勿公开分享。不会导出模型 API Key。"
            })
            .setNegativeButton("取消", null)
            .setPositiveButton(if (importing) "选择文件" else "选择保存位置") { _, _ ->
                if (importing) importFile.launch(arrayOf("application/json", "text/plain"))
                else exportFile.launch("soulbot-replay-${System.currentTimeMillis()}.json")
            }.show()
    }

    private fun transfer(uri: Uri, importing: Boolean) = runWork(if (importing) "导入" else "导出") {
        ReplyReplayDatabase(applicationContext).use { db ->
            val message = if (importing) {
                val stream = contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
                val result = stream.bufferedReader(Charsets.UTF_8).use(db::importFrom)
                "导入 ${result.imported} 条，跳过 ${result.skipped} 条"
            } else {
                val stream = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
                stream.bufferedWriter(Charsets.UTF_8).use(db::writeExport)
                "已导出完整回放记录，请妥善保管"
            }
            val items = db.list(100)
            val counts = db.summary()
            ui {
                rows = items
                (list.adapter as ReplayRows).notifyDataSetChanged()
                summary.text = "学习 ${counts.learning} · 仅测试 ${counts.evaluation} · 已评价 ${counts.reviewed}\n显示最近 100 条；点击查看，长按可删除。"
                showMessage(message, Snackbar.LENGTH_LONG)
            }
        }
    }

    private fun confirmDelete(item: ReplyReplayCase) {
        MaterialAlertDialogBuilder(this).setTitle("删除这条回放？")
            .setMessage("会删除该片段及其候选、改写和评价。不会删除 Soul 消息或本地聊天历史；只有之前导出的文件可以恢复。")
            .setNegativeButton("取消", null).setPositiveButton("删除记录") { _, _ ->
                runWork("删除") {
                    ReplyReplayDatabase(applicationContext).use { db ->
                        check(db.delete(item.id)) { "记录不存在或删除未成功" }
                        val items = db.list(100)
                        val counts = db.summary()
                        ui {
                            rows = items
                            (list.adapter as ReplayRows).notifyDataSetChanged()
                            summary.text = "学习 ${counts.learning} · 仅测试 ${counts.evaluation} · 已评价 ${counts.reviewed}\n显示最近 100 条；点击查看，长按可删除。"
                            showMessage("回放已删除；如有导出文件可重新导入")
                        }
                    }
                }
            }.show()
    }

    private fun runWork(action: String, block: () -> Unit) {
        if (busy || worker.isShutdown) return
        setBusy(true)
        worker.execute {
            val result = runCatching(block)
            ui {
                setBusy(false)
                result.exceptionOrNull()?.let { showMessage("$action 失败：${it.message ?: "请重试"}", Snackbar.LENGTH_LONG) }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }

    private fun setBusy(value: Boolean) {
        busy = value
        findViewById<View>(R.id.replayListProgress).visible(value)
        listOf(R.id.replayNew, R.id.replayImport, R.id.replayExport).forEach { findViewById<View>(it).isEnabled = !value }
    }

    private inner class ReplayRows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val context = this@ChatReplayActivity
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                minimumHeight = (80 * resources.displayMetrics.density).toInt()
                addView(TextView(context).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) })
                addView(TextView(context).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding(0, pad / 3, 0, 0)
                })
            }
            val item = rows[position]
            val reviewed = item.choice != null || item.correctedReply.isNotBlank()
            (row.getChildAt(0) as TextView).text = "${item.contact.ifBlank { "未命名片段" }} · ${if (reviewed) "已评价" else "待评价"}"
            val preview = item.turns.lastOrNull()?.text.orEmpty()
            (row.getChildAt(1) as TextView).text =
                "${if (item.dataset == ReplayDataset.LEARNING) "学习用途" else "仅测试"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt))}\n" +
                    preview.take(100) + if (preview.length > 100) "…" else ""
            return row
        }
    }
}
