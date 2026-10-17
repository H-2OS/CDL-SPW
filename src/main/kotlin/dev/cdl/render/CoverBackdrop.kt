// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import dev.cdl.window.coverFillScale
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.floor

/**
 * 「蒙版封面背景」的**离屏烘焙** —— `background.color_mode = cover_image` 的唯一实现。
 *
 * ## 用户规格（逐字）
 *
 * > 「大致为**歌曲封面作为封面底色**，然后**上覆与从封面取色同源的自动取色
 * >  半透明蒙版**。同时，因为**词岛是长条型**，歌曲封面可能要**进行一定缩放才能
 * >  铺满词岛背景**，同时要**允许用户调整封面角度（0-360 度）**，**旋转后封面自动
 * >  缩放铺满**词岛背景，**不再设调整设置**。**不可拉伸封面原图**。」
 *
 * ## 为什么必须离屏烘焙（**不能每帧现算**）
 *
 * 一次"缩放 + 旋转 + 上蒙版"要新建一张 `W×H` 位图、走一次双线性重采样。
 * 词岛在播放中**每 16~33 ms 重绘一帧**，逐帧现算等于每秒做 30~60 次位图重采样 ——
 * 那是本项目明令禁止的每帧分配热点（`RenderCache` 存在的理由就是消掉它们）。
 *
 * ⇒ 缓存键 = **（源图引用, 目标宽, 目标高, 角度, 蒙版 ARGB）**。三者不变就复用同一张位图。
 *   注意"目标高"含**岛体高度**：控件条展开/收起时岛体会连续长高（`bandGeometry`），
 *   那 180 ms 内会有约 11 帧命中不同的高度 ⇒ 约 11 次烘焙。
 *   **这是有意的**：岛体尺寸变了，"铺满"的定义就变了，必须重算
 *   （用旧尺寸的烘焙图会露出没被盖住的一条）。
 *
 * ## 为什么蒙版**烘焙进位图**、而不是每帧单独 `fill`
 *
 * 两条路都能画对，但分开画时**"背景不透明度"的语义会变形**：
 * 先按 `alpha=A` 画封面、再按 `alpha=A` 填蒙版，最终覆盖度是 `A + A(1−A)`
 * （`A=0.5` 时得 **0.75**）—— 用户拖到 50% 却得到 75%，
 * 属于本项目反复点名反对的「**设置值 ≠ 生效值**」。
 * 烘焙成一张图后，绘制侧只需**一次** `AlphaComposite(alpha = 不透明度)`，语义精确。
 *
 * ## 线程
 *
 * ⚠️ **只在 EDT 上调用**（唯一调用点在 `IslandPanel.paintComponent`）。
 * 缓存字段不加锁 —— 与 [CoverScaler] 同一取舍：加锁会在每帧绘制路径上引入同步开销，
 * 而 Swing 的绘制本就单线程。
 */
object CoverBackdrop {

    /**
     * 蒙版的**不透明度**（0..255）。
     *
     * 为什么是常量而不是一个设置项：用户明确说了「**不再设调整设置**」——
     * 那句话虽然是对"缩放"说的，但蒙版强度属于同一类"算出来的量"：
     * 它的职责是**保证封面之上的歌词仍然可读**，而不是一个审美旋钮。
     * 过淡 → 亮封面上的白字读不出来（本项目的头号可用性红线）；
     * 过浓 → 封面被糊成一块纯色，"封面当底色"这件事就白做了。
     *
     * 取 180（≈71%）：`CoverPalette.darkFrom` 的明度是 0.16（≈41/255），
     * 叠上 71% 之后，即使原封面是纯白（255），合成明度也只有
     * `0.71×41 + 0.29×255 ≈ 103`，白字仍可辨；而封面本身的色相/构图**仍看得见**。
     */
    const val MASK_ALPHA: Int = 180

    /**
     * 铺满系数的**安全余量**（1.002 = 多铺 0.2%）。
     *
     * [coverFillScale] 给出的是**恰好相切**的最小系数：`θ=45°` 且源/目标同比例时，
     * 目标矩形的四个角**正好落在图边上**。而 Java2D 的边缘像素是**抗锯齿**的 ——
     * 相切处那一像素只有部分覆盖，画出来是半透明的 ⇒ 四角会有一条**发丝级的透明缝**。
     *
     * 多铺 0.2%（真机岛高 ~100px ⇒ 0.2px）足以让边界像素被完全覆盖，
     * 而 0.2% 的放大在观感上不可见（远小于"拉伸"那种形变）。
     *
     * ⚠️ 探针断言的是**纯函数** [coverFillScale]（精确相切）；
     *   本常量只在烘焙时叠加，方向是"更大"⇒ 只会更安全，不会破坏那条不变量。
     */
    const val OVERSCAN: Float = 1.002f

    /**
     * 模糊核选择 —— `"box"`：**三遍可分离方框模糊**（旧行为，O(1)/像素）。
     *
     * ★ 保留它（不硬删）是为了"用户/探针要一张确定的旧图"时仍能拿到；
     *   见 [blurBackdrop] 的 KDoc。
     */
    const val KERNEL_BOX: String = "box"

    /**
     * 模糊核选择 —— `"poisson"`：**25-tap 泊松盘**（**默认**）。
     *
     * 25 = 1 个中心 + [POISSON_DISK_POINTS]（12）个盘点 × 2 环（`r` 与 `0.55r`），
     * **等权平均** —— 与参考 `LiquidGlassShader.blurBg()` 的核**逐字一致**
     * （形态 / 权重 / 半径关系见 [poissonPassArr]）。
     *
     * 为什么默认是它：方框核是矩形、有**方向性**，会让折射带显得"方块状"；
     * 泊松盘各向同性、无方向性 ⇒ 更逼近参考实现。详见 [poissonPassArr] 的 KDoc。
     */
    const val KERNEL_POISSON: String = "poisson"

    private var srcRef: BufferedImage? = null
    private var keyW = -1
    private var keyH = -1
    private var keyAngle = Int.MIN_VALUE
    private var keyMask = 0
    // ★ 2026-09-25 第 3 期 L2：模糊参数**也是缓存键的一部分** —— 见 `baked()` 的 KDoc。
    private var keyBlur = 0
    private var keyDownsample = 1
    // ★ B-2：模糊**核**也决定像素结果 ⇒ 必须进缓存键（否则换核后命中旧图）。
    private var keyBlurKernel: String = KERNEL_POISSON
    private var out: BufferedImage? = null

    /** 缓存命中计数 —— **仅供探针断言"缓存真的生效"**（不是给业务用的）。 */
    var cacheHits: Int = 0
        private set

    /** 缓存未命中计数（= 真正做了烘焙的次数）。 */
    var cacheMisses: Int = 0
        private set

    /** 供探针在用例之间复位（否则上一条用例的缓存会污染下一条）。 */
    fun resetForTest() {
        srcRef = null
        keyW = -1
        keyH = -1
        keyAngle = Int.MIN_VALUE
        keyMask = 0
        keyBlur = 0
        keyDownsample = 1
        keyBlurKernel = KERNEL_POISSON
        out = null
        cacheHits = 0
        cacheMisses = 0
    }

    /**
     * 带缓存的入口。键 = （[src] 的**引用相等**, [targetW], [targetH], [angleDeg], [maskArgb]）。
     *
     * 用**引用相等**而不是 `equals`：`BufferedImage` 没有重写 `equals`（比的是同一性），
     * 而 `CoverArtProvider` 对同一首歌返回**同一个实例**（见其缓存契约），
     * 故"换了歌"必然换引用。用引用相等既够用、又是零成本的。
     */
    fun baked(
        src: BufferedImage,
        targetW: Int,
        targetH: Int,
        angleDeg: Int,
        maskArgb: Int,
        blurRadius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {
        if (srcRef === src && keyW == targetW && keyH == targetH &&
            keyAngle == angleDeg && keyMask == maskArgb &&
            keyBlur == blurRadius && keyDownsample == downsample &&
            keyBlurKernel == blurKernel
        ) {
            out?.let { cacheHits++; return it }
        }
        val r = bake(src, targetW, targetH, angleDeg, maskArgb, blurRadius, downsample, blurKernel)
        srcRef = src
        keyW = targetW
        keyH = targetH
        keyAngle = angleDeg
        keyMask = maskArgb
        // ★★ 2026-09-25 第 3 期 L2：**模糊半径与降采样倍率必须进缓存键** ——
        //   不进的话，用户拖「岛内内容模糊」滑杆时 `baked()` 会**命中旧缓存**，
        //   表现为"拖了没反应"。这正是 `B §A-1` R2 说的失效判据里
        //   **唯一一项既有键没覆盖的**（源图 / 尺寸 / 角度 / 蒙版色都已在键里）。
        keyBlur = blurRadius
        keyDownsample = downsample
        keyBlurKernel = blurKernel
        out = r
        cacheMisses++
        return r
    }

    /**
     * ★ **L3（真透视）专用的模糊入口** —— 只做模糊，不做"等比铺满 + 蒙版"。
     *
     * ## 为什么需要它（而不是让 L3 直接调 [bake]）
     *
     * 因为两者的**输入语义完全相反**：
     *
     * | 用途 | 输入 | 期望 |
     * |---|---|---|
     * | [bake]（L2 的 `cover_image` 档） | **封面图**（任意尺寸） | 缩放铺满 + 上覆蒙版色 |
     * | **本方法（L3）** | **已经裁好的岛体截图**（尺寸 = 目标尺寸） | **只模糊，一个像素都不缩放** |
     *
     * 若 L3 去调 [bake]，就必须传一个"角度"与"蒙版色"—— 而截屏**不需要旋转**
     * （它已经是屏上那块区域），也**不该加蒙版**（加蒙版 = 把桌面涂上一层色，
     * 那就不是"透视"了）。硬塞进 [bake] 的形参里会让"哪个档读哪些参数"失去边界，
     * 那正是本项目反复栽的形态。
     *
     * ## 为什么模糊**复用** [blurBackdrop] 而不是另写一份
     *
     * 同一套"降采样 → 3 遍可分离 box blur → 升采样"在 L2 已经过实测与优化
     * （`18-*`：int[] 双缓冲；`24-*` §2：成本接近线性）。
     * **重写一份 = 造第二份实现** —— 两边一旦不同步，表现是"封面糊得和背景不一样"，
     * 而两个方向单独看都"对"（与 `drawnRows` 那条"命中必须来自绘制"同源）。
     *
     * ⚠️ **本方法不缓存**（L3 侧每帧都是新截图，缓存必然 miss ⇒ 只会白占内存）。
     *    L2 的缓存仍在 [baked] 上，两条路互不影响。
     *
     * @param src 岛体尺寸的截图（`TYPE_INT_ARGB` 或 `TYPE_INT_RGB` 都可）
     * @param blurRadius 模糊半径（原分辨率像素）。`<= 0` ⇒ **原样返回**（不引入重采样误差）
     * @param downsample 降采样倍率。L3 传 **4**（`24-*` §3：ds=4 = 43.19 dB 安全）
     * @param blurKernel 模糊核选择（[KERNEL_BOX] / [KERNEL_POISSON]，默认后者）。
     */
    fun blurForBackdrop(
        src: BufferedImage,
        blurRadius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {
        if (blurRadius <= 0) return src
        return blurBackdrop(src, blurRadius, downsample, blurKernel)
    }

    /**
     * **不做缓存**的烘焙 —— 探针与"我要一张确定的图"的场合用它。
     *
     * 产出：一张 `targetW × targetH` 的 `TYPE_INT_ARGB` 位图，
     * 内容是「等比缩放 + 旋转后的 [src]」上覆一层纯色 [maskArgb]。
     * 图内**没有任何透明像素**（前提是 [src] 不透明）—— 这正是"铺满"的定义。
     *
     * @param angleDeg 旋转角度（度）。烘焙时按**顺时针**旋转（Java2D 的 y 轴向下）。
     * @param maskArgb 蒙版色（**含 alpha**，见 [MASK_ALPHA]）
     */
    fun bake(
        src: BufferedImage,
        targetW: Int,
        targetH: Int,
        angleDeg: Int,
        maskArgb: Int,
        blurRadius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {
        // `BufferedImage` 不允许 0 尺寸；退化输入抬到 1 而不是抛异常（渲染路径不抛）。
        val w = targetW.coerceAtLeast(1)
        val h = targetH.coerceAtLeast(1)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // ⚠️ 全树默认是**最近邻**（BUG-122）：不显式设 hint 的话，缩放会出马赛克。
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val sw = src.width.toFloat()
            val sh = src.height.toFloat()
            // 等比：x/y 用**同一个** scale ⇒ 结构上不可能拉伸。
            val scale = coverFillScale(angleDeg.toFloat(), sw, sh, w.toFloat(), h.toFloat()) * OVERSCAN
            val dw = sw * scale
            val dh = sh * scale
            // 缩放因子按 Double 传：`AffineTransform` 只有 `scale(double, double)`
            // （Kotlin 不做 Float→Double 的隐式放宽，传 Float 会编译失败）。
            val kx = (dw / sw).toDouble()
            val ky = (dh / sh).toDouble()

            // 变换 = 平移到中心 · 旋转 · 把"以左上角为原点"的图挪到中心 · 缩放到 dw×dh。
            // 调用顺序即**左乘顺序**（每次调用都是后乘），故读起来就是"从外到内"。
            val t = AffineTransform()
            t.translate(w / 2.0, h / 2.0)
            t.rotate(Math.toRadians(angleDeg.toDouble()))
            t.translate(-dw / 2.0, -dh / 2.0)
            t.scale(kx, ky)
            g.drawImage(src, t, null)

            // 蒙版：整幅盖一层（含旋转后留下的图外区域 —— 那些区域本就被覆盖，
            // 因为 scale 已保证图盖满 w×h；这里"整幅盖"只是把色罩铺匀）。
            g.composite = AlphaComposite.SrcOver
            g.color = Color(maskArgb, true)
            g.fillRect(0, 0, w, h)
        } finally {
            g.dispose()
        }
        // ★ 半径 0 ⇒ **原样返回**：不降采样、不模糊。
        //   这是本项的**第一条硬判据**（"开箱逐像素零变化"）——
        //   "半径 0 也过一遍管线"会引入重采样误差，那是**不该有的变化**。
        if (blurRadius <= 0) return img
        return blurBackdrop(img, blurRadius, downsample, blurKernel)
    }

    /**
     * **共享的离屏模糊管线**：`降采样 → 模糊核 → 升采样`。
     *
     * ## 模糊核可切换（`blurKernel`）
     *
     * - [KERNEL_BOX]（`"box"`）：**3 遍可分离 box blur** —— 旧行为，保留不删。
     *   一遍 box 的核是矩形（有方向性），三遍卷积逼近高斯（中心极限定理），
     *   且可分离（先横后竖）⇒ 复杂度 O(r²) → O(r)，**O(1)/像素**。
     * - [KERNEL_POISSON]（`"poisson"`，**默认**）：**25-tap 泊松盘** gather ——
     *   无方向性，见 [poissonPassArr] 的 KDoc（为什么换 / 出处 / 权重 / 成本）。
     *
     * ★ 两条路**共用**这一段降采样/升采样与 `int[]` 双缓冲管线 —— 不另写第二份
     *   （否则两边一旦不同步，表现是"封面糊得和背景不一样"，而单独看都"对"）。
     *
     * ## ★★ 2026-09-25 优化：全程 `int[]` 双缓冲（`18-*` §3 的根因）
     *
     * 改前：每个 `boxPass` 都 **① `new BufferedImage` 整幅分配
     * ② `getRGB` 整幅解包 ③ `setRGB` 整幅打包** ⇒ **6 次分配 + 12 次整幅像素搬运**；
     * 外加每行/每列 `IntArray(4)` 的隐藏分配（6 × ~154 = 900+ 次）。
     * 改后：**只有进出各一次** `BufferedImage` 往返，卷积全在**两个 `int[]`** 上倒。
     * ★ box 档**数学逐位不变**（同一套滑动窗口 + 同样的 clamp-to-edge 边界处理）
     * ⇒ §B 的 8 条断言**必须仍全绿**。
     *
     * ## 为什么降采样
     *
     * `B §A-1` 的 `blur-compare.png` 证明降采样版**肉眼无差异** —— 模糊本身在抹高频，
     * 在低分辨率上做同一件事，信息损失被随后的升采样插值掩盖。
     * ★ 本项**不动降采样倍率**："泊松盘 @全分辨率"是后续单独一项。
     *
     * @param radius 模糊半径（**原分辨率**下的像素数）。会被折算到降采样后的尺度。
     * @param downsample 降采样倍率（`1` = 不降采样）。档位 `LITE` 传 4、`FULL` 传 1。
     * @param blurKernel 模糊核选择（[KERNEL_BOX] / [KERNEL_POISSON]）。
     */
    private fun blurBackdrop(
        src: BufferedImage,
        radius: Int,
        downsample: Int,
        blurKernel: String = KERNEL_POISSON,
    ): BufferedImage {
        val w = src.width
        val h = src.height
        val ds = downsample.coerceIn(1, 8)
        val sw = (w / ds).coerceAtLeast(1)
        val sh = (h / ds).coerceAtLeast(1)
        // ① 降采样：`drawImage` 做的是**高质量重采样**，这一步保留。
        val small = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().let { g ->
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g.drawImage(src, 0, 0, sw, sh, null)
            } finally {
                g.dispose()
            }
        }
        // ② 取出像素后**全程 int[] 双缓冲**，不再碰 BufferedImage。
        var a = IntArray(sw * sh)
        small.getRGB(0, 0, sw, sh, a, 0, sw)
        var b = IntArray(sw * sh)
        // 半径折算到降采样尺度；至少 1（否则"开了模糊却看不出"）。
        // ★ 用浮点算：box 档再截断成整数（与原 `(radius/ds).toInt().coerceAtLeast(1)` 逐位相同），
        //   泊松档直接吃浮点半径（偏移是分数像素）。
        val rf = (radius.toDouble() / ds).coerceAtLeast(1.0)
        if (blurKernel == KERNEL_BOX) {
            val r = rf.toInt()
            repeat(3) {
                boxPassArr(a, b, sw, sh, r, true)
                boxPassArr(b, a, sw, sh, r, false)
            }
        } else {
            // ★ 默认档：25-tap 泊松盘（单遍 gather，不可分离）。
            poissonPassArr(a, b, sw, sh, rf.toFloat())
        }
        // ③ 升采样回原尺寸。`small` 复用为"模糊后的低分辨率图"（省一次分配）。
        small.setRGB(0, 0, sw, sh, a, 0, sw)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().let { g ->
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g.drawImage(small, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
        }
        return out
    }

    /**
     * 单遍**可分离** box blur，**在 `int[]` 上原地倒**（滑动窗口累加，O(1)/像素）。
     *
     * ★ 累加量用**四个标量**而不是 `IntArray(4)` —— 后者会在**每行/每列**分配一次
     *   （6 遍 × ~154 行 = 900+ 次小分配），是 `18-*` §3 里没写出来的那半个根因。
     *
     * ★ 边界处理是 **clamp-to-edge**（越界索引钳到最近的有效像素）——
     *   与优化前**逐位相同**，故 §B 的断言不受影响。
     */
    private fun boxPassArr(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        r: Int,
        horizontal: Boolean,
    ) {
        val n = 2 * r + 1
        if (horizontal) {
            for (y in 0 until h) {
                val row = y * w
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -r..r) {
                    val c = src[row + i.coerceIn(0, w - 1)]
                    sa += (c ushr 24) and 0xFF
                    sr += (c ushr 16) and 0xFF
                    sg += (c ushr 8) and 0xFF
                    sb += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or
                        ((sg / n) shl 8) or (sb / n)
                    val co = src[row + (x - r).coerceIn(0, w - 1)]
                    sa -= (co ushr 24) and 0xFF
                    sr -= (co ushr 16) and 0xFF
                    sg -= (co ushr 8) and 0xFF
                    sb -= co and 0xFF
                    val ci = src[row + (x + r + 1).coerceIn(0, w - 1)]
                    sa += (ci ushr 24) and 0xFF
                    sr += (ci ushr 16) and 0xFF
                    sg += (ci ushr 8) and 0xFF
                    sb += ci and 0xFF
                }
            }
        } else {
            for (x in 0 until w) {
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -r..r) {
                    val c = src[i.coerceIn(0, h - 1) * w + x]
                    sa += (c ushr 24) and 0xFF
                    sr += (c ushr 16) and 0xFF
                    sg += (c ushr 8) and 0xFF
                    sb += c and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or
                        ((sg / n) shl 8) or (sb / n)
                    val co = src[(y - r).coerceIn(0, h - 1) * w + x]
                    sa -= (co ushr 24) and 0xFF
                    sr -= (co ushr 16) and 0xFF
                    sg -= (co ushr 8) and 0xFF
                    sb -= co and 0xFF
                    val ci = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    sa += (ci ushr 24) and 0xFF
                    sr += (ci ushr 16) and 0xFF
                    sg += (ci ushr 8) and 0xFF
                    sb += ci and 0xFF
                }
            }
        }
    }

    /**
     * 泊松盘的**盘点数** `N`（★ 与参考 `blurBg()` 的 `const int N = 12` 一致）。
     *
     * ⚠️ 它**不是**总 tap 数：参考对每个盘点在 `r` 与 `0.55r` **两环各取一次**，
     * 再加**中心** ⇒ 总 tap 数 = `1 + 2N` = [POISSON_TAPS]。
     */
    private const val POISSON_DISK_POINTS: Int = 12

    /**
     * 泊松盘的**总 tap 数** = 中心(1) + 盘点([POISSON_DISK_POINTS]) × 2 环 = **25**。
     *
     * ★ 与参考 `blurBg()` 的累加器分母 `w` 一致（`w = 1.0` 起、每点 `+2.0`）。
     */
    private const val POISSON_TAPS: Int = 1 + POISSON_DISK_POINTS * 2

    /**
     * 内环半径相对外环的**比例** —— ★ 与参考 `blurBg()` 的
     * `sampleBg(px + disk[i] * radius * 0.55)` 里的 `0.55` 一致。
     */
    private const val POISSON_INNER_RATIO: Float = 0.55f

    /**
     * ★★ **泊松核回退开关** —— 一行切回上一版"仅外环 12 点"的口子。
     *
     * - `true`（**默认**）：**25-tap**（中心 + 双环），对齐参考 `blurBg()`。
     * - `false`：**12-tap**（仅外环 [POISSON_DISK_POINTS] 点、**无中心**），
     *   即本文件引入 25-tap 之前的旧行为（**逐位不变**）。
     *
     * 留着它是为了"要看旧图 / 需要更低成本（48 次取样/像素 vs 100）"时能切回，
     * 详见 [poissonPassArr] 的"回退"一节。
     */
    private const val POISSON_FULL_KERNEL: Boolean = true

    /**
     * 泊松盘的**盘点偏移**（单位圆盘内，未乘半径；共 [POISSON_DISK_POINTS] 个）。
     *
     * ## 出处（★ 逐字取自参考，不是"标准分布"）
     *
     * 参考文件 `Code_20260908143339.html` 的逐字移植版
     * `dev.cdl.render.gpu.LiquidGlassShader` 里 `blurBg()` 的
     * `disk[0..11]` 常量表 —— 12 个点在盘内**非均匀分布**：
     * 9 个落在**外圈**（半径 ≈ 0.87~0.99）、3 个落在**内圈**（半径 ≈ 0.31~0.52）。
     * 故**无需**再补"标准分布"（参考里的具体点已找到）。
     *
     * ⚠️ 这里的"外圈/内圈"是**盘点自身在单位盘内的半径**，与 [poissonPassArr]
     * 里"同一批点在 `r` / `0.55r` 两环各取一次"是两回事，勿混。
     *
     * ## 存放形式
     *
     * 扁平 `FloatArray`（`x,y,x,y,…`）—— **常量表，只在 `CoverBackdrop` 初始化时
     * 分配一次**，不在每次模糊调用里 new（渲染热路径"零分配"要求）。
     */
    private val POISSON_DISK_12: FloatArray = floatArrayOf(
        0.926f, 0.134f,
        -0.708f, 0.592f,
        0.381f, -0.899f,
        -0.943f, -0.282f,
        0.152f, 0.976f,
        -0.284f, -0.941f,
        0.687f, -0.539f,
        -0.534f, 0.813f,
        0.976f, -0.052f,
        -0.161f, -0.261f,
        0.313f, 0.417f,
        -0.437f, -0.118f,
    )

    /**
     * ★★ **泊松盘（Poisson disk）gather 模糊** —— `blurKernel = "poisson"` 时的核。
     *
     * ## ★★ 核的形态（★ 逐字对齐参考 `LiquidGlassShader.blurBg()`）
     *
     * 参考 `blurBg()` 的累加循环（逐字）：
     * ```
     * vec3 acc = sampleBg(px);                            // ← 中心，权重 1
     * float w = 1.0;
     * for (int i = 0; i < N; i++) {
     *   acc += sampleBg(px + disk[i] * radius);          // ← 外环 r
     *   acc += sampleBg(px + disk[i] * radius * 0.55);   // ← 内环 0.55r
     *   w += 2.0;
     * }
     * return acc / w;                                     // ← 等权平均，分母 = 25
     * ```
     * ⇒ ★ **taps = 25** = 1（中心）+ [POISSON_DISK_POINTS]（12）× 2 环；
     * ★ **权重全为 1**（**无权重表**，就是**算术平均**，分母 [POISSON_TAPS] = 25）；
     * ★ **半径关系**：外环 = `radius`、内环 = `radius * [POISSON_INNER_RATIO]`（0.55），
     * 与参考的 `* 0.55` 一致。`radius` 即 [blurBackdrop] 折算到降采样尺度的浮点半径。
     *
     * ## 为什么换（相对 box）
     *
     * 方框核是**矩形**，有**方向性**：横/纵向的阶跃在模糊后会留下"方块状"的
     * 方向性伪影，折射带（本路线的核心观感）上会被放大成可见的方格。
     * 泊松盘的点**在盘内各向同性**（见 [POISSON_DISK_12]）⇒ **无方向性**，
     * 折射带不再呈方块状、更逼近参考实现。
     *
     * ## ★★ 成本口径
     *
     * - box：可分离滑动窗口 ⇒ **O(1)/像素**（与半径无关）。
     * - 本核：**直接 gather，每像素固定 [POISSON_TAPS] = 25 taps**
     *   （每 tap 走一次 [bilinearArgb] = 4 次取样）⇒ 与半径无关，但**常数项远大于 box**：
     *   **25 × 4 = 100 次取样/像素**（旧 12-tap 版为 12 × 4 = 48 ⇒ 本版约 **2.1×**）。
     *   ★ 本函数**只写实现，不测时间**（对照/计时由上层统一做）。
     *
     * ## 边界与取样
     *
     * 与 box 一致的 **clamp-to-edge**（越界坐标钳到最近有效像素，见 [bilinearArgb]）。
     * 取样用**双线性**：偏移是分数像素，最近邻会把小半径的点量化掉
     * （对应参考里 GPU 纹理的 LINEAR 过滤）。
     * ★ 中心 tap 的坐标是整数 ⇒ 直接取 `src[row + x]`（等价于双线性落在整数点，
     * 省掉 4 次取样），与参考的 `sampleBg(px)` 同义。
     *
     * ## 回退
     *
     * ★ [POISSON_FULL_KERNEL] = `false` ⇒ 走**仅外环 12 点、无中心**的旧口径
     * （本文件引入 25-tap 之前的行为，**逐位不变**）⇒ 48 次取样/像素。
     *
     * @param radius 已折算到降采样尺度的半径（浮点，允许分数像素）。
     */
    private fun poissonPassArr(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        radius: Float,
    ) {
        val disk = POISSON_DISK_12
        val points = POISSON_DISK_POINTS
        if (POISSON_FULL_KERNEL) {
            // ★ 25-tap：中心 + 12 点 × 2 环（r / 0.55r），等权平均。见 KDoc。
            val inner = radius * POISSON_INNER_RATIO
            val taps = POISSON_TAPS
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    // 中心 tap：整数坐标 ⇒ 直取原像素（= 参考的 sampleBg(px)），权重 1。
                    val center = src[row + x]
                    var sa = (center ushr 24) and 0xFF
                    var sr = (center ushr 16) and 0xFF
                    var sg = (center ushr 8) and 0xFF
                    var sb = center and 0xFF
                    var i = 0
                    while (i < points * 2) {
                        val co = bilinearArgb(src, w, h, x + disk[i] * radius, y + disk[i + 1] * radius)
                        sa += (co ushr 24) and 0xFF
                        sr += (co ushr 16) and 0xFF
                        sg += (co ushr 8) and 0xFF
                        sb += co and 0xFF
                        val ci = bilinearArgb(src, w, h, x + disk[i] * inner, y + disk[i + 1] * inner)
                        sa += (ci ushr 24) and 0xFF
                        sr += (ci ushr 16) and 0xFF
                        sg += (ci ushr 8) and 0xFF
                        sb += ci and 0xFF
                        i += 2
                    }
                    dst[row + x] = ((sa / taps) shl 24) or ((sr / taps) shl 16) or
                        ((sg / taps) shl 8) or (sb / taps)
                }
            }
        } else {
            // ★ 回退口径：仅外环 12 点、无中心、等权平均（旧行为，逐位不变）。
            val taps = points
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var sa = 0
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    var i = 0
                    while (i < taps * 2) {
                        val c = bilinearArgb(src, w, h, x + disk[i] * radius, y + disk[i + 1] * radius)
                        sa += (c ushr 24) and 0xFF
                        sr += (c ushr 16) and 0xFF
                        sg += (c ushr 8) and 0xFF
                        sb += c and 0xFF
                        i += 2
                    }
                    dst[row + x] = ((sa / taps) shl 24) or ((sr / taps) shl 16) or
                        ((sg / taps) shl 8) or (sb / taps)
                }
            }
        }
    }

    /**
     * 对 `int[]` 位图做一次**双线性**取样（clamp-to-edge），返回打包 ARGB。
     *
     * 与 box 路径的 `coerceIn(0, w-1)` 同一套边界语义。格点用 `kotlin.math.floor` 取
     * （**不能用 `toInt()`**：`fx < 0` 时 `toInt()` 向 0 截断会取错格）。
     * 全程标量、无分配（渲染热路径要求）。
     */
    private fun bilinearArgb(src: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val xa = x0.coerceIn(0, w - 1)
        val xb = (x0 + 1).coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val yb = (y0 + 1).coerceIn(0, h - 1)
        val c00 = src[ya * w + xa]
        val c10 = src[ya * w + xb]
        val c01 = src[yb * w + xa]
        val c11 = src[yb * w + xb]
        val w00 = (1f - tx) * (1f - ty)
        val w10 = tx * (1f - ty)
        val w01 = (1f - tx) * ty
        val w11 = tx * ty
        val a = (((c00 ushr 24) and 0xFF) * w00 + ((c10 ushr 24) and 0xFF) * w10 +
            ((c01 ushr 24) and 0xFF) * w01 + ((c11 ushr 24) and 0xFF) * w11).toInt()
        val r = (((c00 ushr 16) and 0xFF) * w00 + ((c10 ushr 16) and 0xFF) * w10 +
            ((c01 ushr 16) and 0xFF) * w01 + ((c11 ushr 16) and 0xFF) * w11).toInt()
        val g = (((c00 ushr 8) and 0xFF) * w00 + ((c10 ushr 8) and 0xFF) * w10 +
            ((c01 ushr 8) and 0xFF) * w01 + ((c11 ushr 8) and 0xFF) * w11).toInt()
        val b = ((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
            (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
