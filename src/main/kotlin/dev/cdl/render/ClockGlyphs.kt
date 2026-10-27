// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

public object ClockGlyphs {

    public const val STYLE_SEGMENT: String = "SEGMENT"

    public const val STYLE_DOT: String = "DOT"

    public const val STYLE_PIXEL: String = "PIXEL"

    public val ALL_STYLES: List<String> = listOf(STYLE_SEGMENT, STYLE_DOT, STYLE_PIXEL)

    public const val DEFAULT_STYLE: String = STYLE_SEGMENT

    public fun normalizeStyle(wire: String): String = when (wire) {
        STYLE_SEGMENT, STYLE_DOT, STYLE_PIXEL -> wire
        else -> DEFAULT_STYLE
    }

    public fun fitDigitWidth(
        style: String,
        text: String,
        availPx: Float,

        minInkPx: Float = Float.NaN,
    ): Float? =
        when (normalizeStyle(style)) {
            STYLE_DOT, STYLE_PIXEL -> DotMatrix.fitDigitWidth(style, text, availPx, minInkPx)
            else -> SevenSegment.fitDigitWidth(text, availPx, minInkPx)
        }

    public fun totalWidthRatio(style: String, text: String): Float =
        when (normalizeStyle(style)) {

            STYLE_DOT, STYLE_PIXEL -> DotMatrix.totalWidthRatio(text)
            else -> SevenSegment.totalWidthRatio(text)
        }

    public fun digitHeightPx(style: String, digitWidthPx: Float): Float =
        when (normalizeStyle(style)) {
            STYLE_DOT, STYLE_PIXEL -> DotMatrix.digitHeightPx(style, digitWidthPx)
            else -> SevenSegment.digitHeightPx(digitWidthPx)
        }

    public fun textPolygons(
        style: String,
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        colonVisible: Boolean = true,
    ): List<FloatArray> = when (normalizeStyle(style)) {
        STYLE_DOT, STYLE_PIXEL -> DotMatrix.textPolygons(style, text, x, y, w, h, colonVisible)
        else -> SevenSegment.textPolygons(text, x, y, w, h, colonVisible)
    }

    public fun centeredTextPolygons(
        style: String,
        text: String,
        boxLeft: Float,
        boxTop: Float,
        boxWidth: Float,
        boxHeight: Float,
        colonVisible: Boolean = true,

        minInkPx: Float = Float.NaN,
    ): List<FloatArray> {
        val dw = fitDigitWidth(style, text, boxWidth, minInkPx) ?: return emptyList()
        val dh = digitHeightPx(style, dw)

        val probe = textPolygons(style, text, 0f, 0f, dw, dh, colonVisible)
        if (probe.isEmpty()) return emptyList()
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (poly in probe) {
            var i = 0
            while (i + 1 < poly.size) {
                val x = poly[i]
                val y = poly[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        if (!(maxX >= minX) || !(maxY >= minY)) return emptyList()
        val inkW = maxX - minX
        val inkH = maxY - minY

        val dx = boxLeft + (boxWidth - inkW) / 2f - minX
        val dy = boxTop + (boxHeight - inkH) / 2f - minY

        return textPolygons(style, text, dx, dy, dw, dh, colonVisible)
    }
}
