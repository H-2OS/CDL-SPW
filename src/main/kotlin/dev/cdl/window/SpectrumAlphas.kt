// SPDX-License-Identifier: AGPL-3.0-only
//
// 频谱"进度色差"的三个 alpha —— **唯一计算点**（2026-09-21 从 `IslandWindow.kt` 抽出）。
//
// ## 为什么单独一个文件
//
// 它们是**纯函数 + 纯数据**（不碰 `Graphics2D`、不读窗口状态），而 `IslandWindow.kt`
// 是 8000+ 行的窗口实现。抽出来有两个实际好处：
//   1. 它们是 `probe` source set 能**直接断言**的契约（探针看不到 `internal`，
//      故这类"要验证的公式"必须落在**公开顶层**）；
//   2. 放在窗口文件里时它们被挤在类成员之间，位置一旦被后续编辑挪动就会变成
//      "局部声明"（Kotlin 不允许局部 `data class`）—— 顶层文件没有这个风险。
//
// 包名与 `IslandWindow.kt` 相同（`dev.cdl.window`），故窗口侧调用点
// **不需要任何 import**。

package dev.cdl.window

/**
 * 把 [alphaScale]（`0f..1f`）乘进打包 ARGB 的**高 8 位**，返回新的打包值。
 *
 * ## 为什么需要它（2026-09-21 修「频谱开着但不显示」）
 *
 * `spectrum.progress_tint_alpha` 的语义是「**未播放段**相对已播放段压暗多少」。
 * 上一版把它乘进 `SpectralPainter.paint` 的 `opacity` —— 而 `opacity` 是**整帧**的
 * `AlphaComposite`，同时罩住「未播放段底图」**和**「已播放段进度染色层」。
 * 于是差值 100% 时 `opacity = 1 × (1 − 1) = 0`，**整条频谱一起消失** ——
 * 这正是用户报的「频谱设置项处于开启状态，词岛里不显示频谱」。
 *
 * 正解：把"未播放段压暗"乘进**未播放段自己的颜色**，让 `opacity` 只承担
 * "整体不透明度"。而底图在 `spectrum.fade_top = false` 时走
 * `g.color = cache.color(packed)` 这条路径 —— 那是**唯一**没有 alpha 参数通道的
 * 着色路径（其余三条渐变笔刷都收 `alphaScale`），故必须从打包值的高 8 位入手。
 *
 * ## 传进来的 `alphaScale` 是**相对系数**，不是绝对不透明度
 *
 * 它来自 [SpectrumAlphas.unplayed]（= `1 − 差值`），**不含**整体不透明度 ——
 * 后者由 `SpectralPainter.paint` 的整帧 `AlphaComposite` 独自承载。
 * 合成后的有效 alpha = `本函数给出的 alpha × frameOpacity`。
 * 若这里再乘一次整体不透明度，就会被**乘两次**（`base²`）——
 * 见 [SpectrumAlphas] 的 KDoc。
 *
 * ## 为什么基准写死 255，而不是取 `packed` 自带的 alpha
 *
 * 与 `IslandWindow` 的 `verticalFadePaint` / `solidProgressPaint` 的既有约定一致 ——
 * 它们都是 `255 × SPECTRUM_BACKGROUND_ALPHA × alphaScale`，**忽略颜色自带的 alpha**。
 * 频谱底图的不透明度由设置项决定、与色值无关；若这里改成读 `packed` 的 alpha，
 * 同一帧的两条路径就会对"透明度从哪来"给出两种解释（本项目反复踩过的漂移形态）。
 */
fun spectrumLayerArgb(packed: Int, alphaScale: Float): Int {
    val a = (255f * alphaScale.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return (a shl 24) or (packed and 0x00FFFFFF)
}

/**
 * 进度色差**两段各自的填充色**（打包 ARGB，高 8 位即该段的相对 alpha 系数）。
 *
 * @property unplayed 未播放段的颜色 / 笔刷。
 * @property played 已播放段的颜色 / 笔刷。
 */
data class SpectrumSegmentColors(val unplayed: Int, val played: Int)

/**
 * 由频谱本色 `packed` 与三个 alpha 算出两段各自的填充色 —— **唯一计算点**。
 *
 * ## ★★ 2026-09-22：为什么必须是"同一个颜色、只差 alpha"（用户第 ② 条）
 *
 * 用户原话：
 *
 * > 「进度色差我不是说了用**已播放与未播放频谱的透明度的差值**吗？
 * >   怎么滑杆到 0 还有差别的。（我知道主频谱受它的透明度控制，
 * >   那么差值要**基于被控制的这个透明度**灵活计算）」
 *
 * ### 根因：差别**不是**来自 alpha，而是来自**颜色**
 *
 * `progress_tint_alpha = 0` 时 `alphas.played == alphas.unplayed == 1f`
 * —— 两段的 alpha **完全相等**（`SpectrumVisibilityProbe` §③ 早已断言这一点）。
 * 但改动前已播放段用的是 `local.sungColorFor(coverRgb)`（**已唱歌词色**），
 * 未播放段用 `local.spectrumColorFor(coverRgb)`（**频谱本色**）——
 * 那是**两个独立设置项**，颜色差**恒存在**，与差值滑杆无关。
 * 于是"滑杆拖到 0 仍看得出分界" —— 用户看到的是**色差**，不是透明度差。
 *
 * ### 判据（本函数把用户语义钉成可验证的定义）
 *
 * ```
 * tintAlphaPct = 0   ⇒ unplayed == played（**逐位相同**，含 RGB 与 alpha）
 * tintAlphaPct = 100 ⇒ 两段的 alpha 差 = 该段的基准（`frameOpacity` 承担）
 * ```
 *
 * 唯一允许的差异通道是**高 8 位**（alpha）—— RGB 三位在两段上必须逐位相同。
 *
 * ## 为什么抽成公开顶层函数
 *
 * 与 [spectrumAlphas] 同一条理由：它是**纯函数 + 纯数据**，
 * 抽出来之后 `SpectrumVisibilityProbe` §⑧ 才能直接断言
 * "滑杆到 0 两段必须逐位相同" —— 而这条断言**必须能红**
 * （把颜色改回"已唱色"就红），否则它只是一个恒真的空护栏。
 */
fun spectrumSegmentColors(packed: Int, alphas: SpectrumAlphas): SpectrumSegmentColors =
    SpectrumSegmentColors(
        unplayed = spectrumLayerArgb(packed, alphas.unplayed),
        played = spectrumLayerArgb(packed, alphas.played),
    )

/**
 * 频谱"进度色差"的三个 alpha —— **唯一计算点**。
 *
 * ## ⚠️ 关键：`frameOpacity` 是整体不透明度的**唯一载体**
 *
 * `SpectralPainter.paint` 先设 `g.composite = AlphaComposite(SRC_OVER, opacity)`，
 * 再逐段装自己的笔刷（笔刷 alpha 里带着 `alphaScale`）。`SRC_OVER` 下两者**相乘** ——
 * 这是 `Graphics2D` 的真实算术，不是"后设的覆盖先设的"：
 *
 * ```
 * 某段的**有效** alpha = frameOpacity × 该段的 alphaScale
 * ```
 *
 * 于是"整体不透明度"（`opacity_pct`）**只能出现在乘法的一侧**。若 `frameOpacity`
 * 和段系数**都**带 `base`，整体不透明度就被**乘两次**（`base²`）：
 * `opacity_pct = 75` 时只剩 56% 的亮度，而 `100` 时被掩盖（`1² = 1`）——
 * 一个"默认值下看不出、调低就偏暗"的缺陷。
 *
 * 故本类型的约定是：
 *   · [frameOpacity] = 整体不透明度（**唯一**承载它）
 *   · [unplayed] / [played] = **相对系数**（相对"已播放段应当有多亮"），不含 `base`
 *
 * ## 三个字段的落点
 *
 * | 字段 | 施加在哪 | 取值 |
 * |---|---|---|
 * | [frameOpacity] | `SpectralPainter.paint` 的 `opacity`（**整帧** `AlphaComposite`） | 整体不透明度 |
 * | [unplayed] | **未播放段自己的颜色 / 笔刷** | `1 − 差值` |
 * | [played]   | **已播放段自己的颜色 / 笔刷** | `1`（不额外压暗） |
 *
 * ## ⚠️ 为什么"未播放段压暗"**不能**走整帧 `opacity`
 *
 * `opacity` 是**整帧**的 `AlphaComposite`，它同时罩住「未播放段底图」**和**
 * 「已播放段进度染色层」两遍绘制（见 `SpectralPainter.paint` 的 ③ 与 ④）。
 *
 * 2026-09-20 的第一版把"未播放段的绝对 alpha"（= `整体 × (1 − 差值)`）传给了
 * `opacity`，于是 `spectrum.opacity_pct = 100` + `spectrum.progress_tint_alpha = 100` 时
 * `frameOpacity = 1 × (1 − 1) = 0` → **整条频谱 alpha=0 → 完全不显示**。
 * 用户报的「**频谱设置项处于开启状态，词岛里不显示频谱**」正是这个 ——
 * 而真机配置恰好就是这两个 100，即**该缺陷必然触发**（与数据链路、帧驱动无关，
 * 那两条都已由真机日志排除）。
 *
 * ## 为什么 `tintActive` 是入参而不是内部推导
 *
 * "此刻会不会画出已播放段"取决于 `playbackProgress` 的返回值
 * （`NO_PROGRESS` / `0f` 时**没有**已播放段），那是**时间轴**的知识，
 * 本函数不该去猜。调用方算好传进来，本函数只回答"给定这个前提，三个系数各是多少"。
 * 于是"未播放段被压暗而没有任何东西补位"（= 整条变淡）这条路径也被封住。
 */
data class SpectrumAlphas(
    /** 未播放段的**相对系数**：`1 − 差值`。乘进**未播放段自己的颜色 / 笔刷**。 */
    val unplayed: Float,
    /** 已播放段的**相对系数**：恒为 `1f`（不额外压暗）。乘进**已播放段自己的颜色 / 笔刷**。 */
    val played: Float,
    /** 整帧 `AlphaComposite`。**恒等于整体不透明度**，与差值无关。 */
    val frameOpacity: Float,
)

/**
 * 由三个设置 / 时间轴输入算出 [SpectrumAlphas]。见该类型的 KDoc（含"为什么
 * `frameOpacity` 是整体不透明度的唯一载体"与"有效 alpha = 两者相乘"的完整论证）。
 *
 * @param opacityPct 整体不透明度百分比（`0..100`）。主频谱取 `spectrum.opacity_pct`，
 *   副频谱取 `spectrum.secondary.opacity_percent`（两条频谱各有自己的基准）。
 * @param tintAlphaPct 「已播放与未播放的透明度**差值**」百分比（`0..100`）。
 * @param tintActive 此刻**确实会画出已播放段**（`progress` 落在 `(0, 1]`）。
 *   为 `false` 时差值**不生效** —— 否则会出现"未播放段被压暗、已播放段不存在"，
 *   即整条频谱变淡甚至消失，而那与用户的意图（表达进度）完全相反。
 */
fun spectrumAlphas(opacityPct: Int, tintAlphaPct: Int, tintActive: Boolean): SpectrumAlphas {
    val base = opacityPct.coerceIn(0, 100) / 100f
    // ★★★ 2026-10-12（**BUG-174 的更正**）：★ **恢复**"已播放 / 未播放"的透明度差 ✓
    //
    // ## ★★★ 为什么改回来（★ 我上一批**改过头了**）
    //
    // ★ 我上一批把 `diff` 恒设 0，依据是用户 10-12 的第一句：
    //   > 「频谱显示进度，即使值为0。但实际已播放与未播放仍存在透明度差异。」
    // ★★ 而用户 10-12 的**第二句**把范围说清了：
    //   > 「**右侧三型频谱不启用透明度差表播放进度。**」
    // ⇒ ★★★ **"不启用"只针对【右侧三型插槽频谱】**，**主带照旧** ✓
    //
    // ## ★★ 为什么"恒 0"会把进度**彻底弄没**（不是"变淡"，是"消失"）
    //
    // ★ `spectrumSegmentColors(packed, alphas)` 的两段颜色**只差高 8 位（alpha）**，
    //   ★★ **RGB 三位逐位相同**（见该函数的 KDoc：「唯一允许的差异通道是**高 8 位**」）✗
    // ⇒ ★ `diff = 0` ⇒ 两段**逐位相同** ⇒ ★★ 两趟绘制画出**同一个颜色** ⇒
    //   ★★★ **进度条看不见了**（连"变淡"都不是）✗
    //
    // ★ 本函数**只管主带** ✓ —— 插槽的"不启用"由调用方另传一份
    //   （见 `IslandWindow` 的 `slotAlphas`）✓
    val diff = if (tintActive) tintAlphaPct.coerceIn(0, 100) / 100f else 0f
    return SpectrumAlphas(
        // ⚠️ 这里是**相对**系数（不含 `base`）—— `base` 由 `frameOpacity` 独自承载。
        //    写成 `base * (1f - diff)` 会让整体不透明度被乘两次（`base²`）。
        unplayed = 1f - diff,
        played = 1f,
        // ⚠️⚠️ **不能写成 `base * (1f - diff)`** —— 那正是 2026-09-21 修掉的缺陷：
        //    它是**整帧** alpha，写成未播放段的 alpha 会让整条频谱一起消失。
        frameOpacity = base,
    )
}
