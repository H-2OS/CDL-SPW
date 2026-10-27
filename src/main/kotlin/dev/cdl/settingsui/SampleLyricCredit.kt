// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JTextField
import javax.swing.SwingConstants

object SampleLyricCredit {

    const val TITLE = "今、歩き出す君へ。"

    const val ARTIST = "Ceui"

    const val CREDIT = "$ARTIST -《$TITLE》"

    fun sampleLyricCreditField(): JTextField = JTextField(CREDIT).apply {
        isEditable = false
        isFocusable = true
        isOpaque = false

        border = null
        horizontalAlignment = SwingConstants.CENTER

        font = font.deriveFont(font.size2D - 2f).deriveFont(Font.PLAIN)

        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR)
        toolTipText = "可选中复制"
    }

    fun sampleLyricCreditRow(): javax.swing.JComponent = javax.swing.JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(Box.createHorizontalGlue())
        add(sampleLyricCreditField())
        add(Box.createHorizontalGlue())
    }
}
