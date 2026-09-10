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
        assertEquals("处女座", SoulMatchContent.zodiac(titles))
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

    @Test
    fun fallsBackToNameAndZodiacWhenGravityTagsAreMissing() {
        val titles = listOf(
            "ღ半盏♡+清酒ღ",
            "匹配度 96%",
            "Ta仁爱星球",
            "Ta的星座：双子座",
            "Ta的礼仪分：礼仪标兵",
        )

        val name = SoulMatchContent.displayName(titles)
        val zodiac = SoulMatchContent.zodiac(titles)

        assertEquals("ღ半盏♡+清酒ღ", name)
        assertEquals("双子座", zodiac)
        assertEquals(
            "昵称：ღ半盏♡+清酒ღ；星座：双子座",
            SoulMatchContent.openingBasis(name, "", zodiac),
        )
    }

    @Test
    fun commonPointLabelIsNotMistakenForAContactName() {
        assertEquals(
            "清风",
            SoulMatchContent.displayName(
                listOf("你们的共同点：火象星座", "匹配度 95%", "清风"),
            ),
        )
    }
}
