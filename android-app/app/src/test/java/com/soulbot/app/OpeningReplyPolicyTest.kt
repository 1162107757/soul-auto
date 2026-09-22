package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningReplyPolicyTest {
    private val nameOnly = OpeningContext(ReplyScene.SOUL_MATCH, contactName = "半盏清酒", zodiac = "双子座")

    @Test fun acceptsOrdinaryGreetingWithOnlyWeakProfileFields() {
        assertNull(OpeningReplyPolicy.rejectionReason("嗨，来打个招呼", nameOnly, emptyList()))
        assertTrue(OpeningReplyPolicy.prompt(nameOnly).contains("不要求带问题"))
        assertTrue(OpeningReplyPolicy.prompt(nameOnly).contains("系统提示，不是对方说过的话"))
    }

    @Test fun doesNotForceAQuestionOnAConcreteReaction() {
        val context = nameOnly.copy(content = "喜欢做饭，不喜欢洗碗")
        assertNull(OpeningReplyPolicy.rejectionReason("最烦吃饱以后还得面对一池子碗", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("你最喜欢做什么菜？", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("你也是喜欢做饭的吗？", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("你也是打工人吗？", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("你也是铲屎官吗？", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("你也在追这部剧吗？", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("同城有个展，你想去看看吗？", context, emptyList()))
    }

    @Test fun blocksUnsupportedNameAndCityInterviews() {
        assertNotNull(OpeningReplyPolicy.rejectionReason("你的名字有什么故事吗？", nameOnly, emptyList()))
        val city = OpeningContext(ReplyScene.QIYU, matchReason = "距离很近，绵阳市")
        assertNotNull(OpeningReplyPolicy.rejectionReason("这么近，你也是绵阳的吗？", city, emptyList()))
        assertNotNull(OpeningReplyPolicy.rejectionReason("原来同城呀，你也在成都？", city, emptyList()))
        assertNotNull(OpeningReplyPolicy.rejectionReason("你也是绵阳的吗？", city, emptyList()))
        assertNotNull(OpeningReplyPolicy.rejectionReason("成都离我这么近？", city, emptyList()))
    }

    @Test fun cityTacticRemainsTheSameAfterChangingPlaces() {
        val first = OpeningReplyPolicy.fingerprint("这么近，你也是绵阳的吗？", nameOnly)
        val next = OpeningReplyPolicy.fingerprint("原来同城呀，你也在成都？", nameOnly)
        assertEquals("city_confirmation", first.tactic)
        assertEquals(first.tactic, next.tactic)
        assertFalse(first.normalized.contains("绵阳"))
        assertFalse(next.normalized.contains("成都"))
    }

    @Test fun catchesNicknameQuestionsWithoutQuestionMarks() {
        listOf("你这名字怎么来的呀", "清酒这名字怎么来的", "你这昵称啥意思", "这网名咋取的").forEach { text ->
            assertNotNull(text, OpeningReplyPolicy.rejectionReason(text, nameOnly, emptyList()))
            assertEquals(text, "nickname_origin", OpeningReplyPolicy.fingerprint(text, nameOnly).tactic)
        }
        assertNull(OpeningReplyPolicy.rejectionReason("这名字挺有意思", nameOnly, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("这名字怎么听都挺顺口", nameOnly, emptyList()))
    }

    @Test fun catchesCasualCityConfirmationsButNotActivities() {
        listOf("你是成都的吗", "你也在成都啊", "你也在绵阳呀").forEach { text ->
            assertNotNull(text, OpeningReplyPolicy.rejectionReason(text, nameOnly, emptyList()))
            assertEquals(text, "city_confirmation", OpeningReplyPolicy.fingerprint(text, nameOnly).tactic)
        }
        assertFalse(OpeningReplyPolicy.fingerprint("你是成都的吗", nameOnly).normalized.contains("成都"))
        assertFalse(OpeningReplyPolicy.fingerprint("你也在成都啊", nameOnly).normalized.contains("成都"))
        assertNull(OpeningReplyPolicy.rejectionReason("你也在休息啊", nameOnly, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("你也在追这部剧啊", nameOnly, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("你也是铲屎官啊", nameOnly, emptyList()))
    }

    @Test fun changingNicknamesDoesNotCreateANewOpening() {
        val first = OpeningReplyPolicy.fingerprint("半盏清酒，你的名字有什么故事吗？", nameOnly)
        val next = OpeningReplyPolicy.fingerprint("晚风，你的名字有什么故事吗？", nameOnly.copy(contactName = "晚风"))
        assertEquals(first.normalized, next.normalized)
        assertEquals("nickname_origin", next.tactic)
        assertFalse(first.normalized.contains("半盏清酒"))
    }

    @Test fun stripsIdentifiablePlacesFromDistanceChecksAndLabelledContext() {
        val distance = OpeningReplyPolicy.fingerprint("成都离我这么近？", nameOnly)
        assertFalse(distance.normalized.contains("成都"))
        val labelled = OpeningReplyPolicy.fingerprint("四川的天气也太多变了", nameOnly.copy(matchReason = "共同点：四川"))
        assertFalse(labelled.normalized.contains("四川"))
    }

    @Test fun allowsNameTopicWhenTheirActualContentIntroducesIt() {
        val context = nameOnly.copy(content = "今天把昵称改成了小时候的外号")
        assertNull(OpeningReplyPolicy.rejectionReason("这个外号是怎么取的？", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("这个名字有什么故事吗？", context, emptyList()))
    }

    @Test fun preventsZodiacStereotypesAndQuestionStacks() {
        assertNotNull(OpeningReplyPolicy.rejectionReason("双子座肯定很善变", nameOnly, emptyList()))
        assertNotNull(OpeningReplyPolicy.rejectionReason("你多大了？住哪里？", nameOnly, emptyList()))
        assertNotNull(OpeningReplyPolicy.rejectionReason("你哪里人，做什么工作，几岁了", nameOnly, emptyList()))
    }

    @Test fun statementsContainingQuestionWordsAreNotQuestionStacks() {
        val context = nameOnly.copy(content = "不知道吃什么，也不知道怎么做饭")
        assertNull(OpeningReplyPolicy.rejectionReason("不知道吃什么，也不知道怎么做，这纠结太真实了", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("没什么比吃饱了还要洗碗更烦的了", context, emptyList()))
        assertNull(OpeningReplyPolicy.rejectionReason("星座都是娱乐，看看就好", context, emptyList()))
    }

    @Test fun differentStatementsAndGreetingsDoNotExhaustAllTactics() {
        val prior = listOf(OpeningReplyPolicy.fingerprint("嗨，来打个招呼", nameOnly))
        assertNull(OpeningReplyPolicy.rejectionReason("哈喽", nameOnly, prior))
        assertNull(OpeningReplyPolicy.rejectionReason("这瓶可以进避雷名单了", nameOnly, prior))
        assertNotNull(OpeningReplyPolicy.rejectionReason("嗨，来打个招呼", nameOnly, prior))
        assertNotEquals(OpeningReplyPolicy.fingerprint("哈喽", nameOnly).normalized, prior.first().normalized)
    }

    @Test fun usesOnlyTheLastFortySentOpenings() {
        val old = OpeningReplyPolicy.fingerprint("嗨，来打个招呼", nameOnly)
        val recent = listOf(old) + (1..40).map { OpeningFingerprint("别的内容$it", "statement") }
        assertNull(OpeningReplyPolicy.rejectionReason("嗨，来打个招呼", nameOnly, recent))
    }

    @Test fun modelHintContainsOnlyLabelsNeverOtherContactsText() {
        val recent = listOf(OpeningFingerprint("李小明住在绵阳吗", "city_confirmation"))
        val hint = OpeningReplyPolicy.recentPatternHint(recent)
        assertTrue(hint.contains("同城"))
        assertFalse(hint.contains("李小明"))
        assertFalse(hint.contains("绵阳"))
        assertEquals("", OpeningReplyPolicy.recentPatternHint(listOf(OpeningFingerprint("秘密内容", "statement"))))
    }
}
