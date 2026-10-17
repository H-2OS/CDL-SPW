// SPDX-License-Identifier: AGPL-3.0-only
//
// L3 真透视的**自捕获检测判据**（R5 护栏）—— 判据本体，**纯函数、无状态、可断言**。
//
// ## 为什么这份判据必须存在
//
// L3（真透视）靠 `Robot` 截屏取模糊背景。`WDA_EXCLUDEFROMCAPTURE` 让词岛
// **不被自己截到**——它**已实测成立**（`migration/23-*`），但**万一失败**：
// 截到的帧里会含词岛自己 ⇒ 拿它当背景 ⇒ **R2 回授循环**
// （每帧把上一帧玻璃再模糊 ⇒ **糊成一团**）。
//
// ⇒ `migration/23-*` §3 把它列为**必做护栏**，并写明纪律：
// **不得静默把（可能含自己的）糊图当背景**。
//
// ## 判据是什么（`migration/31-*` §4）
//
// **核心洞察**：App **本来就知道自己上一帧渲染了什么**（岛体面板的帧就在 EDT 手里）。
// ⇒ 把「自己的帧」与「截到的帧」都降到亮度，**只在岛体形状内部**算**皮尔逊相关**：
//
// ```
// S1 = pearson( lum(capture[insideShape]), lum(ownFrame[insideShape]) )
// ```
//
// · 自捕获 ⇒ 截到的就是自己 ⇒ **S1 ≈ 1**（实测 0.996–0.997）
// · 排除成功 ⇒ 截到的是桌面 ⇒ **S1 低**（实测 0.105–0.571）
// · **与用户配色无关** —— 哪怕歌词设成白色也能用（这是它优于"高饱和色签名"的地方）
//
// ## 为什么有 S1-hp（高通变体）
//
// 自指纹帧里含 **L3 模糊背景层**，而模糊层**本就与背景低频相关** ⇒
// 原版 S1 在正常桌面上也会拿到中等相关（实测 **normA = 0.571**，逼近阈值）。
// 把**局部均值减掉**（只比结构、不比低频）后，正常桌面掉到 **−0.071** ⇒ 间隔极大。
//
// ## 为什么 S2（高饱和色签名）**不单独判决**
//
// 任务书建议的 S2 经实测**两头失败**（`migration/31-*` §4.3）：
// · **漏判** —— 默认配色下「已唱色 = 纯白」，S = 0 ⇒ alarm 占比仅 0.0126
// · **误判** —— 高饱和桌面上正常帧 0.7143
// ⇒ **不存在可分离阈值**（自捕获类 [0.013, 0.266] 与正常类 [0.019, 0.855] **重叠**）。
// 故本文件**不实现 S2**；若将来要用，只能作"条件前置/佐证"，**不得单独判决**。
//
// ## 本文件与探针的关系
//
// 全部是**公开顶层纯函数** ⇒ 探针可直接调，断言
// 「阈值表」「OR 判决真值表」「判别力表（5 帧）」「降级链状态机」四类不变量。
// 这正是本项目既有做法（见 `KaraokeFeather.kt` 头部"为什么必须抽出来")。
//
// ## ⚠️ 诚实声明（必须随代码保留）
//
// 合并规则在**纯自捕获**上有 ≥0.18 裕度，但在**稀释应力态**
// （玻璃把自捕获稀释到 35%）裕度**仅 0.018**（0.768 vs 0.75）⇒
// **单帧不足以定案** ⇒ **必须**叠加多帧持久化（见 [SelfCaptureRules.K] / [SelfCaptureRules.K2]）。
//
// @see SelfCaptureDecision
// @see detectSelfCapture
package dev.cdl.render

/**
 * 判据阈值与持久化常量 —— **唯一真源**（`migration/31-*` §4.4 / §4.6 / §4.7）。
 *
 * ⚠️ 改任何一个值都会让"判别力表"结论失效（`migration/31-*` §4.3 的 5 帧实测），
 *    **必须重跑那张表**（`decideByScores` 的断言会当场红）。
 */
object SelfCaptureRules {

    /**
     * **S1 阈值**。落在自捕获（0.996–0.997）与正常（0.105–0.571）之间，**距两端 ≥0.18**。
     */
    const val TAU1: Double = 0.75

    /**
     * **S1-hp 阈值**。自捕获 0.979–0.983 vs 正常 ≤0.003 —— 间隔极大，0.70 已留裕度。
     */
    const val TAU1HP: Double = 0.70

    /**
     * **告警持久化帧数** —— 连续判自捕获 3 帧 ⇒ 告警「真透视暂不可用」。
     *
     * 单帧不告警的理由：单帧裕度可能只有 0.018（见文件头"诚实声明"）⇒
     * **单帧判决会误报**，而误报的代价是用户看到一个不该有的警告。
     */
    const val K: Int = 3

    /**
     * **退纯色持久化帧数** —— 连续 10 帧（≈1 s @10 Hz）⇒ 退「纯色玻璃」并**停用截屏**。
     *
     * 为什么比 [K] 大得多：退纯色是**能力降级**（真透视没了），不可轻易触发；
     * 1 秒的持续失败才足以证明"这不是偶发"。
     */
    const val K2: Int = 10

    /** 建议采集频率（Hz）—— [K2] 的"≈1 s"按它折算。**仅报告，不强制**。 */
    const val RECOMMENDED_HZ: Int = 10

    /** S1-hp 的局部均值半径（像素）。`migration/31-*` §6 #4：`r=8` 是否最优**未定论**。 */
    const val HIGHPASS_RADIUS: Int = 8
}

/**
 * 判据的**三种**返回。注意 [UNKNOWN] **不是** [OK] ——
 *
 * `ownFrame == null`（还没渲染过 / 截屏不可用）时**不能**当作"正常帧"放行，
 * 否则恰好落入"**静默把糊图当背景**"那条铁律禁止的情形
 * （冷启动首帧若已是自捕获，护栏会因为"读不到自己"而失效）。
 *
 * 故 [UNKNOWN] 在降级链里与 [SELF_CAPTURE] **同向处理**（`migration/31-*` §4.6 ④/§4.7）。
 */
enum class SelfCaptureDecision {
    /** 判定为自捕获 ⇒ **弃该帧**（沿用上一帧有效背景）。 */
    SELF_CAPTURE,

    /** 判定为正常 ⇒ 可以用这一帧更新模糊层。 */
    OK,

    /**
     * **判不出来** ⇒ 与 [SELF_CAPTURE] 同向处理（弃帧 + 计入连续数）。
     *
     * 触发条件（任一）：`own` 无像素证据 · `capture` 与 `own` 尺寸不符 ·
     * 形状内取样点数为 0 · 计算中出现非有限值。
     */
    UNKNOWN,
}

/**
 * 一次判据的**完整读数** —— 判决 + 两个分数 + 命中原因。
 *
 * 为什么把分数也带出来（而不只返回枚举）：本项目反复栽在
 * 「**只给结论、不给依据** ⇒ 下次出事只能重新猜**」（见 `timingMainDesc` 的 KDoc）。
 * 带出 `s1` / `s1hp` 后，告警里能直接写出"0.94 判自捕获"这样的人话。
 *
 * @property decision 判决
 * @property s1 原版自指纹相关（`null` = 未能计算）
 * @property s1hp 高通变体（`null` = 未能计算）
 * @property reason 命中原因（**诊断用**，如 `own-missing` / `size-mismatch` / `empty-mask`）
 */
data class SelfCaptureReading(
    val decision: SelfCaptureDecision,
    val s1: Double?,
    val s1hp: Double?,
    val reason: String,
)

/**
 * ★ **合并判决** —— 判据本体（`migration/31-*` §4.4 / §4.7）。
 *
 * ```
 * 自捕获 ⇔ (S1 ≥ τ1) OR (S1-hp ≥ τ1hp)
 * ```
 *
 * 为什么是 **OR** 而不是 AND：两条判据各有各的盲区 ——
 * · **S1** 在"玻璃稀释"时掉得少（partial 0.768，勉强过线）但当背景静止时会**误判**（normA 0.571）；
 * · **S1-hp** 几乎免疫误判（正常 ≤0.003）但在稀释时掉得狠（partial 0.202）。
 *
 * ⇒ OR 让**任一条亮就判自捕获**：用的是"**宁可弃帧，不可糊屏**"的取舍 ——
 * 弃一帧的代价是"背景晚 100 ms 更新"（用户看不见），
 * 而放进一帧糊图的代价是**持续的回授循环**（用户看得见，且越糊越糊）。
 *
 * ⚠️ 这个取舍的**代价**是误报率上升（S1 会在静止壁纸上误判）。
 *    这正是 [SelfCaptureRules.K] 存在的理由：**单帧判决不用，多帧持久化才用**。
 *
 * @param s1 原版分数
 * @param s1hp 高通分数
 * @return 命中 ⇒ [SelfCaptureDecision.SELF_CAPTURE]；都不亮 ⇒ [SelfCaptureDecision.OK]
 */
fun decideByScores(s1: Double, s1hp: Double): SelfCaptureDecision =
    if (s1 >= SelfCaptureRules.TAU1 || s1hp >= SelfCaptureRules.TAU1HP) {
        SelfCaptureDecision.SELF_CAPTURE
    } else {
        SelfCaptureDecision.OK
    }

// ── 亮度与皮尔逊 ────────────────────────────────────────────────────────────

/**
 * 像素 → 亮度（Rec.601 系数，与归档实测探针**同系数**）。
 *
 * ⚠️ 系数必须与 `SelfCaptureDetectProbe`（归档 `_blurpaths/e5/`）**逐字一致** ——
 *    否则"实测判别力表"不能迁移到本实现（表里的 0.997 / 0.571 等数字会失效）。
 */
fun luminanceOf(argb: Int): Double {
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/**
 * 在**掩膜内**算两组亮度的**皮尔逊相关**。
 *
 * 分母加 `1e-9` 的**唯一理由**是防"两组都完全常数"（方差 0）时 `0/0`。
 * 此时返回值 ≈ 0 ⇒ 判 [SelfCaptureDecision.OK] ——
 * ⚠️ 这是**已知的漏判面**（岛体纯色静止 + 背景恰好同色，`migration/31-*` §4.5 已登记为未测）。
 *    **不得**为了"看起来更安全"改成返回 1（那会把所有纯色帧都判成自捕获）。
 *
 * @param a 第一组（已按掩膜顺序收集）
 * @param b 第二组（同上，**索引必须与 [a] 一一对应**）
 * @return 相关系数；样本 < 2 ⇒ `null`（**判不出来**，不得当 0 用）
 */
fun pearsonOf(a: DoubleArray, b: DoubleArray): Double? {
    if (a.size != b.size || a.size < 2) return null
    val n = a.size
    var sa = 0.0
    var sb = 0.0
    for (i in 0 until n) {
        sa += a[i]
        sb += b[i]
    }
    val ma = sa / n
    val mb = sb / n
    var cov = 0.0
    var va = 0.0
    var vb = 0.0
    for (i in 0 until n) {
        val da = a[i] - ma
        val db = b[i] - mb
        cov += da * db
        va += da * da
        vb += db * db
    }
    return cov / kotlin.math.sqrt(va * vb + 1e-9)
}

/**
 * 高通亮度图：**每个像素减去其 `[2r+1]²` 邻域的均值**（只留结构、去掉低频）。
 *
 * ## 为什么用可分离盒均值 + 前缀和
 *
 * 归档实测的实现是**两趟可分离盒均值**（水平一趟 + 垂直一趟），
 * 复杂度 `O(W·H)` 而非朴素 `O(W·H·r²)`。后者在 `r=8` 时是 **289 倍**的常数 ——
 * 而本函数**每次采集都跑**（10 Hz），且 `migration/31-*` §4.3 已实测
 * S1-hp 是**四项里最贵的**（p50 2.040 ms，是 S1 的 2.3 倍）。
 *
 * 边界处理：**夹取**（clamp）到图像内 —— 与归档实现一致，保证判别力表可迁移。
 *
 * @param pixels 逐行紧密排列的 ARGB（长度 = `w × h`）
 * @param r 半径（[SelfCaptureRules.HIGHPASS_RADIUS]）
 * @return 长度 = `w × h` 的高通亮度
 */
fun highpassLuminance(pixels: IntArray, w: Int, h: Int, r: Int): DoubleArray {
    require(pixels.size >= w * h) { "pixels 长度 ${pixels.size} < w*h ${w * h}" }
    require(r >= 0) { "半径不得为负：$r" }
    val lum = DoubleArray(w * h)
    for (i in 0 until w * h) lum[i] = luminanceOf(pixels[i])
    if (r == 0) return lum

    // ① 水平一趟：`tmp[y][x] = mean(lum[y][x-r..x+r])`（夹取边界）
    val tmp = DoubleArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var sum = 0.0
            var count = 0
            var dx = -r
            while (dx <= r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                sum += lum[row + xx]
                count++
                dx++
            }
            tmp[row + x] = sum / count
        }
    }

    // ② 垂直一趟：`out[y][x] = mean(tmp[y-r..y+r][x])` ⇒ 完整邻域均值
    val mean = DoubleArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var sum = 0.0
            var count = 0
            var dy = -r
            while (dy <= r) {
                val yy = (y + dy).coerceIn(0, h - 1)
                sum += tmp[yy * w + x]
                count++
                dy++
            }
            mean[y * w + x] = sum / count
        }
    }

    // ③ 高通 = 原值 − 局部均值
    for (i in 0 until w * h) lum[i] -= mean[i]
    return lum
}

/**
 * ★★ **主入口 —— 判据本体**（`migration/31-*` §4.7 `detectSelfCapture` 的生产实现）。
 *
 * ## 输入为什么是"像素 + 形状掩膜"而不是两个 `BufferedImage`
 *
 * 因为生产里的两张图**来源与尺寸都不同**：
 * · `capture` 来自 `Robot.createScreenCapture`（`TYPE_INT_RGB`，屏幕坐标）
 * · `own` 来自 `IslandPanel.publishOwnFrame`（岛体区域，原点 = 岛体左上角）
 *
 * 把它们先**对齐成同一坐标系**是调用方的责任（本项目既有做法：
 * 「把判据放在有数据的那一侧」，见 `drawnCoverBounds` 的 KDoc）。
 * 本函数只要求：两组像素**逐索引一一对应**，且 [insideMask] 标记"哪些索引参与"。
 *
 * ## 为什么掩膜是 `BooleanArray` 而不是 `Shape`
 *
 * 与归档实测探针一致 —— `shapeMask()` 把形状**栅格化一次**再复用
 * （`migration/31-*` §4.7：`shapeMask := rasterize(...)` **一次性**，
 * 尺寸/形状变化时才重算）。每帧拿 `Shape.contains` 逐像素判定会慢一个量级。
 *
 * ## 返回 `UNKNOWN` 的四种情形（都不许当"正常"）
 *
 * | 情形 | `reason` |
 * |---|---|
 * | 掩膜内取样点 < 2 | `empty-mask` |
 * | 两组长度不等 | `length-mismatch` |
 * | 宽高 <= 0 | `bad-size` |
 * | 分数非有限 | `non-finite` |
 *
 * @param capturePixels 截到的帧（ARGB，长度 ≥ `w×h`）
 * @param ownPixels **自身渲染帧**（同上，索引与 `capturePixels` 一一对应）
 * @param insideMask 逐索引的"是否在岛体形状内"（长度 = `w×h`）
 * @param w 宽 @param h 高
 * @param highpassRadius S1-hp 半径（默认 [SelfCaptureRules.HIGHPASS_RADIUS]）
 */
fun detectSelfCapture(
    capturePixels: IntArray,
    ownPixels: IntArray,
    insideMask: BooleanArray,
    w: Int,
    h: Int,
    highpassRadius: Int = SelfCaptureRules.HIGHPASS_RADIUS,
): SelfCaptureReading {
    if (w <= 0 || h <= 0) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "bad-size")
    }
    val n = w * h
    if (capturePixels.size < n || ownPixels.size < n || insideMask.size < n) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "length-mismatch")
    }

    // ── 收集掩膜内的样本（两趟复用同一份索引列表，避免三次遍历掩膜）──────────
    var count = 0
    for (i in 0 until n) if (insideMask[i]) count++
    if (count < 2) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "empty-mask")
    }
    val ca = DoubleArray(count)
    val oa = DoubleArray(count)
    var k = 0
    for (i in 0 until n) {
        if (insideMask[i]) {
            ca[k] = luminanceOf(capturePixels[i])
            oa[k] = luminanceOf(ownPixels[i])
            k++
        }
    }

    val s1 = pearsonOf(ca, oa)

    // ── S1-hp：高通后再相关。掩膜外的像素**仍参与高通计算**（邻域要完整），
    //    只是**不进入相关样本** —— 否则掩膜边缘会出现人为的"断层"，把结构相关拉低。
    val hpCap = highpassLuminance(capturePixels, w, h, highpassRadius)
    val hpOwn = highpassLuminance(ownPixels, w, h, highpassRadius)
    val hca = DoubleArray(count)
    val hoa = DoubleArray(count)
    var m = 0
    for (i in 0 until n) {
        if (insideMask[i]) {
            hca[m] = hpCap[i]
            hoa[m] = hpOwn[i]
            m++
        }
    }
    val s1hp = pearsonOf(hca, hoa)

    if (s1 == null && s1hp == null) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "non-finite")
    }
    if ((s1 != null && !s1.isFinite()) || (s1hp != null && !s1hp.isFinite())) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, s1, s1hp, "non-finite")
    }

    // ⚠️ 只有一条算得出来时：**按最保守处理** —— 用算出来的那条判决，
    //    但若它"不亮"也**不得**直接判 OK（另一条本可能亮）。
    //    取舍：宁可 UNKNOWN（弃帧）也不放行一帧可能糊的图。
    val decision = when {
        s1 != null && s1hp != null -> decideByScores(s1, s1hp)
        s1 != null -> if (s1 >= SelfCaptureRules.TAU1) {
            SelfCaptureDecision.SELF_CAPTURE
        } else {
            SelfCaptureDecision.UNKNOWN
        }
        else -> if (s1hp!! >= SelfCaptureRules.TAU1HP) {
            SelfCaptureDecision.SELF_CAPTURE
        } else {
            SelfCaptureDecision.UNKNOWN
        }
    }
    val reason = when (decision) {
        SelfCaptureDecision.SELF_CAPTURE -> "hit"
        SelfCaptureDecision.UNKNOWN -> "partial-scores"
        SelfCaptureDecision.OK -> "ok"
    }
    return SelfCaptureReading(decision, s1, s1hp, reason)
}

