package com.soulbot.app

import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RuntimeLogActivity : BaseSettingsActivity() {
    private lateinit var tvStats: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var tvContent: TextView
    private lateinit var scrollLog: NestedScrollView
    private lateinit var progress: CircularProgressIndicator
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnExport: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var logFilter: MaterialButtonToggleGroup
    private var cachedContent = ""
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) exportLog(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runtime_log)
        setupToolbar("运行日志")

        tvStats = findViewById(R.id.tvRuntimeLogStats)
        tvUpdated = findViewById(R.id.tvRuntimeLogUpdated)
        tvContent = findViewById(R.id.tvRuntimeLogContent)
        scrollLog = findViewById(R.id.scrollRuntimeLog)
        progress = findViewById(R.id.progressRuntimeLog)
        btnRefresh = findViewById(R.id.btnRefreshRuntimeLog)
        btnExport = findViewById(R.id.btnExportRuntimeLog)
        btnClear = findViewById(R.id.btnClearRuntimeLog)
        logFilter = findViewById(R.id.toggleRuntimeLogFilter)

        btnRefresh.setOnClickListener { refreshLog() }
        btnExport.setOnClickListener {
            exportLauncher.launch(
                "soulbot-diagnostics-${fileTimestamp().format(Date())}.txt",
            )
        }
        btnClear.setOnClickListener {
            confirmClear(
                "清空运行日志？",
                "本机保存的当前日志和历史滚动日志都会删除。建议先导出，删除后无法恢复。",
            ) { clearLog() }
        }
        logFilter.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) renderContent()
        }
        logFilter.check(R.id.btnRuntimeLogAll)
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        if (::tvContent.isInitialized) refreshLog()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshLog() {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                RuntimeLog.stats(applicationContext) to
                    RuntimeLog.readRecent(applicationContext, maxChars = 120_000)
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { (stats, content) ->
                    tvStats.text = if (stats.lineCount == 0) {
                        "暂无运行日志"
                    } else {
                        "${stats.lineCount} 条 · 错误 ${stats.errorCount} · 警告 ${stats.warningCount} · ${formatBytes(stats.totalBytes)}"
                    }
                    tvUpdated.text = when {
                        stats.lastModifiedAt <= 0L -> "任务运行后会自动记录"
                        else -> "${stats.fileCount} 个日志文件 · 最后更新 ${displayTimestamp().format(Date(stats.lastModifiedAt))}"
                    }
                    cachedContent = content
                    renderContent()
                    scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
                }.onFailure {
                    tvStats.text = "日志读取失败"
                    tvUpdated.text = it.message ?: "未知错误"
                }
            }
        }
    }

    private fun exportLog(uri: Uri) {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                val output = contentResolver.openOutputStream(uri) ?: error("无法打开导出文件")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    check(
                        RuntimeLog.writeExport(
                            applicationContext,
                            writer,
                            diagnosticHeaderLines(),
                        ),
                    ) { "写入诊断日志失败" }
                }
                RuntimeLog.stats(applicationContext)
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess { stats ->
                    showMessage(
                        "诊断日志已导出（${stats.lineCount} 条，${formatBytes(stats.totalBytes)}）",
                        Snackbar.LENGTH_LONG,
                    )
                }.onFailure {
                    showMessage("导出失败：${it.message ?: "未知错误"}", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    private fun clearLog() {
        setBusy(true)
        executor.execute {
            val result = runCatching {
                check(RuntimeLog.clear(applicationContext)) { "部分日志文件无法删除" }
            }
            if (!isDestroyed) runOnUiThread {
                setBusy(false)
                result.onSuccess {
                    tvStats.text = "暂无运行日志"
                    tvUpdated.text = "日志已清空"
                    cachedContent = ""
                    renderContent()
                    showMessage("运行日志已清空")
                }.onFailure {
                    showMessage("清空失败：${it.message ?: "未知错误"}", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnRefresh.isEnabled = !busy
        btnExport.isEnabled = !busy
        btnClear.isEnabled = !busy
    }

    private fun renderContent() {
        val visible = when (logFilter.checkedButtonId) {
            R.id.btnRuntimeLogProblems -> cachedContent.lineSequence()
                .filter { " WARN category=" in it || " ERROR category=" in it }
                .joinToString("\n")
            R.id.btnRuntimeLogErrors -> cachedContent.lineSequence()
                .filter { " ERROR category=" in it }
                .joinToString("\n")
            else -> cachedContent
        }
        tvContent.text = visible.ifBlank {
            if (cachedContent.isBlank()) {
                "暂无日志。启动自动任务后，页面识别、操作结果和异常会显示在这里。"
            } else {
                "当前筛选下没有记录。"
            }
        }
    }

    private fun diagnosticHeaderLines(): List<String> {
        val device = DeviceProfileSnapshot.capture(this)
        val compatibility = DeviceCompatibilityStore.read(this)
        val endpoints = ModelEndpointPrefs.getEndpoints(this)
        val runnableEndpoints = endpoints.filter(ModelEndpointRules::isRunnable)
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("未知")
        val accessibilityEnabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().contains(packageName)
        return listOf(
            "导出时间=${displayTimestamp().format(Date())}",
            "应用版本=$appVersion",
            "设备=${device.displayName()}",
            "Android=${device.androidVersion} (SDK ${device.sdk})",
            "屏幕=${device.displayWidthPx}x${device.displayHeightPx}; densityDpi=${device.densityDpi}; fontScale=${device.fontScale}; ${device.orientation}",
            "Soul版本=${device.soulVersion}",
            "自动模式=${Prefs.getAutomationMode(this).displayName}",
            "任务请求运行=${Prefs.getTaskShouldRun(this)}; 服务已连接=${SoulBotService.instance != null}; 当前运行=${SoulBotService.running}",
            "状态截止时间=${SoulBotService.statusDeadline}",
            "无障碍=$accessibilityEnabled; 悬浮窗=${Settings.canDrawOverlays(this)}",
            "模型通道=${endpoints.size}; 可用通道=${runnableEndpoints.size}",
            "设备能力=${compatibility.observedCapabilities.size}/${SoulUiCapability.entries.size}; 最近页面=${compatibility.latest?.page ?: "未知"}",
            "时区=${TimeZone.getDefault().id}",
            "隐私说明=API Key、Authorization 与 Token 会自动脱敏；日志不主动记录完整聊天正文",
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun fileTimestamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private fun displayTimestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
}
