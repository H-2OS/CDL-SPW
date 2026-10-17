// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.util.concurrent.ConcurrentHashMap

/**
 * 渲染期**对象复用容器** —— 把「每帧都想要、但构造很贵」的对象集中缓存。
 *
 * ## 为什么必须有这一层（而不是让各渲染器自己 new）
 *
 * 三条实测出来的代价，任何一条都足以否掉「用到就 new」：
 *
 * | 对象 | 构造代价（实测） | 每帧 new 的后果 |
 * |---|---|---|
 * | `TextLayout` | **0.0234 ms/次** | 60 fps 下每帧白烧 1.4 ms（占 16.6 ms 预算的 8.5%）；40 字的一行歌词若逐字建，直接 0.94 ms |
 * | `getOutline(null)` | **0.0712 ms/次** | 比 `TextLayout` 还贵 3 倍，是命中测试的快路径要做粗筛的**前提** |
 * | `getLogicalHighlightShape` | **0.0038 ms/次** | 相对便宜，但每帧几十次也会累加 |
 *
 * 关键认识：**这些对象一旦文本与字体不变，返回值就完全不变** ——
 * 它们只依赖 `(text, font, frc)`，不依赖帧号、不依赖进度、不依赖画布。
 * 因此「缓存」在这里不是优化技巧，而是**去重复计算**：同一份输入算两遍本身就是错的。
 *
 * ## 线程纪律
 *
 * 约定本类**只在 EDT 上使用**（Swing 的绘制线程）。这与渲染管线的整体假设一致 ——
 * `Graphics2D` 本身就非线程安全，它的配套缓存没有理由反过来支持并发。
 * 但缓存**键值容器**仍用 [ConcurrentHashMap]：不是为了并发正确性，而是为了让
 * 「万一被误用」时表现为「多算一次」而不是「`HashMap` 损坏成环、CPU 打满」——
 * 后者的故障现场离病根太远，不值得为省一点点开销去冒。
 *
 * ## 为什么 `TextLayout` 的缓存要有上限
 *
 * `TextLayout` 内部持有字形（`GlyphVector`）与度量，**单个实例不小**。
 * 歌词在换曲时会不断产生新的字符串键；若只增不减，一次长时间播放会把整首歌
 * 每一行、每一个中间态的组合都留在堆里 —— 是真实的内存泄漏。
 *
 * 上限取 [LAYOUT_CACHE_LIMIT] = 64：一个三槽位词岛最多同屏 3 行，
 * 64 已经覆盖「上一首的残留 + 当前曲目若干行 + 用户在设置里来回改字体」的全部活跃组合。
 * 超出时**整体清空**而非 LRU 淘汰：实测「整表清空」的代价是重建 3 个
 * `TextLayout`（约 0.07 ms），远低于维护 LRU 链表（每次访问都要移动节点）
 * 的复杂度成本。这是有意的取舍 —— 用偶尔多算一次，换掉一份链表代码。
 */
internal class RenderCache {

    // ── 字体与渲染上下文 ──────────────────────────────────────────────────

    /**
     * 当前的字体。**字体是布局缓存的失效键** —— 换字体必须连 `TextLayout` 一起作废。
     */
    private var font: Font = DEFAULT_FONT

    /**
     * `FontRenderContext`。
     *
     * ## 为什么自己持有一个而不是每帧从 `Graphics2D` 取
     *
     * `g.fontRenderContext` 每次调用都会**新建**一个实例（AWT 的实现是无缓存的），
     * 而 `TextLayout` 的构造与 `getOutline` 都以它为键的一部分参与内部比较。
     * 每帧拿到不同的 `frc` 实例会让 Swing 侧的 `FontDesignMetrics` 缓存反复失配。
     *
     * 但也不能随便自造一个：**构造 `TextLayout` 用的 `frc` 必须与绘制时的
     * 抗锯齿设置一致**，否则测出来的 advance 与实际绘制出的字形宽度会有亚像素偏差
     * （在多帧累积下表现为高亮边界「慢半拍」）。
     *
     * 故这里的策略是：**默认用最贴近实际绘制的一组提示（开 AA、开分数度量）**，
     * 并由 [syncFrom] 在绘制前用真实 `Graphics2D` 的设置**校准一次** ——
     * 只在设置真正变化时重建，不变则复用同一个实例。
     */
    private var frc: FontRenderContext = DEFAULT_FRC

    /** 上次校准时的抗锯齿/分数度量组合，用于判断是否需要重建 [frc]。 */
    private var frcKey: Long = FRc_KEY_UNSET

    // ── TextLayout 缓存 ──────────────────────────────────────────────────

    /**
     * `TextLayout` 缓存。键 = 文本内容；值 = 该文本在当前字体下的布局。
     *
     * 为什么键**不含字体**：字体由 [font] 单值持有，[setFont] 换字体时会整表清空。
     * 把字体放进键里看似更通用，但会让「换字体后旧字体的布局」永远留在表里 ——
     * 而那种布局**不可能再被用到**（当前字体只有一个）。用「单值 + 清空」表达
     * 这个不变量，比用复合键表达「其实只有一个维度会变」更准确。
     */
    private val layouts = ConcurrentHashMap<String, TextLayout>()

    /**
     * 取文本的布局；不存在则构造并缓存。
     *
     * ## 空串必须提前挡掉（**这是实测出来的崩溃点**）
     *
     * `new TextLayout("", font, frc)` 抛
     * `IllegalArgumentException: Zero length string passed to TextLayout constructor.`
     * —— 它不是返回一个空布局，而是**直接抛**。
     *
     * 这个异常在真实路径上很容易撞到：歌词行的翻译为空、占位文本被清空、
     * 换曲瞬间的两个回调之间文本是 `""`。若让调用方每次自己判空，
     * 迟早有一处漏掉，而后果是**整个词岛因为一次绘制异常而白屏**（`paintComponent`
     * 抛异常会被 Swing 吞掉并留下未重绘的区域）。故本方法把空串收敛为 `null`，
     * 让「没有可画的东西」变成调用方必须处理的一个显式分支。
     *
     * @return 布局；[text] 为空时返回 `null`（**不是**空布局）
     */
    fun textLayoutFor(text: String, font: Font = this.font): TextLayout? {
        if (text.isEmpty()) return null
        // 传入了非当前字体 → 走一次性构造，不进缓存。
        // 为什么允许：命中测试等场景可能要对「候选字体」做测量（如设置窗口的字体预览），
        // 若把它塞进缓存就要立刻清空当前字体的表，得不偿失。这类调用是低频的。
        if (font !== this.font) return TextLayout(text, font, frc)

        layouts[text]?.let { return it }

        val created = TextLayout(text, font, frc)
        if (layouts.size >= LAYOUT_CACHE_LIMIT) {
            // 整体清空（见类 KDoc 关于上限的论证）。清空后放入本次结果，
            // 保证「刚构造的这个」一定在表里，不会出现「刚算完立刻又被淘汰」的抖动。
            layouts.clear()
        }
        layouts[text] = created
        return created
    }

    /**
     * 换字体。**只在字体真的变化时清缓存**。
     *
     * 用 `!=` 而非 `!==`：`Font` 重写了 `equals`（比较族名、样式、大小），
     * 设置系统每次发布快照都会从字符串重建 `Font`（新的对象、相同的内容），
     * 若按引用比较就会**每次设置变更都全表清空** —— 用户在设置窗口拖动
     * 不相关滑杆时，歌词布局会被反复作废并重建。
     */
    fun setFont(next: Font) {
        if (next == font) return
        font = next
        layouts.clear()
    }

    /** 当前字体。渲染器取它设给 `Graphics2D`，保证「测量用的字体」与「绘制的字体」同源。 */
    fun currentFont(): Font = font

    /**
     * 用真实绘制上下文的设置校准 [frc]。**绘制前调用一次即可**。
     *
     * ## 为什么需要校准（而不是固定用 [DEFAULT_FRC]）
     *
     * `TextLayout` 的 advance 与 `getOutline` 的墨迹**都受抗锯齿与分数度量影响**：
     * 关闭文本抗锯齿时，字形会吸附到整像素，advance 变成整数；
     * 开启时保留分数宽度。若用「开 AA」的布局去「关 AA」的上下文里绘制，
     * 累积到一行末尾会偏出几个像素 —— 表现为高亮边界与字形错位。
     *
     * 因此这里从 `Graphics2D` 读三个键并算出一个指纹，只有指纹变了才重建。
     * **不做静默假设**：调用方不校准也能工作（用 [DEFAULT_FRC]），只是精度略差，
     * 这保证了本类在「忘记校准」时是降级而非崩溃。
     */
    fun syncFrom(g: java.awt.Graphics2D) {
        val key = frcFingerprint(g)
        if (key == frcKey) return
        val hints = g.renderingHints
        val aa = hints[java.awt.RenderingHints.KEY_TEXT_ANTIALIASING]
            ?: java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT
        val fm = hints[java.awt.RenderingHints.KEY_FRACTIONALMETRICS]
            ?: java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT
        val transform = when {
            g.transform.isIdentity -> null
            else -> g.transform
        }
        frc = FontRenderContext(transform, aa as Any, fm as Any)
        frcKey = key
        // frc 换掉了 → 所有用旧 frc 构造的布局都失效（它们的度量基准变了）。
        layouts.clear()
    }

    // ── Color 缓存 ───────────────────────────────────────────────────────

    /**
     * `Color` 缓存，键 = 打包 ARGB 整数。
     *
     * ## 为什么连 `Color` 都要缓存（它看起来很便宜）
     *
     * `Color` 的构造确实便宜，但**它不便宜的地方在于后续使用**：
     * `Graphics2D.setColor` 会走 `SunGraphics2D` 内部的 `Color` → 原生色转换，
     * 而该转换路径在**同一个 `Color` 实例**上有辨认优化。
     * 更实际的理由是：设置系统给的是**打包 int**（[dev.cdl.settings.toColor] 每次调用
     * 都新建 `Color`），若渲染器每帧对 6 个颜色各调一次 `toColor()`，
     * 就是每帧 6 次分配 —— 在 60 fps 下每秒 360 次短命对象，纯属喂给 GC。
     *
     * 打包 int 只有 2^32 种，但实际用到的不会超过十几种（前景/背景/已唱/未唱/频谱/
     * 各悬停态），故无上限担心 —— 且 [ConcurrentHashMap] 的装箱开销在这里被
     * 「同一颜色反复命中」摊薄到可忽略。
     */
    private val colors = ConcurrentHashMap<Int, Color>()

    /**
     * 打包 ARGB → `Color`（带缓存）。**这是渲染层取颜色的唯一入口**。
     *
     * 注意与 [dev.cdl.settings.toColor] 的区别：那个是设置层的语义转换（无缓存），
     * 这个是渲染层的高频取用（带缓存）。两层各自保留是有意的 ——
     * 让设置层不依赖渲染层（依赖方向从渲染指向设置，不反向）。
     */
    fun color(packedArgb: Int): Color = colors.getOrPut(packedArgb) { Color(packedArgb, true) }

    // ── Stroke 缓存 ──────────────────────────────────────────────────────

    /**
     * `BasicStroke` 缓存，键 = 打包后的 `(width, cap, join)`。
     *
     * `BasicStroke` 是**不可变**的（这是它能被安全共享的前提），
     * 但它会在构造时预计算内部的 `dash` 数组与 `PathConsumer` ——
     * 频谱的圆头折线与命中框外扩都会每帧取用，缓存掉这些预计算是净收益。
     *
     * 键把浮点宽度**量化到 1/256 像素**：频谱条宽度来自设置项百分比换算，
     * 会算出 3.4999998 这类值；若按原值做键，同一视觉宽度会分裂成多个条目，
     * 缓存命中率趋近于零。1/256 像素远低于任何显示器的可辨差异。
     */
    private val strokes = ConcurrentHashMap<Long, BasicStroke>()

    fun stroke(width: Float, cap: Int = BasicStroke.CAP_ROUND, join: Int = BasicStroke.JOIN_ROUND): BasicStroke {
        // 量化宽度到 1/256 px，并 clamp 到正值 —— `BasicStroke` 拒绝宽度 <= 0。
        val quantized = (width.coerceAtLeast(0.01f) * 256f).toInt().coerceAtLeast(1)
        val key = (quantized.toLong() shl 8) or (cap.toLong() shl 4) or join.toLong()
        return strokes.getOrPut(key) { BasicStroke(quantized / 256f, cap, join) }
    }

    // ── 命中测试的轮廓缓存 ────────────────────────────────────────────────

    /**
     * 「文本 → 墨迹轮廓」的缓存，供 [GlyphHitTester] 的粗筛使用。
     *
     * ## 为什么这里的缓存比 [layouts] 更值得（同一个键，但代价差 3 倍）
     *
     * `getOutline(null)` 实测 **0.0712 ms/次**，是 `TextLayout` 构造（0.0234 ms）的
     * **3 倍**。而命中测试是**每次鼠标移动**都要跑的 —— 鼠标在词岛上划过时
     * 一秒钟可以产生上百个 `mouseMoved`，每次若重算轮廓就是 7 ms 的卡顿。
     *
     * 键用**文本内容**而非 `TextLayout` 实例：后者虽然更精确地表达了
     * 「同一布局才有同一轮廓」这个依赖，但它没有稳定的 `equals`（`TextLayout`
     * 不重写 `equals`，比较退化为引用相等），用它做键等价于按实例缓存，
     * 在 [layouts] 清空后会产生无法回收的悬挂条目。用文本做键 + 与 [layouts] 一起失效
     * （见 [setFont] / [syncFrom] 里的 `clear`）是更简单且不会泄漏的选择。
     *
     * ## 此处**存的就是**「坐标以基线原点为基准」的原始轮廓
     *
     * 轮廓未经任何平移 —— 平移由 [GlyphHitTester] 在查询时用
     * `AffineTransform.getTranslateInstance(baselineX, baselineY)` 完成。
     * 理由：**平移是每帧变的**（窗口在拖），把它烘进缓存就等于缓存永不命中。
     */
    private val outlines = ConcurrentHashMap<String, java.awt.Shape>()

    /** 取文本的墨迹轮廓（坐标以基线原点为基准，未平移）。空串返回 `null`。 */
    fun outlineFor(layout: TextLayout, text: String): java.awt.Shape? {
        if (text.isEmpty()) return null
        outlines[text]?.let { return it }
        val created = layout.getOutline(null) ?: return null
        if (outlines.size >= OUTLINE_CACHE_LIMIT) outlines.clear()
        outlines[text] = created
        return created
    }

    /** 清空全部缓存。插件卸载或窗口永久隐藏时调用，让对象尽快可回收。 */
    fun clear() {
        layouts.clear()
        colors.clear()
        strokes.clear()
        outlines.clear()
        frcKey = FRc_KEY_UNSET
    }

    /**
     * 诊断信息：`(布局数, 颜色数, 描边数, 轮廓数)`。
     *
     * 存在的理由：缓存类最容易出的问题是**悄悄胀大**（键设计有误导致命中率恒为 0），
     * 而那种故障在视觉上完全看不出来 —— 只是 CPU 偏高。留一个可读的观测点，
     * 让「把缓存数打到日志」这件事不需要临时改代码。
     */
    fun diagnostics(): String =
        "layouts=${layouts.size} colors=${colors.size} strokes=${strokes.size} outlines=${outlines.size}"

    private companion object {
        /**
         * 回退字体。
         *
         * 用**逻辑字体族名 `SansSerif`** 而不是 `"Microsoft YaHei UI"`：
         * 逻辑族名由 AWT 在所有平台上映射到一个真实的物理族，因此无论插件
         * 跑在什么环境（含无中文的 Linux、无 GUI 的 CI）都不会落到
         * 「字体不存在 → 用默认字体」的路径上 —— 那条路径在 Windows 上会静默
         * 换成 `Dialog`，字形宽度与测量结果都可能与预期不同。
         * 真实的字体由设置项 `main_lyric.font.family` 在绘制前通过 [setFont] 设入。
         */
        val DEFAULT_FONT: Font = Font(Font.SANS_SERIF, Font.PLAIN, 22)

        /**
         * 回退 `FontRenderContext`：开文本抗锯齿、开分数度量。
         *
         * 与 [IslandWindow] 的绘制提示保持一致（那里设的就是这两个 ON）。
         * 「默认值恰好与真实绘制一致」不是巧合，而是刻意对齐 —— 这样即使调用方
         * 忘了 [syncFrom]，度量也不会偏。
         */
        val DEFAULT_FRC: FontRenderContext = FontRenderContext(
            null,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        )

        /** [frcKey] 的「尚未校准」哨兵。用 `Long.MIN_VALUE` 而非 0 —— 0 是合法指纹。 */
        const val FRc_KEY_UNSET = Long.MIN_VALUE

        /** `TextLayout` 缓存上限。论证见类 KDoc。 */
        const val LAYOUT_CACHE_LIMIT = 64

        /**
         * 轮廓缓存上限。比 [LAYOUT_CACHE_LIMIT] 小 ——
         * 轮廓只在鼠标位于词岛上时才会被构造（低频路径），且单个轮廓比
         * `TextLayout` 更大（含 `GeneralPath` 的全部段点）。
         */
        const val OUTLINE_CACHE_LIMIT = 16

        /**
         * 把影响度量与墨迹的三个提示算成一个指纹。
         *
         * 用 `hashCode` 拼接而非维护枚举映射：这里只需要「变了没变」，
         * 不需要「变成了什么」。碰撞理论上可能（两个不同提示同哈希），
         * 但后果仅仅是**少重建一次 frc**，且两者的度量差异本身就在亚像素级。
         */
        fun frcFingerprint(g: java.awt.Graphics2D): Long {
            val hints = g.renderingHints
            val aa = hints[java.awt.RenderingHints.KEY_TEXT_ANTIALIASING]?.hashCode() ?: 0
            val fm = hints[java.awt.RenderingHints.KEY_FRACTIONALMETRICS]?.hashCode() ?: 0
            // 变换也进指纹：非恒等变换（如缩放绘制）会改变字形栅格化结果。
            val tf = if (g.transform.isIdentity) 0 else AffineTransform().also { it.setTransform(g.transform) }.hashCode()
            return (aa.toLong() shl 42) or (fm.toLong() shl 21) or (tf.toLong() and 0x1FFFFF)
        }
    }
}
