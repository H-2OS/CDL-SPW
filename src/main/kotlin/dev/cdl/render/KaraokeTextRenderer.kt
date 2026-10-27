// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.BasicStroke
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.text.BreakIterator
import kotlin.math.roundToInt

internal data class TextOutline(
    val widthPx: Float,
    val argb: Int,
    val followWeight: Boolean = false,
    val weightLinkPercent: Int = DEFAULT_OUTLINE_WEIGHT_LINK_PERCENT,
)

public fun resolveOutline(
    enabled: Boolean,
    widthTenthPx: Int,
    r: Int,
    g: Int,
    b: Int,
): ResolvedOutline? {
    if (!enabled) return null
    val w = widthTenthPx / 10f
    if (w <= 0f) return null
    return ResolvedOutline(w, packRgbPublic(r, g, b))
}

public data class ResolvedOutline(val widthPx: Float, val argb: Int)

private fun packRgbPublic(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

internal class KaraokeTextRenderer {

    private data class ClusterKey(val text: String, val wordTiming: Boolean)

    private val clusterCache = HashMap<ClusterKey, IntArray>()

    fun render(
        g: Graphics2D,
        cache: RenderCache,
        layout: TextLayout,
        text: String,
        baselineX: Float,
        baselineY: Float,
        sungColor: Int,
        unsungColor: Int,
        progress: Float,
        karaoke: Boolean,
        wordBoundaries: FloatArray? = null,
        wordLengths: IntArray = IntArray(0),
        featherFontSize: Float? = null,
        outline: TextOutline? = null,

        smooth: Boolean = false,
    ) {

        if (text.isEmpty()) return

        karaokeApplyAntialiasHints(g)

        if (outline != null) {
            val effectiveWidthPx = karaokeOutlineWidthPx(
                baseWidthPx = outline.widthPx,
                weightScale = karaokeFontWeightScale(cache.currentFont()),
                linkPercent = outline.weightLinkPercent,
                followWeight = outline.followWeight,
            )
            if (effectiveWidthPx > 0f) {
                val oldStroke = g.stroke
                val oldColor = g.color
                val oldFont = g.font
                try {
                    g.font = cache.currentFont()
                    g.color = cache.color(outline.argb)
                    g.stroke = karaokeOutlineStroke(effectiveWidthPx)
                    g.draw(layout.getOutline(AffineTransform.getTranslateInstance(
                        baselineX.toDouble(), baselineY.toDouble(),
                    )))
                } catch (_: Throwable) {

                } finally {
                    g.stroke = oldStroke
                    g.color = oldColor
                    g.font = oldFont
                }
            }
        }

        val sung = cache.color(sungColor)
        val unsung = cache.color(unsungColor)

        if (!karaoke) {
            val oldFont = g.font
            val oldColor = g.color
            try {
                g.font = cache.currentFont()
                g.color = unsung
                layout.draw(g, baselineX, baselineY)
            } finally {
                g.font = oldFont
                g.color = oldColor
            }
            return
        }

        val hasWordTiming = wordBoundaries != null && wordBoundaries.isNotEmpty()
        if (hasWordTiming || smooth) {
            renderWordTimed(
                g, cache, layout, baselineX, baselineY, sung, unsung,
                wordBoundaries = if (hasWordTiming) {

                    wordBoundaries
                } else {

                    floatArrayOf(progress.coerceIn(0f, 1f))
                },
                wordLengths = if (hasWordTiming) {
                    wordLengths
                } else {
                    intArrayOf(text.length.coerceAtLeast(1))
                },
                featherFontSize = featherFontSize,

                featherScale = if (smooth) SMOOTH_FEATHER_SCALE else 1f,
            )
            return
        }

        val oldFont = g.font
        val oldColor = g.color
        try {

            g.font = cache.currentFont()
            g.color = unsung
            layout.draw(g, baselineX, baselineY)

            val charIndex = resolveCharIndex(text, layout, progress)
            if (charIndex <= 0) return

            val full = layout.characterCount
            val highlight: java.awt.Shape = if (charIndex >= full) {

                layout.getLogicalHighlightShape(0, full)
            } else {
                layout.getLogicalHighlightShape(0, charIndex)
            }

            val translated = AffineTransform
                .getTranslateInstance(baselineX.toDouble(), baselineY.toDouble())
                .createTransformedShape(highlight)
            val oldClip = g.clip
            try {
                g.clip = translated
                g.color = sung
                layout.draw(g, baselineX, baselineY)
            } finally {

                g.clip = oldClip
            }
        } finally {
            g.font = oldFont
            g.color = oldColor
        }
    }

    private fun resolveCharIndex(
        text: String,
        layout: TextLayout,
        progress: Float,
    ): Int {
        val full = layout.characterCount

        val clusters = clustersOf(text, wordTiming = false)

        if (clusters.size < 2) return full
        return karaokeSungCharCount(clusters, progress).coerceIn(0, full)
    }

    private fun clustersOf(text: String, wordTiming: Boolean): IntArray {
        val key = ClusterKey(text, wordTiming)
        clusterCache[key]?.let { return it }

        val starts = ArrayList<Int>(text.length + 1)
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            starts.add(start)
            start = end
            end = iterator.next()
        }

        starts.add(text.length)

        val result = starts.toIntArray()

        if (clusterCache.size >= CLUSTER_CACHE_LIMIT) clusterCache.clear()
        clusterCache[key] = result
        return result
    }

    fun invalidate() {
        clusterCache.clear()
    }

    private fun renderWordTimed(
        g: Graphics2D,
        cache: RenderCache,
        layout: TextLayout,
        baselineX: Float,
        baselineY: Float,
        sung: java.awt.Color,
        unsung: java.awt.Color,
        wordBoundaries: FloatArray,
        wordLengths: IntArray,
        featherFontSize: Float?,

        featherScale: Float = 1f,
    ) {

        val pick = karaokeBoundaryPick(wordBoundaries, wordLengths, layout.characterCount)
        val boundaryChar = pick.charIndex
        val boundaryRatio = pick.ratio

        val boundaryLen = pick.length.coerceAtLeast(1)

        val oldFont = g.font
        val oldColor = g.color
        val oldPaint = g.paint
        try {

            g.font = cache.currentFont()

            g.color = unsung
            layout.draw(g, baselineX, baselineY)

            if (boundaryChar < 0) {
                g.color = sung
                layout.draw(g, baselineX, baselineY)
                return
            }
            if (boundaryRatio <= 0f) return

            val startX = karaokeCaretX(layout, boundaryChar)
            val endX = karaokeCaretX(layout, boundaryChar + boundaryLen)

            val frontRel = karaokeSweepFrontRel(startX, endX, boundaryRatio)

            val featherRefSize = featherFontSize?.takeIf { it > 0f } ?: cache.currentFont().size2D
            val feather = karaokeFeatherPx(featherRefSize, endX - startX, featherScale)

            val geom = karaokeFeatherGeometry(baselineX, layout.advance, frontRel, feather)

            val oldClip = g.clip
            try {
                g.clip(Rectangle2D.Float(baselineX, baselineY - layout.ascent,
                    geom.clipRight - baselineX, layout.ascent + layout.descent))

                g.paint = GradientPaint(
                    geom.gradientLeft, 0f, sung,
                    geom.gradientRight, 0f, unsung,
                )
                layout.draw(g, baselineX, baselineY)
            } finally {
                g.clip = oldClip
            }
        } finally {
            g.font = oldFont
            g.color = oldColor
            g.paint = oldPaint
        }
    }

    private companion object {

        const val CLUSTER_CACHE_LIMIT = 64

        const val SMOOTH_FEATHER_SCALE = 2.5f
    }
}

fun karaokeOutlineStroke(widthPx: Float): BasicStroke =
    BasicStroke(widthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

const val DEFAULT_OUTLINE_WEIGHT_LINK_PERCENT = 50

fun karaokeOutlineWidthPx(
    baseWidthPx: Float,
    weightScale: Float,
    linkPercent: Int,
    followWeight: Boolean,
): Float {

    if (!followWeight) return baseWidthPx

    if (!baseWidthPx.isFinite() || baseWidthPx <= 0f) return baseWidthPx

    if (!weightScale.isFinite() || weightScale <= 0f) return baseWidthPx
    val ratio = linkPercent.coerceIn(0, 100) / 100f
    val linked = baseWidthPx * (1f + (weightScale - 1f) * ratio)
    if (!linked.isFinite()) return baseWidthPx

    return (linked.coerceAtLeast(0f) * 10f).roundToInt() / 10f
}

fun karaokeFontWeightScale(font: java.awt.Font): Float {

    return if (font.isBold()) 2f else 1f
}

fun karaokeApplyAntialiasHints(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
}
