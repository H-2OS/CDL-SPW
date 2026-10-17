// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin

/**
 * PF4J 插件入口（由 `META-INF/MANIFEST.MF` 的 `Plugin-Class` 指定）。
 *
 * P0 阶段职责：把宿主回调接进来并留下可观测的痕迹，验证"插件能被加载、扩展点能收到回调、
 * 设置页按钮能反射调用静态方法"这条链路。真正的渲染与设置窗口在后续阶段补齐。
 *
 * 注意（架构约定，与灵动词岛一致）：
 * PF4J 会**独立构造扩展实例**，扩展拿不到本插件实例的引用，因此扩展只能访问**静态成员**。
 * 这是 [playback] 这个静态桥存在的原因，后续阶段不要把它改成实例字段。
 */
class CdlPlugin(context: PluginContext) : SpwPlugin(context) {

    override fun start() {
        if (runtime != null) return
        // ⚠️ 必须用 `pluginContext`（基类的只读属性，非空）。
        // 构造参数 `context` 只在 init 块可见，在 `start()` 里访问它会报
        // "Function invocation 'context(...)' expected" —— 因为此处解析到的是
        // 基类的某个同名可调用成员，而不是我们的参数。
        val created = CdlRuntime(pluginContext)
        runtime = created
        // ★★ 2026-09-29（`recon/65` 路线 ② 收口）：**后台预热宿主字体**。
        //
        // ## 为什么必须在这里预热（而不是等用户打开设置页）
        //
        // `HostFonts` 现在枚举宿主 jar 内**全部**字体族（35 个条目），
        // 每个 `Font.createFont` 实测 **≈40 ms** ⇒ **全量 ≈1.4 s**。
        // ⇒ 它**绝不能在 EDT 上同步做**（`resolveOrNull` 已刻意不同步扫描，未预热就返回 null）。
        //
        // ⇒ 但若等到用户**打开字体选择器**才预热，列表里**只有 `@host:MiSans`**
        //   （其余族要等预热完）—— 用户会以为"宿主只有一款字体"。
        // ⇒ ★ **在插件启动时**就把它预热掉（**守护线程，不阻塞启动**）：
        //   用户走到设置页时（通常远晚于 1.4 s）候选**已经完整**。
        //
        // ⚠️ **本行是我漏接的**：`warmUpAsync()` 早先写好却**从未被调用**
        //   （纪律 7「声明了 ≠ 实现了」的形态）—— 收口时被 grep 抓出。
        dev.cdl.font.HostFonts.warmUpAsync()
        // ⚠️ 必须在运行时启动**之前**提升定时器精度 ✗ —— 帧驱动
        //    （`javax.swing.Timer`，见 `IslandWindow`）在 `created.start()` 里就建好了，
        //    量子没降下来的话它一开始就会按 15.625ms 的整数倍排期 ✗（`recon/54` §7）。
        //    失败/非 Windows 时静默降级，行为与修复前完全一致 ✓。
        dev.cdl.platform.TimerResolution.raise()
        try {
            created.start()
        } catch (error: Throwable) {
            // ★ 失败路径也必须**成对回退定时器精度**，且必须走**同一个出口**。
            //   原写法是 `runtime = null; created.close()` —— 它漏了 `restore()`，
            //   而 `stopRuntime()` 当时首句是 `val old = runtime ?: return`
            //   ⇒ runtime 已被置 null ⇒ **`restore()` 永不执行**
            //   ⇒ 宿主进程停在 1ms 高精度态（增加耗电，且**无人知道**）。
            //   根因同类：「释放动作被一个无关状态门控」。
            stopRuntime()
            throw error
        }
    }

    override fun stop() = stopRuntime()

    /**
     * 宿主在"通过 SPW 更新插件"时调用；**替换文件式更新不会调用**（此时是本类新实例的 `start()`）。
     *
     * ## 为什么不再用 `update() = stop()`（缺陷修复，2026-09-15）
     *
     * 原实现与灵动词岛的 `IslandPlugin` 一致：`update()` 只 `stop()`、不重启。
     * 该写法**隐含一个从未验证的假设** —— "宿主调完 `update()` 后会自己再调 `start()`"。
     *
     * **风险**：若某版本宿主调 `update()` 后**不**自动重启，插件就会静默失效
     * （进程里 PF4J 状态是"已加载"，但我们的运行时已被销毁 → 词岛不再响应任何回调，
     * 且**不报任何错**）。这种失效模式极难归因。
     *
     * **改为：`update()` 走"重启"语义** —— 先 `stop()` 再 `start()`。
     * - 若宿主**确实**会随后再调 `start()`：本类 `start()` 有 `if (runtime != null) return` 守卫，
     *   第二次调用是**幂等的空操作**，无害；
     * - 若宿主**不会**重启：插件**自行恢复可用**，不再静默失效。
     *
     * 即"无论宿主行为如何，两种情形都得到正确结果"，**不依赖未经验证的宿主行为**。
     *
     * ⚠️ **仍待真机验证**（已列入大纲 §13 验收）：`update()` 期间重启是否与宿主的
     * 资源释放时序冲突（例如宿主仍持有旧监听）。若冲突，退回"只 stop + 显式日志告警"。
     */
    override fun update() {
        stop()
        start()
    }

    /** 卸载前清理：只停止，不重启（重启没有意义）。 */
    override fun delete() = stop()

    companion object {
        /** 当前运行时实例。仅由本插件与静态入口访问。 */
        @Volatile
        internal var runtime: CdlRuntime? = null

        /**
         * 停止运行时的**唯一实现**（[stop] 与 [requestStop] 共用）。
         *
         * 抽出来的理由：两条路径必须**行为完全一致** ——
         * 宿主调 `stop()` 与用户点菜单"退出"应当产生同样的释放序列。
         * 若各写一份，早晚会漂移（比如只在其中一处清了某个静态引用）。
         */
        private fun stopRuntime() {
            // ⚠️ **不要**在这里用 `val old = runtime ?: return` 提前返回 ——
            //    那会让下面的 `restore()` 被 `runtime` 状态门控，而它要撤销的是
            //    `TimerResolution` 自己的 `raised`，**两者无关** ✗。
            //    真机后果（2026-09-24 读码确证）：`start()` 抛异常时 `runtime` 已被置 null
            //    ⇒ 提前返回 ⇒ 定时器精度**永不回退** ⇒ 宿主停在 1ms 高精度态。
            //    不变量：**`raise()` 与 `restore()` 之间不得存在以 `runtime` 为条件的守卫。**
            val old = runtime
            // 先置空再关闭：`close()` 内部可能触发订阅回调，
            // 而那些回调若通过 `runtime` 取实例，应看到"已停止"而非"仍在运行"。
            runtime = null
            old?.close()
            // 与 [start] 里的 `TimerResolution.raise()` **严格配对** ——
            // 只 raise 不 restore 会让**整个宿主进程**永久处于高精度态（增加耗电 ✗）。
            // 放在这里而不是 `stop()` 里的理由：`stopRuntime()` 是 `stop()`、
            // "退出"（`requestStop`）与 `start()` 失败路径**共用的唯一实现** ✓
            // ⇒ 三条路径行为一致、不会漏 ✓。
            // ⚠️ "只在我们确实 raise 过时才回退"这个判定**在 `restore()` 内部**
            //    （`if (!raised) return`）—— **不要**在调用点再重复一遍 ✗，
            //    那正是本次缺陷的成因（调用点的守卫用的是另一个状态）。
            dev.cdl.platform.TimerResolution.restore()
        }

        /**
         * **仅供探针**：驱动「运行时释放序列」而无需真实宿主。
         *
         * 命名与项目既有接缝同形（`render/SpectralPainter.kt` 的 `paintSpectrumFrameForProbe`
         * 也是 public、由探针直接 import）。
         *
         * 它存在的唯一理由：`stopRuntime()` 是 `private`，而本次修复的缺陷
         * （释放被 `runtime` 状态门控）**只在 `runtime == null` 时暴露** ——
         * 那恰好是探针最容易构造、真机最难复现的状态。
         */
        fun releaseRuntimeForProbe() = stopRuntime()

        /**
         * 让插件**停止自身**（供词岛右键菜单与托盘菜单的"退出"调用）。
         *
         * ⚠️ **这不是"卸载插件"** —— PF4J 仍认为插件已加载，只是我们的运行时已释放
         * （窗口、托盘、订阅、时间轴全部关闭）。用户若想重新启用，需在 SPW 的
         * 插件管理里重新启用，或重启宿主。
         *
         * 为什么不做成"真卸载"：PF4J 的卸载要走 `PluginManager.unloadPlugin`，
         * 而那需要 `PluginManager` 实例 —— 插件 API 不提供（只有宿主能拿到）。
         * 故"退出"的语义只能是"停掉自己"，这与原实现一致。
         */
        internal fun requestStop() = stopRuntime()

        /** 宿主回调的落地目标。扩展点通过它把事件交给运行时（P1 起接入时间轴）。 */
        internal val playback = CallbackSink()

        // ── 设置页按钮入口 ──────────────────────────────────────────────
        // preference_config.json 的 on_click 约定是「全限定类名.静态方法名」，
        // 因此这里必须是 public static 无参方法。

        /**
         * **三个颜色取色器的静态入口**（`preference_config.json` 的 `on_click` 目标）。
         *
         * ## 这三个入口的现状：**已退休**（2026-09-17 第三次修订）
         *
         * 三次修订的脉络：
         *
         * | 轮次 | 做法 | 为什么不够 |
         * | --- | --- | --- |
         * | ① 原记 | 「打开设置」按钮指向自绘 `SettingsWindow` | 用户：「**不要此独立窗口**」→ 该窗已删除 |
         * | ② 2026-09-16 | 41 项按取值域重新分配；**只剩颜色与字体族**需要自绘窗（宿主 5 种控件表达不了） | 用户：「**在 SPW 设置页的设置项不要引去其他窗口**」 |
         * | ③ 2026-09-17 | 颜色 → **12 根 RGB/明度滑杆**；字体 → **构建期注入的 `list`**；三个取色器入口**退休** | —— |
         *
         * **根因（这条值得记）**：`recon/20` §A.7 曾用字节码"穷尽确证"宿主**没有**
         * 取色器、**没有**字体选择器 —— 那个结论**至今成立**。
         * 但"宿主没有颜色控件"**不等于**"颜色只能靠自绘窗"：
         * **SPW 有滑杆**，而 RGB + 明度四根滑杆足以表达任意颜色。
         * 当初的推理漏掉了这一步（"没有 color 控件" → "只能自绘"），
         * 于是绕了自绘窗这条路，而用户两次都把它否掉了。
         *
         * > **纪律：确认"宿主不支持 X"之后，还要问一句"用别的控件能不能凑出 X"。**
         * > 直接跳到"只能自绘"会让方案**看起来**是唯一解，实际是漏了一步推理。
         *
         * 三个方法**保留成显式退休**（打日志、什么都不做）而非删除：
         * `on_click` 走的是**反射**（`Class.getMethod(name).invoke(null)`），
         * 删掉会让"旧 `preference_config.json` 里残留的按钮"点击时抛
         * `NoSuchMethodException` —— 一个只在那一种情况下出现的崩溃。
         */
        // ── 三个取色器入口：**已退休**（2026-09-17）────────────────────────────
        //
        // ## 原记
        //
        // 三个独立按钮（`pickSungColor` / `pickUnsungColor` / `pickTranslationColor`）
        // 各打开一个取色器，写回对应的 hex 键；与 `edittext` 兜底**两条路径并存**。
        //
        // ## 实为（用户原话）
        //
        // > 「SPW 设置页中**我指名的 RGB 三色滑杆，明度滑杆呢**？」
        // > 「**在 SPW 设置页的设置项不要引去其他窗口**。」
        //
        // 即：颜色要用**设置页里的滑杆**改，不要再跳窗口。故：
        //   · 三个颜色键 → **12 根 RGB/明度滑杆**（见 `SettingRegistry.groupF()`）；
        //   · 设置页上的三个取色器按钮 → **删除**；
        //   · 本处三个入口 → **退休**（不再打开取色器，只留一条日志）。
        //
        // ## 为什么保留这三个方法而不是删掉
        //
        // `@JvmStatic` 入口是**宿主反射调用**的约定（`preference_config.json` 的
        // `on_click` 写的是全限定名）。**删掉它们**会让"旧版 `preference_config.json`
        // 或用户手工加的按钮"在点击时抛 `NoSuchMethodException` ——
        // 一个只在那一种情况下出现的崩溃。保留成**显式退休**（打日志、什么都不做）
        // 把"崩溃"换成"一条可读的日志"，代价是 3 个空方法。
        //
        // ⚠️ `CdlRuntime.openColorTool(key)` **仍在**（自绘窗口内部仍用它），
        //   只是**设置页与右键菜单都不再暴露入口**。
        @JvmStatic
        @JvmName("pickSungColor")
        fun pickSungColor() =
            openColorGroupSafely("pickSungColor", "main_lyric.fill.color", "已唱歌词颜色")

        @JvmStatic
        @JvmName("pickUnsungColor")
        fun pickUnsungColor() =
            openColorGroupSafely("pickUnsungColor", "main_lyric.fill.unsung", "未唱歌词颜色")

        @JvmStatic
        @JvmName("pickTranslationColor")
        fun pickTranslationColor() =
            openColorGroupSafely("pickTranslationColor", "main_lyric.translation.color", "译文歌词颜色")

        // ── 2026-09-19：其余颜色组也换成取色器 ────────────────────────────
        //
        // 用户追问：「前面不是让你改颜色控件了吗？为什么现在还有设置项是滑杆改色
        // 或者输入色号改色?」—— 上一轮只做了歌词三色，这三组漏了。

        @JvmStatic
        @JvmName("pickMainOutlineColor")
        fun pickMainOutlineColor() =
            openColorGroupSafely("pickMainOutlineColor", "main_lyric.outline.color", "主歌词描边色")

        @JvmStatic
        @JvmName("pickTranslationOutlineColor")
        fun pickTranslationOutlineColor() =
            openColorGroupSafely(
                "pickTranslationOutlineColor", "translation.outline.color", "副歌词描边色",
            )

        @JvmStatic
        @JvmName("pickBorderColor")
        fun pickBorderColor() =
            openColorGroupSafely("pickBorderColor", "island.border.color", "边框色")

        /**
         * **频谱色**入口 —— 它与其他组不同：是**单键 `COLOR`**（不是 r/g/b 组），
         * 故走 [CdlRuntime.openColorTool] 那条单键路径。
         */
        @JvmStatic
        @JvmName("pickSpectrumColor")
        fun pickSpectrumColor() = openColorGroupSafely("pickSpectrumColor", "spectrum.color", "频谱颜色")

        /**
         * **背景色**入口（2026-09-22 用户点名补的第 8 个取色按钮）。
         *
         * ## 用户原话
         *
         * > 「**有些选项提供自选色但是并没有提供选色入口，如背景设色**」
         *
         * 即：`background.color` 这个键**存在**（用户能改它的值），
         * 但设置页上**没有任何入口**能打开取色器 —— 于是"能改"这件事只写在注册表里，
         * 用户在界面上找不到。这与 `spectrum.color` 当初的形态**完全一样**
         * （见 [pickSpectrumColor]），故照它抄。
         *
         * ## 为什么走单键路径而不是四键组
         *
         * `background.color` 是**单键 `COLOR`**（不是 `$prefix.r/.g/.b` 四键组），
         * 与 `spectrum.color` 同制 —— [openColorGroupSafely] 会按注册表查
         * `background.color.r` 是否存在来自动判定，**不靠调用方记得传对**
         *（少一处能写错的地方）。
         *
         * ## ⚠️ 依赖
         *
         * 本入口要求注册表里有 `background.color`（COLOR，进 `SELF_DRAWN_KEYS`）。
         * 键不在时 `ToolWindows.openColor` 会 `warn("键不存在")` 后返回 ——
         * **不崩**，但用户会看到"点了没反应"。故两个改动必须同批上线。
         */
        @JvmStatic
        @JvmName("pickBackgroundColor")
        fun pickBackgroundColor() =
            // ⚠️ 标题与注册表里 `background.color` 的 `title`（「背景颜色」）**逐字一致** ——
            //   与 `pickSpectrumColor`（注册表 title 与窗口标题都是「频谱颜色」）同一条规矩。
            openColorGroupSafely("pickBackgroundColor", "background.color", "背景颜色")

        /**
         * 打开**渐变止色**取色器（2026-09-25 第 2 期「材质」）。
         *
         * ## 为什么必须有这个入口（不是"顺手加的"）
         *
         * `material.fill.color_end` 的 `kind` 是 `COLOR` —— 而 SPW 设置页的 5 种控件
         * （`switch` / `list` / `seekbar` / `edittext` / `button`）**没有颜色控件**
         * （见 `recon/18` §A）。这正是 F §1.3 记的「**断点 A**」：
         * **键存在、但设置页上看不见，用户改不了也不知道它存在。**
         *
         * 故 COLOR 键必须凑齐「**3 处 + 1 按钮**」：
         * ① 注册表 descriptor · ② `COLOR_GROUPS` · ③ `SELF_DRAWN_KEYS`
         * · ④ **本入口** ＋ `gen_preference_config.py` 的 `BUTTONS` / `BUTTON_ANCHOR`。
         * **少一处就静默失效**（用户点不到，或点了没反应）。
         *
         * ⚠️ 标题与注册表里 `material.fill.color_end` 的 `title`（「渐变止色」）**逐字一致** ——
         *   与 `pickSpectrumColor` / `pickBackgroundColor` 同一条规矩。
         */
        @JvmStatic
        @JvmName("pickGradientEndColor")
        fun pickGradientEndColor() =
            openColorGroupSafely("pickGradientEndColor", "material.fill.color_end", "渐变止色")

        /**
         * ★★★ 2026-10-15（**§3.75**）打开**插槽频谱颜色**取色器。
         *
         * 用户诉求（逐字）：「**开放对插槽频谱的独立选色**」—— 拍板范围 = **完整取色模式**，
         * 故插槽有了自己的 `island.right_slot.spectrum_color_mode`（LIST，走宿主设置页）
         * 与 `island.right_slot.spectrum_color`（**单键 COLOR**，走本入口）。
         *
         * ★ 为什么必须有本入口：COLOR 键在 SPW 设置页**没有控件**（F §1.3「断点 A」）
         * ⇒ 必须凑齐「**3 处 + 1 按钮**」：① 注册表 descriptor · ② `COLOR_GROUPS`
         * · ③ `SELF_DRAWN_KEYS` · ④ **本入口** ＋ `gen_preference_config.py` 的
         * `BUTTONS` / `BUTTON_ANCHOR`。**少一处就静默失效** ✓
         *
         * ⚠️ 标题与注册表里该键的 `title`（「插槽频谱颜色」）**逐字一致** ——
         *   与 [pickSpectrumColor] / [pickBackgroundColor] / [pickGradientEndColor] 同一条规矩。
         */
        @JvmStatic
        @JvmName("pickRightSlotSpectrumColor")
        fun pickRightSlotSpectrumColor() =
            openColorGroupSafely(
                "pickRightSlotSpectrumColor",
                "island.right_slot.spectrum_color",
                "插槽频谱颜色",
            )

        /**
         * 三个取色器入口共用的转发（**只转发不实现**，与 [pickFont] 同一形态）。
         *
         * ## 2026-09-18：从「已退休」恢复为「真正开窗」
         *
         * 本入口在 09-17 曾被标为退休（颜色改由 12 根滑杆承担）。
         * 用户 09-18 要求把滑杆**换成取色器样式**，故入口恢复 ——
         * 现在它打开的是完整取色器（色域板 + 色相条 + 明度条 + RGB + hex）。
         *
         * **兜底（try/catch + toast + EDT）在 [CdlRuntime.openColorGroup] 里** ——
         * 本方法是 `@JvmStatic`、拿不到 `PluginContext`，**用不了 `WorkshopApi`**。
         * 这是与 [pickFont] 完全相同的分工。
         */
        private fun openColorGroupSafely(entry: String, prefix: String, title: String) {
            // 单键 COLOR（如 `spectrum.color`）没有 `.r/.g/.b` 子键 → 走单键路径。
            // 判据用注册表查，**不靠调用方记得传对**（少一处能写错的地方）。
            val isGroup = dev.cdl.settings.SettingRegistry.descriptor("$prefix.r") != null
            if (isGroup) {
                CdlRuntime.openColorGroup(prefix, title)
            } else {
                CdlRuntime.openColorTool(prefix)
            }
        }

        /**
         * 三个取色器入口共用的"已退休"提示。
         *
         * 写成一处是为了**文案一致** —— 三份手写文案最容易在改动时漏掉一份，
         * 而那会让用户看到三种不同的说法，怀疑是不是坏了不同的东西。
         */
        private fun reportRetiredColorTool(entry: String, title: String) {
            System.err.println(
                "[CDL] $entry 已退休（$title）：本版颜色改由 SPW 设置页的 " +
                    "RGB + 明度滑杆调整，不再打开取色器窗口。该入口保留仅为兼容旧配置的按钮声明。",
            )
        }

        /**
         * **字体三来源选择器**（`preference_config.json` 的 `on_click` 目标）。
         *
         * 用户 2026-09-16 诉求原文：「字体选择那里使用近期使用字体列表，
         * 本机完全字体列表加从电脑中选择字体包的形式」。
         *
         * ## 为什么这个按钮是**唯一**能表达该诉求的形态
         *
         * SPW 设置页只有 5 种控件（`recon/20` §A.7 字节码确证），
         * 其中 `list` **只支持静态选项**（写在 json 里），而"本机有哪些字体"
         * 是**运行时**才知道的 —— 故三个来源**都无法**用 `list` 表达。
         * `on_click` 是宿主唯一"能触发插件代码"的通道（`recon/20` §A.3：
         * 裸反射 `Class.getMethod(name).invoke(null)`），因此选择器只能挂在按钮上。
         *
         * ## 契约（宿主侧，`recon/20` §A.3 逐字确证）
         *
         * 必须是 **public static 无参**；返回值被丢弃；抛异常会被宿主 catch 并只记 VLog
         * （用户不可见）。故本方法**不依赖**宿主兜底 —— 见 [CdlRuntime.pickFont]。
         */
        @JvmStatic
        @JvmName("pickFont")
        fun pickFont() {
            CdlRuntime.pickFont()
        }

        /**
         * **找回词岛**（`preference_config.json` 的 `on_click` 目标）。
         *
         * 语义：显示词岛 + 解除鼠标穿透 + 移回主屏居中。
         * 原实现灵动岛有同名按钮，存在的理由是**词岛可能被拖到屏幕外找不回来** ——
         * 它是无边框悬浮件，没有标题栏可拖回，穿透开启后鼠标也点不到。
         *
         * 实现细节见 [CdlRuntime.recoverIsland] 与
         * `IslandWindow.resetToPrimaryScreen` 的 KDoc。
         */
        @JvmStatic
        @JvmName("recover")
        fun recover() {
            CdlRuntime.recoverIsland()
        }

        @JvmStatic
        @JvmName("about")
        fun about() {
            CdlRuntime.showAbout()
        }

        @JvmStatic
        @JvmName("openSource")
        fun openSource() {
            CdlRuntime.openSource()
        }

        /**
         * **删除预设…** / **更改预设…** 两个入口（`preference_config.json` 的 `on_click` 目标）。
         *
         * ## 用户原话（逐字）
         *
         * > 「**在设置项中要提供删除、更改预设的入口**。」
         *
         * ## 为什么必须在设置页给入口（而不是只在右键菜单里）
         *
         * 右键菜单里**已经有**「配置预设」子树（选择 / 导入 / 导出，见
         * `IslandPopupMenu.buildPresetMenu`），但用户点名的位置是**设置项** ——
         * 那是他找设置的地方。两个入口都补上：设置页给"我知道要删/要改"的人，
         * 右键菜单给"我正在调参、顺手存一下"的人。四处（设置页 2 + 菜单 2）
         * 最终都落到 `CdlRuntime.openPresetManager` 这**一条**实现上。
         *
         * ## 语义（详细说明见 `settingsui/PresetManagerDialog.kt`）
         *
         * · **删除** = 把预设文件移入 `presets/_deleted/`（**不是** `delete()`，可手工移回）；
         * · **更改** = 用当前配置覆盖选中的预设文件（文件名不变 ⇒ 预设名不变）；
         * · **内置预设不可删 / 不可改**（它们是代码里的常量，没有文件）。
         *
         * ## 线程与兜底
         *
         * 与 [pickFont] / [recover] 同构：`@JvmStatic` 入口**拿不到 `PluginContext`**，
         * 故 EDT 投递与 toast 兜底都在 [CdlRuntime.openPresetManager] 里。
         */
        @JvmStatic
        @JvmName("deletePreset")
        fun deletePreset() {
            CdlRuntime.openPresetDeleter()
        }

        @JvmStatic
        @JvmName("changePreset")
        fun changePreset() {
            CdlRuntime.openPresetChanger()
        }
    }
}
