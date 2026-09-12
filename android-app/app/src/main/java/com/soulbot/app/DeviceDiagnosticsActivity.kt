package com.soulbot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceDiagnosticsActivity : BaseSettingsActivity() {
    private lateinit var tvHeadline: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvLatest: TextView
    private lateinit var tvCapabilities: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_diagnostics)
        setupToolbar(getString(R.string.device_diagnostics_title))

        tvHeadline = findViewById(R.id.tvCompatibilityHeadline)
        tvDevice = findViewById(R.id.tvDeviceProfile)
        tvLatest = findViewById(R.id.tvLatestSoulSnapshot)
        tvCapabilities = findViewById(R.id.tvCapabilityStatus)

        findViewById<MaterialButton>(R.id.btnOpenSoulForDiagnostics).setOnClickListener {
            if (SoulBotService.running) {
                showMessage("请先在悬浮窗停止自动任务，再进行只读检测")
                return@setOnClickListener
            }
            val launch = packageManager.getLaunchIntentForPackage(SoulBotService.SOUL_PACKAGE)
            if (launch == null) {
                showMessage("未找到 Soul，请先安装")
            } else if (!isAccessibilityEnabled()) {
                showMessage("请先开启 SoulBot 无障碍服务")
            } else {
                showMessage("已开启只读检测：请依次打开聊天、星球和广场页面")
                startActivity(launch)
            }
        }
        findViewById<MaterialButton>(R.id.btnRefreshDiagnostics).setOnClickListener {
            refreshDiagnostics()
            showMessage("检测结果已刷新")
        }
        findViewById<MaterialButton>(R.id.btnCopyDiagnostics).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SoulBot device diagnostics", reportText()))
            showMessage("诊断信息已复制")
        }
        findViewById<android.view.View>(R.id.rowRuntimeLogs).setOnClickListener {
            startActivity(Intent(this, RuntimeLogActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnResetDiagnostics).setOnClickListener {
            confirmClear(
                "重置本机适配记录？",
                "会清除本设备累计检测能力和已学习的语音菜单兜底位置，不影响聊天记忆与其他设置。",
            ) {
                DeviceCompatibilityStore.clearCurrentDevice(this)
                refreshDiagnostics()
                showMessage("本机适配记录已重置")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tvDevice.isInitialized) refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val device = DeviceProfileSnapshot.capture(this)
        val state = DeviceCompatibilityStore.read(this)
        val observedCount = state.observedCapabilities.size
        val totalCount = SoulUiCapability.entries.size

        tvHeadline.text = when {
            SoulBotService.running -> getString(R.string.device_diagnostics_status_stop_task)
            !isAccessibilityEnabled() -> getString(R.string.device_diagnostics_status_accessibility)
            state.latest == null -> getString(R.string.device_diagnostics_status_waiting)
            else -> getString(R.string.device_diagnostics_status_count, observedCount, totalCount)
        }
        tvDevice.text = buildString {
            appendLine(device.displayName())
            appendLine("Android ${device.androidVersion}（API ${device.sdk}）")
            appendLine("${device.displayWidthPx} × ${device.displayHeightPx} px · ${device.densityDpi} dpi")
            appendLine("密度 ${formatDecimal(device.density)} · 字体缩放 ${formatDecimal(device.fontScale)}")
            append("${device.orientation} · Soul ${device.soulVersion}")
        }
        tvLatest.text = state.latest?.let { latest ->
            buildString {
                appendLine("最近页面：${latest.page}")
                appendLine("检测时间：${formatTime(latest.capturedAt)}")
                appendLine("Soul 窗口：${formatRegion(latest.rootBounds)}")
                appendLine("有效区域：${formatRegion(latest.effectiveBounds)}")
                append("可见节点：${latest.visibleNodeCount}")
            }
        } ?: "暂无 Soul 页面记录。点击下方按钮打开 Soul，停留在需要检测的页面数秒后再返回查看。检测过程只读取控件，不会点击或发送消息。"

        val capabilityLines = SoulUiCapability.entries.joinToString("\n") { capability ->
            val stateText = if (capability in state.observedCapabilities) "已检测" else "待检测"
            "$stateText  ${capability.label}"
        }
        val fallbackState = if (DeviceCompatibilityStore.hasLearnedVoiceMenuOffset(this)) {
            "已学习  本机语音菜单兜底位置"
        } else {
            "待学习  本机语音菜单兜底位置"
        }
        tvCapabilities.text = getString(
            R.string.device_diagnostics_capabilities_format,
            capabilityLines,
            fallbackState,
        )
    }

    private fun reportText(): String {
        val device = DeviceProfileSnapshot.capture(this)
        val state = DeviceCompatibilityStore.read(this)
        return buildString {
            appendLine("SoulBot 设备适配诊断")
            appendLine("设备：${device.displayName()}")
            appendLine("系统：Android ${device.androidVersion} / API ${device.sdk}")
            appendLine("屏幕：${device.displayWidthPx}x${device.displayHeightPx} / ${device.densityDpi}dpi")
            appendLine("密度：${formatDecimal(device.density)} / 字体：${formatDecimal(device.fontScale)} / ${device.orientation}")
            appendLine("Soul：${device.soulVersion}")
            appendLine("设备配置键：${device.deviceKey}")
            appendLine(
                "语音菜单兜底：" + if (DeviceCompatibilityStore.hasLearnedVoiceMenuOffset(this@DeviceDiagnosticsActivity)) {
                    "已学习"
                } else {
                    "未学习"
                },
            )
            val latest = state.latest
            if (latest == null) {
                appendLine("最近页面：暂无")
            } else {
                appendLine("最近页面：${latest.page} / ${formatTime(latest.capturedAt)}")
                appendLine("根窗口：${formatRegion(latest.rootBounds)}")
                appendLine("有效区域：${formatRegion(latest.effectiveBounds)}")
                appendLine("可见节点：${latest.visibleNodeCount}")
            }
            append("已检测能力：")
            append(
                state.observedCapabilities
                    .sortedBy(SoulUiCapability::ordinal)
                    .joinToString("、") { it.label }
                    .ifBlank { "暂无" },
            )
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains(packageName)
    }

    private fun formatRegion(region: UiRegion): String =
        "${region.left},${region.top} – ${region.right},${region.bottom}（${region.width}×${region.height}）"

    private fun formatDecimal(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
