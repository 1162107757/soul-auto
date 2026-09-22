package com.soulbot.app

enum class ReplyScene { CHAT, SOUL_MATCH, QIYU, SQUARE }

data class OpeningContext(
    val scene: ReplyScene,
    val contactName: String = "",
    val content: String = "",
    val zodiac: String = "",
    val matchReason: String = "",
)

/** Only an anonymized form of our own sent opening is retained across contacts. */
data class OpeningFingerprint(val normalized: String, val tactic: String)

/** Local safeguards, not a collection of sentences to copy into conversations. */
object OpeningReplyPolicy {
    private val punctuation = Regex("""[\s\p{P}\p{S}]+""")
    private val zodiacs = listOf(
        "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
        "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座",
    )
    private val repeatedTactics = setOf("city_confirmation", "nickname_origin", "zodiac_probe")
    private val tacticLabels = mapOf(
        "city_confirmation" to "距离近或同城，再确认对方所在城市",
        "nickname_origin" to "围绕昵称含义、取名缘由发问",
        "zodiac_probe" to "围绕星座和性格发问",
    )

    fun prompt(context: OpeningContext): String = buildString {
        append("这是${sceneLabel(context.scene)}的开场，不要求带问题或问号。")
        append("有具体动态、兴趣或签名内容时，选一个细节自然回应，可以说一点看法；确实有值得好奇的内容时才问一个小问题。")
        append("不要把每次开场都写成复述资料后反问，不连问，不查户口，不故意制造悬念。")
        append("昵称仅用于识别联系人，星座、距离和地区仅为弱背景，没有必要提及；不要硬问取名故事、确认同城、根据星座推断性格。")
        append("匹配理由是系统提示，不是对方说过的话；距离近也不证明我们住在同一城市。")
        append("如果只有昵称、星座或同城等弱资料，允许一句简短普通招呼，不必强找话题，不解释昵称。")
        append("不要编造自己的经历、位置、爱好或与对方的共同经历，不伪造熟悉感，不为了口语化堆哈哈、表情和错别字。")
        append("只输出自然简短的聊天正文，不输出分析或多个备选。")
    }

    fun fingerprint(text: String, context: OpeningContext): OpeningFingerprint {
        var clean = text.lowercase()
        // Replace identifiers before punctuation removal, including decorated nicknames.
        val nickname = context.contactName.trim().lowercase()
        if (nickname.isNotEmpty()) clean = clean.replace(nickname, "联系人")
        val plainNickname = comparable(context.contactName)
        clean = comparable(clean)
        if (plainNickname.length >= 2) clean = clean.replace(plainNickname, "联系人")
        (zodiacs + context.zodiac).filter(String::isNotBlank).forEach {
            clean = clean.replace(comparable(it), "星座")
        }
        extractRegions(context.content + " " + context.matchReason).forEach { region ->
            clean = clean.replace(comparable(region), "地区")
        }
        // City checks without a 市 suffix still normalize when the system omits a city field.
        if (isCityConfirmation(text)) {
            clean = clean.replace(
                Regex("(?:你也是|你也在|你是|你在|也是|也在|住在|来自)([\\p{IsHan}]{2,7}?)(?=的吗|的嘛|吗|嘛|呀|啊|呢|$)"),
            ) { match -> match.value.replace(match.groupValues[1], "地区") }
            clean = clean.replace(Regex("^[\\p{IsHan}]{2,7}?(?=离我|离你|离得)"), "地区")
        }
        clean = clean.replace(Regex("[0-9]+(?:[.][0-9]+)?"), "数字")
        return OpeningFingerprint(clean.take(180), tactic(text))
    }

    fun rejectionReason(
        text: String,
        context: OpeningContext,
        recent: List<OpeningFingerprint>,
    ): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return "开场为空"
        if (questionCount(clean) > 1) return "开场连续提问；保留一个自然回应，最多一个有内容的小问题"
        if (isNicknameOrigin(clean) && !supportsNicknameTopic(context.content)) {
            return "没有具体内容支撑的昵称由来提问；昵称只作为背景，不要硬聊取名故事"
        }
        if (isCityConfirmation(clean) && !supportsLocationTopic(context.content)) {
            return "把系统距离或地区提示写成了同城确认；换成普通招呼或回应真实资料"
        }
        if (isZodiacStereotype(clean)) return "不能根据星座断言对方的性格或习惯"
        if (tactic(clean) == "zodiac_probe" && !context.content.contains("星座")) {
            return "只有星座背景却硬聊性格；允许不提星座的普通开场"
        }

        val candidate = fingerprint(clean, context)
        val window = recent.takeLast(40)
        if (window.any { sameOpening(candidate.normalized, it.normalized) }) {
            return "与最近已发送的开场过于相似；换表达或切入点，不只替换昵称、地名和语气词"
        }
        if (candidate.tactic in repeatedTactics && window.any { it.tactic == candidate.tactic }) {
            return "最近已经用过${tacticLabels[candidate.tactic]}的套路；换切入方式"
        }
        return null
    }

    /** Model hints deliberately contain no normalized text or another contact's details. */
    fun recentPatternHint(recent: List<OpeningFingerprint>): String {
        val labels = recent.takeLast(40).mapNotNull { tacticLabels[it.tactic] }.distinct()
        if (labels.isEmpty()) return ""
        return "近期自己的开场已用过这些套路，本次避免重复：${labels.joinToString("；")}。不要只换词。"
    }

    private fun sceneLabel(scene: ReplyScene): String = when (scene) {
        ReplyScene.CHAT -> "当前聊天"
        ReplyScene.SOUL_MATCH -> "灵魂匹配"
        ReplyScene.QIYU -> "奇遇铃"
        ReplyScene.SQUARE -> "广场私聊"
    }

    private fun comparable(text: String): String = text.lowercase().replace(punctuation, "")

    private fun tactic(text: String): String = when {
        isCityConfirmation(text) -> "city_confirmation"
        isNicknameOrigin(text) -> "nickname_origin"
        (zodiacs.any(text::contains) || text.contains("星座")) &&
            (directQuestion(text) || isZodiacStereotype(text)) -> "zodiac_probe"
        questionCount(text) > 1 -> "question_stack"
        directQuestion(text) -> "question"
        comparable(text).length <= 14 && listOf("你好", "嗨", "打个招呼", "哈喽", "hello").any(text::contains) -> "greeting"
        else -> "statement"
    }

    private fun isNicknameOrigin(text: String): Boolean {
        val compact = comparable(text)
        return directQuestion(text) && listOf("昵称", "名字", "网名", "外号", "取名", "叫这个").any(compact::contains) &&
            listOf("故事", "由来", "含义", "意思", "怎么", "咋", "为什么", "为啥", "取的", "起的", "来源", "来历").any(compact::contains)
    }

    private fun isCityConfirmation(text: String): Boolean {
        val compact = comparable(text)
        // Activity and identity comparisons are not geographic confirmations.
        if (listOf("喜欢", "爱好", "做饭", "养猫", "养狗", "铲屎官", "打工人", "玩", "看书", "电影", "游戏", "听歌", "追剧", "熬夜", "工作", "上班", "星座", "休息", "放假", "发呆", "出差", "上学", "运动", "减肥", "跑步", "逛街", "健身", "旅行", "旅游").any(compact::contains)) return false
        if (Regex("你(?:也)?在(?:追|看|听|学|睡|吃|做|等|找|读|练|刷)").containsMatchIn(compact)) return false
        if (zodiacs.any(compact::contains)) return false
        // A colloquial confirmation can omit both a question mark and 吗.
        val casualPlaceConfirmation = Regex("你也(?:在|住在)[\\p{IsHan}]{2,4}(?:啊|呀|呢)$").containsMatchIn(compact)
        if (!directQuestion(text) && !casualPlaceConfirmation) return false
        val proximity = listOf("这么近", "那么近", "离得近", "离我近").any(compact::contains)
        val confirmsLocation = listOf("你也", "也是", "也住", "同城吗", "同城嘛", "哪里", "哪儿", "住哪", "在哪").any(compact::contains)
        val distanceCheck = proximity && (compact.contains("离我") || confirmsLocation)
        val cityCheck = compact.contains("同城") && confirmsLocation
        val bareCityOrigin = Regex("你(?:也)?是[\\p{IsHan}]{2,4}的吗$").containsMatchIn(compact)
        val explicitPlace = Regex("你也(?:在|住在)[\\p{IsHan}]{2,6}(?:市|省|县|区)(?:吗|嘛|呀|啊|呢)?$").containsMatchIn(compact)
        return distanceCheck || cityCheck || bareCityOrigin || explicitPlace || casualPlaceConfirmation
    }

    private fun supportsNicknameTopic(content: String): Boolean =
        listOf("名字", "昵称", "网名", "外号", "取名").any(content::contains) &&
            listOf("取", "起", "改", "故事", "来源", "由来", "含义").any(content::contains)

    private fun supportsLocationTopic(content: String): Boolean =
        content.isNotBlank() && listOf("搬", "定居", "旅行", "旅游", "住了", "待了", "生活", "刚到", "刚来", "故乡", "老家").any(content::contains)

    private fun isZodiacStereotype(text: String): Boolean =
        (zodiacs.any(text::contains) || (text.contains("星座") && listOf("你", "的人").any(text::contains))) &&
            listOf("肯定", "一定", "都很", "都爱", "都是", "大多", "都比较", "一般都", "通常都", "天生", "必然", "果然", "难怪", "典型", "是不是都").any(text::contains)

    private fun directQuestion(text: String): Boolean {
        if (text.any { it == '?' || it == '？' }) return true
        val compact = comparable(text)
        if (Regex("(?:吗|嘛|么|不|没)$").containsMatchIn(compact)) return true
        if (listOf("不知道", "没什么", "不管", "无论", "怎么都", "什么时候都").any(compact::startsWith)) return false
        if (Regex("(?:昵称|名字|网名|外号|取名).{0,5}(?:怎么(?:来|取|起|想|选|定)|咋(?:来|取|起)|什么(?:故事|意思|含义|来历)|啥(?:故事|意思|含义|来历)|有没有(?:故事|含义))").containsMatchIn(compact)) return true
        return Regex("^(?:那|所以|话说|对了|你|你的|这个|这|是)*?(?:为什么|为啥|怎么取|怎么起|哪里|哪儿|多大|几岁|做什么|喜欢什么|有没有|是不是|会不会|能不能)").containsMatchIn(compact)
    }

    private fun questionCount(text: String): Int {
        val explicit = Regex("[?？]+").findAll(text).count()
        val clauses = text.split(Regex("[，,。.!！？?；;\\n]+"))
            .count { it.isNotBlank() && directQuestion(it) }
        return maxOf(explicit, clauses)
    }

    private fun extractRegions(source: String): Set<String> {
        val suffixed = Regex("(?:^|[\\s，,；;：:]|来自|坐标|位于|在|共同点[：:]?)([\\p{IsHan}]{2,6}?(?:省|市|县|区))")
            .findAll(source).flatMap { match ->
                val region = match.groupValues[1]
                sequenceOf(region, region.dropLast(1))
            }.filter { it.length >= 2 }.toSet()
        val labelled = Regex("(?:共同点|同城|地区|位置|坐标|来自|城市)[：: ]+([\\p{IsHan}]{2,6})(?=[\\s，,；;。]|$)")
            .findAll(source).map { it.groupValues[1] }.toSet()
        return suffixed + labelled
    }

    private fun sameOpening(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        if (first == second) return true
        // Short, different greetings remain valid; do not equate all greetings/statements.
        if (minOf(first.length, second.length) < 10) return false
        if (minOf(first.length, second.length).toDouble() / maxOf(first.length, second.length) < 0.72) return false
        val a = first.windowed(2).toSet()
        val b = second.windowed(2).toSet()
        val unionSize = (a + b).size
        return unionSize > 0 && a.intersect(b).size.toDouble() / unionSize >= 0.72
    }
}
