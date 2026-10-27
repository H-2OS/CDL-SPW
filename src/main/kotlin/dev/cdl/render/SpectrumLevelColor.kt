// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color

fun levelColorArgb(baseArgb: Int, value: Float): Int {
    val a = (baseArgb ushr 24) and 0xFF

    if (a == 0) return baseArgb

    val v = value.coerceIn(0f, 1f)

    val r = (baseArgb ushr 16) and 0xFF
    val g = (baseArgb ushr 8) and 0xFF
    val b = baseArgb and 0xFF

    val hsb = Color.RGBtoHSB(r, g, b, null)

    val brightness = LEVEL_BRIGHTNESS_MIN + (1f - LEVEL_BRIGHTNESS_MIN) * v
    val saturation = LEVEL_SATURATION_MIN + (1f - LEVEL_SATURATION_MIN) * v

    val rgb = Color.HSBtoRGB(hsb[0], saturation, brightness)

    return (a shl 24) or (rgb and 0xFFFFFF)
}

const val LEVEL_BRIGHTNESS_MIN = 0.35f

const val LEVEL_SATURATION_MIN = 0.55f
