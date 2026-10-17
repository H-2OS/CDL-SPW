// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.Point2D

/**
 * **封面态的「水位状光感」进度** —— 本方案唯一的全新绘制原语。
 *
 * 规格来源：`recon/67-封面态（悬浮窗外观）实施方案-2026-09-28.md` §2.4（v5 定案）。
 * 用户原话：
 *
 * > 「就是不是词岛包裹封面吗，包裹的词岛可以采用水位状的光感来表示进度条」
 *
 * ## 一句话语义
 *
 * 把"播放进度"从**一条横条**改成**一个水位**：包裹封面的那一圈词岛壳里，
 * "水"从岛底升到岛顶，**水位线处带光**；**水位高度 = 播放进度**。
 *
 * ## ★★ 载体：一套，不是两套（§2.4.7 A）
 *
 * 用户选的是「① 壳的内边距环（10 px）」，同时又要求「词岛展开时水位线也必须跨过整个词岛」。
 * 这两句**不矛盾，描述的是同一件事**：
 *
 * ```
 * 水位填充 = 落在【整个岛体形状】内的一个矩形（从岛底升到水位线）
 *         │
 *         ├─ 收起态（小岛）：中间被【封面】挡住 ⇒ 可见部分恰好是那一圈环（10 px）
 *         └─ 展开态（长岛）：封面只占左侧 ⇒ 水位自然横跨整个岛体
 * ```
 *
 * ⇒ **实现上只有一套**。★ 千万不要"收起时画环、展开时画全岛"（那是两套，必漂）。
 *
 * ## ★ 为什么本文件**不做 `setClip`**
 *
 * 方案 §2.4.2 的伪代码第 ① 步写的是 `g2.setClip(shape)`。**本项目不这么做** ——
 * 调用方（`IslandWindow` 的内容段）**已经**在 `g2.clip = shape` 之内
 * （见该方法"④ 画歌词、封面与控件"那段），本函数**只消费**那个裁剪。
 *
 * 两条理由：
 * - [MaterialLayers] 文件头已定：`setClip` 是**硬边**（`Region`，像素中心判定）、
 *   **忽略抗锯齿**，会在圆角处留一圈 1px 锯齿（BUG-117/121 同族）；
 * - 调用方用的 `g2.clip = shape` 与 `fill(shape)` 走**同一套 AA 规则** ⇒ 边缘不可能对不齐。
 *
 * ⇒ 本函数**只画，不裁**。这也让它可以被纯函数式地断言（见 [waterFillRect]）。
 *
 * ## ★ 两条防护（从 `PlaybackControls.progressFillPaint` 搬来，§2.4.6 B）
 *
 * 那两条坑对水位**完全同构**，**必须一起搬**：
 *
 * | # | 坑 | 对水位的同构形态 |
 * |---|---|---|
 * | **C-a** | 尾迹长度取 `已播放长度 × GLOW_TAIL_RATIO`（相对**已播放长度**，不是条宽） | 水位很低时按**岛体高**取比例 ⇒ 整段水位全是渐变 ⇒ 看不见 |
 * | **C-b** | 零宽防护：`LinearGradientPaint` 两端点必须不同（相等抛 `IllegalArgumentException`） | 水位 ≈ 0% 或 100% 时渐变必然退化 |
 *
 * ★ C-b 尤其重要：**不搬就会在"刚开始播放"和"快放完"这两个最常见时刻炸掉水位**，
 * 且症状是"**水位整个消失**"（因为异常会让整条绘制被 `runCatching` 跳过），
 * 而不是崩溃 —— 属于最难归因的一类。
 *
 * ## ★ 为什么判据都是**纯函数**（本项目纪律，照 [MaterialLayers.shouldDrawHighlight]）
 *
 * `probe` 是独立编译单元，看不到 `internal` / `private`。而"水位多高"这条
 * 恰恰是**必须离线验证**的东西 —— 它错了不抛异常，只表现为"进度看着不对"。
 * ⇒ 几何推导抽成 [waterFillRect] / [featherSpanPx] 等公开纯函数，探针直接断言。
 */
object CoverWaterLevel {

    /**
     * 水位线以下、光带以上的**羽化尾迹**比例 —— 相对**水位高**，不是岛体高。
     *
     * 与 `PlaybackControls.GLOW_TAIL_RATIO`（0.35）同源同义：尾迹长度取
     * `已播放长度 × ratio`。★ 这里"已播放长度"= **水位高**。
     *
     * 取 0.35 是复用既有档位 —— 水位与 ⑭ 流光进度条用的是**同一套羽化语义**
     * （§2.4.6 B 的纪律：同一语义只允许一个实现点；数值也保持同源，免得将来调一处忘了另一处）。
     */
    public const val WATER_GLOW_TAIL_RATIO: Float = 0.35f

    /**
     * 水位高不足此值（px）时**退回纯色**（零宽防护，C-b）。
     *
     * 与 `PlaybackControls.GLOW_MIN_FILLED` 同义：渐变两端点靠得太近会因浮点相等而退化。
     * 取 2 px —— 小于它时"渐变"在视觉上本就不可辨，画成实心既安全又等价。
     */
    public const val WATER_GLOW_MIN_FILLED_PX: Float = 2f

    /**
     * 水位**最低可见高度**（px）。低于它时**完全不画**。
     *
     * 为什么需要：进度 = 0 时若画一条 0.x px 的线，会在岛底留一条**永远擦不掉的暗线**
     * （抗锯齿的半透明像素），用户会当成"底下有条缝"。⇒ 进度真的为 0 就一个像素都不画。
     * ★ 这与探针 M6 的判据一致：「进度 0 ⇒ 水位高为 0」。
     */
    public const val WATER_MIN_VISIBLE_PX: Float = 0.5f

    /**
     * 波动水面相对**水位高**的最大振幅比例（§2.4.6 D 的观感档）。
     *
     * §2.4.6 D 给的是"振幅 ≤ 环宽/4"，而 §2.4.7 B 把波动改成"**低高度的多条曲线**"
     * 后，"低高度"本身就成了环宽约束下的正解。⇒ 这里改成**相对水位高**取比例更稳
     * （水位高会随播放变化，环宽是常数 10 px）。
     *
     * 取 0.12：在 56 px 的封面岛高下约 6 px，肉眼可辨但不至于变成"晃动的毛边"。
     */
    public const val WATER_WAVE_AMPLITUDE_RATIO: Float = 0.12f

    /** 波动曲线的**采样点数**。§2.4.6 C 给的档位是 16–32，取 24（折中：够平滑、够便宜）。 */
    public const val WATER_WAVE_SAMPLES: Int = 24

    /**
     * 水位填充用的**矩形** —— 纯几何，抽出是为了可断言（探针 M6/M7 直接打它）。
     *
     * ## 定义
     *
     * ```
     * y 从 岛底 升到  baseY + h × (1 − progress)
     * ```
     *
     * 其中 `baseY` = 岛体顶边、`h` = 岛体高。`progress = 0` ⇒ 矩形高为 0（贴岛底）；
     * `progress = 1` ⇒ 矩形高 = `h`（恰好等于岛体高）。
     *
     * ## ★ 为什么不直接用"水位线 y"（而是返回矩形）
     *
     * 「水位线的 y」有**两种参照系**（从顶往下数 / 从底往上数），是本项目
     * "同一规则散落多处"（纪律 11）最爱的现场。返回**矩形**后，
     * "水位到哪了"只有一个表示：`rect.y`（上边缘）与 `rect.height`（高）。
     * 探针要断言"进度 1 ⇒ 高 = 岛体高"时，打的就是 `rect.height`。
     *
     * @param islandLeft 岛体左边缘（面板坐标）
     * @param islandTop 岛体上边缘
     * @param islandWidth 岛体宽
     * @param islandHeight 岛体高
     * @param progress 播放进度，**会被夹到 `0..1`**（宿主可能报出超范围的瞬时值）
     * @return 水位矩形；岛体退化（宽或高 <= 0）时返回 `null`。★ 注意
     *   **进度 0 时返回的仍是"高为 0 的矩形"、不是 `null`** —— 两者语义不同：
     *   `null` = 几何不可信（不画），高 0 = 进度确实为 0（也不画，但原因不同）。
     */
    public fun waterFillRect(
        islandLeft: Float,
        islandTop: Float,
        islandWidth: Float,
        islandHeight: Float,
        progress: Float,
    ): Rectangle2D.Float? {
        // 退化防护用 `!(x > 0)` 而不是 `x <= 0` —— 前者同时兜住 NaN
        //（照 `MaterialLayers.highlightPaint` 的 `if (!(span > 0.0)) return null` 写法）。
        if (!(islandWidth > 0f) || !(islandHeight > 0f)) return null
        val p = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        val filled = islandHeight * p
        // 矩形的**上边缘**：岛底往上量 `filled`。水位越高 ⇒ 上边缘越靠上。
        val top = islandTop + (islandHeight - filled)
        return Rectangle2D.Float(islandLeft, top, islandWidth, filled)
    }

    /**
     * 水位**是否该画** —— 纯函数判据（与几何分开，便于探针单独打）。
     *
     * 不画的两个原因**互不相同、必须分开**（否则将来调一个会误伤另一个）：
     * - 几何不可信（`rect == null`）；
     * - 进度确实为 0（高 < [WATER_MIN_VISIBLE_PX]）—— 见该常量的 KDoc（避免岛底暗线）。
     */
    public fun shouldDraw(rect: Rectangle2D.Float?): Boolean =
        rect != null && rect.height >= WATER_MIN_VISIBLE_PX

    /**
     * 羽化尾迹的**长度**（px）—— 零宽防护的核心（C-a）。
     *
     * `= 水位高 × `[WATER_GLOW_TAIL_RATIO]，**不是** `岛体高 × ratio`。
     *
     * ★ 这正是 C-a 那条坑的定义：按岛体高取比例时，水位很低（刚开始播放）
     * 尾迹会吃掉整段水位 ⇒ **整段全是渐变 ⇒ 看不见**。
     *
     * @return 尾迹长；水位高不足 [WATER_GLOW_MIN_FILLED_PX] 时返回 `-1`（= 调用方应退回纯色）。
     *   ★ 用 `-1` 而不是 `0` 当哨兵：`0` 是合法的"尾迹为零"（无限小水位的极限），
     *   两者语义不同（与项目「不用 `0L` 当哨兵」同一纪律）。
     */
    public fun featherSpanPx(filledPx: Float): Float {
        if (!(filledPx >= WATER_GLOW_MIN_FILLED_PX)) return -1f
        val tail = filledPx * WATER_GLOW_TAIL_RATIO
        // 尾迹吃掉全长 ⇒ 同样退化（另一端没了实色段）。
        if (!(tail > 0f) || tail >= filledPx) return -1f
        return tail
    }

    /**
     * 水位的**填充笔刷**：纵向线性渐变 —— **底部亮、水面最亮**。
     *
     * ## 方向（不可反过来）
     *
     * ```
     * 岛底（起点）─────────▶ 水位线（终点）
     *   较暗                    最亮 ← "水面"在这里
     * ```
     *
     * 光在**水面**上（物理直觉：水面反光）。若把亮端放岛底，看起来像"底下有灯"，
     * 与"水"的隐喻相反。
     *
     * ★ 这条与 `progressFillPaint` 的横向版**语义同构**：那里亮端在播放头（右端），
     * 这里亮端在水位线（上端）—— 都是"**光跟着进度走**"。
     *
     * ## 零宽防护（C-b，必须）
     *
     * 尾迹 >= 全长、或水位太矮时**退回纯色** —— "画成实心"远好于"抛异常"
     * （异常会让整条绘制被 `runCatching` 跳过 ⇒ 用户看到的是"水位消失"）。
     *
     * @param rect 水位矩形（由 [waterFillRect] 给出）
     * @param accent 水色（由封面取色，见 §9-Q）
     * @return 笔刷；退化时返回 [accent] 本身（纯色）。
     */
    public fun waterFillPaint(rect: Rectangle2D.Float, accent: Color): java.awt.Paint {
        val filled = rect.height
        val tail = featherSpanPx(filled)
        // 退化 ⇒ 纯色（C-b）。
        if (tail < 0f) return accent
        // 矩形在屏幕上**极扁**（高 1–2 px）时，渐变轴也退化 —— 同一防护的另一半。
        val span = (filled - tail)
        if (!(span > 0f)) return accent
        val x = (rect.x + rect.width / 2.0).toFloat()
        return LinearGradientPaint(
            // 起点在**尾迹尽头**（近岛底）、终点在**水位线**（近上边缘）。
            Point2D.Float(x, (rect.y + filled).toFloat()),
            Point2D.Float(x, rect.y.toFloat()),
            floatArrayOf(0f, 1f),
            // 分色：0 = 透明（尾迹尽头的"无光"）→ 1 = 不透明的 accent（水面最亮）。
            // ⚠️ **只改 alpha，不改色相** —— 与 `progressFillPaint` 同一条纪律
            //    （改色相会让"光"变成"另一种颜色"，与"用颜色表示进度"撞车）。
            arrayOf(
                Color(accent.red, accent.green, accent.blue, 0),
                accent,
            ),
        )
    }

    /**
     * 水位线的**高光带** —— 从水位线向上淡出（§2.4.2 第 ④ 步）。
     *
     * 与 [MaterialLayers.highlightPaint] 同手法（`LinearGradientPaint` + alpha 淡出），
     * 但**锚在水位线**而非岛顶。
     *
     * ## ★ 为什么**只有这一个**（没有"白色版"）
     *
     * 双色处置（§9-R）要求高光色按本体色明度选（暗底白 / 亮底黑），
     * 而白与黑**只差一个颜色参数**。若写成两个函数（`waterHighlightPaint` 白 +
     * `waterHighlightPaintOfColor` 带色），就是**两份几何**（端点、关键帧、夹取
     * 全都要维护两遍）—— 正是本项目"同一语义只允许一个实现点"要避免的。
     * ⇒ 只留带 [tint] 参数的这一个；白色 = `tint = Color.WHITE`（由 [highlightTintFor] 给出）。
     *
     * ★ D-a 纪律：`LinearGradientPaint` 的**关键帧必须严格递增**（相等也不行）——
     *   本函数把"跨度退化"显式挡掉并返回 `null`，不交给 Java2D 抛异常。
     *
     * @param rect 水位矩形（高光带贴它的**上边缘** = 水位线）
     * @param spanPx 高光带向**上**延伸的长度
     * @param alpha 高光强度（0..255）
     * @param tint 高光色（暗底 ⇒ 白 = 发光；亮底 ⇒ 黑 = 阴影）
     * @return 笔刷；跨度退化或 `alpha <= 0` 时返回 `null`（= 不画）。
     */
    public fun waterHighlightPaintOfColor(
        rect: Rectangle2D.Float,
        spanPx: Float,
        alpha: Int,
        tint: Color,
    ): LinearGradientPaint? {
        if (alpha <= 0) return null
        // 跨度退化 ⇒ 不画（而不是让 Java2D 抛）。
        if (!(spanPx > 0f)) return null
        val x = (rect.x + rect.width / 2.0).toFloat()
        val y = rect.y.toFloat()
        val a = alpha.coerceIn(1, 255)
        return LinearGradientPaint(
            // 水位线 → 其上 spanPx 处。alpha 从 a 淡到 0（向上淡出）。
            Point2D.Float(x, y),
            Point2D.Float(x, y - spanPx),
            floatArrayOf(0f, 1f),
            arrayOf(
                Color(tint.red, tint.green, tint.blue, a),
                Color(tint.red, tint.green, tint.blue, 0),
            ),
        )
    }

    /**
     * 波动水面的**振幅**（px）—— 由水位高推出。
     *
     * §2.4.7 B 定案：波动 = **低高度的多条曲线状频谱音频线**。
     * "低高度"是 10 px 窄环约束下的正解 ⇒ 振幅必须**远小于**环宽。
     *
     * @return 振幅；水位高不足 [WATER_GLOW_MIN_FILLED_PX] 时返回 `0`（= 波动无意义，画直线）。
     */
    public fun waveAmplitudePx(filledPx: Float): Float {
        if (!(filledPx >= WATER_GLOW_MIN_FILLED_PX)) return 0f
        return filledPx * WATER_WAVE_AMPLITUDE_RATIO
    }

    /**
     * 由频谱数据算出一条**波动水位线** —— 水位线的 y 逐采样点偏移。
     *
     * ## ★★ 核心不变量（探针 M7 断言的就是它）
     *
     * **波动的平均线必须与水位高一致** —— 即"波动只允许**围绕**水位线，
     * 不允许**抬高或压低**水位本身"。
     *
     * 实现上靠**对称映射**保证：频谱值 `b ∈ [0,1]` 映射到 `−1..+1`（`2b − 1`），
     * 再乘振幅。⇒ 平均值为 0 时平均线恰在水位线上。
     * ★ 用 `2b − 1` 而不是 `b`（后者均值 ≈ +0.5 ⇒ **整体抬高**，水位会系统性偏高）。
     *
     * @param rect 水位矩形（提供 x 范围与水位线的 y）
     * @param bands 频谱数据（0..1）；`null` / 空 ⇒ **返回直线**（退化，不画波动）
     * @param phase 相位（0..1，由播放时间推进 ⇒ "水在流动"）
     * @return 水面折线的 y 数组（长 [WATER_WAVE_SAMPLES]）；不可波动时返回 `null`
     *   （= 调用方画直线水位）。
     */
    public fun waveLineYs(
        rect: Rectangle2D.Float,
        bands: FloatArray?,
        phase: Float,
    ): FloatArray? {
        if (bands == null || bands.isEmpty()) return null
        val amp = waveAmplitudePx(rect.height)
        if (!(amp > 0f)) return null
        val baseY = rect.y.toFloat()
        val out = FloatArray(WATER_WAVE_SAMPLES)
        val n = bands.size
        val ph = if (phase.isNaN()) 0f else phase
        for (i in 0 until WATER_WAVE_SAMPLES) {
            out[i] = baseY + waveOffsetPx(i, WATER_WAVE_SAMPLES, amp, ph, bands, n)
        }
        return out
    }

    /**
     * 单个采样点的**偏移量**（px，正 = 向下）—— 抽出来是为了可断言的**纯函数**。
     *
     * `off = amp × sin(2π·(i/N) + 2π·phase) × (2·band − 1)`
     *
     * ★ 三个因子各自的作用（缺一都会变样）：
     * - `sin(... + 2π·phase)` —— **相位推进** ⇒ "水在流动"（phase 静止时波形冻结 = 暂停即停）；
     * - `(2·band − 1)` —— 频谱调制，**零均值** ⇒ 平均线不被抬高（M7 的根据）；
     * - `amp` —— 幅度，由 [waveAmplitudePx] 给出（"低高度"约束）。
     */
    public fun waveOffsetPx(
        i: Int,
        samples: Int,
        ampPx: Float,
        phase: Float,
        bands: FloatArray,
        bandCount: Int,
    ): Float {
        if (samples <= 0 || bandCount <= 0 || bandCount != bands.size) return 0f
        val u = i.toFloat() / samples
        // ⚠️ 用 `kotlin.math.sin` 而不是 `Math.sin` —— 后者返回 `Double`，
        //    混用会让下面的 `Float` 表达式被提升为 `Double`（本项目栽过类型提升的亏）。
        val angle = (2.0 * Math.PI * (u + phase)).toFloat()
        // 采样点 i 取哪一条频带 —— 均匀映射到 bands 的下标域。
        // ⚠️ `i.toLong()` **必须带括号** —— `i.toLong` 是属性引用（函数类型），
        //    不是调用（Kotlin 的经典坑）。
        val idx = ((i.toLong() * bandCount) / samples).toInt().coerceIn(0, bandCount - 1)
        val band = bands[idx].coerceIn(0f, 1f)
        return ampPx * kotlin.math.sin(angle) * (2f * band - 1f)
    }

    /**
     * 把折线 y 数组连成 `Path2D`（供 `g2.fill` / `g2.draw` 用）。
     *
     * 起点在 `rect` 左下、终点在右下，中间是波动折线 ⇒ 填出来的就是"水面以下的水体"。
     *
     * @param ys [waveLineYs] 的输出；`null` ⇒ 返回 `null`（调用方画直线版本）。
     */
    public fun waveAreaPath(rect: Rectangle2D.Float, ys: FloatArray?): Path2D.Float? {
        if (ys == null || ys.isEmpty()) return null
        val left = rect.x.toFloat()
        val right = (rect.x + rect.width).toFloat()
        val bottom = (rect.y + rect.height).toFloat()
        val p = Path2D.Float()
        p.moveTo(left, ys[0])
        // 折线：逐个采样点连过去（采样点在 [left, right] 上均匀分布）。
        val step = rect.width.toFloat() / (ys.size - 1).coerceAtLeast(1)
        for (i in ys.indices) {
            p.lineTo(left + step * i, ys[i])
        }
        // 右侧封口：下到岛底再回到左侧 ⇒ 围出"水面以下"那块。
        p.lineTo(right, bottom)
        p.lineTo(left, bottom)
        p.closePath()
        return p
    }

    /**
     * ★★ **双色处置**（§9-R 定案）—— 暗底**发光**；亮底**发暗**（= 阴影）。
     *
     * ## 为什么不能"无条件用白色高光"
     *
     * [MaterialLayers] 的类 KDoc 逐字写着：
     *
     * > 「**白色高光在浅色底上不可见甚至反向**（`B §A-3` R3，证据等级【推断】）。
     * > 本版**不做**亮度自适应 —— 用户把背景调成浅色时高光会"看不见"」
     *
     * ⇒ 同一个坑会**一模一样**落到"水位光感"上。§9-R 的定案是 **③ 用双色**。
     *
     * ## 明度判据用**本体色的实际明度**（不是系统深浅色）
     *
     * §2.4.7 C 明确：**应当用"本体色的实际明度"**（`Appearance` 里就有 base color），
     * 而不是 `SystemTheme.isLight()` —— 因为**背景色可能被用户手动设成浅色**。
     *
     * @param baseColor 本体色（= 背景当前实际用的那个颜色）
     * @return 高光色：暗底 ⇒ 白（发光）；亮底 ⇒ 黑（阴影）
     */
    public fun highlightTintFor(baseColor: Color): Color = if (isLightBase(baseColor)) {
        Color(0, 0, 0)
    } else {
        Color(255, 255, 255)
    }

    /**
     * 本体色**是否算浅色** —— 纯判据，抽出便于探针断言双色的**分界点**。
     *
     * 用 `Color.RGBtoHSB` 的**亮度（brightness）**分量，阈值 0.5。
     * 为什么不用人眼亮度加权（`0.299R+0.587G+0.114B`）：本项目已有的
     * `CoverPalette` 全走 HSB 派生（[CoverPalette.brightFrom] / [darkFrom]），
     * 保持**同一套色彩空间**比"更精确的公式"更重要 —— 否则会出现
     * "调色用的是 HSB、判明暗用的是加权"两套，将来必然有人问哪个对。
     */
    public fun isLightBase(baseColor: Color): Boolean {
        val hsb = Color.RGBtoHSB(baseColor.red, baseColor.green, baseColor.blue, null)
        return hsb[2] > 0.5f
    }

    /**
     * 把水位的**全部绘制**一次画完 —— 顺序是硬约束。
     *
     * ## 顺序（§2.4.2）
     *
     * ```
     * ① 水体填充（渐变）      ← 最底
     * ② 波动折线（若有数据）
     * ③ 水位线高光带          ← 最上（"光"必须盖在"水"上）
     * ```
     *
     * ## 调用方的前置条件（硬约束，与 [MaterialLayers.paint] 的 KDoc 同源）
     *
     * 1. **已处于 `g2.clip = shape` 之内** —— 本函数不做裁剪（见类 KDoc）；
     * 2. **已画完背景/材质、还没画封面** —— 水位**在封面之下**（§2.4.7 A：
     *    收起态"可见部分是那一圈环"正因为封面挡住了中间）；
     * 3. 当前形态是**封面态** —— 判据由调用方给（本函数不认识 `shape.type`）。
     *
     * ## ⚠️ 本函数**不改 `g2` 的 paint/color**（画完即还原）
     *
     * 与 [MaterialLayers.paint] 的 `oldPaint` / `oldComposite` 保存-还原同一约定：
     * 调用方紧接着要画封面，若水位把 `g2.paint` 留成渐变，封面会跟着变色。
     */
    public fun paint(
        g: Graphics2D,
        rect: Rectangle2D.Float,
        accent: Color,
        bands: FloatArray?,
        phase: Float,
        baseColor: Color,
    ) {
        if (!shouldDraw(rect)) return
        val oldPaint = g.paint

        // ── ① 水体：有频谱数据 ⇒ 画波动水面；否则 ⇒ 画直边水位 ────────────────
        //
        // ★ 两条路**不同 paint、同一个 rect 语义**（都从岛底升到水位线）——
        //   于是"波动只围绕水位线"是结构性质，不是靠对齐。
        val ys = waveLineYs(rect, bands, phase)
        val area = waveAreaPath(rect, ys)
        g.paint = waterFillPaint(rect, accent)
        if (area != null) {
            g.fill(area)
        } else {
            // 退化：无频谱数据（助手未启动 / 未 READY）或水位太矮 ⇒ 直边水位。
            // ★ 这不是"缺陷"，是**正常的降级** —— 见 `syncSpectrum` 的接线说明。
            g.fill(rect)
        }

        // ── ③ 水位线高光带 ────────────────────────────────────────────────────
        //
        // ★ 双色处置（§9-R）：暗底 ⇒ 发光（白）；亮底 ⇒ 发暗（黑，= 阴影）。
        //   色相由**本体色的实际明度**决定（§2.4.7 C：不是系统深浅色）。
        //   ⚠️ 只建**一个** `LinearGradientPaint`（纪律 D-b）——
        //      故这里**直接按色调建**，不再"先建白的、发现要黑再建黑的"（那是两次分配）。
        //   ★ 也不做 `tint == Color.WHITE` 这类**引用比较** —— 那依赖单例实现细节；
        //     判据只有 [isLightBase] 一处，色调由 [highlightTintFor] 一处给出。
        //
        // 跨度 = 水位高的 1/4（§2.4.6 D 的观感档："羽化带宽 ≤ 水位高的 1/4"）。
        val span = rect.height * 0.25f
        val tint = highlightTintFor(baseColor)
        val hl = waterHighlightPaintOfColor(rect, span, WATER_HIGHLIGHT_ALPHA, tint)
        if (hl != null) {
            g.paint = hl
            // 高光带画在**水位线之上**、向上淡出 ⇒ 用矩形填（渐变自带方向）。
            g.fill(Rectangle2D.Float(rect.x, rect.y - span, rect.width, span))
        }

        g.paint = oldPaint
    }

    /**
     * 高光带的 alpha。取 46 —— 与 [MaterialLayers] 的 `HIGHLIGHT_MAX_ALPHA` **同值**。
     *
     * 为什么同值：两者都是"玻璃/水面反光"的同一观感族，用户若觉得玻璃高光合适，
     * 水面高光就该是同一档。★ 不同值会让两处观感打架，且没人说得清哪个"对"。
     */
    public const val WATER_HIGHLIGHT_ALPHA: Int = 46

    /**
     * 由播放位置算**波动相位**（0..1）—— 纯函数，抽出便于断言。
     *
     * `phase = (positionMs % periodMs) / periodMs`
     *
     * ★ 用**播放位置**（不是墙钟时间）⇒ **暂停即冻结**（§2.4.6 C-2 的门控要求）。
     * 这与 `coverRotationDegrees` 用 `positionMs` 而**不是** `System.currentTimeMillis()`
     * 是同一条纪律（见 `drawCover` 的 `positionMs` 参数 KDoc）。
     *
     * @param periodMs 波动一个完整周期的时长（越小越快）
     */
    public fun wavePhase(positionMs: Long, periodMs: Long): Float {
        // ★★ 2026-10-03：**委托到唯一出口** `loopPhase`（`render/PhaseAdvance.kt`）——
        //   理由见该文件的文件头：⑰ **F2（流光位移动画）** 会是**第二处**这种逻辑，
        //   而台账 §3.15′ 明令「**先抽公用推进逻辑**」（纪律 10 已犯 6 次）。
        //
        //   ★ **零行为改动**：本函数原先的三行运算**逐字**搬进了 `loopPhase`
        //     （含"负位置归入 0..1"那条边界），只是换了个住址。
        //   ★ **保留本函数名是有意的**：`CoverWaterLevelProbe` 已按此名钉了断言，
        //     改签名会让探针**无谓地红一次**（而它守的东西**没有任何变化**）。
        return loopPhase(positionMs, periodMs)
    }

    /**
     * 由播放位置与时长算**进度**（0..1）—— 纯函数。
     *
     * ⚠️ **时长未知（`<= 0`）⇒ 返回 0**（不画水位），而不是"画满"或"画一半"：
     * 流媒体/直播曲目时长为 0，画满会让人以为"已经放完了"。
     *
     * @param positionMs 当前位置
     * @param durationMs 总时长；`<= 0` = 未知
     */
    public fun progressOf(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        if (positionMs <= 0L) return 0f
        if (positionMs >= durationMs) return 1f
        return positionMs.toFloat() / durationMs.toFloat()
    }
}
