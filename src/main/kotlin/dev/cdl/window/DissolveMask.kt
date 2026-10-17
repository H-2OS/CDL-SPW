// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.min

/**
 * ★★★ 2026-10-13（**BUG-187**）：「溶边」的**边缘 alpha 蒙版** —— ★ **唯一出口**。
 *
 * ## 它是什么
 *
 * 一张 `岛体宽 × 岛体高` 的 ARGB 图，其 `alpha(x,y) = clamp(距边界距离 ÷ 带宽, 0, 1)`。
 * ★ 用 `AlphaComposite.DstIn` **一次性**乘到帧上 ⇒ 边缘**连续渐隐**、**外缘 ≈ 0%** ✓
 *
 * ## ★★★ 为什么从「描边环」改成「距离场」（本批的根因）
 *
 * ★ 旧实现（BUG-185/186）用 **28 条 1px 描边环**（`BasicStroke + draw`）擦出斜坡。
 * ★★ 用户报：「**左右两端出现黑色麻点**」⇒ ★ U17 实测（真机几何：胶囊 + `SQUIRCLE` n=2）：
 *
 * | 方案 | **孤立麻点数**（比四邻居都高 >60 的像素） |
 * |---|---|
 * | ★ 旧「描边环」+ `SQUIRCLE`（**真机**） | ★★ **152** ✗ |
 * | 旧「描边环」+ `ARC`（`RoundRectangle2D` 真曲线） | **0** ✓ |
 * | ★★ **本实现（距离场）** | ★★ **0** ✓ |
 *
 * ★★ **根因**：`squirclePath` 是**折线**（`SQUIRCLE_SEGMENTS = 24` 段/角）——
 *   胶囊两端 = **48 段折线逼近半圆** ⇒ ★ 用 **1px 描边**去描这些**短弦**时，
 *   相邻线段的描边轮廓互相拍频 ⇒ **串珠** ⇒ 就是用户看到的「麻点」✗
 *   ★ 这也解释了**为什么只在两端**（折线只在弯曲处；直边上是精确直线）✓
 *
 * ★★★ **判据**：★ **「用'描边'去画一个由折线逼近的曲线，短弦 + 细线宽 = 拍频」** ——
 *   ★ 换更细的线宽或更多段都治不了（弦更短 ⇒ 拍频更密）✗
 *   ⇒ ★ **正确的做法是不描边**：把 alpha 定义成**距离的函数**（逐像素解析）✓
 *   ★ 距离场由**填充**得到（填充是扫描线光栅化，与折线的每段无关）⇒ **结构上不可能串珠** ✓
 *
 * ## 成本
 *
 * ★ 栅格化 + 两趟倒角 ≈ **2 ms**（100k 像素）—— ★ 但**只在几何变化时算一次**（缓存）✓
 * ★★ 稳态每帧只有 **1 次 `drawImage`** ✓（比 BUG-185 的 28 次描边还便宜）
 *
 * ## ★★ 缓存键**由参数机械派生**（不接受调用方传入）
 *
 * ★ 蒙版内容完全由 `(宽, 高, 带宽, 上/下圆角, 角风格, 角阶)` 决定 ⇒ 键就从这些参数拼
 * ⇒ ★★ **不存在"调用方漏传一个维度 ⇒ 蒙版变陈旧"的可能** ✓
 *
 * ## ⚠️ **必须 padding**（★ 这是 U17 抓到的第二个坑）
 *
 * ★ 形状**贴到图像上下边**（岛体高 = 图像高）⇒ 上下方**没有"外部像素"** ⇒
 *   倒角距离会跑到**远处的左右端**去量（U17 实测 `(400,0)` 得 **372** 而非 0.5）✗
 * ⇒ ★ 栅格化时四周留 `带宽 + 2` 的空白，算完再裁回岛体矩形 ✓
 */
object DissolveMask {

    private var cached: BufferedImage? = null
    private var cachedKey: String? = null

    /** 倒角距离的**正交步长权重**（对角 = 4，最后除以 3 ⇒ 单位是像素）。 */
    private const val W_ORTHO = 3.0
    private const val W_DIAG = 4.0

    /**
     * 取（或按需重建）本几何下的溶边蒙版。
     *
     * @param widthPx/heightPx **岛体**尺寸（不是面板尺寸）
     * @param bandPx 过渡带宽度 —— ★ 由 `dev.cdl.settings.dissolveBandPx` 给
     * @param topRadiusPx/bottomRadiusPx 岛体本帧的上下边圆角（与岛体形状**同源**）
     * @param cornerStyle/cornerN 角风格与角阶（与岛体形状**同源**）
     * @return 蒙版（**已就位**：左上角 = 岛体左上角）；输入非法 ⇒ `null`（调用方整档不画）
     */
    fun get(
        widthPx: Int,
        heightPx: Int,
        bandPx: Float,
        topRadiusPx: Float,
        bottomRadiusPx: Float,
        cornerStyle: String,
        cornerN: Int,
    ): BufferedImage? {
        if (widthPx <= 0 || heightPx <= 0) return null
        if (!(bandPx > 0f) || !bandPx.isFinite()) return null
        val key = "$widthPx|$heightPx|$bandPx|$topRadiusPx|$bottomRadiusPx|$cornerStyle|$cornerN"
        cached?.let { if (cachedKey == key) return it }

        // ★ 纵深防御：带 > 半高 ⇒ 上下两条带重叠（且几何退化）⇒ 夹住 ✓
        val band = bandPx.coerceAtMost(heightPx / 2f)
        if (!(band > 0f)) return null

        val pad = (ceil(band).toInt() + 2).coerceAtLeast(2)
        val pw = widthPx + pad * 2
        val ph = heightPx + pad * 2

        // ── ① 栅格化形状：**填充**（★ 不描边 —— 描边才是串珠的来源）──────────
        val cov = BufferedImage(pw, ph, BufferedImage.TYPE_BYTE_GRAY)
        val g = cov.createGraphics()
        try {
            // ★★★ **必须开 AA**（★ 这是 U17 抓到的第三个坑）：
            //   ★ AA 关掉时，形状边缘按"像素中心在不在多边形内"二值化 ——
            //   ★★ 而 `squirclePath` 是**折线**（弦内凹 ~0.06px）⇒ 会在弧上留下**孤立空洞** ✗
            //   ★★★ 而倒角距离会把那些空洞当成"外部" ⇒ 距离被**量短** ⇒ 斜坡被压缩 ✗
            //     （U17 实测：岛体 (28,28) 的真实距离是 28，而倒角算出 **26.6**）
            //   ⇒ ★ 开 AA 后用"面积过半即内部"判定 ⇒ 边缘是**面积多数**集 ⇒ 无孤立空洞 ✓
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.translate(pad.toDouble(), pad.toDouble())
            g.fill(
                islandShapeOf(
                    width = widthPx.toFloat(),
                    height = heightPx.toFloat(),
                    topRadiusPx = topRadiusPx,
                    bottomRadiusPx = bottomRadiusPx,
                    cornerStyle = cornerStyle,
                    cornerN = cornerN,
                ),
            )
        } finally {
            g.dispose()
        }

        // ── ② 倒角距离场（3-4 两趟）—— 单位：像素（最后除以 W_ORTHO）─────────
        val inf = 1e9
        val d = DoubleArray(pw * ph)
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                val inside = (cov.getRGB(x, y) and 0xFF) > 127
                d[y * pw + x] = if (inside) inf else 0.0
            }
        }
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                val i = y * pw + x
                var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + W_ORTHO)
                if (y > 0) v = min(v, d[i - pw] + W_ORTHO)
                if (x > 0 && y > 0) v = min(v, d[i - pw - 1] + W_DIAG)
                if (x < pw - 1 && y > 0) v = min(v, d[i - pw + 1] + W_DIAG)
                d[i] = v
            }
        }
        for (y in ph - 1 downTo 0) {
            for (x in pw - 1 downTo 0) {
                val i = y * pw + x
                var v = d[i]
                if (x < pw - 1) v = min(v, d[i + 1] + W_ORTHO)
                if (y < ph - 1) v = min(v, d[i + pw] + W_ORTHO)
                if (x < pw - 1 && y < ph - 1) v = min(v, d[i + pw + 1] + W_DIAG)
                if (x > 0 && y < ph - 1) v = min(v, d[i + pw - 1] + W_DIAG)
                d[i] = v
            }
        }

        // ── ③ 掩版：alpha = clamp(dist / band) × 255（**颜色无关**，`DstIn` 只看 alpha）──
        val img = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB)
        val raster = img.raster
        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                // ★ 减 0.5：倒角量的是"到最近**外部像素中心**"的距离，
                //   而边界像素的中心到**真实边界**只有 0.5 px
                //   ⇒ 不减的话边界像素会得 alpha ≈ 9（≈ 3.5%）而非 ~4 ✓
                val dist = (d[(y + pad) * pw + (x + pad)] / W_ORTHO - 0.5).coerceAtLeast(0.0)
                val a = ((dist / band).coerceIn(0.0, 1.0) * 255.0).toInt()
                raster.setSample(x, y, 3, a) // band 3 = alpha
            }
        }
        cached = img
        cachedKey = key
        return img
    }

    /** ★ 仅供探针：清缓存（让"改几何 ⇒ 必须重建"可被断言）。 */
    fun clearCacheForProbe() {
        cached = null
        cachedKey = null
    }
}
