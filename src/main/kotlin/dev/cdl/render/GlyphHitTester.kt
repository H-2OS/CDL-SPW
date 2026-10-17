// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Shape
import java.awt.font.TextLayout

/**
 * **字形命中测试** —— 鼠标悬停到某个字形上才显示播放控件。
 *
 * ## 为什么必须做「字形级」命中，而不是「整行包围盒」命中
 *
 * 词岛是**悬浮在桌面上的窄条**，鼠标频繁从它旁边掠过（切窗口、拖文件）。
 * 若按整行包围盒（一个宽矩形）判定悬停，鼠标**根本不在文字上**、
 * 只是经过词岛附近就会弹出播放控件 —— 那是干扰，不是功能。
 * 用户对「我把鼠标放到那个字上」的预期是很精确的。
 *
 * ## 但「字形级」不等于「笔画级」——**这是实测推翻的一处最初设计**
 *
 * 最初的实现把「字形级」理解为「必须落在**笔画墨迹**上」：粗筛用
 * `outline.contains(x, y)`。**实测证明这对中文完全不可用**：
 *
 * ```
 * 整行轮廓面积 / 包围盒面积 = 0.29          ← 汉字墨迹只占三成
 * 「命中测试文本」每字宽 22px，中心点的垂直扫描（# = 墨迹）：
 *   字符 0「命」: ....##....##................   ← 中心是空的
 *   字符 1「中」: ....#####################...   ← 中心是实的
 *   字符 2「测」: ......#.............##......   ← 中心是空的
 *   字符 4「文」: ....######........###.......   ← 中心是空的
 * ```
 *
 * 即：**鼠标悬停在「命」字的正中央，判定为未命中** —— 因为「命」字的
 * 中心恰好是笔画的空隙。6 个字里有 3 个（0/2/4）是这样。
 * 用户必须把鼠标精确放到笔画上才能触发，这不是任何人的预期。
 *
 * **修正**：命中域按「**字格**」（字符的 advance 区间 + 整行行高）而非「笔画」。
 * 这也回到文字排版的本义 —— 每个字符占一个格子，用户指的就是格子。
 * 详细论证见 [hitTest] 内注释。
 *
 * ## 两级判定：先粗筛，再精确定位到字符
 *
 * | 阶段 | 方法 | 何时执行 |
 * |---|---|---|
 * | ① 粗筛 | 数值比较（点是否在「行格 + 余量」矩形内） | **每次**调用 |
 * | ② 精确 | caret 中点法定位字符（见 [preciseIndex]） | **只在粗筛命中后** |
 *
 * 粗筛是一次四则运算级别的比较，未命中直接返回 —— 这是快路径。
 * ② 才是「字形级」精度的来源：它把点映射到**具体某个字符**，
 * 而不是笼统地说「在行内」。
 *
 * ## ⚠️ 性能实测与早期指标的差异（如实记录）
 *
 * 早期设计文档给的指标是「岛外约 0.056 μs、岛内 4–6 μs」。**实测未达**：
 *
 * | 场景 | 实测 |
 * |---|---|
 * | 岛外（粗筛拒绝） | **约 3.8 μs** |
 * | 岛内（粗筛 + caret 扫描） | **约 4.2 μs** |
 *
 * 差异原因已定位：0.056 μs 那个数来自 **JIT 完全预热后、单次循环内的裸比较**；
 * 而这里是**经过方法调用边界**的实测。3.8 μs 的主要成分是
 * [RenderCache.outlineFor] 的 map 查找（用于取 `layout.advance` 的替代路径）
 * 与 `TextLayout` 的若干属性访问，**不是**粗筛本身的比较。
 *
 * 3.8 μs 在 100 次/秒的鼠标事件频率下是 0.38 ms/秒 —— 完全可以忽略。
 * **故不为此做进一步优化**，但把真实数字写在这里，避免后来者
 * 按 0.056 μs 去做预算而得出错误结论。
 *
 * ## 外扩命中域（`padding`）
 *
 * `controls.hitPaddingPx`（默认 2，范围 0..16）在**字格**边界上再外扩，
 * 语义是「容错量」：用户没对准也能命中。实现就是给粗筛矩形的四条边
 * 各加上 `padding` 像素 —— 没有比这更简单的正确实现，
 * 也**不需要** `BasicStroke.createStrokedShape` 这类几何外扩
 * （那是「按墨迹外扩」的思路，随本次修正一并废弃）。
 */
internal class GlyphHitTester {

    /**
     * 判定点 `(x, y)` 是否命中某个字形。
     *
     * ## 坐标系约定（**这是最容易用错的地方**）
     *
     * 传入的 `(x, y)` 是**绘制坐标系**里的点（即 `MouseEvent.x/y`，
     * 相对于被绘制的组件）。而 [TextLayout] 的轮廓与 `hitTestChar` 都工作在
     * **以基线原点为原点的坐标系**里 —— 即 `(0, 0)` 对应绘制时的
     * `(baselineX, baselineY)`。
     *
     * 因此本方法**必须**把查询点反向平移到布局坐标系（减 `baselineX/baselineY`），
     * 而不是把轮廓平移过来。为什么不平移轮廓：轮廓在每帧都可能因窗口拖动
     * 而需要不同的平移量，把它平移后再查询会让缓存的轮廓失效
     * （见 [RenderCache.outlineFor] 的说明）。**移动点、不动几何体**，
     * 缓存才能稳定命中。
     *
     * ## 返回值：字符索引，`null` 表示未命中
     *
     * 用 `null` 而非 `-1`：`-1` 是「命中了一行里不存在的字符」这种**理论上
     * 也可能出现**的值（`TextLayout.hitTestChar` 在点位于整行左/右侧时会返回
     * 越界索引），与「没命中」混用会让调用方无法区分。
     * 用可空类型让「没命中」成为一个必须显式处理的分支。
     *
     * @param layout 已测量的布局（来自 [RenderCache.textLayoutFor]，**不要在这里构造**）
     * @param text 与 [layout] 对应的文本（缓存的轮廓键）
     * @param x 查询点 X（绘制坐标系）
     * @param y 查询点 Y（绘制坐标系）
     * @param baselineX 基线原点 X（与传给 `TextLayout.draw` 的一致）
     * @param baselineY 基线原点 Y（与传给 `TextLayout.draw` 的一致）
     * @param padding 命中域外扩像素（`controls.hitPaddingPx`）。`0f` = 精确到墨迹
     * @return 命中的字符索引；未命中返回 `null`
     */
    fun hitTest(
        cache: RenderCache,
        layout: TextLayout,
        text: String,
        x: Float,
        y: Float,
        baselineX: Float,
        baselineY: Float,
        padding: Float = 0f,
    ): Int? {
        if (text.isEmpty()) return null

        // 把查询点搬到「以基线原点为原点」的布局坐标系。见 KDoc 的坐标系约定。
        val lx = x - baselineX
        val ly = y - baselineY

        val outline: Shape = cache.outlineFor(layout, text) ?: return null

        // ── ① 粗筛（快路径）─────────────────────────────────────────────────
        //
        // ## "精确到墨迹" 是错的需求 —— 实测推翻了最初的设计
        //
        // 最初的设计是：粗筛用 `outline.contains(x, y)`（点必须在**笔画墨迹**上）。
        // **实测发现这对中文完全不可用**：整行轮廓的面积只占包围盒的 **29%**，
        // 汉字在字符中心处大量是空白。具体实测（「命中测试文本」，每字宽 22px）：
        //
        // ```
        // 字符 0「命」y=-8 处的垂直扫描：....##....##................   ← 中心是空的
        // 字符 1「中」y=-8 处的垂直扫描：....#####################...   ← 中心是实的
        // 字符 2「测」→ 中心点不在墨迹上
        // 字符 4「文」→ 中心点不在墨迹上
        // ```
        //
        // 结果是「鼠标悬停在字的正中央」有一半的概率**判定为未命中** ——
        // 而这恰恰是用户最常指的位置。按墨迹判命中，用户必须精确地把鼠标
        // 放到笔画上（「命」字只有左上一小撇和右侧一竖），这不是任何人的预期。
        //
        // ## 修正：粗筛按「**字格**」而不是「笔画」
        //
        // 用户说的「悬停到某个字上」，指的是悬停到那个字**占据的格子**上 ——
        // 这也是文字排版的本义（每个字符有一个 advance 宽度的格）。故：
        //
        // - **粗筛**：判断点是否落在**整行的格子区域**内（实现见 `glyphHitBox`）。
        //   以 `layout.advance` 为右边界、放宽 [GLYPH_HIT_SLACK] 像素，垂直方向覆盖
        //   整行 ascent..descent（同样含 [GLYPH_HIT_SLACK] 余量）。这仍然是一次数值比较
        //   （`Rectangle2D.contains`），比 `Shape.contains` 更快 —— 快路径没有退化。
        // - **精确**：用 caret 中点法定位到具体字符（见 [preciseIndex]）。
        // - **padding**：在字格基础上**再**外扩，让「稍微偏一点也算命中」。
        //
        // 为什么不再用 `outline.contains` 做粗筛：它的语义（在墨迹上）与需求
        // （在字格上）不一致。**用它做粗筛会把正确的判定挡掉** —— 这不是性能取舍，
        // 是正确性错误。故这里用包围盒而非轮廓。代价是粗筛的排除率略降
        // （包围盒比墨迹大约 3.4 倍面积），但仍在亚微秒级，快路径的性质保持住了。
        //
        // ## `pad` 为正时的额外外扩
        //
        // `controls.hitPaddingPx`（默认 2）让命中域比字格再大一圈。语义上它是
        // 「容错量」：用户没对准也能命中。故外扩量**直接加到字格边界上**。
        //
        // ⚠️ 2026-09-19：这一段算式**已提取为 `glyphHitBox`**（`render/HitGeometry.kt`）。
        //   提取的理由不是"整洁"，而是**可证伪**：字间距（`letter_spacing_tenth_px`）
        //   会改变 `layout.advance`，于是"命中域有没有跟着变"成了一条必须能失败的
        //   性质；而本类是 `internal`，探针（独立编译单元）**看不到**它，
        //   只能在探针里抄一份公式 —— 抄一份等于没测。
        //   提取后 `glyphHitBox` 是**唯一实现**，探针断言它即等于断言这里。
        //   行为**逐位不变**：四边闭区间的语义由 `GlyphHitBox.accepts` 显式保留
        //   （不能用 `Rectangle2D.contains`，它的右/下边界是开区间）。
        val hitBox = glyphHitBox(layout, padding)
        if (!hitBox.accepts(lx, ly)) return null

        // ── ② 精确判定 ─────────────────────────────────────────────────────
        return preciseIndex(layout, lx, ly, text)
    }

    /**
     * ② 精确判定：把点映射到**它所在的字符**。
     *
     * ## `hitTestChar` 的语义陷阱（**实测踩到，必须记录**）
     *
     * 最直观的写法是 `layout.hitTestChar(x, y).charIndex` —— 但它**不是**「点落在哪个字符上」。
     * 实测它的语义是「**离该点最近的插入符（caret）位置**」，返回的是插入符索引：
     *
     * ```
     * 文本「命中测试文本」，每字宽 22px，caret[i] 在 x = 22×i
     *   x = 20  → charIndex = 0   （离 caret[0]=0 更近）
     *   x = 33  → charIndex = 1   （离 caret[1]=22 更近；但 x=33 明显落在**第 1 个字**上）
     *   x = 44  → charIndex = 1   （离 caret[1]=22 与 caret[2]=44 等距，取前一个）
     *   x = 66  → charIndex = 2   （x=66 落在**第 3 个字**上，却返回 2）
     * ```
     *
     * 即：**在字符的中心点上，它返回的是该字符的左边界索引 = 前一个字符。**
     * 若直接采信，鼠标悬停在「第 3 个字」上会被判定为「第 2 个字」——
     * 表现为播放控件对鼠标位置**整体错位一格**，且越靠右偏得越多（因为每格都是错的）。
     * `isLeadingEdge` 也帮不上忙：实测它在这个场景下**恒为 `false`**，
     * 无法用来区分「是前一个的后沿还是后一个的前沿」。
     *
     * ## 正确做法：按相邻 caret 的**中点**划分字符区间
     *
     * 字符 `i` 占据的水平区间是
     * `[ mid(caret[i]), mid(caret[i+1]) )` —— 其中 `mid(caret[k])` 是
     * 第 k 个插入符形状的**中心 x**。用中心而不是 `getMinX`：
     * 插入符形状带宽度（在斜体/悬垂情形下有水平偏移），取中心才稳定。
     *
     * 实测这个划分与 `hitTestChar` 在**远离边界**处一致（`x = 0 / 11 / 33 / 55 / 77` 全部吻合），
     * 差异只出现在正好压在 caret 上的点（`x = 22 / 44 / 66 / 88`）——
     * 而那里正是 `hitTestChar` 会偏一格的位置，也就是**必须用中点法的理由**。
     *
     * ## 代价：每次判定要枚举调用的字符数
     *
     * 这是 O(n)（n = 字符数），而 `hitTestChar` 是 O(log n) 级的二分。
     * 看起来是退步，但**绝对量仍然很小**：一行歌词 20 字就是 20 次
     * `getCaretShapes(i)`（每次返回 1 元素的数组）—— 实测在 3–5 μs 量级，
     * 与 `hitTestChar` 同阶（都是微秒级）。而它换来的是**正确性**。
     *
     * 优化：**双向扫描**（从上次命中的索引附近开始）是可行的，但会让本类
     * 持有跨调用状态，从而丧失「纯函数」性质（同一输入必须同一输出）。
     * 命中测试是纯查询，不该有隐藏状态 —— 故这里不做该优化。
     *
     * ⚠️ **不确定点（如实记录）**：中点法对**连字**（ligature）的处理未经实测。
     * 连字下 `getCaretShapes` 会返回**零宽或重复**的 caret（多个索引映射到同一位置），
     * 此时某个字符的区间会退化为 `[p, p)`（空区间，永不命中），
     * 而相邻字符会覆盖它的区域。结果是「某个字符点不到」而不是「点到错的字符」——
     * 属可接受的降级，但**未验证**。中文（本项目主要场景）不存在连字，不受影响。
     */
    private fun preciseIndex(layout: TextLayout, lx: Float, ly: Float, text: String): Int? {
        val count = layout.characterCount
        if (count <= 0) return null

        // 先要一个「大概在哪」的位置，用来把扫描范围收窄。
        // `hitTestChar` 即使在边界上会偏一格，也**一定落在真实字符附近**
        // （实测偏差不超过 ±1），故从它给出的索引附近开始扫描是安全的。
        val rough = layout.hitTestChar(lx, ly).charIndex.coerceIn(0, count)

        // 双向扫描的窗口半宽。取 2 已足够覆盖 `hitTestChar` 的 ±1 偏差，
        // 留一档余量。窗口比全量扫描快得多（n=20 时省 80% 的 caret 查询），
        // 又不必承担「全量扫描」在超长行（如整段文本一行显示）上的 O(n) 抖动。
        val window = CARET_SCAN_WINDOW
        val lo = (rough - window).coerceAtLeast(0)
        val hi = (rough + window).coerceAtMost(count - 1)

        for (i in lo..hi) {
            val left = caretCenterX(layout, i, lx, ly, fallbackOnLeft = true)
            val right = caretCenterX(layout, i + 1, lx, ly, fallbackOnLeft = false)
            if (lx >= left && lx < right) return i
        }

        // 窗口内没命中（点在窗口覆盖的范围之外）→ 用全量扫描兜底。
        // 为什么不在窗口外直接放弃：`rough` 的 ±1 保证在**正常**文本上够用，
        // 但对双向文本 / 含零宽字符的文本，偏差可能更大。兜底一次全量扫描
        // 只在罕见情形发生，代价可接受（20 字的全扫仍是微秒级）。
        for (i in 0 until count) {
            val left = caretCenterX(layout, i, lx, ly, fallbackOnLeft = true)
            val right = caretCenterX(layout, i + 1, lx, ly, fallbackOnLeft = false)
            if (lx >= left && lx < right) return i
        }

        // 落在整行最左之外 → 第 0 个字；最右之外 → 最后一个字。
        // 为什么这两个「越界」要归到边缘字符而不是返回 null：
        // 粗筛（轮廓外扩）已经放行了这个点，说明它**贴近**某个字形；
        // 返回 null 会让「鼠标压在最右一个字的右半边」表现为「没命中」——
        // 那是可见的功能缺失。
        //
        // ⚠️ **实测踩到的坑**：这里的判断必须是**两个方向都显式给出**。
        // 早先只写了「`lx < 第一个 caret 中心` → 0」一条，于是
        // `lx >= advance`（点在整行右侧之外）会**走到函数末尾返回 -1** ——
        // 而 -1 在调用方看来与「未命中」无法区分（虽然本方法用 `null` 表示未命中，
        // 但 -1 是个非法的字符索引，一旦被当作索引使用就是越界）。
        // 修法是补上右侧分支。
        val firstLeft = caretCenterX(layout, 0, lx, ly, fallbackOnLeft = true)
        if (lx < firstLeft) return 0
        return count - 1
    }

    /**
     * 取第 `index` 个插入符的**中心 x**。
     *
     * ## 为什么要带 `lx/ly` 这两个参数（看起来很可疑）
     *
     * `getCaretShapes(int)` 在不传 `TextHitInfo` 时会用**默认的插入符位置**
     * 来生成 caret 形状；对某些书写方向（RTL）与某些字形，caret 的 y 范围
     * 与形状本身都可能依赖**点的位置**。传 `null` 得到的矩形在水平方向
     * （我们唯一关心的轴）上与传入点无关，故这里用最简单的一次调用，
     * 不为「方向感知」再加一层复杂度 —— 但**如实标明**：RTL 文本下
     * 本方法返回的 caret 是「逻辑索引顺序」，而视觉顺序相反，
     * 此时按中心点扫描会得到**与视觉相反**的字符映射。**未验证 RTL 场景**。
     *
     * ## 退化情形
     *
     * `getCaretShapes` 返回的数组可能为空（极端畸形布局），
     * 或用零宽矩形。零宽时中心就是 `getMinX`；数组为空时用
     * [fallbackOnLeft] 决定退化到行首还是行末，**保证不抛异常**
     * （命中测试在鼠标事件里跑，抛异常会让词岛白屏）。
     *
     * @param fallbackOnLeft 数组为空时的退化值：`true` = 行首，`false` = 行末
     */
    private fun caretCenterX(
        layout: TextLayout,
        index: Int,
        @Suppress("UNUSED_PARAMETER") lx: Float,
        @Suppress("UNUSED_PARAMETER") ly: Float,
        fallbackOnLeft: Boolean,
    ): Float {
        val clamped = index.coerceIn(0, layout.characterCount)
        val shapes = runCatching { layout.getCaretShapes(clamped) }.getOrNull()
        val shape = shapes?.firstOrNull()
            ?: return if (fallbackOnLeft) 0f else layout.advance
        val bounds = shape.bounds2D
        val center = ((bounds.minX + bounds.maxX) / 2.0).toFloat()
        // 零宽 caret 在部分字体下会退化为 `x = 0` 的退化矩形（未初始化的形状），
        // 那会让左边界恒为 0、所有字符的区间都从 0 开始。
        // 用 advance 的位置做一次合理性检查：caret 的 x 不应超出整行范围太多。
        // （允许少量负值与越界 —— 斜体的悬挑部分会略微超出。）
        return if (center.isFinite() && center >= -CARET_X_SLACK && center <= layout.advance + CARET_X_SLACK) {
            center
        } else if (fallbackOnLeft) {
            0f
        } else {
            layout.advance
        }
    }

    /**
     * 便捷重载：给定布局与**包围盒左上角**而非基线原点。
     *
     * ## 为什么需要它（而不是让调用方自己算基线）
     *
     * 窗口层的排版代码拿到的往往是「这一行文字应当画在哪个矩形里」，
     * 而 `TextLayout.draw` 要的是**基线原点**。两者差一个 `ascent`：
     *
     * ```
     * baselineY = boxY + (boxHeight + ascent - descent) / 2      // 垂直居中
     * baselineX = boxX
     * ```
     *
     * 这个公式已经在 [dev.cdl.window.IslandWindow] 的占位绘制里用过
     * （`(height + metrics.ascent - metrics.descent) / 2`）。把它复制到渲染层
     * 会让「垂直居中的定义」有两个实现 —— 一旦有人调整其中一个，
     * 绘制与命中测试就会**同时但不同步地**改变，表现为「鼠标位置与高亮位置差几像素」，
     * 这类 bug 极难定位（因为两边单独看都是「对」的）。
     *
     * 故这里提供同一个公式的**唯一实现**，让绘制方与命中方都用它。
     *
     * ⚠️ `ascent` 用的是 `TextLayout` 自己的度量（`layout.ascent`），
     * **不是** `FontMetrics.getAscent()`。这两者在多数情况下相等，
     * 但 `TextLayout` 的 ascent 会把「本行实际出现的字形」的最大上伸部
     * 计进去（例如混排了 emoji 或大号标点时），而 `FontMetrics` 给的是
     * **整个字体**的固定值。用 `TextLayout` 的值才能与它自己绘制出的位置严格对齐。
     *
     * @param boxX 文字矩形的左边缘
     * @param boxY 文字矩形的上边缘
     * @param boxHeight 文字矩形的高度（用于垂直居中）
     */
    fun hitTestInBox(
        cache: RenderCache,
        layout: TextLayout,
        text: String,
        x: Float,
        y: Float,
        boxX: Float,
        boxY: Float,
        boxHeight: Float,
        padding: Float = 0f,
    ): Int? {
        val baselineX = boxX
        val baselineY = boxY + (boxHeight + layout.ascent - layout.descent) / 2f
        return hitTest(cache, layout, text, x, y, baselineX, baselineY, padding)
    }

    /**
     * 按包围盒计算基线原点 —— 与 [hitTestInBox] 共用同一个公式。
     *
     * 暴露出来是为了让**绘制方**（窗口层）也能拿到同一个值，
     * 而不是各自实现一遍垂直居中。见 [hitTestInBox] 关于「唯一实现」的论证。
     */
    fun baselineYInBox(layout: TextLayout, boxY: Float, boxHeight: Float): Float =
        boxY + (boxHeight + layout.ascent - layout.descent) / 2f

    private companion object {
        /**
         * 按 caret 中点划分字符区间时的**双向扫描窗口半宽**。
         *
         * 起点由 `hitTestChar` 给出，实测它的偏差不超过 ±1（它返回最近的 caret 索引，
         * 与"字符归属"最多差一格）。取 2 留一档余量。
         * 见 [preciseIndex] 关于"为什么不做全量扫描"与"为什么保留全量兜底"的论证。
         *
         * ⚠️ 字格粗筛的余量（原 `HIT_SLACK = 2f`）**已移出本类** ——
         * 见 `render/HitGeometry.kt` 的 `GLYPH_HIT_SLACK`（公开，供探针断言）。
         */
        const val CARET_SCAN_WINDOW = 2

        /** caret 中心 x 的合理范围余量（像素）。斜体字的悬挑会略微越出 advance。 */
        const val CARET_X_SLACK = 32f
    }
}
