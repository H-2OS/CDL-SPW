// SPDX-License-Identifier: AGPL-3.0-only
//
// ⑰「封面多色流光」**第 2 步 · F1：多停靠点渐变** —— 唯一构造点（2026-10-03）
//
// ══════════════════════════════════════════════════════════════════════════════
// 本文件在整条 ⑰ 里的位置
// ══════════════════════════════════════════════════════════════════════════════
//
// ⑰ 分两步（`未完成工作总账.md` §3.4 / §3.15′）：
//
//   · **第 1 步（已完成 2026-10-02）**：多色**原料** —— `CoverColorExtractor.palette(image, n)`。
//   · **第 2 步（本文件 = 其中 F1）**：把多色**变成会流动的渐变**。四件 = **F1 多停靠点渐变** ·
//     F2 位移动画 · F3 新设置键 · F4 缓存。
//
// ★★ **本文件只做 F1**，且**有意不接消费点** —— 与第 1 步（`palette` 当时也未被消费）
//    **同一个取舍**：先把"地基"做成**可断言**的纯函数，再接消费点（F2/F3）。
//    ⇒ 判据：本文件**不碰任何 `Graphics2D` / 窗口状态**，只回答"停靠点与颜色是什么"。
//
// ══════════════════════════════════════════════════════════════════════════════
// ★★ 为什么必须收敛成"唯一构造点"（而不是各消费点自己拼）
// ══════════════════════════════════════════════════════════════════════════════
//
// `LinearGradientPaint` 的**关键帧必须严格递增**（**相等也不行** ⇒ 构造期抛
// `IllegalArgumentException`）。本项目已把它记为**已知陷阱**
// （`MaterialLayers.highlightPaint` 的 KDoc 逐字："关键帧必须严格递增（相等也不行）"）。
//
// 而"N 个色 ⇒ N 个停靠点"这件事**有两处天然会踩它**：
//
//   ① **`n < 2`** —— 一个色**构不出渐变**。★ 若调用方"补一个假色"或"把同一个色复制一份"，
//      两个关键帧就**相等** ⇒ 直接踩陷阱。⇒ 本文件的做法：**返回 `null`**（降级为单色），
//      与 `highlightPaint` 的"跨度退化 ⇒ 返回 `null`，而不是交给 Java2D 抛异常"**同一条纪律**。
//
//   ② **浮点塌陷** —— `n` 极大时 `i / (n - 1)` 的相邻值可能在 `Float` 下**相等**。
//      ⇒ 本文件**构造后自检**严格递增，不满足就返回 `null`（宁可退回单色，也不抛）。
//
// ⇒ 两个消费点（`MaterialLayers` 的 `LinearGradientPaint` / `SpectralPainter` 的
//    `SpectrumTipFadeBrushCache`）**都调本文件**，不各拼一份 —— 纪律 10「合并成唯一出口」。
//
// ══════════════════════════════════════════════════════════════════════════════

package dev.cdl.render

import java.awt.Color
import java.awt.LinearGradientPaint

/**
 * `FLOW` 档从封面取几个色（= `CoverColorExtractor.palette(image, n)` 的 `n`）。
 *
 * ★ **4** 的理由：`palette` 的原料是 `HUE_BUCKETS = 12` 个色相簇
 * ⇒ 取 4 是"**看得出多色**"与"**不噪**"的折中（3 色偏单调、6+ 色在 76 px 高的岛体上分不清）。
 * ★ 它是**常量而不是设置键**：速度与色数都属于"能调当然好、但先别加键"的那类
 * （用户 2026-09-30 纪律：「**不引入新的控制项**」）。
 */
const val FLOW_PALETTE_COLORS = 4

/**
 * ⑰ · F1：**由色数推出停靠点** —— 严格递增，或 `null`（降级）。
 *
 * ## 分布口径：**等距**（`i / (n - 1)`）
 *
 * 台账 §3.15′ F1 的原话是「**N 色 ⇒ N 个帧**」⇒ 等距是**它要的字面语义**。
 * ★ **不用簇权重做非均匀分布**：权重来自 `palette()` 的 `count`，而
 *   `palette()` 的契约**只保证"按 count 降序"**（不保证权重之和有意义，
 *   也不保证调用方拿得到权重）⇒ 用权重会把"渐变外观"绑上一个**没有契约的量**。
 *
 * ## ★ 后置自检（不是"理论上不会发生"）
 *
 * `Float` 的精度有限：`n` 大到一定程度时 `i/(n-1)` 的相邻值会**塌成相等**
 * ⇒ 那**不是**严格递增 ⇒ `LinearGradientPaint` 会抛。
 * ⇒ 本函数**自己先查一遍**，不满足就返回 `null`（调用方退回单色）。
 * ★ 这与 `MaterialLayers.highlightPaint` 的"跨度退化 ⇒ 返回 `null`"同一条纪律：
 *   **把 Java2D 会抛的输入，在进入 Java2D 之前挡掉**。
 *
 * @param n 色数。**必须是 `palette(...)` 实际返回的 `size`**（它可能**短于**请求的 n）。
 * @return 长度 `n`、**严格递增**、首 `0f` 末 `1f`；`n < 2` ⇒ `null`。
 */
fun flowGradientStops(n: Int): FloatArray? {
    if (n < 2) return null
    val out = FloatArray(n)
    val denom = (n - 1).toFloat()
    for (i in 0 until n) out[i] = i.toFloat() / denom
    // ★ 后置自检：任何相邻两个必须**严格**递增（相等即不合法）。
    for (i in 1 until n) if (!(out[i] > out[i - 1])) return null
    return out
}

/**
 * ⑰ · F1：把 `palette()` 的 ARGB 列表转成 `LinearGradientPaint` 要的 `Array<Color>`。
 *
 * ## 为什么 alpha 由**参数**给，而不是用 `palette` 里的 alpha
 *
 * `CoverColorExtractor.palette` 的契约逐字：「打包 ARGB 列表（`0xFFRRGGBB`，
 * **alpha 恒不透明**）」⇒ 它**故意**不带透明度（那是"原料"，不是"成品"）。
 * ⇒ 透明度是**图层语义**（由 `material.*` 的键决定），必须在**消费点**给。
 *
 * @param paletteArgb `palette()` 的返回（`0xFFRRGGBB`）。
 * @param alpha 目标不透明度（`0..255`，越界会被夹住）。
 * @return 与 `paletteArgb` **同长**；`size < 2` ⇒ `null`（构不出渐变，见 [flowGradientStops]）。
 */
fun flowGradientColors(paletteArgb: List<Int>, alpha: Int): Array<Color>? {
    if (paletteArgb.size < 2) return null
    val a = alpha.coerceIn(0, 255)
    return Array(paletteArgb.size) { i ->
        val argb = paletteArgb[i]
        Color((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, a)
    }
}

/**
 * ★★ ⑰ · **F2**：流光走完**一个完整周期**的时长（毫秒）。越小越快。
 *
 * ★ **6 秒**的理由：它要"看得出在动"但**不抢戏** —— 岛体是歌词的容器，
 * 背景流光比歌词更抓眼就本末倒置了。3 秒偏急、10 秒像静止。
 * ★ 它是**常量而不是设置键**（与 [FLOW_PALETTE_COLORS] 同一条理由：
 * 用户 2026-09-30 纪律「**不引入新的控制项**」）。
 */
const val FLOW_PERIOD_MS = 6000L

/**
 * ★★ ⑰ · **F2**：循环构造里**铺几个周期**。
 *
 * ## 为什么必须是 3（这是"无缝"的全部依据）
 *
 * 可见区沿渐变轴投影成一个**长度 `l`** 的区间、**居中**在形状中心。
 * 若轴只有 `l` 长（一个周期），按相位平移后**可见区会跑出轴外** ⇒ Java2D
 * 用端点色**填充**轴外区域 ⇒ **接缝处跳色**（一眼假）。
 *
 * 铺 **3 个周期**（轴长 `3l`，居中）后，平移量 `shift ∈ [0, l)`：
 *
 * ```
 * 轴覆盖   [cx − 1.5l + shift, cx + 1.5l + shift]
 * 可见区   [cx − 0.5l,        cx + 0.5l       ]
 * shift = 0  ⇒ 轴 [−1.5l, +1.5l] ⊇ 可见 ✓
 * shift = l  ⇒ 轴 [−0.5l, +2.5l] ⊇ 可见 ✓（左端刚好贴住）
 * ```
 *
 * ⇒ **任意相位下可见区都落在轴内**，且落在**中段** ⇒ 平移**无缝**。
 */
const val FLOW_CYCLES = 3

/**
 * ★★ ⑰ · **F2**：**循环停靠点** —— `i / (n × cycles)`，`i ∈ 0..n×cycles`。
 *
 * 与 [flowGradientStops] 的区别：本函数把 `0..1` **铺满 `cycles` 个周期**
 * （每个周期 `n` 段），颜色按 `palette[i % n]` 取 ⇒ **末色 == 首色** ⇒ 可无缝循环。
 *
 * ★ **严格递增**仍然成立（`i/(n·cycles)` 单调），且同样**构造后自检**。
 *
 * @return 长度 `n × cycles + 1`；`n < 2` 或自检不过 ⇒ `null`。
 */
fun flowCyclicStops(n: Int, cycles: Int = FLOW_CYCLES): FloatArray? {
    if (n < 2 || cycles < 1) return null
    val total = n * cycles
    val out = FloatArray(total + 1)
    val denom = total.toFloat()
    for (i in 0..total) out[i] = i.toFloat() / denom
    for (i in 1..total) if (!(out[i] > out[i - 1])) return null
    return out
}

/**
 * ★★ ⑰ · **F2**：**循环颜色** —— `palette[i % n]`，`i ∈ 0..n×cycles`。
 *
 * ⇒ 长度与 [flowCyclicStops] **恒等**（`n × cycles + 1`），且
 * **`colors[0] == colors[last]`**（同色）—— 这正是"轴两端图案相同"、
 * 因而**平移一个周期后看不出接缝**的原因。
 *
 * ⚠️ **注意**：Java2D 要的是"**关键帧**严格递增"（那是 [flowCyclicStops] 的事），
 * **不要求颜色互不相同** ⇒ 首尾同色是**合法且必要**的。
 */
fun flowCyclicColors(
    paletteArgb: List<Int>,
    alpha: Int,
    cycles: Int = FLOW_CYCLES,
): Array<Color>? {
    val n = paletteArgb.size
    if (n < 2 || cycles < 1) return null
    val a = alpha.coerceIn(0, 255)
    return Array(n * cycles + 1) { i ->
        val argb = paletteArgb[i % n]
        Color((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, a)
    }
}

/**
 * ⑰ · F1：**多停靠点线性渐变的唯一构造点** —— 几何与
 * `MaterialLayers.gradientPaint` 的既有算式**逐字同源**（角度 ⇒ 中心 + 半长）。
 *
 * ## 与既有 2 停靠点路径的关系
 *
 * `MaterialLayers.gradientPaint` 走的是「**起色 → 止色**」两个停靠点。
 * 本函数走的是「**N 个封面取色**」N 个停靠点 —— 它是 **F1 的新路径**，
 * **不替换**既有那条（既有那条服务的是 `material.fill.gradient_*` 两个键，
 * 与"封面多色"是**两回事**）。⇒ 两路径**并存**，由调用方按"有没有多色"选。
 *
 * ## 返回值语义（三种 `null`，都对应"退回单色"）
 *
 * | 条件 | 为什么 `null` |
 * |---|---|
 * | `paletteArgb.size < 2` | 一个色构不出渐变（见 [flowGradientStops] ①） |
 * | 跨度退化（`w`/`h` 非正、或投影长度 `l <= 0`） | `from == to` ⇒ 关键帧无从谈起（同 `highlightPaint`） |
 * | 停靠点无法严格递增 | 浮点塌陷（见 [flowGradientStops] ②） |
 *
 * @param angleDeg 渐变方向（度）。与既有路径同口径：`0°` = 向右，`90°` = 向下。
 * @param paletteArgb `CoverColorExtractor.palette()` 的返回（**实际**拿到的色，可能短于请求数）。
 * @param alpha 渐变整体的不透明度（`0..255`）。
 */
fun flowGradientPaint(
    x: Double, y: Double, w: Double, h: Double,
    angleDeg: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    phase: Float = 0f,
): LinearGradientPaint? {
    // ★★ 2026-10-03（**F2**）：改用**循环构造**（铺 `FLOW_CYCLES` 个周期）。
    //
    // ⚠️ 静态（`phase = 0`）与流动（`phase > 0`）**必须是同一条构造** ——
    //   若"静止走 N 停靠点、一流动换成循环构造"，那么**刚开始动的那一帧会跳一下**
    //   （两种构造在可见区里的色彩分布不同）。⇒ 一律循环构造。
    val stops = flowCyclicStops(paletteArgb.size) ?: return null
    val colors = flowCyclicColors(paletteArgb, alpha) ?: return null
    // ⚠️ 两者**必须同长** —— 否则 `LinearGradientPaint` 抛 `IllegalArgumentException`。
    //    ★ 这条不是"应该成立"，而是**本函数的出口契约**：两行都从 `paletteArgb.size`
    //      出发故恒等；写成显式检查是为了**将来有人改其中一处时立刻红**（而非静默抛）。
    if (stops.size != colors.size) return null

    if (!w.isFinite() || !h.isFinite()) return null
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = Math.cos(rad)
    val dy = Math.sin(rad)
    val l = Math.abs(w * dx) + Math.abs(h * dy)
    if (!(l > 0.0)) return null
    val cx = x + w / 2.0
    val cy = y + h / 2.0
    // ★ 轴长 = `FLOW_CYCLES × l`（居中）⇒ 任意 `shift ∈ [0, l)` 下可见区恒在轴内
    //   （推导见 [FLOW_CYCLES] 的 KDoc）。
    val half = FLOW_CYCLES * l / 2.0
    // ★ **相位平移**：把轴心沿方向挪 `phase × l`。
    //   ★ 先取模 ⇒ 任意输入（含负、含 >1）都落 `[0, 1)` ⇒ **无缝**且不越界。
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val shift = ph.toDouble() * l
    val ox = cx + shift * dx
    val oy = cy + shift * dy

    return LinearGradientPaint(
        (ox - half * dx).toFloat(), (oy - half * dy).toFloat(),
        (ox + half * dx).toFloat(), (oy + half * dy).toFloat(),
        stops,
        colors,
    )
}

// ════════════════════════════════════════════════════════════════════════════
// ★★★ 2026-10-15：本段的**适用范围收窄为「频谱实心档」**
//   （词岛背景的碎色流光已改用 `render/CamoTexture.kt` 的**二维贴图**）
// ════════════════════════════════════════════════════════════════════════════
//
// ## 用户报障（逐字）与本次改动的边界
//
// > 「**碎色流光完全没做到应有效果，还是竖条纹状**，并且色彩衔接还没有多色流光
// >   衔接的好。」
//
// ★ 那句话说的是 **`material.fill.gradient_style = CAMO`（词岛背景）** ——
//   而本段这三个函数是**一维**的（`LinearGradientPaint`）：
//   颜色只沿一个轴变化、**垂直于轴的方向上恒定** ⇒ 在“横向长条岛体”上
//   **必然**是竖条纹 ✗ （无论停靠点切几段、块宽怎么抖动）
//   ⇒ 词岛背景**必须**换二维实现（已换：`CamoTexture` 细胞噪声贴图）✓
//
// ## ★ 那为什么本段**保留**？
//
// 因为它的另一个消费点是 **`spectrum.color_mode = cover_camo` 的实心档**
// （`IslandWindow.spectrumFlowPaintFor` / `IslandPreviewPane`）——
// 那里画的是**频谱柱所在的横带**，媒介本身是**一维**的
// （色块沿横向排布），一维渐变**正是合适的工具** ✓
//
// ⇒ ★ **同一个“迷彩”语义，两种媒介、两套实现** —— 这不是重复代码，
//   而是“**数据结构要与媒介匹配**”。判据：
//   **「一维的笔刷服务一维的媒介（频谱带），二维的贴图服务二维的媒介（岛体背景面）。」**
//
// ## ★ 留痕（把一维笔刷用在二维场景上时，付出的两条代价）
//
// ① 关键帧必须**严格递增**（相等也不行 ⇒ 构造期抛）⇒ 每块要拆成“平台起点 +
//    平台终点”两个停靠点，循环拼接时还要去重；
// ② 循环铺 `FLOW_CYCLES` 个周期才能保证“任意相位下可见区都在轴内”。
// ⇒ 这些复杂度**在二维贴图路里完全不存在**（`TexturePaint` 没有这两条约束）。
// ══════════════════════════════════════════════════════════════════════════
// ★★★ 2026-10-14：**迷彩碎色块**的实心档笔刷（`spectrum.color_mode = cover_camo`）
// ══════════════════════════════════════════════════════════════════════════
//
// ## 为什么实心档需要**单独**一支笔刷
//
// 逐柱取色（`spectrumCamoColorAt`）只服务"柱状 / 折线"那两条路；而**实心档**
// （`fade_top = false` / 插槽档）走 `g.paint` 一支渐变 —— 它**不读逐柱回调**
// （见 `IslandWindow.spectrumPaintOverride` 的 KDoc）。
// ⇒ ★ 迷彩档在实心路径上必须**另有表达**：把"离散色块"编进**多停靠点渐变**里。
//   ★ 做法 = 每个色块占一段**平台**（两次几乎重合的停靠点 = 该色铺满块宽），
//     块与块之间用一小段**斜坡**过渡（= 柔边，与逐柱路的 `CAMO_EDGE_SOFT_FRACTION` 同义）✓
//
// ## ★★ 判据（探针逐条钉）
//
// | # | 不变量 | 为什么重要 |
// |---|---|---|
// | ① | **严格递增** —— `LinearGradientPaint` 的硬要求（相等即抛） | 与 [flowGradientStops] 同一条纪律 |
// | ② | **块内是平台** —— 每个块的中心处颜色 = 该块色（两停靠点同色） | 这才叫"色块"，否则退回渐变 |
// | ③ | **块数 = `CAMO BLOCK COUNT`**（与逐柱路同源） | 两条路观感一致 |
// | ④ | `n < 2` / 跨度退化 ⇒ `null`（调用方退回单色） | 与 [flowGradientPaint] 同一条 |

/**
 * 迷彩实心档的**停靠点与颜色**（**唯一构造点**，探针可断言）。
 *
 * ★ 与逐柱路 [spectrumCamoColorAt] 用**同一个块划分**（同一个 [camoBlockWidth] 哈希）
 *   ⇒ ★ 两条路的碎块分布**同源**，用户在实心档与柱状档之间切换时不会看到"完全换了一套图" ✓
 *
 * @param blocks 块数（= 逐柱路的 `CAMO_BLOCK_COUNT`）。
 * @param n 调色板色数。
 * @param paletteArgb 调色板。
 * @return `Pair(严格递增的 floats, 等长的 Colors)`；退化 ⇒ `null`。
 */
fun flowCamoStops(
    blocks: Int,
    n: Int,
    paletteArgb: List<Int>,
): Pair<FloatArray, IntArray>? {
    if (n < 2 || blocks < 1) return null
    // ★ 总宽（块宽坐标）—— 与逐柱路**同一个纯函数**，保证两条路块划分一致。
    val total = camoTotalWidth(blocks)
    if (!(total > 0f)) return null
    // ★★ 结构：**每块两个停靠点** = 「平台起点」与「平台终点」，两点**同色**
    //   ⇒ ★ 段内是一段**平台**（该色铺满），★ 平台终点 → 下一块平台起点之间由
    //     `LinearGradientPaint` 线性插值 ⇒ ★ 那就是**柔边**（与逐柱路的
    //     `CAMO_EDGE_SOFT_FRACTION` 同义）✓
    //
    // ## ★★★ 为什么**不能**用「段首 / 段尾」两个停靠点（第一版的错）
    //
    // 第一版把每块写成 `[start_i, end_i]`，而 `end_i == start_{i+1}`（**同一个边界点**）
    // ⇒ ★ 停靠点里出现**相等**的一对 ⇒ ★ `LinearGradientPaint` **构造期抛**
    //   （本项目已记过两次的陷阱：**关键帧必须严格递增，相等也不行**）✗
    // ⇒ 正解 = **让平台不顶到边界**：平台终点取 `start_i + w_i × (1 − EDGE)`，
    //   留出 `w_i × EDGE` 的斜坡给"本块 → 下一块" ✓
    //
    // ⚠️ 斜坡宽度取 `w_i × EDGE` 而不是固定的 `edge/2`（两侧各半）—— ★ 这样
    //   每块的**总占用**恰好是 `w_i`（平台 + 斜坡），且与逐柱路的"块内 `local`
    //   线性过渡"**同一个比例** ⇒ 两条路的柔边宽度**同源** ✓
    val soft = CAMO_EDGE_SOFT_FRACTION.toDouble()
    // ★ 停靠点数 = `blocks × 2 + 1` —— 末尾那个是**周期闭合点**（见下）✓
    val stops = FloatArray(blocks * 2 + 1)
    val argbs = IntArray(blocks * 2 + 1)
    var cum = 0f
    for (bi in 0 until blocks) {
        val w = camoBlockWidth(bi)
        val start = cum / total
        // ★ 平台终点 = 段首 + 段宽 × (1 − EDGE) ⇒ 与下一块的段首**严格不等**（EDGE > 0）✓
        val plateauEnd = (cum + w * (1f - soft)).toFloat() / total
        // ★ 色 = 与逐柱路**同一个纯函数** `camoBlockColor`（块序号 → 调色板色）。
        val c = camoBlockColor(bi, n, paletteArgb)
        stops[bi * 2] = start
        stops[bi * 2 + 1] = plateauEnd
        argbs[bi * 2] = c
        argbs[bi * 2 + 1] = c
        cum += w
    }
    // ★★ **周期闭合点**：轴末端 `1f`，色 = **首块色**。
    //   ⇒ ★ 末块"平台终点 → 1f"这一段 = "末块色 → 首块色"的斜坡 ⇒ ★ 那**正是**
    //     末块与首块交界处的**柔边**（与逐柱路"位置取模 ⇒ 末块与首块环形相邻"同义）✓
    //   ★ 而 `plateauEnd_last < 1f`（因为 EDGE > 0）⇒ 与末块平台终点**严格递增** ✓
    //   ★ 有了它，一个周期的停靠点**恰好覆盖 `[0, 1]`** ⇒ `flowCamoCyclic` 的
    //     平移铺贴才是无缝的（否则周期末端悬空，铺贴会出现"断口"）✓
    val close = blocks * 2
    stops[close] = 1f
    argbs[close] = argbs[0]
    // ★ 后置自检：严格递增（相等即不合法）—— 与 [flowGradientStops] 同一条纪律。
    for (i in 1 until stops.size) if (!(stops[i] > stops[i - 1])) return null
    return stops to argbs
}

/**
 * 迷彩实心档的**循环**停靠点（铺 `FLOW_CYCLES` 个周期，供相位平移无缝循环）。
 *
 * ★ 与 [flowCamoStops] 的区别：本函数把"一个周期的碎块图案"**重复 `cycles` 遍**，
 *   `stops` 覆盖 `[0,1]` 的 `cycles` 个周期 ⇒ 平移一个周期后图案相同（**无缝**）✓
 *   ★ 与 [flowCyclicStops] / [flowCyclicColors] 的分工**完全对称**。
 */
fun flowCamoCyclic(
    blocks: Int,
    n: Int,
    paletteArgb: List<Int>,
    cycles: Int = FLOW_CYCLES,
): Pair<FloatArray, IntArray>? {
    if (cycles < 1) return null
    val one = flowCamoStops(blocks, n, paletteArgb) ?: return null
    val (s1, c1) = one
    val m = s1.size
    // ★★ 一个周期的停靠点覆盖 `[0, 1]`（末点 `1f` 与首点 `0f` **同色** —— 见 [flowCamoStops]）。
    //   ⇒ ★ 铺 `cycles` 遍时，**第 k 遍的末点与第 k+1 遍的首点是同一个位置、同一个色**
    //     ⇒ ★★ 必须**去掉重复点**（否则 `stops` 出现相等 ⇒ 构造期抛，本项目已记两次的陷阱）✗
    //   ⇒ 做法 = 第 0 遍铺满 `m` 点；其后的每遍**跳过首点**（只铺 `m − 1` 点）✓
    //   ⇒ 总点数 = `m + (cycles − 1) × (m − 1)`。
    val total = m + (cycles - 1) * (m - 1)
    val stops = FloatArray(total)
    val argbs = IntArray(total)
    // 第 0 遍：全部 m 点，位置 `s1[i] / cycles`。
    var idx = 0
    for (i in 0 until m) {
        stops[idx] = s1[i] / cycles
        argbs[idx] = c1[i]
        idx++
    }
    // 其后每遍：跳过首点（i = 0），位置 `(k + s1[i]) / cycles`。
    for (k in 1 until cycles) {
        for (i in 1 until m) {
            stops[idx] = (k + s1[i]) / cycles
            argbs[idx] = c1[i]
            idx++
        }
    }
    // ★ 后置自检：严格递增（相等即不合法）—— 与 [flowCamoStops] 同一条纪律。
    for (i in 1 until stops.size) if (!(stops[i] > stops[i - 1])) return null
    return stops to argbs
}

/**
 * ⑰ · **迷彩档**：**碎色块多停靠点渐变的唯一构造点** —— 与 [flowGradientPaint]
 * **同接口、同几何算式**，只把"等距 N 停靠点"换成"不等宽碎色块平台"。
 *
 * @param angleDeg 渐变方向（度）。`0°` = 向右（与逐柱路的"按柱序"同走向）。
 * @param blocks 碎块数（= 逐柱路的 `CAMO_BLOCK_COUNT`）。
 * @return 色数 < 2 / 跨度退化 / 停靠点无法严格递增 ⇒ `null`（调用方退回单色）。
 */
fun flowCamoPaint(
    x: Double, y: Double, w: Double, h: Double,
    angleDeg: Int,
    blocks: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    phase: Float = 0f,
): LinearGradientPaint? {
    val (stops, argbs) = flowCamoCyclic(blocks, paletteArgb.size, paletteArgb) ?: return null
    val a = alpha.coerceIn(0, 255)
    val colors = Array(argbs.size) { i ->
        val c = argbs[i]
        Color((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF, a)
    }
    if (stops.size != colors.size) return null

    if (!w.isFinite() || !h.isFinite()) return null
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = Math.cos(rad)
    val dy = Math.sin(rad)
    val l = Math.abs(w * dx) + Math.abs(h * dy)
    if (!(l > 0.0)) return null
    val cx = x + w / 2.0
    val cy = y + h / 2.0
    val half = FLOW_CYCLES * l / 2.0
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val shift = ph.toDouble() * l
    val ox = cx + shift * dx
    val oy = cy + shift * dy

    return LinearGradientPaint(
        (ox - half * dx).toFloat(), (oy - half * dy).toFloat(),
        (ox + half * dx).toFloat(), (oy + half * dy).toFloat(),
        stops,
        colors,
    )
}
