package com.soulbot.app

/** Pure parsing helpers for the title texts exposed by Soul's match chat header. */
object SoulMatchContent {
    private val metadataPrefixes = listOf(
        "Ta的引力签",
        "Ta的认证",
        "Ta的星座",
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

    fun sessionKey(name: String, gravityTags: String): String =
        comparableText(name) + "\u0000" + comparableText(gravityTags)

    private fun String.substringAfterLabel(): String {
        val colon = indexOfFirst { it == '：' || it == ':' }
        return if (colon >= 0) substring(colon + 1).trim() else ""
    }
}
