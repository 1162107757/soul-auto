package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingSessionPolicyTest {
    @Test
    fun `already attempted greeting is not reopened in the same run`() {
        val handled = setOf(GreetingSessionPolicy.userKey("小雨"))

        assertEquals(
            listOf("阿圆"),
            GreetingSessionPolicy.pendingUsers(
                listOf("小雨", " 小雨 ", "阿圆", "阿圆"),
                handled,
            ),
        )
    }

    @Test
    fun `name formatting differences share one session key`() {
        assertEquals(
            GreetingSessionPolicy.userKey(" 小 雨 "),
            GreetingSessionPolicy.userKey("小雨"),
        )
    }

    @Test
    fun `system greeting context is stable for persistent deduplication`() {
        assertEquals(
            "系统代发的新招呼，聊天页暂无对方真实消息",
            GreetingSessionPolicy.SYSTEM_GREETING_CONTEXT,
        )
    }
}
