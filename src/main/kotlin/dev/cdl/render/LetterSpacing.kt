// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import dev.cdl.font.HostFonts
import java.awt.Font
import java.awt.font.TextAttribute

fun letterSpacingTracking(letterSpacingTenthPx: Int, fontSize: Int): Float =
    (letterSpacingTenthPx / 10f) / fontSize.coerceAtLeast(1)

fun fontWithLetterSpacing(
    family: String,
    size: Int,
    tracking: Float,

    bold: Boolean = false,
): Font {

    val base = dev.cdl.font.resolveFontOrFallback(family, size)

    val attrs = mutableMapOf<TextAttribute, Any>()
    if (tracking > 0f && size > 0) attrs[TextAttribute.TRACKING] = tracking

    val derived = if (attrs.isEmpty()) base else base.deriveFont(attrs)

    return if (bold) derived.deriveFont(Font.BOLD) else derived
}
