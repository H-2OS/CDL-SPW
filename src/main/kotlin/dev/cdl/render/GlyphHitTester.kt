// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Shape
import java.awt.font.TextLayout

internal class GlyphHitTester {

    fun hitTest(
        cache: RenderCache,
        layout: TextLayout,
        text: String,
        x: Float,
        y: Float,
        baselineX: Float,
        baselineY: Float,
        padding: Float = 0f,
    ): Int? {
        if (text.isEmpty()) return null

        val lx = x - baselineX
        val ly = y - baselineY

        val outline: Shape = cache.outlineFor(layout, text) ?: return null

        val hitBox = glyphHitBox(layout, padding)
        if (!hitBox.accepts(lx, ly)) return null

        return preciseIndex(layout, lx, ly, text)
    }

    private fun preciseIndex(layout: TextLayout, lx: Float, ly: Float, text: String): Int? {
        val count = layout.characterCount
        if (count <= 0) return null

        val rough = layout.hitTestChar(lx, ly).charIndex.coerceIn(0, count)

        val window = CARET_SCAN_WINDOW
        val lo = (rough - window).coerceAtLeast(0)
        val hi = (rough + window).coerceAtMost(count - 1)

        for (i in lo..hi) {
            val left = caretCenterX(layout, i, lx, ly, fallbackOnLeft = true)
            val right = caretCenterX(layout, i + 1, lx, ly, fallbackOnLeft = false)
            if (lx >= left && lx < right) return i
        }

        for (i in 0 until count) {
            val left = caretCenterX(layout, i, lx, ly, fallbackOnLeft = true)
            val right = caretCenterX(layout, i + 1, lx, ly, fallbackOnLeft = false)
            if (lx >= left && lx < right) return i
        }

        val firstLeft = caretCenterX(layout, 0, lx, ly, fallbackOnLeft = true)
        if (lx < firstLeft) return 0
        return count - 1
    }

    private fun caretCenterX(
        layout: TextLayout,
        index: Int,
        @Suppress("UNUSED_PARAMETER") lx: Float,
        @Suppress("UNUSED_PARAMETER") ly: Float,
        fallbackOnLeft: Boolean,
    ): Float {
        val clamped = index.coerceIn(0, layout.characterCount)
        val shapes = runCatching { layout.getCaretShapes(clamped) }.getOrNull()
        val shape = shapes?.firstOrNull()
            ?: return if (fallbackOnLeft) 0f else layout.advance
        val bounds = shape.bounds2D
        val center = ((bounds.minX + bounds.maxX) / 2.0).toFloat()

        return if (center.isFinite() && center >= -CARET_X_SLACK && center <= layout.advance + CARET_X_SLACK) {
            center
        } else if (fallbackOnLeft) {
            0f
        } else {
            layout.advance
        }
    }

    fun hitTestInBox(
        cache: RenderCache,
        layout: TextLayout,
        text: String,
        x: Float,
        y: Float,
        boxX: Float,
        boxY: Float,
        boxHeight: Float,
        padding: Float = 0f,
    ): Int? {
        val baselineX = boxX
        val baselineY = boxY + (boxHeight + layout.ascent - layout.descent) / 2f
        return hitTest(cache, layout, text, x, y, baselineX, baselineY, padding)
    }

    fun baselineYInBox(layout: TextLayout, boxY: Float, boxHeight: Float): Float =
        boxY + (boxHeight + layout.ascent - layout.descent) / 2f

    private companion object {

        const val CARET_SCAN_WINDOW = 2

        const val CARET_X_SLACK = 32f
    }
}
