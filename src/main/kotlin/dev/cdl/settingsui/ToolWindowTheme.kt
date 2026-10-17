// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.font.DEFAULT_FONT_FAMILY
import dev.cdl.platform.SystemTheme
import dev.cdl.settings.SettingRegistry
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 工具窗配色（**唯一来源**）。
 *
 * ## 为什么要有这个对象（2026-09-21，BUG-108）
 *
 * 在此之前，同一套"深浅主题色"在**三处各自写了一遍**：
 * · `ColorPickerDialog.applyModernLook()` 里的 `bg / fg / fieldBg / line / btnBg`；
 * · `ToolWindows.toolWindowBackground()` 里的 `bg`（KDoc 自己都写着"与前者逐字一致"）；
 * · `ToolWindows.MUTED`（`0x555555`）—— **这一处根本没跟主题**。
 *
 * 于是"取色窗跟随了主题、字体窗没跟随"这种**同族漏改**发生了：
 * 用户报的是取色窗，但字体选择器窗是同一类问题。
 *
 * > **纪律：同族实现必须穷尽排查。** 靠"记得也改另一个窗"是不行的 ——
 * > 故这里把配色收成**一处**，并让所有工具窗共用 [applyToolWindowTheme]。
 * > 门禁里另有 `tools/check_toolwindow_theme.py` 做**静态守卫**：
 * > `settingsui` 包内每个 `: JDialog(` 子类都必须调用 [applyToolWindowTheme]，
 * > 漏一个即红 —— **新加工具窗时忘不了**。
 */
object ToolWindowPalette {

    /** 当前是否浅色。**每次读取都重新问系统** —— 用户可能在窗口开着时切换主题。 */
    val isLight: Boolean get() = SystemTheme.isLight()

    /** 窗口与容器底色。 */
    val bg: Color get() = if (isLight) Color(0xFA, 0xFA, 0xFC) else Color(0x20, 0x20, 0x22)

    /** 正文前景色。 */
    val fg: Color get() = if (isLight) Color(0x1B, 0x1B, 0x1F) else Color(0xE8, 0xE8, 0xEA)

    /** 输入框底色。 */
    val fieldBg: Color get() = if (isLight) Color(0xFF, 0xFF, 0xFF) else Color(0x2A, 0x2A, 0x2E)

    /** 边线。 */
    val line: Color get() = if (isLight) Color(0xC8, 0xC8, 0xD0) else Color(0x4A, 0x4A, 0x52)

    /** 按钮底色。 */
    val btnBg: Color get() = if (isLight) Color(0xEC, 0xEC, 0xF0) else Color(0x33, 0x33, 0x38)

    /**
     * 按钮**悬停**底色。
     *
     * ⚠️ 2026-09-21 新增：自绘按钮 [ThemeButton] 需要自己给出悬停/按下的反馈 ——
     * 关掉 LAF 的填充之后，"有没有反馈"就全落在我们身上了。
     */
    val btnHoverBg: Color get() = if (isLight) Color(0xE2, 0xE2, 0xE8) else Color(0x3E, 0x3E, 0x45)

    /** 按钮**按下**底色（见 [btnHoverBg]）。 */
    val btnPressedBg: Color get() = if (isLight) Color(0xD2, 0xD2, 0xDA) else Color(0x49, 0x49, 0x52)

    /** 按钮**禁用**底色（见 [btnHoverBg]）。 */
    val btnDisabledBg: Color get() = if (isLight) Color(0xF2, 0xF2, 0xF5) else Color(0x2A, 0x2A, 0x2E)

    /** 禁用按钮的文字色 —— 比 [muted] 再淡一档，明确"不可点"。 */
    val btnDisabledFg: Color get() = if (isLight) Color(0xA8, 0xA8, 0xAE) else Color(0x6A, 0x6A, 0x70)

    /**
     * 次级文字（状态提示）。
     *
     * ⚠️ **原为固定值 `0x555555`，它本身就是"未跟随主题"的一处**：
     * 浅色底上可读，深色底（`0x202022`）上几乎糊成一片。
     * 现在按主题给两个值，浅色保持原观感（`0x555555`），深色提亮到 `0x9A9A9E`。
     */
    val muted: Color get() = if (isLight) Color(0x55, 0x55, 0x55) else Color(0x9A, 0x9A, 0x9E)
}

/**
 * **跟随系统深浅主题的按钮（自绘）** —— 见 [applyToolWindowTheme] 的「为什么按钮必须自绘」。
 *
 * ## 为什么不能只靠 `JButton.background`（BUG-109）
 *
 * 用户真机反馈：「**两个按键（「确定」「关闭」）不随主题变色**。」
 *
 * `applyToolWindowTheme` 里确实给每个 `AbstractButton` 设了 `background` / `foreground` /
 * `isOpaque = true` —— 但**这条路只在部分 LAF 上成立**：
 * · Metal（Java 默认跨平台 LAF）：`MetalButtonUI.update` 只在
 *   `background !is UIResource && isContentAreaFilled` 时才用你的底色，
 *   按下/禁用态另有自己的渐变 —— 时灵时不灵；
 * · **Windows LAF / 其他原生 LAF**：按钮由 LAF 按原生主题整块绘制，
 *   `background` **被完全忽略**（连警告都没有）。
 *
 * 于是"设了属性却不生效"这件事**没法靠再设一个属性解决** —— 只能把绘制权拿回来。
 *
 * ## 做法
 *
 * 关掉 LAF 的填充与描边（`isContentAreaFilled = false` / `isBorderPainted = false` /
 * `isOpaque = false`），在 [paintComponent] 里**先按主题填底 + 描边**，再调
 * `super.paintComponent` 让 LAF 只负责**画文字**（用我们设的 `foreground`）。
 *
 * ⚠️ 底色**每次绘制时现取** `ToolWindowPalette`（而不是构造时快照一份）——
 * 用户可能在窗口开着的时候切换系统深浅色主题，而本项目的取色窗是**长期存在**的。
 */
class ThemeButton(text: String) : javax.swing.JButton(text) {

    init {
        isFocusPainted = false
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isRolloverEnabled = true
        // 内边距自己给（LAF 的边框被关掉了，不设就会"文字贴着边"）。
        border = BorderFactory.createEmptyBorder(5, 14, 5, 14)
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
        )
        val bg = when {
            !isEnabled -> ToolWindowPalette.btnDisabledBg
            model.isPressed -> ToolWindowPalette.btnPressedBg
            model.isRollover -> ToolWindowPalette.btnHoverBg
            else -> ToolWindowPalette.btnBg
        }
        g2.color = bg
        g2.fillRect(0, 0, width, height)
        g2.color = ToolWindowPalette.line
        // `-1` 是为了让 1px 的线**落在组件内**（否则右边/下边被裁掉一半）。
        g2.drawRect(0, 0, width - 1, height - 1)
        // 文字交给 LAF（它只画文字，因为填充/描边都被关掉了）。
        // ⚠️ 只在**真的不同**时赋值 —— `foreground` 是 bound property，赋值会触发
        //    `firePropertyChange` → `repaint()`，在 `paintComponent` 里无条件赋值
        //    会变成"每帧再排一帧"的重绘自激。
        val want = if (isEnabled) ToolWindowPalette.fg else ToolWindowPalette.btnDisabledFg
        if (foreground != want) foreground = want
        super.paintComponent(g)
    }
}

/**
 * 把整棵组件树刷成"跟随系统深浅主题"的样子，并尝试同步**原生标题栏**。
 *
 * ## 这是工具窗主题的**唯一出口**
 *
 * 所有 `settingsui` 里的 `JDialog` 子类都必须在 `init` 末尾调用本函数
 * （`tools/check_toolwindow_theme.py` 静态守这条）。
 *
 * ## 标题栏为什么必须**可显示之后**再刷
 *
 * [dev.cdl.window.WindowInterop.setDarkTitleBar] 内部走
 * `hwndOf(window)`，而它**第一句就是 `if (!window.isDisplayable) return null`**
 * —— 未 `pack()` / 未显示时 peer 还没建立，取不到 HWND。
 *
 * 故本函数**在可显示时同步刷一次，并在 `windowOpened` 时再刷一次**：
 * 构造期那一次是尽力而为（可能无效），`windowOpened` 那一次才是**真正生效**的。
 * 这修掉了一个**静默失效**：原 `ColorPickerDialog` 只在 `pack()` **之前**调一次
 * `applyModernLook()` —— 那次调用里的标题栏同步**必然返回 false**，且**不报错**。
 *
 * ## 降级安全
 *
 * 拿不到 HWND（窗口未显示 / 非 Windows / 系统不支持）→ `setDarkTitleBar` 返回 false，
 * **静默降级**，标题栏保持系统默认色，功能不受影响。
 *
 * @param contentPane 窗口的内容面板（调用方已设好布局与子组件）。
 * @param rootPane 窗口的根面板（`JDialog.rootPane`）；传 `null` 则跳过。
 * @param fontProvider 返回基础字体，按各控件**原有字号**派生，
 *   保住"小字仍小、正文仍正"。
 *
 *   ⚠️ **2026-09-23：默认值从 `null` 改成 [toolWindowFontProvider]** —— 见下方
 *   「字体为什么改成"默认就有 provider"」。
 */
fun applyToolWindowTheme(
    contentPane: Container,
    rootPane: JRootPane? = null,
    fontProvider: (() -> Font)? = toolWindowFontProvider(),
) {
    val light = ToolWindowPalette.isLight
    val bg = ToolWindowPalette.bg
    val fg = ToolWindowPalette.fg
    val fieldBg = ToolWindowPalette.fieldBg
    val line = ToolWindowPalette.line
    val btnBg = ToolWindowPalette.btnBg

    // ── 字体：由 `fontProvider` 给出（2026-09-23 补完）──────────────────────
    //
    // ## 这一段的两条用户原话
    //
    // · 2026-09-19：「**自绘窗口使用当前使用的字体**」—— 想要的是"跟随用户在设置里
    //   选的那个族名"。
    // · 2026-09-22：「**自绘窗口的字体显示能不能使用自带的 Mi sans 字体?**」——
    //   紧接着用户又拍板「**取消自带字体包，调用自带字体包的路线转到在本机选择的字体上**」。
    // · 2026-09-23：「**也还没随所选字体**，请你自查。」
    //
    // 于是 09-22 那条诉求（用随包 MiSans）**已随随包字体一起作废**；
    // 09-19 / 09-23 那两条（跟随当前字体）**仍然成立** —— 它们才是这里要兑现的目标。
    //
    // ## 字体为什么改成"默认就有 provider"（2026-09-23）
    //
    // ⚠️ 09-22 那次**只兑现了一半**，本文件的旧注释自己就写着：
    //    「`fontProvider` 虽然早就存在（`ColorPickerDialog` 的构造参数），
    //     **至今没有任何调用方真的传过值**（`grep fontProvider` 全树只有声明与转发）」
    //    ⇒ 于是 `fontProvider` 恒为 `null` ⇒ 每个自绘窗都回退**出厂默认族**
    //    ⇒ 用户 09-23 再次报「**也还没随所选字体**」。
    //
    // 这就是本项目反复记下的那条纪律的反面教材：
    //    > **靠"记得在每个调用点接线"是不行的。**
    // 故这次不靠纪律，靠**结构**：`fontProvider` 的**默认值**就是
    // [toolWindowFontProvider]（读 `main_lyric.font.family`），
    // 于是"忘了传"在结构上不可达 —— 连**本任务范围外**的那个调用点
    // （`window/IslandPopupMenu.kt` 的「投喂作者」窗）也一并正确。
    //
    // `settingsui` 的 5 个调用点**另外**各自显式传一遍 [toolWindowFontProvider]：
    // 值相同（幂等），作用是让"这个窗的字体来自设置"在**代码里看得见**，
    // 而不是只靠一个默认值。由探针 `ToolWindowCoverageProbe` §4 机械核对。
    //
    // ## 为什么 `null` 的语义仍是"用默认族"而不是"别动字体"
    //
    // `null` 若不在这里给一个值，自绘窗口就一直是 Swing 默认字体，
    // 那正是用户抱怨的"没生效"。故 `null` = 用**出厂默认族**
    // （[DEFAULT_FONT_FAMILY]，与注册表 `main_lyric.font.family` 的默认值同一个字面量，
    // 由探针 `FontDefaultProbe` 机械比对）。
    //
    // ⚠️ **代价（如实记）**：`null` 不再表示"别动字体、用 Swing 默认"。
    //    若将来真需要那个语义，应**新加一个显式的 `FontPolicy` 参数**。
    //
    // ⚠️ **2026-09-22 之前这里读的是随包 MiSans（首次会读一次 7.7 MB 的 TTF）——
    //    那句说明已随随包字体一并作废**。现在只是一次 `Font(family, …)` 构造，
    //    不打开任何文件，没有首帧额外开销。
    //
    // ⚠️ **字号与样式沿用控件原有的**（见下面 `style()` 里的 `deriveFont(cur.size)`）——
    //    **只换族、不换字号**，否则会毁掉窗口里"标题大、说明小"的层级。
    val baseFont = fontProvider?.invoke()
        ?: Font(
            DEFAULT_FONT_FAMILY,
            Font.PLAIN,
            // 平台默认标签字号 —— 它只对"自身没有字体的控件"生效（其余走 `deriveFont(cur.size)`）。
            UIManager.getFont("Label.font")?.size ?: 12,
        )

    fun style(c: Component) {
        // 字体：按控件**原有字号**派生，保住"小字仍小、正文仍正"。
        if (baseFont != null) {
            val cur = c.font
            c.font = if (cur != null) baseFont.deriveFont(cur.size.toFloat()) else baseFont
        }
        when (c) {
            // ★ 2026-09-21（BUG-109）：**自绘按钮必须排在 `AbstractButton` 之前** ——
            //   `when` 按顺序匹配，落到下面那条就会被套上 LAF 的 `background`/`border`，
            //   而它根本不读这两个属性（底色/边框由 `paintComponent` 按主题现取）。
            is ThemeButton -> c.foreground = fg
            is AbstractButton -> {
                c.isFocusPainted = false
                c.isOpaque = true
                c.background = btnBg
                c.foreground = fg
                c.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line),
                    BorderFactory.createEmptyBorder(5, 14, 5, 14),
                )
            }
            is JTextField -> {
                // ⚠️ 2026-09-22：**前景色无条件设**（浅色/深色主题下都得可读），
                //    但**底色与边框只给可编辑的文本框**。
                //
                // ## 为什么要加这个条件
                //
                // 全 `settingsui/` 里**只有一个**只读文本框：
                // `SampleLyricCredit.sampleLyricCreditField()`（示例歌词的出处与作者那一行）。
                // 用户对它的要求是"像一句**说明文字**"（示例词岛下方居中、可选中复制），
                // 而 `JTextField` 的默认外观是**白底输入框** —— 深色主题下尤其突兀。
                // 那一行自己设了 `isOpaque = false` + `border = null`，
                // 若这里**无条件**重设 `background` / `border`，那些设置会被打回原形。
                //
                // ## 为什么改这里是安全的
                //
                // `grep -rn "isEditable = false" src/main/kotlin/dev/cdl/settingsui/`
                // **只命中那一处** ⇒ 其余所有 `JTextField`（含 `ToolWindowThemeProbe` §②.3
                // 造的那个）都是可编辑的，走的仍是原路径、逐像素不变。
                c.foreground = fg
                if (c.isEditable) {
                    c.background = fieldBg
                    // 细边线替掉 Metal 的凹槽边框（"陈旧感"的一大来源）。
                    c.border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(line),
                        BorderFactory.createEmptyBorder(3, 6, 3, 6),
                    )
                }
            }
            is JLabel -> c.foreground = fg
            // ★ **普通容器面板也要上底色**。
            //
            // ## 原实现与它造成的现象
            //
            // 原先这里是 `else -> Unit`，于是 `JPanel` 落进"什么都不做"。
            // 而 `JPanel` **默认不透明**，底色来自 LAF（Metal 是浅灰）——
            // 结果：窗口壳、外框、按钮区都跟着主题变了，
            // 而**占据最大面积的那些容器面板仍是浅灰**。
            // 用户原话：「自绘取色窗口**大部分面积**仍不随 windows 深浅主题」。
            //
            // ## 为什么给容器设底色**不会**盖掉自绘面板
            //
            // 色域板 / 色相条 / 明度条 / 示例预览这几个自绘组件的
            // `paintComponent` 都**先把自己整块区域填满**（填的正是"父容器背景"），
            // 随后才画内容 —— 故这里设 `background` 对它们**只有一处收益**：
            // 明度条四个圆角与上下留白（胶囊覆盖不到的地方）终于有了
            // **与主题一致**的底色，而不是残留的 LAF 灰。
            is JPanel -> {
                c.isOpaque = true
                c.background = bg
                // ⚠️ 2026-09-23：**面板也要设前景色**。
                //
                // 原先只设 `background`，`foreground` 留着 LAF 的 `Panel.foreground`
                // （本机 Metal 实测 `0x333333`）—— 容器本身不画字，所以肉眼看不出来，
                // 但它是**继承源**：任何没有自己前景色的子控件都会拿到"深色主题下的
                // 深色字"（= 用户说的"白色部分"的反面：**看不见的字**）。
                // 由 `ToolWindowCoverageProbe` §1/§2 的对比度判据抓出来（深色下
                // 亮度差只有 0.074，判据要求 ≥ 0.25）。
                c.foreground = fg
            }
            // ══════════════════════════════════════════════════════════════════
            // ★ 2026-09-23：**滚动/列表家族** —— 用户「自绘窗口**仍有白色部分**」的主体
            // ══════════════════════════════════════════════════════════════════
            //
            // ## 为什么这四类此前是"白块"
            //
            // 旧实现的 `when` 只有 5 个分支（ThemeButton / AbstractButton /
            // JTextField / JLabel / JPanel），其余一律 `else -> Unit`。
            // 而**字体选择窗 / 字体来源窗 / 预设管理窗**里占据最大面积的那一块，
            // 恰恰是 `JScrollPane > JViewport > JList`（外加 `JScrollBar`）——
            // 它们的底色全是 LAF 的 `UIResource`（本机 Metal：`#EEEEEE` / `#FFFFFF`），
            // 深色主题下就是**整块亮板**。
            //
            // 实测（`ToolWindowCoverageProbe` 修复前基线）：
            //   `JList.background = FFFFFFFF` · `JScrollPane.background = FFEEEEEE`
            //   · `JViewport.background = FFEEEEEE` · `JScrollBar.background = FFEEEEEE`
            //   ⇒ 像素级：深色主题下滚动列表 **99.30% 的像素是亮的**。
            //
            // ## 逐类的取色（三类 token，不是随手填）
            //
            // | 控件 | background | foreground | border |
            // | --- | --- | --- | --- |
            // | `JScrollPane` | `bg`（窗底色） | `fg` | **不动** —— 调用方自己设了 `TitledBorder`（"系统中已安装的字体族"/"预设…"），覆盖它等于把标题吃掉 |
            // | `JViewport` | `fieldBg`（"内容区"底色，比窗底亮一档） | `fg` | 不动 |
            // | `JList` | `fieldBg` | `fg` | 不动（在带框的滚动区里再加一层线会显脏） |
            // | `JScrollBar` | `btnBg` | `fg` | 由 [ThemeScrollBarUI] 自绘（见那个类的 KDoc） |
            //
            // `JList` 用 `fieldBg` 而不是 `bg`：列表是"可读的内容区"，
            // 与输入框同类；而且 `DefaultListCellRenderer` 会**直接取
            // `list.getBackground()`** 当作未选中单元格的底色 ⇒ 这一条同时把
            // **单元格**也带上了主题（单元格是渲染器按需创建的，不在组件树里，
            // 递归刷不到它们）。
            is JScrollPane -> {
                c.isOpaque = true
                c.background = bg
                c.foreground = fg
                // 视口与两条滚动条**显式刷一遍**。
                // 实测（本机 Metal）：它们**确实**在 `JScrollPane.components` 里，
                // 故下面那行递归也会到 —— 这里显式再来一次是为了**不依赖这个事实**：
                // 滚动条是 `JScrollPane` 按 LAF 的 `createVerticalScrollBar()` 造的，
                // 不同 LAF 下"什么时候进组件树、进不进"并不由我们决定；
                // 靠 getter 取才是稳的（且 `is JScrollBar` 分支里"先换 UI、再设底色"
                // 的顺序也不能被别处的遍历打乱）。
                c.viewport?.let { style(it) }
                listOfNotNull(c.verticalScrollBar, c.horizontalScrollBar).forEach { style(it) }
            }
            is JViewport -> {
                c.isOpaque = true
                c.background = fieldBg
                c.foreground = fg
            }
            is JList<*> -> {
                c.isOpaque = true
                c.background = fieldBg
                c.foreground = fg
            }
            is JScrollBar -> {
                c.isOpaque = true
                c.background = btnBg
                c.foreground = fg
                // ⚠️ 必须**先**换 UI 再设 background：`BasicScrollBarUI.installUI`
                //    会 `LookAndFeel.installColorsAndFont(...)` 把 `background`
                //    重新刷成 `ScrollBar.background`（UIResource）—— 顺序反了就等于没设。
                if (c.ui !is ThemeScrollBarUI) c.ui = ThemeScrollBarUI()
            }
            // ⚠️ `JSlider` 的来源与上面四类不同，如实记：
            //    它出现在 `ColorPicker.kt` 的内联取色面板里，而那个面板**当前没有任何
            //    调用点**（`SettingsWindow` 已删除，见 `createColorSettingRow`）——
            //    即"包内存在、当前是死代码"。仍补上：它是**本包内的控件类型**，
            //    一旦被复活（把内联取色器接回某个窗），漏覆盖就是同一个缺陷。
            //    `JSlider` 默认 `isOpaque = true`（`BasicSliderUI.installUI` 设的），
            //    故深色主题下它的 `Slider.background`（`#EEEEEE`）同样是一块亮板。
            is JSlider -> {
                c.isOpaque = true
                c.background = bg
                c.foreground = fg
            }
            else -> Unit
        }
        if (c is Container) c.components.forEach { style(it) }
    }

    contentPane.background = bg
    style(contentPane)
    // ⚠️ 必须**最后**再设一次底色 —— `style` 会递归设 `foreground`，
    //    而自定义绘制的面板（色域板 / 预览块 / 示例歌词栏）**自己画**，
    //    不能被底色覆盖掉（它们 `isOpaque` 为 false 或自绘）。
    rootPane?.background = bg

    syncNativeTitleBar(contentPane, light)
}

/**
 * 尝试让**原生标题栏**跟随主题（走 DWM —— Swing 管不到它）。
 *
 * ⚠️ 单独抽出来是为了能在 `windowOpened` 时**再调一次**：peer 建立之前
 * `hwndOf` 取不到句柄，构造期那次多半无效（见 [applyToolWindowTheme] 的 KDoc）。
 *
 * 降级安全：拿不到 HWND / 系统不支持 → 返回 false，**静默**，功能不受影响。
 *
 * @return 是否真的设置成功。**探针用它断言"机制本身可用"**（而不是只看代码）。
 */
fun syncNativeTitleBar(contentPane: Container, dark: Boolean): Boolean = runCatching {
    val owner = SwingUtilities.getWindowAncestor(contentPane) ?: return@runCatching false
    dev.cdl.window.WindowInterop.setDarkTitleBar(owner, dark)
}.getOrDefault(false)

/**
 * **自绘窗口字体族的唯一来源**：读设置项 `main_lyric.font.family`（生效态 `live`）。
 *
 * ## 为什么需要它（2026-09-23，用户原话）
 *
 * > 「有部分自绘窗口仍有白色部分（不随主题），**也还没随所选字体**，请你自查。」
 *
 * 「没随所选字体」的根因**不是**这个函数不存在，而是**没人调用它**：
 * `applyToolWindowTheme` 的 `fontProvider` 一直是 `null`，
 * 于是每个自绘窗都回退到出厂默认族。修法见 [applyToolWindowTheme] 的
 * 「字体为什么改成"默认就有 provider"」——**不靠纪律、靠默认值**。
 *
 * ## 为什么是 `() -> Font` 而不是 `Font`
 *
 * 用户可能在窗口**开着的时候**去设置页换字体。做成函数 ⇒ 每次 `applyToolWindowTheme`
 * 调用都**现读**一次设置；做成值 ⇒ 拿到的是打开窗口那一刻的快照。
 * （与 `MenuStepper.readValue`、`ColorPickerDialog.fontProvider` 同一种"现读"约定。）
 *
 * ## 取的是 `live`（生效态）而不是 `saved`（落盘态）
 *
 * 与 `FontSourceDialog.show` / `IslandPopupMenu.currentText` / `ToolWindows` 的取值路径
 * **一致** —— 全项目读"当前值"一律读 `live`，这样"菜单里勾上的字体"与
 * "工具窗里显示的字体"不可能不一致。
 *
 * ## 降级安全（**每一层都有兜底，绝不抛异常**）
 *
 * · 设置系统未就绪（`SettingsRuntimeHolder.current == null`，如插件正在卸载）→ 出厂默认族；
 * · 注册表里没有这个键 → 出厂默认族；
 * · 值读到 `null` / 空串 / 全空白 → 出厂默认族（空串曾真机出现过，见
 *   `FontToolWindow.commit` 的说明）。
 *
 * ★★★ 2026-10-07 **更正（BUG-161）**：本段旧文写
 * 「字体解析失败时 `Font(family, …)` 会静默退化成 `Dialog`，而那是 AWT 的既定行为，
 *  **不是这里的职责**（`FontDefaultProbe` 另有一条断言钉住『出厂默认族在本机确实可解析』）」
 * ⇒ ★★ **那是一句【责任转嫁】，而它转嫁错了对象**：
 * `FontDefaultProbe` 守的是 **`DEFAULT_FONT_FAMILY`**，而**生产路径用的是设置值**
 * （出厂默认 = 哨兵 `@host:MiSans`）—— **两者不是同一个变量**
 * ⇒ ★ 那条断言**永远绿，而这里照样退化成 `Dialog`**（用户看到的四个工具窗字体全不对）。
 *
 * ★ 现做法：走**唯一漏斗** `font/FontResolution.resolveFontOrFallback` ——
 * 哨兵 / 空值解析失败 ⇒ **回退到 `DEFAULT_FONT_FAMILY`**，不再交给 AWT 退化。
 */
fun toolWindowFontProvider(): () -> Font = {
    val family = runCatching {
        val runtime = SettingsRuntimeHolder.current
        val descriptor = SettingRegistry.descriptor(FONT_FAMILY_KEY)
        if (runtime != null && descriptor != null) runtime.live.text(descriptor) else null
    }.getOrNull()
    // ★★★ 2026-10-07 **BUG-161**：改用**唯一漏斗** `resolveFontOrFallback`。
    //   ★ 原来这里**完全不认识哨兵** —— 直接把设置值（`@host:MiSans`）交给 `Font(...)`
    //     ⇒ **四个工具对话框（取色器 / 字体选择 / 字体来源 / 预设管理）的文字全退化成 `Dialog`**；
    //     而 `DEFAULT_FONT_FAMILY` 只覆盖「值为空」那一档 ⇒ 出厂默认下**从不生效**。
    //   ⚠️ 本函数上面的 KDoc 原写「不是这里的职责（`FontDefaultProbe` 另有一条断言钉住
    //     『出厂默认族在本机确实可解析』）」—— ★ **那句是【责任转嫁】**：
    //     `FontDefaultProbe` 守的是 `DEFAULT_FONT_FAMILY`，而**生产路径用的是设置值**（哨兵）
    //     ⇒ 两者**不是同一个变量** ⇒ 那条断言**永远绿，而这里照样退化**。已一并改正。
    dev.cdl.font.resolveFontOrFallback(
        family,
        // 只作"控件自身没有字体"时的兜底字号；其余走 `style()` 里的 `deriveFont(cur.size)`。
        UIManager.getFont("Label.font")?.size ?: 12,
    )
}

/**
 * **跟随主题的滚动条 UI** —— 见 [applyToolWindowTheme] 里 `is JScrollBar` 分支的说明。
 *
 * ## 为什么必须自绘（而不是设两个属性）
 *
 * 本机 LAF 实测是 **Metal**，而 `MetalScrollBarUI` 的**轨道、滑块与两端箭头**
 * 全部来自 `UIManager` 里的 `ScrollBar.gradient` / `ScrollBar.thumb` 等
 * `UIResource` 色 —— 它**不读** `JScrollBar.background`。
 * 于是"设了属性、画面没变"（与 [ThemeButton] 的 BUG-109 是**同一种失败**：
 * 属性设了、画面没变，而只看属性的断言会**恒绿**）。
 *
 * 用户原话「有部分自绘窗口**仍有白色部分**」里，字体列表 / 预设列表右侧那条
 * 浅灰滚动条就是其中一处 —— 修复前探针 §6 实测：
 * **深色主题下滚动列表 99.30% 的像素是亮的**（几乎整块浅色）。
 *
 * ## 做法
 *
 * 退到 [javax.swing.plaf.basic.BasicScrollBarUI]（它**读** `trackColor` / `thumbColor`
 * 这两个字段），在 [configureScrollBarColors] 里把两个字段按主题**现取**；
 * 并把两端箭头按钮压成 0 尺寸 —— 它们也是 `UIResource` 浅色，
 * 而 10 行的列表里用户靠滚轮 / 拖滑块就够了。
 *
 * ⚠️ 与 [ThemeButton] 同一条纪律：颜色**每次配置时现取** [ToolWindowPalette]，
 *    而不是构造时快照 —— 用户可能在窗口开着时切换系统深浅色。
 */
private class ThemeScrollBarUI : javax.swing.plaf.basic.BasicScrollBarUI() {

    override fun configureScrollBarColors() {
        // 轨道与滑块本体。
        trackColor = ToolWindowPalette.bg
        thumbColor = ToolWindowPalette.line
        // ⚠️ 下面四条**也必须覆盖**：`BasicScrollBarUI.paintThumb` 会拿
        //    `thumbHighlightColor` / `thumbLightShadowColor` / `thumbDarkShadowColor`
        //    描滑块的高光与阴影边，`trackHighlightColor` 描轨道高光 ——
        //    它们的默认值同样是 `UIManager` 里的 **UIResource 浅色**。
        //    只设前两条时，深色主题下滚动条上仍会留下**亮线**
        //    （由本探针 §6 的像素判据抓出来）。
        thumbHighlightColor = ToolWindowPalette.line
        thumbLightShadowColor = ToolWindowPalette.line
        thumbDarkShadowColor = ToolWindowPalette.bg
        trackHighlightColor = ToolWindowPalette.bg
    }

    override fun createDecreaseButton(orientation: Int): javax.swing.JButton = zeroButton()

    override fun createIncreaseButton(orientation: Int): javax.swing.JButton = zeroButton()

    private fun zeroButton(): javax.swing.JButton = javax.swing.JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
        isFocusable = false
    }
}
