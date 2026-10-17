// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D

/**
 * 控件层的**配置快照** —— 把 `controls.*` 的设置值收敛成一个不可变值对象。
 *
 * ## 为什么是 `public` 而不是 `internal`（本包内唯一的一处例外）
 *
 * `probe` source set 是**独立的 Kotlin 编译单元**，看不到 `main` 的 `internal`
 * （Kotlin 的 `internal` 按模块可见，而模块 = 一次编译）。而控件层的布局与状态机
 * 恰恰是**必须离线验证**的部分（见 `ControlsProbe.kt` 的说明：它们错了不抛异常，
 * 只表现为"看得见点不中""永不收起"，真机调试成本极高）。
 *
 * 这不是新开的先例：`SettingRegistry` 同样是 `public`，理由与用途完全一致
 * （`RegistryProbe.kt` 要直接读它）。**其余内部实现仍保持 `internal`** ——
 * 只有"探针需要直接构造并断言"的这三处例外（本类 · [HoverState] · [PlaybackControls]）。
 *
 * ## 为什么要有这一层（而不是渲染时现读快照）
 *
 * 与 `dev.cdl.window.Appearance` 同源的理由：控件条在一次绘制里要用到 9 个键，
 * 每次现读都要走 `SettingRegistry.descriptor` + `SettingsSnapshot` 两次查找；
 * 更要紧的是**悬停命中判定与绘制必须用同一份参数** —— 若一边现读一边用缓存，
 * 用户在设置窗口拖动 `controls.gapPx` 的瞬间就会出现"画在一处、点不中另一处"。
 * 派生成不可变值对象后，两者读的是同一个实例，不一致在结构上不可能发生。
 *
 * ## 字段与设置键的对应（**这是"消费了哪些键"的唯一清单**）
 *
 * | 字段 | 设置键 | 默认 | 消费点 |
 * | --- | --- | --- | --- |
 * | [triggerMode] | `controls.triggerMode` | `GLYPH_HIT` | `IslandWindow.onPointer` 决定触发判据 |
 * | [enterDelayMs] | `controls.enterDelayMs` | `120` | [HoverState.update] |
 * | [exitDelayMs] | `controls.exitDelayMs` | `250` | [HoverState.update] |
 * | [hitPaddingPx] | `controls.hitPaddingPx` | `2` | 传给 `GlyphHitTester.hitTest` 的 `padding` |
 * | [position] | `controls.position` | `BOTTOM` | [PlaybackControls.layout] |
 * | [gapPx] | `controls.gapPx` | `8` | [PlaybackControls.layout] |
 * | [showProgress] | `controls.showProgress` | `true` | [PlaybackControls.layout] |
 * | [autoHideMs] | `controls.autoHideMs` | `0` | [HoverState.update] |
 * | [progressStyle] | `controls.progress_style` | `CLASSIC` | [PlaybackControls.paintProgress] |
 *
 * 十项**全部有消费者**，没有遗留的悬空键。
 */
data class ControlsConfig(
    /** `GLYPH_HIT`（默认，字形命中）或 `ISLAND_HIT`（轮廓/矩形命中）。 */
    val triggerMode: String,
    val enterDelayMs: Long,
    val exitDelayMs: Long,
    val hitPaddingPx: Float,
    /** `TOP` / `BOTTOM`。相对**内容区**（歌词区），不是相对词岛边缘。 */
    val position: String,
    val gapPx: Float,
    val showProgress: Boolean,
    /** `0` = 禁用自动隐藏。 */
    val autoHideMs: Long,
    /**
     * `CLASSIC`（默认，实心 + 圆点恒显示）或 `GLOW`（羽化高光推进 + 圆点按需）。
     *
     * 取值常量见 `SettingRegistry.PROGRESS_STYLE_CLASSIC` / `_GLOW`
     * （**不在此处再写一份字面量** —— 本项目纪律：同一条规则只出现一次）。
     */
    val progressStyle: String,
) {
    /** 命中判据是否是"字形"（否则按岛内矩形）。 */
    val glyphTrigger: Boolean get() = triggerMode != ISLAND_HIT

    /**
     * 是否走**流光**形态（羽化高光推进 + 圆点按需显示）。
     *
     * 判据放这里而不是让绘制层自己比字符串：那样等于把"哪一档开什么"
     * 复制到 N 个消费点（`MotionQuality` 的 KDoc 记过同一类教训）。
     */
    val glowProgress: Boolean get() = progressStyle == GLOW

    companion object {
        /** 注册表默认值的等价物。设置系统不可用时用它，保证控件仍能按合理默认工作。 */
        const val ISLAND_HIT = "ISLAND_HIT"

        /** `controls.position` 的"控件在内容区上方"。 */
        const val TOP = "TOP"

        /**
         * 进度条形态常量 —— **与 `SettingRegistry` 的两个 `const val` 同值**。
         *
         * ⚠️ 这里**必须**再声明一份，因为 `window` 包不能 import `settings` 包的
         * 注册表常量（会让控件层依赖设置系统的实现细节 —— 与 `settingsui` 不
         * 依赖 `window` 是**同一条纪律的反方向**）。
         *
         * ⇒ 两份字面量的一致性由**探针跨包钉住**（`ControlsProbe` 断言
         * `ControlsConfig.CLASSIC == SettingRegistry.PROGRESS_STYLE_CLASSIC`），
         * 而不是靠"记得改两处"。这与 `previewIslandShapeCore` 的做法同构。
         */
        const val CLASSIC = "CLASSIC"

        /** 见 [CLASSIC] 的说明。 */
        const val GLOW = "GLOW"

        val DEFAULTS = ControlsConfig(
            triggerMode = "GLYPH_HIT",
            enterDelayMs = 120,
            exitDelayMs = 250,
            hitPaddingPx = 2f,
            position = "BOTTOM",
            gapPx = 8f,
            showProgress = true,
            autoHideMs = 0,
            // ⚠️ 默认 = 经典：本项目"默认值稳"那条纪律 —— 老用户升级后画面逐像素不变。
            progressStyle = CLASSIC,
        )
    }
}

/**
 * 播放控件的**几何与绘制** —— 上一首 / 播放暂停 / 下一首 三个按钮 + 一条播放进度条。
 *
 * > `public` 的理由见 [ControlsConfig] 的类 KDoc（探针要直接断言布局几何，故不能是 `internal`）。
 *
 * ## 布局模型：控件条是词岛**多出来的一条**，不覆盖歌词
 *
 * 展开时 `IslandWindow` 把窗口**加高**一个 [stripHeight]，歌词仍按原尺寸排在原区域：
 *
 * ```
 * position = BOTTOM（默认）              position = TOP
 * ┌──────────────────────┐  ← 0        ┌──────────────────────┐  ← 0
 * │  歌词区（原高度）      │             │  按钮行               │
 * ├──────────────────────┤  ← base     │  进度条               │  ← strip
 * │  进度条               │             ├──────────────────────┤  ← strip 底
 * │  按钮行               │             │  歌词区（原高度）      │
 * └──────────────────────┘  ← height   └──────────────────────┘  ← height
 * ```
 *
 * **★ 进度条总在「控件与歌词之间」（2026-09-18 修订）**：
 * 用户诉求「进度条应处在播放控件与歌词之间（**无论**播放控件在歌词行的上下左右）」。
 * 改动前是"由岛的外缘向内排"（TOP 时先进度条），于是**两种朝向的进度条都在外侧、离歌词最远**。
 * 现在统一为「**按钮在外、进度条在内**」—— 进度条永远紧贴歌词区，
 * 因为它是一条**指向当前播放位置**的指示器，贴着内容才有意义。
 *
 * **为什么不覆盖在歌词上**：词岛高度只有「三行歌词 + 上下留白」（默认字号下 125 px），
 * 控件条要 76 px —— 覆盖就等于把"下一句歌词"整条挡掉，而下一句恰恰是用户最想看的。
 * **为什么不让歌词压缩**：压缩会改动歌词排版（另一个代理正在做逐字高亮），
 * 而"加高一条"对歌词是**零改动**（歌词的 `centerY` 只需平移 [Layout.lyricsTop]）。
 *
 * ## 内/外顺序：进度条在**内**（紧贴歌词）、按钮在**外**
 *
 * ⚠️ **2026-09-24 修**：本段原先写「进度条在**外**、按钮在**内**（沿用 0.8.9）」✗ ——
 * **与本文件的实现相反**，也与**用户的诉求相反** ✗。
 *
 * 用户原话（`layoutOf` 里逐字记着，2026-09-18）：
 * > 「**进度条应处在播放控件与歌词之间**（无论播放控件在歌词行的上下左右）」
 *
 * ⇒ `layoutOf` 的实际排布是「**按钮在外、进度条在内（紧贴歌词）**」✓：
 * `TOP` 时按钮在上、进度条在下；`BOTTOM` 时进度条在上、按钮在下。
 *
 * （原实现 0.8.9 的 `IslandPanel.doLayout` 确实把进度条放在**外缘** ——
 *  **本实现有意不沿用** ✓，因为用户要的是"进度条夹在控件与歌词之间"。）
 *
 * ## 尺寸常量为什么是常量而不是设置项
 *
 * `recon/13` §10.4 的 `controls.buttonSize` / `buttonSpacing` / `iconSize` / `iconColor` /
 * `hoverColor` / `background` 六项在 `SettingRegistry` 里**根本没有声明**
 * （注册表只住 9 个 `controls.*` 键，见 `SettingRegistry.groupH`）。
 * 故这里用常量，取值照 `recon/13` 的建议默认值；其中
 * [BUTTON_SPACING] = 26 是 `recon/13` §12.0 **D-e 用户拍板值**（"默认 26 贴齐现状"）。
 * **不自行新增设置键** —— 那是设置系统的事，且会让"注册表只有 9 项"的核对失效。
 */
class PlaybackControls {

    /**
     * 控件条上的可命中元素。
     *
     * `PROGRESS` 现在**可响应点击与拖动**（seek）—— 前提是总时长已知
     * （见 `PlaybackControls.seekTargetAt` 对"时长未知时不响应"的说明）。
     */
    enum class Id { PREVIOUS, TOGGLE, NEXT, PROGRESS }

    /** 手绘图标。**不依赖字体或图片资源** —— 宿主环境不保证有对应字体。 */
    private enum class Icon { PLAY, PAUSE, PREVIOUS, NEXT }

    /** 轴对齐矩形（局部坐标，原点 = 面板左上角）。 */
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun contains(px: Float, py: Float): Boolean =
            px >= x && px <= x + width && py >= y && py <= y + height

        val centerX: Float get() = x + width / 2f
        val centerY: Float get() = y + height / 2f
    }

    /** 一个按钮：语义 id + 它的矩形。 */
    data class Button(val id: Id, val rect: Rect)

    /**
     * 一次布局的产物。
     *
     * @param expanded 控件是否展开。`false` 时 [buttons] 为空、[progress] 为 null
     * @param lyricsTop 歌词区上边缘（展开且 `position = TOP` 时 = 控件条高度）
     * @param lyricsHeight 歌词区高度。**收起时等于整个面板高度** ——
     *   这正是"歌词排版零改动"的落地：收起态下 `lyricsTop = 0` 且 `lyricsHeight = height`，
     *   与改动前的 `centerY = height / 2` 完全等价
     * @param stripHeight 控件条占用的高度（收起时为 0）
     */
    data class Layout(
        val expanded: Boolean,
        val buttons: List<Button>,
        val progress: Rect?,
        val lyricsTop: Float,
        val lyricsHeight: Float,
        val stripHeight: Float,
    )

    /**
     * 控件条需要多高。
     *
     * 公式 = 外侧留白 + 按钮行 + 行距 + （有进度条时的）进度条行 + 与歌词区的间距。
     * **两种 `position` 的总高相同**（只是行的先后顺序相反），故只需一个公式。
     */
    fun stripHeight(cfg: ControlsConfig): Float =
        STRIP_PADDING + BUTTON_SIZE + (if (cfg.showProgress) ROW_GAP + PROGRESS_HEIGHT else 0f) + cfg.gapPx

    /**
     * 算一次布局。**绘制与命中判定都必须调它**（同一份输入 → 同一份输出，
     * 这是"画在哪就能点在哪"的前提）。
     *
     * @param height 面板的**当前**高度。展开时它已含控件条（由 `IslandWindow` 调 `setSize` 加高），
     *   故这里用 `height - stripHeight` 反推歌词区 —— **不额外存一份基准高度**，
     *   避免"面板高度"与"基准高度"两个字段漂移
     *
     * ⚠️ **控件带动画期间不要用它** —— 那条反推的前提（面板高度 == 展开态高度）
     *   在动画期间成立、但"岛体形状"在变，反推出的 `base` 会随动画漂移。
     *   动画路径请用 [layoutOf]（显式传基准高度与带高）。
     */
    fun layout(width: Float, height: Float, cfg: ControlsConfig, expanded: Boolean): Layout {
        if (!expanded) {
            return Layout(false, emptyList(), null, 0f, height, 0f)
        }
        // 展开态：面板高度**已经含**控件条，故反推基准高度。见类 KDoc。
        val strip = stripHeight(cfg)
        return layoutOf(width, (height - strip).coerceAtLeast(0f), strip, cfg)
    }

    /**
     * 由「**基准高度**（不含控件条）+ 带高」直接布局。
     *
     * ## 为什么需要它（与 [layout] 的区别，2026-09-22 修「上下闪动」）
     *
     * [layout] 的入参是**面板当前高度**，靠 `height - stripHeight` 反推基准高度 ——
     * 那个反推**只在"面板高度恒等于展开态高度"时成立**。
     * 而控件带弹出/收回的动画期间，面板高度**恒定是展开态**、岛体形状却在连续变化
     * （见 `dev.cdl.window.bandGeometry`）——
     * 此时若仍走反推，`base` 会算成 `base + strip × fraction - strip`（**负数方向**），
     * 歌词区与控件带的位置都会随动画漂移。
     *
     * 故动画路径必须**显式给出基准高度与带高**，两者在整个动画期间都是常量：
     * 歌词不动（用户明确要求），带内布局也不动（否则按钮会在动画期间滑动，
     * 与 `MOTION` 的位移叠加成两段运动）。
     *
     * @param baseHeight 控件条**收起时**的词岛高度（`IslandWindow.baseHeightPx`）。
     * @param stripPx 控件带高度。**必须与窗口侧用的是同一个量化值** ——
     *   否则"画在哪"与"窗口有多大"会差最多 1px（见 `IslandWindow.applyWindowSize` 的注释）。
     */
    fun layoutOf(
        width: Float,
        baseHeight: Float,
        stripPx: Float,
        cfg: ControlsConfig,
    ): Layout {
        val strip = stripPx.coerceAtLeast(0f)
        val base = baseHeight.coerceAtLeast(0f)
        val topStrip = cfg.position == ControlsConfig.TOP
        val lyricsTop = if (topStrip) strip else 0f
        val stripTop = if (topStrip) 0f else base

        // **按钮在外、进度条在内（紧贴歌词）** —— 两种朝向统一。见类 KDoc 的示意图。
        // 用户诉求（2026-09-18）：「进度条应处在播放控件与歌词之间（无论播放控件在歌词行的上下左右）」。
        var cursor = if (topStrip) stripTop + STRIP_PADDING else stripTop + cfg.gapPx
        var progressY = cursor
        val buttons: List<Button>
        if (topStrip) {
            // TOP：按钮在外（上），进度条在内（下）→ 进度条紧贴其下的歌词区。
            buttons = buttonRow(cursor, width, buttonIds(width))
            cursor += BUTTON_SIZE + if (cfg.showProgress) ROW_GAP else 0f
            progressY = cursor
        } else {
            // BOTTOM：进度条在外（上），按钮在内（下）→ 进度条紧贴其上的歌词区。
            if (cfg.showProgress) {
                progressY = cursor
                cursor += PROGRESS_HEIGHT + ROW_GAP
            }
            buttons = buttonRow(cursor, width, buttonIds(width))
        }
        // 进度条的纵向位置至此确定；横向先给"整条内容宽度"，绘制时再按时间标签的
        // 实际宽度内缩（见 paintProgress）。命中判定用这个范围即可 ——
        // 首版进度条不响应点击，但保留完整的命中矩形，将来接上 seek 时不必改这里。
        val progress = if (cfg.showProgress) {
            val side = PROGRESS_SIDE_PADDING
            Rect(side, progressY, (width - side * 2f).coerceAtLeast(0f), PROGRESS_HEIGHT)
        } else {
            null
        }
        return Layout(true, buttons, progress, lyricsTop, base, strip)
    }

    /**
     * 命中判定。
     *
     * ## 为什么先判按钮、后判进度条
     *
     * 两者的矩形**在纵向不重叠**（见 [layout] 的游标推进），故顺序其实无关紧要。
     * 但把按钮放在前面表达了一个意图：按钮是"可点的"，进度条首版只是"看得见的" ——
     * 将来若为了让进度条好点中而把它的命中域外扩，**按钮必须仍然优先**，
     * 否则外扩的进度条会吃掉按钮下缘的点击。
     */
    fun hit(layout: Layout, x: Float, y: Float): Id? {
        layout.buttons.firstOrNull { it.rect.contains(x, y) }?.let { return it.id }
        layout.progress?.let { if (it.contains(x, y)) return Id.PROGRESS }
        return null
    }

    /**
     * 进度条**轨道**（不含两侧时间标签）的精确几何。
     *
     * ## 为什么必须抽成一个函数（而不是在 paintProgress 里内联算）
     *
     * seek 要把"点击的 x"换算成"目标毫秒"，那个换算**必须用绘制时的同一条轨道**。
     * 若两边各算一次（绘制里算 `barX/barWidth`、seek 里按 `rect.x/width` 算），
     * 就会出现「点在这里、跳到的却是那里」的偏移 —— 且偏移量随时间标签的宽度变化，
     * 表现为"进度条拖动越到后面越不准"，极难定位。
     *
     * 与 `IslandWindow.IslandPanel.drawnRows` 是同一个原则：
     * **绘制与命中必须共用同一份几何**，不一致在结构上不可能发生。
     *
     * @param metrics 用于量时间标签宽度；必须与绘制时用的是同一份 `FontMetrics`
     */
    fun trackRect(rect: Rect, metrics: java.awt.FontMetrics, positionMs: Long, durationMs: Long): Rect {
        val known = durationMs > 0
        val left = timeLabel(positionMs)
        val right = if (known) timeLabel(durationMs) else UNKNOWN_TIME
        // 标签占宽按**两者中更宽的那个**取（左右对称），否则进度条会随秒数进位左右横跳。
        val labelWidth = maxOf(metrics.stringWidth(left), metrics.stringWidth(right)) + LABEL_GAP
        val barX = rect.x + labelWidth
        val barWidth = (rect.width - labelWidth * 2f).coerceAtLeast(MIN_BAR_WIDTH)
        val barY = rect.centerY - BAR_THICKNESS / 2f
        return Rect(barX, barY, barWidth, BAR_THICKNESS)
    }

    /**
     * 把进度条上的一个**按下/拖动 x 坐标**换算成目标毫秒。
     *
     * ## 返回 `null` 的三种情形（调用方必须"什么都不做"）
     *
     * | 情形 | 为什么必须返回 null |
     * | --- | --- |
     * | 总时长未知（`durationMs <= 0`） | 分母未知，换算无意义 —— 这正是本插件接入时长读取**之前**的状态 |
     * | `rect` 为 null（进度条被关闭） | 用户关掉了进度条，不该有任何 seek |
     * | `metrics` 量出的轨道宽 ≤ 0 | 极端窄岛下的退化，换算会除零 |
     *
     * ## 为什么 x 要 **clamp 到轨道两端**而不是"点在外面就不响应"
     *
     * 拖动时用户经常把鼠标甩到轨道外面（想跳到接近结尾处，手一滑就出了界）。
     * 此时行为应当是"跳到 0"或"跳到末尾"，而不是"这次拖动作废" ——
     * 后者表现为"拖到边缘就不跟手了，得拖回来"。这正是 0.8.9
     * `PlaybackProgress.positionAt` 里 `coerceIn(0.0, 1.0)` 的语义。
     *
     * @param x 面板局部坐标里的 x
     * @return 目标毫秒；`null` = 这次交互不该产生 seek
     */
    fun seekTargetAt(
        progress: Rect?,
        metrics: java.awt.FontMetrics,
        x: Float,
        durationMs: Long,
    ): Long? {
        if (progress == null || durationMs <= 0) return null
        // 用 `positionMs = 0` 量标签宽度：轨道宽只取决于**标签宽度**，
        // 而标签宽度取的是左右两个标签里更宽的。用 0 会让左标签是 `0:00`（可能比右标签窄），
        // 于是轨道宽会**比绘制时略宽** —— 造成左端偏移。
        // 正确做法是让调用方传"当前正在显示的那个 positionMs"，故本函数要求
        // 传入的 `progress` 已由调用方用**同一份** position/duration 算出的轨道。
        // 这里只做映射，不再重算宽度。
        if (progress.width <= 0f) return null
        val ratio = ((x - progress.x) / progress.width).coerceIn(0f, 1f)
        return (ratio * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /**
     * 画控件条。**调用方必须已经把 `Graphics2D` 的 clip 设成词岛形状** ——
     * 圆角/胶囊形态下控件条的边缘会伸到圆角之外（默认圆角 60% 短边 ≈ 37 px），
     * 不裁就会看到"进度条戳出卡片"。
     *
     * @param accent 进度条已播放部分的颜色。由调用方给（通常是"已唱歌词色"），
     *   这样控件与歌词的配色天然一致，不必再引入一个独立色键
     * @param alpha 淡入动画的进度 `0f..1f`（来自 `HoverState.expansion`）。
     *   `1f` = 完全不透明。**必须由调用方传入**，控件层不持有动画状态
     *   （理由见 `HoverState.expansion` 的 KDoc）
     * @param previewMs 拖动进度条时的**预览位置**；`null` = 不在拖动。
     *   非 null 时进度条显示它而不是 `positionMs` —— 这是 0.8.9
     *   `PlaybackProgress.preview` 的语义：拖动过程中**不提交**，只改显示，
     *   松手才真正 seek。这样连续拖动不会产生几十次 seek 请求
     */
    fun paint(
        g2: Graphics2D,
        layout: Layout,
        hovered: Id?,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        accent: Color,
        cfg: ControlsConfig,
        alpha: Float = 1f,
        previewMs: Long? = null,
        /**
         * **风格化变换**（位移 / 缩放）。`null` = 不变换。
         *
         * 由调用方按 `MotionStyle.offsetYAt` / `scaleAt` 算出 —— **控件层不认识
         * `MotionStyle`**：它负责"画成什么样"，动画层负责"怎么动"。
         *
         * ## 为什么传一个现成的 `AffineTransform`，而不是 `offsetY` + `scale` 两个数
         *
         * 因为**缩放的中心点需要面板尺寸**，而 `paint` 只拿到 [layout] ——
         * 从 `layout` 反推面板宽高既绕又易错（`TOP` / `BOTTOM` 两种排布下控件条
         * 的位置相反）。调用方本来就持有宽高，让它算完再传进来，
         * 本层就只需要 `layer.transform(...)` 一行。
         */
        transform: java.awt.geom.AffineTransform? = null,
    ) {
        if (!layout.expanded) return
        // 淡入 / 位移 / 缩放三者**必须共用同一个临时图层**：
        // 分三次各建一个图层的话，后建的会丢掉前一个的合成状态
        // （表现为"滑入时没有淡入"或"缩放了但没透明"）。
        //
        // 为什么都在控件层做而不是在 IslandWindow：`paint` 是控件条绘制**唯一**的入口，
        // 在这里套一层能保证"按钮 + 进度条 + 悬停底色"三者**同步**变换。
        // 若在外面按组件分别设，三者的曲线就可能不一致（各自被调用一次）。
        val layered = alpha < 1f || transform != null
        val g = if (!layered) {
            g2
        } else {
            (g2.create() as Graphics2D).also { layer ->
                if (alpha < 1f) {
                    layer.composite = java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f),
                    )
                }
                transform?.let { layer.transform(it) }
            }
        }
        try {
            layout.buttons.forEach { button ->
                if (button.id == hovered) {
                    // 悬停底色：让用户确认"这里能点"。0.8.9 没有悬停态样式
                    // （recon/13 §10.4 的 `controls.hoverColor` 标为"待实现"），
                    // 这里用常量而非设置项，理由同类 KDoc 的"尺寸常量"一节。
                    g.color = HOVER_BACKDROP
                    g.fill(
                        RoundRectangle2D.Float(
                            button.rect.x, button.rect.y, button.rect.width, button.rect.height,
                            HOVER_CORNER, HOVER_CORNER,
                        )
                    )
                }
                drawIcon(g, iconFor(button.id, playing), button.rect.centerX, button.rect.centerY, ICON_SIZE)
            }
            layout.progress?.let {
                paintProgress(
                    g, it, previewMs ?: positionMs, durationMs, accent,
                    // `dragging` = 预览中。**松手后 `previewMs` 归 null**，
                    // 于是拖动结束的那一刻 `dragging` 变回 false —— 正确
                    //（此时圆点是否还在，由 `hovered` 决定）。
                    dragging = previewMs != null,
                    cfg = cfg,
                    // ★ 诉求 ⑭：`GLOW` 形态下"鼠标在进度条这一行上"时圆点才出现。
                    //   判据是 `hovered == Id.PROGRESS`（**已经由命中判定算出来**
                    //   的那一个值），不另起一套几何 —— 否则"画在哪"与"悬停在哪"
                    //   会各算一次（本项目"绘制与命中必须共用同一份几何"那条纪律）。
                    hovering = hovered == Id.PROGRESS,
                )
            }
        } finally {
            if (g !== g2) g.dispose()
        }
    }

    /** 按钮语义 → 图标。播放/暂停键**必须随播放态切换**（否则用户不知道当前是哪个动作）。 */
    private fun iconFor(id: Id, playing: Boolean): Icon = when (id) {
        Id.PREVIOUS -> Icon.PREVIOUS
        Id.NEXT -> Icon.NEXT
        // `PROGRESS` 不会走到这里（它不是按钮），给 PLAY 只是为了让 `when` 完备。
        Id.TOGGLE, Id.PROGRESS -> if (playing) Icon.PAUSE else Icon.PLAY
    }

    /** 三个按钮的 id 列表；窄词岛放不下时退化为"只显示播放/暂停"。 */
    private fun buttonIds(width: Float): List<Id> {
        val all = listOf(Id.PREVIOUS, Id.TOGGLE, Id.NEXT)
        val total = all.size * BUTTON_SIZE + (all.size - 1) * BUTTON_SPACING
        // 边界情形（recon/13 §10.4）：词岛窄于控件行 → 只显示 showToggle。
        // 为什么不是"按比例缩小"：缩到 16 px 下限后图标只剩 8 px，比"少一个按钮"更难用；
        // 而播放/暂停是三者里唯一**不可替代**的（上一首/下一首在播放器里更好找）。
        return if (total <= width - STRIP_PADDING * 2) all else listOf(Id.TOGGLE)
    }

    /** 横向居中排一行按钮。 */
    private fun buttonRow(y: Float, width: Float, ids: List<Id>): List<Button> {
        val total = ids.size * BUTTON_SIZE + (ids.size - 1) * BUTTON_SPACING
        val left = (width - total) / 2f
        return ids.mapIndexed { index, id ->
            Button(id, Rect(left + index * (BUTTON_SIZE + BUTTON_SPACING), y, BUTTON_SIZE, BUTTON_SIZE))
        }
    }

    /**
     * 画进度条：`左标签 = 已播时间`、`右标签 = 总时长`、中间是轨道 + 已播填充 + 圆点。
     *
     * ## 总时长从哪来（**这条曾经是本项目的最大缺口，现已闭合**）
     *
     * 宿主 API 里**没有时长**：`WorkshopApi$Playback` 只有 `changeExclusive/pause/
     * play/previous/next/seekTo`（见 `recon/_javap_api_dump.txt`），
     * `PlaybackExtensionPoint` 的回调与 `TimelineSnapshot` 也都不含时长。
     * 0.8.9 是**自己读本地音频文件**取的（`platform/LocalTrackMetadata.kt` +
     * Jaudiotagger）。本插件走同一条路，实现见
     * `platform/LocalTrackDuration.kt` + `host/TrackDurationLoader.kt`，
     * 决策过程与三个候选的逐个评估见 `recon/26` §2。
     *
     * `durationMs = 0` 表示**未知**（非本地文件 / 格式不支持 / 尚未读完），此时：
     *
     * - 轨道照画（用户能看到进度条**存在**）
     * - 不画已播填充与圆点（画了就是**假信息**：0/0 的填充比没有填充更误导）
     * - 右标签显示 `--:--`
     * - **不响应点击/拖动**（分母未知，无法把 x 映射成毫秒 —— 见 [seekTargetAt]）
     *
     * @param dragging 是否正在拖动。为 `true` 时**强制画出填充与圆点** ——
     *   拖动中用户已经明确表达了"我要跳到这个位置"，此时预览值一定来自用户的手，
     *   不是外推的假信息。同时把圆点画大一圈，给出"抓住了"的反馈。
     * @param hovering 鼠标是否在**进度条那一行**上。**只对 [ControlsConfig.GLOW] 形态有意义**
     *   —— 该形态下圆点"不恒显示"（用户诉求 ⑭ 原文），只在拖动中或悬停时出现。
     *   经典形态忽略它（圆点恒显示，保持改动前行为）。
     * @param cfg 控件配置。**必须与 `layout()` 用的是同一份** —— 形态（`progressStyle`）
     *   从它取，不从字段取（本类**无状态**：所有几何与样式都由调用方逐次传入，
     *   见类 KDoc 的"为什么是 public / 为什么不存字段"）。
     */
    private fun paintProgress(
        g2: Graphics2D,
        rect: Rect,
        positionMs: Long,
        durationMs: Long,
        accent: Color,
        dragging: Boolean,
        cfg: ControlsConfig,
        hovering: Boolean = false,
    ) {
        val known = durationMs > 0
        g2.font = LABEL_FONT
        val metrics = g2.fontMetrics
        val left = timeLabel(positionMs)
        val right = if (known) timeLabel(durationMs) else UNKNOWN_TIME

        // 轨道几何走 trackRect —— 与 seekTargetAt 的换算**共用同一份**，
        // 这样"看到的位置"与"点到的位置"必然一致（见 trackRect 的 KDoc）。
        val track = trackRect(rect, metrics, positionMs, durationMs)
        val barX = track.x
        val barY = track.y
        val barWidth = track.width

        g2.color = TRACK_COLOR
        g2.fill(roundBar(barX, barY, barWidth))

        if (known) {
            val ratio = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
            val filled = (barWidth * ratio).toFloat()
            if (filled > 0.5f) {
                g2.paint = fillPaintFor(cfg, barX, barY, filled, accent)
                try {
                    g2.fill(roundBar(barX, barY, filled))
                } finally {
                    // ★ 笔刷用完必须还原成纯色 —— 否则**同一帧后续的 fill**
                    //   （圆点、以及调用方之后的绘制）会继续用这条渐变。
                    //   与 `create()/dispose()` 同一条纪律：改了共享状态就还原。
                    g2.paint = accent
                }
            }
            // 圆点画在填充的右端。位置 clamp 到轨道内，避免"进度条走完后圆点飘出边界"。
            val knobX = (barX + filled).coerceIn(barX, barX + barWidth)
            // 拖动中把圆点放大 —— 这是"抓住了"的唯一可见反馈
            // （0.8.9 用 `isEnabled` 变手形光标，但拖动中光标已被设为 MOVE/手形，
            // 分不出"已抓住"与"只是悬停"）。
            val knobRadius = if (dragging) KNOB_RADIUS * 1.4f else KNOB_RADIUS
            // ★ 2026-09-27 诉求 ⑭：GLOW 形态下小圆**不恒显示** ——
            //   只在「拖动中」或「鼠标在这一行上」时出现。
            //
            //   为什么是这两个条件（而不是"只在拖动中"）：条上有一枚圆点，
            //   用户才知道"这里可以拖"。若只在拖动中出现，用户第一次根本
            //   找不到拖拽点（光标变手形要**悬停之后**才发生，那时已来不及提示）。
            //   ⇒ 悬停即显 = 「这里可以拖」；拖动即显 = 「已经抓住了」。
            //
            //   ⚠️ 经典形态**不看这个判据**（`!cfg.glowProgress` 恒真）——
            //      保持改动前"圆点恒显示"的行为，老用户升级后画面逐像素不变。
            val knobVisible = progressKnobVisible(cfg.glowProgress, dragging, hovering)
            if (knobVisible) {
                g2.color = accent
                g2.fillOval(
                    (knobX - knobRadius).toInt(),
                    (rect.centerY - knobRadius).toInt(),
                    (knobRadius * 2f).toInt(),
                    (knobRadius * 2f).toInt(),
                )
            }
        }

        // 时间标签：基线按"视觉居中"公式对齐到进度条行的中线。
        // ★★★ 2026-10-08（A4 · 最后复审补漏）：★ 它此前是**复刻**（注释自己就写着
        //   「与歌词行用的是**同一个公式**」）—— 现改指那唯一的出口
        //   [dev.cdl.window.lyricBaselineY]（同包，无需 import）。
        //   ★ 这是 A1 契约的**第 7 处** —— A1 当时的 `grep` 判据只盯
        //   `layout.` / `subLayout.` 两种变量名 ⇒ **漏了本处**（这里用的是 `metrics.`）。
        //   ★ 见 `migration/108`。★ 它**探针断言不了**（`paintProgress` 是 private）⇒ 靠结构性判据守。
        g2.color = LABEL_COLOR
        val baseline = lyricBaselineY(rect.centerY, metrics.ascent.toFloat(), metrics.descent.toFloat())
        g2.drawString(left, rect.x, baseline)
        g2.drawString(right, rect.x + rect.width - metrics.stringWidth(right), baseline)
    }

    private fun roundBar(x: Float, y: Float, width: Float): RoundRectangle2D.Float =
        RoundRectangle2D.Float(x, y, width, BAR_THICKNESS, BAR_THICKNESS, BAR_THICKNESS)

    /**
     * 进度条**圆点是否可见** —— 用户诉求 ⑭「**小圆不恒显示**」的唯一判据。
     *
     * ## 真值表（四格，`CLASSIC` 两格恒真）
     *
     * | 形态 | 拖动中 | 悬停中 | 结果 |
     * |---|---|---|---|
     * | `CLASSIC` | 任意 | 任意 | **恒显示**（= 改动前行为，逐像素不变） |
     * | `GLOW` | 是 | 任意 | 显示（"已经抓住了"） |
     * | `GLOW` | 否 | 是 | 显示（"这里可以拖"） |
     * | `GLOW` | 否 | 否 | **隐藏**（"不恒显示"的落地） |
     *
     * ## 为什么是"拖动 **或** 悬停"，而不是"仅在拖动中"
     *
     * 条上有一枚圆点，用户才知道"这里可以拖"。若只在拖动中出现，用户第一次
     * 根本找不到拖拽点 —— 而"光标变手形"要**悬停之后**才发生，那时提示已经晚了
     *（用户的手已经停在那儿、但屏幕上没有任何东西告诉他可以按下去）。
     * ⇒ 悬停即显示"这里可拖"，拖动即显示"已经抓住"。
     *
     * ## 为什么公开（与 [progressFillPaint] 同一理由）
     *
     * 这是⑭需求里"小圆不恒显示"的**唯一判据**，它错了不抛异常，只表现为
     * "选了流光但圆点照样常显"（= 需求没落地）。探针必须能直接喂真值表。
     */
    public fun progressKnobVisible(glow: Boolean, dragging: Boolean, hovering: Boolean): Boolean =
        // 经典档：恒真（老用户升级后画面逐像素不变）。
        !glow || dragging || hovering

    /**
     * **悬停高亮是否仍然有效** —— `hoveredControl` 这个字段的**收敛判据**。
     *
     * ★★★ **2026-10-08（U9）** 新增。用户报障：「**流光样式下的播放条仍很显示小圆球**」。
     *
     * ## 为什么需要它（根因）
     *
     * [progressKnobVisible] 的真值表**本来是对的** —— 问题出在喂给它的 `hovering`
     * **入参**上：`hovering = hoveredControl == PROGRESS`，而 `hoveredControl`
     * 在"收起 / 光标离岛"时**没人清空**（唯一写点只挂在鼠标事件路径上，
     * 而 `onHoverTick` 的常规分支走的是状态机、不经过那里）⇒ 字段**陈旧保持**，
     * 于是"按需显示"退化成"总是显示"。
     *
     * ## 为什么抽成纯函数（而不是在 `IslandWindow` 里写个 `if`）
     *
     * ★ 这条判据**就是 U9 的全部内容**。留在窗口类里意味着：
     * ① 探针断言不到它（`IslandWindow` 需要真窗口）⇒ **修复没有护栏**；
     * ② 下一个读代码的人看到 `if (!expanded || !inside)` 不会知道它是 U9 的修复点。
     * ⇒ 与 `effectiveTranslationGapPx`（U2）/ `pillOutlineFor`（U10）**同一条做法**：
     * 把"必须离线验证"的那一层提成公开纯函数。
     *
     * ## 判据（两个条件，取或）
     *
     * | 条件 | 语义 | 与谁同源 |
     * |---|---|---|
     * | `!expanded` | 控件条已收起 ⇒ **没有可悬停的控件** | 与 `updateHoveredControl` 的 `hover.expanded` 门控**同一个量** |
     * | `!inside` | 光标已离开岛体 | ★ **必须独立**：离开延迟（`exitDelayMs`）期间 `expanded` 仍为 true |
     *
     * ⚠️ 第二条不能省。省了会出现：光标已移到岛外、`exitDelayMs` 还没走完，
     * 此时控件仍"以为被指着"⇒ 圆点在岛外悬停期间继续显示（用户会看到
     * "鼠标已经离开，圆点还亮着"）。
     *
     * @param expanded 悬停是否处于展开态（`HoverState.expanded`）
     * @param inside 光标是否落在岛体内（`lastInside`）
     * @return `true` = **保留** 悬停高亮；`false` = 必须清空 `hoveredControl`
     */
    public fun hoverHighlightKept(expanded: Boolean, inside: Boolean): Boolean = expanded && inside

    /**
     * 已播放段的**填充笔刷** —— `CLASSIC` → 纯色；`GLOW` → 羽化高光（线性渐变）。
     *
     * ## 判据只有一处（本项目纪律：同一条规则只出现一次）
     *
     * 这是"用不用渐变"的**唯一判定点**。`paintProgress` 只管"拿到了什么笔刷就 fill 什么"，
     * 不自己判形态 —— 否则将来加第三档时要改两个地方（`MotionQuality` 记过同类教训）。
     *
     * ## 羽化方向与长度
     *
     * 渐变沿**水平轴**（`barX → barX + width`）：
     *
     * ```
     * 尾部（左端）纯透明 ────────▶ 头部（右端）纯实色
     *    ↑ 光带的"尾迹"                 ↑ 播放头，最亮
     * ```
     *
     * 方向不可反过来：进度条右端才是"当前播放位置"，把亮端放左边会让**播放头变暗**，
     * 与"高光推进"的语义相反（用户诉求原文「**平时羽化高光推进**」）。
     *
     * 尾迹长度取 **`filled × GLOW_TAIL_RATIO`**（相对**已播放长度**，不是条宽）：
     * 这样"已播放 5%"时尾迹也只有整条的 1.2% —— 若按条宽取固定比例，
     * 刚开始播放时整条已播放段会**全是渐变**、几乎看不见（右端还没走到实色）。
     *
     * ## 零宽防护（`MIN_FEATHER` 那类坑的横向版本）
     *
     * `LinearGradientPaint` 的**两端点必须不同**。已播放段极短时
     * `width - tail` 与 `width` 会因浮点相等 ⇒ 退化。故：
     * `filled` 不足 [GLOW_MIN_FILLED] 或尾迹 >= 全长时 **退回纯色** ——
     * "画成实心"远好于"抛异常/画出不可预期的东西"。
     */
    private fun fillPaintFor(cfg: ControlsConfig, barX: Float, barY: Float, width: Float, accent: Color): java.awt.Paint =
        progressFillPaint(cfg.glowProgress, barX, barY, width, accent)

    /**
     * 已播放段的**填充笔刷** —— `CLASSIC` → 纯色；`GLOW` → 羽化高光（线性渐变）。
     *
     * ## 为什么是**公开纯函数**（而不是 `private` 的 [fillPaintFor]）
     *
     * `probe` 是独立编译单元，看不到 `internal` / `private`。而"某档用不用渐变"
     * 恰恰是**必须离线验证**的东西 —— 它错了不抛异常，只表现为
     * "选了流光但看起来还是实心"（或反之，经典档被静默改成渐变 = **老用户画面变了**）。
     *
     * 这与 [labelFont] / [roundBar] 的暴露理由完全一致：
     * **拿不到的常量/函数，探针就只能自己拼一份"看起来一样"的 —— 那正好造出
     * 第二份实现，探针也就失去了意义**（本项目纪律，见 [ControlsConfig] 的类 KDoc）。
     *
     * ## 判据只有一处（本项目纪律：同一条规则只出现一次）
     *
     * 这是"用不用渐变"的**唯一判定点**。`paintProgress` 只管"拿到了什么笔刷就 fill 什么"，
     * 不自己判形态 —— 否则将来加第三档时要改两个地方（`MotionQuality` 记过同类教训）。
     *
     * ## 羽化方向与长度
     *
     * 渐变沿**水平轴**（`barX → barX + width`）：
     *
     * ```
     * 尾部（左端）纯透明 ────────▶ 头部（右端）纯实色
     *    ↑ 光带的"尾迹"                 ↑ 播放头，最亮
     * ```
     *
     * 方向不可反过来：进度条右端才是"当前播放位置"，把亮端放左边会让**播放头变暗**，
     * 与"高光推进"的语义相反（用户诉求原文「**平时羽化高光推进**」）。
     *
     * 尾迹长度取 **`width × GLOW_TAIL_RATIO`**（`width` = **已播放长度**，不是整条轨道宽）：
     * 这样"已播放 5%"时尾迹也只有已播段的 35% —— 若按**轨道宽**取固定比例，
     * 刚开始播放时整条已播放段会**全是渐变**、几乎看不见（右端还没走到实色）。
     *
     * ## 零宽防护（`MIN_FEATHER` 那类坑的横向版本）
     *
     * `LinearGradientPaint` 的**两端点必须不同**（相等会抛 `IllegalArgumentException`）。
     * 已播放段极短、或 `GLOW_TAIL_RATIO` 被调成 0 时都可能退化。故：
     * `width` 不足 [GLOW_MIN_FILLED] 或尾迹 >= 全长时 **退回纯色** ——
     * "画成实心"远好于"抛异常"（而异常会让整条控件绘制被 `runCatching` 跳过 ⇒
     * 用户看到的是"控件整条消失"）。
     *
     * @param glow `true` = 流光档（渐变）；`false` = 经典档（纯色，逐像素等于改动前）
     * @param barX 已播放段左端（绝对 x）
     * @param barY 轨道纵向中心（渐变轴的一个端点，纵向分量对水平渐变无影响）
     * @param width **已播放长度**（不是整条轨道宽）
     */
    public fun progressFillPaint(glow: Boolean, barX: Float, barY: Float, width: Float, accent: Color): java.awt.Paint {
        // 经典形态：纯色（= 改动前的行为，逐像素不变）。
        if (!glow) return accent
        val tail = width * GLOW_TAIL_RATIO
        // 退化防护：太短或尾迹吃掉全长 ⇒ 纯色。
        if (width < GLOW_MIN_FILLED || tail <= 0f || tail >= width) return accent
        return java.awt.LinearGradientPaint(
            // 起点在已播放段左端、终点在播放头（= 右端）。
            // 两端点差值恒为 `width`，上面已保证 `width >= GLOW_MIN_FILLED > 0`。
            java.awt.geom.Point2D.Float(barX, barY),
            java.awt.geom.Point2D.Float(barX + width, barY),
            floatArrayOf(0f, 1f),
            // 分色：0 = 全透明（尾迹尽头的"无光"）→ 1 = 完全不透明的 accent。
            // ⚠️ **只改 alpha，不改色相** —— 改色相会让"高光"变成"另一种颜色"，
            //    与 `spectrum.progress_tint` 的语义撞车（那是"用颜色表示进度"）。
            arrayOf(
                Color(accent.red, accent.green, accent.blue, 0),
                accent,
            ),
        )
    }

    /**
     * 手绘图标。
     *
     * ## 为什么不用 `JButton` + 图标字体（0.8.9 的做法）
     *
     * 0.8.9 用 `JButton(PlaybackIcon.PREVIOUS)` —— 它也是手绘的 `Icon`，
     * 但**依赖 Swing 的组件层**：每个按钮是一个真实组件，要参与布局、要设
     * `isContentAreaFilled = false`、`isBorderPainted = false` 才能去掉默认外观，
     * 而且 `JButton` 会吃掉鼠标事件（词岛的拖动就会在按钮上失效）。
     * 本插件的内容面板是**整块自绘**的（见 `IslandPanel` 的类 KDoc），
     * 混入组件会破坏"一个 `paintComponent` 画完所有东西"的结构。
     *
     * 故这里直接画：三个图标在 18×18 的**设计网格**上定义（坐标取自 0.8.9 的
     * `PlaybackIcon.paintIcon`，与用户熟悉的形状一致），再按 [ICON_SIZE]/18 缩放居中。
     * 好处是命中域由 [Rect] 单独控制 —— 图标画多大与按钮点多大**互不影响**。
     */
    private fun drawIcon(g2: Graphics2D, icon: Icon, cx: Float, cy: Float, size: Float) {
        val scale = size / ICON_GRID
        // 设计网格 0..18 的中心是 9，故减去 9 再缩放，得到以 (cx, cy) 为中心的形状。
        fun gx(value: Float): Float = cx + (value - ICON_GRID / 2f) * scale
        fun gy(value: Float): Float = cy + (value - ICON_GRID / 2f) * scale

        g2.color = ICON_COLOR
        when (icon) {
            Icon.PAUSE -> {
                g2.fill(RoundRectangle2D.Float(gx(4f), gy(3f), 4f * scale, 12f * scale, 1f * scale, 1f * scale))
                g2.fill(RoundRectangle2D.Float(gx(11f), gy(3f), 4f * scale, 12f * scale, 1f * scale, 1f * scale))
            }

            Icon.PLAY -> g2.fillPolygon(
                intArrayOf(gx(5f).toInt(), gx(5f).toInt(), gx(15f).toInt()),
                intArrayOf(gy(3f).toInt(), gy(15f).toInt(), gy(9f).toInt()),
                3,
            )

            Icon.PREVIOUS -> {
                g2.fill(RoundRectangle2D.Float(gx(3f), gy(3f), 2f * scale, 12f * scale, 1f, 1f))
                g2.fillPolygon(
                    intArrayOf(gx(15f).toInt(), gx(15f).toInt(), gx(5f).toInt()),
                    intArrayOf(gy(3f).toInt(), gy(15f).toInt(), gy(9f).toInt()),
                    3,
                )
            }

            Icon.NEXT -> {
                g2.fill(RoundRectangle2D.Float(gx(13f), gy(3f), 2f * scale, 12f * scale, 1f, 1f))
                g2.fillPolygon(
                    intArrayOf(gx(3f).toInt(), gx(3f).toInt(), gx(13f).toInt()),
                    intArrayOf(gy(3f).toInt(), gy(15f).toInt(), gy(9f).toInt()),
                    3,
                )
            }
        }
    }

    /** 毫秒 → `分:秒`。负数按 0 处理（时间轴外推在 seek 前后可能短暂给负值）。 */
    fun timeLabel(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        return "%d:%02d".format(safe / 60_000, safe / 1000 % 60)
    }

    /**
     * 常量区。**必须是 `public`** —— 与 [ControlsConfig] 同一理由：
     * `probe` 是独立的 Kotlin 编译单元，看不到 `main` 的 `internal`
     * （Kotlin 的 `internal` 按模块可见，而模块 = 一次编译）。
     * 而 [LABEL_FONT] 恰恰是**必须离线验证**的东西：探针要拿它造一份 `FontMetrics`
     * 去做 seek 换算断言，若拿不到就只能自己拼一个"看起来一样"的字体 ——
     * 那正好复现了本节要避免的"两份字体不同步"缺陷，探针也就失去了意义。
     *
     * 暴露的是常量（编译期可内联的值）与一个字体，不是可变量，故不构成额外的
     * 封装泄漏面。
     */
    public companion object {
        /** 控件条外侧留白。取 10 而不是 8：圆角形态下进度条要躲开底角的收角（见 paint 的说明）。 */
        const val STRIP_PADDING = 10f

        /** 按钮边长（`recon/13` §10.4 的 `controls.buttonSize` 默认值）。 */
        const val BUTTON_SIZE = 30f

        /** 按钮间距（`recon/13` §12.0 **D-e 用户拍板**：26，贴齐 0.8.9 的 56 px 中心距）。 */
        const val BUTTON_SPACING = 26f

        /** 图标在设计网格（18×18）里的边长。 */
        const val ICON_SIZE = 16f

        /** 图标设计网格的边长。坐标取自 0.8.9 的 `PlaybackIcon`。 */
        const val ICON_GRID = 18f

        /** 进度条行高（含时间标签与轨道）。 */
        const val PROGRESS_HEIGHT = 22f

        /** 进度条与按钮行之间的行距。 */
        const val ROW_GAP = 6f

        /** 进度条左右留白。取 16 是为了避开圆角收角（默认圆角 ≈ 短边 60%）。 */
        const val PROGRESS_SIDE_PADDING = 16f

        /** 进度条轨道粗细。 */
        const val BAR_THICKNESS = 4f

        /** 时间标签与轨道之间的间隙。 */
        const val LABEL_GAP = 6f

        /** 轨道最小可用宽度（极端窄岛下的兜底，避免负数宽度画崩）。 */
        const val MIN_BAR_WIDTH = 8f

        /** 进度条圆点半径。 */
        const val KNOB_RADIUS = 5f

        /**
         * `GLOW` 形态下羽化尾迹占**已播放长度**的比例。
         *
         * `0.35` 的取法：尾迹要"看得出是渐隐"但不吃掉大半条（吃掉会让
         * "播放到了哪里"这个读数变得模糊 —— 而进度条首先是**读数**）。
         * 取 0.5 时已播放段的中间就已经只是半透明，观感上像"条断了"。
         */
        const val GLOW_TAIL_RATIO = 0.35f

        /**
         * `GLOW` 形态下**低于这个宽度就不画渐变**（退化防护）。
         *
         * 取值 = [BAR_THICKNESS] × 2 = 8 px：比这更短的已播放段，
         * 渐变带只有 2.8 px 宽，肉眼看不出渐隐，却要付一次 `LinearGradientPaint`
         * 分配 —— 得不偿失。且能顺带避开"两端点浮点相等"的退化。
         */
        const val GLOW_MIN_FILLED = 8f

        /** 悬停底色的圆角。 */
        const val HOVER_CORNER = 8f

        /** 总时长未知时的占位文本。 */
        const val UNKNOWN_TIME = "--:--"

        /**
         * 时间标签字体。
         *
         * 用**逻辑字体族** `SansSerif` 而不是物理族名：逻辑族由 AWT 在所有平台上映射，
         * 不会落到"字体不存在 → 静默换 Dialog"的路径（同 `RenderCache.DEFAULT_FONT` 的理由）。
         * 这与"图标不依赖字体"并不矛盾：图标用**形状**画（笔画），而时间标签必须**是文字** ——
         * 用数字形状手绘时间标签是另一种意义上的过度设计。
         */
        val LABEL_FONT: Font = Font(Font.SANS_SERIF, Font.PLAIN, 10)

        /**
         * 时间标签字体 —— 供 `IslandWindow` 与探针在鼠标事件里量轨道几何用。
         *
         * ## 为什么必须被外部读到（而不是让调用方自己拼一个"看起来一样"的字体）
         *
         * [trackRect] 要一份 `FontMetrics` 才能算出时间标签占宽，而 seek 的换算
         * 必须与绘制用**同一份**字体。若调用方自己 `Font(SANS_SERIF, PLAIN, 10)`，
         * 两者一旦不同步（改了这里忘了那里），就会表现为"拖动进度条总是差一点点" ——
         * 且差值随时间标签宽度变化，极难定位。
         *
         * 直接作为 [LABEL_FONT] 的**公开别名**暴露，而不是"另存一份副本"：
         * 两份变量即使初值相同，日后也可能只改一处 —— 那正是本注释要防的事。
         */
        fun labelFont(): Font = LABEL_FONT

        /** 控件图标色（`recon/13` §10.4 的 `controls.iconColor` 默认值 `#DFE4ED`）。 */
        val ICON_COLOR: Color = Color(223, 228, 237)

        /** 时间标签色 —— 比图标暗一档，避免与歌词抢注意力。 */
        val LABEL_COLOR: Color = Color(147, 156, 174)

        /** 进度条轨道色。 */
        val TRACK_COLOR: Color = Color(72, 77, 89)

        /** 悬停底色（`recon/13` §10.4 的 `controls.hoverColor` 默认值 `#FFFFFF40`）。 */
        val HOVER_BACKDROP: Color = Color(255, 255, 255, 0x40)
    }
}
