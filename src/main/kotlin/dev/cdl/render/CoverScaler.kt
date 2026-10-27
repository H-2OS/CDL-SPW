// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CoverScaler {

    private const val PRE_FACTOR = 3

    private var srcRef: BufferedImage? = null
    private var dstPx = -1

    private var sharpKey = -1
    private var out: BufferedImage? = null

    var cacheHits: Int = 0
        private set

    var cacheMisses: Int = 0
        private set

    fun release() {
        srcRef = null
        dstPx = -1
        sharpKey = -1
        out = null
    }

    fun resetForTest() {
        srcRef = null
        dstPx = -1
        sharpKey = -1
        out = null
        cacheHits = 0
        cacheMisses = 0
    }

    fun squareScaled(src: BufferedImage, px: Int, sharpness: Int = 0): BufferedImage {
        val n = px.coerceAtLeast(1)
        val s = sharpness.coerceIn(0, 100)
        if (srcRef === src && dstPx == n && sharpKey == s) {
            out?.let { cacheHits++; return it }
        }
        val r = squareScaledUncached(src, n, s)
        srcRef = src
        dstPx = n
        sharpKey = s
        out = r
        cacheMisses++
        return r
    }

    fun squareScaledUncached(src: BufferedImage, px: Int, sharpness: Int = 0): BufferedImage {
        val n = px.coerceAtLeast(1)
        val s = sharpness.coerceIn(0, 100)
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2

        val square = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val g0 = square.createGraphics()
        g0.drawImage(src, 0, 0, side, side, sx, sy, sx + side, sy + side, null)
        g0.dispose()

        if (side <= n) return square

        val pre = if (side > n * PRE_FACTOR) areaAverage(square, n * PRE_FACTOR) else square
        var r = lanczos3(pre, n)

        if (s > 0) r = unsharp(r, s / 100.0)
        return r
    }

    private fun lanczos3(src: BufferedImage, n: Int): BufferedImage {
        val sw = src.width
        val sh = src.height
        val scaleX = sw.toDouble() / n
        val scaleY = sh.toDouble() / n
        val radX = 3.0 * scaleX
        val radY = 3.0 * scaleY
        val srcPx = IntArray(sw * sh)
        src.getRGB(0, 0, sw, sh, srcPx, 0, sw)

        val tA = DoubleArray(n * sh)
        val tR = DoubleArray(n * sh)
        val tG = DoubleArray(n * sh)
        val tB = DoubleArray(n * sh)
        for (dx in 0 until n) {
            val cx = (dx + 0.5) * scaleX - 0.5
            val i0 = maxOf(0, ceil(cx - radX).toInt())
            val i1 = minOf(sw - 1, floor(cx + radX).toInt())
            val w = DoubleArray(i1 - i0 + 1)
            var sum = 0.0
            for (i in i0..i1) {
                val v = lanczosKernel((i - cx) / scaleX)
                w[i - i0] = v
                sum += v
            }
            if (sum != 0.0) for (j in w.indices) w[j] /= sum
            for (y in 0 until sh) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                val row = y * sw
                for (j in w.indices) {
                    val c = srcPx[row + i0 + j]
                    val av = ((c ushr 24) and 0xFF) / 255.0
                    val ww = w[j]
                    a += av * ww
                    r += ((c shr 16) and 0xFF) * av * ww
                    g += ((c shr 8) and 0xFF) * av * ww
                    b += (c and 0xFF) * av * ww
                }
                val k = y * n + dx
                tA[k] = a; tR[k] = r; tG[k] = g; tB[k] = b
            }
        }

        val dst = IntArray(n * n)
        for (dy in 0 until n) {
            val cy = (dy + 0.5) * scaleY - 0.5
            val j0 = maxOf(0, ceil(cy - radY).toInt())
            val j1 = minOf(sh - 1, floor(cy + radY).toInt())
            val w = DoubleArray(j1 - j0 + 1)
            var sum = 0.0
            for (i in j0..j1) {
                val v = lanczosKernel((i - cy) / scaleY)
                w[i - j0] = v
                sum += v
            }
            if (sum != 0.0) for (j in w.indices) w[j] /= sum
            for (dx in 0 until n) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                for (j in w.indices) {
                    val k = (j0 + j) * n + dx
                    val ww = w[j]
                    a += tA[k] * ww; r += tR[k] * ww; g += tG[k] * ww; b += tB[k] * ww
                }
                dst[dy * n + dx] = packPremul(a, r, g, b)
            }
        }
        val out = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, n, n, dst, 0, n)
        return out
    }

    private fun unsharp(img: BufferedImage, amount: Double): BufferedImage {
        val w = img.width
        val h = img.height
        val srcPx = IntArray(w * h)
        img.getRGB(0, 0, w, h, srcPx, 0, w)
        val dst = IntArray(w * h)
        val k = doubleArrayOf(1.0, 2.0, 1.0, 2.0, 4.0, 2.0, 1.0, 2.0, 1.0)
        for (y in 0 until h) for (x in 0 until w) {
            var br = 0.0; var bg = 0.0; var bb = 0.0
            var i = 0
            for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -1..1) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val c = srcPx[yy * w + xx]
                    val kk = k[i++]
                    br += ((c shr 16) and 0xFF) * kk
                    bg += ((c shr 8) and 0xFF) * kk
                    bb += (c and 0xFF) * kk
                }
            }
            br /= 16.0; bg /= 16.0; bb /= 16.0
            val c = srcPx[y * w + x]
            fun shp(orig: Double, blur: Double) =
                (orig + amount * (orig - blur)).roundToInt().coerceIn(0, 255)
            dst[y * w + x] = ((c ushr 24) and 0xFF shl 24) or
                (shp(((c shr 16) and 0xFF).toDouble(), br) shl 16) or
                (shp(((c shr 8) and 0xFF).toDouble(), bg) shl 8) or
                shp((c and 0xFF).toDouble(), bb)
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, w, h, dst, 0, w)
        return out
    }

    private fun lanczosKernel(x: Double): Double {
        val ax = if (x < 0) -x else x
        if (ax >= 3.0) return 0.0
        return sinc(x) * sinc(x / 3.0)
    }

    private fun sinc(t: Double): Double {
        if (t > -1e-9 && t < 1e-9) return 1.0
        val p = Math.PI * t
        return kotlin.math.sin(p) / p
    }

    private fun packPremul(a: Double, r: Double, g: Double, b: Double): Int {
        if (a <= 0.0) return 0
        val up = 1.0 / a
        return ((a * 255.0).roundToInt().coerceIn(0, 255) shl 24) or
            ((r * up).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * up).roundToInt().coerceIn(0, 255) shl 8) or
            (b * up).roundToInt().coerceIn(0, 255)
    }

    private fun areaAverage(src: BufferedImage, n: Int): BufferedImage {
        val sw = src.width
        val sh = src.height
        val srcPx = IntArray(sw * sh)
        src.getRGB(0, 0, sw, sh, srcPx, 0, sw)

        val dstPx = IntArray(n * n)
        val scaleX = sw.toDouble() / n
        val scaleY = sh.toDouble() / n

        for (dy in 0 until n) {
            val y0 = dy * scaleY
            val y1 = y0 + scaleY
            val iy0 = floor(y0).toInt()
            val iy1 = min(sh, ceil(y1).toInt())
            for (dx in 0 until n) {
                val x0 = dx * scaleX
                val x1 = x0 + scaleX
                val ix0 = floor(x0).toInt()
                val ix1 = min(sw, ceil(x1).toInt())

                var aSum = 0.0
                var rSum = 0.0
                var gSum = 0.0
                var bSum = 0.0
                var wSum = 0.0
                for (yy in iy0 until iy1) {

                    val wy = min(y1, yy + 1.0) - max(y0, yy.toDouble())
                    if (wy <= 0.0) continue
                    val row = yy * sw
                    for (xx in ix0 until ix1) {
                        val wx = min(x1, xx + 1.0) - max(x0, xx.toDouble())
                        if (wx <= 0.0) continue
                        val w = wx * wy
                        val c = srcPx[row + xx]
                        val a = (c ushr 24) and 0xFF
                        aSum += a * w

                        val pa = a / 255.0
                        rSum += ((c shr 16) and 0xFF) * pa * w
                        gSum += ((c shr 8) and 0xFF) * pa * w
                        bSum += (c and 0xFF) * pa * w
                        wSum += w
                    }
                }
                if (wSum <= 0.0) continue
                val avgA = aSum / wSum
                val px = if (avgA <= 0.0) {
                    0
                } else {
                    val unPremul = 255.0 / aSum
                    (avgA.roundToInt().coerceIn(0, 255) shl 24) or
                        ((rSum * unPremul).roundToInt().coerceIn(0, 255) shl 16) or
                        ((gSum * unPremul).roundToInt().coerceIn(0, 255) shl 8) or
                        (bSum * unPremul).roundToInt().coerceIn(0, 255)
                }
                dstPx[dy * n + dx] = px
            }
        }

        val outImg = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        outImg.setRGB(0, 0, n, n, dstPx, 0, n)
        return outImg
    }
}
