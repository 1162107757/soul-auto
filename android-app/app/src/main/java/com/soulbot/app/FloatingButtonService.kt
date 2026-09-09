package com.soulbot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatView: TextView? = null
    private var statusPanel: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatusPanel()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        showFloat()
        handler.post(refreshRunnable)
    }

    private fun buildNotification(): Notification {
        val channelId = "soulbot_fg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "SoulBot", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SoulBot 运行中")
            .setContentText("点悬浮按钮开始/停止")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
    }

    private fun overlayParams(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun showFloat() {
        if (floatView != null) return
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        val view = TextView(this).apply {
            text = "开始"
            setBackgroundColor(Color.parseColor("#CC6200EE"))
            setTextColor(Color.WHITE)
            setPadding(28, 18, 28, 18)
            gravity = Gravity.CENTER
            textSize = 14f
        }

        // 默认在屏幕右侧、垂直居中
        val lp = overlayParams(screenWidth - 180, screenHeight / 2)

        // 信息面板，在悬浮球左侧
        val panel = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            textSize = 12f
            text = "已停止"
        }
        val panelLp = overlayParams(screenWidth - 420, screenHeight / 2 - 20)

        var initialX = 0
        var initialY = 0
        var panelInitX = 0
        var panelInitY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x; initialY = lp.y
                    panelInitX = panelLp.x; panelInitY = panelLp.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    lp.x = initialX + dx; lp.y = initialY + dy
                    panelLp.x = panelInitX + dx; panelLp.y = panelInitY + dy
                    if (Math.abs(event.rawX - touchX) > 10 || Math.abs(event.rawY - touchY) > 10) moved = true
                    windowManager.updateViewLayout(view, lp)
                    windowManager.updateViewLayout(panel, panelLp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggle()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, lp)
        floatView = view
        windowManager.addView(panel, panelLp)
        statusPanel = panel

        updateLabel()
    }

    private fun toggle() {
        val svc = SoulBotService.instance
        if (svc == null) {
            floatView?.text = "未开无障碍"
            return
        }
        if (SoulBotService.running) {
            svc.stop()
        } else {
            svc.start()
        }
        updateLabel()
    }

    private fun updateLabel() {
        floatView?.text = if (SoulBotService.running) "停止" else "开始"
    }

    private fun updateStatusPanel() {
        val panel = statusPanel ?: return
        val text = SoulBotService.statusText
        val deadline = SoulBotService.statusDeadline
        val remainSec = if (deadline > 0) (deadline - System.currentTimeMillis() + 500) / 1000 else -1
        panel.text = if (remainSec >= 0) "$text\n还有 ${remainSec} 秒" else text
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        floatView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        statusPanel?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        floatView = null
        statusPanel = null
        super.onDestroy()
    }
}
