// SPDX-License-Identifier: AGPL-3.0-only
//
// 逐字羽化边界的**几何**（2026-09-21 从 `KaraokeTextRenderer.renderWordTimed` 抽出）。
//
// ## 为什么必须抽出来
//
// `KaraokeTextRenderer` 是 `internal class`，探针（独立编译单元）**看不到**它 ——
// 于是 `renderWordTimed` 的分界几何（caret 插值 → 羽化带 → 渐变端点 → clip）
// 一直是**唯一没有任何断言覆盖的几何**。`KaraokeSweepProbe` 的 KDoc 已把这条
// 写成明确建议。抽出公开纯函数后，几何可以被断言
// （本项目既有做法：`spectrumBandGeometry` / `symmetricBandTop` / `spectrumAlphas`）。
//
// ## 坐标系：本文件的契约是**绝对 x**，唯一的两个相对量由本文件自己换算
//
// 这是本次改动最要紧的一条约束。原实现里：
//
// - `boundaryX`（`:674`）是**相对基线原点**的 —— `caretOf` 返回的就是布局自身坐标系
// - `GradientPaint`（`:697-700`）的端点是**绝对用户空间**
//
// 两者混用 ⇒ 渐变过渡带整体左移了 `baselineX`。而同一文件 `:419-423` 的注释记着
// 作者在**另一条路径**上踩过同一个坑并加了 `AffineTransform.getTranslateInstance`
// —— 是**复发**，不是手滑。
//
// 故本文件只收 `lineStartX`（绝对）与 `frontRel` / `advance`（相对量），
// 由本文件自己把相对量加上 `lineStartX`。**调用方没有机会把相对量当绝对量用** ——
// 这是"结构上不可达"，而不是"纪律上不许"。
package dev.cdl.render

import kotlin.math.min

/** 羽化宽度相对**字号**的上限比例（参考 0.8.9 的视觉参数）。 */
const val FEATHER_FONT_RATIO = 0.45f

/** 羽化宽度相对**当前字宽**的上限比例（参考 0.8.9 的视觉参数）。 */
const val FEATHER_WIDTH_RATIO = 0.6f

/**
 * 羽化宽度下限。
 *
 * ⚠️ **原 KDoc 声称**：「`GradientPaint` 的两端点必须不同，故不能为 0，
 * 否则会抛 `IllegalArgumentException`」。**本项目未复现过该异常** ——
 * `KaraokeFeatherProbe` §8 会**实际构造**零宽 `GradientPaint` 并把结果打出来
 * （是抛、还是画不出，以那次运行为准）。
 *
 * 保留下限的**真实**理由是观感：宽度趋 0 时过渡带退化成一条硬边，
 * 与"羽化"的语义相反。数值本身不改（`0.01`）。
 */
const val MIN_FEATHER = 0.01f

/**
 * 一次羽化绘制的**几何**：渐变两端 + clip 右端，**全部是绝对 x**。
 *
 * 字段名按**契约**命名。实现若返回了相对量，那是实现的缺陷，不是名字的谎话 ——
 * 名字说谎比缺陷更难查（本项目纪律）。
 */
class KaraokeFeatherGeometry internal constructor(
    /** 渐变的**起点**（已唱色一端）绝对 x。 */
    val gradientLeft: Float,
    /** 渐变的**终点**（未唱色一端）绝对 x。 */
    val gradientRight: Float,
    /** clip 的**右端**绝对 x。 */
    val clipRight: Float,
) {
    /** 羽化带的宽度。 */
    val bandWidth: Float get() = gradientRight - gradientLeft
}

/**
 * 羽化宽度（px）：`min(字号 × .45, 字宽 × .6)`，下夹 [MIN_FEATHER]，再乘档位倍率。
 *
 * 逐字与平滑两档的羽化**只差这个倍率**（见 `KaraokeTextRenderer.SMOOTH_FEATHER_SCALE`）。
 */
fun karaokeFeatherPx(refFontSize: Float, segWidth: Float, featherScale: Float): Float =
    min(refFontSize * FEATHER_FONT_RATIO, segWidth * FEATHER_WIDTH_RATIO)
        .coerceAtLeast(MIN_FEATHER) * featherScale

/**
 * 扫描前沿**相对行首**的偏移（与坐标系无关）：在 `[segStartRel, segEndRel]` 内
 * 按 `ratio` 线性插值。
 *
 * ⚠️ **本函数刻意不带"这是第几个字"的信息** —— 行首/行末的补偿是**行级**概念，
 * 由 [karaokeFeatherGeometry] 以行界表达。字级签名（`extendStart` / `extendEnd`
 * 之类）会让实现者能对**每个字**都套一次补偿，那是"每字回跳 `f`"的经典失效模式；
 * 行级签名里根本没有这个自由度。
 *
 * 全相对量 ⇒ 天然与坐标系无关；调用方给它**同一坐标系内**的三个数即可。
 */
fun karaokeSweepFrontRel(segStartRel: Float, segEndRel: Float, ratio: Float): Float =
    segStartRel + (segEndRel - segStartRel) * ratio.coerceIn(0f, 1f)

/**
 * **行程补偿**：把扫描前沿的行程从 `[行首, 行尾]` 线性拉宽到
 * `[行首 − f/2, 行尾 + f/2]`。
 *
 * ## 为什么需要
 *
 * 羽化带是**以 `frontX` 为中心**的（前一半 = "还没唱到但已经淡入"的过渡）。
 * 于是两个端点都不对：
 *
 * | 时刻 | 带的端点 | 后果 |
 * |---|---|---|
 * | `r = 0` | 右端 = `行首 + f/2` | 行首已有 `f/2`（≈0.56 字）**被提前点亮** |
 * | `r = 1` | 左端 = `行尾 − f/2` | 行尾还有 `f/2` **没唱满** |
 *
 * 把行程两端各外扩 `f/2` 之后：`r = 0` 时整条带落在行首**之前**（行内全未唱），
 * `r = 1` 时整条带落在行尾**之后**（行内全已唱）。
 *
 * ## ⚠️ 代价（这是取舍，不是白拿）
 *
 * 行程长度从 `advance` 变成 `advance + f` ⇒ 扫过速率乘 `advance / (advance + f)`。
 * 以 `f = 70.875`、`advance = 378` 算是**慢 15.8%** —— **所有用户都看得见**。
 * 中点不变（`r = 0.5` 时前沿仍落在行中央），故观感是"两端更从容"而非"整体变慢"。
 *
 * ## 为什么不会"每字跳变"
 *
 * 这是一次**线性映射**，输入只有"前沿在行内的相对位置"，**不含"这是第几个字"** ——
 * 逐字时间轴模式下段界是"当前字"，但补偿作用在**行级**坐标上，故字与字之间连续。
 * （`KaraokeFeatherProbe` §6 的变异测试钉住了这一条。）
 */
fun karaokeCompensatedFrontX(
    lineStartX: Float,
    advance: Float,
    frontX: Float,
    feather: Float,
): Float {
    // 退化行：没有"行内位置"可言，原样返回（且不产生 NaN）。
    if (advance <= 0f) return frontX
    val half = feather / 2f
    val scale = (advance + feather) / advance
    return lineStartX - half + (frontX - lineStartX) * scale
}

/**
 * 羽化带几何。
 *
 * @param lineStartX 行首**绝对** x。
 * @param advance    行宽（**相对量**，与坐标系无关）。
 * @param frontRel   扫描前沿**相对行首**的偏移（**未补偿**；补偿在本函数内做）。
 * @param feather    羽化宽度（px）。
 *
 * ## 换算全在本函数内完成
 *
 * `frontAbs = lineStartX + frontRel`。调用方**无法**把 `frontRel` 当绝对量用 ——
 * 这正是把换算收进来的理由：原实现在调用点手工混用两套坐标系，于是渐变端点漏了
 * `baselineX`。现在这个错误**在类型上不可表达**。
 *
 * ## 三个输出各自的夹取规则（**不同**，别一律夹）
 *
 * 1. `gradientLeft` —— 渐变起点（**已唱色**一端），**不夹**
 * 2. `gradientRight` —— 渐变终点（**未唱色**一端），**不夹**。
 *    ⚠️ 夹到行尾等于宣称"行尾恰好全未唱"，而 `r = 1` 时行尾本该**全已唱** ——
 *    那会把末字从 50% 打到 0%，**比不夹更糟**。`r = 1` 的"行内全已唱"由
 *    [karaokeCompensatedFrontX] 把整条带推到行尾**之外**来实现。
 * 3. `clipRight` —— clip 右端，**夹到行尾**。夹它无害（行尾之外没有字形），
 *    且保证 clip 有限。
 */
fun karaokeFeatherGeometry(
    lineStartX: Float,
    advance: Float,
    frontRel: Float,
    feather: Float,
): KaraokeFeatherGeometry {
    val half = feather / 2f
    val lineEndX = lineStartX + advance
    val frontAbs = karaokeCompensatedFrontX(lineStartX, advance, lineStartX + frontRel, feather)
    val left = frontAbs - half
    val right = frontAbs + half
    // 上界是**绝对**量：`lineStartX + advance`，不是 `advance`。
    return KaraokeFeatherGeometry(left, right, min(right, lineEndX))
}
