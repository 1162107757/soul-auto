package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SoulMatchContentTest {
    @Test
    fun readsNameAndGravityTagsFromMatchHeaderTitles() {
        val titles = listOf(
            "学习如何成为一枚合格的海后",
            "Ta的引力签：找人聊天、猫咪吸引体、拒绝负能量",
            "Ta的认证：",
            "Ta的星座：处女座",
        )

        assertEquals("学习如何成为一枚合格的海后", SoulMatchContent.displayName(titles))
        assertEquals("找人聊天、猫咪吸引体、拒绝负能量", SoulMatchContent.gravityTags(titles))
    }

    @Test
    fun acceptsAsciiColonAndBuildsStableSessionKey() {
        val first = SoulMatchContent.sessionKey("小 明", "猫咪吸引体")
        val second = SoulMatchContent.sessionKey("小明", "猫咪吸引体")
        val other = SoulMatchContent.sessionKey("小明", "拒绝负能量")

        assertEquals("猫咪吸引体", SoulMatchContent.gravityTags(listOf("Ta的引力签: 猫咪吸引体")))
        assertEquals(first, second)
        assertNotEquals(first, other)
    }
}
