// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

public object DotMatrix {

    public const val COLS: Int = 5

    public const val ROWS: Int = 7

    public const val COLON_WIDTH_RATIO: Float = 0.35f

    public const val GAP_RATIO: Float = 0.30f

    public const val MIN_DOT_PX: Float = 1.2f

    public const val DOT_FILL_RATIO: Float = 0.72f

    public const val DOT_SIDES: Int = 8

    public const val PIXEL_FILL_RATIO: Float = 1.06f

    public const val PIXEL_SIDES: Int = 4

    public fun fillRatioOf(style: String): Float =
        if (style == ClockGlyphs.STYLE_PIXEL) PIXEL_FILL_RATIO else DOT_FILL_RATIO

    public fun cellSidesOf(style: String): Int =
        if (style == ClockGlyphs.STYLE_PIXEL) PIXEL_SIDES else DOT_SIDES

    private fun pack(rows: IntArray): Long {
        require(rows.size == ROWS) { "点阵字形必须恰好 $ROWS 行，实为 ${rows.size}" }
        var m = 0L
        for (r in 0 until ROWS) {
            require(rows[r] in 0..0x1F) { "第 $r 行必须是 5 位掩码，实为 ${rows[r]}" }
            m = m or (rows[r].toLong() shl (r * COLS))
        }
        return m
    }

    private val ZERO = pack(intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110))
    private val ONE = pack(intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110))
    private val TWO = pack(intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111))
    private val THREE = pack(intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b00001, 0b10001, 0b01110))
    private val FOUR = pack(intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010))
    private val FIVE = pack(intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110))
    private val SIX = pack(intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110))
    private val SEVEN = pack(intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000))
    private val EIGHT = pack(intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110))
    private val NINE = pack(intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100))

    private val COLON = pack(intArrayOf(0b0, 0b1, 0b1, 0b0, 0b1, 0b1, 0b0))

    public fun colsOf(ch: Char): Int = when (ch) {
        ':' -> 1
        else -> if (ch in '0'..'9') COLS else 0
    }

    public fun maskOf(ch: Char): Long = when (ch) {
        '0' -> ZERO
        '1' -> ONE
        '2' -> TWO
        '3' -> THREE
        '4' -> FOUR
        '5' -> FIVE
        '6' -> SIX
        '7' -> SEVEN
        '8' -> EIGHT
        '9' -> NINE
        ':' -> COLON
        else -> -1L
    }

    public fun supports(ch: Char): Boolean = maskOf(ch) >= 0L

    public fun rowBits(ch: Char, row: Int): Int {
        val m = maskOf(ch)
        if (m < 0L || row < 0 || row >= ROWS) return 0
        return ((m ushr (row * COLS)) and 0x1FL).toInt()
    }

    public fun litCount(ch: Char): Int {
        val m = maskOf(ch)
        if (m < 0L) return 0
        var n = 0
        val cells = ROWS * COLS
        for (i in 0 until cells) if ((m ushr i) and 1L != 0L) n++
        return n
    }

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
        style: String,
        text: String,
        availPx: Float,

        minDotPx: Float = Float.NaN,
    ): Float? {
        if (!availPx.isFinite() || availPx <= 0f) return null
        val ratio = totalWidthRatio(text)
        if (!(ratio > 0f)) return null
        val w = availPx / ratio

        val cell = cellPitchPx(w, style)
        if (!(cell > 0f)) return null
        val floor = if (minDotPx.isFinite() && minDotPx > 0f) minDotPx else MIN_DOT_PX
        if (cell * fillRatioOf(style) < floor) return null
        return w
    }

    public fun cellPitchPx(digitWidthPx: Float, style: String): Float {
        val denom = (COLS - 1) + fillRatioOf(style)
        if (!(denom > 0f) || !(digitWidthPx > 0f) || !digitWidthPx.isFinite()) return 0f
        return digitWidthPx / denom
    }

    public fun digitHeightPx(style: String, digitWidthPx: Float): Float {
        val fill = fillRatioOf(style)
        val denom = (COLS - 1) + fill
        if (!(denom > 0f) || !(digitWidthPx > 0f) || !digitWidthPx.isFinite()) return 0f
        return digitWidthPx * ((ROWS - 1) + fill) / denom
    }

    public fun cellPolygon(cx: Float, cy: Float, diameter: Float, sides: Int): FloatArray {
        if (!(diameter > 0f) || !cx.isFinite() || !cy.isFinite()) return FloatArray(0)
        val n = if (sides < 3) 3 else sides
        val r = diameter / 2f
        val out = FloatArray(n * 2)

        if (n == 4) {
            out[0] = cx - r; out[1] = cy - r
            out[2] = cx + r; out[3] = cy - r
            out[4] = cx + r; out[5] = cy + r
            out[6] = cx - r; out[7] = cy + r
            return out
        }

        val start = -kotlin.math.PI.toFloat() / 2f
        val step = 2f * kotlin.math.PI.toFloat() / n
        for (k in 0 until n) {
            val a = start + step * k
            out[k * 2] = cx + r * kotlin.math.cos(a)
            out[k * 2 + 1] = cy + r * kotlin.math.sin(a)
        }
        return out
    }

    public fun glyphPolygons(
        style: String,
        ch: Char,
        cx: Float,
        cy: Float,
        cellW: Float,
    ): List<FloatArray> {
        val mask = maskOf(ch)
        if (mask < 0L) return emptyList()
        if (!(cellW > 0f)) return emptyList()
        val cols = colsOf(ch)
        if (cols <= 0) return emptyList()
        val fill = fillRatioOf(style)
        val sides = cellSidesOf(style)
        val diameter = cellW * fill
        val out = ArrayList<FloatArray>(ROWS * cols)
        for (r in 0 until ROWS) {
            for (c in 0 until cols) {

                val bit = if (cols == 1) r * COLS else r * COLS + c
                if ((mask ushr bit) and 1L == 0L) continue

                val col = cols - 1 - c
                val px = cx + (col + 0.5f) * cellW
                val py = cy + (r + 0.5f) * cellW
                val poly = cellPolygon(px, py, diameter, sides)
                if (poly.isNotEmpty()) out.add(poly)
            }
        }
        return out
    }

    public fun textPolygons(
        style: String,
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        colonVisible: Boolean = true,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>()

        val cell = cellPitchPx(w, style)
        if (!(cell > 0f)) return out
        var cursor = x
        for (ch in text) {
            if (!supports(ch)) continue
            val cw = w * advanceRatio(ch)
            if (ch != ':' || colonVisible) {
                val cols = colsOf(ch)

                val gx = cursor + cw / 2f - cols * cell / 2f
                val gy = y + h / 2f - ROWS * cell / 2f
                out.addAll(glyphPolygons(style, ch, gx, gy, cell))
            }
            cursor += cw + w * GAP_RATIO
        }
        return out
    }
}
