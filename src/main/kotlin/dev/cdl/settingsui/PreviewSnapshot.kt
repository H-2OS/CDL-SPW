// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.render.CoverPalette
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingsRuntime
import dev.cdl.window.BACKGROUND_BASE_RGB
import kotlin.math.roundToInt

fun previewTargetMatches(targetKey: String, prefix: String): Boolean {
    fun root(k: String): String = k
        .removeSuffix(".r").removeSuffix(".g").removeSuffix(".b")
        .removeSuffix(".a").removeSuffix(".lightness")
    val a = root(targetKey)
    val b = root(prefix)
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || a.startsWith(b + ".") || b.startsWith(a + ".")
}

internal const val TRANSLATION_SCALE_DEFAULT = 0.64f

data class PreviewSnapshot(

    val shapeType: String,

    val cornerRadius: Int,

    val cornerStyle: String,

    val cornerN: Int,

    val materialHighlightStrength: Int,

    val materialGradientStyle: String,

    val materialGradientTintPercent: Int,

    val materialGradientAngleDeg: Int,

    val materialGradientEndArgb: Int,

    val materialNoiseAmplitude: Int,

    val materialQuality: String,

    val materialBlurRadius: Int,

    val materialLiquidGlarePercent: Int,

    val materialLiquidDepthEffect: Boolean,

    val materialLiquidSurfaceAlpha: Int,

    val materialRenderGpuBackend: Boolean,

    val backgroundColorMode: String,

    val backgroundColorArgb: Int,

    val backgroundCoverAngleDeg: Int,

    val backgroundOpacity: Int,

    val sungColor: Int,

    val unsungColor: Int,

    val translationColor: Int,

    val translationColorMode: String,

    val mainFontSize: Int,

    val fontFamily: String,

    val fontBold: Boolean,

    val translationScale: Float,

    val contentAlign: String,

    val coverEnabled: Boolean,

    val coverScaleWithFont: Boolean,

    val coverSharpness: Int,

    val coverShape: String,

    val coverVinyl: Boolean,

    val spectrumEnabled: Boolean,

    val spectrumColor: Int,

    val spectrumSymmetric: Boolean,

    val spectrumBarPitchTenthPx: Float,

    val spectrumBarShape: String,

    val spectrumBarCorner: String,

    val spectrumColorMode: String,

    val lyricColorMode: String,

    val spectrumAnchor: String,

    val rightSlotComponent: String,

    val rightSlotSpectrumBars: String,

    val rightSlotSpectrumColorMode: String,

    val rightSlotSpectrumColor: Int,

    val spectrumOpacityPct: Int,

    val rightSlotRadialInnerPercent: Int,

    val rightSlotRingInnerPercent: Int,

    val rightSlotPolarOuterPercent: Int,

    val rightSlotPolarStyle: String,

    val clockHourFormat: String,

    val clockStyle: String,

    val borderEnabled: Boolean,

    val borderMode: String,

    val borderDissolveBandPercent: Int,

    val borderColor: Int,

    val borderColorMode: String,

    val mainOutline: OutlineSpec?,

    val translationOutline: OutlineSpec?,

    val karaokeMode: String,
) {

    data class OutlineSpec(val widthPx: Float, val color: Int)

    companion object {

        const val SAMPLE_MAIN = "世界でひとつの君という奇跡が"
        const val SAMPLE_TRANSLATION = "在这世间 有一个被称为「你」的奇迹"

        fun capture(runtime: SettingsRuntime?): PreviewSnapshot {
            if (runtime == null) return DEFAULTS
            val snap = runCatching { runtime.live }.getOrNull() ?: return DEFAULTS
            fun int(key: String, def: Int): Int =
                runCatching { SettingRegistry.descriptor(key)?.let { snap.int(it) } }.getOrNull() ?: def
            fun str(key: String, def: String): String =
                runCatching { SettingRegistry.descriptor(key)?.let { snap.text(it) } }.getOrNull() ?: def
            fun bool(key: String, def: Boolean): Boolean =
                runCatching { SettingRegistry.descriptor(key)?.let { snap.boolean(it) } }.getOrNull() ?: def

            fun group(prefix: String, dr: Int, dg: Int, db: Int): Int {
                val r = int("$prefix.r", dr)
                val g = int("$prefix.g", dg)
                val b = int("$prefix.b", db)
                val l = if (SettingRegistry.descriptor("$prefix.lightness") != null) {
                    int("$prefix.lightness", 100).coerceIn(0, 100)
                } else {
                    100
                }
                return dev.cdl.settings.composePackedArgb(r, g, b, l)
            }

            fun outline(prefix: String): OutlineSpec? {
                if (!bool("$prefix.enabled", false)) return null
                val baseTenth = int("$prefix.width_tenth_px", 10)

                val tenthPx = if (!bool("main_lyric.outline.follow_font_weight", false)) {
                    baseTenth
                } else {

                    val bold = str("main_lyric.font.weight", "NORMAL") == "BOLD"
                    val r = (int("main_lyric.outline.weight_link_percent", 50) / 100f)
                        .coerceIn(0f, 1f)
                    val factor = if (bold) 1f + r else 1f
                    (baseTenth * factor).roundToInt().coerceAtLeast(0)
                }
                val w = tenthPx / 10f
                if (w <= 0f) return null
                return OutlineSpec(w, group("$prefix.color", 255, 255, 255))
            }

            val mainOutline = outline("main_lyric.outline")
            val translationOutline = if (bool("translation.outline.sync_with_main", true)) {
                mainOutline
            } else {
                outline("translation.outline")
            }

            return PreviewSnapshot(

                shapeType = str("shape.type", "SQUARE"),
                cornerRadius = int("shape.corner_radius", 60),

                cornerStyle = str("shape.corner_style", "ARC"),
                cornerN = int("shape.corner_n", 4),

                materialHighlightStrength = int("material.highlight.strength", 0),
                materialGradientStyle = str("material.fill.gradient_style", "NONE"),
                materialGradientTintPercent = int("material.fill.gradient_tint_percent", 30),
                materialGradientAngleDeg = int("material.fill.gradient_angle_deg", 90),

                materialGradientEndArgb = runCatching {
                    SettingRegistry.descriptor("material.fill.color_end")?.let { snap.int(it) }
                }.getOrNull() ?: 0xFF2A2E38.toInt(),
                materialNoiseAmplitude = int("material.noise.amplitude", 0),
                materialQuality = str("material.quality", "FULL"),
                materialBlurRadius = int("material.content.blur_radius", 0),

                materialLiquidGlarePercent = int("material.liquid.glare_strength", 60),
                materialLiquidDepthEffect = bool("material.liquid.depth_effect", true),
                materialLiquidSurfaceAlpha = int("material.liquid.surface_alpha", 10),

                materialRenderGpuBackend = bool("material.render.gpu_backend", false),

                backgroundColorMode = str("background.color_mode", CoverPalette.MANUAL),
                backgroundColorArgb = runCatching {
                    SettingRegistry.descriptor("background.color")?.let { snap.int(it) }
                }.getOrNull() ?: BACKGROUND_BASE_RGB,
                backgroundCoverAngleDeg = int("background.cover.angle_deg", 0),
                backgroundOpacity = int("background.opacity", 96),
                sungColor = group("main_lyric.fill.color", 255, 255, 255),
                unsungColor = group("main_lyric.fill.unsung", 126, 129, 138),
                translationColor = group("main_lyric.translation.color", 177, 182, 195),

                translationColorMode = str(
                    "main_lyric.translation.color_mode",
                    dev.cdl.render.CoverPalette.MANUAL,
                ),
                mainFontSize = int("main_lyric.font.size", 22),
                fontFamily = str("main_lyric.font.family", ""),

                fontBold = str("main_lyric.font.weight", "NORMAL") == "BOLD",
                translationScale = TRANSLATION_SCALE_DEFAULT *
                    (int("layout.translation.scale_percent", 100) / 100f).coerceIn(0.5f, 1.5f),
                contentAlign = str("layout.content_align", "CENTER"),
                coverEnabled = bool("cover.enabled", false),
                coverScaleWithFont = bool("cover.scale_with_font", true),
                coverSharpness = int("cover.sharpness", 25),
                coverShape = str("cover.shape", "ROUNDED"),
                coverVinyl = bool("cover.vinyl", false),
                spectrumEnabled = bool("spectrum.enabled", true),

                spectrumColor = runCatching {
                    SettingRegistry.descriptor("spectrum.color")?.let { snap.int(it) }
                }.getOrNull()?.or(0xFF000000.toInt()) ?: 0xFF84D8BC.toInt(),
                spectrumSymmetric = bool("spectrum.symmetric", false),

                spectrumBarPitchTenthPx = int("spectrum.bar_pitch_tenth_px", 70).toFloat(),
                spectrumBarShape = str("spectrum.bar_shape", "ROUND"),
                spectrumBarCorner = str("spectrum.bar_corner", "ROUND"),

                spectrumColorMode = str("spectrum.color_mode", "manual"),

                lyricColorMode = str("main_lyric.color_mode", dev.cdl.render.CoverPalette.MANUAL),
                spectrumAnchor = str("spectrum.anchor", "BOTTOM"),
                rightSlotComponent = str("island.right_slot.component", "NONE"),
                rightSlotSpectrumBars = str("island.right_slot.spectrum_bars", "5"),

                rightSlotSpectrumColorMode = str("island.right_slot.spectrum_color_mode", "follow"),
                rightSlotSpectrumColor = runCatching {
                    SettingRegistry.descriptor("island.right_slot.spectrum_color")?.let { snap.int(it) }
                }.getOrNull()?.or(0xFF000000.toInt()) ?: 0xFF84D8BC.toInt(),

                spectrumOpacityPct = int("spectrum.opacity_pct", 100),

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

                rightSlotPolarStyle = str(
                    "island.right_slot.polar_style",
                    dev.cdl.render.POLAR_STYLE_DEFAULT,
                ),
                clockHourFormat = str("clock.hour_format", "H24"),
                clockStyle = str("clock.style", "SEGMENT"),
                borderEnabled = bool("island.border.enabled", false),
                borderMode = str("island.border.mode", "SOLID"),
                borderDissolveBandPercent =
                    int("island.border.dissolve_band_percent", 50),
                borderColor = group("island.border.color", 255, 255, 255),

                borderColorMode = str("island.border.color_mode", CoverPalette.MANUAL),
                mainOutline = mainOutline,
                translationOutline = translationOutline,
                karaokeMode = str("main_lyric.karaoke.mode", "SMOOTH"),
            )
        }

        val DEFAULTS = PreviewSnapshot(

            shapeType = "SQUARE",
            cornerRadius = 60,
            cornerStyle = "ARC",
            cornerN = 4,
            materialHighlightStrength = 0,
            materialGradientStyle = "NONE",
            materialGradientTintPercent = 30,
            materialGradientAngleDeg = 90,
            materialGradientEndArgb = 0xFF2A2E38.toInt(),
            materialNoiseAmplitude = 0,
            materialQuality = "FULL",
            materialBlurRadius = 0,

            materialLiquidGlarePercent = 60,
            materialLiquidDepthEffect = true,
            materialLiquidSurfaceAlpha = 10,

            backgroundColorMode = CoverPalette.MANUAL,
            backgroundColorArgb = BACKGROUND_BASE_RGB,
            backgroundCoverAngleDeg = 0,
            backgroundOpacity = 96,
            sungColor = 0xFFFFFFFF.toInt(),
            unsungColor = 0xFF7E818A.toInt(),
            translationColor = 0xFFB1B6C3.toInt(),

            translationColorMode = dev.cdl.render.CoverPalette.MANUAL,
            mainFontSize = 22,
            fontFamily = "",

            fontBold = false,
            translationScale = TRANSLATION_SCALE_DEFAULT,
            contentAlign = "CENTER",
            coverEnabled = false,
            coverScaleWithFont = true,
            coverSharpness = 25,
            coverShape = "ROUNDED",

            coverVinyl = false,
            spectrumEnabled = true,
            spectrumColor = 0xFF84D8BC.toInt(),
            spectrumSymmetric = false,

            spectrumBarPitchTenthPx = 70f,
            spectrumBarCorner = "ROUND",
            spectrumColorMode = "manual",

            lyricColorMode = dev.cdl.render.CoverPalette.MANUAL,
            spectrumAnchor = "BOTTOM",
            rightSlotComponent = "NONE",
            rightSlotSpectrumBars = "5",
            rightSlotSpectrumColorMode = "follow",
            rightSlotSpectrumColor = 0xFF84D8BC.toInt(),
            rightSlotRadialInnerPercent = dev.cdl.render.POLAR_RADIAL_INNER_PERCENT_DEFAULT,
            rightSlotRingInnerPercent = dev.cdl.render.POLAR_RING_INNER_PERCENT_DEFAULT,
            rightSlotPolarOuterPercent = dev.cdl.render.POLAR_OUTER_PERCENT_DEFAULT,

            rightSlotPolarStyle = dev.cdl.render.POLAR_STYLE_DEFAULT,
            clockHourFormat = "H24",

            clockStyle = "SEGMENT",
            borderEnabled = false,
            borderMode = "SOLID",
            borderDissolveBandPercent = 50,
            borderColor = 0xFFFFFFFF.toInt(),

            borderColorMode = CoverPalette.MANUAL,
            mainOutline = null,
            translationOutline = null,
            karaokeMode = "SMOOTH",

            materialRenderGpuBackend = false,
            spectrumBarShape = "ROUND",
            spectrumOpacityPct = 100,
        )
    }
}
