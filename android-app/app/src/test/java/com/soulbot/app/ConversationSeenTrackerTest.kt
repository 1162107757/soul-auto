package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSeenTrackerTest {

    @Test
    fun `same text in different conversations remains unseen`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("你好"))

        assertEquals(listOf("你好"), tracker.unseen("小红", listOf("你好")))
    }

    @Test
    fun `sent conversation messages are filtered`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("你好"))

        assertTrue(tracker.unseen("小明", listOf("你好")).isEmpty())
        assertEquals(listOf("在吗"), tracker.unseen("小明", listOf("你好", "在吗")))
    }

    @Test
    fun `same text sent again is still a new occurrence`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("你好"))

        assertEquals(listOf("你好"), tracker.unseen("小明", listOf("你好", "你好")))
    }

    @Test
    fun `scrolled visible window keeps suffix overlap`() {
        val tracker = ConversationSeenTracker()
        tracker.markSeen("小明", listOf("第一条", "第二条"))

        assertEquals(
            listOf("第三条"),
            tracker.unseen("小明", listOf("第二条", "第三条")),
        )
    }
}
