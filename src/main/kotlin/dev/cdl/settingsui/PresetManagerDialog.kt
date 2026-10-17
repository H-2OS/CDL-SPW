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

/**
 * 「删除预设…」/「更改预设…」两个入口的**模式**。
 *
 * 两个入口打开的是**同一个**管理窗（预设列表 + 删除 / 覆盖 / 重命名）——
 * 模式只决定**标题**与"还没选中时给哪条提示"，让用户一眼知道自己是从哪个入口进来的，
 * 而不是看到两个长得一样、行为也一样的窗（那种窗会让人以为其中一个是坏的）。
 */
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

/**
 * **预设管理窗** —— 删除 / 更改（覆盖）/ 重命名，用户原话：
 *
 * > 「**在设置项中要提供删除、更改预设的入口**。」
 *
 * ## 四个入口共用这一个窗
 *
 * | 入口 | 形态 |
 * | --- | --- |
 * | SPW 设置页「删除预设…」 | `preference_config.json` 的 `button` → `CdlPlugin.deletePreset` |
 * | SPW 设置页「更改预设…」 | 同上 → `CdlPlugin.changePreset` |
 * | 词岛右键菜单「删除预设…」 | `IslandPopupMenu.buildPresetMenu` 的菜单项 |
 * | 词岛右键菜单「更改预设…」 | 同上 |
 *
 * 四处的**落盘语义必须完全相同**（删到哪、覆盖哪些键、改名怎么校验），
 * 故实现只有这一处 —— 与 `showFontSourceDialog` 同一分工：
 * `host` / `window` 侧只负责"在 EDT 上调它、失败时 toast"。
 *
 * ## 三条安全语义（都是刻意的）
 *
 * 1. **删除 = 移入 `presets/_deleted/`**，不是 `delete()`（见 [PresetStore.moveToDeleted]）；
 * 2. **内置预设不可删 / 不可改**（它们是代码里的常量，没有文件）——
 *    按钮**置灰**（[updateActionState]）+ 选中时在状态行**说明原因**，不做"点了没反应"；
 * 3. **删除与覆盖都要二次确认**，且确认文案里**指名道姓**（哪个预设、文件叫什么、去哪了）。
 *
 * ## 结果必须让用户看见
 *
 * 成功与失败都走 [report]：状态行 + 注入的 `toast`。
 * 宿主是 GUI 子系统，`System.err` 是黑洞（见 `CdlRuntime.notifyWarning` 的 KDoc），
 * 而"点了没反应"正是本项目最难归因的缺陷类型。
 *
 * ## 主题
 *
 * 走唯一出口 [applyToolWindowTheme]（`tools/check_toolwindow_theme.py` 静态守这条）。
 * ⚠️ **例外（如实记）**：删除 / 覆盖的**二次确认**用的是 `JOptionPane.showConfirmDialog`
 * —— 它由 LAF 绘制、**不跟本窗的主题**。这与 `FontSourceDialog` 的告警弹窗是同一形态
 * （项目既有先例），换来的是"不必手写一个确认窗"。若将来要求确认框也跟主题，
 * 应当抽一个自绘的确认组件，而不是在这里另写一套配色。
 *
 * ## 线程
 *
 * [show] 必须在 **EDT** 上调用（构造 Swing 组件）。
 */
internal class PresetManagerDialog private constructor(
    /** 当前设置系统 —— 预设目录来自它，覆盖用的快照也来自它。 */
    private val runtime: SettingsRuntime,
    private val mode: PresetManagerMode,
    /** 结果反馈通道（由 `host` / `window` 侧注入，见 [report]）。 */
    private val toast: (message: String, warning: Boolean) -> Unit,
) {

    /** 由 [show] 在构造之后赋值 —— 构造期**不得**触碰它（见 [init] 的注释）。 */
    private lateinit var dialog: JDialog

    // ⚠️ **所有字段都声明在 `init` 之前**：Kotlin 按**声明顺序**初始化，
    //    而列表的选中监听会读下面那几个按钮的 `isEnabled` ——
    //    "构造期回调 + 晚初始化字段"正是本项目 BUG-093 的形态（表现是时好时坏的 NPE）。
    private val listModel = DefaultListModel<PresetRow>()
    private val presetList = JList(listModel)
    private val nameField = JTextField(18)
    private val statusLabel = JLabel(" ").apply {
        // 贴左 + 左对齐：`BoxLayout` 默认按 `alignmentX = 0.5` 把子件居中，
        // 而这一行是"说明 / 结果"，居中读起来像标题。
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

    // ── 布局 ────────────────────────────────────────────────────────────

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
                // ⚠️ **2026-09-24 更正**：原文写「`applyToolWindowTheme` 只认 JPanel/JLabel/按钮/文本框，
                //    JScrollPane 与 JViewport 都落进它的 `else -> Unit`」—— **已不成立**：
                //    `ToolWindowTheme` 现已覆盖**滚动家族**（`JScrollPane` / `JViewport` / `JScrollBar`，见其覆盖表）。
                //    这里仍显式上色是**有意的冗余** —— 让本视口底色不依赖主题实现。
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

    // ── 列表 ────────────────────────────────────────────────────────────

    /**
     * 重新扫一遍预设目录并刷新列表。
     *
     * 删除 / 改名之后**必须**调它 —— 列表反映的是**磁盘现状**，不是内存里的一份副本。
     * （与 `buildPresetMenu` "每次弹出都现扫"同一取舍：预设是文件，用户可能在插件运行
     * 期间往目录里丢了一个。）
     */
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

    /** 重新选中指定名字的行（改名后保持"用户在同一个位置"的连续感）。 */
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

    /**
     * **启用态的唯一出口**：选中变化时同步三个按钮与提示行。
     *
     * 内置预设**置灰**而不是"点了再报错" —— 能靠界面消除的状态，不靠事后报错
     * （见 MEMORY「不要为防用户犯错静默吞掉他的输入」与"被门控的控件在被触碰时自动解门控"）。
     *
     * ## 为什么门控判据是"有没有文件"，而说明用的是 [PresetStore.isBuiltIn]
     *
     * 两者在**今天**等价（内置项没有文件），但不等价的场景是可以构造的：
     * 用户手工在 `presets/` 里放一个与内置预设**同名**的文件时，列表里会有两行同名 ——
     * 内置那行没有文件、文件那行有文件。此时**用户自己那个文件必须还能删**
     * （它是用户的数据），故门控只能看"有没有文件"，不能看名字。
     * 名字判据只用来**解释原因**（`isBuiltIn` 是这条规则在 `PresetStore` 里的唯一定义处）。
     */
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

    // ── 三个动作 ────────────────────────────────────────────────────────

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
        // 快照只取一次：`live` 是**当前生效态**（与右键菜单 / 取色器读的是同一个）。
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

    /**
     * 结果反馈的**唯一出口**：状态行 + toast（+ stderr 供诊断）。
     *
     * `runCatching` 包住 toast 是刻意的 —— 不让"提示本身失败"再引发一次崩溃
     * （与 `IslandPopupMenu.notifyToast` 同一处理）。
     */
    private fun report(message: String, warning: Boolean) {
        statusLabel.text = message
        // ⚠️ 状态行会被窗口宽度裁掉（长文案尤其），故**同时**挂 tooltip ——
        //    "失败必须指名道姓"要求那句话真的能被读到，不能只留一个省略号。
        statusLabel.toolTipText = message
        System.err.println("[CDL] $message")
        runCatching { toast(message, warning) }
            .onFailure { System.err.println("[CDL] 发送提示失败（提示内容：$message）：$it") }
    }

    companion object {
        /**
         * 弹出管理窗并**阻塞**直到用户关闭它。
         *
         * @param owner 父窗口。`null` 表示无属主（`on_click` 入口就是这种情况，
         *   此时 `setLocationRelativeTo(null)` 表现为屏幕居中，是合法调用）。
         * @param toast 结果反馈通道（`settingsui` **不碰 `WorkshopApi`** —— 分层约定，见 `ToolWindows`）。
         */
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
            // ⚠️ `state.dialog` 必须在**构造之后**才赋值 —— 构造期（`init` → `reload`）
            //    绝不触碰它，否则就是"构造期回调读晚初始化字段"（BUG-093）。
            state.dialog = dialog
            dialog.contentPane = state.root
            dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            // ★ 主题**唯一出口**（`tools/check_toolwindow_theme.py` 静态守这条）。
            //   ⚠️ 2026-09-23：`fontProvider` 由**留空**改为显式传 `toolWindowFontProvider()`
            //   —— 用户原话「（自绘窗口）**也还没随所选字体**」。留空 = 回退出厂默认族
            //   `Microsoft YaHei UI`，**不随用户在设置里选的族名**。
            //   （默认值现在也是同一个 provider，显式传是为了在代码里看得见。）
            applyToolWindowTheme(dialog.contentPane, dialog.rootPane, toolWindowFontProvider())
            dialog.pack()
            // 一句话列表 + 一行输入 + 三个按钮：给个下限，免得不同字体度量下挤成一团。
            dialog.minimumSize = Dimension(maxOf(dialog.width, 480), maxOf(dialog.height, 340))
            dialog.addWindowListener(object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) {
                    // peer 建立后原生标题栏才刷得动（构造期那次取不到 HWND，属静默失效）。
                    syncNativeTitleBar(dialog.contentPane, dark = !ToolWindowPalette.isLight)
                }
            })
            dialog.setLocationRelativeTo(owner)
            // `isVisible = true` 会**阻塞**直到窗口关闭（模态）。
            // 点窗口的 X 等同"关闭"，不落盘 —— 每个动作各自确认，故没有"未提交的编辑"。
            dialog.isVisible = true
        }
    }
}

/**
 * 列表一行。[file] 为 `null` 表示**内置预设**（代码里的常量，没有文件 → 不可删 / 不可改）。
 */
private class PresetRow(val name: String, val file: File?)

/**
 * 列表渲染器：内置项**加后缀并置灰** —— 用户必须一眼看出"这一条不能删"，
 * 而不是点下去才被告知。
 *
 * ⚠️ 未选中时要**自己上色**：`applyToolWindowTheme` 只刷**组件树里已有的**组件，
 * 而列表单元格是 `JList` 按需创建、由渲染器返回的，它刷不到
 * （本项目"同族漏改"的老坑：窗口壳跟了主题、内容没跟）。
 */
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

/**
 * 供**四个入口**（设置页两个按钮 + 右键菜单两条）共用的便捷入口。
 *
 * 与 `showFontSourceDialog` 同一分工：调用方只负责"在 EDT 上调它、失败时 toast"，
 * "从哪取设置、往哪落盘"全在这里 —— 四个入口的语义不可能漂移。
 *
 * @return 是否真的弹了窗。设置系统不可用时返回 `false`，并且**已经 toast 说明原因**
 *   （"点了没反应"是本项目最难归因的缺陷类型，不允许静默）。
 */
internal fun showPresetManagerDialog(
    owner: Window?,
    mode: PresetManagerMode,
    toast: (message: String, warning: Boolean) -> Unit,
): Boolean {
    val runtime = SettingsRuntimeHolder.current
    if (runtime == null) {
        val message = "预设管理不可用：设置系统尚未就绪（插件可能正在启动或卸载）"
        System.err.println("[CDL] $message")
        runCatching { toast(message, true) }
        return false
    }
    PresetManagerDialog.show(owner, runtime, mode, toast)
    return true
}
