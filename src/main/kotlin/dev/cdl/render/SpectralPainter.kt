// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 频谱**绘制入口**（不负责采集）。
 *
 * ## 职责边界（刻意划得很窄）
 *
 * 本对象只回答一个问题：**「给定一组 0..1 的频段幅度，怎么画好看」**。
 * 它**不**做这些事：
 *
 * - **不采集音频**。数据从哪来（宿主回调 / FFT / 播放器 API）属于数据层。
 *   这里只收一个 `FloatArray` —— 依赖方向因此是单向的，本文件
 *   不需要 import 任何播放或采集相关类型。
 * - **不管理帧率**。调用方（词岛的 `paintComponent`）决定何时调用。
 * - **不做布局**。`bounds` 由调用方给定，本对象不参与「频谱画在哪」的决策。
 *   这很重要：频谱位置取决于封面是否显示、控件是否悬停等**窗口级**状态，
 *   那些状态本对象不该知道。
 *
 * ## 为什么必须做「平滑 / 回落」——这是本类存在的主要理由
 *
 * 宿主给的是**离散采样**（每次回调一组值），而屏幕是**连续渲染**（60 fps）。
 * 直接把最新一组值画出来，会在两次采样之间**完全静止**、采样到来时**瞬间跳变** ——
 * 那是「每秒闪几次的柱状图」，不是频谱。
 *
 * 本类因此在 [SpectrumCache] 里保存**上一帧的显示值**，每次绘制时向目标值
 * **渐进逼近**。两个方向用不同的速率：
 *
 * | 方向 | 行为 | 为什么 |
 * |---|---|---|
 * | **上升** | 立即（或极快） | 鼓点/瞬态是**信息**。若上升也做平滑，鼓点会被抹圆，频谱看起来「软绵绵」、与音乐不同步 |
 * | **回落** | 缓慢 | 人眼对衰减的感知是连续的。若回落也瞬时，柱子在无信号时**瞬间归零**，看起来像闪烁/掉帧 |
 *
 * 这个「快升慢降」的不对称是电平表（VU meter）沿用几十年的做法，
 * 不是本类发明的 —— 但值得写明，因为「为什么两个方向速率不同」是
 * 一个光看代码会觉得「写成一样不就行了」的取舍。
 *
 * ## 条形风格与实现选择
 *
 * 需求是「圆角条形」（`RoundRectangle2D`）或「带 `BasicStroke` 的圆头折线」。
 * 本类**两种都实现了**，由 [Style] 选择 —— 这不是过度设计，而是因为两者的
 * 代价差异很大且取决于场景：
 *
 * - [Style.BARS]：每个频段一个 `RoundRectangle2D`。**段数可控**（每个矩形一个），
 *   在小尺寸下（词岛高度 88px，频谱通常只有 20–40px 高）视觉清晰。
 * - [Style.WAVE]：一条圆头折线穿过各频段值。段数是 `2 × (n-1)` 条线段，
 *   但**只有一次 `draw` 调用**。频段数多（>= 32）时，它的绘制成本
 *   比逐条画矩形更低（一次吞吐 vs n 次状态切换）。
 *
 * 默认用 [Style.BARS]：词岛的频谱是**装饰性元素**而非精确仪表，
 * 条形的可读性在小尺寸下更好。
 *
 * ## 实测量化
 *
 * `RoundRectangle2D` 的构造与填充在 32 条规模下是**每帧几十微秒**量级 ——
 * 与歌词绘制（0.045 ms）同阶。这是可接受的：两者相加仍远低于 16.6 ms 预算。
 * 但**仍要把 `Color` 与 `Stroke` 缓存起来**（见 [RenderCache]），
 * 因为 `Color` 的构造在这里是每帧 32+ 次 —— 那才是真正显眼的分配热点。
 */
internal object SpectralPainter {

    /** 条形风格。见类 KDoc 关于两种实现代价的对比。 */
    enum class Style {
        /** 逐条圆角矩形。默认。 */
        BARS,

        /** 一条圆头折线。频段多时更省。 */
        WAVE,
    }

    /**
     * 平滑状态容器 —— **调用方持有一份，跨帧传入**。
     *
     * ## 为什么状态由调用方持有，而不是本对象内部存
     *
     * 因为「同一组频段」可能被画在**多个地方**（例如主词岛 + 一个独立的
     * 频谱浮窗），各自需要独立的平滑状态。若状态藏在 `SpectralPainter` 内部，
     * 两个调用点会共享状态，表现为「两个频谱互相干扰对方的回落」。
     *
     * 而且内部状态会让本对象**不再是幂等的** —— 同样的输入画两次结果不同，
     * 这在「同一帧被重绘两次」（Swing 的 `paint` 确实可能被连续调用）时会
     * 造成柱高莫名衰减。
     *
     * ## 为什么 `FloatArray` 而不是 `List<Float>`
     *
     * 这是每帧读写的热数据。`FloatArray` 无装箱、无迭代器分配，
     * 且天然表达「固定长度的频段数」—— 频段数变化时整份数组重建即可。
     */
    class SpectrumCache {
        /** 上次绘制的显示值（已平滑）。长度 0 表示尚未初始化。 */
        internal var displayed: FloatArray = EMPTY

        /** 上次绘制的时间戳（纳秒，来自调用方传入）。用于让回落速率**与帧率无关**。 */
        internal var lastNanos: Long = 0L

        /** 缓存键：上一次的频段数 + 风格。变化时重置显示值，避免两套数据互相污染。 */
        internal var lastSignature: Int = 0

        /**
         * **峰值保持**的每频段高点（长度 0 = 未启用或未初始化）。
         *
         * 与 [displayed] 是**两套独立状态**：`displayed` 是"本帧柱高"（快升慢降），
         * 而本数组记的是"最近出现过的最高点"，按**固定速率**下落 ——
         * 于是它能"挂"在那里一段时间，用户看得见刚才那一击有多高。
         * 见 [updatePeaks]。
         */
        internal var peaks: FloatArray = EMPTY

        internal companion object {
            val EMPTY = FloatArray(0)
        }
    }

    /**
     * 绘制频谱。
     *
     * @param g 绘制上下文。**不改它的 transform**；`color` / `stroke` / `clip` 会被临时改，
     *   但都在 `finally` 里还原成进入本方法时的值（`clip` 见 ④ 进度染色那段）
     * @param bands 各频段幅度，`0f..1f`。越界值会被 clamp。
     *   - **允许空数组**：直接返回不画。这不是异常状态 —— 采集还没开始时就是这样
     * @param bounds 绘制区域（左上角与尺寸）。**只用于布局，不做裁剪** ——
     *   裁剪是调用方的责任（它知道词岛的圆角形状）
     * @param color 频谱颜色（打包 ARGB）
     * @param cache 平滑状态容器。见 [SpectrumCache] 关于「为什么由调用方持有」
     * @param style 条形风格，见 [Style]
     * @param nowNanos 当前时刻（纳秒，任意单调时钟基准）。
     *   ⚠️ 传入而非内部取 `System.nanoTime()`：让绘制**可测试、可重放**，
     *   且让「同一帧内的多次绘制」共用同一时刻（否则重绘两次会各推进一次回落）。
     *   传 `0L` 表示「不做时间推进」（只按固定比例逼近），用于首批帧或测试
     * @param playing 播放中 = `true`。**暂停时改用极短的回落时间常数，且归零后不画** ——
     *   见 [smooth] 与 [PAUSED_RELEASE_TAU_SECONDS]。默认 `true` 是为了让"不关心播放态"
     *   的调用点（探针、将来的独立频谱浮窗）行为与改动前完全一致。
     * @param paintOverride 覆盖填充用的 `Paint`。传 `null`（默认）时用 [color] 构造纯色 ——
     *   与改动前完全一致。**背景化频谱**传一个纵向渐变，让柱子向上淡出（见
     *   `IslandWindow.drawBackgroundSpectrum` 的观感取舍）。之所以做成参数而不是
     *   在内部按场景分支：本对象的职责是"给定量怎么画好看"，"用什么笔刷"属于调用方的表达。
     * @param peakHold 是否画**峰值保持**的"帽"（`animation.quality = ULTRA` 的独有高开销效果）。
     *   默认 `false` —— 于是"不关心此项"的调用点（探针、将来的独立频谱浮窗）行为与改动前**逐位相同**。
     *   见 [updatePeaks] 与 `paintBars` 里的"帽"那一段。
     * @param bounceScale 「跳动幅度」拉伸倍数（`spectrum.bounce_pct` ÷ 100）。默认 `1f` = 原样。
     *   作用点是**归一化柱高**：`h' = stretchAroundMid(h, bounceScale)` ——
     *   见该函数的 KDoc 关于「为什么不是乘法」的论证。默认值保证"不关心此项"的调用点
     *   （探针、将来的独立频谱浮窗）行为与改动前**逐位相同**。
     * @param progressFraction 已播放比例（`0f..1f`），或 [NO_PROGRESS]（默认）= **不染色**。
     *   **`0f` 与 [NO_PROGRESS] 语义不同**：前者是"确实一句都没播"（整条都是未播放色），
     *   后者是"**不知道**播到哪了"（保持原样）—— 见 [playbackProgress] 的 KDoc。
     * @param progressPaintOverride 已播放那一段用的笔刷。`null`（默认）时染色不生效
     *   （重画一遍的结果与本色相同）。**与 [paintOverride] 同一个理由**做成参数：
     *   调用方（`IslandWindow`）才掌握"纵向渐变"这个表达，本对象不该自己构造渐变。
     */
    fun paint(
        g: Graphics2D,
        cache: RenderCache,
        bands: FloatArray,
        bounds: Rectangle,
        color: Int,
        state: SpectrumCache,
        style: Style = Style.BARS,
        nowNanos: Long = 0L,
        playing: Boolean = true,
        paintOverride: Paint? = null,
        bounceScale: Float = 1f,
        peakHold: Boolean = false,
        progressFraction: Float = NO_PROGRESS,
        progressPaintOverride: Paint? = null,
        /**
         * **逐柱笔刷工厂**（2026-09-20 引入）—— **已降级为 legacy**。
         *
         * ## ⚠️ 为什么保留而不是删掉
         *
         * 它的**唯一**作用（"让淡出按每根柱子自己的高度算"）已由本对象**自己做掉**了：
         * 笔刷在本地坐标里按「淡出带宽度」缓存，`translate` 由 `fillFadedPiece` 负责。
         * 于是它**不再需要**，且**在本轮之后永远不该再被传**。
         *
         * 保留它只是为了**不让调用方的编译中断**（调用点 `IslandWindow.drawBackgroundSpectrum`
         * 目前仍在传）。它现在的语义是：
         *
         * | [fadeTop] | 本参数 |
         * |---|---|
         * | `true`（默认） | **完全忽略**（本对象用 [SpectrumTipFadeBrushCache] 的笔刷） |
         * | `false` | 生效（那时没有"末端淡出"，调用方想自己画什么就画什么） |
         *
         * ⚠️ **调用方应当直接删掉这个实参** —— 留着它在 `fadeTop = true` 时是**死参数**，
         *    而"传了却不生效"正是本项目最想避免的那种"设置/参数是装饰品"。
         *
         * ★★★ 2026-10-07 **更正（`migration/98` · C-1）**：本段原写
         *    「这一点由 `SpectrumOpacityProbe` 的 **⑧ 组**断言钉住（"`fadeTop = true` 时
         *    工厂**一次都不会被调用**"）」—— ★ **两处都不成立**：
         *    ① ★ `SpectrumOpacityProbe` **只有 ①–⑥ 组，没有 ⑧ 组**
         *       （★ 纪律 74：「注释说这条护栏存在」必须**实际 `grep` 一次**）；
         *    ② ★ 那句话的内容**已被证伪** —— "工厂一次都不会被调用"**只对柱状档成立**，
         *       **折线档（`bar_pitch = 0`）下每帧被调用 1 次**
         *       （见本文件另一处「2026-10-03 更正（㉒ 收口）」段）。
         *    ⇒ ★ **真实护栏 = `SpectrumFadeBandProbe §F`**（**不是** `SpectrumOpacityProbe` ⑧）。
         */
        paintForBar: ((topY: Float, bottomY: Float, cornerRadiusPx: Float) -> Paint)? = null,
        /**
         * **已播放段**的填充色（打包 ARGB，**高 8 位就是这一段的有效 alpha**）。
         *
         * ## ★★ 2026-09-22：为什么必须有它（用户报「滑杆到 0 还有差别」的正因）
         *
         * ### 改动前：进度染色层被**画成了未播放色** —— 于是色差恒不可见
         *
         * 改动前 `paintBars` 在每根柱子 `fill` **之前**无条件用
         * `paintForBar(topY, bottomY)` 覆盖 `g.paint`。而那个工厂是**同一个**
         * lambda 服务两趟绘制（底图 / 进度染色），拿的是**未播放色** `packed` ——
         * 于是进度染色层（④）虽然先被设成 `progressPaintOverride`（已唱色），
         * 却在每柱 fill 前被**覆盖回未播放色**。两趟画出来逐像素相同 ⇒
         * 「`spectrum.progress_tint` 开了等于没开」。
         *
         * ### 现在：颜色按**趟**传进来，两趟各自拿自己的色
         *
         * 本参数给的是**已播放段**的色。`fadeTop = true` 时 `paintBars` 会用它
         * 建（缓存的）末端淡出笔刷；`fadeTop = false` 时直接实心填充它。
         *
         * ⚠️ **与 [progressPaintOverride] 的分工**：本参数优先。传了它，
         *    `progressPaintOverride` 在**柱体**上不再生效（它只影响 `Style.WAVE`
         *    与"没传本参数"时的兜底）。
         *
         * ⚠️ 传 `null`（默认）→ 本对象会**尽力**从 [progressPaintOverride] 里
         *    **取出 RGB 与 alpha**（见 `playedArgbFrom`）—— 因为形状由本对象重算，
         *    只有颜色需要调用方给。取不到（比如传了一个自定义 `Paint`）时才退回
         *    "整带笔刷"那条旧路径。
         */
        progressColor: Int? = null,
        /**
         * ★★ **逐柱取色**（`spectrum.color_mode = level`，2026-10-02 新增）——
         * `(柱序号, 该柱振幅) -> 打包 ARGB`；`null`（默认）⇒ **所有柱用同一个 [color]**。
         *
         * ## 为什么是回调，而不是"传一个色数组"
         *
         * · **色数组**要在调用方**先算 count 个色**，而 `count` 是 `paintBars` 内部
         *   由 `spectrumBarCount(...)` 推出来的（**调用方不该重算一遍** —— 两处推导
         *   迟早漂移，本项目最忌讳的形态）。
         * · **回调**则在**循环里**被调用，`i` 与 `values[i]` 都是**绘制侧的真值** ⇒
         *   "画哪根柱"与"取哪个色"**结构上不可能错位**。
         *
         * ## 与 [paintForBar]（legacy）的区别（**不要混用**）
         *
         * | | 本参数 | `paintForBar` |
         * |---|---|---|
         * | 返回值 | **颜色**（Int） | **`Paint` 对象** |
         * | 生效条件 | **恒生效** | ★ 只在 `fadeTop = false` 时（`fadeTop = true` 时被**完全忽略**） |
         * | 与淡出笔刷 | **共存**（颜色喂给淡出笔刷缓存） | 互斥 |
         *
         * ⇒ ★ 本参数**不取代** `paintForBar`，两者服务于不同的事（见各自的 KDoc）。
         *
         * ## ★★ 为什么回调要收 `base`（第三个参数）—— 这是**两趟绘制**逼出来的
         *
         * `paintBars` 被调用**两趟**：① 底图（未播放色）② 进度染色层（**已播放色**，
         * 见 [progressColor]）。两趟的**基色不同**。
         *
         * ⇒ 若回调**只**收 `(index, value)`，它只能拿到一个写死的基色 ⇒
         * **已播放段会用未播放色的派生结果**（`spectrum.progress_tint` 开了等于没开 ——
         * 本项目 2026-09-22 刚修过**同一个**成因的缺陷，见 [progressColor] 的 KDoc）。
         *
         * ★ 故把**基色**也交给回调：`{ i, count, v, base -> levelColorArgb(base, v) }`
         * ⇒ 两趟各自用自己的基色派生，**结构上不可能取错**。
         *
         * ## ★★ 2026-10-11（`cover_flow`）：回调**增加 `count` 形参**
         *
         * ⑰「封面多色流光」的频谱侧要求"**沿柱序**把调色板铺成一条渐变" ——
         * 那需要把 `index` **归一化**，而归一化的分母就是**本帧的实际柱数**。
         *
         * ⚠️ **为什么不能由调用方自己算 `count` 再捕获**：本文件早有一条纪律
         * （见上「为什么是回调，而不是传一个色数组」）—— `count` 是 `paintBars`
         * 内部由 `spectrumBarCountFor(...)` 推出来的，**调用方重算一遍迟早漂移**
         * （本项目最忌讳的形态）。⇒ ★ 正解 = **把绘制侧的真值直接交给回调** ✓
         *
         * ★ 对既有 `level` 档**零影响**：它不使用 `count`（只吃 `value` 与 `base`）✓
         *
         * ## 性能
         *
         * 回调在**逐柱循环**里被调用（最多 `SPECTRUM_MAX_BARS` = 1024 次/帧）。
         * ★ 调用方应传**纯函数**（如 `levelColorArgb` / `spectrumFlowColorAt` 的 lambda 包装）。
         */
        argbForBar: ((index: Int, count: Int, value: Float, base: Int) -> Int)? = null,
        /**
         * ★★★ 2026-10-17（**BUG-226**）：**逐段取色**（柱体沿高度切段）——
         * `null`（默认）⇒ 整柱一个颜色（= 改动前的全部行为，**逐像素不变**）。
         *
         * ## 为什么需要它（用户报障逐字）
         *
         * > 「**插槽小频谱的碎色流光不是背景那种。**」
         *
         * ★ 插槽频谱只有 5 根柱，而 [argbForBar] 是**一维**（按柱序）⇒
         *   每根柱子整根一个色 ⇒ 视觉上是 **5 条竖纹**，与岛体背景的
         *   **二维细胞噪声**（`CamoTexture`）不是一个形态 ✗
         * ⇒ 本回调让**同一根柱子的不同高度**取不同色 ⇒ 柱内出现碎块 ✓
         *
         * ⚠️ 只在 `symmetric = false` 时生效：对称双峰要保证"轴处无缝"，
         *   分段会破坏那条不变量（见 `paintBars` 的对称分支）。
         */
        argbForBarSegment: ((index: Int, count: Int, segIndex: Int, segCount: Int, base: Int) -> Int)? = null,
        /**
         * ★★★ **极坐标布局**（**环形 / 放射**，2026-10-04 新增）——
         * `null`（默认）⇒ **直角坐标**（= 改动前的全部行为，**逐像素不变**）。
         *
         * ## 台账出处与业界对照
         *
         * 见 [SpectrumPolarSpec] 的 KDoc（含 Rainmeter `Roundline` / react-audio-visualizers /
         * productivetoolbox 三处取证）。★ 一句话：**环形 = 有内半径（甜甜圈）· 放射 = 从圆心射出**。
         *
         * ## 实现方式（★ 为什么不复制一份"极坐标版"）
         *
         * 台账原话是「把 `paintBars` 的直角坐标换极坐标（`AffineTransform` 旋转 +
         * 条形沿半径生长）」—— ★ **本实现正是这样**，且更省：
         *
         * 1. 把"槽"（`x0` / `baseY` / `halfSpan` / 淡出带的两端）**整体摆到**
         *    "圆心正上方、从内半径向外"的位置（**循环外算一次**）；
         * 2. 循环内**逐柱 `g.rotate(角度, cx, cy)`** —— 每根柱都以为自己还在直角坐标里
         *    向上长，**实际已转到自己的扇区**。
         *
         * ⇒ ★★ **柱体绘制（圆角 / 淡出 / 逐柱取色 / 对称 / 峰值帽）一行都没改** ——
         * 全部**自动**获得极坐标版本。这是"靠结构复用"，不是"复制一份"。
         *
         * ⚠️ **旋转后 `-y` 就是"向外"** ⇒ [SpectrumPolarSpec.innerRadius] 从圆心量起，
         * 柱子从那里向**上**（= 向外）生长 —— 与 Rainmeter 的 `LineStart = Radius − BarLength`
         * 语义一致。
         */
        polar: SpectrumPolarSpec? = null,
        /**
         * 频谱柱**顶端淡出**（`spectrum.fade_top`，默认 `true` = 保持改动前观感）。
         *
         * **原状**：背景频谱用一条纵向渐变笔刷（向上淡出），是**硬编码**的观感取舍。
         * 用户原话：「注意到频谱高峰顶端有淡化效果，我并没有指定这个效果，请把它改成一个设置项。」
         *
         * ## ★★ 2026-09-22：本参数从**死参数**变成真正的落点
         *
         * ### 原记 vs 实为
         *
         * **原记**（改动前的 KDoc）：它"只影响调用方没传 `paintOverride` 时的默认着色"。
         *
         * **实为**：**它一个字都没被读过** —— 在 `paint` / `paintBars` 里搜不到除声明外的
         * 任何出现。于是"默认着色"那条分支根本不存在：调用方传了 `paintForBar`
         * 就由那个 lambda 决定淡出，没传就实心。`fadeTop` 是一个**纯装饰性的形参**。
         *
         * ### 现在的语义（唯一一条，无分支歧义）
         *
         * | `fadeTop` | 柱体填充 |
         * |---|---|
         * | `true`（默认） | 用**缓存的末端淡出笔刷**（[SpectrumTipFadeBrushCache]）—— 尖端淡、柱身实心 |
         * | `false` | 实心：`g.paint = paintOverride`（有则用）/ `cache.color(color)` |
         *
         * 淡出**只作用于柱体与峰值帽**，不改变 `Style.WAVE`（折线没有"末端"可言）。
         *
         * ⚠️ `true` 时 [paintOverride] 对**柱体**不再生效（淡出笔刷要自己按柱高铺色，
         *   否则"两趟形状一致"这条纪律不成立）。这与改动前的**实际**行为一致
         *   （那时 `paintForBar` 同样覆盖了 `paintOverride`），故不是行为变化。
         */
        fadeTop: Boolean = true,
        /**
         * 频谱**整体透明度**（`spectrum.opacity_pct / 100f`，`0f..1f`，默认 `1f`）。
         *
         * 用 [AlphaComposite] **统一施加**，而不是逐个颜色乘 alpha —— 理由：
         *  · 本方法的绘制路径有多条（本色 / `paintOverride` 渐变 / 进度染色重画），
         *    逐个乘会**漏掉某条**（进度染色那条是"再画一遍"，最容易漏）；
         *  · `AlphaComposite` 对**渐变、圆角矩形、波形路径**一视同仁，不可能漏。
         *  · 一次设置、`finally` 还原，与既有的 `oldPaint` / `oldStroke` 同一套纪律。
         */
        opacity: Float = 1f,
        /** **上下对称双峰**（`spectrum.symmetric`）。见 [symmetryCenterY]。 */
        symmetric: Boolean = false,
        /**
         * 对称中线的**屏幕 y 坐标**（仅 [symmetric] 为真时用）。
         *
         * **由调用方算**（它掌握布局）：开启了副歌词（两行）时在**两行之间**，
         * 单行时以**该行中线**为准。本对象**不猜布局**。
         * 传 [Float.NaN] → 回退到 `bounds` 的纵向中心（兜底，见下方实现）。
         */
        symmetryCenterY: Float = Float.NaN,
        /**
         * 目标**柱间距**（含缝，px）。默认 [SPECTRUM_BAR_PITCH_PX]。
         *
         * `spectrum.bar_pitch_mode = AUTO` 时由调用方传 [spectrumAutoPitch] 的结果 ——
         * **由调用方决定间距、painter 只负责用**，因为"窄岛要变细"这条判据需要
         * 知道"岛有多宽"，而那是调用方的知识。
         */
        barPitchPx: Float = SPECTRUM_BAR_PITCH_PX,
        /**
         * ★★★ 2026-10-11（**U13 · 插槽小频谱**）：**固定条数**。`> 0` ⇒ 原样采用；
         * `<= 0`（默认）⇒ 按 `bounds.width / [barPitchPx]` 推导（**改动前行为**）。
         *
         * ## 为什么必须有它（★ 本参数是两个用户症状的共同根因的修复点）
         *
         * 条数推导 [spectrumBarCount] 带 `minBars = 24`（为**主带**设计的下限）。
         * 右端插槽可用宽只有 **28 px** ⇒ 推导结果被**托底到 24 根** ⇒
         * **24 根塞 28 px** ⇒ 柱宽 0.8 px ⇒ **糊成一片 / 看起来不显示** ✗
         * （用户原话：「**显示的是缩小的主频谱**」「**不显示**」）
         *
         * ⇒ ★ 插槽小频谱传 `forceBarCount = dev.cdl.render.slotSideBarCount()`（**5**）
         *    ⇒ **恒 5 根**，与宽度无关 ✓
         * ★ 判据唯一出口 = `spectrumBarCountFor`（`SpectrumGeometry.kt`，探针可断言）✓
         */
        forceBarCount: Int = 0,
        /**
         * 频谱柱的**端头形状**（`spectrum.bar_corner`：圆角 / 方角）。
         *
         * ## ★★ 2026-09-23：用户第 ④ 条 —— 「频谱条的样式提供圆角和方角」
         *
         * 语义与优先级见 [SpectrumBarCorner] 与 [spectrumBarCornerPx]：
         * · 默认 [SpectrumBarCorner.ROUND] = **改动前行为**（零回归，故本参数可以带默认值）；
         * · 手动间距 = 0（实心档）时**恒为方角**，本参数被忽略。
         *
         * ## ⚠️ 为什么这里**给**了默认值（与 `fillArgb` / `tipFade` / `brushes` 的纪律相反）
         *
         * 那三个参数刻意不给默认值，是为了让"第二条调用路径漏传"**编译期就失败**。
         * 本参数相反：它是**新增的观感维度**，而当前调用方（`IslandWindow`）还没有这个设置项 ——
         * 不给默认值会**直接让调用方编译失败**（那是改别人的文件）。
         * 故默认值取"与改动前逐位相同"的 [SpectrumBarCorner.ROUND]，
         * 并由 `SpectrumBarShapeProbe` 断言"默认档逐像素等于改动前"。
         *
         * ⚠️ 调用方补上设置项后**必须显式传它**，否则该设置项是**死设置**
         * （本项目纪律：「声明了设置 ≠ 实现了功能」）。接线方式见
         * `SpectrumBarShapeProbe` 文件头的"调用方该怎么调"。
         */
        barCorner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,
        /**
         * `spectrum.bar_shape`（**柱体**圆角 / 方角）的**圆角比例**。见 [paintBars] 的同名参数。
         *
         * ⚠️ 与 [barCorner] **是两个维度**：[barCorner] 是既有枚举通道；
         * 本参数对应**已注册**的 `spectrum.bar_shape`。
         */
        barShapeRatio: Float = 1f,
    ) {
        if (bands.isEmpty()) return
        if (bounds.width <= 0 || bounds.height <= 0) return

        // ── ① 平滑：把目标值向显示值逼近 ─────────────────────────────────────
        val displayed = smooth(state, bands, bounds, style, nowNanos, playing, peakHold)

        // ── ② 暂停且已归零 → **不画** ────────────────────────────────────────
        //
        // 用户原话：「未播放歌曲时可以保持不亮，但暂停后频谱应尽快归零」。
        // 两句合起来的语义是：**没有声音在放的时候，频谱不该有东西留在屏幕上**。
        // 若这里仍走 [paintBars]，[MIN_BAR_HEIGHT] 会让每个频段留一个 2px 的小圆点 ——
        // 那在"暂停"这个场景下不是"仪表在工作"，而是"一排列不掉的残点"。
        //
        // ⚠️ 只在 `!playing` 时短路。播放中某频段恰为 0 时**仍要画小圆点** ——
        //   那是"有信号但这一段现在是静音"，与"整体没有在播放"是两回事。
        if (!playing && isSilent(displayed)) return

        // ── ③ 绘制 ──────────────────────────────────────────────────────────
        // 保存/还原的是 `paint` 而不是 `color`：`color` 只是 `paint` 的一个特例，
        // 只还原 color 会在传了 [paintOverride]（渐变）时把调用方原本的 paint 丢掉。
        val oldPaint = g.paint
        val oldStroke = g.stroke
        // `opacity` 用 `AlphaComposite` 统一施加（理由见参数 KDoc）。
        // ⚠️ 只在真的 < 1 时才动 composite —— 默认路径（`1f`）**零额外调用**，
        //    这是"新增观感项不该拖慢默认路径"的体现。
        val oldComposite = if (opacity < 1f) g.composite else null
        try {
            if (opacity < 1f) {
                g.composite = java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER,
                    opacity.coerceIn(0f, 1f),
                )
            }
            if (paintOverride != null) g.paint = paintOverride else g.color = cache.color(color)
            // 对称中线：调用方没给（NaN）时回退到 bounds 纵向中心 —— 兜底，
            // 让"忘了算布局"的调用方也能得到一个合理结果，而不是画出 NaN 坐标。
            val axisY = if (symmetryCenterY.isNaN()) {
                bounds.y + bounds.height / 2f
            } else {
                symmetryCenterY
            }
            when (style) {
                // ③ 底图（未播放段）。`fillArgb = color` —— 那是调用方按
                // `spectrumLayerArgb(packed, 段系数)` 算好的**未播放色**，
                // 高 8 位就是它的有效 alpha。
                Style.BARS -> paintBars(
                    g, displayed, bounds, bounceScale, symmetric, axisY, barPitchPx,
                    forceBarCount = forceBarCount,
                    fillArgb = color, tipFade = fadeTop, brushes = tipFadeBrushes,
                    paintForBar = paintForBar, peaks = state.peaks,
                    argbForBarSegment = argbForBarSegment,
                    // ★★ 逐柱取色（`color_mode = level`）—— 本趟基色 = 未播放色 `color`。
                    argbForBar = argbForBar,
                    polar = polar,
                    barCorner = barCorner,
                    barShapeRatio = barShapeRatio,
                )
                Style.WAVE -> paintWave(g, cache, displayed, bounds, bounceScale)
            }

            // ── ④ 进度染色：把**已播放的那一段**用"已唱色"重画一遍 ──────────────
            //
            // ## 为什么是"重画一遍"而不是"逐柱换颜色"
            //
            // 逐柱 `g.color = ...` 会**丢掉 [paintOverride]** —— 而背景频谱的观感
            // 完全建立在"纵向渐变、向上淡出"上（见 `drawBackgroundSpectrum` 的观感取舍）。
            // 逐柱着色会让频谱变成一排实心色块，那是一次远比"没做进度染色"更严重的观感退化。
            //
            // 换成"先画本色、再把已播放段重画一遍"之后：两段**各自**用自己那个
            // 纵向渐变笔刷，淡出一致，且**不需要任何逐柱分配**（每柱一个 `Color`
            // 会让 `RenderCache.colors` 随进度连续变化而无界增长 —— 那是个真实的内存泄漏）。
            //
            // ## 代价：绘制调用翻倍
            //
            // 只在 `progress_tint` 打开、且进度落在 `(0, 1]` 时才发生 ——
            // 即"用户主动打开了这个视觉表达"时。默认关，故默认路径的代价**完全不变**。
            //
            // ## clip 是"临时收窄"，不是"改写"
            //
            // 调用方（`paintComponent`）已经把 clip 设成词岛轮廓；这里再设成进度矩形
            // 是**取交**（AWT 语义），故重画不会溢出词岛。`finally` 里还原 [oldClip]
            // 之后，调用方的 clip 一字不差 —— 这修正了本方法 KDoc 原先
            // 「不改它的 clip」的承诺：现在是"改，但必然还原"。
            // ## ★★ 2026-09-22：本趟的填充色**按趟**取（修「滑杆到 0 还有差别」）
            //
            // 见 [progressColor] 的 KDoc：改动前这里虽然设了 `g.paint = progressPaintOverride`，
            // 但 `paintBars` 在每柱 `fill` 前又用**未播放色**覆盖了它 ——
            // 两趟画出来逐像素相同，"色差"恒不可见。
            //
            // 现在分两种情形，**互斥且都只有一条落点**：
            //   · 取到了已播放色（显式 `progressColor`，或从 `progressPaintOverride`
            //     里提取）→ 由 `paintBars` 用**同一套几何**（缓存的淡出笔刷）填它。
            //     这是 `fadeTop` 两种档位下**唯一**能保证"两趟形状一致"的写法
            //     （形状由本对象算，颜色由调用方给）。
            //   · 取不到 → 退回 [progressPaintOverride]（旧的整带笔刷）。
            val played = progressColor ?: playedArgbFrom(progressPaintOverride)
            if (progressFraction > 0f && progressFraction <= 1f &&
                (played != null || progressPaintOverride != null)
            ) {
                val playedWidth = (bounds.width * progressFraction).toInt()
                    .coerceIn(0, bounds.width)
                if (playedWidth > 0) {
                    val oldClip = g.clip
                    try {
                        g.clip = Rectangle(bounds.x, bounds.y, playedWidth, bounds.height)
                        // 形状一致这条纪律的落点：取到色时两趟都走**同一套几何**
                        // （`fadeTop` 档位决定淡出还是实心），绝不混用。
                        if (played == null) {
                            g.paint = progressPaintOverride
                        } else if (!fadeTop) {
                            g.color = cache.color(played)
                        }
                        when (style) {
                            // ⚠️ **必须把 `symmetric` / `axisY` 一起传下去**（2026-09-18 修）。
                            //
                            // ## 原记与实为
                            //
                            // 原记：这里 `paintBars(g, displayed, bounds, bounceScale)` ——
                            //   少了两个参数，于是 `symmetric` 取**默认 `false`**。
                            //
                            // 实为：用户报「**双峰频谱无法用频谱色差表示播放进度**」。
                            //   根因就是这个"少传参数"：底图用 `symmetric = true` 画成
                            //   上下镜像的双峰，而**进度染色那一层用 `symmetric = false`
                            //   画成一排从底部长起的柱子** —— 两层形态不一致，
                            //   染色层与底图的峰**对不上**，用户看到的就是
                            //   "色差没生效"（其实是画了，只是画歪了）。
                            //
                            // ## 为什么这类缺陷特别值得记
                            //
                            // **同一个绘制函数有两条调用路径（底图 / 进度染色），
                            // 参数表变了只改了一条** —— 这是"两份实现漂移"的变体，
                            // 而且**编译期完全查不出来**（Kotlin 有默认值，
                            // 少传参数是合法的）。故新参数一律给默认值的做法
                            // **在"有第二条调用路径"时会掩盖错误** ——
                            // 修这类缺陷时要**搜同一个函数的所有调用点**，不是只看报错。
                            //
                            // ⚠️ 2026-09-22：本轮把 `fillArgb` / `tipFade` / `brushes`
                            //   **改成必传形参**（没有默认值）就是为了让上面那条教训
                            //   **在编译期**成立 —— 将来再加第三条调用路径时，漏传会直接编译失败。
                            Style.BARS -> paintBars(
                                g, displayed, bounds, bounceScale, symmetric, axisY, barPitchPx,
                                // ★★ 2026-10-11（U13）：**两趟必须同条数** ——
                                //    漏传会让进度染色趟按宽度推导（24 托底）而底图趟固定 5
                                //    ⇒ 两趟几何不一致（= "两趟用同一个基色"那类缺陷的条数版）。
                                forceBarCount = forceBarCount,
                                // ⚠️ `tipFade` 取 `fadeTop && played != null`：
                                //    取不到已播放色时这一趟要退回"整带笔刷"
                                //    （`g.paint` 已被设成 `progressPaintOverride`），
                                //    若仍开淡出，本对象会用**未播放色**重建笔刷、
                                //    把那一趟又画成底图的样子（= 改动前那个"色差不可见"）。
                                fillArgb = played ?: color,
                                tipFade = fadeTop && played != null,
                                brushes = tipFadeBrushes,
                                paintForBar = if (played != null) null else paintForBar,
                                argbForBarSegment = argbForBarSegment,
                                peaks = state.peaks,
                                barCorner = barCorner,
                                barShapeRatio = barShapeRatio,
                                // ★★ 逐柱取色（`color_mode = level`）—— **本趟也要传**。
                                //   ⚠️ 回调收到的 `base` = 本趟的 `fillArgb`（= `played ?: color`）
                                //   ⇒ 已播放段按**已播放色**派生，不会用错基色
                                //   （2026-09-22 修过"两趟用同一个基色"的同类缺陷）。
                                argbForBar = argbForBar,
                                polar = polar,
                            )
                            Style.WAVE -> paintWave(g, cache, displayed, bounds, bounceScale)
                        }
                    } finally {
                        g.clip = oldClip
                    }
                }
            }
        } finally {
            g.paint = oldPaint
            g.stroke = oldStroke
            // `opacity` 的还原：`oldComposite` 非 null 说明我们改过它（见上）。
            // 与 paint/stroke 同一套纪律：**进入本方法时的值必须原样还原** ——
            // 调用方（`paintComponent`）之后还要画歌词、频谱进度等，
            // 留着我们的 alpha 会让**那些东西也变透明**（一个极难归因的观感 bug）。
            if (oldComposite != null) g.composite = oldComposite
        }
    }

    /**
     * 显示值是否已经"全静音"（所有频段都贴底）。见 [paint] 关于暂停时不画的论证。
     *
     * 阈值取 [SILENT_EPSILON] 而不是 0：指数衰减**永远到不了 0**（`x * e^-k` 恒正），
     * 用 0 判定会让"归零"永不成立、暂停时永远多画几帧。
     *
     * 同时供窗口层的帧驱动使用（`IslandWindow` 靠它决定"暂停后还要不要再跑帧"）——
     * 故是 `internal` 可见性而不是 `private`。
     */
    internal fun isSilent(displayed: FloatArray): Boolean {
        for (v in displayed) if (v > SILENT_EPSILON) return false
        return true
    }

    /**
     * 当前平滑状态是否已静音。给帧驱动用（它只持有 cache）。
     *
     * ⚠️ **必须把 `peaks` 一起算**：峰值帽是**逐帧下落**出来的，若只看 `displayed`，
     * 那么"柱子已归零、帽还挂在半空"时 `isSilent` 会返回 `true` →
     * 帧驱动**停帧** → **帽永远僵在那里**（表现为"频谱上留了一排小横线再也不消失"）。
     * 这正是项目纪律「**逐帧动画必须配"谁来跑帧"的答案**」要防的那一类。
     */
    internal fun isSilent(state: SpectrumCache): Boolean =
        isSilent(state.displayed) && isSilent(state.peaks)

    /**
     * 平滑推进：返回本帧应当绘制的显示值数组。
     *
     * ## 为什么回落速率要按**时间差**折算，而不是按「每帧固定比例」
     *
     * 若写成「每帧乘 0.85」，回落速度就**与帧率绑定**：60 fps 时每秒衰减到
     * 0.85^60 ≈ 0.00006，30 fps 时是 0.85^30 ≈ 0.0076 —— 差了 120 倍。
     * 而在实际运行中帧率是**变化的**（词岛在拖动、被遮挡恢复、
     * 别的程序占满 GPU 时都会掉帧），表现为「有时柱子掉得快、有时掉得慢」，
     * 用户会觉得频谱「不稳定」。
     *
     * 所以用指数衰减的标准做法：`factor = exp(-dt / tau)`，`tau` 是时间常数。
     * 这样无论帧率如何，**同样时长内衰减同样的比例**。
     *
     * ## 上升为什么也做成「快速指数逼近」而不是「直接赋值」
     *
     * 直接赋值（`displayed[i] = target`）在**采集跳变**时会显得很生硬 ——
     * 而采集数据本身就常有毛刺（某一帧的 FFT 在某个 bin 上突跳）。
     * 用一个很短的时间常数（[ATTACK_TAU_SECONDS] = 0.02 s，即约 50 Hz 的跟随速度）
     * 既跟得上鼓点，又能滤掉单帧毛刺。
     *
     * ## 频段数变化时**不插值**，直接重置
     *
     * 因为两组不同长度的数组之间「第 i 个频段」没有对应关系
     * （频段数变了意味着频率分辨率变了）。强行插值会得到一个
     * 从「上一套频段的形状」渐变到「新频段的形状」的假动画。
     * 重置（直接采用新值）在视觉上是一次干净的切换。
     */
    private fun smooth(
        state: SpectrumCache,
        bands: FloatArray,
        bounds: Rectangle,
        style: Style,
        nowNanos: Long,
        playing: Boolean,
        peakHold: Boolean,
    ): FloatArray {
        val signature = (bands.size shl 4) or style.ordinal

        // 形状变了（频段数或风格）→ 重置显示值数组。
        //
        // ⚠️ **实测修正**：早先这里无条件把 `bands[i]` 直接赋给 `displayed[i]`，
        // 于是**首次调用**也走这条路径 —— 而首次调用恰好也满足「长度从 0 变成 n」，
        // 结果频谱**第一帧就跳到满值**，attack 平滑完全没生效。
        // 实测：连续 10 帧显示值恒为 `1.000000`，看不出任何渐入。
        //
        // 修正：把「首次初始化」与「形状变化重置」分开处理 ——
        // - **首次**（`displayed` 为空）：从 **0** 开始，让第一帧之后的
        //   attack 平滑把柱子**渐入**上去。这也是用户打开「显示实时频谱」时
        //   期望看到的：柱子从底部涨起来，而不是"啪"地出现。
        // - **形状变化**（已有数据但长度/风格变了）：直接采用新值。
        //   这不是过度设计 —— 两组不同长度的数组之间「第 i 个频段」没有对应关系
        //   （频段数变了意味着频率分辨率变了），强行插值会得到一个
        //   从「上一套频段的形状」渐变到「新频段的形状」的假动画。
        val firstTime = state.displayed.size != bands.size

        if (firstTime) {
            // 首次：用 0 而非目标值初始化，交给下面的 attack 平滑渐入。
            state.displayed = FloatArray(bands.size)
            state.lastSignature = signature
            // 首帧**没有**可信的时间基（调用方可能传 0L 或一个任意起点），
            // 故这里把 lastNanos 设成当前值，让下一帧拿到一个真实的时间差。
            state.lastNanos = nowNanos
            return state.displayed
        }
        if (state.lastSignature != signature) {
            // 形状变化：直接采用（见上面的论证）。
            state.displayed = FloatArray(bands.size) { bands[it].coerceIn(0f, 1f) }
            state.lastSignature = signature
            state.lastNanos = nowNanos
            return state.displayed
        }

        val dt = if (state.lastNanos == 0L || nowNanos == 0L || nowNanos <= state.lastNanos) {
            // 没有可信的时间差 → 退化为「按 60 fps 的一帧」估算。
            // 为什么不用 0（即不推进）：那会让首次调用后的画面完全静止，
            // 而「首帧恰好没有可信时间戳」是常见情况（调用方第一次传 0L）。
            DEFAULT_FRAME_SECONDS
        } else {
            // 封顶：若中间有一大段没画（窗口被遮挡、系统挂起），
            // 不封顶会让 `exp(-dt/tau)` 直接下溢成 0，效果与「瞬间归零」相同 ——
            // 那正是回落平滑要避免的。封顶到 0.1 s 让最坏情况仍是「一次较快的衰减」。
            ((nowNanos - state.lastNanos).toDouble() / 1_000_000_000.0).coerceAtMost(MAX_DT_SECONDS)
        }
        state.lastNanos = nowNanos

        val displayed = state.displayed

        // ── 暂停：**无条件**向 0 快速回落（2026-09-17）────────────────────────
        //
        // ## 原记
        //
        // 只有"向目标值逼近"一条路径，回落用 [RELEASE_TAU_SECONDS] = 0.35 s。
        //
        // ## 实为（用户真机反馈）
        //
        // 「暂停后频谱应**尽快归零**，避免缓慢衰减」。0.35 s 的时间常数下，
        // 从满值落到视觉不可见要约 1.2 s —— 暂停后用户盯着的那一秒里，
        // 柱子还在慢慢往下爬，看起来像"卡住了没反应"。
        //
        // ## 为什么是"无条件向 0"而不是"把 release 系数调小"
        //
        // 因为**暂停时目标值本身不可信**：采集源（WASAPI 进程回环）在暂停瞬间
        // 设备缓冲区里还有未取走的数据，会继续推来几帧**非零**的残留。
        // 若仍按"向目标值逼近"，柱子会**停在残留高度上**（目标非零 → 永不归零），
        // 而用户要的正是"归零"。故这里不看 `bands`，直接把显示值乘衰减因子。
        //
        // 这也让 [paint] 里那条"归零后不画"的短路能真正成立 —— 两者必须配套。
        if (!playing) {
            val factor = Math.exp(-dt / PAUSED_RELEASE_TAU_SECONDS)
            for (i in displayed.indices) {
                displayed[i] = (displayed[i] * factor).toFloat()
            }
            // 峰值也一起**快速**回落 —— 用户要求「暂停后频谱应**尽快归零**」，
            // 若峰按常速下落，它会"挂"在那里 2 秒多，看起来像没归零。
            updatePeaks(state, displayed, dt, peakHold, playing = false)
            return displayed
        }

        for (i in displayed.indices) {
            val target = bands[i].coerceIn(0f, 1f)
            val current = displayed[i]
            if (target > current) {
                // 上升（attack）：快速逼近。
                val factor = Math.exp(-dt / ATTACK_TAU_SECONDS)
                displayed[i] = (current + (target - current) * (1.0 - factor)).toFloat()
            } else {
                // 回落（release）：慢速逼近。见 KDoc 关于快升慢降的论证。
                val factor = Math.exp(-dt / RELEASE_TAU_SECONDS)
                displayed[i] = (current + (target - current) * (1.0 - factor)).toFloat()
            }
        }
        updatePeaks(state, displayed, dt, peakHold, playing = true)
        return displayed
    }

    /**
     * 推进**峰值保持**：每个频段记一个"最近的高点"，按**固定速率**下落。
     *
     * ## 为什么是"固定速率"而不是"指数衰减"
     *
     * 柱高用的是指数衰减（快升慢降，见 [smooth]）—— 那是为了"听着像有惯性"。
     * 但峰值帽的语义不同：它是**读数**（"刚才这一击有多高"），
     * 指数衰减会让它在低位**拖很久**（永远到不了 0），而固定速率让它
     * 在可预期的时间内退场（满量程约 2.2 秒），且**到点就干净地消失**。
     *
     * ## 为什么形状变化时直接采用当前值
     *
     * 与 [smooth] 里 `displayed` 同一条理由：两组不同长度的数组之间
     * 「第 i 个频段」**没有对应关系**（频段数变了意味着频率分辨率变了），
     * 强行插值会得到一个"从上一套频段的形状渐变到新频段形状"的假动画。
     *
     * @param enabled `false` 时把 `peaks` 清空 —— 于是**关掉这个效果不会留下残留的帽**
     *   （用户从 ULTRA 切回 HIGH 时，那些帽必须立刻消失，而不是"慢慢落完"）。
     */
    private fun updatePeaks(
        state: SpectrumCache,
        displayed: FloatArray,
        dt: Double,
        enabled: Boolean,
        playing: Boolean,
    ) {
        if (!enabled) {
            if (state.peaks.isNotEmpty()) state.peaks = SpectrumCache.EMPTY
            return
        }
        if (state.peaks.size != displayed.size) {
            state.peaks = FloatArray(displayed.size) { displayed[it].coerceIn(0f, 1f) }
            return
        }
        val perSecond = if (playing) PEAK_FALL_PER_SECOND else PAUSED_PEAK_FALL_PER_SECOND
        val fall = (perSecond * dt).toFloat()
        for (i in displayed.indices) {
            // `maxOf`：帽要么被本帧的柱高顶上去，要么按速率下落 —— 两者取高者。
            state.peaks[i] = maxOf(state.peaks[i] - fall, displayed[i].coerceIn(0f, 1f))
        }
    }

    //
     // 条形风格：逐频段画一个圆角矩形。
     //
     // ## 三个布局参数全部从 [bounds] 推导（而不是读设置项）
     //
     // 我核对了设置注册表：`spectrum.*` 只有 `enabled` 与 `color_mode` 两项，
     // **没有**条数 / 条宽 / 间距的设置项。所以这些值没有「用户配置」可读，
     // 只能由绘制宽度推导。把推导写在这里（而不是留给调用方传参）是刻意的：
     //
     // - 调用方（窗口层）不该关心「频谱条之间的缝是几像素」这类细节；
     // - 推导规则集中在一处，将来若加了设置项，只需在这里改一处。
     //
     // ## 间隙取「条宽的 30%」，且**下限 1 px**
     //
     // 30% 是一个在 2–10 px 条宽范围内视觉都成立的固定比例（缝太细会糊在一起，
     // 太粗会让频谱显得稀疏）。但比例在小尺寸下会算出**亚像素**的缝
     // （如条宽 3 px → 缝 0.75 px），而抗锯齿会把亚像素的缝画成一层灰雾 ——
     // 那比没有缝还难看。故下限 1 px：宁可缝偏宽，也不要虚化的缝。
     //
     // ## 高度为 0 的频段**仍然画**（画成一个圆角点）
     //
     // 若不画，无信号时频谱会**完全消失** —— 而用户刚刚打开了「显示实时频谱」，
     // 看到一片空白会以为功能坏了。画成 [MIN_BAR_HEIGHT] 高度的小圆角点，
     // 视觉上表达「这里有一条，只是现在没信号」，这是仪表类 UI 的通行做法。
     //
     // ## [bounceScale] 作用在**归一化柱高**上，且在 `MIN_BAR_HEIGHT` 之前
     //
     // 顺序是有意义的：拉伸的输入必须是 `0..1` 的**比例**（`stretchAroundMid` 的
     // 中值是 `0.5` 而不是"一半高度"），所以只能在 `value` 上做，不能对像素高度做。
     // 拉伸完再乘 [bounds].height、再套最小高度 —— 于是"静音仍是静音"
     // （`stretchAroundMid(0, k) = 0` → 仍走 [MIN_BAR_HEIGHT] 的小圆点），
     // 与"无信号时不能完全消失"这条既有规则不冲突。
     //
    /**
     * 画条形频谱。
     *
     * @param symmetric **上下对称双峰**（`spectrum.symmetric`）—— 以 [axisY] 为轴上下各画一份。
     *   语义见 [paint] 的同名参数。
     * @param axisY 对称中线的 y。仅 [symmetric] 为真时用。
     *   ⚠️ 由调用方算（它掌握布局：两行中间 / 单行中线），本函数**不猜布局**。
     * @param fillArgb 本趟填充用的打包 ARGB。**高 8 位就是这一段的有效 alpha**
     *   （见 [SpectrumTipFadeBrushCache] 的 KDoc：绘制侧**不再**另外乘一次）。
     *   底图趟传未播放色、进度染色趟传已播放色。
     * @param tipFade `spectrum.fade_top`。`true` = 柱体与峰值帽用**缓存的**
     *   末端淡出笔刷（尖端淡、柱身实心）；`false` = 用**当前** `g.paint` 实心填充。
     * @param brushes 末端淡出笔刷缓存（由 [paint] 持有，跨帧复用 —— 这是
     *   「每帧新建渐变数从柱数降到常数」的落点）。
     * @param peaks 峰值保持的每频段高点。**长度必须与绘制柱数一致才画**（否则视为未启用）。
     *   由 `paint` 从 `state.peaks` 传进来 —— 本函数**不持有状态**。
     *
     * ⚠️ [fillArgb] / [tipFade] / [brushes] **刻意不给默认值**：`paintBars` 有
     *    两条调用路径（底图 / 进度染色），而 2026-09-18 那次缺陷正是
     *    "新参数给了默认值 ⇒ 第二条路径漏传 ⇒ 编译期查不出来"。
     *    不给默认值 ⇒ **漏传直接编译失败**（把纪律交给编译器）。
     */
    private fun paintBars(
        g: Graphics2D,
        bands: FloatArray,
        bounds: Rectangle,
        bounceScale: Float,
        symmetric: Boolean = false,
        axisY: Float = Float.NaN,
        barPitchPx: Float = SPECTRUM_BAR_PITCH_PX,
        /**
         * ★★★ 2026-10-11（**U13**）：**固定条数**（`paint` 同名参数的透传）。
         * `> 0` ⇒ 恒用该条数（**不经过 [SPECTRUM_MIN_BARS] 托底**）；
         * `<= 0` ⇒ 按宽度推导（改动前行为）。判据唯一出口 = `spectrumBarCountFor`。
         */
        forceBarCount: Int = 0,
        fillArgb: Int,
        tipFade: Boolean,
        brushes: SpectrumTipFadeBrushCache,
        /**
         * **legacy 逐柱笔刷工厂** —— 只在 `tipFade = false` 时生效。见 [paint] 的
         * 同名参数 KDoc（为什么保留、为什么 `tipFade = true` 时必须忽略它）。
         */
        paintForBar: ((topY: Float, bottomY: Float, cornerRadiusPx: Float) -> Paint)? = null,
        peaks: FloatArray = SpectrumCache.EMPTY,
        /** 端头形状（[SpectrumBarCorner]）。见 [paint] 的同名参数。 */
        barCorner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,
        /**
         * ★★ **逐柱取色**（`spectrum.color_mode = level` / `cover_flow`）。见 [paint] 的同名参数 KDoc。
         *
         * ★ 回调收到的 `base` 恒 = **本趟的 [fillArgb]**（底图趟 = 未播放色；
         * 进度染色趟 = 已播放色）⇒ 两趟各自用自己的基色派生。
         * ★ `count` = 本帧**实际**柱数（由本函数给出 —— 调用方不重算，见 [paint] 的 KDoc）。
         */
        argbForBar: ((index: Int, count: Int, value: Float, base: Int) -> Int)? = null,
        /**
         * ★★★ 2026-10-17（**BUG-226**）：**逐段取色**（柱体沿高度切段）——
         * `null`（默认）⇒ 整柱一个颜色（= 改动前的全部行为，**逐像素不变**）。
         *
         * ## 为什么需要它（用户报障逐字）
         *
         * > 「**插槽小频谱的碎色流光不是背景那种。**」
         *
         * ★ 插槽频谱只有 5 根柱，而 [argbForBar] 是**一维**（按柱序）⇒
         *   每根柱子整根一个色 ⇒ 视觉上是 **5 条竖纹**，与岛体背景的
         *   **二维细胞噪声**（`CamoTexture`）不是一个形态 ✗
         * ⇒ 本回调让**同一根柱子的不同高度**取不同色 ⇒ 柱内出现碎块 ✓
         *
         * ⚠️ 只在 `symmetric = false` 时生效：对称双峰要保证"轴处无缝"，
         *   分段会破坏那条不变量（见 `paintBars` 的对称分支）。
         */
        argbForBarSegment: ((index: Int, count: Int, segIndex: Int, segCount: Int, base: Int) -> Int)? = null,
        /**
         * ★★★ **极坐标布局**（环形 / 放射）。见 [paint] 的同名参数 KDoc。
         *
         * ⚠️ 本函数**不自己算角度** —— 角度走公开纯函数 `polarAngleDeg`（`SpectrumGeometry.kt`），
         * 与"几何只在纯函数里定义"那条纪律一致（探针可直接断言）。
         */
        polar: SpectrumPolarSpec? = null,
        /**
         * `spectrum.bar_shape`（**柱体**圆角 / 方角）换算出的**圆角比例**。
         *
         * ★ 2026-09-26 接线：由 `spectrumBarCornerRatio(shape)` 算出（`1f` = 圆角、`0f` = 方角）。
         * 换算**只此一处**（`SettingRegistry.spectrumBarCornerRatio`，`RegistryProbe` §12.2 断言它）。
         *
         * ★ 默认 `1f` ⇒ **开箱零变化**：`x * 1f == x` 对有限 float **逐位成立**。
         */
        barShapeRatio: Float = 1f,
    ) {
        // ── 条数由**宽度**推导（2026-09-17 用户要求）─────────────────────────
        //
        // 用户原话：「如果歌词扩展，选择**增加频谱条**，而不是**拉大已有频谱条**」。
        //
        // 原记：`val count = bands.size`（数据源给的固定段数）+ `slot = 宽度 / count`
        //   —— 于是窗口变宽只会让每条变宽，条数一动不动。
        //
        // 现做法：条数由宽度按目标间距推导（见 [spectrumBarCount]），
        //   条宽因此**基本恒定**；宽度变化体现为**条数变化**。
        // ★★ 2026-10-11（U13）：判据收口到 `spectrumBarCountFor` ——
        //    `forceBarCount > 0` ⇒ 固定条数（**不经过 SPECTRUM_MIN_BARS=24 托底**）；
        //    `<= 0` ⇒ 与改前逐位相同（走 spectrumBarCount）。
        //    ★ 插槽小频谱传 5 ⇒ 修复「24 根塞 28 px ⇒ 糊成一片/不显示」（见该函数 KDoc）。
        val count = spectrumBarCountFor(bounds.width, barPitchPx, forceBarCount)
        // 把数据源的频段**插值**成 count 根柱子。
        // ⚠️ 这是插值，不是提高分辨率 —— 见 [resampleBands] 的 KDoc（不能含糊）。
        val values = resampleBands(bands, count, barScratch(count))

        // ── ★★ 2026-09-20：**真无声时不画任何柱子** ──────────────────────────────
        //
        // 用户原话：「**双峰频谱在歌曲前奏的无声期为何还有高度？**
        //   （呈现**一条贯穿的粗线**）」
        //
        // ## 根因：地板高度 × 对称双峰的几何
        //
        // [MIN_BAR_HEIGHT] = 2px 是"无信号时也别完全消失"的地板。
        // 但在**对称双峰**模式下，每根柱子被画成一个**跨轴**的矩形：
        //   · 高度 = `2 × barHeight` → 地板时是 **4px**；
        //   · 宽度 = `barWidth`，柱间只有 `gap`（默认约 1px）。
        // 于是静音时整条带变成「4px 高的矩形 + 1px 缝」重复 —— 视觉上就是
        // **一条贯穿整个宽度的粗线** ✓ 与用户描述完全吻合。
        //
        // ## 为什么不在**每柱**上都去掉地板
        //
        // 本文件原有的取舍（见 [MIN_BAR_HEIGHT] 的 KDoc）是"一片空白会让用户
        // 以为功能坏了"。那条理由在**有信号**时依然成立 —— 所以这里**只**在
        // **整帧都是静音**时跳过；只要有一点点信号，地板照旧。
        //
        // 判据用**本帧重采样后的峰值**（而不是原始 `bands`）：重采样会把
        // 相邻频段插值进来，用峰值判"这一帧到底有没有声音"更贴近实际绘制结果。
        //
        // ⚠️ 必须按 `0 until count` 遍历：`values` 是**复用缓冲**
        //    （`barScratch`，只增不减），`count` 之后的尾巴是**上一帧的残值** ——
        //    对整数组取 `max()` 会把残值当成信号，于是永远不触发跳过。
        var peak = 0f
        for (i in 0 until count) {
            if (values[i] > peak) peak = values[i]
        }
        if (peak <= SILENCE_PEAK_EPS) return

        val total = bounds.width.toFloat()
        // 每个柱子分配到的水平空间（含缝）。
        val slot = total / count
        // ── ★★ 2026-09-21：**地板从固定 1px 改成动态** —— 修「极小间距下频谱糊成一片」──
        //
        // 用户原话（真机）：「**无声时上下对称双峰中线仍有一条粗线**」（本条的另一半原因），
        // 以及此前把「频谱条间距」拖到最小后**整条频谱糊成一片**。
        //
        // ## 原记（错）
        //
        // `gap = max(slot × 0.30, 1f)`、`barWidth = max(slot − gap, 1f)` ——
        // 两者**各自**有 1px 地板，注释里的理由是"缝低于 1px 会被抗锯齿糊成灰雾"。
        //
        // ## 实为
        //
        // 那个理由只在 `slot` 足够大时成立。而 `slot = bounds.width / count`，
        // `count` 被 `SPECTRUM_MAX_BARS`（**1024**）夹住 ——
        // 600px 宽下 `slot = 600 / 1024 ≈ 0.586px`，而
        // `barWidth + gap >= 1 + 1 = 2px > slot` ⇒ **相邻柱子重叠约 3.4 倍** ⇒
        // **整条频谱在水平方向连成一片**（这正是用户报的"糊成一片"）。
        //
        // ## 修法：把两个地板改成**动态**，并保证 `barWidth + gap == slot`
        //
        // 取 `floor = min(0.25, slot / 2)`：
        //   · `slot` 大时 → `floor = 0.25`（比原来的 1px 更细，但 AA 仍能分辨）；
        //   · `slot` 小时 → `floor = slot / 2`，于是 `barWidth + gap` **恰好 = slot**，
        //     **结构上不可能重叠**（这才是"靠结构消除状态"）。
        // 且 `SPECTRUM_BAR_GAP_RATIO = 0.30 < 0.5` ⇒ `barWidth = slot − gap >= 0.7 × slot >= slot/2`
        // ⇒ `coerceAtLeast(floor)` 对 `barWidth` 不生效，两者之和恒为 `slot`。
        // 水平几何抽成**公开纯函数**（见 [spectrumBarSlot] 的 KDoc）——
        // 于是"柱宽 + 缝 恒 ≤ slot"这条不变量可以被探针**直接穷举断言**，
        // 而不必依赖"画出像素再看"那种间接取证。
        //
        // ── ★★ 2026-09-22：`solid` 档（手动间隙 = 0 ⇒ 实心频谱）─────────────
        //
        // 用户原话（逐字）：
        // > 「频谱条手动间隙改为 **0-200px**，**0.1px 梯度**的滑杆，
        // >   **当间距为 0px 时呈现实心频谱**。」
        //
        // 判据在**公开纯函数** [isSolidSpectrumPitch]（探针可断言），
        // 不在这里写 `barPitchPx <= 0` —— 否则"0 是实心"这条规则会有两个落点。
        //
        // 实心档同时要改**两处**几何，少一处就画不成实心：
        //   ① `gap = 0` + `barWidth = slot`（矩形之间无缝）—— [spectrumBarSlot]；
        //   ② `arc = 0`（方角）—— [spectrumBarCornerPx]。
        // ② 不是可选项：柱宽吃满只保证矩形无缝，而**圆角**矩形相接时
        // 接缝两侧各向内退 `arc/2`，上下各留一个高约 `arc` 的腰形缺口
        // （与 2026-09-18 修的"双峰中线腰缝"是同一个几何，只是发生在每两根柱之间）。
        val solid = isSolidSpectrumPitch(barPitchPx)
        val (gap, barWidth) = spectrumBarSlot(slot, solid)
        // 圆角半径取条宽的一半（完全圆头）—— 条形频谱的常见做法，
        // 也让「细条 + 小高度」时不至于变成方块。
        // 实心档取 0（方角）：理由见上。
        // ⚠️ 2026-09-23：末位多一个 `barCorner`（`spectrum.bar_corner`：圆角 / 方角）——
        //    它是**圆角上限的倍率**，而"实心档恒 0"的优先级更高（见 [spectrumBarCornerPx]）。
        // ★ `barShapeRatio` = `spectrum.bar_shape` 的换算结果（`1f` 圆角 / `0f` 方角）。
        //   它**只改"取多少"**，不改"上限从哪来"（上限仍由 `spectrumBarCornerPx` 唯一决定）——
        //   这正是 `spectrumBarCornerRatio` 的 KDoc 要求的分工。
        // ★★ 2026-09-28 用户要求（逐字）：
        //   「频谱条大R圆角未实现（关于R角大小问题，**按频谱条宽度实时判断**，
        //     使其在**任意宽度下都表现为胶囊状顶部**）」
        //   以及「**示例词岛中频谱条是圆角，但真实词岛中并没有被应用**」。
        //
        // ## 根因：圆角上限错用了 `MIN_BAR_HEIGHT`（= "最小高度"，语义不符）
        //
        // 原实现 `spectrumBarCornerPx(barWidth, MIN_BAR_HEIGHT, …)` 传的 `maxCornerPx = 2f`，
        // 而该函数是 `min(柱宽, 上限) × cornerFraction` ⇒ 实际半径 = `min(柱宽, 2px)`。
        // 本 KDoc 上一行却写着"圆角半径取条宽的一半（完全圆头）"—— **注释与代码相反**。
        // 后果：默认间距 7px 下条宽约 4.9px ⇒ 半径只有 **2px**（远小于 4.9/2 = 2.45px，
        // 更远小于"胶囊"所需的 `条宽/2`）⇒ 看上去**接近方角**，只有肉眼盯住尖端才勉强看出倒角。
        // 而预览侧（`IslandPreviewPane`）传的 `maxCornerPx = 柱高`（很高）⇒ 半径 = `min(条宽, 柱高)`
        // = **条宽** ⇒ 被 `barSegmentPath` 夹到 `minOf(w,h)/2` = `条宽/2` ⇒ **真胶囊**。
        // **这就是"预览是圆角、真机不是"的全部原因**：两边上调用的**上限参数不同**。
        //
        // ## 修法：上限改成 `barWidth / 2f` ⇒ 半径恒 = `条宽/2`（真正的胶囊，任意宽度成立）
        //
        // `spectrumBarCornerPx(barWidth, barWidth / 2f, …)` = `min(barWidth, barWidth/2) × f`
        //   = `barWidth/2 × f`（`f` = `barShapeRatio`，1 = 圆角 / 0 = 方角）。
        // **"按宽度实时判断"由此自动成立** —— 半径是 `barWidth` 的函数，条变宽半径跟着变，
        // 不需要任何分支或档位：
        //   · 宽条（如 26.7px）⇒ 半径 13.3px = 真半圆头（胶囊）；
        //   · 默认细条（约 4.9px）⇒ 半径 2.45px = **完整半圆**（不再是 2px 的近方角）；
        //   · 极细条（如 1px）⇒ 半径 0.5px，仍是"完全圆头"。
        //   · 实心档（`solid = true`）仍恒 0（`spectrumBarCornerPx` 的第一道特判），**不受影响**。
        //
        // ⚠️ `MIN_BAR_HEIGHT` **不删**：它仍是"无信号时的最小高度地板"（另一件事），
        //    只是不再兼任"圆角上限" —— 本行原先正是把两个语义混为一谈。
        val cornerCapPx = barWidth / 2f
        val arc = spectrumBarCornerPx(barWidth, cornerCapPx, solid, barCorner) * barShapeRatio

        // ── ★★★ 2026-10-04：极坐标（环形 / 放射）—— 把"槽"整体摆到"圆心正上方"──
        //
        // ## 一句话原理
        //
        // 旋转后 **`-y` 方向 = 从圆心向外** ⇒ 只要把"槽"摆成
        // 「底边在 `cy − innerRadius`、**顶边**在 `cy − maxLength`」，
        // 再**逐柱 `rotate` 到自己的扇区**，那么"向上长"的柱子就变成了"向外长" ✓
        //
        // ★★★ 2026-10-11（**BUG-166**）：上面那两句的**口径**原本是错的 ——
        //   原实现写的是「向上**延伸** `maxLength`」⇒ `outerR = innerRadius + maxLength`
        //   ⇒ ★ **`RING` 档的外半径 = `0.55R + R = 1.55R`** ⇒ 条形**戳出画布 55%**，
        //   被画布/形状裁成"方方的齿轮"（真机观感 = 「不像环」）✗
        //   ★ 而 `SpectrumPolarSpec` 自己的 KDoc 一直是另一套口径：
        //   「`maxLength` 取 `min(w,h)/2`」+「内半径 == `maxLength` ⇒ 可用径向长度 = **0**」
        //   ⇒ 数据类把 `maxLength` 当**外半径 R**，绘制侧却把它当**条长**
        //   ⇒ ★ **同一个字段两套语义**，`ratio = 0` 时恰好重合（RADIAL 因此看不出问题）✓
        //   ⇒ 本批**统一到数据类那套**（也是 KDoc 里"查证表"的口径）：`maxLength` = R。
        //
        // ⇒ ★★ **柱体绘制的每一行（圆角 / 淡出 / 逐柱取色 / 对称 / 峰值帽）都没改** ——
        //    它们只是"以为自己在直角坐标里"，而坐标系已经转过了。
        //
        // ## 淡出带的两端也要跟着挪
        //
        // `topEdge` / `bottomEdge` 是**淡出带的锚点**（见下方 KDoc）。极坐标下柱子的
        // 活动范围是 `[cy − outerR, cy − innerR]` ⇒ 这两个值必须**同步覆写**，
        // 否则淡出会锚在"岛体矩形"的边上（转过去以后完全错位）。
        val polarInnerR = polar?.innerRadius ?: 0f
        val polarOuterR = polar?.maxLength ?: 0f

        val x0 = bounds.x.toFloat()
        // ── 对称模式的几何（2026-09-18 新增）─────────────────────────────────
        //
        // 非对称（现状）：柱子从 `bounds` **底边**向上长，单侧最大高度 = `bounds.height`。
        // 对称：以 `axis` 为轴，**上下各画一份**（镜像）。
        //
        // ⚠️ 对称时单侧最大高度取 `halfSpan` = **轴到较近那条边的距离**，
        //    而不是 `bounds.height` —— 否则柱子会**溢出 bounds 的另一侧**。
        //    取较近一侧（`minOf`）而不是较远一侧，是为了"两边都不溢出"；
        //    代价是轴偏得越厉害、柱子越矮，但那总比画到框外（被调用方的 clip 切掉）
        //    看起来像"柱子被截断"要好。
        // ⚠️ `bounds.y` / `bounds.height` 是 **Int**，与 `axis`（Float）运算前必须显式转 ——
        //    否则 Kotlin 会去找 `BigDecimal.minus` 之类的重载并报一个**离病根很远**的错。
        //
        // ★★★ 2026-10-12（**BUG-167**）：★ **极坐标下"轴"必须是【环的中半径】** ✗
        //
        // ## 用户报障（逐字）
        //
        // > 「**为什么环状频谱连不成一个环？**（我期望中的环状频谱是**以上下双峰的形式成环**）」
        //
        // ## 根因：极坐标改造**漏了这一行**
        //
        // ★ 2026-10-04 / 10-11 两批把 `topEdge` / `bottomEdge` / `halfSpan` **都**改成
        //   极坐标口径了（见上方 KDoc）—— ★★ **唯独 `axis` 没改** ✗
        // ★ 于是**双峰**模式下 `baseY = axis` 仍是 **`bounds.y + height/2`（岛体矩形的中心）**，
        //   ★★ 而柱子是在**绕圆心旋转后的坐标系**里画的 ⇒ 那个 y 对应的是一个
        //   **与环无关的半径** ⇒ 柱子**没有跨着任何一条环** ⇒ **环连不起来** ✗
        //
        // ## 正确口径
        //
        // ★ 旋转后 `-y` = **向外**（见上方 KDoc）⇒ **半径 `r` ↔ 屏幕 `y = cy − r`** ✓
        // ⇒ 轴 = 中半径 ⇒ `y = cy − (innerR + outerR) / 2` ✓
        //
        // ★★ 这样双峰的上下两半**恰好**铺满 `[innerR, outerR]` ⇒
        //    ★ **一条环 + 向内/向外各一列峰** = 用户要的「以上下双峰的形式成环」✓
        // ★ BUG-222：改调**共用出口**（预览同一个函数）—— 原为本地算式
        //   `it.cy - (polarInnerR + polarOuterR) / 2f`（数值不变，收口唯一落点）
        val polarAxis = polar?.let { polarSymmetricAxisY(it) }
        val axis = polarAxis ?: (if (axisY.isNaN()) bounds.y + bounds.height / 2f else axisY)
        // ★★★ 2026-10-04：极坐标下，柱子的活动范围是**径向**的
        //   `[cy − outerR, cy − innerR]`（旋转后 `-y` = 向外）——
        //   故这三个量**必须**跟着走，否则淡出带与基线会锚到"岛体矩形的边"上。
        val topEdge = if (polar != null) polar.cy - polarOuterR else bounds.y.toFloat()
        val bottomEdge = if (polar != null) polar.cy - polarInnerR else (bounds.y + bounds.height).toFloat()
        val halfSpan = if (polar != null) {
            // 极坐标下"单侧最大长度" = **可用径向长度** = `outerR − innerR`
            // （★ 2026-10-11 BUG-166 修正：原为 `polar.maxLength` —— 那是**外半径**，
            //   在 `innerRadius > 0` 时比可用长度大 `innerRadius`，会把柱子按比例撑长
            //   并戳出画布。`RADIAL`（内半径 0）下两者恰好相等 ⇒ 看不出问题。）
            val span = (polar.maxLength - polarInnerR).coerceAtLeast(1f)
            // ★★★ 2026-10-12（**BUG-167**）：★ **双峰时取【一半】** —— 否则上下两半
            //   各占满整个范围 ⇒ 合起来是**两倍** ⇒ 一定戳出外半径（真机观感 = "齿轮"）✗
            // ★ 取一半 ⇒ 上下一合**恰好**铺满 `[innerR, outerR]` ✓
            //   （★ 与直角坐标的 `minOf(axis − topEdge, bottomEdge − axis)` **同一语义**：
            //     那边也是"轴到边的一半"，这里只是把"边"换成"内/外半径"）✓
            // ★★★ 2026-10-17（**BUG-222**）：★ 双峰那半 = **共用出口**
            //   `polarSymmetricHalfLengthPx(spec, 1f)`（预览 `IslandPreviewPane` 同一个函数，
            //   返回 `((maxLength − innerR)/2).coerceAtLeast(1f)`，与上式 `span/2` 同值）。
            //   ★ 根因复盘：**预览**那份曾把 `half` 取成**外半径**（`(h/2)·env`，而
            //     `h/2` = 插槽方形半边长 = 外半径）⇒ 柱面整体外移 ⇒ 脱出岛体。
            //   ★★ 教训：两边各写一遍**必然再漂移** ⇒ 收口到唯一落点，本行就是那个落点 ✓
            //   ⚠️ 单位 value 的半长在这里取；`value` 由调用方乘
            //     （`barHeight = value * halfSpan`）。
            if (symmetric) polarSymmetricHalfLengthPx(polar, 1f) else span
        } else if (symmetric) {
            minOf(axis - topEdge, bottomEdge - axis).coerceAtLeast(1f)
        } else {
            bounds.height.toFloat()
        }
        val baseY = if (symmetric) axis else bottomEdge

        // ── ★★ 2026-09-23：淡出带的**锚点与宽度**（整帧算一次，不逐柱算）───────
        //
        // 用户原话（逐字）：「关于顶端淡出，**就不能只作用于顶端吗？非得从根部起算?**」
        // 以及「**原来在每根柱顶设置淡化导致效能开销大，卡顿感明显故改掉了**」。
        //
        // 故淡出带锚在**柱子的活动范围**的两端，而不是每根柱自己的尖端：
        //   · 非对称档：柱子活在 `[topEdge, bottomEdge]`（从底边长上去）；
        //   · 对称档  ：柱子活在 `[axis − halfSpan, axis + halfSpan]`
        //     —— ⚠️ 这里**必须**用 `axis ∓ halfSpan` 而不是 `topEdge`/`bottomEdge`：
        //     轴偏心时 `halfSpan < bounds.height / 2`，柱子够不到带的另一条边，
        //     若锚在带边，短的那一侧**永远没有淡出**（两端观感不对称）。
        //
        // 宽度取 [spectrumFadeBandPx]`(halfSpan)`：`min(单侧最大高度 × 0.30, 6px)`。
        // ⚠️ 整帧恒定 ⇒ 笔刷缓存键里**不含**它 ⇒ 键空间只剩 `(颜色, 方向)`。
        // ⚠️ 于是"顶端低于 `淡出带` 的矮柱"整根全实心 —— 这是"不逐柱算"的取舍，
        //    已在 [fillFadedPiece] 的 KDoc 与 `spectrumFadeStartFraction` 里写明。
        // ⚠️ 2026-09-23 **回退**：原先这里整帧算一次 `fadeBand` 与两个 `fadeAnchor*`，
        //    锚在**带的边**。实测那版**没有换到任何性能**（缓存键空间 168 ≤ 容量 192，
        //    同样 0 支/帧），却带来一个可见后果：**顶部够不到淡出带的矮柱整根不淡**。
        //    故锚点与带宽都改回**逐片**（锚 = 这一片自己的外端，带 = `spectrumFadeBandPx(片高)`）。
        //    用户原话：「关于顶端淡出，**就不能只作用于顶端吗？非得从根部起算?**」

        // ── ★★ 2026-09-21：**与像素地板一致的静音判据**（真机"中线粗线"的正因）──
        //
        // 用户原话：「**无声时（包括暂停瞬间频谱坍缩），上下对称双峰中线仍有一条粗线**」
        // —— 他说"**还是老问题**"，即上面那条 `peak <= SILENCE_PEAK_EPS` 没修掉它。
        //
        // ## 为什么没修掉
        //
        // `SILENCE_PEAK_EPS = 1e-6f`（归一化柱高）—— 而它的 KDoc 写着「取值 `0.004f` ≈ -48 dBFS」，
        // **注释与代码差了 4000 倍**（`1e-6` 比 `0.004` 小 4000 倍）。这是个**笔误级**的常量，
        // 后果是：**静音门几乎永不触发** —— 只要 `displayed` 衰减到 `1e-6` 以上（那要很久），
        // 每根柱子就仍然按 `MIN_BAR_HEIGHT = 2px` 的地板画出来；
        // 而**对称跨轴**时地板是 `2 × 2 = 4px` 高 ⇒ **中线一条 4px 粗线**。
        //
        // ## 正确的判据：**问"画出来会不会只是地板"**，而不是问"数值是不是零"
        //
        // 归一化柱高 `peak` 对应的像素高度是 `peak × halfSpan`；
        // 若它 `<= MIN_BAR_HEIGHT`，那么**这一帧画出来的每一根柱子都只会是地板** ——
        // 视觉上就是"一条线"，而不是"频谱"。故直接跳过。
        //
        // ⚠️ 这样写还有一个好处：判据**随几何自动缩放** ——
        //    带子高时门限小、带子矮时门限大，不需要为不同布局各调一个常量。
        // ⚠️ 原来的 `SILENCE_PEAK_EPS` 检查**保留**（它在 `halfSpan` 之前，挡"复用缓冲残值"），
        //    两条判据是"绝对零"与"视觉地板"两层，互不替代。
        // 判据抽成**公开纯函数**（见 [isSpectrumVisuallySilent] 的 KDoc）。
        if (isSpectrumVisuallySilent(peak, halfSpan, MIN_BAR_HEIGHT)) return

        // ── ★★ 2026-09-27：**实心折线形态**（用户诉求 ①）────────────────────────
        //
        // 用户原话（逐字）：「**实心频谱（间距 0 时条太粗 ⇒ 想要"折线边界"）**」。
        // 口径（用户 2026-09-27 拍板）：「**保留滑杆，0 触发折线**」——
        // 即 `solid`（= 间距拖到 0）**同时**触发"实心"与"折线"，老用户升级**逐像素不变**。
        //
        // ## 为什么必须是**独立分支**（而不是塞进下面的逐柱循环）
        //
        // 逐柱 `fill` 在缝为 0 时，相邻柱的公共边界会被**两次 `SRC_OVER` 合成**
        // ⇒ `alpha < 1` 的淡竖线（`spectrumBarRectX` 的 KDoc 详述）。折线**一次 `fill`**
        // 从构造上消除它 —— 但代价是**必须跳出逐柱循环**（折线是一条路径，不是 count 条）。
        //
        // ## 与峰值帽的关系（★ 2026-09-27 用户拍板："先登记，真机后再定"）
        //
        // 折线档**直接 return** ⇒ 峰值帽**不画**。这是**有意的不改**：
        // "一根柱的帽"在连续折线里没有对应物，而用户要求**先登记、真机后再定**。
        // ⚠️ **不得声称"折线形态下峰值帽正常"** —— 本批它**未实现、未验证**。
        //
        // ## 与 `tipFade` 的关系（★ 用户拍板："先做无淡出基线，再叠淡出"）
        //
        // 折线分支**只走无淡出路径**（纯色 `fill`）。`tipFade` 在折线档下
        // **暂不生效**（下一批的第二步：折线 + 淡出带）。
        // ⚠️ 这里**不静默忽略** —— 见下面 `if (tipFade)` 的显式处理。
        // ⚠️ ★★★ 2026-10-12（**BUG-171**）：★ **双峰档【不走】折线分支** ✗
        //
        // ## 用户报障（逐字）
        //
        // > 「**当频谱条间距为0时，上下双峰失效，主频谱变回默认浅绿色外观。**」
        //
        // ## 根因：折线是一条**单次 `fill` 的路径**，它缺两样东西
        //
        // | 缺什么 | 后果 |
        // |---|---|
        // | ★★ **不接逐柱取色**（`argbForBar`） | ★ 整条折线一个颜色 ⇒ 用户设的 `cover_flow` / `level` **全丢** ⇒ **"变回默认浅绿色"** ✗ |
        // | ★ **不接峰值帽 / 淡出**（见下 KDoc，本就有意跳过） | ★ 观感与逐柱档不一致 ✗ |
        //
        // ★ 折线存在的**唯一理由**是"缝为 0 时相邻柱公共边界被两次 `SRC_OVER` 合成
        //   ⇒ `alpha < 1` 的淡竖线"（见下 KDoc）—— ★ 那是**单色档**的问题 ✓
        // ★★ 而**双峰档**要的是"上下各一份 + 逐柱取色" ⇒ ★ 折线给不了 ⇒
        //   **双峰时让给逐柱路径**（`solid` 已把 `gap` 设成 0 ⇒ 逐柱路径照样实心 ✓）
        //
        // ★ 判据：`solid && !symmetric` ⇒ 折线（单色实心，消缝）；否则 ⇒ 逐柱 ✓
        //   ★ 极坐标两档**恒 `symmetric = false`**（见 `IslandPreviewPane` 的说明）⇒ 不受影响 ✓
        if (solid && !symmetric) {
            // ★★★ 2026-10-11（**BUG-166 · 补**）：**极坐标实心档**走**环带轮廓** ——
            //   下面的直角折线生成器**不是极坐标感知的**（它按 `x0 / slotPx / baseY` 铺点）
            //   ⇒ 原样走它会画出"顶部一块半圆"（用户报障「频谱条太丑」的一个成因）✗
            //   ★ 判据：`polar != null` ⇒ 换用 `spectrumPolarPolylinePoints`（闭合环带：
            //     外缘跟频谱、内缘 = 内半径）⇒ 观感 = **连续环 / 连续放射**
            //     （= 权威参考 Banger.Show `Circle` 的默认 `Smooth = 开`）✓
            //   ⚠️ 本档**不接** `paintForBar`（顶端淡出）：折线档本来就是"只走无淡出路径"，
            //     而极坐标的淡出带需要**径向**渐变（另一支笔刷）—— **未实现，如实登记** ✓
            if (polar != null) {
                val needP = 2 * count * 2
                if (polylineScratch.size < needP) polylineScratch = FloatArray(needP)
                if (polylineHeightsScratch.size < count) polylineHeightsScratch = FloatArray(count)
                for (i in 0 until count) {
                    polylineHeightsScratch[i] =
                        (stretchAroundMid(values[i], bounceScale) * halfSpan)
                            .coerceAtLeast(MIN_BAR_HEIGHT)
                }
                val np = spectrumPolarPolylinePoints(
                    spec = polar, count = count,
                    heights = polylineHeightsScratch, out = polylineScratch,
                )
                if (np > 0) {
                    val jointArcPolar =
                        spectrumPolylineCornerPx(slot, MIN_BAR_HEIGHT, barCorner) * barShapeRatio
                    if (spectrumPolylinePath(polylinePath, polylineScratch, np, jointArcPolar)) {
                        fillTracked(g, polylinePath)
                    }
                }
                return
            }
            // 顶点列容器：非对称 `(count + 3) × 2` 个 float
            // （**左下角 + count 个柱顶 + 末根 hold-last 顶点 + 右下角**）。
            // ★★ 2026-09-28：**对称档要两倍** —— 下半部分是上半轮廓的倒序镜像
            //    （见 [spectrumPolylinePoints] 的 `mirrorAxisY`）。
            // ⚠️ 2026-09-27 修正**两处**几何缺陷（都不是"给少了容器"那么简单）：
            //    ① 点列漏了**左下角** ⇒ `closePath()` 从右下角斜穿整幅图，填充退化成三角形；
            //    ② 漏了**末根的 hold-last 顶点** ⇒ 末根顶边一路斜到基线，**末根退化成三角形**
            //       （24 柱 640px 下是右侧 27px 的楔形）。
            //    两者**都不报错**，且纯函数断言若按实现写会全绿 —— 由 `SpectrumPolylineProbe` §③ 抓到。
            val need = if (symmetric) (2 * count + 6) * 2 else (count + 3) * 2
            if (polylineScratch.size < need) polylineScratch = FloatArray(need)
            if (polylineHeightsScratch.size < count) polylineHeightsScratch = FloatArray(count)
            // 归一化柱高 → 像素高度（与逐柱循环**同一个**公式，只是提前整批算好）。
            for (i in 0 until count) {
                polylineHeightsScratch[i] = (stretchAroundMid(values[i], bounceScale) * halfSpan)
                    .coerceAtLeast(MIN_BAR_HEIGHT)
            }
            // ★★ 2026-09-28 用户报障（逐字）：「**折线频谱(间距0)下上下对称双峰没有下半部分**」。
            //    根因：本分支原样整段 `return`，而 `spectrumPolylinePoints` 只生成**单侧**轮廓
            //    ⇒ 对称档的下半**从未被画出来**。
            //    修法：对称时把「对称轴」传给点列生成器，由它**追加一份倒序镜像**，
            //    整个上下双峰仍是**一个闭合轮廓、一次 `fill`**（不破坏折线档
            //    "避免相邻柱双重 SRC_OVER 合成出淡竖线"的初衷）。
            //    ⚠️ `baseY` 在对称档已 = `axis`（见上文 `baseY` 的计算），
            //       故镜像轴就是 `baseY` 本身 —— 不另取参数，避免两处定义不一致。
            val n = spectrumPolylinePoints(
                x0 = x0, count = count, slotPx = slot,
                heights = polylineHeightsScratch, baseY = baseY,
                out = polylineScratch,
                mirrorAxisY = if (symmetric) baseY else Float.NaN,
            )
            if (n > 0) {
                // ── ★★ 2026-09-28 用户报障：「折线频谱的**顶端淡出未实现**（开启后只是不透明度下降）」
                //
                // ## 根因：淡出带锚在**带的顶边**，而不是**折线的顶边**
                //
                // 原实现 `paintForBar(baseY - halfSpan, baseY)` 把笔刷铺在**整带**上，
                // 而 `spectrumTipFadePaint` 的淡出带恒在**传入区间的最外端**
                // （= `baseY − halfSpan`，即"带能达到的最高处"）。
                // 折线的实际峰高通常远低于 `halfSpan` ⇒ 整条轮廓**全都落在实心区**
                // ⇒ 观感上就是"没淡出"。用户看到的"只是不透明度下降"来自 `alphas.unplayed`
                // （那是**整段**压暗，不是顶端淡出）—— 两者被混为一谈。
                //
                // ## 修法：把淡出带锚到**这一帧折线自己的最高点**
                //
                // 取 `topPx = baseY − max(heights)`（本帧轮廓的真实顶边），笔刷区间
                // `[topPx, baseY]` ⇒ 淡出带落在 `[topPx, topPx + min(带高×0.30, 6px)]`
                // = **轮廓顶端那一小段**，正是用户要的"只作用于顶端"。
                // ★ 与柱状档**同一条规则**：都用 `spectrumTipFadeFractions`
                //   （比例 0.30 / 上限 6px 只有一处定义），不另起一套。
                // ⚠️ 只取**正有限**高度；全 0（静音）时 `maxH` 为 0 ⇒ 退化为区间高度 1px，
                //   不会构造出零高渐变（`spectrumTipFadePaint` 内部还有 `coerceAtLeast(1f)` 兜底）。
                // ★★★ 2026-10-03（㉒ 部分实现收口）：**折线的"尖端圆角"也要传给淡出带**。
                //   折线没有"柱"，它的尖端是**折点圆角** `jointArc` —— 语义上就是"圆头半径"。
                //   ⚠️ 原实现只传 `(topY, bottomY)` ⇒ `spectrumTipFadeFractions` 的
                //      `cornerRadiusPx` 取默认 `0f` ⇒ **形状项在这一档不生效**（纪律 15 同族：
                //      同一份数据两个收敛点，只接了一处）。
                //   ★ `jointArc` 的定义因此**上移到本调用之前**（它原本在下面几行）。
                val jointArc = spectrumPolylineCornerPx(slot, MIN_BAR_HEIGHT, barCorner) *
                    barShapeRatio
                if (paintForBar != null) {
                    var maxH = 0f
                    for (i in 0 until count) {
                        val hv = polylineHeightsScratch[i]
                        if (hv.isFinite() && hv > maxH) maxH = hv
                    }
                    g.paint = paintForBar(baseY - maxH, baseY, jointArc)
                }
                // ★★ 2026-09-27：② 折点圆角**接线**。半径走折线专用纯函数
                //    （上限与柱状档同源 = `MIN_BAR_HEIGHT`，`SQUARE` 档恒 0）。
                //    ⚠️ 不接线时 `bar_corner` 在实心档是**死设置**（`ROUND` 是默认值！）——
                //    详见 [spectrumPolylinePath] 的 KDoc。
                // ★★ 2026-09-28 补：**乘 `barShapeRatio`**（= `spectrum.bar_shape` 的换算）。
                //    ⚠️ 原记「② 接线完成」—— 实为**只接了一半**：折线分支只读 `barCorner`，
                //    而 `barCorner` 在**真机侧从无调用方传值**
                //    （`grep -rn "barCorner *=" main/` 只有本文件内 3 处**转发**）
                //    ⇒ 恒为 `SpectrumBarCorner.DEFAULT = ROUND`；它对应的键
                //    `spectrum.bar_corner` 在 `SettingRegistry` 里**根本没注册**（幻影键）。
                //    ⇒ 用户能改的 `spectrum.bar_shape`（方角）在实心档**完全无效**。
                //    取证：2026-09-28 拉 R9 目视清单时按"用户路径"反查发现（BUG-138 / N10）。
                //    与柱状分支（`spectrumBarCornerPx(...) * barShapeRatio`）**同一条规则**。
                // `n` = 写入的点数（含两个基线角点与末根 hold-last）⇒ 一次装完，
                // `closePath()` 由 [spectrumPolylinePath] 内部完成。
                // ⚠️ **不要**在这里手写 `moveTo/lineTo` —— 那正是首版"折点恒尖角"的成因。
                if (spectrumPolylinePath(polylinePath, polylineScratch, n, jointArc)) {
                    fillTracked(g, polylinePath)
                }
            }
            return
        }

        for (i in 0 until count) {
            // ★★★ 2026-10-04：极坐标下**逐柱旋转**到自己的扇区。
            //   ⚠️ 变换必须在**每柱绘制后恢复**（循环末尾）—— 否则会累积旋转。
            //   ⚠️ 角度走**公开纯函数** `polarAngleDeg`（探针可断言），不在这里手算。
            val polarSaved = if (polar != null) g.transform else null
            if (polar != null) {
                g.rotate(
                    Math.toRadians(polarAngleDeg(polar, i, count).toDouble()),
                    polar.cx.toDouble(),
                    polar.cy.toDouble(),
                )
            }
            // 归一化柱高 → 跳动幅度拉伸 → 像素高度。见 KDoc 关于顺序的说明。
            val value = stretchAroundMid(values[i], bounceScale)
            // ★★ 2026-10-02（`color_mode = level`）· 2026-10-11（`cover_flow`）：**本柱的颜色** ——
            //   `argbForBar` 为空 ⇒ 退回整带同色（= 改动前行为，逐像素不变）。
            //   ⚠️ `base` 传本趟的 `fillArgb`（底图趟 = 未播放色 / 进度染色趟 = 已播放色）。
            //   ⚠️ `count` 传本帧**实际**柱数（绘制侧真值 ⇒ 调用方不必重算）。
            val barArgb = argbForBar?.invoke(i, count, value, fillArgb) ?: fillArgb
            // 高度下限：见 KDoc 关于「无信号时不能完全消失」。
            val barHeight = (value * halfSpan).coerceAtLeast(MIN_BAR_HEIGHT)
            // ★ 2026-09-22：水平位置改由**公开纯函数**给出（见 [spectrumBarRectX]）——
            // 实心档在那里做整像素吸附（否则相邻柱的公共边界落在像素内部，
            // 两次 `SRC_OVER` 填充合成出 alpha≈198/255 的淡竖线）。
            // ⚠️ 峰值帽循环用的是**同一个**函数，不要再手写一遍 `x0 + i*slot + gap/2`。
            val (leftSlot, barW) = spectrumBarRectX(x0, i, slot, gap, barWidth, solid)
            // ★★★ 2026-10-04：极坐标下所有柱**共用同一个 x**（圆心正上方）——
            //   旋转把它们分到各自扇区。★ 复用 `barW`（它来自同一个纯函数 ⇒ 宽度语义不变）。
            val left = if (polar != null) polar.cx - barW / 2f else leftSlot
            val tipTop = baseY - barHeight
            val tipBottom = if (symmetric) baseY + barHeight else baseY
            // ★★★ 2026-10-17（**BUG-226**）：**逐段取色** —— 插槽小频谱的二维碎色块。
            //
            // ## 为什么在这里分支（而不是在调用方画多遍）
            //
            // ★ 柱体形状（胶囊圆头 / 根部直角 / 极坐标旋转）全部由本函数负责；
            //   调用方若自己再画一遍，就是**第二份几何**（本项目最忌讳的形态）✗
            // ⇒ 分段必须发生在**同一个循环、同一套几何**里 ✓
            //
            // ⚠️ 只在 `!symmetric` 生效：对称双峰的不变量是"上下两片在轴处无缝"，
            //   分段会让接缝重新出现（见下方 `symmetric` 分支的 KDoc）。
            val segmentFn = argbForBarSegment
            if (segmentFn != null && !symmetric) {
                val segs = CAMO_SEGMENT_ROWS
                val totalH = tipBottom - tipTop
                val oldPaint = g.paint
                var segTop = tipTop
                for (k in 0 until segs) {
                    val segBottom =
                        if (k == segs - 1) tipBottom else tipTop + totalH * (k + 1) / segs
                    val segArgb = segmentFn.invoke(i, count, k, segs, fillArgb)
                    if (k == 0 && tipFade) {
                        // 首段：保留"顶端淡出 + 胶囊圆头"（与整柱路径同观感）
                        fillFadedPiece(
                            g, left, barW, arc, segTop, segBottom,
                            segArgb, brushes, fadeAtBottom = false,
                            roundTop = true, roundBottom = false,
                            fadeAnchor = segTop,
                            fadeBandPx = spectrumFadeBandPx(segBottom - segTop, arc),
                        )
                    } else {
                        // 其余段（以及 `fade_top = false` 时的首段）：**实心**
                        g.color = java.awt.Color(segArgb, true)
                        barSegmentPath(
                            scratchBarPath, left, segTop, barW, segBottom - segTop, arc,
                            roundTop = (k == 0), roundBottom = false,
                        )
                        fillTracked(g, scratchBarPath)
                    }
                    segTop = segBottom
                }
                // 恢复画笔：`fillFadedPiece` 与 `g.color` 都会改 `g.paint`，
                // 下一柱/下一趟若走"整带笔刷"路径会被污染（同 `paintForBar` 的纪律）。
                g.paint = oldPaint
            } else if (tipFade) {
                // ── ★★ 2026-09-23：**顶端淡出**走"分片 + 缓存的本地坐标笔刷" ─────────
                //
                // ## 这条路线的**两版**（不要混淆，用户否掉的是前一版）
                //
                // | 版本 | 笔刷的纵向范围 | 每帧新建渐变 |
                // |---|---|---|
                // | 最初 | 绝对坐标 `0f, tipTop → 0f, tipBottom`（**每柱**） | **= 柱数**（91～1024） |
                // | 2026-09-22 | 本地坐标，按**片高**算淡出带（21 档量化） | 常数（缓存） |
                // | 现在（2026-09-23） | 本地坐标，按**单侧带高**算淡出带（**整帧一个值**） | **= 缓存未命中数（稳态 0）** |
                //
                // 用户原话（逐字）：
                // > 「**原来在每根柱顶设置淡化导致效能开销大，卡顿感明显故改掉了，
                // >   你现在又让他回归，就不能探究其他路线吗？**」
                // 他否掉的是**第一版**（"每根柱顶"= 每柱一支绝对坐标渐变）。
                // 本轮把淡出带**从片高里解耦出来**（见 `fadeBand` 的推导）——
                // 于是同一帧里所有柱子共用**同一支**笔刷，且淡出**只在顶端那一小段**。
                //
                // 对称双峰必须**拆两片**（各以自己的那一端淡出），且两片
                // **不重叠、无缝** —— 见 [spectrumBarPieces] 的不变量论证。
                if (symmetric) {
                    val n = spectrumBarPieces(tipTop, tipBottom, true, pieceScratch)
                    if (n == 2) {
                        // 上片：淡出锚在**柱子的活动范围上沿**（`axis − halfSpan`）、内端（轴）直角
                        fillFadedPiece(
                            g, left, barW, arc, pieceScratch[0], pieceScratch[1],
                            barArgb, brushes, fadeAtBottom = false,
                            roundTop = true, roundBottom = false,
                            fadeAnchor = pieceScratch[0], fadeBandPx = spectrumFadeBandPx(pieceScratch[1] - pieceScratch[0], arc),
                        )
                        // 下片：淡出锚在**活动范围下沿**、内端（轴）直角
                        fillFadedPiece(
                            g, left, barW, arc, pieceScratch[2], pieceScratch[3],
                            barArgb, brushes, fadeAtBottom = true,
                            roundTop = false, roundBottom = true,
                            fadeAnchor = pieceScratch[3], fadeBandPx = spectrumFadeBandPx(pieceScratch[3] - pieceScratch[2], arc),
                        )
                    }
                } else {
                    fillFadedPiece(
                        g, left, barW, arc, tipTop, tipBottom,
                        barArgb, brushes, fadeAtBottom = false,
                        // ★ 2026-09-26 用户要求（逐字）：
                        //   「频谱条的圆角我想要的是**胶囊那种大 R 角**，不是圆角矩形。
                        //     注意**根部不要应用圆角**。」
                        //   ⇒ 顶端保留胶囊圆头（`arc` 经 `barSegmentPath` 夹到 `min(w,h)/2`），
                        //     **根部（贴基线那端）改直角**。
                        //   **原记**：两端都圆（理由"与改动前的 `RoundRectangle2D` 逐像素一致"）——
                        //     那条理由只保证"新增圆角维度时零回归"；用户现在**明确不要**根部圆角。
                        roundTop = true, roundBottom = false,
                        fadeAnchor = tipTop, fadeBandPx = spectrumFadeBandPx(tipBottom - tipTop, arc),
                    )
                }
            } else {
                // ── `fade_top = false`：实心（用**当前** `g.paint`：`paintOverride` 或本色）──
                //
                // 对称：上下画成**一个整体**，而不是两个在轴处相接（2026-09-18 修）。
                //
                // 用户原话：「**双峰频谱中线有一条缝，未完全闭合**」。
                //
                // ## 根因：`RoundRectangle2D` 把**四个角都倒圆**
                //
                // 原实现上下各画一个矩形（各高 `barHeight`，在 `baseY` 处**相接**）。
                // 而圆角矩形在**左右边缘附近**会向内退 `arc/2`：
                //   · 上半份的底边退到 `baseY - arc/2`
                //   · 下半份的顶边退到 `baseY + arc/2`
                // 于是相接处合起来是一条高约 `arc` 的**腰缝**。
                //
                // **为什么它在频谱上特别明显**：`arc = barWidth.coerceAtMost(MIN_BAR_HEIGHT)`，
                // 而频谱条本来就细（默认间距 7px 下条宽约 4.9px）→ `arc` 几乎等于**整个条宽**
                // → 缝的相对宽度极大。条越细越明显，这正是本项设置"窄岛自动变细"之后
                // 更容易被注意到的原因。
                //
                // ## 修法：画一个跨轴的矩形
                //
                // 高 `2 * barHeight`、纵向范围 `[baseY - barHeight, baseY + barHeight]` ——
                // **轴落在它的内部**，那里根本没有角，缝自然消失；
                // 圆角只留在**上下两个尖端**，而那正是"一根柱子"该有的样子
                // （与非对称模式上端的圆头一致）。
                //
                // ⚠️ `arc` 必须按**新高度**再夹一次：`RoundRectangle2D` **不会**自己收敛弧长，
                //    `arc > 高度` 时画出的形状不可预期（Java2D **不报错**，只是结果怪）。
                //    `barHeight` 可能小到 [MIN_BAR_HEIGHT]（2px），故这一步是必要的 ——
                //    少了它，"无信号时的矮条"会退化成两个尖刺。
                // ★ 2026-10-03（㉒ 收口）：`arcTall` **上移到调用之前** ——
                //   它原在下面的 `if (symmetric)` 分支里，而本行（逐柱笔刷）也要用它。
                //   `arc` 必须按**新高度**再夹一次：`RoundRectangle2D` **不会**自己收敛弧长，
                //   `arc > 高度` 时画出的形状不可预期（Java2D **不报错**，只是结果怪）。
                //   `barHeight` 可能小到 [MIN_BAR_HEIGHT]（2px），故这一步是必要的 ——
                //   少了它，"无信号时的矮条"会退化成两个尖刺。
                val arcTall = arc.coerceAtMost(barHeight * 2f)
                // legacy：`fade_top = false` 时调用方仍可自己给一支逐柱笔刷。
                if (paintForBar != null) g.paint = paintForBar(tipTop, tipBottom, arcTall)
                if (symmetric) {
                    // ★★★ 2026-10-13（**BUG-167**）：`RoundRectangle2D` 的 `arcw`/`arch` 是
                    //   **弧的直径**（角半径 = `arcw / 2`），而本文件里的 `arc` **一律是半径**
                    //   （非对称路径走 `barSegmentPath`，那里把 `arc` 当半径并夹到 `min(w,h)/2`
                    //   ⇒ "大 R 即胶囊"）。★ 原实现直接把 `arcTall` 当直径传 ⇒
                    //   **角半径只有应有的一半** ✗
                    //   ⇒ 观感 = "**圆角矩形**"而不是用户 2026-09-26 明确要的
                    //     「**胶囊那种大 R 角**」（原话逐字）。
                    //   ★ 症状在**细柱**上最刺眼：5 条小频谱柱宽 3.92 ⇒ 应得半径 1.96（胶囊），
                    //     实际只有 0.98（≈ 直角）⇒ 用户 2026-10-13 报「使用圆角胶囊频谱条外观」✓
                    //   ⇒ 修法：**乘 2** 换成直径（`arcTall` 已按高度夹过，故 `2·arcTall` 安全）。
                    scratchRect.setRoundRect(
                        left.toDouble(),
                        tipTop.toDouble(),
                        barW.toDouble(),
                        (tipBottom - tipTop).toDouble(),
                        (arcTall * 2f).toDouble(),
                        (arcTall * 2f).toDouble(),
                    )
                    fillTracked(g, scratchRect)
                } else {
                    // ★ 2026-09-26 用户要求：**根部不应用圆角** ⇒ 顶端胶囊圆头 + 根部直角。
                    // 走 `barSegmentPath`（自带 `arc.coerceIn(0f, min(w,h)/2)` 夹取 ⇒ 大 R 即胶囊）。
                    barSegmentPath(
                        scratchBarPath, left, tipTop, barW, tipBottom - tipTop, arc,
                        roundTop = true, roundBottom = false,
                    )
                    fillTracked(g, scratchBarPath)
                }
            }
            // ★★★ 2026-10-04：**恢复变换**（极坐标）。
            //   ⚠️ 必须在循环体**末尾**恢复 —— 漏掉会让旋转**累积**到下一柱
            //   （本项目已栽过同类：边界状态没在 early return 之前收干净，见 BUG-145）。
            if (polarSaved != null) g.transform = polarSaved
        }

        // ── 峰值保持的「帽」（2026-09-20，`animation.quality = ULTRA`）─────────
        //
        // ## 为什么是**单独一趟**（而不是塞进上面的柱子循环）
        //
        // 上面的循环里有"对称 / 非对称"与"逐柱笔刷"两层分支；把帽塞进去，
        // 每个分支都要再判一次"这一柱有没有帽"，分支数翻倍。
        // 单独一趟只多一次 `for`，而几何参数（`slot`/`gap`/`barWidth`/`baseY`/`halfSpan`）
        // 都是上面算好的局部值，**不重复计算**。
        //
        // ## 为什么画在柱子**之后**
        //
        // 柱高会追上来又落下去；帽要**一直可见**，故必须叠在上面。
        //
        // ## 帽的**下沿贴住峰值高度**（不是上沿）
        //
        // 这样"帽的底边"正好就是峰值读数 —— 柱顶追上来时看起来是"柱子顶到了帽"，
        // 而不是"帽浮在柱子上方一段距离"。少 `capH` 的偏移会让读数**系统性偏高**。
        if (peaks.size == values.size) {
            val capH = PEAK_CAP_HEIGHT.coerceAtMost(halfSpan)
            val arcCap = arc.coerceAtMost(capH * 2f)
            for (i in 0 until count) {
                val pv = peaks[i].coerceIn(0f, 1f)
                // 与柱子同一条"无信号"判据：低于阈值不画 ——
                // 否则静音时会在底部留一排小点（会被读成"还有信号"）。
                if (pv <= SILENCE_PEAK_EPS) continue
                // ★★★ 2026-10-04：极坐标下峰值帽**同样逐柱旋转**。
                //   ⚠️ 位置在 `continue` **之后** ⇒ 跳过的柱不会留下未恢复的变换。
                val polarSavedCap = if (polar != null) g.transform else null
                if (polar != null) {
                    g.rotate(
                        Math.toRadians(polarAngleDeg(polar, i, count).toDouble()),
                        polar.cx.toDouble(),
                        polar.cy.toDouble(),
                    )
                }
                val h = pv * halfSpan
                // ★★ 2026-10-02（`color_mode = level`）：**帽按峰值取色** ——
                //   用 `pv`（该柱的**峰值**）而不是 `values[i]`（当前值）⇒
                //   "帽"与"柱顶"在安静回落时**颜色会分开**，那正是峰值保持的语义
                //   （帽记的是"刚才到过多高"）。
                val capArgb = argbForBar?.invoke(i, count, pv, fillArgb) ?: fillArgb
                // ★ 与柱体循环**同一个函数**（见 [spectrumBarRectX]）——
                // 帽必须与它下面那根柱**同宽同左边缘**，否则实心档下帽会错半像素、
                // 看起来像"柱子顶上盖了一块略偏的砖"。
                val (leftSlotCap, barW) = spectrumBarRectX(x0, i, slot, gap, barWidth, solid)
                // ★★★ 2026-10-04：极坐标下帽与柱体**共用同一个 x**（圆心正上方）——
                //   ★ 这正是「帽必须与它下面那根柱同宽同左边缘」那条不变量的极坐标版。
                val left = if (polar != null) polar.cx - barW / 2f else leftSlotCap
                val top = (baseY - h - capH).coerceAtLeast(topEdge)
                if (tipFade) {
                    // 帽的**外端**淡出（上帽锚活动范围上沿、下帽锚下沿）——
                    // 与柱体**同一条**"淡出带锚在带的边"的规则，故帽不会显得比柱子"硬"。
                    fillFadedPiece(
                        g, left, barW, arcCap, top, top + capH,
                        capArgb, brushes, fadeAtBottom = false,
                        roundTop = true, roundBottom = true,
                        fadeAnchor = top, fadeBandPx = spectrumFadeBandPx(capH, arc),
                    )
                } else {
                    scratchRect.setRoundRect(
                        left.toDouble(), top.toDouble(),
                        barW.toDouble(), capH.toDouble(),
                        arcCap.toDouble(), arcCap.toDouble(),
                    )
                    if (paintForBar != null) g.paint = paintForBar(top, top + capH, arcCap)
                    fillTracked(g, scratchRect)
                }
                if (symmetric) {
                    // 对称模式：轴下方镜像一份（峰值在轴下 `h` 处，帽向下留 `capH`）。
                    val topDown = (baseY + h).coerceAtMost(bottomEdge - capH)
                    if (tipFade) {
                        fillFadedPiece(
                            g, left, barW, arcCap, topDown, topDown + capH,
                            capArgb, brushes, fadeAtBottom = true,
                            roundTop = true, roundBottom = true,
                            fadeAnchor = topDown + capH, fadeBandPx = spectrumFadeBandPx(capH, arc),
                        )
                    } else {
                        scratchRect.setRoundRect(
                            left.toDouble(), topDown.toDouble(),
                            barW.toDouble(), capH.toDouble(),
                            arcCap.toDouble(), arcCap.toDouble(),
                        )
                        if (paintForBar != null) g.paint = paintForBar(topDown, topDown + capH, arcCap)
                        fillTracked(g, scratchRect)
                    }
                }
                // ★★★ 2026-10-04：恢复变换（极坐标）。与柱体循环同一条规则。
                if (polarSavedCap != null) g.transform = polarSavedCap
            }
        }
    }

    /**
     * 重采样用的**复用缓冲**（见 [resampleBands] 的分配纪律）。
     *
     * 与 [scratchRect] / [scratchPath] 同一约定：**只在 EDT 上使用**
     * （绘制路径天然在 EDT）。本对象只被词岛的 `paintComponent` 调用。
     *
     * 只增不减：条数上限是 [SPECTRUM_MAX_BARS]，故缓冲最多扩到那么大一次。
     */
    private var barScratch: FloatArray = FloatArray(0)

    private fun barScratch(n: Int): FloatArray {
        if (barScratch.size < n) barScratch = FloatArray(n)
        return barScratch
    }

    /**
     * 画**一片**柱体（或峰值帽）—— 淡出笔刷走缓存，**零逐柱分配**。
     *
     * ## ★★ 2026-09-23：淡出锚点从"这一片的尖端"改成"**带的边**"
     *
     * ### 用户原话（逐字，两条要一起读）
     *
     * > ① 「关于顶端淡出，**就不能只作用于顶端吗？非得从根部起算?**」
     * > ② 「**原来在每根柱顶设置淡化导致效能开销大，卡顿感明显故改掉了，
     * >   你现在又让他回归，就不能探究其他路线吗？**」
     *
     * ### 改动前：淡出带 = `min(片高 × 0.30, 6px)`，锚在**这一片自己的尖端**
     *
     * 于是"淡出带有多宽"是**逐片**算的 —— 好处是矮柱也有一点点末端软化；
     * 代价是笔刷的缓存键里必须带上"淡出带宽度"（21 档量化）。
     * ② 说的正是这条路线的**上一版**（每片一支绝对坐标渐变，每帧 N 个对象）——
     * 那一版已被缓存修好，但用户明确表示**不要这条路线**。
     *
     * ### 现在：淡出带 = [spectrumFadeBandPx]`(单侧带高)`，锚在**带的边**
     *
     * 锚点由调用方按方向给出（[fadeAnchor] = 带顶 / 带底），**与这一片自己的
     * `top` / `bottom` 无关**。于是：
     *   · 淡出带宽度**整帧恒定** ⇒ 缓存键只剩 `(颜色, 方向)` 两个维度；
     *   · **顶端低于"带边 − 淡出带"的矮柱整根全实心**（它没进入淡出区）——
     *     这是"不逐柱算"换来的取舍，也是用户选的路线；
     *   · 高柱的淡出仍然**只在顶端那 6px**，正是 ① 要的。
     *
     * ⚠️ 本函数仍然**只把笔刷从缓存里取出来**，构造次数 = 缓存未命中数
     *    （稳态 = 0），与"每帧 N 个对象"那条路线**结构上不同**。
     *
     * ## 坐标是怎么摆的（这是"笔刷可复用"的全部机制）
     *
     * 笔刷由 [SpectrumTipFadeBrushCache] 在**本地坐标**里构造：`y = 0` 恒为
     * 淡出带的外端。本函数用 `g.translate` 把 [fadeAnchor] 挪到 `y = 0`，
     * 片的形状则写在**相对 [fadeAnchor] 的坐标**里（`top - fadeAnchor`）——
     * 于是**同一支笔刷服务任意位置、任意高度的片**。
     *
     * 因为渐变轴在本地坐标里只到 [fadeBandPx]（`[1, 6]` px），
     * 而 `NO_CYCLE` 会把轴**起点之前**的像素钳到首色、**终点之后**钳到末色 ——
     * 所以：
     *   · 淡出端在上（[fadeAtBottom] = `false`）：渐变从 `y = 0` 向 `+bandPx` 渐入实心，
     *     片落在 `y ≥ 0`，`y > bandPx` 的部分全是末色（实心）；
     *   · 淡出端在下（`true`）：片落在 `y ≤ 0`，全是首色（实心），
     *     `[0, bandPx]` 渐出到透明。
     *
     * ## 为什么两端圆角要**分开**控制
     *
     * 对称双峰拆两片时，两片的**内端**（轴那侧）必须是**直角** ——
     * 圆角内端会在靠近左右边缘处向内退 `arc/2`，于是要么留暗缝、要么重叠
     * （交叠区被 `SRC_OVER` 合成两次，`alpha < 1` 时表现为一条更亮的横带）。
     * 见 [barSegmentPath] 与 [spectrumBarPieces] 的论证。
     *
     * ## 变换必须显式还原
     *
     * `g.transform = old`（`old` 是进入时的**副本**）。不还原的后果不是"偏一点"，
     * 而是**后面画的每一根柱子都叠加上前一根的位移** —— 频谱会整体斜掉。
     * 这与 `IslandWindow` 翻转 `growDown` 时 `setTransform(flip)` 是同一条纪律。
     *
     * @param fadeAnchor 淡出带外端的 y（**带**的边，不是这一片的尖端）。
     * @param fadeBandPx 淡出带宽度（px）。整帧恒定 —— 由调用方按单侧带高
     *   经 [spectrumFadeBandPx] 算一次，故缓存键里不含它。
     */
    private fun fillFadedPiece(
        g: Graphics2D,
        left: Float,
        width: Float,
        arc: Float,
        top: Float,
        bottom: Float,
        argb: Int,
        brushes: SpectrumTipFadeBrushCache,
        /** `true` = 淡出端在下（[fadeAnchor] 取带底）；`false` = 在上（取带顶）。 */
        fadeAtBottom: Boolean,
        roundTop: Boolean,
        roundBottom: Boolean,
        fadeAnchor: Float,
        fadeBandPx: Float,
    ) {
        val h = bottom - top
        if (!h.isFinite() || h <= 0f) return
        if (!top.isFinite() || !left.isFinite() || !width.isFinite() || width <= 0f) return
        if (!fadeAnchor.isFinite() || !fadeBandPx.isFinite()) return
        val old = g.transform
        g.translate(0.0, fadeAnchor.toDouble())
        barSegmentPath(scratchBarPath, left, top - fadeAnchor, width, h, arc, roundTop, roundBottom)
        g.paint = if (fadeAtBottom) {
            brushes.fadeToTip(argb, fadeBandPx)
        } else {
            brushes.fadeFromTip(argb, fadeBandPx)
        }
        fillTracked(g, scratchBarPath)
        g.transform = old
    }

    /**
     * 折线风格：一条穿过各频段值的圆头折线。
     *
     * ## 为什么 `Path2D` 也要复用（并且必须 `reset()`）
     *
     * 折线的段数随频段数增长；每帧新建一个 `Path2D` 会累积短命对象。
     * 复用时**必须**先 `reset()` —— 否则上一帧的路径会留在里面，
     * 表现为「屏幕上出现越来越多的残留线条」（经典的路径累积 bug，
     * 且因为它画的是同色线条，很难一眼看出是路径没清）。
     *
     * ## 采样点取柱子的**中心**，而不是等分点
     *
     * 与 [paintBars] 对齐：条形的中心在 `left + barWidth/2`。
     * 若折线用等分点（`x0 + i * slot`），两种风格切换时峰值位置会**横向偏移半个槽**——
     * 视觉上是「换了风格以后频谱整体挪了一点」。故这里用同一套中心点计算。
     */
    private fun paintWave(
        g: Graphics2D,
        cache: RenderCache,
        bands: FloatArray,
        bounds: Rectangle,
        bounceScale: Float,
    ) {
        val count = bands.size
        val slot = bounds.width.toFloat() / count
        val gap = (slot * 0.25f).coerceAtLeast(1f)
        val barWidth = (slot - gap).coerceAtLeast(1f)

        val x0 = bounds.x.toFloat()
        val baseY = bounds.y + bounds.height
        val maxHeight = bounds.height.toFloat()

        val path = scratchPath
        path.reset() // 必须 —— 见 KDoc 关于路径累积

        for (i in bands.indices) {
            // 与 [paintBars] 同一套顺序：归一化柱高 → 跳动幅度拉伸 → 像素高度。
            val value = stretchAroundMid(bands[i], bounceScale)
            val centerX = x0 + i * slot + gap / 2f + barWidth / 2f
            val y = baseY - (value * maxHeight).coerceAtLeast(MIN_BAR_HEIGHT)
            if (i == 0) path.moveTo(centerX.toDouble(), y.toDouble())
            else path.lineTo(centerX.toDouble(), y.toDouble())
        }

        // 圆头 + 圆接：让折线的端点是圆的，与 BARS 风格的圆角观感一致。
        // 线宽取条宽的 60% —— 比柱子细一点，让折线显得轻盈，
        // 同时不至于细到在抗锯齿下变虚。
        val strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f)
        g.stroke = cache.stroke(strokeWidth)
        g.draw(path)
    }

    // ── 复用容器（非线程安全，只在 EDT 使用 —— 与 RenderCache 同一约定）──────

    /**
     * 条形风格复用的矩形。
     *
     * 为什么 `object` 里可以有可变状态：本对象虽然声明为 `object`（单例），
     * 但它的**使用约定是只在 EDT 上**（与 `RenderCache` 一致，见其 KDoc 的线程纪律）。
     * `Graphics2D` 本身就非线程安全，绘制路径没有理由支持并发。
     * 把它声明成 `internal` 而非 `private` 是没有必要的暴露，故保持 `private`。
     */
    private val scratchRect = RoundRectangle2D.Float()

    /** 折线风格复用的路径。必须在每次使用前 `reset()`，见 [paintWave]。 */
    private val scratchPath = Path2D.Float()

    /**
     * 末端淡出柱体复用的路径。见 [barSegmentPath]（它自己会 `reset()`）。
     *
     * 与 [scratchPath] **分开**而不是共用：`paintWave` 与本路径的调用时机
     * 在同一帧内互斥（`Style` 二选一），共用"不会出错"——
     * 但那是**靠时序**成立的，将来一旦有人让两种风格同帧出现，
     * 就会表现为"波形被柱体路径覆盖"这类极难归因的错。
     * 各占一个容器是零代价的（两个 `Path2D` 常驻），故不共用。
     */
    private val scratchBarPath = Path2D.Float()

    /** [spectrumBarPieces] 的输出缓冲（4 个 float，够 2 片用）。见那里的分配纪律。 */
    private val pieceScratch = FloatArray(4)

    /**
     * **实心折线形态**（`solid = true`）复用的路径与两个缓冲。
     *
     * ## 为什么各自独立（不共用 [scratchPath] / [scratchBarPath]）
     *
     * 与 [scratchBarPath] 的 KDoc **同源理由**：那两个容器与折线在**同一帧内互斥**
     * （`solid` 与 `tipFade` 二选一），共用"不会出错"—— 但那是**靠时序**成立的。
     * 各占一个容器是零代价的（常驻），故不共用。
     *
     * ## 为什么用 `FloatArray` 而不是 `List<Point2D>`
     *
     * 逐帧热路径上按柱数装箱/分配会被 `SpectrumFadePerfProbe` 那类探针测出来；
     * 本项目纪律是"新增原语必须零分配"（同 [pieceScratch]）。
     * 两个缓冲**按需扩容**（`ensureCapacity` 式），稳态不再分配。
     */
    private val polylinePath = Path2D.Float()

    /** 折线顶点列缓冲（`(count + 1) × 2` 个 float，含闭合点）。按需扩容。 */
    private var polylineScratch = FloatArray(0)

    /** 折线用的逐柱像素高度缓冲（`count` 个 float）。按需扩容。 */
    private var polylineHeightsScratch = FloatArray(0)

    /**
     * 末端淡出笔刷缓存（跨帧复用）。
     *
     * **这是"每帧新建渐变数从柱数降到常数"的落点** ——
     * 见 [SpectrumTipFadeBrushCache] 的 KDoc 与 [fillFadedPiece] 的坐标说明。
     */
    private val tipFadeBrushes = SpectrumTipFadeBrushCache()

    /**
     * **笔刷类型追踪器**（探针专用，生产为 `null`）—— 见 [PaintTypeTrace] 的 KDoc。
     *
     * ⚠️ 生产路径上是 `null` ⇒ 每次 `fill` 只多一次空判，**零分配、零分支成本**。
     */
    internal var traceForProbe: PaintTypeTrace? = null

    /**
     * 统一的 `fill` 入口：**先把当前 `g.paint` 记进追踪器，再画**。
     *
     * ## 为什么必须包一层（而不是在每个 `fill` 旁边写一行记录）
     *
     * `paintBars` 里有 **6 处** `g.fill(...)`（柱体实心 / 柱体分片 / 峰值帽 ×2 方向 ×2 档）。
     * 逐处手写记录会漏掉将来新增的那一处，而"漏掉"的表现是
     * **追踪结果偏乐观**（少记了一次渐变）—— 那正是本项目最怕的"检查本身不完整"。
     * 收成一个函数后，新增 `fill` 只要走它就不可能漏。
     */
    private fun fillTracked(g: Graphics2D, shape: Shape) {
        traceForProbe?.record(g.paint)
        g.fill(shape)
    }

    // ── 探针接缝（`internal`，只给同模块的 [SpectrumFrameSession] 用）──────────
    //
    // ⚠️ 这**不是**"为测试放宽可见性"：本对象的 `internal` 是因为它的签名里带
    //    `RenderCache`（同样 `internal`）。而 [SpectrumFrameSession] 的**公开签名
    //    里没有任何 `internal` 类型**，故它可以公开 —— 可见性规则没有被放宽，
    //    只是把"给探针开的那扇门"放在了一个不泄漏内部类型的形状上。
    //
    // 为什么需要这扇门（而不是像 `SpectrumDecayProbe` 那样"复刻公式"）：
    // 本轮要证的命题是**绘制路径的分配行为与 alpha 施加方式** ——
    // 那两件事**没有纯函数替身**（它们就是"调了几次 `fill`、每次装了什么笔刷"）。
    // 复刻一遍只能证明"我抄的公式和我抄的公式一致"。

    /** 见 [SpectrumFrameSession.brushesCreated]。 */
    internal val tipFadeBrushCreatedForProbe: Int get() = tipFadeBrushes.created

    /** 见 [SpectrumFrameSession.brushesReused]。 */
    internal val tipFadeBrushReusedForProbe: Int get() = tipFadeBrushes.reused

    /** 见 [SpectrumFrameSession.brushCacheSize]。 */
    internal val tipFadeBrushCacheSizeForProbe: Int get() = tipFadeBrushes.size

    /** 探针用的全新平滑状态（生产由 `IslandWindow` 持有，见 [SpectrumCache] 的 KDoc）。 */
    internal fun newSpectrumCacheForProbe(): SpectrumCache = SpectrumCache()

    // ── 常量 ─────────────────────────────────────────────────────────────

    /**
     * 上升时间常数（秒）。0.02 s ≈ 50 Hz 跟随速度。
     *
     * 取值依据：人耳对**瞬态**（鼓点、拨弦）的时间分辨约在 10–30 ms 量级，
     * 50 Hz 的跟随速度足以让这些瞬态「跟得上」，同时滤掉单帧 FFT 毛刺。
     */
    private const val ATTACK_TAU_SECONDS = 0.02

    /**
     * 回落时间常数（秒）。0.35 s。
     *
     * 取值依据：这是**观感**参数而非物理参数。太久（> 1 s）会让频谱显得拖沓、
     * 与音乐脱节；太短（< 0.1 s）会让无信号段瞬间清空、看起来在闪烁。
     * 0.35 s 是电平表类 UI 的常见量级。
     */
    private const val RELEASE_TAU_SECONDS = 0.35

    /**
     * **暂停**时的时间常数（秒）。0.06 s。
     *
     * 用户原话：「暂停后频谱应**尽快归零**，避免缓慢衰减」——
     * [RELEASE_TAU_SECONDS]（0.35 s）是"播放中自然衰减"的观感参数，用在暂停上太慢。
     *
     * 取值依据（按真机帧间隔 33 ms 实算，见 `SpectrumDecayProbe` 的 ② 组断言）：
     *
     * | 暂停后 | 残留 | 观感 |
     * |---|---|---|
     * | 1 帧（33 ms） | 57% | 明显下落 |
     * | 3 帧（100 ms） | 19% | 已接近零 |
     * | 5 帧（165 ms） | 6% | **视觉上不可见**（40 px 高的柱子只剩 2.4 px） |
     * | 10 帧（330 ms） | 0.4% | 低于 [SILENT_EPSILON] → [isSilent] 成立、不再绘制 |
     *
     * 即：**视觉归零约 0.17 秒，程序判定归零约 0.33 秒**。
     * 对比改动前（[RELEASE_TAU_SECONDS] = 0.35 s，落到同样水平要 ~1.9 秒）——
     * 快了约 6 倍，正是用户要的"尽快"。
     *
     * ⚠️ 两个"归零"要分清：[isSilent] 是给**帧驱动**用的保守阈值
     * （"还要不要再跑帧"），它比"看不见"更严格，故比视觉归零晚约 0.16 秒。
     * 把两者混为一谈会误判"暂停后帧驱动多跑了 5 帧"是缺陷。
     *
     * ⚠️ 与 [MAX_DT_SECONDS] 的配合：`dt` 被封顶到 0.1 s，故最坏情况（掉帧严重）
     * 一帧也衰减到 `e^(-0.1/0.06) ≈ 0.19`（砍掉八成），仍是"几帧内归零"，
     * 不会因为封顶而变成"永远归不了零"。
     */
    private const val PAUSED_RELEASE_TAU_SECONDS = 0.06

    /**
     * 判定"全静音"的阈值。见 [isSilent]。
     *
     * 0.004 是"柱高占比"：在 40 px 高的频谱里约 0.16 px，**视觉上不可见**。
     * 取这个量级而不是更小，是为了让指数衰减能在有限帧内真正被判成"到了"。
     */
    private const val SILENT_EPSILON = 0.004f

    /**
     * **峰值保持**的下落速率（满量程 / 秒，播放中）。取 `0.45` → 从满到零约 **2.2 秒**。
     *
     * 为什么是"固定速率"而不是指数衰减：见 [updatePeaks]。
     * 为什么是 2.2 秒：短于 1 秒时"帽"几乎追着柱子走（等于没有保持效果）；
     * 长于 4 秒会在安静的段落里留下一排不动的横线（看起来像画坏了）。
     */
    private const val PEAK_FALL_PER_SECOND = 0.45

    /**
     * 暂停时的峰值下落速率。取 `2.5` → 约 **0.4 秒**归零。
     *
     * 用户要求「暂停后频谱应**尽快归零**」—— 若峰按常速下落，它会挂 2 秒多，
     * 看起来像"没归零"（而这正是那条要求要修的东西）。
     */
    private const val PAUSED_PEAK_FALL_PER_SECOND = 2.5

    /** 峰值帽的**厚度**（像素）。取 2 —— 与 [MIN_BAR_HEIGHT] 同量级，不喧宾夺主。 */
    private const val PEAK_CAP_HEIGHT = 2f

    /** 无时间戳时假定的一帧时长（秒）—— 60 fps。见 [smooth]。 */
    private const val DEFAULT_FRAME_SECONDS = 1.0 / 60.0

    /** 时间差上限（秒）。见 [smooth] 关于「窗口被遮挡后不应瞬间归零」的论证。 */
    private const val MAX_DT_SECONDS = 0.1

    /**
     * 柱子的最小高度（像素）。
     *
     * 取 2：足以在抗锯齿下呈现为一个可见的圆角点，又足够小到
     * 「有信号」与「无信号」的高度差异一目了然。
     * 见 [paintBars] 关于「无信号时不能完全消失」的论证。
     */
    private const val MIN_BAR_HEIGHT = 2f

    /**
     * "**整帧算静音**"的峰值门限（归一化柱高，`0f..1f`）。
     *
     * ## ⚠️ 2026-09-21 更正：**本 KDoc 原写「取值 `0.004f`」，而代码是 `1e-6f`** ——
     * **差了 4000 倍**，是典型的"注释与事实相反"。
     *
     * 那个错误有**可观测的后果**：`1e-6` 太小 ⇒ 静音门**几乎永不触发** ⇒
     * 静音期每根柱子仍按 `MIN_BAR_HEIGHT` 地板画出来 ⇒
     * **对称双峰时中线一条 4px 粗线**（用户报的"还是老问题"）。
     *
     * ## 现在的分工（两条判据，互不替代）
     *
     * | 判据 | 位置 | 语义 |
     * | --- | --- | --- |
     * | `peak <= SILENCE_PEAK_EPS`（本常量） | `halfSpan` **之前** | **绝对零** —— 挡"复用缓冲（`barScratch`）尾巴上的上一帧残值" |
     * | `peak × halfSpan <= MIN_BAR_HEIGHT` | `halfSpan` **之后** | **视觉地板** —— "画出来只会是地板"，才是用户看到的"无声" |
     *
     * 第二条是**真正**修掉"中线粗线"的那条，且它**随几何自动缩放**（带子高时门限小、矮时大），
     * 不需要为不同布局各调一个常量。
     *
     * 触发时的后果：本帧**一根柱子都不画**（见 `paintBars` 里的说明）。
     */
    // ⚠️⚠️ 2026-09-20 二次修正：从 **0.004f 降到 1e-6f**。
    //
    // ## 为什么原来那个值是**过度激进**的
    //
    // 用户报：「**主副频谱不显示**」。
    // 0.004f 约等于 **-48 dBFS** —— 它不是「字面零」，而是一个实际的音量门。
    // 只要频谱源给出的数值普遍偏弱（或整段很轻），**整帧**就会被判成静音
    // → **一根柱子都不画** → 用户看到的就是「频谱没了」。
    //
    // ## 修正后的判据
    //
    //  —— 只命中**字面上的零**（真数字静音）。
    // 数据源  会把每个值 ，
    // 真静音时它输出的就是精确的 ，故这个阀值足够命中。
    //
    // 两边都不误伤：
    //   · **真静音** → 整帧精确 0 → 不画 → 那条「贯穿粗线」消失 ✓（原问题）
    //   · **任何真实数据** → 哪怕很小也画 → 频谱不会「消失」 ✓（回归修复）
    private const val SILENCE_PEAK_EPS = 1e-6f
}

/**
 * 从调用方给的**已播放段笔刷**里**取出**填充色（打包 ARGB）—— 取不到时返回 `null`。
 *
 * ## ★★ 2026-09-22：为什么需要"从笔刷里取色"
 *
 * 用户原话：「进度色差我不是说了**用已播放与未播放频谱的透明度的差值**吗？
 * 怎么滑杆到 0 还有差别的。」
 *
 * ### 问题的形状
 *
 * 「进度色差」这一层要**换颜色**（已播放 vs 未播放），而它的**几何**
 * （柱子的分片、末端淡出、对称双峰、峰值帽）必须与底图**逐像素一致**。
 * 于是"几何"与"颜色"必须分家：
 *   · **几何**由 `SpectralPainter` 算（它是唯一知道柱高、对称轴、淡出带的地方）；
 *   · **颜色**由调用方给（它知道 `spectrum.color` / 已唱色 / 两个不透明度设置）。
 *
 * 但调用方目前的接口给的是**一整支笔刷**（`progressPaintOverride`），
 * 里面既有颜色也有几何（`verticalFadePaint` 是一条纵向渐变）。若直接用它，
 * 几何就与底图不一致（底图走末端淡出笔刷、它走整带渐变）—— 那正是
 * `IslandWindow.solidProgressPaint` 的 KDoc 记过的"两幅不同形状的图叠在一起"。
 *
 * ### 取法
 *
 * 只取**颜色**，丢弃几何：
 *   · [Color] → 直接是它；
 *   · [LinearGradientPaint] / [GradientPaint] → 取**alpha 最大**的那个色标
 *     （调用方的渐变都是"实色 → 透明"，故最大 alpha 的那个就是实色）；
 *   · 其它 `Paint` → `null`（退回旧路径，不猜）。
 *
 * ⚠️ 这是**过渡期的适配器**，不是长期接口：调用方应当直接传
 *    `SpectralPainter.paint(progressColor = …)`。留着它是为了
 *    **让修复不必等接线**（两边同时改会有一个窗口期，那时画面会退化成
 *    "色差不可见"—— 正是用户报的那条）。
 *
 * ## 为什么是公开顶层函数
 *
 * 它是**纯函数**（无 `Graphics2D`、无状态），且"取到的色对不对"是
 * **可断言**的（探针 ⑨ 组：对 `verticalFadePaint` 那种
 * `[solid, solid, clear]` 渐变，取出的 alpha 必须等于 `solid` 的 alpha）。
 */
fun playedArgbFrom(paint: Paint?): Int? = when (paint) {
    null -> null
    is Color -> paint.rgb
    is LinearGradientPaint -> paint.colors.maxByOrNull { it.alpha }?.rgb
    is GradientPaint -> maxOf(paint.color1, paint.color2, compareBy { it.alpha }).rgb
    else -> null
}

/**
 * 柱体**分片**的路径（可只圆**部分**角）—— 公开纯几何，可被探针断言。
 *
 * ## ★★ 2026-09-22：为什么需要它（而不是 `RoundRectangle2D`）
 *
 * `RoundRectangle2D` **四角全圆**。而对称双峰要拆成上下两片
 * （见 [spectrumBarPieces] 的不变量论证），两片的**内端**必须是**直角**：
 * 圆角内端会在靠近左右边缘处向内退 `arc/2`，于是两片之间
 * 要么**留一条暗缝**（改动前 `RoundRectangle2D` 拼两半时的老缺陷），
 * 要么**重叠**（交叠区被合成两次 → `alpha < 1` 时中线一条更亮的横带）。
 * 两者都是看得见的错，而"只圆外端"能同时避开。
 *
 * ## 为什么要抽成公开函数
 *
 * 不变量是**几何**的（"两片不重叠且无缝"），而不是"看起来对不对"。
 * 抽成纯函数后，探针可以用 `java.awt.geom.Area` **直接判定**
 * `Area(上片) ∩ Area(下片)` 是否为空、并集是否覆盖整条柱 ——
 * 这正是本项目「为测试发愁时先抽纯函数」的做法（§14.14）。
 *
 * ## 圆角半径的夹取
 *
 * `arc` 被夹到 `min(宽, 高) / 2`。**必须夹**：`RoundRectangle2D` / 手写弧
 * 在 `arc > 高/2` 时画出的形状不可预期（Java2D **不报错**，只是结果怪）——
 * 这条纪律与 `paintBars` 里既有的 `arcTall` 夹取同源。
 *
 * @param path 复用容器（**会被 `reset()`**）。绘制热路径不新建对象。
 * @param x 左边缘（px）
 * @param y 上边缘（px）
 * @param w 宽度（px）
 * @param h 高度（px）
 * @param arc 圆角半径（px，会被夹取）
 * @param roundTop 上端两角是否圆
 * @param roundBottom 下端两角是否圆
 */
fun barSegmentPath(
    path: Path2D,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    arc: Float,
    roundTop: Boolean,
    roundBottom: Boolean,
) {
    path.reset()
    if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return
    if (w <= 0f || h <= 0f) return
    val a = if (arc.isFinite()) arc.coerceIn(0f, minOf(w, h) / 2f) else 0f
    val x1 = x + w
    val y1 = y + h
    if (a <= 0f) {
        // 无圆角：直角矩形。**不用 `Rectangle2D` 是为了与有圆角时走同一条路径构造** ——
        // 两条构造路径的顶点顺序一旦不同，"并集覆盖"那类断言就会在两种档上给出不同结果。
        path.moveTo(x.toDouble(), y.toDouble())
        path.lineTo(x1.toDouble(), y.toDouble())
        path.lineTo(x1.toDouble(), y1.toDouble())
        path.lineTo(x.toDouble(), y1.toDouble())
        path.closePath()
        return
    }
    if (roundTop) {
        path.moveTo(x.toDouble(), (y + a).toDouble())
        path.quadTo(x.toDouble(), y.toDouble(), (x + a).toDouble(), y.toDouble())
        path.lineTo((x1 - a).toDouble(), y.toDouble())
        path.quadTo(x1.toDouble(), y.toDouble(), x1.toDouble(), (y + a).toDouble())
    } else {
        path.moveTo(x.toDouble(), y.toDouble())
        path.lineTo(x1.toDouble(), y.toDouble())
    }
    if (roundBottom) {
        path.lineTo(x1.toDouble(), (y1 - a).toDouble())
        path.quadTo(x1.toDouble(), y1.toDouble(), (x1 - a).toDouble(), y1.toDouble())
        path.lineTo((x + a).toDouble(), y1.toDouble())
        path.quadTo(x.toDouble(), y1.toDouble(), x.toDouble(), (y1 - a).toDouble())
    } else {
        path.lineTo(x1.toDouble(), y1.toDouble())
        path.lineTo(x.toDouble(), y1.toDouble())
    }
    path.closePath()
}

/**
 * **顶端淡出笔刷缓存** —— 让"每根柱子一支渐变"变成"每帧几支"。
 *
 * ## ★★ 2026-09-22：用户原话与根因
 *
 * > 「**频谱顶端淡出开启后频谱跳动卡顿明显。**」
 *
 * ### 改动前
 *
 * 笔刷在**绝对坐标**里构造（`LinearGradientPaint(0f, topY, 0f, bottomY, …)`）——
 * 于是每根柱子的笔刷**位置都不同**，**无法缓存**（缓存了就会拿到位置错误的笔刷，
 * 这一点在改动前的 KDoc 里被写明了）。结果：每帧**每根柱子**新建一支渐变，
 * 且 Java2D 为每支新笔刷都要预计算一张颜色查找表。
 *
 * ### 现在
 *
 * 笔刷在**本地坐标**里构造：`y = 0` 恒为**淡出带的外端**，纵轴只到
 * [spectrumFadeBandPx] 给出的**淡出带宽度**（`[1, 6]` px）。
 * 调用方（`paintBars`）在 `fill` 前 `translate` 到该外端 —— 于是
 * **同一支笔刷可以服务任意位置、任意高度的柱子**（`NO_CYCLE` 会把渐变
 * 起点之前/终点之后的像素分别钳到首/末色，正好就是"顶端透明、下方实心"）。
 *
 * 缓存键 = `(RGB, alpha, 量化后的淡出带宽度, 方向)`。淡出带宽度量化到
 * `0.25 px` ⇒ 至多 `21 × 2` 个键 ⇒ **每帧新建数 ≤ 一个小常数**
 * （量化误差 ≤ 0.125 px，视觉上不可分辨）。
 *
 * ## ★★ 2026-09-23：生产路径的键空间**缩小到 `2 × 颜色数`**
 *
 * 用户原话（逐字）：
 * > 「**原来在每根柱顶设置淡化导致效能开销大，卡顿感明显故改掉了，
 * >   你现在又让他回归，就不能探究其他路线吗？**」
 *
 * 故淡出带**不再按片高逐片算**（见 [fillFadedPiece] 的 KDoc）：
 * `paintBars` 现在整帧只算一次 `fadeBand = spectrumFadeBandPx(单侧带高)`，
 * 于是**同一帧里所有柱子传进来的 `bandPx` 完全相同** ⇒
 * 生产路径的键空间 = `方向(2) × 每帧不同颜色数(≤ 4)` = **8**。
 *
 * ⚠️ 但 `maxEntries` **仍然取 192**（`21 × 2 × 4`）：
 *   · 本类是**公开 API**，键里带着"任意 `bandPx`"这一维（[fadeFromTip] /
 *     [fadeToTip] 的入参没有范围限制），容量按**可能的最大键空间**定才不会
 *     在将来某次改动后突然退化成零命中（`SpectrumFadePerfProbe` ③b 正是
 *     穷举 168 个键来钉这条）；
 *   · 192 个条目的内存是几百字节量级，**不构成成本** ——
 *     而"容量不够 ⇒ LRU 抖动 ⇒ 零命中"是**看不见的性能缺陷**，
 *     两者不对称，故宁可宽一点。
 *
 * ## 为什么 `alpha` 直接取色值的**高 8 位**
 *
 * 与 `IslandWindow` 那套"`255 × SPECTRUM_BACKGROUND_ALPHA × alphaScale`、
 * **忽略**色值自带 alpha"的约定**不同**，这里**就用**色值自带的 alpha。
 * 理由：本缓存是**绘制侧**的唯一落点，色值由调用方按
 * `spectrumLayerArgb(packed, 段系数)` 算好 —— 那个函数把段系数**已经**编进了
 * 高 8 位。再乘一次就是本项目反复踩过的"双重乘 alpha"（`base²`）。
 * 于是这里只有一条规则：**色值的 alpha 就是这一段的有效 alpha**，
 * 整帧 `AlphaComposite` 在它之上再乘一次（两者**正交**，见 `SpectrumAlphas`）。
 *
 * ## 线程纪律
 *
 * 与 `RenderCache` / `SpectralPainter` 同一约定：**只在 EDT 上使用**
 * （绘制路径天然在 EDT）。内部 `LinkedHashMap` 非线程安全，不做额外同步。
 *
 * ## ★★ 2026-09-22（同日第二轮）：容量 `32` 是**错的** —— 它装不下自己的键空间
 *
 * ### 原记（错）
 *
 * 「键的**理论**上限是 `21 × 2`（方向），而实际用到的颜色只有寥寥几种，
 *  **32 足够覆盖**且不会无界增长。」
 *
 * ### 实为：`21 × 2 = 42 > 32` —— 这句话**自相矛盾**
 *
 * 同一段 KDoc 里先算出"上限 42"、再说"32 够用"。而 `LinkedHashMap` 的 LRU 在
 * 工作集**大于**容量时会**抖动**：访问序是循环的，每次淘汰的正是**马上要用**的那个键。
 *
 * **实测（`SpectrumFadePerfProbe` ③b）**：对全部 42 个键做三轮完整扫描 ——
 * `created = 126`、`reused = 0` ⇒ **每一次请求都新建了一支渐变**，
 * 缓存**完全没有命中**。也就是说：只要 `spectrum.symmetric = true`（两个方向）
 * 且柱高分布够散，**这条缓存等于不存在**，`fade_top` 又回到"每柱一支"。
 *
 * ### 现在的容量：从**三个维度**算出来，不再拍脑袋
 *
 * | 维度 | 取值 | 出处 |
 * |---|---|---|
 * | 淡出带档数 | `21` | `[1, 6] px` 量化到 `0.25 px`（[spectrumFadeBandPx]） |
 * | 方向 | `2` | [fadeFromTip] / [fadeToTip] |
 * | 每帧**不同颜色**数 | `≤ 4` | 主频谱「未播放 / 已播放」+ 副频谱「未播放 / 已播放」 |
 *
 * `21 × 2 × 4 = 168` ⇒ 取 **192** 留一点余量（`Paint` 只持色标数组，
 * 每个条目的内存是**几百字节**量级，192 个完全无所谓）。
 *
 * ⚠️ **超出这个上界的只有一种情形**：`spectrum.color_mode = cover` 且封面正在
 * 交叉淡化时，主色**每帧都变** ⇒ 键每帧都是新的。那时 LRU 会持续淘汰旧色 ——
 * 这是**正确**的行为（那些键确实再也不会被用到），不是抖动。
 * 真机实测未在 640px 下观察到该情形造成的额外新建（见 `SpectrumFadePerfProbe` ①②）。
 *
 * @param maxEntries LRU 容量。见上：**必须 ≥ `21 × 2 × 每帧不同颜色数`**，
 *   否则工作集大于容量 ⇒ 循环访问序 ⇒ **零命中**。
 */
class SpectrumTipFadeBrushCache(private val maxEntries: Int = 192) {

    /** 访问序 LRU：命中即移到队尾，队首是最久未用的。 */
    private val lru = LinkedHashMap<Long, Paint>(16, 0.75f, true)

    /** **新建**的笔刷数（= 真实的 `LinearGradientPaint` 构造次数）。探针据此断言。 */
    var created: Int = 0
        private set

    /** 缓存**命中**数。`created + reused` = 总请求次数。 */
    var reused: Int = 0
        private set

    /** 当前缓存条目数。 */
    val size: Int get() = lru.size

    /** 清空（探针计数前调用；生产路径不需要）。 */
    fun clear() {
        lru.clear()
        created = 0
        reused = 0
    }

    /**
     * 「尖端透明 → 沿 [bandPx] 渐入实心」的笔刷。用于**上端**淡出
     * （非对称档的柱顶、对称档的上片）。
     *
     * 坐标语义：`y = 0` 是该片**上边缘**，`y = bandPx` 处已是实心，
     * `y > bandPx` 被 `NO_CYCLE` 钳到末色（实心）—— 故调用方
     * **只要把片的上边缘 `translate` 到 `y = 0` 即可**，无需关心片高。
     */
    fun fadeFromTip(argb: Int, bandPx: Float): Paint = brush(argb, bandPx, down = true)

    /**
     * 「沿 [bandPx] 由实心渐出 → 尖端透明」的笔刷。用于**下端**淡出
     * （对称档的下片、对称档的镜像峰值帽）。
     *
     * 坐标语义：`y = 0` 是该片**下边缘**（尖端），`y = -bandPx` 处已是实心，
     * `y < -bandPx` 被 `NO_CYCLE` 钳到**末色（实心）**；`y > 0` 钳到首色（透明）。
     * 故调用方**要把片的下边缘 `translate` 到 `y = 0`**，片的其余部分落在负 `y`。
     *
     * ## ★★ 2026-09-23：这条轴的方向**原本是反的** —— 下尖从来没有淡出过
     *
     * 改动前 `brush(..., down = false)` 构造的是 `(0,0) → (0, +bandPx)`、
     * 色标 `[solid, clear]` —— 即"轴朝 **+y**、末端透明"。
     * 而调用方（[fillFadedPiece] 的 `fadeAtBottom` 分支）把片放在 **`y ≤ 0`**：
     * `y < 0` 全部落在轴**起点之前** ⇒ `NO_CYCLE` 把它们钳到**首色 = 实心**。
     * 于是**整个下片都是实心的**，`[0, bandPx]` 那段渐变画在片**外面**（那里没有像素）。
     *
     * 症状：对称双峰**只有上尖淡、下尖是硬边**（改动前的 KDoc 自己就写着
     * "片落在 `y ∈ [-h, 0]`，全是首色（实心），最后 `[0, band]` 渐出到透明" ——
     * 那句"最后 `[0, band]`"说的正是**片外**，只是当时没人把它读成缺陷）。
     *
     * 修法：把轴方向翻过来 —— `(0,0) → (0, -bandPx)`、色标 `[clear, solid]`。
     * 于是 `y = 0`（尖端）透明、`y ≤ -bandPx` 实心、`y > 0` 透明 —— 与片的位置一致。
     *
     * 判据见 `SpectrumFadeBandProbe` ⑦（"下端淡出"与"两端对称"两条，
     * 改动前实测 `上=41 / 下=255` ⇒ 红）。
     */
    fun fadeToTip(argb: Int, bandPx: Float): Paint = brush(argb, bandPx, down = false)

    private fun brush(argb: Int, bandPx: Float, down: Boolean): Paint {
        val band = if (bandPx.isFinite()) bandPx.coerceAtLeast(0.25f) else 0.25f
        // 量化到 0.25 px ⇒ 21 档。量化误差 ≤ 0.125 px，视觉不可分辨。
        val bandKey = (band * 4f).roundToInt().coerceIn(0, 255)
        val key = (argb.toLong() and 0xFFFFFFFFL) shl 10 or
            (bandKey.toLong() shl 1) or
            (if (down) 1L else 0L)
        val hit = lru[key]
        if (hit != null) {
            reused++
            return hit
        }
        val base = Color(argb, true)
        val solid = Color(base.red, base.green, base.blue, base.alpha)
        val clear = Color(base.red, base.green, base.blue, 0)
        val len = bandKey / 4f
        // ⚠️ 两个色标**必须严格递增**（`0f < 1f` 恒成立）——
        //    改动前那套 `[0, band, 1-band, 1]` 的越界崩溃（`Keyframe fractions
        //    must be increasing`）在**两色标**的写法下**结构上不可能发生**。
        //
        // ⚠️ 两条轴的**方向相反**（不是同一个轴换色标顺序）：
        //    · `down = true`（上尖淡）：轴朝 **+y**，`y > len` 钳到末色（实心）；
        //    · `down = false`（下尖淡）：轴朝 **−y**，`y < -len` 钳到末色（实心）。
        //    片分别落在 `y ≥ 0` 与 `y ≤ 0` —— 与调用方的 `translate` 一致。
        val paint: Paint = if (down) {
            LinearGradientPaint(
                0f, 0f, 0f, len,
                floatArrayOf(0f, 1f),
                arrayOf(clear, solid),
            )
        } else {
            LinearGradientPaint(
                0f, 0f, 0f, -len,
                floatArrayOf(0f, 1f),
                arrayOf(clear, solid),
            )
        }
        created++
        if (lru.size >= maxEntries) {
            val it = lru.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        lru[key] = paint
        return paint
    }
}

/**
 * 探针 / 基准专用的**绘制会话** —— 持有与生产一致的平滑状态与笔刷缓存。
 *
 * ## ★★ 2026-09-22：为什么需要它（用户原话：「**频谱顶端淡出开启后频谱跳动卡顿明显**」）
 *
 * 那条卡顿的根因是**分配行为**（改动前每帧为每根柱子新建一支 `LinearGradientPaint`），
 * 而"每帧新建了几支渐变"**没有纯函数替身** —— 它就是"调了几次 `fill`、
 * 每次往 `g.paint` 里装了什么"。故本轮的做法是**开一扇门让探针走真实绘制路径**：
 * [paintSpectrumFrameForProbe] → [SpectralPainter.paint] → `paintBars` → [fillFadedPiece]。
 *
 * ## 为什么可见性没有被放宽
 *
 * [SpectralPainter] 是 `internal`，是因为它的**签名**里带 `RenderCache`（同样 `internal`）。
 * 本类把 `RenderCache` / `SpectrumCache` 放在**私有字段**里，公开签名里**没有任何
 * `internal` 类型** —— 于是它可以是公开的，而"可见性纪律"一字未动
 * （对照本项目「不为测试放宽可见性，只把规则抽成有名字的定义点」）。
 *
 * ## 为什么状态必须**跨帧**复用（这一点写错，整个基准就失去意义）
 *
 * [SpectralPainter.smooth] 是**有状态**的：首次调用把显示值初始化为 0 并交给
 * attack 平滑渐入。若每帧新建一个会话，每帧都是一次"首次调用" ⇒ 柱高恒为 0
 * ⇒ `paintBars` 的静音门直接 `return` ⇒ **基准测的是"什么都不画"**。
 * 故调用方必须**复用同一个 `session`** 跑完整个测量序列。
 *
 * ## 线程纪律
 *
 * 与 [SpectralPainter] / [RenderCache] 同一约定：**只在 EDT（或单线程）上使用**。
 *
 * @param brushesCreated 自本会话创建以来**新建**的末端淡出渐变数。
 *   判据：它必须**远小于** `帧数 × 柱数` —— 否则就是"每柱一支渐变"那个卡顿复发了。
 * @param brushesReused 自本会话创建以来**复用**的次数。
 * @param brushCacheSize 笔刷缓存当前条目数（LRU 容量见 [SpectrumTipFadeBrushCache]）。
 * @param enablePaintTrace `true` = 打开 [paintTrace]（**逐 `fill` 记录笔刷类型**）。
 *   默认 `false`：既有探针**行为与开销都不变**（生产路径更是恒 `null`）。
 *   ⚠️ 同一时刻只允许**一个**会话打开它（`SpectralPainter.traceForProbe` 是单例字段）——
 *   探针各自独立 JVM 运行，故实际不会冲突。
 */
class SpectrumFrameSession(enablePaintTrace: Boolean = false) {
    private val cache = RenderCache()
    private val state = SpectralPainter.newSpectrumCacheForProbe()

    private val created0 = SpectralPainter.tipFadeBrushCreatedForProbe
    private val reused0 = SpectralPainter.tipFadeBrushReusedForProbe

    /**
     * 见 [enablePaintTrace]。**未开启时恒为空追踪器**（计数全 0），
     * 于是探针可以无条件读它而不必判空。
     */
    val paintTrace: PaintTypeTrace = PaintTypeTrace()

    init {
        if (enablePaintTrace) {
            SpectralPainter.traceForProbe = paintTrace
            // 清掉"本会话创建前"的残留计数（同一 JVM 里若有别的会话先跑过）。
            paintTrace.reset()
        }
    }

    /** 见类 KDoc。 */
    val brushesCreated: Int get() = SpectralPainter.tipFadeBrushCreatedForProbe - created0

    /** 见类 KDoc。 */
    val brushesReused: Int get() = SpectralPainter.tipFadeBrushReusedForProbe - reused0

    /** 见类 KDoc。 */
    val brushCacheSize: Int get() = SpectralPainter.tipFadeBrushCacheSizeForProbe

    /** 供 [paintSpectrumFrameForProbe] 使用。`internal` ⇒ 不泄漏到公开签名。 */
    internal fun renderCache(): RenderCache = cache

    /** 供 [paintSpectrumFrameForProbe] 使用。`internal` ⇒ 不泄漏到公开签名。 */
    internal fun spectrumCache(): SpectralPainter.SpectrumCache = state
}

/**
 * 探针 / 基准专用入口：把**一帧**频谱画到 [g] 上 —— 走的是**真实绘制路径**。
 *
 * ## 与生产调用点（`IslandWindow.drawBackgroundSpectrum`）的关系
 *
 * 本函数是 [SpectralPainter.paint] 的一层薄壳，只做三件事：
 *   ① 从 [session] 里取出 `RenderCache` / `SpectrumCache`（生产由窗口持有）；
 *   ② 把 `style` 固定成 [SpectralPainter.Style.BARS] —— 生产唯一的档位
 *      （`IslandWindow.kt` 传的就是它）。**`Style` 是 `SpectralPainter` 的嵌套枚举，
 *      出现在公开签名里会暴露 `internal` 类型**，故这里不给这个参数，也不改
 *      `SpectralPainter` 的既有 API（那会让接线方的文件编译失败）。
 *   ③ 其余参数**逐个透传**，语义与 [SpectralPainter.paint] 的同名参数完全一致。
 *
 * ## 它能让探针断言什么（这是它存在的全部理由）
 *
 * | 命题 | 断言方式 |
 * |---|---|
 * | 每帧新建的渐变数**不随柱数增长** | [SpectrumFrameSession.brushesCreated] |
 * | **柱状档**下 `fadeTop = true` 时 [paintForBar] **一次都不会被调用** | 传一个自增计数的工厂 |
 * | `opacity` 是**整帧**施加、覆盖**每一条**绘制路径 | 离屏渲染后读像素 alpha 通道 |
 *
 * ★★ **2026-10-03 更正（㉒ 收口）**：上面第二行的"一次都不会被调用"**只对柱状档成立**。
 * 原文写成**无条件**，曾让我自己误判"㉒ 改 `paintForBar` 是空转"。**实为**：
 * `paintForBar` 只在 `tipFade = false` 的实心分支被读，而生产侧传的 `paintForBar`
 * **非空 ⟺ `spectrum.fade_top = true`** ⇒
 *   · **柱状档**（间距 > 0）：`tipFade = true` ⇒ 走 `fillFadedPiece` ⇒ 工厂**0 次** ✓（原句成立）；
 *   · **折线档**（间距 = 0）：`paintBars` 在逐柱循环**之前**就 `return`（实心折线分支），
 *     那里**只有**工厂这一条上色路径 ⇒ 工厂**每帧 1 次**（首帧被视觉静音门短路 ⇒ `帧数 − 1`）。
 * ⇒ 机械证据：`SpectrumFadeBandProbe` **§F**（F1/F2/F3/F4 直接读工厂收到的第 3 实参）。
 * ⚠️ 这正是折线档下"圆角档顶端淡出与方角档无差别"（㉒）的**真实落点** ——
 *   工厂的第 3 实参 `cornerRadiusPx` 曾因签名只有 2 参而恒为默认 `0f`。
 *
 * ⚠️ 生产路径**不得**调用本函数 —— 它存在的意义就是"让探针看到真路径"。
 *
 * @param nowNanos 见 [SpectralPainter.paint]。基准必须**逐帧递增**它，
 *   否则 `smooth` 拿不到时间差、会退化到"按 60 fps 估一帧"（仍能跑，但不再是真帧率）。
 */
@Suppress("LongParameterList")
fun paintSpectrumFrameForProbe(
    g: Graphics2D,
    session: SpectrumFrameSession,
    bands: FloatArray,
    bounds: Rectangle,
    color: Int,
    nowNanos: Long,
    playing: Boolean = true,
    fadeTop: Boolean = true,
    opacity: Float = 1f,
    symmetric: Boolean = false,
    symmetryCenterY: Float = Float.NaN,
    bounceScale: Float = 1f,
    peakHold: Boolean = false,
    progressFraction: Float = NO_PROGRESS,
    progressColor: Int? = null,
    paintOverride: Paint? = null,
    paintForBar: ((topY: Float, bottomY: Float, cornerRadiusPx: Float) -> Paint)? = null,
    barPitchPx: Float = SPECTRUM_BAR_PITCH_PX,
    /**
     * ★★★ 2026-10-11（**U13**）：**固定条数**（`paint` 同名参数的透传，判据见那边）。
     * `> 0` ⇒ 恒用该条数；`<= 0` ⇒ 按宽度推导（既有探针不传时**逐像素不变**）。
     */
    forceBarCount: Int = 0,
    /**
     * ★★★ 2026-10-11（**U14**）：**极坐标画布**（RADIAL / RING 两档）的透传。
     * `null`（默认）⇒ 直角坐标（既有探针**逐像素不变**）。
     *
     * ## 为什么必须补它（★ 本参数是 RADIAL「探针测不了」的缺口）
     *
     * U13 收尾时登记过：「`paintSpectrumFrameForProbe` 不含 `polar` ⇒ 探针
     * **离屏测不了极坐标** ⇒ RADIAL 是否可见只能靠真机目视」✗
     * ⇒ ★ 补上后，**放射 / 环状**两档都能走**真实绘制路径**出图 + 像素断言
     *   （圆心有无像素 = 放射 vs 环状的机械判据）✓
     */
    polar: SpectrumPolarSpec? = null,
    /**
     * 端头形状（`spectrum.bar_corner`）。见 [SpectrumBarCorner]。
     *
     * 默认 [SpectrumBarCorner.DEFAULT] = **改动前行为**（零回归）——
     * 既有探针（`SpectrumSolidGapProbe` / `SpectrumFadePerfProbe` / `SpectrumOpacityProbe`）
     * 不传它时行为**逐像素不变**。
     */
    barCorner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,
    /**
     * `spectrum.bar_shape` 换算出的圆角比例（`1f` 圆角 / `0f` 方角）。
     *
     * ★★ 2026-09-28 新增（BUG-138）：探针此前**只能**传 `barCorner`，
     * 而那正是**真机从不提供**的输入 ⇒ 「实心档切方角无效」这个缺陷
     * 在探针里**不可能复现**。补上这条参数后，探针才能走**用户路径**。
     */
    barShapeRatio: Float = 1f,
) {
    SpectralPainter.paint(
        g = g,
        cache = session.renderCache(),
        bands = bands,
        bounds = bounds,
        color = color,
        state = session.spectrumCache(),
        style = SpectralPainter.Style.BARS,
        nowNanos = nowNanos,
        playing = playing,
        paintOverride = paintOverride,
        bounceScale = bounceScale,
        peakHold = peakHold,
        progressFraction = progressFraction,
        progressColor = progressColor,
        fadeTop = fadeTop,
        opacity = opacity,
        symmetric = symmetric,
        symmetryCenterY = symmetryCenterY,
        barPitchPx = barPitchPx,
        forceBarCount = forceBarCount,
        polar = polar,
        paintForBar = paintForBar,
        barCorner = barCorner,
        barShapeRatio = barShapeRatio,
    )
}

/**
 * **笔刷类型追踪器** —— 记录**每一趟 `fill` 实际装进 `g.paint` 的是什么**。
 *
 * ## ★★ 2026-09-23：它回答的是用户第 ② 条
 *
 * > 「**在关闭顶端淡出的情况下顶端仍有淡出效果**」
 *
 * ### 为什么"数笔刷类型"是这条诉求的正确判据
 *
 * "顶端淡出"在绘制层**只有一种实现方式**：用**渐变笔刷**填充柱体
 * （见 [SpectrumTipFadeBrushCache]：淡出笔刷恒是 `LinearGradientPaint`）。
 * ⇒ **"这一帧有没有淡出" == "这一帧有没有用渐变笔刷 `fill`"**。
 *
 * 而"顶端仍有淡出"的可能来源**不止一处**（底图趟 / 进度染色趟 / 副频谱趟），
 * 逐个人肉读代码会漏。故这里**在 `fill` 的必经之路上数**（见
 * `SpectralPainter.fillTracked`）—— 这是"不可证伪的检查等于没有检查"的反面：
 * 它统计的是**实际发生的事**，而不是"我以为会走哪条分支"。
 *
 * ### ⚠️ `GradientPaint` 与 `LinearGradientPaint` 必须分开数
 *
 * 两者**没有继承关系**（都直接继承 `MultipleGradientPaint`），
 * 只判 `is GradientPaint` 会**漏掉本项目真正用的那一种**（`LinearGradientPaint`）。
 * 这个坑在 `playedArgbFrom` 里已经踩过一次（那里两个分支是分开写的）。
 *
 * ## 零成本约定
 *
 * 追踪器由 [SpectrumFrameSession] 显式开启（生产为 `null`）——
 * 见 `SpectralPainter.traceForProbe`。生产路径上每次 `fill` 只多一次空判。
 *
 * ## 线程纪律
 *
 * 与 [SpectralPainter] 同一约定：**只在 EDT（或单线程）上使用**，不做同步。
 */
class PaintTypeTrace {
    /** 纯色 `fill` 次数（`java.awt.Color`）。 */
    var plainColorFills: Int = 0
        private set

    /** `LinearGradientPaint` `fill` 次数 —— **本项目"淡出"的唯一笔刷类型**。 */
    var linearGradientFills: Int = 0
        private set

    /** `GradientPaint` `fill` 次数（另一条渐变的形状，本项目当前不用，但必须能数到）。 */
    var gradientPaintFills: Int = 0
        private set

    /** 其它 `Paint`（自定义笔刷 / `TexturePaint` …）——**非零即说明有新路径**。 */
    var otherFills: Int = 0
        private set

    /** 总 `fill` 次数。 */
    val totalFills: Int get() = plainColorFills + linearGradientFills + gradientPaintFills + otherFills

    /** **所有**渐变类笔刷的次数 —— `fade_top = false` 时它必须是 `0`。 */
    val gradientFills: Int get() = linearGradientFills + gradientPaintFills

    internal fun record(paint: Paint?) {
        when (paint) {
            null -> otherFills++
            is Color -> plainColorFills++
            is LinearGradientPaint -> linearGradientFills++
            is GradientPaint -> gradientPaintFills++
            else -> otherFills++
        }
    }

    /** 清空计数（测下一帧前调用）。 */
    fun reset() {
        plainColorFills = 0
        linearGradientFills = 0
        gradientPaintFills = 0
        otherFills = 0
    }

    /**
     * **本对象的快照**（值拷贝）—— 给"跨 case 比较"用。
     *
     * ## ⚠️ 2026-09-23 新增：修一个让 5 条断言全部失真的别名 bug
     *
     * 探针的 `runCase` 原先写的是 `return session.paintTrace` ——
     * **返回的是同一个可变对象的引用** ✗ ⇒ 每次 `runCase` 都 `reset()` 并覆盖上一次的结果 ✗
     * ⇒ **所有"跨 case 比较"都在拿同一个对象和自己比** ✗✗ ⇒ 那些断言**恒真或恒假**，
     * 与产品行为无关。
     *
     * **实测签名**（`SpectrumPaintTraceProbe`）：同一个 `noFade`，
     * 在第 224 行读到 `gradientFills == 0` ✓（此刻确实是 0 ✓），
     * 到第 254 行变成 `91` ✗ —— **一个变量不可能既是 0 又是 91**，这就是别名。
     *
     * ⇒ 调用方（`runCase`）必须拿 [snapshot]，不能拿本对象。
     * **判据**：`runCase(a) !== runCase(b)`（探针里有断言守这条）。
     */
    fun snapshot(): PaintTypeTrace = PaintTypeTrace().also {
        // 同类的私有 setter 可跨实例访问（Kotlin 语义）⇒ 不必放宽可见性。
        it.plainColorFills = plainColorFills
        it.linearGradientFills = linearGradientFills
        it.gradientPaintFills = gradientPaintFills
        it.otherFills = otherFills
    }

    /** 一行摘要（探针报告用）。 */
    fun summary(): String =
        "纯色=$plainColorFills 线性渐变=$linearGradientFills 渐变=$gradientPaintFills 其它=$otherFills 合计=$totalFills"

    override fun toString(): String = summary()
}

/**
 * 把 [spectrumPolylinePoints] 写出的点列装进**一条闭合路径**，顶部折点可圆角
 * —— 公开纯几何，**真机与预览共用**（`spectrum.bar_corner` 在折线形态下的落地点）。
 *
 * ## ★★ 2026-09-27：为什么必须有它（否则 `bar_corner` 在实心档是**死设置**）
 *
 * 首版折线分支直接 `moveTo/lineTo` 顶点再 `closePath()` —— 于是**所有折点恒为尖角**，
 * 无论用户选 `ROUND` 还是 `SQUARE`。而 `ROUND` 是 `SpectrumBarCorner.DEFAULT`
 * ⇒ **默认档的设置毫无作用**，正是本项目 `fixed_width` 那类**死设置**的形态。
 * 探针为此新增 §⑦：`ROUND` 与 `SQUARE` 两档的**路径必须可分辨**。
 *
 * ## 几何：只圆**折点**，两个基线角点恒直角
 *
 * 用户原话（2026-09-26，逐字）：
 *
 * > 「频谱条的圆角我想要的是**胶囊那种大 R 角**，不是圆角矩形。
 * >   注意**根部不要应用圆角**。」
 *
 * 折线形态下的"根" = **基线** ⇒ 索引 `0`（左下角）与 `last`（右下角）**恒直角**；
 * 索引 `1 … last-1`（整条顶部折线）按 [radiusPx] 圆角。
 *
 * 每个折点的圆角做法（标准"圆折线"构造，**不引入 `BasicStroke`**）：
 * 沿入边方向在折点**前** `r` 处进、沿出边方向在折点**后** `r` 处出，
 * 中间以折点自身为控制点用**二次贝塞尔**过渡 —— 与 [barSegmentPath]
 * 用 `quadTo` 画圆角同源，也避免为描边新建 `BasicStroke` 对象（热路径零分配）。
 *
 * ⚠️ `r` 被夹到**相邻两边长度的一半**：否则进/出点会越过对方、路径自交
 * （Java2D **不报错**，只是填充结果怪 —— 与 [barSegmentPath] 的 `arc` 夹取同源）。
 *
 * ⚠️ **半径量级待真机定**：本函数只按调用方给的 [radiusPx] 画。
 * 真机侧传的是 `spectrumPolylineCornerPx(段宽, MIN_BAR_HEIGHT, barCorner)`
 * ⇒ 实心档下半径上限 = `MIN_BAR_HEIGHT / 2` = **1px**（"与柱状档同源"）。
 * 而施工单 §4.2 写的是 `min(段宽, 段高)/2`（最多约 13px）——
 * **两者差别很大**，属**未验证项**（见施工单 §7 回填），不得声称"观感已定"。
 *
 * @param path 复用容器（**会被 `reset()`**）。绘制热路径不新建对象。
 * @param pts [spectrumPolylinePoints] 写出的点列（`x0, y0, x1, y1, …`）
 * @param pointCount [spectrumPolylinePoints] 的返回值（写入的点数）
 * @param radiusPx 折点圆角半径（px，会被夹取）。`<= 0` ⇒ 全尖角（= `SQUARE` 档）
 * @return 是否写出了路径（点数不足 / 点列含非有限值 ⇒ `false`，且 `path` 已 `reset`）
 */
fun spectrumPolylinePath(
    path: Path2D,
    pts: FloatArray,
    pointCount: Int,
    radiusPx: Float,
): Boolean {
    path.reset()
    // 至少需要 [左下角, 一个柱顶, 右下角] 三个点才构成面积。
    if (pointCount < 3) return false
    if (pts.size < pointCount * 2) return false
    val r0 = if (radiusPx.isFinite()) radiusPx.coerceAtLeast(0f) else 0f
    val last = pointCount - 1
    if (!pts[0].isFinite() || !pts[1].isFinite()) return false
    // 起点 = 左下角（**恒直角** —— 用户"根部不要应用圆角"）。
    path.moveTo(pts[0].toDouble(), pts[1].toDouble())
    // 顶部折线：索引 1 … last-1，每个折点可圆角。
    for (i in 1 until last) {
        val vx = pts[i * 2]
        val vy = pts[i * 2 + 1]
        val px = pts[(i - 1) * 2]
        val py = pts[(i - 1) * 2 + 1]
        val nx = pts[(i + 1) * 2]
        val ny = pts[(i + 1) * 2 + 1]
        if (!vx.isFinite() || !vy.isFinite() || !nx.isFinite() || !ny.isFinite()) return false
        val inLen = sqrt((vx - px) * (vx - px) + (vy - py) * (vy - py))
        val outLen = sqrt((nx - vx) * (nx - vx) + (ny - vy) * (ny - vy))
        val r = minOf(r0, inLen / 2f, outLen / 2f)
        if (r <= 0f || inLen <= 0f || outLen <= 0f) {
            // 尖角（`SQUARE` 档 / 零长边）：直接连到折点，**不引入任何退化曲线**。
            path.lineTo(vx.toDouble(), vy.toDouble())
            continue
        }
        // 入点 = 折点沿**入边方向**后退 r；出点 = 沿**出边方向**前进 r。
        val ax = vx - (vx - px) / inLen * r
        val ay = vy - (vy - py) / inLen * r
        val bx = vx + (nx - vx) / outLen * r
        val by = vy + (ny - vy) / outLen * r
        path.lineTo(ax.toDouble(), ay.toDouble())
        path.quadTo(vx.toDouble(), vy.toDouble(), bx.toDouble(), by.toDouble())
    }
    // 右下角（**恒直角**）→ `closePath()` 沿基线回到左下角。
    if (!pts[last * 2].isFinite() || !pts[last * 2 + 1].isFinite()) return false
    path.lineTo(pts[last * 2].toDouble(), pts[last * 2 + 1].toDouble())
    path.closePath()
    return true
}
