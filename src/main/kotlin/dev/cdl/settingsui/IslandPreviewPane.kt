// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.cover.CoverColorExtractor
import dev.cdl.render.CoverBackdrop
import dev.cdl.render.CoverPalette
import dev.cdl.render.CoverScaler
import dev.cdl.render.SpectrumBarCorner
import dev.cdl.render.barSegmentPath
import dev.cdl.render.isSolidSpectrumPitch
import dev.cdl.render.levelColorArgb
import dev.cdl.render.spectrumBarCornerPx
import dev.cdl.render.spectrumBarRectX
import dev.cdl.render.spectrumBarSlot
import dev.cdl.render.spectrumPolylineCornerPx
import dev.cdl.render.spectrumPolylinePath
import dev.cdl.render.spectrumPolylinePoints
import dev.cdl.window.BACKGROUND_BASE_RGB
import dev.cdl.window.BACKGROUND_MODE_COVER_IMAGE
import dev.cdl.window.DissolveMask
import dev.cdl.window.bitmapRenderPx
import dev.cdl.window.deviceScaleOf
import dev.cdl.window.effectiveCornerStyle
import dev.cdl.window.leadingCoverOuterSizePx
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import dev.cdl.font.HostFonts
// ★ 2026-10-10（审计 v4 · B-3）：`dimPacked` 与未唱暗化系数**收敛到 settings/**（唯一出口）。
import dev.cdl.settings.UNSUNG_TRANSLATION_DIM
import dev.cdl.settings.dimPackedArgb
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **词岛示例预览条** —— 取色窗里的那块"示例歌词"。
 *
 * ## 用户原话（2026-09-19，第 2 次）
 *
 * > 「歌词示范的显示逻辑也很不理想。我说的是：示例歌词在**打开此设置项**
 * > （比如副歌词取色时）的取色界面时，**其他设置被固定下来并在示例歌词上渲染**，
 * > 如图……**歌词封面提供给你**（按**窗口启动时的词岛设置**选择显不显示封面），
 * > 这是封面（因为要用于演示，**随包分发**）。」
 *
 * 三件事：
 * 1. **其他设置固定** → 打开时 [PreviewSnapshot.capture] 一份不可变快照，之后不再重读；
 * 2. **在示例歌词上渲染** → 画成一个**小词岛**（形状 / 背景 / 封面 / 频谱 / 边框 /
 *    描边 / 两行歌词），而不是上一版那种"一个深色方块 + 两行字"；
 * 3. **封面随包** → `resources/demo/cover-demo.jpg`，**显示与否由快照里的
 *    `cover.enabled` 决定**（即"窗口启动时的词岛设置"）。
 *
 * ## 为什么要"改哪一项就把那一项染成待定色"
 *
 * 用户调的是**某一个元素**的颜色（比如译文描边）。若预览里所有元素都用各自设置里的色，
 * 那用户看到的仍然是"当前生效的样子"，**改动的效果要靠记忆去对比**。
 * 故 [applyEditedColor] 把**正在编辑的那一项**换成待定色 —— 所见即所得。
 *
 * ## 为什么"进度"是写死的 0.55
 *
 * 它只是为了让"已唱 / 未唱"两种色**同时可见**（全 0 或全 1 时有一半颜色看不见）。
 * 取色器不该去追播放位置 —— 那会让预览随音乐乱动，反而看不清颜色。
 */
class IslandPreviewPane(
    /**
     * 打开时的词岛外观快照 —— **可变**（2026-10-14 修复）。
     *
     * ## ⚠️ 为什么从 `val` 改成 `var`（BUG-196 根因）
     *
     * 用户报障（逐字）：「**预览词岛中没有应用当前设置。**」
     * 具体点名：频谱多色流光 · 溶边（边界第三档）· 背景不透明度 · 「还有很多」。
     *
     * ★ 根因**不是**快照缺字段（这些字段**都在** `PreviewSnapshot` 里、**都**被本面板消费），
     *   而是**快照陈旧**：`ToolWindows.present(...)` 对同一个 bucket 是
     *   **复用窗口**（`existing.toFront()`）—— 而 `previewSnapshot` 是
     *   `ColorKeyToolWindow` 的构造期 `val`（`PreviewSnapshot.capture` **只跑一次**）。
     *   ⇒ ★ 用户流程「改设置 → 打开取色窗 → 关掉 → **再**打开」看到的永远是
     *     **第一次打开时**的词岛外观 ✗（= 本项目最多次的"设了没反应"形态）。
     *
     * ★ 修法 = **把"冻结"的粒度从"应用生命周期"收到"一次开窗"**：
     *   * 同一次开窗内**仍然冻结**（用户 2026-09-19 的原始要求：
     *     「打开此设置项时，**其他设置被固定下来**并在示例歌词上渲染」）✓
     *   * 每次窗口**重新显示**时**重捕一次** ⇒ ★ 两个要求同时满足 ✓
     */
    private var snapshot: PreviewSnapshot,
    /** 正在编辑的键（如 `translation.outline.color`）—— 决定哪一项被染成待定色。 */
    private val targetKey: String,
) : JPanel() {

    /** 待定色（打包 ARGB）。`null` = 还没收到，按快照里的原色画。 */
    private var editedColor: Int? = null

    /** 示例进度：固定值，让"已唱 / 未唱"两色同时可见（见类 KDoc）。 */
    private val demoProgress = 0.55f

    /** 封面软掩膜的缓存图 + 它的键 `(尺寸, 封面形状, 词岛形态)`（见 [drawMaskedCover]）。 */
    private var coverMaskCache: BufferedImage? = null
    private var coverMaskKey: Int = -1

    /** 预览频谱的柱体路径容器（复用，见 [drawStaticSpectrum]）。 */
    private val previewBarPath = Path2D.Float()

    /**
     * 折线形态的复用路径（与 [previewBarPath] 同源纪律：**绘制热路径不新建对象**）。
     *
     * ⚠️ 与 [previewBarPath] **必须是两个实例**：折线与柱状是**互斥**的两条分支，
     * 但 `paint` 一帧里可能先画一种再画另一种（面板同时有真机预览与示意），
     * 共用一个实例会让后画的那支把先画的那支的路径 `reset()` 掉。
     */
    private val previewPolylinePath = Path2D.Float()

    init {
        isOpaque = false
        // ⚠️ 2026-09-21：**尺寸加大 16×16**（460×104 → 476×120），同时把岛体边距
        //    从 4 提到 12 —— **岛体本身仍是 452×96（逐像素不变）**，
        //    多出来的那圈是给「**包裹词岛的对比矩形**」留的位置。
        //
        // 用户原话：「深浅两色对比背景**不要在示例词岛内渲染**，要**外扩**，
        //   以一矩形范围**包裹**示例词岛。」—— 岛外没有留白就画不出那个包裹框。
        preferredSize = Dimension(476, 120)
        minimumSize = Dimension(260, 96)
    }

    /** 收到新的待定色（拖动中实时调用）。 */
    fun applyEditedColor(argb: Int) {
        if (editedColor == argb) return
        editedColor = argb
        repaint()
    }

    /**
     * ★★★ 2026-10-14（**BUG-196**）：**重新捕获**词岛外观快照（每次开窗调一次）。
     *
     * ## 为什么必须有这个入口
     *
     * 取色窗是**复用**的（`ToolWindows.present` 对同一 bucket 走 `toFront()`），
     * 而本面板的 `snapshot` 此前是**构造期一次性**的 ⇒ ★ 用户「改设置 → 再开窗」
     * 看到的是**上一次**的词岛外观（用户报障：「预览词岛中没有应用当前设置」）。
     *
     * ★ 调用点：`ColorKeyToolWindow` 的 `windowOpened`（每次显示都触发，含复用前置）✓
     *
     * ## ★ 与「其他设置固定」这条既有契约的关系
     *
     * 用户 2026-09-19 的原话是「**打开此设置项**的取色界面时，其他设置被固定下来」
     * —— ★ 那是**一次开窗内**的冻结，**不是**"永远冻在第一次开窗那一刻"。
     * 本方法把冻结的**起点**重置到"这一次开窗"，语义上**更贴合原话** ✓
     *
     * ## 两个细节
     *
     * · **相同则短路**：`data class` 的 `equals` 逐字段比 ⇒ 没变就不 `repaint`
     *   （与 [applyEditedColor] 同一条"不做无谓重绘"纪律）；
     * · **重绘触发的缓存失效**：封面软掩膜的缓存键含"尺寸/封面形状/词岛形态"，
     *   若快照里这几项变了而尺寸未变，缓存键**不含其他字段** ⇒ ★ 必须显式清缓存，
     *   否则会拿旧掩膜画新形态（本项目记过的"缓存键漏维度"形态）。
     *   故这里**无条件清掉两个缓存**（代价 = 一次掩膜重建，只发生在开窗时）✓
     *
     * @param next 新的快照（由调用点在 EDT 上 `PreviewSnapshot.capture(...)` 捕好）
     */
    fun updateSnapshot(next: PreviewSnapshot) {
        if (next == snapshot) return
        snapshot = next
        coverMaskCache = null
        coverMaskKey = -1
        repaint()
    }

    // ── 目标元素判定 ─────────────────────────────────────────────────────────
    //
    // 用**前缀**匹配而不是全等：颜色组有 3 键制（`.r/.g/.b`）与 4 键制
    // （`.r/.g/.b/.lightness`）两种形态，全等匹配会漏掉一半。
    // ⚠️ 2026-09-20：判据从 `targetKey.startsWith(prefix)` 改为**取共同根** ——
    //    前者对**4 键颜色组恒为 false**（传进来的是组前缀 `main_lyric.fill`，
    //    而这里判的是完整键 `main_lyric.fill.color`），于是颜色组在示例里
    //    **永远不染色**，只有单键（`spectrum.color`）能染色。
    //    详见 [previewTargetMatches] 的 KDoc。
    private fun targets(prefix: String): Boolean = previewTargetMatches(targetKey, prefix)

    private fun sung(): Int =
        if (targets("main_lyric.fill.color")) editedColor ?: snapshot.sungColor else snapshot.sungColor

    private fun unsung(): Int =
        if (targets("main_lyric.fill.unsung")) editedColor ?: snapshot.unsungColor else snapshot.unsungColor

    private fun translation(): Int =
        if (targets("main_lyric.translation.color")) editedColor ?: snapshot.translationColor
        else snapshot.translationColor

    /**
     * 译文**未唱**色 —— 已唱色按 [UNSUNG_TRANSLATION_DIM] 暗化（与真机同一条公式）。
     *
     * ⚠️ 必须与 `IslandWindow.UNSUNG_TRANSLATION_DIM`（0.62）**同值**：
     * 预览的全部意义是"所见即真机所示"，两者不同值会让用户在预览里调好的搭配在真机上变样。
     */
    private fun translationUnsung(): Int = dimPackedArgb(translation(), UNSUNG_TRANSLATION_DIM)

    /**
     * 频谱**基色**（打包 RGB）—— ★★★ 2026-10-14（**BUG-199**）：**改走 `spectrum.color_mode`**
     * （与真机 `Appearance.spectrumColorFor` **同一个公开纯函数**）。
     *
     * ## 用户原话（逐字）
     *
     * > 「还有**频谱条的取色也跟封面取色不一致**。」
     * > 「**套用设置的设置项**，比如设置的是封面取色那就从**示例词岛封面**取色」
     * > 「预览应从示例封面里取色，且**背景 / 频谱 / 边框都取同一处**」
     *
     * ## 原状（缺陷）：预览**恒取手动色**，完全无视 `spectrum.color_mode`
     *
     * 原实现只有一行 `snapshot.spectrumColor`（= `spectrum.color`）——
     * 于是 `spectrum.color_mode = cover` 时：
     *   · 真机 走 `CoverPalette.resolve(...)` ⇒ **从封面主色派生的亮色** ✓
     *   · 预览 仍画**手动色** ✗
     * ⇒ ★★ 用户在预览里看到的频谱色**与真机不是一个色** ⇒ 「取色不一致」✓
     *
     * ## 修法：**调真机那一个公开纯函数**（不做镜像实现）
     *
     * [dev.cdl.render.CoverPalette.resolve] 是**公开纯函数**，
     * 真机 `Appearance.spectrumColorFor` / `borderColorFor` / `backgroundColorFor`
     * 三处调的都是它 ⇒ 预览直接调它，**同源由构造保证** ✓
     * （★ 这正是本文件"背景色"一段自 2026-09-26 起就在用的做法 —— 频谱 / 边框当时**漏了**，
     *   本批补齐 ⇒ 三处**全部**同源。）
     *
     * ⚠️ `coverRgb` 取 [demoCoverRgb]（**示例封面**的主色）—— 正是用户要的
     *   「从**示例词岛**封面取色」（预览没有真实播放曲目，只能用随包示例封面）✓
     */
    private fun spectrum(): Int =
        if (targets("spectrum.color")) {
            editedColor ?: snapshot.spectrumColor
        } else {
            // ★ 与真机 `Appearance.spectrumColorFor(coverRgb)` **逐字同一条解析**。
            dev.cdl.render.CoverPalette.resolve(
                snapshot.spectrumColorMode,
                Color(snapshot.spectrumColor, true),
                demoCoverRgb(),
                dev.cdl.render.CoverPalette::brightFrom,
            ).rgb and 0xFFFFFF
        }

    /**
     * ★★★ 2026-10-15（**§3.75**）：**插槽频谱的档位** —— 与真机
     * `rightSlotSpectrumColorModeFor` 调**同一个公开纯函数**（预览不撒谎）✓
     *
     * ★ 未知值 / 缺键 ⇒ 该纯函数内部回落 `follow` ⇒ 预览与真机**同一条收敛纪律** ✓
     */
    private fun slotSpectrumColorMode(): String =
        dev.cdl.window.rightSlotSpectrumColorModeFor(snapshot.rightSlotSpectrumColorMode)

    /**
     * ★★★ 2026-10-15（**§3.75**）：**插槽频谱的基色**（打包 RGB）—— 与真机
     * `rightSlotSpectrumColorFor` 调**同一个公开纯函数** ✓
     *
     * ★ `follow` 档传的是 [spectrum]（**主频谱已解析的色**）⇒ "跟随"是**结构性**的：
     *   主频谱怎么变插槽就怎么变，两边**不可能漂移** ✓
     * ★ 取色器正在编辑**插槽那个键**时用 `editedColor` 实时预览（与 [spectrum] 同一条规矩）✓
     */
    private fun slotSpectrum(): Int {
        if (targets("island.right_slot.spectrum_color")) {
            editedColor?.let { return it }
        }
        return dev.cdl.window.rightSlotSpectrumColorFor(
            slotSpectrumColorMode(),
            Color(spectrum(), true),
            Color(snapshot.rightSlotSpectrumColor, true),
            demoCoverRgb(),
        ).rgb and 0xFFFFFF
    }

    /**
     * 边框色（打包 RGB）—— ★★★ 2026-10-14（**BUG-199**）：**改走 `island.border.color_mode`**
     * （与真机 `Appearance.borderColorFor` **同一个公开纯函数**）。
     *
     * 理由与 [spectrum] 逐字相同（同一个缺陷、同一批修）——
     * 原实现恒取 `snapshot.borderColor`，于是 `island.border.color_mode = cover` 时
     * 真机从封面派生、预览画手动色 ⇒ 「取色不一致」✓
     */
    private fun border(): Int =
        if (targets("island.border.color")) {
            editedColor ?: snapshot.borderColor
        } else {
            // ★ 与真机 `Appearance.borderColorFor(coverRgb)` **逐字同一条解析**。
            dev.cdl.render.CoverPalette.resolve(
                snapshot.borderColorMode,
                Color(snapshot.borderColor, true),
                demoCoverRgb(),
                dev.cdl.render.CoverPalette::brightFrom,
            ).rgb and 0xFFFFFF
        }

    private fun mainOutline(): PreviewSnapshot.OutlineSpec? = snapshot.mainOutline?.let { spec ->
        if (targets("main_lyric.outline")) {
            val c = editedColor ?: spec.color
            spec.copy(color = c)
        } else {
            spec
        }
    }

    private fun translationOutline(): PreviewSnapshot.OutlineSpec? =
        snapshot.translationOutline?.let { spec ->
            if (targets("translation.outline")) spec.copy(color = editedColor ?: spec.color) else spec
        }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        // 岛体：四周留出**统一**边距 12px —— 一是避免描边/圆角被组件边缘切掉，
        // 二是给岛体轮廓一点呼吸空间（见 [preferredSize] 的说明）。
        //
        // ⚠️ 2026-09-21 更正：这里原先写「`12 = 4`（安全边距）`+ 8`（对比矩形的外扩量 [backdropPad]）」。
        //    对比底已改为**铺满整个组件**（见 ⓿ 段），不再需要"外扩量"这种东西，
        //    `backdropPad` 已随之删除 —— 那句话会变成**假话**，故同步改掉。
        //    （本项目"注释与事实相反"已出现 7 次，全是编译器抓不到的。）
        val inset = 12f
        val islandW = w - inset * 2
        val islandH = h - inset * 2
        // ★ 2026-09-21：形状**按快照的 `shape.type` 选**（原先恒为圆角矩形）——
        //   见 [PreviewSnapshot.shapeType] 的 KDoc：用户报的「打开前方角，进去看还是圆角」。
        val shape: Shape = islandShape(snapshot, inset, islandW, islandH)
        // ★★ 2026-10-01：玻璃要用的圆角半径 —— 与上面 `shape` 走**同一个出口**
        //   （[previewIslandRadiiPx]）。传给 `MaterialLayers.paint` 的 `shapeCornerRadius`。
        //
        // ⚠️ 为什么必须传：不传 ⇒ `MaterialLayers` 走默认 `-1` ⇒ 它**自己推**一个圆角
        //   （`min(w,h)*0.5*0.4`）⇒ 逐像素玻璃的边缘光**与预览的岛体形状对不上**
        //   （`SQUARE` 下最明显：岛体是直角、玻璃却是圆角矩形）。
        //   真机侧同一处已修（BUG-148），预览侧此前**漏了** —— 本次补齐。
        //
        // ★★ 2026-10-02（T5）：**逐边传**（`first` = 上边、`second` = 下边）。
        //   ⚠️ 原记：「取**较大者**：`NOTCH` 上大下小，而 SDF 只收一个半径（与真机同一条取舍）」
        //      —— ★ **那句话已过期**：T5 把 `GlassSdf` 改成**收两个半径**，
        //      真机侧与预览侧**同时**改成逐边传（`migration/75`）。
        //      留旧话会成为"注释与事实相反"（本文件 180 行正记着这类已出现 7 次）。
        val previewRadii = previewIslandRadiiPx(snapshot.shapeType, snapshot.cornerRadius, islandW, islandH)

        // 岛体背景的不透明度（0..100 → 0..255）。
        //
        // ⚠️ 2026-09-20 的历史背景（保留作依据）：用户的 `background.opacity = 0`
        //    —— 岛体背景**全透明**。真机上它盖在桌面上没问题，但取色窗是**浅色底**，
        //    于是"白色歌词 + 浅色底" = **看不见字**（用户报过「示例歌词一个字都没有」）。
        //    当时的修法是"在岛体内铺一层中性灰卡"；**现在那层灰卡已由下面的对比底承担**
        //    （它垫在岛体之下、包住岛体，全透明时透出来）—— 故此处只留不透明度换算。
        val bgAlpha = (snapshot.backgroundOpacity.coerceIn(0, 100) * 255 / 100)

        // ── ⓿ 参考对比底：**铺满整个预览组件**（2026-09-21 第二次更正）────────────
        //
        // ## 用户原话（两次，逐字）
        //
        // 第一次（**这不是用户的话，是我上一轮的误读**；保留在此只为说明为什么会有第二次更正）：
        //   「深浅两色对比背景**不要**在示例词岛内渲染，要**外扩**，以一矩形范围**包裹**示例词岛。」
        //
        // 第二次（用户的真实意图，**同时否掉了上面那条**）：
        //   「深浅色各一半的色块是作为**参考背景**……**背景，哪有背景局限于词岛内或者只在词岛外的？**」
        //
        // ## 原记与实为
        //
        // 原记：对比底 = "岛体外扩 8px 的包裹矩形"（`backdropPad` + `backdropShape` + `g2.clip`），
        //   并写着"用户要的是把词岛放在一张对比卡上"。
        // 实为：**它是一层"参考背景"，不该被岛体边界二分。**
        //   正解 = 铺满整个预览组件、词岛叠在它上面：`background.opacity = 100` 时岛内被盖住、
        //   岛外看得见；`opacity < 100` 时岛内也透出来 —— **不需要任何"内/外"的分别**。
        // 取证：用户报障「**词岛内**没有显示各一半的深浅色背景」+ 上面第二次的原话。
        //
        // ## 深浅两块的**左右顺序随系统主题交换**（用户要求）
        //
        // 用户原话：「注意"深浅对比色块"间要能**随着深浅主题切换交换位置**。」
        // → 规则见 [backdropLeftColor]：**与主题同色的那一块放左边**（浅色主题左浅、深色主题左深）。
        //   左缘紧贴预览区边界，与工具窗底色连成一片才不突兀；不交换的话，深色主题下
        //   预览区左缘会突然出现一大块亮色。
        // ⚠️ 主题取值走 [ToolWindowPalette.isLight]（唯一出口 —— 见它的 KDoc：
        //   **每次都重新问系统**，因为用户可能在窗口开着时切换主题）。故此处每帧读一次。
        //
        // ★★★ 2026-10-15（用户报障，逐字）：「**注意红圈中指出的黑角和封面脱出问题。**」
        //
        // 实测（离屏渲染 + 逐点取色）：
        //   楔形区 (16,16) = **#2C2C31** · 右上 (462,13) = **#E8E8EC**
        // ⇒ 用户读作"黑角"的那四块**不是纯黑**，而是**对比底从岛体圆角处露出**来的一块。
        //   深色主题下左半 `#2C2C31` 已接近黑 ⇒ 观感就是"岛体被咬掉四块" ✗
        //
        // ## ★★★ 2026-10-17：**回滚**「裁到岛体轮廓」—— 2026-10-15 那次归因是**错的**
        //
        // ### 原记（2026-10-15，本段此前）—— **已推翻**
        //
        // 原记：用户报「**注意红圈中指出的黑角**」⇒ 我离屏取色得楔形区 (16,16) = `#2C2C31`
        //   ⇒ 认定那是「**对比底从岛体圆角处露出**」⇒ 改成 `clip = shape` + 外围涂 window 底色。
        //
        // ★★ **实为：那个 `#2C2C31` 不是对比底** —— 是 `cover_image` 档
        //   [drawCoverImageBackdrop] 的**烘焙底**（`CoverBackdrop` 的蒙版色同族）。
        //   ★ 决定性证据：用户 2026-10-16 02:06 的截图放大后可辨 ——
        //     岛体是圆角矩形、四角是**背景照片的方角**，而**对比底铺满时四角本不该是这个颜色**。
        //   ★ 那次取色**没有判别力**：`#2C2C31` 同时是"对比底深色半"与"封面图烘焙底"的值，
        //     一个像素分不出是哪一层 ✗
        //   ⇒ **判据：红圈报障要先确认【圈的是哪一层】—— 取色必须分层取**
        //     （关掉 `cover_image` 档再取一次，两次对比才定层）。
        //
        // ### 那次"修法"造成的后果（★ 两个新缺陷，原缺陷一个没修掉）
        //
        // ① ★★★ 四角从 `#2C2C31` 变成 **window 底色 `#202022`**（**更深**）——
        //    ★ 用户 2026-10-16 报的「**黑角**」正是它：我把"黑角"改得更黑了 ✗
        // ② ★★★ 对比底**整个看不见了**（岛外是 window 底色、岛内被不透明的封面图盖住）
        //    —— 用户 2026-10-17 逐字：「示例词岛依旧没修好，**对比色背景还被修没了**」✗
        //
        // ### 用户对对比底的**两次**明确要求（都指向"铺满"，本段此前却把第二句读成了否定）
        //
        // · 「深浅色各一半的色块是作为**参考背景**……**背景，哪有背景局限于词岛内或者只在词岛外的？**」
        // · 「**以一矩形范围包裹**示例词岛」（2026-09-21；后由上一句修正为"铺满整个组件"）
        //
        // ### 现在的做法：**回到"铺满整个组件"**
        //
        // ① 对比底**铺满整个组件**（不设 clip）—— 这是"参考背景"的本意；
        // ② 岛体（背景 / 材质 / 内容）照旧叠在它上面；
        // ③ ★ **圆角处露出对比底是"背景的正常露出"，不是缺陷** —— 不再试图"修"它。
        //
        // ⚠️ 分界线仍是**直线**（用户原话「示例词岛**背景**不要跟随词岛用圆角」）✓
        // ⚠️ 岛体本身**仍保持圆角** —— 它模拟的是词岛 ✓
        // ⚠️ 仍只承担**对比**职责：岛体背景不透明时岛内被盖住 —— 那正是它存在的理由
        //    （否则白字在浅底上读不出来）。
        val isLightTheme = ToolWindowPalette.isLight

        // 对比底：**铺满整个组件**（不设 clip —— 见上）。
        // `+1` 是为了让分界线落在**奇偶像素交界**时不留下一条缝
        // （两半恰好在中线接壤，取整误差会漏出下层）。
        g2.color = backdropLeftColor(isLightTheme)
        g2.fillRect(0, 0, (w / 2f).toInt() + 1, h.toInt())
        g2.color = backdropRightColor(isLightTheme)
        g2.fillRect((w / 2f).toInt(), 0, (w - w / 2f).toInt() + 1, h.toInt())

        // ── ① 岛体背景（★ **不设 clip** —— 见下方 AA 说明）───────────────────────
        //
        // ⚠️⚠️ 2026-09-21（BUG-121）：这里此前是 `g2.clip = shape` 之后 `g2.fill(shape)`
        //     —— 一次**硬裁剪 × 软填充**的错配。
        //
        // ⚠️ 与 [drawMaskedCover] 那条（**BUG-117**）**不是同一个缺陷**，别合并：
        //    本条的根因是"`setClip` 走 `Region`、**忽略 AA 提示**"（硬掩膜 vs 软填充）；
        //    BUG-117 的根因是"`DstIn` + `fill` 时**光栅化器不访问形状之外的像素**"。
        //    两者症状也不同：本条是**岛体外缘** 1px 毛边，BUG-117 是**封面完全没被裁**。
        //
        // Java2D 里这两条走的**不是同一套栅格化**：
        //   · `fill(Shape)` 受 `KEY_ANTIALIASING` 影响 → 边缘是**软**的（0..1 覆盖度斜坡）；
        //   · `setClip(Shape)` 走 `Region` → **永远是像素中心判定的硬掩膜**，与 AA 提示无关。
        // 于是覆盖度 `0 < c < 0.5` 的那些像素（中心在形状外、但形状盖住了它一部分）被
        // **整块丢弃** → 外缘那圈 AA 斜坡被削掉一半 → 边缘出现 1px 锯齿/毛边；
        // 拖动取色板时每帧重绘、每帧重新栅格化 ⇒ 毛边闪烁。
        //
        // 用户原话：「示例词岛**边缘有杂色像素**」「一调色就出现」。
        //
        // ## 修法
        //
        // **背景这一笔不设 clip** —— `fill(shape)` 自己就带 AA，而形状之外它本来也画不到；
        // `clip` 只留给**内容**（封面 / 频谱 / 文字）—— 它们才需要被岛体裁掉。
        //
        // ⚠️ 这里**有意不**把对比底改成 `Area(backdrop) - Area(shape)`（"只画岛外"）：
        //    用户上一轮明确要求「深浅两色对比背景……**以一矩形范围包裹**示例词岛」，
        //    而它之所以要**垫在岛体之下**，是因为"岛体背景设为全透明"时用户仍需要
        //    一个深浅对比底来判色（那是本轮之前修过的"示例歌词一个字都没有"）。
        //    改成"只画岛外"会让不透明度 0 时岛内**彻底透明** → 白字看不见 = 回归。
        //    见 `paintComponent` ⓿ 段与 [PreviewSnapshot.backgroundOpacity] 的说明。
        //
        // ## ★★ 岛体底色 **与真机同源**（2026-09-26 修）
        //
        // ### 原状（缺陷）：底色是**硬编码常量**
        //
        // 此处此前只有一行 `val islandBase = Color(0x14, 0x16, 0x1A, bgAlpha)` ——
        // 写死的深灰，且**只有不透明度**跟着快照走。后果与 `shape.type`（09-21）、
        // `shape.corner_style`（09-25）**完全同一族**：用户改 `background.color`、
        // 或把 `background.color_mode` 切到 `cover` 时 —— **真机变、预览不变**，
        // 他会以为设置没生效；而这类缺口**不会报错**（两侧各画各的，都"没崩"）。
        //
        // ### 修法：**调真机那一个公开纯函数**，不在这里复刻公式
        //
        // 真机 `Appearance.backgroundColorFor` 就是
        // `CoverPalette.resolve(mode, manual, coverRgb, CoverPalette::darkFrom)`，
        // 而它是 `render/CoverPalette.kt` 里的**公开纯函数** ⇒ 预览直接调它，
        // **同源由构造保证**，不靠"两边记得同步"。alpha 算式与真机
        // `backgroundWithOpacity` 逐字相同（收在 [previewBaseColorArgb] 一处）。
        //
        // ⚠️ 快照必须带全输入（`color_mode` / `background.color`），否则本函数只能拿到
        //    默认值 ⇒ 又退回"真机变、预览不变"。那三个字段见 [PreviewSnapshot]。
        // ★★★ 2026-10-14（**BUG-201**）：**喂不喂 `coverRgb` 必须与真机同一条判据**。
        //
        // ## 旧写法（错）与症状
        //
        // ```kotlin
        // val previewCoverRgb = if (snapshot.coverEnabled) demoCoverRgb() else null   // ✗
        // ```
        // ★ 只看了 `cover.enabled`，**漏掉三个 `*_color_mode == cover` 的同族情形** ——
        //   于是当用户配置「**不显示封面** + 跟随封面取色」时：
        //     · 真机 `coverImageFor` 仍加载封面 ⇒ 三路取色全部生效 ✓
        //     · 预览 `previewCoverRgb = null` ⇒ **背景**退回手动色，
        //       而下面 `spectrum()` / `border()` 各自调 `demoCoverRgb()` ⇒
        //       **背景 ≠ 频谱 ≠ 边框** ✗
        //   ⇒ 正是用户报的那两条（逐字）：
        //     「**词岛背景颜色与封面颜色的不相符**」
        //     「**频谱条的取色也跟封面取色不一致**」
        //   ⇒ 用户要求：「**预览应从示例封面里取色，且背景/频谱/边框都取同一处**」✓
        //
        // ## 修法：调真机**同一个**公开纯函数 `needsCoverImageFor`
        //
        // ★ 不做镜像实现 —— 真机 `IslandWindow.coverImageFor` 现在也调它
        //   ⇒ "要不要封面色"这件事**只有一个定义点** ✓
        //   （快照必须带全四个入参，否则只能拿到默认值 ⇒ 又退回"真机变、预览不变"。）
        val previewCoverRgb = if (
            dev.cdl.window.needsCoverImageFor(
                coverEnabled = snapshot.coverEnabled,
                lyricColorMode = snapshot.lyricColorMode,
                backgroundColorMode = snapshot.backgroundColorMode,
                spectrumColorMode = snapshot.spectrumColorMode,
            )
        ) {
            demoCoverRgb()
        } else {
            null
        }
        val islandBase = Color(
            previewBaseColorArgb(
                mode = snapshot.backgroundColorMode,
                manualArgb = snapshot.backgroundColorArgb,
                coverRgb = previewCoverRgb,
                opacityPercent = snapshot.backgroundOpacity,
            ),
            true,
        )

        // ★ 与真机**同一套两档分支**：`cover_image` 档画「封面图 + 同源取色蒙版」的烘焙图，
        //   其余档画纯色。真机上这两档是互斥的（`cover_image` 是纯色路径的 `else` 的另一支），
        //   预览必须**同构** —— 否则用户在封面图档下看到的是纯色，而真机是封面背景。
        val previewBackdropSrc =
            if (snapshot.backgroundColorMode == BACKGROUND_MODE_COVER_IMAGE && bgAlpha > 0) {
                demoCover()
            } else {
                null
            }
        if (previewBackdropSrc != null) {
            drawCoverImageBackdrop(g2, shape, previewBackdropSrc, previewCoverRgb, inset, islandW, islandH)
        } else {
            g2.color = islandBase
            g2.fill(shape)
        }

        // ③′ **材质三层**（2026-09-25 第 2 期波次 2）—— 与真机同一份实现、同一个顺序。
        //
        // ⚠️ 为什么必须在这里也画：预览的全部意义是"所见即真机所示"。
        //    不画 ⇒ 用户拖「顶部高光 / 背景渐变 / 颗粒幅度」时**真机变、预览不变**，
        //    他会以为设置没生效。（`shapeType` 2026-09-21 / `cornerStyle` 2026-09-25
        //    已经栽过两次同类缺口。）
        //
        // ⚠️ 用 `setPaint` + `fill(shape)`（不是 clip）—— 与真机同一条理由：
        //    `setClip` 是硬边、忽略 AA，会在圆角处留 1px 锯齿。
        //
        // ⚠️ **与 `cover_image` 档互斥** —— 与真机**同一条边界**：那一档的背景已经是
        //    "离屏烘焙后一次画上"的完整背景，再叠三层观感不可预测
        //    （见 [dev.cdl.render.MaterialLayers] 的 KDoc）。
        //    真机的互斥判断在调用点（`cover_image` 是纯色路径的 `else`），
        //    预览此处同构，故 `previewBackdropSrc != null` 时**不叠三层**。
        if (previewBackdropSrc == null) {
            // ★★★ D-1（2026-10-09）：**L2 玻璃 GPU 后端** —— 预览侧注入点。
            //
            // 与真机 `IslandWindow.applyAppearance` **同一条约定**（变更时推一次，
            // 消费点只读字段）：`MaterialLayers.gpuEnabled` 是那个 object 的成员，
            // 不在 `Appearance` / 快照之外**任何一处**能被自动带下去 ⇒ 两侧都得显式推。
            //
            // ⚠️ 必须在 `MaterialLayers.paint(...)` **之前** —— 它是本帧的消费点。
            // ⚠️ 预览是**离屏/同步**渲染，没有 `glassPool` 的线程切换 ⇒ 这里直接写是安全的。
            dev.cdl.render.MaterialLayers.gpuEnabled = snapshot.materialRenderGpuBackend
            dev.cdl.render.MaterialLayers.paint(
                g = g2,
                shape = shape,
                highlightStrength = snapshot.materialHighlightStrength,
                gradientStyle = snapshot.materialGradientStyle,
                // ★★ 2026-10-03（⑰ F1 接线）：`FLOW` / `CAMO` 档的多色原料。
                //
                // ⚠️ **必须与真机同时传**（门禁 ⓪j `check_preview_parity`：共同调用的函数，
                //   命名实参集合必须相等 —— 这条护栏就是 BUG-149「预览比真机少传 5 个实参、
                //   导致液态玻璃 4 键拖了预览不变」的产物）。
                //
                // ★★★ 2026-10-16（**§3.77-E**）：**改用示例封面真正的多色**。
                //
                // ## 原记与实为（★ 一处"前提错 ⇒ 结论错"）
                //
                // 原记（本段此前逐字）：「★ 预览**没有真实封面**（它是静态示例）⇒
                //   用一组**代表值**让 `FLOW` 在预览里**看得见**」+ `FLOW_PREVIEW_PALETTE`
                //   （硬编码 **琥珀 `#B45309` / 靛蓝 `#1D4ED8` / 石板灰 `#64748B`**）。
                // ★★ **实为**：预览**有**示例封面 —— `demoCover()` 读随包资源
                //   `/demo/cover-demo.jpg`，`demoCoverPalette()` 调的就是真机那**同一个**
                //   公开入口 `CoverColorExtractor.palette` ⇒ ★ **前提不成立，结论自然错** ✗
                //
                // ## 用户报障（逐字，2026-10-16）
                //
                // > 「示例词岛背景的多色碎块**不符合示例词岛的封面**
                // >  （**没取对 / 取的色不像来自示例封面的**）」
                //
                // ★ 与硬编码的那三个色（橙 / 蓝 / 灰）**逐字吻合** —— 用户看到的正是它们 ✗
                // ⇒ ★ 真机此处传 `coverPalette`（封面取色），预览却传"代表值"
                //   ⇒ **预览与真机不同源**（本项目反复点名反对的形态）✓
                //
                // ★ 修法：与真机**同源** —— 传 `demoCoverPalette()` ✓
                //   （示例封面取不到时它返回**空表** ⇒ 与真机"无封面"时的行为一致：
                //     多色档退回单色，而**不是**退回一组凭空的代表值 ✓）
                flowPaletteArgb = demoCoverPalette(),
                // ★★ 2026-10-03（⑰ **F2**）：相位。★ **预览是静态的**（没有播放位置）
                //   ⇒ 固定 `0f`（= 真机在 `position = 0` 时的相位）。
                //   ★ 与真机**同传**是硬要求（门禁 ⓪j `check_preview_parity`：
                //     共同调用的函数，**命名实参集合必须相等**）。
                flowPhase = 0f,
                gradientTintPercent = snapshot.materialGradientTintPercent,
                gradientAngleDeg = snapshot.materialGradientAngleDeg,
                gradientEndArgb = snapshot.materialGradientEndArgb,
                baseColor = islandBase,
                noiseAmplitude = snapshot.materialNoiseAmplitude,
                // ★ 2026-09-25 第 3 期 L2：档位 `OFF` ⇒ 三层全不画（与真机同一个出口）。
                quality = snapshot.materialQuality,
                // ★★ 2026-10-01（液态玻璃子组）：**与真机逐字对齐的 5 个实参**。
                //
                // ⚠️ 此前这 5 个**一个都没传** —— 而 `migration/69` / `70` 落地液态玻璃时，
                //   两份施工记录里 grep「预览」= **0 命中** ⇒ 是**漏接**（不是有意不做）。
                //   后果分两档（见 [PreviewSnapshot.materialLiquidGlarePercent] 的 KDoc）：
                //     · `glare` / `depth` 的默认值**恰好等于面板值** ⇒ 预览**画了但不跟随**；
                //     · `surface` 默认 `0` ⇒ 预览**根本不画**这一层。
                //   ★ 这也是本族缺口的**第四次**（前三次：`shapeType` / `cornerStyle` /
                //     `backgroundColorMode`）—— 故探针加了"实参集合必须与真机一致"的断言。
                liquidGlarePercent = snapshot.materialLiquidGlarePercent,
                liquidDepthEffect = snapshot.materialLiquidDepthEffect,
                liquidSurfaceAlphaPercent = snapshot.materialLiquidSurfaceAlpha,
                // ★ 半径取预览**自己造形状用的那个**（`previewRadii` 与 `shape` 同源）。
                // ★★ T5（2026-10-02）：**与真机同源** —— 逐边传（不再 `maxOf`）。
                //   ★ 否则 `NOTCH` 档下**预览的玻璃边缘**会与**真机的**不一致（真机已改双半径）。
                //   ⚠️ 这一对参数名必须与真机调用点**完全一致** —— 由 `check_preview_parity.py`（门禁 ⓪j）机械保证。
                shapeCornerRadius = previewRadii.first.toDouble(),
                shapeCornerRadiusBottom = previewRadii.second.toDouble(),
                // ★ 超椭圆指数：显式传**与真机同一个常量** ⇒ 两处不可能漂移（BUG-148 同族）。
                shapeRoundness = dev.cdl.render.GlassSdf.DEFAULT_ROUNDNESS,
                // ★★ 2026-10-02（**T1 降级链**）：**显式传 `false`** —— 预览是**静态**的
                //   （不展开/收起 ⇒ 形状不变化 ⇒ 无"动画中"可言）。
                //
                // ⚠️ 为什么不靠"默认值就是 `false`"省掉这一行：
                //   ★ **新守卫 `check_preview_parity.py`（门禁 ⓪j）会当场报红** ——
                //     它要求"预览与真机共同调用的函数，命名实参集合必须相等"
                //     （真机传了、预览没传 ⇒ 不同源）。
                //   ⇒ ★ 这正是那条守卫**设计意图**的体现：**差异必须显式声明**，
                //     不能靠"默认值恰好一样"蒙过去（BUG-149 就是这么发生的）。
                glassAnimating = false,
                // ★★★ 2026-10-17（对齐 A1）：表面 α 上限 0.5（与真机同源，取同一个设置）。
                //   ★ 显式传是门禁 ⓪j 的硬要求（两侧实参集合必须相等）。
                surfaceOpacityPercent = snapshot.backgroundOpacity,
            )
        }

        // ★★★ 2026-10-13（**BUG-187**）：**「溶边」掩版在此应用** ——
        //   位置 = **背景（含材质三层）之后、内容之前** ⇒ **只淡化岛体背景** ✓
        //   ★★ 与真机 `IslandWindow.applyDissolveMask` **同一个相对位置**（背景段之后）✓
        //   ★ 旧位置在最后（边框块里）⇒ 把**歌词 / 封面 / 控件一起淡化** ✗
        //     （用户原话：「**淡化把词岛内容也淡化了**」）
        //   ★ 为什么以前看不出来：BUG-182 时带宽只有 8 px ⇒ 擦不到内容；
        //     ★ BUG-185 把带宽改成**岛体半高**后才够到 ⇒ 症状暴露 ✓
        if (snapshot.borderEnabled &&
            snapshot.borderMode == dev.cdl.settings.BORDER_MODE_EDGE_DISSOLVE &&
            snapshot.backgroundOpacity != 0
        ) {
            val dissolveBand = dev.cdl.settings.dissolveBandPx(
                islandH, snapshot.borderDissolveBandPercent,
            )
            if (dissolveBand > 0f) {
                // ★★ 与真机**同一个出口**（`DissolveMask.get`）⇒ 蒙版逐项同源 ✓
                val mask = DissolveMask.get(
                    widthPx = islandW.toInt(),
                    heightPx = islandH.toInt(),
                    bandPx = dissolveBand,
                    topRadiusPx = previewRadii.first,
                    bottomRadiusPx = previewRadii.second,
                    cornerStyle = effectiveCornerStyle(snapshot.shapeType, snapshot.cornerStyle),
                    cornerN = snapshot.cornerN,
                )
                if (mask != null) {
                    val savedComposite = g2.composite
                    val savedClipDissolve = g2.clip
                    try {
                        // ★★★ 2026-10-16（**BUG-214 · 台账 §3.77-B**）：**必须裁到岛体轮廓** ——
                        //   否则「四角黑」。
                        //
                        // ## 现场（离屏渲染 + 逐点取色，见 `migration/157`）
                        //
                        // 掩版的 alpha 剖面 = 「形状**外**恒 0 · 形状内 0→255 斜坡（带宽 `band` px）」。
                        // ★ 而 `DstIn` 是**逐像素乘目标** ⇒ 形状之外那一圈（四个圆角 +
                        //   左右端帽的月牙）被乘成 **0** ✗
                        //
                        // | 目标缓冲 | 形状之外那一圈的结果 |
                        // |---|---|
                        // | 真机（**逐像素透明窗口**）| 本来就透明 ⇒ 乘 0 后仍透明 ⇒ **无害** ✓ |
                        // | 预览（**不透明组件**）| `DstIn` 只能把**颜色**乘成 0（RGB 缓冲没有 alpha 可降）⇒ **纯黑** ✗ |
                        //
                        // ★ 用户原话（逐字）：「**四角黑**」—— 实测四角恒为 `(0,0,0)`，
                        //   而面板底色是 `(32,32,34)` ⇒ **不是底色，是乘 0 的产物** ✓
                        //
                        // ## 为什么真机那边**不改**（有意保留不对称）
                        //
                        // ★ 真机**必须**让掩版擦掉形状之外的**投影** —— 见 `IslandWindow.applyDissolveMask`
                        //   的 KDoc：「只擦背景会让边缘**透出投影** ⇒ 一条暗边 ✗ ⇒ 背景与投影一起擦
                        //   才是『岛体整体溶边』的语义」。
                        // ★★ 而**预览根本不画投影**（见 `PreviewSnapshot` 的 `materialShadowStrength`
                        //   KDoc）⇒ 形状之外**无物可擦** ⇒ 裁掉它**不损失任何语义**，只去掉那一圈黑 ✓
                        //
                        // ## 为什么不是"改掩版语义"（把形状之外改成 alpha 255）
                        //
                        // ★ 那会**同时改真机** ⇒ 投影会重新露出来 ⇒ 用户明确否过的「一条暗边」回来 ✗
                        // ⇒ ★ 判据：**"两边看起来不一样"未必是缺陷 —— 要看那条差异是否来自
                        //   "两边画的层不同"**。这里真机多画一层投影，预览不画 ⇒ 差异是**必然**的 ✓
                        g2.clip = shape
                        // ★ `DstIn` = 「用源的 alpha **乘**目标的 alpha」⇒ 边缘渐隐 ✓
                        g2.composite = java.awt.AlphaComposite.DstIn
                        g2.drawImage(mask, inset.toInt(), inset.toInt(), null)
                    } finally {
                        g2.composite = savedComposite
                        g2.clip = savedClipDissolve
                    }
                }
            }
        }

        val oldClip = g2.clip
        g2.clip = shape
        try {
            var textLeft = inset + 10f

            // ── ② 封面（`cover.enabled` 决定，见类 KDoc 第 3 条）──────────────
            //
            // ⚠️⚠️ 2026-09-21（BUG-118）：原为 `min(islandH - 12f, 84f)` —— **硬上限 84px**，
            //    于是"封面随字号缩放"在预览里**恒等于关**（词岛变大封面不动）。
            //    用户原话：「示例封面**没有适应示例词岛大小**。」
            //    现按真机同一条公式（`IslandWindow.leadingCoverSize`）算，见 [coverSideFor]。
            //
            // ⚠️ 同时补上 `cover.shape`：用户选了"圆形封面"（黑胶）在预览里此前**看不出变化**。
            // ⚠️ 2026-09-21 再补两档：`cover.shape = SQUARE`（`coverMaskShape` 此前只有
            //    `CIRCLE / else` ⇒ 选「方形」画的是圆角）与 `cover.vinyl`（快照里根本没有
            //    这个字段 ⇒ 黑胶开关在预览里恒等于关）。见 `ColorPickerProbe` ⑯.5。
            if (snapshot.coverEnabled) {
                val cover = demoCover()
                if (cover != null) {
                    // ── ★★ 2026-09-22：几何从"内容固定 + 环外扩"改成"**外径不变 + 内容内缩**"
                    //
                    // 用户原话：「**黑边太窄了**，怎么说也得**和圆形封面的半径的 1/3**，
                    //   同时为了保证封面协调，**加了黑边的封面要适当整体缩小一下**。」
                    //
                    // 旧写法把 `side = coverSideFor(...)` 直接当**内容**画、环画在外圈
                    // ⇒ 加黑边后**总尺寸变大**（默认 105 → 117px），把歌词往右挤 ——
                    // 那正是用户否掉的。新写法把 `outer` 当**槽位（外径）**：
                    //   内容 = outer − 2×ring，环向内占 ⇒ **外径恒定**。
                    val outer = coverSideFor(islandH, snapshot.coverScaleWithFont)
                    // ── 黑胶环宽（`cover.vinyl`）────────────────────────────────
                    // ⚠️ 这是**预览本地那份同值重写**（[previewVinylRingWidth]），
                    //    **不是**真机 `IslandPolicy.coverVinylRingWidth` —— 两者靠探针
                    //    跨包逐点比对钉住（⑯.5「跨包漂移护栏」）。
                    val ring = previewVinylRingWidth(outer, snapshot.coverVinyl)
                    // 内容直径 = 外径 − 2×环宽（关黑胶时**恒等于**外径）。
                    val content = previewCoverContentSize(outer, snapshot.coverVinyl)
                    // 内容左缘 / 上缘（都在**槽位内居中**）与槽位之后的歌词起点
                    // 都由纯函数给出 —— 探针断言的就是这三个函数，
                    // 槽位几何**不存在第二份实现**（环宽那一份是上面说的同值重写）。
                    val cx = coverContentLeft(inset, outer, snapshot.coverVinyl)
                    val cy = coverContentTop(inset, islandH, outer, snapshot.coverVinyl)
                    drawMaskedCover(
                        g2, cover, cx, cy, content, snapshot.coverShape, snapshot.shapeType,
                        snapshot.coverSharpness,
                    )
                    // 环画在封面**之后**、且**不裁剪到封面形状** ——
                    // 与真机同理：`g2` 此刻只被 clip 到**词岛轮廓**（见上面 `g2.clip = shape`），
                    // 故环是完整的（不会被自己裁掉一半），同时也不会戳出词岛。
                    //
                    // ⚠️ `drawVinylRing` 拿的是**内容**形状，它把环画在该形状**之外**
                    //    ⇒ 环的外缘 = `content + 2×ring` = **outer** ✓（这正是"外径不变"）。
                    //
                    // ⚠️ 形状的平移量用 `roundToInt()` —— 与 [drawMaskedCover] 内部
                    //    把封面位图贴到 `x.roundToInt()` 的落点**对齐**，否则环与封面
                    //    会差半个像素（在圆周上就是一圈毛边）。
                    drawVinylRing(
                        g2,
                        coverMaskShape(
                            snapshot.coverShape, snapshot.shapeType, content,
                            cx.roundToInt().toFloat(), cy.roundToInt().toFloat(),
                        ),
                        ring,
                    )
                    // ⚠️ 槽位宽度 ≡ **外径**（真机 `coverSlotWidth` 的新语义）。
                    //    ⚠️ 这里**不能**再 `+ 2×ring` —— 那是旧几何的写法，
                    //    留着它预览的歌词起点会比真机**偏右约 35px**。
                    textLeft = coverTextLeft(inset, outer, snapshot.coverVinyl)
                }
            }

            // ── ③ 频谱（静态柱，只为让"频谱色"看得见）────────────────────────
            //
            // ⚠️ 2026-09-20 实测修正（把预览渲染成 PNG 亲眼看出来的）：
            //    ① 频谱带**压暗到 55%** —— 它本来是**背景**层，全不透明时会把译文行**盖住**，
            //       变成"读不清字"的预览（那正是取色器最不该有的毛病）；
            //    ② 带高 22 → **16px**，且整体上移，给两行歌词让出垂直空间。
            // ⚠️ 这里用**字面量** `"RIGHT"` 而不是真机的 `SPECTRUM_ANCHOR_RIGHT`：
            //   那个常量住在 `IslandPanel` 的 **private companion** 里（预览访问不到）。
            //   ★ 与 `PreviewSnapshot` 里 `str("spectrum.anchor", "BOTTOM")` 同一处理
            //   （**已有先例**）；字面量必须与注册表的 wire 值**逐字一致**。
            //   ★ 宽度用的 `RIGHT_BAR_COUNT` 则是**公共常量**（`render/SpectrumGeometry.kt`）
            //   —— 那一个**不写字面量**，否则"几根柱"就会两边各写一份。
            //
            // ★★ 2026-10-02：`rightBand` / `specX` / `specW` 在**本处**算好（不放进下面的
            //   `if (spectrumEnabled)` 块里）—— 因为 ④ 段的**歌词区宽度也要用它们**让位窄条。
            // ★★ 2026-10-03（⑱ 时钟 · ①b）：与真机**同一判据**（`rightSlotOccupied`，
            //   内含迁移）—— ★ 原来这里直接看 `spectrumAnchor == "RIGHT"`，
            //   而真机也已改成同一函数 ⇒ **两侧不可能漂移**（这正是 ⓪j 守的那类）。
            val rightBand = dev.cdl.render.rightSlotOccupied(
                snapshot.rightSlotComponent,
                snapshot.spectrumAnchor,
            )
            // ★★★ 2026-10-04（**静默失效修复**）：**走纯函数**，不再写死 `RIGHT_SLOT_WIDTH_PX`。
            //
            // ## 原写法为什么是缺陷（与真机**同一处**，两边都写死了 56）
            //
            // `RADIAL` 档下插槽宽 = **词岛高**（用户拍板「自适应」）⇒ 写死 56 会让
            // ★ **预览的插槽宽**与**真机的**不一致（真机已改走纯函数）——
            // 而 ⓪j 同源守卫盯的正是"真机变、预览不变"这一类。
            //
            // ★ 修法 = **两侧共用同一个纯函数** `rightSlotWidthPx(occupant, height)`。
            val specOccupant = dev.cdl.render.rightSlotOccupant(
                snapshot.rightSlotComponent,
                snapshot.spectrumAnchor,
            )
            val specBandW = dev.cdl.render.rightSlotWidthPx(
                specOccupant,
                // ⚠️ 用**预览自己的岛体高** `islandH`（与真机的 `bg.islandHeight` 是同一个语义量）。
                islandH,
            )
            // ★★★ 2026-10-05 **BUG-156 修复（预览侧同源）**：**拆成两套区域** ——
            //   · `slotX` / `slotW` ⇒ **插槽区**（**时钟**用）—— 判据 = **插槽是否被占用**（`rightBand`）✓
            //   · 频谱 ⇒ ★ **2026-10-10（BUG-162）起不再用一对"预合并"的 `specX` / `specW`** ——
            //     ★ 频谱现在有**两个目标**（主带 / 插槽），区域**各自现算**（见 ③ 段）✓
            //   ⚠️ 原来**只有一对、两边共用** ⇒ 用户「插槽 = 时钟 + 频谱贴底边」时，
            //     `rightBand = true` ⇒ **频谱被画进插槽**（"抢过去"）✗
            //   ★ 与真机 `slotArea*` / 频谱两目标 **逐条同源** ✓
            val slotX = if (rightBand) {
                // ★★★ 2026-10-12（**BUG-175**）：★ 右内缩 `4f` → **`COVER_LEADING_INSET`(14)** ✓
                //
                // ★ 用户原话：「**我感觉右侧三型频谱的绘制位置有些偏差（即到词岛边缘的距离
                //   不是等距的），同封面位置不完全对称**」
                // ★★ 左端封面内缩 = `COVER_LEADING_INSET`(14)；
                //   而预览侧插槽的右内缩只有 **4** ⇒ ★ **左右不等距** ✗
                // ★ 真机侧：时钟走 `clockBoxLeftPx(slotLeft, LEADING_INSET, …)`（内缩 14）·
                //   五条小频谱走 `baseInset = HORIZONTAL_INSET`（内缩 14）✓
                // ⇒ ★★ 预览侧改用**同一个常量** ⇒ 时钟 / 五条 / 极坐标两档**四者一起**与真机对齐 ✓
                (inset + islandW - COVER_LEADING_INSET - specBandW)
                    .coerceAtLeast(inset + COVER_LEADING_INSET)
            } else {
                inset + 4f
            }
            val slotW = if (rightBand) {
                (inset + islandW - 4f - slotX).coerceAtLeast(1f)
            } else {
                islandW - 8f
            }
            // ★★ 2026-10-03（⑱ 时钟 · **⑤b**）：**与真机同源** —— 插槽被时钟占用 ⇒ 画时钟、不画频谱。
            //   ★ 几何**完全同一套函数**（`ClockGlyphs.fitDigitWidth` / `digitHeightPx` /
            //     `textPolygons`）⇒ "预览与真机不一致"在**结构上不可能**
            //     （这正是 BUG-149 那类缺口的根治办法）。
            //   ⚠️ 预览用**固定示例时刻 12:34**（真机是"现在"）—— ★ 但**档位**（24/12 制 **与字形风格**）
            //      读**同一个键** ⇒ 设置页切档**立刻可见**（这才是预览该保证的那一层）。
            //   ★★ 2026-10-03（⑥ 字形风格）：调用点从 `SevenSegment` **换成门面 `ClockGlyphs`** ——
            //      真机与预览**都只调门面** ⇒ 新增风格时**两处都不用改**。
            //   ⚠️ 本块**不得**再直接引用 `SevenSegment`（那会让点阵/像素风在预览侧静默退回七段）。
            // ★★★ 2026-10-05 **BUG-153 修复（预览侧同源）**：真机侧我把"插槽归谁"错当成
            //   "频谱画不画"（见 `IslandWindow.drawBackgroundSpectrum` 的 KDoc）——
            //   ★ 预览侧**我加了同样的错**（`if (slotClock) 画时钟 else if (spectrumEnabled) 画频谱`）。
            //   ⇒ 一并按**同一条判据**修：**插槽归谁只影响"频谱档位本来就是右端插槽"的情形**。
            //   ★ 两处判据**必须同源**（这正是 `check_preview_parity` 那条纪律要的）。
            val slotClock = snapshot.rightSlotComponent == dev.cdl.render.RIGHT_SLOT_CLOCK
            // ★★★ 2026-10-05 **BUG-158 修复**（★ 审计抓到的**预览/真机分叉**）：
            //   **时钟该不该画只看"插槽里放的是不是时钟"**（`slotClock`）——
            //   ★ 原来还额外要求 `slotTakesSpectrumBand`（= `anchor == RIGHT` && …），
            //     而**真机不看 anchor**（`IslandWindow` 只要 `slotOccupant == CLOCK` 就画）✗
            //   ⇒ ★ 后果：设置页把插槽设成「时钟」+ 频谱贴底边（**默认**）⇒ **预览不显示时钟**，
            //     而真机显示 ⇒ ★★ **两侧分叉**（正是 `check_preview_parity` 要防的那类）✓
            if (slotClock) {
                val sampleMs = 45_240_000L          // 1970-01-01 12:34 UTC（与探针用例同值，好对照）
                val txt = dev.cdl.render.ClockText.format(
                    sampleMs, java.time.ZoneOffset.UTC, snapshot.clockHourFormat,
                )
                val style = dev.cdl.render.ClockGlyphs.normalizeStyle(snapshot.clockStyle)
                val dw = dev.cdl.render.ClockGlyphs.fitDigitWidth(style, txt, slotW)
                if (dw != null) {
                    val dh = dev.cdl.render.ClockGlyphs.digitHeightPx(style, dw)
                    // ★★★ 2026-10-05 **BUG-155 修复（预览侧同源）**：与真机**同一条修法** ——
                    //   在插槽里**水平居中**（原来把 `specX` 当左边缘 ⇒ 左对齐 ⇒ 偏左）。
                    //   ★ 两处判据同源（`ClockGlyphs.totalWidthRatio`）⇒ 不可能漂移 ✓
                    val textW = dw * dev.cdl.render.ClockGlyphs.totalWidthRatio(style, txt)
                    // ★★★ 2026-10-07 **BUG-160 修复（预览侧同源）**：与真机**同一个纯函数** ——
                    //   把时钟盒**整体左移 `COVER_LEADING_INSET`**（= 镜像左端封面）。
                    //   ★ 为什么必须做：`fitDigitWidth` 让 `textW == slotW` ⇒ 上面那句"居中"
                    //     的 `(slotW − textW)/2` **恒为 0** ⇒ 时钟**贴着插槽右缘**（真机实测 **0 px**）。
                    //   ★ 下限 = 预览的**歌词区右边界**（与下方 `previewSideInset` **同一算式**，
                    //     此处提前算一次 ⇒ 保证时钟**永不与歌词重叠**）。
                    //   ⚠️ 复用外层的 `textLeft`（此刻尚未被下面的局部变量遮蔽）。
                    val clockBoxLeft = dev.cdl.render.clockBoxLeftPx(
                        slotLeft = slotX,
                        insetPx = COVER_LEADING_INSET,
                        minLeftPx = inset + islandW - dev.cdl.render.lyricSideInsetPx(
                            textLeft - inset,
                            if (rightBand) specBandW else 0f,
                        ),
                    )
                    val textLeft = clockBoxLeft + (slotW - textW) / 2f
                    // ★★★ 2026-10-08 **U4 修复**（用户报「**右插槽显示时间后，预览词岛中的显示时间
                    //   位置较桌面词岛有较大偏差。偏右下角。**」）
                    //
                    // ## 根因：预览侧的**纵向**表达式是从「贴底频谱」抄来的，不是时钟的
                    //
                    // **原状**（本行此前）：
                    //     `inset + islandH - 20f + (16f - dh) / 2f`
                    // ★ 它是**贴底 16 px 频谱条**的几何（"离底边 20 px 起的 16 px 高条带里居中"）
                    //   —— 从上面的频谱分支照搬过来的，与"时钟"毫无关系。
                    //
                    // **真机侧**（`IslandWindow` 里绘制插槽时钟的那一段）：
                    //     `layout.lyricsTop + (layout.lyricsHeight - dh) / 2f`
                    // ⇒ **在整个歌词区（≈ 岛体内容区）高度内居中**。
                    //
                    // ## 两个偏差叠加 ⇒ 用户看到的「偏右下角」
                    //
                    // ① **纵向压到底部**：`islandH - 20` 把基准拉到**底部**，而真机在**中部**；
                    // ② ★★ **`(16f − dh)/2` 是负数**：时钟高 `dh` 通常 20~40 px（远大于 16），
                    //    ⇒ 该项把 y **再往下推** `(dh − 16)/2` px。
                    //
                    // ★ 为什么"偏右"也一并解释了：水平方向本文件已按 BUG-160 左移
                    //   （与真机同源），故**只剩纵向分叉** —— 而这正与用户描述的
                    //   "偏右下角"吻合（右下 = 纵向到底 + 水平已右提到边界）。
                    //
                    // ## 修法：与真机**同一条口径** —— 在岛体内容区里纵向居中
                    //
                    // 预览侧没有 `lyricsTop/lyricsHeight` 这组量（那是真机 `Layout` 的字段），
                    // 但预览的**等效物**就是岛体内容区：上边 = `inset`、高 = `islandH`
                    // （两者都是本文件既有变量，见 `:182-184`）。故：
                    //     `inset + (islandH - dh) / 2f`
                    // ⇒ 与真机 `lyricsTop + (lyricsHeight - dh)/2` **逐项对应**（`lyricsTop ≈ inset`
                    //   、`lyricsHeight ≈ islandH`），且**不再含任何"频谱 16 px 条带"的残留**。
                    //
                    // ★★★ 2026-10-10（审计 v4 · **A-1**）**结论：不画标记，改文案**（与真机同一条决定）。
                    //   ★ 实现时才暴露前提问题：**字形渲染器只支持 `0-9` 与 `:`**
                    //     ⇒ 字母画不出来（实测 `totalWidthRatio("PM") == 0`）✗
                    //   ★ 用户拍板「改文案、去掉承诺」⇒ ★ 本处**逐像素不变** ✓
                    //   ★ 详情与论证见真机 `IslandWindow` 的同名注释块。
                    //
                    // ⚠️ 一处刻意的**不改**：水平仍用 `textLeft`（已含 `clockBoxLeftPx` 的左移）
                    //   —— 那是 BUG-160 的成果，改它会让**真正的**右贴边问题复发。
                    val clockTop = inset + (islandH - dh) / 2f
                    val polys = dev.cdl.render.ClockGlyphs.textPolygons(
                        style, txt, textLeft, clockTop, dw, dh,
                    )
                    val oldPaint = g2.paint
                    // ★ 与真机同口径：用**已唱歌词色**（`sungColor`）⇒ 两处同族。
                    g2.paint = java.awt.Color(snapshot.sungColor or 0xFF000000.toInt(), true)
                    for (poly in polys) {
                        val path = java.awt.geom.Path2D.Float()
                        path.moveTo(poly[0], poly[1])
                        for (i in 2 until poly.size step 2) path.lineTo(poly[i], poly[i + 1])
                        path.closePath()
                        g2.fill(path)
                    }
                    g2.paint = oldPaint
                }
            }
            // ★★★ 2026-10-10（**BUG-162**）：频谱**拆成两个目标、各画一次** ——
            //   ★★ 用户拍板（原话「**要**」）：★ 插槽选了「小频谱 / 放射」时，
            //      **主带那条频谱【还要】** ✓
            //
            // ## ★★ 改前这里还有**第二个**问题：`else if`
            //
            // ★ 原来是 `if (occupant == CLOCK) {…时钟…} else if (shouldDraw…) {…频谱…}` ⇒
            //   ★★ **插槽放时钟时，`else` 分支整个不执行** ⇒ **主带频谱也不画** ✗
            //   ★ 而**真机侧不会**（它两条独立）⇒ ★★ **预览与真机不同源** ✗
            //   （★ 与 BUG-153「插槽放时钟 ⇒ 整个频谱全灭」是**同一形态**，
            //    真机那次修好了，★ **预览侧漏改**）
            // ⇒ ★ 本批一并改成**两条独立的 `if`** ✓
            //
            // ⚠️ 判据**必须**用 `SpectrumGeometry` 里的两个纯函数（真机与预览共用）
            //   —— ★ 别在本文件里就地写 `if`，那正是"两处各写一遍迟早漂移" ✓
            //
            // ## 两个目标的区域（★ 与真机逐条同源）
            //
            // | 目标 | 区域 | `RADIAL` 特例 |
            // |---|---|---|
            // | ① **主带** | `(inset + 4, inset + islandH − 20, islandW − 8, 16)` | — |
            // | ② **右端插槽** | `(slotX, inset + islandH − 20, specBandW, 16)` | 极坐标画布 = **插槽方形**（边长 `specBandW`，垂直居中） |
            //
            // ① **主带**（歌词区 / 贴边）—— ★ 与"插槽放了什么"**无关** ✓
            if (dev.cdl.render.shouldDrawSpectrumBand(
                    snapshot.spectrumAnchor,
                    snapshot.spectrumEnabled,
                )
            ) {
                drawStaticSpectrum(
                    g2, inset + 4f, inset + islandH - 20f, islandW - 8f, 16f,
                    symmetric = snapshot.spectrumSymmetric,
                    coverPalette = demoCoverPalette(),
                    // ★★★ 2026-10-15（§3.75）：主带走**主频谱自己的**档位与基色 ✓
                    colorMode = snapshot.spectrumColorMode,
                    colorRgb = spectrum(),
                    // ★★★ 2026-10-16（§3.77-D）：★ 主带 = **min(用户值, 55%)** ——
                    //   55% 那条护栏**收窄到本调用点**（只有主带压在歌词上）✓
                    //   ⚠️ 55% 是**预览本地策略**（真机没有它）⇒ 这里**如实登记**：
                    //     用户把不透明度拉到 100% 时，预览的主带仍显示 55%
                    //     （换"歌词可读"），而插槽两档与真机**逐位一致** ✓
                    frameOpacity = minOf(
                        dev.cdl.window.spectrumAlphas(
                            snapshot.spectrumOpacityPct, 0, false,
                        ).frameOpacity,
                        PREVIEW_MAIN_BAND_MAX_FRAME_OPACITY,
                    ),
                )
            }
            // ② **右端插槽** —— ★ 由 occupant 决定（★ 总开关不管）✓
            // ★★★ 2026-10-11（**U13 · 插槽小频谱 = 「侧边条」呈现**）──────────────
            //
            // ## 与真机**同一条规则**（⓪j 的精神：预览与真机同病同治）
            //
            // 真机插槽档（`drawBackgroundSpectrum` 的 U13 注释）：**五根 · 实时频谱 ·
            // 以插槽纵向中线为轴上下对称 · 满幅（不受 height_pct 影响）· 实心**。
            // ⇒ 预览逐条对齐：
            //   · 矩形纵向 = **歌词区**（`inset .. inset+islandH`，与真机
            //     `areaTop=lyricsTop / areaHeight=lyricsHeight` **逐项对应**——
            //     见 L627 那条"预览侧没有 lyricsTop/lyricsHeight"的换算注释）；
            //   · `symmetric = true`（**强制**，不看设置）；
            //   · 水平 = **28 px 可用带**（插槽 56 − 两侧内缩各 14，与真机
            //     `spectrumBandInsetForRadii(baseInset=14)` 的常态结果一致）
            //     ⇒ `slot = 28/5 = 5.6`，与真机**逐位相同** ✓
            //
            // ⚠️ 改前：`(slotX, inset+islandH−20, specBandW, 16)` + `symmetric=设置值`
            //   ⇒ 16 px 高的"贴底条" = **主频谱的缩小版**（与真机同病）✗
            if (dev.cdl.render.shouldDrawSpectrumInSlot(specOccupant)) {
                // ★★ 2026-10-11（U14）：极坐标两档（RADIAL + RING）判据收口
                //   `isPolarSlotOccupant`（与真机**同一出口**）✓
                if (dev.cdl.render.isPolarSlotOccupant(specOccupant)) {
                    // ★★★ 2026-10-12（**BUG-169b**）：★ 极坐标两档也**钳到封面盒子** ✓
                    //   ★ 用户原话：「**对其他两型插槽频谱也做钳制。**」
                    //   ★ 改前用 `specBandW`（**可用带宽**）当边长 ⇒ 与真机的"封面盒子"**不一致** ✗
                    //   ★ 现与真机同源：边长 = 封面外径（含黑胶环），并**垂直居中** ✓
                    val coverOuter = coverSideFor(islandH, snapshot.coverScaleWithFont)
                    val side = coverOuter
                    // ★★★ 2026-10-12（**BUG-175**）：★ **横向也要镜像**（与封面同一个内缩）✓
                    //
                    // ★ 用户原话：「**我感觉右侧三型频谱的绘制位置有些偏差（即到词岛边缘的距离
                    //   不是等距的），同封面位置不完全对称**」
                    // ★★ 真机侧 = `polarSlotArea(..., insetPx = LEADING_INSET)` ⇒
                    //   ★ `x = 岛体右 − 边长 − LEADING_INSET` ✓
                    // ★ 预览侧原为 `slotX`（其右内缩是 **4**）⇒ 与封面（**14**）不一致 ✗
                    //   ⇒ ★ 现用**同一个常量** `COVER_LEADING_INSET` ⇒ 两侧恒等 ✓
                    val slotRight = inset + islandW
                    // ★★★ 2026-10-17（**BUG-221**）：★ 极坐标插槽**裁到岛体真实轮廟**（兜底）✓
                    //
                    // ## 用户报障（逐字，两次）
                    //
                    // > 「**封面脱出和环形频谱的问题还没修好。**」
                    // > 「**你还敢质疑我？我不是瞎子，和实机偏差这么明显，又不是差几个 px**」
                    //
                    // ## 为什么要裁（几何上的**结构性**风险，与 BUG-222 互补）
                    //
                    // ★ `side` = 极坐标插槽的外接方形边长 ⇒ 环半径 = `side/2`；
                    //   环心 y = 岛体**水平中线** ⇒ 环下缘 y = cy + side/2。
                    // ★★ 而 `PILL` 档右端是**半圆**（半径 = `islandH/2`）——
                    //   在环下缘所在的高度上，端部边界**已经向内收** ⇒
                    //   环的右下部**天然落在轮廓之外**，除非半径/位置恰好配合 ✗
                    // ★ 插槽只扣了 `COVER_LEADING_INSET = 14px` 内缩，
                    //   端部半圆半径 = `islandH/2` ⇒ **14px 远不够** ✗
                    // ⇒ 几何一旦漂移（如 BUG-222 那种"柱面外移"），柱会被端部半圆**切平**，
                    //   观感即用户说的"环戳出岛体" ✓（这与"真的画出岛体之外"在肉眼下难以分辨）
                    //
                    // ## 诚实登记：外层**本就有**一道全局 clip（`paintComponent` 里
                    //   `val oldClip = g2.clip; g2.clip = shape`，覆盖 ②~④ 全段）⇒
                    //   **实测**（2026-10-17 反向变异）：单独摘掉本处 clip **不会**脱出。
                    //   ★ 那为什么还留着本处：① 意图**就地可见**（读者不必上翻 400 行）；
                    //     ② 外层 clip 的**作用域将来可能被收窄**（它是为封面段加的），
                    //     本处是**针对性兜底**；③ 与真机同源语义（真机窗口外部本不可见）。
                    //
                    // ## 为什么用 `clip` 而不是"把插槽再往左移"
                    //
                    // ★ 内移会让环与真机**不再同源**（用户要求「**向真机对齐**」）✗
                    // ★ 而 `clip` 只裁掉"本来就不该出现在岛体之外"的那部分 ⇒
                    //   两侧语义一致（真机窗口外部本就不可见）✓
                    // ★★ 真正的治本 = **BUG-222**：柱面收口到共用几何函数后，
                    //   环本就在环带 `[innerR, outerR]` 内、离岛体边缘 ≥ 14px ⇒ 无需裁剪 ✓
                    //
                    // ⚠️ `clip` 是**与已有 clip 取交**（不是替换）⇒ 必须在 `finally` 里写回✓
                    val savedClipSlot = g2.clip
                    g2.clip = shape
                    try {
                    drawStaticSpectrum(
                        g2, slotRight - COVER_LEADING_INSET - side,
                        inset + (islandH - side) / 2f, side, side,
                        // ★★★ 2026-10-12（**BUG-177**）：★ **环状档开"内外双峰"**（与真机同判据）✓
                        //   ★ 用户原话：「**环状内侧仍不会跳动（效仿上下双峰，做内外双峰）。**」
                        //   ★ 环状 ⇒ `symmetric = true`（条跨中半径，内 / 外各长一半）✓
                        //   ⚠️ 放射档**必须保持非对称**（内半径 0 ⇒ 对称会长到圆心之外）✗
                        symmetric = specOccupant == dev.cdl.render.RIGHT_SLOT_RING,
                        // ★★★ 2026-10-12（**BUG-176**）：★ 极坐标档**恒用固定柱距**（与真机同判据）✓
                        //   ★ 用户原话：「**放射状频谱看不出频谱条间距，建议固定条数和间距，
                        //     同时条间不重叠。**」
                        //   ★ 真机 = `slotBarPitchPx(isPolar = true, …)` 现在**恒回落**固定默认 ✓
                        //   ⚠️ 不传 ⇒ 跟随 `snapshot.spectrumBarPitchTenthPx`（用户设的 0 = 实心）
                        //     ⇒ ★ **预览无缝、真机有缝** ⇒ 预览撒谎 ✗
                        forcePitchPx = dev.cdl.render.slotSideBarPitchPx(),
                        coverPalette = demoCoverPalette(),
                        // ★★★ 2026-10-15（§3.75）：插槽走**插槽自己的**档位与基色
                        //   （默认 `follow` ⇒ 与主频谱逐位相同 ⇒ 零回归）✓
                        colorMode = slotSpectrumColorMode(),
                        colorRgb = slotSpectrum(),
                        // ★★★ 2026-10-16（§3.77-D）：★ 插槽 = **用户值**（与真机逐位一致）——
                        //   环 / 放射与歌词**并排**、不压在上面 ⇒ 没有理由压暗 ✓
                        frameOpacity = slotFrameOpacity(),
                    )
                    } finally {
                        g2.clip = savedClipSlot
                    }
                } else {
                    val sideUsable = (specBandW - 28f).coerceAtLeast(8f)
                    // ★★★ 2026-10-12（**BUG-169**）：★ 垂直范围改 **封面那个盒子**（与真机同源）✓
                    //
                    // ★ 用户原话：「**我们这边的上下跳动范围限定在封面大小内，带黑环时包括黑环**」
                    // ★★ 真机侧 = `bg.coverOuterSizePx`（外径含黑胶环）+ `bg.coverTopFor`（居中）
                    // ⇒ ★ 预览侧用**同一语义的出口** `coverSideFor`（本文件既有惯例：
                    //    `coverSideFor` ↔ 真机 `leadingCoverSize`，见 §1881 的说明）✓
                    // ⚠️ 改前传的是 `islandH`（**整个内容区高**）⇒ 与真机"跳得太高"**同病** ✗
                    val coverOuter = coverSideFor(islandH, snapshot.coverScaleWithFont)
                    val coverTop = inset + (islandH - coverOuter) / 2f
                    drawStaticSpectrum(
                        g2, slotX + 14f, coverTop, sideUsable, coverOuter,
                        symmetric = true,
                        forceBars = dev.cdl.render.slotSideBarCountFor(
                            snapshot.rightSlotSpectrumBars,
                        ),
                        // ★ BUG-166 补：5 根**恒用固定柱距**（与真机同判据）——
                        //   否则用户设"实心"时预览会把五根并成一块（而真机不会）✗
                        forcePitchPx = dev.cdl.render.slotSideBarPitchPx(),
                        coverPalette = demoCoverPalette(),
                        // ★★★ 2026-10-15（§3.75）：插槽走**插槽自己的**档位与基色 ✓
                        colorMode = slotSpectrumColorMode(),
                        colorRgb = slotSpectrum(),
                        // ★★★ 2026-10-16（§3.77-D）：★ 插槽 = **用户值**（与真机逐位一致）——
                        //   侧边条在**插槽带**里、与歌词并排 ⇒ 同样没有理由压暗 ✓
                        frameOpacity = slotFrameOpacity(),
                    )
                }
            }

            // ── ④ 两行歌词 ────────────────────────────────────────────────────
            // ★★ 2026-10-02（`spectrum.anchor = RIGHT`）：**本档要让出右端窄条** ——
            //   与真机**同一条规则**（频谱画在文字之下，若不让位 ⇒ 歌词会压住窄条）。
            //   ⚠️ `specW` / `specX` 在 ③ 段已算好（`rightBand` 也在那里判过）——
            //     本处复用 `specBandW`，**不重算一遍**（两处各写一遍迟早漂移）。
            // ★★★ 2026-10-04：**两侧统一让位** ⇒ 歌词在岛体内居中（与真机**同一条规则**）。
            //
            // ## 为什么（用户逐字）
            //
            // > 「**注意了，如果右侧小组件插槽加宽，词岛内需要相应调整以保证歌词行在词岛内居中。**」
            //
            // ★ 左让位（封面侧）与右让位（插槽侧）**不等**时，歌词区本身在岛体里**不居中**
            // ⇒ 文本中心偏左。★ 修法是**两侧取较大者**（`lyricSideInsetPx`）——
            // 它同时满足「居中」与「不重叠」。
            // ⚠️ 把歌词区整体右移是**错解**：那只是把"右边压插槽"换成"左边压封面"。
            // ★★★ 2026-10-04（`P-r` **对齐真机**）：右让位 = **纯插槽宽**。
            //
            // ## 原写法为什么多算了 10 px
            //
            // 原来是 `(inset + islandW) - (specX - 6f)` —— 其中
            // `specX = inset + islandW - 4f - specBandW` ⇒ 展开后 = `specBandW + 4 + 6`。
            // 而**真机**用的是 `rightBandWidth`（**纯插槽宽**，无额外间隙）
            // ⇒ ★ 预览多算 **10 px**（4 px 边距 + 6 px 间隙）。
            //
            // ★ 那 4 / 6 是**预览自己的排版留白**（为了让插槽不贴岛体边缘、与歌词有缝），
            //   真机没有对应物 ⇒ ★ 让位量必须按**真机的口径**算，否则 `max(左,右)`
            //   的交叉点与真机不同（= `P-r` 要修的那个"不一致"）。
            // ⚠️ **`specX` 不动**（仍留 4 px 边距）—— 那只影响**插槽画在哪**，
            //   不影响**歌词区让多少**。两者分开，才既对齐又不改插槽位置。
            val previewRightInset = if (rightBand) specBandW else 0f
            val previewSideInset = dev.cdl.render.lyricSideInsetPx(
                textLeft - inset,
                previewRightInset,
            )
            textLeft = inset + previewSideInset
            val textRight = if (rightBand) {
                (inset + islandW - previewSideInset).coerceAtLeast(textLeft + 40f)
            } else {
                inset + islandW - 10f
            }
            val avail = max(40f, textRight - textLeft)
            // ★★ 2026-09-20：**字号跟随面板尺寸缩放**（用户要求）。
            //
            // 用户原话：「我不是说了**示例歌词要能够适应窗口大小**吗？」
            //
            // ## 根因
            //
            // 岛体几何（`islandW`/`islandH`）**已经**跟随面板宽高；
            // 但字号此前写死用 `snapshot.mainFontSize` —— 面板变窄/变宽时文字不变，
            // 窄面板里文字就会**溢出或被裁**，宽面板里又显得太小。
            //
            // ## 缩放模型
            //
            // 以 [PREVIEW_REFERENCE_W]（设计宽度 460）为基准，按**岛体实际宽度**等比缩放，
            // 并夹在 `[0.5, 1.6]` —— 夹上下限是防止极端窗口尺寸把字缩成不可读或撑爆。
            //
            // ⚠️ 只缩放**字号**，不改 `avail` 与基线比例 ——
            //    那两者本来就由面板尺寸推导，已经自适应。
            val fontScale = (islandW / PREVIEW_REFERENCE_W).coerceIn(0.5f, 1.6f)
            // ★★ 2026-09-21：**再按"这一行文字的实际宽度"收敛字号** ——
            //    修「自绘取色窗口不能完整显示示例歌词，末端像被截断」。
            //
            // ## 根因（不是"宽度不够"，而是字号没跟着文本收敛）
            //
            // `fontScale` 只按**面板宽度**缩放（以 460 为基准）。而示例文本是**固定的一句**
            // （`SAMPLE_MAIN` / `SAMPLE_TRANSLATION`），其自然宽度**不一定**正好等于 `avail` ——
            // 一旦超出，就被下面的 `g2.clip = shape`（岛体圆角矩形）**裁掉末端**。
            //
            // ## 修法
            //
            // 先量该行在基准字号下的自然宽度，超出可用宽度就**等比缩小字号**
            // —— 而不是裁掉、也不加省略号：用户要看的是**整句长什么样**，
            // 尤其歌词这种"整句才成句"的内容，掐头去尾就没有意义。
            // 下限 `0.45` 防止极端窄面板把字缩到不可读（那时宁可小，也不要缺字）。
            val mainFont = fitToWidth(
                PreviewSnapshot.SAMPLE_MAIN,
                resolveFont(snapshot.mainFontSize * fontScale),
                avail,
                g2.fontRenderContext,
            )
            val subFont = fitToWidth(
                PreviewSnapshot.SAMPLE_TRANSLATION,
                resolveFont(
                    max(
                        9f,
                        snapshot.mainFontSize * snapshot.translationScale * fontScale,
                    ),
                ),
                avail,
                g2.fontRenderContext,
            )

            // 基线比例 0.42/0.76 → **0.38/0.68**：原值让译文落在频谱带上（实测截图可见）。
            val mainBaseline = inset + islandH * 0.38f
            val subBaseline = inset + islandH * 0.68f
            drawKaraokeLine(
                g2, PreviewSnapshot.SAMPLE_MAIN, mainFont, textLeft, mainBaseline, avail,
                mainOutline(),
                // ★ 2026-10-10（B-2）：档位进绘制（原先这个字段**带了没人读**）。
                mode = snapshot.karaokeMode,
            )
            // ★ 2026-09-21（BUG-119）：译文行**也要"已唱 / 未唱"两段色** ——
            //   真机里译文未唱部分按 `UNSUNG_TRANSLATION_DIM`（0.62）暗化，
            //   而预览此前整行单色。用户原话：「副歌词**没有高亮状态**。」
            drawKaraokeLine(
                g2, PreviewSnapshot.SAMPLE_TRANSLATION, subFont, textLeft, subBaseline, avail,
                translationOutline(),
                // ★ 2026-10-10（B-2）：与主歌词**同一个档位**（同一个键）。
                mode = snapshot.karaokeMode,
                sungColor = translation(),
                unsungColor = translationUnsung(),
            )
        } finally {
            g2.clip = oldClip
        }

        // ── ⑤ 边框 ───────────────────────────────────────────────────────────
        //
        // ★★★ 2026-10-10（审计 v4 · **B-1**）：**补上 `FADE_OUT` 档**。
        //
        // ## 原状（本项目的第 5 次"预览同源缺口"）
        //
        // 恒 `BasicStroke(1.5f) + draw(shape)` ⇒ ★ **只画实线** ⇒
        // 用户把 `island.border.mode` 切到「向外渐变」时 **预览纹丝不动** ✗
        // （★ 快照里 `borderMode` 字段**带了**、**没人读** ⇒ `check_preview_parity` ⓪j **测不到**：
        //   它的判据是"两边共同调用的函数的命名实参集合"，而这里**根本没有共同调用的函数**）
        //
        // ## 修法：**调真机同一个纯函数**（不做镜像实现）
        //
        // ★ 层参数来自顶层纯函数 [dev.cdl.settings.borderFadeLayers]（**唯一出口**，
        //   真机 `drawIslandBorder` 也调它）⇒ ★ "层数 / 各层透明度 / 各层线宽"逐项同源 ✓
        // ★ 档位值走**同一个常量** [dev.cdl.settings.BORDER_MODE_FADE_OUT]（同批新增）✓
        //
        // ⚠️ **如实登记的已知差异**（不假装逐像素一致）：
        //   · 预览的线宽恒 `1.5f`；真机取 `island.border.width` 的换算值；
        //   · 真机把环整体平移 `strokeWidth / 2`（半线宽内缩），预览直接画在岛体轮廓上。
        //   ⇒ ★ 两处**层参数同源**、**几何口径有已知差异** —— 但"切档预览有反应"这一层已对齐 ✓
        if (snapshot.borderEnabled) {
            val bc = Color(border() or 0xFF000000.toInt(), true)
            val previewStrokePx = 1.5f
            if (snapshot.borderMode == dev.cdl.settings.BORDER_MODE_FADE_OUT) {
                for (layer in dev.cdl.settings.borderFadeLayers(previewStrokePx)) {
                    if (layer.alpha <= 0) continue
                    // ★ 环 = 岛体形状按 `layer.insetPx` **向内缩**（D1=B：`insetPx` 恒 ≥ 0）
                    //   —— 与真机同一条口径（真机：`width − strokeWidth − inset×2`）。
                    val ring = previewIslandShapeCore(
                        snapshot.shapeType, snapshot.cornerRadius,
                        inset + layer.insetPx,
                        (islandW - layer.insetPx * 2f).coerceAtLeast(1f),
                        (islandH - layer.insetPx * 2f).coerceAtLeast(1f),
                        snapshot.cornerStyle, snapshot.cornerN,
                    )
                    g2.stroke = BasicStroke(layer.widthPx)
                    g2.color = Color(bc.red, bc.green, bc.blue, layer.alpha)
                    g2.draw(ring)
                }
            } else if (snapshot.borderMode == dev.cdl.settings.BORDER_MODE_EDGE_DISSOLVE) {
                // ★★★ 2026-10-13（**BUG-187**）：**本档不在这里做事** ——
                //   溶边掩版已移到「**背景之后、内容之前**」应用（见本方法前段）✓
                //   ★ 与真机 `IslandWindow.drawIslandBorder` 的处置**完全对称** ✓
                //   ★ 保留本分支只为**显式说明**「本档已处理，只是不在这里」✓
                //
                // ★★★ 2026-10-14（**BUG-197**）：**本分支不许有任何绘制调用** ——
                //   之前这里**漏删**了 `SOLID` 档那三行（`color` / `stroke` / `draw(shape)`），
                //   于是注释写着"本档不做事"、代码却**接着往下画了一条硬描边** ✗
                //   ⇒ ★ 用户原话：「**真实词岛应用的是溶边，而示例词岛用的还是硬边**」+
                //     「**词岛边缘有个方形的黑框框住磁道**」——**同一处**的两个症状 ✓
                //   （硬描边 = 硬边；描边色取自 `island.border.color`，深色时看起来就是"黑框"）
                //
                //   ⚠️ 真机同档分支是**空**的（`IslandWindow.drawIslandBorder` 里只剩注释）——
                //     本档必须**与它逐字对称**，否则就是第 6 次"预览同源缺口"。
                //   ★ 这条对称性由 `tools/check_preview_parity.py` ⓪j 的**检查七**机械保证
                //     （扫描本分支体，出现 `g2.draw(` / `g2.fill(` 即报红）✓
            }
        }
    }

    /**
     * **按可用宽度收敛字号** —— 保证一行文字**完整画得下**（2026-09-21 新增）。
     *
     * ## 为什么需要它
     *
     * 示例预览里的岛体宽是由**面板尺寸**推导的（`islandW`），而示例文本是**固定的一句**。
     * 两者没有天然的匹配关系 —— 面板偏窄时，基准字号下的文本会**超出岛体**，
     * 随后被 `g2.clip = shape` **裁掉末端**，用户看到的就是
     * 「示例歌词末端像被**截断**了一样」。
     *
     * ## 为什么是"缩字号"而不是"裁剪 / 省略号"
     *
     * 预览的职责是让用户判断**颜色搭配**，而"整句字长什么样"本身就是判断的一部分。
     * 裁掉或加省略号都会**改变被判断的对象**；缩字号只改变"看在多大倍率下"，
     * 信息完整性得以保留 —— 歌词这种"整句才成句"的内容尤其如此。
     *
     * ## 与 [PREVIEW_REFERENCE_W] 缩放的分工
     *
     * 那个负责"面板越大字越大"（**宏观**自适应）；
     * 本函数负责"**这一行**别溢出"（**微观**收敛）。两者串行：先按面板缩放，再按文本收敛。
     *
     * @param minScale 缩放下限。见 [MIN_FIT_SCALE] —— 它**必须低到能容纳注册表的极端字号**
     *   （`main_lyric.font.size` 上界 200），否则"缩字号"根本缩不到位，末端仍会被 `clip`
     *   裁掉 —— 那正是用户报的「示例歌词显示不全」。
     */
    private fun fitToWidth(
        text: String,
        font: Font,
        avail: Float,
        frc: java.awt.font.FontRenderContext,
        minScale: Float = MIN_FIT_SCALE,
    ): Font {
        val scale = fitScale(text, font, avail, frc, minScale)
        // ⚠️ 是 `size2D`（`Font.getSize2D()` 的属性名），不是 `size2d`。
        return if (scale >= 1f) font else font.deriveFont(font.size2D * scale)
    }

    /**
     * 画一行"半唱"的歌词：左半用已唱色、右半用未唱色。
     *
     * 与 `KaraokeTextRenderer` 的做法**有意不同**：那边是逐字时间轴 + 羽化 + clip，
     * 是**播放态**的渲染；这里是**静态色卡**，只需要"两种色并排可见"。
     * 复刻那套只会造出第二份实现（本项目已因"同族两处实现只改一处"吃过多次亏）。
     *
     * @param sungColor 已唱色。默认取主歌词那一套；**译文行传自己的两色**（见调用点）。
     * @param unsungColor 未唱色（默认见上）。
     */
    private fun drawKaraokeLine(
        g2: Graphics2D, text: String, font: Font,
        x: Float, baseline: Float, avail: Float,
        outline: PreviewSnapshot.OutlineSpec?,
        // ★★★ 2026-10-10（审计 v4 · **B-2**）：新增 `mode` —— `main_lyric.karaoke.mode`。
        //   ★ 原状：本函数**恒**按 `drawX + advance × demoProgress` 取分界点
        //     ⇒ ★ 那是 **`SMOOTH`（线性扫过整行）** 的几何 ⇒
        //     用户把档位切到 `PER_CHAR`（**默认**）或 `LINE` 时，**预览纹丝不动** ✗
        //   ★ 快照里 `karaokeMode` 字段**带了、没人读** ⇒ ⓪j `check_preview_parity` **测不到**
        //     （它的判据是"两边**共同调用**的函数的命名实参集合"——
        //      而本函数是预览**私有**的，真机走的是 `KaraokeTextRenderer`，**没有共同调用点**）✗
        mode: String,
        sungColor: Int = sung(),
        unsungColor: Int = unsung(),
    ) {
        val layout = java.awt.font.TextLayout(text, font, g2.fontRenderContext)
        val drawX = alignX(x, avail, layout.advance)
        if (outline != null && outline.widthPx > 0f) {
            val oldStroke = g2.stroke
            g2.color = Color(outline.color or 0xFF000000.toInt(), true)
            // ★ 2026-09-26 修：此前是 `BasicStroke(outline.widthPx)` —— 那一档是
            //   **`JOIN_MITER`**，在锐角处会外伸 `w/2 × miterLimit`（默认 limit = 10）
            //   ⇒ 画出**尖刺**，而真机**没有**（真机走 [dev.cdl.render.karaokeOutlineStroke]
            //   的 `CAP_ROUND` + `JOIN_ROUND`，见 `KaraokeTextRenderer` 的 KDoc 与
            //   `TextAntialiasProbe` §B/§D/§E）。⇒ 用户在预览里看到的字形边缘与真机不同，
            //   属本项目点名反对的「所见非所得」。
            //   修法是**调真机同一个公开函数**（不是在这里重写一遍笔型）。
            g2.stroke = previewOutlineStrokeFor(outline.widthPx)
            g2.draw(layout.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(drawX.toDouble(), baseline.toDouble())))
            g2.stroke = oldStroke
        }
        // ★★★ 2026-10-10（审计 v4 · **B-2**）：分界点**按档位**求（原为恒 `SMOOTH` 的算式）。
        val split = previewKaraokeSplitX(layout, drawX, mode)
        val oldClip = g2.clip
        g2.clip = java.awt.Rectangle(
            oldClip.bounds.intersection(java.awt.Rectangle(drawX.roundToInt() - 4, baseline.roundToInt() - 60, split - drawX.roundToInt() + 4, 90)),
        )
        g2.color = Color(sungColor or 0xFF000000.toInt(), true)
        layout.draw(g2, drawX, baseline)
        g2.clip = java.awt.Rectangle(
            oldClip.bounds.intersection(java.awt.Rectangle(split, baseline.roundToInt() - 60, (drawX + layout.advance).roundToInt() - split + 4, 90)),
        )
        g2.color = Color(unsungColor or 0xFF000000.toInt(), true)
        layout.draw(g2, drawX, baseline)
        g2.clip = oldClip
    }

    /**
     * ★★★ **预览的示例分界点** —— 按 `main_lyric.karaoke.mode` 求（2026-10-10 · 审计 v4 · B-2）。
     *
     * ## 三档的几何（★ 与真机 `KaraokeTextRenderer.render` 的 `smooth` 参数**同语义**）
     *
     * | 档 | 分界点 | 预览表现（`demoProgress = 0.55`） |
     * |---|---|---|
     * | [KARAOKE_MODE_SMOOTH] | `drawX + advance × progress`（**落在字内部**） | 分界点在一行中间，颜色像一条线推过去 |
     * | [KARAOKE_MODE_PER_CHAR]（默认） | **吸附到字符簇边界** | 分界点落在**字的边界**上（不会有"半亮的字"） |
     * | [KARAOKE_MODE_LINE] | **二值化**：`progress > 0 ⇒ 整行` | 整行已唱色（或不唱），**无渐变过程** |
     *
     * ## ⚠️ 与真机的**已知差异**（如实登记，不假装逐字同源）
     *
     * ★ 真机的分界点由 `KaraokeTextRenderer` 依**真实逐字时间轴**算（`getLogicalHighlightShape`
     *   在**渲染器内部**）；预览没有时间轴 ⇒ 只能用**固定的示例进度** `demoProgress`。
     * ★ 两处**档位语义同源**（都引用 `dev.cdl.settings.KARAOKE_MODE_*` 常量），
     *   但**分界点的数据来源不同**（真机 = 时间轴；预览 = 示例进度）—— 这是预览的固有局限 ✓
     *
     * ★ 未知档 ⇒ 按 [KARAOKE_MODE_PER_CHAR]（**注册表默认**）处理，与"未知值退回默认档"
     *   的同一条口径一致（见 `ClockGlyphs.normalizeStyle`）✓
     */
    private fun previewKaraokeSplitX(
        layout: java.awt.font.TextLayout, drawX: Float, mode: String,
    ): Int = when (mode) {
        dev.cdl.settings.KARAOKE_MODE_LINE ->
            if (demoProgress > 0f) (drawX + layout.advance).roundToInt() else drawX.roundToInt()
        dev.cdl.settings.KARAOKE_MODE_SMOOTH ->
            (drawX + layout.advance * demoProgress).roundToInt()
        else -> {
            // `PER_CHAR` / 未知 ⇒ 吸附到**字符簇边界**（用 Java2D 自己的 highlight 形状取右缘）。
            val target = layout.advance * demoProgress
            var lastX = drawX
            for (i in 1..layout.characterCount) {
                val right = layout.getLogicalHighlightShape(0, i).bounds2D.maxX.toFloat()
                if (right <= target) lastX = drawX + right else break
            }
            lastX.roundToInt()
        }
    }

    /**
     * 岛体形状 —— 走 [previewIslandShapeCore]（**镜像实现**，见 [COVER_SHAPE_CIRCLE] 那条纪律）。
     *
     * ## ⚠️ 2026-09-24 更正：下面这段"为什么必须自己有一份"的旧理由**已经过期**
     *
     * **原记**（改动前逐字）：「`IslandWindow.buildShape` 是 `private`，且 `IslandWindow`
     * 是 `internal` —— `settingsui` 既看不到也调不到。共用需要把渲染层内部实现提升为
     * 公共契约，代价大于收益。故这里是**有意的一份镜像实现**。」
     *
     * **实为**：2026-09-22 形状构造已被提升为 `window/IslandPolicy.kt` 的**公开顶层纯函数**
     * `islandShape(...)`（它自己的 KDoc 写着"生产与探针共用的**唯一定义点**"）
     * ⇒ "调不到"这一条**不再成立**。
     *
     * **但镜像实现仍然保留** —— 因为**另一条理由还在**：`settingsui` 不该依赖 `window` 包的
     * 实现细节（见 [COVER_SHAPE_CIRCLE] 与 [previewVinylRingWidth] 的同一条纪律）。
     * ⇒ 它的性质从"被迫复制"变成"**有意同值重写**"。而按本文件惯例，
     * 这类重写**必须由探针跨包钉住**（⑯.5 就是这么钉 `previewVinylRingWidth` 的）。
     *
     * ⚠️ **而形状这一对在 2026-09-24 之前从来没被钉过**：`grep` 全树只有本文件与
     * `IslandPolicy` 两处构造代码，**没有任何断言比对它们** ⇒
     * 改了真机的圆角公式、忘了改预览（或反之）会一路绿到部署，而用户看到的是
     * 「**设置里预览的圆角与真机不一致**」—— 那正是当初加形态快照要修的那类问题。
     * 已补：`ColorPickerProbe` ⑯.7 在 **4 形态 × 9 圆角百分比 × 7 组尺寸 × 2 个 inset
     * = 882 个组合**上做**逐曲线**几何比对（不是采样）。
     *
     * | 形态 | 真机 `IslandPolicy.islandShape` | 这里 |
     * |---|---|---|
     * | `SQUARE` | `Rectangle2D`（**不加任何圆角**） | 同 |
     * | `ROUNDED_RECT` / 未知 | `RoundRectangle(短边 × percent/100)` | 同 |
     * | `PILL` | 半径 = 短边一半 | 同 |
     * | `NOTCH` | 上大下小（`Area` 差集） | 同 |
     *
     * ⚠️ 圆角是**短边百分比**，不是像素 —— 与 `shape.corner_radius` 的注册表语义一致
     * （`range = 0.0..100.0`，summary 写的就是"圆角大小 %"）。
     *
     * ⚠️ **基准取 `min(w, h)`（预览自己那个盒子的短边），不是真机的 `radiusBasisPx`
     * （收起态岛高）**：预览是**定尺寸**示意图（`preferredSize` 固定），真岛高随字号变。
     * 取自己的短边才能保持"圆角 = 短边的百分比"这一**视觉语义**不变 ——
     * 这是两侧**有意**的差异、不是漂移，故 ⑯.7 比对时也按同一基准喂真机函数。
     *
     * @param inset 岛体相对组件左上角的边距。
     */
    private fun islandShape(snapshot: PreviewSnapshot, inset: Float, w: Float, h: Float): Shape =
        previewIslandShapeCore(
            snapshot.shapeType, snapshot.cornerRadius, inset, w, h,
            // ★ 2026-09-25 收口：角风格两键**已进快照** ⇒ 预览与真机同源
            //   （此前恒传 `"ARC", 4`，表现为"真机切了连续曲率、预览没变"）。
            snapshot.cornerStyle, snapshot.cornerN,
        )

    /**
     * 「封面图」档（`background.color_mode = cover_image`）的岛体背景。
     *
     * **与真机 `IslandPanel.drawCoverImageBackdrop` 是同一条做法** —— 而且靠的是
     * **复用同一个公开入口** [dev.cdl.render.CoverBackdrop.baked]
     * （等比铺满 + 旋转 + 同源取色蒙版 + L2 模糊），不是在这里复刻一套烘焙。
     *
     * ## 为什么这次必须补这一档（而不是"看起来差不多"）
     *
     * 本档下真机画的是**封面图**，预览此前画的是**纯色深灰** —— 这是**方向性的不一致**，
     * 不是数值偏差：用户在封面图档里调 `background.cover.angle_deg`、
     * 调「岛内内容模糊」，预览**一点都不动**。
     *
     * ⚠️ **顺带关掉一个真死字段**：`material.content.blur_radius` 此前已进快照
     *    （`PreviewSnapshot.materialBlurRadius`）却**没有任何消费者** ——
     *    因为预览**根本没有**封面图档这条路径。本档接上后，
     *    该键的消费点就是下面 `blurRadius =` 实参（与真机同一个参数位）。
     *
     * ## 三条与真机**逐字对齐**的规则
     *
     * 1. **蒙版色回退**：没有主色时退回 [BACKGROUND_BASE_RGB]，
     *    **刻意不读 `background.color`** —— 那是**纯色档**的键；本档若读它，
     *    "哪一档读哪些键"的边界就糊了（真机 KDoc 已写明这条理由）。
     * 2. **降采样倍率**：`material.quality == "LITE"` ⇒ 4，其余 ⇒ 1。
     * 3. **alpha 只乘一次**：整张烘焙图做一次 `AlphaComposite`
     *    （而不是"封面按 A 画、蒙版再按 A 填" —— 那会得到 `A + A(1−A)`）。
     */
    private fun drawCoverImageBackdrop(
        g2: Graphics2D,
        shape: Shape,
        src: BufferedImage,
        coverRgb: Int?,
        inset: Float,
        islandW: Float,
        islandH: Float,
    ) {
        // ★★ 2026-10-10（审计 v4 · **B-3**）：本函数与真机 `IslandPanel.drawCoverImageBackdrop`
        //   是**同名近体**（仅差十几字符）—— ★ **已登记为"已知重复、暂未合并"**。
        //
        // ## 为什么暂不合并（★ 不是"懒得做"）
        //   ★ 两者**签名不同**：真机收 `Appearance` + 岛体左上角（`islandLeft/islandTop`，
        //     因为动画期间岛体不在面板左上角）；预览收快照派生的 `inset`（岛体恒在 `(inset, inset)`）。
        //   ★ 而共同的**核心算式**（蒙版色 `maskArgb`）依赖 `BACKGROUND_BASE_RGB`，
        //     那个常量在 `window/` 包 —— ★ 若把核心抽到 `render/`，会引入
        //     `render → window` 的**反向分层依赖** ✗
        //   ⇒ ★ 真正的合并要先把 `BACKGROUND_BASE_RGB` 挪到 `render/`（或改成入参）——
        //     **那是另一批的改动面**，本轮不夹带 ✓
        //
        // ⚠️ **漂移风险点（读这段时留意）**：下面 `maskArgb` 的算式与真机**必须同值**；
        //    `blurRadius` / `downsample` 的取法同理（它们**必须进 `baked()` 的缓存键**）。
        //   ★ 一旦发现两者观感不一致，**先比这三处** ✓
        val w = islandW.roundToInt()
        val h = islandH.roundToInt()
        if (w <= 0 || h <= 0) return
        val percent = snapshot.backgroundOpacity.coerceIn(0, 100)
        // 不透明度 0 = 背景全透明（与纯色档同语义：那时也不画背景）。
        if (percent <= 0) return

        val maskBase = coverRgb?.let { CoverPalette.darkFrom(it) } ?: Color(BACKGROUND_BASE_RGB)
        val maskArgb = (CoverBackdrop.MASK_ALPHA shl 24) or (maskBase.rgb and 0xFFFFFF)

        val baked = CoverBackdrop.baked(
            src = src,
            targetW = w,
            targetH = h,
            angleDeg = snapshot.backgroundCoverAngleDeg,
            maskArgb = maskArgb,
            // ★ 这两个值必须进缓存键（见 `baked()` 的 KDoc）——
            //   不进的话拖「岛内内容模糊」滑杆会命中旧缓存 ⇒ "拖了没反应"。
            blurRadius = snapshot.materialBlurRadius,
            downsample = if (snapshot.materialQuality == "LITE") 4 else 1,
        )

        val oldClip = g2.clip
        val oldComposite = g2.composite
        try {
            g2.clip = shape
            if (percent < 100) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, percent / 100f)
            }
            // 预览的岛体左上角就是 (inset, inset)（形状由 [islandShape] 以 inset 内缩给出）。
            g2.drawImage(baked, inset.roundToInt(), inset.roundToInt(), null)
        } finally {
            // 两个状态都必须显式写回：`clip` 是**与已有 clip 取交**（不是替换），
            // `composite` 会污染后面画歌词/封面/控件的那一大段。
            g2.composite = oldComposite
            g2.clip = oldClip
        }
    }

    /**
     * 把封面画成 `cover.shape` 指定的形状（圆形 / 圆角矩形 / 方形）。
     *
     * ## 为什么走"离屏图 + `DstIn` 软掩膜"而不是 `g2.clip`
     *
     * 与 [paintComponent] 里岛体背景同一条理由（**BUG-121**，注意**不是** BUG-117 —— 两者根因不同，见那条注释）：`setClip(Shape)` 是**硬边**
     * （`Region`，像素中心判定），拿它裁圆形封面会在圆周上留下一圈 1px 锯齿。
     * `AlphaComposite.DstIn` + **抗锯齿**掩膜得到的才是真正的软边。
     *
     * ## ⚠️⚠️ 2026-09-21 修正（BUG-117）：掩膜**必须来自一张位图**，不能用 `fill(shape)`
     *
     * 原写法是 `tg.composite = DstIn; tg.color = WHITE; tg.fill(shape)` —— 实测**完全不做掩膜**：
     *
     * | 写法 | `Ellipse2D(0,0,76,76)` 的 (0,0) alpha | 软边像素数 |
     * |---|---|---|
     * | `DstIn` + `fill(shape)` | **255**（= 没掩膜） | **0** |
     * | `DstIn` + `drawImage(掩膜位图)` | 0 | 276 |
     *
     * 原因：Java2D 的光栅化器**只遍历形状覆盖到的像素**，而 `DstIn` 的语义
     * （`dst = dst × src.alpha`）要求**形状之外**的像素也被置零 —— 那些像素根本没被访问，
     * 于是原样保留。换成 `drawImage` 后源图**覆盖整幅位图**，`DstIn` 才按预期逐像素生效。
     *
     * **后果（本轮才发现，比"方形不生效"更严重）**：`cover.shape = CIRCLE` 在预览里
     * **从来没有圆过** —— 封面一直是**方块**，只是外面套了一个真正圆的黑胶环
     * （环走 `Area` 布尔运算，不受此影响）。用户看到的会是"圆环里嵌着方块"。
     *
     * 代价是每帧一张 `side × side` 的 ARGB 临时图（默认 ≤ 96×96 ≈ 36 KB）——
     * 但只在**封面开启**时才走这条路，且结果按 (尺寸, 封面形状, 词岛形态) 缓存，
     * 拖动取色板时尺寸不变 ⇒ 缓存命中，不重复分配。
     */
    private fun drawMaskedCover(
        g2: Graphics2D, cover: BufferedImage, x: Float, y: Float, side: Float,
        coverShape: String, islandShapeType: String,
        /** ★ 封面清晰度（`cover.sharpness`，BUG-188）。 */
        sharpness: Int,
    ) {
        // ★★★ 2026-10-13（**BUG-189**）：**按设备缩放渲染** —— 与真机 `IslandWindow` 同一条纪律。
        //   ★ `side` 是**逻辑**像素，而 `g2` 带着**设备变换**（HiDPI / 系统缩放）
        //     ⇒ 只渲染 `side` 个像素、却被放大到 `side × 缩放` 个**物理**像素 ⇒ 糊 ✗
        //   ★ 修法：渲染 `ceil(side × 缩放)` 个像素（掩膜也一起），
        //     最后贴图时**指定目标为逻辑 `side`** ⇒ 设备坐标系下 1:1
        //     ⇒ ★ **屏幕上大小形态不变**、只是像素更多 ✓
        //   ★★ 算式收口到 [bitmapRenderPx]（**唯一出口**，与真机**同一个函数**）✓
        val px = bitmapRenderPx(side, deviceScaleOf(g2))
        // ★★ 2026-10-13（BUG-188）：**锐化也进缓存键** ——
        //   否则拖滑杆时 key 不变 ⇒ 预览**不更新** ✗（与 `CoverScaler` 同一条纪律）
        val key = ((px and 0xFF) shl 24) or ((sharpness and 0x7F) shl 17) or
            ((coverShape.hashCode() and 0x7F) shl 10) or
            (islandShapeType.hashCode() and 0x3FF)
        var tmp = coverMaskCache
        if (tmp == null || coverMaskKey != key) {
            // ① 掩膜位图：抗锯齿填充形状 → 边缘是 0..1 的软覆盖度（这正是 `clip` 给不了的）。
            val mask = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
            val mg = mask.createGraphics()
            mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            mg.color = Color.WHITE
            mg.fill(coverMaskShape(coverShape, islandShapeType, px.toFloat()))
            mg.dispose()

            // ② 封面图 × 掩膜。`drawImage` 覆盖整幅位图 ⇒ `DstIn` 逐像素生效
            //    （用 `fill(shape)` 会让形状外的像素**逃过**置零，见上方 KDoc）。
            val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
            val tg = img.createGraphics()
            // ⚠️⚠️ 2026-09-21（BUG-122）：**必须走 [CoverScaler]** ——
            //    原写法 `tg.drawImage(cover, 0, 0, px, px, null)` 用的是 Java2D
            //    **默认的最近邻**插值，512×512 缩到 ~76px 会糊成马赛克。
            //    用户真机原话：「示例封面很**糊**，不清晰，影响观感，
            //    **我提供给你的原文件都没这么糊**。」
            //    顺带把"中心方形裁剪"也统一了：此前预览把整张图**拉伸**到 px×px，
            //    非正方形封面会**变形**，而真机 `drawCoverImage` 取的是中心方形
            //    ⇒ 预览与真机**不同源**（用户会在预览里调好、到真机变样）。
            tg.drawImage(CoverScaler.squareScaled(cover, px, sharpness), 0, 0, null)
            tg.composite = AlphaComposite.DstIn
            tg.drawImage(mask, 0, 0, null)
            tg.dispose()
            coverMaskCache = img
            coverMaskKey = key
            tmp = img
        }
        // ── ★★ 2026-09-22：插值提示 —— 与真机 `IslandWindow` **同一位置、同一取值** ──
        //
        // 真机在贴封面**之前**对 Graphics2D 设 `VALUE_INTERPOLATION_BICUBIC`
        // （`IslandWindow` 里设在 `copy` 上）。本函数此前**没设** ——
        // 这就是"预览与真机两处绘制点"里漏掉的那一处。
        //
        // ## 为什么设在这里（而不是"缩放那一步"）
        //
        // **缩放不在这里** —— 它已由 [CoverScaler.squareScaled]（面积平均）在 ② 段完成，
        // 故本次 `drawImage` 在**用户坐标系**里是 1:1 的。
        //
        // 但 `g2` 是**组件自己的** Graphics2D，它带着**设备变换**（HiDPI / 系统缩放）。
        // 设备缩放 ≠ 1 时，这次 1:1 的贴图在**设备坐标系**里就是一次放大，而 Java2D
        // 默认插值是**最近邻** ⇒ 封面出块、发糊。设了它，这一步由双三次完成。
        // （真机那次贴图同理不是严格 1:1：目标矩形按 `toInt()` 取整、源图取 `ceil(size)`，
        //   亚像素差正是靠这条提示兜住的 —— 见 `IslandWindow` 里贴封面时设的插值提示。）
        //
        // ## ⚠️ 上下文必须是这一个
        //
        // [drawMaskedCover] 全程**直接用传入的 `g2`**，没有任何 `create()` ——
        // 故这里设的就是真正生效的那一份，**不会被内部覆盖**。
        // ⚠️ 也**有意不**设在 `tg`（② 段那个离屏上下文）上：那是真·1:1 的贴图
        // （源 `px×px` → 目标 `px×px`），设在那里是**不可证伪的空操作**。
        g2.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )
        // ★★★★★ 2026-10-17（**BUG-224 · 封面脱出黑胶环**）：**目标矩形写错了**。
        //
        // ## 用户报障（逐字）
        // > 「**示例词岛依旧没修好，封面还是脱出黑胶环。**」
        //
        // ## 根因：`drawImage` 的**五参重载**是 `(x, y, width, height)`，不是 `(x, y, x2, y2)`
        //
        // 旧写法：
        // ```kotlin
        // g2.drawImage(tmp, x.roundToInt(), y.roundToInt(),
        //     x.roundToInt() + side.roundToInt(),     // ← 被当成 **width**
        //     y.roundToInt() + side.roundToInt(),     // ← 被当成 **height**
        //     null)
        // ```
        // ★ Java2D 里「4 个 int + observer」**只有一个**重载 = `(x, y, width, height)`；
        //   想表达「两个角点」必须用 **10 参**版本
        //   （`dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer`）。
        // ⇒ 实测：`x = 26`、`side = 51` 时，图片被画成 **77 × 77**，
        //   而黑环外径只有 **75** ⇒ ★ 封面从环里**溢出一整圈** ✗
        //   （偏移量恰好等于左边距 `x` —— 这就是"脱出"的观感来源）
        //
        // ★★ 真机 `IslandWindow.drawCoverImage` 用的是 **10 参**版本（源矩形 + 目标矩形），
        //   所以**真机没有这个 bug** —— 这也是"示例词岛坏、真机好"的原因 ✓
        //
        // ★ 探针为什么一直没抓到：此前只断言**环的几何**（`previewVinylRingWidth` 等纯函数）
        //   与**像素是否存在**，从未断言「封面的绘制矩形 ≡ 环的内容矩形」✗
        //   ⇒ 本次加 `ColorPickerProbe` 的 ⑰ 组（逐像素：环外不得有封面像素）。
        val dst = coverDestRectFor(x, y, side)
        g2.drawImage(tmp, dst[0], dst[1], dst[2], dst[3], null)
    }

    /**
     * 封面形状。**唯一一份几何实现** —— 掩膜（[drawMaskedCover]）与黑胶环
     * （[drawVinylRing]）都调它，故"画出来的封面"与"环贴着的轮廓"**必然是同一个形状**。
     *
     * @param x 平移量（全局坐标）。掩膜用局部坐标 `(0,0)`，黑胶环用封面左上角。
     */
    private fun coverMaskShape(
        coverShape: String, islandShapeType: String, side: Float,
        x: Float = 0f, y: Float = 0f,
    ): Shape = when (coverShape) {
        // ── 这是**封面形状**（键 `cover.shape`）的「圆形」档 ─────────────
        COVER_SHAPE_CIRCLE -> Ellipse2D.Float(x, y, side, side)
        // ── 这是**封面形状**（键 `cover.shape`）的「方形」档（2026-09-21）──
        //    ⚠️ 2026-09-21 补：注册表与真机 `IslandWindow` **早已**支持这一档，
        //       而这里此前只有 `CIRCLE / else` 两条分支 ⇒ 用户选「方形」，
        //       预览画的是**圆角**矩形（`else` 分支）。真机侧被
        //       `IslandPolicyProbe:399` 钉住了，**预览侧漏了**。
        //    直角 ⇒ 弧半径 0，直接 `Rectangle2D`。
        //
        //    ⚠️ **别把它和 `shape.type` 的 `"SQUARE"` 看成一个**：那个键（词岛整体轮廓）
        //       在下面 `else` 分支里出现，两个键、两个语义、字面量巧合相同。
        COVER_SHAPE_SQUARE -> Rectangle2D.Float(x, y, side, side)
        else -> {
            // ⚠️ 这里读的是 `islandShapeType` —— **词岛形态**（键 `shape.type`），
            //    **不是**封面形状。它只决定"方形词岛下封面圆角更小"。
            val arc = side * if (islandShapeType == "SQUARE") COVER_ARC_RATIO_SQUARE else COVER_ARC_RATIO
            RoundRectangle2D.Float(x, y, side, side, arc, arc)
        }
    }

    /**
     * 黑胶环（`cover.vinyl`）—— 在传入形状**之外**画一圈近黑，**不侵占**该形状。
     *
     * ## 几何与真机 `IslandWindow` 的封面几何 **逐字相同**
     *
     * ```
     * Area(BasicStroke(2 × ring).createStrokedShape(shape)) - Area(shape)
     * ```
     *
     * 描边得到的是沿轮廓**内外各外扩 `ring`** 的带；减去形状本身 ⇒ 只剩**外面**那半圈。
     * 于是 ① 不侵占封面（用户点名要求）、② 形状自动跟随 `cover.shape`
     * （圆形 → 圆环、方形 → 方框、圆角 → 圆角框），无需为每种形状各写一遍。
     *
     * ## ⚠️ 2026-09-22：本函数**没变**，变的是它拿到的 `shape`
     *
     * 用户新要求是"黑边要宽（外半径的 1/3）+ **整体缩小**"。这两条在几何上
     * **不是**靠改本函数实现的，而是靠**传进来的 `shape` 从"外径"换成"内容直径"**：
     *
     * ```
     * 旧：shape = 外径 S 的形状          ⇒ 环外缘 = S + 2b          （外径变大 ✗）
     * 新：shape = 内容 (S − 2b) 的形状   ⇒ 环外缘 = (S−2b) + 2b = S  （外径不变 ✓）
     * ```
     *
     * 故本函数**继续**把环画在 `shape` 之外是正确的 —— 只是调用点现在传
     * **内容**形状（见 `paintComponent` 的 ② 段）。⚠️ 改调用点时**必须**同步改
     * `drawMaskedCover` 的尺寸（两者是同一个 `content`），否则环与封面会错位一整圈。
     *
     * 与 [coverMaskShape] 共用同一个 `shape` —— 这正是"避免第二份几何实现"的落地方式。
     */
    private fun drawVinylRing(g2: Graphics2D, shape: Shape, ring: Float) {
        if (ring <= 0f) return
        val ringArea = Area(BasicStroke(ring * 2f).createStrokedShape(shape))
        ringArea.subtract(Area(shape))
        g2.color = VINYL_RING_COLOR
        g2.fill(ringArea)
    }

    /**
     * ★★★ 2026-10-16（**§3.77-D**）：**插槽目标**（侧边条 / 放射 / 环）的整帧不透明度。
     *
     * ★★ **与真机同源**：走 `dev.cdl.window.spectrumAlphas(...).frameOpacity`
     *   —— 真机侧那条路径也是它（`IslandWindow` 的 `alphas.frameOpacity`）⇒
     *   两边**同一个函数**，不可能漂移 ✓
     *
     * ★ 为什么插槽**不吃**主带那条 55% 护栏：护栏的理由是"别盖住**歌词**"，
     *   而插槽三型都在**右端插槽带**里、与歌词**并排** ⇒ 护栏在此**没有理由**，
     *   留着只会让预览的环比真机淡一半（用户报「**不统一**」）✗
     */
    private fun slotFrameOpacity(): Float =
        dev.cdl.window.spectrumAlphas(
            snapshot.spectrumOpacityPct, 0, false,
        ).frameOpacity

    /**
     * ★★★ 2026-10-16（**§3.77-D**）：**主带**在预览里的不透明度**上限**。
     *
     * ★ 55% = 改动前那个硬编码值（`(0.55f * 255).toInt()`）—— 现在**收窄到主带**
     *   （只有它压在歌词上）✓
     * ⚠️ **这是预览本地策略，真机没有它** ⇒ 用户把 `spectrum.opacity_pct` 拉到
     *   100% 时，预览的主带仍显示 55%（换"歌词读得清"）。
     *   ★ 这是**有意的、如实登记的差异** —— 与插槽两档（与真机逐位一致）**不同**。
     */
    private val PREVIEW_MAIN_BAND_MAX_FRAME_OPACITY = 0.55f

    /** 按快照的对齐设置摆位（与词岛同一个语义）。 */
    private fun alignX(left: Float, avail: Float, advance: Float): Float = when (snapshot.contentAlign) {
        "LEFT" -> left
        "RIGHT" -> left + max(0f, avail - advance)
        else -> left + max(0f, (avail - advance) / 2f)
    }

    /**
     * 静态频谱柱（不接音频）。
     *
     * **有意不接音频**：取色器里放会动的频谱只会分散注意力，而且那需要
     * `ProcessSpectrum` 那套跨进程链路（本类在 `settingsui` 里，不该拉进来）。
     * 形状用一条固定的"包络"，够看出颜色即可。
     *
     * ★★ 2026-09-27：**与真机同源**（用户口径 B：「同步，让预览跟形态走」）。
     *
     * ## 改动前（"独立的一份假频谱"）
     *
     * 固定 `bars = 64`、`barW = max(1, pitch * 0.6)`、`fillRect`（**方角**），
     * **只读** `spectrumEnabled` / `spectrumColor` / `spectrumSymmetric` 三个字段。
     * 于是用户在预览里拖「柱间距」「柱体形状」滑杆 —— **毫无反应**。
     * 形态与真机不符的形态正是本项目 `fixed_width` 那类**死设置**的观感。
     *
     * ## 现在
     *
     * 读 `spectrum.bar_pitch_tenth_px` / `bar_shape` / `bar_corner` 三个键，
     * 并**调用真机那套纯函数**（[isSolidSpectrumPitch] / [spectrumBarSlot] /
     * [spectrumBarRectX] / [spectrumPolylinePoints] / [spectrumPolylineCornerPx]）。
     *
     * ⚠️ **调用同一函数，不是"抄同一份常量"** —— 后者正是"两份实现各自演化"的成因
     * （本项目已犯 6 次）。
     *
     * ⚠️ **一处有意的不一致（如实登记）**：预览的柱数仍固定为 [PREVIEW_SPECTRUM_BARS]
     * （= 64），**不**取真机的 `spectrumBarCount` —— 后者由**真实带宽**推导，
     * 而预览的"带宽"是面板绘制出来的一个示意值，取它会把两套语义搅在一起。
     * 故这里只做到"**几何判据同源**"，柱数不同。
     */
    private fun drawStaticSpectrum(
        g2: Graphics2D, x: Float, y: Float, w: Float, h: Float,
        /** `spectrum.symmetric` —— 双峰时**上下各画一份**（见快照字段的说明）。 */
        symmetric: Boolean,
        /**
         * ★★★ 2026-10-11（**U13 · 插槽小频谱**）：**固定条数**（与真机 `paint` 的
         * 同名参数**同一条判据** `spectrumBarCountFor`）。
         * `> 0` ⇒ 恒用该条数（**不经过 `SPECTRUM_MIN_BARS`=24 托底**）；
         * `<= 0`（默认）⇒ 与改前相同（[PREVIEW_SPECTRUM_BARS]）。
         *
         * ★ 为什么预览也要它：插槽可用宽 28 px 下按宽度推导会被托底到 **24 根**
         * ⇒ 预览也糊成一片 ⇒ ★ **预览与真机必须同病同治**（⓪j 的精神）✓
         */
        forceBars: Int = 0,
        /**
         * ★★★ 2026-10-11（**BUG-166 · 补**）：**固定柱距**（px）。`> 0` ⇒ 恒用该值。
         *
         * ★ 用途单一：**插槽 5 根侧边条**要恒用 [dev.cdl.render.slotSideBarPitchPx]（7）——
         * 与真机 `slotBarPitchPx(isPolar = false, …)` **同一条判据**（"五根"的辨识度
         * 不能被"实心"设置吃掉）✓
         * `<= 0`（默认）⇒ 跟随 `snapshot.spectrumBarPitchTenthPx`（极坐标两档 / 主带）✓
         */
        forcePitchPx: Float = 0f,
        /**
         * ★★★ 2026-10-11（⑰「封面多色流光」）：本帧的**封面多色**。
         *
         * ★ 只在 `spectrum.color_mode = cover_flow` 时被读 —— 其余档**一个分支都不多走**
         * （空列表 ⇒ 退回既有单色路径 ⇒ 预览**逐像素等于改动前**）✓
         * ★ 来源 = `demoCoverPalette()`（与真机 `coverPaletteFor` 调**同一个公开入口**）✓
         *
         * ⚠️ **刻意不给默认值**：本函数有 3 个调用点，给默认值会让"漏传"变成
         *    **静默的单色**（正是本项目反复记过的"新参数给默认值 ⇒ 第二条路径漏传"）。
         */
        coverPalette: List<Int>,
        /**
         * ★★★ 2026-10-15（**§3.75**）：本帧这个目标（**主带 / 插槽**）**自己的取色档位**。
         *
         * ★ 插槽有独立的 `island.right_slot.spectrum_color_mode`（默认 `follow` = 跟随主频谱）
         *   ⇒ 本函数**不能**再直接读 `snapshot.spectrumColorMode` ——
         *   那会让"插槽独立选色"在预览里**看不见**（= 死设置）✗
         * ★ 调用方传 [slotSpectrumColorMode] / `snapshot.spectrumColorMode`（**各自那一份**）✓
         */
        colorMode: String,
        /**
         * ★★★ 2026-10-15（**§3.75**）：本帧这个目标**自己的基色**（打包 RGB）。
         *
         * ★ 与 [colorMode] 同批引入、同一条理由；调用方传 [slotSpectrum] / [spectrum] ✓
         * ⚠️ **刻意不给默认值**（与 [coverPalette] 同一条纪律）—— 给默认值会让"漏传"
         *    变成**静默地用主频谱的色**，而"插槽跟着主频谱"恰好也是合法状态 ⇒
         *    漏接线**看不出来** ✗
         */
        colorRgb: Int,
        /**
         * ★★★ 2026-10-16（**台账 §3.77-D**）：本目标的**整帧不透明度**（0..1）。
         *
         * ★★ **与真机 `alphas.frameOpacity` 同源** —— 由调用方传
         * `dev.cdl.window.spectrumAlphas(pct, …).frameOpacity`
         * （**同一个函数**，不在本文件里另算一份）✓
         *
         * ## 为什么从"硬编码 55%"改成入参
         *
         * ★ 用户原话：「**环形频谱显示不统一**」+「**和桌面词岛上的环不一样**」——
         *   ★★ 根因 = 预览把**所有**频谱一律压到 **55%**，而真机吃
         *   `spectrum.opacity_pct`（本机 = 100）⇒ 预览的环**凭空淡了一半** ✗
         *
         * ★ 55% 那条护栏**初衷对、作用域错**：它是为"频谱压在**歌词**上、
         *   全不透明会盖住字"。而 **右端插槽的环 / 放射与歌词是【并排】的**，
         *   根本不压在上面 ⇒ 对它压暗**没有理由** ✗
         * ⇒ ★ 现在由调用方**按目标**给：主带取 `min(用户值, 55%)`（保住可读性）、
         *   插槽两档取**用户值**（与真机逐位一致）✓
         *
         * ⚠️ **刻意不给默认值**（与 `colorMode` / `colorRgb` 同一条纪律）——
         *   给默认值会让"漏传"变成静默地用某个固定 alpha，而那恰好也是合法状态 ✗
         */
        frameOpacity: Float,
    ) {
        // ★★★ 2026-10-04：极坐标（**放射状**，插槽 occupant = `RADIAL`）——
        //   ★ **与真机同一个纯函数** `spectrumPolarFor`（几何只有一个落点）。
        //   ★ `null` ⇒ 直角坐标（= 改动前的全部行为）。
        val polar = dev.cdl.render.spectrumPolarFor(
            dev.cdl.render.rightSlotOccupant(
                snapshot.rightSlotComponent,
                snapshot.spectrumAnchor,
            ),
            x, y, w, h,
            // ★★★ 2026-10-16（**§3.77-F**）：极坐标**径向范围**三键（与真机**同源**）——
            //   ⚠️ **显式传**（与真机同一条纪律）✓
            snapshot.rightSlotRadialInnerPercent,
            snapshot.rightSlotRingInnerPercent,
            snapshot.rightSlotPolarOuterPercent,
        )
        // ⚠️ 极坐标下条数由**内缘弧长**推（否则一圈塞太多 ⇒ 条在**内缘互相粘连** ⇒ 观感变"齿轮"）——
        //   ★★★ 2026-10-11（BUG-166）：改调**唯一出口** `polarBarCount`，
        //     与真机 `IslandWindow` **同一条判据**（预览不撒谎）✓
        val bars = if (polar != null) {
            // ★★★ 2026-10-11（BUG-166 补）：与真机**同一条判据** ——
            //   条数的间距基准另给（实心档没有间距，而"条数"是几何）✓
            dev.cdl.render.polarBarCount(
                polar,
                dev.cdl.render.polarCountPitchPx(
                    snapshot.spectrumBarPitchTenthPx / 10f,
                ),
            )
        } else if (forceBars > 0) {
            // ★★ 2026-10-11（U13）：与真机**同一条判据** `spectrumBarCountFor`
            //    （固定条数不经过 24 托底）—— 预览与真机同病同治 ✓
            dev.cdl.render.spectrumBarCountFor(
                w.toInt(), dev.cdl.render.SPECTRUM_BAR_PITCH_PX, forceBars,
            )
        } else {
            PREVIEW_SPECTRUM_BARS
        }
        // ★★ 极坐标下 `slot` 是**弧长槽宽**（周长 / 条数）——
        //   ⚠️ 不是 `w / bars`（那是直线宽；极坐标下 `w` 是"直径"，用它会得到过窄的柱）。
        //   ★★★ 2026-10-11（BUG-166）：取**内缘**弧长（不是外缘）—— 条数已按内缘定，
        //     槽宽必须同一口径，否则"10 根 × 外缘弧长 20px"会画出**巨柱** ✗
        //     `2π·innerR/bars` 恒 ≈ 一个 pitch ⇒ 柱宽 ≈ 4.9px，与真机同量级 ✓
        val slot = if (polar != null) {
            (2.0 * Math.PI * polar.innerRadius).toFloat() / bars
        } else {
            w / bars
        }
        // ★ 同源：间距（0 = 实心）→ 缝与柱宽 → 整像素吸附。
        //   `barPitchTenthPx` 是 0.1px 单位 ⇒ 除 10 得 px。
        //   ★★★ 2026-10-11（BUG-166 补）：插槽 5 根侧边条**恒用固定柱距**
        //   （与真机 `slotBarPitchPx(isPolar=false, …)` **同一条判据**）——
        //   实心会让五根**并成一块** ⇒ "五根"辨识度消失 ✗
        //   ★ 极坐标两档与主带**跟随用户设置**（含 0 = 实心 ⇒ 连续环 / 连续放射）✓
        val pitchPx = if (forcePitchPx > 0f) {
            forcePitchPx
        } else {
            snapshot.spectrumBarPitchTenthPx / 10f
        }
        val solid = isSolidSpectrumPitch(pitchPx)
        val (gap, barWidth) = spectrumBarSlot(slot, solid)
        // 端头形状：`bar_shape`（柱体）× `bar_corner`（端头）—— 与真机同一对枚举。
        val cornerEnum = SpectrumBarCorner.parse(snapshot.spectrumBarCorner)
        val shapeEnum = SpectrumBarCorner.parse(
            if (snapshot.spectrumBarShape.equals("ROUND", true)) "ROUND" else "SQUARE",
        )
        val barShapeRatio = shapeEnum.cornerFraction
        // ★★★ 2026-10-16（**§3.77-D**）：alpha 由**调用方按目标**给（见 `frameOpacity` 的 KDoc）——
        //   ★ 改前这里是硬编码 `0.55f` ⇒ 插槽的环 / 放射**凭空淡一半**（用户报「不统一」）✗
        //   ★ 现在：主带 = `min(用户值, 55%)`（护栏仍在，但**只作用在压在歌词上的那条**）；
        //     插槽两档 = 用户值（与真机 `alphas.frameOpacity` 逐位一致）✓
        val previewSpecAlpha = (frameOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        // ★★★ 2026-10-15（§3.75）：改用**本目标自己的**基色（主带 / 插槽各一份）✓
        val specRgb = colorRgb or 0xFF000000.toInt()
        // ★★ 2026-10-02（`color_mode = level`）：**逐柱取色**的基色（含预览固定 alpha）——
        //   真机侧同一档的基色来自 `unplayedPacked` / `played`（两趟各自带 alpha）。
        val specBaseArgb = (previewSpecAlpha shl 24) or (specRgb and 0xFFFFFF)
        // ★★★ 2026-10-15（§3.75）：档位也改用**本目标自己的**（`colorMode`）✓
        val levelMode = colorMode == dev.cdl.render.CoverPalette.LEVEL
        // ★★ 2026-10-11（⑰）：`cover_flow` 档 —— 与真机 `IslandPanel.spectrumBarColorFn`
        //   **同一条判据、同一个纯函数**（`spectrumFlowColorAt`）⇒ 预览不撒谎 ✓
        // ★★★ 2026-10-14：**`cover_camo`（迷彩碎色块）也必须进这里** —— 它与 `cover_flow`
        //   同接口、同四条不变量，只把"色相映射"换成离散热块。
        //   ⚠️ 若漏了本处 ⇒ 预览在迷彩档下**退回单色**，而真机是碎色块 ⇒ **预览撒谎** ✗
        //   （这正是本项目最多次的"同一判据漏了一条路径"形态）
        // ★★★ 2026-10-15（§3.75）：判据改调**公开纯函数** `isMultiColorSpectrumMode`
        //   （与真机 `IslandWindow` **同一个**，不再各写一遍两个 `==`）✓
        val flowMode = colorMode == dev.cdl.render.CoverPalette.COVER_FLOW
        val camoMode = colorMode == dev.cdl.render.CoverPalette.COVER_CAMO
        val multiColorBar = dev.cdl.window.isMultiColorSpectrumMode(colorMode)
        g2.color = Color(specRgb, true).let {
            Color(it.red, it.green, it.blue, previewSpecAlpha)
        }
        val axisY = y + h / 2f

        if (solid) {
            // ── ★ 折线形态（同源：[spectrumPolylinePoints]）────────────────────
            val baseY = if (symmetric) axisY else y + h
            val heights = FloatArray(bars) { i ->
                val t = i.toFloat() / (bars - 1)
                val env = PREVIEW_SPECTRUM_ENV_MIN +
                    (1f - PREVIEW_SPECTRUM_ENV_MIN) * kotlin.math.sin(t * Math.PI).toFloat()
                // ★ BUG-166 补：极坐标下柱长 = **可用径向长度** × 包络
                //   （与真机 `halfSpan = maxLength − innerRadius` 同口径）✓
                val full = when {
                    polar != null -> (polar.maxLength - polar.innerRadius) * env
                    symmetric -> (h / 2f) * env
                    else -> h * env
                }
                full.coerceAtLeast(1f)
            }
            // ★★★ 2026-10-11（**BUG-166 · 补**）：**极坐标实心**走【环带轮廓】——
            //   与真机 `paintBars` **同一条判据**（`spectrumPolarPolylinePoints`）⇒ 预览不撒谎 ✓
            //   ⚠️ 直角折线生成器**不是极坐标感知的**（按 `x0/slot/baseY` 铺点），
            //     原样走它会画出"过圆心的长条"✗
            if (polar != null) {
                val outP = FloatArray(2 * bars * 2)
                val np = dev.cdl.render.spectrumPolarPolylinePoints(
                    spec = polar, count = bars, heights = heights, out = outP,
                )
                if (np > 0) {
                    val contour = java.awt.geom.Path2D.Float()
                    contour.moveTo(outP[0].toDouble(), outP[1].toDouble())
                    for (k in 1 until np) {
                        contour.lineTo(outP[k * 2].toDouble(), outP[k * 2 + 1].toDouble())
                    }
                    contour.closePath()
                    g2.fill(contour)
                }
                return
            }
            // ⚠️ 容器要 `(bars + 3) × 2`：**左下角 + bars 个柱顶 + 末根 hold-last 顶点 + 右下角**。
            //    首版按 `(bars + 1) × 2` 给、且消费时把"最后一个点"当闭合点单独 `lineTo`
            //    ⇒ 与真机**同一对**缺陷（① 填充退化成三角形；② 末根退化成三角形）。
            //    两处同步修正，见 [spectrumPolylinePoints] 的 KDoc 缺陷表。
            // ★★ 2026-09-28：**对称档要两倍**（下半 = 上半轮廓的倒序镜像），
            //    与真机 `paintBars` 折线分支**同一条接线**（用户报障："对称双峰没有下半部分"）。
            val out = FloatArray(if (symmetric) (2 * bars + 6) * 2 else (bars + 3) * 2)
            val n = spectrumPolylinePoints(
                x, bars, slot, heights, baseY, out,
                mirrorAxisY = if (symmetric) baseY else Float.NaN,
            )
            if (n > 0) {
                // ★ 同源：折点圆角走真机那一个函数（② 在预览侧的落地点）。
                //   上限取 `PREVIEW_MIN_BAR_HEIGHT`（与真机 `MIN_BAR_HEIGHT` 同角色）。
                // ★★ 2026-09-28 补：乘 `barShapeRatio` —— 与真机 `paintBars` 折线分支、
                //    以及本文件柱状分支（`rawArc * barShapeRatio`）**同一条规则**。
                //    ⚠️ 原来只读 `cornerEnum`（= `spectrum.bar_corner`，**未注册的幻影键**
                //    ⇒ 恒 "ROUND"）⇒ 预览的实心档也**永远画不出方角折点**（BUG-138）。
                val jointArc = spectrumPolylineCornerPx(slot, PREVIEW_MIN_BAR_HEIGHT, cornerEnum) *
                    barShapeRatio
                // ★★ 2026-10-11（⑰）：折线档的**多色** —— 与真机同一条路
                //   （真机折线档也走 `paintOverride`；`cover_flow` ⇒ 横向多色渐变）✓
                //   ⚠️ 色数 < 2 ⇒ `flowGradientPaint` 返回 `null` ⇒ 保持上面设的单色 ✓
                // ★★★ 2026-10-14：**迷彩档走 `flowCamoPaint`** —— 与真机 `spectrumFlowPaintFor`
                //   的 `COVER_CAMO` 分支**同一个函数、同一支色块划分** ⇒ 预览不撒谎 ✓
                if (multiColorBar) {
                    val flowPaint = if (camoMode) {
                        dev.cdl.render.flowCamoPaint(
                            x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(),
                            0, dev.cdl.render.CAMO_BLOCK_COUNT,
                            coverPalette, previewSpecAlpha, phase = 0f,
                        )
                    } else {
                        dev.cdl.render.flowGradientPaint(
                            x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(),
                            0, coverPalette, previewSpecAlpha, phase = 0f,
                        )
                    }
                    if (flowPaint != null) g2.paint = flowPaint
                }
                if (spectrumPolylinePath(previewPolylinePath, out, n, jointArc)) {
                    g2.fill(previewPolylinePath)
                }
                // ★ 恢复笔刷：`g2.paint` 是**跨调用共享**的 Graphics2D 状态 ——
                //   不恢复会让后续绘制（歌词 / 封面）也用上这支渐变（本项目记过的形态）✓
                if (multiColorBar) g2.color = Color(specRgb, true).let {
                    Color(it.red, it.green, it.blue, previewSpecAlpha)
                }
            }
            return
        }

        // ── 柱状形态（同源：[spectrumBarRectX] + [spectrumBarCornerPx]）──────
        // ★★★ 2026-10-11（**BUG-166 补**）：极坐标**实心**档与真机**同一条路径** ——
        //   真机实心档走**环带轮廓**（`spectrumPolarPolylinePoints`：外缘跟频谱、
        //   内缘 = 内半径，一次 `fill`）；若预览还按"逐柱矩形"画，实心档下
        //   预览是"贴边的粗柱"、真机是"连续环" ⇒ **预览撒谎** ✗
        //   ★ 故这里调**同一个纯函数**、同一套包络 ⇒ 两边同构 ✓
        if (polar != null && solid) {
            val hs = FloatArray(bars) { i ->
                val t = if (bars > 1) i.toFloat() / (bars - 1) else 0.5f
                val env = PREVIEW_SPECTRUM_ENV_MIN +
                    (1f - PREVIEW_SPECTRUM_ENV_MIN) * kotlin.math.sin(t * Math.PI).toFloat()
                (polar.maxLength - polar.innerRadius) * env
            }
            val pts = FloatArray(2 * bars * 2)
            val np = dev.cdl.render.spectrumPolarPolylinePoints(
                spec = polar, count = bars, heights = hs, out = pts,
            )
            if (np > 0) {
                val path = java.awt.geom.Path2D.Float()
                path.moveTo(pts[0].toDouble(), pts[1].toDouble())
                for (k in 1 until np) {
                    path.lineTo(pts[k * 2].toDouble(), pts[k * 2 + 1].toDouble())
                }
                path.closePath()
                g2.fill(path)
            }
            return
        }
        // ★★★ 2026-10-11（BUG-166）：极坐标的**基线**在**内半径**处（不是区域底边）——
        //   与真机 `paintBars` 的 `bottomEdge = cy − innerRadius` **同一条口径**。
        //   ⚠️ 原实现用 `y + h`（区域底边）⇒ 条从**圆心下方**起、按 `h` 长 ⇒
        //     画出来是"过圆心的长条"而不是"从内半径向外的射线" ✗（预览撒谎）
        // ★★★ 2026-10-17（**BUG-222**）：★ 极坐标 + 对称（`RING`）档的基线是
        //   **中半径轴** `cy − (innerR + outerR)/2`，**不是内半径**！
        //
        // ## 根因（与本批 BUG-221 的 clip 修法互补，但这是几何本身的错）
        //
        // ★ 真机 `paintBars`：`axis = cy − (innerR + outerR)/2`、`baseY = axis`（对称档）、
        //   柱 = `[axis − barHeight, axis + barHeight]`（`barHeight = value·halfSpan`）
        //   ⇒ **双峰恰好铺满 `[innerR, outerR]`** ✓
        // ★ 预览原实现：`baseY = cy − innerRadius`（内半径）+ `half = (h/2)·env`
        //   （`h` = 插槽方形边长 ⇒ `h/2` = **外半径**！）
        //   ⇒ 柱被**整体外移** `half`（最大 = 外半径 38px），落在
        //     `[innerR + half − bh, innerR + half]` 上 —— **完全偏出环带** ✗
        //
        // ## 取证（`SlotRingNoProtrusionProbe` 的前身临时探针，用户真实配置 476×120）
        //
        // ```
        // i=9/18 env=0.997 baseY=39.1 half=37.878 bh=17.045 rectY=1.22
        // ```
        // * `baseY` 应为 `60 − (20.9+38)/2 = 30.55`，实为 **39.1**（= 60 − 20.9，内半径）✗
        // * `half` 应为 `(38−20.9)/2 × 0.997 = 8.5`，实为 **37.9**（= 38 × 0.997，外半径）✗
        // * ⇒ 柱面在 `y ∈ [1.22, 18.27]`（半径 41.7～58.8），而环带只有 `[20.9, 38]`
        //   ⇒ **最长那根柱戳出岛体顶边 11px**；用户截图（取色窗 476×120）实测
        //     青绿 bbox = x[361..463] y[27..107]，与"外移后裁剪"逐点吻合 ✓
        //
        // ★ 与 BUG-221（`clip`）的关系：clip 只是**把脱出裁掉**（治标），
        //   几何修对后柱面本就不该出岛体 ⇒ 两者**互补**，clip 保留作兜底 ✓
        val baseY = when {
            // ★ BUG-222：对称极坐标走**共用出口** `polarSymmetricAxisY`（中半径轴）——
            //   与真机 `SpectralPainter` 同一个函数，杜绝"两边各写一遍"再漂移 ✓
            polar != null && symmetric -> dev.cdl.render.polarSymmetricAxisY(polar)
            polar != null -> polar.cy - polar.innerRadius
            symmetric -> axisY
            else -> y + h
        }
        for (i in 0 until bars) {
            val t = i.toFloat() / (bars - 1)
            // 两端低、中间高的固定包络（与真实频谱"中间响"的观感一致）。
            val env = PREVIEW_SPECTRUM_ENV_MIN +
                (1f - PREVIEW_SPECTRUM_ENV_MIN) * kotlin.math.sin(t * Math.PI).toFloat()
            // ★★★ 2026-10-04：极坐标下**逐柱旋转**到自己的扇区（与真机同一条规则）。
            val polarSaved = if (polar != null) g2.transform else null
            if (polar != null) {
                g2.rotate(
                    Math.toRadians(
                        dev.cdl.render.polarAngleDeg(polar, i, bars).toDouble(),
                    ),
                    polar.cx.toDouble(),
                    polar.cy.toDouble(),
                )
            }
            // ★ 同源：水平几何走真机那一个函数（含整像素吸附）。
            val (bxSlot, bw) = spectrumBarRectX(x, i, slot, gap, barWidth, solid = false)
            // ★★★ 极坐标下所有柱**共用同一个 x**（圆心正上方）—— 旋转把它们分到各自扇区。
            val bx = if (polar != null) polar.cx - bw / 2f else bxSlot
            // ★★★ 2026-10-17（**BUG-222**）：极坐标档的 `half` = **半跨度 × env**
            //   （= 真机 `halfSpan × value`），**不是** `(h/2)·env`。
            //   ⚠️ `h` = 插槽方形边长 ⇒ `h/2` = **外半径**（38）而不是半跨度（8.55）
            //     ⇒ 柱面被平移到环带之外（详见 `baseY` 上方的取证）✗
            //   ★ 直角坐标对称档不变（`(h/2)·env` 在那里是对的：轴 = 区域中线）。
            val half = when {
                // ★ BUG-222：极坐标的半长走**共用出口** `polarSymmetricHalfLengthPx` ——
                //   = 真机 `halfSpan × value` 同一个函数 ✓
                polar != null -> dev.cdl.render.polarSymmetricHalfLengthPx(polar, env)
                symmetric -> (h / 2f) * env
                else -> 0f
            }
            // ★ BUG-166：极坐标下柱长 = **可用径向长度** × 包络（与真机 `halfSpan` 同口径）——
            //   ⚠️ 原实现用 `h`（= 直径 64）⇒ 条会长到戳出画布 ✗
            val bh = when {
                polar != null -> (polar.maxLength - polar.innerRadius) * env
                symmetric -> half * 2f
                else -> h * env
            }
            // ★ 同源：圆角半径走真机那一个函数。
            // ★★ 2026-09-28：上限改用 `bw / 2f`（**条宽的一半**）—— 与真机 `paintBars`
            //    **完全同一条公式**（那里是 `spectrumBarCornerPx(barWidth, barWidth / 2f, …)`）。
            //    改前这里传 `bh`（柱高），真机传 `MIN_BAR_HEIGHT`（2px）⇒ **两边上限不同**
            //    ⇒ 预览是胶囊、真机近方角，「示例词岛圆角、真实词岛不是」正源于此。
            //    现在两边都 = `min(bw, bw/2) = bw/2` ⇒ 半径恒为 **条宽/2**，任意宽度都是胶囊。
            val cornerCapPx = bw / 2f
            val rawArc = spectrumBarCornerPx(bw, cornerCapPx, solid = false, cornerEnum)
            val arc = rawArc * barShapeRatio
            // ★★ 2026-10-02（`color_mode = level`）：**逐柱取色** —— 与真机**同一个纯函数**。
            //   ⚠️ 只在 `level` 档改色 ⇒ 其余档 `g2.color` 保持上面设的那一个（逐像素不变）。
            if (levelMode) {
                g2.color = Color(levelColorArgb(specBaseArgb, env), true)
            }
            // ★★ 2026-10-11（⑰「封面多色流光」）：**逐柱多色** —— 与真机 `argbForBar`
            //   调**同一个纯函数** `spectrumFlowColorAt`（★ 预览相位恒 0 = 静态）✓
            //   ⚠️ `bars` 传本帧实际柱数（与真机"由绘制侧给 count"同一条口径）✓
            // ★★★ 2026-10-14：**迷彩档调 `spectrumCamoColorAt`** —— 同样与真机同一个纯函数
            //   （`spectrumBarColorFn` 的 `COVER_CAMO` 分支）⇒ 预览不撒谎 ✓
            if (multiColorBar) {
                g2.color = Color(
                    if (camoMode) {
                        dev.cdl.render.spectrumCamoColorAt(
                            specBaseArgb, i, bars, coverPalette, 0f,
                        )
                    } else {
                        dev.cdl.render.spectrumFlowColorAt(
                            specBaseArgb, i, bars, coverPalette, 0f,
                        )
                    },
                    true,
                )
            }
            if (symmetric) {
                // 对称跨轴：上下两端都是尖端 ⇒ 两端都圆（与真机 `fade_top=false` 对称分支同源）。
                // ★★★ 2026-10-13（**BUG-167**）：`RoundRectangle2D` 的 `arcw/arch` 是**弧的直径**
                //   （角半径 = `arcw/2`），而 `arc` 在本文件与真机里**一律是半径**
                //   ⇒ 原实现把 `arcTall` 当直径传 ⇒ **角半径只有一半**（细柱上≈直角）✗
                //   ⇒ 与真机 `paintBars` **同一条修正**：**乘 2** 换成直径 ✓
                val arcTall = arc.coerceAtMost(bh / 2f)
                g2.fill(
                    java.awt.geom.RoundRectangle2D.Float(
                        bx, baseY - half, bw, bh, arcTall * 2f, arcTall * 2f,
                    ),
                )
            } else {
                // 非对称：顶端圆、**根部直角**（用户 2026-09-26 原话："根部不要应用圆角"）。
                barSegmentPath(
                    previewBarPath, bx, baseY - bh, bw, bh, arc,
                    roundTop = true, roundBottom = false,
                )
                g2.fill(previewBarPath)
            }
            // ★★★ 2026-10-04：**恢复变换**（极坐标）。⚠️ 与真机 `paintBars` 同一条规则 ——
            //   漏掉会让旋转**累积**到下一柱（每柱多转一次 ⇒ 越转越偏）。
            if (polarSaved != null) g2.transform = polarSaved
        }
    }

    /**
     * 解析字体（**带字重**）。
     *
     * 2026-09-21：加字重 —— 与真机走**同一条语义**（`TextAttribute.WEIGHT`，
     * 见 `render/LetterSpacing.fontWithLetterSpacing`），只是这里不叠字距。
     *
     * ⚠️ 预览若不跟着变，用户调完字重在预览里**看不出任何变化** ——
     * 他会以为"这个设置是坏的"，而这正是本项目 `fixed_width` 那类**死设置**的形态。
     */
    private fun resolveFont(size: Float): Font = runCatching {
        // 2026-09-22：随包字体取消，构造方式与渲染层一致（`Font(family, …)`，
        // 见 `render/LetterSpacing.kt`），不再经过 `BundledFonts.resolve`。
        //
        // ★★ 2026-09-29 接线宿主内置字体（`recon/66` §0.3 的构造点 ②）。
        //    ⚠️ **必须与渲染层同一个构造方式** —— 否则"预览与渲染不一致"会复发
        //    （2026-09-22 之前正是这个病，只是方向反过来）。
        // ★★★ 2026-10-07 **BUG-161**：改用**唯一漏斗**（与渲染层**同一个函数**）。
        //   原来写 `resolveOrNull(f)?.deriveFont(…) ?: Font(f, …)` ⇒ 哨兵失败时是
        //   `Font("@host:MiSans", …)` ⇒ **退化成 `Dialog`**（申报的回退族从没被用过）。
        val base = dev.cdl.font.resolveFontOrFallback(snapshot.fontFamily, size.roundToInt())
        // ★★ 2026-09-29：**与渲染层同一套**（`render/LetterSpacing.fontWithLetterSpacing`）——
        //    加粗走 `Font.BOLD`（**JDK 挑该族最接近的真字重** ⇒ 适配不同字体的可用字重档），
        //    而**不再写 `TextAttribute.WEIGHT`**（实测它只有两态，写中间值等于没写）。
        //    ⚠️ 预览与真机必须**同一个构造方式**，否则"预览与渲染不一致"会复发。
        if (snapshot.fontBold) base.deriveFont(Font.BOLD) else base
    }.getOrNull() ?: Font(Font.SANS_SERIF, Font.PLAIN, size.roundToInt())

    companion object {
        // ⚠️ 2026-09-21 清理：这里原先堆着**三段悬空 KDoc**（"预览条的中性底" ×2、
        //    "对比底图的左半（浅）与右半（深）"）。它们描述的常量后来被移动/改名，
        //    于是 KDoc 成了**孤儿** —— 而 Kotlin 会把紧邻的 KDoc 挂到**下一个声明**上，
        //    所以那四段里只有最后一段（本段之后那段）是真正生效的。
        //
        //    正文已按事实归位：配色语义在 [backdropLeftColor] / [backdropRightColor]，
        //    绘制方式与"内/外之分"的更正在 `paintComponent` 的 ⓿ 段。
        //    ⚠️ 其中「**跟随系统深浅色主题**」那句当时是一条**未实现的承诺**
        //    （注释说做了、代码里左右两半是写死的）—— 现已真正实现。
        /**
         * 预览的**设计宽度**（px）—— 字号缩放以此为基准（见 `fontScale` 的说明）。
         *
         * 取 460 的理由：它就是本组件 `preferredSize` 的宽度。
         * 于是「面板正好是首选尺寸」时缩放系数恰为 1，字号与设置值**逐像素一致** ——
         * 这是「改动前观感不变」的锚点。
         */
        private const val PREVIEW_REFERENCE_W = 460f

        /**
         * 预览频谱的**柱数**（固定）。
         *
         * ⚠️ **有意不取真机的 `spectrumBarCount`** —— 后者由**真实带宽**推导，
         * 而预览的"带宽"是面板上画出来的一个示意值。取它会**把两套语义搅在一起**
         * （看似"更同源"，实则把一个几何量当成另一个）。故只做到"几何判据同源"。
         */
        private const val PREVIEW_SPECTRUM_BARS = 64

        /**
         * 预览频谱包络的**最低比例**（两端）。
         *
         * 与改动前的 `0.25f + 0.75f × sin(...)` **逐位等价**：
         * `0.25 + 0.75 × x == ENV_MIN + (1 − ENV_MIN) × x` 当 `ENV_MIN = 0.25`。
         * 抽成常量只是为了让"这个数字是什么"有个名字，**不改数值**（零回归）。
         */
        private const val PREVIEW_SPECTRUM_ENV_MIN = 0.25f

        /**
         * 预览折线形态下**折点圆角的上限**（px）—— 与真机 `SpectralPainter.MIN_BAR_HEIGHT`
         * **同角色**（真机那个是 `private`，预览拿不到，且预览的柱宽与真机不同）。
         *
         * ⚠️ 这是**有意复制的第二个数字**，理由与 [PREVIEW_SPECTRUM_BARS] 相同：
         * 两边的"最小柱高/柱宽"本就是**两套几何**。取值与真机一致（`2f`），
         * 使两边的圆角**观感同量级**；真正的同源是**函数**（`spectrumPolylineCornerPx`），
         * 不是这个数字。
         */
        private const val PREVIEW_MIN_BAR_HEIGHT = 2f

        //
         // 预览里**副歌词相对主歌词的字号比例**。
         //
         // ⚠️ **必须与真机的 `dev.cdl.window.TRANSLATION_SCALE` 同值** ——
         // 预览的全部意义就是"所见即真机所示"。两者不同值，会让用户在预览里调好的
         // 搭配在真机上变样，而**他没有任何线索知道是预览在骗他**。
         //
         // 0.7 → **0.64**（2026-09-21）：用户「示例歌词**主副歌词比例不佳**」。
         // 原值 0.7 是与原实现 0.8.9 对齐而来的，但在"**主歌词有描边、译文无描边**"
         // 的实际观感下，两者的**视觉重量**过于接近，主次不够分明。
         // 收一档到 0.64（仍高于字幕惯例下限 0.6），让主行明确成为视觉焦点。
         //
        /**
         * ⚠️ 2026-09-21：本常量已**并入** [TRANSLATION_SCALE_DEFAULT]。
         *
         * 保留为**别名**而不是直接删 —— 删常量时若有引用被漏改，编译会失败在
         * "找不到符号"上，但**更可能的情况**是某处仍按旧值硬编码而编译照样通过
         * （那才是真正危险的漏改）。留个别名让旧引用继续可用且**指向同一个值**。
         *
         * **新代码请直接用 [TRANSLATION_SCALE_DEFAULT]**。
         */
        private const val PREVIEW_TRANSLATION_SCALE = TRANSLATION_SCALE_DEFAULT

        /**
         * **"缩字号直到这行放得下"的缩放下限**。
         *
         * ⚠️⚠️ 2026-09-21（BUG-120）：原值 **0.45** 是"面板被拖到极窄"时的兜底，
         * 但它**没有覆盖注册表允许的字号区间** —— `main_lyric.font.size` 的上界是 **200**
         * （2026-09-16 用户要求"字号范围放宽"）。字号一大，需要的缩放比就远低于 0.45：
         *
         * | 主字号 | 面板缩放后字号 | 该行自然宽 | 可用宽 | 需要的比例 |
         * |---|---|---|---|---|
         * | 63 | ≈ 62 | ≈ 868 px | ≈ 432 px | ≈ **0.50** |
         * | 200 | ≈ 197 | ≈ 2758 px | ≈ 432 px | ≈ **0.16** |
         *
         * 于是"缩字号"缩到 0.45 就**停住了**，剩下的一截被 `clip` 裁掉 ——
         * 用户看到的就是「示例歌词**显示不全**，末端像被截断」。
         *
         * ## 为什么取 0.05 而不是"干脆不夹"
         *
         * 0.05 已经低到 200 号字都放得下（需要 0.16），同时留了一个"不缩到 0"的护栏 ——
         * 面板被拖到 4px 宽时不会去 `deriveFont(0f)`（那是非法字号，会抛异常）。
         * 与 [fitToWidth] 的 KDoc 同一条取舍：**宁可字小到极限，也不让它缺字**。
         */
        const val MIN_FIT_SCALE = 0.05f

        /**
         * 刘海形态下方两角半径占短边的比例 —— 与真机 `IslandWindow.bottomNotchRadius` 同值。
         */
        private const val NOTCH_BOTTOM_RATIO = 0.12f

        /**
         * 封面形状常量 —— 与真机 `IslandPolicy.COVER_SHAPE_CIRCLE` 同值。
         *
         * ⚠️ **不要**改成从 `IslandPolicy` 导入：那是 `window` 包的内部契约，
         * 而 `settingsui` 不该依赖渲染层的实现细节。这里用**同值的本地常量**，
         * 判据是"值相同"，由探针守住（见 `ColorPickerProbe` ⑯）。
         */
        private const val COVER_SHAPE_CIRCLE = "CIRCLE"

        /**
         * 封面形状「方形」档 —— 与真机 `IslandPolicy.COVER_SHAPE_SQUARE` 同值（2026-09-21）。
         *
         * ⚠️ 与 [COVER_SHAPE_CIRCLE] 同一条纪律：**本地同值常量**，不从 `IslandPolicy` 导入
         * （理由见上）。由探针把"值相同"钉住（见 `ColorPickerProbe` ⑯.5）。
         *
         * ⚠️ 与 `shape.type` 的 `"SQUARE"` **同字面量、不同键** —— 别混（见 [coverMaskShape]）。
         */
        private const val COVER_SHAPE_SQUARE = "SQUARE"

        /** 封面圆角比例 —— 与真机 `IslandWindow.COVER_ARC_RATIO` 同值。 */
        private const val COVER_ARC_RATIO = 0.18f

        // ══ 2026-10-16（**§3.77-E**）：`FLOW_PREVIEW_PALETTE` **已删** ══
        //
        // 原记（该常量的 KDoc 逐字）：「真机的多色来自**当前歌曲的封面**，而预览是
        //   **静态示例**、没有真实封面 ⇒ 用一组**差异明显**的代表色（暖 / 冷 / 中性）
        //   让多停靠点渐变**看得见**」，并"如实记录代价：**预览的颜色不等于真机**"。
        //
        // ★★ **实为**：预览**有**示例封面（`demoCover()` 读随包资源
        //   `/demo/cover-demo.jpg`）⇒ ★ **那条"代价"根本不必付** ——
        //   `demoCoverPalette()` 调的是真机那**同一个** `CoverColorExtractor.palette` ✓
        //
        // ★ 用户报障（逐字）：「示例词岛背景的多色碎块**不符合示例词岛的封面**
        //   （**没取对 / 取的色不像来自示例封面的**）」⇒ 硬编码的橙 / 蓝 / 灰
        //   **正是**用户看到的东西 ✗
        // ⇒ ★ 已改为 `demoCoverPalette()`（**与真机同源**）——
        //   「预览与真机不同源」这处缺口一并消掉 ✓
        // ══ 说明结束 ══

        /**
         * 黑胶环宽占**外径**的比例 —— 与真机 `IslandPolicy.VINYL_RING_OVER_OUTER` 同值。
         *
         * `1/6` = 用户原话「和圆形封面的半径的 1/3」的直接换算（`b = R/3 = S/6`）。
         *
         * ⚠️ 2026-09-22：**入参语义从"内容边长"改成"外径"**，并**删掉了旧的
         * `[2, 6]` 夹取** —— 那个上限 6 正是"黑边太窄了"的成因
         * （旧环宽/外半径只有 0.114 = 用户要求的 34%）。
         * 新几何按比例推导、不需要夹取（理由见真机那份 KDoc）。
         */
        private const val VINYL_RING_OVER_OUTER = 1f / 6f

        /**
         * 黑胶环色 —— 与真机 `IslandWindow.VINYL_RING_COLOR` 同值：**不透明的近黑**。
         *
         * 抄自真机那份 KDoc：不取纯黑是因为词岛背景默认就是近黑，纯黑环会"融进去"，
         * 用户看到的是"封面变小了一圈"而不是"多了一圈黑边"；不带 alpha 是因为
         * 黑胶本体不透明，带 alpha 会透出底下的封面图，像"半透明塑料圈"。
         */
        private val VINYL_RING_COLOR = Color(18, 18, 18)

        /**
         * 黑胶环宽（px）—— **与真机 `IslandPolicy.coverVinylRingWidth` 同值重写**。
         *
         * ## ⚠️⚠️ 这里是**第二份实现**：改一处**必须**改另一处
         *
         * | | 位置 |
         * |---|---|
         * | 真机 | `window/IslandPolicy.kt` 顶层 `fun coverVinylRingWidth` |
         * | 预览 | 本函数（`settingsui/IslandPreviewPane.kt`） |
         *
         * 为什么在预览里**再写一份**而不是 import 真机那份：见 [COVER_SHAPE_CIRCLE]
         * 上面那条纪律（`settingsui` 不依赖 `window` 包的实现细节）。
         * 代价就是"两份实现可能漂移" —— 故探针**跨包逐点比对**两条函数在整段取值域上的
         * 输出（`ColorPickerProbe` ⑯.5「跨包漂移护栏」），而不是只比对常量。
         *
         * ## ⚠️ 为什么**改名叫 `previewVinylRingWidth`**（2026-09-21）
         *
         * 原名与真机那份**逐字相同**。后果实测发生过：`cover-fix` 看到调用点写的是
         * `coverVinylRingWidth(side, …)`（**改名前的名字**），判定为
         * "**直接调用真机函数**"并据此上报 —— 而它其实是**本地这份**。
         * 同名让"两份实现"在**阅读时**塌缩成一份，漂移风险就变得不可见。
         * ⇒ 按本文件既有惯例（`coverSideFor` ↔ 真机 `leadingCoverSize`、
         *   `previewTranslationUnsungColor` ↔ 真机 `dimPacked`：**同值但不同名**）改名。
         *
         * @param outerSize 封面**外径**（px）—— 槽位里那个圆的外接正方形边长。
         * @param vinyl 是否开启 `cover.vinyl`
         */
        private fun previewVinylRingWidth(outerSize: Float, vinyl: Boolean): Float {
            if (!vinyl) return 0f
            if (outerSize <= 0f) return 0f
            return outerSize * VINYL_RING_OVER_OUTER
        }

        /**
         * 封面**内容（图片）直径** —— 与真机 `IslandPolicy.coverContentSize` 同值重写。
         *
         * ## ★★ 2026-09-22：入参语义从"内容边长"改成"**外径**"
         *
         * 用户原话：「**黑边太窄了**，怎么说也得**和圆形封面的半径的 1/3**，
         * 同时为了保证封面协调，**加了黑边的封面要适当整体缩小一下**。」
         *
         * 旧几何把环画在内容**之外**（外径 = 内容 + 2×环宽）⇒ 加黑边让**整体变大**，
         * 正是用户否掉的那一点。新几何环**向内占**：外径是输入、恒定不变，
         * 内容 = 外径 − 2×环宽 = **2/3 外径**。**本函数的入参因此是"外径"**（与真机同步）。
         *
         * @return 内容直径；关黑胶时**恒等于**外径
         */
        private fun previewCoverContentSize(outerSize: Float, vinyl: Boolean): Float {
            if (outerSize <= 0f) return 0f
            return outerSize - 2f * previewVinylRingWidth(outerSize, vinyl)
        }

        /**
         * 供探针**跨包比对** [previewVinylRingWidth] 与真机 `IslandPolicy.coverVinylRingWidth`
         * （见 `ColorPickerProbe` ⑯.5「跨包漂移护栏」）。
         *
         * ⚠️ 2026-09-22：入参语义随 [previewVinylRingWidth] 一起变成"**外径**"。
         */
        fun coverVinylRingWidthFor(outerSize: Float, vinyl: Boolean): Float =
            previewVinylRingWidth(outerSize, vinyl)

        /** 供探针断言"内容直径 = 外径 − 2×环宽"（见 `ColorPickerProbe` ⑯.5）。 */
        fun coverContentSizeFor(outerSize: Float, vinyl: Boolean): Float =
            previewCoverContentSize(outerSize, vinyl)

        /** 供探针断言封面形状常量与真机同值（见 `ColorPickerProbe` ⑯.5）。 */
        fun coverShapeSquareFor(): String = COVER_SHAPE_SQUARE

        /**
         * 「形态 + 圆角百分比 + 尺寸」⇒ **该形态实际使用的上下圆角半径**（px）。
         *
         * ## 为什么必须抽出来（而不是让调用方自己算）
         *
         * `MaterialLayers.paint` 的 `shapeCornerRadius` 要的是"**这个形状的圆角**" ——
         * 真机从 `islandOutlineAt(...)` 取（`maxOf(top, bottom)`），而预览必须取
         * **它自己造形状时用的那个半径**。若让 `paintComponent` 里另写一份"形态 → 半径"
         * 的映射，两份迟早漂移（本项目最忌讳的形态），而漂移的表现是
         * **预览的玻璃边缘与预览的岛体对不上**（真机侧已栽过一次：**BUG-148**）。
         *
         * ⇒ 本函数是**预览侧半径的唯一出口**：`previewIslandShapeCore` 造形状用它，
         * `paintComponent` 传 `shapeCornerRadius` 也用它。
         *
         * ## 与真机的关系
         *
         * 逐分支等价于 `IslandPolicy.islandEdgeRadiusPx(...)`（真机的唯一出口）：
         * `PILL` / `NOTCH` 取 `maxRadius`、`SQUARE` 取 `0`、其余取用户圆角。
         * ★ 等价性由 `ColorPickerProbe` ⑯.7 的跨包逐曲线比对钉住 ——
         * **只改这一份而不改真机那份，⑯.7 立刻红**。
         *
         * @return `(上边半径, 下边半径)` —— 只有 `NOTCH` 两值不同，其余形态相同。
         */
        private fun previewIslandRadiiPx(
            shapeType: String,
            cornerRadius: Int,
            w: Float,
            h: Float,
        ): Pair<Float, Float> {
            // ★★★ 2026-10-03 **去掉镜像实现，直接调生产函数**（用户指令：
            //   「彻查未知的因修改而静默失效的功能链路」）。
            //
            // **原记**：本函数是 `islandEdgeRadiusPx` 的一份**手写镜像** ——
            //   `when (shapeType)` 四档 + 自己的 `NOTCH_BOTTOM_RATIO = 0.12f`，
            //   注释写着「与真机 `NOTCH_BOTTOM_RADIUS_RATIO` 同值」。
            // **风险**（本项**逃过了** `check_preview_parity` ⓪j）：
            //   ⓪j 只比"**两边共同调用的同一个函数**的命名实参集合" ——
            //   而这里两边调的是**不同名**的函数（`islandEdgeRadiusPx` vs `previewIslandRadiiPx`）
            //   ⇒ **不在它的覆盖范围内** ⇒ 任何一档改了另一档**不会红**。
            //   ★ 典型形态：真机加一档（如 `COVER_ONLY` 的独立口径）、预览漏跟 ⇒ **静默不同源**。
            //
            // ⇒ 现在两处**调同一个函数** ⇒ 结构上不可能漂，且**⓪j 从此能覆盖它** ✓
            //   （共同调用的函数 = `islandEdgeRadiusPx` ⇒ 实参名集合会被比对）。
            val basis = dev.cdl.window.islandRadiusBasisPx(w, h)
            fun edge(top: Boolean): Float = dev.cdl.window.islandEdgeRadiusPx(
                shapeType = shapeType,
                cornerRadiusPercent = cornerRadius,
                radiusBasisPx = basis,
                atTopEdge = top,
            )
            return edge(true) to edge(false)
        }

        /**
         * 岛体形状的**镜像实现**（[islandShape] 的实体）。
         *
         * ## 为什么单独拆成一个函数
         *
         * `islandShape` 是**实例私有方法**且要吃一个 `PreviewSnapshot` —— 探针既拿不到实例
         * 也没必要为了一次几何比对去构造快照。故把"只依赖 `shapeType` + `cornerRadius`"
         * 的实体搬到这里（参数化），再由 [islandShapeFor] 公开出去。
         * 与 [previewVinylRingWidth] / [coverVinylRingWidthFor] 是**同一套拆法**。
         *
         * ## 它与真机的关系
         *
         * 逐行等价于 `window/IslandPolicy.kt` 的公开顶层函数
         * `islandShape(shapeType, cornerRadius, w, h, radiusBasisPx = min(w, h))`，
         * 但**有意不 import** 它（理由见 [COVER_SHAPE_CIRCLE]）。
         * 等价性由 `ColorPickerProbe` ⑯.7 **跨包逐点**钉住 ——
         * 在 882 个组合上做**逐曲线**几何比对（`java.awt.geom.Area.equals`）。
         *
         * ⚠️ **只改这一份而不改真机那份 ⇒ ⑯.7 立刻红**（本轮已用变异验证过判别力）。
         */
        private fun previewIslandShapeCore(
            shapeType: String,
            cornerRadius: Int,
            inset: Float,
            w: Float,
            h: Float,
            cornerStyle: String,
            cornerN: Int,
        ): Shape {
            // ★ 半径的**唯一出口** —— 与 `paintComponent` 传给 `MaterialLayers.paint` 的
            //   `shapeCornerRadius` **共用同一个函数**。
            //
            // ⚠️ 本函数此前自己算 `maxRadius` / `radiusPx`，而 `paintComponent` 无从得知
            //   ⇒ 玻璃只能靠 `MaterialLayers` 的默认值（`-1` ⇒ 自己推）⇒
            //   **预览的玻璃边缘与预览的岛体对不上**（BUG-148 的预览侧，2026-10-01 修）。
            val (topRadiusPx, bottomRadiusPx) = previewIslandRadiiPx(shapeType, cornerRadius, w, h)
            fun rounded(radius: Float): Shape =
                RoundRectangle2D.Float(inset, inset, w, h, radius * 2f, radius * 2f)
            // ★ 2026-09-25 第 2 期波次 1：squircle 分支 —— **与真机同形**
            //   （真机在 `IslandPolicy.islandShapeOf` 的 SQUIRCLE 分支走 `squirclePath`）。
            //   预览把形状建在 `(inset, inset)`，真机建在 `(0,0)` 再由调用方平移 ——
            //   两边平移量相同 ⇒ 逐曲线可比（⑯.7 正是这样比的）。
            fun squircle(top: Float, bottom: Float): Shape =
                java.awt.geom.AffineTransform
                    .getTranslateInstance(inset.toDouble(), inset.toDouble())
                    .createTransformedShape(
                        dev.cdl.window.squirclePath(w, h, top, bottom, cornerN.toFloat()),
                    )
            val style = dev.cdl.window.effectiveCornerStyle(shapeType, cornerStyle)

            // ★ 2026-09-30：`COVER_ONLY` 分支用 —— "在原点造好、再整体平移 `inset`"。
            //   必须与真机同款（真机 `islandShape` 造在 `(0,0)`、调用方 `translate`），
            //   否则零弧退化的 double 舍入会差 ~2e-7 ⇒ `Area.equals` 逐曲线判不等。
            fun translate(s: Shape): Shape =
                java.awt.geom.AffineTransform
                    .getTranslateInstance(inset.toDouble(), inset.toDouble())
                    .createTransformedShape(s)

            return when (shapeType) {
                "PILL" -> rounded(topRadiusPx)
                // 方形就是方形：自作主张加 2px 圆角会让"方形 vs 圆角矩形"看不出区别。
                "SQUARE" -> Rectangle2D.Float(inset, inset, w, h)
                "NOTCH" -> if (style == "SQUIRCLE") {
                    // squircle 天然支持"上大下小"，**不需要**那套差集构造
                    //（差集是 `RoundRectangle2D` 只能四角同半径的补丁）。
                    squircle(topRadiusPx, bottomRadiusPx)
                } else {
                    val topRadius = topRadiusPx
                    val bottomRadius = bottomRadiusPx
                    val outer = Area(rounded(topRadius))
                    if (bottomRadius < topRadius) {
                        val cb = topRadius * 2f
                        val left = inset
                        val top = inset + h - cb
                        val right = inset + w - cb
                        val leftBottom = Area(Rectangle2D.Float(left, top, cb, cb)).apply {
                            subtract(Area(RoundRectangle2D.Float(left, top, cb, cb, topRadius * 2f, topRadius * 2f)))
                            add(Area(RoundRectangle2D.Float(left, top, cb, cb, bottomRadius * 2f, bottomRadius * 2f)))
                        }
                        val rightBottom = Area(Rectangle2D.Float(right, top, cb, cb)).apply {
                            subtract(Area(RoundRectangle2D.Float(right, top, cb, cb, topRadius * 2f, topRadius * 2f)))
                            add(Area(RoundRectangle2D.Float(right, top, cb, cb, bottomRadius * 2f, bottomRadius * 2f)))
                        }
                        outer.subtract(Area(Rectangle2D.Float(left, top, cb, cb)))
                        outer.subtract(Area(Rectangle2D.Float(right, top, cb, cb)))
                        outer.add(leftBottom)
                        outer.add(rightBottom)
                    }
                    outer
                }
                // ── 2026-10-01 · 封面态（`recon/67`）：**与 `ROUNDED_RECT` 同款 ⇒ 不单开分支** ──
                //
                // ★★ 用户 2026-09-30 原话：「**跟随胶囊和圆角矩形外观的词岛判定范围设定**」
                //    ⇒ 四角都取 `radiusPx` ⇒ 与 `ROUNDED_RECT` **逐曲线相同** ⇒ 直接落 `else`。
                //
                // ⚠️⚠️ **本分支原先写的是"上圆下直"（上 = `radiusPx`、下 = `0f`）** ——
                //   那**不是**用户要的，而是为了让 `ColorPickerProbe` §⑯.7 的
                //   「形态可区分性」变绿而加的。
                //   用户 2026-10-01 报「**岛体外形也非常丑，没有其他形态的圆润协调对称感**」
                //   ⇒ 真机侧与预览侧**同时**改回 `else`，并放宽那条探针断言
                //   （见 `ColorPickerProbe` §⑯.7 的 `shapePairsIndistinguishableByDesign`）。
                //   ★ 这条护栏（跨包逐曲线比对）**当场抓到了我"只改真机不改预览"**。
                // 未知值也走这里：回退到"圆角矩形"总比不画强（与真机同一条纪律）。
                else -> if (style == "SQUIRCLE") squircle(topRadiusPx, topRadiusPx) else rounded(topRadiusPx)
            }
        }

        /** 供探针做**跨包逐点几何比对**（见 `ColorPickerProbe` ⑯.7「岛体形状漂移护栏」）。 */
        fun islandShapeFor(
            shapeType: String,
            cornerRadius: Int,
            inset: Float,
            w: Float,
            h: Float,
            cornerStyle: String,
            cornerN: Int,
        ): Shape = previewIslandShapeCore(shapeType, cornerRadius, inset, w, h, cornerStyle, cornerN)

        /**
         * 供探针断言 [NOTCH_BOTTOM_RATIO] 与真机 `NOTCH_BOTTOM_RADIUS_RATIO` **同值**
         * （见 `ColorPickerProbe` ⑯.7 的第 ② 条断言）。
         *
         * ## ⚠️ 为什么这条**必须单独**用"常量比对"，不能靠上面的几何比对兜住
         *
         * 2026-09-24 实测（证伪时发现）：把本文件的 `NOTCH_BOTTOM_RATIO` 从 `0.12f` 改成
         * `0.20f`，⑯.7 的**几何比对一条都不红** —— 因为预览的基准是 `min(w, h)`，
         * 于是 `topRadius = 短边/2` ⇒ `cornerBox = topRadius × 2 = 短边`
         * ⇒ 底部那两个 `cornerBox` 方块**盖满整个短边方向**，
         * 差集构造退化成 `(方 − 内切圆) ∪ 圆角矩形 ≡ 方`（内切圆恒含于圆角矩形内）
         * ⇒ **底部圆角半径在预览这个基准下不产生任何像素差异**（几何惰性）。
         *
         * ⇒ 结论：**"几何相同"证明不了"常量相同"**。这类"参数在当前取值域里惰性"的
         *   重复常量必须单独钉（与 `coverShapeSquareFor()` 钉 `COVER_SHAPE_SQUARE` 同一做法）。
         *   反过来说，几何比对仍不可省 —— 它守的是**公式**（已在变异下验过会红）。
         */
        fun notchBottomRatioFor(): Float = NOTCH_BOTTOM_RATIO

        /**
         * 封面左边距 —— 真机 `LEADING_INSET`（= `HORIZONTAL_INSET`）在预览里的同义量。
         *
         * ## ★★★ 2026-10-04：**对齐真机**（原 `8f` ⇒ `14f`）
         *
         * ## 为什么改（`P-r`，2026-10-04 复核查出）
         *
         * 原值 `8f` 与真机的 `14f` **差 6 px**，`COVER_LEADING_GAP` 也差 2 px
         * ⇒ 预览的**左让位**比真机**窄 8 px**。
         * ★ **既存差异**（不是某一轮引入的），但它直接违反**立身纪律**：
         * 「**预览的全部意义是所见即真机所示**」（见 `migration/73` 开头那张"已栽四次"的表）。
         *
         * ★★ **为什么 2026-10-04 才显眼**：以前两侧**各用各的**（不交叉）；
         * 本轮新增 `lyricSideInsetPx = max(左, 右)` ⇒ ★ **常量差会移动 `max` 的交叉点**
         * ⇒ 从"看不出来"变成"**可能不一致**"。
         *
         * ⚠️ **连带效果（对齐的代价，如实记）**：预览的**封面右移 6 px**、
         * **歌词左边界右移 8 px**（= 6 + 2）⇒ 预览观感**会变**（用户 2026-10-04 拍板"对齐吧"）。
         */
        private const val COVER_LEADING_INSET = 14f

        /**
         * 封面槽位与歌词之间的间距 —— 真机 `LEADING_GAP` 在预览里的同义量。
         *
         * ★★★ 2026-10-04：**对齐真机**（原 `10f` ⇒ `12f`，见 [COVER_LEADING_INSET] 的 KDoc）。
         */
        private const val COVER_LEADING_GAP = 12f

        /**
         * 封面**内容**左边缘的 x —— 内容在"外径槽位"内**居中**之后的位置。
         *
         * `= 槽位左缘 + ring`。与真机 `IslandWindow` 的
         * `left = LEADING_INSET + ring` 同语义。
         * `vinyl = false` ⇒ `ring = 0` ⇒ 退回 `inset + COVER_LEADING_INSET`（原值）。
         *
         * ⚠️ 2026-09-22：入参从"内容边长"改成"**外径**"。公式形状不变，
         * 但语义变了 —— 旧几何下 `side` 是内容、环画在外圈；新几何下
         * `outer` 是**槽位边长**、内容 = `outer − 2×ring`、**整体居中于槽位**。
         */
        private fun coverContentLeft(inset: Float, outer: Float, vinyl: Boolean): Float =
            inset + COVER_LEADING_INSET + previewVinylRingWidth(outer, vinyl)

        /**
         * 封面**内容**上边缘的 y —— 与 [coverContentLeft] 同理（垂直方向也居中于槽位）。
         *
         * ⚠️ 旧几何下这里是 `inset + (islandH − side)/2`（内容直接居中于岛体）；
         * 新几何下**槽位**居中于岛体、内容再在槽位内缩一个环宽 ⇒ 末尾那个 `+ ring`
         * **不能漏** —— 漏了内容会整体上移 `ring`，而环是绕内容画的 ⇒
         * 环的下半圈会戳出槽位、盖到频谱带上。
         */
        private fun coverContentTop(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float =
            inset + (islandH - outer) / 2f + previewVinylRingWidth(outer, vinyl)

        /**
         * 歌词起点的 x —— 封面**槽位**之后的第一个可写像素。
         *
         * 与真机 `IslandWindow` 同语义：
         * `areaLeft = LEADING_INSET + coverSlotWidth(size, vinyl) + LEADING_GAP`。
         *
         * ## ★★ 2026-09-22：**去掉 `2 × ring`**
         *
         * 旧几何下 `coverSlotWidth = size + 2×ring`（环画在外圈 ⇒ 槽位要同步加宽），
         * 故这里也必须 `+ 2×ring`，否则预览的歌词起点比真机**偏左**。
         * **新几何下 `coverSlotWidth ≡ 外径`**（环向内占、外径不变）⇒ 槽位与外径
         * **同一个数**，`2×ring` 这一项**必须去掉** —— 留着它会让预览的歌词起点比
         * 真机**偏右约 35px**（比旧版偏左 11px 更糟）。
         */
        private fun coverTextLeft(inset: Float, outer: Float, vinyl: Boolean): Float =
            inset + COVER_LEADING_INSET + outer + COVER_LEADING_GAP

        /**
         * ★★ 2026-10-04：**封面内容左边缘**的探针入口 —— 与 [coverTextLeftFor] 同一理由
         * （private 函数探针看不到 ⇒ 开一个只读包装）。
         * ★ 用途：断言封面用的左内缩与歌词用的左内缩**同源**（都 == 真机 ）。
         */
        fun coverContentLeftFor(inset: Float, outer: Float, vinyl: Boolean): Float =
            coverContentLeft(inset, outer, vinyl)

        /** 供探针断言"开黑胶**不再**右移歌词起点（外径不变）"（见 `ColorPickerProbe` ⑯.5）。 */
        fun coverTextLeftFor(inset: Float, outer: Float, vinyl: Boolean): Float =
            coverTextLeft(inset, outer, vinyl)

        /** 供探针断言"内容在槽位内垂直居中"（见 `ColorPickerProbe` ⑯.5）。 */
        fun coverContentTopFor(inset: Float, islandH: Float, outer: Float, vinyl: Boolean): Float =
            coverContentTop(inset, islandH, outer, vinyl)

        /** 方形词岛上的封面圆角比例 —— 与真机 `COVER_ARC_RATIO_SQUARE` 同值。 */
        private const val COVER_ARC_RATIO_SQUARE = 0.06f

        // ══ 2026-10-10（B-3）：原声明已删，保留理由（普通注释，非 KDoc）══
        // ★★ 2026-10-10（审计 v4 · **B-3**）：译文未唱暗化系数**已迁到
        // `dev.cdl.settings.UNSUNG_TRANSLATION_DIM`**（值仍是 `0.62f`）。
        //
        // ★ **原记**（本行上一版逐字）：「与真机 `IslandWindow.UNSUNG_TRANSLATION_DIM`
        // **同值**……取 0.62 的理由（**抄自**真机那份 KDoc）」——
        // ★★ 那正是缺陷本身：**同一个常量两处各写一份**，靠注释提醒"必须同值" ✗
        // ⇒ ★ 收敛到 `settings/` ⇒ 两侧**引用同一个常量**，不可能漂移 ✓
        // ★ 本文件通过**顶层 import** 引用 ⇒ 调用点一字未改 ✓
        // ══ 说明结束 ══

        /**
         * 参考对比底的**浅块**。
         *
         * ## ★★★ 2026-10-04：加深对比（用户：「**不够白也不够黑**」）
         *
         * | | 原值 | 现值 | 变化 |
         * |---|---|---|---|
         * | 浅块 | `#C8C8CC`（L 0.580） | ★ **`#E8E8EC`**（L 0.795） | 更白 |
         * | 深块 | `#3A3A40`（L 0.043） | ★ **`#2C2C31`**（L 0.027） | 更黑 |
         *
         * ## ★★ 为什么"深块不能更黑"（**这是本项的硬约束**）
         *
         * 对比底的用途是**衬托词岛**（让用户看清词岛边界），而**词岛横跨两块**
         * ⇒ ★ **两块都要能看出词岛边界**。而词岛默认底色 `#14161A` 本身就是深灰 ⇒
         * 深块**越黑，与词岛越分不开**：
         *
         * | 深块 | vs 词岛 | 深浅互比 |
         * |---|---|---|
         * | 原 `#3A3A40` | 1.60 | 6.77 |
         * | ★ **现 `#2C2C31`** | **1.32** | ★ **11.05**（+63%） |
         * | `#2A2A2E` | 1.27 | 12.56 |
         * | `#262629` | 1.20 | 15.09 |
         *
         * ⇒ ★ 取 `#2E2E33`：**深浅互比 +63%**（用户要的"更白更黑"），
         * 而**深块 vs 词岛仍有 1.34**（原值的 84%，边界仍可辨）。
         * ★ 再黑下去（`#262629` ⇒ 1.20）会让**深色词岛在深块上消失** —— 那是把
         * "衬托"变成了"看不见"。
         *
         * ## ★ 为什么不开放给用户（用户 2026-10-04 明确）
         *
         * > 「**这种值就不开放给用户了，避免设置项堆积，我们在开发时定好合适的值。**」
         *
         * ⇒ ★ **写死在代码里**（`private val`，**不是** `SettingRegistry` 的键）。
         * 探针通过 [backdropLeftColor] / [backdropRightColor] 断言 ✓
         */
        private val PREVIEW_BACKDROP_LIGHT = Color(0xE8, 0xE8, 0xEC)

        /** 见 [PREVIEW_BACKDROP_LIGHT]（深块 —— ★ 它的下限由"与词岛底色的对比"决定）。 */
        private val PREVIEW_BACKDROP_DARK = Color(0x2C, 0x2C, 0x31)

        /**
         * 参考对比底的**左半**颜色。
         *
         * ## 规则：与主题同色的那一块放左边
         *
         * 用户原话：「注意"深浅对比色块"间要能**随着深浅主题切换交换位置**。」
         * 以及早先一次：「整个取色窗口要能**识别用户使用的深浅色主题**，同时**交换示例歌词
         *   背景的深浅色块**。」
         *
         * ⇒ 浅色主题 → 左**浅**右深；深色主题 → 左**深**右浅。
         *
         * **为什么"同色靠左"**：左缘紧贴预览区边界，与工具窗底色连成一片才不突兀。
         * 若不交换，深色主题下预览区左缘会突然出现一大块亮色 —— 那是用户两次提出这条的原因。
         *
         * ⚠️ 抽成**公开纯函数**而不是写死在 `paintComponent` 里，有两个理由：
         *   ① 探针能**直接断言两个主题下的取值**（写死在绘制里就只能靠像素采样，且要过注册表）；
         *   ② 它是"左右顺序"这件事的**唯一出口**，不会出现第二份真相源。
         */
        fun backdropLeftColor(isLightTheme: Boolean): Color =
            if (isLightTheme) PREVIEW_BACKDROP_LIGHT else PREVIEW_BACKDROP_DARK

        /** 参考对比底的**右半**颜色 —— 恒为 [backdropLeftColor] 的另一色。 */
        fun backdropRightColor(isLightTheme: Boolean): Color =
            if (isLightTheme) PREVIEW_BACKDROP_DARK else PREVIEW_BACKDROP_LIGHT

        /** 封面缓存：`resources/demo/cover-demo.jpg`（随包分发，见类 KDoc）。 */
        private var coverCache: BufferedImage? = null
        private var coverTried = false

        private fun demoCover(): BufferedImage? {
            if (!coverTried) {
                coverTried = true
                coverCache = runCatching {
                    IslandPreviewPane::class.java.getResourceAsStream("/demo/cover-demo.jpg")
                        ?.use { ImageIO.read(it) }
                }.getOrNull()
            }
            return coverCache
        }

        /** 供探针断言"封面确实随包分发"（不依赖 Swing）。 */
        fun demoCoverAvailable(): Boolean = demoCover() != null

        /**
         * ★★★ 2026-10-17（**BUG-224**）：封面贴图的**目标矩形**（`x, y, width, height`）。
         *
         * ## 为什么抽成函数（这是本轮"封面脱出黑胶环"的护栏）
         *
         * ★ 旧写法把 `(x, y, x + side, y + side)` 传给 `Graphics2D.drawImage` 的
         *   **五参重载** —— 而那个重载的语义是 `(x, y, width, height)`，
         *   **不是** `(x, y, x2, y2)` ✗
         * ⇒ 实测 `x = 26, side = 51` 时图片被画成 **77 × 77**，
         *   而黑胶环外径只有 **75** ⇒ 封面从环里溢出一整圈（用户报障四次的那条）✗
         *
         * ★ 抽成纯函数后，探针可以直接断言 `width == side`（而不是 `x + side`）——
         *   否则这条缺陷只能靠"看图"发现（而它已经躲过了四轮）✗
         */
        fun coverDestRectFor(x: Float, y: Float, side: Float): IntArray = intArrayOf(
            x.roundToInt(), y.roundToInt(),
            side.roundToInt(), side.roundToInt(),
        )

        /** 示例封面**主色**的缓存（`null` = 无封面 / 无可用主色，**也要缓存住**）。 */
        private var coverRgbCache: Int? = null
        private var coverRgbTried = false

        /**
         * 示例封面的**主色**（打包 ARGB；`null` = 无封面或无可用主色）。
         *
         * 与真机 `IslandPanel.coverRgbOf` 调的是**同一个公开入口**
         * [dev.cdl.cover.CoverColorExtractor.dominant] —— 于是
         * "预览的 `background.color_mode = cover` 底色"与真机同源。
         *
         * ⚠️ **必须缓存**：本函数在**每帧重绘**（`paintComponent`）里被调用，
         *    而 `dominant` 要遍历一张采样网格。示例封面是**随包资源、引用恒定**，
         *    故按"只算一次"缓存即可（与真机按图引用缓存的意图相同；
         *    真机多一层"粘性"是因为它的封面**每首歌都换**，预览没有这个问题）。
         */
        private fun demoCoverRgb(): Int? {
            if (!coverRgbTried) {
                coverRgbTried = true
                coverRgbCache = demoCover()?.let { CoverColorExtractor.dominant(it) }
            }
            return coverRgbCache
        }

        /** 示例封面**多色**的缓存（`null` = 还没算过；空列表 = 算过但拿不到多色）。 */
        private var coverPaletteCache: List<Int>? = null

        /**
         * ★★★ 2026-10-11（⑰「封面多色流光」· 频谱侧）：示例封面的**多色**。
         *
         * 与真机 `IslandPanel.coverPaletteFor` 调的是**同一个公开入口**
         * [dev.cdl.cover.CoverColorExtractor.palette] ⇒ 预览的
         * `spectrum.color_mode = cover_flow` 与真机**同源**（预览不撒谎）✓
         *
         * ⚠️ **必须缓存** —— 理由与 [demoCoverRgb] 逐字相同（每帧重绘 + 采样网格遍历）；
         *    示例封面是**随包资源、引用恒定** ⇒ 只算一次即可。
         *
         * ★ 色数取 `FLOW_PALETTE_COLORS`（**与真机同一个常量** ⇒ 不会一边 4 色一边 6 色）✓
         */
        private fun demoCoverPalette(): List<Int> {
            val cached = coverPaletteCache
            if (cached != null) return cached
            val p = demoCover()?.let {
                CoverColorExtractor.palette(it, dev.cdl.render.FLOW_PALETTE_COLORS)
            } ?: emptyList()
            coverPaletteCache = p
            return p
        }

        /**
         * 预览歌词描边的**笔型**（纯函数出口）—— 生产路径 `drawKaraokeLine` 也调它。
         *
         * ⚠️ **2026-09-26 修**：此前预览写的是 `BasicStroke(outline.widthPx)`，
         *    而那一档是 **`JOIN_MITER`** —— 会在锐角处外伸 `w/2 × miterLimit`（默认 10），
         *    画出**尖刺**；真机走 [dev.cdl.render.karaokeOutlineStroke] 的
         *    `CAP_ROUND` + `JOIN_ROUND`，**没有**尖刺
         *    （见 `KaraokeTextRenderer` 的 KDoc 与 `TextAntialiasProbe` §B/§D/§E）。
         *    ⇒ 预览与真机的字形边缘**不一致**，属本项目点名反对的「所见非所得」。
         *
         * 修法与同批的底色修复**同一手法**：**调真机的公开函数**，不在这里重写一份笔型。
         */
        fun previewOutlineStrokeFor(widthPx: Float): BasicStroke =
            dev.cdl.render.karaokeOutlineStroke(widthPx)

        /**
         * **预览岛体底色**（打包 ARGB，含 alpha）—— 与真机同源的**纯函数出口**。
         *
         * ## 为什么它必须是 `public`，且**生产路径也调它**
         *
         * 本项目的既有纪律是「为测试发愁先抽纯函数，而不是放宽可见性」。此处更进一步：
         * [paintComponent] **就是**调本函数取底色 —— 若探针改测"另写一份等价公式"，
         * 那条断言**是空的**（测的是测试自己的实现）。
         *
         * ## 同源在哪一处（一眼可核）
         *
         * | 步骤 | 真机 | 预览 |
         * |---|---|---|
         * | 底色来源 | `Appearance.backgroundColorFor` → `CoverPalette.resolve(…)` | **同一个函数** |
         * | 派生函数 | `CoverPalette::darkFrom` | **同一个** |
         * | alpha | `backgroundWithOpacity`：`255 × percent / 100` | **同一算式**（本函数） |
         *
         * ⚠️ 真机 `cover_image` 档**不走**底色（它整块烘焙，见 `drawCoverImageBackdrop`）——
         *    那时 `resolve` 只因 `mode != COVER` 而返回手动色，仅作"没有封面图可烘"的兜底。
         *    本函数保持同一语义，预览侧同构。
         *
         * @param mode `background.color_mode`（未知值 ⇒ 退回手动色，与注册表 LIST 收敛语义一致）
         * @param manualArgb `background.color`（手动档底色，打包 ARGB）
         * @param coverRgb 封面主色；`null` ⇒ 回退手动色
         * @param opacityPercent `background.opacity`（0..100）
         */
        fun previewBaseColorArgb(
            mode: String,
            manualArgb: Int,
            coverRgb: Int?,
            opacityPercent: Int,
        ): Int {
            val base = CoverPalette.resolve(mode, Color(manualArgb, true), coverRgb, CoverPalette::darkFrom)
            // 与真机 `backgroundWithOpacity` 的 alpha 算式**逐字相同**。
            val alpha = (255 * opacityPercent.coerceIn(0, 100) / 100).coerceIn(0, 255)
            return (alpha shl 24) or (base.rgb and 0xFFFFFF)
        }

        /** 供探针断言色值合成路径（不依赖 Swing）。 */
        fun sampleMain(): String = PreviewSnapshot.SAMPLE_MAIN

        // ── 供探针调用的**纯函数出口** ─────────────────────────────────────────
        //
        // ⚠️ 这些函数是 `public` 的，而上面几条辅助实现是 `private` —— 这是**有意**的：
        //    本项目已有纪律「为测试发愁先抽纯函数，而不是放宽可见性」，而这些**就是**
        //    抽出来的纯函数（不碰 Swing、不碰实例状态），故放宽的只是它们本身。
        //    反过来若把 `fitToWidth` 这种**带 `Graphics2D` 状态**的方法放开，
        //    探针就得先造一个 `Graphics2D` —— 那才是"为测试改契约"。

        /**
         * **按可用宽度收敛字号**的判据本身（纯函数）—— 与 [fitToWidth] 同源。
         *
         * @return 缩放系数（`1f` = 不用缩）。见 [MIN_FIT_SCALE] 的 KDoc。
         */
        private fun fitScale(
            text: String,
            font: Font,
            avail: Float,
            frc: java.awt.font.FontRenderContext,
            minScale: Float,
        ): Float {
            if (avail <= 0f) return 1f
            val advance = runCatching {
                java.awt.font.TextLayout(text, font, frc).advance
            }.getOrNull() ?: return 1f
            if (advance <= avail || advance <= 0f) return 1f
            return (avail / advance).coerceIn(minScale, 1f)
        }

        /**
         * 供探针断言「**极端字号下也不裁字**」—— 见 `ColorPickerProbe` ⑮。
         *
         * 用默认 `FontRenderContext`（与绘制时那一个只是 hint 不同，度量一致）。
         */
        fun fitScaleFor(
            text: String,
            font: Font,
            avail: Float,
            minScale: Float = MIN_FIT_SCALE,
        ): Float = fitScale(
            text, font, avail,
            java.awt.font.FontRenderContext(null, true, true),
            minScale,
        )

        /**
         * 封面边长（纯函数）—— 与真机 `IslandWindow.leadingCoverSize` **同一套公式**。
         *
         * ```
         * natural = (areaHeight - 2 × VERTICAL_PADDING).coerceAtLeast(MIN_COVER_SIZE)
         * scale_with_font = true  → natural            （**不套上限**）
         * scale_with_font = false → natural.coerceAtMost(MAX_COVER_SIZE)
         * ```
         *
         * 用户原话：「示例封面**没有适应示例词岛大小**。」原实现写死 `min(h - 12, 84)`
         * ⇒ **84 的硬上限**让"封面随字号缩放"在预览里恒等于关。
         *
         * ⚠️ 2026-09-22：公式**不再由本文件复刻**，改为**直接调真机的公开纯函数**
         * [dev.cdl.window.leadingCoverOuterSizePx]。理由：本轮起封面尺寸变成
         * **展开进度 `f` 的函数**（用户第 ② 条），两份各改一次必然漂移 ——
         * 而漂移的表现正是"设置页示例与真机不一致"。
         * 常量（`2 × 10` / `20` / `56`）也随之收进那个公开文件，本文件不再各存一份。
         *
         * @param areaHeight 纵向空间高度。预览画的是**收起态**示例词岛，故取岛体高度 `islandH`
         *   —— 与真机 `f = 0` 时 `areaHeight = BandGeometry.islandHeight` 同义。
         */
        fun coverSideFor(areaHeight: Float, scaleWithFont: Boolean): Float =
            leadingCoverOuterSizePx(areaHeight, scaleWithFont)

        // ══ 2026-10-10（审计 v4 · B-3）：原 `private fun dimPacked` **已删** ══
        //   它与 `IslandWindow.dimPacked` 是**逐字相同的两份**（纪律 73 点名要拦的形态）✗
        //   ⇒ ★ 收敛到公开纯函数 `dev.cdl.settings.dimPackedArgb`（两侧都调）✓
        //   ★ 语义（**不动 alpha 位**：暗化 ≠ 变透明）见该函数的 KDoc。
        // ══ 说明结束 ══

        /** 供探针断言译文"未唱色"确实是按 [UNSUNG_TRANSLATION_DIM] 暗化的（见 `ColorPickerProbe` ⑯）。 */
        fun previewTranslationUnsungColor(sungArgb: Int): Int =
            dimPackedArgb(sungArgb, UNSUNG_TRANSLATION_DIM)
    }
}
