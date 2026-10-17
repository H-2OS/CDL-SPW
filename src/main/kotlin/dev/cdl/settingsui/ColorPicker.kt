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

/**
 * 取色器 —— **内联面板**，不是模态对话框。
 *
 * ## 为什么是面板而不是 `JDialog`（硬约束，不是口味）
 *
 * 设置窗口本身是非模态 `JDialog`（`recon/07` §0 铁律）。若取色器再弹一个**模态**子窗，
 * 就会出现"非模态父窗被模态子窗挡住且父窗仍可点"的错乱焦点状态；若弹**非模态**子窗，
 * 则用户可能开着两个取色器改同一个键。两条路都通向状态不一致。
 * 内联面板把"改色"与"改哪些键"绑在同一个窗口生命周期里，从根上消除这个类别的问题。
 * 代价是面板会占用设置窗口右侧的一块纵向空间，故做成**可折叠**的（见 [setExpanded]）。
 *
 * ## 零宿主 API 依赖
 *
 * 本文件只 import `java.awt` / `javax.swing` / 本项目 `settings` 包。
 * **不碰 `WorkshopApi`**。理由：取色器是纯 UI 运算（RGB ↔ hex），
 * 一旦它依赖宿主 API，"单独调试取色器"就变成"必须把插件装进 SPW"，
 * 迭代成本会高一个数量级。
 *
 * ## `alphaAllowed` 恒为 `false`
 *
 * `首版设置总表.md` §1.6 规定首版全部颜色项不含 alpha；注册表的 `verify()` 甚至断言
 * "颜色项一律 `alphaAllowed = false`"。因此本面板**不提供** alpha 滑杆与 8 位 hex 解析，
 * 输出一律为 `0xFFRRGGBB`。
 *
 * 注意 `parseHexOrNull` 本身**拒绝** 8 位输入（返回 `null`）—— 解析层已经替我们把住了门，
 * 本面板只需把"非法输入"如实反馈给用户即可，无需额外判长。
 *
 * ## 拖动中只预览、不落盘
 *
 * 面板通过 [onPreview] / [onCommit] 两个回调把"当前色"交出去，
 * **它自己不接触 `SettingsRuntime`、也不接触磁盘**。这样做的收益有两条：
 * ① 面板可独立复用与测试（传入任意回调即可）；
 * ② "何时落盘"这个纪律只存在于设置窗口一处，不会散落到每个控件里
 *   （`recon/09` §2.2.1「松手才应用，不自动」）。
 */
internal class ColorPicker(
    /** 打开面板时的颜色，用于"取消"回退与双击重置。 */
    private var originalValue: Int,
    /** `SettingKind.COLOR` 项的注册表键，仅用于查 `alphaAllowed` 与日志。 */
    private val key: String? = null,
    /**
     * 拖动中回调（不落盘）。调用方会按 `PreviewKind` 决定是立即发布还是合并发布。
     * 参数是 `0xFFRRGGBB` 打包值。
     */
    private val onPreview: (Int) -> Unit,
    /**
     * 落盘回调。**触发时机 = 一次连续拖动结束**（滑杆 `mouseReleased`）、
     * hex 输入框失焦或回车、或点"应用"。
     */
    private val onCommit: (Int) -> Unit,
) {

    /** 面板当前色（打包 ARGB，恒不透明）。**这是本类的唯一真值**。 */
    var value: Int = originalValue and 0xFFFFFF or ALPHA_OPAQUE
        private set

    /** 设置窗口开关面板时用来折叠的根容器。 */
    val root: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
    }

    // ── 控件 ────────────────────────────────────────────────────────────
    private val redSlider = channelSlider()
    private val greenSlider = channelSlider()
    private val blueSlider = channelSlider()
    private val redField = channelField()
    private val greenField = channelField()
    private val blueField = channelField()

    private val hexField = JTextField(9)

    /** 预览色块。面板里唯一"用颜色本身作为信息"的控件。 */
    private val previewSwatch = JPanel()

    private val applyButton = JButton("应用")

    /** hex 非法时的行内提示；合法时清空。 */
    private val hexHint = JLabel(" ")

    /**
     * 程序化更新标记 —— **双向同步防死循环的关键**。
     *
     * `hexField` 与滑杆互相驱动：拖滑杆要刷新 hex 文本，改 hex 又要把滑杆挪过去。
     * 若不设标记，`slider → hexField.setText → DocumentListener → setValue(slider) → …`
     * 会在一个事件里来回递归（轻度时表现为控件抖动，重度时 `StackOverflowError`）。
     *
     * 为什么用标记位而不是比较"新旧值是否相等"：相等比较能挡住大部分往返，
     * 但**挡不住规范化的往返** —— 用户输入 `f80` 被规范化成 `#FF8800` 写回输入框时，
     * 文本确实变了（`"f80"` → `"#FF8800"`），此时值没变而文本变了，
     * 比较值相等会误判成"无变化"从而漏掉刷新；反过来若按文本比较，
     * 用户把光标停在中间打字时 `getText()` 可能读到半成品，触发无谓的颜色跳变。
     * 标记位的语义是"这次变化由我自己发起，不要回灌"，判定与文本形态无关，最稳。
     */
    private var updatingFromCode = false

    /** 最近一次成功提交的颜色，供"取消"与"双击重置"回退。 */
    private var lastCommittedValue: Int = value

    /** 颜色变化时对外广播（预览 + 局部刷新）。由 [refreshViews] 调用。 */
    private var onChange: ((Int) -> Unit)? = null

    init {
        require(key == null || SettingRegistry.descriptor(key)?.kind ==
            dev.cdl.settings.SettingKind.COLOR) {
            "ColorPicker 的 key 必须指向 COLOR 项：$key"
        }

        // ── 通道滑杆行 ─────────────────────────────────────────────────
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

        // ── 预览色块 ───────────────────────────────────────────────────
        previewSwatch.apply {
            preferredSize = Dimension(56, 56)
            minimumSize = Dimension(56, 56)
            border = BorderFactory.createLineBorder(Color(0x66, 0x66, 0x66))
            toolTipText = "当前颜色（双击重置为打开时的颜色）"
        }

        // 双击重置：规格 §5.3 要求"双击回到面板打开时的颜色"。
        // 用 mouseClicked 而非 mousePressed，避免与后续可能的拖拽操作抢事件。
        previewSwatch.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) resetToOriginal()
            }
        })

        // ── hex 行 ─────────────────────────────────────────────────────
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

        // ── 按钮行 ─────────────────────────────────────────────────────
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

        // ── 组装 ───────────────────────────────────────────────────────
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

    // ── 对外 API ────────────────────────────────────────────────────────

    /**
     * 外部（设置窗口）改动了同一个键时同步进来。
     *
     * 存在的理由：设置窗口会订阅 `SettingsRuntime`，用户可能在别处（右键菜单）改了同一个颜色。
     * 若面板不跟随，就会出现"窗口关了再开颜色才对"的诡异体验。
     */
    fun setValueFromModel(packed: Int) {
        val next = (packed and 0xFFFFFF) or ALPHA_OPAQUE
        if (next == value) return
        value = next
        originalValue = next
        lastCommittedValue = next
        refreshViews(broadcast = false)
    }

    /** 打开面板时调用：把"当前色"作为取消/重置的基线。 */
    fun rebase(packed: Int) {
        originalValue = (packed and 0xFFFFFF) or ALPHA_OPAQUE
    }

    /** 折叠/展开。折叠时不做任何状态变更（颜色仍在面板里活着）。 */
    fun setExpanded(expanded: Boolean) {
        root.isVisible = expanded
        root.revalidate()
        root.repaint()
    }

    /** 面板是否被展开着（设置窗口用来决定要不要保留它）。 */
    val isExpanded: Boolean get() = root.isVisible

    /** 把焦点给 hex 输入框（面板刚展开时方便键盘操作）。 */
    fun focusHexField() {
        SwingUtilities.invokeLater { hexField.requestFocusInWindow() }
    }

    // ── 行为 ────────────────────────────────────────────────────────────

    /** 双击预览色块 / 点"取消" 的共同落点。 */
    private fun resetToOriginal() {
        value = originalValue
        refreshViews(broadcast = true)
    }

    /**
     * 取消：回退到打开时的颜色，**并把回退后的值提交出去**。
     *
     * 为什么取消也要 commit：预览期间 `live` 已经变成新色了（这是预览的定义）。
     * 若取消只改面板内部状态而不广播，"取消"就只是面板自己在骗自己 ——
     * 词岛上仍然是预览色，且窗口下次刷新还会把旧色读回来，状态就此分叉。
     */
    private fun cancel() {
        resetToOriginal()
        onCommit(value)
        lastCommittedValue = value
        setExpanded(false)
    }

    /** "完成"：提交当前色并收起面板（不关设置窗口）。 */
    private fun commitAndBringDown() {
        applyHexIfValid()
        onCommit(value)
        lastCommittedValue = value
        setExpanded(false)
    }

    /** 只提交有把握的变化；值没变就不产生空提交（避免撤销栈里多出空记录）。 */
    private fun commitIfChanged() {
        if (value == lastCommittedValue) return
        lastCommittedValue = value
        onCommit(value)
    }

    // ── 事件接线 ────────────────────────────────────────────────────────

    private fun installWiring() {
        for ((slider, field) in listOf(
            redSlider to redField,
            greenSlider to greenField,
            blueSlider to blueField,
        )) {
            // 拖动中：只预览，不落盘（`recon/09` §2.2.1）。
            slider.addChangeListener {
                if (updatingFromCode) return@addChangeListener
                value = packChannels()
                refreshViews(broadcast = true, syncTextFields = true, syncSliders = false)
            }
            // 松手：这一刻才落盘。用 mouseReleased 而不是 change 事件，
            // 因为 change 无法区分"拖到一半"与"松手了"。
            slider.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseReleased(event: java.awt.event.MouseEvent) {
                    commitIfChanged()
                }
            })
            // 数值框：回车或失焦时解析并应用。
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
        // Esc：回退到打开时的颜色（规格 §5.2 键盘映射）。
        hexField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ESCAPE) cancel()
            }
        })

        applyButton.addActionListener { applyHexIfValid() }

        // 键盘微调（规格 §5.2）：方向键 ∓1、Shift ∓10、Ctrl ∓25、Home/End 到极值。
        // 这里挂在滑杆的 InputMap 之外，是因为我们要在"键盘改值"后**立即落盘**
        // —— 键盘操作没有"松手"这个时刻，若也等 mouseReleased 就永远等不到。
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

    /** 文本变化时：合法则更新颜色（不落盘），非法则只标红不改色。 */
    private fun onHexTextChanged() {
        // ★ 防死循环：程序化写文本引发的回调直接返回，绝不回灌滑杆。
        if (updatingFromCode) return
        val parsed = parseHexOrNull(hexField.text)
        if (parsed == null) {
            // 非法输入**不改变当前颜色**（`recon/10` §1.5 硬要求）。
            // 关键取舍：这里刻意不弹任何对话框、不"自动纠正"，
            // 因为用户很可能只是打到一半（`#FF88` 是合法前缀但还不是完整色）。
            // 输入过程中弹提示会频繁打断；只在视觉上标红，让用户自己补完。
            markHexError("颜色格式无效：#RRGGBB / #RGB / rgb(r,g,b)")
            return
        }
        markHexOk()
        val next = parsed and 0xFFFFFF or ALPHA_OPAQUE
        if (next == value) return
        value = next
        // 只同步滑杆与色块，**不回写 hexField** —— 回写会把用户正在编辑的
        // `f80` 立刻改成 `#FF8800`，光标位置与输入节奏都会被打断。
        // 规范化留到失焦/回车时做（[applyHexIfValid]），那时用户已经打完字了。
        refreshViews(broadcast = true, syncTextFields = false, syncSliders = true)
    }

    /** 回车/失焦/点"应用"：把 hex 文本规范化回显，并落盘。 */
    private fun applyHexIfValid() {
        val parsed = parseHexOrNull(hexField.text)
        if (parsed == null) {
            markHexError("颜色格式无效：#RRGGBB / #RGB / rgb(r,g,b)")
            // 非法输入时把文本还原成当前颜色，避免输入框长期停留在无效内容上。
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

    /** R/G/B 数值框：解析失败就还原成滑杆的当前值。 */
    private fun applyChannelField(slider: JSlider, field: JTextField) {
        val parsed = field.text.trim().toIntOrNull()
        if (parsed == null) {
            field.text = slider.value.toString()
            return
        }
        slider.value = parsed.coerceIn(0, 255)
        // `slider.value = ...` 会触发 ChangeListener（若值有变），它会改 `value` 并广播。
        // 若值没变则 ChangeListener 不触发，此处手动收敛一次以免文本与真值不一致。
        value = packChannels()
        refreshViews(broadcast = true, syncTextFields = true, syncSliders = false)
        commitIfChanged()
    }

    // ── 内部工具 ────────────────────────────────────────────────────────

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

    /**
     * 把 [value] 反映到所有控件上。
     *
     * @param broadcast 是否调用 [onPreview] 通知外部。**初始化与外部同步时必须传 `false`**
     *   —— 否则"打开设置窗口"这个动作本身就会发布一次变更，
     *   在撤销栈里留下一格什么都没改的记录。
     * @param syncTextFields 是否回写 R/G/B 数值框（用户正在编辑它们时传 `false`）
     * @param syncSliders 是否挪动滑杆（用户正在 hex 框里打字时**要**传 `true`，
     *   因为滑杆挪动不会抢走 hex 框的焦点；反之滑杆拖动时传 `false` 以避免它抢焦点）
     */
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
                // hex 文本只在"不是用户正在打字"时才回写。
                // 判定条件用 `!hexField.hasFocus()`：只要输入框有焦点，
                // 用户就可能正在编辑，此时任何回写都是打扰。
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

    /** 设置窗口用来接"面板内部发生了变化"（拖动/双击重置）的钩子。 */
    fun onValueChanged(listener: (Int) -> Unit) {
        onChange = listener
    }

    /** 面板首选的展开尺寸，供设置窗口做滚动布局时参考。 */
    fun preferredPanelSize(): Dimension = Dimension(360, root.preferredSize.height)

    private fun labelOf(text: String) = JLabel(text).apply {
        preferredSize = Dimension(14, 20)
        horizontalAlignment = SwingConstants.LEFT
    }

    private fun channelSlider() = JSlider(0, 255, 0).apply {
        preferredSize = Dimension(180, 22)
        // 关闭滑杆自带的刻度与标签：这些在 180px 宽度里会挤成一团灰点，
        // 数值信息已经由右侧数值框给出，重复显示只会制造噪声。
        paintTicks = false
        paintLabels = false
        isFocusable = true
    }

    private fun channelField() = JTextField(3).apply {
        horizontalAlignment = SwingConstants.RIGHT
    }

    private companion object {
        /** 不透明黑：所有输出色都带这个 alpha 位（总表 §1.6）。 */
        const val ALPHA_OPAQUE = 0xFF000000.toInt()

        val ERROR_RED: Color = Color(0xD1, 0x34, 0x38)

        /** 键盘改值后应当立即落盘的按键。 */
        val NUDGE_KEYS = setOf(
            KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
            KeyEvent.VK_HOME, KeyEvent.VK_END,
        )

        /**
         * 默认边框的缓存。
         *
         * 为什么缓存：`JTextField` 的 `border` 在 LAF 里是共享实例，
         * 但反复 `createLineBorder` 会不断分配新对象；更重要的是
         * "恢复默认边框"必须恢复成**原来那一个**，否则在不同 LAF 下
         * 会出现边框粗细跳变。`ColorPicker` 的实例数很少（同时最多 1 个可见），
         * 这里缓存一份静态引用是安全的。
         */
        val defaultFieldBorder = JTextField().border
    }
}

/**
 * 供设置窗口调用的一行式入口。
 *
 * 为什么单独包一层：设置窗口在 `when (descriptor.kind)` 分支里需要
 * **一行**构造出控件，若把 `ColorPicker(...)` 的构造与回调接线写在那里，
 * `SettingsWindow` 的分派函数会长到难以阅读。这里把"两个回调怎么分工"固定下来：
 * 拖动 → `onPreview`；松手 → `onCommit`。
 *
 * @param owner 用于定位可能弹出的提示（本实现里不弹，但保留参数以便将来加吸管等特性）
 * @return 拆包后的 `(panel, picker)`：`panel` 挂进布局，`picker` 供外部同步与折叠
 */
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
    // 面板内部的变化（拖动滑杆 / 双击重置）统一从 onChange 出去，
    // 这样设置窗口只需要记住"这一个回调是预览"。
    picker.onValueChanged { onPreview(it) }
    picker.setExpanded(true)
    picker.rebase(initialValue)
    return picker.root to picker
}
