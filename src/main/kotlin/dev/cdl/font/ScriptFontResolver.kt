// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.util.concurrent.ConcurrentHashMap

internal object ScriptFontResolver {

    data class Segment(val font: Font, val start: Int, val end: Int)

    private fun isComplexScript(cp: Int): Boolean =
        cp in 0x0E00..0x0E7F ||
            cp in 0x0E80..0x0EFF ||
            cp in 0x1000..0x109F ||
            cp in 0x1780..0x17FF ||
            cp in 0x1A20..0x1AAF ||
            cp in 0x0F00..0x0FFF ||
            cp in 0x0900..0x0DFF

    private val LOGICAL_FAMILIES = setOf(
        "dialog", "sansserif", "serif", "monospaced",
        "dialoginput", "dialog.plain", "dialog.bold", "dialog.italic",
    )

    private fun isLogical(family: String): Boolean =
        family.lowercase() in LOGICAL_FAMILIES

    @Volatile private var allFonts: Array<Font>? = null

    private val familyByCodePoint = ConcurrentHashMap<Int, String>()

    private val missingCodePoints = ConcurrentHashMap.newKeySet<Int>()

    private fun fonts(): Array<Font> {
        allFonts?.let { return it }
        val list = GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
        return list.also { allFonts = it }
    }

    private fun familyFor(cp: Int, preferred: String): String? {
        familyByCodePoint[cp]?.let { return it }
        if (missingCodePoints.contains(cp)) return null

        val preferredFont = runCatching { Font(preferred, Font.PLAIN, 12) }.getOrNull()
        if (preferredFont != null && preferredFont.canDisplay(cp)) {
            familyByCodePoint[cp] = preferred
            return preferred
        }

        for (f in fonts()) {
            if (!f.canDisplay(cp)) continue
            val fam = f.family
            if (isLogical(fam)) continue
            familyByCodePoint[cp] = fam
            return fam
        }

        missingCodePoints.add(cp)
        return null
    }

    fun segments(base: Font, text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        if (base.canDisplayUpTo(text) == -1) {
            return listOf(Segment(base, 0, text.length))
        }

        val preferred = base.family
        val out = ArrayList<Segment>(4)
        var segStart = 0
        var segFamily: String? = null
        var i = 0

        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)

            if (isComplexScript(cp)) {
                var j = i
                val runCp = ArrayList<Int>(4)
                while (j < text.length) {
                    val c = text.codePointAt(j)
                    if (!isComplexScript(c)) break
                    runCp.add(c)
                    j += Character.charCount(c)
                }
                val whole = wholeRunFamily(runCp, preferred)
                if (whole != null) {
                    if (segFamily != whole) {
                        if (i > segStart) out += Segment(fontOf(base, segFamily), segStart, i)
                        segStart = i
                        segFamily = whole
                    }
                    i = j
                    continue
                }

            }

            val fam = familyFor(cp, preferred)
            val effFam = if (fam == null || fam == preferred) null else fam
            if (effFam != segFamily) {
                if (i > segStart) out += Segment(fontOf(base, segFamily), segStart, i)
                segStart = i
                segFamily = effFam
            }
            i += len
        }
        if (text.length > segStart) out += Segment(fontOf(base, segFamily), segStart, text.length)
        return out
    }

    private fun wholeRunFamily(cps: List<Int>, preferred: String): String? {
        val pref = runCatching { Font(preferred, Font.PLAIN, 12) }.getOrNull()
        if (pref != null && cps.all { pref.canDisplay(it) }) return preferred
        for (f in fonts()) {
            if (!cps.all { f.canDisplay(it) }) continue
            if (isLogical(f.family)) continue
            return f.family
        }
        return null
    }

    private fun fontOf(base: Font, family: String?): Font =
        if (family == null || family == base.family) base
        else Font(family, base.style, base.size).deriveFont(base.size2D)
}
