// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

object DissolveMask {

    private var cached: DissolveMaskImage? = null
    private var cachedKey: String? = null

    private const val W_ORTHO = 3.0
    private const val W_DIAG = 4.0

    fun get(
        widthPx: Int,
        heightPx: Int,
        bandPx: Float,
        topRadiusPx: Float,
        bottomRadiusPx: Float,
        cornerStyle: String,
        cornerN: Int,
        subpixelXPx: Float = 0f,
        subpixelYPx: Float = 0f,
    ): DissolveMaskImage? {
        if (widthPx <= 0 || heightPx <= 0) return null
        if (!(bandPx > 0f) || !bandPx.isFinite()) return null

        val sx = if (subpixelXPx.isFinite()) subpixelXPx - floor(subpixelXPx) else 0f
        val sy = if (subpixelYPx.isFinite()) subpixelYPx - floor(subpixelYPx) else 0f
        val key = "$widthPx|$heightPx|$bandPx|$topRadiusPx|$bottomRadiusPx|" +
            "$cornerStyle|$cornerN|$sx|$sy"
        cached?.let { if (cachedKey == key) return it }

        val band = bandPx.coerceAtMost(heightPx / 2f)
        if (!(band > 0f)) return null

        val pad = (ceil(band).toInt() + 2).coerceAtLeast(2)
        val pw = widthPx + pad * 2
        val ph = heightPx + pad * 2

        val cov = BufferedImage(pw, ph, BufferedImage.TYPE_BYTE_GRAY)
        val g = cov.createGraphics()
        try {

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE

            g.translate(pad + sx.toDouble(), pad + sy.toDouble())
            g.fill(
                islandShapeOf(
                    width = widthPx.toFloat(),
                    height = heightPx.toFloat(),
                    topRadiusPx = topRadiusPx,
                    bottomRadiusPx = bottomRadiusPx,
                    cornerStyle = cornerStyle,
                    cornerN = cornerN,
                ),
            )
        } finally {
            g.dispose()
        }

        val inf = 1e9
        val d = DoubleArray(pw * ph)
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                val inside = (cov.getRGB(x, y) and 0xFF) > 127
                d[y * pw + x] = if (inside) inf else 0.0
            }
        }
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                val i = y * pw + x
                var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + W_ORTHO)
                if (y > 0) v = min(v, d[i - pw] + W_ORTHO)
                if (x > 0 && y > 0) v = min(v, d[i - pw - 1] + W_DIAG)
                if (x < pw - 1 && y > 0) v = min(v, d[i - pw + 1] + W_DIAG)
                d[i] = v
            }
        }
        for (y in ph - 1 downTo 0) {
            for (x in pw - 1 downTo 0) {
                val i = y * pw + x
                var v = d[i]
                if (x < pw - 1) v = min(v, d[i + 1] + W_ORTHO)
                if (y < ph - 1) v = min(v, d[i + pw] + W_ORTHO)
                if (x < pw - 1 && y < ph - 1) v = min(v, d[i + pw + 1] + W_DIAG)
                if (x > 0 && y < ph - 1) v = min(v, d[i + pw - 1] + W_DIAG)
                d[i] = v
            }
        }

        val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
        val raster = img.raster
        for (y in 0 until ph) {
            for (x in 0 until pw) {

                val dist = (d[y * pw + x] / W_ORTHO - 0.5).coerceAtLeast(0.0)
                val a = ((dist / band).coerceIn(0.0, 1.0) * 255.0).toInt()
                raster.setSample(x, y, 3, a)
            }
        }
        val result = DissolveMaskImage(img, pad)
        cached = result
        cachedKey = key
        return result
    }

}

class DissolveMaskImage(

    val image: BufferedImage,

    val padPx: Int,
)
