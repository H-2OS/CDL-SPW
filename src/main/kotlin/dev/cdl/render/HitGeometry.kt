// SPDX-License-Identifier: AGPL-3.0-only

/**
 * **命中域几何** —— `GlyphHitTester` 粗筛那一步的纯函数形态。
 *
 * ## 为什么把它从 `GlyphHitTester` 里提出来（2026-09-19）
 *
 * 起因是给主/副歌词加**字间距**（`main_lyric.letter_spacing_tenth_px`）。
 * 加字距会改变 `TextLayout.advance`，而命中判定读的正是 `advance` ——
 * 于是"字距变了，命中域有没有跟着变"成了一条**必须能证伪**的性质：
 * 若某处命中路径自己用 `FontMetrics.stringWidth` 之类算了一遍宽度，
 * 字距越大，鼠标位置与高亮位置偏得越离谱。
 *
 * 但 `GlyphHitTester` 与 `RenderCache` 都是 `internal`（插件内部实现），
 * **探针看不到**（probe 是独立编译单元，`internal` 不跨模块）。
 * 故按本项目既有做法（见 `SpectrumGeometry.kt` 的"纯函数 + 常量，探针可断言"），
 * 把这一步提成**公开纯函数**，让探针断言的是**生产代码本身**，
 * 而不是在探针里抄一份公式（抄一份等于没测）。
 *
 * ## 坐标系
 *
 * 返回的矩形在**布局坐标系**里 —— 即 `(0, 0)` 对应绘制时的
 * `(baselineX, baselineY)`，与 `TextLayout` 的轮廓、caret 同系。
 * 查询点必须先减去基线原点才能与它比较（见 [GlyphHitTester.hitTest] 的坐标系约定）。
 */

package dev.cdl.render

import java.awt.font.TextLayout

/**
 * 字格粗筛的**余量**（像素），水平与垂直同值。
 *
 * ## 为什么需要它（而不是严格贴着 advance / ascent 边界）
 *
 * 整行的 advance 范围是 `[0, advance]`。但**斜体**与**悬垂字形**的真实墨迹
 * 会越出这个范围（左边距为负、右边距超 advance）。若严格按 advance 判，
 * 那些越出的部分会全部落在粗筛之外 —— 鼠标压在这种字的悬挑上会判定未命中。
 *
 * 取 2 的理由：这是"悬挑"的典型量级（22pt 字下亚像素到 1–2 像素）。
 * 值不宜大：它直接决定"鼠标离文字多远还算在字上"。2 像素时，
 * 用户不会感到"在空白处也触发了控件"。
 *
 * ⚠️ 这个常量**同时**出现在两处语义里：`GlyphHitTester` 的粗筛与
 * `controls.hitPaddingPx` 的外扩起点（后者是设置项，默认 2）。
 * 二者独立 —— 改这里不会改用户的设置默认值。
 */
const val GLYPH_HIT_SLACK = 2f

/**
 * 一行文字的字格命中域（布局坐标系）。
 *
 * 构造器是 `internal`：外部（含探针）只需要**读**它的四个边界，
 * 不需要自己造一个 —— 造出来的矩形没有对应的 `TextLayout`，是无意义的。
 */
class GlyphHitBox internal constructor(
    /** 左边界（通常为负：`-slack`）。 */
    val left: Float,
    /** 上边界（`-ascent - slack`）。 */
    val top: Float,
    /** 右边界（`advance + slack`）—— **字距改变时它必须跟着变**。 */
    val right: Float,
    /** 下边界（`descent + slack`）。 */
    val bottom: Float,
) {

    val width: Float get() = right - left

    val height: Float get() = bottom - top

    /**
     * 点是否落在命中域内（**布局坐标系**，调用方负责先减基线原点）。
     *
     * ⚠️ **四边都是闭区间**。这不是随手写的：原实现是
     * `lx < left || lx > right || ly < top || ly > bottom` → 未命中，
     * 取反后 `left <= lx <= right` 与 `top <= ly <= bottom` 都是**闭**的。
     *
     * 若这里改用 `java.awt.geom.Rectangle2D.contains`，右/下边界会变成**开区间**
     * （`Rectangle2D` 的语义是 `x < maxX`），于是"鼠标正好压在最后一个字右边界上"
     * 会从"命中"变成"不命中" —— 一个字宽以内的差异，但它是**行为变更**，
     * 不是重构。故这里显式写出闭区间，不用 `Rectangle2D`。
     */
    fun accepts(lx: Float, ly: Float): Boolean =
        lx >= left && lx <= right && ly >= top && ly <= bottom

    override fun toString(): String =
        "GlyphHitBox(left=$left, top=$top, right=$right, bottom=$bottom)"
}

/**
 * 按 [TextLayout] 的度量算出字格命中域。**纯函数**。
 *
 * 三条边都来自 `layout` 本身，没有一条来自 `FontMetrics`：
 *
 * | 边 | 来源 | 为什么不能用 FontMetrics |
 * |---|---|---|
 * | `right` | `layout.advance` | **这正是字距能生效的原因** —— 字距加在字体属性上，`advance` 已含字距；`FontMetrics` 不知道字距 |
 * | `top` | `-layout.ascent` | `TextLayout.ascent` 会把"本行实际出现的字形"的最大上伸部算进去（混排 emoji / 大号标点时），`FontMetrics` 给的是整个字体的固定值 |
 * | `bottom` | `layout.descent` | 同上 |
 *
 * @param layout 已测量的布局（来自 `RenderCache.textLayoutFor`，**不要在这里构造**）。
 * @param padding 额外外扩像素（`controls.hitPaddingPx`）。负数按 0 处理。
 */
fun glyphHitBox(layout: TextLayout, padding: Float = 0f): GlyphHitBox {
    val slack = GLYPH_HIT_SLACK + padding.coerceAtLeast(0f)
    return GlyphHitBox(
        left = -slack,
        top = -layout.ascent - slack,
        right = layout.advance + slack,
        bottom = layout.descent + slack,
    )
}

/**
 * **命中测试的探针入口** —— 与 `GlyphHitTester.hitTest` 走**同一条代码路径**。
 *
 * ## 为什么需要它（而不是让探针直接用 `GlyphHitTester`）
 *
 * `GlyphHitTester` 与 `RenderCache` 都是 `internal`，探针（独立编译单元）
 * 看不到它们。把它们提成 `public` 会让两个内部实现类变成对外 API；
 * 而这里只需要"跑一次真实命中判定"，故用一个**自建依赖**的公开入口代替。
 *
 * ## 它与生产路径的差异（如实说明）
 *
 * | | 生产路径 | 本函数 |
 * |---|---|---|
 * | 判定逻辑 | `GlyphHitTester.hitTest` | **同一个**（直接调用） |
 * | 缓存 | 窗口持有的长寿命 `RenderCache` | 每次调用**新建**一个 |
 *
 * 新建的那个只被用来取 `outlineFor`（而它在本路径里**仅作空值闸门**，
 * 几何不取自轮廓），故缓存寿命不影响判定结果 —— 只影响速度。
 *
 * ⚠️ **不要在绘制热路径上用它**：每次调用都会分配一个 `RenderCache` 与一个
 * `GlyphHitTester`。热路径请直接持有 `GlyphHitTester` 实例。
 *
 * @return 命中的字符索引；未命中返回 `null`（语义与 `GlyphHitTester.hitTest` 一致）。
 */
fun glyphHitTest(
    layout: TextLayout,
    text: String,
    x: Float,
    y: Float,
    baselineX: Float,
    baselineY: Float,
    padding: Float = 0f,
): Int? = GlyphHitTester().hitTest(
    RenderCache(), layout, text, x, y, baselineX, baselineY, padding,
)
