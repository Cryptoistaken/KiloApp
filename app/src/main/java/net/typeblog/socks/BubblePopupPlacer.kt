package net.typeblog.socks

import android.graphics.Rect

/**
 * Which screen edge the popup panel was placed against.
 *
 * This was a bare [String] before. The callers branch on it with `when` to pick
 * the grow-in pivot, and with string keys a typo silently falls through to the
 * `else` branch instead of failing. An enum makes every `when` exhaustive-checked.
 */
enum class BubblePopupSide { RIGHT, LEFT, BOTTOM, TOP }

/** Shared smart placement for the full-screen floating bubble popup shells. */
data class BubblePopupPlacement(
    val side: BubblePopupSide,
    val x: Int,
    val y: Int
)

object BubblePopupPlacer {
    fun place(
        bounds: Rect,
        bubbleCenterX: Int,
        bubbleCenterY: Int,
        bubbleSizePx: Int,
        panelWidth: Int,
        panelHeight: Int,
        marginPx: Int
    ): BubblePopupPlacement {
        val bx = bubbleCenterX - bubbleSizePx / 2
        val by = bubbleCenterY - bubbleSizePx / 2
        val right = bounds.right - (bx + bubbleSizePx) - marginPx
        val left = bx - bounds.left - marginPx
        val bottom = bounds.bottom - (by + bubbleSizePx) - marginPx
        val top = by - bounds.top - marginPx
        val side = pickSide(right, left, bottom, top, panelWidth, panelHeight)
        var x = when (side) {
            BubblePopupSide.RIGHT -> bx + bubbleSizePx + marginPx
            BubblePopupSide.LEFT -> bx - panelWidth - marginPx
            else -> bx + bubbleSizePx / 2 - panelWidth / 2
        }
        var y = when (side) {
            BubblePopupSide.BOTTOM -> by + bubbleSizePx + marginPx
            BubblePopupSide.TOP -> by - panelHeight - marginPx
            else -> by + bubbleSizePx / 2 - panelHeight / 2
        }
        x = x.coerceIn(
            bounds.left + marginPx,
            (bounds.right - panelWidth - marginPx).coerceAtLeast(bounds.left + marginPx)
        )
        y = y.coerceIn(
            bounds.top + marginPx,
            (bounds.bottom - panelHeight - marginPx).coerceAtLeast(bounds.top + marginPx)
        )
        return BubblePopupPlacement(side, x, y)
    }

    /**
     * Prefer a horizontal side, then a vertical one, then whichever side has the
     * most room. Plain comparisons rather than building filtered lists of
     * Pairs: the old version allocated two lists, four Pairs, two local function
     * objects and two lambdas per call for what is branch-only integer math.
     * Ties resolve toward the earlier candidate in each group, matching the
     * previous `maxByOrNull` over `[right, left]`, `[bottom, top]` and
     * `[right, left, bottom, top]`.
     */
    private fun pickSide(
        right: Int,
        left: Int,
        bottom: Int,
        top: Int,
        panelWidth: Int,
        panelHeight: Int
    ): BubblePopupSide {
        val fitsHorizontally = right >= panelWidth || left >= panelWidth
        val fitsVertically = bottom >= panelHeight || top >= panelHeight
        return when {
            fitsHorizontally -> if (right >= left) BubblePopupSide.RIGHT else BubblePopupSide.LEFT
            fitsVertically -> if (bottom >= top) BubblePopupSide.BOTTOM else BubblePopupSide.TOP
            right >= left && right >= bottom && right >= top -> BubblePopupSide.RIGHT
            left >= bottom && left >= top -> BubblePopupSide.LEFT
            bottom >= top -> BubblePopupSide.BOTTOM
            else -> BubblePopupSide.TOP
        }
    }
}
