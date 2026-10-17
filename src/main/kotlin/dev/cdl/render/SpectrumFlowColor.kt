// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

/**
 * 「**封面多色流光**」在频谱上的**逐柱取色** —— 纯函数，可被探针直接断言。
 *
 * ## 它解决什么
 *
 * `spectrum.color_mode` 此前只有三档：`manual`（手色）/ `cover`（**单色**：封面主色派生）/
 * `level`（按振幅）。★ 而 ⑰「封面多色流光」的第 1 步
 * （`CoverColorExtractor.palette(image, n)`）**早就把多色原料算出来了**，
 * 只是一直**没有频谱侧的消费点**。
 *
 * ⇒ 本文件把"多色"接到频谱上：**沿柱序把封面多色铺成一条连续渐变**，
 * 相位可推进（= 流光）。★ 三型频谱（主 / 副 / 插槽三型）**共用这一个纯函数**
 * （纪律 10「合并成唯一出口」）。
 *
 * ## ★★ 为什么是"按柱序插值"，而不是"每柱取一个色"
 *
 * 直接写 `palette[i % n]` 会在相邻柱之间产生**硬色阶** —— 柱宽只有几像素时，
 * 观感是"一堆竖条纹"而不是"渐变"。而本函数在**相邻两色之间做线性插值**
 * ⇒ 柱数够多时**肉眼连续**；柱数少（插槽 5 根）时退化为"几段色"，
 * 那正是"多色"在窄插槽里应有的样子。
 *
 * ## ★★★ 三条不变量（探针逐条钉）
 *
 * | # | 不变量 | 为什么重要 |
 * |---|---|---|
 * | ① | **循环无缝** —— `phase` 走完 1.0 后逐值回到原样 | 流光要"无限循环"，接缝跳色一眼假 |
 * | ② | **首尾同色** —— 谱面最左与最右落在同一个调色板边界 | 它是①在空间上的对应物（一圈 = 一个完整调色板） |
 * | ③ | **退化 ⇒ 原样返回 `baseArgb`** | 色数 < 2 / 柱数 ≤ 1 时**画单色**而不是不画 —— 见下 |
 *
 * ## ★ 退化口径：**返回基色**，不是返回 `null` / 不画
 *
 * `palette()` 的契约**允许返回短列表**（封面单色 / 取色失败 / 纯黑白封面）。
 * 若那时"不画"，用户看到的是**选了多色档却什么都没有** ——
 * 比"颜色不对"更糟，因为它看起来像「**设了没反应**」（本项目最多次的缺陷形态）。
 * ⇒ 退化时**原样返回基色** ⇒ 观感退化为既有单色档，**至少是画出来的** ✓
 * ★ 与 `MaterialLayers` 的 `FLOW` 档"退化 ⇒ 回退 `LINEAR`"**同一条纪律**。
 *
 * ## ★ 与 [levelColorArgb] 的关系（同族、互斥、同一接线点）
 *
 * 两者都通过 `SpectralPainter` 的 `argbForBar` 回调逐柱取色，**由 `spectrum.color_mode` 二选一**：
 *
 * | 档 | 逐柱颜色来自 |
 * |---|---|
 * | `level` | [levelColorArgb]（**振幅** → 明暗浓淡） |
 * | `cover_flow` | **本函数**（**柱序** → 调色板色相） |
 *
 * ★ 两者**都不碰 alpha** —— 透明度是 `spectrum.opacity_pct` / 进度色差的职责
 * （与 [levelColorArgb] 的 KDoc 同一条取舍）。
 *
 * @param baseArgb 本趟的基色（打包 ARGB，**高 8 位是有效 alpha**）。
 *   ★ 由 `SpectralPainter` **逐趟**给（底图趟 = 未播放色 / 进度染色趟 = 已播放色）
 *   ⇒ "两趟用同一个 alpha"由**结构**保证（见 `SpectralPainter.argbForBar` 的 KDoc）。
 * @param index 柱序号（`0 until count`）。
 * @param count 本帧**实际**柱数（★ 由绘制侧给 —— 调用方**不重算**，
 *   否则"画哪根柱"与"取哪个色"会漂移，见 `SpectralPainter.argbForBar` 的 KDoc）。
 * @param paletteArgb 封面多色（`0xFFRRGGBB` 列表，来自 `CoverColorExtractor.palette`）。
 *   `< 2` ⇒ **退化**（返回 [baseArgb]）。
 * @param phase 流光相位（`0..1` = 一个完整周期）。★ 必须由
 *   [loopPhase]（吃 `positionMs`）给出 ⇒ **暂停即冻结**；非有限值按 `0` 处理。
 * @return 打包 ARGB。★ **alpha 恒等于 [baseArgb] 的 alpha**（本函数不碰透明度）。
 */
fun spectrumFlowColorAt(
    baseArgb: Int,
    index: Int,
    count: Int,
    paletteArgb: List<Int>,
    phase: Float,
): Int {
    val a = (baseArgb ushr 24) and 0xFF
    // ★ alpha = 0 ⇒ 提前返回：与 [levelColorArgb] 同一条判据 ——
    //   否则会把"完全透明色"算成不透明，让"透明度拉到 0"这条设置**看起来生效了**。
    if (a == 0) return baseArgb
    val n = paletteArgb.size
    // ★ 两条退化**合并在同一处**（KDoc 的"退化 ⇒ 原样返回 baseArgb"是同一条口径）：
    //   · `n < 2`   ⇒ 一个色构不成渐变（封面单色 / 取色失败 / 纯黑白封面）
    //   · `count <= 1` ⇒ 没有"柱序"可言（实测生产不可达 —— `spectrumBarCount*` 恒 ≥ 1
    //     且经 `SPECTRUM_MIN_BARS` 托底 ⇒ 这里是**防御性**分支，不是主路径）
    if (n < 2 || count <= 1) return baseArgb

    // ① 柱序 → `0..1`（★ 单柱已在上面的退化分支挡掉 ⇒ 分母恒 > 0）。
    val t = (index.toFloat() / (count - 1)).coerceIn(0f, 1f)
    // ② 相位：先取模 ⇒ 任意输入（含负、含 >1、含 NaN）都落 `[0,1)` ⇒ **无缝**且不越界。
    //    ★ 与 `FlowGradient.flowGradientPaint` 的相位处理**逐字同源**。
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val u = (t + ph) % 1f
    // ③ 落到调色板的哪一段。★ `u < 1` ⇒ `x < n` ⇒ `i0 ∈ [0, n-1]`，不会越界。
    val x = u * n
    val i0 = x.toInt()
    val i1 = (i0 + 1) % n
    val f = x - i0
    val c0 = paletteArgb[i0]
    val c1 = paletteArgb[i1]
    val r = lerpChannel(c0, c1, 16, f)
    val g = lerpChannel(c0, c1, 8, f)
    val b = lerpChannel(c0, c1, 0, f)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * 单通道线性插值。
 *
 * ★ **在 sRGB 空间插值**（不做线性光转换）—— 与 `FlowGradient` 的
 * `LinearGradientPaint`（Java2D 在 sRGB 上插值）**同口径** ⇒
 * "背景多色"与"频谱多色"在同一调色板下**颜色走向一致** ✓
 */
private fun lerpChannel(c0: Int, c1: Int, shift: Int, f: Float): Int {
    val a = (c0 ushr shift) and 0xFF
    val b = (c1 ushr shift) and 0xFF
    return (a + (b - a) * f).toInt().coerceIn(0, 255)
}

// ══════════════════════════════════════════════════════════════════════════
// ★★★ 2026-10-14（**迷彩碎色块流光**）：`spectrum.color_mode = cover_camo`
// ══════════════════════════════════════════════════════════════════════════
//
// ## 用户报障（逐字）
//
// > 「多色流光目前的呈现是**竖色条水平向右**，能否做到类似**迷彩样式的碎色块**，
// >   在呈现上实现**无规则的颜色出现规律**水平向右？」
//
// ## ★ 与 [spectrumFlowColorAt] 的**唯一**区别：**"色块边界"vs"连续渐变"**
//
// | | 色相随柱序的变化 | 观感 |
// |---|---|---|
// | [spectrumFlowColorAt] | **连续线性插值** | 一条**平滑**的彩色带在水平移动 |
// | 本函数 | **离散的、宽度不等的色块** | ★ **迷彩/拼贴**——一块块颜色在水平流动 |
//
// ★ 两条**都**是"多色 + 水平流动"，故本函数**保留** [spectrumFlowColorAt] 的四条不变量
//   （循环无缝 / 退化返回基色 / alpha 原样 / 相位规范化）—— 只换"色相映射"那一步 ✓
//
// ## ★★ 为什么用【哈希】而不是【随机数】
//
// · **必须确定性** ⇒ ★ 探针能逐值断言（"第 k 根柱是什么色"是**纯函数**的结果）；
// · **必须逐帧稳定** ⇒ ★ 用 `Random()` 每帧重掷会让整条谱面**逐帧闪烁**（本项目记过的形态）；
// · ★ **必须能随相位平移** ⇒ 相位变化 = 色块整体右移，而不是"重新摇一遍骰子"。
//
// ⇒ ★ 正解 = **位置 → 哈希 → 调色板下标**：同一个位置永远映射到同一个色块，
//   相位只是把"位置"整条右移 ⇒ ★ 平移是**连续**的、且**循环无缝**（位置取模）✓
//
// ## ★★ 为什么是"块"而不是"逐柱随机"
//
// 用户要的是**碎色块**（camo），不是**逐柱雪花**（每根柱一个随机色 ⇒ 观感是噪点）。
// ⇒ ★ 先把谱面按**不等的量子宽度**切成一段段，每段一个色 ⇒ 段内同色（= 一块），
//   段间换色（= 边界）✓ 段宽由哈希给出 ⇒ **无规则** ✓
//
// ## ★ 柔边（用户 2026-10-14 选定「柔边色块」）
//
// ★ 在**段边界附近**做一小段线性过渡 —— 不是硬切，也不是整条连续渐变。
//   过渡宽度 = `CAMO_EDGE_SOFT_FRACTION × 段宽`（按段自适应）⇒ ★ 小块不会被过渡吃掉 ✓

/**
 * **一圈（一个完整相位周期）里有几个色块** —— 控制"碎"的程度。
 *
 * ★ 块越多 ⇒ 色块越窄、越"碎"；越少 ⇒ 越大块拼贴。
 * ★★ 取 **14**：在 41 根柱的预览谱面上约合 **2.9 根柱/块**
 *   ⇒ ★ 既看得出"一块块"，又不会碎成逐柱噪点 ✓
 * ⚠️ **常量而非设置项** —— 若真机观感偏碎/偏整，**改这一个数**即可（同 [SPECTRUM_FLOW_PERIOD_MS]）。
 *
 * ★ **`public` 而非 `private`**：实心档那支碎块渐变（`FlowGradient.flowCamoPaint`）
 *   由 `IslandWindow` 调用，必须拿到**同一个块数** ⇒ ★ 两条路块划分同源 ✓
 *   ⚠️ 也**不能是 `internal`** —— 探针源集（`src/probe/kotlin`）是**独立编译单元**，
 *       `internal` 跨不过去（★ 本项目已记过这一条：探针要断言的符号必须 `public`）。
 */
const val CAMO_BLOCK_COUNT = 14

/**
 * 一个色块的**基准宽度**（在"块宽坐标"里，平均每块 ≈ 1.0）——
 * 见 [camoBlockWidth]：实际块宽 = `基准 × [1, 1 + SPREAD]`。
 *
 * ★ 它只是"块宽坐标"的标度，**不影响观感**（观感由 [CAMO_BLOCK_COUNT] 与
 *   [CAMO_BLOCK_WIDTH_SPREAD] 决定）⇒ 固定为 1。
 */
private const val CAMO_MIN_BLOCK_FRACTION = 1f

/**
 * 一个色块的宽度**最多**是基准宽的几倍 —— 控制"块宽的随机范围"。
 *
 * ★ 3 ⇒ 块宽在 `[1, 4] × 基准` 之间不规则变化 ⇒ ★ "**无规则**的颜色出现规律" ✓
 */
private const val CAMO_BLOCK_WIDTH_SPREAD = 3f

/**
 * 段边界处的**柔化过渡宽度**占该段宽度的比例。
 *
 * ★ 0 ⇒ 硬切（马赛克）；★ 1 ⇒ 整段都在过渡（退回连续渐变）。
 * ★★ 取 **0.34**：段中间约 **2/3 是纯色**（读得出"这是一块"），两侧各 **1/6 段宽**过渡
 *   ⇒ ★ 既有"碎色块"的骨相，又无硬边锯齿 ✓（用户选的是「柔边色块」）
 */
const val CAMO_EDGE_SOFT_FRACTION = 0.34f

/**
 * 位置哈希 → `[0, 1)` 的确定性伪随机数（**不依赖任何全局状态**）。
 *
 * ★ 用 **整数位混合**（Wang hash / xorshift 混合的简化式）而不是 `Math.random()`：
 *   · 确定性 ⇒ 探针可断言；
 *   · 无分配、无锁 ⇒ 逐柱调用（最多 1024 次/帧）也不成负担；
 *   · 雪崩性够好 ⇒ 相邻下标产出**不相关**的值（这正是"无规则"所需的）。
 *
 * @param seed 整数位置（★ 由"块序号"给出，不是柱子序号 —— 见调用点的推导）。
 * @return `[0, 1)` 的浮点伪随机值。
 */
// ★★ 2026-10-15：`camoHash01` **已搬到 `CamoTexture.kt` 并公开**（本文件不再自带一份）。
//
// 原因：⑲「碎色流光」的**二维迷彩贴图**（`render/CamoTexture.kt`）也要用它，
// 而"同一算法两处定义"会在将来某次只改一处时**静默分叉** ⇒
// ★ 按"**同一问题只准有一个定义点**"的纪律，只留一份实现（新位置 `public`，
//   本文件直接调用同包函数）。原 KDoc 一并搬了过去。

/**
 * **迷彩碎色块流光**的逐柱取色 —— 纯函数，可被探针直接断言。
 *
 * 与 [spectrumFlowColorAt] **同接口、同四条不变量**（循环无缝 / 退化返回基色 /
 * alpha 原样 / 相位规范化），**只把"色相映射"从连续插值换成离散热块**。
 *
 * ## 算法（三步）
 *
 * 1. **位置** `p = 柱序/count + 相位`（取模 ⇒ 无缝、不越界）；
 * 2. **切块**：把 `[0,1)` 切成 [CAMO_BLOCK_COUNT] 个**宽度不等**的块
 *    （宽度由 [camoHash01] 按块序号给出）⇒ ★ 无规则的色块分布；
 * 3. **柔边**：落在块边界 `± EDGE/2` 内 ⇒ 与邻块色**线性过渡**，块中间是纯色。
 *
 * ## ★★ 为什么块宽**不等**且块数**固定**
 *
 * · **不等** = 用户要的"**无规则**"（等宽块 = 规则马赛克，不是迷彩）；
 * · **固定块数** = ★ 每条"位置"都由 `块序号` 唯一决定 ⇒ ★ 函数**无状态**、
 *   且"块宽之和"是一个**可由块数推出的常量**（不必预存表）✓
 *   ⚠️ 若改成"块宽各异且块数浮动"，同一位置在不同柱上可能落到不同块 ⇒ 破坏 ② 块内同色。
 *
 * ## ★★ 判据（探针逐条钉）
 *
 * | # | 不变量 | 为什么重要 |
 * |---|---|---|
 * | ① | **循环无缝** —— `phase = 1.0` 逐值等于 `phase = 0.0` | 与 [spectrumFlowColorAt] 同一条 |
 * | ② | **块内同色** —— 同一块内的柱（避开柔边区）**颜色完全相同** | 这才是"色块"，否则退回渐变 |
 * | ③ | **块间换色** —— 离散色块数远多于 1 | 有边界才叫"碎"；全是同色 = 没生效 |
 * | ④ | **退化 ⇒ 原样返回 `baseArgb`** | 与 [spectrumFlowColorAt] 同一条 |
 * | ⑤ | **确定性** —— 同一入参恒产同一结果 | 逐帧稳定，不闪烁 |
 *
 * @param baseArgb 本趟的基色（打包 ARGB，高 8 位是有效 alpha）。
 * @param index 柱序号（`0 until count`）。
 * @param count 本帧实际柱数。
 * @param paletteArgb 封面多色（`< 2` ⇒ 退化）。
 * @param phase 流光相位（`0..1` = 一个完整周期）。
 * @return 打包 ARGB。★ alpha 恒等于 [baseArgb] 的 alpha。
 */
fun spectrumCamoColorAt(
    baseArgb: Int,
    index: Int,
    count: Int,
    paletteArgb: List<Int>,
    phase: Float,
): Int {
    val a = (baseArgb ushr 24) and 0xFF
    // ★ 三条退化**与 [spectrumFlowColorAt] 逐字同源**（alpha=0 / 色数<2 / 柱数≤1）。
    if (a == 0) return baseArgb
    val n = paletteArgb.size
    if (n < 2 || count <= 1) return baseArgb

    // ① 柱序 → `[0,1)`。★ 用 `count`（不是 `count-1`）—— 本档是**循环**语义，
    //    "位置 1.0 与 0.0 是同一点"，用 `count-1` 会让首尾错开半格。
    val t = (index.coerceIn(0, count - 1)).toFloat() / count
    // ② 相位规范化（★ 与 flow 档逐字同源：任意输入都落 `[0,1)`）。
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val p = (t + ph) % 1f

    // ③ 定位：块宽由"块序号"的哈希给出（不等），总宽 = 各块宽之和。
    //    ★ 总宽是一个**只依赖块数**的常量 ⇒ 每次调用重算的成本 = `CAMO_BLOCK_COUNT` 次哈希，
    //      而块数很小（14）⇒ 可接受（本函数每帧每柱调一次，成本仍是 O(1) 量级）。
    val total = camoTotalWidth(CAMO_BLOCK_COUNT)
    // 目标位置（在"块宽坐标"上）—— 让它覆盖整圈。
    var x = p * total
    var blockIndex = 0
    var blockWidth = CAMO_MIN_BLOCK_FRACTION
    for (bi in 0 until CAMO_BLOCK_COUNT) {
        val w = camoBlockWidth(bi)
        if (x < w) {
            blockIndex = bi
            blockWidth = w
            break
        }
        // ★ 减去本块宽 ⇒ 出循环后 `x` 就是"块内偏移（块宽坐标）"。
        x -= w
        blockIndex = bi
        blockWidth = w
    }
    // 段内偏移 `local ∈ [0,1]`（★ 上面的循环已把 `x` 减到"块内"）。
    val local = (x / blockWidth).coerceIn(0f, 1f)

    // ④ 本块色 + 邻块色（哈希给 ⇒ 无规则）。
    val c0 = camoBlockColor(blockIndex, n, paletteArgb)
    val c1 = camoBlockColor(blockIndex + 1, n, paletteArgb)
    val cPrev = camoBlockColor(blockIndex - 1, n, paletteArgb)

    // ⑤ 柔边：只在块边界**两侧各 EDGE/2** 内过渡，块中间是纯色。
    val edge = CAMO_EDGE_SOFT_FRACTION * 0.5f
    val from: Int
    val to: Int
    val f: Float
    when {
        // 前缘（靠近上一块的边界）：上一块色 → 本块色
        local < edge -> {
            from = cPrev; to = c0; f = (local / edge).coerceIn(0f, 1f)
        }
        local > 1f - edge -> {
            // 后缘（靠近下一块的边界）：本块色 → 下一块色
            from = c0; to = c1; f = ((local - (1f - edge)) / edge).coerceIn(0f, 1f)
        }
        else -> {
            // ★ 块中间 = **纯色**（`from == to` ⇒ 插值恒等于该色）—— 这才是"一块"。
            from = c0; to = c0; f = 0f
        }
    }

    val r = lerpChannel(from, to, 16, f)
    val g = lerpChannel(from, to, 8, f)
    val b = lerpChannel(from, to, 0, f)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * 第 [bi] 块的宽度（**未归一化**，单位 = 每个"最小块"的倍数）。
 *
 * ★ 值域 `[1, 1 + CAMO_BLOCK_WIDTH_SPREAD]`，由块序号的哈希给出 ⇒ **不等且确定** ✓
 * ★ 公开（`public`）纯函数：探针要断言"块宽确实不等"。
 */
fun camoBlockWidth(bi: Int): Float =
    1f + CAMO_BLOCK_WIDTH_SPREAD * camoHash01(bi * 2 + 1)

/**
 * `[0, count)` 个块的**宽度之和** —— 用于把归一化位置折算到块坐标。
 *
 * ★ 它是"只依赖块数"的常量函数 ⇒ 调用点每次重算，避免引入可变缓存（纯函数纪律）。
 */
fun camoTotalWidth(count: Int): Float {
    var s = 0f
    for (bi in 0 until count) s += camoBlockWidth(bi)
    // ★ 兜底：理论恒 > 0（每块 ≥ 1），但防御性保留以免将来改常量时除零。
    return if (s > 0f) s else 1f
}

/**
 * 第 [bi] 块映射到的**调色板颜色**。
 *
 * ★ `bi` 可为负 / 超界（柔边会问"上一块 / 下一块"）⇒ **按块数取模**做**环形**邻接
 *   ⇒ ★ 与"位置取模"同一套循环语义（首块的上一块 = 末块）✓
 */
fun camoBlockColor(bi: Int, n: Int, paletteArgb: List<Int>): Int {
    val idx = ((bi % CAMO_BLOCK_COUNT) + CAMO_BLOCK_COUNT) % CAMO_BLOCK_COUNT
    val pick = (camoHash01(idx * 2) * n).toInt().coerceIn(0, n - 1)
    return paletteArgb[pick]
}

/**
 * ★★★ 2026-10-12（**BUG-172**）：**频谱流光自己的周期** —— ★ 与背景流光**分开**。
 *
 * ## 用户报障（逐字）
 *
 * > 「**动态流光的颜色循环速度过快。**」
 *
 * ## 根因：★ 两者**共用**了 [FLOW_PERIOD_MS]，但两者的**可见尺度差一个量级**
 *
 * | | 渐变轴长 | 6 秒走完一个周期时的观感 |
 * |---|---|---|
 * | ★ **背景流光**（整岛） | ★ 整岛宽 / 高（数百 px） | ★ 缓慢漂移 ✓ |
 * | ★★ **频谱流光**（频谱带） | ★★ **一条窄带**（数十 px） | ★★ 同样的相位速度 ⇒ **颜色在带上来回窜** ✗ |
 *
 * ★ 相位是**相对量**（0..1 = 一个周期），而**观感速度 = 相位速度 × 图案在屏幕上的位移** ✓
 * ⇒ ★★ 轴越短，同样的相位速度看起来越快 ✗ —— 这正是用户看到的"过快" ✓
 *
 * ## 取值
 *
 * ★ **18000 ms（18 秒）** = 背景的 **3 倍**。
 * ★ 判据：频谱是**装饰**、歌词才是主体 ⇒ 它**不该抢眼**；
 *   ★ 3 倍是我按"背景 6 秒在窄带上约等于 2 秒的观感"反推的**起点值**，
 *   ★★ **它是一个常量（不是设置项）** —— 若真机仍偏快/偏慢，**改这一个数**即可 ✓
 *
 * ⚠️ **与 [FLOW_PERIOD_MS] 的关系**：★ 两者**语义相同、尺度不同** ——
 *   ★ 不要合并回一个（那正是本缺陷的成因）✗
 */
const val SPECTRUM_FLOW_PERIOD_MS = 18000L

// ══════════════════════════════════════════════════════════════════════════════
// ★★★ 2026-10-17（**BUG-226**）：插槽小频谱的**二维碎色块**
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 碎色块的**分组列数**（横向）：同一组内的柱共用一色。
 *
 * ★ 取 2：插槽频谱默认只有 5 根柱，若每柱一色 ⇒ 5 条竖纹（用户说的
 *   「**不是背景那种**」）。2 列一组 ⇒ 相邻两根同色 ⇒ 出现"块"而不是"条"。
 */
const val CAMO_SEGMENT_GROUP_COLS: Int = 2

/**
 * 碎色块的**分组行数**（纵向）：同一组内的段共用一色。
 *
 * ★ 取 2：柱体被切成 [CAMO_SEGMENT_ROWS] 段后，2 段一组 ⇒ 上下各半各一色，
 *   与横向分组一起构成**二维块**（而不是"每段一色"的横纹）。
 */
const val CAMO_SEGMENT_GROUP_ROWS: Int = 2

/**
 * 插槽频谱柱体被切成的**段数**（纵向）。
 *
 * ## 为什么必须切（用户报障逐字）
 *
 * > 「**插槽小频谱的碎色流光不是背景那种。**」
 *
 * ★ 根因：`spectrumCamoColorAt` 是**一维**的（按柱序）⇒ 每根柱子**整根一个颜色**
 *   ⇒ 5 根柱 = 5 条竖纹。而岛体背景是**二维细胞噪声**（`CamoTexture`）⇒
 *   两者的"碎"不是一个形态 ✗
 *
 * ★ 修法：把每根柱体**沿高度切 3 段**，每段按 (柱序, 段序) 的**二维哈希**取色 ⇒
 *   柱体内部出现上下不同的色块 ⇒ 与背景的二维碎块同构 ✓
 *
 * ★ 取 3 的理由：2 段太少（只像上下渐变），4 段在 5 根柱的小插槽里
 *   每段只剩 2~3 px 高 ⇒ 看不出块。3 段是"看得出块、又不碎成噪点"的平衡点。
 */
const val CAMO_SEGMENT_ROWS: Int = 3

/**
 * ★★★ 插槽频谱的**二维碎色块取色** —— 纯函数，**唯一实现**。
 *
 * ## 与 [spectrumCamoColorAt] 的分工
 *
 * | 函数 | 维度 | 用在哪 |
 * |---|---|---|
 * | [spectrumCamoColorAt] | **一维**（柱序） | 主/副频谱的柱状档（柱多、够细） |
 * | 本函数 | **二维**（柱序 × 段序） | **插槽小频谱**（柱少，一维会变成竖纹） |
 *
 * ## 三条不变量（与一维版**同口径**）
 *
 * ① `alpha` 恒等于 `baseArgb` 的 alpha（两趟染色各自用自己的基色）；
 * ② 色数 `< 2` ⇒ 原样返回基色（**不是不画**）；
 * ③ 确定性：同一入参恒产同一结果。
 *
 * ## 流光怎么动
 *
 * 组坐标整体随 `phase` **横向平移一组**（一个周期）⇒ 色块会缓慢"游过"柱体，
 * 而不是原地闪烁 ✓（★ 与一维版的"块沿柱序流动"同一个观感方向）
 *
 * @param baseArgb 本趟基色（打包 ARGB）
 * @param col 柱序号（`0 until cols`）
 * @param seg 段序号（`0 until segs`）
 * @param cols 本帧实际柱数
 * @param segs 段数（= [CAMO_SEGMENT_ROWS]）
 * @param paletteArgb 封面多色
 * @param phase 流光相位（`0..1`）
 */
fun spectrumCamoBlockColorAt(
    baseArgb: Int,
    col: Int,
    seg: Int,
    cols: Int,
    segs: Int,
    paletteArgb: List<Int>,
    phase: Float,
): Int {
    val a = (baseArgb ushr 24) and 0xFF
    if (a == 0) return baseArgb
    val n = paletteArgb.size
    if (n < 2) return baseArgb
    if (cols <= 0 || segs <= 0) return baseArgb

    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    // 组坐标（组内同色 ⇒ 视觉上是"块"而不是"每格一色"）
    val gx = col.coerceIn(0, cols - 1) / CAMO_SEGMENT_GROUP_COLS
    val gy = seg.coerceIn(0, segs - 1) / CAMO_SEGMENT_GROUP_ROWS
    // 流光：组坐标整体横向平移（一个周期走完一遍组数）
    val groups = (cols + CAMO_SEGMENT_GROUP_COLS - 1) / CAMO_SEGMENT_GROUP_COLS
    val shift = (ph * groups).toInt()
    val seed = (gx + shift) * 73 + gy * 151
    val k = (camoHash01(seed) * n).toInt().coerceIn(0, n - 1)
    return (a shl 24) or (paletteArgb[k] and 0xFFFFFF)
}
