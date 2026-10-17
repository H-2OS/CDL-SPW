// SPDX-License-Identifier: AGPL-3.0-only
//
// 控件带（播放控件条）在**动画期间**的绘制几何 —— **唯一计算点**。
//
// ## 为什么单独一个文件
//
// 与 `SpectrumAlphas.kt` 同一条理由：它是**纯函数 + 纯数据**（不碰 `Graphics2D`、
// 不读窗口状态），而 `IslandWindow.kt` 是 10286 行的窗口实现。
// 抽到顶层之后：① `probe` 源集可以直接断言它（探针看不到 `private`，
// 故"要验证的公式"必须落在**公开顶层**）；② 绘制侧与窗口侧共用同一份算术，
// 不会出现"窗口按一种算法摆、形状按另一种画"。
//
// ## 它修的是什么缺陷（BUG-123 的**后半截**，2026-09-22）
//
// 用户原话（第三次报同一件事）：
//
// > 「**上方控件的弹出收回动画时间内词岛的上下闪动依旧，能不能彻底根治它？**」
//
// ### 前半截（已修，见 `IslandWindow.applyWindowSize` 的注释）
//
// `setLocation` + `setSize` 是两次 OS 调用，中间必然有一帧"只改了一半" ⇒ 闪。
// 已用**一次** `setBounds` 消除。
//
// ### 后半截（**本文件**）
//
// 那次修复只保证了"窗口尺寸只变一次"，却**没有让"画出来的岛体"跟着连续变化**：
//
// ```
// 展开那一帧：窗口高度 base → base + strip（一次 setBounds）
//             而 `buildShape(local, width, height)` 用的 height **就是窗口高度**
//             ⇒ 岛体**在那一帧瞬间长高 strip px**
//             随后 180 ms 里只有控件条的 alpha 在变 ⇒ 岛体**再也不动**
// ```
//
// 即：`strip` px（真机 ≈ 76 px）的高度变化**整体落在动画之外**。
// 用户看到的是「词岛"啪"地跳一格，然后控件慢慢淡入」——
// 而 `bandFraction` 这个"逐帧的带子占比"字段**从未被任何绘制代码读过**
// （只有一处诊断计数器用它）。这正是本项目反复栽过的形态：
// **改了 A，但 A 的输入被 B 掐断** —— 结构接了一半，剩下的一半靠"看起来差不多"蒙过去。
//
// ### 本文件给出的答案
//
// 把"动画期间的岛体几何"抽成一个**纯函数**，让**窗口尺寸**与**绘制几何**共用它：
//
// | 量 | 谁决定 | 动画期间 |
// | --- | --- | --- |
// | 窗口 bounds | `applyWindowSize`（每次过渡**恰好一次**） | **不变** |
// | 岛体绘制矩形 | [bandGeometry]（逐帧，随 [BandGeometry.islandTop] / [BandGeometry.islandHeight]） | **连续变化** |
//
// 于是窗口"提前到位"不再可见：窗口多出来的那一段在 `fraction = 0` 时
// **完全落在岛体形状之外**（形状还没长到那里），而形状随后连续长过去。
//
// ## 三条不变量（探针逐一断言，见 `AnimationProbe` §11）
//
// ① **锚点边固定**：`position = TOP` 时岛体**下边**逐像素固定（歌词不下移）；
//    `position = BOTTOM` 时岛体**上边**逐像素固定。这对应 `IslandWindow` 里
//    `anchoredWindowY` 保证的"歌词屏幕位置不变"。
// ② **单调**：`fraction` 增大时岛体高度**单调不减**、移动边**单调单向**。
//    中间回跳会让用户看到"带子长到一半又缩回去"。
// ③ **两端精确**：`fraction = 0` → 岛体 == 收起态（逐像素）；
//    `fraction = 1` → 岛体 == 展开态 == **整个窗口**（与改动前的静止观感一致）。
//
// ## 取整：`roundToInt` 而**不是**截断，且只取一次
//
// `stripHeight` 是 `Float`（含 `controls.gapPx` 这个滑杆量），而窗口尺寸是 `Int`。
// 若绘制用 Float、窗口用截断，两者会差最多 1 px ⇒ `fraction = 1` 时
// 形状比窗口**高 1 px**（被窗口裁掉一条边）或**矮 1 px**（岛体与窗口之间留一条缝）。
// 故：**先把 `stripHeight` 量化一次**，窗口与绘制都用这个量化值；
// 高度用 `roundToInt`（截断会让 `0.9 px` 的变化整整晚一帧才出现）。

package dev.cdl.window

import java.awt.geom.Rectangle2D
import kotlin.math.roundToInt

/**
 * 控件带占比的**两个方向合成** —— `IslandWindow.bandFractionNow` 的**唯一**算术定义点。
 *
 * ## 为什么要抽出来
 *
 * 控件带由**两个进度源**推进，而它们的语义完全不同：
 *
 * | 方向 | 进度源 | 谁推进 |
 * | --- | --- | --- |
 * | **展开** | `HoverState.expansion` | `HoverState` 的定时器（按 `FADE_IN_MS`） |
 * | **收起** | `IslandWindow.bandFraction` | `IslandWindow.advanceBandCollapse`（帧循环） |
 *
 * 合成规则取 `max`（只在展开方向）：若按"谁在跑用谁"，那么
 * 「展开播到一半（0.4）→ 鼠标移开 → 又移回来」这条路径上，新起的展开进度从 **0**
 * 开始，带子会**先塌到 0 再长起来**（一次可见的抖动）。
 * 取 `max` 则天然连续：展开方向只会让值变大，收起方向只会让它变小，
 * 两个方向不可能同时反向 ⇒ 合成值**单调**。
 *
 * ## 为什么必须能被探针断言
 *
 * "单调"是**可判定的性质**，而它的失效形态（中途回跳）在真机上表现为
 * 「词岛闪一下」—— 不报错、不崩，只是看起来不对。故它必须落在**公开顶层纯函数**上，
 * 让 `AnimationProbe` §11 能喂一整个"展开 → 打断 → 收起"序列并逐帧断言。
 *
 * @param expanded 当前是否处于展开态（`HoverState.expanded`）。
 * @param expansion 展开方向的进度（`HoverState.expansion`，`0f..1f`）。
 * @param bandFraction 收起方向的进度（`0f..1f`）。
 */
fun bandFractionCombined(expanded: Boolean, expansion: Float, bandFraction: Float): Float =
    if (expanded) maxOf(bandFraction, expansion) else bandFraction

/**
 * 收缩动画某一帧的占比。
 *
 * ## 为什么起点是**参数**而不是恒为 `1`
 *
 * 原实现写的是 `1f - eased(t)` —— 即**无条件从 1 开始收**。
 * 正常路径（展开跑完再离开）下起始值确实是 1，看不出问题；
 * 但「展开播到一半（比如 0.4）就移开鼠标」时，下一帧带子会
 * **从 0.4 跳到 1.0** 再往回收 —— 用户看到的正是
 * 「**词岛先"啪"地长到最大、再缩回去**」。
 * 那是"高度不单调、中途回跳"，属于本项目"上下闪动"这一族的**第二种形态**。
 *
 * @param from 收缩开始那一刻的占比（`IslandWindow.bandCollapseFrom`）。
 * @param easedT 已缓动的进度（`0f..1f`）。`0` → 返回 `from`（**首帧连续**），
 *   `1` → 返回 `0`（收完）。
 */
fun bandCollapseFraction(from: Float, easedT: Float): Float =
    from.coerceIn(0f, 1f) * (1f - easedT.coerceIn(0f, 1f))

/**
 * 控件带动画期间，词岛面板里**各个矩形的唯一权威几何**（全部是**面板坐标**）。
 *
 * @property islandTop 岛体（背景形状 + 边框）的上边缘。**整数** ——
 *   锚点边必须逐像素固定，留小数会让描边在相邻两帧落在不同的像素行上（看起来"抖"）。
 * @property islandHeight 岛体的绘制高度。**整数**（理由见 [bandGeometry]）。
 * @property stripTop 控件带的上边缘。**与动画进度无关** —— 带内布局必须静止，
 *   否则按钮会在动画期间"滑动"，与 `MOTION` 的位移叠加成两段运动。
 * @property stripHeight 控件带高度。**与动画进度无关** —— 它就是布局占位。
 * @property lyricsTop 歌词区上边缘。**与动画进度无关**（用户明确要求动画期间歌词不动）。
 * @property lyricsHeight 歌词区高度。**与动画进度无关**。
 */
data class BandGeometry(
    val islandTop: Float,
    val islandHeight: Int,
    val stripTop: Float,
    val stripHeight: Float,
    val lyricsTop: Float,
    val lyricsHeight: Float,
    /**
     * 岛体的**绘制宽度** —— 随进度从收起态宽连续长到展开态宽（"词岛长大"）。
     *
     * ⚠️ 它与**窗口宽**不是同一个量：窗口恒为展开态宽（[expandWidthGrowPx]），
     * 岛体在窗口内**居中**（见 [islandLeft]）。故收起态下岛体两侧各有一段
     * **永久透明**的预留区 —— 与纵向那条预留带同源。
     */
    val islandWidth: Int = 0,
    /** 岛体**绘制左边缘**（面板坐标）—— 窗口恒宽后岛体在窗口内居中。 */
    val islandLeft: Float = 0f,
    /** 本帧的展开进度（已夹到 `0f..1f`）—— 形状圆角 / 封面几何的插值都用它。 */
    val fraction: Float = 0f,
    /** 展开态相对收起态的**横向**增量（px，已量化成偶数）—— 见 [expandWidthGrowPx]。 */
    val widthGrowPx: Int = 0,
) {
    /** 岛体的下边缘（= [islandTop] + [islandHeight]）。 */
    val islandBottom: Float get() = islandTop + islandHeight

    /** 岛体的右边缘（面板坐标）。 */
    val islandRight: Float get() = islandLeft + islandWidth

    /** 面板的**完整**高度（= 带子外端到岛体另一端的距离）。 */
    val panelHeight: Float get() = if (stripTop == 0f) stripHeight + islandHeight else islandTop + islandHeight

    // ── 封面几何（用户第 ③ 条：封面在词岛生长时不得与控件/歌词重叠）────────────
    //
    // ## 为什么封面几何也住在这里（而不是留在 `IslandWindow.drawCover` 里各算一套）
    //
    // 与 `stripTop` / `lyricsTop` 同一条理由：**动画期间只有一个几何计算点**。
    // 此前封面尺寸在 `IslandPanel` 里用 `bg.islandHeight` 单独算，而"岛体高"在
    // 动画期间是**含控件带**的 —— 于是封面越长越往控件带里钻。
    // 收进本类之后，"封面在哪、多大"与"控件带在哪"由**同一份**几何给出，
    // 断言"两者不相交"才可能是一条**结构**性质而不是巧合。

    /**
     * 封面**外径**（px）—— 基准是**内容区高**（[lyricsHeight]），**与动画进度无关**。
     *
     * ## 为什么不是 [islandHeight]（这正是本轮修掉的那条缺陷）
     *
     * 改动前真机传的是 [islandHeight]，于是 `f = 1` 时封面外径长到
     * `base + stripQ − 2×内边距`，而岛体在纵向**就是** `内容区 + 控件带` 的拼接
     * ⇒ 封面必然横跨进控件带。真机取证（`IslandExpandHitProbe` §E）：
     * **重叠 66 px**，而进度条行高只有 22 px ⇒ 必然压住进度条。
     * 用户原话：
     * > 「**封面在词岛生长仍然与其他控件重叠**。」
     *
     * ## 为什么"内容区"是唯一自洽的基准（结构性论证，不是取值偏好）
     *
     * `岛体 = 内容区 ∪ 控件带`（纵向相邻、互不重叠），而**岛体的生长方向恰好
     * 朝向控件带那一侧**（`TOP` 档向上长、`BOTTOM` 档向下长）。
     * ⇒ "封面按岛体高生长"与"封面不压控件带"**在几何上互斥**：
     * 只要封面在岛体内居中、又按岛体高定尺寸，它长出来的那一半必然落进控件带。
     *
     * 故封面锚在**内容区**：内容区与 [fraction] 无关（见类 KDoc 的不变量），
     * 于是封面尺寸与落点在整段动画里**逐像素不变**，与控件带不相交成为**结构保证**。
     *
     * ## 零变化证明
     *
     * `f = 0` 时 `islandHeight == lyricsHeight`（[bandGeometry] 里 `height = base + stripQ·0`），
     * 故本函数在收起态与改动前**逐像素相同** —— 这条收窄只改展开态。
     *
     * @param scaleWithFont `cover.scale_with_font`（是否套 56px 上限，见 [leadingCoverOuterSizePx]）
     */
    fun coverOuterSizePx(scaleWithFont: Boolean): Float =
        leadingCoverOuterSizePx(lyricsHeight, scaleWithFont)

    /** 封面在**内容区**内垂直居中的上边缘（面板坐标）—— 与动画进度无关。 */
    fun coverTopFor(outerSizePx: Float): Float {
        val s = if (outerSizePx.isFinite()) outerSizePx.coerceAtLeast(0f) else 0f
        return lyricsTop + (lyricsHeight - s) / 2f
    }

    /**
     * 封面矩形（面板坐标，正方形 —— 外径含黑胶环）。
     *
     * 横向锚在**岛体左边缘** + [leadingInsetPx]（与改动前同一个锚）；
     * 纵向锚在**内容区**内居中。两者都与动画进度无关。
     *
     * @param leadingInsetPx 真机 `LEADING_INSET`
     */
    fun coverRect(scaleWithFont: Boolean, leadingInsetPx: Float): Rectangle2D.Float {
        val size = coverOuterSizePx(scaleWithFont)
        val inset = if (leadingInsetPx.isFinite()) leadingInsetPx.coerceAtLeast(0f) else 0f
        return Rectangle2D.Float(islandLeft + inset, coverTopFor(size), size, size)
    }

    /**
     * 控件带矩形（面板坐标）—— 供"封面与控件条不相交"这条断言使用。
     *
     * ⚠️ 它**不含 [fraction]**（带内布局在动画期间静止，见 [stripTop] 的 KDoc），
     * 故这条断言在整段动画上都成立。
     */
    fun stripRect(): Rectangle2D.Float =
        Rectangle2D.Float(islandLeft, stripTop, islandWidth.toFloat(), stripHeight)
}

/**
 * 两个矩形**是否相交**（边界相接不算相交）。
 *
 * 为什么不用 `Rectangle2D.intersects`：它的语义在"宽或高为 0"时返回 `false`，
 * 而 `coverRect` / `stripRect` 在首帧（`islandWidth <= 0`）确实会退化 ——
 * 显式的四则比较让"退化时判为不相交"这条**也是**可读的判据，而不是
 * 一个需要去查 JDK 源码才能确认的副作用。
 */
fun rectsIntersect(a: Rectangle2D, b: Rectangle2D): Boolean =
    a.x < b.x + b.width && b.x < a.x + a.width &&
        a.y < b.y + b.height && b.y < a.y + a.height

/**
 * 展开态相对**收起态岛宽**的横向增量（px）—— 「长大」的**唯一**横向常量来源。
 *
 * ## 为什么等于**控件带高**（而不是另取一个"略大"的百分比）
 *
 * 用户原话：「胶囊和圆角矩形长成**比原来词岛宽度略大**的圆角矩形」。
 *
 * 取"与纵向增量同值"有**三条**依据，而不是手感：
 *
 * 1. **「长大」的字面义就是各方向都长** —— 纵向长 `strip`、横向也长 `strip`，
 *    形状是"等量长大"，不是"长高 + 随机变宽"。
 * 2. ★ **它让歌词可用宽度在整段动画里逐像素不变**（这是可证明的，不是巧合）：
 *    ```
 *    让位宽 rel(f) = LEADING_INSET + coverSize(f) + LEADING_GAP
 *    而 coverSize(f) = (lyricsHeight + grow·f) − 2·VERTICAL_PADDING   ← 封面随 f 放大
 *    ⇒ rel(f) − rel(0) = grow·f
 *    文本宽 = islandWidth(f) − rel(f) = (base + grow·f) − (rel(0) + grow·f) = base − rel(0)
 *    ```
 *    即**横向增量恰好被封面吃掉的宽度抵掉** ⇒ 展开不会把歌词挤窄（长句不会
 *    因为悬停而突然开始滚动）。若横向增量另取一个值，这条不变量立刻破。
 * 3. **没有新常量** —— 它是 `controls.gapPx` / `controls.showProgress` 的派生量，
 *    与窗口高度用的是**同一个**量化值（[bandGeometry] 的 `stripQ`）。
 *
 * ## 为什么取**偶数**
 *
 * 取决于岛体在窗口里**怎么摆**（[bandGeometry] 的 `anchorAtLeftEdge`）：
 *
 * - **居中模式**（既有 4 型，默认）：岛体左右各让出 `grow / 2`。增量取奇数时
 *   `grow / 2` 是**半像素**，岛体左右边缘落在半像素上 ⇒ 圆角与描边被抗锯齿糊一层 ——
 *   而「默认档收起态**逐像素不变**」正是本轮的硬约束。取偶数 ⇒ 让位量恒为整数像素。
 * - **左锚模式**（封面态，2026-09-30 S4）：窗口左边缘就是岛体左边缘（`islandLeft ≡ 0`），
 *   **不存在** `grow / 2` 这一步 ⇒ **半像素问题在机制上不出现**。
 *
 * ★ 那么本态为什么不改成"奇数也行"？因为 **`grow` 是两态共用的同一个量**
 *   （`bandGeometryNow()` 只算一次 [`expandWidthGrowPx`] 再喂给两种模式）。
 *   让它**按模式换奇偶**会多出一条"两态宽度增量不同"的隐性契约 ——
 *   而"横向增量 = 带高"本身是有依据的（见上 §2）⇒ **保持单一取值**。
 *   代价如实记录：左锚模式继承了"最多比 `stripQ` 大 1px"这一条不必要的偏差，
 *   换的是"两种模式不可能各自漂移"。若将来本态确需精确等宽，再单独给本态算。
 *
 * 代价如实记录：`grow` 与 `stripQ` 最多差 1px ⇒ 上述"歌词宽度不变"最多差 1px。
 *
 * @param stripHeightPx 控件带高度（`PlaybackControls.stripHeight`）。非有限 → `0`。
 */
fun expandWidthGrowPx(stripHeightPx: Float): Int {
    if (!stripHeightPx.isFinite()) return 0
    val stripQ = stripHeightPx.roundToInt().coerceAtLeast(0)
    // `(n + 1) / 2 * 2` = 向上取到偶数（`n = 0` → 0，`n = 1` → 2）。
    return ((stripQ + 1) / 2) * 2
}

/**
 * 由「基准高度 + 带高 + 动画进度 + 朝向」算出动画期间的全部几何。
 *
 * @param baseHeightPx 控件条**收起时**的词岛高度（`IslandWindow.baseHeightPx`）。
 * @param stripHeightPx 控件条需要的高度（`PlaybackControls.stripHeight`）。`Float`。
 * @param fraction 控件带当前占 `stripHeight` 的比例（`0f` = 完全收起，`1f` = 完全展开）。
 *   越界会被夹住 —— 帧抖动 / `OVERSHOOT` 类缓动都可能算出略超界的值。
 * @param stripAtTop `controls.position = TOP`。**整个动画期间必须固定** ——
 *   中途切换锚点会让岛体"跳一下"（探针 §11 有专门的反例断言）。
 * @param baseWidthPx 控件条**收起时**的岛宽（`IslandWindow.baseWidthPx`）。
 *   `<= 0` 表示"没有可信宽度"（首帧之前）→ [BandGeometry.islandWidth] 为 `0`，
 *   调用方按"整个面板就是岛体"回退。
 * @param anchorAtLeftEdge ★ 2026-09-30（封面态 S4，`recon/67` §6.2 横向不变量 ④）：
 *   `true` ⇒ 岛体**左边缘固定**在面板左边缘（`islandLeft = 0f`），向右生长；
 *   `false`（默认）⇒ 既有行为：岛体在窗口内**居中**。
 *
 *   ## 为什么本态需要它
 *
 *   用户原话：「词岛**向右侧生长**」。既有谱系里岛体是**居中**的 ——
 *   展开时两侧**各长一半**（左边缘左移、右边缘右移）。那对"围绕歌词居中的词岛"
 *   是对的（视觉重心不动），但封面态的词岛**左边缘是封面**，
 *   它左移会看起来"整条岛往左漂"。
 *
 *   ## ⚠️ 它与 [expandWidthGrowPx] 的"取偶数"理由**无关**
 *
 *   那个理由（"两侧各让 `grow/2`，奇数会落半像素"）只在**居中**模式下成立。
 *   本态左边缘固定在 `0f` ⇒ 没有"/2" ⇒ **奇偶不再影响边缘的整数性**。
 *   但 `grow` 仍由 [expandWidthGrowPx] 给出（它同时服务于纵向对齐与"歌词宽度不变"），
 *   **不为此另做一个取值** —— 见该函数的第 2 条依据（那条依据与锚点无关，仍成立）。
 */
fun bandGeometry(
    baseHeightPx: Int,
    stripHeightPx: Float,
    fraction: Float,
    stripAtTop: Boolean,
    baseWidthPx: Int = 0,
    anchorAtLeftEdge: Boolean = false,
    widthGrowOverridePx: Int = -1,
): BandGeometry {
    val base = baseHeightPx.coerceAtLeast(0)
    // ⚠️ 量化**一次**，窗口与绘制共用（见文件头"取整"一节）。
    val stripQ = if (stripHeightPx.isFinite()) stripHeightPx.roundToInt().coerceAtLeast(0) else 0
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    // 高度：Float 累积、最后一刻 `roundToInt`（不是截断）。
    val height = (base + stripQ * f).roundToInt().coerceIn(0, base + stripQ)
    // 锚点边固定：TOP 固定**下边**（= `stripQ + base`，即展开态的窗口下边）；
    // BOTTOM 固定**上边**（= 0）。
    val top = if (stripAtTop) (stripQ + base) - height else 0
    // ── 横向：窗口恒为**展开态宽**，岛体在窗口内**居中**并随 `f` 连续变宽 ──────
    // 展开增量与纵向同源（= 控件带高），理由见 [expandWidthGrowPx]。
    val growQ = if (widthGrowOverridePx >= 0) widthGrowOverridePx else expandWidthGrowPx(stripHeightPx)
    val bw = baseWidthPx.coerceAtLeast(0)
    val windowW = bw + growQ
    val islandW = if (bw <= 0) 0 else (bw + (growQ * f).roundToInt()).coerceIn(bw, windowW)
    // 居中 ⇒ `f = 0` 时两侧各让出 `growQ / 2`（`growQ` 是偶数 ⇒ 整数像素，见其 KDoc）。
    // ★ 2026-09-30（封面态 S4）：`anchorAtLeftEdge` ⇒ **左边缘固定**、向右生长。
    //   用户原话「词岛**向右侧生长**」。既有形态（居中）**一字不改** ——
    //   默认值 `false` 保证老路径逐像素不变（本轮硬约束）。
    //
    //   ⚠️ 这条公式是横向不变量 ④ 的**唯一**实现点（`recon/67` §6.2）：
    //     ④ 左边缘逐像素固定 · 宽度单调不减
    //   故它必须可被探针断言 —— `BandGeometry` 是公开 data class，`islandLeft`
    //   是公开属性，探针直接读即可（无需额外放宽可见性）。
    val islandLeft = when {
        windowW <= 0 -> 0f
        anchorAtLeftEdge -> 0f
        else -> (windowW - islandW) / 2f
    }
    return BandGeometry(
        islandTop = top.toFloat(),
        islandHeight = height,
        // 带子的位置与高度**不含 `f`**：带内布局静止，变化只体现在岛体形状与 alpha 上。
        stripTop = (if (stripAtTop) 0 else base).toFloat(),
        stripHeight = stripQ.toFloat(),
        lyricsTop = (if (stripAtTop) stripQ else 0).toFloat(),
        lyricsHeight = base.toFloat(),
        islandWidth = islandW,
        islandLeft = islandLeft,
        fraction = f,
        widthGrowPx = growQ,
    )
}
