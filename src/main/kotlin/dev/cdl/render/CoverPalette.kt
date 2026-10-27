// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color

object CoverPalette {

    const val MANUAL = "manual"

    const val COVER = "cover"

    const val LEVEL = "level"

    const val COVER_FLOW = "cover_flow"

    const val COVER_CAMO = "cover_camo"

    private const val BRIGHT_MAX_SATURATION = 0.55f

    private const val DARK_MAX_SATURATION = 0.65f

    private const val BACKDROP_BRIGHTNESS = 0.16f

    fun brightFrom(coverRgb: Int): Color {
        val hsb = hsbOf(coverRgb)
        return Color.getHSBColor(hsb[0], hsb[1].coerceAtMost(BRIGHT_MAX_SATURATION), 1f)
    }

    fun darkFrom(coverRgb: Int): Color {
        val hsb = hsbOf(coverRgb)
        return Color.getHSBColor(hsb[0], hsb[1].coerceAtMost(DARK_MAX_SATURATION), BACKDROP_BRIGHTNESS)
    }

    fun dim(color: Color, factor: Float): Color {
        val f = factor.coerceIn(0f, 1f)
        return Color(
            (color.red * f).toInt().coerceIn(0, 255),
            (color.green * f).toInt().coerceIn(0, 255),
            (color.blue * f).toInt().coerceIn(0, 255),
            color.alpha,
        )
    }

    fun resolve(mode: String, manual: Color, coverRgb: Int?, derived: (Int) -> Color): Color =
        if (mode == COVER && coverRgb != null) derived(coverRgb) else manual

    private fun hsbOf(rgb: Int): FloatArray {
        val red = (rgb shr 16) and 0xFF
        val green = (rgb shr 8) and 0xFF
        val blue = rgb and 0xFF
        return Color.RGBtoHSB(red, green, blue, scratchHsb)
    }

    private val scratchHsb = FloatArray(3)
}

fun augmentPalette(colors: List<Int>, n: Int): List<Int> {
    if (colors.isEmpty() || n <= 0) return colors
    if (colors.size >= n) return colors
    val out = ArrayList<Int>(n)
    out.addAll(colors)

    var i = 0
    while (out.size < n) {
        val src = colors[i % colors.size]
        out.add(if (out.size % 2 == 0) lightenArgb(src, 1.35f) else lightenArgb(src, 0.72f))
        i++

        if (i > 64) break
    }
    return out
}

private fun lightenArgb(argb: Int, factor: Float): Int {
    val a = (argb ushr 24) and 0xFF
    val r = ((argb shr 16) and 0xFF) * factor
    val g = ((argb shr 8) and 0xFF) * factor
    val b = (argb and 0xFF) * factor
    fun clamp(v: Float): Int = v.toInt().coerceIn(0, 255)
    return (a shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
}
