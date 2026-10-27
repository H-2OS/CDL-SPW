// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

fun spectrumFlowColorAt(
    baseArgb: Int,
    index: Int,
    count: Int,
    paletteArgb: List<Int>,
    phase: Float,

    shares: FloatArray? = null,
): Int {
    val a = (baseArgb ushr 24) and 0xFF

    if (a == 0) return baseArgb
    val n = paletteArgb.size

    if (n < 2 || count <= 1) return baseArgb

    val t = (index.toFloat() / (count - 1)).coerceIn(0f, 1f)

    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val u = (t + ph) % 1f

    val cdf = weightedCdfFor(shares, n)
    if (cdf != null) {
        var seg = 0
        while (seg < n - 1 && u >= cdf[seg]) seg++
        val lo = if (seg == 0) 0f else cdf[seg - 1]
        val hi = cdf[seg]
        val f = if (hi > lo) ((u - lo) / (hi - lo)).coerceIn(0f, 1f) else 0f
        val c0 = paletteArgb[seg]
        val c1 = paletteArgb[(seg + 1) % n]
        return (a shl 24) or
            (lerpChannel(c0, c1, 16, f) shl 16) or
            (lerpChannel(c0, c1, 8, f) shl 8) or
            lerpChannel(c0, c1, 0, f)
    }

    val x = u * n
    val i0 = x.toInt()
    val i1 = (i0 + 1) % n
    val f = x - i0
    val c0 = paletteArgb[i0]
    val c1 = paletteArgb[i1]
    val r = lerpChannel(c0, c1, 16, f)
    val g = lerpChannel(c0, c1, 8, f)
    val b = lerpChannel(c0, c1, 0, f)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

fun spectrumDiscreteColorAt(
    baseArgb: Int,
    index: Int,
    count: Int,
    paletteArgb: List<Int>,
    phase: Float,
    shares: FloatArray? = null,
): Int {
    val a = (baseArgb ushr 24) and 0xFF

    if (a == 0) return baseArgb
    val n = paletteArgb.size
    if (n < 2 || count <= 0) return baseArgb
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val cdf = weightedCdfFor(shares, n)
    val idx = if (cdf != null) {

        val t = ((index.toFloat() / count) + ph) % 1f
        var seg = 0
        while (seg < n - 1 && t >= cdf[seg]) seg++
        seg
    } else {

        val shift = kotlin.math.floor(ph * n + 1e-4f).toInt()
        (((index + shift) % n) + n) % n
    }
    return (a shl 24) or (paletteArgb[idx] and 0xFFFFFF)
}

private fun weightedCdfFor(shares: FloatArray?, n: Int): FloatArray? {
    if (shares == null || shares.size != n) return null
    var sum = 0f
    for (v in shares) {
        if (!v.isFinite() || v < 0f) return null
        sum += v
    }
    if (sum <= 0f) return null
    val out = FloatArray(n)
    var acc = 0f
    for (i in 0 until n) {
        acc += shares[i]
        out[i] = acc / sum
    }
    out[n - 1] = 1f
    return out
}

private fun lerpChannel(c0: Int, c1: Int, shift: Int, f: Float): Int {
    val a = (c0 ushr shift) and 0xFF
    val b = (c1 ushr shift) and 0xFF
    return (a + (b - a) * f).toInt().coerceIn(0, 255)
}

const val CAMO_BLOCK_COUNT = 14

private const val CAMO_MIN_BLOCK_FRACTION = 1f

private const val CAMO_BLOCK_WIDTH_SPREAD = 3f

const val CAMO_EDGE_SOFT_FRACTION = 0.34f

fun spectrumCamoColorAt(
    baseArgb: Int,
    index: Int,
    count: Int,
    paletteArgb: List<Int>,
    phase: Float,
): Int {
    val a = (baseArgb ushr 24) and 0xFF

    if (a == 0) return baseArgb
    val n = paletteArgb.size
    if (n < 2 || count <= 1) return baseArgb

    val t = (index.coerceIn(0, count - 1)).toFloat() / count

    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val p = (t + ph) % 1f

    val total = camoTotalWidth(CAMO_BLOCK_COUNT)

    var x = p * total
    var blockIndex = 0
    var blockWidth = CAMO_MIN_BLOCK_FRACTION
    for (bi in 0 until CAMO_BLOCK_COUNT) {
        val w = camoBlockWidth(bi)
        if (x < w) {
            blockIndex = bi
            blockWidth = w
            break
        }

        x -= w
        blockIndex = bi
        blockWidth = w
    }

    val local = (x / blockWidth).coerceIn(0f, 1f)

    val c0 = camoBlockColor(blockIndex, n, paletteArgb)
    val c1 = camoBlockColor(blockIndex + 1, n, paletteArgb)
    val cPrev = camoBlockColor(blockIndex - 1, n, paletteArgb)

    val edge = CAMO_EDGE_SOFT_FRACTION * 0.5f
    val from: Int
    val to: Int
    val f: Float
    when {

        local < edge -> {
            from = cPrev; to = c0; f = (local / edge).coerceIn(0f, 1f)
        }
        local > 1f - edge -> {

            from = c0; to = c1; f = ((local - (1f - edge)) / edge).coerceIn(0f, 1f)
        }
        else -> {

            from = c0; to = c0; f = 0f
        }
    }

    val r = lerpChannel(from, to, 16, f)
    val g = lerpChannel(from, to, 8, f)
    val b = lerpChannel(from, to, 0, f)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

fun camoBlockWidth(bi: Int): Float =
    1f + CAMO_BLOCK_WIDTH_SPREAD * camoHash01(bi * 2 + 1)

fun camoTotalWidth(count: Int): Float {
    var s = 0f
    for (bi in 0 until count) s += camoBlockWidth(bi)

    return if (s > 0f) s else 1f
}

fun camoBlockColor(bi: Int, n: Int, paletteArgb: List<Int>): Int {
    val idx = ((bi % CAMO_BLOCK_COUNT) + CAMO_BLOCK_COUNT) % CAMO_BLOCK_COUNT
    val pick = (camoHash01(idx * 2) * n).toInt().coerceIn(0, n - 1)
    return paletteArgb[pick]
}

const val SPECTRUM_FLOW_PERIOD_MS = 18000L

const val CAMO_SEGMENT_GROUP_COLS: Int = 2

const val CAMO_SEGMENT_GROUP_ROWS: Int = 2

const val CAMO_SEGMENT_ROWS: Int = 3

fun spectrumCamoBlockColorAt(
    baseArgb: Int,
    col: Int,
    seg: Int,
    cols: Int,
    segs: Int,
    paletteArgb: List<Int>,
    phase: Float,
): Int {
    val a = (baseArgb ushr 24) and 0xFF
    if (a == 0) return baseArgb
    val n = paletteArgb.size
    if (n < 2) return baseArgb
    if (cols <= 0 || segs <= 0) return baseArgb

    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f

    val gx = col.coerceIn(0, cols - 1) / CAMO_SEGMENT_GROUP_COLS
    val gy = seg.coerceIn(0, segs - 1) / CAMO_SEGMENT_GROUP_ROWS

    val groups = (cols + CAMO_SEGMENT_GROUP_COLS - 1) / CAMO_SEGMENT_GROUP_COLS
    val shift = (ph * groups).toInt()
    val seed = (gx + shift) * 73 + gy * 151
    val k = (camoHash01(seed) * n).toInt().coerceIn(0, n - 1)
    return (a shl 24) or (paletteArgb[k] and 0xFFFFFF)
}
