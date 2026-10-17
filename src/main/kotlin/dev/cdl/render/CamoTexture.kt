// SPDX-License-Identifier: AGPL-3.0-only
//
// ⑲「碎色流光」的**二维迷彩贴图**（2026-10-15）
//
// ══════════════════════════════════════════════════════════════════════════════
// 为什么整个重写（用户报障，逐字）
// ══════════════════════════════════════════════════════════════════════════════
//
// > 「**碎色流光完全没做到应有效果，还是竖条纹状**，并且色彩衔接还没有多色流光衔接的好。」
//
// ★★★ **根因是结构性的，不是参数问题**：
//
// 上一版（`FlowGradient.flowCamoPaint`，2026-10-14 加的）用 `LinearGradientPaint`
// 表达"碎色块" —— 而**线性渐变是一维的**：颜色只沿一个轴变化，**垂直于轴的方向上
// 颜色恒定**。在"岛体是横向长条、`gradient_angle_deg = 0`"的默认配置下，
// 那必然呈现为**竖条纹**（无论把停靠点切成多少段、块宽怎么抖动、柔边多宽）✗
//
// ⇒ ★ **"迷彩碎块"要的是二维图案，一维笔刷画不出来。** 必须换实现。
//   ★ 这也是本条最值钱的判据：**当用户说"效果完全不对"时，先问"这个效果
//     在当前数据结构里表达得出来吗"** —— 若表达不出来，调参是白费功夫。
//
// ══════════════════════════════════════════════════════════════════════════════
// 新实现：程序生成的迷彩贴图 + `TexturePaint`
// ══════════════════════════════════════════════════════════════════════════════
//
// | 要素 | 做法 | 为什么 |
// |---|---|---|
// | **二维** | **细胞噪声**（Worley）：把贴图切成 `COLS × ROWS` 格，每格一个**抖动过的**细胞中心，逐像素取"最近的细胞" | 水平/垂直**都有变化** ⇒ 不再是条纹 ✓ |
// | **柔边** | 边界处按 `(d2 − d1)` 在**两色之间插值**（不是硬切、也不是 alpha 淡出） | 用户 2026-10-14 选型是「**柔边色块**」；插值比 alpha 更干净（不会与底色混出灰边） |
// | **无规则** | 细胞中心的抖动 + 颜色选择**都由哈希给出** | 用户要的「**无规则的颜色出现规律**」；且**确定性** ⇒ 探针可逐值断言 ✓ |
// | **水平流动** | 平移 `TexturePaint` 的**锚点**（`anchor.x -= phase × W`） | ★ **不重建贴图** ⇒ 每帧成本 = 新建一个轻量对象 ✓ |
// | **水平无缝** | 细胞距离用**环绕距离**（`dx > W/2` ⇒ 取 `W − dx`） | 平铺时左右接缝处图案连续 ✓ |
// | **缓存** | 按 `(尺寸, 调色板, alpha)` 的 FNV 键缓存**一张**图 | 贴图生成是逐像素的（256×64 ≈ 16k 像素 × 9 次距离计算）；调色板只在切歌时变 ⇒ 缓存命中率极高 ✓ |
//
// ## ★★ 与 `spectrum.color_mode = cover_camo`（逐柱路）的关系
//
// 那条路服务**频谱柱**（一维、按柱序），本来就是"每根柱一个色" ⇒ 不需要二维，
// 故它**保留不动**（`SpectrumFlowColor.spectrumCamoColorAt`）。
// 本文件只服务**词岛背景**（二维面）。
// ★ 两条路**不再强求"块数同源"** —— 它们是两种媒介（柱 vs 面）：把"面上好看的
//   碎块密度"绑到"柱数/块数常量"上，正是上一版把 `CAMO_BLOCK_COUNT = 14`
//   硬套到 3 倍岛宽上、结果每块宽达岛宽 1/5 的原因 ✗
//
// ══════════════════════════════════════════════════════════════════════════════
// ★ 上一版留下的两条 `LinearGradientPaint` 陷阱（**已不适用**，但留痕）
// ══════════════════════════════════════════════════════════════════════════════
//
// 旧实现为了"用一维笔刷假装二维"付出了两条代价，现在随实现一起消失：
//   ① 关键帧必须**严格递增**（相等也不行 ⇒ 构造期抛）—— 故要把每块拆成
//      "平台起点 + 平台终点"两个停靠点，还要处理循环拼接时的重复点；
//   ② 循环铺 `FLOW_CYCLES` 个周期才能保证"任意相位下可见区都在轴内"。
// ★ **本文件完全不碰 `LinearGradientPaint`**（`TexturePaint` 没有这两条约束）——
//   这是"换对了数据结构，复杂度自己消失"的一例。

package dev.cdl.render

import java.awt.TexturePaint
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/**
 * 一个迷彩周期的**贴图宽度**（px）—— 也是"流动一个周期"平移的距离。
 *
 * ★ 取 **256** 的理由：
 * · **够宽**：`CAMO_TEX_COLS = 7` 列 ⇒ 每列约 36.6 px，在 300–500 px 宽的岛体上
 *   看得见"一块块"而不是"细碎噪点"；
 * · **是 2 的幂**：贴图行距 / 平铺对齐都不会出现半像素抖动；
 * · **与岛宽解耦**：贴图**不缩放**地平铺 ⇒ 岛变宽只是多铺几块，
 *   **块的大小不随岛宽变**（否则展开动画期间块会"长大"，很怪）。
 */
const val CAMO_TEXTURE_W = 256

/**
 * 贴图高度的**分档粒度**（px）—— 岛高按此向上取整。
 *
 * ★ 为什么必须分档：岛高在**展开/收起动画**期间逐帧变化，而贴图高度是它的函数。
 * 不分档 ⇒ **每帧重建贴图**（16k 像素的逐像素循环）⇒ 动画掉帧 ✗
 * 分档后只有跨越档位时才重建（32 px 一档，动画期间至多几次）✓
 */
const val CAMO_TEXTURE_H_BUCKET = 32

/** 贴图高度的**上限**（px）—— 防止极端字号把逐像素循环撑爆。 */
const val CAMO_TEXTURE_MAX_H = 256

/**
 * 贴图里**列**方向的细胞数（= 水平方向的碎块数）。
 *
 * ★ 7 列 / 256 px ⇒ 每列约 36.6 px。★ **它就是"碎"的程度**：
 *   调大 ⇒ 更碎（趋向噪点）；调小 ⇒ 更大块拼贴。
 */
const val CAMO_TEX_COLS = 10

/**
 * 贴图里**行**方向的细胞数（= 垂直方向的碎块数）。
 *
 * ★ **必须 ≥ 2** —— 这是"二维"的**结构性保证**：只要行数 ≥ 2，
 *   同一列的不同高度就**必然**落在不同细胞里 ⇒ 图案不可能退化成竖条 ✓
 *   （探针钉住这条，见 `FlowGradientProbe` ⑪ 的"二维性"断言。）
 */
const val CAMO_TEX_ROWS = 5

/**
 * 细胞边界的**过渡带宽度**（占"细胞尺寸较小者"的比例）。
 *
 * ★ `0` ⇒ 硬切（马赛克）；★ 越大 ⇒ 过渡越宽（越像模糊）。
 * ★ 取 **0.55**：细胞内部约 45% 面积是纯色（读得出"这是一块"），
 *   边界两侧合计约 55% 在做插值 ⇒ ★ 既有"碎色块"的骨相，又无硬边 ✓
 *   （这正是用户 2026-10-14 选的「**柔边色块**」。）
 */
const val CAMO_CELL_SOFT = 0.55f

/**
 * 细胞中心的最大抖动幅度（占格宽/格高的比例，`0` = 规则网格）。
 *
 * ★ 取 **1.15**（即 ±57.5%）—— ★★★ 2026-10-16 由 `0.7` **上调**。
 *
 * ## 为什么上调（用户逐字：「**进一步打乱碎色块出现逻辑，使其看起来无规则**」）
 *
 * ★ `0.7` 时中心只在**自己那格内**移动 ⇒ 每个细胞仍然"占着"一个格子的地盘
 *   ⇒ ★★ **格子感仍在**（虽然位置乱了，但**密度**是均匀的：一格一块）✗
 * ★ `> 1` 时中心**可以越过格界** ⇒ 相邻细胞的地盘**此消彼长**
 *   ⇒ 细胞大小开始**悬殊** ⇒ 这才像迷彩（有大块、也有小块）✓
 *
 * ⚠️ **原 KDoc 说「> 1 会出现细长碎片」—— 那句是 `0.7` 时代的取舍记录**，
 *   而用户要的**正是**"无规则"。★ 细长碎片由 [CAMO_CELL_DROP_RATE] + 柔边 + 模糊
 *   一起压住（见 [camoTextureArgb] 的三步），实机观感由用户验收 ✓
 */
const val CAMO_CELL_JITTER = 1.9f

/**
 * ★★★ 2026-10-16（**用户要求「进一步打乱碎色块出现逻辑」**）：
 * **槽位不产出细胞的概率**（`0` = 每格必有一块 —— 即改动前）。
 *
 * ## 它解决的是"格子感"的**真正来源**
 *
 * ★ 改动前：`cols × rows` 个格子，**每格必定产出一个细胞** ⇒
 *   ★★ 无论怎么抖动，**块的密度是均匀的**（一格一块）⇒ 眼睛仍能看出**规则网格** ✗
 * ★ 现在：约 [CAMO_CELL_DROP_RATE] 的槽位**不产出** ⇒
 *   ★★ 幸存者的地盘**互相吞并** ⇒ **块的大小变得悬殊**（有大块、有碎块）✓
 *
 * ## 取 0.28 的取舍
 *
 * · 太小（< 0.1）⇒ 与改动前几乎无差别，用户看不到变化；
 * · 太大（> 0.5）⇒ 幸存细胞太少 ⇒ 块**过大**，"碎色"退化成"大色块" ✗
 * · ★ **0.28** ⇒ 约 1/4 的槽位空掉，平均每格约 0.72 个细胞
 *   ⇒ 地盘大小出现明显离散，而**碎度仍在** ✓
 *
 * ⚠️ 兜底：若空得只剩 < 2 个细胞，[camoCellCenters] 会**强制保留前两个**
 *   （一个细胞构不出"碎色"，且下游 `d1/d2` 需要两个不同细胞）✓
 */
const val CAMO_CELL_DROP_RATE = 0.28f

/**
 * 每行的**整体横向相位偏移**（占格宽的比例，双向）。
 *
 * ## 为什么光有抖动不够（★ 2026-10-17，用户第二次报障）
 *
 * 用户逐字：「**我说的碎块过于规律指的是出现位置的规律**，
 * 现在碎色流光档的色块分布规律性太强。」
 *
 * ★ 上一轮（2026-10-16）只加了「抖动 + 丢格」，而**骨架仍是网格**：
 * 每个格子至多一个细胞，且格子本身对齐 ⇒ 细胞仍大致排成
 * **列对齐的若干行** ⇒ 眼睛看得出条带感 ✗
 *
 * ★ 修法：给**每一行**一个由 hash 决定的整体横向偏移 ⇒
 * 行与行之间**不再对齐**（类似砖墙排列），而每行内部仍有抖动 ✓
 * 与抖动**叠加**（两者独立）。
 *
 * 取 1.6 ⇒ 偏移 ±0.8 格：足以让相邻两行的细胞**错开半格以上**，
 * 而不至于把某一行整体推出可见区。
 */
const val CAMO_CELL_ROW_PHASE = 1.6f

/**
 * ★★★ 2026-10-15（第二轮 · 用户报障）：**整体模糊半径**（占"细胞较小尺寸"的比例）。
 *
 * 用户逐字：「**色块边界还是不够模糊，要那种边界不明显的朦胧色感**。」
 *
 * ## 为什么"两色插值"还不够
 *
 * 上一轮已把边界权重从**折线**改成**全连续**（过渡区扩大一倍）——
 * 但那仍是**两色之间的线性插值**：它在细胞边界处**必然**留下一条
 * "可辨的过渡带"（宽度 = `2 × CAMO_CELL_SOFT × min(cellW, cellH)`），
 * 带的两侧仍是**纯色** ⇒ 肉眼仍能看出"一块一块的轮廓" ✗
 *
 * ⇒ ★ 用户要的"**朦胧**"不是"更宽的插值"，而是**把整幅图案的边界化开** ——
 *   即一次**空间模糊**（邻域加权平均）。这与"插值"是**两个不同的手段**：
 *   · 插值 = 在两个**已知色**之间取值（边界仍是"线"）；
 *   · 模糊 = 让每个像素**吸收邻域的颜色**（边界变成"带"）✓
 *
 * ## 取值：0.35（≈ 细胞较小尺寸的 35%）
 *
 * 在默认细胞（约 36.6 × 21.3 px）上 ⇒ 半径 ≈ **7.5 px**，
 * 经**两遍**可分离 box blur（等效高斯 σ ≈ r × 0.8）⇒ 边界糊成约 24 px 的过渡带，
 * 而块心（约 18 px）仍保留纯色 ⇒ ★ **"碎块"的骨相还在，边界已不可辨** ✓
 *
 * ★ 实测（`_CamoBlurShot`，128 × 48 贴图）：
 *
 * | 半径 | 平均相邻色差 | **最大相邻色差** | 观感 |
 * |---|---|---|---|
 * | **0**（上一轮） | 10.3 | **182** | 色块**多边形轮廓清晰可辨** ✗ |
 * | **7.5**（本档） | ≈ 6.5 | **< 25** | 轮廓化开、**仍能感到"哪块是哪块"** ✓ |
 * | 12 | 4.8 | 11 | 糊成一片、"碎块"感消失 ✗（退化成普通渐变） |
 *
 * ⇒ ★ **`最大相邻色差` 是"边界糊没糊"的量化判据**（0 ⇒ 182，7.5 ⇒ < 25）；
 *   探针用它做断言，并用 `半径 0` 做**判别力自证** ✓
 *
 * ⚠️ 调大 ⇒ 更朦胧（趋向"多色烟雾"）；调到 **0** ⇒ 退回上一轮的纯插值观感。
 */
const val CAMO_BLUR_FRACTION = 0.35f

/**
 * 模糊的**遍数**（同一半径重复做）。
 *
 * ★ 2 遍：一遍 box blur 的核是**方形**（有明显方向感）；
 *   两遍（横 + 纵各一遍再各来一次）后**近似高斯** ⇒ 过渡带**没有方形痕迹** ✓
 * ★ 再多遍收益递减而成本线性增长（3 遍比 2 遍柔不了多少）。
 */
const val CAMO_BLUR_PASSES = 2

/**
 * [camoTextureArgb] 的 `blurRadiusPx` 哨兵值：**按细胞尺寸自动算**。
 *
 * ★ 单独给一个常量而不是裸写 `-1`：这个值有**两个**消费点
 * （`camoTextureArgb` 的默认参数、`camoImageFor` 的默认参数）——
 * ★ 按"同一语义只准有一个定义点"，不能两处各写一个字面量。
 */
const val CAMO_BLUR_AUTO = -1

/**
 * 位置哈希 → `[0, 1)` 的确定性伪随机数（**不依赖任何全局状态**）。
 *
 * ★ 用 **整数位混合**（murmur3 finalizer 的常数）而不是 `Random()`：
 * · **确定性** ⇒ 探针可逐值断言（"第 k 个细胞在哪、什么色"是**纯函数**的结果）；
 * · **逐帧稳定** ⇒ 用 `Random()` 每次重建贴图都会**换一套图案** ⇒ 切歌时闪一下；
 * · **无分配、无锁** ⇒ 逐像素调用（16k × 常数次）也不成负担；
 * · **雪崩性够好** ⇒ 相邻下标产出**不相关**的值（这正是"无规则"所需的）。
 *
 * ★★ 2026-10-15：本函数从 `SpectrumFlowColor.kt`（那里是 `private`）**搬到这里并公开** ——
 *   现在它服务**两处**（逐柱频谱路 + 本文件的迷彩贴图）⇒
 *   ★ 按"同一问题只准有一个定义点"的纪律，**必须只有一份实现**。
 *
 * @param seed 整数位置（由"细胞序号"或"块序号"给出）。
 * @return `[0, 1)` 的浮点伪随机值。
 */
fun camoHash01(seed: Int): Float {
    // ★ 标准 32 位混合（murmur3 finalizer 的常数），雪崩性好、无乘法溢出顾虑（Int 回绕即可）。
    var h = seed
    h = h xor (h ushr 16)
    h *= -0x7a143595 // 0x85ebca6b
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb // 0xc2b2ae35
    h = h xor (h ushr 16)
    // ★ 取**低 24 位**再归一化 ⇒ 值域 `[0, 1)`，且不受符号位影响（`and` 后恒非负）。
    return (h and 0xFFFFFF) / 16777216f
}

/**
 * 岛高（px）→ **贴图高度**（px）：向上取整到 [CAMO_TEXTURE_H_BUCKET] 的倍数，并夹到上限。
 *
 * ★ **只增不减**（向上取整）：贴图**永远不会矮于岛体** ——
 *   否则岛体下半部分会落到"平铺的第二行贴图"上，图案被拦腰截断。
 * ★ 夹上限是防御：字号极大时（`main_lyric.font.size` 可到 200+）岛高可达数百 px，
 *   而贴图是逐像素生成的 ⇒ 上限把它挡在 256 px（256×256 = 65k 像素，仍在毫秒级）✓
 */
fun camoTextureHeightFor(islandHeightPx: Int): Int {
    val px = islandHeightPx.coerceAtLeast(1)
    val bucketed = ((px + CAMO_TEXTURE_H_BUCKET - 1) / CAMO_TEXTURE_H_BUCKET) * CAMO_TEXTURE_H_BUCKET
    return bucketed.coerceIn(CAMO_TEXTURE_H_BUCKET, CAMO_TEXTURE_MAX_H)
}

/**
 * 贴图的**细胞中心**坐标（`FloatArray(cols × rows × 2)`，`x, y` 交替）。
 *
 * 每格的中心 = 格中心 + 抖动（幅度 = [jitter] × 格尺寸）。
 *
 * ★ **抖动的两个分量用不同的哈希种子**（`i*4+1` / `i*4+3`）——
 *   若同一个种子，所有细胞会沿**同一条对角线**偏移，图案出现可见的斜纹 ✗
 * ★ 中心**可能落在 `[0, w)` 之外**（抖动导致）—— 那是**有意**的：
 *   距离计算用**环绕**公式（见 [camoTextureArgb]），越界中心等价于"从另一侧卷进来" ✓
 *
 * ## ★★★ 2026-10-16（用户「进一步打乱碎色块出现逻辑」）：槽位**可能不产出细胞**
 *
 * ★ 以概率 [dropRate] 跳过某个槽位 ⇒ 该位置**没有中心** ⇒
 *   相邻细胞的地盘**互相吞并** ⇒ ★★ **块的大小变得悬殊**（有大块、有碎块）✓
 *   （改动前每格必有一块 ⇒ 密度均匀 ⇒ 无论怎么抖都看得出**规则网格** ✗）
 *
 * ★ **被跳过的槽位写 `Float.NaN`**（而不是 `0f`）——
 *   ★★ `0f` 是**合法坐标**，调用方无法区分"这格没有细胞"与"细胞在原点"
 *     （本项目纪律：「『默认值』与『没传值』必须用不同的哨兵」）✓
 *   ★ [camoTextureArgb] 的主循环**必须** `continue` 掉 `NaN` —— 否则它会被算成
 *     "原点处有个细胞"，把左上角糊成一块 ✗
 *
 * ★ **兜底**：若跳完只剩 < 2 个细胞，**强制保留前两个**（未抖动的位置）——
 *   一个细胞构不出"碎色"，且下游的 `d1/d2` 需要**两个不同**细胞 ✓
 *
 * @param cols / rows 列数 / 行数（都 ≥ 1）
 * @param jitter 抖动幅度（占格尺寸的比例）。默认 [CAMO_CELL_JITTER]。
 *   ★ 探针传 `0f` 可得**规则网格**，用于判别力自证（同 [camoTextureArgb] 的 `blurRadiusPx`）✓
 * @param dropRate 槽位不产出细胞的概率。默认 [CAMO_CELL_DROP_RATE]。
 *   ★ 探针传 `0f` 可得**改动前**的"每格一块"，用于对比 ✓
 */
fun camoCellCenters(
    w: Int,
    h: Int,
    cols: Int,
    rows: Int,
    jitter: Float = CAMO_CELL_JITTER,
    dropRate: Float = CAMO_CELL_DROP_RATE,
): FloatArray {
    val out = FloatArray(cols * rows * 2)
    val cellW = w.toFloat() / cols
    val cellH = h.toFloat() / rows
    val drop = dropRate.coerceIn(0f, 0.9f)
    val jit = if (jitter.isFinite()) jitter else CAMO_CELL_JITTER
    var active = 0
    for (ri in 0 until rows) {
        for (ci in 0 until cols) {
            val i = ri * cols + ci
            // ★ 落点判定：本槽位**不产出**细胞 ⇒ 写 NaN（不是 0f，见 KDoc）
            if (drop > 0f && camoHash01(i * 8 + 5) < drop) {
                out[i * 2] = Float.NaN
                out[i * 2 + 1] = Float.NaN
                continue
            }
            val jx = (camoHash01(i * 4 + 1) - 0.5f) * jit * cellW
            val jy = (camoHash01(i * 4 + 3) - 0.5f) * jit * cellH
            // ★ 2026-10-17：**行相位** —— 打破"列对齐"（见 [CAMO_CELL_ROW_PHASE] 的 KDoc）。
            //   只由 `ri` 决定 ⇒ **同一行的细胞一起平移** ⇒ 行内相对关系不变、行间错开 ✓
            val rowPhase = (camoHash01(ri * 7919 + 101) - 0.5f) * cellW * CAMO_CELL_ROW_PHASE
            out[i * 2] = (ci + 0.5f) * cellW + jx + rowPhase
            out[i * 2 + 1] = (ri + 0.5f) * cellH + jy
            active++
        }
    }
    // ★ 兜底：至少 2 个细胞（见 KDoc）。把前两个槽位强制填成**格中心**（无抖动）。
    if (active < 2) {
        for (i in 0 until minOf(2, cols * rows)) {
            val ci = i % cols
            val ri = i / cols
            out[i * 2] = (ci + 0.5f) * cellW
            out[i * 2 + 1] = (ri + 0.5f) * cellH
        }
    }
    return out
}

/**
 * ★★★ **迷彩贴图的逐像素 ARGB**（`IntArray(w × h)`，行主序）—— 本文件的**核心纯函数**。
 *
 * ## 算法（三步）
 *
 * 1. **细胞归属**：对每个像素找**最近**（`d1`, 细胞 `i1`）与**次近**（`d2`, 细胞 `i2`）
 *    的细胞中心。距离用**环绕**公式 ⇒ 水平方向无缝（左右边界接得上）。
 * 2. **边界权重** `t = ½ · (1 − clamp((d2 − d1) / soft, 0, 1))`
 *    · 细胞**内部**（`d2 − d1` 大）⇒ `t = 0` ⇒ 纯 `i1` 色；
 *    · **正落在边界上**（`d2 == d1`）⇒ `t = ½` ⇒ 两色**各半**。
 * 3. **颜色**：`t == 0` ⇒ 直接用 `i1` 色；否则在 `i1` / `i2` 色之间**逐通道插值**。
 *
 * ★★ **为什么用"颜色插值"而不是"alpha 淡出"**：
 * alpha 淡出会让边界处**露出下层**（岛体底色）⇒ 混出灰边、且整体变暗；
 * 颜色插值则是**两色直接混合** ⇒ 干净 ✓（这正是"柔边色块"要的观感。）
 *
 * ## 返回值语义
 *
 * | 条件 | 结果 |
 * |---|---|
 * | `w`/`h` ≤ 0、`cols`/`rows` < 1 | `null`（调用方退回单色） |
 * | `paletteArgb.size < 2` | `null` —— ★ 与 [flowGradientPaint] 的"色数 < 2 ⇒ null"**同一条纪律**：一个色构不出"多色" |
 * | 正常 | `IntArray(w × h)`，每个像素 `alpha` 恒等于入参 `alpha` |
 *
 * ★ **每个像素都会被某个细胞覆盖**（细胞铺满整个平面）⇒ 贴图**没有透明缝**：
 *   `alpha` 就是"整幅贴图的不透明度" ✓
 *
 * @param alpha 贴图整体的不透明度（`0..255`，越界被夹）
 * @param blurRadiusPx ★★★ 2026-10-15（第二轮）：**整体模糊半径**（px）。
 *   `-1`（默认）⇒ 按细胞尺寸自动算（`CAMO_BLUR_FRACTION × min(cellW, cellH)`）；
 *   `0` ⇒ **不模糊**（= 上一轮的纯插值观感，供探针做**判别力自证**）；
 *   `> 0` ⇒ 用该半径。
 *   ★ 用户报障（逐字）：「色块边界还是不够模糊，要那种边界不明显的**朦胧色感**。」
 */
fun camoTextureArgb(
    w: Int,
    h: Int,
    cols: Int,
    rows: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    blurRadiusPx: Int = CAMO_BLUR_AUTO,
    jitter: Float = CAMO_CELL_JITTER,
    dropRate: Float = CAMO_CELL_DROP_RATE,
): IntArray? {
    if (w <= 0 || h <= 0 || cols < 1 || rows < 1) return null
    val n = paletteArgb.size
    if (n < 2) return null
    val a = alpha.coerceIn(0, 255)

    val cellCount = cols * rows
    // ★ 2026-10-16：`jitter` / `dropRate` 透传（默认 = 生产取值 ⇒ 生产调用点**一字不改**）✓
    val centers = camoCellCenters(w, h, cols, rows, jitter, dropRate)
    val cellW = w.toFloat() / cols
    val cellH = h.toFloat() / rows
    // ★ 过渡带基准（px）= `CAMO_CELL_SOFT × 较小的那个细胞尺寸`。
    //   ⚠️ 它**在主循环里**用（见下面的 `t = …`），不在外层算 ——
    //      因为分母是 `2 × softPx`（新算式，见下），把它提到外层会让读者误以为
    //      "过渡带 = softPx"（那是旧算式的口径），从而误调常量。

    // ★ 每个细胞的颜色**预取一次**（逐像素重复哈希是纯浪费）。
    val cellColors = IntArray(cellCount) { i ->
        val idx = (camoHash01(i * 2 + 7) * n).toInt().coerceIn(0, n - 1)
        paletteArgb[idx] and 0xFFFFFF
    }

    val out = IntArray(w * h)
    val halfW = w * 0.5f
    for (y in 0 until h) {
        val py = y + 0.5f
        val rowBase = y * w
        for (x in 0 until w) {
            val px = x + 0.5f
            var d1 = Float.MAX_VALUE
            var d2 = Float.MAX_VALUE
            var i1 = 0
            var i2 = 0
            for (i in 0 until cellCount) {
                // ★★★ 2026-10-16：**被跳过的槽位必须 `continue`** ——
                //   它的坐标是 `NaN`（见 [camoCellCenters] 的 KDoc）。
                //   ⚠️ 若漏了这句，`NaN` 参与比较恒为 `false` ⇒ 它**既不会**成为 d1、
                //      **也不会**成为 d2 ⇒ 看起来"无害"；★ 但那是**巧合**，不是保证 ——
                //      一旦将来有人把哨兵改成 `0f`（合法坐标），左上角就会被糊成一块 ✗
                if (centers[i * 2].isNaN()) continue
                var dx = px - centers[i * 2]
                if (dx < 0f) dx = -dx
                // ★ 环绕：横向距离取 `min(dx, w − dx)` ⇒ 左右边界接得上 ✓
                if (dx > halfW) dx = w - dx
                val dy = py - centers[i * 2 + 1]
                val d2Sq = dx * dx + dy * dy
                if (d2Sq < d1) {
                    d2 = d1; i2 = i1
                    d1 = d2Sq; i1 = i
                } else if (d2Sq < d2) {
                    d2 = d2Sq; i2 = i
                }
            }
            // ★★★ 2026-10-15（用户报障）：**把「折线斜坡」换成「渐变过渡」**
            //
            // 用户逐字：「**碎色流光色块间的过渡做得不够好，要像多色流光那样渐变过渡。**」
            //
            // ## 旧算式的问题（用户这句话的技术内容）
            //
            // 旧式 `t = 0.5 * (1 − clamp(dd / soft))` —— `clamp` 是**折线**：
            // `dd` 从 `soft` 长到 `2 × soft` 的过程中 `t` 恒为 0（细胞内部），
            // 到 `dd = soft` 才开始动 ⇒ ⇒ **过渡带两端是硬拐**，观感"一坨块、一坨缝"。
            //
            // ⇒ ★ 多色流光之所以"衔接好"，是因为 `LinearGradientPaint` 的插值是
            //   **S 曲线 / 跨全程连续**的：任何两个相邻停靠点之间都在平滑插值，
            //   **不存在"内部恒定 → 边界突变"的两段式**。
            //
            // ## 新算式：把 `dd` 直接归一化成 `[0, 1]` 的**全连续**权重
            //
            // `dd ∈ [0, softPx × 2]`（0 = 边界上；越大越远离边界）
            // ⇒ `t = 0.5 × (1 − dd / (2 × soft))`，**全程无 clamp、无平台**：
            //   · `dd = 0`   ⇒ `t = 0.5`（两色各半 —— 边界正中间）
            //   · `dd = soft` ⇒ `t = 0.25`
            //   · `dd = 2soft`⇒ `t = 0`（**恰好插值完了**，再往外仍是 0）
            // ⇒ ★ 与旧式的差别：**旧式在 `dd ∈ [soft, 2soft]` 整段都是恒定色**，
            //   新式把这段也纳入渐变 ⇒ 过渡区**扩大一倍且首尾平滑** ✓
            val dd = Math.sqrt(d2.toDouble()).toFloat() - Math.sqrt(d1.toDouble()).toFloat()
            val softPx = (CAMO_CELL_SOFT * minOf(cellW, cellH)).coerceAtLeast(0.5f)
            // ★ `coerceIn` 仍在，但只作用在**尾端**（`dd > 2 × soft` 时定为 0）——
            //   ⚠️ **不能省**：它让"离边界足够远"的像素**恒是细胞纯色**
            //   （否则整幅贴图都是渐变色，"碎色块"就糊成一片了）。
            val t = 0.5f * (1f - (dd / (2f * softPx)).coerceIn(0f, 1f))
            val rgb = if (t <= 0f) cellColors[i1] else mixRgb(cellColors[i1], cellColors[i2], t)
            out[rowBase + x] = (a shl 24) or (rgb and 0xFFFFFF)
        }
    }

    // ── ★★★ 2026-10-15（第二轮）：**整体模糊** —— 把色块边界化开 ─────────────
    //
    // 用户逐字：「**色块边界还是不够模糊，要那种边界不明显的朦胧色感。**」
    //
    // ★ 上面那段（细胞 + 两色插值）给的是"**边界仍是一条可辨的过渡带**"；
    //   本段做的是**空间模糊** —— 让每个像素吸收邻域颜色 ⇒ 边界变成"带" ✓
    //   ★ 两者是**互补**的：插值决定"边界两侧是什么色"，模糊决定"边界有多糊"。
    //
    // ⚠️ **水平方向必须环绕**（贴图要左右无缝）；垂直方向**钳制**
    //   （贴图上下就是岛体的边缘，越界取边界像素即可）。
    val radius = when {
        blurRadiusPx >= 0 -> blurRadiusPx
        else -> (CAMO_BLUR_FRACTION * minOf(cellW, cellH)).toInt()
    }
    if (radius <= 0) return out
    var cur = out
    repeat(CAMO_BLUR_PASSES) { cur = boxBlurArgb(cur, w, h, radius) }
    return cur
}

/**
 * **可分离 box 模糊**（先横向、再纵向各一遍）—— 逐通道 ARGB。
 *
 * ## 为什么是"可分离 + box"而不是"高斯"
 *
 * 高斯核是 `O(r²)` 每像素；**可分离 box 是 `O(1)` 每像素**（滑动窗口增量更新）——
 * 对一张 256 × 64 的贴图、半径 6、跑 2 遍，总成本 ≈ `2 × 2 × 16k` 次加减 ⇒
 * 亚毫秒级 ✓ 而**两遍 box** 已足够接近高斯（中心极限定理）✓
 *
 * ## 两个方向的边界处理**不同**（这一点容易写错）
 *
 * | 方向 | 处理 | 为什么 |
 * |---|---|---|
 * | **横向** | **环绕**（`x ± r` 取模） | 贴图要**水平无缝** —— 左边界的模糊必须吃到右边界外的像素，否则接缝处会有一条"没糊到"的竖线 ✗ |
 * | **纵向** | **钳制**（越界取边界像素） | 贴图上下就是**岛体的边缘**，没有"对面"可环绕 |
 *
 * @param radius 半径（px）；`<= 0` 或图太小 ⇒ 原样返回
 */
private fun boxBlurArgb(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius <= 0 || w <= 0 || h <= 0 || src.size < w * h) return src
    // ★ 半径不得超过半边长 —— 否则窗口比图还大，"环绕"会重复累加同一批像素（结果偏色）
    val r = radius.coerceAtMost(maxOf(1, minOf(w, h) / 2))
    if (r <= 0) return src
    val win = 2 * r + 1

    // ── ① 横向（★ 环绕）────────────────────────────────────────────────────
    val tmp = IntArray(w * h)
    for (y in 0 until h) {
        val base = y * w
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = src[base + ((k % w) + w) % w]
            sa += (c ushr 24) and 0xFF; sr += (c ushr 16) and 0xFF
            sg += (c ushr 8) and 0xFF; sb += c and 0xFF
        }
        for (x in 0 until w) {
            tmp[base + x] = ((sa / win) shl 24) or ((sr / win) shl 16) or
                ((sg / win) shl 8) or (sb / win)
            // 滑出 / 滑入（★ 都用环绕索引）
            val co = src[base + (((x - r) % w) + w) % w]
            val ci = src[base + (((x + r + 1) % w) + w) % w]
            sa += ((ci ushr 24) and 0xFF) - ((co ushr 24) and 0xFF)
            sr += ((ci ushr 16) and 0xFF) - ((co ushr 16) and 0xFF)
            sg += ((ci ushr 8) and 0xFF) - ((co ushr 8) and 0xFF)
            sb += (ci and 0xFF) - (co and 0xFF)
        }
    }

    // ── ② 纵向（★ 钳制）────────────────────────────────────────────────────
    val dst = IntArray(w * h)
    for (x in 0 until w) {
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val c = tmp[k.coerceIn(0, h - 1) * w + x]
            sa += (c ushr 24) and 0xFF; sr += (c ushr 16) and 0xFF
            sg += (c ushr 8) and 0xFF; sb += c and 0xFF
        }
        for (y in 0 until h) {
            dst[y * w + x] = ((sa / win) shl 24) or ((sr / win) shl 16) or
                ((sg / win) shl 8) or (sb / win)
            val co = tmp[(y - r).coerceIn(0, h - 1) * w + x]
            val ci = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            sa += ((ci ushr 24) and 0xFF) - ((co ushr 24) and 0xFF)
            sr += ((ci ushr 16) and 0xFF) - ((co ushr 16) and 0xFF)
            sg += ((ci ushr 8) and 0xFF) - ((co ushr 8) and 0xFF)
            sb += (ci and 0xFF) - (co and 0xFF)
        }
    }
    return dst
}

/** 两个 `0xRRGGBB` 之间按 `t`（`0..1`）**逐通道**线性插值。 */
internal fun mixRgb(c1: Int, c2: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    val r = (((c1 ushr 16) and 0xFF) * (1f - f) + ((c2 ushr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
    val g = (((c1 ushr 8) and 0xFF) * (1f - f) + ((c2 ushr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
    val b = ((c1 and 0xFF) * (1f - f) + (c2 and 0xFF) * f).toInt().coerceIn(0, 255)
    return (r shl 16) or (g shl 8) or b
}

// ══════════════════════════════════════════════════════════════════════════════
// 贴图缓存 + `TexturePaint` 出口
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 上一张贴图的键（FNV-1a 64 位，把"尺寸 + 调色板 + alpha"混成一个 `Long`）。
 *
 * ★ 用 `Long` 而不是 `String`：这个键在**每一帧绘制**时都要算一次
 *   （`MaterialLayers.paint` 每帧调用本文件的出口）⇒ 不能有字符串分配。
 */
private var camoCacheKey: Long = Long.MIN_VALUE

/** 上一张贴图。键不变则直接复用。 */
private var camoCacheImage: BufferedImage? = null

/**
 * 释放迷彩贴图缓存（词岛隐藏后调用，见 `IslandWindow.releaseIdleCaches`）。
 *
 * 贴图尺寸 = 岛体宽 × 贴图高（1220×127 ≈ 620 KB）。不释放它会一直跟着插件；
 * 释放后下次绘制重算一次 —— `camoTextureArgb` 是纯函数，成本与首次相同。
 */
fun releaseCamoCache() {
    camoCacheKey = Long.MIN_VALUE
    camoCacheImage = null
}

/** 把一组输入混成缓存键。 */
private fun camoTextureKey(
    w: Int,
    h: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    blurRadiusPx: Int,
): Long {
    var k = -0x340d631b7bdddcdbL // FNV-1a 64 位 offset basis
    fun mix(v: Int) {
        k = (k xor (v.toLong() and 0xFFFFFFFFL)) * 0x100000001b3L
    }
    mix(w); mix(h); mix(alpha); mix(paletteArgb.size); mix(blurRadiusPx)
    for (p in paletteArgb) mix(p)
    return k
}

/**
 * 取（或生成）贴图。**同一组输入只生成一次**。
 *
 * ★ `@Synchronized`：绘制主要在 EDT，但 `MaterialLayers.paint` 也被
 *   **逐像素液态玻璃的异步路**间接使用过 ⇒ 用最便宜的方式把并发排除掉
 *   （这个方法每帧只调一次，锁的开销可忽略）。
 */
@Synchronized
private fun camoImageFor(
    w: Int,
    h: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    blurRadiusPx: Int = CAMO_BLUR_AUTO,
): BufferedImage? {
    val key = camoTextureKey(w, h, paletteArgb, alpha, blurRadiusPx)
    camoCacheImage?.let { if (camoCacheKey == key) return it }
    val argb = camoTextureArgb(
        w, h, CAMO_TEX_COLS, CAMO_TEX_ROWS, paletteArgb, alpha, blurRadiusPx,
    ) ?: return null
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, argb, 0, w)
    camoCacheKey = key
    camoCacheImage = img
    return img
}

/**
 * ★★★ **碎色流光（迷彩档）的唯一出口** —— 返回一支 `TexturePaint`，或 `null`（调用方退回单色）。
 *
 * 与旧的 [flowGradientPaint] **同接口、同几何入参**，只是笔刷从"一维渐变"换成
 * "二维贴图平铺" ⇒ 调用点的改动只有函数名 ✓
 *
 * ## 锚点即相位
 *
 * `TexturePaint` 的锚点矩形决定"图像从哪里开始平铺"。
 * ⇒ 把锚点沿 x 平移 `phase × CAMO_TEXTURE_W` 就等于**图案水平移动一个周期** ✓
 * ⇒ ★ **不重建贴图**（只新建一个轻量对象）⇒ 每帧成本恒定，与贴图大小无关 ✓
 *
 * ⚠️ `phase` 的取模与旧实现**逐字相同**（`((p % 1) + 1) % 1`）——
 *   任意输入（负、越界、`NaN`）都落 `[0, 1)` ⇒ 不越界、不跳变 ✓
 *
 * ## 三种 `null`（都对应"退回单色"）
 *
 * | 条件 | 为什么 |
 * |---|---|
 * | `paletteArgb.size < 2` | 一个色构不出多色迷彩 |
 * | `w`/`h` 非有限或 ≤ 0 | 跨度退化 |
 * | 贴图生成失败（尺寸非法） | 同 `w/h` 那条 |
 *
 * @param x / y / w / h 岛体形状的包围盒（窗口坐标）
 * @param islandHeightPx 岛体高度（px）—— 决定贴图高度分档（**不是** `h`：
 *   `h` 可能是动画中间值，而这里要的是"稳定的高度量纲"；两者在当前调用点相等，
 *   但分开传让语义明确）
 * @param phase 流光相位（`0..1` = 一个完整周期）
 */
fun camoTexturePaint(
    x: Double, y: Double, w: Double, h: Double,
    islandHeightPx: Int,
    paletteArgb: List<Int>,
    alpha: Int,
    phase: Float = 0f,
    /**
     * ★★★ 2026-10-15（用户报障）：**贴图的整体模糊半径**（px）。
     *
     * ★ 用户逐字：「**色块边界还是不够模糊，要那种边界不明显的朦胧色感**」。
     * ⇒ 本参数向探针开放，为的是能做**判别力自证**
     *   （`0` ⇒ 不模糊 ⇒ “边界明显”的反面样本）。
     * ★ 生产路径不传（用 `CAMO_BLUR_AUTO`）。
     */
    blurRadiusPx: Int = CAMO_BLUR_AUTO,
): TexturePaint? {
    if (!w.isFinite() || !h.isFinite()) return null
    if (!(w > 0.0) || !(h > 0.0)) return null
    if (paletteArgb.size < 2) return null
    val texH = camoTextureHeightFor(islandHeightPx)
    val img = camoImageFor(CAMO_TEXTURE_W, texH, paletteArgb, alpha, blurRadiusPx) ?: return null
    val ph = if (phase.isFinite()) ((phase % 1f) + 1f) % 1f else 0f
    val anchorX = x - ph.toDouble() * CAMO_TEXTURE_W
    return TexturePaint(
        img,
        Rectangle2D.Double(anchorX, y, CAMO_TEXTURE_W.toDouble(), texH.toDouble()),
    )
}
