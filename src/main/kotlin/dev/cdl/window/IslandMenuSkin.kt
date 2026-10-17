// SPDX-License-Identifier: AGPL-3.0-only
// Ported from 0.8.9 mod (H-2OS / 自宅警備員, commits 7bc0183/8c02841/3a347d5); see NOTICE.
//
// ⚠️ 本文件的外观数值（调色板 / 行高 / 内边距 / 分隔线高 / 悬停圆角条 / 状态字形与
// 子菜单箭头的几何与描边）**逐值来自原实现 GPL-3.0-only 的
// `ui/Windows11PopupStyle.kt`**（0.8.9 部署包内的 source zip，就地解包于
// `recon/up089-src/`）。GPL-3.0-only → GPL-3.0-only，许可兼容，
// 来源与改动点已记入 `NOTICE` 第 9 条。
//
// 本文件**不**包含原实现 `ui/AmllMotion.kt` 的任何内容 —— 后者是 AGPL-3.0-only，
// 与我们的 GPL-3.0-only 不兼容，**不得并入**。
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

/**
 * 右键菜单的 **Windows 11 风格外观** —— 自绘到 `JPopupMenu` 上。
 *
 * ## 为什么是「给 JPopupMenu 套皮肤」而不是「换成 0.8.9 的自绘菜单体系」
 *
 * 结论：**套皮肤**。理由三条，按权重排序：
 *
 * 1. **要复刻的东西 100% 都能套上去。** 原实现那套观感（`089:ui/Windows11PopupStyle.kt`）
 *    的实现手段只有五种：换 `PopupMenuUI` / 换 `MenuItemUI` / 换 `SeparatorUI` /
 *    设 `Border` 与 `preferredSize` / 挂 `PopupMenuListener`。**没有一种是自绘菜单体系独有的** ——
 *    原实现自己就是这么写在 `JPopupMenu` 上的（`089:ui/IslandMenu.kt:100` 的 `trayMenu()`
 *    返回的就是 `JPopupMenu`）。"自绘菜单体系"这个说法本身是个误会：
 *    0.8.9 用的是 **Swing 的菜单模型 + 自绘的 UI 委托**，不是另起一套菜单。
 * 2. **整体替换会丢掉三样我们已经在依赖的原生行为**：菜单的**屏幕边界自动翻转**、
 *    **键盘导航**（上下键 / 回车 / Esc，由 `MenuSelectionManager` 提供）、
 *    **高 DPI 缩放**。这三样在 `JPopupMenu` 上是免费的；换成自绘就要逐条重写，
 *    而"重写后漏掉某个边界情形"这类缺陷只在真机上、只在特定屏幕布局下才暴露。
 *    ⚠️ 2026-09-17 更正：第一条**不完全免费** —— 那个翻转是 `JMenu` **逐层**判定的，
 *    深菜单链会各层翻各层（第 3 层压住第 1 层）。现已由
 *    [decideChainDirection] + [DirectionalMenu] 整条链一次定方向接管，
 *    见 `recon/42-子菜单方向修复.md`。键盘导航与高 DPI 仍免费。
 * 3. **工作量差一个量级。** 套皮肤 = 本文件（**一个文件**，且逻辑照抄原实现已验证的实现）；
 *    整体替换 = 自建 layered 窗口 + 自己接管定位 / 层级 / 子菜单 / 键盘 / 外点关闭，
 *    即 `recon/08` §3.6.3 的方案 M-C，**原实现明确评估过并因代价放弃**
 *    （其 §3.6.3 结论：菜单窗口不是 layered，要拿圆角就得放弃 `JPopupMenu` 宿主，
 *    而"绕过它的代价远超收益"）。
 *
 * **唯一拿不到的是真圆角**（见 [PopupUi] 的注释）。这是 Swing popup 窗口不是
 * layered 窗口的硬约束，**套皮肤和整体替换之外还有第三条路才能解决**，
 * 与"要不要套皮肤"无关。
 *
 * ## 与既有的**有意偏离**（逐条给出理由，不是漏做）
 *
 * | 原实现做法 | 本实现 | 为什么 |
 * | --- | --- | --- |
 * | 勾选态画在**中间列**（`089:ui/Windows11PopupStyle.kt:353-361`） | **相同**（2026-09-16 由"行尾"回改） | 早期版本把它锚在行尾，理由是"我们没有步进器，中间列会让勾浮在行中间"——**该理由已作废**：用户要的是"外观与 0.8.9 一致"，而"右边看起来空"正是原实现菜单的真实观感（那片空位就是步进器的位置）。见 [paintStateGlyph] 的 KDoc。 |
 * | 子菜单箭头用 `accent` 色的 `›` 字符、中间列（`089:ui/Windows11PopupStyle.kt:518-521`） | **相同**（2026-09-16 由"自绘折线 + muted + 行尾"回改） | 同上。保留一处防御：字体不含 U+203A 时回退自绘折线，避免豆腐块。见 [PopupUi.paintSubmenuArrows] 的 KDoc。 |
 * | `MenuUi`（`BasicMenuUI` 子类）+ 子菜单折叠监视器（`:232-276`） | **只做"箭头"这一半** | 换 `MenuUi` 的收益是"能 `arrowIcon = null`"；但本插件**必须保留 `BasicMenuUI`**（悬停展开逻辑住在它里面），故箭头改用"底色盖掉 + 自绘"实现，效果等价。**子菜单折叠监视器不实现**：原实现那个 `startSubmenuWatcher` 是为"步进器行不触发选中变化导致子菜单挂着不走"服务的；本实现改用 [MenuStepper.syncMenuSelection]（原实现 `089:ui/StepperItem.kt:173-191` 的同一手法，只是挂在步进器自己身上而非全局轮询），**不需要额外的轮询线程**。 |
 * | `KEEP_OPEN_KEY` + 自定义 `doClick`（`:448-498`） | **已实现**（2026-09-17 补做） | 原记"不实现"，理由是"本插件的菜单项都是离散动作，点完就该关"。**该理由随菜单扩容而作废** —— 本轮菜单新增了 38 项设置（见 [dev.cdl.window.IslandPopupMenu]），其中「调整词岛设置」是一整棵设置树，**点一下就关会让"连续调几项"变成反复右键**。故按原实现同法实现：见 [keepOpen] 与 [MenuItemUi.doClick]。 |
 * | `rowStart` 等常量写成 `internal const` 供步进器共用 | 保持 `private` | 原实现把它们暴露出来是因为 `StepperItem` 要独立算同一组列坐标（`089:ui/Windows11PopupStyle.kt:51-64`，即 `recon/08` 的坑 P5）。本实现把步进器 [MenuStepper] 写在**同一个对象内部**，于是它直接读同一组 `private` 常量 —— 两处漂移在结构上不可能发生，比"暴露常量"更强。 |
 * | `rowHeightFor` 里 `availableHeight - menuPadding`（**只减一次**，`:166`） | `- menuPadding * 2` | ⚠️ **2026-09-24 更正：这不是"修正笔误"，是等价改写** ✓（原写「**有意修正**既有的一处笔误：`menuPadding = 10` 指的是上下各 5 的合计…原实现那个算式少减了一边」✗ —— **原实现没有笔误** ✓）。**取证**：原实现 `menuPadding = 10` 且减 `menuPadding`（= **10**）✓；本实现 `menuPadding = 5` 且减 `menuPadding * 2`（= **10**）✓ ⇒ **两边减掉的都是 10，数学等价** ✓。（勘察报告 W6-03 指出：原注释把"换了个写法"误说成"修正了既有的错" ✗ —— **这会让后人以为原实现有问题，从而去"再修一次"** ✗。）两者在本插件的行数下都会被 `coerceIn` 夹到 28，**实际不产生差异**，故按正确语义写。 |
 *
 * ## 视觉值全部照抄原实现（这些是"外观一致"的实质）
 *
 * 调色板、行高 `[24, 28]`、内边距 `EmptyBorder(5,5,5,5)`、分隔线高 9、
 * 悬停高亮圆角条 `fillRoundRect(3, 2, w-6, h-4, 7, 7)`、`margin = Insets(0,0,0,0)`、
 * 无前导图标列（标签从 `rowStart` 起）、字体族候选与字号阶梯、
 * **状态字形与子菜单箭头同处中间列**、最小宽度 `max(180, preferred.width + 12)`、
 * **方形直角 + 整矩形填充 + 1px 描边**、以及 `paintPopupBackground` 的"三件套"。
 *
 * ## 尺寸计算为什么要自算一遍（[displaySize]）
 *
 * 菜单的**边缘翻转**必须在 `show()` **之前**拿到尺寸，而 `show()` 之前菜单没有
 * 原生 peer。真机日志里 19 次
 * `IllegalComponentStateException: component must be showing on the screen to
 * determine its location` 正是这条路径抛出来的 —— 详见 [displaySize] 的 KDoc
 * 与 `recon/28`。
 */
internal object IslandMenuSkin {

    /** 行左边缘。所有标签都从这里开始（无前导图标列）。 */
    private const val rowStart = 8

    /** 行右边缘。 */
    private const val rowEnd = 10

    /** 状态字形（对勾 / 圆点 / 子菜单箭头）的边长。 */
    private const val glyphBox = 16

    /** 标签与状态字形之间的间距。 */
    private const val glyphGap = 6

    /**
     * 中间列（状态字形 / 子菜单箭头）的**右侧保留量**。
     *
     * ## 为什么是 `rangeGap + stepperSlotWidth + stepperGap + stepperSlotWidth`
     *
     * 原实现把状态字形画在"标签右缘 + [rangeGap]，到行右缘 - 这个值"这段区间的正中
     * （`089:ui/Windows11PopupStyle.kt:355-358`）。那段区间被既有的
     * `InlineStepper`（`− □ +` 三列）占着，所以它的右边界由三个槽位的宽度算出来：
     * `rangeGap(8) + stepperSlotWidth(26) + stepperGap(3) + stepperSlotWidth(26) = 63`。
     *
     * ⚠️ **本插件曾经没有步进器**（2026-09-17 前），当时 63 这个数只是"为了让中间列落在
     * 与原实现同一处的纯几何复刻"（用户要求"外观与 0.8.9 一致"，字形位置必须逐像素对得上）。
     * **现在 [MenuStepper] 真的会占这三个槽位了** —— 这个常量从"复刻"变成"真实约束"：
     * 若它与 [MenuStepper] 的槽位算法不一致，状态字形会**压在步进器上**。
     * 故 [MenuStepper] 直接复用本对象的这三个常量（见其 `minusX/valueX/plusX`），
     * 不另写一份数值。
     *
     * 原实现这三个常量见 `089:ui/Windows11PopupStyle.kt:57,61,64`；
     * 本文件按值复刻（GPL-3.0-only → GPL-3.0-only，来源与改动点见文件头注释与 NOTICE）。
     */
    private const val rangeGap = 8
    private const val stepperSlotWidth = 26
    private const val stepperGap = 3

    /** 中间列右边界相对行右缘的内缩量，见 [rangeGap] 的 KDoc。 */
    private const val middleColumnInset = rangeGap + stepperSlotWidth + stepperGap + stepperSlotWidth

    /** 默认行高。紧凑是刻意的 —— 让 6 项 + 3 条分隔线在 1080p 上远不到半屏。 */
    private const val defaultRowHeight = 28

    /** 工作区很矮时的行高下限。再矮就压到字体不可读了。 */
    private const val compactRowHeight = 24

    /** 菜单内边距（上下左右各 5）。 */
    private const val menuPadding = 5

    /** 分隔线行高。 */
    private const val separatorHeight = 9

    /** `settlePopup` 的第二次调用延迟 —— 见 [apply] 的注释。 */
    private const val SETTLE_DELAY_MS = 120

    // ⚠️ 原 `MENU_FADE_MS = 140` **已迁走**（2026-09-20）→ `Motions.MENU_FADE.durationMs`。
    //    动效参数只在动画包里出现一次（理由见 `Motions` 的 KDoc）。
    //
    //    **它刻意不跟随 `animation.duration_scale`** —— 菜单淡入走的是
    //    `transitionOf`（独立过渡），不带倍率。理由：菜单是"用完就走"的临时界面，
    //    时长若跟着倍率走，用户把倍率调到 200% 后菜单会显得迟钝，
    //    而那个设置的语义是"词岛上的动画节奏"。**两处的期望本来就不同。**

    /**
     * 淡入的推进间隔（毫秒）。取 16（≈60 fps）。
     *
     * 比主帧循环的 33 ms 快：菜单存活期只有几秒，这点 CPU 换来的平滑是值得的；
     * 且**不随 `animation.quality` 变**（同上，它不属于词岛的动画节奏）。
     */
    private const val MENU_FADE_TICK_MS = 16

    /** 一套系统主题下的调色板。数值取自 Windows 11 原生右键菜单。 */
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

    /**
     * 当前调色板。
     *
     * **每次 [apply] 都重读系统主题**（不缓存成常量）——
     * 菜单是**每次右键重建**的，重读的成本是一次注册表读取（微秒级），
     * 换来的是"用户在系统里切了深浅色，下一次右键就是新配色"。
     * 缓存的话用户必须重启插件才看得到变化，而"菜单还是旧配色"会被当成 bug 报上来。
     */
    private var lightMode = SystemTheme.isLight()
    private val palette: Palette get() = if (lightMode) lightPalette else darkPalette

    private val surface: Color get() = palette.surface
    private val outline: Color get() = palette.outline
    private val hover: Color get() = palette.hover
    private val text: Color get() = palette.text
    private val muted: Color get() = palette.muted
    private val accent: Color get() = palette.accent

    // ══════════════════════════════════════════════════════════════════════════
    // ★★★ 2026-10-15：菜单透明度的**新落点**（背景色 alpha，而非整窗合成）
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 用户报障（逐字）：「**右键菜单的透明度设置改为不对文字内容生效**」。
    //
    // ## 旧落点的问题
    //
    // 旧实现把 `menu.opacity_pct` 落到 `Window.setOpacity` —— 那是**整窗合成**：
    // 窗口里**每一个像素**（含文字、箭头、勾号）都乘上这个 alpha ⇒ 调低时
    // **文字跟着发灰** ✗
    //
    // ## 新落点（本段 + [PopupUi.paint]）
    //
    // 窗口**不做**整窗 alpha（静态恒 `opacity = 1.0`），只把**菜单底板**颜色的
    // alpha 按 `menu.opacity_pct` 算出来（`MenuPolicy.menuSurfaceAlpha`）⇒
    // ★ 文字像素仍是全不透明 ✓ —— 这正是用户要的"背景透、字清楚"。
    //
    // ## 前提（已实测，不是推断）
    //
    // 承载菜单的是 `javax.swing.Popup$HeavyWeightWindow`（`JWindow` 子类）。
    // 实测（Temurin 21.0.12.1 / Win11，2026-10-15，`_t_pixel.java`）：
    // ```
    // isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT) = true
    // 该窗口 setBackground(new Color(0,0,0,0)) → OK，getBackground().getAlpha() = 0
    // 该窗口 setBackground(new Color(rgb, 128)) → OK，getAlpha() = 128
    // setOpacity(0.85) 与逐像素透明**可共存**
    // ```
    // ⇒ ★ 逐像素 alpha 真的生效（不是被静默丢弃）✓
    //
    // ⚠️ **不支持的机器上自动退回旧行为**（整窗 alpha，文字会跟着淡）——
    //    `runCatching` 包住探测，失败即 `perPixelTranslucent = false`。
    //    这是"默认值要选安全侧"：新路失效时菜单仍**可用**（只是观感退回改动前）。

    /**
     * 本机是否支持**逐像素**窗口透明 —— 决定透明度落在"**背景色**"还是"**整窗**"。
     *
     * ★ `by lazy`：探测要问一次屏幕设备，而本 object 的每个菜单都会走到这里；
     *   设备能力在进程生命周期内不会变 ⇒ 只问一次。
     * ★ 整段 `runCatching`：无头环境 / 老驱动上 `getDefaultScreenDevice` 可能抛。
     */
    private val perPixelTranslucent: Boolean by lazy {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .isWindowTranslucencySupported(
                    GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT,
                )
        }.getOrDefault(false)
    }

    /**
     * 当前菜单**底板**的 alpha（`0..255`）。
     *
     * ★ 由 [applyMenuOpacity] / [refreshVisibleMenuOpacity] 写入（都在 EDT），
     *   由 [PopupUi.paint] 读取 ⇒ 用 `@Volatile` 是"便宜的保险"（读点在绘制链上）。
     * ★ **初值 255** = 完全不透明 = `menu.opacity_pct` 的默认值 100 ⇒ **开箱零变化** ✓
     */
    @Volatile
    private var surfaceAlpha: Int = 255

    /** 把百分比落到 [surfaceAlpha]（**唯一**写入口）。 */
    private fun setSurfaceAlpha(pct: Int) {
        surfaceAlpha = menuSurfaceAlpha(pct)
    }

    /**
     * ★★★ 2026-10-17（**BUG-217**）：**指针此刻是否真的落在这一行上** —— 唯一出口。
     *
     * ## 为什么需要它（用户第二次报障「**置灰恢复还是没能修复**」）
     *
     * ★★ `isArmed` / `isSelected` **都是状态**：`MenuSelectionManager` 只在
     *   "指针进入**另一行**"时才更新选中路径。★ 指针停在菜单里**但不在任何行上**
     *   （行间空隙 / 边框 / 分隔线）时，路径**保持不动** ⇒ 那一行一直是 armed ⇒
     *   **高亮永不消退** ✗
     *
     * ★★ 为什么不靠"`MOUSE_EXITED` 有没有送到"：**那是机制、不是事实**。
     *   实测（`_MenuExitProbe`）证明"dispatch 了就会清" —— 而那恰恰说明
     *   **"事件一定会到"这个前提没有被检验** ✓（本轮就栽在这上面）
     *
     * ## 取不到光标时**保守返回 `true`**
     *
     * `MouseInfo.getPointerInfo()` 只在无头 / 远程会话下返回 `null`；那时
     * **退回旧行为**（`isArmed` 说了算）比"高亮全灭"安全 —— 与
     * `pointerInsideMenuTree` 的保守方向**同一条** ✓
     */
    private fun pointerOverRow(row: JComponent): Boolean {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return true
        if (!row.isShowing) return false
        return Rectangle(row.locationOnScreen, row.size).contains(pointer)
    }

    /** 给一个颜色换上当前底板 alpha（只改 alpha，不动 RGB）。 */
    private fun withSurfaceAlpha(c: Color): Color = Color(c.red, c.green, c.blue, surfaceAlpha)

    /**
     * ★★★ 2026-10-15（**§3.74**）：菜单里**每一个背景填充**都必须走这里（**唯一出口**）。
     *
     * 用户报障（逐字）：「**右键菜单光标划过的设置项，在高亮后应该恢复透明度设置，
     * 但现在划过后变灰，不透明。**」
     *
     * ## 根因
     *
     * 底板走了 [withSurfaceAlpha]，而**悬停高亮条**（以及步进器数值框、框内选区、
     * 分隔线）直接用 `hover` / `surface` / `outline` 的**全不透明**色 ⇒
     * 在底板已经半透明的情况下，高亮条是一条**实心灰块** ✗
     * —— 与用户原话「**变灰，不透明**」逐字吻合。
     *
     * ★ **原记（2026-10-15 上一轮 KDoc）**：「文字 / 箭头 / 勾号 / **悬停高亮条**
     *   各有自己的绘制路径，用 `text` / `muted` / `accent` / `hover` 的**全不透明**颜色」
     *   —— ★ **实为**：那句话对**前景**（文字/箭头/勾号/光标/强调描边）成立，
     *   对**背景填充**（高亮条/数值框/选区/分隔线）**不成立** ⇒ 已按本条更正。
     *
     * ## 判据（本函数的全部意义）
     *
     * | 类别 | 例子 | 走本函数？ |
     * |---|---|---|
     * | **背景填充** | 底板 · 悬停高亮条 · 步进器数值框 · 框内选区 · 分隔线 | ✅ |
     * | **前景** | 文字 · 子菜单箭头 · 勾号/单选点 · 光标 · 强调描边 | ❌ 保持不透明 |
     *
     * ⚠️ **与底板同一条 `perPixelTranslucent` 闸**：逐像素透明不可用时窗口退回
     * **整窗 alpha**（文字会跟着淡），此时底板本就是**不透明**的 `surface` ——
     * 若高亮条反而半透，会透出下面那层不透明的底板，得到"更浅的实色"
     *（看起来像调错了颜色，而不是变透）✗ ⇒ 背景元素必须**整组同进同退** ✓
     *
     * ★ 探针/守卫：`check_steady_signal_wiring.py` 的 **F** 组（含本函数的唯一出口判据）。
     */
    private fun menuFill(c: Color): Color = if (perPixelTranslucent) withSurfaceAlpha(c) else c

    /** 全透明 —— 逐像素路下"窗口底色"用它，让底板只由 [PopupUi.paint] 一处定义。 */
    private val transparentColor = Color(0, 0, 0, 0)

    /** 已被挂过"显示后跟随移动"监听的 popup 窗口。用弱引用集合，避免长期持有已销毁的窗口。 */
    private val watchedWindows = Collections.newSetFromMap(WeakHashMap<Window, Boolean>())

    /**
     * 菜单字体。三级候选 + **可显示性校验**。
     *
     * 为什么要校验 `canDisplayUpTo`：候选里的 `Segoe UI Variable Text` / `Segoe UI`
     * 在中文系统上**存在但缺中文字形** —— 只用"字体族名存在"来挑，
     * 会挑到一个把中文画成豆腐块的字体。`canDisplayUpTo == -1` 表示整串都能显示。
     *
     * 采样串用**本菜单实际出现的全部汉字**（而不是原实现那句无关文案），
     * 这样"挑了字体"与"菜单能不能正常显示"是同一件事，不会出现采样通过、菜单里却有个字画不出来。
     */
    private val font: Font by lazy {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val sample = "显示词岛鼠标穿透打开设置关于与许可退出锁定位置"
        sequenceOf("Microsoft YaHei UI", "Segoe UI Variable Text", "Segoe UI")
            .filter { it in available }
            .map { Font(it, Font.PLAIN, 13) }
            .firstOrNull { it.canDisplayUpTo(sample) == -1 }
            ?: Font("Dialog", Font.PLAIN, 13)
    }

    /**
     * 按行高取字号：行越矮字越小，保证 24px 行里中文不被裁掉。
     *
     * ## ⚠️ 2026-09-18：整体下调约 0.5–1pt（用户诉求「缩小字号」）
     *
     * 用户原话：「子菜单中许多选项设置项名字**显示不全**，建议**缩小字号多行显示**…」。
     *
     * **原记**：`>= 32 → font(13)` / `>= 28 → 12f` / `else → 11f`。
     * **现为**：`>= 32 → 12.5f` / `>= 28 → 11.5f` / `else → 11f`。
     *
     * 为什么降：本注册表里最长的标题是 13 个汉字（「频谱高度（占歌词区百分比）」），
     * 12pt 下约 208px、11.5pt 下约 199px —— 降这一档本身不足以让它放下，
     * 真正的主力是 [MenuStepper.ROW_W] 的加宽（见那里的 KDoc）；
     * 字号只是"顺手把余量做大一点"，好让**将来**再长一两个字的标题也不用换行。
     *
     * 为什么不降更多：11pt 以下中文在 96 DPI 上开始糊（尤其是 `Microsoft YaHei UI`
     * 的细笔画），而菜单是**要一眼扫读**的控件。**宁可换行，不要把字压到看不清** ——
     * 换行由 [wrapMenuLabel] + [menuRowHeightForLines] 兜底。
     *
     * 步进器（[MenuStepper.useRowHeight]）用的是**同一个函数**，故两侧字号不可能漂移。
     */
    private fun fontFor(rowHeight: Int): Font = when {
        rowHeight >= 32 -> font.deriveFont(12.5f)
        rowHeight >= 28 -> font.deriveFont(11.5f)
        else -> font.deriveFont(11f)
    }

    /**
     * 让 [rowCount] 行 + [separators] 条分隔线塞进 [availableHeight] 所需的行高，
     * clamp 在 `[compactRowHeight, defaultRowHeight]`。
     *
     * 这是既有的巧思（`089:ui/Windows11PopupStyle.kt:165-168`）：菜单在矮屏上
     * **自动压扁**而不是被裁掉。本插件首版只有 6 行，实际永远取到 28（不触发压缩），
     * 但保留这段逻辑的成本是一次除法 —— 而删掉它，等菜单项变多时就会有人踩"矮屏下最后一项点不到"。
     */
    private fun rowHeightFor(
        rowCount: Int,
        separators: Int,
        availableHeight: Int = workAreaHeight(),
    ): Int {
        val usable = availableHeight - menuPadding * 2 - separators * separatorHeight
        return (usable / rowCount.coerceAtLeast(1)).coerceIn(compactRowHeight, defaultRowHeight)
    }

    /**
     * 指针所在屏幕的**工作区**高度（排除任务栏）。
     *
     * 用工作区而不是整屏高度：任务栏会盖住屏幕底部，按整屏算会让菜单把最后一行
     * 藏到任务栏后面（`recon/08` 的坑 P10 记录了同一个问题在 WPF 侧也出现过）。
     */
    private fun workAreaHeight(): Int {
        val device = MouseInfo.getPointerInfo()?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val bounds = device.defaultConfiguration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(device.defaultConfiguration)
        return bounds.height - insets.top - insets.bottom
    }

    /**
     * 给 [menu] 套上整套外观。**必须在 `menu.show(...)` 之前、且在 EDT 上调用。**
     *
     * ## 为什么这里要注册 `PopupMenuListener` 做两次 `settlePopup`
     *
     * `JPopupMenu` 显示后，Toolkit 仍会**再移动/缩放一次**它（把它 clamp 进屏幕）。
     * 只 settle 一次会按"移动前"的尺寸算工作区适配，结果就是菜单贴边时仍被任务栏遮住。
     * 故第一次用 `invokeLater`（尽量早），第二次用 120ms 后的单次 `Timer`
     * （等 Toolkit 动完）。原实现 `089:ui/Windows11PopupStyle.kt:195-198` 同此，是实测结论。
     *
     * ⚠️ **不要删掉第二次。** 它看起来冗余，实际是唯一能覆盖"Toolkit 之后又动了一次"的手段。
     *
     * ## [delays] 为什么是 `() -> SubmenuDelays` 而不是 `SubmenuDelays`
     *
     * 两个延迟值是**用户设置项**（`menu.submenu_open_delay_ms` / `..._close_delay_ms`），
     * 而这两项**就住在这棵菜单树里** —— 用户可能"打开菜单 → 把收起延迟调大 → 继续点"。
     * 传值会让本次弹出的后续行为停在旧值上（要再右键一次才生效）；
     * 传取值函数则每次判定都读当下的 live 值。**菜单已弹出时改设置立刻生效**，
     * 与 `MenuStepper.readValue` 的"每次重绘现读"是同一条纪律。
     *
     * ## 为什么是**两遍**遍历（2026-09-18 新增第二遍）
     *
     * 第一遍 [styleComponent] 只做"逐项自己的事"（字体 / 边框 / 配色 / 最小宽度），
     * 那时还不知道**本菜单最终有多宽**（宽度是各项最小宽度的最大值，要全部走完才知道）。
     * 而"标签要不要换行"取决于**可用宽度**，故必须有第二遍：
     *
     * 1. 第一遍：各项按**单行**算出自己的最小宽度；
     * 2. 取最大值 = 本菜单的内容宽度（= `displaySize` 用的同一个口径）；
     * 3. 第二遍：按该宽度决定各项是否换行，**只让需要换行的行**变高
     *    （见 [wrapLabelIfNeeded] 与 `MenuPolicy.menuRowHeightForLines`）。
     *
     * 这一遍**不能省**：第一遍里算出的宽度在"最宽的那一项"上恰好等于内容宽度，
     * 但其余项的实际宽度也是内容宽度（BoxLayout 会把每行拉满）——
     * 用"自己那一项"的宽度去判换行，会让窄项误判成"放不下"。
     *
     * @param delays 子菜单延迟的**当前值**提供者（默认兜底值，见 [SubmenuDelays.FALLBACK]）。
     * @param opacityPct 菜单不透明度的**当前值**提供者（百分比，见 `MenuPolicy.MENU_OPACITY_KEY`）。
     *   与 [delays] 同一条纪律：传取值函数而不是值 —— 用户可能"打开菜单 → 把透明度调低 →
     *   继续用"，且**下一次**右键必须立刻是新值。
     * @param fadeEnabled 弹出时是否做**淡入**（对应 `animation.enabled`）。
     *   同样传取值函数：用户可能在菜单已弹出时把动画关掉，那**下一次**右键就该是瞬时的。
     * @param isRoot 本 `menu` 是否是**整条菜单链的根**（即右键弹出来的那一个）。
     *   只有根负责启停 [hoverWatcher] —— 每个子菜单也走 [apply]，
     *   若不加这个开关就会有 N 个轮询定时器同时跑。
     */
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
        // 强制走 heavyweight popup。原实现 `089:ui/SwingTrayPopup.kt:42` 同此：
        // lightweight popup 被画在**宿主窗口内部**，会被窗口裁剪、且与子菜单的
        // 显隐生命周期绑在一起（关子菜单会连带卸载主菜单）。
        menu.isLightWeightPopupEnabled = false

        // ── 第一遍：逐项套样式（宽度按"单行自然宽"算） ──
        // ⚠️⚠️ `opacityPct` / `fadeEnabled` **必须一并下传** —— 子菜单是**另一个**
        //    `JPopupMenu`，它靠这一遍遍历里的递归 `apply` 才能拿到这两个取值函数。
        //    见 [styleComponent] 的 `@param opacityPct`（BUG-195 的根因就在这里）。
        menu.components.forEach { styleComponent(it, height, delays, opacityPct, fadeEnabled) }
        // ── 第二遍：按本菜单的**最终内容宽度**决定换行，只让需要换行的行变高 ──
        val contentWidth = contentWidthOf(menu)
        menu.components.forEach { wrapLabelIfNeeded(it, contentWidth, height) }

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {
                // ⚠️ 透明度必须在这里读**当下**的值（不是构造菜单那一刻的值）：
                // 用户在菜单里拖透明度步进器时，[refreshVisibleMenuOpacity] 会立刻
                // 改已经弹出的窗口；但**新**弹出的窗口要用最新值，故每次显示都重读。
                SwingUtilities.invokeLater { settlePopup(menu, opacityPct, fadeEnabled) }
                Timer(SETTLE_DELAY_MS) { settlePopup(menu, opacityPct, fadeEnabled) }
                    .apply { isRepeats = false }.start()
                // 收起延迟的轮询**只由根启动**：根菜单的可见期就是整棵菜单树的存活期
                // （子菜单都是它的后代 popup，根一关它们全没）。
                if (isRoot) startHoverWatcher(menu, delays)
            }

            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                forgetMenuFade(menu)
                if (isRoot) stopHoverWatcher()
            }

            override fun popupMenuCanceled(event: PopupMenuEvent) {
                forgetMenuFade(menu)
                if (isRoot) stopHoverWatcher()
            }
        })
    }

    // ── 菜单标签的换行与行高（2026-09-18）────────────────────────────────────
    //
    // 用户原话：「子菜单中**许多选项设置项名字显示不全**，建议**缩小字号多行显示**
    //   （但**不要侵占其他设置项**），**拉宽子菜单宽度**。」
    //
    // 折行规则（纯函数）在 `MenuPolicy.wrapMenuLabel`，这里只负责：
    // ① 算出"这一行能放多宽"；② 把折行结果挂到组件上；③ 按行数把**这一行**加高。
    // "不要侵占其他设置项"这条就落在 ③ 上 —— 单行的项拿到的仍是原行高。

    /**
     * 本菜单的**内容宽度**：各项最小宽度的最大值。
     *
     * 与 [displaySize] 用**同一个口径**（那里是 `width + menuPadding * 2`，这里只要内容部分）。
     * 之所以要单独抽一个函数：`displaySize` 是给"算菜单落位"用的（要含内边距），
     * 而这里要的是"每行实际能有多少像素"，两者差两倍内边距。
     */
    private fun contentWidthOf(menu: JPopupMenu): Int {
        var width = 0
        for (component in menu.components) {
            if (component is JSeparator) continue
            width = maxOf(width, itemPreferredWidth(component))
        }
        return if (width <= 0) minMenuWidth else width
    }

    /**
     * 需要时把 [component] 的标签折成多行，并按行数把**它自己**的行高加高。
     *
     * ## 可用宽度怎么算
     *
     * `内容宽度 - 本行自己的左右内缩`。左右内缩取自 `component.insets`，也就是
     * [styleItem] 设的那个 `EmptyBorder` —— 于是"标签可用到哪里"与
     * "Swing 自己画标签时能画到哪里"**是同一个数**（Swing 的
     * `MenuItemLayoutHelper` 也是从 `viewRect` 减 insets 得到文本矩形的）。
     * 用同一个口径，换行边界才与截断边界一致：能换行的地方就一定能画下。
     *
     * ## 只动这一行的高度
     *
     * `preferredSize` 的**宽度不动**（宽度是整条菜单的属性，单行改不了它），
     * 只改高度。故"多行项不会侵占相邻项的点击区域/视觉边界"。
     */
    private fun wrapLabelIfNeeded(component: Component, contentWidth: Int, baseRowHeight: Int) {
        val item = component as? JMenuItem ?: return
        // 步进器行：标签由 [MenuStepper] 自己画（它要按 `− 值 +` 的实际占位算可用宽度），
        // 行高也由它自己决定（见 [MenuStepper.useRowHeight]）。这里**不能**插手 ——
        // 否则会出现"JMenuItem 按文本算了一个高度、步进器按自己的标签算了另一个"，
        // 两个高度打架。本行没有文本（`text` 为空串），折行也无从折起。
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

    /**
     * 菜单标签折行结果的**承载键**（挂在 `JMenuItem` 的 client property 上）。
     *
     * 为什么用 client property 而不是给 [MenuItemUi] 加构造参数：
     * [MenuItemUi] 是在**第一遍** `styleComponent` 里创建的，而折行发生在**第二遍**
     * —— 构造参数在那一刻还没有值。client property 让"绘制"在真正画的时候现读，
     * 与 `MenuStepper.readValue` 的"每次重绘现读"是同一条纪律。
     *
     * ⚠️ 与 `KEEP_OPEN_KEY` 一样只在本文件内使用（不跨模块）。
     */
    private const val LABEL_LINES_KEY = "cdl.menu.labelLines"

    /** 折行结果（`null` = 未折行，按单行绘制）。 */
    private var JMenuItem.menuLabelLines: List<String>?
        get() = getClientProperty(LABEL_LINES_KEY) as? List<String>
        set(value) {
            putClientProperty(LABEL_LINES_KEY, value)
        }

    /**
     * 本行标签**实际会被画出来**的宽度（折行后取最宽的一行）。
     *
     * ## 为什么不能直接用 `stringWidth(item.text)`
     *
     * [middleColumnLeft]（状态字形与子菜单箭头的定位依据）原来用的是**整串**宽度。
     * 折行之后这个数会**远大于**实际画出来的宽度 —— 后果是中间列被推到行外：
     * `middleColumnGlyphX` 里 `left > right - glyphWidth`，字形被夹到最右，
     * 于是"勾号紧贴行右缘"，而 [PopupUi.paintSubmenuArrows] 那段
     * "用底色盖掉 L&F 箭头"的矩形宽度会算成 0 → **L&F 的浅色箭头重新露出来**。
     */
    private fun drawnLabelWidth(item: JMenuItem): Int {
        val metrics = item.getFontMetrics(item.font)
        val lines = item.menuLabelLines
        return if (lines == null || lines.size <= 1) {
            metrics.stringWidth(item.text.orEmpty())
        } else {
            lines.maxOf { metrics.stringWidth(it) }
        }
    }

    // ── 子菜单的展开 / 收起延迟（2026-09-18）─────────────────────────────────
    //
    // ## 这一节解决的是"**点不到子菜单里的项**"（用户原话："使内容显示完整"）
    //
    // 两条延迟各自对应的 JDK 行为（都写死为 0）见 `MenuPolicy.kt` 顶部的取证。
    // 落地方式：
    //  · **展开延迟** → [DelayedMenuUi]（`BasicMenuUI` 子类，只把"进入即展开"改成"进入后延迟展开"）；
    //  · **收起延迟** → [hoverWatcher]（主动轮询指针位置，离开菜单树够久才折叠子菜单）。
    //
    // ## 为什么展开用"逐行定时器"、收起用"全局轮询"
    //
    // 两者拿到的**输入不同**：
    //  · "指针进入了某个 JMenu 行"是**有事件的**（`mouseEntered`），且那一行自带
    //    `getPath()` 能算准选中路径 —— 用逐行 `Timer` 最准（不受轮询粒度影响）。
    //  · "指针离开了整棵菜单树"**不一定有事件**（见 [SUBMENU_HOVER_POLL_MS] 的 KDoc），
    //    只能主动采样。
    // 把展开也做成轮询会让延迟被量化到 120ms，白白牺牲手感。

    /**
     * 收起延迟的轮询器。`null` = 未运行。
     *
     * 只在根菜单可见期间运行（[apply] 的 `isRoot` 分支启停）——
     * 常驻会让插件在用户不做任何操作时也持续读 `MouseInfo`。
     */
    private var hoverWatcher: Timer? = null

    /**
     * ★★★★ 2026-10-16（**BUG-218 · 第四轮**）：被轮询器监视的**根菜单**。
     *
     * ★ 为什么需要它：`repaintStaleHighlightRows` 要枚举"当前可见的 popup"，
     *   而**不能只看 `MenuSelectionManager.selectedPath`** —— 路径为空/被清掉时
     *   一个 popup 都找不到 ⇒ 那时**恰恰最需要擦** ✗
     * ⇒ 根菜单在这里记一份（它由 [startHoverWatcher] 传入，随轮询器一同启停）✓
     */
    private var watchedRoot: JPopupMenu? = null

    /**
     * ★★★★★ 2026-10-16（**BUG-218 · 第四轮**）：**每一行上一次实际被画成了什么**
     * （`true` = 画了高亮条）。
     *
     * ## 为什么需要它 —— 前三轮（+本轮的第一次尝试）都栽在同一个思维定式上
     *
     * ★ 它们都在问「**这一行现在该不该高亮**」，然后据此决定要不要重绘。
     * ★★ 而"旧像素残留"的成因恰恰是**相反的**：
     *   **上一次画的时候该高亮、现在不该了，但没人重画它** ✗
     * ⇒ 只在"该高亮"的行上做重绘 ⇒ **永远碰不到那个残留行** ✗
     *
     * ## 正解：比对【实际画过的】与【现在该画的】
     *
     * · `paintBackground` 每次真的执行时，把**它的结论**记在这里；
     * · 轮询每 120 ms 扫一遍：**记录 ≠ 当前结论** ⇒ `repaint()` 它 ⇒ 收敛 ✓
     *
     * ★ 这条判据**不依赖任何 Swing 内部**（谁在路径里 / 谁被通知 / opaque 与否）——
     *   它只问"**屏幕上画着的**与**现在该画的**一致吗" ✓
     * ★ 用 `IdentityHashMap`（按**对象身份**比）—— 行是组件，不是值对象 ✓
     */
    private val lastPaintedHighlight = java.util.IdentityHashMap<JMenuItem, Boolean>()

    /**
     * 当前判据对 [row] 的结论 —— ★ **与两个 `paintBackground` 用的是同一个判据**
     * （同一问题只准有一个定义点：这里只负责**喂入参**，判断仍在 `shouldPaintMenuRowHighlight`）。
     */
    private fun highlightDecisionFor(row: JMenuItem): Boolean {
        val model = row.model
        return shouldPaintMenuRowHighlight(
            model.isArmed, row is JMenu, model.isSelected,
            (row as? JMenu)?.isPopupMenuVisible == true, pointerOverRow(row),
        )
    }

    /**
     * 指针"已在菜单树之外"的起始时刻（`System.currentTimeMillis()`）。`0` = 尚未在外面。
     *
     * 只由 [collapseAbandonedSubmenus] 在 EDT 上读写（`javax.swing.Timer` 的回调在 EDT）。
     */
    private var outsideSinceMs = 0L

    /** 启动收起轮询。重复调用（同一菜单的 `Visible` 事件理论上只来一次）是幂等的。 */
    private fun startHoverWatcher(menu: JPopupMenu, delays: () -> SubmenuDelays) {
        outsideSinceMs = 0L
        watchedRoot = menu
        hoverWatcher?.let { if (it.isRunning) return }
        val timer = Timer(SUBMENU_HOVER_POLL_MS) {
            // ★★★ 2026-10-17（**BUG-217**）：**先对账"哪一行 armed"与真实指针**，
            //   再判"要不要收菜单"。★ 顺序有意：对账是**局部**的（摘掉某一行），
            //   收起是**整链**的（收子菜单 / 关整条链）⇒ 先做小的、再做大的 ✓
            reconcileArmedRowWithPointer()
            // ★★★★ 2026-10-16（**BUG-218 · 第五轮**）：显式重绘分歧行。
            //   透明模式下 Swing RepaintManager 不清脏区 ⇒ 旧高亮像素留着。
            //   见 [repaintDivergentHighlightRows] 的 KDoc。
            repaintDivergentHighlightRows()
            collapseAbandonedSubmenus(coerceSubmenuDelayMs(delays().closeMs))
        }
        timer.isRepeats = true
        timer.start()
        hoverWatcher = timer
    }

    /** 停止收起轮询。菜单关闭时必须停 —— 否则是一个没人能停的 `javax.swing.Timer`。 */
    private fun stopHoverWatcher() {
        hoverWatcher?.stop()
        hoverWatcher = null
        watchedRoot = null
        // ★ 菜单已关 ⇒ 记的"上次画成什么"全部失效，必须清掉（否则无界增长，
        //   且下次开菜单时**旧行的记录**可能对上**新行**的引用 —— 虽然 IdentityHashMap
        //   不会误配，但留着纯属泄漏）✓
        lastPaintedHighlight.clear()
        outsideSinceMs = 0L
    }

    /**
     * ★★★ 2026-10-17（**BUG-217**）：把「被 armed 的那一行」与**真实指针位置**对齐。
     *
     * ## 为什么必须有这一步（而不是只改判据）
     *
     * ★ 判据（`shouldPaintMenuRowHighlight`）只决定"**下一次重绘**画不画高亮"；
     *   ★★ 而**没有人触发重绘**时，屏幕上那一块**旧像素会一直留着** ✗
     * ⇒ 必须**真的把状态改掉**（`setSelectedPath` ⇒ `menuSelectionChanged(false)`
     *   ⇒ `isArmed = false` ⇒ 该行 `repaint()`）✓
     *
     * ## ★★★★ 2026-10-16（**BUG-218 · 第三轮**）：**本函数曾漏掉 `JMenu` 行**
     *
     * 用户第三次报障（逐字）：「**划过置灰还是没修好**」。
     *
     * ★★ **前两轮（215 / 217）都只改了【判据】**，而本函数当时第一句是
     *   `if (row is JMenu) return` —— **明确跳过 `JMenu` 行** ⇒ 于是：
     *
     *   ① 划过 `JMenu` 行 M ⇒ 子菜单打开 ⇒ 判据为真 ⇒ 高亮**画出来了**
     *   ② 指针移开 ⇒ 子菜单收起 ⇒ 路径从 `[popup, M, …]` 缩回 `[popup, M]`
     *      ★★ M **在前后两个路径里都有** ⇒ `MenuSelectionManager` **不通知它**
     *        ⇒ **没有人 `repaint()` 它** ✗
     *   ③ 判据此刻为 **false**（`submenuVisible` = false、指针也不在行上）
     *      —— ★★ 但**判据只在重绘时被读** ⇒ 旧灰像素**永远留着** ✗✗
     *
     * ⇒ ★★★ **修法：按"子菜单是否真的开着"分，而不是按"是不是 `JMenu` 行"分。**
     *   · 子菜单**开着** ⇒ 保持高亮（**正确语义**，与指针无关）⇒ 不动 ✓
     *   · 子菜单**收着** ⇒ 与叶子行**同样处理**（摘掉 ⇒ 触发重绘）✓
     *
     * ## 本函数现在处理的两种行
     *
     * | 行类型 | 何时摘 | 为什么 |
     * |---|---|---|
     * | 叶子行（`JMenuItem`）| 指针不在它上面 | 没有子菜单 ⇒ "指针不在"就是**唯一**的正确结论 |
     * | `JMenu` 行 | 指针不在它上面 **且子菜单收着** | 子菜单开着时高亮是**正确语义**，不能摘 |
     *
     * ## 只在"指针还在**本行所在的同一个 popup** 里"时摘
     *
     * ★ 指针已经**离开本 popup** ⇒ 交给 [collapseAbandonedSubmenus]（它管"离开够久"，
     *   并带**收起延迟**）—— 两条判据**分工不重叠** ✓
     * ★ 若指针在**本行所属 JMenu 的子菜单**里 ⇒ 本行根本不会是路径末元素
     *   （路径末元素是子菜单里的项）⇒ 不会误伤 ✓
     */
    private fun reconcileArmedRowWithPointer() {
        val manager = MenuSelectionManager.defaultManager()
        val path = manager.selectedPath
        if (path.size < 2) return
        val row = path.last() as? JMenuItem ?: return
        // ★★★★ 2026-10-16（**BUG-218 · 第三轮**）：`JMenu` 行**不能一律跳过**。
        //
        // ## 用户第三次报障（逐字）
        //
        // > 「**划过置灰还是没修好**」
        //
        // ## ★★★ 前两轮都改了【判据】，但都没验过【谁触发重绘】
        //
        // `shouldPaintMenuRowHighlight` 只决定"**下一次重绘**画不画"。前两轮都靠
        // `setSelectedPath(...)` 改状态，并**假定**它会带来重绘 —— 对**叶子行**成立
        // （本探针 §D 实测：摘掉后 9 → 10 次重绘 ✓），但**对 `JMenu` 行不成立**：
        //
        //   ① 指针划过 `JMenu` 行 M ⇒ 路径 = [popup, M] ⇒ 子菜单（延迟后）打开
        //      ⇒ 路径 = [popup, M, submenu, …] ⇒ 判据为真 ⇒ 高亮**画出来了**
        //   ② 指针移开 ⇒ 子菜单收起 ⇒ 路径缩短回 [popup, M]
        //      ★★ 此时 M **在前后两个路径里都有** ⇒ `MenuSelectionManager`
        //        **不会**给它 `menuSelectionChanged` ⇒ **没有人 `repaint()` 它** ✗
        //   ③ 判据此刻算出来是 **false**（`submenuVisible` = false 且指针不在行上）
        //      —— ★★ 但**判据只在重绘时被读** ⇒ 旧灰像素**一直留着** ✗✗
        //
        // ⇒ 而本函数此前第一句就是 `if (row is JMenu) return`（**明确跳过**）
        //   ⇒ 连"对账"这条兜底也不覆盖它 ⇒ 三轮都修不掉。
        //
        // ## 修法：**按"子菜单是否真的开着"分**，而不是按"是不是 JMenu 行"分
        //
        //   · 子菜单**开着** ⇒ 高亮是**正确语义**（父行随子菜单亮，与指针无关）⇒ **不动** ✓
        //   · 子菜单**收着** ⇒ 该行不该再高亮 ⇒ 与叶子行**同样处理**（摘掉 ⇒ 触发重绘）✓
        //
        // ★ 指针**仍在这一行上**时不会走到下面（`contains` 那句会先 `return`）
        //   ⇒ 正常的"悬停 `JMenu` 行"不受影响 ✓
        if (row is JMenu && row.isPopupMenuVisible) return
        if (!row.isShowing) return
        val popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, row) as? JPopupMenu
            ?: return
        if (!popup.isShowing) return
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        if (Rectangle(row.locationOnScreen, row.size).contains(pointer)) return
        if (!Rectangle(popup.locationOnScreen, popup.size).contains(pointer)) {
            // ★★ 指针已**离开本行所在的 popup** —— 是否也立刻摘掉，**按层级分**：
            //   · ★ **根 popup 里的行**（`invoker` 不是 `JMenu`）⇒ **摘**：
            //     高亮应与指针**同步**；"离开够久才收菜单"是 [collapseAbandonedSubmenus]
            //     的事（用户本机 `submenu_close_delay_ms = 771` ⇒ 不摘的话高亮会赖 0.77 s）✓
            //   · ★★ **子菜单里的行**（`invoker` 是 `JMenu`）⇒ **不动**：
            //     摘掉子菜单项会把**子菜单本身**一起关掉（路径里少了那个 popup）
            //     —— 而"从子菜单移回父行"在原生菜单里**不该关子菜单** ✗
            if (popup.invoker is JMenu) return
            manager.setSelectedPath(path.copyOf(path.size - 1))
            return
        }
        // ★ 指针**在同一个 popup 里、但不在这一行上** ⇒ 高亮必须立刻消失
        manager.setSelectedPath(path.copyOf(path.size - 1))
    }

    /**
     * ★★★★★ 2026-10-16（**BUG-218 · 第四轮**）：把「**屏幕上画着的**与**现在该画的**」
     * **不一致**的行 `repaint()` —— ★★ 这是"残留像素"的**正解判据**。
     *
     * ## ★★★ 为什么前三轮（以及本轮第一次尝试）都没修掉
     *
     * 它们**全都在问同一个错问题**：「**这一行现在该不该高亮？**」
     * · 215 / 217 改的是**判据**（该不该画）；
     * · 218 第一版改的是**状态**（把 armed/selected 清掉，指望它顺带重绘）；
     * · 本轮第一次尝试改的是**重绘条件** —— 但条件仍写成
     *   「**该高亮的行**才重绘」 ⇒ ★★ **永远碰不到那个残留行** ✗
     *
     * ★★ 而残留的成因**恰恰相反**：
     *   **上一次画的时候该高亮、现在不该了，但没人重画它** ✗
     * ⇒ 必须在**"记录 ≠ 当前"**这个方向上找，而不是在"当前为真"里找 ✓
     *
     * ## 判据
     *
     * ```
     * for 每一行 in 可见 popup:
     *     recorded = 上一次 paintBackground 的结论（[lastPaintedHighlight]）
     *     current  = 现在判据的结论（[highlightDecisionFor]）
     *     if (recorded != current) row.repaint()      // ⇒ 重画时按 current 画 ⇒ 收敛 ✓
     * ```
     *
     * ★ 它**不依赖任何 Swing 内部**（谁在路径里 / 谁被通知 / popup 是否 opaque /
     *   `RepaintManager` 往上找到哪个祖先）—— 只问"**画着的**与**该画的**一致吗" ✓
     * ★ `recorded` 缺失（这行还没被画过）⇒ 跳过（屏幕上本来就没有它的像素）✓
     *
     * ## 开销
     *
     * 每 120 ms 扫"可见 popup 的行数"（≤ 30 行）+ 每行一次判据（几次字段读 + 一次
     * `MouseInfo` 读）⇒ **纳秒级**；且**只有真分歧的行**才 `repaint()`（通常 0 行）✓
     */
    private fun repaintDivergentHighlightRows() {
        for (popup in visiblePopups()) {
            for (i in 0 until popup.componentCount) {
                val row = popup.getComponent(i) as? JMenuItem ?: continue
                if (!row.isShowing) continue
                val recorded = lastPaintedHighlight[row] ?: continue
                if (recorded != highlightDecisionFor(row)) {
                    // ★★★★ 2026-10-16（**BUG-218 · 第五轮**）：重绘整个 popup。
                    //
                    // `paintBackground()` 里的两步绘制（Clear + SrcOver）负责真正清掉旧高亮；
                    // 这里只需要触发一次 `paint()` 调用。用 `popup.repaint()` 而不是
                    // `row.repaint()` —— 整个 popup 重绘确保 `PopupUi.paint()` 也参与，
                    // 底板色先画、行后画，顺序正确。
                    //
                    // ⚠️ 不用 `paintImmediately()` —— 它同步阻塞 EDT，会妨碍鼠标事件处理
                    // （探针实测：子菜单打不开、悬停状态丢失）。
                    popup.repaint()
                }
            }
        }
    }

    /** 当前**可见**的 popup —— 根菜单（[watchedRoot]）+ 选中路径里的各级子菜单。 */
    private fun visiblePopups(): List<JPopupMenu> {
        val out = ArrayList<JPopupMenu>(3)
        watchedRoot?.let { if (it.isShowing) out.add(it) }
        for (e in MenuSelectionManager.defaultManager().selectedPath) {
            if (e is JPopupMenu && e.isShowing && e !== watchedRoot) out.add(e)
        }
        return out
    }

    /**
     * 一轮收起判定。
     *
     * ## 为什么"先判在不在里面、再算时长"
     *
     * `outsideSinceMs` 记的是**连续**在外的起点：只要有一轮判定"在里面"就清零。
     * 于是"指针擦着菜单边缘来回"不会累积成"离开够久"——
     * 而累积式（只加不减）会让用户在菜单边缘抖动时子菜单突然消失。
     *
     * ## ★★★ 2026-10-15：动作**按深度分两种**（用户要求一级菜单也自动收起）
     *
     * 用户报障（逐字）：
     * > 「**右键菜单（第一级），同子菜单一样受收起延迟控制**（现在是**不会自行收起**的情况）」
     *
     * ★ **原记**：本函数在 `path.size <= 1` 时直接 `return`（连计时都不做）⇒
     *   根菜单**永不自动收起** ✗
     * ★ **实为**：那对"收子菜单"是对的（收完子菜单要**保留**根菜单），但把
     *   "根菜单本身该不该收"也一并否掉了 —— 用户把指针移开去干别的，
     *   菜单会**一直挂在屏幕上**。
     *
     * ⇒ 现在由 `MenuPolicy.collapseAction` 给出动作（**时间判据两种深度共用**）：
     *   · [MenuCollapse.SUBMENUS]（`pathDepth ≥ 2`）⇒ `setSelectedPath(arrayOf(path[0]))`
     *     —— 只保留根菜单，与原生 Windows 一致；
     *   · [MenuCollapse.ALL]（`pathDepth == 1`）⇒ `clearSelectedPath()`
     *     —— ★ 整条链关闭。**这与 `GlobalMenuDismisser`（点菜单外）是同一个动作，
     *     但触发条件不同**（那个是全局鼠标钩子的"按下"，这个是"指针离开够久"），
     *     两者**并列不冲突**：先到者生效，后者发现路径已空即自行清零。
     *
     * ⚠️ `outsideSinceMs` 的清零时机：**只有"路径为空"与"指针在里面"两种情况**
     *   才清零 —— `path.size == 1` **不再**清零（否则根菜单永远计不满时长）。
     *   动作执行后也要清零，否则下一次展开会被"上一轮的离开起点"立刻收掉。
     */
    private fun collapseAbandonedSubmenus(closeDelayMs: Int) {
        val manager = MenuSelectionManager.defaultManager()
        val path = manager.selectedPath
        if (path.isEmpty()) {
            // 菜单已经没了。**必须清零** —— 否则"上次离开的起点"会被留到
            // 下一次展开之后，让新展开的菜单立刻被收掉。
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

    /**
     * 指针是否落在**本进程任何一个菜单弹出窗**内。
     *
     * ⚠️ 取不到光标时返回 `true`（当作"在里面"）—— 这是**有意的保守方向**：
     * 误判成"在里面"只会让子菜单多留一会儿（用户可继续操作），
     * 误判成"在外面"会把用户正要点的子菜单收掉。
     * `MouseInfo` 只在无头/远程会话下返回 `null`，那时也不该冒险收菜单。
     */
    private fun pointerInsideMenuTree(): Boolean {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return true
        return Window.getWindows().any { window ->
            window.isShowing && isMenuPopupWindow(window) && window.bounds.contains(pointer)
        }
    }

    /**
     * 该窗口是否是**菜单弹出窗**（即内容树里含 `JPopupMenu`）。
     *
     * 为什么必须用"含不含 `JPopupMenu`"而不是"是不是我们的 owner 窗口"：
     * `IslandPopupMenu` 的 1×1 owner 窗口在菜单可见期间**也是 `isShowing`** 的，
     * 但它**不是**菜单 —— 若把它算进菜单树，指针擦过屏幕左上角（owner 被摆在菜单左下角处）
     * 就会被判成"在菜单里"，收起延迟永远不生效。
     * 与 `GlobalMenuDismisser.insideAnyPopup` / `IslandPopupMenu.containsPopup` 是**同一套语义**。
     */
    private fun isMenuPopupWindow(window: Window): Boolean {
        val content = (window as? RootPaneContainer)?.contentPane ?: return false
        return containsPopup(content)
    }

    /** 递归判定容器里是否含 `JPopupMenu` —— 用于区分"菜单弹出窗"与"别的窗口"。 */
    private fun containsPopup(container: Container): Boolean {
        if (container is JPopupMenu) return true
        for (child in container.components) {
            if (child is Container && containsPopup(child)) return true
        }
        return false
    }

    /**
     * **延迟展开的 `JMenu` UI 委托** —— 只改"悬停即展开"这一件事，其余全部沿用 JDK。
     *
     * ## 为什么是"子类 + 覆写 `createMouseInputListener`"而不是自己写一个 UI
     *
     * `BasicMenuUI` 里除了鼠标监听器，还有**键盘动作**（方向键 / 回车 / Esc 展开子菜单，
     * 走 `Actions.SELECT` + `setupPostTimer`）、`arrowIcon` 的默认画法、
     * `JMenu` 的 `MenuListener` 接线。自己写一个 `MenuUI` 就要把这些**全部重写一遍**，
     * 而"漏掉某一条"的表现是"键盘用不了了"这种只在特定操作下暴露的缺陷。
     *
     * 这里只覆写**一个工厂方法**，且**把它自己的实现包一层**
     * （`super.createMouseInputListener(c)` 返回的就是 JDK 的 `Handler`）——
     * 于是点击 / 拖拽 / 滚轮那些分支**逐字节还是 JDK 的**，不可能漂移。
     *
     * ## 与 `styleComponent` 那句"不要 `component.ui = ...`"的关系（2026-09-18 修订）
     *
     * 那条注释的本意是"**不要换成 `MenuItemUi`**"—— 换成 `MenuItemUi` 会丢掉
     * `BasicMenuUI` 的悬停展开逻辑（表现是"子菜单划过也不展开"）。
     * 换成**本类**（`BasicMenuUI` 的子类）**保留了 `BasicMenuUI`**，只是把它写死的
     * 0 延迟换成可调值 —— 本意仍然成立，故 `styleComponent` 的注释已同步改写。
     */
    private class DelayedMenuUi(
        private val openDelayMs: () -> Int,
        /** 与 [MenuItemUi.installDefaults] 同一来源的两个前景色（见该方法的 KDoc）。 */
        private val labelColor: () -> Color,
        private val mutedColor: () -> Color,
    ) : BasicMenuUI() {

        /**
         * ⚠️ **必须自己设这两个字段**（2026-09-18 真机崩溃后补）。
         *
         * `BasicMenuItemUI` 的 `disabledForeground` / `selectionForeground` 由
         * **基类的 `installDefaults()`** 从 `UIManager` 取：
         * `UIManager.getColor("MenuItem.disabledForeground")` —— 而**当前 L&F 下该键不存在**，
         * 于是字段保持 `null`。Kotlin 把它当 platform type（`Color!`），
         * **编译通过、paint 时才 NPE**：
         *
         * ```
         * java.lang.NullPointerException: disabledForeground must not be null
         *     at IslandMenuSkin$DelayedMenuUi.paintText(IslandMenuSkin.kt:668)
         * ```
         *
         * `MenuItemUi` 一直有这段（所以它不崩）；`DelayedMenuUi` 是后加的、
         * **只覆写了 `paintText` 却没覆写 `installDefaults`** —— 这就是漏的那一步。
         *
         * **为什么探针拦不住**：paint 路径在无头环境跑不到；848 项断言全绿，
         * 真机一开右键菜单就崩。**教训：覆写 `paintText` 时必须同时检查
         * "它依赖的 protected 字段由谁初始化"。**
         */
        override fun installDefaults() {
            super.installDefaults()
            selectionForeground = labelColor()
            disabledForeground = mutedColor()
            // 防御性补全（2026-09-18）：本插件的菜单项**不设 `accelerator`**，
            // 故 JDK 的 `paintText` 不会走"画快捷键"那条分支，这两个字段用不到。
            // 但它们与上面两个同源（都由 `installDefaults` 从 `UIManager` 取、
            // 都可能为 null）—— 一并设上，免得将来有人给菜单项加了快捷键，
            // 又在同一类"L&F 缺键"的环境下踩同一个 NPE。
            acceleratorForeground = mutedColor()
            acceleratorSelectionForeground = labelColor()
            // ⚠️ **清掉 L&F 的展开箭头**（2026-09-21 补，与 [MenuItemUi] 的
            //    `checkIcon = null` 完全对称）。
            //
            // **原记**（[styleComponent] 的 KDoc）：*"`arrowIcon` 不是 `JMenu` 的属性，
            //   故这里无法把它置空"* —— **这条是错的**。`arrowIcon` 是
            //   `BasicMenuItemUI` 的 `protected Icon` 字段（Temurin 21
            //   `plaf/basic/BasicMenuItemUI.java:153`），`DelayedMenuUi` 是
            //   `BasicMenuUI` 的子类 → **子类当然能写这个字段**。真正做不到的是
            //   "从 `JMenu` 实例上取到它"（`javax.swing.JMenu` 没有 arrow 相关成员），
            //   而那与"在子类里置空"是两件事。
            //
            // **为什么必须补这一行**：`BasicMenuItemUI.paintMenuItem` 的最后一步是
            //   `paintArrowIcon(...)`（`BasicMenuItemUI.java:700`），而
            //   `MenuItemLayoutHelper.useCheckAndArrow` 只对**菜单栏顶层 `JMenu`**
            //   返回 false（`sun/swing/MenuItemLayoutHelper.java:1118-1125`）——
            //   弹出菜单里的 `JMenu` **一律为 true**。于是 L&F 会在**每一行子菜单项的
            //   行尾**画一个浅色三角（`WindowsIconFactory.MenuArrowIcon.paintIcon`
            //   真的会画；`MenuItemArrowIcon` 才是空实现）。
            //   不置空的话，[PopupUi.paintSubmenuArrows] 画在**中间列**的那个 `›`
            //   会与它**并存** —— 即用户 2026-09-19 报的「有两个小箭头」。
            //
            // ⚠️ 2026-09-21 复核：当时是靠"用底色矩形盖掉它"来处理的，而**那不可能
            //   生效** —— 见 [PopupUi.paintSubmenuArrows] 的 KDoc（`paintComponent`
            //   在 `paintChildren` **之前**）。置空字段才是唯一有效的做法。
            //
            // 副作用：`MenuItemLayoutHelper` 会把 `arrowIconSize` 记为 0，右侧多出
            //   约 8px 可用宽度。**视觉无变化** —— 标签的绘制起点是 `textRect.x`
            //   （= 我们的 border 内缩），且 `paintText` 不按 `textRect.width` 裁剪。
            arrowIcon = null
        }

        override fun createMouseInputListener(c: JComponent): MouseInputListener =
            DelayedHoverListener(super.createMouseInputListener(c), openDelayMs, c)

        /**
         * 子菜单行的标签也走**多行绘制** —— 与 [MenuItemUi] 同一套逻辑。
         *
         * 为什么必须在这里也写一遍：`JMenu` 保留 `BasicMenuUI`（见 [styleComponent] 的
         * KDoc），它的 `paintText` 是 `BasicMenuItemUI` 的实现，**不经过** [MenuItemUi]。
         * 不覆写的话，子菜单行（如「调整词岛设置 ▸」）是全菜单里唯一还会被截断加 `…`
         * 的一类行 —— 而用户报的正是"名字显示不全"。
         *
         * 委托给同一个 [paintWrappedLabelLines]，故"什么时候换行、换几行、怎么居中"
         * 只有一处定义，两类行不可能漂移。
         */
        override fun paintText(g: Graphics, menuItem: JMenuItem, textRect: Rectangle, text: String) {
            if (paintWrappedLabelLines(g, menuItem, textRect, text, disabledForeground, selectionForeground)) {
                return
            }
            super.paintText(g, menuItem, textRect, text)
        }

        /**
         * ★★★ 2026-10-15（**用户报障**）：**本方法是"蓝色高亮不消退"的正解**。
         *
         * 用户逐字：「**被选中过的菜单背景变蓝不消退**（重开菜单蓝色消失）。」
         *
         * ## 为什么此前会变蓝
         *
         * 本类（`JMenu` 行的 UI）**只覆写了 `installDefaults` / `createMouseInputListener`
         * / `paintText`，漏了 `paintBackground`** ⇒ 那一行落到 L&F 默认实现
         * （`plaf/basic/BasicMenuItemUI.paintBackground`）：
         * ```java
         * if (model.isArmed() || (menuItem instanceof JMenu && model.isSelected())) {
         *     g.setColor(bgColor);          // bgColor = selectionBackground（Windows L&F 的蓝色）
         *     g.fillRect(0, 0, menuWidth, menuHeight);
         * }
         * ```
         * ⇒ ★ 用**蓝色**填满整行 ✗ —— 而 `MenuItemUi`（普通项）覆写了本方法，
         *   所以**只有带子菜单的那几行**会蓝 ✓ 与用户截图**逐字吻合**
         *   （变蓝的正是「频谱」与「更多设置」两个 `JMenu`）✓
         *
         * ## 为什么"重开菜单就消失"
         *
         * 菜单关闭前 `MenuSelectionManager.selectedPath` 仍指着那一行 ⇒ 只要重绘
         * 就还是蓝的；**重开菜单时路径重建** ⇒ 蓝消失 ✓（状态残留，不是绘制残留）
         *
         * ## 修法：与 [MenuItemUi] **共用同一个判据**
         *
         * 委托给公开纯函数 [shouldPaintMenuRowHighlight]（★ 该规则从此只有**一处定义** ——
         * 「两类行本该同规则却各写各的」正是本缺陷的**成因形态**）。
         * ⚠️ **不调 `super.paintBackground`**：调了就会把 L&F 的蓝色矩形画出来。
         */
        override fun paintBackground(g: Graphics, menuItem: JMenuItem, background: Color) {
            val model = menuItem.model
            // ★★★ 2026-10-16（**BUG-215**）：第 4 个实参 = **子菜单是否真的显示着** ——
            //   `JMenu` 行的 `isSelected` 由 `MenuSelectionManager` 的**选中路径**驱动，
            //   而路径是**状态**：指针离开后不会自动清 ⇒ 那一行"只要重绘就还是高亮" ✗
            //   （用户原话「**划过置灰不消失**」）⇒ 用**结构性真值**兜底 ✓
            val submenuVisible = (menuItem as? JMenu)?.isPopupMenuVisible == true
            // ★★★ 2026-10-17（**BUG-217**）：第 5 个实参 = **指针真的在这一行上吗** ——
            //   ★ `isArmed` 是**状态**（指针停在菜单里的空隙时不会更新）⇒ 必须用
            //     `MouseInfo` 的位置读数兜底，否则高亮**永不消退** ✗
            // ★★★★★ 2026-10-16（BUG-218 第四轮）：**记下这一次实际画成什么** ——
            //   轮询器靠它找出"上次画了、现在不该画、但没人重画"的残留行 ✓
            val painted = shouldPaintMenuRowHighlight(
                model.isArmed, menuItem is JMenu, model.isSelected,
                submenuVisible, pointerOverRow(menuItem),
            )
            lastPaintedHighlight[menuItem] = painted
            if (!painted) {
                // ★★★★ 2026-10-16（**BUG-218 · 第五轮**）：**两步绘制**（同 MenuItemUi）。
                //    先 Clear 清空旧高亮，再画底板色 ⇒ 透明模式下像素正确恢复。
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
            // ★★ 2026-10-15（**§3.74**）：走 [menuFill] 而不是裸 `hover` ——
            //   高亮条是**背景**，必须跟随菜单透明度（裸 `hover` 全不透明 ⇒ 实心灰块 ✗）。
            g2.color = menuFill(hover)
            // 与 [MenuItemUi.paintBackground] **逐像素同形**（内缩 3px / 上下 2px / 圆角 7px）——
            // ★ 两类行的高亮条必须长得一样，否则"JMenu 行"一眼就能看出是另一套 UI。
            g2.fillRoundRect(3, 2, menuItem.width - 6, menuItem.height - 4, 7, 7)
            g2.dispose()
        }
    }

    /**
     * 把 JDK 的悬停展开**改成延迟展开**，其余原样转发。
     *
     * ## 只拦两个方法（这是本类存在的全部理由）
     *
     * | 事件 | JDK 原行为 | 本类 |
     * | --- | --- | --- |
     * | `mouseEntered` | 立即选中本行、并按 `JMenu.delay` 起一个展开定时器 | **不转发**，改起一个 `openDelayMs` 的单次定时器 |
     * | `mouseExited` | `BasicMenuUI.Handler.mouseExited` 是**空实现** | 取消挂起的定时器，再转发 |
     * | 其余 5 个 | —— | **原样转发** |
     *
     * ## ★ 触发时**转发一个新建的 `MouseEvent`**（2026-09-18 修的回归，这是本类最重要的一段）
     *
     * ### 曾经的错法（用户报的"子菜单从悬停展开变成了必须点击"）
     *
     * 原实现在定时器到点后只做一件事：`setSelectedPath(getPath())`。
     * 而 `JMenu` 的展开在 JDK 里是**两阶段**的（见 `MenuPolicy.kt` 顶部那段实测）：
     *
     * ```
     * 阶段①  manager.setSelectedPath(getPath())            // 选中本行（高亮）
     * 阶段②  setupPostTimer → delay 后 appendPath(…, popup) // 把子菜单挂上路径 → 真正展开
     * ```
     *
     * 原实现**只兑现了阶段①**，且因为拦下了 `mouseEntered`，阶段②的定时器**从未被安排**。
     * 于是 `setSelectedPath` 把路径设成 `[根 popup, 本 JMenu]` —— 这一行的
     * `menuSelectionChanged(true)` 只调 `setSelected(true)`，**不会**让子菜单出现
     * （展开那一步住在 `JPopupMenu.menuSelectionChanged` 里，而子菜单不在路径上）。
     * **结果：子菜单永不展开，用户只能点击**（点击走 `mouseClicked` → 原样转发 → JDK 自己走完整两阶段）。
     *
     * ### 现做法：把整条两阶段逻辑**交回 JDK**
     *
     * 定时器到点后，**新建**一个 `MOUSE_ENTERED` 并转发给 `delegate.mouseEntered(...)`。
     * JDK 于是自己走 `getPath()` → `appendPath(…, popup)` → 子菜单展开。
     * 与"JDK 自己收到鼠标进入"**完全同一条代码路径**，不可能是"我们以为的展开"。
     *
     * 实测（Temurin 21.0.12.1 / Win11，heavyweight popup，`JMenu.delay = 0`）：
     * ```
     * dispatch MOUSE_ENTERED 后立刻   选中路径长度=3 [JPopupMenu JMenu JPopupMenu]  子菜单可见=true
     * ```
     *
     * ### 为什么必须**新建** `MouseEvent`，不能缓存收到的那个
     *
     * 类 KDoc（[DelayedHoverListener] 上方那段）已写明：AWT 会复用/失效 `MouseEvent`，
     * 跨定时器持有它是典型的"偶发怪行为"来源。这里连坐标都不读
     * （`BasicMenuUI.Handler.mouseEntered` 只用 `menuItem` / `isEnabled` / `isTopLevelMenu` / `getDelay`），
     * 故坐标给项内任意点 `(1,1)` 即可 —— 但仍然**新建**，因为"是否复用"是 AWT 的实现细节，
     * 不该由我们去赌。
     *
     * ### 与 [JMenu.delay] 的关系（**必须一起改，缺一不可**）
     *
     * `BasicMenuUI.installDefaults` 把 `JMenu.delay` 写死成 **200**
     * （`plaf/basic/BasicMenuUI.java:98`）。若不动它，转发之后子菜单还要再等 JDK 那 200ms
     * → 真实延迟 = 本设置项 + 200ms，且**本设置项根本不是延迟的来源**（一个"死设置"）。
     * 故 [styleComponent] 的 `JMenu` 分支把 `delay` 置 0：让 JDK 走
     * `appendPath` 那条**立即展开**的分支，本设置项成为延迟的唯一来源。
     *
     * ## `mouseExited` 里那个"取消"是承重的
     *
     * 没有它就会出现"**指针已经移走，子菜单却迟一步弹出来**"：
     * 定时器在 EDT 上排队，指针从 A 行移到 B 行后 A 的定时器仍会触发，
     * 于是路径被指回 A —— 用户看到菜单"跳"回上一个项。
     * AWT 保证"离开 A"的事件先于"进入 B"派发，故在这里取消是可靠的。
     * （JDK 自己的 `mouseExited` 是**空实现**，它不取消自己的 `setupPostTimer` 定时器 ——
     * 这也正是我们**不能**直接把延迟托付给 `JMenu.delay` 的原因之一。）
     *
     * ## 拖拽（按住鼠标键划过菜单）**不做延迟**
     *
     * 拖拽语义是"按住不放，划到哪就选到哪"，延迟会让拖拽变成断断续续。
     * 故按住任一鼠标键时直接转发给 JDK（`e.modifiersEx` 判按键，与 JDK 用
     * `e.getModifiers()` 判的是同一件事，只是前者不依赖已废弃 API）。
     *
     * @param source 本行组件（即那个 `JMenu`）。转发时要它做新 `MouseEvent` 的 source
     *   —— `MouseEvent` 的构造器**不接受 null source**，且 JDK 的处理器会用
     *   `e.getSource()` 取回 `menuItem`。
     */
    private class DelayedHoverListener(
        private val delegate: MouseInputListener,
        private val openDelayMs: () -> Int,
        private val source: JComponent,
    ) : MouseInputListener {

        /** 挂起的展开定时器。`null` = 没有挂起的展开。 */
        private var pending: Timer? = null

        /** 挂起定时器所基于的"进入时刻"与"当时读到的延迟"，用于触发时复核。 */
        private var enteredAtMs = 0L
        private var pendingDelayMs = 0

        private fun cancelPending() {
            pending?.stop()
            pending = null
        }

        override fun mouseEntered(e: MouseEvent) {
            val dragging = (e.modifiersEx and DRAG_BUTTON_MASK) != 0
            val delay = coerceSubmenuDelayMs(openDelayMs())
            // 拖拽中 / 延迟为 0 → 完全退回 JDK 的即时行为（"可调"的下限就是"原样"）。
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

        /**
         * 定时器到点。**再复核一次"确实悬停够久"**才展开。
         *
         * 为什么要有这道复核：定时器的实际触发时刻晚于设定值（EDT 忙时会晚得多），
         * 且延迟值是**可变的**（用户此刻正在菜单里拖着那根滑杆）。
         * 复核走 [shouldOpenSubmenu] 这个纯函数 —— 于是"多久算够"这条规则
         * **只有一处定义**，且能被探针穷举断言。
         *
         * ## ★ 展开动作 = 转发一个**新建**的 `MOUSE_ENTERED`（见类 KDoc 的长注）
         *
         * 交回 JDK 走它自己的两阶段逻辑，而不是我们自己 `setSelectedPath`。
         */
        private fun fireIfStillHovered() {
            pending = null
            val elapsed = System.currentTimeMillis() - enteredAtMs
            if (!shouldOpenSubmenu(elapsed, pendingDelayMs)) return
            // ⚠️ 选中路径为空时必须**放弃**，不能转发。
            //
            // 空路径下 JDK 的 `BasicMenuUI.appendPath(getPath(), popup)` 会走到
            // `if (path.length == 0) { setSelectedPath([elem]); return; }` 这一支 ——
            // 于是路径变成**只有子菜单**，根 popup 被移出路径 →
            // `JPopupMenu.menuSelectionChanged(false)` → `setVisible(false)`
            // → **整棵菜单被关掉**（真机实测：路径被清空后再 dispatch MOUSE_ENTERED，
            // 根菜单消失、连窗口都没了）。
            //
            // 空路径只可能出现在"菜单正在被关"的过程中（`clearSelectedPath` 之后），
            // 这时本来也不该展开任何东西。判据直接用 `MenuSelectionManager` 的
            // 现读路径，而不是 `getPath()` 的返回值 —— 后者是"把本项接上去"的结果，
            // 用它的**长度**反推"路径是否为空"要多绕一层（`getPath()` 在路径为空时
            // 返回空数组，两者恰好等价，但意图不如直读清楚）。
            if (MenuSelectionManager.defaultManager().selectedPath.isEmpty()) return
            delegate.mouseEntered(freshEnteredEvent())
        }

        /**
         * 造一个"刚刚进入本行"的 `MOUSE_ENTERED`。
         *
         * 坐标给 `(1, 1)`（项内任意点）：`BasicMenuUI.Handler.mouseEntered` 只读
         * `menuItem` / `isEnabled()` / `isTopLevelMenu()` / `getDelay()`，**不读坐标**
         * （Temurin 21 `plaf/basic/BasicMenuUI.java:562-598`）。
         * `modifiers = 0` + `NOBUTTON` 是 `MOUSE_ENTERED` 的规范取值
         * （进入事件不带按键），故 [mouseEntered] 里那条 `dragging` 判定
         * 也不会被这个事件误触发。
         */
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

        // 其余分支**原样转发** —— 见类 KDoc：点击 / 拖拽 / 移动的语义一个字都不改。
        override fun mouseClicked(e: MouseEvent) = delegate.mouseClicked(e)

        override fun mousePressed(e: MouseEvent) = delegate.mousePressed(e)

        override fun mouseReleased(e: MouseEvent) = delegate.mouseReleased(e)

        override fun mouseDragged(e: MouseEvent) = delegate.mouseDragged(e)

        override fun mouseMoved(e: MouseEvent) = delegate.mouseMoved(e)

        private companion object {
            /**
             * 三个鼠标键的按下掩码 —— 与 JDK 用
             * `BUTTON1_MASK|BUTTON2_MASK|BUTTON3_MASK` 判的是同一件事。
             *
             * 用 `val` 而非 `const val`：`or` 在 Kotlin 里是函数调用，不是常量表达式。
             */
            val DRAG_BUTTON_MASK =
                InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON2_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK
        }
    }

    /** 本菜单自己的行高：按它的行数与分隔线数算，不传外部高度。 */
    private fun adaptiveRowHeight(menu: JPopupMenu): Int {
        val separators = menu.components.count { it is JSeparator }
        val rows = (menu.componentCount - separators).coerceAtLeast(1)
        return rowHeightFor(rows, separators)
    }

    /**
     * 求菜单的**展示尺寸** —— **不依赖 peer**，也就是说：
     * **菜单还没 `show()`（没有原生窗口）时也能算出正确的宽高**。
     *
     * ## 这是本次崩溃修复的核心（BUG-019）
     *
     * 原先 [dev.cdl.window.IslandPopupMenu.placeMenu] 在 `show()` **之前**
     * 调 `menu.preferredSize` 来算边缘翻转。那条路径**不可靠** ——
     * `getPreferredSize()` 会去问 `MenuItemLayoutHelper`，后者在
     * `calcExtraWidths() → SwingUtilities2.getLeftSideBearing(char) →
     * FontDesignMetrics.getMetrics() → FontStrikeDesc → StrikeCache.xAdvanceOffset`
     * 这条链上触碰 `sun.font` 的**共享静态初始化**。真机实测（见 recon/28）：
     * 在这条链上抛过 `IllegalComponentStateException: component must be showing on
     * the screen to determine its location`，异常被 `onFailure` 吞掉 → **菜单从未弹出**。
     *
     * 本函数把尺寸**完全自算**，一个 `preferredSize` 都不取：
     *
     * - **高度** = 内边距上下 + **逐项累加各自的行高** + 分隔线数 × 分隔线高。
     *   基础行高用 [rowHeightFor]，与 [apply] 里 `styleComponent` 用的是**同一个函数、
     *   同一批入参**，故两者必然一致（不会出现"皮肤按 28 排、这里按 24 算"）。
     *
     *   ⚠️ **2026-09-18 由"行数 × 行高"改为"逐项累加"**：多行标签让不同行的高度
     *   不再相同（见 [wrapLabelIfNeeded]）。用乘法会算出**比实际矮**的总高 ——
     *   后果是菜单窗口比内容矮，最后一行被裁掉（用户看到"菜单底部少一项"，
     *   而它其实画不出来）。逐项累加读的就是 [styleItem] / [wrapLabelIfNeeded]
     *   已经写进 `preferredSize` 的那个数，**两者不可能漂移**。
     *   未套过样式的组件（理论上不存在）回退到基础行高。
     * - **宽度** = 内边距左右 + 各项自身最小宽度的最大值。
     *   各项的最小宽度由 [styleItem] 已经设成 `max(180, preferred.width + 12)`
     *   （见那里的注释）—— 那是**既有的取值**，本函数复刻同一公式。
     *   子菜单那一行还额外留了 `rowEnd + glyphBox + glyphGap`（[styleComponent]
     *   的 `JMenu` 分支），状态项留 `rowEnd + glyphBox + glyphGap`（[styleItem]），
     *   这些都已含在 `preferred.width` 里，无需再加。
     *
     * ## 为什么宽度不用 `getPreferredSize()`
     *
     * 对**已套皮肤**的菜单项来说，`preferredSize` 已被 [styleItem] 显式
     * `setPreferredSize(...)` 覆盖 —— 而 `JComponent.getPreferredSize()` 只在
     * `preferredSize == null` 时才委派给 UI（JDK 源码
     * `javax/swing/JComponent.java` 的该方法是 `if (preferredSize != null) return
     * preferredSize;`），故**这条路径本身不碰字体度量**，是安全的。
     * 为了不依赖这个"恰好安全"，这里仍然只读 [JComponent.getPreferredSize] 的结果，
     * 并在读之前**先确认它已被显式设过**（否则回退到按字宽估算），
     * 从而把"会不会碰 sun.font"这件事从"取决于调用顺序"变成"结构上不可能"。
     *
     * 调用方保证在 EDT 上（[dev.cdl.window.IslandPopupMenu.present] 的契约）。
     */
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
        // 一项都没有的退化情形：给一个不小于最小宽度的值，避免算出 0 宽的菜单。
        if (width <= 0) width = minMenuWidth
        return Dimension(width + menuPadding * 2, height)
    }

    /**
     * 单个菜单行的**实际行高**（含多行标签带来的增高）。
     *
     * 优先信 `preferredSize.height` —— 它是 [styleItem]（单行）或
     * [wrapLabelIfNeeded] / [MenuStepper.useRowHeight]（多行）显式写进去的，
     * 读它是**纯字段读取**，不触碰字体度量（与 [itemPreferredWidth] 同一条纪律：
     * `JComponent.getPreferredSize()` 只在字段为 `null` 时才委派给 UI）。
     * 没被设过（理论上不存在）则回退到基础行高，保证不会算出 0 高的行。
     */
    private fun itemPreferredHeight(component: Component, fallback: Int): Int {
        val preferred = (component as? JComponent)?.preferredSize
        return if (preferred != null && preferred.height > 0) preferred.height else fallback
    }

    /**
     * 单个菜单行的最小宽度（含它自己的 border 内缩）。
     *
     * 优先信 [JComponent.getPreferredSize]：套过皮肤的项已被 [styleItem]
     * 显式设过 `preferredSize`，此时它是**纯字段读取**，不触碰字体度量。
     * 没被设过（例如将来新增的自绘行）则退回按字宽估算 —— 那一步只用到
     * `FontMetrics.stringWidth`，走的是 `FontDesignMetrics` 的**已有实例**，
     * 不触发 `StrikeCache` 的静态初始化那条链。
     */
    private fun itemPreferredWidth(component: Component): Int {
        // ── 步进器行：**必须按步进器自己的宽度算**（2026-09-18 修「子菜单元素重叠」）──
        //
        // 用户原话：「右键菜单**子菜单的元素重叠问题仍未解决，尤其设置颜色处**。」
        //
        // ## 根因
        //
        // 步进器行的外层 `JMenuItem` 的 `text` 是**空串** —— 内容全靠
        // [MenuStepper] 自己画（「标签 …… − 值 +」）。而 LAF 的
        // `MenuItemLayoutHelper` 是按**文本**算宽度的：空串 → 只算出内缩那点宽度。
        // 于是 [contentWidthOf] 得到的菜单宽度**远小于**步进器实际要画的内容，
        // 步进器只能挤在一条过窄的矩形里 → **标签与「− 值 +」互相重叠**。
        //
        // **为什么只有「颜色处」最明显**：颜色项是三根滑杆
        // （`*.color.r` / `.g` / `.b`），一个子菜单里**连续三项都是步进器** ——
        // 没有一条长文本项把菜单宽度撑开，于是低估的宽度直接生效。
        // 别的子菜单里只要有一条长文案项，宽度就被它撑到够用，问题被掩盖。
        //
        // ## ⚠️ 2026-09-19 修订：这一支**只是"读"，不是"修"**
        //
        // 上面那段（2026-09-18）**只让自算宽变大**，没让**真实 popup** 变大 ——
        // 而 popup 的真实宽度由 `DefaultMenuLayout` 按子组件的 `preferredSize` 求和，
        // 外层那个 `JMenuItem` 的宽仍是 180。于是"自算 408 / 真实 241"，
        // 反倒**制造**了用户本轮报的两条缺陷（③ 重叠照旧 + ② 向左展开时子菜单隔空）。
        // 真正的修法在外层宽度上，见 [styleComponent] 的 `is JMenuItem ->` 分支；
        // 本支与它共用 [stepperRowWidth]，故两处不可能再漂移。
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

    /**
     * **步进器行实际需要的宽度** = 步进器自算宽 + 外层 `JMenuItem` 的左右内缩。
     *
     * ## 为什么必须是一个**共用**函数（而不是两处各写一遍）
     *
     * 这条算式有**两个消费点**，它们必须给出**同一个数**：
     * · [styleComponent] 的 `is JMenuItem ->` 分支 → 写进外层 `preferredSize`，
     *   于是 `DefaultMenuLayout` 把 popup **真实**撑到这么宽；
     * · [itemPreferredWidth] → 让 [displaySize] / [chainWidth] **自算**出同样的宽
     *   （落位、链方向判定都要在 `show()` **之前**用，那时量不到真实窗）。
     *
     * 两者一旦不一致，缺陷就是用户本轮报的那两条：
     * **向左展开的子菜单与父菜单之间空出一段**（自算 > 真实，见 [styleComponent] 的
     * 那段实测表）、**步进器标签压到 `− 值 +` 上**（自算按 380 折行、真实只有 180）。
     * 故算式只有这一份 —— 与 [rangeGap] 那组常量"只有一份"是同一条纪律。
     *
     * @return 需要的宽度；步进器还没算过宽度时返回 `0`（调用方须保留原值）。
     */
    private fun stepperRowWidth(component: Component, stepper: MenuStepper): Int {
        val insets = (component as? JComponent)?.insets
        val w = stepper.preferredSize?.width?.takeIf { it > 0 }
            ?: stepper.minimumSize?.width?.takeIf { it > 0 }
            ?: 0
        return if (w <= 0) 0 else w + (insets?.left ?: 0) + (insets?.right ?: 0)
    }

    /**
     * 菜单最小宽度。原实现 `089:ui/Windows11PopupStyle.kt:344` 的取值。
     *
     * 用途有二：① [itemPreferredWidth] 的兜底；② 让"退出"这类短文案的菜单
     * 不至于窄成一条标签（悬停高亮条与状态字形会挤在一起）。
     */
    private const val minMenuWidth = 180

    // ── 子菜单链的展开方向（2026-09-17 新增，修「一会向左、一会向右」） ──────────

    /**
     * 子菜单链方向的 client property 键 —— 存在**顶层** `JPopupMenu` 上，
     * 由各层 [DirectionalMenu] 向上找根 popup 读取。见 [decideChainDirection]。
     */
    private const val CHAIN_DIRECTION_KEY = "cdl.menu.chainDirection"

    /** 整条子菜单链的展开方向。 */
    private enum class ChainDirection {
        /** 每一层都从父项**右**缘向右展开。 */
        RIGHT,

        /** 每一层都从父项**左**缘向左展开。 */
        LEFT,

        /** 不做干预，逐层交回 JDK 判定（见 [decideChainDirection] 的兜底分支）。 */
        AUTO,
    }

    /**
     * **整条子菜单链的总宽度**：本菜单宽 + 它最深的那条子菜单路径宽。
     *
     * ## 为什么需要它
     *
     * JDK 的 `JMenu.getPopupMenuOrigin()`（Temurin 21 `JMenu.java:400-425`）**逐层**
     * 决定子菜单朝哪边开：右侧放不下**且**父项在屏幕右半边 → 翻到左边。
     * "逐层"是问题所在：一条 4 层深的菜单链（主菜单 → 调整词岛设置 → 显示与行为 → 形态）
     * 在第 3 层判定"放不下"时，会**单独**翻到左边，落点正好压在第 1、2 层上。
     * 实测（`recon/menu-lab/SubmenuDirectionLab.java`，2560×1540，锚点 x=1750）：
     * `L0=1750 L1=1974 L2=2151 L3=1974` —— 第 3 层与第 1 层**同 x**，即被自己的祖先盖住。
     *
     * 要避免这件事，必须**在顶层一次性**判断"整条链放不放得下"，见 [decideChainDirection]。
     * 本函数就是那个判断需要的输入。
     *
     * ## 为什么用 [displaySize] 逐层累加（而不是量真实 popup 宽）
     *
     * 判断发生在 `show()` **之前**，那时一个 popup 窗口都还没有（量不到）。
     * [displaySize] 是纯几何自算，正好满足这个约束。
     *
     * 累加值是**偏大**的：实测每层真实推进量 = 父层 `displaySize().width - 13`
     * ⚠️ **2026-09-24 更正**：这里原有一个**自相矛盾的分解** ——
     * 原写「（5px 内边距 + `Menu.submenuPopupOffsetX = -4` + 布局取整）」✗，
     * 但 `5 + (−4) = **1**`，**推不出 13** ✗（勘察报告 W6-04 的原话：「注释内部自相矛盾」）。
     *
     * **⇒ 已删掉那个错误分解。**
     * · **`13` 这个值本身保留** ✓ —— 它是**实测**出来的（每层真实推进量），不是推导出来的 ✓；
     * · 可证的只有 `menuPadding * 2 = 10` 是其中一部分 ✓；
     * · **剩下的 3 没有取证** ⇒ **不编一个看起来对的分解** ✗（编了就是把"自相矛盾"换成"看着合理但可能错"）。
     *
     * **待办**：若要拆解这 13，应实测「父层 popup 宽 − 子层 popup 起点 x」逐层取差，
     * 而不是从几个常量里凑 ✓。
     * 故按 `displaySize` 全宽累加会高估约 13px/层 —— 高估只会让"右侧放不下"更早成立
     * （即更早整条翻左），不会算出"放得下"而实际溢出。**方向上的保守是安全的。**
     */
    internal fun chainWidth(menu: JPopupMenu): Int {
        var deepest = 0
        for (component in menu.components) {
            if (component is JMenu) deepest = maxOf(deepest, chainWidth(component.popupMenu))
        }
        return displaySize(menu).width + deepest
    }

    /**
     * 在**顶层**一次性决定整条子菜单链的方向，写进 [menu] 的 client property。
     *
     * ## 规则（三条，顺序即优先级）
     *
     * 1. 菜单左边缘 + [chainWidth] 不超出屏幕右缘 → **整条向右**。
     * 2. 否则，菜单左边缘向左退 `chainWidth - 本菜单宽` 仍不超出屏幕左缘 → **整条向左**。
     * 3. 两侧都放不下（链比屏幕还宽，2560 宽屏下链只有 ~900px，实际到不了）→ `AUTO`，
     *    逐层交回 JDK —— 宁可偶尔不一致，也不能把子菜单推到屏幕外面去。
     *
     * ## 为什么是"整条一个方向"而不是"每层各自最优"
     *
     * 每层各自最优正是 JDK 现在的行为，也正是用户看到的"逻辑混乱"：
     * 同一条链里第 1、2 层向右、第 3 层向左，且第 3 层落在第 1 层上面。
     * **方向是"这条链整体的属性"，不是"某一层的属性"** —— 决定一次、全体照办，
     * 用户才可能预期"我这次右键，子菜单一律往哪边开"。
     *
     * ## 为什么不选"永远向右 + 把主菜单整体左移"
     *
     * 那条路（方案 C）能让方向**恒定**，但代价是主菜单会离光标越来越远：
     * 光标在屏幕最右侧时，主菜单左边缘要退到 `屏幕右缘 - chainWidth`（2560 屏上约退 900px），
     * 与用户刚点的位置完全脱节。而用户对菜单的第一诉求是
     * **"随鼠标光标弹出"**（见 [dev.cdl.window.IslandPopupMenu.placeMenu] 的 KDoc）。
     * 本方案把主菜单留在光标处、只让**子菜单链**整体选一侧，两个诉求都满足。
     *
     * ## 与原实现 0.8.9 的关系（**它没有对策**，不要以为在对齐它）
     *
     * 取证：原实现 `089:ui/Windows11PopupStyle.kt` 全文件与 `089:ui/SwingTrayPopup.kt`
     * 均**没有任何**子菜单方向 / `setMenuLocation` / `Menu.submenuPopupOffsetX` 相关代码
     * （全目录 grep 只命中 `fitPopupToWorkArea` 里那一处 `window.setLocation`，
     * 那是"把整窗塞回工作区"，与方向无关）。
     * 原实现唯一沾边的一处是 `089:ui/IslandMenu.kt:102-103` 的注释 —— 它把设置子菜单
     * 放在主菜单**第一项**，让子菜单向下展开时纵向空间最大。**那是纵向，不是横向。**
     * 故本函数是**我们自己的修法**，不是复刻原实现。
     *
     * @param menuLeft 主菜单左边缘的屏幕 x（由 `placeMenu` 算出）。
     * @param screenLeft / @param screenRight 光标所在屏的**整屏**左右缘。
     */
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

    /**
     * **方向受控的子菜单行** —— 菜单里所有 `JMenu` 都必须用它建。
     *
     * ## 为什么是覆写 `getPopupMenuOrigin()`，而不是 `setMenuLocation()`
     *
     * `JMenu` 有两条设置子菜单位置的公开路径，只有一条能用：
     *
     * - `setMenuLocation(x, y)` 写的是 `customMenuLocation`，它在
     *   `JMenu.setPopupMenuVisible` 里**优先于** `getPopupMenuOrigin()` 被采用
     *   （Temurin 21 `JMenu.java:331-337`）。但它是**一次性定值**，
     *   而我们每次右键都重建菜单、位置每次都不同；要让它跟上，就得在每次
     *   "菜单即将展开"之前把它算好 —— 而那个时点（`MenuListener.menuSelected`）
     *   在 `setPopupMenuVisible` **之前**，那时 `isShowing()` 还不成立，
     *   拿不到父项在屏幕上的坐标。**时序上不可靠。**
     * - `getPopupMenuOrigin()` 是 `protected`，**每次展开时现算**，
     *   调用点自带正确的坐标系（相对本 `JMenu` 项）。**这才是对的落点。**
     *
     * ## 只改横向，纵向仍用 JDK 的判定
     *
     * `super.getPopupMenuOrigin()` 的 `y` 原样保留 —— 见
     * [dev.cdl.window.IslandMenuSkin] 的类 KDoc 与 `recon/27` 的"遗留风险"：
     * 用户报的是**左右**，纵向翻转（下方放不下时翻到上方）在视觉上远不如横向
     * 自相矛盾那样"看起来坏了"，且强行统一纵向会把深子菜单推到屏幕外。
     *
     * ## 没有方向属性时退化为 JDK 行为
     *
     * [chainDirection] 读不到属性（例如某个 `JMenu` 被单独 `show` 出来，没走
     * [dev.cdl.window.IslandPopupMenu.present]）时返回 `AUTO` →
     * 完全等于覆写前的行为。**新增机制不改变任何既有路径的语义。**
     */
    internal class DirectionalMenu(text: String) : JMenu(text) {

        override fun getPopupMenuOrigin(): Point {
            val base = super.getPopupMenuOrigin()
            return when (chainDirection()) {
                ChainDirection.RIGHT -> Point(rightOriginX(), base.y)
                ChainDirection.LEFT -> Point(leftOriginX(), base.y)
                ChainDirection.AUTO -> base
            }
        }

        /** 本子菜单所属**整条链**的方向（存在链根的 client property 上）。 */
        private fun chainDirection(): ChainDirection =
            rootPopup()?.getClientProperty(CHAIN_DIRECTION_KEY) as? ChainDirection
                ?: ChainDirection.AUTO

        /**
         * 链根 popup：沿 `popup.invoker` 一路向上，直到某个 popup 的 invoker **不是** `JMenu`
         * （那就是主菜单 —— 它的 invoker 是 [dev.cdl.window.IslandPopupMenu]
         * 那个 1×1 的 owner `contentPane`）。
         *
         * 不用 `JPopupMenu.getRootPopupMenu()`：那是包私有方法（Temurin 21
         * `JPopupMenu.java:1013`），外部调不到。
         */
        private fun rootPopup(): JPopupMenu? {
            var popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, this) as? JPopupMenu
            while (popup != null) {
                val invoker = popup.invoker as? JMenu ?: return popup
                popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, invoker) as? JPopupMenu
            }
            return null
        }

        /** JDK 的 `Menu.submenuPopupOffsetX`（本机实测为 −4）。与 JDK 读同一个键，不另写常量。 */
        private fun offsetX(): Int = UIManager.getInt("Menu.submenuPopupOffsetX")

        /** 向右：子菜单左边缘贴在**本项右缘**（JDK 同式，`JMenu.java:412`）。 */
        private fun rightOriginX(): Int = width + offsetX()

        /**
         * 向左：子菜单右边缘贴在**本项左缘**（JDK 同式，`JMenu.java:419`）。
         *
         * ⚠️ 宽度用 [displaySize] 自算，**不取** `popupMenu.preferredSize` ——
         * 后者会走 `MenuItemLayoutHelper` → `sun.font` 的共享静态初始化，
         * 是 BUG-019（`IllegalComponentStateException`，菜单从未弹出）的根因路径。
         * JDK 自己的 `getPopupMenuOrigin` 里有这条路径（`pm.getSize()` 为 0 时回退
         * `getPreferredSize()`），我们**不跟**。
         */
        private fun leftOriginX(): Int = -offsetX() - displaySize(popupMenu).width
    }

    /** 显示后的一次"落位"：先塞进工作区、再把 popup 宿主窗涂成菜单底色、挂移动监听、最后套透明度。 */
    private fun settlePopup(menu: JPopupMenu, opacityPct: () -> Int, fadeEnabled: () -> Boolean) {
        fitPopupToWorkArea(menu)
        paintPopupBackground(menu)
        watchPopupWindow(menu)
        startMenuFade(menu, opacityPct, fadeEnabled)
    }

    // ── 菜单弹出时的**淡入**（2026-09-20）────────────────────────────────────
    //
    // 用户原话：「**我觉得我们可以引入动画开关，然后增加对CPU GPU性能高的动画效果，
    // 以大大提升UX**」。
    //
    // ## 为什么菜单的淡入**不走** `AnimationRuntime` 的帧循环
    //
    // 主帧循环的启停由**播放态**决定（暂停即停帧）。而菜单是**独立的原生弹出窗**，
    // 存活期只有几百毫秒，与"歌在不在播"毫无关系 —— 挂在帧循环上会出现
    // **"暂停时右键菜单不淡入"**这种莫名其妙的表现。
    //
    // 故这里用 [transitionOf] 造一个独立过渡，**用菜单自己的定时器**推进。
    // 复用的是缓动与插值的**数学**，不是**调度**。

    /** 正在跑的菜单淡入过渡。`null` = 没有。 */
    private var menuFade: Transition? = null

    /** 推进 [menuFade] 的定时器。 */
    private var menuFadeTimer: Timer? = null

    /**
     * [menuFade] 属于**哪个**菜单。
     *
     * ⚠️ 必须有它：菜单链上每一层（根 + 子菜单）都会走 [startMenuFade]，
     * 而定时器只有一个字段。没有归属信息就分不清"该停谁"——
     * 关闭子菜单会把根菜单正在跑的淡入一起掐掉（见 [finishRunningMenuFade]）。
     */
    private var menuFadeOwner: JPopupMenu? = null

    /**
     * 已经淡入过的那个菜单实例。
     *
     * ## 为什么必须记它（否则菜单会"闪两下"）
     *
     * `settlePopup` 会被调用**两次**（`invokeLater` 一次、`SETTLE_DELAY_MS` 一次，
     * 见 `apply` 里的弹出回调）。若不判重，第二次调用会把不透明度**重新拉回 0**
     * 再淡一遍 —— 用户看到的是"淡进来一半，又跳回去重淡"。
     *
     * 用**实例身份**判重而不是"定时器还在不在跑"：后者与淡入时长耦合
     * （时长 < `SETTLE_DELAY_MS` 时定时器已停，判重失效）。
     */
    private var fadedMenu: JPopupMenu? = null

    /**
     * 菜单弹出时**从透明淡入**。**必须在 EDT 上调用。**
     *
     * ## ★ 顺序上的硬约束：**先设终值探测能力，再设 0 开始淡**
     *
     * `Window.setOpacity` 在**不支持 `TRANSLUCENT` 的机器上会抛**。若先设 0 再淡，
     * 那种机器上就会留下一个**永远隐形的菜单** —— 用户看不到菜单、也就没有任何
     * 界面手段能把它调回来。这是**不可自救状态**，本项目明令禁止
     * （同 `applyVisibility` 里"绝不制造用户无法自救的状态"）。
     *
     * 故：**第一次赋值就是终值**。成功 → 说明支持，再设 0 开始淡；
     * 失败 → 已经是"不支持"，直接返回（菜单保持不透明，与改动前行为一致）。
     *
     * ## ★★★ 2026-10-15：淡入的终值**恒为 `1.0f`**（不再是 `pct/100`）
     *
     * 用户要求"透明度不对文字生效" ⇒ 透明度已落到**背景色**上（见 [applyMenuOpacity]）。
     * 若终值仍取 `menuOpacityAlpha(pct)`，静态时整窗又会被乘一次
     * ⇒ 背景 = `pct²`、文字 = `pct` ✗（**正是用户报的那条缺陷**）。
     *
     * ⇒ 终值恒 `1.0f` = "窗口不做整窗 alpha"，与 [applyMenuOpacity] 的静态落位
     *   **是同一个值** —— 仍满足项目纪律「**动画的终值必须等于静态值**」✓
     *   （淡入期间 `opacity` 从 0 → 1，那只是"菜单正在出现"的过程；结束后文字全不透明。）
     */
    private fun startMenuFade(menu: JPopupMenu, opacityPct: () -> Int, fadeEnabled: () -> Boolean) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return
        // ① 静态落位先做（含"本机支不支持"的探测与失败日志）。
        //    用既有的 [applyMenuOpacity] 而不是自己写一遍 `runCatching` ——
        //    那个"只报一次"的诊断是排查"菜单透明度不生效"的唯一线索，不能丢。
        if (!applyMenuOpacity(menu, opacityPct())) return
        // ② 本次弹出已经淡过了 → 不再重来（`settlePopup` 会来两次）。
        if (fadedMenu === menu) return
        // ★ 换一个菜单开始淡入前，把**上一个**立刻落到终值并停表。
        //   不停的话上一个菜单的窗口会**永远停在半透明**（详见 finishRunningMenuFade）。
        finishRunningMenuFade()
        fadedMenu = menu
        // ③ 动画关闭 → 上面的终值赋值就是最终结果（**真的没有动画**，不是"变快"）。
        //    传 `0` 给 transitionOf 会让它同步落终值并返回 null。
        val spec = Motions.MENU_FADE
        val durationMs = if (fadeEnabled()) spec.durationMs else 0L
        val t = transitionOf(
            from = 0f,
            // ★ 终值恒 1.0f —— 见本函数 KDoc「淡入的终值恒为 1.0f」。
            to = 1.0f,
            durationMs = durationMs,
            // 缓动来自 spec（`EASE_OUT`：菜单"已经弹出来了"，用户要的是
            // **尽快看到**，故开头快、结尾减速落位）—— **不在调用点写死**。
            easing = spec.easing,
            onUpdate = { v -> runCatching { window.opacity = v } },
        ) ?: return
        // ④ 起点设为全透明（此时才敢设 0 —— 能力已探测过）。
        runCatching { window.opacity = 0f }
        menuFade = t
        menuFadeOwner = menu
        // 用 `Timer(delay) { ... }` 构造式（与本文件其它定时器同形）——
        // lambda 直接就是 `ActionListener`，不必再 `addActionListener`。
        val timer = Timer(MENU_FADE_TICK_MS) { ev ->
            // `advance` 返回 true = 这一帧之后动画已结束 → 停表。
            // ⚠️ 停表是必须的：不停的话每 16 ms 一次 `window.opacity = target`
            //    会一直跑下去，而这个菜单还要活好几秒（用户正在里面挑选项）。
            if (t.advance(System.nanoTime())) (ev.source as? Timer)?.stop()
        }
        menuFadeTimer = timer
        timer.start()
    }

    /**
     * 把正在跑的菜单淡入**立刻落到终值**并停表。**必须在 EDT 上调用。**
     *
     * ## ★ 为什么必须"落到终值"而不是简单停掉（这是一处真实缺陷的修法）
     *
     * 菜单链上**每一层**（根 + 已展开的子菜单）都会走 [startMenuFade]，
     * 而推进动画的定时器只有**一个**字段。于是：
     *
     * ```
     * 根菜单开始淡入（t=0） → 用户 130 ms 时展开子菜单（根还差 10 ms 没淡完）
     *   → 子菜单的 startMenuFade 把定时器停掉 → 根菜单的进度**冻在 0.95**
     *   → 根菜单永远比它该有的不透明度淡一点点
     * ```
     *
     * 这个缺陷**不报错、不崩**，用户只会觉得"菜单好像有点透"，
     * 而且**只有在那 10 ms 的窗口里展开子菜单才会复现** —— 属于最难归因的一类。
     *
     * 修法：停表前先 `advance(Long.MAX_VALUE)`（进度夹到 1 → `onUpdate(终值)`），
     * 让被顶掉的那个菜单**停在该有的不透明度上**。
     */
    private fun finishRunningMenuFade() {
        val t = menuFade
        menuFadeTimer?.stop()
        menuFadeTimer = null
        menuFade = null
        menuFadeOwner = null
        t?.advance(Long.MAX_VALUE)
    }

    /**
     * 菜单收起时**清掉淡入的记账**。**必须在 EDT 上调用。**
     *
     * ## 两件事各自的条件
     *
     * ① 清 [fadedMenu] —— 不清的话，持有引用会让**上一个菜单实例**
     *    （连同它的全部组件与 `BufferedImage` 皮肤）无法回收。
     *    菜单是每次右键重建的，这份引用没有存在价值。
     * ② 停淡入 —— ⚠️ **只停"属于这个菜单"的那一次**。无条件停的话，
     *    关闭一个子菜单会把**根菜单**正在跑的淡入一起掐掉，
     *    根菜单就永远停在半透明（同 [finishRunningMenuFade] 的那个缺陷）。
     */
    private fun forgetMenuFade(menu: JPopupMenu) {
        if (fadedMenu === menu) fadedMenu = null
        if (menuFadeOwner === menu) finishRunningMenuFade()
    }

    /**
     * 把**菜单不透明度**应用到承载本菜单的那个弹出窗。
     *
     * ## 为什么可行（实测，不是推断）
     *
     * 菜单走 heavyweight popup（[apply] 里 `isLightWeightPopupEnabled = false`），
     * 承载它的是 `javax.swing.Popup$HeavyWeightWindow`（`JWindow` 子类）。
     * 实测（Temurin 21.0.12.1 / Win11）：
     * ```
     * Window.getWindows() → javax.swing.Popup$HeavyWeightWindow showing=true
     * 该窗口 setOpacity(0.5) → OK，getOpacity()=0.5
     * GraphicsDevice.isWindowTranslucencySupported(TRANSLUCENT) = true
     * ```
     *
     * ⚠️ **JDK 21 的 `Window.setOpacity` 源码里没有"必须 undecorated"这条检查**
     * （只校验：范围 `[0,1]`、不是全屏窗、`TRANSLUCENT` 受支持）。
     * javadoc 那句"must be undecorated"是给 `Frame` / `Dialog` 的
     * （那两个类的 peer 会拒绝）—— `JWindow` 本身没有装饰，天然满足。
     * 另外 `java.awt.Window` **没有** `isUndecorated()` 方法（编译期实测
     * `找不到符号`），所以"先判它再 setOpacity"这种防御写法**根本写不出来**。
     *
     * ## 为什么整段 `runCatching`
     *
     * `setOpacity(<1)` 在两种环境下会抛：`TRANSLUCENT` 不受支持
     * （`UnsupportedOperationException`，见于部分远程桌面 / 老驱动）、
     * 或窗口正处全屏（`IllegalComponentStateException`）。
     * 那时**必须让菜单照常可用**（只是不透明）—— 透明度是外观增强，
     * 不该把"右键弹不出菜单"变成代价。故吞掉并只记一次日志。
     *
     * ## 为什么每次都设（而不是只在 < 100 时设）
     *
     * 弹出窗是 `PopupFactory` **回收复用**的（同一个 `HeavyWeightWindow` 会被
     * 后续菜单复用）。若只在 `< 100` 时设，用户把 40% 调回 100% 后
     * **下一个菜单仍是 40%** —— 那种"设置改了没生效"的缺陷最难归因。
     * 每次都设（含 `1.0f`）才让"窗口状态"与"设置值"永远一致。
     *
     * ## ★★★ 2026-10-15：落点改成**背景色 alpha**（用户报障）
     *
     * 用户逐字：「**右键菜单的透明度设置改为不对文字内容生效**」。
     * ⇒ 逐像素透明可用时，本函数**不再**给窗口设 `opacity = pct/100`
     *   （那会把文字一起乘淡 ✗），而是：
     *   ① 把 pct 落到 [surfaceAlpha]（**唯一**影响底板颜色的那个量）；
     *   ② 窗口 `opacity` 设为 **1.0f**（静态时不做整窗 alpha）；
     *   ③ `repaint()` 让底板用新 alpha 重画。
     * ⇒ ★ 文字 / 箭头 / 勾号 / 高亮条各走自己的绘制路径，**不受影响** ✓
     *
     * ★ 逐像素不可用时**退回整窗 alpha**（= 改动前行为，文字会跟着淡）——
     *   退而求其次但菜单仍"能透"；这是有意的降级，不是遗漏。
     *
     * @return **是否设置成功**。2026-09-20 起有返回值 —— [startMenuFade] 用它
     *   探测"本机支不支持半透明"：**不支持就完全不做淡入**（否则会先设 0
     *   再发现设不上去，菜单可能停在隐形状态）。
     */
    private fun applyMenuOpacity(menu: JPopupMenu, opacityPct: Int): Boolean {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return false
        val pct = coerceMenuOpacityPct(opacityPct)
        // ★ 落点一（首选）：**背景色 alpha** —— 文字不受影响。
        setSurfaceAlpha(pct)
        if (perPixelTranslucent) {
            // 静态时窗口**不做**整窗 alpha；整窗 alpha 只留给淡入（见 [startMenuFade]）。
            val ok = runCatching { window.opacity = 1.0f }.isSuccess
            window.repaint()
            return ok
        }
        // ★ 落点二（回退）：整窗 alpha —— 与改动前逐像素一致。
        val alpha = menuOpacityAlpha(pct)
        window.repaint()
        if (runCatching { window.opacity = alpha }.isSuccess) return true
        // 只报一次：菜单每次弹出都会走到这里，逐次打印会把日志刷满
        // （本项目在 BUG-017 上踩过同类问题：5071 行噪声把真信号淹掉）。
        if (!opacityFailureLogged) {
            opacityFailureLogged = true
            System.err.println(
                "[CDL] 菜单透明度不可用（本机不支持 TRANSLUCENT 窗口半透明），" +
                    "菜单将保持不透明：$alpha",
            )
        }
        return false
    }

    /** [applyMenuOpacity] 失败只报一次 —— 见那里的注释。只在 EDT 上读写。 */
    private var opacityFailureLogged = false

    /**
     * 把 alpha 立刻套到**当前所有可见的菜单弹出窗**上。
     *
     * ## 为什么需要它（否则"调透明度"就是个死设置）
     *
     * 菜单是**每次右键重建**的，透明度本来只在下一次弹出时生效 ——
     * 于是用户在菜单里拖着「右键菜单透明度」那根步进器时，**画面毫无变化**，
     * 只有关掉再右键才看到效果。那正是用户会报"这项没用"的形态。
     *
     * 由 `IslandPopupMenu.applyValue` 在写入该键之后调用（见那里的注释）。
     * 遍历 `Window.getWindows()` 而不是持有窗口引用：菜单链可能有多层 popup
     * （根 + 已展开的子菜单），用户期望的是"整条链一起变淡"。
     * 判据复用 [isMenuPopupWindow]（含 `JPopupMenu` 的窗口），与
     * `GlobalMenuDismisser.insideAnyPopup` 是同一套语义 ——
     * 不会误伤 1×1 的 owner 窗口。
     */
    internal fun refreshVisibleMenuOpacity(opacityPct: Int) {
        val pct = coerceMenuOpacityPct(opacityPct)
        // ★★★ 2026-10-15：落点与 [applyMenuOpacity] **同一处定义**（底板 alpha）。
        //    写在循环**外**：它是全局量（用户设置只有一个值），逐窗重复写是浪费，
        //    更重要的是"写在哪里"决定了它会不会被漏 —— 一处写、全部窗共享 ✓
        setSurfaceAlpha(pct)
        val alpha = menuOpacityAlpha(pct)
        for (window in Window.getWindows()) {
            if (!window.isShowing || !isMenuPopupWindow(window)) continue
            // ★ 逐像素可用 ⇒ 整窗恒 1.0（静态不做整窗 alpha，文字不受影响）；
            //   否则退回整窗 alpha（= 改动前行为）。
            runCatching { window.opacity = if (perPixelTranslucent) 1.0f else alpha }
            // ★ **必须重绘**：底板颜色是在 [PopupUi.paint] 里按 [surfaceAlpha] 现算的 ——
            //   只改字段不重绘，用户拖步进器时画面不会动（那正是"这项没用"的形态）。
            window.repaint()
        }
    }

    /**
     * 把 popup 的**宿主窗口**（以及它的 `contentPane`）也涂成菜单底色。
     *
     * ## 为什么这一步不能省
     *
     * `JPopupMenu` 画在自己的组件区域里，而承载它的 heavyweight popup **窗口**
     * 仍带着 Look&Feel 的默认底色。窗口比菜单组件大出来的那一圈（内边距、边角）
     * 就会露出那道默认色 —— 在深色菜单上表现为**一圈浅色缝**，
     * 看起来像"菜单外面套了个白框"。
     *
     * `isOpaque = true` 是必须的第三件：只设 `background` 而不把 `contentPane`
     * 标为不透明，Swing 会跳过它的背景绘制，颜色设了也不生效。
     * 原实现 `089:ui/Windows11PopupStyle.kt:224-230` 同此，是 `apply()` 能真正生效的关键补丁。
     *
     * ## ★★★ 2026-10-15：逐像素路下**改涂全透明**
     *
     * 用户要求"透明度不对文字生效" ⇒ 透明度落到**底板颜色**的 alpha 上
     * （见本文件"菜单透明度的新落点"一段）。此时窗口/内容面板**不能再涂不透明底色** ——
     * 否则底板那层半透明色会与它**叠加**：复合 alpha `1−(1−a)² > a`
     * ⇒ 实际比设置值更不透明，而且用户根本分不清哪层在生效 ✗
     *
     * ⇒ 逐像素可用时：窗口/内容面板涂**全透明**，底板色**只由 [PopupUi.paint] 一处定义** ✓
     * ⇒ 逐像素不可用时：保持改动前的行为（涂不透明菜单底色）。
     *
     * ⚠️ `isOpaque = true` 两种情况都要设：它让 Swing **真的去填** `background`
     *   （哪怕填的是全透明色）—— 否则 Swing 会用 L&F 默认色填，又冒出那道浅色缝。
     */
    private fun paintPopupBackground(menu: JPopupMenu) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return
        val content = (window as? RootPaneContainer)?.contentPane
        if (perPixelTranslucent) {
            // ★ 新路：窗口底色全透明（`setBackground` 失败也不影响 —— 底板仍在组件层）。
            runCatching { window.background = transparentColor }
            content?.background = transparentColor
        } else {
            // 旧路：涂成不透明菜单底色（消除"窗口比菜单大出来的那一圈"）。
            window.background = surface
            content?.background = surface
        }
        (content as? JComponent)?.isOpaque = true
    }

    /**
     * 给 popup 窗口挂移动/缩放监听，一旦被 Toolkit 挪动就重新塞回工作区。
     *
     * 用 [watchedWindows] 去重：`settlePopup` 会被调用两次，重复挂监听会让
     * 每次移动触发两遍 `fitPopupToWorkArea`（无害但纯浪费），
     * 更重要的是**同一窗口被反复 `addComponentListener` 是真实的监听器泄漏**。
     */
    private fun watchPopupWindow(menu: JPopupMenu) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return
        if (!watchedWindows.add(window)) return
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = fitPopupToWorkArea(menu)
            override fun componentMoved(event: ComponentEvent) = fitPopupToWorkArea(menu)
        })
    }

    /**
     * 把 popup 窗口挪进**工作区**（而不只是屏幕 bounds）。
     *
     * Swing 自己只 clamp 到屏幕 bounds —— 于是从托盘弹出（锚点靠近屏幕底部）时，
     * 菜单最后几行会落在**任务栏后面**：用户看得到"还有内容"却点不到。
     * 原实现 `089:ui/Windows11PopupStyle.kt:363-368` 的注释记录了同一现象。
     *
     * 比工作区还高的菜单**塞不进去**，此时钉在顶部 —— 至少让前几行可见，
     * 好过把第一行藏到屏幕上方（用户会以为菜单没弹出来）。
     */
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

    /**
     * 逐组件套样式。
     *
     * `JMenuItem` 与 `JSeparator` 之外的类型原样放过（如未来新增的自绘行）。
     *
     * ## `JMenu`（子菜单）为什么**必须单独处理、且不能换成 `MenuItemUi`**
     *
     * `JMenu` 是 `JMenuItem` 的子类 —— 若让它落进下面那个 `is JMenuItem` 分支，
     * 会被换成 `MenuItemUi`，于是**失去悬停展开子菜单的逻辑**
     * （那段逻辑住在 `BasicMenuUI` 里）。表现是"子菜单项点不动、鼠标划过也不展开"。
     * 原实现 `089:ui/Windows11PopupStyle.kt:304` 的注释原文即
     * *"JMenu must keep BasicMenuUI"*，本实现照此。
     *
     * ⚠️ **2026-09-18 修订**：原注释写的是"**不要 `component.ui = ...`**"，
     * 那是**过于宽泛**的表述 —— 真正不能换的是 `MenuItemUi`，
     * 而 `DelayedMenuUi`（[DelayedMenuUi]）是 `BasicMenuUI` 的**子类**，
     * 保留了悬停展开逻辑、只把写死的 0 延迟换成可调值（用户诉求：
     * 「鼠标悬停和离开展开项时的子菜单出现、消失延迟要可调」）。
     * 故这里**必须**给它换 UI，否则展开延迟无处落地。
     * 判据是"换完还是不是 `BasicMenuUI`"，不是"有没有换"。
     *
     * 但**只换 UI 不套样式**也不行：`JMenu` 的行高、字体、文字色若不设，
     * 会和旁边的项不一致（表现为子菜单那一行明显更高、或字色不一致）。
     * 故这里做 [styleItem] 的几何/配色，并额外：
     *
     * - 清掉 `BasicMenuUI` 会在行尾画的**展开箭头**（`arrowIcon`）——
     *   那个箭头是 L&F 默认画法，在深色自绘底上是浅色三角，很扎眼；
     * - 用 [MenuGlyph] 换成我们自己的右向箭头（与对勾同一套描边参数），
     *   放在行尾（与状态字形同一列），视觉上与其余行齐整。
     *
     * ⚠️ 展开箭头的 `arrowIcon` **不是 `JMenu` 的属性**（实测：`javax.swing.JMenu`
     * 没有 arrow 相关方法与字段）。但它是 **`BasicMenuItemUI` 的 `protected` 字段** ——
     * 故正确的置空位置是 [DelayedMenuUi.installDefaults]（子类写得动那个字段），
     * **不是**"从 `JMenu` 实例上取"。
     * 2026-09-21 之前这里写着"无法把它置空"，那是把"实例上没有这个成员"错当成了
     * "子类不能写这个字段"；于是当时改用"用底色矩形盖掉它"，
     * 而那条路**结构上不成立**（见 [PopupUi.paintSubmenuArrows] 的 KDoc）。
     * 已于 2026-09-21 一并改正。
     */
    private fun styleComponent(
        component: Component,
        rowHeight: Int,
        delays: () -> SubmenuDelays,
        opacityPct: () -> Int,
        fadeEnabled: () -> Boolean,
    ) {
        when (component) {
            is JMenu -> {
                // 换 UI 的**唯一**目的：把"悬停即展开"改成"悬停 [SubmenuDelays.openMs] 后展开"。
                // `DelayedMenuUi` 是 `BasicMenuUI` 子类 —— 见上方 KDoc 的 2026-09-18 修订。
                //
                // ⚠️ 必须在 `styleItem` **之前**换：`installUI` 会重设 border / 字体等默认值，
                //    先套样式再换 UI 会被 UI 的 `installDefaults` 覆盖回去。
                // ⚠️ 两个颜色用 lambda 延迟取（调色板随浅色/深色模式切换而变）——
                //    与 [MenuItemUi] 的 `installDefaults` 取同一对颜色，两类行的
                //    "禁用灰 / 选中白"才会一致。**漏传它们会真机 NPE**，见
                //    [DelayedMenuUi.installDefaults] 的 KDoc。
                component.ui = DelayedMenuUi({ delays().openMs }, { text }, { muted })
                // ⚠️⚠️ 必须在**换 UI 之后**置 0：`BasicMenuUI.installDefaults` 会把
                //    `JMenu.delay` 写死成 200（`plaf/basic/BasicMenuUI.java:98`）。
                //
                // 置 0 的两条理由（缺任一条，"子菜单展开延迟"这个设置项都是假的）：
                // ① `BasicMenuUI.Handler.mouseEntered` 按 `delay == 0` 二选一：
                //    0 → `appendPath(getPath(), popup)`（**立即**展开）；
                //    ≠0 → `setSelectedPath(getPath())` + `setupPostTimer`（delay 毫秒后展开）。
                //    [DelayedHoverListener] 已经在**它自己**的延迟里替用户等过了，
                //    这里再叠 JDK 的 200ms 会让真实延迟变成 `openMs + 200`
                //    —— 用户拖那根滑杆时数字在变、手感却几乎不变（因为被 200ms 主导）。
                // ② JDK 那个 `setupPostTimer` 起的 `javax.swing.Timer` **无法从外部取消**
                //    （`BasicMenuUI.Handler.mouseExited` 是空实现）。让它根本不启动，
                //    就把"指针移走后子菜单迟一步弹出"这条 JDK 固有的怪行为一并消掉了
                //    —— 而那条正是 [DelayedHoverListener.mouseExited] 的取消逻辑要防的。
                component.delay = 0
                styleItem(component, rowHeight)
                // 子菜单那一行要用行尾箭头，故右侧留出与状态字形等宽的空间。
                component.border = EmptyBorder(0, rowStart, 0, rowEnd + glyphBox + glyphGap)
                // 子菜单本身也要套皮肤（它是**另一个** JPopupMenu，有自己的 UI）。
                // ⚠️ `isRoot = false`：只有右键弹出的那个根菜单负责启停收起轮询。
                // ⚠️⚠️ 2026-10-14（**BUG-195**）：`opacityPct` / `fadeEnabled` **必须转发** ——
                //    原先这里只传 `delays`，那两个参数就落到 `apply` 的**默认值**
                //    （兜底透明度）⇒ **用户设的菜单透明度对子菜单完全无效** ✗
                //    用户报障（逐字）：「设置右键菜单透明度后，在后续使用中**次级子菜单
                //    没有应用透明度**。」
                //    ★ 根因形态 = 纪律（BUG-192 判据 D）：「**同一判据必须落在【每一条
                //      调用路径】上**」—— 根菜单传了三个取值函数、子菜单只传了一个。
                //    故这里逐字转发同一对 lambda（它们本就是**取值函数**，递归传下去
                //    每个子菜单每次显示都读当下值）✓
                apply(component.popupMenu, delays, opacityPct, fadeEnabled, isRoot = false)
            }

            is JMenuItem -> {
                // keepOpen 由构建侧用 client property 声明（见 [KEEP_OPEN_KEY]），
                // 这里在**换 UI 之前**读它 —— 因为该行为住在 UI 委托的 doClick 里。
                component.ui = MenuItemUi(component.keepOpenRequested)
                styleItem(component, rowHeight)

                // ── 步进器行：必须**再往里看一眼**（BUG-044）──────────────────────
                //
                // ## 原记（错）
                //
                // 以为下面那个 `is MenuStepper -> component.useRowHeight(rowHeight)` 分支
                // 会覆盖到步进器，故 `useRowHeight()` 会被调用。
                //
                // ## 实为
                //
                // 步进器**不是**被遍历到的这一层组件 —— 构建侧把它包在一个**空的 `JMenuItem`** 里：
                //     `JMenuItem().apply { layout = BorderLayout(); add(stepper, CENTER) }`
                // （见 `IslandPopupMenu.stepperItem()`）。
                // 于是它是 `JMenuItem` 的**子组件（孙层）**，`when (component)` 永远匹配到
                // `is JMenuItem ->` 分支 → **`is MenuStepper ->` 分支是死代码**。
                //
                // ## 后果（实测）
                //
                // `useRowHeight()` 从不被调用 → 步进器的**字体 / 行高 / 值框宽度从不按行高重算**
                // → 弹出宽度停在默认值（实测 **190**，而本文件 KDoc 设计的是 **270**）
                // → **取值域提示几乎画不出来**，步进器整体显得局促。
                //
                // ## 为什么在这里处理而不是改构建侧
                //
                // 那个 `JMenuItem` 包装是**有理由的**：`keepOpen()` 是挂在 `JMenuItem` 上的行为
                // （它住在 UI 委托的 `doClick` 里），`MenuStepper` 是裸 `JComponent`，没有这个能力。
                // 故包装不能拆。正确做法是**样式分派时下探一层**。
                //
                // 用 `filterIsInstance` 而不是 `component.component`：后者只取 `setComponent()`
                // 设的**那一个**组件，而这里是用 `add(..., CENTER)` 加的**普通子组件**，
                // 两者不是同一个东西（实测：`component.component` 为 null）。
                // ⚠️ 2026-09-18：`useRowHeight` 现在**返回本行实际需要的行高** ——
                //    标签折行时它比 `rowHeight` 大，必须把外层 `JMenuItem` 也一起加高，
                //    否则步进器画出来的第二行会被外层组件裁掉（用户看到的是"只有半行字"）。
                //    这里**不重算**折行，只用它返回的数：折行规则只有一处定义。
                component.components.filterIsInstance<MenuStepper>().forEach { stepper ->
                    val needed = stepper.useRowHeight(rowHeight)
                    // ── ⚠️ 2026-09-19：**宽度也必须一起写进外层 `JMenuItem`** ──────────
                    //
                    // 用户原话（本轮）：「**右键菜单展开出现重大问题，弹出的第三级菜单
                    //   不紧贴父菜单，隔空弹出。**」「**右键菜单的元素重叠或者设置项名称
                    //   被遮挡问题你还是没有解决。**」
                    //
                    // ## 这两条是**同一个根因**：自算宽 ≠ 真实布局宽
                    //
                    // `styleItem` 在上面把外层的宽度设成 `max(180, L&F 算出的宽 + 12)` ——
                    // 而 L&F 是按**文本**算的，本行的 `text` 是**空串**（内容全由
                    // [MenuStepper] 自己画），于是它算出 180。
                    // 步进器自己的 `preferredSize.width`（= [MenuStepper.ROW_W]，380）
                    // **从来没有传给外层**，所以：
                    //
                    //   · **真实 popup 宽** = 各行外层 `preferredSize.width` 的最大值 + 内边距
                    //     （`DefaultMenuLayout` 只认子组件的 `preferredSize`）；
                    //   · **[displaySize] 算出的宽** = [itemPreferredWidth] 对步进器行
                    //     **特意**取了步进器自己的宽（那一段 2026-09-18 就加了）。
                    //
                    // 两者于是**必然对不上**。实测（`recon/menu-lab/MenuGapLab.java`，
                    // 本机 1707×1027，Temurin 21.0.12.1）：
                    //
                    // | popup | displaySize | Swing 布局宽 | Δ |
                    // |---|---|---|---|
                    // | 显示与行为 | 408 | 241 | −167 |
                    // | 位置与尺寸 | 408 | 193 | −215 |
                    // | 外观与字体 | 408 | 190 | −218 |
                    //
                    // 后果一（**② 隔空**）：链方向判为**向左**时，[DirectionalMenu.leftOriginX]
                    //   用 `displaySize` 算子菜单左边缘 → 子菜单的右边缘落在
                    //   `父菜单左边缘 − Δ` 处。实测**缝 = 158px 的空白**（同一份日志），
                    //   正是用户看到的"隔空弹出"。向右时不显形（那条路用的是
                    //   本项**真实**宽度 `width`），所以缺陷只在屏幕右侧一侧出现。
                    // 后果二（**③ 重叠/遮挡**）：步进器被挤在 180~231px 里，
                    //   而它的 `useRowHeight` 是按 [MenuStepper.ROW_W]（380）算折行的
                    //   （见其 `assumedLabelMaxWidth`）—— **按 380 排、按 180 画**，
                    //   于是标签直接压到 `− 值 +` 上，长标题被切掉。
                    //
                    // ## 修法：把"步进器行需要的宽"写进外层（与高度同一条纪律）
                    //
                    // 高度早就是这么修的（`useRowHeight` 返回本行实际高度、外层跟着加高），
                    // 宽度只是**漏了同一半**。补上之后：
                    //   · `DefaultMenuLayout` 拿到的就是 398（= 380 + 内缩 18）→ 真实 popup 宽
                    //     = 408 = `displaySize` → 缝回到 −9（与父菜单重叠 9px，紧贴）；
                    //   · 步进器的实际宽度 = 398 − 内缩 18 = **380 = ROW_W** →
                    //     折行用的宽度与绘制的宽度**是同一个数**，标签不再压到 `±` 上。
                    //
                    // ⚠️ 算式必须与 [itemPreferredWidth] 的步进器分支**同一份**
                    //   （[stepperRowWidth]），否则两边又会漂移成"自算 ≠ 真实"——
                    //   那正是本条缺陷的成因。故这里**不重写**算式，只调用它。
                    val current = component.preferredSize
                    val width = maxOf(current.width, stepperRowWidth(component, stepper))
                    val height = maxOf(current.height, needed)
                    if (width != current.width || height != current.height) {
                        component.preferredSize = Dimension(width, height)
                    }
                }
            }

            // ⚠️ 本分支在**当前接线**下不可达 —— 步进器总是被包在 `JMenuItem` 里，
            //    永远走不到这一层。**保留它**是防御性的：若将来有人把步进器直接挂进菜单，
            //    这里仍能正确套样式（那时上面那段下探自然不会再命中）。
            //    真正的修复在 `is JMenuItem ->` 分支里，见那段 KDoc（BUG-044）。
            is MenuStepper -> component.useRowHeight(rowHeight)

            is JSeparator -> {
                component.ui = SeparatorUi()
                component.foreground = outline
                component.preferredSize = Dimension(1, separatorHeight)
            }
        }
    }

    /** 单个菜单行的几何与配色。 */
    private fun styleItem(component: JMenuItem, rowHeight: Int) {
        component.font = fontFor(rowHeight)
        component.foreground = if (component.isEnabled) text else muted
        component.background = surface
        component.isOpaque = false
        // 无前导图标列：状态字形住在**中间列**（见 paintStateGlyph），
        // 所以标签从 rowStart 起，与菜单里所有其他行的左边缘对齐。
        component.icon = null
        component.disabledIcon = null
        component.iconTextGap = 0
        // JMenuItem 自己在 border 之上还加了 2px margin，会把标签推右 2px
        // （原实现 `089:ui/Windows11PopupStyle.kt:335-338` 的实测结论）。清零它。
        component.margin = Insets(0, 0, 0, 0)
        // ⚠️ 2026-09-17：状态项的**右边距**改为"整条中间列 + 字形 + 间隙"。
        //
        // **原记**：`rowEnd + glyphBox + glyphGap`（= 32）—— 只给"行尾 + 一个字形宽"留位。
        //
        // **实为**：那让标签的可用宽度一直延伸到 `item.width - 32`，
        //   而勾号画在**中间列**（右边界是 `item.width - middleColumnInset`）——
        //   标签一长就**盖到勾号上**（用户报的"字与勾选状态提示重叠"）。
        //
        // **现做法**：右边距留够**整条中间列**，于是 Swing 画标签时**自带省略号**
        //   （`SwingUtilities.layoutCompoundLabel` 内部会 `clipString`），
        //   标签永远到不了中间列。与 [middleColumnGlyphX] 的夹取**配套**：
        //   那边管"字形不越界"，这边管"标签不越界"，缺一条都会重叠。
        component.border = EmptyBorder(
            0, rowStart, 0,
            if (component.isStateItem) middleColumnInset + glyphBox + rangeGap else rowEnd,
        )
        // 最小宽度 180：菜单项文字很短时（如"退出"），菜单会窄得像一条标签，
        // 悬停高亮条与勾选列都挤在一起。180 是既有的取值，观感上接近系统菜单。
        // 公式 `max(180, preferred.width + 12)` 逐字照
        // `089:ui/Windows11PopupStyle.kt:343-344`（那里的 `minOf`/`maxOf` 写反过一次，
        // 以原实现当前实现的 `maxOf(180, preferred.width + 12)` 为准）。
        val preferred = component.preferredSize
        component.preferredSize = Dimension(maxOf(minMenuWidth, preferred.width + 12), rowHeight)
    }

    /** 是否带状态字形（勾选 / 单选）。 */
    private val JMenuItem.isStateItem: Boolean
        get() = this is JCheckBoxMenuItem || this is JRadioButtonMenuItem

    /**
     * 标记一个菜单项为「点击后**不关菜单**」。
     *
     * ## 为什么需要它（2026-09-17 新增）
     *
     * 「调整词岛设置」是一棵 38 项的设置树。默认的 Swing 行为是**点任何一项就关掉整条
     * 菜单链** —— 于是"打开翻译 → 再开逐字高亮 → 再调字号"要右键三次。
     * 原实现 0.8.9 用同一手法解决（`089:ui/IslandSettingsMenu.kt:14-17` 的 `keepOpen()`），
     * 我们照做。
     *
     * ## 为什么用 client property 而不是"给每个项换一个 UI 子类"
     *
     * [styleComponent] 在 `apply` 里**逐组件**决定用哪个 `MenuItemUi`。若把 keepOpen
     * 做成两个 UI 类（`MenuItemUi` / `KeepOpenMenuItemUi`），那么"哪些项该用哪个"
     * 这个信息就得在**构建菜单的那一侧**（[dev.cdl.window.IslandPopupMenu]）
     * 通过换 UI 来表达 —— 而那里根本不该知道 UI 类名。
     * client property 让构建侧只说"这一项要 keepOpen"，皮肤侧自己去读，
     * **两侧的耦合面就只剩一个字符串常量**（[KEEP_OPEN_KEY]）。
     *
     * ⚠️ 与原实现同名同义（`089:ui/Windows11PopupStyle.kt:48`），便于日后对照。
     */
    internal const val KEEP_OPEN_KEY = "cdl.menu.keepOpen"

    /** 把本项标为「点击后不关菜单」。见 [KEEP_OPEN_KEY] 的 KDoc。 */
    internal fun <T : JMenuItem> T.keepOpen(): T {
        putClientProperty(KEEP_OPEN_KEY, true)
        return this
    }

    /** 本项是否被标记为 keepOpen。 */
    private val JMenuItem.keepOpenRequested: Boolean
        get() = getClientProperty(KEEP_OPEN_KEY) == true

    /**
     * 中间列（标签与行右缘之间那段）的**左边缘**，坐标相对 [item] 本身。
     *
     * 与原实现同一套几何：`left = rowStart + 标签字宽 + rangeGap`，
     * `right = item.width - middleColumnInset`，状态字形画在 `[left, right)` 的正中。
     * 见 `089:ui/Windows11PopupStyle.kt:355-358` 与 [rangeGap] 的 KDoc。
     *
     * ⚠️ **`item.width` 为 0 时（尚未布局）本函数会算出负数左边缘。**
     * 调用点都在 `paint` 里，那时组件已被布局，不会是 0；但为了让这个前提
     * 显式化（而不是靠"paint 一定在布局之后"的隐式约定），这里对结果做了
     * "不小于 0"的钳制 —— 代价是一次 `coerceAtLeast`，
     * 换来的是"万一布局顺序变了也不会画到组件外面去"。
     */
    private fun middleColumnLeft(item: JMenuItem): Int {
        // ⚠️ 用 [drawnLabelWidth]（折行后取最宽的一行）而不是 `stringWidth(item.text)`：
        // 折行之后"整串宽度"会远大于实际画出来的宽度，用它会把中间列推出行外
        // —— 见 [drawnLabelWidth] 的 KDoc（后果是勾号贴到行右缘、L&F 箭头重新露出来）。
        val labelEnd = rowStart + drawnLabelWidth(item)
        return (labelEnd + rangeGap).coerceAtLeast(0)
    }

    //
     // 多行标签的绘制。**返回 `true` 表示"已经画完了，调用方不要再调 `super`"**。
     //
     // ## 为什么是一个共享的顶层私有函数，而不是某个 UI 类的方法
     //
     // 两类菜单行需要它，而它们的父类**不同**：
     // · 普通项 → [MenuItemUi]（`BasicMenuItemUI` 的子类）
     // · 子菜单行 → [DelayedMenuUi]（`BasicMenuUI` 的子类，必须保留它，见 [styleComponent]）
     // 两者无法共享一个"带 `disabledForeground` / `selectionForeground` 字段"的基类
     // （那两个字段是 `BasicMenuItemUI` 的 protected 字段，只有子类能读）。
     // 故把颜色做成参数传进来 —— 于是"什么时候换行、怎么居中、用什么颜色"
     // **只有这一处定义**，两类行不可能漂移。
     //
     // ## 为什么可以在这里画多行（而不是必须重写 `paintMenuItem`）
     //
     // `BasicMenuItemUI.paintMenuItem` 把文本绘制**单独**委派给
     // `paintText(Graphics, JMenuItem, Rectangle, String)`（Temurin 21
     // `plaf/basic/BasicMenuItemUI.java:697,725-736`），那是个 `protected` 方法。
     // 覆写它即可接管文本，而背景 / 勾选图标 / 箭头 / 快捷键的绘制**完全不动** ——
     // 改动面比"重写 paintMenuItem"小一个量级（后者要把上面那五件事重写一遍，
     // 漏一件的表现是"某个状态下的菜单项看起来不对"）。
     //
     // ## 垂直居中：**整块**居中（与 [MenuStepper] 的 ± 保持同一取舍）
     //
     // 多行文本作为**一个整块**在行内垂直居中（`y0 = (行高 - 块高) / 2`），
     // 而不是"首行对齐单行时的基线、其余行往下挂"。理由：同一行里还有
     // 状态字形 / 子菜单箭头 / 步进器，它们都是按**整行高度**居中的
     // （`(item.height - glyphBox) / 2`）。若文本按首行对齐，2 行标签的
     // 第一行会与勾号错开半行 —— 看起来像"勾号浮在字的中间"。
     // **整块居中 = 文本块、勾号、箭头、步进器共用同一条中轴**，这是唯一自洽的取舍。
     //
     // @param textRect `MenuItemLayoutHelper` 算出的文本矩形。只用它的 `x`
     //   （= `insets.left` = [rowStart]）；`y` **不用** —— 它是按单行算的，
     //   多行时我们自己按整块居中算。
     //
    /**
     * 多行绘制菜单标签。
     *
     * ## ⚠️ 两个前景色参数**必须可空**（2026-09-18 真机崩溃后改）
     *
     * **原记**：`disabledFg: Color` / `selectedFg: Color`（Kotlin 非空）。
     * **实为**：它们来自 `BasicMenuItemUI` 的 `protected Color disabledForeground`
     *   / `selectionForeground` —— **Java 字段，可以是 null**。
     *   Kotlin 把 Java 字段当 **platform type**，于是**编译通过、运行时才 NPE**：
     *
     *   ```
     *   java.lang.NullPointerException: disabledForeground must not be null
     *       at IslandMenuSkin$DelayedMenuUi.paintText(IslandMenuSkin.kt:668)
     *   ```
     *
     * **为什么 `MenuItemUi` 那条路径不崩**：它自己在 `:2482` 显式设了
     *   `disabledForeground = muted`；而 `DelayedMenuUi`（`JMenu` 用，保留 `BasicMenuUI`）
     *   **没设**，于是用基类从 `UIManager.getColor("MenuItem.disabledForeground")`
     *   取到的值 —— **当前 L&F 下该键不存在，返回 null**。
     *
     * **为什么探针拦不住**：这是 Swing 的 **paint 路径**，无头环境碰不到窗口；
     *   848 项断言全绿，真机一开菜单就崩。**教训：paint 路径必须有 null 兜底，
     *   因为它的输入来自 L&F 这个"运行时可变的全局状态"。**
     *
     * 兜底顺序：传入值 → `menuItem.foreground` → 一个安全常量。
     * `menuItem.foreground` 是 Swing 保证非空的（`JComponent.getForeground()` 有默认值）。
     */
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
        // 颜色规则与 `BasicMenuItemUI.paintText` 逐条对齐（禁用 → 选中 → 常态）。
        // 显式设色而不是依赖 `g` 上残留的颜色：那正是"某些行的字色偶尔不对"的成因。
        //
        // ⚠️ 三级兜底：L&F 没定义 `MenuItem.disabledForeground` 时它是 null，
        //    直接用会 NPE（见上方 KDoc 的真机崩溃）。`menuItem.foreground` 由
        //    `JComponent` 保证非空，故它总是能兜住。
        val fallback = menuItem.foreground ?: Color.GRAY
        g.color = when {
            !menuItem.isEnabled -> disabledFg ?: fallback
            // ★★★ 2026-10-17（**BUG-217**）：**与背景高亮走同一条判据** ——
            //   ★ 原实现读裸 `isArmed` / `isSelected`（都是**状态**）⇒
            //     背景高亮已经消失、字却还是"选中色" ⇒ **同一个残留在两处表现不一致** ✗
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

    /**
     * 本行的**装饰物列布局**（勾选字形与子菜单箭头各自的 x）。
     *
     * ⚠️ 2026-09-17：算术搬到 [middleColumnGlyphX]（公开纯函数，可被探针断言）——
     * 原实现是照抄既有的一行公式，而它**假设标签不会太长**：
     * 标签一长 `left` 就超过 `right`，`(right - left - glyphBox)` 变负，
     * 字形被推到**标签上面** —— 正是用户报的"字与勾选状态提示重叠"。
     *
     * ⚠️ 2026-09-21：再上移到 [menuDecorationX]。原因是**"勾选与箭头同列"这件事本身**：
     * 两者原先都调 [middleColumnGlyphX]（居中于同一个中间列），
     * 于是**只要它们出现在同一行，算出来的 x 就逐像素相同**。
     * 现在"两种装饰物都在"时各占一格，位置由那一个函数统一分配（见其 KDoc）。
     */
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

    /**
     * 在**中间列**画状态字形（勾选 / 单选）。
     *
     * ## 位置与原实现**逐像素一致**（2026-09-16 修正）
     *
     * **原记**：本实现曾把字形锚在**行尾**，理由是"我们的菜单没有步进器，
     * 照抄中间列会让勾浮在行中间、右边空一大块"。**这条理由是错的**，
     * 已按用户要求回改为中间列 —— 用户要的是"外观与 0.8.9 一致"，
     * 而"右边看起来空"正是原实现菜单的真实观感（那片空位就是步进器的位置）。
     *
     * 原实现公式（`089:ui/Windows11PopupStyle.kt:353-361`）：
     * ```
     * left  = rowStart + labelEnd + rangeGap
     * right = item.width - (rangeGap + stepperSlotWidth + stepperGap + stepperSlotWidth + rowEnd)
     * x     = left + (right - left - glyphBox) / 2
     * y     = (item.height - glyphBox) / 2
     * ```
     * 本实现即此式（`middleColumnInset` 见其 KDoc）。
     *
     * ⚠️ 注意 `right` 里**没有** `glyphGap`：原实现那三个槽位宽量的右边界就是
     * `stepperSlotWidth + rowEnd`，`glyphGap` 只在 [styleItem] 计算**行内边距**时用。
     * 这里照原实现，不"顺手补上" `glyphGap`。
     */
    private fun paintStateGlyph(graphics: Graphics, item: JMenuItem) {
        if (!item.isStateItem) return
        // ⚠️ 这里的 `graphics` 是**本行自己的**（`MenuItemUi.paint` 的入参），
        //    故"行顶边在坐标系里的 y"就是 **0**。
        //    与 [PopupUi.paintSubmenuArrows] 那边**恰好相反** —— 那边拿到的是
        //    popup 的 `Graphics`，必须把 `child.y` 加回去（本 BUG 的根因）。
        //    两处的差别现在只体现在传给 [menuDecorationY] 的 `rowTopY` 上。
        val x = decorationX(item).checkX ?: return
        val y = menuDecorationY(rowTopY = 0, rowHeight = item.height, glyphHeight = glyphBox)
        MenuGlyph().paintIcon(item, graphics, x, y)
    }

    /**
     * 菜单底板：**方形直角**、整矩形填充 + 1px 描边。
     *
     * ## 为什么不做圆角（这条不是偷懒，是硬约束）
     *
     * 圆角只能靠**窗口形状裁剪**实现，而被裁掉的那部分会露出**下方的桌面**。
     * 在深色壁纸下，深色菜单的四角被裁掉后露出的桌面会被读成"菜单有个黑边"；
     * 在浅色模式下则是一圈与菜单不同色的杂边。**方形直角是唯一不留破绽的画法。**
     *
     * 根因是 `JPopupMenu` 的 heavyweight popup 窗口**不是 layered 窗口**
     * （实测 `EXSTYLE = 0x08000008`，不含 `WS_EX_LAYERED`）→ 拿不到逐像素 alpha。
     * 原实现 `089:ui/Windows11PopupStyle.kt:397-402` 的注释给出了同样的结论，
     * 而且它是在**同一前提**下得出的（`recon/08` §3.6.3 的实测表）。
     * → 所以「顺手改成圆角」会立刻退化成黑边，**不要改**。
     */
    private class PopupUi : BasicPopupMenuUI() {
        override fun paint(graphics: Graphics, component: JComponent) {
            val g = graphics.create() as Graphics2D
            // ★★★ 2026-10-15：底板与描边都用**带 alpha 的**颜色 —— 透明度只作用在**背景**上。
            //
            // ★★ 2026-10-15（**§3.74 更正**）：原记「悬停高亮条不走这里、用 `hover` 的
            // **全不透明**颜色」—— ★ **实为缺陷**（用户报「划过后变灰，不透明」）⇒
            // 悬停高亮条 / 步进器数值框 / 框内选区 / 分隔线**同样**是背景 ⇒ 一律走
            // [menuFill]（唯一出口）。**前景**（文字 / 箭头 / 勾号 / 光标 / 强调描边）
            // 仍保持全不透明 ⇒ 用户要的"背景透、字清楚"由此成立 ✓
            //
            // ⚠️ 逐像素透明不可用时**退回不透明的 surface**：那时窗口底色是不透明的，
            //    再叠一层半透明色只会得到"更浅的实色"（看起来像调错了颜色，而不是变透）。
            //    [menuFill] 内置了这条闸（与底板同进同退）。
            g.color = menuFill(surface)
            g.fillRect(0, 0, component.width, component.height)
            g.color = menuFill(outline)
            g.drawRect(0, 0, component.width - 1, component.height - 1)
            g.dispose()
            super.paint(graphics, component)
            // 子菜单行的箭头在这里补画 —— `JMenu` 保留 `BasicMenuUI`（见 styleComponent 的
            // KDoc），它的 `paint` **不经过**我们的 [MenuItemUi]，故不能在那边画。
            //
            // ⚠️ 2026-09-21 更正一处**错误注释**：这里原先写着"放在 `super.paint` 之后：
            //    箭头要压在行上，先画会被行内容盖住"—— **不成立**。
            //    `super.paint` 是 `BasicPopupMenuUI.paint`（**空实现**，它不画子组件），
            //    所以这段代码整体仍在 `paintComponent` 阶段 → 行（子组件）**永远画在
            //    我们之后**（`JComponent.paint`：paintComponent → paintBorder → paintChildren）。
            //    结论：本函数画的箭头**压在行下面**，只是行大多不透明区域为空
            //    （`MenuItemUi.paintBackground` 只在 armed 时填色），故看得见。
            //    这**不影响**箭头可见性，但决定了"用底色盖 L&F 箭头"那条路走不通
            //    （见 [paintSubmenuArrows] 的 KDoc）。
            paintSubmenuArrows(graphics, component)
        }

        /**
         * 给本菜单里的每个 `JMenu` 行画**中间列**的子菜单箭头。
         *
         * ## 形状对齐原实现（2026-09-16 修正）
         *
         * **原记**：本实现曾自绘两段线 + `muted` 色 + 行尾。**已按用户要求回改** ——
         * 原实现用的是**字体字符 `›`（U+203A）+ `accent` 色**：
         * `089:ui/Windows11PopupStyle.kt:518-521`
         * ```
         * g.font = g.font.deriveFont(Font.BOLD, 12f)
         * g.drawString("\u203A", x + 5, y + 13)
         * ```
         * 位置也是**中间列**（原实现 [MenuUi] 的 `paint`，`:429-434`，
         * 与状态字形同一套 `left/right` 公式）。
         *
         * ⚠️ 字符版箭头依赖**字体里有 U+203A**：候选字体（Microsoft YaHei UI /
         * Segoe UI Variable Text / Segoe UI）都含它，但若将来换字体，这里会变成豆腐块。
         * 故在画之前用 `canDisplay` 判一次，不能显示则回退到自绘折线
         * （形状与本实现早期版本一致）—— **宁可画得不像，也不能画成豆腐块**。
         *
         * ## L&F 那支箭头是怎么消掉的（**2026-09-21 修正**）
         *
         * **原记**：*"先用底色盖一块抹掉它，再画我们自己的"* ——
         * 于是本函数里有一段 `g.color = surface; g.fillRect(left, child.y, …)`。
         * **那段从来没能盖住任何东西**，已删除：
         *
         * `PopupUi.paint` 是**组件自身的 `paintComponent` 阶段**（`JComponent.paint`
         * 的顺序是 `paintComponent` → `paintBorder` → `paintChildren`，
         * Temurin 21 `javax/swing/JComponent.java:1128-1141`），而 L&F 的箭头是
         * **菜单项自己**画的（`BasicMenuItemUI.paintMenuItem` 的最后一步
         * `paintArrowIcon`，`plaf/basic/BasicMenuItemUI.java:700`）——
         * 也就是说它属于 `paintChildren`，**永远画在我们的覆盖矩形之后**。
         * 离线实测（`JPopupMenu` + 自定义 `PopupMenuUI` 涂蓝、子项涂红，
         * 逐像素取色）：**子组件在上**。故"盖掉"这条路在结构上就不成立。
         *
         * **现在怎么做**：在 [DelayedMenuUi.installDefaults] 里 `arrowIcon = null`
         * （`arrowIcon` 是 `BasicMenuItemUI` 的 `protected` 字段，子类写得动 ——
         * 原 KDoc 说"没法置空"是把"`JMenu` 实例上没有这个成员"错当成了
         * "子类不能写这个字段"）。字段为空则 `paintArrowIcon` 直接跳过，
         * L&F 的三角**根本不存在**，也就没有"盖"这件事了。
         *
         * ⚠️ 顺序上必须先做这一步：**修好 y 之后，L&F 的三角与我们的 `›` 会落在
         *  同一行上**（此前 L&F 的在行尾、我们的在第一行，反而"错开"了）——
         *  不置空字段的话，修 y 会把"两个箭头"从"跨行"变成"同一行"，
         *  看起来更像缺陷。
         */
        private fun paintSubmenuArrows(graphics: Graphics, component: JComponent) {
            for (child in component.components) {
                if (child !is JMenu || !child.isVisible) continue
                val g = graphics.create() as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // 与状态字形同列：中间列的左边缘起到行右缘的内缩处。
                val left = middleColumnLeft(child)
                // x 走与勾选字形**同一套列分配**（见 [menuDecorationX]）——
                // 本行"有箭头、无勾选"，故与旧值逐像素相同；
                // 但"两者都在同一行"时各占一格，**不可能再叠在一起**。
                val x = menuDecorationX(
                    itemWidth = child.width,
                    labelEnd = left,
                    columnInset = middleColumnInset,
                    glyphWidth = glyphBox,
                    rangeGap = rangeGap,
                    hasCheck = false,
                    hasArrow = true,
                ).arrowX ?: continue
                // ⚠️⚠️ **y 必须带上 `child.y`**（2026-09-21 修，本 BUG 的根因）。
                //
                // `g` 是 **popup** 的坐标系，而 `(child.height - glyphBox) / 2`
                // 是**行内**偏移。少了 `child.y` 的后果不是"箭头画歪一点"，而是
                // **一个 popup 里所有箭头算出同一个 y** —— 全部叠在 popup 的
                // **第一行**上。而那一行恰好是「显示实时频谱」（`spectrum.enabled`，
                // SWITCH，默认开 → 有勾），勾与箭头又共用中间列
                // → 两者**逐像素重合**（用户截图里 `✓` 与 `›` 叠在一起）。
                //
                // 见 [menuDecorationY] 的 KDoc（那里把这条坐标系规则写成了
                // 具名函数，并被 `MenuProbe` 断言）。
                val y = menuDecorationY(rowTopY = child.y, rowHeight = child.height, glyphHeight = glyphBox)
                // ② 画我们自己的箭头。
                //
                // ⚠️ L&F 那一支**不需要**在这里处理 —— 它由
                //    [DelayedMenuUi.installDefaults] 的 `arrowIcon = null` 从源头消掉。
                //    这里原先有一段"用底色盖掉它"的 `fillRect`，**已删除**：
                //    它在 `paintComponent` 阶段，而 L&F 的箭头在 `paintChildren` 阶段，
                //    永远盖不住（见本函数的 KDoc）。
                g.color = accent
                val arrowFont = child.font.deriveFont(Font.BOLD, 12f)
                if (arrowFont.canDisplay('\u203A')) {
                    g.font = arrowFont
                    g.drawString("\u203A", x + 5, y + 13)
                } else {
                    // 回退：自绘折线（与早期版本同形），保证不出现豆腐块。
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

    /**
     * **内联步进器**：菜单里就地调数值设置（`− [值] +`）。
     *
     * ## 为什么必须自己造一个组件（而不是用 `JSlider` / 弹输入框）
     *
     * 原实现 0.8.9 的菜单之所以"设置项够用"，关键在于它把 5 个数值项
     * （字号 / 透明度 / 最大宽度 / 歌词偏移 / 圆角）**直接放进了菜单**，
     * 靠的就是这个 `− [值] +` 行（`089:ui/StepperItem.kt`）。
     * 没有它，数值项在 `JPopupMenu` 里**没有任何可用的宿主**：
     * - `JSlider` 放菜单里没有行高概念，会撑高整行、且拖动与菜单的
     *   `MenuSelectionManager` 抢事件；
     * - `JOptionPane` 输入框要另开窗口，等于"菜单只是个入口"，
     *   与用户"右键就能调"的诉求相悖。
     *
     * ## 与既有的**有意偏离**（逐条给理由，不是漏做）
     *
     * | 原实现能力（`089:ui/StepperItem.kt`） | 本实现 | 为什么 |
     * | --- | --- | --- |
     * | 点数值框进入**内联文本编辑**（光标闪烁、选区、退格、`WH_KEYBOARD_LL` 键盘钩子接管，`:193-511`） | **不做** | 那套东西要一整套键盘捕获基础设施（`KeyboardCapture` / `platform/KeyboardHook`，`recon/08` 的 M3），而它**只服务于"输入一个精确值"**这一个场景。点击/长按 ± 已能覆盖"快速微调"；精确值走设置页（那里有原生 `seekbar`）。**为一个次要交互引入全局键盘钩子，风险收益不成比例** —— 钩子装不上或与宿主输入链冲突的代价，远大于"少一个文本编辑框"。 |
     * | 范围提示居中于中间列，**按需缩小字号**（`:549-566`） | **照做** | 这行提示（如 `8–200`）是"这一项能调到多少"的唯一线索 —— 没有它用户只能靠点。缩小字号的循环照抄（下限 9pt，`:89`）。 |
     * | 长按 ± **自动重复**（`:206-226`） | **照做** | 从 22 调到 40 要按 18 次，没有连点这项就等于不可用。参数同原实现（首延迟 400ms / 间隔 110ms）。 |
     * | 鼠标滚轮调值 | **新增**（原实现没有） | 滚轮是 Windows 上最自然的"微调"手势，而 `JPopupMenu` 里滚轮**没有任何默认行为**（菜单不可滚动），所以这个事件是**白捡的** —— 不会与任何东西冲突。 |
     * | `syncMenuSelection()`：把选中路径指向自己所在的菜单链，折叠掉别的子菜单（`:167-191`） | **照做** | 步进器不是 `MenuElement`，悬停它**不会**更新 Swing 的选中路径 → 于是"先展开了一个子菜单、再把鼠标移到步进器上"时，那个子菜单会**挂在屏幕上不走**。原实现为此写了全局轮询监视器（`Windows11PopupStyle.startSubmenuWatcher`），本实现用原实现同一段 `syncMenuSelection` 但**挂在步进器自己身上** —— 效果等价，且**不需要轮询线程**。 |
     * | `preferredSize = (270, h)` | **改为 380**（2026-09-18） | 270 是原实现取值，但它只保证"± 不会挤到标签上"，**不保证标签放得下**：270 下标签可用宽度仅 144px，而本注册表最长标题约 199px → 必然截断（用户报的「许多选项设置项名字显示不全」）。理由与算式见 [ROW_W] 的 KDoc。 |
     * | 标签单行、超长即 `ellipsize` 截断 | **先折行、再截断兜底** | 用户诉求「**多行显示**」+「**不要侵占其他设置项**」。折行由 `MenuPolicy.wrapMenuLabel` 负责，行高按**本项行数**增长（[menuRowHeightForLines]），单行的项一分钱不多占。最后一层 `ellipsize` 保留 —— `wrapMenuLabel` 最多折 [MENU_LABEL_MAX_LINES] 行，最后一行仍可能超宽。 |
     *
     * ## 与 [IslandMenuSkin] 的几何关系（**唯一事实来源**）
     *
     * `−` / 值 / `+` 三个槽位的坐标由本对象的 [rangeGap] / [stepperSlotWidth] /
     * [stepperGap] / [rowEnd] 算出（见 [minusX] 等）。**不是巧合，是必须** ——
     * 中间列的状态字形与子菜单箭头用的正是同一组常量（[middleColumnInset]），
     * 两者不一致会让字形压在步进器上。
     *
     * 原实现把这三个常量写成 `internal const` 供 `StepperItem` 引用（`recon/08` 的坑 P5
     * 记录了"两侧各算一遍列坐标"这个结构性弱点）；本实现把步进器放在**同一个对象内部**，
     * 于是两处漂移在结构上不可能发生 —— 比"暴露常量"更强。
     *
     * @param label 行标签（左对齐，与其余菜单行同一列）。
     * @param min / @param max 取值域（含端点），与 `SettingDescriptor.range` 同源。
     * @param step 一次点击的步长。
     * @param unit 单位后缀（`%` / `px` / `ms`），空串表示无单位。
     * @param readValue **每次重绘都调用** —— 传的是 `SettingsRuntime.live` 的读取，
     *   故它与设置页/其他入口的改动**永远一致**，不存在"菜单里显示的是旧值"。
     * @param writeValue 落盘。由调用方决定走 `apply`（生效 + 落盘一次事务）。
     */
    internal class MenuStepper(
        // ⚠️ 刻意**不是 `private`**（2026-09-23）：步进器形态的菜单项 `JMenuItem.text` 是空串
        //    （标题画在本组件里），于是「三级菜单项的可见标题」在探针眼里**读不出来**。
        //    本类是 `internal` ⇒ 去掉 `private` **不会**扩大对外 API，只是让同模块的
        //    `menuItemVisibleTitle`（`MenuPolicy.kt`）能读它，从而让
        //    `MenuProbe` §J4b 断言「可见标题 == `menuItemTitleFor(descriptor)`」**真的成立**。
        //    （反面做法：在 `stepperItem` 里把自己传的 title 打成 client property 再断言 ——
        //     那是**断言自己刚写下的值**，恒真，属于假绿。）
        val label: String,
        private val min: Int,
        private val max: Int,
        private val step: Int,
        private val unit: String,
        private val readValue: () -> Int,
        private val writeValue: (Int) -> Unit,
    ) : JComponent() {

        private var hovered = false

        // ── 内联数值编辑（2026-09-17，对齐原实现 0.8.9）──────────────────────────
        //
        // 用户原话：「右键菜单的交互逻辑没有做到灵动岛 0.8.9 的
        //   **允许对步进器内数值编辑**」。
        //
        // ⚠️ **不能用 `JTextField`** —— 原实现 KDoc 的实测结论：
        //   在 popup menu 里，子组件的文本框会**静默丢掉每一个按键**。
        //   故本实现与原实现同法：**全局 `KeyEventDispatcher`** 路由 + 自维护缓冲/光标/选区。
        //   纯逻辑在 `MenuPolicy.kt`（`stepperEditor*` 一族），本组件只管接线与绘制。
        //
        // ⚠️ **未实现既有的 Win32 低级键盘钩子**（`WH_KEYBOARD_LL`）。
        //   原实现有它，是为了覆盖"按键根本没到本进程"（别的程序有焦点）的情形。
        //   代价：**编辑时若焦点在别的程序上，按键会落到那个程序**（我们收不到）。
        //   这是本轮**有意的取舍**（那套钩子的工作量与风险远超本条），已如实记入报告。

        /** 内联编辑器状态。`null` = 未编辑。 */
        private var editor: StepperEditor? = null

        /** 光标是否处于"亮"的半周期。 */
        private var caretOn = true

        /**
         * 光标闪烁。**只在编辑中运行** —— 与连点定时器同一纪律：
         * 组件不可见时必须停（否则是一个没人能停的 `javax.swing.Timer`，真实的内存泄漏）。
         */
        private val caretBlink = Timer(CARET_BLINK_MS) {
            caretOn = !caretOn
            repaint()
        }

        /** 当前按住的 ± 方向（`0` = 没按住）。 */
        private var repeatDirection = 0

        private val repeatTimer = Timer(STEPPER_REPEAT_INTERVAL_MS.toInt()) { repeatStep() }.apply {
            isRepeats = true
            initialDelay = STEPPER_REPEAT_DELAY_MS
        }

        /** 值框宽度。由 [useRowHeight] 按字体度量重算。 */
        private var valueW = DEFAULT_VALUE_W

        init {
            isOpaque = false
            // 不可聚焦：菜单里 Tab/方向键的焦点链归 MenuSelectionManager，
            // 一个能抢焦点的子组件会让键盘导航在半路断掉。
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
                        // ⚠️ 2026-09-17：点**数值框**进入内联编辑（用户要求，对齐 0.8.9）。
                        in valueX()..valueX() + valueW -> beginEdit()
                    }
                }

                override fun mouseReleased(event: MouseEvent) = stopRepeat()
            })
            addMouseWheelListener { event: MouseWheelEvent ->
                // 滚轮上 = 加（与 Windows 滑杆/微调框的直觉一致：向上 = 变大）。
                val direction = if (event.wheelRotation < 0) 1 else -1
                // 编辑中滚轮 → **先提交**再步进（与点 ± 同一处理：不丢用户刚打的数）。
                closeEditor(commit = true)
                step(direction)
                event.consume()
            }
            // 菜单关闭时本组件被隐藏 —— **必须停掉连点定时器与光标闪烁**，
            // 否则连点会继续对已不可见的行写设置（用户会看到"关掉菜单后数值还在涨"），
            // 而闪烁会一直跑（`javax.swing.Timer` 没人停 = 真实的内存泄漏）。
            //
            // ⚠️ 2026-09-17：这里还必须**注销全局按键路由**（`closeEditor` 里做）——
            //    否则菜单关了、编辑器还在，之后用户在任何地方打字都会被它吞掉。
            addHierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !isShowing) {
                    stopRepeat()
                    // 提交（而不是放弃）：用户"打到一半点了别处"时，
                    // 提交比丢掉更符合直觉 —— 与点 ± 的处理一致。
                    closeEditor(commit = true)
                }
            }
        }

        /**
         * 标签折行后的每一行。`size <= 1` = 未折行（按单行绘制）。
         *
         * 由 [useRowHeight] 写入 —— 它同时用行数算出了本行的实际行高，
         * 故"画几行"与"留多高"必然一致（不可能出现"留了一行的高度却画两行"）。
         */
        private var labelLines: List<String> = emptyList()

        /**
         * 采用菜单的自适应行高（见 [rowHeightFor]），并据此重算字体 / 值框宽度 / **标签折行**。
         *
         * 由 [styleComponent] 调用 —— 与其余菜单行的 [styleItem] 是**同一个行高入参**，
         * 故步进器行不会比旁边高一行或矮一行。
         *
         * ## 返回值 = 本行**实际需要**的行高（≥ 入参）
         *
         * 标签放不下时本行要变高（用户诉求「多行显示」+「不要侵占其他设置项」）。
         * 调用方（[styleComponent]）拿到这个数之后要**把外层 `JMenuItem` 也一起加高** ——
         * 因为步进器是被包在 `JMenuItem` 里的（`BorderLayout.CENTER`），
         * 外层高度不变的话，步进器画出来的第二行会被外层裁掉。
         * 返回而不是让调用方再算一遍：折行规则只有一处定义。
         *
         * @return 本行实际行高（单行时**等于** [height]，即一分钱都不多占）
         */
        internal fun useRowHeight(height: Int): Int {
            font = fontFor(height)
            val metrics = getFontMetrics(font)
            // 值框要放得下取值域两端（`-2000` 比 `1200` 宽）与当前值（含单位）。
            val needed = maxOf(
                metrics.stringWidth("$min"),
                metrics.stringWidth("$max"),
                metrics.stringWidth("${readValue()}$unit"),
            )
            valueW = (needed + 16).coerceIn(DEFAULT_VALUE_W, MAX_VALUE_W)
            // ⚠️ 顺序要紧：折行要按**最终的 valueW** 算可用宽度（valueW 刚刚才定下来）。
            labelLines = wrapMenuLabel(label, assumedLabelMaxWidth()) { metrics.stringWidth(it) }
            val rowHeight = menuRowHeightForLines(height, labelLines.size)
            preferredSize = Dimension(ROW_W, rowHeight)
            minimumSize = preferredSize
            maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
            revalidate()
            repaint()
            return rowHeight
        }

        /**
         * 折行时**假定**的标签可用宽度（像素）。
         *
         * ## 为什么用 [ROW_W] 而不是 `width`
         *
         * [useRowHeight] 在**布局之前**被调用（`styleComponent` 里），那时 `width == 0`，
         * 按它算会得到负的可用宽度、折行逻辑直接退化。
         *
         * 而布局之后步进器的实际宽度 = 外层 `JMenuItem` 宽度 - 它的左右内缩，
         * 后者 **≥ [ROW_W]**（菜单内容宽度取各项最小宽度的最大值，而本项的最小宽度
         * 就是 `ROW_W + 内缩`）。故按 [ROW_W] 算是**偏保守**的：折出来的行在真实宽度下
         * 一定放得下（最坏情况是"本可以单行却折了两行"，那只是更好看，不会溢出）。
         *
         * ⚠️ 与 [minusX] 共用同一个算式（传 `ROW_W` 进去），不另写一份几何 ——
         * 否则"折行用的宽度"与"实际画标签的宽度"会各算各的，某天就会漂移成
         * "折了行但第二行压在 `−` 上"。
         */
        private fun assumedLabelMaxWidth(): Int = (minusX(ROW_W) - rangeGap - rowStart).coerceAtLeast(0)

        // ── 槽位坐标（与中间列状态字形共用同一组常量，见类 KDoc） ──────────────
        //
        // ⚠️ 三个函数都带一个 `w` 参数（默认 = 本组件当前宽度）：[assumedLabelMaxWidth]
        //    要在**布局之前**按 [ROW_W] 试算一遍，而绘制与命中测试要用**真实宽度**。
        //    同一份算式，两个入参 —— 见 [assumedLabelMaxWidth] 的 KDoc。

        private fun plusX(w: Int = width) = w - rowEnd - stepperSlotWidth
        private fun valueX(w: Int = width) = plusX(w) - stepperGap - valueW
        private fun minusX(w: Int = width) = valueX(w) - stepperGap - stepperSlotWidth

        /**
         * 把 Swing 的选中路径指向**本行所在的那条菜单链**。
         *
         * 见类 KDoc 的偏离表：步进器不是 `MenuElement`，悬停它不会更新选中路径，
         * 于是"先展开一个子菜单、再把鼠标移到步进器上"会让那个子菜单挂在屏幕上。
         * 手动把路径设回本行所在的链，Swing 就会自动折叠掉别的子菜单。
         *
         * `selectedPath.lastOrNull() === popup` 时直接返回：那是**已经在正确路径上**的
         * 常见情形（鼠标在同一个菜单里移动），不必要地重设路径会让菜单闪一下。
         */
        private fun syncMenuSelection() {
            val popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, this) as? JPopupMenu
                ?: return
            val manager = MenuSelectionManager.defaultManager()
            if (manager.selectedPath.lastOrNull() === popup) return
            manager.setSelectedPath(menuPath(popup))
        }

        /** `[顶层 popup, …, 父 JMenu, 本 popup]` —— 让本行所在 popup 保持展开的那条链。 */
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

        // ── 内联编辑 ──────────────────────────────────────────────────────

        /** 打开内联编辑器（点击数值框）。已打开时**重新全选**（与真实文本框的再次点击一致）。 */
        private fun beginEdit() {
            stopRepeat()
            editor = stepperEditorOpen(readValue(), selectAll = true)
            caretOn = true
            attachKeyRouter()
            caretBlink.restart()
            repaint()
        }

        /**
         * 关闭编辑器。
         *
         * @param commit `true` = 提交缓冲里的值（`Enter` / 点了 ± / 菜单关闭时的收尾）；
         *   `false` = 放弃（`Esc`）。
         *
         * ⚠️ **无论哪条路径都必须调用它** —— 否则全局 `KeyEventDispatcher` 会一直挂着，
         * 之后用户在**任何**地方打字都会被这个已经不存在的编辑器吞掉。
         * 调用点有四处：`Enter` / `Esc` / 点 ± 之前 / 组件不可见时。
         */
        private fun closeEditor(commit: Boolean) {
            val current = editor ?: return
            editor = null
            caretBlink.stop()
            detachKeyRouter()
            if (commit) {
                // 解析失败（空串 / 只有 `-` / 打了一堆非数字）→ **什么都不做**。
                // 那正是"用户打错了然后回车"应有的结果：不改设置，而不是猜一个值。
                stepperEditorCommitValue(current.buffer)?.let { value ->
                    // 越界在这里夹一次（用户可能打出 99999）。
                    // 与 `step()` 用同一个 `coerceIn(min, max)` —— 两处规则一致。
                    val clamped = value.coerceIn(min, max)
                    if (clamped != readValue()) {
                        runCatching { writeValue(clamped) }
                            .onFailure {
                                System.err.println("[CDL] 菜单步进器写入失败（$label=$clamped）：$it")
                            }
                    }
                }
            }
            repaint()
        }

        /**
         * 注册全局按键路由。
         *
         * ## 为什么是"全局"的
         *
         * 因为**没有焦点可依附** —— 本组件 `isFocusable = false`
         * （那是刻意的：菜单的键盘导航归 `MenuSelectionManager`，抢焦点会让它断掉）。
         * 于是按键只能从 `KeyboardFocusManager` 的 dispatcher 上接。
         *
         * ## 为什么要先把上一个关掉
         *
         * `activeStepper` 是**静态单例** —— 同时只允许一个编辑器在跑。
         * 用户在 A 行编辑到一半又点 B 行的数值框时，
         * A 必须**先提交并注销**，否则 A 的 dispatcher 还在、且会抢走 B 的按键。
         */
        private fun attachKeyRouter() {
            if (activeStepper === this) return
            activeStepper?.closeEditor(commit = true)
            activeStepper = this
            runCatching {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyRouter)
            }
        }

        /** 注销全局按键路由。**幂等** —— 只有"当前是它"时才真的注销。 */
        private fun detachKeyRouter() {
            if (activeStepper !== this) return
            activeStepper = null
            runCatching {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyRouter)
            }
        }

        /**
         * 按键路由（全局 dispatcher 的回调，**在 EDT 上**）。
         *
         * @return `true` = **吞掉**这个按键（不再往下传）。编辑期间可打印字符必须吞 ——
         *   否则会漏到菜单后面的播放器窗口（用户会看到"我在菜单里打字，播放器在搜索"）。
         */
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
                        // 其它可打印字符：**吞掉但不改状态**（理由见上面 @return）。
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

        // ── 步进 ──────────────────────────────────────────────────────────

        /**
         * 连点已持续的时长（毫秒）—— 用于**长按加速**（2026-09-17 新增）。
         *
         * 用户原话：「步进器**没有做到最细粒度**（数值增减逻辑可参考 0.8.9）」。
         *
         * ## 为什么"最细粒度"必须配"长按加速"，两者缺一不可
         *
         * 只把步长改成 1（最细）会让 `controls.autoHideMs`（0..10000 量级）
         * **按到手指酸也走不到头**；只做加速而不改步长，则用户永远调不出"差 1"的值。
         * 故：**点一下走 1（精确），按住不放越来越快（可快速跨越）** ——
         * 这正是 Windows 微调框 / 0.8.9 的数值增减逻辑。
         */
        private var repeatElapsedMs = 0L

        /** 按一次：立刻走一步（**恒定 1 步**，保证"点一下 = 精确 ±1"）。 */
        private fun beginStep(direction: Int) {
            // ⚠️ 先提交编辑器（若在编辑中）—— 用户在数值框里打了 `42` 然后直接点 `+`，
            // 期望的是"在 42 的基础上 +1"，而不是"丢掉 42、从旧值 +1"。
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

        /**
         * 长按**加速倍数** —— 规则本身在 [stepperAccelerationFactor]
         * （公开纯函数，可被探针断言，见 `MenuPolicy.kt`）。
         *
         * 这里只做一件事：把本组件累积的连点时长喂进去。
         */
        private fun accelerationFactor(): Int = stepperAccelerationFactor(repeatElapsedMs)

        /**
         * 走一步并落盘。
         *
         * @param direction **带符号的步数**（不是"方向"）——
         *   调用方传的是 `方向 × 加速倍数`，故这里再乘 [step]（该项的单位步长）即可。
         *   命名保留 `direction` 是历史原因；语义已在 [beginStep] 的调用处写明。
         *
         * `coerceIn(min, max)` 在**这里**做，而不是指望写入侧 —— 步进器知道自己的
         * 取值域，而"越界值被静默夹回"这件事必须在用户看到它之前发生
         * （否则值框会短暂显示一个越界的数）。
         */
        private fun step(direction: Int) {
            val next = (readValue() + direction * step).coerceIn(min, max)
            if (next == readValue()) {
                // 已经到端点：停掉连点，否则按住不放会空转 110ms 一次。
                stopRepeat()
                return
            }
            runCatching { writeValue(next) }
                .onFailure { System.err.println("[CDL] 菜单步进器写入失败（$label=$next）：$it") }
            repaint()
        }

        // ── 绘制 ──────────────────────────────────────────────────────────

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = font
            val metrics = g.fontMetrics

            // ── 垂直对齐（2026-09-18，多行引入后的**新**问题）──────────────────
            //
            // 结论：**整块**垂直居中 —— 标签块、`− 值 +`、取值域提示共用**同一条中轴**。
            //
            // 为什么不是"首行对齐"：本行右边是 `− 值 +` 三个槽位，它们按**整行高度**居中
            // （`slotY = (height - slotHeight) / 2`）是既有且正确的观感。
            // 若标签按首行对齐，两行标签的第一行会与 ± 错开半行 —— 看起来像
            // "± 浮在两行字的中间偏下"。整块居中让三者共用中轴，是唯一自洽的取舍。
            // 与 [paintWrappedLabelLines] 对普通菜单行的取舍**完全一致**（同一理由）。
            //
            // ⚠️ 单行时 `centerBaseline` 与改动前的 `baseline` 逐位相同
            //（`(height + ascent - descent) / 2`），故"绝大多数单行的步进器行"
            //  的绘制没有任何变化 —— 这次改动只影响真的折了行的那些。
            val centerBaseline = (height + metrics.ascent - metrics.descent) / 2
            val lines = labelLines.ifEmpty { listOf(label) }
            val lineHeight = metrics.height
            val blockTop = (height - lineHeight * lines.size) / 2

            if (hovered) {
                // ★★ 2026-10-15（**§3.74**）：步进器**包装行**的高亮条也是背景 ⇒ 走 [menuFill]
                //   （与 [MenuItemUi] / [DelayedMenuUi] 同一条口径 —— 三类行的行高亮必须同进同退）✓
                g.color = menuFill(hover)
                g.fillRoundRect(3, 2, width - 6, height - 4, 7, 7)
            }

            // 左列：标签（与其余菜单行同一左边缘）。
            //
            // ⚠️ 2026-09-17：**必须省略号截断** —— 原实现直接 `drawString(label)`，
            // 而步进器的 `−` 位置由**右边**算出（`minusX()`），与标签宽度无关。
            // 于是标题一长（如「频谱高度（占歌词区百分比）」）就**压到 `−` 上** ——
            // 正是用户报的"字与步进器重叠"。
            //
            // 用 [ellipsize]（本项目自己的纯函数）而不是 Swing 的现成方法 ——
            // 实测 `SwingUtilities.clipString(FontMetrics, String, Int)` **不存在**
            // （编译期 `Unresolved reference`）。而且纯函数**可以被探针断言**。
            //
            // ⚠️ 2026-09-18：截断前面先**折行**（[useRowHeight] 已经折好，这里只画）。
            // 逐行仍然调 `ellipsize` 兜底：`wrapMenuLabel` 最多折 `MENU_LABEL_MAX_LINES`
            // 行，**最后一行**可能仍然超宽 —— 那时仍必须截断，否则会压到 `−` 上。
            // 两层是配合关系（折行优先、截断兜底），不是重复。
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
            // 中列：取值域提示（会被裁剪到中列范围内，不侵占两侧）。
            // 提示从**最宽的那一行**之后起算 —— 用第一行会让提示压在第二行的字上。
            // `rangeLeft ≤ minusX() - rangeGap`，故提示的可用宽度至少为 0
            // （`drawRangeHint` 对 `available <= 0` 会直接返回，不会画出重叠的字）。
            val rangeLeft = rowStart + widestLine + rangeGap
            drawRangeHint(g, rangeLeft, minusX() - rangeGap, centerBaseline)

            // 右列：− [值] +（按整行高度居中，见上面的「垂直对齐」注）
            val slotHeight = (height - 12).coerceAtLeast(10)
            val slotY = (height - slotHeight) / 2
            drawSlot(g, metrics, minusX(), minusGlyph(), centerBaseline, slotY, slotHeight)
            drawSlot(g, metrics, plusX(), "+", centerBaseline, slotY, slotHeight)
            val boxHeight = (height - 10).coerceAtLeast(12)
            drawValueBox(g, metrics, valueX(), valueW, centerBaseline, (height - boxHeight) / 2, boxHeight)
            g.dispose()
        }

        /**
         * 减号字形。优先用 U+2212 `−`（与 `+` 等宽、视觉平衡），
         * 字体不含它时回退 ASCII `-` —— 与子菜单箭头的 `canDisplay` 防御同一理由：
         * **宁可画得不像，也不能画成豆腐块**。
         */
        private fun minusGlyph(): String =
            if (font.canDisplay('\u2212')) "\u2212" else "-"

        /**
         * 取值域提示（如 `8–200` / `-2000–2000ms`），居中于标签与 `−` 之间的空档。
         *
         * 字号**按需缩小**（下限 [MIN_RANGE_FONT_SIZE]）：中文标签 + 负值范围可能挤不下，
         * 而"挤不下就重叠"比"小一号"难看得多。裁剪到中列范围内是第二道保险。
         * 原实现 `089:ui/StepperItem.kt:549-566` 同此。
         */
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

        /** 画一个 ± 槽位：圆角描边 + 居中的字形。原实现 `089:ui/StepperItem.kt:568-582` 同形。 */
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

        /** 画值框：底色 + 描边（悬停时用 accent 强调"这一行能调"）。 */
        private fun drawValueBox(
            g: Graphics2D,
            metrics: FontMetrics,
            x: Int,
            boxWidth: Int,
            baseline: Int,
            boxY: Int,
            boxHeight: Int,
        ) {
            // ★★ 2026-10-15（**§3.74**）：数值框的**底**也是背景 ⇒ 走 [menuFill]。
            //   ★ 这一条与"框本身不可见"的设计**互相依赖**：底色与底板**同色**，
            //     两者**同 alpha** 时才互相抵消；若框留全不透明，在半透的底板上
            //     它会显成一块**更深的实心圆角矩形** ✗（同一族缺陷的第二个落点）。
            g.color = menuFill(surface)
            g.fillRoundRect(x + 1, boxY, boxWidth - 2, boxHeight, 6, 6)
            // 编辑中：边框用强调色（与悬停同款），让"正在输入"一目了然 ——
            // 否则用户看不出"这个框现在是可打字的"。
            val editing = editor
            val active = hovered || editing != null
            g.color = if (active) accent else muted
            g.stroke = BasicStroke(if (active) 1.5f else 1f)
            g.drawRoundRect(x + 1, boxY, boxWidth - 2, boxHeight, 6, 6)
            // 编辑中显示**缓冲**（用户打了一半的数），否则显示当前值 + 单位。
            val value = if (editing != null) editing.buffer else "${readValue()}$unit"
            val textX = x + (boxWidth - metrics.stringWidth(value)) / 2
            // 选区高亮（编辑中且有选区时）—— 没有它用户不知道自己"全选"了没有，
            // 而全选与否直接决定"下一个数字是替换还是追加"。
            // ★★ 2026-10-15（**§3.74**）：走 [menuFill]（选区底也是**背景**）。
            // ⚠️ 原记「复用 `hover`（一个既有的半透明色）而不是每帧 new 一个 `Color`」——
            //    ★ **两处都不准**：`hover` 是**全不透明**的 RGB 常量（正是本条缺陷）；
            //    而 [menuFill] 在半透明档**确实**会 new 一个 `Color`。
            //    ⇒ 代价可接受且**有界**：每次菜单绘制最多 1 个（只在真的有选区时），
            //      与底板 [PopupUi.paint] 同一量级；菜单是短命的 16 ms 循环，不在词岛主帧上 ✓
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
            // 光标：**有选区时不画** —— 选区本身就是"当前编辑位置"的指示，
            // 两者同时出现会让"下一个字符插在哪"变得不确定。
            if (editing != null && !editing.hasSelection && caretOn) {
                val before = value.substring(0, editing.caret)
                g.color = accent
                g.fillRect(textX + metrics.stringWidth(before), boxY + 2, 1, boxHeight - 4)
            }
        }

        private companion object {
            /** 值框默认/最大宽度。原实现 `089:ui/StepperItem.kt:83-84` 的取值。 */
            const val DEFAULT_VALUE_W = 54

        /** 光标闪烁半周期（毫秒）。500 与 Windows 默认的光标闪烁频率同量级。 */
        const val CARET_BLINK_MS = 500

        /**
         * 当前正在编辑的步进器（**全局唯一**）。
         *
         * `@Volatile`：`keyRouter` 可能被非 EDT 线程调用（`KeyboardFocusManager`
         * 的 dispatcher 链在事件派发线程上跑），故引用本身要可见。
         * 组件内部状态的读写仍只在 EDT 上。
         */
        @Volatile
        private var activeStepper: MenuStepper? = null

        /**
         * 全局按键路由 —— 把按键转给 [activeStepper]。
         *
         * 做成**静态单例**而不是每个实例一个：`KeyboardFocusManager` 的 dispatcher
         * 是一条链，注册多个会让每个都跑一遍（每个都返回 false 才轮到下一个）。
         * 单例 + "当前是谁"这一个字段，语义清楚且不会漏注销。
         */
        private val keyRouter = KeyEventDispatcher { event -> activeStepper?.onKey(event) ?: false }
            const val MAX_VALUE_W = 78

            /**
             * 步进器行的**最小行宽**。
             *
             * ## ⚠️ 2026-09-18：270 → 380（与既有的一处**有意偏离**）
             *
             * 原实现 `089:ui/StepperItem.kt:92` 取 270。用户本轮反馈
             * 「子菜单中**许多选项设置项名字显示不全**」—— 而"显示不全"的项
             * **几乎全是步进器行**：它们的标签由本组件自己画（`MenuStepper.paintComponent`），
             * **不参与**外层 `JMenuItem` 的宽度计算，故标签再长也撑不宽菜单
             * （对比：开关项的文字会撑宽菜单，所以开关项从不被截断）。
             *
             * 270 时标签可用宽度只有 **144px**，而本注册表里最长的标题
             * 「频谱高度（占歌词区百分比）」13 个汉字在 11.5pt 下约 **199px**
             * → 必然截断。380 把它抬到 **242px**（算式见 [assumedLabelMaxWidth]），
             * 199px 放得下且留有余量 —— 这正是用户要的「**拉宽子菜单宽度**」。
             *
             * ⚠️ **2026-09-19 勘误**：本行原写 254px，那是 `DEFAULT_VALUE_W = 42`
             * 时代的取值。`DEFAULT_VALUE_W` 后来定为 **54**，而
             * `assumedLabelMaxWidth() = 296 - valueW`，故当前真值是 **242px**。
             * 算式（`ROW_W - rowEnd - slot - gap - valueW - gap - slot - rangeGap - rowStart`
             * = `380 - 10 - 26 - 3 - 54 - 3 - 26 - 8 - 8`）与 `MenuGapLab` 实测的
             * 「标签可用=242」**逐位吻合** —— 这个 242 也正是 ③ 那条不变量的实测值，
             * 故此处必须与实测同源，不能再凭记忆写一个数。
             *
             * ## 为什么直接改这个常量，而不是"按最长标签动态算"
             *
             * 动态算需要"先量所有标签 → 取最大值 → 再定宽度"，而菜单宽度是
             * **所有项**（含开关/子菜单行）的最小宽度取最大值 —— 步进器只贡献其中一项。
             * 动态算要么引入一轮额外的测量遍历（并且要在 `displaySize` 之前完成），
             * 要么让不同菜单宽度不一致（同一层里两项长度不同 → 菜单忽宽忽窄）。
             * 取一个**够宽的常量**更简单，且顺带让整条菜单的观感统一。
             *
             * ⚠️ 本常量是**步进器的最小宽度**，不是菜单宽度：菜单内容宽度取各项最大值，
             *    故它同时也是"含步进器的那些子菜单"宽度的下界。
             *    （实测影响：含步进器的 5 个分类桶子菜单宽 ~410px，顶层菜单仍是 ~190px。）
             */
            const val ROW_W = 380

            /** 范围提示的最小字号。原实现 `:89`。 */
            const val MIN_RANGE_FONT_SIZE = 9f

            /** 按住连点的首延迟与重复间隔。原实现 `:90-91`。 */
        // ⚠️ 2026-09-17：连点与加速的常量**已移到 `MenuPolicy.kt`**（同包顶层 `const val`）。
        //
        // 原记：`REPEAT_DELAY_MS` / `REPEAT_INTERVAL_MS` / `ACCEL_*` 定义在本 companion 里。
        //
        // 实为：本 companion 是 `private`，而探针是独立编译单元 —— **看不到它们**，
        //   于是"加速倍数"这条新逻辑无法被断言。故连同 [stepperAccelerationFactor]
        //   一起移到 `window/MenuPolicy.kt`（公开纯函数文件），让探针断言**真值**。
        //   三个名字在本文件里仍可直接引用（同包顶层）。
        }
    }

    /**
     * 菜单行的绘制：底色透明 + 悬停时画一条圆角高亮条 + 行尾状态字形。
     *
     * `paintBackground` 里判 `isArmed` 而不是 `isSelected`：`isArmed` 是
     * "鼠标正压在这一行上"（含键盘导航的选中态），才是 Windows 上高亮条的语义。
     * 不判的话每一行都会是实心底色，看起来像"所有项都被选中"。
     */
    private class MenuItemUi(private val keepOpen: Boolean) : BasicMenuItemUI() {
        override fun installDefaults() {
            super.installDefaults()
            // 清掉 L&F 的勾选图标与箭头：状态字形由 paintStateGlyph 自绘，
            // 不清的话会出现"L&F 的勾 + 我们的勾"两个。
            checkIcon = null
            // ⚠️ 2026-09-21 补上 `arrowIcon = null`：上面这行注释一直写着"与箭头"，
            //    而**代码里从来没有清过箭头** —— 注释与代码漂移了。
            //    `MenuItem.arrowIcon` 在 Metal / Windows 两套 L&F 下都**非 null**
            //    （实测 `UIManager.getIcon("MenuItem.arrowIcon")`），只是它们的
            //    `paintIcon` 恰好是空实现（`MenuItemArrowIcon`），
            //    所以此前"看起来没问题"。那是**依赖 L&F 的实现细节**，
            //    换一套 L&F 就会在每一行普通项的行尾冒出一个小三角。
            //    置空是零成本地把这件事变成结构保证（与 [DelayedMenuUi] 同一个理由）。
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

        /**
         * 多行标签的接管点 —— 见 [paintWrappedLabelLines] 的 KDoc（那里解释了
         * 为什么覆写这一个方法就够、以及为什么是"整块"垂直居中）。
         *
         * 未折行（`menuLabelLines` 为 `null` 或只有一行）时**原样交给 JDK** ——
         * 于是"绝大多数单行项"的绘制逐字节仍是 JDK 的，不可能漂移。
         */
        override fun paintText(graphics: Graphics, menuItem: JMenuItem, textRect: Rectangle, text: String) {
            if (paintWrappedLabelLines(graphics, menuItem, textRect, text, disabledForeground, selectionForeground)) {
                return
            }
            super.paintText(graphics, menuItem, textRect, text)
        }

        override fun paintBackground(graphics: Graphics, item: JMenuItem, background: Color) {
            // ★★★ 2026-10-15：判据改用**公开纯函数** `shouldPaintMenuRowHighlight` ——
            //   本类与 [DelayedMenuUi] **共用同一个规则**（那个类此前漏覆写本方法，
            //   于是 JMenu 行落到 L&F 默认实现、被 `selectionBackground` 涂成**蓝色** ✗）。
            //   ⚠️ **不调 `super.paintBackground`**：调了就会把 L&F 的
            //   `selectionBackground` 矩形画出来（那正是缺陷本身）。
            val model = item.model
            // ★★★ 2026-10-16（**BUG-215**）：第 4 个实参 = **子菜单是否真的显示着** ——
            //   见 [shouldPaintMenuRowHighlight] 的 KDoc（`isSelected` 是**申报**、
            //   `isPopupMenuVisible` 是**事实**）。本类与 [DelayedMenuUi] **同一条判据** ✓
            val submenuVisible = (item as? JMenu)?.isPopupMenuVisible == true
            // ★★★ 2026-10-17（**BUG-217**）：第 5 个实参 = **指针真的在这一行上吗**（见 `MenuPolicy` 的 KDoc）✓
            // ★★★★★ 2026-10-16（BUG-218 第四轮）：**记下这一次实际画成什么**（同上）✓
            val painted = shouldPaintMenuRowHighlight(
                model.isArmed, item is JMenu, model.isSelected,
                submenuVisible, pointerOverRow(item),
            )
            lastPaintedHighlight[item] = painted
            if (!painted) {
                // ★★★★ 2026-10-16（**BUG-218 · 第五轮** · 用户纠正「透明模式下」）：
                // **两步绘制：先 Clear 清空，再画底板色**。
                //
                // ## 用户报障（逐字）
                //
                // > 「你的测试都是基于不透明情况下的，我说的问题是透明情况下的。
                // >  光标一移出菜单，再重新打开菜单就会恢复正常。」
                //
                // ## 根因（透明模式专属 · 探针实测复现）
                //
                // `JMenuItem` 与 `JPopupMenu` 都设了 `isOpaque = false`。
                // 不透明模式下，`JWindow` 的 opaque 背景会间接清掉旧像素；
                // 但**逐像素透明窗口**里，Swing 的 `RepaintManager` **不会自动清脏区**
                // —— 旧高亮像素**永远留着** ✗ 直到菜单关闭重开
                // （用户原话「重新打开菜单就会恢复正常」）。
                //
                // ## 为什么不能只用 `menuFill(surface)` 填充
                //
                // `menuFill(surface)` 在透明模式下返回**半透明**色（alpha < 255）。
                // `AlphaComposite.SrcOver` 会与旧高亮**混合**而不是**替换** ⇒ 旧像素留着 ✗
                // （探针实测：paint() 被调用了、次数也增加了，但屏幕像素没变回去）
                //
                // ## 两步绘制
                //
                // 1. `AlphaComposite.Clear` —— 把整行像素**替换**为全透明（不看源像素）
                // 2. `AlphaComposite.SrcOver` + `menuFill(surface)` —— 在透明底上画底板色
                //    （透明底 × 半透明色 = 干净的底板色，没有旧高亮残留）✓
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
            // ★★ 2026-10-15（**§3.74**）：走 [menuFill]（背景必须跟随菜单透明度）✓
            g.color = menuFill(hover)
            // 内缩 3px / 上下各 2px、圆角 7px —— 与 Windows 11 原生右键菜单的高亮条同形。
            g.fillRoundRect(3, 2, item.width - 6, item.height - 4, 7, 7)
            g.dispose()
        }

        /**
         * `keepOpen` 项点击后**不关菜单**。
         *
         * ## 为什么不能直接调 `AbstractButton.doClick(0)` 再自己翻转状态
         *
         * 这段是既有的血泪教训（`089:ui/Windows11PopupStyle.kt:478-482` 的注释原文）：
         * `doClick(0)` 内部是 `model.isArmed = true` → `model.isPressed = true` →
         * `setPressed(false)`，而 `ToggleButtonModel.setPressed(false)` 在 armed 状态下
         * **会自己翻转 `isSelected`**。所以"先手工翻转、再调 doClick"会**翻两次**
         * （勾了又取消，用户看到的是"点了没反应"）。
         *
         * 故这里**绕开 `doClick`**：自己清 armed/pressed、自己翻转、
         * 自己 `fireActionPerformed`。语义上等价于"点了一下"，只是不通知
         * `MenuSelectionManager` 去关菜单 —— 而那正是我们要的。
         *
         * ⚠️ **`JRadioButtonMenuItem` 只置 `true`，不做取反**：单选项不能被"取消选中"，
         * 取反会让整组变空（且 `ButtonGroup` 的模型语义也不允许）。
         *
         * ⚠️ **禁用项仍走"关菜单"分支**：原实现在 `else` 里调 `msm.clearSelectedPath()`。
         * 本实现保持 `super.doClick(msm)` —— `BasicMenuItemUI` 对禁用项本来就不触发动作，
         * 且"点禁用项把菜单关掉"是**反直觉**的（用户会以为点错了）。
         * 这是与既有的一处**有意偏离**，理由如上。
         */
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

    /**
     * 分隔线：**一条细横线**，左右内缩到与标签列同宽。
     *
     * 不内缩的话分隔线会顶到菜单左右边缘，看起来像把菜单切成两半的边框，
     * 而不是"分组提示"。左端对齐 `rowStart` 让它与标签左边缘成一条竖线。
     */
    private class SeparatorUi : BasicSeparatorUI() {
        override fun paint(graphics: Graphics, component: JComponent) {
            // ★★ 2026-10-15（**§3.74**）：分隔线与底板描边同族（都是 `outline`）⇒ 同样走 [menuFill]
            //   （否则半透菜单里会留一条**实心**横线）。
            graphics.color = menuFill(outline)
            val y = component.height / 2
            graphics.drawLine(rowStart, y, component.width - rowEnd, y)
        }
    }

    /**
     * 状态字形：勾选画对勾、单选画实心圆点、未选中什么都不画。
     *
     * 用自绘而不是字体里的 `✓`：字体勾的粗细与位置随字体变化（候选字体有三级），
     * 自绘用固定 `BasicStroke(1.8f)` 才能保证三级字体下观感一致。
     * 形状与描边参数取自原实现 `089:ui/Windows11PopupStyle.kt:508-533`。
     */
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

// ─────────────────────────────────────────────────────────────────────────────
// 装饰物几何：**公开纯函数**（`MenuProbe` 要断言它们，而 `IslandMenuSkin` 是
// `internal`，探针是独立编译单元，看不到 object 内部）。
//
// 这是本项目反复使用的手法：把"会漂移的算术"从 paint 路径里提出来，
// 让它能被离线断言（同 `MenuPolicy.middleColumnGlyphX` / `ellipsize`）。
// ⚠️ 提出来的**唯一**目的是可断言 —— 不要在这里加任何依赖 Swing 状态的东西。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一行菜单里两个**装饰物**（勾选字形 / 子菜单箭头）各自的 x。
 *
 * `null` = 该装饰物不在这一行。两者都为 `null` = 这一行没有装饰物
 * （普通文本行，如「导入字体包…」「退出」）。
 */
data class MenuDecorationX(val checkX: Int?, val arrowX: Int?)

/**
 * 把菜单行的**中间列**分给勾选字形与子菜单箭头。
 *
 * ## 为什么需要它（2026-09-21，用户**第二次**报「右键菜单元素重叠」）
 *
 * 勾选字形与子菜单箭头**原先都调 [middleColumnGlyphX]**，即"居中于同一个中间列"。
 * 于是**只要两者落在同一行，算出来的 x 就逐像素相同** ——
 * 用户截图里「显示实时频谱」右侧 `✓` 与 `›` 完全重合，就是这条的直接后果
 * （两者恰好都是 6 个汉字宽 → `labelEnd` 相同 → x 一模一样）。
 *
 * ⚠️ 把两者放到同一行的**不是**"某一行同时需要两种装饰物"：
 *   `JMenu` 与 `JCheckBoxMenuItem` 是互斥类型，构造侧造不出这种行。
 *   真正的原因是**箭头画错了行** —— `IslandMenuSkin.PopupUi.paintSubmenuArrows`
 *   用**行内** y 画在了 **popup** 坐标系里，于是每个箭头的 y 都落在 popup 的
 *   **第一行**上（见 [menuDecorationY]）。
 *   即"两个各自正确的装饰物，因为一处坐标错位而叠在一起"。
 *
 * ## 两种装饰物都在时：箭头占最右格，勾选占它左边一格
 *
 * ```
 * 只有勾选 / 只有箭头：[ labelEnd+rangeGap ............... right )   居中（与旧行为逐像素一致）
 * 两者都在：           [ .......... 勾选 ][gap][ 箭头 ]            箭头贴中间列右缘
 * ```
 *
 * 关键性质：**`checkX + glyphWidth <= arrowX` 恒成立**（`itemWidth` 够宽时），
 * 且与列宽**无关** —— 因为勾选的 x 是**从箭头往左推**算出来的，不是各自居中。
 * 于是"两个装饰物不相交"成了**结构保证**，不再依赖"这一行只会有一种装饰物"
 * 这个当前为真、却没有任何机制守住的前提。
 *
 * ⚠️ **这是防御性分支**：当前菜单走不到"两者都在"（见上）。
 *   留着它的理由是**本 BUG 的教训**：两个装饰物共用一格这件事此前没有任何
 *   结构性约束，而"没有约束"正是它能悄悄回归的原因。
 *
 * ⚠️ `itemWidth < columnInset + 2 * glyphWidth + rangeGap`（本插件取值 = 103）
 *   时勾选会被钳到 0，此时两者会相交 —— 现实中由 `minMenuWidth = 180` 挡住。
 *   探针对"够宽"与"极窄"两段分别断言（见 `MenuProbe`）。
 *
 * @param itemWidth 菜单行宽（`JMenuItem.width`）
 * @param labelEnd 标签右边缘（`rowStart + 标签宽`，见 `IslandMenuSkin.middleColumnLeft`）
 * @param columnInset 中间列右侧保留量（`IslandMenuSkin.middleColumnInset`）
 * @param glyphWidth 字形边长（`glyphBox`）
 * @param rangeGap 标签与中间列之间的间隙（`rangeGap`）
 * @param hasCheck 本行画勾选 / 单选字形
 * @param hasArrow 本行画子菜单箭头
 */
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
        // 单一装饰物：**逐像素沿用** [middleColumnGlyphX]（居中于中间列）——
        // 原实现 0.8.9 就是这个位置，只有一种装饰物的行**不允许**漂移。
        val x = middleColumnGlyphX(itemWidth, labelEnd, columnInset, glyphWidth, rangeGap)
        return if (hasCheck) MenuDecorationX(checkX = x, arrowX = null)
        else MenuDecorationX(checkX = null, arrowX = x)
    }
    // 两者都在：箭头贴中间列**右缘**（"还能展开"的语义锚点，也与只有箭头时最接近），
    // 勾选从箭头**往左推**一格 —— 于是 `checkX + glyphWidth <= arrowX` 与列宽无关地成立。
    // 与 [middleColumnGlyphX] 同样做"不为负"的钳制：极窄行会把它们算到行外，
    // 视觉上是"菜单左边飘着一个勾"（现实中由 `minMenuWidth = 180` 挡住）。
    val arrowX = (right - glyphWidth).coerceAtLeast(0)
    val checkX = (arrowX - rangeGap - glyphWidth).coerceAtLeast(0)
    return MenuDecorationX(checkX = checkX, arrowX = arrowX)
}

/**
 * 装饰物（勾选字形 / 子菜单箭头）在**传入 `Graphics` 的那个坐标系**里的 y。
 *
 * ## 为什么把这件事抽成一个函数（2026-09-21）
 *
 * 用户第二次报「右键菜单元素重叠依旧」。根因是
 * `IslandMenuSkin.PopupUi.paintSubmenuArrows` 里的一处**坐标系错配**：
 * 它拿到的是 **popup** 的 `Graphics`，却用 `(child.height - glyphBox) / 2`
 * 这个**行内**偏移去画箭头 —— 少了 `child.y`。
 *
 * 后果不是"箭头画歪一点"，而是**一个 popup 里所有箭头的 y 完全相同**
 * （都等于第一行的行内居中偏移）→ 全部叠在 popup 的**第一行**上。
 * 而那一行恰好是「显示实时频谱」（`spectrum.enabled`，SWITCH，默认开 → 有勾），
 * 勾与箭头又共用中间列（见 [menuDecorationX]）→ 两者**逐像素重合**。
 *
 * 两个缺陷各自都不会有这种表现：只错 y → 箭头堆在第一行（但与勾不同列时仍看得出）；
 * 只错列 → 同一行的两种装饰物重叠（但当前没有那种行）。
 * **它们叠在一起，才是用户看到的那个形态。**
 *
 * 故把 y 也做成**具名、可断言**的函数，并让参数名自带坐标系说明：
 * 传**行自己的** `Graphics`（`MenuItemUi.paint` 的入参）时 `rowTopY = 0`；
 * 传 **popup 的** `Graphics`（`PopupUi.paint` 的入参）时 `rowTopY = child.y`。
 *
 * @param rowTopY 本行顶边在**当前坐标系**里的 y（行内坐标系传 0）
 * @param rowHeight 本行高（`item.height` / `child.height`）
 * @param glyphHeight 字形边长（`glyphBox`）
 */
fun menuDecorationY(rowTopY: Int, rowHeight: Int, glyphHeight: Int): Int =
    rowTopY + (rowHeight - glyphHeight) / 2
