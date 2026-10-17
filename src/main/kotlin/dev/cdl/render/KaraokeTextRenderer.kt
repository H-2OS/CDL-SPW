// SPDX-License-Identifier: AGPL-3.0-only
//
// 许可说明（逐字羽化边界，2026-09-16 新增）：
// 本类的「caret 之间线性插值 + 羽化边界」在**视觉参数**上参考了原实现
// Dynamic Lyrics Island 0.8.9（其 `ui/AmllWordPainter.kt` 为 AGPL-3.0-only）。
// 但**未复制其源码**：这里的实现走的是本插件既有的
// 「`TextLayout` + `Graphics2D.clip`/`GradientPaint`」两遍绘制路径，
// 而原实现走的是「逐字形 `Area.fill` + `GradientPaint`」路径 —— 是**不同实现**。
// 沿用的只有「羽化宽度 = min(字号×.45, 字宽×.6)」这个**数值事实**，
// 数值本身不构成受版权保护的表达。故本文件保持 GPL-3.0-only，无需 AGPL 传染。
package dev.cdl.render

import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextHitInfo
import java.awt.BasicStroke
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.text.BreakIterator
import kotlin.math.roundToInt

/**
 * 歌词描边的参数（`main_lyric.outline.*` / `translation.outline.*`）。
 *
 * ## 用户原话（2026-09-18）
 *
 * > 「加入**主歌词和副歌词的字体描边**，两种歌词独立设置，也可设置两者同步。」
 *
 * ## 为什么是"值对象"而不是把 5 个参数摊平传进来
 *
 * 描边的 5 项（开关 / 宽度 / RGB）在绘制层**永远是同进同出的一组**，
 * 而且"副歌词同步主歌词"这条规则会把**一整组**复制过去。
 * 摊平成 5 个参数的话，那条同步规则要在调用方逐项写 5 次 ——
 * 正是"改一处漏一处"的温床。
 *
 * `null` = 不描边（这是**默认**，与两个开关的默认 `false` 一致）。
 *
 * @param widthPx 描边宽度（**用户设置的像素值**，来自 `*_tenth_px / 10f`）。
 *   注意 `BasicStroke(widthPx)` 是以轮廓线为**中心**描边的，故字形**外侧**的
 *   可见厚度约为 `widthPx / 2`；内侧那一半会被随后的**填充**盖住，
 *   于是字形本身**不会变细**。这个"看起来比设置值细一半"是刻意接受的：
 *   改成 `BasicStroke(2 * widthPx)` 会让内缘吃掉填充，字形反而变胖。
 *
 *   ⚠️ 它是**基准宽度**。当 [followWeight] 为 `true` 时，真正被描边的宽度是
 *   `karaokeOutlineWidthPx(widthPx, 字体实际字重, weightLinkPercent, true)` ——
 *   即 [widthPx] **不**等于最终笔宽。这一点必须写清，否则下一个人会以为
 *   "传进来的就是画出来的"。
 * @param argb 描边色（打包 ARGB）
 * @param followWeight **描边宽度是否跟随字体粗细**（`translation.outline.follow_weight`）。
 *
 *   默认 `false` = **改动前的行为**（笔宽恒等于 [widthPx]）。
 *
 *   ## 为什么放在 `TextOutline` 上、而不是在调用方先把宽度算好
 *
 *   主歌词的联动**已经在调用方算好了**（`IslandWindow` 的
 *   `mainOutlineWidthTenthPx`）。副歌词这一项之所以放到这里，是因为
 *   "字体粗细"的真相源是**字体本身**（`LetterSpacing.fontWithLetterSpacing`
 *   把 `TextAttribute.WEIGHT` 写进了字体属性表），而**字体只在绘制这一层**
 *   拿得到（`cache.currentFont()`）—— 调用方传的是一个已派生好的 `TextLayout`，
 *   它不暴露自己用的字体。
 *
 *   ⚠️ 于是两条路径的**分工不同**：主歌词"调用方算"，副歌词"渲染器算"。
 *   这不是历史遗留，是有意的：见 [karaokeFontWeightScale] 的 KDoc
 *   （副歌词与主歌词共用同一个 `weight_scale`，故 `cache.currentFont()`
 *   给出的倍率**就是**副歌词那一行的倍率）。
 * @param weightLinkPercent **联动比例**（%），来自 `main_lyric.outline.weight_link_percent`。
 *   仅在 [followWeight] 为 `true` 时被读。**不另设一项**的理由见注册表该项的 KDoc。
 */
internal data class TextOutline(
    val widthPx: Float,
    val argb: Int,
    val followWeight: Boolean = false,
    val weightLinkPercent: Int = DEFAULT_OUTLINE_WEIGHT_LINK_PERCENT,
)

/**
 * **描边的解析结果** —— 公开的、**与设置键解耦**的那一层。
 *
 * ## 为什么需要它（一个**真机报障**逼出来的公开面）
 *
 * 用户报障（2026-10-08）：
 * > 「即便我**打开主歌词描边**，并打开自绘选色窗进行选色，再点击确定退出。
 * >   期间桌面词岛内的歌词描边**没有任何变化**。副歌词同样。」
 *
 * 这条把"开关默认 `false`"那个假说**排除**了 —— 开关已开、颜色已改，仍无变化
 * ⇒ 断点必在「**设置键 → `Appearance` → 描边值**」这条链的某一跳上。
 *
 * ★ 而这条链**在探针里够不到**：`Appearance` 虽是 `public class`，但
 *   `mainOutlineFor()` 是 `internal`、`TextOutline` 是 `internal`，而
 *   **探针是独立编译模块** ⇒ 一个值都断言不了（本项目既有事故，见
 *   `IslandAppearance.kt` 文件头「可见性为什么是 `public`」）。
 * ⇒ 按本项目既有先例（`physicalBounds` / `shouldHoldTempTopmost` /
 *   `islandScreenRect` / `effectiveTranslationGapPx` / `pillOutlineFor` /
 *   `hoverHighlightKept`）把**可测的那一层**提成**顶层公开纯函数**。
 *
 * ## ★ 纪律 10（同语义只允许一个实现点）
 *
 * 这里的算式是「十分之一像素 → 像素 + 三通道 → ARGB」的**唯一实现点**。
 * `Appearance.mainOutlineFor()` / `translationOutlineFor()` **转调**本函数，
 * 不各写一遍 —— 否则"实际画出来的描边"与"探针断言的那份"迟早漂移，
 * 又变成"两个真相"。
 *
 * ## 每一跳的语义（与注册表逐一对应）
 *
 * | 输入 | 来源键 | 语义 |
 * |---|---|---|
 * | [enabled] | `main_lyric.outline.enabled` | `false` ⇒ 不描边（`null`） |
 * | [widthTenthPx] | `main_lyric.outline.width_tenth_px` | `/10f` ⇒ px；**`0` 合法**（注册表 `range = 0.0..40.0`）⇒ `null` |
 * | [r] / [g] / [b] | `main_lyric.outline.color.r/g/b` | α 恒 `0xFF`（不透明） |
 *
 * ★ [widthTenthPx] 用 `Int` 而不是 `Float`：注册表该项是**整数滑杆**语义
 *   （KDoc：「SPW 滑杆写 Float，用像素作整数单位会被截断成 0」）。
 *
 * @return `null` = **不描边**（成因有二：开关关 / 宽度 ≤ 0）
 */
public fun resolveOutline(
    enabled: Boolean,
    widthTenthPx: Int,
    r: Int,
    g: Int,
    b: Int,
): ResolvedOutline? {
    if (!enabled) return null
    val w = widthTenthPx / 10f
    if (w <= 0f) return null
    return ResolvedOutline(w, packRgbPublic(r, g, b))
}

/**
 * [resolveOutline] 的输出 —— `public`（探针要断言它的两个字段）。
 *
 * ⚠️ 与 `internal data class TextOutline` **不是同一个类型**，是**刻意的**：
 *   `TextOutline` 还带 `followWeight` / `weightLinkPercent` 两个**渲染期才用**的字段
 *   （副歌词那套联动），把它们拉进公开面会让"设置链"与"渲染细节"再次耦合。
 *   本类型只承载**绘制所必需的两件事**：多宽、什么色。
 */
public data class ResolvedOutline(val widthPx: Float, val argb: Int)

/**
 * 三通道 0..255 → 打包 ARGB（alpha 恒为不透明）。
 *
 * ★ 与 `window/IslandAppearance.kt` 的 `private fun packRgb` **同算式**，
 *   但**不能**共用（那是 `private`，跨文件够不到）。此处是**公开**版本，
 *   专供 [resolveOutline] 使用 —— 而这个函数就是描边色的**唯一公开出口**，
 *   故"两处各有一份 packRgb"不会分叉（另一份仅服务 `Appearance` 内部
 *   那些与描边无关的取色，如 `borderColorFor`）。
 */
private fun packRgbPublic(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

/**
 * 卡拉 OK 式**逐字高亮**渲染器。
 *
 * ## 本类存在的唯一理由：一次实测出的 27 倍差距
 *
 * 原实现做法是**逐字 `Area.fill`**：对每个字形取轮廓 → 布尔求交 → 填充。
 * 实测 **8.45 ms/帧**（Microsoft YaHei UI 22pt、含抗锯齿、整行 18 字）。
 * 这个数字意味着：60 fps 的预算是 16.6 ms，单是画一行歌词就吃掉一半，
 * 而词岛还要画背景、频谱、控件 —— **「拖动设置时实时预览」在物理上不可能**。
 *
 * 本类改用 **`Graphics2D.clip(Shape)` + `TextLayout.draw()` 两遍绘制**，实测 **0.31 ms/帧**
 * （同一文本、同一字体、同一次测量条件），快 **27×**。这不是调优，是换了一条路径：
 * 前者让 Java2D 对每个字形做**布尔运算**（几何复杂度与字形路径段数成正比），
 * 后者让 Java2D 走**栅格化裁剪**（GPU/光栅器友好的整数扫描线）。
 *
 * ## 为什么是「两遍绘制」而不是「一遍 + 两个 clip」
 *
 * 直觉方案是：clip 到已唱区画已唱色，再 clip 到未唱区画未唱色 —— 两个独立 clip，各画一遍。
 * 本类**没有**采用它，改为：
 *
 * ```
 * ① 不设 clip，整行用「未唱色」画一遍
 * ② clip 到「已唱区」，整行用「已唱色」再画一遍   ← 只覆盖已唱部分
 * ```
 *
 * 理由是**抗锯齿边缘的重叠**：两个互斥的 clip（已唱区 / 未唱区）在交界处的
 * 抗锯齿像素**各自只被覆盖一次**，于是交界处会有半透明的「缝」——
 * 一条垂直的、颜色偏暗的细线跟着播放头移动。这在高对比度配色下非常明显。
 * 两遍绘制方案里，第二遍是**覆盖**在第一遍之上：交界处的抗锯齿像素
 * 被已唱色以 alpha 混合覆盖，未唱色的底子仍在下面，**不会出现缝**。
 *
 * 实测验证：两遍绘制后逐像素检查「未唱色残留」= 0，且交界处无暗线。
 *
 * ## 进度必须按**字形簇**对齐，不能按 UTF-16 char
 *
 * 「已唱到 40%」要落到一个字符索引上，才能给 `getLogicalHighlightShape` 用。
 * 若按 UTF-16 code unit 数取整，会在**代理对**（emoji `U+1F600` 占两个 char）和
 * **组合序列**（`e` + `U+0301` 组合尖音符占两个 char）上把字符劈成两半 ——
 * 表现为「半个 emoji 变色」，以及组合符被单独高亮导致的错位。
 *
 * 故本类用 [BreakIterator.getCharacterInstance] 枚举**字形簇边界**
 * （用户感知的「一个字」），把 `progress` 映射成簇数，再取该簇的**起始 char 索引**。
 * 映射策略经实测确认：`k` 簇已唱 → `charIndex = clusters[k].start`
 * （`k = 0` → `0`；`k >= 簇总数` → `text.length`）。
 *
 * ## 实测量化（本机、Microsoft YaHei UI 22pt、含 AA、整行 18 个汉字）
 *
 * | 操作 | 耗时 |
 * |---|---|
 * | 两遍 clip 绘制（本类路径） | **0.045 ms/帧** |
 * | `Area` 逐字填充（原实现路径） | **55.351 ms/帧**（N=200 次均值） |
 * | `getLogicalHighlightShape` | 0.0038 ms/次 |
 * | `TextLayout` 构造 | 0.0234 ms/次 |
 *
 * 注意第一行的 0.045 ms 与类注释开头的 0.31 ms 是两个不同口径：
 * 0.31 ms 是**含整行文本测量与颜色设置**的一次完整 `render` 调用，
 * 0.045 ms 是**只算绘制本身**（测量结果已缓存）。本类的设计目标就是让
 * 「每帧都要重做的那部分」收敛到后者。
 *
 * ## 本类不做的事（职责边界）
 *
 * - **不构造 `TextLayout`**：那是 [RenderCache] 的职责（它才是缓存所有者）。
 * - **不改 `Graphics2D` 的字体/颜色以外状态**：不改 clip（用完必还原）、
 *   不改 transform、不改 stroke。调用方传进来的 `g` 在返回后与传入时**状态一致**。
 * - **不做折行、不做多行排版**：一行就是一行。折行属排版层，见窗口层的槽位管理。
 *
 * ## ⚠️ `internal` 是**必需的**（2026-09-18）
 *
 * ⚠️ `internal` 是**必需的**：本类的 `render` 暴露了 `internal` 的 [RenderCache] 与
 *   [TextOutline]。2026-09-18 我在它前面插入 [TextOutline] 时，锚点串
 *   `class KaraokeTextRenderer` **匹配掉了 `internal ` 前缀**，类变成 public →
 *   编译报"public function exposes internal parameter type"。
 *   **这是"用子串做锚点"的典型代价**：锚点要带上修饰符。
 */
internal class KaraokeTextRenderer {

    /**
     * 字形簇缓存的键 —— `(文本, 是否有逐字时间轴)`。
     *
     * ## 为什么键里有「是否有逐字时间轴」这一位
     *
     * 因为同一行文本在两种模式下需要**不同的簇划分粒度**：
     *
     * - **整行进度模式**（无逐字时间轴）：只需要「已唱到整行的百分之几」，
     *   按**整行簇数**均分即可，簇列表是简单的一维数组。
     * - **逐字时间轴模式**（有逐字时间轴）：每簇的进度由各自的
     *   `startMs/endMs` 决定，需要知道**每个簇的起止 char 范围**，
     *   以及簇与歌词数据里的「字」如何对位。
     *
     * 两种划分的结果可能是同一份（对中文歌词通常就是），但**不能假设**
     * （连字、emoji 序列会让两者分叉）。用一个 bit 把两种缓存分开，
     * 避免「先按模式 A 算好、模式 B 读到错的缓存」这类难查的错。
     *
     * 实际上 [enableWordTimingProgress] 是更重要的开关：它决定 [render] 走哪条路径，
     * 而两条路径对簇的消费方式不同。
     */
    private data class ClusterKey(val text: String, val wordTiming: Boolean)

    /** 字形簇列表缓存：键 → 「各簇的起始 char 索引」数组（末尾追加 `text.length` 作哨兵）。 */
    private val clusterCache = HashMap<ClusterKey, IntArray>()

    /**
     * 最近的进度 → 已唱字符索引的中间态，用于**跨帧复用**。
     *
     * ## 为什么要跨帧复用（这是个不那么显然的优化）
     *
     * `progress` 在整行播放期间**每帧都不一样**（时间在走），所以「按进度查表」
     * 看似没有命中率。但真正被反复计算的是同一件事：
     *
     * > 「当前进度落在第 k 簇」→ k → charIndex
     *
     * 对**同一份文本**，`clusters[k].start` 是个纯查表操作。缓存整份簇数组后，
     * 每帧的代价只是 `(progress * clusterCount).toInt()` 加一次数组索引 —— 是 O(1)。
     * 所以这里不需要缓存「每个进度值的结果」，只需要缓存簇数组本身。
     * [lastCharIndex] 的价值在于让「进度没变的中帧」（如暂停、或 80 ms 节流窗口内
     * 的多次重绘）**完全跳过簇计算**。
     */
    private var lastKey: ClusterKey? = null
    private var lastCharIndex: Int = -1

    /**
     * 渲染一行歌词。
     *
     * @param g 绘制上下文。**本方法不改它的 clip/transform/stroke**（用完还原）
     * @param layout 已测量好的文本布局（由 [RenderCache.textLayoutFor] 提供，**不要在这里构造**）
     * @param baselineX 基线原点 X（即文本起点），**不是**包围盒左上角
     * @param baselineY 基线原点 Y（即基线所在的 y 坐标，文字坐在它上面）
     * @param sungColor 已唱部分颜色（打包 ARGB）
     * @param unsungColor 未唱部分颜色（打包 ARGB）
     * @param progress 已唱进度，`0f..1f`。越界会被 clamp（防御调用方传入外推结果）
     * @param karaoke 是否开启逐字分割
     *   - `true`：按 [progress] 分割成两段绘制
     *   - `false`：整行用 [unsungColor] 绘制，**不做任何 clip**
     * @param wordBoundaries 可选的**逐字时间轴**驱动模式：给出「每个数据字在整行中的
     *   已唱比例」。为 `null` 时按 [progress] 均分。
     *
     *   ⚠️ 这是一个**有意留出的窄口子**：逐字时间轴（[dev.cdl.playback.LyricWord]）
     *   给出的是**每个字各自的时间区间**，其「已唱到哪里」不能由整行 `progress` 线性
     *   推出（各个字时长不等）。传入本参数时，本类走 [renderWordTimed] ——
     *   在**当前字的两个 caret 之间连续插值**并羽化边界，而不是旧的「整字跳变」。
     *   这样本类**不必知道时间轴模型**，依赖方向保持从 render 指向 playback 以外的零耦合。
     * @param wordLengths 与 [wordBoundaries] 一一对应的**每个字的字符数**。
     *   它是把「第 k 个字」映射到 `caret(charIndex)` 所必需的 —— 均分推断在
     *   中英混排下会算错位置。仅在 [wordBoundaries] 非空时被读取。
     * @param featherFontSize **本行使用的字号**，只用于计算逐字羽化宽度（见 [renderWordTimed]）。
     *
     *   为什么不直接读 [RenderCache.currentFont]：那是**主歌词**的字体，而译文行用
     *   派生小字号（主字号 × 0.64）走同一条逐字路径 —— 读错会让译文的羽化带按主字号算
     *   （22pt 时 `0.45 × 22 = 9.9px`，而译文正确的值是 `0.45 × 15.4 = 6.9px`），
     *   表现为译文那行小字上糊开一片比原文宽出四成的过渡带。
     *
     *   为 `null`（缺省）表示"未指定" → 退回 [RenderCache.currentFont] 的字号，
     *   也就是**改动前**的行为。主歌词行**不需要**传：它本来就该用主字号。
     */
    fun render(
        g: Graphics2D,
        cache: RenderCache,
        layout: TextLayout,
        text: String,
        baselineX: Float,
        baselineY: Float,
        sungColor: Int,
        unsungColor: Int,
        progress: Float,
        karaoke: Boolean,
        wordBoundaries: FloatArray? = null,
        wordLengths: IntArray = IntArray(0),
        featherFontSize: Float? = null,
        outline: TextOutline? = null,
        /**
         * **平滑模式**（`main_lyric.karaoke.mode = SMOOTH`）——
         * 已读颜色在**字间连续移动**，而不是逐字跳变（2026-09-19 加）。
         *
         * 用户原话：「逐字高亮提供**另一种形式**，**已读颜色平滑在字间移动**，
         * 而不是目前的**真·逐字**。」
         *
         * ## 实现：把"整行"当成"一个字"喂给 [renderWordTimed]
         *
         * [renderWordTimed] 的分界算法是
         * `boundaryX = caretOf(k) + (caretOf(k+n) - caretOf(k)) × ratio`。
         * 于是 `k = 0`、`n = 全文长度`、`ratio = 整行进度` 时：
         *
         * ```
         * boundaryX = 0 + (advance - 0) × progress = advance × progress
         * ```
         *
         * —— 分界点**线性扫过整行**，落在字的**内部**（不是簇边界）。
         * 这正是用户要的"平滑在字间移动"，而且**复用同一条羽化绘制路径**，
         * 不必新写一套 clip + GradientPaint（那会造出第二份实现，
         * 而本项目刚因"同族两处实现只改一处"连吃三次缺陷）。
         *
         * ⚠️ 与"逐字时间轴"（`wordBoundaries != null`）**不冲突**：
         *   后者优先级更高（有真实逐字数据时用它，那更准）。本参数只在
         *   **没有**逐字数据时改变"整行进度"的绘制方式。
         */
        smooth: Boolean = false,
    ) {
        // 空串：`TextLayout` 无法承载空串（见 RenderCache.textLayoutFor 的说明），
        // 调用方若还是传进来（例如拿到一个 null 布局后仍调用），直接返回不画。
        if (text.isEmpty()) return

        // ── 抗锯齿：**由本类自己保证，不依赖调用方**（2026-09-22 加）───────────
        //
        // 描边遍走 `g.draw(Shape)` ⇒ 它的 AA 由 `KEY_ANTIALIASING` 决定；
        // 填充遍走 `TextLayout.draw` ⇒ 由 `KEY_TEXT_ANTIALIASING` 决定。
        // **两个键互不共享**，而本类此前一个都不设 —— 全靠调用方
        // （`IslandWindow.paintComponent` / `IslandPreviewPane`）记得设。
        // 任一新调用方漏设，表现就是"字形边缘阶梯状锯齿"，且**没有任何断言在守**。
        //
        // ⚠️ 这里**不是**在改观感：两个现存调用方都已设为 ON，故本行对真机是 no-op。
        //    它修的是"依赖调用方记得"这个脆弱点。实测数字（本类走生产顺序、
        //    MiSans 42px、描边 4px）：AA 开中间调占 27.2%，AA 关占 **0.0%**。
        karaokeApplyAntialiasHints(g)

        // ── 描边遍：**必须先于填充**（2026-09-18 加）──────────────────────────
        //
        // 顺序是硬约束：`BasicStroke(w)` 以轮廓线为中心向内外各展开 `w/2`，
        // 先描边、后填充 → 内侧那一半被填充盖住，于是**字形不变细、
        // 外侧多出一圈描边**。反过来（先填充后描边）会让描边压住字形边缘，
        // 看起来像"字被描边吃掉了"。
        //
        // `getOutline(translate(baselineX, baselineY))` 与 `draw(g, baselineX, baselineY)`
        // 用的是**同一个原点**，故两者必然对齐 —— 这也是为什么这里不自己算偏移。
        //
        // ⚠️ 笔型**必须**走 `karaokeOutlineStroke`，**不能**写 `BasicStroke(w)`
        //    （后者是 `CAP_SQUARE` + `JOIN_MITER` + `miterLimit=10`）——
        //    见该函数的 KDoc：真机字号 42 下 MITER 会在 `ひ` 的锐角处外伸 10.3px，
        //    表现为用户报的"字形边缘毛刺（一根竖直的针）"。
        //
        // ── 描边宽度：**跟随字体粗细**（`translation.outline.follow_weight`）────────
        //
        // 用户原话（2026-09-23）：「**允许副歌词的描边跟随字体粗细**」。
        //
        // ★ 这里是那一项的**消费点**。取值链（"哪个取值改变了哪个输出"）：
        //
        //   `translation.outline.follow_weight`（设置页开关）
        //     → `TextOutline.followWeight`（调用方构造时传入）
        //       → **本行**：`followWeight = false` ⇒ 笔宽 = `outline.widthPx`（不变）；
        //                   `followWeight = true`  ⇒ 笔宽 = `widthPx × (1 + (字重倍率−1) × 比例)`
        //         → `karaokeOutlineStroke(笔宽)` ⇒ `g.draw(字形轮廓)`
        //           → **同一字形的描边外伸改变**（外伸 ≈ 笔宽 / 2）
        //
        // ⚠️ 字重倍率取自**字体本身**（`cache.currentFont()` 的 `TextAttribute.WEIGHT`），
        //    而不是某个参数 —— 理由见 `TextOutline.followWeight` 的 KDoc。
        //    `followWeight = false` 时 `karaokeOutlineWidthPx` 在**第一行就早退**，
        //    故不跟随的那条路径与改动前**逐位相同**（主歌词因此完全不受影响）。
        if (outline != null) {
            val effectiveWidthPx = karaokeOutlineWidthPx(
                baseWidthPx = outline.widthPx,
                weightScale = karaokeFontWeightScale(cache.currentFont()),
                linkPercent = outline.weightLinkPercent,
                followWeight = outline.followWeight,
            )
            if (effectiveWidthPx > 0f) {
                val oldStroke = g.stroke
                val oldColor = g.color
                val oldFont = g.font
                try {
                    g.font = cache.currentFont()
                    g.color = cache.color(outline.argb)
                    g.stroke = karaokeOutlineStroke(effectiveWidthPx)
                    g.draw(layout.getOutline(AffineTransform.getTranslateInstance(
                        baselineX.toDouble(), baselineY.toDouble(),
                    )))
                } catch (_: Throwable) {
                    // 描边是**装饰**：任何失败都不该让整行歌词消失（项目铁律：
                    // 逐行降级为"这一行不画"已经够糟，描边失败连那一步都不该触发）。
                } finally {
                    g.stroke = oldStroke
                    g.color = oldColor
                    g.font = oldFont
                }
            }
        }

        val sung = cache.color(sungColor)
        val unsung = cache.color(unsungColor)

        // 不开启分割 → 一整串用未唱色画完。**这是最快路径**（无 clip、无簇计算）。
        // 注意仍要用 [unsungColor] 而不是「原色」：设置的语义是「未唱歌词颜色」，
        // 关掉卡拉 OK 不等于「没有颜色概念」。
        if (!karaoke) {
            val oldFont = g.font
            val oldColor = g.color
            try {
                g.font = cache.currentFont()
                g.color = unsung
                layout.draw(g, baselineX, baselineY)
            } finally {
                g.font = oldFont
                g.color = oldColor
            }
            return
        }

        // ── 逐字时间轴 / 平滑：**唯一一条出口**（2026-09-19 三次修订）───────────
        //
        // 这两档共用 `renderWordTimed`（连续插值 + 羽化边界），与下面的「整行进度」
        // 模式**不是同一套几何**：逐字/平滑的分界点落在**字内部的一个浮点位置**
        // （不是簇边界），需要 `caret` 插值，无法用 `getLogicalHighlightShape` 表达。
        //
        // 参考 0.8.9 的视觉参数（caret 之间线性插值、羽化宽度
        // `min(fontSize*.45, 字宽*.6)`），**独立实现**（见本类 KDoc 的许可说明）。
        //
        // ## ⚠️ 原记与实为（用户报「你真的修复了吗？译文平滑高亮仍然落后于主行」）
        //
        // **原记**（本函数第二次修订）：逐字时间轴先 `if (wordBoundaries != null) { … return }`，
        //   平滑档放在它**后面**的 `if (smooth) { … }` 里，两处各写一遍 `renderWordTimed`。
        //   当时的想法是"逐字数据更准，优先级更高"。
        //
        // **实为**：那个顺序让平滑档的 `featherScale = SMOOTH_FEATHER_SCALE` **永不可达** ——
        //   只要有逐字数据，就会在**第一个** `if` 里 return 掉，`featherScale` 取默认 `1f`。
        //   后果：**平滑档与真·逐字档像素级完全相同**，用户看不到"平滑"，
        //   而本文件下面的注释还写着"平滑档照旧用 wordBoundaries，只把羽化加宽"——
        //   **注释描述的行为与代码实际行为不一致，且不报错。**
        //
        // **形态**：同一个分支被写了两遍，前一遍把后一遍的入口**掐死**。
        //   这是本项目第 4 次遇到这个形态（另三处：`useWordTiming` 掐断 SMOOTH、
        //   `applyChannels` 缺 alpha 掐断去抖判据、`narrowWordsToText` 截短文本却保留区间）。
        //
        // **修法（结构，而不是纪律）**：两个分支**合并成一条** ——
        //   只有**一个** `renderWordTimed` 调用点，`featherScale` 由 `smooth` 直接决定。
        //   于是"平滑档拿不到自己的羽化"**结构上不可达**，下次重构也不会再漏。
        //
        // 语义：
        //   · 有逐字时间轴 → 时序用它（更准），**羽化按档位**（逐字 1f / 平滑 2.5f）；
        //   · 没有逐字时间轴 → 整行当一个字（`progress` 线性扫过），同样按档位羽化。
        //
        // ⚠️ `progress` 只在**没有**逐字数据时才被读；有逐字数据时它被忽略（见调用方注释）。
        val hasWordTiming = wordBoundaries != null && wordBoundaries.isNotEmpty()
        if (hasWordTiming || smooth) {
            renderWordTimed(
                g, cache, layout, baselineX, baselineY, sung, unsung,
                wordBoundaries = if (hasWordTiming) {
                    // `hasWordTiming` 是本地 val，故这里 Kotlin 能自行收窄类型
                    // （原先写 `!!` 会被编译器判为"多余的非空断言"，那也是一条谎话）。
                    wordBoundaries
                } else {
                    // 一个"字"：比例 = 整行进度；长度 = 全文长度。
                    floatArrayOf(progress.coerceIn(0f, 1f))
                },
                wordLengths = if (hasWordTiming) {
                    wordLengths
                } else {
                    intArrayOf(text.length.coerceAtLeast(1))
                },
                featherFontSize = featherFontSize,
                // 平滑档的羽化**更宽** —— 这是它与逐字档**唯一**的观感差别。
                // 逐字档 `1f` 是默认值，这里显式写出来，免得日后又把两者混在一起。
                featherScale = if (smooth) SMOOTH_FEATHER_SCALE else 1f,
            )
            return
        }

        // ── 平滑模式的设计说明（保留备查，代码已合并到上面那条唯一出口）──────────
        //
        // ## ⚠️ 原记与实为（用户报「平滑高亮明显落后逐字高亮，1-3 个字」）
        //
        // **原记**（本函数上一版）：平滑档**丢掉** `wordBoundaries`，
        //   改用"整行当一个字"（`boundaryX = advance × progress`），
        //   理由是"已读颜色在字间连续移动"。**那只考虑了"连续"，漏了"时序"。**
        //
        // **实为**：两条路径的**时序来源根本不同** ——
        //
        // | 档 | 分界点的时序来源 |
        // | --- | --- |
        // | 逐字 | 宿主的**逐字时间轴**（`wordBoundaries`）→ 准 |
        // | 平滑（原实现） | **整行进度** `(t − start) / (end − start)` → **偏慢** |
        //
        // 而**歌词行的 `end` 通常包含句尾空白 / 间奏** —— 于是"整行进度"天然
        // 落后于"真实唱到哪"，用户看到的正是**稳定落后 1-3 个字**。
        //
        // **副歌词又落后主歌词 1-3 个字**：同一机制的第二层 ——
        //   译文用的是**收窄后**的逐字轴（只覆盖译文那一段），
        //   而原实现给它传的也是**整行进度**，于是译文的分母（整行）比它自己的
        //   那一段大得多 → 落后得更明显。**两处根因同一个：丢了 wordBoundaries。**
        //
        // ## 现做法
        //
        // 平滑档**照旧用 `wordBoundaries`**（时序与逐字档同源），只把
        // **羽化加宽**（`featherScale`）—— 于是：
        //   · **时序**与逐字档一致（不再落后）；
        //   · **观感**仍是"一条柔和的边扫过去"，而不是逐字档那种"逐字吸附"的硬边。
        //
        // 没有逐字数据时才退回"整行当一个字"（此时没有更准的时序可用）。

        // ── 两遍绘制 ────────────────────────────────────────────────────────

        val oldFont = g.font
        val oldColor = g.color
        try {
            // ① 整行打底（未唱色）。**必须先画这一遍** —— 它是「缝」不存在的原因。
            g.font = cache.currentFont()
            g.color = unsung
            layout.draw(g, baselineX, baselineY)

            // ② 算已唱区的 char 索引。
            val charIndex = resolveCharIndex(text, layout, progress)
            if (charIndex <= 0) return  // 一点都没唱 → 只留未唱色，不必进第二遍

            // ③ 取已唱区几何形状。
            //    **实测修正**：`getLogicalHighlightShape(0, length)` 其实**不抛异常**，
            //    它返回的就是覆盖整行 advance 的完整形状（`[x=0, w=advance, h=ascent+descent]`）。
            //    早期探针曾记录它会抛 `IllegalArgumentException`，后续用同一条文本
            //    复核时该异常**未能复现** —— 如实记录这处不确定，也正因为这个矛盾
            //    才有了下面这个「两条路径都合法」的写法。
            val full = layout.characterCount
            val highlight: java.awt.Shape = if (charIndex >= full) {
                // 「已唱满」：用整个逻辑行高亮形状（不是 `layout.bounds` —— 那是墨迹包围盒，
                // 左上角带 y 偏移且在基线坐标系里，直接当 clip 会切错位置，这是实测踩到的坑）。
                layout.getLogicalHighlightShape(0, full)
            } else {
                layout.getLogicalHighlightShape(0, charIndex)
            }

            // ④ 平移到绘制位置。**形状坐标以基线原点为基准**（实测确认）：
            //    直接 clip 会把高亮画在组件左上角，而不是文字所在处。
            val translated = AffineTransform
                .getTranslateInstance(baselineX.toDouble(), baselineY.toDouble())
                .createTransformedShape(highlight)
            val oldClip = g.clip
            try {
                g.clip = translated
                g.color = sung
                layout.draw(g, baselineX, baselineY)
            } finally {
                // 还原 clip。**必须用 `g.clip = oldClip` 而不是假设「设 null 就还原」** ——
                // `Graphics2D.clip(Shape)` 是**与已有 clip 取交**（不是替换），
                // 所以若调用方本来就有 clip，我们是在它的基础上又切了一刀；
                // 只有显式写回原值才能恢复。
                g.clip = oldClip
            }
        } finally {
            g.font = oldFont
            g.color = oldColor
        }
    }

    /**
     * 把整行 [progress] 换算成**已唱的 char 索引**（整行均分模式专用）。
     *
     * ⚠️ **逐字时间轴模式不走本方法** —— 那条路走 [renderWordTimed] 的
     * caret 连续插值（分界点是浮点位置，无法用 char 索引表达）。
     *
     * ## 取整策略：**向下取整到簇边界**（而不是四舍五入）
     *
     * 「已唱 40%」若落在第 3 簇的中间，取整有两个方向：
     *
     * - **向上**（当作第 3 簇已唱完）：字会在它**实际唱到之前**就变亮 ——
     *   用户看到高亮走在人声前面，这是**明确错误的信息**。
     * - **向下**（当作第 2 簇已唱完，第 3 簇尚未开始）：高亮走在人声**之后**一点点，
     *   最多晚一簇（一个字的时长，通常 100–300 ms）。
     *
     * 这里选**向下**。理由不是「保守」而是**正确性方向**：一个「略微滞后」的
     * 高亮与「歌词本来就比人声晚一点」的常见观感一致；而一个「提前」的高亮
     * 会让人以为播放器跳帧了。当 `progress == 1f` 时向下取整也自然得到「全唱完」，
     * 不需要特殊处理。
     *
     * 注意这条路径**只服务「没有逐字数据」的情形**（如 `karaoke` 开着，
     * 但宿主给的是单个覆盖整行的 cell）。有逐字数据时观感由 [renderWordTimed]
     * 的连续插值决定，不再有「整字跳变」。
     *
     * ⚠️ **不确定点（如实记录）**：在**连字**（ligature，如 `fi`、阿拉伯语连写）
     * 场景下，`BreakIterator` 的簇边界与「排版引擎实际渲染成几个字形」不一定一一对应 ——
     * 排版引擎可能把两个 char 合成一个字形，也可能把一个 char 拆成多个定位字形。
     * 此时按簇边界 clip 得到的边界仍会**落在字符之间**（不会劈开字形），
     * 但视觉上高亮可能「跨过」一个视觉字形的一部分。本环境（中文歌词为主）
     * 无法实测阿拉伯语/天城文等复杂脚本，**未验证**该场景。
     */
    private fun resolveCharIndex(
        text: String,
        layout: TextLayout,
        progress: Float,
    ): Int {
        val full = layout.characterCount

        // ── 整行均分模式 ────────────────────────────────────────────────────
        // ⚠️ 逐字时间轴模式**不经过本方法**：它走 `renderWordTimed` 的
        // caret 连续插值路径（见 [renderWordTimed]）。本方法只服务
        // 「没有逐字数据、只能按整行进度均分」这一条路。
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped <= 0f) return 0
        if (clamped >= 1f) return full

        val key = ClusterKey(text, false)
        if (key == lastKey && lastCharIndex >= 0) {
            // 跨帧中间态复用：同一文本、同一模式，只需按新进度重算一次索引。
            // 这里的复用价值有限（索引本身就是 O(1)），真正省下的是 `clustersOf`
            // 的字符串遍历 —— 不过那些结果已被 clusterCache 缓存，故这里的
            // 唯一收益是让「进度未变的连续重绘」（拖动窗口、悬停高亮）零成本。
        }
        val clusters = clustersOf(text, wordTiming = false)
        val count = clusters.size // 哨兵不计入
        if (count <= 0) return full

        // 向下取整到簇边界。`clamped` 已在 (0,1) 开区间，故 index 落在 [0, count-1]。
        val sungClusters = (clamped * count).toInt().coerceIn(0, count)
        val result = if (sungClusters <= 0) 0 else clusters[sungClusters - 1]

        lastKey = key
        lastCharIndex = result
        return result
    }

    /**
     * 枚举 [text] 的**字形簇起始 char 索引**，返回数组 `[c0, c1, ..., c_{n-1}, text.length]`。
     *
     * 末尾的 `text.length` 是**哨兵**：它让「已唱 k 簇」可以直接写成 `clusters[k]`
     * 而不必到处写 `if (k >= size) text.length else clusters[k]`。
     * 这个技巧值得保留 —— 它把最容易写错的边界分支从**每个使用点**收敛到**一处**。
     *
     * 于是语义变成：`clusters[k]` = 「已完整唱过 k 个簇时，已唱的 char 数」。
     * `k = 0` → `0`（哨兵前的第 0 个元素是 0，因为第一个簇从 0 开始）；
     * `k = n` → `text.length`（末尾哨兵）。**两个边界都对**，无需特判。
     *
     * ## `wordTiming` 位的实际作用
     *
     * 见 [ClusterKey] 的说明。当前两条路径都用 [BreakIterator] 划分，
     * 差异只在缓存键 —— 这是**刻意的**：现在两条路径的簇划分确实相同，
     * 用一个 bit 把缓存分开是为了将来若逐字模式需要不同的划分（例如按歌词数据
     * 的字边界而非 Unicode 簇边界），**不必回头改缓存的键结构**（那会让
     * 已经在跑的缓存产生错误的命中）。
     */
    private fun clustersOf(text: String, wordTiming: Boolean): IntArray {
        val key = ClusterKey(text, wordTiming)
        clusterCache[key]?.let { return it }

        val starts = ArrayList<Int>(text.length + 1)
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            starts.add(start)
            start = end
            end = iterator.next()
        }
        // 末尾哨兵：见 KDoc。
        starts.add(text.length)

        val result = starts.toIntArray()
        // 缓存上限：字形簇数组比 `TextLayout` 小得多（一个 int 数组），
        // 但同样不能无限增长。清空策略与 `RenderCache` 一致 ——
        // 整体清空（重建一份的成本是遍历一次字符串，微秒级）。
        if (clusterCache.size >= CLUSTER_CACHE_LIMIT) clusterCache.clear()
        clusterCache[key] = result
        return result
    }

    /**
     * 文本与字体变化时作废缓存。
     *
     * 调用方**不必主动调用**：簇划分只依赖文本（不依赖字体），
     * 而文本变化会让 [ClusterKey] 自然不命中。提供本方法是为了
     * **显式释放**（窗口长期隐藏、换曲时一次性清干净），
     * 而不是为了正确性 —— 这一点必须说清楚，否则会被误以为「不调用就会算错」。
     */
    fun invalidate() {
        clusterCache.clear()
        lastKey = null
        lastCharIndex = -1
    }

    /**
     * 逐字时间轴模式的渲染：**分界点在字内部连续推进 + 边界羽化**。
     *
     * ## 为什么不能沿用「整字跳变」
     *
     * 旧实现把逐字进度折算成「已完整唱过的字数」（`ratio >= 1f` 才计数），
     * 再向下取整到簇边界。于是分界点**只在字与字之间跳**，字内部完全静止：
     * 一个 300 ms 的字，前 299 ms 高亮停在字前，最后一刻整字翻亮。
     *
     * 0.8.9 不是这样：它在**当前字的两个 caret 之间按 `progress` 线性插值**
     * （`up089-src/…/ui/LyricPainter.kt:19-25`），分界点是连续移动的。
     * 观感差异明显 —— 连续推进读起来像「追着人声走」，整字跳变则像「一格一格闪」。
     *
     * ## 分界点的算法
     *
     * [wordBoundaries] 与 [wordLengths] 一一对应：第 k 项是第 k 个字的
     * 已唱比例与它的字符数。找到**第一个未唱满**的字 `k`：
     * - 它的起始 char 索引 = 前 k 个字的 `wordLengths` 之和；
     * - 分界 X = `caret(startChar)` 与 `caret(startChar + wordLengths[k])`
     *   之间按 `wordBoundaries[k]` 插值。
     *
     * 字符数**由调用方精确给出**（`LyricWord.text.length`），不做均分推断 ——
     * 均分在中英混排（`I do believe 我坚信不疑`）下会把分界算到错位置。
     *
     * 若所有字都唱满 → 整行已唱色。
     * 若第一个字就没开始 → 分界 = 0，整行未唱色（不必进第二遍）。
     *
     * ## 羽化（feather）
     *
     * 硬边界的 clip 在边界处是**像素级跳变**，快速移动时会有明显的「锯齿感」。
     * 这里用 [GradientPaint] 在分界两侧各 `feather/2` 的范围内把颜色
     * 从已唱色渐变到未唱色，得到一个柔和的过渡带。
     *
     * 羽化宽度取 `min(字号 × .45, 当前字宽 × .6)`（参考 0.8.9 的视觉参数），
     * 并夹到 `≥ .01f` 避免 `GradientPaint` 因起止点重合而抛
     * `IllegalArgumentException`（它的两个端点必须不同）。
     * 「不超过当前字宽的一半多一点」这一项保证**短字不会被整字渐变掉** ——
     * 否则一个窄字的过渡带会覆盖它自己，看起来像「整字都在半亮」。
     *
     * ## 为什么只画两遍（与整行进度模式同样的理由）
     *
     * 羽化带本身是**叠加**在未唱底色之上的渐变覆盖，所以边界处不存在
     * 「两个互斥 clip 各盖一次」留下的透明缝（见类 KDoc 的说明）。
     */
    private fun renderWordTimed(
        g: Graphics2D,
        cache: RenderCache,
        layout: TextLayout,
        baselineX: Float,
        baselineY: Float,
        sung: java.awt.Color,
        unsung: java.awt.Color,
        wordBoundaries: FloatArray,
        wordLengths: IntArray,
        featherFontSize: Float?,
        /**
         * 羽化宽度的**倍率**。`1f` = 逐字档（硬边，逐字吸附）；
         * [SMOOTH_FEATHER_SCALE] = 平滑档（柔边，"一条线扫过去"）。
         *
         * ⚠️ 单独开一个参数而不是"传个更大的字号" —— 那个参数（`featherFontSize`）
         * 的语义是"**本行实际使用的字号**"，拿它当倍率用会让名字说谎
         * （本项目纪律：名字说谎比没名字更糟）。
         */
        featherScale: Float = 1f,
    ) {
        // 找到分界所在的字：第一个「没唱满」的字。
        var leadingChars = 0
        var boundaryChar = -1
        var boundaryRatio = 0f
        var boundaryLen = 1
        for (i in wordBoundaries.indices) {
            val ratio = wordBoundaries[i]
            if (ratio < 1f) {
                boundaryChar = leadingChars
                boundaryRatio = ratio.coerceIn(0f, 1f)
                boundaryLen = wordLengths.getOrElse(i) { 1 }.coerceAtLeast(1)
                break
            }
            leadingChars += wordLengths.getOrElse(i) { 1 }
        }

        val oldFont = g.font
        val oldColor = g.color
        val oldPaint = g.paint
        try {
            // ⚠️ 这一行**只为对称**：`TextLayout.draw` 用的是**布局自己的**字体属性
            // （布局由 `TextLayout(String, Font, FRC)` 构造，字体已进属性表），
            // 与 `g.font` 无关 —— 译文行用派生小字号走同一条路径时，
            // 这里设的是主字号也不会把译文画大。已由
            // `TranslationKaraokeProbe` ⑨ 组实测钉死。
            // 保留它是为了"不改 g 的字体以外状态"这条契约读起来一致（用完即还原）。
            g.font = cache.currentFont()
            // ① 整行打底（未唱色）。
            g.color = unsung
            layout.draw(g, baselineX, baselineY)

            // 全部唱满 → 整行已唱色。
            if (boundaryChar < 0) {
                g.color = sung
                layout.draw(g, baselineX, baselineY)
                return
            }
            if (boundaryRatio <= 0f) return  // 一点没唱：只留未唱色

            // ② 取分界所在字段的两个 caret（**相对基线原点** —— `caretOf` 返回的已是相对坐标）。
            val startX = caretOf(layout, boundaryChar)
            val endX = caretOf(layout, boundaryChar + boundaryLen)
            // 分界点位置由 `KaraokeFeather` 的纯函数给出。⚠️ 它返回的是**相对行首**的量，
            // 不是绝对 x —— 到绝对坐标的换算由 `karaokeFeatherGeometry` **内部**完成，
            // 调用方拿不到"忘了加 `baselineX`"这个机会（见该文件顶部的坐标系说明）。
            val frontRel = karaokeSweepFrontRel(startX, endX, boundaryRatio)

            // ③ 羽化绘制：clip 到「分界 + 半羽化宽」，再用渐变过渡边界。
            //
            // ⚠️ 羽化宽度的「字号」必须取自**本行的**字号，不能一律取
            // `cache.currentFont()` —— 后者是**主歌词**的字号。译文行用派生小字号
            // （主字号 × 0.64）走同一条逐字路径，取错会让羽化带按主字号算
            // （22pt 时 0.45×22 = 9.9px，而译文正确的值是 0.45×15.4 = 6.9px），
            // 表现为译文那行小字上糊开一片比原文宽四成的过渡带。
            // 缺省（`featherFontSize == null`）时退回 `cache.currentFont()` ——
            // 那是**改动前**的行为，主歌词行因此逐像素不变。
            val featherRefSize = featherFontSize?.takeIf { it > 0f } ?: cache.currentFont().size2D
            val feather = karaokeFeatherPx(featherRefSize, endX - startX, featherScale)

            // ④ 几何：渐变两端与 clip 右端**一次算齐**，且**全部是绝对 x**。
            //
            // ⚠️ 这一步曾经写错，值得留档：原实现把 `boundaryX`（**相对**基线原点）
            //    直接当成 `GradientPaint` 的端点（**绝对**用户空间）用 —— 过渡带因此
            //    整体左移了 `baselineX`；而 clip 那边因为 `Rectangle2D.Float` 的第 3 个
            //    参数是**宽**，恰好把相对量用对了。**同一段代码里并存两套坐标系**，
            //    与 `:419-423` 记过的坑同族（那里作者记得加 translate，这里忘了）。
            //    现在两者都出自同一个 `geom`，混用**结构上不可达**。
            val geom = karaokeFeatherGeometry(baselineX, layout.advance, frontRel, feather)

            val oldClip = g.clip
            try {
                g.clip(Rectangle2D.Float(baselineX, baselineY - layout.ascent,
                    geom.clipRight - baselineX, layout.ascent + layout.descent))
                // 「分界左侧=已唱色 → 右侧=未唱色」的横向渐变覆盖已唱区。
                g.paint = GradientPaint(
                    geom.gradientLeft, 0f, sung,
                    geom.gradientRight, 0f, unsung,
                )
                layout.draw(g, baselineX, baselineY)
            } finally {
                g.clip = oldClip
            }
        } finally {
            g.font = oldFont
            g.color = oldColor
            g.paint = oldPaint
        }
    }

    /**
     * 取文本偏移 [offset] 处的**相对基线原点**的 caret x 坐标。
     *
     * `TextHitInfo.leading(n)` 在 `n == characterCount` 时**不合法**
     * （索引上界是 `characterCount - 1`），末位要用 `trailing`。
     * 这是 0.8.9 的 `caret` 辅助函数踩过的同一个坑
     * （`up089-src/…/ui/LyricPainter.kt:74-78`），此处独立实现同样的分支。
     */
    private fun caretOf(layout: TextLayout, offset: Int): Float = when {
        offset <= 0 -> layout.getCaretInfo(TextHitInfo.leading(0))[0]
        offset >= layout.characterCount ->
            layout.getCaretInfo(TextHitInfo.trailing(layout.characterCount - 1))[0]
        else -> layout.getCaretInfo(TextHitInfo.leading(offset))[0]
    }

    private companion object {
        /** 簇缓存上限。论证见 [clustersOf]。 */
        const val CLUSTER_CACHE_LIMIT = 64

        // ⚠️ `FEATHER_FONT_RATIO` / `FEATHER_WIDTH_RATIO` / `MIN_FEATHER` 三个常量
        // 已移到 `KaraokeFeather.kt`（顶层公开）—— 羽化几何连同它的数值一起搬了家，
        // 免得"几何在 A 文件、数值在 B 文件"再造一处两处实现。
        // 断言见 `KaraokeFeatherProbe` §1。

        /**
         * **平滑档**的羽化倍率（`main_lyric.karaoke.mode = SMOOTH`）。
         *
         * 取 2.5：逐字档的羽化是"逐字吸附 + 窄过渡"（锐）；平滑档要的是
         * "**已读颜色在字间连续移动**"（柔）。**时序两者同源**（都用逐字时间轴），
         * 故**观感差别就落在这一条羽化上** —— 这正是用户要的"另一种形式"。
         *
         * 上限受 `FEATHER_FONT_RATIO`（0.45 × 字号）约束，故 2.5 倍后
         * 22px 字号下约 24.75px ≈ **1 个字宽**的过渡带 —— 够"柔"，
         * 又不至于糊成一片看不出唱到哪。
         */
        const val SMOOTH_FEATHER_SCALE = 2.5f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 描边笔型与抗锯齿：**顶层公开**（不是 `KaraokeTextRenderer` 的成员）
// ─────────────────────────────────────────────────────────────────────────────
//
// ⚠️ 为什么必须是顶层 `public` 而不是 `internal`：`KaraokeTextRenderer` 是
//    `internal class`，而探针（`src/probe/kotlin`）是**独立的编译单元**，
//    `internal` 对它不可见（`KaraokeSweepProbe` 的 KDoc 里记过这条）。
//    这与 `KaraokeFeather.kt` 把 `karaokeFeatherPx` 等做成顶层公开是**同一个理由**，
//    不是"为了好看"。
//
// ⚠️ 一致性靠"**调用点唯一**"保证（`grep` 核过：两者在 main 里各只有一处调用），
//    不靠探针 —— 与 `KaraokeFeatherProbe` 的取舍相同。

/**
 * 描边遍使用的笔型。**这是"字形边缘毛刺"的根因所在**（2026-09-22）。
 *
 * ## 缺陷现象（用户原话：「那个红圈圈中的字体有毛刺」）
 *
 * 日文歌词 `世界でひとつ…` 的第二个假名 `ひ`，右上笔画末端伸出一根
 * **宽 1–2px、长约 14px 的竖直针**。它不是"抗锯齿没开"（实测中间调占比 27.2%），
 * 也不是"锯齿"（锯齿是阶梯状，这是**多出来的一块几何**）。
 *
 * ## 根因：`BasicStroke(w)` 的默认 `JOIN_MITER` + `miterLimit = 10`
 *
 * `JOIN_MITER` 在**锐角**处把外角延伸成一个尖，延伸长度受 `miterLimit` 约束，
 * 上限是 `w/2 × miterLimit = 5w`（本处 w = 真机的 4.0px ⇒ 上限 **20px**）。
 * `ひ` 的轮廓里恰好有一个足够锐的角，于是：
 *
 * ```
 * 纯几何外伸（`createStrokedShape` 边界到字形边界的最大距离，w = 4.0px）
 *   字号      JOIN_MITER(现状)   JOIN_ROUND(本函数)
 *   20–40       2.87–2.88          2.00–2.03
 *   42          10.325  ← 真机字号  2.025
 *   44           9.782             2.025
 *   63           2.882             2.007
 * ```
 *
 * ⚠️ 注意它在字号 **42**（= `main_lyric.font.size`，用户真机配置）上恰好发作，
 *    而 20–40 与 63 都正常 —— 这正是"偶尔才有毛刺、改字号就消失"的原因，
 *    也是为什么这条缺陷**很难靠眼睛复现**。同一字号下 `CAP_*` 完全不影响外伸
 *    （`CAP_BUTT`/`CAP_SQUARE` 的 MITER 外伸同为 10.325）：**载荷全在 JOIN 上**。
 *
 * ## 为什么 `JOIN_ROUND` 是**定理级**的正确解，而不是"调参"
 *
 * 圆角连接在几何上**不可能**超出笔宽的一半：`createStrokedShape` 的边界点
 * 到原轮廓的距离恒 ≤ `w/2`。上表 ROUND 那一列在**所有**字号下都是 2.00–2.03
 * （= w/2 = 2.0 加展平误差），不是拟合出来的阈值。
 * `JOIN_BEVEL` 同样满足该上界，但圆角在笔画拐角处的观感更接近原字形，
 * 故取 ROUND。
 *
 * ⚠️ `CAP_ROUND` 是**陪衬**：`TextLayout.getOutline` 给出的是**闭合**轮廓，
 *    端点帽型（cap）对它几乎不起作用，真正起作用的是 `JOIN_ROUND`。
 *    写成 `CAP_ROUND` 只是与"圆头圆角"这一组语义保持自洽。
 *
 * 断言见 `TextAntialiasProbe` §B/§D/§E（含把本函数换回 `BasicStroke(w)` 的变异测试）。
 */
fun karaokeOutlineStroke(widthPx: Float): BasicStroke =
    BasicStroke(widthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

/**
 * `TextOutline.weightLinkPercent` 的缺省值 —— **与 `main_lyric.outline.weight_link_percent`
 * 的注册表默认值（`50`）必须相等**。
 *
 * ## 为什么需要这个常量（而不是在 `TextOutline` 里直接写 `50`
 *
 * 它是**缺省值**，只在调用方**没有**显式传比例时被读 —— 当前唯一的这种调用方是
 * **主歌词**（它走的是调用方预算好的宽度，`followWeight = false`，故本值用不上）。
 *
 * 但"用不上"不等于"可以随便写"：一旦将来有人给主歌词打开 `followWeight` 却忘了传比例，
 * 描边就会按**这个**数走 —— 若它与注册表默认值不一致，就会得到
 * "设置页显示 50、实际按 80 算"这种**两个来源不一致**的静默偏差（BUG-024 同族）。
 *
 * 故把它写成**公开常量**，让 `TextAntialiasProbe` §F 直接拿注册表的默认值比它
 * —— 一致性由断言守，而不是靠"下次谁记得同步"。
 */
const val DEFAULT_OUTLINE_WEIGHT_LINK_PERCENT = 50

/**
 * **描边宽度跟随字体粗细**的换算（`translation.outline.follow_weight` 的 `f`）。
 *
 * ## 公式（与主歌词那条**逐字相同**，故两行描边的观感不会割裂）
 *
 * ```
 * 实际笔宽(px) = 基准笔宽(px) × (1 + (字重倍率 − 1) × 联动比例)
 *
 *   字重倍率 = main_lyric.font.weight_scale / 100          （默认 1.0 = 常规）
 *   联动比例 = main_lyric.outline.weight_link_percent / 100 （默认 0.5 = 半跟随）
 * ```
 *
 * | 字重倍率 | 联动比例 | 结果 |
 * | --- | --- | --- |
 * | 1.0（默认） | 任意 | **×1.00** —— 出厂零变化（`f(1) ≡ 1`） |
 * | 2.0（最粗） | 50%（默认） | ×1.50 |
 * | 2.0 | 0% | ×1.00（完全不跟随） |
 * | 2.0 | 100% | ×2.00（等比跟随） |
 * | 0.5（最细） | 50% | ×0.75 |
 *
 * ## 为什么取"半跟随"50% 为中值
 *
 * 论证在 `main_lyric.outline.weight_link_percent` 的 KDoc 里（描边 1:1 等比跟随
 * 会把字腔填死、0% 又让描边相对笔画失去存在感）。本函数**不重复**那段论证 ——
 * 两个键共用同一个比例，故它们共用同一条理由。
 *
 * ## 为什么结果量化到 **0.1 px**
 *
 * 主歌词那条路径是在"十分之一像素"的整数域里算的
 * （`IslandWindow` 的 `mainOutlineWidthTenthPx` 先乘后 `roundToInt`）。
 * 这里若按浮点原样返回，两条路径会在同一个设置下算出**差一点点**的笔宽
 * （`1.25` vs `1.3`），表现为"主副描边的粗细看着不一样"。
 * 故这里同样量化到 0.1 px —— **两条路径的算式与精度都对齐**。
 *
 * ## 早退（`followWeight = false`）是**逐位不变**的保证
 *
 * 关掉开关时本函数**第一行就返回 `baseWidthPx`**（不做任何浮点运算），
 * 故主歌词那条既有路径（`followWeight` 恒为默认 `false`）与改动前**逐位相同**。
 *
 * @param baseWidthPx 基准笔宽（px，来自 `*_tenth_px / 10f`）
 * @param weightScale 字重倍率（`1f` = 常规）。来自 [karaokeFontWeightScale]
 * @param linkPercent 联动比例（%）。越界夹到 `0..100`
 * @param followWeight 是否跟随。`false` ⇒ 原样返回 [baseWidthPx]
 * @return 实际笔宽（px，量化到 0.1）。**恒 ≥ 0**（负值/非有限输入原样返回）
 */
fun karaokeOutlineWidthPx(
    baseWidthPx: Float,
    weightScale: Float,
    linkPercent: Int,
    followWeight: Boolean,
): Float {
    // ★ 早退：改动前的路径走的就是这一行（故主歌词逐位不变）。
    if (!followWeight) return baseWidthPx
    // 非法基准宽度原样返回 —— 由调用方的 `> 0f` 判定去决定"画不画描边"，
    // 这里**不要**擅自改成 0 或某个默认值（那会把"没开描边"变成"画一条细描边"）。
    if (!baseWidthPx.isFinite() || baseWidthPx <= 0f) return baseWidthPx
    // 字重倍率非法（缺属性表时 `karaokeFontWeightScale` 已回退 1f，这里是纵深防御）。
    if (!weightScale.isFinite() || weightScale <= 0f) return baseWidthPx
    val ratio = linkPercent.coerceIn(0, 100) / 100f
    val linked = baseWidthPx * (1f + (weightScale - 1f) * ratio)
    if (!linked.isFinite()) return baseWidthPx
    // 量化到 0.1 px —— 与主歌词那条路径同精度（理由见上）。
    return (linked.coerceAtLeast(0f) * 10f).roundToInt() / 10f
}

/**
 * 从**字体**读回它的**字重倍率**（`1f` = 常规）。
 *
 * ## 为什么"跟随字体粗细"要读字体，而不是读某个参数
 *
 * `LetterSpacing.fontWithLetterSpacing` 把 `main_lyric.font.weight_scale`
 * 写进了字体的 `TextAttribute.WEIGHT`（**全工程字体的唯一构造入口**）——
 * 于是"这一行到底用了多粗的字"这件事，**字体自己知道**。
 *
 * 而副歌词的 `TextLayout` 由调用方派生好后传进来，`TextLayout` **不暴露**
 * 自己用的字体；绘制这一层能拿到的只有 `cache.currentFont()`。
 *
 * ## ⚠️ 为什么用 `cache.currentFont()`（主字体）去代表副歌词那一行是对的
 *
 * 副歌词字号是 `fontSize × translationScale`，而**字重倍率与字号无关** ——
 * 两处用的是**同一个** `local.fontWeightScale`
 * （`IslandWindow` 的 `fontWithLetterSpacing(…, local.fontWeightScale)`，主副各一处）。
 * 故从主字体读出的倍率**就是**副歌词那一行的倍率。
 *
 * ## 回退
 *
 * 属性表里没有 `WEIGHT`（未派生过字重的字体）⇒ 返回 `1f` —— 即"不跟随"，
 * 与改动前的观感一致。非有限 / 非正值同样回退 `1f`。
 *
 * @param font 该行实际使用的字体（生产路径传 `RenderCache.currentFont()`）
 */
fun karaokeFontWeightScale(font: java.awt.Font): Float {
    // ★★ 2026-09-29：**改从 `Font.isBold()` 判断**（不再读 `TextAttribute.WEIGHT` 属性）。
    //
    // ## 为什么必须改（纪律 10：改了 A，A 的输入不能被 B 掐断）
    //
    // 原实现从字体的 `TextAttribute.WEIGHT` 属性**反推**倍率 —— 而
    // `render/LetterSpacing.fontWithLetterSpacing` 从今天起**不再写 `WEIGHT`**了
    // （实测它只有两态，写中间值等于没写；加粗改走 `Font.BOLD`）。
    // ⇒ 若不改这里，本函数会**永远返回 `1f`** ⇒ **"描边宽度跟随字重"静默失效**。
    //
    // ## 返回值语义保持不变（**消费点不用改**）
    //
    // `2f` = 加粗（对应旧的"倍率 2.0"）· `1f` = 常规（旧的"倍率 1.0"）。
    // ★ 这是**刻意的最小改动**：调用方 `IslandWindow` 的折算公式原样成立。
    return if (font.isBold()) 2f else 1f
}

/**
 * 把本类绘制所依赖的抗锯齿提示设到 [g] 上。**`render` 自己调用**，
 * 使描边遍（`KEY_ANTIALIASING`）与填充遍（`KEY_TEXT_ANTIALIASING`）的 AA
 * 不再依赖调用方记得设。
 *
 * ## 为什么需要它（这不是"多此一举地再设一遍"）
 *
 * Java2D 的**形状 AA 与文本 AA 是两个互不共享的键**：
 * - 描边遍走 `g.draw(Shape)` → 看 `KEY_ANTIALIASING`
 * - 填充遍走 `TextLayout.draw` → 看 `KEY_TEXT_ANTIALIASING`
 *
 * 本类此前**一个都不设**。两个现存调用方恰好都设了（`IslandWindow.kt:5824-5827`、
 * `IslandPreviewPane.kt:139-140`），所以真机观感是对的 —— 但这是**巧合而非契约**，
 * 且缺了它没有任何断言会红。实测（生产顺序、MiSans 42px、描边 4px）：
 * AA 开中间调占 **27.2%**，AA 关 **0.0%** —— 一旦漏设，字形边缘立刻变硬。
 *
 * ## 为什么不设 `VALUE_TEXT_ANTIALIAS_GASP`
 *
 * `GASP` 会**按字号档位关闭** AA（低于字体的 `maxPPEM` 档就继承系统默认渲染）。
 * 真机字号 42 远在该档之上，故当前不受影响；但把它设成 `GASP` 等于把一个
 * "字号相关的隐藏开关"引进来 —— 用户一调小字号就会看到毛刺回来。
 * 保持 `ON` 是无条件抗锯齿，与"歌词必须始终平滑"这个产品要求一致。
 *
 * ## `KEY_STROKE_CONTROL` 为什么显式写 `NORMALIZE`
 *
 * `NORMALIZE` 本来就是默认值（此行对真机是 no-op），显式写出来是为了**挡住**
 * 一条曾被写进分诊文档的**错误修法**：把描边吸附改为 `PURE`。
 * 实测 `PURE` 在小字号下**更糟**（硬跳 205/203 像素 vs `NORMALIZE` 的 74/91），
 * 因为 `NORMALIZE` 会把笔画吸附到像素网格，使 ≥1px 的笔画不至于糊成半透明。
 * 断言见 `TextAntialiasProbe` §A3。
 */
fun karaokeApplyAntialiasHints(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
}
