// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

/**
 * 黑胶样式**外围黑边**（`cover.vinyl`）的**几何唯一真相源**。
 *
 * ## 用户要求（原话）
 *
 * > 「**黑边太窄了**，怎么说也得**和圆形封面的半径的 1/3**，同时为了保证封面协调，
 * > **加了黑边的封面要适当整体缩小一下**。」
 *
 * ## 这套几何在解什么
 *
 * 设**外半径** `R`（= 加黑边之前那个圆形封面的半径，也就是槽位里"看得见的那个圆"的半径）、
 * 黑边宽 `b`、**内容（图片）半径** `r`：
 *
 * ```
 * b = R / 3            ← 用户："和圆形封面的半径的 1/3"
 * r = R − b = 2R / 3   ← 用户："加了黑边的封面要适当整体缩小一下"
 * ```
 *
 * ⚠️ 关键在 `r = R − b` 这一行：**黑边向内占，不是向外扩张**。
 * 旧实现把环画在内容**之外**（`coverSlotWidth = 内容 + 2 × 环宽`），
 * 于是加黑边会让"整个封面"变大、把歌词往右挤；用户要的是**外径不变、内容缩小**。
 *
 * ## 为什么用"外径"当入参（而不是旧实现的"内容边长"）
 *
 * 旧签名 `coverVinylRingWidth(contentSize, vinyl)` 的语义是"内容边长 → 环宽"，
 * 而它把环画在内容之外 ⇒ 外径 = 内容 + 2×环宽 —— **外径随环宽变化**，
 * 于是"环宽"与"外径"互为因果，`coverSlotWidth` 必须再加一项 `2 × ring` 才自洽。
 * 本对象改成"**外径 → 环宽**"：外径是**输入**、恒定不变，环宽与内容都是它的派生量，
 * 单向推导、没有循环定义，`coverSlotWidth` 于是**恒等于外径**。
 *
 * ## 数值对照（`b ≥ r/2` ⟺ `b ≥ R/3`）
 *
 * | 外径 S | 旧实现环宽（0.07×内容，夹 2..6） | 旧 b/r | 本对象环宽 b = S/6 | 内容直径 2r = 2S/3 | 新 b/r |
 * |---|---|---|---|---|---|
 * | 105（默认封面） | 6.00（被上限夹） | 0.114 | **17.50** | 70.00 | **0.500** |
 * | 76（预览）      | 5.32             | 0.140 | **12.67** | 50.67 | **0.500** |
 * | 56（真机上限）  | 3.92             | 0.140 | **9.33**  | 37.33 | **0.500** |
 *
 * ⇒ 旧环宽只有要求的 **23% ~ 28%** —— 用户说"太窄了"是对的。
 *
 * ## ⚠️ 接线状态（2026-09-22 更新 —— 读之前先看这段）
 *
 * 本对象是**目标几何**，但**尚未接线**。同一套几何在本项目里有**三份**副本：
 *
 * | 副本 | 谁在跑 | 现在是什么值 |
 * |---|---|---|
 * | 本对象 `render/CoverRing.kt` | **没人跑**（目标几何） | `b = S/6`（目标值） |
 * | `window/IslandPolicy.kt` `VINYL_RING_OVER_OUTER`（:688） | **真机**（`IslandWindow.drawCover` / `areaLeft` 调它） | **已改成 `1f/6f`** |
 * | `settingsui/IslandPreviewPane.kt` `VINYL_RING_OVER_OUTER`（:863） | 取色器的示例词岛预览 | **已改成 `1f/6f`** |
 *
 * ⇒ **常量这一层已经三份一致**（`CoverScalerProbe` §6b 钉 `CoverRing ≡ IslandPolicy`、
 *   `ColorPickerProbe` ⑯ 钉 `预览 ≡ IslandPolicy`）。剩下**没接线的是绘制**：
 *
 * - `window/IslandWindow.kt` `drawCover()` 仍把图片画在 `size` 上、环画在 `size` **之外**，
 *   而 `leadingCoverSize()` 返回的仍是**内容**边长 ⇒ 三处常量一致但**真机画出的是"外扩"几何**，
 *   环会外溢 `2 × b = 35px`（外径 105 时）压到歌词上。**部署前必须改那几行**（把 `size`
 *   当外径、内容内缩一个环宽），细节见 `CoverScalerProbe` §6b 的 KDoc 与 `IslandPolicy` 的接线提示。
 * - `settingsui/IslandPreviewPane.kt` 的 `drawVinylRing` **已改成"环画在内容形状之外、
 *   内容自身内缩"**（与目标几何一致）。
 *
 * 故本对象由 `CoverScalerProbe` §6 单独钉住（几何公式）+ §6b（与真机策略不漂移）。
 * 真正"把本对象接成唯一真相源"（删掉另外两份）仍是**未完成**事项。
 */
object CoverRing {

    /**
     * 黑边宽 / **外半径**。用户原话：「怎么说也得**和圆形封面的半径的 1/3**」。
     *
     * 取 `1/3` 是**用户明确给的数**，不是手感值 —— 不要"优化"它。
     */
    const val RING_OVER_OUTER_RADIUS = 1f / 3f

    /**
     * 内容半径 / 外半径。由 `r = R − b` 推出：`1 − 1/3 = 2/3`。
     *
     * 写成派生式而不是 `0.6667f`，是为了让"黑边向内占"这条关系**在代码里可见**。
     */
    const val CONTENT_OVER_OUTER_RADIUS = 1f - RING_OVER_OUTER_RADIUS

    /**
     * 黑边宽度（px）—— 由**外径**推导。
     *
     * `b = R / 3 = (S / 2) / 3 = S / 6`。
     *
     * @param outerSize 封面**外径**（px）= 槽位里那个圆的外接正方形边长。
     *   **不是**内容直径（旧实现的入参语义，见类 KDoc）。
     * @param vinyl 是否开启 `cover.vinyl`
     * @return 黑边宽；未开启 / 外径非正 → `0f`（**不抛异常**：渲染路径不抛）
     */
    fun ringWidth(outerSize: Float, vinyl: Boolean): Float =
        if (!vinyl || outerSize <= 0f) 0f else outerSize * RING_OVER_OUTER_RADIUS / 2f

    /**
     * 封面**内容（图片）直径**（px）—— 黑边**向内占**之后剩给图片的那部分。
     *
     * `2r = S − 2b`。开启黑胶后它**必然小于** [outerSize]（用户要的"整体缩小"）；
     * 未开启时**恒等于** [outerSize]（没有黑边就没有内缩）。
     *
     * @return 内容直径；`outerSize <= 0` → `0f`
     */
    fun contentSize(outerSize: Float, vinyl: Boolean): Float =
        if (outerSize <= 0f) 0f else outerSize - 2f * ringWidth(outerSize, vinyl)
}
