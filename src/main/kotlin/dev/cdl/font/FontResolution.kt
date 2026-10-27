// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font

fun resolveFontOrFallback(family: String?, size: Int): Font {

    val raw = family?.trim().orEmpty()
    if (raw.isEmpty()) return Font(DEFAULT_FONT_FAMILY, Font.PLAIN, size)

    val host = HostFonts.resolveOrNull(raw)
    if (host != null) return host.deriveFont(size.toFloat())

    if (HostFonts.isSentinel(raw)) return Font(DEFAULT_FONT_FAMILY, Font.PLAIN, size)

    return Font(raw, Font.PLAIN, size)
}

fun fontMatchesRequest(family: String?, size: Int): Boolean {
    val raw = family?.trim().orEmpty()
    if (raw.isEmpty()) return false
    if (HostFonts.resolveOrNull(raw) != null) return true
    if (HostFonts.isSentinel(raw)) return false
    return Font(raw, Font.PLAIN, size).family.equals(raw, ignoreCase = true)
}
