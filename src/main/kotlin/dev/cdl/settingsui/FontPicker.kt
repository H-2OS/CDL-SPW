// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.font.DEFAULT_FONT_FAMILY
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingKind
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import dev.cdl.font.HostFonts
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder

internal class FontPicker(

    private var originalFamily: String,

    private val previewSize: Int,

    private val onCommit: (String) -> Unit,
) {

    var value: String = originalFamily
        private set

    val root: JPanel = JPanel(BorderLayout(0, 6)).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(6, 0, 4, 0)
        add(Box.createVerticalStrut(2), BorderLayout.NORTH)
    }

    private val allFamilies: List<String> = loadFamilies()

    private val listModel = DefaultComboBoxModel<String>().apply {

        val ordered = buildList {
            if (originalFamily.isNotBlank()) add(originalFamily)
            allFamilies.filterTo(this) { it != originalFamily }
        }
        ordered.forEach(::addElement)
    }

    private val familyList = JList(listModel).apply {
        visibleRowCount = 10
        layoutOrientation = JList.VERTICAL
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        cellRenderer = FamilyCellRenderer(initialPreviewFont())

        setSelectedValue(originalFamily, true)

        fixedCellHeight = 22

    }

    private val previewCanvas = object : JPanel() {
        init {
            isOpaque = true
            background = ToolWindowPalette.fieldBg
            preferredSize = Dimension(300, 76)
            minimumSize = Dimension(200, 76)
            border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ToolWindowPalette.line),
                "预览",
                TitledBorder.LEADING,
                TitledBorder.TOP,
            )
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )
                g2.font = previewFont
                g2.color = ToolWindowPalette.fg
                val metrics = g2.fontMetrics

                val line = "CDL for SPW 0123"
                g2.drawString(line, 8, 20 + metrics.ascent)
                g2.color = ToolWindowPalette.muted
                g2.font = g2.font.deriveFont(Math.max(9f, previewSize - 6f))
                g2.drawString(
                    "实际字体：${previewFamilyLabel()}",
                    8,
                    20 + metrics.ascent + metrics.descent + 18,
                )
            } finally {
                g2.dispose()
            }
        }
    }

    private val previewHint = JLabel(
        "<html><span style='color:#B45309'>字体族切换代价高，" +
            "拖动/滚动列表时预览保持上一款字体；" +
            "<b>双击字体名或按回车</b>后才真正生效。</span></html>"
    )

    private val selectionLabel = JLabel(originalFamily).apply {
        foreground = ToolWindowPalette.fg
    }

    private val confirmButton = JButtonFactory.primary("确认使用该字体")

    private var previewFont: Font = initialPreviewFont()

    private var previewFamily: String = originalFamily

    private var lastCommitted: String = originalFamily

    init {
        root.add(listScroll(), BorderLayout.CENTER)
        root.add(

            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(previewCanvas, BorderLayout.NORTH)
                add(
                    previewHint.apply {

                        preferredSize = Dimension(10, 36)
                        verticalAlignment = javax.swing.SwingConstants.TOP
                    },
                    BorderLayout.CENTER,
                )
                add(
                    JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4)).apply {
                        isOpaque = false
                        add(confirmButton)
                        add(JLabel("当前选择：").also { label ->
                            label.foreground = ToolWindowPalette.muted
                        })
                        add(selectionLabel)
                    },
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.SOUTH,
        )

        installWiring()
        updateSelectionLabel()
        updatePreviewImmediate()
    }

    fun setValueFromModel(family: String) {
        if (family == originalFamily && family == value) return
        originalFamily = family
        value = family
        lastCommitted = family
        if (listModel.getIndexOf(family) < 0) listModel.insertElementAt(family, 0)
        familyList.setSelectedValue(family, true)
        updateSelectionLabel()
        updatePreviewImmediate()
    }

    fun setExpanded(expanded: Boolean) {
        root.isVisible = expanded
        root.revalidate()
        root.repaint()
    }

    private fun onSelectionChanged() {
        val family = familyList.selectedValue ?: return
        value = family
        updateSelectionLabel()
        updatePreviewImmediate()
    }

    private fun previewFamilyLabel(): String = previewFontLabel(previewFamily, previewFont)

    private fun updatePreviewImmediate() {
        previewFamily = value

        previewFont = dev.cdl.font.resolveFontOrFallback(value, previewSize)
        previewCanvas.repaint()
    }

    private fun commit() {
        val family = value
        if (family.isBlank() || family == lastCommitted) return
        lastCommitted = family
        originalFamily = family
        onCommit(family)
    }

    private fun installWiring() {
        familyList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onSelectionChanged()
        }

        familyList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) commit()
            }
        })

        familyList.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = commit()
            },
        )
        familyList.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    value = originalFamily
                    lastCommitted = originalFamily
                    familyList.setSelectedValue(originalFamily, true)
                    updateSelectionLabel()
                    updatePreviewImmediate()

                }
            },
        )
        confirmButton.addActionListener { commit() }
    }

    private fun updateSelectionLabel() {
        selectionLabel.text = value.ifBlank { "（空）" }
    }

    private fun listScroll(): JComponent {
        val scroll = javax.swing.JScrollPane(familyList).apply {

            preferredSize = Dimension(300, 220)
            verticalScrollBar.unitIncrement = 48
            verticalScrollBar.blockIncrement = 220
            horizontalScrollBar.unitIncrement = 16

            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.preferredSize = Dimension(10, 0)
            border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ToolWindowPalette.line),
                "系统中已安装的字体族",
                TitledBorder.LEADING,
                TitledBorder.TOP,
            )
        }
        return scroll
    }

    private fun initialPreviewFont(): Font =
        Font(FALLBACK_FAMILY, Font.PLAIN, previewSize)

    private inner class FamilyCellRenderer(private val fallback: Font) :
        javax.swing.DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val label = super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus,
            ) as JLabel
            val family = value as? String
            if (family != null) {

                label.text = HostFonts.displayName(family)

                val hostFont = HostFonts.resolveOrNull(family)

                val resolved = hostFont?.deriveFont(LIST_ITEM_SIZE.toFloat())
                    ?: Font(family, Font.PLAIN, LIST_ITEM_SIZE)

                val candidate = if (hostFont != null || isFamilyAvailable(family, resolved)) {
                    resolved
                } else {

                    fallback.deriveFont(LIST_ITEM_SIZE.toFloat())
                }

                label.font = dev.cdl.font.fontForLabelText(
                    candidate = candidate,
                    text = label.text,

                    fallback = fallback.deriveFont(LIST_ITEM_SIZE.toFloat()),
                )
            }
            return label
        }
    }

    private companion object {

        const val FALLBACK_FAMILY = DEFAULT_FONT_FAMILY

        const val LIST_ITEM_SIZE = 14
    }

    fun availableFamilyCount(): Int = allFamilies.size

    private fun loadFamilies(): List<String> = runCatching {

        HostFonts.SENTINELS +
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toList()
                .filter { it.isNotBlank() }
    }.getOrElse {

        listOf(FALLBACK_FAMILY)
    }
}

internal fun createFontSettingRow(
    key: String,
    initialFamily: String,
    previewSize: Int,
    onCommit: (String) -> Unit,
): Pair<JPanel, FontPicker> {
    require(SettingRegistry.descriptor(key)?.kind == SettingKind.FONT) {
        "createFontSettingRow 的 key 必须指向 FONT 项：$key"
    }
    val picker = FontPicker(
        originalFamily = initialFamily,
        previewSize = previewSize,
        onCommit = onCommit,
    )
    picker.setExpanded(true)
    return picker.root to picker
}

internal object JButtonFactory {

    fun primary(text: String) = ThemeButton(text).apply {
        font = font.deriveFont(Font.BOLD)
    }
}

internal inline fun onEdtSafely(what: String, crossinline block: () -> Unit) {
    SwingUtilities.invokeLater {
        runCatching { block() }.onFailure {
        }
    }
}

internal fun isFamilyAvailable(family: String, resolved: Font): Boolean =
    resolved.family.equals(family, ignoreCase = true)

fun previewFontLabel(family: String, resolved: Font): String {
    if (HostFonts.isSentinel(family)) {
        return if (HostFonts.resolveOrNull(family) != null) {
            HostFonts.displayName(family) + "（宿主内置，非系统回退）"
        } else {
            HostFonts.displayName(family) + "（宿主不可用，已回退系统字体）"
        }
    }
    return if (isFamilyAvailable(family, resolved)) family
    else "$family（系统回退为 ${resolved.family}）"
}
