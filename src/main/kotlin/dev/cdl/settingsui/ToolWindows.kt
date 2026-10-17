// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.host.CdlRuntime
import dev.cdl.settings.SettingDescriptor
import dev.cdl.settings.SettingKind
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.composePackedArgb
import dev.cdl.settings.SettingsRuntime
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * 取色器 / 字体选择器 —— 宿主设置页**表达不了**的那几项的自绘工具窗。
 *
 * ## 为什么不是"独立设置窗口"（本轮结构性改造，2026-09-16）
 *
 * 用户诉求原文：「高级设置的独立窗口（**不要此独立窗口**）没有并入 SPW 设置页中，
 * 也没有并入右键菜单的子菜单中。」
 *
 * 因此原来那个 1009 行的 `SettingsWindow`（左侧 15 组分组树 + 右侧 40 项控件 +
 * 底部应用/取消）**已删除**。它的内容按取值域分成三处：
 *
 * | 取值域 | 归属 | 机制 |
 * |---|---|---|
 * | **闭环**（开关 / 数值 / 静态枚举） | **SPW 设置页** | `preference_config.json` 声明式，宿主自己渲染 |
 * | **闭环**（同上，但用户想从桌面直接改） | **词岛右键菜单的子菜单** | `IslandPopupMenu` 自绘 |
 * | **开放集**（颜色 / 字体族） | **本文件的两个工具窗** | `button` 的 `on_click` 反射唤起，或菜单唤起 |
 *
 * ## 为什么颜色与字体**必须**留在自绘窗（这是唯一的例外，不是没做完）
 *
 * 宿主的 `type` 全集已由宿主解析器字节码**穷尽确证为 5 种**
 * （`switch` / `list` / `seekbar` / `edittext` / `button`，见 `recon/18` §A、`recon/20` §A.7），
 * **没有取色器、没有字体选择器**。而颜色（16,777,216 色）与字体族（本机 250+）
 * 的取值域都是**开放集**：
 *
 * - 用 `edittext` 承载 → 真值可见、可手改，但用户要手打 `#RRGGBB` / 字体名，UX 差；
 * - 用 `list` 承载 → **不可行**：宿主在"配置里的值不在 `entry_values` 里"时，
 *   会把下拉框显示成 `entries[0]` **而配置值不变**（`recon/20` §A.2 的读取陷阱，
 *   `fX.java:551`）—— 界面会**撒谎**，用户一选非预设色就中招。
 *
 * 故：**SPW 设置页放 `edittext` 兜底（真值可见 + 可手改），重交互放在本文件的工具窗**，
 * 两者写同一个键，不冲突。这正是 `recon/20` §C 的结论。
 *
 * ## 与旧 `SettingsWindow` 的差别（删掉了什么）
 *
 * | 旧 `SettingsWindow` 的部件 | 处置 | 理由 |
 * |---|---|---|
 * | 左侧分组树（`buildLeftPane` / `installGroupSelection`） | **删** | 只剩 1 项时导航树是净负担 |
 * | `switch` / `seekbar` / `list` / `edittext` 四类控件行 | **删** | 已由 SPW 设置页承载（BUG-001 的范式要求） |
 * | 底部"应用 / 确定 / 取消" 三键 | **删** | 工具窗语义是"确定即生效"，不需要事务条 |
 * | 两段式预览管线（`publish` 预览 / `apply` 落盘） | **保留** | 铁律：预览永不写盘（`recon/07` §1） |
 * | `disposeWindow` 的退订 | **保留** | 不退订会让"开十次泄漏十个窗口" |
 *
 * ## 线程
 *
 * 所有 Swing 操作在 EDT。静态引用用 [AtomicReference]，因为宿主 `button` 的
 * 反射调用线程不确定（`on_click` 由宿主的 Compose 事件泵触发）。
 */
internal class ColorToolWindow(
    owner: Window?,
    /** 要编辑的颜色键。**每个颜色各需一个入口** —— `on_click` 不能传参（`recon/20` §A.3）。 */
    private val descriptor: SettingDescriptor,
    private val settings: SettingsRuntime?,
    /**
     * **4 键制的通道前缀**（如 `main_lyric.fill.color`）；`null` = 单键制（`descriptor.key`）。
     *
     * ## 为什么要有这个（2026-09-18）
     *
     * 三个歌词色在 v0.20 方案 A 里是**四根滑杆**（`.r/.g/.b/.lightness`），
     * `IslandWindow.mixerColor` 按 `r * lightness/100` 合成 —— **不是单个 COLOR 键**。
     * 用户要求把滑杆换成取色器，取色器就必须能读写这 4 个键，
     * 否则 `openColor(key)` 的 `descriptor.kind != COLOR` 检查会直接拒掉。
     *
     * 写入策略：**`r/g/b` = 目标色通道、`lightness` = 100** ——
     * 这样 `mixerColor` 合成回来**逐通道等于用户选的颜色**（"设一次再读回不改变颜色"）。
     */
    private val groupPrefix: String? = null,
) : JDialog(owner, "个性歌词 · ${descriptor.title}", ModalityType.MODELESS) {

    //
     // 拖动中只 `publish`（不写盘）、松手才 `apply`（写盘）。
     //
     // 理由参见 [ColorPickerDialog] 类注释与 `recon/07` §1 铁律①。**这是本窗唯一的写路径**。
     //
    //
     // 完整取色器（色域板 + 色相条 + 明度条 + RGB + hex）。
     //
     // ## 2026-09-18 换过实现
     //
     // 原用 `ColorPicker`（内联"四根滑杆 + hex"，BUG-009 时期）——
     // 用户给了 SPW 内置设置页的截图，要求换成那种样式：
     // **能一眼看出"这个颜色长什么样"**，而不是在大脑里合成 RGB。
     //
     // 新实现见 [ColorPickerDialog]，本处**只取它的内容面板**（`buildContent()`），
     // 不显示它自身的对话框壳 —— 这样两种形态（独立弹窗 / 嵌入工具窗）共用同一套控件。
     //
     // `onPreview` 接的就是原有的 `preview`，**两段式写路径没变**：
     // 拖动中只 publish（实时预览）、点"确定"才 apply（写盘）。
     //
    /**
     * ⚠️ **必须声明在 [picker] 之前**。
     *
     * `ColorPickerDialog` 的 `init` 里会走 `wireEvents() → syncFromModel() → onPreview.invoke(…)`
     * —— 也就是**构造期间就回调**。而 Kotlin 按**声明顺序**初始化属性，
     * 若本字段声明在 `picker` 之后，回调里任何一次 `statusLabel.text = …` 都会 NPE；
     * 那个异常从构造函数冒出去、被 `ToolWindows.present` 的 `runCatching` 吞掉，
     * **表现为"点了没反应"**（用户看不到任何提示）。
     *
     * 真机日志（2026-09-19）：
     * ```
     * 打开工具窗失败（main_lyric.outline.color）：
     *   java.lang.NullPointerException: Cannot invoke "javax.swing.JLabel.setText(String)"
     *   because "this.statusLabel" is null
     * ```
     * 而且它**只在 `runtime.isDirty` 为真时才触发** → 于是"第一次能开、
     * 改了色没保存、关掉之后**再也打不开**" —— 正是用户报的那个现象。
     *
     * 只把顺序调对**不够**（下次重构又会漏），故同时把 `refreshStatus()` 从
     * [preview] 里移出去：**构造路径不得触碰任何晚初始化的 UI**。
     */
    private val statusLabel = JLabel(" ").apply {
        foreground = MUTED
        border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
    }

    /**
     * 打开时的词岛外观快照（**冻结**，见 [PreviewSnapshot] 的 KDoc）。
     *
     * 用户原话：「示例歌词在**打开此设置项**的取色界面时，**其他设置被固定下来**
     * 并在示例歌词上渲染」。故这里**读一次**，之后不再重读。
     */
    private val previewSnapshot = PreviewSnapshot.capture(settings)

    private val picker = ColorPickerDialog(
        owner = null,
        // ★★ 2026-10-18（P0）：**初始值必须传基色**，不能传 `currentPacked()`。
        //   后者已经乘过 `lightness`，而取色器会再乘一次 ⇒ 每「打开→确定」一次
        //   颜色再暗一档，越调越黑且调不回来。
        initial = currentBasePacked(),
        title = descriptor.title,
        onPreview = { picked -> preview(picked) },
        initialLightnessPercent = currentLightnessPercent(),
        previewTargetKey = groupPrefix ?: descriptor.key,
        previewSnapshot = previewSnapshot,
    )

    /** 订阅句柄，**必须在 dispose 时关闭**（否则每次开窗多一个永不释放的强引用）。 */
    private var subscription: AutoCloseable? = null

    /** 自己发布期间置位，避免订阅回调把值弹回用户正在拖的滑杆。 */
    private var publishingFromSelf = false

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) = release()

            /**
             * ★★★ 2026-10-14（**BUG-196**）：**每次显示都重捕示例词岛的快照**。
             *
             * ## 用户报障（逐字）
             *
             * > 「**预览词岛中没有应用当前设置。**」
             * （点名：频谱多色流光 · 溶边（边界第三档）· 背景不透明度 · 「还有很多」）
             *
             * ## ★ 根因：窗口**复用** + 快照**构造期冻结**
             *
             * `ToolWindows.present(bucket, factory)` 对同一 bucket **不新建**，
             * 只 `existing.toFront()`（见那里的 KDoc）。而 `previewSnapshot` 是
             * **构造期 `val`** ⇒ ★ 用户「改设置 → 开取色窗 → 关 → 再开」
             * 看到的永远是**第一次**打开时的词岛外观 ✗
             *
             * ★ 这与用户 2026-09-19 的原始要求**并不矛盾** —— 他要的是
             * 「**打开此设置项**时，其他设置被固定下来」（= **一次开窗内**冻结）。
             * ⇒ ★ 修法 = 把冻结的**起点**从"构造"挪到"每一次 `windowOpened`" ✓
             *
             * ⚠️ `windowOpened` **只在真正显示时触发一次**，对复用前置（`toFront()`）
             *    **不会**触发 ⇒ 必须同时挂 `windowActivated`（前置会激活）。
             *    ★ 两个都挂、且刷新方法内**相同则短路**（`data class` 判等）
             *      ⇒ ★ 重复调用无副作用，不需要额外去重状态 ✓
             */
            override fun windowOpened(event: WindowEvent) = refreshPreviewSnapshotFromSettings()

            override fun windowActivated(event: WindowEvent) = refreshPreviewSnapshotFromSettings()
        })
        contentPane = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 14, 8, 14)
            background = ToolWindowPalette.bg
            // ── ⚠️ 2026-09-19：**删掉那段开发者文案**（用户说的"无关内容"）──────
            //
            // 原文案是三行 HTML：
            //   「键：`main_lyric.outline.color.r` / 拖动只预览、松手才写入。/
            //     也可直接在 SPW 插件设置里手填十六进制色值 —— 两处写同一个键。」
            //
            // **两处都不该出现在用户面前**：
            // ① 第一行把**内部键名**摊给用户看（`main_lyric.outline.color.r`）——
            //    那是调试信息，不是界面文案；而且对**颜色组**而言它还是**错的**
            //    （组是 r/g/b 三键，却只显示了 `.r`）。
            // ② 第三行的指路是**错的**：这些颜色键**全在 `SELF_DRAWN_KEYS` 里**，
            //    **根本不在 SPW 设置页上** —— 用户照它去找会一无所获。
            //    **一条指向不存在位置的提示比没有提示更糟。**
            //
            // 窗口标题（「个性歌词 · 主歌词描边色」）已经说清这是哪个颜色，
            // 故整块**删除**，不再替换。
            add(picker.buildContent(), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(statusLabel, BorderLayout.WEST)
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
                            // ★ 2026-09-21：**必须跟主题** —— 用户报「底部仍有未随
                            //   深浅主题变化的区域」：这条按钮栏此前没设 background，
                            //   用 Swing 默认浅色底，深色主题下就是一条亮横带。
                            background = ToolWindowPalette.bg
                            // ★ 2026-09-21（BUG-109）：`JButton` → `ThemeButton`。
                            //   用户真机反馈「两个按键（「确定」「关闭」）**不随主题变色**」——
                            //   根因是 `background` 在 Windows/原生 LAF 下被**完全忽略**，
                            //   只能自绘。理由与判据见 [ThemeButton] 的 KDoc。
                            add(ThemeButton("确定").apply {
                                addActionListener {
                                    commit(picker.picked())
                                    release()
                                    dispose()
                                }
                            })
                            add(ThemeButton("关闭").apply {
                                addActionListener {
                                    release()
                                    dispose()
                                }
                            })
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.SOUTH,
            )
        }
        // ★ 2026-09-21：**统一主题出口**（BUG-108）—— 见 [ToolWindowPalette] 的 KDoc。
        //    必须在组件树建好之后调（它递归刷整棵树），故放在 `pack()` 之前。
        //    ⚠️ 2026-09-23：**显式传 `toolWindowFontProvider()`** —— 用户原话
        //    「（自绘窗口）**也还没随所选字体**」。此前是 2 参调用，靠默认值
        //    （当时为 `null`）⇒ 恒回退出厂默认族，**不随用户在设置里选的族名**。
        applyToolWindowTheme(contentPane, rootPane, toolWindowFontProvider())
        // 标题栏要在 **peer 建立之后**再刷一次：`pack()` 之前那次取不到 HWND
        // （`hwndOf` 第一句就是 `if (!window.isDisplayable) return null`），
        // 属**静默失效**。`windowOpened` 才是真正生效的时机。
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                syncNativeTitleBar(contentPane, dark = !ToolWindowPalette.isLight)
            }
        })
        pack()
        minimumSize = Dimension(400, 320)
        setLocationRelativeTo(owner)
        installSubscription()
        // 面板展开后把焦点给 hex 框：键盘用户可以直接粘贴一个色值。
        SwingUtilities.invokeLater { picker.focusHexField() }
    }

    /**
     * 4 键制的**基色**（打包 ARGB，**不再乘 lightness**）—— 取色器的初始值。
     *
     * 与 [currentPacked] 的唯一区别：明度固定按 100% 合成。
     * 明度本身由 `initialLightnessPercent` 单独交给取色器（`ColorPickerDialog` 的
     * `lightness` KDoc 明确说明"不从打包值反推，反推有损"）。
     */
    private fun currentBasePacked(): Int {
        if (groupPrefix == null) return currentPacked()
        val runtime = settings ?: return descriptor.default as? Int ?: 0
        val snap = runCatching { runtime.live }.getOrNull()
            ?: return descriptor.default as? Int ?: 0
        fun ch(name: String, def: Int): Int =
            runCatching {
                snap.int(SettingRegistry.descriptor("$groupPrefix.$name")!!)
            }.getOrDefault(def)
        return composePackedArgb(ch("r", 255), ch("g", 255), ch("b", 255), 100)
    }

    /** 当前色值（打包 ARGB）。设置系统不可用时回退注册表默认值。 */
    private fun currentPacked(): Int {
        val fallback = descriptor.default as? Int ?: 0
        val runtime = settings ?: return fallback
        if (groupPrefix == null) {
            // ⚠️ 2026-09-19：**必须归一化 alpha**（"一并修"的第二处）——
            //    `COLOR` 类键（如 `spectrum.color`）在配置里存的是 `#RRGGBB` **文本**，
            //    读回来可能是**不带 alpha 位**的打包值；直接喂给取色器会重演
            //    "`#00000000` / 预览全透明"那一幕。
            //    `or 0xFF000000` 对已经带 alpha 的值是**幂等**的，故无条件加是安全的。
            return (runCatching { runtime.live.int(descriptor) }.getOrDefault(fallback)) or
                0xFF000000.toInt()
        }
        // 4 键制：按 `mixerColor` 的同一公式合成（读不到就走各自默认）。
        val snap = runCatching { runtime.live }.getOrNull() ?: return fallback
        fun ch(name: String, def: Int) =
            runCatching { snap.int(SettingRegistry.descriptor("$groupPrefix.$name")!!) }.getOrDefault(def)
        // ⚠️ 2026-09-19：**lightness 只在 4 键制里存在** ——
        //    原写法无条件 `descriptor("$groupPrefix.lightness")!!`，
        //    对 3 键制（描边 / 边框）**每次都抛 NPE**，被 `runCatching` 静默吞掉。
        //    它恰好"看起来能工作"（落到 `def = 100`），但**每帧一条异常**，
        //    且掩盖了真正的缺陷（见下）。现改为**先判断键在不在**。
        val lightnessKey = SettingRegistry.descriptor("$groupPrefix.lightness")
        val l = if (lightnessKey != null) {
            ch("lightness", 100).coerceIn(0, 100) / 100f
        } else {
            1f     // 3 键制：通道值**本身就是最终值**，不乘任何系数
        }
        val r = ch("r", 255)
        val g = ch("g", 255)
        val b = ch("b", 255)
        // ── ★★ 2026-09-19：**必须补上 alpha 位**（真机截图暴露的缺陷）──────────
        //
        // 用户截图里：hex 显示 `#00FFFFFF`（**八位**）、预览块**全白看不见**、
        // 示例歌词**一个字都没有**。
        //
        // 根因就是这一行**少了 `0xFF shl 24`**：
        //   · `toHexString()` 见 alpha ≠ 0xFF → 走八位分支 → `#00FFFFFF`；
        //   · `Color(0x00FFFFFF, true)` → **alpha = 0，全透明** → 预览块与示例歌词
        //     **画了但看不见**（这正是"示例歌词也没有"的真相）。
        //
        // **为什么之前没暴露**：只有"打开取色器"这条路会用到本函数，
        // 而它是上一轮才接上取色按钮的。
        // 合成走**公开纯函数**（`ColorPickerProbe` 直接断言它的 alpha 位）——
        // 公式不再在本文件里手写一遍，"忘了补 alpha"这件事从此只可能在一处发生。
        return composePackedArgb(r, g, b, (l * 100f).toInt())
    }

    /**
     * 4 键制的**整体明度倍率**（0..100）；3 键制恒 `100`。
     *
     * 必须单独读出来交给取色器 —— 取色器**不会**从打包值反推它
     * （反推有损：拖到黑之后基色就丢了，见 `ColorPickerDialog.lightness` 的 KDoc）。
     */
    private fun currentLightnessPercent(): Int {
        if (groupPrefix == null) return 100
        val key = SettingRegistry.descriptor("$groupPrefix.lightness") ?: return 100
        val runtime = settings ?: return 100
        return runCatching { runtime.live.int(key) }.getOrDefault(100).coerceIn(0, 100)
    }

    /**
     * 待写入的键值对：单键制 1 项、4 键制 4 项。见 [groupPrefix] 的 KDoc。
     *
     * ## ⚠️ 4 键制必须写**基色** + **真实明度**，不能写"最终色 + 明度 100"
     *
     * `IslandWindow.mixerColor` 的口径是 `r × lightness/100`。若把**已压暗**的最终色
     * 写进 `.r/.g/.b` 又带上 `lightness`，就会**乘两次** —— 观感是"越调越黑"，
     * 而且再也调不回来。故这里两个数都取自 [PickedColor]（由取色器一并交出，
     * 不需要在这里反推 —— 反推是有损的）。
     */
    private fun colorPatch(picked: PickedColor): Map<String, Any> =
        buildMap {
            if (groupPrefix == null) {
                put(descriptor.key, descriptor.coerce(picked.packed))
            } else {
                val hasLightness = SettingRegistry.descriptor("$groupPrefix.lightness") != null
                // ★★★ 2026-10-10 **BUG-164**：★ 通道值由 [colorGroupChannelRgb] 决定
                //   （**3 键制写最终色、4 键制写基色**）—— 原实现**无条件写基色** ✗
                val rgb = colorGroupChannelRgb(hasLightness, picked)
                put("$groupPrefix.r", (rgb shr 16) and 0xFF)
                put("$groupPrefix.g", (rgb shr 8) and 0xFF)
                put("$groupPrefix.b", rgb and 0xFF)
                // 只有 4 键制才写 lightness —— 3 键制没有这个键，写进去会被当成"未知键"。
                if (hasLightness) {
                    put("$groupPrefix.lightness", picked.lightnessPercent)
                }
            }
            // ★ 2026-09-20：**自动解门控**（见 [autoEnableGates] 的说明）。
            putAll(autoEnableGates())
        }

    /**
     * 本次编辑的颜色若属于**被开关关着**的功能，顺手把那个开关打开。
     *
     * ## 为什么（真机取证）
     *
     * 用户报「**我要求的实时更新设置变化，怎么我在调色时词岛里的歌词颜色不变?**」
     * 而他当时的配置是：
     *
     * ```
     * main_lyric.outline.enabled = false      ← 主歌词描边**关着**
     * ```
     *
     * 他的截图标题正是「个性歌词 · **主歌词描边色**」——
     * **描边根本没画，所以他调什么都不会变**。这不是 bug，是**门控**：
     * 一个被关掉的功能，它的颜色键调了当然没有可见效果。
     *
     * 但这体验是坏的：用户点进"主歌词描边色"，意图**唯一** ——
     * **他就是要看到这个描边**。故按本项目既有纪律
     * 「**被门控的控件在被触碰时自动解门控**（判据：用户意图是否唯一）」，
     * 在写入颜色时**一并把开关打开**。
     *
     * ## 为什么放在 [colorPatch] 里（而不是各个调用点）
     *
     * 本函数是"颜色写入"的**唯一出口**（`preview` 与 `commit` 都走它）。
     * 放在这里，两条路径自动一致 —— 与 [PickedColor] 收敛"三个数"是同一个理由：
     * **同一个语义只允许有一个实现点。**
     *
     * ## 为什么只做"启用"、不做 `color_mode` 的改写
     *
     * 另一个会让"调色无反应"的合法情形是 `color_mode = cover`（颜色取自专辑封面，
     * 如用户当前的 `main_lyric.translation.color_mode`）。
     * 但改写 `color_mode` 是**改变颜色的来源**，不是"解门控" ——
     * 用户可能刻意想跟随封面。故**不静默改写**，只在文档里登记为待确认项。
     *
     * ## 覆盖范围
     *
     * 目前只有两个"描边"有 `enabled` 门控（注册表里查得到）；
     * 将来若新增带门控的颜色组，在下面加一行即可。
     *
     * ## ★★★ 2026-10-08（U3）修复：返回值从 `List<String>` 改成 `Map<String, Any>`
     *
     * ### 用户报障（逐字）
     *
     * > 「主歌词描边色设置不成功，在预览词岛可见设置应用成功，但**点击确定后桌面词岛
     * >   无变化**。与最初期望的设置项实时预览相背。**副歌词同样调整失败**。」
     *
     * ### 副描边为什么仍然失败（★ 即使 `enabled` 被自动打开了）
     *
     * `translationOutlineFor()` 的第一行是：
     *     `if (translationOutlineSync) return mainOutlineFor()`
     * 而 `translation.outline.sync_with_main` **默认 `true`** ⇒ ★★ **整组 5 键被短路**。
     *
     * ⇒ 本函数原先把 `translation.outline.enabled` 设成 `true` **是必要但不充分的**：
     *   同步开关仍开着 ⇒ 走的还是主歌词那条路 ⇒ **主歌词没开描边时副描边一样不画**。
     *   用户看到的正是"副歌词同样调整失败"。
     *
     * ### 修法：副描边这一支**同时**把同步开关设成 `false`
     *
     * 语义上完全成立：用户**亲手去调副描边自己的颜色** ⇒ 意图唯一 ——
     * 「我要副描边按**我调的**颜色显示，而不是跟随主歌词」。
     * ★ 这与 `SettingCoupling` 里既有的规则（拖副描边任一键 ⇒ 关 `sync_with_main`）
     *   **是同一条语义**。此处只是把同一条规则补到"取色器"这条入口上 ——
     *   那条入口此前**漏了**（`SettingCoupling` 走的是 `EXTERNAL` 变更，
     *   而取色器走 `APPLY`/`PUBLISH`，两者不重叠）。
     *
     * ⚠️ **为什么不在 `SettingCoupling` 里补**：那里的入参是"被改的键集合"，
     *   而 `auto_ungate` 是**用户可关的**（`settings.auto_ungate_on_touch`）——
     *   放在那边会让"关掉自动解门控"这个开关失效。两条规则的门控条件不同，
     *   故各自实现（同一语义、**不同触发条件**，不是"两个真相"）。
     *
     * @return **要写入的键值对**（值可为 `true` 或 `false`）。
     *   原返回 `List<String>` 假设"解门控 = 一律设 true" —— 副描边的同步开关
     *   需要的恰好是 `false`，故类型必须放宽（否则要么另起一个函数、
     *   要么在调用点特判，两条都是"同语义两个实现点"）。
     */
    private fun autoEnableGates(): Map<String, Any> {
        // ★ 2026-09-20：**本行为本身是一个设置项**（用户点名要求）——
        //   「被门控的控件在被触碰时应自动解门控」要能关。
        //   默认 `true`（保持本轮之前的行为）；关掉后只写颜色、不碰任何 `enabled`。
        //
        //   读不到设置（降级 / 键缺失）时**按开启处理** —— 与默认值一致，
        //   宁可多帮一次，也不要因为读不到就静默少做一件事（那更难排查）。
        val runtime = settings ?: return emptyMap()
        // ⚠️ `boolean(...)` 定义在 **`SettingsSnapshot`** 上（不是 runtime）——
        //    故走 `runtime.live`（当前生效的那份快照）。
        val enabled = runCatching { runtime.live.boolean(AUTO_UNGATE_KEY) }.getOrNull() ?: true
        if (!enabled) return emptyMap()

        // ★★ 判据本体在文件顶层的 [autoUngateFor]（**公开纯函数**）——
        //   抽出去是为了让探针能直接断言它（本类是 `internal`、本方法是 `private`）。
        //   本处只做"开关检查 → 转调"这两跳，**不自己写一遍前缀 if-chain**：
        //   写两遍就是 U3 复发的地方（判据一份、消费它的一份）。
        return autoUngateFor(descriptor.key)
    }

    private companion object {
        /** 见 [autoEnableGates]。 */
        const val AUTO_UNGATE_KEY = "settings.auto_ungate_on_touch"
    }

    /**
     * 拖动中的实时预览（只 publish、不落盘）。
     *
     * ⚠️ **本函数会在 [picker] 的构造期间被调用一次**（`ColorPickerDialog.init` →
     * `syncFromModel` → `onPreview`）。故它**绝对不能读 `picker` 字段**、
     * **也不能碰任何"晚初始化"的 UI**（`statusLabel` 就是当年那个 NPE 的现场）。
     * 需要的一切都从 [PickedColor] 参数拿。
     */
    private fun preview(picked: PickedColor) {
        // ★★★ 2026-10-10（U3 复发排查）：★ 原来这里是**静默 return** ——
        //   设置系统不可用 ⇒ 拖动毫无反应且**日志一个字都没有** ✗
        //   （★ 本项目最多次的缺陷形态："改了没反应且不可观测"）
        val runtime = settings ?: run {
            System.err.println(
                "[CDL] ⚠️ 取色器预览被跳过：设置系统不可用（${descriptor.key}）" +
                    "⇒ 拖动不会有任何反应",
            )
            return
        }
        publishingFromSelf = true
        try {
            val patch = colorPatch(picked)
            val change = runtime.publish(patch)
            // ★ 诊断（U3 复发排查）：三段串起来 —— 补丁 / runtime 是否接受 / 词岛能否收到
            System.err.println(
                "[CDL] 取色器预览：${descriptor.key} picked=#${picked.packed.toHexString()} " +
                    "patch=$patch ⇒ kind=${change.kind} changed=${change.changedKeys}",
            )
        } catch (error: Throwable) {
            System.err.println("[CDL] 颜色预览失败（${descriptor.key}）：$error")
        } finally {
            publishingFromSelf = false
        }
        // 注意：**这里不调 `refreshStatus()`** —— 它碰 `statusLabel`，
        // 而本函数在构造期间会跑一次。状态刷新改在订阅回调与 [commit] 里做。
    }

    private fun commit(picked: PickedColor) {
        // ★ 同 [preview]：静默 return 会让"按了确定没反应"**完全不可观测** ✗
        val runtime = settings ?: run {
            System.err.println(
                "[CDL] ⚠️ 取色器写入被跳过：设置系统不可用（${descriptor.key}）" +
                    "⇒ 按「确定」不会有任何效果",
            )
            return
        }
        publishingFromSelf = true
        try {
            val patch = colorPatch(picked)
            val change = runtime.apply(patch)
            statusLabel.text = "已写入 ${picked.packed.toHexString()}"
            System.err.println(
                "[CDL] 取色器写入：${descriptor.key} picked=#${picked.packed.toHexString()} " +
                    "patch=$patch ⇒ kind=${change.kind} changed=${change.changedKeys}",
            )
        } catch (error: Throwable) {
            System.err.println("[CDL] 颜色写入失败（${descriptor.key}）：$error")
        } finally {
            publishingFromSelf = false
        }
    }

    /**
     * 外部改了同一个键时把面板拉过来。
     *
     * ⚠️ 只在**本项所在分组**被触及才同步 —— 订阅回调是进程级的，
     * 任意键的改动都会进来；无脑同步会让用户拖到一半的值被别的键的改动弹回去。
     */
    private fun installSubscription() {
        val runtime = settings ?: return
        subscription = runtime.subscribe { snapshot, change ->
            SwingUtilities.invokeLater {
                if (publishingFromSelf || !isDisplayable) return@invokeLater
                if (descriptor.group !in change.groups) return@invokeLater
                runCatching {
                    if (groupPrefix == null) {
                        picker.setValueFromModel(snapshot.int(descriptor))
                    } else {
                        // ⚠️ **组不能读 `descriptor`** —— 它是 `$prefix.r`，那是一个**通道值**
                        //    （0..255），当颜色用会得到"几乎全黑"。必须把三通道 + lightness
                        //    一起读出来交给取色器（**不在这里反推**，见 [PickedColor] 的 KDoc）。
                        fun ch(n: String): Int =
                            SettingRegistry.descriptor("$groupPrefix.$n")?.let { snapshot.int(it) } ?: 255
                        val base = composePackedArgb(ch("r"), ch("g"), ch("b"))
                        val l = SettingRegistry.descriptor("$groupPrefix.lightness")
                            ?.let { snapshot.int(it) } ?: 100
                        picker.setValueFromModel(base, l)
                    }
                }
                // 状态刷新放在这里（而不是 `preview()` 里）—— 见 `preview()` 的注释：
                // `preview()` 会在构造期间被调用一次，那时 `statusLabel` 还没初始化。
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        val runtime = settings ?: return
        if (runtime.isDirty) statusLabel.text = "有未保存的修改"
    }


    /**
     * 工具窗的主体背景色 —— **跟随系统深浅色主题**。
     *
     * 取值与 `ColorPickerDialog.applyModernLook()` 的 `bg` **逐字一致**（深/浅各一组）。
     * 两处若不一致，嵌入的取色器会在工具窗里出现一圈**颜色不同的方块**。
     *
     * ## 为什么需要它（真机取证）
     *
     * 用户原话：「自绘取色窗口**大块面积不跟随深浅色主题**，
     *   只有输入窗口等**几个部分是黑的**。」
     *
     * 根因：嵌入的取色器自己有 `applyModernLook()` 把**它自己**那几个控件染成深色；
     * 而**工具窗本身**的 `contentPane` 没有 `background`（本文件原先一处 SystemTheme 都没有）
     * → 用 Swing 默认的浅色底 → 「大块面积是亮的、几个嵌入控件是黑的」。
     */
    private fun release() {
        subscription?.let { runCatching { it.close() } }
        subscription = null
    }

    /**
     * ★★★ 2026-10-14（**BUG-196**）：**重捕示例词岛的外观快照**并推给取色器里的预览条。
     *
     * 见 `addWindowListener` 里 `windowOpened` / `windowActivated` 的 KDoc
     * （用户报障：「预览词岛中没有应用当前设置」；根因 = 窗口复用 + 构造期冻结）。
     *
     * ## 三条纪律
     *
     * · **必须在 EDT 上**：`PreviewSnapshot.capture` 读 `SettingsRuntime.live`
     *   （一个快照对象），本方法只会在窗口事件里被调 —— 那些**本来就在 EDT**；
     *   ★ 但为防将来有人从非 EDT 调，这里**显式判一次**并转投（便宜的保险）✓
     * · **失败不得影响开窗**：整段 `runCatching` —— 快照读失败时预览保持旧值，
     *   ★ 总好过"开窗抛异常 ⇒ 用户点取色器**没反应**"（本项目真机踩过该形态）✓
     * · **无条件调**（不判"设置是否变过"）：判等在 `IslandPreviewPane.updateSnapshot`
     *   里做（`data class` 逐字段比）⇒ ★ 判据只有一处，不会两处漂 ✓
     */
    private fun refreshPreviewSnapshotFromSettings() {
        val captured = runCatching { PreviewSnapshot.capture(settings) }.getOrNull() ?: return
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            runCatching { picker.refreshPreviewSnapshot(captured) }
        } else {
            javax.swing.SwingUtilities.invokeLater {
                runCatching { picker.refreshPreviewSnapshot(captured) }
            }
        }
    }
}

/**
 * 字体选择工具窗 —— 对应 `main_lyric.font.family`，并消费 `main_lyric.font.recent`。
 *
 * 「最近使用字体」（`main_lyric.font.recent`）是**开放集**（用户装过的任意字体），
 * 宿主的 `list` 只支持静态选项 ⇒ 它不出现在 SPW 设置页
 * （见 [SettingRegistry.SELF_DRAWN_KEYS]），由 [FontPicker] 内部消费。
 */
internal class FontToolWindow(
    owner: Window?,
    private val settings: SettingsRuntime?,
) : JDialog(owner, "个性歌词 · 字体选择", ModalityType.MODELESS) {

    private val statusLabel = JLabel(" ").apply {
        foreground = MUTED
        border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
    }

    private val familyDescriptor: SettingDescriptor? = SettingRegistry.descriptor(FONT_FAMILY_KEY)

    private val picker: FontPicker? = familyDescriptor?.let { descriptor ->
        FontPicker(
            originalFamily = currentFamily(descriptor),
            previewSize = currentPreviewSize(),
            onCommit = { family -> commit(descriptor, family) },
        ).also { it.setExpanded(true) }
    }

    private var subscription: AutoCloseable? = null
    private var publishingFromSelf = false

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) = release()
        })

        contentPane = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 14, 8, 14)
            background = ToolWindowPalette.bg
            add(
                JLabel(
                    "<html>键：<code>$FONT_FAMILY_KEY</code><br>" +
                        "选择后<b>双击 / 回车 / 点「确定」</b>才写入（滚动选择不算）。<br>" +
                        "也可直接在 SPW 插件设置里手填字体族名 —— 两处写同一个键。</html>",
                ),
                BorderLayout.NORTH,
            )
            if (picker != null) {
                add(picker.root, BorderLayout.CENTER)
            } else {
                // 禁用而非隐藏 + 说明原因：比"窗口一片空白"诚实。
                add(JLabel("字体设置项不可用（注册表未就绪）"), BorderLayout.CENTER)
            }
            add(
                JPanel(BorderLayout()).apply {
                    add(statusLabel, BorderLayout.WEST)
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
                            // ★ 2026-09-21：**必须跟主题** —— 用户报「底部仍有未随
                            //   深浅主题变化的区域」：这条按钮栏此前没设 background，
                            //   用 Swing 默认浅色底，深色主题下就是一条亮横带。
                            background = ToolWindowPalette.bg
                            // ★ 2026-09-21（BUG-109）：同族穷尽 —— 字体窗的这个"关闭"
                            //   与取色窗的两个按钮是**同一类**（`JButton.background`
                            //   在原生 LAF 下被忽略）。只修取色窗就会漏掉这一处。
                            add(ThemeButton("关闭").apply {
                                addActionListener {
                                    release()
                                    dispose()
                                }
                            })
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.SOUTH,
            )
        }
        // ★ 2026-09-21：**统一主题出口**（BUG-108）—— 见 [ToolWindowPalette] 的 KDoc。
        //    必须在组件树建好之后调（它递归刷整棵树），故放在 `pack()` 之前。
        //    ⚠️ 2026-09-23：**显式传 `toolWindowFontProvider()`** —— 用户原话
        //    「（自绘窗口）**也还没随所选字体**」。此前是 2 参调用，靠默认值
        //    （当时为 `null`）⇒ 恒回退出厂默认族，**不随用户在设置里选的族名**。
        applyToolWindowTheme(contentPane, rootPane, toolWindowFontProvider())
        // 标题栏要在 **peer 建立之后**再刷一次：`pack()` 之前那次取不到 HWND
        // （`hwndOf` 第一句就是 `if (!window.isDisplayable) return null`），
        // 属**静默失效**。`windowOpened` 才是真正生效的时机。
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                syncNativeTitleBar(contentPane, dark = !ToolWindowPalette.isLight)
            }
        })
        pack()
        minimumSize = Dimension(420, 380)
        setLocationRelativeTo(owner)
        installSubscription()
    }

    private fun currentFamily(descriptor: SettingDescriptor): String {
        val fallback = descriptor.default as? String ?: DEFAULT_FONT_FAMILY
        val runtime = settings ?: return fallback
        return runCatching { runtime.live.text(descriptor) }.getOrDefault(fallback)
    }

    /** 预览字号取自 `main_lyric.font.size`，让预览与真实观感一致。 */
    private fun currentPreviewSize(): Int {
        val descriptor = SettingRegistry.descriptor(FONT_SIZE_KEY) ?: return DEFAULT_FONT_PREVIEW_SIZE
        val fallback = descriptor.default as? Int ?: DEFAULT_FONT_PREVIEW_SIZE
        val runtime = settings ?: return fallback
        return runCatching { runtime.live.int(descriptor) }.getOrDefault(fallback)
    }

    private fun commit(descriptor: SettingDescriptor, family: String) {
        val runtime = settings ?: return
        // ⚠️ 空族名**不写**（2026-09-16 修）。两道防线里的第一道：
        // `SettingDescriptor.coerce` 对 FONT 已改成"空串回退默认值"（第二道），
        // 但那会**静默**把空串变成默认字体 —— 用户看到"我清空了但字体没变"，
        // 同样费解。故这里直接拦下并**告诉用户为什么**。
        // 真机配置文件曾实测到 `"family": ""`，来源就是缺少这道检查。
        if (family.isBlank()) {
            statusLabel.text = "字体族名不能为空，未写入"
            return
        }
        publishingFromSelf = true
        try {
            runtime.apply(mapOf(descriptor.key to descriptor.coerce(family)))
            statusLabel.text = "已写入「$family」"
        } catch (error: Throwable) {
            System.err.println("[CDL] 字体写入失败：$error")
        } finally {
            publishingFromSelf = false
        }
    }

    private fun installSubscription() {
        val runtime = settings ?: return
        subscription = runtime.subscribe { snapshot, _ ->
            SwingUtilities.invokeLater {
                if (publishingFromSelf || !isDisplayable) return@invokeLater
                familyDescriptor?.let { picker?.setValueFromModel(snapshot.text(it)) }
            }
        }
    }

    private fun release() {
        subscription?.let { runCatching { it.close() } }
        subscription = null
    }

    companion object {
        const val FONT_FAMILY_KEY = "main_lyric.font.family"
        const val FONT_SIZE_KEY = "main_lyric.font.size"

        /**
         * 与注册表默认值一致（原实现 `preference_config.json` 同值）。
         *
         * ⚠️ 2026-09-22：**不再写第三份字面量**，改为引用唯一来源
         * `dev.cdl.font.DEFAULT_FONT_FAMILY`（见 `font/FontDefaults.kt`）。
         * 它只是"读不到运行时时"的兜底 —— 正常路径走上面 `currentFamily()` 的
         * `descriptor.default`，故两者取值必须一致。
         * 全限定名引用是刻意的：本类**没有** import 那个常量，避免与这个
         * 同名 companion 成员产生遮蔽歧义。
         */
        const val DEFAULT_FONT_FAMILY = dev.cdl.font.DEFAULT_FONT_FAMILY
        const val DEFAULT_FONT_PREVIEW_SIZE = 22
    }
}

/**
 * 工具窗的**幂等**打开入口。
 *
 * ## 为什么必须幂等
 *
 * 宿主 `button` 的 `on_click` 可以被连点。不幂等就会开出一串窗口，
 * 每个都持有一份 [SettingsRuntime] 订阅、都在写同一个键 ——
 * 用户在其中任一窗里改的值会被另一窗的"外部变更"逻辑回灌，控件互相打架。
 *
 * ## 为什么整个判断都在 EDT 内
 *
 * 静态表里的引用可能已被 dispose。若在调用线程判断、在 EDT 创建，
 * 两次并发调用会**都**通过判断。整个函数体投到 EDT，判断与创建落在同一个
 * EDT 事件里，单线程模型天然消除竞态。
 *
 * ## 为什么按**键**分桶而不是共用一个引用
 *
 * 三个颜色键 + 一个字体键 = 4 个独立工具窗。用户可能同时开着「已唱色」与
 * 「未唱色」对比着调 —— 共用引用会让第二个请求变成"前置第一个窗"，
 * 那就无法并排比较。
 */
internal object ToolWindows {

    /** 已打开的工具窗，按桶名索引。**只在 EDT 上读写。** */
    private val open = HashMap<String, JDialog>()

    private const val FONT_BUCKET = "font"

    /** 打开（或前置）某个颜色工具窗。[key] 必须是 `COLOR` 项。 */
    fun openColor(key: String) {
        val descriptor = SettingRegistry.descriptor(key)
        if (descriptor == null) {
            warn("颜色工具窗：键不存在：$key")
            return
        }
        if (descriptor.kind != SettingKind.COLOR) {
            // 不静默降级：`on_click` 指向一个非颜色键是配置错误，必须留下痕迹。
            warn("颜色工具窗要求 COLOR 项，实为 ${descriptor.kind}：$key")
            return
        }
        present(key) { owner -> ColorToolWindow(owner, descriptor, SettingsRuntimeHolder.current) }
    }

    /**
     * 打开**四键制颜色组**的取色器（`.r/.g/.b/.lightness`）。
     *
     * ## 为什么不能走 [openColor]
     *
     * `openColor` 要求 `descriptor.kind == COLOR` —— 而三个歌词色**不是** COLOR：
     * v0.20 方案 A 把它们拆成四根 SEEKBAR（`.r/.g/.b/.lightness`），
     * 由 `IslandWindow.mixerColor` 按 `r * lightness/100` 合成。
     * 故 `openColor("main_lyric.fill.color")` 会被它自己的 kind 检查拒掉。
     *
     * 本方法以**组**为单位：取 `prefix` 下的 4 个键，交给 [ColorToolWindow]
     * 的 `groupPrefix` 路径读写。**两段式写路径与单键版完全一致**。
     *
     * @param prefix 组前缀，如 `main_lyric.fill.color`
     * @param title 窗口标题用的中文名（`on_click` 不能传参，故由调用方给字面量）
     */
    fun openColorGroup(prefix: String, title: String) {
        // 组内 4 个键必须齐全 —— 缺任何一个都说明注册表被改坏了，**不静默降级**。
        // **两种键制都要支持**：
        //   · 4 键制（歌词三色，`mixerColor`）：r/g/b/**lightness**
        //   · 3 键制（描边 / 边框）：只有 r/g/b —— 消费点是 `int("xxx.r")` 直接读三通道
        // 故 lightness **可选**：有就写、没有就不写。
        val hasLightness = SettingRegistry.descriptor("$prefix.lightness") != null
        val required = if (hasLightness) listOf("r", "g", "b", "lightness") else listOf("r", "g", "b")
        val missing = required.filter {
            SettingRegistry.descriptor("$prefix.$it") == null
        }
        if (missing.isNotEmpty()) {
            warn("颜色组 $prefix 缺键：$missing")
            return
        }
        present(prefix) { owner ->
            ColorToolWindow(
                owner = owner,
                descriptor = SettingRegistry.descriptor("$prefix.r")!!,
                settings = SettingsRuntimeHolder.current,
                groupPrefix = prefix,
            ).also { it.title = "个性歌词 · $title" }
        }
    }

    /** 打开（或前置）字体工具窗。 */
    fun openFont() {
        present(FONT_BUCKET) { owner -> FontToolWindow(owner, SettingsRuntimeHolder.current) }
    }

    /**
     * 共同路径：EDT 上判断"是否已开着" → 前置或新建。
     *
     * `owner` **惰性取**词岛窗口 —— 词岛窗口可能在两次调用之间被销毁，
     * 取早了会把 `JDialog` 的 owner 钉在一个死窗口上（窗口定位会跑到屏幕角落）。
     */
    private fun present(bucket: String, factory: (Window?) -> JDialog) {
        SwingUtilities.invokeLater {
            runCatching {
                val existing = open[bucket]
                if (existing != null && existing.isDisplayable) {
                    existing.toFront()
                    existing.requestFocus()
                    return@runCatching
                }
                val created = factory(CdlRuntime.lastIslandWindow())
                open[bucket] = created
                // 关窗后必须清引用：留着等于让一个死窗口无法被 GC
                // （静态表指着的对象永远不会被回收）。
                created.addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(event: WindowEvent) {
                        if (open[bucket] === created) open.remove(bucket)
                    }
                })
                created.isVisible = true
                // ★ 2026-09-21：**原生标题栏跟随系统深浅色**（走 DWM）。
                //
                // 工具窗（取色窗 / 字体窗）都是带**原生标题栏**的 `JDialog`，
                // 而标题栏由 OS 绘制、**不读 Swing 的 `background`** ——
                // 光把 `contentPane` 涂成深色，标题栏仍是浅色，整窗看起来"拼起来的"。
                // 用户截图取证：「窗口**仍有未跟随主题颜色部分**」，指的就是它。
                //
                // ⚠️ **必须放在 `isVisible = true` 之后** —— 窗口未显示时拿不到 HWND
                //    （`WindowInterop.hwndOf` 依赖 `Native.getWindowPointer`）。
                //    降级安全：拿不到句柄 / 系统不支持 → 静默返回 false，标题栏保持默认色。
                runCatching {
                    dev.cdl.window.WindowInterop.setDarkTitleBar(
                        created,
                        !dev.cdl.platform.SystemTheme.isLight(),
                    )
                }
            }.onFailure {
                // ⚠️ **必须打完整栈**（2026-09-19 的教训）：原先只打 `$it`（一行 message），
                //    于是"关掉取色窗就再也打不开"这个 P0 在日志里只有一句话，
                //    查了半天才定位到是构造期的 NPE。
                //    本项目纪律：**告警要么指名道姓、要么别打** —— 打一半等于没打。
                warn("打开工具窗失败（$bucket）：${it.stackTraceToString().take(1500)}")
            }
        }
    }

    /** 工具窗全靠用户主动点击才会打开，失败时不能静默（宿主 stdout 是黑洞，至少留 stderr）。 */
    private fun warn(message: String) {
        System.err.println("[CDL] $message")
    }
}

/**
 * 静态持有"当前的 [SettingsRuntime]"，供工具窗取用。
 *
 * ## 为什么不直接用 `CdlPlugin.runtime`
 *
 * **依赖方向**：`settingsui` 是表现层、`host` 是组合根。反过来 import 会形成
 * `host → settingsui → host` 的环 —— 能编译，但让"能否单独编译 settingsui"变成不能，
 * 分层也就失去意义。故由 `host` 侧在 `start()` / `close()` 时写这里。
 *
 * ## 代价（诚实记录）
 *
 * 这是**进程级可变状态**，与 `CdlPlugin.runtime` 存在"两份状态描述同一件事"
 * 的风险 —— 若两处更新时机不一致，就会出现"插件已启动但工具窗说没有设置系统"。
 * 防线是：**两处赋值写在同一个函数体里**（见 [CdlRuntime.start] / `close`），
 * 任何一处的修改都能在同一屏里看到另一处。
 *
 * 另一条备选方案是"给工具窗加一个 `SettingsRuntime?` 参数，由 `on_click` 目标传入" ——
 * 但 `on_click` 的反射约定是**无参静态方法**，改签名会让调用失败。故不可行。
 *
 * ⚠️ 插件正在卸载时读到 `null`，工具窗以只读形态打开（控件禁用 + 说明原因），
 * 不做"点了没反应"的半成品。
 */
internal object SettingsRuntimeHolder {
    @Volatile
    var current: SettingsRuntime? = null
}

/** 供菜单判定"是否该禁用编辑类入口"—— 设置系统不可用时返回 `false`。 */
internal fun settingsAvailable(): Boolean = SettingsRuntimeHolder.current != null

/**
 * ★★★ **颜色组要写进 `.r/.g/.b` 的那个 RGB** —— **唯一判据**（★ 公开纯函数，供探针断言）。
 *
 * ## ★★★ 2026-10-10 **BUG-164**：★ 这是"**改描边色不生效**"的**真根因**
 *
 * ### 症状（真机日志，本诊断落地后第一轮就抓到）
 *
 * ```
 * 取色器预览：main_lyric.outline.color.r picked=#ff000000
 *   patch={r=255, g=255, b=255, main_lyric.outline.enabled=true} ⇒ kind=PUBLISH changed=[]
 * ```
 *
 * ★ 用户选的是**黑**（`picked=#ff000000`），★ 而补丁里写的是**白**（`255,255,255`）✗
 * ⇒ ★★ `changed=[]` ⇒ **被 `coerceDelta` 当成"值没变"丢弃**
 * ⇒ ★★★ **不落盘、不通知、真机不变** ✗
 *
 * ### 根因：**两种键制的"明度"语义不同，而原实现只按一种写**
 *
 * | 键制 | 键 | 消费侧怎么算 | 该写什么 |
 * |---|---|---|---|
 * | **4 键制**（歌词三色） | `r/g/b` + **`lightness`** | ★ `mixerColor`：`r × lightness/100` | ★ **基色** + 真实明度 ✓ |
 * | **3 键制**（描边 / 边框） | ★ **只有 `r/g/b`** | ★ `KaraokeTextRenderer` **直读三通道，不乘任何系数** | ★★ **最终色** ✓ |
 *
 * ★ 原实现**无条件写 `baseRgb`（基色）** ⇒
 * ★★ 在 3 键制下，"整体明度"滑杆的调整**整段被丢掉**（因为**没有地方存它**）✗
 * ⇒ ★ 用户把明度拖到 0（全黑），写回去的仍是**基色白** ⇒ ★ **等于什么都没做** ✓
 *
 * ### 为什么"预览看得见、真机看不见"
 *
 * ★ 取色器里的**示例预览**用的是**本地 `picked`**（`applyEditedColor(c)`）⇒ 跟着动 ✓
 * ★ 而**真机**读的是**设置里的三通道** ⇒ 收到的是基色 ⇒ **不动** ✗
 * ★★ 这正是用户两次报障（2026-10-08 U3 / 2026-10-10）逐字描述的现象 ✓
 *
 * @param hasLightnessKey `$groupPrefix.lightness` 这个键**在注册表里存不存在**。
 * @param picked 取色器交出的三件套（`packed` = 最终色、`baseRgb` = 基色、`lightnessPercent`）。
 * @return ★ 要写进 `.r/.g/.b` 的 RGB（★ **含 alpha 高位** —— 调用方按通道取）。
 *
 * ⚠️ ★ **必须是 `public`（不是 `internal`）** —— ★ 探针是**独立编译模块**，
 *    **看不到 `internal`**（实测：写成 `internal` 时 `compileProbeKotlin` 直接报
 *    「Cannot access … it is internal in file」）✗
 *    ★ 与 `composePackedArgb` 同口径（它也是 `public`，探针在用）✓
 */
public fun colorGroupChannelRgb(hasLightnessKey: Boolean, picked: PickedColor): Int =
    // ★ 4 键制：写**基色**（明度另有键存）✓
    // ★ 3 键制：写**最终色**（明度无处可存 ⇒ 必须烘进通道）✓
    if (hasLightnessKey) picked.baseRgb else picked.packed

/**
 * **取色器触碰某个颜色键 ⇒ 要自动解的门控**（★ U3 的判据，**公开纯函数**）。
 *
 * ## 为什么必须抽出来（而不是留在 [ColorToolWindow.autoEnableGates] 里）
 *
 * ★ 与 `effectiveTranslationGapPx`（U2）/ `pillOutlineFor`（U10）/
 * `hoverHighlightKept`（U9）**同一条做法**：`ColorToolWindow` 是 `internal class`
 * 且该方法是 `private` ⇒ 探针（独立编译单元）断言不到。
 * 而这条映射**就是 U3 修复的全部内容** —— 留在私有方法里等于**修复没有护栏**。
 *
 * ## 用户报障（逐字）
 *
 * > 「主歌词描边色设置不成功…**副歌词同样调整失败**。」
 *
 * ## 两个分支各自的语义
 *
 * | 触碰的键前缀 | 要写的键值对 | 为什么 |
 * |---|---|---|
 * | `main_lyric.outline*` | `main_lyric.outline.enabled = true` | 开关默认 `false` ⇒ 不画描边 ⇒ 调色无反应 |
 * | `translation.outline*` | `translation.outline.enabled = true` **且** `sync_with_main = false` | ★ 见下 |
 *
 * ### ★★ 副描边为什么必须**同时**关掉同步
 *
 * `Appearance.translationOutlineFor()` 第一行是
 * `if (translationOutlineSync) return mainOutlineFor()` ——
 * 而 `sync_with_main` **默认 `true`** ⇒ **整组 5 键被短路**。
 * 只开 `translation.outline.enabled` 是**必要但不充分**的：
 * 同步开关仍开着 ⇒ 走的还是主歌词那条路 ⇒ 主歌词没开描边时副描边**一样不画**。
 *
 * 语义上完全成立：用户**亲手去调副描边自己的颜色** ⇒ 意图唯一 ——
 * 「我要副描边按**我调的**颜色显示，而不是跟随主歌词」。
 * 这与 `SettingCoupling` 里既有规则（拖副描边任一键 ⇒ 关 `sync_with_main`）
 * **是同一条语义的另一个入口** —— 那条入口走 `EXTERNAL` 变更，
 * 取色器走 `APPLY`/`PUBLISH`，两者不重叠，故此处的补充不是重复。
 *
 * @param targetKey 正在被编辑的**颜色键**（如 `translation.outline.color.r`）。
 *   用 `startsWith` 判前缀 —— 见下面 ⚠️。
 * @return 要写入的键值对。**空 map = 这个键不触发任何解门控**
 *   （绝大多数颜色键属此类，如 `main_lyric.fill.color` 没有门控）。
 *
 * ⚠️ **前缀判据必须用 `startsWith("main_lyric.outline")`（不带尾点）** ——
 *   这是**沿用原实现**（`autoEnableGates` 原就如此），不是笔误：
 *   `main_lyric.outline` 既是组前缀本身、也是 `main_lyric.outline.color.r` 的前缀。
 *   加尾点会**漏掉**"用户直接编辑组前缀键"的情形（组编辑走 `$groupPrefix.r`，
 *   不会命中裸前缀；但保持与原实现一致可避免"改前缀判据"这类静默行为变化）。
 */
internal fun autoUngateFor(targetKey: String): Map<String, Any> = when {
    targetKey.startsWith("main_lyric.outline") -> mapOf("main_lyric.outline.enabled" to true)
    // ★ U3：副描边**两件事一起做** —— 开自己的开关 + 关掉同步（否则整组仍被短路）。
    targetKey.startsWith("translation.outline") -> mapOf(
        "translation.outline.enabled" to true,
        "translation.outline.sync_with_main" to false,
    )
    else -> emptyMap()
}

// ⚠️ 2026-09-21：**改为跟随主题**（原为固定 `0x555555` —— 深色底上几乎糊成一片，
//    它本身就是"未跟随主题"的一处）。取值见 `ToolWindowPalette.muted`。
private val MUTED: Color get() = ToolWindowPalette.muted

// ⚠️ 2026-09-21：`toolWindowBackground()` 已**删除** —— 它的 KDoc 自己就写着
//    "取值与 `ColorPickerDialog.applyModernLook()` 的 `bg` 逐字一致"，
//    即**同一套配色写了两遍**。现在唯一来源是 `ToolWindowPalette.bg`，
//    主题统一出口是 `applyToolWindowTheme(...)`。见 BUG-108。
