package com.soulbot.app

data class SelfProfile(
    val gender: String = "男",
    val age: String = "25",
    val region: String = "",
    val zodiac: String = "",
    val occupation: String = "",
    val details: String = "",
) {
    fun promptContext(): String {
        val facts = listOf(
            "性别" to gender,
            "年龄" to age,
            "现居地区" to region,
            "星座" to zodiac,
            "职业或工作状态" to occupation,
            "其他资料" to details,
        ).mapNotNull { (label, rawValue) ->
            rawValue.trim().takeIf(String::isNotEmpty)?.let { value ->
                "- $label：${value.take(300)}"
            }
        }
        if (facts.isEmpty()) return ""
        return buildString {
            append("\n\n以下是你本人已经确认的真实资料：\n")
            append(facts.joinToString("\n"))
            append("\n对方问到这些信息时，直接按资料自然回答，不要说自己在读取配置。")
            append("没有填写的资料绝对不能编造；可以自然说暂时不想透露，或把话题轻轻带开。")
        }
    }
}
