// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.font.TextLayout

const val GLYPH_HIT_SLACK = 2f

class GlyphHitBox internal constructor(

    val left: Float,

    val top: Float,

    val right: Float,

    val bottom: Float,
) {

    val width: Float get() = right - left

    val height: Float get() = bottom - top

    fun accepts(lx: Float, ly: Float): Boolean =
        lx >= left && lx <= right && ly >= top && ly <= bottom

    override fun toString(): String =
        "GlyphHitBox(left=$left, top=$top, right=$right, bottom=$bottom)"
}

fun glyphHitBox(layout: TextLayout, padding: Float = 0f): GlyphHitBox {
    val slack = GLYPH_HIT_SLACK + padding.coerceAtLeast(0f)
    return GlyphHitBox(
        left = -slack,
        top = -layout.ascent - slack,
        right = layout.advance + slack,
        bottom = layout.descent + slack,
    )
}

fun glyphHitTest(
    layout: TextLayout,
    text: String,
    x: Float,
    y: Float,
    baselineX: Float,
    baselineY: Float,
    padding: Float = 0f,
): Int? = GlyphHitTester().hitTest(
    RenderCache(), layout, text, x, y, baselineX, baselineY, padding,
)
