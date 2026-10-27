// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.animation.MotionStyle
import dev.cdl.animation.Motions
import dev.cdl.render.CoverPalette
import dev.cdl.render.TextOutline
import dev.cdl.render.letterSpacingTracking
import dev.cdl.render.resolveOutline
import dev.cdl.settings.AnimationStyleResolver
import dev.cdl.settings.KARAOKE_MODE_PER_CHAR
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingsSnapshot
import dev.cdl.settings.toColor
import java.awt.Color
import kotlin.math.roundToInt

private fun packRgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

const val RIGHT_SLOT_SPECTRUM_MODE_FOLLOW: String = "follow"

fun rightSlotSpectrumColorModeFor(wire: String?): String = when (wire?.trim()) {
    CoverPalette.MANUAL,
    CoverPalette.COVER,
    CoverPalette.LEVEL,
    CoverPalette.COVER_FLOW,
    CoverPalette.COVER_CAMO,
    -> wire.trim()

    else -> RIGHT_SLOT_SPECTRUM_MODE_FOLLOW
}

fun rightSlotSpectrumColorFor(
    slotMode: String,
    mainColor: Color,
    slotManualColor: Color,
    coverRgb: Int?,
): Color = if (slotMode == RIGHT_SLOT_SPECTRUM_MODE_FOLLOW) {
    mainColor
} else {
    CoverPalette.resolve(slotMode, slotManualColor, coverRgb, CoverPalette::brightFrom)
}

fun isMultiColorSpectrumMode(mode: String): Boolean =
    mode == CoverPalette.COVER_FLOW || mode == CoverPalette.COVER_CAMO

class Appearance internal constructor(

    val shapeType: String,

    val cornerRadiusPercent: Int,

    val materialHighlightStrength: Int,

    val materialGradientStyle: String,

    val materialFlowSpeed: Int,

    val materialGradientTintPercent: Int,

    val materialGradientAngleDeg: Int,

    val materialGradientEndArgb: Int,

    val materialNoiseAmplitude: Int,

    val materialQuality: String,

    val materialBlurRadius: Int,

    val materialRenderGpuBackend: Boolean,

    val frameLogEnabled: Boolean,

    val cornerStyle: String,

    val cornerN: Int,

    val opacityPercent: Int,

    val sungColor: Color,

    val unsungColor: Color,

    val fontFamily: String,

    val materialLiquidGlarePercent: Int,

    val materialLiquidDepthEffect: Boolean,

    val materialLiquidSurfaceAlpha: Int,

    val materialLiquidShadowStrength: Int,

    val fontBold: Boolean,

    val translationScale: Float,
    val fontSize: Int,

    val islandEnabled: Boolean,

    val glassFrosted: Boolean,

    val glassBlurRadius: Int,

    val glassClarity: String,

    val glassDispersionPercent: Int,

    val glassSaturationPercent: Int,

    val glassSurfaceAlpha: Int,
    val translationEnabled: Boolean,

    val translationAbove: Boolean,

    val mainOutlineEnabled: Boolean,
    val mainOutlineWidthTenthPx: Int,
    val mainOutlineColorR: Int,
    val mainOutlineColorG: Int,
    val mainOutlineColorB: Int,
    val translationOutlineSync: Boolean,
    val translationOutlineEnabled: Boolean,
    val translationOutlineWidthTenthPx: Int,

    val translationOutlineFollowWeight: Boolean = false,
    val translationOutlineColorR: Int,
    val translationOutlineColorG: Int,
    val translationOutlineColorB: Int,

    val karaokeMode: String,

    val translationColor: Color,

    val translationColorMode: String,

    internal val lines: LineCount,

    val lineGapPx: Float,

    val lyricOffsetMs: Long,

    internal val contentAlign: Align,

    internal val noLyricsMode: NoLyricsMode,

    val showTrackTitle: Boolean,

    val showAlbum: Boolean,

    val hideWhenInstrumental: Boolean,

    val titleInTranslation: Boolean,

    val coverEnabled: Boolean,

    val coverScaleWithFont: Boolean,

    val coverSharpness: Int,

    val coverShape: String,

    val coverRotate: Boolean,

    val coverRotatePeriodS: Float,

    val coverVinyl: Boolean,

    val lockCover: Boolean,

    val borderEnabled: Boolean,

    val borderWidthTenthPx: Int,

    val borderDissolveBandPercent: Int,

    val borderMode: String,
    val borderColorR: Int,
    val borderColorG: Int,
    val borderColorB: Int,

    val borderColorMode: String,

    val spectrumEnabled: Boolean,

    val lyricColorMode: String,

    val backgroundColorMode: String,

    val backgroundManualColor: Color,

    val backgroundCoverAngleDeg: Int,

    val spectrumColorMode: String,

    val spectrumColor: Color,

    val rightSlotSpectrumColorMode: String,

    val rightSlotSpectrumColor: Color,

    val rightSlotRadialInnerPercent: Int,

    val rightSlotRingInnerPercent: Int,

    val rightSlotPolarOuterPercent: Int,

    val rightSlotPolarStyle: String,

    val spectrumAnchor: String,

    val spectrumHeightPct: Int,

    val spectrumBarPitchAuto: Boolean,

    val spectrumBarPitchPx: Int,

    val spectrumSecondaryMode: String,

    val spectrumSecondaryOpacityPct: Int,

    val hitCoverEnabled: Boolean,

    val letterSpacingTenthPx: Int,

    val translationLetterSpacingFollowMain: Boolean,

    val controlsMotionStyle: MotionStyle,

    val lyricMotionStyle: MotionStyle,
) {

    fun sungColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(lyricColorMode, sungColor, coverRgb, CoverPalette::brightFrom)

    fun translationColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(
            translationColorMode, translationColor, coverRgb, CoverPalette::brightFrom,
        )

    fun unsungColorFor(coverRgb: Int?): Color =
        if (lyricColorMode == CoverPalette.COVER && coverRgb != null) {
            CoverPalette.dim(CoverPalette.brightFrom(coverRgb), UNSUNG_LYRIC_DIM)
        } else {
            unsungColor
        }

    fun backgroundColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(
            backgroundColorMode,
            backgroundManualColor,
            coverRgb,
            CoverPalette::darkFrom,
        )

    fun spectrumColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(spectrumColorMode, spectrumColor, coverRgb, CoverPalette::brightFrom)

    fun borderColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(
            borderColorMode,
            Color(borderColorR, borderColorG, borderColorB),
            coverRgb,
            CoverPalette::brightFrom,
        )

    fun mainLetterTracking(): Float = letterSpacingTracking(letterSpacingTenthPx, fontSize)

    internal fun mainOutlineFor(): TextOutline? {

        val r = resolveOutline(
            enabled = mainOutlineEnabled,
            widthTenthPx = mainOutlineWidthTenthPx,
            r = mainOutlineColorR,
            g = mainOutlineColorG,
            b = mainOutlineColorB,
        ) ?: return null
        return TextOutline(r.widthPx, r.argb)
    }

    internal fun translationOutlineFor(): TextOutline? {
        if (translationOutlineSync) return mainOutlineFor()

        val r = resolveOutline(
            enabled = translationOutlineEnabled,
            widthTenthPx = translationOutlineWidthTenthPx,
            r = translationOutlineColorR,
            g = translationOutlineColorG,
            b = translationOutlineColorB,
        ) ?: return null
        return TextOutline(
            r.widthPx,
            r.argb,

            followWeight = translationOutlineFollowWeight,
        )
    }

    fun mainOutlineResolved(): dev.cdl.render.ResolvedOutline? =
        mainOutlineFor()?.let { dev.cdl.render.ResolvedOutline(it.widthPx, it.argb) }

    fun translationOutlineResolved(): dev.cdl.render.ResolvedOutline? =
        translationOutlineFor()?.let { dev.cdl.render.ResolvedOutline(it.widthPx, it.argb) }

    companion object {
        fun from(fresh: SettingsSnapshot): Appearance {
            val registry = SettingRegistry
            fun bool(key: String, fallback: Boolean): Boolean =
                registry.descriptor(key)?.let { runCatching { fresh.boolean(it) }.getOrDefault(fallback) }
                    ?: fallback
            fun int(key: String, fallback: Int): Int =
                registry.descriptor(key)?.let { runCatching { fresh.int(it) }.getOrDefault(fallback) }
                    ?: fallback
            fun text(key: String, fallback: String): String =
                registry.descriptor(key)?.let { runCatching { fresh.text(it) }.getOrDefault(fallback) }
                    ?: fallback

            val fontBold = text("main_lyric.font.weight", "NORMAL") == "BOLD"

            val coverOnlyShape = text("shape.type", "SQUARE") == "COVER_ONLY"

            fun color(key: String, fallback: Int): Color =
                registry.descriptor(key)?.let { descriptor ->
                    runCatching {
                        val raw = fresh.raw(descriptor.key)
                        (descriptor.coerce(raw) as Int).toColor()
                    }.getOrDefault(fallback.toColor())
                } ?: fallback.toColor()

            fun mixerColor(prefix: String, fallback: Int): Color {
                val base = fallback.toColor()
                val r = int("$prefix.r", base.red).coerceIn(0, 255)
                val g = int("$prefix.g", base.green).coerceIn(0, 255)
                val b = int("$prefix.b", base.blue).coerceIn(0, 255)
                val lightness = int("$prefix.lightness", 100).coerceIn(0, 100) / 100f
                return Color(
                    (r * lightness).toInt().coerceIn(0, 255),
                    (g * lightness).toInt().coerceIn(0, 255),
                    (b * lightness).toInt().coerceIn(0, 255),
                )
            }

            val unsung = mixerColor("main_lyric.fill.unsung", 0xFF7E818A.toInt())

            return Appearance(
                shapeType = text("shape.type", "SQUARE"),
                cornerRadiusPercent = int("shape.corner_radius", 60),

                materialHighlightStrength = int("material.highlight.strength", 0),
                materialGradientStyle = text("material.fill.gradient_style", "NONE"),

                materialFlowSpeed = int("material.fill.flow_speed", 15),
                materialGradientTintPercent = int("material.fill.gradient_tint_percent", 30),
                materialGradientAngleDeg = int("material.fill.gradient_angle_deg", 90),

                materialGradientEndArgb = color("material.fill.color_end", 0xFF2A2E38.toInt()).rgb,
                materialNoiseAmplitude = int("material.noise.amplitude", 0),

                materialLiquidGlarePercent = int("material.liquid.glare_strength", 60),
                materialLiquidDepthEffect = bool("material.liquid.depth_effect", true),

                materialLiquidSurfaceAlpha = int("material.liquid.surface_alpha", 10),
                materialLiquidShadowStrength = int("material.liquid.shadow_strength", 60),
                materialQuality = text("material.quality", "FULL"),
                materialBlurRadius = int("material.content.blur_radius", 0),

                materialRenderGpuBackend = bool("material.render.gpu_backend", false),

                frameLogEnabled = bool("animation.frame_log", false),

                cornerStyle = text("shape.corner_style", "ARC"),
                cornerN = int("shape.corner_n", 4),
                opacityPercent = int("background.opacity", 96),
                sungColor = mixerColor("main_lyric.fill.color", 0xFFFFFFFF.toInt()),
                unsungColor = unsung,
                fontFamily = text("main_lyric.font.family", "Microsoft YaHei UI"),

                fontBold = fontBold,

                translationScale = TRANSLATION_SCALE *
                    (int("layout.translation.scale_percent", 100) / 100f).coerceIn(0.5f, 1.5f),
                fontSize = int("main_lyric.font.size", 22),
                islandEnabled = bool("island.enabled", true),
                translationEnabled = bool("layout.translation.enabled", true),

                mainOutlineEnabled = bool("main_lyric.outline.enabled", false),

                mainOutlineWidthTenthPx = run {
                    val base = int("main_lyric.outline.width_tenth_px", 10)
                    if (!bool("main_lyric.outline.follow_font_weight", false)) {
                        base
                    } else {

                        val w = if (fontBold) 2f else 1f
                        val r = (int("main_lyric.outline.weight_link_percent", 50) / 100f)
                            .coerceIn(0f, 1f)
                        (base * (1f + (w - 1f) * r)).roundToInt().coerceAtLeast(0)
                    }
                },
                mainOutlineColorR = int("main_lyric.outline.color.r", 255),
                mainOutlineColorG = int("main_lyric.outline.color.g", 255),
                mainOutlineColorB = int("main_lyric.outline.color.b", 255),

                translationOutlineSync = bool("translation.outline.sync_with_main", true),
                translationOutlineEnabled = bool("translation.outline.enabled", false),

                translationOutlineFollowWeight = bool("translation.outline.follow_weight", false),
                translationOutlineWidthTenthPx = run {
                    val base = int("translation.outline.width_tenth_px", 10)

                    if (!bool("translation.outline.follow_weight", false)) {
                        base
                    } else {

                        val w = if (fontBold) 2f else 1f

                        val r = (int("main_lyric.outline.weight_link_percent", 50) / 100f)
                            .coerceIn(0f, 1f)
                        (base * (1f + (w - 1f) * r)).roundToInt().coerceAtLeast(0)
                    }
                },
                translationOutlineColorR = int("translation.outline.color.r", 255),
                translationOutlineColorG = int("translation.outline.color.g", 255),
                translationOutlineColorB = int("translation.outline.color.b", 255),

                glassFrosted = text("glass.material", "LIQUID")
                    .equals("FROSTED", ignoreCase = true),
                glassBlurRadius = int("glass.blur_radius", 10).coerceIn(0, 20),

                glassClarity = text("glass.clarity", "HIGH"),
                glassDispersionPercent = int("glass.dispersion", 35).coerceIn(0, 100),
                glassSaturationPercent = int("glass.saturation", 150).coerceIn(0, 200),
                glassSurfaceAlpha = int("glass.surface_alpha", 10).coerceIn(0, 30),
                translationAbove = text("layout.translation.position", TRANSLATION_POSITION_BELOW)
                    .equals(TRANSLATION_POSITION_ABOVE, ignoreCase = true),

                karaokeMode = text("main_lyric.karaoke.mode", KARAOKE_MODE_PER_CHAR),

                translationColor = mixerColor("main_lyric.translation.color", 0xFFB1B6C3.toInt()),

                translationColorMode = text(
                    "main_lyric.translation.color_mode", CoverPalette.MANUAL,
                ),
                lines = LineCount.parse(text("layout.lines", LineCount.SINGLE.wire)),

                lineGapPx = int("layout.line_gap_px", 0).toFloat().coerceAtLeast(0f),

                lyricOffsetMs = int("lyric.offset_ms", 0).toLong(),
                contentAlign = Align.parse(text("layout.content_align", Align.CENTER.wire)),
                noLyricsMode = NoLyricsMode.parse(
                    text("layout.noLyrics.mode", NoLyricsMode.HIDE_LINE.wire),
                ),
                showTrackTitle = bool("layout.noLyrics.showTrackTitle", true),

                showAlbum = bool("layout.noLyrics.showAlbum", false),

                hideWhenInstrumental = bool("layout.noLyrics.hideWhenInstrumental", false),
                titleInTranslation = bool("layout.noLyrics.titleInTranslation", false),

                coverEnabled = bool("cover.enabled", false) || coverOnlyShape,

                coverScaleWithFont = bool("cover.scale_with_font", true),

                coverSharpness = int("cover.sharpness", 25),

                coverShape = text("cover.shape", "ROUNDED"),
                coverRotate = bool("cover.rotate", false),
                coverRotatePeriodS = int("cover.rotate_period_s", 20).toFloat(),

                coverVinyl = bool("cover.vinyl", false),

                lockCover = bool("cover.lock", false),

                borderEnabled = bool("island.border.enabled", false),

                borderWidthTenthPx = int("island.border.width_tenth_px", 10),

                borderMode = text("island.border.mode", "SOLID"),

                borderDissolveBandPercent =
                    int("island.border.dissolve_band_percent", 50),
                borderColorR = int("island.border.color.r", 255),
                borderColorG = int("island.border.color.g", 255),
                borderColorB = int("island.border.color.b", 255),

                borderColorMode = text("island.border.color_mode", CoverPalette.MANUAL),

                spectrumEnabled = bool("spectrum.enabled", true),

                lyricColorMode = text("main_lyric.color_mode", CoverPalette.MANUAL),
                backgroundColorMode = text("background.color_mode", CoverPalette.MANUAL),

                backgroundManualColor = color("background.color", BACKGROUND_BASE_RGB),

                backgroundCoverAngleDeg = int("background.cover.angle_deg", 0),
                spectrumColorMode = text("spectrum.color_mode", CoverPalette.MANUAL),

                spectrumColor = color("spectrum.color", DEFAULT_SPECTRUM_COLOR),

                rightSlotSpectrumColorMode = text(
                    "island.right_slot.spectrum_color_mode",
                    RIGHT_SLOT_SPECTRUM_MODE_FOLLOW,
                ),
                rightSlotSpectrumColor = color(
                    "island.right_slot.spectrum_color",
                    DEFAULT_SPECTRUM_COLOR,
                ),

                rightSlotRadialInnerPercent = int(
                    "island.right_slot.radial_inner_percent",
                    dev.cdl.render.POLAR_RADIAL_INNER_PERCENT_DEFAULT,
                ),
                rightSlotRingInnerPercent = int(
                    "island.right_slot.ring_inner_percent",
                    dev.cdl.render.POLAR_RING_INNER_PERCENT_DEFAULT,
                ),
                rightSlotPolarOuterPercent = int(
                    "island.right_slot.polar_outer_percent",
                    dev.cdl.render.POLAR_OUTER_PERCENT_DEFAULT,
                ),

                rightSlotPolarStyle = text(
                    "island.right_slot.polar_style",
                    dev.cdl.render.POLAR_STYLE_DEFAULT,
                ),

                spectrumAnchor = text("spectrum.anchor", SPECTRUM_ANCHOR_BOTTOM),

                spectrumHeightPct = int("spectrum.height_percent", 100),

                spectrumBarPitchAuto = text("spectrum.bar_pitch_mode", SPECTRUM_PITCH_AUTO)
                    .equals(SPECTRUM_PITCH_AUTO, ignoreCase = true),
                spectrumBarPitchPx = int("spectrum.bar_pitch_tenth_px", 70),
                spectrumSecondaryMode = text(
                    "spectrum.secondary.mode",

                    SPECTRUM_SECONDARY_MODE_OFF,
                ),
                spectrumSecondaryOpacityPct = int("spectrum.secondary.opacity_percent", 35),

                hitCoverEnabled = bool("island.hit.cover", false) || coverOnlyShape,

                letterSpacingTenthPx = int("main_lyric.letter_spacing_tenth_px", 0),

                translationLetterSpacingFollowMain =
                    bool("translation.letter_spacing.follow_main", true),

                controlsMotionStyle = MotionStyle.parse(
                    AnimationStyleResolver.effective(
                        componentValue = text(
                            "controls.animation_style",
                            AnimationStyleResolver.FOLLOW_TOTAL,
                        ),
                        totalValue = text(
                            "animation.style",
                            AnimationStyleResolver.TOTAL_PER_COMPONENT,
                        ),
                        factoryDefault = Motions.CONTROLS_APPEAR.style.name,
                    ),
                ),
                lyricMotionStyle = MotionStyle.parse(
                    AnimationStyleResolver.effective(
                        componentValue = text(
                            "lyric.animation_style",
                            AnimationStyleResolver.FOLLOW_TOTAL,
                        ),
                        totalValue = text(
                            "animation.style",
                            AnimationStyleResolver.TOTAL_PER_COMPONENT,
                        ),
                        factoryDefault = Motions.LYRIC_EXIT.style.name,
                    ),
                ),
            )
        }
    }
}
