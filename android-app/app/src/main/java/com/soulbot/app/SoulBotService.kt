package com.soulbot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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

        const val REPLY_CHAT = true
        const val DM_INTERVAL = 240L  // 广场私信间隔（秒）
        const val CONTEXT_LEN = 12

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

    private enum class Screen { CHAT, LIST, SQUARE, PROFILE, GIFT, GREETING_LIST, QIYU_POPUP, OTHER }

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
        if (running) return
        running = true
        android.util.Log.d("SoulBot", "start() called, launching runLoop thread")
        worker = Thread { runLoop() }.apply { start() }
    }

    fun stop() {
        running = false
        statusText = "已停止"
        statusDeadline = 0
        worker?.interrupt()
        worker = null
    }

    // ===== 基础辅助 =====

    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

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

    private fun rect(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r
    }

    private fun textOf(node: AccessibilityNodeInfo?): String =
        node?.text?.toString()?.trim() ?: ""

    private fun tap(x: Float, y: Float) {
        val cx = maxOf(0f, x)
        val cy = maxOf(0f, y)
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun tapNode(node: AccessibilityNodeInfo) {
        val r = rect(node)
        tap(r.exactCenterX().toFloat(), r.exactCenterY().toFloat())
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

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StoppedException()
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun contains(outer: Rect, inner: Rect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    // ===== 界面识别 =====

    private fun currentScreen(): Screen = when {
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

    // ===== 导航（都带验证） =====

    private fun returnToList(): Boolean {
        // 从任意界面返回聊天列表：二级界面按 back，广场点聊天 tab
        for (i in 0 until 4) {
            if (isOnList()) return true
            if (isOnSquare()) return gotoChatList()
            pressBack(); sleep(1000)
        }
        return isOnList()
    }

    private fun gotoSquare(): Boolean {
        for (i in 0 until 3) {
            val tab = findById(ID_SQUARE_TAB)
            if (tab != null) {
                tapNode(tab)
                for (w in 0 until 8) {
                    if (isOnSquare()) return true
                    sleep(800)
                }
                return isOnSquare()
            }
            pressBack(); sleep(1000)
        }
        return isOnSquare()
    }

    private fun gotoChatList(): Boolean {
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
            pressBack(); sleep(1000)
        }
        return isOnList()
    }

    private fun backToSquare(): Boolean {
        for (i in 0 until 4) {
            if (isOnSquare()) return true
            pressBack(); sleep(1000)
        }
        return isOnSquare()
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
            val input = findById(ID_INPUT_BOX) ?: return false
            tapNode(input)
            sleep(300)
            setText(input, piece)
            sleep(500)
            // 等发送按钮出现再点，最多等 4 秒
            var sent = false
            for (wait in 0 until 8) {
                val btn = findById(ID_SEND_BUTTON)
                if (btn != null) {
                    tapNode(btn)
                    sent = true
                    break
                }
                sleep(500)
            }
            if (!sent) return false
            if (i < pieces.size - 1) sleep(random.nextLong(2000, 5000))
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
                    role = if (rect(av).left > 540) "out" else "in"
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

    private fun handleQiyu(seen: MutableSet<String>): Boolean {
        if (currentScreen() != Screen.QIYU_POPUP) return false
        val signature = textOf(findById(ID_QIYU_SIGNATURE))
        val reason = textOf(findById(ID_QIYU_REASON))
        val chatBtn = findById(ID_QIYU_CHAT) ?: return false
        statusText = "奇遇铃私聊"
        statusDeadline = 0
        tapNode(chatBtn)
        sleep(2500)
        if (!isOnChat()) {
            if (isOnGift()) { skipGift(); return false }
            pressBack(); sleep(1200); return false
        }
        val qiyuTag = readQiyuSignature()
        val reply = genQiyuReply(signature, reason, qiyuTag)
        if (reply != null) {
            send(reply)
            historyDb?.record(signature, "理由：$reason\n引力签：$qiyuTag", reply)
        }
        pressBack()
        sleep(1200)
        return reply != null
    }

    // ===== 回复会话（仅会话列表起点） =====

    private fun replyTo(name: String, seen: MutableSet<String>): Boolean {
        if (!isOnList()) return false
        val item = findConversation(name) ?: return false
        tapNode(item)
        sleep(1500)
        if (!isOnChat()) { gotoChatList(); return false }
        val thread = readThread()
        val incoming = thread.filter { it.first == "in" }
        if (incoming.isEmpty()) { gotoChatList(); return false }
        val new = incoming.filter { it.second !in seen }
        if (new.isEmpty()) { gotoChatList(); return false }
        incoming.forEach { seen.add(it.second) }
        val reply = genReply(thread) ?: run { gotoChatList(); return false }
        send(reply)
        historyDb?.record(name, incoming.last().second, reply)
        gotoChatList()
        return true
    }

    // ===== 收到的新招呼 =====

    private fun hasGreeting(): Boolean = findById(ID_GREETING_TITLE) != null

    private fun hasUnreadRedDot(): Boolean = findById(ID_MSG_RED_DOT) != null

    private fun openGreetingList(): Boolean {
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

    private fun replyToGreeting(name: String, seen: MutableSet<String>): Boolean {
        if (currentScreen() != Screen.GREETING_LIST) return false
        val nameNode = findByIds(ID_CONV_NAME).firstOrNull { textOf(it) == name } ?: return false
        tapNode(nameNode)
        sleep(1500)
        if (!isOnChat()) { pressBack(); sleep(1200); return false }
        val thread = readThread()
        val incoming = thread.filter { it.first == "in" }
        if (incoming.isEmpty()) { pressBack(); sleep(1200); return false }
        val new = incoming.filter { it.second !in seen }
        if (new.isEmpty()) { pressBack(); sleep(1200); return false }
        incoming.forEach { seen.add(it.second) }
        val reply = genReply(thread) ?: run { pressBack(); sleep(1200); return false }
        send(reply)
        historyDb?.record(name, incoming.last().second, reply)
        pressBack()
        sleep(1200)
        return true
    }

    // ===== 广场 =====

    private fun readPosts(): List<Pair<String, AccessibilityNodeInfo>> {
        if (!isOnSquare()) return emptyList()
        val cards = findByIds(ID_SQUARE_CARD)
        val texts = findByIds(ID_SQUARE_TEXT)
        val avatars = findByIds(ID_SQUARE_AVATAR)
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
            findById(ID_GIFT_CLOSE)?.let { tapNode(it) }
            sleep(1500)
        }
    }

    private fun browseSquareOnce(seenDm: MutableSet<String>, greetDb: GreetDatabase): Boolean {
        if (!isOnSquare()) return false
        for (screen in 0 until 6) {
            val posts = readPosts()
            for ((text, avatar) in posts) {
                if (text in seenDm) continue
                seenDm.add(text)
                tapNode(avatar)                 // 点头像进主页
                sleep(2000)
                if (!isOnProfile()) { backToSquare(); continue }
                val name = getProfileName()
                if (name.isNotEmpty() && !greetDb.shouldGreet(name, text, Prefs.getGreetIntervalHours(applicationContext))) { backToSquare(); continue }
                val secret = findById(ID_CHAT_SECRET)
                if (secret == null) { backToSquare(); continue }
                tapNode(secret)                 // 主页点私聊
                sleep(2500)
                when {
                    isOnGift() -> {             // 要送礼，跳过
                        skipGift()
                        backToSquare()
                    }
                    isOnChat() -> {             // 免费进聊天
                        val reply = genDmReply(text)
                        if (reply != null) {
                            send(reply)
                            if (name.isNotEmpty()) {
                                greetDb.recordGreet(name, text)
                                historyDb?.record(name, text, reply)
                            }
                        }
                        backToSquare()
                        return reply != null
                    }
                    else -> backToSquare()
                }
            }
            if (!isOnSquare()) break
            swipe(540f, 1800f, 540f, 600f)      // 上滑翻更多
            sleep(1500)
        }
        return false
    }

    // ===== 主循环 =====

    private fun runLoop() {
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
        statusText = "运行中"
        statusDeadline = 0

        val seen = mutableSetOf<String>()
        val seenDm = mutableSetOf<String>()
        val greetDb = GreetDatabase(applicationContext)
        historyDb = HistoryDatabase(applicationContext)
        val squareInterval = Prefs.getSquareInterval(applicationContext).toLong()
        var lastDm = 0L
        var lastScreenName = ""
        var stuckCount = 0

        while (running) {
            try {
                val screen = currentScreen()
                if (screen.name == lastScreenName) stuckCount++ else {
                    lastScreenName = screen.name
                    stuckCount = 0
                    android.util.Log.d(
                        "SoulBot",
                        "screen=${screen.name} pager=${findVisibleById(ID_SQUARE_PAGER) != null} avatar=${findVisibleById(ID_SQUARE_AVATAR) != null} name=${findVisibleById(ID_CONV_NAME) != null} input=${findVisibleById(ID_INPUT_BOX) != null}"
                    )
                }
                if (stuckCount > 8) { sleep(5000); stuckCount = 0; continue }

                if (!REPLY_CHAT) {
                    // 测试模式：只刷广场
                    when (screen) {
                        Screen.CHAT, Screen.GIFT, Screen.PROFILE -> { pressBack(); sleep(1200) }
                        Screen.SQUARE -> {
                            if (System.currentTimeMillis() - lastDm >= squareInterval * 1000L) {
                                browseSquareOnce(seenDm, greetDb)
                                lastDm = System.currentTimeMillis()
                            } else sleep(3000)
                        }
                        else -> gotoSquare()
                    }
                    continue
                }

                // 正常模式：优先回未读，空闲刷广场
                when (screen) {
                    Screen.QIYU_POPUP -> {
                        handleQiyu(seen)
                    }
                    Screen.LIST -> {
                        if (hasGreeting()) {
                            statusText = "处理新招呼"
                            statusDeadline = 0
                            openGreetingList()
                        } else if (hasUnreadRedDot()) {
                            val unread = scanUnread()
                            if (unread.isNotEmpty()) {
                                val (name, timeStr) = unread[0]
                                val delay = replyDelay(timeStr)
                                if (delay > 0) {
                                    statusText = "回复 $name（思考中）"
                                    statusDeadline = System.currentTimeMillis() + delay
                                    sleep(delay)
                                }
                                statusText = "回复 $name"
                                statusDeadline = 0
                                replyTo(name, seen)
                            } else {
                                statusText = "往下找未读"
                                statusDeadline = 0
                                swipe(540f, 1900f, 540f, 1000f, 500)
                                sleep(1500)
                            }
                        } else {
                            val remainMs = squareInterval * 1000L - (System.currentTimeMillis() - lastDm)
                            if (remainMs <= 0) {
                                statusText = "刷广场私信"
                                statusDeadline = 0
                                lastDm = System.currentTimeMillis()
                                if (gotoSquare()) {
                                    browseSquareOnce(seenDm, greetDb)
                                    gotoChatList()
                                }
                            } else {
                                statusText = "空闲等待"
                                statusDeadline = System.currentTimeMillis() + remainMs
                                sleep(3000)
                            }
                        }
                    }
                    Screen.GREETING_LIST -> {
                        val users = scanGreetingUsers()
                        if (users.isNotEmpty()) {
                            statusText = "回招呼 ${users[0]}"
                            statusDeadline = 0
                            replyToGreeting(users[0], seen)
                        } else {
                            statusText = "返回列表"
                            statusDeadline = 0
                            pressBack(); sleep(1200)
                        }
                    }
                    else -> returnToList()
                }
            } catch (e: StoppedException) {
                break
            } catch (e: Exception) {
                android.util.Log.d("SoulBot", "异常: ${e.message}")
                sleep(2000)
            }
        }
    }
}
