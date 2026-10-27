// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import dev.cdl.window.coverFillScale
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.floor

object CoverBackdrop {

    const val MASK_ALPHA: Int = 180

    const val OVERSCAN: Float = 1.002f

    const val KERNEL_BOX: String = "box"

    const val KERNEL_POISSON: String = "poisson"

    private var srcRef: BufferedImage? = null
    private var keyW = -1
    private var keyH = -1
    private var keyAngle = Int.MIN_VALUE
    private var keyMask = 0

    private var keyBlur = 0
    private var keyDownsample = 1

    private var keyBlurKernel: String = KERNEL_POISSON
    private var out: BufferedImage? = null

    var cacheHits: Int = 0
        private set

    var cacheMisses: Int = 0
        private set

    fun resetForTest() {
        srcRef = null
        keyW = -1
        keyH = -1
        keyAngle = Int.MIN_VALUE
        keyMask = 0
        keyBlur = 0
        keyDownsample = 1
        keyBlurKernel = KERNEL_POISSON
        out = null
        cacheHits = 0
        cacheMisses = 0
    }

    fun baked(
        src: BufferedImage,
        targetW: Int,
        targetH: Int,
        angleDeg: Int,
        maskArgb: Int,
        blurRadius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {
        if (srcRef === src && keyW == targetW && keyH == targetH &&
            keyAngle == angleDeg && keyMask == maskArgb &&
            keyBlur == blurRadius && keyDownsample == downsample &&
            keyBlurKernel == blurKernel
        ) {
            out?.let { cacheHits++; return it }
        }
        val r = bake(src, targetW, targetH, angleDeg, maskArgb, blurRadius, downsample, blurKernel)
        srcRef = src
        keyW = targetW
        keyH = targetH
        keyAngle = angleDeg
        keyMask = maskArgb

        keyBlur = blurRadius
        keyDownsample = downsample
        keyBlurKernel = blurKernel
        out = r
        cacheMisses++
        return r
    }

    fun blurForBackdrop(
        src: BufferedImage,
        blurRadius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {
        if (blurRadius <= 0) return src
        return blurBackdrop(src, blurRadius, downsample, blurKernel)
    }

    fun bake(
        src: BufferedImage,
        targetW: Int,
        targetH: Int,
        angleDeg: Int,
        maskArgb: Int,
        blurRadius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {

        val w = targetW.coerceAtLeast(1)
        val h = targetH.coerceAtLeast(1)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val sw = src.width.toFloat()
            val sh = src.height.toFloat()

            val scale = coverFillScale(angleDeg.toFloat(), sw, sh, w.toFloat(), h.toFloat()) * OVERSCAN
            val dw = sw * scale
            val dh = sh * scale

            val kx = (dw / sw).toDouble()
            val ky = (dh / sh).toDouble()

            val t = AffineTransform()
            t.translate(w / 2.0, h / 2.0)
            t.rotate(Math.toRadians(angleDeg.toDouble()))
            t.translate(-dw / 2.0, -dh / 2.0)
            t.scale(kx, ky)
            g.drawImage(src, t, null)

            g.composite = AlphaComposite.SrcOver
            g.color = Color(maskArgb, true)
            g.fillRect(0, 0, w, h)
        } finally {
            g.dispose()
        }

        if (blurRadius <= 0) return img
        return blurBackdrop(img, blurRadius, downsample, blurKernel)
    }

    private fun blurBackdrop(
        src: BufferedImage,
        radius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {
        val w = src.width
        val h = src.height
        val ds = downsample.coerceIn(1, 8)
        val sw = (w / ds).coerceAtLeast(1)
        val sh = (h / ds).coerceAtLeast(1)

        val small = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().let { g ->
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g.drawImage(src, 0, 0, sw, sh, null)
            } finally {
                g.dispose()
            }
        }

        var a = IntArray(sw * sh)
        small.getRGB(0, 0, sw, sh, a, 0, sw)
        var b = IntArray(sw * sh)

        val rf = (radius.toDouble() / ds).coerceAtLeast(1.0)
        if (blurKernel == KERNEL_BOX) {
            val r = rf.toInt()
            repeat(3) {
                boxPassArr(a, b, sw, sh, r, true)
                boxPassArr(b, a, sw, sh, r, false)
            }
        } else {

            poissonPassArr(a, b, sw, sh, rf.toFloat())
        }

        small.setRGB(0, 0, sw, sh, a, 0, sw)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().let { g ->
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g.drawImage(small, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
        }
        return out
    }

    private fun boxPassArr(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        r: Int,
        horizontal: Boolean,
    ) {
        val n = 2 * r + 1
        if (horizontal) {
            for (y in 0 until h) {
                val row = y * w
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -r..r) {
                    val c = src[row + i.coerceIn(0, w - 1)]
                    sa += (c ushr 24) and 0xFF
                    sr += (c ushr 16) and 0xFF
                    sg += (c ushr 8) and 0xFF
                    sb += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or
                        ((sg / n) shl 8) or (sb / n)
                    val co = src[row + (x - r).coerceIn(0, w - 1)]
                    sa -= (co ushr 24) and 0xFF
                    sr -= (co ushr 16) and 0xFF
                    sg -= (co ushr 8) and 0xFF
                    sb -= co and 0xFF
                    val ci = src[row + (x + r + 1).coerceIn(0, w - 1)]
                    sa += (ci ushr 24) and 0xFF
                    sr += (ci ushr 16) and 0xFF
                    sg += (ci ushr 8) and 0xFF
                    sb += ci and 0xFF
                }
            }
        } else {
            for (x in 0 until w) {
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -r..r) {
                    val c = src[i.coerceIn(0, h - 1) * w + x]
                    sa += (c ushr 24) and 0xFF
                    sr += (c ushr 16) and 0xFF
                    sg += (c ushr 8) and 0xFF
                    sb += c and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or
                        ((sg / n) shl 8) or (sb / n)
                    val co = src[(y - r).coerceIn(0, h - 1) * w + x]
                    sa -= (co ushr 24) and 0xFF
                    sr -= (co ushr 16) and 0xFF
                    sg -= (co ushr 8) and 0xFF
                    sb -= co and 0xFF
                    val ci = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    sa += (ci ushr 24) and 0xFF
                    sr += (ci ushr 16) and 0xFF
                    sg += (ci ushr 8) and 0xFF
                    sb += ci and 0xFF
                }
            }
        }
    }

    private const val POISSON_DISK_POINTS: Int = 12

    private const val POISSON_TAPS: Int = 1 + POISSON_DISK_POINTS * 2

    private const val POISSON_INNER_RATIO: Float = 0.55f

    private const val POISSON_FULL_KERNEL: Boolean = true

    private val POISSON_DISK_12: FloatArray = floatArrayOf(
        0.926f, 0.134f,
        -0.708f, 0.592f,
        0.381f, -0.899f,
        -0.943f, -0.282f,
        0.152f, 0.976f,
        -0.284f, -0.941f,
        0.687f, -0.539f,
        -0.534f, 0.813f,
        0.976f, -0.052f,
        -0.161f, -0.261f,
        0.313f, 0.417f,
        -0.437f, -0.118f,
    )

    private fun poissonPassArr(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        radius: Float,
    ) {
        val disk = POISSON_DISK_12
        val points = POISSON_DISK_POINTS
        if (POISSON_FULL_KERNEL) {

            val inner = radius * POISSON_INNER_RATIO
            val taps = POISSON_TAPS
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {

                    val center = src[row + x]
                    var sa = (center ushr 24) and 0xFF
                    var sr = (center ushr 16) and 0xFF
                    var sg = (center ushr 8) and 0xFF
                    var sb = center and 0xFF
                    var i = 0
                    while (i < points * 2) {
                        val co = bilinearArgb(src, w, h, x + disk[i] * radius, y + disk[i + 1] * radius)
                        sa += (co ushr 24) and 0xFF
                        sr += (co ushr 16) and 0xFF
                        sg += (co ushr 8) and 0xFF
                        sb += co and 0xFF
                        val ci = bilinearArgb(src, w, h, x + disk[i] * inner, y + disk[i + 1] * inner)
                        sa += (ci ushr 24) and 0xFF
                        sr += (ci ushr 16) and 0xFF
                        sg += (ci ushr 8) and 0xFF
                        sb += ci and 0xFF
                        i += 2
                    }
                    dst[row + x] = ((sa / taps) shl 24) or ((sr / taps) shl 16) or
                        ((sg / taps) shl 8) or (sb / taps)
                }
            }
        } else {

            val taps = points
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var sa = 0
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    var i = 0
                    while (i < taps * 2) {
                        val c = bilinearArgb(src, w, h, x + disk[i] * radius, y + disk[i + 1] * radius)
                        sa += (c ushr 24) and 0xFF
                        sr += (c ushr 16) and 0xFF
                        sg += (c ushr 8) and 0xFF
                        sb += c and 0xFF
                        i += 2
                    }
                    dst[row + x] = ((sa / taps) shl 24) or ((sr / taps) shl 16) or
                        ((sg / taps) shl 8) or (sb / taps)
                }
            }
        }
    }

    private fun bilinearArgb(src: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val xa = x0.coerceIn(0, w - 1)
        val xb = (x0 + 1).coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val yb = (y0 + 1).coerceIn(0, h - 1)
        val c00 = src[ya * w + xa]
        val c10 = src[ya * w + xb]
        val c01 = src[yb * w + xa]
        val c11 = src[yb * w + xb]
        val w00 = (1f - tx) * (1f - ty)
        val w10 = tx * (1f - ty)
        val w01 = (1f - tx) * ty
        val w11 = tx * ty
        val a = (((c00 ushr 24) and 0xFF) * w00 + ((c10 ushr 24) and 0xFF) * w10 +
            ((c01 ushr 24) and 0xFF) * w01 + ((c11 ushr 24) and 0xFF) * w11).toInt()
        val r = (((c00 ushr 16) and 0xFF) * w00 + ((c10 ushr 16) and 0xFF) * w10 +
            ((c01 ushr 16) and 0xFF) * w01 + ((c11 ushr 16) and 0xFF) * w11).toInt()
        val g = (((c00 ushr 8) and 0xFF) * w00 + ((c10 ushr 8) and 0xFF) * w10 +
            ((c01 ushr 8) and 0xFF) * w01 + ((c11 ushr 8) and 0xFF) * w11).toInt()
        val b = ((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
            (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
