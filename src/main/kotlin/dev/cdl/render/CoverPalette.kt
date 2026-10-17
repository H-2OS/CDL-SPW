// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color

/**
 * 「颜色来源」解析 —— 三个 `*_color_mode` 设置项（歌词 / 背景 / 频谱）的**唯一判定点**。
 *
 * ## 三个键的语义
 *
 * | 键 | `manual` | `cover` |
 * |---|---|---|
 * | `main_lyric.color_mode` | 用 `main_lyric.fill.*` 里的显式颜色 | 歌词色由封面主色派生 |
 * | `background.color_mode` | 用内置深灰底 `#14161A` | 背景色由封面主色派生 |
 * | `spectrum.color_mode` | 用内置频谱色 `#84D8BC` | 频谱色由封面主色派生 |
 *
 * ## 为什么不能"直接用封面主色"
 *
 * 封面主色是**任意**的：可能是一个饱和度 1.0 的纯红（当歌词色会刺眼、
 * 边缘还会因次像素渲染发彩边），也可能是一个明度 0.05 的近黑
 * （当歌词色会**完全看不见**）。直接拿它当强调色，等于把可读性交给封面设计者。
 *
 * 故本类把主色**只当色相**用，饱和与明度由**用途**决定：
 *
 * - [brightFrom]（歌词 / 频谱）：明度拉满 `1.0`，饱和压到 ≤ [BRIGHT_MAX_SATURATION] ——
 *   在深色背景上永远清晰，且不会因高饱和而刺眼；
 * - [darkFrom]（背景）：明度压到 [BACKDROP_BRIGHTNESS]（0.16）—— 这是"深色底"，
 *   与既有内置底色 `#14161A`（明度约 0.10）同量级，保证白字仍然可读。
 *
 * 这套"保色相、改饱和与明度"的派生规则取自原实现 0.8.9 的 `ui/IslandPalette.kt`
 * （它的 `bright` / `dark` 两个派生式与这里的三个数值一致）—— 见 `NOTICE`。
 * **本类不复用它的代码**：原实现把三个颜色与三个布尔设置**写在一个 `from()` 里**，
 * 而本项目要的是"每个键独立解析、manual 逐像素不变"，故拆成 [resolve] 一个泛型入口。
 *
 * ## `manual` 路径为什么必须逐像素不变
 *
 * 这是本任务书的硬要求，也是**用户可验证**的：三个键在用户当前配置里都是 `manual`，
 * 若解析路径哪怕只改动一个通道值，用户会立刻看到"我没开这个开关，颜色却变了"。
 *
 * 结构上的保证是 [resolve]：`mode != COVER` 时**原样返回**传入的 `manual` 对象
 * （不是"重新构造一个同色对象"）—— 于是"manual 不受影响"不是靠测试覆盖，
 * 而是**没有代码路径能改它**。
 *
 * ## 为什么是 `object` 而不是 `internal object`（与项目既有取舍一致）
 *
 * 理由同 `cover/CoverColorExtractor`：本类的"manual 逐像素不变"与"cover 回退"
 * 两条契约必须由离线探针直接断言，而探针是独立编译单元、看不到 `internal`。
 * 这与 `window/HoverState` / `window/PlaybackControls` 被 `ControlsProbe` 直接验证
 * 是同一个取舍。
 */
object CoverPalette {

    /** `*_color_mode` 的取值：用显式配置的颜色。 */
    const val MANUAL = "manual"

    /** `*_color_mode` 的取值：跟随封面取色。 */
    const val COVER = "cover"

    /**
     * `spectrum.color_mode` 的取值：**随音量流光**（2026-10-02 新增）。
     *
     * ★ 语义 = 业界 [audioMotion] 的 `Level` Bar Color Mode
     * —— 「**按当前振幅取色，音量越高颜色越「烫」**」。
     *
     * ★ 只有 `spectrum.color_mode` 用这个值（`background` / `border` / `island.border`
     * 那三处**没有**这一档）—— 因为它需要"逐柱的振幅"，而那些部件没有逐单位量。
     *
     * ★ **基色** = `spectrum.color`（手动色）：本档是"**怎么用**这个色"，
     * 不是"色从哪来" —— 派生规则见 `render/SpectrumLevelColor.kt`。
     */
    const val LEVEL = "level"

    /**
     * `spectrum.color_mode` 的取值：**封面多色流光**（2026-10-11 新增）。
     *
     * ★ 语义 = 把 `CoverColorExtractor.palette(image, n)` 取到的**多个**封面色
     * 沿**柱序**铺成一条连续渐变，相位可推进（= 流光）。
     *
     * ## ★★ 为什么是**新档**，而不是让现有 `cover` 档变多色
     *
     * | # | 理由 |
     * |---|---|
     * | ① | **存量配置不得改观感** —— `cover` 档已存在，用户可能正在用；直接改它 = **悄悄改观感**（本项目硬纪律） |
     * | ② | **两条语义不同** —— `cover` = "取一个主色当强调色"（[brightFrom] 保色相压饱和）；本档 = "把整张封面的色彩铺成渐变" |
     * | ③ | **加档不动任何计数表** —— `check_derived_counts` 与 `EXPECTED_TOTAL` 数的都是**键** ⇒ 加档零 churn ✓（与 `gradient_style` 的 `FLOW` 档同一条理由） |
     *
     * ★ 只有 `spectrum.color_mode` 用这个值 —— 它是"逐单位取色"的档，
     * 与 [LEVEL] 同一族（都需要"逐柱"这个维度）。歌词 / 背景 / 边框那三处
     * 没有"逐单位"量，故不提供本档。
     *
     * ★ 取色规则见 `render/SpectrumFlowColor.kt`（纯函数，探针可断言）。
     */
    const val COVER_FLOW = "cover_flow"

    /**
     * `spectrum.color_mode` 的取值：**封面迷彩碎色块流光**（2026-10-14 新增）。
     *
     * ★ 语义 = 把封面的**多个**色铺成**离散的、宽度不等的色块**（柔边），相位可推进
     * （= 碎色块水平向右流动）。
     *
     * ## ★★ 与 [COVER_FLOW] 的唯一区别：**"色块"vs"连续渐变"**
     *
     * 用户报障（逐字）：「多色流光目前的呈现是**竖色条水平向右**，能否做到类似
     * **迷彩样式的碎色块**，在呈现上实现**无规则的颜色出现规律**水平向右？」
     *
     * | 档 | 色相随柱序 | 观感 |
     * |---|---|---|
     * | [COVER_FLOW] | 连续线性插值 | 一条**平滑**的彩色带水平移动 |
     * | 本档 | 离散热色块（宽不等、色号哈希给） | ★ **迷彩/拼贴**——一块块颜色水平流动 |
     *
     * ## ★★ 为什么是**新档**，而不是改 [COVER_FLOW]
     *
     * ★ 与 [COVER_FLOW] 加档时**同一条理由**（见它的 KDoc）：
     * **存量配置不得改观感** —— `cover_flow` 已存在，用户可能正在用；
     * 直接把它改成碎色块 = **悄悄改观感**（本项目硬纪律）✗
     * ⇒ 两档并存，用户在设置里**自己切** ✓（A0：正解是"能，且在设置里能切"）
     *
     * ★ 取色规则见 `render/SpectrumFlowColor.kt` 的 [spectrumCamoColorAt]（纯函数，探针可断言）。
     */
    const val COVER_CAMO = "cover_camo"

    /**
     * [brightFrom] 的饱和度上限。
     *
     * 取 0.55：这是"颜色明显可辨"与"不刺眼"的折中。纯色封面的主色饱和度常常接近 1.0，
     * 那样的歌词色在深底上会产生视觉振颤（尤其细笔画的中文字形）。
     * 与原实现 `IslandPalette` 的 `bright` 同值。
     */
    private const val BRIGHT_MAX_SATURATION = 0.55f

    /**
     * [darkFrom] 的饱和度上限。比 [BRIGHT_MAX_SATURATION] 略高（0.65）。
     *
     * 理由：背景色明度很低（[BACKDROP_BRIGHTNESS]），在低明度下同样的饱和度
     * 看起来**更灰**，故允许它略高一点，让"跟随封面"的背景确实能看出色调。
     * 与原实现 `IslandPalette` 的 `dark` 同值。
     */
    private const val DARK_MAX_SATURATION = 0.65f

    /**
     * [darkFrom] 的明度。0.16 ≈ 亮度 41/255。
     *
     * 与内置底色 `#14161A`（明度约 0.10）同量级，略亮一点 ——
     * 因为"跟随封面"时用户是**主动**想要看到封面色调，太暗会让色相看不出来。
     * 同时它仍足够暗，白色歌词在其上的对比度远超 WCAG AA 的 4.5:1。
     */
    private const val BACKDROP_BRIGHTNESS = 0.16f

    /**
     * 由封面主色派生**亮色**（歌词色 / 频谱色）：保色相、压饱和、拉满明度。
     *
     * @param coverRgb 打包 RGB（来自 `CoverColorExtractor.dominant`）
     */
    fun brightFrom(coverRgb: Int): Color {
        val hsb = hsbOf(coverRgb)
        return Color.getHSBColor(hsb[0], hsb[1].coerceAtMost(BRIGHT_MAX_SATURATION), 1f)
    }

    /**
     * 由封面主色派生**暗色**（背景色）：保色相、压饱和、压到很低明度。
     *
     * 为什么不直接"把主色乘一个系数"（如 ×0.16）：那是**乘法**，
     * 对深色主色几乎不变（0.05 × 0.16 = 0.008，与黑无异），对浅色主色又偏亮。
     * 改用 HSB 的"直接指定明度"后，任何主色都被映射到**同一个亮度档位**，
     * 于是"背景有多暗"是**可控**的，与封面本身的明暗无关 —— 这是可读性的前提。
     */
    fun darkFrom(coverRgb: Int): Color {
        val hsb = hsbOf(coverRgb)
        return Color.getHSBColor(hsb[0], hsb[1].coerceAtMost(DARK_MAX_SATURATION), BACKDROP_BRIGHTNESS)
    }

    /**
     * 按亮度暗化一个颜色（只作用于 RGB，alpha 保持不变）。
     *
     * 用途：跟随封面时，**未唱歌词**取已唱色的暗化版本 —— 两者保持同一色相，
     * "已唱 / 未唱"的对比就仍然成立（若未唱色仍用手动色，会出现
     * "已唱是封面色、未唱是灰白"的割裂观感）。
     */
    fun dim(color: Color, factor: Float): Color {
        val f = factor.coerceIn(0f, 1f)
        return Color(
            (color.red * f).toInt().coerceIn(0, 255),
            (color.green * f).toInt().coerceIn(0, 255),
            (color.blue * f).toInt().coerceIn(0, 255),
            color.alpha,
        )
    }

    /**
     * 解析一个 `*_color_mode`：`cover` 且有可用主色时用 [derived]，否则**原样**返回 [manual]。
     *
     * ## 为什么把"回退"写在这里（而不是让每个调用点自己判空）
     *
     * 三个键各自有回退需求（无封面、提取失败），而回退规则**完全一样**。
     * 分散在三处判定的话，"某处忘了判空"会直接抛 NPE（或画出一个 null 颜色）——
     * 而这类缺陷只在"这首歌没有封面"时出现，正是最容易被漏测的组合。
     * 收成一个入口后，"回退"是**唯一**的出口之一，漏不掉。
     *
     * @param mode 设置值。**未知值也走 manual**（与注册表 LIST 项的收敛语义一致，
     *   且万一将来加了新模式而我们没更新这里，退回手动色总比不画强）
     * @param coverRgb 封面主色；`null` = 没有封面或提取失败 → 回退 [manual]
     */
    fun resolve(mode: String, manual: Color, coverRgb: Int?, derived: (Int) -> Color): Color =
        if (mode == COVER && coverRgb != null) derived(coverRgb) else manual

    /** 取打包 RGB 的 HSB（复用同一个临时数组，避免每帧为一次转换分配数组）。 */
    private fun hsbOf(rgb: Int): FloatArray {
        val red = (rgb shr 16) and 0xFF
        val green = (rgb shr 8) and 0xFF
        val blue = rgb and 0xFF
        return Color.RGBtoHSB(red, green, blue, scratchHsb)
    }

    /**
     * HSB 转换的复用数组。
     *
     * ⚠️ 与 `RenderCache` / `SpectralPainter` 的复用容器同一约定：**只在 EDT 上使用**。
     * 本对象只被渲染路径（`IslandWindow` 的绘制与解析）调用，那条路径全在 EDT 上
     * （见 `IslandWindow` 的线程纪律）。若将来要从别的线程调用，必须改成局部数组。
     */
    private val scratchHsb = FloatArray(3)
}

/**
 * ★★★ 2026-10-12（**BUG-181**）：**把调色板补足到至少 [n] 色** —— ★ 唯一出口。
 *
 * ## 用户报障（逐字）
 *
 * > 「**设置随封面取色流光无效，呈现默认色，但改成自选色或者从封面取色又会变**」
 *
 * ## ★★ 根因链（三段，缺一不可）
 *
 * 1. ★ `CoverColorExtractor.palette(image, 4)` **不保证返回 4 个** ✗ ——
 *    ★★ 它的契约逐字写着：「超过实际簇数 ⇒ **返回实际那么多**」「仍不足 ⇒ **返回短列表**」✗
 *    ⇒ ★ 低彩度封面（如暗紫 `#3E3347`）可能只出 **1 个**色 ✓
 * 2. ★★ `spectrumFlowColorAt` 有一条**退化判据**：`if (n < 2 || count <= 1) return baseArgb` ✗
 *    ⇒ ★★ **色数 < 2 ⇒ 原样返回基色** ⇒ 用户看到的是**单色**（= `spectrum.color` 的默认青）✗
 * 3. ★★★ **整条链【静默】**：★ 不报错、不告警 ⇒ ★ 表现就是"选了多色档，看着跟没选一样" ✗
 *
 * ## ★ 修法：**从已有色派生**（不引入新数据源）
 *
 * ★ 只有 1 个色时，**它本身就是最好的素材** —— ★ 派生出"更亮 / 更暗"两份 ✓
 * ★★ 这样"封面多色流光"**在任何封面上都有效**（至少是**同色系的双色流动**）✓
 * ★ 且**色数 ≥ n 时本函数是恒等**（逐位返回原列表）⇒ ★ **对现状零影响** ✓
 *
 * ⚠️ **为什么不去改 `palette()` 的契约**：★ 它同时服务 `dominant` 与背景 `FLOW` ✗ ——
 *   ★ 改它的返回条数会**同时**动那两条路径 ✗ ⇒ ★ 本处**只在频谱流光的取值点补足** ✓
 *
 * @param colors 原始调色板（可能为空 / 短）
 * @param n 目标色数（`FLOW_PALETTE_COLORS`）
 * @return 长度 ≥ `min(n, max(colors.size, 2))` 的列表；★ 输入为空 ⇒ **原样返回空**
 *   （★ 空 = "确实没有封面" ⇒ 应当走单色回退，**不许**凭空造色 ✗）
 */
fun augmentPalette(colors: List<Int>, n: Int): List<Int> {
    if (colors.isEmpty() || n <= 0) return colors
    if (colors.size >= n) return colors
    val out = ArrayList<Int>(n)
    out.addAll(colors)
    // ★ 派生两份：★ 提亮 / 压暗（保持色相与饱和 ⇒ "同色系流动"）✓
    //   交替取源色，避免"全部由一个色派生"导致整条只剩两种明度。
    var i = 0
    while (out.size < n) {
        val src = colors[i % colors.size]
        out.add(if (out.size % 2 == 0) lightenArgb(src, 1.35f) else lightenArgb(src, 0.72f))
        i++
        // ★ 防御：派生值若与已有色完全重复（如纯黑提亮失败）⇒ 换一档，避免死循环。
        if (i > 64) break
    }
    return out
}

/** 按倍率调整明度（保持 alpha）。★ 与 `ColorPicker` 的明度通道同口径。 */
private fun lightenArgb(argb: Int, factor: Float): Int {
    val a = (argb ushr 24) and 0xFF
    val r = ((argb shr 16) and 0xFF) * factor
    val g = ((argb shr 8) and 0xFF) * factor
    val b = (argb and 0xFF) * factor
    fun clamp(v: Float): Int = v.toInt().coerceIn(0, 255)
    return (a shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
}
