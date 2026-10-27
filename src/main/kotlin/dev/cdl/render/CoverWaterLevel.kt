// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.Point2D

object CoverWaterLevel {

    public const val WATER_GLOW_TAIL_RATIO: Float = 0.35f

    public const val WATER_GLOW_MIN_FILLED_PX: Float = 2f

    public const val WATER_MIN_VISIBLE_PX: Float = 0.5f

    public const val WATER_WAVE_AMPLITUDE_RATIO: Float = 0.12f

    public const val WATER_WAVE_SAMPLES: Int = 24

    public fun waterFillRect(
        islandLeft: Float,
        islandTop: Float,
        islandWidth: Float,
        islandHeight: Float,
        progress: Float,
    ): Rectangle2D.Float? {

        if (!(islandWidth > 0f) || !(islandHeight > 0f)) return null
        val p = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        val filled = islandHeight * p

        val top = islandTop + (islandHeight - filled)
        return Rectangle2D.Float(islandLeft, top, islandWidth, filled)
    }

    public fun shouldDraw(rect: Rectangle2D.Float?): Boolean =
        rect != null && rect.height >= WATER_MIN_VISIBLE_PX

    public fun featherSpanPx(filledPx: Float): Float {
        if (!(filledPx >= WATER_GLOW_MIN_FILLED_PX)) return -1f
        val tail = filledPx * WATER_GLOW_TAIL_RATIO

        if (!(tail > 0f) || tail >= filledPx) return -1f
        return tail
    }

    public fun waterFillPaint(rect: Rectangle2D.Float, accent: Color): java.awt.Paint {
        val filled = rect.height
        val tail = featherSpanPx(filled)

        if (tail < 0f) return accent

        val span = (filled - tail)
        if (!(span > 0f)) return accent
        val x = (rect.x + rect.width / 2.0).toFloat()
        return LinearGradientPaint(

            Point2D.Float(x, (rect.y + filled).toFloat()),
            Point2D.Float(x, rect.y.toFloat()),
            floatArrayOf(0f, 1f),

            arrayOf(
                Color(accent.red, accent.green, accent.blue, 0),
                accent,
            ),
        )
    }

    public fun waterHighlightPaintOfColor(
        rect: Rectangle2D.Float,
        spanPx: Float,
        alpha: Int,
        tint: Color,
    ): LinearGradientPaint? {
        if (alpha <= 0) return null

        if (!(spanPx > 0f)) return null
        val x = (rect.x + rect.width / 2.0).toFloat()
        val y = rect.y.toFloat()
        val a = alpha.coerceIn(1, 255)
        return LinearGradientPaint(

            Point2D.Float(x, y),
            Point2D.Float(x, y - spanPx),
            floatArrayOf(0f, 1f),
            arrayOf(
                Color(tint.red, tint.green, tint.blue, a),
                Color(tint.red, tint.green, tint.blue, 0),
            ),
        )
    }

    public fun waveAmplitudePx(filledPx: Float): Float {
        if (!(filledPx >= WATER_GLOW_MIN_FILLED_PX)) return 0f
        return filledPx * WATER_WAVE_AMPLITUDE_RATIO
    }

    public fun waveLineYs(
        rect: Rectangle2D.Float,
        bands: FloatArray?,
        phase: Float,
    ): FloatArray? {
        if (bands == null || bands.isEmpty()) return null
        val amp = waveAmplitudePx(rect.height)
        if (!(amp > 0f)) return null
        val baseY = rect.y.toFloat()
        val out = FloatArray(WATER_WAVE_SAMPLES)
        val n = bands.size
        val ph = if (phase.isNaN()) 0f else phase
        for (i in 0 until WATER_WAVE_SAMPLES) {
            out[i] = baseY + waveOffsetPx(i, WATER_WAVE_SAMPLES, amp, ph, bands, n)
        }
        return out
    }

    public fun waveOffsetPx(
        i: Int,
        samples: Int,
        ampPx: Float,
        phase: Float,
        bands: FloatArray,
        bandCount: Int,
    ): Float {
        if (samples <= 0 || bandCount <= 0 || bandCount != bands.size) return 0f
        val u = i.toFloat() / samples

        val angle = (2.0 * Math.PI * (u + phase)).toFloat()

        val idx = ((i.toLong() * bandCount) / samples).toInt().coerceIn(0, bandCount - 1)
        val band = bands[idx].coerceIn(0f, 1f)
        return ampPx * kotlin.math.sin(angle) * (2f * band - 1f)
    }

    public fun waveAreaPath(rect: Rectangle2D.Float, ys: FloatArray?): Path2D.Float? {
        if (ys == null || ys.isEmpty()) return null
        val left = rect.x.toFloat()
        val right = (rect.x + rect.width).toFloat()
        val bottom = (rect.y + rect.height).toFloat()
        val p = Path2D.Float()
        p.moveTo(left, ys[0])

        val step = rect.width.toFloat() / (ys.size - 1).coerceAtLeast(1)
        for (i in ys.indices) {
            p.lineTo(left + step * i, ys[i])
        }

        p.lineTo(right, bottom)
        p.lineTo(left, bottom)
        p.closePath()
        return p
    }

    public fun highlightTintFor(baseColor: Color): Color = if (isLightBase(baseColor)) {
        Color(0, 0, 0)
    } else {
        Color(255, 255, 255)
    }

    public fun isLightBase(baseColor: Color): Boolean {
        val hsb = Color.RGBtoHSB(baseColor.red, baseColor.green, baseColor.blue, null)
        return hsb[2] > 0.5f
    }

    public fun paint(
        g: Graphics2D,
        rect: Rectangle2D.Float,
        accent: Color,
        bands: FloatArray?,
        phase: Float,
        baseColor: Color,
    ) {
        if (!shouldDraw(rect)) return
        val oldPaint = g.paint

        val ys = waveLineYs(rect, bands, phase)
        val area = waveAreaPath(rect, ys)
        g.paint = waterFillPaint(rect, accent)
        if (area != null) {
            g.fill(area)
        } else {

            g.fill(rect)
        }

        val span = rect.height * 0.25f
        val tint = highlightTintFor(baseColor)
        val hl = waterHighlightPaintOfColor(rect, span, WATER_HIGHLIGHT_ALPHA, tint)
        if (hl != null) {
            g.paint = hl

            g.fill(Rectangle2D.Float(rect.x, rect.y - span, rect.width, span))
        }

        g.paint = oldPaint
    }

    public const val WATER_HIGHLIGHT_ALPHA: Int = 46

    public fun wavePhase(positionMs: Long, periodMs: Long): Float {

        return loopPhase(positionMs, periodMs)
    }

    public fun progressOf(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        if (positionMs <= 0L) return 0f
        if (positionMs >= durationMs) return 1f
        return positionMs.toFloat() / durationMs.toFloat()
    }
}
