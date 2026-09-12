package com.soulbot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random
import kotlin.math.roundToInt

class SoulBotService : AccessibilityService() {

    companion object {
        @Volatile var running = false
        @Volatile var instance: SoulBotService? = null
        @Volatile var statusText: String = "已停止"
        @Volatile var statusDeadline: Long = 0  // 下次动作的绝对时间戳（毫秒），0=无倒计时

        const val SOUL_PACKAGE = "cn.soulapp.android"
        const val DM_INTERVAL = 240L  // 广场私信间隔（秒）
        const val CONTEXT_LEN = 20
        const val MAX_SCROLLS_TO_TOP = 20
        const val SQUARE_RETRY_DELAY_MS = 3000L
        const val SQUARE_POST_LOAD_TIMEOUT_MS = 4500L
        const val SQUARE_POST_STABLE_MS = 1200L
        const val SQUARE_SCROLL_PREPARE_MS = 2000L
        const val SQUARE_SCROLL_SETTLE_MS = 1600L
        const val SQUARE_MAX_VIEWPORTS = 10
        const val MESSAGE_SETTLE_MS = 3000L
        const val MESSAGE_LOAD_TIMEOUT_MS = 8000L
        const val VOICE_TRANSCRIPT_STABLE_MS = 2000L
        const val VOICE_TRANSCRIPT_START_GRACE_MS = 1500L

        // Soul 控件 id
        const val ID_INPUT_BOX = "cn.soulapp.android:id/et_sendmessage"
        const val ID_CONTENT_TEXT = "cn.soulapp.android:id/content_text"
        const val ID_SEND_BUTTON = "cn.soulapp.android:id/btn_send"
        const val ID_CHAT_ITEM = "cn.soulapp.android:id/item_root"
        const val ID_CHAT_AVATAR = "cn.soulapp.android:id/chat_avatar"
        const val ID_MESSAGE_READ = "cn.soulapp.android:id/message_read"
        const val ID_VOICE_BUBBLE = "cn.soulapp.android:id/voice_bubble"
        const val ID_AUDIO_CONTENT = "cn.soulapp.android:id/audioContent"
        const val ID_EXPRESSION = "cn.soulapp.android:id/llExpression"
        const val ID_SHARED_POST_TEXT = "cn.soulapp.android:id/expandable_text"
        const val ID_CONV_LIST = "cn.soulapp.android:id/conversation_list"
        const val ID_CONV_ITEM = "cn.soulapp.android:id/item_content_root"
        const val ID_CONV_NAME = "cn.soulapp.android:id/name"
        const val ID_CONV_TIME = "cn.soulapp.android:id/time"
        const val ID_UNREAD_BADGE = "cn.soulapp.android:id/unread_msg_number"
        const val ID_SQUARE_TAB = "cn.soulapp.android:id/main_tab_square"
        const val ID_MSG_TAB = "cn.soulapp.android:id/main_tab_msg"
        const val ID_MSG_RED_DOT = "cn.soulapp.android:id/main_tab_msg_red_dot"
        const val ID_SQUARE_CARD = "cn.soulapp.android:id/llSquare"
        const val ID_SQUARE_TEXT = "cn.soulapp.android:id/square_item_text"
        const val ID_SQUARE_AVATAR = "cn.soulapp.android:id/flAvatar"
        const val ID_SQUARE_PAGER = "cn.soulapp.android:id/pager_square"
        const val ID_SQUARE_CHANNEL_TAB = "cn.soulapp.android:id/tv_tab"
        const val ID_GIFT_CLOSE = "cn.soulapp.android:id/tv_btn_close"
        const val ID_NO_SEND_CLOSE = "cn.soulapp.android:id/tv_no_send_close"
        const val ID_CHAT_SECRET = "cn.soulapp.android:id/tv_chat_secret"
        const val ID_PROFILE_NAME = "cn.soulapp.android:id/titlebar_text_tv"
        const val ID_QIYU_CHAT = "cn.soulapp.android:id/tv_love_chat"
        const val ID_QIYU_SIGNATURE = "cn.soulapp.android:id/tv_love_signature"
        const val ID_QIYU_REASON = "cn.soulapp.android:id/tv_recall_reason"
        const val ID_QIYU_CLOSE = "cn.soulapp.android:id/iv_love_close"
        const val ID_GREETING_TITLE = "cn.soulapp.android:id/tv_greeting_title"
        const val ID_GREETING_TIPS = "cn.soulapp.android:id/tv_tips"
        const val ID_PLANET_TAB = "cn.soulapp.android:id/main_tab_planet"
        const val ID_GENERIC_TITLE = "cn.soulapp.android:id/tv_title"
        const val ID_SOUL_MATCH_PERCENT = "cn.soulapp.android:id/pipeidu_new"
        const val SOUL_MATCH_TIMEOUT_MS = 180_000L
    }

    private enum class Screen {
        CHAT,
        SOUL_MATCH_CHAT,
        SOUL_PLANET,
        LIST,
        SQUARE,
        PROFILE,
        GIFT,
        NO_SEND,
        GREETING_LIST,
        QIYU_POPUP,
        OUTSIDE,
        OTHER,
    }

    private class StoppedException : RuntimeException()

    private data class SquarePostCandidate(
        val text: String,
        val avatar: AccessibilityNodeInfo,
        val viewportKey: String,
    )

    private val random = Random.Default
    private var worker: Thread? = null
    private var historyDb: HistoryDatabase? = null
    private var learningDb: ChatLearningDatabase? = null
    private var conversationMemoryDb: ConversationMemoryDatabase? = null
    @Volatile private var activeReplyName: String? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var lastDiagnosticsAt = 0L
    private var lastDiagnosticsSignature = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RuntimeLog.record(applicationContext, "accessibility service connected")
        if (Prefs.getTaskShouldRun(applicationContext)) {
            start()
        } else {
            statusText = Prefs.getLastStopReason(applicationContext)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != SOUL_PACKAGE) return
        captureCompatibilitySnapshot()
    }

    override fun onInterrupt() {
        RuntimeLog.record(applicationContext, "accessibility service interrupted")
    }

    override fun onDestroy() {
        RuntimeLog.record(applicationContext, "accessibility service destroyed; shouldRun=${Prefs.getTaskShouldRun(applicationContext)}")
        restartHandler.removeCallbacksAndMessages(null)
        running = false
        worker?.interrupt()
        instance = null
        super.onDestroy()
    }

    fun start() {
        Prefs.setTaskShouldRun(applicationContext, true)
        if (running || worker?.isAlive == true) {
            RuntimeLog.record(
                applicationContext,
                "task start ignored; running=$running; workerAlive=${worker?.isAlive == true}",
            )
            return
        }
        restartHandler.removeCallbacksAndMessages(null)
        running = true
        RuntimeLog.record(applicationContext, "task start requested; launching worker")
        worker = Thread { runLoop() }.apply { start() }
    }

    fun stop() {
        RuntimeLog.record(
            applicationContext,
            "task stop requested by user; running=$running; workerAlive=${worker?.isAlive == true}",
        )
        Prefs.setTaskShouldRun(applicationContext, false)
        Prefs.setLastStopReason(applicationContext, "用户手动停止")
        running = false
        statusText = "用户手动停止"
        statusDeadline = 0
        worker?.interrupt()
        // runLoop() clears the reference in finally. Keeping it until then prevents
        // a second worker from starting while the interrupted one is still unwinding.
    }

    // ===== 基础辅助 =====

    private fun root(): AccessibilityNodeInfo? =
        runCatching { rootInActiveWindow }.getOrNull()

    private fun isSoulForeground(): Boolean = runCatching {
        root()?.packageName?.toString() == SOUL_PACKAGE
    }.getOrDefault(false)

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun activeWindowRegion(): UiRegion {
        val rootBounds = root()?.let(::rect)?.toUiRegion()
        return AdaptiveUiPolicy.effectiveWindow(screenWidth(), screenHeight(), rootBounds)
    }

    private fun visibleContainerRegion(id: String): UiRegion? =
        findByIds(id)
            .asSequence()
            .filter(::isVisibleOnScreen)
            .map { rect(it).toUiRegion() }
            .filter(UiRegion::isValid)
            .maxByOrNull { it.width.toLong() * it.height }
            ?.intersect(activeWindowRegion())

    private fun swipeVertically(
        containerId: String,
        startRatio: Float,
        endRatio: Float,
        duration: Long = 300,
    ) {
        val area = visibleContainerRegion(containerId) ?: activeWindowRegion()
        val line = AdaptiveUiPolicy.verticalGesture(
            area = area,
            startRatio = startRatio,
            endRatio = endRatio,
            edgeInset = dp(8),
        )
        swipe(
            line.start.x.toFloat(),
            line.start.y.toFloat(),
            line.end.x.toFloat(),
            line.end.y.toFloat(),
            duration,
        )
    }

    private fun conversationListSnapshot(): String =
        findByIds(ID_CONV_NAME)
            .map { node -> rect(node).top to textOf(node) }
            .sortedBy { it.first }
            .joinToString("|") { (top, name) -> "$top:$name" }

    private fun scrollConversationToTop() {
        if (!isOnList()) return
        setNextStep("回到会话顶部")

        // RecyclerView exposes this action on supported Soul versions and can jump
        // directly to the first row without depending on device height.
        findById(ID_CONV_LIST)?.let { list ->
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
            }
            runCatching {
                list.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                    args,
                )
            }
            sleep(500)
        }

        // Verify that the list is really at the beginning. Keep scrolling backward
        // until the visible conversations stop changing, rather than assuming a
        // fixed number of gestures is enough.
        repeat(MAX_SCROLLS_TO_TOP) {
            if (!isOnList()) return
            val before = conversationListSnapshot()
            val list = findById(ID_CONV_LIST)
            val accepted = runCatching {
                list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            }.getOrDefault(false)
            if (!accepted) {
                // Fallback for Soul versions whose list does not publish scroll actions.
                swipeVertically(ID_CONV_LIST, 0.30f, 0.86f, 450)
            }
            sleep(500)
            val after = conversationListSnapshot()
            if (before.isNotEmpty() && after == before) return
        }
    }

    private fun findById(id: String): AccessibilityNodeInfo? = runCatching {
        root()?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
    }.getOrNull()

    private fun findByIds(id: String): List<AccessibilityNodeInfo> = runCatching {
        root()?.findAccessibilityNodeInfosByViewId(id) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun findExactText(text: String): List<AccessibilityNodeInfo> = runCatching {
        root()?.findAccessibilityNodeInfosByText(text)
            ?.filter { textOf(it) == text && isVisibleOnScreen(it) }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun findVisibleById(id: String): AccessibilityNodeInfo? {
        val r = root() ?: return null
        return runCatching {
            r.findAccessibilityNodeInfosByViewId(id).firstOrNull(::isVisibleOnScreen)
        }.getOrNull()
    }

    private fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean {
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return false
        val bounds = rect(node).toUiRegion()
        return bounds.isValid && bounds.intersect(activeWindowRegion()) != null
    }

    private fun rect(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        return r
    }

    private fun textOf(node: AccessibilityNodeInfo?): String =
        runCatching { node?.text?.toString()?.trim() ?: "" }.getOrDefault("")

    private fun tap(x: Float, y: Float): Boolean {
        val point = AdaptiveUiPolicy.clampPoint(
            UiPoint(x.roundToInt(), y.roundToInt()),
            activeWindowRegion(),
            dp(2),
        )
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        // Prefer semantic accessibility clicks. Some Soul child views are not clickable,
        // so walk up a few parents before falling back to a coordinate gesture.
        var clickable: AccessibilityNodeInfo? = node
        repeat(4) {
            val candidate = clickable ?: return@repeat
            val clicked = runCatching {
                candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) {
                return true
            }
            clickable = runCatching { candidate.parent }.getOrNull()
        }
        val r = rect(node)
        if (r.width() <= 0 || r.height() <= 0) return false
        return tap(r.exactCenterX().toFloat(), r.exactCenterY().toFloat())
    }

    private fun longPressNode(node: AccessibilityNodeInfo): Boolean {
        val clicked = runCatching {
            node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }.getOrDefault(false)
        if (clicked) {
            return true
        }
        val bounds = rect(node)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val path = Path().apply {
            moveTo(bounds.exactCenterX().toFloat(), bounds.exactCenterY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1200))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val area = activeWindowRegion()
        val start = AdaptiveUiPolicy.clampPoint(UiPoint(x1.roundToInt(), y1.roundToInt()), area, dp(2))
        val end = AdaptiveUiPolicy.clampPoint(UiPoint(x2.roundToInt(), y2.roundToInt()), area, dp(2))
        val path = Path().apply {
            moveTo(start.x.toFloat(), start.y.toFloat())
            lineTo(end.x.toFloat(), end.y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun setNextStep(action: String, delayMs: Long = 0L) {
        if (statusText != action) {
            RuntimeLog.record(
                applicationContext,
                "workflow state; stage=${diagnosticStage(action)}; stateId=${action.hashCode().toUInt().toString(16)}; delayMs=$delayMs",
            )
        }
        statusText = action
        statusDeadline = if (delayMs > 0L) System.currentTimeMillis() + delayMs else 0L
    }

    private fun diagnosticStage(action: String): String = when {
        action.contains("奇遇铃") -> "QIYU"
        action.contains("语音") || action.contains("转文字") -> "VOICE"
        action.contains("模型") || action.contains("生成") || action.contains("通道") -> "MODEL"
        action.contains("广场") || action.contains("同城") || action.contains("私聊") -> "SQUARE"
        action.contains("匹配") || action.contains("星球") -> "SOUL_MATCH"
        action.contains("消息") || action.contains("回复") || action.contains("发送") ||
            action.contains("输入") || action.contains("会话") || action.contains("招呼") -> "MESSAGE"
        action.contains("打开") || action.contains("返回") || action.contains("页面") ||
            action.contains("检测") -> "NAVIGATION"
        else -> "AUTOMATION"
    }

    private fun sleep(ms: Long) {
        // Short UI waits also get a real countdown. A longer deadline that was
        // explicitly reported by the workflow (for example the next square visit)
        // always takes precedence over these internal waits.
        val now = System.currentTimeMillis()
        val ownsDeadline = running && statusDeadline <= now
        if (ownsDeadline) statusDeadline = now + ms
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StoppedException()
        } finally {
            if (ownsDeadline) statusDeadline = 0
        }
    }

    private fun waitForInputText(expected: String, timeoutMs: Long = 1000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (textOf(findById(ID_INPUT_BOX)) == expected) return true
            sleep(100)
        }
        return textOf(findById(ID_INPUT_BOX)) == expected
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
        if (waitForInputText(text)) {
            return true
        }

        // ACTION_SET_TEXT is unreliable in some Soul releases. Use the public
        // accessibility paste action as a fallback and restore the user's clipboard.
        val input = findById(ID_INPUT_BOX) ?: return false
        tapNode(input)
        runCatching { input.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val currentText = textOf(input)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
        }
        runCatching { input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = runCatching { clipboard.primaryClip }.getOrNull()
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("SoulBot reply", text))
            sleep(100)
            runCatching {
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }.getOrDefault(false) && waitForInputText(text)
        } catch (e: Exception) {
            RuntimeLog.record(applicationContext, "message text injection failed", e)
            false
        } finally {
            try {
                if (oldClip != null) {
                    clipboard.setPrimaryClip(oldClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {}
        }
    }

    private fun contains(outer: Rect, inner: Rect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    // ===== 界面识别 =====

    private fun visibleTitleTexts(): List<String> =
        findByIds(ID_GENERIC_TITLE)
            .filter(::isVisibleOnScreen)
            .map(::textOf)
            .filter(String::isNotBlank)

    private fun visibleTextsContaining(query: String): List<String> = runCatching {
        root()?.findAccessibilityNodeInfosByText(query)
            ?.filter(::isVisibleOnScreen)
            ?.map(::textOf)
            ?.filter(String::isNotBlank)
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun soulMatchProfileTexts(): List<String> = buildList {
        // The conversation name and metadata use different ids in different Soul builds.
        displayedConversationName().takeIf(String::isNotBlank)?.let(::add)
        addAll(visibleTitleTexts())
        addAll(visibleTextsContaining("Ta的引力签"))
        addAll(visibleTextsContaining("Ta的星座"))
    }.distinct()

    private fun isSoulMatchChatSurface(): Boolean =
        findVisibleById(ID_INPUT_BOX) != null &&
            (findVisibleById(ID_SOUL_MATCH_PERCENT) != null ||
                visibleTitleTexts().any {
                    it.startsWith("Ta的引力签") || it.startsWith("Ta的星座")
                })

    private fun isSoulPlanetSurface(): Boolean =
        findVisibleById(ID_INPUT_BOX) == null &&
            (findExactText("灵魂匹配").isNotEmpty() ||
                findExactText("开始匹配").isNotEmpty())

    private fun currentScreen(): Screen = when {
        !isSoulForeground() -> Screen.OUTSIDE
        findVisibleById(ID_QIYU_CHAT) != null -> Screen.QIYU_POPUP
        findVisibleById(ID_NO_SEND_CLOSE) != null -> Screen.NO_SEND
        findVisibleById(ID_GIFT_CLOSE) != null -> Screen.GIFT
        isSoulMatchChatSurface() -> Screen.SOUL_MATCH_CHAT
        findVisibleById(ID_INPUT_BOX) != null -> Screen.CHAT
        findVisibleById(ID_CHAT_SECRET) != null -> Screen.PROFILE
        findVisibleById(ID_GREETING_TIPS) != null -> Screen.GREETING_LIST
        findVisibleById(ID_SQUARE_PAGER) != null -> Screen.SQUARE
        findVisibleById(ID_CONV_NAME) != null -> Screen.LIST
        isSoulPlanetSurface() -> Screen.SOUL_PLANET
        else -> Screen.OTHER
    }

    private fun captureCompatibilitySnapshot() {
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticsAt < 750L) return
        val currentRoot = root() ?: return
        if (currentRoot.packageName?.toString() != SOUL_PACKAGE) return
        lastDiagnosticsAt = now

        val rootBounds = rect(currentRoot).toUiRegion()
        val effectiveBounds = AdaptiveUiPolicy.effectiveWindow(
            screenWidth(),
            screenHeight(),
            rootBounds,
        )
        val capabilities = buildSet {
            if (findVisibleById(ID_PLANET_TAB) != null ||
                findVisibleById(ID_SQUARE_TAB) != null ||
                findVisibleById(ID_MSG_TAB) != null
            ) add(SoulUiCapability.BOTTOM_NAV)
            if (findVisibleById(ID_CONV_LIST) != null || findVisibleById(ID_CONV_NAME) != null) {
                add(SoulUiCapability.CHAT_LIST)
            }
            if (findVisibleById(ID_INPUT_BOX) != null) add(SoulUiCapability.CHAT_INPUT)
            if (findVisibleById(ID_SEND_BUTTON) != null) add(SoulUiCapability.SEND_BUTTON)
            if (findVisibleById(ID_SQUARE_PAGER) != null) add(SoulUiCapability.SQUARE)
            if (findByIds(ID_SQUARE_CHANNEL_TAB).any { node ->
                    if (!isVisibleOnScreen(node)) return@any false
                    val title = textOf(node)
                    title.isNotBlank() && title != "关注" && title != "推荐"
                }
            ) add(SoulUiCapability.LOCAL_CHANNEL)
            if (findVisibleById(ID_SOUL_MATCH_PERCENT) != null || isSoulPlanetSurface()) {
                add(SoulUiCapability.SOUL_MATCH)
            }
            if (findVisibleById(ID_VOICE_BUBBLE) != null) add(SoulUiCapability.VOICE_MESSAGE)
            if (findVisibleById(ID_QIYU_CHAT) != null) add(SoulUiCapability.QIYU)
            if (findVisibleById(ID_CHAT_SECRET) != null) add(SoulUiCapability.PROFILE_DM)
        }
        val page = when (currentScreen()) {
            Screen.CHAT -> "普通聊天"
            Screen.SOUL_MATCH_CHAT -> "灵魂匹配聊天"
            Screen.SOUL_PLANET -> "星球"
            Screen.LIST -> "聊天列表"
            Screen.SQUARE -> "广场"
            Screen.PROFILE -> "用户主页"
            Screen.GIFT -> "礼物限制弹窗"
            Screen.NO_SEND -> "暂不聊天弹窗"
            Screen.GREETING_LIST -> "新招呼列表"
            Screen.QIYU_POPUP -> "奇遇铃"
            Screen.OUTSIDE -> "Soul 外部"
            Screen.OTHER -> "未识别页面"
        }
        val visibleNodeCount = countVisibleNodes(currentRoot)
        val signature = listOf(
            page,
            rootBounds,
            effectiveBounds,
            capabilities.sortedBy(SoulUiCapability::ordinal).joinToString { it.key },
            visibleNodeCount / 10,
        ).joinToString("|")
        if (signature == lastDiagnosticsSignature) return
        lastDiagnosticsSignature = signature
        DeviceCompatibilityStore.record(
            applicationContext,
            SoulUiDiagnosticSnapshot(
                capturedAt = now,
                page = page,
                rootBounds = rootBounds,
                effectiveBounds = effectiveBounds,
                visibleNodeCount = visibleNodeCount,
                capabilities = capabilities,
            ),
        )
    }

    private fun countVisibleNodes(rootNode: AccessibilityNodeInfo): Int {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(rootNode)
        var count = 0
        while (pending.isNotEmpty() && count < 5000) {
            val node = pending.removeFirst()
            if (runCatching { node.isVisibleToUser }.getOrDefault(false)) count++
            for (index in 0 until node.childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(pending::addLast)
            }
        }
        return count
    }

    private fun isOnChat(): Boolean {
        val screen = currentScreen()
        return screen == Screen.CHAT || screen == Screen.SOUL_MATCH_CHAT
    }
    private fun isOnList() = currentScreen() == Screen.LIST
    private fun isOnSquare() = currentScreen() == Screen.SQUARE
    private fun isOnProfile() = currentScreen() == Screen.PROFILE
    private fun isOnGift() = currentScreen() == Screen.GIFT
    private fun isOnNoSend() = currentScreen() == Screen.NO_SEND

    private fun selectedSquareChannel(): String =
        findByIds(ID_SQUARE_CHANNEL_TAB)
            .firstOrNull {
                runCatching { it.isSelected }.getOrDefault(false) && isVisibleOnScreen(it)
            }
            ?.let(::textOf)
            .orEmpty()

    private fun ensureLocalSquareChannel(): String? {
        if (!isOnSquare()) return null
        val localTab = findByIds(ID_SQUARE_CHANNEL_TAB)
            .filter(::isVisibleOnScreen)
            .firstOrNull {
                val title = textOf(it)
                title.isNotEmpty() && title != "关注" && title != "推荐"
            } ?: return null
        val localTitle = textOf(localTab)
        if (runCatching { localTab.isSelected }.getOrDefault(false)) return localTitle

        setNextStep("切换到同城·$localTitle")
        tapNode(localTab)
        repeat(10) {
            sleep(250)
            if (selectedSquareChannel() == localTitle) return localTitle
        }
        RuntimeLog.record(applicationContext, "square channel switch failed")
        return null
    }

    // ===== 导航（都带验证） =====

    private fun returnToList(): Boolean {
        setNextStep("返回会话列表")
        // 从任意界面返回聊天列表：二级界面按 back，广场点聊天 tab
        for (i in 0 until 4) {
            if (!isSoulForeground()) return false
            if (currentScreen() == Screen.QIYU_POPUP) return false
            if (isOnList()) return true
            if (isOnSquare()) return gotoChatList()
            pressBack(); sleep(1000)
        }
        return isOnList()
    }

    private fun gotoSquare(): Boolean {
        if (!isSoulForeground()) return false
        if (currentScreen() == Screen.QIYU_POPUP) return false
        setNextStep("打开同城广场")
        for (i in 0 until 3) {
            val tab = findById(ID_SQUARE_TAB)
            if (tab != null) {
                tapNode(tab)
                for (w in 0 until 8) {
                    if (isOnSquare()) return ensureLocalSquareChannel() != null
                    sleep(800)
                }
                return isOnSquare() && ensureLocalSquareChannel() != null
            }
            if (!isSoulForeground()) return false
            if (currentScreen() == Screen.QIYU_POPUP) return false
            pressBack(); sleep(1000)
        }
        return isOnSquare() && ensureLocalSquareChannel() != null
    }

    private fun gotoChatList(): Boolean {
        if (!isSoulForeground()) return false
        if (currentScreen() == Screen.QIYU_POPUP) return false
        setNextStep("返回会话列表")
        for (i in 0 until 3) {
            val tab = findById(ID_MSG_TAB)
            if (tab != null) {
                tapNode(tab)
                for (w in 0 until 8) {
                    if (isOnList()) return true
                    sleep(800)
                }
                return isOnList()
            }
            if (!isSoulForeground()) return false
            if (currentScreen() == Screen.QIYU_POPUP) return false
            pressBack(); sleep(1000)
        }
        return isOnList()
    }

    private fun gotoSoulPlanet(): Boolean {
        if (!isSoulForeground()) return false
        if (isSoulPlanetSurface()) return true
        setNextStep("打开星球里的灵魂匹配")
        for (i in 0 until 3) {
            val tab = findVisibleById(ID_PLANET_TAB)
                ?: findExactText("星球").maxByOrNull { rect(it).top }
            if (tab != null) {
                tapNode(tab)
                repeat(12) {
                    if (isSoulPlanetSurface()) return true
                    sleep(500)
                }
                return isSoulPlanetSurface()
            }
            if (!isSoulForeground()) return false
            pressBack()
            sleep(1000)
        }
        return isSoulPlanetSurface()
    }

    private fun gotoModeIdleSurface(mode: AutomationMode): Boolean =
        when (mode.idleSurface) {
            IdleSurface.SOUL_PLANET -> gotoSoulPlanet()
            IdleSurface.LOCAL_SQUARE -> gotoSquare()
        }

    private fun startSoulMatch(): Boolean {
        if (!isSoulPlanetSurface()) return false
        val startButton = findExactText("开始匹配").firstOrNull()
        if (startButton != null) {
            setNextStep("点击“开始匹配”")
            if (!tapNode(startButton)) return false
            sleep(800)
        } else {
            // The button disappears while matching. Re-entering this method in that
            // state should continue waiting instead of tapping arbitrary coordinates.
            setNextStep("等待正在进行的灵魂匹配")
        }

        val deadline = System.currentTimeMillis() + SOUL_MATCH_TIMEOUT_MS
        while (running && isSoulForeground() && System.currentTimeMillis() < deadline) {
            when (currentScreen()) {
                Screen.SOUL_MATCH_CHAT -> return true
                Screen.QIYU_POPUP -> {
                    handleQiyu()
                    return false
                }
                else -> Unit
            }
            if (hasUnreadRedDot()) {
                setNextStep("发现聊天消息，暂停匹配并优先回复")
                gotoChatList()
                return false
            }
            val failed = listOf("匹配失败", "今日匹配次数已用完", "今日次数已用完")
                .any { findExactText(it).isNotEmpty() }
            if (failed) {
                setNextStep("本次灵魂匹配未成功")
                return false
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            setNextStep("等待灵魂匹配完成", remaining)
            sleep(minOf(1000L, remaining))
        }
        if (!running) return false
        setNextStep("灵魂匹配等待超时，本轮跳过")
        return false
    }

    private fun backToSquare(): Boolean {
        for (i in 0 until 4) {
            if (!isSoulForeground()) return false
            if (currentScreen() == Screen.QIYU_POPUP) return false
            if (isOnSquare()) return ensureLocalSquareChannel() != null
            pressBack(); sleep(1000)
        }
        return isOnSquare() && ensureLocalSquareChannel() != null
    }

    // ===== 延时 =====

    private fun thinkDelay(): Long {
        val min = Prefs.getThinkMin(applicationContext).coerceAtLeast(0)
        val max = Prefs.getThinkMax(applicationContext).coerceAtLeast(min + 1)
        return random.nextLong(min.toLong(), max.toLong()) * 1000L
    }

    private fun waitUnlessQiyu(delayMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + delayMs
        while (running && System.currentTimeMillis() < deadline) {
            if (currentScreen() == Screen.QIYU_POPUP) {
                handleQiyu()
                return false
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            sleep(minOf(250L, remaining))
        }
        return running
    }

    private fun replyDelay(timeStr: String): Long {
        val minutesAgo = UnreadConversationPolicy.ageMinutes(timeStr)
        if (minutesAgo >= 1) return 0L   // 消息已发超过 1 分钟，立即回
        return thinkDelay()
    }

    // ===== 拆分回复 =====

    private fun splitReply(text: String): List<String> {
        // Model formatting must not turn one thought into three incomplete bubbles.
        val singleBubble = text.trim().replace(Regex("""\s*\n+\s*"""), " ")
        return listOf(singleBubble)
    }

    // ===== 发送（仅聊天界面） =====

    private fun outgoingOccurrenceCount(text: String): Int {
        val target = normalizeReply(text)
        if (target.isEmpty()) return 0
        return readThread()
            .asSequence()
            .filter { it.first == "out" }
            .map { normalizeReply(it.second) }
            .count { it == target }
    }

    private fun visibleMessageOccurrenceCount(text: String): Int {
        val target = normalizeReply(text)
        if (target.isEmpty()) return 0
        return findByIds(ID_CONTENT_TEXT)
            .asSequence()
            .filter(::isVisibleOnScreen)
            .map { normalizeReply(textOf(it)) }
            .count { it == target }
    }

    private fun send(text: String): Boolean {
        if (!isOnChat()) {
            RuntimeLog.record(applicationContext, "message send rejected; reason=not_on_chat")
            return false
        }
        val pieces = splitReply(text)
        val attemptId = System.nanoTime().toString(36)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        RuntimeLog.record(
            applicationContext,
            "message send started; attemptId=$attemptId; parts=${pieces.size}; chars=${text.length}",
        )
        for ((i, piece) in pieces.withIndex()) {
            var inputReady = false
            while (running && isOnChat() && !inputReady) {
                setNextStep("输入第 ${i + 1} 段回复")
                val input = findById(ID_INPUT_BOX)
                if (input == null) {
                    RuntimeLog.record(
                        applicationContext,
                        "message input missing; attemptId=$attemptId; part=${i + 1}",
                    )
                    setNextStep("等待输入框后重试", SQUARE_RETRY_DELAY_MS)
                    sleep(SQUARE_RETRY_DELAY_MS)
                    continue
                }
                tapNode(input)
                sleep(300)
                if (!setText(input, piece)) {
                    RuntimeLog.record(
                        applicationContext,
                        "message input injection failed; attemptId=$attemptId; part=${i + 1}",
                    )
                    setNextStep("输入失败，重新输入第 ${i + 1} 段", SQUARE_RETRY_DELAY_MS)
                    sleep(SQUARE_RETRY_DELAY_MS)
                    continue
                }
                inputReady = true
            }
            if (!inputReady) return false

            sleep(500)
            val beforeOutgoingCount = outgoingOccurrenceCount(piece)
            val beforeVisibleCount = visibleMessageOccurrenceCount(piece)
            val sendButton = findVisibleById(ID_SEND_BUTTON) ?: run {
                RuntimeLog.record(
                    applicationContext,
                    "message send button missing; attemptId=$attemptId; part=${i + 1}",
                )
                setNextStep("未找到发送按钮，重新检查", 5000)
                sleep(5000)
                return false
            }

            var sent = false
            for (sendAttempt in 0 until 2) {
                setNextStep(
                    if (sendAttempt == 0) "发送第 ${i + 1} 段回复"
                    else "上次点击未生效，再发送一次",
                )
                val currentButton = findVisibleById(ID_SEND_BUTTON) ?: break
                tapNode(if (sendAttempt == 0) sendButton else currentButton)

                val confirmDeadline = System.currentTimeMillis() + 10_000L
                while (running && isOnChat() && System.currentTimeMillis() < confirmDeadline) {
                    if (outgoingOccurrenceCount(piece) > beforeOutgoingCount ||
                        visibleMessageOccurrenceCount(piece) > beforeVisibleCount
                    ) {
                        sent = true
                        break
                    }
                    val remaining = confirmDeadline - System.currentTimeMillis()
                    setNextStep("确认第 ${i + 1} 段已出现在聊天中", remaining)
                    sleep(500)
                }

                if (sent) break
                // Only click again when the original text is still in the input box.
                // If it has disappeared, the result is ambiguous and another click could duplicate it.
                if (normalizeReply(textOf(findVisibleById(ID_INPUT_BOX))) != normalizeReply(piece)) break
            }
            if (!sent) {
                RuntimeLog.record(
                    applicationContext,
                    "message send unconfirmed; attemptId=$attemptId; part=${i + 1}; durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
                )
                setNextStep("消息未确认发出，停止重复点击", 1500)
                sleep(1500)
                return false
            }
            if (i < pieces.size - 1) {
                val nextPartDelay = random.nextLong(2000, 5000)
                setNextStep("发送第 " + (i + 2) + " 段回复", nextPartDelay)
                sleep(nextPartDelay)
            }
        }
        RuntimeLog.record(
            applicationContext,
            "message send confirmed; attemptId=$attemptId; parts=${pieces.size}; durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
        return true
    }

    // ===== 读聊天消息（仅聊天界面） =====

    private fun messageRole(
        item: AccessibilityNodeInfo,
        avatars: List<AccessibilityNodeInfo>,
        readReceipts: List<AccessibilityNodeInfo>,
    ): String {
        val bounds = rect(item)
        val avatarBounds = avatars.firstOrNull { contains(bounds, rect(it)) }
            ?.let(::rect)
            ?.toUiRegion()
        val hasReadReceipt = readReceipts.any { contains(bounds, rect(it)) }
        val contentBounds = findByIds(ID_CONTENT_TEXT)
            .firstOrNull { contains(bounds, rect(it)) }
            ?.let(::rect)
            ?.toUiRegion()
        val chatArea = activeWindowRegion()
        return when (
            AdaptiveUiPolicy.messageSide(
                chatArea = chatArea,
                avatarBounds = avatarBounds,
                contentBounds = contentBounds,
                hasReadReceipt = hasReadReceipt,
            )
        ) {
            MessageSide.OUTGOING -> "out"
            MessageSide.INCOMING -> "in"
        }
    }

    private fun descendantsById(
        node: AccessibilityNodeInfo,
        id: String,
    ): List<AccessibilityNodeInfo> = runCatching {
        node.findAccessibilityNodeInfosByViewId(id) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun Rect.toUiRegion(): UiRegion = UiRegion(left, top, right, bottom)

    private fun transcriptForVoice(
        bubble: AccessibilityNodeInfo,
        item: AccessibilityNodeInfo? = null,
    ): String {
        // Prefer the actual message subtree. Some Soul releases render the
        // transcript as a sibling below the item, so fall back to geometric
        // ownership among all currently visible voice bubbles.
        item?.let { messageItem ->
            descendantsById(messageItem, ID_AUDIO_CONTENT)
                .asSequence()
                .filter(::isVisibleOnScreen)
                .map(::textOf)
                .firstOrNull(String::isNotBlank)
                ?.let { return it }
        }

        val voiceBounds = rect(bubble)
        val voices = findByIds(ID_VOICE_BUBBLE)
            .filter(::isVisibleOnScreen)
            .map { rect(it).toUiRegion() }
        val transcripts = findByIds(ID_AUDIO_CONTENT)
            .filter(::isVisibleOnScreen)
            .map { TranscriptCandidate(textOf(it), rect(it).toUiRegion()) }
        return VoiceTranscriptPolicy.transcriptFor(
            targetVoice = voiceBounds.toUiRegion(),
            visibleVoices = voices,
            transcripts = transcripts,
            maxHorizontalDistance = activeWindowRegion().width / 2,
            maxVerticalDistance = maxOf(dp(180), voiceBounds.height() * 4),
        )
    }

    private fun voiceTranscriptFrom(item: AccessibilityNodeInfo): String {
        val bubble = descendantsById(item, ID_VOICE_BUBBLE).firstOrNull()
            ?: findByIds(ID_VOICE_BUBBLE).firstOrNull { contains(rect(item), rect(it)) }
            ?: return ""
        return transcriptForVoice(bubble, item)
    }

    private fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> {
        if (!isOnChat()) return emptyList()
        val items = findByIds(ID_CHAT_ITEM)
        val avatars = findByIds(ID_CHAT_AVATAR)
        val messageDots = findByIds(ID_MESSAGE_READ)
        return findByIds(ID_VOICE_BUBBLE).filter { bubble ->
            val bubbleBounds = rect(bubble)
            val item = items.firstOrNull { contains(rect(it), bubbleBounds) }
            val hasUnreadDot = item != null && messageDots.any { dot ->
                val dotBounds = rect(dot)
                contains(rect(item), dotBounds) &&
                    dotBounds.centerX() > bubbleBounds.right &&
                    dotBounds.centerY() > bubbleBounds.centerY()
            }
            item != null &&
                messageRole(item, avatars, messageDots) == "in" &&
                hasUnreadDot &&
                transcriptForVoice(bubble, item).isBlank()
        }
    }

    private fun voiceActionNear(voiceBounds: Rect): AccessibilityNodeInfo? =
        findExactText("转文字")
            .filter { action ->
                val bounds = rect(action)
                kotlin.math.abs(bounds.centerY() - voiceBounds.centerY()) < maxOf(dp(180), voiceBounds.height() * 3) &&
                    kotlin.math.abs(bounds.centerX() - voiceBounds.centerX()) < activeWindowRegion().width / 2
            }
            .minByOrNull { action ->
                val bounds = rect(action)
                kotlin.math.abs(bounds.centerY() - voiceBounds.centerY()) * 4 +
                    kotlin.math.abs(bounds.centerX() - voiceBounds.centerX())
            }

    private fun convertIncomingVoiceMessages(): Boolean {
        val initialPending = pendingIncomingVoiceBubbles().size
        if (initialPending > 0) {
            RuntimeLog.record(applicationContext, "voice transcription batch started; pending=$initialPending")
        }
        repeat(8) { index ->
            val bubble = pendingIncomingVoiceBubbles().lastOrNull() ?: return true
            if (!running || !isOnChat()) return false
            val voiceBounds = rect(bubble)

            val directButton = voiceActionNear(voiceBounds)
            if (directButton != null) {
                RuntimeLog.record(applicationContext, "voice transcription action; path=direct; index=${index + 1}")
                setNextStep("点击未读语音旁的转文字")
                tapNode(directButton)
                sleep(700)
            } else {
                setNextStep("长按第 ${index + 1} 条未读语音")
                if (!longPressNode(bubble)) return false
                sleep(700)

                // Prefer the semantic menu item when this Soul version exposes it.
                // Keep a coordinate fallback only for the self-drawn menu variant.
                val menuButton = voiceActionNear(voiceBounds)
                if (menuButton != null) {
                    RuntimeLog.record(applicationContext, "voice transcription action; path=semantic_menu; index=${index + 1}")
                    DeviceCompatibilityStore.recordVoiceMenuOffset(
                        applicationContext,
                        voiceBounds.toUiRegion(),
                        rect(menuButton).toUiRegion(),
                        activeWindowRegion(),
                    )
                    setNextStep("点击语音菜单中的转文字")
                    tapNode(menuButton)
                } else {
                    val activeArea = activeWindowRegion()
                    val menuPoint = DeviceCompatibilityStore.learnedVoiceMenuPoint(
                        applicationContext,
                        voiceBounds.toUiRegion(),
                        activeArea,
                        edgeInset = dp(8),
                    ) ?: AdaptiveUiPolicy.voiceMenuFallback(
                        voiceBounds.toUiRegion(),
                        activeArea,
                        offsetPx = dp(56),
                        edgeInset = dp(8),
                    )
                    RuntimeLog.record(
                        applicationContext,
                        "voice transcription action; path=coordinate_fallback; learned=${DeviceCompatibilityStore.hasLearnedVoiceMenuOffset(applicationContext)}; index=${index + 1}",
                    )
                    setNextStep("点击语音菜单中的转文字")
                    tap(menuPoint.x.toFloat(), menuPoint.y.toFloat())
                }
                sleep(700)
            }

            val startedAt = System.currentTimeMillis()
            var lastTranscript = ""
            var stableSince = System.currentTimeMillis()
            while (running && isOnChat() && System.currentTimeMillis() - startedAt < 30_000L) {
                val currentBubble = findByIds(ID_VOICE_BUBBLE)
                    .filter(::isVisibleOnScreen)
                    .minByOrNull { candidate ->
                        val bounds = rect(candidate)
                        kotlin.math.abs(bounds.centerY() - voiceBounds.centerY()) * 4 +
                            kotlin.math.abs(bounds.centerX() - voiceBounds.centerX())
                    }
                    ?: bubble
                val transcript = transcriptForVoice(currentBubble)
                if (transcript != lastTranscript) {
                    lastTranscript = transcript
                    stableSince = System.currentTimeMillis()
                }
                val stableFor = System.currentTimeMillis() - stableSince
                val elapsed = System.currentTimeMillis() - startedAt
                if (lastTranscript.isNotBlank() &&
                    elapsed >= VOICE_TRANSCRIPT_START_GRACE_MS &&
                    stableFor >= VOICE_TRANSCRIPT_STABLE_MS
                ) {
                    break
                }
                val remaining = (VOICE_TRANSCRIPT_STABLE_MS - stableFor).coerceAtLeast(500L)
                setNextStep("等待语音转文字完成", remaining)
                sleep(500)
            }
            if (lastTranscript.isBlank()) {
                RuntimeLog.record(
                    applicationContext,
                    "voice transcription failed; reason=no_text; index=${index + 1}; durationMs=${System.currentTimeMillis() - startedAt}",
                )
                setNextStep("语音转文字失败，稍后重试", 5000)
                sleep(5000)
                return false
            }
            RuntimeLog.record(
                applicationContext,
                "voice transcription completed; index=${index + 1}; chars=${lastTranscript.length}; durationMs=${System.currentTimeMillis() - startedAt}",
            )
        }
        val complete = pendingIncomingVoiceBubbles().isEmpty()
        if (initialPending > 0) {
            RuntimeLog.record(applicationContext, "voice transcription batch ended; complete=$complete")
        }
        return complete
    }

    private fun readThread(): List<Pair<String, String>> {
        if (!isOnChat()) return emptyList()
        val items = findByIds(ID_CHAT_ITEM).sortedBy { rect(it).top }
        val avatars = findByIds(ID_CHAT_AVATAR)
        val texts = findByIds(ID_CONTENT_TEXT)
        val readReceipts = findByIds(ID_MESSAGE_READ)
        val result = mutableListOf<Pair<String, String>>()
        for (item in items) {
            val ib = rect(item)
            if (ib.width() <= 0 || ib.height() <= 0) continue
            var text = ""
            for (t in texts) {
                if (contains(ib, rect(t))) { text = textOf(t); break }
            }
            if (text.isEmpty()) text = voiceTranscriptFrom(item)
            if (text.isEmpty()) text = nonTextMessageFrom(item)
            if (text.isEmpty()) continue
            val role = messageRole(item, avatars, readReceipts)
            result.add(role to text)
        }
        return result
    }

    private fun nonTextMessageFrom(item: AccessibilityNodeInfo): String {
        fun descendantsById(id: String): List<AccessibilityNodeInfo> = runCatching {
            item.findAccessibilityNodeInfosByViewId(id) ?: emptyList()
        }.getOrDefault(emptyList())

        if (descendantsById(ID_EXPRESSION).isNotEmpty()) return "[表情]"

        val sharedPostText = descendantsById(ID_SHARED_POST_TEXT)
            .asSequence()
            .map(::textOf)
            .firstOrNull(String::isNotBlank)
        if (sharedPostText != null) return "[分享动态] $sharedPostText"

        // Some Soul versions expose picture/sticker messages only through the
        // descendant's accessibility description rather than a text node.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(item)
        var visited = 0
        while (stack.isNotEmpty() && visited < 80) {
            val node = stack.removeLast()
            visited++
            val description = runCatching {
                node.contentDescription?.toString()?.trim().orEmpty()
            }.getOrDefault("")
            when {
                description.contains("表情") -> return "[表情]"
                description.contains("图片") || description.contains("照片") -> return "[图片]"
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(stack::add)
            }
        }
        return ""
    }

    private fun incomingSignature(thread: List<Pair<String, String>>): String =
        thread.filter { it.first == "in" }.joinToString("\u0001") { it.second }

    private fun waitForStableThread(name: String): List<Pair<String, String>> {
        if (!isOnChat()) return emptyList()
        if (!convertIncomingVoiceMessages()) {
            // Never answer using older text while an unread voice message is still
            // unresolved. Keeping the unread marker lets the next list pass retry it.
            return emptyList()
        }

        var latest = emptyList<Pair<String, String>>()
        var lastSignature = ""
        var stableSince = System.currentTimeMillis()
        val loadDeadline = System.currentTimeMillis() + MESSAGE_LOAD_TIMEOUT_MS
        while (running && isOnChat()) {
            latest = readThread()
            val signature = incomingSignature(latest)
            if (signature != lastSignature) {
                lastSignature = signature
                stableSince = System.currentTimeMillis()
            }
            if (signature.isEmpty()) {
                val remaining = loadDeadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    setNextStep("未读取到 $name 的未读内容，返回列表重新检查")
                    return latest
                }
                setNextStep("等待 $name 的消息加载", remaining)
                sleep(minOf(500L, remaining))
                continue
            }

            val remaining = MESSAGE_SETTLE_MS - (System.currentTimeMillis() - stableSince)
            if (remaining <= 0L) return latest
            setNextStep("等待 $name 发完消息", remaining)
            sleep(minOf(500L, remaining))
        }
        return latest
    }

    // ===== 扫未读（仅会话列表） =====

    private fun scanUnread(): List<UnreadConversationCandidate> {
        if (!isOnList()) return emptyList()
        val items = findByIds(ID_CONV_ITEM)
            .filter(::isVisibleOnScreen)
            .sortedBy { rect(it).top }
        val names = findByIds(ID_CONV_NAME).filter(::isVisibleOnScreen)
        val times = findByIds(ID_CONV_TIME).filter(::isVisibleOnScreen)
        // Soul keeps recycled/hidden unread badges in the accessibility tree. Using
        // them made an already-read row reopen forever and wait for content that
        // could never be "unread" again.
        val badges = findByIds(ID_UNREAD_BADGE).filter(::isVisibleOnScreen)
        val result = mutableListOf<UnreadConversationCandidate>()
        for ((displayOrder, item) in items.withIndex()) {
            val ib = rect(item)
            val hasBadge = badges.any { contains(ib, rect(it)) }
            if (!hasBadge) continue
            var name = ""
            for (n in names) { if (contains(ib, rect(n))) { name = textOf(n); break } }
            var time = ""
            for (tm in times) { if (contains(ib, rect(tm))) { time = textOf(tm); break } }
            if (name.isNotBlank()) {
                result += UnreadConversationCandidate(name, time, displayOrder)
            }
        }
        val ordered = UnreadConversationPolicy.oldestFirst(result)
        RuntimeLog.record(
            applicationContext,
            "unread scan completed; visibleRows=${items.size}; visibleBadges=${badges.size}; candidates=${ordered.size}; selectedContact=${ordered.firstOrNull()?.let { DiagnosticIdentity.contactId(applicationContext, it.name) } ?: "none"}",
        )
        return ordered
    }

    private fun findConversation(name: String): AccessibilityNodeInfo? {
        if (!isOnList()) return null
        val items = findByIds(ID_CONV_ITEM).filter(::isVisibleOnScreen)
        val names = findByIds(ID_CONV_NAME).filter(::isVisibleOnScreen)
        for (item in items) {
            val ib = rect(item)
            for (n in names) {
                if (contains(ib, rect(n)) && textOf(n) == name) return item
            }
        }
        return null
    }

    // ===== LLM =====

    private fun normalizeReply(text: String): String =
        text.trim().replace(Regex("""[\s，。！？!?、~～]+"""), "")

    private fun baseSystemPrompt(): String =
        ModelClient.buildSystemPrompt(Prefs.getSelfProfile(applicationContext))

    private fun routeModelChat(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
    ): String? = ModelRouter.chat(
        context = applicationContext,
        systemPrompt = systemPrompt,
        messages = messages,
        onProgress = { text, delayMs -> setNextStep(text, delayMs) },
        waitBeforeNext = ::sleep,
    )

    private fun genReply(
        contactName: String,
        thread: List<Pair<String, String>>,
        focusIncoming: List<String> = emptyList(),
        rejectedReplies: List<String> = emptyList(),
    ): String? {
        val recent = thread.takeLast(CONTEXT_LEN)
        val contextAnalysis = ConversationReplyContext.analyze(recent, focusIncoming)
        val msgs = mutableListOf<Pair<String, String>>()
        for ((role, text) in recent) {
            msgs.add((if (role == "in") "user" else "assistant") to text)
        }
        val latest = contextAnalysis.focusIncoming
        val shouldAskQuestion = ConversationReplyContext.shouldAskEngagingQuestion(recent, latest)
        val recentSent = recent.filter { it.first == "out" }.takeLast(4).map { it.second }
        val avoid = (recentSent + rejectedReplies).filter { it.isNotBlank() }.distinct()
        var systemPrompt = baseSystemPrompt() +
            " 必须优先回应对方本轮最新发来的内容，不能忽略最新问题，也不能照搬之前已经发过的回复。"
        systemPrompt += personalizationContext(contactName, latest.joinToString("\n"))
        if (avoid.isNotEmpty()) {
            systemPrompt += " 以下句子已经发过或因重复被拒绝，禁止再次输出：${avoid.joinToString("｜")}"
        }
        if (latest.isNotEmpty()) {
            systemPrompt += "\n本轮重点回应的消息：${latest.joinToString(" / ")}"
        }
        if (contextAnalysis.latestIsLowInformation) {
            systemPrompt += "\n对方最新一句只是“哦、嗯、对”这类低信息承接语，不能只围绕这几个字空泛回应。" +
                "请结合下面最近多轮，判断它承接的具体话题，再自然延续那个话题：可以接一个相关细节、说一点自己的真实反应，或问一个紧贴该话题的小问题。" +
                "禁止只回复语气词，也不要输出“然后呢”“还有呢”“怎么啦”“那你呢”这类无上下文的万能追问。" +
                "\n用于判断话题的最近对话：\n${contextAnalysis.supportingTranscript}"
        }
        if (shouldAskQuestion) {
            systemPrompt += "\n这轮回复不能停在附和、复述或结论上。先接住对方刚说的具体点，再只抛一个紧贴当前话题、低压力而且容易回答的小问题作为引子。" +
                "优先从具体细节、二选一、当时场景或轻微猜测中选一种，例如问‘是味道太冲还是有股怪味’，而不是泛泛问候。" +
                "不要只说“你呢”“然后呢”“你怎么样”“平时干嘛”“今天上班没”，不要突然换题或连续盘问。"
        } else {
            systemPrompt += "\n这一轮不要再提问题，也不要用问号结尾。直接回答对方，或接住具体细节说一点简短、真实的反应；" +
                "不要为了延续聊天强行反问，给对方留一点自然回应空间。"
        }
        return routeModelChat(systemPrompt, msgs)
    }

    private fun genDmReply(
        contactName: String,
        postText: String,
        rejectedReplies: List<String> = emptyList(),
    ): String? {
        var sys = baseSystemPrompt() +
            " 现在要根据对方发的一条广场动态，写一句自然的私聊开场白，能接上这条动态。" +
            "开场白必须包含一个紧贴动态具体内容、对方很容易回答的小问题，不能只评价或复述动态。" +
            personalizationContext(contactName, postText)
        if (rejectedReplies.isNotEmpty()) {
            sys += " 以下候选已被拒绝，禁止重复：${rejectedReplies.joinToString("｜")}"
        }
        return routeModelChat(
            sys,
            listOf("user" to "对方发的广场动态：$postText\n写一句自然、口语化、简短的私聊开场白。"),
        )
    }

    private fun genQiyuReply(
        signature: String,
        reason: String,
        qiyuTag: String,
        rejectedReplies: List<String> = emptyList(),
    ): String? {
        var sys = baseSystemPrompt() +
            " 现在对方通过奇遇铃匹配了你，要根据Ta的签名和引力签写一句自然的破冰开场白。" +
            "必须带一个紧贴签名、引力签或匹配理由的具体小问题，让对方容易接话。" +
            personalizationContext(signature, "$reason\n$qiyuTag")
        if (rejectedReplies.isNotEmpty()) {
            sys += " 以下候选已被拒绝，禁止重复：${rejectedReplies.joinToString("｜")}"
        }
        val userMsg = "对方签名：$signature\n匹配理由：$reason\n引力签：$qiyuTag\n" +
            "写一句自然、口语化、简短的破冰开场白，能接上对方的引力签或匹配理由。"
        return routeModelChat(sys, listOf("user" to userMsg))
    }

    private fun genSoulMatchReply(
        contactName: String,
        gravityTags: String,
        zodiac: String,
        rejectedReplies: List<String> = emptyList(),
    ): String? {
        val basis = SoulMatchContent.openingBasis(contactName, gravityTags, zodiac)
        var sys = baseSystemPrompt() +
            " 你刚通过灵魂匹配进入聊天。根据当前能看到的资料写一句有真人感的开场白，并带一个紧贴资料、对方容易回答的小问题。" +
            "有引力签时优先聊引力签；没有引力签时自然地从昵称和星座中选一个切入，二者都可用时可以轻巧组合。" +
            "聊星座只能当作轻松话题，不能武断分析性格；聊昵称不要生硬解释字面意思。" +
            "不要自我介绍、通用问候、客服式套话或‘感觉你很特别’这类空话，只输出一句简短口语。" +
            personalizationContext(contactName, basis)
        if (rejectedReplies.isNotEmpty()) {
            sys += " 以下候选已被拒绝，禁止重复：${rejectedReplies.joinToString("｜")}"
        }
        val userMsg = "当前可用资料：$basis\n" +
            "根据资料写一句自然、简短、像真人刚匹配时会发的开场白；必须给对方一个容易回答的具体话口。"
        return routeModelChat(sys, listOf("user" to userMsg))
    }

    private fun generateValidatedChatReply(
        contactName: String,
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
    ): String? {
        val requireTopicContinuation = focusIncoming.lastOrNull()
            ?.let(ConversationReplyContext::isLowInformation) == true
        val requireEngagingHook = ConversationReplyContext.shouldAskEngagingQuestion(
            thread,
            focusIncoming,
        )
        val rejected = mutableListOf<String>()
        var totalAttempts = 0
        var modelFailures = 0

        while (running && isOnChat() && totalAttempts < 6 && modelFailures < 2) {
            totalAttempts++
            setNextStep(
                "根据 $contactName 的最新消息生成回复（第 $totalAttempts 次）",
                ModelRouter.MAX_GENERATION_WAIT_MS,
            )
            val rawCandidate = genReply(contactName, thread, focusIncoming, rejected)
            if (!running) throw StoppedException()
            val candidate = rawCandidate?.let(ReplyNaturalness::sanitize)
            if (candidate.isNullOrBlank()) {
                modelFailures++
                RuntimeLog.record(
                    applicationContext,
                    "reply generation empty; attempt=$totalAttempts; modelFailures=$modelFailures",
                )
                if (modelFailures < 2) {
                    val retryDelay = 3000L
                    val reason = ModelRouter.lastError.ifBlank { "模型未返回文字" }.take(36)
                    setNextStep("生成失败：$reason，换通道后再试一次", retryDelay)
                    sleep(retryDelay)
                }
                continue
            }

            val issue = ReplyNaturalness.rejectionReason(
                candidate,
                requireTopicContinuation = requireTopicContinuation,
                requireEngagingHook = requireEngagingHook,
                forbidQuestion = !requireEngagingHook,
            )
            if (issue != null) {
                rejected += candidate
                RuntimeLog.record(
                    applicationContext,
                    "reply quality rejected; attempt=$totalAttempts; reason=${issue.take(80)}; chars=${candidate.length}",
                )
                val switched = ModelRouter.markQualityRejected(applicationContext)
                if (totalAttempts < 6) {
                    val action = if (switched) "$issue，切换下一模型" else "$issue，重新生成"
                    setNextStep(action, 1000)
                    sleep(1000)
                }
                continue
            }
            ModelRouter.markAccepted(applicationContext)
            RuntimeLog.record(
                applicationContext,
                "reply generation accepted; attempts=$totalAttempts; chars=${candidate.length}; hookRequired=$requireEngagingHook",
            )
            return candidate
        }
        RuntimeLog.record(
            applicationContext,
            "reply generation exhausted; attempts=$totalAttempts; modelFailures=$modelFailures; qualityRejected=${rejected.size}",
        )
        return null
    }

    private fun generateValidatedOpening(
        action: String,
        generator: (List<String>) -> String?,
    ): String? {
        val rejected = mutableListOf<String>()
        var totalAttempts = 0
        var modelFailures = 0
        while (running && isOnChat() && totalAttempts < 5 && modelFailures < 2) {
            totalAttempts++
            setNextStep("$action（第 $totalAttempts 次）", ModelRouter.MAX_GENERATION_WAIT_MS)
            val candidate = generator(rejected)?.let(ReplyNaturalness::sanitize)
            if (!running) throw StoppedException()
            if (candidate.isNullOrBlank()) {
                modelFailures++
                RuntimeLog.record(
                    applicationContext,
                    "opening generation empty; attempt=$totalAttempts; modelFailures=$modelFailures",
                )
                if (modelFailures < 2) {
                    setNextStep("开场白生成失败，换通道后再试一次", 3000)
                    sleep(3000)
                }
                continue
            }
            val issue = ReplyNaturalness.rejectionReason(
                candidate,
                requireEngagingHook = true,
            )
            if (issue != null) {
                rejected += candidate
                RuntimeLog.record(
                    applicationContext,
                    "opening quality rejected; attempt=$totalAttempts; reason=${issue.take(80)}; chars=${candidate.length}",
                )
                val switched = ModelRouter.markQualityRejected(applicationContext)
                if (totalAttempts < 5) {
                    val next = if (switched) "$issue，切换下一模型" else "$issue，重新生成开场白"
                    setNextStep(next, 1000)
                    sleep(1000)
                }
                continue
            }
            ModelRouter.markAccepted(applicationContext)
            RuntimeLog.record(
                applicationContext,
                "opening generation accepted; attempts=$totalAttempts; chars=${candidate.length}",
            )
            return candidate
        }
        RuntimeLog.record(
            applicationContext,
            "opening generation exhausted; attempts=$totalAttempts; modelFailures=$modelFailures; qualityRejected=${rejected.size}",
        )
        return null
    }

    private fun personalizationContext(contactName: String, latestMessage: String): String {
        return buildString {
            val db = learningDb
            if (db != null && Prefs.getStyleLearningEnabled(applicationContext)) {
                val profile = db.styleProfile()
                val examples = db.relevantStyleSamples(latestMessage, 5)
                if (examples.isNotEmpty()) {
                    append("\n\n下面是这个用户本人过去的人工回复样本。只模仿长度、措辞和节奏，不要照抄其中的人名、事实或句子。\n")
                    append(profile.promptLine())
                    for (sample in examples) {
                        append("\n对方：").append(sample.theirMessage)
                        append("\n我：").append(sample.myMessage)
                    }
                }
            }
            if (db != null &&
                Prefs.getContactMemoryEnabled(applicationContext) &&
                contactName.isNotBlank()
            ) {
                val memories = db.contactMemories(contactName, 5)
                if (memories.isNotEmpty()) {
                    append("\n\n对方过去提到过这些内容，相关时再自然参考，不相关就忽略，也不要说自己在读取记忆：")
                    memories.forEach { append("\n- ").append(it) }
                }
            }
            if (Prefs.getContactMemoryEnabled(applicationContext) && contactName.isNotBlank()) {
                val olderMessages = runCatching {
                    conversationMemoryDb?.relevantOlderMessages(contactName, latestMessage, 6)
                }.getOrNull().orEmpty()
                if (olderMessages.isNotEmpty()) {
                    append("\n\n下面是与当前话题相关的更早聊天片段。只在确实相关时自然接上，")
                    append("不要复述整段，不要声称自己在读取数据库，也不要把推测当事实：")
                    olderMessages.forEach { message ->
                        append("\n")
                        append(if (message.role == "in") "对方曾说：" else "我曾说：")
                        append(message.content)
                    }
                }
            }
        }
    }

    private fun learnFromManualReplies(contactName: String, thread: List<Pair<String, String>>) {
        runCatching {
            conversationMemoryDb?.syncVisibleThread(contactName, thread)
        }.onFailure { error ->
            RuntimeLog.record(
                applicationContext,
                "conversation memory sync failed; contactId=${DiagnosticIdentity.contactId(applicationContext, contactName)}",
                error,
            )
        }
        val db = learningDb ?: return
        val manualSamples = ManualStyleLearner.extract(thread) { reply ->
            db.isAutomatedReply(contactName, reply)
        }
        if (Prefs.getStyleLearningEnabled(applicationContext)) {
            manualSamples.forEach { db.recordManualSample(it.theirMessage, it.myMessage) }
        }
        if (Prefs.getContactMemoryEnabled(applicationContext)) {
            manualSamples.forEach { db.recordContactMemory(contactName, it.theirMessage) }
        }
    }

    private fun recordAutomatedInteraction(
        contactName: String,
        incomingMessages: List<String>,
        reply: String,
    ) {
        runCatching {
            conversationMemoryDb?.recordMessage(contactName, "out", reply)
        }.onFailure { error ->
            RuntimeLog.record(
                applicationContext,
                "conversation memory write failed; contactId=${DiagnosticIdentity.contactId(applicationContext, contactName)}",
                error,
            )
        }
        val db = learningDb ?: return
        // Always mark automated text, even when learning is disabled, so it can
        // never be mistaken for a human-written sample after learning is enabled.
        db.recordAutomatedReply(contactName, reply)
        if (Prefs.getContactMemoryEnabled(applicationContext)) {
            incomingMessages.takeLast(3).forEach { db.recordContactMemory(contactName, it) }
        }
    }

    private fun readQiyuSignature(): String {
        return SoulMatchContent.gravityTags(visibleTitleTexts())
    }

    private fun handleSoulMatchChat(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ): Boolean {
        if (currentScreen() != Screen.SOUL_MATCH_CHAT) return false

        var titleTexts = soulMatchProfileTexts()
        var gravityTags = SoulMatchContent.gravityTags(titleTexts)
        var zodiac = SoulMatchContent.zodiac(titleTexts)
        var contactName = SoulMatchContent.displayName(titleTexts)
        for (attempt in 0 until 10) {
            if (contactName.isNotBlank() &&
                (gravityTags.isNotBlank() || zodiac.isNotBlank())
            ) break
            setNextStep("读取匹配资料（引力签、昵称或星座）", (10L - attempt) * 500L)
            sleep(500)
            titleTexts = soulMatchProfileTexts()
            gravityTags = SoulMatchContent.gravityTags(titleTexts)
            zodiac = SoulMatchContent.zodiac(titleTexts)
            contactName = SoulMatchContent.displayName(titleTexts)
        }

        setNextStep("检查匹配聊天是否已有新消息", MESSAGE_SETTLE_MS)
        if (!waitUnlessQiyu(MESSAGE_SETTLE_MS)) return false
        if (!convertIncomingVoiceMessages()) {
            setNextStep("匹配聊天语音尚未转写，本轮不回复", 1200)
            sleep(1200)
            return false
        }
        val thread = readThread()
        val openingBasis = SoulMatchContent.openingBasis(contactName, gravityTags, zodiac)
        if (contactName.isBlank()) contactName = "灵魂匹配对象"
        learnFromManualReplies(contactName, thread)
        val incomingTexts = thread.filter { it.first == "in" }.map { it.second }
        val newIncoming = seen.unseen(contactName, incomingTexts)
        val replyingToMessage = newIncoming.isNotEmpty()
        if (openingBasis.isBlank() && !replyingToMessage) {
            RuntimeLog.record(applicationContext, "soul-match profile has no usable opener fields")
            setNextStep("未读取到昵称、星座或引力签，稍后重试", 5000)
            sleep(5000)
            return false
        }

        val matchKey = SoulMatchContent.sessionKey(contactName, openingBasis)
        val persistentName = "灵魂匹配:$contactName"
        if (!replyingToMessage &&
            (matchKey in handledMatches || !greetDb.shouldGreet(persistentName, openingBasis))
        ) {
            RuntimeLog.record(
                applicationContext,
                "soul match skipped; reason=already_handled; contactId=${DiagnosticIdentity.contactId(applicationContext, contactName)}",
            )
            setNextStep("该灵魂匹配已处理，返回星球页", 1200)
            sleep(1200)
            gotoSoulPlanet()
            return false
        }

        val finalReply = if (replyingToMessage) {
            generateValidatedChatReply(contactName, thread, newIncoming)
        } else {
            generateValidatedOpening("根据 $contactName 的匹配资料生成开场白") { rejected ->
                genSoulMatchReply(contactName, gravityTags, zodiac, rejected)
            }
        }
        if (finalReply == null) {
            RuntimeLog.record(applicationContext, "soul-match reply generation exhausted")
            setNextStep("匹配回复生成失败，返回星球避免卡住", 2000)
            sleep(2000)
            gotoSoulPlanet()
            return false
        }

        val delay = thinkDelay()
        setNextStep("准备向 $contactName 发送匹配开场白", delay)
        if (!waitUnlessQiyu(delay)) return false
        if (!running) return false
        if (!isOnChat()) {
            RuntimeLog.record(applicationContext, "soul match send cancelled; reason=chat_screen_changed")
            setNextStep("聊天界面已变化，重新检测", 1000)
            sleep(1000)
            return false
        }
        if (replyingToMessage && incomingSignature(readThread()) != incomingSignature(thread)) {
            setNextStep("收到更新消息，重新按最新内容准备回复", MESSAGE_SETTLE_MS)
            return false
        }
        val sent = send(finalReply)
        if (sent) {
            handledMatches += matchKey
            greetDb.recordGreet(persistentName, openingBasis)
            if (replyingToMessage) seen.markSeen(contactName, incomingTexts)
            val contextMessages = if (replyingToMessage) newIncoming else listOf(openingBasis)
            val historyContext = if (replyingToMessage) {
                newIncoming.last()
            } else {
                "灵魂匹配资料：$openingBasis"
            }
            recordAutomatedInteraction(contactName, contextMessages, finalReply)
            historyDb?.record(contactName, historyContext, finalReply)
            setNextStep("灵魂匹配开场白已发送，返回星球页", 1200)
            sleep(1200)
            gotoSoulPlanet()
        }
        return sent
    }

    private fun handlePlanetChatIdle(
        handledMatches: MutableSet<String>,
        greetDb: GreetDatabase,
        seen: ConversationSeenTracker,
    ) {
        if (!isSoulPlanetSurface() && !gotoSoulPlanet()) return
        if (hasUnreadRedDot()) {
            setNextStep("发现新的聊天消息，优先处理")
            gotoChatList()
            return
        }

        setNextStep("没有新的聊天消息，立即开始下一次灵魂匹配")
        if (gotoSoulPlanet() && startSoulMatch()) {
            handleSoulMatchChat(handledMatches, greetDb, seen)
        } else if (running) {
            if (hasUnreadRedDot()) {
                gotoChatList()
            } else if (isSoulPlanetSurface()) {
                setNextStep("本次匹配未完成，短暂检查后继续匹配", 1500)
                sleep(1500)
            } else {
                gotoSoulPlanet()
            }
        }
    }

    private fun handleQiyu(): Boolean {
        if (currentScreen() != Screen.QIYU_POPUP) return false
        val signature = textOf(findById(ID_QIYU_SIGNATURE))
        val reason = textOf(findById(ID_QIYU_REASON))
        val chatBtn = findById(ID_QIYU_CHAT) ?: return false
        setNextStep("打开奇遇铃私聊")
        tapNode(chatBtn)
        sleep(2500)
        if (!isOnChat()) {
            if (isOnGift()) { skipGift(); return false }
            setNextStep("奇遇铃私聊未打开，准备重试", 3000)
            sleep(3000)
            return false
        }
        val qiyuTag = readQiyuSignature()
        val finalReply = generateValidatedOpening("优先生成奇遇铃私聊回复") { rejected ->
            genQiyuReply(signature, reason, qiyuTag, rejected)
        }
        if (finalReply == null) {
            setNextStep("奇遇铃回复生成失败，本轮结束避免卡住", 2000)
            sleep(2000)
            gotoChatList()
            return false
        }
        setNextStep("立即发送奇遇铃私聊回复")
        val sent = send(finalReply)
        if (sent) {
            recordAutomatedInteraction(signature, listOf(reason, qiyuTag), finalReply)
            historyDb?.record(signature, "理由：$reason\n引力签：$qiyuTag", finalReply)
            setNextStep("奇遇铃回复已发送，返回会话列表", 1200)
            sleep(1200)
            gotoChatList()
        }
        return sent
    }

    // ===== 回复会话（仅会话列表起点） =====

    private fun replyTo(name: String, seen: ConversationSeenTracker): Boolean {
        val contactId = DiagnosticIdentity.contactId(applicationContext, name)
        if (name.isBlank()) {
            RuntimeLog.record(applicationContext, "conversation reply skipped; reason=blank_name")
            return false
        }
        if (!reserveReply(name)) {
            RuntimeLog.record(applicationContext, "conversation reply skipped; reason=reply_busy; contactId=$contactId")
            return false
        }
        RuntimeLog.record(applicationContext, "conversation reply started; contactId=$contactId")
        return try {
            replyToReserved(name, seen).also { sent ->
                RuntimeLog.record(
                    applicationContext,
                    "conversation reply ended; contactId=$contactId; sent=$sent",
                )
            }
        } catch (stopped: StoppedException) {
            throw stopped
        } catch (error: Throwable) {
            RuntimeLog.record(
                applicationContext,
                "conversation reply crashed; contactId=${DiagnosticIdentity.contactId(applicationContext, name)}",
                error,
            )
            setNextStep("$name 的会话发生变化，返回列表重试", 1200)
            if (running && currentScreen() != Screen.QIYU_POPUP) {
                runCatching { gotoChatList() }
            }
            false
        } finally {
            releaseReply(name)
        }
    }

    @Synchronized
    private fun reserveReply(name: String): Boolean {
        if (activeReplyName != null) return false
        activeReplyName = name
        return true
    }

    @Synchronized
    private fun releaseReply(name: String) {
        if (activeReplyName == name) activeReplyName = null
    }

    private fun displayedConversationName(): String =
        textOf(findVisibleById(ID_PROFILE_NAME))

    private fun isExpectedConversation(name: String): Boolean {
        val displayed = displayedConversationName()
        if (displayed.isBlank()) return true
        return comparableText(displayed) == comparableText(name)
    }

    private fun openConversationForReply(name: String): Boolean {
        val contactId = DiagnosticIdentity.contactId(applicationContext, name)
        repeat(2) { attempt ->
            if (!running || !isOnList()) return false
            val item = findConversation(name) ?: return false
            setNextStep(if (attempt == 0) "打开 $name 的会话" else "重新定位 $name 的会话")
            if (!tapNode(item)) {
                sleep(500)
                return@repeat
            }
            sleep(1500)
            if (currentScreen() == Screen.QIYU_POPUP) return false
            if (isOnChat() && isExpectedConversation(name)) {
                RuntimeLog.record(
                    applicationContext,
                    "conversation opened; contactId=$contactId; attempt=${attempt + 1}",
                )
                return true
            }

            setNextStep("会话列表刚刚更新，重新定位 $name", 800)
            if (isOnChat()) gotoChatList()
            sleep(800)
        }
        RuntimeLog.record(applicationContext, "conversation open failed; contactId=$contactId; attempts=2")
        return false
    }

    private fun replyToReserved(name: String, seen: ConversationSeenTracker): Boolean {
        if (!isOnList() || !openConversationForReply(name)) return false

        replyCycle@ while (running && isOnChat()) {
            // 等对方连续一段时间不再发新消息，并先完成所有可见语音的转写。
            val thread = waitForStableThread(name)
            learnFromManualReplies(name, thread)
            val incoming = thread.filter { it.first == "in" }
            RuntimeLog.record(
                applicationContext,
                "conversation loaded; contactId=${DiagnosticIdentity.contactId(applicationContext, name)}; messages=${thread.size}; incoming=${incoming.size}; pendingVoice=${pendingIncomingVoiceBubbles().size}",
            )
            if (incoming.isEmpty()) {
                RuntimeLog.record(applicationContext, "conversation reply aborted; reason=no_readable_incoming")
                setNextStep("未读内容不可读取，返回会话列表", 1200)
                sleep(1200)
                gotoChatList()
                return false
            }
            val incomingTexts = incoming.map { it.second }
            val new = seen.unseen(name, incomingTexts)
            if (new.isEmpty()) {
                RuntimeLog.record(applicationContext, "conversation reply skipped; reason=no_unseen_message")
                gotoChatList()
                return false
            }

            val reply = generateValidatedChatReply(name, thread, new)
            if (reply.isNullOrBlank()) {
                RuntimeLog.record(applicationContext, "conversation reply aborted; reason=generation_failed")
                val reason = ModelRouter.lastError.ifBlank { "连续生成内容不合格" }.take(36)
                setNextStep("本轮回复失败：$reason，返回列表避免卡住", 2000)
                sleep(2000)
                gotoChatList()
                return false
            }
            run {
                val normalized = normalizeReply(reply)
                val repeated = thread.filter { it.first == "out" }
                    .map { normalizeReply(it.second) }
                    .any { it.isNotEmpty() && it == normalized }
                if (repeated) {
                    RuntimeLog.record(applicationContext, "conversation reply aborted; reason=duplicate_output")
                    setNextStep("回复内容与历史重复，本轮不发送并返回列表", 1500)
                    sleep(1500)
                    gotoChatList()
                    return false
                }
            }

            // 模型生成期间对方可能又发了文字或语音。此时废弃旧回复，重新等待并生成。
            val newestThread = readThread()
            if (pendingIncomingVoiceBubbles().isNotEmpty() ||
                incomingSignature(newestThread) != incomingSignature(thread)
            ) {
                RuntimeLog.record(applicationContext, "conversation reply regenerated; reason=incoming_changed")
                setNextStep("收到更新消息，重新准备回复", MESSAGE_SETTLE_MS)
                sleep(500)
                continue@replyCycle
            }
            if (!isExpectedConversation(name)) {
                RuntimeLog.record(applicationContext, "conversation reply cancelled; reason=conversation_changed")
                setNextStep("会话已切换，取消本次发送并重新检查列表", 800)
                gotoChatList()
                return false
            }

            val sent = send(reply)
            if (sent) {
                RuntimeLog.record(applicationContext, "conversation reply confirmed; unseenMessages=${new.size}")
                seen.markSeen(name, incomingTexts)
                recordAutomatedInteraction(name, new, reply)
                historyDb?.record(name, incoming.last().second, reply)
                gotoChatList()
                return true
            } else if (running && isOnChat()) {
                RuntimeLog.record(applicationContext, "conversation reply send failed; chatStillVisible=true")
                setNextStep("回复未完成，保留当前会话")
            }
            return false
        }
        return false
    }

    // ===== 收到的新招呼 =====

    private fun hasGreeting(): Boolean = findVisibleById(ID_GREETING_TITLE) != null

    /** Hidden tab-bar nodes remain in Soul's accessibility tree; only a visible dot is unread. */
    private fun hasUnreadRedDot(): Boolean = findVisibleById(ID_MSG_RED_DOT) != null

    private fun openGreetingList(): Boolean {
        setNextStep("打开新招呼列表")
        for (i in 0 until 2) {
            if (currentScreen() == Screen.GREETING_LIST) return true
            findById(ID_GREETING_TITLE)?.let { tapNode(it) }
            sleep(1500)
        }
        return currentScreen() == Screen.GREETING_LIST
    }

    private fun scanGreetingUsers(
        handledKeys: Set<String>,
        greetDb: GreetDatabase,
    ): List<String> {
        if (currentScreen() != Screen.GREETING_LIST) return emptyList()
        val visibleNames = findByIds(ID_CONV_NAME)
            .filter(::isVisibleOnScreen)
            .map(::textOf)
        return GreetingSessionPolicy.pendingUsers(visibleNames, handledKeys)
            .filter { name ->
                greetDb.shouldGreet(
                    "新招呼:$name",
                    GreetingSessionPolicy.SYSTEM_GREETING_CONTEXT,
                )
            }
    }

    private fun replyToGreeting(
        name: String,
        seen: ConversationSeenTracker,
        handledKeys: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean {
        if (currentScreen() != Screen.GREETING_LIST) return false
        val greetingKey = GreetingSessionPolicy.userKey(name)
        if (greetingKey.isBlank() || !handledKeys.add(greetingKey)) return false
        val nameNode = findByIds(ID_CONV_NAME)
            .filter(::isVisibleOnScreen)
            .firstOrNull { textOf(it) == name } ?: return false
        setNextStep("打开 $name 的招呼")
        tapNode(nameNode)
        sleep(1500)
        if (!isOnChat()) {
            if (currentScreen() == Screen.QIYU_POPUP) handleQiyu()
            else { pressBack(); sleep(1200) }
            return false
        }
        if (!isExpectedConversation(name)) {
            setNextStep("招呼列表发生变化，取消错误会话", 800)
            pressBack()
            sleep(800)
            return false
        }
        val thread = waitForStableThread(name)
        if (!running || !isOnChat() || !isExpectedConversation(name)) return false
        learnFromManualReplies(name, thread)
        val incoming = thread.filter { it.first == "in" }
        val systemGreetingName = "新招呼:$name"
        if (incoming.isEmpty()) {
            if (pendingIncomingVoiceBubbles().isNotEmpty()) {
                setNextStep("$name 的语音尚未转写，本轮暂不处理", 1200)
                pressBack()
                sleep(1200)
                return false
            }
            // Soul can show an app-generated preview under "new greetings" even
            // though the person never sent a chat message. Persist the decision so
            // an accessibility-service/worker restart cannot reopen the same row.
            greetDb.recordGreet(
                systemGreetingName,
                GreetingSessionPolicy.SYSTEM_GREETING_CONTEXT,
            )
            RuntimeLog.record(
                applicationContext,
                "greeting ignored; reason=system_only; contactId=${DiagnosticIdentity.contactId(applicationContext, name)}",
            )
            setNextStep("$name 只有系统招呼，没有真实消息，已忽略", 1200)
            if (currentScreen() == Screen.QIYU_POPUP) handleQiyu()
            else { pressBack(); sleep(1200) }
            return false
        }
        val incomingTexts = incoming.map { it.second }
        val new = seen.unseen("招呼:$name", incomingTexts)
        if (incoming.isNotEmpty() && new.isEmpty()) {
            if (currentScreen() == Screen.QIYU_POPUP) handleQiyu()
            else { pressBack(); sleep(1200) }
            return false
        }
        val replyContext = new.last()
        if (!greetDb.shouldGreet(systemGreetingName, replyContext)) {
            setNextStep("$name 的这条招呼已处理，返回列表", 800)
            pressBack()
            sleep(800)
            return false
        }
        val reply = generateValidatedChatReply(name, thread, new) ?: run {
            setNextStep("招呼回复生成失败，返回列表避免卡住", 1200)
            if (currentScreen() == Screen.QIYU_POPUP) handleQiyu()
            else { pressBack(); sleep(1200) }
            return false
        }
        if (currentScreen() == Screen.QIYU_POPUP) {
            handleQiyu()
            return false
        }
        val sent = send(reply)
        if (sent) {
            if (incomingTexts.isNotEmpty()) seen.markSeen("招呼:$name", incomingTexts)
            greetDb.recordGreet(systemGreetingName, replyContext)
            recordAutomatedInteraction(name, new, reply)
            historyDb?.record(name, replyContext, reply)
            setNextStep("已确认给 $name 发出消息，返回招呼列表", 800)
        } else {
            setNextStep("给 $name 的消息未发出，本轮不再重复进入", 1500)
            sleep(1500)
        }
        if (currentScreen() == Screen.QIYU_POPUP) handleQiyu()
        else { pressBack(); sleep(1200) }
        return sent
    }

    // ===== 广场 =====

    private fun readPosts(expectedChannel: String): List<SquarePostCandidate> {
        if (!isOnSquare()) return emptyList()
        if (selectedSquareChannel() != expectedChannel) return emptyList()
        val cards = findByIds(ID_SQUARE_CARD)
            .filter(::isVisibleOnScreen)
            .sortedBy { rect(it).top }
        val result = mutableListOf<SquarePostCandidate>()
        for (card in cards) {
            val cb = rect(card)
            // ViewPager keeps neighbouring channels in the accessibility tree and
            // their cards can share screen coordinates. Bind the text and avatar
            // through this card's own subtree, never through global geometry.
            val text = descendantsById(card, ID_SQUARE_TEXT)
                .asSequence()
                .filter(::isVisibleOnScreen)
                .map(::textOf)
                .firstOrNull(String::isNotBlank)
                .orEmpty()
            val avatar = descendantsById(card, ID_SQUARE_AVATAR)
                .firstOrNull(::isVisibleOnScreen)
            if (text.isNotBlank() && avatar != null) {
                result += SquarePostCandidate(
                    text = text,
                    avatar = avatar,
                    viewportKey = SquareBrowsePolicy.viewportKey(text, cb.top, dp(48)),
                )
            }
        }
        return result.distinctBy { it.viewportKey }
    }

    private fun squarePostSignature(posts: List<SquarePostCandidate>): String =
        posts.joinToString("|") { it.viewportKey }

    private fun waitForStableSquarePosts(expectedChannel: String): List<SquarePostCandidate> {
        val deadline = System.currentTimeMillis() + SQUARE_POST_LOAD_TIMEOUT_MS
        var latest = emptyList<SquarePostCandidate>()
        var lastSignature = ""
        var stableSince = System.currentTimeMillis()
        while (running && isOnSquare() && selectedSquareChannel() == expectedChannel) {
            val current = readPosts(expectedChannel)
            val signature = squarePostSignature(current)
            if (signature != lastSignature) {
                lastSignature = signature
                stableSince = System.currentTimeMillis()
            }
            latest = current
            val stableFor = System.currentTimeMillis() - stableSince
            if (latest.isNotEmpty() && stableFor >= SQUARE_POST_STABLE_MS) return latest

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return latest
            val action = if (latest.isEmpty()) {
                "等待同城动态加载"
            } else {
                "确认本屏 ${latest.size} 条动态已加载"
            }
            setNextStep(action, minOf(remaining, SQUARE_POST_STABLE_MS))
            sleep(minOf(300L, remaining))
        }
        return latest
    }

    private fun getProfileName(): String =
        if (isOnProfile()) textOf(findById(ID_PROFILE_NAME)) else ""

    private fun skipGift() {
        if (isOnGift()) {
            setNextStep("关闭礼物弹窗")
            findById(ID_GIFT_CLOSE)?.let { tapNode(it) }
            sleep(1500)
        }
    }

    private fun skipNoSend() {
        if (isOnNoSend()) {
            setNextStep("点击“先不聊了”")
            findById(ID_NO_SEND_CLOSE)?.let { tapNode(it) }
            sleep(1200)
        }
    }

    private fun openProfile(avatar: AccessibilityNodeInfo): Boolean {
        val bounds = rect(avatar)
        setNextStep("打开同城用户主页")
        for (attempt in 0 until 2) {
            if (attempt == 0) {
                tapNode(avatar)
            } else {
                RuntimeLog.record(applicationContext, "square profile open retry; attempt=2")
                tap(bounds.exactCenterX().toFloat(), bounds.exactCenterY().toFloat())
            }
            for (wait in 0 until 8) {
                sleep(250)
                if (isOnProfile()) return true
                if (!isSoulForeground()) return false
            }
            if (!isOnSquare()) return false
        }
        RuntimeLog.record(applicationContext, "square profile open failed; attempts=2")
        return false
    }

    private fun browseSquareOnce(
        seenDm: MutableSet<String>,
        attemptedDmProfiles: MutableSet<String>,
        greetDb: GreetDatabase,
    ): Boolean {
        if (!isOnSquare()) return false
        val channel = ensureLocalSquareChannel() ?: run {
            statusText = "未找到同城频道"
            RuntimeLog.record(applicationContext, "square browse aborted; reason=local_channel_missing")
            return false
        }
        RuntimeLog.record(applicationContext, "square browse started; maxViewports=$SQUARE_MAX_VIEWPORTS")
        setNextStep("浏览同城·$channel")
        val handledInViewport = mutableSetOf<String>()
        val transientFailures = mutableMapOf<String, Int>()
        var viewportCount = 0
        var lastScrolledFrom = ""
        while (viewportCount < SQUARE_MAX_VIEWPORTS) {
            if (currentScreen() == Screen.QIYU_POPUP || hasUnreadRedDot()) return false
            if (selectedSquareChannel() != channel) {
                RuntimeLog.record(applicationContext, "square browse stopped; reason=channel_changed")
                return false
            }
            val posts = waitForStableSquarePosts(channel)
            RuntimeLog.record(
                applicationContext,
                "square viewport scanned; viewport=${viewportCount + 1}; posts=${posts.size}; handled=${handledInViewport.size}",
            )
            val candidate = posts.firstOrNull { post ->
                post.viewportKey !in handledInViewport &&
                    SquareBrowsePolicy.shouldRetryTransientFailure(
                        transientFailures[post.viewportKey] ?: 0,
                    )
            }

            if (candidate == null) {
                // Re-read once after the list has settled. Only advance when every
                // currently visible candidate is handled or has exhausted retries.
                val confirmedPosts = waitForStableSquarePosts(channel)
                val remaining = confirmedPosts.firstOrNull { post ->
                    post.viewportKey !in handledInViewport &&
                        SquareBrowsePolicy.shouldRetryTransientFailure(
                            transientFailures[post.viewportKey] ?: 0,
                        )
                }
                if (remaining != null) continue

                val signature = squarePostSignature(confirmedPosts)
                val prepareDelay = if (signature == lastScrolledFrom) 3000L else SQUARE_SCROLL_PREPARE_MS
                setNextStep("本屏动态已检查，${prepareDelay / 1000} 秒后下滑", prepareDelay)
                sleep(prepareDelay)
                if (currentScreen() == Screen.QIYU_POPUP || hasUnreadRedDot()) return false
                if (!isOnSquare() || selectedSquareChannel() != channel) return false

                lastScrolledFrom = signature
                // Shorter and slower than the old half-screen fling. The overlap
                // keeps cards near both edges visible on the next pass.
                swipeVertically(ID_SQUARE_PAGER, 0.76f, 0.50f, 650)
                setNextStep("等待下方动态加载", SQUARE_SCROLL_SETTLE_MS)
                sleep(SQUARE_SCROLL_SETTLE_MS)
                viewportCount++
                handledInViewport.clear()
                transientFailures.clear()
                continue
            }

            val text = candidate.text
            val avatar = candidate.avatar
            val viewportKey = candidate.viewportKey
            if (!isVisibleOnScreen(avatar)) {
                transientFailures[viewportKey] = (transientFailures[viewportKey] ?: 0) + 1
                continue
            }

            setNextStep("检查当前同城动态")
            if (!openProfile(avatar)) {
                val attempts = (transientFailures[viewportKey] ?: 0) + 1
                transientFailures[viewportKey] = attempts
                if (!backToSquare()) return false
                if (SquareBrowsePolicy.shouldRetryTransientFailure(attempts)) {
                    setNextStep("头像打开失败，重新扫描本屏", 1200)
                    sleep(1200)
                } else {
                    handledInViewport += viewportKey
                }
                continue
            }

            val name = getProfileName()
            if (name.isBlank()) {
                val attempts = (transientFailures[viewportKey] ?: 0) + 1
                transientFailures[viewportKey] = attempts
                if (!backToSquare()) return false
                if (!SquareBrowsePolicy.shouldRetryTransientFailure(attempts)) {
                    handledInViewport += viewportKey
                }
                continue
            }

            val profileKey = SquareBrowsePolicy.profileKey(name)
            if (profileKey.isBlank() || profileKey in attemptedDmProfiles) {
                handledInViewport += viewportKey
                if (!backToSquare()) return false
                continue
            }
            // Reserve before opening private chat. The reservation survives the
            // overlapping area of the next short swipe, even after a modal detour.
            attemptedDmProfiles += profileKey

            val postKey = "$name\u0000$text"
            if (postKey in seenDm ||
                !greetDb.shouldGreet(
                    name,
                    text,
                    Prefs.getGreetIntervalHours(applicationContext),
                )
            ) {
                seenDm += postKey
                handledInViewport += viewportKey
                if (!backToSquare()) return false
                continue
            }

            val secret = findById(ID_CHAT_SECRET)
            if (secret == null) {
                handledInViewport += viewportKey
                if (!backToSquare()) return false
                continue
            }
            setNextStep("打开 $name 的私聊")
            tapNode(secret)
            sleep(2500)
            when {
                isOnNoSend() -> {
                    seenDm += postKey
                    handledInViewport += viewportKey
                    skipNoSend()
                    if (!backToSquare()) return false
                }
                isOnGift() -> {
                    seenDm += postKey
                    handledInViewport += viewportKey
                    skipGift()
                    if (!backToSquare()) return false
                }
                isOnChat() -> {
                    val contactId = DiagnosticIdentity.contactId(applicationContext, name)
                    RuntimeLog.record(applicationContext, "square private chat opened; contactId=$contactId")
                    val reply = generateValidatedOpening("生成给 $name 的广场开场白") { rejected ->
                        genDmReply(name, text, rejected)
                    }
                    val sent = reply != null && send(reply)
                    if (sent) {
                        seenDm += postKey
                        greetDb.recordGreet(name, text)
                        recordAutomatedInteraction(name, listOf(text), reply)
                        historyDb?.record(name, text, reply)
                    }
                    RuntimeLog.record(
                        applicationContext,
                        "square private chat ended; contactId=$contactId; sent=$sent",
                    )
                    backToSquare()
                    return sent
                }
                else -> {
                    val attempts = (transientFailures[viewportKey] ?: 0) + 1
                    transientFailures[viewportKey] = attempts
                    if (!backToSquare()) return false
                    if (!SquareBrowsePolicy.shouldRetryTransientFailure(attempts)) {
                        handledInViewport += viewportKey
                    }
                }
            }
        }
        RuntimeLog.record(applicationContext, "square browse ended; sent=false; scannedViewports=$viewportCount")
        return false
    }

    // ===== 主循环 =====

    private fun runLoop() {
        var failure: Throwable? = null
        try {
            runLoopInternal()
        } catch (_: StoppedException) {
            RuntimeLog.record(applicationContext, "worker interrupted for stop")
        } catch (t: Throwable) {
            failure = t
            RuntimeLog.record(applicationContext, "worker crashed", t)
        } finally {
            runCatching { conversationMemoryDb?.close() }
            conversationMemoryDb = null
            runCatching { learningDb?.close() }
            learningDb = null
            runCatching { historyDb?.close() }
            historyDb = null
            // A destroyed service can overlap briefly with a newly connected one.
            // The old worker must never overwrite the new instance's global state.
            val ownsGlobalState = instance === this
            if (ownsGlobalState) {
                running = false
                statusDeadline = 0
            }
            if (worker === Thread.currentThread()) worker = null

            val shouldRestart = Prefs.getTaskShouldRun(applicationContext) && ownsGlobalState
            RuntimeLog.record(
                applicationContext,
                "worker finished; failure=${failure != null}; ownsState=$ownsGlobalState; restart=$shouldRestart",
            )
            if (shouldRestart) {
                val reason = failure?.message?.takeIf(String::isNotBlank)
                    ?.let { "任务异常：${it.take(36)}" }
                    ?: "任务线程意外退出"
                Prefs.setLastStopReason(applicationContext, reason)
                RuntimeLog.record(
                    applicationContext,
                    "worker ended unexpectedly; scheduling restart; hadFailure=${failure != null}",
                )
                setNextStep("$reason，自动恢复", 3000)
                restartHandler.postDelayed({
                    if (instance === this &&
                        Prefs.getTaskShouldRun(applicationContext) &&
                        !running
                    ) {
                        RuntimeLog.record(applicationContext, "worker automatic restart executing")
                        start()
                    } else {
                        RuntimeLog.record(applicationContext, "worker automatic restart cancelled")
                    }
                }, 3000)
            } else if (ownsGlobalState &&
                statusText != "未配置 API Key" &&
                statusText != "模型配置不完整" &&
                statusText != "用户手动停止"
            ) {
                statusText = Prefs.getLastStopReason(applicationContext)
            }
        }
    }

    private fun runLoopInternal() {
        val ctx = applicationContext
        val automationMode = Prefs.getAutomationMode(ctx)
        val configuredModels = ModelEndpointPrefs.getEndpoints(ctx)
        val enabledModels = configuredModels.filter(ModelEndpointRules::isRunnable)
        if (configuredModels.none { it.enabled && it.apiKey.isNotBlank() }) {
            statusText = "未配置 API Key"
            statusDeadline = 0
            Prefs.setTaskShouldRun(ctx, false)
            Prefs.setLastStopReason(ctx, statusText)
            RuntimeLog.record(applicationContext, "worker preflight failed; reason=no_api_key")
            return
        }
        if (enabledModels.isEmpty()) {
            statusText = "模型配置不完整"
            statusDeadline = 0
            Prefs.setTaskShouldRun(ctx, false)
            Prefs.setLastStopReason(ctx, statusText)
            RuntimeLog.record(applicationContext, "worker preflight failed; reason=no_runnable_model")
            return
        }
        setNextStep("${automationMode.displayName}：检查当前页面")
        RuntimeLog.record(
            applicationContext,
            "worker initialized; mode=${automationMode.name}; enabledModels=${enabledModels.size}; primary=${enabledModels.first().displayName}",
        )

        val seen = ConversationSeenTracker()
        val seenDm = mutableSetOf<String>()
        val attemptedDmProfiles = mutableSetOf<String>()
        val greetDb = GreetDatabase(applicationContext)
        historyDb = HistoryDatabase(applicationContext)
        conversationMemoryDb = ConversationMemoryDatabase(applicationContext).also { memory ->
            memory.seedLegacyHistory(historyDb?.getAll().orEmpty())
        }
        learningDb = ChatLearningDatabase(applicationContext).also { db ->
            // Existing interaction history contains only replies sent by the bot.
            // Seed it once so older bot messages are not learned as human style.
            db.seedAutomatedReplies(historyDb?.getAll().orEmpty())
        }
        val squareInterval = Prefs.getSquareInterval(applicationContext).toLong()
        var lastDm = 0L
        var lastScreenName = ""
        var stuckCount = 0
        var unreadTopSearchComplete = false
        val handledSoulMatches = mutableSetOf<String>()
        val handledGreetingUsers = mutableSetOf<String>()
        var greetingRecheckAt = 0L

        fun handleIdleWork() {
            if (automationMode.handlesSoulMatch) {
                handlePlanetChatIdle(
                    handledSoulMatches,
                    greetDb,
                    seen,
                )
                return
            }

            val remainMs = squareInterval * 1000L - (System.currentTimeMillis() - lastDm)
            if (remainMs > 0L) {
                if (!isOnSquare() && !gotoSquare()) return
                setNextStep("停留同城广场，等待下一次私聊", remainMs)
                sleep(minOf(3000L, remainMs))
                return
            }

            setNextStep("进入同城广场并私聊")
            var sent = false
            if (gotoSquare()) {
                sent = browseSquareOnce(seenDm, attemptedDmProfiles, greetDb)
                if (currentScreen() == Screen.QIYU_POPUP) {
                    handleQiyu()
                    return
                }
                if (sent) lastDm = System.currentTimeMillis()
            }
            if (!sent && running) {
                if (hasUnreadRedDot()) {
                    gotoChatList()
                    return
                }
                if (!isOnSquare()) gotoSquare()
                RuntimeLog.record(applicationContext, "square round ended; sent=false; cooldown=false")
                setNextStep("继续寻找可私聊用户", SQUARE_RETRY_DELAY_MS)
                sleep(SQUARE_RETRY_DELAY_MS)
            }
        }

        mainLoop@ while (running) {
            try {
                val screen = currentScreen()
                if (!hasUnreadRedDot()) unreadTopSearchComplete = false
                if (screen.name == lastScreenName) stuckCount++ else {
                    lastScreenName = screen.name
                    stuckCount = 0
                    RuntimeLog.record(
                        applicationContext,
                        "screen=${screen.name} pager=${findVisibleById(ID_SQUARE_PAGER) != null} avatar=${findVisibleById(ID_SQUARE_AVATAR) != null} name=${findVisibleById(ID_CONV_NAME) != null} input=${findVisibleById(ID_INPUT_BOX) != null}"
                    )
                }
                if (stuckCount > 8) {
                    RuntimeLog.record(
                        applicationContext,
                        "screen state stalled; screen=${screen.name}; consecutiveChecks=$stuckCount",
                    )
                    setNextStep("重新检测当前页面", 5000)
                    sleep(5000)
                    stuckCount = 0
                    continue
                }

                // 奇遇铃高于匹配、广场和普通聊天，在两个工作模式中都立即处理。
                if (screen == Screen.QIYU_POPUP) {
                    handleQiyu()
                    continue
                }

                if (screen != Screen.LIST &&
                    screen != Screen.GREETING_LIST &&
                    hasUnreadRedDot() &&
                    !unreadTopSearchComplete
                ) {
                    setNextStep("发现聊天消息，优先返回会话列表")
                    gotoChatList()
                    continue
                }

                // 两个模式都先处理招呼和未读聊天；空闲时才执行各自的主动找人方式。
                when (screen) {
                    Screen.NO_SEND -> {
                        skipNoSend()
                        gotoModeIdleSurface(automationMode)
                    }
                    Screen.GIFT -> {
                        skipGift()
                        gotoModeIdleSurface(automationMode)
                    }
                    Screen.QIYU_POPUP -> handleQiyu()
                    Screen.SOUL_MATCH_CHAT -> {
                        if (automationMode.handlesSoulMatch) {
                            handleSoulMatchChat(handledSoulMatches, greetDb, seen)
                        } else {
                            gotoSquare()
                        }
                    }
                    Screen.SOUL_PLANET -> {
                        if (automationMode.handlesSoulMatch) {
                            handlePlanetChatIdle(
                                handledSoulMatches,
                                greetDb,
                                seen,
                            )
                        } else {
                            gotoSquare()
                        }
                    }
                    Screen.LIST -> {
                        if (hasGreeting() && System.currentTimeMillis() >= greetingRecheckAt) {
                            setNextStep("处理新招呼")
                            openGreetingList()
                        } else if (hasUnreadRedDot()) {
                            val unread = scanUnread()
                            if (unread.isNotEmpty()) {
                                unreadTopSearchComplete = false
                                val oldestUnread = unread.first()
                                val name = oldestUnread.name
                                val timeStr = oldestUnread.timeText
                                val delay = replyDelay(timeStr)
                                if (delay > 0) {
                                    setNextStep("回复 $name", delay)
                                    if (!waitUnlessQiyu(delay)) continue@mainLoop
                                }
                                setNextStep(
                                    if (unread.size > 1) {
                                        "共 ${unread.size} 条未读，先回复最早的 $name"
                                    } else {
                                        "回复 $name"
                                    },
                                )
                                replyTo(name, seen)
                            } else if (!unreadTopSearchComplete) {
                                setNextStep("回到会话顶部")
                                scrollConversationToTop()
                                unreadTopSearchComplete = true
                                setNextStep("重新检查未读消息", 800)
                                sleep(800)
                            } else {
                                // The tab red dot can remain stale after all rows are read.
                                // Keep the acknowledgement while the same dot remains visible,
                                // otherwise an empty list would pull us back from the work page.
                                unreadTopSearchComplete = true
                                handleIdleWork()
                            }
                        } else {
                            unreadTopSearchComplete = false
                            handleIdleWork()
                        }
                    }
                    Screen.GREETING_LIST -> {
                        val users = scanGreetingUsers(handledGreetingUsers, greetDb)
                        if (users.isNotEmpty()) {
                            setNextStep("回复招呼 " + users[0])
                            replyToGreeting(users[0], seen, handledGreetingUsers, greetDb)
                        } else {
                            greetingRecheckAt = System.currentTimeMillis() +
                                GreetingSessionPolicy.EMPTY_LIST_RECHECK_MS
                            setNextStep(
                                "本轮新招呼已检查，稍后再检查",
                                GreetingSessionPolicy.EMPTY_LIST_RECHECK_MS,
                            )
                            pressBack()
                            sleep(1200)
                        }
                    }
                    Screen.OUTSIDE -> {
                        setNextStep("检查 Soul 是否已打开", 1500)
                        sleep(1500)
                    }
                    Screen.SQUARE -> {
                        if (automationMode.handlesSquareDm) handleIdleWork() else gotoSoulPlanet()
                    }
                    Screen.PROFILE -> {
                        gotoModeIdleSurface(automationMode)
                    }
                    Screen.CHAT -> gotoModeIdleSurface(automationMode)
                    else -> gotoModeIdleSurface(automationMode)
                }
            } catch (e: StoppedException) {
                break
            } catch (e: Exception) {
                val detail = "${e.javaClass.simpleName}：${e.message.orEmpty()}".take(48)
                RuntimeLog.record(applicationContext, "page flow error: $detail", e)
                setNextStep("页面读取异常：$detail，重新检测", 2000)
                sleep(2000)
            }
        }
    }
}
