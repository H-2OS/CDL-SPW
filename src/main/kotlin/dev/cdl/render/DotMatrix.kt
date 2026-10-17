// SPDX-License-Identifier: AGPL-3.0-only
//
// ⑱ 时钟 · **点阵（DOT）/ 像素风（PIXEL）** 的几何 —— 纯函数（唯一真值源）
//
// ══════════════════════════════════════════════════════════════════════════════
// 用户原话（逐字，2026-09-28）
// ══════════════════════════════════════════════════════════════════════════════
//
// > 「**七段数码管、点阵、像素风的时间显示样式**」
//
// 2026-10-03 只做了「七段数码管」（`SevenSegment.kt`）；本文件补上后两种。
//
// ══════════════════════════════════════════════════════════════════════════════
// ★★ 设计决策：**两种风格共用同一张 5×7 点阵真值表**
// ══════════════════════════════════════════════════════════════════════════════
//
// 「点阵」与「像素风」的**区别不在"哪几格亮"，而在"一格画成什么"**：
//
// | 风格 | 一格的形状 | 格内填充比 | 观感 |
// |---|---|---|---|
// | **DOT**（点阵） | **正八边形**（近圆） | `0.72` | LED 点阵屏：点之间有**可见的缝** |
// | **PIXEL**（像素风） | **正方形** | `1.06`（**故意 > 1**） | 8-bit 位图字：方块**连成笔画** |
//
// ★★ **为什么 PIXEL 的填充比 > 1**（`1.06`）：相邻格若**恰好**相接（比值 = 1），
//   抗锯齿会在公共边上留下一条**比背景略深的发丝缝**（本项目在频谱条上踩过同一形态：
//   `spectrumBarRectX` 的 KDoc 记着「相邻柱双重 `SRC_OVER` 合成出淡竖线」）。
//   ⇒ 让方块**互相重叠 6%**，同色不透明 ⇒ **缝被覆盖掉，而外轮廓不受影响**。
//   ★ 这条是**可断言的不变量**（见 `ClockGlyphProbe`：`PIXEL` 的填充比必须 `> 1`）。
//
// ★ **为什么共用真值表**（而不是各写一套字形）：
//   1. 「哪几格亮」这件事**只该有一个定义点** —— 两套表会在"某个数字写错一格"时**只错一边**
//      （本项目反复栽在"同一份数据两个落点"，见 `BUG-150`）；
//   2. 共用之后，**"两种风格画的是同一个字"这件事本身可被探针断言**；
//   3. 将来要分化（例如像素风改 3×5 粗网格）只需**改常量**，不必重画两套字形。
//
// ══════════════════════════════════════════════════════════════════════════════
// ★★ 与 `SevenSegment` 的**布局同源**（这是刻意的，不是巧合）
// ══════════════════════════════════════════════════════════════════════════════
//
// 宽度模型**完全复用** `SevenSegment` 的那三个比例：
// 数字 = `1.0` · 冒号 = `COLON_WIDTH_RATIO`（0.35）· 字距 = `GAP_RATIO`（0.30）。
//
// ⇒ ★★ **切风格不会让文字重新排版** —— `HH:MM` 在三种风格下占据**同一个包围框**，
//   只有"格子的形状"变。用户来回切档时看到的是"字变了"，而**不是"整块跳了一下"**。
//
// ⚠️ 代价（如实记）：**天真的**自然比例是 `7 行 / 5 列 = 1.4`（若一格恰好占满格位），
//   而七段是 `2.0`（长条）。★ 但本实现的格**不占满格位**（填充比 ≠ 1）
//   ⇒ 真正的数字高由 [digitHeightPx] 按「墨迹恰好铺满」反解（点阵 ≈ `1.424 w`、像素风 ≈ `1.395 w`）。
//   两档都**比七段矮** ⇒ 调用方**纵向居中**摆放（与七段同一条规则），故不会因此错位。
//
// ══════════════════════════════════════════════════════════════════════════════

package dev.cdl.render

/**
 * 5×7 点阵字形的**真值表 + 几何** —— 全纯函数，供「点阵」「像素风」两档共用。
 *
 * ## 网格与位序
 *
 * ```
 * 列 →  0 1 2 3 4
 * 行 0  . . . . .
 * 行 1  . . . . .
 * 行 2  . . . . .
 * 行 3  . . . . .
 * 行 4  . . . . .
 * 行 5  . . . . .
 * 行 6  . . . . .
 * ```
 *
 * 掩码 = **`Long`，位序号 = `行 × COLS + 列`**（`bit0` = 左上角）。
 * ★ 用单个 `Long` 而不是 `IntArray(7)`：**无分配**（渲染热路径每帧调用），
 *   且**可逐位断言**（探针里 `rowBits('1', 1) == 0b01100`）。
 * ★ 35 位 < 64 ⇒ `Long` 够用，且**不会被符号位污染**（永远为正）。
 */
public object DotMatrix {

    /** 数字网格的**列数**。 */
    public const val COLS: Int = 5

    /** 网格的**行数**（数字与冒号相同）。 */
    public const val ROWS: Int = 7

    /** 冒号的**逻辑宽**（相对数字宽）。★ 与 `SevenSegment.COLON_WIDTH_RATIO` **同值**（见文件头）。 */
    public const val COLON_WIDTH_RATIO: Float = 0.35f

    /** 字符间**字距**（相对数字宽）。★ 与 `SevenSegment.GAP_RATIO` **同值**（见文件头）。 */
    public const val GAP_RATIO: Float = 0.30f

    /**
     * ★★ 一格的**最小可见直径（px）** —— 低于它**宁可不画**。
     *
     * 取 **1.2 px**：与 `SevenSegment.MIN_SEGMENT_PX = 1.5` **同一条纪律**
     * （"达不到可见标准就不画，而不是画一个看不清的东西"），数值略小是因为
     * **点的视觉重量小于线段**（1.2 px 的圆点在深色底上仍能看出是一个点，
     * 而 1.2 px 的段会与相邻段糊成一片）。
     */
    public const val MIN_DOT_PX: Float = 1.2f

    // ── 风格常量（wire 值见 `ClockGlyphs`）──────────────────────────────────

    /**
     * 「点阵」档：一格 = **正八边形**（近圆），格内填充比 `0.72` ⇒ **点之间有可见的缝**。
     * ★ 取八边形而不是"真圆"：顶点数固定 ⇒ **可被探针逐值断言**，
     *   且 8 个顶点在 1–3 px 的尺寸下与真圆**肉眼不可分**。
     */
    public const val DOT_FILL_RATIO: Float = 0.72f

    /** 「点阵」档一格的边数。 */
    public const val DOT_SIDES: Int = 8

    /**
     * ★★ 「像素风」档：一格 = **正方形**，填充比 **`1.06`（故意 > 1）**。
     * 理由见文件头（相邻格**重叠**才能消掉 AA 发丝缝）⇒ ★ 这是**可断言的不变量**。
     */
    public const val PIXEL_FILL_RATIO: Float = 1.06f

    /** 「像素风」档一格的边数（正方形）。 */
    public const val PIXEL_SIDES: Int = 4

    /**
     * 该风格的**格内填充比**。★ 传未知值 ⇒ 退回「点阵」档
     * （与 `ClockGlyphs.normalizeStyle` 同口径：**未知档不静默变空白**）。
     */
    public fun fillRatioOf(style: String): Float =
        if (style == ClockGlyphs.STYLE_PIXEL) PIXEL_FILL_RATIO else DOT_FILL_RATIO

    /** 该风格**一格的边数**。 */
    public fun cellSidesOf(style: String): Int =
        if (style == ClockGlyphs.STYLE_PIXEL) PIXEL_SIDES else DOT_SIDES

    // ══════════════════════════════════════════════════════════════════════════
    // ① 真值表
    // ══════════════════════════════════════════════════════════════════════════
    //
    // ★ 每个字形写成一个**可读的 7 行字符串**，再由 `pack` 折成 `Long` ——
    //   而不是直接写十六进制掩码。理由：**十六进制掩码读不出"这个数字长什么样"**，
    //   写错一格时只能靠画出来才发现；而字符串形式**在源码里就是那张图**。
    // ★ 打包在**类初始化时做一次**（`private val`），不进渲染热路径。

    /** 把 7 行 5 位掩码折成一个 `Long`（位序号 = 行 × [COLS] + 列）。 */
    private fun pack(rows: IntArray): Long {
        require(rows.size == ROWS) { "点阵字形必须恰好 $ROWS 行，实为 ${rows.size}" }
        var m = 0L
        for (r in 0 until ROWS) {
            require(rows[r] in 0..0x1F) { "第 $r 行必须是 5 位掩码，实为 ${rows[r]}" }
            m = m or (rows[r].toLong() shl (r * COLS))
        }
        return m
    }

    // ★ 字形来源：经典 **5×7 LED 点阵**（业界通用表）。
    //   ⚠️ 两处**刻意不用**"带斜杠的 0"与"顶行满横的 3" —— 在 2 px 的点径下
    //     斜杠会糊成噪点、满横会让 3 与 7 难分。★ 取舍依据 = 本对象的设计尺寸很小
    //     （56 px 插槽 ⇒ 一格约 2 px），**清晰度优先于花哨**。
    //   ★ 字形可读性已用"把真值表渲染成 ASCII 图"的方式**肉眼复核过**（见 `migration/84`）。
    private val ZERO = pack(intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110))
    private val ONE = pack(intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110))
    private val TWO = pack(intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111))
    private val THREE = pack(intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b00001, 0b10001, 0b01110))
    private val FOUR = pack(intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010))
    private val FIVE = pack(intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110))
    private val SIX = pack(intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110))
    private val SEVEN = pack(intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000))
    private val EIGHT = pack(intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110))
    private val NINE = pack(intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100))

    /**
     * 冒号 —— ★ **1 列**（不是 5 列）。
     *
     * 理由：冒号的**包围框**只有 `0.35 × 数字宽`（= 1.75 格）。
     * 若沿用 5 列网格，格宽会缩到 `0.07 × 数字宽` ⇒ **冒号的点会明显小于数字的点**
     * （看起来像"另一个字号"）。⇒ 冒号用**单列网格**，格宽**与数字完全相同**。
     */
    private val COLON = pack(intArrayOf(0b0, 0b1, 0b1, 0b0, 0b1, 0b1, 0b0))

    /** 该字符的**列数**（数字 5 / 冒号 1）。不支持的字符 ⇒ `0`。 */
    public fun colsOf(ch: Char): Int = when (ch) {
        ':' -> 1
        else -> if (ch in '0'..'9') COLS else 0
    }

    /** 该字符的**位掩码**；`-1L` = **本渲染器不支持该字符**（与 `SevenSegment.maskOf` 同口径）。 */
    public fun maskOf(ch: Char): Long = when (ch) {
        '0' -> ZERO
        '1' -> ONE
        '2' -> TWO
        '3' -> THREE
        '4' -> FOUR
        '5' -> FIVE
        '6' -> SIX
        '7' -> SEVEN
        '8' -> EIGHT
        '9' -> NINE
        ':' -> COLON
        else -> -1L
    }

    /** 该字符是否可渲染（= 真值表里有它）。 */
    public fun supports(ch: Char): Boolean = maskOf(ch) >= 0L

    /**
     * 第 `row` 行的 **5 位行掩码**（`bit0` = 最左列）。
     * ★ 供探针**逐行断言**字形（比断言整个 `Long` 更容易定位错在哪一行）。
     */
    public fun rowBits(ch: Char, row: Int): Int {
        val m = maskOf(ch)
        if (m < 0L || row < 0 || row >= ROWS) return 0
        return ((m ushr (row * COLS)) and 0x1FL).toInt()
    }

    /** 该字符**亮格总数**（冒号按 1 列网格数）。 */
    public fun litCount(ch: Char): Int {
        val m = maskOf(ch)
        if (m < 0L) return 0
        var n = 0
        val cells = ROWS * COLS
        for (i in 0 until cells) if ((m ushr i) and 1L != 0L) n++
        return n
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ② 布局（与 `SevenSegment` 同值 —— 见文件头「布局同源」）
    // ══════════════════════════════════════════════════════════════════════════

    /** 一个字符的**逻辑宽**（相对数字宽）。不支持的字符 ⇒ `0f`（不占位）。 */
    public fun advanceRatio(ch: Char): Float = when (ch) {
        ':' -> COLON_WIDTH_RATIO
        else -> if (supports(ch)) 1.0f else 0.0f
    }

    /**
     * 文本的**总逻辑宽**（相对数字宽）—— 含字距。
     * ★ 公式与 `SevenSegment.totalWidthRatio` **逐字相同**（不支持的字符既不占宽也不计字距）。
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
     * ★★ 在 `availPx` 里放下 `text` 所需的**数字宽**；**放不下（点太小）⇒ `null`**。
     *
     * ```
     * digitWidth = availPx / totalWidthRatio(text)
     * cell       = digitWidth / COLS
     * 点直径      = cell × fillRatioOf(style)
     * ⇒ 点直径 < MIN_DOT_PX ⇒ 返回 null（**宁可不画**）
     * ```
     *
     * ★ 与 `SevenSegment.fitDigitWidth` **同一条纪律**：把"画出来一定看不清"的输入
     *   在进入绘制之前挡掉。
     */
    public fun fitDigitWidth(style: String, text: String, availPx: Float): Float? {
        if (!availPx.isFinite() || availPx <= 0f) return null
        val ratio = totalWidthRatio(text)
        if (!(ratio > 0f)) return null
        val w = availPx / ratio
        // ★ 判据用**一格墨迹的尺寸**（= 格距 × 填充比），而不是"数字宽 / 列数" ——
        //   后者在像素风（fill > 1）下**高估**了格的大小 ⇒ 会在真的看不清时仍然放行。
        val cell = cellPitchPx(w, style)
        if (!(cell > 0f)) return null
        if (cell * fillRatioOf(style) < MIN_DOT_PX) return null
        return w
    }

    /**
     * ★★ 一格的**格心间距**（px）—— 由「**墨迹恰好铺满数字宽**」反解。
     *
     * ## 推导（**必须读懂这一条，否则会改错**）
     *
     * 一个数字有 [COLS] 格，格心在 `0.5c, 1.5c, …, 4.5c`（`c` = 格距），
     * 每格墨迹宽 = `c × fill`。⇒ 墨迹总宽 = `(COLS − 1) × c + c × fill = c × (COLS − 1 + fill)`。
     * 令它 = 数字宽 `w`：
     *
     * ```
     * c = w / (COLS − 1 + fill)
     * ```
     *
     * ★★ **为什么不能简单取 `c = w / COLS`**（那是最直觉的写法）：
     *   那样墨迹总宽 = `(COLS − 1 + fill)/COLS × w`，于是
     *   · 像素风（`fill = 1.06`）⇒ 墨迹 **比标称宽 1.2%**（超出右端插槽）；
     *   · 点阵（`fill = 0.72`）⇒ 墨迹 **比标称窄 5.6%**（看起来没占满，左右各空一截）。
     *   ⇒ 只有按上式反解，**墨迹边界才恰好等于 `totalWidthRatio` 描述的框** ——
     *     这正是「切档不重排」那条不变量（`ClockGlyphProbe` §B/§H1）成立的前提。
     *
     * ★ 推论：**一格的墨迹宽 = `c × fill` 在两档下都是 `w` 的固定比例**，
     *   而**格距**不同 ⇒ 两档的观感差异正是"点之间留多大缝"。
     */
    public fun cellPitchPx(digitWidthPx: Float, style: String): Float {
        val denom = (COLS - 1) + fillRatioOf(style)
        if (!(denom > 0f) || !(digitWidthPx > 0f) || !digitWidthPx.isFinite()) return 0f
        return digitWidthPx / denom
    }

    /**
     * 该风格下的**数字高**（px）—— 同样由「墨迹恰好铺满」反解（[ROWS] 行）。
     *
     * ```
     * h = w × (ROWS − 1 + fill) / (COLS − 1 + fill)
     * ```
     *
     * ★ 它是**风格相关**的（点阵 ≈ `1.424 w`、像素风 ≈ `1.395 w`），
     *   **不是一个常数** —— 故本对象**不提供** `DIGIT_ASPECT` 常量
     *   （给了就会有人拿它去乘，而那在像素风下是错的）。
     */
    public fun digitHeightPx(style: String, digitWidthPx: Float): Float {
        val fill = fillRatioOf(style)
        val denom = (COLS - 1) + fill
        if (!(denom > 0f) || !(digitWidthPx > 0f) || !digitWidthPx.isFinite()) return 0f
        return digitWidthPx * ((ROWS - 1) + fill) / denom
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ③ 格形多边形
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * **一格**的多边形顶点（闭合，**不含重复的首点**）。
     *
     * @param sides 边数（[DOT_SIDES] 近圆 / [PIXEL_SIDES] 正方形）。
     * @return `sides × 2` 个 float（`[x0,y0,x1,y1,…]`）；`diameter ≤ 0` ⇒ 空数组。
     */
    public fun cellPolygon(cx: Float, cy: Float, diameter: Float, sides: Int): FloatArray {
        if (!(diameter > 0f) || !cx.isFinite() || !cy.isFinite()) return FloatArray(0)
        val n = if (sides < 3) 3 else sides
        val r = diameter / 2f
        val out = FloatArray(n * 2)
        // ★ 正方形（n = 4）时**不用**极坐标：`cos(45°) = 0.7071` 会把边长缩成
        //   `diameter × 0.7071`（内接正方形），比预期小 29%。⇒ 四边时直接给四角。
        if (n == 4) {
            out[0] = cx - r; out[1] = cy - r
            out[2] = cx + r; out[3] = cy - r
            out[4] = cx + r; out[5] = cy + r
            out[6] = cx - r; out[7] = cy + r
            return out
        }
        // ★ 从 `-π/2`（正上方）起画：八边形于是**上下左右各有一个顶点**，
        //   而不是"左右是平边" —— 后者在 1–3 px 下看起来比前者窄。
        val start = -kotlin.math.PI.toFloat() / 2f
        val step = 2f * kotlin.math.PI.toFloat() / n
        for (k in 0 until n) {
            val a = start + step * k
            out[k * 2] = cx + r * kotlin.math.cos(a)
            out[k * 2 + 1] = cy + r * kotlin.math.sin(a)
        }
        return out
    }

    /**
     * 一个字符的**全部亮格多边形**。
     *
     * ## 参数约定（★ 与 `SevenSegment.glyphPolygons` **不同**，不要混用）
     *
     * 七段那边收的是"字符包围框 `(x, y, w, h)`"；本函数收的是
     * **格心原点 + 格宽** —— 因为点阵的"框"是**由格数推出**的，
     * 反过来传框再除以列数会**两次取整**（冒号只有 1 列，除出来就不对了）。
     *
     * @param cx 该字符**格心网格的左上角**横坐标（即"第 0 列格心"是 `cx + cellW/2`）。
     * @param cy 该字符**格心网格的左上角**纵坐标（同理）。
     * @param cellW 一格宽（px）。★ 冒号与数字**用同一个值**（见 [COLON] 的说明）。
     * @param style 风格（决定格形与填充比）。
     * @return 亮格的多边形列表；**不可渲染字符** ⇒ 空列表（不画，而不是画占位块）。
     */
    public fun glyphPolygons(
        style: String,
        ch: Char,
        cx: Float,
        cy: Float,
        cellW: Float,
    ): List<FloatArray> {
        val mask = maskOf(ch)
        if (mask < 0L) return emptyList()
        if (!(cellW > 0f)) return emptyList()
        val cols = colsOf(ch)
        if (cols <= 0) return emptyList()
        val fill = fillRatioOf(style)
        val sides = cellSidesOf(style)
        val diameter = cellW * fill
        val out = ArrayList<FloatArray>(ROWS * cols)
        for (r in 0 until ROWS) {
            for (c in 0 until cols) {
                // ★ 冒号是**单列网格** ⇒ 它的位序号按 COLS 打包，但只有第 0 列有意义。
                val bit = if (cols == 1) r * COLS else r * COLS + c
                if ((mask ushr bit) and 1L == 0L) continue
                // ★★★ 2026-10-05 **BUG-154 修复**（用户报「**点阵和像素风的时钟风格下数字是镜像的**」）：
                //   **列序方向反了** —— 真值表用的是**普通二进制字面量（MSB 在左）**：
                //   ```
                //   private val SEVEN = pack(intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, …))
                //   //                                          ↑ 第 2 行只有 bit0 置位
                //   ```
                //   "7" 的斜杠**从右上开始** ⇒ `0b00001` 的置位格必须是**最右** ⇒ ★ **`bit c` = 从右数第 c 格**。
                //   而这里原来写 `(c + 0.5f)` ⇒ **`c=0` 落在最左** ⇒ ★★ **两边方向相反 ⇒ 整块镜像** ✗
                //   ★ **判据（可复核）**：`SEVEN` 第 2 行 `0b00001`；若它画在**最左**就是错的
                //     （"7" 的斜杠起点在右上）· `FOUR` 的 `0b00010` 同理（"4" 的顶点偏右）。
                //   ★ **修法**：只翻转**绘制侧**的列序（真值表 10 个字形 + 冒号**一字不动**）——
                //     比"反转 11 组字面量"改动小得多，且**真值表仍保持"所见即所得"的可读性**
                //     （★ 这一点很重要：写表的人看到 `0b01110` 就能直接想象出字形）。
                //   ⚠️ `cols == 1`（冒号）时本式 = 0，与原来同值 ✓
                val col = cols - 1 - c
                val px = cx + (col + 0.5f) * cellW
                val py = cy + (r + 0.5f) * cellW
                val poly = cellPolygon(px, py, diameter, sides)
                if (poly.isNotEmpty()) out.add(poly)
            }
        }
        return out
    }

    /**
     * 整串文本的**全部格形多边形**（含字距推进）—— 调用方的接线点。
     *
     * 推进量与 [totalWidthRatio] **同源** ⇒ "算出来的宽度"与"画出来的位置"不可能漂移。
     *
     * @param w 数字宽（px）；`h` 仅用于**纵向居中**（应等于 `digitHeightPx(style, w)`）。
     * @param colonVisible 冒号闪烁：**灭的那一秒不画、但照旧占位**（整串不左右跳）。
     */
    public fun textPolygons(
        style: String,
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        colonVisible: Boolean = true,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        // ★ 格距由**数字宽**反解（与 `digitHeightPx` 同一个公式）——
        //   冒号**也用这个值** ⇒ 它的点与数字的点**一样大**（见 [COLON] 的说明）。
        val cell = cellPitchPx(w, style)
        if (!(cell > 0f)) return out
        var cursor = x
        for (ch in text) {
            if (!supports(ch)) continue
            val cw = w * advanceRatio(ch)
            if (ch != ':' || colonVisible) {
                val cols = colsOf(ch)
                // ★★ 格心网格左上角 —— 由「**墨迹中心 == 字符框中心**」反解：
                //   格心网格的墨迹中心 = `gx + cols × cell / 2` ⇒
                //   `gx = cursor + cw / 2 − cols × cell / 2`。
                //   ⚠️ 不能写成 `gx = cursor + (cw − cols × cell) / 2` ——
                //     那个式子让**格心网格**居中，而墨迹（因填充比 ≠ 1）比网格略宽/略窄
                //     ⇒ 数字会整体**偏移** `(fill − 1) × cell / 2`（点阵档约 0.03 w）。
                val gx = cursor + cw / 2f - cols * cell / 2f
                val gy = y + h / 2f - ROWS * cell / 2f
                out.addAll(glyphPolygons(style, ch, gx, gy, cell))
            }
            cursor += cw + w * GAP_RATIO
        }
        return out
    }
}
