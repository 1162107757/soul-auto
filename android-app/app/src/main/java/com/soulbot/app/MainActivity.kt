package com.soulbot.app

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var tvRuntimeTitle: TextView
    private lateinit var tvRuntimeDetail: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnOverlay: MaterialButton
    private lateinit var tvTaskSummary: TextView
    private lateinit var tvModelSummary: TextView
    private lateinit var tvProfileSummary: TextView
    private lateinit var tvReplySummary: TextView
    private lateinit var tvDataSummary: TextView
    private lateinit var memoryDb: ConversationMemoryDatabase
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDashboard()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyToolbarInsets()
        memoryDb = ConversationMemoryDatabase(applicationContext)
        HistoryDatabase(applicationContext).use { history ->
            memoryDb.seedLegacyHistory(history.getAll())
        }

        tvRuntimeTitle = findViewById(R.id.tvRuntimeTitle)
        tvRuntimeDetail = findViewById(R.id.tvRuntimeDetail)
        statusDot = findViewById(R.id.statusDot)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        tvTaskSummary = findViewById(R.id.tvTaskSummary)
        tvModelSummary = findViewById(R.id.tvModelSummary)
        tvProfileSummary = findViewById(R.id.tvProfileSummary)
        tvReplySummary = findViewById(R.id.tvReplySummary)
        tvDataSummary = findViewById(R.id.tvDataSummary)

        findViewById<android.view.View>(R.id.rowTaskSettings).setOnClickListener {
            open(TaskSettingsActivity::class.java)
        }
        findViewById<android.view.View>(R.id.rowModelSettings).setOnClickListener {
            open(ModelSettingsActivity::class.java)
        }
        findViewById<android.view.View>(R.id.rowProfileSettings).setOnClickListener {
            open(ProfileSettingsActivity::class.java)
        }
        findViewById<android.view.View>(R.id.rowReplyLearning).setOnClickListener {
            open(ReplyLearningActivity::class.java)
        }
        findViewById<android.view.View>(R.id.rowDataBackup).setOnClickListener {
            open(DataBackupActivity::class.java)
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnPrimary.setOnClickListener {
            if (!isOverlayEnabled()) {
                openOverlaySettings()
                return@setOnClickListener
            }
            val intent = Intent(this, FloatingButtonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Snackbar.make(btnPrimary, "悬浮控制已启动", Snackbar.LENGTH_SHORT).show()
            refreshDashboard()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshRunnable.run()
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        memoryDb.close()
        super.onDestroy()
    }

    private fun refreshDashboard() {
        val serviceAvailable = SoulBotService.instance != null
        val running = SoulBotService.running
        val accessibilityEnabled = isAccessibilityEnabled()
        val rawStatus = if (serviceAvailable) SoulBotService.statusText else Prefs.getLastStopReason(this)
        tvRuntimeTitle.text = if (!serviceAvailable && accessibilityEnabled) {
            "等待服务连接"
        } else {
            FloatingStatusFormatter.title(running, serviceAvailable, rawStatus)
        }
        tvRuntimeDetail.text = if (!serviceAvailable && accessibilityEnabled) {
            "无障碍设置已开启；若长时间未连接，请重新开启一次"
        } else if (!running && serviceAvailable &&
            rawStatus !in setOf("未配置 API Key", "模型配置不完整")
        ) {
            "自动任务未运行，请在悬浮控制中点击启动"
        } else {
            FloatingStatusFormatter.detail(
                running, serviceAvailable, rawStatus, SoulBotService.statusDeadline,
                System.currentTimeMillis(),
            )
        }
        val stateColor = when {
            running -> R.color.state_success
            !serviceAvailable -> R.color.state_warning
            else -> R.color.brand_primary
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, stateColor))

        val overlayEnabled = isOverlayEnabled()
        btnAccessibility.text = if (accessibilityEnabled) "无障碍设置已开启" else "开启无障碍"
        btnOverlay.text = if (overlayEnabled) "悬浮窗已授权" else "授权悬浮窗"
        btnPrimary.text = if (overlayEnabled) "启动悬浮控制" else "先授权悬浮窗"

        val mode = Prefs.getAutomationMode(this)
        tvTaskSummary.text = "${mode.displayName} · 回复等待 ${Prefs.getThinkMin(this)}–${Prefs.getThinkMax(this)} 秒"
        val modelEndpoints = ModelEndpointPrefs.getEndpoints(this)
        val enabledModels = modelEndpoints.filter(ModelEndpointRules::isRunnable)
        tvModelSummary.text = if (enabledModels.isEmpty()) {
            "尚无可用通道"
        } else {
            "已启用 ${enabledModels.size} 个 · 主用 ${enabledModels.first().displayName}"
        }

        val profile = Prefs.getSelfProfile(this)
        val completed = listOf(
            profile.gender, profile.age, profile.region,
            profile.zodiac, profile.occupation, profile.details,
        ).count(String::isNotBlank)
        tvProfileSummary.text = "已填写 $completed/6 项 · ${profile.region.ifBlank { "地区未填写" }}"
        val styleState = if (Prefs.getStyleLearningEnabled(this)) "风格学习开启" else "风格学习关闭"
        val memoryState = if (Prefs.getContactMemoryEnabled(this)) "记忆引用开启" else "记忆引用关闭"
        tvReplySummary.text = "$styleState · $memoryState"
        tvDataSummary.text = "${memoryDb.contactCount()} 位联系人 · ${memoryDb.messageCount()} 条消息"
    }

    private fun <T> open(activity: Class<T>) = startActivity(Intent(this, activity))

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun isOverlayEnabled(): Boolean = Settings.canDrawOverlays(this)
}
