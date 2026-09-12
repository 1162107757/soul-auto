package com.soulbot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingButtonService : Service() {

    private enum class DockSide { LEFT, RIGHT }

    private lateinit var windowManager: WindowManager
    private var floatView: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var expandedView: LinearLayout? = null
    private var collapsedView: LinearLayout? = null
    private var statusTitleTv: TextView? = null
    private var nextActionTv: TextView? = null
    private var actionTv: TextView? = null
    private var statusDot: View? = null
    private var collapsedActionIcon: ImageView? = null
    private var isCollapsed = false
    private var isDockedToEdge = true
    private var dragInProgress = false
    private var dragLayoutErrorReported = false
    private var dockSide = DockSide.RIGHT
    private var lastRenderedRunning: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        if (!dragInProgress && !isCollapsed && isDockedToEdge) setCollapsed(true)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatusPanel()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RuntimeLog.record(
            applicationContext,
            "floating service start command; startId=$startId; flags=$flags",
        )
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLog.record(applicationContext, "floating service created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        val shown = runCatching { showFloat() }
        if (shown.isFailure) {
            RuntimeLog.record(
                applicationContext,
                "floating window creation failed",
                shown.exceptionOrNull(),
            )
            stopSelf()
            return
        }
        RuntimeLog.record(applicationContext, "floating window shown")
        handler.post(refreshRunnable)
        if (Prefs.getTaskShouldRun(applicationContext)) {
            val service = SoulBotService.instance
            if (service == null) {
                RuntimeLog.record(applicationContext, "floating restore deferred; accessibility service unavailable")
            } else {
                RuntimeLog.record(applicationContext, "floating restore requested; restarting automation")
                service.start()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun safeOverlayRegion(): UiRegion {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val safe = UiRegion(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
            if (safe.isValid) return safe
        }
        val metrics = resources.displayMetrics
        return UiRegion(0, dp(24), metrics.widthPixels, metrics.heightPixels - dp(24))
    }

    private fun buildNotification(): Notification {
        val channelId = "soulbot_fg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "SoulBot", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SoulBot 运行中")
            .setContentText("悬浮窗可查看下一步操作与倒计时")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
    }

    private fun overlayParams(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun roundedShape(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun roundedRipple(color: Int, radiusDp: Int): RippleDrawable {
        val content = roundedShape(color, radiusDp)
        val mask = roundedShape(Color.WHITE, radiusDp)
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            content,
            mask,
        )
    }

    private fun dotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun showFloat() {
        if (floatView != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(10).toFloat()
            background = roundedShape(
                Color.parseColor("#F225262A"),
                28,
                Color.parseColor("#26FFFFFF"),
            )
        }

        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val statusArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "SoulBot 状态，可拖动悬浮窗"
        }

        val dot = View(this).apply {
            background = dotDrawable(Color.parseColor("#8B5CF6"))
        }
        statusArea.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(10)
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(120)
        }
        val statusTitle = TextView(this).apply {
            text = "已停止"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
        }
        val nextAction = TextView(this).apply {
            text = "点击启动"
            textSize = 11f
            setTextColor(Color.parseColor("#C7C9D1"))
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(160)
            setPadding(0, dp(3), 0, 0)
        }
        textColumn.addView(statusTitle)
        textColumn.addView(nextAction)
        statusArea.addView(textColumn)
        expanded.addView(statusArea)

        val action = TextView(this).apply {
            text = "启动"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(88)
            minHeight = dp(48)
            setPadding(dp(14), 0, dp(14), 0)
            compoundDrawablePadding = dp(8)
            background = roundedRipple(Color.parseColor("#6D28D9"), 24)
            contentDescription = "启动自动助手"
            isClickable = true
            isFocusable = true
        }
        expanded.addView(action, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(48),
        ).apply {
            marginStart = dp(8)
        })

        val collapsed = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedRipple(Color.TRANSPARENT, 28)
            contentDescription = "展开 SoulBot 悬浮窗"
            isClickable = true
            isFocusable = true
        }
        val collapsedStateIcon = ImageView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        collapsed.addView(collapsedStateIcon, LinearLayout.LayoutParams(dp(20), dp(20)))

        root.addView(expanded)
        root.addView(collapsed, LinearLayout.LayoutParams(dp(56), dp(56)))

        val safeRegion = safeOverlayRegion()
        val lp = overlayParams(
            safeRegion.right - dp(80),
            safeRegion.top + safeRegion.height / 2 - dp(40),
        )
        windowManager.addView(root, lp)

        floatView = root
        windowParams = lp
        expandedView = expanded
        collapsedView = collapsed
        statusTitleTv = statusTitle
        nextActionTv = nextAction
        actionTv = action
        statusDot = dot
        collapsedActionIcon = collapsedStateIcon

        // Window overlays dispatch a tap to the deepest child under the finger.
        // Bind the whole information cluster as a drag surface. A plain tap keeps
        // the panel open and restarts its five-second edge timer.
        listOf<View>(root, expanded, statusArea, dot, textColumn, statusTitle, nextAction)
            .forEach { target ->
            if (target !== statusArea) {
                target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            attachDragAndClick(target)
            }
        attachDragAndClick(action) { toggle() }
        listOf<View>(collapsed, collapsedStateIcon).forEach { target ->
            if (target !== collapsed) {
                target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            attachDragAndClick(target) { setCollapsed(false) }
        }

        root.post {
            val currentSafeRegion = safeOverlayRegion()
            lp.y = (currentSafeRegion.centerY - root.height / 2)
                .coerceAtLeast(currentSafeRegion.top)
            dockSide = DockSide.RIGHT
            isDockedToEdge = true
            snapToEdge()
            scheduleAutoCollapse()
        }
        updateStatusPanel()
    }

    private fun attachDragAndClick(view: View, onClick: (() -> Unit)? = null) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val movementThreshold = dp(6)

        if (onClick != null) {
            view.setOnClickListener {
                onClick()
                if (!isCollapsed) scheduleAutoCollapse()
            }
        } else {
            view.setOnClickListener(null)
        }
        view.setOnTouchListener { touchedView, event ->
            val root = floatView ?: return@setOnTouchListener false
            val lp = windowParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelAutoCollapse()
                    dragInProgress = true
                    dragLayoutErrorReported = false
                    initialX = lp.x
                    initialY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (abs(dx) >= movementThreshold || abs(dy) >= movementThreshold)) {
                        moved = true
                    }
                    if (moved) {
                        val safeRegion = safeOverlayRegion()
                        lp.x = (initialX + dx.toInt()).coerceIn(
                            safeRegion.left - root.width + dp(48),
                            safeRegion.right - dp(48),
                        )
                        lp.y = (initialY + dy.toInt()).coerceIn(
                            safeRegion.top,
                            (safeRegion.bottom - root.height).coerceAtLeast(safeRegion.top),
                        )
                        try {
                            windowManager.updateViewLayout(root, lp)
                        } catch (error: RuntimeException) {
                            if (!dragLayoutErrorReported) {
                                dragLayoutErrorReported = true
                                RuntimeLog.record(
                                    applicationContext,
                                    "floating window drag layout update failed",
                                    error,
                                )
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    dragInProgress = false
                    if (moved) {
                        finishDrag()
                    } else if (onClick != null) {
                        touchedView.performClick()
                    } else {
                        scheduleAutoCollapse()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragInProgress = false
                    if (moved) {
                        finishDrag()
                    }
                    // A system gesture or a temporary window-layout change may cancel
                    // touch delivery. Keep the panel expanded so the user's drag does
                    // not look like it got stuck and disappeared.
                    true
                }

                else -> false
            }
        }
    }

    private fun finishDrag() {
        dragInProgress = false
        val root = floatView ?: return
        val lp = windowParams ?: return
        if (root.width == 0) return

        val safeRegion = safeOverlayRegion()
        val edgeSlop = dp(8)
        val leftGap = lp.x - safeRegion.left
        val rightGap = safeRegion.right - (lp.x + root.width)

        when {
            leftGap <= edgeSlop -> {
                dockSide = DockSide.LEFT
                isDockedToEdge = true
                snapToEdge()
                scheduleAutoCollapse()
            }

            rightGap <= edgeSlop -> {
                dockSide = DockSide.RIGHT
                isDockedToEdge = true
                snapToEdge()
                scheduleAutoCollapse()
            }

            else -> {
                isDockedToEdge = false
                cancelAutoCollapse()
                if (isCollapsed) {
                    // A collapsed handle dragged away from the edge becomes a
                    // normal panel at the exact place where the user left it.
                    setCollapsed(false)
                } else {
                    clampFreePosition()
                }
            }
        }
    }

    private fun clampFreePosition() {
        val root = floatView ?: return
        val lp = windowParams ?: return
        if (root.width == 0) return
        val safeRegion = safeOverlayRegion()
        val horizontalPadding = dp(8)
        lp.x = lp.x.coerceIn(
            safeRegion.left + horizontalPadding,
            (safeRegion.right - root.width - horizontalPadding)
                .coerceAtLeast(safeRegion.left + horizontalPadding),
        )
        lp.y = lp.y.coerceIn(
            safeRegion.top,
            (safeRegion.bottom - root.height).coerceAtLeast(safeRegion.top),
        )
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (error: Exception) {
            RuntimeLog.record(applicationContext, "floating free-position clamp failed", error)
        }
    }

    private fun snapToEdge() {
        val root = floatView ?: return
        val lp = windowParams ?: return
        if (root.width == 0) return
        val safeRegion = safeOverlayRegion()
        lp.x = when {
            isCollapsed && dockSide == DockSide.LEFT -> safeRegion.left - dp(8)
            isCollapsed -> safeRegion.right - root.width + dp(8)
            dockSide == DockSide.LEFT -> safeRegion.left + dp(8)
            else -> safeRegion.right - root.width - dp(8)
        }
        lp.y = lp.y.coerceIn(
            safeRegion.top,
            (safeRegion.bottom - root.height).coerceAtLeast(safeRegion.top),
        )
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (error: Exception) {
            RuntimeLog.record(applicationContext, "floating edge snap failed", error)
        }
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        if (!dragInProgress && !isCollapsed && isDockedToEdge) {
            handler.postDelayed(autoCollapseRunnable, 5000)
        }
    }

    private fun cancelAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
    }

    private fun setCollapsed(collapsed: Boolean) {
        if (isCollapsed == collapsed) return
        val root = floatView ?: return
        cancelAutoCollapse()
        RuntimeLog.record(
            applicationContext,
            "floating panel state changed; collapsed=$collapsed; docked=$isDockedToEdge; side=${dockSide.name}",
        )
        root.animate().cancel()
        root.animate()
            .alpha(0.65f)
            .setDuration(80)
            .withEndAction {
                isCollapsed = collapsed
                expandedView?.visibility = if (collapsed) View.GONE else View.VISIBLE
                collapsedView?.visibility = if (collapsed) View.VISIBLE else View.GONE
                root.requestLayout()
                root.post {
                    if (isDockedToEdge) {
                        snapToEdge()
                    } else {
                        clampFreePosition()
                    }
                    root.animate().cancel()
                    root.animate().alpha(1f).setDuration(120).start()
                    if (!collapsed) scheduleAutoCollapse()
                }
            }
            .start()
    }

    private fun toggle() {
        val service = SoulBotService.instance
        if (service == null) {
            RuntimeLog.record(applicationContext, "floating toggle ignored; accessibility service unavailable")
            updateStatusPanel()
            return
        }
        if (SoulBotService.running) {
            service.stop()
        } else {
            service.start()
        }
        updateStatusPanel()
    }

    private fun updateStatusPanel() {
        val serviceAvailable = SoulBotService.instance != null
        val running = SoulBotService.running
        val rawStatus = SoulBotService.statusText
        val deadline = SoulBotService.statusDeadline
        val now = System.currentTimeMillis()

        statusTitleTv?.text = FloatingStatusFormatter.title(running, serviceAvailable, rawStatus)
        nextActionTv?.text = FloatingStatusFormatter.detail(
            running,
            serviceAvailable,
            rawStatus,
            deadline,
            now,
        )

        val activeColor = if (running) Color.parseColor("#22C55E") else Color.parseColor("#8B5CF6")
        statusDot?.background = dotDrawable(activeColor)
        collapsedView?.contentDescription =
            "${FloatingStatusFormatter.title(running, serviceAvailable, rawStatus)}，点击展开悬浮窗"

        if (lastRenderedRunning != running) {
            actionTv?.apply {
                text = if (running) "停止" else "启动"
                contentDescription = if (running) "停止自动助手" else "启动自动助手"
                background = roundedRipple(
                    if (running) Color.parseColor("#B4232D") else Color.parseColor("#6D28D9"),
                    24,
                )
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    ActionIconDrawable(running, dp(18)),
                    null,
                    null,
                    null,
                )
            }
            collapsedActionIcon?.setImageDrawable(ActionIconDrawable(running, dp(18)))
            lastRenderedRunning = running
        }
    }

    override fun onDestroy() {
        RuntimeLog.record(applicationContext, "floating service destroying")
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        floatView?.let {
            try {
                windowManager.removeView(it)
            } catch (error: Exception) {
                RuntimeLog.record(applicationContext, "floating window removal failed", error)
            }
        }
        floatView = null
        windowParams = null
        expandedView = null
        collapsedView = null
        statusTitleTv = null
        nextActionTv = null
        actionTv = null
        statusDot = null
        collapsedActionIcon = null
        dragInProgress = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        RuntimeLog.record(
            applicationContext,
            "device configuration changed; orientation=${newConfig.orientation}; screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}",
        )
        floatView?.post {
            if (isDockedToEdge) snapToEdge() else clampFreePosition()
        }
    }

    private class ActionIconDrawable(
        private val stop: Boolean,
        private val sizePx: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = sizePx * 0.28f
            if (stop) {
                canvas.drawRoundRect(
                    cx - radius,
                    cy - radius,
                    cx + radius,
                    cy + radius,
                    sizePx * 0.09f,
                    sizePx * 0.09f,
                    paint,
                )
            } else {
                val path = Path().apply {
                    moveTo(cx - radius * 0.72f, cy - radius)
                    lineTo(cx + radius, cy)
                    lineTo(cx - radius * 0.72f, cy + radius)
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = sizePx

        override fun getIntrinsicHeight(): Int = sizePx
    }

}
