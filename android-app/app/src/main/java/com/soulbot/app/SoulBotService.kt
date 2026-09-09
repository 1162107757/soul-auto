package com.soulbot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

class SoulBotService : AccessibilityService() {

    companion object {
        @Volatile var running = false
        @Volatile var instance: SoulBotService? = null
        @Volatile var statusText: String = "已停止"
        @Volatile var statusDeadline: Long = 0  // 下次动作的绝对时间戳（毫秒），0=无倒计时

        const val SOUL_PACKAGE = "cn.soulapp.android"
        const val REPLY_CHAT = true
        const val DM_INTERVAL = 240L  // 广场私信间隔（秒）
        const val CONTEXT_LEN = 12
        const val MAX_SCROLLS_TO_TOP = 20
        const val SQUARE_RETRY_DELAY_MS = 3000L

        // Soul 控件 id
        const val ID_INPUT_BOX = "cn.soulapp.android:id/et_sendmessage"
        const val ID_CONTENT_TEXT = "cn.soulapp.android:id/content_text"
        const val ID_SEND_BUTTON = "cn.soulapp.android:id/btn_send"
        const val ID_CHAT_ITEM = "cn.soulapp.android:id/item_root"
        const val ID_CHAT_AVATAR = "cn.soulapp.android:id/chat_avatar"
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
        const val ID_CHAT_SECRET = "cn.soulapp.android:id/tv_chat_secret"
        const val ID_PROFILE_NAME = "cn.soulapp.android:id/titlebar_text_tv"
        const val ID_QIYU_CHAT = "cn.soulapp.android:id/tv_love_chat"
        const val ID_QIYU_SIGNATURE = "cn.soulapp.android:id/tv_love_signature"
        const val ID_QIYU_REASON = "cn.soulapp.android:id/tv_recall_reason"
        const val ID_QIYU_CLOSE = "cn.soulapp.android:id/iv_love_close"
        const val ID_GREETING_TITLE = "cn.soulapp.android:id/tv_greeting_title"
        const val ID_GREETING_TIPS = "cn.soulapp.android:id/tv_tips"
    }

    private enum class Screen { CHAT, LIST, SQUARE, PROFILE, GIFT, GREETING_LIST, QIYU_POPUP, OUTSIDE, OTHER }

    private class StoppedException : RuntimeException()

    private val random = Random.Default
    private var worker: Thread? = null
    private var currentModel: ModelConfig = ModelList.MODELS[0]
    private var currentApiKey: String = ""
    private var historyDb: HistoryDatabase? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        stop()
        instance = null
        super.onDestroy()
    }

    fun start() {
        if (running || worker?.isAlive == true) return
        running = true
        android.util.Log.d("SoulBot", "start() called, launching runLoop thread")
        worker = Thread { runLoop() }.apply { start() }
    }

    fun stop() {
        running = false
        statusText = "已停止"
        statusDeadline = 0
        worker?.interrupt()
        // runLoop() clears the reference in finally. Keeping it until then prevents
        // a second worker from starting while the interrupted one is still unwinding.
    }

    // ===== 基础辅助 =====

    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    private fun isSoulForeground(): Boolean =
        root()?.packageName?.toString() == SOUL_PACKAGE

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun swipeUp(startRatio: Float, endRatio: Float, duration: Long = 300) {
        val x = screenWidth() / 2f
        val height = screenHeight().toFloat()
        swipe(x, height * startRatio, x, height * endRatio, duration)
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
            list.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                args,
            )
            sleep(500)
        }

        // Verify that the list is really at the beginning. Keep scrolling backward
        // until the visible conversations stop changing, rather than assuming a
        // fixed number of gestures is enough.
        repeat(MAX_SCROLLS_TO_TOP) {
            if (!isOnList()) return
            val before = conversationListSnapshot()
            val list = findById(ID_CONV_LIST)
            val accepted = list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            if (!accepted) {
                // Fallback for Soul versions whose list does not publish scroll actions.
                val x = screenWidth() / 2f
                val height = screenHeight().toFloat()
                swipe(x, height * 0.30f, x, height * 0.86f, 450)
            }
            sleep(500)
            val after = conversationListSnapshot()
            if (before.isNotEmpty() && after == before) return
        }
    }

    private fun findById(id: String): AccessibilityNodeInfo? =
        root()?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()

    private fun findByIds(id: String): List<AccessibilityNodeInfo> =
        root()?.findAccessibilityNodeInfosByViewId(id) ?: emptyList()

    private fun findVisibleById(id: String): AccessibilityNodeInfo? {
        val r = root() ?: return null
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return r.findAccessibilityNodeInfosByViewId(id).firstOrNull { node ->
            if (!node.isVisibleToUser) return@firstOrNull false
            val b = Rect()
            node.getBoundsInScreen(b)
            b.left >= 0 && b.top >= 0 && b.right <= w && b.bottom <= h
        }
    }

    private fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val bounds = rect(node)
        return bounds.width() > 0 && bounds.height() > 0 &&
            bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < screenWidth() && bounds.top < screenHeight()
    }

    private fun rect(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r
    }

    private fun textOf(node: AccessibilityNodeInfo?): String =
        node?.text?.toString()?.trim() ?: ""

    private fun tap(x: Float, y: Float): Boolean {
        val cx = maxOf(0f, x)
        val cy = maxOf(0f, y)
        val path = Path().apply { moveTo(cx, cy) }
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
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            clickable = candidate.parent
        }
        val r = rect(node)
        return tap(r.exactCenterX().toFloat(), r.exactCenterY().toFloat())
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun setNextStep(action: String, delayMs: Long = 0L) {
        statusText = action
        statusDeadline = if (delayMs > 0L) System.currentTimeMillis() + delayMs else 0L
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
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (waitForInputText(text)) {
            return true
        }

        // ACTION_SET_TEXT is unreliable in some Soul releases. Use the public
        // accessibility paste action as a fallback and restore the user's clipboard.
        val input = findById(ID_INPUT_BOX) ?: return false
        tapNode(input)
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val currentText = textOf(input)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = runCatching { clipboard.primaryClip }.getOrNull()
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("SoulBot reply", text))
            sleep(100)
            input.performAction(AccessibilityNodeInfo.ACTION_PASTE) && waitForInputText(text)
        } catch (e: Exception) {
            android.util.Log.d("SoulBot", "文字注入失败: ${e.message}")
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

    private fun currentScreen(): Screen = when {
        !isSoulForeground() -> Screen.OUTSIDE
        findVisibleById(ID_QIYU_CHAT) != null -> Screen.QIYU_POPUP
        findVisibleById(ID_GIFT_CLOSE) != null -> Screen.GIFT
        findVisibleById(ID_INPUT_BOX) != null -> Screen.CHAT
        findVisibleById(ID_CHAT_SECRET) != null -> Screen.PROFILE
        findVisibleById(ID_GREETING_TIPS) != null -> Screen.GREETING_LIST
        findVisibleById(ID_SQUARE_PAGER) != null -> Screen.SQUARE
        findVisibleById(ID_CONV_NAME) != null -> Screen.LIST
        else -> Screen.OTHER
    }

    private fun isOnChat() = currentScreen() == Screen.CHAT
    private fun isOnList() = currentScreen() == Screen.LIST
    private fun isOnSquare() = currentScreen() == Screen.SQUARE
    private fun isOnProfile() = currentScreen() == Screen.PROFILE
    private fun isOnGift() = currentScreen() == Screen.GIFT

    private fun selectedSquareChannel(): String =
        findByIds(ID_SQUARE_CHANNEL_TAB)
            .firstOrNull { it.isSelected && isVisibleOnScreen(it) }
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
        if (localTab.isSelected) return localTitle

        setNextStep("切换到同城·$localTitle")
        tapNode(localTab)
        repeat(10) {
            sleep(250)
            if (selectedSquareChannel() == localTitle) return localTitle
        }
        android.util.Log.d("SoulBot", "同城频道切换失败: $localTitle")
        return null
    }

    // ===== 导航（都带验证） =====

    private fun returnToList(): Boolean {
        setNextStep("返回会话列表")
        // 从任意界面返回聊天列表：二级界面按 back，广场点聊天 tab
        for (i in 0 until 4) {
            if (!isSoulForeground()) return false
            if (isOnList()) return true
            if (isOnSquare()) return gotoChatList()
            pressBack(); sleep(1000)
        }
        return isOnList()
    }

    private fun gotoSquare(): Boolean {
        if (!isSoulForeground()) return false
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
            pressBack(); sleep(1000)
        }
        return isOnSquare() && ensureLocalSquareChannel() != null
    }

    private fun gotoChatList(): Boolean {
        if (!isSoulForeground()) return false
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
            pressBack(); sleep(1000)
        }
        return isOnList()
    }

    private fun backToSquare(): Boolean {
        for (i in 0 until 4) {
            if (!isSoulForeground()) return false
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

    private fun parseMinutesAgo(s: String): Long {
        val t = s.trim()
        if (t.isEmpty() || t == "刚刚" || t == "现在") return 0
        Regex("""(\d+)\s*分钟前""").find(t)?.let { return it.groupValues[1].toLong() }
        Regex("""(\d+)\s*小时前""").find(t)?.let { return it.groupValues[1].toLong() * 60 }
        Regex("""(\d+)\s*天前""").find(t)?.let { return it.groupValues[1].toLong() * 24 * 60 }
        if (t == "昨天") return 24 * 60
        Regex("""(?:今天\s*)?(\d{1,2}):(\d{2})""").find(t)?.let {
            val hh = it.groupValues[1].toInt()
            val mm = it.groupValues[2].toInt()
            val now = java.time.LocalTime.now()
            var minutes = (now.hour - hh) * 60L + (now.minute - mm)
            if (minutes < 0) minutes += 24 * 60
            return minutes
        }
        return 24 * 60
    }

    private fun replyDelay(timeStr: String): Long {
        val minutesAgo = parseMinutesAgo(timeStr)
        if (minutesAgo >= 1) return 0L   // 消息已发超过 1 分钟，立即回
        return thinkDelay()
    }

    // ===== 拆分回复 =====

    private fun splitReply(text: String): List<String> {
        val t = text.trim()
        // 按换行和句子结束符拆分，两条合一句的能分开
        val parts = t.split(Regex("""(?<=[。！？!?…~])|\n+"""))
            .map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size <= 1) listOf(t) else parts.take(3)
    }

    // ===== 发送（仅聊天界面） =====

    private fun send(text: String): Boolean {
        if (!isOnChat()) return false
        val pieces = splitReply(text)
        for ((i, piece) in pieces.withIndex()) {
            setNextStep("输入第 " + (i + 1) + " 段回复")
            val input = findById(ID_INPUT_BOX) ?: return false
            tapNode(input)
            sleep(300)
            if (!setText(input, piece)) {
                statusText = "输入失败"
                android.util.Log.d("SoulBot", "输入框文字注入失败")
                return false
            }
            sleep(500)
            // 等发送按钮出现再点，最多等 4 秒
            var sent = false
            setNextStep("发送第 " + (i + 1) + " 段回复")
            for (wait in 0 until 8) {
                if (textOf(findById(ID_INPUT_BOX)).isEmpty()) {
                    sent = true
                    break
                }
                val btn = findById(ID_SEND_BUTTON)
                if (btn != null) {
                    tapNode(btn)
                    // A successful send clears the input. Verify the UI instead of
                    // treating a dispatched gesture as a delivered message.
                    for (verify in 0 until 6) {
                        sleep(250)
                        if (textOf(findById(ID_INPUT_BOX)).isEmpty()) {
                            sent = true
                            break
                        }
                    }
                    if (sent) break
                }
                sleep(500)
            }
            if (!sent) {
                statusText = "发送失败"
                android.util.Log.d("SoulBot", "发送按钮点击后输入框未清空")
                return false
            }
            if (i < pieces.size - 1) {
                val nextPartDelay = random.nextLong(2000, 5000)
                setNextStep("发送第 " + (i + 2) + " 段回复", nextPartDelay)
                sleep(nextPartDelay)
            }
        }
        return true
    }

    // ===== 读聊天消息（仅聊天界面） =====

    private fun readThread(): List<Pair<String, String>> {
        if (!isOnChat()) return emptyList()
        val items = findByIds(ID_CHAT_ITEM)
        val avatars = findByIds(ID_CHAT_AVATAR)
        val texts = findByIds(ID_CONTENT_TEXT)
        val result = mutableListOf<Pair<String, String>>()
        for (item in items) {
            val ib = rect(item)
            var text = ""
            for (t in texts) {
                if (contains(ib, rect(t))) { text = textOf(t); break }
            }
            if (text.isEmpty()) continue
            var role = "in"
            for (av in avatars) {
                if (contains(ib, rect(av))) {
                    role = if (rect(av).centerX() > screenWidth() / 2) "out" else "in"
                    break
                }
            }
            result.add(role to text)
        }
        return result
    }

    // ===== 扫未读（仅会话列表） =====

    private fun scanUnread(): List<Pair<String, String>> {
        if (!isOnList()) return emptyList()
        val items = findByIds(ID_CONV_ITEM)
        val names = findByIds(ID_CONV_NAME)
        val times = findByIds(ID_CONV_TIME)
        val badges = findByIds(ID_UNREAD_BADGE)
        val result = mutableListOf<Pair<String, String>>()
        for (item in items) {
            val ib = rect(item)
            val hasBadge = badges.any { contains(ib, rect(it)) }
            if (!hasBadge) continue
            var name = ""
            for (n in names) { if (contains(ib, rect(n))) { name = textOf(n); break } }
            var time = ""
            for (tm in times) { if (contains(ib, rect(tm))) { time = textOf(tm); break } }
            result.add(name to time)
        }
        return result
    }

    private fun findConversation(name: String): AccessibilityNodeInfo? {
        if (!isOnList()) return null
        val items = findByIds(ID_CONV_ITEM)
        val names = findByIds(ID_CONV_NAME)
        for (item in items) {
            val ib = rect(item)
            for (n in names) {
                if (contains(ib, rect(n)) && textOf(n) == name) return item
            }
        }
        return null
    }

    // ===== LLM =====

    private fun genReply(thread: List<Pair<String, String>>): String? {
        val recent = thread.takeLast(CONTEXT_LEN)
        val msgs = mutableListOf<Pair<String, String>>()
        for ((role, text) in recent) {
            msgs.add((if (role == "in") "user" else "assistant") to text)
        }
        return ModelClient.chat(currentApiKey, currentModel.baseUrl, currentModel.model, ModelClient.buildSystemPrompt(), msgs)
    }

    private fun genDmReply(postText: String): String? {
        val sys = ModelClient.buildSystemPrompt() +
            " 现在要根据对方发的一条广场动态，写一句自然的私聊开场白，能接上这条动态。"
        return ModelClient.chat(currentApiKey, currentModel.baseUrl, currentModel.model, sys, listOf("user" to "对方发的广场动态：$postText\n写一句自然、口语化、简短的私聊开场白。"))
    }

    private fun genQiyuReply(signature: String, reason: String, qiyuTag: String): String? {
        val sys = ModelClient.buildSystemPrompt() +
            " 现在对方通过奇遇铃匹配了你，要根据Ta的签名和引力签写一句自然的破冰开场白。"
        val userMsg = "对方签名：$signature\n匹配理由：$reason\n引力签：$qiyuTag\n" +
            "写一句自然、口语化、简短的破冰开场白，能接上对方的引力签或匹配理由。"
        return ModelClient.chat(currentApiKey, currentModel.baseUrl, currentModel.model, sys, listOf("user" to userMsg))
    }

    private fun readQiyuSignature(): String {
        for (node in findByIds("cn.soulapp.android:id/tv_title")) {
            val t = textOf(node)
            if (t.startsWith("Ta的引力签")) return t.removePrefix("Ta的引力签：").removePrefix("Ta的引力签:")
        }
        return ""
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
            pressBack(); sleep(1200); return false
        }
        val qiyuTag = readQiyuSignature()
        val reply = genQiyuReply(signature, reason, qiyuTag)
        val sent = reply != null && send(reply)
        if (sent) {
            historyDb?.record(signature, "理由：$reason\n引力签：$qiyuTag", reply)
        }
        pressBack()
        sleep(1200)
        return sent
    }

    // ===== 回复会话（仅会话列表起点） =====

    private fun replyTo(name: String, seen: ConversationSeenTracker): Boolean {
        if (!isOnList()) return false
        val item = findConversation(name) ?: return false
        setNextStep("打开 $name 的会话")
        tapNode(item)
        sleep(1500)
        if (!isOnChat()) { gotoChatList(); return false }
        val thread = readThread()
        val incoming = thread.filter { it.first == "in" }
        if (incoming.isEmpty()) { gotoChatList(); return false }
        val incomingTexts = incoming.map { it.second }
        val new = seen.unseen(name, incomingTexts)
        if (new.isEmpty()) { gotoChatList(); return false }
        setNextStep("生成给 $name 的回复")
        val reply = genReply(thread) ?: run { gotoChatList(); return false }
        val sent = send(reply)
        if (sent) {
            seen.markSeen(name, incomingTexts)
            historyDb?.record(name, incoming.last().second, reply)
        }
        gotoChatList()
        return sent
    }

    // ===== 收到的新招呼 =====

    private fun hasGreeting(): Boolean = findById(ID_GREETING_TITLE) != null

    private fun hasUnreadRedDot(): Boolean = findById(ID_MSG_RED_DOT) != null

    private fun openGreetingList(): Boolean {
        setNextStep("打开新招呼列表")
        for (i in 0 until 2) {
            if (currentScreen() == Screen.GREETING_LIST) return true
            findById(ID_GREETING_TITLE)?.let { tapNode(it) }
            sleep(1500)
        }
        return currentScreen() == Screen.GREETING_LIST
    }

    private fun scanGreetingUsers(): List<String> {
        if (currentScreen() != Screen.GREETING_LIST) return emptyList()
        return findByIds(ID_CONV_NAME).map { textOf(it) }.filter { it.isNotEmpty() }
    }

    private fun replyToGreeting(name: String, seen: ConversationSeenTracker): Boolean {
        if (currentScreen() != Screen.GREETING_LIST) return false
        val nameNode = findByIds(ID_CONV_NAME).firstOrNull { textOf(it) == name } ?: return false
        setNextStep("打开 $name 的招呼")
        tapNode(nameNode)
        sleep(1500)
        if (!isOnChat()) { pressBack(); sleep(1200); return false }
        val thread = readThread()
        val incoming = thread.filter { it.first == "in" }
        if (incoming.isEmpty()) { pressBack(); sleep(1200); return false }
        val incomingTexts = incoming.map { it.second }
        val new = seen.unseen("招呼:$name", incomingTexts)
        if (new.isEmpty()) { pressBack(); sleep(1200); return false }
        setNextStep("生成给 $name 的回复")
        val reply = genReply(thread) ?: run { pressBack(); sleep(1200); return false }
        val sent = send(reply)
        if (sent) {
            seen.markSeen("招呼:$name", incomingTexts)
            historyDb?.record(name, incoming.last().second, reply)
        }
        pressBack()
        sleep(1200)
        return sent
    }

    // ===== 广场 =====

    private fun readPosts(expectedChannel: String): List<Pair<String, AccessibilityNodeInfo>> {
        if (!isOnSquare()) return emptyList()
        if (selectedSquareChannel() != expectedChannel) return emptyList()
        val cards = findByIds(ID_SQUARE_CARD).filter(::isVisibleOnScreen)
        val texts = findByIds(ID_SQUARE_TEXT).filter(::isVisibleOnScreen)
        val avatars = findByIds(ID_SQUARE_AVATAR).filter(::isVisibleOnScreen)
        val result = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        for (card in cards) {
            val cb = rect(card)
            var text = ""
            for (t in texts) { if (contains(cb, rect(t))) { text = textOf(t); break } }
            var avatar: AccessibilityNodeInfo? = null
            for (a in avatars) { if (contains(cb, rect(a))) { avatar = a; break } }
            if (text.length >= 2 && avatar != null) result.add(text to avatar)
        }
        return result
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

    private fun openProfile(avatar: AccessibilityNodeInfo): Boolean {
        val bounds = rect(avatar)
        setNextStep("打开同城用户主页")
        for (attempt in 0 until 2) {
            if (attempt == 0) {
                tapNode(avatar)
            } else {
                android.util.Log.d("SoulBot", "点头像未进资料页，重试一次")
                tap(bounds.exactCenterX().toFloat(), bounds.exactCenterY().toFloat())
            }
            for (wait in 0 until 8) {
                sleep(250)
                if (isOnProfile()) return true
                if (!isSoulForeground()) return false
            }
            if (!isOnSquare()) return false
        }
        android.util.Log.d("SoulBot", "点头像仍失败，跳过")
        return false
    }

    private fun browseSquareOnce(seenDm: MutableSet<String>, greetDb: GreetDatabase): Boolean {
        if (!isOnSquare()) return false
        val channel = ensureLocalSquareChannel() ?: run {
            statusText = "未找到同城频道"
            return false
        }
        setNextStep("浏览同城·$channel")
        for (screen in 0 until 6) {
            if (selectedSquareChannel() != channel) {
                android.util.Log.d(
                    "SoulBot",
                    "广场频道发生变化，停止本轮: $channel -> ${selectedSquareChannel()}",
                )
                return false
            }
            val posts = readPosts(channel)
            for ((text, avatar) in posts) {
                if (text in seenDm) continue
                if (selectedSquareChannel() != channel || !isVisibleOnScreen(avatar)) continue
                seenDm.add(text)
                if (!openProfile(avatar)) { backToSquare(); continue }
                val name = getProfileName()
                if (name.isNotEmpty() && !greetDb.shouldGreet(name, text, Prefs.getGreetIntervalHours(applicationContext))) { backToSquare(); continue }
                val secret = findById(ID_CHAT_SECRET)
                if (secret == null) { backToSquare(); continue }
                setNextStep("打开 $name 的私聊")
                tapNode(secret)                 // 主页点私聊
                sleep(2500)
                when {
                    isOnGift() -> {             // 要送礼，跳过
                        skipGift()
                        backToSquare()
                    }
                    isOnChat() -> {             // 免费进聊天
                        setNextStep("生成给 $name 的开场白")
                        val reply = genDmReply(text)
                        val sent = reply != null && send(reply)
                        if (sent) {
                            if (name.isNotEmpty()) {
                                greetDb.recordGreet(name, text)
                                historyDb?.record(name, text, reply)
                            }
                        }
                        backToSquare()
                        return sent
                    }
                    else -> backToSquare()
                }
            }
            if (!isOnSquare()) break
            setNextStep("加载更多同城动态")
            swipeUp(0.82f, 0.32f)               // 按当前屏幕尺寸上滑翻更多
            sleep(1500)
        }
        return false
    }

    // ===== 主循环 =====

    private fun runLoop() {
        try {
            runLoopInternal()
        } finally {
            running = false
            statusDeadline = 0
            if (statusText != "未配置 API Key" && statusText != "模型配置不完整") {
                statusText = "已停止"
            }
            if (worker === Thread.currentThread()) worker = null
        }
    }

    private fun runLoopInternal() {
        val ctx = applicationContext
        val modelName = Prefs.getSelectedModel(ctx)
        var m = ModelList.findByName(modelName)
        if (m.isCustom) {
            m = ModelConfig(m.name, Prefs.getCustomBaseUrl(ctx), Prefs.getCustomModel(ctx), true)
        }
        currentModel = m
        currentApiKey = Prefs.getApiKey(ctx, currentModel.name)
        if (currentApiKey.isEmpty()) {
            statusText = "未配置 API Key"
            statusDeadline = 0
            return
        }
        if (currentModel.baseUrl.isBlank() || currentModel.model.isBlank()) {
            statusText = "模型配置不完整"
            statusDeadline = 0
            return
        }
        setNextStep("检查当前页面")

        val seen = ConversationSeenTracker()
        val seenDm = mutableSetOf<String>()
        val greetDb = GreetDatabase(applicationContext)
        historyDb = HistoryDatabase(applicationContext)
        val squareInterval = Prefs.getSquareInterval(applicationContext).toLong()
        var lastDm = 0L
        var lastScreenName = ""
        var stuckCount = 0
        var unreadTopSearchComplete = false

        while (running) {
            try {
                val screen = currentScreen()
                if (screen != Screen.LIST) unreadTopSearchComplete = false
                if (screen.name == lastScreenName) stuckCount++ else {
                    lastScreenName = screen.name
                    stuckCount = 0
                    android.util.Log.d(
                        "SoulBot",
                        "screen=${screen.name} pager=${findVisibleById(ID_SQUARE_PAGER) != null} avatar=${findVisibleById(ID_SQUARE_AVATAR) != null} name=${findVisibleById(ID_CONV_NAME) != null} input=${findVisibleById(ID_INPUT_BOX) != null}"
                    )
                }
                if (stuckCount > 8) {
                    setNextStep("重新检测当前页面", 5000)
                    sleep(5000)
                    stuckCount = 0
                    continue
                }

                if (!REPLY_CHAT) {
                    // 测试模式：只刷广场
                    when (screen) {
                        Screen.CHAT, Screen.GIFT, Screen.PROFILE -> {
                            setNextStep("返回同城广场")
                            pressBack()
                            sleep(1200)
                        }
                        Screen.SQUARE -> {
                            if (System.currentTimeMillis() - lastDm >= squareInterval * 1000L) {
                                val sent = browseSquareOnce(seenDm, greetDb)
                                if (sent) {
                                    lastDm = System.currentTimeMillis()
                                } else {
                                    android.util.Log.d("SoulBot", "本轮广场未成功私聊，不进入冷却")
                                    setNextStep("继续寻找可私聊用户", SQUARE_RETRY_DELAY_MS)
                                    sleep(SQUARE_RETRY_DELAY_MS)
                                }
                            } else {
                                setNextStep("检查广场私信时间", 3000)
                                sleep(3000)
                            }
                        }
                        else -> gotoSquare()
                    }
                    continue
                }

                // 正常模式：优先回未读，空闲刷广场
                when (screen) {
                    Screen.QIYU_POPUP -> {
                        handleQiyu()
                    }
                    Screen.LIST -> {
                        if (hasGreeting()) {
                            setNextStep("处理新招呼")
                            openGreetingList()
                        } else if (hasUnreadRedDot()) {
                            val unread = scanUnread()
                            if (unread.isNotEmpty()) {
                                unreadTopSearchComplete = false
                                val (name, timeStr) = unread[0]
                                val delay = replyDelay(timeStr)
                                if (delay > 0) {
                                    setNextStep("回复 $name", delay)
                                    sleep(delay)
                                }
                                setNextStep("回复 $name")
                                replyTo(name, seen)
                            } else if (!unreadTopSearchComplete) {
                                setNextStep("回到会话顶部")
                                scrollConversationToTop()
                                unreadTopSearchComplete = true
                                setNextStep("重新检查未读消息", 800)
                                sleep(800)
                            } else {
                                setNextStep("重新检查未读消息", 3000)
                                sleep(3000)
                            }
                        } else {
                            unreadTopSearchComplete = false
                            val remainMs = squareInterval * 1000L - (System.currentTimeMillis() - lastDm)
                            if (remainMs <= 0) {
                                setNextStep("进入同城广场并私信")
                                var sent = false
                                if (gotoSquare()) {
                                    sent = browseSquareOnce(seenDm, greetDb)
                                    if (sent) {
                                        lastDm = System.currentTimeMillis()
                                    }
                                    gotoChatList()
                                }
                                if (!sent) {
                                    android.util.Log.d("SoulBot", "本轮广场未成功私聊，不进入冷却")
                                    setNextStep("继续寻找可私聊用户", SQUARE_RETRY_DELAY_MS)
                                    sleep(SQUARE_RETRY_DELAY_MS)
                                }
                            } else {
                                setNextStep("进入同城广场并私信", remainMs)
                                sleep(3000)
                            }
                        }
                    }
                    Screen.GREETING_LIST -> {
                        val users = scanGreetingUsers()
                        if (users.isNotEmpty()) {
                            setNextStep("回复招呼 " + users[0])
                            replyToGreeting(users[0], seen)
                        } else {
                            setNextStep("返回会话列表")
                            pressBack()
                            sleep(1200)
                        }
                    }
                    Screen.OUTSIDE -> {
                        setNextStep("检查 Soul 是否已打开", 1500)
                        sleep(1500)
                    }
                    else -> returnToList()
                }
            } catch (e: StoppedException) {
                break
            } catch (e: Exception) {
                android.util.Log.d("SoulBot", "异常: ${e.message}")
                setNextStep("异常后重试", 2000)
                sleep(2000)
            }
        }
    }
}
