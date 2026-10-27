// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.parseHexOrNull
import dev.cdl.settings.toColor
import dev.cdl.settings.toHexString
import dev.cdl.settings.toPackedArgb
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class ColorPicker(

    private var originalValue: Int,

    private val key: String? = null,

    private val onPreview: (Int) -> Unit,

    private val onCommit: (Int) -> Unit,
) {

    var value: Int = originalValue and 0xFFFFFF or ALPHA_OPAQUE
        private set

    val root: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
    }

    private val redSlider = channelSlider()
    private val greenSlider = channelSlider()
    private val blueSlider = channelSlider()
    private val redField = channelField()
    private val greenField = channelField()
    private val blueField = channelField()

    private val hexField = JTextField(9)

    private val previewSwatch = JPanel()

    private val applyButton = JButton("应用")

    private val hexHint = JLabel(" ")

    private var updatingFromCode = false

    private var lastCommittedValue: Int = value

    private var onChange: ((Int) -> Unit)? = null

    init {
        require(key == null || SettingRegistry.descriptor(key)?.kind ==
            dev.cdl.settings.SettingKind.COLOR) {
            "ColorPicker 的 key 必须指向 COLOR 项：$key"
        }

        val rows = JPanel(GridBagLayout())
        rows.isOpaque = false
        var row = 0
        for ((label, slider, field) in listOf(
            Triple("R", redSlider, redField),
            Triple("G", greenSlider, greenField),
            Triple("B", blueSlider, blueField),
        )) {
            rows.add(labelOf(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST
                insets = Insets(2, 0, 2, 6)
            })
            rows.add(slider, GridBagConstraints().apply {
                gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 0, 2, 6)
            })
            rows.add(field, GridBagConstraints().apply {
                gridx = 2; gridy = row; anchor = GridBagConstraints.WEST
                insets = Insets(2, 0, 2, 0)
            })
            row++
        }

        previewSwatch.apply {
            preferredSize = Dimension(56, 56)
            minimumSize = Dimension(56, 56)
            border = BorderFactory.createLineBorder(Color(0x66, 0x66, 0x66))
            toolTipText = "当前颜色（双击重置为打开时的颜色）"
        }

        previewSwatch.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) resetToOriginal()
            }
        })

        hexField.toolTipText = "支持 #RRGGBB / RRGGBB / #RGB / rgb(r,g,b)，不区分大小写"
        applyButton.toolTipText = "把输入框中的颜色应用到当前色（不落盘）"

        val hexRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JLabel("十六进制"))
            add(hexField)
            add(applyButton)
        }

        hexHint.foreground = ERROR_RED
        hexHint.font = hexHint.font.deriveFont(hexHint.font.size2D - 1f)

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JButton("取消").apply {
                toolTipText = "放弃本次改动，回到打开取色器时的颜色"
                addActionListener { cancel() }
            })
            add(JButton("完成").apply {
                toolTipText = "保留当前颜色并收起取色器"
                addActionListener { commitAndBringDown() }
            })
        }

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(6, 0, 4, 0)

            val top = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(rows)
                add(previewSwatch)
            }
            add(top)
            add(Box.createVerticalStrut(4))
            add(hexRow)
            add(hexHint)
            add(Box.createVerticalStrut(2))
            add(buttonRow)
        }
        root.add(body)

        installWiring()
        refreshViews(broadcast = false)
        lastCommittedValue = value
    }

    fun setValueFromModel(packed: Int) {
        val next = (packed and 0xFFFFFF) or ALPHA_OPAQUE
        if (next == value) return
        value = next
        originalValue = next
        lastCommittedValue = next
        refreshViews(broadcast = false)
    }

    fun rebase(packed: Int) {
        originalValue = (packed and 0xFFFFFF) or ALPHA_OPAQUE
    }

    fun setExpanded(expanded: Boolean) {
        root.isVisible = expanded
        root.revalidate()
        root.repaint()
    }

    val isExpanded: Boolean get() = root.isVisible

    fun focusHexField() {
        SwingUtilities.invokeLater { hexField.requestFocusInWindow() }
    }

    private fun resetToOriginal() {
        value = originalValue
        refreshViews(broadcast = true)
    }

    private fun cancel() {
        resetToOriginal()
        onCommit(value)
        lastCommittedValue = value
        setExpanded(false)
    }

    private fun commitAndBringDown() {
        applyHexIfValid()
        onCommit(value)
        lastCommittedValue = value
        setExpanded(false)
    }

    private fun commitIfChanged() {
        if (value == lastCommittedValue) return
        lastCommittedValue = value
        onCommit(value)
    }

    private fun installWiring() {
        for ((slider, field) in listOf(
            redSlider to redField,
            greenSlider to greenField,
            blueSlider to blueField,
        )) {

            slider.addChangeListener {
                if (updatingFromCode) return@addChangeListener
                value = packChannels()
                refreshViews(broadcast = true, syncTextFields = true, syncSliders = false)
            }

            slider.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseReleased(event: java.awt.event.MouseEvent) {
                    commitIfChanged()
                }
            })

            field.addActionListener { applyChannelField(slider, field) }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(event: FocusEvent) = applyChannelField(slider, field)
            })
        }

        hexField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = onHexTextChanged()
            override fun removeUpdate(event: DocumentEvent) = onHexTextChanged()
            override fun changedUpdate(event: DocumentEvent) = onHexTextChanged()
        })
        hexField.addActionListener { applyHexIfValid() }
        hexField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) = applyHexIfValid()
        })

        hexField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ESCAPE) cancel()
            }
        })

        applyButton.addActionListener { applyHexIfValid() }

        for ((slider, field) in listOf(
            redSlider to redField,
            greenSlider to greenField,
            blueSlider to blueField,
        )) {
            slider.addKeyListener(object : KeyAdapter() {
                override fun keyReleased(event: KeyEvent) {
                    if (event.keyCode in NUDGE_KEYS) commitIfChanged()
                }
            })
            field.addKeyListener(object : KeyAdapter() {
                override fun keyReleased(event: KeyEvent) {
                    if (event.keyCode in NUDGE_KEYS) commitIfChanged()
                }
            })
        }
    }

    private fun onHexTextChanged() {

        if (updatingFromCode) return
        val parsed = parseHexOrNull(hexField.text)
        if (parsed == null) {

            markHexError("颜色格式无效：#RRGGBB / #RGB / rgb(r,g,b)")
            return
        }
        markHexOk()
        val next = parsed and 0xFFFFFF or ALPHA_OPAQUE
        if (next == value) return
        value = next

        refreshViews(broadcast = true, syncTextFields = false, syncSliders = true)
    }

    private fun applyHexIfValid() {
        val parsed = parseHexOrNull(hexField.text)
        if (parsed == null) {
            markHexError("颜色格式无效：#RRGGBB / #RGB / rgb(r,g,b)")

            updatingFromCode = true
            hexField.text = value.toHexString()
            updatingFromCode = false
            return
        }
        markHexOk()
        val next = parsed and 0xFFFFFF or ALPHA_OPAQUE
        value = next
        updatingFromCode = true
        hexField.text = next.toHexString()
        updatingFromCode = false
        refreshViews(broadcast = true, syncTextFields = false, syncSliders = true)
        commitIfChanged()
    }

    private fun applyChannelField(slider: JSlider, field: JTextField) {
        val parsed = field.text.trim().toIntOrNull()
        if (parsed == null) {
            field.text = slider.value.toString()
            return
        }
        slider.value = parsed.coerceIn(0, 255)

        value = packChannels()
        refreshViews(broadcast = true, syncTextFields = true, syncSliders = false)
        commitIfChanged()
    }

    private fun packChannels(): Int =
        ALPHA_OPAQUE or (redSlider.value shl 16) or (greenSlider.value shl 8) or blueSlider.value

    private fun markHexError(message: String) {
        hexField.border = BorderFactory.createLineBorder(ERROR_RED)
        hexHint.text = message
        hexHint.foreground = ERROR_RED
    }

    private fun markHexOk() {
        hexField.border = defaultFieldBorder
        hexHint.text = " "
    }

    private fun refreshViews(
        broadcast: Boolean,
        syncTextFields: Boolean = true,
        syncSliders: Boolean = true,
    ) {
        val color = value.toColor()

        updatingFromCode = true
        try {
            if (syncSliders) {
                redSlider.value = color.red
                greenSlider.value = color.green
                blueSlider.value = color.blue
            }
            if (syncTextFields) {
                redField.text = color.red.toString()
                greenField.text = color.green.toString()
                blueField.text = color.blue.toString()
            }
            if (broadcast) {

                if (!hexField.hasFocus()) hexField.text = value.toHexString()
            } else {
                hexField.text = value.toHexString()
                redField.text = color.red.toString()
                greenField.text = color.green.toString()
                blueField.text = color.blue.toString()
            }
        } finally {
            updatingFromCode = false
        }

        previewSwatch.background = color
        previewSwatch.repaint()

        if (broadcast) onChange?.invoke(value)
    }

    fun onValueChanged(listener: (Int) -> Unit) {
        onChange = listener
    }

    fun preferredPanelSize(): Dimension = Dimension(360, root.preferredSize.height)

    private fun labelOf(text: String) = JLabel(text).apply {
        preferredSize = Dimension(14, 20)
        horizontalAlignment = SwingConstants.LEFT
    }

    private fun channelSlider() = JSlider(0, 255, 0).apply {
        preferredSize = Dimension(180, 22)

        paintTicks = false
        paintLabels = false
        isFocusable = true
    }

    private fun channelField() = JTextField(3).apply {
        horizontalAlignment = SwingConstants.RIGHT
    }

    private companion object {

        const val ALPHA_OPAQUE = 0xFF000000.toInt()

        val ERROR_RED: Color = Color(0xD1, 0x34, 0x38)

        val NUDGE_KEYS = setOf(
            KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
            KeyEvent.VK_HOME, KeyEvent.VK_END,
        )

        val defaultFieldBorder = JTextField().border
    }
}

internal fun createColorSettingRow(
    owner: Component?,
    key: String,
    initialValue: Int,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
): Pair<JPanel, ColorPicker> {
    val picker = ColorPicker(
        originalValue = initialValue,
        key = key,
        onPreview = onPreview,
        onCommit = onCommit,
    )

    picker.onValueChanged { onPreview(it) }
    picker.setExpanded(true)
    picker.rebase(initialValue)
    return picker.root to picker
}
