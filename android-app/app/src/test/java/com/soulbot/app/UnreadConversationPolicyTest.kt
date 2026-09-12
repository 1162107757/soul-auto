package com.soulbot.app

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadConversationPolicyTest {
    private val now = LocalDateTime.of(2026, 9, 12, 12, 0)

    @Test
    fun `orders unread conversations from oldest to newest`() {
        val rows = listOf(
            UnreadConversationCandidate("最新", "刚刚", 0),
            UnreadConversationCandidate("较早", "2小时前", 1),
            UnreadConversationCandidate("最早", "昨天 08:30", 2),
            UnreadConversationCandidate("中间", "35分钟前", 3),
        )

        assertEquals(
            listOf("最早", "较早", "中间", "最新"),
            UnreadConversationPolicy.oldestFirst(rows, now).map { it.name },
        )
    }

    @Test
    fun `parses clock weekday and concrete date labels`() {
        assertEquals(90L, UnreadConversationPolicy.ageMinutes("10:30", now))
        assertEquals(4L * 24L * 60L + 12L * 60L, UnreadConversationPolicy.ageMinutes("周二", now))
        assertEquals(4L * 24L * 60L + 12L * 60L, UnreadConversationPolicy.ageMinutes("9月8日", now))
    }

    @Test
    fun `future clock is treated as previous day`() {
        assertEquals(13L * 60L, UnreadConversationPolicy.ageMinutes("23:00", now))
    }

    @Test
    fun `unknown labels sort after recognized times`() {
        val rows = listOf(
            UnreadConversationCandidate("未知", "置顶", 2),
            UnreadConversationCandidate("最新", "刚刚", 0),
            UnreadConversationCandidate("最早", "1小时前", 1),
        )

        assertEquals(
            listOf("最早", "最新", "未知"),
            UnreadConversationPolicy.oldestFirst(rows, now).map { it.name },
        )
    }
}
