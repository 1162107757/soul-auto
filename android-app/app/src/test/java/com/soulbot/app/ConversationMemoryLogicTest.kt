package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMemoryLogicTest {
    @Test
    fun `first visible thread stores every message`() {
        val visible = listOf(
            "in" to "你好",
            "out" to "刚忙完",
            "in" to "吃饭了吗",
        )

        assertEquals(
            visible,
            ConversationMemoryLogic.messagesAfterOverlap(emptyList(), visible),
        )
    }

    @Test
    fun `only appends messages after the shared tail`() {
        val existing = listOf(
            "in" to "你好",
            "out" to "刚忙完",
            "in" to "吃饭了吗",
        )
        val visible = listOf(
            "out" to "刚忙完",
            "in" to "吃饭了吗",
            "out" to "还没，你呢",
        )

        assertEquals(
            listOf("out" to "还没，你呢"),
            ConversationMemoryLogic.messagesAfterOverlap(existing, visible),
        )
    }

    @Test
    fun `maximum overlap handles repeated messages without duplicating them`() {
        val existing = listOf(
            "in" to "嗯",
            "out" to "怎么啦",
            "in" to "嗯",
        )
        val visible = listOf(
            "in" to "嗯",
            "out" to "怎么啦",
            "in" to "嗯",
        )

        assertEquals(
            emptyList<Pair<String, String>>(),
            ConversationMemoryLogic.messagesAfterOverlap(existing, visible),
        )
    }

    @Test
    fun `relevant memory ranks the matching old topic first`() {
        val candidates = listOf(
            entry("最近一直下雨，没出门", 3),
            entry("我家那只猫半夜总跑酷", 2),
            entry("上次说的酸梅酒真的不好喝", 1),
        )

        val result = ConversationMemoryLogic.rankRelevant(
            "你家猫咪现在还会半夜跑酷吗",
            candidates,
            2,
        )

        assertEquals(listOf("我家那只猫半夜总跑酷"), result.map { it.content })
    }

    private fun entry(content: String, time: Long) = ConversationMemoryEntry(
        eventKey = time.toString(),
        contact = "小明",
        role = "in",
        content = content,
        observedAt = time,
        source = "chat",
    )
}
