// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.settings.PresetStore
import dev.cdl.settings.SettingsRuntime
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.TitledBorder

internal enum class PresetManagerMode(val title: String, val emptyHint: String) {
    DELETE(
        "删除配置预设",
        "选中一个预设后点「删除…」。内置预设没有文件，不可删除。",
    ),
    CHANGE(
        "更改配置预设",
        "选中一个预设后点「用当前配置覆盖…」；也可以在这里重命名。",
    ),
}

internal class PresetManagerDialog private constructor(

    private val runtime: SettingsRuntime,
    private val mode: PresetManagerMode,

    private val toast: (message: String, warning: Boolean) -> Unit,
) {

    private lateinit var dialog: JDialog

    private val listModel = DefaultListModel<PresetRow>()
    private val presetList = JList(listModel)
    private val nameField = JTextField(18)
    private val statusLabel = JLabel(" ").apply {

        alignmentX = 0.0f
        horizontalAlignment = javax.swing.SwingConstants.LEFT
    }

    private val deleteButton = ThemeButton("删除…").apply {
        toolTipText = "把选中的预设移入 presets/${PresetStore.DELETED_DIR}/ 归档目录（可手工移回）"
        addActionListener { deleteSelected() }
    }
    private val overwriteButton = ThemeButton("用当前配置覆盖…").apply {
        toolTipText = "把当前设置写进选中的预设文件（文件名不变，预设名就不变）"
        addActionListener { overwriteSelected() }
    }
    private val renameButton = ThemeButton("重命名").apply {
        toolTipText = "改名 = 改文件名（预设名就是文件名）"
        addActionListener { renameSelected() }
    }
    private val closeButton = ThemeButton("关闭").apply {
        addActionListener { dialog.dispose() }
    }

    private val root = JPanel(BorderLayout(0, 8)).apply {
        isOpaque = true
        background = ToolWindowPalette.bg
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
    }

    init {
        root.add(buildTop(), BorderLayout.NORTH)
        root.add(buildCenter(), BorderLayout.CENTER)
        root.add(buildBottom(), BorderLayout.SOUTH)
        reload()
    }

    private fun buildTop(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(
            JLabel(
                "<html><b>${mode.title}</b>　预设名就是文件名，存在数据目录的 presets/ 里。" +
                    "<br>删除是<b>移入 presets/${PresetStore.DELETED_DIR}/</b>（可手工移回），" +
                    "不是永久删除。</html>",
            ),
        )
        add(Box.createVerticalStrut(6))
    }

    private fun buildCenter(): JPanel = JPanel(BorderLayout(0, 4)).apply {
        isOpaque = false
        presetList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 10
            cellRenderer = PresetCellRenderer()
            background = ToolWindowPalette.fieldBg
            foreground = ToolWindowPalette.fg
            addListSelectionListener { event ->
                if (event.valueIsAdjusting) return@addListSelectionListener
                val row = presetList.selectedValue
                nameField.text = row?.name ?: ""
                updateActionState(row)
            }
        }
        add(
            JScrollPane(presetList).apply {

                viewport.background = ToolWindowPalette.fieldBg
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(ToolWindowPalette.line),
                    "预设（内置项在前；内置项不可删除 / 不可更改）",
                    TitledBorder.LEADING,
                    TitledBorder.TOP,
                )
            },
            BorderLayout.CENTER,
        )
    }

    private fun buildRenameRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(JLabel("新名称："))
        nameField.toolTipText = "改名 = 改文件名；预设名就是文件名"
        add(nameField)
        add(renameButton)
    }

    private fun buildBottom(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(buildRenameRow())
        add(Box.createVerticalStrut(6))
        add(statusLabel)
        add(Box.createVerticalStrut(6))
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                add(deleteButton)
                add(overwriteButton)
                add(closeButton)
            },
        )
    }

    private fun reload() {
        listModel.clear()
        for (preset in PresetStore.BUILT_IN) {
            listModel.addElement(PresetRow(preset.name, null))
        }
        for (file in PresetStore.files(runtime.dataDirectory())) {
            listModel.addElement(PresetRow(file.nameWithoutExtension, file))
        }
        if (listModel.size == 0) {
            statusLabel.text = "预设目录里没有任何预设文件"
            return
        }
        updateActionState(presetList.selectedValue)
    }

    private fun selectByName(name: String) {
        for (i in 0 until listModel.size) {
            if (listModel.getElementAt(i).name == name) {
                presetList.selectedIndex = i
                return
            }
        }
        presetList.clearSelection()
        updateActionState(null)
    }

    private fun selected(): PresetRow? = presetList.selectedValue

    private fun updateActionState(row: PresetRow?) {
        val actionable = row?.file != null
        deleteButton.isEnabled = actionable
        overwriteButton.isEnabled = actionable
        renameButton.isEnabled = actionable
        statusLabel.text = when {
            row == null -> mode.emptyHint
            actionable -> "已选中「${row.name}」"
            PresetStore.isBuiltIn(row.name) ->
                "「${row.name}」是内置预设（代码里的常量，没有文件）：不可删除、不可更改。"
            else -> "「${row.name}」没有对应文件，不可删除 / 不可更改。"
        }
    }

    private fun deleteSelected() {
        val row = selected() ?: return
        val file = row.file
        if (file == null) {
            report("「${row.name}」是内置预设，没有文件，无法删除。", warning = true)
            return
        }
        val answer = JOptionPane.showConfirmDialog(
            dialog,
            "删除预设「${row.name}」？\n\n" +
                "文件会被移入 presets/${PresetStore.DELETED_DIR}/ 归档目录（不是永久删除），\n" +
                "需要时可以把它移回 presets/ 恢复。",
            "删除预设",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (answer != JOptionPane.OK_OPTION) {
            statusLabel.text = "已取消删除「${row.name}」"
            return
        }
        PresetStore.moveToDeleted(runtime.dataDirectory(), file).fold(
            onSuccess = { moved ->
                reload()
                report(
                    "已删除预设「${row.name}」：文件已移到 " +
                        "presets/${PresetStore.DELETED_DIR}/${moved.fileName}（可手工移回）",
                    warning = false,
                )
            },
            onFailure = { failure ->
                report(
                    "删除预设「${row.name}」失败：${failure.message ?: failure.javaClass.simpleName}",
                    warning = true,
                )
            },
        )
    }

    private fun overwriteSelected() {
        val row = selected() ?: return
        val file = row.file
        if (file == null) {
            report("「${row.name}」是内置预设，没有文件，无法更改。", warning = true)
            return
        }

        val values = runtime.live.asMap()
        val answer = JOptionPane.showConfirmDialog(
            dialog,
            "用当前配置覆盖预设「${row.name}」？\n\n" +
                "这会改写文件 ${file.name}（共 ${values.size} 项设置），\n" +
                "它原来存的那份配置将不再保留。",
            "更改预设",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (answer != JOptionPane.OK_OPTION) {
            statusLabel.text = "已取消更改「${row.name}」"
            return
        }
        PresetStore.overwrite(file, values).fold(
            onSuccess = {
                report("已用当前配置覆盖预设「${row.name}」（${values.size} 项）", warning = false)
            },
            onFailure = { failure ->
                report(
                    "更改预设「${row.name}」失败：${failure.message ?: failure.javaClass.simpleName}",
                    warning = true,
                )
            },
        )
    }

    private fun renameSelected() {
        val row = selected() ?: return
        val file = row.file
        if (file == null) {
            report("「${row.name}」是内置预设，没有文件，无法重命名。", warning = true)
            return
        }
        val newName = nameField.text
        if (newName.trim() == row.name) {
            statusLabel.text = "新名称与当前名称相同，未改名"
            return
        }
        PresetStore.rename(runtime.dataDirectory(), file, newName).fold(
            onSuccess = { moved ->
                val renamed = moved.fileName.toString().substringBeforeLast('.')
                reload()
                selectByName(renamed)
                report("已把预设「${row.name}」改名为「$renamed」", warning = false)
            },
            onFailure = { failure ->
                report(
                    "改名「${row.name}」失败：${failure.message ?: failure.javaClass.simpleName}",
                    warning = true,
                )
            },
        )
    }

    private fun report(message: String, warning: Boolean) {
        statusLabel.text = message

        statusLabel.toolTipText = message
        runCatching { toast(message, warning) }
            .onFailure {  }
    }

    companion object {

        fun show(
            owner: Window?,
            runtime: SettingsRuntime,
            mode: PresetManagerMode,
            toast: (String, Boolean) -> Unit,
        ) {
            check(SwingUtilities.isEventDispatchThread()) {
                "PresetManagerDialog.show 必须在 EDT 上调用（它会构造 Swing 组件）"
            }
            val state = PresetManagerDialog(runtime, mode, toast)
            val dialog = JDialog(JOptionPane.getRootFrame(), mode.title, true)

            state.dialog = dialog
            dialog.contentPane = state.root
            dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

            applyToolWindowTheme(dialog.contentPane, dialog.rootPane, toolWindowFontProvider())
            dialog.pack()

            dialog.minimumSize = Dimension(maxOf(dialog.width, 480), maxOf(dialog.height, 340))
            dialog.addWindowListener(object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) {

                    syncNativeTitleBar(dialog.contentPane, dark = !ToolWindowPalette.isLight)
                }
            })
            dialog.setLocationRelativeTo(owner)

            dialog.isVisible = true
        }
    }
}

private class PresetRow(val name: String, val file: File?)

private class PresetCellRenderer : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val label = super.getListCellRendererComponent(
            list, value, index, isSelected, cellHasFocus,
        ) as JLabel
        val row = value as? PresetRow ?: return label
        label.text = if (row.file == null) "${row.name}　（内置）" else row.name
        if (!isSelected) {
            label.background = ToolWindowPalette.fieldBg
            label.foreground = if (row.file == null) ToolWindowPalette.muted else ToolWindowPalette.fg
        }
        return label
    }
}

internal fun showPresetManagerDialog(
    owner: Window?,
    mode: PresetManagerMode,
    toast: (message: String, warning: Boolean) -> Unit,
): Boolean {
    val runtime = SettingsRuntimeHolder.current
    if (runtime == null) {
        val message = "预设管理不可用：设置系统尚未就绪（插件可能正在启动或卸载）"
        runCatching { toast(message, true) }
        return false
    }
    PresetManagerDialog.show(owner, runtime, mode, toast)
    return true
}
