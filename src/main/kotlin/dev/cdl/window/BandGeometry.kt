// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import java.awt.geom.Rectangle2D
import kotlin.math.roundToInt

fun bandFractionCombined(expanded: Boolean, expansion: Float, bandFraction: Float): Float =
    if (expanded) maxOf(bandFraction, expansion) else bandFraction

fun bandCollapseFraction(from: Float, easedT: Float): Float =
    from.coerceIn(0f, 1f) * (1f - easedT.coerceIn(0f, 1f))

data class BandGeometry(
    val islandTop: Float,
    val islandHeight: Int,
    val stripTop: Float,
    val stripHeight: Float,
    val lyricsTop: Float,
    val lyricsHeight: Float,

    val islandWidth: Int = 0,

    val islandLeft: Float = 0f,

    val fraction: Float = 0f,

    val widthGrowPx: Int = 0,
) {

    val islandBottom: Float get() = islandTop + islandHeight

    val islandRight: Float get() = islandLeft + islandWidth

    val panelHeight: Float get() = if (stripTop == 0f) stripHeight + islandHeight else islandTop + islandHeight

    fun coverOuterSizePx(scaleWithFont: Boolean): Float =
        leadingCoverOuterSizePx(lyricsHeight, scaleWithFont)

    fun coverTopFor(outerSizePx: Float): Float {
        val s = if (outerSizePx.isFinite()) outerSizePx.coerceAtLeast(0f) else 0f
        return lyricsTop + (lyricsHeight - s) / 2f
    }

    fun coverRect(scaleWithFont: Boolean, leadingInsetPx: Float): Rectangle2D.Float {
        val size = coverOuterSizePx(scaleWithFont)
        val inset = if (leadingInsetPx.isFinite()) leadingInsetPx.coerceAtLeast(0f) else 0f
        return Rectangle2D.Float(islandLeft + inset, coverTopFor(size), size, size)
    }

    fun stripRect(): Rectangle2D.Float =
        Rectangle2D.Float(islandLeft, stripTop, islandWidth.toFloat(), stripHeight)
}

fun rectsIntersect(a: Rectangle2D, b: Rectangle2D): Boolean =
    a.x < b.x + b.width && b.x < a.x + a.width &&
        a.y < b.y + b.height && b.y < a.y + a.height

fun expandWidthGrowPx(stripHeightPx: Float): Int {
    if (!stripHeightPx.isFinite()) return 0
    val stripQ = stripHeightPx.roundToInt().coerceAtLeast(0)

    return ((stripQ + 1) / 2) * 2
}

fun bandGeometry(
    baseHeightPx: Int,
    stripHeightPx: Float,
    fraction: Float,
    stripAtTop: Boolean,
    baseWidthPx: Int = 0,
    anchorAtLeftEdge: Boolean = false,
    widthGrowOverridePx: Int = -1,
): BandGeometry {
    val base = baseHeightPx.coerceAtLeast(0)

    val stripQ = if (stripHeightPx.isFinite()) stripHeightPx.roundToInt().coerceAtLeast(0) else 0
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f

    val height = (base + stripQ * f).roundToInt().coerceIn(0, base + stripQ)

    val top = if (stripAtTop) (stripQ + base) - height else 0

    val growQ = if (widthGrowOverridePx >= 0) widthGrowOverridePx else expandWidthGrowPx(stripHeightPx)
    val bw = baseWidthPx.coerceAtLeast(0)
    val windowW = bw + growQ
    val islandW = if (bw <= 0) 0 else (bw + (growQ * f).roundToInt()).coerceIn(bw, windowW)

    val islandLeft = when {
        windowW <= 0 -> 0f
        anchorAtLeftEdge -> 0f
        else -> (windowW - islandW) / 2f
    }
    return BandGeometry(
        islandTop = top.toFloat(),
        islandHeight = height,

        stripTop = (if (stripAtTop) 0 else base).toFloat(),
        stripHeight = stripQ.toFloat(),
        lyricsTop = (if (stripAtTop) stripQ else 0).toFloat(),
        lyricsHeight = base.toFloat(),
        islandWidth = islandW,
        islandLeft = islandLeft,
        fraction = f,
        widthGrowPx = growQ,
    )
}
