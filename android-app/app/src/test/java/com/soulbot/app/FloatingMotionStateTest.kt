package com.soulbot.app

import org.junit.Assert.*
import org.junit.Test

class FloatingMotionStateTest {
    @Test fun aFrameTakesOnlyTheLatestOfManyMoveEvents() {
        val state = FloatingMotionState()
        state.beginGesture()
        repeat(1000) { state.queuePosition(UiPoint(it, it * 2)) }
        assertEquals(UiPoint(999, 1998), state.takePosition())
        assertNull(state.takePosition())
    }

    @Test fun idleMovesCannotQueueWorkOrMoveThePanel() {
        val state = FloatingMotionState()
        repeat(1000) { state.queuePosition(UiPoint(it, it)) }
        assertNull(state.takePosition())
        assertFalse(state.dragging)
    }

    @Test fun touchingAfterIdleInvalidatesAnOldCollapseLayoutCallback() {
        val state = FloatingMotionState()
        val oldLayout = state.invalidateLayout()
        assertTrue(state.canApplyLayout(oldLayout))
        state.beginGesture()
        assertFalse(state.canApplyLayout(oldLayout))
        state.queuePosition(UiPoint(200, 500))
        assertEquals(UiPoint(200, 500), state.takePosition())
        state.endGesture()
        assertFalse(state.canApplyLayout(oldLayout))
    }

    @Test fun latestLayoutReplacesBothOldListenerAndFallbackGeneration() {
        val state = FloatingMotionState()
        val first = state.invalidateLayout()
        val second = state.invalidateLayout()
        assertFalse(state.canApplyLayout(first))
        assertTrue(state.canApplyLayout(second))
        state.invalidateLayout()
        assertFalse(state.canApplyLayout(second))
    }

    @Test fun cancellingAGestureDiscardsAnyPendingFrame() {
        val state = FloatingMotionState()
        state.beginGesture()
        state.queuePosition(UiPoint(200, 300))
        state.endGesture()
        assertFalse(state.dragging)
        assertNull(state.takePosition())
    }

    @Test fun aNewGestureDoesNotInheritUnfinishedMoves() {
        val state = FloatingMotionState()
        state.beginGesture()
        state.queuePosition(UiPoint(800, 100))
        state.beginGesture()
        assertNull(state.takePosition())
        state.queuePosition(UiPoint(250, 600))
        assertEquals(UiPoint(250, 600), state.takePosition())
    }

    @Test fun interruptingAnExpandDefersNecessaryClampingUntilRelease() {
        val state = FloatingMotionState()
        val oldLayout = state.invalidateLayout()
        state.beginGesture(layoutPending = true)
        assertFalse(state.canApplyLayout(oldLayout))
        state.endGesture()
        assertTrue(state.takeDeferredReposition())
        assertFalse(state.takeDeferredReposition())
        val newLayout = state.invalidateLayout()
        assertTrue(state.canApplyLayout(newLayout))
        assertFalse(state.canApplyLayout(oldLayout))
    }

    @Test fun rotatingDuringDragDefersLayoutWithoutLosingTheLastMove() {
        val state = FloatingMotionState()
        state.beginGesture()
        state.queuePosition(UiPoint(200, 500))
        state.deferReposition()
        val invalidDuringTouch = state.invalidateLayout()
        assertFalse(state.canApplyLayout(invalidDuringTouch))
        assertEquals(UiPoint(200, 500), state.takePosition())
        state.endGesture()
        assertTrue(state.takeDeferredReposition())
    }
}
