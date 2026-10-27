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

internal class FontSourceDialog private constructor(

    private var originalFamily: String,

    private val previewSize: Int,

    private val historyRaw: String,

    private val onCommit: (family: String, newHistoryRaw: String) -> Unit,
) {

    var value: String = originalFamily
        private set

    private val allCandidates = ArrayList<FontCandidate>()
    private val listModel = DefaultListModel<String>()
    private val familyList = JList(listModel)

    private val searchField = JTextField(20)
    private val statusLabel = JLabel(" ")
    private val countLabel = JLabel(" ")

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

                val importButton = ThemeButton("从电脑中选择字体包…").apply {
                    toolTipText = "支持 .ttf / .otf / .ttc / .otc。" +
                        "只读取你选中的文件，不复制、不打包进插件。"
                    addActionListener { pickFontFiles() }
                }
                add(importButton)
                add(

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

        val scroll = JScrollPane(familyList).apply {

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

                    val shown = dev.cdl.font.resolveFontOrFallback(value, previewSize)
                    g2.font = shown
                    g2.color = ToolWindowPalette.fg
                    val metrics = g2.fontMetrics

                    g2.drawString("CDL for SPW 0123", 8, 18 + metrics.ascent)
                    g2.font = shown.deriveFont(Math.max(9f, previewSize - 6f))
                    g2.color = ToolWindowPalette.muted

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

                    foreground = ToolWindowPalette.fg
                    font = font.deriveFont(Font.BOLD)
                }.also { selectionLabel = it })
                add(ThemeButton("确定").apply { addActionListener { confirmAndClose() } })
                add(ThemeButton("取消").apply { addActionListener { cancelled = true; closeDialog() } })
            },
            BorderLayout.SOUTH,
        )

        previewPanel = preview
    }

    private var previewPanel: JPanel? = null

    private var selectionLabel: JLabel? = null

    private var cancelled = false

    private var dialog: javax.swing.JDialog? = null

    private fun reloadCandidates() {
        val recent = FontCatalog.fromRecent(historyRaw)

        HostFonts.warmUpAsync()
        val host = FontCatalog.fromHost(HostFonts.SENTINELS)
        val system = runCatching {
            FontCatalog.fromSystem(GraphicsEnvironment.getLocalGraphicsEnvironment())
        }.onFailure {
        }.getOrElse { emptyList() }

        allCandidates.clear()

        allCandidates += FontCatalog.dedupe(recent + importedCandidates + host + system)
        applyFilter()
    }

    private fun applyFilter() {
        val keyword = searchField.text.trim()
        val visible = if (keyword.isEmpty()) {
            allCandidates
        } else {
            val lower = keyword.lowercase(java.util.Locale.ROOT)
            allCandidates.filter { candidate ->

                candidate.family.lowercase(java.util.Locale.ROOT).contains(lower) ||
                    displayFamily(candidate.family).lowercase(java.util.Locale.ROOT).contains(lower)
            }
        }

        val keep = value
        listModel.clear()
        visible.forEach { listModel.addElement(listKey(it)) }
        countLabel.text = "共 ${visible.size} 项" +
            if (keyword.isEmpty()) "" else "（已过滤，总 ${allCandidates.size} 项）"

        val index = visible.indexOfFirst { it.family == keep }
        if (index >= 0) {
            familyList.selectedIndex = index
        } else if (listModel.size() > 0 && keep == originalFamily) {

            familyList.clearSelection()
        }
        updateStatus()
    }

    private fun listKey(candidate: FontCandidate): String =

        "${sourceTag(candidate.source)} ${candidate.family}"

    private fun displayFamily(family: String): String = HostFonts.displayName(family)

    private fun sourceTag(source: dev.cdl.font.FontSource): String = when (source) {
        dev.cdl.font.FontSource.RECENT -> "[近期]"
        dev.cdl.font.FontSource.SYSTEM -> "[本机]"

        dev.cdl.font.FontSource.HOST -> "[宿主]"
        dev.cdl.font.FontSource.FILE -> "[包]"
    }

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

    private fun updateStatus() {
        val selected = allCandidates.firstOrNull { it.family == value }

        selectionLabel?.text = displayFamily(value)
        statusLabel.text = when {

            selected == null ->
                "当前选择「${displayFamily(value)}」不在候选列表里" +
                    "（可能是手填的族名、已卸载的字体，或宿主字体尚未就绪）。"
            selected.source == dev.cdl.font.FontSource.FILE ->
                "来源：字体包 ${selected.filePath}"
            selected.source == dev.cdl.font.FontSource.RECENT ->
                "来源：近期使用过（确认后会再次置顶）。"

            selected.source == dev.cdl.font.FontSource.HOST ->
                "来源：宿主程序自带（随宿主分发，与宿主界面同款）。" +
                    "未预热时这里可能只列出 MiSans。"
            else -> "来源：本机已安装字体。"
        }
        previewPanel?.repaint()
    }

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

        closeDialog()
        runCatching { onCommit(family, newHistory) }.onFailure {
        }
    }

    private fun closeDialog() {
        (dialog ?: dialogFromAncestor())?.dispose()
    }

    private fun dialogFromAncestor(): javax.swing.JDialog? =
        SwingUtilities.getWindowAncestor(root) as? javax.swing.JDialog

    private fun pickFontFiles() {
        val dialog = FileDialog(null as Frame?, "选择字体包（.ttf / .otf / .ttc）", FileDialog.LOAD)

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

    private fun importFiles(files: List<Path>) {
        statusLabel.text = "正在加载 ${files.size} 个字体包…"
        val environment = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
        }.getOrNull()

        Thread {

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

                            "· 字体集合文件（.ttc / .otc）里没有可用的字体族",
                        "未能加载字体包",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return@invokeLater
                }
                importedCandidates += candidates

                value = candidates.first().family
                reloadCandidates()
                updateStatus()
            }
        }.apply { isDaemon = true; name = "cdl-font-import" }.start()
    }

    companion object {

        private const val FALLBACK_FAMILY = DEFAULT_FONT_FAMILY

        private val FONT_SUFFIXES = listOf(".ttf", ".otf", ".ttc", ".otc", ".woff", ".woff2", ".fon")

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

            applyToolWindowTheme(dialog.contentPane, dialog.rootPane, toolWindowFontProvider())
            dialog.pack()

            dialog.isResizable = true
            dialog.minimumSize = Dimension(520, 420)

            dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowOpened(e: java.awt.event.WindowEvent) {
                    syncNativeTitleBar(dialog.contentPane, dark = !ToolWindowPalette.isLight)
                }
            })
            dialog.setLocationRelativeTo(owner)
            dialog.isVisible = true

        }
    }
}

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

            val family = raw.substringAfter(' ', raw)
            val resolved = dev.cdl.font.resolveFontOrFallback(family, LIST_ITEM_SIZE)

            val shown = dev.cdl.font.HostFonts.displayName(family)
            label.text = shown

            val candidate = if (dev.cdl.font.fontMatchesRequest(family, LIST_ITEM_SIZE)) {
                resolved
            } else {

                label.font.deriveFont(LIST_ITEM_SIZE.toFloat())
            }

            label.font = dev.cdl.font.fontForLabelText(
                candidate = candidate,
                text = shown,

                fallback = label.font.deriveFont(LIST_ITEM_SIZE.toFloat()),
            )
        }
        return label
    }

    private companion object {
        const val LIST_ITEM_SIZE = 14
    }
}

internal fun showFontSourceDialog(owner: java.awt.Window?): Boolean {
    val runtime = SettingsRuntimeHolder.current

    if (runtime == null) {
        return false
    }

    val snapshot = runtime.live
    val familyDescriptor = SettingRegistry.descriptor(FONT_FAMILY_KEY)
    val recentDescriptor = SettingRegistry.descriptor(FONT_RECENT_KEY)
    if (familyDescriptor == null || recentDescriptor == null) {
        return false
    }

    var committed = false
    FontSourceDialog.show(
        owner = owner,
        originalFamily = snapshot.text(familyDescriptor),
        previewSize = snapshot.int(SettingRegistry.descriptor(FONT_SIZE_KEY)!!),
        historyRaw = snapshot.text(recentDescriptor),
    ) { family, newHistory ->

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

internal const val FONT_FAMILY_KEY = "main_lyric.font.family"

internal const val FONT_RECENT_KEY = "main_lyric.font.recent"

internal const val FONT_SIZE_KEY = "main_lyric.font.size"
