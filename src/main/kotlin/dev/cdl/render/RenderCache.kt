// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.font.TextAttribute
import java.text.AttributedString
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.util.concurrent.ConcurrentHashMap

internal class RenderCache {

    private var font: Font = DEFAULT_FONT

    private var frc: FontRenderContext = DEFAULT_FRC

    private var frcKey: Long = FRc_KEY_UNSET

    private val layouts = ConcurrentHashMap<String, TextLayout>()

    fun textLayoutFor(text: String, font: Font = this.font): TextLayout? {
        if (text.isEmpty()) return null

        if (font !== this.font) return buildLayout(text, font)

        layouts[text]?.let { return it }

        val created = buildLayout(text, font)
        if (layouts.size >= LAYOUT_CACHE_LIMIT) {

            layouts.clear()
        }
        layouts[text] = created
        return created
    }

    private fun buildLayout(text: String, font: Font): TextLayout {
        val segs = dev.cdl.font.ScriptFontResolver.segments(font, text)
        if (segs.size <= 1) return TextLayout(text, font, frc)
        val attributed = AttributedString(text)
        for (seg in segs) {
            attributed.addAttribute(TextAttribute.FONT, seg.font, seg.start, seg.end)
        }
        return TextLayout(attributed.iterator, frc)
    }

    fun setFont(next: Font) {
        if (next == font) return
        font = next
        layouts.clear()
    }

    fun currentFont(): Font = font

    fun syncFrom(g: java.awt.Graphics2D) {
        val key = frcFingerprint(g)
        if (key == frcKey) return
        val hints = g.renderingHints
        val aa = hints[java.awt.RenderingHints.KEY_TEXT_ANTIALIASING]
            ?: java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT
        val fm = hints[java.awt.RenderingHints.KEY_FRACTIONALMETRICS]
            ?: java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT
        val transform = when {
            g.transform.isIdentity -> null
            else -> g.transform
        }
        frc = FontRenderContext(transform, aa as Any, fm as Any)
        frcKey = key

        layouts.clear()
    }

    private val colors = ConcurrentHashMap<Int, Color>()

    fun color(packedArgb: Int): Color = colors.getOrPut(packedArgb) { Color(packedArgb, true) }

    private val strokes = ConcurrentHashMap<Long, BasicStroke>()

    fun stroke(width: Float, cap: Int = BasicStroke.CAP_ROUND, join: Int = BasicStroke.JOIN_ROUND): BasicStroke {

        val quantized = (width.coerceAtLeast(0.01f) * 256f).toInt().coerceAtLeast(1)
        val key = (quantized.toLong() shl 8) or (cap.toLong() shl 4) or join.toLong()
        return strokes.getOrPut(key) { BasicStroke(quantized / 256f, cap, join) }
    }

    private val outlines = ConcurrentHashMap<String, java.awt.Shape>()

    fun outlineFor(layout: TextLayout, text: String): java.awt.Shape? {
        if (text.isEmpty()) return null
        outlines[text]?.let { return it }
        val created = layout.getOutline(null) ?: return null
        if (outlines.size >= OUTLINE_CACHE_LIMIT) outlines.clear()
        outlines[text] = created
        return created
    }

    fun clear() {
        layouts.clear()
        colors.clear()
        strokes.clear()
        outlines.clear()
        frcKey = FRc_KEY_UNSET
    }

    private companion object {

        val DEFAULT_FONT: Font = Font(Font.SANS_SERIF, Font.PLAIN, 22)

        val DEFAULT_FRC: FontRenderContext = FontRenderContext(
            null,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        )

        const val FRc_KEY_UNSET = Long.MIN_VALUE

        const val LAYOUT_CACHE_LIMIT = 64

        const val OUTLINE_CACHE_LIMIT = 16

        fun frcFingerprint(g: java.awt.Graphics2D): Long {
            val hints = g.renderingHints
            val aa = hints[java.awt.RenderingHints.KEY_TEXT_ANTIALIASING]?.hashCode() ?: 0
            val fm = hints[java.awt.RenderingHints.KEY_FRACTIONALMETRICS]?.hashCode() ?: 0

            val tf = if (g.transform.isIdentity) 0 else AffineTransform().also { it.setTransform(g.transform) }.hashCode()
            return (aa.toLong() shl 42) or (fm.toLong() shl 21) or (tf.toLong() and 0x1FFFFF)
        }
    }
}
