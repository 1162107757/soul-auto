package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveUiPolicyTest {
    @Test
    fun effectiveWindowUsesVisibleRootAndClampsItToDisplay() {
        assertEquals(
            UiRegion(0, 90, 1080, 2280),
            AdaptiveUiPolicy.effectiveWindow(
                1080,
                2400,
                UiRegion(-20, 90, 1100, 2280),
            ),
        )
    }

    @Test
    fun invalidSmallRootFallsBackToCompleteDisplay() {
        assertEquals(
            UiRegion(0, 0, 720, 1600),
            AdaptiveUiPolicy.effectiveWindow(720, 1600, UiRegion(10, 10, 50, 50)),
        )
    }

    @Test
    fun verticalGestureUsesContainerInsteadOfWholeDisplay() {
        val line = AdaptiveUiPolicy.verticalGesture(
            area = UiRegion(40, 300, 1040, 1900),
            startRatio = 0.75f,
            endRatio = 0.25f,
            edgeInset = 0,
        )

        assertEquals(UiPoint(540, 1500), line.start)
        assertEquals(UiPoint(540, 700), line.end)
    }

    @Test
    fun messageDirectionUsesChatAreaCenter() {
        val chat = UiRegion(100, 200, 900, 1800)

        assertEquals(
            MessageSide.INCOMING,
            AdaptiveUiPolicy.messageSide(chat, UiRegion(120, 400, 200, 480), null, false),
        )
        assertEquals(
            MessageSide.OUTGOING,
            AdaptiveUiPolicy.messageSide(chat, UiRegion(800, 400, 880, 480), null, false),
        )
    }

    @Test
    fun receiptWinsWhenBubbleTextCrossesCenter() {
        assertEquals(
            MessageSide.OUTGOING,
            AdaptiveUiPolicy.messageSide(
                UiRegion(0, 0, 1000, 2000),
                null,
                UiRegion(200, 400, 800, 500),
                true,
            ),
        )
    }

    @Test
    fun voiceMenuFallbackIsClampedInsideActiveWindow() {
        val point = AdaptiveUiPolicy.voiceMenuFallback(
            voiceBounds = UiRegion(100, 60, 500, 140),
            activeArea = UiRegion(0, 80, 720, 1500),
            offsetPx = 56,
            edgeInset = 8,
        )

        assertEquals(UiPoint(300, 196), point)
    }

    @Test
    fun learnedRelativeOffsetScalesToAnotherViewport() {
        val learned = AdaptiveUiPolicy.relativeOffset(
            anchor = UiRegion(200, 1200, 600, 1300),
            target = UiRegion(300, 1060, 500, 1140),
            activeArea = UiRegion(0, 0, 1080, 2400),
        )!!

        assertEquals(
            UiPoint(500, 1360),
            AdaptiveUiPolicy.pointFromOffset(
                anchor = UiRegion(250, 1500, 750, 1620),
                offset = learned,
                activeArea = UiRegion(0, 0, 1440, 3200),
                edgeInset = 0,
            ),
        )
    }
}
