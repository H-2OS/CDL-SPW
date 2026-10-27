// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.cdl.window

import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.cdl.host.CdlPlugin
import dev.cdl.font.FontHistory
import dev.cdl.host.CdlRuntime
import dev.cdl.platform.GlobalMenuDismisser
import dev.cdl.settings.SettingDescriptor
import dev.cdl.settings.SettingGroups
import dev.cdl.settings.SettingKind
import dev.cdl.settings.Preset
import dev.cdl.settings.PresetStore
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingsRuntime
import dev.cdl.settingsui.applyToolWindowTheme
import dev.cdl.window.IslandMenuSkin.keepOpen
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.Desktop
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.WindowConstants
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import java.awt.GridLayout
import java.awt.Image
import java.net.URI
import javax.swing.ImageIcon

internal object IslandPopupMenu {

    private val TOP_LEVEL_SWITCHES: List<MenuSwitch> = MENU_TOP_LEVEL_SWITCHES

    private fun <T : JMenuItem> T.keepOpenFor(role: MenuItemRole): T {
        if (menuDismissFor(role) == MenuDismiss.KEEP_OPEN) keepOpen()
        return this
    }

    private val UNIT_BY_KEY: Map<String, String> = MENU_UNIT_BY_KEY

    private val COMMON_FONT_FAMILIES = listOf(
        "Microsoft YaHei UI", "微软雅黑", "等线", "黑体", "宋体", "楷体",
        "Arial", "Segoe UI", "Consolas", "Dialog",
        "HarmonyOS Sans SC", "MiSans", "Source Han Sans SC", "Noto Sans CJK SC",
    )

    fun build(settings: SettingsRuntime?, window: IslandWindow?): JPopupMenu {
        val menu = JPopupMenu()

        menu.add(buildSettingsTree(settings))

        for (item in TOP_LEVEL_SWITCHES) {
            val check = JCheckBoxMenuItem(item.title, currentBoolean(settings, item.key, false))

            check.putClientProperty(MENU_ITEM_KEY_PROPERTY, item.key)
            check.addActionListener {
                applySwitch(settings, item.key, check.isSelected)

                syncSwitchInstances(check, settings, item.key)

                if (item.key == "island.enabled") window?.setVisible(check.isSelected)
            }

            check.keepOpenFor(MenuItemRole.ADJUST)

            if (item.key == "island.lock_position") {
                val lockMenu = IslandMenuSkin.DirectionalMenu("锁定")
                lockMenu.add(check)

                val collapse = JCheckBoxMenuItem(
                    "锁定收起",
                    currentBoolean(settings, "island.lock_collapsed", false),
                )
                collapse.putClientProperty(MENU_ITEM_KEY_PROPERTY, "island.lock_collapsed")
                collapse.addActionListener {
                    applySwitch(settings, "island.lock_collapsed", collapse.isSelected)
                    syncSwitchInstances(collapse, settings, "island.lock_collapsed")
                }
                collapse.keepOpenFor(MenuItemRole.ADJUST)
                lockMenu.add(collapse)
                menu.add(lockMenu)
            } else {
                menu.add(check)
            }
        }

        menu.add(JMenuItem("导入字体包…").apply {
            addActionListener { CdlRuntime.openFontTool() }
        })

        if (settings != null) {
            menu.add(buildPresetMenu(settings))
        }

        run {
            val w = window
            if (w != null) {
                val quick = IslandMenuSkin.DirectionalMenu("快速布置")

                quick.add(JMenuItem("屏幕顶部正中（完整可见）").apply {
                    addActionListener { w.quickPlace(QuickPlaceEdge.TOP, onTaskbar = false) }
                })
                quick.add(JMenuItem("屏幕底部正中").apply {
                    addActionListener { w.quickPlace(QuickPlaceEdge.BOTTOM, onTaskbar = false) }
                })
                menu.add(quick)
            }
        }

        menu.add(JMenuItem("找回词岛").apply {
            addActionListener { CdlRuntime.recoverIsland() }
        })

        menu.add(JMenuItem("关于与许可").apply {
            addActionListener { CdlRuntime.showAbout() }
        })

        menu.add(JMenuItem(MENU_TITLE_SOURCE_REPO).apply {
            addActionListener { CdlRuntime.openSource() }

            isEnabled = !isPlaceholderSourceUrl(sourceUrlOrNull())
            if (!isEnabled) {

                toolTipText = "仓库地址尚未确定（打包时用 -PprojectUrl=<真实地址> 指定）"
            }
        })

        menu.add(JMenuItem(MENU_TITLE_FEED_AUTHOR).apply {

            addActionListener { showThanksWindow(settings) }
        })

        menu.add(JMenuItem("退出").apply {
            addActionListener {

                CdlPlugin.requestStop()
            }
        })

        if (settings == null) {
            disableSwitches(menu)
        }

        validateTables(menu, settingsAvailable = settings != null)

        IslandMenuSkin.apply(
            menu,
            delays = { submenuDelays(settings) },
            opacityPct = { menuOpacityPct(settings) },
            fadeEnabled = { animationEnabled(settings) },
        )

        return menu
    }

    private fun showThanksWindow(settings: SettingsRuntime?) {
        check(SwingUtilities.isEventDispatchThread()) {
            "showThanksWindow 必须在 EDT 上调用（它会构造 Swing 组件）"
        }

        thanksWindow?.takeIf { it.isDisplayable }?.dispose()

        val panel = JPanel(BorderLayout()).apply {
            background = ThanksWindowSkin.background

            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThanksWindowSkin.border),
                BorderFactory.createEmptyBorder(26, 44, 26, 44),
            )
        }

        val titleLabel = JLabel(THANKS_WINDOW_TEXT).apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER

            font = font.deriveFont(font.size2D + 7f)
            foreground = ThanksWindowSkin.foreground
        }
        val titlePanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.CENTER)
        }
        panel.add(titlePanel, BorderLayout.NORTH)

        val body = JLabel("<html>${SPONSOR_BODY_LINES.joinToString("<br>") { it.htmlEscape() }}</html>").apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER

            font = font.deriveFont(font.size2D - 2f)
            foreground = ThanksWindowSkin.muted
        }

        val bodyWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(6, 0, 12, 0)
            add(body, BorderLayout.CENTER)
        }

        val qrGrid = JPanel(GridLayout(1, 2, 16, 0)).apply {
            isOpaque = false

            border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
        }
        val qrMax = 230
        val donateIcon = loadSponsorImage(SPONSOR_QR_PATH)?.let { fitInside(it, qrMax) }
        val paypalIcon = loadSponsorImage(SPONSOR_PAYPAL_QR_PATH)?.let { fitInside(it, qrMax) }
        fun addQr(icon: ImageIcon?, fallback: String) {

            val label = if (icon != null) {
                JLabel(null, icon, javax.swing.SwingConstants.CENTER)
            } else {
                JLabel(fallback, javax.swing.SwingConstants.CENTER).apply {
                    foreground = ThanksWindowSkin.muted
                    font = font.deriveFont(java.awt.Font.PLAIN, 11f)
                }
            }
            qrGrid.add(label)
        }
        addQr(donateIcon, "二维码缺失")
        addQr(paypalIcon, "PayPal 码缺失")

        val paypalWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(14, 0, 0, 0)
        }
        val ppLabel = JLabel(SPONSOR_PAYPAL_LABEL).apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            font = font.deriveFont(font.size2D - 1f)
            foreground = ThanksWindowSkin.muted
        }
        val ppLinkLabel = JLabel(
            "<html><a href='${SPONSOR_PAYPAL_LINK}'>${SPONSOR_PAYPAL_LINK}</a></html>",
            javax.swing.SwingConstants.CENTER,
        ).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = ThanksWindowSkin.linkFg
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }

        ppLinkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = openPayPal()
        })
        val ppStack = JPanel(java.awt.GridLayout(2, 1, 0, 4)).apply {
            isOpaque = false
            add(ppLabel)
            add(ppLinkLabel)
        }
        paypalWrap.add(ppStack, BorderLayout.CENTER)

        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
            add(bodyWrap, BorderLayout.NORTH)
            add(qrGrid, BorderLayout.CENTER)
            add(paypalWrap, BorderLayout.SOUTH)
        }
        panel.add(center, BorderLayout.CENTER)

        val dialog = JDialog(
            null as Window?,
            MENU_TITLE_FEED_AUTHOR,
            Dialog.ModalityType.MODELESS,
        )
        dialog.contentPane = panel
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

        val family = currentString(settings, MENU_FONT_FAMILY_KEY, "").takeIf { it.isNotBlank() }
        val fontProvider: (() -> java.awt.Font)? = family?.let { name ->

            { java.awt.Font(name, java.awt.Font.PLAIN, titleLabel.font.size) }
        }

        applyToolWindowTheme(dialog.contentPane, dialog.rootPane, fontProvider)

        titleLabel.foreground = ThanksWindowSkin.foreground
        dialog.isAlwaysOnTop = true
        dialog.pack()

        val size = dialog.size
        dialog.minimumSize = Dimension(maxOf(size.width, 320), maxOf(size.height, 240))

        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowOpened(e: java.awt.event.WindowEvent) {

                dev.cdl.settingsui.syncNativeTitleBar(
                    dialog.contentPane,
                    dark = !dev.cdl.settingsui.ToolWindowPalette.isLight,
                )
                dialog.toFront()
            }

            override fun windowClosed(e: java.awt.event.WindowEvent) {
                if (thanksWindow === dialog) thanksWindow = null
            }
        })
        thanksWindow = dialog
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    private fun loadSponsorImage(path: String): ImageIcon? = runCatching {
        val stream = javaClass.getResourceAsStream(path) ?: return null
        stream.use {
            val icon = ImageIcon(it.readBytes())
            if (icon.image == null || icon.iconWidth <= 0 || icon.iconHeight <= 0) return null
            icon
        }
    }.getOrNull()

    private fun fitInside(icon: ImageIcon, maxSide: Int): ImageIcon {
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= maxSide && h <= maxSide) return icon
        val scale = minOf(maxSide.toDouble() / w, maxSide.toDouble() / h)
        val nw = maxOf(1, (w * scale).toInt())
        val nh = maxOf(1, (h * scale).toInt())

        val scaled = icon.image.getScaledInstance(
            nw, nh, Image.SCALE_SMOOTH,
        )
        return ImageIcon(scaled)
    }

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun openPayPal() {
        runCatching {
            if (!Desktop.isDesktopSupported()) error("Desktop API 不可用")
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) error("不支持打开浏览器")
            desktop.browse(URI(SPONSOR_PAYPAL_LINK))
        }.onFailure {
            runCatching {
                WorkshopApi.ui.toast("无法打开 PayPal 赞助链接", WorkshopApi.Ui.ToastType.Warning)
            }
        }
    }

    private fun installClickAnywhereToClose(dialog: JDialog) {
        val closer = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = dialog.dispose()
        }
        fun attach(component: Component) {
            component.addMouseListener(closer)
            if (component is java.awt.Container) component.components.forEach(::attach)
        }
        attach(dialog.contentPane)
        dialog.addMouseListener(closer)

        dialog.rootPane.registerKeyboardAction(
            object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) = dialog.dispose()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
    }

    private var thanksWindow: JDialog? = null

    private fun validateTables(menu: JPopupMenu, settingsAvailable: Boolean) {

        if (settingsAvailable) {
            val topLevelTitles = (0 until menu.componentCount)
                .mapNotNull { menu.getComponent(it) as? JMenuItem }
                .map { it.text }
                .toSet()
            val absent = TOP_LEVEL_MENU_ENTRIES.map { it.title }.filterNot { it in topLevelTitles }
            if (absent.isNotEmpty()) {
            }
        }

        for (item in TOP_LEVEL_SWITCHES) {
            val descriptor = SettingRegistry.descriptor(item.key)
            when {
                descriptor == null ->
                    Unit

                descriptor.kind != SettingKind.SWITCH ->
                    Unit
            }
        }

        val duplicated = MENU_COMPONENT_SECTIONS.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
        }
        val missing = SettingGroups.ALL - MENU_COMPONENT_SECTIONS.toSet()
        if (missing.isNotEmpty()) {
        }
        val stray = MENU_COMPONENT_SECTIONS.toSet() - SettingGroups.ALL.toSet()
        if (stray.isNotEmpty()) {
        }

        if (MENU_COMPONENT_SECTIONS != SettingGroups.ALL) {
        }

        for (key in UNIT_BY_KEY.keys) {
            val descriptor = SettingRegistry.descriptor(key)
            if (descriptor == null) {
            } else if (descriptor.kind != SettingKind.SEEKBAR) {
            }
        }
    }

    private fun disableSwitches(menu: JPopupMenu) {
        for (index in 0 until menu.componentCount) {
            val component = menu.getComponent(index)
            when (component) {
                is JCheckBoxMenuItem, is JRadioButtonMenuItem, is IslandMenuSkin.MenuStepper -> {
                    component.isEnabled = false
                    component.toolTipText = "设置系统当前不可用，无法修改外观选项。"
                }

                is JMenu -> disableSwitches(component.popupMenu)
            }
        }
    }

    private fun buildSettingsTree(settings: SettingsRuntime?): JMenu {

        val root = IslandMenuSkin.DirectionalMenu(MENU_TITLE_MORE_SETTINGS)

        for (component in MENU_COMPONENT_SECTIONS) {

            val descriptors = menuItemsForComponent(component)

            if (descriptors.isEmpty()) continue
            val sub = IslandMenuSkin.DirectionalMenu(component)

            val componentSubmenu = component in MENU_COMPONENT_SUBMENUS
            var lastSubGroup: String? = null
            for (subGroup in descriptors.map { it.subGroup }.distinct()) {
                val group = descriptors.filter { it.subGroup == subGroup }

                val asSubmenu = (componentSubmenu || subGroup in MENU_SUB_SUBMENUS) && group.size >= 2
                if (asSubmenu) {
                    if (lastSubGroup != null && !componentSubmenu) sub.addSeparator()
                    val groupMenu = IslandMenuSkin.DirectionalMenu(subGroup ?: component)
                    for (descriptor in group) {
                        menuItemFor(settings, descriptor)?.let(groupMenu::add)
                    }
                    if (groupMenu.itemCount > 0) sub.add(groupMenu)
                } else {

                    if (lastSubGroup != null) sub.addSeparator()
                    for (descriptor in group) {
                        menuItemFor(settings, descriptor)?.let(sub::add)
                    }
                }
                lastSubGroup = subGroup
            }
            if (sub.itemCount > 0) root.add(sub)
        }
        return root
    }

    private fun menuItemFor(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem? {

        val item = when (menuExposureFor(descriptor)) {

            MenuExposure.COLOR_ENTRY -> {
                val group = SettingRegistry.colorGroupFor(descriptor.key)
                    ?: return null

                JMenuItem(menuItemTitleFor(descriptor)).apply {

                    addActionListener {
                        if (group.keys.size == 1) {
                            CdlRuntime.openColorTool(group.keys.first())
                        } else {
                            CdlRuntime.openColorGroup(group.prefix, group.title)
                        }
                    }
                }
            }

            MenuExposure.SWITCH -> switchItem(settings, descriptor.key, descriptor.title)
            MenuExposure.STEPPER -> stepperItem(settings, descriptor)
            MenuExposure.RADIO_LIST, MenuExposure.FONT_RECENT -> listItem(settings, descriptor)

            MenuExposure.NOT_IN_MENU -> null
        }

        item?.putClientProperty(MENU_ITEM_KEY_PROPERTY, descriptor.key)
        return item
    }

    private fun recentFontItem(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem {
        val recent = FontHistory.parse(currentString(settings, descriptor.key, ""))
        val submenu = IslandMenuSkin.DirectionalMenu(descriptor.title)
        if (recent.isEmpty()) {
            submenu.add(JMenuItem("（还没有使用过其他字体）").apply { isEnabled = false })
            return submenu
        }
        val currentFamily = currentString(settings, MENU_FONT_FAMILY_KEY, "")

        val group = ButtonGroup()
        for (family in recent) {
            val item = JRadioButtonMenuItem(family, family == currentFamily)

            item.keepOpenFor(MenuItemRole.ADJUST)
            group.add(item)
            item.addActionListener {
                if (settings == null) return@addActionListener
                val next = FontHistory.record(recent, family)
                runCatching {
                    settings.apply(
                        mapOf(
                            "main_lyric.font.family" to family,
                            "main_lyric.font.recent" to FontHistory.serialize(next),
                        ),
                    )
                }.onFailure {
                }
            }
            submenu.add(item)
        }
        return submenu
    }

    private fun stepperItem(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem {
        val range = descriptor.range
        val min = range?.start?.toInt() ?: 0
        val max = range?.endInclusive?.toInt() ?: 100
        val fallback = descriptor.default as? Int ?: min

        val stepper = IslandMenuSkin.MenuStepper(
            label = descriptor.title,
            min = min,
            max = max,

            step = descriptor.step,
            unit = UNIT_BY_KEY[descriptor.key].orEmpty(),
            readValue = { currentInt(settings, descriptor.key, fallback) },
            writeValue = { applyValue(settings, descriptor.key, it) },
        )

        return JMenuItem().apply {
            layout = BorderLayout()
            add(stepper, BorderLayout.CENTER)
            keepOpenFor(MenuItemRole.ADJUST)
        }
    }

    private fun listItem(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem {
        val options = descriptor.options.orEmpty()
        if (options.isEmpty()) {
            return recentFontItem(settings, descriptor)
        }

        val current = currentString(settings, descriptor.key, descriptor.default as? String ?: "")
        val submenu = IslandMenuSkin.DirectionalMenu(descriptor.title)

        val group = ButtonGroup()
        for ((value, label) in options) {
            val item = JRadioButtonMenuItem(label, value == current)
            item.addActionListener { applyValue(settings, descriptor.key, value) }

            item.keepOpenFor(MenuItemRole.ADJUST)

            group.add(item)
            submenu.add(item)
        }
        return submenu
    }

    private fun currentInt(settings: SettingsRuntime?, key: String, fallback: Int): Int {
        val runtime = settings ?: return fallback
        return runCatching {
            val descriptor = SettingRegistry.descriptor(key) ?: return fallback
            descriptor.coerceInt(runtime.live.raw(key))
        }.getOrElse { fallback }
    }

    private fun submenuDelays(settings: SettingsRuntime?): SubmenuDelays = SubmenuDelays(
        openMs = currentInt(settings, SUBMENU_OPEN_DELAY_KEY, SUBMENU_OPEN_DELAY_FALLBACK_MS),
        closeMs = currentInt(settings, SUBMENU_CLOSE_DELAY_KEY, SUBMENU_CLOSE_DELAY_FALLBACK_MS),
    )

    private fun menuOpacityPct(settings: SettingsRuntime?): Int =
        currentInt(settings, MENU_OPACITY_KEY, MENU_OPACITY_FALLBACK_PCT)

    private fun animationEnabled(settings: SettingsRuntime?): Boolean =
        currentBoolean(settings, "animation.enabled", true)

    private fun buildMoreOptions(settings: SettingsRuntime?): JMenu {
        val submenu = IslandMenuSkin.DirectionalMenu("更多选项")

        submenu.add(switchItem(settings, "island.auto_hide_on_hover", "鼠标经过时自动隐藏"))
        submenu.add(switchItem(settings, "layout.translation.enabled", "显示翻译"))

        submenu.add(switchItem(settings, "island.hide_fullscreen", "全屏时隐藏"))
        submenu.add(switchItem(settings, "island.hide_paused", "暂停时隐藏"))

        submenu.addSeparator()

        val shapeItem = JMenuItem(shapeToggleLabel(settings))
        shapeItem.addActionListener {
            val current = currentString(settings, "shape.type", "SQUARE")
            val order = listOf("SQUARE", "ROUNDED_RECT", "PILL")
            val idx = order.indexOf(current)
            val next = order[((if (idx < 0) 0 else idx) + 1) % order.size]
            applyValue(settings, "shape.type", next)
        }

        shapeItem.keepOpenFor(MenuItemRole.ADJUST)
        submenu.add(shapeItem)

        return submenu
    }

    private fun shapeToggleLabel(settings: SettingsRuntime?): String {
        val current = currentString(settings, "shape.type", "SQUARE")
        val order = listOf("SQUARE", "ROUNDED_RECT", "PILL")
        val labels = mapOf("SQUARE" to "方形", "ROUNDED_RECT" to "圆角矩形", "PILL" to "胶囊")
        val idx = order.indexOf(current)
        val next = order[((if (idx < 0) 0 else idx) + 1) % order.size]
        return "切换为${labels[next]}"
    }

    private fun switchItem(settings: SettingsRuntime?, key: String, title: String): JCheckBoxMenuItem {
        val item = JCheckBoxMenuItem(title, currentBoolean(settings, key, false))

        item.putClientProperty(MENU_ITEM_KEY_PROPERTY, key)
        item.addActionListener {
            applySwitch(settings, key, item.isSelected)

            syncSwitchInstances(item, settings, key)
        }

        item.keepOpenFor(MenuItemRole.ADJUST)
        return item
    }

    private fun rootPopupOf(item: JMenuItem): JPopupMenu? {
        var component: java.awt.Component? = item.parent
        while (component != null) {
            if (component is JPopupMenu) {
                val invoker = component.invoker
                if (invoker is JMenu) {
                    component = invoker.parent
                    continue
                }
                return component
            }
            component = component.parent
        }
        return null
    }

    private fun syncSwitchInstances(clicked: JMenuItem, settings: SettingsRuntime?, key: String) {
        val root = rootPopupOf(clicked) ?: return
        val value = currentBoolean(settings, key, false)
        walkCheckBoxes(root) { candidate ->
            if (candidate.getClientProperty(MENU_ITEM_KEY_PROPERTY) == key) {
                candidate.isSelected = value
            }
        }
    }

    private fun walkCheckBoxes(root: JPopupMenu, action: (JCheckBoxMenuItem) -> Unit) {
        for (index in 0 until root.componentCount) {
            when (val component = root.getComponent(index)) {
                is JCheckBoxMenuItem -> action(component)

                is JMenu -> walkCheckBoxes(component.popupMenu, action)
                else -> Unit
            }
        }
    }

    private fun currentString(settings: SettingsRuntime?, key: String, fallback: String): String {
        if (settings == null) return fallback
        val descriptor = SettingRegistry.descriptor(key) ?: return fallback
        return runCatching { settings.live.text(descriptor) }.getOrDefault(fallback)
    }

    private fun currentBoolean(settings: SettingsRuntime?, key: String, fallback: Boolean): Boolean {
        if (settings == null) return fallback
        val descriptor = SettingRegistry.descriptor(key) ?: return fallback
        return runCatching { settings.live.boolean(descriptor) }.getOrDefault(fallback)
    }

    private fun applySwitch(settings: SettingsRuntime?, key: String, value: Boolean) {
        applyValue(settings, key, value)
    }

    private fun applyValue(settings: SettingsRuntime?, key: String, value: Any?) {
        if (settings == null) return
        runCatching { settings.apply(mapOf(key to value)) }
            .onFailure {  }

        if (key == MENU_OPACITY_KEY) {
            IslandMenuSkin.refreshVisibleMenuOpacity(
                currentInt(settings, MENU_OPACITY_KEY, MENU_OPACITY_FALLBACK_PCT),
            )
        }
    }

    fun showAt(settings: SettingsRuntime?, window: IslandWindow?, invoker: Component, x: Int, y: Int) {
        present(settings, window, invoker, anchorOverride = null)
    }

    fun showAtScreen(settings: SettingsRuntime?, window: IslandWindow?, x: Int, y: Int) {
        present(settings, window, null, anchorOverride = Point(x, y))
    }

    private fun present(
        settings: SettingsRuntime?,
        window: IslandWindow?,
        invoker: Component?,
        anchorOverride: Point?,
    ) {
        val show: () -> Unit = {
            runCatching {
                val menu = build(settings, window)
                val owner = ownerWindow()
                val anchor = menuAnchor(invoker, anchorOverride) ?: return@runCatching
                installOutsideDismiss(menu)
                hideOwnerOnClose(menu, owner)

                val size = IslandMenuSkin.displaySize(menu)
                val at = placeMenu(size, anchor, owner.graphicsConfiguration)

                val screen = screenBounds(owner.graphicsConfiguration)
                IslandMenuSkin.decideChainDirection(
                    menu, at.x, screen.x, screen.x + screen.width,
                )
                owner.setLocation(at)

                owner.isVisible = true

                menu.show(owner.contentPane, 0, 0)
            }.onFailure {

            }
            Unit
        }
        if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeLater(show)
    }

    private fun hideOwnerOnClose(menu: JPopupMenu, owner: JWindow) {
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit

            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                owner.isVisible = false
            }

            override fun popupMenuCanceled(event: PopupMenuEvent) {
                owner.isVisible = false
            }
        })
    }

    private fun ownerWindow(): JWindow = trayOwner ?: JWindow().apply {
        type = Window.Type.POPUP
        isAlwaysOnTop = true
        setSize(1, 1)
    }.also { trayOwner = it }

    private var trayOwner: JWindow? = null

    private fun menuAnchor(invoker: Component?, fallback: Point?): Point? =
        MouseInfo.getPointerInfo()?.location
            ?: fallback
            ?: invoker?.takeIf { it.isShowing }?.locationOnScreen

    private fun placeMenu(size: Dimension, anchor: Point, gc: GraphicsConfiguration?): Point {
        val screen = screenBounds(gc)

        var x = anchor.x
        var y = anchor.y - size.height
        if (x + size.width > screen.x + screen.width) x = screen.x + screen.width - size.width
        if (x < screen.x) x = screen.x
        if (y < screen.y) y = anchor.y
        return Point(x, y)
    }

    private fun screenBounds(gc: GraphicsConfiguration?): Rectangle {
        val device = gc?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        return device.defaultConfiguration.bounds
    }

    private fun installOutsideDismiss(menu: JPopupMenu) {

        val dismisser = GlobalMenuDismisser {

            if (menu.isVisible) menu.setVisible(false)
        }

        var fallbackListener: AWTEventListener? = null
        val detachFallback = {
            fallbackListener?.let { runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(it) } }
            fallbackListener = null
        }

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {

                val dismisserWindow = SwingUtilities.getWindowAncestor(menu)

                val armed = dismisser.arm(dismisserWindow)
                if (!armed) {

                    if (fallbackListener == null) {
                        val listener = AWTEventListener { awtEvent ->
                            val press = awtEvent as? MouseEvent ?: return@AWTEventListener
                            if (press.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
                            if (!menu.isVisible) return@AWTEventListener

                            if (!insideAnyPopup(press.xOnScreen, press.yOnScreen)) {
                                menu.isVisible = false
                            }
                        }
                        runCatching {
                            Toolkit.getDefaultToolkit().addAWTEventListener(
                                listener, AWTEvent.MOUSE_EVENT_MASK,
                            )
                            fallbackListener = listener
                        }
                    }
                } else {

                    detachFallback()
                }
            }

            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                dismisser.disarm()
                detachFallback()
            }

            override fun popupMenuCanceled(event: PopupMenuEvent) {
                dismisser.disarm()
                detachFallback()
            }
        })
    }

    private fun insideAnyPopup(x: Int, y: Int): Boolean {
        for (window in Window.getWindows()) {
            if (!window.isShowing) continue
            if (!window.bounds.contains(x, y)) continue
            if (containsPopup(window)) return true
        }
        return false
    }

    private fun containsPopup(container: java.awt.Container): Boolean {
        if (container is JPopupMenu) return true
        for (child in container.components) {
            if (child is java.awt.Container && containsPopup(child)) return true
        }
        return false
    }

    private fun buildPresetMenu(settings: SettingsRuntime): JMenu {
        val root = IslandMenuSkin.DirectionalMenu(MENU_TITLE_PRESET_LIST)
        val dataDir = settings.dataDirectory()

        root.add(JMenuItem("导入配置…").apply {
            addActionListener { importPreset(settings) }
        })
        root.add(JMenuItem("导出配置…").apply {
            addActionListener { exportPreset(settings) }
        })

        root.add(JMenuItem("删除预设…").apply {
            addActionListener { CdlRuntime.openPresetDeleter() }
        })
        root.add(JMenuItem("更改预设…").apply {
            addActionListener { CdlRuntime.openPresetChanger() }
        })
        root.addSeparator()

        val listing = PresetStore.list(dataDir)

        val current = settings.live

        val group = javax.swing.ButtonGroup()

        val currentValues = current.asMap()

        var presetCount = 0
        for (preset in PresetStore.BUILT_IN + listing.presets) {
            val item = JRadioButtonMenuItem(preset.name)

            item.isSelected = PresetStore.matches(preset, currentValues)
            item.addActionListener { applyPreset(settings, preset) }

            item.keepOpenFor(MenuItemRole.ADJUST)
            group.add(item)
            root.add(item)
            presetCount++
        }
        if (presetCount == 0) {
            root.add(JMenuItem("（无预设）").apply { isEnabled = false })
        }

        if (listing.skipped.isNotEmpty()) {
            root.addSeparator()
            root.add(JMenuItem("⚠ ${listing.skipped.size} 个预设读取失败").apply {
                isEnabled = false
                toolTipText = listing.skipped.joinToString("\n")
            })
        }
        return root
    }

    private fun applyPreset(settings: SettingsRuntime, preset: Preset) {

        val full = PresetStore.applyMapFor(preset)

        val resetCount = full.size - preset.values.count { it.key !in PresetStore.EXCLUDED_KEYS }
        runCatching {
            settings.apply(
                full,
                dev.cdl.settings.SettingChange.Kind.PRESET,
            )
        }.onSuccess {

            if (resetCount > 0) {
                notifyToast(
                    "已套用预设「${preset.name}」：" +
                        "它未包含的 $resetCount 项已回到默认值",
                )
            }
            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath()
        }.onFailure {
            notifyToast("应用预设「${preset.name}」失败：${it.message ?: it}", warning = true)
        }
    }

    private fun exportPreset(settings: SettingsRuntime) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "导出词岛配置"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("JSON 预设 (*.json)", "json")
            selectedFile = java.io.File("词岛配置-${java.time.LocalDate.now()}.json")
        }
        if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile

        val target = if (file.extension.isEmpty()) java.io.File(file.parentFile, file.name + ".json") else file

        if (target.exists()) {
            val answer = javax.swing.JOptionPane.showConfirmDialog(
                null,
                "「${target.name}」已存在，要覆盖它吗？",
                "导出配置",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE,
            )
            if (answer != javax.swing.JOptionPane.OK_OPTION) return
        }
        PresetStore.exportTo(
            target,
            target.nameWithoutExtension,
            settings.live.asMap(),
        )
            .onSuccess {
                notifyToast("已导出到 $it")
            }
            .onFailure {
                notifyToast("导出失败：${it.message ?: it}", warning = true)
            }
    }

    private fun importPreset(settings: SettingsRuntime) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "导入词岛配置"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("配置预设 (*.json, *.txt)", "json", "txt")
        }
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val source = chooser.selectedFile
        PresetStore.importFrom(source).fold(
            onSuccess = { report ->

                if (report.preset.values.isEmpty()) {
                    notifyImportFailure(
                        "「${report.preset.name}」没有可用的设置项" +
                            "（${report.unknownKeys.size} 个键本版不认识，" +
                            "${report.unparsableKeys.size} 个值无法解析）",
                    )
                    return@fold
                }

                val installResult = PresetStore.install(settings.dataDirectory(), source)
                val installed = installResult.getOrNull()
                val installError = installResult.exceptionOrNull()?.message
                runCatching {
                    settings.apply(
                        PresetStore.filterForPreset(report.preset.values),
                        dev.cdl.settings.SettingChange.Kind.PRESET,
                    )
                }
                    .onFailure {  }
                notifyImportResult(report, installed, installError)
            },
            onFailure = { notifyImportFailure("导入配置失败：${it.message ?: it}") },
        )
    }

    private fun notifyImportResult(
        report: PresetStore.ImportReport,
        installed: java.nio.file.Path?,
        installError: String? = null,
    ) {
        if (report.unknownKeys.isNotEmpty()) {
        }
        if (report.unparsableKeys.isNotEmpty()) {
        }
        val name = installed?.fileName?.toString()?.substringBeforeLast('.') ?: report.preset.name
        val problems = ArrayList<String>(3)
        if (installed == null) {
            problems += "未能存入预设列表" +
                (installError?.let { "：$it" } ?: "（数据目录不可用）")
        }
        if (report.unknownKeys.isNotEmpty()) problems += "${report.unknownKeys.size} 个键本版没有，已忽略"
        if (report.unparsableKeys.isNotEmpty()) problems += "${report.unparsableKeys.size} 个值无法解析，已忽略"
        val head = "已导入「$name」：应用 ${report.preset.values.size} 项"
        if (problems.isEmpty()) {
            notifyToast("$head，已存入预设列表")
        } else {
            notifyToast("$head；${problems.joinToString("；")}", warning = true)
        }
    }

    private fun notifyImportFailure(message: String) {
        notifyToast(message, warning = true)
    }

    private fun notifyToast(message: String, warning: Boolean = false) {
        runCatching {

            val type = if (warning) {
                WorkshopApi.Ui.ToastType.Warning
            } else {
                WorkshopApi.Ui.ToastType.Success
            }
            WorkshopApi.ui.toast(message, type)
        }.onFailure {  }
    }

}
