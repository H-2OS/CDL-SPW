// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.animation.Motions
import dev.cdl.animation.Transition
import dev.cdl.animation.transitionOf
import dev.cdl.platform.SystemTheme
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.Collections
import java.awt.AlphaComposite
import java.util.WeakHashMap
import javax.swing.Icon
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import javax.swing.MenuElement
import javax.swing.MenuSelectionManager
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.event.MouseInputListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicMenuItemUI
import javax.swing.plaf.basic.BasicMenuUI
import javax.swing.plaf.basic.BasicPopupMenuUI
import javax.swing.plaf.basic.BasicSeparatorUI

internal object IslandMenuSkin {

    private const val rowStart = 8

    private const val rowEnd = 10

    private const val glyphBox = 16

    private const val glyphGap = 6

    private const val rangeGap = 8
    private const val stepperSlotWidth = 26
    private const val stepperGap = 3

    private const val middleColumnInset = rangeGap + stepperSlotWidth + stepperGap + stepperSlotWidth

    private const val defaultRowHeight = 28

    private const val compactRowHeight = 24

    private const val menuPadding = 5

    private const val separatorHeight = 9

    private const val SETTLE_DELAY_MS = 120

    internal const val MENU_FADE_TICK_MS = 16

    private class Palette(
        val surface: Color,
        val outline: Color,
        val hover: Color,
        val text: Color,
        val muted: Color,
        val accent: Color,
    )

    private val darkPalette = Palette(
        surface = Color(0x20, 0x20, 0x20),
        outline = Color(0x45, 0x45, 0x45),
        hover = Color(0x35, 0x35, 0x35),
        text = Color(0xF5, 0xF5, 0xF5),
        muted = Color(0x9D, 0x9D, 0x9D),
        accent = Color(0x60, 0xCD, 0xFF),
    )

    private val lightPalette = Palette(
        surface = Color(0xF9, 0xF9, 0xF9),
        outline = Color(0xD8, 0xD8, 0xD8),
        hover = Color(0xEB, 0xEB, 0xEB),
        text = Color(0x1B, 0x1B, 0x1B),
        muted = Color(0x5D, 0x5D, 0x5D),
        accent = Color(0x00, 0x67, 0xC0),
    )

    private var lightMode = SystemTheme.isLight()
    private val palette: Palette get() = if (lightMode) lightPalette else darkPalette

    private val surface: Color get() = palette.surface
    private val outline: Color get() = palette.outline
    private val hover: Color get() = palette.hover
    private val text: Color get() = palette.text
    private val muted: Color get() = palette.muted
    private val accent: Color get() = palette.accent

    private val perPixelTranslucent: Boolean by lazy {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .isWindowTranslucencySupported(
                    GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT,
                )
        }.getOrDefault(false)
    }

    @Volatile
    private var surfaceAlpha: Int = 255

    private fun setSurfaceAlpha(pct: Int) {
        surfaceAlpha = menuSurfaceAlpha(pct)
    }

    private fun pointerOverRow(row: JComponent): Boolean {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return true
        if (!row.isShowing) return false
        return Rectangle(row.locationOnScreen, row.size).contains(pointer)
    }

    private fun withSurfaceAlpha(c: Color): Color = Color(c.red, c.green, c.blue, surfaceAlpha)

    private fun menuFill(c: Color): Color = if (perPixelTranslucent) withSurfaceAlpha(c) else c

    private val transparentColor = Color(0, 0, 0, 0)

    private val watchedWindows = Collections.newSetFromMap(WeakHashMap<Window, Boolean>())

    private val font: Font by lazy {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val sample = "显示词岛鼠标穿透打开设置关于与许可退出锁定位置"
        sequenceOf("Microsoft YaHei UI", "Segoe UI Variable Text", "Segoe UI")
            .filter { it in available }
            .map { Font(it, Font.PLAIN, 13) }
            .firstOrNull { it.canDisplayUpTo(sample) == -1 }
            ?: Font("Dialog", Font.PLAIN, 13)
    }

    private fun fontFor(rowHeight: Int): Font = when {
        rowHeight >= 32 -> font.deriveFont(12.5f)
        rowHeight >= 28 -> font.deriveFont(11.5f)
        else -> font.deriveFont(11f)
    }

    private fun rowHeightFor(
        rowCount: Int,
        separators: Int,
        availableHeight: Int = workAreaHeight(),
    ): Int {
        val usable = availableHeight - menuPadding * 2 - separators * separatorHeight
        return (usable / rowCount.coerceAtLeast(1)).coerceIn(compactRowHeight, defaultRowHeight)
    }

    private fun workAreaHeight(): Int {
        val device = MouseInfo.getPointerInfo()?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val bounds = device.defaultConfiguration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(device.defaultConfiguration)
        return bounds.height - insets.top - insets.bottom
    }

    fun apply(
        menu: JPopupMenu,
        delays: () -> SubmenuDelays = { SubmenuDelays.FALLBACK },
        opacityPct: () -> Int = { MENU_OPACITY_FALLBACK_PCT },
        fadeEnabled: () -> Boolean = { true },
        isRoot: Boolean = true,
    ) {
        lightMode = SystemTheme.isLight()
        val height = adaptiveRowHeight(menu)

        menu.ui = PopupUi()
        menu.isOpaque = false
        menu.background = surface
        menu.foreground = text
        menu.border = EmptyBorder(menuPadding, menuPadding, menuPadding, menuPadding)

        menu.isLightWeightPopupEnabled = false

        menu.components.forEach { styleComponent(it, height, delays, opacityPct, fadeEnabled) }

        val contentWidth = contentWidthOf(menu)
        menu.components.forEach { wrapLabelIfNeeded(it, contentWidth, height) }

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {

                MenuFadeOut.finishAll()

                SwingUtilities.invokeLater { settlePopup(menu, opacityPct, fadeEnabled, fadeIn = false) }
                Timer(SETTLE_DELAY_MS) { settlePopup(menu, opacityPct, fadeEnabled, fadeIn = false) }
                    .apply { isRepeats = false }.start()

                if (isRoot) startHoverWatcher(menu, delays)
            }

            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {

                MenuFadeOut.onMenuHiding(menu, fadeEnabled)
                forgetMenuFade(menu)
                if (isRoot) stopHoverWatcher()
            }

            override fun popupMenuCanceled(event: PopupMenuEvent) {

                MenuFadeOut.onMenuHiding(menu, fadeEnabled)
                forgetMenuFade(menu)
                if (isRoot) stopHoverWatcher()
            }
        })
    }

    private fun contentWidthOf(menu: JPopupMenu): Int {
        var width = 0
        for (component in menu.components) {
            if (component is JSeparator) continue
            width = maxOf(width, itemPreferredWidth(component))
        }
        return if (width <= 0) minMenuWidth else width
    }

    private fun wrapLabelIfNeeded(component: Component, contentWidth: Int, baseRowHeight: Int) {
        val item = component as? JMenuItem ?: return

        if (component.components.any { it is MenuStepper }) return
        val insets = item.insets ?: return
        val available = contentWidth - insets.left - insets.right
        val lines = wrapMenuLabel(item.text.orEmpty(), available) { width ->
            item.getFontMetrics(item.font).stringWidth(width)
        }
        if (lines.size <= 1) return
        item.menuLabelLines = lines
        item.preferredSize = Dimension(item.preferredSize.width, menuRowHeightForLines(baseRowHeight, lines.size))
    }

    private const val LABEL_LINES_KEY = "cdl.menu.labelLines"

    private var JMenuItem.menuLabelLines: List<String>?
        get() = getClientProperty(LABEL_LINES_KEY) as? List<String>
        set(value) {
            putClientProperty(LABEL_LINES_KEY, value)
        }

    private fun drawnLabelWidth(item: JMenuItem): Int {
        val metrics = item.getFontMetrics(item.font)
        val lines = item.menuLabelLines
        return if (lines == null || lines.size <= 1) {
            metrics.stringWidth(item.text.orEmpty())
        } else {
            lines.maxOf { metrics.stringWidth(it) }
        }
    }

    private var hoverWatcher: Timer? = null

    private var watchedRoot: JPopupMenu? = null

    private val lastPaintedHighlight = java.util.IdentityHashMap<JMenuItem, Boolean>()

    private fun highlightDecisionFor(row: JMenuItem): Boolean {
        val model = row.model
        return shouldPaintMenuRowHighlight(
            model.isArmed, row is JMenu, model.isSelected,
            (row as? JMenu)?.isPopupMenuVisible == true, pointerOverRow(row),
        )
    }

    private var outsideSinceMs = 0L

    private fun startHoverWatcher(menu: JPopupMenu, delays: () -> SubmenuDelays) {
        outsideSinceMs = 0L
        watchedRoot = menu
        hoverWatcher?.let { if (it.isRunning) return }
        val timer = Timer(SUBMENU_HOVER_POLL_MS) {

            reconcileArmedRowWithPointer()

            repaintDivergentHighlightRows()
            collapseAbandonedSubmenus(coerceSubmenuDelayMs(delays().closeMs))
        }
        timer.isRepeats = true
        timer.start()
        hoverWatcher = timer
    }

    private fun stopHoverWatcher() {
        hoverWatcher?.stop()
        hoverWatcher = null
        watchedRoot = null

        lastPaintedHighlight.clear()
        outsideSinceMs = 0L
    }

    private fun reconcileArmedRowWithPointer() {
        val manager = MenuSelectionManager.defaultManager()
        val path = manager.selectedPath
        if (path.size < 2) return
        val row = path.last() as? JMenuItem ?: return

        if (row is JMenu && row.isPopupMenuVisible) return
        if (!row.isShowing) return
        val popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, row) as? JPopupMenu
            ?: return
        if (!popup.isShowing) return
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        if (Rectangle(row.locationOnScreen, row.size).contains(pointer)) return
        if (!Rectangle(popup.locationOnScreen, popup.size).contains(pointer)) {

            if (popup.invoker is JMenu) return
            manager.setSelectedPath(path.copyOf(path.size - 1))
            return
        }

        manager.setSelectedPath(path.copyOf(path.size - 1))
    }

    private fun repaintDivergentHighlightRows() {
        for (popup in visiblePopups()) {
            for (i in 0 until popup.componentCount) {
                val row = popup.getComponent(i) as? JMenuItem ?: continue
                if (!row.isShowing) continue
                val recorded = lastPaintedHighlight[row] ?: continue
                if (recorded != highlightDecisionFor(row)) {

                    popup.repaint()
                }
            }
        }
    }

    private fun visiblePopups(): List<JPopupMenu> {
        val out = ArrayList<JPopupMenu>(3)
        watchedRoot?.let { if (it.isShowing) out.add(it) }
        for (e in MenuSelectionManager.defaultManager().selectedPath) {
            if (e is JPopupMenu && e.isShowing && e !== watchedRoot) out.add(e)
        }
        return out
    }

    private fun collapseAbandonedSubmenus(closeDelayMs: Int) {
        val manager = MenuSelectionManager.defaultManager()
        val path = manager.selectedPath
        if (path.isEmpty()) {

            outsideSinceMs = 0L
            return
        }
        if (pointerInsideMenuTree()) {
            outsideSinceMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        val elapsed = if (outsideSinceMs == 0L) {
            outsideSinceMs = now
            0L
        } else {
            now - outsideSinceMs
        }
        when (collapseAction(elapsed, closeDelayMs, path.size)) {
            MenuCollapse.NONE -> Unit
            MenuCollapse.SUBMENUS -> {
                outsideSinceMs = 0L
                manager.setSelectedPath(arrayOf(path[0]))
            }
            MenuCollapse.ALL -> {
                outsideSinceMs = 0L
                manager.clearSelectedPath()
            }
        }
    }

    private fun pointerInsideMenuTree(): Boolean {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return true
        return Window.getWindows().any { window ->
            window.isShowing && isMenuPopupWindow(window) && window.bounds.contains(pointer)
        }
    }

    private fun isMenuPopupWindow(window: Window): Boolean {
        val content = (window as? RootPaneContainer)?.contentPane ?: return false
        return containsPopup(content)
    }

    private fun containsPopup(container: Container): Boolean {
        if (container is JPopupMenu) return true
        for (child in container.components) {
            if (child is Container && containsPopup(child)) return true
        }
        return false
    }

    private class DelayedMenuUi(
        private val openDelayMs: () -> Int,

        private val labelColor: () -> Color,
        private val mutedColor: () -> Color,
    ) : BasicMenuUI() {

        override fun installDefaults() {
            super.installDefaults()
            selectionForeground = labelColor()
            disabledForeground = mutedColor()

            acceleratorForeground = mutedColor()
            acceleratorSelectionForeground = labelColor()

            arrowIcon = null
        }

        override fun createMouseInputListener(c: JComponent): MouseInputListener =
            DelayedHoverListener(super.createMouseInputListener(c), openDelayMs, c)

        override fun paintText(g: Graphics, menuItem: JMenuItem, textRect: Rectangle, text: String) {
            if (paintWrappedLabelLines(g, menuItem, textRect, text, disabledForeground, selectionForeground)) {
                return
            }
            super.paintText(g, menuItem, textRect, text)
        }

        override fun paintBackground(g: Graphics, menuItem: JMenuItem, background: Color) {
            val model = menuItem.model

            val submenuVisible = (menuItem as? JMenu)?.isPopupMenuVisible == true

            val painted = shouldPaintMenuRowHighlight(
                model.isArmed, menuItem is JMenu, model.isSelected,
                submenuVisible, pointerOverRow(menuItem),
            )
            lastPaintedHighlight[menuItem] = painted
            if (!painted) {

                val gClear = g.create() as Graphics2D
                gClear.composite = AlphaComposite.Clear
                gClear.fillRect(0, 0, menuItem.width, menuItem.height)
                gClear.composite = AlphaComposite.SrcOver
                gClear.color = menuFill(surface)
                gClear.fillRect(0, 0, menuItem.width, menuItem.height)
                gClear.dispose()
                return
            }
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = menuFill(hover)

            g2.fillRoundRect(3, 2, menuItem.width - 6, menuItem.height - 4, 7, 7)
            g2.dispose()
        }
    }

    private class DelayedHoverListener(
        private val delegate: MouseInputListener,
        private val openDelayMs: () -> Int,
        private val source: JComponent,
    ) : MouseInputListener {

        private var pending: Timer? = null

        private var enteredAtMs = 0L
        private var pendingDelayMs = 0

        private fun cancelPending() {
            pending?.stop()
            pending = null
        }

        override fun mouseEntered(e: MouseEvent) {
            val dragging = (e.modifiersEx and DRAG_BUTTON_MASK) != 0
            val delay = coerceSubmenuDelayMs(openDelayMs())

            if (dragging || delay <= 0) {
                cancelPending()
                delegate.mouseEntered(e)
                return
            }
            cancelPending()
            enteredAtMs = System.currentTimeMillis()
            pendingDelayMs = delay
            pending = Timer(delay) { fireIfStillHovered() }.apply {
                isRepeats = false
                start()
            }
        }

        private fun fireIfStillHovered() {
            pending = null
            val elapsed = System.currentTimeMillis() - enteredAtMs
            if (!shouldOpenSubmenu(elapsed, pendingDelayMs)) return

            if (MenuSelectionManager.defaultManager().selectedPath.isEmpty()) return
            delegate.mouseEntered(freshEnteredEvent())
        }

        private fun freshEnteredEvent(): MouseEvent = MouseEvent(
            source,
            MouseEvent.MOUSE_ENTERED,
            System.currentTimeMillis(),
            0,
            1,
            1,
            0,
            false,
            MouseEvent.NOBUTTON,
        )

        override fun mouseExited(e: MouseEvent) {
            cancelPending()
            delegate.mouseExited(e)
        }

        override fun mouseClicked(e: MouseEvent) = delegate.mouseClicked(e)

        override fun mousePressed(e: MouseEvent) = delegate.mousePressed(e)

        override fun mouseReleased(e: MouseEvent) = delegate.mouseReleased(e)

        override fun mouseDragged(e: MouseEvent) = delegate.mouseDragged(e)

        override fun mouseMoved(e: MouseEvent) = delegate.mouseMoved(e)

        private companion object {

            val DRAG_BUTTON_MASK =
                InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON2_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK
        }
    }

    private fun adaptiveRowHeight(menu: JPopupMenu): Int {
        val separators = menu.components.count { it is JSeparator }
        val rows = (menu.componentCount - separators).coerceAtLeast(1)
        return rowHeightFor(rows, separators)
    }

    fun displaySize(menu: JPopupMenu): Dimension {
        val separators = menu.components.count { it is JSeparator }
        val rows = (menu.componentCount - separators).coerceAtLeast(1)
        val baseRowHeight = rowHeightFor(rows, separators)
        var height = menuPadding * 2 + separators * separatorHeight
        for (component in menu.components) {
            if (component is JSeparator) continue
            height += itemPreferredHeight(component, baseRowHeight)
        }

        var width = 0
        for (component in menu.components) {
            if (component is JSeparator) continue
            width = maxOf(width, itemPreferredWidth(component))
        }

        if (width <= 0) width = minMenuWidth
        return Dimension(width + menuPadding * 2, height)
    }

    private fun itemPreferredHeight(component: Component, fallback: Int): Int {
        val preferred = (component as? JComponent)?.preferredSize
        return if (preferred != null && preferred.height > 0) preferred.height else fallback
    }

    private fun itemPreferredWidth(component: Component): Int {

        val stepper = (component as? java.awt.Container)
            ?.components?.firstOrNull { it is MenuStepper } as? MenuStepper
        if (stepper != null) {
            val w = stepperRowWidth(component, stepper)
            if (w > 0) return w
        }
        val preferred = (component as? JComponent)?.preferredSize
        if (preferred != null && preferred.width > 0) return preferred.width
        val font = component.font ?: font
        val metrics = component.getFontMetrics(font)
        val text = (component as? JMenuItem)?.text.orEmpty()
        val insets = (component as? JComponent)?.insets
        val textWidth = metrics.stringWidth(text)
        return textWidth + (insets?.left ?: 0) + (insets?.right ?: 0)
    }

    private fun stepperRowWidth(component: Component, stepper: MenuStepper): Int {
        val insets = (component as? JComponent)?.insets
        val w = stepper.preferredSize?.width?.takeIf { it > 0 }
            ?: stepper.minimumSize?.width?.takeIf { it > 0 }
            ?: 0
        return if (w <= 0) 0 else w + (insets?.left ?: 0) + (insets?.right ?: 0)
    }

    private const val minMenuWidth = 180

    private const val CHAIN_DIRECTION_KEY = "cdl.menu.chainDirection"

    private enum class ChainDirection {

        RIGHT,

        LEFT,

        AUTO,
    }

    internal fun chainWidth(menu: JPopupMenu): Int {
        var deepest = 0
        for (component in menu.components) {
            if (component is JMenu) deepest = maxOf(deepest, chainWidth(component.popupMenu))
        }
        return displaySize(menu).width + deepest
    }

    internal fun decideChainDirection(menu: JPopupMenu, menuLeft: Int, screenLeft: Int, screenRight: Int) {
        val chain = chainWidth(menu)
        val own = displaySize(menu).width
        val direction = when {
            menuLeft + chain <= screenRight -> ChainDirection.RIGHT
            menuLeft - (chain - own) >= screenLeft -> ChainDirection.LEFT
            else -> ChainDirection.AUTO
        }
        menu.putClientProperty(CHAIN_DIRECTION_KEY, direction)
    }

    internal class DirectionalMenu(text: String) : JMenu(text) {

        override fun getPopupMenuOrigin(): Point {
            val base = super.getPopupMenuOrigin()
            return when (chainDirection()) {
                ChainDirection.RIGHT -> Point(rightOriginX(), base.y)
                ChainDirection.LEFT -> Point(leftOriginX(), base.y)
                ChainDirection.AUTO -> base
            }
        }

        private fun chainDirection(): ChainDirection =
            rootPopup()?.getClientProperty(CHAIN_DIRECTION_KEY) as? ChainDirection
                ?: ChainDirection.AUTO

        private fun rootPopup(): JPopupMenu? {
            var popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, this) as? JPopupMenu
            while (popup != null) {
                val invoker = popup.invoker as? JMenu ?: return popup
                popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, invoker) as? JPopupMenu
            }
            return null
        }

        private fun offsetX(): Int = UIManager.getInt("Menu.submenuPopupOffsetX")

        private fun rightOriginX(): Int = width + offsetX()

        private fun leftOriginX(): Int = -offsetX() - displaySize(popupMenu).width
    }

    private fun settlePopup(
        menu: JPopupMenu,
        opacityPct: () -> Int,
        fadeEnabled: () -> Boolean,
        fadeIn: Boolean,
    ) {
        fitPopupToWorkArea(menu)
        paintPopupBackground(menu)
        watchPopupWindow(menu)
        if (fadeIn) {
            startMenuFade(menu, opacityPct, fadeEnabled)
        } else {

            applyMenuOpacity(menu, opacityPct())
        }
    }

    private var menuFade: Transition? = null

    private var menuFadeTimer: Timer? = null

    private var menuFadeOwner: JPopupMenu? = null

    private var fadedMenu: JPopupMenu? = null

    private fun startMenuFade(menu: JPopupMenu, opacityPct: () -> Int, fadeEnabled: () -> Boolean) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return

        if (fadedMenu === menu) return

        if (!applyMenuOpacity(menu, opacityPct())) return

        finishRunningMenuFade()
        fadedMenu = menu

        val spec = Motions.MENU_FADE
        val durationMs = if (fadeEnabled()) spec.durationMs else 0L
        val t = transitionOf(
            from = 0f,

            to = 1.0f,
            durationMs = durationMs,

            easing = spec.easing,
            onUpdate = { v -> runCatching { window.opacity = v } },
        ) ?: return

        runCatching { window.opacity = 0f }
        menuFade = t
        menuFadeOwner = menu

        val timer = Timer(MENU_FADE_TICK_MS) { ev ->

            if (t.advance(System.nanoTime())) (ev.source as? Timer)?.stop()
        }
        menuFadeTimer = timer
        timer.start()
    }

    private fun finishRunningMenuFade() {
        val t = menuFade
        menuFadeTimer?.stop()
        menuFadeTimer = null
        menuFade = null
        menuFadeOwner = null
        t?.advance(Long.MAX_VALUE)
    }

    private fun forgetMenuFade(menu: JPopupMenu) {
        if (fadedMenu === menu) fadedMenu = null
        if (menuFadeOwner === menu) finishRunningMenuFade()
    }

    private fun applyMenuOpacity(menu: JPopupMenu, opacityPct: Int): Boolean {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return false
        val pct = coerceMenuOpacityPct(opacityPct)

        setSurfaceAlpha(pct)
        if (perPixelTranslucent) {

            val ok = runCatching { window.opacity = 1.0f }.isSuccess
            window.repaint()
            return ok
        }

        val alpha = menuOpacityAlpha(pct)
        window.repaint()
        if (runCatching { window.opacity = alpha }.isSuccess) return true

        return false
    }


    internal fun refreshVisibleMenuOpacity(opacityPct: Int) {
        val pct = coerceMenuOpacityPct(opacityPct)

        setSurfaceAlpha(pct)
        val alpha = menuOpacityAlpha(pct)
        for (window in Window.getWindows()) {
            if (!window.isShowing || !isMenuPopupWindow(window)) continue

            runCatching { window.opacity = if (perPixelTranslucent) 1.0f else alpha }

            window.repaint()
        }
    }

    private fun paintPopupBackground(menu: JPopupMenu) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return
        val content = (window as? RootPaneContainer)?.contentPane
        if (perPixelTranslucent) {

            runCatching { window.background = transparentColor }
            content?.background = transparentColor
        } else {

            window.background = surface
            content?.background = surface
        }
        (content as? JComponent)?.isOpaque = true
    }

    private fun watchPopupWindow(menu: JPopupMenu) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return
        if (!watchedWindows.add(window)) return
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = fitPopupToWorkArea(menu)
            override fun componentMoved(event: ComponentEvent) = fitPopupToWorkArea(menu)
        })
    }

    private fun fitPopupToWorkArea(menu: JPopupMenu) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return
        if (window.width <= 0 || window.height <= 0) return
        val gc = window.graphicsConfiguration ?: return
        val screen = gc.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val left = screen.x + insets.left
        val top = screen.y + insets.top
        val right = screen.x + screen.width - insets.right
        val bottom = screen.y + screen.height - insets.bottom
        var x = window.x
        var y = window.y
        if (window.height <= bottom - top) {
            if (y + window.height > bottom) y = bottom - window.height
            if (y < top) y = top
        } else {
            y = top
        }
        if (window.width <= right - left) {
            if (x + window.width > right) x = right - window.width
            if (x < left) x = left
        }
        if (x != window.x || y != window.y) window.setLocation(x, y)
    }

    private fun styleComponent(
        component: Component,
        rowHeight: Int,
        delays: () -> SubmenuDelays,
        opacityPct: () -> Int,
        fadeEnabled: () -> Boolean,
    ) {
        when (component) {
            is JMenu -> {

                component.ui = DelayedMenuUi({ delays().openMs }, { text }, { muted })

                component.delay = 0
                styleItem(component, rowHeight)

                component.border = EmptyBorder(0, rowStart, 0, rowEnd + glyphBox + glyphGap)

                apply(component.popupMenu, delays, opacityPct, fadeEnabled, isRoot = false)
            }

            is JMenuItem -> {

                component.ui = MenuItemUi(component.keepOpenRequested)
                styleItem(component, rowHeight)

                component.components.filterIsInstance<MenuStepper>().forEach { stepper ->
                    val needed = stepper.useRowHeight(rowHeight)

                    val current = component.preferredSize
                    val width = maxOf(current.width, stepperRowWidth(component, stepper))
                    val height = maxOf(current.height, needed)
                    if (width != current.width || height != current.height) {
                        component.preferredSize = Dimension(width, height)
                    }
                }
            }

            is MenuStepper -> component.useRowHeight(rowHeight)

            is JSeparator -> {
                component.ui = SeparatorUi()
                component.foreground = outline
                component.preferredSize = Dimension(1, separatorHeight)
            }
        }
    }

    private fun styleItem(component: JMenuItem, rowHeight: Int) {
        component.font = fontFor(rowHeight)
        component.foreground = if (component.isEnabled) text else muted
        component.background = surface
        component.isOpaque = false

        component.icon = null
        component.disabledIcon = null
        component.iconTextGap = 0

        component.margin = Insets(0, 0, 0, 0)

        component.border = EmptyBorder(
            0, rowStart, 0,
            if (component.isStateItem) middleColumnInset + glyphBox + rangeGap else rowEnd,
        )

        val preferred = component.preferredSize
        component.preferredSize = Dimension(maxOf(minMenuWidth, preferred.width + 12), rowHeight)
    }

    private val JMenuItem.isStateItem: Boolean
        get() = this is JCheckBoxMenuItem || this is JRadioButtonMenuItem

    internal const val KEEP_OPEN_KEY = "cdl.menu.keepOpen"

    internal fun <T : JMenuItem> T.keepOpen(): T {
        putClientProperty(KEEP_OPEN_KEY, true)
        return this
    }

    private val JMenuItem.keepOpenRequested: Boolean
        get() = getClientProperty(KEEP_OPEN_KEY) == true

    private fun middleColumnLeft(item: JMenuItem): Int {

        val labelEnd = rowStart + drawnLabelWidth(item)
        return (labelEnd + rangeGap).coerceAtLeast(0)
    }

    private fun paintWrappedLabelLines(
        graphics: Graphics,
        menuItem: JMenuItem,
        textRect: Rectangle,
        text: String,
        disabledFg: Color?,
        selectedFg: Color?,
    ): Boolean {
        val lines = menuItem.menuLabelLines ?: return false
        if (lines.size <= 1) return false
        val g = graphics.create() as Graphics2D
        val metrics = g.fontMetrics
        val lineHeight = metrics.height
        val blockHeight = lineHeight * lines.size
        val top = (menuItem.height - blockHeight) / 2

        val fallback = menuItem.foreground ?: Color.GRAY
        g.color = when {
            !menuItem.isEnabled -> disabledFg ?: fallback

            shouldPaintMenuRowHighlight(
                menuItem.model.isArmed, menuItem is JMenu, menuItem.model.isSelected,
                (menuItem as? JMenu)?.isPopupMenuVisible == true, pointerOverRow(menuItem),
            ) -> selectedFg ?: fallback
            else -> fallback
        }
        for ((index, line) in lines.withIndex()) {
            g.drawString(line, textRect.x, top + index * lineHeight + metrics.ascent)
        }
        g.dispose()
        return true
    }

    private fun decorationX(item: JMenuItem): MenuDecorationX =
        menuDecorationX(
            itemWidth = item.width,
            labelEnd = middleColumnLeft(item),
            columnInset = middleColumnInset,
            glyphWidth = glyphBox,
            rangeGap = rangeGap,
            hasCheck = item.isStateItem,
            hasArrow = item is JMenu,
        )

    private fun paintStateGlyph(graphics: Graphics, item: JMenuItem) {
        if (!item.isStateItem) return

        val x = decorationX(item).checkX ?: return
        val y = menuDecorationY(rowTopY = 0, rowHeight = item.height, glyphHeight = glyphBox)
        MenuGlyph().paintIcon(item, graphics, x, y)
    }

    private class PopupUi : BasicPopupMenuUI() {
        override fun paint(graphics: Graphics, component: JComponent) {
            val g = graphics.create() as Graphics2D

            g.color = menuFill(surface)
            g.fillRect(0, 0, component.width, component.height)
            g.color = menuFill(outline)
            g.drawRect(0, 0, component.width - 1, component.height - 1)
            g.dispose()
            super.paint(graphics, component)

            paintSubmenuArrows(graphics, component)
        }

        private fun paintSubmenuArrows(graphics: Graphics, component: JComponent) {
            for (child in component.components) {
                if (child !is JMenu || !child.isVisible) continue
                val g = graphics.create() as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val left = middleColumnLeft(child)

                val x = menuDecorationX(
                    itemWidth = child.width,
                    labelEnd = left,
                    columnInset = middleColumnInset,
                    glyphWidth = glyphBox,
                    rangeGap = rangeGap,
                    hasCheck = false,
                    hasArrow = true,
                ).arrowX ?: continue

                val y = menuDecorationY(rowTopY = child.y, rowHeight = child.height, glyphHeight = glyphBox)

                g.color = accent
                val arrowFont = child.font.deriveFont(Font.BOLD, 12f)
                if (arrowFont.canDisplay('\u203A')) {
                    g.font = arrowFont
                    g.drawString("\u203A", x + 5, y + 13)
                } else {

                    g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val cx = x + glyphBox / 2
                    val cy = y + glyphBox / 2
                    g.drawLine(cx - 2, cy - 4, cx + 3, cy)
                    g.drawLine(cx + 3, cy, cx - 2, cy + 4)
                }
                g.dispose()
            }
        }
    }

    internal class MenuStepper(

        val label: String,
        private val min: Int,
        private val max: Int,
        private val step: Int,
        private val unit: String,
        private val readValue: () -> Int,
        private val writeValue: (Int) -> Unit,
    ) : JComponent() {

        private var hovered = false

        private var editor: StepperEditor? = null

        private var caretOn = true

        private val caretBlink = Timer(CARET_BLINK_MS) {
            caretOn = !caretOn
            repaint()
        }

        private var repeatDirection = 0

        private val repeatTimer = Timer(STEPPER_REPEAT_INTERVAL_MS.toInt()) { repeatStep() }.apply {
            isRepeats = true
            initialDelay = STEPPER_REPEAT_DELAY_MS
        }

        private var valueW = DEFAULT_VALUE_W

        init {
            isOpaque = false

            isFocusable = false
            toolTipText = "$label：$min – $max$unit"
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    syncMenuSelection()
                    if (!hovered) {
                        hovered = true
                        repaint()
                    }
                }
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) = syncMenuSelection()

                override fun mouseExited(event: MouseEvent) {
                    stopRepeat()
                    if (hovered) {
                        hovered = false
                        repaint()
                    }
                }

                override fun mousePressed(event: MouseEvent) {
                    when (event.x) {
                        in minusX()..minusX() + stepperSlotWidth -> beginStep(-1)
                        in plusX()..plusX() + stepperSlotWidth -> beginStep(1)

                        in valueX()..valueX() + valueW -> beginEdit()
                    }
                }

                override fun mouseReleased(event: MouseEvent) = stopRepeat()
            })
            addMouseWheelListener { event: MouseWheelEvent ->

                val direction = if (event.wheelRotation < 0) 1 else -1

                closeEditor(commit = true)
                step(direction)
                event.consume()
            }

            addHierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !isShowing) {
                    stopRepeat()

                    closeEditor(commit = true)
                }
            }
        }

        private var labelLines: List<String> = emptyList()

        internal fun useRowHeight(height: Int): Int {
            font = fontFor(height)
            val metrics = getFontMetrics(font)

            val needed = maxOf(
                metrics.stringWidth("$min"),
                metrics.stringWidth("$max"),
                metrics.stringWidth("${readValue()}$unit"),
            )
            valueW = (needed + 16).coerceIn(DEFAULT_VALUE_W, MAX_VALUE_W)

            labelLines = wrapMenuLabel(label, assumedLabelMaxWidth()) { metrics.stringWidth(it) }
            val rowHeight = menuRowHeightForLines(height, labelLines.size)
            preferredSize = Dimension(ROW_W, rowHeight)
            minimumSize = preferredSize
            maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
            revalidate()
            repaint()
            return rowHeight
        }

        private fun assumedLabelMaxWidth(): Int = (minusX(ROW_W) - rangeGap - rowStart).coerceAtLeast(0)

        private fun plusX(w: Int = width) = w - rowEnd - stepperSlotWidth
        private fun valueX(w: Int = width) = plusX(w) - stepperGap - valueW
        private fun minusX(w: Int = width) = valueX(w) - stepperGap - stepperSlotWidth

        private fun syncMenuSelection() {
            val popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, this) as? JPopupMenu
                ?: return
            val manager = MenuSelectionManager.defaultManager()
            if (manager.selectedPath.lastOrNull() === popup) return
            manager.setSelectedPath(menuPath(popup))
        }

        private fun menuPath(popup: JPopupMenu): Array<MenuElement> {
            val chain = ArrayDeque<MenuElement>()
            var current: JPopupMenu? = popup
            while (current != null) {
                chain.addFirst(current)
                val menu = current.invoker as? JMenu ?: break
                chain.addFirst(menu)
                current = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, menu) as? JPopupMenu
            }
            return chain.toTypedArray()
        }

        private fun beginEdit() {
            stopRepeat()
            editor = stepperEditorOpen(readValue(), selectAll = true)
            caretOn = true
            attachKeyRouter()
            caretBlink.restart()
            repaint()
        }

        private fun closeEditor(commit: Boolean) {
            val current = editor ?: return
            editor = null
            caretBlink.stop()
            detachKeyRouter()
            if (commit) {

                stepperEditorCommitValue(current.buffer)?.let { value ->

                    val clamped = value.coerceIn(min, max)
                    if (clamped != readValue()) {
                        runCatching { writeValue(clamped) }
                            .onFailure {
                            }
                    }
                }
            }
            repaint()
        }

        private fun attachKeyRouter() {
            if (activeStepper === this) return
            activeStepper?.closeEditor(commit = true)
            activeStepper = this
            runCatching {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyRouter)
            }
        }

        private fun detachKeyRouter() {
            if (activeStepper !== this) return
            activeStepper = null
            runCatching {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyRouter)
            }
        }

        private fun onKey(event: KeyEvent): Boolean {
            val current = editor ?: return false
            fun keep(next: StepperEditor): Boolean {
                editor = next
                caretOn = true
                caretBlink.restart()
                repaint()
                return true
            }
            return when (event.id) {
                KeyEvent.KEY_TYPED -> {
                    if (event.isControlDown || event.isAltDown || event.isMetaDown) return false
                    val typed = event.keyChar
                    when {
                        typed in '0'..'9' -> keep(stepperEditorType(current, typed, min < 0))
                        typed == '-' && min < 0 -> keep(stepperEditorType(current, typed, true))

                        typed >= ' ' && typed != '\u007F' -> true
                        else -> false
                    }
                }

                KeyEvent.KEY_PRESSED -> {
                    val (signal, next) = stepperEditorPress(current, event.keyCode, event.isControlDown)
                    when (signal) {
                        StepperEditSignal.CONTINUE -> keep(next)
                        StepperEditSignal.COMMIT -> {
                            closeEditor(commit = true)
                            true
                        }
                        StepperEditSignal.CANCEL -> {
                            closeEditor(commit = false)
                            true
                        }
                    }
                }

                else -> false
            }
        }

        private var repeatElapsedMs = 0L

        private fun beginStep(direction: Int) {

            closeEditor(commit = true)
            repeatElapsedMs = 0L
            step(direction * accelerationFactor())
            repeatDirection = direction
            repeatTimer.restart()
        }

        private fun repeatStep() {
            if (repeatDirection == 0 || !isShowing) {
                stopRepeat()
                return
            }
            repeatElapsedMs += STEPPER_REPEAT_INTERVAL_MS
            step(repeatDirection * accelerationFactor())
        }

        private fun stopRepeat() {
            repeatDirection = 0
            repeatElapsedMs = 0L
            repeatTimer.stop()
        }

        private fun accelerationFactor(): Int = stepperAccelerationFactor(repeatElapsedMs)

        private fun step(direction: Int) {
            val next = (readValue() + direction * step).coerceIn(min, max)
            if (next == readValue()) {

                stopRepeat()
                return
            }
            runCatching { writeValue(next) }
                .onFailure {  }
            repaint()
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = font
            val metrics = g.fontMetrics

            val centerBaseline = (height + metrics.ascent - metrics.descent) / 2
            val lines = labelLines.ifEmpty { listOf(label) }
            val lineHeight = metrics.height
            val blockTop = (height - lineHeight * lines.size) / 2

            if (hovered) {

                g.color = menuFill(hover)
                g.fillRoundRect(3, 2, width - 6, height - 4, 7, 7)
            }

            val labelMax = (minusX() - rangeGap - rowStart).coerceAtLeast(0)
            g.color = text
            var widestLine = 0
            for ((index, line) in lines.withIndex()) {
                val drawn = ellipsize(line, labelMax) { metrics.stringWidth(it) }
                widestLine = maxOf(widestLine, metrics.stringWidth(drawn))
                val baseline = if (lines.size <= 1) {
                    centerBaseline
                } else {
                    blockTop + metrics.ascent + index * lineHeight
                }
                g.drawString(drawn, rowStart, baseline)
            }

            val rangeLeft = rowStart + widestLine + rangeGap
            drawRangeHint(g, rangeLeft, minusX() - rangeGap, centerBaseline)

            val slotHeight = (height - 12).coerceAtLeast(10)
            val slotY = (height - slotHeight) / 2
            drawSlot(g, metrics, minusX(), minusGlyph(), centerBaseline, slotY, slotHeight)
            drawSlot(g, metrics, plusX(), "+", centerBaseline, slotY, slotHeight)
            val boxHeight = (height - 10).coerceAtLeast(12)
            drawValueBox(g, metrics, valueX(), valueW, centerBaseline, (height - boxHeight) / 2, boxHeight)
            g.dispose()
        }

        private fun minusGlyph(): String =
            if (font.canDisplay('\u2212')) "\u2212" else "-"

        private fun drawRangeHint(g: Graphics2D, leftX: Int, rightX: Int, baseline: Int) {
            val available = rightX - leftX
            if (available <= 0) return
            val hint = if (unit.isEmpty()) "$min–$max" else "$min–$max$unit"
            var hintFont = font
            var hintWidth = g.getFontMetrics(hintFont).stringWidth(hint)
            while (hintWidth > available && hintFont.size2D > MIN_RANGE_FONT_SIZE) {
                hintFont = hintFont.deriveFont(hintFont.size2D - 1f)
                hintWidth = g.getFontMetrics(hintFont).stringWidth(hint)
            }
            val clip = g.clip
            g.clipRect(leftX, 0, available, height)
            g.font = hintFont
            g.color = muted
            g.drawString(hint, leftX + (available - hintWidth) / 2, baseline)
            g.clip = clip
            g.font = font
        }

        private fun drawSlot(
            g: Graphics2D,
            metrics: FontMetrics,
            x: Int,
            glyph: String,
            baseline: Int,
            slotY: Int,
            slotHeight: Int,
        ) {
            g.color = muted
            g.stroke = BasicStroke(1f)
            g.drawRoundRect(x + 3, slotY, stepperSlotWidth - 6, slotHeight, 6, 6)
            g.color = text
            g.drawString(glyph, x + (stepperSlotWidth - metrics.stringWidth(glyph)) / 2, baseline)
        }

        private fun drawValueBox(
            g: Graphics2D,
            metrics: FontMetrics,
            x: Int,
            boxWidth: Int,
            baseline: Int,
            boxY: Int,
            boxHeight: Int,
        ) {

            g.color = menuFill(surface)
            g.fillRoundRect(x + 1, boxY, boxWidth - 2, boxHeight, 6, 6)

            val editing = editor
            val active = hovered || editing != null
            g.color = if (active) accent else muted
            g.stroke = BasicStroke(if (active) 1.5f else 1f)
            g.drawRoundRect(x + 1, boxY, boxWidth - 2, boxHeight, 6, 6)

            val value = if (editing != null) editing.buffer else "${readValue()}$unit"
            val textX = x + (boxWidth - metrics.stringWidth(value)) / 2

            if (editing != null && editing.hasSelection) {
                val before = value.substring(0, editing.selectionStart)
                val selected = value.substring(editing.selectionStart, editing.selectionEnd)
                g.color = menuFill(hover)
                g.fillRoundRect(
                    textX + metrics.stringWidth(before), boxY + 2,
                    metrics.stringWidth(selected), boxHeight - 4, 3, 3,
                )
            }
            g.color = text
            g.drawString(value, textX, baseline)

            if (editing != null && !editing.hasSelection && caretOn) {
                val before = value.substring(0, editing.caret)
                g.color = accent
                g.fillRect(textX + metrics.stringWidth(before), boxY + 2, 1, boxHeight - 4)
            }
        }

        private companion object {

            const val DEFAULT_VALUE_W = 54

        const val CARET_BLINK_MS = 500

        @Volatile
        private var activeStepper: MenuStepper? = null

        private val keyRouter = KeyEventDispatcher { event -> activeStepper?.onKey(event) ?: false }
            const val MAX_VALUE_W = 78

            const val ROW_W = 380

            const val MIN_RANGE_FONT_SIZE = 9f

        }
    }

    private class MenuItemUi(private val keepOpen: Boolean) : BasicMenuItemUI() {
        override fun installDefaults() {
            super.installDefaults()

            checkIcon = null

            arrowIcon = null
            selectionForeground = text
            disabledForeground = muted
            acceleratorForeground = muted
            acceleratorSelectionForeground = text
        }

        override fun paint(graphics: Graphics, component: JComponent) {
            super.paint(graphics, component)
            paintStateGlyph(graphics, menuItem)
        }

        override fun paintText(graphics: Graphics, menuItem: JMenuItem, textRect: Rectangle, text: String) {
            if (paintWrappedLabelLines(graphics, menuItem, textRect, text, disabledForeground, selectionForeground)) {
                return
            }
            super.paintText(graphics, menuItem, textRect, text)
        }

        override fun paintBackground(graphics: Graphics, item: JMenuItem, background: Color) {

            val model = item.model

            val submenuVisible = (item as? JMenu)?.isPopupMenuVisible == true

            val painted = shouldPaintMenuRowHighlight(
                model.isArmed, item is JMenu, model.isSelected,
                submenuVisible, pointerOverRow(item),
            )
            lastPaintedHighlight[item] = painted
            if (!painted) {

                val g2 = graphics.create() as Graphics2D
                g2.composite = AlphaComposite.Clear
                g2.fillRect(0, 0, item.width, item.height)
                g2.composite = AlphaComposite.SrcOver
                g2.color = menuFill(surface)
                g2.fillRect(0, 0, item.width, item.height)
                g2.dispose()
                return
            }
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g.color = menuFill(hover)

            g.fillRoundRect(3, 2, item.width - 6, item.height - 4, 7, 7)
            g.dispose()
        }

        override fun doClick(msm: MenuSelectionManager) {
            if (!keepOpen || !menuItem.isEnabled) {
                super.doClick(msm)
                return
            }
            menuItem.model.isArmed = false
            menuItem.model.isPressed = false
            when (menuItem) {
                is JCheckBoxMenuItem -> menuItem.isSelected = !menuItem.isSelected
                is JRadioButtonMenuItem -> menuItem.isSelected = true
            }
            val event = ActionEvent(menuItem, ActionEvent.ACTION_PERFORMED, menuItem.actionCommand)
            for (listener in menuItem.actionListeners) listener.actionPerformed(event)
        }
    }

    private class SeparatorUi : BasicSeparatorUI() {
        override fun paint(graphics: Graphics, component: JComponent) {

            graphics.color = menuFill(outline)
            val y = component.height / 2
            graphics.drawLine(rowStart, y, component.width - rowEnd, y)
        }
    }

    private class MenuGlyph : Icon {
        override fun getIconWidth() = glyphBox
        override fun getIconHeight() = glyphBox

        override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = accent
            when (component) {
                is JCheckBoxMenuItem -> if (component.isSelected) {
                    g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawLine(x + 3, y + 8, x + 7, y + 12)
                    g.drawLine(x + 7, y + 12, x + 14, y + 4)
                }

                is JRadioButtonMenuItem -> if (component.isSelected) {
                    g.fillOval(x + 4, y + 4, 8, 8)
                }
            }
            g.dispose()
        }
    }
}

data class MenuDecorationX(val checkX: Int?, val arrowX: Int?)

fun menuDecorationX(
    itemWidth: Int,
    labelEnd: Int,
    columnInset: Int,
    glyphWidth: Int,
    rangeGap: Int,
    hasCheck: Boolean,
    hasArrow: Boolean,
): MenuDecorationX {
    val right = itemWidth - columnInset
    if (!hasCheck && !hasArrow) return MenuDecorationX(checkX = null, arrowX = null)
    if (!hasCheck || !hasArrow) {

        val x = middleColumnGlyphX(itemWidth, labelEnd, columnInset, glyphWidth, rangeGap)
        return if (hasCheck) MenuDecorationX(checkX = x, arrowX = null)
        else MenuDecorationX(checkX = null, arrowX = x)
    }

    val arrowX = (right - glyphWidth).coerceAtLeast(0)
    val checkX = (arrowX - rangeGap - glyphWidth).coerceAtLeast(0)
    return MenuDecorationX(checkX = checkX, arrowX = arrowX)
}

fun menuDecorationY(rowTopY: Int, rowHeight: Int, glyphHeight: Int): Int =
    rowTopY + (rowHeight - glyphHeight) / 2
