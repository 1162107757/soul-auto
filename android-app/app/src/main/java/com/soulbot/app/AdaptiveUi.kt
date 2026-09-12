package com.soulbot.app

import kotlin.math.roundToInt

/** A rectangle in the accessibility service's physical screen coordinate space. */
data class UiRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isValid: Boolean get() = width > 0 && height > 0

    fun intersect(other: UiRegion): UiRegion? {
        val result = UiRegion(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        )
        return result.takeIf(UiRegion::isValid)
    }

    fun inset(horizontal: Int, vertical: Int): UiRegion {
        val safeHorizontal = horizontal.coerceIn(0, width / 3)
        val safeVertical = vertical.coerceIn(0, height / 3)
        return UiRegion(
            left + safeHorizontal,
            top + safeVertical,
            right - safeHorizontal,
            bottom - safeVertical,
        )
    }
}

data class UiPoint(val x: Int, val y: Int)

data class UiOffsetRatio(
    val horizontal: Float,
    val vertical: Float,
)

data class UiGestureLine(
    val start: UiPoint,
    val end: UiPoint,
)

enum class MessageSide { INCOMING, OUTGOING }

/**
 * Device-independent geometry used by accessibility flows.
 *
 * Business code provides accessibility-node bounds. This policy clamps every
 * fallback gesture to the active Soul window or the specific scroll container,
 * rather than treating the complete physical display as the content area.
 */
object AdaptiveUiPolicy {
    fun effectiveWindow(
        displayWidth: Int,
        displayHeight: Int,
        rootBounds: UiRegion?,
    ): UiRegion {
        val display = UiRegion(0, 0, displayWidth.coerceAtLeast(1), displayHeight.coerceAtLeast(1))
        return rootBounds
            ?.takeIf(UiRegion::isValid)
            ?.intersect(display)
            ?.takeIf { it.width >= display.width / 3 && it.height >= display.height / 3 }
            ?: display
    }

    fun clampPoint(point: UiPoint, area: UiRegion, edgeInset: Int = 1): UiPoint {
        val safeArea = area.inset(edgeInset.coerceAtLeast(0), edgeInset.coerceAtLeast(0))
        return UiPoint(
            point.x.coerceIn(safeArea.left, safeArea.right - 1),
            point.y.coerceIn(safeArea.top, safeArea.bottom - 1),
        )
    }

    fun verticalGesture(
        area: UiRegion,
        startRatio: Float,
        endRatio: Float,
        horizontalRatio: Float = 0.5f,
        edgeInset: Int = 1,
    ): UiGestureLine {
        val safeArea = area.inset(edgeInset.coerceAtLeast(0), edgeInset.coerceAtLeast(0))
        fun xAt(ratio: Float): Int =
            safeArea.left + (safeArea.width * ratio.coerceIn(0f, 1f)).toInt()
        fun yAt(ratio: Float): Int =
            safeArea.top + (safeArea.height * ratio.coerceIn(0f, 1f)).toInt()
        val x = xAt(horizontalRatio)
        return UiGestureLine(
            start = UiPoint(x, yAt(startRatio)),
            end = UiPoint(x, yAt(endRatio)),
        )
    }

    fun messageSide(
        chatArea: UiRegion,
        avatarBounds: UiRegion?,
        contentBounds: UiRegion?,
        hasReadReceipt: Boolean,
    ): MessageSide {
        if (hasReadReceipt) return MessageSide.OUTGOING
        val anchor = avatarBounds?.takeIf(UiRegion::isValid)
            ?: contentBounds?.takeIf(UiRegion::isValid)
        return if (anchor != null && anchor.centerX > chatArea.centerX) {
            MessageSide.OUTGOING
        } else {
            MessageSide.INCOMING
        }
    }

    fun voiceMenuFallback(
        voiceBounds: UiRegion,
        activeArea: UiRegion,
        offsetPx: Int,
        edgeInset: Int = 1,
    ): UiPoint {
        val y = if (voiceBounds.centerY > activeArea.centerY) {
            voiceBounds.top - offsetPx
        } else {
            voiceBounds.bottom + offsetPx
        }
        return clampPoint(UiPoint(voiceBounds.centerX, y), activeArea, edgeInset)
    }

    fun relativeOffset(
        anchor: UiRegion,
        target: UiRegion,
        activeArea: UiRegion,
    ): UiOffsetRatio? {
        if (!anchor.isValid || !target.isValid || !activeArea.isValid) return null
        return UiOffsetRatio(
            horizontal = (target.centerX - anchor.centerX).toFloat() / activeArea.width,
            vertical = (target.centerY - anchor.centerY).toFloat() / activeArea.height,
        ).takeIf { it.horizontal in -1f..1f && it.vertical in -1f..1f }
    }

    fun pointFromOffset(
        anchor: UiRegion,
        offset: UiOffsetRatio,
        activeArea: UiRegion,
        edgeInset: Int = 1,
    ): UiPoint? {
        if (!anchor.isValid || !activeArea.isValid ||
            offset.horizontal !in -1f..1f || offset.vertical !in -1f..1f
        ) return null
        return clampPoint(
            UiPoint(
                x = anchor.centerX + (offset.horizontal * activeArea.width).roundToInt(),
                y = anchor.centerY + (offset.vertical * activeArea.height).roundToInt(),
            ),
            activeArea,
            edgeInset,
        )
    }
}
