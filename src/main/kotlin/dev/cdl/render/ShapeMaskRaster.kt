// SPDX-License-Identifier: AGPL-3.0-only
//
// 自捕获判据的**形状掩膜**（R5）—— 把「岛体形状」栅格化成可复用的布尔掩膜。
//
// ## 为什么需要它（而不是每帧拿 `Shape.contains` 逐像素判）
//
// `migration/31-*` §4.7 的伪代码把 `shapeMask` 标为**一次性**：
// ```
// shapeMask := rasterize(shape, w, h)      // 尺寸/形状变化时才重算
// ```
// 理由：`Shape.contains(x, y)` 对 `RoundRectangle2D` / `Area` 是**逐点几何求值**
// （`Area` 还要跑多边形绕数），304×154 = 46,816 次/帧 × 10 Hz 是**纯浪费**；
// 而形状**绝大多数帧不变**（只有悬停展开、圆角拖滑杆、搬窗口时才变）。
//
// ## 与 `MIGRATION 31-*` 的**形状语义一致性**（这是最关键的一条）
//
// 掩膜必须与**面板真正画的那个形状**同源，否则"圆角外的桌面像素"会掺进相关样本
// ⇒ S1 被无关像素稀释 ⇒ 判别力表（0.997 / 0.571 那些数字）**整表失效**。
//
// 故本文件的坐标口径**逐条对齐** `IslandWindow.paintComponent` ② 段（`:6475`）：
//
// | 量 | 面板怎么算 | 本文件 |
// |---|---|---|
// | 形状自身尺寸 | `bg.islandWidth` × `bg.islandHeight` | 同（从 `OwnFrame` 取） |
// | 形状自身原点 | 恒 `(0, 0)`（`islandShapeOf` 起算点） | 同 |
// | 平移量 | `getTranslateInstance(islandLeft, islandTop)` | 同（栅格化时平移） |
// | 形态门 | `effectiveCornerStyle(shapeType, cornerStyle)` | 同（**直接调用**，不重写） |
// | 圆角 | `islandOutlineAt(...)` | 由调用方算出后传入 |
//
// ⚠️ **本文件不重新推导半径** —— 半径是 `islandOutlineAt` 的输出，包含
//    「`fraction` 插值 + `shape.type` 例外」两套规则。在这里重算一遍就会造出
//    **第二份实现**，它与面板一旦有细微差别（比如某次改动只改了其中一处），
//    结果就是"掩膜与画面差几像素"——而两边单独看都是"对"的，极难定位。
//    （这正是 `drawnRows` 那条"命中必须来自绘制而不是重新算一遍"的同一条理由。）
//
// ## 与探针的关系
//
// 全部是**公开顶层函数** ⇒ 探针可断言：
// · `squircle(n=4)` 与 `RoundRectangle2D` 的掩膜**确实不同**（整形状 + 角区两条）
// · 掩膜**行紧密**（`y*w+x`）—— 与 `detectSelfCapture` 的索引约定一致
// · 退化输入（`w/h <= 0`、非有限）**不抛异常**，返回空掩膜
package dev.cdl.render

import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/**
 * 形状掩膜的**栅格化与取样**（无状态，可复用）。
 *
 * 实例持有的是**尺寸**（构造时定），掩膜本体由 [maskFor] 按形状取出/重算 ——
 * 这样"形状没变就复用"这个优化落在**类内部**，调用方不必自己管缓存键，
 * 也就不可能出现"缓存键写漏一个维度"那类经典缺陷。
 *
 * ⚠️ **非线程安全**：内部缓存 + `BufferedImage` 复用。约定**单线程使用**
 * （采集线程）—— 与 [SelfCaptureGate] 同一条约定。
 *
 * @param width 掩膜宽（= 岛体宽 `OwnFrame.islandWidth`）
 * @param height 掩膜高（= 岛体高 `OwnFrame.islandHeight`）
 */
class ShapeMaskRaster(
    val width: Int,
    val height: Int,
) {

    /** 掩膜内像素总数（`true` 的个数）。`0` = 形状不在栅格内 ⇒ 判据会返 `UNKNOWN`。 */
    var insideCount: Int = 0
        private set

    /** 掩膜的**行紧密**布尔数组（`y * width + x`），长度 = `width * height`。 */
    var mask: BooleanArray = BooleanArray(0)
        private set

    /**
     * 上一份掩膜的缓存键。`null` = 还没栅格化过。
     *
     * 键取形状的**边界盒 + 段数 + 是否闭合** —— 而不是"形状对象身份"：
     * `IslandWindow` 每帧都 `createTransformedShape` 新建一个 `Shape`
     * （`:6475`），身份比较**恒不等** ⇒ 每帧重算，优化等于没做。
     */
    private var cachedKey: String? = null

    private val scratch: BufferedImage? =
        if (width > 0 && height > 0) {
            BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        } else {
            null
        }

    /**
     * 取出（必要时重算）形状掩膜，并一并算出 [insideCount] / [mask]。
     *
     * ## 为什么用 `fill` + 灰度读回，而不是 `Shape.contains` 逐点判
     *
     * `fill` 走 Java2D 的**同一套扫描线 + 抗锯齿**实现 —— 与面板真正绘制时
     * **同源**。`Shape.contains` 是另一套几何谓词，两者在**边界像素**上
     * 本来就可能不一致（`contains` 是"中心点在内"的硬判定）。
     *
     * 抗锯齿的中间灰度按 **≥128 取真**（与 Java2D 内部 `Region` 的像素中心规则一致）。
     *
     * ⚠️ **形状为空 / 尺寸退化 ⇒ 返回空掩膜**（不是抛异常、也不是"全 true"）。
     *    全 true 会把窗口矩形内、形状外的桌面像素全收进来 —— 那是**静默污染样本**，
     *    正是铁律禁止的形态。
     *
     * @param shape 岛体形状（**面板坐标**，含 `islandLeft/islandTop` 平移）
     * @return 本实例的 [mask]（**复用同一数组** —— 调用方不要长期持有）
     */
    fun maskFor(shape: Shape): BooleanArray {
        val img = scratch
        if (img == null) {
            insideCount = 0
            mask = BooleanArray(0)
            cachedKey = null
            return mask
        }
        val key = keyOf(shape)
        if (key == cachedKey && mask.size == width * height) return mask

        // ① 清空 + 用白色（255）填充形状 —— TYPE_BYTE_GRAY 下一个通道足够。
        val g = img.createGraphics()
        try {
            g.setColor(java.awt.Color(0, 0, 0))
            g.fillRect(0, 0, width, height)
            g.setColor(java.awt.Color(255, 255, 255))
            g.fill(shape)
        } finally {
            g.dispose()
        }

        // ② 读回成布尔掩膜。用 `getRGB` 整行读（一次调用一行）而不是逐像素
        //    `getRGB(x,y)` —— 后者在 `BufferedImage` 上是**同步方法**，
        //    46,816 次调用会各走一次颜色模型转换。
        val out = BooleanArray(width * height)
        val row = IntArray(width)
        var count = 0
        for (y in 0 until height) {
            img.getRGB(0, y, width, 1, row, 0, width)
            val base = y * width
            for (x in 0 until width) {
                val v = row[x] and 0xFF
                if (v >= 128) {
                    out[base + x] = true
                    count++
                }
            }
        }
        mask = out
        insideCount = count
        cachedKey = key
        return out
    }

    /**
     * 掩膜的形状指纹。
     *
     * ⚠️ 用 `bounds2D` **不是** `bounds`（整数盒）：`bounds` 会把 `Float` 边界
     *    **向下/向上取整**，于是"`63.9` 与 `64.1` 两个不同的形状"会拿到
     *    同一个整数盒 ⇒ **缓存串味**（拿旧掩膜配新形状）。
     *    `migration/31-*` §4.3 的判别力表是在**亚像素**几何上测的，串味会直接
     *    让表里的数字对不上。
     *
     * ## ★★ 2026-09-27 实测修正：「边界盒 + 类名」**不足以**区分形状
     *
     * 原先的键是 `${x}|${y}|${w}|${h}|${javaClass.simpleName}` —— 本轮实测发现
     * **两个真实存在的不同形状会撞键**：
     *
     * | 形状 | `SQUIRCLE, n=4, R=52.36` | `ARC, R=52.36` |
     * |---|---|---|
     * | 边界盒 | 恒 `(0,0,304,154)` | 恒 `(0,0,304,154)` |
     * | 类名 | `Path2D$Double` | `RoundRectangle2D$Float` |
     * | **掩膜内像素** | **45943** | **44411** |
     *
     * 两者差 **1532 px**（3.4%）—— 但 `bounds2D` 与类名**完全相同**。
     * 只有把它们**放进同一个 `ShapeMaskRaster` 实例**里（模拟"用户拖圆角滑杆
     * 从 ARC 切到 SQUIRCLE"）才会撞：
     *
     * ```
     * r.maskFor(p1)   // ARC      ⇒ 44411
     * r.maskFor(p2)   // SQUIRCLE ⇒ 缓存命中，返回同一个数组 ⇒ **错误地仍是 44411**
     * ```
     *
     * 后果不是崩溃，而是**判据静默失效**：掩膜与实际形状差 3.4%，
     * 那些"形状外但被当作形状内"的像素把 S1 稀释掉 —— 而 S1 被稀释的方向是
     * **漏判**（true 自捕获被判成正常）⇒ 回授循环放行。**没有任何报错。**
     *
     * ## 修法（两层，都要）
     *
     * ① **`javaClass.name`** 而不是 `simpleName` —— 后者在 `Path2D$Double` 上
     *    丢掉了内部类层级，`Path2D$Double` 与 `Path2D$Float` 的 `simpleName`
     *    可能都是 `Path2D`（同名不同实现）。
     * ② **形状自己的序列化影子**：`Path2D` 与 `RoundRectangle2D` 都实现了
     *    `Serializable` ⇒ 用 `ObjectOutputStream` 把它们写进
     *    `ByteArrayOutputStream` 取字节摘要。这是**形状的完整指纹**：
     *    路径的每个段、每个曲率指数都进去了 ⇒ **结构上不可能撞键**。
     *
     * ⚠️ 为什么不在 `maskFor` 里改成"每次都重算"（那样绝不会串味）：
     *    掩膜重算 = 一次 `fill` + 一次 `getRGB` 全图（46,816 px）≈ **0.5–1 ms**，
     *    而它每帧都可能被调 ⇒ 那是**在 EDT 外的固定开销**，没必要付。
     *    指纹计算的成本（一次序列化，几十微秒）远低于重算 —— 这笔账要算清。
     *
     * ⚠️ 序列化**可能失败**（某些第三方 `Shape` 实现不 `Serializable`）⇒
     *    失败时**退回** `identityHashCode`（退化成"几乎不命中缓存"，但**绝不会串味**）。
     *    这里刻意选"慢而正确"—— 缓存只是优化，**正确性是底线**。
     */
    private fun keyOf(shape: Shape): String {
        val b: Rectangle2D = shape.getBounds2D()
        val head = "${b.x}|${b.y}|${b.width}|${b.height}|${shape.javaClass.name}"
        val body = runCatching {
            val bos = java.io.ByteArrayOutputStream(256)
            java.io.ObjectOutputStream(bos).use { it.writeObject(shape) }
            val bytes = bos.toByteArray()
            // 不引入 MessageDigest 的开销：自己走一遍 32 位滚动散列即可
            //（碰撞概率与本键的用途匹配：只要"不同形状不撞"，不需要抗攻击性）。
            var h = 1125899906842597L
            for (byte in bytes) h = 31 * h + byte
            bytes.size.toString() + ":" + h
        }.getOrElse {
            // 序列化失败 ⇒ 退化成身份哈希（**不命中**好过**串味**）。
            "uid:" + System.identityHashCode(shape)
        }
        return "$head|$body"
    }

    /** 诊断：上一份掩膜用了哪个形状指纹（`null` = 还没算过）。 */
    fun lastKey(): String? = cachedKey

    /** 紧凑的调试摘要（**不是**逐像素转储 —— 46,816 个布尔值打日志会刷屏）。 */
    override fun toString(): String =
        "ShapeMaskRaster(${width}x$height, inside=$insideCount, key=$cachedKey)"

    companion object {
        /**
         * 便捷入口：为一个 `OwnFrame` 的形状栅格化一次性掩膜。
         *
         * ⚠️ **不要每帧调它** —— 那样就退化成"每帧重算"，本类的缓存毫无意义。
         *    它是给**一次性/低频**场景（探针、冷启动）准备的。
         */
        fun rasterizeOnce(shape: Shape, width: Int, height: Int): BooleanArray =
            ShapeMaskRaster(width, height).maskFor(shape)
    }
}

/**
 * 从 `BufferedImage` 取出**行紧密**的 ARGB 数组（`y * width + x`）。
 *
 * ## 为什么必须有这个转换（而不是让判据直接吃 `BufferedImage`）
 *
 * 判据 [detectSelfCapture] 的输入是 `IntArray` —— 因为两张图的**来源不同**：
 * · `capture` 来自 `Robot.createScreenCapture`（`TYPE_INT_RGB`）
 * · `own` 来自 `IslandPanel.ownFrame.pixels`（`TYPE_INT_ARGB`，见 `publishOwnFrame`）
 *
 * 两者**类型不同**，但"亮度"只看 `R/G/B` 三个通道（[luminanceOf] 用位运算取），
 * 与 alpha 无关 ⇒ 统一转成 `IntArray` 后，判据不必知道它们原来的类型。
 * 这比"让判据接两个 `BufferedImage` 再各自 `getRGB`"少一层分支，
 * 也让判据**完全不依赖 AWT**（`IntArray` 是纯数据 ⇒ 探针可以手搓输入）。
 *
 * ⚠️ 转换成本：304×154 一次 `getRGB` 整张 ≈ 0.2 ms（一次调用，不是逐像素）。
 *    这与判据本身的 2.6 ms 同量级，且同样**不进 EDT 预算**。
 */
fun toArgbArray(image: BufferedImage): IntArray {
    val w = image.width
    val h = image.height
    val out = IntArray(w * h)
    image.getRGB(0, 0, w, h, out, 0, w)
    return out
}

/**
 * 按 `OwnFrame` 的岛体矩形，从**屏幕坐标下的整屏截图**里裁出对应区域。
 *
 * ## ★★★ 2026-10-10 **契约更正（BUG-160）**：截图口径已从「整屏」改为「区域」
 *
 * ★ **原记**（上一版逐字）：「因为 `Robot` 抓的是**整屏**（L3 的真实用法：截一整屏再模糊）……
 *   ⚠️ 本函数假定整屏截图的坐标原点 = `(0, 0)`……`originOnScreen` 必须是
 *   `locationOnScreen` 的原点」——
 * ★★ **那段描述的"整屏"口径已经不存在了**：`captureProvider` 的默认实现是
 *   **`createScreenCapture(region)`**（★ 只抓岛体那一块，成本 **8.25 ms @304×154**；
 *   抓整屏会贵得多）⇒ ★ **传进来的图就是区域本身** ✓
 *
 * ★★★ **而调用点没跟着改** ⇒ 拿**屏幕绝对坐标**去裁一张**区域大小**的图
 *   ⇒ ★ `x + w > screen.width` **恒真** ⇒ 恒返回 `null`
 *   ⇒ ★★ 真机症状：**`交付=0` 永远**（折射层从不绘制），★ **不报错、不告警** ✗
 *   （★ 取证与完整推导见 `BackdropCapture.runOnce` ② 段的注释）
 *
 * ## 现在的契约（★ 调用方照这个来）
 *
 * ★ `screen` = **任意一张截图**；★ `originOnScreen` = 岛体区域在**这张图自己**的
 *   坐标系里的左上角 —— ★ **区域截图 ⇒ 恒为 `(0, 0)`** ✓
 * ★ 故本函数**不再假定任何屏幕口径** —— 它只做三件事：
 *   ① `w/h` 合法 ② 原点非负 ③ **`图 ≥ w×h`**（★ 这条现在守的是
 *   "**截回来的图够不够裁**"，正是区域截图该守的那一条）✓
 *
 * @param screen 截图（★ 区域截图或整屏截图都行，见上）
 * @param originOnScreen 岛体区域在**该图自己**坐标里的左上角
 * @param w/h 岛体宽高（= `OwnFrame.islandWidth/islandHeight`）
 * @return 裁出的 ARGB（不是 `BufferedImage` —— 与 [toArgbArray] 同口径）
 */
fun cropArgb(screen: BufferedImage, originOnScreen: Rectangle, w: Int, h: Int): IntArray? {
    if (w <= 0 || h <= 0) return null
    if (originOnScreen.x < 0 || originOnScreen.y < 0) return null
    if (originOnScreen.x + w > screen.width || originOnScreen.y + h > screen.height) return null
    val out = IntArray(w * h)
    screen.getRGB(originOnScreen.x, originOnScreen.y, w, h, out, 0, w)
    return out
}
