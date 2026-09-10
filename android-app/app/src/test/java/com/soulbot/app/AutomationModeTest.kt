package com.soulbot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationModeTest {
    @Test
    fun planetChatModeDoesNotRunSquareTasks() {
        val mode = AutomationMode.PLANET_CHAT

        assertTrue(mode.handlesChatReplies)
        assertTrue(mode.handlesSoulMatch)
        assertFalse(mode.handlesSquareDm)
        assertTrue(mode.idleSurface == IdleSurface.SOUL_PLANET)
    }

    @Test
    fun squareDmModeStillRepliesToChatsButDoesNotMatch() {
        val mode = AutomationMode.SQUARE_DM

        assertTrue(mode.handlesSquareDm)
        assertTrue(mode.handlesChatReplies)
        assertFalse(mode.handlesSoulMatch)
        assertTrue(mode.idleSurface == IdleSurface.LOCAL_SQUARE)
    }
}
