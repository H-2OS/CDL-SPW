// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color
import java.awt.LinearGradientPaint

const val FLOW_PALETTE_COLORS = 4

fun flowGradientStops(n: Int): FloatArray? {
    if (n < 2) return null
    val out = FloatArray(n)
    val denom = (n - 1).toFloat()
    for (i in 0 until n) out[i] = i.toFloat() / denom

    for (i in 1 until n) if (!(out[i] > out[i - 1])) return null
    return out
}

fun flowGradientColors(paletteArgb: List<Int>, alpha: Int): Array<Color>? {
    if (paletteArgb.size < 2) return null
    val a = alpha.coerceIn(0, 255)
    return Array(paletteArgb.size) { i ->
        val argb = paletteArgb[i]
        Color((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, a)
    }
}

const val FLOW_PERIOD_MS = 6000L

const val FLOW_CYCLES = 3

fun flowCyclicStops(n: Int, cycles: Int = FLOW_CYCLES): FloatArray? {
    if (n < 2 || cycles < 1) return null
    val total = n * cycles
    val out = FloatArray(total + 1)
    val denom = total.toFloat()
    for (i in 0..total) out[i] = i.toFloat() / denom
    for (i in 1..total) if (!(out[i] > out[i - 1])) return null
    return out
}

fun flowCyclicStopsWeighted(shares: FloatArray, cycles: Int = FLOW_CYCLES): FloatArray? {
    val n = shares.size
    if (n < 2 || cycles < 1) return null
    var sum = 0f
    for (v in shares) {
        if (!v.isFinite() || v < 0f) return null
        sum += v
    }
    if (sum <= 0f) return null
    val cum = FloatArray(n)
    var acc = 0f
    for (i in 0 until n) {
        acc += shares[i]
        cum[i] = acc / sum
    }
    cum[n - 1] = 1f
    val total = n * cycles
    val out = FloatArray(total + 1)

    var k = 0
    out[k++] = 0f
    for (c in 0 until cycles) {
        val base = c.toFloat() / cycles
        for (i in 0 until n) {

            if (c == cycles - 1 && i == n - 1) continue
            out[k++] = base + cum[i] / cycles
        }
    }
    out[total] = 1f
    for (i in 1..total) if (!(out[i] > out[i - 1])) return null
    return out
}

fun flowCyclicColors(
    paletteArgb: List<Int>,
    alpha: Int,
    cycles: Int = FLOW_CYCLES,
): Array<Color>? {
    val n = paletteArgb.size
    if (n < 2 || cycles < 1) return null
    val a = alpha.coerceIn(0, 255)
    return Array(n * cycles + 1) { i ->
        val argb = paletteArgb[i % n]
        Color((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, a)
    }
}

fun flowGradientPaint(
    x: Double, y: Double, w: Double, h: Double,
    angleDeg: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    phase: Float = 0f,

    shares: FloatArray? = null,
): LinearGradientPaint? {

    val stops = (
        shares?.takeIf { it.size == paletteArgb.size }?.let { flowCyclicStopsWeighted(it) }
            ?: flowCyclicStops(paletteArgb.size)
        ) ?: return null
    val colors = flowCyclicColors(paletteArgb, alpha) ?: return null

    if (stops.size != colors.size) return null

    if (!w.isFinite() || !h.isFinite()) return null
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = Math.cos(rad)
    val dy = Math.sin(rad)
    val l = Math.abs(w * dx) + Math.abs(h * dy)
    if (!(l > 0.0)) return null
    val cx = x + w / 2.0
    val cy = y + h / 2.0

    val half = FLOW_CYCLES * l / 2.0

    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val shift = ph.toDouble() * l
    val ox = cx + shift * dx
    val oy = cy + shift * dy

    return LinearGradientPaint(
        (ox - half * dx).toFloat(), (oy - half * dy).toFloat(),
        (ox + half * dx).toFloat(), (oy + half * dy).toFloat(),
        stops,
        colors,
    )
}

fun flowCamoStops(
    blocks: Int,
    n: Int,
    paletteArgb: List<Int>,
): Pair<FloatArray, IntArray>? {
    if (n < 2 || blocks < 1) return null

    val total = camoTotalWidth(blocks)
    if (!(total > 0f)) return null

    val soft = CAMO_EDGE_SOFT_FRACTION.toDouble()

    val stops = FloatArray(blocks * 2 + 1)
    val argbs = IntArray(blocks * 2 + 1)
    var cum = 0f
    for (bi in 0 until blocks) {
        val w = camoBlockWidth(bi)
        val start = cum / total

        val plateauEnd = (cum + w * (1f - soft)).toFloat() / total

        val c = camoBlockColor(bi, n, paletteArgb)
        stops[bi * 2] = start
        stops[bi * 2 + 1] = plateauEnd
        argbs[bi * 2] = c
        argbs[bi * 2 + 1] = c
        cum += w
    }

    val close = blocks * 2
    stops[close] = 1f
    argbs[close] = argbs[0]

    for (i in 1 until stops.size) if (!(stops[i] > stops[i - 1])) return null
    return stops to argbs
}

fun flowCamoCyclic(
    blocks: Int,
    n: Int,
    paletteArgb: List<Int>,
    cycles: Int = FLOW_CYCLES,
): Pair<FloatArray, IntArray>? {
    if (cycles < 1) return null
    val one = flowCamoStops(blocks, n, paletteArgb) ?: return null
    val (s1, c1) = one
    val m = s1.size

    val total = m + (cycles - 1) * (m - 1)
    val stops = FloatArray(total)
    val argbs = IntArray(total)

    var idx = 0
    for (i in 0 until m) {
        stops[idx] = s1[i] / cycles
        argbs[idx] = c1[i]
        idx++
    }

    for (k in 1 until cycles) {
        for (i in 1 until m) {
            stops[idx] = (k + s1[i]) / cycles
            argbs[idx] = c1[i]
            idx++
        }
    }

    for (i in 1 until stops.size) if (!(stops[i] > stops[i - 1])) return null
    return stops to argbs
}

fun flowCamoPaint(
    x: Double, y: Double, w: Double, h: Double,
    angleDeg: Int,
    blocks: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    phase: Float = 0f,

    shares: FloatArray? = null,
): LinearGradientPaint? {
    val (stops, argbs) = flowCamoCyclic(blocks, paletteArgb.size, paletteArgb) ?: return null
    val a = alpha.coerceIn(0, 255)
    val colors = Array(argbs.size) { i ->
        val c = argbs[i]
        Color((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF, a)
    }
    if (stops.size != colors.size) return null

    if (!w.isFinite() || !h.isFinite()) return null
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = Math.cos(rad)
    val dy = Math.sin(rad)
    val l = Math.abs(w * dx) + Math.abs(h * dy)
    if (!(l > 0.0)) return null
    val cx = x + w / 2.0
    val cy = y + h / 2.0
    val half = FLOW_CYCLES * l / 2.0
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val shift = ph.toDouble() * l
    val ox = cx + shift * dx
    val oy = cy + shift * dy

    return LinearGradientPaint(
        (ox - half * dx).toFloat(), (oy - half * dy).toFloat(),
        (ox + half * dx).toFloat(), (oy + half * dy).toFloat(),
        stops,
        colors,
    )
}
