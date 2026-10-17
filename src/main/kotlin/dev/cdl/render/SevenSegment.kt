// SPDX-License-Identifier: AGPL-3.0-only
//
// ⑱ 时钟 · **七段数码管**的几何 —— 纯函数（唯一真值源）
//
// ══════════════════════════════════════════════════════════════════════════════
// 为什么单独一个文件 + 为什么全是纯函数
// ══════════════════════════════════════════════════════════════════════════════
//
// 1. **不能用 `Font`** —— 七段是**字形几何**，没有对应的系统字体；
//    引第三方点阵字体要处理**许可证 + 随包体积**（台账 §B.4 第 4 件）。
// 2. **笔画几何必须有唯一来源** —— 本项目纪律：绘制侧与探针**共用同一份算术**
//    （否则"探针说对、真机画出来不对"）。
// 3. ★ **纯函数才能被探针穷举** —— 七段的"哪几段亮"是**真值表**，
//    完全可以穷举断言（10 个数字 + 冒号），**不需要真机**。
//
// ══════════════════════════════════════════════════════════════════════════════
// ★★ U-1：本文件首先回答一个**布局决策问题**
// ══════════════════════════════════════════════════════════════════════════════
//
// 右端插槽的可用宽 = `RIGHT_SLOT_WIDTH_PX`（= 封面宽 56 px，见 `SpectrumGeometry.kt`）。
// ★ 它**曾经**是 `RIGHT_BAR_COUNT(5) × SPECTRUM_BAR_PITCH_PX(7)` = 35 px ——
//   35 px 下 `HH:MM` 的段厚只有 ~1 px（糊成一片）⇒ 2026-10-03 用户拍板
//   「**两端封面和时间组件保持对称感**」⇒ 插槽宽取**封面宽**（56）。
// **`HH:MM` 在 35 px 里放得下吗？** ⇒ 用 [fitDigitWidth] 机械算，不靠目测。
//
// 结论见 `migration/80-*` §5 的 U-1（本文件给出**判据**，结论由探针钉住）。

package dev.cdl.render

/**
 * 七段数码管的**段位掩码**与**几何比例** —— 全纯常量 + 纯函数。
 *
 * ## 段位编号（业界惯例，顺时针）
 *
 * ```
 *      aaaa
 *     f    b
 *     f    b
 *      gggg
 *     e    c
 *     e    c
 *      dddd
 * ```
 *
 * ★ **为什么用位掩码而不是 `BooleanArray(7)`**：掩码是 `Int` ⇒ 可**直接写进真值表常量**、
 * 可**按位断言**（探针里 `maskOf('1') == B or C`），且**无分配**（渲染热路径友好）。
 */
public object SevenSegment {

    // ── 段位（bit0..bit6）────────────────────────────────────────────────────
    public const val A: Int = 1 shl 0   // 上
    public const val B: Int = 1 shl 1   // 右上
    public const val C: Int = 1 shl 2   // 右下
    public const val D: Int = 1 shl 3   // 下
    public const val E: Int = 1 shl 4   // 左下
    public const val F: Int = 1 shl 5   // 左上
    public const val G: Int = 1 shl 6   // 中

    /** 全部 7 段 —— 用于断言"没有第 8 位"（纵深防御）。 */
    public const val ALL: Int = A or B or C or D or E or F or G

    /**
     * ★★ **真值表**：字符 → 段掩码。`-1` = **本渲染器不支持该字符**。
     *
     * ★ 数字 0–9 与冒号是**唯一**需要的（时钟 = `HH:MM`）。
     * ★ 负号 / 空白留给"将来要显示日期"之类的扩展 —— **现在不给**（本项目纪律：
     *   **选项数 = 真实状态数**；同理，**真值表也只放真实用到的字符**）。
     */
    public fun maskOf(ch: Char): Int = when (ch) {
        '0' -> A or B or C or D or E or F
        '1' -> B or C
        '2' -> A or B or G or E or D
        '3' -> A or B or G or C or D
        '4' -> F or G or B or C
        '5' -> A or F or G or C or D
        '6' -> A or F or G or E or C or D
        '7' -> A or B or C
        '8' -> ALL
        '9' -> A or B or C or D or F or G
        // 冒号：**用 G 段**（中间那一横）当两个点 —— ★ 这是本项目**自己的取舍**：
        //   真冒号是"两个点"，而七段里没有"点"这个段。
        //   ⇒ 用 G 表示"冒号"是**可读的**（它在竖直中线上），且**不新增段位**。
        //   ★ 代价如实记录：它和数字的 G 段**同形** ⇒ 需要靠**宽度**（冒号更窄）区分。
        ':' -> G
        else -> -1
    }

    /** 该字符是否可渲染（= 真值表里有它）。 */
    public fun supports(ch: Char): Boolean = maskOf(ch) >= 0

    // ── 几何比例（全部相对"数字宽 = 1.0"）────────────────────────────────────

    /**
     * 数字的**高 / 宽**比。取 **2.0**（经典七段的比例）。
     * ★ 取 1.0 会让数字看起来"方而矮"、取 2.5 会显得"细长"。
     */
    public const val DIGIT_ASPECT: Float = 2.0f

    /**
     * 段厚 / 数字宽。取 **0.16**（业界常见 0.12–0.20 的中值）。
     * ★ 它决定**最小可读字号**（见 [MIN_SEGMENT_PX]）。
     */
    public const val SEGMENT_THICKNESS_RATIO: Float = 0.16f

    /** 冒号的**逻辑宽**（相对数字宽）。比数字窄 —— 否则 `12:34` 会显得中间空一大块。 */
    public const val COLON_WIDTH_RATIO: Float = 0.35f

    /** 字符间**字距**（相对数字宽）。★ 取 **0.30** —— 与 `SPECTRUM_BAR_GAP_RATIO` 同值（同一族的观感）。 */
    public const val GAP_RATIO: Float = 0.30f

    /**
     * ★★ **段的最小可读厚度（px）** —— 低于它**宁可不画**。
     *
     * 取 **1.5 px** 的理由：1 px 的段在抗锯齿下与相邻段**糊在一起**（"8"与"0"分不出）；
     * 本项目已有同类取舍的先例（`CoverWaterLevel.WATER_MIN_VISIBLE_PX`：
     * **"达不到可见标准就不画，而不是画一个看不清的东西"**）。
     *
     * ★ 这是 [fitDigitWidth] 的**唯一**否决依据 ⇒ 它一变，U-1 的结论就变 ⇒ **必须可断言**。
     */
    public const val MIN_SEGMENT_PX: Float = 1.5f

    /**
     * 一个字符的**逻辑宽**（相对数字宽）。不支持的字符 ⇒ `0f`（不占位，见 [totalWidthRatio]）。
     */
    public fun advanceRatio(ch: Char): Float = when (ch) {
        ':' -> COLON_WIDTH_RATIO
        else -> if (supports(ch)) 1.0f else 0.0f
    }

    /**
     * 文本的**总逻辑宽**（相对数字宽）—— 含**字距**。
     *
     * ```
     * total = Σ advanceRatio(ch)  +  GAP_RATIO × (可渲染字符数 − 1)
     * ```
     *
     * ★ 不支持的字符**既不占宽、也不计字距**（它们**不被画**）—— 这与"画一个豆腐块"相反，
     * 是本项目的一贯取舍（未知就显示为未知/不显示，见 `PlaybackControls.UNKNOWN_TIME`）。
     *
     * @return 逻辑宽；**没有任何可渲染字符** ⇒ `0f`。
     */
    public fun totalWidthRatio(text: String): Float {
        var sum = 0f
        var count = 0
        for (ch in text) {
            if (!supports(ch)) continue
            sum += advanceRatio(ch)
            count++
        }
        if (count == 0) return 0f
        return sum + GAP_RATIO * (count - 1).toFloat()
    }

    /**
     * ★★★ **U-1 的判据**：在 `availPx` 里放下 `text` 所需的**数字宽**；**放不下（段太薄）⇒ `null`**。
     *
     * ```
     * digitWidth = availPx / totalWidthRatio(text)
     * 段厚        = digitWidth × SEGMENT_THICKNESS_RATIO
     * ⇒ 段厚 < MIN_SEGMENT_PX  ⇒ 返回 null（**宁可不画**）
     * ```
     *
     * ★ 返回 `null` 的语义与 `MaterialLayers.highlightPaint` 的"跨度退化 ⇒ null"**一致**：
     *   **把"画出来一定难看"的输入，在进入绘制之前挡掉**，而不是画一团糊的东西。
     *
     * @return 可用的数字宽（px）；`text` 无可渲染字符、`availPx` 非正、或段厚不足 ⇒ `null`。
     */
    public fun fitDigitWidth(text: String, availPx: Float): Float? {
        if (!availPx.isFinite() || availPx <= 0f) return null
        val ratio = totalWidthRatio(text)
        if (!(ratio > 0f)) return null
        val w = availPx / ratio
        if (w * SEGMENT_THICKNESS_RATIO < MIN_SEGMENT_PX) return null
        return w
    }

    /**
     * 该字号下的**数字高**（px）= 数字宽 × [DIGIT_ASPECT]。
     * ★ 供调用方判断"插槽高度够不够"（插槽是竖带 ⇒ 高度通常不是瓶颈，但**要可断言**）。
     */
    public fun digitHeightPx(digitWidthPx: Float): Float = digitWidthPx * DIGIT_ASPECT

    // ══════════════════════════════════════════════════════════════════════════
    // ④ **段形多边形**（2026-10-03）—— 把真值表变成可填充的几何
    // ══════════════════════════════════════════════════════════════════════════
    //
    // ★ **为什么返回"顶点数组"而不是 `Path2D`**：顶点可被探针**逐值断言**
    //   （顶点数 / 是否在框内 / 对称性），而 `Path2D` 只能靠"画出来再比像素"。
    //   ⇒ 调用方（渲染层）拿顶点自己 `Path2D` 化 —— **几何的唯一来源仍是本文件**。
    //
    // ★★ **段形用"削角六边形"（经典七段的样子）**，不是矩形：
    //   矩形段在相邻段相接处会**叠成方块**（拐角发胖），削角后接缝干净。
    //   代价：每个段 6 个顶点（矩形是 4 个）—— 可忽略。

    /**
     * 一个段的**多边形顶点**（闭合多边形，**不含重复的首点**）。
     *
     * ## 参数约定
     *
     * `(x, y, w, h)` = 该**字符**的包围框（`h` 通常 = `w × DIGIT_ASPECT`）。
     * 段厚 `t = w × `[SEGMENT_THICKNESS_RATIO]。
     *
     * ★★★ **2026-10-08（U8）新增 `thicknessRefWidthPx`** —— 见 [glyphPolygons] 的 KDoc。
     *
     * @param seg **单个**段位（[A]..[G] 之一）。★ 传多个位只会取最低位 —— 调用方请逐段调。
     * @param thicknessRefWidthPx 计算段厚用的**参考数字宽**（px）。默认 `-1f` = 用 `w`
     *   （**保持单参数调用点的老行为逐像素不变**）。★ 冒号必须传**数字宽**（见 [glyphPolygons]）。
     * @return 6 个顶点的 `FloatArray`（`[x0,y0,x1,y1,…]`，逆时针）；`seg` 非单一位 ⇒ 空数组。
     */
    public fun segmentPolygon(
        seg: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        thicknessRefWidthPx: Float = -1f,
    ): FloatArray {
        // ★ U8：段厚应由**数字宽**决定 —— 冒号的**框宽**只有 `0.35w`，
        //   若拿它算厚度，冒号的 G 段就只有数字段厚的 35%（用户：「粗细与字体不符」）。
        val t = (if (thicknessRefWidthPx > 0f) thicknessRefWidthPx else w) * SEGMENT_THICKNESS_RATIO
        if (!(t > 0f) || !(w > 0f) || !(h > 0f)) return FloatArray(0)
        // 半厚 —— 削角用
        val half = t / 2f
        // 横向段：沿 x 铺满（两端各留 t/2 的削角），厚度 t
        fun horizontal(y0: Float): FloatArray = floatArrayOf(
            x + half, y0,
            x + w - half, y0,
            x + w, y0 + half,
            x + w - half, y0 + t,
            x + half, y0 + t,
            x, y0 + half,
        )
        // 纵向段：从 `y0` 沿 y 铺**给定长度** `len`，厚度 t
        //
        // ★★★ 2026-10-03（BUG-151）：本函数原先**没有 `len` 参数**，而是直接用数字高 `h`
        //   ⇒ 竖段从 `y0` 一路画到 `y0 + h` ⇒ **比数字框长出一整段**：
        //     · `F`/`B`（上半）从 `y + t` 画到 `y + t + h` ⇒ 下沿溢出 `t`；
        //     · `E`/`C`（下半）从 `yMid + t` 画到 `yMid + t + h` ⇒ 下沿溢出 **`h/2 + t/2`**
        //       （默认 56px 插槽下实测 **10.8 px**，肉眼是"数字下面挂了两条长尾巴"）。
        //   ★ **为什么 92 条断言没拦住**：`SevenSegmentProbe` 只断言了
        //     「**A 段**顶点全在字符框内」—— 而 A 是**横向**段，正好是唯一不会越界的那一类。
        //     ⇒ 教科书级的「**断言存在 ≠ 断言有判别力**」。
        //   ★ 捕获路径：新增的 `ClockGlyphProbe` §H4「三档所有顶点都在字符框内」
        //     是**对全部段**的检查，首次运行即红（读数 `SEGMENT(13.0,35.0)` vs 框底 `25.0`）。
        fun vertical(x0: Float, y0: Float, len: Float): FloatArray = floatArrayOf(
            x0 + half, y0,
            x0 + t, y0 + half,
            x0 + t, y0 + len - half,
            x0 + half, y0 + len,
            x0, y0 + len - half,
            x0, y0 + half,
        )
        val yMid = y + (h - t) / 2f
        // 竖段的长度：上段 `y+t → yMid`、下段 `yMid+t → y+h−t` —— 两者**恰好相等**
        // （= `h/2 − 3t/2`），这正是七段字形的对称性。
        val upperLen = yMid - (y + t)
        val lowerLen = (y + h - t) - (yMid + t)
        return when (seg) {
            A -> horizontal(y)
            G -> horizontal(yMid)
            D -> horizontal(y + h - t)
            // 上半的两条竖段：从 a 的下沿到 g 的上沿
            F -> vertical(x, y + t, upperLen)
            B -> vertical(x + w - t, y + t, upperLen)
            // 下半的两条竖段：从 g 的下沿到 d 的上沿
            E -> vertical(x, yMid + t, lowerLen)
            C -> vertical(x + w - t, yMid + t, lowerLen)
            else -> FloatArray(0)
        }
    }

    /**
     * 一个字符的**全部亮段多边形**（按 [maskOf] 的真值表）。
     *
     * ★★★ **2026-10-08（U8）新增 `thicknessRefWidthPx`** —— 段厚的**参考数字宽**。
     *
     * ## 为什么需要它（用户报障逐字）
     *
     * > 「选择使用时钟时，**时与分之间按周期跳动的横杠**，
     * >   其**粗细与时钟字体粗细不相符**。」
     *
     * ## 根因（取证 · 算术可复核）
     *
     * 冒号走 [`':` → `G`][maskOf]，而 G 是**横向段** ⇒ 它的**厚度**就是段厚 `t`。
     * 但 `t` 原先是 `w × SEGMENT_THICKNESS_RATIO`，这里的 `w` 是**该字符的框宽** ——
     * 而冒号的框宽只有 `w × COLON_WIDTH_RATIO = 0.35w`（`:316` 的 `cw`）。
     * ⇒ **冒号段厚 = 数字段厚的 35%**（实测：数字宽 20 px 时，数字 `3.2 px` / 冒号 `1.12 px`）。
     * ★ 这就是"横杠与字体粗细不符"的**全部成因** —— 数字段与冒号段**用了不同的基准宽**。
     *
     * ## 修法
     *
     * ★ 段厚**恒由数字宽**给出（`thicknessRefWidthPx`），字符框宽 `w` 只管**水平铺开范围**
     *   ⇒ 冒号的横杠与数字的段**同厚** ✓
     * ★ 默认 `-1f` = 用 `w` ⇒ **既有单参数调用点（数字）逐像素不变**。
     *
     * @return 亮段的多边形列表；**不可渲染字符**（[supports] = false）⇒ **空列表**
     *   （★ 与 `totalWidthRatio` 的"不占宽"一致：**不画，而不是画一个占位块**）。
     */
    public fun glyphPolygons(
        ch: Char,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        thicknessRefWidthPx: Float = -1f,
    ): List<FloatArray> {
        val mask = maskOf(ch)
        if (mask < 0) return emptyList()
        val out = ArrayList<FloatArray>(7)
        for (seg in intArrayOf(A, B, C, D, E, F, G)) {
            if (mask and seg == 0) continue
            val poly = segmentPolygon(seg, x, y, w, h, thicknessRefWidthPx)
            if (poly.isNotEmpty()) out.add(poly)
        }
        return out
    }

    /**
     * 整串文本的**全部段形多边形**（含字距推进）—— ⑤ 的接线点。
     *
     * 推进量：数字 `w`、冒号 `w × `[COLON_WIDTH_RATIO]、不可渲染字符 `0`（跳过）；
     * 每个可渲染字符之后加 `w × `[GAP_RATIO]（最后一个不加）。
     *
     * ★ **与 [totalWidthRatio] 同源** ⇒ "算出来的宽度"与"画出来的位置"不可能漂移
     *   （这正是本项目"抽公开纯函数"那条纪律要的）。
     *
     * @return 所有多边形；`text` 无可渲染字符 ⇒ 空列表。
     */
    public fun textPolygons(
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        /**
         * ★★ 2026-10-03（⑱ 时钟 · **冒号闪烁**）：本秒**是否画冒号**。
         *
         * 用户原话：「**HH：MM中间的冒号要能显示秒的跳动。…就是一闪一闪的那种效果。**」
         * ⇒ 由 `ClockText.colonVisible(epochMs)` 给出（**按秒的奇偶**）。
         *
         * ⚠️ **灭的那一秒是"不画"，不是"不占位"** —— 推进量照旧累加
         * （见下方 `cursor += …` 在 if 之外）⇒ ★ 整串**不会左右跳**
         * （否则 `12:34` 与 `1234` 交替出现，看起来像"整块在抖"）。
         */
        colonVisible: Boolean = true,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var cursor = x
        // ★ BUG-160：先找出**最后一个受支持的字符** —— 末尾不再推进 gap（见下方 KDoc）✓
        val lastSupported = text.indexOfLast { supports(it) }
        for ((i, ch) in text.withIndex()) {
            if (!supports(ch)) continue
            // ★★ **该字符的框宽 = `w × advanceRatio(ch)`** —— 冒号只有 `0.35w`。
            //   ⚠️ 这里曾写错成"每个字符都传 `w`" ⇒ 冒号会被画成**整个数字宽**
            //      （G 段铺满 w）⇒ 与 `totalWidthRatio` 的宽度模型**对不上**、
            //      且 `12:34` 会显得中间空一大块。★ 由探针 ⑩′ 钉住。
            val cw = w * advanceRatio(ch)
            // ★ 冒号"灭"的那一秒：**跳过绘制、但下面照旧推进**。
            if (ch != ':' || colonVisible) {
                // ★★★ **2026-10-08（U8）**：段厚**恒按数字宽 `w` 算**（不是字符框宽 `cw`）——
                //   否则冒号的横杠只有数字段厚的 35%（用户：「粗细与字体不符」）。
                //   ★ 数字档 `cw == w` ⇒ 本参数**等于老行为**，逐像素不变 ✓
                out.addAll(glyphPolygons(ch, cursor, y, cw, h, thicknessRefWidthPx = w))
            }
            cursor += cw + w * GAP_RATIO
        }
        return out
    }
}
