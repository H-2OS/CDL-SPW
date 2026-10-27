// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.font.DEFAULT_FONT_FAMILY
import dev.cdl.platform.SystemTheme
import dev.cdl.settings.SettingRegistry
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.UIManager

object ToolWindowPalette {

    val isLight: Boolean get() = SystemTheme.isLight()

    val bg: Color get() = if (isLight) Color(0xFA, 0xFA, 0xFC) else Color(0x20, 0x20, 0x22)

    val fg: Color get() = if (isLight) Color(0x1B, 0x1B, 0x1F) else Color(0xE8, 0xE8, 0xEA)

    val fieldBg: Color get() = if (isLight) Color(0xFF, 0xFF, 0xFF) else Color(0x2A, 0x2A, 0x2E)

    val line: Color get() = if (isLight) Color(0xC8, 0xC8, 0xD0) else Color(0x4A, 0x4A, 0x52)

    val btnBg: Color get() = if (isLight) Color(0xEC, 0xEC, 0xF0) else Color(0x33, 0x33, 0x38)

    val btnHoverBg: Color get() = if (isLight) Color(0xE2, 0xE2, 0xE8) else Color(0x3E, 0x3E, 0x45)

    val btnPressedBg: Color get() = if (isLight) Color(0xD2, 0xD2, 0xDA) else Color(0x49, 0x49, 0x52)

    val btnDisabledBg: Color get() = if (isLight) Color(0xF2, 0xF2, 0xF5) else Color(0x2A, 0x2A, 0x2E)

    val btnDisabledFg: Color get() = if (isLight) Color(0xA8, 0xA8, 0xAE) else Color(0x6A, 0x6A, 0x70)

    val muted: Color get() = if (isLight) Color(0x55, 0x55, 0x55) else Color(0x9A, 0x9A, 0x9E)
}

class ThemeButton(text: String) : javax.swing.JButton(text) {

    init {
        isFocusPainted = false
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isRolloverEnabled = true

        border = BorderFactory.createEmptyBorder(5, 14, 5, 14)
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
        )
        val bg = when {
            !isEnabled -> ToolWindowPalette.btnDisabledBg
            model.isPressed -> ToolWindowPalette.btnPressedBg
            model.isRollover -> ToolWindowPalette.btnHoverBg
            else -> ToolWindowPalette.btnBg
        }
        g2.color = bg
        g2.fillRect(0, 0, width, height)
        g2.color = ToolWindowPalette.line

        g2.drawRect(0, 0, width - 1, height - 1)

        val want = if (isEnabled) ToolWindowPalette.fg else ToolWindowPalette.btnDisabledFg
        if (foreground != want) foreground = want
        super.paintComponent(g)
    }
}

fun applyToolWindowTheme(
    contentPane: Container,
    rootPane: JRootPane? = null,
    fontProvider: (() -> Font)? = toolWindowFontProvider(),
) {
    val light = ToolWindowPalette.isLight
    val bg = ToolWindowPalette.bg
    val fg = ToolWindowPalette.fg
    val fieldBg = ToolWindowPalette.fieldBg
    val line = ToolWindowPalette.line
    val btnBg = ToolWindowPalette.btnBg

    val baseFont = fontProvider?.invoke()
        ?: Font(
            DEFAULT_FONT_FAMILY,
            Font.PLAIN,

            UIManager.getFont("Label.font")?.size ?: 12,
        )

    fun style(c: Component) {

        if (baseFont != null) {
            val cur = c.font
            c.font = if (cur != null) baseFont.deriveFont(cur.size.toFloat()) else baseFont
        }
        when (c) {

            is ThemeButton -> c.foreground = fg
            is AbstractButton -> {
                c.isFocusPainted = false
                c.isOpaque = true
                c.background = btnBg
                c.foreground = fg
                c.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line),
                    BorderFactory.createEmptyBorder(5, 14, 5, 14),
                )
            }
            is JTextField -> {

                c.foreground = fg
                if (c.isEditable) {
                    c.background = fieldBg

                    c.border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(line),
                        BorderFactory.createEmptyBorder(3, 6, 3, 6),
                    )
                }
            }
            is JLabel -> c.foreground = fg

            is JPanel -> {
                c.isOpaque = true
                c.background = bg

                c.foreground = fg
            }

            is JScrollPane -> {
                c.isOpaque = true
                c.background = bg
                c.foreground = fg

                c.viewport?.let { style(it) }
                listOfNotNull(c.verticalScrollBar, c.horizontalScrollBar).forEach { style(it) }
            }
            is JViewport -> {
                c.isOpaque = true
                c.background = fieldBg
                c.foreground = fg
            }
            is JList<*> -> {
                c.isOpaque = true
                c.background = fieldBg
                c.foreground = fg
            }
            is JScrollBar -> {
                c.isOpaque = true
                c.background = btnBg
                c.foreground = fg

                if (c.ui !is ThemeScrollBarUI) c.ui = ThemeScrollBarUI()
            }

            is JSlider -> {
                c.isOpaque = true
                c.background = bg
                c.foreground = fg
            }
            else -> Unit
        }
        if (c is Container) c.components.forEach { style(it) }
    }

    contentPane.background = bg
    style(contentPane)

    rootPane?.background = bg

    syncNativeTitleBar(contentPane, light)
}

fun syncNativeTitleBar(contentPane: Container, dark: Boolean): Boolean = runCatching {
    val owner = SwingUtilities.getWindowAncestor(contentPane) ?: return@runCatching false
    dev.cdl.window.WindowInterop.setDarkTitleBar(owner, dark)
}.getOrDefault(false)

fun toolWindowFontProvider(): () -> Font = {
    val family = runCatching {
        val runtime = SettingsRuntimeHolder.current
        val descriptor = SettingRegistry.descriptor(FONT_FAMILY_KEY)
        if (runtime != null && descriptor != null) runtime.live.text(descriptor) else null
    }.getOrNull()

    dev.cdl.font.resolveFontOrFallback(
        family,

        UIManager.getFont("Label.font")?.size ?: 12,
    )
}

private class ThemeScrollBarUI : javax.swing.plaf.basic.BasicScrollBarUI() {

    override fun configureScrollBarColors() {

        trackColor = ToolWindowPalette.bg
        thumbColor = ToolWindowPalette.line

        thumbHighlightColor = ToolWindowPalette.line
        thumbLightShadowColor = ToolWindowPalette.line
        thumbDarkShadowColor = ToolWindowPalette.bg
        trackHighlightColor = ToolWindowPalette.bg
    }

    override fun createDecreaseButton(orientation: Int): javax.swing.JButton = zeroButton()

    override fun createIncreaseButton(orientation: Int): javax.swing.JButton = zeroButton()

    private fun zeroButton(): javax.swing.JButton = javax.swing.JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
        isFocusable = false
    }
}
