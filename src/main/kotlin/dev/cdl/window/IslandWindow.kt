// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.animation.AnimationRuntime
import dev.cdl.animation.Easing
import dev.cdl.animation.FramePacer
import dev.cdl.animation.MotionStyle
import dev.cdl.animation.Motions
import dev.cdl.animation.FramePacingMonitor
import dev.cdl.animation.PerSecondMeter
import dev.cdl.cover.CoverArtProvider
import dev.cdl.cover.CoverColorExtractor
import dev.cdl.playback.LyricLine
import dev.cdl.playback.TimelineSnapshot
import dev.cdl.playback.sharedSweepFraction
import dev.cdl.render.CoverBackdrop
import dev.cdl.render.CoverPalette
import dev.cdl.render.CoverScaler
import dev.cdl.render.CoverWaterLevel
import dev.cdl.render.FLOW_PALETTE_COLORS
import dev.cdl.render.FLOW_PERIOD_MS
import dev.cdl.render.loopPhase
import dev.cdl.render.rightSlotExtraWidthPx
import dev.cdl.render.GlyphHitTester
import dev.cdl.render.KaraokeTextRenderer
import dev.cdl.render.MaterialLayers
import dev.cdl.render.NO_PROGRESS
import dev.cdl.render.OwnFrame
import dev.cdl.render.RenderCache
import dev.cdl.render.TextOutline
import dev.cdl.render.fontWithLetterSpacing
import dev.cdl.render.letterSpacingTracking
import dev.cdl.render.spectrumAutoPitch
import dev.cdl.render.symmetricBandTop
import dev.cdl.render.SpectralPainter
import dev.cdl.render.playbackProgress
import dev.cdl.render.spectrumBandGeometry
import dev.cdl.render.spectrumFadeStartFraction
import dev.cdl.settings.AnimationStyleResolver
import dev.cdl.settings.BORDER_MODE_FADE_OUT
import dev.cdl.settings.BORDER_MODE_EDGE_DISSOLVE
import dev.cdl.settings.borderFadeLayers
import dev.cdl.settings.KARAOKE_MODE_LINE
import dev.cdl.settings.KARAOKE_MODE_PER_CHAR
import dev.cdl.settings.KARAOKE_MODE_SMOOTH
import dev.cdl.settings.UNSUNG_TRANSLATION_DIM
import dev.cdl.settings.dimPackedArgb
import dev.cdl.settings.SettingDomains
import dev.cdl.settings.SettingRegistry
import dev.cdl.platform.CaptureExclusion
import dev.cdl.settings.blendPackedArgb
import dev.cdl.settings.spectrumBarCornerRatio
import dev.cdl.settings.SettingsRuntime
import dev.cdl.settings.SettingsSnapshot
import dev.cdl.settings.toColor
import dev.cdl.settings.toPackedArgb
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MouseInfo
import java.awt.Paint
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「**曲名 · 作者 · 专辑**」这一行的**唯一出口**（封面态 S2 提取，2026-09-30）。
 *
 * ## 为什么抽出来
 *
 * 这行文本原先**内联在** `drawNoLyricsNotice` 里。而**封面态**的 S2（展开态）
 * 也要画同一行（`recon/67` §2.2：「曲名 / 作者 = 复用 `drawNoLyricsNotice` 的
 *   `"$title · $artist"`」）。
 * ⇒ 若复制一份，**两份迟早漂移**（`recon/67` §5-⑥ 逐字：「复制一份 ⇒ 两份迟早漂移」）。
 * 故在**加第二个调用点之前**先把它抽成唯一出口（纪律 10：「首选修法是合并成唯一出口」）。
 *
 * ## 契约（★ 三条，都有既有注释背书）
 *
 * 1. **顺序 = 曲名 → 作者 → 专辑**（与原实现 0.8.9 一致，专辑**追加在尾** ——
 *    它是三段里最不重要也最长的一段）。
 * 2. **每段都先 `trim()`**，且**空段连带分隔符一起省略** ——
 *    否则会出现「曲名 · 」或「曲名 · 作者 · 」这种尾巴。
 * 3. **专辑还受 `showAlbum` 门控**（`layout.noLyrics.showAlbum`，默认 `false`）——
 *    专辑名通常很长，会把这行**挤到被 `ellipsizeToWidth` 截断**。
 *
 * ## ⚠️ 为什么**必须 public**：`private` 和 `internal` 都不行
 *
 * 探针（`probe/TrackCreditLineProbe.kt`）是**独立编译单元** ——
 * `private` 让它看不见，`internal` 也一样看不见（`internal` 的可见域是
 * **同一个 module**，而 probe 源集虽然与本 module 共享 `main` 的输出，
 * 编译时却按**独立 Kotlin 模块**处理）。
 *
 * ★ 本项目**已经真机踩过一次**，教训原文在 `PreviewSnapshot.previewTargetMatches` 上方：
 * 那里原先是 `public`、`ColorPickerProbe` 绿着；某次改成 `internal` 的那一刻，
 * 那条回归护栏**直接编译不过**（`Cannot access 'fun previewTargetMatches(...)': it is internal in file.`）。
 * 后果**不是"少一条断言"，而是该探针整个编译失败 → `run_all_probes.sh` 整条挂掉**
 * （它排得很前，后面全部连坐）。
 *
 * ⇒ 需要"不对外"的**意图标注**就用本段 KDoc 说明，**不要**用编译器级可见性收窄。
 *   本函数的唯一外部消费者就是探针 —— 收窄买不到任何封装收益，只打死一条护栏。
 *
 * 同先例：[`previewFontLabel`] / [`borderFadeLayers`] / [`spectrumBarCornerRatio`]
 * （三者都是 public；其中 `previewFontLabel` 的 KDoc 里也写着同一条「不能改 internal」）。
 *
 * ## 边界
 *
 * - 曲名为空 ⇒ **不返回占位句**（占位句由调用方决定，见 `NO_LYRICS_PLACEHOLDER`）——
 *   本函数**只负责「有曲名时那一行」**，返回 `""` 表示"无曲名"。
 * - 作者 / 专辑为空 ⇒ 自动跳过（**不产生空段**）。
 * - 三段全空 ⇒ 返回 `""`。
 *
 * @param showAlbum 专辑段是否参与（来自 `layout.noLyrics.showAlbum`）。
 */
fun trackCreditLine(snap: TimelineSnapshot, showAlbum: Boolean): String {
    val title = snap.trackTitle.trim()
    if (title.isEmpty()) return ""
    val artist = snap.artist.trim()
    val album = snap.album.trim()
    return buildString {        append(title)
        if (artist.isNotEmpty()) append(" · ").append(artist)
        if (showAlbum && album.isNotEmpty()) append(" · ").append(album)
    }
}

/**
 * 把打包颜色（`0xAARRGGBB` 或 `0x00RRGGBB`）格式化成 `#RRGGBB` —— **只为日志**。
 *
 * ## 为什么必须是十六进制
 *
 * 取色诊断行（[IslandWindow.logColorSourceDiag]）要回答「这个颜色是不是出厂默认」。
 * 而 `cdl.json` 里所有颜色都是 `#RRGGBB` 字面量 —— 只有打印成同一种写法，
 * 人才能**逐字符对眼**，一眼看出「打印出来的就是默认值」。
 * 十进制三元组做不到这一点（要心算）。
 *
 * ## 为什么不用 `"%06X".format(...)`
 *
 * `String.format` 走 `java.util.Formatter`，受默认 `Locale` 影响；本项目已经因为
 * 「隐式 Locale」踩过一次坑（数字分组）。这里手写补零，**完全不碰 Locale**。
 * 只取低 24 位：本项目所有颜色都是不透明的，alpha 位在这里没有信息量。
 */
private fun hexRgb(packed: Int): String {
    val s = Integer.toHexString(packed and 0xFFFFFF).uppercase()
    return "#" + "0".repeat(6 - s.length) + s
}

// ── 常量（★ 2026-10-07 从 `IslandWindow.private companion object` 提到文件级）────
//
// ## 为什么提出来（`migration/100` · B1a）
//
// `private class Appearance`（本文件的嵌套类）要用这些常量，而它即将**外迁到
// `IslandAppearance.kt`**。原位置在 `IslandWindow` 的 `private companion object` 里 ——
// ★ **嵌套类能访问外层 private companion**（所以今天能编译），**但顶层类看不见**。
// ⇒ 提到文件级 `internal`（同 module 可见）⇒ 外迁后零调用点改动。
// ★ 同项目先例：`BACKGROUND_BASE_RGB` 也是为同一理由被提到 `IslandPolicy.kt` 顶层的。
// ★ 用 `internal` 而非 `public`：这 8 个是**内部 wire 值**，不该进公开 API 面
//   （探针也看不到 `internal` ⇒ 无泄露）。
//

/** `layout.translation.position` 的两个取值（与注册表 `options` 逐字一致）。 */
internal const val TRANSLATION_POSITION_BELOW = "BELOW"
internal const val TRANSLATION_POSITION_ABOVE = "ABOVE"

/** `spectrum.bar_pitch_mode` 的取值（与注册表 `options` 逐字一致）。 */
internal const val SPECTRUM_PITCH_AUTO = "AUTO"

// ★★ 2026-10-10（审计 v4 · B-2）：`KARAOKE_MODE_PER_CHAR` **已迁到 `dev.cdl.settings`**
//   （原为此处 `internal const val`）—— ★ 理由：预览侧也要判这一档，而它取不到
//   `IslandWindow` 的 `private companion object` ⇒ 迁到 wire 值常量的**唯一住处** ✓
//   ★ 本文件通过顶层 import 引用；**13 处调用点一行未改** ✓

/**
 * 副歌词字号 = 主字号 × **本值**。
 *
 * ## 0.7 → 0.64（2026-09-21，用户要求）
 *
 * 用户原话：「示例歌词**主副歌词比例不佳**。」
 *
 * 0.7 是为对齐原实现 0.8.9 的 `fontSize * .7f` 而来的，但在
 * **主歌词有描边、译文无描边**的实际观感下，两者的**视觉重量**过于接近 ——
 * 描边让主行偏细，于是 0.7 的译文看起来几乎与主行同大，**主次不分**。
 *
 * 取 **0.64**：仍高于字幕惯例下限（约 0.6），但让主行明确成为视觉焦点。
 *
 * ⚠️ **必须与预览侧的 `IslandPreviewPane.PREVIEW_TRANSLATION_SCALE` 同值** ——
 * 预览的全部意义就是"所见即真机所示"；两者不同值，用户会在预览里调好一套、
 * 到真机上却是另一套，**而他没有任何线索知道是预览在骗他**。
 */
internal const val TRANSLATION_SCALE = 0.64f

/**
 * `spectrum.color` 的**默认值**（`#84D8BC`，青绿；打包 ARGB）。
 *
 * ## ⚠️ 2026-09-17 修订：从"唯一的颜色"变成"默认值"
 *
 * **原记**（当时的名字是 `MANUAL_SPECTRUM_COLOR: Color`）：
 * 注册表里 `spectrum.*` **只有 `enabled` 与 `color_mode` 两项**，
 * 没有独立的颜色键，故手动档只能是硬编码常量。
 *
 * **实为**（用户原话）：「**自行设定颜色也没做到**」——
 * 补了 `spectrum.color`（COLOR）之后，本常量降级为**该键的默认值**。
 * 取值不变（与原实现 `IslandPalette` 的频谱默认色一致），
 * 故**开箱观感零变化** —— 只是从"用户改不了"变成"能改"。
 *
 * ⚠️ 类型从 `Color` 改成 `Int`（打包 ARGB）：`Appearance.from` 里的
 * `color(key, fallbackInt)` 助手收的是打包值（与其余颜色项同一约定），
 * 多一层 `Color` ↔ `Int` 转换只会多一处出错的地方。
 */
internal val DEFAULT_SPECTRUM_COLOR: Int = 0xFF84D8BC.toInt()

/**
 * `spectrum.anchor` 的取值之一：**贴歌词区底边**（默认）。
 *
 * 抽成常量而不是到处写 `"BOTTOM"`：它与 `spectrum.anchor` 的注册表
 * `options` 里的 wire 值**必须逐字一致**，散在多处时改一处漏一处不会报错，
 * 只会表现为"选了贴底边却按贴顶边画"。
 */
internal const val SPECTRUM_ANCHOR_BOTTOM: String = "BOTTOM"

/**
 * ★★★ 2026-10-13（**BUG-191**）：切歌空窗期**保持上一次主色**的宽限窗（毫秒）。
 *
 * 用户报障（逐字）：「**切歌一瞬间频谱色会回到默认再转下一首的颜色**」——
 * 根因是新封面未到时 `coverRgb` 为 `null` ⇒ 静默回退 `manual`（默认色）✗
 * （完整时序见 `IslandPanel.coverRgbHold` 的 KDoc）。
 *
 * ## 为什么取 1800 ms
 *
 * 「本曲确实没有封面」与「封面还在加载」在**单帧里无法区分** ⇒ 只能给一个**时间窗**：
 * · **太短** ⇒ 慢一点的封面还没到就回退了 ⇒ 默认色照样闪 ✗
 * · **太长** ⇒ 无封面的曲子会长时间挂着**上一首的颜色**（误导）✗
 *
 * 1800 ms 覆盖常见封面加载耗时（本地读图 + 主色提取），
 * 又不至于让"无封面曲"长时间显示别人的颜色 ✓
 *
 * ★ 探针断言：`CoverColorHoldProbe`（窗口边界两侧的行为）✓
 */
const val COVER_RGB_HOLD_GRACE_MS: Long = 1800L

/**
 * ★★★ 2026-10-13（**BUG-191**）：**空窗期是否沿用上一次的主色** —— 唯一判据。
 *
 * ★ **纯函数**（不读任何字段）⇒ 探针可直接喂三组值断言，不必"跑起来再看" ✓
 * （与 `spectrumAlphas` / `playbackProgress` 同一条纪律。）
 *
 * | 情形 | 返回 |
 * |---|---|
 * | 从未取到过主色（`held == null`） | `null` ⇒ 认账"本曲无封面"，按 `manual` 渲染 ✓ |
 * | 宽限窗内（`age <= COVER_RGB_HOLD_GRACE_MS`） | ★ **`held`**（盖住封面加载期）✓ |
 * | 超出宽限窗 | `null` ⇒ 回退 `manual` ✓ |
 * | 时钟回拨（`age < 0`） | ★ **`held`**（当作"刚取到"，宁可保持也不闪）✓ |
 *
 * @param held 上一次真正取到的主色（跨曲目保留）
 * @param heldAtMs [held] 的刷新时刻（`System.currentTimeMillis()`）
 * @param nowMs 当前时刻
 */
fun coverRgbHoldOrNull(held: Int?, heldAtMs: Long, nowMs: Long, stillWaiting: Boolean): Int? {
    if (held == null) return null
    return if (coverHoldActive(heldAtMs, nowMs, stillWaiting)) held else null
}

/**
 * ★★★ 2026-10-14（**BUG-194**）：**"空窗期保持"是否生效** —— 主色与调色板**共用**的唯一判据。
 *
 * ## ★★★ 用户对上一版（BUG-191/193）的指正（逐字）
 *
 * > 「**我想说的是，你做的衔接还是没衔接好，仍会漏基色。**」
 *
 * ★ 他**说得对**：上一版的判据是「`now − heldAt <= 1800 ms`」——
 *   ★★ 那是一条**时间启发式**：封面加载慢于 1800 ms 就**照样漏基色** ✗
 *
 * ## 修法：主判据换成**结构性信号**，时间窗降为**兜底**
 *
 * `coverImageFor` 里有一个字段 **`coverPending`**，语义是
 * 「**本曲的封面还没定论**」—— ★ 且它有**时间截止期**（`coverWaitDeadlineNanos`，
 * 2026-09-28 修的「播放中重试计数永不增长」那条）⇒ ★★ **它保证一定会变成 `false`**
 *   ⇒ ★ 它是一条**结构性**信号（"还在等"），**不是**启发式 ✓
 *
 * | `stillWaiting` | 结果 |
 * |---|---|
 * | `true`（★ **本曲封面还没定论**） | ★ **无限保持** —— ★ 封面还没到，凭什么断言"没有多色"？ |
 * | `false`（已定论：图到了 / 截止期到了） | 落在**宽限窗**内才保持；窗外 ⇒ 认账（本曲确实没有） |
 *
 * ⇒ ★ 于是"**漏基色**"这件事**只在"本曲确实没有封面/没有多色"时发生** ✓ ——
 *   而那正是**正确**的渲染（没有多色可用，只能显示基色）✓
 *
 * ⚠️ 宽限窗（[COVER_RGB_HOLD_GRACE_MS]）**保留**为兜底：它盖住
 * 「图到了但调色板 < 2 色」（纯黑白封面）与"时钟抖动"这两种边角 ✓
 *
 * @param stillWaiting ★ 本曲封面**还没定论**（= `coverPending`）—— **主判据**
 */
fun coverHoldActive(heldAtMs: Long, nowMs: Long, stillWaiting: Boolean): Boolean {
    // ★ 主判据：还在等 ⇒ 无限保持（结构性）✓
    if (stillWaiting) return true
    return withinCoverHoldGrace(heldAtMs, nowMs)
}

/**
 * ★★★ 2026-10-14（**BUG-193**）：**宽限窗判据** —— 主色与调色板**共用**的唯一实现。
 *
 * ★ 抽出来是为了避免"两个消费者各写一遍窗口判断"（本项目最高频的缺陷形态）✓
 *
 * @return `true` ⇒ 仍在宽限窗内（应保持旧值）；`false` ⇒ 认账"新资源确实没有"
 */
fun withinCoverHoldGrace(heldAtMs: Long, nowMs: Long): Boolean {
    val age = nowMs - heldAtMs
    // ★ 时钟回拨（`age < 0`）当作"刚取到"：宁可多保持一会儿，也不要在这一帧闪回 ✗
    if (age < 0L) return true
    return age <= COVER_RGB_HOLD_GRACE_MS
}

/**
 * ★★★ 2026-10-14（**BUG-193**）：**空窗期是否沿用上一次的多色调色板** —— 唯一判据。
 *
 * ## 用户报障（逐字）
 *
 * > 「**还是有那个自选色出来**（前面我说错了，默认频谱的色可以被自选色更改，
 * >   所以显示的其实是被改过的色），**现在其实是流光多色叠加在自选色之上？**」
 *
 * ## 根因（★ 与主色**同一个形态**，但**不是同一条路径**）
 *
 * 用户的 `spectrum.color_mode = **cover_flow**`（流光多色）。而流光的取色函数
 * `spectrumFlowColorAt` 有一条**退化判据**：`palette.size < 2 ⇒ 返回 baseArgb` ✓
 * ⇒ ★ **调色板为空时，整条频谱退回"基色"** —— 而基色 = `spectrum.color` = **用户的自选色** ✗
 *
 * 而调色板的粘性（`stickyPalettePath`）**也是按曲目**的 ⇒ 切歌时返回**空表** ✗
 * ⇒ ★ 用户看到的就是「**自选色**」而不是"叠加" ✓（★ 回答用户那句提问：**不是叠加，是退回**）
 *
 * ## 修法：与主色**同一条**「保持 + 宽限 + 交叉淡化」
 *
 * ★ 主色（[coverRgbHoldOrNull]）上一批已修；本处**补齐同一条**给调色板 ✓
 *
 * ## ⚠️ 与既有 KDoc 的一处**有意更正**
 *
 * `coverPaletteFor` 的 KDoc 原本写着「**不**照 `stickyCoverColorPath` 那套做按曲目粘住」，
 * 理由是「多色是**大面积填充** ⇒ 若粘住，会出现"封面已经换了、整块背景还是上一首的颜色"，
 * 比主色闪烁**更显眼**」。
 *
 * ★★ **那条理由针对的是"无过渡的粘住"** —— 而本批的修法是
 * **保持（仅限加载空窗）+ 新调色板到达时逐色交叉淡化 260 ms**：
 * 空窗结束后**必然**在 260 ms 内过渡到新色 ⇒ ★ "封面已换、颜色还是旧的"这个**稳态**
 * **不存在**（只存在一段过渡）⇒ 原顾虑**不成立** ✓
 *
 * @param held 上一次取到的**有效**调色板（`size >= 2`；`< 2` 视为"没有"）
 * @return 应沿用的调色板；`emptyList()` ⇒ 认账"本曲确实取不到多色"，走退化路径
 */
fun coverPaletteHoldOrEmpty(
    held: List<Int>,
    heldAtMs: Long,
    nowMs: Long,
    stillWaiting: Boolean,
): List<Int> {
    if (held.size < 2) return emptyList()
    return if (coverHoldActive(heldAtMs, nowMs, stillWaiting)) held else emptyList()
}

/**
 * 跟随封面时**未唱歌词色**相对已唱色的亮度系数。
 *
 * 取 0.5：与手动档默认色的对比度量级一致（`#FFFFFF` 已唱 vs `#7E818A` 未唱，
 * 三通道比值约 0.49–0.54）。用一个与既有默认值同量级的系数，
 * 用户从 `manual` 切到 `cover` 时"高亮的对比感"不会突然变化 ——
 * 变的只是色调，不是"能不能看出唱到哪了"。
 *
 * ⚠️ 与译文的 [UNSUNG_TRANSLATION_DIM]（0.62）**取值不同是有意的**：
 * 译文行本身比正文小 30%（[TRANSLATION_SCALE]），再压到 0.5 会糊成一团。
 * 两者是不同元素的对比度参数，不要"为了统一"改成同一个数。
 */
internal const val UNSUNG_LYRIC_DIM = 0.5f

/**
 * 词岛悬浮窗 —— 无边框、透明、置顶、可鼠标穿透、可拖动的桌面歌词窗。
 *
 * ## 为什么是 `JWindow` 而不是 `JFrame`
 *
 * `JFrame` 会带系统边框与非客户区（标题栏、阴影），即使 `setUndecorated(true)`
 * 在某些 Windows 主题下仍有残留的 DWM 阴影/圆角处理，且任务栏会多一个条目。
 * `JWindow` 是**没有装饰器、没有任务栏条目**的顶层容器 —— 正是「悬浮件」需要的形态。
 * 代价：`JWindow` 在部分平台上**默认不支持 per-pixel 半透明**，必须满足两个前提才能
 * 真正透明，见下面的「透明三前提」。
 *
 * ## 透明三前提（缺一个就会看到黑底）
 *
 * 1. `isUndecorated` 天然为 true（`JWindow` 无装饰）；
 * 2. `background = Color(0, 0, 0, 0)` —— **必须显式设全透明色**。Swing 默认的
 *    `Window` 背景是不透明色，不设它时「透明区」会被画成那个颜色；
 * 3. **图形环境必须支持 per-pixel translucency**
 *    （`GraphicsEnvironment.isTranslucencySupported(PERPIXEL_TRANSLUCENT)`）。
 *    不满足时（老显卡驱动、远程桌面会话、部分虚拟机）窗口会退化成不透明 ——
 *    因此 [IslandWindow.start] 检测到不支持时会**给窗口一个可读的深色底**，
 *    而不是让用户看到一个「本应透明却全黑」的方块。这是有意的降级。
 *
 * ## 为什么内容面板自绘而不是摆 `JLabel`
 *
 * 三个理由，缺一都足以否掉 `JLabel`：
 * ① **圆角 + 渐变背景**：`JLabel` 是矩形不透明组件，"圆角"要靠 4 个角贴同色小块、
 *    或让父容器透明再用 `paintComponent` 画 —— 绕一圈还是回到自绘；
 * ② **半透明叠加**：背景不透明度是**在自绘时乘进 alpha 的**
 *    （见 [IslandPanel.paintComponent]），`JLabel` 只能整组件设一种不透明度；
 * ③ **逐字高亮的将来需求**：歌词要按时间轴逐字上色，那是「一个字符串画两遍、
 *    第二遍用 clip 裁到进度位置」——`JLabel` 的 `paint` 不支持这种分层绘制。
 *
 * ⚠️ 2026-09-24 更正：**「逐字高亮」早已实现**（[KaraokeTextRenderer] 逐字上色 + clip 进度）。
 *    原文写「现在只画一行占位文字（P1 阶段无时间轴）」—— 那是**首版口径**，不成立。
 *
 * ## 线程纪律（项目硬约束）
 *
 * 宿主回调线程 `CdlRuntime.onPlaybackEvent` **不在 EDT 上**，
 * 那里绝不能碰 Swing 组件。本类的所有公开入口因此都做了两件事之一：
 * ① 若已在 EDT 就直接执行；② 否则 `SwingUtilities.invokeLater` 投递 —— 见 [onEdt]。
 * 这**不是**「保险起见加一层」：Swing 组件与 `Window` 的多数方法是**非线程安全**的，
 * 从非 EDT 触碰会有概率性的、难以复现的渲染损坏与死锁，
 * 且这类 bug 在开发机上通常看不到（时序不同）—— 属于「发布后才炸」的类型。
 *
 * ## 与设置系统的关系
 *
 * 本类对**渲染相关键**只读（[SettingsRuntime.live]），其写入一律由 [IslandPopupMenu] 负责。
 * 这样做的理由是避免「窗口自己改设置 → 订阅回调又改窗口」的回路 ——
 * 让窗口成为**纯消费者**，方向单一，回路在结构上就不存在。
 *
 * ★★★ 2026-10-07 **更正（`migration/98` · C-5）**：本段原写「本类**只读**设置…
 * **写入一律由** `IslandPopupMenu` 负责」—— ★ **那个不变量是假的**：
 * 本文件自己也有写入点（`persistPosition` 的 `runtime.apply(mapOf("window.position" to …))`，
 * 以及拖拽/滑杆的 `settings?.publish/apply(delta)`）⇒ 故本句收窄为
 * 「**渲染相关键**只读」—— ★ 这样它才是**能被核查**的陈述（渲染键的写入点确实只有菜单）。
 * 订阅返回的 `AutoCloseable` 必须持有并在 [stop] 里关掉，否则插件卸载后
 * 设置系统仍持有指向已销毁窗口的 lambda（真实内存泄漏）。
 */

internal class IslandWindow(
    /** 设置系统。为 `null` 时（插件启动时设置系统就坏了）走全默认值运行。 */
    private val settings: SettingsRuntime?,
    /**
     * 快照供给：渲染线程每帧调用，取回时间轴的**不可变快照**。
     *
     * ## 为什么传 lambda 而不是直接持有 `PlaybackTimeline`
     *
     * 两个理由，各自独立成立：
     * ① **可见性**：`CdlRuntime.timeline` 是 `internal var ... private set`
     *    （见其 KDoc），外部只能读不能写。传一个 lambda 让窗口只获得"读"能力，
     *    与那个 `private set` 表达的意图一致 —— 若传 timeline 实例本身，
     *    窗口就拿到了 `trackChanged` / `seek` 等**写入方法**的入口，
     *    那些方法只允许宿主回调线程调用，从 EDT 误调会破坏时间轴的状态契约。
     * ② **依赖方向**：`window/` 只需知道 `TimelineSnapshot` 这个值类型，
     *    不必依赖 `PlaybackTimeline` 的全部 API。以后时间轴的读接口若要
     *    换实现（如换成共享内存的无锁环形缓冲），窗口侧一行都不用改。
     *
     * 由组合根保证：时间轴缺失（启动降级）时返回 `TimelineSnapshot.EMPTY`
     * 而非抛异常 —— 于是渲染层**永远不必处理 null 时间轴**，
     * 这是把"没有数据"统一成一种正常状态（见 `TimelineSnapshot.EMPTY` 的注释）。
     */
    private val snapshotSupplier: () -> TimelineSnapshot,
    /**
     * 播放控件的**写操作**入口（上一首 / 播放 / 暂停 / 下一首 / seek）。
     *
     * 默认 [PlaybackActions.NONE]（点了没反应）而不是必填：本类的构造点在组合根，
     * 而组合根在"宿主 API 不可用"时会走降级路径 —— 那时**词岛仍应显示**，
     * 只是控件点了不生效。让参数可选，降级路径就不必构造一个假实现。
     *
     * 为什么是接口而不是直接持有宿主 API：见 [PlaybackActions] 的类 KDoc（依赖方向 / 语义收敛 / 可降级）。
     */
    private val playbackActions: PlaybackActions = PlaybackActions.NONE,
    /**
     * 当前曲目的**总时长**（毫秒）；`0` = 未知。
     *
     * ## 为什么是个可注入的 lambda，而不是在窗口里自己去取
     *
     * 时长**当前无来源**（这是首版的已知缺口，不是遗漏）：
     * 宿主 API 没有时长字段，0.8.9 是自己读本地音频文件拿的
     * （`platform/LocalTrackMetadata.kt` + Jaudiotagger）。本轮明确不新增文件读取，
     * 故默认 `{ 0L }` —— 进度条照画轨道与已播时间，但不画填充（见
     * `PlaybackControls.paintProgress` 的说明：0/0 的填充是假信息）。
     *
     * 留成 lambda 的目的是**把接入点收敛到一处**：将来接上元数据读取后，
     * 只需在组合根把这个参数换成 `{ metadata.durationMs }`，控件层一行都不用改。
     */
    private val durationSupplier: () -> Long = { 0L },
    /**
     * 频谱数据来源（`platform/ProcessSpectrum` 的最新一组频段电平）。`null` = 暂无数据。
     *
     * ## 为什么是"每帧读一次"的 lambda，而不是推送
     *
     * 与 [durationSupplier] 同一个理由：渲染层是**纯消费者**，方向单一。
     * 数据源（进程回环助手）在后台线程更新，渲染层在 EDT 上读一个
     * `AtomicReference` —— **无锁、无 I/O、不阻塞帧**。
     *
     * ## 为什么 `null` 是常态而不是错误
     *
     * 三种情况都会给出 `null`：助手未启动（`spectrum.enabled` 关）、
     * 已启动但还没 `READY`、助手挂了。渲染层对三者的处理**完全相同**：
     * **不画频谱**。把"没有数据"统一成一种正常状态，渲染层就不必处理异常
     * ——这与 `TimelineSnapshot.EMPTY` 的设计取向一致。
     *
     * ## 生命周期
     *
     * 进程由组合根持有并关闭（见 `CdlRuntime`），本类**不**碰它的生命周期。
     */
    private val spectrumBandsSupplier: () -> FloatArray? = { null },
    /**
     * 专辑封面来源（`cover/CoverArtProvider`）。`null` = 不提供封面（降级）。
     *
     * ## 为什么是可空、且默认 `null`
     *
     * 与 [playbackActions] 同一个理由：构造点在组合根，而组合根有"宿主 API 不可用"
     * 的降级路径 —— 那时词岛仍应显示，只是没有封面。做成可空，降级路径就不必
     * 构造一个假实现（假实现会让"没有封面"与"有封面但读失败"两种状态混在一起）。
     *
     * ⚠️ **生命周期由组合根负责**：本类**不**关闭它（它持有后台线程，
     * 关闭时机与插件卸载绑定，见 `CdlRuntime.close()`）。
     * 若本类去关，窗口重建（设置系统重载）后 provider 就废了。
     *
     * ## 契约（写在这里以免调用方误判）
     *
     * `coverFor` **永不阻塞**，且**首次调用必返回 `null`**（图片在后续调用中出现）。
     * 渲染层必须把 `null` 当作"暂无封面"而不是错误 —— 见 [coverImageFor]。
     */
    private val coverArtProvider: CoverArtProvider? = null,
    /**
     * `island.enabled` 变化时的通知（可选）。当前为 `null` —— 显隐由本类自己按
     * 设置快照处理（见 [IslandPanel.paintComponent] 与 [applyVisibility]），
     * 留这个口子是给将来"宿主也有个开关要跟着刷新"的场景，
     * 不想在那时改构造函数签名而牵动所有调用点。
     */
    private val onEnabledChanged: ((Boolean) -> Unit)? = null,
    /**
     * **用户可见提示通道**（toast）。`null` = 静默（降级路径）。
     *
     * ## 为什么是注入的 lambda，而不是在窗口里直接调 `WorkshopApi`
     *
     * 分层约定：`window/` **不碰 `WorkshopApi`**（宿主的创意工坊 API 只在
     * `host/` 与 `settingsui/` 的注入点出现）。窗口要发提示时，走这条注入的
     * lambda，由组合根 [dev.cdl.host.CdlRuntime] 接到
     * `WorkshopApi.ui.toast(...)` —— 与 `showPresetManagerDialog` 的 toast 通道
     * 是**同一条**（见 `CdlRuntime.notifyUser`）。
     *
     * ## 当前唯一的用途：L3（真透视）的降级告警
     *
     * `SelfCaptureGate` 连续 `K=3` 帧判定"截到了自己"时，必须**让用户看得见**
     * —— 因为那意味着"磨砂背景"这个功能实质上失效了（回落到纯色档）。
     * 宿主对插件的 `stderr` 是黑洞（见 `CdlRuntime` 启动失败处的注释），
     * 故这里**必须**走 toast，不能只打日志。
     *
     * ## 为什么可空且默认 `null`
     *
     * 与 [playbackActions] / [coverArtProvider] 同一理由：构造点在组合根，
     * 而组合根在"宿主 API 不可用"时会走降级路径 —— 那时词岛仍应显示，
     * 只是提示发不出去。做成可空，降级路径就不必构造假实现。
     */
    private val toastSink: ((String) -> Unit)? = null,
) : AutoCloseable {

    /**
     * 窗口标题，用于 [WindowInterop.findHwndByTitle] 定位 HWND。
     *
     * ⚠️ 必须**足够独特**：`findHwndByTitle` 是精确匹配标题，
     * 若与宿主或别的插件的窗口重名，就会拿到错误的 HWND 去改样式（会污染别的窗口）。
     * 故带上一个几乎不可能撞车的后缀。
     */
    private val windowTitle = "CDL Lyric Window"

    /** 顶层窗口。`JWindow` 无装饰、不进任务栏。 */
    private val window: JWindow = object : JWindow() {}

    /**
     * 最近一次应用的快照。渲染与拖动判定都读它，避免每帧都从设置系统取。
     *
     * ⚠️ **声明顺序是硬约束，不要把它挪到 [panel] 后面。**
     *
     * Kotlin 的属性初始化器按**声明顺序**执行。`IslandPanel` 在**构造期**就会读本字段
     * （经 `IslandPanel.appearance` → `Appearance.from(..., fresh = snapshot)`），
     * 因此本字段**必须先于** `panel` 完成赋值。
     *
     * 曾经把它放在 `panel` 之后 → `panel` 构造时读到 `null` → 抛
     * `NullPointerException: Parameter specified as non-null is null:
     * ...Appearance$Companion.from, parameter fresh` →
     * 被组合根的 `runCatching` 静默降级 → **词岛窗口完全不出现，且 100% 必现**。
     *
     * 取证：`recon/21-真机失败复核.md`（真机日志 `super-island.log` +
     * `javap` 字节码偏移：`<init>` 在 offset 46–54 构造 `panel`，offset 80 才 `putfield snapshot`）。
     */
    @Volatile
    private var snapshot: SettingsSnapshot = settings?.live ?: SettingsSnapshot.defaults()

    /** 自绘内容面板。**构造期依赖 [snapshot] 已初始化**，故必须声明在其后。 */
    private val panel = IslandPanel()

    /** 缓存 WM 句柄。`0L` = 尚未取得或取得失败。**只在 EDT 上写。** */
    @Volatile
    private var hwnd: Long = 0L

    /** 设置订阅句柄，`close()` 时必须反注册。 */
    private var settingsSubscription: AutoCloseable? = null

    /** 拖动起点（鼠标按下时的屏幕坐标）。`null` = 当前没在拖。 */
    private var dragOrigin: Point? = null

    /** 按下时窗口左上角的屏幕坐标，与 [dragOrigin] 相减得到位移。 */
    private var dragWindowOrigin: Point? = null

    /**
     * **临时提顶是否生效中**（2026-09-21 新增；2026-09-30 语义两次变更）。
     *
     * ## 语义（★ 现在 = "用户操作过词岛 / 找回词岛，我们临时把它提到 topmost"）
     *
     * 它是"临时 topmost **生效中**"这件事的**唯一真源**。
     *
     * | 时间 | 谁置位 / 复位 |
     * |---|---|
     * | 2026-09-21 – 09-29 | 只在 `mouseDragged` **第一帧**置位；`mouseReleased` 复位 |
     * | 2026-09-30（F-4′） | ★ 改成 `mousePressed` 置位（**点击也提顶**）；复位走 [maintainTempTopmost] —— **焦点离开本 App 时才复位** |
     * | 2026-09-30 16:3x（诉求 ㉚ 首版） | ★ 找回词岛**也**置位；复位改到 `mousePressed` —— 我把它读成了"**点词岛**才释放" |
     * | **2026-10-01 01:2x（用户纠正 · 现行）** | ★★ **回退到 F-4′ 判据** —— 用户澄清「我说的是**点击词岛外部**就释放，不是点击词岛」⇒ 点词岛 **保持**、点外面 **释放**；`mousePressed` **不再是释放入口** |
     *
     * ★★ **一次"我理解反了"的记录（值得留）**：
     * ㉚ 首版我把「点了才释放」读成"**点词岛**才释放"，把释放入口放进 `mousePressed` ——
     * **正好反了**。用户 2026-10-01 01:2x 澄清后，正确语义 = **前台判据**
     * （[shouldHoldTempTopmost]）：用户点到别处（别的应用 / 桌面 / 任务栏）⇒
     * 前台不再属于本进程 ⇒ **释放**；用户点词岛 ⇒ 词岛成为前台（**仍是本进程**）⇒ **保持**。
     * ★ 教训：**「点了才释放」这句话没有说"点哪儿"** —— 我按"点词岛"实现，
     * 恰好与用户意图相反。⇒ 措辞有歧义时，**先问落点，再动手**。
     *
     * ## 为什么必须去重
     *
     * `mouseDragged` 每帧都会触发，而 `SetWindowPos` 是系统调用 ——
     * 每帧调不只是浪费，还会让窗口在 Z 序里被反复重排（部分显卡驱动上**可见闪烁**）。
     * 见 [raiseToTopForDrag] 的 KDoc。
     *
     * ## 为什么标志位不能漏复位（本项目踩过）
     *
     * 若置位后没复位 ⇒ ① 下一次 `if (!dragTopRaised)` 恒假 ⇒ **永久不再提顶**；
     * ② 窗口**卡在 topmost**（用户的"取消置顶"看起来失效）。
     * 这正是 F-2 修过的形态；F-4′ 又给它加了一层看门狗兜底（窗口外松手也能收）。
     */
    private var dragTopRaised = false

    /** 是否已启动（防 `start` 重入 / `close` 后误操作）。 */
    @Volatile
    private var started = false

    // ── 渲染资源（长生命周期，绝不每帧重建） ────────────────────────────────

    /**
     * 渲染期对象复用容器。**由本类持有，与窗口同生命周期。**
     *
     * ## 为什么必须是字段而不是在 `paintComponent` 里 `RenderCache()`
     *
     * `RenderCache` 的全部价值在于"跨调用复用"。它缓存的东西各有实测代价：
     * - `TextLayout` 构造 **0.0234 ms/次** —— 60 fps 下每帧重建就是白烧 1.4 ms
     *   （占 16.6 ms 预算的 8.5%），而一行 40 字的歌词若逐字建更是 0.94 ms；
     * - `getOutline` **0.0712 ms/次**（比 TextLayout 还贵 3 倍）；
     * - `getLogicalHighlightShape` 0.0038 ms/次。
     *
     * 若每帧 new 一个，这些缓存**命中率恒为 0** —— 那等于用着 `KaraokeTextRenderer`
     * 的绘制路径，却把它赖以成立的 27× 提速前提（见该类的 KDoc）整个丢掉，
     * 只留下更复杂的代码。故缓存的所有权必须比一次绘制调用**长**，
     * 天然落点就是窗口自身。
     *
     * ## 线程纪律
     * `RenderCache` 约定**只在 EDT 上使用**（`Graphics2D` 本身就非线程安全，
     * 其配套缓存没有理由反过来支持并发）。本类所有触碰它的路径都在 EDT 上：
     * `paintComponent` 天然在 EDT，帧驱动用 `javax.swing.Timer`（回调也在 EDT）。
     */
    private val renderCache = RenderCache()

    /**
     * 频谱绘制的**平滑状态**。由窗口持有，与窗口同生命周期。
     *
     * ## 为什么不由 `SpectralPainter` 自己持有
     *
     * `SpectralPainter.paint` 是**有状态**的：显示值向目标值逐帧逼近（见它的 `smooth`）。
     * 而"上次画的是什么"属于**调用方**的知识 —— 同一个 painter 可能被多处调用，
     * 每处都该有自己的平滑进度。这与 [renderCache] 的归属理由是同一个。
     *
     * ## 线程纪律
     *
     * **只在 EDT 上使用** —— 与 [renderCache] 同（绘制路径天然在 EDT）。
     */
    private val spectrumCache = SpectralPainter.SpectrumCache()

    // ★ B2a（2026-10-08）：原先这里有 **11 个** 渐变笔刷缓存字段
    //   （`backgroundSpectrumPaint` / `*GeomKey` / `*ColorKey` / `*AlphaKey` ·
    //    `backgroundSpectrumProgress*` ×4 · `solidProgressPaint*` ×3）。
    //
    //   它们**只被 `IslandPanel` 的 3 个笔刷构造函数读写**（实测：该 panel 其余
    //   37 个成员函数一次都不碰）⇒ 不是"跨函数耦合"，而是**那 3 个函数自己的一组
    //   状态**被错放在外层类上（`tools/measure_outer_refs.py` 据此把它们算成
    //   "隐式外层引用"＝拆分阻力，而那是**假阻力**）。
    //
    //   ⇒ 已收进 `LinearBrushMemo`（状态随函数走）。
    //     外层引用数 **33 → 22**（`migration/110` §4 · `migration/111`）。
    //
    //   ⚠️ `spectrumCache`（上面那个）**不在本批**：它另被
    //      `IslandPanel.needsAnimationFrames` 使用（跨 2 个函数），不属于"只此一处"。

    /**
     * **实测的自然宽度**（内容实际需要多宽），由绘制路径每帧测量并回填。
     *
     * ## 它服务于 `layout.fixed_width = false`
     *
     * 原实现语义（`recon/16` 第 15 项 + 原实现 `IslandLyricsLayout.kt:38`）：
     * `if (settings.fixedWidth) limit else natural` ——
     * `true` 固定为 `max_width`，`false` **随歌词长度收缩**。
     *
     * ## 为什么必须由绘制路径回填（而不是在 `applyGeometry` 里算）
     *
     * 算"内容需要多宽"必须有**已塑形的文本**，而文本布局住在绘制路径
     * （`renderCache.textLayoutFor`）。`applyGeometry` 只有设置，没有文本 ——
     * 在那里硬算只能得到一个与真实排版无关的数。
     *
     * ## `0` 的语义 = "还没测过"
     *
     * 首帧之前它是 0，此时 `applyGeometry` **回退到 `max_width`** ——
     * 否则会以 0 宽建窗，看起来像"闪一下"。
     *
     * ## 线程
     *
     * 只在 EDT 上读写（绘制路径与 `applyGeometry` 都在 EDT），故用 `@Volatile`
     * 只是为了防御性的可见性，不是并发设计。
     */
    @Volatile
    private var naturalWidthPx: Int = 0

    /** 逐字高亮渲染器。无状态（除内部的字形簇缓存，其键含文本），故单例复用即可。 */
    private val karaokeRenderer = KaraokeTextRenderer()

    // ── 控件层（悬停展开 + 播放控件 + 进度条）──────────────────────────────

    /**
     * 播放控件的几何与绘制。**无状态**（除常量），故单例复用。
     *
     * ⚠️ 它**不持有展开态** —— 展开态的唯一来源是 [hover]。曾经考虑过让控件自己
     * 记住"我现在展开了吗"，那会造成"两份状态"：窗口按一份尺寸排、控件按另一份画，
     * 表现是"控件条画在窗口外面"。故所有布局都要求调用方显式传入 `expanded`。
     */
    private val controls = PlaybackControls()

    /**
     * 悬停状态机（展开/收起 + 两个延迟 + 自动隐藏）。
     *
     * 与 [panel] 的关系：**面板只读它、不改它**（`IslandPanel` 通过外层引用读 `hover.expanded`），
     * 这样"什么时候展开"只有一个决策点，不存在面板与窗口各判一次的可能。
     */
    private val hover = HoverState()

    /** 字形命中测试器。无状态纯查询，见其类 KDoc。 */
    private val glyphHitTester = GlyphHitTester()

    /**
     * 悬停定时器。**按需启停**，不是常驻。
     *
     * ## 为什么必须有它（而不是只在鼠标事件里判延迟）
     *
     * `enterDelayMs` / `exitDelayMs` 是**时间的推移**，而"鼠标稳稳停在字上不动"
     * 这件事**不产生事件**。没有定时器就永远等不到"延迟到点"，
     * 表现为：鼠标停在字上，控件永远不弹出。
     *
     * ## 为什么是"按需"而不是常驻（与 `syncFrameDriver` 同一考虑）
     *
     * 判据见 [HoverState.needsTicker]：只有"正在等某个延迟"时才需要跑。
     * 状态稳定时（收起且无待判定 / 展开且鼠标在岛内且未开自动隐藏）停掉，
     * 让 CPU 能进入深度空闲。这与帧驱动"暂停时停掉定时器"是同一取舍。
     *
     * 间隔取 [HOVER_TICK_MS] = 40 ms：延迟量级是 100 ms 以上（注册表默认 120/250），
     * 40 ms 的量化误差（最多晚 40 ms 弹出）远低于可辨阈值，而 25 Hz 的唤醒频率
     * 只在"正在过渡"的短暂窗口内发生。
     */
    private var hoverTimer: Timer? = null

    /** 最近一次鼠标事件算出的"是否命中字形"。定时器 tick 复用它（tick 时没有鼠标位置）。 */
    private var lastHitGlyph: Boolean = false

    /** 最近一次鼠标事件算出的"是否在词岛内"。 */
    private var lastInside: Boolean = false

    // ── 控件带（`controls`）的**动画高度占比** ────────────────────────────────
    //
    // ## 为什么需要它（BUG-123：上方控件弹出/收回时"词岛上下闪动"，用户第三次报）
    //
    // ### 原状（两处**瞬时跳变**，各一次）
    //
    // 控件条展开时 `applyWindowSize` 把窗口**一次性**加高一个 `stripHeight`
    // （`position = TOP` 时还会整体上移同样多，好让歌词在屏幕上不动）。
    // 而**岛体（背景形状 + 边框）是铺满整个窗口的**（`buildShape(local, width, height)`）——
    // 于是窗口一变大，用户看到的"词岛"**在那一帧瞬间长高一条**，
    // 而控件条本身却在之后 180 ms 里慢慢淡入。收起方向同理（且**完全没有动画**：
    // `HoverState` 在收起时把 `expansion` 直接归零，见其 KDoc"收起时窗口已经变矮"）。
    //
    // ⇒ 用户看到的是：**词岛整体"啪"地向上跳一格 → 一会儿又"啪"地向下跳回来**。
    //    这正是"上下闪动"。**它与窗口 setBounds 的调用次数无关**（那条路径本来就只有
    //    每次展开/收起各一次），根因是"**尺寸跳变发生在动画之外**"。
    //
    // ### 修法：把"控件带"从"窗口尺寸"里拆出来，做成一个**连续的绘制量**
    //
    // | 量 | 谁来动 | 什么时候动 |
    // | --- | --- | --- |
    // | 窗口 bounds | `applyWindowSize`（每次过渡**恰好一次**） | 展开：动画**开始前**；收起：动画**结束后** |
    // | 控件带高度 `stripHeight × bandFraction` | 本字段（逐帧） | 整段动画期间 |
    //
    // 于是：
    //   · 展开的第一帧，窗口已经到位、而 `bandFraction = 0` ⇒ 岛体形状**与收起态逐像素相同**；
    //     随后带子连续长出来 ⇒ **没有跳变**；
    //   · 收起时窗口**保持**展开尺寸，带子连续缩回去，动画跑完才把窗口收掉 ⇒ 同样没有跳变；
    //   · **整段动画期间 `setBounds` 调用 0 次**（判据见 `applyWindowSize` 的注释）。
    //
    // ⚠️ `bandFraction` 在**展开方向**由 `hover.expansion` 驱动（那个进度已经在
    //    `HoverState` 里按 `FADE_IN_MS` 推进，不另起第二个驱动 —— 与 `drawControls`
    //    复用同一个进度是同一条纪律）；**收起方向**才由本类自己推进，
    //    因为 `HoverState` 在收起时把 `expansion` 直接归零（那条 KDoc 的理由
    //    "窗口已经变矮、没有可绘制的载体"在本修法下**不再成立**：窗口现在保持不变）。
    //
    // ### ★★ 2026-09-22：上面那张表**当时只实现了一半**（用户第三次报同一现象）
    //
    // 用户原话：
    // > 「上方控件的弹出收回动画时间内词岛的上下闪动依旧，能不能彻底根治它？」
    //
    // 根因：`bandFraction` 被引入了，但**没有任何绘制代码读它**（只有 `applyWindowSize`
    // 里一处诊断计数器用）。岛体形状走的是 `buildShape(local, width, height)`，
    // 而那个 `height` 是**窗口高度** —— 窗口在展开那一帧就已经到位
    // ⇒ 岛体在那一帧瞬间长高 `strip` px，随后 180 ms 里再也不动。
    // 即"高度变化"整体落在动画之外。这正是本项目反复栽过的形态：
    // **改了 A，但 A 的输入被 B 掐断**。
    //
    // 现在补齐：岛体形状由 [bandGeometryNow]（→ 纯函数 [bandGeometry]）逐帧给出，
    // `bandFractionNow` 是它**唯一**的进度输入。于是上表最后一行成立，
    // 且表里"岛体形状与收起态逐像素相同"这句**第一次是真的**。
    //
    // 同时修掉两处会让"接入绘制"之后立刻显形的缺陷：
    //   · [startBandCollapse] 原先无条件从 `1f` 开始收 ⇒ "展开播到一半就移开鼠标"时
    //     带子先**跳**到最大再缩回去（高度不单调、中途回跳）；
    //   · 绘制用的展开态判据原为 `hover.expanded` ⇒ 收起方向**一帧都画不出来**，
    //     控件"闪没了"而窗口还保持展开 180 ms。
    /**
     * 控件带当前占 `stripHeight` 的比例（`0f` = 完全收起，`1f` = 完全展开）。
     *
     * 只由 [bandFractionNow]（展开方向，取自状态机）与 [advanceBandCollapse]（收起方向）
     * 两处推进；**取 `max` 合成**，于是"展开动画没跑完就移开鼠标"再"移回来"
     * 不会让带子从 0 重来（见 [bandFractionNow] 的说明）。
     */
    private var bandFraction: Float = 0f

    /** 收起方向的带子收缩动画是否在跑。在跑期间窗口**保持**展开尺寸（窗口本就恒为展开尺寸）。 */
    private var bandCollapseRunning: Boolean = false

    /** 收缩动画的起点（`System.nanoTime()`）。 */
    private var bandCollapseStartNanos: Long = 0L

    /**
     * 收缩动画的**起始占比**（`0f..1f`）。取 `startBandCollapse` 那一刻的 [bandFractionNow]。
     *
     * ## 为什么必须存下来（2026-09-22 修「中途回跳」）
     *
     * 原写法是 `bandFraction = 1f - easing(t)` —— 即**无条件从 1 开始收**。
     * 正常路径（展开跑完再离开）下起始值确实是 1，看不出问题；但
     * 「展开播到一半（比如 0.4）就移开鼠标」时，下一帧带子会**从 0.4 跳到 1.0**
     * 再往回收 —— 用户看到的正是「**词岛先"啪"地长到最大、再缩回去**」。
     *
     * 那正是本轮要根治的"上下闪动"的一种形态：**高度不单调、中途回跳**。
     * 存下起始值后 `bandFraction` 在整个收缩过程中**单调不增**，
     * 且首帧与展开被打断那一刻**逐像素连续**（见 `AnimationProbe` §11 的单调性断言）。
     */
    private var bandCollapseFrom: Float = 1f

    /** 收缩动画的时长（毫秒）。取 `Motions.CONTROLS_APPEAR.durationMs`，见 [startBandCollapse]。 */
    private var bandCollapseDurationMs: Long = 0L

    /** 收缩动画的缓动。取 `Motions.CONTROLS_APPEAR.easing`。 */
    private var bandCollapseEasing: Easing = Easing.EASE_OUT

    /**
     * 控件带**此刻**的高度占比 —— 展开方向与收起方向的**唯一汇合点**。
     *
     * ## 为什么要取 `max` 而不是"谁在跑用谁"
     *
     * 两个方向各有一个进度源（`hover.expansion` 与 [bandFraction]）。若按"谁在跑用谁"，
     * 那么"展开动画播到一半（0.4）时鼠标移开 → 收起 → 又移回来"这条路径上，
     * 新起的展开进度从 **0** 开始，带子会**先塌到 0 再长起来**（一次可见的抖动）。
     *
     * 取 `max` 则天然连续：展开方向只会让值**变大**，收起方向只会让它**变小**，
     * 两个方向不可能同时"反向"，故合成值**单调**，任何时刻都不会回跳。
     */
    private val bandFractionNow: Float
        get() = bandFractionCombined(hover.expanded, hover.expansion, bandFraction)

    /**
     * 当前 `window.y` 相对**逻辑位置**（用户摆的那个位置）的偏移量（px）。
     *
     * ## 为什么需要这个字段（2026-09-22，与「窗口恒为展开尺寸」配套）
     *
     * 窗口恒为展开尺寸 ⇒ 它比岛体多出的一条带高必须落在某一侧：
     * `BOTTOM` 在下方（偏移 0）、`TOP` 在上方（偏移 = 带高）。
     * 于是 `window.y` **不再等于**用户摆的位置，而 [persistPosition] 要存的是
     * **用户摆的位置** ⇒ 必须把这个偏移记下来（算术在 [windowYOffsetPx]，公开纯函数）。
     *
     * ## 为什么不能每次从设置现算
     *
     * 因为"当前窗口 y 是按**哪个**位置档摆的"是一个**历史事实**：
     * 用户把 `controls.position` 从 `TOP` 改成 `BOTTOM` 的那一刻，
     * `window.y` 还是按 `TOP` 摆的 —— 只有记住旧偏移才能把它换算回逻辑位置
     * （否则每切一次档位词岛就往上/下爬一条带高）。
     *
     * ⚠️ **任何绕过 [applyWindowSize] 直接摆窗口的地方都必须同步它** ——
     *    即 [restoreSavedPosition]（居中分支）与 [resetToPrimaryScreen]，
     *    两处都调 [syncWindowYShift]。拖动**不需要**（窗口是整体平移，
     *    偏移不变）。漏同步的表现是"下次改设置时词岛跳一条带高"。
     */
    private var windowYShiftPx: Int = 0

    /**
     * 外部（不经 [applyWindowSize]）直接摆好窗口位置后，同步 [windowYShiftPx]。
     *
     * 语义：把**当前窗口 y** 当作参照，记录它与逻辑位置的差 —— 即
     * "按现在的 `controls.position` 档，窗口 y 应该比逻辑位置高多少"。
     */
    private fun syncWindowYShift() {
        val cfg = controlsConfig()
        windowYShiftPx = windowYOffsetPx(controls.stripHeight(cfg), cfg.position == ControlsConfig.TOP)
    }

    /**
     * 本帧的**控件带绘制几何** —— 见 [bandGeometry]（公开纯函数，探针可断言）。
     *
     * ## 为什么收成一个方法（2026-09-22）
     *
     * 它是"窗口尺寸"与"岛体形状"两条路径的**唯一汇合点**：`applyWindowSize` 用同一个
     * 量化后的 `stripHeight`，`paintComponent` 用这里返回的 `islandTop` / `islandHeight`。
     * 两处若各算一遍，"窗口比岛体大 1px"或"锚点边差 1px"这类错配就会**静默发生**
     * （本项目在"两份实现漂移"上栽过多次）。
     *
     * ⚠️ 进度取 [bandFractionNow]（**不是** `hover.expansion`）—— 后者在收起方向
     *    会被 `HoverState` 直接归零，用它会让岛体**一帧内缩回**（那正是原缺陷）。
     *
     * ## ★ 2026-09-22：带高**恒为真实值**，不再判"带子是否被预留"（这是「上下闪动」的第三半）
     *
     * 改动前这里写的是 `if (bandReserved()) controls.stripHeight(cfg) else 0f`。
     * 那时窗口尺寸是"悬停才加高"的，两者配套 ⇒ 收起态 `stripQ = 0`、岛体落在窗口顶边。
     *
     * 现在窗口**恒为展开尺寸**（见 [applyWindowSize]）。若这里仍按那个判据归零：
     *
     * ```
     * 收起态：stripQ = 0   → 岛体 = [0, base]        （贴窗口**上**边）
     * 悬停首帧：stripQ = 76 → 岛体 = [76, 76+base]   （贴窗口**下**边）← 整条岛**下跳 76px**
     * ```
     *
     * 也就是把"闪动"从"窗口跳"搬到了"岛体跳" —— 同一个缺陷换了个位置。
     * 故带高必须**恒用真实值**：收起时多出来的那一条落**在岛体形状之外**
     * （形状由 `fraction` 决定，`fraction = 0` 时岛体就是收起态那么高），观感与改动前一致。
     */
    private fun bandGeometryNow(): BandGeometry {
        val cfg = controlsConfig()
        return bandGeometry(
            baseHeightPx = baseHeightPx,
            stripHeightPx = controls.stripHeight(cfg),
            fraction = bandFractionNow,
            stripAtTop = cfg.position == ControlsConfig.TOP,
            // ★ 2026-09-22：岛体**宽度**也由它给出（随展开进度从 `baseWidthPx` 连续长到
            //   `baseWidthPx + 带高`，见 [expandWidthGrowPx]）。窗口宽恒为后者 ⇒
            //   本函数仍是"窗口尺寸"与"岛体形状"的唯一汇合点（三个方向都在这里）。
            baseWidthPx = baseWidthPx,
            // ★ 2026-09-30（封面态 S4）：本态**左边缘固定、向右生长**
            //   （`recon/67` §6.2 横向不变量 ④；用户原话「词岛**向右侧生长**」）。
            //   ⚠️ 判据走 `coverShapeSkipsLyricChain`（与 S3 的内容分支**同一个函数**）——
            //     本态的两个特征（跳过歌词链 / 左锚）**语义上是同一件事**
            //     （"这是个封面态的词岛"），用同一个判据可保证二者不会各自漂移。
            //     若将来它们真的要分开，那时再拆成两个函数 —— 而不是现在就假设会分。
            anchorAtLeftEdge = coverShapeSkipsLyricChain(shapeTypeNow()),
            // ★★ 2026-10-01（封面态 R4）：与 [applyWindowSize] 传**同一个**值 ——
            //   否则"窗口宽"与"岛体最多长多宽"会漂移（本文件顶部那条纪律）。
            widthGrowOverridePx = widthGrowOverridePx(),
        )
    }

    /**
     * 当前的 `shape.type` 值（wire）—— 供绘制侧读形态。
     *
     * ★ 2026-09-30（封面态 S3/S4）：新增。此前绘制侧读形态走的是
     * `Appearance`（`local.shapeType`，由设置快照折叠而来），而 [bandGeometryNow]
     * **拿不到 `Appearance`**（它在 `paintComponent` 之外、且被 `applyWindowSize`
     * 等路径共用）。本方法给出一个**统一读法**，避免各处各写一次
     * `textOf(snapshot, "shape.type")`。
     *
     * ⚠️ **读的是本类持有的 [snapshot]**（`settings?.live`，副作用接收端在
     *    L3016 `snapshot = fresh`）—— **不是** `settings?.live` 直读。
     *    与 [controlsConfig] / [applyGeometry] 同一口径：本窗口的几何必须在
     *    "**这一帧看到的那份快照**"上算，否则设置变更与本帧绘制会撕裂。
     *    （`SettingsRuntime` **没有** `preview()` 方法 —— 曾经照记忆写成
     *    `settings.preview()`，编译期直接判死，见 `migration/52`。）
     *
     * ⚠️ **默认值必须与 `Appearance` 侧一致**（都是 `"SQUARE"`）——
     *    否则"还没读到设置"的首帧会算出与本态不同的几何（一闪）。
     */
    private fun shapeTypeNow(): String = textOf(snapshot, "shape.type")

    /**
     * 当前是否**被封面锁阻止展开**（`recon/67` §3.5）。
     *
     * 判据本体在公开纯函数 [coverLockBlocksExpand]（可被探针钉住）—— 本方法只负责
     * **把设置读出来**。两者分开的理由与 [shapeTypeNow] 相同：
     * 判据必须可离线断言，读设置在真机上才成立。
     *
     * ⚠️ 读的是本类持有的 [snapshot]（同一口径，见 [shapeTypeNow] 的 KDoc）。
     * ⚠️ `cover.lock` 的读取走 [boolOf]（descriptor 默认值兜底），
     *    与 `Appearance.from` 里的 `bool("cover.lock", false)` **同源同默认** ——
     *    否则"设置系统坏了"时两处会给出相反的锁状态（画了锁标却不拦展开）。
     */
    private fun lockBlocksExpandNow(): Boolean =
        coverLockBlocksExpand(shapeTypeNow(), boolOf(snapshot, "cover.lock"))

    /**
     * 锁头标志此刻的**矩形**（`null` = 此刻没有标志，因而不可点）。
     *
     * ## 三种"没有标志"的情形（都必须返回 `null`）
     *
     * | 情形 | 为什么不该有标志 |
     * |---|---|
     * | 非封面态 | 锁的语义只在本态成立（判据同源：[coverLockBlocksExpand]） |
     * | 未上锁 | 没有锁可提示（未锁时命中的正确反馈是**直接展开**） |
     * | 显示窗口期已过 | 标志是"短暂提示"，不是常驻元素（§3.5 L-2） |
     *
     * 最后一条由**公开顶层纯函数** [dev.cdl.window.coverLockBadgeVisibleAt] 判
 * —— 它的时刻入参可注入，故这条**真的可被探针钉住**（`CoverShapeSizeProbe §⑤b`）。
 * ★★ 2026-10-07 **更正（`migration/98` · C-2）**：本句原写「用 [coverLockBadgeVisible] 判
 * —— 它的时刻入参可注入，故这条可被探针钉住」，而那时它是 **`private fun`** ⇒
 * ★ **探针根本读不到** ⇒ 该申报为**空护栏**。已按上述抽成公开纯函数。
     *
     * ## 位置：封面的**右上角**
     *
     * 用户原话「命中只会在右上角弹出锁头标志」⇒ 锚在 `drawnCoverRect()` 的右上角。
     * 尺寸与内缩见 [COVER_LOCK_BADGE_PX] / [COVER_LOCK_BADGE_INSET_PX]。
     *
     * ⚠️ 用**本帧实际画出的**封面矩形（[IslandPanel.drawnCoverRect]）——
     *    与 `hitCover` 同源。若另算一份几何，封面随展开进度移动后会漂移。
     * ⚠️ **返回矩形**（而不是布尔命中）：绘制侧也要用它（"可见"与"可点"
     *    必须是同一个矩形，否则会出现"画在 A、要点 B"）。
     */
    private fun coverLockBadgeHit(x: Float, y: Float): java.awt.geom.Rectangle2D? {
        val rect = coverLockBadgeRectOrNull()
        return if (rect != null && rect.contains(x.toDouble(), y.toDouble())) rect else null
    }

    /**
     * 此刻标志的矩形（不判具体坐标）—— 绘制侧用它，事件侧经 [coverLockBadgeHit] 判命中。
     *
     * 两者共用本函数 ⇒ "画在哪"与"点在哪"**不可能**是两个矩形（唯一出口）。
     */
    private fun coverLockBadgeRectOrNull(): java.awt.geom.Rectangle2D? {
        if (!coverLockBadgeVisible()) return null
        if (!coverLockBlocksExpand(shapeTypeNow(), boolOf(snapshot, "cover.lock"))) return null
        val cover = panel.drawnCoverRect() ?: return null
        return coverLockBadgeRect(cover)
    }

    /**
     * 由封面矩形推出锁头标志的矩形 —— **纯几何，抽出来是为了可断言**。
     *
     * 锚点是封面的**右上角内缩**（见 [coverLockBadgeHit] 的 KDoc）。
     * `cover` 退化（宽/高 <= 0）时返回 `null` —— 与 `BandGeometry` 的"首帧退化"
     * 同一处置：**宁可没有标志，也不要画一个 0×0 的点**（那会永远点不中，
     * 却让"有没有标志"这条判据看起来是"有"）。
     */
    private fun coverLockBadgeRect(cover: java.awt.geom.Rectangle2D): java.awt.geom.Rectangle2D? {
        if (cover.width <= 0.0 || cover.height <= 0.0) return null
        val size = COVER_LOCK_BADGE_PX
        val inset = COVER_LOCK_BADGE_INSET_PX
        return java.awt.geom.Rectangle2D.Float(
            (cover.maxX - inset - size).toFloat(),
            (cover.minY + inset).toFloat(),
            size,
            size,
        )
    }

    /**
     * 起一个"锁头标志显示窗口期"（`recon/67` §3.5 L-2）。
     *
     * 由**命中已锁封面**的那条路径调用（见 `onPointer`）。
     *
     * ## 为什么要重绘 + 让帧驱动跑起来
     *
     * 标志是**时间敏感**的（[COVER_LOCK_BADGE_MS] 后要自己消失）。若只 `repaint()`
     * 一次，那一帧画上标志后就再没有后续帧 ⇒ **标志会一直留在屏幕上**（直到下一次
     * 因别的原因重绘）。故必须保证窗口期内**有帧在跑**。
     *
     * ⚠️ 本项目的帧驱动有**省电纪律**（`syncFrameDriver`：暂停时停帧）。
     *    故这里不直接 `frameTimer.start()`，而是走 [syncFrameDriver] 的统一判据 ——
     *    让它自己决定（它会看到"有一个待过期的显示窗口"⇒ 该跑）。
     */
    private fun triggerCoverLockBadge() {
        if (!coverLockBlocksExpand(shapeTypeNow(), boolOf(snapshot, "cover.lock"))) return
        coverLockBadgeUntilNanos = System.nanoTime() + COVER_LOCK_BADGE_MS * 1_000_000L
        panel.repaint()
        syncFrameDriver()
    }

    /**
     * 点锁头标志 ⇒ **展开**（用户原话「点锁标 = 展开」）。
     *
     * ## 为什么不改 `cover.lock`
     *
     * §9-S 定案明确**不采用**"点锁头标志解锁" —— 因为**点锁头标志 = 展开**，
     * 同一个控件不能既是"展开"又是"解锁"。解锁走右键菜单 / 设置项（两条入口）。
     *
     * ## 实现方式：把状态机的展开收口进它自己的方法
     *
     * 展开的唯一真源是 [hover]（`HoverState`）。它的 `expanded` 等字段是
     * `private set` / `private`（刻意如此）⇒ 从外面戳字段既编不过、也会让
     * "五个字段同时归零"这个不变量散落。故走它新增的公开入口
     * [HoverState.expandNow]（与 `update` 的展开分支共用同一套状态转移）。
     *
     * ⚠️ **标志窗口期同时关闭**：用户已经点到了，提示的使命完成 ⇒ 让它立刻消失
     *    （否则它会在展开后的词岛上悬着 [COVER_LOCK_BADGE_MS] 那么久）。
     */
    private fun expandForCoverBadge() {
        coverLockBadgeUntilNanos = 0L
        hover.expandNow(System.currentTimeMillis())
        bandFraction = maxOf(bandFraction, hover.expansion)
        applyWindowSize()
        panel.repaint()
        syncHoverTicker()
        syncFrameDriver()
    }

    /**
     * 当前是否**已开启鼠标穿透**（`island.interactive`）。**只在 EDT 上读写。**
     *
     * ## 为什么悬停层需要知道这件事（BUG-023，2026-09-17 修）
     *
     * 穿透窗口（`WS_EX_TRANSPARENT`）**收不到任何鼠标事件** ——
     * 这是该窗口样式的定义，不是缺陷。于是：
     * ```
     * 穿透 → 无事件 → onPointer 永不被调用 → enterSince 恒为 NONE
     *      → needsTicker 返回 false → 悬停定时器不启动
     *      → 而唯一能启动它的"鼠标事件"永远不会来   ← 引导死锁
     * ```
     * 表现为「勾上穿透后，悬停变手形 / 播放控件 / 进度条全部不可达」。
     *
     * 故本字段是"**改用轮询**"的开关：为 `true` 时
     * ① [syncHoverTicker] 让定时器**常驻**；② [onHoverTick] 自己用
     * `MouseInfo` 取光标位置喂给状态机（原实现 0.8.9 的同一做法，
     * `089:ui/IslandWindow.kt:142-146`）。
     *
     * **为什么用字段而不是每次现读设置**：`onHoverTick` 每 40 ms 跑一次，
     * 而它只需要一个布尔 —— 每帧去查注册表 + coerce 是纯浪费。
     * 设置变更本来就会走 [applyExtensionStyles]，在那里同步一次即可。
     */
    private var clickThroughActive: Boolean = false

    /**
     * 最近一次低频检测得到的「**前台窗口是否铺满屏幕**」。
     *
     * ## 为什么必须是字段（缓存），而不是在 [applyVisibility] 里现查
     *
     * [applyVisibility] 的调用频率是**每帧**（播放时 33 ms 一次，见 [onFrameTick]）。
     * 若在里面现查，就等于把「Win32 查询 + `GraphicsEnvironment` 取屏幕边界」
     * 塞进了渲染循环 —— 那是一条**与画面无关的内核调用**混在帧预算里。
     * 而全屏状态的**变化频率是分钟级**的，每帧查一次是纯浪费。
     *
     * 故由 [onWatchdogTick] 以低频（[WATCHDOG_TICK_MS]）刷新本字段，
     * [applyVisibility] 只读它 —— 与 `hide_paused` 由帧驱动传入 `playing` 是同一个模式：
     * **显隐判定只做算术，不做 I/O**。
     *
     * ## 初值 `false` 的理由
     *
     * 「还没测过」按「不是全屏」处理 —— 若按 `true`，插件启动时会先把自己藏起来
     * 直到第一次检测（最长 400 ms）跑完，用户看到的是"刚启动词岛闪一下才出来"。
     * 按 `false` 则最坏只是"在全屏里多显示 400 ms"，明显更可接受。
     *
     * `@Volatile`：写者可能在 EDT（定时器），读者也在 EDT，但设置订阅回调
     * 的线程契约是"任意线程"（见 [installSettingsSubscription]），故防御性加上。
     */
    @Volatile
    private var foregroundFullscreen: Boolean = false

    /**
     * 迟滞用的**候选全屏状态**（2026-09-18 加）。`null` = 当前没有候选。
     *
     * 真机日志（`super-island.log:5784-5811`）显示 `island.hide_fullscreen` 的显隐
     * 在 **1~3 秒**间隔上来回抖 —— 用户看到的是词岛反复闪。
     * 详见 [onWatchdogTick] 里那段 KDoc（含"为什么抖必然是缺陷"的论证）。
     */
    private var fullscreenCandidate: Boolean? = null

    /** [fullscreenCandidate] 连续成立的 tick 数。达到 `FULLSCREEN_CONFIRM_TICKS` 才切换。 */
    private var fullscreenCandidateTicks: Int = 0

    /**
     * 「**全屏检测 + 重申置顶**」的看门狗定时器。**常驻**（只要 [started]），
     * 与窗口显隐、播放态、悬停态**全都无关**。
     *
     * ## 为什么必须常驻（这是本定时器存在的全部理由）
     *
     * 它负责的两件事**都不产生窗口事件给词岛**：
     * ① **进出全屏**：全屏的是**别的**应用，词岛既不是前台窗口、尺寸也没变 ——
     *    没有任何消息会送到我们这里；
     * ② **任务栏把自己重新提上来**：那是 Shell 的动作，词岛完全被动。
     *
     * 于是只能"自己定时去看一眼"。这也正是 [applyVisibility] 里
     * `island.hide_fullscreen` 与 `island.hide_paused` 的**本质区别**：
     * 后者有事件源（播放态变化会经宿主回调进来），前者没有。
     *
     * ⚠️ **它不能随窗口隐藏而停** —— 否则"全屏时隐藏"会变成**不可逆**的：
     * 词岛把自己藏起来之后就再也没人去检测"用户已经退出全屏了"，
     * 用户会以为插件坏了（而且此时右键菜单也点不到）。停它的唯一时机是 [close]。
     */
    private var watchdogTimer: Timer? = null

    /**
     * 上一次**重申置顶**的时刻（`System.nanoTime()`）。用于给悬停期间的高频重申**节流**。
     *
     * 只在 EDT 上读写（[onHoverTick] 与 [onWatchdogTick] 都是 EDT 定时器回调），
     * 故不需要 `@Volatile`。理由与取值见 [onHoverTick] 里那段 KDoc。
     */
    private var lastRaiseNanos: Long = 0L

    /**
     * 面板左上角的**屏幕坐标缓存**。用途只有一个：窗口**隐藏期间**
     * 仍然能算出"鼠标是否还在词岛原来占的那块区域里"。
     *
     * ## 为什么需要它（`island.auto_hide_on_hover` 的"怎么恢复"全靠这一条）
     *
     * 该设置是**临时隐藏**：鼠标移开就恢复（取舍见 [applyVisibility] 的说明）。
     * 而"鼠标移开了没有"必须靠轮询回答 —— 窗口隐藏后**收不到任何鼠标事件**。
     * 轮询本身没问题（[onHoverTick] 每 40 ms 已经在轮询），问题在于
     * `Component.locationOnScreen` 对**未显示的组件会抛 `IllegalComponentStateException`**
     * （这正是 BUG-019 那条异常的来源），于是 [pollPointer] 在窗口隐藏时**必然拿不到原点**
     * → 判不出"鼠标已离开" → 词岛永远回不来。
     *
     * 故这里把最后一次**成功取到的**屏幕原点留下来，隐藏期间拿它当参照。
     * 窗口隐藏时不可能被拖动，故这份缓存不会过期。
     *
     * 只在 EDT 上读写（与 [pollPointer] 的调用方一致）。
     */
    private var panelOriginCache: Point? = null

    /**
     * **区域级穿透**的当前命中态（上一次判定的结果）。**只在 EDT 上读写。**
     *
     * ## 这是什么（用户诉求，2026-09-17）
     *
     * > 「对于词岛背景，**不应阻挡鼠标对其下覆盖的内容操作**，也就是说，
     * > 鼠标光标在**没有命中字形时，词岛背景对鼠标而言是穿透的**。
     * > 当鼠标光标**命中字形时，整个词岛对鼠标不穿透**（在未勾选鼠标穿透下），
     * > 此时用户可以对词岛的**边框**进行调整，对**播放控件、进度条**进行操作」
     *
     * 即"是否穿透"不再由**设置**静态决定，而是**随光标位置动态切换**：
     * - 光标在**字形 / 播放控件 / 进度条**上 → **不穿透**（可交互）
     * - 光标在**背景空白处** → **穿透**（事件落到下层窗口）
     *
     * ## 初值为什么是 `true`（= 不穿透）
     *
     * `true` 表示"命中交互区"→ 不穿透。这是**安全侧**：
     * 启动瞬间若判成"穿透"而用户恰好想拖动词岛，会出现"点不到"的困惑；
     * 判成"不穿透"最多是"第一帧背景挡住了下层"—— 一个 40 ms 后就自行纠正的小瑕疵。
     * **两个方向的代价不对称，故取安全侧。**
     *
     * ## 为什么必须缓存（而不是每 tick 都调 `SetWindowLongW`）
     *
     * `SetWindowLongW` 是用户态→内核态调用。40 ms 一次的 tick 里**无条件**调用它
     * 会让 CPU 无谓升高，且可能触发窗口重绘（闪烁）。
     * 故只在**命中态发生变化**时才调用 —— 鼠标停在原地不动时一次都不调。
     */
    private var lastRegionalHit: Boolean = true

    /** 当前鼠标悬停在哪个控件上（用于画悬停底色与切光标）。`null` = 没悬停在任何控件上。 */
    private var hoveredControl: PlaybackControls.Id? = null

    /**
     * 本次按下是否已被控件消费。
     *
     * 存在的理由：控件点击与窗口拖动共用**同一套鼠标事件**（面板既是控件条也是拖动把手）。
     * 按下时若命中了控件，就必须让随后的 `mouseDragged` / `mouseReleased` **整体失效** ——
     * 否则用户点"下一首"时手指稍有位移，词岛会跟着跑，看起来像"点了按钮却拖动了窗口"。
     */
    private var controlPressConsumed: Boolean = false

    /**
     * 进度条拖动的**预览位置**（毫秒）。`null` = 当前没在拖进度条。
     *
     * ## 为什么必须有预览（而不是"拖到哪就 seek 到哪"）
     *
     * 拖动是连续动作，一次拖动会产生几十上百个 `mouseDragged`。每帧都往宿主发一次
     * `seekTo` 的后果是：① 播放器被 seek 请求淹没，音频断断续续；
     * ② 每次 seek 都会触发宿主的 `onSeekTo` 回调 → 时间轴跳变 → 帧驱动 `restart()`
     * （见 `onFrameTick` 的 `jumped` 分支）→ **重绘风暴**。
     *
     * 故拖动中**只改这个预览值并重绘**，松手（[commitSeek]）才真正调用 `playbackActions.seekTo`。
     * 这与 0.8.9 `PlaybackProgress` 的 `preview` 字段是同一设计
     * （它的 `mouseReleased` 里才 `seek(target)`）。
     *
     * ## 为什么拖动中不跟手播放位置
     *
     * 预览值只由鼠标位置决定，**不随播放推进** —— 否则用户按住不动时进度条会自己往前爬，
     * 看起来像"没抓住"。这与 0.8.9 一致（`preview ?: snapshot.positionMs`）。
     */
    private var seekPreviewMs: Long? = null

    /**
     * 本次拖动的**起始曲目标识**。
     *
     * 存在的理由：拖动过程中若发生换曲（用户按了键盘的下一首、或一首歌正好播完），
     * 松手时那个"目标毫秒"是相对**上一首**的时长算出来的，提交到新曲目上会跳到一个
     * 毫无意义的位置。故记下起始标识，松手时比对 —— 不一致就**丢弃这次拖动**。
     * 这是 0.8.9 `PlaybackControls.gestureTrack` 的语义（它比对 `Track` 对象；
     * 本项目没有稳定的曲目标识对象，故用 `path` + 文档版本，见 [currentTrackKey]）。
     */
    private var seekGestureKey: String? = null

    /**
     * ★★ 2026-09-28 新增：**已提交但尚未被宿主位置确认**的 seek 目标（毫秒）。`null` = 没有在等。
     *
     * ## 为什么必须有它（用户报障）
     *
     * 用户原话（逐字）：「**拉动进度条(无论何种形态)，松手后会短暂闪现回原位**」。
     *
     * ### 根因：松手这一帧**先清预览、后发 seek**，中间那帧画的是旧位置
     *
     * [commitSeek] 的执行顺序是：
     * ① `seekPreviewMs = null` → ② `panel.repaint()` → ③ 才调 `playbackActions.seekTo(target)`。
     *
     * 而 `seekTo` 是**异步**的 —— 宿主把新位置回来要经过它自己那条
     * "一秒一次"的位置回调（见 `PlaybackTimeline` 的类注释）。于是从松手到宿主报新位置之间，
     * 进度条**每一帧**都按 `snap.positionMs`（= **seek 前的旧值**）绘制 ⇒ 用户看到
     * "闪回原位"，等约一秒后才跳到目标。
     *
     * ### 为什么不能用标志位（纪律 14）
     *
     * "正在等宿主确认"若做成一个 boolean，它就必须有人去**复位**；而复位点是**时序相关的**
     * （哪个回调先到不确定，`onSeekTo` 与 `onPositionUpdated` 的先后宿主不保证）——
     * 标志位一旦错过复位时机就会**永久卡住**（进度条再也不跟播放走）。
     * 故改用**比值/时间**判据：只要"当前显示位置"仍**远离**目标就继续用目标画，
     * 一旦接近（宿主确认了）或超过绝对截止期（宿主没回）就**自动**失效 —— 无需任何人复位。
     *
     * 这与 [PlaybackTimeline.pendingSeekTarget] 是**同一条纪律的两处落地**：
     * 那边管"丢弃陈旧心跳"，这里管"丢弃陈旧显示值"。
     */
    private var committedSeekMs: Long? = null

    /**
     * [committedSeekMs] 的**绝对截止期**（`System.nanoTime()`，0 = 未在等）。
     *
     * 存在的理由：宿主可能**永远不报**新位置（曲子正好播完、播放器被换掉、句柄失效……）。
     * 没有截止期的话进度条会**无限**停在那个目标上，看起来像"卡死了"。
     * 到点即放弃保持，退回真实位置 —— 这是安全方向（最差也只是"闪一下"回到旧行为）。
     *
     * 取值 [SEEK_DISPLAY_HOLD_MS] = 3000 ms：宿主位置回调约 1 秒一次，
     * 3 秒 = 连续错过三次仍未确认 ⇒ 不再等它。
     */
    private var committedSeekDeadlineNanos: Long = 0L

    /**
     * [committedSeekMs] 所属的**曲目标识**（见 [currentTrackKey] 的公式）。
     *
     * 存在的理由：拖动中/提交后若发生换曲（键盘切歌、一首播完自动下一首），
     * 那个"目标毫秒"是相对**上一首**的时长算的 —— 拿它去画新曲目的进度条
     * 会跳到一个毫无意义的位置。判据与 [seekGestureKey] 完全一致（**同源**）。
     */
    private var committedSeekTrackKey: String = ""

    /**
     * 收起态下的窗口尺寸（宽 × 高）。
     *
     * 展开时窗口高度 = [baseHeightPx] + 控件条高度。**必须存下来**，因为
     * [applyWindowSize] 会在悬停状态变化时被调用，那时手上没有设置快照
     * （它由鼠标事件触发，与设置变更无关）。
     */
    private var baseWidthPx: Int = 0

    /**
     * ★★★ 2026-10-17（**BUG-225 · 歌词在高亮到句尾时移动**）：本帧生效的**最大宽度**缓存。
     *
     * 绘制侧要用它判断"窗口是不是已经顶到上限"—— 只有顶到上限（真的放不下）才允许
     * 水平滚动；自动宽度模式下窗口还能继续变宽 ⇒ **不许滚**（否则用户看到
     * 「唱到句尾时歌词横向移动」= 他报的「就像宽度不足一样」）。
     *
     * ★ 不在绘制里调 `effectiveMaxWidth()`：它每帧问一次
     *   `GraphicsEnvironment.maximumWindowBounds`，成本与"每帧读设置"同量级；
     *   而 `applyGeometry` 本来就已算过一次 ⇒ 存下来即可（**同一帧一个值**）。
     */
    private var maxWidthPxCache: Int = 0

    /**
     * ★★ 2026-10-01（封面态返工 · R3/R4）：**本态展开后的"完整词岛宽"**（px）。
     *
     * ## 为什么需要它
     *
     * 封面态有**两个**宽度：**收起 = 小岛宽**（封面外径 + 2×内边距，见 [applyGeometry]）·
     * **展开 = 完整词岛宽**（本字段）。而 [baseWidthPx] 只装得下一个 ——
     * 故本态下它装**小岛宽**，完整宽放这里，**生长量 = 两者之差**
     * （`recon/67` §6.2：「本态 = **完整词岛宽 − 小岛宽**」，**倍数级**）。
     *
     * ⚠️ **非本态恒为 `0`** ⇒ [widthGrowOverridePx] 返回 `-1` ⇒ 走既有"带高"增量，
     *    既有四型**逐像素不变**（本轮硬约束）。
     */
    private var coverExpandedWidthPx: Int = 0

    /**
     * 横向生长量（px）—— 本态（封面态）走**参数化**，其余形态返回 `-1`（用默认"带高"）。
     *
     * `-1` 是"未指定"的哨兵（与 [bandGeometry] / [windowTargetBounds] 的
     * `widthGrowOverridePx` 约定一致）：那两个函数见到负数就自己调
     * [expandWidthGrowPx]，故既有四型的算术**一字未改**。
     */
    private fun widthGrowOverridePx(): Int =
        if (coverExpandedWidthPx > 0 && baseWidthPx > 0) {
            (coverExpandedWidthPx - baseWidthPx).coerceAtLeast(0)
        } else {
            -1
        }
    private var baseHeightPx: Int = 0

    // ── 封面状态（`cover.enabled` + 三个 `*_color_mode`）────────────────────
    //
    // 下面 5 个字段**只在 EDT 上读写**：它们全部由 `IslandPanel.paintComponent`
    // （EDT）与 `javax.swing.Timer` 的回调（也在 EDT）触碰，故不需要 volatile。
    // 这与 [RenderCache] 的线程纪律是同一条（绘制路径上的状态没有理由支持并发）。

    /**
     * 上一次向 [coverArtProvider] 索要封面的曲目路径。
     *
     * 作用有二：① 识别换曲（路径变了 → 重新进入"等待首次结果"状态）；
     * ② 作为"当前有没有曲目"的判据 —— 空串表示宿主还没报过曲目，
     * 此时左侧不占位（没有歌就没有封面可言）。
     */
    private var coverRequestPath: String = ""

    /**
     * 是否仍在等待本曲目的**首次**封面结果。
     *
     * ## 为什么需要它（而不是"`null` 就是不显示"）
     *
     * `coverFor` 的契约是"首次调用必返回 `null`"（见 [coverArtProvider] 的说明）。
     * 若把 `null` 直接当作"没有封面"：
     *
     * - **不占位** → 封面到达时文字会**突然右移**一段（可见的跳动）；
     * - **占位** → 一首真的没有封面的歌会**永远**在左边留一块空白。
     *
     * 两者都不能接受，故必须区分"**还没结论**"与"**结论是没有**"：
     * 前者占位（避免跳动），后者收窄（让文字占满）。
     *
     * 状态转移：换曲 → `true`；拿到图 → `false`；重试耗尽 → `false`。
     */
    private var coverPending: Boolean = false

    /**
     * 已安排的"封面补重绘"次数。
     *
     * ## 为什么必须有上限
     *
     * 补重绘的目的是"图片在后台到达后自己出现"（见 [scheduleCoverRepaint]）。
     * 但**没有封面的歌**会让每一次补重绘都拿到 `null` —— 若不限次数，
     * 词岛在暂停状态下会永远每 120 ms 重绘一次，纯白烧 CPU（真实空转）。
     *
     * 上限 [MAX_COVER_REPAINT_ATTEMPTS] 次后认定"本曲没有封面"，
     * 停止轮询并把 [coverPending] 置 false。
     */
    private var coverRepaintAttempts: Int = 0

    /**
     * ★★ 2026-09-28 新增：本曲"等待封面"的**绝对截止期**（`System.nanoTime()`，0 = 未在等）。
     *
     * ## 为什么必须有它（用户报障）
     *
     * 用户原话（逐字）：「**无歌词和封面的歌曲会复用上一首歌曲的封面**」。
     *
     * ### 根因：重试计数在**播放中永不增长** ⇒ `coverPending` 永远为 `true`
     *
     * 原设计的"等待结束"只靠 [coverRepaintAttempts] 触及 [MAX_COVER_REPAINT_ATTEMPTS]。
     * 而 [scheduleCoverRepaint] 的第一件事是
     * `if (frameTimer?.isRunning == true) return` —— **播放中直接返回**，计数**一次都不加**。
     * 于是**无封面的歌一放起来**，`coverPending` 就**永远**停在 `true`：
     *
     * - [coverImageFor] 每帧返回 `null`（正确）；
     * - [resolveCoverMix] 落到"**等新图**"那一档（`newImage == null && coverPending`）
     *   ⇒ `prev = null, shown = 旧图` ⇒ **上一首的封面被原样继续画**（错误）。
     *
     * 用户看到的正是"复用上一首封面"—— 且它**不报错、不超时**，只在换到无封面歌时出现。
     * ⚠️ **暂停时反而正常**（帧定时器停了 ⇒ 重试真的会跑满 8 次 ⇒ 约 1 s 后淡出）
     *    ⇒ 这才是本缺陷"时好时坏"的来源。
     *
     * ### 修法：把"等待多久"从**计数**改成**绝对时间**
     *
     * 计数与"帧定时器是否在跑"耦合（正是它失效的原因）；绝对截止期与之**无关**：
     * 无论播放/暂停、无论帧节拍如何，到点即认定"本曲没有封面"。
     * 总等待时长 = [COVER_REPAINT_DELAY_MS] × [MAX_COVER_REPAINT_ATTEMPTS]（语义不变，
     * 仍是"约 1 秒"），只是判据从"数了几次"改成"过了多久"。
     * ★ 这与本项目纪律 14「回灌/回弹守卫用**比值/时间**不用标志位」同源 ——
     *   标志位与时序耦合，迟早出错。
     */
    private var coverWaitDeadlineNanos: Long = 0L

    /**
     * 封面补重绘定时器（**单次触发**，用一次建一次）。
     *
     * 为什么不复用 [frameTimer]：帧驱动的语义是"播放中按帧推进"，
     * 它在暂停时**必须**停（见 `syncFrameDriver` 的省电论证）；
     * 而封面到达是**一次性事件**，与播放态无关。混在一起会让
     * "暂停时也要跑帧驱动"变成常态，把省电设计整个抵消掉。
     *
     * ⚠️ 它持有指向本类的 lambda，`close()` 里**必须**停（否则插件卸载后
     * 定时器仍在 EDT 上跑，是真实的内存泄漏 —— 与 `frameTimer` 同一条纪律）。
     */
    private var coverRepaintTimer: Timer? = null

    // ── 封面锁的「锁头标志」显示窗口期（2026-09-30 封面态 S5）──────────────────
    //
    // ## 为什么它是一个"窗口期"而不是"命中即画"（`recon/67` §3.5 L-2）
    //
    // 锁是**持久状态**（落盘、重启仍在），而"鼠标划过封面"是**瞬时事件**。
    // 若标志只在"鼠标停在封面上"时画，那么鼠标一移开它就消失 ——
    // 用户**无法确认自己到底锁上了没有**（这正是 §3.5 L-2 点名的技术点）。
    // ⇒ 命中已锁的封面时，**起一个短暂的显示窗口**（[COVER_LOCK_BADGE_MS]），
    //   窗口期内标志常显且**可点**（点它 = 展开，用户原话「点锁标 = 展开」）。
    //
    // ## 为什么用"到期时刻"而不是"剩余毫秒 + 递减"
    //
    // 与 `FramePacer` 的"绝对截止期"同一条纪律（纪律 24）：**减少**依赖 tick 频率
    // 的累积。用绝对时刻后，标志的消失时机与"这段时间里 tick 了多少次"无关。
    // ⇒ 判据是纯比较（[coverLockBadgeVisibleAt]），**可被探针直接断言**（注入时刻）。
    private var coverLockBadgeUntilNanos: Long = 0L

    /**
     * 锁头标志在当前时刻是否应当**显示**（且因而**可点**）。
     *
     * ★★★ 2026-10-07 **BUG-162 修复**（`migration/98` · C-2）：
     *   **判据本体已抽成公开顶层纯函数**
     *   [dev.cdl.window.coverLockBadgeVisibleAt]`(nowNanos, untilNanos)`。
     *   ★ 原来它是本类的 `private fun`，而**探针读不到 `private`**（probe 是独立编译模块）
     *   ⇒ 本处 KDoc 原写「它的时刻入参可注入，故这条**可被探针钉住**」**是假的**
     *   （全探针树对该函数名 **0 命中**）⇒ ★ **"显示窗口期"当时零护栏**。
     *   ★ 现在判据在 `IslandPolicy` 里，由 `CoverShapeSizeProbe §⑤b` **直接断言**。
     *
     * ⚠️ `untilNanos <= 0` 表示"从未触发过" ⇒ 不显示（见纯函数的 KDoc）。
     */
    private fun coverLockBadgeVisible(): Boolean =
        coverLockBadgeVisibleAt(System.nanoTime(), coverLockBadgeUntilNanos)

    /**
     * 主色缓存的键（上次提取主色用的那张图）。用**引用比较**判断是否需要重算。
     *
     * 为什么必须缓存：`CoverColorExtractor.dominant` 要遍历约 1024 个采样点
     * 并做 1024 次 HSB 转换。每帧算一次虽然只有几十微秒，但**完全没有必要** ——
     * `CoverArtProvider` 对同一首歌返回的是**同一个 `BufferedImage` 实例**
     * （它的缓存就是为这个而存在的），故引用一变才重算，稳定时每帧零成本。
     */
    private var coverColorImage: BufferedImage? = null

    /** 上一次提取出的主色（`null` = 那张图没有可用主色）。与 [coverColorImage] 配对。 */
    private var coverColorRgb: Int? = null

    /**
     * ★★ 2026-10-03（⑰「封面多色流光」**F4 缓存**）：与 [coverColorImage] 配对的**多色**缓存。
     *
     * 键与主色**完全同一个**（同一张 `BufferedImage` 引用）⇒ "图一变，色与多色一起重算"，
     * 不会出现"主色换了、多色还是上一首的"这种错配。
     */
    private var coverPaletteImage: BufferedImage? = null

    /** 与 [coverPaletteImage] 配对的多色列表（`FLOW` 档用）。 */
    private var coverPalette: List<Int> = emptyList()

    /**
     * 「按曲目粘住」的主色缓存：**这条路径上一次成功取到的主色**。
     *
     * ## 为什么需要它（2026-09-18，用户真机反馈）
     *
     * 用户原话：
     * > 「前文提到的外观设置变默认问题，目前遇到的是**可逆现象**，在调整一些设置项时
     * > 词岛外观会**短暂变为默认外观**，但之后又**恢复当前选项**了。」
     *
     * ### 已证实的部分（症状机理）
     *
     * 用户的配置里 `spectrum.color_mode` / `background.color_mode` 都是 `cover` ——
     * 即**颜色来源于封面主色**。而绘制路径是：
     *
     * ```
     * coverImageFor(local) → coverRgb = coverImage?.let { coverRgbOf(it) }
     *                      ↓ coverRgb 为 null 时
     * CoverPalette.resolve 的 cover 档 → **静默回退到 manual**
     * ```
     *
     * 于是**只要 `coverRgb` 有一帧为 `null`**，用户看到的就是
     * 「频谱颜色回归初始（`spectrum.color` 的默认值）+ 背景色变默认 + 封面消失」——
     * 与用户早前那次报告的三条症状**逐条对应**。下一帧封面可用，颜色立刻变回来，
     * 这就是用户说的"**可逆**"。
     *
     * ### 未证实的部分（触发时机）
     *
     * `coverRgb` 为 `null` 的具体触发点**尚未定案**（候选：该帧 `needsCoverImage`
     * 判为 false、路径暂空、provider 尚未就绪）。**本缓存不依赖触发点是什么** ——
     * 它把"取色"与"封面此刻在不在"解耦：
     *
     * > **曲目没变，颜色就不该闪。**
     *
     * 只有当**曲目真的换了**、或**这首曲目从未成功取过色**时，才允许回退到 `manual`。
     * 这与 `CoverArtProvider` 自己"按路径缓存封面图"的取舍同源（同一个键、同一种粘性），
     * 于是"图"和"色"的缓存边界一致，不会出现"图还在、色却没了"的错配。
     */
    private var stickyCoverColorPath: String = ""

    /**
     * ★★★ 2026-10-12（**BUG-180**）：**多色调色板的粘性缓存** —— ★ 与 [stickyCoverColorPath] 同一套。
     *
     * ## 为什么需要（用户报「**颜色选项还会突然失效**」）
     *
     * ★ 封面是**异步加载**的 ⇒ 加载完成前那一帧拿不到图 ⇒ 调色板为空
     * ⇒ ★★ `spectrum.color_mode = cover_flow` 的**流光退回单色** ⇒ 观感"颜色突然失效" ✗
     *
     * ## 为什么"按曲目路径"键就够（★ 原 KDoc 的顾虑不成立）
     *
     * ★ 原 KDoc 说"多色是大面积填充 ⇒ 粘住会出现'封面已经换了、整块背景还是上一首的颜色'" ✗
     * ★★ 但**按曲目路径**键 ⇒ ★ 曲目一变 `path` 就变 ⇒ **缓存立刻失效** ⇒ **不可能串色** ✓
     * ★ 主色（[resolveCoverRgb]）**早就是这么做的** ⇒ ★ 本处只是**补齐同一条** ✓
     */
    private var stickyPalettePath: String = ""

    /** 与 [stickyPalettePath] 配对的调色板。空列表 = 该曲目确实取不到多色。 */
    private var stickyPalette: List<Int> = emptyList()

    /**
     * ★★★ 2026-10-14（**BUG-193**）：**跨曲目**保留的调色板 + 过渡起点 —— 抹掉
     * "切歌一瞬间流光退回**自选色**"。
     *
     * ★ 完整根因（`spectrumFlowColorAt` 的 `size < 2 ⇒ 返回基色` 退化判据）
     *   与"为什么不违背既有 KDoc"见 [coverPaletteHoldOrEmpty] 的 KDoc ✓
     *
     * ★ 与主色那一对（[coverRgbHold]/[coverRgbHoldAtMs]）**同构** —— 两者用**同一个**
     *   宽限窗判据 [withinCoverHoldGrace] ✓
     */
    private var paletteHold: List<Int> = emptyList()

    /** [paletteHold] 的**刷新时刻**。 */
    private var paletteHoldAtMs: Long = 0L

    /** 本次过渡的**起点调色板**（上一份）。与 [paletteHold] 等长时才逐色插值。 */
    private var palettePrev: List<Int> = emptyList()

    /** 与 [stickyCoverColorPath] 配对的主色。`null` = 该曲目确实取不到主色。 */
    private var stickyCoverColorRgb: Int? = null

    /**
     * 「曲目未变但取不到封面」这条诊断**本轮是否已经打过**。
     *
     * 只用于去噪：该情形在动画期间可能逐帧成立，不去噪会把日志淹掉
     * （与「设置读取」那条告警同一套做法 —— 只在**状态翻转**时打一行）。
     */
    private var coverUnavailableLogged: Boolean = false

    /**
     * ★★★ 2026-10-12（**BUG-181**）：**调色板诊断的去噪键**（去噪方式与 [logColorSourceDiag] 同一条）。
     *
     * ★ 只记"**取色结果**"（色数 + 十六进制）—— 同一条结果只打一行 ✓
     * ★★ 本批的根因正是"色数不足 ⇒ 流光**静默**退回单色"，而那条路径**一行日志都没有** ✗
     * ⇒ ★ 加上这一行之后，"选了多色档却看不出多色"**当场可查** ✓
     */
    private var paletteDiagKey: String = ""

    /** 与 [logFlowDiag] 配对的去噪键。 */
    private var flowDiagKey: String = ""

    /**
     * 「本曲**从未**取到封面」这条诊断已经为哪个曲目路径打过。
     *
     * ## 为什么必须与 [coverUnavailableLogged] 分开（2026-09-19）
     *
     * 原状：**只有**「曾取得后丢失」这一种情形会打日志（见 [resolveCoverRgb]），
     * 判据是 `path == stickyCoverColorPath` —— 而 `stickyCoverColorPath` 只在
     * **成功取到过图**时才会被赋值。于是「**从头到尾就没拿到封面**」这个
     * **最常见的失败**一声不响：既没有取色生效的日志，也没有取色失败的日志，
     * 用户看到的就只是「颜色没生效」。
     *
     * 真机取证（2026-09-19）：用户的四个 `*_color_mode` 全是 `cover`，
     * 而日志里**既没有**取色诊断、**也没有**任何封面相关报错 ——
     * 「取封面失败」与「取封面成功」在日志里**完全不可区分**，
     * 这正是那次缺陷无法定案的原因。
     *
     * 故新增本字段：按**曲目路径**去噪（同一首歌只打一次），
     * 与 [coverUnavailableLogged]（按**状态翻转**去噪）各管一种情形。
     */
    private var coverNeverLoggedPath: String = ""

    /**
     * 「取色诊断」这条日志的去噪键（上次打印时的状态元组）。
     *
     * ## 为什么是"状态元组"而不是布尔
     *
     * 本行要回答的是「**渲染此刻到底用了什么颜色来源**」，而它的输入有
     * 六个自由度（四个 `*_color_mode` + `cover.enabled` + `coverRgb` 是否为 null）
     * 加两个身份（曲目路径 / 粘性路径）。用布尔去噪的话，
     * 「先 manual 后 cover」与「先 cover 后 manual」都会被吞掉一次。
     *
     * 故把整个元组序列化成字符串比较 —— 与 `SettingsStore.lastFallbackKeys`
     * 「记集合而不是记计数」是同一条纪律（计数相同但成员不同时也必须重打）。
     *
     * ⚠️ **不要把 `coverImage == null` 放进键里**：它在同一首歌里会
     * 「null → 非 null」翻转一次，让每首歌多打一行无用日志。
     * 它已经由 `coverRgb` 是否为 null 覆盖了。
     */
    private var colorSourceDiagKey: String = ""

    // ── 帧驱动状态 ────────────────────────────────────────────────────────

    /**
     * 帧驱动定时器。`null` = 未启动（暂停、或窗口未启动）。
     *
     * ⚠️ 用 `javax.swing.Timer` 而不是自管线程：它的回调**本身就在 EDT 上**，
     * 于是"渲染必须在 EDT"这条硬约束由定时器类型本身就保证了 ——
     * 不需要在每个 tick 里包一层 `invokeLater`（那会多一跳投递延迟，
     * 且在高频 tick 下堆积 EDT 任务队列）。自管线程则要么在非 EDT 上画（会炸），
     * 要么还是得投递，白多一个线程的生命周期要管。
     */
    private var frameTimer: Timer? = null

    /**
     * 帧节拍的**绝对截止期**驱动器（第 1 期 ⑦）。
     *
     * 为什么是"驱动器旁挂一个 pacer"而不是"换掉驱动器"：见 [FramePacer] 的 KDoc ——
     * `javax.swing.Timer` 的超发（实测 ×1.07）是**恒定投递延迟**，不是周期误差，
     * 故把 `delay` 改成"距下一个**绝对**截止期还有多久"即可精确，**不必改线程模型**。
     *
     * 间隔**每次读取** `animator.frameIntervalMs`（不是构造时快照），
     * 故 `animation.quality` 改帧间隔能立刻生效。
     */
    private val framePacer = FramePacer({ animator.frameIntervalMs })

    /**
     * **宿主内**帧节拍取样（`recon/54` §5.2 的 C2，2026-09-23 引入）。
     *
     * ## 为什么需要它（以及为什么 C0 不够）
     *
     * `recon/54` §7 的 C0 探针在**探针 JVM** 里实测出 `javax.swing.Timer` 的投递节拍被量化到
     * **15.625 ms 的整数倍**，导致 `animation.frame_rate` 的 5 个选项只产出 2 种实得帧率。
     * **但那个量子是「进程级」的** —— 若宿主自己调用过 `timeBeginPeriod(1)`，
     * 宿主内的量子可能是 1 ms。⇒ **那只温度计量的是探针 JVM，不是宿主。**
     * 本字段就是宿主里的温度计。
     *
     * ## 输出去哪
     *
     * 只产出一行 `System.err.println`（由 `PluginLog` 镜像进 `super-island.log`），
     * 触发点是"累计 N 帧"与"帧驱动停止"两个 —— **不新增任何定时器**
     * （新起定时器会加进 EDT 争用名单，而 EDT 争用本身就会改变帧间隔，等于自己污染自己）。
     *
     * ## ⚠️ 它不参与任何断言
     *
     * 帧周期是机器相关的量。按 `recon/54` §5.3 的纪律「**时间只作报告，断言必须结构性**」，
     * 它永远**不进** `needsAnimationFrames()` 之类的判定路径。
     */
    private val framePacing = FramePacingMonitor()

    /**
     * ★★ **每秒帧率读数**（用户要求，2026-09-28）—— 与 [framePacing] **并存**。
     *
     * ## 为什么在已有 40 s 分位数摘要之外还要每秒一行
     *
     * [framePacing] 回答"这一段帧周期的**分布**好不好"（每 2400 帧 ≈ 40 s 一报）；
     * 本字段回答"**此刻**每秒跑了几帧、每帧实际多少 ms"。
     * 验收时问的是后者 ——"我刚拖了滑杆 / 挪了窗口，那一下掉帧没有"
     * 只有每秒行答得上（40 s 一报来不及）。
     *
     * ## 用什么当"上一次本帧的耗时"
     *
     * `onFrameTick` 的**上一帧总耗时** = "上一帧入口 → 本帧入口"的间隔
     * 减去 `javax.swing.Timer` 那一段的 `delay`。
     * ⚠️ 但 `delay` 每帧都在被 [framePacer] 改（绝对截止期），且"实际睡眠>delay"是常态
     * ⇒ **无法干净地反解**。故本字段报告的"平均/最大"口径就是
     * **帧间隔本身**（含 EDT 排队 + 本帧工作量之和），与"实得帧率"同源、互相自洽。
     * ★ **诚实标注**：它是**帧周期**，不是"纯渲染耗时"。两者差一个 `delay`。
     *   想要"纯渲染耗时"要看 [panel] 侧的绘制计时（另一件事，本字段不冒充）。
     *
     * ## ⚠️ 不参与任何断言
     *
     * 同 [framePacing]：时间只作报告，断言必须结构性。本字段**只产出日志**。
     */
    private val framePerSecond = PerSecondMeter(label = "动画帧", unit = "帧")

    /**
     * **本窗口所有过渡动画的唯一驱动**（2026-09-20 引入）。
     *
     * ## 为什么它不自己起定时器
     *
     * 它由上面这个 [frameTimer] 每帧调 `tick()`。若每个动效各起一个
     * `javax.swing.Timer`，就会有 N 个定时器争 EDT，且"什么时候该停"
     * 要各自判断 —— 而**停不下来就是常驻烧 CPU**。
     * 跑帧这件事**必须集中判定**，这正是 `needsAnimationFrames()` 存在的理由。
     *
     * ## 三个设置怎么进来
     *
     * 见 [applyAnimationSettings] —— 由设置订阅推入（与"现读"约定一致：
     * 每次变更推一次，而不是每帧读设置）。
     *
     * ## 生命周期
     *
     * 与窗口同寿。窗口销毁时由 `dispose` 调 `clear()` 丢弃在跑的动画
     * （不触发回调 —— 那时 UI 已经不该再被碰了）。
     */
    private val animator = AnimationRuntime()

    // ── 词岛整体淡入淡出（2026-09-20 引入）────────────────────────────────
    //
    // 用户原话：「**我觉得我们可以引入动画开关，然后增加对CPU GPU性能高的动画效果，
    // 以大大提升UX**」。
    //
    // ## 为什么淡的是**面板绘制**而不是 `window.opacity`
    //
    // `Window.setOpacity` 在 Windows 上要过一次原生调用（分层窗口属性），
    // 30–60 次/秒地调它有**闪屏与丢 topmost 的风险**；而且它作用在整个窗口上，
    // 与"面板自己按 `background.opacity` 合成"是两套叠加的 alpha 机制，
    // 容易出现"两处都对但结果不对"。
    //
    // 面板绘制路径**本来就在做 alpha 合成**（见 `backgroundWithOpacity`），
    // 故这里只是再乘一层 —— **纯 Java2D，无原生调用，与既有 alpha 天然一致**。
    // 代价是淡出期间窗口仍然存在（还能被点到）200 ms，这是可接受的。

    /**
     * **期望**可见性 —— 由 [applyVisibility] 每帧给出（它就是"用户/设置想要的状态"）。
     *
     * ⚠️ 它与 `window.isVisible` **不是一回事**：淡出期间期望已是 `false`，
     * 而窗口还可见（正在淡）。判"状态是否稳定"必须用这个字段，
     * 不能用 `window.isVisible`（那会让"淡出到一半又让它显示"被误判为无事可做）。
     */
    private var desiredVisible = true

    /** 当前整体不透明度（绘制时乘进 `g2.composite`）。**静止时恒为 0f 或 1f**。 */
    private var fadeAlpha = 1f

    /**
     * 词岛**整体微缩放的当前系数**（`1f` = 原尺寸）。与 [fadeAlpha] 同源、同一帧更新。
     *
     * ## 它解决什么（2026-09-21，用户要求"UX 至上"）
     *
     * 原先词岛显隐**只改透明度**。动效调研（`recon/46` §3.1）的结论是：
     * 纯透明度变化在视觉上**接近"闪烁"**，而**位移或缩放才提供重量感**
     * （这正是用户报「动效一闪而过、观感不好」的结构性原因之一）。
     *
     * 词岛是**贴在桌面上的悬浮件、没有"来向"**，故**只取缩放** ——
     * 缩放在方向上中立，不暗示"从屏幕某边飞进来"（那条是错误的空间隐喻，
     * 本项目 KDoc 与探针一直明确反对）。
     *
     * 幅度取自 `Motions.ISLAND_FADE.scaleFrom`（**0.97**，比调研给出的
     * "贴屏悬浮窗不浮夸上限 4%" 再收一档 —— 词岛面积大，同比例感知更强）。
     *
     * ⚠️ **静止时它恒为 `1f`**：`paintComponent` 只在 `!= 1f` 时才动 `g2`，
     * 从而保证"有动画"与"关掉了动画（`animation.enabled = false`）"两种情形
     * **画出的像素完全一致** —— 与 [fadeAlpha] 遵循同一条约定。
     */
    private var fadeScale = 1f

    /** 是否有淡入淡出正在跑。**用于避免"每帧重复起动画"导致动画永远走不完**。 */
    private var fadeRunning = false

    /**
     * 首次显隐是否已经"预热"过。
     *
     * ## 为什么需要它（**没有它启动时会闪一下**）
     *
     * `start()` 里先把 `window.isVisible = true` 摆好，随后 `applyVisibility` 才判定
     * 该不该显示。若插件启动时词岛本就该隐藏（`island.enabled=false` 等），
     * 不加这个标志就会**先淡入 200 ms 再淡出 200 ms** —— 用户看到一次闪烁。
     *
     * 故**第一次**显隐判定一律瞬时生效（`durationMs = 0`），此后才走动画。
     * 语义是"动画只描述**状态变化**，不描述**初始状态**"。
     */
    private var fadePrimed = false

    // ── 封面交叉淡化（2026-09-20）──────────────────────────────────────────
    //
    // 用户诉求：「**增加对 CPU/GPU 性能高的动画效果**」。本条管**切歌时的观感**。
    //
    // ## 为什么"交叉淡化"必须自己持有两张图
    //
    // 封面是**换曲瞬间整块替换**的，而且中间还有一段"等后台读盘"的空档
    // （`coverPending`）。故本组字段要同时解决**三件事**：
    //
    // | 情形 | 处置 | 不这么做会怎样 |
    // | --- | --- | --- |
    // | 等新图（`coverPending`） | **继续画旧图** | 封面先消失、再出现 —— **闪两下** |
    // | 新图到达 | **交叉淡化** | 图是"啪"地换掉的 |
    // | 本曲确实没有封面 | 旧图**淡出**后收起占位 | 上一个封面的图**永远留在那儿** |
    //
    // ## 一个 alpha 就够了
    //
    // 设 [coverMix] 为"**新图占比**"：旧图画 `1 - coverMix`、新图画 `coverMix`。
    // 三种情形共用同一条公式（见 [resolveCoverMix]）。

    /** 本帧要画的封面（`null` = 不画）。 */
    private var coverShown: BufferedImage? = null

    /** 正在淡出的**上一张**封面（`null` = 没有）。 */
    private var coverPrev: BufferedImage? = null

    /** [coverShown] 的占比 `0f..1f`。**静止时恒为 `1f`**（否则终值 ≠ 静态值）。 */
    private var coverMix = 1f

    /**
     * 是否有封面过渡在跑。
     *
     * ⚠️ 必须有它：`resolveCoverMix` **每帧都会被调用**，若不加这个门，
     * 每帧都会重新起一次动画 → `durationMs` 被反复重置 → **过渡永远走不完**
     * （表现为封面卡在两张图各半的"重影"上）。
     */
    private var coverMixRunning = false

    // ── 跟随封面取色的**色彩**也一起淡（2026-09-20，用户追加要求）─────────────
    //
    // 用户原话：「**跟随封面取色的色彩也做过渡动画呗**」。
    //
    // ## 为什么必须一起淡
    //
    // 背景 / 歌词 / 频谱的配色可以来自封面主色（三个 `*_color_mode = cover`）。
    // 若只淡**图**、不淡**色**，换曲瞬间就会出现"**图在慢慢淡，而整块配色「啪」地跳了**" ——
    // 那比完全不做淡化**更刺眼**，因为两者不同步、眼睛会立刻抓到那个错位。
    //
    // ## 为什么必须是**未插值**的两个端点
    //
    // 混合需要"旧色"与"新色"两个**端点**。若把"上一帧渲染用的值"当端点，
    // 那它本身就是插值出来的 → 再插一次会**复利式衰减**（永远到不了新色）。
    // 故这里存的是**两张图各自的主色**（未经插值），与 `coverMix` 无关。

    /** **上一张**图的主色（未插值）。交叉淡化时作为混合的起点。 */
    private var coverRgbPrev: Int? = null

    /** **当前** [coverShown] 那张图的主色（未插值）。每帧由绘制路径刷新。 */
    private var coverRgbShown: Int? = null

    /**
     * ★★★ 2026-10-13（**BUG-191**）：**跨曲目的主色保持** —— 抹掉"切歌一瞬间回默认色"。
     *
     * ## 用户报障（逐字）
     *
     * > 「**切歌一瞬间频谱色会回到默认再转下一首的颜色，能不能加个过渡渐变，盖掉这一段。**」
     *
     * ## 根因：`stickyCoverColorPath` 的粘性是**按曲目**的
     *
     * [resolveCoverRgb] 的粘性表第三行写得很清楚：
     * 「本帧无图，**曲目变了** / 上次没取到色 ⇒ `null` → 按 `manual` 渲染」——
     * ★ 那条规则对"本曲确实没有封面"是**对的**，但对"**新封面还在加载**"是**错的** ✗
     *
     * 切歌时的真实时序：
     *
     * ```
     * 曲目变 → path 变 → 粘性判据不成立 → coverImage 还没到 → coverRgb = null
     *        → CoverPalette.resolve 的 cover 档静默回退 manual ⇒ ★ 用户看到【默认色】✗
     * 若干帧后新封面到达 → 新主色可用 → 已有 260ms 交叉淡化把它淡过来 ✓
     * ```
     *
     * ⇒ ★ 用户描述的「**回到默认 → 再转下一首的颜色**」**逐字对应**上面两行 ✓
     *
     * ## 修法：**空窗期保持上一次的主色**（限时宽限）
     *
     * 新封面到达前，**继续用上一首的主色**（而不是回退 manual）——
     * ★ 于是"默认色"这一帧**根本不存在**，而新色到达时既有的
     * [blendedCoverRgb] 交叉淡化（260ms）自然接管 ⇒ **整段被过渡盖住** ✓
     *
     * ## ⚠️ 为什么必须是**限时**宽限（而不是无限粘住）
     *
     * 「本曲确实没有封面」（纯黑白封面 / 无内嵌封面且同目录无图）与
     * 「封面还在加载」在**单帧里无法区分**。若无限粘住，无封面的曲子会
     * **永远显示上一首的颜色** ✗ —— 比闪一下更糟。
     * ⇒ 取一个**宽限窗**：窗内保持（盖住加载期），窗外回退 manual（认账"本曲无封面"）✓
     *
     * ★ [COVER_RGB_HOLD_GRACE_MS] 取 **1800 ms**：比封面加载的常见耗时宽裕，
     *   又不至于让"无封面曲"长时间挂着别人的颜色。
     */
    private var coverRgbHold: Int? = null

    /** [coverRgbHold] 的**刷新时刻**（最后一次真正取到主色的 `currentTimeMillis`）。 */
    private var coverRgbHoldAtMs: Long = 0L

    // ── 歌词行切换：**旧行上滑退场**（2026-09-20）──────────────────────────
    //
    // ## 为什么只动旧行（见 `Motions.LYRIC_EXIT` 的 KDoc 全文）
    //
    // 歌词是**单槽**（用户明确要求"取消已唱歌词的显示"）→ 换行时新旧两段文字
    // **占同一个位置**。于是：
    //   · **交叉淡化** → 两段不同文字叠在一起，糊成一团；
    //   · **新行也滑动入场** → 在用户最需要读的那一刻给它加运动。
    //
    // 故只让旧行**上滑 + 淡出**。新行几何一个像素都不动 →
    // `drawnRows`（命中域）**完全不受影响**，这是"零风险"的关键。
    //
    // ── ⚠️ 2026-09-28：上面那条「新行也滑动入场」**已被用户推翻** ──────────
    //
    // 用户原话：「歌词的**消失动画是做了，但显现动画没做**，显得**割裂**（副歌词也是）」。
    // ⇒ 新行现在也有入场动画（见下面的 `mainEnter` 与 [Motions.LYRIC_ENTER] 的 KDoc，
    //    那里记录了"原记 X、实为 Y"的完整取证）。
    // ⇒ 「一个像素都不动」这条**已不成立**；取而代之的是**登记静止几何、
    //    只变换绘制输出**（见 [drawWithEnterTransform]）—— 于是命中域**仍然不受影响**，
    //    "零风险"这个**目的**保住了，只是实现手段从"不动"换成了"动了但不登记动的坐标"。

    /** 上一次画出的**当前行**文本。用于识别"换行了"。 */
    private var lastMainText: String? = null

    /** 正在退场的**上一行**文本（`null` = 没有）。 */
    private var exitingMainText: String? = null

    /** 退场进度 `0f..1f`（1 = 完全退场）。**静止时恒为 `1f`**。 */
    private var mainExit = 1f

    /** 是否有退场动画在跑。 */
    private var mainExitRunning = false

    /**
     * 入场进度 `0f..1f`（1 = 已落位）。**静止时恒为 `1f`**。
     *
     * ## 与 [mainExit] 的关系（镜像，但**方向相反**）
     *
     * | 量 | 0 的含义 | 1 的含义 |
     * | --- | --- | --- |
     * | [mainExit] | 旧行在原位（还没走） | 旧行已走完 |
     * | [this] | 新行在偏移位置（还没落位） | 新行在原位（已站好） |
     *
     * 两者**同时**在跑：换行那一刻旧行开始走、新行同时开始来。
     * 故它们是**两个槽位**（`lyric.exit` / `lyric.enter`），见 [Slots.LYRIC_ENTER]。
     *
     * ⚠️ 静止时必须是 `1f`（= 不施加任何位移/淡入）—— 这是"不做动画也不走形"的保证。
     */
    private var mainEnter = 1f

    /** 是否有入场动画在跑。 */
    private var mainEnterRunning = false

    /** 上一帧的歌词文档版本，用于识别"歌词换了 → 布局/文本要重建 → 立刻重绘"。 */
    private var lastDocVersion: Long = -1L

    /** 上一帧的位置，用于识别 seek 造成的跳变。 */
    private var lastPositionMs: Long = -1L

    // ── 生命周期 ──────────────────────────────────────────────────────────

    /**
     * 装配并显示窗口。**幂等**：重复调用只有第一次生效。
     *
     * 顺序是刻意的，每一步都依赖前一步：
     * ① 建 UI（必须在 EDT）；
     * ② 显示窗口 —— **必须先 `isVisible = true`**，因为 Win32 的 HWND 是在
     *    窗口**映射到屏幕**时才真正创建/稳定下来的，在此之前 `FindWindowW` 找不到它；
     * ③ 拿到 HWND 后应用扩展样式（穿透/置顶/工具窗）—— 这一步也必须等 ② 完成；
     * ④ 订阅设置变更。
     *
     * ⚠️ ③ 的「等 HWND 就绪」在实践中需要一点点延迟：Swing 的 `isVisible = true`
     * 是**异步**提交给 native 的。故这里不硬等，而是在 [resolveHwnd] 里做
     * 「立刻试一次 + `invokeLater` 再试一次」的两段尝试，两段都失败就永久降级
     * （窗口照常显示，只是没有穿透/置顶能力）。**不做轮询重试** ——
     * 那会在失败时留下一个永不退出的后台定时器。
     */
    fun start() = onEdt {
        if (started) return@onEdt
        started = true

        // `JWindow` 没有 `title` 属性（标题栏是装饰器提供的，JWindow 无装饰器），
        // 但 `Window.setName` 会经 AWT 传给底层 peer，使 `GetWindowTextW`
        // 返回这个名字 —— 这正是 findHwndByTitle 依赖的查找键。
        window.name = windowTitle
        // 前提 2：显式设全透明背景。不设的话「圆角之外」的区域会被画成默认不透明色。
        window.background = Color(0, 0, 0, 0)
        window.contentPane.background = Color(0, 0, 0, 0)
        window.contentPane.add(panel)
        // 注意：`JWindow` 没有 `defaultCloseOperation`（那是 JFrame/JDialog 的）。
        // 窗口的销毁由本类的 close() 显式负责，这也更符合"插件卸载时统一释放"的生命周期。

        // 右键菜单：**不在 start 时构造**，而是在每次 popup 触发时构造。
        // 理由见 IslandPopupMenu 的 KDoc —— 3 个 checkbox 的勾选态必须反映当下设置，
        // 长驻菜单需要手工同步状态，那是"一份状态两处表示"的腐化点。
        installPopupHandler()

        applyGeometry(snapshot)
        // ⚠️ 必须**显式推一次**：`SettingsRuntime.subscribe` 只登记监听者、
        //    **不会立即回调**（见其实现）。漏了这一句，`animation.quality`
        //    要等用户**第一次改设置**才生效 —— 表现就是"我设了高帧率但没变化"。
        applyAnimationSettings(snapshot)
        restoreSavedPosition(snapshot)
        window.isVisible = true

        // ── 诊断日志（2026-09-18 加）────────────────────────────────────
        //
        // **为什么必须加**：用户报"插件能启动但词岛不见了"，而 `applyVisibility`
        // 的**五条隐藏判据全部被排除**（enabled=true、三个隐藏开关 false、
        // noLyrics.mode=PLACEHOLDER）。也就是说**窗口创建阶段**出了问题 ——
        // 而那里**此前零日志**，只能靠猜。
        //
        // 打这四条的理由：
        //  · `bounds` —— 位置/尺寸是否为 0，或是否跑到屏幕外（用户设置里
        //    `window.position="1263,724"` + `max_width=1439`，在 2560 宽的屏上
        //    右边界 2702 会超出 142px，虽仍可见但值得记录）
        //  · `isVisible` —— Swing 层的可见性（与 Win32 层可能不一致）
        //  · `isDisplayable` —— 为 false 说明 peer 还没建好，那 `hwndOf` 必然失败
        //  · `opacity` —— 若为 0 则窗口"显示着但完全透明"，用户看到的就是"不见了"
        //
        // 打在 `isVisible = true` **之后**：这些值在这一刻才定型。
        System.err.println(
            "[CDL] 窗口已显示：bounds=${window.bounds} " +
                "isVisible=${window.isVisible} isDisplayable=${window.isDisplayable} " +
                "opacity=${window.opacity} screen=${java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds}"
        )

        // 前提 3：检测 per-pixel 半透明支持，不支持时给一个可读的降级底色。
        // 放在 isVisible 之后检测是等价的（该能力是"图形环境级"的，与窗口无关），
        // 但放这里能让降级动作与显示动作挨着，读代码时因果关系清楚。
        if (!supportsPerPixelTranslucency()) {
            System.err.println("[CDL] 图形环境不支持逐像素半透明，词岛降级为不透明深色底。")
            window.background = DEGRADED_BACKGROUND
        }

        // 拖动：按下记起点、拖动改位置、松手落盘位置。
        installDragHandler()
        // 悬停：命中歌词字形 → 展开播放控件（见 installHoverHandler）。
        // 必须在拖动之后装：拖动处理里要判"按下点是否落在控件上"，
        // 而那条判断依赖悬停状态机给出的展开态 —— 顺序反了会在启动瞬间读到未初始化的状态。
        installHoverHandler()
        resolveHwnd()
        applyExtensionStyles(snapshot)
        installSettingsSubscription()
        // 帧驱动最后启动：它读快照并触发重绘，必须等窗口已可见、
        // 外观已应用（否则第一帧会以默认外观画一次，造成可见的闪一下）。
        applyVisibility(snapshot)
        // ── 初始状态**瞬时**生效，之后的状态变化才走淡入淡出（2026-09-20）──
        //
        // ⚠️ 顺序不能反：必须**先**让 `applyVisibility` 把初始显隐摆好，
        //    **再**置 `fadePrimed` 并同步三个字段。理由见 [fadePrimed] 的 KDoc ——
        //    启动时若词岛本就该隐藏，动画会让它"先淡入再淡出"（可见的闪一下）。
        //
        // 同时把 `fadeAlpha` / `desiredVisible` 对齐到**实际的**窗口状态：
        // 上面那段 `applyVisibility` 可能已经调过 `setVisible(false)`，
        // 而那时 `fadePrimed` 还是 false → 它是瞬时生效的，窗口确实已经不可见。
        // 不对齐就会出现"期望可见但窗口不可见"，下一次 `setVisible(true)` 会
        // 因为 `visible == desiredVisible` 直接 early-return → **窗口再也出不来**。
        fadePrimed = true
        desiredVisible = window.isVisible
        fadeAlpha = if (window.isVisible) 1f else 0f
        syncFrameDriver()
        // 看门狗**最后**启动：它一跑起来就可能改显隐（`hide_fullscreen`），
        // 必须等窗口已可见、样式已应用、悬停层已装好 —— 否则第一次检测触发的
        // `setVisible(false)` 会在一个还没装配完的窗口上执行。
        startWatchdog()
    }

    /** 隐藏并释放窗口。可在任意线程调用。 */
    override fun close() = onEdt {
        started = false
        // 定时器必须先停：它每 33ms 会读快照并调用 repaint，若不先停，
        // 我们刚 dispose 掉的窗口会在下一 tick 被再次触碰（AWT 会抛
        // IllegalStateException 或静默画到已销毁的 peer 上）。
        // 更关键的是：定时器持有指向本类的 lambda，不停就是真实的内存泄漏
        // —— 插件卸载后定时器仍在 EDT 上跑，永不释放。
        stopFrameDriver()
        // ★ L3 采集线程同理，且理由更硬：它持有 `Robot`（系统资源）与指向本 panel 的
        //   lambda。不停 ⇒ 插件卸载后仍每 100 ms 采一次全屏（8.25 ms/次），
        //   而"采什么"已无意义 —— 既烧 CPU 又是不必要的截屏权限持有。
        //   `stop()` 幂等；两个引用置 null 让渲染侧**立刻**回到"不画背景"。
        runCatching { panel.disposeL3() }
            .onFailure { System.err.println("[L3] 关闭时停采集线程失败（已忽略）：$it") }
        // 悬停定时器同理必须先停：它持有指向本类的 lambda，不停就是真实的内存泄漏。
        stopHoverTicker()
        // 看门狗同理（同一个理由）。它虽然低频，但**不停就会在插件卸载后继续
        // 对着一个已 dispose 的窗口调 `SetWindowPos`** —— 那不只是泄漏，
        // 还会去动一个可能已被系统回收复用的 HWND。
        stopWatchdog()
        // 封面补重绘定时器同理（同一个理由：它持有指向本类的 lambda）。
        // 它的间隔只有 120 ms 且单次触发，但"短"不是"不会泄漏"——
        // 插件卸载后它仍会在 EDT 上跑一次并持有一个已销毁窗口的引用。
        coverRepaintTimer?.stop()
        coverRepaintTimer = null
        // ★★ 2026-10-05（同族核查补漏 · 自查）：**秒级唤醒定时器也要停**。
        //   它虽然是 `isRepeats = false` 的一次性自调度（`rescheduleClockWake` 里
        //   先 `stop` 再判 `started`），理论上"关闭后最多多触发一次 `repaint` 就自灭"，
        //   ★ **但"理论上不会持续泄漏"不等于"可以不清理"**：
        //   · 它持有指向 `panel` 的 lambda ⇒ 关闭后那一次回调仍会 `repaint` 一个**已销毁**的窗口；
        //   · 且**下一次 `rescheduleClockWake` 会不会被调到**取决于调用方，
        //     把"最终会自灭"当作不清理的理由，就是 BUG-152 那种"依赖调用方记得"的思路。
        //   ⇒ ★ **在唯一的生命周期出口显式停掉**，与上面几个定时器同族。
        clockWakeTimer?.stop()
        clockWakeTimer = null
        // 封面主色缓存也要清：它持有 `BufferedImage`（一张 1000×1000 的 ARGB 图是 4 MB）。
        // 窗口销毁后这份引用若不放开，会一直挂到本对象被回收为止。
        coverColorImage = null
        coverColorRgb = null
        runCatching { settingsSubscription?.close() }
        settingsSubscription = null
        // 渲染缓存里存的是 TextLayout / Color / Shape，都只在 EDT 用。
        // 窗口销毁即释放，让这些对象尽快可回收（尤其 TextLayout 单个实例不小）。
        runCatching { renderCache.clear() }
        runCatching { karaokeRenderer.invalidate() }
        // 丢弃在跑的过渡，**不触发回调** —— 此刻窗口即将 dispose，
        // 让 onDone 再去碰 UI（如 `hideWindowNow` 里的 `syncHoverTicker`）
        // 只会操作一个正在死掉的窗口。`clear()` 的语义正是"什么都不做"。
        runCatching { animator.clear() }
        fadeRunning = false
        runCatching { window.isVisible = false }
        runCatching { window.dispose() }
    }

    /** 当前窗口是否可见（菜单的「显示词岛」要用它判断当前状态）。 */
    val isVisible: Boolean
        get() = window.isVisible

    /**
     * 按 `island.enabled` 显示/隐藏。**只做显隐，不销毁窗口**
     * ——销毁再建会重新走一遍 HWND 查找与样式应用，代价高且可能失败。
     *
     * ## 2026-09-20：显隐改为**淡入淡出**
     *
     * 原来这里是"立刻 `window.isVisible = visible`" —— 词岛的**出现与消失都是突变的**，
     * 而词岛常驻在桌面上、面积不小，突变在观感上是明显的"闪"。
     * 现在：淡入 → 窗口出现后 alpha 从 0 到 1；淡出 → alpha 从 1 到 0，
     * **走完才真正 `setVisible(false)`**。
     *
     * ## ⚠️ 三处必须看清楚的细节
     *
     * **① "状态稳定"的判据不能只看 `window.isVisible`。**
     * 淡出期间期望已是 `false`、窗口却仍可见。若用 `window.isVisible` 判稳定，
     * "淡出到一半又让它显示"会被误判为无事可做 → 画面**永久卡在半透明**。
     * 故判据是 `visible == desiredVisible && !fadeRunning`。
     *
     * **② 每帧都会被调用（`applyVisibility` 在帧循环里）。**
     * 所以这个函数**必须幂等** —— 状态稳定时立即返回，绝不能在稳定态下重起动画
     * （那会让 `durationMs` 每帧重置 → 动画永远走不到终点）。
     *
     * **③ 淡出"走完才隐藏"由动画的 `onDone` 负责**（见 [startFade]）。
     * 被取消的过渡**不触发 `onDone`**（见 `AnimationRuntime.startIn` 的契约）——
     * 这正是"淡出途中又让它显示"不会把窗口藏掉的原因。
     */
    fun setVisible(visible: Boolean) = onEdt {
        if (!started) return@onEdt
        // ── 状态稳定 → 立即返回（本函数每帧被调用，这里是最热的一行）──
        if (visible == desiredVisible && !fadeRunning) return@onEdt
        desiredVisible = visible
        if (visible) {
            if (!window.isVisible) {
                // 从"完全隐藏"进入可见：窗口先出现，alpha 从 0 起淡入。
                // ⚠️ 不能沿用旧的 `fadeAlpha`（淡出完成后它是 0，但若因别的原因
                //    隐藏过就可能是 1）—— 显式置 0 才是"从无到有"的确定起点。
                fadeAlpha = 0f
                window.isVisible = true
                // 重新显示后 HWND 可能已变（JWindow 在 hide/show 之间可能重建 peer），
                // 故重新解析一次并重放样式。失败也只是降级，不抛。
                resolveHwnd()
                applyExtensionStyles(snapshot)
                // 重新显示后再重申一次置顶（2026-09-18）。
                // 理由：`hide/show` 会重建 peer，而**新 peer 在 topmost 组里是"新加入者"**——
                // 它在组内的位置由创建时机决定，未必在任务栏之上。
                // `applyExtensionStyles` 已设过 `HWND_TOPMOST`，但那是"加入组"；
                // 这里补一次 `SetWindowPos` 才是"提到组内最前"。
                // ⚠️ 顺序必须在 `resolveHwnd()` 之后 —— 否则拿的是旧句柄。
                raiseToTopNow()
            }
            startFade(to = 1f)
        } else {
            if (!window.isVisible) {
                // 本来就没显示 —— 无需淡出。但要把 alpha 归位到 0，
                // 否则下次显示会从"上一个残留值"开始（表现为淡入第一帧闪一下）。
                fadeAlpha = 0f
                return@onEdt
            }
            startFade(to = 0f)
        }
    }

    /**
     * 起一次淡入或淡出。**必须在 EDT 上调用。**
     *
     * ## 时长与缓动都来自 [Motions.ISLAND_FADE]
     *
     * **不在调用点写数字** —— 那是"换风格/调时长要改 N 处"的根源。
     * 本方法只负责"起一次过渡 + 处理收尾"，动效参数全在动画包里。
     *
     * ## 为什么两个方向用**同一个**缓动（`EASE_OUT`）
     *
     * 原记（本方法初版）：淡入用 `EASE_OUT`、淡出用 `EASE_IN_OUT`（两头慢）。
     * 实为：`EASE_OUT` 对**两个方向都更合适**，故统一。
     *   · 淡入：开头快 = "已经触发了，尽快看到"；结尾慢 = 落位不生硬。
     *   · 淡出：开头快 = **赶紧让开**（用户暂停时的诉求正是"别挡着我"）；
     *     结尾慢 = 消失得柔和，不像"被掐掉"。
     *   而 `EASE_IN_OUT` 的"两头慢"在淡出时会表现为**"它赖着不走"**。
     *   取证：`Motions.ISLAND_FADE.easing` 是本方法唯一读的缓动来源。
     *
     * ## 为什么"真正隐藏"放在 `onDone` 里
     *
     * 因为只有它知道"淡出**走完了**"。放在 `setVisible` 里做不到 ——
     * 那里只能知道"期望隐藏"，而此刻 alpha 可能还是 1（刚起动画）。
     * 立刻隐藏就等于没有淡出。
     *
     * ## 首次显隐瞬时生效
     *
     * 见 [fadePrimed] 的 KDoc：动画只描述**状态变化**，不描述**初始状态**。
     */
    private fun startFade(to: Float) {
        val spec = Motions.ISLAND_FADE
        // `Motions.ISLAND_FADE.slot` 是 `Slots.ISLAND_FADE`（非空）—— 词岛淡入
        // **由帧循环驱动**，必须有槽位。若有人把它改成 `null`，那是**声明错误**：
        // 这里用 `requireNotNull` 直接报出来，而不是静默回退到一个兜底槽位
        // （静默回退会让"两条动效抢同一个槽位"这类错误更难发现）。
        // 探针 §9 已断言这条不变量。
        val slot = requireNotNull(spec.slot) {
            "Motions.ISLAND_FADE 必须声明槽位（它由帧循环驱动）"
        }
        // 首次显隐不做动画（否则"启动时本该隐藏"会先淡入再淡出，闪一下）。
        val durationMs = if (fadePrimed) spec.durationMs else 0L
        fadePrimed = true
        fadeRunning = animator.startIn(
            slot = slot,
            from = fadeAlpha,
            to = to,
            durationMs = durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                fadeAlpha = v
                // ★ 微缩放与 alpha **共用同一个进度值、同一条缓动曲线**
                //   （调研 §4 反例 4：同一动效的各属性若各用一条缓动，视觉会"散架"）。
                //
                // 起始缩放的**唯一来源**：显式 `scaleFrom` 优先，否则取风格自带的
                // （`POP` 有 0.94，`FADE`/`SLIDE` 均为 1f）。
                // 这样 `FADE + scaleFrom = 0.97` 得到 0.97→1.0 的插值，
                // 而不是"常数 0.97"（后者不随时间变化，等于没动）。
                val startScale = if (spec.scaleFrom != 1f) {
                    spec.scaleFrom
                } else {
                    spec.style.scaleAt(0f)
                }
                fadeScale = startScale + (1f - startScale) * v
                panel.repaint()
            },
            onDone = {
                fadeRunning = false
                // ⚠️ 再确认一次 `desiredVisible`：`onDone` 只在"过渡正常走完"时触发
                //    （被取消不触发，见 startIn 的契约），但这里复核一次，
                //    让"窗口被藏掉"在结构上只可能发生在期望隐藏时。
                if (to == 0f && !desiredVisible) hideWindowNow()
            },
        )
        // ★★ 2026-10-18（P0）：**起完过渡必须唤醒帧循环**。
        //   本文件 `animator.tick()` 只在 `onFrameTick` 一处被调；暂停态下帧驱动已停，
        //   而淡入/淡出仍可能被非帧路径触发（看门狗判全屏、`island.enabled` 切换）。
        //   少了这一句：`fadeRunning` 永为 true、`onDone` 永不触发 ——
        //   隐藏方向窗口留在屏上，显示方向 `isVisible=true` 但 `fadeAlpha=0`（完全看不见）。
        //   与 `startBandCollapse` 末尾那句是同一件事（那里早就这么做了）。
        syncFrameDriver()
    }

    /**
     * 真正把窗口藏起来（淡出完成后调）。**必须在 EDT 上调用。**
     *
     * 这里的四句与旧 `setVisible(false)` 分支**逐字一致** —— 淡出只是把
     * "何时执行它们"从"立刻"推后到"淡完"，动作本身一个字都没改。
     */
    private fun hideWindowNow() {
        if (!window.isVisible) return
        window.isVisible = false
        fadeAlpha = 0f
        // 缩放也复位 —— 保证"隐藏后再显示"是从干净的静止态重新起过渡，
        // 而不是带着上一轮剩下的半程缩放值（那会让下次显示"跳一下"）。
        fadeScale = 1f
        // 隐藏时把悬停复位：不复位的话，窗口下次显示会带着一个陈旧的
        // `enterSince`（窗口隐藏期间定时器已停，那个时刻永远不会推进），
        // 表现为"刚显示出来、鼠标还没碰到字，控件就自己弹开了"。
        hover.reset()
        // ★ 带子也必须一起复位（2026-09-22）。`hover.reset()` 只把 `expanded/expansion`
        //   清零，而 `bandFraction` 是**独立字段** —— 若隐藏发生在收缩动画中途，
        //   它会停在半程值上。下次显示时 `applyWindowSize()` 按"未展开"收窗口，
        //   而岛体形状走 `bandGeometryNow()`（读 `bandFractionNow`）⇒ 会按半程高度
        //   画一个**被窗口裁掉一截**的岛。复位后"隐藏→再显示"从干净的收起态起过渡。
        resetControlsBand()
        hoveredControl = null
        syncHoverTicker()
        applyWindowSize()
        // ★ 2026-10-18：窗口真正藏起来之后，释放"只用于绘制"的缓存。
        releaseIdleCaches()
    }

    /**
     * 释放只用于绘制的缓存（**词岛隐藏后**调用，2026-10-18）。
     *
     * 三项都是纯派生物，下次显示按当前设置重算即可：
     * · `RenderCache.layouts`（最多 64 个 `TextLayout`，换曲后旧词条也在）；
     * · `CoverScaler` 的源图 + 输出图（1024² ARGB ≈ 4 MB）；
     * · 迷彩贴图（岛宽 × 贴图高 ≈ 0.6 MB）。
     *
     * 不释放的东西（有意）：
     * · `HostFonts` 的字体缓存 —— `Font` 对象本身很小，而重解析要 40 ms/族；
     * · `AnimationRuntime` / `FramePacer` 的槽位与计数 —— 它们是常驻状态，不是缓存。
     */
    private fun releaseIdleCaches() {
        renderCache.clear()
        CoverScaler.release()
        dev.cdl.render.releaseCamoCache()
    }

    /**
     * 安装右键菜单触发。
     *
     * ## 为什么监听 `isPopupTrigger` 而不是判断 `isRightMouseButton`
     *
     * `isPopupTrigger` 是 AWT 抽象出来的**平台无关**的"弹出菜单应该发生"的信号：
     * Windows 上它落在 `mouseReleased`，部分 Unix 上落在 `mousePressed`，
     * 且能正确处理"按住右键拖动取消"这类细节。自己判断 `isRightMouseButton`
     * 会在按下的瞬间就弹菜单，与平台习惯不符（也想在按下时开始拖动）。
     *
     * 故这里在 `pressed` 与 `released` **两个**回调里都检查它 ——
     * 这是 Swing 官方推荐写法，因为不同平台触发时机不同。
     */
    private fun installPopupHandler() {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                maybeShowPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowPopup(e)
            }

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                // 传入的是**本窗口自己**，这样菜单的消失行为与窗口正确关联。
                IslandPopupMenu.showAt(settings, this@IslandWindow, panel, e.x, e.y)
            }
        }
        panel.addMouseListener(listener)
    }

    /** 供菜单等外部触发重绘（设置变了但不走订阅路径时用）。可在任意线程调用。 */
    fun repaint() = onEdt { panel.repaint() }

    // ── 显隐与帧驱动 ─────────────────────────────────────────────────────

    /**
     * 按设置决定窗口显隐。**必须在 EDT 上调用。**
     *
     * ## 两个开关是**两回事**，不要合并
     *
     * - `island.enabled`（默认 true）：用户关掉它表示"不要这个词岛"。
     *   本类让窗口**整体隐藏**（`setVisible(false)`）。
     *   注意渲染层**同时也**在 `paintComponent` 里检查这一项并直接 return ——
     *   双保险是有意的：隐藏窗口依赖 Win32 的 peer 状态（可能因 HWND 解析失败
     *   而没真正隐藏），而 `paintComponent` 的 return 是纯 Swing 层的、必然生效。
     * - `island.hide_paused`（默认 false）：**只在暂停时**隐藏，播放时自动回来。
     *   这是"暂停时别挡着用户干活"的诉求，与"是否启用词岛"无关。
     * - `island.hide_fullscreen`（默认 true，2026-09-18 接线）：**前台窗口全屏时**隐藏，
     *   退出全屏自动回来。触发源是 [onWatchdogTick] 的低频轮询（无事件可依赖）。
     * - `island.auto_hide_on_hover`（默认 false，2026-09-18 接线）：鼠标**进入词岛区域**时
     *   隐藏，**移开即恢复**。同样是临时隐藏，理由见下面那段。
     *
     * 四者都反映到同一个 `window.isVisible`，但**判定条件各不相同**；
     * 三个"临时隐藏"（paused / fullscreen / hover）都是**同一个优先级层**，
     * 谁先命中谁生效 —— 因为它们的语义都是"此刻不该显示"，无所谓哪个更优先。
     */
    private fun applyVisibility(fresh: SettingsSnapshot, playing: Boolean? = null) {
        if (!started) return
        val enabled = boolOf(fresh, "island.enabled")
        onEnabledChanged?.invoke(enabled)
        if (!enabled) {
            setVisible(false)
            logVisibilityChange("island.enabled=false")
            return
        }
        // 暂停时隐藏：只在调用方明确告知播放态时才判定（帧驱动每次 tick 都会告知）。
        val hidePaused = boolOf(fresh, "island.hide_paused")
        val isPlaying = playing ?: snapshotSupplier().isPlaying
        if (hidePaused && !isPlaying) {
            setVisible(false)
            logVisibilityChange("island.hide_paused")
            return
        }
        // ── 全屏时隐藏（`island.hide_fullscreen`，2026-09-18 接线）─────────────
        //
        // ⚠️ 这个键此前是**死设置**：注册表里有、右键菜单里有、设置窗口里有，
        // 但**没有任何消费点** —— 全屏检测从未实现。用户勾了它"没反应"。
        //
        // 判据来自 [foregroundFullscreen]（由 [onWatchdogTick] 低频刷新），
        // 本处**只读不算** —— 理由见那个字段的 KDoc：显隐判定不该做 I/O。
        //
        // ⚠️ 检测必须传**词岛自己的 hwnd** 作为排除项，否则词岛（topmost 且铺满）
        // 会把自己判成"全屏应用"，表现为"一开这个设置就立刻隐藏自己、且看不出为什么"。
        // 这一条已经收在 [WindowInterop.isForegroundFullscreen] 内部，此处只需保证
        // 传进去的是**当下**的 hwnd（见 [onWatchdogTick]）。
        //
        // 与 `hide_paused` 一样，这是**临时隐藏**：退出全屏后 [onWatchdogTick]
        // 会检测到 `foregroundFullscreen = false` 并把窗口放回来。
        // 与 `hide_paused` 的**唯一区别**是触发源：播放态有宿主回调事件，
        // 而全屏状态**没有任何事件**能送到词岛（全屏的是别的应用）——
        // 故它必须靠常驻定时器轮询，这也是本项目里少见的"必须有常驻定时器"的场合。
        if (boolOf(fresh, "island.hide_fullscreen") && foregroundFullscreen) {
            setVisible(false)
            logVisibilityChange("island.hide_fullscreen")
            return
        }
        // ── 鼠标经过时自动隐藏（`island.auto_hide_on_hover`，2026-09-18 接线）──
        //
        // ⚠️ 同为死设置：此前只有一个菜单 `switchItem`，没有任何消费点。
        //
        // ## 语义：**临时**隐藏，鼠标移开即恢复
        //
        // 原实现 0.8.9 的同类功能就是这个语义。**不能做成永久隐藏** ——
        // 词岛一藏，右键菜单也点不到（菜单挂在词岛面板上），用户将**没有任何
        // 界面内手段**把它找回来（只剩设置窗口/托盘，那是"用户得先知道去哪找"的
        // 前提）。那是**不可逆困境**，与本项目在 [applyRegionalClickThrough]
        // 里"算不清就按不穿透"是同一条纪律：**绝不制造用户无法自救的状态**。
        //
        // 恢复路径依赖 [onHoverTick] 的常驻轮询 + [panelOriginCache]：
        // 窗口隐藏后收不到鼠标事件，只能靠 `MouseInfo` 主动看光标是否已离开词岛矩形。
        //
        // ## 为什么要求 [shouldDriveHover]（整窗穿透时不生效）
        //
        // 整窗穿透（`island.interactive` = 开）时 [onHoverTick] **完全不驱动悬停**
        // （用户明确要求，见 [shouldDriveHover] 的 KDoc），于是 [lastInside]
        // 会停在一个**陈旧值**上。若还用它判显隐，就会出现"穿透 + 自动隐藏"
        // 组合下词岛被一个过期状态永久藏起来 —— 同一个不可逆困境。
        // 故这里显式要求"悬停判定真的在跑"才采纳 [lastInside]。
        if (boolOf(fresh, "island.auto_hide_on_hover") &&
            shouldDriveHover(clickThroughActive) &&
            lastInside
        ) {
            setVisible(false)
            logVisibilityChange("island.auto_hide_on_hover")
            return
        }
        // ── 无歌词时隐藏整个词岛（`layout.noLyrics.mode = HIDE_ISLAND`）────────
        //
        // ⚠️ 这个键此前是**死设置**（2026-09-16 修复）。放在**可见性**这一层而不是
        // 绘制层，是因为"隐藏窗口"是生命周期动作：绘制层能做的只是"不画字"，
        // 而窗口仍占着屏幕、仍能拖动与右键 —— 那不是用户选 HIDE_ISLAND 想要的效果
        // （纯音乐场景下他不希望有个空悬浮件挡着）。
        //
         // 判据用"所有槽位是否都为空"而不是"文档是否为空"：前者是渲染层与时间轴层
        // **共用**的同一份判定（见 drawLyricSlots 的 `present.isEmpty()`），
        // 用它就不会出现"窗口还在但一行都没画"或反过来的错配。
        //
        // ⚠️ **已知交互（如实记录，未特殊处理）**：选 HIDE_ISLAND 时点右键菜单的
        // 「找回词岛」会"看起来没反应" —— 因为 `recover()` 让它可见之后，
        // 下一帧本判定又把它藏回去（当前曲目确实没有歌词）。
        // 这是该设置的**语义后果**而不是缺陷：用户选了"无歌词时隐藏整个词岛"，
        // 那"无歌词时看不到词岛"就是正确行为。若用户觉得困扰，改回 HIDE_LINE 即可。
        // 不为它加例外，是因为例外会让"这个开关到底管不管用"变得不可预测 ——
        // 那比"看起来没反应"更糟。
        val hideForNoLyrics = NoLyricsMode.parse(textOf(fresh, "layout.noLyrics.mode")) ==
            NoLyricsMode.HIDE_ISLAND
        if (hideForNoLyrics) {
            val snap = snapshotSupplier()
            // ── 判据修正（2026-09-18，P0）─────────────────────────────────
            //
            // 原记：`snap.currentLine == null && snap.prevLine == null && snap.nextLine == null`
            // 实为：**`HIDE_ISLAND` 在当时的默认设置下永不生效** ——
            //   设置项 `layout.emptySlot.keepPlaceholder`（**已于 2026-09-18 移除**）默认 `true` 时，
            //   `PlaybackTimeline.nextLineFor()` 会返回一个**空文本的占位行**（非 null），
            //   于是第三个条件恒为假 → `noLyrics` 恒为 `false` → 永远走不到 `setVisible(false)`。
            //   即：**这个开关被另一个设置项未文档化地门控了**，而用户看到的
            //   "选了『无歌词时隐藏整个词岛』却没反应"就是这么来的。
            //
            //   `slotsFor` 只输出 `current` **一个**槽位 —— **已唱行与未唱行一律不绘制**
            //   （用户明确要求「多行歌词时不显示未唱行」+「取消已唱歌词的显示」，
            //    见 `slotsFor` 的 KDoc）。
            //   所以"词岛上有内容"的**充要条件**就是"current 非空"。
            //   把不参与绘制的 `nextLine` 算进来，等于用一个**永远不会显示的东西**
            //   去决定"要不要隐藏"，那在语义上本来就是错的。
            //
            // ⚠️ 后续（2026-09-18 用户裁定）：该设置项因"两个分支在真机上均不可达"
            //   被**整体移除**（审计见 `recon/39`），故上面这条修正不再有"解耦"可言 ——
            //   它现在只是把判据收敛到它本该有的样子。
            //
            // ★★ 2026-09-28 **再次修正**（用户报障「无歌词时…判定不准确(时显时不显)」）：
            //   原来判的是 `currentLine == null && prevLine == null`，它有两个问题：
            //   ① 后一个条件**恒为真**（`prevLine` 在 `index == -1` 时必为 null）—— 是**空条件**；
            //   ② 真正的错在于"**还没唱到第一行**"被当成了"没有歌词" ⇒ 每首歌开头几秒
            //      藏掉整个词岛、第一行来了又冒出来（用户看到的"时显时不显"）。
            //   现改判 [noLyricsDisplay] —— 与绘制层（`drawLyricSlots`）**同一个函数**，
            //   于是"无歌词"这个状态在全项目只有**一处定义**（纪律 15 / 纪律 10）。
            val noLyrics = noLyricsDisplay(
                lyricLineCount = snap.lyricLineCount,
                hasCurrentLine = snap.currentLine != null,
            ) == NoLyricsDisplay.NO_LYRICS
            if (noLyrics) {
                setVisible(false)
                logVisibilityChange("layout.noLyrics.mode=HIDE_ISLAND")
                return
            }
        }
        setVisible(true)
        logVisibilityChange("")
    }

    /**
     * 显隐**原因**变化时打一行日志（2026-09-18 加）。
     *
     * ## 为什么需要它
     *
     * 用户报「插件能启动但**词岛不见了**」，而 [applyVisibility] 有**五条**
     * 隐藏路径 —— 从外面**看不出是哪一条**触发的。而本函数每帧（33 ms）调一次，
     * 每条都打会**爆日志**、一条都不打则**无从排查**。
     *
     * ## 去噪方式：记「原因」这个值，只在它变化时打
     *
     * 照项目既有纪律（"**记键集合而不是计数**，只在集合变化时打一行"）——
     * 于是日志量与"**显隐状态变化的次数**"成正比（分钟级），
     * 而不是与帧数成正比（30 次/秒）。
     *
     * ⚠️ 这条日志**必须打 stderr**：宿主是 jpackage GUI 子系统，
     * `System.out` 在 `PluginLog` 接管前会**静默丢失**（见项目纪律）。
     * stderr 已被 `PluginLog` 的 `TeeOutputStream` 接管，会进 `super-island.log`。
     *
     * @param reason 空串 = **可见**（没有隐藏原因）；否则是触发隐藏的分支名，
     *   取值与 `SettingRegistry` 的键名一致，可直接 grep。
     */
    private fun logVisibilityChange(reason: String) {
        if (reason == lastVisibilityReason) return
        lastVisibilityReason = reason
        System.err.println(
            "[CDL] 词岛显隐变化：" + if (reason.isEmpty()) "可见" else "隐藏（原因：$reason）"
        )
    }

    /** 上一次的显隐原因（空串 = 可见）。初值用 `null` 保证**第一次一定打一行**。 */
    private var lastVisibilityReason: String? = null

    /**
     * 同步帧驱动的启停。**必须在 EDT 上调用。**
     *
     * ## 为什么由"播放态"决定启停，而不是无条件常开
     *
     * 词岛的画面只在播放时变化：暂停后位置不再外推（见 `PlaybackTimeline`
     * 的 `extrapolatedPosition`）、歌词行不再切换，画面是**静止的**。
     * 此时定时器每次 tick 都是白跑一趟：取快照（读若干 `@Volatile` 字段 +
     * 一次 `lineAt` 二分查找）+ 判定 + 可能的一次 `repaint`。
     * 单次开销不大，但它是**每秒 30 次的永久占用**，且会让 CPU 无法进入深度空闲
     * （定时器唤醒本身就是一次调度）。笔记本电池场景下这是可感知的代价。
     *
     * 故：播放 → 启动；暂停 → 停止。停止后画面**保持最后一帧**（AWT 不会清屏），
     * 这正是"暂停"应有的观感 —— 歌词停在那一句上。
     *
     * ## ⚠️ 这里有一个"谁来唤醒"的闭环问题（本方法的调用者必须清楚）
     *
     * 定时器在发现 `!isPlaying` 时会**自停**（见 [onFrameTick]）。于是启动的
     * 责任不能只靠本方法在 `start()` 与设置订阅里被调用 —— 那个时机之外
     * （用户在播放器里按了播放）没人会来调。
     *
     * 因此**组合根在把播放态事件交给时间轴之后，必须调用 [onPlaybackStateMaybeChanged]**。
     * 那条路径是宿主回调线程，所以那个方法内部会自己切到 EDT ——
     * 调用方不需要关心线程。
     */
    fun onPlaybackStateMaybeChanged() = onEdt { syncFrameDriver() }

    private fun syncFrameDriver() {
        if (!started) return
        if (snapshotSupplier().isPlaying) {
            startFrameDriver()
        } else if (panel.needsAnimationFrames()) {
            // 暂停了，但还有**收尾动画**要跑（频谱归零 / 长歌词滚动）——
            // 保持驱动，由 [onFrameTick] 在收尾完成后自停。
            // ⚠️ 不能在这里直接 `stopFrameDriver()`：那会让"暂停后尽快归零"
            //   永远不发生（归零是逐帧衰减出来的，没有帧就没有衰减）。
            startFrameDriver()
        } else {
            stopFrameDriver()
        }
        // ★★ 2026-10-03（⑱ 时钟 · **⑤c**）：**秒边界的一次性唤醒**。
        //   本处是"要不要跑帧"的**唯一判定点**（上面那三支）⇒ 时钟的唤醒也挂在这里。
        rescheduleClockWake()
    }

    /**
     * ★★ 2026-10-03（⑱ 时钟 · **⑤c**）：**下一次"秒边界"的一次性唤醒定时器**。
     *
     * ## ★★ 为什么**不能**直接把时钟加进 [IslandPanel.needsAnimationFrames]
     *
     * 那会让帧循环**永远常开**（`animator.frameIntervalMs` = 33/16 ms ⇒ **30–60 fps × 永远**）
     * = ★★ **正是台账 §4 第 2 件警告的"每秒白烧 60 帧"**。
     * ⇒ ★ 时钟只需要**每秒一帧**，不是每秒 60 帧。
     *
     * ## 做法：**算到下一个整秒还有多少 ms ⇒ 一次性唤醒 ⇒ 重绘 ⇒ 再安排**
     *
     * ```
     * 下次唤醒 = 500 − (now mod 500)   （ms）
     * ```
     *
     * ★ **相位对齐半秒**（不是"从安排时刻起 +500 ms"）⇒ 与 `ClockText.colonVisible`
     *   的"按绝对秒编号的奇偶"**同拍** ⇒ 冒号在**真正的秒边界**翻转，不会越走越偏。
     * ★ 与 `coverLockBadgeVisible` 的"到点即止"同族，但**要重新安排**（时钟是常驻的）。
     * ★ **插槽没放时钟 ⇒ 直接不安排**（零成本，连定时器都不建）。
     *
     * ⚠️ 本定时器**只 repaint 一帧**，不参与 `frameTimer` 的节拍 —— 两者互不干扰
     *   （帧驱动跑动画时，本定时器的 repaint 只是多画一帧，无副作用）。
     */
    private var clockWakeTimer: Timer? = null

    /** 右端插槽此刻是否显示**时钟**（= 需要秒级唤醒）。 */
    private fun rightSlotShowsClock(): Boolean =
        dev.cdl.render.rightSlotOccupant(
            textOf(snapshot, "island.right_slot.component"),
            textOf(snapshot, "spectrum.anchor"),
        ) == dev.cdl.render.RIGHT_SLOT_CLOCK

    /** 重新安排"下一次秒边界"的唤醒（幂等）。**必须在 EDT 上调用。** */
    private fun rescheduleClockWake() {
        // 先撤旧的（每次重排都是"从现在算下一次"，旧的自然作废）。
        clockWakeTimer?.stop()
        clockWakeTimer = null
        if (!started || !window.isShowing || !rightSlotShowsClock()) return
        // ★★ **相位对齐【半秒】**（不是整秒）—— 因为冒号是 **1 Hz**（半秒翻一次）
        //   ⇒ 唤醒必须落在**半秒边界**，才能与 `colonVisible` 的
        //   "`floorDiv(epochMs, 500)` 的奇偶"**同拍** ⇒ **不会越走越偏**。
        val toNextTick = 500L - (System.currentTimeMillis() % 500L)
        val t = Timer(toNextTick.toInt().coerceAtLeast(1)) {
            panel.repaint()
            // ★ 再安排下一次 —— 时钟是**常驻**的，不是"到点即止"。
            rescheduleClockWake()
        }
        t.isRepeats = false
        clockWakeTimer = t
        t.start()
    }

    /** 启动帧驱动。幂等（已启动则只确保是运行态）。**必须在 EDT 上调用。** */
    private fun startFrameDriver() {
        if (frameTimer != null) {
            // 已存在但可能被 stop 过 —— `Timer.isRunning` 区分"存在"与"在跑"。
            val existing = frameTimer!!
            if (!existing.isRunning) {
                // ⑦ 起驱动 = 起一条**新的**绝对截止期序列（旧序列随 stop 作废）。
                framePacer.reset()
                existing.start()
            }
            return
        }
        // ⚠️ 间隔取 `animator.frameIntervalMs` 而**不是**写死 33 ——
        //    `animation.quality` 会把它在 33（标准）/ 16（高、极致）之间切换。
        framePacer.reset()
        frameTimer = Timer(animator.frameIntervalMs.toInt()) { onFrameTick() }.apply { start() }
    }

    /**
     * 因**离散事件**（换曲 / seek）立刻重绘一帧，并**重排节拍**。
     *
     * 为什么必须重排（⑦）：`restart()` 只是"用当前 `delay` 重新计时"，
     * 而当前 `delay` 是**旧截止期序列**的余量 —— 不重排的话，这次抢跑会把
     * 后续每一帧都提前/推后，等于把一次 seek 变成一段永久的相位偏移。
     * 抢跑本身是不连续（用户拖了进度条），所以"以现在为新起点"才是对的语义。
     *
     * **必须在 EDT 上调用。**
     */
    private fun restartFrameDriverNow() {
        val t = frameTimer ?: return
        framePacer.reset()
        t.delay = animator.frameIntervalMs.toInt()
        t.restart()
    }

    /** 停止帧驱动但保留实例（恢复播放时复用，避免反复建 Timer）。**必须在 EDT 上调用。** */
    private fun stopFrameDriver() {
        frameTimer?.stop()
        // ★ 帧节拍收尾摘要（`recon/54` C2）。放在**本方法内**是为了覆盖全部停止路径
        //   （`syncFrameDriver` / `onFrameTick` 自停 / 窗口销毁 都会走到这里）。
        //   样本不足 30 帧时 `flush` 返回 null，不会为"点一下就暂停"报出无意义的数。
        framePacing.flush("帧驱动停止")?.let { System.err.println(it) }
        // ★ 每秒计量的窗口也复位：下次恢复播放时是从"新的第一帧"起算 1 秒，
        //   而不是把"暂停那段时间"算成一个超长窗口（那会报出一个荒谬的低帧率）。
        framePerSecond.reset()
    }

    /**
     * 帧回调。**由 `javax.swing.Timer` 在 EDT 上调用**，故这里可以直接碰 Swing。
     *
     * ## 重绘决策：为什么不是每 tick 都 `repaint()`
     *
     * 三个条件任一成立就必须重绘，且**理由各不相同**：
     *
     * ① **暂停了** → 停止定时器并返回。画面不会再变，继续跑是纯浪费（见 [syncFrameDriver]）。
     * ② **`docVersion` 变了** → 歌词文档被替换（换曲 / 重新载入歌词）。
     *    这是**离散事件**，它改变的是"要画哪几行字"，若等到下一 tick 才画，
     *    用户切歌时会看到约 33ms 的旧歌词残留。更要紧的是帧首必须以新文本
     *    去 `RenderCache.textLayoutFor`（缓存键是文本，换文本自然不命中，不必手动清），
     *    所以这里抢跑一帧是必要的，不是为了"更顺滑"。
     * ③ **位置大幅跳变**（seek）→ 用户拖了进度条。此时歌词应该**立刻**跳到新位置，
     *    而不是等下一帧 —— 33ms 的延迟在"拖动手感"上是能被感知的（拖动是连续动作，
     *    延迟会表现为"高亮跟在鼠标后面"）。用阈值而非"变化了就重绘"是因为
     *    正常播放时位置**每帧都在变**，无阈值等于退化成"每 tick 都重绘"。
     *
     * 其余情况（位置小幅前进）→ 按固定间隔重绘即可，不需要额外触发。
     *
     * ## 为什么在 tick 里读快照两次是刻意的
     *
     * [applyVisibility] 需要 `isPlaying` 才能判断 `hide_paused`，而这里也要判断。
     * 两次调用的**间隔是纳秒级**，快照之间几乎不可能翻转；即便翻转（正好在这一瞬
     * 换了播放态），后果也只是这一帧的显隐按旧值走，下一帧就纠正 ——
     * 而把快照存成字段跨 tick 复用会引入"字段与真实状态不同步"这类更难查的问题。
     * 这里选择"多读一次、不留状态"。
     */
    private fun onFrameTick() {
        if (!started) {
            stopFrameDriver()
            return
        }
        // ★ 帧节拍取样（`recon/54` C2）。**必须在本方法最前**：它测的是
        //   "上一帧入口 → 本帧入口"的真实周期（**含 EDT 排队等待**）。
        //   若放在后面，本帧自身的工作量会被算进间隔里 —— 量到的就成了
        //   "帧工作量"而不是"帧周期"，而 `recon/54` §3 R1 已把这两者列为**必须分开**的两件事。
        //   ⚠️ `System.nanoTime()` 只在这里取一次：在监视器内部取会把它的自身耗时算进去。
        val tickNanos = System.nanoTime()
        // ★ 2026-10-18：内存读数（60 s 一次；变化 < 4 MB 不打）。**只报告，不参与判定**。
        //   放在取样之后：与帧节拍共用同一个时刻，不另取时间。
        dev.cdl.host.MemoryReport.noteIfDue(tickNanos)
        framePacing.onFrame(tickNanos, animator.frameIntervalMs)?.let { System.err.println(it) }
        // ★ 每秒帧率读数（用户要求，2026-09-28）。与上面那条 40 s 分位数摘要**并存**。
        //   ★ **复用上面同一个 `tickNanos`** —— 再取一次会让两条节拍量的窗口起止差几百纳秒，
        //     而且"只取一次"本身就是本项目已定的约定（见文件头与 FramePacingMonitor 的 KDoc）。
        //   本行**不改任何状态、不参与判定**，只把"这一帧发生了 + 距上一帧多久"记进 1 秒窗口。
        //   `expectedHz` 由当前请求间隔反算（用户改 `animation.frame_rate` 后，
        //   下一行就会报新的达成率 —— 这正是"达成情况"要看的东西）。
        run {
            val requestMs = animator.frameIntervalMs
            framePerSecond.noteInterval(
                nowNanos = tickNanos,
                expectedHz = if (requestMs > 0L) 1000.0 / requestMs else 0.0,
            )?.let { System.err.println(it) }
        }
        // ★ ⑦ 绝对截止期：**紧接取样之后、任何帧工作之前**重排下一帧。
        //   放在取样之后，是为了不把本行的开销算进上面那条"帧周期"不变量；
        //   放在帧工作之前，是为了让 `delay` 与"本帧画多久"无关 —— 否则一帧画慢了
        //   会把下一帧的截止期一起推后，慢帧被放大成抖动（见 [FramePacer.onFrameDelivered]）。
        framePacer.onFrameDelivered()
        frameTimer?.let { it.delay = framePacer.delayToNextMs() }
        // ★ 推进过渡动画。**必须在读快照/判显隐之前** —— 动画的 `onUpdate` 可能
        //   改窗口不透明度与位置，而紧随其后的 `applyVisibility` 会读这些状态。
        //   放在最后会让"这一帧动画刚改的值"要等下一帧才被看到（33ms 的滞后）。
        //
        // ⚠️ `tick()` 的返回值（还有没有动画在跑）**故意不使用**：
        //    "要不要继续跑帧"由 [IslandPanel.needsAnimationFrames] **统一判定**
        //    （它已经把 `animator.hasRunning()` 算进去了）。
        //    这里再判一次等于有两个判据，迟早会不一致。
        animator.tick()
        // ★ 控件带的收缩动画（2026-09-22）。与 `animator` 并列放在最前 ——
        //   它可能改窗口 bounds（走完时收窗口），而紧随其后的 `applyVisibility`
        //   与重绘都该看到"这一帧刚算出的"几何。
        advanceBandCollapse()
        val snap = snapshotSupplier()
        // ★ 2026-10-18（P1）：**窗口不可见时不要跑帧**。
        //   原来播放分支不检查可见性：`island.hide_fullscreen` / 无歌词隐藏 / 总开关关闭
        //   之后仍以 30~60 Hz 读快照 + `repaint()` 一个不可见组件（纯空转）。
        if (snap.isPlaying && !window.isShowing) {
            stopFrameDriver()
            return
        }
        if (!snap.isPlaying) {
            // 播放停了 → 把窗口按 hide_paused 的语义摆正。
            // 顺序上先摆显隐再停：否则停完就退出，这一帧的显隐不会被应用。
            applyVisibility(snapshot, playing = false)
            // ⚠️ 2026-09-17：**不再立刻停帧** —— 暂停后还有两件事需要帧：
            //   ① 频谱要「尽快归零」（用户要求），而归零是逐帧衰减出来的；
            //   ② 长歌词要继续滚动（见 SCROLL_*）。
            //   两者都完成后 [IslandPanel.needsAnimationFrames] 转 false，帧驱动自停 ——
            //   于是"暂停后不空转"这条纪律仍然成立，只是多跑了几帧收尾。
            if (panel.needsAnimationFrames()) {
                panel.repaint()
                return
            }
            stopFrameDriver()
            panel.repaint()
            return
        }

        val docChanged = snap.docVersion != lastDocVersion
        val jumped = lastPositionMs >= 0 && abs(snap.positionMs - lastPositionMs) > SEEK_JUMP_THRESHOLD_MS
        lastDocVersion = snap.docVersion
        lastPositionMs = snap.positionMs

        // hide_paused 的反向：恢复播放时要把窗口显回来（若上一帧是暂停隐藏态）。
        applyVisibility(snapshot, playing = true)

        // 播放中每 tick 都重绘 —— 位置在走，画面每帧都不同，这就是"固定帧间隔"的
        // 本来目的。`docChanged` / `jumped` 不改变"这一帧要画"的结论（当前 33ms
        // 间隔已短到无感），它们的价值在于：**离散事件发生时把定时器拉回起跑线**，
        // 让"事件 → 画面更新"的延迟从"最多一个间隔"降到"接近 0"。
        //
        // 用 `restart()` 而不是"再调一次 repaint()"：`repaint()` 只是往 EDT 队列
        // 投递一个合并请求，而这一 tick 本身已经在 EDT 上 —— 再投一次不会让绘制
        // 提前发生。真正能缩短延迟的是**把下一个 tick 的计时重置**，使后续帧
        // 重新与事件对齐。这样 seek 后的高亮是"立刻跟上"而不是"最多晚 33ms"。
        if (docChanged || jumped) restartFrameDriverNow()
        panel.repaint()
    }

    // ── 封面与取色（`cover.enabled` + 三个 `*_color_mode`）──────────────────
    //
    // 这一段的四个方法共同实现「封面显示」与「跟随封面取色」的**渲染侧**逻辑：
    // 取图（异步）、提取主色（带缓存）、决定是否占位、图片到达后补一次重绘。
    // 绘制本身在 `IslandPanel.drawCover` 与 `backgroundWithOpacity`。

    /**
     * 取当前曲目的封面。**非阻塞**，且会在"图片尚未到达"时安排一次补重绘。
     * **必须在 EDT 上调用**（它读写 [coverPending] 等字段，并可能新建 [Timer]）。
     *
     * ## 异步契约的处理（这是本方法存在的全部理由）
     *
     * `CoverArtProvider.coverFor` 的契约是"**首次调用必返回 `null`**，图片在后续
     * 调用中出现"（它把读盘与解码放在后台线程，换取 EDT 永不卡顿）。
     * 于是渲染侧必须回答两个问题：
     *
     * ① **本帧画不画封面** —— `null` 就不画。这是任务书要求的"跳过绘制"，
     *    也是唯一不会画出半个封面的做法。
     * ② **什么时候再看一眼** —— 若只在帧驱动里被动等，**暂停时封面永远不会出现**
     *    （帧驱动在暂停时会自停，见 `syncFrameDriver`）。故这里主动排一次
     *    延迟重绘（见 [scheduleCoverRepaint]）。
     *
     * ## 为什么开关关闭时**不调用** `coverFor`
     *
     * `coverFor` 在未命中时会**提交一次后台读盘**（读音频文件前几 MB）。
     * 关掉开关却继续触发加载，等于让用户明确关掉的功能继续吃磁盘 IO ——
     * 故这里先判开关，而不是"调用了但不用结果"。
     */
    private fun coverImageFor(local: Appearance): BufferedImage? {
        // ── 何时需要封面图：**显示它**，或**从它取色**（2026-09-17 修）──────────
        //
        // ## 原记
        //
        // `if (!local.coverEnabled) { resetCoverWait(); return null }` ——
        // 即"没开显示封面就不加载图"。
        //
        // ## 实为（用户真机投诉）
        //
        // 用户原话：「**频谱根据封面取色也没有做到**」。
        //
        // 根因：三个 `*_color_mode = cover` 都靠本方法返回的图去提主色
        // （`coverRgb = coverImage?.let { coverRgbOf(it) }`）。而用户的配置里
        // `cover.enabled = false`（**不显示**封面）→ 这里直接返回 null →
        // `coverRgb = null` → `CoverPalette.resolve` 的 `cover` 档**静默回退到 manual**。
        //
        // **"取色的来源"与"要不要把图显示出来"本来是两件事**，却被一个开关绑在一起。
        // 后果是：用户只想让频谱/歌词跟随封面变色、但不想在词岛里显示那张图 ——
        // 这个完全合理的诉求**做不到**，而且**不报任何错**（只是颜色"没生效"）。
        //
        // ## 现做法
        //
        // 只要**任一**颜色来源是 `cover`，就加载图（用于取色）；
        // **是否绘制**仍由 `leadingCoverSize` 看 `cover.enabled` 单独决定 ——
        // 于是"取色"与"显示"彻底解耦，两种组合都能工作：
        //
        // | `cover.enabled` | 某个 `*_color_mode = cover` | 结果 |
        // |---|---|---|
        // | true | true | 显示封面 **且** 取色（改动前唯一能工作的组合） |
        // | false | true | **不显示封面，但取色生效**（本次修好的组合） |
        // | true | false | 只显示封面，颜色走 manual |
        // | false | false | 都不做 —— 此时**不加载图**，零开销 |
        // ★ 2026-09-22：`background.color_mode = cover_image`（「封面图」档）**也要图** ——
        //   它把封面图本身当底色，没有图就没有这一档。漏掉这一条的表现是
        //   「选了「封面图」，底色却还是纯色」且**不报错**（`coverRgb` 为 null →
        //   烘焙被跳过 → 退回纯色档）—— 正是本项目反复栽的"改了 A 但 A 的输入被掐断"。
        // ★★★ 2026-10-12（**BUG-181 的真根因**）：★ `cover_flow` **必须**进这个名单 ✓
        //
        // ## 用户报障 + 诊断取证（逐字）
        //
        // > 「**设置随封面取色流光无效，呈现默认色，但改成自选色或者从封面取色又会变**」
        //
        // ★ 我加的诊断当场命中：
        // ```
        // 🎨 流光诊断：档位=cover_flow · 调色板=0 色 · 逐柱回调=有 · fade_top=true
        // ```
        // ★★ 而 `封面调色板` 那行**一次都没打** ⇒ ★ **`coverImage` 那条分支根本没进** ✗
        // ⇒ ★★★ 本名单**漏了 `COVER_FLOW`** ⇒ `needsCoverImage = false` ⇒ `coverImage = null`
        //   ⇒ 调色板恒空 ⇒ **流光退回手动色**（= 用户说的"默认色"）✗
        //
        // ## ★★★ 这正是本 KDoc **自己警告过**的形态
        //
        // ★ 上面那段（2026-09-22）写的是：「漏掉这一条的表现是
        //   『选了「封面图」，底色却还是纯色』且**不报错**」✗ ——
        // ★★ **同一个形态第二次发生**：★ 新档 `cover_flow` 接进了**取色**（`spectrumBarColorFn` ✓）
        //   却**没接进取色的【输入】**（本名单 ✗）✓
        // ⇒ ★ 判据：★ **"新加一个依赖封面的档" ⇒ 必须同时进本名单** ✓
        //   （★ 与 `coverPaletteFor` 的 `needFlow` **成对** —— 一个管"要不要图"、
        //     一个管"要不要算多色"，**两个都得加** ✗ 本批就是漏了前一个）
        // ★★★ 2026-10-14（**BUG-201**）：本名单**已外移**为公开纯函数
        //   [needsCoverImageFor] —— 预览侧（`IslandPreviewPane`）此前**自己另写了一份**
        //   只看 `cover.enabled` 的判据 ⇒ "不显示封面 + 跟随封面取色"时，
        //   真机三路都取到封面色、而预览的**背景**退回手动色 ✗
        //   （用户报障逐字：「词岛背景颜色与封面颜色的不相符」+「频谱条的取色也跟封面取色不一致」）
        //   ⇒ 现在**两侧调同一个函数**，同源由构造保证。
        val needsCoverImage = needsCoverImageFor(
            coverEnabled = local.coverEnabled,
            lyricColorMode = local.lyricColorMode,
            backgroundColorMode = local.backgroundColorMode,
            spectrumColorMode = local.spectrumColorMode,
        )
        if (!needsCoverImage) {
            resetCoverWait()
            return null
        }
        val provider = coverArtProvider ?: run {
            resetCoverWait()
            return null
        }
        val path = snapshotSupplier().path
        if (path.isEmpty()) {
            resetCoverWait()
            return null
        }

        if (path != coverRequestPath) {
            // 换曲：重新进入"等待首次结果"状态（见 [coverPending] 的 KDoc）。
            coverRequestPath = path
            coverPending = true
            coverRepaintAttempts = 0
            // ★★ 2026-09-28：同时**起算绝对截止期** —— 播放中帧定时器会让"计数"永不增长，
            //    只有时间判据才保证"约 1 秒后一定有结论"（见 [coverWaitDeadlineNanos] 的 KDoc）。
            coverWaitDeadlineNanos = System.nanoTime() +
                COVER_REPAINT_DELAY_MS.toLong() * MAX_COVER_REPAINT_ATTEMPTS * 1_000_000L
        }

        val image = provider.coverFor(path)
        if (image != null) {
            coverPending = false
            coverRepaintAttempts = 0
            coverWaitDeadlineNanos = 0L
            return image
        }
        // ★★ 2026-09-28：**时间到 ⇒ 认定"本曲没有封面"** —— 与帧定时器无关。
        //    少了这一条，播放中 `coverPending` 永远为真，旧封面被一直复用（用户报障）。
        //    判据走**唯一实现** `coverWaitElapsed`（公开纯函数，探针可直接断言）——
        //    两处各写一份 `now >= deadline` 就是"同族两处实现只改一处"的老坑。
        if (coverWaitElapsed(coverPending, System.nanoTime(), coverWaitDeadlineNanos)) {
            coverPending = false
        }
        // 尚无封面 → 调用方跳过绘制，但**保留占位空间**直到有结论（见 [coverPending]）。
        if (coverPending) scheduleCoverRepaint()
        return null
    }

    /**
     * 图片到达后的**补重绘**调度。**必须在 EDT 上调用。**
     *
     * ## 为什么必须有它（而不是靠下一帧）
     *
     * 帧驱动只在**播放中**跑（暂停时会自停，理由见 `syncFrameDriver` 的省电论证）。
     * 而"封面在后台读完了"这件事与播放态**无关** —— 用户完全可能在暂停状态下
     * 切歌（用播放器选一首、暂停着看歌词）。那时没有任何东西会触发重绘，
     * 封面会**一直不出现**，直到用户恢复播放。这是真实可复现的缺口。
     *
     * ## 为什么次数有上限
     *
     * 没有封面的歌非常常见（大部分单曲文件不带内嵌图）。若无上限，
     * 那首歌会永远每 [COVER_REPAINT_DELAY_MS] 重绘一次 —— 一个纯装饰性元素
     * 让词岛在暂停时也永不空闲。上限到了就认定"本曲没有封面"并停止轮询。
     */
    private fun scheduleCoverRepaint() {
        // ★★ 2026-09-28：**先按绝对时间收口** —— 放在"播放中直接返回"**之前**。
        //    原实现把这层判据放在最后（且只在暂停时才会走到），于是播放中
        //    `coverPending` 永不收敛 ⇒ 上一首封面被一直复用（用户报障）。
        //    现在无论播放/暂停，到点就在这里认定"本曲没有封面"。
        //    判据走**唯一实现** `coverWaitElapsed`（与 `coverImageFor` 同一份逻辑）。
        if (coverWaitElapsed(coverPending, System.nanoTime(), coverWaitDeadlineNanos)) {
            coverPending = false
            return
        }
        // 播放中：帧驱动每 33 ms 就重绘一次，下一帧自然会看到新封面 ——
        // 再排定时器只是重复投递（且会让"播放中的封面"多一次无谓的唤醒）。
        // ⚠️ 计数也不再需要它来推进（截止期已按时间收口），这里保持早退只为省一次定时器。
        if (frameTimer?.isRunning == true) return
        if (coverRepaintAttempts >= MAX_COVER_REPAINT_ATTEMPTS) {
            // 重试耗尽 → 认定"本曲没有封面"：收窄左侧，让文字占满整宽。
            coverPending = false
            return
        }
        // 已有一个待触发的补重绘 → 不叠加（多次 `repaint()` 本来就会被 Swing 合并，
        // 但叠加会让 attempts 计数虚高、提前用尽重试额度）。
        if (coverRepaintTimer?.isRunning == true) return
        coverRepaintAttempts++
        coverRepaintTimer = Timer(COVER_REPAINT_DELAY_MS) { panel.repaint() }.apply {
            isRepeats = false
            start()
        }
    }

    /** 复位封面等待状态（开关关闭 / 无 provider / 无曲目时调用）。 */
    private fun resetCoverWait() {
        coverRequestPath = ""
        coverPending = false
        coverRepaintAttempts = 0
        coverWaitDeadlineNanos = 0L
    }

    /**
     * 本帧封面的**绘制参数**：要画哪两张图、新图占多少。
     *
     * @param shown 新图（`null` = 不画）
     * @param prev 正在淡出的旧图（`null` = 没有）
     * @param mix [shown] 的占比；[prev] 画 `1 - mix`
     */
    private class CoverPaint(val shown: BufferedImage?, val prev: BufferedImage?, val mix: Float) {
        companion object {
            /** 什么都不画。 */
            val NONE = CoverPaint(null, null, 1f)
        }
    }

    /**
     * 同步封面交叉淡化的状态，返回**本帧**要画的参数。
     *
     * **必须在 EDT 上调用** —— 由 `IslandPanel.paintComponent` 每帧调用。
     *
     * ## 为什么由绘制路径调用（而不是帧回调）
     *
     * 封面**到达**这件事与播放态无关（后台读盘完成即可），而帧驱动只在播放中跑。
     * 放在绘制路径意味着：**任何一次重绘都会重新判定** —— 包括
     * `scheduleCoverRepaint` 那次补重绘（那正是"暂停中切歌"的路径）。
     * 而"起了过渡要唤醒帧驱动"由 `paintComponent` 末尾既有那段
     * （`if (needsAnimationFrames() && frameTimer?.isRunning != true) syncFrameDriver()`）
     * 自动完成 —— 它读的就是 `animator.hasRunning()`。
     *
     * ## 三种情形（共用同一个 alpha）
     *
     * | 情形 | 判定 | 结果 |
     * | --- | --- | --- |
     * | 新图到达 | `newImage != null && newImage !== coverShown` | `prev = 旧, shown = 新`，`mix: 0 → 1` |
     * | 本曲无封面 | `newImage == null && !coverPending` | `prev = 旧, shown = null`，`mix: 0 → 1`（即淡出） |
     * | 等新图 | 其余（`newImage == null && coverPending`） | `prev = null, shown = 旧`，`mix = 1`（**原样画**） |
     *
     * @param newImage 本帧 `coverImageFor` 的结果（`null` = 尚未到达）
     * @param enabled `cover.enabled`
     */
    private fun resolveCoverMix(newImage: BufferedImage?, enabled: Boolean): CoverPaint {
        // ── 开关关掉 → **立刻**清空，不做动画 ──
        // 用户关掉封面时不该看到它淡出 260 ms（那会像"关不掉"）。
        if (!enabled) {
            coverShown = null
            coverPrev = null
            coverMix = 1f
            coverMixRunning = false
            return CoverPaint.NONE
        }
        // ── 新图到达（换曲 / 首次）──
        if (newImage != null && newImage !== coverShown) {
            coverPrev = coverShown
            coverShown = newImage
            // ⚠️ 必须在 `coverRgbShown` 被本帧的新值覆盖**之前**抓住旧色
            //    （调用顺序：`resolveCoverMix` → `resolveCoverRgb` → `coverRgbShown = …`）。
            coverRgbPrev = coverRgbShown
            // 首次拿到封面（没有旧图）→ **不做动画**：与 `fadePrimed` 同一条理由
            // （动画只描述**状态变化**，不描述**初始状态**）。
            // 否则插件启动时会先淡入词岛、再淡入封面，两次叠在一起。
            startCoverMix(from = if (coverPrev != null) 0f else 1f)
        } else if (newImage == null && !coverPending && coverShown != null) {
            // ── 等待已结束、且本曲确实没有封面 → 旧图淡出 ──
            coverPrev = coverShown
            coverShown = null
            // 没有新色可混 → 让配色从"旧图的主色"淡回"没有封面时的回退色"
            // （`blendedCoverRgb` 在两个端点有一端为 null 时会走退化分支）。
            coverRgbPrev = coverRgbShown
            startCoverMix(from = 0f)
        }
        return if (coverPrev == null) {
            CoverPaint(coverShown, null, 1f)
        } else {
            CoverPaint(coverShown, coverPrev, coverMix)
        }
    }

    /**
     * 起一次封面混合（`coverMix: from → 1`）。**必须在 EDT 上调用。**
     *
     * 终值恒为 `1f` —— 于是"新图完全到位、旧图被丢掉"这件事**由构造保证**，
     * 不需要在别处再判一次"过渡结束了吗"（那会出现"过渡结束了但旧图还挂着"）。
     */
    private fun startCoverMix(from: Float) {
        coverMix = from
        // ⚠️ **不需要**"已在跑就别重起"的守卫：`resolveCoverMix` 只在**状态真的变了**
        //    （图换了 / 本曲无封面）时才调到这里，而那时 `coverShown` 已经更新，
        //    下一帧不会再满足同一个条件。加一个基于 `coverMixRunning` 的门反而有害 ——
        //    "淡出途中换曲"会被它误判成"已在跑"而**跳过新过渡**，封面卡在重影上。
        //    （同一槽位上的旧过渡由 `startIn` 自己取消，见其契约。）
        val spec = Motions.COVER_CROSSFADE
        val slot = requireNotNull(spec.slot) {
            "Motions.COVER_CROSSFADE 必须声明槽位（它由帧循环驱动）"
        }
        coverMixRunning = animator.startIn(
            slot = slot,
            from = from,
            to = 1f,
            durationMs = spec.durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                coverMix = v
                panel.repaint()
            },
            onDone = {
                // 终值：新图完全到位 → 旧图**必须**在这里丢掉，
                // 否则它会一直挂着一张 `BufferedImage`（4 MB 量级）不释放。
                coverMixRunning = false
                coverMix = 1f
                coverPrev = null
            },
        )
    }

    /**
     * 按交叉淡化进度混合**封面主色**（跟随封面取色的色彩过渡）。**必须在 EDT 上调用。**
     *
     * ## 端点情形（都退化成"直接用其中一个"）
     *
     * | 情形 | 结果 |
     * | --- | --- |
     * | 没有在淡化（`paint.prev == null`） | 直接用 `raw` —— **静止态与改动前逐像素一致** |
     * | 旧图没有可用主色 | 用 `raw`（新色） |
     * | 新图没有可用主色 | 用旧色（[coverRgbPrev]） |
     * | 两个都没有 | `null` → 由 `CoverPalette.resolve` 回退到 `manual` 档 |
     *
     * ## ★ 为什么必须**拆通道**插值
     *
     * 两个理由（**都由实测证实**，完整论证与数字见 [blendPackedArgb] 的 KDoc）：
     *
     * ① **通道串扰** —— 整数插值把 ARGB 当成**一个 32 位整数**插，
     *    低位字节的进位会爬到高位上：黑→白的中点会得到 `0xFF7FFFFF`（G/B 都是 255）
     *    而不是 `0xFF7F7F7F`（各 127），**偏差 128**，肉眼一眼可见。
     * ② **alpha 也跟着插** —— 两端 alpha 不同时会得到**半透明的主色**，**不报错**。
     *
     * 故这里**不自己算**，一律走公开纯函数 [blendPackedArgb]（`SettingModel.kt`）——
     * 那里是**唯一**的实现，且"alpha 恒为 `0xFF`"由 `composePackedArgb` **构造**保证。
     *
     * 拆通道 + 重新打包的逻辑**不在本方法里** —— 它是公开纯函数
     * [blendPackedArgb]（`SettingModel.kt`），理由见其 KDoc（含 alpha 的硬约束）。
     *
     * ## 为什么它不影响 `*_color_mode = manual`
     *
     * 那些档位下 `CoverPalette.resolve` **根本不看** `coverRgb`，故混合结果被丢弃 ——
     * 即"不用封面取色"的用户**零代价**（只多几次整数运算）。
     */
    private fun blendedCoverRgb(raw: Int?, paint: CoverPaint): Int? {
        // 静止态（绝大多数帧）：原样返回，不做任何运算。
        if (paint.prev == null) return raw
        val a = coverRgbPrev
        val b = raw
        if (a == null) return b
        if (b == null) return a
        // 拆通道插值 + 重新打包的**唯一实现**在 `blendPackedArgb`（公开纯函数）——
        // 放那儿是为了**能被探针直接断言**（项目纪律：首选抽公开纯函数而非放宽可见性）。
        return blendPackedArgb(a, b, paint.mix)
    }

    /**
     * 检测"当前行换行了"，把**上一行**交给退场动画、把**新行**交给入场动画。
     * **必须在 EDT 上调用，每帧一次。**
     *
     * ⚠️ 由 `drawLyricSlots` 在**槽位循环之前**调用 —— 放进循环里会在有多槽时重复触发
     * （当前是单槽，但那是 `slotsFor` 的实现细节，渲染层不该依赖它）。
     *
     * ## 为什么"首次"与"变成无歌词"都不做动画
     *
     * · **首次**（`prev == null`）：没有"上一行"可退，且启动时不该有文字飞出去。
     *   但**新行仍然入场** —— 启动后第一行歌词"滑上来"是自然的（它本来就从无到有）。
     * · **变成无歌词**（`text == null`）：旧行不是"被换掉"，而是"整块没了" ——
     *   那时由 `drawNoLyricsNotice` 接管画面，再飞一段旧文字会很突兀。
     *   此时新行也不入场（没有新行）。
     *
     * 两条都是"**动画只描述状态变化**"的同一个原则（与 `fadePrimed`、封面首帧同源）。
     *
     * ## 2026-09-28：为什么退场与入场**放在同一个函数**里
     *
     * 它们是**同一件事**（"换行了"）的两个后果。分成两个各自判 `text == lastMainText`
     * 的函数，就必须有一份"谁先更新 `lastMainText`"的隐式约定 —— 先跑的那个把
     * `lastMainText` 改掉，后跑的那个就永远看不到变化（**静默失效**，且只在换行时表现）。
     * 故收成**一个判定点、两个动作**：判据只有一份，动作各自独立（槽位也不同）。
     */
    private fun syncMainExit(text: String?) {
        if (text == lastMainText) return
        val prev = lastMainText
        // ⚠️ `lastMainText` **无论档位都要更新** —— 否则"在 STANDARD 下换了行、
        //    再切到 HIGH"会让那次早已过去的换行被当成"刚刚发生"，凭空飞出一段旧文字。
        lastMainText = text
        // 质量档 = STANDARD → 不做进阶过渡（注册表选项文案承诺了「仅基础过渡」）。
        // 歌词行切换是**高频**效果（每几秒一次）+ 每次多画一段文字，
        // 在弱机器上是稳定可感的开销，故标准档不做它。见 MotionQuality.advancedTransitions。
        if (!animator.effectiveAdvancedTransitions) {
            exitingMainText = null
            mainExit = 1f
            mainExitRunning = false
            mainEnter = 1f
            mainEnterRunning = false
            return
        }
        if (prev == null || text == null) {
            // 无退场：没有"上一行"可退（首次）或整块没了（变无歌词）。
            exitingMainText = null
            mainExit = 1f
            mainExitRunning = false
            // ★ 但**新行仍然入场**（除非本帧没有新行 = 变无歌词）。
            //   首次歌词"滑上来"是自然的：它本来就从无到有，不是"被换掉"。
            if (text != null) startMainEnter() else {
                mainEnter = 1f
                mainEnterRunning = false
            }
            return
        }
        exitingMainText = prev
        startMainExit()
        // 两端同时起（两个槽位，互不取消）—— 这才是"不割裂"。
        startMainEnter()
    }

    /** 起一次退场（`mainExit: 0 → 1`）。**必须在 EDT 上调用。** */
    private fun startMainExit() {
        val spec = Motions.LYRIC_EXIT
        val slot = requireNotNull(spec.slot) {
            "Motions.LYRIC_EXIT 必须声明槽位（它由帧循环驱动）"
        }
        mainExit = 0f
        mainExitRunning = animator.startIn(
            slot = slot,
            from = 0f,
            to = 1f,
            durationMs = spec.durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                mainExit = v
                panel.repaint()
            },
            onDone = {
                mainExitRunning = false
                mainExit = 1f
                exitingMainText = null
            },
        )
    }

    /**
     * 起一次入场（`mainEnter: 0 → 1`）。**必须在 EDT 上调用。**
     *
     * ## 与 [startMainExit] 的**唯一结构差异**
     *
     * `onDone` **不清任何文本** —— 入场的对象就是"当前行"，它动画结束后仍要留在画面上，
     * 故只需要把进度归 1（= 停止施加位移/淡入）。退场则相反：动画结束 = 旧行该消失了。
     *
     * ## 为什么用 `startIn`（而不是"检查有没有在跑再起"）
     *
     * `AnimationRuntime.startIn` 对**同槽位**是"**就地替换**"（旧的被取消，且
     * **不触发其 `onDone`**）—— 这正是我们要的：连续换行时，第二次入场直接接管，
     * 不必等第一次跑完，也不会出现"第一段的 `onDone` 把第二段的状态清掉"。
     *
     * ⚠️ `mainEnterRunning` 由 `startIn` 的**返回值**给出（它告诉我们"这条是否真的起了"）——
     * 不要让本函数自己维护一个"我以为在跑"的标志（那是本项目反复吃亏的
     * "标志位与时序耦合"，见 [mainEnter] 的说明）。
     */
    private fun startMainEnter() {
        val spec = Motions.LYRIC_ENTER
        val slot = requireNotNull(spec.slot) {
            "Motions.LYRIC_ENTER 必须声明槽位（它由帧循环驱动）"
        }
        mainEnter = 0f
        mainEnterRunning = animator.startIn(
            slot = slot,
            from = 0f,
            to = 1f,
            durationMs = spec.durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                mainEnter = v
                panel.repaint()
            },
            onDone = {
                mainEnterRunning = false
                mainEnter = 1f
            },
        )
    }

    /**
     * 取某张封面图的**主色**（打包 RGB；`null` = 无可用主色）。带按图引用缓存。
     *
     * 缓存的意义见 [coverColorImage] 的 KDoc：`CoverArtProvider` 对同一首歌返回
     * **同一个实例**，故引用比较就是"要不要重算"的正确判据。
     */
    private fun coverRgbOf(image: BufferedImage): Int? {
        if (image !== coverColorImage) {
            coverColorImage = image
            coverColorRgb = CoverColorExtractor.dominant(image)
        }
        return coverColorRgb
    }

    /**
     * ★★ 2026-10-03（⑰ **F4**）：本帧 `FLOW` 档要用的**多色**（非 `FLOW` 档恒返回空列表）。
     *
     * ## ★★ 两条成本控制 —— F4 被台账称为"决定成败的一件"，就落在这里
     *
     * | # | 控制 | 效果 |
     * |---|---|---|
     * | ① | **非 `FLOW` 档直接返回空** —— 连 `palette()` 都不调 | 存量用户（`NONE`/`LINEAR`/`LIQUID`）**零开销** |
     * | ② | **按图引用缓存**（键 = [coverPaletteImage]，与主色同一个键） | `CoverArtProvider` 对同一首歌返回**同一个实例** ⇒ 稳定时**每帧零成本** |
     *
     * ★ ① 尤其重要：`palette()` 与 `dominant()` **共用同一份 `accumulate`**
     *   （遍历约 1024 个采样点 + 1024 次 HSB 转换）⇒ 若每帧都调，就是"每帧新建渐变笔刷"
     *   那个已卡过的坑的同族（`SpectralPainter` 的 KDoc 记着）。
     *
     * ## ★ 为什么**不**照 [stickyCoverColorPath] 那套做"按曲目粘住"
     *
     * **原记**（本行上一版逐字）：多色是**大面积填充** ⇒ 若粘住，会出现"封面已经换了、
     * 整块背景还是上一首的颜色"，比主色闪烁**更显眼** ⇒ 本档接受"这一帧没有图 ⇒ 退回 `LINEAR`"。
     *
     * ★★★ **实为（2026-10-14 更正 · BUG-193）**：那条理由针对的是「**无过渡的粘住**」✗ ——
     *   ★ 而本批的修法是 **保持（仅限加载空窗）+ 新调色板到达时逐色交叉淡化 260 ms**
     *     （见 [holdAndBlendPalette]）⇒ 空窗结束后**必然**在 260 ms 内过渡到新色
     *   ⇒ ★「封面已换、颜色还是旧的」这个**稳态不存在**（只存在一段过渡）
     *   ⇒ **原顾虑不成立** ✓
     *
     * ★★ 触发这次更正的**用户报障**（逐字）：「**还是有那个自选色出来**（前面我说错了，
     *   默认频谱的色可以被自选色更改，所以显示的其实是被改过的色），
     *   **现在其实是流光多色叠加在自选色之上？**」
     *   ⇒ ★ 根因 = 本函数的空窗分支返回**空表** ⇒ `spectrumFlowColorAt` 的
     *     `n < 2 ⇒ 返回 baseArgb` 退化判据命中 ⇒ **整条频谱退回基色**
     *     （= `spectrum.color` = **用户的自选色**）✗
     *   ⇒ ★ **不是"叠加"，是"退回"** ✓（完整论证见 [coverPaletteHoldOrEmpty] 的 KDoc）
     */
    private fun coverPaletteFor(coverImage: BufferedImage?): List<Int> {
        // ★★ 2026-10-11：**两个消费方**都要它 ——
        //   ① `material.fill.gradient_style = FLOW`（背景多色渐变）
        //   ② `spectrum.color_mode = cover_flow`（频谱多色流光，本轮新增）
        //   ⇒ 判据改成"**任一档需要**"。★ 两者都关时**照旧直接返回空**（连 `palette()` 都不调）✓
        // ★★ 2026-10-14：**再加 `cover_camo`**（迷彩碎色块档）—— ★ 与 `cover_flow` 同族，
        //   也是"逐柱取封面多色" ⇒ ★ 必须一起进本判据（漏了 ⇒ 迷彩档退回单色）✗
        // ★★★ 2026-10-14（**用户报障纠正**）：**再加 `gradient_style == "CAMO"`** ——
        //   用户逐字「**碎色流光是提供给词岛外观的**」⇒ 碎色的**真正落点是背景档**；
        //   ★ 漏了它 ⇒ `MaterialLayers` 的 `flowCamoPaint` 拿到**空色板** ⇒ 返回 `null`
        //     ⇒ 退回 `LINEAR`/单色 ⇒ **"选了封面迷彩碎色流光却没反应"**（本项目最多次的形态）✗
        val gradientStyle = textOf(snapshot, "material.fill.gradient_style")
        val spectrumMode = textOf(snapshot, "spectrum.color_mode")
        val needFlow = gradientStyle == "FLOW" || gradientStyle == "CAMO" ||
            spectrumMode == dev.cdl.render.CoverPalette.COVER_FLOW ||
            spectrumMode == dev.cdl.render.CoverPalette.COVER_CAMO
        if (!needFlow) return emptyList()
        // ★★★ 2026-10-12（**BUG-180**）：★ **封面还没到 ⇒ 沿用同一曲目上一次的调色板** ✓
        //
        // ## 用户报障（逐字）
        //
        // > 「**颜色选项还会突然失效。**」
        //
        // ## ★★ 取证（真机日志，不是推断）
        //
        // ★ 该日志里 `coverRgb=null` 出现 **35 次**、有值 **29 次** ✗ ——
        //   ★★ 且**同一首歌**先是 `null`、随后才有色 ✓
        //   ⇒ ★★★ **封面是【异步加载】的**：加载完成前这一帧拿不到图 ✗
        //   ⇒ ★ 调色板为空 ⇒ **流光退回单色** ⇒ 用户看到"颜色突然失效" ✓
        //
        // ## ★★ 修法：照 [stickyCoverColorPath] 那一套（**按曲目路径**粘住）
        //
        // ★ 本函数原先**故意不粘** ✗，理由是"多色是大面积填充，粘住会出现
        //   '封面已经换了、整块背景还是上一首的颜色'"（见本函数上方的 KDoc）——
        // ★★ 但那条顾虑**恰恰被"按曲目路径"键解决了** ✓：
        //   ★ 曲目一变 ⇒ `path` 就变 ⇒ 粘性缓存**立刻失效** ⇒ **结构上不可能**串色 ✓
        //   ★ 主色（`resolveCoverRgb`）**早就是这么做的** ✓ —— 本处只是**补齐同一条** ✓
        //
        // ⚠️ 与主色**同一条边界**（三条）：★ 本帧有图 ⇒ 用图并刷新缓存 ·
        //   ★ 本帧无图 + 曲目未变 + 上次取到过 ⇒ **沿用** · ★ 曲目变了 / 从未取到 ⇒ 空（正确回退）✓
        val path = snapshotSupplier().path
        if (coverImage == null) {
            if (path.isNotEmpty() && path == stickyPalettePath) return stickyPalette
            // ★★★ 2026-10-14（**BUG-193**）：★ **切歌空窗期沿用上一次的调色板**（限时宽限）✓
            //   用户报障（逐字）：「**还是有那个自选色出来**」—— 根因 = 空表 ⇒
            //   `spectrumFlowColorAt` 的 `size < 2 ⇒ 返回基色` ⇒ 基色 = `spectrum.color`
            //   （**用户的自选色**）✗ 完整论证见 [coverPaletteHoldOrEmpty] 的 KDoc ✓
            return coverPaletteHoldOrEmpty(
                paletteHold, paletteHoldAtMs, System.currentTimeMillis(), coverPending,
            )
        }
        if (coverImage !== coverPaletteImage) {
            coverPaletteImage = coverImage
            // ★★★ 2026-10-12（**BUG-181**）：★ **补足到 ≥2 色** ⇒ 流光不再静默退回默认色 ✓
            //
            // ★ 用户原话：「**设置随封面取色流光无效，呈现默认色，
            //   但改成自选色或者从封面取色又会变**」
            // ★★ 根因：`CoverColorExtractor.palette()` **不保证 4 个**（低彩度封面可能只出 1 个）✗
            //   ⇒ `spectrumFlowColorAt` 的 `n < 2 ⇒ 返回基色` 判据命中
            //   ⇒ ★★ 用户看到**单色**（= `spectrum.color` 的默认青）✗
            // ★ 修法：`augmentPalette` 从已有色**派生**提亮/压暗两份 ✓
            //   ★ 色数已够时**恒等** ⇒ 对现状零影响 ✓
            coverPalette = dev.cdl.render.augmentPalette(
                CoverColorExtractor.palette(coverImage, FLOW_PALETTE_COLORS),
                FLOW_PALETTE_COLORS,
            )
            // ★ 诊断（去噪，只在取色结果变化时打一行）：把"本帧调色板到底几个色"变成可 grep 的事实 ——
            //   ★★ 本批的根因正是"色数不足 ⇒ 静默退回单色"，而那**没有任何日志** ✗
            logPaletteDiag(coverPalette)
        }
        if (path.isNotEmpty()) {
            stickyPalettePath = path
            stickyPalette = coverPalette
        }
        // ★★★ 2026-10-14（**BUG-193**）：刷新**跨曲目**的调色板 + 与上一份做**逐色过渡** ✓
        return holdAndBlendPalette(coverPalette)
    }

    /**
     * ★★★ 2026-10-14（**BUG-193**）：**刷新跨曲目的调色板，并在新旧之间逐色交叉淡化**。
     *
     * ## 为什么必须**逐色**插值（而不是"整表换掉"）
     *
     * 流光是**逐柱取色**（`spectrumFlowColorAt(base, i, count, palette, phase)`），
     * 换调色板若整表替换 ⇒ ★ 用户看到的是**所有柱的颜色同时跳一下** ✗ ——
     * 那正是"切歌一瞬间"最刺眼的一帧。
     * ⇒ 与 `blendedCoverRgb` 对主色做的是**同一件事**：按 `coverMix` 逐项插值 ✓
     *
     * ## 复用 [coverMix] 而不是自建一条时间轴
     *
     * `coverMix` 由 `resolveCoverMix` 在**新封面到达**那一帧起 0 → 1（260 ms）✓ ——
     * 而调色板**恰好在同一帧**才更新（两者都由同一张新图触发）
     * ⇒ ★ 直接复用 ⇒ **颜色过渡与图片过渡天然同步**（这正是既有 KDoc 强调的
     * "只淡图不淡色 ⇒ 两者不同步、比不做更刺眼"那条纪律）✓
     *
     * ⚠️ **只在两表等长时插值**：不等长说明"色数变了"，逐项插值没有对应关系
     * （会得到错位的中间色）⇒ ★ 此时**直接换表**（宁可跳一下，也不要错位的颜色）✓
     *
     * @param raw 本帧由封面算出的调色板（`size < 2` = 本曲确实取不到多色）
     */
    private fun holdAndBlendPalette(raw: List<Int>): List<Int> {
        val now = System.currentTimeMillis()
        if (raw.size < 2) {
            // 本曲确实取不到多色 ⇒ 走退化路径（但**宽限窗内**仍保持上一份）✓
            return coverPaletteHoldOrEmpty(paletteHold, paletteHoldAtMs, now, coverPending)
        }
        if (paletteHold.size < 2) {
            // 首次取到（没有旧表可过渡）⇒ **不做动画**（与主色的"首帧不淡入"同一条理由）✓
            paletteHold = raw
            paletteHoldAtMs = now
            palettePrev = emptyList()
            return raw
        }
        if (raw != paletteHold) {
            // ★ 新调色板到达 ⇒ 起点 = 上一次的（**未插值**的）那份
            palettePrev = paletteHold
            paletteHold = raw
            paletteHoldAtMs = now
            val from = palettePrev
            // ★ 复用 `coverMix`：新封面到达那一帧它从 0 起 ⇒ 与图片过渡同步 ✓
            if (from.size == raw.size && coverMixRunning) {
                return raw.indices.map { i ->
                    blendPackedArgb(from[i], raw[i], coverMix)
                }
            }
            return raw
        }
        // 同一份 ⇒ 只刷新时刻（延长宽限窗）✓
        paletteHoldAtMs = now
        return raw
    }

    /**
     * 本帧用于取色的**封面主色**（打包 RGB；`null` = 确实用不了主色）。
     *
     * 与直接写 `coverImage?.let { coverRgbOf(it) }` 的区别只有一处：
     * **封面这一帧拿不到时，只要曲目没换、就沿用上一次成功的主色** ——
     * 理由与取舍见 [stickyCoverColorPath] 的 KDoc（用户报的"外观短暂变默认"）。
     *
     * ## 粘性的边界（三条，都必须是"曲目变了"或"从未成功"才回退）
     *
     * | 情形 | 结果 |
     * | --- | --- |
     * | 本帧有图 | 用图算（并刷新粘性缓存） |
     * | 本帧无图，但曲目未变且上次取到过色 | **沿用上次的色**（消除闪烁） |
     * | 本帧无图，曲目变了 / 上次没取到色 | `null` → 按 `manual` 渲染（正确回退） |
     *
     * ⚠️ **不许把"上次取到的是 null"也粘住后当成有值** —— 有些曲子确实没有可用主色
     * （纯黑白封面）。那种情况就该走 `manual`，粘一个假色反而更糟。
     */
    private fun resolveCoverRgb(coverImage: BufferedImage?): Int? {
        if (coverImage != null) {
            val rgb = coverRgbOf(coverImage)
            val path = snapshotSupplier().path
            if (path.isNotEmpty()) {
                stickyCoverColorPath = path
                stickyCoverColorRgb = rgb
            }
            // ★★★ 2026-10-13（BUG-191）：刷新**跨曲目保持**用的主色。
            //   ⚠️ 只在 `rgb != null` 时刷新 —— 一张"取不出主色"的封面（纯黑白）
            //     不该把上一次的好色冲掉（否则下一首切进来时会闪一下默认色）✓
            if (rgb != null) {
                coverRgbHold = rgb
                coverRgbHoldAtMs = System.currentTimeMillis()
            }
            coverUnavailableLogged = false
            return rgb
        }
        val path = snapshotSupplier().path
        if (path.isNotEmpty() && path == stickyCoverColorPath) {
            // ── 诊断 A：**曾取得后丢失**（2026-09-18）──────────────────────────
            //
            // **这一行是给"外观短暂变默认"准备的取证点**。
            //
            // 走到这里意味着：**曲目没换，但本帧取不到封面** —— 而用户配置里
            // `spectrum.color_mode` / `background.color_mode` 都是 `cover`，
            // 所以这正是"频谱色回归初始 + 背景变默认 + 封面消失"的**唯一**入口。
            // 但**具体是谁让封面这一帧不可用，静态分析定不了案**（候选：该帧
            // `needsCoverImage` 判为 false、路径暂空、provider 尚未就绪）。
            //
            // 故这里**打一行指名道姓的告警**（项目纪律：告警要么指名道姓、要么别打），
            // 把"触发点真的发生了"变成一条可 grep 的事实。有了它，下次复现时
            // 看这一行**前后相邻的日志**（谁在这之前改了设置 / 切了曲）就能定位。
            //
            // **只在状态翻转时打一次**（`coverUnavailableLogged`）——
            // 这是"按集合变化去噪"的同一套做法，避免逐帧刷屏把日志淹掉。
            if (!coverUnavailableLogged) {
                coverUnavailableLogged = true
                System.err.println(
                    "[CDL] ⚠️ 曲目未变但本帧取不到封面（$path）—— " +
                        "凡取色方式为「跟随封面」的项（频谱色 / 背景色）本帧会回退到手动色。" +
                        "已沿用上一次的主色，画面不应再闪；但**触发点就在这里**，" +
                        "请把本行连同上下文反馈以便定案。"
                )
            }
            return stickyCoverColorRgb
        }
        // ── 诊断 B：**本曲从未取得封面**（2026-09-19 新增）────────────────────
        //
        // ## 为什么必须补这一条
        //
        // 原实现**只**覆盖「曾取得后丢失」（上面的诊断 A），而它的判据
        // `path == stickyCoverColorPath` 要求 `stickyCoverColorPath` 非空 ——
        // 那个字段**只在成功取到过图时**才会被赋值。
        // 于是「**从头到尾就没拿到封面**」这个**最常见的失败一声不响**：
        // 既没有取色成功的日志，也没有取色失败的日志，用户看到的只是
        // 「颜色没生效」。真机取证（2026-09-19）：用户四个 `*_color_mode`
        // 全是 `cover`，而日志里**两者都没有** —— 这正是那次缺陷无法定案的原因。
        //
        // ## 判据与去噪（两条缺一不可）
        //
        // · `path != stickyCoverColorPath` ⇒ 本曲**一次都没成功过**；
        // · `!coverPending` ⇒ **等待期已结束**。`coverFor` 的契约是"首次必返回 null"，
        //   若不加这一条，**每首歌的前一两帧**都会命中本分支 → 每首歌一行噪声
        //   （正是本项目明令禁止的"日志噪声"）。`coverPending` 只在"拿到图"
        //   或"补重绘重试耗尽"时置 false，故它等价于"provider 已给出结论"。
        // 按**曲目路径**去噪（`coverNeverLoggedPath`）：同一首歌只打一次。
        if (path.isNotEmpty()) {
            if (!coverPending && coverNeverLoggedPath != path) {
                coverNeverLoggedPath = path
                System.err.println(
                    "[CDL] ⚠️ 本曲**从未**取得封面（$path）—— " +
                        "凡取色方式为「跟随封面」的项（歌词 / 背景 / 频谱 / 边框）" +
                        "**全程**用手动色渲染，画面看起来就是「默认形态」。" +
                        "常见原因：① 这首歌没有内嵌封面、同目录也没有 cover/folder/front 图片；" +
                        "② 封面存在但解析或解码失败（`CoverArtProvider` 对失败一律静默返回 null，" +
                        "没有独立日志）。若您确认这首歌有封面，请把本行反馈 —— 那属于原因 ②。"
                )
            }
            // ★★★ 2026-10-13（BUG-191）：★ **先问"空窗期要不要沿用上一次的主色"** ✓
            //   ★ 宽限窗内 ⇒ 返回上一次的主色（**不回退 manual**）⇒ 用户看不到默认色 ✓
            //   ★ 窗外 / 从未取到过 ⇒ 仍返回 `null`（认账"本曲无封面"，按 manual 渲染）✓
            //   ⚠️ 上面那些诊断日志**照旧打**（它们记的是"取色失败"这个事实）——
            //     本行只改**渲染用什么色**，不改"有没有失败" ✓
            return coverRgbHoldOrNull(
                coverRgbHold, coverRgbHoldAtMs, System.currentTimeMillis(), coverPending,
            )
        }
        coverUnavailableLogged = false
        return coverRgbHoldOrNull(
            coverRgbHold, coverRgbHoldAtMs, System.currentTimeMillis(), coverPending,
        )
    }

    /**
     * 「取色诊断」——本帧**渲染到底采用了哪种颜色来源**，一行说清（2026-09-19 新增）。
     *
     * ## 为什么必须有这一行（真机取证的空白）
     *
     * 用户报告「插件启用后**不是按设置取色进行渲染，而是默认形态**」。而
     * `super-island.log` 里**既没有取色成功的记录、也没有取色失败的记录** ——
     * 于是三个互相独立的可能（H1 / H2 / H3，见下）在日志里**完全不可区分**，
     * 这正是那次缺陷无法定案的原因。
     *
     * 本行把 H1 与 H2 一刀切开：
     *
     * | 打印结果 | 结论 |
     * | --- | --- |
     * | 四个 `*_color_mode` 是 `manual`（而用户设置里是 `cover`） | **H1**：渲染层读到的配置就是默认值 |
     * | 四个 `*_color_mode` 是 `cover` 但 `coverRgb=null` | **H2**：配置对，但取不到封面色 |
     * | 四个 `*_color_mode` 是 `cover` 且 `coverRgb` 有值 | 取色链路正常，问题在别处（H3 取色器写入） |
     *
     * 同时打印**五个解析后的颜色**：它们就是最终画到屏幕上的颜色 ——
     * 「画面是默认形态」这句话，从此有了**逐字节可比**的定义
     * （出厂默认值：已唱 `#FFFFFF` / 未唱 `#7E818A` / 背景 `#14161A` /
     * 频谱 `#84D8BC` / 边框 `#FFFFFF`）。若打印出来的正好是这一组，
     * 「默认形态」就被钉成了**日志里的一行事实**，不再需要靠肉眼描述。
     *
     * ## 去噪（硬纪律：不许逐帧刷屏）
     *
     * 键 = 「四个 mode + `cover.enabled` + provider 是否为 null + `coverRgb`
     * + 曲目路径 + 粘性路径」这一整个状态元组，见 [colorSourceDiagKey] 的 KDoc。
     * **只有元组变化时才打** —— 同一首歌同一套颜色来源，一帧也只打一行。
     *
     * ⚠️ 本方法**只读**：不写任何字段（除去噪键本身），不改渲染结果。
     *   它在 [IslandPanel.paintComponent] 里、`coverRgb` 算出之后被调用，
     *   故打印的五个颜色与**本帧实际要画的**是同一组输入。
     */

    private fun logColorSourceDiag(local: Appearance, coverRgb: Int?) {
        val path = snapshotSupplier().path
        val key = buildString {
            append(local.lyricColorMode).append('|')
            append(local.backgroundColorMode).append('|')
            append(local.spectrumColorMode).append('|')
            append(local.borderColorMode).append('|')
            append(local.coverEnabled).append('|')
            append(coverArtProvider == null).append('|')
            append(coverRgb).append('|')
            append(path).append('|')
            append(stickyCoverColorPath)
        }
        if (key == colorSourceDiagKey) return
        colorSourceDiagKey = key
        System.err.println(
            "[CDL] 🎨 取色来源（曲目=$path）—— " +
                "mode 词/底/频/框 = ${local.lyricColorMode}/${local.backgroundColorMode}/" +
                "${local.spectrumColorMode}/${local.borderColorMode} · " +
                "cover.enabled=${local.coverEnabled} · " +
                "provider=${if (coverArtProvider == null) "null" else "ok"} · " +
                "sticky=${stickyCoverColorPath.length} 字符" +
                (if (stickyCoverColorPath.isEmpty()) "" else "（$stickyCoverColorPath）") + " · " +
                "coverRgb=${coverRgb?.let(::hexRgb) ?: "null"} · " +
                "解析色：已唱=${hexRgb(local.sungColorFor(coverRgb).rgb)} " +
                "未唱=${hexRgb(local.unsungColorFor(coverRgb).rgb)} " +
                "译文=${hexRgb(local.translationColor.rgb)} " +
                "背景=${hexRgb(local.backgroundColorFor(coverRgb).rgb)} " +
                "频谱=${hexRgb(local.spectrumColorFor(coverRgb).rgb)} " +
                "边框=${hexRgb(local.borderColorFor(coverRgb).rgb)}"
        )
    }

    /**
     * 左侧封面区的**边长**（px）；`null` = 本帧不显示封面区（文字占满整宽）。
     *
     * ## 三种"不显示"的语义不同，必须分开
     *
     * | 情况 | 返回值 | 为什么 |
     * |---|---|---|
     * | `cover.enabled = false` | `null` | 用户关掉了这个元素 —— 与其它元素开关一致，**不占位** |
     * | 没有曲目路径 | `null` | 首帧早于宿主回调，没有歌就没有封面可言，占位只会让文字白白左移 |
     * | 已有图 / 仍在等待 | 边长 | 见下 |
     * | 确认本曲没有封面 | `null` | 收窄，让文字占满整宽（否则永远空一块） |
     *
     * ## 为什么"仍在等待"要占位
     *
     * 占位的代价是"一首没封面的歌在重试耗尽前左边空一块"（最多
     * [MAX_COVER_REPAINT_ATTEMPTS] × [COVER_REPAINT_DELAY_MS] ≈ 1 秒）；
     * 不占位的代价是"**每一首有封面的歌**，封面到达时文字都会突然右移一段"。
     * 前者是罕见的一次性瑕疵，后者是常态化的可见跳动 —— 故取占位。
     *
     * ## 边长怎么定
     *
     * 受**歌词区高度**约束（上下各留 [VERTICAL_PADDING]），并设上限
     * [MAX_COVER_SIZE] —— 上限存在是因为词岛很宽（`layout.max_width` 可到 1200），
     * 若边长只按高度推导，一个 158 px 高的词岛会得到 138 px 的封面，
     * 喧宾夺主。56 px 在默认字号（22 px）下与**歌词块**的视觉重量相当。
     */
    //
    // ★★★ 2026-10-12（**BUG-181 的决定性诊断**）：**一行定位"封面多色流光无效"**。
    //
    // ## 为什么必须是"四个量一起打"
    //
    // ★ 用户报「**设置随封面取色流光无效，呈现默认色**」后，我提了两条假设
    //   （① 调色板色数不足 ② `fade_top` 吃掉逐柱色）—— ★★ **两条都站不住** ✗
    // ⇒ ★★★ 教训：**"静默失效"类报障，先打诊断，不要先改代码** ✓
    //
    // | 量 | 若异常 ⇒ 断点在 |
    // |---|---|
    // | ① `mode` | ★ **不是 `cover_flow`** ⇒ 设置没读进来 / 键名不对 |
    // | ② `paletteN` | ★ **< 2** ⇒ `spectrumFlowColorAt` 静默退回基色 |
    // | ③ `fnOk` | ★ **false** ⇒ 回调没建 ⇒ 整带同色 |
    // | ④ `fadeTop` | ★ 用于对照画笔走哪个分支 |
    //
    private fun logFlowDiag(mode: String, palette: List<Int>, fnOk: Boolean, fadeTop: Boolean) {
        val key = "$mode|${palette.size}|$fnOk|$fadeTop"
        if (key == flowDiagKey) return
        flowDiagKey = key
        System.err.println(
            "[CDL] 🎨 流光诊断：档位=$mode · 调色板=${palette.size} 色 · " +
                "逐柱回调=${if (fnOk) "有" else "**无**"} · fade_top=$fadeTop" +
                if ((mode == dev.cdl.render.CoverPalette.COVER_FLOW ||
                        mode == dev.cdl.render.CoverPalette.COVER_CAMO) && (!fnOk || palette.size < 2)) {
                    "  ✗ **本档应当有流光，但上面的某个量为空/不足 ⇒ 这就是断点**"
                } else "",
        )
    }

    /**
     * ★★★ 2026-10-12（**BUG-181**）：**调色板诊断** —— 把"本帧到底取了几个色"变成可 grep 的事实。
     *
     * ## 为什么必须有（本批的根因是【静默】的）
     *
     * ★ 用户报「**设置随封面取色流光无效，呈现默认色**」——
     *   ★★ 而根因链的最后一环是 `spectrumFlowColorAt` 的
     *   `if (n < 2) return baseArgb` ⇒ ★ **静默退回单色**，**没有任何日志** ✗
     * ⇒ ★ 这一行让"选了多色档却看不出多色"**当场可查** ✓
     *
     * ★ 去噪方式与 [logColorSourceDiag] 同一条：**只记取色结果**（色数 + 十六进制），
     *   ★ 同一条结果只打一行（否则每帧一行会淹掉日志）。
     */
    private fun logPaletteDiag(palette: List<Int>) {
        val key = palette.joinToString(",") { "%06X".format(it and 0x00FFFFFF) }
        if (key == paletteDiagKey) return
        paletteDiagKey = key
        val n = palette.size
        // ★ 明确写出"是否足够流光"—— ★★ `n < 2` 就是那个静默退回的阈值 ✓
        val verdict = if (n >= 2) "✓ 足够流光" else "✗ **不足 2 色** ⇒ 流光会退回单色"
        System.err.println(
            "[CDL] 🎨 封面调色板：$n 色 [" +
                palette.joinToString(" ") { "#%06X".format(it and 0x00FFFFFF) } +
                "] $verdict",
        )
    }

    private fun leadingCoverSize(
        local: Appearance,
        image: BufferedImage?,
        /**
         * 本帧的岛体几何 —— 封面外径由它的**内容区高**推导
         * （[BandGeometry.coverOuterSizePx]，即 `lyricsHeight − 2×内边距`）。
         *
         * ⚠️ 2026-09-23：此前收的是 `areaHeight: Float`，调用点传 `bg.islandHeight`
         *    （**含控件带**）⇒ `f = 1` 时封面横跨进控件带。用户第 ③ 条：
         *    > 「**封面在词岛生长仍然与其他控件重叠。**」
         *    改成传整个几何，尺寸的**唯一几何出口**就是 [BandGeometry.coverOuterSizePx]。
         */
        geometry: BandGeometry,
        /**
         * 是否**强制保留**封面槽位。用于"本曲没有封面 → 旧图正在淡出"那一段：
         * 那时 `coverShown` 已是 `null`，若不保留，槽位会**立刻塌缩** ——
         * 文字瞬间占满整宽，而淡出还没画完（看起来是"文字跳了一下 + 封面闪没了"）。
         */
        reserveWhileFading: Boolean = false,
    ): Float? {
        if (!local.coverEnabled) return null
        if (coverRequestPath.isEmpty()) return null
        if (image == null && !coverPending && !reserveWhileFading) return null
        // ⚠️ 2026-09-18：上限**只在 `cover.scale_with_font = false` 时才套**。
        //
        // 原状：无条件 `coerceIn(MIN_COVER_SIZE, MAX_COVER_SIZE)` ——
        //   而 `MAX_COVER_SIZE = 56`，默认字号下 `areaHeight - 20 = 105` 就已经撞上限，
        //   于是**字号再大封面也不变**（用户报的"封面不随字体缩放"）。
        //
        // 现在：`scale_with_font = true`（默认）→ **不套上限**，让封面真正跟随歌词区高度；
        //   `false` → 保留旧的上限夹取。
        //   下限 `MIN_COVER_SIZE` **两种情况都保留** —— 它是"封面不能小到看不清"的护栏，
        //   与"是否跟随字号"无关。
        //
        // ⚠️ 2026-09-22：公式**已搬到** [leadingCoverOuterSizePx]（公开纯函数）——
        //    设置页预览（`IslandPreviewPane.coverSideFor`）此前是**逐字相同**的第二份，
        //    而本轮封面尺寸变成展开进度 `f` 的函数，两份各改一次必然漂移
        //    （表现：设置页示例与真机不一致）。故收敛到一处，两侧都调它。
        //
        // ⚠️ 返回的是封面**外径**（含黑胶环的外接正方形边长，2026-09-22）——
        //    槽位宽度（[coverSlotWidth]）与内容（`coverContentSize`）都由它推导。
        // ⚠️ 2026-09-23：入参从"任意高度"收窄成"**内容区高**"（见本函数 KDoc）——
        //    这是"封面不压控件带"这条结构性质的落点，别再改回 `islandHeight`。
        return geometry.coverOuterSizePx(local.coverScaleWithFont)
    }

    // ── 左侧频谱槽位：**已删除**（2026-09-17）───────────────────────────────
    //
    // 原记：`leadingSpectrumSize(local, areaHeight)` —— 频谱与封面**共用左侧槽位**，
    //   尺寸算法与封面逐字一致，靠调用点保证"封面优先、只有封面为空时才轮到频谱"。
    //
    // 实为：用户要求「**已实现的频谱跳动效果保留，但应将其置于歌词背景
    //   （随着歌词长度变化）中进行跳动**」。频谱移到歌词背景后：
    //   · 它**不再占任何布局宽度** → 本函数的存在前提消失，故删除（不是改名）；
    //   · `cover.enabled` 与 `spectrum.enabled` 的**互斥关系随之解除** ——
    //     原实现 0.8.9 的 `LeadingContent` 是互斥枚举，本项目按设置面拆成两个开关后
    //     只能靠"封面优先"制造确定性；现在两者画在**不同的图层**（左侧 vs 背景），
    //     互斥不再有必要，同开的结果天然确定。
    //   · 尺寸推导搬进 `IslandPanel.drawBackgroundSpectrum`（它按歌词区矩形算，
    //     不再需要"与封面一致"这个约束）。

    // ── 设置订阅与外观应用 ────────────────────────────────────────────────

    /**
     * 订阅设置变更。
     *
     * ⚠️ **2026-09-24 更正**：本句原写「（**而不是按 `change.changedKeys` 增量处理**）」✗ ——
     * **与本方法的实现相反** ✓（勘察报告 W1-01 指出）。
     *
     * **取证**：同一方法里确有
     * `if (change.touchesAny(SettingDomains.GEOMETRY))`
     * 这个分支 ✓ —— 它**是一处真实的增量优化**：
     * 外观（颜色/描边…）便宜，**几何重算贵** ⇒ 只有 `layout.*` / `main_lyric.font.*` 变更时
     * 才额外调 `applyGeometry` ✓。
     *
     * ⚠️ **2026-09-24（第 1 期 ⑧）**：本行原引的是字面量
     * `changedKeys.any {{ it.startsWith("layout.") || it.startsWith("main_lyric.font.") }}`；
     * 现收敛到 [SettingDomains.GEOMETRY] —— 判定点**唯一**，且域可被机械守卫
     * （`SettingDomainProbe` 断言"每个域名都命中 ≥1 个注册表键"）。
     * 帧域同批收敛（见 [applyAnimationSettings] 调用处的门控）。
     *
     * ⇒ 正确的说法是：**外观整份重算**（这一点原文没错 ✓），
     *   **但几何按变更域定向触发**（原文说反了 ✗）。
     *
     * 为什么**整个外观都从快照重算**（而不是按 `change.changedKeys` 增量处理）：
     * 外观项之间存在耦合（改 `shape.type` 需要同时考虑 `shape.corner_radius`；
     * 改不透明度影响整幅绘制），增量处理要写一张「键 → 重算什么」的对照表，
     * 而那张表会随设置项增加而腐化。全量重算的代价是「重设几个字段 + 一次 repaint」，
     * 完全可以忽略 —— 这里**不用**为性能做增量优化。
     */
    private fun installSettingsSubscription() {
        val runtime = settings ?: return
        settingsSubscription = runtime.subscribe { fresh, change ->
            // 订阅回调可能在**任意线程**（设置窗口的 UI 线程、文件轮询线程）触发，
            // 故整段处理都投到 EDT —— 包括对 window 的读写。
            onEdt {
                // ★★★ 2026-10-10（U3 复发排查）：**颜色类变更留痕**。
                //
                // ## 为什么加这一条
                //
                // ★ 用户报「**改完描边色按「确定」，不落盘、颜色不变；拖滑杆也不变**」——
                //   ★ 而**同样的症状 2026-10-08 报过一次**（`migration/` 的 U3 批），
                //   那次的修法是"自动解门控 + 关同步开关"。
                // ★★ 但**没有任何一条日志能回答"词岛到底收到这次变更没有"** ⇒
                //   只能靠猜（"是写入没发生？还是通知没到？还是画的时候没用？"）✗
                // ⇒ ★ 本行把**第二段**（词岛侧）也变成可观测的 ✓
                // ★ 只对**颜色/描边**类键打（拖动时高频，全打会刷屏）✓
                if (change.changedKeys.any {
                        it.contains("color") || it.contains("outline")
                    }
                ) {
                    System.err.println(
                        "[CDL] 词岛收到设置变更：kind=${change.kind} " +
                            "keys=${change.changedKeys}",
                    )
                }
                snapshot = fresh
                applyAppearance(fresh)
                applyExtensionStyles(fresh)
                // 几何也要重算：字号变化会改变窗口高度（见 applyGeometry 的说明），
                // 漏掉这一句会让用户把字号调大后文字被裁在词岛之外。
                // 只改尺寸不动位置 —— 用户拖过的地方必须保持，否则改个字号窗口就跳走。
                if (change.touchesAny(SettingDomains.GEOMETRY)) {
                    applyGeometry(fresh)
                }
                // 动画设置要**在 syncFrameDriver 之前**推入 —— 它会改帧间隔，
                // 而 syncFrameDriver 可能正在启动定时器（用旧间隔启动就白启一次）。
                //
                // ★★ 2026-09-24（第 1 期 ⑧）：**必须按帧域门控**。
                //   它末尾是 `framePacer.reset()` + 重设 `frameTimer.delay`；
                //   而本订阅体对**任何**变更都会被调用（拖颜色滑杆时按鼠标采样率同步通知）——
                //   无门控 ⇒ ⑦ 的绝对截止期 epoch 被反复作废，
                //   帧节拍退化成"每帧 delay = interval"，即 ⑦ 修掉的 ×1.07 超发。
                //   取证与判据：`02-第1期执行记录` §11 · `SettingDomainProbe` §B。
                if (change.touchesAny(SettingDomains.FRAME)) {
                    applyAnimationSettings(fresh)
                }
                // 显隐与帧驱动也要跟着刷新：`island.enabled` 直接在设置里被改
                // （不经右键菜单）时，必须让窗口立刻响应，否则要等下一次
                // 播放态变化才生效 —— 那是可感知的"设置没反应"。
                applyVisibility(fresh)
                syncFrameDriver()
            }
        }
    }

    /** 应用外观（形态、圆角、不透明度、置顶、穿透）并重绘。**必须在 EDT 上调用。** */
    private fun applyAppearance(fresh: SettingsSnapshot) {
        panel.appearance = Appearance.from(fresh)
        // ★★ 2026-09-28：L3（真透视）的开关点。放在这里 = 与 `material.quality`
        //    **同一个触发点**（见 `IslandPanel.syncL3` 的 KDoc）。
        //    ⚠️ 必须在 `repaint()` **之前** —— 否则第一帧会画到"还没有背景"的纯色态，
        //       用户会看到一次闪（虽然很快，但那是可避免的）。
        panel.syncL3(panel.appearance)
        // ★★★ D-1（2026-10-09）：**L2 玻璃的 GPU 后端开关注入点**。
        //
        // ## 为什么落在这里（与 L3 的 `preferGpu` 同一条约定）
        //   `material.render.gpu_backend` 是**全局后端开关**，它同时管 L3（真透视采集）
        //   与 L2（逐像素玻璃）。L3 那条路在 `syncL3` 里读 → 已由 `panel.appearance` 带下去 ✓；
        //   ★ 但 L2 那条路（`MaterialLayers.liquidGlassImage`）**在 `Appearance` 之外**，
        //     它是 `MaterialLayers` 这个 **object** 的字段 ⇒ 必须在**这里显式推一次**，
        //     否则设置改了它对消费点**不可见**（= 本项目最多次的"设了没反应"形态）。
        //
        // ## 为什么"推一次"而不是"每帧读设置"
        //   与 `applyAppearance` / `applyAnimationSettings` **同一条约定**（见本文件头）：
        //   设置变更时推一次，消费点只读字段。
        //   ★ 线程口径：`MaterialLayers.gpuEnabled` 声明为 **`@Volatile`**（见该字段）——
        //     由 EDT 写、由 `glassPool` 的单线程读 ⇒ **写读跨线程**，故 `@Volatile`
        //     是必需的（★ 原稿曾写"非 volatile、正好够"，**那是错的**：跨线程可见性
        //     不能靠"推一次"来保证，只能靠 `@Volatile` 或同步块）✓
        //
        // ## ⚠️ 必须在 `repaint()` **之前**
        //   与上面的 `syncL3` 同因：否则第一帧仍按旧开关出图（用户会看到一次"闪"）。
        dev.cdl.render.MaterialLayers.gpuEnabled = panel.appearance.materialRenderGpuBackend
        panel.repaint()
    }

    /**
     * 把 `animation.*` 的三个设置推给 [animator]。**必须在 EDT 上调用。**
     *
     * ## 为什么是"推"而不是每帧"读"
     *
     * 与 `applyAppearance` 同一条约定：**设置变更时推一次，而不是每帧读设置**。
     * 每帧读要付一次 `SettingsSnapshot` 查表的代价（30–60 次/秒），
     * 而设置在一帧之内**不可能变两次** —— 那个代价买不到任何东西。
     *
     * ## 三件事各自的作用点
     *
     * | 设置 | 作用 |
     * | --- | --- |
     * | `animation.enabled` | 关掉时**立即把所有在跑的动画落到终值**（不是"变快"） |
     * | `animation.quality` | 改帧间隔 —— 故这里要同步 `frameTimer.delay` |
     * | `animation.duration_scale` | 之后新起的动画按倍率折算时长（**不改已在跑的**） |
     *
     * ⚠️ 最后一条是**刻意**的：已在跑的过渡若中途改时长，进度会由绝对时间重算而
     * **瞬间跳变**（`rawProgress` 用的是 `nowNanos - startNanos`）。
     * "改设置时画面闪一下"比"这次改动下一帧才生效"糟糕得多。
     */
    private fun applyAnimationSettings(fresh: SettingsSnapshot) {
        animator.applySettings(
            enabled = boolOf(fresh, "animation.enabled"),
            quality = textOf(fresh, "animation.quality"),
            durationScalePercent = intOf(fresh, "animation.duration_scale"),
            // 帧率独立于质量档（2026-09-21）：`AUTO` = 跟随显示器刷新率。
            frameRate = textOf(fresh, "animation.frame_rate"),
        )
        // 帧率档决定帧间隔（1000/Hz）—— 定时器要跟着调，否则设置改了帧率不变。
        // `Timer.delay` 的 setter 在运行中也安全（下一帧起用新间隔）。
        // ⑦ 间隔变了 ⇒ 旧截止期序列作废，以**现在**为新起点（否则新间隔会从
        //    一个属于旧序列的时刻开始计数，第一帧就偏）。
        framePacer.reset()
        frameTimer?.delay = animator.frameIntervalMs.toInt()
        // ★ 每秒帧率日志开关（`animation.frame_log`，用户 2026-09-28 要求，**测试用**）。
        //   按项目约定"**每次变更推一次，而不是每帧读设置**"（见 [animator] 的 KDoc）——
        //   故这里推一次，`onFrameTick` 里**只读字段**。
        //   ⚠️ `enabled` 的 setter 在值变化时会 `reset()` 计量器的窗口
        //     ⇒ 打开/关闭都不会把"另一段时期"的时间算进当前窗口（见 `PerSecondMeter` KDoc）。
        //   ⚠️ 键名在 `animation.` 命名空间下**是刻意的**：本方法只在
        //     `change.touchesAny(SettingDomains.FRAME)` 为真时被调用，而
        //     `FRAME = setOf("animation.")` ⇒ 换了命名空间本开关会**静默失效**。
        framePerSecond.enabled = boolOf(fresh, "animation.frame_log")
    }

    /**
     * 把设置里的**几何**应用到窗口（尺寸与位置策略）。**必须在 EDT 上调用。**
     *
     * 尺寸策略：宽度取 `layout.max_width`（注册表里是 **280..2400** —— 见下）。
     *
     * ## ⚠️ 2026-09-23 修两处（同一处曾挂两段 KDoc）
     *
     * **① 陈旧值**：这里原写「在注册表里**保证 280..1200**」✗ ——
     * 注册表 `SettingRegistry.kt:964` 自己就记着
     * 「**原记 `280.0..1200.0`，实为 `280.0..2400.0`**（2026-09-17 放宽）」，
     * 用户原话是「**加大允许的歌词框最大长度**」✓。
     *
     * **② 孤儿 KDoc**：本段与下面那段原先**各是一段独立 KDoc、挂同一个声明** ✗ ——
     * Kotlin 只把**紧邻**的那段挂上去，**前一段会成孤儿**（编译器不报错、IDE 也提示不到）。
     * 已**合并为一段** ✓。（本项目已出现 3 次，是**高发结构缺陷**。）
     *
     * ---
     *
     * ## `layout.max_width` 的**有效上限**：再夹一道「屏幕可用宽度」。
     *
     * ## 为什么必须夹（2026-09-21 真机取证）
     *
     * `layout.max_width` 的注册范围是 `280..2400`。**2400 是为 2560 宽的屏选的**
     * （原注释：「在 2560 宽的屏上留 80 px 边距，词岛居中，两侧各 80」）——
     * 但它是**全局固定**的，不随屏幕变。
     *
     * 真机（用户）实际配置：`layout.max_width = 2400`，
     * 而这块屏的可用区是 **1707×1067**（逻辑像素）。于是词岛宽 **2400** ——
     * **比整块屏幕还宽 693 px**。三个后果：
     *
     * | 后果 | 表现 |
     * | --- | --- |
     * | 窗口比屏幕宽 | `window.position` 的「中心必须落在屏内」判据**永远算在屏外** → 每次启动都回退居中（真机日志 11 次） |
     * | 超大透明窗 | 每次移动/重绘都要合成 2400×125 的 per-pixel alpha 位图 → **拖动卡顿** |
     * | 内容画到屏外 | 贴边绘制（频谱、边框）有一截落在屏幕外，用户看不到 |
     *
     * ## 判据用「屏宽 − 两侧边距」而不是某个百分比
     *
     * 原设计的 `2400` 恰好 = `2560 − 2×80`，即「屏宽减固定边距」。
     * 本方法**沿用同一个公式**，只是把硬编码的 2400 换成按当前屏算 ——
     * 于是 2560 的屏仍然得到 2400（**零行为变化**），而小屏自动收缩。
     *
     * **不用百分比**的理由：边距是"给桌面留白"的视觉常量，
     * 它不该随屏幕线性缩放（大屏上按 4% 会留出 100+ px 的夸张边距）。
     */
    private fun effectiveMaxWidth(configured: Int): Int {
        val screen = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        }.getOrNull() ?: return configured
        if (screen.width <= 0) return configured
        val byScreen = screen.width - 2 * SCREEN_SIDE_MARGIN_PX
        return minOf(configured, byScreen).coerceAtLeast(MIN_ISLAND_WIDTH)
    }

    private fun applyGeometry(fresh: SettingsSnapshot) {
        val maxWidth = effectiveMaxWidth(intOf(fresh, "layout.max_width"))
        // ★ BUG-225：绘制侧要用它判"是否顶到上限"（见 [maxWidthPxCache]）
        maxWidthPxCache = maxWidth
        // 高度由字号推导：**主歌词行 +（显示时）译文行**，加一点上下留白。
        // `lineHeight = fontSize * LINE_HEIGHT_RATIO` 与绘制侧用的是**同一个公式** ——
        // 这里必须与 IslandPanel 的布局计算保持一致，否则文字会挤出背景形状
        // （背景按窗口尺寸画，文字按自己的公式排；两者不一致就是"文字露在圆角外"）。
        // 故公式抽成 companion 常量 + 工具函数，两处共用同一个来源。
        val fontSize = intOf(fresh, "main_lyric.font.size")
        // 显示译文时，译文行会真实占用垂直空间 —— 高度必须同步 +1 行，
        // 否则译文会画到背景形状之外（或被 paintComponent 的 clip 切掉）。
        // 这是 bug-015 引入译文行后**必须**一起改的地方。
        val translationEnabled = boolOf(fresh, "layout.translation.enabled")
        // 行数（`layout.lines`）与行间距（`layout.line_gap_px`）同样影响高度 ——
        // 2026-09-16 新增，见 islandHeightFor 的 KDoc。**必须在这里也传进去**，
        // 否则高度与绘制不一致 → 文字整体偏上、下方空一块。
        // ⚠️ BUG-079：`layout.lines` 其实**不影响高度** —— `SINGLE.rows == DUAL.rows == 1`。
        //   双行的第二行是译文，其高度由 `layout.translation.enabled` 单独占。
        //   真正改变高度的是 `layout.translation.enabled` 与 `layout.line_gap_px`。
        val lines = LineCount.parse(textOf(fresh, "layout.lines"))
        val lineGapPx = intOf(fresh, "layout.line_gap_px").toFloat().coerceAtLeast(0f)
        // ── 宽度策略：`layout.fixed_width` = **是否随歌词长度收缩** ──────────────
        //
        // 原实现语义（`recon/16` 第 15 项；原实现实现见 `IslandLyricsLayout.kt:38`
        // 的 `if (settings.fixedWidth) limit else natural`）：
        //   · `true`  → 固定为 `layout.max_width`
        //   · `false` → 用**实测内容宽**（[naturalWidthPx]），上限 `max_width`
        //
        // ⚠️ **原记**：`baseWidthPx = maxWidth`（恒取上限）。
        //    **实为**：那其实是 `fixed_width = true` 的行为 ——
        //    而该键**默认值是 `false`**，于是默认行为与设置语义**正好相反**，
        //    且该键在渲染层**零消费点**（BUG-043 里唯一的纯实现遗漏）。
        //
        // ⚠️ `naturalWidthPx <= 0` 表示"还没测过"（首帧之前）→ 回退 `max_width`，
        //    否则会以 0 宽建窗（然后被下限兜到 280），看起来像"闪一下"。
        val fixedWidth = boolOf(fresh, "layout.fixed_width")
        // ── ★★ 2026-10-01（封面态返工 · R3/R4）：本态**收起宽 = 小岛宽** ──────────
        //
        // 用户原话：「**收起状态下只显示封面和包裹的岛体**」+「词岛**向右侧生长**」。
        // ⇒ 本态必须有**两个**宽度：**收起 = 封面外径 + 2×内边距**；**展开 = 通用宽度**；
        //   **生长量 = 两者之差**（`recon/67` §6.2 的"**倍数级**"，不是"带高 ≈76 px"）。
        //
        // ⚠️⚠️ **改前本态压根没有"小岛宽"这个量** ⇒ 收起宽直接落 `maxWidth`（全宽），
        //   而 `naturalWidthPx` 由**歌词文本宽**测出、本态无歌词槽 ⇒ **永不更新**
        //   ⇒ 收起态是一条**全宽词岛**（封面在左、右侧一大片空）——
        //   这正是用户 2026-10-01 报的「**呈现完全不一致**」。
        val genericWidthPx = if (fixedWidth || naturalWidthPx <= 0) {
            maxWidth
        } else {
            naturalWidthPx.coerceIn(MIN_ISLAND_WIDTH, maxWidth)
        }
        if (coverShapeSkipsLyricChain(textOf(fresh, "shape.type"))) {
            // 小岛宽 = 封面外径 + 2×内边距 ⇒ 与岛体高**同值**（本态收起是一个正方形）。
            coverExpandedWidthPx = genericWidthPx
            baseWidthPx = coverShapeCollapsedHeightPx(coverOuterSizePx = COVER_MAX_OUTER_SIZE_PX)
        } else {
            coverExpandedWidthPx = 0
            // ★★ 2026-10-03（⑱ 时钟 · 右端插槽）：**本档词岛向右扩展 +21 px** ——
            //   用户原话「**向右扩展词岛不就行了吗？歌词行保持居中。两端封面和时间组件保持对称感**」。
            //   ★ **为什么必须加**：真机只用插槽宽来**让歌词的右边界**
            //     （`textRightEdge = islandRight − 插槽宽`）⇒ 插槽 35 → 56 而词岛不动
            //     ⇒ 歌词区被**压窄 21 px**（= 回归）。
            //   ★ **加宽后的效果**：歌词区**尺寸与位置都不变**（`textRightEdge` 仍是旧值），
            //     岛体右端多出 21 px ⇒ 左封面 56 / 右插槽 56 ⇒ **两端对称** ✓
            // ★★★ 2026-10-04：**插槽补偿挪到高度算完之后**（见下方 `baseHeightPx` 之后那一段）。
            //   ## 为什么必须挪（这是本轮"牵动面"最需要注意的一处）
            //   `RADIAL` 档下**插槽宽 = 词岛高**（用户 2026-10-04 拍板"自适应"）
            //   ⇒ 补偿量**依赖高度** ⇒ 而高度在**本行之后**才算。
            //   ⚠️ 原写法（在算宽度时直接问插槽宽）在 `RADIAL` 档下会拿到**上一帧的高**
            //      （或首帧的 0）⇒ 表现为"词岛宽度**慢一帧**才对"（拖字号时会抖）。
            //   ★ 故：宽度先按**不含插槽**算，等高度出来后再补 —— 顺序即正确性。
            baseWidthPx = genericWidthPx
        }
        // ★ 2026-09-30（封面态 S3）：本态（`COVER_ONLY`）的高度走**封面口径** ——
        //   见 `coverShapeCollapsedHeightPx` 的 KDoc（`recon/67` §9-A 定案 ①）。
        //
        //   ⚠️ 为什么在**这里**分叉而不是改 `islandHeightFor`：
        //     那个函数的输入全是"歌词块"的构成量（字号/行数/行距/译文行），
        //     本态**根本没有歌词块** ⇒ 让它内部去判形态，等于把"两个不相干的
        //     尺寸体系"塞进一个函数。分开写，两条式子各自可读、各自可断言。
        //
        //   ⚠️ 封面外径传 `0f`：那是 `leadingCoverOuterSizePx` 的"未指定"约定，
        //     而 `coverShapeCollapsedHeightPx` 会**兜到 `COVER_MIN_OUTER_SIZE_PX`**
        //     （封面不能小到看不见）。★ 不用在这里算封面外径 —— 它依赖
        //     `islandHeight`，而 `islandHeight` 正是本行要求的量（**闭环**）。
        //     这与 `leadingCoverOuterSizePx` 的 KDoc 里记的那个环是同一个。
        baseHeightPx = if (coverShapeSkipsLyricChain(textOf(fresh, "shape.type"))) {
            // ⚠️⚠️ **2026-10-01 修**：这里原先传 `0f`，而 `0f` 是"未指定"的约定
            //   ⇒ `coverShapeCollapsedHeightPx` 兜到 `COVER_MIN_OUTER_SIZE_PX`(20)
            //   ⇒ 岛体高 = **40 px**（= 20 + 2×10）⇒ 封面只有 20 px。
            //   ★ 配合"宽度 = max_width"⇒ 收起态是一条 **~600×40 的扁条** ——
            //     用户 2026-10-01 报「**岛体外形也非常丑**」的直接来源。
            //   ⇒ 现在传 **`COVER_MAX_OUTER_SIZE_PX`(56)** ⇒ 岛体 = 56 + 2×10 = **76 px**
            //     （与 `recon/67` §11.3 的量化"封面可用宽 36 = 56 − 2×10"**同一个口径**）。
            coverShapeCollapsedHeightPx(coverOuterSizePx = COVER_MAX_OUTER_SIZE_PX)
        } else {
            islandHeightFor(
                fontSize, translationEnabled, lines, lineGapPx,
                // 副歌词比例**也影响岛体高度** —— 漏传会表现为"副歌词调大后被底边裁掉"
                // （见 `islandHeightFor` 的 `translationScale` 参数说明）。
                (intOf(fresh, "layout.translation.scale_percent") / 100f)
                    .coerceIn(0.5f, 1.5f) * TRANSLATION_SCALE,
                // ★ 2026-09-22（用户第 5 条）：词岛尺寸与字号的耦合方式改为**两档**。
                //   ⚠️ 两个键都在 `layout.` 前缀下 —— 这正是本函数被调用的判据
                //   （`installSettingsSubscription` 按 `layout.` / `main_lyric.font.` 前缀决定
                //     "要不要重算几何"）。落在别的前缀下 = **改了没反应**（静默）。
                followFont = boolOf(fresh, "layout.island.height.follow_font"),
                heightRatioPercent = intOf(fresh, "layout.island.height.ratio_percent"),
            )
        }
        // ★★★ 2026-10-04：**右端插槽的词岛加宽**（放在高度算完之后 —— 见上面那处说明）。
        //   ★ `RADIAL` 档下插槽宽 = 词岛高 ⇒ 必须先有 `baseHeightPx`。
        //   ⚠️ `rightSlotOccupied` 是**唯一判据**（内含迁移：`anchor == RIGHT` ⇒ `SPECTRUM`）
        //     ⇒ 与歌词让位、频谱绘制**共用同一个函数**，不可能各判各的。
        if (!coverShapeSkipsLyricChain(textOf(fresh, "shape.type"))) {
            val slotOccupantForWidth = dev.cdl.render.rightSlotOccupant(
                textOf(fresh, "island.right_slot.component"),
                textOf(fresh, "spectrum.anchor"),
            )
            // ★★★ 2026-10-04（复核后收敛）：**两笔增量合并成唯一出口** ——
            //   原先这里加两笔、而 `applyNaturalWidth` 只加一笔
            //   ⇒ **测量回填会把第二笔抹掉**。
            //   ⇒ 现在两个落点都调 `rightSlotTotalExtraWidthPx`，**结构上不可能只加一笔**。
            baseWidthPx += dev.cdl.render.rightSlotTotalExtraWidthPx(
                slotOccupantForWidth,
                baseHeightPx.toFloat(),
                // ★ 左让位走**唯一口径**（收起态）—— 与 `applyNaturalWidth` 同源。
                collapsedCoverInsetRel(LEADING_INSET, LEADING_GAP),
            )

        }
        applyWindowSize()
         // ★★ 2026-10-03（⑱ 时钟 · ⑤c）：**设置变更后重新武装秒级唤醒** ——
         //   否则把插槽切到「时钟」之后**定时器不会被建** ⇒ 时钟画出来就冻住
         //   （用户眼里 = "时钟不走 / 冒号不闪"）。
         //   ★ 切走时本调用会**撤掉**定时器（`rescheduleClockWake` 先 stop 再判）⇒ 零残留。
         rescheduleClockWake()
    }

    /**
     * 按**实测自然宽度**重排窗口（仅 `layout.fixed_width = false` 时生效）。
     *
     * ## 为什么单独一个方法（而不是并进 [applyGeometry]）
     *
     * 两者的**触发源不同**：`applyGeometry` 由**设置变更**驱动（用户拖滑块），
     * 本方法由**内容变化**驱动（每帧测量、超阈值才调）。
     * 合并会让"什么时候会重排窗口"变得不可推理 —— 而窗口重排是用户可见的动作。
     *
     * 且本方法**必须**从 `invokeLater` 里调（见测量处的注释：在 paint 里直接改尺寸会重入）。
     *
     * ## 幂等
     *
     * 目标宽度与当前相同就**什么都不做** —— 这是"每帧测量"能安全存在的前提。
     *
     * ## 为什么这里要再读一次设置
     *
     * 从测量到 `invokeLater` 真正执行之间，用户可能已经改了 `fixed_width` 或
     * `max_width`。读**当前**值（`runtime.live`）而不是测量那一刻的旧值，
     * 避免"刚切到固定宽度又被一次迟到的收缩改回去"。
     */
    private fun applyNaturalWidth() {
        val runtime = settings ?: return
        val snapshot = runtime.live
        // `fixed_width = true` → 宽度由 [applyGeometry] 固定为 `max_width`，这里不该插手。
        if (boolOf(snapshot, "layout.fixed_width")) return
        // ★★ 2026-10-01（封面态返工 · R3）：本态的宽度由 [applyGeometry] 按"小岛宽"固定 ——
        //   测量回填只服务于"歌词文本宽"，而本态**没有歌词槽** ⇒ 若放它进来，
        //   会把 `baseWidthPx` 改成 `naturalWidthPx.coerceIn(MIN_ISLAND_WIDTH, …)`(=280)
        //   ⇒ 收起态又变回全宽（正是 R3 的成因）。⇒ 本态直接返回。
        if (coverShapeSkipsLyricChain(textOf(snapshot, "shape.type"))) return
        val maxWidth = effectiveMaxWidth(intOf(snapshot, "layout.max_width"))
        // ★★ 2026-10-03（⑱ 时钟 · 右端插槽）：测量回填**也要带上"右端插槽多占的宽"** ——
        //   否则本条一跑就把 [applyGeometry] 加的那 21 px **抹掉**（词岛缩回去 ⇒ 歌词区被压窄）。
        //   ⚠️ 这是本项**最容易漏的一处**：`naturalWidthPx` 只含"歌词文本宽 + 左侧让位"，
        //      **不含右侧插槽** ⇒ 必须在这里补上。
        // ★★★ 2026-10-04：改走 `rightSlotOccupant`（与 `applyGeometry` 同一个函数），
        //   并把**词岛高**传进去 —— `RADIAL` 档下插槽宽 = 词岛高（用户拍板「自适应」）。
        //   ⚠️ 用 `baseHeightPx`（**属性**，`applyGeometry` 已算过）—— 本函数在其之后跑。
        // ★★★ 2026-10-04（复核后收敛）：**改用总增量**（两笔合一处）——
        //   原先这里只有第一笔 ⇒ **测量回填会把「两侧对称化」那笔抹掉**（= 歌词又偏左）。
        val slotExtra = dev.cdl.render.rightSlotTotalExtraWidthPx(
            dev.cdl.render.rightSlotOccupant(
                textOf(snapshot, "island.right_slot.component"),
                textOf(snapshot, "spectrum.anchor"),
            ),
            baseHeightPx.toFloat(),
            // ★ 左让位走**唯一口径**（收起态）—— 与 `applyGeometry` 同源。
            collapsedCoverInsetRel(LEADING_INSET, LEADING_GAP),
        )
        val target = naturalWidthPx.coerceIn(MIN_ISLAND_WIDTH, maxWidth) + slotExtra
        if (target == baseWidthPx) return
        baseWidthPx = target
        applyWindowSize()
    }

    /**
     * 按当前设置把窗口摆到**展开尺寸**。**必须在 EDT 上调用。**
     *
     * ## 为什么要改窗口尺寸（而不是把控件条画在歌词上）
     *
     * 词岛高度只有「歌词块 + 上下留白」—— 默认字号下与控件条的 76 px 是**同一量级**，
     * 控件条要 76 px —— 覆盖上去等于把"下一句歌词"整条挡掉，而下一句正是用户最想看的。
     * 加高则对歌词**零影响**：歌词区仍按 [baseHeightPx] 排，只是整体平移到
     * 控件条的另一侧（见 `PlaybackControls.layout` 的 `lyricsTop`）。
     *
     * ## ★★ 2026-09-22：窗口**恒为展开尺寸**，与悬停态无关（第三次修「上下闪动」）
     *
     * 前两次的修法都只治了一半（见 `IslandPolicy.windowTargetBounds` 的 KDoc）：
     * 一次把两次 OS 调用合成一次 `setBounds`，一次让**岛体形状**连续变化 ——
     * 而**窗口矩形本身仍在悬停那一帧跳变**。于是那一帧里"旧内容出现在新位置"= 闪。
     *
     * 现判据只有一句：**悬停路径不碰窗口几何**。落地为三条：
     *   · 目标尺寸恒为 `base + 带高`（算术在 [windowTargetBounds]，入参里**没有**悬停量）；
     *   · 于是悬停期间目标恒等于当前 ⇒ `setBounds` **不可达**（本方法一处都不做）；
     *   · 岛体形状由 `bandGeometry` 连续驱动 ⇒ 收起时那条预留高度**落在形状之外**，
     *     观感与改动前一致。
     *
     * ## 为什么"加高"不会引起抖动（这是 `recon/13` §12.2 V8 的疑问）
     *
     * V8 担心的是：展开会改变词岛尺寸，而尺寸变化又可能让鼠标脱离命中区 →
     * 收起 → 尺寸变回 → 又命中 → 抖动。这条环在本实现里**不存在**：
     * 悬停**根本不改窗口尺寸**（上面那条），而"维持展开"的判据是
     * **鼠标在岛体矩形内**（见 [insideIsland] 与 [HoverState] 的类 KDoc），
     * 且展开只会让岛体**变大** —— 鼠标不可能因为"变大"而跑到岛外，故无自我否定。
     *
     * ⚠️ 判据从"窗口矩形"改成"**岛体矩形**"是本次修法**必须**的连带改动：
     *    窗口恒为展开尺寸后多出一条**永久透明**的预留带，用窗口矩形判会让
     *    `inside` 恒为 `true` ⇒ **控件永远收不回去**。见 [insideIsland]。
     *
     * 位置：`BOTTOM` 档窗口 y 就是用户摆的位置（多出的带高在**下方**）；
     * `TOP` 档窗口 y 比用户摆的位置**高一条带高**（多出的那段在**上方**，
     * 否则控件条没地方画）。偏移的算术在 [windowYOffsetPx]。
     */
    // ── ★ 诊断计数（BUG-123：控件带动画期间改窗口 bounds）─────────────────────
    //
    // 用户原话：「上方控件的弹出收回动画时间内**词岛的上下闪动依旧**，能不能**彻底根治**它？」
    //
    // 这组计数器把"闪动"变成**可数的判据**：整段控件带动画期间 `setBounds` 调用次数
    // 必须为 **0**（稳态两个端点 0 与 1 不算"动画中"）。真机上 grep 日志里的
    // `控件带动画期间改窗口 bounds` 即可取证 —— 这是判据，不是感觉。
    //
    // ⚠️ 2026-09-22 补：这两个字段的**声明**在 2026-09-21 那轮被漏加（只写了用法），
    //    导致整棵树从 09-22 00:39 起编译不过（`Unresolved reference 'boundsSetCount'`）。
    //    计数只用于诊断，不影响绘制路径。
    private var boundsSetCount = 0
    private var boundsSetDuringBandAnimCount = 0

    private fun applyWindowSize() {
        if (baseWidthPx <= 0 || baseHeightPx <= 0) return
        val cfg = controlsConfig()
        // ★★ 全部算术在 [windowTargetBounds]（公开纯函数，探针可断言）——
        //    它**没有**任何悬停量入参，这就是"悬停全程 `setBounds` 0 次"的结构性保证。
        //    ⚠️ 量化**一次**（`roundToInt`，不是截断）也在那里 —— `stripHeight` 是 `Float`
        //       （含 `controls.gapPx` 这个滑杆量），而窗口尺寸是 `Int`；绘制侧
        //       （`bandGeometry`）用同一个量化值算岛体高度，两者才可能逐像素对齐。
        //       取 `roundToInt` 而不是截断：截断会让 `0.9px` 的变化整整晚一帧才出现。
        val target = windowTargetBounds(
            oldX = window.x,
            oldY = window.y,
            oldWidth = window.width,
            currentYOffsetPx = windowYShiftPx,
            baseWidthPx = baseWidthPx,
            baseHeightPx = baseHeightPx,
            stripHeightPx = controls.stripHeight(cfg),
            stripAtTop = cfg.position == ControlsConfig.TOP,
            // ★★ 2026-10-01（封面态 R4）：本态生长量 = 完整词岛宽 − 小岛宽（倍数级）。
            //   非本态返回 `-1` ⇒ 走既有"带高"增量，四型逐像素不变。
            widthGrowOverridePx = widthGrowOverridePx(),
        )
        // 记账**无条件**更新：它是"当前 window.y 相对逻辑位置的偏移"，
        // 与"本次要不要 setBounds"无关（两者不同步的表现是"下次改设置跳一条带高"）。
        windowYShiftPx = target.yOffsetPx

        // ── 宽度变化时**以中心为锚**（用户 2026-09-17 明确要求）─────────────────
        //
        // ## 原记：直接 `setSize`
        //
        // 那会**保持左上角不动** —— 于是宽度变化时词岛只向**右侧**伸缩（左边钉死）。
        // 用户原话：「歌词框应以**两边向中间**的方式伸缩（变长或变短），
        //   **而非当前的伸缩方式**」。
        //
        // ## 为什么这个缺陷现在才明显
        //
        // 本版刚实现了 `layout.fixed_width = false` 时**按歌词长度收缩** ——
        // 宽度**每句都会变**。在那之前宽度恒定，单侧伸缩根本看不出来。
        // **一个旧的小瑕疵，被一个新特性放大成了主要观感问题。**
        //
        // ## 现做法
        //
        // 算术在 [centeredWindowX]（公开纯函数，可被探针断言）：先记住**旧的横向中心**，
        // 再把窗口挪回"以该中心对齐"，于是左右两侧**同时**伸缩。
        //
        // ⚠️ **只在宽度真的变了时才挪位置** —— 否则每次高度/位置变化词岛都会左右跳一下。
        //
        // ── 高度/位置变化时**以歌词的屏幕位置为锚**（用户 2026-09-17 明确要求）──────
        //
        // ## 用户原话
        //
        // > 「在设置控件为**上方**时，光标移到弹出控件判定范围，**移动的是整个歌词行和
        // >   背景频谱**，而不是设置控件位置为**下方**时整个歌词行和背景频谱位置不变的
        // >   情形，**修复它**。」
        //
        // ## 原记与实为
        //
        // 原记：`setSize` 只改宽高、不动 `y` —— 于是"窗口向下生长"，看起来没问题。
        //
        // 实为：**只对 `position = BOTTOM` 成立。**
        //   `layout.lyricsTop` 在 `TOP` 时 = 控件条高度（歌词被推下去让出顶部那段），
        //   而窗口的**顶边没动** → 歌词在**屏幕上**真的下移了一条的高度。
        //   而 `BOTTOM` 时 `lyricsTop = 0`、窗口向下生长 → 歌词屏幕位置不变。
        //   **同一个机制，两种位置两种观感** —— 这就是用户说的"而不是……的情形"。
        //
        // ## 修法：`TOP` 时窗口顶边比逻辑位置**高一条带高**
        //
        // 算术在 [anchoredWindowY]（公开纯函数，可被探针断言）—— 它把
        // "控件条占据的那一段落在歌词的**另一侧**"这条用户诉求钉成可验证的定义点。
        //   · `BOTTOM` → `newY = 逻辑位置`（多出的带高在下方，歌词屏幕位置本来就不动）；
        //   · `TOP`    → `newY = 逻辑位置 − 带高`（顶边上移，新长出的那段正好放控件条，
        //     而歌词相对窗口顶下移了同样多 → **屏幕位置完全不变**）。
        //
        // ⚠️ 判据用 **`cfg.position`（用户设置）而不是 `hover.expanded`** ——
        //   收起时 `hover.expanded` 已是 `false`，用它会让"收起"这一半不还原 `y`，
        //   词岛会**一条一条往上爬**。
        //
        // ⚠️ 这引入了一个新耦合：**`window.y` 不再等于"用户摆的位置"**（`TOP` 档下）。
        //   故 [persistPosition] 必须把偏移加回去、[restoreSavedPosition] 必须减掉，
        //   否则展开态拖动会存下偏移后的坐标、启动时词岛会整体下移一条带高。
        //   两处都走 [windowYShiftPx] 这一个记账点（见它的 KDoc）。
        //
        // ── ⚠️ 2026-09-18 四次修订：**本函数一处位置裁剪都不做** ────────────────
        //
        // ## 用户原话
        //
        // > 「词岛拖至**超出屏幕边框会被挤回屏幕内**，虽然我们希望词岛不会乱跑，
        // >   但**用户造成的操作还是不要阻拦**吧？」
        //
        // ## 三轮来回的教训（这才是根因所在）
        //
        // | 轮次 | 语义 | 结果 |
        // | --- | --- | --- |
        // | 一 | **无裁剪** | "启动后几秒词岛完全不可见"（宽度收缩以出屏中心为锚） |
        // | 二 | "完全可见" | "松手后被挤回屏幕内 + 向上平移" |
        // | 三 | "至少 48px 可见" | **仍然被挤回**（用户本次投诉） |
        //
        // **根因不是"裁剪多严"，而是"这里根本就不该裁剪"。**
        // 三轮都在调**阈值**，而真正的问题是**判据的存在性**：
        // 本函数是**派生几何**（宽度/高度变化的副产物），职责只是"把尺寸变化
        // 换算成位置"，**没有资格对位置做任何裁决**。用户摆在哪就是哪 ——
        // 哪怕摆到屏幕外（他可能就是想让它半藏在边缘，或者要拖到第二块屏）。
        //
        // ## 那"第一轮那个缺陷"靠什么防？
        //
        // 靠 [restoreSavedPosition] —— 它管的是**启动时读到的持久化值**，
        // 那个值可能来自别的显示器 / 别的 DPI，**必须校验**（中心不在屏内就居中）。
        // 两者真正的区别是**值的来源**：
        //   · 启动：来自磁盘，可能陈旧 / 异机 → **校验**
        //   · 运行：来自用户**此刻的手** → **不干预**
        //
        // **这条区分就是三轮修改都没抓住的东西。**
        //
        // ## 跑丢了怎么办
        //
        // 已有逃生门：「找回词岛」→ [resetToPrimaryScreen]（清空 `window.position`
        // 并居中到主屏）。故"不阻拦"不会造成不可逆困境。
        val unchanged = window.x == target.x && window.y == target.y &&
            window.width == target.width && window.height == target.height
        if (unchanged) {
            // ★ 悬停路径**落到这里**（目标恒等于当前）⇒ 连 `validate` / `paintImmediately`
            //   都不做。这正是"悬停期间一次窗口操作都不发生"的直接体现。
            return
        }

        // ── ⚠️ 2026-09-19：**用一次 `setBounds` 取代 `setLocation` + `setSize`** ──────
        //
        // 用户原话：「播放控件设置**在上方**时，鼠标命中词岛时，词岛会在**极短时间内
        // **上下闪现**。」
        //
        // ## 为什么"两次调用"必然闪 —— 无论哪个在前
        //
        // `setLocation` 与 `setSize` 是**两次独立的 OS 调用**，之间必然有一帧处于
        // "只改了一半"的状态。控件条在**上方**时 `growth > 0`、`newY = oldY - growth`：
        //
        // | 顺序 | 中间那一帧 | 用户看到 |
        // | --- | --- | --- |
        // | 先 `setLocation` | 窗口**上移了**、高度还是旧的 → **底边也跟着上去** | 内容整体**上跳**，下方露一条空 |
        // | 先 `setSize`（改动前） | 窗口**向下长**、位置未变 → 底边压到任务栏 | 内容整体**下跳**，压住任务栏 |
        //
        // **两种顺序都错** —— 因为"上移 + 长高"这件事**本身**要求位置与尺寸**同时**生效。
        // 上一轮我只把顺序从"先 size"改成"先 location"，那只换了**闪的方向**，
        // 没有消除闪 —— 这正是"调参数而不是改判据"的又一次翻版。
        //
        // ## 修法：一次 `setBounds`
        //
        // `java.awt.Window.setBounds(x, y, w, h)` 最终落到**一次** `peer.setBounds`
        // （`Component.reshape` → `peer.setBounds(x, y, w, h, SET_BOUNDS)`）——
        // 位置与尺寸在**同一次** `SetWindowPos` 里生效，**中间帧不存在**。
        //
        // ⚠️ `window.setSize(baseWidthPx, target)` 那句**必须删掉** ——
        //    留着它会把 `setBounds` 刚设好的位置/尺寸再覆盖一次（回到两次调用）。
        window.setBounds(target.x, target.y, target.width, target.height)
        // ── ★ 判据（BUG-123）─────────────────────────────────────────────
        // "整段控件带动画期间 `setBounds` 调用次数" 必须为 **0** ——
        // 这是"上下闪动"被根治的**可数判据**，不是感觉。
        // 稳态两个端点（0 与 1）不算"动画中"；只有中间态才是。
        // 真机上 grep 这一行即可取证：`控件带动画期间改窗口 bounds`。
        // ⚠️ 2026-09-22 起：悬停路径**根本走不到这里**（目标恒等于当前），
        //    故这条计数在稳态下应当**永远为 0**；一旦打印出来就是回归。
        boundsSetCount++
        val bandAnimating = bandFractionNow > 0f && bandFractionNow < 1f
        if (bandAnimating) {
            boundsSetDuringBandAnimCount++
            System.err.println(
                "[CDL] ⚠️ 控件带动画期间改窗口 bounds（应为 0 次）：" +
                    "band=$bandFractionNow expanded=${hover.expanded} " +
                    "collapsing=$bandCollapseRunning " +
                    "bounds=(${target.x},${target.y},${target.width},${target.height}) " +
                    "累计=$boundsSetDuringBandAnimCount/${boundsSetCount}"
            )
        }
        // ⚠️ `setSize` 只是把容器标记为"需要重新布局"，真正的布局要等到下一轮
        // EDT 的 validate 才发生。而**紧接着的代码**（`updateHoveredControl` 与
        // `consumeControlPress`）就要用 `panel.width/height` 去算控件几何 ——
        // 不在这里同步 validate，它们会拿到**旧高度**，表现为"展开后的第一次
        // 悬停/点击按收起态的几何判定"（控件画出来了却点不中）。
        // 本方法只在 EDT 上被调用（悬停状态变化、设置变更、启动），故 validate 合法。
        runCatching { window.validate() }
        // ── ⚠️ `setSize` 之后必须**同步**重绘（2026-09-19 修「第二词岛闪现」）──────
        //
        // 用户原话（答复"第二词岛闪现只在贴任务栏时还是任何位置"）：
        // > 「**任何**」
        //
        // 即**与任务栏无关** → 排除"Z 序/任务栏反击"那条线，剩下的是**绘制时序**：
        //
        // `setSize` 只是把窗口标记为"需要重新布局"，真正的内容要等**下一轮 EDT**
        // 的 `validate` + `repaint` 才画出来。而窗口本身**已经变大了** ——
        // 于是有若干帧里，**新的那块区域显示的是窗口变大的旧内容/背景**，
        // 而旧的（较小的）内容还在原处 → 看起来就是"**出现了第二个词岛**"。
        //
        // `repaint()` 是**异步**的（投到 RepaintManager 队列），救不了这个窗口期。
        // `paintImmediately` 是**同步**的：它立刻走完"布局 → 绘制"，把那几帧消掉。
        //
        // ⚠️ 顺序：必须在 `validate()` **之后** —— 否则面板尺寸还是旧的，
        //    立刻绘制等于把旧尺寸的内容画到新窗口里，反而更糟。
        runCatching { panel.paintImmediately(0, 0, panel.width, panel.height) }
    }

    //
     // 恢复上次保存的位置（`window.position`）。
     //
     // 值的格式是 `"x,y"`。空字符串表示**从未拖动过** —— 这是注册表里定的哨兵语义
     // （见 `SettingRegistry` 中 `window.position` 的默认值注释），
     // 此时居中显示。
     //
     // 解析失败（用户手改配置成乱码）**不报错**，退化为居中 —— 与"配置坏了不能崩"一致。
     // 解析出的坐标还会做一次**可见性校验**：显示器可能被拔掉（多屏→单屏），
     // 旧坐标会落在不存在的区域，用户会以为"窗口丢了"。故裁剪到主屏范围内。
     //
    /**
     * 恢复上次保存的位置（`window.position`）。
     *
     * ## 三种情形（2026-09-18 重写，用户要求）
     *
     * | 情形 | 处置 |
     * |---|---|
     * | **从未拖动过**（值为空串） | **居中**（`setLocationRelativeTo(null)`） |
     * | 有位置且**中心落在屏幕内** | **用它**（并裁剪到"完全可见"） |
     * | 有位置但**中心在屏幕外** | **居中** —— 视为无效 |
     *
     * ## 原记与实为（这是本轮用户报的"仍处于屏幕边缘"）
     *
     * **原记**：解析出的坐标无条件 `coerceIn(bounds.x - width + MIN_VISIBLE, …)` ——
     * 语义是"**至少** `MIN_VISIBLE`(48px) 可见"。
     *
     * **实为**：那条裁剪的**结果是"贴边"** —— 一个中心在屏幕外 1000px 的旧位置，
     * 会被裁到"右边缘留 48px 可见"，用户看到词岛**贴在屏幕最右边**，
     * 而他要的是"**回到屏幕中间**"。**裁剪保证了"看得见"，但没保证"用得上"。**
     *
     * 用户原话：
     * > 「插件启动后词岛可见了，但**仍处于屏幕边缘**，你认真修复这个问题，
     * > **避免在其他规格的设备上造成同样问题**。（推荐在**首次启动时词岛固定出现在
     * > 屏幕中间**，后续启动以**上一次使用时位置**为启动位置）」
     *
     * ## 为什么判据取"**中心在屏幕内**"而不是"可见比例 ≥ x%"
     *
     * 中心是用户对"窗口在哪"的**直觉锚点**：中心在屏内 → 用户认为"它在屏幕上"；
     * 中心在屏外 → 用户认为"它跑掉了"，哪怕还露着 200px 边。
     * 取中心还与"宽度变化以中心为锚"（[centeredWindowX]）**同源** ——
     * 两处用同一个锚点，就不会出现"位置判定说它在屏内、伸缩时却按屏外中心算"的错配。
     *
     * ## 为什么"有位置就用、越界才居中"，而不是"每次都居中"
     *
     * 用户明确要"**后续启动以上一次位置为准**" —— 那是他摆好的位置，必须尊重。
     * 只有**明显不可用**（中心都跑出屏幕）时才回退居中。
     * 这也是"**绝不制造用户无法自救的状态**"这条纪律的体现：居中总是可见的。
     *
     * 解析失败（用户手改配置成乱码）**不报错**，同样退化为居中 ——
     * 与"配置坏了不能崩"一致。
     */
    private fun restoreSavedPosition(fresh: SettingsSnapshot) {
        val raw = textOf(fresh, "window.position").trim()
        val parsed = parsePosition(raw)
        if (parsed == null) {
            window.setLocationRelativeTo(null)
            // ⚠️ 2026-09-22：窗口恒为展开尺寸 ⇒ `TOP` 档下窗口顶边比"岛体该在的位置"
            //    高一条带高。**居中保持"窗口居中"不变**（与改动前一致：改动前居中时
            //    窗口也已经是展开尺寸），只把偏移**记账**下来，让逻辑位置由它派生。
            syncWindowYShift()
            return
        }
        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .maximumWindowBounds
        // ── 有效性判定：**窗口中心必须落在主屏内**（见 KDoc 的取舍说明）──
        val centerX = parsed.x + window.width / 2
        val centerY = parsed.y + window.height / 2
        val centerInside = centerX in bounds.x until (bounds.x + bounds.width) &&
            centerY in bounds.y until (bounds.y + bounds.height)
        if (!centerInside) {
            // ⚠️ 这条分支**必须打日志** —— 否则用户看到"位置没生效"会以为设置坏了，
            //    而实际是"旧位置被判为不可用"。静默回退比报错更难排查（项目纪律）。
            System.err.println(
                "[CDL] 保存的位置不可用（中心 $centerX,$centerY 在屏幕 " +
                    "${bounds.x},${bounds.y},${bounds.width},${bounds.height} 之外）→ 改为居中显示"
            )
            window.setLocationRelativeTo(null)
            syncWindowYShift()
            return
        }
        // 中心在屏内 → 用它，但**裁剪到完全可见**（窗口比屏幕大时退化为贴左/上）。
        val x = parsed.x.coerceIn(bounds.x, (bounds.x + bounds.width - window.width).coerceAtLeast(bounds.x))
        val y = parsed.y.coerceIn(bounds.y, (bounds.y + bounds.height - window.height).coerceAtLeast(bounds.y))
        // ★ 存下来的是**逻辑位置**（用户摆的那个位置），而窗口 y 比它高 [windowYShiftPx]
        //   （`TOP` 档）—— 故这里要**减掉**偏移再摆窗口。改动前展开态的窗口 y 也是
        //   "逻辑位置 − 带高"（`anchoredWindowY`），故 `TOP` 档下 `y - 带高` 与
        //   改动前的**展开态**窗口位置逐像素相同；`BOTTOM` 档偏移为 0，逐像素不变。
        val cfg = controlsConfig()
        val shift = windowYOffsetPx(controls.stripHeight(cfg), cfg.position == ControlsConfig.TOP)
        window.setLocation(x, y - shift)
        windowYShiftPx = shift
    }

    /** 解析 `"x,y"`；非法返回 `null`。 */
    private fun parsePosition(raw: String): Point? {
        if (raw.isEmpty()) return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        return Point(x, y)
    }

    /**
     * **找回词岛**：清空保存的位置、**把「岛体」居中**、**临时置顶**，并确保窗口可见。
     *
     * ★ 2026-09-30（诉求 ㉚）：后两项是本轮补齐的 —— 见下方「补齐两块」。
     *
     * ## 为什么需要这个方法（它解决的是"不可逆的困境"）
     *
     * 词岛是**无边框 + 可选鼠标穿透 + 不进 Alt+Tab** 的悬浮件。一旦用户把它拖到
     * 屏幕外（或多屏改单屏后旧坐标落在不存在的区域），就**没有任何鼠标操作能找回它** ——
     * 这属于"功能把用户锁死"的缺陷，不是体验瑕疵。
     *
     * ## 为什么必须**清空设置**而不只是 `setLocation`
     *
     * 只调 `setLocation` 的话，`window.position` 里仍是那个屏幕外坐标；
     * **下次启动**会再次把窗口定位到屏幕外 —— 用户每次开宿主都要点一次"找回"。
     * 故这里把设置项清成空串（注册表定义的"从未拖动过"哨兵），
     * 让居中成为**持久**状态。
     *
     * ## 为什么走 `publish` + `apply` 而不是只 `apply`
     *
     * 与本项目其它写点一致：`publish` 让订阅者（渲染层）立刻看到新值，
     * `apply` 才落盘。两者连做是因为这里没有"拖动中"的中间态 —— 一次点击就是一个完整决策。
     *
     * ---
     *
     * ## ★★ 2026-09-30（诉求 ㉚）补齐两块 —— 用户的完整原话
     *
     * > 「**找回词岛应该使词岛立即回到屏幕正中央（注意逻辑/物理位置）并置顶
     * >   （不打开设置项，只是维持置顶状态直到被操作）**」
     *
     * 补齐前的现状（`未完成工作总账.md` §3.8 实测）：本方法做 5 件事，
     * 但**缺两块** ——（a）**没有把「岛体」居中**、（b）**全程没有任何提顶调用**。
     *
     * ### (a) 居中对象从「窗口」改成「岛体」
     *
     * 原实现是 `window.setLocationRelativeTo(null)` ⇒ 居中的是**窗口**。
     * 而词岛有**三层**（`§3.9` 已单列归档术语）：**窗口 ⊃ 面板 ≳ 岛体**，
     * 差量是**永久透明的预留区**（控件带 + 横向让位）。
     *
     * ```
     * stripAtTop = true 时（bandGeometry 逐行读出）：
     *   岛体高 = base + stripQ·f     ⇒ f = 1 才等于窗口高
     *   岛体顶 = (stripQ + base) − 岛体高
     *   ⇒ f = 0（收起态）岛体顶 = stripQ ⇒ 窗口居中 ⇒ ★ 岛体整体偏下 stripQ/2
     * ```
     *
     * 真机 `stripQ ≈ 76` ⇒ **偏下 ≈ 38 px**，正是用户看到的"没在正中央"。
     * 现改为按 [centeredWindowLocationForIsland]（**岛体外接矩形中心** == 屏心，`§3.8` 定案 B）。
     *
     * ⚠️ **不可用回退**：岛体宽度尚不可信（首帧前 `baseWidthPx <= 0`）时纯函数返回
     * `null` ⇒ 退回 `setLocationRelativeTo(null)`（= 改动前的行为）。
     * ★ **绝不猜一个位置** —— 那会造出"看起来居中了、实际偏了"的静默错。
     *   这是刻意的：宁可退化到**旧行为**（至少可复现、可归因），也不引入一个不可断言的新状态。
     *
     * ### (b) 置顶：**复用 F-4′ 那套临时 topmost，不另写一套**
     *
     * 用户要求"置顶，但**不打开设置项**" ⇒ 语义**正是** `dragTopRaised` 那条路径：
     * 临时 topmost，**不改** `island.always_on_top`（`WindowInterop.raiseToTop`
     * 是 `SetWindowPos(HWND_TOPMOST)`，只动 Z 序、不写设置）。
     *
     * ⚠️ **为什么不另写一套**：本项目明令禁止"同一语义两个实现点"，且台账 §3.8 §4
     * 逐字写着「**两处共用一个解法**：**临时 topmost**」。
     * 故这里**只置位 [dragTopRaised] + 调 [raiseToTopForDrag]**，
     * 释放完全交给既有的 [maintainTempTopmost]。
     *
     * ### ★★ (b′) 释放判据：2026-10-01 01:2x 用户纠正 —— **前台判据（F-4′）**
     *
     * > 「我说的是**点击词岛外部**就释放，**不是点击词岛**」（用户逐字）
     *
     * ⇒ 现实现 = **前台窗口是否属于本进程**（[shouldHoldTempTopmost]，探针逐格断言）：
     *
     * | 用户动作 | 前台 | 结果 |
     * |---|---|---|
     * | 点**词岛** | 本进程 | ★ **保持**（他正在操作它 ⇒ 该看得见） |
     * | 点**别的应用 / 桌面 / 任务栏** | 别的进程 | ★ **释放**，降回他的置顶设置 |
     *
     * ★ 本方法**只置位 [dragTopRaised] + 提顶**；释放完全交给既有的
     * [maintainTempTopmost]（看门狗每 400 ms 判一次）。**不需要第二个状态位。**
     *
     * #### ★★ 一次"我理解反了"的完整记录（三轮翻覆，值得留）
     *
     * | 轮次 | 我实现的 | 用户反馈 |
     * |---|---|---|
     * | ㉚ 首版 | F-4′ 前台判据（点词岛 ⇒ 保持） | 我在 `migration/55` §5 把它**如实标为"与用户原话的字面差异"** |
     * | ㉚ 追加 | 改成"**点词岛 ⇒ 释放**"（把释放入口放进 `mousePressed`） | 「**你完全理解反了。我说的是点击词岛外部就释放，不是点击词岛**」 |
     * | 本轮 | ★ **回退到前台判据**（= ㉚ 首版，即 F-4′） | —— |
     *
     * ★★ **根因（比"我粗心"更具体）**：用户原话「**必须是"点了才释放"**」
     * **没有说"点哪儿"**，而"点"的**最自然的宾语就是词岛本身** —— 我按这个默认读了，
     * 恰好与用户意图相反。⇒ **教训：歧义措辞要问"落点"，不要按最自然的读法默认。**
     *
     * ★★ **第二个教训（关于"如实标出差异"这件事）**：我上一轮确实**如实记录了差异**
     * —— 这是对的做法，也正是它让用户发现了问题。**但"记录差异"不能替代"确认差异"**：
     * 我在文档里写了"两者不等价"，却没**停下来问一句**，而是继续按自己的判断往下做，
     * 还给了三条理由为它辩护。⇒ **发现与用户原话有实质分歧时，先问，别先辩护。**
     *
     * ⚠️ **已知且接受的后果**：点 SPW 自己的主界面 / 设置窗口**不算"外部"**
     * （同进程 ⇒ 保持）；别的程序自己抢走前台（用户没点）也会释放 ——
     * Windows 前台锁使后者罕见，用户已确认接受（见 `migration/57` §2）。
     */
    fun resetToPrimaryScreen() = onEdt {
        if (!started) return@onEdt
        runCatching {
            val delta = mapOf("window.position" to "")
            settings?.publish(delta)
            settings?.apply(delta)
        }.onFailure {
            // 清空设置失败不该阻止"把窗口拉回来" —— 那是用户最迫切的需求。
            System.err.println("[CDL] 找回词岛：清空位置设置失败（仍会居中显示）：$it")
        }

        // ── (a) 把**岛体**居中（㉚ · S-A / 定案 B）───────────────────────────
        //
        // 屏幕 bounds 走 `maximumWindowBounds`（**AWT 逻辑坐标**，已排除任务栏）——
        // ★ S-C 坐标纪律：全程逻辑，**不出现 `GetWindowRect`**
        //   （`tools/check_coord_hygiene.py` 是本条的守卫，它的滑窗会把
        //   `GetWindowRect` 与 `setLocation` 判为"同一函数体内混用物理/逻辑"）。
        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val target = centeredWindowLocationForIsland(
            screenX = bounds.x,
            screenY = bounds.y,
            screenW = bounds.width,
            screenH = bounds.height,
            windowW = window.width,
            windowH = window.height,
            bg = bandGeometryNow(),
        )
        if (target != null) {
            window.setLocation(target.x, target.y)
        } else {
            // 退化：岛体几何尚不可信 ⇒ 回到改动前的"窗口居中"。
            // ⚠️ 这条分支**必须留痕**（与 `restoreSavedPosition` 的"位置不可用"同一条纪律：
            //    静默回退比报错更难排查 —— 用户会以为"找回没用"，而实际是"几何还没算出来"）。
            System.err.println(
                "[CDL] 找回词岛：岛体几何尚不可信（islandWidth <= 0）⇒ 退化为窗口居中"
            )
            window.setLocationRelativeTo(null)
        }
        // ⚠️ 2026-09-22：本方法**绕过 `applyWindowSize` 直接摆窗口**，
        //    故必须同步"窗口 y ↔ 逻辑位置"的偏移记账（否则下次改设置会跳一条带高）。
        syncWindowYShift()

        // 若用户此前用设置把词岛关掉了，找回时一并打开 —— 否则"找回了但看不见"。
        if (!window.isVisible) window.isVisible = true

        // ── (b) 临时置顶（㉚ · S-B）—— **复用 F-4′ 原语，不写第二套** ──────────
        //
        // 顺序要求：必须在 `isVisible = true` **之后**。
        // `raiseToTopForDrag()` 有 `if (!window.isVisible) return` 这条守卫
        // （见 [raiseToTopNow] 的门控表：把不可见窗口提顶会打乱前台归属）——
        // 放在前面会让"找回了但没置顶"，且**不报错**。
        //
        // ★★ **2026-10-01 01:2x（用户纠正）：释放判据 = 前台判据（F-4′）** ——
        //    「点击词岛**外部**才释放」。找回后只置 [dragTopRaised]，由
        //    [maintainTempTopmost] 判"前台还在不在本进程"：点词岛 ⇒ **保持**；
        //    点外面 ⇒ 看门狗 400 ms 内降回。**不需要第二个状态位**。
        if (!dragTopRaised) {
            dragTopRaised = true
            raiseToTopForDrag()
        } else {
            // 已经在临时提顶态（用户先拖过词岛、再点找回）⇒ **重申**一次即可。
            // 与 [maintainTempTopmost] 的"保持 ⇒ 重申"同一语义，也**不会**漏掉置顶。
            raiseToTopForDrag()
        }

        panel.repaint()
    }

    // ── 拖动 ─────────────────────────────────────────────────────────────

    /**
     * 安装拖动处理。
     *
     * ## 为什么不用 `window.point = ...`（Swing 的窗口拖动）而是自己算
     *
     * ① `addMouseMotionListener` + 手动 `setLocation` 的行为在各平台上一致，
     *    而 `Window` 自带的拖动依赖 native 装饰器 —— 我们 `undecorated`，不可用；
     * ② 自己算才能实现「锁定时完全不动」，以及「松手才写设置」这两条需求。
     *
     * ## 锁定位置（`island.lock_position`）
     *
     * 锁定时：按下**直接返回**，连起点都不记 —— 这样拖动中途把锁定打开，
     * 后续的 `mouseDragged` 会因为 `dragOrigin == null` 而自然失效，
     * 不需要额外处理"拖到一半被锁"的边界。
     *
     * ## 松手才写位置
     *
     * 拖动过程中**不写设置**（`publish` 会每像素触发一次整份快照替换与订阅广播，
     * 那是纯粹的浪费），只在 `mouseReleased` 时 `apply` 一次落盘。
     * 这与设置系统的两段式设计一致：拖动中高频的是 `publish`，
     * 但"位置"这项的中间态**对任何消费者都没意义**（只有最终位置重要），
     * 故连 `publish` 都省掉，直接 `apply`。
     */
    private fun installDragHandler() {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // 右键交给 JPopupMenu，不参与拖动。
                if (!SwingUtilities.isLeftMouseButton(e)) return

                // ── 按下即**提到最前**（2026-09-18 加，用户诉求）──────────────────
                //
                // ## 用户原话
                //
                // > 「词岛**取消置顶后**在不同页面中切换焦点，最后**切不回词岛**
                // >   （即鼠标已经能拖动词岛了，但词岛仍**被其他页面盖着**，没有成为焦点）」
                //
                // ## 为什么"能拖动"却"被盖着"
                //
                // 用户关掉「窗口置顶」后，词岛就是一个**普通窗口**，于是它与其他窗口
                // 同处一个 Z 序组。**鼠标能拖它，是因为鼠标事件走的是命中测试
                // （谁在光标下谁收事件），与 Z 序无关** —— 但"谁盖住谁"只看 Z 序。
                // 于是出现这个反直觉的现象：**能操作，但看不见**。
                //
                // 而 Windows 在点击时**不会**自动把非前台窗口提到最前（那需要
                // `SetForegroundWindow` / `SetWindowPos(HWND_TOP)`）——
                // 对一个无边框、不进任务栏的 `JWindow`，系统更不会替你提。
                //
                // ## 修法：按下时显式 `toFront()`
                //
                // `Window.toFront()` 在 Windows 上落到 `SetWindowPos(HWND_TOP)`，
                // 即"提到**非 topmost 组**的最前" —— 正好是用户要的语义，且
                // **不会**把窗口变成 topmost（那会推翻他的「取消置顶」）。
                //
                // 置顶开着时调它也安全：topmost 窗口调 `toFront()` 仍在 topmost 组内，
                // 不会掉出组外；且看门狗每 400 ms 还会重申一次。
                //
                // ⚠️ 放在"左键判定之后、控件判定之前" —— 点控件时也要提，
                //   否则"点一下播放键，词岛还是被盖着"（同一个缺陷的另一种触发方式）。
                window.toFront()
                // ── 2026-09-19：**非置顶时还要让它能成为焦点**（用户报的缺陷）───────
                //
                // 用户原话：「**取消词岛置顶后，焦点在词岛上时词岛无法置顶。**」
                //
                // `toFront()` 只保证"在**非 topmost 组**里排到最前"，但
                // **`JWindow` 默认不可聚焦**（`isFocusableWindowState` 的默认值对
                // `Window` 子类不保证为 true，且无边框窗口通常拿不到焦点）——
                // 于是"点一下"只提了 Z 序，**焦点仍在别的窗口**，
                // 用户看到的是"点了但它还是没在最前"（焦点窗口往往被系统重新提到最前）。
                //
                // 两句各治一半：
                //   · `isFocusableWindowState = true` —— 让它可以**被**聚焦；
                //   · `requestFocus()` —— 点下去时**真的**去拿焦点。
                //
                // ⚠️ 不设 `setAutoRequestFocus(true)`：那会让窗口**显示时**抢焦点
                //    （用户开插件时焦点从播放器被抢走）。只在**用户点击**这条路径上要焦点。
                runCatching {
                    window.setFocusableWindowState(true)
                    window.requestFocus()
                }

                // ── 2026-09-30（BUG-145 · F-4′）：**按下即临时提到 topmost** ─────────
                //
                // ## 为什么上面那两句不够（用户实测，见 BUG-145 的「追加取证」）
                //
                // `toFront()`（= `SetWindowPos(HWND_TOP)`）与 `requestFocus()`
                // **只在同进程内有效**。用户实测的"特征签名"：
                //   · 词岛 ↔ **SPW 窗口**：正常（同进程，不受前台锁约束）；
                //   · 词岛 ↔ **其他程序**：怎么操作都被盖（★ 前台锁拒绝非前台进程抢焦点）；
                //   · **打开置顶**：正常（topmost 组**不需要焦点**就能压住别人）。
                //
                // ⇒ 跨进程要能"被看见"，唯一的路是 **topmost**。
                //
                // ## 与 09-21 拖动提顶共用同一对原语（不新造概念）
                //
                // 复用 `raiseToTopForDrag()` + `restoreAlwaysOnTop()` + `dragTopRaised`
                // —— 语义从"拖动期"**扩展**为"焦点期"（F-4′，2026-09-30 09:xx）。
                //
                // ★★ **2026-09-30 16:3x 用户改判（诉求 ㉚）**：焦点期语义**整个退场**，
                //    改为「**点了才释放**」⇒ **本方法就是唯一的释放入口**（见下块）。
                //
                // ⚠️ 为什么在**控件判定之前**：点控件时也要提（否则"点一下播放键，
                //    词岛还是被盖着"）—— 与 `toFront()` 那句同一个理由。
                //    ★ 释放分支同样放在最前 —— 用户点的是控件还是空白面板，
                //    都是"**他操作了词岛**"⇒ 都该还清欠账。

                // ── ★ 点词岛 ⇒ **不释放**（2026-10-01 01:2x 用户纠正）──────────────
                //
                // ⚠️⚠️ **这里曾经有一条"释放分支"（㉚ 首版），已删除。**
                // 当时我把「必须是"点了才释放"」读成"**点词岛**才释放" ⇒ 在 `mousePressed`
                // 开头插了 `if (tempTopmostPendingRelease) { … restoreAlwaysOnTop() }`。
                // 用户 2026-10-01 01:2x 纠正：「我说的是**点击词岛外部**就释放，
                // **不是点击词岛**」—— 我的实现**正好反了**。
                //
                // ## 现在为什么不在这里释放（正面理由，不只是"用户这么说"）
                //
                // 用户点词岛 = **他正在操作词岛** ⇒ 此刻把它降回（可能变成非 topmost）
                // 会被别的窗口盖住，连这一下的效果都看不见 —— 与"找回是为了看见它"矛盾。
                // ⇒ 点词岛只**提顶**（下面那句）；释放交给 [maintainTempTopmost] 的
                //   前台判据（点外面 ⇒ 前台离开本进程 ⇒ 400 ms 内降回）。
                //
                // ⚠️ 为什么在**控件判定之前**：点控件时也要提（否则"点一下播放键，
                //    词岛还是被盖着"）—— 与 `toFront()` 那句同一个理由。
                if (!dragTopRaised) {
                    dragTopRaised = true
                    raiseToTopForDrag()
                }

                // ── 控件优先于拖动 ──────────────────────────────────────────
                // 顺序是硬要求：面板既是控件条也是拖动把手，同一个按下事件两者都想消费。
                // 先判控件，命中就执行动作并**直接返回**（不记拖动起点）——
                // 这样随后的 mouseDragged 会因为 dragOrigin == null 而自然失效，
                // 不需要额外处理"按在按钮上拖了一半"的边界。
                if (consumeControlPress(e)) return
                if (boolOf(snapshot, "island.lock_position")) return
                dragOrigin = e.locationOnScreen
                dragWindowOrigin = window.location
                panel.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }

            override fun mouseDragged(e: MouseEvent) {
                // 进度条拖动优先：它有自己的连续反馈（预览位置），不能被窗口拖动吃掉。
                if (seekPreviewMs != null) {
                    updateSeekPreview(e.x)
                    return
                }
                if (controlPressConsumed) return
                val origin = dragOrigin ?: return
                val windowOrigin = dragWindowOrigin ?: return
                // ★ 2026-09-21：**拖动期间临时提顶**。用户实测：关闭置顶时拖动词岛，
                //   词岛会被其他应用窗口盖住 —— 他看不见自己在往哪拖、也看不见拖到哪。
                //
                // ★ 2026-09-30（BUG-145 · F-4′）：进入拖动的提顶**已提前到 `mousePressed`**
                //   （语义从"拖动期"扩展为"焦点期"）⇒ **正常情况下这里恒为空操作**
                //   （`dragTopRaised` 已在原实现置位）。
                //   ★ 保留它作为**防御**：万一有路径没走 `mousePressed` 就进了 `mouseDragged`
                //   （例如某些平台的鼠标事件重排），这里仍能保证"拖动时被看见"。
                //   去重仍是必须的 —— 否则每帧一次 `SetWindowPos`（见 `raiseToTopForDrag` 的 KDoc）。
                if (!dragTopRaised) {
                    dragTopRaised = true
                    raiseToTopForDrag()
                }
                val now = e.locationOnScreen
                window.setLocation(
                    windowOrigin.x + (now.x - origin.x),
                    windowOrigin.y + (now.y - origin.y),
                )
            }

            override fun mouseReleased(e: MouseEvent) {
                // ★ 2026-09-21 的原始理由：拖动结束 → **立刻**把置顶恢复成用户设置的值，
                //   不得留滞后 —— 否则"关闭置顶"会表现得像**设置失效**。
                //   见 `raiseToTopForDrag` 的 KDoc 末段。
                //
                // ★ 2026-09-29（BUG-145 · F-2）：这段"结账"**提到方法开头**，先于下面两条
                //   early return（进度条 / 控件）。原实现放在它们之后 ⇒ 走那两条路径时
                //   `dragTopRaised` 留在 `true` 且 `restoreAlwaysOnTop()` 不被调用。
                //   ⚠️ 可达性**未确证**，但复位提前的代价是零（那两条路径上本就是 `false`）。
                //
                // ★★ 2026-09-30（BUG-145 · F-4′）**改为条件释放**：
                //   旧实现在松手时**无条件**降回 ⇒ 用户"点一下词岛（焦点刚过来）、
                //   松手瞬间又掉下去"，表现为"切不回词岛"。
                //   现在：**松手后若前台仍在本 App，则继续保持 topmost**，
                //   由看门狗在"前台真的离开"时才释放（见 `onWatchdogTick`）。
                //
                //   ⚠️ 这不是"留滞后"—— 原顾虑是"松手后窗口还压着别人几百毫秒"，
                //   而现在压着的条件是**前台在词岛 / SPW 上**（用户正在操作它），
                //   压住正是预期行为（与"谁为前台谁置顶"的直觉一致）。
                //   点到别处 ⇒ 看门狗 400 ms 内降回。
                releaseTempTopmostIfFocusLeft()

                // 进度条：松手才提交（见 seekPreviewMs 的 KDoc）。
                if (seekPreviewMs != null) {
                    commitSeek(e.x)
                    return
                }
                if (controlPressConsumed) {
                    controlPressConsumed = false
                    return
                }
                if (dragOrigin == null) return
                dragOrigin = null
                dragWindowOrigin = null
                panel.cursor = Cursor.getDefaultCursor()
                // 只在真正拖动过（位置变了）时落盘，避免"点一下没动"也写文件。
                persistPosition()
            }
        }
        panel.addMouseListener(listener)
        panel.addMouseMotionListener(listener)
    }

    // ── 悬停展开与控件点击 ────────────────────────────────────────────────

    /**
     * 装悬停监听。**必须在 EDT 上调用**（`start()` 里调用，天然满足）。
     *
     * ## 为什么用 `mouseMoved` 而不是"每帧用 `MouseInfo` 取全局鼠标位置"
     *
     * 0.8.9 是在**每帧的 tick 里**用 `MouseInfo.getPointerInfo()` 取全局坐标再判轮廓的
     * （见本文件 `MouseInfo.getPointerInfo()` 那一处）。那样做有两个代价：
     * ① 鼠标不动时也在算（每秒 60 次无意义的判定）；
     * ② 它判的是**屏幕坐标**，于是必须把窗口与面板的偏移一起减掉 ——
     *    任何一次坐标换算写错都会表现为"命中区整体偏移"，而这类错误在
     *    多屏 / 缩放比非 100% 的环境下极难定位。
     *
     * 事件驱动则天然拿到**面板局部坐标**（`MouseEvent.x/y` 就是绘制坐标系），
     * 与 `GlyphHitTester` 的约定完全一致（见其"坐标系约定"一节），一次换算都不需要。
     *
     * ## 为什么 `mouseExited` 也要接
     *
     * 鼠标移出窗口后不会再有 `mouseMoved`，而 `exitDelayMs` 必须开始倒计时。
     * 不接它的话，鼠标快速移出后词岛会**一直保持展开**，直到用户下次碰到它。
     */
    private fun installHoverHandler() {
        val listener = object : MouseAdapter() {
            // ⚠️ 2026-09-23：不再传 `inside = true` —— 判据已收敛到 [onPointer]
            //    内部（岛体矩形）。见那个方法的 KDoc（用户第 ① 条）。
            override fun mouseEntered(e: MouseEvent) = onPointer(e.point)

            override fun mouseMoved(e: MouseEvent) = onPointer(e.point)

            // ⚠️⚠️ 2026-09-20 修「上方控件命中闪现 / 词岛上下闪动」（用户报的）。
            //
            // ## 现象
            // 用户原话：「选择播放控件**向上展开**之后，鼠标一命中，
            //   **在播放控件展开过程中词岛会上下闪动**，鼠标离开亦如此。」
            //
            // ## 根因：**不要无条件相信 `mouseExited`**
            //
            // 词岛在控件展开/收起时会**改自己的窗口尺寸**（向上长高 / 缩回）。
            // Windows 在窗口边界变化后会重算鼠标命中，并可能**补发一次 `mouseExited`**
            // —— 而光标其实**还在岛内**。照单全收就构成一个**自激振荡**：
            //
            //   exited → `inside=false` → 倒计时后收起 → 窗口缩回
            //   → 光标又落在岛内 → `mouseEntered` → 展开 → 改尺寸
            //   → 系统再补发 `exited` → …
            //
            // 这解释了两个特征：为什么**只在展开过程中**闪（改尺寸只发生在动画期）、
            // 以及为什么**进出都会闪**（两个方向都改尺寸）。
            //
            // ## 修法：用**真实光标位置**复核
            //
            // 复核用的 [pollPointer] 就是穿透态轮询用的**同一套**坐标换算 ——
            // 不新增第二条换算路径，避免两处漂移（本项目的既有纪律）。
            // 判定"还在面板矩形内"就当作没离开，走 `inside = true`。
            override fun mouseExited(e: MouseEvent) {
                // ⚠️ 2026-09-23：**删掉了"在面板矩形内"这一步判据** ——
                //    它此前是第二处 `inside` 定义点（与 tick 路径的岛体矩形不同口径），
                //    而两条口径在"永久透明的预留带"上给出相反答案 ⇒ 燃料。
                //    现在 `inside` 只在 [onPointer] 内部算一次（岛体矩形）。
                //    取不到指针（无头 / 远程会话）时 `pollPointer()` 返回 `null`
                //    ⇒ `inside = false`，与原来那条 `else` 分支**同一个结果**。
                onPointer(pollPointer())
            }
        }
        panel.addMouseListener(listener)
        panel.addMouseMotionListener(listener)
    }

    /**
     * 鼠标位置变化时的统一入口。`point == null` 表示鼠标已离开窗口。
     *
     * ## ★★ 2026-09-23：`inside` **不再由调用方传入**（用户第 ① 条）
     *
     * 用户原话：
     * > 「**改为轮廓命中后展缩循环依旧。**」
     *
     * ### 改动前的三条判据（这是振荡的燃料）
     *
     * | 调用方 | 它给的 `inside` |
     * | --- | --- |
     * | `mouseEntered` / `mouseMoved` | **恒 `true`**（只要求"在窗口内"） |
     * | `mouseExited` | "在**面板矩形**内" |
     * | 区域级穿透 tick | `insideIsland(point)`（**岛体矩形**） |
     *
     * 而窗口现在**恒为展开尺寸**（[windowTargetBounds]）⇒ 面板比岛体多出一条
     * **永久透明**的预留带（`BOTTOM` 在下方、`TOP` 在上方，横向两侧同理）。
     * 三条判据在那条预留带上给出**不同**的答案，于是同一个光标位置在一帧内
     * 被写入两个相反的 `lastInside`（事件路径 `true` / tick 路径 `false`）——
     * 而 `HoverState` 的收起倒计时（`exitSince`）**被反复重置或反复启动**。
     *
     * ### 修法：判据收敛成一个（结构上不可能再漂移）
     *
     * `inside` 只在这里算一次，且**与 [islandHitTest] / [insideIsland] 同源**。
     * 调用方再想传一个不同的口径都**没有入口** —— 参数已经删掉。
     *
     * @param point 面板局部坐标；`null` = 鼠标已离开窗口（或取不到指针）
     */
    private fun onPointer(point: Point?) {
        if (!started) return
        val cfg = controlsConfig()
        // `inside` 的**唯一**判据：点在**岛体矩形**内。与 tick 路径
        // （`insideIsland`）以及下面的 `hitGlyph` 用的是同一个量 —— 见方法 KDoc。
        val inside = point != null && insideIsland(point)
        // 先报告边界变化：`island.auto_hide_on_hover` 要**尽快**把词岛藏起来，
        // 拖到方法末尾做的话，中间那段悬停推进已经白跑了一轮。
        // 它内部会按需调 [applyVisibility]（可能让窗口变隐藏，见下）。
        reportInside(inside)
        // ── 窗口隐藏时不推进悬停状态机（2026-09-18 加）─────────────────────────
        //
        // 走到这里说明窗口是隐藏的：要么是 [reportInside] 刚刚按
        // `island.auto_hide_on_hover` 把它藏了，要么它本来就因
        // `hide_paused` / `hide_fullscreen` / `island.enabled` 而不可见
        // （区域级穿透的轮询**不因隐藏而停**，故这条路径是真实可达的）。
        //
        // 为什么必须拦住：`advanceHover` 会把控件条**展开**（`hover.expanded = true`
        // → 改窗口尺寸 → 重绘）。看不见的窗口上做这些是纯浪费，而且会让窗口
        // **恢复显示时带着一个自己弹开的控件条** —— 用户看到"鼠标一移开，
        // 词岛弹出来并抖了一下"。这是 `island.auto_hide_on_hover` 最容易踩的观感坑。
        if (!window.isVisible) {
            // 清掉命中标记：它会被 [advanceHover] 的 `pointerEvent = false` 路径复用，
            // 留一个陈旧的 `true` 等于给"恢复后立刻弹控件"埋雷。
            lastHitGlyph = false
            return
        }
        lastHitGlyph = point != null && when {
            // ── `ISLAND_HIT`（**轮廓命中**）────────────────────────────────────
            //
            // ★★ 2026-09-23 修（用户第 ① 条「改为轮廓命中后展缩循环依旧」）：
            // 原状这里恒为 `true`（只要 `point != null`）—— 即判据是"在**窗口**内"，
            // 而**维持**判据（`HoverState.update` 的 `inside`）是"在**岛体矩形**内"。
            // 窗口恒为展开尺寸 ⇒ 那条永久透明的预留带让两个判据给出**相反**答案：
            //
            // ```
            // 光标停在预留带上（面板内、岛体之外）
            //   → 进入判据 true  → enterDelayMs 后展开
            //   → 维持判据 false → exitDelayMs 后收起
            //   → 进入判据仍 true → 又展开 → …（无限循环）
            // ```
            //
            // 这**同时**解释用户第 ② 条「鼠标还在**框外**就命中了」——
            // 轮廓命中档下，面板**外**的光标也满足 `point != null` ⇒ 判命中。
            //
            // 修法是**结构**的：判据取 `inside`（= `insideIsland`，**与维持判据同一个量**）
            // ⇒ "命中"与"维持"不可能由展开本身翻转 ⇒ 反馈环不存在。
            // 语义也与注册表描述一致：「轮廓命中（悬停到词岛**可见区域**即展开）」——
            // 可见区域就是画出来的那块岛体，不含永久透明的预留带。
            //
            // ⚠️ 刻意**不**用"加延迟 / 迟滞 / 阈值"压振荡：那只是把判据的错位盖住，
            //    换个动效时长就会复发（同一条纪律见 `islandDrawLayout` 的 KDoc）。
            !cfg.glyphTrigger -> inside
            // `GLYPH_HIT`（默认）：必须落在**字格**上，见 GlyphHitTester 的类 KDoc。
            //
            // ⚠️ 2026-09-19：`island.hit.cover = true` 时**封面**也算命中
            //   （见 [IslandPanel.hitCover]）。取或而不是并进 `hitGlyph`，
            //   理由见那个函数的 KDoc（"名字说谎比没名字更糟"）。
            //
            // ★★ 2026-09-23 修（用户第 ② 条「**字形命中时甚至鼠标还在框外就命中了**」）：
            //   判据取**合取** —— 既要压在一个字（或封面）上，也要**在岛体内**。
            //   改动前只有左边那一项，而字格命中域是**整行 `advance`** 那么宽的矩形，
            //   它的落点 `baselineX` 与岛体左边缘**不是同一个锚**：
            //     · `alignBaselineX` 用**面板宽**（窗口恒为展开宽、岛体在窗口内居中）
            //       ⇒ 收起态文本区右边缘可越出岛体右边缘；
            //     · 长歌词滑移把 `baselineX` 平移 `−overflow` ⇒ 命中域整体滑到岛体左侧之外。
            //   两处叠加的**实测越界量**见 `GlyphHitOutsideProbe` §3（向右 / 向左各自的 px）。
            //
            //   ⚠️ 修法刻意**不**去收紧 `glyphHitBox`：那会把"点在汉字正中央"这类**正确**的
            //      命中一起挡掉（汉字墨迹只占包围盒 29%，见 `GlyphHitTester` 的类 KDoc）。
            //      而"在岛内"是**已经算好**的量（上面的 `inside`）⇒ 取合取零额外几何。
            //   ⚠️ 它也**顺带**把第 ① 条收得更死：进入判据与维持判据现在**都含**这一项，
            //      且是同一个量 ⇒ 不可能由展开本身翻转。
            //   语义与注册表描述一致：「悬停到词岛**可见区域**即展开」——
            //   "可见区域"就是岛体，不含那两条**永久透明**的预留带。
            else -> glyphHitAccepted(
                insideIsland = inside,
                glyphOrCoverHit = panel.hitGlyph(point.x.toFloat(), point.y.toFloat(), cfg.hitPaddingPx) ||
                    panel.hitCover(point.x.toFloat(), point.y.toFloat()),
            )
        }
        // ★★ 2026-09-30（封面态 S5）：命中**已锁**的封面 ⇒ 起一个锁头标志显示窗口期。
        //
        // ## 为什么放在这里（`lastHitGlyph` 算完之后、`advanceHover` 之前）
        //
        // 这里正是"用户命中封面"这个事实**唯一**确定下来的地方（`lastHitGlyph` 刚算完）。
        // 放在 `advanceHover` 之前有一个实际好处：状态机在**同一帧**就知道
        // "用户命中了但被锁拦着"，于是标记的显示与"命中不展开"在同帧生效，
        // 不会出现"命中那帧起标志、下一帧才拦住展开"的一帧错位。
        //
        // ## 判据与拦截**同源**（[coverLockBlocksExpand]）
        //
        // "弹标志"与"不展开"必须由**同一个判据**决定 —— 若各写一次，会出现
        // "弹了标志但照样展开了"（或反过来）这种**自相矛盾**的反馈。
        // 故两处都走 [lockBlocksExpandNow]。
        if (lastHitGlyph && lockBlocksExpandNow()) {
            triggerCoverLockBadge()
        }
        // 顺序：先推进状态机（可能改变展开态与窗口尺寸），再按**新的**展开态
        // 更新悬停控件与光标 —— 反过来的话，"刚展开的这一帧"会按收起态去判控件命中，
        // 表现为"控件已经画出来了，但鼠标移上去不变手形、要动一下才生效"。
        advanceHover(cfg, pointerEvent = true)
        updateHoveredControl(point, cfg)
    }

    /**
     * 推进悬停状态机一次，并在状态变化时应用副作用。
     *
     * 鼠标事件与 [onHoverTick] 都调它 —— 两条路径的状态推进逻辑**必须完全一致**，
     * 否则"鼠标不动时延迟到点"与"鼠标微动时延迟到点"会有不同的表现。
     * 唯一的区别是 [pointerEvent]（见 [HoverState.update] 的说明：
     * 定时器 tick 不是用户交互，不能刷新 `autoHideMs` 的活动时刻）。
     */
    private fun advanceHover(cfg: ControlsConfig, pointerEvent: Boolean) {
        // 记下推进前的动画进度：淡入期间 `expanded` 不变，但 `expansion` 每帧都在变，
        // 而**每一帧都必须重绘**（否则动画会冻在第一个 alpha 上）。
        // 这正是"状态没变就不用重绘"这条优化在动画场景下的失效点。
        val before = hover.expansion
        // ★★ 2026-09-30（封面态 S5 · 锁的核心消费点）：**已锁 ⇒ 命中不触发展开**。
        //
        // ## 为什么在这里改（而不是改 `HoverState`）
        //
        // `HoverState.update` 是**纯状态机**（`hitGlyph: Boolean` 是入参），
        // 它不知道"封面态""锁"这些概念。把锁的语义塞进去会让它承担窗口职责。
        // ⇒ 在**喂给它的那一跳**把 `hitGlyph` 折成 `false` 即可 —— 状态机看到的
        // 就是"没命中"，于是 `enterSince` 被重置、永不展开（正是要的语义）。
        //
        // ## 为什么"锁标"这一侧不受影响
        //
        // 锁标的弹出走**鼠标事件**（`onPointer` → `consumeControlPress` / 专门的按压分支），
        // **不经过**本状态机。故这里把 `hitGlyph` 折成 `false` 不会让"点锁标"失效 ——
        // 那正是把两个通道分开的意义（§3.5 L-2）。
        //
        // ## ⚠️ 只折"进入"，不折"维持"
        //
        // 若用户在**展开态**下上锁（右键菜单），此时不该把它**踢回收起态** ——
        // 那会让词岛在用户眼前"缩回去"。故只在"尚未展开"时折
        // （`hover.expanded` 为 false 才折），已展开的会话走正常的离开延迟。
        val hitGlyphForMachine =
            if (!hover.expanded && lockBlocksExpandNow()) false else lastHitGlyph
        val changed = hover.update(hitGlyphForMachine, lastInside, System.currentTimeMillis(), cfg, pointerEvent)
        // ★★★ 2026-10-08 **U9 修复**（用户报「**流光样式下的播放条仍很显示小圆球**」）。
        //
        // ## 根因：`hoveredControl` 在"收起 / 鼠标离岛"时**没人清空**
        //
        // 判据链（`paintProgress` 的 `hovering` 入参）：
        //     `hovering = hoveredControl == PlaybackControls.Id.PROGRESS`
        // 而 [hoveredControl] 的**唯一写点**是 [updateHoveredControl]，它只被
        // [onPointer]（**鼠标事件 / 区域级穿透轮询**）调用 ⇒ ★★ **`onHoverTick`
        // 的非穿透分支根本不调它**（那条路走 [advanceHover]）。
        //
        // ⇒ 于是这条路径上 `hoveredControl` **永远保持上一次的值**：
        //     ① 光标悬停进度条 ⇒ `hoveredControl = PROGRESS`；
        //     ② 光标移开（仍在窗口内，未触发 `onPointer`）⇒ `hover.expanded` 变 false、
        //        窗口收起、圆点看不见 —— **但字段仍是 `PROGRESS`**；
        //     ③ 再次悬停展开 ⇒ `hovering` 立刻为 true ⇒ **圆点又出现**。
        //     ★ 用户感知为"选了流光，圆点**仍很显示**"（"按需"没生效）。
        //
        // ## 为什么在**这里**清（而不是在 `paintProgress` 里加条件）
        //
        // ★ 本项目纪律：**同一条规则只出现一次**。`paintProgress` 拿到的
        //   `hovering` 必须已经是"光标此刻真的在进度条上"这一事实 ——
        //   若在绘制层再补一句 `&& hover.expanded`，那"悬停态"就有**两个定义点**
        //   （`HoverState` 与绘制层），迟早漂移（本项目反复记录过的坑）。
        //   故在**状态的收敛处**（推进悬停状态机之后）把它清干净。
        //
        // ## 清空条件为什么是"两个，取或"
        //
        // · `!hover.expanded` ⇒ 控件条已经不在（**收起态没有可悬停的控件**）。
        //   与 [updateHoveredControl] 里的 `hover.expanded` 门控**同源** ——
        //   两处用同一个量，不可能一个允许一个不允许。
        // · `!lastInside`   ⇒ 光标已离开岛体。**必须独立于上一条**：
        //   离开延迟（`exitDelayMs`）期间 `hover.expanded` 仍为 true，
        //   但那时光标已经在岛外，控件不该"以为被指着"。
        //
        // ★★ 判据本体在 [PlaybackControls.hoverHighlightKept]（**公开纯函数**）——
        //   抽出去是为了让探针能直接断言它（`IslandWindow` 需要真窗口，断言不到）。
        //   本处只做"判据 → 清空"这一跳，**不再自己写一遍布尔表达式**：
        //   写两遍就是 U9 复发的地方（"判据"与"消费它的地方"各一份）。
        //
        // ⚠️ 不清 [PlaybackControls] 的 `previewMs` / `dragOrigin` —— 那些属
        //   **拖动会话**，由鼠标释放事件结束（见 `consumeControlRelease`）。
        //   把它们也清了会让"拖到一半光标短暂离岛"变成**拖动中断**。
        //   本处只收"悬停高亮"这一个语义。
        if (!controls.hoverHighlightKept(hover.expanded, lastInside) && hoveredControl != null) {
            hoveredControl = null
            panel.repaint()
        }
        // 展开方向：带子进度**与状态机同源**（`hover.expansion` 已按 FADE_IN_MS 逐帧推进）。
        // 取 `max` 合成，见 [bandFractionNow] —— 于是"展开播到一半又移回来"不会让带子回跳。
        if (hover.expanded) bandFraction = maxOf(bandFraction, hover.expansion)
        if (changed) {
            // 顺序：先改窗口尺寸（面板的 height 随之变化），再重绘 ——
            // 反过来的话这一帧会按旧高度画一次，看起来像"控件条先闪一下再就位"。
            //
            // ⚠️ 2026-09-22：**两个方向不对称**（这是"上下闪动"的根治点，别顺手改回对称）：
            //   · **展开** → 窗口尺寸**根本不动**（恒为展开尺寸，见 [applyWindowSize]），
            //     带子在随后 180 ms 里从当前占比连续长出来；
            //   · **收起** → 也不动窗口，先让带子连续缩回去（[startBandCollapse]），
            //     动画跑完才由 [advanceBandCollapse] 收窗口 —— 而那里现在同样
            //     **什么都不会改**（窗口已经是目标尺寸），故整段动画 `setBounds` 0 次。
            //
            // ★ 第二处独立成因（BUG-125 的第三个洞）：**展开方向必须取消收缩动画**。
            //   `bandCollapseRunning` 此前只在 [advanceBandCollapse] 的收尾里被置 false，
            //   于是「展开 → 移开（开始收）→ 180 ms 内又移回」这条路径上：
            //   带子会**先缩回收起态、再重新长出来**（两次可见的抖动），
            //   而且收缩动画的收尾还会把 `bandFraction` 压回 0。
            //   展开分支取消它即可 —— `bandFraction` 保留当前值，`max` 合成保证单调。
            if (hover.expanded) {
                bandCollapseRunning = false
                applyWindowSize()
            } else {
                startBandCollapse()
            }
            panel.repaint()
        } else if (hover.expansion != before) {
            // 展开态内动画推进 —— 只重绘，不动窗口尺寸（高度在展开瞬间就已到位）。
            panel.repaint()
        }
        syncHoverTicker()
    }

    /**
     * 起一次"控件带缩回去"的过渡。**必须在 EDT 上调用。**
     *
     * ## 为什么它不经过 `AnimationRuntime`
     *
     * `Motions.CONTROLS_APPEAR.slot` 是 `null` —— 控件条的出现进度**本来就归
     * `HoverState` 的定时器推进**（见 `Slots` 的 KDoc 表格）。收缩是**同一条动效的反向**，
     * 若把它塞进 `AnimationRuntime`，同一条动效就有两个调度者，
     * 而"同一槽位互斥"这条保证也随之失真。
     *
     * 故这里**复用帧循环**（`onFrameTick` → [advanceBandCollapse]），
     * 不新建 `Timer`（纪律：帧循环只能有一个）。
     * 参数（时长 / 缓动）仍从 `Motions.CONTROLS_APPEAR` 读 —— **不在调用点写数字**。
     *
     * ## 时长为什么不乘 `animation.duration_scale`
     *
     * 展开方向的实际时长由 `HoverState.FADE_IN_MS` 决定，而那个常量
     * **不经过** `AnimationRuntime.startIn`，因此**不受 `duration_scale` 影响**。
     * 收缩若乘了倍率，同一对动效会出现"进 180 ms / 出 360 ms"的**不对称** ——
     * 那比"两者都不跟随倍率"更糟（用户看到的是"收回比弹出慢一倍"）。
     * 故两者取**同一个**基准时长（`Motions.CONTROLS_APPEAR.durationMs`，
     * 它与 `FADE_IN_MS` 同值由 `ControlsProbe` 的 O 节守着，见 BUG-111）。
     */
    private fun startBandCollapse() {
        val spec = Motions.CONTROLS_APPEAR
        bandCollapseEasing = spec.easing
        // `animation.enabled = false` → 用户要的是"完全不动"，故**不跑动画**，
        // 由 `advanceBandCollapse` 立刻落到终值（时长 0 ⇒ 下一帧就收完）。
        bandCollapseDurationMs = if (animator.enabled) spec.durationMs else 0L
        bandCollapseStartNanos = System.nanoTime()
        // ★ 从**当前**占比开始收，而不是无条件从 1 开始 ——
        //   "展开播到一半就离开"时，从 1 开始会让带子先**跳**到最大再缩回去。
        //   ⚠️ 此刻 `hover.expanded` 已是 `false`，故 `bandFractionNow == bandFraction`。
        bandCollapseFrom = bandFractionNow
        bandCollapseRunning = true
        // 必须立刻唤醒帧循环：暂停播放时帧驱动是停的，而本动画**只有帧**能推进。
        // 漏了这一句的表现是"暂停时收回控件，带子永远停在半路"。
        syncFrameDriver()
    }

    /**
     * 推进收缩动画一次。**由 [onFrameTick] 调用**（与所有逐帧动效同一条驱动）。
     *
     * 走完时**才**把窗口收回收起尺寸 —— 那是整段收起动画里唯一一次 `setBounds`。
     */
    private fun advanceBandCollapse() {
        if (!bandCollapseRunning) return
        val elapsedMs = (System.nanoTime() - bandCollapseStartNanos) / 1_000_000L
        val t = if (bandCollapseDurationMs <= 0L) {
            1f
        } else {
            (elapsedMs.toFloat() / bandCollapseDurationMs).coerceIn(0f, 1f)
        }
        // 与展开方向**镜像**：`from × (1 - eased(t))` —— 展开是 `eased(t)`
        // （`HoverState.expansion`），于是"进"与"出"是同一条曲线走两个方向，观感对称。
        // ⚠️ 乘 `bandCollapseFrom`（不是恒 1）：见那个字段的 KDoc ——
        //    展开播到一半就离开时，从 1 开始会**先跳后缩**。
        bandFraction = bandCollapseFraction(bandCollapseFrom, bandCollapseEasing.apply(t))
        if (t >= 1f) {
            bandCollapseRunning = false
            bandFraction = 0f
            // ★ 动画已结束 → 此刻才收窗口。这是**本段动画里唯一一次** bounds 变更，
            //   且发生在带子已经不可见之后 ⇒ 用户看不到任何跳变。
            applyWindowSize()
        }
        panel.repaint()
    }

    /**
     * 把带子复位到"收起且无动画"。用于**非用户动画**的收起路径
     * （窗口被隐藏、切换到鼠标穿透）—— 那些路径没有可看的过渡，不该多跑一次动画。
     */
    private fun resetControlsBand() {
        bandCollapseRunning = false
        bandFraction = 0f
    }

    /**
     * 悬停定时器的一次 tick。
     *
     * ## 两条路径（BUG-023，2026-09-17 修）
     *
     * - **非穿透态**：没有新鼠标位置，沿用上一次的 [lastHitGlyph] / [lastInside]。
     *   tick 只负责"把延迟推到点"（进入延迟、淡入动画、离开延迟、自动隐藏）。
     * - **穿透态**：窗口**收不到鼠标事件**，所以这里**必须自己取光标位置**
     *   （[pollPointer]），否则状态机永远停在 `enterSince == NONE`。
     *   取到后走与鼠标事件**同一条** [onPointer] 路径 ——
     *   两条路径的判定逻辑因此**不可能漂移**（命中判定、控件命中、重绘全在同一处）。
     */
    private fun onHoverTick() {
        if (!started) {
            stopHoverTicker()
            return
        }

        // ── 悬停期间**加频重申置顶**（2026-09-18 加）────────────────────────────
        //
        // ## 为什么 400 ms 的看门狗不够
        //
        // 用户原话：
        // > 「拖动时词岛可以盖在任务栏上方，但只要松手，光标离开命中区域，词岛会
        // >   立即被任务栏挤出…**应用全屏下词岛无法保持页面最前**，也**无法置于
        // >   任务栏上方盖住任务栏**。」
        //
        // 关键在"**光标**"：任务栏（`Shell_TrayWnd`）也是 topmost 组的成员，而 Windows
        // 在**鼠标悬停到任务栏上**时会把任务栏提到 topmost 组的最前。词岛要盖住任务栏，
        // 光标就必然落在任务栏的矩形内 —— 于是**每一次悬停都在触发任务栏抢先**，
        // 而 400 ms 的定期重申**追不上由鼠标事件驱动的连续抢先**。
        //
        // ## 为什么是"悬停期间加频"，而不是"把看门狗整体调快"
        //
        // [onWatchdogTick] 的 KDoc 论证过：`SetWindowPos` 提到每帧（33 ms）会在部分
        // 显卡驱动上表现为**可见闪烁**，且窗口在 Z 序里被反复重排。**那个论证成立，
        // 故不动看门狗。** 而"悬停期间"这个窗口期同时满足三件事，使它成为唯一
        // 值得加频的时机：
        //   · 用户**正在看**词岛（闪烁风险最低、收益最高）；
        //   · 任务栏**正在被抢先**（这正是缺陷成立的前提）；
        //   · 悬停 ticker 本来就活着 —— **不新增任何唤醒源**，零额外功耗。
        //
        // ## 为什么必须节流
        //
        // [HOVER_TICK_MS] = 40（展开淡入期间是 `FADE_TICK_MS`，更快）。不节流就是 25 Hz。
        // 取 [RAISE_THROTTLE_MS] = 200 ms（5 Hz）：比看门狗快一倍，又比"每帧"慢一个
        // 数量级 —— 落在"追得上抢先"与"不闪"之间。
        //
        // ⚠️ 门控（置顶开关 / 可见 / 无自有菜单）全部在 [raiseToTopNow] 里，
        //   本处**不要**再写一遍。
        val nowNanos = System.nanoTime()
        if (nowNanos - lastRaiseNanos >= RAISE_THROTTLE_MS * 1_000_000L) {
            lastRaiseNanos = nowNanos
            raiseToTopNow()
        }
        // ── 整窗穿透：**完全不驱动悬停状态机**（用户 2026-09-17 明确要求）────────
        //
        // ## 原记（BUG-023，2026-09-17 早先）—— **已推翻**
        //
        // 原记：穿透态下窗口收不到鼠标事件，故这里**必须**自己轮询指针
        //   （[pollPointer]），否则状态机永远停在 `enterSince == NONE`
        //   （当时称为"悬停引导死锁"），播放控件**永不可达** —— 判定为缺陷并加了轮询。
        //
        // 实为：**那是过度纠正。** 「鼠标穿透」的语义就是**该窗口不接收鼠标交互** ——
        //   "控件不可达"是穿透的**正常结果**，不是缺陷；用户想交互时会关掉穿透。
        //   原实现让"勾了穿透却仍能被悬停触发"，与用户预期**正好相反**。
        //   用户本轮原话：「**设置鼠标穿透后，鼠标经过不应再感应或触发播放控件**」。
        //
        // ## 现做法
        //
        // 穿透态**不喂 [onPointer]**，并把可能已展开的控件条**收起** ——
        // 否则用户从"未穿透"切到"穿透"时，控件条会停在一个**再也点不到**的展开态
        // （看得见、摸不着，还会让人以为"穿透没生效"）。
        //
        // ⚠️ 注意这**只影响整窗穿透**（`island.interactive` = 开）。
        //   **区域级穿透必须保留** —— 它是"背景可穿透、字形可交互"的特性，
        //   与本条不冲突，且它**必须**轮询（见 [REGIONAL_CLICK_THROUGH] 的 KDoc：
        //   要回答"光标现在**离开了**字形吗"，而离开时通常**没有事件**可收）。
        if (!shouldDriveHover(clickThroughActive)) {
            // ⚠️ 这个判断本身在 [shouldDriveHover]（公开纯函数）里 —— 它被探针断言，
            //   防止"穿透后仍驱动悬停"这个**用户投诉过的缺陷**（BUG-046）再回来。
            // 防御性兜底：切换点已 reset 过一次，但 ticker 的时序可能不同步。
            // `reset()` 是幂等的（全部字段回初值），重复调用无害。
            // ⚠️ 判据里必须带上 `bandFraction > 0f`：带子是独立字段，`hover` 全零时
            //   它仍可能停在半程（切换穿透打断了收缩动画）。漏了就会出现
            //   "已穿透、窗口已收，但岛体还按半程高度画"的裁切残影。
            if (hover.expanded || hover.expansion > 0f || bandFraction > 0f) {
                hover.reset()
                resetControlsBand()
                applyWindowSize()
                panel.repaint()
            }
            return
        }
        if (REGIONAL_CLICK_THROUGH) {
            // 取不到（无头/远程会话）时退回"沿用上次状态"，不能让 tick 抛异常 ——
            // 定时器回调里抛异常会静默吞掉并可能停掉定时器。
            val point = pollPointer()
            if (point != null) {
                // `inside` 由**实际包含关系**决定，不是恒 true ——
                // 否则鼠标移出词岛后 `lastInside` 仍为 true，控件永不收起。
                val inside = insideIsland(point)
                // ── 窗口已隐藏时：**只**维护 lastInside，不驱动悬停状态机 ──────────
                //
                // ## 为什么必须有这一条（2026-09-18 加）
                //
                // 两个独立理由，各自都足以否掉"照常推进"：
                //
                // ① **`island.auto_hide_on_hover` 的恢复信号就住在 [lastInside] 里。**
                //    窗口被这个设置藏起来之后**收不到任何鼠标事件**，唯一能发现
                //    "鼠标已经移开"的路径就是本 tick 的轮询 → [reportInside]。
                //    少了它，词岛藏起来就**再也回不来**（不可逆困境）。
                //
                // ② **隐藏期间推进悬停是纯浪费，而且会留下可见的副作用。**
                //    `advanceHover` 在"鼠标停在字形上"时会在 `enterDelayMs` 后
                //    把控件条**展开**（`hover.expanded = true` → `applyWindowSize`
                //    改窗口尺寸 → `panel.repaint()`）。窗口看不见，这些全是白做；
                //    更糟的是窗口**恢复显示时会带着一个自己弹开的控件条**，
                //    用户看到的是"鼠标一移开，词岛弹出来并抖了一下"。
                //
                // ⚠️ 隐藏期间**也跳过 [applyRegionalClickThrough]** ——
                //   对一个不可见的窗口设穿透毫无意义（它本来就不参与命中测试），
                //   而每次 tick 一次 `SetWindowLongW` 是实打实的内核调用。
                if (!window.isVisible) {
                    reportInside(inside)
                    return
                }
                applyRegionalClickThrough(point)
                // ⚠️ 2026-09-23：不再传 `inside` —— [onPointer] 内部会用**同一个**
                //    `insideIsland` 再算一次（上面那个局部变量仍服务于
                //    "窗口已隐藏"分支，故保留）。两条路径的判据因此**逐位相同**。
                onPointer(point)
                return
            }
        }
        // 非穿透态、或取不到指针（无头/远程会话）：沿用上次的命中状态推进。
        // ⚠️ 同样要跳过隐藏窗口 —— 理由见上面那一段（否则会带着弹开的控件条恢复）。
        if (!window.isVisible) return
        advanceHover(controlsConfig(), pointerEvent = false)
    }

    /**
     * 光标（**面板局部坐标**）是否落在**岛体矩形**内。
     *
     * ## ★★ 2026-09-22：判据从"窗口/面板矩形"改成"**岛体矩形**"（本次修法的连带改动）
     *
     * 窗口现在**恒为展开尺寸**（见 [applyWindowSize]），于是它比岛体多出一条
     * **永久透明**的预留带（`BOTTOM` 在下方、`TOP` 在上方）。
     * 若仍按窗口矩形判"鼠标在不在岛内"，就会出现：
     *
     * ```
     * 鼠标落在预留带上 → inside = true → 状态机认为"还没离开" → 控件**永远收不回去**
     * ```
     *
     * 即"修好了闪动，却换来一条关不掉的控件条"——**比原缺陷更糟**。
     * 判据因此必须落在**画出来的那块岛体**上（[bandGeometryNow] 给的 `islandTop..islandBottom`）。
     *
     * ⚠️ 不能改成"窗口矩形减去带高"：展开态岛体**就是**整个窗口
     * （`bandGeometry` 的 `fraction = 1`），那种算法会把已经长出来的控件条判成岛外 ⇒
     * 鼠标一移到控件条上就判为离开 ⇒ 控件条立刻收回（**点了就消失**）。
     *
     * ## 为什么不用 `panel.contains(point)`
     *
     * `panel.width/height` 是**布局算出来的** —— 它依赖 `validate()` 真的跑过。
     * 而窗口隐藏后布局不保证更新（`Component.validate()` 要求 peer 存在，
     * 隐藏窗口的 peer 虽在，但这条链路上任何一环没跑到，尺寸就停在旧值或 0）。
     *
     * 这个差别在 `island.auto_hide_on_hover` 下会变成**严重故障**：
     * 若面板尺寸退化成 0×0，`contains` 恒为 false →
     * 「藏起来 → 立刻判为鼠标已离开 → 立刻放回来 → 下一 tick 又判为在岛内 → 再藏」
     * = **25 Hz 的显隐抖动**，而且看起来完全不像"某个尺寸算错了"。
     *
     * 故宽度仍优先取 [window] 的 `width` —— 那是 `setBounds` **直接设**的值，
     * 不经过任何布局，隐藏时依然可靠。**纵向则完全不再依赖尺寸**：
     * 岛体矩形由 [bandGeometryNow] 从 `baseHeightPx` / 带高 / 进度算出，
     * 与面板、窗口的布局状态都无关（这比"取窗口高度"更可靠）。
     *
     * ## 为什么必须收敛成一个方法
     *
     * "鼠标在不在岛内"有两个调用者（鼠标事件路径与轮询路径）。本项目对
     * "同一语义散在多处定义点"的纪律是**收敛到一处**（见 `boolOf` /
     * `shouldDriveHover` 的 KDoc）—— 否则两处迟早漂移，而漂移的表现是
     * "控件有时收不起来 / 有时不弹出"这种极难定位的时序 bug。
     */
    private fun insideIsland(local: Point): Boolean {
        // 判据本身在 [islandHitTest]（公开纯函数，探针可断言"预留带内必须返回 false"）。
        // ⚠️ 2026-09-22：**不再传面板宽** —— 窗口恒为展开宽后，面板在**横向**也多出
        //    两条永久透明的预留区（岛体在窗口内居中）。用面板宽判会让那两条也判成
        //    "岛内" ⇒ 与纵向那条同一个后果：**控件永远收不回去**。
        //    判据改取 [BandGeometry.islandLeft] / [BandGeometry.islandWidth]。
        return islandHitTest(local.x.toFloat(), local.y.toFloat(), bandGeometryNow())
    }

    /**
     * 记录"鼠标是否在词岛矩形内"，并在**跨过边界**时重算显隐。
     *
     * ## 为什么抽成一个方法（而不是在调用处各写一遍）
     *
     * 有两个调用者：[onPointer]（鼠标事件 / 区域级穿透轮询）与 [onHoverTick]
     * 的"窗口已隐藏"分支。两边都需要**完全相同**的语义 ——
     * 尤其"跨过边界时才重算"这条，写漏了会退化成"每次指针移动都重算"
     * （40 ms 一次读 4 个设置键），写反了会让 `island.auto_hide_on_hover`
     * **完全失效**（永远认为"没变化"）。本项目在"同一语义散在多处定义点"
     * 上吃过不止一次亏（见 `boolOf` / `shouldDriveHover` 的 KDoc），故收敛到一处。
     *
     * ## 为什么只报告"变了"，不在这里判 `inside`
     *
     * "要不要藏"的逻辑**只允许有一处**，即 [applyVisibility]。
     * 在这里再判一次 `inside` 就是第二个定义点，两处迟早漂移。
     *
     * ⚠️ 必须**先比较再赋值** —— 顺序反了则比较结果恒为"没变化"。
     */
    private fun reportInside(inside: Boolean) {
        val crossed = lastInside != inside
        lastInside = inside
        if (crossed && boolOf(snapshot, "island.auto_hide_on_hover")) {
            applyVisibility(snapshot)
        }
    }

    /**
     * **区域级穿透**：按光标位置动态切换 `WS_EX_TRANSPARENT`。
     *
     * ## 判定"交互区"的四个来源（缺一不可）
     *
     * | 来源 | 为什么算交互区 |
     * |---|---|
     * | 字形（`panel.hitGlyph`） | 用户明确要求"命中字形时整个词岛不穿透" |
     * | 封面（`panel.hitCover`） | 2026-09-19 用户点名「追加**封面命中**开关」——**开关打开时**才生效 |
     * | 播放控件（`controls.hit`） | 用户明确要求"可以对播放控件、进度条进行操作" |
     * | 进度条 | 它是 `controls` 布局的一部分，由 `controls.hit` 一并覆盖 |
     *
     * ## 判定失败时按"命中"处理（**安全侧**）
     *
     * 整个判定包在 `runCatching` 里，**异常时返回"命中"（不穿透）**。
     * 理由：异常意味着"我算不清光标在哪"，此时若判成穿透，用户会**点不到词岛**
     * 且没有任何办法恢复（穿透后收不到事件，右键菜单也弹不出来）——
     * 那是**不可逆的困境**。而判成"不穿透"最坏只是"背景挡住了下层"，可自行纠正。
     *
     * ⚠️ 注意这与 `hitGlyph` **内部**的 `runCatching` 方向相反（那里失败按"未命中"）——
     * 两者不矛盾：那里是"**某一行**测失败就试下一行"（局部），
     * 这里是"**整体**算不出来就保守不穿透"（全局）。层级不同，取向自然不同。
     *
     * ## 为什么只在命中态**变化**时才调 `SetWindowLongW`
     *
     * 见 [lastRegionalHit] 的 KDoc：40 ms 一次的无条件内核调用既费 CPU 又可能闪烁。
     * 鼠标停在原地时，这里**一次都不调**。
     */
    private fun applyRegionalClickThrough(point: Point) {
        val overInteractive = runCatching {
            val cfg = controlsConfig()
            val x = point.x.toFloat()
            val y = point.y.toFloat()
            panel.hitGlyph(x, y, cfg.hitPaddingPx) ||
                // 封面命中（`island.hit.cover`，2026-09-19）。与上面那两条**取或**：
                // 三个来源互不排斥，"任一中命中即不穿透"。
                panel.hitCover(x, y) ||
                controls.hit(panel.controlsLayout(), x, y) != null
        }.getOrElse { true }   // ← 算不清 → 按"命中"（不穿透），见上方 KDoc

        if (overInteractive == lastRegionalHit) return
        lastRegionalHit = overInteractive

        val handle = hwnd
        if (handle == 0L) return   // 无 hwnd 时静默跳过（`applyExtensionStyles` 已告警过）
        // `!overInteractive`：命中 → 不穿透；未命中 → 穿透。
        WindowInterop.setClickThrough(handle, !overInteractive)
    }

    /**
     * 穿透态下取指针在**面板局部坐标系**里的位置。
     *
     * ## 为什么把"屏幕 → 面板"的换算收在这一个函数里
     *
     * `IslandWindow` 的类 KDoc 里记过一条设计取舍：当初**刻意不用**
     * 每帧 `MouseInfo` 轮询，理由之一正是"它判的是**屏幕坐标**，必须把窗口与面板的
     * 偏移一起减掉 —— 任何一次坐标换算写错都会表现为**命中区整体偏移**，
     * 而这类错误在多屏 / 缩放比非 100% 的环境下极难定位"。
     *
     * 现在穿透态**不得不**轮询（否则引导死锁），那条理由就变成了
     * "**只允许有一处换算**" —— 即本函数。散落第二处就会重现它警告的那个问题。
     *
     * ## 为什么要 `runCatching`
     *
     * `panel.locationOnScreen` 在组件未显示时抛 `IllegalComponentStateException`
     * （这正是 BUG-019 那条异常的来源）。虽然本函数只在窗口可见时被调用，
     * 但"窗口刚被隐藏、定时器还没停"这一帧是真实存在的竞态 ——
     * 让 tick 抛异常会静默吞掉（Swing 定时器回调），留下一个不报错的失效。
     * 取不到就返回 `null`，由调用方退回"沿用上次状态"。
     */
    private fun pollPointer(): Point? {
        val screen = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return null
        // ⚠️ 窗口**隐藏时** `panel.locationOnScreen` 会抛 `IllegalComponentStateException`
        //    （见 [panelOriginCache] 的 KDoc），故失败时退回缓存的屏幕原点 ——
        //    否则 `island.auto_hide_on_hover` 藏起词岛后将**永远判不出鼠标已离开**，
        //    即"藏起来就回不来"。这是那条恢复路径的唯一支点。
        val origin = runCatching { panel.locationOnScreen }.getOrNull()
            ?: panelOriginCache
            ?: return null
        // 只在取到**新鲜**原点时刷新缓存：缓存不该被它自己的副本反复覆盖
        // （那会让一个过期值永远"看起来是新的"，将来排查时看不出它有多旧）。
        if (panel.isShowing) panelOriginCache = origin
        return Point(screen.x - origin.x, screen.y - origin.y)
    }

    /**
     * 按 [HoverState.needsTicker] 启停悬停定时器。
     *
     * ⚠️ 关键细节：**不能每次都调 `Timer.start()`**。`javax.swing.Timer.start()`
     * 会**重置初始延迟**（等价于 `restart()`）—— 若每次 `mouseMoved` 都调它，
     * 而鼠标在移动中不断产生事件，那么定时器永远等不到第一次触发，
     * 表现是"鼠标划过时控件不弹，停下来才弹"。故这里只在**未运行**时启动。
     *
     * ## 淡入动画期间的间隔取值（**40 ms 对动画太粗**）
     *
     * [HOVER_TICK_MS] = 40 ms 是给"延迟到点"这类**时间事件**用的（误差 40 ms 不可辨）。
     * 但淡入动画只有 `HoverState.FADE_IN_MS`（不足 0.2 s），40 ms 的量化意味着整段动画只有 **3–4 帧**，
     * 观感是"跳两下就出来了"，而不是渐变。
     *
     * 故动画期间改用 [FADE_TICK_MS] = 16 ms（约 60 fps，整段有 9–10 帧）。
     * 这只在**展开后的头一段**内发生，之后 `expansion` 到 1、[HoverState.needsTicker]
     * 会给出"状态稳定"的结论，定时器随即停掉或回到 40 ms —— 不存在长期高频唤醒。
     *
     * 实现上有个陷阱：`javax.swing.Timer` 的间隔**不能在运行中改**（`setDelay` 会
     * 影响**下一次**触发，但正在等待的那次仍按旧值）。故这里在"需要换间隔"时
     * **重建定时器** —— 重建代价是对象分配 + 一次线程调度，而它只在
     * "动画开始/结束"这两个瞬间各发生一次。
     */
    private fun syncHoverTicker() {
        val cfg = controlsConfig()
        // ⚠️ 第二个参数是"必须自己轮询指针"（BUG-023 + 区域级穿透）——
        // 少了它，勾上穿透后定时器会被判定为"不需要"而停掉，
        // 而穿透后又没有任何事件能再把它叫醒 → 悬停功能永久失效。
        //
        // 区域级穿透同理：它要**动态**判断"光标现在离开字形了吗"，
        // 而"离开"这个时刻通常**没有事件**可收（光标移到了窗口外/背景上），
        // 故也必须常驻轮询 —— 见 `REGIONAL_CLICK_THROUGH` 的 KDoc。
        if (!started || !hover.needsTicker(cfg, clickThroughActive || REGIONAL_CLICK_THROUGH)) {
            stopHoverTicker()
            return
        }
        val wanted = if (hover.expanded && hover.expansion < 1f) FADE_TICK_MS else HOVER_TICK_MS
        val timer = hoverTimer
        // 间隔不符 → 重建（`setDelay` 不影响正在等待的那次触发，见上面的说明）。
        if (timer != null && timer.delay != wanted) {
            timer.stop()
            hoverTimer = Timer(wanted) { onHoverTick() }.also { it.start() }
            return
        }
        val active = timer ?: Timer(wanted) { onHoverTick() }.also { hoverTimer = it }
        if (!active.isRunning) active.start()
    }

    /** 停掉悬停定时器但保留实例（下次需要时复用，避免反复建 `Timer`）。 */
    private fun stopHoverTicker() {
        hoverTimer?.stop()
    }

    // ── 看门狗：全屏检测 + 重申置顶（2026-09-18 新增）─────────────────────────

    /** 启动看门狗。幂等（已存在则只确保在跑）。**必须在 EDT 上调用。** */
    private fun startWatchdog() {
        val existing = watchdogTimer
        if (existing != null) {
            if (!existing.isRunning) existing.start()
            return
        }
        watchdogTimer = Timer(WATCHDOG_TICK_MS) { onWatchdogTick() }.apply { start() }
    }

    /** 停掉看门狗但保留实例（与 [stopHoverTicker] 同：避免反复建 `Timer`）。 */
    private fun stopWatchdog() {
        watchdogTimer?.stop()
    }

    /**
     * 是否有**我们自己的弹出菜单**正在显示。**必须在 EDT 上调用**
     * （`Window.getWindows()` 的遍历要在 EDT 上做，见 `GlobalMenuDismisser` 的说明）。
     *
     * ## 为什么看门狗必须避开它（否则会亲手把右键菜单压住）
     *
     * 右键菜单是一个 `Window.Type.POPUP` + `isAlwaysOnTop = true` 的**独立窗口**
     * （见 `IslandPopupMenu.ownerWindow`）—— 它**也是 topmost 组的成员**。
     * 而菜单是**贴着鼠标光标**弹出的（`IslandPopupMenu.menuAnchor` 优先取
     * `MouseInfo` 的逻辑光标），光标就在词岛上 → **菜单与词岛必然重叠**。
     *
     * 于是若看门狗在这时调 [WindowInterop.raiseToTop]，词岛会被提到菜单**之上**：
     * 菜单最上面那一两项（正是离光标最近、最可能被点的两项）被词岛盖住。
     * 用户看到的是"右键菜单被词岛挡住、头两项点不到" —— 而它只在看门狗 tick
     * 撞上菜单打开的那 0.4 s 窗口内发生，表现是**时有时无**，极难归因。
     *
     * ## 为什么不用 `MenuSelectionManager` 判
     *
     * 本项目的菜单是**程序化 `show`** 出来的，实测**不进
     * `MenuSelectionManager` 的选中路径**（路径长度 0，见 `IslandPopupMenu` 的 KDoc）——
     * 用它判会漏。而 `Window.getWindows()` 遍历的是**真实存在的窗口**，
     * 菜单的 popup 窗口与它的 1×1 owner 都在其中 ——
     * `GlobalMenuDismisser` 的外点关闭正是靠同一条路径成立的。
     *
     * ## 判据为什么是"含**可见的** `JPopupMenu`"而不是"有 POPUP 型窗口"
     *
     * 后者会把**宿主**（同进程同 JVM）的任意浮动窗/提示窗也算进来 ——
     * 一旦宿主有一个长期可见的 POPUP 窗口，任务栏遮挡修复就会被**永久静默停用**，
     * 而没有任何征兆。收敛到"真的有 `JPopupMenu` 在显示"就没有这个风险：
     * 那是本插件唯一会弹出的菜单形态，且它的生命周期有明确的开关（见 `hideOwnerOnClose`）。
     *
     * ⚠️ 先判 `w is JWindow` 再递归：`JPopupMenu` 的宿主窗口是 `JWindow`
     * （Swing 的 `PopupFactory.HeavyWeightWindow` 与 owner 都是），
     * 而设置窗口是 `JFrame`/`JDialog` —— 跳过它就不必每 400 ms 遍历一棵深组件树。
     */
    private fun ownPopupVisible(): Boolean = runCatching {
        java.awt.Window.getWindows().any { w ->
            w !== window && w.isVisible && w is JWindow && containsVisiblePopup(w)
        }
    }.getOrDefault(false)

    /** [ownPopupVisible] 的递归部分：容器树里有没有**正在显示**的 `JPopupMenu`。 */
    private fun containsVisiblePopup(container: java.awt.Container): Boolean {
        for (child in container.components) {
            if (child is javax.swing.JPopupMenu && child.isShowing) return true
            if (child is java.awt.Container && containsVisiblePopup(child)) return true
        }
        return false
    }

    /**
     * **重申置顶（带完整门控）。所有"需要立刻提回最前"的时机都必须调本方法。**
     *
     * ## 为什么要有这个包装（而不是各处直接调 `WindowInterop.raiseToTop`）
     *
     * `raiseToTop` 内部是 `SetWindowPos(HWND_TOPMOST)`，它有**三个前置条件**，
     * 漏掉任何一条都会造出一个用户可见的缺陷，而且**归因极难**：
     *
     * | 门控 | 漏掉的后果 |
     * | --- | --- |
     * | `island.always_on_top` 为 true | 用户明确关掉了置顶，我们却把窗口**变成** topmost —— 等于推翻他的设置 |
     * | 窗口当前可见 | 一个不可见的 topmost 窗口被提到最前，会**打乱前台窗口归属**，下一个前台窗口变得莫名其妙 |
     * | 没有自己的弹出菜单在显示 | 把我们**自己的右键菜单压到词岛下面**（菜单看得见、点不到） |
     *
     * 这三条理由在 [onWatchdogTick] 的 KDoc 里逐条论证过。**把它们收在一处**，
     * 是因为"多处各写一遍门控"必然漂移 —— 而那正是本项目反复踩过的形态
     * （同一条判据写两遍 → 两处语义分叉 → 只在某个组合下暴露）。
     */
    private fun raiseToTopNow() {
        if (hwnd == 0L) return
        if (!window.isVisible) return
        if (!boolOf(snapshot, "island.always_on_top")) return
        if (ownPopupVisible()) return
        WindowInterop.raiseToTop(hwnd)
    }

    /**
     * **用户操作期间的**提顶 —— 与 [raiseToTopNow] 的唯一区别是：**不检查
     * `island.always_on_top`**。
     *
     * ## 为什么用户操作可以破例（而"看门狗 / 悬停"不行）
     *
     * [raiseToTopNow] 的门控理由写得很清楚：「用户明确关掉了置顶，我们却把窗口**变成**
     * topmost —— 等于推翻他的设置」。那条理由针对的是**后台的、用户没在操作的**时刻：
     * 那时窗口自己跳出来压住别人，确实是推翻设置。
     *
     * 但**用户正在操作**是另一回事：此刻窗口被别的窗口盖住，后果不是"违背偏好"，
     * 而是**这个操作根本做不成** —— 他看不见自己在往哪拖、也看不见拖到哪，
     * 甚至会发现"点了却切不回来"。
     *
     * 用户原话（2026-09-21）：
     * > 「切换焦点时好时坏，表现为**已在拖动词岛，词岛却仍被其他应用窗口盖住**，
     * >   焦点在其他应用上时，词岛不被覆盖。前面情况皆为**关闭置顶**状态下实测。」
     *
     * 用户原话（2026-09-30，F-4′ 的**终点**定义）：
     * > 「**直到焦点切换走，离开词岛**」
     *
     * ## 调用时机（2026-09-30 起）
     *
     * | 时机 | 调用点 |
     * |---|---|
     * | **按下**（点击 / 拖动 / 点控件） | `mousePressed` ★ F-4′ 新增 |
     * | 拖动第一帧 | `mouseDragged`（**防御性**，正常恒不触发） |
     *
     * ## 释放（2026-09-30 改为**条件释放**）
     *
     * 由 [maintainTempTopmost] 统一判定：**焦点离开本 App 才降回**。
     * ★ **不再**在松手时无条件降回（那会让"点一下就切不回词岛"）。
     * ★ 看门狗每 400 ms 兜底 ⇒ "拖到窗口外松手导致 `mouseReleased` 丢失"也能收干净。
     *
     * ## 为什么释放要**及时**（原顾虑仍在）
     *
     * 若焦点已离开却迟迟不降回，会让"关闭置顶"产生**不确定的滞后效果** ——
     * 看起来就像设置失效。故"焦点离开 ⇒ 最多 400 ms 内降回"，
     * 而这个延迟在"用户已切走、看不见词岛"时不可感知。
     */
    private fun raiseToTopForDrag() {
        if (hwnd == 0L) return
        if (!window.isVisible) return
        // ⚠️ `ownPopupVisible` 仍要检查：拖动词岛时若右键菜单正开着，
        //    把词岛提到菜单之上会让菜单**看得见点不到**（与 [raiseToTopNow] 同一条理由）。
        if (ownPopupVisible()) return
        // ⚠️ **刻意不检查 `island.always_on_top`** —— 见本方法 KDoc。
        WindowInterop.raiseToTop(hwnd)
    }

    /**
     * 把窗口的置顶状态**恢复成用户设置的值**。
     *
     * 拖动结束时调用（见 [raiseToTopForDrag] 的 KDoc 末段）。用
     * `WindowInterop.setAlwaysOnTop` 而不是 Swing 的 `isAlwaysOnTop` ——
     * 与 [applyAppearance] 里那条路径**同一个实现**，
     * 避免"两处控件顶、行为不一致"（本项目反复踩过的形态）。
     */
    private fun restoreAlwaysOnTop() {
        val wanted = boolOf(snapshot, "island.always_on_top")
        runCatching { window.isAlwaysOnTop = wanted }
        if (hwnd != 0L) runCatching { WindowInterop.setAlwaysOnTop(hwnd, wanted) }
    }

    /**
     * 本进程 pid 的缓存（BUG-145 · F-4′）。
     *
     * `ProcessHandle.current().pid()` 每次调用都要走一次系统查询，而看门狗 400 ms 一次 ——
     * 缓存起来即可（进程 id 在一次运行内**不可能变**）。
     * 取不到时返回 `0`（`shouldHoldTempTopmost` 会把 0 当作"判据不可用"）。
     */
    private val selfPid: Int by lazy {
        runCatching { ProcessHandle.current().pid().toInt() }.getOrDefault(0)
    }

    /**
     * **临时 topmost 的"重申 / 降回"维护**（BUG-145 · F-4′ · 2026-10-01 01:2x 回退到本判据）。
     *
     * ## 语义（★ 用户 2026-10-01 01:2x 逐字澄清）
     *
     * > 「我说的是**点击词岛外部**就释放，**不是点击词岛**」
     *
     * ⇒ 判据 = **前台窗口是否属于本进程**，全在纯函数 [shouldHoldTempTopmost] 里
     * （探针逐格断言），本方法只负责**执行**。
     *
     * | 用户动作 | 前台窗口 | 判定 | 结果 |
     * |---|---|---|---|
     * | 点**词岛** | 词岛（**本进程**） | 保持 | ★ **不释放**（他正在用它） |
     * | 点**别的应用 / 桌面 / 任务栏** | 别的进程 | 不保持 | ★ **释放**，降回他的置顶设置 |
     * | 别的程序**自己**抢走前台（用户没点） | 别的进程 | 不保持 | 释放（可接受：Windows 前台锁使这种情况罕见） |
     *
     * ⚠️ **点 SPW 自己的主界面 / 设置窗口不算"外部"**（同进程 ⇒ 保持）——
     * 这是"按进程判"的直接后果，已知并接受（见 `migration/57`）。
     *
     * ## 两条分支
     *
     * | 判定 | 动作 | 为什么 |
     * |---|---|---|
     * | 保持 | `WindowInterop.raiseToTop(hwnd)` | ★ **必须重申** —— 同为 topmost 的窗口之间仍有 Z 序，任务栏/别的 topmost 窗口可能压上来（见 `raiseToTop` 的 KDoc） |
     * | 不保持 | `restoreAlwaysOnTop()` + `dragTopRaised = false` | 前台离开本进程 ⇒ 立刻降回用户的「取消置顶」 |
     *
     * ## 为什么"释放"这一侧要同时清 `dragTopRaised`
     *
     * 该标志是"临时提顶生效中"的**唯一真源**。若不一起清，下一次 `mousePressed` 的
     * `if (!dragTopRaised)` 会恒假 ⇒ **永久不再提顶**（这正是 F-2 修过的那个形态）。
     *
     * ## 幂等性
     *
     * `mouseReleased` 与看门狗都会调本方法 ⇒ 谁先发现谁释放。重复调用是安全的：
     * 不保持时 `dragTopRaised` 已是 `false`，再调一次 `restoreAlwaysOnTop()` 是
     * **写同一个值**（幂等）；保持时 `raiseToTop` 可重复调（见其 KDoc 的"重申"语义）。
     */
    private fun maintainTempTopmost() {
        if (!dragTopRaised) return
        val hold = shouldHoldTempTopmost(
            manualRaised = true,
            foregroundPid = WindowInterop.foregroundProcessId(),
            selfPid = selfPid,
        )
        if (hold) {
            if (hwnd != 0L && window.isVisible) WindowInterop.raiseToTop(hwnd)
        } else {
            dragTopRaised = false
            restoreAlwaysOnTop()
        }
    }

    /**
     * 鼠标松开时的**条件释放**（BUG-145 · F-4′）—— 见 [maintainTempTopmost]。
     *
     * 名字直白：前台还在本 App（用户正在操作词岛）就**不释放**；
     * 点到别处、前台离开了就释放。
     * ★ 旧实现在这里**无条件** `restoreAlwaysOnTop()` ⇒ 用户"点一下词岛、
     * 松手瞬间又掉下去"，表现为"切不回词岛"。
     *
     * ⚠️ 本方法在 2026-09-30 曾一度改名为 `reaffirmTempTopmostOnRelease`
     * （㉚ 首版把释放入口搬到了 `mousePressed`）；用户 2026-10-01 01:2x 纠正后**改回原名**
     * —— 它确实是一条**释放**路径，名实相符。
     */
    private fun releaseTempTopmostIfFocusLeft() = maintainTempTopmost()

    /**
     * 看门狗的一次 tick。**由 `javax.swing.Timer` 在 EDT 上调用**，故这里可以直接碰 Swing。
     *
     * ## 它做两件事，共用**同一次**唤醒（这是刻意的）
     *
     * 两件事的触发源是同一个（"系统状态可能在背后变了，而没有任何事件告诉我们"），
     * 且都需要**低频**。拆成两个定时器只是多一次唤醒、多一处生命周期要维护。
     *
     * ### ① 全屏检测 → 驱动 `island.hide_fullscreen`
     *
     * 只在**状态真的翻转**时才调 [applyVisibility] —— 那个方法会读若干设置并
     * 可能改窗口尺寸，每 400 ms 无脑跑一次是浪费。翻转才处理，且不影响
     * "用户在设置里改了这一项"的路径（那条走 [installSettingsSubscription]，
     * 它自己会调 `applyVisibility`，读到的正是这里刷新好的 [foregroundFullscreen]）。
     *
     * ### ② 重申置顶 → 修「全屏时任务栏遮挡词岛」
     *
     * ## 为什么"置顶"了还会被任务栏挡住（根因）
     *
     * `HWND_TOPMOST` **不是"永远在最上"**，而是"加入 topmost 组"——
     * **组内仍按 Z 序排，后加入者在上**。而**任务栏也是 topmost 组的成员**，
     * 且 Windows 会在若干时机（窗口切换、全屏应用退出、Shell 重绘）
     * **把它自己重新提上来** → 词岛被压到它下面。这不是我们哪里写错了，
     * 是"同为 topmost 的窗口之间还有 Z 序"这条 Win32 语义的必然结果。
     *
     * 既然没有"被遮挡"的通知，唯一的办法就是**定期重申**：
     * 调 [WindowInterop.raiseToTop] 把自己提回 topmost 组的最前。
     *
     * ## ⚠️ 必须先确认用户开启了置顶（否则会凭空造出一个置顶窗口）
     *
     * `raiseToTop` 内部是 `SetWindowPos(HWND_TOPMOST)` —— 若窗口**本来不是** topmost，
     * 这个调用会**把它变成** topmost。而用户可能明确关掉了「窗口置顶」，
     * 那我们就等于**无视了他的设置**，且这种"设置被推翻"极难被归因。
     * 故这里用 `island.always_on_top` 显式门控 —— 它同时保证
     * [applyExtensionStyles] 已经用同一个值设过置顶，两者不会矛盾。
     *
     * ## ⚠️ 为什么必须跳过不可见的窗口
     *
     * 窗口隐藏时（`hide_fullscreen` / `hide_paused` / 用户关了 `island.enabled`）
     * 重申置顶毫无意义，而且会**打乱用户当前的前台窗口关系** ——
     * 一个不可见的 topmost 窗口被提到最前，会让下一个前台窗口的归属变得莫名其妙。
     *
     * ## 频率与代价（诚实记录）
     *
     * [WATCHDOG_TICK_MS] = 400 ms，即 2.5 Hz。每次一次 `GetForegroundWindow` +
     * `GetWindowRect` + 一次 `SetWindowPos`。这是**用户态→内核态**的调用，
     * 量级是微秒，2.5 Hz 下 CPU 占用不可测。
     *
     * ⚠️ **不要把它改成每帧调**：33 ms 一次的 `SetWindowPos` 不只是浪费 ——
     * 它会让窗口在 Z 序里被反复重排，在部分显卡驱动上表现为**可见的闪烁**。
     * 400 ms 的代价是"被遮挡后最多 0.4 s 自愈"，用户感知是"偶尔闪一下"，
     * 远好于"一直被挡"。
     */
    private fun onWatchdogTick() {
        if (!started) {
            stopWatchdog()
            return
        }
        val fresh = snapshot

        // ── ① 全屏检测（**带迟滞**，2026-09-18 修）──────────────────────────
        //
        // ## 原记：检测到就立即切换
        //
        // **实为**（真机日志取证，`super-island.log:5784-5811`）：
        // `island.hide_fullscreen` 的显隐在 **1~3 秒**的间隔上**来回抖** ——
        // `隐藏（原因：island.hide_fullscreen）` → `可见` → `隐藏` → `可见` …
        // 用户看到的就是词岛**反复闪**。
        //
        // ## 为什么"抖"必然是缺陷而不是用户操作
        //
        // 用户把词岛放在任务栏上、正在听歌 —— 这个状态在几秒内**没有任何变化**，
        // 而"是否处于全屏应用"却翻转了 4 次。故翻转源是**瞬时干扰**
        // （`GetForegroundWindow` 拿到别的东西：通知、工具提示、任务栏自身，
        // 或一次返回 `null`），不是用户真的在全屏与桌面之间来回切。
        //
        // ## 修法：迟滞 —— 要求新状态**连续 N 次**都成立才切换
        //
        // 取 [FULLSCREEN_CONFIRM_TICKS] = 2 → 最坏 0.8 s 延迟。取舍：
        //   · 真的进/出全屏 → 迟 0.8 s，感知为"跟着全屏走"，可接受；
        //   · 单次瞬时干扰 → 候选被下一次 tick 重置，**永不触发切换**。
        //
        // ⚠️ **两个方向用同一个 N**，不做"隐藏快、显示慢"的不对称迟滞 ——
        //   不对称会让"退出全屏后词岛要等更久才回来"，而那正是用户最在意的方向。
        //
        // ⚠️ `hwnd == 0L` 时传 0 是安全的：0 不是任何真实窗口的句柄，
        //   排除项恒不命中，最坏只是"少了一层自我保护"（见 `isForegroundFullscreen`）。
        val observed = WindowInterop.isForegroundFullscreen(hwnd)
        if (observed == foregroundFullscreen) {
            // 与当前状态一致 → 清掉候选（这正是"干扰已消失"的判据）。
            fullscreenCandidate = null
            fullscreenCandidateTicks = 0
        } else {
            if (fullscreenCandidate == observed) {
                fullscreenCandidateTicks++
            } else {
                fullscreenCandidate = observed
                fullscreenCandidateTicks = 1
            }
            if (fullscreenCandidateTicks >= FULLSCREEN_CONFIRM_TICKS) {
                foregroundFullscreen = observed
                fullscreenCandidate = null
                fullscreenCandidateTicks = 0
                // 只在用户开着这项时才需要重算显隐；没开的话 [applyVisibility] 也不会看它。
                if (boolOf(fresh, "island.hide_fullscreen")) applyVisibility(fresh)
            }
        }

        // ② 重申置顶。门控三条（用户开了置顶 / 窗口可见 / 没有自己的弹出菜单）
        //    已收在 [raiseToTopNow] 里 —— 本处与其它调用点共用同一份判据。
        raiseToTopNow()

        // ③ ★ 临时 topmost 的"前台期"维护（BUG-145 · F-4′）—— **与 ② 互补**：
        //    ② 管"用户**开了**置顶"时的重申；③ 管"用户**关了**置顶"时，
        //    用户操作过词岛 / 刚找回词岛之后的那段临时提顶。
        //    ★ 两者**不会同时生效**：② 要求 `island.always_on_top` 为 true，
        //    而 ③ 只在 `dragTopRaised`（= 用户关了置顶又按了词岛 / 刚找回）时为真。
        //    故不存在"两条路径打架"。
        //
        //    ★ 本调用是**兜底**：拖到窗口外松手（`mouseReleased` 丢失）时，
        //    由它发现"前台已离开本进程"并释放 —— 否则会**卡在 topmost**。
        //    ⚠️ 它同时负责"保持侧的重申"（前台仍在本进程 ⇒ 重申，防被任务栏压住）。
        maintainTempTopmost()
    }

    /**
     * 更新"鼠标悬停在哪个控件上"以及随之而来的光标形状。
     *
     * ## 光标只在**没有拖动**时管理
     *
     * 拖动中光标由 [installDragHandler] 设为 `MOVE`，这里若把它改回箭头，
     * 拖动过程中光标会在"手形 / 箭头 / 移动"之间跳。
     *
     * ## 2026-09-18：命中字形时**立即**给移动箭头（用户诉求）
     *
     * 用户原话：「鼠标光标**命中字形后仍不会改变箭头光标**，需要**左键单击后**
     * 才能显现抓取手型或可移动的箭头光标。」
     *
     * 根因：改动前这里只会给出「HAND（命中控件）/ DEFAULT（其它）」两种，
     * 而 `MOVE` **只在 `mousePressed` 里设置** —— 于是"这个词岛可以拖动"这件事
     * 从未在悬停时被表达过，用户必须先按一下才看得到。
     *
     * 形状判定抽在 [hoverCursorFor]（`IslandPolicy.kt` 的公开纯函数）里，
     * 因为它是一条**只有一两行、但错过一次就会变成用户可见缺陷**的规则 ——
     * 与 `shouldDriveHover` / `clickThroughFromSetting` 同一处置，理由见该文件 KDoc。
     *
     * ## ⚠️ 为什么这条修法**同时覆盖了"穿透态"与"非穿透态"**
     *
     * 本方法是 [onPointer] 的最后一环，而 [onPointer] 有**两个**调用者：
     *
     * | 路径 | 何时走 | 为什么必须有它 |
     * |---|---|---|
     * | `mouseMoved` / `mouseEntered`（[installHoverHandler]） | 窗口**收得到**鼠标事件时 | 最直接、零延迟 |
     * | [onHoverTick] 的区域级穿透轮询 | 窗口**收不到**事件时 | 见下 |
     *
     * 第二条不是冗余：`REGIONAL_CLICK_THROUGH = true` 下，**光标不在字形上时
     * 窗口被设成 `WS_EX_TRANSPARENT`**（见 [applyRegionalClickThrough]），
     * 此时 Windows 把鼠标消息投给下层窗口 —— **`mouseMoved` 一次都不会来**。
     * 用户把鼠标移上字形的那一瞬间正是这个状态，若只挂鼠标事件路径，
     * 光标仍然要"动一下"才变（因为要先有事件把窗口变回非穿透）。
     * 轮询每 40 ms 跑一次、每次都走 [onPointer]，于是**命中字形的下一帧就设上光标**。
     *
     * 两条路径共用同一个 [onPointer] → 判定逻辑**不可能漂移**
     * （这正是 [reportInside] / [insideIsland] 的 KDoc 反复强调的纪律）。
     *
     * ⚠️ **整窗穿透**（`island.interactive` = 开）时两条路径都不走 ——
     *    [onHoverTick] 在 `!shouldDriveHover(...)` 处就返回了。这是对的：
     *    那时窗口对鼠标完全透明，光标显示的是下层窗口的形状，设了也看不见。
     */
    private fun updateHoveredControl(point: Point?, cfg: ControlsConfig) {
        val next = if (point != null && hover.expanded) {
            controls.hit(panel.controlsLayout(), point.x.toFloat(), point.y.toFloat())
        } else {
            null
        }
        if (dragOrigin == null) {
            panel.cursor = cursorFor(
                hoverCursorFor(
                    hitControl = next != null,
                    // `lastHitGlyph` 由 [onPointer] 在调用本方法**之前**算好 ——
                    // 复用它而不是在这里重算 `hitGlyph`：重算等于给"命中"这个语义
                    // 造出第二个定义点，两处迟早漂移（本项目反复记录过的坑）。
                    overGlyph = point != null && lastHitGlyph,
                    // 锁定位置时按下不会拖动（`mousePressed` 直接 return）→ 悬停也不该
                    // 给移动箭头。理由见 [hoverCursorFor] 的 KDoc。
                    positionLocked = boolOf(snapshot, "island.lock_position"),
                )
            )
        }
        if (next != hoveredControl) {
            hoveredControl = next
            panel.repaint()
        }
    }

    /**
     * [HoverCursor]（纯判定，无 Swing 依赖）→ 真正的 `Cursor` 对象。
     *
     * 为什么要分两层：`Cursor.getPredefinedCursor` 会触碰 Toolkit，
     * 而无头环境下会抛 `HeadlessException` —— 探针（不带显示环境的普通 JVM 进程）
     * 只能断言纯判定那一层，见 [hoverCursorFor] 的 KDoc。
     */
    private fun cursorFor(kind: HoverCursor): Cursor = when (kind) {
        HoverCursor.HAND -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        HoverCursor.MOVE -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        HoverCursor.DEFAULT -> Cursor.getDefaultCursor()
    }

    /**
     * 控件点击。命中则执行动作并返回 `true`（表示"这次按下已被消费，不要拖动窗口"）。
     *
     * ## 为什么这里要再判一次 `hover.expanded`
     *
     * 控件条只在展开态存在。不判的话，收起态下"面板底部的某个坐标"仍可能落在
     * 控件的矩形里（几何上算得出来），用户想拖动词岛却按到了"下一首"。
     *
     * ## ★★ 2026-09-30（封面态 S5）：**放宽**这一前置，为"锁头标志"开一条前置分支
     *
     * ### 为什么必须放宽（`recon/67` §3.5 L-1，否则是**死锁**）
     *
     * 锁头标志**只在收起态出现**（它是"已锁 + 命中封面"的反馈），而它的**唯一**作用
     * 是"点它 ⇒ 展开"（用户原话「点锁标 = 展开」）。若首句仍是
     * `if (!hover.expanded) return false`，那么**收起态下它永远点不到**
     * ⇒ 用户一旦上锁，封面态就**再也展不开**（只能去右键菜单解锁）。
     * 这正是"锁"这个功能最容易死掉的地方 —— 而它**不报错**，只是"点了没反应"。
     *
     * ### 放宽的方式：**不改首句，改在它之前插一条前置分支**
     *
     * 首句的语义（"控件条只在展开态存在"）对**控件条**仍然正确 ⇒ **不动它**。
     * 插入的分支只认**锁标**：锁标是**另一个**可见元素，它有自己的存在条件
     * （本态 + 已锁 + 标志在显示窗口期内），与 `hover.expanded` **无关**。
     * 这样：① 控件条的既有守卫逐字不变（默认档零行为变化）；
     * ② 锁标的存在条件**收在它自己的判据**里（[coverLockBadgeHit]），不会外溢。
     */
    private fun consumeControlPress(e: MouseEvent): Boolean {
        // ★★ 前置分支：锁头标志（仅封面态 + 已锁 + 标志可见时存在）。
        // 见本方法 KDoc —— 它必须能**在收起态**被点到，否则是死锁。
        if (coverLockBadgeHit(e.x.toFloat(), e.y.toFloat()) != null) {
            // "点锁标 = 展开"（用户原话）。
            //
            // ⚠️ 这里**不**改 `cover.lock` 的值 —— 用户明确「点锁头标志 = 展开」，
            //    而解锁走右键菜单 / 设置项（§9-S 定案）。同一个控件不能两用。
            // ⇒ 只把状态机推进到展开态；随后 `advanceHover` 会按展开态走正常动画。
            expandForCoverBadge()
            return true
        }
        if (!hover.expanded) return false
        val hit = controls.hit(panel.controlsLayout(), e.x.toFloat(), e.y.toFloat()) ?: return false
        controlPressConsumed = true
        when (hit) {
            PlaybackControls.Id.PREVIOUS -> performPlayback("上一首") { playbackActions.previous() }
            PlaybackControls.Id.NEXT -> performPlayback("下一首") { playbackActions.next() }
            PlaybackControls.Id.TOGGLE -> {
                // 切换必须**先读当前播放态**再决定调 play 还是 pause ——
                // 宿主 API 没有 toggle()（见 recon/13 §10.4 的行为契约表）。
                val playing = snapshotSupplier().isPlaying
                performPlayback(if (playing) "暂停" else "播放") {
                    if (playing) playbackActions.pause() else playbackActions.play()
                }
            }
            // 进度条：按下**不立即 seek**，而是开始一次拖动手势（见 seekPreviewMs 的 KDoc）。
            // 松手（或拖动）时才会走到 updateSeekPreview / commitSeek。
            PlaybackControls.Id.PROGRESS -> {
                beginSeekGesture(e.x)
            }
        }
        // 点击后立刻重绘：悬停底色与"播放↔暂停"图标都要跟手。
        // 真正的播放态变化会经宿主回调更新快照，但那条路径有延迟（见 PlaybackTimeline
        // 的"宿主一秒一次的心跳"），不能作为按钮图标的即时反馈来源。
        panel.repaint()
        return true
    }

    // ── 进度条 seek ───────────────────────────────────────────────────────

    /**
     * 当前曲目的**稳定标识** —— 用于"拖动中换曲就丢弃这次拖动"。
     *
     * ## 为什么不能只用 `path`
     *
     * 同一首歌重复播放（单曲循环）时 `path` 不变，但播放位置已经归零 ——
     * 此时一次"拖到 2:30"的手势若被提交，会把新的一轮直接跳到 2:30，
     * 而用户的手势针对的是**上一轮**。故把 `docVersion` 也拼进去：
     * 换曲（含重复同一首）会重新载入歌词文档、`docVersion` 自增。
     *
     * `docVersion` 对**无歌词**的曲目不增长（文档是 EMPTY），故 `path` 是主要成分，
     * `docVersion` 是补充。两者都相同则判定为"同一轮播放"—— 这是保守方向：
     * 漏判的后果只是"重复播放同一首时那次拖动被接受"，而误判会让**正常的拖动失效**。
     */
    private fun currentTrackKey(): String = trackKeyOf(snapshotSupplier())

    /** 进度条**轨道**的精确矩形 —— 绘制与 seek 换算的**唯一来源**。 */
    private fun progressTrackRect(): PlaybackControls.Rect? {
        val row = panel.controlsLayout().progress ?: return null
        val snap = snapshotSupplier()
        val metrics = runCatching { panel.fontMetricsOf() }.getOrNull() ?: return null
        return controls.trackRect(row, metrics, snap.positionMs, durationSupplier())
    }

    /**
     * 把鼠标 x 换算成目标毫秒。
     *
     * 每次调用**重新取轨道几何与 FontMetrics**（而不是缓存一份）：鼠标事件之间
     * 播放位置在推进，而时间标签宽度可能因秒数进位而变化（`0:59` → `1:00`），
     * 故轨道左端会随之移动几个像素。缓存会让"长时间按住后拖动"出现偏移。
     * 这条路径的频率是每秒至多几十次，重新计算的量级是微秒级。
     */
    private fun seekTarget(x: Int): Long? {
        val track = progressTrackRect() ?: return null
        val metrics = runCatching { panel.fontMetricsOf() }.getOrNull() ?: return null
        return controls.seekTargetAt(track, metrics, x.toFloat(), durationSupplier())
    }

    /** 开始一次 seek 手势。时长未知时什么都不做（进度条不可交互，见 `seekTargetAt`）。 */
    private fun beginSeekGesture(x: Int) {
        // 先确认这次按下真的能换算成时间目标；不能就不进入手势
        //（否则会留下一个"拖动中"的状态，而松手时又提交不了 —— 用户的拖动被静默吞掉）。
        if (seekTarget(x) == null) return
        // ★ 2026-09-28：新的一次按下**立刻作废**上一次提交的显示保持。
        //   理由：用户又按下来了，说明他/她对刚才那次跳转不满意/要改 ——
        //   此时若还锁着旧目标，拖动预览会被那个旧值覆盖，手感直接错乱。
        //   这与"用户输入优先于宿主的陈旧回显"是同一条原则。
        clearCommittedSeekHold()
        seekGestureKey = currentTrackKey()
        seekPreviewMs = seekTarget(x)
        panel.repaint()
    }

    /** 拖动中：把鼠标 x 换算成预览毫秒并重绘。**不调用宿主 seek**。 */
    private fun updateSeekPreview(x: Int) {
        val target = seekTarget(x) ?: return
        if (target == seekPreviewMs) return
        seekPreviewMs = target
        panel.repaint()
    }

    /** 作废"提交后显示保持"（见 [committedSeekMs] 的 KDoc）。 */
    private fun clearCommittedSeekHold() {
        committedSeekMs = null
        committedSeekDeadlineNanos = 0L
    }

    /**
     * ★★ 进度条**实际应当显示的位置**（毫秒）—— 绘制路径的**唯一出口**。
     *
     * ## 为什么必须收敛成一个函数（纪律 15 / 纪律 10）
     *
     * 显示位置的来源有三个：拖动预览、提交后的短期保持、时间轴的真实位置。
     * 若在绘制点就地写成一串 `?:`，将来任何一处新增来源都会漏改另一处 ——
     * 本项目的"改了 A 但 A 的输入被 B 掐断"已经有过 6 次，故收敛成唯一出口。
     *
     * ## 判定顺序（每一步都对应一个已发生的真机现象）
     *
     * 1. **[seekPreviewMs]**（拖动中）优先级最高 —— 用户的手指就是真相。
     * 2. **[committedSeekMs]**（刚松手、宿主还没回新位置）—— 见该字段的 KDoc：
     *    松手那一帧若退回 `snap.positionMs` 就是用户报的"闪现回原位"。
     * 3. **`snap.positionMs`**（默认）—— 宿主确认后自然回到这里。
     *
     * ## 第 2 步的失效判据是**比值/时间**，不是标志位（纪律 14）
     *
     * - **已接近目标**（`|snap.positionMs − target| ≤ [SEEK_CONFIRM_TOLERANCE_MS]`）
     *   ⇒ 宿主已经跳过去了 ⇒ 保持**立即失效**（继续用它只是多此一举，且会盖住正在推进的位置）；
     * - **超过截止期**（[committedSeekDeadlineNanos]）⇒ 宿主没回 ⇒ 放弃保持；
     * - **换曲**（`path` 变了）⇒ 那个毫秒数是上一首的，作废。
     *
     * 这三条都是**无状态**的（每次调用现算），因此不存在"忘了复位"这种失败模式。
     */
    private fun progressDisplayMs(snap: TimelineSnapshot): Long {
        seekPreviewMs?.let { return it }
        val target = committedSeekMs ?: return snap.positionMs
        val now = System.nanoTime()
        // ① 换曲（含单曲循环重新载入）：目标毫秒已失去意义。
        if (trackKeyOf(snap) != committedSeekTrackKey) {
            clearCommittedSeekHold()
            return snap.positionMs
        }
        // ② 宿主已确认（位置到了目标附近）：保持失效。
        if (kotlin.math.abs(snap.positionMs - target) <= SEEK_CONFIRM_TOLERANCE_MS) {
            clearCommittedSeekHold()
            return snap.positionMs
        }
        // ③ 超时（宿主始终没回）：放弃保持。
        if (now >= committedSeekDeadlineNanos) {
            clearCommittedSeekHold()
            return snap.positionMs
        }
        return target
    }

    /** 由快照算出曲目稳定标识 —— 与 [currentTrackKey] 同一公式（**必须同源**）。 */
    private fun trackKeyOf(snap: TimelineSnapshot): String = "${snap.path}#${snap.docVersion}"

    /**
     * 松手：提交 seek 并清掉手势状态。
     *
     * ## 两条守卫（缺一都会产生"跳到莫名其妙的位置"）
     *
     * ① **曲目没换**（[seekGestureKey] 比对）—— 拖动中换曲则丢弃，理由见该字段的 KDoc；
     * ② **仍能换算**（`seekTarget` 返回非 null）—— 拖动中可能切到了流媒体曲目
     *    （时长变 0），此时预览值是用上一首的时长算的，提交过去毫无意义。
     *
     * 无论提交与否都**必须清掉手势状态**：不清的话 `mouseDragged` 会一直认为
     * "还在拖进度条"，此后拖动词岛就失效了。
     *
     * ## 为什么按"松手时的 x"重算，而不是直接用 [seekPreviewMs]
     *
     * 预览只在 `mouseDragged` 里更新，而用户可能**按下后直接松手**（没产生任何
     * drag 事件）—— 那时预览是按下点算的，与松手点相同，重算无害；
     * 但若拖动中有事件丢失（EDT 繁忙时的合并），重算能保证提交的是**用户看到手停在哪**。
     * 用重算值让"提交的位置 == 用户最后看到的位置"。
     *
     * ## ★ 2026-09-28：清预览**之后**必须立刻建立"显示保持"（用户报障）
     *
     * 用户原话（逐字）：「**拉动进度条(无论何种形态)，松手后会短暂闪现回原位**」。
     *
     * 本方法原来只做 `seekPreviewMs = null` + `repaint()`，于是**这一帧**
     * 进度条按 `snap.positionMs`（= seek 前的旧值）画 ⇒ 肉眼可见地弹回原位，
     * 直到约一秒后宿主那条低频位置回调把新位置送来。
     *
     * 现在：先把 `target` 写进 [committedSeekMs] 并给一个 [SEEK_DISPLAY_HOLD_MS] 截止期，
     * **再**清预览 —— 于是"清预览"与"建立保持"之间**没有任何一帧**会露出旧位置。
     * 顺序是硬要求（先清后建 = 中间恰好露一帧旧值，正是要修的那个现象）。
     */
    private fun commitSeek(x: Int) {
        val gestureKey = seekGestureKey
        val hadPreview = seekPreviewMs != null
        seekGestureKey = null
        // 没进入过手势（beginSeekGesture 提前返回）→ 什么都不做，也不建立保持。
        if (gestureKey != null && hadPreview) {
            val target = seekTarget(x)
            if (target != null && gestureKey == currentTrackKey()) {
                // ① 先建立显示保持（`trackKeyOf` 用**同一份**曲目标识公式）。
                committedSeekMs = target
                committedSeekTrackKey = gestureKey
                committedSeekDeadlineNanos = System.nanoTime() + SEEK_DISPLAY_HOLD_MS * 1_000_000L
                // ② 再清预览并重绘 —— 中间不留任何"两边都空"的帧。
                seekPreviewMs = null
                panel.cursor = Cursor.getDefaultCursor()
                panel.repaint()
                performPlayback("跳转") { playbackActions.seekTo(target) }
                return
            }
        }
        // 未提交（守卫拦下）：同样要清干净，否则 `mouseDragged` 会一直认为在拖进度条。
        clearCommittedSeekHold()
        seekPreviewMs = null
        panel.cursor = Cursor.getDefaultCursor()
        panel.repaint()
    }

    /**
     * 统一包裹播放控制调用。
     *
     * 包 `runCatching` 是硬要求：宿主 API 在"宿主正在退出""没有当前播放器"等状态下
     * 可能抛异常，而这里是 EDT 上的鼠标事件回调 —— 异常逃逸会被 Swing 吞掉并留下
     * 一块不再重绘的区域（与 `paintComponent` 里逐行 `runCatching` 是同一类防御）。
     *
     * @param label 仅用于日志。写清楚是哪个动作，否则日志里只有一句异常毫无线索
     */
    private inline fun performPlayback(label: String, block: () -> Unit) {
        runCatching { block() }.onFailure {
            System.err.println("[CDL] 播放控制「$label」调用失败：$it")
        }
    }

    /**
     * 把当前窗口位置写入 `window.position`。**必须在 EDT 上调用。**
     *
     * ## ⚠️ `TOP` 档必须把"窗口比逻辑位置高的那一条带高"加回去（2026-09-17 / 2026-09-22）
     *
     * `window.position` 的语义始终是"**词岛该在哪**"（= 逻辑位置），与控件条展开与否无关。
     * 而窗口恒为展开尺寸之后，`TOP` 档下 `window.y` **恒比逻辑位置高一条带高**
     * （多出来的那段在岛体**上方**，否则控件条没地方画）——
     * 见 [windowYOffsetPx] 与 [windowYShiftPx]。
     *
     * 若直接存 `window.y`：`TOP` 档用户拖动词岛后，**下次启动词岛会整体下移一条带高**，
     * 且每存一次累积一次。
     *
     * ## 为什么用字段 [windowYShiftPx] 而不是现算
     *
     * 改动前这里写的是 `if (hover.expanded && cfg.position == TOP)` —— 那个判据在
     * "窗口恒为展开尺寸"下**不再成立**（偏移与悬停态无关了）。而现算 `stripQ` 只能得到
     * "**应该**是多少"，得不到"当前窗口 y 是按哪个档摆的"这个**历史事实**
     * （位置档位刚被改掉的那一瞬间，两者不同）。故一律读记账字段。
     */
    private fun persistPosition() {
        val runtime = settings ?: return
        val location = window.location
        // 补偿量 = 窗口 y 相对逻辑位置的偏移（`TOP` 档为带高，`BOTTOM` 档为 0）。
        // ⚠️ 它是 [applyWindowSize] / [restoreSavedPosition] / [resetToPrimaryScreen]
        //    三处共同维护的记账值，**不要在别处重算**。
        val shiftedUp = windowYShiftPx
        val value = "${location.x},${location.y + shiftedUp}"
        // apply（而非 publish）：位置的中间态没有消费者，见 installDragHandler 的说明。
        // 整段包 runCatching —— 设置写入失败（磁盘满/权限）不该让拖动崩掉。
        runCatching { runtime.apply(mapOf("window.position" to value)) }
            .onFailure { System.err.println("[CDL] 词岛位置未能保存：$it") }
        // 松手瞬间重申置顶（2026-09-18）。理由：拖动期间鼠标**必然**扫过任务栏所在区域，
        // 而任务栏在鼠标悬停其上时会把自己提到 topmost 组最前 —— 于是"松手"这一刻
        // 恰恰是词岛最可能已经被压到任务栏下面的时刻。这里补一次，
        // 用户看到的才是"拖到哪就停在最前"，而不是"松手就掉下去"。
        raiseToTopNow()
    }

    // ── Win32 扩展样式 ────────────────────────────────────────────────────

    /**
     * 取得 HWND。分两段尝试（立刻 + 下一轮 EDT），都失败就保持 `0L` 永久降级。
     *
     * ## 定位方式：**先 `getWindowPointer`，再按标题兜底**（2026-09-16 改）
     *
     * **原记**（本方法的早期版本）：唯一路径是 `findHwndByTitle(windowTitle)`。
     *
     * **实为**：那是**脆弱设计**，真机上它直接让「鼠标穿透」失效 ——
     * 12:36 构建的日志里 `FindWindowW` 返回 null 后代码对 null 取 `.pointer`
     * 抛 NPE，整条定位链路降级：
     *
     * ```
     * [CDL] 按标题查找窗口失败（降级为无扩展能力）：java.lang.NullPointerException:
     *     Cannot invoke "…WinDef$HWND.getPointer()" because "direct" is null
     * [CDL] 未能按标题找到词岛窗口（HWND 未知）：鼠标穿透与「不显示在 Alt+Tab」将不可用
     * ```
     *
     * 现在首选 [WindowInterop.hwndOf] —— 用 JNA 内建的 `Native.getWindowPointer`
     * **直接从我们自己创建的 `Window` 取句柄**，不依赖窗口标题对 `GetWindowTextW` 可见。
     * 原实现 0.8.9 用的就是这个（`089:platform/WindowsOverlay.kt:17`），它从来不用标题。
     * 按标题查找**保留为兜底**（peer 尚未建立时聊胜于无）。
     *
     * ## 为什么需要第二段（这一条没变，仍然必要）
     *
     * `isVisible = true` 之后 native 建窗是**异步**的，立刻查可能查不到
     * （`isDisplayable` 仍为 false → `hwndOf` 返回 null）。
     * `invokeLater` 至少让当前事件循环跑完，给 native 一个机会 ——
     * 实践中这就够了。**不做定时轮询**（见 [start] 的说明）。
     */
    private fun resolveHwnd() {
        if (!WindowInterop.available) {
            System.err.println("[CDL] 非 Windows 环境，词岛以纯 Swing 形态运行（无穿透/工具窗）。")
            return
        }
        // 首选：直接从 AWT Window 取句柄（不依赖标题）。
        val direct = WindowInterop.hwndOf(window)
        if (direct != null) {
            hwnd = direct
            return
        }
        SwingUtilities.invokeLater {
            // 第二段仍先试 `hwndOf` —— 此刻 peer 通常已建立，这是最可能成功的时机。
            val retryDirect = WindowInterop.hwndOf(window)
            if (retryDirect != null) {
                hwnd = retryDirect
                applyExtensionStyles(snapshot)
                return@invokeLater
            }
            // 兜底：按标题枚举。走到这里说明 peer 迟迟未建立或 `getWindowPointer` 不可用。
            val byTitle = WindowInterop.findHwndByTitle(windowTitle)
            if (byTitle != null) {
                hwnd = byTitle
                applyExtensionStyles(snapshot)
            } else {
                System.err.println(
                    "[CDL] 未能定位词岛窗口（HWND 未知）：" +
                        "鼠标穿透与「不显示在 Alt+Tab」将不可用；置顶退回 Swing 自带实现。" +
                        "（已尝试：getWindowPointer / 按标题枚举）"
                )
            }
        }
    }

    /**
     * 把设置里的三项窗口级能力应用到 HWND。**必须在 EDT 上调用。**
     *
     * 三项各有一个「Swing 自带退路」，这是刻意的分层：
     * - **置顶**：即使 JNA 失败，`window.isAlwaysOnTop` 也能达成大部分效果，
     *   故它**总是**被执行（先设 Swing 的，再用 JNA 兜一层更硬的 topmost）。
     * - **穿透**：Swing 无对应能力，JNA 失败就是真的做不到 —— 此时**不隐藏窗口**，
     *   只是它可被点击（用户仍能从右键菜单关掉它）。
     * - **工具窗**（不进 Alt+Tab）：同理无 Swing 退路。
     */
    private fun applyExtensionStyles(fresh: SettingsSnapshot) {
        val alwaysOnTop = boolOf(fresh, "island.always_on_top")
        // Swing 退路：无条件先设。注意它在非 EDT 调用会炸，故本函数必须在 EDT。
        runCatching { window.isAlwaysOnTop = alwaysOnTop }

        val handle = hwnd
        if (handle == 0L) {
            // ── 为什么必须打这条日志（2026-09-17 新增）─────────────────────
            //
            // 原记：`if (handle == 0L) return` —— **静默返回**。
            // 实为：这会让"拿不到 hwnd"这件事**完全不可观测** ——
            //   穿透与置顶样式**双双失效**，而日志里**一个字都没有**。
            //   用户看到的是"我勾了鼠标穿透，没反应"，而排查时**无从下手**：
            //   既不知道 hwnd 有没有取到，也不知道样式有没有设上。
            //
            // 这是「词岛锁定和鼠标穿透无法作用于词岛」这条真机反馈的
            // **第二大嫌疑**（第一是 `setClickThrough` 掩盖失败，见 recon/37 §1.3）。
            //
            // hwnd 取不到时**不降级为"部分可用"**是刻意的：穿透与置顶都**必须**有
            // 有效 hwnd 才能设，硬撑着继续只会让状态更混乱（置顶走 Swing 退路已设过，
            // 见上方 `window.isAlwaysOnTop`）。
            System.err.println(
                "[CDL] ⚠️ 未取到窗口 hwnd（三级 fallback 全失败）→ " +
                    "穿透/置顶的 Win32 样式**本次未应用**。" +
                    "表现为「鼠标穿透」「锁定位置」等设置看似无效。见 recon/37 §1.3。"
            )
            return
        }

        // ══ ★★★ 2026-10-11（**U12**）：**已移除「把词岛排除出截屏」** ═════════════════
        //
        // ## 原记（2026-10-10 · BUG-163）
        //
        // ★ 此处曾调 `CaptureExclusion.applyTo(handle)`（`SetWindowDisplayAffinity`
        //   + `WDA_EXCLUDEFROMCAPTURE`）—— ★ 它的**唯一目的**是给 L3（真透视）铺路：
        //   ★ 没设 ⇒ 词岛**对自己也可见** ⇒ 自捕获护栏判"命中" ⇒ **折射层永远不显示** ✗
        //   （真机日志取证：`[L3] ★★ 退纯色玻璃并停用截屏（s1=0.411 s1hp=0.853 reason=hit）`）
        //
        // ## ★ 为什么移除（用户指令 2026-10-11）
        //
        // > 「液态玻璃呈现**始终不达标**，**截断其渲染路径以及显示的设置项**，
        // >   但有收益的以及对其他功能还有用的建设可以保留。」
        //
        // ★★ **L3 是 `CaptureExclusion` 的【唯一消费者】**（全树仅此一处调用）⇒
        //   ★ 玻璃下架 ⇒ ★ 它**失去了全部功能价值** ✓
        // ★★ 而它有一个**用户可见的副作用**：★ **截图（Win+Shift+S / 第三方截屏）拍不到词岛**
        //   ⇒ ★ 用户无法截图反馈（★ 本日已被此困扰两次）✓
        // ⇒ ★ 按用户拍板「**一起下架**」移除 ✓
        //
        // ⚠️ `CaptureExclusion.kt` **文件保留**（未删）—— 理由：
        //   · 它是「**Win32 窗口级属性的一个正确实现**」，算法本身可复用；
        //   · ★ 路线文档说"补不齐的上限"要靠 **D-2 架构重构** ⇒ ★ 将来若复活 L3 还要用它；
        //   · ★ 已在文件头登记为「**待评估删除**」✓
        // ★ 判据（★ 可复算）：`grep -rn "CaptureExclusion.applyTo" plugin/src/main` = **0** ✓

        // 穿透：键 `island.interactive` 的**标题**是「鼠标穿透」，而它的**值语义**是
        //   `true` = 穿透开启（= 不可交互）。这一点有四处独立证据，勿凭键名猜：
        //   1. `preference_config.json` 该项 `title` = "鼠标穿透"、`default` = false；
        //   2. 本文件右键菜单的勾选项 `isSelected` **直接读写该键的值**（见 `IslandPopupMenu.kt` 里勾选项读写该键那处），
        //      故"勾上"就等于值为 true；若 true 表示"可交互"，勾上「鼠标穿透」反而会关掉穿透；
        //   3. `SettingRegistry` 该项注释写明「true 表示穿透」；
        //   4. `CdlRuntime` 对 `interactive=false` 的注释是「解除鼠标穿透」。
        //
        // ⚠️ 曾有两次误改，务必别再犯（两次都是"键名与值语义相反"引发的误判）：
        //   - 错法 A：`setClickThrough(handle, boolOf(fresh, "island.interactive"))`
        //     靠双否定"碰巧"正确（用户值为 false → 不穿透），但语义是绕回原点，
        //     读起来像 bug、其实是**正确行为**；
        //   - 错法 B：`val clickThrough = !interactive` —— **越过键名去"修正"取值方向**，
        //     把本来正确的直传改成了取反，导致两种情形**全反**：
        //       用户勾上「鼠标穿透」→ 传 false → 穿透被关掉；
        //       默认 false → 传 true → 一启动就整窗穿透，鼠标完全进不来。
        //     这是**把命名问题误判成逻辑问题**。键名 `interactive` 描述的是"概念"，
        //     不是"要传给 setClickThrough 的布尔方向"；取值方向只由**值语义**决定。
        //
        // 结论：直传，不取反。若要改可读性，只应改**局部变量名**（如下），
        // 而**不是**改取值方向。见 recon/30 §V-B。
        //
        // ⚠️ 2026-09-17：这里改走 [clickThroughFromSetting] —— 一个**公开的纯函数**。
        //   它做的事与 `boolOf(...)` 完全一样（**不取反**），但把"直传是对的"这条约定
        //   从**注释**变成了**代码 + 探针断言**：想取反的人必须改掉那个函数，
        //   而 [IslandPolicyProbe] 会立刻变红。这个坑已被误改两次（BUG-020），
        //   而那两次的探针都是全绿的 —— 光靠注释拦不住方向类错误。
        val clickThrough = clickThroughFromSetting(boolOf(fresh, "island.interactive"))

        // ── 与区域级穿透的分工（2026-09-17）──────────────────────────────
        //
        // 勾选了「鼠标穿透」→ 整窗穿透，**本处直接设**，区域级逻辑完全退让。
        // 未勾选 → 是否穿透由**光标位置**决定，归 `applyRegionalClickThrough` 管。
        //   本处**不能**直接设 `false`，否则每次设置变更都会把窗口短暂拉回
        //   "不穿透"，而下一 tick（≤40 ms）又被区域级逻辑改回去 ——
        //   用户看到的是"改个设置，词岛闪了一下"。
        //
        // 但仍要在这里设一次（按上次的命中态），因为设置变更时
        // `lastRegionalHit` 可能已过期（如窗口刚显示、或此前 hwnd 未就绪）。
        val effectiveClickThrough = if (REGIONAL_CLICK_THROUGH && !clickThrough) {
            !lastRegionalHit
        } else {
            clickThrough
        }
        WindowInterop.setClickThrough(handle, effectiveClickThrough)
        // 记下穿透态供悬停状态机使用（BUG-023）。
        //
        // ⚠️ **必须在 `setClickThrough` 之后、且要触发 `syncHoverTicker()`** ——
        // 穿透开/关会切换"事件驱动 ↔ 轮询"两种悬停模式：
        //   · 关闭穿透 → 事件驱动，定时器按需启停（省电）
        //   · 开启穿透 → 必须常驻轮询（否则引导死锁，见 HoverState.needsTicker 的说明）
        // 只改字段不同步定时器，会让"刚勾上穿透"这一刻定时器仍处于停止状态 ——
        // 而它**再也没有机会**被启动了（穿透后收不到事件，没有任何东西会来叫醒它）。
        //
        // ⚠️ 这里比较的是**设置值** `clickThrough`（用户勾没勾），
        // 而**不是** `effectiveClickThrough` —— 因为 `clickThroughActive` 的语义是
        // "用户是否开启了整窗穿透"，它决定悬停走"事件驱动"还是"常驻轮询"。
        // 区域级穿透让"常驻轮询"也成立，但那是 `REGIONAL_CLICK_THROUGH` 的事，
        // 与这个字段的语义无关（见 `syncHoverTicker` 的调用点）。
        if (clickThroughActive != clickThrough) {
            clickThroughActive = clickThrough
            // 开启穿透时**立刻收起**可能已展开的控件条（用户 2026-09-17 要求）。
            //
            // ## 为什么必须在**切换点**做，而不是等 `onHoverTick`
            //
            // 控件条一旦展开，窗口会变高；而穿透态下用户**再也点不到它** ——
            // 它会停在那里直到用户关掉穿透。那是"看得见但摸不着"的坏状态，
            // 而且会让用户以为"穿透没生效"（画面还在动、控件还亮着）。
            //
            // tick 里也有一处兜底（时序不同步时），但**这里才是确定的时点**。
            if (clickThrough) {
                hover.reset()
                // 同上：带子是独立字段，必须一起复位（否则穿透后残留半程高度）。
                resetControlsBand()
                runCatching { applyWindowSize() }
                runCatching { panel.repaint() }
            }
            syncHoverTicker()
        }

        // 置顶：JNA 的 SetWindowPos 比 Swing 的更强（后者在无边框窗口上偶有失效）。
        WindowInterop.setAlwaysOnTop(handle, alwaysOnTop)

        // 工具窗：词岛是常驻悬浮件，混进 Alt+Tab 会干扰用户切换真实应用。
        WindowInterop.setToolWindow(handle, true)
    }

    // ── 工具函数 ─────────────────────────────────────────────────────────

    /**
     * 线程收敛：已在 EDT 则直接跑，否则投递。
     *
     * 之所以两分支都要（而不是永远 `invokeLater`）：**永远投递**会让
     * 「调用方是 EDT、且调用方需要同步语义」的场景拿到过期的返回值
     * （如 [isVisible] 刚设完立刻读）。已在 EDT 时同步执行既正确又省一跳。
     */
    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }

    /**
     * 逐像素半透明是否可用。不支持时上层要降级为不透明底色。
     *
     * ⚠️ 这个能力是**按 `GraphicsDevice` 查询**的（每个显示器可以不同，
     * 例如主屏支持而某个远程桌面会话不支持），并**不是**
     * `GraphicsEnvironment` 上的方法 —— 故必须先取默认屏幕。
     * 取不到设备（无头环境）时按"不支持"处理，走降级路径。
     */
    private fun supportsPerPixelTranslucency(): Boolean = runCatching {
        val device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        device.isWindowTranslucencySupported(
            java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
        )
    }.getOrDefault(false)

    // ── 强类型读设置（避免在业务代码里散落 descriptor 查找） ──────────────

    /**
     * 读布尔设置。**设置系统为 null 或键不存在时回退注册表默认值**。
     *
     * 为什么不直接用 `SettingsSnapshot.boolean(key)`（无 descriptor 的重载）：
     * 那个重载在键不存在时**返回 false**，而这里需要的是**该项的默认值**
     * （如 `island.always_on_top` 默认 `true`）。用 descriptor 读才能保证
     * 「插件启动时设置系统坏了」这种降级场景下拿到的是合理的默认外观，
     * 而不是一堆 `false` 导致窗口不置顶、不显示。
     */
    private fun boolOf(fresh: SettingsSnapshot, key: String): Boolean {
        val descriptor = SettingRegistry.descriptor(key) ?: return false
        return runCatching { fresh.boolean(descriptor) }.getOrDefault(descriptor.defaultValueBoolean())
    }

    private fun intOf(fresh: SettingsSnapshot, key: String): Int {
        val descriptor = SettingRegistry.descriptor(key) ?: return 0
        return runCatching { fresh.int(descriptor) }.getOrDefault(descriptor.defaultValueInt())
    }

    private fun textOf(fresh: SettingsSnapshot, key: String): String {
        val descriptor = SettingRegistry.descriptor(key) ?: return ""
        return runCatching { fresh.text(descriptor) }.getOrDefault(descriptor.defaultValueText())
    }

    /**
     * 把 `controls.*` 的 8 个键读成一个不可变配置。
     *
     * ## 为什么每次都重新构造（而不是缓存成字段）
     *
     * 它被调用的时机是"鼠标事件 / 悬停 tick"，频率是每秒几十次；构造它只是 8 次
     * map 查找 + 一次对象分配，量级是纳秒到微秒 —— 与"命中测试 4 μs"同阶，
     * 完全不值得缓存。而缓存会引入一个真实风险：用户在设置窗口改 `controls.gapPx` 后
     * 若漏了失效某一处缓存，就会出现"控件画在一处、点不中另一处"。
     *
     * 兜底用 [ControlsConfig.DEFAULTS]（注册表默认值）而不是 `false`/`0`：
     * 与 [boolOf] 的理由一致 —— 设置系统坏了时应当拿到"合理的默认外观"，
     * 而不是一堆零导致控件条高度算成 0、什么都看不见。
     *
     * ## ⚠️ 2026-09-17：**已删除**「贴近屏幕底边时自动翻转 BOTTOM → TOP」
     *
     * **原记**（`recon/23` §6.2 的 U4）：词岛贴近屏幕底边时，向下加高出来的控件条会跑到
     * 屏幕外、用户看不到它 → 于是在这里按 `ControlsPlacement.flipToTopWhenClipped`
     * 把 `position` 翻成 `TOP`。
     *
     * **实为**（用户原话）：「你是不是从灵动岛 0.8.9 那吸收了**词岛的自动翻转**？
     * （在任务栏边界）**给我取消掉这个功能！**」
     *
     * 即：**这个"自动"本身就是问题** —— 用户把词岛摆在任务栏附近是**有意的**，
     * 而插件在他没要求的情况下**把控件条换到了另一边**。行为不可预期，
     * 比"控件条在屏幕外"更让人困惑。
     *
     * **删掉之后为什么反而更好**：配合 [applyWindowSize] 的"`TOP` 时窗口向上生长"
     * （同一天的另一处修复），**选 `TOP` 不再会让歌词位移** ——
     * 于是"贴任务栏"这个场景有了一个**用户自己可选、且无副作用**的解法，
     * 不再需要插件替他做决定。
     *
     * ⚠️ **代价（如实记录）**：`position = BOTTOM` 且词岛贴着任务栏时，
     * 控件条**会有一部分落在屏幕外**。这是用户明确选择的取舍 ——
     * 需要时把 `controls.position` 设为「上方」即可。
     */
    private fun controlsConfig(fresh: SettingsSnapshot = snapshot): ControlsConfig {
        val defaults = ControlsConfig.DEFAULTS
        return ControlsConfig(
            triggerMode = textOf(fresh, "controls.triggerMode").ifEmpty { defaults.triggerMode },
            enterDelayMs = intOf(fresh, "controls.enterDelayMs").toLong(),
            exitDelayMs = intOf(fresh, "controls.exitDelayMs").toLong(),
            hitPaddingPx = intOf(fresh, "controls.hitPaddingPx").toFloat(),
            position = textOf(fresh, "controls.position").ifEmpty { defaults.position },
            gapPx = intOf(fresh, "controls.gapPx").toFloat(),
            showProgress = boolOf(fresh, "controls.showProgress"),
            autoHideMs = intOf(fresh, "controls.autoHideMs").toLong(),
            // ★ 2026-09-27 诉求 ⑭（进度条形态）。默认取 `CLASSIC` ——
            //   与注册表默认值一致（两边都写「经典」才是"默认值稳"）；
            //   `ifEmpty` 兜住"设置系统不可用 / 键缺失"的路径（与上面几行同构）。
            progressStyle = textOf(fresh, "controls.progress_style").ifEmpty { defaults.progressStyle },
        )
    }

    // ── 「贴近屏幕底边时自动翻转 BOTTOM → TOP」：**已删除**（2026-09-17）──────
    //
    // 原记：`resolveControlsPosition(cfg)` —— 展开时读屏幕工作区，若加高后的窗口底边
    //   超出工作区，就把 `position` 翻成 `TOP`（决策在 `window/ControlsPlacement.kt`，
    //   那个文件也已随本函数一起删除）。
    //
    // 实为（用户原话）：「你是不是从灵动岛 0.8.9 那吸收了**词岛的自动翻转**？
    //   （在任务栏边界）**给我取消掉这个功能！**」
    //
    // 处置：**整个函数与 `ControlsPlacement.kt` 一并删除**（不是加开关关掉）——
    //   用户要的是"这个功能不存在"，留一个默认关闭的开关等于把复杂度留着。
    //
    // 为什么删掉之后体验反而更好：见 `controlsConfig` 的 KDoc ——
    //   配合 `applyWindowSize` 的"`TOP` 时窗口向上生长"，选 `TOP` 不再让歌词位移，
    //   于是"贴任务栏"这个场景有了用户自己可选、且无副作用的解法。
    //
    // ⚠️ 若将来有人想把它加回来：**先问用户**。它的存在本身就是"插件替用户做决定"，
    //   而这类决定在边界上必然有用户不满意的时候（这正是本条被砍的原因）。

    // ── 自绘面板 ─────────────────────────────────────────────────────────

    // ★ 2026-09-28：`OwnFrame` 已**上移到 `render/OwnFrame.kt`**（顶层类）。
    //
    // 原记：它是 `IslandWindow` 的嵌套类，理由是"Kotlin 不允许 `inner class` 内嵌 `class`，
    //      而 `IslandPanel` 是 `inner class`" ⇒ 只能挂在 `IslandWindow` 层级。
    // 实为：**这个理由只回答了"不能放哪"，没回答"该放哪"**。真正决定位置的是
    //      **依赖方向** —— L3 采集侧（`render/BackdropCapture`）要消费 `OwnFrame`
    //      做 S1 判据，若它留在 `window/`，`render/` 就得 import `window/`，
    //      而本项目分层恒为 `window/` → `render/`（反向一条都没有）。
    //      放一个反向依赖 = 埋环：单独编译 `render/`（探针/单测）时会发现拉不出来。
    // ⇒ 语义上它是"**自身画面**"这个渲染概念（与"窗口"无关），归 `render/`。
    //    `window/` 侧 import 使用（见文件头 import `dev.cdl.render.OwnFrame`）。

    // ── LinearBrushMemo：频谱笔刷缓存槽（B2a · 2026-10-08）─────────────
    /**
     * **「按多个键记住一支笔刷」**的缓存槽 —— 频谱区域三处笔刷共用同一套纪律。
     *
     * ## 为什么要有这个类（B2a · 2026-10-08）
     *
     * `IslandWindow.kt` 里曾有 **11 个** 字段（`backgroundSpectrumPaint*` /
     * `backgroundSpectrumProgressPaint*` / `solidProgressPaint*`）住在本类（外层
     * `IslandWindow`）上，只为了给 `IslandPanel` 的 3 个笔刷构造函数当缓存槽。
     * 那 11 个字段**只在那 3 个函数里读写**（实测：该 panel 其余 37 个成员函数
     * 一次都不碰）⇒ 它们不是"跨函数耦合"，而是**那 3 个函数自己的一组状态**
     * 被错放到了外层类上（`tools/measure_outer_refs.py` 据此把它们算成
     * "隐式外层引用"，即拆分阻力 —— 而那是个**假阻力**）。
     *
     * ⇒ 把它们收回**使用处附近**：状态随函数走，外层引用数 **33 → 22**。
     *
     * ## 为什么不做成"一个大状态对象"
     *
     * 归拢成 `panelState.xxx` 那种做法**只降数字、不降耦合**：这些字段本就只服务
     * 一个函数，打包后那个函数仍要读同一个对象，却顺带能看见无关字段
     * （耦合面**变大**）。故本类只承载**真正属于同一件事**的状态：
     * 「一支按 (几何, 颜色, alpha) 三键记忆的笔刷」。
     *
     * ## ★★ 为什么这类**不能**写在 `IslandPanel` 里面（本批踩过的坑）
     *
     * 首次实现把本类写在 `IslandPanel` 内，编译**当场失败**：
     * ```
     * e: IslandWindow.kt:6273:17 'Class' is prohibited here.
     * ```
     * ★ **根因**：`IslandPanel` 是 **`inner class`**，而 **Kotlin 禁止在 `inner class`
     * 里声明嵌套 `class`**（语言级限制）—— ★★ 这正是 **B4「去 `inner`」想解除的那条
     * 限制**（`migration/99` §4 记它「已造成实际伤害」：3 个类型为此被迫住到文件级）。
     *
     * ⇒ 本类声明在**外层 `IslandWindow`**（普通 `class`，可以嵌套），
     *   而**实例**仍由 `IslandPanel` 持有（`private val xxxBrush = LinearBrushMemo(…)`）
     *   ⇒ ★ 实例算 `IslandPanel` 自己的字段、**不计入外层引用**，归拢目标照样达成。
     *
     * ## 为什么是 `private class`（非 `inner`）
     *
     * 它**不该**触碰任何实例成员 —— 颜色查表由构造参数 [colorOf] 注入。
     * 非 `inner` 让这条约束**由编译器执行**（写错就编译不过），而不是靠"记得别写"。
     *
     * ## 线程
     *
     * 与它服务的 3 个函数相同：**只在 EDT 上使用**（`paintComponent` 链路）。
     */
    private class LinearBrushMemo(
        /** 取色回调 —— 生产上恒为 `renderCache.color`（`packed` → `Color`）。 */
        private val colorOf: (Int) -> Color,
        /** 底部不透明度（`SPECTRUM_BACKGROUND_ALPHA`）。 */
        private val baseAlpha: Float,
    ) {
        private var cached: Paint? = null
        private var geomKey: Long = 0L
        private var colorKey: Int = 0
        private var alphaKey: Int = -1

        /**
         * 「底部 [baseAlpha] 不透明、顶端一小段淡到全透明」的**纵向渐变**笔刷。
         *
         * 三键记忆：`geomKey`（几何）+ `colorKey`（颜色）+ `alphaKey`（不透明度）。
         *
         * ⚠️ **`alphaKey` 不能省** —— 否则用户拖"差值 / 进度色差"滑杆时命中旧笔刷、
         *    画面纹丝不动（本项目记过的"设置改不动"经典症状）。
         */
        fun verticalFade(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,
            alphaScale: Float = 1f,
        ): Paint {
            val geomKey = geomKeyOf(left, bottom, top)
            val alphaKey = (alphaScale * 100f).toInt()
            val cached = this.cached
            if (cached != null && this.geomKey == geomKey &&
                this.colorKey == packed && this.alphaKey == alphaKey
            ) {
                return cached
            }
            val paint = buildVerticalFade(packed, left, bottom, top, alphaScale)
            this.cached = paint
            this.geomKey = geomKey
            this.colorKey = packed
            this.alphaKey = alphaKey
            return paint
        }

        /**
         * **实心**进度色差笔刷（`spectrum.fade_top = false` 时用）。
         *
         * 两键记忆（`colorKey` + `alphaKey`）—— 它没有几何（整条实心填充），
         * 故不参与 [geomKey]。★ 与 [verticalFade] 共用同一套槽位是**有意的**：
         * 两者在同一帧里只会用到一支（`fade_top` 决定走哪条路），
         * 不会互相顶掉（区别于"本色 / 已唱色"那对**同时使用**的笔刷，见下）。
         */
        fun solidProgress(packed: Int, alphaScale: Float): Paint {
            val key = (alphaScale * 100f).toInt()
            val cached = this.cached
            if (cached != null && this.colorKey == packed && this.alphaKey == key) {
                return cached
            }
            val base = colorOf(packed)
            val paint = Color(
                base.red, base.green, base.blue,
                (255 * baseAlpha * alphaScale.coerceIn(0f, 1f)).toInt(),
            )
            this.cached = paint
            this.colorKey = packed
            this.alphaKey = key
            return paint
        }

        /** 两段键里的**几何**那一半。位域一旦抄成两份，症状是「缓存永不命中」。 */
        private fun geomKeyOf(left: Float, bottom: Float, top: Float): Long =
            (left.toInt().toLong() shl 42) or
                (bottom.toInt().toLong() shl 21) or
                top.toInt().toLong()

        /**
         * 构造一条"底部不透明、**只在顶端一小段**淡到全透明"的纵向渐变。
         *
         * 淡出是背景频谱**能压在歌词下面而不干扰阅读**的前提。两个调用点
         * （本色 / 已唱色）必须用**完全相同**的几何与 alpha，否则两段接缝处
         * 会出现一条可见的亮边 ⇒ ★ 这正是把它收进**同一个类**的理由之一。
         *
         * ## 淡出带宽度（2026-09-23 改）
         *
         * 起点由**公开纯函数** [spectrumFadeStartFraction] 按**带高**给出，
         * 淡出带恒为 `min(带高 × 0.30, 6px)`（见 [spectrumFadeBandPx]）——
         * 即"**只作用于顶端**"。改动前起点是常量 `0.65f`（淡出带 = 带高的 35%），
         * 那正是用户报的「非得从根部起算」。
         *
         * ⚠️ **每个带只构造 1 个** `LinearGradientPaint`（由调用方缓存）——
         *    用户明确否掉了"逐柱一支"（「每根柱顶设置淡化导致效能开销大」）。
         */
        private fun buildVerticalFade(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,
            alphaScale: Float,
        ): Paint {
            val base = colorOf(packed)
            val solid = Color(
                base.red, base.green, base.blue,
                (255 * baseAlpha * alphaScale.coerceIn(0f, 1f)).toInt(),
            )
            val clear = Color(base.red, base.green, base.blue, 0)
            return LinearGradientPaint(
                left, bottom, left, top,
                floatArrayOf(0f, spectrumFadeStartFraction(bottom - top), 1f),
                arrayOf<Color>(solid, solid, clear),
            )
        }
    }

    /**
     * 内容面板 —— 圆角背景 + 歌词文字，全部自绘。
     *
     * 关于「为什么要 `isOpaque = false`」：`JPanel` 默认 `opaque = false`，
     * 但一旦有人给它设了 `background` 并忘了这点，Swing 会先填满整个矩形，
     * 把上层窗口的透明区域盖成实心 —— 这是「莫名其妙有黑边」最常见的原因。
     * 故这里**显式写出来**，并保留注释，防止将来被"优化"掉。
     */
    private inner class IslandPanel : JPanel() {

        /** 当前外观模型。**只在 EDT 上写**（由 [applyAppearance] 设置）。 */
        @Volatile
        var appearance: Appearance = Appearance.from(snapshot)

        /**
         * **本色**背景频谱的纵向渐变笔刷缓存（`spectrum.fade_top = true` 时用）。
         *
         * ★ B2a（2026-10-08）：原先是外层 `IslandWindow` 上的 4 个字段
         * （`backgroundSpectrumPaint` / `*GeomKey` / `*ColorKey` / `*AlphaKey`），
         * 但它们**只被本类的 [`backgroundSpectrumPaint`] 读写** ⇒ 收回此处。
         */
        private val backgroundBrush = LinearBrushMemo(
            colorOf = { renderCache.color(it) },
            baseAlpha = SPECTRUM_BACKGROUND_ALPHA,
        )

        /**
         * **已播放段**的纵向渐变笔刷缓存（`spectrum.progress_tint` 打开时用）。
         *
         * ⚠️ 必须与 [backgroundBrush] **各存一份**：两者在**同一帧内同时使用**
         * （本色画整条 → 已唱色重画已播放段）。共用一个槽位会每帧互相顶掉、
         * 命中率恒为 0（比不缓存还差）。同上，原先是外层的 4 个字段。
         */
        private val progressBrush = LinearBrushMemo(
            colorOf = { renderCache.color(it) },
            baseAlpha = SPECTRUM_BACKGROUND_ALPHA,
        )

        /**
         * **实心**进度色差笔刷缓存（`spectrum.fade_top = false` 时用）。
         *
         * ★ 与 [progressBrush] 可**共用槽位**：`fade_top` 决定同一帧只会走两条路之一，
         * 不会像"本色 / 已唱色"那样同帧并用。同上，原先是外层的 3 个字段。
         */
        private val solidProgressBrush = LinearBrushMemo(
            colorOf = { renderCache.color(it) },
            baseAlpha = SPECTRUM_BACKGROUND_ALPHA,
        )

        /**
         * 上一帧**实际画出来**的文字行几何 —— 字形命中测试的唯一依据。
         *
         * ## 登记点有**三处**，缺一处就是一个"点不中的槽位"（2026-09-18 补全）
         *
         * | 登记点 | 画的是什么 | 本轮状态 |
         * |---|---|---|
         * | [drawOneLine] | 主歌词（当前行 / 已唱 / 未唱） | 一直有 |
         * | [drawTranslationLine] | 译文（副歌词） | **本轮新增**（原为"有意不登记"） |
         * | [drawNoLyricsNotice] | 曲名 / 「暂无歌词」占位 | **本轮新增**（原为"有意不登记"） |
         *
         * 用户本轮原话：「完善鼠标**字形命中**，使对**曲名、作者**等非歌词也能命中
         * （**方便拖动**）」。
         *
         * ⚠️ 为什么"少登记一处"是缺陷而不是小事：`REGIONAL_CLICK_THROUGH = true`
         *   时窗口只在**命中字形或播放控件**处不穿透 —— 未登记的那一横条是**穿透空洞**，
         *   鼠标移上去既点不中也**拖不动词岛**（拖动要求窗口先收到按下事件）。
         *
         * ## 为什么必须来自"绘制"而不是"重新算一遍"
         *
         * 命中判定要的是"屏幕上那几行字画在哪"。若在鼠标事件里按设置重新推导一遍
         * （字号 → 行高 → 居中 → 基线），就出现了**第二份排版实现**；
         * 它与 `drawLyricSlots` 一旦有任何细微差别（比如某次改动只改了其中一处），
         * 结果就是"鼠标位置与命中位置差几像素"——而两边单独看都是"对"的，极难定位。
         *
         * 故这里记录的是绘制时**实际用过的** `(TextLayout, baselineX, baselineY)`，
         * 绘制与命中**共用同一份数据**，不一致在结构上不可能发生。
         *
         * ## 线程与生命周期
         *
         * 只在 EDT 上访问（`paintComponent` 写、鼠标事件读，两者都在 EDT），故不需要同步。
         * 每次 `paintComponent` 开头**整体清空** —— 这样"窗口隐藏 / 无歌词"时列表自然为空，
         * 命中恒为 false，不必再写一套失效逻辑。
         */
        private val drawnRows = ArrayList<DrawnRow>()

        /**
         * 本帧**实际采用**的高亮时序源（主歌词 / 译文各一份）—— **可观测性**用。
         *
         * ## 为什么需要它
         *
         * 用户原话：「**#7 你排查一下代码，我无法给你准确的结论，因为是靠感觉
         * （1-3 字的落后，副歌词相较主歌词又是 1-3 字）。**」
         *
         * 「译文落后主行」在本项目出现过**三次**，而三次的根因都不同：
         *   ① 时序来源不同（主行有逐字轴走逐字，译文没有 → 退回整行进度）；
         *   ② `useWordTiming` 的档位判据把 `SMOOTH` 档掐断；
         *   ③ `KaraokeTextRenderer` 里逐字分支的早退把平滑档的羽化掐断。
         * 三者**都不报错**，也都只能靠"看画面"判断 —— 于是每轮都在重新猜。
         *
         * 故这里记录绘制时**实际走过的**分支，并按**变化去重**打一行日志
         * （纪律：告警要么指名道姓、要么别打；去噪要记**集合**而不是计数）。
         * 下次再报"落后"，日志里直接能看出是**哪一侧**退回了整行、以及**为什么**。
         *
         * ⚠️ 这是**读**渲染决策、不是**重新推导**一遍决策 —— 两者分开写就又会造出
         * "第二份实现"，那正是本项目反复吃亏的形态。这里只在既有判据旁边记一句。
         *
         * 只在 EDT 上访问（与 [drawnRows] 同一线程约定），故不需要同步。
         */
        private var timingMainDesc: String? = null
        private var timingSubDesc: String? = null

        /** 上一次打出去的时序源描述；与本次相同则不打（否则每帧一行 = 噪声）。 */
        private var lastTimingDesc: String? = null

        /**
         * 上一帧**实际画出来**的封面矩形；`null` = 本帧没画封面（或本项未开启）。
         *
         * ## 为什么封面**不能**登记进 [drawnRows]
         *
         * `DrawnRow` 的载荷是 `(text, TextLayout, baselineX, baselineY)` ——
         * 它是"**一行字**"的几何，`GlyphHitTester` 要拿 `layout.advance` /
         * `getCaretShapes` 才能判定"点在哪个字上"。封面是**一张图**，
         * 没有文本也没有布局 —— 硬塞进去只能伪造一个 `TextLayout`，那是错的。
         *
         * 故封面走**独立的一份几何**（本字段），由 [hitCover] 判定；
         * 两条命中路径在调用方**取或**（见 [applyRegionalClickThrough] / `onPointer`）。
         *
         * ## 与 `drawnRows` 同一套生命周期纪律
         *
         * 只在 EDT 上访问（`paintComponent` 写、鼠标事件读）；每帧 `paintComponent`
         * 开头与 `drawnRows` **一起清空** —— 于是"本帧没画封面"时它自然为 `null`，
         * 命中恒为 false，不必再写一套失效逻辑。
         *
         * ## 为什么只在 `island.hit.cover = true` 时登记（而不是"总登记、命中时再查设置"）
         *
         * 两者等价，但**在这里判**有一个实际好处：`hitCover` 是鼠标热路径
         * （每次 `mouseMoved` / 40 ms 轮询都跑），在那里读设置会多一次快照查询；
         * 而绘制本来就是每帧一次、已经拿着 `Appearance`。**把判据放在有数据的那一侧。**
         */
        private var drawnCoverBounds: Shape? = null

        /**
         * 本帧画出的封面**矩形**（`null` = 本帧没画封面）。
         *
         * ★ 2026-09-30（封面态 S5）：为**锁头标志**新增。标志画在封面的右上角，
         *   故它必须拿到封面的实际位置 —— 而封面位置随 `bandGeometry`（展开进度）、
         *   `cover.scale_with_font`、`cover.vinyl` 环宽等**多处**变化。
         *
         * ## 为什么不复用 [hitCover]
         *
         * [hitCover] 回答的是"点**在不在**封面上"（布尔），而标志需要**具体坐标**
         * （右上角在哪）。用 `contains` 反推不出位置 ⇒ 必须暴露矩形。
         *
         * ⚠️ 返回的是**本帧实际画出**的那一份（与 [hitCover] **同源**）——
         *    这正是本项目的一贯做法："看得见的"与"点得中的"必须是同一个形状
         *    （见 [drawnCoverBounds] 的 KDoc）。若另算一份几何，就会漂移。
         */
        fun drawnCoverRect(): java.awt.geom.Rectangle2D? =
            drawnCoverBounds?.bounds2D

        // ── 长歌词水平滚动（`marquee`）：**没有状态了**（2026-09-23）───────────
        //
        // 用户原话：「当框长不足以显示全部歌词时歌词应能**自行滚动**」。
        //
        // ⚠️ 用户第 ④ 条把滑移改成**进度驱动**之后，原先的两个字段**都已删除**：
        //
        // | 原字段 | 为什么不再需要 |
        // | --- | --- |
        // | `scrollAnchors`（`ScrollAnchorTable`） | 相位锚点只服务于**时间驱动** —— 滑移现在是播放进度的纯函数（[marqueeOffsetForProgress]），没有"从什么时候开始滚"这回事 |
        // | `scrollActive`（帧驱动判据） | 滑移随进度更新，而**播放中帧驱动恒开**（[syncFrameDriver]）；暂停 ⇒ 进度不动 ⇒ 滑移也不动 ⇒ 不需要额外帧 |
        //
        // ⇒ 这两条都是"时间驱动"的残留。留着它们会让"什么时候该滚"有两个真相源。
        //
        // 历史：`scrollAnchors` 曾是**按文本分别持有**的（2026-09-21）——
        // 因为一帧里会有正文 + 译文两行各取一次锚点，共用字段会让两行互相重置。
        // 那个缺陷随锚点表一起消失（进度驱动不需要锚点）。

        /**
         * 本帧之后**是否还需要继续跑帧**（暂停时的收尾动画）。
         *
         * ## 为什么需要这个判据
         *
         * `onFrameTick` 原来的策略是"暂停即停帧"—— 那在"画面静止"的前提下是对的。
         * 但本轮加了三件**暂停后仍在动**的事：
         *
         * ① **频谱归零**：用户要求「暂停后频谱应**尽快归零**，避免缓慢衰减」，
         *    而归零是**逐帧衰减**出来的 —— 没有帧就没有衰减，柱子会僵在暂停那一刻。
         * ② **长歌词滚动**：滚动本身是时间驱动的动画，与播放态无关。
         * ③ **过渡动画**（2026-09-20 加入）：如"暂停时控件条淡出"这类收尾过渡 ——
         *    它同样与播放态无关，只看 `animator` 里还有没有在跑的过渡。
         *
         * ## 为什么"多跑几帧"不会变成"暂停后一直空转"
         *
         * ①②③都会**自行结束**：①在 [SpectralPainter.isSilent] 为真时结束
         * （0.06 s 时间常数下约 0.2 s 归零），②在歌词不再溢出时结束，
         * ③在 [AnimationRuntime] 的动画列表清空时结束。
         * 三者都结束时本方法返回 false，帧驱动自停 ——
         * "暂停后不空转"这条纪律仍然成立，只是多了几帧收尾。
         *
         * ## 为什么 `window.isShowing` 要放在最前面
         *
         * `hide_paused` 会把窗口隐藏。窗口不可见时跑动画是**纯浪费**
         * （Swing 对隐藏组件不会真的绘制，`repaint` 只是空转 EDT）。
         * 放在最前面短路，避免"暂停 + 隐藏 + 有长歌词"这种组合下永久空转。
         */
        fun needsAnimationFrames(): Boolean {
            if (!window.isShowing) return false
            // ① 频谱 —— **只要"开着 + 在播"就必须给帧**（2026-09-20 修）。
            //
            // ## 原判据（本行原文）与它造成的故障
            //
            // 原记：`if (appearance.spectrumEnabled && !SpectralPainter.isSilent(spectrumCache)) return true`
            //   —— 意图是"暂停后把柱子衰减到零，然后停帧（不空转）"。
            //
            // 实为：**一旦归零，帧驱动就停了，而停掉之后没有任何东西会在音乐重新变响时
            //   把它拉起来** → 用户看到的频谱**永久冻住**（表现为「**主副频谱不显示**」）。
            //   真机取证：`SILENT_EPSILON = 0.004f`，**任何一段安静**（间奏、轻奏、
            //   甚至换曲间隙）都足以触发。
            //
            // ## 修法：把"此刻是否静音"换成"**是否在播**"
            //
            // 音量**随时可能变响**，所以"现在静音"**不能**作为"以后都不需要帧"的依据。
            // 而在播期间本来就该有帧（`onFrameTick` 会在 `!isPlaying` 时**自停**，
            // 见 `syncFrameDriver` 的说明）—— 暂停路径由它兜住，不空转这条纪律不破。
            //
            // 于是两个意图都在：
            //   · **在播** → 永远给帧 → 音量一涨立刻有反应 ✓（修掉冻住）
            //   · **暂停** → 继续跑到归零 → 然后自停 ✓（不空转）
            if (appearance.spectrumEnabled &&
                (snapshotSupplier().isPlaying || !SpectralPainter.isSilent(spectrumCache))
            ) {
                return true
            }
            // ② ⚠️ 2026-09-23：**"有长歌词正在滚"这一条已删除** ——
            //    滑移改由播放进度驱动（[marqueeOffsetForProgress]）之后，
            //    "暂停后还要不要为滚动跑帧"这个问题**不存在**了：
            //    暂停 ⇒ 进度不动 ⇒ 滑移不动 ⇒ 画面静止。
            //    而播放中帧驱动本来就恒开（见 [syncFrameDriver] 的 `isPlaying` 分支）。
            //    （原状是"滚动与播放态无关，故暂停后仍要跑帧" —— 那正是**时间驱动**的性质。）
            // ②b 封面正在转（`cover.rotate` + 有封面 + 在播）—— 2026-09-21 新增。
            //
            // ⚠️ 这里**仍要**判 `isPlaying`：角度虽由播放位置推导（暂停 → 位置不动 →
            //    角度不变），但若只判 `coverRotate`，暂停后会为一张**静止画面**永远跑帧。
            //    这正是"逐帧动画必须配一个'谁来跑帧'的答案"的落地 ——
            //    **新动效必须在这一处登记**，否则表现是"转两下就卡住"。
            if (appearance.coverRotate && appearance.coverEnabled && snapshotSupplier().isPlaying) {
                return true
            }
            // ③ **有过渡动画在跑**（2026-09-20 引入）。
            //
            // ⚠️ 这一条必须放在**这里**（而不是让每个动效各自去启动定时器）：
            //    "谁还在动"只能有一个判据，否则新增动效时漏加一处，
            //    表现就是"动画播到一半卡住"（帧停了），而且极难定位。
            //    反过来，动画跑完后 `hasRunning()` 转 false，帧驱动**自停** ——
            //    "不空转"这条纪律对动画同样成立。
            //
            // ④ 控件带正在收缩（2026-09-22 新增）—— **它就是上面那条纪律说的
            //    "新增动效必须在这里登记"**。它不经过 `AnimationRuntime`
            //    （理由见 `startBandCollapse`），故 `hasRunning()` 看不到它；
            //    漏了这一段的表现是"暂停播放时收回控件，带子永远停在半路"。
            //
            // ⑤ 锁头标志的显示窗口期内（2026-09-30 封面态 S5）—— **同一纪律的又一例**。
            //
            //    标志是**时间敏感**的：窗口期一到它就该消失（`coverLockBadgeVisible`
            //    转 false）。若不给帧，那一帧画上标志后再没有后续帧 ⇒ 标志会
            //    **一直留在屏幕上**，直到别的原因触发一次重绘（用户眼里就是"锁标不消失"）。
            //    ⚠️ 它**不经过 `AnimationRuntime`**（那是补间动画的框架，而这里只是
            //      "到期就隐藏"）⇒ 与 `bandCollapseRunning` 同理，必须在这里单独登记。
            //    ★ 它**自会结束**：窗口期是绝对时刻，到点即 false ⇒ 不会永久空转。
            return animator.hasRunning() || bandCollapseRunning || coverLockBadgeVisible()
        }

        init {
            // 透明：本面板不填自己的背景，只在 paintComponent 里画圆角形状。
            isOpaque = false
            // 使用 Java2D 的抗锯齿 —— 圆角与文字都需要，否则边缘会有锯齿。
        }

        /**
         * 鼠标点是否命中某个**歌词字形**。
         *
         * ## 判定语义：按「字格」而不是「笔画」
         *
         * 交给 [GlyphHitTester]（其类 KDoc 记录了完整论证与实测）：
         * 按每个字符的 **advance 格**判定，而不是按墨迹轮廓 ——
         * 后者会让"鼠标停在汉字正中央"有一半概率判为未命中（汉字墨迹只占包围盒约 29%，
         * 中心常是笔画的空隙）。**这条与 `recon/13` §10.3 的原方案（`outline.contains`）
         * 相反**，原因是那份规格的实测只验证了"字间空白返回 false"，
         * 未验证"字内部中心"这一情形；`GlyphHitTester` 补测后发现原方案不可用。
         * 取证与复现见其 KDoc。
         *
         * ## 逐行判定，不逐字
         *
         * 行数最多 1（[IslandPanel.slotsFor] 恒返回单槽），每行一次"矩形粗筛 + caret 定位"，实测 4 μs 量级。
         * 鼠标在行外时第一次数值比较就返回 —— 这是"鼠标从词岛旁掠过不弹控件"的关键。
         *
         * @param padding `controls.hitPaddingPx` 的外扩量，让没对准的鼠标也能命中
         */
        fun hitGlyph(x: Float, y: Float, padding: Float): Boolean {
            for (row in drawnRows) {
                // 包 runCatching：命中测试跑在鼠标事件里，抛异常会让整个词岛停止响应鼠标
                // （与绘制路径逐行 runCatching 同一考虑）。失败按"未命中"处理。
                // 注意 `?: continue` 之后的值**必然非 null**（`null` 表示未命中，
                // 已由 continue 处理），故这里直接返回 true 即可，不必再判一次。
                runCatching {
                    glyphHitTester.hitTest(
                        cache = renderCache,
                        layout = row.layout,
                        text = row.text,
                        x = x,
                        y = y,
                        baselineX = row.baselineX,
                        baselineY = row.baselineY,
                        padding = padding,
                    )
                }.getOrNull() ?: continue
                return true
            }
            return false
        }

        /**
         * 鼠标点是否命中**封面**（`island.hit.cover = true` 且本帧画了封面时）。
         *
         * ## 为什么是独立方法而不是塞进 [hitGlyph]
         *
         * `hitGlyph` 的输入是 `DrawnRow`（`TextLayout` + 基线），语义是"点在哪个**字**上"。
         * 封面没有文本、没有布局，**混进去会让那个函数名说谎** —— 而本项目的纪律是
         * "名字说谎比没名字更糟"。故两条命中路径各自一个函数，在调用方取或：
         *
         * ```
         * panel.hitGlyph(x, y, pad) || panel.hitCover(x, y)
         * ```
         *
         * ## 判定用**本帧实际画出的形状**（见 [drawnCoverBounds] 的 KDoc）
         *
         * 于是"看得见的封面"与"点得中的封面"是同一个形状 —— 封面随字号缩放
         * （`cover.scale_with_font`）、随控件条展开而上下移动时，命中域**自动跟着走**，
         * 不需要在任何一处重算几何。
         *
         * `drawnCoverBounds` 为 `null`（没画封面 / 开关关着）时恒为 `false`，
         * 故**本方法不需要读设置** —— 判据已经在绘制侧收敛过一次（单一事实源）。
         */
        fun hitCover(x: Float, y: Float): Boolean =
            drawnCoverBounds?.contains(x.toDouble(), y.toDouble()) == true

        // 当前控件条布局。**绘制与命中判定都调它**，保证"画在哪就能点在哪"。 
        /**
         * **命中判定**用的控件几何。**展开态由 `hover.expanded` 决定** ——
         * 与改动前逐字相同：收起动画期间控件不再可点（用户已经把鼠标移开了）。
         */
        fun controlsLayout(): PlaybackControls.Layout =
            controls.layout(width.toFloat(), height.toFloat(), controlsConfig(), hover.expanded)

        /**
         * **绘制**用的控件几何。与 [controlsLayout] 的**唯一**区别是展开态判据：
         * 这里**不判任何展开态** —— 布局只有一个取值（见下）。
         *
         * ## 2026-09-22 晚：原先的 `bandReserved()` 分支**已删除**（用户第 ①② 条）
         *
         * 用户原话：
         *
         * > ① 「**改为词岛轮廓命中之后词岛一直在展开收缩反复。**」
         * > ② 「切换为控件向上方展开之后，**词岛收缩状态词岛内容消失，频谱又上移**。」
         *
         * 原先这里写的是：
         *
         * ```
         * if (!bandReserved())  Layout(lyricsTop = 0f, lyricsHeight = baseHeightPx)   // ← 就是它
         * else                  layoutOf(..., stripQ, ...)   // lyricsTop = stripQ（TOP 档）
         * ```
         *
         * 而**岛体形状**由 [bandGeometry] 给出，其 `lyricsTop` 恒为
         * `if (stripAtTop) stripQ else 0`（**与动画进度无关**）。`position = TOP` 时
         * 收起态那一支把内容区摆在面板 `[0, base]`，而岛体在 `[stripQ, stripQ+base]`
         * ⇒ 内容整体落在岛体之外、被 `g2.clip = shape` 裁掉（症状 ②）；
         * 而展开瞬间内容区**在屏幕上平移 `stripQ`**，字形命中域（`drawnRows`）跟着平移
         * ⇒ 命中判据与它自己的结果构成反馈环（症状 ①）。
         *
         * 故现在**只有一个**取值：`islandDrawLayout(...)`（公开纯函数，
         * 结构上不含 `f` / `expanded` / `bandReserved`）——
         * 与 `bandGeometry` 共用同一份"内容区"推导，收起态与展开态逐像素相同。
         * 详细论证与逐帧断言见那个函数的 KDoc 与探针 `IslandExpandHitProbe`。
         *
         * ## 那"控件只该在展开时可见"靠什么保证
         *
         * 靠**alpha**，不靠布局：`drawControls` 用 `alpha = bandFractionNow` 且
         * 在 `progress <= 0f` 时提前返回；`bandFractionNow` 在收起稳态下恒为 `0`
         * （见 [bandFractionNow]）。于是"布局恒定 + 只变透明度"这一分工
         * 正好对应"岛体形状连续变形"（`bandGeometry`）—— 三者同源。
         *
         * ## 为什么不干脆让命中判定也用这份布局
         *
         * 那会让"正在消失的按钮"在 180 ms 内仍然可点 —— 用户的手已经移开了，
         * 这时点中一个正在淡出的按钮是**误操作**。故两个判据**有意不同**：
         * **画出来的**是恒定的内容区，**能点的**（[controlsLayout]）跟用户意图走。
         */
        private fun controlsDrawLayout(): PlaybackControls.Layout {
            val cfg = controlsConfig()
            return islandDrawLayout(
                controls = controls,
                widthPx = width,
                baseHeightPx = baseHeightPx,
                stripHeightPx = controls.stripHeight(cfg),
                cfg = cfg,
            )
        }

        /**
         * 取一份**与绘制同源**的 `FontMetrics`（控件时间标签用的那个字体）。
         *
         * 给 seek 的轨道换算用 —— 见 `IslandWindow.progressTrackRect` 的说明：
         * 换算必须与绘制用同一份标签宽度，否则拖动会差一点点。
         *
         * 面板尚未 realized（`graphics == null`）时抛 `IllegalStateException`；
         * **调用方负责** `runCatching` 兜住（seek 路径已在 `progressTrackRect` 里包了）。
         * 这里不返回 null 是为了让"没有 Graphics"这件事在类型上显式 —— 返回一个
         * 随便构造的 `FontMetrics` 会让换算静默算错，那比抛异常难查得多。
         */
        fun fontMetricsOf(): java.awt.FontMetrics {
            val g = graphics ?: error("面板尚未 realized，无法取 FontMetrics")
            return try {
                g.font = PlaybackControls.labelFont()
                g.fontMetrics
            } finally {
                g.dispose()
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )
                // ★ 词岛整体淡入淡出（2026-09-20）。**乘在整个面板上** ——
                //   背景、封面、歌词、控件条一起淡，这才是"词岛整体出现/消失"。
                //
                // ⚠️ 只在 `< 1f` 时才设 composite：`AlphaComposite(SRC_OVER, 1f)` 与
                //    Java2D 的默认合成器**语义相同**，但显式设它会多走一次合成器
                //    切换。更重要的是 —— **静止时不动任何绘制参数**，
                //    保证"有动画的系统"与"关掉动画的系统"画出的像素完全一致
                //    （`animation.enabled = false` 时 `fadeAlpha` 恒为 1f）。
                if (fadeAlpha < 1f) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fadeAlpha)
                }
                // ★ 词岛整体**微缩放**（2026-09-21）。与 alpha 同在绘制入口、
                //   共用同一个进度值：两者一起构成"一边淡入、一边由小及大落位"。
                //
                // ⚠️ **绕面板中心缩放**（不是左上角）—— 绕角缩放会让词岛看起来
                //    "从左上角被拉开"，而不是"在原地由小及大"。
                // ⚠️ `g2` 是 `g.create()` 的副本（见方法开头），这里的变换不会
                //    污染外层 `Graphics`；且只在 `!= 1f` 时才执行 ——
                //    **静止时一次变换调用都不发生**，与 `fadeAlpha` 的约定一致。
                if (fadeScale != 1f) {
                    // ⚠️ 必须用 `Double`：`Graphics2D` 只提供 `translate(int,int)`
                    //    与 `translate(double,double)` 两个重载，传 `Float` 会编译失败
                    //    （Kotlin 不做 Float→Double 的隐式放宽）。
                    val cx = (this.width / 2f).toDouble()
                    val cy = (this.height / 2f).toDouble()
                    g2.translate(cx, cy)
                    g2.scale(fadeScale.toDouble(), fadeScale.toDouble())
                    g2.translate(-cx, -cy)
                }
                val local = appearance
                val width = width
                val height = height
                if (width <= 0 || height <= 0) return

                // 上一帧的命中几何立即作废：本帧若在下面某处提前 return（无歌词、
                // 词岛被禁用），列表保持为空 —— 命中恒 false 正是那时想要的语义。
                drawnRows.clear()
                // 封面命中域与文字命中域**同一处作废**（理由见 [drawnCoverBounds] 的 KDoc）。
                drawnCoverBounds = null

                // ① 封面：本帧先问一次。**非阻塞**（`coverFor` 首次必返回 null），
                //    且在图片尚未到达时会安排一次补重绘（见 [coverImageFor]）。
                //    ⚠️ 必须**先于**背景与歌词：它同时决定背景色与歌词色的解析
                //    （两个 `*_color_mode` 都读主色），以及文字区要让出多少宽度。
                val coverImage = coverImageFor(local)
                // ★ 封面交叉淡化（2026-09-20）：本帧画**哪两张图、各占多少**。
                //
                // ⚠️ 顺序是硬约束：必须在 `resolveCoverRgb` **之前**。
                //    起过渡时要把「上一帧那张图的主色」记进 `coverRgbPrev`，
                //    而紧接着的 `coverRgbShown = coverRgbRaw` 会把它覆盖掉 —— 反了就抓不到旧色。
                val coverPaint = resolveCoverMix(coverImage, local.coverEnabled)
                // 本帧**未插值**的封面主色（= 新图自己的主色）。
                val coverRgbRaw = resolveCoverRgb(coverImage)
                // ★★ 2026-10-03（⑰ F4）：`FLOW` 档的**多色**。★ 非 FLOW 档**恒返回空且不调
                //   `palette()`** ⇒ 存量用户零开销（见 [coverPaletteFor] 的两条成本控制）。
                val coverPalette = coverPaletteFor(coverImage)
                // ★★ 2026-10-03（⑰ **F2**）：流光**相位** —— ★ **由播放位置推进**（不是墙钟）
                //   ⇒ **暂停即冻结**（与水位波动 `wavePhase` / 封面转动 `coverRotationDegrees`
                //   同一条纪律）。★ `loopPhase` 只吃 `positionMs` ⇒ **调用方无法用错时钟**。
                //   ★ 非 `FLOW` 档也照算：`loopPhase` 只是一次取模 + 一次除法（纳秒级），
                //     在这里分叉反而会让"档位判断"多出一处（纪律 10：判据只此一处）。
                // ★★★ 2026-10-16：周期经**唯一换算点** `scaledFlowPeriodMs` 缩放
                //   （用户「加入对流光流速的流速调整设置」）。
                //   ★ 默认 100% ⇒ `base × 100 / 100 == base` ⇒ **逐毫秒等于改动前** ✓
                val flowPhase = loopPhase(
                    snapshotSupplier().positionMs,
                    dev.cdl.render.scaledFlowPeriodMs(FLOW_PERIOD_MS, local.materialFlowSpeed),
                )
                coverRgbShown = coverRgbRaw
                // 取色诊断（去噪，只在状态元组变化时打一行）：把「本帧到底用了什么
                // 颜色来源」变成一条可 grep 的事实。详见 [logColorSourceDiag] 的 KDoc。
                //
                // ⚠️ 喂**未插值**的值 —— 插值后的值每帧都不同，会让这条
                //    "只在变化时打一行"的诊断**退化成每帧一行**（日志刷屏）。
                logColorSourceDiag(local, coverRgbRaw)
                // ★ **跟随封面取色的色彩也跟着一起淡**（与封面图共用同一个 `coverMix`）。
                //   否则会出现"图在淡、而背景/歌词/频谱的颜色**啪**地跳成新封面的"——
                //   比不做淡化还刺眼（因为两者不同步）。
                val coverRgb = blendedCoverRgb(coverRgbRaw, coverPaint)

                // ② 计算「形状」——形态与圆角都在这里收敛成一个 java.awt.Shape。
                // ── ★★ 控件带动画期间的全部几何（2026-09-22 修「上下闪动」的后半截）──
                //
                // 用户原话（第三次报同一件事）：
                // > 「上方控件的弹出收回动画时间内词岛的上下闪动依旧，能不能彻底根治它？」
                //
                // ## 根因（结构，不是参数）
                //
                // 窗口在展开的**那一帧**就已经是展开尺寸（一次 `setBounds`），
                // 而此前 `buildShape(local, width, height)` 用的 `height` **就是窗口高度**
                // ⇒ 岛体在那**一帧**瞬间长高 `strip` px（真机 ≈ 76 px）。
                // 随后 180 ms 里只有控件条的 alpha 在变，岛体**再也不动** ——
                // 即"高度变化"整体落在动画之外，用户看到的是"啪"地跳一格。
                //
                // `bandFraction` 这个"逐帧的带子占比"字段当时**没有任何绘制代码读它**
                // （只有一处诊断计数器用），正是本项目反复栽过的形态：
                // **改了 A，但 A 的输入被 B 掐断**。
                //
                // ## 修法
                //
                // 让**岛体形状**（而不是窗口）随 `bandFractionNow` 连续变化 ——
                // 算术收在纯函数 [bandGeometry] 里（公开顶层，探针可直接断言
                // "单调 / 锚点固定 / 两端精确"三条不变量，见 `AnimationProbe` §11）。
                //
                // ⚠️ `bandFractionNow` 是**唯一**的进度来源：它已经把"展开方向由
                //    `HoverState.expansion` 驱动、收起方向由 `bandCollapseRunning` 驱动"
                //    合成成一条单调曲线（见其 KDoc）。
                val bg = bandGeometryNow()
                // ── ★★ 2026-09-22：形状**宽 / 高 / 圆角**都随展开进度连续变形 ─────────
                //
                // 用户原话（第 ① 条）：
                // > 「字形命中后展开控件改为词岛"**长大**"，**胶囊和圆角矩形长成比原来词岛
                // >   宽度略大的圆角矩形**，**R 角取适当值，不可调**。」
                //
                // ## 三条不变量的落点（都在公开纯函数里，探针可断言）
                //
                // | 量 | 定义点 | `f = 0` | `f = 1` |
                // |---|---|---|---|
                // | 宽度 | [expandWidthGrowPx] + [bandGeometry] | `baseWidthPx`（**不变**） | `baseWidthPx + 带高` |
                // | 横向落点 | [bandGeometry] `islandLeft` | 居中让出 `grow/2` | `0`（铺满窗口） |
                // | 圆角 | [islandOutlineAt] | `shape.type` 的收起态半径 | [EXPAND_CORNER_RADIUS_PX]（`SQUARE` / `NOTCH` 例外，见其 KDoc） |
                //
                // ⚠️ **窗口宽**恒为 `baseWidthPx + grow`（见 `windowTargetBounds`），
                //    与悬停态无关 —— 故本段只是"在固定窗口里连续变形"，**一次 `setBounds`
                //    都不做**（`AnimationProbe` §12 的可数判据）。
                // ⚠️ 形状**整体平移到 `(islandLeft, islandTop)`**（不是改 `g2` 的变换）——
                //    与下面"不改 `g2`"的理由相同：歌词/封面/控件都按面板坐标绘制。
                val outline = islandOutlineAt(
                    shapeType = local.shapeType,
                    cornerRadiusPercent = local.cornerRadiusPercent,
                    // 圆角基准恒取**收起态岛高**（与 `islandRadiusBasisPx` 的 KDoc 一致）：
                    // 用当前岛宽会让"宽度长大"顺带改圆角比例 —— 那是观感巨变，不是修 bug。
                    radiusBasisPx = islandRadiusBasisPx(
                        bg.islandWidth.toFloat().takeIf { it > 0f } ?: baseWidthPx.toFloat(),
                        baseHeightPx.toFloat(),
                    ),
                    fraction = bg.fraction,
                )
                val shape = AffineTransform
                    .getTranslateInstance(bg.islandLeft.toDouble(), bg.islandTop.toDouble())
                    .createTransformedShape(
                        islandShapeOf(
                            width = bg.islandWidth.toFloat(),
                            height = bg.islandHeight.toFloat(),
                            topRadiusPx = outline.topRadiusPx,
                            bottomRadiusPx = outline.bottomRadiusPx,
                            // 形态门在此压：`PILL` / `SQUARE` 恒 `ARC`（它们没有"用户圆角"这一维）。
                            cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                            cornerN = local.cornerN,
                        ),
                    )

                // ── ②′ **投影**（2026-10-01 第二批，`material.liquid.shadow_strength`）──────
                //
                // ## 为什么必须在**这里**（形状算完、背景画之前）
                //
                // 投影的语义是"面板**浮在**桌面上"⇒ 它必须在**岛体底下**。
                // 画在 ③ 之后会**盖住玻璃** —— 那不是投影，是"面板上蒙了一层灰"。
                // ★ 这也是本项**不能**塞进 `MaterialLayers.paint(...)` 的原因：
                //   那个函数是"岛体上的膜"，按定义只能画在岛体**之后**。
                //
                // ## 为什么画在 `g2` 上就自带淡入淡出
                //
                // 本方法开头对 `g2` 设过 `fadeAlpha` 的 composite（词岛整体淡入淡出）。
                // 投影画在同一个 `g2` 上 ⇒ **跟着一起淡**（不会出现"面板淡出了、影子还在"）。
                // ★ `drawDropShadow` 只改 `KEY_INTERPOLATION` 并**在 finally 里还原**，
                //   不碰 composite ⇒ 这条性质不靠约定，靠实现。
                //
                // ## 为什么也要吃 `quality == "OFF"`
                //
                // `OFF` 的语义是"**别上材质**"（`material.quality` 的 summary 逐字如此）。
                // 投影与高光/渐变/噪点/表面蒙版**同属材质**，只让它单独活着会自相矛盾。
                // ⚠️ 代价如实记录：`OFF` 时**这一层不走** `MaterialLayers.paint` 的早退路径
                //   （它在那个函数之外）⇒ 判据**必须在这里重复一次**。
                //   两处判据的一致性由 `MaterialProbe` 的 ⑫-8 断言钉住（同一 `OFF` 输入下两者都关）。
                if (local.materialQuality != "OFF" &&
                    MaterialLayers.shouldDrawDropShadow(local.materialLiquidShadowStrength)
                ) {
                    val sb = shape.bounds2D
                    // ★ 圆角取两条边的**较大者**：`NOTCH` 是"上边直角、下边大圆角"，
                    //   取上边会让阴影变成方块。投影本身是**模糊**的，圆角只是近似 ——
                    //   故这里要的是"**别明显不像**"，而不是"逐像素贴合"。
                    MaterialLayers.drawDropShadow(
                        g = g2,
                        x = sb.x, y = sb.y, w = sb.width, h = sb.height,
                        cornerRadius = maxOf(outline.topRadiusPx, outline.bottomRadiusPx).toDouble(),
                        percent = local.materialLiquidShadowStrength,
                        stripAtTop = controlsConfig().position == ControlsConfig.TOP,
                    )
                }

                // ③ 画背景：圆角矩形（或刘海形）+ 按不透明度折算的 alpha。
                //
                // ## 两条互斥的路径（`background.color_mode` 决定走哪条）
                //
                // | 档 | 画什么 | 谁处理 alpha |
                // |---|---|---|
                // | `manual` / `cover` | **纯色**（手动色 / 封面主色派生的深色） | [backgroundWithOpacity] |
                // | `cover_image` | **封面图 + 同源取色蒙版**（离屏烘焙后一次画上） | [drawCoverImageBackdrop] |
                //
                // ⚠️ 顺序是硬约束：两条路径都在取到 [coverRgb] 之后，因为 `cover` 档
                //    要它派生的深色、`cover_image` 档要它当蒙版色。
                //
                // ⚠️ **没有封面图时 `cover_image` 档退回纯色**（不是"什么都不画"）：
                //    本曲没有内嵌封面是常态，那时词岛若变成全透明，用户会以为
                //    "插件坏了"。退回手动色与 `cover` 档"无封面 → 手动色"是同一取舍。
                //
                // ── ★★ ③⁻  L3（真透视 / ④b）背景层：**最底**，两条路径之下 ──────────
                //
                // ## 为什么在最底（"先画模糊背景，再画本体色"）
                //
                // L3 的语义是"**词岛身后的窗口**被模糊后透出来"⇒ 它是**底**，
                // 而不是"叠在本体色之上的一层膜"。若画在本体色之上，本体色（不透明度
                // 通常 < 100%）会被模糊图**盖住**，用户看到的是"整块糊图"而不是
                // "词岛半透、背后是磨砂玻璃"。这正是 L2（`MaterialLayers`）与 L3 的
                // 层次关系：**L3 在底、L2 在中、内容在上**。
                //
                // ## 铁律：**没有有效背景 ⇒ 一个像素都不画**（不是"画个半透明替代"）
                //
                // `l3State?.backdrop == null` 有四种成因（未启用 / 冷启动首帧 /
                // 单帧判据拒收 / 已退纯色档）。**四者都必须落到"不画"**——
                // 于是渲染路径自然回到 L3 未启用时的样子，用户看到的是"设置还没生效"
                // 而不是"词岛坏了一半"。这也让"关掉 blur_radius"与"从未开启"
                // 两条路径的**像素完全一致**（同一个 `null` 分支），可被探针断言。
                //
                // ## 为什么用 `drawImage` 而不是每帧模糊（E5 实测，`migration/31-*` §1.1）
                //
                // 预烘焙蒙版位图 `drawImage` p50 = **0.150 ms**（与裸 `drawImage` 不可分辨）
                // ⇒ 模糊**已在采集线程**做完（10 Hz），EDT 侧**只搬一次位图**。
                // 若在这里调 `blurForBackdrop` ⇒ 每帧一次模糊 + 每帧一张 ARGB 分配，
                // 那是 `24-*` E1 已经量过的"吃掉半个帧预算"。
                //
                // ⚠️ **不裁到 `shape`**：L3 背景位图是**与岛体同尺寸**的矩形（采集侧
                //    按 `l3RegionOnScreen` 的岛体矩形裁的），而绘制原点就是岛体左上角
                //    ⇒ 位图**天然被岛体矩形框住**。真正的圆角裁切在 ④ 的 `g2.clip = shape`
                //    （见下）—— 但那是给**内容**用的；L3 若也等那时才裁，
                //    则"背景"会先铺满整个矩形再被裁，**没差**（Swing 的 clip 是硬边，
                //    但岛体圆角本来就该硬边 —— 抗锯齿由形状自身的 AA 负责，见 `ShapeMaskRaster`）。
                //    ⇒ 这里用 `setClip(shape)` 把背景裁到岛体形状，与本体色 `fill(shape)` 对齐。
                paintL3Backdrop(
                    g2 = g2,
                    local = local,
                    shape = shape,
                    islandLeft = bg.islandLeft,
                    islandTop = bg.islandTop,
                    islandWidth = bg.islandWidth,
                    islandHeight = bg.islandHeight,
                )

                if (local.backgroundColorMode == BACKGROUND_MODE_COVER_IMAGE && coverImage != null) {
                    drawCoverImageBackdrop(
                        g2 = g2,
                        local = local,
                        shape = shape,
                        src = coverImage,
                        coverRgb = coverRgbRaw,
                        // ⚠️ 2026-09-22：烘焙目标从"面板"改成**岛体**（宽/高/左边缘）——
                        //    窗口现在恒为展开宽、岛体在窗口内居中，若仍按面板烘焙，
                        //    展开时岛体右边缘之外那段"没有铺满"的会露出来。
                        //    烘焙图按**岛体矩形**生成、平移到 `(islandLeft, islandTop)`，
                        //    与歌词/封面同系。
                        // ⚠️ 缓存代价已由 [dev.cdl.render.CoverBackdrop] 的 KDoc 认定为
                        //    "有意的"（岛体尺寸变了"铺满"的定义就变了）—— 本轮把"宽"也纳入
                        //    该尺寸，故悬停动画期间同样约 11 次烘焙（与纵向既有情形一致）。
                        islandLeft = bg.islandLeft,
                        islandTop = bg.islandTop,
                        islandWidth = bg.islandWidth,
                        islandHeight = bg.islandHeight,
                    )
                } else {
                    val base = backgroundWithOpacity(local, coverRgb)
                    // ★★★ 2026-10-17（对齐 A1b）：**液态玻璃 + 有真实背景时，不要再用纯色盖住 L3**。
                    //
                    // 原链路：`L3 背景`（本段之前）→ **纯色 85% 覆盖** → **玻璃 100% 覆盖**
                    //   ⇒ L3 只剩 `15% × 0%` ≈ **看不见** ⇒ 用户看到"完全不透明"。
                    // 现链路：跳过纯色 ⇒ **L3 当底** → 玻璃以 ≤0.5 的 α 叠上去 ⇒ **真的能透**。
                    //
                    // ★ 判据收紧到三条同时成立才跳过：`LIQUID` 档 · L3 有背景 · 非 `cover_image` 分支
                    //   ⇒ 其它情况（L3 关 / 冷启动首帧 / 已退纯色档）**保持原行为**（零回归）。
                    val glassOverBackdrop =
                        local.materialGradientStyle == "LIQUID" && l3State?.backdrop != null
                    if (!glassOverBackdrop) {
                        g2.color = base
                        g2.fill(shape)
                    }

                    // ③′ **材质三层**（2026-09-25 第 2 期波次 2 · 施工单 D/E/F）─────────
                    //
                    // ## 为什么放在这里（`F §6.1` 的四条硬约束，逐条对应）
                    //
                    // | 约束 | 本处 |
                    // |---|---|
                    // | 在 `fill(shape)` **之后** | ✅ 就在它下一行 |
                    // | 在 `g2.clip = shape` **之前**（或自己裁到 shape） | ✅ 用 `setPaint` + `fill(shape)` ⇒ **自带裁到 shape**（见 [MaterialLayers] 的 KDoc） |
                    // | 在 `drawIslandBorder` **之前** | ✅ 边框在更下面画 |
                    // | 与 `cover_image` 档**互斥** | ✅ 本分支就是 `cover_image` 的 `else`（纯色路径） |
                    //
                    // ⚠️ **`base` 只算一次**并同时喂给 `g2.color` 与 `baseColor` ——
                    //    否则同一帧会把"背景色"算两遍（两次分配），且两边理论上可能不同源。
                    //
                    // ⚠️ 三层**一次画完**（高光 → 渐变 → 噪点），不要三进三出（`F §8.2` 第 3 步）。
                    MaterialLayers.paint(
                        g = g2,
                        shape = shape,
                        highlightStrength = local.materialHighlightStrength,
                        gradientStyle = local.materialGradientStyle,
                        // ★★ 2026-10-03（⑰ F1 接线）：`FLOW` 档的**多色原料**（封面取色，已缓存）。
                        //   ★ 预览侧 `IslandPreviewPane` **必须同时传**（门禁 ⓪j
                        //     `check_preview_parity` 守的就是"共同调用的函数，命名实参集合必须相等"）。
                        flowPaletteArgb = coverPalette,
                        // ★★ 2026-10-03（⑰ F2）：相位（由**播放位置**推进 ⇒ 暂停即冻结）。
                        flowPhase = flowPhase,
                        gradientTintPercent = local.materialGradientTintPercent,
                        gradientAngleDeg = local.materialGradientAngleDeg,
                        gradientEndArgb = local.materialGradientEndArgb,
                        baseColor = base,
                        noiseAmplitude = local.materialNoiseAmplitude,
                        quality = local.materialQuality,
                        // ★★ 2026-10-01「液态玻璃」子组
                        liquidGlarePercent = local.materialLiquidGlarePercent,
                        liquidDepthEffect = local.materialLiquidDepthEffect,
                        // ★ 2026-10-01 第二批：表面蒙版（**第四层**，在噪点之前画）。
                        //   它**不属逐像素** ⇒ 对 `LINEAR` 档同样生效（面板把它列为材质属性）。
                        liquidSurfaceAlphaPercent = local.materialLiquidSurfaceAlpha,
                        // ★★ 2026-10-01（BUG-148 修复）：**把岛体的真实圆角传进去**。
                        //
                        // 原记：本参数一直用默认 `-1` ⇒ 逐像素玻璃**自己推**一个圆角
                        // （`min(w,h)*0.5*0.4`），**与实际形状无关** ——
                        // 默认形态是 `SQUARE`（直角）⇒ 玻璃却被画成圆角矩形 ⇒
                        // ★ **玻璃的边缘光与岛体的角对不上**。
                        //
                        // ⚠️ **原记（2026-10-01，T5 已推翻）**：「为什么取两条边的**较大者**：
                        //   `NOTCH` 上大下小，而 SDF 只收一个半径 ⇒ 取大者（观感更主导）。
                        //   如实记录：`NOTCH` 下是**近似**。」—— ★ **那段已不成立**（见下方 T5）。
                        // ★ 与 `syncL3` 里给折射用的是**同一个式子**（见那里的注释）——
                        //   两处若漂移，"玻璃边缘"与"折射带"就会错开。
                        // ★★ T5（2026-10-02）：**双半径** —— 改前是 `maxOf(上, 下)`，
                        //   而 `shape.type = NOTCH` 是**上大下小** ⇒ 玻璃边缘与岛体轮廓**对不上**（近似）。
                        //   现在逐边传，SDF 按 `y` 相对中心选半径（见 `GlassSdf.roundedRect` 的 KDoc：
                        //   ★ 在 `y = cy` 处半径**不参与运算** ⇒ 天然连续）。
                        shapeCornerRadius = outline.topRadiusPx.toDouble(),
                        shapeCornerRadiusBottom = outline.bottomRadiusPx.toDouble(),
                        // ★★ 2026-10-01（同族第二处）：**超椭圆指数也要显式传**。
                        // 原记：`migration/68-*` 已把 roundness 定为 **5**（业界 `ShapeRoundness`
                        //   默认值），**但那个值从来没被接线** —— 本参数默认 2.0 且调用点不传
                        //   ⇒ 生产恒为 2.0，**与折射侧（`GlassSdf.DEFAULT_ROUNDNESS = 5`）不一致**
                        //   ⇒ 两条 SDF 的角不一样（"玻璃边缘"与"折射带"会错开）。
                        // ★ 显式传同一个常量 ⇒ 两处**不可能漂移**。
                        //
                        // ★★★ A-4（2026-10-09）**已核 · 同源**：
                        //   · L2（本处，词岛本体的液态玻璃）传 [dev.cdl.render.GlassSdf.DEFAULT_ROUNDNESS]。
                        //   · L3（背景折射）走 [dev.cdl.render.BackdropRefraction.Params] ——
                        //     `syncL3` 构造它时**不显式传 `roundness`** ⇒ 取该 data class 的默认
                        //     [dev.cdl.render.BackdropRefraction.ROUNDNESS]，
                        //     而它本身就是 `GlassSdf.DEFAULT_ROUNDNESS`（同一常量）。
                        //   ⇒ 两层**同一个常量、同一个值** ⇒ **不存在"边缘对不齐"的漂移** ✓
                        //   ★ 判据取 **L2 为准**（L2 是玻璃本体，L3 只是它的折射）—— 两侧本就都指向 L2 常量。
                        shapeRoundness = dev.cdl.render.GlassSdf.DEFAULT_ROUNDNESS,
                        // ★★ 2026-10-02（**T1 降级链**）：**形状正在动画** ⇒ 逐像素玻璃降采样
                        //   （13.6 → ≈3.4 ms；见 `MaterialLayers.GLASS_ANIM_SHRINK` 的 KDoc）。
                        //
                        // ★ 判据取**开区间** `(0f, 1f)`：`0` = 完全收起、`1` = 完全展开
                        //   ⇒ **两端都是稳态**、都不降级。这比"距上次变化 < N ms"那种
                        //   **自适应**判据可靠 —— 后者已被探针证伪（见该参数的 KDoc）。
                        //
                        // ⚠️ 必须用 `bg.fraction`（**与上面 `islandOutlineAt` 的 `fraction` 同源**）：
                        //   不能改用 `hover.expanded` —— 那是**目标态**、动画期间恒为 `true`
                        //   ⇒ 会退化成"展开态**一直**降采样"（静态观感也变差）。
                        glassAnimating = bg.fraction > 0f && bg.fraction < 1f,
                        // ★★★ 2026-10-17（对齐 A1）：**表面 α 上限 0.5**，源自词岛不透明度。
                        //   与参考 `MATERIAL` 层同语义：`mix(surfaceTint, backdrop, 1 − α)`。
                        surfaceOpacityPercent = local.opacityPercent,
                    )
                }

                // ★★★ 2026-10-13（**BUG-187**）：**「溶边」掩版在此应用** ——
                //   ★ 位置 = **背景（含投影）之后、内容之前** ⇒ **只淡化岛体背景** ✓
                //   ★★ 旧位置在最后（`drawIslandBorder`，所有内容之后）⇒
                //     把**歌词 / 封面 / 控件也一起淡化** ✗（用户原话：「**淡化把词岛内容也淡化了**」）
                //   ★ 为什么以前看不出来：BUG-182 时带宽只有 **8 px**、内容都在更内侧
                //     ⇒ **擦不到** ⇒ 那时"放在最后"是安全的；★★ BUG-185 把带宽拉到
                //     **半高（28 px）**后，带**够到了内容** ⇒ 症状才暴露 ✓
                //   ★ 判据：★★ **「擦除带的作用范围一旦变大，所有'它够不到'的旧假设都要重审」** ✓
                val islandBounds = shape.bounds2D
                applyDissolveMask(
                    g2 = g2,
                    local = local,
                    islandLeftPx = islandBounds.x.toFloat(),
                    islandTopPx = islandBounds.y.toFloat(),
                    islandWidthPx = islandBounds.width.toFloat(),
                    islandHeightPx = islandBounds.height.toFloat(),
                    topRadiusPx = outline.topRadiusPx,
                    bottomRadiusPx = outline.bottomRadiusPx,
                )

                // ④ 画歌词、封面与控件。**三者都必须被裁在形状内** —— 圆角/胶囊形态下，
                //    居中的长文本两端会伸到圆角外面，控件条底部的进度条也会戳出圆角，
                //    看起来像"内容戳出卡片"。clip 到 shape 是最省事且必然正确的做法
                //    （不必自己算内缩矩形）。
                val oldClip = g2.clip
                try {
                    g2.clip = shape
                    // 控件条的几何先算出来：歌词区的高度/位置由它决定（展开且 position=TOP 时
                    // 歌词整体下移一个控件条的高度）。**同一份 Layout 也用于绘制控件本身**，
                    // 于是"歌词让出的空间"与"控件占用的空间"必然吻合。
                    // ⚠️ 用 `controlsDrawLayout()`（**不是** `controlsLayout()`）——
                    //    前者在收缩动画期间仍返回展开态布局，后者会立刻返回收起态、
                    //    让控件一帧内消失。见那个函数的 KDoc。
                    val layout = controlsDrawLayout()
                    // 左侧封面区的边长（null = 不显示）。文字区要按它让出宽度 ——
                    // 于是"封面占的地方"与"文字让出的地方"由**同一个值**推导，不会错配。
                    //
                    // ⚠️ 2026-09-17：**频谱不再占左侧槽位**（用户要求"置于歌词背景"）。
                    //   于是左侧只剩封面，`areaLeft` 只由它推导 ——
                    //   连带的效果是 `cover.enabled` 与 `spectrum.enabled` **不再互斥**，
                    //   两者可以同时开（改动前封面优先、频谱连布局都不参与）。
                    // ⚠️ `coverPaint` 在**本方法开头**就算好了（那里也顺带捕获了旧主色）——
                    //    "本曲无封面 → 旧图淡出"那一段里 `shown` 已是 `null`，
                    //    槽位要靠 `prev` 保留住，故这里必须用它而不是 `coverImage`。
                    // ── ★★ 2026-09-23：封面几何收进 `BandGeometry`（用户第 ③ 条）───────
                    //
                    // 用户原话（**附截图**）：
                    // > 「**封面在词岛生长仍然与其他控件重叠。**」
                    //
                    // ## 根因（结构性，不是取值问题）
                    //
                    // 原状把封面尺寸的基准取成 `bg.islandHeight`（**岛体绘制高**），
                    // 而岛体在纵向**就是** `内容区 + 控件带` 的拼接 ⇒ `f = 1` 时封面
                    // 必然横跨进控件带。真机取证（`IslandExpandHitProbe` §E）：
                    // 重叠 **66 px**，而进度条行高只有 22 px ⇒ **必然压住进度条**。
                    //
                    // ## 修法
                    //
                    // 尺寸与落点都改由 [BandGeometry] 给出（[BandGeometry.coverOuterSizePx] /
                    // [BandGeometry.coverTopFor] / [BandGeometry.coverRect]），基准是
                    // **内容区**（与 `f` 无关）⇒ "封面与控件带不相交"成为**结构保证**，
                    // 而不是靠一个恰好取对的常量。
                    //
                    // ⚠️ **如实登记（这是本轮的一处行为收窄）**：它**移除了**
                    //    "封面随展开放大 / 下移"这一行为（用户第 ② 条曾明确要求
                    //    「随着词岛展开，封面也要跟着移动放大」）。理由不是取舍而是
                    //    **几何互斥**：岛体的生长方向**恰好朝向控件带那一侧**
                    //    （`TOP` 向上长、`BOTTOM` 向下长）⇒ 只要封面在岛体内居中、
                    //    又按岛体高定尺寸，它长出来的那一半**必然**落进控件带。
                    //    "封面生长"与"不压控件"不可能同时成立 ⇒ 取**更新的**原话（第 ③ 条）。
                    //    （若日后用户坚持要"封面生长"，唯一自洽的方向是让控件带
                    //     **不占岛体高度**（悬在岛体之外）—— 那是一个产品决策，不是几何问题。）
                    //
                    // ⚠️ `f = 0` 时 `islandHeight == lyricsHeight`（见 [bandGeometry]）
                    //    ⇒ 收起态与改动前**逐像素相同**。
                    val coverBox = bg.coverRect(local.coverScaleWithFont, LEADING_INSET)
                    val coverSize = leadingCoverSize(
                        local = local,
                        image = coverPaint.shown,
                        geometry = bg,
                        reserveWhileFading = coverPaint.prev != null,
                    )
                    // ⚠️ 尺寸不再随 `f` 变化 ⇒ "测量用（收起态）"与"绘制用"**同值**。
                    //    保留这个别名是刻意的：两条口径的分工（**测量恒取 `f = 0`**）是
                    //    "悬停全程 `setBounds == 0`"那条不变量的守卫 —— 见下。
                    val coverSizeCollapsed = coverSize
                    // ⚠️ 让位宽度必须用 **`coverSlotWidth`**（含黑胶环的槽位宽），
                    //    不是 `coverSize` 本身 —— 环画在封面**之外**时若只让出 `coverSize`，
                    //    环的右半圈会压在歌词上、左半圈会从 `LEADING_INSET` 往左戳出词岛边缘。
                    //    算术收在 [leadingCoverAreaLeftPx]（公开纯函数，真机与预览共用）。
                    //
                    // ⚠️ **相对**让位宽（不含岛体左边缘）—— 它是 `naturalWidthPx` 的输入，
                    //    而 `naturalWidthPx` 又决定岛宽；含岛体左边缘会形成
                    //    `岛宽 → 让位宽 → 自然宽 → 岛宽` 的闭环（自激反馈）。
                    //    ⇒ 绘制侧再单独加上 `bg.islandLeft`（下一行），两条口径**只差一个平移**。
                    // ★★★ 2026-10-04：**occupant / 右插槽宽 / 统一让位** —— 提到最前面算。
                    //
                    // ## 为什么必须提前（用户提醒："插槽加宽后歌词要在词岛内居中"）
                    //
                    // 歌词区 = `[岛体左 + 左让位, 岛体右 − 右让位]`，文本在**歌词区内**居中
                    // ⇒ ★ 文本中心 == 岛体中心 的**充要条件**是 **左让位 == 右让位**。
                    // ★ `RADIAL` 档右插槽宽 = 词岛高（如 76）≠ 左让位（封面 ≈56）
                    // ⇒ **不居中**（文本偏左）。
                    //
                    // ⇒ 修法：**两侧取较大者**（`lyricSideInsetPx`）—— 它同时满足
                    //   「居中」与「不重叠」（★ 把歌词区整体右移是**错解**：那只是把
                    //   "右边压插槽"换成"左边压封面"）。
                    //
                    // ⚠️ 故 `rightSlotWidthPx` 必须在 `areaLeftRel` **之前**算出来 ——
                    //   原顺序（插槽宽在 8373 才算）做不到。
                    val slotOccupantEarly = dev.cdl.render.rightSlotOccupant(
                        textOf(snapshot, "island.right_slot.component"),
                        textOf(snapshot, "spectrum.anchor"),
                    )
                    // ★★★ 2026-10-11（**BUG-165** · 第二处同源缺陷）：插槽宽**必须用歌词区高**。
                    //
                    // 原实现传 `bg.islandHeight` —— ★ 那是**动画量**（展开时 = 歌词区高 + 控件带高）
                    // ⇒ `RADIAL` 档下插槽宽**随展开从 64 长到 132** ⇒
                    // ★★ **歌词让位量在悬停时突然变宽 68 px** ⇒ 歌词区被**压窄**
                    //   （而词岛宽度是按 `baseHeightPx` 加宽的、**不变** ⇒ 两者不一致 ✗）。
                    //
                    // ★ 正解与 `polarSlotArea` **同源**：用 `layout.lyricsHeight`
                    //   （= `baseHeightPx`，**与展开动画无关**）⇒
                    //   · 画布宽 == 预留宽（第一次真正对齐）
                    //   · 悬停时歌词区**逐像素不动**
                    //   · 与词岛加宽算式（`rightSlotTotalExtraWidthPx(…, baseHeightPx, …)`）同源
                    val rightBandWidthEarly = dev.cdl.render.rightSlotWidthPx(
                        slotOccupantEarly,
                        layout.lyricsHeight,
                    )
                    /** 左让位（封面侧，相对岛体左边缘）—— 与改动前**同一个算式**。 */
                    val coverInsetRel = leadingCoverAreaLeftPx(
                        outerSizePx = coverSize ?: 0f,
                        leadingInsetPx = LEADING_INSET,
                        leadingGapPx = LEADING_GAP,
                        slotWidthPx = if (coverSize != null) {
                            coverSlotWidth(coverSize, local.coverVinyl)
                        } else {
                            0f
                        },
                    )
                    /**
                     * ★★★ **统一让位**（两侧同值）⇒ 歌词居中。
                     * ★ 非插槽档下 `rightBandWidthEarly` = 0（`NONE`）或 56（`SPECTRUM`/`CLOCK`）
                     * ⇒ `max` 取 56 ⇒ **与改动前逐像素一致**（改动前左让位 ≈ 56 也是它）✓
                     */
                    val sideInsetRel = dev.cdl.render.lyricSideInsetPx(
                        coverInsetRel,
                        if (dev.cdl.render.rightSlotOccupied(
                                textOf(snapshot, "island.right_slot.component"),
                                textOf(snapshot, "spectrum.anchor"),
                            )
                        ) {
                            rightBandWidthEarly
                        } else {
                            0f
                        },
                    )
                    val areaLeftRel = sideInsetRel
                    /** 面板**绝对**坐标下的歌词区左边缘（岛体左边缘 + 相对让位宽）。 */
                    val areaLeft = bg.islandLeft + areaLeftRel
                    /**
                     * 测量自然宽度用的让位宽 —— **恒取收起态**（`f = 0`）。
                     *
                     * ⚠️ 这是本轮"打破循环依赖"的**那一步**（见任务书第 4 问）：
                     * 封面在展开时会变大 ⇒ 让位宽变大 ⇒ 若测量也跟着变，则
                     * `naturalWidthPx` 会在**悬停动画期间**变化 → `applyNaturalWidth()`
                     * → `setBounds` → **刚根治的闪动立刻复发**。
                     * 判据一句话：**凡是能影响窗口几何的量，一律按 `f = 0` 算一次。**
                     * 于是展开只是"在一个尺寸固定的窗口里变形"，与悬停态**完全无关**。
                     */
                    val measureAreaLeft = leadingCoverAreaLeftPx(
                        outerSizePx = coverSizeCollapsed ?: 0f,
                        leadingInsetPx = LEADING_INSET,
                        leadingGapPx = LEADING_GAP,
                        slotWidthPx = if (coverSizeCollapsed != null) {
                            coverSlotWidth(coverSizeCollapsed, local.coverVinyl)
                        } else {
                            0f
                        },
                    )
                    // 文字区宽度 = **岛体右边缘** − 歌词区左边缘。
                    // **用 `coerceAtLeast(1)` 兜住**极窄窗口（`layout.max_width` 下限 280
                    // 时不会触发，但窗口在被拖动/缩放的瞬间可能出现 1–2 px 的过渡尺寸，
                    // 那时宽度算成 0 会让 TextLayout 的居中除出负坐标）。
                    //
                    // ★★ 2026-10-02（`spectrum.anchor = RIGHT`）：**本档要让出右端窄条**。
                    //   ## 为什么必须让（本轮自查发现的真问题）
                    //   频谱画在**文字之下**（见下方 ①/② 的顺序注释），而窄条占**岛体最右 35px**
                    //   ⇒ 若歌词区仍按"到岛体右边缘"算，**歌词会压住窄条** ——
                    //   而窄条是**背景装饰**、歌词是**主体** ⇒ 结果是"频谱右端被字盖掉"。
                    //   ★ 故本档把歌词区右边界收到**窄条左边缘**（`spectrumAreaLeft`）。
                    //   ⚠️ 只在 `RIGHT` 档生效 ⇒ 前两档**逐像素不变**。
                    // ★★ 2026-10-03（⑱ 时钟 · ①b）：**判据换成"插槽是否被占用"** ——
                    //   原来直接看 `spectrum.anchor == RIGHT`，现在走 `rightSlotOccupied`
                    //   （它内含**迁移**：`anchor == RIGHT` ⇒ 视为 `SPECTRUM`）。
                    //   ★ 只有**一处判据** ⇒ 歌词让位 / 词岛加宽 / 频谱绘制 不可能各判各的。
                    val rightBand = dev.cdl.render.rightSlotOccupied(
                        textOf(snapshot, "island.right_slot.component"),
                        textOf(snapshot, "spectrum.anchor"),
                    )
                    // ★★ 2026-10-03（⑱ 时钟 · ③c）：**插槽 occupant 的消费点**。
                    //   ⚠️ 现在**只算不画** —— 七段渲染器（④）与接线（⑤）还没做。
                    //   ★ 但这一步**必须现在做**：`clock.hour_format` 是个**新键**，
                    //     没有读取点就会让 `check_consumed_keys` 报红
                    //     （"声明了但没人读 ⇒ 用户改了不生效"）。
                    //   ★ 且这里的算式**就是 ⑤ 要用的那一份** —— ⑤ 只需把它交给渲染器，
                    //     不必重算（避免"两处各算一遍迟早漂移"）。
                    val slotOccupant = slotOccupantEarly
                    // ★ 时间源**只取一次** —— 文本与冒号必须用**同一个时刻**
                    //   （取两次可能跨秒 ⇒ 冒号与文本在边界那一帧不同步）。
                    val clockNowMs = if (slotOccupant == dev.cdl.render.RIGHT_SLOT_CLOCK) {
                        System.currentTimeMillis()
                    } else {
                        0L
                    }
                    val clockText: String? =
                        if (slotOccupant == dev.cdl.render.RIGHT_SLOT_CLOCK) {
                            // ★ 时间源**在此处注入**（`ClockText` 本身不读时钟 ⇒ 可被探针穷举）。
                            dev.cdl.render.ClockText.format(
                                clockNowMs,
                                java.time.ZoneId.systemDefault(),
                                textOf(snapshot, "clock.hour_format"),
                            )
                        } else {
                            null
                        }
                    // ★★ 2026-10-03（⑱ 时钟 · **冒号闪烁**）：用户「**冒号要能显示秒的跳动…一闪一闪**」
                    //   ⇒ 按秒的奇偶（`ClockText.colonVisible`）⇒ 灭的那一秒**不画但占位**。
                    val clockColonVisible = dev.cdl.render.ClockText.colonVisible(clockNowMs)
                    // ★★ 2026-10-03（⑱ 时钟 · **⑥ 字形风格**）：用户「**七段数码管、点阵、像素风**」
                    //   ⇒ 本帧用哪种字形，**只在这里读一次**，往下透传给门面。
                    //   ★ 归一化由 `ClockGlyphs.normalizeStyle` 做（未知值 ⇒ 退回七段档）——
                    //     设置项来自外部 JSON，**不能假设它合法**。
                    //   ★★ 关键：真机与预览**都只调 `ClockGlyphs`** ⇒ 新增第四种风格时
                    //     这两个调用点**一行都不用改**（结构上不可能"只改一处"，见 BUG-149/150）。
                    val clockStyle = dev.cdl.render.ClockGlyphs.normalizeStyle(
                        textOf(snapshot, "clock.style"),
                    )
                    // ★★ 2026-10-03（⑱ 时钟 · 右端插槽）：插槽宽从「5 根柱的宽（35）」提到
                    //   **封面宽（56）** ⇒ 左封面 / 右插槽**同宽** ⇒ 两端对称
                    //   （用户口径逐字见 `RIGHT_SLOT_WIDTH_PX` 的 KDoc）。
                    //   ⚠️ **词岛必须同量加宽** —— 见 `applyGeometry` / `applyNaturalWidth` 里的
                    //      `rightSlotExtraWidthPx`；否则歌词区会被压窄 21 px（= 回归）。
                    // ★★★ 2026-10-04（**静默失效修复**）：**走纯函数**，不再写死 `RIGHT_SLOT_WIDTH_PX`。
                    //
                    // ## 原写法为什么是缺陷
                    //
                    // 原来是 `val rightBandWidth = RIGHT_SLOT_WIDTH_PX`（**固定 56**）——
                    // 而 `RADIAL` 档下插槽宽 = **词岛高**（用户拍板「自适应」）
                    // ⇒ ★ 歌词让位量（56）与**实际插槽宽**（词岛高）**不一致**
                    // ⇒ 要么歌词**压住**插槽、要么插槽右边**空出一块**。
                    //
                    // ★★ 这正是「同一个量在两处各算一遍 ⇒ 漂移」的形态 ——
                    // 修法是**收敛到唯一出口**（`rightSlotWidthPx`），不是"记得两处一起改"。
                    // ⚠️ 它与 `applyGeometry` / `applyNaturalWidth` 里那两处
                    // `rightSlotExtraWidthPx(occupant, height)` **同源**（后者算的是**增量**）。
                    val rightBandWidth = rightBandWidthEarly
                    // ★★★ 2026-10-04：**右侧也用统一让位**（`sideInsetRel`）——
                    //   与左侧同值 ⇒ **歌词在岛体里居中** ✓
                    //   ★ 它恒 ≥ 插槽宽（`max` 保证）⇒ 不会压住插槽 ✓
                    val textRightEdge = if (rightBand) {
                        (bg.islandRight - sideInsetRel).coerceAtLeast(areaLeft + 1f)
                    } else {
                        bg.islandRight
                    }
                    val textWidth = (textRightEdge - areaLeft).toInt().coerceAtLeast(1)

                    // ══ 封面态（`COVER_ONLY`）：**跳过整个歌词链**（频谱 ① + 歌词 ②）══
                    //
                    // ★ 2026-09-30（封面态 S3，`recon/67` §5-⑤ / §9-… ）：
                    //   本态只画「壳 + 封面 + 曲名/作者」，**不画歌词、不画频谱**。
                    //
                    //   ⚠️ 为什么必须**显式跳过**而不是"让它自然画不出来"：
                    //     本态的 `baseHeightPx` 是**按封面口径**算的
                    //     （见 `applyGeometry` 里 `coverShapeCollapsedHeightPx` 那条分支），
                    //     与歌词块尺寸体系**无关** ⇒ 若歌词链照跑，它会画进一个
                    //     与自身无关的尺寸里（"文字露在圆角外"那类缺陷的同族）。
                    //
                    //   ⚠️ 判据走**函数**（`coverShapeSkipsLyricChain`）而不是就地写
                    //     `local.shapeType == "COVER_ONLY"` —— 本项目已多次因
                    //     "同一规则散落多处"栽跟头（纪律 11）。收成一处，且可被探针断言。
                    val skipLyricChain = coverShapeSkipsLyricChain(local.shapeType)
                    if (!skipLyricChain) {
                    // ① 频谱：画在**歌词背景**（文字之下），宽度跟随歌词区宽度 ——
                    //    故 `layout.fixed_width = false` 时它随歌词长度一起伸缩。
                    //    ⚠️ 顺序是硬约束：必须在 drawLyricSlots **之前**，否则会盖住文字。
                    //    数据可能为 null（助手未启动 / 未 READY）—— 内部会跳过绘制。
                    // ★★ 2026-10-02（`spectrum.anchor = RIGHT`）：**区域随档切换** ——
                    //   前两档占**整个歌词区**；本档缩到**岛体右端的窄带**（宽 = 5 根柱）。
                    //
                    // ★★ 只改**区域**、**不改绘制方向**（柱子仍**垂直**生长）——
                    //   这是本档成本低的原因：`spectrumBandGeometry` / `paintBars`
                    //   **一行都不用改**（它们本来就只吃"一个矩形 + 贴哪条边"）。
                    //   ⚠️ 本档下 `BOTTOM` / `TOP` 不再有意义（窄带里没有上下之分）——
                    //     内部固定按 `BOTTOM` 语义画，见 `SPECTRUM_ANCHOR_RIGHT` 的 KDoc。
                    //   ⚠️ `rightBand` / `rightBandWidth` **已在上面算歌词区宽度时定义**（本档要
                    //     让出窄条 ⇒ 两处必须用**同一个**值，不能再算一遍 —— 两处各写一遍迟早漂移）。
                    // ★★★ 2026-10-05 **BUG-156 修复**（用户截图取证：「**小插件处显示的不是五条小频谱，
                    //   而是频谱图，而且会把歌词行那里的频谱抢过去呈现（开启实时频谱再打开右侧插键槽时）**」）：
                    //
                    // ## 根因：**一个 `rightBand` 量被同时用于两件不同的事** ✗
                    //
                    // | 用途 | 该看什么 | 原来用的 |
                    // |---|---|---|
                    // | `textRightEdge`（**歌词让位**） | ★ **插槽是否被占用** | `rightBand` ✅ |
                    // | `spectrumArea*`（**频谱画在哪**） | ★★ **频谱档位是否 = `RIGHT`** | `rightBand` ❌ |
                    //
                    // ⇒ ★ 用户配置「**插槽 = 时钟** + **频谱贴底边（`anchor = BOTTOM`）**」时：
                    //   `rightBand = true`（插槽被时钟占用）⇒ ★★ **频谱区域被算成【插槽区】**
                    //   ⇒ **本该画在歌词区下方的频谱，被画进了右端插槽** ✗ —— 这就是用户说的"**抢过去**" ✓
                    //
                    // ## 正确分层（★ 与 `shouldDrawSpectrumBand` 同一条思路）
                    //
                    // ★ **"插槽归谁"只影响歌词让位；"频谱画在哪"只由频谱自己的档位决定** ✓
                    //   （★ 这已经是我第三次踩"一个开关管两层"—— 前两次是 BUG-153 与 153b）
                    // ★★ 拆成**两套区域**（本步修正上一版的缺陷）：
                    //   · `slotArea*`     ⇒ **插槽区**（**时钟**用）—— 判据 = **插槽是否被占用**（`rightBand`）✓
                    //   · `spectrumArea*` ⇒ **频谱区**（**频谱**用）—— 判据 = **频谱档位是否 = RIGHT** ✓
                    //   ⚠️ 上一版我只改了判据、没拆变量 ⇒ 会让**时钟跑到歌词区** ✗（当场发现并改正）
                    val slotAreaLeft = if (rightBand) {
                        // 右端 − 5 根柱的宽度；`coerceAtLeast(areaLeft)` 兜住"岛体太窄"的极端。
                        // ⚠️ `RIGHT_BAR_COUNT` 来自 `render/SpectrumGeometry.kt`（**公共**）——
                        //   预览侧用**同一个常量**算宽度 ⇒ 两边不可能漂移。
                        (bg.islandRight - rightBandWidth).coerceAtLeast(areaLeft)
                    } else {
                        areaLeft
                    }
                    val slotAreaWidth = if (rightBand) {
                        // ⚠️ 用 `rightBandWidth` 而**不是** `bg.islandRight - spectrumAreaLeft` ——
                        //   两者在正常情况等价，但**岛体极窄**时 `spectrumAreaLeft` 会被
                        //   `coerceAtLeast(areaLeft)` 抬高，那时两者不等 ⇒ 取**明确的窄条宽**
                        //   才能保证"窄条宽度恒等于 5 根柱"这条不变量（探针可断言）。
                        rightBandWidth.coerceAtMost(bg.islandRight - areaLeft).coerceAtLeast(1f)
                    } else {
                        textWidth.toFloat()
                    }
                    // ★★ 2026-10-03（⑱ 时钟 · ⑤）：**插槽被时钟占用 ⇒ 画时钟**。
                    //
                    // 几何：① 用 `ClockGlyphs.fitDigitWidth` 在插槽宽里反推数字宽
                    //      （★ 放不下 ⇒ `null` ⇒ **不画**，与 `highlightPaint` 同一条纪律）；
                    //      ② 纵向在插槽里**居中**；③ 逐形 `fill`。
                    // ★ 顶点由 `ClockGlyphs.textPolygons` 给出（**几何唯一来源**）——
                    //   本处只负责"摆在哪 + 填什么颜色"，不自己算字形。
                    //
                    // ★★ 2026-10-03（⑱ 时钟 · **⑥ 字形风格**）：调用点从 `SevenSegment`
                    //   **换成门面 `ClockGlyphs`** —— 三种风格共用这一处代码，
                    //   只有 `clockStyle` 这一个参数不同。
                    //   ⇒ ★ **新增风格时本处一行都不用改**（这正是门面存在的理由）。
                    // ⚠️ 本块**不得**再直接引用 `SevenSegment` —— 那会让"点阵/像素风"
                    //   在这条路径上静默退回七段（= 本项目的"两个落点只改一处"，BUG-150）。
                    if (slotOccupant == dev.cdl.render.RIGHT_SLOT_CLOCK && clockText != null) {
                        val dw = dev.cdl.render.ClockGlyphs.fitDigitWidth(
                            clockStyle, clockText, slotAreaWidth,
                        )
                        if (dw != null) {
                            val dh = dev.cdl.render.ClockGlyphs.digitHeightPx(clockStyle, dw)
                            // ★★★ 2026-10-05 **BUG-155 修复**（用户报「**显示的时钟超出词岛边缘，
                            //   在词岛右侧的空间内不居中**」）：**原来把 `spectrumAreaLeft` 当左边缘**
                            //   ⇒ 文本**左对齐**，插槽比文本宽时就偏左（观感"没居中"）。
                            //   ★ 修法 = 在插槽里**水平居中**（与下面纵向居中的写法同族）：
                            //     文本宽 = `数字宽 × totalWidthRatio`（★ 由**门面**给，不自己凑）。
                            //   ★ 且它与 `textPolygons` **同源** ⇒ "算的宽"就是"画的宽" ✓
                            val textW = dw * dev.cdl.render.ClockGlyphs
                                .totalWidthRatio(clockStyle, clockText)
                            // ★★★ 2026-10-07 **BUG-160 修复**（用户第二次报「**依旧靠近边界，依旧不居中**」）：
                            //   **上面那句"居中"在七段档下是空操作** ——
                            //   `fitDigitWidth` 反解出的数字宽**恰好**让 `textW == slotAreaWidth`
                            //   ⇒ `(slotAreaWidth − textW) / 2` **恒为 0** ⇒ 时钟仍然**贴着岛体右缘**。
                            //   ★ 取证（用户截图逐像素量，1.25×）：墨迹宽 **71**（÷1.25 ≈ **56** = 插槽宽）·
                            //     墨迹右缘 x=243 **== 岛体右缘 x=243** ⇒ **间距 0 px** ✓
                            //
                            //   ★★ 修法 = **把盒整体左移 `LEADING_INSET`**（**不是**缩小墨迹）——
                            //     缩小墨迹会让段厚掉到 `MIN_SEGMENT_PX`(1.5) 以下 ⇒ **时钟直接消失**
                            //     （56px 插槽下段厚只有 1.614，两侧共留 ≥4px 就掉线）。
                            //   ★ 左移量 = 左端的 `LEADING_INSET` ⇒ **两端对称**：
                            //     左 `14 | 封面 56 | 12` · 右 `12 | 时钟 56 | 14` ✓
                            //   ★ 且歌词区两侧让位已是 `max(82,56)=82` ⇒ 左移 14 后
                            //     歌词与时钟之间**恰好空出 12 = `LEADING_GAP`** ⇒ **词岛无需加宽** ✓
                            //   ⚠️ 第三个实参是**下限**（歌词区右边界）⇒ 保证**永不与歌词重叠**。
                            val clockBoxLeft = dev.cdl.render.clockBoxLeftPx(
                                slotLeft = slotAreaLeft,
                                insetPx = LEADING_INSET,
                                minLeftPx = textRightEdge,
                            )
                            // ★★★ 2026-10-10（审计 v4 · **A-1**）**结论：不画标记，改文案**。
                            //
                            // ## 原状（设置页对用户的**虚假承诺**）
                            //   `SettingRegistry` 的 `H12` 文案写着「12 小时制（**上下午由小标记区分**）」，
                            //   而 `ClockText.isPm` **只有 KDoc 与探针引用** ⇒ **没有任何绘制代码** ✗
                            //
                            // ## ★★ 为什么最终【不补绘制】（用户 2026-10-10 拍板）
                            //   ★ 实现时才暴露前提问题：**字形渲染器只支持 `0-9` 与 `:`**
                            //     （`SevenSegment.maskOf` / `DotMatrix.maskOf` 的 `else -> -1`）
                            //   ⇒ ★ **字母画不出来** —— 实测 `totalWidthRatio("PM") == 0`
                            //     ⇒ 标记会**静默退回「不画」** ✗
                            //   ★ 要画就得扩三张字形真值表，而**七段上 `M` 没有标准写法**
                            //   ⇒ ★ 用户拍板：**改文案、去掉承诺**（不引入半吊子的档间不一致）✓
                            //   ⇒ ★ 文案更正见 `SettingRegistry` 的 `clock.hour_format` 选项 ✓
                            //
                            // ⚠️ 本处**逐像素不变**（与改动前同一条路径）——
                            //   中间那版「补标记」的实现（`render/ClockStack.kt`）**已整体回退**，不留死代码 ✓
                            val textLeft = clockBoxLeft + (slotAreaWidth - textW) / 2f
                            val polys = dev.cdl.render.ClockGlyphs.textPolygons(
                                clockStyle,
                                clockText,
                                textLeft,
                                layout.lyricsTop + (layout.lyricsHeight - dh) / 2f,
                                dw,
                                dh,
                                // ★ 冒号闪烁：灭的那一秒**不画但占位**（整串不跳）。
                                colonVisible = clockColonVisible,
                            )
                            val oldPaint = g2.paint
                            // 颜色复用**已唱歌词色** —— 与歌词同族，且无需新键
                            // （★ 本项目纪律：**不引入新的控制项**）。
                            g2.paint = local.sungColor
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
                    // ★★★ 2026-10-05 **BUG-156**：**频谱自己的区域** —— 判据 = **频谱档位**（不是"插槽占用"）。
                    //
                    // ## 与 `slotArea*` 的分工（**这是本 BUG 的根因**）
                    //
                    // | 区域 | 谁用 | 判据 |
                    // |---|---|---|
                    // | `slotArea*` | **时钟** | ★ **插槽是否被占用**（`rightBand`）✓ |
                    // | `spectrumArea*`（本处） | **频谱** | ★★ **频谱档位是否 = `RIGHT`** ✓ |
                    //
                    // ★★ **原来只有一对、两边共用** ⇒ 用户「**插槽 = 时钟** + **频谱贴底边**」时：
                    //   `rightBand = true` ⇒ ★★ **频谱区域被算成【插槽区】** ⇒ **本该画在歌词区下方的
                    //   频谱被画进了右端插槽** ✗ —— 这就是用户报的"**把歌词行那里的频谱抢过去呈现**" ✓
                    //   （★ 用户截图取证：「**环形和放射状也是没效果（显示的仍是被抢过去的经典频谱条）**」）
                    //
                    // ★ 修法 = **拆成两套**（"一个量管两层"是本轮第三次踩，见 BUG-153 / 153b）✓
                    // ★★★ 2026-10-10（**BUG-162**）：频谱**拆成两个目标、各画一次** ——
                    //   ★★ 用户拍板（原话「**要**」）：★ 插槽选了「小频谱 / 放射」时，
                    //      **主带那条频谱【还要】** ✓
                    //
                    // ## 两个目标（★ 各自有**独立判据**，见 `SpectrumGeometry`）
                    //
                    // | 目标 | 区域 | 判据 |
                    // |---|---|---|
                    // | ① **主带**（歌词区 / 贴边） | 歌词区（`areaLeft` / `textWidth`） | `shouldDrawSpectrumBand(anchor, enabled)` |
                    // | ② **右端插槽** | 插槽条（`rightBandWidth`） | `shouldDrawSpectrumInSlot(occupant)` |
                    //
                    // ## ★★★ 改前的形态（用户两条报障的**同一根因**）
                    //
                    // ★ 只有**一次**绘制，而"画在哪"由 `isSlotBand = (spectrum.anchor == RIGHT)` 判 ⇒
                    //   ★ 用户配置（`anchor = BOTTOM` + 插槽 = 小频谱）下：
                    //     插槽**被占位**（歌词让位）却**画到了主带** ⇒ ★ **小频谱不显示** ✗
                    //   ★ 而插槽选「放射」时，同一次绘制被极坐标画布顶到插槽
                    //     ⇒ ★ **主带那条被顶掉** ⇒ 用户报的「**抢主频谱**」✗
                    //
                    // ⚠️ **不要把两个判据合回一个函数** —— ★ 它们**输入不同**
                    //   （主带看 anchor+总开关；插槽只看 occupant）⇒ 合起来就只能靠
                    //   "假装 occupant"来区分目标 ⇒ ★★ `occupant = NONE` 时两个目标判据相同
                    //   ⇒ **主带会被画两次**（更亮更密）✗ 这正是本批拆开的原因 ✓
                    val anchorNow = textOf(snapshot, "spectrum.anchor")
                    // ★★★ 2026-10-11（**BUG-165**）：插槽方形画布改由**公开纯函数**给出 ——
                    //   原实现内联在这里、且用的是 `bg.islandHeight`（**动画量**，展开时含控件带）
                    //   ⇒ 展开时圆变大一倍 + 圆心下移 34 px（实测见 `migration/125`）。
                    //   ★ 正解 = **歌词区**（与时钟 / 五条小频谱同一个落点，且与布局侧同源）。
                    // ★★★ 2026-10-12（**BUG-169b**）：★ **极坐标两档（放射 / 环状）也钳到封面盒子** ✓
                    //
                    // ★ 用户原话：「**对其他两型插槽频谱也做钳制。**」
                    // ★ 上一批（BUG-169）只钳了**五条小频谱**（直角档）——
                    //   ★ 而极坐标两档仍走 `layout.lyricsHeight`（**整个歌词区**）✗
                    // ★★ 改后三档**同一个盒子**（= 封面外径，含黑胶环）⇒
                    //   ★ 插槽里三档的**外接方框一致**（切档时观感不跳）✓
                    // ★ 注意 `polarSlotArea` 的语义：它取 `min(宽, 高) / 2` 当半径 ⇒
                    //   ★ 传封面外径后，**半径 = 封面外径 / 2** ⇒ 圆恰好内切于封面方框 ✓
                    val coverSidePx = bg.coverOuterSizePx(local.coverScaleWithFont)
                    val coverTopPx = bg.coverTopFor(coverSidePx)
                    val slotArea = dev.cdl.render.polarSlotArea(
                        slotOccupant,
                        bg.islandRight,
                        coverTopPx,
                        coverSidePx,
                        // ★★★ 2026-10-12（**BUG-175**）：★ **补上外侧内缩** —— 与左端封面同一个量 ✓
                        //   ★ 用户原话：「**我感觉右侧三型频谱的绘制位置有些偏差（即到词岛边缘的距离
                        //     不是等距的），同封面位置不完全对称**」
                        //   ★★ 改前：右边**完全贴边**（inset 0）、左边留 `LEADING_INSET`
                        //     ⇒ 正是用户说的"不等距、不对称" ✗
                        //   ★ 传**同一个常量**（`LEADING_INSET`）⇒ 两侧到岛体边缘的距离**恒等** ✓
                        insetPx = LEADING_INSET,
                    )
                    val slotRectNow = java.awt.Rectangle(
                        slotArea[0].toInt(),
                        slotArea[1].toInt(),
                        slotArea[2].toInt().coerceAtLeast(1),
                        slotArea[3].toInt().coerceAtLeast(1),
                    )

                    /** 画**一个**频谱目标（区域 / occupant 由调用方按目标给）。 */
                    fun paintSpectrumTarget(
                        areaLeftForTarget: Float,
                        areaWidthForTarget: Float,
                        occupantForTarget: String,
                        // ★★★ 2026-10-12（**BUG-169**）：★ 垂直范围**逐目标给** ——
                        //   · ★ **主带** ⇒ 歌词区（`layout.lyricsTop` / `lyricsHeight`）✓ 不变
                        //   · ★★ **右端插槽（五条小频谱）** ⇒ **封面那个盒子** ✓
                        //     （用户原话：「**我们这边的上下跳动范围限定在封面大小内，
                        //      带黑环时包括黑环**」）
                        // ★ 改前两处**共用**歌词区高度 ⇒ 小频谱能长到**整块歌词区**那么高
                        //   ⇒ 用户报「**跳动幅度和跳动范围皆缩小**」✗
                        areaTopForTarget: Float,
                        areaHeightForTarget: Float,
                    ) {
                        drawBackgroundSpectrum(
                            g2 = g2,
                            local = local,
                            // ★★★ 2026-10-05 **BUG-153 修复**（用户报「**选择5条小频谱和放射频谱等完全不显示**」）：
                            //   ★★ 本行原来是 `if (slotOccupant == RIGHT_SLOT_CLOCK) FloatArray(0) else …`
                            //   ⇒ "只要插槽放着时钟 ⇒ 整个频谱全灭" ✗
                            //   ★ 正解：**本处只负责给数据，不做取舍** —— 画不画由上面的两个判据管 ✓
                            bands = spectrumBandsSupplier(),
                            areaLeft = areaLeftForTarget,
                            areaTop = areaTopForTarget,
                            areaWidth = areaWidthForTarget,
                            areaHeight = areaHeightForTarget,
                            // ⚠️ 2026-09-22：`islandHeight` 实参**已删除** —— 副频谱锚点改由
                            //    [secondarySpectrumBands] 从 `areaTop` / `areaHeight` 算出（BUG-126）✓
                            coverRgb = coverRgb,
                            playing = snapshotSupplier().isPlaying,
                            slotRect = slotRectNow,
                            slotOccupant = occupantForTarget,
                            // 峰值保持是 `animation.quality = ULTRA` 的**独有**效果。
                            // ★★★ 2026-10-12（**BUG-173**）：★ **全面取消峰值帽的显示** ✓
                            //
                            // ★ 用户原话（逐字）：「**全面取消峰值帽的显示。**」
                            //
                            // ★ 原为 `animator.effectiveHighCostEffects`（= `animation.quality = ULTRA`
                            //   的独有高开销效果）⇒ ★ 用户无法单独关掉它 ✗
                            // ★ 现**恒 `false`** ⇒ ★ `SpectralPainter.updatePeaks` 会**清空 `peaks`**
                            //   （该函数自己的正规"关闭"路径：`false` ⇒ 清空 ⇒ **不留残留的帽**）✓
                            // ★★ **不是"传 false 绕过去"** —— 那是本特性**唯一**的开关，
                            //   且它本来就支持"关掉即无帽" ✓
                            // ⚠️ **待办（已登记台账）**：★ 峰值帽的**代码**（`updatePeaks` / `state.peaks` /
                            //   `paintBars` 的 `peaks` 参数与帽绘制段 ≈ 80 行）**尚未删除** ——
                            //   因"取消显示"已由本行完整达成，而整段删除属**结构清理**，
                            //   留待与下一批一并做（避免深夜大范围改动）✓
                            peakHold = false,
                            // ⚠️ 圆角**随展开进度变形** ⇒ 用**本帧真正用的**那两个半径（`outline`）✓
                            topRadiusPx = outline.topRadiusPx,
                            bottomRadiusPx = outline.bottomRadiusPx,
                            // ★★ 2026-10-11：封面多色 + 流光相位（`spectrum.color_mode = cover_flow`）。
                            //   ★ `coverPalette` 由 `coverPaletteFor` 按图引用缓存（`paintComponent` 里已算）。
                            coverPalette = coverPalette,
                            flowPhase = flowPhase,
                        )
                    }

                    // ① **主带** —— ★ 判据与"插槽放了什么"**无关** ✓
                    if (dev.cdl.render.shouldDrawSpectrumBand(anchorNow, local.spectrumEnabled)) {
                        paintSpectrumTarget(
                            areaLeftForTarget = areaLeft,
                            areaWidthForTarget = textWidth.toFloat(),
                            occupantForTarget = dev.cdl.render.RIGHT_SLOT_NONE,
                            // ★ 主带：**歌词区**（与改动前逐像素相同）✓
                            areaTopForTarget = layout.lyricsTop,
                            areaHeightForTarget = layout.lyricsHeight,
                        )
                    }
                    // ② **右端插槽** —— ★ 由 occupant 决定（★ 总开关不管）✓
                    if (dev.cdl.render.shouldDrawSpectrumInSlot(slotOccupant)) {
                        // ★★★ 2026-10-12（**BUG-169**）：★ **五条小频谱的垂直范围 = 封面那个盒子** ✓
                        //
                        // ★ 用户原话（逐字）：
                        // > 「五条小频谱跳动幅度和跳动范围皆缩小，对齐原实现大小」
                        // > 「**我们这边的上下跳动范围限定在封面大小内，带黑环时包括黑环**」
                        //
                        // ★★ 改前用的是 `layout.lyricsHeight`（**整个歌词区**）⇒ 条能长到整块
                        //   歌词区那么高 ⇒ **跳得又高又远** ✗
                        // ★ 正解 = 复用**封面自己的几何**（`BandGeometry`）：
                        //   · `coverOuterSizePx` ⇒ **外径含黑胶环**（用户要的"带黑环时包括黑环"）✓
                        //   · `coverTopFor` ⇒ **已在内容区内垂直居中** ⇒ 小频谱与封面**同心** ✓
                        // ★★ 两处**同一个来源** ⇒ 封面改尺寸时小频谱**自动跟着**（不可能漂移）✓
                        val coverBox = dev.cdl.render.smallSpectrumAreaPx(
                            coverTopPx = bg.coverTopFor(bg.coverOuterSizePx(local.coverScaleWithFont)),
                            coverOuterSizePx = bg.coverOuterSizePx(local.coverScaleWithFont),
                        )
                        paintSpectrumTarget(
                            areaLeftForTarget = (bg.islandRight - rightBandWidth).coerceAtLeast(areaLeft),
                            areaWidthForTarget = rightBandWidth
                                .coerceAtMost(bg.islandRight - areaLeft).coerceAtLeast(1f),
                            occupantForTarget = slotOccupant,
                            areaTopForTarget = coverBox[0],
                            areaHeightForTarget = coverBox[1],
                        )
                    }

                    // ② 歌词（画在频谱之上）。
                    // ⚠️ `measureAreaLeft` 是**收起态**的让位宽 —— 只用于自然宽度测量，
                    //    见它的定义处（那是"打破封面 ↔ 歌词区宽度循环依赖"的那一步）。
                    drawLyricSlots(
                        g2, local, textWidth, areaLeft, layout.lyricsTop, layout.lyricsHeight, coverRgb,
                        measureAreaLeft = measureAreaLeft,
                    )
                    }

                    // ── 封面态的「水位状光感」进度（★ 2026-09-30，`recon/67` §2.4）──────
                    //
                    // ★★ **位置是硬约束：必须在 `drawCover` 之前、在背景/材质之后。**
                    //   两条理由各自独立：
                    //   · **在封面之前** —— §2.4.7 A：水位 = **落在整个岛体形状内的填充**，
                    //     "封面画在水位之上"⇒ 收起态可见部分**恰好是那一圈环**（10 px）。
                    //     若画在封面之后，水位会盖住专辑图。
                    //   · **在背景之后** —— 水位是"水"，背景是"容器"；反过来会被背景盖掉。
                    //
                    // ⚠️ **不在这里做任何 clip**：本段整体已在 `g2.clip = shape` 之内
                    //    （见上方"④ 画歌词、封面与控件"那段的 `g2.clip = shape`）。
                    //    `CoverWaterLevel` 的类 KDoc 记了为什么**不**另做 `setClip`
                    //    （硬边、忽略 AA、圆角留锯齿 —— BUG-117/121 同族）。
                    //
                    // ⚠️ 几何用 `bg`（岛体绘制矩形），**不是** `layout` —— 水位的载体是
                    //    **整个岛体**（§2.4.7 A 的"一套，不是两套"）。
                    if (skipLyricChain) {
                        val waterRect = CoverWaterLevel.waterFillRect(
                            islandLeft = bg.islandLeft,
                            islandTop = bg.islandTop,
                            islandWidth = bg.islandWidth.toFloat(),
                            islandHeight = bg.islandHeight.toFloat(),
                            progress = CoverWaterLevel.progressOf(
                                snapshotSupplier().positionMs,
                                durationSupplier(),
                            ),
                        )
                        if (waterRect != null) {
                            CoverWaterLevel.paint(
                                g = g2,
                                rect = waterRect,
                                // ★ 水色由封面取色（§9-Q 定案：`CoverPalette`，与封面同源）。
                                //   ⚠️ 走 `CoverPalette.resolve` 而**不是**直接 `brightFrom` ——
                                //     `coverRgb` 是 `Int?`（null = 无封面 / 提取失败），
                                //     而 `resolve` 正是"回退"的**唯一出口**
                                //     （见它的 KDoc：分散判空会漏，收成一处就漏不掉）。
                                //     无封面时回退 `local.sungColor` —— 与歌词已唱色同源，
                                //     "水位的光"与"字的光"于是是同一个颜色族。
                                accent = CoverPalette.resolve(
                                    mode = CoverPalette.COVER,
                                    manual = local.sungColor,
                                    coverRgb = coverRgb,
                                ) { CoverPalette.brightFrom(it) },
                                bands = spectrumBandsSupplier(),
                                // ★ 相位由**播放位置**推进（不是墙钟）⇒ 暂停即冻结（§2.4.6 C-2）。
                                phase = CoverWaterLevel.wavePhase(
                                    snapshotSupplier().positionMs,
                                    COVER_WATER_WAVE_PERIOD_MS,
                                ),
                                // ★ 双色处置的判据源：**本体色的实际明度**（§2.4.7 C），
                                //   不是系统深浅色 —— 因为背景色可能被用户手动设成浅色。
                                //
                                // ⚠️ 为什么**不**复用上面 `else` 分支里的 `base`：
                                //    那个变量定义在**背景绘制的 else 分支**里，
                                //    而本段在 `g2.clip = shape` 这个**另一个作用域**内
                                //    ⇒ 根本看不见（编译期就报 Unresolved reference）。
                                // ⚠️ 为什么走 `backgroundColorFor` 而**不是** `backgroundWithOpacity`：
                                //    后者把用户设的**不透明度**乘进去了 —— 而"明度判据"要看的是
                                //    **色相本身的明暗**，与透明度无关（半透明的浅色底仍是浅色底）。
                                baseColor = local.backgroundColorFor(coverRgb),
                            )
                        }
                    }

                    // ③ 封面（独占左侧槽位，与频谱互不干涉）。
                    if (coverSize != null) {
                        // `coverPaint` 可能两张都是 null（尚无封面 / 本曲没有）——
                        // [drawCover] 会跳过绘制，但**占位空间已由 coverSize 给出**
                        // （见 [leadingCoverSize] 的取舍说明）。
                        drawCover(
                            g2, local, coverPaint, coverSize,
                            // ★ 2026-09-23：槽位矩形由 [BandGeometry.coverRect] **唯一**给出
                            //   （含横向的 `LEADING_INSET` 与纵向的内容区居中）——
                            //   与 `areaLeft` 的横向让位是**同一份几何**，
                            //   故"封面不压控件带、不压歌词"是结构性质。
                            coverLeft = coverBox.x,
                            coverTop = coverBox.y,
                            // 转动角度按**播放位置**推导（不是墙钟时间）—— 见 [coverRotationDegrees]。
                            // ⚠️ 刻意**不**复用 `effectiveMs`：那个含 `lyric.offset_ms`
                            //   （歌词轴相对音频的偏移），封面转动不该被歌词偏移影响。
                            positionMs = snapshotSupplier().positionMs,
                        )
                    }

                    // ── 封面态的「曲名 · 作者」文本（★ 2026-09-30，S3）────────────
                    //
                    // 本态**跳过了 [drawLyricSlots]**（见上方 `skipLyricChain`）——
                    // 而它正是 [drawNoLyricsNotice] 的**唯一**调用点。
                    // ⇒ 本态必须**自己调**，否则"封面 + 空壳"完全没有文字。
                    //
                    // ⚠️ 为什么走 `drawNoLyricsNotice` 而不是另写一处绘制：
                    //   字体、`ellipsizeToWidth` 截断、`alignBaselineX` 对齐、
                    //   `drawnRows` 命中登记 —— 这四样都要有。复制一份就是
                    //   把「曲名 · 作者」的**排版**再抄一遍（S2 抽 `trackCreditLine`
                    //   时消灭的是"文本"漂移，这里若复制就是"排版"漂移，同族问题）。
                    //   ⇒ 复用同一处，用 `forceShowCredits = true` 切换语义。
                    //
                    // ⚠️ 复用的是**歌词区矩形**（`areaLeft` / `layout.lyricsTop` /
                    //   `layout.lyricsHeight`）：本态没有歌词，那块区域就是
                    //   "封面右侧的空白" —— 正是文本该在的地方（`recon/67` §2.2：
                    //   展开态「曲名 / 作者」在封面右侧）。S4 的向右生长会把它撑开。
                    // ★★ 2026-10-01（封面态返工 · R5）：**收起态不画曲名/作者**。
                    //
                    // 规格 `recon/67` §2.1：S1（收起）= **只画封面 + 包裹的岛体**；
                    // §2.2：S2（展开）才露出「播放控件 + 曲名 + 作者」。
                    // ⚠️ 改前这里是**无条件**画的 ⇒ 收起态就挂着曲名/作者 ——
                    //    正是用户 2026-10-01 报的「**呈现完全不一致**」的一半。
                    //
                    // ★ 判据取 [bandFractionNow] `> 0f`（而不是 `hover.expanded`）：
                    //   本段整体已在 `g2.clip = shape` 之内 ⇒ 展开过程中文本会被
                    //   **逐渐长开的岛体**裁出来 —— 正是用户要的"向右生长、**暴露**曲名/作者"。
                    if (skipLyricChain && bandFractionNow > 0f) {
                        drawNoLyricsNotice(
                            g2, local, textWidth, areaLeft, layout.lyricsTop, layout.lyricsHeight,
                            snapshotSupplier(), coverRgb,
                            forceShowCredits = true,
                        )
                        // ── 封面锁的「锁头标志」（★ 2026-09-30，S5）────────────────
                        //
                        // 画在**封面右上角**（用户原话「命中只会在右上角弹出锁头标志」）。
                        // 位置由 `drawnCoverRect()`（本帧刚登记的实际封面矩形）推出 ——
                        // 与命中判定（[coverLockBadgeHit]）**同源**
                        // ⇒ "看得见的标志"与"点得中的标志"不可能是两个位置。
                        //
                        // ⚠️ **只在窗口期内画**（[coverLockBadgeVisible]）——
                        //    它是短暂提示，不是常驻元素（§3.5 L-2）。
                        // ⚠️ 判据含「本态 + 已锁」（[coverLockBlocksExpand]）——
                        //    与"命中被拦"同源，故不会出现"拦了展开却不弹标志"。
                        val badge = if (coverLockBadgeVisible() && local.lockCover) {
                            coverLockBadgeRect(coverBox)
                        } else {
                            null
                        }
                        if (badge != null) {
                            drawCoverLockBadge(g2, badge)
                        }
                    }
                    drawControls(g2, local, layout)

                    // ── 收尾：必要时把帧驱动**唤醒** ─────────────────────────────
                    //
                    // 为什么需要这一步：`syncFrameDriver` 只在"播放态变化"时被调用，
                    // 而**暂停后仍需要动画**的两种情形都可以在别的时刻出现 ——
                    // 例如暂停中拖动进度条换到一句很长的歌词（→ 需要开始滚动），
                    // 或者刚在设置里打开频谱（→ 需要跑几帧才画得出来）。
                    // 那时帧驱动是停的，画面会僵在半个字上。
                    //
                    // 放在最后是因为 [needsAnimationFrames] 读的是**本帧刚算出的**
                    // 频谱衰减进度。`isRunning` 判断避免每帧空调。
                    if (needsAnimationFrames() && frameTimer?.isRunning != true) {
                        syncFrameDriver()
                    }
                } finally {
                    // 必须显式写回：`Graphics2D.clip(Shape)` 是**与已有 clip 取交**
                    // （不是替换），只有写回原值才能恢复调用方设的裁剪。
                    g2.clip = oldClip
                }

                // ④ 边框（可选，设置项 `island.border.*`）。
                //
                // ⚠️ **位置是硬约束：必须在 `g2.clip = shape` 之外、且在所有内容之后。**
                //   两条理由各自独立：
                //   · 在裁剪**之外** —— 描边以轮廓线为中心向两侧各展开 `width/2`，
                //     若在裁剪内画，外侧那一半被裁掉，用户设 4 px 只看到 2 px
                //     （正是"设置值 ≠ 生效值"那类毛病）。
                //   · 在内容**之后** —— 频谱是贴边绘制的（用户明确要求"频谱贴顶边"），
                //     先画边框会被频谱盖住。
                // ⚠️ 传**岛体的绘制矩形**（`bg`），不是面板尺寸 ——
                //    动画期间面板高度恒为展开态，用它会画出一圈"比岛体大"的框。
                drawIslandBorder(
                    g2 = g2,
                    local = local,
                    islandLeft = bg.islandLeft,
                    width = bg.islandWidth,
                    islandTop = bg.islandTop,
                    height = bg.islandHeight,
                    topRadiusPx = outline.topRadiusPx,
                    bottomRadiusPx = outline.bottomRadiusPx,
                    coverRgb = coverRgb,
                )

                // ⑤ ★★ **自身渲染帧的交付**（R5 自捕获护栏的可行性前提，2026-09-27）──────
                //
                // ## 为什么需要它
                //
                // L3（真透视，`Robot` 截屏 + 模糊）有一条**必做**护栏（`migration/23-*` §3）：
                // 万一 `WDA_EXCLUDEFROMCAPTURE` **失败**，截到的帧里会含词岛自己 ⇒
                // 拿它当背景 ⇒ **R2 回授循环**（每帧把上一帧玻璃再模糊 ⇒ 糊成一团）。
                //
                // 判据 **S1**（`migration/31-*` §4.1）的核心洞察是：
                // **App 本来就知道自己上一帧渲染了什么** —— 把它与截到的帧都降到亮度、
                // 只在岛体形状内算皮尔逊相关，自捕获 ⇒ 相关 ≈ 1，排除成功 ⇒ 低。
                //
                // ⇒ 本段就是给 S1 供给那个"自己的帧"（伪代码里的 `ownLastFrame`）。
                //
                // ## 为什么落在**这里**（本方法最末尾）
                //
                // 此刻"本帧到底渲染了什么"**已全部落地**：背景（③）· 歌词/封面/控件（④）·
                // 边框（④′）都画完了。放在中间任何一处都会漏掉后面的图层。
                //
                // ## ⚠️ 三条硬约束（都与本项目既有纪律同源）
                //
                // ① **不扰动绘制路径**：本段只**读**已经算好的 `shape` / `bg` /
                //    已渲染的像素，**不执行任何绘制、不改任何 `g2` 状态**。
                //    与 `fadeAlpha` / `fadeScale` 的"静止时一次变换都不发生"是同一条约定。
                // ② **不产生每帧分配**：像素快照是**可选**的（`ownFrameCaptureArmed`），
                //    只有 L3 真正接线后才置位。**没接线时本段只记三个标量**，成本 ≈ 0。
                //    （若每帧都 `getRGB` 一整张 304×154，就是本项目栽过的
                //     "每帧 2 张 ARGB 分配 ⇒ 374 KB/帧 GC"那个坑。）
                // ③ **跨线程交付**：S1 跑在**采集/工作线程**（`migration/31-*` §4.3：
                //    判据耗时 2.6 ms **不进 EDT 预算**）⇒ 交付物必须**线程安全**。
                //    故这里写出的是 `@Volatile` 的**不可变快照对象**（一次性替换引用）。
                //
                // ## 与命中域（`drawnRows` / `drawnCoverBounds`）的区别
                //
                // 那两者是"屏幕上的字画在哪"（供鼠标命中）；本项是"整块岛体的像素"（供判据）。
                // 前者每帧必写、后者按需写 —— **两类消费者，不同频率**，故不合并。
                publishOwnFrame(
                    shape = shape,
                    islandLeft = bg.islandLeft,
                    islandTop = bg.islandTop,
                    islandWidth = bg.islandWidth,
                    islandHeight = bg.islandHeight,
                )
            } finally {
                // create() 出来的 Graphics2D 必须 dispose，否则高频重绘下
                // 会累积 native 资源（在 Windows 上表现为 GDI 对象泄漏）。
                g2.dispose()
            }
        }

        /**
         * ★ **自身渲染帧的交付点**（R5 护栏的可行性前提；见 `paintComponent` ⑤ 段）。
         *
         * ## 交付什么（两层，按需）
         *
         * | 层 | 内容 | 何时写 |
         * |---|---|---|
         * | **元数据**（恒写） | 岛体矩形 + 形状 + 帧序号 | 每帧（三个标量，零分配） |
         * | **像素快照**（按需） | 岛体区域的 ARGB 像素副本 | 仅当 [armOwnFrameCapture] 置位 |
         *
         * ★ **默认只写元数据**：L3 尚未接线（`grep Robot` 在生产代码里 0 命中）⇒
         *   现在就开始每帧拷贝像素是**纯浪费**，且会引入 374 KB/帧 的 GC 压力。
         *   ⇒ 门是"**接线了才开**"，不是"先开着等接线"。
         *
         * ## 为什么形状与矩形都要交出去
         *
         * S1 要求在**岛体形状内部**取样（不是矩形内）—— 圆角外的像素是桌面，
         * 掺进来会把相关拉低。形状是"生产真正画的那个"（`islandShapeOf` + 平移），
         * 而**蒙版栅格化要用矩形**（`bg.islandLeft/Top/Width/Height`）。
         * 两者一起给出，消费方才能既栅格化又判内外（`migration/31-*` §4.7 的 `shapeMask`）。
         *
         * ⚠️ `shape` 是**每帧新建**的 `Shape`（`:6433` 的 `createTransformedShape`）——
         *    交给别的线程持有是安全的（不可变几何），但**不要**拿它去改。
         *
         * ## 线程
         *
         * 本方法**只在 EDT 上调用**（`paintComponent` 内）。写出的是 `@Volatile` 引用
         * ⇒ 采集线程读到的是**某一个完整帧**，不会读到半个。**不做锁** ——
         * S1 容许丢帧（下一帧就补上），而锁会把 EDT 与采集线程耦合起来。
         */
        private fun publishOwnFrame(
            shape: Shape,
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ) {
            ownFrameSeq += 1L
            val armed = ownFrameCaptureArmed
            // ── 像素快照（仅接线后）─────────────────────────────────────────
            //
            // ## ⚠️ 为什么**不**在这里 `paintAll(离屏图)`（实测踩到的递归）
            //
            // 本方法是 `paintComponent` **内部**调的，而 `paintAll` 会**再走一遍**
            // `paintComponent` ⇒ `publishOwnFrame` ⇒ `paintAll` ⇒ … **无限递归**
            // （`StackOverflowError` 只是时间问题；而它发生在**渲染路径**里，
            //  表现会是"画面偶尔整块不刷新"，极难归因）。**已实测排除该写法。**
            //
            // ## 采用：`Robot` 抓**自己窗口**的岛体区域（2026-09-27）
            //
            // 三条理由，逐条对应本项目既有事实：
            // ① **零递归** —— `Robot` 是系统调用，不经过 Swing 绘制路径。
            // ② **与威胁模型完全同源** —— 判据要防的是"**截屏截到了自己**"，
            //    那么"自己的帧"最好也**用同一取景机理**拿到。用 `Robot` 抓自己，
            //    得到的正是"若排除失败，截屏会看到什么"的**上界**。
            // ③ **成本可接受且已在预算里** —— 截屏地板 8.25 ms（`migration/20-*` E1）
            //    是 L3 本来就要付的；R5 的快照是**同一量级**，
            //    且只在 [ownFrameCaptureArmed] 时付。**未接线时零成本。**
            //
            // ⚠️ **此刻抓屏拿到的是"本帧"**（`paintComponent` 即将返回、内容已上屏）——
            //    这与 S1 需要的"上一帧"**不矛盾**：交付对象叫 `ownFrame`（自身帧），
            //    判据拿它与 `capture` 比时，两者都是"同一时刻的屏上内容"，
            //    相关语义更强（→ `migration/31-*` §4.1 的"跟自己的帧比"）。
            //
            // ⚠️ **`Robot` 抓自己的窗口不受 `WDA_EXCLUDEFROMCAPTURE` 限制** ——
            //    那个标志只让**别的进程**截不到本窗口；本进程自己抓是抓得到的。
            //    这正是本方案能成立的关键（否则会抓到一个"自己消失"的空洞）。
            val snapshot: java.awt.image.BufferedImage? = if (armed) {
                captureOwnRegion(islandLeft, islandTop, islandWidth, islandHeight)
            } else {
                null
            }
            ownFrame = OwnFrame(
                seq = ownFrameSeq,
                shape = shape,
                islandLeft = islandLeft,
                islandTop = islandTop,
                islandWidth = islandWidth,
                islandHeight = islandHeight,
                // ⚠️ armed 但抓取失败 ⇒ `null` = "本帧没有像素证据" ⇒
                //    判据必须退回 `UNKNOWN`（**不得**当成"正常帧"放行）。
                pixels = snapshot,
            )
            // ★ L3：记下**本帧**岛体尺寸。`syncL3` 在**设置变化时**启动采集线程，
            //   那一刻可能还没有 `ownFrame`（窗口首帧尚未渲染、或 L3 在播放中途被打开）
            //   ⇒ 用这个缓存兜底定掩膜尺寸。之后每帧刷新，保证"下次改半径重启线程"
            //   拿到的是**最近一帧**的尺寸，而不是启动那一刻的陈旧值。
            lastIslandW = islandWidth
            lastIslandH = islandHeight
        }

        /**
         * 用 `Robot` 抓**本窗口**在屏幕上的岛体区域。
         *
         * 见 [publishOwnFrame] 的三条理由。**失败一律返回 `null`**（不抛）——
         * 判据把 `null` 当 `UNKNOWN` 处理，比"抛异常中断绘制路径"安全得多。
         *
         * ## 坐标换算
         *
         * `paintComponent` 里的 `islandLeft/islandTop` 是**面板坐标**，
         * 而 `Robot` 要的是**屏幕坐标** ⇒ 必须加上面板在屏幕上的原点。
         * `getLocationOnScreen()` 在窗口未显示时会抛 `IllegalComponentStateException`
         * （**这正是本项目 BUG-029 那一族**）⇒ 用 `runCatching` 包住。
         *
         * ⚠️ **本方法只在 EDT 上调用**（绘制路径内）—— `getLocationOnScreen` 有线程亲和性。
         */
        private fun captureOwnRegion(
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ): java.awt.image.BufferedImage? = runCatching {
            if (islandWidth <= 0 || islandHeight <= 0) return@runCatching null
            val robot = ownCaptureRobot ?: return@runCatching null
            val origin = locationOnScreen ?: return@runCatching null
            val x = origin.x + islandLeft.toInt()
            val y = origin.y + islandTop.toInt()
            // `createScreenCapture` 返回的是 `TYPE_INT_RGB`（无 alpha）——
            // S1 只用**亮度**，故无 alpha 不影响判据；但它意味着
            // "形状外 vs 形状内"的区分必须靠 `shape`，不能靠 alpha（见 `OwnFrame.shape`）。
            robot.createScreenCapture(java.awt.Rectangle(x, y, islandWidth, islandHeight))
        }.getOrNull()

        /**
         * 抓屏器（惰性单例）。
         *
         * ⚠️ **`Robot` 的构造本身会抛**（无图形环境 / 权限不足）⇒ 惰性 + `runCatching`。
         *    失败时**记住 `null` 并永久降级**（`by lazy` 只求值一次）——
         *    不重试，避免每帧抛一次异常（那会在 `super-island.log` 里刷屏）。
         *
         * ⚠️ 日志走 `System.err`：宿主启动后 `PluginLog.install` 把它重定向到
         *    数据目录的 `super-island.log`（**界面看不见**），这正是本项目既有的落点。
         */
        private val ownCaptureRobot: java.awt.Robot? by lazy {
            runCatching { java.awt.Robot() }.getOrElse { e ->
                System.err.println(
                    "[R5] 无法创建 Robot（屏幕捕获不可用）⇒ 自捕获护栏将恒为 UNKNOWN：" +
                        "${e::class.simpleName}: ${e.message}",
                )
                null
            }
        }

        /**
         * ★ **S1 判据的输入：本 App 上一帧渲染的内容**（`migration/31-*` §4.7 的 `ownLastFrame`）。
         *
         * `null` = **还没渲染过** ⇒ 判据返回 `UNKNOWN` ⇒ 按"自捕获"处理（冷启动直接纯色，
         * **不许"先糊一次"**，见 §4.6 ④）。**这个 `null` 是有语义的，不要用空图代替。**
         */
        @Volatile
        var ownFrame: OwnFrame? = null
            private set

        /** 已交付的帧序号（单调递增）。诊断用：可判"EDT 是否还在出帧"。 */
        @Volatile
        var ownFrameSeq: Long = 0L
            private set

        /** 是否要求 [publishOwnFrame] 额外拷一份像素快照。**L3 接线后才置 `true`**。 */
        @Volatile
        var ownFrameCaptureArmed: Boolean = false

        /**
         * 开关像素快照。**在 EDT 上调用**（与 `paintComponent` 同线程约定）。
         *
         * 为什么需要显式开关而不是"总是拷"：见 [publishOwnFrame] 的约束② ——
         * 未接线时每帧拷一张 ARGB 是纯浪费。**门是"接线了才开"**。
         */
        fun armOwnFrameCapture(armed: Boolean) {
            ownFrameCaptureArmed = armed
            if (!armed) ownFrame = null
        }

        // ── ★★ L3（真透视 / ④b）消费侧（2026-09-28 接线）─────────────────────
        //
        // ## 这三件各是什么
        //
        // | 成员 | 角色 | 谁写 |
        // |---|---|---|
        // | [l3State] | 采集侧发布的"当前有效模糊背景" | 采集线程（`@Volatile` 引用替换） |
        // | [l3MaskRaster] | 形状掩膜（判据用，跨帧复用） | 采集线程（单线程约定） |
        // | [l3Worker] | 采集线程本体 | EDT（start/stop） |
        //
        // ## ★ 为什么消费侧只需要"一次 `drawImage`"
        //
        // E5 实测（`migration/31-*` §1.1，`304×154`）：**预烘焙蒙版位图 `drawImage`
        // p50 = 0.150 ms**，与"裸 `drawImage`"（0.159 ms）不可分辨 ⇒ 蒙版成本已被
        // 预烘焙吸收。而 `setClip(Shape)` 既**更贵**（0.186 ms）又**圆角无抗锯齿**
        // （半透明像素恒为 0）⇒ 结构性出局。
        //
        // ⇒ 本侧**只做**：`if (l3 有有效背景) drawImage(背景, 岛体矩形)`，
        //   然后**照常**叠材质三层与歌词。**不做**任何每帧模糊 / 每帧掩膜重建。

        /**
         * L3 采集侧的状态（有效背景 + 降级链）。
         *
         * `null` = **本层未启用**（`material.backdrop.blur_radius == 0`）
         * ⇒ 渲染路径**一个分支都不多走**（与"材质三层默认零视觉变化"同一约定）。
         */
        @Volatile
        var l3State: dev.cdl.render.L3BackdropState? = null
            private set

        /** 形状掩膜（判据用）。仅在 L3 启用时创建 —— 否则白占一张 304×154 灰度图。 */
        @Volatile
        private var l3MaskRaster: dev.cdl.render.ShapeMaskRaster? = null

        /** 采集线程。仅在 L3 启用时非 null。 */
        @Volatile
        private var l3Worker: dev.cdl.render.L3CaptureWorker? = null

        /**
         * 上一次传给 [l3Worker] 的**每秒日志开关**值 —— `syncL3()` 的幂等判据之一。
         *
         * ⚠️ 为什么需要它：日志开关是**构造参数**（不可运行期改）⇒ 它一变就必须
         *    重建 worker。若不在判据里比它，用户"只勾开关不拖半径"时会被
         *    "半径没变 ⇒ return" 拦掉 ⇒ L3 那行日志永不出现（勾了没反应）。
         * 与 [l3Worker] 同寿：`syncL3` 关闭路径与 [disposeL3] 都要清它。
         */
        @Volatile
        private var lastL3FrameLogEnabled: Boolean = false

        /**
         * 上一次传给 [l3Worker] 的**折射参数指纹**（2026-10-01 ④b 第二批）——
         * `syncL3()` 的幂等判据之一。
         *
         * ⚠️ **为什么必须进判据**：与 [lastL3FrameLogEnabled] 同因 ——
         * `BackdropCaptureParams` 是**构造参数**（`L3CaptureWorker` 只在构造时读它）。
         * 若不比这一项，用户拖「折射量 / 色散 / 饱和度」时会被
         * "半径没变 ⇒ return" 拦掉 ⇒ ★ **4 根新滑杆拖了没反应**（且不报错）。
         * ★ 这是本项目「**改了 A，但 A 的输入被 B 掐断**」那一族的**标准形态**，
         *   故这里用一个**把 4 个数拼起来的指纹串**，而不是逐个比（漏一个就复发）。
         *
         * 与 [l3Worker] 同寿：关闭路径与 [disposeL3] 都要清它。
         */
        @Volatile
        private var lastL3RefractionKey: String = ""

        /**
         * L3 worker 的**幂等指纹**（含**形状几何**）—— 见 [lastL3RefractionKey] 的 KDoc。
         *
         * ★ 实现已搬到**公开纯函数** [dev.cdl.window.l3WorkerKey] ——
         *   理由与其它"抽公开纯函数"一致：本类是 `private inner class`，探针够不到，
         *   而"**指纹有没有漏项**"这条判据**写不出来**（`IslandPolicyProbe` §L3K 现已补上）。
         *
         * ★ 拼串而不是比对象：`data class` 的 `equals` 也对，但**指纹串能直接打进日志**，
         *   排查"到底哪个值变了"时不必再猜。
         *
         * ★★★ 2026-10-03：**加上 `cornerRadiusTop` / `cornerRadiusBottom`** ——
         * 这两个量取自 `islandOutlineAt(...)`，随 `shape.type` / `shape.corner_radius` /
         * 展开进度变化，而它们是**折射 SDF 的输入**。
         *
         * ⚠️ **原实现只比 4 个折射键** ⇒ 用户改「**外观形态**」或「**圆角大小**」时
         *   `syncL3` **早退、worker 不重建** ⇒ 折射仍用**旧圆角** ⇒
         *   **折射带与岛体边缘错开**（正是 `BackdropCaptureParams.cornerRadiusPx` 的
         *   KDoc 点名的后果）—— ★ **且不报错、不警告**。
         *   ★ 而逐像素玻璃侧**每帧重算**圆角 ⇒ **两边会不一致**（错开）。
         *
         * ★ 本项由用户 2026-10-03 指令「**彻查未知的因修改而静默失效的功能链路**」查出。
         * ★ 同族判据（**可复用**）：**凡是进了构造参数的量，都必须进幂等/缓存键** ——
         *   漏一个的症状是"改了设置没反应"，与"设置项本身无效"**完全一样**，极难归因。
         */
        private fun l3RefractionKey(
            local: Appearance,
            cornerRadiusTop: Double,
            cornerRadiusBottom: Double,
        ): String = dev.cdl.window.l3WorkerKey(
            blurRadius = local.materialBackdropBlurRadius,
            frameLogEnabled = local.frameLogEnabled,
            refractionHeight = local.materialBackdropRefractionHeight,
            refractionAmount = local.materialBackdropRefractionAmount,
            dispersion = local.materialBackdropDispersion,
            saturation = local.materialBackdropSaturation,
            cornerRadiusTop = cornerRadiusTop,
            cornerRadiusBottom = cornerRadiusBottom,
            // ★★ R7：GPU 后端开关也必须进指纹（同因：它只被 `L3CaptureWorker` 构造期读）✓
            gpuBackend = local.materialRenderGpuBackend,
        )

        /**
         * ★ 启动 L3（由 [applyAppearance] 在**设置变化时**调用；`blurRadius == 0` 时停止）。
         *
         * ## 为什么"开关"落在这里而不是"每帧判设置"
         *
         * 因为 L3 要开一个**线程**。每帧判设置意味着"设置改了但线程没重起"
         * 或"线程起了一堆"——两者都是静默失效（前者用户改了没反应，后者烧 CPU）。
         * 落在 `applyAppearance` ⇒ **与 `material.quality` 同一个触发点**，
         * 用户拖滑杆 → 快照变 → `applyAppearance` → 这里恰好重起一次。
         *
         * ## 为什么 `reset()` 只在"从启用变启用"时不调（只在从 0 变 >0 时调）
         *
         * 因为 [dev.cdl.render.SelfCaptureGate] 的 `PURE_COLOR` 是**吸收态**
         * （`31-*` §4.6 ③）。若每次 `applyAppearance` 都 `reset`，用户随便改个
         * 无关设置就会把"已退纯色"重置回满档 ⇒ 又一次截屏 ⇒ 又一次糊团闪烁。
         * ⇒ **只在"用户主动打开本功能"时 `reset`**（那是 `SelfCaptureGate.reset` 的
         * KDoc 点名的"外部事件驱动"之一）。
         */
        fun syncL3(local: Appearance) {
            val radius = local.materialBackdropBlurRadius
            val existing = l3State
            if (radius <= 0) {
                // 关闭：停线程 + 清空引用（渲染侧随即走纯色路径）。
                l3Worker?.stop()
                l3Worker = null
                l3State = null
                // 开关记录一并清 —— 下次启用时无论开关是 true 还是 false，
                // 「重建」都是正确动作（worker 本来就是新建的）。
                lastL3FrameLogEnabled = false
                lastL3RefractionKey = ""
                armOwnFrameCapture(false)
                return
            }
            // ── ★★★ 形状几何：**必须在幂等判据【之前】算**（2026-10-03）──────────
            //
            // 因为折射的 SDF 圆角取自 `islandOutlineAt(...)`，而它随
            // `shape.type` / `shape.corner_radius` / 展开进度变化 —— 它们是
            // `BackdropCaptureParams` 的**构造参数** ⇒ **必须并进幂等判据**。
            //
            // ⚠️ **原实现在判据【之后】才算** ⇒ 用户改「**外观形态**」或「**圆角大小**」时
            //   `syncL3` **早退、worker 不重建** ⇒ 折射仍用**旧圆角** ⇒
            //   **折射带与岛体边缘错开**（正是 `cornerRadiusPx` 的 KDoc 点名的后果），
            //   ★ **且不报错、不警告**；而逐像素玻璃侧**每帧重算** ⇒ 两边不一致。
            // ★ 本项由用户 2026-10-03 指令「**彻查未知的因修改而静默失效的功能链路**」查出。
            val w = lastIslandWidthForL3()
            val h = lastIslandHeightForL3()
            if (w <= 0 || h <= 0) return
            // ── ★★ 折射的 SDF 圆角：**必须与 `islandShape` 同源** ────────────────
            //
            // 取法与 `paintComponent` 里那条**逐字相同**（`islandOutlineAt` +
            // `islandRadiusBasisPx` + 当前 `bandFractionNow`）——
            // 否则"背景折射带"会与"岛体边缘"错开几像素（见 `GlassSdf` 的 KDoc）。
            //
            // ★★ 2026-10-02（T5）：**改前**这里写的是
            //   「⚠️ 两条边半径可能不同（`NOTCH`：上大下小），而 SDF 只收**一个**半径
            //     ⇒ 取**较大者**（大圆角在观感上更主导）。如实记录：`NOTCH` 下是近似。」
            //   ⇒ ★ **那段已过期**：`GlassSdf` 现在**收两个半径**（按 `y` 相对中心选），
            //     本处与预览侧**同时**改成逐边传（`migration/75`）。
            val radiusBasis = islandRadiusBasisPx(
                w.toFloat().takeIf { it > 0f } ?: baseWidthPx.toFloat(),
                baseHeightPx.toFloat(),
            )
            val outline = islandOutlineAt(
                shapeType = local.shapeType,
                cornerRadiusPercent = local.cornerRadiusPercent,
                radiusBasisPx = radiusBasis,
                fraction = bandFractionNow,
            )
            // ★★ T5（2026-10-02）：**双半径** —— 改前 `maxOf(上, 下)` ⇒ `NOTCH`（上大下小）档下
            //   折射带与岛体边缘**错开几像素**（看得见）。★ 与逐像素玻璃侧**同一个修法**。
            val glassCornerRadius = outline.topRadiusPx.toDouble()
            val glassCornerRadiusBottom = outline.bottomRadiusPx.toDouble()

            // ── ★★★ 幂等判据（**形状几何算完之后**才判）─────────────────────────
            //   ★★ 2026-10-03：本判据**原来漏了「模糊半径」** —— 注释写着
            //      「已在跑且**半径与日志开关都没变**」，但代码里**从来没有比较半径的项**
            //      （无 `lastL3Radius` 之类字段，取证：全文件 `grep lastL3` 只有两个字段）。
            //   ⇒ ⚠️ **从一个非 0 半径拖到另一个非 0 半径 ⇒ worker 不重建 ⇒ 模糊量不变**
            //      （拖滑杆没反应，且不报错）。★ 这正是台账 **R12**（用户报"身后窗口模糊看不见"）
            //      的一个**真实候选根因**。
            //   ⇒ 已把半径与两个圆角**一并并进 `l3RefractionKey`**（见那里的 KDoc）。
            val refractionKey = l3RefractionKey(local, glassCornerRadius, glassCornerRadiusBottom)
            if (existing != null && l3Worker?.running?.get() == true &&
                lastL3FrameLogEnabled == local.frameLogEnabled &&
                lastL3RefractionKey == refractionKey
            ) {
                return
            }

            // 启动（或半径从 0 变 >0 ⇒ 全新实例 + 显式复位）。
            val state = dev.cdl.render.L3BackdropState()
            val raster = dev.cdl.render.ShapeMaskRaster(w, h)
            val params = dev.cdl.render.BackdropCaptureParams(
                blurRadius = radius,
                downsample = dev.cdl.render.BackdropCaptureParams.DEFAULT_DOWNSAMPLE,
                cornerRadiusPx = glassCornerRadius,
                cornerRadiusBottomPx = glassCornerRadiusBottom,
                // ★ 4 个新键 → 已换算的物理量（换算只在 `BackdropRefraction` 的纯函数里做一次）。
                refraction = dev.cdl.render.BackdropRefraction.Params(
                    thicknessPx = dev.cdl.render.BackdropRefraction
                        .thicknessPx(local.materialBackdropRefractionHeight),
                    amountPx = dev.cdl.render.BackdropRefraction
                        .amountPx(local.materialBackdropRefractionAmount),
                    dispersionFactor = dev.cdl.render.BackdropRefraction
                        .dispersionFactor(local.materialBackdropDispersion),
                    saturation = dev.cdl.render.BackdropRefraction
                        .saturationFactor(local.materialBackdropSaturation),
                ),
                // ★★ R7：GPU 后端开关（`material.render.gpu_backend`，默认 false）
                //   ★ 失败自动回退 CPU（见 `BackdropCaptureParams.preferGpu` 的 KDoc）✓
                preferGpu = local.materialRenderGpuBackend,
            )
            val worker = dev.cdl.render.L3CaptureWorker(
                params = params,
                state = state,
                ownFrameProvider = { ownFrame },
                regionProvider = { l3RegionOnScreen() },
                // ★★ 告警必须**切回 EDT**：采集线程不是 EDT，而 `ui.toast` 与 Swing 同属 UI 面
                //    （`WorkshopApi.ui.toast` 的调用点在宿主侧同样由 EDT 驱动）。
                //    采集线程直接调 = 未定义的线程亲和性（本项目 BUG-029 那一族）。
                //    ⚠️ 用 `SwingUtilities.invokeLater` 而**不**用 `onEdt` —— 后者是
                //    `private inline`，从 `inner class` 里调用要先 `@PublishedApi`，
                //    为一条提示付这个代价不值得。
                onWarn = { msg ->
                    SwingUtilities.invokeLater { runCatching { toastSink?.invoke(msg) } }
                },
                // ★ 掩膜按**采集侧要求的尺寸**取。`ShapeMaskRaster` 的尺寸在构造时定死
                //   （= L3 启动那一刻的岛体尺寸），而采集线程每帧传入的是**当帧**的
                //   岛体尺寸 ⇒ 两者在形状动画期间会不一致。`maskFor` 只做"把 shape 光栅化成
                //   宽度×高度 的 0/1 表"，尺寸不符时它内部会按自己的 `width/height` 输出
                //   —— 于是判据侧的相关系数会**静默算错**（不报错，只是数字没意义）。
                //   ⇒ 传尺寸**匹配过的**那个 raster（不匹配时明确返回 null，让判据走 UNKNOWN
                //   而不是拿错尺寸的掩膜硬算）。
                maskProvider = { shape, ww, hh ->
                    if (raster.width == ww && raster.height == hh) raster.maskFor(shape) else null
                },
                // ★ 每秒帧率日志（`animation.frame_log`，**测试用**）。构造期定值 ——
                //   它变化时 `syncL3` 会重建 worker（见上面的幂等判据）。
                perSecondLogEnabled = local.frameLogEnabled,
            )
            worker.robotProvider = { ownCaptureRobot }
            // ★★★ 2026-10-05 **BUG-152 修复**：**覆盖 `l3Worker` 之前必须先停旧的**。
            //
            // ## 症状（真机取证，用户 2026-10-05 报「**一直卡顿，CPU 20–30%**」）
            //
            // 日志里 `[L3] 采集线程启动` **68 次** vs `采集线程已停` **3 次**
            // ⇒ ★ **净泄漏 65 个采集线程**（每个 interval=100ms ⇒ 每秒 650 次窗口采集）。
            //
            // ## 根因
            //
            // 本方法有**两条**走向"新建 worker"的路径：
            //   · ① `radius == 0 → >0`（从关到开）
            //   · ② ★ **幂等判据不成立**（`:9169`）—— 即 **改 `blurRadius` / 两个圆角 /
            //     `frameLogEnabled`** 时（它们都并进了 `l3RefractionKey`）
            // ★ ① 走的是**关闭路径**（`:9110` 有 `stop()`），**② 没有** ——
            //   原来的代码在这里**直接 `l3Worker = worker` 覆盖引用**，
            //   而**旧线程的 `running` 仍是 `true`**（没人置 false）⇒
            //   ★★ **旧线程永远在跑，且再也无人引用它** ⇒ **泄漏**。
            //
            // ## 为什么症状是"改滑杆"而不是"开一次"
            //
            // 用户**拖**一次 `material.backdrop.blur_radius`（2→11→21→32→38→40）
            // ⇒ ★ **一次拖拽泄漏 6 个线程**；几次调参下来就是几十个
            // ⇒ 每个都在 100ms 节拍上做**窗口采集 + 模糊** ⇒ **CPU 20–30% + 一直卡**。
            //
            // ## 修法
            //
            // ★ 覆盖前先 `stop()`。★ 它**幂等**（`:449` 的 `compareAndSet`）⇒ 首次启动
            //   （`l3Worker == null`）与"已停"两种情形都安全。
            // ★ 且**必须在这里**（而不是挪进某个共享 helper）—— 因为"谁负责停旧的"
            //   必须与"谁负责建新的"**在同一个方法里**，否则下一次改这里的人又会漏
            //   （本 BUG 就是这么来的：`disposeL3` 的 KDoc 专门解释了"为什么分开"，
            //   却没人补上**重建路径**该有的那一次 stop）。
            l3Worker?.stop()
            l3State = state
            l3MaskRaster = raster
            l3Worker = worker
            // ★ 记下本次构造用的开关值，供下一次 `syncL3` 的幂等判据比对。
            lastL3FrameLogEnabled = local.frameLogEnabled
            lastL3RefractionKey = refractionKey
            // ★ 接线后才 arm 像素快照（见 [publishOwnFrame] 约束②：未接线时零成本）。
            armOwnFrameCapture(true)
            worker.start()
        }

        /**
         * 停掉 L3 采集线程并清空引用。**幂等**，**可在任意线程调用**（`stop` 内部只置标志 + interrupt）。
         *
         * ## 为什么与 [syncL3] 分开
         *
         * `syncL3` 的语义是"**按设置**调档"（半径 0 → 停；半径 >0 → 起/复用），
         * 而本方法的语义是"**窗口正在死**，无论设置如何都必须停"。
         * 两者恰好都调 `stop()`，但**触发条件不同**（前者由设置驱动、后者由生命周期驱动）。
         * 合成一个方法的代价是：`close()` 要在调用点先构造一个"假 Appearance"
         * 去骗 `syncL3` 走关闭分支 —— 那正是"两个能力不对等的收敛点"（纪律 15）
         * 的形态：报告说停了、实际取决于那个假值对不对。**分开，各自单一职责。**
         */
        fun disposeL3() {
            l3Worker?.stop()
            l3Worker = null
            l3State = null
            l3MaskRaster = null
            // 与 [syncL3] 的关闭路径一致：开关记录与 worker 同寿，一并清掉。
            lastL3FrameLogEnabled = false
            lastL3RefractionKey = ""
            armOwnFrameCapture(false)
        }

        /** 岛体在 `Robot` 屏幕坐标里的矩形（= `locationOnScreen` 原点 + 面板内偏移）。 */
        private fun l3RegionOnScreen(): java.awt.Rectangle? = runCatching {
            val origin = locationOnScreen ?: return@runCatching null
            val f = ownFrame ?: return@runCatching null
            val x = origin.x + f.islandLeft.toInt()
            val y = origin.y + f.islandTop.toInt()
            if (f.islandWidth <= 0 || f.islandHeight <= 0) return@runCatching null
            java.awt.Rectangle(x, y, f.islandWidth, f.islandHeight)
        }.getOrNull()

        /** 上一帧岛体尺寸（L3 启动时用来定掩膜尺寸）。**只读缓存，不触发绘制**。 */
        private var lastIslandW: Int = 0
        private var lastIslandH: Int = 0

        private fun lastIslandWidthForL3(): Int = ownFrame?.islandWidth ?: lastIslandW
        private fun lastIslandHeightForL3(): Int = ownFrame?.islandHeight ?: lastIslandH

        /**
         * 画词岛边框（设置项 `island.border.enabled` / `width_px` / `color.r|g|b`）。
         *
         * ## 几何：为什么是"内缩半个线宽再描边"
         *
         * `Graphics2D.draw(shape)` 的描边**以轮廓线为中心**向内外各展开 `width/2`。
         * 而形状是贴着**岛体矩形**构造的（`islandShapeOf` 从 `(0,0)` 起算到 `(w,h)`），
         * 于是三种做法各不相同：
         *
         * | 做法 | 结果 |
         * | --- | --- |
         * | 不内缩 | 外沿落在窗口外 `width/2` 处，被窗口裁掉 → **视觉只有一半粗** |
         * | 内缩整个 `width` | 外沿落在窗口内 `width/2` 处 → 边框与窗口边缘**之间一条缝** |
         * | **内缩 `width/2`（本实现）** | 外沿恰好落在窗口边缘，内沿在 `width` 处 → 所见即所设 |
         *
         * 形状仍由**公开纯函数**构造（而不是自己写矩形）—— 于是边框形态与
         * `shape.type`（胶囊 / 方形 / 刘海）**必然一致**，不必为每种形态各写一套描边。
         *
         * ⚠️ 2026-09-22：原先是 `buildShape(local, w, h)`（按 `shape.type` 反推**收起态**
         *    半径）。圆角现在随展开进度变形，故改为收**本帧真正用的那两个半径**
         *    （[islandOutlineAt] 给出）—— 边框与岛体轮廓由**同一个**值推出，
         *    不可能出现"边框还是胶囊、岛体已经是圆角矩形"这种错位。
         */
        //
        // ★★★ 2026-10-13（**BUG-183**）：**封面形状的唯一构造点**（圆形 / 方形 / 圆角矩形）。
        //
        // ## 为什么必须抽出来（★ 不是"顺手重构"）
        //
        // ★ 命中域需要**第二个**形状：开黑胶时要登记**外径**（含环）而不是内容 ✓
        // ★★ 若在 `paintComponent` 里另写一份 `when`，就是**第二份几何实现** ⇒
        //   ★ 将来加一种封面形状，只改一处 ⇒ "看得见的封面点不中"（本项目最忌讳的形态 ✗）
        // ⇒ ★ **同一个函数、两种尺寸**（内容尺寸 / 外径尺寸）⇒ 形状种类只有一处 ✓
        //
        // ⚠️ `else` 分支读的是 `local.shapeType`（**词岛形态**），不是 `local.coverShape`
        //    （封面形状）—— 两个键、两个语义、字面量巧合相同（沿用原 KDoc 的警告）。
        //
        private fun coverShapeAt(left: Float, top: Float, side: Float, local: Appearance): Shape =
            when (local.coverShape) {
                COVER_SHAPE_CIRCLE -> Ellipse2D.Float(left, top, side, side)
                COVER_SHAPE_SQUARE -> Rectangle2D.Float(left, top, side, side)
                else -> {
                    val arc = side *
                        if (local.shapeType == "SQUARE") COVER_ARC_RATIO_SQUARE else COVER_ARC_RATIO
                    RoundRectangle2D.Float(left, top, side, side, arc, arc)
                }
            }

        /**
         * ★★★ 2026-10-13（**BUG-187**）：「溶边」掩版的**唯一应用点**。
         *
         * ## ★★ 为什么它**不在** [drawIslandBorder] 里（本批的核心修正）
         *
         * ★ `drawIslandBorder` 在**所有内容之后**被调用 —— 在那里做 `DstIn` 会把
         *   **歌词 / 封面 / 频谱 / 控件一起淡化** ✗（用户原话：「**淡化把词岛内容也淡化了**」）
         * ★★ 那个位置在 **BUG-182** 时是安全的：带宽只有 **8 px**，内容都在更内侧 ⇒ **擦不到** ✓
         * ★★★ 而 **BUG-185** 把带宽改成**岛体半高**（56 px 岛 ⇒ 28 px）后，带**够到了内容**
         *   ⇒ 症状才暴露 ✗
         * ⇒ ★ 判据：★★ **「擦除带的作用范围一旦变大，所有'它够不到'的旧假设都要重审」** ✓
         *
         * ## ★ 为什么放"背景之后"（而不是"投影之前"）
         *
         * ★ 投影画在岛体**底下**（`MaterialLayers.drawDropShadow` 在本方法更早处）——
         *   只擦背景会让边缘**透出投影** ⇒ 一条暗边 ✗ ⇒ ★ **背景与投影一起擦** 才是
         *   「岛体整体溶边」的语义 ✓
         */
        private fun applyDissolveMask(
            g2: Graphics2D,
            local: Appearance,
            islandLeftPx: Float,
            islandTopPx: Float,
            islandWidthPx: Float,
            islandHeightPx: Float,
            topRadiusPx: Float,
            bottomRadiusPx: Float,
        ) {
            if (!local.borderEnabled) return
            // ★ 只有「溶边」档需要它（`SOLID` / `FADE_OUT` 走各自的描边路径）✓
            if (local.borderMode != BORDER_MODE_EDGE_DISSOLVE) return
            // ★ 背景全透明 ⇒ 无物可溶（用户 BUG-182 原话带的括号）✓
            if (local.opacityPercent == 0) return
            val widthPx = Math.round(islandWidthPx)
            val heightPx = Math.round(islandHeightPx)
            if (widthPx <= 0 || heightPx <= 0) return
            val band = dev.cdl.settings.dissolveBandPx(
                heightPx.toFloat(), local.borderDissolveBandPercent,
            )
            if (band <= 0f) return
            val mask = DissolveMask.get(
                widthPx = widthPx,
                heightPx = heightPx,
                bandPx = band,
                topRadiusPx = topRadiusPx,
                bottomRadiusPx = bottomRadiusPx,
                cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                cornerN = local.cornerN,
            ) ?: return
            val savedComposite = g2.composite
            val savedInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
            try {
                // ★ `DstIn` = 「用源的 alpha **乘**目标的 alpha」⇒ 边缘渐隐 ✓
                g2.composite = java.awt.AlphaComposite.DstIn
                // ★ 1:1 贴图 ⇒ 关插值，避免重采样把斜坡糊掉 ✓
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )
                g2.drawImage(mask, Math.round(islandLeftPx), Math.round(islandTopPx), null)
            } finally {
                g2.composite = savedComposite
                // ★ 还原插值提示 —— 别泄漏给后面的绘制（歌词/封面缩放都吃它）✓
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    savedInterp ?: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )
            }
        }

        private fun drawIslandBorder(
            g2: Graphics2D,
            local: Appearance,
            /** 岛体在面板坐标里的**左**边缘（`BandGeometry.islandLeft`）。 */
            islandLeft: Float,
            width: Int,
            /** 岛体在面板坐标里的上边缘（`BandGeometry.islandTop`）。`BOTTOM` 时恒为 0。 */
            islandTop: Float,
            height: Int,
            /** 本帧形状的上 / 下边圆角半径（[islandOutlineAt]）—— 与岛体形状**同一个**来源。 */
            topRadiusPx: Float,
            bottomRadiusPx: Float,
            coverRgb: Int?,
        ) {
            if (!local.borderEnabled) return
            // ⚠️ **除以 10**：键的单位是"十分之一像素"（见 `borderWidthTenthPx` 的 KDoc）。
            // 忘了除 → 用户设 1.0 会画出 10px 的粗框；这正是"单位换了名字没跟着换"的典型形态。
            val strokeWidth = local.borderWidthTenthPx / 10f
            // `0.1 px` 以下的描边在 Java2D 里会退化成**几乎不可见的一层极淡像素**，
            // 而用户拖到 0 就是想"不画"。故用 `<= 0f` 判据而不是 `< 1f` ——
            // 保留 0.1~0.9 px 的细线（那正是本次修订要支持的诉求）。
            if (strokeWidth <= 0f) return
            // ⚠️ 2026-09-22：形状构造从 `buildShape(local, w, h)` 改成 `islandShapeOf(w, h, r…)` ——
            //    圆角现在随展开进度变形，而 `buildShape` 只能按 `shape.type` 反推**收起态**半径。
            //    用**本帧真正用的**那两个半径 ⇒ 边框与岛体轮廓**必然同形**（这正是本函数
            //    存在的理由：不为每种形态各写一套描边）。
            val insetShape = islandShapeOf(
                width = (width - strokeWidth).toInt().coerceAtLeast(1).toFloat(),
                height = (height - strokeWidth).toInt().coerceAtLeast(1).toFloat(),
                topRadiusPx = topRadiusPx,
                bottomRadiusPx = bottomRadiusPx,
                // ★ 与岛体**同一个**风格来源 ⇒ 不可能出现"边框还是圆角矩形、岛体已是 squircle"。
                cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                cornerN = local.cornerN,
            )
            val savedTransform = g2.transform
            val savedStroke = g2.stroke
            val savedColor = g2.color
            try {
                // 平移半个线宽 —— 推导见 KDoc 的表格。
                // ⚠️ 还要加上 `islandTop` / `islandLeft`：动画期间岛体不在面板左上角
                //    （`TOP` 档下它随带子收缩而下移；恒宽窗口下它随展开居中平移），
                //    边框必须跟着岛体走，否则会画出一圈"与岛体错位"的框。
                g2.translate(
                    islandLeft.toDouble() + strokeWidth / 2.0,
                    islandTop.toDouble() + strokeWidth / 2.0,
                )
                val borderColor = local.borderColorFor(coverRgb)
                // ★★★ 2026-10-10（审计 v4 · B-1）：判据改用**常量** `BORDER_MODE_FADE_OUT`
                //   （原为字面量 `"FADE_OUT"`）—— ★ 预览侧同批接上了这一档，
                //   两处共用**同一个常量** ⇒ "档位值"不可能漂移 ✓
                if (local.borderMode == BORDER_MODE_FADE_OUT) {
                    // ★★ 2026-09-29（用户拍板 ⑨）：**不画实线**，改为「由外缘向内递减 alpha 的
                    //    多层描边」—— 视觉上边缘柔和淡出，而不是一条硬线。
                    //
                    // ★ **D1 = B（不外扩）**：淡出带只落在形状**内部** ⇒
                    //   ① 不需要预乘蒙版（不外扩就没有"形状外"要画）；
                    //   ② 视觉尺寸**不变**（实测包围盒 +0/+0），不会与布局锚点冲突。
                    //
                    // ★ 为什么用"多层描边"而不是 `setClip` 或位图掩膜：
                    //   `MaterialLayers` 文件头已定 **绝不能用 `setClip`**（硬边、忽略 AA，
                    //   BUG-117/121 同族）；而位图掩膜是"外扩"才需要的（D1=B 时是过度设计）。
                    //   ⇒ 多层 `draw` **天然贴合任意形状**（与边框同一条 `insetShape` 来源），
                    //     且**不做 clip** ⇒ 边缘仍由 Java2D 抗锯齿产生。
                    // ★ 层参数由**顶层纯函数** [borderFadeLayers] 给出 —— 换算只此一处，
                    //   且**可被探针断言**（`drawIslandBorder` 是 private，探针看不到）。
                    for (layer in borderFadeLayers(strokeWidth)) {
                        if (layer.alpha <= 0) continue
                        val inset = layer.insetPx
                        val ring = islandShapeOf(
                            width = (width - strokeWidth - inset * 2f).toInt().coerceAtLeast(1).toFloat(),
                            height = (height - strokeWidth - inset * 2f).toInt().coerceAtLeast(1).toFloat(),
                            topRadiusPx = (topRadiusPx - inset).coerceAtLeast(0f),
                            bottomRadiusPx = (bottomRadiusPx - inset).coerceAtLeast(0f),
                            cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                            cornerN = local.cornerN,
                        )
                        g2.stroke = BasicStroke(layer.widthPx)
                        g2.color = Color(
                            borderColor.red, borderColor.green, borderColor.blue, layer.alpha,
                        )
                        g2.draw(ring)
                    }
                } else if (local.borderMode == BORDER_MODE_EDGE_DISSOLVE) {
                    // ★★★ 2026-10-13（**BUG-187**）：**本档不在这里做事** ——
                    //   溶边掩版已移到「**背景之后、内容之前**」应用（见 [applyDissolveMask]）✓
                    //
                    //   ★ 原因：在本函数里做 `DstIn` 是错的 —— 本函数在**所有内容之后**
                    //     被调用 ⇒ 会把**歌词 / 封面 / 频谱 / 控件一起淡化** ✗
                    //     （用户原话：「**淡化把词岛内容也淡化了**」）
                    //   ★★ 那个位置在 BUG-182 时**是安全的**：带宽只有 8 px、内容都在更内侧
                    //     ⇒ 擦不到 ✓；★ 而 BUG-185 把带宽改成**岛体半高**（28 px）后
                    //     带**够到了内容** ⇒ 症状才暴露 ✗
                    //
                    //   ★ 保留这个分支（而不是删掉它）是为了**显式说明**
                    //     「本档已处理，只是不在这里」—— 否则后来者会以为漏了 ✓
                } else {
                    // `SOLID`：**改动前的路径，一行不动**（★ A2 断言：逐像素不变）。
                    g2.stroke = BasicStroke(strokeWidth)
                    // 颜色来源由 `island.border.color_mode` 决定（`manual` / `cover`）——
                    // 解析逻辑与歌词/背景/频谱同源（[Appearance.borderColorFor]）。
                    g2.color = borderColor
                    g2.draw(insetShape)
                }
            } finally {
                // 三样都还原：歌词的描边（艺术字描边）与后续绘制依赖它们，
                // 不还原会让"打开边框"这个动作污染其它元素的画法。
                g2.transform = savedTransform
                g2.stroke = savedStroke
                g2.color = savedColor
            }
        }

        /**
         * 画控件条。**只在展开态画**（`layout.expanded` 为 false 时 [PlaybackControls.paint] 直接返回）。
         *
         * 进度条的强调色取**已唱歌词色**（`local.sungColor`）：控件与歌词的配色因此天然一致，
         * 用户改歌词颜色时进度条会跟着变，不必再多一个色键去同步。
         */
        private fun drawControls(g2: Graphics2D, local: Appearance, layout: PlaybackControls.Layout) {
            if (!layout.expanded) return
            val snap = snapshotSupplier()
            // ── 风格化（2026-09-20）────────────────────────────────────────────
            //
            // ★★ 2026-09-22：进度源从 `hover.expansion` 换成 **[bandFractionNow]** ——
            //    这是"上下闪动"后半截修复的**必要**一步。
            //
            //    两个进度源的区别只在**收缩方向**：
            //      · `hover.expansion`：`HoverState` 在收起时把它**直接归零**
            //        （它的 KDoc 写的理由是"窗口已经变矮、没有可绘制的载体"——
            //         那条理由在本轮修法下**已不成立**：窗口现在整段动画都保持展开）；
            //      · `bandFractionNow`：收起方向由 `bandCollapseRunning` 逐帧推进。
            //    用前者会让带子**一帧内从 1 掉到 0**（"控件闪没了"），
            //    而岛体还要 180 ms 才收回去 —— 那正是用户看到的"闪"。
            //
            //    展开方向两者**同源**（`bandFractionNow = max(bandFraction, hover.expansion)`），
            //    故这一改动对"弹出"逐帧无影响。
            //
            // 风格来自 `controls.animation_style` / `animation.style`（经 `Appearance`
            // 传进来，绘制层不读设置），
            // 且只有"跟随全局风格"的动效才用它（见 `Motions.resolve`）。
            val progress = bandFractionNow
            // `progress = 0` ⇒ 带子整个在岛体形状之外（被 `g2.clip = shape` 裁掉），
            // 不必走一趟注定不可见的绘制。
            if (progress <= 0f) return
            val spec = Motions.resolve(Motions.CONTROLS_APPEAR, local.controlsMotionStyle)
            // ★ 诉求 ⑭：控件配置在这里**只取一份**，同时喂给下面的 `fromTop`
            //   判定与 `controls.paint(...)` —— 原先 `fromTop` 那行自己调了一次
            //   `controlsConfig()`，若再为 `paint` 取一份就是**两次调用**。
            //   两次调用在"设置刚改、快照尚未稳定"的瞬间可能拿到不同的值 ⇒
            //   "按 TOP 算位移、却按 BOTTOM 画进度条形态"（朝向与形态分叉）。
            //   ⇒ 收敛成唯一一份（本项目纪律：同一份数据不要有两个收敛点）。
            val cfg = controlsConfig()
            // `MotionStyle.offsetYAt` 的约定是"正 = 向下"，与元素在哪条边无关 ——
            // 故**方向只在这里判一次**：TOP 的控件条从上方进来（负偏移），
            // BOTTOM 的从下方进来（正偏移）。
            val fromTop = cfg.position == ControlsConfig.TOP
            val signedDistance = if (fromTop) -spec.distancePx else spec.distancePx
            // ⚠️ `animation.enabled = false` → **不做位移与缩放**（用户关它是要"完全不动"）。
            //    注意**淡入仍然保留** —— 那是**既有行为**（早于动画体系，由 `HoverState.expansion`
            //    驱动），不属于本开关的管辖范围。如实划界，不假装"关了就没有任何变化"。
            val motionOn = animator.enabled
            val offsetY = if (motionOn) spec.style.offsetYAt(progress, signedDistance) else 0f
            val scale = if (motionOn) spec.style.scaleAt(progress) else 1f
            val motionTransform = if (offsetY == 0f && scale == 1f) {
                null
            } else {
                // 绕**控件条自身中心**缩放 —— 否则会从左上角"长大"，
                // 看起来像"飞进来"而不是"弹出来"。
                val cx = width / 2f
                val stripCenterY = if (fromTop) {
                    layout.stripHeight / 2f
                } else {
                    height - layout.stripHeight / 2f
                }
                AffineTransform().apply {
                    // 顺序 = 先位移、再绕中心缩放（`AffineTransform` 的
                    // `translate`/`scale` 是**后乘**，即"先写的先作用"）。
                    // 反过来的话偏移量会被缩放系数乘一遍 —— 6% 的误差，
                    // 肉眼看不出，但会让"三种风格的距离不一致"。
                    translate(0.0, offsetY.toDouble())
                    translate(cx.toDouble(), stripCenterY.toDouble())
                    scale(scale.toDouble(), scale.toDouble())
                    translate(-cx.toDouble(), -stripCenterY.toDouble())
                }
            }
            runCatching {
                controls.paint(
                    g2 = g2,
                    layout = layout,
                    hovered = hoveredControl,
                    playing = snap.isPlaying,
                    // ★★★ 2026-10-11（**BUG-165**）：`positionMs` 与 `previewMs` **必须分开传**。
                    //
                    // ## 原记（错的，且它让 ⑭「小圆不恒显示」整条需求**从未生效**）
                    //
                    // 原先只有一行：`previewMs = progressDisplayMs(snap)`。
                    // ★★ 而 `progressDisplayMs` 的返回类型是 **`Long`（非空）** ——
                    //    它在"不在拖动"时返回 `snap.positionMs`（**一个真值**）✗
                    // ⇒ 赋给 `previewMs: Long?` 之后**永不为 null**
                    // ⇒ `paintProgress` 的 `dragging = previewMs != null` **恒为真**
                    // ⇒ `progressKnobVisible(glow = true, dragging = true, ·)` = **恒真**
                    // ⇒ ★★ **流光档的圆点仍"常驻"，而且按"拖动中"放大 1.4×** ✗
                    //    （用户 2026-10-11 报障逐字：「**流光外观下的进度条小球仍常驻**」，
                    //      截图里圆点直径 ≈ 14px = `KNOB_RADIUS(5) × 1.4 × 2` ⇒ 坐实）
                    //
                    // ## 为什么 `PlaybackControls` 的判据**一直是对的**
                    //
                    // `progressKnobVisible` 的真值表 + `hoverHighlightKept`（U9）**都对**，
                    // 探针也钉着它们 —— ★ 坏的只是**调用点喂进来的那个布尔**。
                    // ⇒ 属「**护栏与生产路径错位**」（BUG-161 族）：守住了纯函数，
                    //   **没守住接线**。★ 故本次同时补一条静态守卫（见
                    //   `tools/check_progress_knob_wiring.py`）。
                    //
                    // ## 修法：两个语义**各归各位**
                    //
                    // | 参数 | 语义 | 传什么 |
                    // |---|---|---|
                    // | `positionMs` | **要显示的位置**（拖动预览 / 提交保持 / 真实位置） | `progressDisplayMs(snap)`（恒有值） |
                    // | `previewMs` | **"是否正在拖动"** 的信号（`null` = 没在拖） | `seekPreviewMs`（**只在拖动中非 null**） |
                    //
                    // ★ `paintProgress` 内部是 `previewMs ?: positionMs` ——
                    //   而 `progressDisplayMs` 在拖动时**本来就返回 `seekPreviewMs`**
                    //   ⇒ 两者叠加**逐像素等价于改动前**（位置不变），
                    //   只有"拖动中"这个布尔**恢复成真值** ✓
                    positionMs = progressDisplayMs(snap),
                    // 时长来源见 TrackDurationLoader：宿主回调线程在后台读完
                    // 本地音频文件后写进 loader，这里每帧读它（无锁 AtomicLong）。
                    durationMs = durationSupplier(),
                    accent = local.sungColor,
                    // ★ 诉求 ⑭：控件配置（含 `progressStyle` / 圆点显隐判据都由它派生）。
                    //   **必须与 `layout()` 用的是同一份** —— 两者不同会让
                    //   "画成流光但命中按经典算"（几何虽同，但形态判定分叉）。
                    cfg = cfg,
                    // 淡入 / 淡出动画进度。**与岛体形状同源**（同一个 `bandFractionNow`）——
                    // 两者若各用一份进度，"带子长出来"与"控件亮起来"会不同步，
                    // 看起来像两段互不相干的动画。
                    alpha = progress,
                    // ★★ 拖动**信号**（不是位置！）—— 见上表。
                    //   ⚠️ **不要把 `progressDisplayMs(snap)` 传进来**：它是非空的，
                    //      会把"拖动中"变成恒真（这正是 BUG-165 的成因）。
                    previewMs = seekPreviewMs,
                    // 位移 / 缩放（由风格算出）。`null` = 不变换，绘制路径与改动前逐像素一致。
                    transform = motionTransform,
                )
            }.onFailure {
                // 控件绘制失败不该拖垮整个词岛 —— 与歌词逐行 runCatching 同一理由
                // （paintComponent 抛异常会被 Swing 吞掉，留下一块永不重绘的区域）。
                System.err.println("[CDL] 播放控件绘制失败（本次跳过）：$it")
            }
        }

        //
         // 依据「形态 + 圆角百分比」构造形状。
         //
         // ## 圆角百分比 → 像素的换算（**这是本文件最容易写错的一处**）
         //
         // 设置项 `shape.corner_radius` 的取值范围是 0..100，语义是
         // **相对短边的百分比**，不是像素 —— 这样同一个数值在大小不同的词岛上
         // 视觉比例一致（用户换宽度时不必重调圆角）。
         //
         // 换算与全部形态构造**已搬到 [islandShape]**（公开纯函数）——
         // 理由：BUG-127 的验收判据是「带子矩形的四角必须落在 `shape.contains(...)` 之内」，
         // 探针必须拿到**生产真正画的那个形状**；让探针抄一份构造代码迟早会漂移，
         // 而漂移的表现是"探针绿了、真机仍然戳出去"。
         //
         // ## ★ 2026-09-22：圆角基准从"当前岛高"改成**收起态岛高**
         //
         // 改动前这里取 `shortSide = min(w, height)`，而 `height` 传的是 `bg.islandHeight`
         // （动画中从 `base` 连续长到 `base + strip`）⇒ 整段动画里**岛体轮廓的圆角在连续变形**。
         // 那是"看起来在抖"的**第二个来源**（第一个是窗口矩形跳变）。
         // 现在基准恒为 `baseHeightPx`（= `bandGeometry` 用的"岛体静止尺寸"口径），
         // 与动画无关。理由（为什么不用宽度）见 [islandRadiusBasisPx]。
         //
         // ## ⚠️ 2026-09-22 晚：本函数已**删除**（同名逻辑并入 `islandShapeOf`）
         //
         // 圆角现在**按展开进度插值**（[islandOutlineAt]），而本函数的签名只能表达
         // "按 `shape.type` 反推收起态半径" ⇒ 它无法表达插值结果。
         // 保留一个"只能用一半"的旧入口 = 下一个人会拿它画出一条**不跟着变形的**
         // 轮廓（本项目反复记的形态：**留着一个不再正确的入口，比删掉它危险**）。
         // 两个调用点（岛体 / 边框）都已改为直接调 `islandShapeOf(w, h, 上半径, 下半径)`。
         //

        /**
         * L3（真透视 / ④b）的**消费点**：把采集线程发布的模糊背景画在**最底层**。
         *
         * ## 三件事，各一行，**没有第四件**
         *
         * ① 没有有效背景 ⇒ **立刻返回**（一个像素都不画）；
         * ② 有 ⇒ 把位图对到**岛体左上角**（`islandLeft/islandTop`）铺上；
         * ③ 裁到岛体形状。
         *
         * ## ★ 本方法**绝不**做的两件事
         *
         * | 不做 | 为什么 |
         * |---|---|
         * | **不模糊** | 模糊在采集线程（10 Hz，`BackdropCapture`）；EDT 每帧模糊 = 吃掉半个帧预算（`24-*` E1） |
         * | **不重建蒙版** | 蒙版预烘焙（E5：预烘焙 `drawImage` 0.150 ms vs 每帧重建 1.749 ms，11.7×） |
         *
         * ## 为什么"没有背景"是**四合一**的单一分支
         *
         * `backdrop == null` 同时覆盖：未启用 / 冷启动首帧 / 单帧判据拒收 / 已退纯色档。
         * 四者都落到"不画"⇒ 渲染路径**自动**回到 L3 未启用时的样子。
         * 这是"铁律：任何情况下都不得静默把（可能含自己的）糊图当背景"在**渲染侧**的体现
         * —— 采集侧负责**判定**，渲染侧负责**不越权补画**。
         *
         * @param islandLeft 岛体左上角在**面板坐标**里的 x（`bg.islandLeft`）
         * @param islandTop  岛体左上角在**面板坐标**里的 y（`bg.islandTop`）
         * @param islandWidth  当前岛体宽（采集位图的尺寸校验基准）
         * @param islandHeight 当前岛体高（采集位图的尺寸校验基准）
         */
        private fun paintL3Backdrop(
            g2: Graphics2D,
            local: Appearance,
            shape: Shape,
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ) {
            // `material.backdrop.blur_radius == 0` ⇒ `l3State` 恒 null（`syncL3` 的关闭分支）
            // ⇒ 与"从未开启"同一条路径。**这是"默认零视觉变化"的保证点。**
            val backdrop = l3State?.backdrop ?: return
            // 位图尺寸必须与当前岛体矩形一致 —— 采集是 10 Hz 而形状可能正在动画中
            // （展开动画里岛宽每帧都在变）⇒ 尺寸不匹配时**本帧不画**（等下一帧采集跟上），
            // 而不是"拉伸铺满"（拉伸 = 形状跳变时糊图的纹理被拉扯，观感是"背景在抖"）。
            if (backdrop.width != islandWidth || backdrop.height != islandHeight) return
            val oldClip = g2.clip
            try {
                g2.setClip(shape)
                g2.drawImage(backdrop, islandLeft.toInt(), islandTop.toInt(), null)
            } finally {
                g2.clip = oldClip
            }
        }

        /**
         * 背景色 —— 把「不透明度百分比」乘进 alpha。
         *
         * 底色用**接近黑的深灰**（`#14161A`）而不是纯黑：纯黑在浅色壁纸上
         * 对比过强、像"补丁"，深灰更接近系统毛玻璃的观感。这是视觉选择。
         *
         * ## `background.color_mode`（2026-09-17 接线，原为死设置）
         *
         * 底色**来源**由 [Appearance.backgroundColorFor] 解析：
         * - `manual` → [BASE_BACKGROUND]（即上面那个 `#14161A`，**逐像素不变**）；
         * - `cover` → 由封面主色派生的深色（同色相、明度 0.16）。
         *
         * ⚠️ **alpha 的计算两条路径完全共用**（就是下面这三行）——
         * 若把 alpha 也塞进调色板，"跟随封面时背景不透明度失效"这类缺陷
         * 会很难归因（用户会以为是不透明度设置坏了，而不是取色路径的问题）。
         *
         * @param coverRgb 封面主色；`null` = 没有封面/提取失败 → 回退手动档底色
         */
        private fun backgroundWithOpacity(local: Appearance, coverRgb: Int?): Color {
            val percent = local.opacityPercent.coerceIn(0, 100)
            // alpha = 百分比 → 0..255。`background.opacity` 的下限在注册表里是 35，
            // 故这里不会出现"完全看不见背景"的情况；但**仍然**用 coerceIn 兜住 ——
            // 快照理论上已被 coerce，可万一有别的写入路径绕过了，这里不至于画出全透明。
            val alpha = (255 * percent / 100).coerceIn(0, 255)
            val base = local.backgroundColorFor(coverRgb)
            return Color(base.red, base.green, base.blue, alpha)
        }

        /**
         * 「封面图」档的底色：**封面图（等比铺满 + 旋转）+ 同源取色半透明蒙版**。
         *
         * ## 用户规格（2026-09-22 逐字）
         *
         * > 「大致为**歌曲封面作为封面底色**，然后**上覆与从封面取色同源的自动取色
         * >  半透明蒙版**。同时，因为**词岛是长条型**，歌曲封面可能要**进行一定缩放才能
         * >  铺满词岛背景**，同时要**允许用户调整封面角度（0-360 度）**，**旋转后封面
         * >  自动缩放铺满**词岛背景，**不再设调整设置**。**不可拉伸封面原图**。」
         *
         * 五条判据 → 本方法的对应关系：
         *  ① 底色是**封面图**（不是纯色）→ `src` 就是它；
         *  ② 上覆**同源取色**的蒙版 → 蒙版色 = [CoverPalette.darkFrom]`(coverRgb)`，
         *    与 `background.color_mode = cover` 用的是**同一个派生函数** ⇒ 真正"同源"；
         *  ③ **等比铺满**（不是 contain、更不是拉伸）→ 缩放由 [coverFillScale] 算，
         *    x/y 共用同一个系数；
         *  ④ **0..360 可调**、旋转后**自动重新铺满** → 角度进缓存键，改了必然重烘；
         *  ⑤ **没有"缩放"设置项** → 缩放是算出来的，用户看不到也不需要它。
         *
         * ## 为什么必须离屏烘焙（性能）
         *
         * 见 [CoverBackdrop] 的类 KDoc：逐帧现算等于每秒几十次位图重采样。
         * 缓存键 =（封面图引用, 岛宽, 岛高, 角度, 蒙版 ARGB），三者不变就复用。
         *
         * ## 为什么 alpha 只乘**一次**
         *
         * `background.opacity` 的语义是"**这块底色有多不透明**"。蒙版已经烘进位图，
         * 故这里只需对整张烘焙图做**一次** `AlphaComposite` ——
         * 若改成"封面按 A 画、蒙版再按 A 填"，最终覆盖度是 `A + A(1−A)`
         * （`A=0.5` 时得 **0.75**），用户拖到 50% 却得到 75%
         * —— 正是本项目点名反对的「设置值 ≠ 生效值」。
         *
         * ## 裁剪与坐标
         *
         * 岛体形状（圆角/胶囊/刘海）由 `shape` 给出，烘焙图按**岛体矩形**（宽 × 岛高）
         * 生成、平移 `islandTop` 后画上去 —— 两者都锚在**面板坐标**，与歌词/封面同系。
         * 裁剪用 `g2.clip = shape`（**不是** `setClip` 之外的做法），与下面第 ④ 步
         * 给歌词/封面用的是同一个形状 ⇒ 三者的边界必然一致。
         *
         * @param coverRgb **未插值**的封面主色（`coverRgbRaw`）。刻意不用插值后的
         *   `coverRgb`：那个值在封面交叉淡化期间**每帧都不同**，会让缓存键每帧变化
         *   ⇒ 缓存彻底失效（退化成逐帧烘焙）。用未插值值则**只在换曲时**变一次。
         */
        private fun drawCoverImageBackdrop(
            g2: Graphics2D,
            local: Appearance,
            shape: Shape,
            src: BufferedImage,
            coverRgb: Int?,
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ) {
            val w = islandWidth
            val h = islandHeight
            if (w <= 0 || h <= 0) return
            val percent = local.opacityPercent.coerceIn(0, 100)
            // 不透明度 0 = 背景全透明（与纯色档同语义：那时也不画背景）。
            if (percent <= 0) return

            // 蒙版色：与 `background.color_mode = cover` **同一个派生函数**（"同源"）。
            //
            // ⚠️ 没有主色（提取失败）时退回**内置深灰**（`BACKGROUND_BASE_RGB`），
            //    **刻意不读 `background.color`** —— 那是**纯色档**的键，
            //    本档若去读它，"哪一档读哪些键"的边界就糊了（用户在封面图档下改
            //    纯色档的颜色会意外影响蒙版）。宁可蒙版色朴素，也不要让封面裸奔
            //    （裸奔的亮封面上白字读不出来，那是本项目的头号可用性红线）。
            val maskBase = coverRgb?.let { CoverPalette.darkFrom(it) }
                ?: Color(BACKGROUND_BASE_RGB)
            val maskArgb = (CoverBackdrop.MASK_ALPHA shl 24) or (maskBase.rgb and 0xFFFFFF)

            val baked = CoverBackdrop.baked(
                src = src,
                targetW = w,
                targetH = h,
                angleDeg = local.backgroundCoverAngleDeg,
                maskArgb = maskArgb,
                // ★ 2026-09-25 第 3 期 L2：**这两个值必须进缓存键**（见 `baked()` 的 KDoc）——
                //   不进的话，用户拖「岛内内容模糊」滑杆会命中旧缓存 ⇒ "拖了没反应"。
                blurRadius = local.materialBlurRadius,
                // 档位 → 降采样倍率：`LITE` ⇒ 4（实测省 55%，`B §A-1`），其余 ⇒ 1。
                // ⚠️ 内联算，**不做成 Appearance 的计算属性** —— 后者不能放进构造参数列表。
                downsample = if (local.materialQuality == "LITE") 4 else 1,
            )

            val oldClip = g2.clip
            val oldComposite = g2.composite
            try {
                g2.clip = shape
                if (percent < 100) {
                    g2.composite = AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER,
                        percent / 100f,
                    )
                }
                g2.drawImage(
                    baked,
                    AffineTransform.getTranslateInstance(
                        islandLeft.toDouble(),
                        islandTop.toDouble(),
                    ),
                    null,
                )
            } finally {
                // 两个状态都必须显式写回：`clip` 是**与已有 clip 取交**（不是替换），
                // `composite` 会污染后面画歌词/封面/控件的那一大段。
                g2.composite = oldComposite
                g2.clip = oldClip
            }
        }

        /**
         * 画**锁头标志**（封面态 · 已锁 · 命中时短暂弹出 —— `recon/67` §3.5）。
         *
         * ## 为什么手绘（而不是加一个 `PlaybackControls.Icon`）
         *
         * `PlaybackControls.Icon` 是**控件条图标**的枚举（PLAY/PAUSE/PREVIOUS/NEXT），
         * 它的语义域是"控件条里那几个可点的按钮"、且由 `drawIcon` 按 18×18
         * **设计网格**绘制（坐标取自 0.8.9）。而锁头标志：
         *  · 不在控件条里（它锚在**封面**右上角）；
         *  · 不是播放动作；
         *  · 尺寸与位置由封面派生。
         * ⇒ 塞进那个枚举会让"图标"这个概念承载两种无关的东西
         *   （本项目对"名字说谎"很敏感，见 `hitGlyph` / `hitCover` 为何分开）。
         *
         * ## 图形：一把**闭合的挂锁**（锁体 + 锁梁）
         *
         * 在本函数给出的矩形内按比例绘制（不依赖外部网格）：
         *  · **锁梁**（上方的半环）：`Arc2D` 画一条粗弧 —— 开口朝下；
         *  · **锁体**（下方的方块）：圆角矩形。
         * 配色用 [PlaybackControls.ICON_COLOR]（与控件条图标**同一个常量**）⇒ 观感统一，
         * 且**不引入新色**（本项目对"多一份常量"很警惕：日后只改一处就会漂移）。
         * 另加一圈**深色描边**：标志画在**封面图**上，而封面可能是任意亮色，
         * 纯白图标在浅封面上会看不见（"看得见"是它的全部意义）。
         *
         * @param badge 标志的矩形（由 [coverLockBadgeRect] 从封面矩形推出）
         */
        private fun drawCoverLockBadge(g2: Graphics2D, badge: java.awt.geom.Rectangle2D) {
            val x = badge.x.toFloat()
            val y = badge.y.toFloat()
            val s = badge.width.toFloat()

            // ── 衬底：一个半透明深色圆角块 ────────────────────────────────────
            //
            // 为什么需要：图标直接画在**封面图**上（可能是白色专辑封面）
            // ⇒ 必须有衬底保证对比度。取值与控件条的悬停底色同源（同一种"浮起"语义）。
            g2.color = java.awt.Color(0, 0, 0, COVER_LOCK_BADGE_SCRIM_ALPHA)
            g2.fill(RoundRectangle2D.Float(x, y, s, badge.height.toFloat(), s * 0.3f, s * 0.3f))

            // ── 图标：锁梁（上方弧）＋ 锁体（下方圆角矩形）────────────────────
            //
            // 比例：锁体占下方 ~52%，锁梁占上方 ~34%（其余是边距）。
            val pad = s * 0.22f
            val bodyW = s - 2f * pad
            val bodyH = bodyW * 0.78f
            val bodyY = y + s - pad - bodyH
            val shackleH = bodyH * 0.72f
            val shackleW = bodyW * 0.62f
            val shackleX = x + (s - shackleW) / 2f
            val shackleY = bodyY - shackleH

            g2.color = PlaybackControls.ICON_COLOR
            g2.stroke = BasicStroke(
                (s * 0.11f).coerceAtLeast(1f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )
            // 锁梁：180° 的半环（开口朝下）。`Arc2D.OPEN` + 从 0° 扫 180° 即上半环。
            g2.draw(
                java.awt.geom.Arc2D.Float(
                    shackleX, shackleY, shackleW, shackleH * 2f,
                    0f, 180f, java.awt.geom.Arc2D.OPEN,
                ),
            )
            // 锁体
            g2.fill(
                RoundRectangle2D.Float(
                    x + pad, bodyY, bodyW, bodyH,
                    s * 0.14f, s * 0.14f,
                ),
            )
        }

        /**
         * 画左侧的专辑封面。**`cover.enabled` 的元素本体**（2026-09-17 接线，原为死设置）。
         *
         * ## 三个必须处理的细节
         *
         * ① **居中方形裁剪**。封面图很少是正方形（CD 封面是，但用户拍的、
         *    下载的常常不是）。直接 `drawImage` 到正方形区域会**拉伸变形** ——
         *    人像会被压扁。故取 `min(宽, 高)` 的中心方形作为源区域
         *    （原实现 `IslandLeadingContent.cover` 的同一做法）。
         * ② **圆角裁剪**。与词岛的整体圆角风格协调 —— 圆角形态的词岛用
         *    [COVER_ARC_RATIO]（18%），方形词岛用更小的 [COVER_ARC_RATIO_SQUARE]
         *    （6%，方形岛上出现一个大圆角封面会很突兀）。
         * ③ **极淡的白色描边**。深色封面上看不出边界，浅色封面又会与浅色背景糊在一起 ——
         *    一层 alpha 35 的白描边在任何封面上都能勾出轮廓，且几乎不可见。
         *
         * ## 为什么图片为 `null` 时"什么都不画"（而不是画一个占位图标）
         *
         * 任务书的明确要求是"`coverFor` 首次返回 null 时**跳过绘制**"。
         * 原实现会画一个半透明方块 + 唱片图标作为占位；本项目先按"跳过"落地，
         * **占位空间仍由 [leadingCoverSize] 保留**（否则封面到达时文字会跳动）。
         * 若用户反馈"没有封面的歌左边空一块难看"，再补占位图标即可 ——
         * 那是纯视觉增量，不影响这里的任何契约。
         *
         * @param cover 本帧的绘制参数（旧图 / 新图 / 新图占比）。两者都为 `null`
         *   时才直接返回 —— **淡出期间 `shown` 已是 `null`，但 `prev` 还在**，
         *   那时必须继续画（否则淡出会被截断成一帧消失）。
         * @param size 封面**内容**的边长（px），由 [leadingCoverSize] 给出 ——
         *   **不含**黑胶环（`cover.vinyl`）。环宽由 [coverVinylRingWidth] 从 `size` 推导、
         *   画在 `size` **之外**；含环的槽位宽度见 [coverSlotWidth]。
         */

        private fun drawCover(
            g2: Graphics2D,
            local: Appearance,
            cover: CoverPaint,
            size: Float,
            /**
             * 封面槽位的**左边缘**（面板坐标）—— 由 [BandGeometry.coverRect] 给出。
             *
             * ⚠️ 2026-09-23：此前这里收的是"岛体左边缘"、再由本函数自己加
             *    `LEADING_INSET` —— 那是**第二份几何**（与 `areaLeft` 的推导各算一套）。
             *    现在槽位矩形由 [BandGeometry] 唯一给出，本函数只消费。
             */
            coverLeft: Float,
            /**
             * 封面槽位的**上边缘**（面板坐标）—— 由 [BandGeometry.coverTopFor] 给出。
             *
             * ⚠️ 2026-09-23：此前是"岛体上边缘 + 在**岛体**内居中"（用户第 ② 条
             *    「移动到下方的空位」）—— 而岛体在纵向**含控件带**，于是 `f = 1` 时
             *    封面横跨进控件带（真机取证：重叠 **66 px**，必然压住 22 px 高的进度条）。
             *    用户第 ③ 条明确否掉了它：「**封面在词岛生长仍然与其他控件重叠**」。
             *    现在锚在**内容区**内居中（[BandGeometry.coverTopFor]）。
             */
            coverTop: Float,
            /**
             * 本帧的**播放位置**（毫秒）—— 转动角度由它推导（**不是墙钟时间**）。
             * 用位置才能"暂停即停"，且让角度是纯函数（见 [coverRotationDegrees]）。
             */
            positionMs: Long,
        ) {
            if (cover.shown == null && cover.prev == null) return
            if (size <= 0f) return

            // ── 黑胶环宽（`cover.vinyl`）──────────────────────────────────────
            //
            // ⚠️ **2026-09-22 接线修正：`size` 是槽位边长 = 黑胶的「外径」，不是内容边长。**
            //    环**向内**占：`内容 = 外径 − 2 × 环宽`。
            //
            //    **原状**：把 `size` 当内容、把环画在 `size` **之外** ⇒ 画出的外径
            //    `= size + 2 × 环宽`。环宽只有 6px 时看不出来，但用户要求
            //    「**环宽 = 圆形封面的半径的 1/3**」⇒ 环宽 = `外径/6` = 17.5px
            //    ⇒ **外径膨胀 35px**：右半圈压到歌词上、左半圈从词岛左边缘戳出去。
            //    （这正是 `render/CoverRing.kt` 那段"接线状态"记录的缺陷，
            //      也是"两半必须同时部署"的原因 —— 只改常量不改这里 = 比改前更糟。）
            val ring = coverVinylRingWidth(size, local.coverVinyl)
            // 内容（图片）边长：外径减去两侧环宽。`coerceAtLeast(1f)` 防极端值下退化成 0/负。
            val content = (size - 2f * ring).coerceAtLeast(1f)
            // 环的**最外圈**贴封面槽位左边缘（`coverLeft` = 岛体左边缘 + `LEADING_INSET`）；
            // 内容再内缩一个环宽。于是环恰好铺满 `[coverLeft, coverLeft + size]`
            // —— 与槽位一致，不外溢。
            val left = coverLeft
            val contentLeft = left + ring
            // ── 2026-09-23：纵向由 [BandGeometry.coverTopFor] 唯一给出 ──────────────
            //
            // 原状：本函数自己按"岛体上边缘 + 在岛体内居中"算 ——
            //   而岛体纵向含控件带 ⇒ `f = 1` 时封面横跨进控件带（压住进度条）。
            // 现在槽位矩形（含纵向锚点）由 `BandGeometry` 给出，与 `areaLeft` 的
            //   横向让位**同一份几何**，故"封面不压控件、不压歌词"是结构性质。
            // ⚠️ 居中的是**外径**（`size`）—— 环是封面的一部分，必须一起居中。
            val top = coverTop
            val contentTop = top + ring
            // ── 形状：圆形 / 方形 / 圆角矩形（`cover.shape`，2026-09-21）──────────
            //
            // 用户附**黑胶唱片**截图：「新增**圆形封面**选项并允许其转动。」
            // 圆形与「转动」配起来才是黑胶那个观感 —— 方形封面转起来只是"图片在转"。
            //
            // ⚠️ 三条分支产出**同一个 `Shape` 变量**，下游（`clip` 与命中域登记）
            //    **不必知道是哪一种** —— 这正是"命中域必须来自绘制时实际用过的那个 shape"
            //    的落地方式（见下面 `drawnCoverBounds` 的注释）。加形状时只要多一条分支，
            //    命中判定**自动跟着走**，不会出现"看得见的封面点不中"。
            // ★★★ 2026-10-13（**BUG-183**）：★ 抽出为助手 [coverShapeAt] ——
            //   ★★ 因为**命中域也要造一个形状**（开黑胶时用**外径**）✗
            //   ⇒ ★ 若在这里另写一份 `when`，就是**第二份几何实现** ⇒ 必然漂移 ✗
            //   ⇒ ★ **同一个函数、两种尺寸** ⇒ 形状种类只有一处定义 ✓
            val shape: Shape = coverShapeAt(contentLeft, contentTop, content, local)

            // ── 封面进命中域（`island.hit.cover`，2026-09-19）─────────────────────
            //
            // 用户原话：「**追加封面命中开关。**」
            //
            // ## 为什么在这里登记（而不是在 `paintComponent` 里另算一遍几何）
            //
            // 与 `drawnRows` 同一条纪律（见其 KDoc）：命中域必须来自**绘制时实际用过的
            // 那个 shape**，不能在鼠标事件里按设置重推一遍 —— 后者是第二份几何实现，
            // 一旦 `leadingCoverSize` / `LEADING_INSET` / 展开态有一处改动只改一边，
            // 表现就是"看得见的封面点不中"，而两边单独看都对。
            //
            // ## 为什么登记的是**圆角形状**而不是外接矩形
            //
            // 封面用 `clip(shape)` 画成圆角，四个角上露出的是**词岛背景**而不是封面图。
            // 用外接矩形会让"点在封面左上角的背景上"也判成命中封面 —— 那是
            // "命中了看不见的东西"。`RoundRectangle2D.contains` 与绘制用的是同一个
            // 形状，故所见即所命中。
            //
            // ## 为什么不加 `controls.hitPaddingPx` 的外扩
            //
            // 那个外扩的语义是"字形太细，鼠标没对准也给命中"（见 `GlyphHitTester`）。
            // 封面是一块几十像素见方的实心区域，**不需要容错**；
            // 给它外扩反而会让命中域溢出到封面之外的背景上。
            // ★★★ 2026-10-13（**BUG-183**）：★ **黑胶环参与封面命中** ✓
            //
            // ## 用户原话（逐字）
            //
            // > 「**黑胶环设置为参与封面命中。**」
            //
            // ## ★★ 根因
            //
            // ★ 环画在封面**内容形状之外**（`contentLeft = left + ring`，见 [CoverRing]）✓
            //   ⇒ ★★ 而命中域登记的是**内容形状** ⇒ **点在环上不算命中封面** ✗
            // ★ 表现：● 开黑胶时，点封面中心能用、点外圈黑边**没反应** ✓
            //
            // ## 修法：★ **命中域 = 绘制域的并集**（内容 ∪ 环）⇒ 开黑胶时用**外径形状**
            //
            // ★ 关黑胶时 `ring = 0` ⇒ 外径形状**逐位等于**内容形状 ✓ ⇒ **零影响** ✓
            // ★ 这与本段既有纪律（"所见即所命中"）**完全同向**：★ 环也画出来了 ⇒
            //   ★★ 就该点得中 ⇒ 登记它 ✓
            if (local.hitCoverEnabled && cover.shown != null) {
                drawnCoverBounds = if (ring > 0f) coverShapeAt(left, top, size, local) else shape
            }

            val copy = g2.create() as Graphics2D
            try {
                // clip 到封面形状（圆形 / 圆角矩形）—— 这是"异形封面"唯一不需要
                // 手算每个像素的做法。
                copy.clip(shape)
                // ── 转动（`cover.rotate`，2026-09-21）──────────────────────────
                //
                // ⚠️ 顺序是硬约束：**先 clip 再 rotate**。
                //    圆形下两者等价（圆绕心旋转是自身），但换成圆角矩形就会暴露差别 ——
                //    先转再 clip 会把**旋转后的外接矩形**当成裁剪域，四角露出没被裁掉的原图。
                //    先 clip 则裁剪域恒为那个**静止的** shape，与命中域登记的形状一致。
                //
                // 绕**封面中心**旋转：圆心即中心，故旋转后图像仍完整落在 clip 域内。
                val rotation = if (local.coverRotate) {
                    coverRotationDegrees(positionMs, local.coverRotatePeriodS)
                } else {
                    0f
                }
                if (rotation != 0f) {
                    copy.rotate(
                        Math.toRadians(rotation.toDouble()),
                        (left + size / 2f).toDouble(),
                        (top + size / 2f).toDouble(),
                    )
                }
                // 双三次插值：封面通常是**缩小**绘制的（几百像素 → 56 像素），
                // 默认的最近邻会让画面出现明显的锯齿与摩尔纹。
                copy.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                )
                // ⚠️ 顺序是硬约束：**旧图先画、新图后画**。
                //    反过来的话，交叉淡化中段新图会被旧图盖掉一半，
                //    表现为"先看到旧图、再看到新图"（那是**接力**不是**交叉**）。
                //
                // ⚠️ 画在**内容**矩形上（`contentLeft/contentTop/content`）——
                //    `size` 是**外径**（含黑胶环），图片只占内缩后的那一块。
                cover.prev?.let {
                    drawCoverImage(copy, it, contentLeft, contentTop, content, 1f - cover.mix, local.coverSharpness)
                }
                cover.shown?.let {
                    drawCoverImage(copy, it, contentLeft, contentTop, content, cover.mix, local.coverSharpness)
                }
            } finally {
                // create() 出来的 Graphics2D 必须 dispose（与 paintComponent 同一条纪律：
                // 高频重绘下不 dispose 会累积 native 资源，在 Windows 上是 GDI 对象泄漏）。
                copy.dispose()
            }

            // 描边画在**未裁剪**的 g2 上：`g2` 此刻已被 clip 到词岛轮廓（paintComponent 里），
            // 但**没有**被 clip 到封面的圆角形状 —— 故这圈描边是完整的（不会被自己裁掉一半）。
            g2.color = COVER_OUTLINE
            g2.draw(shape)

            // ── 黑胶环（`cover.vinyl`，2026-09-21）────────────────────────────
            //
            // 用户原话：「旋转圆形封面加上黑边，使其呈现黑胶样式
            // （在**外围**加黑边，**不要侵占封面内容**）。」
            //
            // ## 几何：外扩带 **减去** 内容形状
            //
            // `BasicStroke(2 × ring).createStrokedShape(shape)` 得到的是沿内容轮廓
            // **内外各外扩 `ring`** 的带。再 `subtract(Area(shape))` 去掉内侧半圈 ——
            // 剩下的就是"**只在封面之外**"的那一圈。
            //
            // 两个好处，各对应一条要求：
            //   ① **不侵占封面内容** —— 若直接 `fill` 那条带，内侧半圈会盖住封面图一圈
            //      （正是用户点名不要的）；
            //   ② 形状**自动跟随** `cover.shape` —— 圆形 → 圆环（黑胶）、
            //      方形 → 方框、圆角 → 圆角框，不需要为每种形状各写一遍。
            //
            // ## 为什么画在**这里**（未裁剪的 g2、封面之后）
            //
            // 与上面那圈描边同理：`g2` 已被 clip 到**词岛轮廓**（`paintComponent` 里），
            // 但**没有**被 clip 到封面形状 —— 故环是完整的（不会被自己裁掉一半），
            // 同时也不会戳出词岛（词岛轮廓的 clip 仍在）。
            if (ring > 0f) {
                val ringArea = Area(BasicStroke(ring * 2f).createStrokedShape(shape))
                ringArea.subtract(Area(shape))
                g2.color = VINYL_RING_COLOR
                g2.fill(ringArea)
            }
        }

        /**
         * 画**一张**封面（居中方形裁剪 + 指定不透明度）。**调用方负责 clip 与插值提示。**
         *
         * ## 为什么把"一张图"抽出来
         *
         * 交叉淡化要画两张 —— 若把 `drawImage` 那一段复制一遍，两份裁剪公式
         * （`min(宽,高)` 的中心方形）迟早会不一致，表现是"淡入的那张变形了"。
         *
         * @param alpha `1f` 时**不建图层**（走快路径）：静止态是绝大多数帧，
         *   而每帧多建一个 `Graphics2D` 就是每帧多一份 native 资源。
         */
        private fun drawCoverImage(
            g: Graphics2D,
            image: BufferedImage,
            left: Float,
            top: Float,
            size: Float,
            alpha: Float,
            /** ★ 封面清晰度（`cover.sharpness`，BUG-188）。 */
            sharpness: Int,
        ) {
            val a = alpha.coerceIn(0f, 1f)
            if (a <= 0f) return
            val layer = if (a >= 1f) {
                g
            } else {
                (g.create() as Graphics2D).also {
                    it.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)
                }
            }
            try {
                // ⚠️⚠️ 2026-09-21（BUG-122）：**必须走 [CoverScaler]**。
                //    原写法是一次 `drawImage(src 子矩形 → dest 矩形)`，而 Java2D 的
                //    缩放插值**默认是最近邻** ⇒ 512px 封面缩到 ≤56px 的槽位必然糊/马赛克。
                //    全树 grep `KEY_INTERPOLATION` **零命中** —— 这是预览与真机**两处**
                //    绘制点共同的根因（用户真机原话：「示例封面很**糊**…我提供给你的
                //    原文件都没这么糊」）。
                //    顺带：中心方形裁剪（原 `side` / `sourceX` / `sourceY` 三行）
                //    已收进 `CoverScaler`，与预览**同源**（预览此前会把非正方形封面
                //    拉伸变形，两处观感不一致）。
                //    ⚠️ 目标边长取 `ceil(size)` 后画到精确的 `size` 矩形 ——
                //       剩下那点亚像素差由 BILINEAR 兜住，观感无损。
                //
                // ★★★ 2026-10-13（**BUG-189**）：**必须乘设备缩放**。
                //   用户原话：「**词岛显示的封面分辨率太低**」（附截图：歌词字清晰、封面糊）。
                //   ★ 根因：`size` 是**逻辑**像素，而 `g` 带着**设备变换**（实测该机 **125%**）。
                //     只渲染 `size × size` 个像素、却被放大到 `size × 1.25` 个**物理**像素
                //     ⇒ **放大 1.25×**（信息量没变）⇒ 糊 ✗
                //   ★ 修法：渲染 `ceil(size × 设备缩放)` 个像素 ⇒ 与物理像素 **1:1** ✓
                //   ★★ 关键：**绘制目标矩形仍是逻辑坐标**（`left/top/size` 不动）——
                //     设备变换会把它放大到物理像素，恰好与源位图一一对应 ⇒
                //     ★ **屏幕上的大小形态逐像素不变**（正是用户要的「大小形态不变」）✓
                //   ⚠️ 设备缩放取不到时退 `1f`（行为与改前一致，零风险）✓
                //   ★★ 算式收口到 [bitmapRenderPx]（**唯一出口**）—— 理由见它的 KDoc：
                //      "判据必须建在调用点上"，写在这里探针就只能测**恒真式** ✗
                val px = bitmapRenderPx(size, deviceScaleOf(g))
                layer.drawImage(
                    CoverScaler.squareScaled(image, px, sharpness),
                    left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt(),
                    0, 0, px, px,
                    null,
                )
            } finally {
                if (layer !== g) layer.dispose()
            }
        }

        /**
         * **当前行（及其译文）的入场变换层**：`mainEnter: 0 → 1` 期间把绘制整体
         * 位移 + 淡入，落位后（`mainEnter == 1f`）**完全零开销**（直接跑 `body`）。
         *
         * ## 2026-09-28 新增（用户报「消失动画做了但显现动画没做，显得割裂」）
         *
         * 与 [drawExitingMainLine] 是**镜像**：那边动的是**旧行**、走 `mainExit`（0→1 退场）；
         * 这边动的是**新行（当前行 + 其译文）**、走 `mainEnter`（0→1 落位）。
         *
         * ## ★★ 为什么必须是"包一层"而不是"把坐标算好再传进去"
         *
         * 关键约束：**`drawnRows`（字形命中域）必须登记"静止几何"**。
         *
         * 入场期间新行实际画在偏移位置，但命中域**不能**跟着偏移 —— 否则：
         *   · 180 ms 内鼠标正好点在"文字看起来在的位置"会**点不中**；
         *   · 更要紧的是**命中域每帧都在变**，而 `REGIONAL_CLICK_THROUGH`
         *     靠它决定窗口哪块不穿透 ⇒ 穿透区域在换行时**闪动**。
         *
         * 故 [drawOneLine] / [drawTranslationLine] 里 `drawnRows.add(...)` 仍然用
         * **未变换的** `baselineX/baselineY`，只有**送给渲染器的画布**被变换 ——
         * 本助手就是那个"只换画布、不换几何"的接缝。
         *
         * > 这正是本项目"**改了 A 但 A 的输入被 B 掐断**"的反面用法：
         * > 变化被**限制在一层复合里**，未动的东西（几何、命中域、布局计算）
         * > 连一个字节都没改。
         *
         * ## 风格化
         *
         * 与退场**同源**：`Motions.resolve(Motions.LYRIC_ENTER, local.lyricMotionStyle)` ——
         * 故用户换 `lyric.animation_style` 时两端一起变，不会出现"上滑走 + 原地浮现"。
         *
         * ## 方向：`+lineHeight`（从下方升上来）
         *
         * 退场是 `-lineHeight`（上滑走）。入场取正值 → 拼成**同一条向上的轨迹**：
         * 旧行从原位上滑离去，新行从原位下方升到原位 —— 读起来是"歌词在向上流动"。
         * 用负值会让新旧两行**相向而行**（旧行往上、新行也从上面下来），像撞在一起。
         *
         * @param restingBaselineX 新行**静止时**的基线 x（入场不水平移动，只为算缩放中心）
         * @param restingBaselineY 新行**静止时**的基线 y（同上）
         * @param layout 新行的文本布局（**必须与 `body` 里画的是同一份** ——
         *   否则缩放中心会按另一个文本的宽度算，POP 档下看起来偏心）
         */
        private inline fun drawWithEnterTransform(
            g2: Graphics2D,
            local: Appearance,
            restingBaselineX: Float,
            restingBaselineY: Float,
            layout: TextLayout,
            lineHeight: Float,
            body: (Graphics2D) -> Unit,
        ) {
            val progress = mainEnter
            // 静止态（绝大多数帧）：不建复合、不设 alpha —— **零行为变化**。
            // ⚠️ 这条 early-return 是"没做动画的用户零代价"的保证，不要为了统一而删掉。
            if (progress >= 1f) {
                body(g2)
                return
            }
            val alpha = progress.coerceIn(0f, 1f)
            if (alpha <= 0f) return
            val spec = Motions.resolve(Motions.LYRIC_ENTER, local.lyricMotionStyle)
            // 距离**由布局给**（正号 = 向下 = 从下方升上来，见 MotionStyle.offsetYAt 的约定）。
            val dy = spec.style.offsetYAt(progress, lineHeight)
            val scale = spec.style.scaleAt(progress)
            val layer = g2.create() as Graphics2D
            try {
                layer.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                if (dy != 0f || scale != 1f) {
                    // 绕**文字自身中心**缩放（与退场同一条规则）—— 否则 POP 档会从基线"长出来"。
                    val cx = restingBaselineX + layout.advance / 2f
                    val cy = lyricCenterYFromBaseline(restingBaselineY, layout.ascent, layout.descent)
                    layer.transform(
                        AffineTransform().apply {
                            translate(0.0, dy.toDouble())
                            translate(cx.toDouble(), cy.toDouble())
                            scale(scale.toDouble(), scale.toDouble())
                            translate(-cx.toDouble(), -cy.toDouble())
                        },
                    )
                }
                body(layer)
            } finally {
                layer.dispose()
            }
        }

        /**
         * 画**正在退场的上一行歌词**（上滑 + 淡出）。**必须在 EDT 上调用。**
         *
         * ## 三条设计约束（都由"单槽 + 高频事件"推出来）
         *
         * ① **新行不参与** —— 本方法只画旧行。
         *    ⚠️ 2026-09-28：原记为"新行的几何与不透明度**一个像素都不动**"，
         *    现**新行也有入场**（[drawWithEnterTransform]）—— 但**命中域仍不受影响**
         *    （那边只变换画布、不登记偏移坐标），故本条约束的**目的**（零命中代价）仍成立。
         * ② **`drawnRows` 不登记它** —— 它正在离开，点它不该算命中
         *    （登记了会让"鼠标点在一段正在消失的文字上"触发悬停展开）。
         * ③ **`karaoke = false` + `progress = 1f`** —— 旧行此刻**已唱完**，
         *    整行用已唱色即可；这同时走渲染器的最快路径（不设 clip、不算字形簇）。
         *
         * ## 风格化
         *
         * 位移与缩放都走 `MotionStyle`（`Motions.resolve` 后取 `offsetYAt` / `scaleAt`），
         * 故 `animation.style` 换档时它**跟着变**（FADE = 原地淡出 / SLIDE = 上滑 /
         * POP = 缩小淡出）。距离由**布局**给（`-lineHeight`）——
         * 上移量是"一个行高"，随字号变，写死像素会在用户改字号后错位
         * （见 `MotionSpec.distanceFromLayout`）。
         *
         * @param baselineY 新行的基线 y。旧行从**同一个位置**出发向上移
         *   —— 两行是同一个槽位，故"出发点"本就相同（不必存上一帧的坐标）。
         * @param lineHeight 一个行高（含 `layout.line_gap_px`），退场距离
         */
        private fun drawExitingMainLine(
            g2: Graphics2D,
            local: Appearance,
            coverRgb: Int?,
            width: Int,
            areaLeft: Float,
            baselineY: Float,
            lineHeight: Float,
        ) {
            val text = exitingMainText ?: return
            val progress = mainExit
            if (progress >= 1f) return
            val alpha = (1f - progress).coerceIn(0f, 1f)
            if (alpha <= 0f) return
            // 旧行的布局按**同一份字体**现算（字体已在 `drawLyricSlots` 开头设好）——
            // `RenderCache` 按文本缓存，故这一行的 TextLayout 会被复用而不是每帧重建。
            val layout = runCatching { renderCache.textLayoutFor(text) }.getOrNull() ?: return
            val spec = Motions.resolve(Motions.LYRIC_EXIT, local.lyricMotionStyle)
            val x = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)
            // 距离**由布局给**（负号 = 向上，见 MotionStyle.offsetYAt 的约定）。
            val dy = spec.style.offsetYAt(progress, -lineHeight)
            val scale = spec.style.scaleAt(progress)
            val layer = g2.create() as Graphics2D
            try {
                layer.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                if (dy != 0f || scale != 1f) {
                    // 绕**文字自身中心**缩放（不是绕基线）—— 否则 POP 档会从基线"长出来"，
                    // 看起来像文字在往上抽，而不是原地缩小。
                    val cx = x + layout.advance / 2f
                    val cy = lyricCenterYFromBaseline(baselineY, layout.ascent, layout.descent)
                    layer.transform(
                        AffineTransform().apply {
                            translate(0.0, dy.toDouble())
                            translate(cx.toDouble(), cy.toDouble())
                            scale(scale.toDouble(), scale.toDouble())
                            translate(-cx.toDouble(), -cy.toDouble())
                        },
                    )
                }
                val packed = local.sungColorFor(coverRgb).toPackedArgb()
                karaokeRenderer.render(
                    g = layer,
                    cache = renderCache,
                    layout = layout,
                    text = text,
                    baselineX = x,
                    baselineY = baselineY,
                    sungColor = packed,
                    unsungColor = packed,
                    progress = 1f,
                    karaoke = false,
                    outline = local.mainOutlineFor(),
                    smooth = false,
                )
            } finally {
                // 与 paintComponent / drawCover 同一条纪律：create() 出来的必须 dispose。
                layer.dispose()
            }
        }

        /**
         * 画**歌词背景**里的实时频谱（用户 2026-09-17 要求）。
         *
         * ## 原记 vs 实为
         *
         * 原记：频谱画在**左侧信息区**，与封面共用槽位、尺寸逐字一致、垂直居中，
         * 靠调用点保证"封面优先"。
         *
         * 实为：用户要求「已实现的频谱跳动效果保留，但应将其**置于歌词背景
         * （随着歌词长度变化）中进行跳动**，同时实现**随封面颜色更改频谱颜色**」。
         * → 左侧槽位让给封面**独占**；频谱改为铺在**歌词文字之下**的整条背景上。
         *
         * ## 几何：宽度跟随歌词区，而不是固定方形
         *
         * `areaLeft` / `areaWidth` 由调用方按**歌词区**给出（= 面板宽 − 左侧封面让出的宽），
         * 而 `layout.fixed_width = false` 时面板宽本身随歌词长度伸缩 ——
         * 于是频谱宽度**天然跟随歌词长度**，正是用户要的"随着歌词长度变化"。
         *
         * 左右各内缩 [HORIZONTAL_INSET]、上下各内缩半个 [VERTICAL_PADDING]：
         * 最外侧的柱子不至于贴着圆角被裁掉，顶部也留一点呼吸。
         *
         * ## 观感取舍：**低不透明度 + 向上淡出**
         *
         * 频谱在文字下面跳，最容易出的问题是**干扰阅读**。三条约束共同压制它：
         * ① 纵向渐变，底部 [SPECTRUM_BACKGROUND_ALPHA]、顶部**全透明** ——
         * 高柱子的顶端几乎看不见，视觉重量落在"文字下方的地面"；
         * ② 不描边、不加底板（改动前那圈 `COVER_OUTLINE` 是"卡片"语义，
         * 背景元素不该有边框）；
         * ③ 柱子的底边贴着歌词区底部，不侵入行间。
         *
         * 有意**不做**的事：不新增"频谱不透明度"设置项。用户没有要求，
         * 而设置项是**长期契约**（要配消费点、值域断言、文档），先给一个克制的固定值，
         * 真觉得不合适时再加键 —— 这比先加键再删键便宜。
         *
         * ## 为什么 `nowNanos` 传真实时刻、为什么固定 `Style.BARS`
         *
         * 同改动前：平滑回落与帧率无关靠这个时间戳；`WAVE` 是留给将来"频谱样式"
         * 设置项的（现在**没有那个键**），故取原实现 0.8.9 的竖条。
         *
         * ## 与 `coverRgb` 的关系（`spectrum.color_mode = cover` 的落点）
         *
         * 频谱色由 [Appearance.spectrumColorFor] 统一解析：`manual` 用固定色、
         * `cover` 跟随封面主色。本方法只消费结果 ——
         * 于是"跟随封面颜色"这条需求**不需要**在绘制层做任何判断，
         * 只要 `coverRgb` 传对了就生效（它就是 [IslandPanel.paintComponent] 里
         * 由封面图算出来的那个值）。
         *
         * ## 两个本轮新增的观感设置（2026-09-18）
         *
         * | 键 | 用户原话 | 落点 |
         * |---|---|---|
         * | `spectrum.bounce_pct` | 「频谱设置增加一项**相对跳动高度**」 | `bounceScale` → [SpectralPainter.paint] 的 `bounceScale` |
         * | `spectrum.progress_tint` | 「允许通过**频谱色差**表示歌曲播放进度」 | `progress` + [backgroundSpectrumProgressPaint] |
         *
         * 两者都**默认关闭/原样**（`100` / `false`），故默认安装的观感与改动前逐位相同。
         *
         * ## 为什么这两个键**在这里读**，而不是加进 `Appearance`
         *
         * `Appearance` 是本项目"渲染层只读快照"的正规入口，绝大多数外观项都走它。
         * 但这两个键的取值**每帧都可能变**（进度随播放走、跳动幅度随滑杆走），
         * 而 `Appearance` 只在**设置变更**时重建 —— 塞进去会得到"进度永远停在
         * 改设置的那一刻"这种极难定位的错误。
         *
         * 于是它们与 `controls.*` 走同一条既有路径：在**消费点**用 [intOf] / [boolOf]
         * 按需读（见 `controlsConfig` 的同类取舍 —— 几次 map 查找，纳秒量级，
         * 不值得缓存，缓存反而会引入"哪一帧读了"的推理负担）。
         *
         * ## 暂停时不画
         *
         * `playing = false` 时交给 [SpectralPainter.paint] 快速归零并在归零后短路 ——
         * 见那里的注释（用户要求"暂停后尽快归零"）。
         */
        private fun drawBackgroundSpectrum(
            g2: Graphics2D,
            local: Appearance,
            bands: FloatArray?,
            areaLeft: Float,
            areaTop: Float,
            areaWidth: Float,
            areaHeight: Float,
            coverRgb: Int?,
            playing: Boolean,
            /**
             * ★★★ 2026-10-04：**插槽区域**（`RADIAL` 档的极坐标画布）。
             *
             * ⚠️ 必须由**调用方**给（本函数看不到 `bg` / `rightBandWidth` ——
             * 它们是 `paintComponent` 局部量）⇒ 与 occupant 一样"算一次、传进来"。
             *
             * ★ 只有 `RADIAL` 档会用它；其余档**完全忽略** ⇒ 另三档逐像素不变。
             */
            slotRect: java.awt.Rectangle,
            /**
             * ★★★ 2026-10-04：**右端插槽的 occupant**（`rightSlotOccupant` 的产物）。
             *
             * ## 为什么必须**传进来**（而不是在本函数里再算一次）
             *
             * occupant 的判据是 `rightSlotOccupant(slotKey, spectrumAnchor)` —— 它内含
             * **迁移**（`anchor == RIGHT` ⇒ 视为 `SPECTRUM`）。若本函数再算一次：
             * ★ 两处各算一遍 ⇒ **迟早漂移**（本项目最多次的缺陷形态），
             * 且 `spectrum.anchor` 被改时会出现"让位按一个 occupant、绘制按另一个"。
             *
             * ★ 调用方（`paintComponent`）**已经算好** `slotOccupant` ⇒ 直接传。
             */
            slotOccupant: String,
            /**
             * 是否画**峰值保持**的"帽"（`animation.quality = ULTRA`）。
             * 由 `paintComponent` 从 `animator.effectiveHighCostEffects` 传进来 ——
             * 绘制层不自己读设置（与 `local.controlsMotionStyle` 同一条纪律）。
             */
            peakHold: Boolean,
            /**
             * 本帧形状的**上 / 下边圆角半径**（[islandOutlineAt] 的产物）。
             *
             * ⚠️ 2026-09-22 新增：圆角随展开进度变形 ⇒ 频谱的"圆角感知内缩"必须用
             * **本帧真正用的**那两个半径。否则 `PILL` 档展开时内缩仍按收起态的
             * `短边/2`（≈62px）算，而实际轮廓半径只有 16px ⇒ 频谱**左右各白缩 46px**。
             */
            topRadiusPx: Float,
            bottomRadiusPx: Float,
            /**
             * ★★★ 2026-10-11（⑰「封面多色流光」· **频谱侧接线**）：
             * 本帧的**封面多色**（`CoverColorExtractor.palette` 的返回）。
             *
             * ★ 只在 `spectrum.color_mode = cover_flow` 时被读 —— 其余档**一个分支都不多走**
             * （空列表 ⇒ 退回既有单色路径 ⇒ **逐像素等于改动前**）✓
             * ★ 由调用方传（`paintComponent` 已按图引用缓存算好，见 `coverPaletteFor`）——
             * 本函数**不自己调 `palette()`**（那是每帧 1024 次采样 + 1024 次 HSB 转换）。
             */
            coverPalette: List<Int>,
            /**
             * ★★★ 2026-10-11：**流光相位**（`0..1` = 一个完整周期）。
             *
             * ★ 由 `loopPhase(播放位置, FLOW_PERIOD_MS)` 给出 ⇒ **暂停即冻结**
             * （与水位波动、封面转动、背景流光**同一个时钟**）。
             * ★ 只有 `cover_flow` 档读它；其余档传什么都不会改变像素 ✓
             */
            flowPhase: Float,
        ) {
            // ★★★ 2026-10-10（**BUG-162**）：**"画不画"的判据已移到【调用方】** ——
            //   ★ 因为频谱现在有**两个目标**（主带 / 插槽），而两个目标的判据**输入不同**：
            //     · 主带 ⇒ `shouldDrawSpectrumBand(anchor, enabled)`
            //     · 插槽 ⇒ `shouldDrawSpectrumInSlot(occupant)`
            //   ⇒ ★ 本函数**拿不到"我是哪个目标"**（`slotOccupant` 在两处都可能相同）
            //   ⇒ ★ 若在这里判，就只能"假装 occupant"来区分 ⇒ ★★ `occupant = NONE` 时
            //     两个目标判据相同 ⇒ **主带被画两次** ✗
            //   ⇒ ★ 故：★ **调用方用那两个纯函数门控**（真机与预览共用同一对函数）✓
            //   ★ 本函数现在只负责"**给定目标就把它画出来**" + 数据/尺寸的健全性检查 ✓
            //
            // ⚠️ **历史（BUG-153 / 153b，2026-10-05）**：★ 这里原来判的是
            //   `shouldDrawSpectrumBand(local.spectrumAnchor, slotOccupant, local.spectrumEnabled)` ——
            //   ★ 它内含用户拍板的口径「**总开关不管小组件**」（插槽档由 `right_slot.component` 管）✓
            //   ★ 那条口径**保留**（搬到了 `shouldDrawSpectrumInSlot`）✓
            if (bands == null || bands.isEmpty()) return
            if (areaWidth <= 1f || areaHeight <= 1f) return

            // ── ⚠️ 2026-09-22：`islandHeight` 这个入参**已删除**（BUG-126 的收尾）──
            //
            // 它曾被用来给副频谱定位（`islandHeight - secHeight` / `0f`）。
            // BUG-077 把它从 `height`（面板高）改成 `baseHeightPx`（收起态岛高）——
            // **修了"高"、没修"顶"**：`position = TOP` 时词岛从控件带高 `stripQ` 开始，
            // 而 `0f` 那个锚点把 TOP 档的副频谱画进了**控件条那条带**里。
            // 现在两个锚点都改由 [secondarySpectrumBands] 从**歌词区**的两条边算出，
            // 于是它变成**死参数** —— 留着一定会被下一个人再当成"词岛顶/底"用一次，
            // 故**删掉**（而不是留着不用）。
            //
            // ⚠️ 也**不要**改用 `bg.islandTop / bg.islandBottom`：那会让副频谱在动画期间
            //    随带子一起"长出去"，而用户要的是**静止的歌词块**的边缘。

            // ── 高度与贴边（`spectrum.height_pct` / `spectrum.anchor`，2026-09-17 新增）──
            //
            // 用户原话（2026-09-17）：「频谱**相对歌词框底边/顶边高度**也没做到」。
            // 用户原话（2026-09-18，本轮）：「频谱**贴边（贴顶边）逻辑有误，贴不到顶边**」。
            //
            // ## 上一版为什么"贴不到顶边"（本轮的根因，不是差几像素）
            //
            // 上一版只挪了**带矩形**（`top` / `bottom`），而 `SpectralPainter.paintBars`
            // 恒以 `bounds.y + bounds.height` 为基线**向上**生长 —— 于是
            // `spectrum.height_pct` 取**默认值 100** 时两档逐像素相同：
            //
            // | 档 | 带矩形 | 柱子基线 |
            // |---|---|---|
            // | `BOTTOM`（默认） | `[areaTop, areaTop+areaHeight]` | `areaTop+areaHeight`（底边） |
            // | `TOP` | `[areaTop, areaTop+areaHeight]` | `areaTop+areaHeight`（**还是底边**） |
            //
            // 用户选了"贴顶边"却看不到任何变化 → 报「贴不到顶边」。
            // 对称语义的出处（`SettingRegistry` 里 `spectrum.anchor` 的逐字注释）是
            // 「**柱子的底边**贴着歌词区底部往上长」→ 那么 `TOP` 必须是
            // 「**柱子的顶边**贴着歌词区顶边往下长」。故本轮除了矩形，还要**翻向**。
            //
            // 几何算式（含两端都不留白）与"为什么基线才是判据"见
            // `render/SpectrumGeometry.kt` 的 [spectrumBandGeometry] —— 抽成纯函数
            // 是为了让探针能直接断言"改前 y = areaTop+areaHeight / 改后 y = areaTop"。
            // ★★★ 2026-10-11（**U13 · 插槽小频谱 = 「侧边条」呈现**）──────────────────
            //
            // ## 用户两轮报障 + 设计定案
            //
            // · 症状：「**不显示** 或者 **显示的是缩小的主频谱**」——
            //   ★ 根因 = 条数推导被 `SPECTRUM_MIN_BARS`(24) **托底** ⇒
            //     **24 根塞进 28 px**（插槽 56 − 两侧圆角内缩各 14）⇒ 柱宽 ≈ 0.8 px ⇒
            //     **糊成一片**（= "缩小版主频谱"或"看不见"）✗
            //   ★ 修法（条数）= `forceBarCount = slotSideBarCountFor(该键)`（见 painter）✓
            //     ★★ 2026-10-13（**U16**）：条数由 `island.right_slot.spectrum_bars` 决定
            //       （**4 / 5 / 6**，默认 5）—— 原写"恒 5"已过期 ✓
            //       ★ 用户追加口径（逐字）：「**盒子宽度不变，频谱条的大小随条数自适应**」
            //       ⇒ 插槽恒 56、可用带恒 28，`slot = 28 / 条数` ⇒ 柱宽 **4.90 / 3.92 / 3.27** ✓
            // · **设计定案（用户 2026-10-11 逐字）**：「我们的小频谱设计是**五根**，
            //   **实时频谱**」—— 数据仍是**全频段重采样**（painter 的 `resampleBands`），
            //   ★ **不是** 原实现 那种"只取前 4 个低频频段"。
            // · **呈现形态**（参照物 = 原实现 0.8.9 `IslandLeadingContent.drawSpectrum`
            //   的四条侧边小频谱 + 网络经典做法；★ **只取形态**，柱数/数据按我们设计）：
            //   ① **以插槽纵向中线为轴，上下对称生长**（不是"从底往上长"——
            //      那正是"缩小版主频谱"的观感来源）；
            //   ② **满幅**（可长到整个歌词区高，**不受 `spectrum.height_pct` 影响** ——
            //      那个百分比是主带的观感标尺；插槽是独立组件，参照物就是撑满插槽）；
            //   ③ **实心**（`fade_top` 的"下实上透明"渐变在**对称双峰**下会把上半
            //      画成几乎透明 ⇒ 观感"只有下半"✗ ⇒ 插槽档强制关闭，与参照物的
            //      纯色填充一致）。
            // ⚠️ 本flag**在这里算一次**，下面（pct / symmetric / anchorTop /
            //    paintOverride / fadeTop / barPitch / forceBarCount）**共用同一个值** ——
            //    两处各判一遍迟早漂移（本项目最高频的缺陷形态）✓
            val slotSideBars = slotOccupant == dev.cdl.render.RIGHT_SLOT_SPECTRUM
            // ★ 满幅：插槽档不受 height_pct 影响（见上 ②）。
            val pct = if (slotSideBars) {
                1f
            } else {
                local.spectrumHeightPct.coerceIn(MIN_SPECTRUM_HEIGHT_PCT, 100) / 100f
            }
            // ⚠️ 这个布尔**在这里算一次**，下面（带几何 / 对称中线 / painter 调用）
            //    **共用同一个值** —— 三处各读一次 `boolOf(snapshot, ...)` 的话，
            //    任何一处漏改都会让"带的位置"与"画出来的形态"不一致（本项目踩过：
            //    同一函数两条调用路径参数表漂移）。
            // ★ 插槽档**强制对称**（见上 ①）—— 用户设置的 `spectrum.symmetric` 只管主带。
            // ★★ 2026-10-11（**U14 · 极坐标两档**）：**RADIAL / RING 强制【非】对称** ——
            //   极坐标下柱子的活动范围是**径向** `[cy−innerR, cy−outerR]`，经典形态是
            //   **从内向外单侧生长**（太阳 / 甜甜圈）；若用户开着 `spectrum.symmetric`，
            //   painter 会以"径向中点"为轴向**内外双向**长 ⇒ 观感完全崩坏 ✗
            //   （★ 这就是 RADIAL 此前"没效果 / 不像样"的真候选之一）✓
            // ★ `slotOwn` = "插槽自定义形态"（SPECTRUM 侧边条 + RADIAL/RING 极坐标两档）
            //   —— fade / 实心 / 柱距的强制判据用**这一个**值（一处算，多处共用）。
            val slotPolar = dev.cdl.render.isPolarSlotOccupant(slotOccupant)
            val slotOwn = slotSideBars || slotPolar
            // ★★★ 2026-10-12（**BUG-177**）：★ **环状档走"内外双峰"** ✓
            //
            // ★ 用户原话（逐字）：
            // > 「**环状内侧仍不会跳动（效仿上下双峰，做内外双峰）。**」
            //
            // ★★ 改前：极坐标两档**一律非对称** ⇒ 条只从内缘**向外**长 ✗
            //   ⇒ ★ 环的**内侧是一条静止的圆**（只有外缘在动）✗
            //
            // ★ 正解 = **环状档开 `symmetric`** ⇒ 条**跨着中半径**（内 / 外各长一半）✓
            //   ★ 这正好复用直角坐标"上下双峰"的**同一套机制**：
            //     `axis` = 中半径（`cy − (innerR+outerR)/2`，见 BUG-167 的修正）·
            //     `halfSpan` = `span/2`（上下各一半）⇒ ★ 内缘也跳起来 ✓
            //
            // ⚠️ **放射档（RADIAL）必须保持非对称** ✗ —— ★ 它的内半径是 **0**（交于圆心），
            //   ★★ 对称会让一半的条长到**圆心之外**（负半径）⇒ 观感崩坏 ✓
            // ★ 判据：`slotOccupant == RIGHT_SLOT_RING` ⇒ 只有环状开 ✓
            val symmetric = slotSideBars ||
                (slotPolar && slotOccupant == dev.cdl.render.RIGHT_SLOT_RING) ||
                (!slotPolar && boolOf(snapshot, "spectrum.symmetric"))
            val bandRaw = spectrumBandGeometry(
                areaTop = areaTop,
                areaHeight = areaHeight,
                heightFraction = pct,
                // ★ 插槽档强制 `growDown = false`：对称满幅带以中线为轴，
                //   翻转映射到它自己（见下方"对称模式"注释），但显式关掉更不易误读。
                anchorTop = !slotSideBars && local.spectrumAnchor == SPECTRUM_ANCHOR_TOP,
            )
            // ── 对称模式下：带**以歌词区中心为心**（2026-09-18 修我自己引入的缺陷）──
            //
            // ## 为什么必须改
            //
            // `spectrum.anchor`（贴顶 / 贴底）在**对称**模式下没有意义 ——
            // 双峰以**歌词行中线**为轴，那条轴才是真正的锚点。而
            // [spectrumBandGeometry] 是按 anchor 把带贴在歌词区的**某一条边**上的：
            //
            // ```
            // 底边档：带 = [areaTop + areaHeight - bandH, areaTop + areaHeight]
            // 轴（= 歌词区中心）到带顶边的距离 = bandH - areaHeight / 2
            //                                  ↑ height_percent < 50 时为**负**
            // ```
            //
            // 而 `paintBars` 里 `halfSpan = minOf(轴到上边, 轴到下边).coerceAtLeast(1f)`
            // → 负值被夹成 **1** → `barHeight = value * 1` 被 `MIN_BAR_HEIGHT`(2px) 兜住
            // → **整个频谱塌成一条 2px 的线**，且**不报错**。
            //
            // 即：**"把频谱高度调到 50% 以下"会让对称双峰直接消失。**
            // 这是 2026-09-18 修 BUG-061（把轴从"带中心"改成"歌词区中心"）时
            // **引入的**：轴的语义改对了，但**带的摆法没跟着改**。
            //
            // ## 修法
            //
            // 对称模式下把带**摆成以轴为中心**（`growDown` 沿用 anchor 的选择，
            // 它只影响淡出渐变的方向，不影响双峰的形态 —— 带中心 == 轴，
            // 故翻转把双峰映射到它自己）。
            //
            // 连带好处：`height_percent` 在对称模式下的语义变得直白 ——
            // **双峰的总高度 = 歌词区高度 × 百分比**（上下各占一半）。
            val band = if (symmetric) {
                // 几何算式在 [symmetricBandTop]（公开纯函数，可被探针断言）——
                // 它把"**任意百分比下双峰都不得塌**"这条钉成一个可验证的定义点。
                val bandHeight = (areaHeight * pct).coerceAtLeast(1f)
                bandRaw.copy(
                    top = symmetricBandTop(areaTop, areaHeight, pct),
                    bottom = symmetricBandTop(areaTop, areaHeight, pct) + bandHeight,
                )
            } else {
                bandRaw
            }
            val top = band.top
            val bottom = band.bottom
            if (band.height <= 1f) return

            // ── ★ 横向范围：**圆角感知的内缩**（BUG-127，2026-09-22）──────────────
            //
            // 用户原话：「**贴边频谱在大圆角下左右端超出词岛**」。
            //
            // ## 根因（**与直觉不同**）
            //
            // `clip` 是有的（本函数的调用方 `g2.clip = shape`，频谱在 `try` 内，
            // 物理上画不到形状外）。真因是**横向内缩是常量 `HORIZONTAL_INSET = 14f`、
            // 完全不感知圆角半径**：大圆角下带子两端落在弧区里，被 `setClip` 的
            // **硬掩膜**沿弧线切掉（`setClip` 忽略抗锯齿，BUG-121）⇒
            // 观感就是"戳到外面去了"（其实是"被斜着切掉了"）。
            //
            // ## 判据（决定 `edgeInset` 取多少，**不是观感取舍**）
            //
            // **带子矩形的四个角必须落在形状内** ⇒ `edgeInset` 由"让四角都在形状内"
            // **唯一确定**（取满足条件的最小值）。推导与"为什么不能直接用
            // `shape.contains` 二分"见 [spectrumEdgeInset] 的 KDoc。
            //
            // ⚠️ 参照的"块"是**歌词区**（`areaTop .. areaTop + areaHeight`）而不是
            //    `bg.islandTop/islandBottom` —— 后者在动画期间会随带子长出去，
            //    会让频谱在 180 ms 里左右抖。而词岛轮廓的圆角半径**恒等于**
            //    歌词块那一侧的圆角（`BOTTOM` 档下两者本来就是同一个矩形；
            //    `TOP` 档下岛体只比歌词块多出**上方**那一段，下方两条边仍然重合），
            //    故用歌词块作参照既**稳定**又**不低估**。
            // 算术（含"为什么不能直接用 `shape.contains` 二分"）在 [spectrumBandInsetForRadii]。
            // ⚠️ 2026-09-22：改用**本帧**的两个圆角半径（由 `islandOutlineAt` 给出）——
            //    与画出来的轮廓**同一个**来源，故"内缩量"与"形状"不可能漂移。
            //
            // ★★★ 2026-10-11（**BUG-166**）：**插槽档不走圆角内缩** —— 只留基础内缩。
            //
            // ## 用户报障（逐字）
            //
            // > 「三型侧边频谱……在**收起状态下的词岛仍不可见**，**展开时才可见**。」
            //
            // ## 根因（★ 与 BUG-165 同一条纪律，这是它在**另一个量**上的复发）
            //
            // 圆角内缩的**输入是动画量**（`topRadiusPx`/`bottomRadiusPx` 随展开从
            // 收起态半径插值到 `EXPAND_CORNER_RADIUS_PX = 16`）。而**收起态**的半径
            // = `min(岛宽, 收起岛高) × 圆角% / 2` —— 用户 `corner_radius = 100` 时
            // **恰好 = 岛高的一半 = 32**（= 胶囊）。
            // 而插槽的**宽**（`rightSlotWidthPx`）在 `RADIAL`/`RING` 档下**就是岛高**（64）、
            // `SPECTRUM` 档是 56 ⇒ ★ **内缩 32 把整条带宽吃光**：
            //
            // | 档 | 插槽宽 | 收起内缩 | 带净宽 | 展开内缩 | 带净宽 |
            // |---|---|---|---|---|---|
            // | `SPECTRUM` | 56 | **32** | **−8** ✗ | 16 | 24 ✓ |
            // | `RADIAL` / `RING` | 64 | **32** | **0** ✗ | 16 | 32 ✓ |
            //
            // ⇒ ★ 撞上下面的 `if (barWidth <= 1f) return` ⇒ **三型在收起态全部不画**，
            //   而**展开态半径掉到 16 就又画出来了** —— 与用户描述逐字吻合 ✓
            //
            // ## 为什么正解是"插槽档恒用基础内缩"
            //
            // 1. **插槽是自足组件**：它的几何由 `lyricsHeight`（**稳态量**）定死，
            //    故它的内缩也**必须**是稳态量 —— 这是 BUG-165 立下的
            //    「**布局常量不得取自动画量**」的直接应用（那边是画布位置，这边是带宽）。
            // 2. **圆角内缩的模型不适用**：它是为**主带**（横跨歌词区、贴着上/下边）推的
            //    ——「带子四角落在圆角弧内」。而插槽是**右端的一小块**，它的圆角由
            //    **岛体轮廓自己的 clip** 处理（本项目一贯做法："clip 到 shape 最省事且必然正确"）。
            // 3. **极坐标两档根本不用 `left`/`right`**（画布是 `slotRect`）⇒ 内缩只影响
            //    那道守卫；用基础内缩即可让它正常出图。
            //
            // ⚠️ **代价（如实记）**：`SPECTRUM` 档展开态的带净宽由 24 → **28**（+4px），
            //    即"比改动前略宽一点"。★ 收起态则由"不画"变成"正常画"。
            //    两态**带宽恒为 28**（不再随悬停变化）—— 这才是正确的稳态。
            val edgeInset = dev.cdl.window.spectrumBandInsetFor(
                slotOwn = slotOwn,
                topRadiusPx = topRadiusPx,
                bottomRadiusPx = bottomRadiusPx,
                areaTop = areaTop,
                areaHeight = areaHeight,
                bandTop = top,
                bandBottom = bottom,
                baseInset = HORIZONTAL_INSET,
            )
            val left = areaLeft + edgeInset
            val right = areaLeft + areaWidth - edgeInset
            val barWidth = right - left
            // 极窄（或圆角半径大到带子容不下）时**不画**：画出来只会是几个糊在一起的像素。
            if (barWidth <= 1f) return

            val mainSpectrumColor = local.spectrumColorFor(coverRgb)
            val packed = mainSpectrumColor.toPackedArgb()

            // ★★★ 2026-10-15（**§3.75**）：**插槽频谱的独立取色**（用户拍板「完整取色模式」）。
            //
            // ★ 三个量**一次算好**（`slotPacked` / `slotMultiColor` / 下面的 `slotSolidFlowPaint`），
            //   插槽分支只读它们 —— 与 `slotOwn` 一样是"一处算、多处共用"，
            //   避免"读一次档位、画的时候又读一次"造成两处漂移 ✓
            //
            // ★ **默认档 `follow` ⇒ `slotPacked === packed`** ⇒ 开箱逐像素不变 ✓
            //   （`rightSlotSpectrumColorFor` 对 `follow` **原样返回**主频谱已解析的色）
            //
            // ★ 唯一出口 = `IslandAppearance.kt` 的两个**公开纯函数**
            //   （探针看不到 `internal` 的 `IslandWindow` ⇒ 规则必须抽出去才能被断言）。
            val slotColorMode = dev.cdl.window.rightSlotSpectrumColorModeFor(
                local.rightSlotSpectrumColorMode,
            )
            val slotPacked = dev.cdl.window.rightSlotSpectrumColorFor(
                slotColorMode,
                mainSpectrumColor,
                local.rightSlotSpectrumColor,
                coverRgb,
            ).toPackedArgb()
            val slotMultiColor = dev.cdl.window.isMultiColorSpectrumMode(slotColorMode)

            // ★★★ 2026-10-11（⑰「封面多色流光」· 频谱侧）：**逐柱取色回调**（唯一构造点）。
            //   ★ 主频谱 / 副频谱**共用同一个实例** —— 两处各构造一次会让
            //     "哪一档走哪条取色"在两个目标上漂移（副频谱与主频谱不一致的经典成因）✓
            // ★★★ 2026-10-12（**BUG-172**）：★ **频谱流光用自己的、更慢的相位** ✓
            //
            // ★ 用户原话：「**动态流光的颜色循环速度过快。**」
            // ★★ 根因：频谱与背景**共用**传入的 `flowPhase`（周期 6 s），而频谱带比整岛背景
            //   **短一个量级** ⇒ ★ 相位是**相对量** ⇒ **轴越短，同样的相位速度看起来越快** ✗
            // ★ 正解 = 频谱自己的周期（`SPECTRUM_FLOW_PERIOD_MS` = 3 倍慢）✓
            //   ★ 同样吃 `positionMs` ⇒ **暂停即冻结**，与背景同一条纪律 ✓
            // ⚠️ **本方法内唯一的取值点** —— 逐柱取色（`barColorFn`）与两趟渐变
            //   （`spectrumFlowPaintFor`）**必须用同一个**，否则"同一帧两种流速" ✗
            // ★★★ 2026-10-16：**同一个流速键**也缩放频谱这条（经**同一个**换算点）
            //   ⇒ ★★ 两条流光 3× 的快慢关系**保持不变** ✓（见 `scaledFlowPeriodMs` 的 KDoc）
            val spectrumFlowPhase = dev.cdl.render.loopPhase(
                snapshotSupplier().positionMs,
                dev.cdl.render.scaledFlowPeriodMs(
                    dev.cdl.render.SPECTRUM_FLOW_PERIOD_MS,
                    local.materialFlowSpeed,
                ),
            )
            val barColorFn = spectrumBarColorFn(local.spectrumColorMode, coverPalette, spectrumFlowPhase)
            // ★★★ 2026-10-12（**BUG-181 的决定性诊断**）：★ 一次把这四个量打出来 ⇒ 断点当场可定位 ✓
            //
            // ★ 用户报「**设置随封面取色流光无效，呈现默认色**」——
            //   ★★ 我前两条假设（色数不足 / `fade_top` 吃掉逐柱色）**都站不住** ✗
            //   ⇒ ★★★ **不再猜**：把"这一帧到底走了哪条路"打成一行可 grep 的事实 ✓
            //
            // ★ 四个量分别对应四个可能的断点：
            //   ① `mode` —— 档位到底是不是 `cover_flow`（★ 若不是 ⇒ 设置没读进来）
            //   ② `palette` 色数 —— 若 < 2 ⇒ `spectrumFlowColorAt` 会静默退回基色
            //   ③ `fn` 是否为空 —— 空 ⇒ 整带同色（回调根本没建）
            //   ④ `fadeTop` —— 记录它以便对照画笔分支
            logFlowDiag(local.spectrumColorMode, coverPalette, barColorFn != null, boolOf(snapshot, "spectrum.fade_top"))

            // ── 跳动幅度（`spectrum.bounce_pct`，2026-09-18 新增）──────────────
            //
            // 用户原话：「频谱设置增加一项**相对跳动高度**（即频谱高低跳动更明显）」。
            // 拉伸公式与"为什么不是乘法"见 `render/SpectrumGeometry.kt` 的 `stretchAroundMid`；
            // 100% = 原样（逐位相同）。
            //
            // `coerceIn(1f, 3f)` 是**降级护栏**，不是为了再夹一次注册表的 range
            // （`intOf` 已经夹过，再夹会掩盖注册表被改坏 —— 见 `Appearance.from` 的同类取舍）：
            // 它防的是"键读不到"这条路径 —— 那时 `intOf` 返回 `0`，而 `scale = 0f`
            // 会把**所有**柱子压到 0.5（频谱变成一条平线），远比"保持原样"更像故障。
            val bounceScale = (intOf(snapshot, "spectrum.bounce_pct") / 100f).coerceIn(1f, 3f)

            // ── 进度染色（`spectrum.progress_tint`，2026-09-18 新增）────────────
            //
            // 用户原话：「允许通过**频谱色差**表示歌曲播放进度。」
            //
            // 只有"设置开着"时才去问时间轴 —— 默认关的路径连 `snapshotSupplier()`
            // 都不多调一次（它是每帧的热路径）。
            //
            // 时长未知时 [playbackProgress] 返回 [NO_PROGRESS]（**不染色**）；
            // 位置为 0 时返回 `0f`，那时已播放段宽度为 0，绘制层自然不重画 ——
            // 两种"未知"因此都不需要在这里特判。
            val progress = if (boolOf(snapshot, "spectrum.progress_tint")) {
                val timeline = snapshotSupplier()
                playbackProgress(timeline.positionMs, durationSupplier())
            } else {
                NO_PROGRESS
            }

            // ── 频谱"进度色差"的三个 alpha ─────────────────────────────────────
            //
            // 推导**全部**收在 [spectrumAlphas] 里（唯一计算点，可被探针断言）——
            // 本处只负责把设置值与"此刻有没有已播放段"喂进去。
            //
            // ⚠️ 必须在 `try` **之外**算：副频谱（本函数后半段）要用同一个 `alphas`，
            //    而它画在 `try/finally` 之外 —— 放进 `try` 里会变成"主频谱有、副频谱看不见"。
            //
            // ## `tintActive` 为什么不能省
            //
            // `progress` 的取值域是 `[0,1] ∪ {NO_PROGRESS(-1)}`（见 `playbackProgress`）：
            //   · `NO_PROGRESS` → 时长未知，`progressPaintOverride` 为 null，**没有已播放段**
            //   · `0f`          → 一句都没播，已播放段宽度为 0，同样**没有已播放段**
            // 两种情形下若仍按差值压暗未播放段，得到的是"整条变淡"而没有任何东西补位。
            val tintActive = progress > 0f && progress <= 1f
            val alphas = spectrumAlphas(
                opacityPct = intOf(snapshot, "spectrum.opacity_pct"),
                tintAlphaPct = intOf(snapshot, "spectrum.progress_tint_alpha"),
                tintActive = tintActive,
            )
            // 两段的填充色 —— 由**同一个 `packed`** 推出，唯一差异是 alpha。
            // 推导与"为什么必须是同一个颜色"的完整论证见 [spectrumSegmentColors]。
            val segColors = spectrumSegmentColors(packed, alphas)
            // ★★★ 2026-10-12（**BUG-174 的更正**）：★ **插槽三型【不启用】透明度差表进度** ✓
            //
            // ★ 用户原话（逐字）：
            // > 「**右侧三型频谱不启用透明度差表播放进度。**」
            //
            // ★★ 做法：插槽另用一份 alpha（`unplayed = 1f`）⇒ 两段**逐位相同** ⇒
            //   ★ 插槽档的"进度染色"两趟画出同一个颜色 ⇒ **不表进度** ✓
            // ★ **主带不动**（照旧用 `alphas`）✓ —— 这正是上一批改过头的地方 ✓
            // ★ 判据：★ 与 `slotOwn` **同一个开关**（它已决定"插槽自定义形态"）✓
            val slotAlphas = alphas.copy(unplayed = 1f)
            // ★★★ 2026-10-15（**§3.75**）：插槽的基色改用**插槽自己的** `slotPacked`
            //   （`follow` 档下它与 `packed` 逐位相同 ⇒ 零回归）✓
            val slotSegColors = spectrumSegmentColors(slotPacked, slotAlphas)
            // ★★ 2026-10-11（⑰）：**实心档**（`fade_top = false` / 插槽档）用的**横向多色渐变**。
            //
            // 为什么实心档不能复用逐柱回调 `barColorFn`：★ `SpectralPainter.paintBars` 的
            // **实心分支**走 `g.paint`（不读 `barArgb`）—— 那是它既有的契约
            // （`argbForBar` 的 KDoc 记着它只在淡出分支生效）。
            // ⇒ 实心档改用一支 `LinearGradientPaint`，**由同一个调色板与相位驱动**
            //   ⇒ 两条路的观感一致（都是"一个调色板周期铺满谱面"）✓
            // ★ `0°` = 从左到右 ⇒ 与逐柱回调的"按柱序"**同一个走向**（不会一条横一条竖）✓
            // ★ 色数 < 2 ⇒ `flowGradientPaint` 返回 `null` ⇒ 退回既有的单色 `packed` ✓
            // ★★ 2026-10-14：**`cover_camo` 也走本支** —— 它在实心档由 `spectrumFlowPaintFor`
            //   分派出「碎色块多停靠点渐变」（★ 与逐柱路同源块划分）✓
            // ★★★ 2026-10-15（**§3.75**）：判据抽成**公开纯函数** `isMultiColorSpectrumMode`
            //   —— 它此前在**三个消费点**各写一遍（主带 / 插槽 / 副频谱），
            //   而 BUG-204 的注释里已经记过一次"漏了一处 ⇒ 新档退化回单色"的教训 ✓
            val multiColorSolid = dev.cdl.window.isMultiColorSpectrumMode(local.spectrumColorMode)
            val solidFlowPaint: Paint? = if (multiColorSolid) {
                spectrumFlowPaintFor(
                    left, top, right, bottom, coverPalette, spectrumFlowPhase, alphas.unplayed,
                    local.spectrumColorMode,
                )
            } else {
                null
            }
            // ★★★ 2026-10-15（**§3.75**）：**插槽自己那一支**多色笔刷 —— 与上面同源
            //   （同一个 `spectrumFlowPaintFor`、同一个调色板与相位），只换**档位**与 **alpha**。
            //   ★ alpha 用 `slotAlphas.unplayed`（= 1f）—— 与插槽"不表进度"的既有口径一致 ✓
            //   ★ `follow` 档下 `slotColorMode == local.spectrumColorMode` ⇒ 与 `solidFlowPaint`
            //     **逐位相同**（零回归）✓
            val slotSolidFlowPaint: Paint? = if (slotMultiColor) {
                spectrumFlowPaintFor(
                    left, top, right, bottom, coverPalette, spectrumFlowPhase,
                    slotAlphas.unplayed, slotColorMode,
                )
            } else {
                null
            }
            // ★★ 2026-10-11（⑰）：**进度染色趟**的多色笔刷 —— 与底图同一支调色板与相位，
            //   只是 alpha 换成 `alphas.played`（★ 见 `spectrumFlowPaintFor` 的口径说明）。
            //   ⚠️ 为什么必须有它：**插槽档恒为实心** ⇒ 若不给，用户开「进度色差」后
            //      会看到"未播放段是彩虹、已播放段是单色"—— 而那正是本功能点名的三型频谱之一 ✗
            val solidFlowProgressPaint: Paint? = if (multiColorSolid) {
                spectrumFlowPaintFor(
                    left, top, right, bottom, coverPalette, spectrumFlowPhase, alphas.played,
                    local.spectrumColorMode,
                )
            } else {
                null
            }
            // 未播放段的颜色（高 8 位带 alpha）。`fade_top = false` 时底图走
            // `g.color = cache.color(color)` 这条**唯一没有 alpha 参数**的路径。
            val unplayedPacked = segColors.unplayed
            // ── ★★ 已播放段的颜色：**与未播放段同一个 `packed`**（2026-09-22 修用户第 ② 条）──
            //
            // 用户原话：
            // > 「进度色差我不是说了用**已播放与未播放频谱的透明度的差值**吗？
            // >   怎么滑杆到 0 还有差别的。（我知道主频谱受它的透明度控制，
            // >   那么差值要**基于被控制的这个透明度**灵活计算）」
            //
            // ## 根因：差别**不是**来自 alpha，而是来自**颜色**
            //
            // `spectrum.progress_tint_alpha = 0` 时 `alphas.played == alphas.unplayed == 1f`
            // —— 两段的 alpha **完全相等**（这一点探针 §③ 早已断言）。
            // 但已播放段当时用的是 `local.sungColorFor(coverRgb)`（**已唱歌词色**），
            // 未播放段用 `local.spectrumColorFor(coverRgb)`（**频谱本色**）——
            // 两个键各自独立，**颜色差恒存在**，与差值滑杆无关。
            // 于是"滑杆拖到 0 仍看得出分界"—— 那是**色差**，不是透明度差。
            //
            // ## 修法：同一个颜色，唯一差别是 alpha
            //
            // 用户要的语义是"进度色差 = 两段的**透明度**差值"，故已播放段必须用
            // **与未播放段相同的 `packed`**，两段的全部差异只由
            // `alphas.played` / `alphas.unplayed` 承担。
            // 于是：`tintAlphaPct = 0` ⇒ 两段**逐像素完全相同**（探针 §⑧ 断言）。
            //
            // ## "基于被控制的透明度灵活计算"是怎么成立的
            //
            // 整体不透明度 `spectrum.opacity_pct` 由 `alphas.frameOpacity` 独自承载
            // （`SpectralPainter.paint` 的整帧 `AlphaComposite`），
            // 而这里给的是**相对系数**（`played = 1f` / `unplayed = 1 − 差值`）——
            // 有效 alpha = `frameOpacity × 本系数`（`SRC_OVER` 下两者相乘）。
            // 故差值**恒相对** `base`：`base = 60` 时差值 100% 得到 `0.6 / 0`，
            // 差值 0% 得到 `0.6 / 0.6` —— 与 `base` 的大小**正交**，不会出现
            // "整体透明度 100 时差值滑杆恒为 0"那种天花板（那正是 2026-09-20 修掉的旧式）。
            //
            // ⚠️ 与 `spectrumLayerArgb` 的约定一致：本函数给的是**相对**系数，
            //    不含整体不透明度（否则会被乘两次，`base²`）。
            val playedPacked = segColors.played

            // ── 贴顶边：把柱子的**生长方向翻过来**（本轮新增，见上面"上一版为什么贴不到顶边"）
            //
            // `SpectralPainter.paintBars` 只会"从带矩形下边向上长"，它没有、也不该有
            // 一个"方向"参数 —— 那会让"方向"出现**两个定义点**（调用方传参 + 绘制方解释），
            // 与本项目"绘制与命中必须共用同一份几何"的纪律相悖（见 `PlaybackControls.trackRect`）。
            // 故翻向用 `Graphics2D` 的仿射变换完成，**整个改动关在本文件里**。
            //
            // 要的映射是 `y' = 2·top + bandHeight − y`：
            //   · 带矩形下边（`top + bandHeight`，即柱子的基线）→ 映到 `top`；
            //   · 向上长出的 `h` → 变成向下长的 `h`，柱子的**顶边**落在 `top`。
            //
            // ⚠️ **调用顺序是 `translate` 再 `scale`，不能反** —— 这是实测出来的，
            //   不是照着"先缩放后平移"的直觉写的。AWT 的 `translate`/`scale` 是
            //   **前乘**（新变换左乘当前矩阵），故两种顺序给出**不同**的矩阵：
            //
            //   | 调用顺序 | 结果矩阵的 m10/m01/m02 | 对 `y = top + bandHeight` 的映射 |
            //   |---|---|---|
            //   | `scale(1,-1)` → `translate` | `-y - 2·top - bandHeight` | 落到 `-2·(top+bandHeight)`（**飞出画面**） |
            //   | `translate` → `scale(1,-1)` | `-y + 2·top + bandHeight` | 落到 `top` ✔ |
            //
            //   验算脚本：`plugin/tmp/FlipCheck2.java`（矩阵与落点，可直接 `java FlipCheck2.java` 复跑）
            //   与 `plugin/tmp/FlipCheck3.java`（渐变方向）。实测：
            //   · 源码矩形 `[48, 88)` → 设备 `[0, 40)`，柱子的顶边正好压在 `top` 上；
            //   · 渐变 alpha `y=0 → 200`、`y=44 → 100`、`y=87 → 2` = **顶部实、底部透明**。
            //
            // 连带效果（都是要的）：`LinearGradientPaint` 也在用户空间里被同一个变换翻转，
            //   于是 `verticalFadePaint` 那条"下实上透明"的渐变自动变成"**上实下透明**" ——
            //   与"柱子从顶边往下长"一致，无需再传一个"是否翻转"给笔刷构造。
            //   进度染色的 `g.clip` 同样是用户空间矩形，一起被正确翻转。
            val flip = if (band.growDown) g2.transform else null
            if (flip != null) {
                g2.translate(0.0, 2.0 * top + band.height)
                g2.scale(1.0, -1.0)
            }
            try {
                // ★ 2026-09-26 接线 `spectrum.bar_shape`（柱体圆角 / 方角）。
                //
                // ★ 换算走 `spectrumBarCornerRatio`（**只此一处**，`RegistryProbe` §12.2 断言）。
                //    默认 `ROUND` ⇒ 比例 `1f` ⇒ **开箱零变化**（`x * 1f == x` 逐位成立）。
                //
                // ⚠️ 两处调用（主 / 副频谱）**各自传同一个表达式**，不共用一个局部 ——
                //    主频谱那次在 `for (band in …)` **循环体内**，副频谱那次在**循环外**
                //    （Kotlin 按花括号定作用域）⇒ 共用局部会 `Unresolved reference`。
                //    表达式是**纯函数 + 同一个键** ⇒ 两处必然同值，不会不一致。
                // ★★★ 2026-10-04：**极坐标（放射状）** —— 判据是插槽 occupant = `RADIAL`。
                //
                // ## 为什么用**歌词区**（而不是 `band`）当画布
                //
                // 极坐标下"最大径向长度"是**半径**，而 `band` 只是频谱带（高 16–40px）
                // ⇒ 用它算出的半径**极小**，环会缩成一个点。
                // ★ 用整个歌词区 ⇒ 半径 = `min(区宽, 区高) / 2`，环/放射才有观感。
                //
                // ## ★★ 为什么把**周长**当 `bounds.width` 传
                //
                // `paintBars` 内部用 `bounds.width` 推**条数**（`spectrumBarCount`）。
                // 环形下条数应当由**周长**决定（`2πR / 柱距`）—— 若传直线宽（几百 px），
                // 一圈会塞进几百根柱（每根 ~1°）⇒ **糊成一片**。
                // ⇒ ★ 传周长 ⇒ `spectrumBarCount` 自然给出"环该有的条数" ✓
                //
                // ⚠️ `bounds` 的 `x` / `y` / `height` 在极坐标下**被 `polar` 全接管**
                //    （见 `paintBars` 里 `topEdge` / `bottomEdge` / `halfSpan` 的覆写）
                //    ⇒ 这里只有 `width` 是有效的。
                //   ★ 只有 `RADIAL` 档返回非空 ⇒ 其余档（`SPECTRUM`/`CLOCK`/`NONE`）**逐像素不变**。
                //   ★ 只有 `RADIAL` 档返回非空 ⇒ 其余档（含 `SPECTRUM` / `CLOCK` / `NONE`）**逐像素不变**。
                // ★★★ 2026-10-04：**区域口径** —— `RADIAL` 档用**插槽那个方形**，不是歌词区。
                //
                // ## 为什么不能沿用歌词区
                //
                // 极坐标的半径 = `min(宽, 高) / 2`。若把**整个歌词区**当画布：
                //  · 宽 = 歌词区宽（几百 px）· 高 = 歌词区高（几十 px）⇒ `min` = 高
                //  ⇒ 圆心落在**歌词区中间** ⇒ 圆会**横跨整个歌词区**（压住歌词）
                //  ✗ 而用户要的是"**在那个右端插槽里**"。
                //
                // ## 本档的正确区域 = 插槽方形（宽 = 高 = `rightBandWidth`）
                //
                // ★ 插槽宽在 `RADIAL` 档下**就是词岛高**（用户拍板"自适应"）
                // ⇒ 天然是方形 ⇒ 圆**正好撑满插槽** ✓
                // ★ 垂直**居中**于岛体（`islandTop + (islandHeight − W) / 2`）——
                //   与时钟/小频谱的"贴歌词区"不同，圆居中更好看（它不是"贴边生长"的形态）。
                // ⚠️ **破环**：`polarArea` 不能靠"`spectrumPolarFor` 是否返回 null"来判
                //   （那是**结果**，而 `spectrumPolarFor` 的输入正是 `polarArea`）——
                //   直接用 **occupant** 判（`RADIAL` 是**唯一**会走极坐标的 occupant）✓
                // ★ 插槽区域由调用方算好（`slotRect`）—— 本档的极坐标画布就是它。
                // ★★ 2026-10-11（U14）：判据收口到 `isPolarSlotOccupant`（RADIAL + RING）——
                //   「三处各写一遍迟早漂移」⇒ 唯一出口 ✓
                val polarArea = if (dev.cdl.render.isPolarSlotOccupant(slotOccupant)) {
                    floatArrayOf(
                        slotRect.x.toFloat(),
                        slotRect.y.toFloat(),
                        slotRect.width.toFloat(),
                        slotRect.height.toFloat(),
                    )
                } else {
                    floatArrayOf(areaLeft, areaTop, areaWidth, areaHeight)
                }
                val polarSpecFinal = dev.cdl.render.spectrumPolarFor(
                    slotOccupant, polarArea[0], polarArea[1], polarArea[2], polarArea[3],
                    // ★★★ 2026-10-16（**§3.77-F**）：极坐标**径向范围**三键 ——
                    //   内半径按档取各自的键、外半径两档共用（见 `spectrumPolarFor` 的 KDoc）。
                    //   ⚠️ **显式传**，不给默认值：给默认值会让"漏接线"变成静默地用出厂几何 ✗
                    local.rightSlotRadialInnerPercent,
                    local.rightSlotRingInnerPercent,
                    local.rightSlotPolarOuterPercent,
                )
                val spectrumBounds = if (polarSpecFinal != null) {
                    // ★★★ 2026-10-11（**BUG-166 · 补**）：周长必须取**内缘** ——
                    //   `paintBars` 用 `slot = bounds.width / count` 推**柱宽**
                    //   （`barWidth = slot − gap`，gap ≈ 30%）。
                    //   ★ 原实现传**外缘**周长（`2π·maxLength ≈ 201`），而条数已改按**内缘**定
                    //     （`RADIAL` = 10 根）⇒ `slot = 20.1` ⇒ **柱宽 ≈ 14 px** ✗
                    //   ⇒ 真机观感 = "一瓣瓣肥厚花瓣"（用户 2026-10-11 报障「频谱条太丑」）✓
                    //   ★ 正解 = 与条数**同一口径**：`2π·innerRadius / count ≈ pitch ≈ 7`
                    //     ⇒ 柱宽 ≈ 4.9 px（与主带同一条柱宽口径）✓
                    //   ⚠️ 预览侧（`IslandPreviewPane`）**本来就是内缘口径** ——
                    //     即本行修的是"真机落后于预览"，不是"两边一起改"。
                    // ★★★ 2026-10-12（**BUG-170**）：★ 半径口径改走**唯一出口**
                    //   `polarSpacingRadiusPx` —— ★ 与 `polarBarCount`（**条数**）**同源** ✓
                    //
                    // ## 本行就是"放射状频谱不显示"的根因（★ 是我上一批自己引入的）
                    //
                    // ★ 改前写死 `2.0 * Math.PI * polarSpecFinal.innerRadius` ✗
                    // ★★ 而上一批把 `RADIAL_INNER_RATIO` 由 `0.35` 改成 **0**
                    //   （用户拍板「条应交于一点」）⇒ ★ 内半径 **0** ⇒ 周长 **0**
                    //   ⇒ `coerceAtLeast(1)` ⇒ `bounds.width = 1`
                    //   ⇒ `paintBars` 的 `slot = 1 / count` ⇒ **柱宽 ≈ 0** ⇒ **整档不显示** ✗
                    //
                    // ★ 当时**只改了 `polarBarCount`、漏了本处** —— ★ 两个消费者读同一个几何量，
                    //   改一处必查另一处（★ 判据：「这个常数还被哪几处消费？」）✓
                    // ★★ 现在**两个消费者调同一个函数** ⇒ 漏一个**结构上不可能** ✓
                    val circumferencePx =
                        (2.0 * Math.PI * dev.cdl.render.polarSpacingRadiusPx(polarSpecFinal)).toInt()
                    Rectangle(
                        polarArea[0].toInt(),
                        polarArea[1].toInt(),
                        circumferencePx.coerceAtLeast(1),
                        polarArea[3].toInt(),
                    )
                } else {
                    Rectangle(left.toInt(), top.toInt(), barWidth.toInt(), band.height.toInt())
                }
                SpectralPainter.paint(
                    g = g2,
                    cache = renderCache,
                    bands = bands,
                    bounds = spectrumBounds,
                    // ⚠️ 传**未播放段的颜色**（已乘上 `alphas.unplayed`），不是 `packed`。
                    //    `fade_top = false` 时 painter 走 `g.color = cache.color(color)` ——
                    //    那是底图唯一没有 alpha 参数通道的着色路径（见 `spectrumLayerArgb`）。
                    // ★ 插槽三型**不启用透明度差**（用户 10-12）⇒ 用插槽那份（两段同色）✓
                    color = if (slotOwn) slotSegColors.unplayed else unplayedPacked,
                    // ★★ 2026-10-02（`spectrum.color_mode = level`）：**逐柱取色**。
                    //   ★ **只在 `level` 档传** ⇒ 其余档 `null` ⇒ **逐像素等于改动前**。
                    //   ★ 基色由 painter **逐趟**给（底图趟 = 未播放色 / 进度染色趟 = 已播放色）
                    //     ⇒ "两趟用同一个基色"的同类缺陷**结构上不可能**
                    //     （2026-09-22 刚修过那个成因，见 `SpectralPainter.progressColor` 的 KDoc）。
                    // ★★ 2026-10-11（⑰）：**改为走唯一构造点** `spectrumBarColorFn` ——
                    //   同一档还多了 `cover_flow`（封面多色流光），且主/副/插槽三处共用它 ✓
                    argbForBar = barColorFn,
                    // ★★★ 2026-10-17（**BUG-226**）：**插槽小频谱的二维碎色块**。
                    //
                    // ★ 用户报障（逐字）：「插槽小频谱的碎色流光不是背景那种。」
                    // ★ 根因：`argbForBar` 是**一维**（按柱序）⇒ 5 根柱 = 5 条竖纹；
                    //   而岛体背景是**二维细胞噪声** ⇒ 形态不同 ✗
                    // ★ 修法：只给**插槽**传 `argbForBarSegment`（柱体切 3 段、二维哈希取色）——
                    //   主/副频谱柱多（几十上百根），一维已经够碎，不额外分段（省性能）。
                    argbForBarSegment = if (local.spectrumColorMode == dev.cdl.render.CoverPalette.COVER_CAMO) {
                        { i2, count2, seg2, segs2, base2 ->
                            dev.cdl.render.spectrumCamoBlockColorAt(
                                base2, i2, seg2, count2, segs2, coverPalette, spectrumFlowPhase,
                            )
                        }
                    } else {
                        null
                    },
                    // ★★★ 2026-10-04：极坐标（环形 / 放射）。★ `null` ⇒ 直角坐标（默认）。
                    polar = polarSpecFinal,
                    state = spectrumCache,
                    style = SpectralPainter.Style.BARS,
                    barShapeRatio = spectrumBarCornerRatio(
                        textOf(snapshot, "spectrum.bar_shape"),
                    ),
                    nowNanos = System.nanoTime(),
                    playing = playing,
                    // ★★★ 2026-10-11（**BUG-166 · 补**）：**插槽档一律关闭峰值保持** ——
                    //   用户报障（逐字）：「**为什么插槽内这三型频谱 这种游离在条状外的小横杠？**」
                    //
                    // ## 那几道"小横杠"是什么
                    //
                    // 就是**峰值保持帽**（`PEAK_CAP_HEIGHT` 高、与柱**同宽**的一小段）——
                    // 语义是"**刚才这一击有多高**"（读数型仪表：帽 = 历史峰值、柱 = 当前值，
                    // 两者的**间距本身就是信息**，见 `paintBars` 里帽那一趟的 KDoc）。
                    //
                    // ## 为什么在插槽里必须关掉（三条，任一条都够）
                    //
                    // 1. **尺寸不够**：插槽是 `56~64 px` 的小组件，柱长只有 `14~21 px`，
                    //    而帽 `3 px` + **帽与柱之间的空隙** ⇒ 那点空隙在这个尺度上读不出
                    //    "回落了多少"，只读得出"**有个东西飘在外面**" ✗
                    // 2. **形态不匹配**：帽是**横杠**，而插槽三档是**纵向小柱 / 径向射线**
                    //    ⇒ 小尺寸下两者的**方向对比**格外刺眼（用户原话"游离在条状外"）✓
                    // 3. **语义重复**：插槽是**装饰型**组件（不是仪表），"峰值"这个读数的
                    //    归属是**主带** —— 要看峰值时看主带即可 ✓
                    //
                    // ⚠️ **主带不受影响**：`slotOwn = false` ⇒ 原样透传 ✓
                    peakHold = peakHold && !slotOwn,
                    // ⚠️ `spectrum.fade_top` 的**真正落点在这里**（不是下面那个同名参数）。
                    //
                    // `SpectralPainter` 的既有约定是"**传了 `paintOverride` 就以它为准**" ——
                    // 而背景频谱的"向上淡出"观感**完全来自这条渐变**。
                    // 故关掉淡出 = **不传渐变**，让 painter 走"本色实心填充"那条路径。
                    //
                    // 只把 `fadeTop = false` 传给 painter 是**不够的**（那只是改它自己的
                    // 默认着色分支，而这里根本没走到那条分支）—— 那会造出一个**死设置**。
                    // ★ 插槽档强制**实心**（U13 ③ + U14）：侧边条的对称双峰下渐变会把
                    //   上半画没；极坐标两档的渐变虽沿径向可用，但经典放射/环状
                    //   （Rainmeter 等）就是纯色条 ⇒ 统一实心，观感与判据都更简单 ✓
                    paintOverride = if (!slotOwn && boolOf(snapshot, "spectrum.fade_top")) {
                        // ⚠️ 压暗从整帧 composite 挪到了**笔刷 alpha** 上
                        //    （见上面"这个压暗不能走整帧 opacity"的推导）。
                        //    只走 `alphaScale` 这一个通道 —— `verticalFadePaint` 忽略
                        //    颜色自带的 alpha，若这里同时把 alpha 编进 `packed` 会让人
                        //    误以为乘了两次（将来谁把 `verticalFadePaint` 改成读
                        //    `base.alpha`，那就**真的**会乘两次）。与已播放段
                        //    （`backgroundSpectrumProgressPaint(sungArgb, …, tintAlpha)`）
                        //    的写法对称。
                        backgroundSpectrumPaint(packed, left, bottom, top, alphas.unplayed)
                    } else if (slotOwn) {
                        // ★★★ 2026-10-15（**§3.75**）：插槽走**插槽自己那一支**
                        //   （档位 / 基色都可独立；`follow` 档下它与 `solidFlowPaint` 逐位相同）✓
                        slotSolidFlowPaint
                    } else {
                        // ★★ 2026-10-11：**实心档**（`fade_top = false`）。
                        //   ★ `cover_flow` / `cover_camo` ⇒ 横向多色渐变；其余档 ⇒ `null` ✓
                        solidFlowPaint
                    },
                    bounceScale = bounceScale,
                    progressFraction = progress,
                    // ★★ 2026-09-22：已播放段的**填充色**按"趟"直接传给 painter
                    //    （`SpectralPainter.paint(progressColor = …)`）。
                    //
                    // ## 为什么不能只靠 `progressPaintOverride`
                    //
                    // 那个参数是**一整支笔刷**（颜色 + 几何），而 painter 为了"两趟形状
                    // 逐像素一致"必须**自己重算几何**、只要颜色。它靠 `playedArgbFrom`
                    // 从笔刷里"猜"出颜色（取 alpha 最大的那个色标）—— 能work，但
                    // 是**启发式**：将来谁换一种渐变写法，猜出来的色就可能是错的，
                    // 而症状是"色差不可见"（不报错、只是没效果）。
                    //
                    // 现在显式给色：`playedPacked` = 与未播放段**同一个 `packed`**、
                    // 只换 alpha（见它的 KDoc）。painter 侧 `progressColor` **优先于**
                    // `progressPaintOverride`（那是 `SpectralPainter` 明确写下的约定）。
                    //
                    // ⚠️ 两者**有意用同一个颜色**：`progressPaintOverride` 保留下来是
                    //    为了 `Style.WAVE` 等"只认笔刷"的路径，以及"取不到色"时的兜底。
                    //    两处若写成不同色，就会重新变成"两个定义点"——
                    //    本项目栽过多次的那类漂移。故它们**由构造保证相等**。
                    // ★★★ 2026-10-12（**BUG-179b**）：★ **插槽档连"进度色"也不传** ✓
                    //
                    // ★ 用户原话（逐字，两轮）：
                    // > ① 「侧边频谱仍显示进度。」  ② 「**侧边频谱还是显示进度。**」
                    //
                    // ## ★★★ 为什么只关【笔刷】不够（我上一轮的漏项）
                    //
                    // ★ 上一轮我只把 `progressPaintOverride` 置了 `null` ✗ ——
                    //   ★★ 而 `progressColor` **还在传** ✗
                    // ★★ 且底图在流光档下是**逐柱取色**（`argbForBar` = `barColorFn`）✗，
                    //   而 `progressColor` 是一个**平面色** ✗
                    //   ⇒ ★★★ **进度趟用平面色盖住逐柱流光色** ⇒ **照样看得见分界** ✗
                    //
                    // ## 判据：★ 插槽档 ⇒ **`progressColor` 也传 `null`** ✓
                    //
                    // ★ 与 `progressPaintOverride` 的 `!slotOwn` **成对** ——
                    //   ★★ **两个参数都得关**，少一个就漏（本轮的教训）✓
                    // ★ 主带不受影响 ✓（用户明确说主带用色差表进度）
                    progressColor = if (progress > 0f && !slotOwn) {
                        playedPacked
                    } else null,
                    // ★★★ 2026-10-12（**BUG-179**）：★ **插槽三型【整趟不画】进度层** ✓
                    //
                    // ★ 用户原话（逐字）：「**侧边频谱仍显示进度。**」
                    //
                    // ## ★★ 为什么上一批（BUG-178）的修法**没生效**
                    //
                    // ★ 上一批我只把**颜色**换成了 `slotSegColors`（两段同色）——
                    //   ★★ 但**底图**用的是**平面色**（`spectrumLayerArgb(packed, …)`），
                    //   而**进度层**走的是 `solidFlowProgressPaint`（**流光渐变**）✗
                    //   ⇒ ★★★ **两趟的颜色本来就不同**（平面 vs 渐变）⇒ 进度照样看得见 ✗
                    // ★ 根因：**"同一颜色"这个前提在流光档下不成立** ✗
                    //   （`spectrumSegmentColors` 保证的是"同一个 `packed` 只差 alpha"，
                    //     而流光档的两趟**根本不走同一个笔刷** ✗）
                    //
                    // ## 判据：★ 插槽档 ⇒ **不画那一趟**（`null`）
                    //
                    // ★ 这比"让两趟同色"**更强、也更简单** ——
                    //   ★★ 不画 ⇒ **结构上不可能**出现进度染色 ✓（不是"画了但看不出来"）
                    // ★ 与用户原话"**不启用**"逐字对应 ✓
                    //
                    // ⚠️ **主带不受影响**（照旧画进度层）✓ ——
                    //   ★ 用户明确说了「**频谱条主带**的色差表示进度」✓
                    progressPaintOverride = if (progress > 0f && !slotOwn) {
                        // 2026-09-19：已播放段的**整体不透明度**可调（见该键的 KDoc）。
                        // 用户原话：「频谱色差显示播放进度有点难辨认，加入一个单独的
                        // 调节滑杆（主要是已播放/未播放的频谱的透明度）」。
                        // ── 2026-09-19：语义改为**差值**（用户明确要求）──────────────
                        //
                        // 用户原话：「进度色差的**不透明度值设置为已播放与未播放频谱的透明度差值**。」
                        //
                        // 原语义：它直接乘在已播放段的 alpha 上 —— 于是"把它拖大"会让
                        // **已播放段更不透明**，但用户看不出"差了多少"，因为它与
                        // `spectrum.opacity_pct` 是两个**独立**的标尺（BUG-076 同族）。
                        //
                        // ⚠️⚠️ 2026-09-20 **重写**：原式 `(opacity_pct + 差值).coerceIn(0, 100)`
                        //     有**天花板缺陷** —— 色差被锁死在 `100 − opacity_pct`：
                        //       · 整体透明度 75 → 最大色差只有 25%；
                        //       · 整体透明度 100 → 差值**恒等于 0，这个滑杆完全无效**。
                        //     用户原话：「你设置中的差值设置为 100% 观感仍不明显，
                        //     **你逻辑上就出错了**。」—— 他说的就是这个 clamp。
                        //
                        // 新语义：**把"未播放段"按差值压暗**，而不是把"已播放段"提亮。
                        //   · 未播放段 alpha = 整体透明度 × (1 − 差值)
                        //   · 已播放段 alpha = 整体透明度
                        //   → 差值与整体透明度**正交**（互不挤压），
                        //     差值 100% 永远 = 未播放段完全透明 = **最大对比**，
                        //     且**不再有任何天花板**。
                        //   → 差值 0% = 两段完全相同（看不出进度），单调、可读。
                        // （三个 alpha 收在 `spectrumAlphas` 里，见上面 `alphas`）
                        val tintAlpha = alphas.played
                        // ⚠️ **形状必须与底图一致**（见 [solidProgressPaint] 的 KDoc）：
                        //    底图实心时用实心笔刷，否则渐变层顶端透明、露出底图本色，
                        //    看起来"没有色差" —— 那正是用户报的那条。
                        // ★ 插槽自定义形态走**实心**（U13 对称双峰 / U14 极坐标，同理由）。
                        if (slotOwn) {
                            // ★ 2026-10-11（⑰）：实心档的进度染色层也走多色（见 `solidFlowProgressPaint`）✓
                            solidFlowProgressPaint ?: solidProgressPaint(playedPacked, tintAlpha)
                        } else if (boolOf(snapshot, "spectrum.fade_top")) {
                            backgroundSpectrumProgressPaint(playedPacked, left, bottom, top, tintAlpha)
                        } else {
                            solidFlowProgressPaint ?: solidProgressPaint(playedPacked, tintAlpha)
                        }
                    } else {
                        null
                    },
                    // ── 以下 3 项：2026-09-18 用户点名新增 ──────────────────────
                    //
                    // ① 顶端淡出（`spectrum.fade_top`）—— 原为**硬编码**的观感取舍。
                    //    注意：本调用**传了 `paintOverride`**（背景频谱的纵向渐变），
                    //    而 `SpectralPainter` 的约定是"传了 override 就以它为准" ——
                    //    故这里**必须自己**根据开关决定要不要那个渐变（见下方 override 的取法）。
                    //    ★ 插槽自定义形态（U13 侧边条 / U14 极坐标两档）**强制关**。
                    fadeTop = !slotOwn && boolOf(snapshot, "spectrum.fade_top"),
                    // ★ 2026-09-20：**逐柱末端淡出**（用户：「每条频谱条的**末端**
                    //   （不是根部）有自然的淡化效果，**不要扩展到整条**」）。
                    //
                    // 与上面的 `paintOverride`（覆盖整个频带的纵向渐变）**正交**：
                    //   · `paintOverride` 决定「整条怎么着色」；
                    //   · `paintForBar` 决定「**这一柱的末端**怎么淡」。
                    // 只靠前者是不够的 —— 它的淡出起点是带高的 65%，
                    // **矮于这个高度的柱子末端全是锐边**（这正是用户报的「没效果」）。
                    //    ★ 插槽自定义形态强制 null（同上）。
                    paintForBar = if (!slotOwn && boolOf(snapshot, "spectrum.fade_top")) {
                        { topY, bottomY, tipCornerRadiusPx ->
                            // ⚠️ 必须跟 `paintOverride` 用**同一个** `alphas.unplayed` ——
                            //    本笔刷覆盖的是柱子**末端**那段，若它满 alpha，
                            //    未播放段的压暗就只在柱子根部生效（差值滑杆"只有一半作用"）。
                            spectrumTipFadePaint(
                                packed, topY, bottomY, symmetric, alphas.unplayed,
                                // ★ ㉒ 收口：把本柱的尖端圆角传下去（原为默认 0f ⇒ 形状项失效）
                                cornerRadiusPx = tipCornerRadiusPx,
                            )
                        }
                    } else {
                        null
                    },
                    // ② 整体不透明度（`spectrum.opacity_pct` → 0f..1f）。
                    //    由 painter 用 `AlphaComposite` 统一施加，覆盖本色 / 渐变 / 进度染色三条路径。
                    //
                    // ⚠️⚠️ 2026-09-21 修：这里传的是**整体不透明度** `alphas.frameOpacity`，
                    //    **不再**是"未播放段"的 alpha。`opacity` 是**整帧**的 composite，
                    //    它会同时罩住底图与进度染色层 —— 把 `整体 × (1 − 差值)` 传进来
                    //    会让差值 100% 时**整条频谱 alpha=0**（用户报的"完全不显示"）。
                    //    "未播放段压暗"改由颜色 / 笔刷承担，见 `unplayedPacked` 与 `alphas.unplayed`。
                    opacity = alphas.frameOpacity,
                    // ③ 上下对称双峰（`spectrum.symmetric`）。
                    symmetric = symmetric,
                    // ── 对称中线：**歌词区的纵向中心**（2026-09-18 修"已知偏差"）──
                    //
                    // 用户要的语义是「**两行之间** / 该行**中线**」——
                    // 而"歌词区"就是 [areaTop] + [areaHeight] 这块矩形，
                    // 它的纵向中心在两行模式下正是"两行之间"、单行模式下正是"该行中线"。
                    //
                    // ## 原记与实为
                    //
                    // 原记：**故意不传**，靠 painter 的默认回退（= `bounds` 纵向中心），
                    //   理由写的是"`band` 本身就是歌词区，其中心恰好就是要的值"。
                    //
                    // 实为：**那句话只在 `spectrum.height_pct = 100` 时成立。**
                    //   `band` 是**歌词区的子矩形**（高度 = 歌词区 × 百分比），
                    //   一旦百分比 < 100，带中心就**不再是**歌词区中心 ——
                    //   双峰的中线会随百分比上下漂移，用户看到的是
                    //   「双峰的对称中线**不在两行之间**」，而且调高度时**中线跟着跑**。
                    //   用户原话：「双峰频谱的频谱高度是**高度**而不是占歌词栏百分比」
                    //   —— 他看到的正是"这条带被摆在了某个位置"，而不是"以歌词栏为中心"。
                    //
                    // 修法很直接：调用点**本来就拿得到** `areaTop` / `areaHeight`
                    //   （它们是本函数的参数），算一个中心传进去即可。
                    //   原注释说"本调用点拿不到歌词行几何"是**错的** —— 那是把
                    //   "行几何"（行高、行间距）与"歌词区矩形"混为一谈了。
                    symmetryCenterY = areaTop + areaHeight / 2f,
                    // ── 柱间距（`spectrum.bar_pitch_mode` / `bar_pitch_px`）─────────
                    //
                    // 判据用**岛宽**（`band` 的横向跨度）而不是歌词区宽 ——
                    // 用户要的是"**词岛**窄时条变细"，而"词岛有多窄"最直接的量
                    // 就是频谱带自己的宽度。
                    //
                    // 自动档只会在**窄**的方向上偏离默认间距（见 `spectrumAutoPitch`
                    // 的对照表：宽岛被上限夹回默认值），故宽岛观感逐像素不变。
                    // ★★★ 2026-10-11（U13）：**插槽档固定柱距** —— `slotSideBarPitchPx()`
                    //    （= 默认 7px）。AUTO 的判据是"岛有多宽"，而插槽可用宽是**固定的
                    //    28 px** ⇒ 用 AUTO 会让"5 根塞 28 px"随岛宽抖动 ⇒ 不成立 ✓
                    barPitchPx = if (slotOwn) {
                        // ★★★ 2026-10-11（U13 立 · **BUG-166 补**改）：插槽档的柱距 —— 唯一出口。
                        //   ★ 极坐标两档**跟随用户的 FIXED 值**（含 `0` = 实心 ⇒ 连续环/连续放射）——
                        //     修的是"死设置"：用户 2026-09-22 原话「**当间距为 0px 时呈现实心频谱**」，
                        //     而插槽一直忽略它（用户 2026-10-11 报障「频谱条太丑」）✓
                        //   ★ 5 根侧边条**保持固定 7**（用户拍板"只极坐标两档跟随"）——
                        //     实心会让五根并成一块 ⇒ "五根"辨识度消失 ✗
                        //   ★ `AUTO` 一律回落固定默认（AUTO 判"岛宽"，而插槽宽是固定的）✓
                        dev.cdl.render.slotBarPitchPx(
                            isPolar = slotPolar,
                            auto = local.spectrumBarPitchAuto,
                            userPitchPx = local.spectrumBarPitchPx / 10f,
                        )
                    } else if (local.spectrumBarPitchAuto) {
                        spectrumAutoPitch((right - left).toInt())
                    } else {
                        // 单位是 0.1 像素 → 除以 10 换回像素（见注册表该键的 KDoc）。
                        local.spectrumBarPitchPx / 10f
                    },
                    // ★★★ 2026-10-11（U13）：**固定 5 根** —— 本轮两个症状
                    //    （「缩小版主频谱」「不显示」）的共同根因 = 条数推导被
                    //    `SPECTRUM_MIN_BARS`(24) 托底 ⇒ 24 根塞 28 px ⇒ 糊成一片。
                    //    判据唯一出口 = `spectrumBarCountFor`（探针可断言）✓
                    forceBarCount = when {
                        // ★ U13 侧边条：条数由设置决定（★ 2026-10-13 U16：4 / 5 / 6，默认 5）。
                        //   ★ 唯一出口 `slotSideBarCountFor`（未知值回落 5 ⇒ 零回归）✓
                        slotSideBars -> dev.cdl.render.slotSideBarCountFor(
                            textOf(snapshot, "island.right_slot.spectrum_bars"),
                        )
                        // ★★★ 2026-10-11（BUG-166）：极坐标两档按**内缘**定条数 ——
                        //   ★ 必须走 `forceBarCount`（`spectrumBarCount` 的 24 托底会把
                        //     "内缘 10 根"抬回 24 ⇒ 粘连照旧）。判据见 `polarBarCount` 的 KDoc。
                        //   ★★ 间距基准**另给**（`polarCountPitchPx`）：实心档没有间距，
                        //     而"条数"是几何（决定轮廓分辨率）—— 两件事不能混（见该函数 KDoc）✓
                        polarSpecFinal != null -> dev.cdl.render.polarBarCount(
                            polarSpecFinal,
                            dev.cdl.render.polarCountPitchPx(
                                local.spectrumBarPitchPx / 10f,
                            ),
                        )
                        else -> 0
                    },
                )
            } finally {
                // 必须显式写回：`paintComponent` 之后还要画歌词/封面/控件，
                // 变换残留会让整个词岛上下颠倒（与 `g2.clip` 的写回是同一条纪律）。
                if (flip != null) g2.setTransform(flip)
            }

            // ── 副频谱（`spectrum.secondary.mode`，2026-09-18）────────────────────
            //
            // 用户原话：
            // > 「双峰频谱背景中有靠底边的另一频谱在跳动（随歌曲播放进度前进），
            // >   将这个 bug **改为独立设置**（副频谱跳动，有三个选项，底/顶/底+顶，
            // >   受显示频谱总开关管理，副频谱需要与主频谱在透明度上做出差异）」
            //
            // ## 它原本是什么
            //
            // 用户看到的"另一条频谱"是**进度染色**那一遍绘制 —— 2026-09-18 之前
            // 它漏传了 `symmetric`（见 `SpectralPainter` 的修复），于是底图画双峰、
            // 染色层画"从底部长"的柱子，看起来像背景里另一条频谱在随进度前进。
            // 那一处**已按缺陷修好**；本段是**把那个视觉收编成可选功能**。
            //
            // ## 为什么这里**不**与主频谱共用那段 `paint` 调用
            //
            // 本项目刚因"同一函数两条调用路径、参数表变了只改一条"出过缺陷
            // （进度染色漏传 `symmetric`）。故这里要特别说清：**副频谱不是"同一幅画"** ——
            // 它刻意**没有** `paintOverride`（不跟随主频谱的淡出渐变）、
            // **没有**进度染色、**没有**对称模式。能共用的参数
            // （`opacity` / `barPitchPx` / `state`）**都已经共用**，
            // 差别只在几何与透明度 —— 那是"两个元素"而不是"两处实现"。
            //
            // ## 几何（★ 2026-09-22 修「副频谱跟着控件条走」，BUG-126）
            //
            // · `BOTTOM` → 带子**底边**贴**歌词区**底边，向上长（painter 的默认方向）
            // · `TOP`    → 带子**顶边**贴**歌词区**顶边，向下长（复用主频谱那套
            //              `translate + scale(1,-1)` 翻转）
            // · `BOTH`   → 两条都画
            //
            // ## 为什么锚在"歌词区"而不是"词岛"（根因）
            //
            // 改动前两个锚点都是**面板绝对坐标**（`islandHeight - secHeight` / `0f`），
            // 而 `position = TOP` 时词岛从控件带高 `stripQ` 开始 ⇒ TOP 档的副频谱被画在
            // `[0, secHeight]` —— **正是控件条那条带**。
            //
            // ⚠️ BUG-077 只对了一半：它把 `islandHeight` 从 `height`（面板高）改成
            //    `baseHeightPx`（收起态岛高）—— **修了"高"、没修"顶"**。
            //    而 `position = BOTTOM` 下 `baseHeightPx` 与歌词区底边恰好重合 ⇒
            //    **它的验收在 BOTTOM 下必然通过、TOP 下漏网**。
            //
            // 现锚点由 [secondarySpectrumBands] 从**歌词区**的两条边算出（唯一计算点）：
            //   · `BOTTOM` 档下 `areaTop = 0` ⇒ 与今天**逐像素相同**（回归风险 0）；
            //   · `TOP` 档下 `areaTop = stripQ` ⇒ 带子落在歌词块上，**不在控件条上**。
            //
            // ⚠️ **不要**改成 `bg.islandTop / bg.islandBottom`：那会让副频谱在动画期间
            //    随带子一起"长出去"，而用户要的是**静止的歌词块**的边缘。
            //
            // 带高 = 主频谱带高 × [SECONDARY_SPECTRUM_HEIGHT_SCALE]（一半）——
            // 副频谱是背景层，等高会喧宾夺主（理由见该常量的 KDoc）。
            //
            // ⚠️ 受总开关管理是**免费的**：本函数第一行就是 `if (!local.spectrumEnabled) return`。
            val secMode = local.spectrumSecondaryMode
            // ★★★ 2026-10-05 **BUG-159 修复**（★ 审计抓到）：**副频谱是"歌词区/贴边"的功能**
            //   ⇒ ★★ **必须受总开关管** —— 与用户口径「总开关不管小组件」**不冲突**
            //   （那条只管"**右端插槽**"这个组件；副频谱画在**词岛底边/顶边**，属歌词区）✓
            //
            //   ⚠️ 原来这里**没有自己的判据**，注释写着「受总开关管理是**免费的**：
            //     本函数第一行就是 `if (!local.spectrumEnabled) return`」——
            //   ★★ **那句话在 BUG-153b 之后已失效**：本函数第一行换成了
            //     `shouldDrawSpectrumBand(...)`，而它在 `anchor == RIGHT`（插槽档）时
            //     **不看总开关** ⇒ ★ 于是插槽档下**副频谱会照画**（用户没开总开关却多出副频谱）✗
            //
            //   ★ 判据（可复用）：**"免费继承原实现判据"的注释是【承诺】，不是【事实】** ——
            //     原实现判据一改，下游就静默失效 ✓（★ 与"派生计数要重算"同族）
            //
            // ★★★ 2026-10-13（**BUG-192**）：★ **插槽档不画副频谱** ✓
            //
            // 用户第 3 次报「**右侧插槽还是显示进度**」⇒ 复查时发现：本函数对
            // **插槽目标**也会走到这里 —— 而副频谱的 `progressPaintOverride`
            // 当时**没有** `!slotOwn` 闸（见下）⇒ ★ 一旦用户打开副频谱，
            // **插槽里会出现一条缩小的副频谱 + 它的进度染色** ✗
            // ⇒ ★ 与主带同一条判据：**插槽档整段不画**（`!slotOwn`）✓
            //   ★ 理由与 `paintOverride` / `fadeTop` / `peakHold` 完全一致 ——
            //     插槽是**自足的小组件**，不该叠主带的第二层频谱 ✓
            if (secMode != SPECTRUM_SECONDARY_MODE_OFF && local.spectrumEnabled && !slotOwn) {
                val secHeight = band.height * SECONDARY_SPECTRUM_HEIGHT_SCALE
                if (secHeight > 1f) {
                    // 2026-09-21：与主频谱**同一套**（含同一个 `tintActive` 判据、同一个
                    // 唯一计算点 [spectrumAlphas]）—— 只是基准换成副频谱自己的不透明度设置。
                    // 差值与整体不透明度**正交**，故 100% 永远 = 最大对比。
                    val secAlphas = spectrumAlphas(
                        opacityPct = local.spectrumSecondaryOpacityPct,
                        tintAlphaPct = intOf(snapshot, "spectrum.progress_tint_alpha"),
                        tintActive = tintActive,
                    )
                    // 副频谱底图的颜色（高 8 位带 alpha）—— `fade_top = false` 时
                    // painter 走 `g.color = cache.color(color)` 这条唯一没有 alpha 参数的路。
                    val secUnplayedPacked = spectrumLayerArgb(packed, secAlphas.unplayed)
                    // 柱间距与主频谱同源（同一个判据、同一份取值）—— 两条频谱的
                    // 疏密必须一致，否则看起来像两种不同的图叠在一起。
                    val secPitch = if (local.spectrumBarPitchAuto) {
                        spectrumAutoPitch((right - left).toInt())
                    } else {
                        // 单位是 0.1 像素 → 除以 10 换回像素（见注册表该键的 KDoc）。
                        local.spectrumBarPitchPx / 10f
                    }
                    // ★ 锚点算术的唯一计算点（公开纯函数，探针可断言
                    //   "它只依赖 areaTop/areaHeight，不依赖控件条展开量"）。
                    val secBands = secondarySpectrumBands(
                        mode = secMode,
                        areaTop = areaTop,
                        areaHeight = areaHeight,
                        bandHeight = secHeight,
                    )
                    for ((secTop, secGrowDown) in secBands) {
                        val secFlip = if (secGrowDown) g2.transform else null
                        if (secFlip != null) {
                            g2.translate(0.0, 2.0 * secTop + secHeight)
                            g2.scale(1.0, -1.0)
                        }
                        try {
                            SpectralPainter.paint(
                                g = g2,
                                cache = renderCache,
                                bands = bands,
                                bounds = Rectangle(
                                    left.toInt(), secTop.toInt(),
                                    barWidth.toInt(), secHeight.toInt(),
                                ),
                                // ⚠️ 未播放段的颜色（带 alpha），理由同主频谱。
                                color = secUnplayedPacked,
                                state = spectrumCache,
                                style = SpectralPainter.Style.BARS,
                                nowNanos = System.nanoTime(),
                                playing = playing,
                                // 副频谱也开 —— 否则上下两条频谱里只有一条有"帽"，
                                // 看起来像画漏了（用户对副频谱的要求是"跟随主频谱"）。
                                // ★ 2026-10-11（BUG-166 补）：与主带**同一条判据** ——
                                //   插槽档不画帽（见上面主调用点的三条理由）✓
                                peakHold = peakHold && !slotOwn,
                                // ⚠️ 2026-09-21：与主频谱同步 —— 这里传**整体不透明度**
                                //    `secAlphas.frameOpacity`，未播放段的压暗由
                                //    `secUnplayedPacked` 承担。传 `secAlphas.unplayed`
                                //    会让差值 100% 时副频谱整条消失。
                                opacity = secAlphas.frameOpacity,
                                barPitchPx = secPitch,
                                // ⚠️ 2026-09-23 由 lead 补：**这里原先漏了 `fadeTop`** ✗
                                //    `SpectralPainter.paint` 的 `fadeTop` 默认值是 **`true`** ✗
                                //    ⇒ 漏传 ⇒ 副频谱**恒走淡出** ⇒ 用户报的
                                //    「**关闭顶端淡出后，顶端仍有淡出效果**」✗
                                //    （主频谱 `:7662` 传了 ✓ ⇒ 只关掉一半 ⇒ 两条频谱观感不一致 ✗）
                                //    ⚠️ 上面 2026-09-19 那段 KDoc 声称"已修"，但它修的是
                                //    `bounce_pct` 与 `progress_tint` ✗ —— **`fade_top` 从未被传过** ✗
                                //    （属"注释与事实相反"✗：读注释会以为这条已修）
                                fadeTop = boolOf(snapshot, "spectrum.fade_top"),
                                // ── 2026-09-19：副频谱**跟随主频谱的全部观感设置** ──────
                                //
                                // 用户原话：「**副频谱不能调整频谱高度和频谱跳动幅度等**
                                // （即主频谱的相关设置）」。
                                //
                                // 之前这里只传了 `opacity` 与 `barPitchPx`，于是：
                                //   · `spectrum.bounce_pct`（跳动幅度）**对它无效**；
                                //   · `spectrum.fade_top`（顶端淡化）**对它无效** —— 它恒用
                                //     painter 的默认实心填充，与主频谱的"向上淡出"观感不一致；
                                //   · `spectrum.progress_tint`（进度色差）**对它无效**。
                                //
                                // 带高本来就跟随（`secHeight = band.height * 常数`，而 `band`
                                // 由 `spectrum.height_percent` 算出），故高度这条已经通了。
                                //
                                // 为什么可以共用主频谱那份 `bounceScale` / `progress`：
                                // 两条频谱在**同一个函数**（[drawBackgroundSpectrum]）里绘制，
                                // 那些值在作用域内，且语义完全相同（"跳多少""播到哪"）。
                                bounceScale = bounceScale,
                                progressFraction = progress,
                                // ★ 2026-09-26：副频谱**跟随主频谱**的形状设置 ——
                                //   同一个键、同一个纯函数 ⇒ 必然同值。
                                barShapeRatio = spectrumBarCornerRatio(
                                    textOf(snapshot, "spectrum.bar_shape"),
                                ),
                                // ★★ 2026-10-11（⑰）：副频谱**跟随主频谱**的逐柱取色 ——
                                //   与主频谱**共用同一个回调实例**（`barColorFn`）⇒
                                //   "哪一档走哪条取色"在两条频谱上**结构上不可能**不一致 ✓
                                //   ⚠️ 这条同时**修好了一处既有缺口**：`level` 档此前只作用于
                                //      主频谱（本处原来没有 `argbForBar`）⇒ 副频谱恒单色。
                                //      用户 2026-09-19 的要求原文是「**副频谱跟随主频谱**」✓
                                argbForBar = barColorFn,
                                // 淡化渐变要用**副频谱自己的**上下边（`secTop` / `secTop+secHeight`），
                                // 不能沿用主频谱的 `bottom`/`top` —— 那是另一条带的位置。
                                paintOverride = if (boolOf(snapshot, "spectrum.fade_top")) {
                                    backgroundSpectrumPaint(
                                        packed, left, secTop + secHeight, secTop, secAlphas.unplayed,
                                    )
                                } else {
                                    // ★★ 2026-10-11：副频谱的**实心档**多色渐变 ——
                                    //   几何换成**副频谱自己的带**（`secTop` / `secHeight`）✓
                                    //   ★ 其余档 ⇒ `null`（= 改动前行为）✓
                                    // ★★★ 2026-10-14（迷彩碎色块 · 新档 `cover_camo`）：
                                    //   本处**原来只认 `COVER_FLOW`** ⇒ 新档会让副频谱退化回单色。
                                    //   ⇒ 改用 `spectrumFlowPaintFor(...)` —— 它内部按 `mode` 分派
                                    //     （`COVER_CAMO` ⇒ `flowCamoPaint`；其余 ⇒ `flowGradientPaint`）✓
                                    //   ★ 这条正是「**同一判据必须落在每一条调用路径上**」的回归点：
                                    //     加档时若只改主频谱，副频谱就会**静默退化**（无报错、无日志）。
                                    //   ⚠️ alpha 口径与主频谱**逐字相同**（见那里的推导）✓
                                    if (local.spectrumColorMode ==
                                        dev.cdl.render.CoverPalette.COVER_FLOW ||
                                        local.spectrumColorMode ==
                                        dev.cdl.render.CoverPalette.COVER_CAMO
                                    ) {
                                        spectrumFlowPaintFor(
                                            left, secTop, right, secTop + secHeight,
                                            coverPalette, flowPhase,
                                            // ⚠️ 与主频谱同一条 alpha 口径 ✓
                                            //   ★ `spectrumFlowPaintFor` 收的是**相对系数**
                                            //   （`0..1`），乘 `SPECTRUM_BACKGROUND_ALPHA`
                                            //   与 `255` 的事在它内部做 —— 与主频谱
                                            //   `solidFlowPaint` 传 `alphas.unplayed` 同源 ✓
                                            secAlphas.unplayed.coerceIn(0f, 1f),
                                            mode = local.spectrumColorMode,
                                        )
                                    } else {
                                        null
                                    }
                                },
                                // 2026-09-19：**副频谱此前根本没传这一项** → 它恒无进度色差。
                                // 与主频谱用同一套形状判据（实心对实心、渐变对渐变），
                                // 只是上下边换成副频谱自己的（`secTop` / `secTop+secHeight`）。
                                //
                                // ★★ 2026-09-22：已播放段的颜色与主频谱**同一条规则** ——
                                //    用与未播放段相同的 `packed`，只换 alpha（见 `playedPacked`
                                //    的 KDoc）。副频谱此前也走 `sungColorFor`，
                                //    故同一个"滑杆到 0 还有差别"的缺陷在两条频谱上都在。
                                // ★★★ 2026-10-13（**BUG-192**）：`!slotOwn` —— 与上面
                                //   `progressPaintOverride` **成对**（★ BUG-179b 的教训：
                                //   **两个参数都得关，少一个就漏**）✓
                                progressColor = if (progress > 0f && !slotOwn) playedPacked else null,
                                // ★★★ 2026-10-13（**BUG-192**）：`!slotOwn` —— ★ 双保险。
                                //   外层总开关**已经**加了 `!slotOwn`（插槽档整段不画副频谱）✓
                                //   这里再带一次，是为了让**静态守卫**能用一条**统一**的判据
                                //   （"凡 `progressPaintOverride` 实参必含 `!slotOwn`"）覆盖
                                //   全部调用点 —— ★ 否则守卫得先"读懂"外层条件，那太脆 ✓
                                progressPaintOverride = if (progress > 0f && !slotOwn) {
                                    // ── 2026-09-21：与主频谱**共用唯一计算点** [spectrumAlphas] ──
                                    //
                                    // 历史：2026-09-20 之前这里是 `(opacity_pct + 差值).coerceIn(0,100)`
                                    //   的**有天花板**旧式（`opacity_pct = 100` 时该滑杆恒为 0）；
                                    //   2026-09-20 改成"未播放段按差值压暗"，**但只改了主频谱**，
                                    //   副频谱仍是旧式 → 同一个键在两条频谱上行为不同。
                                    //
                                    // 现在两条频谱走**同一个函数**：基准换成副频谱自己的
                                    //   `spectrum.secondary.opacity_percent`（它本就有独立的不透明度设置），
                                    //   公式与主频谱逐字相同（见上面 `secAlphas`）。
                                    //   底图的整帧 `opacity` 传 `secAlphas.frameOpacity` ——
                                    //   **不是** `secAlphas.unplayed`（那会让差值 100% 时整条消失）。
                                    val tintAlpha = secAlphas.played
                                    if (boolOf(snapshot, "spectrum.fade_top")) {
                                        backgroundSpectrumProgressPaint(
                                            playedPacked, left, secTop + secHeight, secTop, tintAlpha,
                                        )
                                    } else {
                                        // ★★ 2026-10-11（⑰）：实心档的进度染色层也走多色
                                        //   （与主频谱**同一条规则**；几何换成副频谱自己的带）✓
                                        // ★★★ 2026-10-14：**加档 `cover_camo` 时必须同时进这里**
                                        //   —— 与上面 `paintOverride` **成对**（同一族教训：
                                        //   "两个参数都得关/都得开，少一个就漏"）✓
                                        if (local.spectrumColorMode ==
                                            dev.cdl.render.CoverPalette.COVER_FLOW ||
                                            local.spectrumColorMode ==
                                            dev.cdl.render.CoverPalette.COVER_CAMO
                                        ) {
                                            spectrumFlowPaintFor(
                                                left, secTop, right, secTop + secHeight,
                                                coverPalette, flowPhase, tintAlpha,
                                                mode = local.spectrumColorMode,
                                            ) ?: solidProgressPaint(playedPacked, tintAlpha)
                                        } else {
                                            solidProgressPaint(playedPacked, tintAlpha)
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        } finally {
                            // 与主频谱同一条纪律：变换必须显式写回，
                            // 否则后面画的歌词/封面会整体上下颠倒。
                            if (secFlip != null) g2.setTransform(secFlip)
                        }
                    }
                }
            }
        }

        //
         // 背景频谱的**渐变笔刷**（底部实、顶部全透明）。
         //
         // ## 为什么要缓存
         //
         // `LinearGradientPaint` 每次构造都会带上两个数组（fractions / colors），
         // 而本项目的绘制路径对"每帧分配"是有纪律的（见 `RenderCache` 关于
         // `Color` 缓存的论证：每帧 32 次 `Color` 构造就被视为热点）。
         // 几何只在窗口尺寸或控件条展开态变化时改变，故命中率极高。
         //
         // ## 键为什么分两段
         //
         // 见 [IslandWindow.backgroundSpectrumPaint] 的注释：颜色 32 位 + 三个坐标
         // 拼进一个 `Long` 必然要做位域裁剪，那会让"颜色高位被截掉"这类
         // 极难发现的缓存串味变得可能。分开存就没有这个问题。
         //
         // 渐变沿**纵向**（x 恒为 `left`）：柱子从底部往上长，于是"越高越淡"，
         // 见 [drawBackgroundSpectrum] 的观感取舍。
         //
        /**
         * **逐柱末端淡出**笔刷（2026-09-20 新增）。
         *
         * ## 与 [backgroundSpectrumPaint] 的区别（这是修「没效果」的关键）
         *
         * 后者把渐变铺在**整个频带**上（`left, bottom → top`），淡出起点是带高的 65%。
         * 于是**柱子矮于带高 65% 时，它的末端根本没有进入淡出区** → 锐利边缘。
         * 本函数改按**这一柱自己的纵向范围** `(topY, bottomY)` 铺渐变 →
         * **无论柱子多矮，末端都有一段淡出**，这正是用户要的「仅末端」。
         *
         * ## 淡出带宽度
         *
         * `min(柱高 × 30%, 6px)` —— 上限 6px 是「**仅末端**」的量化：
         * 高柱不会因为 30% 而变成「整条发虚」（那正是用户明确不要的）。
         * 下限 1px 保证极矮的柱子也有一点点软化，而不是纯锐边。
         *
         * ## 对称双峰：**两端都淡**
         *
         * `symmetric` 时柱子跨轴、`topY`/`bottomY` 是**上下两个尖**，故用四段色标
         * （`clear → solid → solid → clear`）让**两端各自淡出**。
         *
         * ⚠️ **每次调用分配一个 `LinearGradientPaint`**（每帧 = 柱数 × 1~2 趟）。
         *    这里**没有**做缓存，理由是渐变的坐标是**绝对**的（每柱的 top/bottom 都不同），
         *    按高度缓存会拿到位置错误的笔刷。若将来帧时间出现回归，
         *    正解是「**一支笔刷 + 逐柱 `g.translate`**」（零分配），而不是加缓存。
         */
        private fun spectrumTipFadePaint(
            packed: Int,
            topY: Float,
            bottomY: Float,
            symmetric: Boolean,
            /**
             * 底图的**相对系数**（`0f..1f`），**不含**整体不透明度（理由同
             * [backgroundSpectrumPaint] 的同名参数）。
             *
             * 2026-09-21 加：本笔刷会**覆盖** `paintOverride` 在柱子末端那段着色
             * （见上面的正交性说明），故它若不跟 `paintOverride` 用同一个系数，
             * 未播放段的压暗就会**在柱子上半截被抹掉** —— 表现是"差值滑杆只有根部生效"。
             */
            alphaScale: Float = 1f,
            /**
             * ★★★ 2026-10-03（㉒ 部分实现收口）：本柱的**尖端圆角半径**（px）。
             *
             * 由 `SpectralPainter` 逐柱传入（柱状档 = `arc`/`arcCap`，折线档 = 折点圆角 `jointArc`）。
             * ⚠️ **原实现没有这个参数** ⇒ `spectrumTipFadeFractions` 的 `cornerRadiusPx`
             *   恒取默认 `0f` ⇒ ★ **"形状项"在本路径完全不生效** ——
             *   即「圆角档顶端淡出与方角档差别不大」（㉒ 的用户原话）**在这一条路径上从未修好**。
             *   ★ 同族：**纪律 15**（同一份数据两个收敛点，只接了一处）。
             */
            cornerRadiusPx: Float = 0f,
        ): Paint {
            val base = renderCache.color(packed)
            val solid = Color(
                base.red, base.green, base.blue,
                (255 * SPECTRUM_BACKGROUND_ALPHA * alphaScale.coerceIn(0f, 1f)).toInt(),
            )
            val clear = Color(base.red, base.green, base.blue, 0)
            val h = (bottomY - topY).coerceAtLeast(1f)
            // ★ 关键帧分数交给**公开纯函数**（见 [spectrumTipFadeFractions] 的 KDoc）——
            //   2026-09-21 真机崩溃的根因就在这里：对称分支要求 `f <= 1-f`（即 `f <= 0.5`），
            //   而原写法 `f = 1 - fade/h` 在几乎所有柱高下都是 0.70~0.94 →
            //   `IllegalArgumentException: Keyframe fractions must be increasing`。
            //   抽出来之后，"分数必须严格递增"这条不变量可以被探针**穷举**。
            val fractions = spectrumTipFadeFractions(h, symmetric, cornerRadiusPx)
            return if (symmetric) {
                LinearGradientPaint(
                    0f, topY, 0f, bottomY,
                    fractions,
                    arrayOf<Color>(clear, solid, solid, clear),
                )
            } else {
                LinearGradientPaint(
                    0f, topY, 0f, bottomY,
                    fractions,
                    arrayOf<Color>(clear, solid, solid),
                )
            }
        }

        /**
         * ★★★ 2026-10-11（⑰「封面多色流光」· **频谱侧接线**）：
         * **逐柱取色回调的唯一构造点**。
         *
         * ## 为什么必须收敛成一处
         *
         * 主频谱 / 副频谱 / 插槽三型频谱**都要**这个回调（用户指令：「**应用到那些从
         * 封面取色的呈现上（主副频谱，小插槽三型频谱）**」）。
         * 若三处各写一遍判据，则"哪一档走哪条取色"**迟早漂移** ——
         * 而症状是"副频谱跟主频谱不一样"（本项目 2026-09-19 修过**同一形态**：
         * 副频谱漏传 `fade_top` ⇒ 两条频谱观感不一致）。
         *
         * ## 三档的映射（★ `null` = 整带同色 = 改动前的全部行为）
         *
         * | `spectrum.color_mode` | 回调 | 颜色来自 |
         * |---|---|---|
         * | `manual` / `cover` | **`null`** | 整带单色（`color` 参数）—— **逐像素不变** |
         * | `level` | `levelColorArgb` | **振幅**（越响越"烫"） |
         * | `cover_flow` | `spectrumFlowColorAt` | **柱序**（封面多色铺成渐变 + 流光相位） |
         * | `cover_camo` | `spectrumCamoColorAt` | **柱序**（封面多色铺成**碎色块** + 流光相位） |
         *
         * ## ★ 退化口径：`cover_flow` / `cover_camo` 而色数 < 2 ⇒ **画单色**，不是不画
         *
         * 两个取色函数内部都对"色数 < 2"**原样返回基色**（见它们的 KDoc）——
         * 于是"封面是纯黑白 / 取色失败"时用户看到的仍是**一条单色频谱**，
         * 而不是"选了多色档却什么都没有"（= 本项目最多次的"设了没反应"形态）✓
         *
         * @param coverPalette 封面多色（空列表 ⇒ 取色函数退回基色）
         * @param flowPhase 流光相位（`cover_flow` / `cover_camo` 读）
         */
        private fun spectrumBarColorFn(
            mode: String,
            coverPalette: List<Int>,
            flowPhase: Float,
        ): ((index: Int, count: Int, value: Float, base: Int) -> Int)? = when (mode) {
            dev.cdl.render.CoverPalette.LEVEL ->
                { _, _, value, base -> dev.cdl.render.levelColorArgb(base, value) }
            dev.cdl.render.CoverPalette.COVER_FLOW ->
                { i, count, _, base ->
                    dev.cdl.render.spectrumFlowColorAt(base, i, count, coverPalette, flowPhase)
                }
            // ★★★ 2026-10-14：**迷彩碎色块**档 —— 与 `cover_flow` **同接口、同四条不变量**，
            //   只把"色相映射"从连续插值换成离散热块（见 `spectrumCamoColorAt` 的 KDoc）。
            dev.cdl.render.CoverPalette.COVER_CAMO ->
                { i, count, _, base ->
                    dev.cdl.render.spectrumCamoColorAt(base, i, count, coverPalette, flowPhase)
                }
            else -> null
        }

        /**
         * ★★★ 2026-10-11（⑰）：**实心档的多色渐变笔刷**（唯一构造点）。
         *
         * 实心档（`fade_top = false` / 插槽档 / 折线档）走 `g.paint`，**不读逐柱回调**
         * ⇒ 多色只能靠**一支横向渐变**表达（`0°` = 从左到右，与逐柱回调的"按柱序"
         * **同一个走向** ⇒ 两条路观感一致）。
         *
         * ## ⚠️ alpha 口径必须与 `backgroundSpectrumPaint` / `solidProgressPaint` 逐字相同
         *
         * `255 × SPECTRUM_BACKGROUND_ALPHA × alphaScale` ——
         * ★ 底图趟传 `alphas.unplayed`、进度染色趟传 `alphas.played`（**不是** `frameOpacity`；
         * 后者是"整帧"的口径，混用会让"进度色差"滑杆在实心档下**偏一档**）。
         *
         * @return 色数 < 2 / 跨度退化 ⇒ `null`（调用方退回既有的单色路径 —— **不是不画**）
         */
        private fun spectrumFlowPaintFor(
            left: Float, top: Float, right: Float, bottom: Float,
            coverPalette: List<Int>, flowPhase: Float, alphaScale: Float,
            // ★★ 2026-10-14：**多色档的形态** —— 决定用"连续渐变"还是"碎色块"。
            //   默认 `cover_flow`（= 改动前行为，逐像素不变）；`cover_camo` 走 `flowCamoPaint`。
            mode: String = dev.cdl.render.CoverPalette.COVER_FLOW,
        ): Paint? {
            val alpha = (255 * SPECTRUM_BACKGROUND_ALPHA * alphaScale.coerceIn(0f, 1f)).toInt()
                .coerceIn(0, 255)
            return if (mode == dev.cdl.render.CoverPalette.COVER_CAMO) {
                // ★ 迷彩档：碎色块多停靠点渐变（★ 与逐柱路**同一个块划分** ⇒ 两条路同源）。
                dev.cdl.render.flowCamoPaint(
                    left.toDouble(), top.toDouble(),
                    (right - left).toDouble(), (bottom - top).toDouble(),
                    0,
                    dev.cdl.render.CAMO_BLOCK_COUNT,
                    coverPalette,
                    alpha,
                    phase = flowPhase,
                )
            } else {
                dev.cdl.render.flowGradientPaint(
                    left.toDouble(), top.toDouble(),
                    (right - left).toDouble(), (bottom - top).toDouble(),
                    0,
                    coverPalette,
                    alpha,
                    phase = flowPhase,
                )
            }
        }

        private fun backgroundSpectrumPaint(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,
            /**
             * 底图的**相对系数**（`0f..1f`），**不含**整体不透明度。
             *
             * 2026-09-21 加：见 [spectrumLayerArgb] 的 KDoc —— 底图不再靠
             * `SpectralPainter.paint` 的整帧 `opacity` 压暗，而是**自己带 alpha**。
             * 传入的是 [SpectrumAlphas.unplayed]（= `1 − 差值`）；整体不透明度由
             * 整帧 `AlphaComposite` 独自承载，**不能**在这里再乘一次。
             * 默认 `1f` = 与改动前逐字节相同（那时底图恒满 alpha，压暗由整帧 composite 承担）。
             */
            alphaScale: Float = 1f,
        ): Paint = backgroundBrush.verticalFade(packed, left, bottom, top, alphaScale)

        //
         // **已播放那一段**用的渐变笔刷（`spectrum.progress_tint`）。
         //
         // 与 [backgroundSpectrumPaint] **完全同构**：同样的纵向淡出、同样的两段键、
         // 同样各自一份缓存。区别只有颜色 —— 这里传的是**已唱色**。
         //
         // ## 为什么必须是"另一个笔刷"而不是"同一个笔刷换个颜色参数"
         //
         // 因为两者在**同一帧内同时使用**（本色画整条 → 已唱色重画已播放段）。
         // 共用一个缓存槽位会每帧互相顶掉，命中率恒为 0 —— 那比不缓存还差
         // （多了一次必然失效的比较 + 一次必然的分配）。
         //
         // ## 为什么用**同一个** [SPECTRUM_BACKGROUND_ALPHA]
         //
         // 两段的不透明度若不同，"已播放/未播放"的分界会同时表现为**亮度跳变**，
         // 而用户要的是**色差**。同 alpha 让这条分界只由颜色承担，
         // 与歌词高亮"只改色相不改透明度"是同一个取舍。
         //
        /**
         * **实心**的进度色差笔刷 —— 底图实心时（`spectrum.fade_top = false`）用它。
         *
         * ## 为什么必须有它（2026-09-19 修）
         *
         * 用户原话：「**在未开启淡入淡出时频谱没有谱条色差表示播放进度
         * （无论频谱是什么形态）**」。
         *
         * ### 根因：两层笔刷**形状不一致**
         *
         * | 层 | `fade_top = true` | `fade_top = false` |
         * | --- | --- | --- |
         * | 底图 | 纵向渐变（下实上透明） | **实心** |
         * | 进度层（改动前） | 纵向渐变 | **纵向渐变**（恒用渐变） |
         *
         * 进度层的渐变在柱子**顶端是全透明的** → 那里露出底图的**实心本色** →
         * 已播放段只有**靠近基线的一小截**变了色，越往上越"被底图盖回去"。
         * 用户看到的就是"没有色差"。
         *
         * ### 判据
         *
         * **两层笔刷形状必须一致**：底图实心 → 进度层也必须实心；
         * 底图渐变 → 进度层用**同一条**渐变。
         * 任一处不一致，叠加结果就不是"换了个颜色的同一根柱子"，
         * 而是"两幅不同形状的图叠在一起" —— 而那正是本条缺陷的形态。
         *
         * ⚠️ 缓存键**必须含 `alphaScale`**（与 [backgroundSpectrumProgressPaint] 同理）：
         *    否则用户拖 `spectrum.progress_tint_alpha` 时命中旧缓存、画面纹丝不动。
         */
        private fun solidProgressPaint(packed: Int, alphaScale: Float): Paint =
            solidProgressBrush.solidProgress(packed, alphaScale)

        private fun backgroundSpectrumProgressPaint(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,
            /**
             * 已播放段的**相对系数**（`0f..1f`），**不含**整体不透明度。
             *
             * 用户 2026-09-19：「频谱色差显示播放进度有点难辨认，加入一个单独的调节滑杆
             * （主要是已播放/未播放的频谱的透明度）」。
             * 2026-09-21 起语义统一到 [SpectrumAlphas]：本参数收的是
             * [SpectrumAlphas.played]（恒 `1f` —— "已播放段不额外压暗"），
             * 整体不透明度由整帧 `AlphaComposite` 独自承载（见 [SpectrumAlphas] 的 KDoc）。
             * 它乘在渐变笔刷的 alpha 上（见 [verticalFadePaint] 的 `alphaScale`）。
             */
            alphaScale: Float = 1f,
        ): Paint = progressBrush.verticalFade(packed, left, bottom, top, alphaScale)

        //
        // ★★ B2a（2026-10-08）：原先这里有 `spectrumGradientGeomKey` 与
        //    `verticalFadePaint` 两个私有函数，服务于上面 3 个笔刷构造函数。
        //    它们**只被那 3 个函数调用**（实测）⇒ 已随缓存状态一起收进
        //    `LinearBrushMemo`（`geomKeyOf` / `buildVerticalFade`）。
        //
        //    ★ 收进去的**另一条理由**：`verticalFadePaint` 的 KDoc 原话是
        //      「两个调用点（本色 / 已唱色）必须用**完全相同**的几何与 alpha，
        //      否则两段接缝处会出现一条可见的亮边」—— 而"两处必须一致"这种事
        //      **放在同一个类里由构造保证**，比靠两处分别记得去调同一个函数更硬。
        //

        /**
         * "**显示哪几行**"的**唯一判定点** —— [drawLyricSlots] 与窗口几何都必须由它推导。
         *
         * ## 当前模型（2026-09-18 起）：**恒为单槽**
         *
         * 本方法**恒返回一个槽位**（`snap.currentLine`）。
         *
         * 旧的「`island.experimental_multi_line` 总闸 × `layout.line_mode` 三选」
         * 两层模型已**整体删除**（见 [LineCount] 的 KDoc）：自"未唱行一律不显示"后，
         * 三选里有两个内容完全相同 → 两个设置项只表达两种状态，故合并为 `layout.lines`。
         *
         * ⚠️ **"双行"不在这里实现** —— 双行的第二行是**副歌词（译文）**，
         *   由 [drawTranslationLine] 单独绘制、单独占高，受 `layout.translation.enabled`
         *   控制。本方法只负责"**主歌词**占几个槽"（恒为 1）。
         *
         * ## 为什么收成一个方法（而不是在 `drawLyricSlots` 里直接写死）
         *
         * 因为它有**两个消费者**：绘制（本文件）与**窗口高度**（[effectiveRows]）。
         * 两者若各写一份判定，"行数改了但窗口高度没跟着改"这类错配就会出现 ——
         * 表现为"文字上方或下方空一大块"，而且**不报错**。
         *
         * ## 为什么取当前行
         *
         * 对齐原实现 0.8.9 `ActiveLyrics.select` 的 `!experimental` 分支：
         * `listOfNotNull(snapshot.line)` —— 即**宿主最后推送的那一行**。
         * 本插件里"最后推送的那一行"与"位置落在的那一行"在曲中恒等
         * （`lineAt` 的语义，见 `LyricDocument.lineAt`），故取 `snap.currentLine` 等价，
         * 且无需把宿主回调行单独存一份。
         */
        private fun slotsFor(snap: TimelineSnapshot, local: Appearance): List<Slot> {
            // ── 2026-09-18 简化：**恒为单槽（当前行）** ─────────────────────
            //
            // 用户原话：「取消多行歌词设置，只保留选择**单行（主歌词）**、
            //   **双行（主+副）**的设置，**取消已唱歌词的显示**。」
            //
            // 原实现按 `layout.line_mode` 三选，其中两项会输出
            // `Slot(snap.prevLine, isCurrent = false)`（**已唱行**）。
            // 用户要求取消已唱行 → 那些分支整体删除，本方法退化为单槽。
            //
            // ⚠️ **"双行"不在这里实现** —— 双行的第二行是**副歌词（译文）**，
            //   由 [drawTranslationLine] 单独绘制，并受 `layout.translation.enabled`
            //   控制（见 `layout.lines` 的 KDoc）。本方法只负责"主歌词占几个槽"。
            //
            // ⚠️ 与 [effectiveRows] 的同步仍是硬约束：主歌词行数恒为 1，
            //   两边都由 [LineCount.SINGLE] 的 `rows` 驱动，不会漂移。
            // ★★★ 2026-10-07 **B3a**：判据已外提到顶层公开纯函数
            //   [dev.cdl.window.currentLineSlots]（纪律 17：抽纯函数，而不是改可见性）。
            //   本方法退化为**壳** —— 只把「画哪几行」包装成带 `isCurrent` 的 `Slot`。
            //   ★ 行为逐字等价：原来也是无条件造一个 `Slot(snap.currentLine, isCurrent = true)`。
            return currentLineSlots(snap.currentLine).map { Slot(it, isCurrent = true) }
        }

        /**
         * 绘制歌词槽位。**显示哪几行由 [slotsFor] 决定** —— 当前**恒为 1 个（当前行）**。
         *
         * ## 数据流（这是本项目的"最后一公里"接线点）
         *
         * ```
         * snapshotSupplier()            ← 组合根注入的 lambda，内部是 timeline.snapshot()
         *   ├─ prevLine / currentLine / nextLine   → 三行的文本（[slotsFor] 当前只用 currentLine）
         *   ├─ positionMs                           → 逐字模式下每字进度的输入
         *   ├─ currentLineProgress                  → 整行模式下当前行的进度
         *   └─ isPlaying / docVersion               → 帧驱动用来决定启停与抢帧
         *        ↓
         * renderCache.setFont / syncFrom / textLayoutFor   ← 字体与 TextLayout（缓存）
         *        ↓
         * karaokeRenderer.render(g2, cache, layout, ...)   ← 两遍 clip 绘制
         * ```
         *
         * ## 为什么三槽位的 y 是"先定槽位数、再均匀分布"而不是写死三个 y
         *
         * 槽位是**可塌缩**的：`PlaybackTimeline.nextLineFor()` 在"没有下一行"时给出 `null`
         * （2026-09-18 前由设置项 `layout.emptySlot.keepPlaceholder` 决定，
         * 该项因两个分支在真机上均不可达已移除，现固定为该行为）。
         * 塌缩已由时间轴层落地，故这里只需**按实际非 null 的槽位数**重新算 y —— 布局自然收紧。
         *
         * 若写死三个 y，槽位塌缩时下面就会留一块空白，
         * 正是"当前行在句间上下抖动"的来源。
         *
         * ## 为什么每行都包 runCatching
         *
         * `paintComponent` 抛出的异常会被 Swing **吞掉**（`RepaintManager` 捕获后
         * 不再传播），留下的是"这一块区域永远不再重绘"的白块 —— 故障现场离病根很远。
         * 而文本构造路径上确实有真实的崩溃点（`TextLayout` 构造对非法输入会抛）。
         * 故逐行降级为"这一行不画"，绝不让整个词岛白屏。
         * ⚠️ 槽位选择**不在这里** —— 见 [slotsFor]（它是"显示哪几行"的唯一判定点，
         *   当前**恒返回单槽（当前行）**；行数模型见 [LineCount]）。
         *
         * @param width **文字区**宽度（= 面板宽 − 左侧让出的宽度，见 [IslandPanel.paintComponent]）。
         *   ⚠️ **不是面板宽** —— 居中/右对齐都基于这个宽度算。左侧显示封面时它比面板窄，
         *   于是文字在**剩余区域**里居中，而不是被封面压住或整体偏右。
         * @param areaLeft **文字区**左边缘的 x（0 = 不显示封面，与改动前完全一致）。
         *   左对齐时文字从这个值 + [HORIZONTAL_INSET] 起排 —— 否则左对齐的字会**压在封面上**。
         * @param areaTop 歌词区上边缘。**收起态恒为 0**；展开且 `controls.position = TOP`
         *   时为控件条的高度 —— 此时歌词整体下移，让出顶部那条给控件。
         *   传入它而不是在内部读展开态，是为了让"歌词让出的空间"与"控件占用的空间"
         *   来自**同一份 Layout**（见 [IslandPanel.paintComponent]）
         * @param areaHeight 歌词区高度。收起态 = 面板高度（**与改动前完全一致**：
         *   此时 `centerY = 0 + height / 2`，正是原来那一行）；展开且 `position = BOTTOM`
         *   时仍等于原高度（控件条长在下方，不动歌词）
         * @param coverRgb 封面主色（`null` = 手动档或无封面）。**只影响颜色解析**，
         *   不影响任何几何 —— 见 [Appearance.sungColorFor]
         */
        private fun drawLyricSlots(
            g2: Graphics2D,
            local: Appearance,
            width: Int,
            areaLeft: Float,
            areaTop: Float,
            areaHeight: Float,
            coverRgb: Int?,
            /**
             * 自然宽度测量用的让位宽（**岛体相对坐标**，且恒取**收起态**）。
             *
             * ## 为什么必须与 `areaLeft` 分开（这是本轮"打破循环依赖"的那一步）
             *
             * `areaLeft` 现在是**面板绝对坐标**（含岛体左边缘），且随展开进度变化
             * （封面会变大）。而它正是 `naturalWidthPx` 的输入，`naturalWidthPx` 又决定
             * 岛宽 —— 若测量跟着展开进度走，就会得到
             *
             * ```
             * 悬停 → 封面变大 → 让位宽变大 → naturalWidthPx 变大 → applyNaturalWidth
             *      → setBounds → 【刚根治的"悬停闪动"当场复发】
             * ```
             *
             * 故测量**一律按 `f = 0` 算一次**（调用方传入），于是
             * **凡能影响窗口几何的量都与悬停态无关** —— 这是那条不变量（悬停全程
             * `setBounds == 0`）在"封面会变大"这一新特性下**仍然成立**的唯一保证。
             *
             * 默认值 `areaLeft` 让"没有封面 / 没有展开"的路径**一个字都不用改**。
             */
            measureAreaLeft: Float = areaLeft,
        ) {
            // `island.enabled = false` → **不渲染**（而非隐藏窗口）。见 [applyVisibility]。
            if (!local.islandEnabled) return

            // 快照可能为 EMPTY（首帧早于第一次宿主回调）：那时三槽位都是 null，
            // 本方法自然什么都不画 —— 不需要额外的"有没有数据"判断。
            val snap = snapshotSupplier()

            // ── `lyric.offset_ms`：**在这里算一次**，逐字与整行两个粒度共用 ──
            //
            // 为什么不在两个使用点各加一次：那样"改一处忘一处"会让逐字与整行错位，
            // 而错位的表现（整行已唱满、最后一个字还没亮）很难归因到"偏移没同步"。
            // 收在这里，偏移的语义就只有一个定义点（见 Appearance.lyricOffsetMs 的 KDoc）。
            val effectiveMs = snap.positionMs + local.lyricOffsetMs

            // 字体：每帧都设一次是**安全的**，因为 `RenderCache.setFont` 内部用
            // `Font != font` 比较（Font 重写了 equals），字体没变时不清缓存、不重建。
            // 故这里不需要"字体变了没"的判断，把这个不变量留给缓存自己维护。
            //
            // ── 字间距（`main_lyric.letter_spacing_tenth_px`，2026-09-19）─────────
            // 用户原话：「允许调整主副歌词的**字间距**，可选以一定比例联动调整。」
            // 字距加在**字体属性**上（见 [fontWithLetterSpacing]），于是 advance / caret /
            // outline 全部按新字距重算 —— 命中域（`drawnRows`）**自动**跟着走，
            // 不需要第二处同步。`letterSpacingTenthPx = 0`（默认）时不派生字体，
            // 与改动前**逐位相同**。
            runCatching {
                renderCache.setFont(
                    fontWithLetterSpacing(
                        local.fontFamily,
                        local.fontSize,
                        local.mainLetterTracking(),
                        local.fontBold,
                    ),
                )
            }

            // 用真实绘制上下文的 AA / 分数度量校准 FontRenderContext。
            // 不校准的话，默认 frc 与实际的抗锯齿设置不一致，会被 `TextLayout` 的
            // advance 带偏（详见 RenderCache.syncFrom 的说明）。
            runCatching { renderCache.syncFrom(g2) }

            // ── ① 组装槽位 ────────────────────────────────────────────────
            // ⚠️ 2026-09-18：`slotsFor` 当前**恒返回单槽（当前行）** —— 旧的
            //   「总闸 × `layout.line_mode` 三选」已整体删除（见 [slotsFor] / [LineCount]）。
            //
            // 用 null 表示"这一槽不存在"。槽位塌缩已由时间轴落地
            // （`PlaybackTimeline.nextLineFor`：没有下一行就是 null），
            // 这里只忠实反映快照 —— 不在渲染层二次判断设置，避免两处各解释一半。
            //
            // ⚠️ 槽位选择仍只收在 [slotsFor] 一处 —— 它是"显示哪几行"的**唯一判定点**，
            //   与几何用的 [effectiveRows] 必须由**同一个** `layout.lines` 驱动，
            //   不允许各自判定。
            val slots = slotsFor(snap, local)
            // 过滤掉"没有行对象"的槽 —— 它们不占位（这正是槽位塌缩的效果）。
            val present = slots.filter { it.line != null }
            // ★ 歌词行切换（2026-09-20）：**每帧一次**地检测"当前行换了吗"。
            //   放在这里（槽位循环之前）而不是循环内 —— 理由见 [syncMainExit]。
            syncMainExit(present.firstOrNull { it.isCurrent }?.line?.text)
            // ── 无歌词（本曲没有歌词，或还没收到第一行）─────────────────────
            // ⚠️ 改动前这里直接 `return` —— 于是 `layout.noLyrics.mode` 与
            // `layout.noLyrics.showTrackTitle` **两个键都是死设置**
            // （设置页能选，行为恒定 = HIDE_LINE）。见 NoLyricsMode 的 KDoc。
            //
            // ★★ 2026-09-28 **判据修正**（用户报障「判定不准确(时显时不显)」）：
            //   原来只判 `present.isEmpty()`（即 `currentLine == null`）——
            //   于是**每一首歌的前奏**（位置早于第一行）都被当成"没有歌词"，
            //   占位句/曲名闪一下、第一行来了又消失。现改用 [noLyricsDisplay]：
            //   「文档为空」才画占位，「有行但还没到」**什么都不画**（歌词马上就来）。
            //   与 [applyVisibility] 的 HIDE_ISLAND 判定**同一个函数** —— 唯一真源。
            val lyricsState = noLyricsDisplay(
                lyricLineCount = snap.lyricLineCount,
                hasCurrentLine = snap.currentLine != null,
            )
            // (B) 前奏（有行、还没唱到）：什么都不画，**也不画占位**。
            if (lyricsState == NoLyricsDisplay.NOT_YET) return
            if (lyricsState == NoLyricsDisplay.NO_LYRICS) {
                // HIDE_ISLAND 的隐藏动作由 applyVisibility 负责（生命周期动作，
                // 不该在绘制层做）；这里只处理"要画点什么"的两档。
                //
                // ── `layout.noLyrics.hideWhenInstrumental`（2026-09-19）─────────
                // 用户原话：「追加**检测到纯音乐时不显示歌词/曲名/作者**的开关」。
                // 它挡的**只是那行文字**：窗口、封面、频谱照常（要连窗口一起藏，
                // 那是 `noLyricsMode = HIDE_ISLAND`，见 [applyVisibility]）。
                // 判据与 `noLyricsMode` **同一处**（就是本分支）—— 于是"无歌词"
                // 这个状态只有一处定义，两个键不可能各自解释一半。
                // ⚠️ 2026-09-19 二次修订：`hideWhenInstrumental` **不再"整块不画"** ——
                //    用户明确纠正「而不是**连歌名/作者都不显示了**」。
                //    现在它只作为 `suppressPlaceholder` 传进去：挡占位句、留「曲名 · 作者」。
                //    于是"只想让那句「纯音乐，请欣赏」消失"这个诉求终于能被满足。
                if (local.noLyricsMode == NoLyricsMode.PLACEHOLDER) {
                    drawNoLyricsNotice(
                        g2, local, width, areaLeft, areaTop, areaHeight, snap, coverRgb,
                        suppressPlaceholder = local.hideWhenInstrumental,
                    )
                }
                return
            }

            // 行间距 = 行高 + 用户额外间距（`layout.line_gap_px`）。
            // 用 `lineHeight` 做垂直步进、`lineGapPx` 只加在步进上 —— 这样
            // "行高"（字号×比例）与"行间距"（用户项）在语义上仍然分离，
            // 见注册表该项的注释（做成"额外量"而非"总行高"的理由）。
            val lineHeight = local.fontSize * LINE_HEIGHT_RATIO + local.lineGapPx
            // 当前槽位是否要画译文行（BUG-015）。判定收在这里一次，下面复用：
            //   · 设置开关打开；
            //   · 当前行确实有译文（非空）。
            // **只对当前行生效** —— 译文是当前行的附加子行，不是独立槽位
            // （理由见 islandHeightFor 的 KDoc）。
            val currentSub = snap.currentLine
                ?.let { it.translation?.takeIf(String::isNotBlank) }
                ?.takeIf { local.translationEnabled }
            // 译文行占用的垂直空间。**只加在"当前行"这一槽之后**，
            // 因为它只属于当前行；若平摊给每一槽，非当前行会白白多出空隙。
            // ★★ 2026-10-03：式子**已收敛到公开纯函数** `translationRowHeightPx`
            //   —— 它与副行落点 `translationCenterOffsetPx` **必须由同一个语义来源给出**，
            //   而两者此前各算各的（`+ TRANSLATION_GAP` 出现在这里、落点那里又写一遍），
            //   那正是"副行没填满预留槽 ⇒ 上下留白不等"的根因。
            //   ⚠️ 抽出**不改行为**（字面量逐字相同，只是换了名字）。
            val subRowHeight = if (currentSub != null) {
                translationRowHeightPx(local.fontSize, local.translationScale, local.lineGapPx)
            } else {
                0f
            }
            // 每个"当前槽"之后的额外步进（译文子行的高度）。抽成 val 是为了让
            // "有译文 / 没译文"这一个判据在下面的步进里只出现一次 ——
            // 与 `subRowHeight` 两处各判一次的话，"改一处漏一处"会让
            // 块高与步进不一致，表现为末行被切掉或下方空一块。
            val subAdvance = subRowHeight
            // 歌词区的垂直中心。收起态下 areaTop = 0、areaHeight = height，
            // 于是这一行**退化成改动前的 `height / 2f`** —— 展开机制对未展开时的排版零影响。
            val centerY = areaTop + areaHeight / 2f
            // 以"当前行"为锚点做垂直居中：当前行永远是视觉焦点。
            // 若以整块中心为锚，三槽位数量变化时当前行会上下浮动 ——
            // 而当前行是逐句高频切换的，它抖动最刺眼。
            val anchorIndex = present.indexOfFirst { it.isCurrent }.let { if (it < 0) 0 else it }
            // ── 整块高度与垂直锚点（2026-09-17 修正）──────────────────────────
            //
            // 原记：`firstY = centerY - anchorIndex * lineHeight - subRowHeight / 2f`，
            //   即"把**锚点行**（当前行）抬到块中心再往上挪半个译文行"。
            //
            // 实为：**译文行必须占进"行与行之间"的步进里，否则会压在下一行上。**
            //   原实现 0.8.9 就是把译文算进**行块自身高度**的
            //   （`IslandTextBlock.kt:21`：`height = shapedMain.height + gap + shapedSub.height`），
            //   于是"当前行 + 译文 + 下一行"三段各有各的位置。
            //   本项目此前把译文画在 `center + lineHeight/2 + GAP` 处，却**没有把后续槽位下移** ——
            //   **原记**：`LINE_HEIGHT_RATIO = 1.6` 下相邻两行的墨迹是「紧贴」的（行间余量为 0）。
            //   **实为（2026-09-20 实测，`FontMetrics`）**：1.6 **并不是**墨迹接触的比率 ——
            //     MiSans 的 `(ascent+descent)/字号` 实测约 **1.36**（22 号 1.364 / 40 号 1.350），
            //     所以 1.6 时**每行反而多出** `0.236 × 字号` 的空白（22 号 ≈ **5.2 px**）。
            //     即：**「余量为 0」这个归因是错的**。
            //     ⚠️ 但下面那条**实测结论本身仍然成立** —— 译文行插进去确实会与下一行重叠
            //     （22 号实测约 9 px）。真正的原因是**译文行自身高度**
            //     （`字号 × TRANSLATION_SCALE × LINE_HEIGHT_RATIO + TRANSLATION_GAP`）
            //     **超过了可用的步进**，而不是「步进里没有余量」。
            //     → 归因错了不影响修法（都是把译文算进步进），但会误导后来人调参，故记正。
            //
            // 修法（**只在新功能生效时才改变行为**）：
            //   · `subRowHeight == 0f`（无译文，= 改动前用户能看到的全部场景）
            //     → 仍走"锚点行居中"公式，**逐像素不变**；
            //   · `subRowHeight > 0f`（真的在画译文）→ 改用**整块居中**
            //     （与原实现 `top = (areaHeight - contentHeight) / 2` 同义）。
            //     不画译文时（主歌词单槽）两者等价；
            //     但**在画译文**时块是"主歌词 + 译文"两行，"锚点居中"会让块整体下沉、
            //     末行被面板底边切掉（实算 fontSize 22 时溢出约 11 px），故必须切到整块居中。
            // 副歌词在上还是在下（`layout.translation.position`）。
            // 只在真的会画译文时才有意义 —— 没有译文行时它完全不影响布局。
            val subAbove = currentSub != null && local.translationAbove
            // ── 整块垂直居中，**并让块包含译文行所占的那一段**（2026-09-18 扩）──
            //
            // 块高度恒为 `n * lineHeight + subRowHeight`（与译文在上/在下无关）。
            // 把"块顶边"对齐到 `centerY - 块高/2` 之后：
            //   · 译文在**下** → 主歌词第一行的中心 = 块顶 + lineHeight/2
            //   · 译文在**上** → 译文占了块顶那一段，故主歌词第一行要**再下移** subRowHeight
            // 这就是下面 `+ subRowShift` 的全部含义。
            //
            // ⚠️ `subRowHeight == 0f` 时**逐像素等于改动前**的公式
            //   （`centerY - anchorIndex * lineHeight`），故没开译文行的用户不受影响。
            // ★★ 2026-10-03：式子**已抽成公开纯函数** `dev.cdl.window.lyricFirstCenterY`
            //   —— 为了让探针能断言"上下留白相等"（本类是 private，探针看不到）。
            //   ⚠️ 抽出**不改行为**：函数体是这段的**逐字复制**（含"无译文走锚点行 / 有译文走整块"两档）。
            val firstY = lyricFirstCenterY(
                areaTop = areaTop,
                areaHeight = areaHeight,
                anchorIndex = anchorIndex,
                rows = present.size,
                lineHeightPx = lineHeight,
                subRowHeightPx = subRowHeight,
                translationAbove = subAbove,
            )

            // ── 自然宽度测量（服务 `layout.fixed_width = false`）──────────────────
            //
            // 取**最宽那一行的 advance** + 左侧信息区 + 两侧内边距 —— 这是"内容需要多宽"。
            // 原实现同义实现见 `IslandLyricsLayout.kt:38` 的 `if (settings.fixedWidth) limit else natural`。
            //
            // 用 `renderCache.textLayoutFor` 而不是另建 `TextLayout`：它就是本方法
            // 马上要用的那个缓存，命中时不重建（见 `RenderCache` 的 KDoc）
            // → 这次测量**零额外成本**（不新增塑形）。
            //
            // ⚠️ **只在变化超过迟滞阈值时才回填并触发重排** —— 无条件跟随会让窗口
            //    每帧都改尺寸（实测噪声 1–2 px），用户看到的是边缘持续微颤。
            //    阈值理由见 [WIDTH_HYSTERESIS_PX]。
            runCatching {
                var widest = 0f
                for (slot in present) {
                    val text = slot.line?.text ?: continue
                    if (text.isBlank()) continue
                    val advance = renderCache.textLayoutFor(text)?.advance ?: continue
                    if (advance > widest) widest = advance
                }
                // ★★★ 2026-10-18：**译文也要计入岛宽**（用户报障："词岛宽度足够显示，
                //   副歌词/译文还是左移"）。
                //
                // ## 根因：这段测量**只量了正文行**，译文从未参与
                // ⇒ 岛宽按**正文**的最宽行决定，而译文用的是**另一套字体与字号**
                //   （`fontSize × translationScale`，且字距可能跟随正文）
                // ⇒ 当**译文比正文宽**时（翻译通常更长，或正文是短句），
                //   岛宽**天生就装不下译文** ⇒ 译文**恒溢出** ⇒ 恒左移 ✗
                //
                // ⚠️ 为什么不能"直接拿译文去 `textLayoutFor`"：
                //   那个重载用 `cache.font`（**正文**字体），而译文用的是 `subFont`
                //   （见 [drawTranslationLine]）⇒ 传错字体会量到一个**假宽度** ✗
                //   故必须**显式传 `subFont`**，与绘制侧**逐字同源**。
                currentSub?.takeIf { it.isNotBlank() }?.let { sub ->
                    translationFontFor(local)?.let { subFont ->
                        renderCache.textLayoutFor(sub, subFont)?.advance?.let { adv ->
                            if (adv > widest) widest = adv
                        }
                    }
                }
                if (widest > 0f) {
                    // ⚠️ 用 `measureAreaLeft`（**收起态**的让位宽），不是 `areaLeft` ——
                    //    后者随展开进度变化，会让"悬停"改到窗口宽度（见其 KDoc）。
                    // ★★ 2026-10-17（**BUG-225**）：加 `WIDTH_GLYPH_SLACK_PX` ——
                    //    `TextLayout.advance` **不含描边外扩**（`main_lyric.outline` 开着时
                    //    每个字左右各多出 1~2 px），也不含抗锯齿的半个像素。
                    //    不加这 4 px，`availWidth` 恰好比 `advance` 少一点点 ⇒ 长行**常态滚动** ✗
                    val measured =
                        (widest + measureAreaLeft + 2 * HORIZONTAL_INSET + WIDTH_GLYPH_SLACK_PX).toInt()
                    if (kotlin.math.abs(measured - naturalWidthPx) > WIDTH_HYSTERESIS_PX) {
                        naturalWidthPx = measured
                        // ⚠️ **延迟到下一个 EDT 周期**再改窗口尺寸：在 `paint` 里直接改
                        //    会造成重入（改尺寸 → 触发重绘 → 又进 paint → 又改尺寸…）。
                        javax.swing.SwingUtilities.invokeLater {
                            runCatching { applyNaturalWidth() }
                        }
                    }
                }
            }

            // 本帧的时序源记录：**每帧清空** —— 于是"本帧没画当前行"自然表现为 null，
            // 不必再写一套失效逻辑（与 `drawnRows` 同一条约定）。
            timingMainDesc = null
            timingSubDesc = null

            // ⚠️ 2026-09-23：原先这里每帧清空 `scrollActive`（帧驱动判据）。
            //    滑移改成**进度驱动**之后该字段已删除 —— 见类内"长歌词水平滚动"那段说明。
            //    （历史：2026-09-21 修过"它从不复位导致帧循环永不自停"的缺陷，
            //      那个缺陷随字段一起消失。）

            // 槽位游标：**逐槽推进**，而不是 `firstY + index * lineHeight` ——
            // 当前行之后要多让出一个 `subRowHeight`（译文子行占的位置）。
            // ⚠️ 必须用游标：循环里有两处"跳过绘制"（行对象缺失 / 空文本），
            //    但它们**仍然占位**，游标必须无条件推进，否则后续行会往上叠。
            var cursorY = firstY

            for (slot in present) {
                // 本槽的垂直步进。非当前槽恒为 `lineHeight`（与改动前一致）。
                val step = lineHeight + if (slot.isCurrent) subAdvance else 0f
                val line = slot.line
                val text = line?.text
                // 空文本（blank 而非仅 empty）：保留占位（槽位已在 `present` 里，
                // 空间已被计入），但不画字。**必须 early-skip 而不是去建空 layout** ——
                // `TextLayout("")` 会抛 IllegalArgumentException（实测），
                // 而 blank 的字符串（纯空格）建 layout 虽不抛，但没有可画的墨迹。
                // ⚠️ `line == null` 在本循环里不可达（`present` 已过滤掉它），判空只为类型收窄。
                if (line != null && !text.isNullOrBlank()) {
                    val center = cursorY
                    runCatching {
                        drawOneLine(g2, local, effectiveMs, slot, line, text, width, areaLeft, center, coverRgb, lineHeight)
                    }.onFailure {
                        System.err.println("[CDL] 歌词行绘制失败（本行跳过）：$it")
                    }

                    // 译文行：紧贴当前行下方，**与主歌词同步高亮**（2026-09-17 改）。
                    //
                    // 原记：「整行静态色、不做任何逐字高亮 —— 宿主只给一份 `lyricsCells`
                    // （属于原文），译文没有自己的时间轴，没有时间轴就不可能有正确的逐字边界。」
                    //
                    // 实为（2026-09-17 第二次修正）：**"宿主只给一份 cells"这个前提本身是错的** ——
                    //   `pureMainText` 就是 cells 的拼接，译文既然在 `pureMainText` 里，
                    //   就必然也在 cells 里。于是：
                    //   · `cells=1`（真机 86/86）→ 译文那一段没有亚行粒度 → **整行进度**高亮；
                    //   · 逐字 cells → 尾部那段就是**译文自己的**逐字时间轴 → **逐字**高亮。
                    //   两条路由 `LyricLine.hasTranslationTimingEvidence` 自动分档，
                    //   渲染层不猜、不伪造（判据见 `narrowWordsToTranslation` 的 KDoc）。
                    //   注意译文**绝不借用原文的时间轴** —— 那会按原文的字数切译文，
                    //   把高亮落在错误的字上（比不亮更糟）。
                    if (slot.isCurrent && currentSub != null) {
                        drawTranslationLine(
                            g2, local, line, currentSub, width, areaLeft,
                            // ★★★ 2026-10-03 **本次修复点**：副歌词的垂直中心。
                            //
                            // 原记：`center ± (lineHeight/2 + TRANSLATION_GAP)`。
                            // **实为**：漏了 `+ 副行行高/2` ⇒ 副行**墨迹填不满它的预留槽**
                            //   ⇒ 整块墨迹偏上 ⇒ **上留白 10 / 下留白 19.6**（用户报的正是这个）。
                            // ⇒ 改由公开纯函数 [translationCenterOffsetPx] 给（**唯一实现点**，
                            //   无歌词占位路径也调它）。
                            //
                            // ★★★ 2026-10-03 **第二次修正（方案翻转）**：我当天第一版**剔掉了** `lineGapPx`
                            //   （理由写的是"行间距不该推动子行"）—— **那是错的**：
                            //   `layout.line_gap_px` 的**用户诉求就是「一二行歌词间距」**
                            //   （= 主歌词行 ↔ 副歌词行，与"副歌词"同一条诉求里提出，见 CHANGELOG v0.12 §④）。
                            //   剔掉它 ⇒ 该键**排版零效果** ⇒ **死设置**。
                            //   ⇒ 现由 `translationCenterOffsetPx` 与 `translationRowHeightPx`
                            //     **成对**含 `lineGapPx` ⇒ 主副拉开 + 上下留白仍相等 + 岛高跟着长。
                            center + translationCenterOffsetPx(
                                fontSize = local.fontSize,
                                translationScale = local.translationScale,
                                above = subAbove,
                                lineGapPx = local.lineGapPx,
                            ),
                            // 与主歌词行**共用同一个** `effectiveMs`（含 `lyric.offset_ms`）——
                            // 用 `snap.positionMs` 会让"主行已偏移、译文没偏移"，
                            // 两者高亮错位。这与 `drawOneLine` 里 `progress = line.progress(effectiveMs)`
                            // 是同一个理由（见那里的注释）。
                            effectiveMs = effectiveMs,
                            coverRgb = coverRgb,
                        )
                    }
                }
                // 无条件推进：被跳过的槽位同样占位（其空间已计入 `present`）。
                cursorY += step
            }

            // ── 高亮时序源诊断（按变化去重，见字段 [lastTimingDesc]）──────────────
            //
            // 只在**本帧画了当前行**时打（`timingMainDesc != null`）。
            // 描述里带上"为什么"（无逐字轴 / LINE 档 / 非当前行），否则只报"整行"
            // 等于没报 —— 下次排查仍要回头读代码。
            val mainDesc = timingMainDesc
            if (mainDesc != null) {
                val desc = "mode=${local.karaokeMode} 主=$mainDesc 译=${timingSubDesc ?: "无译文"}"
                if (desc != lastTimingDesc) {
                    lastTimingDesc = desc
                    System.err.println("[CDL] 高亮时序源：$desc")
                }
            }
        }

        //
         // 画一行。**所有坐标都是绝对像素**（已含居中与垂直分布的结果）。
         //
         // ## 为什么用 `layout.advance` 而不是 `layout.bounds` 来居中
         //
         // `TextLayout.bounds` 是**墨迹包围盒**：它的 x 偏移是墨迹相对原点的偏移
         // （斜体、部分标点会带非零偏移），且 y 是相对基线坐标系的高度。
         // 用它居中会让文字看起来"偏了一点"，且偏移量随文本变化 —— 像是随机抖动。
         // `advance` 才是"这一行排出来占多宽"，是排版意义上的宽度。
         //
         // ## 基线的算法
         //
         // `baseline = center + (ascent - descent) / 2` 是 Java2D 里让文字**视觉居中**
         // 的常用公式。直接用 `center` 会让文字偏下，因为基线在字形底部而非中部。
         // ascent/descent 从 `TextLayout` 取（而不是 `Graphics2D.fontMetrics`）——
         // 保证"测量用的度量"与"布局用的度量"同源，避免字体回退时两者不一致。
         //
        /**
         * 画一行歌词。
         *
         * @param effectiveMs **已含 `lyric.offset_ms` 的播放位置**（由 [drawLyricSlots]
         *   算好后传进来）。本函数**刻意不收 `TimelineSnapshot`** ——
         *   那样每处用它算进度时都得自己记得加偏移，漏一处就出现"逐字与整行错位"。
         *   传一个已经调好的标量，语义上就只有"这个时间点"一个含义。
         * @param width 文字区宽度（见 [drawLyricSlots] 的同名参数）
         * @param areaLeft 文字区左边缘（见 [drawLyricSlots] 的同名参数）
         * @param coverRgb 封面主色，用于解析歌词色（见 [Appearance.sungColorFor]）
         */
        private fun drawOneLine(
            g2: Graphics2D,
            local: Appearance,
            effectiveMs: Long,
            slot: Slot,
            line: LyricLine,
            text: String,
            width: Int,
            areaLeft: Float,
            center: Float,
            coverRgb: Int?,
            /**
             * 一个行高（含 `layout.line_gap_px`）。**由 [drawLyricSlots] 传进来** ——
             * 它是那边的局部变量，本函数看不到；而**不能在这里重算** ——
             * 重算就是第二份公式，"改一处漏一处"会让退场距离与行距不一致。
             */
            lineHeight: Float,
        ) {
            // 逐字模式下开卡拉 OK 分割；非当前行一律整行绘制（更快，且视觉上
            // "还没唱到/已经唱过"的行本就不该逐字高亮）。
            //
            // `main_lyric.karaoke.mode = LINE` → 不做逐字分割，progress 二值化（整行高亮）：
            // 该设置的语义是"关掉逐字"，不是"关掉高亮"（见 Appearance 的字段说明）。
            // ── ⚠️ 2026-09-19 二次修订：**只有「不逐字（LINE）」档才排除逐字时间轴** ──
            //
            // **原记**（本行上一版）：`karaokeMode == PER_CHAR && hasWordTimingEvidence`
            //   —— 理由是"另外两档必须忽略它，否则整行高亮会被逐字数据拉回逐字"。
            //
            // **实为**：那个理由**只对 `LINE` 档成立**。把它同时套到 `SMOOTH` 档，
            //   后果是 **SMOOTH 永远拿不到 `wordBoundaries`** → `render` 里那段
            //   "平滑档照旧用 `wordBoundaries`"**成了死代码** → 退回"整行进度"
            //   → **用户第二次报的「译文平滑高亮仍然落后于主行」就是这么来的。**
            //
            // **我上一轮修 #7 时，修复本身被这一行挡住了** —— 典型的"改了 A，
            //   但 A 的输入被 B 掐断了，于是 A 看起来没生效"。
            //
            // 判据：`LINE` = 整行同色（**不需要**任何时间轴）；
            //      `SMOOTH` / `PER_CHAR` = 都需要**准确的时序**，都该用逐字时间轴。
            val useWordTiming = slot.isCurrent &&
                local.karaokeMode != KARAOKE_MODE_LINE && line.hasWordTimingEvidence

            // 可观测性：记下**实际**走的哪条时序（不重新推导，只复述上面的判据）。
            timingMainDesc = when {
                !slot.isCurrent -> "非当前行"
                local.karaokeMode == KARAOKE_MODE_LINE -> "整行(LINE 档)"
                line.hasWordTimingEvidence -> "逐字(${line.effectiveWords.size} 字)"
                else -> "整行(无逐字轴)"
            }

            val layout: TextLayout = renderCache.textLayoutFor(text) ?: return
            // ⚠️ 对齐由 `layout.content_align` 决定（原为死设置，2026-09-16 修复）。
            // 走共用助手 —— 译文行用的是同一个，两处不会漂移。
            // `areaLeft` 让左/右对齐在**文字区**内工作（左侧有封面时不至于压上去）。
            val alignedX = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)

            // ── 长歌词水平滚动（用户 2026-09-17 要求）────────────────────────
            //
            // 触发条件：这一行的**排版宽度**超过文字区可用宽度（扣除两侧内边距）。
            // 之所以会真的发生：`layout.fixed_width = false` 时词岛会按歌词长度伸缩，
            // 但**伸缩上限是 `layout.max_width`** —— 超过上限的长句必然溢出。
            // （上限本轮已从 1200 放宽到 2400，那是"少发生"，不是"不发生"。）
            //
            // 溢出量小于 [SCROLL_MIN_OVERFLOW_PX] 时**不滚**：为几像素的截断让整行
            // 来回晃，代价远大于收益（见该常量的注释）。
            val regionLeft = lyricTextRegionLeft(areaLeft, HORIZONTAL_INSET)
            val availWidth = lyricTextRegionWidth(width, areaLeft, HORIZONTAL_INSET)
            val overflow = layout.advance - availWidth
            // ★★★ 2026-10-17（**BUG-225**）：**只有"真的放不下"才滚**。
            //
            // ## 用户报障（逐字）
            // > 「改了最大宽度，并取消自动宽度，但在**高亮到句尾时歌词会移动**，
            // >   就像**宽度不足**一样。」
            //
            // ## 根因：`overflow` 只要超过 8 px 就滚 —— 而自动宽度模式下
            //   `naturalWidthPx` 的更新是**异步**的（`invokeLater` + 迟滞阈值）
            //   ⇒ 换行那一帧窗口还是**上一行的宽度** ⇒ 新行溢出几像素 ⇒ **触发滚动**
            //   ⇒ 滚动是**进度驱动**的 ⇒ 唱到句尾时整行移到最右 ⇒ 用户看到"歌词在动" ✗
            //
            // ★ 判据：**滚动的前提是"窗口已经没法再宽"**，而不是"这一帧宽了几像素"。
            //   · 自动宽度 + 窗口还能变宽 ⇒ 让窗口去变宽，**不滚** ✓
            //   · 顶到 `layout.max_width` ⇒ 真的放不下 ⇒ 才滚 ✓
            //   · 固定宽度 ⇒ `width` 恒 = `maxWidth` ⇒ 与上面同一判据 ✓
            //
            // ⚠️ `maxWidthPxCache` 为 0（`applyGeometry` 还没跑过）⇒ 视为"未顶到上限"，
            //    宁可**不滚**也不要在首帧晃一下。
            val atWidthCap = maxWidthPxCache > 0 && width >= maxWidthPxCache - 1
            val scrolling = overflow > SCROLL_MIN_OVERFLOW_PX && atWidthCap
            // ── ★★ 2026-09-23：滑移由**已唱进度**驱动（用户第 ④ 条）───────────────
            //
            // 用户原话：
            // > 「词岛宽度不足以显示完整主歌词时，主歌词自动滑移时，**尽量保持最低匀速**，
            // >   使**高亮进度保持在歌词显示部分的 1/2 处**。」
            //
            // ⚠️ 驱动量取 [sharedSweepFraction]，**与下面 `karaokeRenderer.render` 的
            //    `progress` 是同一个函数、同一个 `effectiveMs`** —— 各算一份会让
            //    "高亮进度"与"滑移位置"走两条曲线，那条 1/2 处的断言立刻不成立。
            //
            // 非当前行取 `0f`（不滑）：`slotsFor` 当前**恒只输出当前行一个槽位**，
            // 已唱行与未唱行都不在画面上；保留这条分支是为了"非当前行不滑移"
            // 这条语义在将来重新引入多槽时仍然成立。
            val scrollProgress = if (slot.isCurrent) sharedSweepFraction(line, effectiveMs) else 0f
            val baselineX = alignedX + if (scrolling) {
                marqueeOffsetForProgress(layout.advance, scrollProgress, availWidth)
            } else {
                0f
            }
            val baselineY = lyricBaselineY(center, layout.ascent, layout.descent)

            // 记下这一行**实际画在哪** —— 字形命中测试读的就是这份数据（见 drawnRows 的说明）。
            // 放在基线算完之后、真正绘制之前：即便下面的渲染抛异常，几何也是对的；
            // 而"画失败"这件事由调用方的 runCatching 处理（本行不画），
            // 此时命中域里多一行"看不见的字"是可接受的偏差（最多 33 ms 后被下一帧纠正）。
            drawnRows.add(DrawnRow(text, layout, baselineX, baselineY))

            // 滚动时把绘制**裁到文字区**：否则移出左边界的那半句会画到左侧封面的
            // 让位区（`LEADING_GAP`）里，看起来像"字跑到了封面旁边"。
            // 上层只把 clip 设成"词岛形状"，不含文字区约束，故这里要自己收一道。
            //
            // ⚠️ 只在真的滚时才动 clip —— 常态（不溢出）下**零行为变化**。
            val savedClip = if (scrolling) g2.clip else null
            if (scrolling) {
                g2.clip(
                    Rectangle2D.Float(
                        areaLeft + HORIZONTAL_INSET / 2f,
                        0f,
                        availWidth + HORIZONTAL_INSET,
                        height.toFloat(),
                    ),
                )
            }

            if (slot.isCurrent) {
                // ⚠️⚠️ **2026-10-08（U1）修复：必须走 `*For(coverRgb)`，不能用裸值。**
                //
                // **原状**：此处传 `local.sungColor` / `local.unsungColor`（**裸值**），
                //   而同一条链上的另两处传的是**已解析**的值：
                //   · `:9323`（退场行）`local.sungColorFor(coverRgb)`
                //   · `:11133`（非当前行）`local.unsungColorFor(coverRgb)`
                //
                // **后果**（用户原话）：「已唱完的歌词淡出时，**颜色会变**（看样子是封面
                //   取色的颜色），与设置的主歌词颜色不一致。」
                //   ⇒ `main_lyric.color_mode = cover` 时，当前行用裸色、退场行用封面派生色
                //   ⇒ **退场那 ~180 ms 内颜色跳一次**。`manual` 档下 `*For` 原样返回
                //   （`IslandAppearance` 的 manual 分支），故默认档**零行为变化**。
                //
                // ★ **同一语义在三处调用点只改了一处的典型** ⇒ 与 `:9323` / `:11133` 同源。
                val sungPacked = local.sungColorFor(coverRgb).toPackedArgb()
                val unsungPacked = local.unsungColorFor(coverRgb).toPackedArgb()
                // 整行进度。逐字模式会忽略它（渲染器内部如此约定），
                // 但两者都传是刻意的 —— 于是"有没有可信逐字"这一个条件
                // 不会漏掉"忘了传 progress"这类分支。
                //
                // ⚠️ **不再用 `snap.currentLineProgress`**：那个是时间轴层用
                // **未偏移**的位置算好的。用了它就会"逐字已偏移、整行没偏移"，
                // 两者错位（整行已唱满而最后一个字还没亮）。故与 `boundaries`
                // 共用同一个 `effectiveMs` 重算。
                val boundaries: FloatArray? = if (useWordTiming) {
                    // 每个字各自的进度。**不能用整行 progress 线性推算** ——
                    // 各字时长不等，线性推算会让高亮落在错误的字上
                    // （这是 LyricWord 存在的原因）。
                    val words = line.effectiveWords
                    // ⚠️ 用 `effectiveMs`（含 `lyric.offset_ms`），不是 `snap.positionMs` ——
                    // 这个键此前完全没被读过（BUG-025），用户拖滑杆画面零变化。
                    FloatArray(words.size) { words[it].progress(effectiveMs) }
                } else {
                    null
                }
                // 每个字的字符数：渲染器要把「第 k 个字」映射到 `caret(charIndex)`，
                // 必须知道它前面累计了多少 char。**必须精确给**，不能让它均分推断 ——
                // 中英混排（`I do believe 我坚信不疑`）下均分会把分界算到错位置。
                // `effectiveWords` 已保证 cells 拼接精确等于 text，故这里的长度和是自洽的。
                val lengths: IntArray = if (useWordTiming) {
                    line.effectiveWords.map { it.text.length }.toIntArray()
                } else {
                    IntArray(0)
                }
                // ── ★★ 2026-09-28：当前行的**入场变换**（用户报"显现动画没做"）──────
                //
                // ⚠️ 只把 `karaokeRenderer.render` 这一句包进去，**几何计算留在外面** ——
                //    `drawnRows.add(...)` 已在上面按**静止坐标**登记过（见本函数上方），
                //    故入场期间命中域依旧正确（判据与理由见 [drawWithEnterTransform]）。
                //
                // ⚠️ `drawExitingMainLine` **在包外**（见下）—— 它自己有变换层，
                //    若也被包进去就叠了两次位移（旧行会被推得更远）。
                drawWithEnterTransform(
                    g2 = g2,
                    local = local,
                    restingBaselineX = baselineX,
                    restingBaselineY = baselineY,
                    layout = layout,
                    lineHeight = lineHeight,
                ) { target ->
                karaokeRenderer.render(
                    g = target,
                    cache = renderCache,
                    layout = layout,
                    text = text,
                    baselineX = baselineX,
                    baselineY = baselineY,
                    sungColor = sungPacked,
                    unsungColor = unsungPacked,
                    // ⚠️ 与上面 `boundaries` 用**同一个** `effectiveMs` 重算，而不是
                    // `snap.currentLineProgress`（那个是用未偏移的位置在时间轴层算好的）。
                    // 用快照里的值会让"逐字已偏移、整行没偏移"，两者错位。
                    // `progress()` 对空行/零时长行有定义（见 LyricLine.progress），
                    // 且 `line` 在此分支必非 null。
                    //
                    // ── 2026-09-19：关闭逐字时**整行一起变色，不再扫过** ─────────
                    //
                    // 用户原话：「**关闭逐字高亮后实际歌词仍为逐字高亮状态**，
                    // 而不是统一以已唱歌词渲染(主+副)歌词。」
                    //
                    // 根因：这里 `karaoke` 曾**恒传 `true`**，于是即便关掉逐字，
                    // 走的仍是"整行进度"路径 —— `progress` 从 0 连续变到 1，
                    // 视觉上就是**从左到右扫过**，与逐字几乎无法区分。
                    //
                    // 修法：**把 `progress` 二值化** —— 关掉逐字后，
                    // 当前行只要开唱（`progress > 0`）就**整行用已唱色**，
                    // 未开唱则整行未唱色。已唱完的行本就是 1，不受影响。
                    //
                    // 为什么不是改成 `karaoke = false`：那条路径**整行用未唱色**
                    // （见 `KaraokeTextRenderer` 的 `!karaoke` 分支），
                    // 会让"正在唱"的行看起来像"还没唱到"，与设置语义相反。
                    // ── ⚠️ 2026-09-19：回退进度改用 `sharedSweepFraction`（与译文**同一个函数**）
                    //
                    // 主行有逐字轴、译文没有时，两条曲线原本不同 → 同一时刻比例不同。
                    // 抽成一个纯函数后，**两处调用点共用同一个分母** ——
                    // 各写一份就是本项目反复吃亏的"同族两处实现只改一处"。
                    progress = sharedSweepFraction(line, effectiveMs).let {
                        // `LINE` 档：**二值化** —— 开唱即整行已唱色（用户要的"整行高亮"）。
                        // 另两档保持连续进度。
                        if (local.karaokeMode == KARAOKE_MODE_LINE) {
                            if (it > 0f) 1f else 0f
                        } else {
                            it
                        }
                    },
                    karaoke = true,
                    wordBoundaries = boundaries,
                    wordLengths = lengths,
                    // 主歌词描边（`main_lyric.outline.*`）。`null` = 不描边。
                    // 由渲染器在**填充之前**画一遍轮廓，故字形本身不会变细。
                    outline = local.mainOutlineFor(),
                    // 平滑模式（`main_lyric.karaoke.mode`）：整行当一个字扫过去。
                    // 有真实逐字时间轴时渲染器内部优先走时间轴，本参数不生效。
                    smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
                )
                } // ← drawWithEnterTransform：当前行的入场变换到此结束
                // ★ 旧行的退场（画在**新行之后** → 叠在新行之上）。
                //   为什么叠在上面：它正在**离开**，被新行盖住反而像"卡在文字后面"；
                //   而它的不透明度在 180 ms 内归零，遮挡是短暂的、且正是"退场"的观感。
                //   ⚠️ 在入场变换**之外**调用：它自带一层变换（走 `mainExit`），
                //      叠进来会把旧行推得更远（两个位移相加）。
                drawExitingMainLine(g2, local, coverRgb, width, areaLeft, baselineY, lineHeight)
            } else {
                // 非当前行：整行用未唱色。渲染器在 `karaoke = false` 时走最快路径
                // （不设 clip、不算字形簇）。
                // 颜色同样经 `main_lyric.color_mode` 解析 —— 若将来重新引入多槽，
                // 各槽的未唱色也必须与当前行**一致**，否则会出现多种色调。
                val snapped = local.unsungColorFor(coverRgb).toPackedArgb()
                karaokeRenderer.render(
                    g = g2,
                    cache = renderCache,
                    layout = layout,
                    text = text,
                    baselineX = baselineX,
                    baselineY = baselineY,
                    sungColor = snapped,
                    unsungColor = snapped,
                    progress = 0f,
                    karaoke = false,
                    // 主歌词描边（`main_lyric.outline.*`）。`null` = 不描边。
                    // 由渲染器在**填充之前**画一遍轮廓，故字形本身不会变细。
                    outline = local.mainOutlineFor(),
                    // 平滑模式（`main_lyric.karaoke.mode`）：整行当一个字扫过去。
                    // 有真实逐字时间轴时渲染器内部优先走时间轴，本参数不生效。
                    smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
                )
            }
            // 还原裁剪。⚠️ 放在 if/else **之后**而不是各自 `try/finally` 里：
            // 渲染抛异常时本行不会执行，但上层 `paintComponent` 的 finally 会把 clip
            // 整体还原（它保存的是进入时的 clip），故**不会污染后续帧** ——
            // 代价只是"本帧余下的行也被裁"，而那是异常路径，可接受。
            if (savedClip != null) g2.clip = savedClip
        }

        /**
         * 画译文行（当前行的副歌词）。**与原文各自独立高亮。**
         *
         * ## 译文用哪种高亮（两条路，由数据自动分档）
         *
         * 用户的需求是"原文和译文**分别**逐字高亮"。译文能不能逐字，
         * 只取决于**宿主给的 cells 有没有覆盖译文那一段**：
         *
         * | 条件 | 译文走哪条路 |
         * |---|---|
         * | [LyricLine.hasTranslationTimingEvidence] == true | **逐字**：用译文**自己的**逐字时间轴（`wordBoundaries`） |
         * | 否则（真机 `cells=1` 就属这档） | **整行进度**：按字符比例跟随本行进度渐变 |
         *
         * 两条路都**不借用原文的时间轴**：
         * - 按原文时间轴切译文 → 高亮落**在错误的字上**（中文译文与日文原文
         *   字数、语序都不同）。显示错误的信息比不显示更糟。
         * - 用整行进度线性切译文 → 每个字时长不等，同样错位（但至少方向是对的：
         *   它只表达"这一句唱到几成"，不声称"这个字正在唱"）。
         *
         * 故"没有逐字数据"时退到整行进度，**绝不伪造**一套时间轴 ——
         * 与 `LyricLine.effectiveWords` 的 fail-closed 纪律同一条。
         *
         * ## 与 0.8.9 的关系
         *
         * 原实现 0.8.9 给译文传 `words = emptyList()`、`karaoke = false`（整行静态）。
         * 本项目**有意前进一档**：静态译文看不出"唱到哪了"，用户明确提过这一点。
         * 但原实现"不拿原文时间轴切译文"这条纪律被完整保留。
         *
         * ## 字号与间距
         *
         * 字号 = 主字号 × [TRANSLATION_SCALE]（0.7，对齐 0.8.9 的 `fontSize * .7f`），
         * 颜色 = [Appearance.translationColor]，未唱部分按 [UNSUNG_TRANSLATION_DIM] 暗化。
         * 居中对齐与主行同一套算法（用 `advance` 而非 `bounds`，见 [drawOneLine]）。
         *
         * @param line 当前行。译文的逐字时间轴与整行进度都从它取 ——
         *   调用点已保证 `slot.isCurrent`，故 `line === snap.currentLine`。
         * @param center 译文行**垂直中心**（不是基线）——
         *   与主行同一套居中语义，便于调用方直接按"行的中心"传坐标。
         * @param effectiveMs **已含 `lyric.offset_ms` 的播放位置**（与 [drawOneLine] 同一个值）——
         *   两处若各算一份，会出现"主行已偏移、译文没偏移"的错位。
         */
        /**
         * **副歌词字体**的**唯一构造点** —— 绘制侧与**岛宽测量侧**共用。
         *
         * ## 为什么必须收成一处（2026-10-18）
         *
         * 副歌词的字体由**三个设置**共同决定：
         * `main_lyric.font.family` · `main_lyric.font.size × layout.translation.scale_percent`
         * · 字距（`translation.letter_spacing.follow_main` 为真时**按比例**跟随正文）。
         *
         * 而**岛宽测量**（`drawLyricSlots` 的自然宽度那段）自2026-10-18 起
         * **也必须用这套字体去量译文** —— 否则译文比正文宽时岛宽装不下它 ⇒ 恒左移。
         *
         * ⇒ 两个消费点一旦各写一份 `fontWithLetterSpacing(...)`，
         *   任何"只改一处"（例如把字距跟随的算法调一下）都会让
         *   **量出来的宽度**与**画出来的宽度**悄悄对不上 ——
         *   而症状是"明明词岛够宽，译文却还在左移"，**极难归因**
         *   （看起来像渲染 bug，实际是测量口径漂了）。
         *   这正是本项目反复吃亏的「同一条规则写 N 遍、漏一处」形态。
         *
         * @return 副歌词字体；字号算出来 `<= 0` 时返回 `null`
         *   （`TextLayout` 会抛，故此处不构造 —— 调用方按"没有副歌词"处理）。
         */
        private fun translationFontFor(local: Appearance): Font? {
            // `translation.letter_spacing.follow_main = true`（默认）时**复用主歌词的
            // tracking** —— 注意是**比例**不是像素，于是副歌词的像素字距自动等于
            // `主字距 × (副字号 / 主字号)`（推导见 [Appearance.mainLetterTracking]）。
            // `false` → `tracking = 0`（不加字距，保持原样）。
            val subTracking = if (local.translationLetterSpacingFollowMain) {
                local.mainLetterTracking()
            } else {
                0f
            }
            return fontWithLetterSpacing(
                local.fontFamily,
                (local.fontSize * local.translationScale).toInt(),
                subTracking,
            ).takeIf { it.size > 0 }
        }

        private fun drawTranslationLine(
            g2: Graphics2D,
            local: Appearance,
            line: LyricLine,
            translation: String,
            width: Int,
            areaLeft: Float,
            center: Float,
            effectiveMs: Long,
            /**
             * 封面主色（`null` = 取不到）。
             *
             * 2026-09-19 新增：译文色有了 `main_lyric.translation.color_mode`
             * （用户报「歌词颜色跟随封面取色失效」），解析它需要这个值 ——
             * 而它由调用方（`paintComponent`）从 `coverImageFor` 拿到，
             * 本函数自己取不到。
             */
            coverRgb: Int?,
        ) {
            // ── 副歌词字间距（2026-09-19）────────────────────────────────────
            //
            // `translation.letter_spacing.follow_main = true`（默认）时**复用主歌词的
            // tracking** —— 注意是**比例**不是像素，于是副歌词的像素字距自动等于
            // `主字距 × (副字号 / 主字号)`，即用户要的"**按比例联动**"
            // （推导见 [Appearance.mainLetterTracking] 的 KDoc）。
            // `false` → `tracking = 0`（不加字距，保持原样）。
            //
            // ⚠️ 与主歌词共用 `renderCache` 的缓存**无关**：这里传的是 `subFont`，
            //   与 `cache.font` 不同 → `textLayoutFor` 走一次性构造、不进缓存
            //   （见 `RenderCache.textLayoutFor` 的分支），故不会污染主歌词的缓存表。
            //
            // ★★ 2026-10-18：改为调[translationFontFor] —— 岛宽测量那侧也要用**同一个**字体，
            //   两处各写一份 `fontWithLetterSpacing(...)` 就是本项目反复吃亏的
            //   「同一条规则写 N 遍、漏一处」：字距跟随的算法一旦只改一处，
            //   量出来的宽度与画出来的宽度就对不上，症状是"明明够宽却还在左移"。
            val subFont = translationFontFor(local) ?: return
            val layout = renderCache.textLayoutFor(translation, subFont) ?: return
            // ⚠️ 与正文行**共用同一个助手**（`local.contentAlign`）——
            // 两处各算一份的话，"改一处漏一处"的表现是译文相对正文偏移
            // （正文左对齐、译文还在中间），而那看起来像"译文没跟着设置走"。
            // `areaLeft` 同样必须传：左侧显示封面时，译文要与正文一起让出那段宽度。
            val alignedX = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)
            // ── 长译文水平滚动（2026-09-21）──────────────────────────────────
            //
            // 用户 2026-09-17 的原话是「当框长不足以显示全部歌词时歌词应能**自行滚动**」——
            // **译文也是歌词**。而本函数此前**完全没有溢出处理**：过长的译文被直接截断，
            // 后半句一个字都读不到。那不是"有意不做"，是**没做全**。
            //
            // ⚠️ 2026-09-23：与正文行**共用同一个滑移函数**（[marqueeOffsetForProgress]）——
            // 但**驱动量是译文自己的进度**（`sharedSweepFraction(line, effectiveMs)`，
            // 与下面译文高亮用的是同一个值）。
            //
            // 为什么不是"与正文共用同一个偏移"：译文与正文的字数、语序都不同
            // （见本函数 KDoc 的论证），共用一个偏移会让"译文的高亮位置"落在
            // 错误的字上 —— 而那条 1/2 处的断言是**逐行**成立的。
            val regionLeft = lyricTextRegionLeft(areaLeft, HORIZONTAL_INSET)
            val availWidth = lyricTextRegionWidth(width, areaLeft, HORIZONTAL_INSET)
            val overflow = layout.advance - availWidth
            // ★★★ 2026-10-18（**用户报障**）：译文也必须**"放不下才滑"**。
            //
            // ## 用户报障（逐字）
            // > 「副歌词高亮时左移的幅度过大（明明词岛宽度足够显示，副歌词/译文还是左移）」
            //
            // ## 根因：**这一段漏了 BUG-225 的 `atWidthCap` 判据**
            //
            // 2026-10-17 修 BUG-225 时，正文行那段加了"窗口已经没法再宽才滑"的门
            // （见本文件 `drawOneLine` 的 `atWidthCap`），**而本段没有** ——
            // 于是同一个缺陷在译文上原样复发：自动宽度模式下 `naturalWidthPx` 的更新
            // 是**异步**的（`invokeLater` + 迟滞阈值），换行那一帧窗口还是**上一行的宽度**
            // ⇒ 译文溢出几像素 ⇒ **触发左移**；而滑移是**进度驱动**的
            // ⇒ 唱到句尾时译文已移到最左 ⇒ 用户看到"译文平白往左跑"。
            //
            // ★ 与正文行的差异只有这一处 ⇒ **判据必须共用**，否则"改一处漏一处"复发。
            //   （这正是本项目反复吃亏的「同族两处实现」形态。）
            val atWidthCap = maxWidthPxCache > 0 && width >= maxWidthPxCache - 1
            val baselineX = alignedX + if (overflow > SCROLL_MIN_OVERFLOW_PX && atWidthCap) {
                marqueeOffsetForProgress(
                    layout.advance,
                    sharedSweepFraction(line, effectiveMs),
                    availWidth,
                )
            } else {
                0f
            }
            val baselineY = lyricBaselineY(center, layout.ascent, layout.descent)

            // ── 登记进字形命中域（2026-09-18 改为登记）───────────────────────
            //
            // 用户本轮原话：「完善鼠标**字形命中**，使对**曲名、作者**等非歌词也能命中
            // （**方便拖动**）」。
            //
            // ## 原记 vs 实为
            //
            // 原记：译文行**有意不登记** —— 「点击它是想操作词岛，而词岛的所有交互都以
            //   '主歌词字形'为触发点」。
            //
            // 实为：那条理由与本项目的**交互模型**不符。`REGIONAL_CLICK_THROUGH = true`
            //   （见其 KDoc），窗口只在"命中字形或播放控件"处**不穿透** ——
            //   于是"不登记"的真实后果不是"译文不可点"，而是**译文那一横条是穿透空洞**：
            //   鼠标移上去点击会落到下层应用上、且**拖不动词岛**（拖动要求窗口先收到按下事件）。
            //   这正好是用户本轮抱怨的"不方便拖动"。
            //   而用户本轮明确要求非歌词文本也能作为交互触发点，等于**推翻了原记的前提**。
            //
            // 放在基线算完之后、真正绘制之前：与 [drawOneLine] 同一位置 —— 即便下面的
            // 渲染抛异常，几何也是对的；"画失败"由调用方的 runCatching 处理，
            // 命中域里多一行看不见的字最多 33 ms 后被下一帧纠正。
            //
            // ⚠️ 已知副作用（**用户要的**）：鼠标停在译文上会展开播放控件。
            //   若日后用户觉得"译文不该弹控件"，把这一行注释掉即可 ——
            //   代价是那一横条重新变成穿透空洞。
            drawnRows.add(DrawnRow(translation, layout, baselineX, baselineY))
            //
            // ── 逐字分档（2026-09-17 新增）───────────────────────────────
            // 与 `drawOneLine` 完全对称：开关关掉时**不传逐字**但保留整行进度，
            // 因为 `karaoke.mode = LINE` 的语义是"不逐字（整行高亮）"，不是"关掉高亮"。
            // 与主歌词同一条判据（只排除 LINE 档）—— 译文同样需要**准确的时序**，
            // 否则"译文落后主行"会原样复现（用户报过两次）。
            val useWordTiming = local.karaokeMode != KARAOKE_MODE_LINE &&
                line.hasTranslationTimingEvidence

            // 可观测性：与主歌词同一套记法。**"为什么没有逐字"要分开报** ——
            // `translationWords` 为空（宿主没给译文 cells / 收窄失败）与
            // `LINE` 档（用户选择不逐字）是**完全不同的两件事**，混成一句就没法排查。
            timingSubDesc = when {
                local.karaokeMode == KARAOKE_MODE_LINE -> "整行(LINE 档)"
                line.hasTranslationTimingEvidence ->
                    "逐字(${line.effectiveTranslationWords.size} 字)"
                line.effectiveTranslationWords.isEmpty() -> "整行(译文无逐字轴)"
                else -> "整行(译文逐字无亚行证据)"
            }
            val words = if (useWordTiming) line.effectiveTranslationWords else emptyList()
            // 每个字各自的进度与字符数。字符数**必须精确给**（不能均分推断）——
            // 中英混排下均分会把 `caret` 插值算到错位置，理由同 `drawOneLine`。
            val boundaries: FloatArray? = if (words.isEmpty()) {
                null
            } else {
                FloatArray(words.size) { words[it].progress(effectiveMs) }
            }
            val lengths: IntArray = if (words.isEmpty()) {
                IntArray(0)
            } else {
                words.map { it.text.length }.toIntArray()
            }
            // 已唱色 = 设置里的译文色；未唱色 = 它按固定系数暗化（不新增设置项，
            // 理由见 [UNSUNG_TRANSLATION_DIM]）。
            // ⚠️ 必须走 [Appearance.translationColorFor]（而不是直接读 `translationColor`）——
            //    `cover` 档就在那个函数里解析（2026-09-19 修"歌词颜色跟随封面取色失效"）。
            val sung = local.translationColorFor(coverRgb).toPackedArgb()
            val unsung = dimPackedArgb(sung, UNSUNG_TRANSLATION_DIM)
            // ── ★★ 2026-09-28：译文的**入场变换**（用户原话：「（副歌词也是）」）────────
            //
            // 与正文行**同一个变换层、同一个 `mainEnter`** —— 故译文与正文一起落位，
            // 不会出现"正文滑上来、译文已经站好"的错位（那正是用户抱怨的"割裂"）。
            //
            // ⚠️ 缩放中心用的是**译文自己的** `layout.advance`（而不是正文的）——
            //    译文与正文字数/字号都不同，用正文的宽度算中心会让 POP 档下译文偏心。
            //
            // ⚠️ 与正文行同一条纪律：`drawnRows.add(...)` 已按**静止坐标**登记（见上），
            //    这里只变换画布。
            drawWithEnterTransform(
                g2 = g2,
                local = local,
                restingBaselineX = baselineX,
                restingBaselineY = baselineY,
                layout = layout,
                lineHeight = local.fontSize * local.translationScale * LINE_HEIGHT_RATIO,
            ) { target ->
            karaokeRenderer.render(
                g = target,
                cache = renderCache,
                layout = layout,
                text = translation,
                baselineX = baselineX,
                baselineY = baselineY,
                sungColor = sung,
                unsungColor = unsung,
                // 有逐字时间轴时渲染器会忽略它（内部约定），但两者都传是刻意的 ——
                // 于是"有没有可信逐字"这一个条件不会漏掉"忘了传 progress"这类分支。
                //
                // ── 2026-09-19：与主歌词同一条修法（见 `drawOneLine` 的说明）──
                // 关掉逐字后 `progress` **二值化**，整行一起变色而不是从左到右扫过。
                // 用户原话明确要求**主 + 副**都这样：「而不是统一以已唱歌词渲染(主+副)歌词」。
                // ── ⚠️ 2026-09-19：回退进度改用 `sharedSweepFraction`（与主歌词**同一个函数**）
                //
                // 用户原话：「主副歌词高亮**同一时刻两行推进到同一比例**并没有实现
                //   （平滑高亮设置下）。」
                //
                // 原实现这里用 `line.progress(effectiveMs)`（整行线性），而**主行在有逐字轴时
                // 走的是逐字时间轴** —— 两条曲线不同 → 同一时刻比例不同。
                // `sharedSweepFraction` 返回"主行边界在字符空间的占比"，
                // 于是两行的回退进度**由构造保证同源**，不再靠巧合。
                progress = sharedSweepFraction(line, effectiveMs).let {
                    // `LINE` 档：**二值化** —— 开唱即整行已唱色（与主歌词同一条判据）。
                    // 用户原话明确要求**主 + 副**都这样：「而不是统一以已唱歌词渲染(主+副)歌词」。
                    if (local.karaokeMode == KARAOKE_MODE_LINE) {
                        if (it > 0f) 1f else 0f
                    } else {
                        it
                    }
                },
                karaoke = true,
                wordBoundaries = boundaries,
                wordLengths = lengths,
                // 羽化宽度必须按**译文自己的**字号算（`subFont`），不能沿用主字号 ——
                // 否则译文那行小字上会糊开一片比原文宽出四成的过渡带。
                featherFontSize = subFont.size2D,
                // 副歌词描边（`translation.outline.*`）。`null` = 不描边。
                // `translation.outline.sync_with_main = true` 时整组跟随主歌词
                // （含"主歌词没开"→ 副歌词也不描边）—— 解析在 [Appearance.translationOutlineFor]。
                outline = local.translationOutlineFor(),
                // 平滑模式（`main_lyric.karaoke.mode`）：整行当一个字扫过去。
                // 有真实逐字时间轴时渲染器内部优先走时间轴，本参数不生效。
                smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
            )
            } // ← drawWithEnterTransform：译文的入场变换到此结束
        }

        // ══ 2026-10-10（B-3）：原声明已删，保留理由（普通注释，非 KDoc）══
        // ★★ 2026-10-10（审计 v4 · **B-3**）：原 `private fun dimPacked` **已删** ——
        // 它与 `IslandPreviewPane.dimPacked` 是**逐字相同的两份**（纪律 73 点名要拦的形态）✗
        // ⇒ ★ 收敛到公开纯函数 `dev.cdl.settings.dimPackedArgb`（两侧都调）✓
        // ★ 语义（**不动 alpha 位**）见该函数的 KDoc。
        // ══ 说明结束 ══

        /**
         * 画「无歌词」时的占位文本（`layout.noLyrics.mode = PLACEHOLDER`）。
         *
         * ## 为什么用"未唱色"而不是正文色
         *
         * 这是**提示**不是歌词。用正文色会让用户以为"这就是这首歌的歌词"，
         * 而实际上它只是"暂时没有歌词"的占位。用未唱色（比正文暗一档）
         * 语义上正好 —— 它和"还没唱到的那句"是同一类"非高亮文本"。
         *
         * ## 登记进 `drawnRows`（字形命中域）—— 2026-09-18 改为登记
         *
         * 用户本轮原话：「完善鼠标**字形命中**，使对**曲名、作者**等非歌词也能命中
         * （**方便拖动**）」。
         *
         * 这一行**就是**用户说的"曲名"：`layout.noLyrics.mode = PLACEHOLDER` 且
         * `layout.noLyrics.showTrackTitle = true` 时，它是**本插件唯一**会画曲名的地方
         * （全文件只有 [drawOneLine] / [drawTranslationLine] / 本函数三处绘制文本）。
         *
         * 原记「占位文本不该是交互触发点」已被用户本轮诉求推翻 —— 理由与
         * [drawTranslationLine] 那段完全同构（`REGIONAL_CLICK_THROUGH = true` 下
         * "不登记"等于**穿透空洞**：点不中、也拖不动），此处不重复。
         *
         * ⚠️ **作者（artist）没有登记点**：本插件**从不绘制** `snap.artist`
         *   （原实现 0.8.9 的 `IslandPanel.kt:108` 会在展开时画一行
         *   `曲名 · 作者`，本移植版没有实现它）。故"作者"这一半**无绘制即无命中** ——
         *   要做到得先有那行文本，那是**新增功能**而不是本缺陷的修法，已如实上报。
         *
         * ## 为什么是"整行静态绘制"
         *
         * `karaoke = false` 是最快路径（不设 clip、不算字形簇），
         * 而占位文本本来就没有时间轴，逐字毫无意义。
         */
        private fun drawNoLyricsNotice(
            g2: Graphics2D,
            local: Appearance,
            width: Int,
            areaLeft: Float,
            areaTop: Float,
            areaHeight: Float,
            snap: TimelineSnapshot,
            coverRgb: Int?,
            /**
             * `true` = **不画占位句**（但仍画「曲名 · 作者」）。
             *
             * 来自 `layout.noLyrics.hideWhenInstrumental` —— 见注册表该键的 KDoc
             * （用户纠正过它的语义：要隐藏的是**占位句**，不是曲名/作者）。
             */
            suppressPlaceholder: Boolean = false,
            /**
             * ★ 2026-09-30（封面态 S3）：`true` = **本函数按"封面态"语义跑** ——
             * 即无条件画「曲名 · 作者（· 专辑）」，**不画 `NO_LYRICS_PLACEHOLDER`**。
             *
             * ## 为什么需要它
             *
             * 封面态的内容就是「封面 + 曲名/作者」（`recon/67` §9-G 定案：**一行**）。
             * 那里**没有"有没有歌词"这个概念** —— 无论本曲有没有歌词，
             * 曲名/作者都该显示。而本函数原有的语义是"**无歌词时**的提示"，
             * 它会在无曲名时退回占位句（「纯音乐，请欣赏」）—— 那是错的。
             *
             * ⚠️ 为什么**不另写一个绘制函数**：那会把「曲名 · 作者」的**排版**
             * （字体、`ellipsizeToWidth` 截断、`alignBaselineX` 居中、命中登记）
             * 复制一份 —— 正是 S2 抽 `trackCreditLine` 要避免的那类漂移，
             * 只是漂移的从"文本"变成"排版"。⇒ 复用同一处绘制，**用参数区分语义**。
             *
             * ⚠️ 与 [suppressPlaceholder] 的关系：两者**不同**。前者是"别画占位句"，
             * 本参数是"**这一整行**是常态内容、不是提示"。本态传 `true` 时
             * [suppressPlaceholder] 的判定**不再参与**（本态压根不产出占位句）。
             */
            forceShowCredits: Boolean = false,
        ) {
            // 曲名优先（`layout.noLyrics.showTrackTitle`），空曲名时退回中性占位 ——
            // 曲名可能为空（宿主还没报曲目），此时画一个空串等于什么都没画。
            //
            // ── 2026-09-18：补上「作者」（移植遗漏）────────────────────────
            //
            // 用户诉求：「完善鼠标字形命中，使对**曲名、作者**等非歌词也能命中」。
            // 但"作者"此前**根本没有绘制点**（`snap.artist` 全文件只出现在注释里），
            // 而**无绘制即无命中** —— 光加命中登记是够不着的。
            //
            // 原实现 0.8.9 的行为（`089:ui/IslandPanel.kt:108`）：展开时画「**曲名 · 作者**」。
            // 本移植版漏了这一行，故在此补齐 —— 于是命中登记**自然覆盖两者**，
            // 不需要为"作者"单开一处登记。
            //
            // 分隔符用 `·`（与原实现一致）。作者为空时**不画那个点** ——
            // 否则会出现「曲名 · 」这种尾巴，看起来像 bug。
            //
            // ★ 2026-09-30（封面态 S2）：**这条规则已搬进 [trackCreditLine] 的契约** ——
            //   本节不再自己 `trim` 作者/专辑，改由那一处统一处理（唯一出口，防漂移）。
            //   ⚠️ `title` **仍留在本节**：`hasTitle` 与下方 `titleInTranslation` 分支都要用它。
            val title = snap.trackTitle.trim()
            // ★ 2026-09-30（封面态 S3）：`forceShowCredits` 下**不看曲名是否为空** ——
            //   本态只画"曲名 · 作者"这一行，没有"无歌词提示"的语义。
            //   ⚠️ `local.showTrackTitle` **仍然尊重**：那是用户对"要不要显示曲名"
            //     的显式设置（`layout.noLyrics.showTrackTitle`），与本态无关，
            //     不该被本态绕过（绕过 = 用户关了它还显示 = 静默失效，纪律 12 反面）。
            val hasTitle = local.showTrackTitle && (title.isNotEmpty() || forceShowCredits)
            // ── `suppressPlaceholder` 的落点（2026-09-19）────────────────────────
            //
            // 用户纠正过语义：「真正需要隐藏的是**占位句**（「纯音乐，请欣赏」），
            //   而不是**连歌名/作者都不显示**。」
            // 故这里**只**在"本来要画占位句"时跳过 —— 有曲名时照画「曲名 · 作者」。
            //
            // ★ 2026-09-30（封面态 S3）：`forceShowCredits` 下**本函数不产出占位句**
            //   ⇒ 这条早退**不参与**（否则本态会被一个与它无关的键挡掉整行）。
            if (!forceShowCredits && suppressPlaceholder && !hasTitle) return
            // ★ 2026-09-27：专辑（`layout.noLyrics.showAlbum`，默认 false）。
            //
            // 顺序 = **曲名 · 作者 · 专辑** —— 与原实现 0.8.9 的「曲名 · 作者」一致，
            // 专辑**追加在尾**（它是三段里最不重要、也最长的一段）。
            //
            // ⚠️ 每段都**只在非空时才加分隔符**：否则会出现「曲名 · 」或「曲名 · 作者 · 」
            //    这种尾巴（本函数原有的注释已就"作者为空时不画那个点"写过同一条理由）。
            //
            // ⚠️ 专辑名通常很长（如「某动画 OST 集 Vol.3」）⇒ 本行**会被 `ellipsizeToWidth` 截断**
            //    （见下方），故默认 `showAlbum = false`：不让"开箱即见曲名与作者"被专辑挤掉。
            val text = if (forceShowCredits) {
                // ★ 封面态 S3：永远画 credits（曲名可能为空 —— 那时 `trackCreditLine`
                //   返回 `""`，由下方 `ellipsizeToWidth`/`textLayoutFor` 走空串路径、
                //   不画任何字。**不退回占位句** —— 本态没有"提示"语义）。
                trackCreditLine(snap, local.showAlbum)
            } else if (hasTitle) {
                trackCreditLine(snap, local.showAlbum)
            } else {
                NO_LYRICS_PLACEHOLDER
            }
            // ⚠️ 这里**刻意不用缓存字体**（`renderCache.textLayoutFor(text)` 的默认重载）——
            //   缓存字体带 `main_lyric.letter_spacing_tenth_px` 的字距（见 [fontWithLetterSpacing]），
            //   而本行画的是**曲名/作者/占位**，不是歌词。字距设置的语义是
            //   "主副歌词的字间距"（注册表该项 title/summary 逐字如此），
            //   把它套到曲名上会让"改歌词字距"顺带改变一个与歌词无关的元素。
            //   故显式传一个 **tracking = 0** 的字体（与改动前**逐位相同**）。
            //   代价：这一次布局不进缓存（`subFont !== cache.font` 走一次性构造）——
            //   但本函数只在"没有歌词"时跑，不是热路径。
            val noticeFont = fontWithLetterSpacing(local.fontFamily, local.fontSize, 0f)
            // ── 溢出时截断 + 省略号（2026-09-21）────────────────────────────
            //
            // 本函数此前**完全没有溢出处理** —— 同族三处绘制文本里的**最后一处**
            // （另两处：`drawOneLine` 有滚动、`drawTranslationLine` 2026-09-21 补滚动）。
            //
            // ⚠️ 它用的是 [alignBaselineX]：**居中对齐时两端一起被裁**，
            //    开头和结尾都看不到 —— 比单侧裁剪更糟。
            //
            // 这里**不滚动**：滚动是给"跟着读"的歌词用的，而本行是**静态提示**
            // （本函数 KDoc 原文：「这是**提示**不是歌词」）。省略号能"说出"被截断，
            // 而裁剪什么都不说。判据与理由见 [ellipsizeToWidth]。
            val regionLeft = lyricTextRegionLeft(areaLeft, HORIZONTAL_INSET)
            val availWidth = lyricTextRegionWidth(width, areaLeft, HORIZONTAL_INSET)
            val shown = ellipsizeToWidth(text, availWidth) { candidate ->
                renderCache.textLayoutFor(candidate, noticeFont)?.advance ?: 0f
            }
            val layout = renderCache.textLayoutFor(shown, noticeFont) ?: return
            val baselineX = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)
            val centerY = areaTop + areaHeight / 2f
            val baselineY = lyricBaselineY(centerY, layout.ascent, layout.descent)
            // 与 [drawOneLine] / [drawTranslationLine] 同一位置（基线算完、绘制之前）——
            // 三处必须一致，否则"某一行画得出来却点不中"会只在其中一个槽位上复现。
            // ⚠️ 登记的是**实际画出来的**文本（`shown`）而不是原始 `text` ——
            //    否则命中域比画出来的字宽，"点空白处也能命中"。
            drawnRows.add(DrawnRow(shown, layout, baselineX, baselineY))
            // ── `layout.noLyrics.titleInTranslation`：**额外**在第二行画曲名 ──────
            //
            // 用户原话：「**增加此类纯音乐允许在译文行显示歌名的开关设置。**」
            // 位置与 [drawTranslationLine] **同源**（调同一个 `translationCenterOffsetPx`），
            // 字号用译文比例 —— 于是"主行干净、曲名在下方"这个布局成立。
            // ★ 2026-10-03 更正：本行原写「当前行下沿 + `TRANSLATION_GAP`」——
            //   那是**旧语义**（副行中心 = 主行下沿 + GAP），已随本次修复改为
            //   「主行盒底 + GAP + **副行行高/2**」。见 `TRANSLATION_GAP_PX` 的 KDoc。
            //
            // ⚠️ **曲名同样要截断**：它比占位提示更容易超宽（真实曲名可以很长），
            //    而这里此前与主行一样是裸的 `alignBaselineX`。
            if (local.titleInTranslation && title.isNotEmpty()) {
                val subFont = fontWithLetterSpacing(
                    local.fontFamily, (local.fontSize * local.translationScale).toInt(), 0f,
                    local.fontBold,
                )
                val shownTitle = ellipsizeToWidth(title, availWidth) { candidate ->
                    renderCache.textLayoutFor(candidate, subFont)?.advance ?: 0f
                }
                val subLayout = renderCache.textLayoutFor(shownTitle, subFont)
                if (subLayout != null) {
                    // ★★ 2026-10-03：**这是副行落点的第二个实现点**（此前与 `drawLyricSlots`
                    //   各写一份，且 `lineHeight` 的定义**都不一样** —— 这里含 `lineGapPx`）。
                    //   ⇒ 已收敛到 `translationCenterOffsetPx`（**唯一实现点**）。
                    //   ⚠️ 行为变化：① 补上 `+ 副行行高/2`（与主路径同一条修复）
                    //              ② `lineGapPx` **保留**（2026-10-03 第二次修正：它是「一二行歌词间距」，
                    //                 不是"多行模式的行间距" —— 我第一版剔掉它是错的）
                    val subCenter = centerY + translationCenterOffsetPx(
                        fontSize = local.fontSize,
                        translationScale = local.translationScale,
                        above = false,
                        lineGapPx = local.lineGapPx,
                    )
                    val subBaselineX = alignBaselineX(
                        width, subLayout.advance, local.contentAlign, areaLeft,
                    )
                    val subBaselineY = lyricBaselineY(subCenter, subLayout.ascent, subLayout.descent)
                    // 与主行同一位置登记命中（三处一致：基线算完、绘制之前）。
                    drawnRows.add(DrawnRow(shownTitle, subLayout, subBaselineX, subBaselineY))
                    val subPacked = local.translationColorFor(coverRgb).toPackedArgb()
                    karaokeRenderer.render(
                        g = g2, cache = renderCache, layout = subLayout, text = shownTitle,
                        baselineX = subBaselineX, baselineY = subBaselineY,
                        sungColor = subPacked, unsungColor = subPacked,
                        progress = 0f, karaoke = false,
                        outline = local.translationOutlineFor(),
                    )
                }
            }

            // 占位文本用**未唱色**（同样经 `main_lyric.color_mode` 解析）——
            // 与"没有歌词"这一状态的语义一致：它不是高亮内容。
            val packed = local.unsungColorFor(coverRgb).toPackedArgb()
            karaokeRenderer.render(
                g = g2,
                cache = renderCache,
                layout = layout,
                // ⚠️ 画的是**截断后**的文本（`shown`），不是原始 `text` ——
                //    与上面 `DrawnRow(shown, …)` 的命中域同源（画什么就登记什么）。
                text = shown,
                baselineX = baselineX,
                baselineY = baselineY,
                // 两端同色 + `progress = 0f` + `karaoke = false` = 整行单色静态绘制。
                sungColor = packed,
                unsungColor = packed,
                progress = 0f,
                karaoke = false,
                // 副歌词描边（`translation.outline.*`）。`null` = 不描边。
                // `translation.outline.sync_with_main = true` 时整组跟随主歌词
                // （含"主歌词没开"→ 副歌词也不描边）—— 解析在 [Appearance.translationOutlineFor]。
                outline = local.translationOutlineFor(),
                // 平滑模式（`main_lyric.karaoke.mode`）：整行当一个字扫过去。
                // 有真实逐字时间轴时渲染器内部优先走时间轴，本参数不生效。
                smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
            )
        }
    }

    // ── 常量 ─────────────────────────────────────────────────────────────

    private companion object {
        /**
         * 行高与字号的比例。
         *
         * ⚠️⚠️ **2026-09-20：值从 `1.6` 改为 `1.36`**（用户拍板 + 实测取证）。
         *
         * 用户原话：「**歌词行间距最低值从两行文字接触线起算。**」
         *
         * 原记（本 KDoc 原文）：「取 1.6 而不是 1.0：三槽位是上下相邻的三行，行高等于
         *   字号时上行与下行的字形会在视觉上挤在一起……1.6 是中文歌词排版的常见行距
         *   （约 1.5–1.8 之间），能让三行既紧凑又可区分。」
         *
         * **实为：1.6 并不是「两行墨迹接触」的比率，它偏大。** 实测（`FontMetrics`，
         *   本机实际取到的字体族 = **MiSans**）：
         *
         *   | 字号 | ascent | descent | (ascent+descent)/字号 |
         *   | --- | --- | --- | --- |
         *   | 22 | 23 | 7 | **1.364** |
         *   | 40 | 42 | 12 | **1.350** |
         *
         *   即两行**墨迹真正接触**需要的比率约 **1.36**；用 1.6 时每行多出
         *   `0.236 × 字号` 的空白 —— 22 号下 **5.2 px**、40 号下 **10 px**。
         *   所以「行间距 = 0」时两行之间**仍有约 1/4 字号的空隙**，
         *   用户说的「最低值不是从接触线起算」指的就是这个。
         *
         *   取 1.36（而不是精确的 1.350）：偏大一丁点更安全 —— 宁可多半个像素，
         *   也不要因为整数取整让某一行**贴死**（`ascent`/`descent` 是字体的**声明**
         *   度量，不同字号下取整方向不同，实测就在 1.350~1.364 之间浮动）。
         *
         * ⚠️ **这是一次默认观感的改动**（岛会变矮一档）。用户明确要求，故照做。
         *
         * ⚠️ **这个常量被两处共用**：窗口几何（[islandHeightFor]）与绘制布局
         * （[IslandPanel.drawLyricSlots]）。两者必须一致，否则背景形状按一个高度画、
         * 文字按另一个高度排，结果是"文字挤出背景"。故只在这里定义一次。
         */
        // ⚠️ 2026-09-20：**常量本身已移到 （顶层 ）** ——
        //    因为  是 ，探针看不到它，那条「行高常数 ==
        //    真实墨迹比率」的自校验护栏就写不出来。上面那段 KDoc 保留作为取值取证；
        //    **唯一定义点在 **（同包，无需 import）。

        /**
         * **单行**档的有效行数（= `LineCount.SINGLE.rows`）。
         *
         * ⚠️ **2026-09-24 更正：当前没有任何代码引用它。** 原文写「它同时被
         *   [effectiveRows]（几何）与 [IslandPanel.slotsFor]（绘制）的语义指涉」—— 实测
         *   **两个函数都不用它**（[effectiveRows] 走 `LineCount.rows`，[slotsFor] 恒返回单槽）。
         *   保留它是为了让"单行 = 1 行"这条约定在 grep 时**有个具名落点**（`LineCount.SINGLE.rows` 也是 1，两者语义相同）。
         *
         * ⚠️ 2026-09-18：原文写「多行总闸（`island.experimental_multi_line`）关闭时的
         *   有效行数」—— 该总闸已删除，本常量的语义随之改为"单行档的行数"。
         */
        const val SINGLE_ROW = 1

        //
         // `spectrum.secondary.mode` 的取值。
         //
         // ⚠️ **2026-09-22 已搬到 `IslandPolicy.kt`（公开顶层 `SPECTRUM_SECONDARY_MODE_*`）** ——
         // 副频谱的锚点抽成公开纯函数 [secondarySpectrumBands] 之后，探针要**按真值**
         // 枚举四个档位；留一份私有副本会让"探针枚举的档位"与"生产判断的档位"
         // 有两个真相源（本项目在"抄一份常量"上吃过亏：抄来的常量会漂移）。
         //

        /**
         * 副频谱带的高度 = 主频谱带高度 × 本比例。
         *
         * 取 0.5：副频谱是**背景层**，占主频谱一半高就不会喧宾夺主，
         * 又足以看清"它在跳"。**不单独做成设置项**是刻意的 ——
         * 用户只要求"透明度上做出差异"，高度再开一项会让这一组设置失控
         * （已有 4 个频谱尺寸/位置项）。
         */
        const val SECONDARY_SPECTRUM_HEIGHT_SCALE = 0.5f

        /**
         * ★★ `main_lyric.karaoke.mode` 的取值 —— **2026-10-10（审计 v4 · B-2）已迁到
         * `dev.cdl.settings`**（原在这里的 `private companion object` ⇒ 预览取不到）。
         * ★ 本文件通过**顶层 import** 引用它们（`KARAOKE_MODE_LINE` / `_SMOOTH` / `_PER_CHAR`）✓
         */
        //
         // 译文行相对主行的字号比例。
         //
         // 为什么是 0.7 而不是别处常见的小字号常量：这是原实现 Dynamic Lyrics Island
         // 的实际取值（其 `IslandTextBlock` 用 `mainFont.deriveFont(fontSize * .7f)`），
         // 也是宿主自带桌面歌词页在同位元素上的观感基准。沿用它是为了让用户在
         // 插件与原版之间切换时不会觉得"字怎么变大了"。**不是抄代码**，是取一个
         // 已被验证过的比例 —— 见 recon/24 §2 的差异说明。
         //
        /**
         * 无歌词且 `layout.noLyrics.showTrackTitle = false`（或曲名为空）时的占位文本。
         *
         * 取「暂无歌词」而不是 `♪` 之类的符号：符号在"歌词窗里出现一个音符"
         * 这个语境下是**歧义**的（可能被读成"正在播放"或装饰），
         * 而中文短句把状态说清楚了。用户在设置里关掉"显示曲名"，
         * 要的就是"别告诉我歌名"，给一句中性说明即可。
         */
        const val NO_LYRICS_PLACEHOLDER = "暂无歌词"

        /**
         * 译文行与主行之间的额外间距（像素）。
         *
         * 取 8f 的理由同上（原实现 `IslandTextBlock` 的 `gap = 8f`）。
         * 它是在行高**之外**另加的一段留白：译文本身占一行高，
         * 若不留白，两种字号的行会因 ascent/descent 不同而视觉粘连。
         *
         * ★★ **2026-10-03：字面量搬到 `IslandPolicy.TRANSLATION_GAP_PX`，这里改为别名。**
         * 理由：本值现在被**公开纯函数**（`translationCenterOffsetPx` / `translationRowHeightPx`）
         * 使用，而本 companion 是 **`private`** ⇒ 外部与探针都读不到。
         * ⚠️ **语义也已随之收紧**（见 `TRANSLATION_GAP_PX` 的 KDoc）：
         * 从「主行下沿 → 副行**中心**」改为「**主行盒底 → 副行盒顶**」——
         * 前者正是"上下留白不等"那个缺陷的来源。
         */
        const val TRANSLATION_GAP = TRANSLATION_GAP_PX

        // ══ 2026-10-10（B-3）：原声明已删，保留理由（普通注释，非 KDoc）══
        // ★★ 2026-10-10（审计 v4 · **B-3**）：译文未唱暗化系数**已迁到
        // `dev.cdl.settings.UNSUNG_TRANSLATION_DIM`**（值仍是 `0.62f`）。
        //
        // ## 为什么迁（原记："取 0.62 的论证"）
        //
        // ★ 原为这里的 `const val`，而**预览侧另有一份同值常量** ——
        // 两份靠"注释提醒必须同值"维系（预览那份的 KDoc 就写着"必须与
        // `IslandWindow.UNSUNG_TRANSLATION_DIM`（0.62）同值"）✗
        // ⇒ ★ 那是本项目最忌的「**同一常量两处各写一份**」形态
        // ⇒ ★ 收敛到 `settings/`（与 `dimPackedArgb` / `blendPackedArgb` 同处）✓
        //
        // ★ **取值 `0.62` 的论证原样保留在 `UNSUNG_TRANSLATION_DIM` 的 KDoc 里**
        // （太暗 → 与背景糊在一起；太亮 → 高亮看不见；0.62 是折中）。
        // ★ 本文件通过**顶层 import** 引用它 ⇒ 调用点一字未改 ✓
        // ══ 说明结束 ══

        /**
         * 三槽位所需的垂直内边距（上下各一份），避免文字贴着背景边缘。
         *
         * ⚠️ 2026-09-22：字面量搬到 `IslandPolicy.kt`（[ISLAND_VERTICAL_PADDING_PX]），
         * 这里改成**别名** —— 理由：它同时是"封面外径"与"词岛高度"两条公式的输入，
         * 而后者（[islandCollapsedHeightPx]）已在公开文件里（探针要断言它）。
         * 两个来源一旦漂移，表现是"封面与词岛内边距不一致"，**不报错**。
         */
        const val VERTICAL_PADDING = ISLAND_VERTICAL_PADDING_PX

        //
         // 译文行占据的额外高度（像素），`translationEnabled` 为 true 时计入。
         //
         // ## 为什么译文是"当前行的附加子行"而不是第四个槽位
         //
         // 原实现 `IslandLyricsPainter` 就是这么做的：译文挂在当前行**下方**，
         // 槽位模型仍是 prev/current/next 三个（见 recon/24 §2）。
         // 好处是"上一行 / 下一行要不要显示译文"这个歧义根本不存在 ——
         // 译文永远只属于正在唱的那一行。若做成第四槽，就得回答"谁的译文"
         // （上一行的？下一行的？），而那种问题没有正确答案。
         //
         // ## 高度必须与绘制侧一致
         //
         // 与 [LINE_HEIGHT_RATIO] 同样的约束：本值同时被窗口几何与
         // [IslandPanel.drawLyricSlots] 使用，两处不一致就会出现"文字挤出背景"。
         // 故只在这里定义一次。
         //
        /**
         * 译文行占用的高度。
         *
         * ⚠️ 2026-09-21：**比例改成参数**（原先是写死的 `TRANSLATION_SCALE`）。
         * 因为该比例现在可由 `layout.translation.scale_percent` 调 ——
         * 高度若仍按常量算，用户把副歌词调大后**译文会被裁掉**（高度没跟着长）。
         * 这是本项目 `line_gap_px` 那类"高度与绘制不同步"缺陷的同族形态。
         */
        // ★★ 2026-10-03：**实现已搬到公开纯函数** `dev.cdl.window.translationRowHeightPx`
        //   —— 理由是它与 `translationCenterOffsetPx`（副行落点）**必须由同一个语义来源给出**，
        //   而两者此前各算各的（那正是"副行没填满预留槽 ⇒ 上下留白不等"的根因）。
        //   本函数退化为**壳**（保留旧签名，调用点一个字不用改）。
        private fun translationRowHeight(
            fontSize: Int,
            translationScale: Float,
            lineGapPx: Float,
        ): Float = translationRowHeightPx(fontSize, translationScale, lineGapPx)

        /**
         * 按 [align] 算出**基线原点 X**（与 `karaokeRenderer.render` 的 `baselineX` 同义）。
         *
         * ## 为什么抽成一个助手（而不是在两个绘制点各写一次 `when`）
         *
         * 正文行（[drawOneLine]）与译文行（[drawTranslationLine]）都要算它。
         * 两处各写一份的话，"改一处漏一处"的表现是**译文相对正文偏移**
         * （例如正文左对齐、译文还在中间）—— 那看起来像"译文没跟着设置走"，
         * 而归因时会先怀疑译文自己的绘制逻辑，不容易想到是"对齐没同步"。
         *
         * ## 左右对齐为什么要内缩（[HORIZONTAL_INSET]）
         *
         * `width` 是**文字区宽度**（调用方已减掉左侧封面让出的宽度，
         * 见 `paintComponent` 的 `drawLyricSlots(g2, local, textWidth, …)`）。
         * 居中时文字两侧天然有空白，
         * 但左右对齐会让文字**贴到圆角边上** —— 而词岛是圆角矩形，
         * 贴边的字会被圆角切到一点。故左右对齐各留一段内缩。
         *
         * ## `areaLeft` 是什么（2026-09-17 新增，为封面让位）
         *
         * 它是**文字区左边缘**（0 = 左侧没有封面，行为与改动前逐像素一致）。
         *
         * 为什么必须传它而不是"把 width 减小就行"：`width` 只表达"可用多宽"，
         * 而左对齐的落点是 `HORIZONTAL_INSET`（**面板左边缘** + 内缩）——
         * 左侧有封面时那个位置**正是封面所在处**，文字会画到封面上。
         * 传入 `areaLeft` 后三种对齐都相对**文字区**计算，封面与文字各占各的区域。
         *
         * 默认值 `0f` 让"没有封面"这条路径的调用点**一个字都不用改** ——
         * 与 `areaLeft + 0` 的算术等价，不会有任何像素差异。
         */
        fun alignBaselineX(width: Int, advance: Float, align: Align, areaLeft: Float = 0f): Float =
            // ★★★ 2026-10-08 **B3a/A3**：三分支已外提到顶层公开纯函数
            //   [dev.cdl.window.lyricAlignBaselineX]（同 A1/A2 的取向：
            //   把"算式的出口"收成唯一一处，让探针**不必再复刻**）。
            //   ★ 本函数退化为**壳**；★ **5 个调用点一个都没改**（原地委派）。
            lyricAlignBaselineX(width, advance, align.wire, areaLeft, HORIZONTAL_INSET)


        /**
         * **有效行数** —— "最多能占几行"的**唯一判定点**。
         *
         * ## 为什么必须与 [IslandPanel.slotsFor] 分开、却又由同一个布尔驱动
         *
         * 两者回答的是**不同的问题**，不能合并：
         * - [IslandPanel.slotsFor] 回答"**这一次**要画哪几行" —— 依赖快照
         *   （某一行可能不存在，于是实际画出的行数比上限少）；
         * - 本方法回答"**最多**要留几行的高度" —— **不依赖快照**，
         *   因为窗口高度必须在没有歌词时也稳定（否则首行到来前后窗口会跳一次高度）。
         *
         * 但它们必须由**同一个** `layout.lines` 驱动 —— 若一处按单行算、
         * 另一处按双行算，就会出现"窗口多留一行高度"的错配：
         * 文字挤在顶部，**不报错**，只是难看。
         *
         * @param lines `layout.lines` —— 单行 / 双行（2026-09-18 合并自
         *   `layout.line_mode` + `island.experimental_multi_line`）
         */
        fun effectiveRows(lines: LineCount): Int = lines.rows

        /**
         * 由字号推导词岛高度。
         *
         * 公式与 `drawLyricSlots` 的布局一致：**行数 × 行高 + 上下内边距**，
         * 再在**显示译文**时加上译文行的高度。
         *
         * ⚠️ `translationEnabled` 必须传进来：译文行会真实占用垂直空间，
         * 若高度不给它留位置，译文会画到背景外面（或被 clip 切掉）。
         * 这也是本参数从无到有的原因 —— 改动前译文根本不画，故没有这个高度项。
         *
         * ⚠️ `lines` 与 `lineGapPx` 同理：行高不只由字号决定
         * （`layout.line_gap_px` 会额外加）。**三处必须用同一份几何**：
         * 本方法、`drawLyricSlots` 的 `lineHeight`、以及面板尺寸重算点。
         * 少同步一处的表现是"文字被切掉"或"下方空一块" —— 都不报错，只是难看。
         *
         * ⚠️ 2026-09-18：参数从 `(lineMode, multiLineEnabled)` 简化为单个
         * `lines`（`layout.lines`）。行数一律经 [effectiveRows] 推导，
         * 本方法**不自己 `when(lines)`**，理由见其 KDoc。
         */
        fun islandHeightFor(
            fontSize: Int,
            translationEnabled: Boolean,
            lines: LineCount = LineCount.SINGLE,
            lineGapPx: Float = 0f,
            /**
             * 译文行比例（`layout.translation.scale_percent` 折算后）。
             *
             * ⚠️ 2026-09-21 新增：**必须与绘制侧同源** —— 否则用户把副歌词调大后
             * 高度没跟着长，译文会被词岛底边裁掉（`line_gap_px` 那类缺陷的同族）。
             */
            translationScale: Float = 0.64f,
            /**
             * 「词岛高度是否跟随字号」（`layout.island.height.follow_font`）。
             * `true`（默认）= 旧行为 `歌词块 + 2 × VERTICAL_PADDING`（**开箱零变化**）。
             */
            followFont: Boolean = ISLAND_HEIGHT_FOLLOW_FONT_DEFAULT,
            /**
             * 「收起态高度 / 歌词块高度」的比值（`layout.island.height.ratio_percent`）。
             * **仅在 `followFont = false` 时生效**。
             */
            heightRatioPercent: Int = ISLAND_HEIGHT_RATIO_DEFAULT_PERCENT,
        ): Int {
            // ⚠️ 2026-09-22：公式**已搬到** [islandCollapsedHeightPx]（公开纯函数）——
            //    理由与本项目其它"抽公开纯函数"一致：`IslandPanel` 的 companion 是
            //    `private`，探针看不到 ⇒ "比值档真的按 `base + extra` 算吗"这条断言
            //    写不出来。本函数退化为**壳**（保留旧签名与默认值，调用点一个字不用改）。
            return islandCollapsedHeightPx(
                fontSize = fontSize,
                rows = effectiveRows(lines),
                lineGapPx = lineGapPx,
                translationRowHeightPx = if (translationEnabled) {
                    translationRowHeight(fontSize, translationScale, lineGapPx)
                } else {
                    0f
                },
                followFont = followFont,
                heightRatioPercent = heightRatioPercent,
            )
        }
        /**
         * 不透明度 100% 时的底色（深灰）。见 [IslandPanel.backgroundWithOpacity]。
         *
         * ⚠️ **2026-09-22：字面量搬到了 `IslandPolicy.kt`**（[BACKGROUND_BASE_RED] 等
         * 公开顶层常量），这里改成**别名**。理由：
         * 新增的 `background.color` 键的默认值也要引用"默认底色是什么"，
         * 而 `IslandPanel` 是 **`private inner class`** —— 它的 companion **外部看不到**，
         * 默认值不可能从这里派生。⇒ 唯一的可读来源必须落在**公开**位置。
         *
         * 三个名字**保留**：它们仍是"这个颜色是什么"的**可读来源**
         * （`Color(0x14, 0x16, 0x1A)` 一眼能读，`Color(rgb)` 不能）。
         */
        const val BASE_BACKGROUND_RED = BACKGROUND_BASE_RED
        const val BASE_BACKGROUND_GREEN = BACKGROUND_BASE_GREEN
        const val BASE_BACKGROUND_BLUE = BACKGROUND_BASE_BLUE

        /**
         * [BASE_BACKGROUND_RED] / [BASE_BACKGROUND_GREEN] / [BASE_BACKGROUND_BLUE] 的
         * 打包形态 —— 供 `background.color_mode` 的**手动档**直接引用。
         *
         * 为什么还要有一个 `Color` 常量（而不是每次 `Color(r, g, b)`）：
         * `Appearance.backgroundColorFor` 每帧都会被调用一次，`Color` 是不可变值类、
         * 每次构造都是新对象。用一个共享常量把这条路径的每帧分配降为 0 ——
         * 与 `RenderCache` 存在的理由（消除每帧分配热点）是同一条纪律。
         * 三个通道常量**保留**是因为它们仍是"这个颜色是什么"的可读来源。
         */
        val BASE_BACKGROUND: Color = Color(BASE_BACKGROUND_RED, BASE_BACKGROUND_GREEN, BASE_BACKGROUND_BLUE)

        /** `spectrum.anchor` 的另一取值：**贴歌词区顶边**。见 [SPECTRUM_ANCHOR_BOTTOM]。 */
        const val SPECTRUM_ANCHOR_TOP: String = "TOP"

        /**
         * `spectrum.anchor` 的第三取值：**词岛右端的窄条**（2026-10-02 新增）。
         *
         * ★ 用户指令：「**实现那个总账里的词岛右侧的小频谱**」——
         * 台账 `migration/未达成项可行性评估-2026-09-26.md` §2 ⑧ 的第三种形态
         * 「**五根细条 + 词岛右端**」。
         *
         * ## 与前两档的**根本区别**（不是"又一个贴边位置"）
         *
         * | 档 | 占哪块区域 | 条数 |
         * |---|---|---|
         * | `BOTTOM` / `TOP` | **整个歌词区**（`areaLeft` ~ `islandRight`） | 由宽度推导（可上千） |
         * | ★ `RIGHT` | ★ **右端一条窄带**（宽 = [RIGHT_BAR_COUNT] 根柱的宽度） | ★ **恰好 5 根** |
         *
         * ★★ ⇒ 本档**不改绘制方向**（柱子仍**垂直**生长）—— 它只**改区域**。
         * 这是本档成本低的原因：`spectrumBandGeometry` / `paintBars` 一行都不用改，
         * 只需在调用方把 `areaLeft` / `areaWidth` 换掉（见 `drawBackgroundSpectrum` 的调用点）。
         *
         * ⚠️ **`BOTTOM` / `TOP` 在本档下不再有意义**（窄带里没有"上下贴边"的区分）
         * —— 本档内部固定按 `BOTTOM` 语义画（贴窄带底边向上长）。
         */
        const val SPECTRUM_ANCHOR_RIGHT: String = "RIGHT"

        // ⚠️ `RIGHT_BAR_COUNT`（窄条占几根柱）**不在本 companion 里** ——
        //   ★ 它住在 `render/SpectrumGeometry.kt`（**公共纯函数文件**）。
        //   理由：预览（`IslandPreviewPane`）**也要用同一个宽度**，而本 companion 是
        //   `private`（探针与预览都看不到）⇒ 放这里会导致"两边各写一份 ⇒ 迟早漂移"。
        //   ★ 这正是本项目"抽公开纯函数"那条纪律的应用（见 `islandCollapsedHeightPx`
        //   的 KDoc：同一成因）。

        // ── 左侧封面区（`cover.enabled`）的几何 ──────────────────────────────

        /**
         * 封面区距词岛**左边缘**的内缩（px）。
         *
         * 取 [HORIZONTAL_INSET] 同一个值：左对齐时文字的左边缘是
         * `areaLeft + HORIZONTAL_INSET`，而封面的左边缘是 `LEADING_INSET` ——
         * 两者相等时，**封面与左对齐的文字落在同一条视觉基线上**。
         * 若各取一个值，切换对齐方式时封面看起来会"没对齐"。
         */
        const val LEADING_INSET = HORIZONTAL_INSET

        /**
         * 封面与文字之间的间隙（px）。
         *
         * 取 12：小于它（如 4–6）时，居中排版的歌词在窄词岛上会几乎贴住封面，
         * 视觉上"挤在一起"；大于它（如 20）则会在封面右侧留出一段明显的空带，
         * 看起来像"文字没有真正居中于剩余区域"。
         */
        const val LEADING_GAP = 12f

        /**
         * 词岛宽度的下限（px）。
         *
         * **必须与 `SettingRegistry` 里 `layout.max_width` 的 `range.start` 一致**（280）——
         * 否则 `fixed_width = false` 收缩出来的宽度可能**小于用户能拖到的下限**，
         * 表现为"滑块最小只能到 280，但词岛能缩得更窄"（两处值域打架，
         * 与 BUG-024「json 范围 ↔ 注册表范围不一致」是同一类问题）。
         */
        const val MIN_ISLAND_WIDTH = 280

        /**
         * 词岛宽度**相对屏幕两侧保留的边距**（px）。
         *
         * ## 这个数字从哪来（不是新发明的）
         *
         * `layout.max_width` 的注册上限 `2400` 原注释写着「在 2560 宽的屏上留 80 px 边距」
         * —— 即 `2400 = 2560 − 2×80`。本常量就是那个 80，
         * 由 [effectiveMaxWidth] 用来把「固定的 2400」换成「按当前屏算」。
         *
         * → **2560 的屏仍然得到 2400**（零行为变化），小屏才收缩。
         */
        const val SCREEN_SIDE_MARGIN_PX = 80

        /**
         * 收缩/扩张的**迟滞阈值**（px）。
         *
         * ## 为什么必须有它（`recon/13` 曾提醒"半成品会抖"）
         *
         * `fixed_width = false` 时宽度由**每帧实测**的内容宽推导。而实测值会因
         * 抗锯齿、分数度量、字形回退而在**相邻帧之间有 1–2 px 的抖动** ——
         * 无条件跟随会让窗口每帧都改尺寸，用户看到的是**边缘持续微颤**。
         *
         * 取 24 px 的理由：它**大于**任何单帧测量噪声（1–2 px），
         * 又**小于**"换一句明显更长/更短的歌词"带来的变化（通常 50 px 以上）——
         * 于是"该变的会变、不该变的不会动"。
         */
        const val WIDTH_HYSTERESIS_PX = 24

        /**
         * 封面边长上限（px）—— `cover.scale_with_font = false` 时才套。
         *
         * 取 56：默认字号下**歌词块**约 57 px（主歌词行 + 译文行），56 与它视觉重量相当。
         *
         * ⚠️ **2026-09-24：原文的「与原实现同比例」论证已失效。**
         *   原文写「默认字号 22 下词岛高约 **158 px**…原实现在 88 px 高的词岛上用 32 px
         *   （约 36%）；**56/158 ≈ 35%**，同一量级」。但词岛高度模型已从「三行歌词」
         *   改为「**主歌词 + 译文**」（见 `LineCount` 的 KDoc）⇒ 默认字号下歌词块只有约 57 px，
         *   **56/57 ≈ 98%**，与既有的 36% **不在同一量级**。
         *
         *   **这不一定是缺陷**（紧凑词岛上配大封面可能正是想要的），但**论证已不成立** ⇒
         *   **「56 是否仍合适」待用户确认**；本注释不擅自改常量值。
         * 上限存在的原因是词岛**很宽**（`layout.max_width` 可到 1200）——
         * 若边长只按高度推导，会得到一个与"信息区"体量不符的大方块。
         *
         * ⚠️ 2026-09-22：字面量搬到 `IslandPolicy.kt`（[COVER_MAX_OUTER_SIZE_PX]），
         * 这里改成**别名** —— 公式已抽到 [leadingCoverOuterSizePx]，设置页预览也调它，
         * 常量必须与公式在同一个文件里（否则又是一份会漂移的副本）。
         */
        const val MAX_COVER_SIZE = COVER_MAX_OUTER_SIZE_PX

        /**
         * 封面边长下限（px）。
         *
         * 取 20：单行模式 + 小字号（14 px）下歌词区高约 42 px，减去上下内缩后只剩
         * 22 px —— 20 是"还能看出是张图"的最小尺寸。再小就只是一个彩色小点，
         * 不如不显示（但**不**在这里返回 null：那时左侧会突然收窄、文字跳动，
         * 比一个小封面更刺眼）。
         *
         * ⚠️ 2026-09-22：同上，改为 [COVER_MIN_OUTER_SIZE_PX] 的别名。
         */
        const val MIN_COVER_SIZE = COVER_MIN_OUTER_SIZE_PX

        /** 圆角形态（ROUNDED_RECT / PILL / NOTCH）下，封面圆角占边长的比例。 */
        const val COVER_ARC_RATIO = 0.18f

        // ⚠️ `cover.shape` 的"圆形"取值常量**不在这里** —— 它被移到公开文件
        //    `IslandPolicy.kt`（顶层 `COVER_SHAPE_CIRCLE`）。理由：
        //    `IslandWindow` 是 `internal`，**探针看不到它**，于是
        //    "这个字符串与注册表 options 逐字一致吗"这条断言就没法写；
        //    而字符串对不上的后果是 **静默失效**（`if (shape == CIRCLE)` 恒 false，
        //    选了圆形却没变，不报错）。项目纪律：**首选抽公开纯函数/常量**，
        //    而不是"为了测它而放宽可见性"。

        /**
         * **方形**（SQUARE）词岛下封面圆角占边长的比例。
         *
         * 明显小于 [COVER_ARC_RATIO]：用户选"方形"就是明确要直角轮廓，
         * 在方形岛上放一个大圆角封面会让两个形态的差异看起来"没生效"。
         * 但仍留 6% —— 完全直角在缩小绘制时边缘会有锯齿感。
         */
        const val COVER_ARC_RATIO_SQUARE = 0.06f

        /**
         * 封面描边色（alpha 35 的白）。
         *
         * 深色封面上看不出边界、浅色封面会与浅色背景糊在一起 ——
         * 一层极淡的白描边在任何封面上都能勾出轮廓，且几乎不可见。
         * 与原实现 `IslandLeadingContent.cover` 的描边同值。
         */
        val COVER_OUTLINE: Color = Color(255, 255, 255, 35)

        /**
         * 黑胶环色（`cover.vinyl`，2026-09-21）—— **不透明的近黑**。
         *
         * ## 为什么不取纯黑 `Color(0,0,0)`
         *
         * 词岛背景默认是**深色**（不透明度 99 的近黑）。纯黑环贴在深色背景上
         * 会"融进去"，用户看到的是"封面变小了一圈"而不是"多了一圈黑边"。
         * 取 `18` 保留一点点层次 —— 在深色背景上勾得出轮廓，
         * 在浅色壁纸透出的场景下依然明确是"黑"。
         *
         * ## 为什么不带 alpha
         *
         * 黑胶本体是**不透明**的。带 alpha 的环会透出底下的封面图/背景，
         * 看起来像"半透明塑料圈"，与用户要的黑胶观感相反。
         */
        val VINYL_RING_COLOR: Color = Color(18, 18, 18)

        //
         // ## 历史（**勿照此改回**）：`0.38 → 0.75` 是一个已被推翻的中间态
         //
         // **原记**（2026-09-19）：把底色从 `0.38` 提到 `0.75`，并把渐变 fractions
         // 写成固定 `[0f, 0.65f, 1f]` —— 理由是"下面 65% 保持实心、只在顶端 35% 淡出"。
         //
         // **实为**：该状态**已被下一段 KDoc 推翻** —— `0.75` 本身被定性为缺陷
         // （"拖到 100 也追不上关掉淡化"）。且渐变起点现在由 [spectrumFadeStartFraction]
         // 按**带高**现算，**不是**固定的 `0.65`。
         //
         // ⇒ **取值依据一律以下一段为准**；本段只作修订史留存。
         //
        /**
         * 频谱底图/渐变笔刷的**基准不透明度**。
         *
         * ## ⚠️ 2026-09-19 改为 `1.0f` —— 它原本是 `0.75f`，而那是一个**缺陷**
         *
         * 用户原话（答复"拖不透明度到 100 vs 关掉顶端淡化，哪边更透"）：
         * > 「**前者**」（= 拖不透明度到 100 **更透**）
         *
         * ### 根因
         *
         * 笔刷 alpha 是 `255 × SPECTRUM_BACKGROUND_ALPHA × alphaScale`，
         * 而 `alphaScale` 来自 `spectrum.opacity_pct`。于是：
         *
         * | 情形 | 实际 alpha |
         * | --- | --- |
         * | `opacity_pct = 100`（用户以为"完全不透明"） | `255 × 0.75 = 191` → **75%** |
         * | `fade_top = false`（底图走 `cache.color(packed)` 那条实心路径） | **255 → 100%** |
         *
         * —— 两条路径的"满值"不一致，用户拖到 100 也**永远追不上**"关掉淡化"。
         * 而注册表里 `spectrum.opacity_pct` 的 KDoc 写的是「`100` = **完全不透明**」——
         * **注释与实现不符**，这正是"设置项的值 ≠ 生效值"家族。
         *
         * ### 修法
         *
         * 基准改 `1.0f`（不再额外打折），同时把 `spectrum.opacity_pct` 的**默认值
         * 100 → 75** —— 于是**默认观感逐像素不变**（`255 × 1.0 × 0.75` = 原来的 191），
         * 而"拖到 100"终于等于"完全不透明"。
         */
        const val SPECTRUM_BACKGROUND_ALPHA = 1.0f

        /**
         * `spectrum.height_pct` 的**下限**（与注册表该项的 `range.start` 一致）。
         *
         * 取 10：**不能是 0** —— 0% 等于"不画频谱"，而那是 `spectrum.enabled` 的语义。
         * 两个键做同一件事会让用户困惑（"我开了频谱怎么什么都没有"）。
         *
         * ⚠️ 与注册表 `range = 10.0..100.0` **必须同步** —— 不一致会导致
         * "滑块能拖到 10、值被夹回别的数"（BUG-024 的形态）。
         * 这里再夹一次是**防御**（注册表被手改坏时不至于画出负高度）。
         */
        const val MIN_SPECTRUM_HEIGHT_PCT = 10

        // ── 长歌词滚动的三个参数：**已移到 `IslandPolicy.kt`** ────────────────
        //
        // 原记：`SCROLL_SPEED_PX_PER_SEC` / `SCROLL_HOLD_MS` / `SCROLL_MIN_OVERFLOW_PX`
        //   定义在本（私有）companion 里。
        //
        // 实为：本 companion 是 `private`，而探针是独立编译单元 ——
        //   **看不到它们**，只能"抄一份常量"去断言相位数学，而抄来的常量会漂移。
        //   故连同 [marqueeOffsetPx] 一起移到 `IslandPolicy.kt`（公开的纯函数文件），
        //   让探针断言的是**真值**。三个名字在本文件里仍然可直接引用（同包顶层）。


        /**
         * 封面补重绘的延迟（毫秒）。
         *
         * 取 120：`CoverArtProvider` 的一次后台读取（读文件头 + 解码 + 缩放）
         * 是**几十毫秒**量级（它的 KDoc 里记的是"几十毫秒"）。120 ms 让第一次
         * 补重绘有较大概率直接命中已到达的图片 —— 比"每 33 ms 轮询一次"
         * 少了 3/4 的无效重绘，而"封面晚 120 ms 出现"在观感上不可辨。
         *
         * 注意这个延迟**只在暂停时**起作用（播放中由帧驱动每 33 ms 重绘，见
         * [scheduleCoverRepaint]），故用户实际感知到的是"最多晚一帧"。
         */
        const val COVER_REPAINT_DELAY_MS = 120

        /**
         * 封面补重绘的最大次数。
         *
         * 8 × [COVER_REPAINT_DELAY_MS] ≈ 1 秒。超过它认定"本曲没有封面"并停止轮询 ——
         * 没有封面的歌很常见，不限次数会让词岛在暂停时**永远**每 120 ms 重绘一次。
         * 1 秒的依据：本地文件读取不会超过这个量级（`CoverArtProvider` 只读文件头
         * 与 4 MB 扫描窗口，不含网络）；真超过它说明这首就是没有封面。
         */
        const val MAX_COVER_REPAINT_ATTEMPTS = 8

        // ── 封面锁的「锁头标志」（2026-09-30 封面态 S5）────────────────────────

        /**
         * 锁头标志的边长（px）。
         *
         * ## 取 16 的依据
         *
         * 与 `PlaybackControls.ICON_SIZE`（控件条图标）同量级 —— 标志是**同样权重**的
         * 视觉元素（一个图标），不该比控件条的图标更大。而 [COVER_LOCK_BADGE_INSET_PX]
         * 让它内缩进封面，故实际占位更小。
         *
         * ⚠️ 它同时是**命中域**（`coverLockBadgeRect` 用它当边长）—— 16×16 是
         *    可点的（远大于"最小可点目标"的经验下限 ~8 px）。
         */
        const val COVER_LOCK_BADGE_PX = 16f

        /** 锁头标志距封面右上角的内缩（px）。贴边会显得"掉出封面"。 */
        const val COVER_LOCK_BADGE_INSET_PX = 4f

        /**
         * 锁头标志的**显示窗口期**（毫秒）。
         *
         * ## 为什么需要它（`recon/67` §3.5 L-2）
         *
         * 锁是**持久状态**，而"鼠标划过封面"是**瞬时事件**。若标志只在"鼠标停在封面上"
         * 时画，鼠标一移开它就消失 ⇒ **用户无法确认自己锁上了没有**。
         * ⇒ 命中已锁封面后，让标志**常显一段时间**（且窗口期内**可点**）。
         *
         * ## 取 1200 的依据
         *
         * - **下限**：必须够长到用户能"看见 ⇒ 决定是否去点" ⇒ 短于 ~800 ms 会有点不上。
         * - **上限**：它是提示，不是常驻元素，不该永久占住封面右上角。
         * - 参照项目内同类"短暂提示"的量级：`Motions.CONTROLS_APPEAR` 是 180 ms
         *   （纯动画），`COVER_REPAINT_DELAY_MS` 是 120 ms（单次重排）。
         *   1200 ms ≈ 一次动画的 6–7 倍，符合"看一眼并做决定"的时长。
         *
         * ⚠️ **它是纯时长（不是设置项）**—— 与 `HoverState.FADE_IN_MS` 同一条纪律：
         *    提示时长不值得开一个键（`recon/67` §14.1：只有"新功能"才配拥有键）。
         */
        const val COVER_LOCK_BADGE_MS = 1200L

        /**
         * 锁头标志衬底的不透明度（0..255）。
         *
         * ## 为什么必须有衬底
         *
         * 标志画在**封面图**上，而封面可以是任意亮度（纯白专辑封面很常见）。
         * 纯浅色图标在浅封面上等于看不见 —— 而"看得见"是这个标志的**全部意义**
         * （它是"你锁上了"的唯一反馈，见 [COVER_LOCK_BADGE_MS]）。
         * ⇒ 加一层深色半透明衬底，保证任何封面上都有对比度。
         *
         * 取 `140`（约 55%）：够压暗背景、又不至于把封面遮成一块黑斑
         * （标志只有 16×16，遮住的面积本就很小）。
         */
        const val COVER_LOCK_BADGE_SCRIM_ALPHA = 140

        /**
         * ★ 2026-09-30（封面态 ③）：水位波动的一个完整周期时长（毫秒）。
         *
         * ## 语义
         *
         * `wavePhase` = `(positionMs % 本值) / 本值` ⇒ **播放每过本值毫秒，水波走完一个整周期**。
         * ⚠️ 用**播放位置**而不是墙钟 ⇒ **暂停即冻结**（§2.4.6 C-2 的硬要求）；
         * 恢复播放时从当前位置继续算，**不跳变**（V12 的判据）。
         *
         * ## 取 4000 的依据
         *
         * - 歌的**播放速度**是它真正的节拍来源。4000 ms ≈ 一小节（中速歌）的 2 倍，
         *   波动看起来是"随歌流动"而不是"疾速抖动"或"几乎不动"。
         * - 参照项目内同类"水/流动"语义的时长：`cover.rotate_period_s` 的默认档位在
         *   秒级（封面转动一圈），而波动应当**明显比转动快**（它是"水"不是"唱片"）。
         * - ⚠️ 它**不随播放速度（倍速）变化** —— 本项目拿不到播放速率，
         *   用位置差能间接反映（倍速播放时位置推进更快 ⇒ 水位推进更快 ⇒ 相位也更快）。
         *   这是**自然得到的**，不需要额外处理。
         *
         * ⚠️ **它是纯常量（不是设置项）** —— 与 `COVER_LOCK_BADGE_MS` 同一条纪律：
         *    这类"观感档位"不值得开键（`recon/67` §14.1：只有"新功能"才配拥有键）。
         *    若真机观感不对，改这里；若用户要"可调"，那时再升格为键。
         */
        const val COVER_WATER_WAVE_PERIOD_MS = 4000L

        /**
         * ★★ 2026-09-28：进度条"提交后显示保持"的**最长时间**（毫秒）。
         *
         * ## 它治什么（用户报障）
         *
         * 用户原话（逐字）：「**拉动进度条(无论何种形态)，松手后会短暂闪现回原位**」。
         * 松手后进度条必须**继续显示目标位置**，直到宿主用新位置确认 ——
         * 否则中间那几帧会按旧位置画（见 [committedSeekMs] 的 KDoc）。
         *
         * ## 为什么是 3000
         *
         * 宿主位置回调约 **1 秒一次**（`PlaybackTimeline` 类注释里引的原实现原话
         * "the host's one-second clock"）。取 3 秒 = 连续错过三次回调仍未确认
         * ⇒ 判定宿主不会回了，放弃保持退回真实位置。
         *
         * 这个值只影响"宿主异常时保持多久"（正常路径在几十毫秒内就被 `onSeekTo` 确认），
         * 故取值宽松一点更安全：短了会在慢盘/卡顿时**重现**闪现，长了只是多停一会儿。
         */
        const val SEEK_DISPLAY_HOLD_MS = 3_000L

        /**
         * ★★ 2026-09-28：判定"宿主已确认 seek"的位置容忍度（毫秒，取绝对值）。
         *
         * [progressDisplayMs] 用它做**比值/时间**判据（纪律 14）：一旦
         * `|真实位置 − 目标| ≤ 它` 就认为宿主已经跳过去了，显示保持**自动失效**。
         *
         * 取 250 ms 的理由：宿主位置回调的量化粒度与取整误差都在十几毫秒量级，
         * 而 250 ms 远小于用户能分辨的"进度条位置差"（一首 4 分钟的歌，
         * 250 ms 只有 0.1% 的长度）。取太小则宿主的取整误差会让保持**永不失效**
         * （退化成"卡在目标上"），取太大则会让保持**过早失效**（闪现复发）。
         * 两侧不对称，故取一个数量级上的中间值。
         */
        const val SEEK_CONFIRM_TOLERANCE_MS = 250L

        /** 图形环境不支持半透明时的降级底色 —— 必须可读，故用不透明的深灰。 */
        val DEGRADED_BACKGROUND: Color = Color(0x14, 0x16, 0x1A)

        // ⚠️ 原 `MIN_VISIBLE = 48`（"拖动时至少留 48px 在屏幕内"）**已删除**（2026-09-18）。
        //
        // 它曾是**运行时不裁剪位置**那三轮修改的核心参数（见 `applyWindowSize` 的
        // "四次修订"注释）。用户明确要求「**用户造成的操作还是不要阻拦**」之后，
        // 运行时的位置裁剪被整体移除，`restoreSavedPosition` 也改用了
        // "窗口中心是否在主屏内"这一条**独立判据**（不再用像素余量）。
        //
        // 于是它**零消费点**。按项目纪律（"声明了但没消费点 = 死代码，留着只会让人
        // 以为还有人在用"）删掉 —— 要恢复很容易：判据与数值都留在这条注释里。

        // ⚠️ 原 `FRAME_INTERVAL_MS = 33` **已迁移**（2026-09-20）。
        //
        // 引入动画后，帧间隔**不再是常量** —— `animation.quality` 要把它在
        // 33（标准）/ 16（高、极致）之间切换，故它搬到了动画运行时：
        // **`AnimationRuntime.frameIntervalMs`**，"为什么基准是 30 fps 而非 60"
        // 的完整论证也一并搬了过去（见那个属性的 KDoc）。
        //
        // **不在这里留一个重复的常量**：两份"帧间隔"迟早会不一致，
        // 而那种不一致的表现是"帧率设置只生效一半"，极难定位。
        // 帧驱动的取值点只有一个 —— [startFrameDriver]。

        /**
         * 位置跳变阈值（毫秒）。超过它认为发生了 seek，需要把定时器拉回起跑线。
         *
         * 取 800 ms：正常播放时每帧（33 ms）位置只前进约 33 ms，远小于阈值；
         * 而 seek 通常是"跳到另一句"，跨度至少是秒级。800 ms 既不会把
         * 正常播放误判为跳变，又能捕捉到小跨度的拖动。
         */
        // ⚠️ 原 `ISLAND_FADE_MS = 220` **已迁走**（2026-09-20）。
        //
        // 引入动效风格体系后，动效参数（时长 / 缓动 / 风格 / 距离）**只在动画包里
        // 出现一次**（`Motions.ISLAND_FADE`）—— 留在这里就会出现"两处时长"，
        // 而那种不一致的表现是"改了时长只生效一半"，极难定位。
        // "为什么是 220 ms"的完整论证也一并搬进了 `Motions` 的 KDoc。

        const val SEEK_JUMP_THRESHOLD_MS = 800L

        /**
         * 悬停定时器的间隔（毫秒）。
         *
         * ## 为什么是 40 ms
         *
         * 它只负责一件事：把"延迟到点"这个**时间事件**变成一次检查。延迟量级由设置决定
         * （注册表默认 `enterDelayMs = 120` / `exitDelayMs = 250`），40 ms 的量化误差
         * 意味着控件最多晚 40 ms 弹出 —— 远低于人眼对"响应"的可辨阈值（约 100 ms），
         * 而 25 Hz 的唤醒**只在过渡窗口内**发生（见 [syncHoverTicker] 的按需启停），
         * 状态稳定时定时器是停的，不会长期占用 CPU。
         *
         * 不取更小（如 16 ms）的理由：它只会让弹出早 24 ms，代价是唤醒频率翻 2.5 倍；
         * 也不取更大（如 100 ms）：那会让"延迟 120 ms"的观感变成"延迟 200 ms"，
         * 用户能感觉到滞后。
         */
        const val HOVER_TICK_MS = 40

        /**
         * **区域级穿透**总开关（2026-09-17 新增）。
         *
         * ## 它控制什么
         *
         * 开启时，"是否穿透"由**光标位置动态决定**（见 [applyRegionalClickThrough]）：
         * 光标在字形/控件上 → 不穿透；在背景空白处 → 穿透。
         * 关闭时退回原行为：整窗要么全穿透、要么全不穿透（由 `island.interactive` 决定）。
         *
         * ## 为什么写成常量而不是设置项
         *
         * 用户对这条的表述是**"应当如此"**（「对于词岛背景，**不应阻挡**鼠标对其下
         * 覆盖的内容操作」），不是"给我一个开关"。把它做成设置项会让设置面从 48 项
         * 涨到 49 项，而**没有用户诉求支持那一项**。
         *
         * 若将来需要可关闭（如某些环境下轮询开销不可接受），把它接进 `SettingRegistry`
         * 即可 —— 判定逻辑本身不需要改。
         *
         * ## 代价（诚实记录）
         *
         * 开启后**未穿透态也常驻 40 ms 轮询**（原行为是事件驱动、只在需要时 tick）。
         * 这是"背景穿透"能成立的**唯一**办法：不轮询就不知道"光标离开字形了没有"。
         * 40 ms = 25 Hz，开销与穿透态的既有轮询同级。
         */
        const val REGIONAL_CLICK_THROUGH = true

        /**
         * 淡入动画期间的悬停定时器间隔（毫秒）。
         *
         * 取 16 而非 40：动画总长远短于 1 秒，40 ms 的量化只有 3–4 帧（观感是"跳两下"），
         * 16 ms 有 9–10 帧（约 60 fps），才是"渐变"。见 [syncHoverTicker] 的说明 ——
         * 这个高频档**只在展开后的头一段存在**，之后立刻回到 [HOVER_TICK_MS]
         * 或彻底停掉，不是常驻开销。
         */
        const val FADE_TICK_MS = 16

        /**
         * **看门狗**定时器的间隔（毫秒）—— 「全屏检测 + 重申置顶」。
         *
         * ## 为什么是**独立的低频**定时器，而不是挂在 40 ms 的 [HOVER_TICK_MS] 上
         *
         * 挂上去看似"免费"：`REGIONAL_CLICK_THROUGH = true` 已经让悬停定时器常驻，
         * 于是共用一次唤醒、不用新建对象。**但那是巧合，不是保证**：
         *
         * ① 悬停定时器的启停由 [HoverState.needsTicker] 决定 —— 一旦将来把
         *    `REGIONAL_CLICK_THROUGH` 关掉，它就退回"按需启停"，
         *    于是**全屏检测会静默失效**，而用户只会看到"设置没反应"
         *    （本项目已经有过一次"死设置"，就是 `island.hide_fullscreen` 本身）；
         * ② 它的间隔在展开动画期间会变成 [FADE_TICK_MS] = 16 ms ——
         *    全屏检测会被迫以 60 Hz 去问内核，而全屏状态的变化频率是**分钟级**；
         * ③ 两个关注点混在一个 tick 里，任何一处 early-return（悬停 tick 有**两处**）
         *    都会顺手掐掉另一处，且不会报错。
         *
         * 独立定时器把"系统状态在背后变了"与"鼠标在哪"彻底解耦。代价只是一次
         * 2.5 Hz 的唤醒 —— 与既有的 25 Hz 悬停轮询相比可忽略。
         *
         * ## 为什么是 400 ms
         *
         * 两个消费点对延迟的容忍度都很高，且**量级相同**：
         * · 进/出全屏 → 隐藏/显示词岛：0.4 s 内完成，观感是"跟着全屏走"；
         * · 被任务栏压下去 → 弹回来：0.4 s 后自愈，观感是"偶尔闪一下"
         *   而不是"一直被挡"。
         *
         * 取更小（如 40 ms）**没有收益**：`raiseToTop` 是 `SetWindowPos`
         * （用户态→内核态），高频调用既浪费又可能带来可见的 Z 序重排闪烁。
         * 取更大（如 1000 ms）则"任务栏遮挡"会持续整整一秒，退回用户投诉的观感。
         */
        const val WATCHDOG_TICK_MS = 400

        /**
         * 全屏状态切换需要的**连续确认次数**（2026-09-18 加）。
         *
         * ## 为什么需要迟滞
         *
         * 真机日志（`super-island.log:5784-5811`）显示 `island.hide_fullscreen`
         * 的显隐在 **1~3 秒**间隔上**来回抖**（隐藏→可见→隐藏→可见），
         * 用户看到词岛反复闪。而当时用户只是在听歌 + 把词岛放在任务栏上，
         * 状态**没有任何变化** → 翻转源是 `GetForegroundWindow` 的瞬时干扰。
         *
         * ## 取 2 的理由（= 最坏 0.8 s 延迟）
         *
         * · 1 → 等于没有迟滞（单次干扰照样切换）；
         * · 2 → 能吃掉**单 tick 的干扰**（那是最常见的形态：一次 `null`、
         *   一次通知窗口抢占前台）；
         * · ≥3 → 真的进/出全屏要等 1.2 s 以上，用户开始觉得"词岛反应迟钝"。
         *
         * ⚠️ **如果真机上仍然抖**，说明干扰持续了 2 个 tick 以上，
         *   下一步不是继续加大这个数，而是**把干扰源找出来**
         *   （在切换处打出 `GetForegroundWindow` 的句柄与矩形，看是谁在抢）。
         */
        const val FULLSCREEN_CONFIRM_TICKS = 2

        /**
         * 悬停期间重申置顶的**最小间隔**（2026-09-18 加）。
         *
         * ## 为什么需要一个独立的、比 [WATCHDOG_TICK_MS] 更快的档
         *
         * 用户报「**应用全屏下词岛无法保持页面最前**，也**无法置于任务栏上方
         * 盖住任务栏**」。根因是：任务栏同为 topmost 组成员，且 Windows 在
         * **鼠标悬停到任务栏上**时把它提到组内最前 —— 而词岛要盖住任务栏，
         * 光标就必然在任务栏矩形内，于是**悬停本身在持续触发抢先**。
         * 400 ms 的定期重申追不上这种"由鼠标事件驱动"的连续抢先。
         *
         * ## 为什么取 200 ms
         *
         * [HOVER_TICK_MS] = 40，不节流就是 25 Hz —— 而看门狗的 KDoc 已论证过
         * 高频 `SetWindowPos` 可能带来可见的 Z 序重排闪烁。
         * 200 ms（5 Hz）比看门狗快一倍、比每帧慢一个数量级，
         * 落在"追得上抢先"与"不闪"之间。**这是估的，不是量出来的** ——
         * 真机上若仍被遮挡，先把它降到 120 ms；若出现闪烁，升到 300 ms。
         */
        const val RAISE_THROTTLE_MS = 200L
    }
}

/**
 * 一行**已绘制**歌词的几何快照 —— 字形命中测试的输入。
 *
 * ## 为什么存 `TextLayout` 引用而不是只存坐标
 *
 * `GlyphHitTester` 需要 `layout.advance / ascent / descent / characterCount` 与
 * `getCaretShapes`，这些都只能从 `TextLayout` 拿。而 `TextLayout` 的构造代价是
 * **0.0234 ms**（见 `RenderCache` 的实测表），在鼠标事件里重建是不可接受的。
 * 存引用是零成本 —— 且这些实例本来就活着（`RenderCache.layouts` 持有它们）。
 *
 * ## 与 `Slot` 同为文件级私有类型
 *
 * 同样的理由：Kotlin 不允许在 `inner class` 内嵌套 `class`（"Class is prohibited here"，
 * 编译实测），而 `IslandPanel` 是 `inner class`。做成 `private` 顶层类型即可满足
 * "只在本文件内可见"的封装需求。
 *
 * @param text 与 [layout] 对应的文本。`GlyphHitTester` 用它做轮廓缓存的键
 * @param baselineX 基线原点 X（与传给渲染器的完全一致）
 * @param baselineY 基线原点 Y。命中判定必须反向平移这个量，见 `GlyphHitTester` 的坐标系约定
 */
private data class DrawnRow(
    val text: String,
    val layout: TextLayout,
    val baselineX: Float,
    val baselineY: Float,
)

    /**
     * **歌词行数**（`layout.lines`）。2026-09-18 取代了原先的
     * `LineMode`（三选模式）+ `island.experimental_multi_line`（多行总闸）。
     *
     * ## 为什么要合并
     *
     * 旧模型是「总闸 × 三选模式」，但自 2026-09-17 起"未唱行一律不显示"，
     * 三选里有两个**内容完全相同** → **两个设置项只表达了两种状态**。
     * 用户原话：「**取消多行歌词设置**，只保留选择**单行（主歌词）**、
     *   **双行（主+副）**的设置」。合并后**选项数 = 真实状态数**。
     *
     * ## 与"已唱歌词"的关系
     *
     * 旧 `PREV_AND_CURRENT` / `THREE_LINES` 会显示"刚唱完的那句"。
     * 用户明确要求取消 → 本枚举里**没有**这个状态。
     * （注意：逐字高亮里的"已唱部分"仍在 —— 那是同一行内的进度染色，不是一行。）
     *
     * ## `rows` 的含义
     *
     * **主歌词**占的行数。恒为 1 —— 双行的第二行是**副歌词**，由
     * [drawTranslationLine] 单独绘制、单独占高（见 [islandHeightFor]）。
     * ⚠️ 这里用字面量 `1` 而不是 `IslandWindow.SINGLE_ROW`：本枚举是**顶层声明**，
     *   看不见 `IslandWindow` 的 companion（那正是"把常量放在最内层作用域"的代价）。
     *   两者语义相同 —— `SINGLE_ROW = 1`。
     */
    internal enum class LineCount(val wire: String, val rows: Int) {
        /** 单行：只显示当前主歌词。 */
        SINGLE("SINGLE", 1),

        /**
         * 双行：主歌词 + **副歌词（译文）**。
         *
         * ⚠️ 主歌词本身仍是 1 行 —— 第二行是译文，其高度与绘制由
         *   `layout.translation.enabled` 与 [drawTranslationLine] 负责。
         *   故这里的 `rows` 也是 `1`（见枚举 KDoc 关于字面量的说明）。
         */
        DUAL("DUAL", 1),
        ;

        companion object {
            /**
             * 解析设置值。**未知值回退默认而不是抛异常** —— 配置可能被手改坏，
             * 而"配置坏了就崩渲染"是不可接受的（项目铁律：坏配置必须收敛）。
             *
             * ⚠️ 旧值 `CURRENT_AND_NEXT` / `PREV_AND_CURRENT` / `THREE_LINES`
             * 会全部落到回退分支 → `SINGLE`。这是**有意的迁移**：
             * 用户升级后看到单行，需要自己去设置里选双行。
             * 反过来（把旧值映射成 DUAL）会让"没想要双行"的用户凭空多一行。
             */
            fun parse(raw: String?): LineCount =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
                    ?: SINGLE
        }
    }

    /**
     * `layout.content_align` —— 歌词在词岛内的**水平对齐**。
     *
     * ## 为什么要有这个枚举（2026-09-16）
     *
     * 用户投诉「SPW 插件配置页中颇多选项无法修改实际设置」。逐键扫描确认
     * `layout.content_align` 是**十项死设置之一**：注册表里有、设置页有下拉框、
     * 但**渲染层从没读过它** —— 选左/中/右，画面零变化。
     *
     * ## 为什么做成枚举而不是直接用字符串
     *
     * 设置值是字符串，而渲染层要的是"把 X 算在哪"。中间必须有**一处**把字符串
     * 收敛成类型 —— 否则每个使用点各自 `when (raw)`，改一处漏一处。
     * 与 [LineCount] 同一套做法（含未知值回退默认，见其 `parse`）。
     */
    internal enum class Align(val wire: String) {
        /** 左对齐。 */
        LEFT("LEFT"),

        /** 居中（**默认**，与改动前的硬编码行为一致）。 */
        CENTER("CENTER"),

        /** 右对齐。 */
        RIGHT("RIGHT"),
        ;

        companion object {
            /** 未知值回退 [CENTER]（= 改动前的行为），不抛异常。理由同 [LineCount.parse]。 */
            fun parse(raw: String?): Align =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: CENTER
        }
    }

    /**
     * `layout.noLyrics.mode` —— **当前曲目没有歌词时**词岛怎么办。
     *
     * ## 为什么要有它（2026-09-16）
     *
     * 与 [Align] 同因：`layout.noLyrics.mode` 是**十项死设置之一** ——
     * 设置页能选，渲染层从没读过。改动前无论选什么，行为都固定是"什么都不画"
     * （= [HIDE_LINE]），因为 `drawLyricSlots` 在 `present.isEmpty()` 时直接 return。
     *
     * ## 三档的实际差别
     *
     * | 值 | 行为 |
     * |---|---|
     * | [HIDE_LINE] | 不画歌词（改动前的行为）。窗口仍在，可拖动、可右键 |
     * | [PLACEHOLDER] | 画一行占位文本，让"这里有个歌词窗但没歌词"可见 |
     * | [HIDE_ISLAND] | **整个词岛隐藏** —— 无歌词时不留悬浮件（纯音乐场景） |
     *
     * ⚠️ [HIDE_ISLAND] 与"没有曲目"不同：它是**有曲目但无歌词**时的隐藏，
     * 由 [IslandWindow.applyVisibility] 处理（不是绘制层 —— 隐藏窗口是生命周期动作）。
     */
    internal enum class NoLyricsMode(val wire: String) {
        /** 隐藏歌词行（**默认**，= 改动前的行为）。 */
        HIDE_LINE("HIDE_LINE"),

        /** 显示占位文本（配合 [Appearance.showTrackTitle] 可显示曲名）。 */
        PLACEHOLDER("PLACEHOLDER"),

        /** 隐藏整个词岛。 */
        HIDE_ISLAND("HIDE_ISLAND"),
        ;

        companion object {
            /** 未知值回退 [HIDE_LINE]（= 改动前的行为），不抛异常。理由同 [LineCount.parse]。 */
            fun parse(raw: String?): NoLyricsMode =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: HIDE_LINE
        }
    }

    /**
     * 三槽位模型里的一格：行对象（可为 null = 该槽不存在）+ 它是不是"当前行"。
 *
 * 定义在**文件级**而不是 `IslandPanel` 内部：Kotlin 不允许在 `inner class`
 * 内嵌套 `class`（"Class is prohibited here"，编译实测），而 `IslandPanel`
 * 是 `inner class`（它要访问外层的 [IslandWindow.renderCache] 等状态）。
 * 做成 `private` 顶层类型即可满足"只在本文件内可见"的封装需求。
 */
private data class Slot(val line: LyricLine?, val isCurrent: Boolean)
