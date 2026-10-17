// SPDX-License-Identifier: AGPL-3.0-only
//
// ⑱ 时钟 · **字形风格的门面** —— 三种风格（七段 / 点阵 / 像素风）的唯一入口
//
// ══════════════════════════════════════════════════════════════════════════════
// ★★★ 为什么必须有这一层（它是本文件存在的**唯一**理由）
// ══════════════════════════════════════════════════════════════════════════════
//
// 时钟有**两个绘制点**：真机（`window/IslandWindow`）与预览（`settingsui/IslandPreviewPane`）。
// 本项目在"两个绘制点"上**反复**栽过同一形态的跟头：
//
// | 缺陷 | 形态 |
// |---|---|
// | `BUG-149` | 预览的 `MaterialLayers.paint` 调用点**少传 5 个实参** ⇒ 拖滑杆"真机变、预览不变" |
// | `BUG-150` | 同一规则两个落点，**只给一处传了 `arc`** ⇒ 形状项在一条路径上恒不生效 |
// | `BUG-148` | `shapeRoundness` 定为 5 却**从没接线** |
//
// 共同点：**"要在 N 个调用点各改一处"这件事本身就是缺陷源。**
//
// ⇒ 本层的做法：**把"选风格"收敛成一个参数，两个调用点都只调本对象。**
//   新增第四种风格时，两个调用点**一行都不用改** —— 它们只把 `style` 透传下来。
//   ★ 这是"结构上不可能漂"，而不是"靠纪律记得改两处"。
//
// ══════════════════════════════════════════════════════════════════════════════
// ★ 未知档的口径
// ══════════════════════════════════════════════════════════════════════════════
//
// [normalizeStyle] 把**任何**不认识的值（空串 / 老配置 / 拼写错）**归到七段档**。
// ★ 这与 `SpectrumBarCorner` 的"未知值退回默认档"**同一口径**，理由也一样：
//   设置项是从**外部 JSON** 读进来的，**不能假设它一定合法**；
//   而归到"已实现且默认"的那一档，**开箱观感最稳**（而不是变成一块空白）。
// ⚠️ 与"选项数 = 真实状态数"不冲突：**本函数防的是"配置脏"，不是"多给选项"**。

package dev.cdl.render

/**
 * 时钟字形的**风格枚举（wire 值）** + 三个纯函数门面。
 *
 * ## 三种风格
 *
 * | wire 值 | 名称 | 几何实现 | 观感 |
 * |---|---|---|---|
 * | `SEGMENT`（默认） | 七段数码管 | [SevenSegment] | 经典数字钟 |
 * | `DOT` | 点阵 | [DotMatrix]（八边形格） | LED 点阵屏 |
 * | `PIXEL` | 像素风 | [DotMatrix]（正方形格） | 8-bit 位图字 |
 *
 * ★ **默认 `SEGMENT`** —— 它正是"改动前唯一能画的那一档"⇒ **老配置零回归**
 *   （本项目纪律：「默认值要稳」）。
 *
 * ## 门面的三个函数**必须成对使用**
 *
 * [fitDigitWidth] → [digitHeightPx] → [textPolygons] 是一条流水线，
 * 中间**不能**换成别的风格（否则"按点阵算的宽"会被"按七段画的高"用上 ⇒ 文字溢出）。
 * ★ 探针 `ClockGlyphProbe` 断言：三种风格下这条流水线的**每一步都自洽**。
 */
public object ClockGlyphs {

    /** 七段数码管（默认）。 */
    public const val STYLE_SEGMENT: String = "SEGMENT"

    /** 点阵（八边形格，格间有缝）。 */
    public const val STYLE_DOT: String = "DOT"

    /** 像素风（正方形格，格间重叠）。 */
    public const val STYLE_PIXEL: String = "PIXEL"

    /** 全部风格，**顺序 = 设置项下拉里的顺序**（与 `SettingRegistry` 的 `options` 一一对应）。 */
    public val ALL_STYLES: List<String> = listOf(STYLE_SEGMENT, STYLE_DOT, STYLE_PIXEL)

    /** 默认风格 —— 必须是"改动前唯一能画的那一档"。 */
    public const val DEFAULT_STYLE: String = STYLE_SEGMENT

    /**
     * 把外部值**归一化**成三种之一；不认识的一律退回 [DEFAULT_STYLE]。
     * ★ 见文件头「未知档的口径」。
     */
    public fun normalizeStyle(wire: String): String = when (wire) {
        STYLE_SEGMENT, STYLE_DOT, STYLE_PIXEL -> wire
        else -> DEFAULT_STYLE
    }

    /**
     * 在 `availPx` 里放下 `text` 所需的**数字宽**；**放不下 ⇒ `null`**（宁可不画）。
     * ★ 两档的"放不下"判据不同（七段看**段厚**、点阵看**点直径**），但**语义同一条**。
     */
    public fun fitDigitWidth(style: String, text: String, availPx: Float): Float? =
        when (normalizeStyle(style)) {
            STYLE_DOT, STYLE_PIXEL -> DotMatrix.fitDigitWidth(style, text, availPx)
            else -> SevenSegment.fitDigitWidth(text, availPx)
        }

    /**
     * 整串文本的**逻辑宽**（= `数字宽 × 本值`）—— ★★ **2026-10-05 新增**（BUG-155 修复的配套）。
     *
     * ## 为什么必须由门面提供
     *
     * ★ 用户报「**显示的时钟超出词岛边缘，在词岛右侧的空间内不居中**」⇒ 根因是绘制侧
     *   **把 `spectrumAreaLeft` 当左边缘**（左对齐），而**居中需要知道"文本到底多宽"**。
     * ★ 而"文本多宽"**只有各风格自己知道**（七段：4 数字 + 冒号 + 字距；点阵：5 格 × 列数 …）
     *   ⇒ ★ **不能由调用方用 `dw` 硬凑**（那正是"两个落点各算一遍"的形态，迟早漂移）。
     * ⇒ 放进**门面**：与 `fitDigitWidth` / `digitHeightPx` / `textPolygons` **同一条出口** ✓
     *
     * ★ **与 `textPolygons` 同源**：本值算出来的宽度，就是 `textPolygons` 实际铺开的宽度
     *   （各风格的 `totalWidthRatio` 与它们的推进量共用同一组常量）⇒ ★ **"算的"与"画的"不可能漂移** ✓
     */
    public fun totalWidthRatio(style: String, text: String): Float =
        when (normalizeStyle(style)) {
            // ★★ 两档的 `totalWidthRatio` **都不吃 `style`**（格律由 `style` 决定的是**格数/填充比**，
            //   而"宽度比"只与**列数**有关）⇒ ★ **都只传 `text`** ✓
            STYLE_DOT, STYLE_PIXEL -> DotMatrix.totalWidthRatio(text)
            else -> SevenSegment.totalWidthRatio(text)
        }

    /**
     * 该数字宽下的**数字高**（px）。
     * ★ 两档的**自然比例不同**（七段恒 `2.0`；点阵 / 像素风由**填充比反解**，
     *   约 `1.42` / `1.40`）⇒ **必须分流**，不能共用一个常量。
     */
    public fun digitHeightPx(style: String, digitWidthPx: Float): Float =
        when (normalizeStyle(style)) {
            STYLE_DOT, STYLE_PIXEL -> DotMatrix.digitHeightPx(style, digitWidthPx)
            else -> SevenSegment.digitHeightPx(digitWidthPx)
        }

    /**
     * 整串文本的全部字形多边形。
     *
     * @param w 数字宽（px）· `h` 数字高（px，应等于 `digitHeightPx(style, w)`）
     * @param colonVisible 冒号闪烁：灭的那一秒**不画但占位**（整串不左右跳）——
     *   ★ 三种风格**同一语义**（各自的 `textPolygons` 都这么实现）。
     */
    public fun textPolygons(
        style: String,
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        colonVisible: Boolean = true,
    ): List<FloatArray> = when (normalizeStyle(style)) {
        STYLE_DOT, STYLE_PIXEL -> DotMatrix.textPolygons(style, text, x, y, w, h, colonVisible)
        else -> SevenSegment.textPolygons(text, x, y, w, h, colonVisible)
    }
}
