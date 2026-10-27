// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.image.BufferedImage

object BackdropRefraction {

    const val DISPERSION_SCALE = 20.0

    const val N_R = 0.98
    const val N_G = 1.0
    const val N_B = 1.02

    private var scratchSrc: IntArray? = null

    private var scratchOut: IntArray? = null

    const val ROUNDNESS = GlassSdf.DEFAULT_ROUNDNESS

    fun shouldRefract(thicknessPercent: Int, amountPercent: Int): Boolean =
        thicknessPercent > 0 && amountPercent > 0

    fun shouldSaturate(@Suppress("UNUSED_PARAMETER") saturationPercent: Int): Boolean = true

    public fun vibrancyContrast(v: Int): Int =
        ((v - 127.5) * 1.02 + 127.5 + 3.06).toInt().coerceIn(0, 255)

    fun thicknessPx(percent: Int): Double = percent.coerceIn(0, 100).toDouble()

    fun amountPx(percent: Int): Double = percent.coerceIn(0, 100).toDouble()

    fun dispersionFactor(percent: Int): Double =
        percent.coerceIn(0, 100) / 100.0 * DISPERSION_SCALE

    fun saturationFactor(percent: Int): Double = percent.coerceIn(0, 200) / 100.0

    fun edgeFactor(
        depthPx: Double,
        thicknessPx: Double,
    ): Double {
        if (!(thicknessPx > 0.0)) return 0.0
        if (depthPx <= 0.0) return 0.0
        if (depthPx >= thicknessPx) return 0.0
        val t = depthPx / thicknessPx

        return Math.pow(1.0 - t, PROFILE_EXPONENT)
    }

    const val PROFILE_EXPONENT = 1.35

    data class Params(
        val thicknessPx: Double = 16.0,
        val amountPx: Double = 32.0,
        val dispersionFactor: Double = 0.0,
        val saturation: Double = 1.0,
        val roundness: Double = ROUNDNESS,

        val depthEffect: Boolean = false,
    ) {

        val isIdentity: Boolean
            get() = !(thicknessPx > 0.0) || (!(amountPx > 0.0) && !depthEffect)
    }

    private class RefrField(
        val key: String,
        val m: DoubleArray,
        val nx: DoubleArray,
        val ny: DoubleArray,
        val ef: DoubleArray,
    )

    @Volatile
    private var refrField: RefrField? = null

    private fun ensureRefrField(
        w: Int, h: Int,
        cornerRadius: Double, cornerRadiusBottom: Double, roundness: Double,
        thicknessPx: Double, refracting: Boolean,
    ): RefrField {
        val key = "$w|$h|$cornerRadius|$cornerRadiusBottom|$roundness|$thicknessPx|$refracting"
        val cur = refrField
        if (cur != null && cur.key == key) return cur
        val n = w * h
        val m = DoubleArray(n)
        val nx = DoubleArray(n)
        val ny = DoubleArray(n)
        val ef = DoubleArray(n)
        val grad = DoubleArray(2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val fx = x + 0.5
                val fy = y + 0.5
                val mm = GlassSdf.of(fx, fy, w, h, cornerRadius, roundness, cornerRadiusBottom)
                m[i] = mm
                if (mm < 0.0 && refracting) {
                    GlassSdf.gradient(
                        fx, fy, w, h, cornerRadius, roundness, grad,
                        cornerRadiusBottom = cornerRadiusBottom,
                    )
                    val gl = Math.hypot(grad[0], grad[1])
                    nx[i] = if (gl > 1e-9) grad[0] / gl else 0.0
                    ny[i] = if (gl > 1e-9) grad[1] / gl else 0.0
                    ef[i] = edgeFactor(-mm, thicknessPx)
                }
            }
        }
        val f = RefrField(key, m, nx, ny, ef)
        refrField = f
        return f
    }

    fun bake(
        src: BufferedImage,
        cornerRadius: Double,
        params: Params,

        cornerRadiusBottom: Double = Double.NaN,
    ): BufferedImage {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        if (params.isIdentity) return src

        val refracting = params.thicknessPx > 0.0 && (params.amountPx > 0.0 || params.depthEffect)

        val n = w * h
        val srcPx = scratchSrc?.takeIf { it.size == n } ?: IntArray(n).also { scratchSrc = it }
        val outPx = scratchOut?.takeIf { it.size == n } ?: IntArray(n).also { scratchOut = it }
        src.getRGB(0, 0, w, h, srcPx, 0, w)
        val grad = DoubleArray(2)
        val f = params.dispersionFactor

        val sat = params.saturation

        val field = ensureRefrField(
            w, h, cornerRadius, cornerRadiusBottom, params.roundness,
            params.thicknessPx, refracting,
        )
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val fx = x + 0.5
                val fy = y + 0.5

                val m = field.m[i]
                if (m >= 0.0) {

                    outPx[i] = srcPx[i]
                    continue
                }
                var argb = srcPx[i]
                if (refracting) {
                    val nx = field.nx[i]
                    val ny = field.ny[i]

                    val ef = field.ef[i]
                    if (ef > 0.0) {

                        var ox = -nx * ef * params.amountPx
                        var oy = -ny * ef * params.amountPx

                        if (params.depthEffect) {
                            val tE = (-m / params.thicknessPx).coerceIn(0.0, 1.0)
                            val hProf = Math.sqrt((2.0 * tE - tE * tE).coerceAtLeast(0.0))
                            ox += -(fx - w * 0.5) * 0.045 * hProf
                            oy += -(fy - h * 0.5) * 0.045 * hProf
                        }

                        val edgeFade = (-m / 2.0).coerceIn(0.0, 1.0)
                        val fEdge = f * edgeFade
                        argb = if (fEdge != 0.0) {

                            val halfW = w * 0.5
                            val halfH = h * 0.5
                            val cornerW = (((fx - halfW) * (fy - halfH)) / (halfW * halfH))
                                .coerceIn(-1.0, 1.0)

                            val spanX = ox * (N_B - 1.0) * fEdge * cornerW
                            val spanY = oy * (N_B - 1.0) * fEdge * cornerW

                            val s1 = bilinear(srcPx, w, h, fx + ox + spanX, fy + oy + spanY)
                            val s2 = bilinear(srcPx, w, h, fx + ox + spanX * 0.5, fy + oy + spanY * 0.5)
                            val s3 = bilinear(srcPx, w, h, fx + ox, fy + oy)
                            val s4 = bilinear(srcPx, w, h, fx + ox - spanX * 0.5, fy + oy - spanY * 0.5)
                            val s5 = bilinear(srcPx, w, h, fx + ox - spanX, fy + oy - spanY)

                            val rC = ((s1 ushr 16 and 0xFF) + (s2 ushr 16 and 0xFF) * 0.5) / 1.5
                            val gC = ((s2 ushr 8 and 0xFF) * 0.5 + (s3 ushr 8 and 0xFF)
                                + (s4 ushr 8 and 0xFF) * 0.5) / 2.0
                            val bC = ((s4 and 0xFF) * 0.5 + (s5 and 0xFF)) / 1.5
                            (0xFF shl 24) or
                                (rC.toInt().coerceIn(0, 255) shl 16) or
                                (gC.toInt().coerceIn(0, 255) shl 8) or
                                bC.toInt().coerceIn(0, 255)
                        } else {
                            bilinear(srcPx, w, h, fx + ox, fy + oy)
                        }
                    }
                }

                argb = saturate(argb, sat)
                outPx[i] = argb
            }
        }

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, w, h, outPx, 0, w)
        return out
    }

    private fun bilinear(px: IntArray, w: Int, h: Int, fx: Double, fy: Double): Int {
        val x0f = Math.floor(fx)
        val y0f = Math.floor(fy)
        val tx = fx - x0f
        val ty = fy - y0f
        var x0 = x0f.toInt()
        var y0 = y0f.toInt()
        var x1 = x0 + 1
        var y1 = y0 + 1
        x0 = x0.coerceIn(0, w - 1); x1 = x1.coerceIn(0, w - 1)
        y0 = y0.coerceIn(0, h - 1); y1 = y1.coerceIn(0, h - 1)
        val p00 = px[y0 * w + x0]
        val p10 = px[y0 * w + x1]
        val p01 = px[y1 * w + x0]
        val p11 = px[y1 * w + x1]
        val w00 = (1 - tx) * (1 - ty)
        val w10 = tx * (1 - ty)
        val w01 = (1 - tx) * ty
        val w11 = tx * ty
        fun ch(shift: Int): Int {
            val v = ((p00 ushr shift) and 0xFF) * w00 + ((p10 ushr shift) and 0xFF) * w10 +
                ((p01 ushr shift) and 0xFF) * w01 + ((p11 ushr shift) and 0xFF) * w11
            return Math.round(v).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun saturate(argb: Int, s: Double): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b

        val nr = vibrancyContrast((luma + (r - luma) * s).toInt().coerceIn(0, 255))
        val ng = vibrancyContrast((luma + (g - luma) * s).toInt().coerceIn(0, 255))
        val nb = vibrancyContrast((luma + (b - luma) * s).toInt().coerceIn(0, 255))
        return (argb and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }
}
