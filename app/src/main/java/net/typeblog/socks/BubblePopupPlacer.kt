package net.typeblog.socks

import android.graphics.Rect

/** Shared smart placement for the full-screen floating bubble popup shells. */
data class BubblePopupPlacement(
    val side: String,
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
        fun fitsH(value: Int) = value >= panelWidth
        fun fitsV(value: Int) = value >= panelHeight
        val horizontal = listOf("right" to right, "left" to left).filter { fitsH(it.second) }
        val vertical = listOf("bottom" to bottom, "top" to top).filter { fitsV(it.second) }
        val side = when {
            horizontal.isNotEmpty() -> horizontal.maxByOrNull { it.second }!!.first
            vertical.isNotEmpty() -> vertical.maxByOrNull { it.second }!!.first
            else -> listOf("right" to right, "left" to left, "bottom" to bottom, "top" to top)
                .maxByOrNull { it.second }!!.first
        }
        var x = when (side) {
            "right" -> bx + bubbleSizePx + marginPx
            "left" -> bx - panelWidth - marginPx
            else -> bx + bubbleSizePx / 2 - panelWidth / 2
        }
        var y = when (side) {
            "bottom" -> by + bubbleSizePx + marginPx
            "top" -> by - panelHeight - marginPx
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
}
