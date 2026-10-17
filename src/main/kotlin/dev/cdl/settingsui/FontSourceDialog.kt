// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.font.DEFAULT_FONT_FAMILY
import dev.cdl.font.FontCandidate
import dev.cdl.font.FontCatalog
import dev.cdl.font.FontHistory
import dev.cdl.font.HostFonts
import dev.cdl.settings.SettingRegistry
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * **字体三来源选择对话框** —— 用户 2026-09-16 诉求的落地形态。
 *
 * > 「SPW 插件配置字体选择那里使用近期使用字体列表，本机完全字体列表加从电脑中选择字体包的形式。」
 *
 * ## 为什么是"模态对话框"而不是并入设置窗口的内联面板
 *
 * 设置窗口里的 [FontPicker] 是**内联**的（常驻占据一块纵向空间）。
 * 三来源形态**不能**沿用这个形态，原因是**空间**：
 *
 * - 近期（≤10）+ 本机（本机实测 250+ 族）+ 字体包（每次导入若干）——
 *   一个内联面板要么把设置窗口撑到几千像素高，要么让用户在一个
 *   10 行高的列表里翻 250 项。
 * - 而 [FontPicker] 之所以内联，是因为它只需要"从 250 项里挑一个"这一件事，
 *   一屏 10 行 + 滚动够用。加入"导入文件"这个**动作**后，
 *   面板要同时承载"浏览"与"动作"两件事，内联就不够了。
 *
 * 模态对话框还带来一个**实质好处**：它可以拿 `owner` 做居中定位，
 * 而 `preference_config.json` 的 `button` 入口（`on_click` 反射、**拿不到父窗口**）
 * 只能 `owner = null` —— 两种入口共用本类，由调用方决定传不传 owner。
 *
 * ## 与 [FontPicker] 的关系：**不替换，并存**
 *
 * | 场景 | 用哪个 | 理由 |
 * |---|---|---|
 * | 设置窗口的内联字体行 | [FontPicker] | 只挑不导入，内联省一次弹窗 |
 * | `preference_config.json` 的字体按钮 | **[FontSourceDialog]**（本类） | 三来源全功能 |
 *
 * ## 许可证红线
 *
 * 「从电脑中选择字体包」**只读取**用户显式选中的文件，**不复制、不打包**任何字体。
 * 插件包内不放字体（见 `FontLoader` 的类 KDoc：MiSans 免费商用但**不可再分发**）。
 *
 * ## 线程
 *
 * [show] 必须在 **EDT** 上调用（它构造 Swing 组件）。
 * 内部的**字体加载在后台线程**做 —— 解析字体包是毫秒到百毫秒级，
 * 在 EDT 上做会让对话框卡住（见 [FontLoader] 的线程声明）。
 */
internal class FontSourceDialog private constructor(
    /** 打开时的族名。取消时回退到它。 */
    private var originalFamily: String,
    /** 预览字号（取自 `main_lyric.font.size`）。 */
    private val previewSize: Int,
    /** 近期历史原始串（设置项 `main_lyric.font.recent`）。 */
    private val historyRaw: String,
    /**
     * 确认时回调（**落盘**）。参数是（族名, 新的历史原始串）。
     * 历史串一并交出去，是为了让"用过的字体进历史"这条规则**只有一处实现**
     * （[FontHistory.record]），而不是调用方各处自行 record。
     */
    private val onCommit: (family: String, newHistoryRaw: String) -> Unit,
) {

    /** 当前选中的族名。 */
    var value: String = originalFamily
        private set

    private val allCandidates = ArrayList<FontCandidate>()
    private val listModel = DefaultListModel<String>()
    private val familyList = JList(listModel)

    private val searchField = JTextField(20)
    private val statusLabel = JLabel(" ")
    private val countLabel = JLabel(" ")

    /** 用户本次导入的字体包文件（累积）。用于"来源 = 字体包"的候选是否还活着。 */
    private val importedCandidates = ArrayList<FontCandidate>()

    private val root = JPanel(BorderLayout(0, 8)).apply {
        isOpaque = true
        background = ToolWindowPalette.bg
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
    }

    init {
        root.add(buildTop(), BorderLayout.NORTH)
        root.add(buildCenter(), BorderLayout.CENTER)
        root.add(buildBottom(), BorderLayout.SOUTH)
        reloadCandidates()
        installSearchWiring()
    }

    // ── 布局 ────────────────────────────────────────────────────────────

    private fun buildTop(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(
            JLabel(
                "<html><b>选择歌词字体</b>　" +
                    "<span style='color:#666'>三个来源：近期使用 · 本机完全字体表 · 从电脑中选择字体包</span></html>"
            )
        )
        add(Box.createVerticalStrut(6))
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JLabel("搜索："))
                searchField.toolTipText = "输入族名的一部分即可过滤。中文与英文都支持。"
                add(searchField)
                add(countLabel.apply { foreground = ToolWindowPalette.muted })
            }
        )
        add(Box.createVerticalStrut(4))
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                // ★★★ 2026-10-17（BUG-227）：**必须用 ThemeButton**（自绘）——
                //   裸 `JButton` 在 Windows LAF 下由 LAF 整块绘制，
                //   `applyToolWindowTheme` 设的 background/foreground **被忽略**
                //   ⇒ 按钮是系统默认色，与同窗其它控件**不同色**（用户报「按钮异色」）✗
                //   ★ 同项目其它对话框（ColorPicker / PresetManager）全部用 ThemeButton，
                //     只有本窗漏了 —— 这是"同一形态在两处实现"的又一例。
                val importButton = ThemeButton("从电脑中选择字体包…").apply {
                    toolTipText = "支持 .ttf / .otf / .ttc / .otc。" +
                        "只读取你选中的文件，不复制、不打包进插件。"
                    addActionListener { pickFontFiles() }
                }
                add(importButton)
                add(
                    // ★ 2026-10-01 更正：原写「**下次启动若文件仍在，会随条目再次加载**」——
                    //   **与实现相反**：`FontHistory` **只持久化族名**（其 serialize 仅存 family），
                    //   **文件路径不落盘**；而 `FontCatalog.fromFiles` 的唯一调用点就是
                    //   **用户手选文件**这条路径（本文件下方 `pickFontFiles()`）
                    //   ⇒ **下次启动不会重载**。
                    //   ★ 旁证：`FontCatalog` 的 KDoc 逐字写着「本来源的族名只在本次会话内有效…
                    //     但**下次启动就找不到了**…界面上必须提示」。
                    JLabel(
                        "<html><span style='color:#B45309'>导入的字体仅在本次会话内注册；" +
                            "下次启动不会自动重载（记录里只保留字体名，不保留文件路径），" +
                            "需要时请重新导入。</span></html>"
                    )
                )
            }
        )
    }

    private fun buildCenter(): JPanel = JPanel(BorderLayout(0, 4)).apply {
        isOpaque = false
        familyList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
            cellRenderer = SourceCellRenderer(previewSize)
            // ★★★ 2026-10-18：同 FontPicker —— **不要给 JList 设 preferredSize**，
            //   否则 JScrollPane 认为内容刚好放得下，永远不出滚动条。
            //   视口尺寸在下面的 JScrollPane 上给。
            setSelectedValue(originalFamily, true)
            addListSelectionListener { event ->
                if (event.valueIsAdjusting) return@addListSelectionListener
                val picked = resolveSelection(event.source as JList<*>)
                if (picked != null) {
                    value = picked.family
                    updateStatus()
                }
            }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(event: java.awt.event.MouseEvent) {
                    if (event.clickCount >= 2) confirmAndClose()
                }
            })
        }
        // ★★ 2026-10-18：滚动**显式声明**，不依赖 LAF 默认（用户报「无法上下滚动」）。
        //
        // 三个都要给，少一个都可能复现：
        // ① 策略 —— `AS_NEEDED` 是默认值，这里写明，避免将来有人改成 `NEVER` 而无感；
        // ② 滚动条宽度 —— `ThemeScrollBarUI` 把两端箭头压成 0 尺寸，若宽度也交给
        //    LAF 推导，某些 LAF 下会得出 0 宽 ⇒ 滚动条"存在、但看不见也拖不动"；
        // ③ 滚轮兜底 —— 某些焦点状态下 `JList` 不把滚轮事件转给 viewport，
        //    显式转发一次，保证鼠标停在列表上就能滚。
        val scroll = JScrollPane(familyList).apply {
            // ★ 视口尺寸给 JScrollPane，JList 的 preferredSize 保持「随内容增长」。
            preferredSize = Dimension(460, 240)
            verticalScrollBar.unitIncrement = 48
            verticalScrollBar.blockIncrement = 240
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.preferredSize = Dimension(10, 0)
            border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ToolWindowPalette.line),
                "字体族（按来源分组：近期 → 字体包 → 本机）",
                TitledBorder.LEADING,
                TitledBorder.TOP,
            )
        }
        add(scroll, BorderLayout.CENTER)
        add(statusLabel.apply { foreground = ToolWindowPalette.fg }, BorderLayout.SOUTH)
    }

    private fun buildBottom(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        val preview = object : JPanel() {
            init {
                isOpaque = true
                background = ToolWindowPalette.fieldBg
                preferredSize = Dimension(460, 62)
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(ToolWindowPalette.line),
                    "预览",
                    TitledBorder.LEADING,
                    TitledBorder.TOP,
                )
            }

            override fun paintComponent(g: java.awt.Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                    )
                    // ★★ 2026-09-29：宿主哨兵要走 `HostFonts` —— 否则 `Font("@host:MiSans", …)`
                    //    找不到该族、静默退化成 `Dialog`，预览画出来的**不是**用户选的那一款。
                    //    与 `FontPicker.updatePreviewImmediate` 是**同一个 `?:` 形状**
                    //    （两个入口的预览必须同源，否则用户会以为"两处选同一字体却长得不一样"）。
                    //    ⚠️ `resolveOrNull` 对**非哨兵**返回 null ⇒ 走原路 ⇒ 既有行为零改变。
                    // ★★★ 2026-10-07 **BUG-161**：改用**唯一漏斗**（哨兵非空白 ⇒ 原来会退化成 `Dialog`）。
                    val shown = dev.cdl.font.resolveFontOrFallback(value, previewSize)
                    g2.font = shown
                    g2.color = ToolWindowPalette.fg
                    val metrics = g2.fontMetrics
                    // 中英混排：中文验字形覆盖（很多拉丁字体没有汉字，Java 会静默回退），
                    // 英文验字距观感。只看 "Sample" 是看不出缺字的。
                    g2.drawString("CDL for SPW 0123", 8, 18 + metrics.ascent)
                    g2.font = shown.deriveFont(Math.max(9f, previewSize - 6f))
                    g2.color = ToolWindowPalette.muted
                    // ★★ 2026-09-29：说明文字改走**唯一出口** `previewFontLabel`
                    //    —— 与 `FontPicker` 同源。此前这里自己写了一份 `isFamilyAvailable`
                    //    判据，对哨兵会误报成「系统回退为 Dialog」（两处都错：既摊出了
                    //    原始哨兵，又把"正常解析"说成"回退"）。
                    g2.drawString(
                        "实际字体：${previewFontLabel(value, shown)}",
                        8, 18 + metrics.ascent + metrics.descent + 14,
                    )
                } finally {
                    g2.dispose()
                }
            }
        }
        add(preview, BorderLayout.NORTH)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
                isOpaque = false
                add(JLabel("当前选择：").apply { foreground = ToolWindowPalette.muted })
                add(JLabel().apply {
                    // 标签文字随选择变化 —— 由 [updateStatus] 经 [selectionLabel] 写入。
                    // ★ 2026-09-29：此前本标签**从没人写过文字**（见 [updateStatus] 的说明）。
                    name = "selectionProbe"
                    foreground = ToolWindowPalette.fg
                    font = font.deriveFont(Font.BOLD)
                }.also { selectionLabel = it })
                add(ThemeButton("确定").apply { addActionListener { confirmAndClose() } })
                add(ThemeButton("取消").apply { addActionListener { cancelled = true; closeDialog() } })
            },
            BorderLayout.SOUTH,
        )
        // 预览区需要随选择重绘：把 repaint 挂在 statusLabel 的更新路径上不够可靠
        // （JList 的选中会先改 value 再触发 listener）。改用 document 无关的显式钩子：
        // 这里把 preview 存进字段，由 updateStatus 统一 repaint。
        previewPanel = preview
    }

    private var previewPanel: JPanel? = null

    /**
     * 「当前选择：」右侧那个标签 —— 文字由 [updateStatus] 写入。
     *
     * ⚠️ 声明为**可空** —— 这是**防御性**的，理由如下（★ 已核过装配顺序，不是想当然）：
     *
     * 装配顺序是 `buildTop()` → `buildCenter()` → **`buildBottom()`** → `reloadCandidates()`
     * （`:112–115`），而本标签在 [buildBottom] 里创建 ⇒ **正常路径下**
     * `reloadCandidates → applyFilter → updateStatus` 跑时本字段**已经**有值。
     *
     * 但 [buildCenter] 里的 `JList` 装了一个**选中 listener**（它调 [updateStatus]），
     * 而 `buildCenter()` 在 `buildBottom()` **之前**执行 ⇒ 只要那次回调发生在
     * **装配期**（`setSelectedIndex` / `clearSelection` 都会触发），本字段就是 `null`。
     * ★ 纪律 13（**构造期回调 + 晚初始化字段 = 必然 NPE**）在本类**真实成立**
     *   （`ColorPickerDialog` 正是栽在这条上）⇒ 写入点必须用 `?.`（防御成本为零）。
     */
    private var selectionLabel: JLabel? = null

    private var cancelled = false

    private var dialog: javax.swing.JDialog? = null

    // ── 候选加载 ────────────────────────────────────────────────────────

    /**
     * 重新组装候选列表：**近期 → 字体包 → 本机**，然后按族名去重。
     *
     * ## 顺序为什么是这样（这是本功能的用户可见语义，不是实现细节）
     *
     * 1. **近期置顶**：用户最可能"回到上次那个"。
     * 2. **字体包其次**：用户刚在本次会话里导入的，正在找它。
     * 3. **本机全量最后**：兜底，最全但最长。
     *
     * 去重**保留首次出现**（见 [FontCatalog.dedupe]）—— 所以一个"既在近期、
     * 又刚导入、也装在本机"的族名，会以**近期**的身份出现（来源标签 = 近期）。
     * 这是有意的：用户看到自己用过它，比看到"它装在本机"更有信息量。
     *
     * ## ★★ 候选顺序：`近期 → 刚导入 → 宿主内置 → 本机全量`（2026-09-29）
     *
     * 既有契约是"**近期优先、其次刚导入、最后本机全量**"（见 [FontCatalog.dedupe] 的
     * KDoc 末段）。本轮把"本机全量"**拆成两段**，宿主插在系统之前：
     *
     * | 段 | 为什么在这个位置 |
     * |---|---|
     * | 近期 / 刚导入 | 用户**主动**选过的 —— 最该在顶部（既有契约，未动） |
     * | ★ **宿主内置** | 出厂默认值就在这一段（`main_lyric.font.family` = `@host:MiSans`）⇒ 用户打开对话框第一眼该看到它，否则会以为"默认字体不在列表里" |
     * | 本机全量 | 250+ 条的大列表，兜底 —— 保持最后 |
     *
     * ⚠️ 宿主与系统**同名**时（本机真装了 `MiSans`）会同时出现两条
     * （`@host:MiSans` 与 `MiSans`）—— 这是**刻意的**，不是重复项，
     * 见 [FontCatalog.dedupe] 的「不会互相吞掉」段。
     *
     * ## 为什么 `fromSystem` 失败要单独兜住
     *
     * 无头环境 / 字体子系统异常时 `GraphicsEnvironment` 会抛。此时**本机全量
     * 这一块整体不可用**，但近期与字体包仍然有价值 —— 降级为"只有那两块"，
     * 而不是整个对话框打不开。
     *
     * ★ 宿主那一段**不需要**同样的兜底：`FontCatalog.fromHost` 的契约是"**不抛**"
     * （空输入 ⇒ 空候选），且 [HostFonts.SENTINELS] 是纯内存读（未预热时只有
     * `@host:MiSans`）⇒ 宿主不可用时表现为"候选里只有那一条"，对话框照常可用。
     */
    private fun reloadCandidates() {
        val recent = FontCatalog.fromRecent(historyRaw)
        // ★★ 2026-09-29：宿主内置字体（哨兵值）—— 本对话框此前**选不到** `@host:…`，
        //    是"从宿主复用字体"这条线的最后一块（见类 KDoc 的"三来源"说明）。
        //
        // 为什么这里再调一次 `warmUpAsync()`（插件启动时 `CdlPlugin` 已调过）：
        // ① 它是**幂等**的（已扫描/已失败 ⇒ 立即返回）· **非阻塞**（内部起守护线程）；
        // ② 覆盖"启动那次还没跑完 / 失败后想重试"的窗口 —— 否则用户刚启动就打开
        //    本对话框时，宿主段只有 `@host:MiSans` 一条。
        // ⚠️ 它**绝不**在 EDT 上同步扫描（那是 1.4 s 冻结，见 `HostFonts.resolveOrNull` 的 KDoc）；
        //    故本次读到的 `SENTINELS` 可能仍是**上一次**的快照 —— 这是可接受的
        //    （列表本就是快照，用户重开对话框即刷新）。
        HostFonts.warmUpAsync()
        val host = FontCatalog.fromHost(HostFonts.SENTINELS)
        val system = runCatching {
            FontCatalog.fromSystem(GraphicsEnvironment.getLocalGraphicsEnvironment())
        }.onFailure {
            System.err.println("[CDL] 读取本机字体族失败，字体对话框进入降级形态：$it")
        }.getOrElse { emptyList() }

        allCandidates.clear()
        // 顺序见上方 KDoc 的表格：近期 → 刚导入 → 宿主内置 → 本机全量。
        allCandidates += FontCatalog.dedupe(recent + importedCandidates + host + system)
        applyFilter()
    }

    /** 按搜索框内容过滤并刷新列表模型。 */
    private fun applyFilter() {
        val keyword = searchField.text.trim()
        val visible = if (keyword.isEmpty()) {
            allCandidates
        } else {
            val lower = keyword.lowercase(java.util.Locale.ROOT)
            allCandidates.filter { candidate ->
                // ★ 2026-09-29：**同时**匹配显示名 —— 否则用户照着界面上看到的
                //   「宿主内置 MiSans」去搜会**搜不到**（`family` 是哨兵 `@host:MiSans`，
                //   里面既没有"宿主"也没有空格分隔的"内置"）。
                //   朴素要求：**列表显示什么，就应该能搜什么**。
                candidate.family.lowercase(java.util.Locale.ROOT).contains(lower) ||
                    displayFamily(candidate.family).lowercase(java.util.Locale.ROOT).contains(lower)
            }
        }

        // 记住当前选择，刷新后尽量保住它（否则输入一个字符就丢失选中项，
        // 用户的下一次回车会作用在一个意外跳转的项上）。
        val keep = value
        listModel.clear()
        visible.forEach { listModel.addElement(listKey(it)) }
        countLabel.text = "共 ${visible.size} 项" +
            if (keyword.isEmpty()) "" else "（已过滤，总 ${allCandidates.size} 项）"

        val index = visible.indexOfFirst { it.family == keep }
        if (index >= 0) {
            familyList.selectedIndex = index
        } else if (listModel.size() > 0 && keep == originalFamily) {
            // 打开时 originalFamily 可能**不在**候选里（例如它是手打进配置的、
            // 系统已卸载的族名）。此时不强行选第一项 —— 那会让"当前选择"标签
            // 显示成别的字体，用户一按回车就把它落盘了。保持无选中，让 status 说明。
            familyList.clearSelection()
        }
        updateStatus()
    }

    /**
     * 列表项在 `JList` 里的显示键。
     *
     * 为什么不是裸族名：`JList` 用**元素相等**判断选中，而不同来源可能有**同族名**
     * 的候选（虽然 [FontCatalog.dedupe] 已去重，但显示键必须唯一才能让
     * `setSelectedValue` 工作可靠）。把来源前缀编进显示键，既解决唯一性，
     * 又让用户一眼看到"这条来自哪里"。
     */
    private fun listKey(candidate: FontCandidate): String =
        // ★★★ 2026-10-07 **BUG-161 修复**：这里原来用 `displayFamily(...)`（**友好名**），
        //   ⇒ 列表渲染器拿到的"族名"是「宿主内置 MiSans」这种**显示名**，
        //     而 `Font("宿主内置 MiSans", …)` 必然解析不到 ⇒ ★ **每一条宿主字体
        //     都用回退字形画自己**（用户看不出"宿主内置 X"长什么样）。
        //   ★ 修法 = **key 携带【权威值】**（宿主族就是哨兵 `@host:…`），
        //     友好名改由**渲染器**用 `HostFonts.displayName` 现场算 ⇒ 显示不变、
        //     而解析路径与 `FontPicker` **同一条**（`FontPicker` 早就用哨兵解析，
        //     并留了注释「哨兵值不走可用性判据」—— 本文件当时漏了这条）。
        //   ⚠️ `displayFamily` 仍用于**筛选**与**选择态标签**，那两处传的本来就是族名。
        "${sourceTag(candidate.source)} ${candidate.family}"

    /**
     * 列表里显示的**族名**（2026-09-29 新增）。
     *
     * 为什么需要它：宿主族的**权威值**是哨兵（`@host:MiSans`），但把哨兵原样
     * 摊给用户看是错的 —— 那是内部编码，不是字体名。转换交给
     * [HostFonts.displayName]（它对非哨兵**原样返回**，故调用方不必先判）。
     *
     * ⚠️ 与 [listKey] 的分工：本函数只管"族名那一段"，来源前缀由 [sourceTag] 出。
     * 两者都进 [listKey]，故 [resolveSelection] 的反查**自动**跟着一致
     * （显示键 → 候选的唯一映射永远由同一个 [listKey] 生成）。
     */
    private fun displayFamily(family: String): String = HostFonts.displayName(family)

    private fun sourceTag(source: dev.cdl.font.FontSource): String = when (source) {
        dev.cdl.font.FontSource.RECENT -> "[近期]"
        dev.cdl.font.FontSource.SYSTEM -> "[本机]"
        // ★ 2026-09-29：宿主内置族。刻意用**短**标签 —— 列表项已含 `宿主内置 X`
        //   的完整显示名（见 [displayFamily]），此处再写全称会重复两遍。
        dev.cdl.font.FontSource.HOST -> "[宿主]"
        dev.cdl.font.FontSource.FILE -> "[包]"
    }

    /** 从 `JList` 的选中项反查候选。显示键 → 候选的唯一映射在这里。 */
    private fun resolveSelection(list: JList<*>): FontCandidate? {
        val key = list.selectedValue as? String ?: return null
        return allCandidates.firstOrNull { listKey(it) == key }
    }

    private fun installSearchWiring() {
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = applyFilter()
            override fun removeUpdate(event: DocumentEvent) = applyFilter()
            override fun changedUpdate(event: DocumentEvent) = applyFilter()
        })
        // Esc 关闭 = 取消（与 [FontPicker] 的 Esc 语义一致）。
        root.inputMap.put(
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) {
                    cancelled = true
                    closeDialog()
                }
            },
        )
    }

    // ── 状态与提交 ──────────────────────────────────────────────────────

    private fun updateStatus() {
        val selected = allCandidates.firstOrNull { it.family == value }
        // ★ 2026-09-29 顺带修：`selectionProbe` 那个 JLabel 此前**从没人设置过文字**
        //   （`buildBottom` 的注释写着"会在 updateStatus 里被改"，但本函数里没有那一句）
        //   ⇒ 界面上"当前选择："后面**永远空白**。这里补上。
        //   ⚠️ 用 null-safe：`updateStatus` 可能在 `buildBottom` 之前被调到
        //   （`reloadCandidates → applyFilter → updateStatus`），那时字段仍是 null
        //   —— 纪律 13：构造期回调 + 晚初始化字段 = 必然 NPE。
        selectionLabel?.text = displayFamily(value)
        statusLabel.text = when {
            // ⚠️ 用 displayFamily 而不是裸 `$value`：哨兵（`@host:…`）是内部编码，
            //    摊给用户看是错的（同 [displayFamily] 的 KDoc）。
            selected == null ->
                "当前选择「${displayFamily(value)}」不在候选列表里" +
                    "（可能是手填的族名、已卸载的字体，或宿主字体尚未就绪）。"
            selected.source == dev.cdl.font.FontSource.FILE ->
                "来源：字体包 ${selected.filePath}"
            selected.source == dev.cdl.font.FontSource.RECENT ->
                "来源：近期使用过（确认后会再次置顶）。"
            // ★ 2026-09-29：**必须**有这一支 —— 否则宿主族会掉进下面的 `else`，
            //   被报成"本机已安装字体"（不实，且会让用户以为它可以脱离宿主存在）。
            selected.source == dev.cdl.font.FontSource.HOST ->
                "来源：宿主程序自带（随宿主分发，与宿主界面同款）。" +
                    "未预热时这里可能只列出 MiSans。"
            else -> "来源：本机已安装字体。"
        }
        previewPanel?.repaint()
    }

    /**
     * 确认：落盘族名 + 更新历史。
     *
     * **历史只在确认时才 record** —— 与 [FontPicker] 的"预览不落盘"纪律一致
     * （`recon/07` §1 铁律①）：用户在列表里划过 20 个字体不会把历史刷成那 20 个。
     */
    private fun confirmAndClose() {
        val family = value
        if (family.isBlank()) {
            JOptionPane.showMessageDialog(
                dialog, "请选择一个字体族。", "未选择字体", JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        val newHistory = FontHistory.serialize(FontHistory.record(FontHistory.parse(historyRaw), family))
        cancelled = false
        // 先关窗再回调：回调会写盘 + 触发重绘，若窗口还在，
        // 用户会看到"窗口还在但设置已变"的中间态。
        closeDialog()
        runCatching { onCommit(family, newHistory) }.onFailure {
            System.err.println("[CDL] 字体确认回调失败：$it")
        }
    }

    private fun closeDialog() {
        (dialog ?: dialogFromAncestor())?.dispose()
    }

    private fun dialogFromAncestor(): javax.swing.JDialog? =
        SwingUtilities.getWindowAncestor(root) as? javax.swing.JDialog

    // ── 文件选择 ────────────────────────────────────────────────────────

    /**
     * 弹**原生文件对话框**（`java.awt.FileDialog`）让用户挑字体包。
     *
     * ## 为什么用 `FileDialog` 而不是 `JFileChooser`
     *
     * `JFileChooser` 是 Swing 自绘的（在 Windows 上长得像 1998 年的 Motif），
     * 而 `FileDialog` 是**系统原生**对话框 —— 用户能用熟悉的侧边栏、最近访问、
     * 上一级按钮。用户诉求原话是"从电脑中选择字体包"，原生对话框才配得上这句话。
     *
     * ## ⚠️ 实测限制：`FileDialog` 的原生多选在 Windows 上**不保证可用**
     *
     * `FileDialog.setMultipleMode(true)` 是 AWT 的 API，但**在 Windows 上
     * 它是否生效取决于 JDK 实现**（本机 JDK 21 实测：置 true 后
     * `getFiles()` 仍可能只返回一个）。故本实现**按单文件设计、多文件兼容**：
     * 只处理 `getFiles()` 返回的全部，但**不依赖它返回多于一个** ——
     * 用户想加多个字体包，再点一次"从电脑中选择字体包…"即可。
     * 这样无论 JDK 行为如何，功能都完整（只是多点几次）。
     *
     * ## 为什么 `owner = null`
     *
     * 本对话框有两个入口：设置窗口（有 owner）与 `preference_config.json`
     * 的 `button`（`on_click` 反射、**拿不到父窗口**）。为了让两条路径共用同一段
     * 代码，这里统一用 `null` —— `FileDialog(null as Frame?, ...)` 是合法的，
     * 表现为对话框不隶属任何窗口（Windows 上仍正常置顶显示）。
     * 为一个"更好看的居中"分两条实现路径不值得。
     *
     * ## 许可证
     *
     * 过滤器按扩展名**筛选显示**，不复制文件。用户选中的文件只是被**读取**
     * （`Font.createFonts` 打开它）—— 不产生任何再分发。
     */
    private fun pickFontFiles() {
        val dialog = FileDialog(null as Frame?, "选择字体包（.ttf / .otf / .ttc）", FileDialog.LOAD)
        // 过滤器：Windows 上 FileDialog 的 setFilenameFilter 是**大小写敏感**的
        // （与 Unix 一致），而磁盘上常见 `MSYH.TTC` 这样的大写名。
        // 故这里把常见大小写组合都列上 —— 只列小写会让用户"看不到自己的字体文件"。
        dialog.setFilenameFilter { _, name ->
            val lower = name.lowercase(java.util.Locale.ROOT)
            FONT_SUFFIXES.any { lower.endsWith(it) }
        }
        dialog.isMultipleMode = true
        dialog.isVisible = true

        val picked = dialog.files?.map { it.toPath() }.orEmpty()
        if (picked.isEmpty()) return
        importFiles(picked)
    }

    /**
     * 在**后台线程**加载用户选中的字体包，完成后回到 EDT 刷新列表。
     *
     * ## 为什么必须后台
     *
     * `Font.createFonts` 对一个大 `.ttc`（`msyh.ttc` 约 20 MB）要读文件 + 解析
     * `name` 表 + 建字形索引，实测在几十毫秒量级；用户一次选几个就是几百毫秒。
     * 在 EDT 上做会让**整个界面冻结**（包括本对话框的"正在加载"都无法绘制）。
     *
     * ## 为什么用 `Thread` 而不是线程池
     *
     * 这是一次性的、用户点击驱动的、**串行**的操作（用户在等结果，
     * 不可能同时发起两次导入）。为一个这样的任务引入 `ExecutorService`
     * 意味着要管它的生命周期（谁 shutdown、插件卸载时怎么办）——
     * 而一个 daemon 线程用完即走，没有生命周期问题。
     * 用 `isDaemon = true` 保证插件卸载后它不会吊住 JVM。
     */
    private fun importFiles(files: List<Path>) {
        statusLabel.text = "正在加载 ${files.size} 个字体包…"
        val environment = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
        }.getOrNull()

        Thread {
            // `registerInto = environment`：让 Swing 按族名能取回这些字体。
            // 不登记的话，用户从列表里选了它，`new Font(family, ...)` 会静默回退 ——
            // 见 FontLoader.registerInto 的 KDoc。
            val candidates = FontCatalog.fromFiles(files, environment)
            SwingUtilities.invokeLater {
                if (candidates.isEmpty()) {
                    statusLabel.text = "未能从所选文件解析出字体（文件可能已损坏或不是字体格式）。"
                    JOptionPane.showMessageDialog(
                        dialog,
                        "所选文件里没有解析出任何字体。\n\n" +
                            "常见原因：\n" +
                            "· 文件损坏或下载不完整\n" +
                            "· 不是字体文件（例如图片改了扩展名）\n" +
                            // ★ 2026-10-01 更正：原写「**字体集合文件（.ttc）的格式 JDK 不支持**」——
                            //   **与同文件 tooltip 自相矛盾**（本文件 `loadFamilies` 处写
                            //   「支持 .ttf / .otf / **.ttc** / .otc」），且 `FontLoader` 正是
                            //   **因为 JDK 支持**才用 `Font.createFonts` 去展开 `.ttc`。
                            //   ⇒ 真实原因是"该集合里**没有**可用的字体族"，不是"格式不支持"。
                            "· 字体集合文件（.ttc / .otc）里没有可用的字体族",
                        "未能加载字体包",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return@invokeLater
                }
                importedCandidates += candidates
                // 自动选中本次导入的第一个 —— 用户刚选了文件，
                // 他几乎必然就是要用它，让他少点一次。
                value = candidates.first().family
                reloadCandidates()
                updateStatus()
            }
        }.apply { isDaemon = true; name = "cdl-font-import" }.start()
    }

    companion object {
        /** 预览保底字体（同 [FontPicker]：出厂默认族，Windows 上一定存在的简体中文字体）。 */
        private const val FALLBACK_FAMILY = DEFAULT_FONT_FAMILY

        /**
         * 认作字体包的扩展名（小写比较）。
         *
         * 与 `FontLoader.FONT_EXTENSIONS` **刻意分开**：那边是"扫描目录时的粗筛"，
         * 这边是"文件对话框里给用户看的可选项"。两者语义不同 ——
         * 目录扫描要**更保守**（少认几种，避免把无关大文件拖进解析），
         * 文件对话框要**更宽松**（用户点开了它，就让他试）。
         * 例如 `.woff`/`.woff2` 是网页字体，JDK 的 `createFonts` 解析不了，
         * 但用户确实可能想试 —— 让 `FONT_EXTENSIONS` 不含它们（扫描时忽略），
         * 而这里含（允许用户主动试，失败有明确提示）。
         */
        private val FONT_SUFFIXES = listOf(".ttf", ".otf", ".ttc", ".otc", ".woff", ".woff2", ".fon")

        /**
         * 弹出对话框并**阻塞**直到用户关闭它。
         *
         * ## 为什么阻塞（而不是回调式）
         *
         * 调用方（设置窗口 / `on_click` 入口）在返回后就要决定"要不要落盘"，
         * 而决定所需的全部信息（用户选了哪个族名、是否取消）只有对话框关闭时才有。
         * 回调式会迫使调用方把"落盘"逻辑写进一个 lambda 里跨栈传递 ——
         * 而 [onCommit] 已经是那个 lambda 了，再加一层只是把同一件事说两遍。
         *
         * ## 契约
         *
         * 用户取消 / 关闭窗口 → **不调用** [onCommit]（调用方无须判断）。
         *
         * @param owner 父窗口。`null` 表示无属主（`on_click` 入口就是这种情况）。
         */
        fun show(
            owner: java.awt.Window?,
            originalFamily: String,
            previewSize: Int,
            historyRaw: String,
            onCommit: (family: String, newHistoryRaw: String) -> Unit,
        ) {
            check(SwingUtilities.isEventDispatchThread()) {
                "FontSourceDialog.show 必须在 EDT 上调用（它会构造 Swing 组件）"
            }
            val state = FontSourceDialog(originalFamily, previewSize, historyRaw, onCommit)
            val dialog = JOptionPane.getRootFrame().let { frame ->
                javax.swing.JDialog(frame, "选择字体", true)
            }
            state.dialog = dialog
            dialog.contentPane = state.root
            dialog.defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
            // ★ 2026-09-21：**本窗此前完全没有跟随系统深浅主题**（BUG-108 同族）——
            //    底色、边线、标签、预览文字全是硬编码浅色，深色主题下是一块亮板。
            //    现在走统一出口（见 [ToolWindowPalette] 的 KDoc）。
            //    ⚠️ 必须在 `contentPane` 设好、且组件树建完之后调（它递归刷整棵树）。
            //    ⚠️ 2026-09-23：**显式传 `toolWindowFontProvider()`** —— 用户原话
            //       「（自绘窗口）**也还没随所选字体**」。此前这里是 2 参调用，
            //       靠 `fontProvider` 的默认值（当时是 `null`）⇒ 恒回退**出厂默认族**。
            //       现在默认值本身就是这个 provider，这里**再显式传一遍**是为了让
            //       "本窗字体来自设置项 `main_lyric.font.family`"在**代码里看得见**
            //       （而不是只靠一个默认值）。探针 `ToolWindowCoverageProbe` §4b 机械核对。
            applyToolWindowTheme(dialog.contentPane, dialog.rootPane, toolWindowFontProvider())
            dialog.pack()
            // ★ 2026-10-18：允许拉大窗口 —— 240px 的列表高度在 1080p 下只够约 11 行；
            //   用户报「无法上下滚动」时也常伴随"窗口太小看不清"。可调整不改变任何判据。
            dialog.isResizable = true
            dialog.minimumSize = Dimension(520, 420)
            // 标题栏要在 **peer 建立之后**再刷一次：`pack()` 之前那次取不到 HWND
            // （`hwndOf` 第一句就是 `if (!window.isDisplayable) return null`），属静默失效。
            dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowOpened(e: java.awt.event.WindowEvent) {
                    syncNativeTitleBar(dialog.contentPane, dark = !ToolWindowPalette.isLight)
                }
            })
            dialog.setLocationRelativeTo(owner)
            dialog.isVisible = true
            // `isVisible = true` 会**阻塞**直到窗口关闭（模态）。
            // 若用户点的是窗口的 X（不是"取消"按钮），`cancelled` 仍是 true 的初值 ——
            // 语义正确：X 等同取消，不落盘。
        }
    }
}

/**
 * 列表项渲染器：**每一项用自己那款字体画自己的名字**，并在左侧画来源标签。
 *
 * 这是"字体选择器"相比"下拉框"的全部存在理由 —— 用户不必逐项点开就知道
 * 那一项长什么样。代价可控：`JList` 只渲染可视区域内的项（约 12 行）。
 *
 * ⚠️ 族名**不可用**时用回退字体画，让用户一眼看出"这项渲染不出区别"，
 * 而不是让他以为是字体"长得像默认字体"。
 *
 * 判据是 [isFamilyAvailable] —— 即"解析回来的族名是否还是它自己"。
 * 2026-09-19 接随包字体后它一度必须绕开族名比对（包内字体没注册进系统族表，
 * 比对必然不等 ⇒ **假阴性**）；随包字体取消后族名比对重新是正确判据，
 * 详见 [isFamilyAvailable] 的 KDoc。
 */
private class SourceCellRenderer(
    private val previewSize: Int,
) : javax.swing.DefaultListCellRenderer() {

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
        val raw = value as? String
        if (raw != null) {
            // 显示键形如 "[近期] 微软雅黑"。★ **族名 = 第一个空格之后的全部**
            // （族名本身可含空格，故不能用 split 取第二段）。
            // ★★★ 2026-10-07 **BUG-161**：现在这一段是**权威值**（宿主族 = 哨兵），
            //   故解析要走**唯一漏斗** —— 与 `FontPicker` 同一条路。
            val family = raw.substringAfter(' ', raw)
            val resolved = dev.cdl.font.resolveFontOrFallback(family, LIST_ITEM_SIZE)
            // ★ 显示文本仍用**友好名**（哨兵是内部编码，摊给用户看是错的）。
            val shown = dev.cdl.font.HostFonts.displayName(family)
            label.text = shown
            // 判据见 [isFamilyAvailable]（族名比对）· ★ 哨兵走 `fontMatchesRequest`。
            val candidate = if (dev.cdl.font.fontMatchesRequest(family, LIST_ITEM_SIZE)) {
                resolved
            } else {
                // 回退：用列表默认字体，让"渲染不出区别"可见。
                //
                // ⚠️ 这里**不能**写成 `label.font = label.font.deriveFont(...)` ——
                // 赋值语句的值是 `Unit`，会让 `if/else` 的公共类型退化成 `Any`，
                // 于是外层 `label.font = <Any>` 编译失败
                // （`Assignment type mismatch: actual type is 'Any', but 'Font!' was expected`）。
                // 本轮实际踩到过。`deriveFont` 的**返回值**才是要赋的东西。
                label.font.deriveFont(LIST_ITEM_SIZE.toFloat())
            }
            // ★★★ 2026-10-18：**候选字体必须能显示整段文字**，否则回退列表字体。
            //
            // 现场（用户截图）：宿主族 `@host:Inter` 的显示名是「宿主内置 Inter」，
            // 含四个汉字，而 Inter 没有中文字形 ⇒ 前四个字被画成方框
            // （截图里就是「□□□ Inter」）。
            //
            // 判据用 [java.awt.Font.canDisplayUpTo]（返回 -1 = 全部可显示）。
            // 代价：文字含中文时，拉丁字体项不再按自身字形渲染 —— 这是刻意的取舍，
            // 因为"看得懂名字"优先于"看到字形"；纯拉丁族名（如 `Inter`）仍走自身字体。
            label.font = dev.cdl.font.fontForLabelText(
                candidate = candidate,
                text = shown,
                // 回退用"列表默认字体"（renderer 进来时 label 自带的那个）按本项字号派生。
                fallback = label.font.deriveFont(LIST_ITEM_SIZE.toFloat()),
            )
        }
        return label
    }

    private companion object {
        const val LIST_ITEM_SIZE = 14
    }
}

/**
 * 供设置窗口使用的**便捷入口**：从静态桥取当前设置，落盘族名 + 历史。
 *
 * ## 为什么在这里做"取设置、落盘"而不是让调用方做
 *
 * 三个调用点（设置窗口的字体行、`preference_config.json` 的字体按钮、
 * 右键菜单的"最近字体"）需要**完全相同**的落盘语义：
 * 写 `main_lyric.font.family` + 更新 `main_lyric.font.recent`，
 * 且**一次 `apply` 同时提交两个键**（分两次提交会让词岛重绘两次，
 * 且中途的中间态是"新字体 + 旧历史"）。
 *
 * 把这段集中一处，三点就不可能漂移。
 *
 * @param owner 父窗口（可空）。
 * @return 是否真的落了盘（用户取消时为 `false`）。
 */
internal fun showFontSourceDialog(owner: java.awt.Window?): Boolean {
    val runtime = SettingsRuntimeHolder.current
    // 设置系统不可用时不弹窗：没有可写的地方，弹了也只能取消。
    // 打一行日志即可 —— 这与 SettingsWindow 的"只读形态"策略一致。
    if (runtime == null) {
        System.err.println("[CDL] 字体选择器：设置系统不可用，已忽略")
        return false
    }
    // ⚠️ 取的是 `live`（生效态）而不是 `saved`（落盘态），也没有 `snapshot()` 这个方法 ——
    // `SettingsRuntime` 暴露的是两个 `SettingsSnapshot` 属性：`live` 与 `saved`
    // （见其 `:359` / `:364`）。原来写的 `runtime.snapshot()` **不存在**，编译失败。
    //
    // 为什么用 `live`：与 `IslandPopupMenu.currentBoolean/currentText`、
    // `ToolWindows` 的取值路径**一致** —— 全项目读"当前值"一律读 `live`，
    // 这样"菜单里勾上的值"与"取色器里显示的值"不可能不一致。
    val snapshot = runtime.live
    val familyDescriptor = SettingRegistry.descriptor(FONT_FAMILY_KEY)
    val recentDescriptor = SettingRegistry.descriptor(FONT_RECENT_KEY)
    if (familyDescriptor == null || recentDescriptor == null) {
        System.err.println("[CDL] 字体选择器：注册表缺少字体项，已忽略")
        return false
    }

    var committed = false
    FontSourceDialog.show(
        owner = owner,
        originalFamily = snapshot.text(familyDescriptor),
        previewSize = snapshot.int(SettingRegistry.descriptor(FONT_SIZE_KEY)!!),
        historyRaw = snapshot.text(recentDescriptor),
    ) { family, newHistory ->
        // 一次 apply 两个键：见本函数 KDoc 的理由（避免中间态与双重绘）。
        runtime.apply(
            mapOf(
                FONT_FAMILY_KEY to family,
                FONT_RECENT_KEY to newHistory,
            )
        )
        committed = true
    }
    return committed
}

/** 字体族键（三个入口共用）。 */
internal const val FONT_FAMILY_KEY = "main_lyric.font.family"
/** 近期字体键。 */
internal const val FONT_RECENT_KEY = "main_lyric.font.recent"
/** 字号键（预览用）。 */
internal const val FONT_SIZE_KEY = "main_lyric.font.size"
