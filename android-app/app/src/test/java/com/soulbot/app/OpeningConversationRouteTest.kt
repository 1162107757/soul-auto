package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OpeningConversationRouteTest {
    @Test fun newMatchWithoutMessagesMayOpen() {
        assertEquals(OpeningConversationAction.OPEN, OpeningConversationRoute.action(emptyList(), false))
    }

    @Test fun actualMessagesTakePriorityOverMatchingProfile() {
        val thread = listOf("out" to "昨天聊到猫", "in" to "刚带它打完疫苗", "in" to "还挺乖")
        assertEquals(OpeningConversationAction.REPLY, OpeningConversationRoute.action(thread, true))
        assertEquals(listOf("刚带它打完疫苗", "还挺乖"), OpeningConversationRoute.pendingIncoming(thread))
    }

    @Test fun answeredConversationDoesNotRestartFromOldIncoming() {
        val thread = listOf("in" to "下班了", "out" to "总算能歇会儿了")
        assertEquals(OpeningConversationAction.SKIP, OpeningConversationRoute.action(thread, true))
        assertEquals(emptyList<String>(), OpeningConversationRoute.pendingIncoming(thread))
    }

    @Test fun storedConversationWithoutReadableMessagesDoesNotGetAnotherOpener() {
        assertEquals(OpeningConversationAction.SKIP, OpeningConversationRoute.action(emptyList(), true))
    }

    @Test fun unreadVoiceMustBeResolvedByCallerBeforeRouting() {
        // This selector handles real readable content, never system matching reasons.
        assertEquals(listOf("语音转写内容"), OpeningConversationRoute.pendingIncoming(listOf("in" to "语音转写内容")))
    }
}
