// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.cover.CoverColorExtractor
import dev.cdl.render.CoverBackdrop
import dev.cdl.render.CoverPalette
import dev.cdl.render.CoverScaler
import dev.cdl.render.SpectrumBarCorner
import dev.cdl.render.barSegmentPath
import dev.cdl.render.isSolidSpectrumPitch
import dev.cdl.render.levelColorArgb
import dev.cdl.render.spectrumBarCornerPx
import dev.cdl.render.spectrumBarRectX
import dev.cdl.render.spectrumBarSlot
import dev.cdl.render.spectrumPolylineCornerPx
import dev.cdl.render.spectrumPolylinePath
import dev.cdl.render.spectrumPolylinePoints
import dev.cdl.window.BACKGROUND_BASE_RGB
import dev.cdl.window.BACKGROUND_MODE_COVER_IMAGE
import dev.cdl.window.DissolveMask
import dev.cdl.window.bitmapRenderPx
import dev.cdl.window.deviceScaleOf
import dev.cdl.window.effectiveCornerStyle
import dev.cdl.window.leadingCoverOuterSizePx
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import dev.cdl.font.HostFonts

import dev.cdl.settings.UNSUNG_TRANSLATION_DIM
import dev.cdl.settings.dimPackedArgb
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class IslandPreviewPane(

    private var snapshot: PreviewSnapshot,

    private val targetKey: String,
) : JPanel() {

    private var editedColor: Int? = null

    private val demoProgress = 0.55f

    private var coverMaskCache: BufferedImage? = null
    private var coverMaskKey: Int = -1

    private val previewBarPath = Path2D.Float()

    private val previewPolylinePath = Path2D.Float()

    init {
        isOpaque = false

        preferredSize = Dimension(476, 120)
        minimumSize = Dimension(260, 96)
    }

    fun applyEditedColor(argb: Int) {
        if (editedColor == argb) return
        editedColor = argb
        repaint()
    }

    fun updateSnapshot(next: PreviewSnapshot) {
        if (next == snapshot) return
        snapshot = next
        coverMaskCache = null
        coverMaskKey = -1
        repaint()
    }

    private fun targets(prefix: String): Boolean = previewTargetMatches(targetKey, prefix)

    private fun sung(): Int =
        if (targets("main_lyric.fill.color")) {
            editedColor ?: snapshot.sungColor
        } else {
            dev.cdl.render.CoverPalette.resolve(
                snapshot.lyricColorMode,
                Color(snapshot.sungColor, true),
                demoCoverRgb(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
                dev.cdl.render.CoverPalette::brightFrom,
            ).rgb and 0xFFFFFF
        }

    private fun unsung(): Int {
        if (targets("main_lyric.fill.unsung")) {
            return editedColor ?: snapshot.unsungColor
        }
        val rgb = demoCoverRgb(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE)
        return if (snapshot.lyricColorMode == dev.cdl.render.CoverPalette.COVER && rgb != null) {
            dev.cdl.render.CoverPalette.dim(
                dev.cdl.render.CoverPalette.brightFrom(rgb),
                dev.cdl.window.UNSUNG_LYRIC_DIM,
            ).rgb and 0xFFFFFF
        } else {
            snapshot.unsungColor
        }
    }

    private fun translation(): Int =
        if (targets("main_lyric.translation.color")) {
            editedColor ?: snapshot.translationColor
        } else {

            dev.cdl.render.CoverPalette.resolve(
                snapshot.translationColorMode,
                Color(snapshot.translationColor, true),
                demoCoverRgb(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
                dev.cdl.render.CoverPalette::brightFrom,
            ).rgb and 0xFFFFFF
        }

    private fun translationUnsung(): Int = dimPackedArgb(translation(), UNSUNG_TRANSLATION_DIM)

    private fun spectrum(): Int =
        if (targets("spectrum.color")) {
            editedColor ?: snapshot.spectrumColor
        } else {

            dev.cdl.render.CoverPalette.resolve(
                snapshot.spectrumColorMode,
                Color(snapshot.spectrumColor, true),
                demoCoverRgb(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
                dev.cdl.render.CoverPalette::brightFrom,
            ).rgb and 0xFFFFFF
        }

    private fun slotSpectrumColorMode(): String =
        dev.cdl.window.rightSlotSpectrumColorModeFor(snapshot.rightSlotSpectrumColorMode)

    private fun slotSpectrum(): Int {
        if (targets("island.right_slot.spectrum_color")) {
            editedColor?.let { return it }
        }
        return dev.cdl.window.rightSlotSpectrumColorFor(
            slotSpectrumColorMode(),
            Color(spectrum(), true),
            Color(snapshot.rightSlotSpectrumColor, true),
            demoCoverRgb(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
        ).rgb and 0xFFFFFF
    }

    private fun border(): Int =
        if (targets("island.border.color")) {
            editedColor ?: snapshot.borderColor
        } else {

            dev.cdl.render.CoverPalette.resolve(
                snapshot.borderColorMode,
                Color(snapshot.borderColor, true),
                demoCoverRgb(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
                dev.cdl.render.CoverPalette::brightFrom,
            ).rgb and 0xFFFFFF
        }

    private fun mainOutline(): PreviewSnapshot.OutlineSpec? = snapshot.mainOutline?.let { spec ->
        if (targets("main_lyric.outline")) {
            val c = editedColor ?: spec.color
            spec.copy(color = c)
        } else {
            spec
        }
    }

    private fun translationOutline(): PreviewSnapshot.OutlineSpec? =
        snapshot.translationOutline?.let { spec ->
            if (targets("translation.outline")) spec.copy(color = editedColor ?: spec.color) else spec
        }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val inset = 12f
        val islandW = w - inset * 2
        val islandH = h - inset * 2

        val shape: Shape = islandShape(snapshot, inset, islandW, islandH)

        val previewRadii = previewIslandRadiiPx(snapshot.shapeType, snapshot.cornerRadius, islandW, islandH)

        val bgAlpha = (snapshot.backgroundOpacity.coerceIn(0, 100) * 255 / 100)

        val isLightTheme = ToolWindowPalette.isLight

        g2.color = backdropLeftColor(isLightTheme)
        g2.fillRect(0, 0, (w / 2f).toInt() + 1, h.toInt())
        g2.color = backdropRightColor(isLightTheme)
        g2.fillRect((w / 2f).toInt(), 0, (w - w / 2f).toInt() + 1, h.toInt())

        val previewCoverRgb = if (
            dev.cdl.window.needsCoverImageFor(
                coverEnabled = snapshot.coverEnabled,
                lyricColorMode = snapshot.lyricColorMode,
                backgroundColorMode = snapshot.backgroundColorMode,
                spectrumColorMode = snapshot.spectrumColorMode,

                slotSpectrumColorMode = dev.cdl.window.rightSlotSpectrumColorModeFor(
                    snapshot.rightSlotSpectrumColorMode,
                ),

                borderColorMode = snapshot.borderColorMode,
                gradientStyle = snapshot.materialGradientStyle,

                translationColorMode = snapshot.translationColorMode,
            )
        ) {
            demoCoverRgb(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE)
        } else {
            null
        }
        val islandBase = Color(
            previewBaseColorArgb(
                mode = snapshot.backgroundColorMode,
                manualArgb = snapshot.backgroundColorArgb,
                coverRgb = previewCoverRgb,
                opacityPercent = snapshot.backgroundOpacity,
            ),
            true,
        )

        val previewBackdropSrc =
            if (snapshot.backgroundColorMode == BACKGROUND_MODE_COVER_IMAGE && bgAlpha > 0) {
                demoCover()
            } else {
                null
            }
        if (previewBackdropSrc != null) {
            drawCoverImageBackdrop(g2, shape, previewBackdropSrc, previewCoverRgb, inset, islandW, islandH)
        } else {
            g2.color = islandBase
            g2.fill(shape)
        }

        if (previewBackdropSrc == null) {

            dev.cdl.render.MaterialLayers.gpuEnabled = snapshot.materialRenderGpuBackend
            dev.cdl.render.MaterialLayers.paint(
                g = g2,
                shape = shape,
                highlightStrength = snapshot.materialHighlightStrength,
                gradientStyle = snapshot.materialGradientStyle,

                flowPaletteArgb = demoCoverPalette(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),

                flowPaletteShares = demoCoverPaletteShares(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),

                flowPhase = 0f,
                gradientTintPercent = snapshot.materialGradientTintPercent,
                gradientAngleDeg = snapshot.materialGradientAngleDeg,
                gradientEndArgb = snapshot.materialGradientEndArgb,
                baseColor = islandBase,
                noiseAmplitude = snapshot.materialNoiseAmplitude,

                quality = snapshot.materialQuality,

                liquidGlarePercent = snapshot.materialLiquidGlarePercent,
                liquidDepthEffect = snapshot.materialLiquidDepthEffect,
                liquidSurfaceAlphaPercent = snapshot.materialLiquidSurfaceAlpha,

                shapeCornerRadius = previewRadii.first.toDouble(),
                shapeCornerRadiusBottom = previewRadii.second.toDouble(),

                shapeRoundness = dev.cdl.render.GlassSdf.DEFAULT_ROUNDNESS,

                glassAnimating = false,

                surfaceOpacityPercent = snapshot.backgroundOpacity,

                surfaceSuppressed = false,
            )
        }

            if (dev.cdl.render.shouldDrawSpectrumBand(
                    snapshot.spectrumAnchor,
                    snapshot.spectrumEnabled,
                )
            ) {
                drawStaticSpectrum(
                    g2, inset + 4f, inset + islandH - 20f, islandW - 8f, 16f,
                    symmetric = snapshot.spectrumSymmetric,
                    coverPalette = demoCoverPalette(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
                    paletteShares = demoCoverPaletteShares(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),

                    colorMode = snapshot.spectrumColorMode,
                    colorRgb = spectrum(),

                    frameOpacity = minOf(
                        dev.cdl.window.spectrumAlphas(
                            snapshot.spectrumOpacityPct, 0, false,
                        ).frameOpacity,
                        PREVIEW_MAIN_BAND_MAX_FRAME_OPACITY,
                    ),

                    islandBox = null,
                )
            }

        if (snapshot.borderEnabled &&
            snapshot.borderMode == dev.cdl.settings.BORDER_MODE_EDGE_DISSOLVE &&
            snapshot.backgroundOpacity != 0
        ) {
            val dissolveBand = dev.cdl.settings.dissolveBandPx(
                islandH, snapshot.borderDissolveBandPercent,
            )
            if (dissolveBand > 0f) {

                val mask = DissolveMask.get(
                    widthPx = islandW.toInt(),
                    heightPx = islandH.toInt(),
                    bandPx = dissolveBand,
                    topRadiusPx = previewRadii.first,
                    bottomRadiusPx = previewRadii.second,
                    cornerStyle = effectiveCornerStyle(snapshot.shapeType, snapshot.cornerStyle),
                    cornerN = snapshot.cornerN,

                    subpixelXPx = inset,
                    subpixelYPx = inset,
                )
                if (mask != null) {
                    val savedComposite = g2.composite
                    val savedClipDissolve = g2.clip
                    try {

                        g2.clip = shape

                        g2.composite = java.awt.AlphaComposite.DstIn

                        g2.drawImage(
                            mask.image,
                            inset.toInt() - mask.padPx,
                            inset.toInt() - mask.padPx,
                            null,
                        )
                    } finally {
                        g2.composite = savedComposite
                        g2.clip = savedClipDissolve
                    }
                }
            }
        }

        val oldClip = g2.clip
        g2.clip = shape
        try {
            var textLeft = inset + 10f

            if (snapshot.coverEnabled) {
                val cover = demoCover()
                if (cover != null) {

                    val outer = coverSideFor(islandH, snapshot.coverScaleWithFont)

                    val ring = previewVinylRingWidth(outer, snapshot.coverVinyl)

                    val content = previewCoverContentSize(outer, snapshot.coverVinyl)

                    val cx = coverContentLeft(inset, islandH, outer, snapshot.coverVinyl)
                    val cy = coverContentTop(inset, islandH, outer, snapshot.coverVinyl)
                    drawMaskedCover(
                        g2, cover, cx, cy, content, snapshot.coverShape, snapshot.shapeType,
                        snapshot.coverSharpness,
                    )

                    drawVinylRing(
                        g2,
                        coverMaskShape(
                            snapshot.coverShape, snapshot.shapeType, content,
                            cx.roundToInt().toFloat(), cy.roundToInt().toFloat(),
                        ),
                        ring,
                    )

                    textLeft = coverTextLeft(inset, islandH, outer, snapshot.coverVinyl)
                }
            }

            val rightBand = dev.cdl.render.rightSlotOccupied(
                snapshot.rightSlotComponent,
                snapshot.spectrumAnchor,
            )

            val specOccupant = dev.cdl.render.rightSlotOccupant(
                snapshot.rightSlotComponent,
                snapshot.spectrumAnchor,
            )
            val specBandW = dev.cdl.render.rightSlotWidthPx(
                specOccupant,

                islandH,

                coverOuterPx = dev.cdl.window.leadingCoverOuterSizePx(
                    islandH,
                    snapshot.coverScaleWithFont,
                ),
            )

            val slotBandInset = dev.cdl.render.lyricSideInsetPx(
                (textLeft - inset).coerceAtLeast(0f),
                if (rightBand) specBandW else 0f,
            )

            val slotX = if (rightBand) {
                dev.cdl.render.rightSlotBoxLeftPx(
                    islandRightPx = inset + islandW,
                    bandInsetPx = slotBandInset,
                    boxWidthPx = specBandW,

                    minLeftPx = inset + islandW - slotBandInset,
                )
            } else {
                inset + 4f
            }

            val slotW = if (rightBand) specBandW else islandW - 8f

            val slotClock = snapshot.rightSlotComponent == dev.cdl.render.RIGHT_SLOT_CLOCK

            if (slotClock) {
                val sampleMs = 45_240_000L
                val txt = dev.cdl.render.ClockText.format(
                    sampleMs, java.time.ZoneOffset.UTC, snapshot.clockHourFormat,
                )
                val style = dev.cdl.render.ClockGlyphs.normalizeStyle(snapshot.clockStyle)

                val dw = dev.cdl.render.ClockGlyphs.fitDigitWidth(
                    style, txt, slotW, dev.cdl.render.minInkPxFor(islandH),
                )
                if (dw != null) {
                    val dh = dev.cdl.render.ClockGlyphs.digitHeightPx(style, dw)

                    val textW = dw * dev.cdl.render.ClockGlyphs.totalWidthRatio(style, txt)

                    val clockBoxLeft = dev.cdl.render.rightSlotBoxLeftPx(
                        islandRightPx = inset + islandW,
                        bandInsetPx = slotBandInset,
                        boxWidthPx = slotW,
                        minLeftPx = inset + islandW - slotBandInset,
                    )
                    val textLeft = clockBoxLeft + (slotW - textW) / 2f

                    val polys = dev.cdl.render.ClockGlyphs.centeredTextPolygons(
                        style, txt, clockBoxLeft, inset, slotW, islandH,

                        colonVisible = true,

                        minInkPx = dev.cdl.render.minInkPxFor(islandH),
                    )
                    val oldPaint = g2.paint

                    g2.paint = java.awt.Color(snapshot.sungColor or 0xFF000000.toInt(), true)
                    for (poly in polys) {
                        val path = java.awt.geom.Path2D.Float()
                        path.moveTo(poly[0], poly[1])
                        for (i in 2 until poly.size step 2) path.lineTo(poly[i], poly[i + 1])
                        path.closePath()
                        g2.fill(path)
                    }
                    g2.paint = oldPaint
                }
            }

            if (dev.cdl.render.shouldDrawSpectrumInSlot(specOccupant)) {

                if (dev.cdl.render.isPolarSlotOccupant(specOccupant)) {

                    val coverOuter = coverSideFor(islandH, snapshot.coverScaleWithFont)
                    val side = coverOuter

                    val slotRight = inset + islandW

                    val savedClipSlot = g2.clip
                    g2.clip = shape
                    try {
                    drawStaticSpectrum(

                        g2, dev.cdl.render.rightSlotBoxLeftPx(slotRight, slotBandInset, side),
                        inset + (islandH - side) / 2f, side, side,

                        symmetric = specOccupant == dev.cdl.render.RIGHT_SLOT_RING,

                        forcePitchPx = dev.cdl.render.slotSideBarPitchPx(),

                        polarLine = dev.cdl.render.polarLineStyleFor(snapshot.rightSlotPolarStyle),
                        coverPalette = demoCoverPalette(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
                        paletteShares = demoCoverPaletteShares(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),

                        colorMode = slotSpectrumColorMode(),
                        colorRgb = slotSpectrum(),

                        frameOpacity = slotFrameOpacity(),

                        islandBox = floatArrayOf(inset, inset, islandW, islandH),
                    )
                    } finally {
                        g2.clip = savedClipSlot
                    }
                } else {

                    val coverOuter = coverSideFor(islandH, snapshot.coverScaleWithFont)
                    val coverTop = inset + (islandH - coverOuter) / 2f

                    val slotPolarNow = dev.cdl.render.isPolarSlotOccupant(
                        dev.cdl.render.rightSlotOccupant(
                            snapshot.rightSlotComponent,
                            snapshot.spectrumAnchor,
                        ),
                    )
                    val slotInset = if (slotPolarNow) {
                        0f
                    } else {
                        dev.cdl.render.slotSpectrumInsetPx(specBandW)
                    }
                    drawStaticSpectrum(
                        g2, slotX + slotInset, coverTop,
                        (specBandW - 2f * slotInset).coerceAtLeast(1f), coverOuter,
                        symmetric = true,
                        forceBars = dev.cdl.render.slotSideBarCountFor(
                            snapshot.rightSlotSpectrumBars,
                        ),

                        forcePitchPx = dev.cdl.render.slotSideBarPitchPx(),
                        coverPalette = demoCoverPalette(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),
                        paletteShares = demoCoverPaletteShares(snapshot.coverShape == dev.cdl.window.COVER_SHAPE_CIRCLE),

                        colorMode = slotSpectrumColorMode(),
                        colorRgb = slotSpectrum(),

                        frameOpacity = slotFrameOpacity(),

                        islandBox = floatArrayOf(inset, inset, islandW, islandH),
                    )
                }
            }

            val previewRightInset = if (rightBand) specBandW else 0f
            val previewSideInset = dev.cdl.render.lyricSideInsetPx(
                textLeft - inset,
                previewRightInset,
            )
            textLeft = inset + previewSideInset
            val textRight = if (rightBand) {
                (inset + islandW - previewSideInset).coerceAtLeast(textLeft + 40f)
            } else {
                inset + islandW - 10f
            }
            val avail = max(40f, textRight - textLeft)

            val fontScale = (islandW / PREVIEW_REFERENCE_W).coerceIn(0.5f, 1.6f)

            val mainFont = fitToWidth(
                PreviewSnapshot.SAMPLE_MAIN,
                resolveFont(snapshot.mainFontSize * fontScale),
                avail,
                g2.fontRenderContext,
            )
            val subFont = fitToWidth(
                PreviewSnapshot.SAMPLE_TRANSLATION,
                resolveFont(
                    max(
                        9f,
                        snapshot.mainFontSize * snapshot.translationScale * fontScale,
                    ),
                ),
                avail,
                g2.fontRenderContext,
            )

            val mainBaseline = inset + islandH * 0.38f
            val subBaseline = inset + islandH * 0.68f
            drawKaraokeLine(
                g2, PreviewSnapshot.SAMPLE_MAIN, mainFont, textLeft, mainBaseline, avail,
                mainOutline(),

                mode = snapshot.karaokeMode,
            )

            drawKaraokeLine(
                g2, PreviewSnapshot.SAMPLE_TRANSLATION, subFont, textLeft, subBaseline, avail,
                translationOutline(),

                mode = snapshot.karaokeMode,
                sungColor = translation(),
                unsungColor = translationUnsung(),
            )
        } finally {
            g2.clip = oldClip
        }

        if (snapshot.borderEnabled) {
            val bc = Color(border() or 0xFF000000.toInt(), true)
            val previewStrokePx = 1.5f
            if (snapshot.borderMode == dev.cdl.settings.BORDER_MODE_FADE_OUT) {
                for (layer in dev.cdl.settings.borderFadeLayers(previewStrokePx)) {
                    if (layer.alpha <= 0) continue

                    val ring = previewIslandShapeCore(
                        snapshot.shapeType, snapshot.cornerRadius,
                        inset + layer.insetPx,
                        (islandW - layer.insetPx * 2f).coerceAtLeast(1f),
                        (islandH - layer.insetPx * 2f).coerceAtLeast(1f),
                        snapshot.cornerStyle, snapshot.cornerN,
                    )
                    g2.stroke = BasicStroke(layer.widthPx)
                    g2.color = Color(bc.red, bc.green, bc.blue, layer.alpha)
                    g2.draw(ring)
                }
            } else if (snapshot.borderMode == dev.cdl.settings.BORDER_MODE_EDGE_DISSOLVE) {

            }
        }
    }

    private fun fitToWidth(
        text: String,
        font: Font,
        avail: Float,
        frc: java.awt.font.FontRenderContext,
        minScale: Float = MIN_FIT_SCALE,
    ): Font {
        val scale = fitScale(text, font, avail, frc, minScale)

        return if (scale >= 1f) font else font.deriveFont(font.size2D * scale)
    }

    private fun drawKaraokeLine(
        g2: Graphics2D, text: String, font: Font,
        x: Float, baseline: Float, avail: Float,
        outline: PreviewSnapshot.OutlineSpec?,

        mode: String,
        sungColor: Int = sung(),
        unsungColor: Int = unsung(),
    ) {
        val layout = java.awt.font.TextLayout(text, font, g2.fontRenderContext)
        val drawX = alignX(x, avail, layout.advance)
        if (outline != null && outline.widthPx > 0f) {
            val oldStroke = g2.stroke
            g2.color = Color(outline.color or 0xFF000000.toInt(), true)

            g2.stroke = previewOutlineStrokeFor(outline.widthPx)
            g2.draw(layout.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(drawX.toDouble(), baseline.toDouble())))
            g2.stroke = oldStroke
        }

        val split = previewKaraokeSplitX(layout, drawX, mode)
        val oldClip = g2.clip
        g2.clip = java.awt.Rectangle(
            oldClip.bounds.intersection(java.awt.Rectangle(drawX.roundToInt() - 4, baseline.roundToInt() - 60, split - drawX.roundToInt() + 4, 90)),
        )
        g2.color = Color(sungColor or 0xFF000000.toInt(), true)
        layout.draw(g2, drawX, baseline)
        g2.clip = java.awt.Rectangle(
            oldClip.bounds.intersection(java.awt.Rectangle(split, baseline.roundToInt() - 60, (drawX + layout.advance).roundToInt() - split + 4, 90)),
        )
        g2.color = Color(unsungColor or 0xFF000000.toInt(), true)
        layout.draw(g2, drawX, baseline)
        g2.clip = oldClip
    }

    private fun previewKaraokeSplitX(
        layout: java.awt.font.TextLayout, drawX: Float, mode: String,
    ): Int = when (mode) {
        dev.cdl.settings.KARAOKE_MODE_LINE ->
            if (demoProgress > 0f) (drawX + layout.advance).roundToInt() else drawX.roundToInt()
        dev.cdl.settings.KARAOKE_MODE_SMOOTH ->
            (drawX + layout.advance * demoProgress).roundToInt()
        else -> {

            val target = layout.advance * demoProgress
            var lastX = drawX
            for (i in 1..layout.characterCount) {
                val right = layout.getLogicalHighlightShape(0, i).bounds2D.maxX.toFloat()
                if (right <= target) lastX = drawX + right else break
            }
            lastX.roundToInt()
        }
    }

    private fun islandShape(snapshot: PreviewSnapshot, inset: Float, w: Float, h: Float): Shape =
        previewIslandShapeCore(
            snapshot.shapeType, snapshot.cornerRadius, inset, w, h,

            snapshot.cornerStyle, snapshot.cornerN,
        )

    private fun drawCoverImageBackdrop(
        g2: Graphics2D,
        shape: Shape,
        src: BufferedImage,
        coverRgb: Int?,
        inset: Float,
        islandW: Float,
        islandH: Float,
    ) {

        val w = islandW.roundToInt()
        val h = islandH.roundToInt()
        if (w <= 0 || h <= 0) return
        val percent = snapshot.backgroundOpacity.coerceIn(0, 100)

        if (percent <= 0) return

        val maskBase = coverRgb?.let { CoverPalette.darkFrom(it) } ?: Color(BACKGROUND_BASE_RGB)
        val maskArgb = (CoverBackdrop.MASK_ALPHA shl 24) or (maskBase.rgb and 0xFFFFFF)

        val baked = CoverBackdrop.baked(
            src = src,
            targetW = w,
            targetH = h,
            angleDeg = snapshot.backgroundCoverAngleDeg,
            maskArgb = maskArgb,

            blurRadius = snapshot.materialBlurRadius,
            downsample = if (snapshot.materialQuality == "LITE") 4 else 1,
        )

        val oldClip = g2.clip
        val oldComposite = g2.composite
        try {
            g2.clip = shape
            if (percent < 100) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, percent / 100f)
            }

            g2.drawImage(baked, inset.roundToInt(), inset.roundToInt(), null)
        } finally {

            g2.composite = oldComposite
            g2.clip = oldClip
        }
    }

    private fun drawMaskedCover(
        g2: Graphics2D, cover: BufferedImage, x: Float, y: Float, side: Float,
        coverShape: String, islandShapeType: String,

        sharpness: Int,
    ) {

        val px = bitmapRenderPx(side, deviceScaleOf(g2))

        val key = ((px and 0xFF) shl 24) or ((sharpness and 0x7F) shl 17) or
            ((coverShape.hashCode() and 0x7F) shl 10) or
            (islandShapeType.hashCode() and 0x3FF)
        var tmp = coverMaskCache
        if (tmp == null || coverMaskKey != key) {

            val mask = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
            val mg = mask.createGraphics()
            mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            mg.color = Color.WHITE
            mg.fill(coverMaskShape(coverShape, islandShapeType, px.toFloat()))
            mg.dispose()

            val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
            val tg = img.createGraphics()

            tg.drawImage(CoverScaler.squareScaled(cover, px, sharpness), 0, 0, null)
            tg.composite = AlphaComposite.DstIn
            tg.drawImage(mask, 0, 0, null)
            tg.dispose()
            coverMaskCache = img
            coverMaskKey = key
            tmp = img
        }

        g2.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )

        val dst = coverDestRectFor(x, y, side)
        g2.drawImage(tmp, dst[0], dst[1], dst[2], dst[3], null)
    }

    private fun coverMaskShape(
        coverShape: String, islandShapeType: String, side: Float,
        x: Float = 0f, y: Float = 0f,
    ): Shape = when (coverShape) {

        COVER_SHAPE_CIRCLE -> Ellipse2D.Float(x, y, side, side)

        COVER_SHAPE_SQUARE -> Rectangle2D.Float(x, y, side, side)
        else -> {

            val arc = side * if (islandShapeType == "SQUARE") COVER_ARC_RATIO_SQUARE else COVER_ARC_RATIO
            RoundRectangle2D.Float(x, y, side, side, arc, arc)
        }
    }

    private fun drawVinylRing(g2: Graphics2D, shape: Shape, ring: Float) {
        if (ring <= 0f) return

        g2.color = VINYL_RING_COLOR
        g2.fill(dev.cdl.render.CoverRing.ringArea(shape, ring))
    }

    private fun slotFrameOpacity(): Float =
        dev.cdl.window.spectrumAlphas(
            snapshot.spectrumOpacityPct, 0, false,
        ).frameOpacity

    private val PREVIEW_MAIN_BAND_MAX_FRAME_OPACITY = 0.55f

    private fun alignX(left: Float, avail: Float, advance: Float): Float = when (snapshot.contentAlign) {
        "LEFT" -> left
        "RIGHT" -> left + max(0f, avail - advance)
        else -> left + max(0f, (avail - advance) / 2f)
    }

    private fun drawStaticSpectrum(
        g2: Graphics2D, x: Float, y: Float, w: Float, h: Float,

        symmetric: Boolean,

        forceBars: Int = 0,

        forcePitchPx: Float = 0f,

        polarLine: Boolean = false,

        coverPalette: List<Int>,

        paletteShares: FloatArray? = null,

        colorMode: String,

        colorRgb: Int,

        frameOpacity: Float,

        islandBox: FloatArray?,
    ) {

        val polar = dev.cdl.render.spectrumPolarFor(
            dev.cdl.render.rightSlotOccupant(
                snapshot.rightSlotComponent,
                snapshot.spectrumAnchor,
            ),
            x, y, w, h,

            snapshot.rightSlotRadialInnerPercent,
            snapshot.rightSlotRingInnerPercent,
            snapshot.rightSlotPolarOuterPercent,
        )

        val bars = if (polar != null) {

            dev.cdl.render.polarBarCount(
                polar,
                dev.cdl.render.polarCountPitchPx(
                    snapshot.spectrumBarPitchTenthPx / 10f,
                ),
            )
        } else if (forceBars > 0) {

            dev.cdl.render.spectrumBarCountFor(
                w.toInt(), dev.cdl.render.SPECTRUM_BAR_PITCH_PX, forceBars,
            )
        } else {
            PREVIEW_SPECTRUM_BARS
        }

        val slot = if (polar != null) {
            (2.0 * Math.PI * polar.innerRadius).toFloat() / bars
        } else {
            w / bars
        }

        val pitchPx = if (forcePitchPx > 0f) {
            forcePitchPx
        } else {
            snapshot.spectrumBarPitchTenthPx / 10f
        }
        val solid = isSolidSpectrumPitch(pitchPx)

        val (gap, barWidth) = if (polarLine && !solid) {
            dev.cdl.render.polarLineBarSlot(slot)
        } else {
            spectrumBarSlot(slot, solid)
        }

        val cornerEnum = SpectrumBarCorner.parse(snapshot.spectrumBarCorner)
        val shapeEnum = SpectrumBarCorner.parse(
            if (snapshot.spectrumBarShape.equals("ROUND", true)) "ROUND" else "SQUARE",
        )
        val barShapeRatio = shapeEnum.cornerFraction

        val previewSpecAlpha = (frameOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

        val specRgb = colorRgb or 0xFF000000.toInt()

        val specBaseArgb = (previewSpecAlpha shl 24) or (specRgb and 0xFFFFFF)

        val levelMode = colorMode == dev.cdl.render.CoverPalette.LEVEL

        val flowMode = colorMode == dev.cdl.render.CoverPalette.COVER_FLOW
        val camoMode = colorMode == dev.cdl.render.CoverPalette.COVER_CAMO
        val multiColorBar = dev.cdl.window.isMultiColorSpectrumMode(colorMode)
        g2.color = Color(specRgb, true).let {
            Color(it.red, it.green, it.blue, previewSpecAlpha)
        }

        val camoPaint: java.awt.Paint? = if (camoMode && islandBox != null) {
            dev.cdl.render.camoTexturePaint(
                islandBox[0].toDouble(), islandBox[1].toDouble(),
                islandBox[2].toDouble(), islandBox[3].toDouble(),
                islandHeightPx = islandBox[3].toInt(),
                paletteArgb = coverPalette,
                alpha = previewSpecAlpha,
                phase = 0f,

                shares = paletteShares,
            )
        } else {
            null
        }
        if (camoPaint != null) g2.paint = camoPaint
        val axisY = y + h / 2f

        if (solid) {

            val baseY = if (symmetric) axisY else y + h
            val heights = FloatArray(bars) { i ->
                val t = i.toFloat() / (bars - 1)
                val env = PREVIEW_SPECTRUM_ENV_MIN +
                    (1f - PREVIEW_SPECTRUM_ENV_MIN) * kotlin.math.sin(t * Math.PI).toFloat()

                val full = when {
                    polar != null -> (polar.maxLength - polar.innerRadius) * env
                    symmetric -> (h / 2f) * env
                    else -> h * env
                }
                full.coerceAtLeast(1f)
            }

            if (polar != null) {
                val outP = FloatArray(2 * bars * 2)
                val np = dev.cdl.render.spectrumPolarPolylinePoints(
                    spec = polar, count = bars, heights = heights, out = outP,
                )
                if (np > 0) {
                    val contour = java.awt.geom.Path2D.Float()
                    contour.moveTo(outP[0].toDouble(), outP[1].toDouble())
                    for (k in 1 until np) {
                        contour.lineTo(outP[k * 2].toDouble(), outP[k * 2 + 1].toDouble())
                    }
                    contour.closePath()
                    g2.fill(contour)
                }
                return
            }

            val out = FloatArray(if (symmetric) (2 * bars + 6) * 2 else (bars + 3) * 2)
            val n = spectrumPolylinePoints(
                x, bars, slot, heights, baseY, out,
                mirrorAxisY = if (symmetric) baseY else Float.NaN,
            )
            if (n > 0) {

                val jointArc = spectrumPolylineCornerPx(slot, PREVIEW_MIN_BAR_HEIGHT, cornerEnum) *
                    barShapeRatio

                if (multiColorBar) {
                    val flowPaint = if (camoMode) {
                        dev.cdl.render.flowCamoPaint(
                            x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(),
                            0, dev.cdl.render.CAMO_BLOCK_COUNT,
                            coverPalette, previewSpecAlpha, phase = 0f,
                        )
                    } else {
                        dev.cdl.render.flowGradientPaint(
                            x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(),
                            0, coverPalette, previewSpecAlpha, phase = 0f,
                        )
                    }
                    if (flowPaint != null) g2.paint = flowPaint
                }
                if (spectrumPolylinePath(previewPolylinePath, out, n, jointArc)) {
                    g2.fill(previewPolylinePath)
                }

                if (multiColorBar) g2.color = Color(specRgb, true).let {
                    Color(it.red, it.green, it.blue, previewSpecAlpha)
                }
            }
            return
        }

        if (polar != null && solid) {
            val hs = FloatArray(bars) { i ->
                val t = if (bars > 1) i.toFloat() / (bars - 1) else 0.5f
                val env = PREVIEW_SPECTRUM_ENV_MIN +
                    (1f - PREVIEW_SPECTRUM_ENV_MIN) * kotlin.math.sin(t * Math.PI).toFloat()
                (polar.maxLength - polar.innerRadius) * env
            }
            val pts = FloatArray(2 * bars * 2)
            val np = dev.cdl.render.spectrumPolarPolylinePoints(
                spec = polar, count = bars, heights = hs, out = pts,
            )
            if (np > 0) {
                val path = java.awt.geom.Path2D.Float()
                path.moveTo(pts[0].toDouble(), pts[1].toDouble())
                for (k in 1 until np) {
                    path.lineTo(pts[k * 2].toDouble(), pts[k * 2 + 1].toDouble())
                }
                path.closePath()
                g2.fill(path)
            }
            return
        }

        val baseY = when {

            polar != null && symmetric -> dev.cdl.render.polarSymmetricAxisY(polar)
            polar != null -> polar.cy - polar.innerRadius
            symmetric -> axisY
            else -> y + h
        }
        for (i in 0 until bars) {
            val t = i.toFloat() / (bars - 1)

            val env = PREVIEW_SPECTRUM_ENV_MIN +
                (1f - PREVIEW_SPECTRUM_ENV_MIN) * kotlin.math.sin(t * Math.PI).toFloat()

            val polarSaved = if (polar != null) g2.transform else null
            if (polar != null) {
                g2.rotate(
                    Math.toRadians(
                        dev.cdl.render.polarAngleDeg(polar, i, bars).toDouble(),
                    ),
                    polar.cx.toDouble(),
                    polar.cy.toDouble(),
                )
            }

            val (bxSlot, bw) = spectrumBarRectX(x, i, slot, gap, barWidth, solid = false)

            val bx = if (polar != null) polar.cx - bw / 2f else bxSlot

            val half = when {

                polar != null -> dev.cdl.render.polarSymmetricHalfLengthPx(polar, env)
                symmetric -> (h / 2f) * env
                else -> 0f
            }

            val bh = when {
                polar != null -> (polar.maxLength - polar.innerRadius) * env
                symmetric -> half * 2f
                else -> h * env
            }

            val cornerCapPx = bw / 2f
            val rawArc = spectrumBarCornerPx(bw, cornerCapPx, solid = false, cornerEnum)
            val arc = rawArc * barShapeRatio

            if (levelMode) {
                g2.color = Color(levelColorArgb(specBaseArgb, env), true)
            }

            if (multiColorBar && camoPaint == null) {
                g2.color = Color(
                    if (camoMode) {
                        dev.cdl.render.spectrumCamoColorAt(
                            specBaseArgb, i, bars, coverPalette, 0f,
                        )
                    } else {
                        dev.cdl.render.spectrumFlowColorAt(
                            specBaseArgb, i, bars, coverPalette, 0f, paletteShares,
                        )
                    },
                    true,
                )
            }
            if (symmetric) {

                val arcTall = arc.coerceAtMost(bh / 2f)
                g2.fill(
                    java.awt.geom.RoundRectangle2D.Float(
                        bx, baseY - half, bw, bh, arcTall * 2f, arcTall * 2f,
                    ),
                )
            } else {

                barSegmentPath(
                    previewBarPath, bx, baseY - bh, bw, bh, arc,
                    roundTop = true, roundBottom = false,
                )
                g2.fill(previewBarPath)
            }

            if (polarSaved != null) g2.transform = polarSaved
        }
    }

    private fun resolveFont(size: Float): Font = runCatching {

        val base = dev.cdl.font.resolveFontOrFallback(snapshot.fontFamily, size.roundToInt())

        if (snapshot.fontBold) base.deriveFont(Font.BOLD) else base
    }.getOrNull() ?: Font(Font.SANS_SERIF, Font.PLAIN, size.roundToInt())

    companion object {

        private const val PREVIEW_REFERENCE_W = 460f

        private const val PREVIEW_SPECTRUM_BARS = 64

        private const val PREVIEW_SPECTRUM_ENV_MIN = 0.25f

        private const val PREVIEW_MIN_BAR_HEIGHT = 2f

        private const val PREVIEW_TRANSLATION_SCALE = TRANSLATION_SCALE_DEFAULT

        const val MIN_FIT_SCALE = 0.05f

        private const val NOTCH_BOTTOM_RATIO = 0.12f

        private const val COVER_SHAPE_CIRCLE = "CIRCLE"

        private const val COVER_SHAPE_SQUARE = "SQUARE"

        private const val COVER_ARC_RATIO = 0.18f

        private const val VINYL_RING_OVER_OUTER = 1f / 6f

        private val VINYL_RING_COLOR = Color(18, 18, 18)

        private fun previewVinylRingWidth(outerSize: Float, vinyl: Boolean): Float {
            if (!vinyl) return 0f
            if (outerSize <= 0f) return 0f
            return outerSize * VINYL_RING_OVER_OUTER
        }

        private fun previewCoverContentSize(outerSize: Float, vinyl: Boolean): Float {
            if (outerSize <= 0f) return 0f
            return outerSize - 2f * previewVinylRingWidth(outerSize, vinyl)
        }

        fun coverVinylRingWidthFor(outerSize: Float, vinyl: Boolean): Float =
            previewVinylRingWidth(outerSize, vinyl)

        fun coverContentSizeFor(outerSize: Float, vinyl: Boolean): Float =
            previewCoverContentSize(outerSize, vinyl)

        fun coverShapeSquareFor(): String = COVER_SHAPE_SQUARE

        private fun previewIslandRadiiPx(
            shapeType: String,
            cornerRadius: Int,
            w: Float,
            h: Float,
        ): Pair<Float, Float> {

            val basis = dev.cdl.window.islandRadiusBasisPx(w, h)
            fun edge(top: Boolean): Float = dev.cdl.window.islandEdgeRadiusPx(
                shapeType = shapeType,
                cornerRadiusPercent = cornerRadius,
                radiusBasisPx = basis,
                atTopEdge = top,
            )
            return edge(true) to edge(false)
        }

        private fun previewIslandShapeCore(
            shapeType: String,
            cornerRadius: Int,
            inset: Float,
            w: Float,
            h: Float,
            cornerStyle: String,
            cornerN: Int,
        ): Shape {

            val (topRadiusPx, bottomRadiusPx) = previewIslandRadiiPx(shapeType, cornerRadius, w, h)
            fun rounded(radius: Float): Shape =
                RoundRectangle2D.Float(inset, inset, w, h, radius * 2f, radius * 2f)

            fun squircle(top: Float, bottom: Float): Shape =
                java.awt.geom.AffineTransform
                    .getTranslateInstance(inset.toDouble(), inset.toDouble())
                    .createTransformedShape(
                        dev.cdl.window.squirclePath(w, h, top, bottom, cornerN.toFloat()),
                    )
            val style = dev.cdl.window.effectiveCornerStyle(shapeType, cornerStyle)

            fun translate(s: Shape): Shape =
                java.awt.geom.AffineTransform
                    .getTranslateInstance(inset.toDouble(), inset.toDouble())
                    .createTransformedShape(s)

            return when (shapeType) {
                "PILL" -> rounded(topRadiusPx)

                "SQUARE" -> Rectangle2D.Float(inset, inset, w, h)
                "NOTCH" -> if (style == "SQUIRCLE") {

                    squircle(topRadiusPx, bottomRadiusPx)
                } else {
                    val topRadius = topRadiusPx
                    val bottomRadius = bottomRadiusPx
                    val outer = Area(rounded(topRadius))
                    if (bottomRadius < topRadius) {
                        val cb = topRadius * 2f
                        val left = inset
                        val top = inset + h - cb
                        val right = inset + w - cb
                        val leftBottom = Area(Rectangle2D.Float(left, top, cb, cb)).apply {
                            subtract(Area(RoundRectangle2D.Float(left, top, cb, cb, topRadius * 2f, topRadius * 2f)))
                            add(Area(RoundRectangle2D.Float(left, top, cb, cb, bottomRadius * 2f, bottomRadius * 2f)))
                        }
                        val rightBottom = Area(Rectangle2D.Float(right, top, cb, cb)).apply {
                            subtract(Area(RoundRectangle2D.Float(right, top, cb, cb, topRadius * 2f, topRadius * 2f)))
                            add(Area(RoundRectangle2D.Float(right, top, cb, cb, bottomRadius * 2f, bottomRadius * 2f)))
                        }
                        outer.subtract(Area(Rectangle2D.Float(left, top, cb, cb)))
                        outer.subtract(Area(Rectangle2D.Float(right, top, cb, cb)))
                        outer.add(leftBottom)
                        outer.add(rightBottom)
                    }
                    outer
                }

                else -> if (style == "SQUIRCLE") squircle(topRadiusPx, topRadiusPx) else rounded(topRadiusPx)
            }
        }

        fun islandShapeFor(
            shapeType: String,
            cornerRadius: Int,
            inset: Float,
            w: Float,
            h: Float,
            cornerStyle: String,
            cornerN: Int,
        ): Shape = previewIslandShapeCore(shapeType, cornerRadius, inset, w, h, cornerStyle, cornerN)

        fun notchBottomRatioFor(): Float = NOTCH_BOTTOM_RATIO

        private fun coverContentLeft(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float =

            inset + dev.cdl.render.sideGapPx(islandH) + previewVinylRingWidth(outer, vinyl)

        private fun coverContentTop(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float =
            inset + (islandH - outer) / 2f + previewVinylRingWidth(outer, vinyl)

        private fun coverTextLeft(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float {

            val gap = dev.cdl.render.sideGapPx(islandH)
            return inset + gap + outer + gap
        }

        fun coverContentLeftFor(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float =
            coverContentLeft(inset, islandH, outer, vinyl)

        fun coverTextLeftFor(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float =
            coverTextLeft(inset, islandH, outer, vinyl)

        fun coverContentTopFor(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float =
            coverContentTop(inset, islandH, outer, vinyl)

        private const val COVER_ARC_RATIO_SQUARE = 0.06f

        private val PREVIEW_BACKDROP_LIGHT = Color(0xE8, 0xE8, 0xEC)

        private val PREVIEW_BACKDROP_DARK = Color(0x2C, 0x2C, 0x31)

        fun backdropLeftColor(isLightTheme: Boolean): Color =
            if (isLightTheme) PREVIEW_BACKDROP_LIGHT else PREVIEW_BACKDROP_DARK

        fun backdropRightColor(isLightTheme: Boolean): Color =
            if (isLightTheme) PREVIEW_BACKDROP_DARK else PREVIEW_BACKDROP_LIGHT

        private var coverCache: BufferedImage? = null
        private var coverTried = false

        private fun demoCover(): BufferedImage? {
            if (!coverTried) {
                coverTried = true
                coverCache = runCatching {
                    IslandPreviewPane::class.java.getResourceAsStream("/demo/cover-demo.jpg")
                        ?.use { ImageIO.read(it) }
                }.getOrNull()
            }
            return coverCache
        }

        fun demoCoverAvailable(): Boolean = demoCover() != null

        fun coverDestRectFor(x: Float, y: Float, side: Float): IntArray = intArrayOf(
            x.roundToInt(), y.roundToInt(),
            side.roundToInt(), side.roundToInt(),
        )

        private var coverRgbCache: Int? = null
        private var coverRgbTried = false

        private var coverRgbMask: Boolean = true

        private fun demoCoverRgb(circleMask: Boolean = true): Int? {

            if (!coverRgbTried || coverRgbMask != circleMask) {
                coverRgbTried = true
                coverRgbMask = circleMask
                coverRgbCache = demoCover()?.let {
                    dev.cdl.cover.CoverPaletteRich.colorSource(it, circleMask)
                }
            }
            return coverRgbCache
        }

        private var coverPaletteCache: List<Int>? = null

        private var coverPaletteCacheMask: Boolean = true

        private var coverPaletteSharesCache: FloatArray = FloatArray(0)

        private fun demoCoverPalette(circleMask: Boolean = true): List<Int> {
            ensureDemoCoverPalette(circleMask)
            return coverPaletteCache ?: emptyList()
        }

        private fun demoCoverPaletteShares(circleMask: Boolean = true): FloatArray {
            ensureDemoCoverPalette(circleMask)
            return coverPaletteSharesCache
        }

        private fun ensureDemoCoverPalette(circleMask: Boolean) {

            val cached = coverPaletteCache
            if (cached != null && coverPaletteCacheMask == circleMask) return
            val entries = demoCover()?.let {
                dev.cdl.cover.CoverPaletteRich.entries(it, circleMask)
            } ?: emptyList()
            coverPaletteCache = entries.map { e -> e.argb }
            coverPaletteSharesCache = entries.map { e -> e.share }.toFloatArray()
            coverPaletteCacheMask = circleMask
        }

        fun previewOutlineStrokeFor(widthPx: Float): BasicStroke =
            dev.cdl.render.karaokeOutlineStroke(widthPx)

        fun previewBaseColorArgb(
            mode: String,
            manualArgb: Int,
            coverRgb: Int?,
            opacityPercent: Int,
        ): Int {
            val base = CoverPalette.resolve(mode, Color(manualArgb, true), coverRgb, CoverPalette::darkFrom)

            val alpha = (255 * opacityPercent.coerceIn(0, 100) / 100).coerceIn(0, 255)
            return (alpha shl 24) or (base.rgb and 0xFFFFFF)
        }

        fun sampleMain(): String = PreviewSnapshot.SAMPLE_MAIN

        private fun fitScale(
            text: String,
            font: Font,
            avail: Float,
            frc: java.awt.font.FontRenderContext,
            minScale: Float,
        ): Float {
            if (avail <= 0f) return 1f
            val advance = runCatching {
                java.awt.font.TextLayout(text, font, frc).advance
            }.getOrNull() ?: return 1f
            if (advance <= avail || advance <= 0f) return 1f
            return (avail / advance).coerceIn(minScale, 1f)
        }

        fun fitScaleFor(
            text: String,
            font: Font,
            avail: Float,
            minScale: Float = MIN_FIT_SCALE,
        ): Float = fitScale(
            text, font, avail,
            java.awt.font.FontRenderContext(null, true, true),
            minScale,
        )

        fun coverSideFor(areaHeight: Float, scaleWithFont: Boolean): Float =
            leadingCoverOuterSizePx(areaHeight, scaleWithFont)

        fun previewTranslationUnsungColor(sungArgb: Int): Int =
            dimPackedArgb(sungArgb, UNSUNG_TRANSLATION_DIM)
    }
}
