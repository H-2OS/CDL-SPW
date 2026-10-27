// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.settings.SettingDescriptor
import dev.cdl.settings.SettingGroups
import dev.cdl.settings.SettingKind
import dev.cdl.settings.SettingRegistry

const val STEPPER_REPEAT_DELAY_MS = 400

const val STEPPER_REPEAT_INTERVAL_MS = 110L

const val STEPPER_ACCEL_STAGE1_MS = 1000L

const val STEPPER_ACCEL_STAGE2_MS = 2500L

const val STEPPER_ACCEL_FACTOR1 = 5

const val STEPPER_ACCEL_FACTOR2 = 20

fun stepperAccelerationFactor(elapsedMs: Long): Int = when {
    elapsedMs >= STEPPER_ACCEL_STAGE2_MS -> STEPPER_ACCEL_FACTOR2
    elapsedMs >= STEPPER_ACCEL_STAGE1_MS -> STEPPER_ACCEL_FACTOR1
    else -> 1
}

val MENU_COMPONENT_SECTIONS: List<String> = listOf(

    SettingGroups.LYRIC,
    SettingGroups.LYRIC_COLOR_OUTLINE,
    SettingGroups.ISLAND,
    SettingGroups.ISLAND_APPEARANCE,
    SettingGroups.RIGHT_SLOT,
    SettingGroups.SPECTRUM,
    SettingGroups.COVER,
    SettingGroups.CONTROLS,
    SettingGroups.ANIMATION,
    SettingGroups.MENU_SETTINGS,
)

fun menuSupportsKind(kind: SettingKind): Boolean = when (kind) {
    SettingKind.SWITCH, SettingKind.SEEKBAR, SettingKind.LIST -> true
    SettingKind.COLOR, SettingKind.FONT, SettingKind.EDITTEXT -> false
}

val NON_SETTING_KEYS: Set<String> = setOf(
    "window.position",
    "main_lyric.font.recent",
)

fun middleColumnGlyphX(
    itemWidth: Int,
    labelEnd: Int,
    columnInset: Int,
    glyphWidth: Int,
    rangeGap: Int,
): Int {
    val right = itemWidth - columnInset

    val left = (labelEnd + rangeGap).coerceAtMost(right - glyphWidth)
    val x = left + (right - left - glyphWidth) / 2

    return x.coerceAtLeast(0)
}

fun ellipsize(text: String, maxWidth: Int, widthOf: (String) -> Int): String {
    if (maxWidth <= 0) return ""
    if (widthOf(text) <= maxWidth) return text
    val ellipsis = "…"
    if (widthOf(ellipsis) > maxWidth) return ""
    var end = text.length
    while (end > 0) {

        if (text[end - 1].isHighSurrogate()) {
            end--
            continue
        }
        val candidate = text.substring(0, end) + ellipsis
        if (widthOf(candidate) <= maxWidth) return candidate
        end--
    }
    return ellipsis
}

data class StepperEditor(
    val buffer: String,
    val caret: Int,
    val anchor: Int,
) {

    val selectionStart: Int get() = minOf(caret, anchor)

    val selectionEnd: Int get() = maxOf(caret, anchor)

    val hasSelection: Boolean get() = caret != anchor
}

enum class StepperEditSignal {

    CONTINUE,

    COMMIT,

    CANCEL,
}

fun stepperEditorOpen(value: Int, selectAll: Boolean): StepperEditor {
    val text = value.toString()
    return if (selectAll) {
        StepperEditor(buffer = text, caret = text.length, anchor = 0)
    } else {
        StepperEditor(buffer = text, caret = text.length, anchor = text.length)
    }
}

fun stepperEditorType(editor: StepperEditor, typed: Char, allowNegative: Boolean): StepperEditor {
    if (typed in '0'..'9') return replaceSelection(editor, typed.toString())
    if (typed == '-' && allowNegative) return replaceSelection(editor, "-")
    return editor
}

fun stepperEditorPress(
    editor: StepperEditor,
    keyCode: Int,
    ctrlDown: Boolean,
): Pair<StepperEditSignal, StepperEditor> {
    val continueWith = { e: StepperEditor -> StepperEditSignal.CONTINUE to e }
    return when {

        keyCode == java.awt.event.KeyEvent.VK_A && ctrlDown ->
            continueWith(editor.copy(caret = editor.buffer.length, anchor = 0))

        keyCode == java.awt.event.KeyEvent.VK_ENTER ->
            StepperEditSignal.COMMIT to editor

        keyCode == java.awt.event.KeyEvent.VK_ESCAPE ->
            StepperEditSignal.CANCEL to editor

        keyCode == java.awt.event.KeyEvent.VK_LEFT -> {

            val target = if (editor.hasSelection) editor.selectionStart else (editor.caret - 1)
            continueWith(editor.copy(caret = target.coerceIn(0, editor.buffer.length), anchor = target.coerceIn(0, editor.buffer.length)))
        }

        keyCode == java.awt.event.KeyEvent.VK_RIGHT -> {
            val target = if (editor.hasSelection) editor.selectionEnd else (editor.caret + 1)
            continueWith(editor.copy(caret = target.coerceIn(0, editor.buffer.length), anchor = target.coerceIn(0, editor.buffer.length)))
        }

        keyCode == java.awt.event.KeyEvent.VK_HOME ->
            continueWith(editor.copy(caret = 0, anchor = 0))

        keyCode == java.awt.event.KeyEvent.VK_END ->
            continueWith(editor.copy(caret = editor.buffer.length, anchor = editor.buffer.length))

        keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE -> {

            if (editor.hasSelection) {
                continueWith(replaceSelection(editor, ""))
            } else if (editor.caret > 0) {
                val next = editor.buffer.removeRange(editor.caret - 1, editor.caret)
                continueWith(StepperEditor(next, editor.caret - 1, editor.caret - 1))
            } else {
                continueWith(editor)
            }
        }

        keyCode == java.awt.event.KeyEvent.VK_DELETE -> {
            if (editor.hasSelection) {
                continueWith(replaceSelection(editor, ""))
            } else if (editor.caret < editor.buffer.length) {
                val next = editor.buffer.removeRange(editor.caret, editor.caret + 1)
                continueWith(StepperEditor(next, editor.caret, editor.caret))
            } else {
                continueWith(editor)
            }
        }

        else -> continueWith(editor)
    }
}

fun stepperEditorCommitValue(buffer: String): Int? {
    val trimmed = buffer.trim()
    if (trimmed.isEmpty() || trimmed == "-") return null
    val parsed = trimmed.toDoubleOrNull() ?: return null
    if (!parsed.isFinite()) return null
    return kotlin.math.round(parsed).toInt()
}

private fun replaceSelection(editor: StepperEditor, replacement: String): StepperEditor {
    val start = editor.selectionStart
    val end = editor.selectionEnd
    val next = editor.buffer.substring(0, start) + replacement + editor.buffer.substring(end)
    val caret = start + replacement.length
    return StepperEditor(buffer = next, caret = caret, anchor = caret)
}

const val SUBMENU_OPEN_DELAY_KEY = "menu.submenu_open_delay_ms"

const val SUBMENU_CLOSE_DELAY_KEY = "menu.submenu_close_delay_ms"

const val SUBMENU_DELAY_MIN_MS = 0

const val SUBMENU_DELAY_MAX_MS = 1000

const val SUBMENU_OPEN_DELAY_FALLBACK_MS = 180

const val SUBMENU_CLOSE_DELAY_FALLBACK_MS = 250

const val ROOT_MENU_CLOSE_MIN_MS = 800

const val SUBMENU_HOVER_POLL_MS = 120

data class SubmenuDelays(val openMs: Int, val closeMs: Int) {
    companion object {

        val FALLBACK = SubmenuDelays(SUBMENU_OPEN_DELAY_FALLBACK_MS, SUBMENU_CLOSE_DELAY_FALLBACK_MS)
    }
}

fun coerceSubmenuDelayMs(raw: Int): Int = raw.coerceIn(SUBMENU_DELAY_MIN_MS, SUBMENU_DELAY_MAX_MS)

fun shouldOpenSubmenu(hoveredMs: Long, openDelayMs: Int): Boolean =
    hoveredMs >= coerceSubmenuDelayMs(openDelayMs).toLong()

fun collapseAction(outsideMs: Long, closeDelayMs: Int, pathDepth: Int): MenuCollapse {
    if (pathDepth < 1) return MenuCollapse.NONE
    val close = coerceSubmenuDelayMs(closeDelayMs)

    val effective = if (pathDepth == 1 && close > 0) maxOf(close, ROOT_MENU_CLOSE_MIN_MS) else close
    if (outsideMs < effective.toLong()) return MenuCollapse.NONE
    return if (pathDepth > 1) MenuCollapse.SUBMENUS else MenuCollapse.ALL
}

fun shouldPaintMenuRowHighlight(
    isArmed: Boolean,
    isMenu: Boolean,
    isSelected: Boolean,
    submenuVisible: Boolean,
    pointerOverRow: Boolean,
): Boolean = (isMenu && isSelected && submenuVisible) || (isArmed && pointerOverRow)

enum class MenuCollapse {

    NONE,

    SUBMENUS,

    ALL,
}

fun shouldCollapseSubmenu(outsideMs: Long, closeDelayMs: Int, pathDepth: Int): Boolean =
    collapseAction(outsideMs, closeDelayMs, pathDepth) == MenuCollapse.SUBMENUS

fun submenuDelayPairIsSane(openMs: Int, closeMs: Int): Boolean = closeMs > openMs

const val MENU_OPACITY_KEY = "menu.opacity_pct"

const val MENU_OPACITY_MIN_PCT = 30

const val MENU_OPACITY_MAX_PCT = 100

const val MENU_OPACITY_FALLBACK_PCT = 100

fun coerceMenuOpacityPct(raw: Int): Int = raw.coerceIn(MENU_OPACITY_MIN_PCT, MENU_OPACITY_MAX_PCT)

fun menuOpacityAlpha(pct: Int): Float = coerceMenuOpacityPct(pct) / 100f

fun menuSurfaceAlpha(pct: Int): Int {
    val p = coerceMenuOpacityPct(pct)
    return (p * 255 + 50) / 100
}

const val MENU_LABEL_MAX_LINES = 3

const val MENU_LABEL_EXTRA_LINE_PX = 18

private const val NO_LINE_START = "）】」』、，。；：？！·…%‰”’"

private const val NO_LINE_END = "（【「『“‘"

fun wrapMenuLabel(
    text: String,
    maxWidth: Int,
    maxLines: Int = MENU_LABEL_MAX_LINES,
    widthOf: (String) -> Int,
): List<String> {
    if (text.isEmpty()) return listOf("")
    if (maxWidth <= 0 || maxLines <= 1) return listOf(text)

    val lines = ArrayList<String>(maxLines)
    var i = 0
    val n = text.length
    while (i < n) {

        while (i < n && text[i] == ' ') i++
        if (i >= n) break
        if (lines.size == maxLines - 1) {

            lines.add(text.substring(i))
            break
        }

        var end = i
        var lastSpace = -1
        while (end < n) {
            val step = if (isSurrogatePairAt(text, end)) 2 else 1
            if (widthOf(text.substring(i, end + step)) > maxWidth) break
            if (text[end] == ' ') lastSpace = end
            end += step
        }
        if (end == i) {

            end = i + if (isSurrogatePairAt(text, i)) 2 else 1
        } else if (end < n && lastSpace > i + (end - i) / 2) {
            end = lastSpace
        }

        if (end < n) {

            while (end > i + 1 && text[end] in NO_LINE_START) end--

            while (end > i + 1 && text[end - 1] in NO_LINE_END) end--
        }
        lines.add(text.substring(i, end))
        i = end
    }
    return if (lines.isEmpty()) listOf("") else lines
}

private fun isSurrogatePairAt(text: String, index: Int): Boolean =
    index + 1 < text.length && text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()

fun menuRowHeightForLines(baseRowHeight: Int, lineCount: Int): Int =
    if (lineCount <= 1) baseRowHeight else baseRowHeight + (lineCount - 1) * MENU_LABEL_EXTRA_LINE_PX

enum class MenuDismiss {

    KEEP_OPEN,

    CLOSE,
}

enum class MenuItemRole(val dismiss: MenuDismiss) {

    ADJUST(MenuDismiss.KEEP_OPEN),

    NAVIGATE(MenuDismiss.CLOSE),

    ACTION(MenuDismiss.CLOSE),
}

fun menuDismissFor(role: MenuItemRole): MenuDismiss = role.dismiss

data class TopLevelMenuEntry(

    val title: String,

    val role: MenuItemRole,
)

const val MENU_TITLE_FEED_AUTHOR = "投喂作者"

const val MENU_TITLE_MORE_SETTINGS = "更多设置"

const val THANKS_WINDOW_TEXT = "谢谢！"

val SPONSOR_BODY_LINES: List<String> = listOf(
    "如果你喜欢这个桌面歌词岛，可以请我喝瓶水。",
    "完全自愿，不影响任何功能。",
    "当然，比起赞助，我更希望你能把它分享给同样喜欢它的人。",
)

const val SPONSOR_PAYPAL_LABEL = "通过 PayPal 支持："

const val SPONSOR_PAYPAL_LINK = "https://www.paypal.com/ncp/payment/3687MA8M4JHGG"

const val SPONSOR_QR_PATH = "/sponsor/donate_qr.png"

const val SPONSOR_PAYPAL_QR_PATH = "/sponsor/paypal_qr.png"

const val MENU_TITLE_SOURCE_REPO = "源仓库跳转"

const val PROJECT_URL_PROPERTY_KEY = "source.url"

const val PROJECT_URL_PLACEHOLDER_MARKER = "CHANGE-ME"

fun sourceUrlOrNull(): String? = runCatching {
    val properties = java.util.Properties()
    SourceUrlAnchor::class.java.getResourceAsStream("/project.properties")?.use(properties::load)
    properties.getProperty(PROJECT_URL_PROPERTY_KEY)?.takeIf { it.isNotBlank() }
}.getOrNull()

fun isPlaceholderSourceUrl(url: String?): Boolean =
    url.isNullOrBlank() || url.contains(PROJECT_URL_PLACEHOLDER_MARKER)

private object SourceUrlAnchor

const val MENU_TITLE_PRESET_LIST = "预设列表"

const val MENU_TITLE_CLICK_THROUGH = "鼠标穿透"

val TOP_LEVEL_REQUIRED_TITLES: List<String> = listOf(
    MENU_TITLE_CLICK_THROUGH,
    MENU_TITLE_FEED_AUTHOR,
    MENU_TITLE_SOURCE_REPO,
    MENU_TITLE_MORE_SETTINGS,
    MENU_TITLE_PRESET_LIST,
)

val TOP_LEVEL_MENU_ENTRIES: List<TopLevelMenuEntry> = listOf(

    TopLevelMenuEntry(MENU_TITLE_MORE_SETTINGS, MenuItemRole.NAVIGATE),

    TopLevelMenuEntry("显示词岛", MenuItemRole.ADJUST),

    TopLevelMenuEntry(MENU_TITLE_CLICK_THROUGH, MenuItemRole.ADJUST),

    TopLevelMenuEntry("锁定", MenuItemRole.ADJUST),

    TopLevelMenuEntry("启动时自动播放", MenuItemRole.ADJUST),

    TopLevelMenuEntry("导入字体包…", MenuItemRole.NAVIGATE),

    TopLevelMenuEntry(MENU_TITLE_PRESET_LIST, MenuItemRole.NAVIGATE),

    TopLevelMenuEntry("找回词岛", MenuItemRole.ACTION),
    TopLevelMenuEntry("关于与许可", MenuItemRole.ACTION),

    TopLevelMenuEntry(MENU_TITLE_SOURCE_REPO, MenuItemRole.ACTION),

    TopLevelMenuEntry(MENU_TITLE_FEED_AUTHOR, MenuItemRole.ACTION),
    TopLevelMenuEntry("退出", MenuItemRole.ACTION),
)

object ThanksWindowSkin {

    val background: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.bg

    val foreground: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.fg

    val border: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.line

    val muted: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.muted

    val linkFg: java.awt.Color
        get() {
            val base = dev.cdl.settingsui.ToolWindowPalette.fg
            val r = (base.red * 5 / 6).coerceIn(0, 255)
            val g = base.green
            val b = (base.blue * 2).coerceIn(0, 255)
            return java.awt.Color(r, g, b)
        }
}

fun buildMenuForInspection(): javax.swing.JPopupMenu =
    IslandPopupMenu.build(settings = null, window = null)

data class MenuSwitch(

    val key: String,

    val title: String,
)

val MENU_TOP_LEVEL_SWITCHES: List<MenuSwitch> = listOf(
    MenuSwitch(key = "island.enabled", title = "显示词岛"),

    MenuSwitch(key = "island.interactive", title = MENU_TITLE_CLICK_THROUGH),
    MenuSwitch(key = "island.lock_position", title = "锁定位置"),

    MenuSwitch(key = "playback.autoplay_on_start", title = "启动时自动播放"),
)

val MENU_UNIT_BY_KEY: Map<String, String> = mapOf(
    "layout.max_width" to "px",
    "layout.line_gap_px" to "px",
    "shape.corner_radius" to "%",
    "background.opacity" to "%",
    "controls.hitPaddingPx" to "px",
    "controls.gapPx" to "px",
    "lyric.offset_ms" to "ms",
    "controls.enterDelayMs" to "ms",
    "controls.exitDelayMs" to "ms",
    "controls.autoHideMs" to "ms",

    SUBMENU_OPEN_DELAY_KEY to "ms",
    SUBMENU_CLOSE_DELAY_KEY to "ms",
)

const val MENU_FONT_FAMILY_KEY = "main_lyric.font.family"

enum class MenuExposure {

    COLOR_ENTRY,

    SWITCH,

    STEPPER,

    RADIO_LIST,

    FONT_RECENT,

    NOT_IN_MENU,
}

val MENU_COMPONENT_SUBMENUS: Set<String> = setOf(

    "词岛材质",
)

val MENU_SUB_SUBMENUS: Set<String> = setOf(
    "歌词描边",
    "右端插槽",
)

fun menuExposureFor(descriptor: SettingDescriptor): MenuExposure {

    if (descriptor.retired) return MenuExposure.NOT_IN_MENU

    SettingRegistry.colorGroupFor(descriptor.key)?.let { group ->
        return if (descriptor.key == group.keys.first()) {
            MenuExposure.COLOR_ENTRY
        } else {
            MenuExposure.NOT_IN_MENU
        }
    }
    return when (descriptor.kind) {
        SettingKind.SWITCH -> MenuExposure.SWITCH
        SettingKind.SEEKBAR -> MenuExposure.STEPPER
        SettingKind.LIST ->
            if (descriptor.options.isNullOrEmpty()) MenuExposure.FONT_RECENT else MenuExposure.RADIO_LIST

        SettingKind.COLOR, SettingKind.FONT, SettingKind.EDITTEXT -> MenuExposure.NOT_IN_MENU
    }
}

fun menuReachableKeys(): Set<String> =
    SettingRegistry.all.flatMap { descriptor ->
        val group = SettingRegistry.colorGroupFor(descriptor.key)
        when {

            group != null -> group.keys
            menuExposureFor(descriptor) != MenuExposure.NOT_IN_MENU -> listOf(descriptor.key)
            else -> emptyList()
        }
    }.toSet()

const val MENU_ITEM_KEY_PROPERTY = "cdl.menu.itemKeys"

fun menuItemsForComponent(component: String): List<SettingDescriptor> =
    SettingRegistry.byGroup[component].orEmpty()

        .filterNot { it.retired }
        .filter { menuExposureFor(it) != MenuExposure.NOT_IN_MENU }

fun menuItemTitleFor(descriptor: SettingDescriptor): String =
    SettingRegistry.colorGroupFor(descriptor.key)?.let { "${it.title}…" } ?: descriptor.title

fun menuItemVisibleTitle(item: javax.swing.JMenuItem): String {
    val text = item.text
    if (!text.isNullOrEmpty()) return text
    for (i in 0 until item.componentCount) {
        val child = item.getComponent(i)
        if (child is IslandMenuSkin.MenuStepper) return child.label
    }
    return ""
}
