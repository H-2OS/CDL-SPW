// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object SpectralPainter {

    enum class Style {

        BARS,

        WAVE,
    }

    class SpectrumCache {

        internal var displayed: FloatArray = EMPTY

        internal var lastNanos: Long = 0L

        internal var lastSignature: Int = 0

        internal var peaks: FloatArray = EMPTY

        internal companion object {
            val EMPTY = FloatArray(0)
        }
    }

    fun paint(
        g: Graphics2D,
        cache: RenderCache,
        bands: FloatArray,
        bounds: Rectangle,
        color: Int,
        state: SpectrumCache,
        style: Style = Style.BARS,
        nowNanos: Long = 0L,
        playing: Boolean = true,
        paintOverride: Paint? = null,
        bounceScale: Float = 1f,
        peakHold: Boolean = false,
        progressFraction: Float = NO_PROGRESS,
        progressPaintOverride: Paint? = null,

        paintForBar: ((topY: Float, bottomY: Float, cornerRadiusPx: Float, baseArgb: Int) -> Paint)? = null,

        fadeMaskForBar: ((
            topY: Float, bottomY: Float, baseArgb: Int, cornerRadiusPx: Float,
        ) -> Paint)? = null,

        progressColor: Int? = null,

        argbForBar: ((index: Int, count: Int, value: Float, base: Int) -> Int)? = null,

        argbForBarSegment: ((index: Int, count: Int, segIndex: Int, segCount: Int, base: Int) -> Int)? = null,

        polar: SpectrumPolarSpec? = null,

        polarLine: Boolean = false,

        fadeTop: Boolean = true,

        opacity: Float = 1f,

        symmetric: Boolean = false,

        symmetryCenterY: Float = Float.NaN,

        barPitchPx: Float = SPECTRUM_BAR_PITCH_PX,

        forceBarCount: Int = 0,

        barCorner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,

        barShapeRatio: Float = 1f,
    ) {
        if (bands.isEmpty()) return
        if (bounds.width <= 0 || bounds.height <= 0) return

        val displayed = smooth(state, bands, bounds, style, nowNanos, playing, peakHold)

        if (!playing && isSilent(displayed)) return

        val oldPaint = g.paint
        val oldStroke = g.stroke

        val oldComposite = if (opacity < 1f) g.composite else null
        try {
            if (opacity < 1f) {
                g.composite = java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER,
                    opacity.coerceIn(0f, 1f),
                )
            }
            if (paintOverride != null) g.paint = paintOverride else g.color = cache.color(color)

            val axisY = if (symmetryCenterY.isNaN()) {
                bounds.y + bounds.height / 2f
            } else {
                symmetryCenterY
            }
            when (style) {

                Style.BARS -> paintBars(
                    g, displayed, bounds, bounceScale, symmetric, axisY, barPitchPx,
                    forceBarCount = forceBarCount,
                    fillArgb = color, tipFade = fadeTop, brushes = tipFadeBrushes,
                    paintForBar = paintForBar, peaks = state.peaks,
                    argbForBarSegment = argbForBarSegment,

                    argbForBar = argbForBar,
                    fadeMaskForBar = fadeMaskForBar,
                    polar = polar,

                    polarLine = polarLine,
                    barCorner = barCorner,
                    barShapeRatio = barShapeRatio,
                )
                Style.WAVE -> paintWave(g, cache, displayed, bounds, bounceScale)
            }

            val played = progressColor ?: playedArgbFrom(progressPaintOverride)
            if (progressFraction > 0f && progressFraction <= 1f &&
                (played != null || progressPaintOverride != null)
            ) {
                val playedWidth = (bounds.width * progressFraction).toInt()
                    .coerceIn(0, bounds.width)
                if (playedWidth > 0) {
                    val oldClip = g.clip
                    try {
                        g.clip = Rectangle(bounds.x, bounds.y, playedWidth, bounds.height)

                        if (played == null) {
                            g.paint = progressPaintOverride
                        } else if (!fadeTop) {
                            g.color = cache.color(played)
                        }
                        when (style) {

                            Style.BARS -> paintBars(
                                g, displayed, bounds, bounceScale, symmetric, axisY, barPitchPx,

                                forceBarCount = forceBarCount,

                                fillArgb = played ?: color,
                                tipFade = fadeTop && played != null,
                                brushes = tipFadeBrushes,
                                paintForBar = if (played != null) null else paintForBar,
                                argbForBarSegment = argbForBarSegment,
                                peaks = state.peaks,
                                barCorner = barCorner,
                                barShapeRatio = barShapeRatio,

                                argbForBar = argbForBar,
                                fadeMaskForBar = fadeMaskForBar,
                                polar = polar,

                                polarLine = polarLine,
                            )
                            Style.WAVE -> paintWave(g, cache, displayed, bounds, bounceScale)
                        }
                    } finally {
                        g.clip = oldClip
                    }
                }
            }
        } finally {
            g.paint = oldPaint
            g.stroke = oldStroke

            if (oldComposite != null) g.composite = oldComposite
        }
    }

    internal fun isSilent(displayed: FloatArray): Boolean {
        for (v in displayed) if (v > SILENT_EPSILON) return false
        return true
    }

    internal fun isSilent(state: SpectrumCache): Boolean =
        isSilent(state.displayed) && isSilent(state.peaks)

    private fun smooth(
        state: SpectrumCache,
        bands: FloatArray,
        bounds: Rectangle,
        style: Style,
        nowNanos: Long,
        playing: Boolean,
        peakHold: Boolean,
    ): FloatArray {
        val signature = (bands.size shl 4) or style.ordinal

        val firstTime = state.displayed.size != bands.size

        if (firstTime) {

            state.displayed = FloatArray(bands.size)
            state.lastSignature = signature

            state.lastNanos = nowNanos
            return state.displayed
        }
        if (state.lastSignature != signature) {

            state.displayed = FloatArray(bands.size) { bands[it].coerceIn(0f, 1f) }
            state.lastSignature = signature
            state.lastNanos = nowNanos
            return state.displayed
        }

        val dt = if (state.lastNanos == 0L || nowNanos == 0L || nowNanos <= state.lastNanos) {

            DEFAULT_FRAME_SECONDS
        } else {

            ((nowNanos - state.lastNanos).toDouble() / 1_000_000_000.0).coerceAtMost(MAX_DT_SECONDS)
        }
        state.lastNanos = nowNanos

        val displayed = state.displayed

        if (!playing) {
            val factor = Math.exp(-dt / PAUSED_RELEASE_TAU_SECONDS)
            for (i in displayed.indices) {
                displayed[i] = (displayed[i] * factor).toFloat()
            }

            updatePeaks(state, displayed, dt, peakHold, playing = false)
            return displayed
        }

        for (i in displayed.indices) {
            val target = bands[i].coerceIn(0f, 1f)
            val current = displayed[i]
            if (target > current) {

                val factor = Math.exp(-dt / ATTACK_TAU_SECONDS)
                displayed[i] = (current + (target - current) * (1.0 - factor)).toFloat()
            } else {

                val factor = Math.exp(-dt / RELEASE_TAU_SECONDS)
                displayed[i] = (current + (target - current) * (1.0 - factor)).toFloat()
            }
        }
        updatePeaks(state, displayed, dt, peakHold, playing = true)
        return displayed
    }

    private fun updatePeaks(
        state: SpectrumCache,
        displayed: FloatArray,
        dt: Double,
        enabled: Boolean,
        playing: Boolean,
    ) {
        if (!enabled) {
            if (state.peaks.isNotEmpty()) state.peaks = SpectrumCache.EMPTY
            return
        }
        if (state.peaks.size != displayed.size) {
            state.peaks = FloatArray(displayed.size) { displayed[it].coerceIn(0f, 1f) }
            return
        }
        val perSecond = if (playing) PEAK_FALL_PER_SECOND else PAUSED_PEAK_FALL_PER_SECOND
        val fall = (perSecond * dt).toFloat()
        for (i in displayed.indices) {

            state.peaks[i] = maxOf(state.peaks[i] - fall, displayed[i].coerceIn(0f, 1f))
        }
    }

    private fun applyFadeMask(g: Graphics2D, shape: Shape, mask: Paint) {

        val oldComposite = g.composite
        val oldPaint = g.paint
        try {
            g.composite = AlphaComposite.DstIn
            g.paint = mask
            g.fill(shape)
        } finally {
            g.composite = oldComposite
            g.paint = oldPaint
        }
    }

    private fun paintBars(
        g: Graphics2D,
        bands: FloatArray,
        bounds: Rectangle,
        bounceScale: Float,
        symmetric: Boolean = false,
        axisY: Float = Float.NaN,
        barPitchPx: Float = SPECTRUM_BAR_PITCH_PX,

        forceBarCount: Int = 0,
        fillArgb: Int,
        tipFade: Boolean,
        brushes: SpectrumTipFadeBrushCache,

        paintForBar: ((topY: Float, bottomY: Float, cornerRadiusPx: Float, baseArgb: Int) -> Paint)? = null,

        fadeMaskForBar: ((
            topY: Float, bottomY: Float, baseArgb: Int, cornerRadiusPx: Float,
        ) -> Paint)? = null,
        peaks: FloatArray = SpectrumCache.EMPTY,

        barCorner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,

        argbForBar: ((index: Int, count: Int, value: Float, base: Int) -> Int)? = null,

        argbForBarSegment: ((index: Int, count: Int, segIndex: Int, segCount: Int, base: Int) -> Int)? = null,

        polar: SpectrumPolarSpec? = null,

        polarLine: Boolean = false,

        barShapeRatio: Float = 1f,
    ) {

        val count = spectrumBarCountFor(bounds.width, barPitchPx, forceBarCount)

        val values = resampleBands(bands, count, barScratch(count))

        var peak = 0f
        for (i in 0 until count) {
            if (values[i] > peak) peak = values[i]
        }
        if (peak <= SILENCE_PEAK_EPS) return

        val total = bounds.width.toFloat()

        val slot = total / count

        val solid = isSolidSpectrumPitch(barPitchPx)

        val (gap, barWidth) = if (polarLine && !solid) {
            polarLineBarSlot(slot)
        } else {
            spectrumBarSlot(slot, solid)
        }

        val cornerCapPx = barWidth / 2f
        val arc = spectrumBarCornerPx(barWidth, cornerCapPx, solid, barCorner) * barShapeRatio

        val polarInnerR = polar?.innerRadius ?: 0f
        val polarOuterR = polar?.maxLength ?: 0f

        val x0 = bounds.x.toFloat()

        val polarAxis = polar?.let { polarSymmetricAxisY(it) }
        val axis = polarAxis ?: (if (axisY.isNaN()) bounds.y + bounds.height / 2f else axisY)

        val topEdge = if (polar != null) polar.cy - polarOuterR else bounds.y.toFloat()
        val bottomEdge = if (polar != null) polar.cy - polarInnerR else (bounds.y + bounds.height).toFloat()
        val halfSpan = if (polar != null) {

            val span = (polar.maxLength - polarInnerR).coerceAtLeast(1f)

            if (symmetric) polarSymmetricHalfLengthPx(polar, 1f) else span
        } else if (symmetric) {
            minOf(axis - topEdge, bottomEdge - axis).coerceAtLeast(1f)
        } else {
            bounds.height.toFloat()
        }
        val baseY = if (symmetric) axis else bottomEdge

        if (isSpectrumVisuallySilent(peak, halfSpan, MIN_BAR_HEIGHT)) return

        if (solid && !symmetric) {

            if (polar != null) {
                val needP = 2 * count * 2
                if (polylineScratch.size < needP) polylineScratch = FloatArray(needP)
                if (polylineHeightsScratch.size < count) polylineHeightsScratch = FloatArray(count)
                for (i in 0 until count) {
                    polylineHeightsScratch[i] =
                        (stretchAroundMid(values[i], bounceScale) * halfSpan)
                            .coerceAtLeast(MIN_BAR_HEIGHT)
                }
                val np = spectrumPolarPolylinePoints(
                    spec = polar, count = count,
                    heights = polylineHeightsScratch, out = polylineScratch,
                )
                if (np > 0) {
                    val jointArcPolar =
                        spectrumPolylineCornerPx(slot, MIN_BAR_HEIGHT, barCorner) * barShapeRatio
                    if (spectrumPolylinePath(polylinePath, polylineScratch, np, jointArcPolar)) {
                        fillTracked(g, polylinePath)
                    }
                }
                return
            }

            val need = if (symmetric) (2 * count + 6) * 2 else (count + 3) * 2
            if (polylineScratch.size < need) polylineScratch = FloatArray(need)
            if (polylineHeightsScratch.size < count) polylineHeightsScratch = FloatArray(count)

            for (i in 0 until count) {
                polylineHeightsScratch[i] = (stretchAroundMid(values[i], bounceScale) * halfSpan)
                    .coerceAtLeast(MIN_BAR_HEIGHT)
            }

            val n = spectrumPolylinePoints(
                x0 = x0, count = count, slotPx = slot,
                heights = polylineHeightsScratch, baseY = baseY,
                out = polylineScratch,
                mirrorAxisY = if (symmetric) baseY else Float.NaN,
            )
            if (n > 0) {

                val jointArc = spectrumPolylineCornerPx(slot, MIN_BAR_HEIGHT, barCorner) *
                    barShapeRatio
                if (paintForBar != null) {
                    var maxH = 0f
                    for (i in 0 until count) {
                        val hv = polylineHeightsScratch[i]
                        if (hv.isFinite() && hv > maxH) maxH = hv
                    }
                    g.paint = paintForBar(baseY - maxH, baseY, jointArc, fillArgb)
                }

                if (spectrumPolylinePath(polylinePath, polylineScratch, n, jointArc)) {
                    fillTracked(g, polylinePath)
                }
            }
            return
        }

        for (i in 0 until count) {

            val polarSaved = if (polar != null) g.transform else null
            if (polar != null) {
                g.rotate(
                    Math.toRadians(polarAngleDeg(polar, i, count).toDouble()),
                    polar.cx.toDouble(),
                    polar.cy.toDouble(),
                )
            }

            val value = stretchAroundMid(values[i], bounceScale)

            val barArgb = argbForBar?.invoke(i, count, value, fillArgb) ?: fillArgb

            val barHeight = (value * halfSpan).coerceAtLeast(MIN_BAR_HEIGHT)

            val (leftSlot, barW) = spectrumBarRectX(x0, i, slot, gap, barWidth, solid)

            val left = if (polar != null) polar.cx - barW / 2f else leftSlot
            val tipTop = baseY - barHeight
            val tipBottom = if (symmetric) baseY + barHeight else baseY

            val segmentFn = argbForBarSegment
            if (segmentFn != null && !symmetric) {
                val segs = CAMO_SEGMENT_ROWS
                val totalH = tipBottom - tipTop
                val oldPaint = g.paint
                var segTop = tipTop
                for (k in 0 until segs) {
                    val segBottom =
                        if (k == segs - 1) tipBottom else tipTop + totalH * (k + 1) / segs
                    val segArgb = segmentFn.invoke(i, count, k, segs, fillArgb)
                    if (k == 0 && tipFade) {

                        fillFadedPiece(
                            g, left, barW, arc, segTop, segBottom,
                            segArgb, brushes, fadeAtBottom = false,
                            roundTop = true, roundBottom = false,
                            fadeAnchor = segTop,
                            fadeBandPx = spectrumFadeBandPx(segBottom - segTop, arc),
                        )
                    } else {

                        g.color = java.awt.Color(segArgb, true)
                        barSegmentPath(
                            scratchBarPath, left, segTop, barW, segBottom - segTop, arc,
                            roundTop = (k == 0), roundBottom = false,
                        )
                        fillTracked(g, scratchBarPath)
                    }
                    segTop = segBottom
                }

                g.paint = oldPaint
            } else if (tipFade) {

                if (symmetric) {
                    val n = spectrumBarPieces(tipTop, tipBottom, true, pieceScratch)
                    if (n == 2) {

                        fillFadedPiece(
                            g, left, barW, arc, pieceScratch[0], pieceScratch[1],
                            barArgb, brushes, fadeAtBottom = false,
                            roundTop = true, roundBottom = false,
                            fadeAnchor = pieceScratch[0], fadeBandPx = spectrumFadeBandPx(pieceScratch[1] - pieceScratch[0], arc),
                        )

                        fillFadedPiece(
                            g, left, barW, arc, pieceScratch[2], pieceScratch[3],
                            barArgb, brushes, fadeAtBottom = true,
                            roundTop = false, roundBottom = true,
                            fadeAnchor = pieceScratch[3], fadeBandPx = spectrumFadeBandPx(pieceScratch[3] - pieceScratch[2], arc),
                        )
                    }
                } else {
                    fillFadedPiece(
                        g, left, barW, arc, tipTop, tipBottom,
                        barArgb, brushes, fadeAtBottom = false,

                        roundTop = true, roundBottom = false,
                        fadeAnchor = tipTop, fadeBandPx = spectrumFadeBandPx(tipBottom - tipTop, arc),
                    )
                }
            } else {

                val arcTall = arc.coerceAtMost(barHeight * 2f)

                if (paintForBar != null) {
                    g.paint = paintForBar(tipTop, tipBottom, arcTall, barArgb)
                } else if (argbForBar != null) {

                    g.color = java.awt.Color(barArgb, true)
                }
                if (symmetric) {

                    scratchRect.setRoundRect(
                        left.toDouble(),
                        tipTop.toDouble(),
                        barW.toDouble(),
                        (tipBottom - tipTop).toDouble(),
                        (arcTall * 2f).toDouble(),
                        (arcTall * 2f).toDouble(),
                    )
                    fillTracked(g, scratchRect)
                } else {

                    barSegmentPath(
                        scratchBarPath, left, tipTop, barW, tipBottom - tipTop, arc,
                        roundTop = true, roundBottom = false,
                    )
                    fillTracked(g, scratchBarPath)
                }

                if (fadeMaskForBar != null) {
                    applyFadeMask(
                        g,
                        if (symmetric) scratchRect else scratchBarPath,
                        fadeMaskForBar(tipTop, tipBottom, barArgb, arcTall),
                    )
                }
            }

            if (polarSaved != null) g.transform = polarSaved
        }

        if (peaks.size == values.size) {
            val capH = PEAK_CAP_HEIGHT.coerceAtMost(halfSpan)
            val arcCap = arc.coerceAtMost(capH * 2f)
            for (i in 0 until count) {
                val pv = peaks[i].coerceIn(0f, 1f)

                if (pv <= SILENCE_PEAK_EPS) continue

                val polarSavedCap = if (polar != null) g.transform else null
                if (polar != null) {
                    g.rotate(
                        Math.toRadians(polarAngleDeg(polar, i, count).toDouble()),
                        polar.cx.toDouble(),
                        polar.cy.toDouble(),
                    )
                }
                val h = pv * halfSpan

                val capArgb = argbForBar?.invoke(i, count, pv, fillArgb) ?: fillArgb

                val (leftSlotCap, barW) = spectrumBarRectX(x0, i, slot, gap, barWidth, solid)

                val left = if (polar != null) polar.cx - barW / 2f else leftSlotCap
                val top = (baseY - h - capH).coerceAtLeast(topEdge)
                if (tipFade) {

                    fillFadedPiece(
                        g, left, barW, arcCap, top, top + capH,
                        capArgb, brushes, fadeAtBottom = false,
                        roundTop = true, roundBottom = true,
                        fadeAnchor = top, fadeBandPx = spectrumFadeBandPx(capH, arc),
                    )
                } else {
                    scratchRect.setRoundRect(
                        left.toDouble(), top.toDouble(),
                        barW.toDouble(), capH.toDouble(),
                        arcCap.toDouble(), arcCap.toDouble(),
                    )
                    if (paintForBar != null) g.paint = paintForBar(top, top + capH, arcCap, capArgb)
                    fillTracked(g, scratchRect)

                    if (fadeMaskForBar != null) {
                        applyFadeMask(
                            g, scratchRect,
                            fadeMaskForBar(top, top + capH, capArgb, arcCap),
                        )
                    }
                }
                if (symmetric) {

                    val topDown = (baseY + h).coerceAtMost(bottomEdge - capH)
                    if (tipFade) {
                        fillFadedPiece(
                            g, left, barW, arcCap, topDown, topDown + capH,
                            capArgb, brushes, fadeAtBottom = true,
                            roundTop = true, roundBottom = true,
                            fadeAnchor = topDown + capH, fadeBandPx = spectrumFadeBandPx(capH, arc),
                        )
                    } else {
                        scratchRect.setRoundRect(
                            left.toDouble(), topDown.toDouble(),
                            barW.toDouble(), capH.toDouble(),
                            arcCap.toDouble(), arcCap.toDouble(),
                        )
                        if (paintForBar != null) g.paint = paintForBar(topDown, topDown + capH, arcCap, capArgb)
                        fillTracked(g, scratchRect)
                    }
                }

                if (polarSavedCap != null) g.transform = polarSavedCap
            }
        }
    }

    private var barScratch: FloatArray = FloatArray(0)

    private fun barScratch(n: Int): FloatArray {
        if (barScratch.size < n) barScratch = FloatArray(n)
        return barScratch
    }

    private fun fillFadedPiece(
        g: Graphics2D,
        left: Float,
        width: Float,
        arc: Float,
        top: Float,
        bottom: Float,
        argb: Int,
        brushes: SpectrumTipFadeBrushCache,

        fadeAtBottom: Boolean,
        roundTop: Boolean,
        roundBottom: Boolean,
        fadeAnchor: Float,
        fadeBandPx: Float,
    ) {
        val h = bottom - top
        if (!h.isFinite() || h <= 0f) return
        if (!top.isFinite() || !left.isFinite() || !width.isFinite() || width <= 0f) return
        if (!fadeAnchor.isFinite() || !fadeBandPx.isFinite()) return
        val old = g.transform
        g.translate(0.0, fadeAnchor.toDouble())
        barSegmentPath(scratchBarPath, left, top - fadeAnchor, width, h, arc, roundTop, roundBottom)
        g.paint = if (fadeAtBottom) {
            brushes.fadeToTip(argb, fadeBandPx)
        } else {
            brushes.fadeFromTip(argb, fadeBandPx)
        }
        fillTracked(g, scratchBarPath)
        g.transform = old
    }

    private fun paintWave(
        g: Graphics2D,
        cache: RenderCache,
        bands: FloatArray,
        bounds: Rectangle,
        bounceScale: Float,
    ) {
        val count = bands.size
        val slot = bounds.width.toFloat() / count
        val gap = (slot * 0.25f).coerceAtLeast(1f)
        val barWidth = (slot - gap).coerceAtLeast(1f)

        val x0 = bounds.x.toFloat()
        val baseY = bounds.y + bounds.height
        val maxHeight = bounds.height.toFloat()

        val path = scratchPath
        path.reset()

        for (i in bands.indices) {

            val value = stretchAroundMid(bands[i], bounceScale)
            val centerX = x0 + i * slot + gap / 2f + barWidth / 2f
            val y = baseY - (value * maxHeight).coerceAtLeast(MIN_BAR_HEIGHT)
            if (i == 0) path.moveTo(centerX.toDouble(), y.toDouble())
            else path.lineTo(centerX.toDouble(), y.toDouble())
        }

        val strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f)
        g.stroke = cache.stroke(strokeWidth)
        g.draw(path)
    }

    private val scratchRect = RoundRectangle2D.Float()

    private val scratchPath = Path2D.Float()

    private val scratchBarPath = Path2D.Float()

    private val pieceScratch = FloatArray(4)

    private val polylinePath = Path2D.Float()

    private var polylineScratch = FloatArray(0)

    private var polylineHeightsScratch = FloatArray(0)

    private val tipFadeBrushes = SpectrumTipFadeBrushCache()

    private fun fillTracked(g: Graphics2D, shape: Shape) {
        g.fill(shape)
    }

    private const val ATTACK_TAU_SECONDS = 0.02

    private const val RELEASE_TAU_SECONDS = 0.35

    private const val PAUSED_RELEASE_TAU_SECONDS = 0.06

    private const val SILENT_EPSILON = 0.004f

    private const val PEAK_FALL_PER_SECOND = 0.45

    private const val PAUSED_PEAK_FALL_PER_SECOND = 2.5

    private const val PEAK_CAP_HEIGHT = 2f

    private const val DEFAULT_FRAME_SECONDS = 1.0 / 60.0

    private const val MAX_DT_SECONDS = 0.1

    private const val MIN_BAR_HEIGHT = 2f

    private const val SILENCE_PEAK_EPS = 1e-6f
}

fun playedArgbFrom(paint: Paint?): Int? = when (paint) {
    null -> null
    is Color -> paint.rgb
    is LinearGradientPaint -> paint.colors.maxByOrNull { it.alpha }?.rgb
    is GradientPaint -> maxOf(paint.color1, paint.color2, compareBy { it.alpha }).rgb
    else -> null
}

fun barSegmentPath(
    path: Path2D,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    arc: Float,
    roundTop: Boolean,
    roundBottom: Boolean,
) {
    path.reset()
    if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return
    if (w <= 0f || h <= 0f) return
    val a = if (arc.isFinite()) arc.coerceIn(0f, minOf(w, h) / 2f) else 0f
    val x1 = x + w
    val y1 = y + h
    if (a <= 0f) {

        path.moveTo(x.toDouble(), y.toDouble())
        path.lineTo(x1.toDouble(), y.toDouble())
        path.lineTo(x1.toDouble(), y1.toDouble())
        path.lineTo(x.toDouble(), y1.toDouble())
        path.closePath()
        return
    }
    if (roundTop) {
        path.moveTo(x.toDouble(), (y + a).toDouble())
        path.quadTo(x.toDouble(), y.toDouble(), (x + a).toDouble(), y.toDouble())
        path.lineTo((x1 - a).toDouble(), y.toDouble())
        path.quadTo(x1.toDouble(), y.toDouble(), x1.toDouble(), (y + a).toDouble())
    } else {
        path.moveTo(x.toDouble(), y.toDouble())
        path.lineTo(x1.toDouble(), y.toDouble())
    }
    if (roundBottom) {
        path.lineTo(x1.toDouble(), (y1 - a).toDouble())
        path.quadTo(x1.toDouble(), y1.toDouble(), (x1 - a).toDouble(), y1.toDouble())
        path.lineTo((x + a).toDouble(), y1.toDouble())
        path.quadTo(x.toDouble(), y1.toDouble(), x.toDouble(), (y1 - a).toDouble())
    } else {
        path.lineTo(x1.toDouble(), y1.toDouble())
        path.lineTo(x.toDouble(), y1.toDouble())
    }
    path.closePath()
}

class SpectrumTipFadeBrushCache(private val maxEntries: Int = 192) {

    private val lru = LinkedHashMap<Long, Paint>(16, 0.75f, true)

    var created: Int = 0
        private set

    var reused: Int = 0
        private set

    val size: Int get() = lru.size

    fun clear() {
        lru.clear()
        created = 0
        reused = 0
    }

    fun fadeFromTip(argb: Int, bandPx: Float): Paint = brush(argb, bandPx, down = true)

    fun fadeToTip(argb: Int, bandPx: Float): Paint = brush(argb, bandPx, down = false)

    private fun brush(argb: Int, bandPx: Float, down: Boolean): Paint {
        val band = if (bandPx.isFinite()) bandPx.coerceAtLeast(0.25f) else 0.25f

        val bandKey = (band * 4f).roundToInt().coerceIn(0, 255)
        val key = (argb.toLong() and 0xFFFFFFFFL) shl 10 or
            (bandKey.toLong() shl 1) or
            (if (down) 1L else 0L)
        val hit = lru[key]
        if (hit != null) {
            reused++
            return hit
        }
        val base = Color(argb, true)
        val solid = Color(base.red, base.green, base.blue, base.alpha)
        val clear = Color(base.red, base.green, base.blue, 0)
        val len = bandKey / 4f

        val paint: Paint = if (down) {
            LinearGradientPaint(
                0f, 0f, 0f, len,
                floatArrayOf(0f, 1f),
                arrayOf(clear, solid),
            )
        } else {
            LinearGradientPaint(
                0f, 0f, 0f, -len,
                floatArrayOf(0f, 1f),
                arrayOf(clear, solid),
            )
        }
        created++
        if (lru.size >= maxEntries) {
            val it = lru.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        lru[key] = paint
        return paint
    }
}


@Suppress("LongParameterList")


fun spectrumPolylinePath(
    path: Path2D,
    pts: FloatArray,
    pointCount: Int,
    radiusPx: Float,
): Boolean {
    path.reset()

    if (pointCount < 3) return false
    if (pts.size < pointCount * 2) return false
    val r0 = if (radiusPx.isFinite()) radiusPx.coerceAtLeast(0f) else 0f
    val last = pointCount - 1
    if (!pts[0].isFinite() || !pts[1].isFinite()) return false

    path.moveTo(pts[0].toDouble(), pts[1].toDouble())

    for (i in 1 until last) {
        val vx = pts[i * 2]
        val vy = pts[i * 2 + 1]
        val px = pts[(i - 1) * 2]
        val py = pts[(i - 1) * 2 + 1]
        val nx = pts[(i + 1) * 2]
        val ny = pts[(i + 1) * 2 + 1]
        if (!vx.isFinite() || !vy.isFinite() || !nx.isFinite() || !ny.isFinite()) return false
        val inLen = sqrt((vx - px) * (vx - px) + (vy - py) * (vy - py))
        val outLen = sqrt((nx - vx) * (nx - vx) + (ny - vy) * (ny - vy))
        val r = minOf(r0, inLen / 2f, outLen / 2f)
        if (r <= 0f || inLen <= 0f || outLen <= 0f) {

            path.lineTo(vx.toDouble(), vy.toDouble())
            continue
        }

        val ax = vx - (vx - px) / inLen * r
        val ay = vy - (vy - py) / inLen * r
        val bx = vx + (nx - vx) / outLen * r
        val by = vy + (ny - vy) / outLen * r
        path.lineTo(ax.toDouble(), ay.toDouble())
        path.quadTo(vx.toDouble(), vy.toDouble(), bx.toDouble(), by.toDouble())
    }

    if (!pts[last * 2].isFinite() || !pts[last * 2 + 1].isFinite()) return false
    path.lineTo(pts[last * 2].toDouble(), pts[last * 2 + 1].toDouble())
    path.closePath()
    return true
}
