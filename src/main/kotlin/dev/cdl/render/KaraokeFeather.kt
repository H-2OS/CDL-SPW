// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import kotlin.math.min

const val FEATHER_FONT_RATIO = 0.45f

const val FEATHER_WIDTH_RATIO = 0.6f

const val MIN_FEATHER = 0.01f

class KaraokeFeatherGeometry internal constructor(

    val gradientLeft: Float,

    val gradientRight: Float,

    val clipRight: Float,
) {

    val bandWidth: Float get() = gradientRight - gradientLeft
}

fun karaokeFeatherPx(refFontSize: Float, segWidth: Float, featherScale: Float): Float =
    min(refFontSize * FEATHER_FONT_RATIO, segWidth * FEATHER_WIDTH_RATIO)
        .coerceAtLeast(MIN_FEATHER) * featherScale

fun karaokeSweepFrontRel(segStartRel: Float, segEndRel: Float, ratio: Float): Float =
    segStartRel + (segEndRel - segStartRel) * ratio.coerceIn(0f, 1f)

fun karaokeCompensatedFrontX(
    lineStartX: Float,
    advance: Float,
    frontX: Float,
    feather: Float,
): Float {

    if (advance <= 0f) return frontX
    val half = feather / 2f
    val scale = (advance + feather) / advance
    return lineStartX - half + (frontX - lineStartX) * scale
}

fun karaokeFeatherGeometry(
    lineStartX: Float,
    advance: Float,
    frontRel: Float,
    feather: Float,
): KaraokeFeatherGeometry {
    val half = feather / 2f
    val lineEndX = lineStartX + advance
    val frontAbs = karaokeCompensatedFrontX(lineStartX, advance, lineStartX + frontRel, feather)
    val left = frontAbs - half
    val right = frontAbs + half

    return KaraokeFeatherGeometry(left, right, min(right, lineEndX))
}
