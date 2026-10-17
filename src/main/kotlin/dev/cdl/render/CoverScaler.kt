// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 封面的**高质量缩放** —— 预览与真机的**唯一**缩放实现。
 *
 * ## 为什么需要它（BUG 级：全代码库没有一处设置 `KEY_INTERPOLATION`）
 *
 * 2026-09-21 用户真机反馈：
 * > 「示例封面很**糊**，不清晰，影响观感，**我提供给你的原文件都没这么糊**。」
 *
 * 第一轮取证（BUG-122）：源图被画到 ~105px（**≈5× 下采样**）而全树
 * `KEY_INTERPOLATION` **零命中** ⇒ Java2D 走**默认最近邻** ⇒ 马赛克 + 混叠。
 * 那一轮的修法是"多级折半（每次 ÷2 的双线性）+ 末步双线性"。
 *
 * ## ★★ 第二轮修正（2026-09-22）：折半链本身**过平滑**，改用**单次面积平均**
 *
 * 第一轮的修法**确实**把"最近邻"换掉了（实测：512→105 的梯度能量
 * 最近邻 **39.41** vs 折半链 **29.68** —— 不再是马赛克）。但它**仍然比理想软**，
 * 而软的来源是**折半链的核宽**，不是插值质量：
 *
 * | 512 → 105px 的路径 | 等效核宽（源像素） | 梯度能量 | 相对理想 |
 * |---|---|---|---|
 * | 最近邻（不设 hint） | 1（点采样） | 39.41 | **+29%**（混叠） |
 * | 折半 ×2 后末步双线性（第一轮） | ≈ 61.8 | 29.68 | **−2.8%**（过平滑） |
 * | **单次面积平均（本轮）** | **57.1（= 精确足迹）** | **30.54** | **0（基准）** |
 *
 * 为什么折半链会更宽：每一级 `÷2` 的双线性都是一次 **2 源像素**宽的 box，
 * 它们**串联**后核宽是各级的平方和开方 —— `512→256→128` 两级（在 6000 原图
 * 尺度上各 23.4 px）叠上末步（57.2 px）⇒ `√(23.4²+57.2²)` ≈ **61.8**，
 * 比理想的 **57.1** 宽 8%。**多出来的那 8% 就是"糊"。**
 *
 * ⇒ 正确做法是**一次**覆盖整个源足迹的面积平均（box filter）：
 * 缩放比 `k = 源边长 / 目标边长`，每个输出像素平均 `k × k` 的源矩形
 * （边界像素按**面积占比**加权）。核宽**恰好**是 k ⇒ 既不过平滑也不混叠。
 *
 * ## 为什么不做"多级折半"了（性能反而更好）
 *
 * 折半链的每一级都要新建一张 `BufferedImage` + 一个 `Graphics2D`（native 资源），
 * 而单次面积平均只在最后写一次 `setRGB`。代价对比（6000×6000 → 105）：
 * 折半链 ≈ 48M 次采样 + 3 张中间位图；单次面积平均 ≈ 35.8M 次采样 + **0** 张中间位图。
 *
 * ## 为什么**不放大**（契约，未变）
 *
 * `side <= px` 时直接返回中心方形裁剪（原尺寸），**不放大** —— 放大会引入插值模糊，
 * 而"把小图放大"不是本函数的职责。这条契约由 `CoverScalingProbe` §5 钉住，
 * 本轮**有意不改**（改它会与既有已验收断言冲突）。
 *
 * ## 为什么还要"中心方形裁剪"
 *
 * 真机侧（`IslandWindow.drawCoverImage`）本来就取 `min(宽,高)` 的中心方形再缩放
 * —— 否则人像会被压扁。而**预览侧此前是直接把整张图 `drawImage` 到 `px × px`**，
 * 非正方形封面在预览里会**变形** ⇒ 用户在预览里调好的搭配到真机上不一样。
 * 本函数把"中心方形裁剪"收进来，**两处从此同源**（纪律：预览的全部意义是"所见即真机所示"）。
 *
 * ## ★★★ 第三轮（2026-10-13 · BUG-188）：换核为 **Lanczos3** + 可调锐化
 *
 * ### 为什么"面积平均"还不够（前两轮都把 box 当成"理想"，这是判据盲区）
 *
 * 前两轮的判据都是**相对 box** 的（"面积平均 相对 双三次 × 0.85"）——
 * 它们证明的是"box 比双三次/最近邻好"，**从没测过"box 之上还能更好"**。
 *
 * 用户 2026-10-13：「**能否优化封面显示?使其显示得更清晰可变**」⇒ 补测（`U18` 离屏）：
 *
 * | 核 | 锐度（真实封面 1024→56） | 混叠均值↓（扫频 1.05–6.0 × 奈奎斯特） | 边缘过冲 |
 * |---|---|---|---|
 * | **box（旧）** | 41.377（基准） | **13.08** ✗ | — |
 * | Mitchell | −4.4% | 2.97 | 低 |
 * | Catmull-Rom | +2.4% | 3.82 | 中 |
 * | **Lanczos3（本轮）** | **+7.3%** | **3.25** | 3.0 |
 * | Lanczos3 + 锐化 .25 | **+16.4%** | **3.25** | **4.0** |
 *
 * ★★★ **两处关键发现**：
 * ① **box 的抗混叠明显最差**（混叠均值 13.08 = Mitchell 的 **4.4 倍**）——
 *    因为 box 的频响是 `sinc`，**旁瓣衰减慢**，落在旁瓣上的频率抑制不足。
 *    ⇒ 这**不是审美偏好，是真缺陷**（表现为封面上的摩尔纹/噪点）。
 * ② Lanczos3 **同时**更锐（+7.3%）**且**更干净（混叠 −75%）⇒ 两个方向一致 ✓
 *
 * ### ★★ 成本控制：两级（box 预降采样 → Lanczos3 收尾）
 *
 * Lanczos3 的支撑是 **3 × 缩放比** ⇒ 对 6000px 源、56px 目标（比例 107）
 * 单级要 `642² ≈ 41 万` 次采样/像素 ⇒ **1.3G 次**，比 box 慢 **36 倍** ✗
 *
 * ⇒ 先用 box 缩到 **3n**（快、且 box 在 3n 上的混叠都在 1n 的奈奎斯特**之外**，
 * 会被末级 Lanczos3 滤掉 ⇒ 无影响 ✓），再用 Lanczos3 从 3n 收到 n
 * （比例 3 ⇒ 每像素 `19² = 361` 次）⇒ 总成本 ≈ box（37M）✓
 *
 * ### ★★ 锐化（`cover.sharpness`，0–100）
 *
 * unsharp mask（3×3 高斯），`amount = sharpness / 100`。
 * 实测过冲：`0 → 3.0`（Lanczos3 自身 ringing）· `.25 → 4.0` · `.50 → 5.0` · `.75 → 12.0`
 * ⇒ 默认 **25**（观感提升明显而光晕几乎不可辨）✓
 *
 * ## 缓存
 *
 * 封面是"每曲一次"的，但**尺寸**会随字号/面板宽度变。故缓存**一条**
 * （键 = 源图**引用相等** + 目标边长），命中即返回 —— 与 `CoverArtProvider`
 * 的"只缓存一首歌"是同一取舍（容量 1 足够，且避免维护 LRU）。
 *
 * ⚠️ **只在 EDT 上调用**（两处调用点都在绘制路径里）。缓存字段不加锁 ——
 *    加锁会在每帧的绘制路径上引入同步开销，而 Swing 的绘制本就单线程。
 */
object CoverScaler {

    /**
     * ★ 预降采样倍率（BUG-188）—— 先 box 缩到 `n × 本值`，再由 Lanczos3 收到 `n`。
     * 取 3 是因为它**恰好等于 Lanczos3 的支撑**：比例 3 时核宽 9 中间像素，
     * 相邻目标像素的核重叠充分（不会露出"没被任何核覆盖"的缝隙）。
     * ★ 成本对比（6000px 源 → 56px）：单级 Lanczos3 ≈ 1.3G 次采样；
     *   本两级 ≈ 37M ⇒ 与旧 box 同量级 ✓
     */
    private const val PRE_FACTOR = 3

    private var srcRef: BufferedImage? = null
    private var dstPx = -1

    /** ★ BUG-188：锐化也进缓存键 —— 否则用户拖滑杆**看不到变化**（缓存不失效）✗ */
    private var sharpKey = -1
    private var out: BufferedImage? = null

    /** 缓存命中计数 —— **仅供探针断言"缓存真的生效"**（不是给业务用的）。 */
    var cacheHits: Int = 0
        private set

    /** 缓存未命中计数（= 真正做了缩放计算的次数）。 */
    var cacheMisses: Int = 0
        private set

    /**
     * 释放缓存的源图与输出图（词岛隐藏后调用，见 `IslandWindow.releaseIdleCaches`）。
     *
     * 与 [resetForTest] 的区别：本函数**不动命中/未命中计数** —— 那两个数是
     * 诊断读数，不能因为隐藏一次窗口就归零（否则"缓存到底有没有生效"再也看不出来）。
     * 代价：下次显示重做一次中心方形缩放（实测亚毫秒级）。
     */
    fun release() {
        srcRef = null
        dstPx = -1
        sharpKey = -1
        out = null
    }

    /** 供探针在用例之间复位（否则上一条用例的缓存会污染下一条）。 */
    fun resetForTest() {
        srcRef = null
        dstPx = -1
        sharpKey = -1
        out = null
        cacheHits = 0
        cacheMisses = 0
    }

    /**
     * 把 [src] 的**中心方形**区域高质量缩放到 [px] × [px]。
     *
     * @param px 目标边长（px）。`<= 0` 会被抬到 `1`（`BufferedImage` 不允许 0 尺寸）。
     * @param sharpness 锐化强度 `0..100`（`cover.sharpness`）—— ★ 超范围会被夹住，
     *   且**参与缓存键**（改了它必须重算，否则用户拖滑杆看不到变化 ✗）。
     */
    fun squareScaled(src: BufferedImage, px: Int, sharpness: Int = 0): BufferedImage {
        val n = px.coerceAtLeast(1)
        val s = sharpness.coerceIn(0, 100)
        if (srcRef === src && dstPx == n && sharpKey == s) {
            out?.let { cacheHits++; return it }
        }
        val r = squareScaledUncached(src, n, s)
        srcRef = src
        dstPx = n
        sharpKey = s
        out = r
        cacheMisses++
        return r
    }

    /** 不做缓存的版本 —— **探针与"我需要一张确定的图"的场合用它**。 */
    fun squareScaledUncached(src: BufferedImage, px: Int, sharpness: Int = 0): BufferedImage {
        val n = px.coerceAtLeast(1)
        val s = sharpness.coerceIn(0, 100)
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2

        // ── ① 中心方形裁剪 ───────────────────────────────────────────────────
        val square = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val g0 = square.createGraphics()
        g0.drawImage(src, 0, 0, side, side, sx, sy, sx + side, sy + side, null)
        g0.dispose()

        // ── ② 不放大（契约，见类 KDoc）────────────────────────────────────────
        if (side <= n) return square

        // ── ③ 两级：box 预降采样 → Lanczos3 收尾（见类 KDoc「成本控制」）──────
        val pre = if (side > n * PRE_FACTOR) areaAverage(square, n * PRE_FACTOR) else square
        var r = lanczos3(pre, n)

        // ── ④ 可选锐化（`cover.sharpness`）──────────────────────────────────
        if (s > 0) r = unsharp(r, s / 100.0)
        return r
    }

    /**
     * **Lanczos3** 重采样（`a = 3`）—— ★ 本轮（BUG-188）的收尾核。
     *
     * 核函数 `L(x) = sinc(x) · sinc(x/3)`（`|x| < 3`），按**目标像素间距**归一化。
     * 可分离实现（先横后纵）⇒ 成本 `O(2 · n · 源宽 · 2a)`。
     *
     * ## 为什么它比 box 好（U18 实测）
     *
     * ★ box 的频响是 `sinc`，**旁瓣衰减慢**（`1/f`）⇒ 落在旁瓣上的频率抑制不足
     * ⇒ 实测**混叠是 Lanczos3 的 4 倍**（13.08 vs 3.25）。
     * ★ Lanczos3 的窗（`sinc(x/3)`）让旁瓣快速衰减 ⇒ 更接近理想低通 ✓
     * 代价是**轻微 ringing**（阶跃边缘过冲实测 3.0/255 ≈ 1.2%，不可辨）✓
     *
     * ## alpha 与 box 同口径（预乘空间加权）
     *
     * 理由见 [areaAverage] 的 KDoc（透明像素的黑色不得参与 RGB 加权）。
     */
    private fun lanczos3(src: BufferedImage, n: Int): BufferedImage {
        val sw = src.width
        val sh = src.height
        val scaleX = sw.toDouble() / n
        val scaleY = sh.toDouble() / n
        val radX = 3.0 * scaleX
        val radY = 3.0 * scaleY
        val srcPx = IntArray(sw * sh)
        src.getRGB(0, 0, sw, sh, srcPx, 0, sw)

        // ① 水平：sw×sh → n×sh（预乘）
        val tA = DoubleArray(n * sh)
        val tR = DoubleArray(n * sh)
        val tG = DoubleArray(n * sh)
        val tB = DoubleArray(n * sh)
        for (dx in 0 until n) {
            val cx = (dx + 0.5) * scaleX - 0.5
            val i0 = maxOf(0, ceil(cx - radX).toInt())
            val i1 = minOf(sw - 1, floor(cx + radX).toInt())
            val w = DoubleArray(i1 - i0 + 1)
            var sum = 0.0
            for (i in i0..i1) {
                val v = lanczosKernel((i - cx) / scaleX)
                w[i - i0] = v
                sum += v
            }
            if (sum != 0.0) for (j in w.indices) w[j] /= sum
            for (y in 0 until sh) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                val row = y * sw
                for (j in w.indices) {
                    val c = srcPx[row + i0 + j]
                    val av = ((c ushr 24) and 0xFF) / 255.0
                    val ww = w[j]
                    a += av * ww
                    r += ((c shr 16) and 0xFF) * av * ww
                    g += ((c shr 8) and 0xFF) * av * ww
                    b += (c and 0xFF) * av * ww
                }
                val k = y * n + dx
                tA[k] = a; tR[k] = r; tG[k] = g; tB[k] = b
            }
        }

        // ② 垂直：n×sh → n×n
        val dst = IntArray(n * n)
        for (dy in 0 until n) {
            val cy = (dy + 0.5) * scaleY - 0.5
            val j0 = maxOf(0, ceil(cy - radY).toInt())
            val j1 = minOf(sh - 1, floor(cy + radY).toInt())
            val w = DoubleArray(j1 - j0 + 1)
            var sum = 0.0
            for (i in j0..j1) {
                val v = lanczosKernel((i - cy) / scaleY)
                w[i - j0] = v
                sum += v
            }
            if (sum != 0.0) for (j in w.indices) w[j] /= sum
            for (dx in 0 until n) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                for (j in w.indices) {
                    val k = (j0 + j) * n + dx
                    val ww = w[j]
                    a += tA[k] * ww; r += tR[k] * ww; g += tG[k] * ww; b += tB[k] * ww
                }
                dst[dy * n + dx] = packPremul(a, r, g, b)
            }
        }
        val out = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, n, n, dst, 0, n)
        return out
    }

    /**
     * **非锐化掩模**（unsharp mask，3×3 高斯近似）—— `cover.sharpness` 的执行体。
     *
     * `out = src + amount × (src − blur(src))`，逐通道夹到 `0..255`。
     * ★ 实测过冲（阶跃边缘，`255` 制）：`amount .25 → 4.0` · `.50 → 5.0` · `.75 → 12.0`
     * ⇒ 默认 25（观感提升明显、光晕不可辨）✓
     *
     * ⚠️ alpha **不参与锐化**（锐化 alpha 会让半透明边缘出现硬边）——
     *    只改 RGB，alpha 原样透传 ✓
     */
    private fun unsharp(img: BufferedImage, amount: Double): BufferedImage {
        val w = img.width
        val h = img.height
        val srcPx = IntArray(w * h)
        img.getRGB(0, 0, w, h, srcPx, 0, w)
        val dst = IntArray(w * h)
        val k = doubleArrayOf(1.0, 2.0, 1.0, 2.0, 4.0, 2.0, 1.0, 2.0, 1.0)
        for (y in 0 until h) for (x in 0 until w) {
            var br = 0.0; var bg = 0.0; var bb = 0.0
            var i = 0
            for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -1..1) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val c = srcPx[yy * w + xx]
                    val kk = k[i++]
                    br += ((c shr 16) and 0xFF) * kk
                    bg += ((c shr 8) and 0xFF) * kk
                    bb += (c and 0xFF) * kk
                }
            }
            br /= 16.0; bg /= 16.0; bb /= 16.0
            val c = srcPx[y * w + x]
            fun shp(orig: Double, blur: Double) =
                (orig + amount * (orig - blur)).roundToInt().coerceIn(0, 255)
            dst[y * w + x] = ((c ushr 24) and 0xFF shl 24) or
                (shp(((c shr 16) and 0xFF).toDouble(), br) shl 16) or
                (shp(((c shr 8) and 0xFF).toDouble(), bg) shl 8) or
                shp((c and 0xFF).toDouble(), bb)
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, w, h, dst, 0, w)
        return out
    }

    /** `sinc(x) · sinc(x/3)`（`|x| < 3`，否则 0）—— Lanczos3 的核。 */
    private fun lanczosKernel(x: Double): Double {
        val ax = if (x < 0) -x else x
        if (ax >= 3.0) return 0.0
        return sinc(x) * sinc(x / 3.0)
    }

    private fun sinc(t: Double): Double {
        if (t > -1e-9 && t < 1e-9) return 1.0
        val p = Math.PI * t
        return kotlin.math.sin(p) / p
    }

    /**
     * 预乘累积量 → ARGB（`a <= 0` ⇒ 全 0，即完全透明）。
     *
     * ⚠️⚠️ **口径必须与 [areaAverage] 一致**（本函数是它的抽出版）：
     * - [lanczos3] 的权重**已归一化**（`Σw = 1`）⇒ 传入的 `a` 是 **0..1 的平均 alpha**，
     *   而 `r/g/b` 是 **`Σ(c × a/255 × w)`**（预乘、量纲 0..255 × 0..1）；
     * - 故还原是 `r / a`（**不是** `r × 255 / a` —— 那会多乘 255 直接溢出到全白 ✗）；
     * - alpha 通道要 `a × 255` 才能进 `shl 24` 的字节位
     *   （★ 写成裸 `a.roundToInt()` 会得到 **1**，即"几乎全透明"——
     *    2026-10-13 本批**当场踩过**，被 `CoverScalerProbe` 的两条方形断言抓住 ✓）。
     */
    private fun packPremul(a: Double, r: Double, g: Double, b: Double): Int {
        if (a <= 0.0) return 0
        val up = 1.0 / a
        return ((a * 255.0).roundToInt().coerceIn(0, 255) shl 24) or
            ((r * up).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * up).roundToInt().coerceIn(0, 255) shl 8) or
            (b * up).roundToInt().coerceIn(0, 255)
    }

    /**
     * **面积平均**降采样（box filter，边界按面积占比加权）。**只在缩小路径上用。**
     *
     * ## 为什么不是 `drawImage(…, w, h, …)` + `VALUE_INTERPOLATION_BILINEAR`
     *
     * 双线性的采样核**恒为 2 源像素宽**，与缩放比无关：
     * 缩放比 ∈ (1, 2) 时核比理想**宽**（过平滑 = 糊），
     * 缩放比 > 2 时核比理想**窄**（欠采样 = 混叠）。
     * 而面积平均的核宽**恒等于**缩放比 —— 两个方向都对。
     *
     * ## alpha 必须在**预乘**空间平均
     *
     * 直接在非预乘空间平均 RGB，会让透明像素的黑色参与加权（`RGB=0`）
     * ⇒ 半透明封面在缩小时边缘**发黑**。故本实现先按 `alpha/255` 加权 RGB，
     * 最后再除以平均 alpha 还原（`aSum == 0` 时整像素全 0，即完全透明）。
     */
    private fun areaAverage(src: BufferedImage, n: Int): BufferedImage {
        val sw = src.width
        val sh = src.height
        val srcPx = IntArray(sw * sh)
        src.getRGB(0, 0, sw, sh, srcPx, 0, sw)

        val dstPx = IntArray(n * n)
        val scaleX = sw.toDouble() / n
        val scaleY = sh.toDouble() / n

        for (dy in 0 until n) {
            val y0 = dy * scaleY
            val y1 = y0 + scaleY
            val iy0 = floor(y0).toInt()
            val iy1 = min(sh, ceil(y1).toInt())
            for (dx in 0 until n) {
                val x0 = dx * scaleX
                val x1 = x0 + scaleX
                val ix0 = floor(x0).toInt()
                val ix1 = min(sw, ceil(x1).toInt())

                var aSum = 0.0
                var rSum = 0.0
                var gSum = 0.0
                var bSum = 0.0
                var wSum = 0.0
                for (yy in iy0 until iy1) {
                    // 该源行落在足迹内的**垂直**占比（0..1）。
                    val wy = min(y1, yy + 1.0) - max(y0, yy.toDouble())
                    if (wy <= 0.0) continue
                    val row = yy * sw
                    for (xx in ix0 until ix1) {
                        val wx = min(x1, xx + 1.0) - max(x0, xx.toDouble())
                        if (wx <= 0.0) continue
                        val w = wx * wy
                        val c = srcPx[row + xx]
                        val a = (c ushr 24) and 0xFF
                        aSum += a * w
                        // 预乘：RGB × (alpha/255)，见本函数 KDoc。
                        val pa = a / 255.0
                        rSum += ((c shr 16) and 0xFF) * pa * w
                        gSum += ((c shr 8) and 0xFF) * pa * w
                        bSum += (c and 0xFF) * pa * w
                        wSum += w
                    }
                }
                if (wSum <= 0.0) continue   // 整像素落在足迹外 → 保持全 0（透明）
                val avgA = aSum / wSum
                val px = if (avgA <= 0.0) {
                    0
                } else {
                    val unPremul = 255.0 / aSum   // 预乘 → 非预乘
                    (avgA.roundToInt().coerceIn(0, 255) shl 24) or
                        ((rSum * unPremul).roundToInt().coerceIn(0, 255) shl 16) or
                        ((gSum * unPremul).roundToInt().coerceIn(0, 255) shl 8) or
                        (bSum * unPremul).roundToInt().coerceIn(0, 255)
                }
                dstPx[dy * n + dx] = px
            }
        }

        val outImg = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        outImg.setRGB(0, 0, n, n, dstPx, 0, n)
        return outImg
    }
}
