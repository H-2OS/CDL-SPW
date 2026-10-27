// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

public object SevenSegment {

    public const val A: Int = 1 shl 0
    public const val B: Int = 1 shl 1
    public const val C: Int = 1 shl 2
    public const val D: Int = 1 shl 3
    public const val E: Int = 1 shl 4
    public const val F: Int = 1 shl 5
    public const val G: Int = 1 shl 6

    public const val ALL: Int = A or B or C or D or E or F or G

    public fun maskOf(ch: Char): Int = when (ch) {
        '0' -> A or B or C or D or E or F
        '1' -> B or C
        '2' -> A or B or G or E or D
        '3' -> A or B or G or C or D
        '4' -> F or G or B or C
        '5' -> A or F or G or C or D
        '6' -> A or F or G or E or C or D
        '7' -> A or B or C
        '8' -> ALL
        '9' -> A or B or C or D or F or G

        ':' -> G
        else -> -1
    }

    public fun supports(ch: Char): Boolean = maskOf(ch) >= 0

    public const val DIGIT_ASPECT: Float = 2.0f

    public const val SEGMENT_THICKNESS_RATIO: Float = 0.16f

    public const val COLON_WIDTH_RATIO: Float = 0.35f

    public const val GAP_RATIO: Float = 0.30f

    public const val MIN_SEGMENT_PX: Float = 1.5f

    public fun advanceRatio(ch: Char): Float = when (ch) {
        ':' -> COLON_WIDTH_RATIO
        else -> if (supports(ch)) 1.0f else 0.0f
    }

    public fun totalWidthRatio(text: String): Float {
        var sum = 0f
        var count = 0
        for (ch in text) {
            if (!supports(ch)) continue
            sum += advanceRatio(ch)
            count++
        }
        if (count == 0) return 0f
        return sum + GAP_RATIO * (count - 1).toFloat()
    }

    public fun fitDigitWidth(
        text: String,
        availPx: Float,

        minSegmentPx: Float = Float.NaN,
    ): Float? {
        if (!availPx.isFinite() || availPx <= 0f) return null
        val ratio = totalWidthRatio(text)
        if (!(ratio > 0f)) return null
        val w = availPx / ratio
        val floor = if (minSegmentPx.isFinite() && minSegmentPx > 0f) minSegmentPx else MIN_SEGMENT_PX
        if (w * SEGMENT_THICKNESS_RATIO < floor) return null
        return w
    }

    public fun digitHeightPx(digitWidthPx: Float): Float = digitWidthPx * DIGIT_ASPECT

    public fun segmentPolygon(
        seg: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        thicknessRefWidthPx: Float = -1f,
    ): FloatArray {

        val t = (if (thicknessRefWidthPx > 0f) thicknessRefWidthPx else w) * SEGMENT_THICKNESS_RATIO
        if (!(t > 0f) || !(w > 0f) || !(h > 0f)) return FloatArray(0)

        val half = t / 2f

        fun horizontal(y0: Float): FloatArray = floatArrayOf(
            x + half, y0,
            x + w - half, y0,
            x + w, y0 + half,
            x + w - half, y0 + t,
            x + half, y0 + t,
            x, y0 + half,
        )

        fun vertical(x0: Float, y0: Float, len: Float): FloatArray = floatArrayOf(
            x0 + half, y0,
            x0 + t, y0 + half,
            x0 + t, y0 + len - half,
            x0 + half, y0 + len,
            x0, y0 + len - half,
            x0, y0 + half,
        )
        val yMid = y + (h - t) / 2f

        val upperLen = yMid - (y + t)
        val lowerLen = (y + h - t) - (yMid + t)
        return when (seg) {
            A -> horizontal(y)
            G -> horizontal(yMid)
            D -> horizontal(y + h - t)

            F -> vertical(x, y + t, upperLen)
            B -> vertical(x + w - t, y + t, upperLen)

            E -> vertical(x, yMid + t, lowerLen)
            C -> vertical(x + w - t, yMid + t, lowerLen)
            else -> FloatArray(0)
        }
    }

    public fun glyphPolygons(
        ch: Char,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        thicknessRefWidthPx: Float = -1f,
    ): List<FloatArray> {
        val mask = maskOf(ch)
        if (mask < 0) return emptyList()
        val out = ArrayList<FloatArray>(7)
        for (seg in intArrayOf(A, B, C, D, E, F, G)) {
            if (mask and seg == 0) continue
            val poly = segmentPolygon(seg, x, y, w, h, thicknessRefWidthPx)
            if (poly.isNotEmpty()) out.add(poly)
        }
        return out
    }

    public fun textPolygons(
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,

        colonVisible: Boolean = true,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var cursor = x

        val lastSupported = text.indexOfLast { supports(it) }
        for ((i, ch) in text.withIndex()) {
            if (!supports(ch)) continue

            val cw = w * advanceRatio(ch)

            if (ch != ':' || colonVisible) {

                out.addAll(glyphPolygons(ch, cursor, y, cw, h, thicknessRefWidthPx = w))
            }
            cursor += cw + w * GAP_RATIO
        }
        return out
    }
}
