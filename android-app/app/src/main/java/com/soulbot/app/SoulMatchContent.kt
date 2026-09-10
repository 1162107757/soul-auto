package com.soulbot.app

/** Pure parsing helpers for the title texts exposed by Soul's match chat header. */
object SoulMatchContent {
    private val metadataPrefixes = listOf(
        "Ta的引力签",
        "Ta的认证",
        "Ta的星座",
        "Ta的礼仪分",
        "Ta仁爱星球",
        "你们的共同点",
        "Ta的共同点",
        "匹配度",
        "查看主页",
    )

    fun gravityTags(titleTexts: List<String>): String = titleTexts
        .map(String::trim)
        .firstOrNull { it.startsWith("Ta的引力签") }
        ?.substringAfterLabel()
        .orEmpty()

    fun displayName(titleTexts: List<String>): String = titleTexts
        .map(String::trim)
        .firstOrNull { text ->
            text.isNotEmpty() && metadataPrefixes.none(text::startsWith)
        }
        .orEmpty()

    fun zodiac(titleTexts: List<String>): String = titleTexts
        .map(String::trim)
        .firstOrNull { it.startsWith("Ta的星座") }
        ?.substringAfterLabel()
        .orEmpty()

    /** Best available material for an opener, in descending order of specificity. */
    fun openingBasis(name: String, gravityTags: String, zodiac: String): String = when {
        gravityTags.isNotBlank() -> "引力签：$gravityTags"
        name.isNotBlank() && zodiac.isNotBlank() -> "昵称：$name；星座：$zodiac"
        zodiac.isNotBlank() -> "星座：$zodiac"
        name.isNotBlank() -> "昵称：$name"
        else -> ""
    }

    fun sessionKey(name: String, gravityTags: String): String =
        comparableText(name) + "\u0000" + comparableText(gravityTags)

    private fun String.substringAfterLabel(): String {
        val colon = indexOfFirst { it == '：' || it == ':' }
        return if (colon >= 0) substring(colon + 1).trim() else ""
    }
}
