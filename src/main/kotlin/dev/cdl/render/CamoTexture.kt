// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.TexturePaint
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

const val CAMO_TEXTURE_W = 256

const val CAMO_TEXTURE_H_BUCKET = 32

const val CAMO_TEXTURE_MAX_H = 256

fun camoTextureWidthFor(islandWidthPx: Int): Int {
    if (!CAMO_FIT_WIDTH) return CAMO_TEXTURE_W
    val w = islandWidthPx.coerceAtLeast(0)
    val bucketed = ((w + 127) / 128) * 128
    return bucketed.coerceIn(CAMO_TEXTURE_W, CAMO_FIT_WIDTH_MAX)
}

const val CAMO_FIT_WIDTH = true

const val CAMO_FIT_WIDTH_MAX = 1536

const val CAMO_HALF_RES_MIN_W = 512

const val CAMO_DOWNSCALE = 4

fun camoColsFor(textureWidthPx: Int): Int =
    Math.round(textureWidthPx / 25.6f).coerceAtLeast(CAMO_TEX_COLS)

const val CAMO_TEX_COLS = 10

const val CAMO_TEX_ROWS = 5

const val CAMO_CELL_SOFT = 0.55f

const val CAMO_CELL_JITTER = 1.9f

const val CAMO_CELL_DROP_RATE = 0.28f

const val CAMO_CELL_ROW_PHASE = 1.6f

const val CAMO_BLUR_FRACTION = 0.35f

const val CAMO_BLUR_PASSES = 2

const val CAMO_BLUR_AUTO = -1

fun camoHash01(seed: Int): Float {

    var h = seed
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb
    h = h xor (h ushr 16)

    return (h and 0xFFFFFF) / 16777216f
}

fun camoTextureHeightFor(islandHeightPx: Int): Int {
    val px = islandHeightPx.coerceAtLeast(1)
    val bucketed = ((px + CAMO_TEXTURE_H_BUCKET - 1) / CAMO_TEXTURE_H_BUCKET) * CAMO_TEXTURE_H_BUCKET
    return bucketed.coerceIn(CAMO_TEXTURE_H_BUCKET, CAMO_TEXTURE_MAX_H)
}

fun camoCellCenters(
    w: Int,
    h: Int,
    cols: Int,
    rows: Int,
    jitter: Float = CAMO_CELL_JITTER,
    dropRate: Float = CAMO_CELL_DROP_RATE,
): FloatArray {
    val out = FloatArray(cols * rows * 2)
    val cellW = w.toFloat() / cols
    val cellH = h.toFloat() / rows
    val drop = dropRate.coerceIn(0f, 0.9f)
    val jit = if (jitter.isFinite()) jitter else CAMO_CELL_JITTER
    var active = 0
    for (ri in 0 until rows) {
        for (ci in 0 until cols) {
            val i = ri * cols + ci

            if (drop > 0f && camoHash01(i * 8 + 5) < drop) {
                out[i * 2] = Float.NaN
                out[i * 2 + 1] = Float.NaN
                continue
            }
            val jx = (camoHash01(i * 4 + 1) - 0.5f) * jit * cellW
            val jy = (camoHash01(i * 4 + 3) - 0.5f) * jit * cellH

            val rowPhase = (camoHash01(ri * 7919 + 101) - 0.5f) * cellW * CAMO_CELL_ROW_PHASE
            out[i * 2] = (ci + 0.5f) * cellW + jx + rowPhase
            out[i * 2 + 1] = (ri + 0.5f) * cellH + jy
            active++
        }
    }

    if (active < 2) {
        for (i in 0 until minOf(2, cols * rows)) {
            val ci = i % cols
            val ri = i / cols
            out[i * 2] = (ci + 0.5f) * cellW
            out[i * 2 + 1] = (ri + 0.5f) * cellH
        }
    }
    return out
}

fun camoTextureArgb(
    w: Int,
    h: Int,
    cols: Int,
    rows: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    blurRadiusPx: Int = CAMO_BLUR_AUTO,
    jitter: Float = CAMO_CELL_JITTER,
    dropRate: Float = CAMO_CELL_DROP_RATE,

    shares: FloatArray? = null,
): IntArray? {
    if (w <= 0 || h <= 0 || cols < 1 || rows < 1) return null
    val n = paletteArgb.size
    if (n < 2) return null
    val a = alpha.coerceIn(0, 255)

    val cellCount = cols * rows

    val centers = camoCellCenters(w, h, cols, rows, jitter, dropRate)
    val cellW = w.toFloat() / cols
    val cellH = h.toFloat() / rows

    val cdf = weightedCdf(shares, n)
    val cellColors = IntArray(cellCount) { i ->
        val u = camoHash01(i * 2 + 7)
        val idx = if (cdf != null) cdfIndex(u, cdf) else (u * n).toInt().coerceIn(0, n - 1)
        paletteArgb[idx] and 0xFFFFFF
    }

    val out = IntArray(w * h)
    val halfW = w * 0.5f
    for (y in 0 until h) {
        val py = y + 0.5f
        val rowBase = y * w
        for (x in 0 until w) {
            val px = x + 0.5f
            var d1 = Float.MAX_VALUE
            var d2 = Float.MAX_VALUE
            var i1 = 0
            var i2 = 0
            for (i in 0 until cellCount) {

                if (centers[i * 2].isNaN()) continue
                var dx = px - centers[i * 2]
                if (dx < 0f) dx = -dx

                if (dx > halfW) dx = w - dx
                val dy = py - centers[i * 2 + 1]
                val d2Sq = dx * dx + dy * dy
                if (d2Sq < d1) {
                    d2 = d1; i2 = i1
                    d1 = d2Sq; i1 = i
                } else if (d2Sq < d2) {
                    d2 = d2Sq; i2 = i
                }
            }

            val dd = Math.sqrt(d2.toDouble()).toFloat() - Math.sqrt(d1.toDouble()).toFloat()
            val softPx = (CAMO_CELL_SOFT * minOf(cellW, cellH)).coerceAtLeast(0.5f)

            val t = 0.5f * (1f - (dd / (2f * softPx)).coerceIn(0f, 1f))
            val rgb = if (t <= 0f) cellColors[i1] else mixRgb(cellColors[i1], cellColors[i2], t)
            out[rowBase + x] = (a shl 24) or (rgb and 0xFFFFFF)
        }
    }

    val radius = when {
        blurRadiusPx >= 0 -> blurRadiusPx
        else -> (CAMO_BLUR_FRACTION * minOf(cellW, cellH)).toInt()
    }
    if (radius <= 0) return out
    var cur = out
    repeat(CAMO_BLUR_PASSES) { cur = boxBlurArgb(cur, w, h, radius) }
    return cur
}

private fun boxBlurArgb(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius <= 0 || w <= 0 || h <= 0 || src.size < w * h) return src

    val r = radius.coerceAtMost(maxOf(1, minOf(w, h) / 2))
    if (r <= 0) return src
    val win = 2 * r + 1

    val tmp = IntArray(w * h)
    for (y in 0 until h) {
        val base = y * w
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = src[base + ((k % w) + w) % w]
            sa += (c ushr 24) and 0xFF; sr += (c ushr 16) and 0xFF
            sg += (c ushr 8) and 0xFF; sb += c and 0xFF
        }
        for (x in 0 until w) {
            tmp[base + x] = ((sa / win) shl 24) or ((sr / win) shl 16) or
                ((sg / win) shl 8) or (sb / win)

            val co = src[base + (((x - r) % w) + w) % w]
            val ci = src[base + (((x + r + 1) % w) + w) % w]
            sa += ((ci ushr 24) and 0xFF) - ((co ushr 24) and 0xFF)
            sr += ((ci ushr 16) and 0xFF) - ((co ushr 16) and 0xFF)
            sg += ((ci ushr 8) and 0xFF) - ((co ushr 8) and 0xFF)
            sb += (ci and 0xFF) - (co and 0xFF)
        }
    }

    val dst = IntArray(w * h)
    for (x in 0 until w) {
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = tmp[k.coerceIn(0, h - 1) * w + x]
            sa += (c ushr 24) and 0xFF; sr += (c ushr 16) and 0xFF
            sg += (c ushr 8) and 0xFF; sb += c and 0xFF
        }
        for (y in 0 until h) {
            dst[y * w + x] = ((sa / win) shl 24) or ((sr / win) shl 16) or
                ((sg / win) shl 8) or (sb / win)
            val co = tmp[(y - r).coerceIn(0, h - 1) * w + x]
            val ci = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            sa += ((ci ushr 24) and 0xFF) - ((co ushr 24) and 0xFF)
            sr += ((ci ushr 16) and 0xFF) - ((co ushr 16) and 0xFF)
            sg += ((ci ushr 8) and 0xFF) - ((co ushr 8) and 0xFF)
            sb += (ci and 0xFF) - (co and 0xFF)
        }
    }
    return dst
}

internal fun mixRgb(c1: Int, c2: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    val r = (((c1 ushr 16) and 0xFF) * (1f - f) + ((c2 ushr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
    val g = (((c1 ushr 8) and 0xFF) * (1f - f) + ((c2 ushr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
    val b = ((c1 and 0xFF) * (1f - f) + (c2 and 0xFF) * f).toInt().coerceIn(0, 255)
    return (r shl 16) or (g shl 8) or b
}

private var camoCacheKey: Long = Long.MIN_VALUE

private var camoCacheImage: BufferedImage? = null

fun releaseCamoCache() {
    camoCacheKey = Long.MIN_VALUE
    camoCacheImage = null
}

private fun weightedCdf(shares: FloatArray?, n: Int): FloatArray? {
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

private fun cdfIndex(u: Float, cdf: FloatArray): Int {
    var i = 0
    while (i < cdf.size - 1 && u >= cdf[i]) i++
    return i
}

private fun camoTextureKey(
    w: Int,
    h: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    blurRadiusPx: Int,
    shares: FloatArray? = null,
): Long {
    var k = -0x340d631b7bdddcdbL
    fun mix(v: Int) {
        k = (k xor (v.toLong() and 0xFFFFFFFFL)) * 0x100000001b3L
    }
    mix(w); mix(h); mix(alpha); mix(paletteArgb.size); mix(blurRadiusPx)
    for (p in paletteArgb) mix(p)

    if (shares != null) for (v in shares) mix((v * 10000f).toInt())
    return k
}

@Synchronized
private fun camoImageFor(
    w: Int,
    h: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    blurRadiusPx: Int = CAMO_BLUR_AUTO,
    shares: FloatArray? = null,
): BufferedImage? {
    val key = camoTextureKey(w, h, paletteArgb, alpha, blurRadiusPx, shares)
    camoCacheImage?.let { if (camoCacheKey == key) return it }

    val down = if (w >= CAMO_HALF_RES_MIN_W) CAMO_DOWNSCALE else 1
    val sw = (w + down - 1) / down
    val sh = (h + down - 1) / down
    val argb = camoTextureArgb(
        sw, sh, camoColsFor(w), CAMO_TEX_ROWS, paletteArgb, alpha, blurRadiusPx,
        shares = shares,
    ) ?: return null
    val img = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, sw, sh, argb, 0, sw)
    if (down > 1) {
        val big = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = big.createGraphics()
        g2.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g2.drawImage(img, 0, 0, w, h, null)
        g2.dispose()
        camoCacheKey = key
        camoCacheImage = big
        return big
    }
    camoCacheKey = key
    camoCacheImage = img
    return img
}

fun camoTexturePaint(
    x: Double, y: Double, w: Double, h: Double,
    islandHeightPx: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    phase: Float = 0f,

    blurRadiusPx: Int = CAMO_BLUR_AUTO,

    shares: FloatArray? = null,
): TexturePaint? {
    if (!w.isFinite() || !h.isFinite()) return null
    if (!(w > 0.0) || !(h > 0.0)) return null
    if (paletteArgb.size < 2) return null
    val texH = camoTextureHeightFor(islandHeightPx)

    val texW = camoTextureWidthFor(w.toInt())
    val img = camoImageFor(texW, texH, paletteArgb, alpha, blurRadiusPx, shares) ?: return null
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val anchorX = x - ph.toDouble() * texW
    return TexturePaint(
        img,
        Rectangle2D.Double(anchorX, y, texW.toDouble(), texH.toDouble()),
    )
}
