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

/**
 * 字体族选择器 —— **选择 + 实时预览**，同样是内联面板。
 *
 * ## 为什么字体族切换必须走 `PreviewKind.DEFERRED`
 *
 * 这不是取舍，是**技术不可能**（`SettingModel.PreviewKind` 的 KDoc 原文）：
 * `Font.createFont` 与随后的全量字形重塑（中文字库动辄上万字形）**无法在 80 ms 内完成**。
 * 若像数值那样做 80 ms 合并，用户在字体列表里连点十几项时，
 * 合并后的那一次重建会一次性卡住 EDT 数百毫秒，表现为界面冻结。
 *
 * 因此本面板的策略是：
 * - **选中项的"高亮"是即时的**（`JList` 自己处理，零成本）；
 * - **预览区用回退字体即时渲染**，让用户看到"这一项大概长什么样"的位置与字号；
 * - **真正的字体族只在"确认"（双击 / 回车 / 点确认按钮）时才交给外部**。
 *
 * 用户被明确告知这一延迟 —— 见 [previewHint] 的提示文本。这是大纲要求的
 * "必须在验收时明确告知用户"落地方式：不是写在文档里，而是写在界面上。
 *
 * ## 为什么不用 `Font.createFont` 加载字体文件
 *
 * 本面板只做"从系统已装字体里挑一个族名"，不做字体文件导入。
 * 理由：字体文件导入会引入"文件生命周期"（用户删了源文件怎么办）、
 * "许可证"（把商业字体嵌进配置）两类新问题，而首版注册表里
 * `main_lyric.font.family` 就是一个族名字符串，不涉及文件。
 *
 * ## 零宿主 API 依赖
 *
 * 与 [ColorPicker] 同：只 import `java.awt` / `javax.swing` / `settings` / `font` 包。
 * ⚠️ 2026-09-22：`font` 包那条依赖此前是 `BundledFonts`（接随包字体用），
 * 随包字体取消后改为 `DEFAULT_FONT_FAMILY` 一个常量，依赖面**变小**了。
 */
internal class FontPicker(
    /** 打开时的族名。取消时回退到它。 */
    private var originalFamily: String,
    /** 预览用的字号（取自 `main_lyric.font.size`，让预览与真实观感一致）。 */
    private val previewSize: Int,
    /**
     * 确认时回调（**落盘**）。参数是族名。
     * 只有双击 / 回车 / 点"确认"才触发，滚动选择不触发。
     */
    private val onCommit: (String) -> Unit,
) {

    /** 当前选中（尚未确认）的族名。 */
    var value: String = originalFamily
        private set

    val root: JPanel = JPanel(BorderLayout(0, 6)).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(6, 0, 4, 0)
        add(Box.createVerticalStrut(2), BorderLayout.NORTH)
    }

    // ── 控件 ────────────────────────────────────────────────────────────

    private val allFamilies: List<String> = loadFamilies()

    private val listModel = DefaultComboBoxModel<String>().apply {
        // 排序规则：**把注册表默认值提到第一位**，方便用户一键回到默认。
        // 之后按系统默认顺序（`availableFontFamilyNames` 本身已是本地化排序），
        // 不额外做字典序重排 —— 中文字体族名混排时字典序毫无意义。
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
        // `JList.getSelectedValue()` 在 Kotlin 里被映射成只读属性 `selectedValue`，
        // 写入必须走显式的 `setSelectedValue(...)`。
        setSelectedValue(originalFamily, true)
        // 字体列表可能很长（系统装了 300+ 字体很常见）。
        // 不设固定 preferredSize 会被 JList 撑到几百像素高，
        // 把设置窗口的滚动条变成一个"要滚很久"的东西。
        //
        // ★ 2026-10-18：**统一行高**。此前每行高度由 `cellRenderer` 的字体决定，
        //   而列表项各用各的字体（MiSans / Inter / 回退字体）⇒ 行高参差 ⇒
        //   用户看到的是「字体大小不统一」（截图里 MiSans 那行明显更大）。
        //   `fixedCellHeight` 让"行距"与字体无关，字面大小仍由渲染器控制。
        fixedCellHeight = 22
        // ★★★ 2026-10-18：**绝对不要给 JList 设 preferredSize** ——
        //   `JScrollPane` 用它和一屏高度比较来决定「要不要出滚动条」；
        //   设成恒定值会让它永远认为内容放得下 ⇒ 不出滚动条、内容被裁、滚轮无效
        //   （这正是用户两次报「滚动不了」的根因）。视口尺寸交给 JScrollPane。
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
                // 用一行**中英混排**做样例：中文验字形覆盖面（很多拉丁字体没有汉字，
                // 会被 Java 静默回退到默认中文字体 —— 用户若只看 "Sample" 是看不出来的），
                // 英文验字重与字距观感。这一行是"预览"这个功能的全部价值所在。
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

    /** "当前选择："右侧的族名标签。**必须在 `init` 之前声明** —— `init` 块会把它加进面板。 */
    private val selectionLabel = JLabel(originalFamily).apply {
        foreground = ToolWindowPalette.fg
    }

    private val confirmButton = JButtonFactory.primary("确认使用该字体")

    // ── 状态 ────────────────────────────────────────────────────────────

    /**
     * 预览区当前使用的字体。
     *
     * 初值是"回退字体"—— 即用一个**必定存在**的字体渲染，
     * 避免一打开面板就因为字体不存在而渲染成默认宋体（视觉突跳）。
     */
    private var previewFont: Font = initialPreviewFont()

    private var previewFamily: String = originalFamily

    /** 最近一次确认过的族名，`value == lastCommitted` 时不需要产生提交。 */
    private var lastCommitted: String = originalFamily

    init {
        root.add(listScroll(), BorderLayout.CENTER)
        root.add(
            // ★★★★ 2026-10-18（**BUG-228**）：底部面板从 `BoxLayout` 改成 **`BorderLayout`**。
            //
            // ## 用户报障（逐字 + 截图）
            //
            // > 「字体包导入界面出现问题，**异常缩小**，按钮异色。」
            // 截图里：「确认使用该字体」按钮**只剩一条细横条**（文字被裁掉一半）。
            //
            // ## 根因：`BoxLayout` 按 **preferredSize 高度**分配，而 `previewHint` 是
            //    **HTML 长文本** —— 它的 `preferredSize` 按**不换行**算（1 行高），
            //    但实际绘制时被容器宽度压成 **2 行** ⇒ **实际占位比分配多 1 行** ⇒
            //    BoxLayout 的总高度不够 ⇒ 排在最下面的按钮行被**裁掉** ✗
            //
            // ## 修法：按钮行放 `SOUTH`（永远拿到自己的 preferredSize），
            //    提示放 `CENTER`（可压缩、只裁它自己）；并给提示**显式 2 行高度**，
            //    让父容器的 preferredSize 也把换行算进去 ✓
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(previewCanvas, BorderLayout.NORTH)
                add(
                    previewHint.apply {
                        // 2 行（提示文本在 660px 宽下正好折成两行）
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

    // ── 对外 API ────────────────────────────────────────────────────────

    /** 外部改了同一个键时同步进来。 */
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

    /** 面板折叠/展开。折叠时若选择未确认，**不自动提交**（延迟纪律见类注释）。 */
    fun setExpanded(expanded: Boolean) {
        root.isVisible = expanded
        root.revalidate()
        root.repaint()
    }

    // ── 行为 ────────────────────────────────────────────────────────────

    /**
     * 选中变化：只更新"当前选择"标签与一个**低成本**的预览，
     * 绝不在这里调 `Font.createFont`（那就是 DEFERRED 要避免的事）。
     */
    private fun onSelectionChanged() {
        val family = familyList.selectedValue ?: return
        value = family
        updateSelectionLabel()
        updatePreviewImmediate()
    }

    /**
     * "实际字体：" 那一行的文字。
     *
     * ## 判据：**解析回来的族名必须等于请求的族名**
     *
     * 判据是 [isFamilyAvailable] —— 即"`Font(family, …)` 解析回来的族名是否还是它自己"。
     * 不等 ⇒ 本机没这款（AWT 静默退到了 `Dialog`），如实告诉用户回退到了哪一款。
     *
     * ## 2026-09-22：这条判据**变回简单了**，此前一度需要绕过随包字体
     *
     * 2026-09-19 接上随包字体后，单纯的族名比对成了**假阴性**：包内那份 MiSans
     * 没有注册进 `GraphicsEnvironment`，`Font("MiSans", …)` 取不到它，于是界面会显示
     * 「**MiSans（系统回退为 Dialog）**」—— **明确说它不可用，而实际渲染好好的**。
     * 当时靠 `BundledFonts.isBundled(family)` 短路来绕过这个假阴性。
     *
     * 随包字体取消后，**这个假阴性的前提不存在了** —— 所有可用族名都来自
     * 本机 `GraphicsEnvironment`，族名比对重新成为正确判据（见 [isFamilyAvailable]）。
     */
    // ★★ 2026-09-29：函数体**抽到了顶层** [previewFontLabel] —— 因为
    //    `FontSourceDialog` 也要显示同一行说明文字，两处必须**同源**
    //    （见该函数的 KDoc：哨兵为何不能走 [isFamilyAvailable]）。
    //    这里保留一层薄封装，调用点不必知道 [previewFamily] / [previewFont] 这两个字段名。
    private fun previewFamilyLabel(): String = previewFontLabel(previewFamily, previewFont)

    /**
     * 立即重建预览字体。
     *
     * ## 2026-09-22：从 `BundledFonts.resolve` 回到 `Font(...)`
     *
     * 这里原先走 `BundledFonts.resolve(value, …)`，理由是"不接随包字体的话，
     * 用户机器没装 MiSans 时预览画 `Dialog` 回退字形、而实际渲染画包内 MiSans
     * —— 预览与渲染不一致"。**随包字体已取消**，渲染层也回到了 `Font(...)`
     * （见 `render/LetterSpacing.kt` 的 `fontWithLetterSpacing`），
     * 故预览与渲染**重新走同一条路**，一致性由"同一个构造方式"保证，不需要再经过解析器。
     *
     * 代价是**微秒级**：`Font(family, style, size)` 只是一次族表查找 + 逻辑字体构造，
     * 不打开任何字体文件（原先 `resolve` 首次会读 7.7 MB 的 TTF，那才是慢的那个）。
     */
    private fun updatePreviewImmediate() {
        previewFamily = value
        // ★★ 2026-09-29 接线宿主内置字体（`recon/66` §0.3 的构造点 ③）。
        //    让"宿主内置 MiSans"这一条**用宿主字体画自己** —— 否则列表里那一条
        //    显示成回退字形（`Font("@host:MiSans", …)` 找不到该族）。
        // ★★★ 2026-10-07 **BUG-161**：改用**唯一漏斗**。
        //   ★ 上面那句 KDoc 的目标是「用宿主字体画自己 —— **否则…找不到该族**」，
        //     而原来的 `?: Font(value.ifBlank { FALLBACK_FAMILY }, …)` 在
        //     `resolveOrNull` 返回 `null` 时**正好做了它警告的那件事**
        //     （哨兵非空白 ⇒ `Font("@host:MiSans", …)` ⇒ 退化成 `Dialog`）。
        previewFont = dev.cdl.font.resolveFontOrFallback(value, previewSize)
        previewCanvas.repaint()
    }

    /** 确认：这一刻才把族名交出去（落盘）。 */
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
        // 双击 = 确认（规格 §5 的"松手/确认才生效"在本控件上的对应物）。
        familyList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) commit()
            }
        })
        // 回车 = 确认；Esc = 回退到打开时的字体。
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
                    // Esc 的语义是"放弃未确认的改动"，放弃 = 回到上次落盘的值。
                    // 由于从未提交过未确认的改动，这里**不需要**再调 onCommit ——
                    // 磁盘上本来就是 `originalFamily`。
                }
            },
        )
        confirmButton.addActionListener { commit() }
    }

    private fun updateSelectionLabel() {
        selectionLabel.text = value.ifBlank { "（空）" }
    }

    /**
     * 把列表包进 `JScrollPane`，并把滚动单位的默认值调小。
     *
     * 为什么调小：Swing 的默认滚动单位是"一屏"（约 10 行），
     * 在字体列表这种"要一项一项看"的场景里，滚一格跳 10 个字体太快了。
     * 改成 3 行是手感问题，不是功能问题，但用户会明显感觉到。
     */
    private fun listScroll(): JComponent {
        val scroll = javax.swing.JScrollPane(familyList).apply {
            // ★ 视口尺寸在这里给（而不是给 JList）：JList 的 preferredSize 必须
            //   保持「随内容增长」，否则 JScrollPane 不会出滚动条。
            preferredSize = Dimension(300, 220)
            verticalScrollBar.unitIncrement = 48
            verticalScrollBar.blockIncrement = 220
            horizontalScrollBar.unitIncrement = 16
            // ★★ 2026-10-18：滚动**显式声明**（用户报「字体列表不可滚动」）。
            //   两条缺一不可：**策略** 与 **滚动条宽度** —— 与 `FontSourceDialog` 同一套写法。
            //   `ThemeScrollBarUI` 把两端箭头压成 0 尺寸，若宽度也交给 LAF 推导，
            //   某些 LAF 下会得出 0 宽 ⇒ 滚动条「存在但看不见、拖不动」。
            //   （滚轮不需要手动转发：`BasicScrollPaneUI` 会把 handler 装在 JScrollPane 上。）
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

    /** 预览用的"保底字体"，保证面板在极早期就能画出合法内容。 */
    private fun initialPreviewFont(): Font =
        Font(FALLBACK_FAMILY, Font.PLAIN, previewSize)

    /**
     * 列表项渲染器：**每一项用自己那款字体画自己的名字**。
     *
     * 这个渲染器的代价是可控的：`JList` 只渲染可视区域内的项（约 10 行），
     * 每次滚动重建 10 个 `Font` 逻辑字体，实测在 Swing 里是微秒级。
     * 收益是用户**不需要点每一项**就能大致看出特征 —— 这正是"字体选择器"
     * 相比"下拉框"存在的意义。若把 `JComboBox` 换成 `JList` 而不做这一步，
     * 就只是换了个更长的下拉框，白付了空间成本。
     */
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
                // ★ 显示名：哨兵值 `@host:MiSans` → 「宿主内置 MiSans」（`recon/66` §3.2）。
                //    ⚠️ **只改显示，不改值** —— 落盘的必须仍是哨兵（`commit()` 交出去的是 `value`）。
                label.text = HostFonts.displayName(family)

                // ★★ 构造点 ③（`recon/66` §0.3）—— **候选列表项用宿主字体画自己**。
                //    ⚠️ 这里才是规格里说的"候选列表项的样例字形"；
                //      `updatePreviewImmediate()` 那个是**大预览画布**，两者**都要改**。
                //      只改一处 ⇒ 列表里那一条显示成回退字形（用户以为选不到）。
                val hostFont = HostFonts.resolveOrNull(family)
                // ★★★★★ 2026-10-18（**BUG-228**）：**必须 `.toFloat()`** ——
                //   否则 Kotlin 选中 `Font.deriveFont(int style)` 重载，
                //   把 `14` 当成**样式位掩码**，而**字号保持宿主字体的原值**。
                //   `Font.createFont(...)` 得到的字体默认 size = **1.0** ⇒
                //   所有「宿主字体」项被画成 **1px 字**、行高 ~3px ✗
                //   （用户截图：MiSans 之后一整片"极小的字体名"）
                //
                // ★ 判据：**`deriveFont` 有 `(int style)` 与 `(float size)` 两个重载，
                //   传 `Int` 时 Kotlin 选前者** —— 想要字号必须显式 `toFloat()`。
                //   `FontSourceDialog` 那边早就写了 `.toFloat()`，本文件漏了。
                val resolved = hostFont?.deriveFont(LIST_ITEM_SIZE.toFloat())
                    ?: Font(family, Font.PLAIN, LIST_ITEM_SIZE)
                // 判据见 [isFamilyAvailable] —— 本机没有这款时用保底字体画，
                // 让"这一项渲染不出区别"可见。
                // ⚠️ **哨兵值不走可用性判据**：`resolveOrNull` 已证明拿到了真字体，
                //    而 `Font("@host:MiSans", …)` 必然解析不到该族 ⇒ 走判据会**误判为不可用**。
                val candidate = if (hostFont != null || isFamilyAvailable(family, resolved)) {
                    resolved
                } else {
                    // ★ 2026-10-18：回退字体也必须按**列表字号**派生。
                    //   `fallback` 是预览字号（跟着 `main_lyric.font.size`，常见 30+），
                    //   直接用它会让"回退项"比别的项大一圈 —— 用户报「字体大小不统一」
                    //   的另一半原因（探针 ⑦ 实测：回退项 32px vs 其它项 14px）。
                    fallback.deriveFont(LIST_ITEM_SIZE.toFloat())
                }
                // ★★★ 2026-10-18：候选字体必须能显示整段文字，否则回退保底字体。
                //   现场：宿主族 `@host:Inter` 的显示名含「宿主内置」四个汉字，
                //   而 Inter 无中文字形 ⇒ 被画成方框（用户报「字体名显示乱码」）。
                //   判据 = [java.awt.Font.canDisplayUpTo]（-1 = 全部可显示）。
                label.font = dev.cdl.font.fontForLabelText(
                    candidate = candidate,
                    text = label.text,
                    // ★★ 2026-10-18：`fallback` 是**预览字号**（跟着 `main_lyric.font.size`，
                    //   常见 30+），直接拿来画列表项会让"回退项"比别的项大一圈 ——
                    //   这正是用户看到的「字体大小不统一」的另一半原因。
                    //   列表项一律按 `LIST_ITEM_SIZE` 派生。
                    fallback = fallback.deriveFont(LIST_ITEM_SIZE.toFloat()),
                )
            }
            return label
        }
    }

    private companion object {
        /**
         * 预览保底字体 = **出厂默认字体族**（见 [DEFAULT_FONT_FAMILY]）。
         *
         * 它必须是 Windows 10/11 上**一定存在**的简体中文字体，理由见
         * [DEFAULT_FONT_FAMILY] 的 KDoc（默认值指向本机没有的族名会**静默退化**成 `Dialog`）。
         * 即便在极端环境（精简版系统）里缺失，`Font(...)` 也只会回退到平台默认，
         * 不会抛异常 —— 故这里不需要 `runCatching`。
         *
         * ⚠️ 2026-09-22 之前，这里与默认值**故意不是同一个字面量**：
         * 那时默认值是随包的 `MiSans`，而 `MiSans` 在没装它的机器上**不存在**，
         * 拿它当"保底"等于没有保底。随包字体取消后默认值本身就是本机字体，
         * 故两者合一 —— 少一个字面量，也就少一处可能漂移的地方。
         */
        const val FALLBACK_FAMILY = DEFAULT_FONT_FAMILY

        /** 列表项字号。比预览区小，目的是让一屏能多显示几项。 */
        const val LIST_ITEM_SIZE = 14
    }

    /** 只读：列出系统字体族的原始结果（供报告与排障）。 */
    fun availableFamilyCount(): Int = allFamilies.size

    private fun loadFamilies(): List<String> = runCatching {
        // ★★ 2026-09-29：宿主内置字体**排在最前**（`recon/66` §3.2）。
        //    为什么排最前：它是本插件新增的、也是与宿主 UI 最一致的一条 ⇒ 用户最该先看到它。
        //    ⚠️ 哨兵值（`@host:MiSans`）**不在** `availableFontFamilyNames` 里 ——
        //      它刻意不写系统族表（见 `HostFonts` 文件头 §一/§二）⇒ 必须在这里显式并入。
        HostFonts.SENTINELS +
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toList()
                .filter { it.isNotBlank() }
    }.getOrElse {
        // 无头环境 / 字体子系统异常时的降级：至少让面板可用（列表里只有当前值）。
        System.err.println("[CDL] 读取系统字体族失败，字体选择器进入降级形态：$it")
        listOf(FALLBACK_FAMILY)
    }
}

/**
 * `FontPicker` 在设置窗口里的一行式构造入口。
 *
 * @param previewSize 预览字号，取自 `main_lyric.font.size` 的当前值。
 *   为什么把字号传进来：用户改字体时最关心的是"在这个字号下好不好看"，
 *   若预览固定用 14pt，选出来的字体在 32pt 正文里可能完全不是那个观感。
 */
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

/** 设置窗口用的按钮小工厂：避免每个控件里重复写同一套外观设置。 */
internal object JButtonFactory {
    /**
     * "主按钮"：视觉上更醒目（加粗）。
     *
     * 为什么要在代码里做这件事而不是用 LAF 的默认外观：Swing 跨 LAF 没有
     * `defaultButton` 之外的强调手段，而本项目的设置窗口刻意用系统 LAF
     * （见 `SettingsWindow` 的注释），所以强调只能靠字重。
     */
    // ★★ 2026-10-18：**必须用 `ThemeButton`**（自绘）——
    //   裸 `JButton` 在 Windows LAF 下由 LAF 整块绘制，
    //   `applyToolWindowTheme` 设的 background/foreground **被忽略** ⇒
    //   按钮是系统默认浅色，与同窗其它控件不同色（用户报「按钮不随主题」）。
    //   本项目其它工具窗（取色器 / 预设管理 / 字体来源）已全部改用 ThemeButton，
    //   只剩这一个工厂还在产裸按钮。
    fun primary(text: String) = ThemeButton(text).apply {
        font = font.deriveFont(Font.BOLD)
    }
}

/** 让设置窗口在 EDT 上安全地跑一段可能抛异常的 UI 构造。 */
internal inline fun onEdtSafely(what: String, crossinline block: () -> Unit) {
    SwingUtilities.invokeLater {
        runCatching { block() }.onFailure {
            System.err.println("[CDL] $what 失败：$it")
        }
    }
}

/**
 * 族名是否**可用** —— 即"画出来真的是它"，而不是被 AWT 静默回退成别的字体。
 *
 * ## 判据：`Font(family, …)` 解析回来的族名是否还是它自己
 *
 * 不等 ⇒ 本机没这款，AWT 退到了 `Dialog`（这是 `Font` 构造器的既定行为，不报错）。
 *
 * ## 2026-09-22：判据**回到**了最朴素的形式 —— 这是一次"还原"，不是新写法
 *
 * 2026-09-19 接上随包字体后，这个判据一度**必须**写成
 * `BundledFonts.isBundled(family) || 族名比对` —— 因为包内那份 MiSans
 * 没有注册进 `GraphicsEnvironment`，族名比对必然不等，于是"字体包"来源里的每一项
 * 都会被误画成回退字体（**假阴性**）。
 *
 * 随包字体已取消（用户 2026-09-22 拍板），**所有可用族名都来自本机
 * `GraphicsEnvironment`** —— 假阴性的前提不存在了，族名比对重新是正确且充分的判据。
 *
 * ⚠️ 若日后**再次**引入随包字体，必须**同时**把这里的判据连同
 * `render/LetterSpacing.fontWithLetterSpacing` 的构造方式一起改回去 ——
 * 只改渲染层不改这里，就是当年 BUG-102 的形状（界面说不可用、渲染却正常）。
 *
 * @param family 请求的族名（设置项里的值）。
 * @param resolved 该族名**已经**被 `Font(family, style, size)` 解析过一轮的结果。
 */
internal fun isFamilyAvailable(family: String, resolved: Font): Boolean =
    resolved.family.equals(family, ignoreCase = true)

/**
 * 预览区「实际字体：…」那一行的文字 —— **两个字体入口的唯一出口**（2026-09-29）。
 *
 * ## 为什么必须抽成唯一出口（而不是各写一份）
 *
 * 有两个地方要显示"这次预览到底用了哪款字"：
 * 设置窗口的内联选择器 [FontPicker] 与三来源对话框 `FontSourceDialog`。
 * 它们**必须给出同一个答案** —— 否则用户在两个入口看到"同一款字体"却写着不同的
 * 说明文字，会以为哪里坏了。本项目在"同族两处实现只改一处"上已吃过多次亏
 * （纪律 11），故这里合并成一个函数。
 *
 * ## 哨兵值为什么**不能**走 [isFamilyAvailable]
 *
 * `isFamilyAvailable` 的判据是「用 `Font(family, …)` 解析回来的族名是否还是它自己」。
 * 而 `Font("@host:MiSans", …)` **必然解析不到该族**（哨兵刻意不写系统族表，
 * 见 `HostFonts` 文件头 §一/§二）⇒ 哨兵会被**误判为不可用**，显示成
 * 「`@host:MiSans`（系统回退为 MiSans）」—— **两处都错**：
 * ① 摊出了原始哨兵（应显示「宿主内置 MiSans」）；
 * ② 说"系统回退"是**误导** —— 实际用的**就是宿主那一份**，根本没有回退。
 *
 * ⇒ 哨兵的正确可用性判据是 `HostFonts.resolveOrNull` 能否拿到。
 *
 * @param family 请求的族名（设置项里的值，可能是哨兵）。
 * @param resolved 该族名**已经**被解析过一轮的结果（哨兵情形下是回退字体，
 *   本函数不会用它来判断哨兵 —— 见上）。
 * @return 给人看的说明文字（**绝不含**原始 `@host:` 字样）。
 */
// ⚠️ 2026-09-29：**必须 public，不能改成 internal** —— 与 [previewTargetMatches]
//    同一个理由（那条教训写在 `PreviewSnapshot.kt` 的 `previewTargetMatches` 上方）：
//    探针（`probe/FontProbe.kt` §7.7/§7.8）是**独立编译单元**，看不到 `internal` 顶层函数。
//    改成 `internal` 的后果不是"少两条断言"，而是**整个 `FontProbe` 编译失败 →
//    全量门禁连坐**（`run_all_probes.sh` 里排在前面的探针挂掉会带走后面的）。
//    → 需要"不对外"的**意图标注**就用 KDoc 说明，不要用编译器级可见性收窄。
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
