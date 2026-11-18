package com.rrajath.grove.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/**
 * Positions a popup so its bottom edge sits [gapPx] above the anchor, aligned to
 * the anchor's start edge (left in LTR, right in RTL). Opens downward instead only
 * when there isn't room above (the field is near the top of the screen). Either
 * way the popup never overlaps the anchor, and it stays clear of the IME, which
 * docks directly under a focused text field.
 */
class FlyUpPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val startX = if (layoutDirection == LayoutDirection.Rtl) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = startX.coerceIn(0, maxX)
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (above >= 0) above else anchorBounds.bottom + gapPx
        return IntOffset(x, y)
    }
}
