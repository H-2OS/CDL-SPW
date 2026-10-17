// SPDX-License-Identifier: AGPL-3.0-only

/**
 * 频谱柱状图的**几何与重采样** —— 纯函数，可被探针直接断言。
 *
 * ## 为什么单独一个文件（而不是塞进 `SpectralPainter`）
 *
 * 两条理由，都与本项目既有纪律一致：
 *
 * ① **可断言**：`SpectralPainter` 是 `internal object`，而探针是**独立编译单元**
 *    （`build.gradle.kts` 未配 `associate`/friend-paths），看不到 `internal`。
 *    把纯计算抽到这里（顶层 `fun`，公开）就能被断言 ——
 *    **不为测试放宽可见性，只把规则抽成有名字的定义点**（见 §14.14）。
 * ② **它是"规则"而不是"绘制"**：条数怎么定、频段怎么映射到柱子，
 *    这两个问题与"用什么颜色画、画多高"完全无关。混在一起会让
 *    "改观感"和"改算法"互相牵连。
 *
 * ## 用户原话（本轮诉求，逐字）
 *
 * > 「柱状条要**细一些**，**覆盖的频率要完整一点**，
 * >   如果歌词扩展，选择**增加频谱条**，而不是**拉大已有频谱条**。」
 *
 * 三条分别对应下面三个决定：
 *
 * | 诉求 | 落点 |
 * | --- | --- |
 * | 条更细 | [SPECTRUM_BAR_PITCH_PX]（目标间距 7 px）+ [SPECTRUM_BAR_GAP_RATIO]（缝占 30%） |
 * | 覆盖更完整 | **C# 侧 `BandCount` 12 → 24**（每段从 0.7 个八度压到 0.35 个）—— 见下 |
 * | 变宽时**加条数**而不是拉宽条 | [spectrumBarCount]：条数由**宽度**推导 |
 *
 * ## ⚠️ 关于"覆盖的频率更完整"——两件事必须分清
 *
 * ① **真实分辨率**的提升只能来自 C# 侧（`SpectrumAnalyzer.BandCount`）——
 *    那里 12 → 24，每段覆盖的音程减半。**这才是真的"更完整"**，本轮已做。
 * ② **本文件的 [resampleBands] 不提升分辨率**，它只是把 N 个源频段
 *    **插值**成 M 根柱子（M 通常远大于 N），让"变宽时条数变多"在视觉上成立。
 *    插值出来的中间值**不含新信息** —— 这一点不能含糊，否则会误以为
 *    "柱子多了 = 频谱更准了"。
 */

package dev.cdl.render

/**
 * 目标**柱间距**（含缝，px）。
 *
 * 取 7：条宽约 4.9 px、缝约 2.1 px（见 [SPECTRUM_BAR_GAP_RATIO]）——
 * 这是"能看出是一根根柱子"与"不显得稀疏"的平衡点。
 *
 * ## 为什么是"间距"而不是"条宽"
 *
 * 因为用户要的是**条数随宽度增加**。若直接定"条宽 = 5 px"，那么
 * `条数 = 宽度 / 5` 也能算出来 —— 但那个写法把"缝"挤成了余数，
 * 于是宽度变化时**缝先变、条宽后变**，观感上"忽密忽疏"。
 * 用间距（条+缝）作基准，两者按固定比例同步缩放，密度就稳定。
 *
 * ## 为什么取 7 而不是更小（比如 3）
 *
 * 3 px 间距在 600 px 宽上会画出 200 根柱子 —— 单根只有 2 px，
 * 在抗锯齿下会糊成一片灰带，**看起来像一条渐变色块而不是频谱**。
 * 频谱是**装饰性元素**，可读性优先于"信息密度"。
 */
const val SPECTRUM_BAR_PITCH_PX = 7f

/**
 * `spectrum.anchor = RIGHT`（**词岛右端的窄条**）时，窄条占**几根柱**的宽度。
 *
 * ★ 取 **5** —— 台账 `migration/未达成项可行性评估-2026-09-26.md` §2 ⑧ 的原话是
 * 「**五根细条** + 词岛右端」。
 *
 * ★ 宽度 = `RIGHT_BAR_COUNT × SPECTRUM_BAR_PITCH_PX`（默认柱距 7px ⇒ **35px**）。
 *
 * ## ★ 为什么放在这里（而不是 `IslandWindow` 的 companion 里）
 *
 * 真机的 `SPECTRUM_ANCHOR_*` 常量住在 `IslandPanel` 的 **private companion**
 * （那是有意的：那些是"wire 值"，只该由真机解析）—— 但**预览也要用同一个宽度**：
 * 否则"预览的窄条宽度"与"真机的"会**各写一份** ⇒ 迟早漂移（本项目最忌讳的形态）。
 *
 * ⇒ ★ 把**几何量**（"几根柱"）放到这个**纯函数文件**里 —— 两边都能引用，
 * 且探针也能直接断言（`IslandWindow` 的 companion 是 private，探针看不到）。
 */
const val RIGHT_BAR_COUNT: Int = 5

/**
 * ★★ 2026-10-03（⑱ 时钟 · **右端插槽**）：插槽的**可用宽**（px）= **封面宽**。
 *
 * ## 用户口径（逐字）
 *
 * > 「**向右扩展词岛不就行了吗？歌词行保持居中。两端封面和时间组件保持对称感**」
 *
 * ⇒ 插槽宽取 **`COVER_MAX_OUTER_SIZE_PX`（56）** ⇒ **左端件（封面）与右端件（插槽）同宽**
 *   ⇒ ★ **两端对称**，歌词区夹在中间 ⇒ 视觉居中。
 *
 * ## ★ 它同时满足了七段时钟的**可读下限**（已由探针钉住）
 *
 * `SevenSegmentProbe` ④″：56 px ⇒ 数字宽 10.09 px ⇒ 段厚 **1.61 px ≥ 1.5**（下限）
 * ⇒ ★ **余量 3.97 px**（理论最小 52.03）。
 * ⇒ 原窄带 35 px 下段厚只有 **1.01 px** ⇒ **糊成一片**（这就是本常量存在的理由）。
 *
 * ## ⚠️ 它与 [RIGHT_BAR_COUNT] × [SPECTRUM_BAR_PITCH_PX] 的关系**已经变了**
 *
 * 原来：`RIGHT_BAR_COUNT × SPECTRUM_BAR_PITCH_PX` = **35** = **插槽宽**（两者同一件事）。
 * 现在：那 35 只描述「**频谱在插槽里画多宽**」（5 根柱 × 7 px 柱距），
 * **不再**是插槽宽 ⇒ ★ **别再拿它当插槽宽用**。
 *
 * ★ **同值常量必须单独钉**（本项目既有做法：`ColorPickerProbe` ⑯.7 就钉过一对同值常量）
 * ⇒ 本常量与 `IslandPolicy.COVER_MAX_OUTER_SIZE_PX` 的**相等关系由探针断言**，
 * 而不是靠"我记得改成一样的"。
 */
const val RIGHT_SLOT_WIDTH_PX: Float = 56f

/**
 * ★★ 2026-10-03（⑱ 时钟）：右端插槽相对**旧窄带**多占的宽（px）—— 纯函数，可断言。
 *
 * ```
 * extra = RIGHT_SLOT_WIDTH_PX − RIGHT_BAR_COUNT × SPECTRUM_BAR_PITCH_PX
 *       = 56 − 35 = 21
 * ```
 *
 * ## 为什么必须有它（**不加宽词岛就是回归**）
 *
 * 真机侧只用插槽宽来**让歌词的右边界**（`textRightEdge = islandRight − 插槽宽`）。
 * ⇒ 若插槽从 35 提到 56 而**词岛不同时加宽**，歌词区会被**压窄 21 px** —— 那是**回归**。
 * ⇒ 必须把词岛**同量加宽**，效果才是：
 *   **歌词区尺寸与位置都不变**（`textRightEdge` 仍是旧值），而岛体右端多出 21 px
 *   ⇒ 左封面 56 / 右插槽 56 ⇒ **两端对称** ✓（= 用户要的）。
 *
 * @param occupant 插槽 occupant（`rightSlotOccupant` 的产物）。`NONE` ⇒ **0**。
 * @param islandHeightPx 词岛高（px）—— **只有 `RADIAL` 档会用到**（见 [rightSlotWidthPx]）。
 *   ★ 本参数是 2026-10-04 加的：`RADIAL` 档下插槽宽 = 词岛高 ⇒ 必须先有高度。
 */
fun rightSlotExtraWidthPx(occupant: String, islandHeightPx: Float): Int {
    if (occupant == RIGHT_SLOT_NONE) return 0
    val extra = rightSlotWidthPx(occupant, islandHeightPx) - RIGHT_BAR_COUNT * SPECTRUM_BAR_PITCH_PX
    return if (extra > 0f) Math.round(extra) else 0
}

/**
 * ★★★ 2026-10-04：**"两侧对称化"要多加宽多少词岛**（px）—— 与 [rightSlotExtraWidthPx] **相加**使用。
 *
 * ## 推导（★ 这两笔增量是**不同**的来源，必须都算）
 *
 * | # | 来源 | 算式 |
 * |---|---|---|
 * | ① | **插槽本身比旧窄带宽**（`SPECTRUM` 档 56 vs 旧 35） | `rightSlotExtraWidthPx`（**已有**） |
 * | ② | ★ **两侧对称化**（`lyricSideInsetPx` 取 `max`） | ★ **本函数** |
 *
 * ②的推导：
 * ```
 * 改动前：歌词区宽 = W − 左让位 − 右让位
 * 改动后：歌词区宽 = W' − 2 × max(左, 右)
 * 要"不被压窄" ⇒ W' ≥ W − 左 − 右 + 2×max(左,右)
 * ⇒ extra = 2×max(左,右) − 左 − 右 = |左 − 右|
 * ```
 * ★ 那个绝对值**恰好**是"两侧差多少" —— 直观：两侧要对称，就得给**窄的那侧补上差额**，
 * 而补的宽度要从**词岛**出（否则压窄歌词区）。
 *
 * ## ★ 为什么必须用**收起态**的封面让位
 *
 * `coverInsetRel` 在**展开动画期间会变大**（封面放大）。若用它算 extra：
 * ⇒ 岛宽随悬停动画变化 ⇒ `setBounds` ⇒ **闪动复发**（本项目 2026-09-21 根治过这个闭环）。
 * ⇒ ★ 调用方**必须传收起态的值**（与 `measureAreaLeft` 同一口径）。
 *
 * @param coverInsetRel 左让位（封面侧，**收起态**）
 * @param rightSlotWidth 右插槽宽
 * @return 额外加宽（px，≥ 0）
 */
fun lyricCenteringExtraWidthPx(coverInsetRel: Float, rightSlotWidth: Float): Int {
    val l = if (coverInsetRel.isFinite()) coverInsetRel.coerceAtLeast(0f) else 0f
    val r = if (rightSlotWidth.isFinite()) rightSlotWidth.coerceAtLeast(0f) else 0f
    val diff = Math.abs(l - r)
    return if (diff > 0f) Math.round(diff) else 0
}

/**
 * ★★★ 2026-10-04：**歌词区两侧的让位量**（左 / 右）—— **保证歌词在词岛内居中**。
 *
 * ## 用户口径（逐字，2026-10-04）
 *
 * > 「**注意了，如果右侧小组件插槽加宽，词岛内需要相应调整以保证歌词行在词岛内居中。**」
 *
 * ## 为什么"插槽加宽"会破坏居中（**算术**）
 *
 * 歌词区 = `[岛体左 + 左让位, 岛体右 − 右让位]`，歌词文本在**歌词区内居中**。
 * ⇒ 文本中心与岛体中心重合的**充要条件**是 ★ **左让位 == 右让位**。
 *
 * | 档 | 左让位（封面插槽） | 右让位（插槽） | 居中？ |
 * |---|---|---|---|
 * | `SPECTRUM` / `CLOCK` / `NONE` | `coverSlotWidth`（≈56） | `RIGHT_SLOT_WIDTH_PX`（56） | ✅ 相等 ⇒ 居中 |
 * | ★ `RADIAL` | `coverSlotWidth`（≈56） | ★ **词岛高**（如 76） | ❌ **不等 ⇒ 文本偏左** |
 *
 * ## 修法：两侧**取较大者**（而不是各自算各自的）
 *
 * ```
 * 让位 = max(左让位, 右让位)
 * ```
 *
 * ⇒ ★ **两端对称** ⇒ 歌词居中 ✓
 * ⇒ ★ 且**不重叠**（让位 ≥ 两侧件宽）✓
 *
 * ★★ **为什么不是"把歌词区整体右移"**（那是最直觉的错解）：
 * 右移会让歌词区**左边界越过左端件**（压住封面）—— 只是把"右边压插槽"换成"左边压封面"。
 * ★ 唯一同时满足「居中」与「不重叠」的解就是**两侧都取较大者** ✓
 *
 * ## ★ 连带后果（**必须一起做，否则是回归**）
 *
 * 让位变大 ⇒ 歌词区被压窄 ⇒ ★ 必须把**词岛同量加宽**（`rightSlotExtraWidthPx` 已含此项）
 * ⇒ 效果 = **歌词区尺寸与位置都不变**，只是岛体两端各多出一段留白 ✓
 *
 * @param leftInsetPx 左让位（= `leadingCoverAreaLeftPx` 的产物，含内缩 + 封面插槽 + 间隙）
 * @param rightSlotWidthPx 右端插槽宽（= [rightSlotWidthPx] 的产物）
 * @return 两侧**统一**的让位量（px）。★ 非有限值 ⇒ 退回另一个；都为 0 ⇒ 0
 */
fun lyricSideInsetPx(leftInsetPx: Float, rightSlotWidthPx: Float): Float {
    val l = if (leftInsetPx.isFinite()) leftInsetPx.coerceAtLeast(0f) else 0f
    val r = if (rightSlotWidthPx.isFinite()) rightSlotWidthPx.coerceAtLeast(0f) else 0f
    return maxOf(l, r)
}

/**
 * ★★★ 2026-10-07：**时钟绘制盒的「外侧内缩」** —— 盒**整体左移** [insetPx]，宽度**不变**。
 *
 * ## 用户报的症状（逐字，2026-10-07）
 *
 * > 「**依旧靠近边界，依旧不居中**」（★ 第二次报，见 BUG-155）
 *
 * ## 为什么 BUG-155 的"居中"修法**是空操作**（本条的根因）
 *
 * 插槽盒 = `[islandRight − slotWidth, islandRight]`（**右缘 = 岛体右缘**）。
 * 而 `ClockGlyphs.fitDigitWidth` 反解出的数字宽**恰好**让墨迹总宽 `== slotWidth`
 * ⇒ `textLeft = slotLeft + (slotWidth − textW)/2` 里的 **`(slotWidth − textW)/2` 恒为 0**
 * ⇒ ★ **"居中"这一步在七段档下什么都没做**，时钟仍然**贴着岛体右边缘**（实测间距 **0 px**）。
 *
 * ★★ **取证（用户截图逐像素量）**：设备 1.25× 下 ——
 * 时钟墨迹宽 **71 px**（÷1.25 = **56.8 ≈ 插槽宽 56**）· 墨迹右缘 x=243 **== 岛体右缘 x=243**
 * ⇒ ★ **间距 0 px** ✓ 与上述推断**逐值吻合**。
 *
 * ## 修法：把盒**左移**，而不是把墨迹缩小
 *
 * ★ **为什么不能缩小墨迹**：`MIN_SEGMENT_PX = 1.5` 是"段的最小可读厚度"。
 * 56 px 插槽下数字宽 = `56 / 5.55 = 10.09` ⇒ 段厚 `1.614`（**刚过线**）。
 * 只要两侧共留 **≥ 4 px** 内边距 ⇒ 段厚 `1.499 < 1.5` ⇒ ★★ **`fitDigitWidth` 返回 `null`
 * ⇒ 时钟直接消失**。⇒ **内边距只能靠"移盒"腾出来，不能靠"缩墨迹"**。
 *
 * ## ★★ 为什么内缩量取**左端的 `LEADING_INSET`**（由调用方传入）
 *
 * 左端结构：`岛体左 + LEADING_INSET(14)` → 封面(56) → `LEADING_GAP(12)` → 歌词
 * ⇒ 左端合计让位 = **82**
 * 右端目标（**镜像**）：歌词 → `LEADING_GAP(12)` → 组件(56) → `LEADING_INSET(14)` → `岛体右`
 * ⇒ 右端合计让位 = **82** ✓ **两端完全对称**（= 用户 2026-10-03 的要求「两端保持对称感」）
 *
 * ★ 且 `lyricSideInsetPx = max(左, 右) = max(82, 56) = 82` ⇒ 歌词区**两侧已经都是 82**
 * ⇒ 把插槽盒左移 14 之后，**歌词与插槽之间恰好空出 12 px = `LEADING_GAP`** ✓
 * ⇒ ★★ **不需要加宽词岛**（歌词的位置与宽度**逐像素不变**）✓
 *
 * ★ **另一种等价读法**（两者给出同一个答案）：把时钟**居中于"右侧空间"**
 * （= `[歌词右边界, 岛体右缘]` = 82 px 宽）⇒ 56 px 的盒落在 `[岛体右 − 69, 岛体右 − 13]`
 * ⇒ 与"镜像左端"的 `[岛体右 − 70, 岛体右 − 14]` **只差 1 px** ✓
 *
 * ## 为什么参数里**没有** `LEADING_INSET` 的默认值
 *
 * ★ 本项目纪律（`BUG-150` 的教训）：**"同一份规则多个落点"的参数，默认值应当不给** ——
 * 漏传 ⇒ **编译期失败**，而不是静默退回一个错的值。
 *
 * @param slotLeft 插槽盒左边缘（真机 = `islandRight − slotWidth`）
 * @param insetPx 外侧内缩（★ **由调用方传左端的 `LEADING_INSET`** ⇒ 无重复常量）
 * @param minLeftPx 左边界**下限**（真机 = 歌词区右边界 `textRightEdge`）—— ★ **防重叠**
 * @return 时钟盒的左边缘（盒宽不变 ⇒ 右边缘 = 本值 + 插槽宽）；非有限输入 ⇒ `0f`
 */
fun clockBoxLeftPx(slotLeft: Float, insetPx: Float, minLeftPx: Float): Float {
    if (!slotLeft.isFinite()) return 0f
    val ins = if (insetPx.isFinite()) insetPx.coerceAtLeast(0f) else 0f
    val floor = if (minLeftPx.isFinite()) minLeftPx else 0f
    return (slotLeft - ins).coerceAtLeast(floor)
}


/**
 * ★★★ 2026-10-04：**右端插槽让词岛多占的总宽**（px）—— ★ **唯一出口**（两笔增量合一处）。
 *
 * ## 为什么必须有这个"合并函数"（**本轮复核抓到的真问题**）
 *
 * 词岛加宽有**两笔来源**（见 [lyricCenteringExtraWidthPx] 的 KDoc）：
 * ```
 * ① 插槽比旧窄带宽      → rightSlotExtraWidthPx(occupant, height)
 * ② 两侧对称化（居中）  → lyricCenteringExtraWidthPx(coverInsetRel, 插槽宽)
 * ```
 * ★★ 而**词岛宽度有两个落点**：
 * · `applyGeometry`（设置变化时算）
 * · `applyNaturalWidth`（**测量回填**时算 —— 它会把 `baseWidthPx` **整体改写**）
 *
 * ⇒ ★★ 若只在其中一个落点加某一笔，**另一个落点会把它抹掉**。
 * ★ 本轮实测：`applyGeometry` 加了两笔，而 `applyNaturalWidth` 只加了 ①
 * ⇒ **测量回填一跑，② 就没了**（= 歌词又偏左）。
 *
 * ★ **修法不是"记得两处都加"，而是收敛成唯一出口**（本函数）——
 * 两个落点都调它 ⇒ **结构上不可能只加一笔** ✓
 * （与 `spectrumBarCount` / `rightSlotOccupant` 同一条纪律：**规则只在一个地方定义**。）
 *
 * @param occupant `rightSlotOccupant` 的产物
 * @param islandHeightPx 词岛高（px）—— 只有 `RADIAL` 档用到
 * @param coverInsetRel 左让位（封面侧，**收起态**）—— 只有 `RADIAL` 档用到
 * @return 总加宽（px，≥ 0）。★ `NONE` 档恒 0
 */
fun rightSlotTotalExtraWidthPx(
    occupant: String,
    islandHeightPx: Float,
    coverInsetRel: Float,
): Int {
    if (occupant == RIGHT_SLOT_NONE) return 0
    val slotW = rightSlotWidthPx(occupant, islandHeightPx)
    return rightSlotExtraWidthPx(occupant, islandHeightPx) +
        lyricCenteringExtraWidthPx(coverInsetRel, slotW)
}

/**
 * ★★★ 2026-10-04：**插槽宽度**（px）—— 随 occupant **自适应**。
 *
 * ## 用户口径（逐字，2026-10-04）
 *
 * > 问：环形/放射放进右端插槽时，占用多大区域？
 * > 答：★ **「插槽宽 = 词岛高（自适应）」**
 *
 * ## 为什么 RADIAL 不能用 56 px（**几何原因，不是审美**）
 *
 * 极坐标的半径 = `min(宽, 高) / 2`（见 [spectrumPolarFor]）。插槽是**窄带**：
 * · `SPECTRUM` 档 ⇒ 宽 56 / 高（歌词区高，约 40–120）⇒ `min = 56` ⇒ 半径 28 ⇒ 尚可；
 * · ★ 但 `RADIAL` 要的是**方形**：宽 56 而高 100 ⇒ `min = 56` ⇒ 半径 28，
 *   而**上下会空出一大块**（圆只占中间 56×56）⇒ 观感是"一个小圆饼贴在中间"。
 * ⇒ ★ 让**宽 = 高** ⇒ `min = 高` ⇒ 圆**撑满**插槽 ✓
 *
 * ## ★★ 与 `rightSlotExtraWidthPx` 的关系（**这里有一个必须先想清的顺序问题**）
 *
 * 词岛宽度 = `genericWidthPx + extra`，而 `extra` 依赖**插槽宽**，
 * 插槽宽（本档）又依赖**词岛高** ⇒ 看起来像**循环依赖**。
 * ★ 实为**不循环**：**词岛高与词岛宽是各自独立算的**
 * （高走 `islandCollapsedHeightPx(fontSize, rows, …)`，与宽度无关）
 * ⇒ 先算高、再把高喂给宽度算式即可。★ 调用方必须**先有高度**（`IslandWindow` 里已是如此）。
 *
 * ⚠️ **若将来高度开始依赖宽度**（例如"宽高比锁定"），**这条就会真的变成循环**
 * ⇒ 那时必须把本函数改成"读上一帧的高度"或引入一次不动点迭代。★ 已在此处标注。
 *
 * @param occupant `rightSlotOccupant` 的产物
 * @param islandHeightPx 词岛高（px）。`SPECTRUM` / `CLOCK` 档**不用**它。
 * @return 插槽宽（px）。★ 恒 > 0（`NONE` 档也返回一个值 —— 但调用方不该在 `NONE` 时问宽度）
 */

fun rightSlotWidthPx(occupant: String, islandHeightPx: Float): Float = when (occupant) {
    // ★ 放射状频谱：宽 = 高 ⇒ 圆撑满（用户拍板"自适应"）。
    //   ⚠️ 下限 1f 兜住"高度还没算出来"（0）的过渡帧 —— 否则 extra 会算成负数。
    // ★ 2026-10-11（U14）：**环状**同 RADIAL —— 圆环也要求方形画布（内外半径同圆心）。
    RIGHT_SLOT_RADIAL, RIGHT_SLOT_RING -> islandHeightPx.coerceAtLeast(1f)
    // `SPECTRUM` / `CLOCK` / `NONE` / 未知值 ⇒ **固定 56**（= 与左端封面同宽 ⇒ 两端对称）。
    // ★ 这条保证**老配置与另两档逐像素不变**。
    else -> RIGHT_SLOT_WIDTH_PX
}

/**
 * ★★★ 2026-10-11（**BUG-165**）：**右端插槽的极坐标方形画布** —— 唯一计算点。
 *
 * ## 它修的是什么（用户报障：「放射性频谱和环形频谱**绘制位置偏差**」）
 *
 * **原实现**（`IslandWindow` 里内联）用的是**词岛高**：
 *
 * ```
 * y = bg.islandTop + (bg.islandHeight − W) / 2f      // W = rightSlotWidthPx(occupant, islandHeight)
 * ```
 *
 * ★★ 而 `bg.islandHeight` 是**动画量**：`base + 控件带高 × fraction`
 * ⇒ **展开时它含控件带那 68 px**（用户 `controls.showProgress = true`）✗
 *
 * | 状态 | `bg.islandHeight` | W | 圆心 cy | 歌词区中心 | 偏差 |
 * |---|---|---|---|---|---|
 * | 收起（`f=0`） | 64 | 64 | 32 | 32 | **0** ✓ |
 * | 展开（`f=1`） | **132** | **132** | **66** | 32 | ★ **+34 px** ✗ |
 *
 * ⇒ 展开时**圆变大一倍**、**圆心下移 34 px**、**圆横向跨进歌词区 68 px**
 * （实测出图见 `migration/125`：圆纵向范围 `[0,132]` 而歌词区只有 `[0,64]`，
 * 即**圆盖住了整条控件带**）。
 * ★ 这也解释了用户那句「（均在词岛**收起时不可见**）」—— **收起时偏差恰好为 0**，
 *   所以只有**展开**才看得出来 ✓
 *
 * ## 为什么正解是「**歌词区**」而不是「词岛高」
 *
 * 右端插槽是**与时钟 / 五条小频谱同一个组件位**（`island.right_slot.component` 的四档
 * 共用同一个插槽）。而那两档的落点都是**歌词区**：
 *
 * | 档 | 垂直落点 |
 * |---|---|
 * | `CLOCK` | `layout.lyricsTop + (layout.lyricsHeight − 字高)/2` |
 * | `SPECTRUM`（五条） | `areaTop = layout.lyricsTop`，`areaHeight = layout.lyricsHeight` |
 * | ★ `RADIAL` / `RING` | ★ **本函数** ⇒ 同一个歌词区 |
 *
 * ★★ 三条独立理由（任一条都足以定案）：
 *
 * 1. **一致性** —— 插槽的语义区域就是歌词区；用"词岛高"等于让极坐标两档
 *    独享一个**别的档不用**的坐标系。
 * 2. **稳定性** —— `lyricsHeight` **与展开动画无关**（`BandGeometry` 的不变量）
 *    ⇒ 悬停时圆**不再变大/下移**（那正是用户看到的"偏差"）。
 * 3. **与布局侧同源** —— 词岛的**加宽**与**歌词让位**都按 `baseHeightPx`
 *    （= `lyricsHeight`）算（见 `rightSlotTotalExtraWidthPx`）
 *    ⇒ ★ 用 `lyricsHeight` 后，**画布宽 == 预留宽**，两者第一次真正对齐 ✓
 *
 * ## 与 [rightSlotWidthPx] 的关系
 *
 * 宽度仍走**同一个函数**（`RADIAL` / `RING` ⇒ 宽 = 高 ⇒ 方形）——
 * 本函数只决定"**拿谁的高度**去问它"以及"摆在歌词区的哪里"。
 *
 * @param occupant `rightSlotOccupant` 的产物
 * @param islandRightPx 岛体右边缘（面板坐标）
 * @param lyricsTopPx 歌词区上边缘（`BandGeometry.lyricsTop`）
 * @param lyricsHeightPx 歌词区高度（`BandGeometry.lyricsHeight`，**与展开动画无关**）
 * @return `[x, y, w, h]`（面板坐标）—— 与 `IslandWindow` 里 `polarArea` 的口径一致。
 *   `lyricsHeightPx <= 0` ⇒ 宽高夹到 `1`（防"首帧还没算出高度"时退化成 0 面积）。
 */
fun polarSlotArea(
    occupant: String,
    islandRightPx: Float,
    lyricsTopPx: Float,
    lyricsHeightPx: Float,
    /**
     * ★★★ 2026-10-12（**BUG-175**）：**外侧内缩**（★ 与左端封面**同一个量**）。
     *
     * ## 用户报障（逐字）
     *
     * > 「**我感觉右侧三型频谱的绘制位置有些偏差（即到词岛边缘的距离不是等距的），
     * >   同封面位置不完全对称**」
     *
     * ## 根因：★ 本函数**漏了内缩**（`x = islandRight − side` ⇒ 右边**完全贴边**）✗
     *
     * ★ 左端结构：`岛体左 + LEADING_INSET(14)` → 封面(56) → …
     * ★★ 而本函数原来给的是：`… → 组件(56) → 岛体右`（**没有** `LEADING_INSET`）✗
     * ⇒ ★ 右侧**贴边**、左侧**留 14** ⇒ 正是用户说的"**不是等距、不完全对称**" ✓
     *
     * ★ 项目里**早就有**这条镜像口径（见 [clockBoxLeftPx] 的 KDoc：
     *   「右端目标（**镜像**）：… → 组件(56) → `LEADING_INSET(14)` → 岛体右」）——
     *   ★★ 而**频谱这两档没接**（时钟接了）✗ ⇒ ★ 本参数就是把它接上 ✓
     *
     * @param insetPx 外侧内缩（★ **由调用方传左端的 `LEADING_INSET`** ⇒ 无重复常量）
     */
    insetPx: Float = 0f,
): FloatArray {
    val h = if (lyricsHeightPx.isFinite()) lyricsHeightPx.coerceAtLeast(1f) else 1f
    val side = rightSlotWidthPx(occupant, h)
    val top = if (lyricsTopPx.isFinite()) lyricsTopPx else 0f
    // ★ 非有限输入 ⇒ 当 0（与 `clockBoxLeftPx` 同一条防御）✓
    val ins = if (insetPx.isFinite()) insetPx.coerceAtLeast(0f) else 0f
    return floatArrayOf(
        islandRightPx - side - ins,
        top + (h - side) / 2f,
        side,
        side,
    )
}

// ── 右端插槽的 **occupant**（2026-10-03 · ⑱ 时钟 · `migration/80`）────────────

/** 插槽 occupant 的 wire 值 —— **与 `island.right_slot.component` 的选项一一对应**。 */
public const val RIGHT_SLOT_NONE: String = "NONE"
public const val RIGHT_SLOT_SPECTRUM: String = "SPECTRUM"
public const val RIGHT_SLOT_CLOCK: String = "CLOCK"

/**
 * ★★★ 2026-10-11（**U14**）：**环状频谱**（插槽 occupant 第 5 个取值）。
 *
 * ## 它就是 KDoc 预告过的那一档（**零新绘制代码**）
 *
 * [SpectrumPolarSpec] 的 KDoc 早写着：「若将来要"环形"档，**只需再加一个 occupant 常量 +
 * 一个 innerRatio**，不必动绘制」⇒ 本档就是**兑现那句话**：
 * · 几何 = [SpectrumPolarSpec]（与 RADIAL **同一套**），只差
 *   `innerRadiusRatio = [SpectrumPolarSpec.CIRCULAR_INNER_RATIO]`（**0.55** ⇒ 中心空出约一半）；
 * · 绘制 = `paintBars` 的极坐标分支（**一行都不改**）✓
 *
 * ## 与 RADIAL 的差别（★ 业界对照，见 [SpectrumPolarSpec] 的查证表）
 *
 * | 形态 | 内半径 | 观感 |
 * |---|---|---|
 * | **RADIAL（放射）** | **0** ⇒ 从圆心射出 | 一个**太阳** |
 * | **RING（环状，本档）** | **0.55 × 外半径** ⇒ 中心空 | 一个**甜甜圈**（条沿圆环向外长） |
 */
public const val RIGHT_SLOT_RING: String = "RING"

/**
 * ★★★ 2026-10-04：**放射状频谱**（插槽 occupant 的第 4 个取值）。
 *
 * ## 为什么是**一个**档而不是两个（用户 2026-10-04 拍板「只加一档（固定一种）」）
 *
 * 台账 §3.1 ⑧ 的"放射 / 环形"是**两种**形态，但用户明确选择**只给一个 occupant 档**
 * ⇒ ★ 取**放射**（内半径 0 ⇒ 条从圆心射出）—— 与档名 `RADIAL` 一致，
 *   且它在**方形区域**里最"满"（环形中心空着，在**小尺寸**下会显得空荡）。
 *
 * ★ **业界对照**（用户要求"参照网上的经典呈现"）：Rainmeter `Roundline` ·
 * react-audio-visualizers 的 `radialSquaredBars`（`radius` + `startingAngle`）·
 * productivetoolbox 的 `Radial`。见 [SpectrumPolarSpec] 的 KDoc。
 *
 * ★ 几何**完全复用** [SpectrumPolarSpec] 那套（只把 `innerRadiusRatio` 设为 0）
 * ⇒ 若将来要"环形"档，**只需再加一个 occupant 常量 + 一个 innerRatio**，不必动绘制。
 */
public const val RIGHT_SLOT_RADIAL: String = "RADIAL"

/**
 * ★★★ 2026-10-03（⑱ 时钟）：**右端插槽里放什么** —— **唯一判据**（纯函数，可被探针断言）。
 *
 * ## 输入两个键，为什么
 *
 * | 键 | 角色 |
 * |---|---|
 * | `island.right_slot.component` | ★ **主判据**（用户 2026-10-03 新增的那一个） |
 * | `spectrum.anchor` | ★ **只作迁移**（老配置表达"右端有频谱"用的就是它） |
 *
 * ## ★★ 迁移规则（老配置**逐像素不变**）
 *
 * ```
 * slotKey ∈ {SPECTRUM, CLOCK}  ⇒ 就是它（**显式选择优先**）
 * 否则 spectrumAnchor == "RIGHT" ⇒ SPECTRUM（★ 老配置的等价物）
 * 否则                          ⇒ NONE
 * ```
 *
 * ★ **为什么新键默认 `NONE` 而不是 `SPECTRUM`**：若默认 `SPECTRUM`，
 *   **从没用过 RIGHT 档的用户**升级后会**凭空多出一块 56 px 插槽**（词岛宽 +21、歌词让位）
 *   = **开箱观感变化**，违反"默认值要稳"。⇒ 把迁移放在**这里**，默认值保持 `NONE`。
 *
 * ★ **已知代价（如实记，见 `migration/80` §2.2）**：本函数**没有"用户是否显式设过"这个状态**
 *   ⇒ 用户把插槽设成「关闭」但 `spectrum.anchor` 仍是「词岛右端」时，**仍是 `SPECTRUM`**
 *   （要彻底关掉得把 `anchor` 也改回「贴底边」）。★ 这是**有意的**：宁可"关不干净"，
 *   也不要"老配置升级后频谱凭空消失"（后者是**数据丢失**级的观感事故）。
 *
 * @param slotKey `island.right_slot.component` 的值（未知值按 `NONE` 处理）。
 * @param spectrumAnchor `spectrum.anchor` 的值。
 */
fun rightSlotOccupant(slotKey: String, spectrumAnchor: String): String = when (slotKey) {
    // ★ 2026-10-04：加 `RIGHT_SLOT_RADIAL`（放射状频谱）—— **显式选择优先**。
    // ★ 2026-10-11（U14）：加 `RIGHT_SLOT_RING`（环状频谱）—— 同上。
    RIGHT_SLOT_SPECTRUM, RIGHT_SLOT_CLOCK, RIGHT_SLOT_RADIAL, RIGHT_SLOT_RING -> slotKey
    else -> if (spectrumAnchor == SPECTRUM_ANCHOR_RIGHT) RIGHT_SLOT_SPECTRUM else RIGHT_SLOT_NONE
}

/**
 * `spectrum.anchor` 的**右端插槽**档。
 *
 * ★★ 2026-10-05：本常量原来**散在 4 处**当裸字面量 `"RIGHT"` 用
 * （`rightSlotOccupant` · `IslandWindow.drawBackgroundSpectrum` · `IslandPreviewPane` · 探针）
 * ⇒ ★ 抽成**唯一来源**（"两处各写一遍迟早漂移"是本项目反复栽的那条）✓
 */
public const val SPECTRUM_ANCHOR_RIGHT: String = "RIGHT"

/**
 * ★★★ **要不要【起频谱数据源】** —— **唯一判据**（2026-10-05 · **BUG-157**）。
 *
 * ## ★★ 这是 BUG-153b 的**同一形态在【数据层】复发**（用户要求"全面核查"时抓到的）
 *
 * ★ BUG-153b 修的是**绘制侧**（`shouldDrawSpectrumBand`：插槽档不看总开关）；
 * ★★ 但**数据侧**漏了 —— `CdlRuntime.syncSpectrum` 原来只判 `spectrum.enabled`
 *   ⇒ `spectrum.enabled = false` + 插槽选「小频谱/放射」时：
 *   **助手进程根本不启动 ⇒ `bands` 为空 ⇒ 绘制侧首行就 return ⇒ 插槽频谱不画** ✗
 *   ⇒ ★ 用户的诉求「**我选了小频谱却没反应**」**依然存在**（只是换了一层）✓
 *
 * ## ★ 与 `shouldDrawSpectrumBand` **语义对称**
 *
 * | 问题 | 函数 |
 * |---|---|
 * | **要不要起数据源** | `needsSpectrumData`（本函数） |
 * | **要不要画这一带** | `shouldDrawSpectrumBand` |
 * ★ 两者**必须同判**：凡是会画的，就必须有数据 —— 否则就是"画了但没数据"（静默空白）✓
 *
 * ## ★ 与「封面态强制开启」**同一手法**
 *
 * `CdlRuntime` 已有先例（`coverShapeForcesSpectrum`）：**视为开启，但绝不改键值**
 * （改键值就是偷改用户配置）✓ 本函数同此 ✓
 *
 * @param slotKey `island.right_slot.component` 的值。
 * @param spectrumAnchor `spectrum.anchor` 的值。
 * @param spectrumEnabled `spectrum.enabled`（总开关）。
 * @return `true` ⇒ 需要数据源（起进程）。
 */
public fun needsSpectrumData(
    slotKey: String,
    spectrumAnchor: String,
    spectrumEnabled: Boolean,
): Boolean {
    // ★ 总开关开着 ⇒ 一律要数据（歌词区 / 贴边档也画）✓
    if (spectrumEnabled) return true
    // ★ 总开关关着 ⇒ **只有插槽档会画** ⇒ 那就得有数据 ✓
    //   ⚠️ 判据复用 `rightSlotOccupant`（含迁移）⇒ 与 `shouldDrawSpectrumBand` **同源** ✓
    // ★ 2026-10-11（U14）：RING 加入（与 RADIAL 同判 —— 环状也画在插槽里）。
    val occ = rightSlotOccupant(slotKey, spectrumAnchor)
    return occ == RIGHT_SLOT_SPECTRUM || occ == RIGHT_SLOT_RADIAL || occ == RIGHT_SLOT_RING
}

/**
 * ★★★ **【主带】该不该画频谱** —— **唯一判据**（真机与预览共用）。
 *
 * ## ★★★ 2026-10-10（**BUG-162**）：本函数从"一个判据管两件事"里**拆出来**
 *
 * ★★ **用户拍板**（原话「**要**」）：★ **插槽选了「小频谱 / 放射」时，主带那条频谱【还要】** ✓
 * ⇒ ★ 频谱从"**只画一次**"改成"**主带 + 插槽各画一次**" ⇒
 * ★★ **原 `shouldDrawSpectrumBand(anchor, occupant, enabled)` 把两件事塞进一个函数**
 *   ⇒ ★ 它**没法回答"我现在问的是哪个目标"** ⇒ ★ 只能靠"假装 occupant"来区分
 *   ⇒ ★★ 那是**脆弱**的（`occupant = NONE` 时两个目标判据相同 ⇒ **主带会被画两次**）✗
 * ⇒ ★ **正解 = 拆成两个函数，各自只收自己真正需要的输入** ✓
 *
 * ## 判据
 *
 * ★ `anchor == RIGHT`（插槽档）⇒ **false** —— ★ 那档没有"主带"，频谱归插槽（见 [shouldDrawSpectrumInSlot]）✓
 * ★ 其余（`BOTTOM` / `TOP` = 歌词区 / 贴边）⇒ ★ **`spectrum.enabled`（总开关「显示实时频谱」）** ✓
 *
 * @param spectrumAnchor `spectrum.anchor` 的值。
 * @param spectrumEnabled `spectrum.enabled`（总开关）。
 * @return `true` ⇒ 主带该画。
 */
public fun shouldDrawSpectrumBand(
    spectrumAnchor: String,
    spectrumEnabled: Boolean,
): Boolean = spectrumAnchor != SPECTRUM_ANCHOR_RIGHT && spectrumEnabled

/**
 * ★★★ **【右端插槽】该不该画频谱** —— **唯一判据**（真机与预览共用）。
 *
 * ## ★★ 为什么插槽档**不看总开关**（★ 用户 2026-10-05 拍板，**真实踩过**）
 *
 * 右端插槽是"**组件**"（与时钟、封面并列），它有**自己的选择项**
 * （`RIGHT_SLOT_NONE` / `SPECTRUM` / `CLOCK` / `RADIAL`）
 * ⇒ ★★ **那 4 档本身就是它的开关** ⇒ 再拿总开关门控它 = **同一个东西两个开关**
 * ⇒ 用户必然撞上「**我选了小频谱却没反应**」（实测取证：`cdl.json` 里
 * `spectrum.enabled = false` 而 `right_slot.component` 已切到 `SPECTRUM`）✗
 *
 * @param slotOccupant 由 [rightSlotOccupant] 算好的 occupant（**含迁移**）。
 * @return `true` ⇒ 插槽该画。
 */
public fun shouldDrawSpectrumInSlot(slotOccupant: String): Boolean =
    slotOccupant == RIGHT_SLOT_SPECTRUM || slotOccupant == RIGHT_SLOT_RADIAL ||
        slotOccupant == RIGHT_SLOT_RING

/** 插槽是否**被占用**（= 要占位、要歌词让位、要词岛加宽）。 */
fun rightSlotOccupied(slotKey: String, spectrumAnchor: String): Boolean =
    rightSlotOccupant(slotKey, spectrumAnchor) != RIGHT_SLOT_NONE

/**
 * 柱子的**下限条数**。
 *
 * 取 24 = **C# 侧当前的 `BandCount`**。理由：条数少于源频段数会**丢信息**
 * （两根柱子映射到同一个源频段，或某个源频段被跳过）——
 * 而"变窄时丢信息"是用户完全预料不到的（他只是把窗口拖小了）。
 *
 * ⚠️ 若将来 C# 侧改了 `BandCount`，**这里要跟着改** ——
 * 两者的关系是"渲染不该比数据源更粗"。
 */
const val SPECTRUM_MIN_BARS = 24

/**
 * 柱子的**上限条数**。
 *
 * 取 192：`layout.max_width` 上限 2400 px 时，`2400 / 7 ≈ 343` ——
 * 远超上限。封顶的原因有两个：
 * ① 每根柱子一次 `g.fill()`，343 根在 30 fps 下是每秒 1 万次绘制调用；
 * ② 更实际的：超过约 200 根后单根宽度降到 1 px 以下，**视觉上不再有增益**，
 *    只是白烧 CPU。
 *
 * 封顶后极宽窗口的柱子会略宽于 [SPECTRUM_BAR_PITCH_PX] ——
 * 这是有意的（宁可略宽，也不要无意义地画 300 根 1 px 的线）。
 *
 * ## ⚠️ 2026-09-19 修订：192 → **384**（用户报「频谱条间距不能再小吗？」）
 *
 * ## 原记 vs 实为
 *
 * **原记**：这个上限只是"极宽窗口的省电阀"，不影响任何正常配置。
 *
 * **实为**：它是一个**把设置项架空的硬闸**。有效间距恒为
 * `max(设置值, 宽度 / MAX_BARS)` —— 上限 192 时：
 *
 * | 词岛宽度 | 用户把「频谱条间距」设成 2 或 3 时**实际**得到的间距 |
 * |---|---|---|
 * | 450 | 3.0（`450/192 = 2.34`，未触闸） |
 * | 640（默认 `layout.max_width`） | 3.33（**触闸**，用户设 3 也到不了） |
 * | 1000 | 5.21（**触闸**，用户设 2 得到 5.21） |
 * | 2400（上限） | 12.5（**触闸**，差 6 倍） |
 *
 * 于是用户把滑杆拖到最小、画面却**纹丝不动** —— 他看到的正是
 * 「间距不能再小吗」。**根因不是下限，是这个上限。**
 *
 * ## 为什么取 384 而不是更高
 *
 * 判据是"让**默认配置**（`layout.max_width = 640`）能把滑杆拉到下限 2 px"：
 * `640 / 2 = 320 ≤ 384` ✓。384 同时把 3 px 档的可达宽度从 576 抬到 **1152**。
 *
 * 代价（如实记）：每帧 `g.fill()` 的次数最多翻倍（192 → 384）。
 * 但**只在用户主动把间距调小、或词岛宽到 `宽度/7 > 384`（即 > 2688 px）时才发生** ——
 * 前者是用户明确要的"更密"，后者超出 `layout.max_width` 的上限 2400，不可达。
 * 且进度染色那一遍是**可选层**（`spectrum.progress_tint`，默认关），
 * 默认路径仍是单遍绘制。
 *
 * ## ⚠️⚠️ 2026-09-23 再修订：384 → **1024**（**上面"不追 512/768"的结论已被推翻**）
 *
 * 上面那段写「再往上抬（512/768…）… 故**不追**」✗ —— **后来追了，而且是用户要求的**。
 *
 * **用户答复**（问他"要更小请给数值"）：「**最低低至 0.1px**」
 * （见 `BUG-与修复记录.md` 的 **BUG-080**）。
 *
 * ⇒ 于是三处**必须同档**放宽，否则滑杆与实际不符：
 *   · [SPECTRUM_AUTO_MIN_PITCH_PX] `2` → **`0.1`**；
 *   · 本常量 `384` → **`1024`**；
 *   · 键 `spectrum.bar_pitch_px` → **`spectrum.bar_pitch_tenth_px`**（0.1px 单位，`1..200`，默认 70）。
 *
 * **代价如实记**：每帧 `g.fill()` 次数上限从 384 抬到 1024（**2.67 倍**）。
 * 但**只在用户主动把间距调到极小**时才逼近上限；默认路径（`tenth_px = 70` ⇒ 7px）离它很远。
 *
 * ⚠️ **而"更小"的实际观感有物理上限**：`paintBars` 里 `gap` 与 `barWidth` 都有 `coerceAtLeast(1f)`
 * ⇒ **每根柱、每条缝都至少要 1px 才画得出来** ⇒ **实际能看到的**最小间距约 **2px**。
 * 把间距设成 0.1 的真实效果是"**柱数触到本上限**"，**不是**"柱细到 0.1px"。
 * （这段物理限制原先写在下一条常量的 KDoc 里 —— 它属于**这里**。）
 */
const val SPECTRUM_MAX_BARS = 1024

/**
 * 自动档（`spectrum.bar_pitch_mode = AUTO`）的**参考条数**。
 *
 * 语义：把宽度分成这么多条时，间距恰好是 [SPECTRUM_BAR_PITCH_PX]。
 * 即"宽岛用默认间距、窄岛按比例压细"。取 150 的理由与逐宽对照表
 * 见 [spectrumAutoPitch] 的 KDoc。
 */
const val SPECTRUM_AUTO_REFERENCE_BARS = 150f

/**
 * 自动档的**间距下限**（px）。
 *
 * 取 3：条宽 = 3 × 0.7 = 2.1 px、缝 0.9 px。再细就落到 1 px 量级，
 * 抗锯齿下会糊成一条灰带 —— **看起来像色块而不是频谱**
 * （与 [SPECTRUM_BAR_PITCH_PX] 取 7 而不是 3 是同一条理由，
 * 只是自动档允许在窄岛时更接近这个极限）。
 *
 * ## 修订史：`3` → `2` → **`0.1`**（当前值）
 *
 * ⚠️ **2026-09-23 再修订**：本段原先通篇论证「**2 是物理下限**」并停在 2 ✗ ——
 * **那个论证本身没错，但它解释的是"视觉下限"，不是"设置下限"** ✗。
 * 用户后来答复（问他"要更小请给数值"）：「**最低低至 0.1px**」
 * （见 `BUG-与修复记录.md` 的 **BUG-080**）⇒ 设置下限下到 **0.1**，
 * 并**接受**"设 0.1 时视觉上仍约 2px 见底"这个后果。
 *
 * ## 为什么设 0.1 而视觉上仍是约 2px（**这才是"2"的正确位置**）
 *
 * `SpectralPainter.paintBars` 的算术是：
 *
 * ```
 * slot     = 可用宽度 / 条数
 * gap      = max(slot × 0.30, 1f)      ← 缝有 1 px 地板
 * barWidth = max(slot - gap, 1f)       ← 条也有 1 px 地板
 * ```
 *
 * 两处地板合起来意味着 `barWidth + gap ≥ 2 px`。于是：
 *   · `slot = 2` → 缝 1 px、条 1 px —— **恰好铺满，相邻柱不重叠**；
 *   · `slot < 2` → 条与缝相加**大于** `slot`，柱子会**互相压住**，
 *     频谱糊成一片连续色块 —— 那不是"更细"，是**消失**。
 *
 * ⇒ 所以**把设置调到 0.1 的效果是"柱数触到 [SPECTRUM_MAX_BARS] 上限"**，
 * **不是**"柱细到 0.1px" ✓ —— 这解释了为什么用户会觉得"调到底也没更细"。
 *
 * ⚠️ 三者**必须同档**放宽，否则滑杆与实际不符（本次三处一起改的）：
 *   · 本常量 `2` → **`0.1`**；
 *   · [SPECTRUM_MAX_BARS] `384` → **`1024`**（否则宽词岛上这个下限**够不到**）；
 *   · `SettingRegistry` 的键 `spectrum.bar_pitch_px` → **`spectrum.bar_pitch_tenth_px`**
 *     （0.1px 单位，`1..200`，默认 70）。
 */
const val SPECTRUM_AUTO_MIN_PITCH_PX = 0.1f

/**
 * 柱间缝隙占**间距**的比例。
 *
 * 取 0.30（改动前是 0.25）：用户要"条细一些"，而**加宽缝隙比压窄条更有效** ——
 * 同样的条宽下，缝更宽会让"一根根"的分离感更强。
 *
 * ⚠️ 缝有**下限 1 px**（见 `SpectralPainter.paintBars`）：比例在极小间距下会算出
 * 亚像素缝（间距 7 → 缝 2.1，还行；间距 3 → 缝 0.9），而抗锯齿会把亚像素的缝
 * 画成一层灰雾 —— 那比没有缝还难看。这条纪律是改动前就有的，本轮保留。
 */
const val SPECTRUM_BAR_GAP_RATIO = 0.30f

/**
 * **自动柱间距**（`spectrum.bar_pitch_mode = AUTO`）—— 窄岛时把柱子做细，以塞下更多条。
 *
 * ## 用户原话（2026-09-18）
 *
 * > 「**不固定词岛宽度又是短歌词时，频谱条应该变细，以塞下更多频谱条**。
 * >   增加设置频谱条宽度范围的独立选项**或自动**（频谱条宽度与词岛宽度呈一比例）」
 *
 * ## 为什么不是"条数恒定"（那样更简单，但会推翻更早的一条诉求）
 *
 * 更早的诉求是「歌词扩展时**增加频谱条**，而不是拉大已有频谱条」——
 * 即**宽岛要多条**。若做成"条数恒定"（`pitch = width / 固定条数`），
 * 宽岛反而会从 192 条掉到 150 条，**推翻那条诉求**。
 *
 * 故本函数的形状是"**只在窄岛变细，宽岛保持 [SPECTRUM_BAR_PITCH_PX]**"：
 *
 * ```
 * pitch = clamp(width / SPECTRUM_AUTO_REFERENCE_BARS, MIN, SPECTRUM_BAR_PITCH_PX)
 * ```
 *
 * | 岛宽 | 自动间距 | 条数 | 与改动前（恒 7px）对比 |
 * | --- | --- | --- | --- |
 * | 300 | 2.0（下限，2026-09-19 由 3.0 放宽） | 150 | 42 → **150**（细得多） |
 * | 450 | 3.0 | 150 | 64 → **150**（细得多） |
 * | 600 | 4.0 | 150 | 85 → **150** |
 * | 780 | 5.2 | 150 | 111 → **150** |
 * | 1050 | 7.0 | 150 | 150 → 150（相同） |
 * | 1400 | 7.0（上限） | 200 | 192 → **200**（上限 384 后不再被夹） |
 *
 * 于是：**窄岛变细变密、宽岛观感逐像素不变** —— 两个诉求同时成立。
 *
 * ⚠️ 2026-09-19：最后一行从「192（触上限）」变为「200」—— 因为
 *   [SPECTRUM_MAX_BARS] 由 192 抬到 **384**，再抬到 **1024**（理由见该常量 KDoc —— BUG-080）。
 *   宽岛的**间距**仍是 7.0 px 不变，只是不再被上限压成 7.29 px，
 *   故"宽岛观感不变"这句话在 1400 px 这一档上**由"逐像素"退化为"几乎逐像素"**
 *   （7.0 vs 7.29 px，差 4%）—— 如实记在这里，不假装没变。
 *
 * @param widthPx 可用宽度（像素）
 */
fun spectrumAutoPitch(widthPx: Int): Float {
    if (widthPx <= 0) return SPECTRUM_BAR_PITCH_PX
    return (widthPx / SPECTRUM_AUTO_REFERENCE_BARS)
        .coerceIn(SPECTRUM_AUTO_MIN_PITCH_PX, SPECTRUM_BAR_PITCH_PX)
}

/**
 * **对称模式下频谱带的纵向范围**（纯函数，可被探针断言）。
 *
 * ## 为什么单独抽出来
 *
 * 2026-09-18 修 BUG-061 时把对称轴从"带中心"改成"歌词区中心"（正确），
 * 但**带的摆法没跟着改** —— 于是 `height_percent < 50` 时轴落到带**外面**，
 * `paintBars` 的 `halfSpan` 被 `coerceAtLeast(1f)` 夹成 1
 * → **整个双峰塌成一条 2px 的线**，且不报错。
 *
 * 那段逻辑原本写在 `IslandWindow.drawBackgroundSpectrum` 里（**不可测**）。
 * 抽成本函数后，探针可以直接断言"**任意百分比下都不得塌**" —— 这正是
 * §14.14 那条纪律（**为测试发愁时先抽纯函数，而不是放宽可见性**）。
 *
 * ## 语义
 *
 * 带**以歌词区中心为心**，总高 = `areaHeight × heightFraction`
 * → `height_percent` 在对称模式下读作"**双峰总高占歌词区的百分比**"（上下各一半）。
 *
 * @param areaTop 歌词区上边缘
 * @param areaHeight 歌词区高度
 * @param heightFraction `spectrum.height_percent / 100f`（调用方已按注册表 range 收敛）
 * @return 带的上边缘 y（带高 = `areaHeight × heightFraction`，恒 ≥ 1）
 */
fun symmetricBandTop(areaTop: Float, areaHeight: Float, heightFraction: Float): Float {
    val bandHeight = (areaHeight * heightFraction).coerceAtLeast(1f)
    return areaTop + areaHeight / 2f - bandHeight / 2f
}

/**
 * 由**可用宽度**推导柱数。
 *
 * ## 为什么这是"变宽加条数"的全部实现
 *
 * `SpectralPainter` 原来写的是 `slot = bounds.width / bands.size` ——
 * 段数是**数据源给的固定值**，于是窗口变宽只会让每条变宽（用户抱怨的正是这个）。
 * 改成"条数由宽度推导"之后，宽度 → 条数 → 条宽，**条宽基本恒定**。
 *
 * ## 边界
 *
 * - 宽度 ≤ 0 → 返回 [minBars]（调用方本就不该在此时绘制，这里只保证不返回 0/负数）
 * - 结果被夹在 `[minBars, maxBars]` —— 两个边界的理由见各自常量的 KDoc
 *
 * @param widthPx 可用宽度（像素，已扣除两侧内边距）
 * @param pitchPx 目标柱间距（含缝）
 * @param minBars 下限（不应少于数据源的频段数）
 * @param maxBars 上限
 */

fun spectrumBarCount(
    widthPx: Int,
    pitchPx: Float = SPECTRUM_BAR_PITCH_PX,
    minBars: Int = SPECTRUM_MIN_BARS,
    maxBars: Int = SPECTRUM_MAX_BARS,
): Int {
    if (pitchPx <= 0f) return minBars
    if (maxBars < minBars) return minBars
    val raw = (widthPx / pitchPx).toInt()
    return raw.coerceIn(minBars, maxBars)
}

/**
 * ★★★ 2026-10-11（**U13 · 插槽小频谱**）：**条数判据的唯一出口**（含「固定条数」档）。
 *
 * ## 为什么必须有它（★ 本函数是本轮两个用户症状的**共同根因**的修复点）
 *
 * [spectrumBarCount] 的 `minBars = [SPECTRUM_MIN_BARS]`（**24**）是为**主带**设计的：
 * 主带宽几百 px，24 根起步是合理的下限。
 *
 * ★★ 但**右端插槽**的可用宽只有 **28 px**（56 插槽 − 两侧圆角内缩各 14）：
 * `spectrumBarCount(28) = (28/7)=4 ⇒ coerceIn(24, 1024) = `**24 根**
 * ⇒ ★★ **24 根塞进 28 px** ⇒ 每根槽位 1.17 px、柱宽 ≈ 0.8 px ⇒
 * **糊成一片 / 视觉上等于没有** ✗
 *
 * ⇒ ★ 用户的两个症状（「**显示的是缩小的主频谱**」「**不显示**」）**一次全解释**：
 * 那不是"5 根小频谱"，而是 **24 根密密麻麻的小柱子**（= 主频谱的缩小版）；
 * 柱宽低于 AA 分辨率时**看起来就是不显示** ✓
 *
 * ## 修法：**固定条数**（用户设计定案：**五根**）
 *
 * ★ `forceBarCount > 0` ⇒ **原样采用**（只夹上限 [SPECTRUM_MAX_BARS]，**不经过
 * [SPECTRUM_MIN_BARS]**）⇒ 插槽恒 5 根 ✓
 * ★ `forceBarCount <= 0` ⇒ 与改前**逐位相同**（走 [spectrumBarCount]）⇒
 * **主带与其它调用点零变化** ✓
 *
 * ★ 观感参照（用户点名）：原实现 0.8.9 的四条侧边小频谱
 * （`IslandLeadingContent.drawSpectrum`：**垂直居中、上下对称生长、胶囊、最小高度**）
 * —— ★ **柱数与数据源仍按我们的设计**（五根、实时频谱全频段重采样），**只取呈现形态** ✓
 *
 * @param widthPx 可用宽度（px）。`forceBarCount > 0` 时**不参与计算**（仍收下是为了
 *   让调用方的实参表在两档间保持一致，漏传会编译失败）。
 * @param pitchPx 目标柱间距（px）。
 * @param forceBarCount **固定条数**。`> 0` ⇒ 原样采用；`<= 0` ⇒ 按宽度推导。
 */
fun spectrumBarCountFor(
    widthPx: Int,
    pitchPx: Float,
    forceBarCount: Int,
): Int {
    if (forceBarCount > 0) return forceBarCount.coerceIn(1, SPECTRUM_MAX_BARS)
    return spectrumBarCount(widthPx, pitchPx = pitchPx)
}

/**
 * ★★★ 2026-10-11（**U13**）：**插槽小频谱的固定条数** —— 用户设计定案「**五根**」。
 *
 * ⚠️ **2026-10-13（U16）后本函数是"默认档"** —— 条数改由用户设置
 * `island.right_slot.spectrum_bars` 决定（4 / 5 / 6）⇒ 消费点请用 [slotSideBarCountFor]。
 * ★ 本函数保留为**默认值的唯一来源**（= 5），供注册表默认值 / 迁移 / 探针基线引用 ✓
 *
 * ★ 与 [RIGHT_BAR_COUNT] 同值（5）⇒ **插槽宽的让位算式**（`RIGHT_BAR_COUNT ×
 * [SPECTRUM_BAR_PITCH_PX]`）与**绘制条数**天然一致，两处不可能漂移 ✓
 */
fun slotSideBarCount(): Int = RIGHT_BAR_COUNT

/**
 * ★★★ 2026-10-13（**U16**）：**插槽小频谱的条数** —— 由设置键决定 —— **唯一出口**。
 *
 * ## 用户原话（逐字）
 *
 * > 「**新增5条小频谱的频谱条数选择（4和6根）**」
 *
 * ## 判据（三档 · 默认 5）
 *
 * | wire | 条数 |
 * |---|---|
 * | `"4"` | 4 |
 * | `"5"` | 5（★ 默认 = 既有观感 ⇒ **零回归**） |
 * | `"6"` | 6 |
 * | **其余 / `null` / 空串 / 未知值** | ★ **[RIGHT_BAR_COUNT]（5）** |
 *
 * ## ★ 为什么未知值**回落 5**（而不是"报错 / 不画"）
 *
 * 与 `CoverPalette.resolve`（未知档走 `manual`）、`ClockGlyphs.normalizeStyle`
 * （未知值退回七段）**同一条收敛纪律**：设置来自外部 JSON，**不能假设它合法**；
 * 退回**既有默认观感**总比"画不出来 / 崩"强 ✓
 *
 * ## ⚠️ 只对 `SPECTRUM` 档生效
 *
 * `RADIAL` / `RING` 的条数由**内缘弧长**推（见 [polarBarCount]）——
 * 本函数**不参与**那两档（它们的调用点不读它）✓
 *
 * @param wire `island.right_slot.spectrum_bars` 的值（`null` = 键缺失 ⇒ 走默认）
 */
fun slotSideBarCountFor(wire: String?): Int = when (wire?.trim()) {
    "4" -> 4
    "6" -> 6
    // "5" 与一切未知值都落这里 —— ★ 显式写 `else` 而不是 `"5" ->`，
    //   让"未知值回落默认"这条**一眼可见**（不给将来留"漏一档就静默变 0"的缝）。
    else -> RIGHT_BAR_COUNT
}

/**
 * ★★★ 2026-10-11（**U13**）：插槽小频谱的**柱间距** —— **固定值，不走 AUTO**。
 *
 * ## 为什么插槽档**不跟** `spectrum.bar_pitch_mode = AUTO`
 *
 * AUTO 的判据是「**岛有多宽**」⇒ 它需要"岛宽"这个输入（见 `spectrumAutoPitch`），
 * 而**插槽的可用宽是固定的 28 px**（56 − 两侧内缩）⇒ ★ 用 AUTO 会让插槽的条距
 * 随**岛宽**抖动，而"5 根塞 28 px"这件事与岛宽**无关** ✓
 *
 * ★ 故插槽档恒用 [SPECTRUM_BAR_PITCH_PX]（7 px）—— 与"5 根"一起构成
 * **插槽自己的几何常量**，不随任何设置漂移 ✓
 */
fun slotSideBarPitchPx(): Float = SPECTRUM_BAR_PITCH_PX

/**
 * ★★★ 2026-10-11（**BUG-166 · 补**）：插槽档的**目标柱距**（px）—— **唯一出口**。
 *
 * ## ★★ 它修的是"**死设置**"（用户设了「实心」，插槽却一直画"分离的齿"）
 *
 * 用户 2026-09-22 原话（逐字）：
 * 「频谱条手动间隙改为 **0-200px**，**0.1px 梯度**的滑杆，**当间距为 0px 时呈现实心频谱**。」
 * —— 而插槽三档**一直忽略**这个值（U13 为防 `AUTO` 抖动，把插槽柱距**钉成 7**）
 * ⇒ ★ 用户配置 `FIXED + 0`（= 实心）时，插槽画的却是**分离的齿 / 花瓣**
 * ⇒ 用户 2026-10-11 报障「**放射状和环状频谱的频谱条太丑了**」✓
 *
 * ## 判据表（★ 用户 2026-10-11 拍板「**只极坐标两档跟随**」）
 *
 * | 档 | `AUTO` | `FIXED` |
 * |---|---|---|
 * | `SPECTRUM`（5 根侧边条） | ★ **固定 7** | ★ **固定 7** |
 * | `RADIAL` / `RING`（极坐标） | ★ 固定 7 | ★ **跟随用户值**（含 `0` ⇒ **实心**） |
 *
 * ## 为什么 5 根侧边条**不跟随**
 *
 * 用户的设计定案是「**我们的小频谱设计是五根**」（U13）—— 若跟随实心，
 * 五根会因**无缝隙**而并成**一块实心矩形** ⇒ "五根"的辨识度**直接消失** ✗
 * ⇒ 用户拍板：**5 根保持分离**（保辨识度），**极坐标两档跟随**（连续环/连续放射是**合法风格**）✓
 *
 * ## 为什么 `AUTO` 一律回落到固定默认
 *
 * `AUTO` 的判据是「**岛有多宽**」（`spectrumAutoPitch`），而**插槽的可用宽是固定的**
 * （侧边条 28 px / 极坐标方形 = 岛高）⇒ 用 `AUTO` 会让插槽的条距随**岛宽**抖动，
 * 而"插槽里放几根"与岛宽**无关** ✓（这条是 U13 立的，本批**保留**）
 *
 * @param isPolar 是否极坐标两档（`RADIAL` / `RING`）—— 判据见 [isPolarSlotOccupant]
 * @param auto `spectrum.bar_pitch_mode == AUTO`
 * @param userPitchPx `spectrum.bar_pitch_tenth_px / 10f`（`0` = 实心）
 */
fun slotBarPitchPx(isPolar: Boolean, auto: Boolean, userPitchPx: Float): Float = when {
    !isPolar -> slotSideBarPitchPx()
    auto -> slotSideBarPitchPx()
    // ★★★ 2026-10-12（**BUG-176**）：★ 极坐标档的**柱距恒为固定默认**（不再吃用户的 0）✓
    //
    // ## 用户报障（逐字）
    //
    // > 「**放射状频谱看不出频谱条间距，建议固定条数和间距，同时条间不重叠。**」
    //
    // ## 根因：★ 用户设了 `bar_pitch = 0`（"实心频谱"），而极坐标档**照抄了这个 0** ✗
    //
    // ★ `0` ⇒ `spectrumBarSlot(slot, solid = true)` 返回 `gap = 0` ⇒ **缝为 0** ⇒
    //   ★★ 极坐标下一圈柱**首尾相接成一整片** ⇒ **看不出任何间距** ✗
    // ★ 而"实心"这个诉求是**直角坐标**的观感取舍（"不要一栅栏缝"）——
    //   ★★ 它在**极坐标**下的表现是"整圈糊成一个圆盘" ✗ —— 与用户要的"看得见条"相反 ✓
    //
    // ## 判据：★ 与"五条侧边条"**同一条规则**
    //
    // ★ `slotSideBarPitchPx()`（固定 7 px）就是"**辨识度不能被'实心'设置吃掉**"那条 ✓
    //   （见该函数的 KDoc：`AUTO` 一律回落固定默认）
    // ★★ 极坐标同理 ⇒ ★ `0` 视为"未给" ⇒ 回落固定默认 ✓
    //   ⇒ ★ **条数 = 周长 / 7**（与柱宽同源）⇒ ★★ **条间不重叠**（结构上保证）✓
    //
    // ⚠️ **> 0 的用户值仍然生效** —— ★ 只把 `0`（实心）这一档挡掉 ✓
    else -> if (userPitchPx.isFinite() && userPitchPx > 0f) userPitchPx else slotSideBarPitchPx()
}

/**
 * ★★★ 2026-10-11（**BUG-166 · 补**）：极坐标**条数**的间距基准 —— 与 [slotBarPitchPx] **分开**。
 *
 * ## 为什么不能直接用 [slotBarPitchPx] 的返回值
 *
 * `0`（实心）时 `polarBarCount(spec, 0)` 会返回 **1**（该函数对 `pitch <= 0` 的退化处理）
 * ⇒ ★ **整圈只剩一根柱** ✗
 * ⇒ 但"实心"与"条数"是**两件事**：★ **间距是风格**（有无缝），**条数是几何**（画几根）。
 * 实心档照样要"画多少根" —— 且此时**没有"不粘连"约束**（粘连正是实心想要的）✓
 *
 * ## 取固定默认作基准的理由
 *
 * 实心档的条数决定的是**轮廓的分辨率**（外缘折线的段数）。取 [slotSideBarPitchPx]（7）
 * ⇒ 与"有缝档"**同一个条数**（`RADIAL` 10 根 / `RING` 15 根）⇒
 * ★ 用户切换"有缝 ↔ 实心"时**只变缝、不变条数** ⇒ 观感连续、不跳 ✓
 *
 * @param userPitchPx `spectrum.bar_pitch_tenth_px / 10f`。`> 0` ⇒ 原样用它（保证内缘不粘连）
 */
fun polarCountPitchPx(userPitchPx: Float): Float =
    if (userPitchPx.isFinite() && userPitchPx > 0f) userPitchPx else slotSideBarPitchPx()

/**
 * 频谱**带矩形**的几何：贴哪条边、多高、柱子朝哪个方向长。
 *
 * @param top 带矩形上边缘（面板局部坐标，px）
 * @param bottom 带矩形下边缘
 * @param growDown 柱子的**生长方向**。`true` = 从 [top] 往下长（贴顶边档），
 *   `false` = 从 [bottom] 往上长（贴底边档，也是 `SpectralPainter` 唯一会做的方向）
 */
data class SpectrumBandGeometry(
    val top: Float,
    val bottom: Float,
    val growDown: Boolean,
) {
    /** 带高（px）。恒等于 `bottom - top`，不额外留白 —— 见 [spectrumBandGeometry] 的论证。 */
    val height: Float get() = bottom - top

    /**
     * 柱子的**基线** —— 柱子从这条线朝 [growDown] 的方向长出去。
     *
     * 单独抽出来是因为**它才是"贴边"的判据**：`anchor = TOP` 的语义是
     * "柱子的**顶边**贴着歌词区顶边"（见 `SettingRegistry` 里 `spectrum.anchor`
     * 的逐字注释），也就是**基线在顶边、柱身向下**。
     * 只挪 [top] / [bottom] 而不改基线，是 2026-09-18 那个缺陷的形态 —— 见
     * [spectrumBandGeometry] 的 KDoc。
     */
    val baselineY: Float get() = if (growDown) top else bottom
}

/**
 * 由「歌词区矩形 + 高度百分比 + 贴哪条边」推出频谱带矩形 —— **纯函数，可被探针断言**。
 *
 * ## 用户原话（本轮反馈，逐字）
 *
 * > 「频谱**贴边（贴顶边）逻辑有误，贴不到顶边**」
 *
 * ## ★ 根因：只挪了带矩形，没改**基线**
 *
 * `SpectralPainter.paintBars` 恒以 `bounds.y + bounds.height` 为基线**向上**生长
 * （见那里的 `baseY`）。而改动前的调用方只做了这一件事：
 *
 * ```
 * anchor == TOP → bottom = areaTop + bandHeight;  top = bottom - bandHeight
 * ```
 *
 * 于是 `spectrum.height_pct` 取**默认值 100** 时：
 *
 * | 档 | top | bottom | 柱子基线 |
 * |---|---|---|---|
 * | `BOTTOM`（默认） | `areaTop` | `areaTop + areaHeight` | `areaTop + areaHeight`（歌词区**底**边） |
 * | `TOP` | `areaTop` | `areaTop + areaHeight` | `areaTop + areaHeight`（同上） |
 *
 * 两档**逐像素相同** —— 用户选了"贴顶边"却看不到任何变化，于是报「贴不到顶边」。
 * 这不是"差几像素"，是**这个设置在默认档下完全失效**。
 *
 * ## 对称语义的出处（不是我定的）
 *
 * `SettingRegistry` 里 `spectrum.anchor` 的注释逐字写着
 * 「默认 BOTTOM：与改动前一致（**柱子的底边贴着歌词区底部往上长**）」。
 * 那么 `TOP` 的对称含义必然是「**柱子的顶边**贴着歌词区顶边**往下**长」——
 * 故本函数不只算矩形，还给出 [SpectrumBandGeometry.growDown]。
 *
 * ## 为什么"两端都不额外留白"
 *
 * 贴边的那一侧必须**严丝合缝**（`top` 恒 `= areaTop`），否则"贴边"名不副实；
 * 朝内的那一侧本就靠纵向渐变淡出（见 `IslandWindow.verticalFadePaint`），
 * 留白反而会让 `100%` 看着不像占满。故带高 = `areaHeight × heightFraction`，
 * 两端都不再扣 `VERTICAL_PADDING`。
 *
 * ## 归一化的责任在调用方
 *
 * [heightFraction] 是**已经夹好**的 `0..1`（调用方按 `MIN_SPECTRUM_HEIGHT_PCT..100`
 * 夹取）。本函数只把结果夹到"至少 1 px"，避免 0 高矩形让
 * `SpectralPainter.paint` 的第一道守卫直接返回（那会让"高度滑杆拉到最小"表现为
 * "频谱整个消失"，与 `spectrum.enabled` 的语义重叠）。
 *
 * @param areaTop 歌词区上边缘（面板局部坐标，px）
 * @param areaHeight 歌词区高度（px）
 * @param heightFraction 带高占歌词区高度的比例（`0..1`，由调用方夹取）
 * @param anchorTop `true` = 贴歌词区**顶**边（柱子向下长）；`false` = 贴底边（柱子向上长）
 */
fun spectrumBandGeometry(
    areaTop: Float,
    areaHeight: Float,
    heightFraction: Float,
    anchorTop: Boolean,
): SpectrumBandGeometry {
    val bandHeight = (areaHeight * heightFraction).coerceAtLeast(1f)
    return if (anchorTop) {
        // 顶边严丝合缝：top 恒 = areaTop，柱身向下（growDown）。
        SpectrumBandGeometry(top = areaTop, bottom = areaTop + bandHeight, growDown = true)
    } else {
        // 底边严丝合缝：bottom 恒 = areaTop + areaHeight，柱身向上。
        SpectrumBandGeometry(
            top = areaTop + areaHeight - bandHeight,
            bottom = areaTop + areaHeight,
            growDown = false,
        )
    }
}

/**
 * 「**频谱跳动幅度**」的拉伸公式 —— 围绕中值 `0.5` 放大偏离量。
 *
 * ## 用户原话（逐字）
 *
 * > 「频谱设置增加一项**相对跳动高度**（即频谱高低跳动更明显）。」
 *
 * ## 为什么**不是**乘一个系数（`h * k`）—— 这是本函数存在的唯一理由
 *
 * 乘法把整条曲线**整体推高**：安静段（`h = 0.2`）也被抬到 `0.6`。
 * 用户看到的不是"跳得更明显"，而是"频谱整体变高了、底部垫了一层" ——
 * 他要的是**动态范围变大**，不是**基线抬高**。
 *
 * 围绕中值拉伸则**中值不动**：`0.5` 恒映射到 `0.5`，只有偏离中值的部分被放大。
 * 于是安静段仍低、峰值更高，**高低差**变大 —— 这才是"跳动更明显"。
 *
 * | h（输入） | k=1（100%） | k=2 | k=3（300%） |
 * |---|---|---|---|
 * | 0.00 | 0.00 | 0.00（被夹） | 0.00（被夹） |
 * | 0.25 | 0.25 | 0.00 | 0.00 |
 * | 0.50 | 0.50 | 0.50 | 0.50 |
 * | 0.75 | 0.75 | 1.00 | 1.00 |
 * | 1.00 | 1.00 | 1.00 | 1.00 |
 *
 * ## 为什么 `scale == 1f` 要**提前返回**（而不是照样算一遍）
 *
 * `0.5f + (h - 0.5f) * 1f` 在数学上恒等于 `h`，但在**浮点**上不成立：
 * 两次加减各引入一次舍入。实测 `h = 0.1f` 时算得 `0.099999994f`（≠ `0.1f`）。
 * 而 `100%` 是**默认值**，也就是"绝大多数用户看到的画面"——
 * 默认档下画面必须与改动前**逐位相同**，否则这次改动就带了一个无人要求的行为变化。
 *
 * @param h 归一化柱高（`0..1`）。越界值先被夹到 `0..1` ——
 *   注意 `coerceIn` 对**已在范围内**的值返回原值本身，故不影响上面那条逐位保证
 * @param scale 拉伸倍数。`1f` = 原样；`> 1f` = 放大跳动；`< 1f` = 压缩（注册表不提供，纯函数上仍成立）
 */
fun stretchAroundMid(h: Float, scale: Float): Float {
    val v = h.coerceIn(0f, 1f)
    // 见 KDoc：100% 必须逐位相同，不能靠"算一遍恰好相等"。
    if (scale == 1f) return v
    return (0.5f + (v - 0.5f) * scale).coerceIn(0f, 1f)
}

/**
 * 「**没有进度**」哨兵 —— [playbackProgress] 在时长未知时返回它。
 *
 * 为什么用负数而不是 `0f`：`0f` 是一个**合法进度**（刚开播）。
 * 两者语义完全不同 —— `0f` 表示"一句都没播"（整条频谱都是未播放色），
 * 哨兵表示"**不知道**播到哪了"（**不染色**，保持原样）。
 * 用一个不可能出现的值表达"未知"，比再加一个 `Boolean` 参数更难写错。
 */
const val NO_PROGRESS: Float = -1f

/**
 * 播放进度比例 —— 供 `spectrum.progress_tint`（频谱色差表示进度）使用。
 *
 * ## 用户原话（逐字）
 *
 * > 「允许通过**频谱色差**表示歌曲播放进度。」
 *
 * ## 时长未知时返回 [NO_PROGRESS]（**不染色**），而不是 0
 *
 * 时长来自宿主回调之外的一条独立链路（`TrackDurationLoader` 读本地音频文件），
 * 它**可能还没读完**（刚换曲的头几百毫秒）或**根本读不到**（流媒体、文件被占）。
 * 那时 `durationMs = 0`。
 *
 * 若把这种情况当成 `0`，画面会表现为"**整条频谱一直是未播放色**"——
 * 用户会以为"开了这个设置没生效"。返回哨兵则让绘制层**完全跳过染色**，
 * 画面与没开这个设置时一致 —— 这是唯一不会误导用户的降级行为。
 *
 * ## ⚠️ 2026-09-24 更正：`positionMs` **不是**「未知」（本条原先写错了 ✗）
 *
 * 原写「同理，`positionMs` 为负也归为未知」✗ —— **与本文件的实现相反**，
 * 也与另外两处实现相反 ✗。**取证**（四处一致 ⇒ 本条是唯一的异类）：
 *   · `PlaybackTimeline.kt:80`：`positionMs` 初值 = **`0L`** ⇒ 负值在实践中**不会出现** ✓
 *   · `TimelineSnapshot.kt:87`：默认 `positionMs = 0L` ✓
 *   · `IslandPolicy.kt:557/565`：「`positionMs <= 0`（**曲首** / 未知）→ 返回 `0`」✓
 *   · `SpectrumDecayProbe`：`-1 → 0f` ✓（且 `(-1, 0L) → NO_PROGRESS` ✓，**时长优先**）
 *
 * ⇒ **`positionMs <= 0` 按「曲首」处理（合法的 `0` 进度）** ✓ ——
 *   只有 **`durationMs <= 0`**（时长读不到）才走 [NO_PROGRESS] 哨兵 ✓。
 *
 * @param positionMs 当前播放位置（毫秒）。`<= 0` 按**曲首**处理（返回 `0`）
 * @param durationMs 总时长（毫秒）。`<= 0` = 未知
 * @return `0f..1f` 的已播放比例；未知时返回 [NO_PROGRESS]
 */
fun playbackProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return NO_PROGRESS
    // ✅ **2026-09-24 定论**：这里原先被我判为「两处权威互相矛盾」✗ —— 现已取证，**代码是对的** ✓。
    //   证据：`PlaybackTimeline.positionMs` 初值 = `0L`（负值不会出现）✓；
    //   `IslandPolicy.kt:557/565` 也写「`<= 0`（曲首/未知）→ `0`」✓；
    //   `SpectrumDecayProbe` 断言 `-1 → 0f` 且 `(-1, 0L) → NO_PROGRESS`（时长优先）✓。
    //   ⇒ **错的是上面 KDoc 里「负值 = 未知」那句，已更正** ✓（见该段）。
    //   ⚠️ 反过来改这里（返回哨兵）会让 `SpectrumDecayProbe` 红 ✓ —— 别再试。
    if (positionMs <= 0L) return 0f
    return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/**
 * 把 [src] 的 `src.size` 个频段**线性插值**成 [target] 个值，写进 [out]。
 *
 * ## 这是插值，不是"提高分辨率"（必须说清楚）
 *
 * 输出里的中间值是相邻两个源频段的加权平均，**不含新信息**。
 * 它的作用只有一个：让"变宽 → 条数变多"在视觉上成立，
 * 且柱子高度**平滑过渡**（否则会出现"相邻两根一模一样、第三根突然跳变"的阶梯感，
 * 那比柱子少还难看）。
 *
 * ## 映射用"端点对齐"而不是"分段均分"
 *
 * `p = j · (m-1) / (n-1)`：第 0 根对第 0 个源、第 n-1 根对第 m-1 个源。
 * 于是**两端频段一定被完整表达**（最左 = 最低频、最右 = 最高频）。
 *
 * 若改用分段均分（`p = j · m / n`），当 n > m 时最右那根会落在
 * `m·(n-1)/n < m-1` 处 —— **最高频段永远画不出来**，
 * 表现为"频谱右端总是差一截"。这个偏差在 n 远大于 m 时才明显，
 * 而本轮正是 n（~85）远大于 m（24），故必须用端点对齐。
 *
 * ## 分配纪律
 *
 * **不分配新数组**：结果写进调用方传入的 [out]（`SpectralPainter` 复用一个
 * 字段缓冲）。本项目对绘制热路径的每帧分配是有纪律的
 * （见 `RenderCache` 关于 `Color` 缓存的论证）。
 *
 * @param src 源频段（0..1）
 * @param target 目标条数（≤ [out].size，否则只填前 `out.size` 个）
 * @param out 输出缓冲（**会被原地修改**）
 * @return [out]（便于链式使用）
 */
fun resampleBands(src: FloatArray, target: Int, out: FloatArray): FloatArray {
    val n = target.coerceAtMost(out.size)
    val m = src.size
    if (n <= 0) return out
    if (m == 0) {
        for (j in 0 until n) out[j] = 0f
        return out
    }
    if (m == 1) {
        val v = src[0]
        for (j in 0 until n) out[j] = v
        return out
    }
    if (n == 1) {
        out[0] = src[m - 1]
        return out
    }
    val span = (m - 1).toFloat()
    val step = span / (n - 1).toFloat()
    for (j in 0 until n) {
        val p = j * step
        val i0 = p.toInt().coerceIn(0, m - 1)
        val i1 = (i0 + 1).coerceAtMost(m - 1)
        val t = p - i0
        out[j] = src[i0] * (1f - t) + src[i1] * t
    }
    return out
}

/**
 * 频谱柱的**水平几何**：给定每柱可用空间 [slotPx]，返回 `(缝宽, 柱宽)`。
 *
 * ## ★★ 2026-09-21：为什么地板是**动态**的（真机"频谱糊成一片"的修复）
 *
 * ### 原记（错）
 *
 * `gap = max(slot × 0.30, 1f)`、`barWidth = max(slot − gap, 1f)` ——
 * 两者**各自**有 1px 地板，理由写作"缝低于 1px 会被抗锯齿糊成灰雾"。
 *
 * ### 实为
 *
 * 那条理由只在 `slotPx` 足够大时成立。而 `slotPx = bounds.width / count`，
 * `count` 被 `SPECTRUM_MAX_BARS`（**1024**）夹住 —— 600px 宽下
 * `slotPx ≈ 0.586px`，而 `barWidth + gap >= 1 + 1 = 2px > slotPx`
 * ⇒ **相邻柱子重叠约 3.4 倍** ⇒ 整条频谱在水平方向**连成一片**。
 *
 * ### 现在的取法
 *
 * `floor = min(0.25, slotPx / 2)`：
 * · `slotPx` 大时 → `floor = 0.25`（比 1px 更细，AA 仍能分辨）；
 * · `slotPx` 小时 → `floor = slotPx / 2` ⇒ **`barWidth + gap` 恰好 = `slotPx`**
 *   ⇒ **结构上不可能重叠**（靠结构消除状态，而不是靠纪律保证不重叠）。
 *
 * ⚠️ `SPECTRUM_BAR_GAP_RATIO = 0.30 < 0.5` ⇒ `barWidth = slot − gap >= 0.7 × slot >= slot/2`
 * ⇒ `coerceAtLeast(floor)` 对 `barWidth` **不生效**，两者之和恒为 `slotPx`。
 * 这条恒等式由探针断言钉住（`SpectrumDecayProbe`）。
 *
 * ## ★★ 2026-09-22：新增 [solid] 档 —— 「手动间隙 = 0 ⇒ 实心频谱」
 *
 * 用户原话（逐字）：
 *
 * > 「频谱条手动间隙改为 **0-200px**，**0.1px 梯度**的滑杆，
 * >   **当间距为 0px 时呈现实心频谱**。」
 *
 * ### 为什么"0"不能走上面的比例式
 *
 * 比例式恒给 `gap = 0.30 × slotPx > 0`（还有 `floor > 0` 兜底）——
 * 也就是**无论间距拖到多小，缝都还在**。故 0 档必须**换一条支路**，
 * 而不是把比例式的外推到底：`gap = 0`、`barWidth = slotPx`
 * ⇒ 相邻柱的左右边**逐像素重合**（`left(i+1) = left(i) + slotPx`，而柱宽正是 `slotPx`）。
 *
 * ### ⚠️ 只做到这里**还不够** —— 还有第二个来源：**圆角**
 *
 * 柱宽吃满只保证"矩形之间无缝"。而 `paintBars` 给每根柱**四角倒圆**
 * （`arc = min(柱宽, MIN_BAR_HEIGHT)`，见 [spectrumBarCornerPx]）。
 * 相邻两个圆角矩形**相接**时，接缝处两个圆角各自向内退 `arc/2`
 * ⇒ 上下两端各留一个**腰形缺口**（高度约 `arc`）——
 * 那正是 2026-09-18 修过的"双峰中线一条腰缝"的**同一个几何**，
 * 只是这次发生在**每两根柱之间**。故 0 档必须**同时**把圆角取 0（方角）。
 * 这条由 [spectrumBarCornerPx] 单独定义、由探针**像素级**断言。
 *
 * ### 为什么"非 0 档"一字不改
 *
 * `solid = false` 时本函数**逐位**返回改动前的结果（同一个表达式、同一个顺序）——
 * 这样"间距 > 0 的老观感"没有回归风险，而 0 档是**新增的一条支路**。
 *
 * ### ⚠️ 如实记：实心档下这一支路对**画面**是**冗余**的（变异测试的产物）
 *
 * 实心档的"实心"由**三条**规则共同保证，其中 [spectrumBarRectX] 的**整像素吸附**
 * 已经**顺带**把缝吃掉了（那里的左右边缘都由 `slot` 推出，与本函数的返回值无关）。
 * 本轮把三处分别**单独**改回去做变异测试，实测：
 *
 * | 变异 | 纯几何断言 | 像素断言（最小 alpha / 未画满列数） |
 * |---|---|---|
 * | 缝保留地板（本支路失效） | **红 2 条** | 仍绿（255 / 0）—— 吸附把它盖住了 |
 * | 圆角不取 0 | 红 1 条 | **红**（196 / 48） |
 * | 去掉整像素吸附 | 红 3 条 | **红**（198 / 16） |
 *
 * 故**留着**本支路的理由不是"它在画面上起作用"，而是：
 * ① 它是"缝 = 0"这条**语义**的定义点（`SpectrumSolidGapProbe` §② 直接断言它）；
 * ② 它让"实心"**不依赖**吸附这一条实现细节 —— 将来若有人改吸附方式，
 *    缝仍然是 0（只可能重新引入"边界落在像素内部"那条，而那是**另一条**断言守的）。
 * 反过来说：**别**以为删掉吸附后画面还是实心（上表第三行已经否证了这一点）。
 *
 * @param slotPx 每根柱子分到的水平空间（含缝）
 * @param solid `true` = 手动间隙为 0（实心档）：缝 0、柱宽吃满整格。
 *   判据由 [isSolidSpectrumPitch] 给出（**不要**在这里重复写 `pitch <= 0`）。
 * @return `Pair(缝宽, 柱宽)`。非实心档两者之和恒 ≤ [slotPx]；
 *   **实心档缝恒为 `0f`、柱宽恒为 [slotPx]**
 */
fun spectrumBarSlot(slotPx: Float, solid: Boolean = false): Pair<Float, Float> {
    // 极小 / 非法 slot 的兜底：负值或 0 时给一对最小几何，避免负宽度
    // （Java2D 收到负宽度会**翻方向**画，那是"看起来像随机竖线"的来源）。
    if (!slotPx.isFinite() || slotPx <= 0f) return 0.05f to 0.05f
    // ★ 2026-09-22 实心档：缝 0、柱宽吃满整格。放在地板之前 ——
    //   地板（`floorPx`）的存在意义是"非 0 档别把缝压到看不见"，
    //   而实心档**要的正是看不见缝**，两者语义相反，不能共存。
    if (solid) return 0f to slotPx
    val floorPx = minOf(0.25f, slotPx / 2f)
    val gap = (slotPx * 0.30f).coerceAtLeast(floorPx)
    val barWidth = (slotPx - gap).coerceAtLeast(floorPx)
    return gap to barWidth
}

/**
 * 「**手动间隙 = 0 ⇒ 实心频谱**」的**唯一判据**（纯函数，可被探针断言）。
 *
 * 用户原话（逐字）：
 *
 * > 「频谱条手动间隙改为 **0-200px**，**0.1px 梯度**的滑杆，
 * >   **当间距为 0px 时呈现实心频谱**。」
 *
 * ## 为什么判据写在**柱间距**上、而不是在"缝宽"上
 *
 * 本项的键是 `spectrum.bar_pitch_tenth_px` —— 它的语义是**柱间距**（条 + 缝），
 * 见 `SPECTRUM_BAR_PITCH_PX` 的 KDoc。用户口里的"手动间隙"就是这一项
 * （滑杆的"固定"档）。故"拖到 0"在代码里表现为**传入的 `barPitchPx = 0`**。
 *
 * ⚠️ `AUTO` 档**永远给不出 0**（`spectrumAutoPitch` 的下限是
 *   `SPECTRUM_AUTO_MIN_PITCH_PX = 0.1`）⇒ 实心档只可能由"固定 + 0"触发，
 *   不会因为自动档在极窄词岛上算出 0 而**意外**变实心。
 *
 * ## 为什么 `<= 0` 而不是 `== 0`
 *
 * 非法值（负数、`-0.0f`）与 0 语义相同：都表示"不要缝"。
 * `NaN` 也落在 `true` 一侧 —— 与 `spectrumBarCount` 对非法 `pitchPx`
 * 的兜底（回落到 `minBars`）方向一致：宁可画成实心，也不画出随机竖线。
 */
fun isSolidSpectrumPitch(pitchPx: Float): Boolean = !(pitchPx > 0f)

/**
 * 频谱柱的**端头形状**（设置项建议键名 `spectrum.bar_corner`）。
 *
 * ## 用户原话（逐字）
 *
 * > 「**频谱条的样式提供圆角和方角**」
 *
 * ## 为什么做成枚举 + 一个比例，而不是 `Boolean`
 *
 * 判据是"**这个维度上还有没有别的合理取值**"：
 * 圆角与方角之间还存在"半圆 / 微圆角"（如 `cornerFraction = 0.5` 或 `0.25`）——
 * 本项目定位是**高度自由、个性化的桌面歌词定制平台**（用户 2026-09-20 原话），
 * 故"将来要不要给更细的圆角档"是一个**可预期**的问题。
 * 把它表达成"**比例**"之后，加档位只需加一个枚举值，**绘制侧一行都不用改**。
 *
 * ## `cornerFraction` 的语义
 *
 * 它是**圆角上限的倍率**，而不是像素值：
 *
 * ```
 * 实际圆角 = min(柱宽, maxCornerPx) × cornerFraction
 * ```
 *
 * 于是"圆角"档的观感**随柱宽自动缩放**（细条圆、粗条也圆），
 * 不需要为不同柱宽各调一个数字 —— 这正是改动前 `min(柱宽, MIN_BAR_HEIGHT)`
 * 那条规则的性质，本枚举**不改变它**。
 *
 * ## 与 [isSolidSpectrumPitch] 的关系（**实心档优先级更高**）
 *
 * 手动间距 = 0 时，圆角**必须是 0**（否则相邻柱之间仍有腰形缺口，见
 * [spectrumBarCornerPx] 的论证）—— 那是"实心"这个语义的**硬要求**，
 * 不因用户选了 [ROUND] 而改变。故实心档下本枚举**被忽略**。
 */
enum class SpectrumBarCorner(
    /**
     * 圆角上限的倍率。`1f` = 完全圆头（改动前的行为）；`0f` = 方角。
     * 中间值（如 `0.5f`）是"半圆角"，本枚举暂不提供，但绘制侧已支持。
     */
    val cornerFraction: Float,
) {
    /** **圆角**（完全圆头）。与改动前的观感**逐像素相同**，故是默认值。 */
    ROUND(1f),

    /** **方角**（直角矩形）。 */
    SQUARE(0f),
    ;

    companion object {
        /**
         * 出厂默认。取 [ROUND] —— **与改动前逐像素一致**，故"新增这个维度"本身
         * 不带来任何观感变化（零回归）；用户要方角时显式选 [SQUARE]。
         */
        val DEFAULT = ROUND

        /** 把设置里的字符串解析成形状。**未知值回退 [DEFAULT]**（不抛）。 */
        fun parse(key: String?): SpectrumBarCorner =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 柱的**圆角半径**（px）—— 实心档必须为 0，否则相邻柱之间仍有腰形缺口。
 *
 * ## ★★ 2026-09-22：为什么这个函数必须存在（实心档的第二道来源）
 *
 * `paintBars` 的圆角取 `min(柱宽, MIN_BAR_HEIGHT)`（完全圆头，条形频谱的常见做法）。
 * 柱宽吃满整格（[spectrumBarSlot] 的实心档）只保证**矩形**之间无缝；
 * 而两个**圆角**矩形相接时，接缝两侧各向内退 `arc/2` ⇒ 上下各一个高约 `arc`
 * 的腰形缺口。于是"间距拖到 0"看起来仍是一根根柱子 —— **只是缝变窄了**，
 * 而不是用户要的**实心**。
 *
 * ## 取 0 是**唯一**能满足"实心"的取法
 *
 * 与 [spectrumBarPieces] 的论证同源：内端只能取直角。这里是**左右两侧都是内端**
 * （每根柱的左右边都与邻柱相接），故**四角**都得是直角。
 *
 * ## ★★ 2026-09-23：新增 [corner] —— 「频谱条的样式提供圆角和方角」
 *
 * 用户原话（逐字）：「**频谱条的样式提供圆角和方角**」。
 * 于是圆角半径多了**一个维度**（形状），但**优先级是固定的**：
 *
 * | 档位 | 结果 |
 * | --- | --- |
 * | `solid = true`（间距 = 0） | **恒 0**（方角）—— [corner] 被忽略，理由见上 |
 * | `solid = false` + [SpectrumBarCorner.ROUND] | `min(柱宽, maxCornerPx)`（**与改动前逐位相同**） |
 * | `solid = false` + [SpectrumBarCorner.SQUARE] | `0` |
 *
 * ⚠️ 顺序不能反：先判 `solid` 再乘 `cornerFraction`，而不是先乘再判 ——
 * 否则实心档在 `SQUARE` 下恰好也是 0、看起来"对"，但一旦将来有人给
 * `SQUARE` 一个非零比例（如"微方角"），实心档就会**静默长出缺口**。
 *
 * @param barWidthPx 柱宽（px）
 * @param maxCornerPx 圆角上限。调用方传 `MIN_BAR_HEIGHT` —— 它仍是**单一来源**
 *   （`SpectralPainter` 的私有常量），本函数**不复制**那个数字。
 * @param solid 见 [isSolidSpectrumPitch]
 * @param corner 端头形状（`spectrum.bar_corner`）。默认 [SpectrumBarCorner.ROUND]
 *   = 改动前行为（零回归）。
 * @return 实心档恒 `0f`；非实心档 = `min(柱宽, maxCornerPx) × corner.cornerFraction`
 */
fun spectrumBarCornerPx(
    barWidthPx: Float,
    maxCornerPx: Float,
    solid: Boolean,
    corner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,
): Float {
    if (solid) return 0f
    if (!barWidthPx.isFinite() || !maxCornerPx.isFinite()) return 0f
    val full = barWidthPx.coerceAtMost(maxCornerPx)
    // `cornerFraction` 是编译期常量（枚举构造参数），乘法在浮点上精确：
    // 1f 时逐位等于 `full`（`x * 1f == x` 对任何有限 float 成立）⇒ 零回归。
    return full * corner.cornerFraction
}

/**
 * 第 [index] 根柱子的**水平矩形**：`Pair(左边缘 x, 柱宽)` —— 纯函数，可被探针断言。
 *
 * ## ★★ 2026-09-22：为什么不能直接写 `left = x0 + i·slot + gap/2`
 *
 * 那是**非实心档**的写法（与改动前逐位相同），而实心档还差**最后一步**：
 * 左右边缘必须**吸附到整像素**。
 *
 * ### 不吸附会怎样（这不是洁癖，是看得见的线）
 *
 * `slot = 带宽 / 条数` 一般**不是整数**（600px / 24 = 25px 恰好是，但
 * 640 / 24 = 26.666… 不是）。缝为 0 时相邻两根柱的公共边界就落在**像素内部**：
 * 该像素被前一根覆盖 0.667、被后一根覆盖 0.333。
 *
 * ⚠️ 而 Java2D 的抗锯齿填充是**逐次合成**的（`SRC_OVER`），不是"先取并集再算覆盖"：
 * 两次填充后该像素的 alpha = `0.333 + 0.667 × (1 − 0.333)` ≈ **0.778**，
 * **不是 1.0**。于是"缝 0"的画面上每隔一格就有一条 **alpha ≈ 198/255** 的淡竖线 ——
 * 用户要的是**实心**，得到的是"没有缝但有竖纹"。
 *
 * 吸附到整像素后，每个像素**恰好被一根柱完整覆盖**（边界都在整数 x 上），
 * 逐次合成也就没有可叠加的部分 ⇒ alpha 恒为满值。
 *
 * ### 为什么"少画/多画半像素"是可以接受的
 *
 * 实心档下每根柱的宽度只可能差 1 px（26 / 27），而它旁边那根**同时**反向差 1 px ——
 * 总带宽**逐像素不变**。与"每条边界都留一条 22% 淡线"相比，这是明显的净收益。
 *
 * ### 为什么抽成函数而不是写在两处
 *
 * `paintBars` 里**有两处**需要柱的水平位置：柱体循环与峰值帽循环。
 * 本项目刚因"同一件事写两遍、只改一处"出过缺陷（`paintBars` 与 `paintWave` 的
 * `gap`/`barWidth` 就是两份），故这里只留**一个**定义点，两处都调它。
 *
 * @param x0 频谱带左边缘（px）
 * @param index 第几根柱（0 起）
 * @param slotPx 每根柱分到的水平空间（含缝）
 * @param gapPx 缝宽（来自 [spectrumBarSlot]）
 * @param barWidthPx 柱宽（来自 [spectrumBarSlot]）
 * @param solid 见 [isSolidSpectrumPitch]
 * @return 非实心档恒 `(x0 + i·slot + gap/2, barWidthPx)`（与改动前逐位相同）；
 *   实心档为整像素对齐的一对 `(左, 宽)`，且**相邻柱的右边缘与下一根的左边缘逐位相等**
 */
fun spectrumBarRectX(
    x0: Float,
    index: Int,
    slotPx: Float,
    gapPx: Float,
    barWidthPx: Float,
    solid: Boolean,
): Pair<Float, Float> {
    val leftExact = x0 + index * slotPx
    if (!solid) return (leftExact + gapPx / 2f) to barWidthPx
    // 实心档：左右边缘各吸附到最近的整像素。**左边缘由本根算、右边缘由下一根的
    // 左边缘公式算**（`round(x0 + (i+1)·slot)`）—— 这样"右边缘"与"下一根的左边缘"
    // 是**同一个表达式**，逐位相等是构造保证的，不靠"两根各自四舍五入恰好相等"。
    val left = Math.round(leftExact).toFloat()
    val right = Math.round(x0 + (index + 1) * slotPx).toFloat()
    return left to (right - left)
}

/**
 * **实心折线形态**的顶点列（唯一几何来源；真机与预览共用）—— 公开纯函数，可被探针断言。
 *
 * ## 用户原话（逐字）
 *
 * > 「实心频谱（间距 0 时条太粗 ⇒ 想要"**折线边界**"）」
 *
 * 他要的不是"无缝矩形"，而是**折线边界** —— 相邻条顶部连成**一条折线轮廓**。
 * 实心矩形在窄岛下 `slot` 小 ⇒ 条看起来"粗"（一根根柱子，只是没有缝）。
 *
 * ## ★★ 为什么不复用 [spectrumBarRectX]
 *
 * 两者**语义不同**，不可互相替代：
 *
 * | | 返回 | 含义 |
 * |---|---|---|
 * | [spectrumBarRectX] | `count` 个 `(左, 宽)` | **count 个矩形**（逐柱 `fill`） |
 * | 本函数 | 一条 `(x, y)` 点列 | **一条折线**（**一次** `fill`） |
 *
 * 逐柱 `fill` 在缝为 0 时会在相邻柱的公共边界上**两次 `SRC_OVER` 合成**
 * ⇒ `alpha < 1` 的淡竖线（[spectrumBarRectX] 的 KDoc 详述过这条）。
 * 折线**一次 `fill`** 从构造上消除了这个问题 —— 边界只被覆盖一次。
 *
 * ⚠️ 但两者的 **x 坐标必须一致**，否则"折线"与"峰值帽 / 淡出带"会在不同 x 上。
 * 故本函数**必须**用与 [spectrumBarRectX] **同一个** x 公式，而这条一致性
 * 由探针**交叉断言**（`points[2i] == spectrumBarRectX(…).first`），不是靠"我记得两边写成一样"。
 *
 * ## 顶点列的构造
 *
 * ```
 * p0     = (x0′, baseY)                  ← 左下角（起点）· x0′ = 第 0 根柱的左边缘
 * p1     = (x0, baseY − h[0])            ← 第 0 根柱的顶
 * p2     = (x1, baseY − h[1])            ← 第 1 根柱的顶
 * …
 * pn     = (x_{n−1}, baseY − h[n−1])     ← 最后一根柱的顶
 * p_{n+1}= (xn, baseY − h[n−1])          ← **末根顶保持水平**到带右端（hold-last）
 * p_{n+2}= (xn, baseY)                   ← 右下角 · xn = 最后一根柱的右边缘
 * closePath()                            ← 沿**基线**由右下角连回左下角
 * ```
 *
 * ### ★★ 2026-09-27 实测缺陷（首版）：点列漏了两个关键点，各错一次
 *
 * | # | 漏了什么 | 后果 | 只有谁能抓到 |
 * |---|---|---|---|
 * | **P1** | **左下角 `p0`** | `closePath()` 从右下角**斜穿整幅图**回到左上角 ⇒ 填充退化成**三角形**（左边缘只剩 ~1px 高） | 像素层「**基线行是否被每一列覆盖**」 |
 * | **P2** | **末根的 hold-last 顶点 `p_{n+1}`** | 顶部顶点落在**柱的左边缘**，末根**没有后继柱** ⇒ 其顶边一路斜到**基线** ⇒ **末根退化成三角形**（24 柱 640px 下是右侧 27px 的楔形） | 像素层「**整列暗缝**」（末根那几列只剩抗锯齿的残影，无满不透明像素） |
 *
 * ⚠️ 两个缺陷的共同特征，正是本项目最怕的那一类：
 * ① **不报错、不抛异常**；② **纯函数断言若按实现写就全绿**（首版正是如此）；
 * ③ 只有"**把真实像素画出来、逐列量**"才抓得到 —— 见 `SpectrumPolylineProbe` §③。
 * ⇒ 该探针的 §① 期望已改为**按本契约写**，并新增 §③ 的两条像素主判据。
 *
 * 于是**每根柱的"宽"就是相邻两个顶点之间的水平跨度**，而**顶边是相邻顶点连成的斜线** ——
 * 这正是"折线边界"。虚线（`|Δy| > 0`）由 `Path2D` 的直线段自然给出，
 * 不需要（也不应该）在这里做任何平滑/插值 —— 平滑是另一个维度，本项目**未做**。
 *
 * ## 非法输入的兜底（与 [spectrumBarRectX] 同向）
 *
 * - `count <= 0` / 非法 `x0` / `slotPx` / `baseY` ⇒ 返回 **0**（不写任何点）。
 * - `out` 容量不足 ⇒ 返回 **0**（调用方负责给够；宁可什么都不画，也不越界写）。
 * - `heights` 比 `count` 短 ⇒ 只写 `heights.size` 个柱的顶点（返回 `heights.size + 3`）。
 *
 * @param x0 频谱带左边缘（px）
 * @param count 柱数
 * @param slotPx 每根柱分到的水平空间（含缝；折线档由实心档调用，故缝恒 0）
 * @param heights 每根柱的高度（px，`≥ 0`；调用方已把地板与拉伸算好）
 * @param baseY 基线 y（柱底；非对称档 = `bounds` 底边，对称档 = `axis`）
 * @param out 复用容器（**会被覆写**）。长度需 `≥ (柱数 + 3) × 2`
 *   （**左下角 + 柱数个柱顶 + 末根 hold-last 顶点 + 右下角**；缺任一个都会画错，
 *   见上表）。热路径不新建对象。
 *   ⚠️ **容器是 `FloatArray` 不是 `List`** —— 逐帧热路径上装箱/分配到柱数级会被
 *   `SpectrumFadePerfProbe` 那类探针测出来，而本项目的纪律是"新增原语必须零分配"
 *   （见 [spectrumBarPieces] 的 `pieceScratch` 同源理由）。
 *   ★ 对称档（[mirrorAxisY] 有限）需要 **两倍**：`≥ (2 × 柱数 + 6) × 2`。
 * @param mirrorAxisY ★★ 2026-09-28 新增：**对称镜像轴**的 y（px）。`NaN` = 不镜像
 *   （非对称档，行为与改动前**逐位相同**）。
 *
 *   ## 为什么必须在这里做镜像（用户报障）
 *
 *   用户原话（逐字）：「**折线频谱(间距0)下上下对称双峰没有下半部分**」。
 *   根因：`solid` 折线分支**整段 `return`**（见 `SpectralPainter.paintBars`），
 *   而本函数只沿 `baseY - h` 生成**单侧**轮廓 ⇒ 对称档下**下半部分从未被画出来**。
 *   ⚠️ 该缺陷长期未被发现，因为折线探针**只测过 `symmetric = false`**（空护栏）。
 *
 *   ## 做法：**一个闭合轮廓**装下上下两半（而不是画两次）
 *
 *   上半轮廓写成 `p0 … p_{n+2}`（末尾是右下角）。若 [mirrorAxisY] 有限，则**继续**写
 *   下半：把上半的点**倒序**取一遍、y 换成 `2·axis − y`（关于轴的镜像），
 *   于是整个点列首尾都在轴上（`2·axis − axis = axis`），`closePath()` 自然闭合 ——
 *   填充区域 = 上下两个峰 + 它们之间的轴带，**一次 `fill`**（仍是折线档"避免双重
 *   合成"的初衷，见 `paintBars` 折线分支的论证）。
 *
 *   ⚠️ 镜像后的 y **不夹取**到 `bounds`：调用方的 `halfSpan` 已保证 `h ≤ halfSpan`，
 *   故 `2·axis − (baseY − h)` 最远只到 `axis + halfSpan`，不会越界（与柱状档同一约束）。
 *
 * @return 写入的**点数**；非法输入 / 容器不足返回 `0`。
 *   非对称（`mirrorAxisY = NaN`）= `count + 3`（含两个基线角点与末根 hold-last）；
 *   对称 = `2 × count + 6` 减去两端在轴上的**重合点**（见实现：`+4`）。
 */
fun spectrumPolylinePoints(
    x0: Float,
    count: Int,
    slotPx: Float,
    heights: FloatArray,
    baseY: Float,
    out: FloatArray,
    mirrorAxisY: Float = Float.NaN,
): Int {    if (count <= 0) return 0
    if (!x0.isFinite() || !slotPx.isFinite() || !baseY.isFinite()) return 0
    if (slotPx <= 0f) return 0
    val n = minOf(count, heights.size)
    if (n <= 0) return 0
    val mirror = mirrorAxisY.isFinite()
    // 需要 (n + 1) 个顶部顶点 + 2 个基线角点（左下 / 右下）⇒ 2 × (n + 3) 个 float。
    // ⚠️ 两个角点**都不是可省的"闭合点"**：`closePath()` 只负责把**右下角**
    //    沿基线连回**左下角**。缺了左下角，闭合线会**斜穿整幅图**（见 KDoc 的实测缺陷）。
    // ★ 对称档（`mirror`）要再装一份**倒序镜像**的上半 ⇒ 两倍再加两端的角点。
    val need = if (mirror) (2 * n + 6) * 2 else (n + 3) * 2
    if (out.size < need) return 0
    // 顶点列与 [spectrumBarRectX] **同一个 x 公式**（实心档：整像素吸附）。
    // ⚠️ 这里**不重写** `round(x0 + i·slot)` —— 而是调那个函数取左边缘，
    //    这样"折线顶点"与"柱矩形左边缘"是**同一个表达式**，
    //    "两者 x 一致"由构造保证，不由纪律保证。
    var w = 0
    // ── p0 = 左下角 ──────────────────────────────────────────────────────────
    // x 取**第 0 根柱的左边缘**（实心档 = `round(x0)`）：既与柱矩形对齐，
    // 又保证左边缘是**竖直边**（`x0` 非整数时 `round(x0) != x0`）。
    // 生产路径上 `x0` 恒为整数（`bounds.x` 是 Int），此处两者逐位相等。
    out[w++] = spectrumBarRectX(x0, 0, slotPx, 0f, slotPx, solid = true).first
    out[w++] = baseY
    // ── p1 … pn = 各柱顶（落在**相邻柱的公共边界**上，见 KDoc）────────────────
    var lastRight = out[0]
    var lastTop = baseY
    for (i in 0 until n) {
        val (left, width) = spectrumBarRectX(x0, i, slotPx, 0f, slotPx, solid = true)
        val h = if (heights[i].isFinite()) heights[i].coerceAtLeast(0f) else 0f
        out[w++] = left
        out[w++] = baseY - h
        lastRight = left + width
        lastTop = baseY - h
    }
    // ── p_{n+1} = **末根顶保持水平**到带的右端 ────────────────────────────────
    // ⚠️★ 这一条是 2026-09-27 实测补上的第二个缺陷：顶部顶点落在**柱的左边缘**，
    //    故第 i 段的顶边是"第 i 根柱的顶 → 第 i+1 根柱的顶"的斜线；
    //    而**最后一根没有后继柱** —— 若直接跳到右下角，末根柱的顶边会
    //    一路斜到**基线** ⇒ **整根退化成三角形**（24 柱 640px 下是右侧 27px 的楔形）。
    //    故这里补一个"保持上一根高度"的顶点（hold-last），使末根与其余各根一样
    //    是**梯形**（顶边水平）。
    out[w++] = lastRight
    out[w++] = lastTop
    // ── p_{n+2} = 右下角 ────────────────────────────────────────────────────
    // ⚠️ 用 `spectrumBarRectX` 的**右边缘**（= 下一根的左边缘公式）——
    //    与 [spectrumBarRectX] 的 `right` 逐位相等。
    out[w++] = lastRight
    out[w++] = baseY
    if (!mirror) return n + 3
    // ── ★★ 2026-09-28：对称镜像 —— 把上面的上半轮廓**倒序**再走一遍，
    //    y 关于 `mirrorAxisY` 镜像（`y' = 2·axis − y`）。
    //    ★ 为什么"倒序"：上半走到右下角（x 最大）⇒ 下半必须从**右**往**左**回来，
    //      路径才不自交（Java2D 对自交多边形用**奇偶填充**，重叠区会被挖空 ——
    //      **不报错**，只是结果怪，与 [barSegmentPath] 的弧长夹取同一类坑）。
    //    ⚠️ 不重复写两端在轴上的重合点：起点已由上面的 `p0`（y = baseY = axis）占位，
    //      终点由 `closePath()` 沿轴连回 `p0`。故只追加**中间的 n+2 个点**的镜像，
    //      跳过索引 0（左下角）。写入量 = (w/2 − 1) 个点。
    val axis2 = 2f * mirrorAxisY
    val written = w / 2
    var k = written - 1
    while (k >= 1) {
        out[w++] = out[k * 2]
        out[w++] = axis2 - out[k * 2 + 1]
        k--
    }
    return written + (written - 1)
}

/**
 * ★★★ 2026-10-11（**BUG-166 · 补**）：**极坐标的「实心」轮廓**（连续环 / 连续放射）——
 * 唯一出口，是 [spectrumPolylinePoints] 的**极坐标版**。
 *
 * ## 为什么需要它（★ 一个"死设置"的收口）
 *
 * 用户 2026-09-22 原话：「频谱条手动间隙改为 0-200px…**当间距为 0px 时呈现实心频谱**。」
 * 而绘制侧 `solid` 档走的是**折线分支**（`spectrumPolylinePoints`），它**不是极坐标感知的**
 * —— 它按 `x0 / slotPx / baseY` 在**直角坐标**里铺点
 * ⇒ ★ 极坐标 + 实心档画出来是"**顶部一块半圆**"（旋转一次都没做）✗
 * （用户 2026-10-11 报障「放射状和环状频谱的频谱条太丑了」的一个成因；
 *  ★ 另一成因是柱宽取了外缘周长 —— 见 `IslandWindow` 里 `spectrumBounds` 的注释。）
 *
 * ## 轮廓的构造（**闭合环带**，一次 `fill`）
 *
 * ```
 * 外缘：i = 0 … n−1，半径 = innerRadius + hᵢ，角度 = polarAngleDeg(spec, i, n)
 * 内缘：i = n−1 … 0，半径 = innerRadius      （★ 倒序 ⇒ 路径不自交）
 * ```
 *
 * ★ **为什么内缘要倒序**：`closePath()` 会沿内缘最后一点连回外缘第一点；
 * 若内缘正序，整条路径会**自交**（Java2D 对自交多边形用**奇偶填充** ⇒ 重叠区被挖空，
 * **不报错**只是结果怪 —— 与 `spectrumPolylinePoints` 的镜像段同一条纪律）。
 *
 * ★ **为什么"一次 `fill`"**：与折线档同一个理由 —— 缝为 0 时逐柱 `fill` 会让相邻柱的
 * 公共边界被**两次 `SRC_OVER` 合成** ⇒ `alpha < 1` 时出现淡缝 ✗
 *
 * ## 坐标约定（与 [polarAngleDeg] 一致）
 *
 * `0° = 12 点`、**顺时针为正** ⇒ 屏幕坐标 `x = cx + r·sin θ`、`y = cy − r·cos θ` ✓
 *
 * @param spec 极坐标规格（`innerRadius` 已夹在 `[0, 0.85·maxLength]`）
 * @param count 条数（= 外缘 / 内缘各多少个顶点）
 * @param heights 每根柱的**像素高度**（径向长度，已乘过 `halfSpan`）
 * @param out 输出缓冲（长度 ≥ `4 × count`，**会被原地修改**）
 * @return 写入的**点数**（= `2 × count`）；非法输入 / 容器不足返回 `0`
 */
fun spectrumPolarPolylinePoints(
    spec: SpectrumPolarSpec,
    count: Int,
    heights: FloatArray,
    out: FloatArray,
): Int {
    if (count <= 0) return 0
    if (!spec.cx.isFinite() || !spec.cy.isFinite() || !spec.maxLength.isFinite()) return 0
    val n = minOf(count, heights.size)
    if (n <= 0) return 0
    if (out.size < 4 * n) return 0
    val inner = spec.innerRadius.coerceAtLeast(0f)
    var w = 0
    // ── 外缘（正序）─────────────────────────────────────────────────────────
    for (i in 0 until n) {
        val h = if (heights[i].isFinite()) heights[i].coerceAtLeast(0f) else 0f
        // ★ 外半径**夹到 `maxLength`**：柱高若因浮点误差越界，轮廓会戳出画布
        //   （与 `paintBars` 的 `polarOuterR` 同一条上界）。
        val r = (inner + h).coerceIn(0f, spec.maxLength)
        val rad = Math.toRadians(polarAngleDeg(spec, i, n).toDouble())
        out[w++] = spec.cx + r * Math.sin(rad).toFloat()
        out[w++] = spec.cy - r * Math.cos(rad).toFloat()
    }
    // ── 内缘（★ 倒序）───────────────────────────────────────────────────────
    for (i in n - 1 downTo 0) {
        val rad = Math.toRadians(polarAngleDeg(spec, i, n).toDouble())
        out[w++] = spec.cx + inner * Math.sin(rad).toFloat()
        out[w++] = spec.cy - inner * Math.cos(rad).toFloat()
    }
    return w / 2
}

/**
 * 折线形态下**折点**的圆角半径（px）—— 公开纯函数。
 *
 * ## ★★ 为什么不改 [spectrumBarCornerPx] 的 `if (solid) return 0f`
 *
 * 那条 `solid → 0f` **对"柱状实心档"仍然正确**：柱状实心下相邻柱紧贴，
 * 圆角会让接缝两侧各退 `arc/2` ⇒ 条间出腰形凹口（[spectrumBarCornerPx] 的 KDoc 详述）。
 * **删掉它会同时改坏柱状档**。
 *
 * ⇒ 折线档走**另一条分支**（本函数）：折线是一条**连续轮廓**，圆角作用在
 * **折点（顶点）**上，而共边保证段与段之间无缝 —— 于是"既圆角又无凹口"成立。
 *
 * ## 用户原话（逐字，2026-09-26）
 *
 * > 「频谱条的圆角我想要的是**胶囊那种大 R 角**，不是圆角矩形。
 * >   注意**根部不要应用圆角**。」
 *
 * ⇒ 折线形态下"根"= **基线**（底边）⇒ 底边**恒直角**，只有折点圆。
 *
 * ## 与 [SpectrumBarCorner] 的优先级
 *
 * | 档 | 结果 |
 * |---|---|
 * | [SpectrumBarCorner.ROUND] | `min(段宽, 段高) / 2 × cornerFraction`（胶囊） |
 * | [SpectrumBarCorner.SQUARE] | `0f`（全直角折点）—— 即 2026-09-22 之前"实心"的样子 |
 *
 * @param segmentWidthPx 相邻顶点的水平跨度（px）
 * @param maxCornerPx 圆角上限（调用方传 `MIN_BAR_HEIGHT`，与柱状档同源）
 * @param corner 端头形状
 * @return `SQUARE` 档 / 非法输入恒 `0f`；`ROUND` 档 = `min(段宽, 上限) / 2 × 比例`
 */
fun spectrumPolylineCornerPx(
    segmentWidthPx: Float,
    maxCornerPx: Float,
    corner: SpectrumBarCorner = SpectrumBarCorner.DEFAULT,
): Float {
    if (!segmentWidthPx.isFinite() || !maxCornerPx.isFinite()) return 0f
    val full = segmentWidthPx.coerceAtMost(maxCornerPx) / 2f
    if (full <= 0f) return 0f
    return full * corner.cornerFraction
}


/**
 * 本帧是否**视觉上静音**：即"每根柱子画出来都只会是地板"。
 *
 * ## ★★ 2026-09-21：这是真机「无声时双峰中线一条粗线」的**正因**修复
 *
 * ### 症状与旧判据的失配
 *
 * 用户原话：「无声时（包括暂停瞬间频谱坍缩），**上下对称双峰中线仍有一条粗线**」，
 * 且他明说这是「**还是老问题**」—— 即旧的 `peak <= SILENCE_PEAK_EPS` 没修掉它。
 *
 * ### 为什么没修掉
 *
 * `SILENCE_PEAK_EPS = 1e-6f`，而它的 KDoc 写着「取值 `0.004f` ≈ -48 dBFS」——
 * **注释与代码差了 4000 倍**。后果：静音门**几乎永不触发**，
 * 每根柱子仍按 `minBarHeight` 地板画出来；**对称跨轴**时地板是 `2 × 2 = 4px` 高
 * ⇒ 中线一条 4px 粗线。
 *
 * ### 正确判据：问「**画出来会不会只是地板**」，而不是问「数值是不是零」
 *
 * 归一化柱高 `peak` 对应的像素高度是 `peak × halfSpan`；
 * 若 `<= minBarHeight`，这一帧画出来的**每一根**柱子都只会是地板 ⇒ 视觉上是"线"不是"频谱"。
 *
 * ⚠️ 它还**随几何自动缩放**：带子高时门限小、矮时门限大 ——
 * 不需要为不同布局各调一个常量（这正是旧写法"一个常量管所有布局"的病根）。
 *
 * @param peak 本帧重采样后的归一化峰值（`0f..1f`）
 * @param halfSpan 单侧最大像素高度（对称模式是"轴到较近那条边"）
 * @param minBarHeight 柱高地板（`MIN_BAR_HEIGHT`）
 */
fun isSpectrumVisuallySilent(peak: Float, halfSpan: Float, minBarHeight: Float): Boolean {
    if (!peak.isFinite() || !halfSpan.isFinite() || !minBarHeight.isFinite()) return true
    return peak * halfSpan <= minBarHeight
}

/**
 * 柱末端**淡出带**占柱高的比例（`spectrum.fade_top` 的量化）。
 *
 * ## ★★ 2026-09-22：为什么这个常量必须是"比例的量化"，而不是"每柱算一次"
 *
 * 用户原话：「**频谱顶端淡出开启后频谱跳动卡顿明显。**」
 *
 * ### 根因（卡顿的量化来源）
 *
 * 改动前，淡出笔刷的**纵向范围按这一柱自己的高度**算 —— 于是
 * 「一支笔刷」这个说法在几何上不成立：每根柱子的渐变**坐标都不同**，
 * 笔刷**不可缓存**（缓存了就会拿到位置错误的笔刷）。
 * 结果：**每帧为每根柱子新建一个 `LinearGradientPaint`**。
 *
 * | 规模 | 每帧新建渐变 | 每秒（60 fps） |
 * |---|---|---|
 * | 640 px 宽、默认间距 7 px（约 91 柱） | 91 | 5 460 |
 * | 300 px 宽、自动间距 2 px（150 柱） | 150 | 9 000 |
 * | 上限 [SPECTRUM_MAX_BARS] | 1024 | 61 440 |
 *
 * 而 `LinearGradientPaint` 的构造**不只是分配一个对象** ——
 * Java2D 会为它预计算一张**颜色查找表**（`MultipleGradientPaintContext` 的
 * 渐变 ramp），这笔开销在**每个不同的笔刷实例**上都要付一次。
 *
 * ### 修法：把"淡出带宽度"从"柱高"里解耦出来
 *
 * 淡出带的**绝对宽度** `f = min(柱高 × [SPECTRUM_FADE_BAND_RATIO], [SPECTRUM_FADE_BAND_MAX_PX])`
 * 的取值只有 `[1, 6]` 这一段 —— 把它量化到 `0.25 px` 就只有 **21 个可能值**。
 * 于是笔刷可以**只按 `f` 缓存**（见 `SpectrumTipFadeBrushCache`），
 * 每帧新建数从"柱数"降到"不同 `f` 的个数"。
 *
 * ## 为什么比例是 `0.30`、上限是 `6 px`
 *
 * 与 `window/IslandPolicy.kt` 的 `spectrumTipFadeFractions` **同源**
 * （那里是 2026-09-21 真机崩溃后定下的取值，理由是"仅末端"的量化：
 * 高柱不会因为 30% 而整条发虚）。本函数是同一规则在**绘制侧**的落点。
 *
 * ⚠️ 两处**必须同步**：本文件是 `render` 包，`IslandPolicy` 是 `window` 包，
 * 依赖方向是 `window → render`（单向），故 `render` **不能** import 那一份 ——
 * 这属于"规则有两个落点"。
 *
 * ## ★★ 2026-10-03（㉒ 收口）：这条"有断言钉住"的说法**曾经是假的**
 *
 * **原记**（本段 2026-09-29 写下的）：
 * > 「由 `SpectrumOpacityProbe` 的 ⑥ 组断言钉住
 * >   （它穷举柱高，比较两侧给出的**淡出带绝对宽度**是否逐位相同）」
 *
 * **实为：那条断言不存在。** 判据（可机械复核）—— 全树没有任何探针**同时**引用这两个函数：
 * 遍历 `plugin/src/probe/kotlin/` 下全部 `*Probe.kt`，取「既含 `spectrumFadeBandPx`
 * 又含 `spectrumTipFadeFractions`」者 ⇒ **空集**。
 * （`SpectrumOpacityProbe` 的 ⑥ 组实测是"把 alpha 搞错的变异测试"，与淡出带无关。）
 *
 * **这条缺口真的漏过东西**：2026-09-29 给本函数加"形状项"时，
 * 第二处 `window/IslandPolicy.spectrumTipFadeFractions` 的函数体**也**加了同名参数，
 * 但它的**生产调用点**（`IslandWindow.spectrumTipFadePaint`）**没传第三个实参**，
 * 而参数当时**有默认值 `0f`** ⇒ 形状项在**那条路径上恒不生效**、**没有任何检查会红**。
 * 用户报的「**圆角档顶端淡出与方角档差别不大**」因此拖了 4 天 ——
 * 这是**纪律 15**（同一份数据两个收敛点，只接了一处）的教科书形态。
 *
 * **现在的护栏（两条，缺一不可）**：
 *  1. `SpectrumFadeBandProbe` **§E** —— 交叉一致性：对 `(h, arc)` 网格换算后**逐值相同**，
 *     并**双向**验证"形状项在两处都生效"（E2）、"arc=0 零回归"（E3）、"真的改变了值"（E4）。
 *  2. `spectrumTipFadeFractions` 的 `cornerRadiusPx` **已删默认值** ⇒
 *     "调用点漏传"由**运行时静默**变成**编译期失败**。
 *
 * ## 边界
 *
 * - `spanPx` 非有限 / ≤ 0 → 返回下限 `1f`（不返回 0：0 宽的渐变没有意义，
 *   而 `LinearGradientPaint` 的色标区间为空会退化为"整柱不可见"）
 * - 返回值恒落在 `[1, 6]`（探针穷举断言）
 *
 * @param spanPx 这一段柱体的**纵向跨度**（px）。非对称档 = 柱高；
 *   对称双峰档 = **半高**（每一片各自的跨度）—— 见 [spectrumBarPieces]。
 */
fun spectrumFadeBandPx(
    spanPx: Float,
    /**
     * 本帧柱体的**圆头半径**（px）。★ 方角档 / 实心档传 `0f`（默认）。
     *
     * ★★ 2026-09-29（用户指令「把22做了吧」，`recon/69`）：
     * 用户报「**顶端淡出在圆角新状态下效果不好，与方角差别不大**」。
     * 根因：本函数原公式 `min(片高 × 0.30, 6px)` **与 `bar_shape` 无关** ⇒
     * 两档拿到**同一条带**；而**圆角档的圆头半径 ≈ 2.45px 小于带** ⇒ **整个圆头发虚**。
     *
     * ⇒ 修法 = **带公式引入形状项**（用户拍板 P-i ①）：带**至少覆盖圆头半径**。
     *
     * ⚠️ **上限只夹"比例项"，不夹形状项** —— 上限 `6px` 的目的（见
     * [SPECTRUM_FADE_BAND_MAX_PX]）是"别让带跟着**柱高**无限长"，而**圆头半径
     * 与柱高无关**（它由**柱宽**推出）⇒ 用 6px 夹形状项会**重新引入用户报的症状**。
     */
    cornerRadiusPx: Float = 0f,
): Float {
    val byShape = cornerRadiusPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (!spanPx.isFinite()) return maxOf(SPECTRUM_FADE_BAND_MAX_PX, byShape)
    val h = spanPx.coerceAtLeast(1f)
    val base = (h * SPECTRUM_FADE_BAND_RATIO).coerceAtMost(SPECTRUM_FADE_BAND_MAX_PX)
        .coerceAtLeast(1f)
    // ★ 默认（byShape = 0f）⇒ maxOf(base, 0f) == base ⇒ **与改动前逐值相同**。
    return maxOf(base, byShape)
}

/**
 * 淡出带占柱高的比例。见 [spectrumFadeBandPx] 的 KDoc。
 *
 * 取 `0.30`：与 `IslandPolicy.spectrumTipFadeFractions` 的 `fade = h * 0.30f` 同值。
 */
const val SPECTRUM_FADE_BAND_RATIO = 0.30f

/**
 * 淡出带的**绝对宽度上限**（px）。
 *
 * 取 `6`：这是「**仅末端**」的量化 —— 没有它，高柱的淡出带会跟着高度线性增长，
 * 看起来就是用户明确不要的「整条发虚」。同 `IslandPolicy.spectrumTipFadeFractions`。
 */
const val SPECTRUM_FADE_BAND_MAX_PX = 6f

/**
 * 淡出带占**带高**的比例上限。
 *
 * ## ★★ 2026-09-23：这条上限为什么必须存在（用户第 26 轮原话）
 *
 * > 「关于顶端淡出，**就不能只作用于顶端吗？非得从根部起算?**」
 *
 * 改动前 [spectrumFadeStartFraction] 的起点是一个**常量 `0.65f`** ——
 * 于是淡出带 = **带高的 35%**，与带高无关。带子越矮，那 35% 在视觉上
 * 越像"从根部就开始淡"（用户看到的正是这个）。
 *
 * 现在淡出带恒取 [spectrumFadeBandPx]（`min(带高 × 0.30, 6px)`），
 * 只剩一个"极矮的带子"需要兜底：`带高 < 2px` 时那条公式会给出
 * `band / 带高 > 0.5`，而 `fadeStart = 1 − band/带高` 就会掉到 0.5 以下 ——
 * 那时"淡出带"变成"整个带子"，又回到用户不要的样子。
 * 故取 `0.5` 兜底：**淡出带最多占带高的一半**，
 * 于是 `fadeStart ∈ [0.5, 1)` 恒成立（`LinearGradientPaint` 的
 * 色标 `[0f, fadeStart, 1f]` 结构上永远严格递增，不会抛
 * `Keyframe fractions must be increasing`）。
 *
 * 取 `0.5` 而不是更小（如 `0.1`）的理由：它是**兜底**而不是**目标** ——
 * 带高 ≥ 20px 时 `band / 带高 ≤ 0.3 < 0.5`，这条上限**根本不起作用**；
 * 只在带高 < 2px 这种退化档上才生效，那时"半个带子"已经是能给出的最好结果。
 */
const val SPECTRUM_MAX_FADE_FRACTION = 0.5f

/**
 * **整带**纵向渐变笔刷的**淡出起点**（`0f..1f`，自底向上）—— 纯函数，可被探针断言。
 *
 * ## 它回答的是哪句话
 *
 * 用户原话（第 26 轮，逐字）：
 * > 「关于顶端淡出，**就不能只作用于顶端吗？非得从根部起算?**」
 *
 * ### 改动前：淡出起点是**常量 `0.65f`**
 *
 * `IslandWindow.verticalFadePaint` 把色标写成 `[0f, 0.65f, 1f]` ——
 * 淡出带 = **带高的 35%**。一条 48px 高的带子里有 **16.8px** 在渐变，
 * 而频谱柱大多只有二三十像素高 ⇒ 用户看到的"淡"几乎覆盖整根柱子，
 * 于是他的描述是「**从根部起算**」。
 *
 * ### 现在：淡出带 = `min(带高 × 0.30, 6px)`
 *
 * ```
 * fadeStart = 1 − min(spectrumFadeBandPx(带高) / 带高, SPECTRUM_MAX_FADE_FRACTION)
 * ```
 *
 * `spectrumFadeBandPx` 是**既有**的公开纯函数（`[1, 6]` px，上限 6 是"仅末端"的量化），
 * 故本函数**不引入第二个"淡出带有多宽"的定义点** —— 它只是把那条规则
 * 从"每根柱子"换到"整条带"的坐标系上。
 *
 * ## 为什么是"整带一支渐变"而不是"逐柱一支"
 *
 * 用户原话（第 26 轮，逐字）：
 * > 「**原来在每根柱顶设置淡化导致效能开销大，卡顿感明显故改掉了，
 * >   你现在又让他回归，就不能探究其他路线吗？**」
 *
 * 故本轮的路线是：**每个带仍然只构造 1 个 `LinearGradientPaint`**（整带锚定），
 * 靠"把淡出带压到顶端 6px"来满足"只作用于顶端"，而不是靠逐柱笔刷。
 *
 * ## 代价（**如实写明，不藏**）
 *
 * 淡出带锚在**带**的顶端，不是每根柱自己的顶端 ⇒
 * **顶端低于"带顶 − 6px"的矮柱整根全实心**（它根本没进入淡出区）。
 * 这正是"不逐柱计算"换来的东西，也是用户选择的取舍。
 *
 * ## 边界
 *
 * - `bandHeightPx` 非有限 / ≤ 0 → 返回 `1f`（= 整条实心，没有淡出带）
 * - 返回值恒落在 `[SPECTRUM_MAX_FADE_FRACTION, 1f]` —— 探针穷举断言
 *   （`fadeStart < 1` 保证 `[0f, fadeStart, 1f]` 严格递增）
 *
 * @param bandHeightPx 带高（px）。非对称档 = 带矩形高；对称档 = **单侧最大高度**
 *   （轴到较近那条边的距离）—— 与 [spectrumBarPieces] 的 `halfSpan` 同源。
 */
fun spectrumFadeStartFraction(bandHeightPx: Float): Float {
    if (!bandHeightPx.isFinite() || bandHeightPx <= 0f) return 1f
    val band = spectrumFadeBandPx(bandHeightPx)
    val fraction = (band / bandHeightPx).coerceAtMost(SPECTRUM_MAX_FADE_FRACTION)
    return (1f - fraction).coerceIn(SPECTRUM_MAX_FADE_FRACTION, 1f)
}

/**
 * 一根柱子要画成**几片**、每片的纵向范围与淡出端 —— **纯函数，可被探针断言**。
 *
 * ## 为什么需要"分片"（而不是一根柱子一支笔刷）
 *
 * 见 [spectrumFadeBandPx] 的 KDoc：笔刷只按**淡出带宽度**缓存，
 * 而淡出带宽度只取决于"这一片自己的跨度"。于是对称双峰必须拆成
 * **上下两片**（各以自己那一端为尖端），否则整条柱子就要一支"按全跨度的
 * 双端渐变"笔刷 —— 而那支笔刷的坐标随柱高变化，**又回到每柱一支**。
 *
 * ## ★ 分片的**不变量**（探针 ⑦ 组穷举的那两条）
 *
 * 1. **不重叠**：两片在纵向上不相交 —— 否则交叠区会被**合成两次**
 *    （`SRC_OVER` 的 `a ⊕ a = 2a − a²`），在 `alpha < 1` 时表现为
 *    双峰中线一条**更亮的横带**（这正是"接缝"缺陷的另一副面孔）。
 * 2. **无缝**：两片的并集恰好覆盖 `[topTip, bottomTip]` ——
 *    分界处若有空隙，双峰中线会出现一条**暗缝**。
 *
 * 取"在**中点**一刀切、两片各带**直角**内端"同时满足两条：
 * 中点处两者恰好相接（无缝），且各占半边（不重叠）。
 * 内端取直角是**唯一**能满足"不重叠 + 无缝"的取法 —— 圆角内端会在
 * 靠近左右边缘处**向内退** `arc/2`，于是要么留缝（缺陷）要么重叠（双重合成）。
 *
 * @param topTip 上尖端 y（对称档 = `axis − barHeight`；非对称档 = 柱顶）
 * @param bottomTip 下尖端 y（对称档 = `axis + barHeight`；非对称档 = 柱根）
 * @param symmetric `true` = 上下对称双峰（拆两片，两端各自淡出）；
 *   `false` = 单片（只在 [topTip] 端淡出，[bottomTip] 端是柱根、不淡）
 * @param out 输出缓冲（长度 ≥ 2，**会被原地修改**；只写前 `n` 项）——
 *   分配纪律见 [resampleBands]：绘制热路径不新建数组
 * @return 实际写入的片数（`1` 或 `2`）。每片占 `out[i*2]` / `out[i*2+1]`：
 *   `[0]` = 上边缘、`[1]` = 下边缘；淡出端由位置决定
 *   （上片淡顶端、下片淡底端），调用方据此选笔刷方向。
 */
fun spectrumBarPieces(
    topTip: Float,
    bottomTip: Float,
    symmetric: Boolean,
    out: FloatArray,
): Int {
    if (out.size < 4) return 0
    if (!topTip.isFinite() || !bottomTip.isFinite()) return 0
    if (bottomTip <= topTip) {
        // 退化几何（0 高）：给出一片"至少 1px"的兜底，避免调用方拿到空片集后画出空图形。
        out[0] = topTip
        out[1] = topTip + 1f
        return 1
    }
    if (!symmetric) {
        out[0] = topTip
        out[1] = bottomTip
        return 1
    }
    val axis = (topTip + bottomTip) / 2f
    out[0] = topTip
    out[1] = axis
    out[2] = axis
    out[3] = bottomTip
    return 2
}

/**
 * ★★★ **极坐标布局**（环形 / 放射）—— 纯数据，可被探针直接断言。
 *
 * ## 台账出处
 *
 * `未完成工作总账` §3.1 **⑧**「**放射状 / 环形频谱**」的第三种（五根细条 + 词岛右端）
 * 已于 2026-10-02 落地 ⇒ **本文件补齐剩下两种**。
 * 台账给的技术路径原话：
 *
 * > **放射状**：把 `paintBars` 的直角坐标换极坐标（`AffineTransform` 旋转 + 条形沿半径生长）
 * > **环形**：同机制 + 沿圆环排布
 *
 * ## ★★★ 业界的"经典呈现"（用户要求「参照网上的经典呈现」⇒ 已查证）
 *
 * | 来源 | 关键参数 | 结论 |
 * |---|---|---|
 * | **Rainmeter** `Roundline`（桌面频谱的经典做法） | `LineStart = Radius − BarLength`（**内半径**）· `LineLength = Radius`（**外半径**） | 条形**从内半径向外**生长 |
 * | **react-audio-visualizers** | `roundBars` **无** `radius` 参数；`radialLine` / `radialSquaredBars` **有** `radius` + **`startingAngle`** + **`mirror`** | ★ **"环形"与"放射"是两个不同的 theme** |
 * | **productivetoolbox** | `Circular` = "bars arranged **radially around a circle**"（360°）· `Radial` = "pulsing circle with frequency dots" | 同上 |
 *
 * ⇒ ★★ **两者的几何差别只有一个：内半径**：
 *
 * | 形态 | 内半径 | 观感 |
 * |---|---|---|
 * | **环形（CIRCULAR）** | ★ **大**（≈ 外半径的一半）⇒ **中心空** | 一个**甜甜圈**，像 80 年代音响的环形指示灯 |
 * | **放射（RADIAL）** | ★ **小 / 0** ⇒ **从圆心射出** | 一个**太阳**，条从中心向外辐射 |
 *
 * ★ 故本实现**共用同一套极坐标几何**，只差 [innerRadiusRatio] —— 这也是台账说
 * "同机制"的含义。
 *
 * ## 角度约定（★ 与业界一致，且必须是**纯函数**）
 *
 * · **0° = 12 点方向**（正上方）· **顺时针为正**（钟表方向）—— 这是频谱可视化的通用约定；
 * · 每根柱**占一段扇区**，柱子画在该扇区的**中线**上（★ 否则首尾两柱会贴在一起）；
 * · `sweepDeg = 360` ⇒ 整圈（环形 / 全放射）；`180` ⇒ 半圈（半环，常见于"环绕词岛上半"）。
 *
 * ⚠️ **为什么放在本文件**（而不是 `SpectralPainter`）：与 [RIGHT_BAR_COUNT] 同一条理由 ——
 * 它是**几何量**，预览与真机都要用、探针也要能直接断言。`SpectralPainter` 的对象是
 * **绘制**，几何应当先在纯函数里定死。
 *
 * @param cx 圆心 x（面板局部坐标，px）
 * @param cy 圆心 y
 * @param maxLength 条形的**最大径向长度**（px）—— 对应直角坐标里的 `bounds.height`
 * @param innerRadiusRatio 内半径 = `maxLength × 本值`。★ **0 = 纯放射**（从圆心射出）；
 *   取 `0.5` 左右 = 环形（中心空出一半）。★ 用**比值**而不是绝对值，是为了让
 *   "内半径"随尺寸自适应（窗口可缩放，绝对像素会在小尺寸下把中心堵死）。
 * @param startAngleDeg 起始角（度）。`0` = 12 点方向
 * @param sweepDeg 扫过的角度（度）。`360` = 整圈
 */
data class SpectrumPolarSpec(
    val cx: Float,
    val cy: Float,
    val maxLength: Float,
    val innerRadiusRatio: Float,
    val startAngleDeg: Float = 0f,
    val sweepDeg: Float = 360f,
) {
    /** 内半径（px）。★ 恒 ≥ 0，且**不得吃掉全部长度**（否则条形零长 ⇒ 什么都看不见）。 */
    val innerRadius: Float
        get() = (maxLength * innerRadiusRatio.coerceIn(0f, MAX_INNER_RADIUS_RATIO))
            .coerceIn(0f, maxLength * MAX_INNER_RADIUS_RATIO)

    companion object {
        /**
         * 内半径占最大长度的**上限**。
         *
         * ★ 取 **0.85** 而不是 1.0：内半径 == `maxLength` ⇒ 可用径向长度 = **0** ⇒
         * 条形画不出来，而**不会报错**（`fillFadedPiece` 有 `h <= 0f` 的静默守卫）——
         * 那正是本项目最忌讳的"设置拖到极端 ⇒ 东西无声消失"。
         */
        const val MAX_INNER_RADIUS_RATIO = 0.85f

        /** 环形档的内半径比（中心空出约一半 —— 对应 Rainmeter 的 `Radius − BarLength`）。 */
        const val CIRCULAR_INNER_RATIO = 0.55f

        /**
         * 放射档的内半径比。
         *
         * ## ★★★ 2026-10-11（**BUG-166**）：**0 → 0.35**（用户拍板「留中心空档」）
         *
         * 用户报障（逐字）：「**三型侧边频谱的外观不大符合常见认知，你去搜索看看业界是怎么呈现的。**」
         *
         * ### 检索结论（四处取证，口径一致）
         *
         * | 来源 | 原文 / 要点 |
         * |---|---|
         * | **Music Motion** · How to Design a Circular Audio Visualizer | ★ 「**Reserve a stable inner safe area** for the focal image or logo」；★ 反面清单第一条 = 「**avoid a dense radial field that swallows the centre**」 |
         * | **WaveCanvas** · Radial Bars | ★ 把「**Center space for artwork/logos**」列为 **key feature**；参数含 `radius` / `spacing` / `mirror` |
         * | **productivetoolbox** | `Circular`（bars around a circle）与 `Radial` **两者都留中心** |
         * | **Rainmeter** `Roundline` | `LineStart = Radius − BarLength`（= 内半径）⇒ **从内半径向外长** |
         *
         * ⇒ ★★ **业界的「圆形频谱」几乎都留中心**；**「条从圆心点射出」（内半径 0）很罕见** ——
         * 因为条数一多，所有条在圆心**汇聚重叠** ⇒ 中心糊成一团（正是上面那条反面清单说的）。
         *
         * ### 取 0.35 的判据（不是手感值）
         *
         * · **下限**：内半径必须**明显大于 0**，否则"留空"在 64 px 的小画布上看不出来
         *   （`0.35 × 32 ≈ 11 px` ⇒ 直径 22 px 的空档，肉眼可辨）；
         * · **上限**：可用径向长度 = `(1 − 0.35) × R = 0.65R` ⇒ 条**仍然够长**
         *   （`RING` 的 0.55 只剩 `0.45R`，两档因此**形态可区分**：放射的空档小、环状的空档大）；
         * · **与 `RING` 拉开差距**：`0.35` vs `0.55` ⇒ 一个是"花芯"、一个是"甜甜圈" ✓
         *
         * ⚠️ **观感会变**（原记「只加一档（固定一种）」时取 0 是"从圆心射出"）——
         * ★ 用户 2026-10-11 **明确拍板改**，故这不是"悄悄改观感" ✓
         *
         * ## ★★★ 2026-10-12 **改回 `0`**（用户再次纠正）—— ★ 上面那次是**误判**
         *
         * 用户原话（逐字）：
         * > 「**放射状频谱不应该最终交于一点吗？**」
         *
         * ★★ 即：★ 用户要的就是「**条从圆心点射出、最终交于一点**」✓
         * ⇒ ★ 2026-10-11 那次"按业界惯例留中心空档"的改动**方向反了** ✗
         *
         * ### ★ 为什么当时会改错（★ 值得记）
         *
         * ★ 用户 10-11 的原话是「**三型侧边频谱的外观不大符合常见认知，你去搜索看看业界是怎么呈现的。**」
         *   —— ★ 那是**要求去检索**，**不是**"我同意检索出来的做法"✗
         * ★ 而当时的 KDoc 写成了「★ 用户 2026-10-11 **明确拍板改**」⇒
         *   ★★ **把"让我去查"记成了"用户拍板"** ⇒ ★ **依据被拔高了一级** ✗
         *
         * ### ★ 检索结论仍然有效，只是**适用范围**不同
         *
         * ★ 业界那四处（Music Motion / WaveCanvas / productivetoolbox / Rainmeter）讲的都是
         *   「**在图形周围摆一圈条**」的场景 —— ★ 那里中心要放**封面 / logo** ⇒ 必须留空 ✓
         * ★★ 而本档是「**放射状频谱**」（`RIGHT_SLOT_RADIAL`）—— ★ 用户要的是
         *   **从中心射出的放射线**（经典的 sunburst 式可视化）⇒ ★ **中心就该交于一点** ✓
         *
         * ⚠️ 与 `RING`（`0.55`）的**形态可区分性不受影响** —— 现在差得更多了
         * （"交点放射" vs "甜甜圈"）✓
         */
        const val RADIAL_INNER_RATIO = 0f
    }
}

/**
 * 第 [index] 根柱应该画在**哪个角度**（度，0 = 12 点，顺时针）。
 *
 * ★ **纯函数**，探针可直接断言（不必"画出来再看"）。
 *
 * ## 为什么取扇区**中线**（`+ step / 2`）
 *
 * 每根柱占 `sweep / count` 度的扇区。若柱子画在扇区**左边界**，那么
 * 最后一根会落在 `startAngle + sweep` 上 —— 而 `sweep = 360` 时那个角度**等于起始角**
 * ⇒ **首尾两根柱完全重叠**（在整圈模式下表现为"有一根柱是双倍宽"，肉眼像瑕疵）。
 * 取中线则所有柱子均匀分布、首尾对称 ✓
 *
 * ## 为什么不用 `% 360` 归约
 *
 * 调用方（`SpectralPainter`）把它喂给 `AffineTransform.rotate`，而旋转角
 * **本来就可以任意大**（内部走三角函数）⇒ 归约是**多余**的，且会让
 * "`sweep = 720`（两圈）"这种输入失去意义。★ 保持原值。
 *
 * @param spec 极坐标规格
 * @param index 柱序号（`0 until count`）
 * @param count 柱数。`<= 0` ⇒ 返回 [spec] 的起始角（调用方本来也不会用）
 */
fun polarAngleDeg(spec: SpectrumPolarSpec, index: Int, count: Int): Float {
    if (count <= 0) return spec.startAngleDeg
    val step = spec.sweepDeg / count
    return spec.startAngleDeg + step * (index + 0.5f)
}

/**
 * 该极坐标规格下，**条形是否从圆心射出**（内半径为 0）。
 *
 * ⚠️ **2026-10-11（BUG-166）后本判据的用途已变**：`RADIAL` 的内半径由 **0 改为 0.35**
 * （用户拍板「留中心空档」，见 [SpectrumPolarSpec.RADIAL_INNER_RATIO] 的检索表）
 * ⇒ ★ **两个插槽档的内半径都 > 0** ⇒ 本函数对生产档**恒为 `false`**。
 * ★ 它保留为**几何谓词**（"这个规格是不是从圆心起"），**不再是"放射 vs 环形"的判据** ——
 * 两者的区分现在是**内半径的大小**（`0.35` vs `0.55`）与 **occupant**（见 [isPolarSlotOccupant]）。
 */
fun isRadialFromCenter(spec: SpectrumPolarSpec): Boolean = spec.innerRadius <= 0f

/**
 * ★★★ 2026-10-11（**BUG-166**）：极坐标两档的**条数** —— 唯一出口。
 *
 * ## 判据：**内缘不粘连**
 *
 * 条宽是**线性**的（由 [SPECTRUM_BAR_PITCH_PX] 推出 ≈ 4.9 px），而极坐标下两柱之间的
 * **弧距随半径变小**。若条数按**外周长**推（`2π·R / pitch ≈ 28`），则**内缘**的弧距只有
 * `2π·innerR / 28`（`RING`：`2π·17.6/28 ≈ 3.9 px < 4.9 px`）⇒ ★ **条在内缘互相重叠**
 * ⇒ 观感从"一圈细条"退化成"一圈齿"（用户 2026-10-11 报障的形态之一，已拍板「改按内缘定」）。
 *
 * ⇒ 正解：**按内半径处的弧长定条数** ⇒ 内缘的"柱 + 缝"恰好等于一个 pitch ⇒ 不粘连 ✓
 *
 * ## ★★ 为什么不能只改传给 `bounds.width` 的值
 *
 * [spectrumBarCount] 有 `minBars = [SPECTRUM_MIN_BARS]`（**24**）托底 ⇒
 * 内缘周长（`RADIAL`：`2π×11.2 ≈ 70 px` ⇒ 10 根）会被**抬到 24** ⇒ 粘连照旧 ✗
 * ⇒ 必须走 [spectrumBarCountFor] 的 **`forceBarCount`** 通道（不经托底）✓
 *
 * ## 预览与真机**共用本函数**（预览不撒谎）
 *
 * `IslandWindow`（真机）与 `IslandPreviewPane`（预览）都调它 ⇒
 * 「真机 10 根、预览 28 根」这类**同屏不一致**结构上不可能 ✓
 *
 * @param spec 极坐标规格（内半径已夹在 `[0, 0.85·maxLength]`）
 * @param pitchPx 目标柱距（px）。`<= 0` / 非有限 ⇒ 返回 `1`（调用方本来也不会这么传）
 * @return 条数（`≥ 1`，`≤ [SPECTRUM_MAX_BARS]`）
 */
fun polarBarCount(spec: SpectrumPolarSpec, pitchPx: Float): Int {
    if (!pitchPx.isFinite() || pitchPx <= 0f) return 1
    // ★★★ 2026-10-12（**BUG-167**）：★ **内半径退化（= 0）时改用外半径** ✗
    //
    // ## 为什么要改
    //
    // ★ 用户 10-12 原话：「**放射状频谱不应该最终交于一点吗？**」⇒ ★ 放射档内半径回到 **0** ✓
    // ★★ 而本函数原**恒用内半径**算周长 ⇒ `innerR = 0` ⇒ 周长 **0** ⇒ **条数塌成 1** ✗
    //   （真机观感 = 整个放射只剩**一根楔子**）
    //
    // ## 判据：**在"柱最窄的那一圈"定条数**
    //
    // ★ "不粘连"要守的是**柱最挤的地方**：
    // · ★ `RING`（内半径 > 0）⇒ 柱最挤在**内缘** ⇒ 用内半径 ✓（原口径，不变）
    // · ★★ `RADIAL`（内半径 = 0）⇒ ★ 内缘是**一个点**（柱本来就该在那里交于一点）
    //   ⇒ ★ 那里"不粘连"**没有意义** ⇒ ★ 改用**外半径**：柱最挤的地方变成**外缘** ✓
    //
    // ★ 这样"交于圆心"与"外缘不粘连"**同时成立** —— 柱在圆心收拢、在外缘铺开，
    //   ★ 正是用户要的**放射状**观感 ✓
    // ★★ 半径口径走**唯一出口** [polarSpacingRadiusPx] ——
    //   ★ 与 `IslandWindow` 算**柱宽**用的那一个**必须同源**（BUG-170 的教训）✓
    val spacingRadius = polarSpacingRadiusPx(spec)
    val circ = (2.0 * Math.PI * spacingRadius).toFloat()
    if (!circ.isFinite() || circ <= 0f) return 1
    return (circ / pitchPx).toInt().coerceIn(1, SPECTRUM_MAX_BARS)
}

/**
 * 从**插槽 occupant**与**区域矩形**算出极坐标规格（★ 2026-10-04 改：原先读 `spectrum.shape`，
 * 但用户明确「**环形/放射归属于那个右端插槽**」⇒ 判据必须走 `island.right_slot.component`）。
 *
 * ★ **纯函数**：预览（`IslandPreviewPane`）与真机（`IslandWindow`）**共用同一个**，
 * 探针也能直接喂值断言 —— 与 [SpectrumPolarSpec] 同一条理由（几何只有一个落点）。
 *
 * ## 判据是**插槽 occupant**（★ 2026-10-04 改 —— 原先读 `spectrum.shape`，那个键已删）
 *
 * | occupant | 返回 | 观感 |
 * |---|---|---|
 * | ★ `RADIAL` | 内半径 **0** | 条从**圆心射出**（放射状） |
 * | 其余（`SPECTRUM` / `CLOCK` / `NONE` / **未知值**） | ★ **`null`** | 直角坐标 = **各自原有的绘制** |
 *
 * ⚠️ **未知值也返回 `null`**（= 退回直角坐标）—— 与 `CoverPalette.resolve` 的
 * "未知档走 manual"同一条收敛纪律：**退回安全档总比画不出来强**。
 *
 * ★ **为什么没有"环形"档**：用户 2026-10-04 拍板「**只加一档（固定一种）**」⇒
 *   取**放射**。★ 若要环形，**只需再加一个 occupant 常量 + 一个 innerRatio**，
 *   绘制与几何**都不用动**（见 [SpectrumPolarSpec.CIRCULAR_INNER_RATIO] 已备好）。
 *
 * ## `maxLength` 取 `min(w, h) / 2` 的理由
 *
 * 极坐标下"最大径向长度"是**半径**，而区域是矩形。取**较短边的一半**保证
 * 条形**永远不会转出区域外**（被调用方的 clip 切掉会看起来像"柱子被截断"）。
 * ★ 若取较长边，宽矩形里上下的条会**超出边界**。
 *
 * @param occupant `island.right_slot.component` 的取值（大小写不敏感）
 * @param left 区域左边缘（px）
 * @param top 区域上边缘
 * @param width 区域宽
 * @param height 区域高
 */
fun spectrumPolarFor(
    occupant: String,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    /**
     * ★★★ 2026-10-16（**台账 §3.77-F**）：极坐标的**径向范围**三键（百分比整数）。
     *
     * 用户诉求（逐字）：「**允许调整环形频谱的跳动幅度和范围**」+ 拍板
     * 「**径向范围（内外半径可调）**」·「**给放射+环形两档**」。
     *
     * ★ **刻意不给默认值** —— 给默认值会让"漏接线"变成**静默地用出厂几何**
     * （而那恰好也是合法状态）⇒ 漏接线看不出来 ✗（与 `drawStaticSpectrum` 的
     * `colorMode` / `colorRgb` 同一条纪律）。
     */
    radialInnerPercent: Int,
    ringInnerPercent: Int,
    outerPercent: Int,
): SpectrumPolarSpec? {
    // ★ 内半径：**按档取各自的键** —— 两档出厂值不同（0 / 55），
    //   共用一个键必然让其中一档的默认值变掉 ⇒ **必有一档回归** ✗
    val ratio = polarInnerRatioFor(occupant, radialInnerPercent, ringInnerPercent) ?: return null
    val maxLength = (minOf(width, height) / 2f * polarOuterRatioOf(outerPercent))
        .coerceAtLeast(1f)
    return SpectrumPolarSpec(
        cx = left + width / 2f,
        cy = top + height / 2f,
        maxLength = maxLength,
        innerRadiusRatio = ratio,
    )
}

/**
 * ★★★ 2026-10-16（**台账 §3.77-F**）：极坐标**内半径比例**的**唯一出口**。
 *
 * | `occupant` | 取哪个键 |
 * |---|---|
 * | `RADIAL` | `island.right_slot.radial_inner_percent` |
 * | `RING` | `island.right_slot.ring_inner_percent` |
 * | 其余（含未知值） | ★ **`null`** ⇒ 调用方退回直角坐标（与改动前同一条收敛纪律）|
 *
 * ★ 归一：`percent/100`，并夹到 `[0, MAX_INNER_RADIUS_RATIO]`
 *   —— ★ 与 `SpectrumPolarSpec.innerRadius` 的夹取**同口径**，
 *     故"滑杆能到的值"与"几何实际用的值"**不会分叉** ✓
 */
fun polarInnerRatioFor(
    occupant: String,
    radialInnerPercent: Int,
    ringInnerPercent: Int,
): Float? = when (occupant.trim().uppercase()) {
    RIGHT_SLOT_RADIAL -> polarInnerRatioOf(radialInnerPercent)
    RIGHT_SLOT_RING -> polarInnerRatioOf(ringInnerPercent)
    else -> null
}

/** 内半径百分比 ⇒ 比例（夹到几何上限，见 `MAX_INNER_RADIUS_RATIO`）。 */
fun polarInnerRatioOf(percent: Int): Float =
    (percent / 100f).coerceIn(0f, SpectrumPolarSpec.MAX_INNER_RADIUS_RATIO)

/** 外半径百分比 ⇒ 比例（下限 10% ⇒ 条形永远还有长度，不会"滑到 0 就消失"）。 */
fun polarOuterRatioOf(percent: Int): Float = (percent.coerceIn(10, 100) / 100f)

/**
 * ★★★ 2026-10-17（**BUG-222**）：极坐标**对称档**（`RING`）柱面的**轴** ——
 * **中半径**在未旋转坐标系里的 `y`（`cy − (innerR + outerR)/2`）。
 *
 * ## 为什么必须是中半径（而不是内半径 / 外半径）
 *
 * ★ 对称档的柱是**双峰**：跨中半径，向内 / 向外各长一半 ⇒ `value = 1` 时
 *   **恰好铺满 `[innerR, outerR]`** ✓
 * ★★ 若把基线放在**内半径**（`cy − innerR`）再让柱向上长 `halfSpan` ⇒
 *   柱整体**外移**半个跨度；预览 BUG-222 正是这个形态（还叠了一个
 *   "`half` 取成外半径"的错，见 [polarSymmetricHalfLengthPx]）：
 *   * 柱面落在 `[innerR + halfSpan, innerR + 2·halfSpan]` …**偏出环带**
 *   * 最长那根戳出岛体 ⇒ 用户 2026-10-17 报「环形频谱脱出」（截图取证：
 *     取色窗 476×120 实测青绿 bbox 与"外移后被岛体 clip 裁剪"逐点吻合）
 *
 * ★ **预览（`IslandPreviewPane`）与真机（`SpectralPainter.paintBars`）共用本函数**
 *   —— BUG-222 的根因正是"两边各写一遍、预览那份写错了"：真机侧
 *   `axis = cy − (innerR + outerR)/2` 是对的，预览侧却用 `cy − innerRadius` ✗
 *   ⇒ 收口到**唯一出口**，杜绝再漂移（本项目最高频缺陷形态）
 */
fun polarSymmetricAxisY(spec: SpectrumPolarSpec): Float =
    spec.cy - (spec.innerRadius + spec.maxLength) / 2f

/**
 * ★★★ 2026-10-17（**BUG-222**）：极坐标**对称档**（`RING`）单根柱的**半长**（px）
 * = `(outerR − innerR)/2 × value`（`value` ∈ 0..1，预览侧是包络 `env`）。
 *
 * ## 与 [polarSymmetricAxisY] 合起来就是柱面的纵向范围
 *
 * 未旋转坐标系里柱面 = `[axis − half, axis + half]`（旋转后是径向的
 * `[innerR + (1−value)·span/2, outerR − (1−value)·span/2]`）⇒
 * **恒在环带 `[innerR, outerR]` 内** ✓（由 `SlotPolarBandProbe` 逐位断言）
 *
 * ⚠️ `coerceAtLeast(1f)`：跨度可能被用户拖到极小（`ring_inner_percent` 上限
 *    `MAX_INNER_RADIUS_RATIO = 0.85` ⇒ 跨度下限 = `0.15 × maxLength`），
 *    但 `value` 可以是 0（包络最低 0.25，真机可以是静音 0）⇒ 不做托底会得到
 *    **零长柱**（本项目忌的"设置拖到极端 ⇒ 东西无声消失"）。1px 与真机
 *    `halfSpan` 的 `coerceAtLeast(1f)` 同口径 ✓
 */
fun polarSymmetricHalfLengthPx(spec: SpectrumPolarSpec, value: Float): Float =
    ((spec.maxLength - spec.innerRadius) / 2f).coerceAtLeast(1f) * value

/**
 * 出厂值（**百分比整数**）—— ★ 注册表的三个键必须引用这里，不许各写一份数字。
 *
 * ★★ 判据：`island.right_slot.*_percent` 的 `default` 与
 *   `SpectrumPolarSpec.RADIAL_INNER_RATIO` / `CIRCULAR_INNER_RATIO` **必须是同一个数**
 *   —— 各写一份就会重演"两份实现各自演化"（本项目最高频缺陷形态）✗
 *   由 `SpectrumGeometryProbe` 逐位断言 ✓
 */
const val POLAR_RADIAL_INNER_PERCENT_DEFAULT: Int = 0

/** 见 [POLAR_RADIAL_INNER_PERCENT_DEFAULT]。 */
const val POLAR_RING_INNER_PERCENT_DEFAULT: Int = 55

/** 见 [POLAR_RADIAL_INNER_PERCENT_DEFAULT]。 */
const val POLAR_OUTER_PERCENT_DEFAULT: Int = 100

/**
 * ★★★ 2026-10-11（**U14**）：**极坐标两档（RADIAL / RING）的共同判据** —— 唯一出口。
 *
 * ## 为什么收成一个函数
 *
 * `IslandWindow` 的 `polarArea` 判据、预览的插槽分支、探针的出图场景 ——
 * 三处都要问"这个 occupant 走不走极坐标" ⇒ 各写一遍 `== RADIAL || == RING`
 * 就是"三处各写一遍迟早漂移"（本项目最高频缺陷形态）✓
 */
fun isPolarSlotOccupant(occupant: String): Boolean =
    occupant == RIGHT_SLOT_RADIAL || occupant == RIGHT_SLOT_RING



/**
 * ★★★ 2026-10-12（**BUG-169**）：**五条小频谱的垂直活动范围** —— ★ **= 封面那个盒子**。
 *
 * ## 用户原话（逐字，两轮）
 *
 * > ① 「**五条小频谱跳动幅度和跳动范围皆缩小，对齐原实现大小**」
 * > ② 「**我们这边的上下跳动范围限定在封面大小内，带黑环时包括黑环**」
 *
 * ## ★★ 根因：**高度参照用错了量**
 *
 * | | 高度参照 | 结果 |
 * |---|---|---|
 * | ★ **改前（我们）** | `layout.lyricsHeight` = **整个歌词区高度** | ★ 条能长到**整块歌词区**那么高 ⇒ **跳得又高又远** ✗ |
 * | ★★ **本轮（用户拍板）** | `BandGeometry.coverOuterSizePx` = **封面外径（含黑胶环）** | ★ 条在**封面那个正方形**里跳 ✓ |
 *
 * ★ 既有的旁证：
 * ```kotlin
 * val scale = size / BASE_SIZE          // BASE_SIZE = 32f
 * val barHeight = 2f*scale + band.coerceIn(0f,1f) * (size - 2f*scale)
 * g.fill(RoundRectangle2D.Float(cx - 11f*scale + i*6f*scale, cy - barHeight/2f, …))
 * ```
 * ★ 它也是「**一个正方形盒子 + 以中心为轴上下对称**」—— ★ 与"封面盒子"**同一个形态** ✓
 *
 * ## ★★ 为什么复用 [BandGeometry.coverOuterSizePx] 而不另算一个尺寸
 *
 * ★ 封面的"外径含黑胶环"这条口径**已经有一个唯一出口**（`BandGeometry`），
 *   且它的 `coverTopFor` **就是"在内容区内垂直居中"** ✓
 * ⇒ ★★ 本函数**只做透传** ⇒ ★ 小频谱与封面**永远同一个盒子**（结构上不可能漂移）✓
 * ★ 纪律 10「首选修法 = 复用已存在的唯一出口」—— ★ 这里正是那条 ✓
 *
 * ⚠️ **不要在这里再写一遍 `lyricsHeight − 2×内边距`** ——
 *   ★ 那会让"封面尺寸"有**两个来源**，而两者在 `cover.scale_with_font` / 黑胶环开关下会漂移 ✗
 *
 * @param coverTopPx 封面外接矩形的顶边（= `BandGeometry.coverTopFor`，**已在内容区内居中**）
 * @param coverOuterSizePx 封面外接边长（= `BandGeometry.coverOuterSizePx`，**外径含黑胶环**）
 * @return `floatArrayOf(top, height)` —— ★ 直接喂 `drawBackgroundSpectrum` 的 `areaTop` / `areaHeight` ✓
 */
fun smallSpectrumAreaPx(coverTopPx: Float, coverOuterSizePx: Float): FloatArray {
    val h = if (coverOuterSizePx.isFinite()) coverOuterSizePx.coerceAtLeast(1f) else 1f
    val top = if (coverTopPx.isFinite()) coverTopPx else 0f
    return floatArrayOf(top, h)
}

/**
 * ★★★ 2026-10-12（**BUG-170**）：极坐标**「柱最挤的那一圈」的半径** —— ★ **唯一出口**。
 *
 * ## 为什么必须有这个函数（★ 它是"两个消费者漂移"的收敛点）
 *
 * ★ 极坐标下**两个量**都依赖"周长"，而它们**必须同源**：
 *
 * | 消费者 | 用途 |
 * |---|---|
 * | [polarBarCount] | ★ **条数** = `周长 / 间距` |
 * | `IslandWindow` 的 `spectrumBounds` | ★ **柱宽** = `bounds.width / 条数`（`paintBars` 口径） |
 *
 * ★★ **两者不同源 ⇒ 柱宽与条数对不上**（真机观感 = "肥厚花瓣"或"细到看不见"）✗
 *
 * ## ★★★ 本函数的判据：**在柱最挤的那一圈取半径**
 *
 * · ★ `RING`（内半径 > 0）⇒ 柱最挤在**内缘** ⇒ 用**内半径** ✓
 * · ★★ `RADIAL`（内半径 = **0**，条交于圆心）⇒ ★ 内缘是**一个点**
 *   ⇒ ★ 那里"不粘连"**没有意义** ⇒ 用**外半径** ✓
 *
 * ## ★ 真机取证（BUG-170 的成因）
 *
 * ★ 2026-10-12 把 `RADIAL_INNER_RATIO` 由 `0.35` 改回 `0`（用户拍板「条应交于一点」）后，
 * ★★ `IslandWindow` 那行 `2π × innerRadius` **算出了 0** ⇒ 柱宽 ≈ 0
 * ⇒ ★★★ **整个放射状频谱不显示**（用户原话）✗
 * ★ 而当时**只改了 [polarBarCount]、漏了这个消费者** ——
 * ★★ 本函数就是为了让"漏一个"**结构上不可能**（两个消费者调**同一个**函数）✓
 *
 * @param spec 极坐标规格（内半径已夹在 `[0, 0.85·maxLength]`）
 * @return 用于算周长/条数/柱宽的半径（px）
 */
fun polarSpacingRadiusPx(spec: SpectrumPolarSpec): Float =
    if (spec.innerRadius > 0f) spec.innerRadius else spec.maxLength
