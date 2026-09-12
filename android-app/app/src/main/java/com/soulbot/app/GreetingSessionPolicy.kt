package com.soulbot.app

/** Rules for consuming Soul's "new greeting" list without reopening stale rows forever. */
internal object GreetingSessionPolicy {
    const val EMPTY_LIST_RECHECK_MS = 120_000L
    const val SYSTEM_GREETING_CONTEXT = "系统代发的新招呼，聊天页暂无对方真实消息"

    fun userKey(name: String): String = comparableText(name).take(120)

    fun pendingUsers(names: List<String>, handledKeys: Set<String>): List<String> =
        names.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(::userKey)
            .filter { userKey(it) !in handledKeys }
            .toList()
}
