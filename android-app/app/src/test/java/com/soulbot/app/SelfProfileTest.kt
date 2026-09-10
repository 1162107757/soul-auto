package com.soulbot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfProfileTest {
    @Test
    fun promptContext_includesOnlyConfiguredFactsAndNoFabricationRule() {
        val prompt = SelfProfile(
            gender = "男",
            age = "26",
            region = "四川绵阳",
            zodiac = "双子座",
            occupation = "",
            details = "喜欢徒步和做饭",
        ).promptContext()

        assertTrue(prompt.contains("性别：男"))
        assertTrue(prompt.contains("年龄：26"))
        assertTrue(prompt.contains("现居地区：四川绵阳"))
        assertTrue(prompt.contains("星座：双子座"))
        assertTrue(prompt.contains("喜欢徒步和做饭"))
        assertFalse(prompt.contains("职业或工作状态："))
        assertTrue(prompt.contains("绝对不能编造"))
    }

    @Test
    fun emptyProfile_addsNoIdentityFacts() {
        val prompt = SelfProfile(
            gender = "",
            age = "",
            region = "",
            zodiac = "",
            occupation = "",
            details = "",
        ).promptContext()

        assertTrue(prompt.isEmpty())
    }
}
