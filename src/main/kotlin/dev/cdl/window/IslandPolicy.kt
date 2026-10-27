// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.playback.LyricLine
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val SCROLL_MIN_OVERFLOW_PX = 8f

const val WIDTH_GLYPH_SLACK_PX = 4f

fun clickThroughFromSetting(islandInteractive: Boolean): Boolean = islandInteractive

fun shouldDriveHover(clickThroughActive: Boolean): Boolean = !clickThroughActive

fun hoverNeedsPointerPoll(
    fullClickThrough: Boolean,
    regionalClickThrough: Boolean,
): Boolean = if (fullClickThrough) false else regionalClickThrough

enum class HoverCursor {

    DEFAULT,

    HAND,

    MOVE,
}

fun hoverCursorFor(
    hitControl: Boolean,
    overGlyph: Boolean,
    positionLocked: Boolean,
): HoverCursor = when {
    hitControl -> HoverCursor.HAND
    overGlyph && !positionLocked -> HoverCursor.MOVE
    else -> HoverCursor.DEFAULT
}

fun centeredWindowX(oldX: Int, oldWidth: Int, newWidth: Int): Int =
    oldX + oldWidth / 2 - newWidth / 2

data class SavedWindowPosition(val x: Int, val y: Int, val width: Int?)

fun parseSavedWindowPosition(raw: String): SavedWindowPosition? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val parts = t.split(',')
    if (parts.size != 2 && parts.size != 3) return null
    val x = parts[0].trim().toIntOrNull() ?: return null
    val y = parts[1].trim().toIntOrNull() ?: return null
    val w = if (parts.size == 3) {

        val v = parts[2].trim().toIntOrNull() ?: return null
        if (v <= 0) null else v
    } else {
        null
    }
    return SavedWindowPosition(x, y, w)
}

fun restoredWindowX(savedX: Int, savedWidth: Int?, currentWidth: Int): Int =
    if (savedWidth == null || savedWidth == currentWidth) {
        savedX
    } else {
        centeredWindowX(savedX, savedWidth, currentWidth)
    }

data class QuickPlaceTarget(val x: Int, val y: Int)

enum class QuickPlaceEdge { TOP, BOTTOM }

fun quickPlaceTarget(
    screenX: Int,
    screenY: Int,
    screenWidth: Int,
    screenHeight: Int,
    insetTop: Int,
    insetBottom: Int,
    insetLeft: Int,
    insetRight: Int,
    windowWidth: Int,
    windowHeight: Int,

    islandHeightPx: Int,
    yShiftPx: Int,

    edge: QuickPlaceEdge,
    onTaskbar: Boolean,
): QuickPlaceTarget {

    val iTop = if (onTaskbar) 0 else insetTop
    val iBottom = if (onTaskbar) 0 else insetBottom
    val iLeft = if (onTaskbar) 0 else insetLeft
    val iRight = if (onTaskbar) 0 else insetRight

    val areaTop = screenY + iTop
    val areaBottom = screenY + screenHeight - iBottom
    val areaLeft = screenX + iLeft
    val areaRight = screenX + screenWidth - iRight

    val islandHeight = if (islandHeightPx > 0) {
        islandHeightPx
    } else {
        (windowHeight - yShiftPx).coerceAtLeast(1)
    }

    val usableWidth = areaRight - areaLeft
    val centeredX = screenX + (screenWidth - windowWidth) / 2
    val x = if (windowWidth >= usableWidth) {
        areaLeft
    } else {
        centeredX.coerceIn(areaLeft, areaRight - windowWidth)
    }

    val islandTop = when (edge) {
        QuickPlaceEdge.TOP -> {

            val maxTop = (areaBottom - islandHeight).coerceAtLeast(screenY)
            areaTop.coerceIn(screenY, maxTop)
        }
        QuickPlaceEdge.BOTTOM -> {

            (screenY + screenHeight - islandHeight).coerceAtLeast(screenY)
        }
    }

    return QuickPlaceTarget(x, islandTop - yShiftPx)
}

fun anchoredWindowY(oldY: Int, heightGrowth: Int, stripAtTop: Boolean): Int =
    if (stripAtTop) oldY - heightGrowth else oldY

const val LINE_HEIGHT_RATIO = 1.36f

fun marqueeOffsetForProgress(
    advancePx: Float,
    progress: Float,
    visibleWidthPx: Float,
): Float {
    if (!advancePx.isFinite() || !visibleWidthPx.isFinite()) return 0f

    if (visibleWidthPx <= 0f) return 0f
    val overflow = advancePx - visibleWidthPx
    if (overflow <= 0f) return 0f
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val target = advancePx * p - visibleWidthPx / 2f
    return -target.coerceIn(0f, overflow)
}

fun marqueeSungFraction(
    advancePx: Float,
    progress: Float,
    visibleWidthPx: Float,
): Float {
    if (!advancePx.isFinite() || !visibleWidthPx.isFinite() || visibleWidthPx <= 0f) return 0f
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val sungW = advancePx * p

    return (sungW + marqueeOffsetForProgress(advancePx, p, visibleWidthPx)) / visibleWidthPx
}

fun ellipsizeToWidth(
    text: String,
    availWidth: Float,
    advanceOf: (String) -> Float,
): String {
    if (text.isEmpty()) return text
    if (availWidth <= 0f) return ""
    if (advanceOf(text) <= availWidth) return text

    val ellipsis = "…"
    val ellipsisWidth = advanceOf(ellipsis)

    if (ellipsisWidth > availWidth) return ""

    var lo = 0
    var hi = text.length - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (advanceOf(text.substring(0, mid)) + ellipsisWidth <= availWidth) lo = mid else hi = mid - 1
    }

    return if (lo <= 0) ellipsis else text.substring(0, lo) + ellipsis
}

fun coverRotationDegrees(positionMs: Long, periodSeconds: Float): Float {
    if (periodSeconds <= 0f) return 0f
    if (positionMs <= 0L) return 0f
    val turns = positionMs / 1000.0 / periodSeconds.toDouble()
    return (turns * 360.0 % 360.0).toFloat()
}

const val COVER_SHAPE_CIRCLE = "CIRCLE"

const val COVER_SHAPE_SQUARE = "SQUARE"

const val COVER_SHAPE_ROUNDED = "ROUNDED"

const val VINYL_RING_OVER_OUTER = 1f / 6f

fun coverVinylRingWidth(outerSize: Float, vinyl: Boolean): Float {
    if (!vinyl) return 0f
    if (outerSize <= 0f) return 0f
    return outerSize * VINYL_RING_OVER_OUTER
}

fun coverContentSize(outerSize: Float, vinyl: Boolean): Float {
    if (outerSize <= 0f) return 0f
    return outerSize - 2f * coverVinylRingWidth(outerSize, vinyl)
}

@Suppress("UNUSED_PARAMETER")
fun coverSlotWidth(outerSize: Float, vinyl: Boolean): Float {
    if (outerSize <= 0f) return 0f
    return outerSize
}

const val BACKGROUND_MODE_COVER_IMAGE = "cover_image"

fun needsCoverImageFor(
    coverEnabled: Boolean,
    lyricColorMode: String,
    backgroundColorMode: String,
    spectrumColorMode: String,

    slotSpectrumColorMode: String = "",

    borderColorMode: String = "",

    translationColorMode: String = "",

    gradientStyle: String = "",
): Boolean =
    coverEnabled ||
        lyricColorMode == dev.cdl.render.CoverPalette.COVER ||
        backgroundColorMode == dev.cdl.render.CoverPalette.COVER ||
        backgroundColorMode == BACKGROUND_MODE_COVER_IMAGE ||
        spectrumColorMode == dev.cdl.render.CoverPalette.COVER ||

        borderColorMode == dev.cdl.render.CoverPalette.COVER ||
        translationColorMode == dev.cdl.render.CoverPalette.COVER ||

        gradientStyle == "FLOW" || gradientStyle == "CAMO" ||

        spectrumColorMode == dev.cdl.render.CoverPalette.COVER_FLOW ||
        spectrumColorMode == dev.cdl.render.CoverPalette.COVER_CAMO ||

        slotSpectrumColorMode == dev.cdl.render.CoverPalette.COVER_FLOW ||
        slotSpectrumColorMode == dev.cdl.render.CoverPalette.COVER_CAMO

const val BACKGROUND_BASE_RED: Int = 0x14

const val BACKGROUND_BASE_GREEN: Int = 0x16

const val BACKGROUND_BASE_BLUE: Int = 0x1A

const val BACKGROUND_BASE_RGB: Int =
    (0xFF shl 24) or
        (BACKGROUND_BASE_RED shl 16) or
        (BACKGROUND_BASE_GREEN shl 8) or
        BACKGROUND_BASE_BLUE

fun coverFillScale(
    angleDeg: Float,
    srcWidth: Float,
    srcHeight: Float,
    targetWidth: Float,
    targetHeight: Float,
): Float {
    if (!srcWidth.isFinite() || !srcHeight.isFinite()) return 1f
    if (!targetWidth.isFinite() || !targetHeight.isFinite()) return 1f
    if (srcWidth <= 0f || srcHeight <= 0f || targetWidth <= 0f || targetHeight <= 0f) return 1f
    val deg = if (angleDeg.isFinite()) ((angleDeg % 360f) + 360f) % 360f else 0f
    val rad = Math.toRadians(deg.toDouble())

    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val scaleX = (targetWidth * c + targetHeight * s) / srcWidth
    val scaleY = (targetWidth * s + targetHeight * c) / srcHeight
    return maxOf(scaleX, scaleY)
}

fun spectrumTipFadeFractions(
    barHeight: Float,
    symmetric: Boolean,

    cornerRadiusPx: Float,
): FloatArray {

    val h = barHeight.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f

    val base = (h * 0.30f).coerceAtMost(6f).coerceAtLeast(1f)
    val byShape = cornerRadiusPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val fade = maxOf(base, byShape)

    val upper = if (symmetric) 0.49f else 0.99f

    val band = (fade / h).coerceAtMost(upper)
    return if (symmetric) {
        floatArrayOf(0f, band, 1f - band, 1f)
    } else {
        floatArrayOf(0f, band, 1f)
    }
}

fun windowYOffsetPx(stripHeightPx: Float, stripAtTop: Boolean): Int =
    if (stripAtTop && stripHeightPx.isFinite()) {
        stripHeightPx.roundToInt().coerceAtLeast(0)
    } else {
        0
    }

data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,

    val yOffsetPx: Int,
)

fun windowTargetBounds(
    oldX: Int,
    oldY: Int,
    oldWidth: Int,
    currentYOffsetPx: Int,
    baseWidthPx: Int,
    baseHeightPx: Int,
    stripHeightPx: Float,
    stripAtTop: Boolean,
    widthGrowOverridePx: Int = -1,
): WindowBounds {

    val stripQ = if (stripHeightPx.isFinite()) stripHeightPx.roundToInt().coerceAtLeast(0) else 0
    val height = (baseHeightPx + stripQ).coerceAtLeast(0)

    val width = (baseWidthPx + if (widthGrowOverridePx >= 0) widthGrowOverridePx else expandWidthGrowPx(stripHeightPx)).coerceAtLeast(0)

    val logicalY = oldY + currentYOffsetPx

    val y = anchoredWindowY(logicalY, stripQ, stripAtTop)

    val x = if (oldWidth != width) centeredWindowX(oldX, oldWidth, width) else oldX
    return WindowBounds(x, y, width, height, windowYOffsetPx(stripHeightPx, stripAtTop))
}

fun islandHitTest(x: Float, y: Float, geometry: BandGeometry): Boolean {
    if (!x.isFinite() || !y.isFinite()) return false
    val width = geometry.islandWidth
    if (width <= 0) return false
    if (x < geometry.islandLeft || x >= geometry.islandLeft + width) return false
    return y >= geometry.islandTop && y < geometry.islandBottom
}

fun islandScreenRect(
    windowX: Int,
    windowY: Int,
    bg: BandGeometry,
): Rectangle2D.Float? {
    if (bg.islandWidth <= 0) return null
    val l = bg.islandLeft
    val t = bg.islandTop
    val w = bg.islandWidth.toFloat()
    val h = bg.islandHeight.toFloat()
    if (!l.isFinite() || !t.isFinite() || !w.isFinite() || !h.isFinite()) return null
    return Rectangle2D.Float(windowX + l, windowY + t, w, h)
}

fun centeredWindowLocationForIsland(
    screenX: Int,
    screenY: Int,
    screenW: Int,
    screenH: Int,
    windowW: Int,
    windowH: Int,
    bg: BandGeometry,
): java.awt.Point? {
    if (screenW <= 0 || screenH <= 0) return null
    if (windowW <= 0 || windowH <= 0) return null
    val l = bg.islandLeft
    val t = bg.islandTop
    val islandW = bg.islandWidth
    val islandH = bg.islandHeight
    if (islandW <= 0 || islandH <= 0) return null
    if (!l.isFinite() || !t.isFinite()) return null

    val islandCenterInWindowX = l + islandW / 2f
    val islandCenterInWindowY = t + islandH / 2f
    val screenCenterX = screenX + screenW / 2f
    val screenCenterY = screenY + screenH / 2f
    val wx = (screenCenterX - islandCenterInWindowX).roundToInt()
    val wy = (screenCenterY - islandCenterInWindowY).roundToInt()
    return java.awt.Point(wx, wy)
}

fun glyphHitAccepted(insideIsland: Boolean, glyphOrCoverHit: Boolean): Boolean =
    insideIsland && glyphOrCoverHit

fun islandDrawLayout(
    controls: PlaybackControls,
    widthPx: Int,
    baseHeightPx: Int,
    stripHeightPx: Float,
    cfg: ControlsConfig,
): PlaybackControls.Layout {

    val stripQ = if (stripHeightPx.isFinite()) stripHeightPx.roundToInt().coerceAtLeast(0) else 0
    return controls.layoutOf(
        width = widthPx.coerceAtLeast(0).toFloat(),
        baseHeight = baseHeightPx.coerceAtLeast(0).toFloat(),
        stripPx = stripQ.toFloat(),
        cfg = cfg,
    )
}

const val NOTCH_BOTTOM_RADIUS_RATIO = 0.12f

fun islandRadiusBasisPx(widthPx: Float, collapsedHeightPx: Float): Float {
    if (!widthPx.isFinite() || !collapsedHeightPx.isFinite()) return 0f
    return minOf(widthPx, collapsedHeightPx).coerceAtLeast(0f)
}

fun islandCornerRadiusPx(cornerRadiusPercent: Int, radiusBasisPx: Float): Float {
    if (!radiusBasisPx.isFinite() || radiusBasisPx <= 0f) return 0f
    val maxRadius = radiusBasisPx / 2f
    return (radiusBasisPx * cornerRadiusPercent / 100f).coerceIn(0f, maxRadius)
}

fun islandEdgeRadiusPx(
    shapeType: String,
    cornerRadiusPercent: Int,
    radiusBasisPx: Float,
    atTopEdge: Boolean,
    coverShape: String = COVER_SHAPE_ROUNDED,
): Float {
    if (!radiusBasisPx.isFinite() || radiusBasisPx <= 0f) return 0f
    val maxRadius = radiusBasisPx / 2f
    return when (shapeType) {

        "PILL" -> maxRadius

        "SQUARE" -> 0f

        "NOTCH" -> if (atTopEdge) {
            maxRadius
        } else {
            (radiusBasisPx * NOTCH_BOTTOM_RADIUS_RATIO).coerceIn(0f, maxRadius)
        }

        else -> islandCornerRadiusPx(cornerRadiusPercent, radiusBasisPx)
    }
}

fun islandShape(
    shapeType: String,
    cornerRadiusPercent: Int,
    width: Float,
    height: Float,
    radiusBasisPx: Float,
    cornerStyle: String,
    cornerN: Int,
): Shape {
    val w = if (width.isFinite()) width else 0f
    val h = if (height.isFinite()) height else 0f
    val basis = if (radiusBasisPx.isFinite() && radiusBasisPx > 0f) {
        radiusBasisPx
    } else {
        minOf(w, h)
    }

    return islandShapeOf(
        width = w,
        height = h,
        topRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, basis, true),
        bottomRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, basis, false),

        cornerStyle = effectiveCornerStyle(shapeType, cornerStyle),
        cornerN = cornerN,
    )
}

fun islandShapeOf(
    width: Float,
    height: Float,
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    cornerStyle: String,
    cornerN: Int,
): Shape {
    val w = if (width.isFinite()) width else 0f
    val h = if (height.isFinite()) height else 0f
    val maxRadius = (minOf(w, h) / 2f).coerceAtLeast(0f)
    val topRadius = if (topRadiusPx.isFinite()) topRadiusPx.coerceIn(0f, maxRadius) else 0f
    val bottomRadius = if (bottomRadiusPx.isFinite()) bottomRadiusPx.coerceIn(0f, maxRadius) else 0f

    if (cornerStyle == "SQUIRCLE") {
        return squirclePath(w, h, topRadius, bottomRadius, cornerN.toFloat())
    }

    if (cornerStyle == "SQUIRCLE") {
        return squirclePath(w, h, topRadius, bottomRadius, cornerN.toFloat())
    }

    if (topRadius <= 0f && bottomRadius <= 0f) return Rectangle2D.Float(0f, 0f, w, h)

    if (topRadius == bottomRadius) {
        return RoundRectangle2D.Float(0f, 0f, w, h, topRadius * 2f, topRadius * 2f)
    }

    val outer = Area(RoundRectangle2D.Float(0f, 0f, w, h, topRadius * 2f, topRadius * 2f))
    if (bottomRadius < topRadius) {
        val cornerBox = topRadius * 2f

        val leftBottom = Area(
            Rectangle2D.Float(0f, h - cornerBox, cornerBox, cornerBox),
        ).apply {
            subtract(
                Area(
                    RoundRectangle2D.Float(
                        0f, h - cornerBox, cornerBox, cornerBox,
                        topRadius * 2f, topRadius * 2f,
                    ),
                ),
            )
            add(
                Area(
                    RoundRectangle2D.Float(
                        0f, h - cornerBox, cornerBox, cornerBox,
                        bottomRadius * 2f, bottomRadius * 2f,
                    ),
                ),
            )
        }
        val rightBottom = Area(
            Rectangle2D.Float(w - cornerBox, h - cornerBox, cornerBox, cornerBox),
        ).apply {
            subtract(
                Area(
                    RoundRectangle2D.Float(
                        w - cornerBox, h - cornerBox, cornerBox, cornerBox,
                        topRadius * 2f, topRadius * 2f,
                    ),
                ),
            )
            add(
                Area(
                    RoundRectangle2D.Float(
                        w - cornerBox, h - cornerBox, cornerBox, cornerBox,
                        bottomRadius * 2f, bottomRadius * 2f,
                    ),
                ),
            )
        }

        outer.subtract(Area(Rectangle2D.Float(0f, h - cornerBox, cornerBox, cornerBox)))
        outer.subtract(Area(Rectangle2D.Float(w - cornerBox, h - cornerBox, cornerBox, cornerBox)))
        outer.add(leftBottom)
        outer.add(rightBottom)
    }
    return outer
}

const val SQUIRCLE_N_DEFAULT: Float = 4f

const val SQUIRCLE_SEGMENTS: Int = 24

fun cornerStyleOf(wire: String): String = if (wire == "SQUIRCLE") "SQUIRCLE" else "ARC"

fun effectiveCornerStyle(shapeType: String, wire: String): String =
    if (shapeType == "PILL" || shapeType == "SQUARE") "ARC" else cornerStyleOf(wire)

fun squirclePath(
    width: Float,
    height: Float,
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    n: Float,
): Shape {
    val w = if (width.isFinite()) width else 0f
    val h = if (height.isFinite()) height else 0f
    val maxRadius = (minOf(w, h) / 2f).coerceAtLeast(0f)
    val tr = if (topRadiusPx.isFinite()) topRadiusPx.coerceIn(0f, maxRadius) else 0f
    val br = if (bottomRadiusPx.isFinite()) bottomRadiusPx.coerceIn(0f, maxRadius) else 0f
    val exponent = if (n.isFinite() && n > 0f) n else SQUIRCLE_N_DEFAULT

    if (tr <= 0f && br <= 0f) return Rectangle2D.Float(0f, 0f, w, h)

    val e = 2.0 / exponent.toDouble()
    val seg = SQUIRCLE_SEGMENTS
    val p = Path2D.Double()
    p.moveTo(tr.toDouble(), 0.0)
    p.lineTo((w - tr).toDouble(), 0.0)
    appendSquircleCorner(p, (w - tr).toDouble(), tr.toDouble(), tr.toDouble(), e, seg, 0.0, -1.0, 1.0, 0.0)
    p.lineTo(w.toDouble(), (h - br).toDouble())
    appendSquircleCorner(p, (w - br).toDouble(), (h - br).toDouble(), br.toDouble(), e, seg, 1.0, 0.0, 0.0, 1.0)
    p.lineTo(br.toDouble(), h.toDouble())
    appendSquircleCorner(p, br.toDouble(), (h - br).toDouble(), br.toDouble(), e, seg, 0.0, 1.0, -1.0, 0.0)
    p.lineTo(0.0, tr.toDouble())
    appendSquircleCorner(p, tr.toDouble(), tr.toDouble(), tr.toDouble(), e, seg, -1.0, 0.0, 0.0, -1.0)
    p.closePath()
    return p
}

private fun appendSquircleCorner(
    p: Path2D.Double,
    ccx: Double,
    ccy: Double,
    r: Double,
    e: Double,
    seg: Int,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
) {

    if (r <= 0.0) {
        p.lineTo(ccx, ccy)
        return
    }
    for (i in 1..seg) {
        val a = (Math.PI / 2.0) * i / seg
        val ca = Math.pow(Math.cos(a), e)
        val sa = Math.pow(Math.sin(a), e)
        p.lineTo(ccx + r * (ax * ca + bx * sa), ccy + r * (ay * ca + by * sa))
    }
}

const val EXPAND_CORNER_RADIUS_PX = 16f

data class IslandOutline(
    val topRadiusPx: Float,
    val bottomRadiusPx: Float,
)

fun expandedOutlineRadii(
    shapeType: String,
    collapsedTopRadiusPx: Float,
    collapsedBottomRadiusPx: Float,
): IslandOutline = when (shapeType) {
    "SQUARE" -> IslandOutline(0f, 0f)
    "NOTCH" -> IslandOutline(collapsedTopRadiusPx, collapsedBottomRadiusPx)

    "COVER_ONLY" -> IslandOutline(collapsedTopRadiusPx, collapsedBottomRadiusPx)
    else -> IslandOutline(EXPAND_CORNER_RADIUS_PX, EXPAND_CORNER_RADIUS_PX)
}

fun islandOutlineAt(
    shapeType: String,
    cornerRadiusPercent: Int,
    radiusBasisPx: Float,
    fraction: Float,
): IslandOutline {
    val collapsedTop = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, true)
    val collapsedBottom = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, false)
    val expanded = expandedOutlineRadii(shapeType, collapsedTop, collapsedBottom)
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    return IslandOutline(
        topRadiusPx = collapsedTop + (expanded.topRadiusPx - collapsedTop) * f,
        bottomRadiusPx = collapsedBottom + (expanded.bottomRadiusPx - collapsedBottom) * f,
    )
}

fun spectrumEdgeInset(
    baseInset: Float,
    edgeRadiusPx: Float,
    distanceFromEdgePx: Float,
): Float {
    val base = if (baseInset.isFinite()) baseInset.coerceAtLeast(0f) else 0f
    if (!edgeRadiusPx.isFinite() || edgeRadiusPx <= 0f) return base
    val r = edgeRadiusPx
    val d = if (distanceFromEdgePx.isFinite()) distanceFromEdgePx.coerceIn(0f, r) else 0f

    val arc = r - sqrt((r * r - (r - d) * (r - d)).coerceAtLeast(0f))
    return maxOf(base, arc)
}

fun spectrumBandInset(
    shapeType: String,
    cornerRadiusPercent: Int,
    radiusBasisPx: Float,
    areaTop: Float,
    areaHeight: Float,
    bandTop: Float,
    bandBottom: Float,
    baseInset: Float,
): Float = spectrumBandInsetForRadii(
    topRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, true),
    bottomRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, false),
    areaTop = areaTop,
    areaHeight = areaHeight,
    bandTop = bandTop,
    bandBottom = bandBottom,
    baseInset = baseInset,
)

fun spectrumBandInsetForRadii(
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    areaTop: Float,
    areaHeight: Float,
    bandTop: Float,
    bandBottom: Float,
    baseInset: Float,
): Float = maxOf(

    spectrumEdgeInset(
        baseInset = baseInset,
        edgeRadiusPx = topRadiusPx,
        distanceFromEdgePx = bandTop - areaTop,
    ),

    spectrumEdgeInset(
        baseInset = baseInset,
        edgeRadiusPx = bottomRadiusPx,
        distanceFromEdgePx = (areaTop + areaHeight) - bandBottom,
    ),
)

fun spectrumBandInsetFor(
    slotOwn: Boolean,
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    areaTop: Float,
    areaHeight: Float,
    bandTop: Float,
    bandBottom: Float,
    baseInset: Float,
): Float = if (slotOwn) {
    if (baseInset.isFinite()) baseInset.coerceAtLeast(0f) else 0f
} else {
    spectrumBandInsetForRadii(
        topRadiusPx = topRadiusPx,
        bottomRadiusPx = bottomRadiusPx,
        areaTop = areaTop,
        areaHeight = areaHeight,
        bandTop = bandTop,
        bandBottom = bandBottom,
        baseInset = baseInset,
    )
}

const val SPECTRUM_SECONDARY_MODE_OFF = "OFF"

const val SPECTRUM_SECONDARY_MODE_BOTTOM = "BOTTOM"

const val SPECTRUM_SECONDARY_MODE_TOP = "TOP"

const val SPECTRUM_SECONDARY_MODE_BOTH = "BOTH"

fun secondarySpectrumBands(
    mode: String,
    areaTop: Float,
    areaHeight: Float,
    bandHeight: Float,
): List<Pair<Float, Boolean>> {
    val bottom = mode == SPECTRUM_SECONDARY_MODE_BOTTOM || mode == SPECTRUM_SECONDARY_MODE_BOTH
    val top = mode == SPECTRUM_SECONDARY_MODE_TOP || mode == SPECTRUM_SECONDARY_MODE_BOTH
    val out = ArrayList<Pair<Float, Boolean>>(2)

    if (bottom) out += ((areaTop + areaHeight) - bandHeight) to false

    if (top) out += areaTop to true
    return out
}

const val ISLAND_VERTICAL_PADDING_PX = 10f

const val ISLAND_HEIGHT_FOLLOW_FONT_DEFAULT = true

const val ISLAND_HEIGHT_RATIO_DEFAULT_PERCENT = 120

fun islandCollapsedHeightPx(
    fontSize: Int,
    rows: Int,
    lineGapPx: Float,
    translationRowHeightPx: Float,
    followFont: Boolean = ISLAND_HEIGHT_FOLLOW_FONT_DEFAULT,
    heightRatioPercent: Int = ISLAND_HEIGHT_RATIO_DEFAULT_PERCENT,
): Int {
    val lineHeight = fontSize * LINE_HEIGHT_RATIO
    val gap = if (lineGapPx.isFinite()) lineGapPx.coerceAtLeast(0f) else 0f
    val step = lineHeight + gap
    val base = step * (rows - 1).coerceAtLeast(0) + lineHeight
    val extra = if (translationRowHeightPx.isFinite()) translationRowHeightPx.coerceAtLeast(0f) else 0f

    val block = base + extra
    val total = if (followFont) {
        block + ISLAND_VERTICAL_PADDING_PX * 2f
    } else {
        block * (heightRatioPercent / 100f)
    }
    return total.toInt()
}

const val TRANSLATION_GAP_PX = 8f

fun lineBoxTrimPx(fontSize: Int, translationScale: Float): Float {
    val f = if (fontSize > 0) fontSize.toFloat() else 0f
    val s = if (translationScale.isFinite() && translationScale > 0f) translationScale else 1f
    return f * (LINE_HEIGHT_RATIO - 1f) / 2f * (1f + s)
}

fun translationExtraGapPx(fontSize: Int, translationScale: Float, lineGapPx: Float): Float {
    val g = if (lineGapPx.isFinite()) lineGapPx.coerceAtLeast(0f) else 0f
    val trim = lineBoxTrimPx(fontSize, translationScale)

    val t = (g / TRANSLATION_GAP_PX).coerceIn(0f, 1f)

    return TRANSLATION_GAP_PX * t + g - trim * (1f - t)
}

const val HORIZONTAL_INSET = 14f

fun lyricMainLineHeightPx(fontSize: Int): Float = fontSize * LINE_HEIGHT_RATIO

fun lyricSubLineHeightPx(fontSize: Int, translationScale: Float): Float =
    fontSize * translationScale * LINE_HEIGHT_RATIO

fun translationRowHeightPx(fontSize: Int, translationScale: Float, lineGapPx: Float): Float =
    lyricSubLineHeightPx(fontSize, translationScale) +

        translationExtraGapPx(fontSize, translationScale, lineGapPx)

fun translationCenterOffsetPx(
    fontSize: Int,
    translationScale: Float,
    above: Boolean,
    lineGapPx: Float,
): Float {
    val d = lyricMainLineHeightPx(fontSize) / 2f +

        translationExtraGapPx(fontSize, translationScale, lineGapPx) +
        lyricSubLineHeightPx(fontSize, translationScale) / 2f
    return if (above) -d else d
}

fun lyricFirstCenterY(
    areaTop: Float,
    areaHeight: Float,
    anchorIndex: Int,
    rows: Int,
    lineHeightPx: Float,
    subRowHeightPx: Float,
    translationAbove: Boolean,
): Float {
    val centerY = areaTop + areaHeight / 2f
    if (subRowHeightPx == 0f) {
        return centerY - anchorIndex * lineHeightPx
    }
    val subRowShift = if (translationAbove) subRowHeightPx else 0f
    return centerY - ((rows - 1) * lineHeightPx + subRowHeightPx) / 2f + subRowShift
}

const val COVER_MIN_OUTER_SIZE_PX = 20f

const val COVER_MAX_OUTER_SIZE_PX = 56f

fun deviceScaleOf(g: Graphics2D): Float {
    val tx = runCatching { g.deviceConfiguration?.defaultTransform }.getOrNull()
    val s = tx?.scaleX?.toFloat() ?: 1f
    return if (s.isFinite() && s > 0f) s else 1f
}

fun bitmapRenderPx(logicalSizePx: Float, deviceScale: Float): Int {
    val l = if (logicalSizePx.isFinite() && logicalSizePx > 0f) logicalSizePx else 0f
    val s = if (deviceScale.isFinite() && deviceScale > 0f) deviceScale else 1f
    return kotlin.math.ceil(l * s).toInt().coerceAtLeast(1)
}

fun leadingCoverOuterSizePx(areaHeightPx: Float, scaleWithFont: Boolean): Float {
    val h = if (areaHeightPx.isFinite()) areaHeightPx else 0f

    val box = dev.cdl.render.sideContentSizePx(h)

    val floor = COVER_MIN_OUTER_SIZE_PX.coerceAtMost(box)

    val sized = box.coerceAtLeast(floor)
    return if (scaleWithFont) sized else sized.coerceAtMost(COVER_MAX_OUTER_SIZE_PX)
}

fun leadingCoverAreaLeftPx(
    outerSizePx: Float,
    leadingInsetPx: Float,
    leadingGapPx: Float,
    slotWidthPx: Float = outerSizePx,
): Float {
    if (!outerSizePx.isFinite() || outerSizePx <= 0f) return 0f
    val inset = if (leadingInsetPx.isFinite()) leadingInsetPx.coerceAtLeast(0f) else 0f
    val gap = if (leadingGapPx.isFinite()) leadingGapPx.coerceAtLeast(0f) else 0f
    val slot = if (slotWidthPx.isFinite()) slotWidthPx.coerceAtLeast(0f) else 0f
    return inset + slot + gap
}

fun collapsedCoverInsetRel(leadingInsetPx: Float, leadingGapPx: Float): Float =
    leadingCoverAreaLeftPx(
        outerSizePx = COVER_MAX_OUTER_SIZE_PX,
        leadingInsetPx = leadingInsetPx,
        leadingGapPx = leadingGapPx,
        slotWidthPx = coverSlotWidth(COVER_MAX_OUTER_SIZE_PX, false),
    )

fun coverShapeCollapsedHeightPx(
    coverOuterSizePx: Float,
    verticalPaddingPx: Float = ISLAND_VERTICAL_PADDING_PX,
): Int {
    val size = if (coverOuterSizePx.isFinite() && coverOuterSizePx > 0f) {
        coverOuterSizePx
    } else {
        COVER_MIN_OUTER_SIZE_PX
    }
    val pad = if (verticalPaddingPx.isFinite()) verticalPaddingPx.coerceAtLeast(0f) else 0f
    return (size + 2f * pad).toInt()
}

fun coverShapeSkipsLyricChain(shapeType: String): Boolean = shapeType == "COVER_ONLY"

fun coverLockBlocksExpand(shapeType: String, locked: Boolean): Boolean =
    locked && coverShapeSkipsLyricChain(shapeType)

fun coverLockBadgeVisibleAt(nowNanos: Long, untilNanos: Long): Boolean =
    untilNanos > 0L && nowNanos < untilNanos

fun coverShapeForcesSpectrum(shapeType: String): Boolean = coverShapeSkipsLyricChain(shapeType)

fun noLyricsDisplay(lyricLineCount: Int, hasCurrentLine: Boolean): NoLyricsDisplay = when {

    hasCurrentLine -> NoLyricsDisplay.LYRIC

    lyricLineCount <= 0 -> NoLyricsDisplay.NO_LYRICS

    else -> NoLyricsDisplay.NOT_YET
}

enum class NoLyricsDisplay {

    LYRIC,

    NO_LYRICS,

    NOT_YET,
}

fun coverWaitElapsed(pending: Boolean, nowNanos: Long, deadlineNanos: Long): Boolean {

    if (!pending) return false

    if (deadlineNanos == 0L) return false
    return nowNanos >= deadlineNanos
}

fun l3WorkerKey(
    blurRadius: Int,
    frameLogEnabled: Boolean,
    refractionHeight: Int,
    refractionAmount: Int,
    dispersion: Int,
    saturation: Int,
    cornerRadiusTop: Double,
    cornerRadiusBottom: Double,
    gpuBackend: Boolean = false,
): String =
    "b$blurRadius|f$frameLogEnabled|" +
        "h$refractionHeight|a$refractionAmount|d$dispersion|s$saturation|" +
        "ct$cornerRadiusTop|cb$cornerRadiusBottom|g$gpuBackend"

fun currentLineSlots(currentLine: LyricLine?): List<LyricLine?> = listOf(currentLine)

fun lyricBaselineY(centerY: Float, ascent: Float, descent: Float): Float =
    centerY + (ascent - descent) / 2f

fun lyricCenterYFromBaseline(baselineY: Float, ascent: Float, descent: Float): Float =
    baselineY - (ascent - descent) / 2f

fun lyricTextAreaWidth(widthPx: Int, horizontalInsetPx: Float): Float =
    widthPx - 2f * horizontalInsetPx

fun lyricTextRegionLeft(areaLeft: Float, horizontalInsetPx: Float): Float =
    areaLeft + horizontalInsetPx

fun lyricTextRegionWidth(widthPx: Int, areaLeft: Float, horizontalInsetPx: Float): Float =
    (widthPx - areaLeft - 2f * horizontalInsetPx).coerceAtLeast(0f)

fun lyricAlignBaselineX(
    widthPx: Int,
    advance: Float,
    alignWire: String,
    areaLeft: Float,
    horizontalInsetPx: Float,
): Float = when (alignWire.trim().uppercase()) {
    "LEFT" -> areaLeft + horizontalInsetPx
    "RIGHT" -> (areaLeft + widthPx - horizontalInsetPx - advance)
        .coerceAtLeast(areaLeft + horizontalInsetPx)

    else -> areaLeft + (widthPx - advance) / 2f
}

const val WHEEL_VOLUME_STEP_PERCENT = 5

fun wheelVolumeStepPercent(rotation: Int, stepPercent: Int = WHEEL_VOLUME_STEP_PERCENT): Int {
    if (rotation == 0) return 0
    val step = stepPercent.coerceAtLeast(1)

    return if (rotation > 0) -step else step
}

fun applyWheelToVolume(
    currentVolume: Float,
    rotation: Int,
    stepPercent: Int = WHEEL_VOLUME_STEP_PERCENT,
): Float {
    if (!currentVolume.isFinite()) return 0f
    val delta = wheelVolumeStepPercent(rotation, stepPercent)
    if (delta == 0) return currentVolume.coerceIn(0f, 1f)
    val step = stepPercent.coerceAtLeast(1)
    val currentPercent = (currentVolume.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)

    val snapped = (currentPercent.toFloat() / step).roundToInt() * step
    val next = (snapped + delta).coerceIn(0, 100)
    return next / 100f
}

fun wheelVolumeActive(enabled: Boolean, hit: Boolean): Boolean = enabled && hit
