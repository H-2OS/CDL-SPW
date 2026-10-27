// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

const val SPECTRUM_BAR_PITCH_PX = 7f

const val RIGHT_BAR_COUNT: Int = 5

const val RIGHT_SLOT_WIDTH_PX: Float = 56f

fun rightSlotExtraWidthPx(occupant: String, islandHeightPx: Float, coverOuterPx: Float = Float.NaN): Int {
    if (occupant == RIGHT_SLOT_NONE) return 0

    val extra = rightSlotWidthPx(occupant, islandHeightPx, coverOuterPx) - rightSlotBaseBandWidthPx(occupant)
    return if (extra > 0f) Math.round(extra) else 0
}

fun rightSlotBaseBandWidthPx(occupant: String): Int =
    if (occupant == RIGHT_SLOT_NONE) 0 else Math.round(RIGHT_BAR_COUNT * SPECTRUM_BAR_PITCH_PX)

fun naturalWidthForContentPx(
    widestPx: Float,
    coverInsetCollapsedPx: Float,
    occupant: String,
    islandHeightPx: Float,
    horizontalInsetPx: Float,
    glyphSlackPx: Float,
    coverOuterPx: Float = Float.NaN,
): Int {
    if (widestPx <= 0f) return 0
    val slotW = if (occupant == RIGHT_SLOT_NONE) 0f else rightSlotWidthPx(occupant, islandHeightPx, coverOuterPx)
    val sideInset = maxOf(
        if (coverInsetCollapsedPx.isFinite()) coverInsetCollapsedPx.coerceAtLeast(0f) else 0f,
        if (slotW.isFinite()) slotW.coerceAtLeast(0f) else 0f,
    )

    val panel = widestPx + 2f * sideInset + 2f * horizontalInsetPx + glyphSlackPx

    val extra = rightSlotTotalExtraWidthPx(
        occupant, islandHeightPx, coverInsetCollapsedPx, coverOuterPx,
    )
    return (panel - extra).toInt()
}

fun lyricCenteringExtraWidthPx(coverInsetRel: Float, rightSlotWidth: Float): Int {
    val l = if (coverInsetRel.isFinite()) coverInsetRel.coerceAtLeast(0f) else 0f
    val r = if (rightSlotWidth.isFinite()) rightSlotWidth.coerceAtLeast(0f) else 0f
    val diff = Math.abs(l - r)
    return if (diff > 0f) Math.round(diff) else 0
}

fun lyricSideInsetPx(leftInsetPx: Float, rightSlotWidthPx: Float): Float {
    val l = if (leftInsetPx.isFinite()) leftInsetPx.coerceAtLeast(0f) else 0f
    val r = if (rightSlotWidthPx.isFinite()) rightSlotWidthPx.coerceAtLeast(0f) else 0f
    return maxOf(l, r)
}

const val SLOT_BAND_POSITION_RATIO: Float = 0.5f

fun rightSlotBoxLeftPx(
    islandRightPx: Float,
    bandInsetPx: Float,
    boxWidthPx: Float,
    minLeftPx: Float = Float.NaN,
): Float {
    if (!islandRightPx.isFinite()) return 0f
    val band = if (bandInsetPx.isFinite()) bandInsetPx.coerceAtLeast(0f) else 0f
    val box = if (boxWidthPx.isFinite()) boxWidthPx.coerceAtLeast(0f) else 0f

    val b = maxOf(band, box)

    val slack = (b - box).coerceAtLeast(0f)
    val left = islandRightPx - b + slack * SLOT_BAND_POSITION_RATIO
    val floor = if (minLeftPx.isFinite()) minLeftPx else -Float.MAX_VALUE
    return left.coerceAtLeast(floor)
}

fun rightSlotTotalExtraWidthPx(
    occupant: String,
    islandHeightPx: Float,
    coverInsetRel: Float,
    coverOuterPx: Float = Float.NaN,
): Int {
    if (occupant == RIGHT_SLOT_NONE) return 0
    val slotW = rightSlotWidthPx(occupant, islandHeightPx, coverOuterPx)

    return rightSlotExtraWidthPx(occupant, islandHeightPx, coverOuterPx) +
        lyricCenteringExtraWidthPx(coverInsetRel, slotW)
}

fun rightSlotWidthPx(
    occupant: String,
    islandHeightPx: Float,

    coverOuterPx: Float = Float.NaN,
): Float = when (occupant) {

    RIGHT_SLOT_RADIAL, RIGHT_SLOT_RING -> islandHeightPx.coerceAtLeast(1f)

    else -> if (coverOuterPx.isFinite() && coverOuterPx > 0f) {
        coverOuterPx
    } else {

        sideContentSizePx(islandHeightPx).coerceAtLeast(1f)
    }
}

fun polarSlotArea(
    occupant: String,
    islandRightPx: Float,
    lyricsTopPx: Float,
    lyricsHeightPx: Float,

    bandInsetPx: Float,
    coverOuterPx: Float = Float.NaN,
): FloatArray {
    val h = if (lyricsHeightPx.isFinite()) lyricsHeightPx.coerceAtLeast(1f) else 1f
    val side = rightSlotWidthPx(occupant, h, coverOuterPx)
    val top = if (lyricsTopPx.isFinite()) lyricsTopPx else 0f
    return floatArrayOf(

        rightSlotBoxLeftPx(islandRightPx, bandInsetPx, side),
        top + (h - side) / 2f,
        side,
        side,
    )
}

public const val RIGHT_SLOT_NONE: String = "NONE"
public const val RIGHT_SLOT_SPECTRUM: String = "SPECTRUM"
public const val RIGHT_SLOT_CLOCK: String = "CLOCK"

public const val RIGHT_SLOT_RING: String = "RING"

public const val RIGHT_SLOT_RADIAL: String = "RADIAL"

fun rightSlotOccupant(slotKey: String, spectrumAnchor: String): String = when (slotKey) {

    RIGHT_SLOT_SPECTRUM, RIGHT_SLOT_CLOCK, RIGHT_SLOT_RADIAL, RIGHT_SLOT_RING -> slotKey
    else -> if (spectrumAnchor == SPECTRUM_ANCHOR_RIGHT) RIGHT_SLOT_SPECTRUM else RIGHT_SLOT_NONE
}

public const val SPECTRUM_ANCHOR_RIGHT: String = "RIGHT"

public fun needsSpectrumData(
    slotKey: String,
    spectrumAnchor: String,
    spectrumEnabled: Boolean,
): Boolean {

    if (spectrumEnabled) return true

    val occ = rightSlotOccupant(slotKey, spectrumAnchor)
    return occ == RIGHT_SLOT_SPECTRUM || occ == RIGHT_SLOT_RADIAL || occ == RIGHT_SLOT_RING
}

public fun shouldDrawSpectrumBand(
    spectrumAnchor: String,
    spectrumEnabled: Boolean,
): Boolean = spectrumAnchor != SPECTRUM_ANCHOR_RIGHT && spectrumEnabled

public fun shouldDrawSpectrumInSlot(slotOccupant: String): Boolean =
    slotOccupant == RIGHT_SLOT_SPECTRUM || slotOccupant == RIGHT_SLOT_RADIAL ||
        slotOccupant == RIGHT_SLOT_RING

fun rightSlotOccupied(slotKey: String, spectrumAnchor: String): Boolean =
    rightSlotOccupant(slotKey, spectrumAnchor) != RIGHT_SLOT_NONE

const val SPECTRUM_MIN_BARS = 24

const val SPECTRUM_MAX_BARS = 1024

const val SPECTRUM_AUTO_REFERENCE_BARS = 150f

const val SPECTRUM_AUTO_MIN_PITCH_PX = 0.1f

const val SPECTRUM_BAR_GAP_RATIO = 0.30f

fun spectrumAutoPitch(widthPx: Int): Float {
    if (widthPx <= 0) return SPECTRUM_BAR_PITCH_PX
    return (widthPx / SPECTRUM_AUTO_REFERENCE_BARS)
        .coerceIn(SPECTRUM_AUTO_MIN_PITCH_PX, SPECTRUM_BAR_PITCH_PX)
}

fun symmetricBandTop(areaTop: Float, areaHeight: Float, heightFraction: Float): Float {
    val bandHeight = (areaHeight * heightFraction).coerceAtLeast(1f)
    return areaTop + areaHeight / 2f - bandHeight / 2f
}

fun spectrumBarCount(
    widthPx: Int,
    pitchPx: Float = SPECTRUM_BAR_PITCH_PX,
    minBars: Int = SPECTRUM_MIN_BARS,
    maxBars: Int = SPECTRUM_MAX_BARS,
): Int {
    if (pitchPx <= 0f) return minBars
    if (maxBars < minBars) return minBars
    val raw = (widthPx / pitchPx).toInt()
    return raw.coerceIn(minBars, maxBars)
}

fun spectrumBarCountFor(
    widthPx: Int,
    pitchPx: Float,
    forceBarCount: Int,
): Int {
    if (forceBarCount > 0) return forceBarCount.coerceIn(1, SPECTRUM_MAX_BARS)
    return spectrumBarCount(widthPx, pitchPx = pitchPx)
}

fun slotSideBarCount(): Int = RIGHT_BAR_COUNT

fun slotSideBarCountFor(wire: String?): Int = when (wire?.trim()) {
    "4" -> 4
    "6" -> 6

    else -> RIGHT_BAR_COUNT
}

fun slotSideBarPitchPx(): Float = SPECTRUM_BAR_PITCH_PX

fun slotBarPitchPx(isPolar: Boolean, auto: Boolean, userPitchPx: Float): Float = when {
    !isPolar -> slotSideBarPitchPx()
    auto -> slotSideBarPitchPx()

    else -> if (userPitchPx.isFinite() && userPitchPx > 0f) userPitchPx else slotSideBarPitchPx()
}

fun polarCountPitchPx(userPitchPx: Float): Float =
    if (userPitchPx.isFinite() && userPitchPx > 0f) userPitchPx else slotSideBarPitchPx()

data class SpectrumBandGeometry(
    val top: Float,
    val bottom: Float,
    val growDown: Boolean,
) {

    val height: Float get() = bottom - top

    val baselineY: Float get() = if (growDown) top else bottom
}

fun spectrumBandGeometry(
    areaTop: Float,
    areaHeight: Float,
    heightFraction: Float,
    anchorTop: Boolean,
): SpectrumBandGeometry {
    val bandHeight = (areaHeight * heightFraction).coerceAtLeast(1f)
    return if (anchorTop) {

        SpectrumBandGeometry(top = areaTop, bottom = areaTop + bandHeight, growDown = true)
    } else {

        SpectrumBandGeometry(
            top = areaTop + areaHeight - bandHeight,
            bottom = areaTop + areaHeight,
            growDown = false,
        )
    }
}

fun stretchAroundMid(h: Float, scale: Float): Float {
    val v = h.coerceIn(0f, 1f)

    if (scale == 1f) return v
    return (0.5f + (v - 0.5f) * scale).coerceIn(0f, 1f)
}

const val NO_PROGRESS: Float = -1f

fun playbackProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return NO_PROGRESS

    if (positionMs <= 0L) return 0f
    return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

fun resampleBands(src: FloatArray, target: Int, out: FloatArray): FloatArray {
    val n = target.coerceAtMost(out.size)
    val m = src.size
    if (n <= 0) return out
    if (m == 0) {
        for (j in 0 until n) out[j] = 0f
        return out
    }
    if (m == 1) {
        val v = src[0]
        for (j in 0 until n) out[j] = v
        return out
    }
    if (n == 1) {
        out[0] = src[m - 1]
        return out
    }
    val span = (m - 1).toFloat()
    val step = span / (n - 1).toFloat()
    for (j in 0 until n) {
        val p = j * step
        val i0 = p.toInt().coerceIn(0, m - 1)
        val i1 = (i0 + 1).coerceAtMost(m - 1)
        val t = p - i0
        out[j] = src[i0] * (1f - t) + src[i1] * t
    }
    return out
}

fun spectrumBarSlot(slotPx: Float, solid: Boolean = false): Pair<Float, Float> {

    if (!slotPx.isFinite() || slotPx <= 0f) return 0.05f to 0.05f

    if (solid) return 0f to slotPx
    val floorPx = minOf(0.25f, slotPx / 2f)
    val gap = (slotPx * 0.30f).coerceAtLeast(floorPx)
    val barWidth = (slotPx - gap).coerceAtLeast(floorPx)
    return gap to barWidth
}

fun isSolidSpectrumPitch(pitchPx: Float): Boolean = !(pitchPx > 0f)

enum class SpectrumBarCorner(

    val cornerFraction: Float,
) {

    ROUND(1f),

    SQUARE(0f),
    ;

    companion object {

        val DEFAULT = ROUND

        fun parse(key: String?): SpectrumBarCorner =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

fun spectrumBarCornerPx(
    barWidthPx: Float,
    maxCornerPx: Float,
    solid: Boolean,
    corner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,
): Float {
    if (solid) return 0f
    if (!barWidthPx.isFinite() || !maxCornerPx.isFinite()) return 0f
    val full = barWidthPx.coerceAtMost(maxCornerPx)

    return full * corner.cornerFraction
}

fun spectrumBarRectX(
    x0: Float,
    index: Int,
    slotPx: Float,
    gapPx: Float,
    barWidthPx: Float,
    solid: Boolean,
): Pair<Float, Float> {
    val leftExact = x0 + index * slotPx
    if (!solid) return (leftExact + gapPx / 2f) to barWidthPx

    val left = Math.round(leftExact).toFloat()
    val right = Math.round(x0 + (index + 1) * slotPx).toFloat()
    return left to (right - left)
}

fun spectrumPolylinePoints(
    x0: Float,
    count: Int,
    slotPx: Float,
    heights: FloatArray,
    baseY: Float,
    out: FloatArray,
    mirrorAxisY: Float = Float.NaN,
): Int {    if (count <= 0) return 0
    if (!x0.isFinite() || !slotPx.isFinite() || !baseY.isFinite()) return 0
    if (slotPx <= 0f) return 0
    val n = minOf(count, heights.size)
    if (n <= 0) return 0
    val mirror = mirrorAxisY.isFinite()

    val need = if (mirror) (2 * n + 6) * 2 else (n + 3) * 2
    if (out.size < need) return 0

    var w = 0

    out[w++] = spectrumBarRectX(x0, 0, slotPx, 0f, slotPx, solid = true).first
    out[w++] = baseY

    var lastRight = out[0]
    var lastTop = baseY
    for (i in 0 until n) {
        val (left, width) = spectrumBarRectX(x0, i, slotPx, 0f, slotPx, solid = true)
        val h = if (heights[i].isFinite()) heights[i].coerceAtLeast(0f) else 0f
        out[w++] = left
        out[w++] = baseY - h
        lastRight = left + width
        lastTop = baseY - h
    }

    out[w++] = lastRight
    out[w++] = lastTop

    out[w++] = lastRight
    out[w++] = baseY
    if (!mirror) return n + 3

    val axis2 = 2f * mirrorAxisY
    val written = w / 2
    var k = written - 1
    while (k >= 1) {
        out[w++] = out[k * 2]
        out[w++] = axis2 - out[k * 2 + 1]
        k--
    }
    return written + (written - 1)
}

fun spectrumPolarPolylinePoints(
    spec: SpectrumPolarSpec,
    count: Int,
    heights: FloatArray,
    out: FloatArray,
): Int {
    if (count <= 0) return 0
    if (!spec.cx.isFinite() || !spec.cy.isFinite() || !spec.maxLength.isFinite()) return 0
    val n = minOf(count, heights.size)
    if (n <= 0) return 0
    if (out.size < 4 * n) return 0
    val inner = spec.innerRadius.coerceAtLeast(0f)
    var w = 0

    for (i in 0 until n) {
        val h = if (heights[i].isFinite()) heights[i].coerceAtLeast(0f) else 0f

        val r = (inner + h).coerceIn(0f, spec.maxLength)
        val rad = Math.toRadians(polarAngleDeg(spec, i, n).toDouble())
        out[w++] = spec.cx + r * Math.sin(rad).toFloat()
        out[w++] = spec.cy - r * Math.cos(rad).toFloat()
    }

    for (i in n - 1 downTo 0) {
        val rad = Math.toRadians(polarAngleDeg(spec, i, n).toDouble())
        out[w++] = spec.cx + inner * Math.sin(rad).toFloat()
        out[w++] = spec.cy - inner * Math.cos(rad).toFloat()
    }
    return w / 2
}

fun spectrumPolylineCornerPx(
    segmentWidthPx: Float,
    maxCornerPx: Float,
    corner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,
): Float {
    if (!segmentWidthPx.isFinite() || !maxCornerPx.isFinite()) return 0f
    val full = segmentWidthPx.coerceAtMost(maxCornerPx) / 2f
    if (full <= 0f) return 0f
    return full * corner.cornerFraction
}

fun isSpectrumVisuallySilent(peak: Float, halfSpan: Float, minBarHeight: Float): Boolean {
    if (!peak.isFinite() || !halfSpan.isFinite() || !minBarHeight.isFinite()) return true
    return peak * halfSpan <= minBarHeight
}

fun spectrumFadeBandPx(
    spanPx: Float,

    cornerRadiusPx: Float = 0f,
): Float {
    val byShape = cornerRadiusPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (!spanPx.isFinite()) return maxOf(SPECTRUM_FADE_BAND_MAX_PX, byShape)
    val h = spanPx.coerceAtLeast(1f)
    val base = (h * SPECTRUM_FADE_BAND_RATIO).coerceAtMost(SPECTRUM_FADE_BAND_MAX_PX)
        .coerceAtLeast(1f)

    return maxOf(base, byShape)
}

const val SPECTRUM_FADE_BAND_RATIO = 0.30f

const val SPECTRUM_FADE_BAND_MAX_PX = 6f

const val SPECTRUM_MAX_FADE_FRACTION = 0.5f

fun spectrumFadeStartFraction(bandHeightPx: Float): Float {
    if (!bandHeightPx.isFinite() || bandHeightPx <= 0f) return 1f
    val band = spectrumFadeBandPx(bandHeightPx)
    val fraction = (band / bandHeightPx).coerceAtMost(SPECTRUM_MAX_FADE_FRACTION)
    return (1f - fraction).coerceIn(SPECTRUM_MAX_FADE_FRACTION, 1f)
}

fun spectrumBarPieces(
    topTip: Float,
    bottomTip: Float,
    symmetric: Boolean,
    out: FloatArray,
): Int {
    if (out.size < 4) return 0
    if (!topTip.isFinite() || !bottomTip.isFinite()) return 0
    if (bottomTip <= topTip) {

        out[0] = topTip
        out[1] = topTip + 1f
        return 1
    }
    if (!symmetric) {
        out[0] = topTip
        out[1] = bottomTip
        return 1
    }
    val axis = (topTip + bottomTip) / 2f
    out[0] = topTip
    out[1] = axis
    out[2] = axis
    out[3] = bottomTip
    return 2
}

data class SpectrumPolarSpec(
    val cx: Float,
    val cy: Float,
    val maxLength: Float,
    val innerRadiusRatio: Float,
    val startAngleDeg: Float = 0f,
    val sweepDeg: Float = 360f,
) {

    val innerRadius: Float
        get() = (maxLength * innerRadiusRatio.coerceIn(0f, MAX_INNER_RADIUS_RATIO))
            .coerceIn(0f, maxLength * MAX_INNER_RADIUS_RATIO)

    companion object {

        const val MAX_INNER_RADIUS_RATIO = 0.85f

        const val CIRCULAR_INNER_RATIO = 0.55f

        const val RADIAL_INNER_RATIO = 0f
    }
}

fun polarAngleDeg(spec: SpectrumPolarSpec, index: Int, count: Int): Float {
    if (count <= 0) return spec.startAngleDeg
    val step = spec.sweepDeg / count
    return spec.startAngleDeg + step * (index + 0.5f)
}

fun isRadialFromCenter(spec: SpectrumPolarSpec): Boolean = spec.innerRadius <= 0f

fun polarBarCount(spec: SpectrumPolarSpec, pitchPx: Float): Int {

    val minVisiblePitchPx = 2.14f

    val spacingRadius = polarSpacingRadiusPx(spec)
        .coerceAtLeast(spec.maxLength * 0.35f)
    val circ = (2.0 * Math.PI * spacingRadius).toFloat()
    if (!circ.isFinite() || circ <= 0f) return 1
    val pitch = if (pitchPx.isFinite() && pitchPx >= minVisiblePitchPx) pitchPx else minVisiblePitchPx
    return (circ / pitch).toInt().coerceIn(1, SPECTRUM_MAX_BARS)
}

fun spectrumPolarFor(
    occupant: String,
    left: Float,
    top: Float,
    width: Float,
    height: Float,

    radialInnerPercent: Int,
    ringInnerPercent: Int,
    outerPercent: Int,
): SpectrumPolarSpec? {

    val ratio = polarInnerRatioFor(occupant, radialInnerPercent, ringInnerPercent) ?: return null
    val maxLength = (minOf(width, height) / 2f * polarOuterRatioOf(outerPercent))
        .coerceAtLeast(1f)
    return SpectrumPolarSpec(
        cx = left + width / 2f,
        cy = top + height / 2f,
        maxLength = maxLength,
        innerRadiusRatio = ratio,
    )
}

fun polarInnerRatioFor(
    occupant: String,
    radialInnerPercent: Int,
    ringInnerPercent: Int,
): Float? = when (occupant.trim().uppercase()) {
    RIGHT_SLOT_RADIAL -> polarInnerRatioOf(radialInnerPercent)
    RIGHT_SLOT_RING -> polarInnerRatioOf(ringInnerPercent)
    else -> null
}

fun polarInnerRatioOf(percent: Int): Float =
    (percent / 100f).coerceIn(0f, SpectrumPolarSpec.MAX_INNER_RADIUS_RATIO)

fun polarOuterRatioOf(percent: Int): Float = (percent.coerceIn(10, 100) / 100f)

fun polarSymmetricAxisY(spec: SpectrumPolarSpec): Float =
    spec.cy - (spec.innerRadius + spec.maxLength) / 2f

fun polarSymmetricHalfLengthPx(spec: SpectrumPolarSpec, value: Float): Float =
    ((spec.maxLength - spec.innerRadius) / 2f).coerceAtLeast(1f) * value

const val POLAR_RADIAL_INNER_PERCENT_DEFAULT: Int = 0

const val POLAR_RING_INNER_PERCENT_DEFAULT: Int = 55

const val POLAR_OUTER_PERCENT_DEFAULT: Int = 100

fun isPolarSlotOccupant(occupant: String): Boolean =
    occupant == RIGHT_SLOT_RADIAL || occupant == RIGHT_SLOT_RING

public const val POLAR_STYLE_BARS: String = "BARS"

public const val POLAR_STYLE_LINE: String = "LINE"

public const val POLAR_STYLE_DEFAULT: String = POLAR_STYLE_BARS

fun polarLineStyleFor(wire: String?): Boolean =
    wire?.trim()?.equals(POLAR_STYLE_LINE, ignoreCase = true) == true

const val POLAR_LINE_BAR_WIDTH_RATIO: Float = 0.30f

const val POLAR_LINE_MIN_BAR_WIDTH_PX: Float = 1.5f

fun polarLineBarSlot(slotPx: Float): Pair<Float, Float> {
    if (!slotPx.isFinite() || slotPx <= 0f) return 0.05f to 0.05f
    val floorPx = minOf(POLAR_LINE_MIN_BAR_WIDTH_PX, slotPx / 2f)
    val barWidth = (slotPx * POLAR_LINE_BAR_WIDTH_RATIO)
        .coerceAtLeast(floorPx)
        .coerceAtMost(slotPx)
    return (slotPx - barWidth) to barWidth
}

fun smallSpectrumAreaPx(coverTopPx: Float, coverOuterSizePx: Float): FloatArray {
    val h = if (coverOuterSizePx.isFinite()) coverOuterSizePx.coerceAtLeast(1f) else 1f
    val top = if (coverTopPx.isFinite()) coverTopPx else 0f
    return floatArrayOf(top, h)
}

fun polarSpacingRadiusPx(spec: SpectrumPolarSpec): Float =
    if (spec.innerRadius > 0f) spec.innerRadius else spec.maxLength

const val SIDE_GAP_RATIO: Float = 0.12f

const val SLOT_INSET_RATIO: Float = 0.177f

fun slotSpectrumInsetPx(boxWidthPx: Float): Float {
    val box = if (boxWidthPx.isFinite()) boxWidthPx.coerceAtLeast(0f) else 0f

    return box * SLOT_INSET_RATIO
}

fun minInkPxFor(islandHeightPx: Float): Float {
    val h = if (islandHeightPx.isFinite()) islandHeightPx.coerceAtLeast(0f) else 0f
    return minOf(SevenSegment.MIN_SEGMENT_PX, h * CLOCK_INK_RELATIVE_MIN)
}

const val CLOCK_INK_RELATIVE_MIN: Float = 0.02f

fun sideGapPx(islandHeightPx: Float): Float {
    val h = if (islandHeightPx.isFinite()) islandHeightPx.coerceAtLeast(0f) else 0f
    return h * SIDE_GAP_RATIO
}

fun sideContentSizePx(islandHeightPx: Float): Float {
    val h = if (islandHeightPx.isFinite()) islandHeightPx.coerceAtLeast(0f) else 0f

    return (h - 2f * sideGapPx(h)).coerceAtLeast(0f)
}
