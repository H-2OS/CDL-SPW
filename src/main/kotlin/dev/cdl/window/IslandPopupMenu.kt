// SPDX-License-Identifier: AGPL-3.0-only
// `WorkshopApi.ui.toast` 标了 `@UnstableSpwWorkshopApi` —— 导入结果要 toast（P1-2），
// 故与 `SettingsRuntime.kt` / `CdlRuntime.kt` 同样做文件级 opt-in。
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

/**
 * 词岛窗口与托盘的**共用右键菜单** —— 一级 11 行 + 一棵由 [SettingRegistry] 驱动的设置树
 * （**三级**：`更多设置 ▸ 组件 ▸ 设置项`）。
 *
 * ## 菜单结构（2026-09-23 重做：**一级 · 二级（组件）· 三级（设置项）**）
 *
 * ```
 * 更多设置 ▸                        ← 唯一的"目的地"，恒在第一（用户 2026-09-19 原话）
 *   ├── 歌词 ▸ / 词岛 ▸ / 频谱 ▸ / 封面 ▸ / 播放控件 ▸ / 动画 ▸ / 菜单与设置 ▸
 *   │     ├── …                     ← 三级 = 该组件下的**全部设置项**
 *   │     └── …
 * ────────────
 * 显示词岛                          ← 开关 island.enabled
 * 鼠标穿透                          ← 开关 island.interactive（用户称「词岛穿透」）
 * 锁定位置                          ← 开关 island.lock_position
 * ────────────
 * 导入字体包…                       ← 工具窗（宿主无字体选择器，见 recon/20 §A.7）
 * 预设列表 ▸                        ← 预设（内置 + `presets/` 目录）+ 导入/导出/删除/更改
 * ────────────
 * 找回词岛                          ← CdlRuntime.recoverIsland()
 * 关于与许可
 * 源仓库跳转                        ← 用户点名的名字（旧名「项目源代码（GitHub）」）
 * 投喂作者                          ← 自绘窗口（服从主题切换）
 * 退出
 * ```
 *
 * ⚠️ **二级那 10 个组件的名字与顺序来自 `MenuPolicy.MENU_COMPONENT_SECTIONS`**，
 * 而那张表**必须与 `SettingGroups.ALL` 逐字同序** —— 由 `MenuProbe` §J 与
 * `MenuRegistrySyncProbe` §A6 机械断言（本项目已 3 次栽在"改名后联动静默失效"）。
 *
 * ## 为什么本轮要把「更多选项」换成注册表驱动的设置树
 *
 * **用户诉求原文**：「目前的右键菜单相较灵动岛 0.8.9 而言**过于简陋**（**菜单上设置项不足**）」。
 *
 * 取证：原实现 0.8.9 的菜单里**可调项有 21 项**（`089:ui/IslandSettingsMenu.kt:65-89`
 * 的「调整词岛设置」子菜单，另有 `089:ui/IslandMenu.kt:29-40` 的 10 个岛内开关），
 * 而本插件改造前只有 **10 个开关 + 3 个颜色入口 + 1 个字体工具窗**，
 * 且**没有任何数值项**（字号 / 透明度 / 最大宽度 / 歌词偏移 / 圆角全部只能去设置页）。
 *
 * 根因不是"漏抄了几项"，而是**菜单项是手写的**：手写一份清单就必然与
 * `SettingRegistry` 的 43 项漂移（`recon/08` §3.4 已把这条列为"本节最重要的架构改造"，
 * 原实现 `IslandSettingsMenu.kt` 的 21 项手写正是它要避免的反面教材）。
 * 故本轮的修法是**换数据源**：菜单从注册表生成，加设置项时菜单自动跟上。
 *
 * ## 注册表 → 菜单控件的映射（[SettingKind] 六种全覆盖）
 *
 * | `SettingKind` | 菜单控件 | 依据 |
 * |---|---|---|
 * | `SWITCH` | `JCheckBoxMenuItem` + keepOpen | 原实现 `089:ui/IslandSettingsMenu.kt:28-32` 的 `toggle()` |
 * | `LIST`（**恰好 2 项**） | `JCheckBoxMenuItem`（勾 = 第 0 项） | 原实现 `:35-41` 的 `switch()` |
 * | `LIST`（**≥3 项**） | `JMenu` + `JRadioButtonMenuItem` × N | 原实现 `:53-63` 的 `radio()` |
 * | `SEEKBAR` | [IslandMenuSkin.MenuStepper]（`− 值 +`） | 原实现 `:43-51` 的 `stepper()` |
 * | `FONT` | `JMenu` + 常用族单选（**硬编码白名单** `COMMON_FONT_FAMILIES`） | 原实现 `:92-108` 的 `fontMenu()` |
 * | `COLOR` | **不进树**，顶层「歌词颜色…」子菜单承载 | 见下方"有意不进树的四类" |
 * | `EDITTEXT` | **不进树** | 见下方 |
 *
 * ### 有意不进树的四类（**不是漏做**）
 *
 * 1. `COLOR`（3 项）—— 顶层已有专门的「歌词颜色…」子菜单（用户 2026-09-16 明确要求
 *    "颜色要并进右键菜单的子菜单"）。树里再来一份就是**同一项两个入口**，
 *    徒增困惑。故树里排除，由顶层子菜单独占。
 * 2. `EDITTEXT`（1 项，`window.position`）—— 它是"屏幕 + 坐标 + 锚点"的复合值，
 *    由**拖动**写入而不是用户手调的项；菜单里没有输入框，且"清空它"在菜单上下文里
 *    语义不清（`recon/25` §5 已给出完整论证）。更稳的落点是设置页。
 * 3. `FONT` 的 `main_lyric.font.recent` —— 它是**开放集合**（用户装过的任意字体），
 *    菜单只能给固定候选；完整字体族走顶层「字体…」工具窗。
 * 4. 数值项的**精确输入** —— 见 [IslandMenuSkin.MenuStepper] 的 KDoc：
 *    既有的"点数值框进入内联编辑"需要全局键盘钩子，本实现有意不做。
 *
 * ## 为什么是「更多设置 ▸ 组件 ▸ 设置项」（而不是把 10 个组件铺在一级菜单）
 *
 * `SettingGroups.ALL` 有 **10 个一级组，全部是组件**（2026-10-17 起「总开关」组已撤销）。
 * 直接把它们铺在一级菜单会有两个问题：
 * ① 一级菜单长出 8 行（与"右键响应要利落"相悖，且会把「退出」挤到屏外）；
 * ② 用户右键的**主要意图**是"改点什么" —— 他要的是**一个**入口，不是八个。
 * 故一级菜单只留**一个**「更多设置」，组件那一层降为二级（见 [buildSettingsTree]）。
 *
 * ⚠️ 组件表（`MenuPolicy.MENU_COMPONENT_SECTIONS`）是**唯一手写的东西**，
 * 故在 [validateTables] 里有一条构造期校验：它必须与 `SettingGroups.ALL` **逐字同序**，
 * 否则打 stderr 告警（漏一个组件 = 那个组件的设置项在菜单里**整体消失**，而这是静默的）。
 * 探针侧另有**红色断言**（`MenuProbe` §J / `MenuRegistrySyncProbe` §A6）。
 *
 * ## 为什么是 `JPopupMenu` 而不是自绘菜单
 *
 * 窗口本体是自绘的（圆角、半透明），但**菜单不自绘**，这是有意的取舍：
 * `JPopupMenu` 会走 native 的弹出窗口路径，天然具备「超出屏幕边界时自动翻转方向」、
 * 「键盘导航（上下键/回车/Esc）」、「高 DPI 缩放」这些行为。
 *
 * ⚠️ **原记**：「超出屏幕边界时自动翻转方向」是 `JPopupMenu` 免费给的、可以直接依赖的。
 * **实为：那个翻转是 `JMenu` 逐层各自判定的，本菜单链深达 4 层，逐层判定会让
 * 第 3 层翻到左边、正好压在第 1、2 层上 —— 用户报的「一会向左、一会向右」正是它。**
 * 取证：JDK 21 `javax/swing/JMenu.java:400-425` 的 `getPopupMenuOrigin()`；
 * 实测 `recon/menu-lab/SubmenuDirectionLab.java` 锚点 x=1750 时
 * `L1=1974 L2=2151 L3=1974`（L3 与 L1 同 x）。
 * 故**方向已改由我们整条链一次决定**，见 [IslandMenuSkin.decideChainDirection]
 * 与 `recon/42-子菜单方向修复.md`。其余两条（键盘导航 / 高 DPI）仍然免费。
 * 自绘菜单要把上面每一条都重写一遍，且几乎必然会漏掉若干边界情形 ——
 * 收益只是"视觉更统一"这一点，代价太大。规格里也明确写了「风格尽量与 Swing 默认一致」。
 *
 * 外观不一致的问题**不是靠换掉 `JPopupMenu` 解决的**，而是靠给它套一层自绘 UI
 * （见 [IslandMenuSkin]）—— 理由与工作量对比写在该文件的 KDoc 里。
 *
 * ## 「点击外部关闭」为什么需要 [GlobalMenuDismisser] 补一手
 *
 * **原记**（本类早期 KDoc）：「`JPopupMenu` 天然具备『点击外部关闭』」——
 * **实为：在我们这种非激活窗口上，Swing 这条链路一定断，菜单根本不会因外点而关。**
 * 取证：JDK 21 源码 `javax/swing/plaf/basic/BasicPopupMenuUI.java` 的 `MouseGrabber`
 * 是唯一的外部点击消费者，而它要收到事件，前提是
 * `WToolkit.grab(invokerWindow)` → `WWindowPeer.nativeGrab()` → Win32 `SetCapture`
 * 真的把鼠标捕获到了我们的窗口上。两处断点：
 *
 * - **托盘入口传 `invoker = null`** → `JPopupMenu.isPopupMenu()`（源码 `JPopupMenu.java:921`：
 *   `invoker != null && !(invoker is JMenu)`）为 `false` → 菜单**根本不进
 *   `MenuSelectionManager` 的选中路径** → `grabWindow` 不会被调用 → 连 `SetCapture` 都没试过。
 * - **词岛入口的 invoker 是非激活窗口**：词岛是 `WS_EX_NOACTIVATE` 悬浮件
 *   （实测 `EXSTYLE=0x08080008`），**永远不是前台窗口**。MSDN `SetCapture` 的 Remarks：
 *   *"Only the foreground window can capture the mouse. When a background window attempts
 *   to do so, the window receives messages only for mouse events that occur when the cursor
 *   hot spot is within the visible portion of the window."*
 *   → 光标一离开词岛，鼠标消息就发给光标下的窗口（宿主 / 桌面，都不是 AWT 窗口）
 *   → AWT 连事件都不产生。
 *
 * 实测（`recon/menu-lab/DismissLab.java`，Temurin 21.0.12.1，Win11）：托盘路径
 * `MenuSelectionManager` 选中路径长度 **0**、`GetCapture()` **null**，Robot 在别的进程
 * 窗口上真点一下 → **菜单不关**；词岛路径选中路径长度 1（AWT 确实尝试抓取）但
 * `GetCapture()` 仍为 **null**（抓取没生效，手工 `SetCapture` 到非前台词岛窗口同样回读 null）。
 * 换上 `WH_MOUSE_LL` 钩子后同一次点击**菜单关闭**，且该点击**照常送达**光标下的窗口。
 *
 * 原实现 `089:platform/GlobalMenuDismisser.kt` 正是为此自建的钩子 —— 且该机制
 * **在官方 0.6.0 就已存在**（`extract/ref060/060-src/.../GlobalMenuDismisser.kt:67`）。
 *
 * ## 为什么每次弹出都**重建**菜单（而不是构造一次、反复 `setVisible`）
 *
 * 菜单里有 **38 项绑定设置的控件**（开关 / 单选 / 步进器），它们**必须反映当下的真实
 * 设置值**。若菜单是长驻对象，就得在每次弹出前手工同步这 38 个控件的状态 ——
 * 那是「一份状态、两处表示」，一旦漏同步就会出现"显示已勾选但其实没开"的情况
 * （在设置窗口里改了同一项时尤其容易发生）。
 *
 * 重建的代价是创建一个 ~14 行 + 5 个子菜单的菜单（毫秒级），换来的是**勾选态在构造时
 * 就从 [SettingsRuntime.live] 读**这一条结构保证：不存在"过期"的可能。
 * 顺带还免费解决了配色随系统主题变化 —— 每次重建都会重读系统主题（见 [IslandMenuSkin.apply]）。
 *
 * ⚠️ 步进器的"当前值"是**每次重绘**现读的（[IslandMenuSkin.MenuStepper] 的 `readValue`），
 * 所以即使菜单已经弹出、用户在设置页改了同一项，步进器显示的值也会跟着变 ——
 * 这是"重建"之外的**第二条**防漂移措施，两者覆盖的时间尺度不同。
 *
 * ## 线程纪律
 *
 * `JPopupMenu` 的构造与 `show` **必须在 EDT 上**。Swing 的 popup 机制会在
 * 构造时注册键盘/鼠标 grab，从非 EDT 触碰会破坏 AWT 的事件队列
 * （症状是菜单偶尔卡住不消失）。故 [build] 用 [onEdt] 包装 —— 即便调用点
 * 已经是 EDT（正常情况），这一层也保证不会再出问题。
 *
 * ## 与设置系统的写路径
 *
 * 所有写操作都走 `settings.apply(delta)` **一次**，**不**额外调 `publish`：
 * - `apply` 内部就是 `publish(delta)` + `store.save()`，**本身即"生效 + 落盘"的完整事务**；
 * - 再补一次 `publish` 只会多递增一次 `version`、多广播一次订阅（渲染层白白重算）。
 *
 * 菜单的勾选/步进是**离散的显式动作**（不是拖动中的连续态），"生效"与"落盘"
 * 应当是同一次事务 —— 故这里用**一次 `apply`**，见 [applyValue] 的实现。
 */
internal object IslandPopupMenu {

    // ⚠️ 原 `HEADER_HINT = "完整设置请在 SPW 插件配置中调整"` **已删除**（2026-09-18）。
    //
    // 用户原话：「**右键菜单的顶端不显示「更多设置请前往SPW」**」。
    // 那条禁用的"指路牌"占着菜单顶端第一眼，却不可点 —— 信息量在第二次打开菜单时归零，
    // 而代价是每次都要先扫过一行废话。
    //
    // **为什么连常量一起删（而不是留着备用）**：本项目刚清理过一批"声明了但没消费点"
    // 的设置项，理由是"留着只会让人以为能用"。常量同理 —— 留一个无人引用的文案常量，
    // 下一个人会以为"这东西应该被某处用着，是不是漏了"。**要恢复很容易（文案在注释里）。**

    /**
     * 顶层三个开关的键。**顺序即显示顺序**。
     *
     * ## 为什么这三项要**留在顶层**（而不并进注册表树）
     *
     * 它们是"词岛现在能不能用/能不能点"的**状态开关**，属于**每次右键都可能动**的那一类：
     * 想暂时藏起词岛、想临时穿透、想锁住位置防误拖 —— 都要一次点到。
     * 放进两层子菜单等于把最常用的三个动作藏起来（原实现也是把它们平铺在最前面，
     * `089:ui/IslandMenu.kt:29-30`）。
     *
     * 表中每一项都必须在注册表里存在且为 `SWITCH` —— 见 [build] 末尾的构造期校验。
     *
     * ⚠️ **2026-09-22：表本身已搬到 `MenuPolicy.MENU_TOP_LEVEL_SWITCHES`** ——
     * 与 `MENU_COMPONENT_SECTIONS` 被搬走**同一条理由**：它是菜单侧**手写**的数据，
     * 与注册表之间**没有编译期约束**（键名写错不报错），而探针是独立编译单元、
     * 看不到 `private` 的常量。搬成公开常量后，`MenuRegistrySyncProbe` 才能断言
     * 「这 3 个键都存在且是 `SWITCH`」+「文案与注册表的 `title` 逐字相同」。
     * 本处保留 `TOP_LEVEL_SWITCHES` 这个名字只是为了让类内调用点不必全改。
     */
    private val TOP_LEVEL_SWITCHES: List<MenuSwitch> = MENU_TOP_LEVEL_SWITCHES

    /**
     * **按角色决定"这一项点击后要不要保持菜单打开"** —— 本类里**唯一**的 `keepOpen()` 入口。
     *
     * ## 用户原话（本轮诉求，逐字）
     *
     * > 「**右键菜单中有部分项点击即消失，不能连续调参，请你完善。**」
     *
     * ## 为什么要有这个函数（而不是在各处直接写 `item.keepOpen()`）
     *
     * "点击后关不关菜单"原本是**每个构建点各自决定**的，于是它漏了三次：
     * `switchItem`（2026-09-18 补）、顶层三个开关（2026-09-19 补）、
     * **预设单选 + 最近字体单选（本轮补）**。
     * 三次都是同一个形态：**用户点完菜单没了，得重新右键**。
     *
     * 现在决定收成一处（[menuDismissFor]），各构建点只声明**角色**：
     * 调参类传 [MenuItemRole.ADJUST]，导航/动作类传另两个（= 不 `keepOpen`）。
     * 于是"某个调参项漏了 keepOpen"这件事**不可能再靠忘记发生** ——
     * 因为它不再是"加一行调用"，而是"选一个角色"，而角色只有三个、且
     * `MenuProbe` 对三个角色的判据都有断言。
     *
     * ## 为什么只对"调参类"生效（而不是所有项都 `keepOpen`）
     *
     * 导航类（打开取色器 / 字体包 / 文件选择器）点完会弹出**另一个窗口** ——
     * 菜单留在上面只会挡住它；动作类（找回词岛 / 关于 / 退出）没有"连续做"的语义。
     * 把它们也做成保持打开，用户的第一反应是"菜单卡住了"。**两个方向都是缺陷。**
     *
     * ⚠️ 禁用项即使 `keepOpen` 也不会保持打开 —— `MenuItemUi.doClick` 对
     * `!isEnabled` 走 `super`（关菜单）分支，见那里的 KDoc。
     */
    private fun <T : JMenuItem> T.keepOpenFor(role: MenuItemRole): T {
        if (menuDismissFor(role) == MenuDismiss.KEEP_OPEN) keepOpen()
        return this
    }

    // ── 原 `COLOR_ITEMS`（顶层「歌词颜色…」子菜单的三项）：**已删除**（2026-09-17）──
    //
    // **原记**（2026-09-16 新增，当时复核保留）：颜色是**开放集**（1677 万色），
    //   宿主 5 种控件表达不了 → 设置页只能 `edittext` 兜底（手打 `#RRGGBB`），
    //   故"菜单里的这三项才是顺手改色的主路径"。表里的键必须存在且为 `COLOR`，
    //   `build` 里有绑定校验。
    //
    // **实为**（2026-09-17，用户拍板「方案 A」）：歌词三色键**已改为 12 根 RGB/明度滑杆**
    //   （每色 R / G / B / 明度），那三个 hex 键**不复存在**。
    //   于是本表若留着，会去打开一个不存在的键 —— 写进去的值是**孤儿**（无消费点），
    //   用户看到的是"改颜色没反应"。
    //
    // **为什么删掉不亏**：那 12 根滑杆是注册表里的 `SEEKBAR`，
    //   而设置树**按 kind 自动生成**（见 [SettingKind.SEEKBAR] 那一支）→
    //   它们**自动出现在「歌词颜色」分类里**，每根都是 `− 值 +` 步进器，
    //   且刚做过"最细粒度 + 长按加速"，调色比原来的取色器更顺手。
    //   `SettingGroups.LYRIC`（2026-09-21 重分组后的一级组件；此前是细粒度组
    //   `LYRIC_COLOR`，再往前叫 `TEXT_COLOR`）在 `MENU_COMPONENT_SECTIONS` 里，
    //   故这条覆盖是确定的（不是推测）。
    //
    // > **纪律：顶层手写入口与"树里自动生成"是两条路。删掉一条时必须确认
    // > 另一条**真的覆盖**了它 —— 否则用户会发现"那个功能不见了"。**
    //
    // ⚠️ `CdlRuntime.openColorTool(key)` 仍在（自绘窗口用），
    //   但菜单与设置页**都不再暴露入口** —— 这正是用户要的"不要引去其他窗口"。

    // ── 原 `BUCKETS`（`= MenuPolicy.MENU_BUCKETS`）：**2026-09-23 已删除** ────────
    //
    // ## 删掉的是什么
    //
    // 一张「注册表分组 → **意图桶**（"文字与颜色"/"外观与位置"/"元素与控件"/"行为与设置"）」
    // 的归并表。它曾是菜单树的结构来源（`更多设置 ▸ 桶 ▸ 组件 ▸ 项`，**四层**）。
    //
    // ## 为什么删
    //
    // 用户本轮要求「**按组件来分组**」，二级菜单**以组件名为选项名** ⇒
    // 中间那层"意图桶"**不再存在**。菜单现在直接消费 `MenuPolicy.MENU_COMPONENT_SECTIONS`
    // （组件分区表，见 [buildSettingsTree]）。
    //
    // ⚠️ **删的是表，不是护栏**：旧表上那条「必须覆盖全部注册表分组」的判据被
    //    **原样搬到**组件分区表上（构造期校验在 [validateTables] 里，
    //    探针断言在 `MenuProbe` §J 与 `MenuRegistrySyncProbe` §A6）——
    //    那才是旧设计真正值钱的部分（它拦的是 BUG-113 那一类"整组静默消失"）。
    //    留着旧表只会让下一个人以为"菜单还按意图分桶"。
    //
    // ## 为什么连常量一起删（而不是留着备用）
    //
    // 与 2026-09-18 删 `HEADER_HINT` 同一条理由：**留一个无人消费的常量，
    // 下一个人会以为"这东西应该被某处用着，是不是漏了"**。要恢复很容易（论证在 git 历史里）。

    //
    // ─────────────────────────────────────────────────────────────────────────
    // ⚠️ **2026-10-14（BUG-198）已删除 `STEP_ALL` 常量**（原来在这里，值恒为 1）。
    //
    // ## 它原来做什么
    //
    // `stepperItem` 里 `step = STEP_ALL` —— **所有**数值项的步长恒为 1（最细粒度）。
    // 这条是用户 2026-09-17 的原话换来的：
    //   > 「步进器**没有做到最细粒度**（数值增减逻辑可参考 0.8.9）」
    //   ★ 当时的问题是**粗步长**（`layout.max_width` 10 · `controls.autoHideMs` 100 ·
    //     `lyric.offset_ms` 10 …）⇒ 用户**永远调不出**"差 1"的值 ✗
    //   ⇒ 那张 `STEP_BY_KEY` 粗步长表被删除，改成"步长恒 1 + 长按加速"。
    //
    // ## 为什么现在删掉这个常量（★ 不是推翻上面那条指令）
    //
    // ★★ 用户新报障（逐字）：「**子菜单的消失延迟调整后不应用**」。
    // ★ 逐条核过写读两条链**都对**（`live` 立刻更新；收起轮询每次 tick 现读）⇒
    //   **真凶是步长与量纲不匹配**：`menu.submenu_close_delay_ms` 的域是 **0–1000 ms**
    //   而步长恒 1 ⇒ 点 20 下才 +20ms ⇒ **手感上分辨不出来** ⇒
    //   用户得到「数字变了、菜单行为一点没变」⇒ 必然判成"设置了不应用" ✗
    //   （★ 与"单位换了名字没跟着换"同族：这里是"**量纲变了步长没跟着变**"。）
    //
    // ★ 修法 = **兑现本常量 KDoc 自己写下的预告**（见下方"正确做法"那段，逐字保留）：
    //   把步长从"全局常量"改成"**跟着设置项定义走**"的 `SettingDescriptor.step`（默认 1）。
    //   ⇒ ★ **未设 `step` 的项行为逐字不变**（仍是 1，用户那条"最细粒度"仍然成立）✓
    //   ⇒ ★ 只有**域大到 0–1000 ms** 的两项设 `step = 10`（"差 1"仍可由**内联编辑**拿到）✓
    //
    // ⚠️ **不要**把这个常量加回来 —— 全局步长常量会让"哪些项被特殊对待"变得不可见
    //   （这正是下面"正确做法"那段要防的）✓
    //
    // ## 原 KDoc 保留（★ 它记着"为什么粗步长表被删"这条论证，仍有价值）
    //
    //   **原记**：部分项用粗步长（`layout.max_width` 10 · `controls.autoHideMs` 100 ·
    //     `lyric.offset_ms` 10 …），理由是"同一个 0–100 的域，圆角按 1 走、延迟按 10 走才顺手"。
    //   **实为**（用户原话）：「步进器**没有做到最细粒度**（数值增减逻辑可参考 0.8.9）」。
    //     粗步长的**代价**是：用户**永远调不出**"差 1"的值 ——
    //     而"差 1"恰恰是最常见的需求（延迟 249 还是 250 ms、宽度 640 还是 641 px）。
    //     而粗步长想省的"按很多次"这件事，**本来就该由长按加速解决**，不该由牺牲精度解决。
    //   **现做法**：默认步长 1（精确），**快速跨越交给长按加速**
    //     （1 s → ×5、2.5 s → ×20，见 [IslandMenuSkin.MenuStepper.accelerationFactor]）。
    //     于是"点一下 = 精确 ±1"与"按住能快速走完 0..10000"**同时成立**。
    //   > **纪律：用"降低精度"换"少点几次"是拿错了工具 —— 该用加速。**
    //     ★ 2026-10-14 补：★ **而"量纲跨度大"是另一件事** —— 那种情况该用
    //       **`SettingDescriptor.step`**（跟着设置项定义走），**不是**恢复全局表 ✓
    //   ⚠️ 以下那段是**已兑现**的预告（原文保留，见 `SettingDescriptor.step`）：
    //     「若将来确实需要某个项走粗步长（例如某个域大到 0..100000），**不要恢复一张全局表** ——
    //      表会让"哪些项被特殊对待"变得不可见。正确做法是在 `SettingDescriptor` 上加一个
    //      可选的 `step` 字段，让"步长"跟着**设置项的定义**走。」
    //     ⇒ ★ 2026-10-14：**已按此兑现**（`SettingDescriptor.step` + 两个 ms 键设 10）✓
    //

    /**
     * 数值项的**单位表**（只影响显示，如 `64%` / `640px` / `120ms`）。
     *
     * 空串表示无单位（字号、圆角已是"%"之外的自然数）。与 [STEP_BY_KEY] 同理由手写。
     *
     * ⚠️ **2026-09-22：表本身已搬到 `MenuPolicy.MENU_UNIT_BY_KEY`**（同
     * `MENU_COMPONENT_SECTIONS` 的理由：手写数据必须暴露给探针才能被断言"键存在且为 SEEKBAR"）。
     * 本处保留 `UNIT_BY_KEY` 这个名字只是为了让类内调用点不必全改。
     */
    private val UNIT_BY_KEY: Map<String, String> = MENU_UNIT_BY_KEY

    /**
     * 字体子菜单的**常用族候选**（照原实现 `089:ui/IslandSettingsMenu.kt:94-98`）。
     *
     * 完整字体族（本机可能 250+）留给顶层的「字体…」工具窗 —— 菜单里给一长串
     * 字体名会把菜单撑成一面墙。
     *
     * ★★★ 2026-10-07 **更正（`migration/98` · E-1）**：本段原写「原实现同样 `take(16)`」，
     *   而**本文件里从来没有 `take(` 调用**（★ 成功执行的检索：只命中注释文字）——
     *   真正把菜单限住的是下面这份**硬编码 14 项白名单**。
     *   ⚠️ 故 `FONT_MENU_LIMIT`（原 16）**从未被使用**，已删除（它是"孤儿常量"）。
     *   ★ 附带如实记一条**可见事实**：白名单是硬编码 ⇒ 用户装了**不在名单里**的字体时，
     *   右键菜单里**选不到**（工具窗里能选）。这是**产品取舍**，不是缺陷。
     */
    private val COMMON_FONT_FAMILIES = listOf(
        "Microsoft YaHei UI", "微软雅黑", "等线", "黑体", "宋体", "楷体",
        "Arial", "Segoe UI", "Consolas", "Dialog",
        "HarmonyOS Sans SC", "MiSans", "Source Han Sans SC", "Noto Sans CJK SC",
    )

    /**
     * 构造菜单。**每次右键都调一次**（理由见类 KDoc）。
     *
     * @param settings 设置系统。为 `null`（插件启动时设置系统就坏了）时，
     *   所有绑定设置的控件会**禁用而非隐藏** —— 隐藏会让用户以为插件没有这个功能，
     *   禁用则明确传达"当前不可用"。动作项（找回词岛/关于/源代码/退出）仍然可用，
     *   因为退出与查看许可是用户在**任何**状态下都应能做的操作。
     * @param window 词岛窗口，用于「显示词岛」的显隐联动。
     *
     * ## 顺序与理由（逐项，2026-09-23 对齐 `TOP_LEVEL_MENU_ENTRIES`）
     *
     * 1. **更多设置 ▸** —— 注册表驱动的三级设置树（见 [buildSettingsTree]）。
     *    恒在**第一**：用户 2026-09-19 原话「**调整词岛设置改到右键一级菜单的顶端**」，
     *    而它是这个菜单**唯一的"目的地"**（其余项要么是开关、要么是动作）。
     *    ⚠️ 原首行那条禁用的"指路牌"**已删除**（2026-09-18，用户原话
     *    「右键菜单的顶端不显示「更多设置请前往SPW」」）—— 见 [build] 第 0 节。
     * 2. **显示词岛 / 鼠标穿透 / 锁定位置** —— 三个"每次右键都可能动"的状态开关，
     *    平铺在最前（原实现同此）。「显示词岛」除写设置外**还要立刻调用**
     *    [IslandWindow.setVisible]：写设置虽然会经订阅传导到窗口，但那条路径是
     *    异步的（`invokeLater`）；直接在菜单动作里同步调用能让窗口在同一帧内响应。
     *    「鼠标穿透」**注意**：开启后再想在窗口上右键就点不到了（穿透正是这个意思），
     *    故实际使用时用户需从托盘菜单或设置页关掉它 —— 这是该功能的固有性质，不是缺陷。
     *    （用户本轮先称它为「词岛穿透」，随后拍板「菜单文案**统一为鼠标穿透**」
     *      —— 见 [MENU_TITLE_CLICK_THROUGH] 的命名史；别名表已删除。）
     *    ⚠️ 它**同时也在三级**（更多设置 ▸ 词岛 ▸ 交互）—— 用户要求"两处都要有，
     *      方便找"，且两处必须同态。联动实现见 [syncSwitchInstances]，判据见 `MenuProbe` §K。
     * 3. **导入字体包…** —— 一类**开放取值域**（字体族）。
     *    宿主 5 种控件里没有字体选择器（`recon/20` §A.7 字节码穷尽确证），
     *    故它必须自绘工具窗，也是本菜单必须提供的桌面入口。
     *    ⚠️ 它**不受 `settings == null` 影响**：工具窗自己会处理"设置系统不可用"
     *    的情形（以只读形态打开并说明原因），故不随 [disableSwitches] 一起被禁用。
     * 4. **预设列表 ▸** —— 预设清单（内置 + `presets/` 目录）与导入 / 导出 / 删除 / 更改，
     *    见 [buildPresetMenu]。**只在 `settings != null` 时出现** ——
     *    没有设置系统就无从读写预设（那条路径下不出现比出现一个点了没反应的项更诚实）。
     * 5. **找回词岛** —— 调用 [CdlRuntime.recoverIsland]。
     *    它覆盖了原实现两个项（`089:ui/IslandMenu.kt:107-108` 的「解除鼠标穿透并显示」
     *    与「重置位置」）：`recover()` 内部同时做「置 `enabled=true`」「置
     *    `interactive=false`」「窗口移到主屏」。**原实现要两次点击的场景，本插件一次到位**
     *    —— 那两个动作在本插件里本来就是"把词岛弄回来"这一个意图的三个步骤，
     *    分开列反而要求用户知道该按哪个（`recon/25` §5 已论证过"重置位置"单独成项
     *    的语义问题）。
     * 6. **关于与许可 / 源仓库跳转 / 投喂作者** —— 许可通告是 GPL/AGPL 组合分发的
     *    必须项；仓库链接由 [CdlRuntime.openSource] 提供
     *    （它读 `project.properties` 的 `source.url`，未配置时有兜底地址）。
     *    ⚠️ 跳转是**「API 之外」**的做法（`java.awt.Desktop.browse`，SPW API 没有这个能力），
     *    代价见该项的注释。「投喂作者」是自绘窗口（服从主题切换）。
     * 7. **退出** —— 停插件（而非只关窗口）。见下方该项的注释。
     *
     * ⚠️ 原「打开设置」项**已删除**（2026-09-16）：它指向那个 1009 行的
     * `SettingsWindow`，而用户明确要求「不要此独立窗口」，该窗已删。
     * 保留一个指向已删窗口的入口比删掉它更糟 —— 用户点了没反应，
     * 而"点了没反应"正是本项目最难归因的缺陷类型。
     */
    fun build(settings: SettingsRuntime?, window: IslandWindow?): JPopupMenu {
        val menu = JPopupMenu()

        // ── 0. 首行说明 —— **已删除**（2026-09-18）──────────────────────────
        //
        // 原记：对齐原实现 089 的首行，加一条禁用的
        //   「完整设置请在 SPW 插件配置中调整」当作**指路牌**。
        //
        // 实为：用户原话「**右键菜单的顶端不显示「更多设置请前往SPW」**」。
        //   它占着菜单**最贵的一行**（顶端第一眼），却是**不可点**的 ——
        //   每开一次菜单都要先读一行废话才能看到真正能用的项。
        //   而"更多设置在哪"这件事，用户在第一次用插件时就知道（它是 SPW 插件），
        //   指路牌的信息量在**第二次**打开菜单时就归零了。
        //
        // ⚠️ 连**分隔线一起去掉**：留着一条光秃秃的顶部分隔线会让菜单看起来
        //   像是"第一项加载失败"—— 那正是本项目最忌讳的"看起来坏了"。
        //
        // 常量 `HEADER_HINT` 也一并删除（理由见其原处的说明）。

        // ── 1. 注册表驱动的设置树（**2026-09-19 提到最顶端**）────────────────
        //
        // 用户原话：「**调整词岛设置改到右键一级菜单的顶端**」。
        //
        // 为什么放最前：它是这个菜单**唯一的"目的地"** —— 其余项要么是开关、
        // 要么是动作（找回/关于/退出）。用户右键的**主要意图**就是"改点什么"，
        // 而原来它排在三行开关之后，每次都要先扫过三个不常动的开关。
        // 与 2026-09-18 删掉首行"指路牌"同一条思路：**把最贵的位置给最有用的项**。
        menu.add(buildSettingsTree(settings))

        menu.addSeparator()

        // ── 2. 三个状态开关 ──────────────────────────────────────────────
        for (item in TOP_LEVEL_SWITCHES) {
            val check = JCheckBoxMenuItem(item.title, currentBoolean(settings, item.key, false))
            // ── ★ 2026-09-23：**打上键标记** —— 这是"一级 ↔ 三级联动"的接线前提 ──
            //
            // 用户原话：「注意，**即使一级菜单中有这个设置项，在三级细分菜单中也应该还有
            //   这个设置项，方便找**。同时**设置要同步**，不管是一级和三级，还是右键菜单和
            //   SPW 设置页，都应该是**同一种状态**，不应该出现某处开，某处关的误导现象。」
            //
            // 同一份菜单里，本项（一级）与「更多设置 ▸ 词岛 ▸ 交互」里那一项（三级）
            // 是**两个独立的 `JCheckBoxMenuItem`** —— 各自有自己的 `ButtonModel`，
            // 各读各的 `isSelected`。不联动就会出现"同一份菜单里一处打勾、一处空着"。
            // 联动靠 [syncSwitchInstances]：它按**本标记**在整棵菜单里找同键的项统一刷新。
            check.putClientProperty(MENU_ITEM_KEY_PROPERTY, item.key)
            check.addActionListener {
                applySwitch(settings, item.key, check.isSelected)
                // ★ 把**同一份菜单里**该键的所有实例刷成同一个值（一级 ↔ 三级）。
                //   放在 `applySwitch` 之后：先落盘，再让所有视图对齐同一个事实。
                syncSwitchInstances(check, settings, item.key)
                // 「显示词岛」额外同步联动窗口显隐：见 KDoc 关于"异步订阅 + 同步调用"的理由。
                //
                // ⚠️ `window` 可为 null —— 托盘菜单在窗口创建失败时仍要能弹出
                // （托盘是兜底入口，它存在的意义正是"窗口不可用时还能操作"）。
                // 此时只改设置：设置是唯一事实来源，窗口若在后续启动成功会自动跟上。
                if (item.key == "island.enabled") window?.setVisible(check.isSelected)
            }
            // ── ⚠️ 2026-09-19：**必须 `keepOpen()`**（修用户报的「选中之后无法撤销」）──
            //
            // ## 这是 `switchItem` 那条纪律的**漏网点**
            //
            // [switchItem] 的 KDoc 立过规矩：「**开关该保持**（勾选类项的效果当场可见，
            // 用户自然会想连着调几项）」—— 并且它正是为了修用户
            // 「右键菜单**第一级菜单**点击一次即消失，**不能连续调参**」而加的。
            // 但那一次只加到了**设置树里**的开关（`switchItem`），
            // **第一级菜单上这三个开关恰恰没加** —— 用户抱怨的"第一级菜单"正是这三行。
            //
            // ## 不修会怎样（本条是「无法撤销」里**真的走不回去**的那一类）
            //
            // 这两个开关的效果**不是"当场可见"，而是"当场把自己关在门外"**：
            // - `island.interactive`（**鼠标穿透**）：开启后词岛整体 `WS_EX_TRANSPARENT`，
            //   右键点不到它 → 用户**再也没法从这个菜单里关掉它**
            //   （只能绕去托盘菜单或 SPW 设置页）。
            // - `island.enabled`（**显示词岛**）：取消勾选后词岛立即隐藏 → 同一个菜单里
            //   再也点不到它。
            // 而菜单本身是**独立的 heavyweight popup 窗口**（锚在那 1×1 owner 上，
            // 见 [present]），词岛隐藏/穿透**都不影响菜单继续可点** ——
            // 于是只要 `keepOpen`，用户就能**当场把自己刚勾的那一下撤回来**。
            // 这正是本条修复的全部价值：不需要新增任何"从托盘恢复"的引导。
            //
            // ⚠️ 与动作类项（找回词岛 / 关于 / 退出）**仍然不同**：那些项要么打开新窗口、
            // 要么让整个插件退出，菜单留着只会挡住视线 —— 故它们**不**加，与本条不冲突。
            check.keepOpenFor(MenuItemRole.ADJUST)
            menu.add(check)
        }

        // ── 3. 字体：**导入字体包**（不是"选字体"）─────────────────────────
        //
        // 用户原话：「右键菜单中选择字体设置项**仍然是弹出自绘窗口进行少量的字体选择**，
        //   而不是**最近使用的字体清单**。**完整的字体检索 + 选取放到 SPW 插件配置页**。」
        //
        // 于是分工定为（见 [recentFontItem] 的表格）：
        //   · **选字体** → 菜单里是「最近使用字体 ▸」（`font.recent`，见 [recentFontItem]）；
        //     完整检索在 **SPW 设置页**（`main_lyric.font.family` 是宿主原生 `list`，260 个族可搜索）。
        //   · **导入字体包** → 仍需要自绘窗口（宿主没有文件选择器），
        //     故这一项**保留**，但**改名为「导入字体包…」** ——
        //     名字必须说清它做什么，否则用户会以为"点它就能选字体"，
        //     而实际弹出来的是一个**以导入为主的窗口**（正是用户抱怨的那点）。
        menu.add(JMenuItem("导入字体包…").apply {
            addActionListener { CdlRuntime.openFontTool() }
        })

        // ⚠️ 2026-09-17：**已删除「歌词颜色…」子菜单**（原由 `COLOR_ITEMS` 驱动，
        // 每项调 `CdlRuntime.openColorTool(key)` 打开取色器）。
        //
        // 原因：歌词三色键已改为 **12 根 RGB/明度滑杆**（用户拍板的「方案 A」），
        // 那三个 hex 键**不复存在** —— 子菜单会去打开一个不存在的键，
        // 写进去的值是**孤儿**（没有任何消费点），用户看到"改颜色没反应"。
        //
        // **颜色现在怎么改**：那 12 根滑杆是注册表里的 `SEEKBAR`，
        // 而设置树**按 kind 自动生成**（见 [SettingKind.SEEKBAR] 那一支）——
        // 故它们**自动出现在「歌词颜色」分类里**，每根都是 `− 值 +` 步进器，
        // 而且本轮刚做过"最细粒度 + 长按加速"，调色反而比原来的取色器更顺手。
        //
        // > **纪律：顶层手写入口与"树里自动生成"是两条路，删掉一条时必须确认
        // > 另一条**真的覆盖**了它** —— 否则用户会发现"那个功能不见了"。**
        // > 这里确认过：`SettingGroups.LYRIC`（2026-09-21 重分组后的一级组件；
        // > 此前是细粒度组 `LYRIC_COLOR`，再往前叫 `TEXT_COLOR`）
        // > 在 `BUCKETS` 里（见 `BUCKETS` 的定义），
        // > 故 12 根滑杆都会出现在树里（细类「歌词颜色」在 `subGroup` 里）。

        menu.addSeparator()

        // ── 3.5 预设列表（列出 / 导入 / 导出 / 删除 / 更改）—— 2026-09-19 新增 ───
        //
        // 用户原话：「**重量级：允许导入导出词岛配置，以 `.txt`/`.json` 形式存储/读取
        // （以文件名为预设名），在菜单新增导入导出配置选项，
        // 新增预设选择子菜单（多选框，允许已导入和默认预设出现在列表中）。**」
        //
        // ⚠️ 2026-09-23 **改名 + 扁平化**（用户原话「一级菜单放…**预设列表**等…」+
        //    「**预设列表 ▸** ← 列出 presets 目录里的预设」）：
        //    · 旧名「配置预设」→ 新名「预设列表」（理由见 `MENU_TITLE_PRESET_LIST` 的 KDoc）；
        //    · 旧结构是 `配置预设 ▸ 选择预设 ▸ 预设`（**两层**，要悬停两次才看到一个预设名），
        //      新结构是 `预设列表 ▸ 预设`（**一层**）—— 用户要的就是"这一层直接列出预设"。
        //
        // ## 为什么用 `JRadioButtonMenuItem` 而不是用户说的"多选框"
        //
        // 用户写的是"多选框"，但**预设选择的语义是单选** —— 点一个预设 = 应用它，
        // 不存在"同时应用两个预设"（两个预设对同一个键给不同的值，谁赢？）。
        // 用 `JCheckBoxMenuItem` 会让 UI 显示**多个勾**，那是在说谎：
        // 用户会以为两个都生效了。
        //
        // > **纪律：UI 控件形态要与语义一致。** 用户用日常语言描述形态（"多选框"），
        // > 但**真正要的是"列表里能看到多个预设并选一个"** —— 后者才是需求。
        // > 照字面做会造出一个语义错误的界面。
        //
        // 与设置树里的 `LIST ≥ 3 项` 同一形态（`JMenu` + radio），故视觉一致。
        if (settings != null) {
            menu.add(buildPresetMenu(settings))
        }

        // ── 4. 找回词岛（覆盖既有的「解除穿透并显示」+「重置位置」）────────
        menu.add(JMenuItem("找回词岛").apply {
            addActionListener { CdlRuntime.recoverIsland() }
        })

        // ── 5. 关于与许可 ────────────────────────────────────────────────
        menu.add(JMenuItem("关于与许可").apply {
            addActionListener { CdlRuntime.showAbout() }
        })

        // ── 6. 源仓库跳转 —— 对齐原实现 089:ui/IslandMenu.kt:44 ────────────────
        //
        // ⚠️ **2026-09-23 改名**：「项目源代码（GitHub）」→「源仓库跳转」。
        //    用户本轮点名的五个一级项里写的就是这个名字；改名理由见
        //    `MENU_TITLE_SOURCE_REPO` 的 KDoc（旧名把实现细节 "GitHub" 写进了名字里）。
        //
        // ## ⚠️ 本项**是「API 之外」的做法** —— 如实标注（本项目铁律）
        //
        // 落点：`CdlRuntime.openSource()` → `java.awt.Desktop.browse(URI(url))`。
        // 而 **SPW Workshop API 里没有任何"打开外部浏览器"的能力** ——
        // 这是 AWT 给的能力，不是宿主给的。故它是**API 之外**的。
        //
        // 代价（三条，都已在该实现里兜底）：
        // ① **无头 / 受限会话下 `Desktop.isDesktopSupported()` 为 false** →
        //    抛 `IllegalStateException`，被 `runCatching` 接住后**只能 toast 提示**，
        //    跳转**静默失败**（用户看到一条 toast，浏览器不开）；
        // ② `Desktop.Action.BROWSE` 在个别平台不受支持 → 同上；
        // ③ URL 本身可能**仍是占位符**：`projectUrl` 由构建期 gradle 属性展开，
        //    唯一真相源是 `gradle.properties` 的 `projectUrl`（2026-10-18 起
        //    已填真实地址 `https://github.com/H-2OS/CDL-SPW`）。
        //    若把它改回占位符，`verifyProjectUrl` 任务会在**打包时**拦下
        //    （本地真机验证可显式加 `-PallowPlaceholder=true` 豁免）。
        //
        // > 为什么不自己做 `rundll32 url.dll,FileProtocolHandler` 之类的更底层方案：
        // > 那同样是 API 之外，而且比 `Desktop` 更不可移植、更难兜底。**没有收益。**
        menu.add(JMenuItem(MENU_TITLE_SOURCE_REPO).apply {
            addActionListener { CdlRuntime.openSource() }
            // ── ★ 2026-09-23：**占位符地址 ⇒ 置灰**（用户原话「仓库地址还没有，暂定」）──
            //
            // 用户说地址"暂定"⇒ 现在点它**必然 404**。给用户一个"点了打开 404"的项，
            // 观感是**插件坏了**；置灰 + 一句 tooltip 说清原因，才是诚实的表达。
            //
            // ★★ 2026-10-18：仓库地址已确定 ⇒ 判据自然为"非占位符" ⇒ 本项**不再置灰**。
            //    置灰逻辑**保留**（不是删掉）：它守的是"地址被打回占位符"这一回归，
            //    探针 §L1/§L3b2 也仍在守这个判据（见 `MenuPolicy` 的 KDoc）。
            //
            // ⚠️ 判据是**纯函数** [isPlaceholderSourceUrl]（真值表见 `MenuProbe` §L1），
            //    地址读取在 [sourceUrlOrNull] —— 那两个东西为什么在 `MenuPolicy` 而不是
            //    直接调 `CdlRuntime`（它的 `projectUrl()` 是 `private`）、
            //    以及"两个读者"这条已知缺陷的护栏，见 `MenuPolicy` 里那段 KDoc。
            isEnabled = !isPlaceholderSourceUrl(sourceUrlOrNull())
            if (!isEnabled) {
                // 置灰的项**必须**说清为什么 —— 否则用户只会看到"这一项点不动"。
                toolTipText = "仓库地址尚未确定（打包时用 -PprojectUrl=<真实地址> 指定）"
            }
        })

        // ── 6.5 投喂作者（2026-09-21 新增）─────────────────────────────────
        //
        // ## 用户原话
        //
        // > 「在右键菜单**一级菜单**中加入「**投喂作者**」项，单击后**拉起自绘窗口**
        // >   （同样要**服从主题切换**），内容是「**谢谢！**」」
        //
        // ## 为什么放这一层（而不是塞进「关于与许可」子菜单）
        //
        // 用户明确写了"**一级菜单**"。三条独立理由也都指向同一处：
        // ① 它与「关于与许可」「源仓库跳转」同属"关于本项目"那一簇，紧邻最自然；
        // ② 藏进子菜单等于把"想支持作者"这个动作变成"先找到入口"—— 而它本来就只有一步；
        // ③ 一级菜单**只有 11 行**（见 `TOP_LEVEL_MENU_ENTRIES`），加一行不会让它变长到要滚动。
        //
        // ## 为什么是**动作类**（点完关菜单）
        //
        // 它弹出一个独立窗口 —— 菜单留在上面只会挡住它。
        // 判据不在这一行，而在 `MenuPolicy.TOP_LEVEL_MENU_ENTRIES` 里登记的角色
        // （见那里的 KDoc：散落的决定必须收成一处）。
        menu.add(JMenuItem(MENU_TITLE_FEED_AUTHOR).apply {
            // ⚠️ `settings` 传下去**只为**给弹窗取"当前字体族"（见 [showThanksWindow]）。
            //    为 `null`（设置系统不可用）时弹窗退回出厂默认族 —— 它仍必须能弹出来。
            addActionListener { showThanksWindow(settings) }
        })

        // ── 7. 退出 ──────────────────────────────────────────────────────
        menu.add(JMenuItem("退出").apply {
            addActionListener {
                // 语义（2026-09-16 修正）：原实现只调 `window.close()` ——
                // 那只关窗口、**不停插件**：托盘图标仍在、设置订阅仍在、
                // 宿主回调仍在被处理。用户会看到"点了退出但托盘还在"。
                // 改为停插件：`requestStop()` 会级联关闭窗口与托盘
                // （见 CdlRuntime.close 的释放顺序）。
                CdlPlugin.requestStop()
            }
        })

        // 设置系统不可用时：所有绑定设置的控件禁用（动作项保持可用）。
        // 依据：没有设置写入通道时，勾选/步进动作会静默失败 —— 禁用比"点了没反应"诚实。
        if (settings == null) {
            disableSwitches(menu)
        }

        validateTables(menu, settingsAvailable = settings != null)

        // ⚠️ 套皮肤必须是**最后一步**：它会读每一项的 `isEnabled` 来决定文字颜色
        // （禁用项用 muted 灰），若在上面那段"设置系统不可用则禁用"之前套，
        // 被禁用的项会保留正常色，用户看不出"这一项现在点不了"。
        //
        // ⚠️ `delays` / `opacityPct` 传的是**取值函数**而不是值：这两项**本身就在这棵树里**，
        // 用户可以在菜单已弹出时把它们调大 —— 传值会让本次弹出的后续行为停在旧值上。
        // 详见 `IslandMenuSkin.apply` 的 `@param delays` / `@param opacityPct`。
        IslandMenuSkin.apply(
            menu,
            delays = { submenuDelays(settings) },
            opacityPct = { menuOpacityPct(settings) },
            fadeEnabled = { animationEnabled(settings) },
        )

        return menu
    }

    /**
     * 「投喂作者」—— **自绘 Swing 赞助页窗口**。
     *
     * ## 结构（用户 2026-10-17 拍板：谢谢！+三句说明）
     *
     * ```
     * ┌──────────────────────────────────────────────┐
     * │                    谢谢！                      │   ← 主标题（大字）
     * │      如果你喜欢这个桌面歌词岛，可以请我喝瓶水。    │
     * │           完全自愿，不影响任何功能。             │   ← 三句说明（muted）
     * │   当然，比起赞助，我更希望你能把它分享给……        │
     * │                                                │
     * │   ┌───────────┐  ┌───────────┐                 │
     * │   │ 赞助码.png │  │ PayPal 码 │                 │   ← 两张二维码并排
     * │   └───────────┘  └───────────┘                 │
     * │        通过 PayPal 支持：                      │
     * │        https://www.paypal.com/ncp/payment/…   │   ← 可点击链接
     * └──────────────────────────────────────────────┘
     * ```
     *
     * ⚠️ **注意「谢谢！」只出现一次** —— 它是**主标题**；
     *    三句说明里**不再**重复它（一屏两处「谢谢！」是重复，不是强调）。
     *    正文措辞与定稿理由见 [dev.cdl.window.SPONSOR_BODY_LINES] 的 KDoc。
     *
     * 对齐三合一启动器的「支持作者」弹窗（`install_gui.ps1` 的 `Show-SupportWindow`）：
     * 文案 + 两张二维码并排 + PayPal 链接常驻。差异（措辞）见
     * [dev.cdl.window.SPONSOR_BODY_LINES] 的 KDoc。
     *
     * ## 为什么**不用 `JOptionPane`**（用户明确要求）
     *
     * `JOptionPane` 的底色 / 图标 / 按钮**全部由 LAF 决定**，我们的主题表碰不到它 ——
     * 深色系统主题下会弹出一块亮板，那正是用户报过的
     * 「自绘取色窗口大部分面积仍不随 windows 深浅主题」（BUG-108）同一类缺陷。
     * 自绘窗口才谈得上"服从主题切换"。
     *
     * ## 主题怎么跟上（各槽位，**零颜色字面量**）
     *
     * 底 / 正文 / 边线 / 次级文字 / PayPal 链接色分别取 [ThanksWindowSkin] 的
     * `background` / `foreground` / `border` / `muted` / `linkFg`，而那个对象
     * **只转发** `settingsui/ToolWindowPalette`（自绘窗口配色的唯一来源）。
     * 另外再走一遍 [applyToolWindowTheme] —— 它会把**整棵组件树**（含 `rootPane`、
     * 嵌套面板、标签、按钮）按同一套色板刷一遍，并顺带刷原生标题栏。
     * 两者**不冲突**：色板是同一个，故刷两遍结果相同（幂等）。
     *
     * ## 为什么 `owner` 传 `null`（**不是** [ownerWindow]）
     *
     * 菜单的 owner 是那个 1×1 的 `JWindow`，而 `hideOwnerOnClose` 在菜单关闭时会
     * `owner.isVisible = false` —— AWT 里**隐藏属主会连带隐藏它的 owned 窗口**。
     * 于是若把这个弹窗挂在那上面，用户点完「投喂作者」、菜单一关，
     * **弹窗会跟着一起消失**（表现为"点了没反应"）。
     * `null` 属主让它完全独立，菜单怎么关都不影响它。
     *
     * ## 会不会被词岛压住（任务书要求确认）
     *
     * 词岛是 `topmost` 无边框窗，而普通 `JDialog` 不是 —— 不处理就会被压在下面。
     * 故这里显式 `isAlwaysOnTop = true`（与词岛**同级**，后显示的在上），
     * 显示后再 `toFront()` 把它提到同级窗口的最前。
     * ⚠️ 这与菜单自身的 `Window.Type.POPUP` owner 是**两条路**：
     * 菜单走的是"不抢焦点"（避免把用户的播放器顶到后台），
     * 而这个窗是**用户主动点出来的内容窗**，抢焦点是正确的（他要看它、点它关掉它）。
     *
     * ## 怎么关（两条路；**不**用 [installClickAnywhereToClose]）
     *
     * ① 标题栏的 X → `DISPOSE_ON_CLOSE`；
     * ② `Esc` → 键盘绑定（无边框/无标题栏的观感下，用户会先试 Esc）。
     *
     * ⚠️ **2026-10-17 起，本窗**不再**「点窗内任意处即关闭」** ——
     * 它是赞助页，用户要拿手机**扫二维码**，扫码期间窗口必须留在屏幕上；
     * 沿用旧"点任意处即关"会让扫码中的手滑把二维码关掉（赞助泡汤）。
     * 「点任意处关闭」仍作为 [installClickAnywhereToClose] 提供给**一句话**
     * 的窗（如旧的「谢谢！」）；本赞助页明确不调用它。
     *
     * ## 线程
     *
     * 必须在 **EDT** 上调用（构造 Swing 组件）。菜单的 `ActionListener` 本来就在 EDT 上，
     * 这里的 `check` 是**防御**：非 EDT 调用会在真机上表现为"偶发不显示"，
     * 那种缺陷最难归因。
     *
     * @param settings 设置系统，**只用来取"当前字体族"**（见下面 `fontProvider` 那一段）。
     *   为 `null`（设置系统不可用）时弹窗**照常弹出**，字体退回出厂默认族 ——
     *   与 `build` 里"动作项不随 `disableSwitches` 被禁用"同一条：这个窗不是设置项。
     */
    private fun showThanksWindow(settings: SettingsRuntime?) {
        check(SwingUtilities.isEventDispatchThread()) {
            "showThanksWindow 必须在 EDT 上调用（它会构造 Swing 组件）"
        }
        // 连点两次「投喂作者」不叠窗：先把上一个关掉（同一时刻只留一个）。
        thanksWindow?.takeIf { it.isDisplayable }?.dispose()

        val panel = JPanel(BorderLayout()).apply {
            background = ThanksWindowSkin.background
            // 边线走主题表（**不是** `Color(...)` 字面量）——
            // 深浅主题下这条线要与菜单的描边同色，否则窗会显得"贴"在桌面上。
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThanksWindowSkin.border),
                BorderFactory.createEmptyBorder(26, 44, 26, 44),
            )
        }

        // ── ① 主标题：谢谢！──────────────────────────────────────────────
        val titleLabel = JLabel(THANKS_WINDOW_TEXT).apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            // 主标题用大一号的字 —— 让"这是赞助页"一眼可见。
            font = font.deriveFont(font.size2D + 7f)
            foreground = ThanksWindowSkin.foreground
        }
        val titlePanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.CENTER)
        }
        panel.add(titlePanel, BorderLayout.NORTH)

        // ── ② 三句说明（muted 次级文字）─────────────────────────────────
        //
        // ⚠️ **`<html>` 前缀是必需的**：Swing 只有文本**以 `<html>` 开头**才启用
        //    HTML 渲染（`JLabel` 的 `BasicHTML.isHTMLString` 判据）。漏了它，
        //    `<br>` 会**原样显示**成字面量（用户会看到一行 "…<br>…"）。
        //    这里先 `joinToString("<br>")` 再整体包 `<html>…</html>`。
        val body = JLabel("<html>${SPONSOR_BODY_LINES.joinToString("<br>") { it.htmlEscape() }}</html>").apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            // 三句说明是次级文字：比主标题小一号、颜色 muted。
            font = font.deriveFont(font.size2D - 2f)
            foreground = ThanksWindowSkin.muted
        }
        // 空行夹在标题与二维码之间：`BorderLayout.CENTER` 区域不会自己撑高，
        // 说明文字要落在二维码**上方**，故把它放 NORTH 的下一个槽。
        val bodyWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(6, 0, 12, 0)
            add(body, BorderLayout.CENTER)
        }

        // ── ③ 两张二维码并排（赞助码 + PayPal 码）───────────────────────
        val qrGrid = JPanel(GridLayout(1, 2, 16, 0)).apply {
            isOpaque = false
            // 对齐三合一：单图时退化为单列居中 —— 这里恒为两列，
            // 但"二维码缺失"时该槽位留空（不显示破图）。
            border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
        }
        val qrMax = 230  // 对齐三合一 `New-QrImage` 的 `maxSide`
        val donateIcon = loadSponsorImage(SPONSOR_QR_PATH)?.let { fitInside(it, qrMax) }
        val paypalIcon = loadSponsorImage(SPONSOR_PAYPAL_QR_PATH)?.let { fitInside(it, qrMax) }
        fun addQr(icon: ImageIcon?, fallback: String) {
            // ⚠️ **不能** `JLabel(icon ?: ImageIcon(name), …)` ——
            //    `ImageIcon(String)` 把参数当**文件名**，缺失时会尝试读一个
            //    "二维码缺失"文件并静默失败（`iconWidth = -1`）⇒ 二维码位置变成
            //    **空壳**（连占位文字都没有）。正确做法：两态分建 ——
            //    有图则 `JLabel(null, icon, CENTER)`，无图则 `JLabel(fallback)`。
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

        // ── ④ PayPal 链接（置两张二维码下方，始终显示；可点击）────────────
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
        // ⚠️ `JLabel` 的 `<a href>` 在 Swing 里**不会自己打开浏览器** ——
        //    必须手动挂 `MouseAdapter` 用 `Desktop.browse` 打开（对齐
        //    三合一 `install_gui.ps1` 的 `Add_Click { Process.Start }`）。
        //    判断"可打开"的三步与 `CdlRuntime.openSource()` 同构
        //    （`runCatching` + toast 兜底 —— 见那两处的 KDoc）。
        ppLinkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = openPayPal()
        })
        val ppStack = JPanel(java.awt.GridLayout(2, 1, 0, 4)).apply {
            isOpaque = false
            add(ppLabel)
            add(ppLinkLabel)
        }
        paypalWrap.add(ppStack, BorderLayout.CENTER)

        // ── 组装 ──────────────────────────────────────────────────────
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
        // ── 字体来源：与 `ColorPickerDialog.kt:1111` 传的**同一个**（2026-09-23 补）──
        //
        // `applyToolWindowTheme` 的 `fontProvider` 是"自绘窗口用**当前使用的字体**"的
        // **唯一入口**（见 `ToolWindowTheme.kt` 的 KDoc：不给就退回**出厂默认族**，
        // 那正是用户 2026-09-19 抱怨的"没生效"）。
        // `ColorPickerDialog` 走的是它的构造参数 `fontProvider`（`ColorPickerDialog.kt:598`），
        // 而那个值最终也来自**设置里的当前字体族** `main_lyric.font.family` ——
        // 故这里直接读**同一个键**，两边**同源**（不是各读一个键）。
        //
        // ⚠️ 设置系统不可用时**不编一个族名**：返回 `null`，让 `applyToolWindowTheme`
        //    走它自己的"出厂默认族"兜底 —— 族名的唯一来源是
        //    `font/FontDefaults.kt` 的 `DEFAULT_FONT_FAMILY`（`FontDefaultProbe` 守着
        //    它与注册表默认值逐字相同），在这里再写一份就是**第二个真相源**。
        val family = currentString(settings, MENU_FONT_FAMILY_KEY, "").takeIf { it.isNotBlank() }
        val fontProvider: (() -> java.awt.Font)? = family?.let { name ->
            // 字号用标签当下的字号：`applyToolWindowTheme` 只 `deriveFont(cur.size)` 换族，
            // **不动字号**（否则会毁掉窗里"标题大、说明小"的层级，见其 KDoc）。
            { java.awt.Font(name, java.awt.Font.PLAIN, titleLabel.font.size) }
        }
        // ★ 整棵树按主题刷一遍（含 rootPane 底色与原生标题栏），见本函数的 KDoc。
        applyToolWindowTheme(dialog.contentPane, dialog.rootPane, fontProvider)
        // 主题刷过之后再确认一次正文色 —— 色板是同一个，故这里是幂等的显式声明：
        // "这个窗的文字色来自 ThanksWindowSkin"这件事在代码里看得见。
        titleLabel.foreground = ThanksWindowSkin.foreground
        dialog.isAlwaysOnTop = true
        dialog.pack()
        // 赞助页窗：给个下限，避免不同字体度量下变成一个"小方块"。
        val size = dialog.size
        dialog.minimumSize = Dimension(maxOf(size.width, 320), maxOf(size.height, 240))
        // ⚠️ **2026-10-17：不再「点窗内任意处即关闭」**。
        //
        // 旧实现是一句「谢谢！」（用户点一下就看完、顺手关掉）；
        // 现在的窗是**赞助页** —— 用户要用手机扫二维码，扫码期间窗口必须**留在屏幕上**。
        // 若沿用"点任意处即关"，用户扫码时手一滑点进窗里，二维码立刻消失，赞助泡汤。
        // 关闭只剩两条路（见本函数 KDoc「怎么关」）：标题栏 X / Esc。
        // （`installClickAnywhereToClose` 仍保留：它是「关于与许可」那类一句话窗的
        //   通用助手，本窗**不再调用**它 —— 见 KDoc 注释。）
        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowOpened(e: java.awt.event.WindowEvent) {
                // peer 建立后原生标题栏才刷得动（构造期那次取不到 HWND，属静默失效）。
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

    /**
     * 读包内二维码资源并按 [maxSide] **等比**缩放（不裁剪）。
     *
     * @return 缩放后的 [ImageIcon]；资源缺失 / 解码失败时 `null`（调用方负责占位）。
     */
    private fun loadSponsorImage(path: String): ImageIcon? = runCatching {
        val stream = javaClass.getResourceAsStream(path) ?: return null
        stream.use {
            val icon = ImageIcon(it.readBytes())
            if (icon.image == null || icon.iconWidth <= 0 || icon.iconHeight <= 0) return null
            icon
        }
    }.getOrNull()

    /**
     * 把 [icon] 等比缩放到**落在 [maxSide]×[maxSide] 内**（保持宽高比）。
     */
    private fun fitInside(icon: ImageIcon, maxSide: Int): ImageIcon {
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= maxSide && h <= maxSide) return icon
        val scale = minOf(maxSide.toDouble() / w, maxSide.toDouble() / h)
        val nw = maxOf(1, (w * scale).toInt())
        val nh = maxOf(1, (h * scale).toInt())
        // 二维码是二值结构，双线性缩放即可（信息在码眼位置，不在平滑度）。
        val scaled = icon.image.getScaledInstance(
            nw, nh, Image.SCALE_SMOOTH,
        )
        return ImageIcon(scaled)
    }

    /** Swing `JLabel` HTML 文本转义 —— 文案里若出现 `&` / `<` 不会被当成标签。 */
    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 打开 PayPal 赞助链接（浏览器）。与 [dev.cdl.host.CdlRuntime.openSource] 同构。 */
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

    /**
     * 让 [dialog] **点窗内任意处即关闭**（用户要求的三条关闭路径之一）。
     *
     * 递归挂监听而不是只挂在 `contentPane` 上：标签是 `contentPane` 的**子组件**，
     * 而 Swing 里子组件会**吃掉**鼠标事件（不冒泡给父容器）——
     * 只挂一层的话，用户点在「谢谢！」这四个字上反而关不掉（那正是最可能点的地方）。
     */
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
        // `Esc` 也能关：无标题栏观感下用户会先试它。
        dialog.rootPane.registerKeyboardAction(
            object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) = dialog.dispose()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
    }

    /** 当前打开的「投喂作者」窗口（同一时刻最多一个，见 [showThanksWindow]）。 */
    private var thanksWindow: JDialog? = null

    /**
     * **构造期的表校验** —— 四张表与注册表的一致性。
     *
     * ## 为什么值得在每次右键时都跑一遍
     *
     * 这四张表（`TOP_LEVEL_MENU_ENTRIES` / [TOP_LEVEL_SWITCHES] /
     * `MENU_COMPONENT_SECTIONS` / [UNIT_BY_KEY]）是菜单侧**手写**的
     * 全部结构信息，而它们的正确性依赖 `SettingRegistry` 那一侧。两边一旦漂移，
     * 用户侧的表现分别是：
     * - 引用了不存在的键 → 点了没反应（写入被 `coerceDelta` 丢弃）；
     * - 键存在但类型不对 → 颜色项打不开（`openColorTool` 内部 warn 后 return）；
     * - **组件漏进二级表 → 那一整组设置项在菜单里静默消失**（最隐蔽的一种）；
     * - **一级项被挪进子菜单 → 用户要多点一层**（探针看不到，只有这张网能拦）。
     *
     * 三条都不是崩溃，都不出声，都只能靠用户报"某项不见了"才发现。故在这里把它们
     * 变成 stderr 告警。**用告警而不是抛异常**：菜单构造失败会让用户连"退出"都点不到 ——
     * 那比"少一项"严重得多。
     *
     * 成本：每次右键几十次 map 查找（微秒级），可忽略。
     */
    private fun validateTables(menu: JPopupMenu, settingsAvailable: Boolean) {
        // ── 一级菜单表：表里的每一项都必须**真的出现在菜单顶层**（2026-09-21 新增）──
        //
        // ## 为什么需要这一道
        //
        // `TOP_LEVEL_MENU_ENTRIES` 是"一级菜单有哪些项"的公开表，`MenuProbe` 拿它断言
        // 「「投喂作者」在一级菜单」。但**探针看不到 `build()`** ——
        // 若有人把某一项从 `menu.add(...)` 改成 `submenu.add(...)`（挪进子菜单），
        // 表不变、探针照样绿，而用户要多点一层。**那就是一条假绿。**
        //
        // 故这里在构造期核对一遍：表里的标题必须在菜单**顶层**找得到。
        // 找不到就出声（stderr）—— 与本节其余校验同一条理由：
        // 这类缺陷**不报错、不崩溃**，只能靠出声或探针拦住。
        //
        // ⚠️ `settings == null` 时**跳过**：那时「预设列表」整棵子树根本不会被 add
        //    （见 [build] 第 3.5 节），逐项核对会得到一条**必然误报**的告警 ——
        //    而必然误报的告警很快会被无视（比没有更糟，与本文件其他校验同此取舍）。
        if (settingsAvailable) {
            val topLevelTitles = (0 until menu.componentCount)
                .mapNotNull { menu.getComponent(it) as? JMenuItem }
                .map { it.text }
                .toSet()
            val absent = TOP_LEVEL_MENU_ENTRIES.map { it.title }.filterNot { it in topLevelTitles }
            if (absent.isNotEmpty()) {
                System.err.println(
                    "[CDL] 右键菜单的一级项表登记了菜单顶层找不到的项（是不是被挪进子菜单了）：$absent",
                )
            }
        }

        for (item in TOP_LEVEL_SWITCHES) {
            val descriptor = SettingRegistry.descriptor(item.key)
            when {
                descriptor == null ->
                    System.err.println("[CDL] 右键菜单引用了不存在的设置键：${item.key}")

                descriptor.kind != SettingKind.SWITCH ->
                    System.err.println(
                        "[CDL] 右键菜单的开关项不是 SWITCH：${item.key}（实为 ${descriptor.kind}）",
                    )
            }
        }

        // ⚠️ 2026-09-17：原来这里还校验 `COLOR_ITEMS`（三个颜色键必须存在且为 `COLOR`）。
        // 那张表**已删除**（颜色改为 12 根滑杆，走设置树自动生成），故校验一并删除。
        // 保留本段其余校验的理由不变：手写的表与注册表之间**没有编译期约束**，
        // 键名写错只会让"那一项在菜单里不对"，**不报错、不影响启动**。

        // 组件分区表（`MENU_COMPONENT_SECTIONS`）：二级菜单就是它。
        // 漏一个 = 那组设置项从菜单里**整体消失**（不报错、不崩溃）。
        val duplicated = MENU_COMPONENT_SECTIONS.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            System.err.println("[CDL] 右键菜单的二级组件表重复包含：$duplicated")
        }
        val missing = SettingGroups.ALL - MENU_COMPONENT_SECTIONS.toSet()
        if (missing.isNotEmpty()) {
            System.err.println("[CDL] 右键菜单的二级组件表漏掉了分组（该组设置项将不出现在菜单里）：$missing")
        }
        val stray = MENU_COMPONENT_SECTIONS.toSet() - SettingGroups.ALL.toSet()
        if (stray.isNotEmpty()) {
            System.err.println("[CDL] 右键菜单的二级组件表引用了不存在的分组：$stray")
        }
        // ⚠️ 顺序也要一致（不只是集合相等）：用户点开「更多设置」看到的组件顺序
        //    就是本表的顺序，而设置页的顺序来自 `SettingGroups.ALL` ——
        //    两者不一致时"菜单里歌词排第 2、设置页里排第 3"，用户会以为漏了。
        if (MENU_COMPONENT_SECTIONS != SettingGroups.ALL) {
            System.err.println(
                "[CDL] 右键菜单的二级组件表与 SettingGroups.ALL **顺序不一致**：" +
                    "$MENU_COMPONENT_SECTIONS vs ${SettingGroups.ALL}",
            )
        }

        // 单位表：键必须存在且为 SEEKBAR，否则那一项的步进器显示单位是错的。
        //
        // ⚠️ 2026-09-17：原来这里还检查 `STEP_BY_KEY`（粗步长表）。
        // 那张表**已删除**，步长改由**设置项自己**的 `SettingDescriptor.step` 给
        // （默认 1）—— 见 `SettingDescriptor.step` 的 KDoc（含 2026-10-14 BUG-198 的由来）。
        // 本自检**保留**的理由与它当初存在的理由一样：这张手写表与注册表之间
        // 没有编译期约束 —— 键名写错只会让"那一项的单位不对"，
        // **不报错、不影响启动**，是典型的"沉默失效"。
        // ⚠️ **步长没有第二张表**（它跟着 descriptor 走）⇒ 不存在"两张表漂移"的可能 ✓
        for (key in UNIT_BY_KEY.keys) {
            val descriptor = SettingRegistry.descriptor(key)
            if (descriptor == null) {
                System.err.println("[CDL] 右键菜单的单位表引用了不存在的键：$key")
            } else if (descriptor.kind != SettingKind.SEEKBAR) {
                System.err.println(
                    "[CDL] 右键菜单的单位表引用了非数值项：$key（实为 ${descriptor.kind}）",
                )
            }
        }
    }

    /**
     * 递归禁用菜单里所有**绑定设置的控件**（含子菜单内的）。
     *
     * 子菜单要**递归**：只遍历顶层会在设置树里留下一批看起来可点、
     * 实际写入会静默失败的项 —— 那正是本条想避免的情形。
     *
     * 三类都要覆盖：`JCheckBoxMenuItem`（开关）、`JRadioButtonMenuItem`（枚举单选）、
     * [IslandMenuSkin.MenuStepper]（数值）。漏掉任一类，那一类就会在
     * "设置系统不可用"时表现为"能点但没反应"。
     */
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

    //
     // 「更多选项」子菜单：承载原实现 0.8.9 的那批开关与形态切换。
     //
     // ## 为什么是子菜单而不是平铺
     //
     // 原实现是**平铺**的（`089:ui/IslandMenu.kt:29-40` 一路 `toggle(...)` 下去），
     // 因为它的菜单只有 14 项、且用户习惯从托盘/岛内直接调。本插件平铺会有 12+ 项，
     // 在 1080p 上占掉接近半屏，而其中大半是"设一次就不动"的项。
     // 收进子菜单后，顶层只留**每次都可能动**的 4 项 + 动作项，右键响应更利落。
     //
     // ⚠️ **子菜单要求 `isLightWeightPopupEnabled = false`**（已在
     // [IslandMenuSkin.apply] 里设好）—— heavyweight popup 才有独立原生窗口，
     // 子菜单才能正确画在主菜单之外。删掉那一行会让子菜单被父窗口裁剪。
     // 另：`JMenu` **必须保留 `BasicMenuUI`**（它拥有悬停展开逻辑）。
     // ⚠️ 2026-09-18 修订：原文写的是"所以这里不对 `JMenu` 套自定 UI" ——
     // 那是**过于宽泛**的表述。真正不能换的是 `MenuItemUi`（换了就丢悬停展开）；
     // 皮肤侧现在给它换的是 `BasicMenuUI` 的**子类**（`DelayedMenuUi`，只为把写死的
     // 0 延迟做成可调，见 [IslandMenuSkin.styleComponent]）——
     // 判据是"换完还是不是 `BasicMenuUI`"，不是"有没有换"。
     // 构建侧仍然**不需要**知道 UI 类名，那是皮肤侧的事。
     //
     // 菜单项顺序**照原实现 0.8.9**（`089:ui/IslandMenu.kt:31-40`），只把它的
     // `click_through` 挪到了顶层（本插件的「鼠标穿透」在主菜单里，避免重复）。
     //
    /**
     * **注册表驱动的设置树** —— 用户报的「菜单设置项不足」缺陷的修复主体。
     *
     * ## 为什么从注册表驱动（而不是手写菜单项）
     *
     * 手写菜单项会与 `SettingRegistry` 的 40 项**必然漂移**：加了一项设置忘了加菜单，
     * 或改了一个键名菜单还指着旧的。从注册表驱动后，**加设置项时菜单自动跟上** ——
     * 这正是原实现 0.8.9 的做法（`089:ui/IslandMenu.kt` 的菜单也是从它的设置表生成的）。
     *
     * ## 结构：`更多设置 ▸ 组件 ▸ 设置项`（三级）
     *
     * 二级就是 `MenuPolicy.MENU_COMPONENT_SECTIONS` 里那 10 个**组件名**
     * （`SettingGroups.ALL` 的手写镜像），组件内用**分隔线**保留 `subGroup`（细类）边界 ——
     * 注册表的两级结构（组件 ▸ 方面）没丢，只是菜单层数少了一层。
     *
     * ## 哪些项不出现在树里
     *
     * - `COLOR` / `FONT` → 顶层已有专门入口（「歌词颜色…」「字体…」），重复会让人困惑
     * - `EDITTEXT` → 复合值（如 `window.position`），菜单无法表达
     * - 顶层三个开关（显示词岛 / 鼠标穿透 / 锁定位置）→ 已在 [build] 的第 1 节
     */
    private fun buildSettingsTree(settings: SettingsRuntime?): JMenu {
        // ── 结构：更多设置 ▸ **组件** ▸ 设置项（2026-09-23 三级）──────────────────
        //
        // 用户原话：
        // > 「二级菜单比如**更多设置**的子菜单，该子菜单中**以组件名为选项名**，
        // >   再接着展出**第三级**菜单，显示**组件下的设置项**。」
        //
        // **原状**：`更多设置 ▸ 桶（"文字与颜色"等 4 个）▸ 组件 ▸ 项` —— **四层**。
        // 桶名与组件名**语义重叠**（桶叫"文字与颜色"，里面又是"歌词颜色/歌词描边/字体"），
        // 用户找一项要**读两遍名字**，且多一次悬停展开。
        //
        // **现结构**：`更多设置 ▸ 组件 ▸ 项`（**三层**，正是用户要求的形态）。
        // 二级的名字**就是组件名**（歌词 / 词岛 / 频谱 / 封面 / 播放控件 / 动画 /
        // 菜单与设置）—— **没有新造概念**。
        // ★ 2026-10-08（U12c）：组件由 8 个拆为 **11 个**（用户「每组最好不超30项，
        //   但总的分组不要过多」）⇒ 二级多出「歌词颜色与描边」/「词岛外观」/「词岛材质」。
        // ★★★ 2026-10-17：**11 → 10** —— 用户「取消总分组，将其下发回各自的组」
        //   ⇒ 二级里不再有「总开关」，那 6 项回到各自组的三级列表里。
        //   **层数不变（仍三层）** —— 删的是**组**，不是减一层。
        //
        // 组件内仍用分隔线标出 `subGroup`（"方面"）的边界 ——
        // 于是注册表的**两级结构（组件 ▸ 方面）完整保留**，只是菜单层数少了一层。
        //
        // ── ⚠️ 为什么**没有**再插"细类"那一层子菜单（第 4 层）────────────────────
        //
        // 三条理由（前两条是决定性的）：
        // ① **用户原话只到三级**：「…再接着展出**第三级**菜单，显示**组件下的设置项**。」
        //    他要的第三级**就是设置项本身**。插一层细类 ⇒ 设置项被推到第 4 层 ⇒
        //    **与用户原话直接冲突**（本项目纪律：用户原话优先于设计判断）。
        // ② **细类数分布不均，插层对一半组件是纯负担**。实测（`SettingGroups.SUBS`
        //    的 `listOf` 项数 · ★ 2026-09-21 当时口径）：**歌词 8** · 词岛 6 ·
        //    频谱 4 · **封面 1** · 播放控件 3 · 动画 2 · 菜单与设置 2。
        //    ⚠️ ★★ 2026-10-08（U12/U12c）**这行数字已过时**（现 11 组件 / 31 细类）——
        //    但**结论不变**（封面仍只有 1 个细类 + 动画/菜单与设置各 2-3 个）⇒
        //    本条理由**依然成立**。★ 保留原数字并标注日期，供读者理解当时的判断依据。
        //    即 **封面（1 个细类）插层后得到的是一层"只有一条子菜单"的空壳**；
        //    动画（2）/ 菜单与设置（2）也好不到哪去 —— 用户要多悬停一次、多看一眼，
        //    换来的信息量是 0（"封面"下面的细类叫"开关与缩放"，与"封面"本身同义）。
        // ③ **代价（如实记）**：`歌词` 在三级里是 **20 行不分层**（★ U12c 前是 48 行），靠分隔线分段 ——
        //    分隔线**不带名字**，故"这一段是'歌词颜色'"这件事用户读不出来
        //    （要自己从项名反推）。这是本取舍**真实损失的**那一点。
        //    换来的是：所有组件层数一致（都恰好 3 层），且与用户原话一致。
        //
        // > 若将来用户反馈"歌词那 20 行太长、找不到东西"，正确的下一步是
        // > **回到用户**确认是否接受第 4 层，而不是在这里悄悄插一层
        // > （悄悄插层 = 断言 J4 立刻红，因为三级项数会变成"细类数"而不是"设置项数"）。
        // > ★★ 2026-10-08（U12c）**实证了这条纪律的正确性**：用户报"组太长"时，
        // >   我们**没有**插第 4 层，而是**拆组件**（8 → 11）—— 层数不变，用户原话不违。
        //
        // ⚠️ 细类边界**仍必须被表达**（否则上面 ③ 那 32 行会糊成一坨）——
        //    表达方式就是下面 `addSeparator()` 那两行。它由 `MenuProbe` §J4d/J4e 守：
        //    分隔线数**必须等于**细类切换次数，且**至少有一个**组件真的有分隔线
        //    （没有 J4e 的话，"所有组件都只有 1 个细类"会让 J4d 恒真 = 空转）。
        //
        // ⚠️ **2026-09-23：这里原来有一句"顶层已有独立入口的键要排除"** ——
        //    那是 `MENU_TOP_LEVEL_KEYS` 的判据，**已删除**。用户原话：
        //    「注意，**即使一级菜单中有这个设置项，在三级细分菜单中也应该还有这个设置项，
        //      方便找**。同时**设置要同步**…不应该出现某处开，某处关的误导现象。」
        //    ⇒ 一级那三个开关（显示词岛 / 鼠标穿透 / 锁定位置）**现在也在三级里**，
        //      由 §K 的联动保证两处同态。完整论证见 `MenuPolicy` 里
        //      `MENU_TOP_LEVEL_KEYS` 那段已删说明。
        val root = IslandMenuSkin.DirectionalMenu(MENU_TITLE_MORE_SETTINGS)
        // ⚠️ 遍历的是 **`MENU_COMPONENT_SECTIONS`**（`MenuPolicy` 里的**手写表**），
        //    而**不是** `SettingGroups.ALL` —— 这正是"二级菜单 == 那 11 个组件"这条
        //    能被断言的前提：探针断言「表 == `ALL`（逐字 + 顺序）」，故表里漏一个组件、
        //    写错一个字都会当场红。若这里直接读 `ALL`，那条断言只能写成 `ALL == ALL`
        //    （恒真 = 空护栏，本项目栽过）。
        for (component in MENU_COMPONENT_SECTIONS) {
            // ⚠️ 筛选判据**只有一处**（`MenuPolicy.menuItemsForComponent`）——
            //    探针用**同一个函数**算"三级应该有几项"，两处各写一份迟早漂移，
            //    而漂移的表现是"探针绿、菜单里少一项"（最坏的一种假绿）。
            val descriptors = menuItemsForComponent(component)
            // 空组件不入树 —— 注册表若把某组件删空了，这里保证不会出现一个点不开的空子菜单。
            if (descriptors.isEmpty()) continue
            val sub = IslandMenuSkin.DirectionalMenu(component)
            var lastSubGroup: String? = null
            for (descriptor in descriptors) {
                val subGroup = descriptor.subGroup
                // 方面边界的分隔线：只在**确实换了方面**时插，避免首行留下悬空的分隔线。
                if (lastSubGroup != null && subGroup != lastSubGroup) sub.addSeparator()
                lastSubGroup = subGroup
                menuItemFor(settings, descriptor)?.let(sub::add)
            }
            if (sub.itemCount > 0) root.add(sub)
        }
        return root
    }

    /**
     * 按 [SettingKind] 分派菜单项。返回 `null` 表示该项不适合出现在菜单里。
     *
     * `when` **不加 `else`** 是刻意的：将来 `SettingKind` 新增类型时，
     * 这里会**编译失败**而不是静默漏掉 —— 与 `SettingsWindow` 的分派同一纪律。
     */
    private fun menuItemFor(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem? {
        // ⚠️ 本函数**必须是块体**（`{ ... }`）而不是表达式体（`= when(...)`）——
        //    2026-09-19 加"颜色走取色器"那段时，原表达式体导致
        //    `Syntax error: Expecting member declaration`，**错误位置在 `when` 那一行**，
        //    离病根（函数声明形式）有 27 行。**改函数体形式时要连声明一起改。**
        //
        // ── ★ 2026-09-22：判断逻辑**已抽成公开纯函数** `MenuPolicy.menuExposureFor` ──
        //
        // ## 为什么抽（而不是继续把 `when` 留在这里）
        //
        // 这段判断原先只在这里，而本对象是 `internal` ⇒ 探针看不到 ⇒
        // "注册表加了一项，菜单会不会自动出现"**只能靠读代码猜**。
        // 抽成公开纯函数后，`MenuRegistrySyncProbe` 能**穷举注册表每一个键**断言
        // 「它要么在菜单里有条目、要么在设置页能改」—— 这正是用户要的"避免缺漏"。
        //
        // ⚠️ **唯一真相源**：本函数**必须**消费 `menuExposureFor`。
        //    若将来有人在这里再写一套判断，两条判据会漂移，而漂移的表现是
        //    "**探针说在菜单里、实际不在**" —— 那正是本条要防的假绿。
        val item = when (menuExposureFor(descriptor)) {
            // ── 颜色：**一律拉起自绘取色窗口**，不再给 RGB 步进器（2026-09-19）────
            //
            // 用户原话（两次）：
            // > 「**RGB 数值步进器不再放到右键菜单中。**」
            // > 「**右键菜单中不再使用三色 RGB 滑杆选色，而是直接拉起自绘取色窗口。**」
            //
            // ⚠️ **一个颜色组只给一个入口**：组的第一个键生成入口，其余键是
            //   `MenuExposure.NOT_IN_MENU`（去重）。少了这一步，「歌词颜色」分类下会并排
            //   出现 4 个一模一样的「已唱歌词颜色…」—— 那正是用户报的
            //   "**单设置项内具有元素重复**"的同族缺陷。
            MenuExposure.COLOR_ENTRY -> {
                val group = SettingRegistry.colorGroupFor(descriptor.key)
                    ?: return null // 不可达：`COLOR_ENTRY` 的定义就是"属于某个颜色组"。
                // ⚠️ 标题走 `menuItemTitleFor`（**唯一来源**，2026-09-23 抽出）——
                //    探针拿**同一个函数**算"三级菜单应该显示什么文字"。
                //    两处各写一份的话，漂移的表现是"探针绿、菜单显示的是另一个名字"。
                JMenuItem(menuItemTitleFor(descriptor)).apply {
                    // 单键组走 `openColorTool`（取色器按单键读写）；
                    // 多键组走 `openColorGroup`（取色器按 `prefix` 合成/写回 r/g/b[/lightness]）。
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
            // ⚠️ `COLOR` 的键**全部**已被 `SettingRegistry.COLOR_GROUPS` 拦下
            //    （含 `spectrum.color` / `background.color` 两个单键组），
            //    故这一支当前**恒不命中** —— 它必须**留着**：`when` 是表达式，
            //    少了它编译器要求 `else`，而 `else` 会**吞掉将来新增的形态**
            //    （本项目刻意"不加 else"，见本函数的 KDoc）。
            MenuExposure.NOT_IN_MENU -> null
        }
        // ── 给菜单项打上**它承载的设置键**（2026-09-23）───────────────────────
        //
        // 为什么必须打：探针要断言「**三级菜单 = 该组件下的全部设置项**」。
        // 只比**条目数**会漏掉一整类错误 —— 拿 A 项的标题建 B 项的控件（数目对得上、键是错的）。
        // 要比键，就必须能从 `JMenuItem` 反查出键（`JMenuItem` 上没有这个字段）。
        //
        // ⚠️ 打标记**不改变任何用户可见行为**（client property 不参与布局与绘制）。
        item?.putClientProperty(MENU_ITEM_KEY_PROPERTY, descriptor.key)
        return item
    }

    /**
     * **开放集合 → 「最近使用字体」子菜单**（`main_lyric.font.recent`）。
     *
     * 用户原话（两次）：
     * > 「右键菜单中**最近使用字体项为灰**，不显示最近使用的字体列表。」
     * > 「右键菜单中选择字体设置项**仍然是弹出自绘窗口**进行少量的字体选择，
     * >   而不是**最近使用的字体清单**。**完整的字体检索 + 选取放到 SPW 插件配置页**。」
     *
     * ## 分工（三处各司其职，不重叠）
     *
     * | 位置 | 承担什么 | 为什么 |
     * | --- | --- | --- |
     * | **右键菜单**（本函数） | **最近用过的字体**（≤ [FontHistory.CAPACITY] 条） | 菜单要短；"回到上次那个"是最高频的字体操作 |
     * | **SPW 设置页** | **完整字体检索**（260 个族，宿主原生 `list` 可搜索） | 那是"找新字体"的地方，菜单放不下 |
     * | **「导入字体包…」** | 从磁盘选 `.ttf`/`.otf` | 这是**另一个动作**（把字体带进来），不是"选一个已有的" |
     *
     * ## 点选后的写回是**两个键**
     *
     * `font.family`（用哪个）+ `font.recent`（把它顶到最前）。
     * [FontHistory.record] 负责去重与截断 —— 故这里**不自己拼字符串**，
     * 否则"容量 10 / 去重 / 转义"三条规则会散到两处实现。
     *
     * ⚠️ 两个键必须**同一次 `apply`** 写入：分两次写会让"已换字体但历史没更新"
     *   出现一个可观察的中间态（用户下次打开菜单看到的顺序是旧的）。
     *
     * 空历史时给一条**说明性**（仍禁用）的项 —— 此时"没有内容"是**事实**，
     * 与改动前那个"恒为灰的 `font.recent`"有本质区别：
     * 那时用户永远看不到列表，现在只是"还没用过"。
     */
    private fun recentFontItem(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem {
        val recent = FontHistory.parse(currentString(settings, descriptor.key, ""))
        val submenu = IslandMenuSkin.DirectionalMenu(descriptor.title)
        if (recent.isEmpty()) {
            submenu.add(JMenuItem("（还没有使用过其他字体）").apply { isEnabled = false })
            return submenu
        }
        val currentFamily = currentString(settings, MENU_FONT_FAMILY_KEY, "")
        // 与 [listItem] 同一条纪律：列表项是**单选**，必须建 `ButtonGroup`
        // （少了它用户"选中之后无法撤销"，见那里的长注释）。
        val group = ButtonGroup()
        for (family in recent) {
            val item = JRadioButtonMenuItem(family, family == currentFamily)
            // ── ⚠️ 2026-09-21：**本轮补的漏网点**（用户报的「部分项点击即消失」）──────
            //
            // 这里是"换字体"，而换字体是**调参类**：效果当场可见（词岛上的字立刻变），
            // 用户自然会连着试几个字体看哪个顺眼。
            // 此前这一处**没有** `keepOpen` —— 点一个字体菜单就关，
            // 想再试下一个得重新右键、重新展开两层子菜单（设置树 ▸ 文字与颜色 ▸ 最近使用字体）。
            //
            // 漏它的原因与本项目其他漏网点一样：`keepOpen` 是**逐个构建点手写**的，
            // 而 [listItem]（同一形态的"单选子菜单"）**有**、这里**没有** ——
            // 两处长得几乎一样，故靠肉眼审查发现不了。
            // 现在收口到 [keepOpenFor]，角色是唯一输入。
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
                    System.err.println("[CDL] 菜单选字体失败（$family）：$it")
                }
            }
            submenu.add(item)
        }
        return submenu
    }

    /**
     * 数值项 → **菜单内嵌步进器**（`－ 值 ＋`）。
     *
     * ## 为什么不用滑杆
     *
     * Swing 的菜单项不支持拖动（菜单一拖动就关），故数值项用 [IslandMenuSkin.MenuStepper]
     * 的 `±` 步进器 —— 这也是原实现 0.8.9 的做法（`089:ui/StepperItem.kt`）。
     *
     * ## 为什么步长与单位是手写表
     *
     * 见 [STEP_BY_KEY] 与 [UNIT_BY_KEY] 的 KDoc：`SettingDescriptor` 只有 `range`，
     * 而"一次点多少"是**交互参数**不是设置属性（同一个 0–100 域，圆角按 1 走、
     * 延迟按 10 走才顺手）。给 43 项都加 `step` 字段，其中只有 11 项用得上。
     *
     * ## 为什么包一层空 `JMenuItem`
     *
     * `MenuStepper` 是 `JComponent`，而 `JMenu.add` 只接受 `JMenuItem`。
     * 空 `JMenuItem` + `BorderLayout` 是 Swing 里把任意组件塞进菜单的标准做法；
     * `keepOpen()` 让点击步进器时菜单**不关闭**（否则按一下 `+` 菜单就没了）。
     */
    private fun stepperItem(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem {
        val range = descriptor.range
        val min = range?.start?.toInt() ?: 0
        val max = range?.endInclusive?.toInt() ?: 100
        val fallback = descriptor.default as? Int ?: min

        val stepper = IslandMenuSkin.MenuStepper(
            label = descriptor.title,
            min = min,
            max = max,
            // ★★★ 2026-10-14（**BUG-198**）：步长**改读 descriptor.step**（默认 1 = 逐像素不变）。
            //
            // ## 为什么必须改（用户报障「子菜单的消失延迟调整后不应用」）
            //
            // ★ 逐条核过写读两条链（写：`applyValue`→`settings.apply`→`publish` ⇒ `live` 立刻更新；
            //   读：收起轮询 `delays()` 每次 tick 现读 `live`）—— **每一环都是对的** ✓
            // ★★ 真凶 = **步长与量纲不匹配**：`menu.submenu_close_delay_ms` 的域是 **0–1000 ms**
            //   而步长恒 `1` ⇒ 点 20 下才 +20ms ⇒ **手感上分辨不出来** ⇒
            //   用户得到「数字变了、菜单行为一点没变」⇒ 结论必然是"**设置了不应用**" ✗
            //   （★ 与"单位换了名字没跟着换"同族：这里是"**量纲变了步长没跟着变**"。）
            //
            // ⚠️ 这里**不再**用全局步长常量（`STEP_ALL` 已于 2026-10-14 随 BUG-198 删除）——
            //   `descriptor.step` 的**默认值就是 1**，故所有未设 `step` 的项**行为逐字不变** ✓
            //   ★ 用户在 2026-09-17 要求过"最细粒度"：那条仍然成立 ——
            //     ① 未设 `step` 的项**照旧是 1**；② 设了 `step` 的项仍可用**内联编辑**
            //        （点数值框直接打任意整数，见 `MenuStepper.beginEdit`）拿到"差 1" ✓
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

    /**
     * 列表项 → **单选子菜单**。
     *
     * 用 `JRadioButtonMenuItem` 而不是 `JCheckBoxMenuItem`：列表项是**单选**
     * （如 `shape.type` 的四型只能选一个），用勾选框会让用户以为可以多选。
     *
     * ## 选项为空时（开放集合）—— **列出实际内容**，不是禁用项（2026-09-19 修）
     *
     * 原记：选项为空时返回一个**禁用的** `JMenuItem(descriptor.title)`
     *   —— 理由是"让用户看到这里有这一项但当前不可用，比整项消失更少困惑"。
     *
     * 实为（用户原话）：「**右键菜单中最近使用字体项为灰，不显示最近使用的字体列表。**」
     *   那个理由**对"固定候选但暂时不可用"成立，对"开放集合"完全不成立** ——
     *   `main_lyric.font.recent` 的 `options` 恒为空（它的合法取值是**用户装过的任意字体**），
     *   于是那一项**永远是灰的**，而它本该是"最近用过的字体"的清单。
     *   用户看到的是一个**永远点不动的死项**，比"整项消失"更让人困惑。
     *
     * 现做法：开放集合交给 [recentFontItem] —— 真的把 `font.recent` 解析出来列成单选子菜单。
     */
    private fun listItem(settings: SettingsRuntime?, descriptor: SettingDescriptor): JMenuItem {
        val options = descriptor.options.orEmpty()
        if (options.isEmpty()) {
            return recentFontItem(settings, descriptor)
        }

        val current = currentString(settings, descriptor.key, descriptor.default as? String ?: "")
        val submenu = IslandMenuSkin.DirectionalMenu(descriptor.title)
        // ── ⚠️ 2026-09-19：**必须建 `ButtonGroup`**（修用户报的「选中之后无法撤销」）──────
        //
        // ## 根因（移植遗漏）
        //
        // 原实现 0.8.9 的 `radio()` **建了组**并把每个单选项 `group.add(this)`
        // （`089:ui/IslandSettingsMenu.kt:55-63`，字体子菜单 `:102-108` 同）。
        // 本插件移植时**只搬了 `JRadioButtonMenuItem`、漏了 `ButtonGroup`** ——
        // 证据是本文件顶部 `import javax.swing.ButtonGroup`（`:26`）**一直存在但从未被使用**，
        // 而 `IslandMenuSkin.MenuItemUi.doClick` 的 KDoc 还写着"`ButtonGroup` 的模型语义
        // 也不允许"（那里以为有组）。两处都指向同一个假设：**组本该存在。**
        //
        // ## 不修会怎样（用户原话「右键菜单的**一些选项选中之后无法撤销**」）
        //
        // 没有 `ButtonGroup` 时，一组 `JRadioButtonMenuItem` 是**彼此独立**的：
        // `MenuItemUi.doClick` 只把被点的那个置 `true`（不取反，见那里的 KDoc），
        // **不会清掉原来选中的那个**。于是：
        //   · 构造期：`value == current` 的那一项已选中（一个圆点）；
        //   · 用户点另一项 → 那一项也变 `true` → **两个圆点同时亮**；
        //   · 用户再点回原来那项 → 两项**仍然都亮**，永远回不到"只有一个选中"。
        // 从用户视角这就是"选了就撤不掉" —— 且**选项恰好 2 个的项最明显**
        // （`layout.translation.position` 的「主歌词下方/上方」、`layout.lines` 的
        //  「单行/双行」、四个 `*.color_mode` 的「手动指定/跟随封面取色」）：
        // 两项都亮时整个子菜单看起来就是坏的。
        //
        // ## 为什么建组就够（不需要改 `doClick`）
        //
        // `ButtonGroup.add` 会监听每个 model 的 `ChangeListener`，**被点项变 `true` 时
        // 自动把原选中项置 `false`** —— 这正是"切换 = 撤销"所需的语义。
        // 而 `doClick` 走的是 `menuItem.isSelected = true` → `model.setSelected(true)`，
        // 该路径同样会触发组监听，故**两边不用互相知道**（原实现同此）。
        //
        // ⚠️ `ButtonGroup.add` 对"加入时已选中"的按钮有一条规则：若组内已有选中项，
        // 新加入的那个会被**置回 false**（`ButtonGroup.add` 源码）。这里按选项顺序加入，
        // 而构造期**最多只有一个** `value == current`，故组内初始选中项恰好是当前值，
        // 不会出现"加着加着把当前值挤掉"。
        val group = ButtonGroup()
        for ((value, label) in options) {
            val item = JRadioButtonMenuItem(label, value == current)
            item.addActionListener { applyValue(settings, descriptor.key, value) }
            // 同 [switchItem]：单选项也要 `keepOpen()` —— 原实现 `:57` / `:104` 两处都加了。
            // 理由：切换列表值（如「形态」「对齐」）效果**当场可见**，用户会连试几档；
            // 点一次关一次菜单等于每次都把他踢出去。
            item.keepOpenFor(MenuItemRole.ADJUST)
            // ⚠️ `group.add` 必须在 `submenu.add` 之前或之后都行 —— 组只关心 model，
            //    与容器层级无关。这里与原实现同序（先入组、再入菜单）。
            group.add(item)
            submenu.add(item)
        }
        return submenu
    }

    /**
     * 读当前整数值。`settings` 为 `null` 或键不是数值类型时回退 [fallback]。
     *
     * 与 [currentBoolean] / [currentString] 一样从 `live`（生效态）而非 `saved`（落盘态）读。
     */
    private fun currentInt(settings: SettingsRuntime?, key: String, fallback: Int): Int {
        val runtime = settings ?: return fallback
        return runCatching {
            val descriptor = SettingRegistry.descriptor(key) ?: return fallback
            descriptor.coerceInt(runtime.live.raw(key))
        }.getOrElse { fallback }
    }

    /**
     * 读当下的**子菜单延迟**（`menu.submenu_open_delay_ms` / `..._close_delay_ms`）。
     *
     * ## 用户原话（本轮诉求）
     *
     * > 「鼠标悬停和离开展开项时的**子菜单出现、消失延迟要可调**。」
     *
     * ## 为什么走 [currentInt]（与勾选态/步进器同一条读取路径）
     *
     * 三个理由，缺一都会出问题：
     * ① **从 `live` 读**（不是 `saved`）—— 用户在菜单里把收起延迟从 250 拖到 600 时，
     *    本次弹出的行为要**立刻**跟着变，而不是等下一次右键（见 `apply` 的 `@param delays`）；
     * ② **走 `descriptor.coerceInt`** —— 用户内联编辑可能打出 `99999`，
     *    夹取规则只该有一处定义（与步进器的 `coerceIn(min, max)` 同一套）；
     * ③ **`settings == null` 时回退常量**（不是回退 0）—— 设置系统启动失败时菜单仍要能弹出，
     *    而"延迟 0"正是本轮要修的缺陷（离开即收起 = 点不到子菜单项）。
     *
     * ⚠️ 兜底常量与注册表默认值**必须相等**（见 `MenuPolicy` 的
     * [SUBMENU_OPEN_DELAY_FALLBACK_MS]），探针断言这一点 ——
     * 否则"设置系统坏掉时"与"设置系统正常时"的手感会不一样，且这种差异只在
     * 故障态下出现，最难被发现。
     */
    private fun submenuDelays(settings: SettingsRuntime?): SubmenuDelays = SubmenuDelays(
        openMs = currentInt(settings, SUBMENU_OPEN_DELAY_KEY, SUBMENU_OPEN_DELAY_FALLBACK_MS),
        closeMs = currentInt(settings, SUBMENU_CLOSE_DELAY_KEY, SUBMENU_CLOSE_DELAY_FALLBACK_MS),
    )

    /**
     * 读当下的**菜单不透明度**（`menu.opacity_pct`，百分比）。
     *
     * 与 [submenuDelays] **逐条同构**（走 [currentInt]、从 `live` 读、
     * `settings == null` 时回退兜底常量），故这里只写差异：
     * 它读的是**一个**键而不是两个，且兜底值是
     * [MENU_OPACITY_FALLBACK_PCT]（= 100，即"不透明"）——
     * 设置系统坏掉时菜单应当**照常可读**，而不是变半透明。
     *
     * 用户原话：「**允许调整右键菜单的透明度**。」
     */
    private fun menuOpacityPct(settings: SettingsRuntime?): Int =
        currentInt(settings, MENU_OPACITY_KEY, MENU_OPACITY_FALLBACK_PCT)

    /**
     * 读当下的**动画开关**（`animation.enabled`）。菜单弹出时的淡入由它决定。
     *
     * 与 [menuOpacityPct] **逐条同构**（走 [currentBoolean]、从 `live` 读、
     * `settings == null` 时回退兜底值）。为什么传取值函数而不是值：
     * 用户可以在菜单**已经弹出**时把动画关掉，那**下一次**右键就该是瞬时的 ——
     * 传值会让本次弹出的后续行为停在旧值上。
     *
     * 兜底值取 `true` = 注册表里该项的默认值。设置系统不可用时按"开"处理，
     * 与"外观类设置坏掉时回退注册表默认值"是同一条纪律（见 `boolOf` 的 KDoc）。
     */
    private fun animationEnabled(settings: SettingsRuntime?): Boolean =
        currentBoolean(settings, "animation.enabled", true)

    private fun buildMoreOptions(settings: SettingsRuntime?): JMenu {
        val submenu = IslandMenuSkin.DirectionalMenu("更多选项")

        // 顺序照原实现：自动隐藏 → 翻译 → 逐字高亮 → 多行 → 全屏隐藏 → 暂停隐藏
        //             → 减少动画 → 形态切换。
        //
        // ⚠️ 2026-09-19 **移除「减少动画」**（原 `performance.low`）——
        //    逐键审计确认那一项**没有任何读取点**：勾了、翻了，行为零变化。
        //    菜单里留一个假开关比少一个开关更糟（用户会一直以为"是我没设对"）。
        //    详见 `SettingRegistry` 里该项原声明处的注释。
        submenu.add(switchItem(settings, "island.auto_hide_on_hover", "鼠标经过时自动隐藏"))
        submenu.add(switchItem(settings, "layout.translation.enabled", "显示翻译"))
        // ⚠️ 2026-09-19：原「逐字高亮」开关**已删** —— 它被
        //   `main_lyric.karaoke.mode`（三档：整行 / 平滑 / 逐字）取代。
        //   三档是 `LIST`，由设置树**自动生成**（见 [listItem]），故这里不再手写。
        //   留着它就会指向一个不存在的键 → 点了没反应（本项目最忌的形态）。
        submenu.add(switchItem(settings, "island.hide_fullscreen", "全屏时隐藏"))
        submenu.add(switchItem(settings, "island.hide_paused", "暂停时隐藏"))

        submenu.addSeparator()

        // 形态切换：★ **2026-10-17 改为在三个【仍在设置面里】的形态间轮换**
        //   （SQUARE → ROUNDED_RECT → PILL → SQUARE）。
        //   原实现是「胶囊 ⇄ 顶部刘海」两型对切，但 `NOTCH`（刘海）已按用户指令撤出设置面，
        //   继续对切会**写入非法值** ⇒ 被 `applyValue` 拒收 ⇒ 用户点它"没反应"
        //   （正是本项目最忌的"设了无效"形态）。
        //   ⚠️ 仍保留 `keepOpenFor`：若将来被接进菜单，这一项与同子菜单的开关应行为一致。
        val shapeItem = JMenuItem(shapeToggleLabel(settings))
        shapeItem.addActionListener {
            val current = currentString(settings, "shape.type", "SQUARE")
            val order = listOf("SQUARE", "ROUNDED_RECT", "PILL")
            val idx = order.indexOf(current)
            val next = order[((if (idx < 0) 0 else idx) + 1) % order.size]
            applyValue(settings, "shape.type", next)
        }
        // 形态切换是**调参类**（词岛外形当场变），与同一子菜单里的四个开关同类 ——
        // 它们都有 keepOpen，这一项此前没有。见 [keepOpenFor]。
        //
        // ⚠️ `buildMoreOptions` 目前**没有任何调用点**（`build` 里没接它），
        //    故这一行当前不产生用户可见效果 —— 但补上它是**刻意的**：
        //    这里若与同一子菜单里的开关不一致，将来谁把它接进菜单就会带回同一个缺陷。
        shapeItem.keepOpenFor(MenuItemRole.ADJUST)
        submenu.add(shapeItem)

        return submenu
    }

    /** 形态切换项文案（★ 2026-10-17：在**设置面现存的三型**之间轮换）。 */
    private fun shapeToggleLabel(settings: SettingsRuntime?): String {
        val current = currentString(settings, "shape.type", "SQUARE")
        val order = listOf("SQUARE", "ROUNDED_RECT", "PILL")
        val labels = mapOf("SQUARE" to "方形", "ROUNDED_RECT" to "圆角矩形", "PILL" to "胶囊")
        val idx = order.indexOf(current)
        val next = order[((if (idx < 0) 0 else idx) + 1) % order.size]
        return "切换为${labels[next]}"
    }

    /**
     * 造一个绑定设置键的 checkbox 菜单项。
     *
     * 抽出来是为了 [buildMoreOptions] 里那 7 项**不再各写一遍**"读值 + 写值 + 失败兜底"
     * ——那份重复正是漂移的来源（原实现也是用局部函数 `toggle` 收口，见
     * `089:ui/IslandMenu.kt:25-27`）。
     */
    private fun switchItem(settings: SettingsRuntime?, key: String, title: String): JCheckBoxMenuItem {
        val item = JCheckBoxMenuItem(title, currentBoolean(settings, key, false))
        // ★ 2026-09-23：打上键标记 —— 联动与探针都靠它反查"这一项承载哪个键"。
        item.putClientProperty(MENU_ITEM_KEY_PROPERTY, key)
        item.addActionListener {
            applySwitch(settings, key, item.isSelected)
            // ★ 把同一份菜单里该键的所有实例刷成同一个值（一级 ↔ 三级）。
            //   `island.interactive` / `island.enabled` / `island.lock_position` 三个键
            //   同时在一级与三级里出现 —— 点任一处，另一处必须当场跟上。
            syncSwitchInstances(item, settings, key)
        }
        // ⚠️ `keepOpen()` —— **移植遗漏，2026-09-18 补**。
        //
        // 用户原话：「右键菜单第一级菜单**点击一次即消失**，不能**连续调参**。」
        //
        // 原实现 0.8.9 给**开关**和**列表**项都加了它
        // （`089:ui/IslandSettingsMenu.kt:29/36/57/104` 四处 `keepOpen()`）；
        // 本插件此前**只给步进器加了**（见 `stepperItem`），于是：
        //   · 步进器 `±` → 菜单保持（对）
        //   · **开关 → 点一下菜单就关**（错）—— 用户想连调两项得重新右键
        //
        // 为什么"开关该保持"：勾选类项的效果**当场可见**（词岛外观立刻变），
        // 用户自然会想连着调几项；关掉菜单等于每次都强制打断。
        // 而**动作类项**（打开设置窗口 / 关于 / 退出 / 找回词岛）**不该**保持 ——
        // 它们要么打开新窗口、要么让词岛消失，菜单留着只会挡住视线。
        // 故本函数（开关）与 [listItem]（单选）加，动作项不加。
        //
        // ⚠️ 2026-09-19：**第一级菜单那三个开关也必须加** —— 本条纪律此前只落在
        //    `switchItem` 上，而 [build] 里手写的三个开关漏了它，用户
        //    「选中之后无法撤销」里最严重的那一类（鼠标穿透 / 显示词岛）正是它。
        //    见 [build] 第 2 节里的完整论证。
        item.keepOpenFor(MenuItemRole.ADJUST)
        return item
    }

    /**
     * 从**任意**菜单项出发，找到它所在的那棵**顶层 `JPopupMenu`**。
     *
     * ## 为什么要往上走（而不是把根当参数传下来）
     *
     * 需要刷新的两个实例分别在**不同层级**：一级项直接挂在根 `JPopupMenu` 上，
     * 三级项挂在 `JMenu("词岛")` 的 `popupMenu` 上。若把根 `JPopupMenu` 当参数一路传下去，
     * 要改 `buildSettingsTree` / `menuItemFor` / `switchItem` / `buildMoreOptions`
     * 四层签名 —— 而**漏传一处就静默失效**（那一处不联动，且没有任何编译错误）。
     *
     * 从**被点的项自己**出发往上走，则**不需要任何接线**：谁被点了，它自己就知道
     * 自己长在哪棵树上。少一条"必须记得接线"的纪律。
     *
     * ## 怎么走
     *
     * `JMenuItem.parent` → 它所在的 `JPopupMenu`；子菜单的 `JPopupMenu.invoker`
     * 就是那个 `JMenu`，再取 `JMenu.parent` 继续往上。直到某个 `JPopupMenu` 的
     * `invoker` **不是 `JMenu`**（是 null 或宿主组件）—— 那就是根。
     *
     * ⚠️ **本函数不抛异常、找不到就返回 `null`**（调用方对 `null` 直接跳过刷新）。
     * 理由与 `MenuProbe` 里 `first {}` 的教训同一条：菜单结构是**运行期**装配的，
     * 一个"按内容找元素"的辅助函数抛异常会把整条链打断，而这里**没有任何东西**
     * 值得用崩溃去换。
     */
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

    /**
     * **同一个设置键**在**同一份菜单**里的所有勾选实例，统一刷成"该键的当前值"。
     *
     * ## 它解决的是什么（用户原话）
     *
     * > 「注意，**即使一级菜单中有这个设置项，在三级细分菜单中也应该还有这个设置项，
     * >   方便找**。同时**设置要同步**，不管是一级和三级，还是右键菜单和 SPW 设置页，
     * >   都应该是**同一种状态**，**不应该出现某处开，某处关的误导现象**。」
     *
     * 菜单每次右键都**重建**（`present` → `build`），故刚打开时两处**天然一致**
     * （都从 `currentBoolean` 读）。会不一致的唯一时机是**菜单开着的时候**：
     * 用户点了一级的「鼠标穿透」，然后鼠标移到「更多设置 ▸ 词岛 ▸ 交互」——
     * 那两项是两个独立的 `JCheckBoxMenuItem`，各自持有自己的 `ButtonModel`，
     * 不刷新就会出现"上面打勾、下面空着"。**用户看到的是打开着的菜单**，
     * 所以"关掉再开就好了"**不是**解法。
     *
     * ## 为什么不用"两处共用一个 `ButtonModel`"
     *
     * `DefaultButtonModel` 不只有 `selected`，还有 `armed` / `pressed` / `rollover`。
     * 共用 model ⇒ **鼠标划过其中一项会把另一项也点亮**（两行同时高亮）。
     * 那是把"勾选态不同步"换成了"高亮态串味"，仍是可见缺陷。
     * 按需刷新只动 `isSelected`，不碰另外三个状态。
     *
     * ## 为什么刷新值**重新读设置**而不是直接用刚写入的值
     *
     * 用户要的是"同一种状态" —— 那**就是**"该键的当前值"。重新读一遍即**定义**，
     * 而不是"我以为写进去的值"：万一 `apply` 被 `coerceDelta` 收敛成别的值
     * （`applyValue` 的 KDoc 说过它会按 descriptor 类型收敛），刷新出来的仍是**事实**。
     * `settings == null` 时 `currentBoolean` 回退 `false` —— 而那条路径下
     * 所有开关都被 [disableSwitches] 置灰、点不动，故不会产生新的不一致。
     *
     * ## 覆盖范围
     *
     * **只刷同一份菜单内的实例**（`MENU_ITEM_KEY_PROPERTY == key` 的 `JCheckBoxMenuItem`）。
     * 跨窗口（SPW 设置页）不归这里管 —— 那边由**同一个键**保证同源，
     * 而"设置页改了、菜单里还旧"由菜单**每次重建**时重新读值覆盖。
     */
    private fun syncSwitchInstances(clicked: JMenuItem, settings: SettingsRuntime?, key: String) {
        val root = rootPopupOf(clicked) ?: return
        val value = currentBoolean(settings, key, false)
        walkCheckBoxes(root) { candidate ->
            if (candidate.getClientProperty(MENU_ITEM_KEY_PROPERTY) == key) {
                candidate.isSelected = value
            }
        }
    }

    /** 深度优先遍历一棵菜单树里的**所有** `JCheckBoxMenuItem`（含各级子菜单）。 */
    private fun walkCheckBoxes(root: JPopupMenu, action: (JCheckBoxMenuItem) -> Unit) {
        for (index in 0 until root.componentCount) {
            when (val component = root.getComponent(index)) {
                is JCheckBoxMenuItem -> action(component)
                // ⚠️ 子菜单要**递归**：三级项在 `JMenu("词岛")` 的 `popupMenu` 里，
                //    只扫根这一层会漏掉**全部**三级实例（那就是本条要修的场景本身）。
                is JMenu -> walkCheckBoxes(component.popupMenu, action)
                else -> Unit
            }
        }
    }

    /**
     * 读当前字符串设置值。`settings` 为 `null` 或键不是文本类型时回退 [fallback]。
     *
     * 与 [currentBoolean] 一样从 `live`（生效态）而非 `saved`（落盘态）读，
     * 理由见 [currentBoolean] 的 KDoc。
     */
    private fun currentString(settings: SettingsRuntime?, key: String, fallback: String): String {
        if (settings == null) return fallback
        val descriptor = SettingRegistry.descriptor(key) ?: return fallback
        return runCatching { settings.live.text(descriptor) }.getOrDefault(fallback)
    }

    /**
     * 读当前设置值。`settings` 为 `null` 时回退 [fallback]。
     *
     * 从 [SettingsRuntime.live]（生效态）而非 `saved`（落盘态）读 ——
     * 用户在设置窗口里改了但还没松手时，右键菜单应当反映**屏幕上正在生效的值**，
     * 而不是磁盘上的旧值。这与 `live`/`saved` 分离的设计意图一致。
     */
    private fun currentBoolean(settings: SettingsRuntime?, key: String, fallback: Boolean): Boolean {
        if (settings == null) return fallback
        val descriptor = SettingRegistry.descriptor(key) ?: return fallback
        return runCatching { settings.live.boolean(descriptor) }.getOrDefault(fallback)
    }

    /**
     * 写入一个布尔设置并落盘。
     *
     * ## 为什么用 `apply`（单次）而不是 `publish` + `apply`（两次）
     *
     * `SettingsRuntime.apply(delta)` 的实现是 `publish(delta)` 之后再 `store.save()`
     * ——**它本身就是"生效 + 落盘"的完整事务**。再额外调一次 `publish` 只会
     * 多递增一次 `version`、多广播一次订阅（渲染层白白重算一遍）。
     * 菜单勾选是离散的显式动作，不存在"拖动中的连续中间态需要预览"的场景，
     * 故一次 `apply` 正是设计意图内的用法。
     *
     * ## 为什么整段 `runCatching`
     *
     * `apply` 内部对落盘失败已有自己的降级（toast 提示），但 `coerceDelta`
     * 之外仍有理论知识上的抛异常可能（如宿主配置通道在运行时失效）。
     * 菜单回调抛异常会让 Swing 的 `EventQueue` 打印堆栈**并继续** ——
     * 用户看到的是"点了没反应"，那是最难排查的失败形态。故这里兜住并记录。
     */
    private fun applySwitch(settings: SettingsRuntime?, key: String, value: Boolean) {
        applyValue(settings, key, value)
    }

    /**
     * 写入**任意类型**的设置值并落盘。
     *
     * 与 [applySwitch] 共用同一条写入路径（`apply` 单次 = 生效 + 落盘），
     * 只是放开类型 —— 「切换为胶囊/刘海」写的是 `shape.type`（文本枚举），
     * 不是布尔。为它单独写一份几乎相同的代码就会多一个"只有一处会漏改"的落点，
     * 故这里收口成一个方法，布尔版本只是它的具名转调。
     *
     * ⚠️ **不做类型转换**：`apply` 内部的 `coerceDelta` 会按 descriptor 的类型
     * 收敛（不匹配的值会被纠正为该类型的默认值）。故调用方必须传对类型 ——
     * 传错不会崩，但会静默写成默认值。这是刻意的：写入路径不应猜调用方的意图。
     */
    private fun applyValue(settings: SettingsRuntime?, key: String, value: Any?) {
        if (settings == null) return
        runCatching { settings.apply(mapOf(key to value)) }
            .onFailure { System.err.println("[CDL] 右键菜单写入设置失败（$key=$value）：$it") }
        // ── 菜单透明度：改完**立刻**作用到已经弹出的菜单窗上 ────────────────────
        //
        // 用户原话：「**允许调整右键菜单的透明度**。」
        //
        // ## 为什么需要这一句（否则它就是个"看起来没用"的设置项）
        //
        // 透明度本来只在菜单**下一次弹出**时生效（[IslandMenuSkin.apply] 的
        // `opacityPct` 在 `popupMenuWillBecomeVisible` 里读）。而这一项**就住在菜单里**：
        // 用户点 `−` / `+` 时菜单正开着，画面却毫无变化 —— 只有关掉再右键才看到效果。
        // 那正是"设置项没生效"的观感，用户会当成缺陷报上来。
        //
        // ## 为什么只对这一项特殊处理（而不是给所有项做"实时预览"）
        //
        // 其余项都有**自己的**实时反馈：开关的勾、单选的圆点、步进器的数值框
        // 都是控件自身重绘出来的；而"菜单窗的 alpha"不在任何控件上，
        // 是本行唯一一个"写进去了但当前画面看不出来"的项。
        // 只补这一条，不做通用机制 —— 通用机制需要皮肤层订阅设置变更，
        // 那是给一个设置项引入一整套观察者接线。
        if (key == MENU_OPACITY_KEY) {
            IslandMenuSkin.refreshVisibleMenuOpacity(
                currentInt(settings, MENU_OPACITY_KEY, MENU_OPACITY_FALLBACK_PCT),
            )
        }
    }

    /**
     * 在 EDT 上弹出菜单，**锚定在鼠标光标处**（词岛内右键）。
     *
     * 供 [IslandWindow] 的鼠标监听调用（`isPopupTrigger` 分支）。
     * [x] / [y] 是**组件局部坐标**（`MouseEvent.x/y`），仅当取不到全局光标位置时
     * 才作为回退锚点；正常情况下由 [cursorAnchoredShow] 换算为屏幕坐标。
     *
     * 单独暴露为方法而不是让窗口直接持有 `JPopupMenu` 实例 ——
     * 因为菜单必须**每次重建**（见类 KDoc），持有实例会造成"看起来是同一个菜单"的假象。
     */
    fun showAt(settings: SettingsRuntime?, window: IslandWindow?, invoker: Component, x: Int, y: Int) {
        present(settings, window, invoker, anchorOverride = null)
    }

    /**
     * 在 EDT 上弹出菜单，**锚定在鼠标光标处**（托盘右键）。
     *
     * ## 为什么**丢弃**调用方传入的事件坐标（这是本次修复的第 2 条）
     *
     * `TrayIcon` 的 `MouseEvent.xOnScreen/yOnScreen` 在高 DPI 下给的是**物理像素**，
     * 而 `JPopupMenu.show` 要的是**逻辑像素**。125% / 150% 缩放下两者不等，
     * 直接用会把菜单推到逻辑屏幕之外，再由 Swing 钳回右下角 ——
     * 表现为"托盘菜单永远弹在屏幕右下"。**原记**（本类早期 KDoc）此处为
     * "已知偏差，本版未改"，**现已修复**：与原实现 0.8.9 同为**丢弃入参、
     * 改用 `MouseInfo.getPointerInfo().location`**（`089:ui/SwingTrayPopup.kt:27-32`）。
     *
     * 故 [x] / [y] 现在只作为**取不到光标时的回退**（`MouseInfo` 在极少数
     * 无头/远程会话下会返回 `null`），不再是主路径。
     */
    fun showAtScreen(settings: SettingsRuntime?, window: IslandWindow?, x: Int, y: Int) {
        present(settings, window, null, anchorOverride = Point(x, y))
    }

    /**
     * 两个入口的**共用弹出路径**：构造 → 套皮肤（在 [build] 内）→ 装外点关闭 → 定位 → `show`。
     *
     * ## `invoker == null` 时为什么要自造 1×1 owner（本次修复的第 3 条，即 `recon/22` §7.1 的 G1）
     *
     * `TrayIcon` 不是 `Component`，无法直接作 invoker。传 `null` 虽被 Swing 接受，
     * 但代价实测确凿（`recon/menu-lab/DismissLab.java`）：`JPopupMenu.isPopupMenu()`
     * 因 `invoker == null` 返回 false（JDK 源码 `JPopupMenu.java:921`）→ 菜单
     * **不进 `MenuSelectionManager` 的选中路径**（实测路径长度 0）→
     * **上下键 / 回车 / Esc 全部失效**。
     *
     * 原实现 0.8.9 为此造了一个 1×1 的不可见 `JWindow`（`089:ui/SwingTrayPopup.kt:36-40`，
     * 即 `recon/08` M6）。本次照做，但**理解后自己写**（见 [ownerWindow] 的 KDoc）：
     * - `Window.Type.POPUP`：不进任务栏、不抢焦点 —— 这点是**关键**，
     *   普通 `JWindow` 会抢前台，而词岛/托盘场景恰恰**不能**抢焦点
     *   （抢了会把用户的播放器顶到后台，那是比菜单错位更严重的回归）。
     * - `isAlwaysOnTop = true`：保证菜单画在无关窗口之上。
     * - `setSize(1, 1)`：owner 本身不可见，尺寸只用于给 Swing 一个合法的
     *   `graphicsConfiguration`，菜单的真实位置由 [placeMenu] 算好后直接设上去。
     *
     * ⚠️ **与 [GlobalMenuDismisser] 的关系必须澄清**（任务书要求验证这一点）：
     * 加 owner **不会**破坏外点关闭。原因是钩子判定"是否在菜单外"用的是
     * `insideAnyPopup`，它遍历**所有可见窗口**并只认"含 `JPopupMenu` 的窗口"
     * （见 `GlobalMenuDismisser.insideAnyPopup` 的 `containsPopup` 递归）——
     * owner 窗口**不含** `JPopupMenu`（菜单是 `show(owner.contentPane, 0, 0)` 之后
     * 由 Swing 另建的 popup 窗口），故 owner 不会被误判成"菜单内"，
     * 点它照样关闭菜单。而钩子的 arm/disarm 只挂在 `PopupMenuListener` 上，
     * **完全不读 invoker** —— 两条链路正交。
     *
     * ## 为什么整段 `runCatching`
     *
     * 菜单弹出失败（构造异常 / popup 窗口创建失败）会让 AWT 的鼠标监听抛出 ——
     * 后果是"右键没反应"，而用户看不到任何提示。这里兜住并记日志：
     * 菜单是增强入口，它坏了不该影响词岛本体。
     *
     * @param anchorOverride 托盘入口传入的**事件坐标回退值**；词岛入口传 `null`
     *   （此时用 invoker 的屏幕位置 + 组件局部坐标换算）。
     */
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
                // ⚠️ 定位必须是"**先算位置 → 移 owner → 再 show**"，且两个入口都要 owner。
                //
                // 实测（Temurin 21.0.12.1 / Win11，与 `recon/menu-lab` 同法）：
                // - `menu.setLocation(p)` + `menu.show(真实组件, 0, 0)` → **位置被忽略**，
                //   菜单落在 `invoker 屏幕位置 + (0,0)` —— 因为 `show(invoker,x,y)` 是
                //   **以 invoker 为原点**算的，会覆盖 `setLocation` 设的值。
                // - `menu.setLocation(p)` + `menu.show(1×1 owner, 0, 0)` → **同样无效**（得到 0,0）。
                // - `owner.setLocation(p)` + `menu.show(owner.contentPane, 0, 0)` → ✅ 精确命中。
                //   把 owner 摆到目标位置，菜单以 owner 为原点偏移 0 即成为绝对定位。
                //
                // 后者正是原实现 0.8.9 的做法（`089:ui/SwingTrayPopup.kt:60-63`）。
                // 故**不能**"词岛路径直接用 invoker 省一个窗口" —— 那条路会让位置算得
                // 再对也落不到位。`recon/25` §2.3 的差异表已据此更正。
                //
                // ⚠️ **尺寸必须用 [IslandMenuSkin.displaySize]，不能用 `menu.preferredSize`**
                // （BUG-019，本次修复的核心）：见 [placeMenu] 的 KDoc。
                val size = IslandMenuSkin.displaySize(menu)
                val at = placeMenu(size, anchor, owner.graphicsConfiguration)
                // ⚠️ **必须在 `show` 之前**定方向：子菜单展开时才会去读它，
                // 而"读"发生在 `JMenu.getPopupMenuOrigin()`（见 IslandMenuSkin.DirectionalMenu）。
                // 在 show 之后设，第一次展开的子菜单就已经按 JDK 的逐层判定走过了。
                val screen = screenBounds(owner.graphicsConfiguration)
                IslandMenuSkin.decideChainDirection(
                    menu, at.x, screen.x, screen.x + screen.width,
                )
                owner.setLocation(at)
                // owner 必须 `isVisible`，否则它的 `contentPane` 不是有效锚点、
                // 菜单会落在 (0,0)（实测方案 D）。owner 是 1×1，视觉上不可见。
                owner.isVisible = true
                // `show` **不阻塞** —— 它只是把 popup 窗口显示出来并返回。
                // 这一点是**承重**的：外点关闭钩子装在 `PopupMenuListener` 上，
                // 若 show 真的阻塞，钩子就要等菜单关了才装得上，等于没装。
                menu.show(owner.contentPane, 0, 0)
                System.out.println(
                    "[CDL] 右键菜单已弹出 @(${at.x},${at.y}) " +
                        "size=(${size.width},${size.height})",
                )
            }.onFailure {
                // ⚠️ 必须打**完整堆栈**：原来只打 `toString()`，于是真机上 19 次失败
                // 只留下一行没有调用点的消息 —— 定位根因时被迫从 JDK 源码反推整条
                // 调用链，绕了一大圈。异常类型 + 消息不足以定位，**调用点才是**。
                System.err.println("[CDL] 右键菜单弹出失败：$it")
                it.printStackTrace()
            }
            Unit
        }
        if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeLater(show)
    }

    /**
     * 菜单关闭时把 1×1 owner 窗口**隐藏**。⚠️ **BUG-128**。
     *
     * ⚠️ **2026-09-24 更正一处误引**：这里原写 `⚠️ BUG-020` ✗ ——
     * 但台账的 **BUG-020** 讲的是「**穿透极性被反向**」（本项目最贵的那个坑 ✓），
     * **与"隐藏 owner 窗口"毫无关系** ✗（勘察报告 W6-02 指出）。
     * 全库另 **6 处**引 `BUG-020` 都是穿透极性 ✓（那 6 处是对的 ✓）—— **只有这一处错** ✗。
     *
     * ⇒ 已改引 **BUG-128** ✓，并**在台账补了该条目** ✓
     *   （按「**编号是引用契约**」：引了编号，台账就得有；**引错编号，等于没有** ✓）。
     *
     * ## 不修会怎样
     *
     * [present] 里有 `owner.isVisible = true`（那是必需的 —— 见那里的注释：
     * owner 不可见时菜单会落 `(0,0)`），但**原实现再无任何一处把它设回 `false`**。
     * 于是：菜单关了，那个 `JWindow` 仍然 `isShowing == true`，**永久可见**。
     *
     * 这不只是"留了个 1×1 的小窗口"：
     * [GlobalMenuDismisser.insideAnyPopup] 的判定是**遍历 `Window.getWindows()`
     * 里所有 `isShowing` 的窗口**再逐个 `containsPopup`。一个常驻可见窗口就是一个
     * **持续存在的判定输入**。它现在恰好不含 `JPopupMenu` 所以不误判，
     * 但那是"碰巧安全" —— 任何将来让 owner 内容树里出现 `JPopupMenu` 的改动
     * （哪怕是为别的目的）都会立刻把"点菜单外关闭"变成"点哪都不关"。
     * 状态不该留在那里等人踩。
     *
     * ## 为什么单独挂一个监听，而不是塞进 [installOutsideDismiss]
     *
     * 两件事的**生命周期不同、失败面也不同**：`installOutsideDismiss` 装的是
     * 全局鼠标钩子（跨线程、可能装不上、有降级路径），而这里只是"把 owner 设回不可见"
     * （同线程、不可能失败）。混在一个 `PopupMenuListener` 里会让"钩子的降级逻辑"
     * 与"owner 的显隐"互相纠缠 —— 例如降级路径若提前 `return`，owner 就再也不隐藏了。
     * 分开挂，[hideOwnerOnClose] 只有一条语句，**没有任何提前返回**。
     *
     * ## 为什么 `Invisible` 与 `Canceled` 都要写（与 [installOutsideDismiss] 同理）
     *
     * 这两条是 `JPopupMenu` 的两条**不同**关闭路径（前者来自 `setVisible(false)`，
     * 后者来自 `MenuSelectionManager.clearSelectedPath()`），实测各自触发、
     * **不保证都触发**。漏写任何一个都会留下一个永不隐藏的 owner。
     * 重复执行 `isVisible = false` 本身幂等，故两个都写是安全且必要的。
     *
     * ## 为什么隐藏 owner 不会破坏外点关闭（任务书要求验证这一点）
     *
     * `insideAnyPopup` 只认"含 `JPopupMenu` 的窗口"（见其 `containsPopup` 递归），
     * 而菜单是 `show(owner.contentPane, 0, 0)` 之后由 Swing **另建**的 popup 窗口
     * 承载的，owner 自己那 1×1 的 `contentPane` 里没有 `JPopupMenu`。
     * → 隐藏 owner 只是**移除了一个永不生效的判定输入**，判定结果不变。
     */
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

    /**
     * 取／造菜单的 **owner 窗口**（1×1、不可见、不抢焦点）。
     *
     * ## 为什么**两个入口都用它**，而不是"词岛路径直接用 invoker"
     *
     * 这是本次实现中途**被实测纠正**的一处设计（原设想是省一个窗口对象）。
     * 实测结论（Temurin 21.0.12.1 / Win11，见 [present] 的注释）：
     * `JPopupMenu` 的最终位置**只能**由 `owner.setLocation(...)` +
     * `show(owner.contentPane, 0, 0)` 决定；
     * 若 owner 是别的真实组件（或 `setLocation` 只设在 menu 上），位置会被忽略。
     * → **必须统一走这条路径**，否则词岛路径算得再准也落不到位。
     *
     * ## 为什么复用而非每次新建
     *
     * 菜单每次右键都重建（见类 KDoc），若 owner 也跟着建，反复弹菜单会持续创建
     * `JWindow`（每个都是一个原生窗口 + `graphicsConfiguration` 缓存），
     * 长期是实打实的资源增长。
     *
     * ## 为什么 `Window.Type.POPUP`
     *
     * 不进任务栏、**不抢焦点** —— 后者是关键：词岛是 `WS_EX_NOACTIVATE` 悬浮件，
     * 若 owner 抢了前台，会把用户正在看的播放器顶到后台，
     * 那是比"菜单位置不对"严重得多的回归。
     */
    private fun ownerWindow(): JWindow = trayOwner ?: JWindow().apply {
        type = Window.Type.POPUP
        isAlwaysOnTop = true
        setSize(1, 1)
    }.also { trayOwner = it }

    /**
     * owner 窗口。`null` = 尚未创建。**两入口共用**（见 [ownerWindow]）。
     *
     * ⚠️ **必须在 EDT 上读写**（[present] 已保证）。不能是 `@Volatile` 就完事 ——
     * 它持有的是 Swing 窗口引用，跨线程共享本身就不合规。
     */
    private var trayOwner: JWindow? = null

    /**
     * 求菜单应当锚定的**屏幕坐标**（即"菜单底左角该坐在哪"）。
     *
     * 优先级：
     * 1. **真实逻辑光标**（`MouseInfo`）—— 这是用户诉求的核心：
     *    「随鼠标光标弹出」。两个入口都走这条。
     * 2. [fallback]（托盘入口传入的事件坐标）—— 仅在取不到光标时兜底。
     * 3. [invoker] 的屏幕位置 —— 最后兜底（理论上到不了，除非无头环境）。
     *
     * ## 为什么托盘入口要**丢弃**事件坐标（而不是用它）
     *
     * `TrayIcon` 的 `MouseEvent.xOnScreen/yOnScreen` 在高 DPI 下是**物理像素**，
     * 而 Swing 要**逻辑像素**。125%/150% 缩放下两者不等，直接用会把菜单推出
     * 逻辑屏幕、再被钳到右下角 —— 这正是「托盘菜单永远弹在右下」的根因。
     * 原实现 0.8.9 同此（`089:ui/SwingTrayPopup.kt:27-32`）。
     */
    private fun menuAnchor(invoker: Component?, fallback: Point?): Point? =
        MouseInfo.getPointerInfo()?.location
            ?: fallback
            ?: invoker?.takeIf { it.isShowing }?.locationOnScreen

    /**
     * 计算菜单该出现在屏幕的哪个位置（**该返回给 owner.setLocation 的坐标**）。
     *
     * ## ⚠️ BUG-019：这里为什么**不再**取 `menu.preferredSize`（本次修复的核心）
     *
     * **原实现**（本函数旧版）：
     * ```kotlin
     * val size = menu.preferredSize   // ← 真机抛异常，菜单从未弹出
     * ```
     * 真机日志 19 次：
     * ```
     * [CDL] 右键菜单弹出失败：java.awt.IllegalComponentStateException:
     *   component must be showing on the screen to determine its location
     * ```
     *
     * **抛出机制**（逐层取证见 `recon/28`）：
     * ```
     * JComponent.getPreferredSize()                    // 已套皮肤的项：preferredSize 字段非 null，安全
     *   └─ JPopupMenu 自己的 preferredSize 走 UI 布局（DefaultMenuLayout）
     *        └─ sun.swing.MenuItemLayoutHelper.calcExtraWidths()
     *             └─ SwingUtilities2.getLeftSideBearing(char)
     *                  └─ FontDesignMetrics.getMetrics() → FontStrikeDesc
     *                       └─ StrikeCache.xAdvanceOffset   ← sun.font 的共享静态初始化
     * ```
     * 这条链上任何一环在**菜单还没有原生 peer**（`show()` 之前）时试图定位，
     * 都会走到 `java.awt.Component#getLocationOnScreen_NoTreeLock`：
     * ```
     * if (peer != null && isShowing()) { ... }
     * else throw new IllegalComponentStateException(
     *     "component must be showing on the screen to determine its location");
     * ```
     * 异常被 `present` 的 `.onFailure` 吞掉 → **菜单从未显示**。
     *
     * **修法**：尺寸改由 [IslandMenuSkin.displaySize] **纯几何自算**（不碰任何
     * 字体度量的共享静态状态），菜单有没有 peer 都算得一样准。
     *
     * ## 算法：**底左角坐在光标上**（与原实现 0.8.9 同形，`089:ui/SwingTrayPopup.kt:51-60`）
     *
     * 即 `y = cursor.y - menuHeight` —— 菜单在光标**上方**展开、左下角贴着鼠标。
     * 这是 Windows 右键菜单的惯例方向，好处是**不会盖住用户刚点的那个东西**。
     *
     * 三条越界处置，顺序即优先级：
     * 1. 右边越界 → 整体左移，使**右边缘贴屏幕右边缘**（而不是把左边缘压回屏幕内，
     *    那会让菜单在光标左侧远开，视线要跳）。
     * 2. 左边越界 → 钉在屏幕左边缘。
     * 3. 上边越界（光标离屏幕顶部太近）→ **翻转到光标下方**（`y = cursor.y`），
     *    而不是钉在屏幕顶部 —— 钉顶部会让菜单盖住光标所在处。
     *
     * ⚠️ 用**光标所在那块屏**的 bounds，不是默认屏：多显示器下用错屏，
     * 菜单会跑到另一块屏上（原实现 `:54` 的 `device.defaultConfiguration.bounds` 同此）。
     *
     * ## 与 [IslandMenuSkin.fitPopupToWorkArea] 的职责边界（**不要重复做**）
     *
     * 这里只管"**从哪展开**"，用的是**整屏 bounds**；
     * "**别被任务栏盖住**"由皮肤在显示后用 `getScreenInsets` + 工作区处理。
     * 两处都做会互相覆盖，表现为菜单"跳一下"。
     *
     * @param size 菜单尺寸，由 [IslandMenuSkin.displaySize] 算出（**不依赖 peer**）。
     * @param anchor 菜单底左角应当坐落的屏幕坐标（来自 [menuAnchor]）。
     * @param gc owner 的图形配置，用于取它所在屏的 bounds；`null` 时退回默认屏。
     */
    private fun placeMenu(size: Dimension, anchor: Point, gc: GraphicsConfiguration?): Point {
        val screen = screenBounds(gc)

        var x = anchor.x
        var y = anchor.y - size.height
        if (x + size.width > screen.x + screen.width) x = screen.x + screen.width - size.width
        if (x < screen.x) x = screen.x
        if (y < screen.y) y = anchor.y
        return Point(x, y)
    }

    /**
     * [gc] 所在那块屏的**整屏** bounds（**含**任务栏占用的区域）。
     *
     * ## 为什么用整屏而不是工作区（与 [IslandMenuSkin.fitPopupToWorkArea] 的分工）
     *
     * 这里回答的是"**从哪展开**"——那是"用户的鼠标在哪"的问题，用的是**整屏**；
     * "别被任务栏盖住"是另一件事，由皮肤在显示后用 `getScreenInsets` 处理。
     * 两处都做会互相覆盖，表现为菜单"跳一下"（见 [placeMenu] 的 KDoc）。
     *
     * ⚠️ 必须用**光标所在那块屏**：多显示器下用默认屏的 bounds 会把菜单算到另一块屏上。
     *
     * 抽成函数是因为它现在有**两个**调用点：[placeMenu] 算落点，
     * 以及 [present] 里算[子菜单链方向][IslandMenuSkin.decideChainDirection]的屏幕左右缘。
     * 两处必须用**同一块屏**的同一个矩形 —— 各算一遍迟早会漂移。
     */
    private fun screenBounds(gc: GraphicsConfiguration?): Rectangle {
        val device = gc?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        return device.defaultConfiguration.bounds
    }

    /**
     * 给菜单装上「点外部即关闭」的补丁。
     *
     * ## 为什么钩子随菜单**可见性**装卸，而不是随插件生命周期
     *
     * `WH_MOUSE_LL` 是**全局**钩子：装上之后，**整个桌面的每一次鼠标按下**
     * 都要绕经本进程一次。菜单只活几秒，若钩子常驻，就等于插件在用户
     * 不做任何操作时也持续介入系统输入链路 —— 那是既无必要又有风险的常驻开销。
     * 故：`popupMenuWillBecomeVisible` 时装、`Invisible` / `Canceled` 时摘。
     *
     * ## 为什么三个回调都要写全（不能只写 Invisible）
     *
     * - `popupMenuWillBecomeInvisible`：正常关闭（点了菜单项 / 调了 `setVisible(false)`）。
     * - `popupMenuCanceled`：**被取消**（Esc、点外部、宿主抢焦点）。
     *   它与 Invisible 是**两条不同的路径**（前者来自 `JPopupMenu.setVisible(false)`，
     *   后者来自 `MenuSelectionManager.clearSelectedPath()` 的 `JPopupMenu.firePopupMenuCanceled`），
     *   实测两者会各自触发、**不保证都触发**。漏写任何一个都会留下装好的钩子 ——
     *   那不只是性能问题，而是"菜单早关了，钩子还在跑"的资源泄漏。
     *
     * ⚠️ 卸载**不能只依赖**这两个回调：万一将来某条路径两个回调都没走（例如宿主强杀
     * 插件进程），钩子会随进程一起消失（钩子是进程级资源），所以最坏情况仍然安全。
     */
    private fun installOutsideDismiss(menu: JPopupMenu) {
        // 回调体在 EDT 上执行（[GlobalMenuDismisser] 的契约），故可以直接碰 Swing。
        val dismisser = GlobalMenuDismisser {
            // 二次判 `isVisible`：从"判定为外点"到"回调执行"之间菜单可能已被关掉，
            // 重复 `setVisible(false)` 本身无害，但这次判定已经过期，不该再触发关闭逻辑。
            if (menu.isVisible) menu.setVisible(false)
        }
        // 钩子装不上时的**降级路径**（本次新增，见 §下方 KDoc）。`null` = 未启用。
        var fallbackListener: AWTEventListener? = null
        val detachFallback = {
            fallbackListener?.let { runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(it) } }
            fallbackListener = null
        }

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {
                // ⚠️ **必须取 `getWindowAncestor(menu)`，不能传 invoker/owner。**
                //
                // 三个候选的区别（这是本次修正的一处，与原实现
                // `089:ui/SwingTrayPopup.kt:65`、`089:ui/IslandMenu.kt:53` 同形）：
                // - `menu` 自己：`JPopupMenu` 是 `JComponent`，**不是** `Window`，类型都不符；
                // - `owner` / `invoker`：那是**看不见的载体**（1×1 owner 或词岛面板），
                //   它的矩形与菜单矩形无关 —— 用它的矩形做剔除会把整个屏幕都判成"菜单外"，
                //   等于没剔除（但也不会错，只是白做）；更糟的是词岛面板的矩形可能
                //   **盖住菜单**，那样用户点菜单也会被判成"点在菜单矩形内"而不投递，
                //   反而不关闭菜单。
                // - `getWindowAncestor(menu)`：**Swing 为这个 popup 建的那个顶层窗口**，
                //   它的 `GetWindowRect` 才是真正"菜单画在哪"。
                //
                // 另外：`popupMenuWillBecomeVisible` 触发时 popup 窗口**已经存在**
                // （Swing 先建窗再 fire 该事件），故此刻取 ancestor 是有效的；
                // 若窗口意外不存在（`null`），传 `null` 会让钩子装不上 → 走下面的降级分支。
                val dismisserWindow = SwingUtilities.getWindowAncestor(menu)
                // ⚠️ 2026-09-17 修正：**不因 ancestor 为 null 就放弃装钩子**。
                //
                // 真机取证：这条降级日志出现 **17 次**，且**每次都是 `windowAncestor=null`** ——
                // 说明 `popupMenuWillBecomeVisible` 触发时 ancestor 就是 null。
                // 而旧代码是 `dismisserWindow != null && dismisser.arm(...)`，
                // **短路让 `arm()` 从未被调用**，钩子从来没装上。
                //
                // 后果：只剩 AWT 降级路径，而它**看不到本进程之外的点击**
                // （点桌面 / 别的程序不产生本 JVM 的 AWT 事件）→
                // 用户报的「点击菜单外部时菜单消失」**必然做不到**。
                //
                // 根因：把一个**纯优化**（廉价剔除用的菜单矩形）当成了**装钩子的前提**。
                // `arm` 已改为接受可空 window（见其 KDoc），故这里直接传。
                val armed = dismisser.arm(dismisserWindow)
                if (!armed) {
                    // 降级：钩子**真的**装不上（非 Windows / JNA 的 User32 不可用 / 系统限制）。
                    // **必须记日志**，否则这个能力缺口完全不可见 —— 前两次真机反馈里
                    // "点外面不消失"就有一段时间无法归因，正是因为它不吭声。
                    //
                    // ⚠️ 措辞与旧版**有意不同**：旧版把"ancestor 为 null"也归进这条，
                    // 于是**两种完全不同的原因（拿不到窗口 vs 装不上钩子）在日志里长得一样**，
                    // 而它们的修法截然不同。现在分开报，并把降级的**能力边界**写进日志 ——
                    // 否则下一个看日志的人仍会以为"降级了但还能用"。
                    System.err.println(
                        "[CDL] 菜单外点关闭降级为 AWTEventListener（WH_MOUSE_LL 钩子**装不上**；" +
                            "windowAncestor=${dismisserWindow?.javaClass?.simpleName ?: "<null>"}）；" +
                            "⚠️ 该降级**看不到本进程之外的点击**（点桌面/别的程序不会关菜单）。" +
                            "菜单仍可用 Esc 或选择菜单项关闭。",
                    )
                    if (fallbackListener == null) {
                        val listener = AWTEventListener { awtEvent ->
                            val press = awtEvent as? MouseEvent ?: return@AWTEventListener
                            if (press.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
                            if (!menu.isVisible) return@AWTEventListener
                            // 与钩子**同一套命中判定**：落在任何含 `JPopupMenu` 的窗口内 = 菜单内。
                            // 这里用 `press.xOnScreen/yOnScreen`（屏幕坐标），与 `insideAnyPopup`
                            // 内部用 `GetWindowRect`（物理像素）比较 —— 两者在 100% 缩放下一致，
                            // 高 DPI 下**可能有偏差**。这是降级路径的已知局限，
                            // 故它**只是兜底**：能覆盖"点我们自己的窗口"这类主流场景。
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
                    // 钩子装上了：确保降级路径是**关掉**的（上一次弹出可能降级过）。
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

    /**
     * 点是否落在**本进程任何一个可见的菜单弹出窗**内。
     *
     * 与 [GlobalMenuDismisser] 内部的同名判定是**同一套语义**（都要求窗口里含
     * `JPopupMenu`），此处独立实现一份是因为降级路径（`AWTEventListener`）需要它，
     * 而 `GlobalMenuDismisser` 的那份是 `private`。
     *
     * ⚠️ **不抽公共函数是刻意的**：两者的坐标空间不同（这里收的是 Swing 屏幕坐标，
     * 那边收的是 Win32 物理像素），强行合并会把"用哪个坐标系"这件事藏进
     * 一个共用函数里，而它恰恰是这两条路径最容易出错的地方。分开写、各自注释清楚，
     * 比"少写几行但把差异藏起来"更安全。
     */
    private fun insideAnyPopup(x: Int, y: Int): Boolean {
        for (window in Window.getWindows()) {
            if (!window.isShowing) continue
            if (!window.bounds.contains(x, y)) continue
            if (containsPopup(window)) return true
        }
        return false
    }

    /** 递归判定容器里是否含 `JPopupMenu` —— 用于区分"菜单弹出窗"与"别的窗口"。 */
    private fun containsPopup(container: java.awt.Container): Boolean {
        if (container is JPopupMenu) return true
        for (child in container.components) {
            if (child is java.awt.Container && containsPopup(child)) return true
        }
        return false
    }

    /**
     * **预设列表子菜单** —— 列出预设 + 导入 / 导出 / 删除 / 更改。
     *
     * ## 用户原话（本轮结构要求）
     *
     * > 「一级菜单放…**预设列表**等…」+「**预设列表 ▸** ← **列出 presets 目录里的预设**」
     *
     * ## 2026-09-23：从「配置预设 ▸ 选择预设 ▸ 预设」**扁平化**成「预设列表 ▸ 预设」
     *
     * 旧结构里，用户想看"有哪些预设"要**悬停两次**（先展开「配置预设」，再展开「选择预设」）；
     * 而"列出预设"正是这一项存在的理由 —— 它不该藏在第二层。
     * 现在四个管理动作（导入 / 导出 / 删除 / 更改）留在顶部、用**分隔线**与预设清单分开，
     * 预设清单**直接就是这一层的条目**。
     *
     * ## 为什么根用 [IslandMenuSkin.DirectionalMenu]（2026-09-23 补）
     *
     * `IslandMenuSkin` 的 KDoc 立了契约：「**菜单里所有 `JMenu` 都必须用它建**」——
     * 否则子菜单方向不受 `decideChainDirection` 的统一决策管辖，
     * 会退回 JDK 的"逐层各自判定"（用户报过「一会向左、一会向右」）。
     * 本函数此前用的是**裸 `JMenu`**（漏了），本轮一并改齐 ——
     * 与「一级菜单那三个开关漏了 `keepOpen`」是同一种"局部漏掉全局契约"的形态。
     *
     * ## 每次弹出时**重新扫描**预设目录
     *
     * 预设是**文件**，用户可能在插件运行期间往目录里丢了一个（或删了一个）。
     * 缓存列表会让"我明明放进去了却没出现" —— 而本菜单的构建成本是
     * **一次目录列举 + 几个小文件读取**（预设只有几十行），远低于用户感知阈值。
     * 故**不缓存**：每次右键都现扫。
     */
    private fun buildPresetMenu(settings: SettingsRuntime): JMenu {
        val root = IslandMenuSkin.DirectionalMenu(MENU_TITLE_PRESET_LIST)
        val dataDir = settings.dataDirectory()

        root.add(JMenuItem("导入配置…").apply {
            addActionListener { importPreset(settings) }
        })
        root.add(JMenuItem("导出配置…").apply {
            addActionListener { exportPreset(settings) }
        })
        // ── 2026-09-22：删除 / 更改预设（用户原话「**在设置项中要提供删除、更改
        //    预设的入口**」）────────────────────────────────────────────────
        //
        // 设置页同轮也补了两个同名按钮（`preference_config.json` 的 `deletePreset` /
        // `changePreset`）。两处**都**给：设置页给"我知道要删/要改"的人，
        // 这里给"我正在调参、顺手存一下"的人 —— 而**实现只有一条**
        //（`CdlRuntime.openPresetManager` → `settingsui/PresetManagerDialog`），
        // 故不存在"菜单里删了、设置页没删"这类漂移。
        //
        // 语义见 `PresetStore.moveToDeleted` / `PresetStore.overwrite`：
        // 删除 = 移入 `presets/_deleted/`（不是 `delete()`）；更改 = 用当前配置覆盖。
        // 内置预设（代码里的常量，没有文件）在窗里**置灰**。
        //
        // ⚠️ 不在 `settingsAvailable()` 为 false 时禁用它们：窗里会 toast 说明
        //    "设置系统尚未就绪"，比一个灰掉且没有解释的菜单项更好。
        root.add(JMenuItem("删除预设…").apply {
            addActionListener { CdlRuntime.openPresetDeleter() }
        })
        root.add(JMenuItem("更改预设…").apply {
            addActionListener { CdlRuntime.openPresetChanger() }
        })
        root.addSeparator()

        // ── 预设清单：**直接列在这一层**（2026-09-23 扁平化，旧为「选择预设」子菜单）──
        // 内置在前、已导入在后（内置是"随时可用"的那批）。
        val listing = PresetStore.list(dataDir)
        // ⚠️ 是 `live`（当前生效快照），**不是 `load()`** ——
        // `load()` 属于 `SettingsStore`（从磁盘读），而 `SettingsRuntime` 暴露的是
        // `live`（内存里的生效态）。用 `load()` 会编译不过，用错了语义会读到旧值。
        val current = settings.live

        val group = javax.swing.ButtonGroup()
        // 快照只取一次（判据是纯函数，见 [PresetStore.matches]）。
        val currentValues = current.asMap()
        // ⚠️ 用**计数器**而不是 `pick.itemCount`（旧写法）——扁平化之后"这一层"还混着
        //    上面那四个管理动作，`root.itemCount` 恒 ≥ 4，判断"有没有预设"会**恒为假**，
        //    于是空列表时用户看到一个**没有任何预设的空子菜单**（看起来像坏了）。
        var presetCount = 0
        for (preset in PresetStore.BUILT_IN + listing.presets) {
            val item = JRadioButtonMenuItem(preset.name)
            // 勾 = 当前快照与这个预设**完全一致**（不是"曾经点过"）。
            // 这样用户改了任何一项，勾会自动消失 —— 界面反映的是"现在是不是这个预设"，
            // 而不是"上次点了哪个"。后者会让界面说谎（显示勾着、实际已改）。
            //
            // ⚠️ 判据在 [PresetStore.matches] 里，**不是**就地 `all{}` ——
            // 空表预设（「默认（全部复原）」）的 `all{}` 恒为 `true`，
            // 会让那一行在任何状态下都显示为已勾选（P0-2）。
            item.isSelected = PresetStore.matches(preset, currentValues)
            item.addActionListener { applyPreset(settings, preset) }
            // ── ⚠️ 2026-09-21：**本轮补的漏网点**（用户报的「部分项点击即消失」）──────
            //
            // 应用预设是**调参类**：它一次改一整组设置，而用户选预设的典型动作是
            // "试一个 → 看看效果 → 换一个再试"。此前这里没有 `keepOpen`，
            // 于是**每试一个预设都要重新右键 + 展开两层子菜单** ——
            // 这正是用户说的「不能连续调参」。
            //
            // 勾选态由 `PresetStore.matches` 按**当前快照**判定（不是"点过哪个"），
            // 故保持打开时用户能当场看到勾在预设之间移动 —— 菜单本身就成了对比视图。
            item.keepOpenFor(MenuItemRole.ADJUST)
            group.add(item)
            root.add(item)
            presetCount++
        }
        if (presetCount == 0) {
            root.add(JMenuItem("（无预设）").apply { isEnabled = false })
        }

        // 读不出来的文件**如实列出**（不静默丢 —— 否则用户会以为文件没生效）。
        if (listing.skipped.isNotEmpty()) {
            root.addSeparator()
            root.add(JMenuItem("⚠ ${listing.skipped.size} 个预设读取失败").apply {
                isEnabled = false
                toolTipText = listing.skipped.joinToString("\n")
            })
        }
        return root
    }

    // 勾选判据 `matches` 已上移到 [PresetStore.matches]（公开纯函数）——
    // 它带着 P0-2 的"空表预设不参与勾选"这条规则，必须能被探针直接断言，
    // 而不是藏在这个 UI 类的 private 方法里。

    /** 应用预设：**整体 `apply` 一次**（不是逐键 —— 逐键会让订阅者看到一串中间态）。 */
    private fun applyPreset(settings: SettingsRuntime, preset: Preset) {
        if (preset.values.isEmpty()) {
            // "全部复原"：把预设声明过的键（此处为空）之外的**全部**写回默认。
            // 空表预设的语义就是"回到开箱状态"，故这里要显式构造全量默认表。
            val defaults = SettingRegistry.all.associate { it.key to it.default }
            runCatching {
                settings.apply(
                    PresetStore.filterForPreset(defaults),
                    dev.cdl.settings.SettingChange.Kind.PRESET,
                )
            }.onFailure { System.err.println("[CDL] 复原默认值失败：$it") }
            return
        }
        runCatching {
            settings.apply(
                PresetStore.filterForPreset(preset.values),
                dev.cdl.settings.SettingChange.Kind.PRESET,
            )
        }.onSuccess {
            // ★ 2026-10-18：应用后**关闭菜单** —— 下次打开会按新快照重建，
            //   勾与同层开关都从 `live` 重新计算。
            //   原来菜单保持打开但从不刷新 ⇒ 勾停在旧项、开关显示旧值（界面在说谎）。
            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath()
        }.onFailure {
            System.err.println("[CDL] 应用预设「${preset.name}」失败：$it")
            notifyToast("应用预设「${preset.name}」失败：${it.message ?: it}", warning = true)
        }
    }

    /** 导出：选保存位置，**默认文件名 = 当前日期**（用户没指定预设名时的合理兜底）。 */
    private fun exportPreset(settings: SettingsRuntime) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "导出词岛配置"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("JSON 预设 (*.json)", "json")
            selectedFile = java.io.File("词岛配置-${java.time.LocalDate.now()}.json")
        }
        if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        // 用户没写扩展名时补 `.json`（否则 Format.detect 会按 TXT 处理，与对话框承诺不符）。
        val target = if (file.extension.isEmpty()) java.io.File(file.parentFile, file.name + ".json") else file
        // ★ 2026-10-18：**覆盖前必须问一次**（原来直接写，同名文件被静默替换）。
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
                System.err.println("[CDL] 已导出配置：$it")
                notifyToast("已导出到 $it")
            }
            .onFailure {
                System.err.println("[CDL] 导出配置失败：$it")
                notifyToast("导出失败：${it.message ?: it}", warning = true)
            }
    }

    /**
     * 导入：选文件 → 解析 → **存入预设列表** → 应用；**结果必须让用户看见**。
     *
     * 见 [PresetStore.install]（P0-1：导入后要出现在「选择预设」里）
     * 与 [notifyImportResult]（P1-2：结果不能只进 stderr）。
     */
    private fun importPreset(settings: SettingsRuntime) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "导入词岛配置"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("配置预设 (*.json, *.txt)", "json", "txt")
        }
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val source = chooser.selectedFile
        PresetStore.importFrom(source).fold(
            onSuccess = { report ->
                // ★★ 2026-10-18：**0 项可用 ⇒ 不入库、不应用**。
                //   否则会埋一条"点一下就全部复原"的地雷（空表预设的语义 = 回到默认）。
                if (report.preset.values.isEmpty()) {
                    notifyImportFailure(
                        "「${report.preset.name}」没有可用的设置项" +
                            "（${report.unknownKeys.size} 个键本版不认识，" +
                            "${report.unparsableKeys.size} 个值无法解析）",
                    )
                    return@fold
                }
                // **先存入列表**（P0-1）：用户原话是「允许**已导入**和默认预设出现在列表中」，
                // 而此前"导入"实际只等于"应用一次"，列表里不会多出这一条。
                val installResult = PresetStore.install(settings.dataDirectory(), source)
                val installed = installResult.getOrNull()
                val installError = installResult.exceptionOrNull()?.message
                runCatching {
                    settings.apply(
                        PresetStore.filterForPreset(report.preset.values),
                        dev.cdl.settings.SettingChange.Kind.PRESET,
                    )
                }
                    .onFailure { System.err.println("[CDL] 应用导入的配置失败：$it") }
                notifyImportResult(report, installed, installError)
            },
            onFailure = { notifyImportFailure("导入配置失败：${it.message ?: it}") },
        )
    }

    /**
     * 把导入结果如实告诉用户（P1-2）。
     *
     * ## 为什么必须 toast
     *
     * 原先这里只打 `System.err`，而宿主是 **GUI 子系统**（PE subsystem = 2）——
     * 标准句柄没有去处，**stderr 是黑洞**（取证见 `CdlRuntime.notifyWarning` 的 KDoc）。
     * 于是"导入成功 / 部分成功 / 有未知键"用户**一概不知道**，
     * 而项目纪律是「**凡是"功能降级但插件仍活着"的失败，都必须 toast**」。
     *
     * 详细键名仍走 stderr（诊断用）—— 但**用户可见的那一路必须有**。
     */
    private fun notifyImportResult(
        report: PresetStore.ImportReport,
        installed: java.nio.file.Path?,
        installError: String? = null,
    ) {
        if (report.unknownKeys.isNotEmpty()) {
            System.err.println(
                "[CDL] 导入「${report.preset.name}」：${report.unknownKeys.size} 个键本版没有，" +
                    "已忽略 → ${report.unknownKeys.joinToString(", ")}",
            )
        }
        if (report.unparsableKeys.isNotEmpty()) {
            System.err.println(
                "[CDL] 导入「${report.preset.name}」：${report.unparsableKeys.size} 个值无法解析，" +
                    "已忽略 → ${report.unparsableKeys.joinToString(", ")}",
            )
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

    /** 导入整体失败（文件不存在 / 不是预设文件）—— 同样是"用户必须知道"的失败。 */
    private fun notifyImportFailure(message: String) {
        System.err.println("[CDL] $message")
        notifyToast(message, warning = true)
    }

    /**
     * 发一条宿主 toast。
     *
     * `runCatching` 包住是刻意的：**不让"提示本身失败"再引发一次崩溃**
     * （与 `CdlRuntime.notifyWarning` 同一处理）。
     */
    private fun notifyToast(message: String, warning: Boolean = false) {
        runCatching {
            // ⚠️ `ToastType` 只有 `Success` / `Warning` / `Error` 三个值
            //（反编译取证：`WorkshopApi$Ui$ToastType`）—— **没有 `Info`**。
            val type = if (warning) {
                WorkshopApi.Ui.ToastType.Warning
            } else {
                WorkshopApi.Ui.ToastType.Success
            }
            WorkshopApi.ui.toast(message, type)
        }.onFailure { System.err.println("[CDL] 发送提示失败（提示内容：$message）：$it") }
    }

}
