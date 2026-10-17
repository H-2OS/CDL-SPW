// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

/**
 * ★★★ **液态玻璃的 SDF（有符号距离场）—— 生产两处共用的唯一定义点。**
 *
 * ## 为什么必须抽出来（**不是"顺手重构"**）
 *
 * 2026-10-01 ④b 开工时，**第二个消费者出现了**：
 *
 * | 消费者 | 在哪跑 | 要 SDF 干什么 |
 * |---|---|---|
 * | [MaterialLayers.liquidGlassImage] | **EDT**（逐像素玻璃） | 算法线 → 菲涅耳 / Glare |
 * | [BackdropRefraction.bake] | **采集线程**（10 Hz 背景烘焙） | 算法线 → **折射 UV 偏移** |
 *
 * ★ 两处的**边缘必须落在同一条线上** —— 否则"玻璃的边缘光"与"背景的折射带"会**错开几像素**，
 * 而那是**看得见**的（一条亮带与一条扭曲带不重合）。
 *
 * ★★ 本项目已有纪律：「**不要造第二份实现**」（两份一旦有一份改了，漂移是**静默**的 ——
 * 数字照样算得出来，只是没意义）。故这里把 `liquidGlassImage` 里那份**内联** SDF
 * 原样搬成公开纯函数，**两处都调它**。
 *
 * ## 形状（**照抄业界**，见 `recon/69-*` §3）
 *
 * 圆角**不是圆**，是**超椭圆**（`pow(p.x, n) + pow(p.y, n)`）——
 * 这正是 Liquid Glass 那种"方中带圆"手感的来源。
 *
 * ```
 * 角区（|dx| > -r 且 |dy| > -r）：  sdf = ((|dx'|^n + |dy'|^n)^(1/n)) - r
 * 非角区：                        sdf = min(max(dx,dy),0) + hypot(max(dx,0), max(dy,0))
 * ```
 * 其中 `dx = |x-cx| - hw`、`dy = |y-cy| - hh`、`dx' = |x-cx| - (hw-r)`。
 *
 * ★ **符号约定**：**内部为负、边界为 0、外部为正**。
 * ⚠️ 这与"距离"的直觉相反，但它**决定了菲涅耳的写法**：
 *   源码用 `pow(1 + merged*k + hardness, 5)`，`merged = 0`（边界）时最亮 ⇒
 *   若把符号反过来，边缘光会**跑到形状外面**。
 *
 * ## 纯函数、零分配
 *
 * 本对象**只收 `Double`、只返 `Double`**（[gradient] 写进调用方给的 `DoubleArray`）
 * —— 逐像素路径上**一次对象分配都不做**。
 */
object GlassSdf {

    /**
     * 超椭圆指数 `n` 的默认值。
     *
     * ★ 出处：`LiquidGlassWinUI` 的 `ShapeRoundness` 默认 **5**（范围 2–8），
     * 见 `README.zh-CN.md` 的「形状 (Shape)」表。
     * `n = 2` ⇒ 标准圆角；越大角越方。
     */
    const val DEFAULT_ROUNDNESS = 5.0

    /**
     * 法线（中心差分）的步长（px）。
     *
     * ★ 出处：与 [MaterialLayers.liquidGlassImage] 一直用的值**逐字相同**（`eps = 0.75`）。
     * ⚠️ **不要为了"更精确"调小** —— 太小时 SDF 的浮点差会被放大，
     * 表现为法线**逐像素抖动**（边缘光出现麻点）。
     */
    const val NORMAL_EPS_PX = 0.75

    /**
     * 圆角矩形的 SDF（超椭圆角）。
     *
     * @param x/y 采样点（**形状局部坐标**，形状从 `(0,0)` 起算）
     * @param cx/cy 形状中心
     * @param hw/hh 形状**半**宽 / **半**高
     * @param cornerRadius 圆角半径（px）。`0` ⇒ 直角；**负值按 0 处理**
     * @param roundness 超椭圆指数（见 [DEFAULT_ROUNDNESS]）。**非有限或 < 2 ⇒ 回退 [DEFAULT_ROUNDNESS]**
     * @return 有符号距离（**内负外正**，见对象 KDoc）
     */
    fun roundedRect(
        x: Double, y: Double,
        cx: Double, cy: Double,
        hw: Double, hh: Double,
        cornerRadius: Double,
        roundness: Double,
        /**
         * ★★ **下边圆角半径**（T5，2026-10-02）—— `NaN`（默认）⇒ **与上边同值**（= 改动前行为）。
         *
         * ## 为什么需要它
         *
         * `shape.type = NOTCH` 的词岛是**上大下小**（上边圆角 ≠ 下边圆角）。
         * ★ 而本函数**原先只收一个半径** ⇒ 两个消费者（EDT 逐像素玻璃 · 采集线程折射）
         * 都只能取 `maxOf(上, 下)` ⇒ ★ **`NOTCH` 档下玻璃边缘/折射带与岛体轮廓对不上**（近似）。
         *
         * ## 为什么"按 `y` 相对中心选半径"是**连续**的
         *
         * 半径**只在角区参与运算**（`|x-cx| > hw - r` 且 `|y-cy| > hh - r`）。
         * ★ 在 `y = cy` 附近、若 `|x-cx|` 不够大 ⇒ **根本不在角区** ⇒ `r` 取哪个都一样
         * ⇒ SDF 在该处**连续**。★ 而真正"上下圆弧相接"的位置（`|x-cx|` 大 且 `y ≈ cy`）
         * **真实形状本来就有拐点** ⇒ 那里不连续是**正确的**。
         *
         * ⚠️ 上/下**都 ≤ 0** 时退化为直角矩形（与改动前同）。
         */
        cornerRadiusBottom: Double = Double.NaN,
    ): Double {
        val dx0 = Math.abs(x - cx) - hw
        val dy0 = Math.abs(y - cy) - hh
        // ★★ T5：上/下分别取半径（`NaN` ⇒ 与上边同值 ⇒ **改动前行为**）。
        val rTop = if (cornerRadius > 0.0) cornerRadius else 0.0
        val r = if (cornerRadiusBottom.isNaN()) {
            rTop
        } else {
            val rBot = if (cornerRadiusBottom > 0.0) cornerRadiusBottom else 0.0
            if (y < cy) rTop else rBot
        }
        if (r > 0.0 && dx0 > -r && dy0 > -r) {
            // ★ 超椭圆角：把两个轴向距离各自减去 (hw-r)/(hh-r) 后取 n-范数。
            val n = if (roundness.isFinite() && roundness >= 2.0) roundness else DEFAULT_ROUNDNESS
            val ax = Math.abs(x - cx) - (hw - r)
            val ay = Math.abs(y - cy) - (hh - r)
            val v = Math.pow(Math.pow(ax, n) + Math.pow(ay, n), 1.0 / n)
            return v - r
        }
        // 非角区：标准"矩形外距 + 负内距"公式。
        return Math.min(Math.max(dx0, dy0), 0.0) +
            Math.hypot(Math.max(dx0, 0.0), Math.max(dy0, 0.0))
    }

    /**
     * 便捷重载：形状从 `(0,0)` 起算、尺寸 `w × h`。
     *
     * ★ 两个消费者都用这一支 —— 少一处"中心怎么算"的重复。
     */
    fun of(
        x: Double, y: Double,
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double = DEFAULT_ROUNDNESS,
        /** ★★ T5：下边圆角半径（`NaN` ⇒ 与上边同值 ⇒ **改动前行为**）。见 [roundedRect]。 */
        cornerRadiusBottom: Double = Double.NaN,
    ): Double = roundedRect(
        x, y,
        w * 0.5, h * 0.5,
        w * 0.5, h * 0.5,
        cornerRadius, roundness, cornerRadiusBottom,
    )

    /**
     * SDF 的**中心差分梯度**（**未归一化**）—— 写进 `out`，**不分配对象**。
     *
     * ## 为什么"不归一化"（这是照抄业界，**不是漏了一步**）
     *
     * 业界的 `getNormal` 拿到的法线**在应用前才 `normalize`**，而**梯度长度 `length(normal)`
     * 本身是一个量**：Glare 的最后一步乘的是 `glareAngleFactor * glareGeoFactor * length(normal)`
     * ⇒ 长度被当作"离边界多近"的权重用。**若在这里就归一化，那个权重就丢了。**
     *
     * ⇒ 本函数**故意**返回未归一化的梯度，归一化交给调用方（两处的用法不同）。
     *
     * @param out 长度 ≥ 2 的数组；写出 `out[0] = ∂sdf/∂x`、`out[1] = ∂sdf/∂y`
     * @param eps 步长（见 [NORMAL_EPS_PX]）
     */
    fun gradient(
        x: Double, y: Double,
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double,
        out: DoubleArray,
        eps: Double = NORMAL_EPS_PX,
        /** ★★ T5：下边圆角半径（`NaN` ⇒ 与上边同值 ⇒ **改动前行为**）。见 [roundedRect]。 */
        cornerRadiusBottom: Double = Double.NaN,
    ) {
        out[0] = of(x + eps, y, w, h, cornerRadius, roundness, cornerRadiusBottom) -
            of(x - eps, y, w, h, cornerRadius, roundness, cornerRadiusBottom)
        out[1] = of(x, y + eps, w, h, cornerRadius, roundness, cornerRadiusBottom) -
            of(x, y - eps, w, h, cornerRadius, roundness, cornerRadiusBottom)
    }
}
