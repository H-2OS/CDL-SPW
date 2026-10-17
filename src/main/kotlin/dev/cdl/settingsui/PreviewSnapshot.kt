// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.render.CoverPalette
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingsRuntime
import dev.cdl.window.BACKGROUND_BASE_RGB
import kotlin.math.roundToInt

/**
 * \`previewTargetKey\` 是否指向 \`prefix\` 描述的那一项（示例预览里**染色目标**的**唯一命中判据**）。
 *
 * ## ⚠️ 2026-09-21：本文件的头部曾被一次误操作损坏
 *
 * 当时用 \`cat >>\` 往本文件追加一个常量，结果**文件头（package / import / 本函数签名与 KDoc）
 * 被覆盖丢失**，而中文注释也因编码不匹配变成乱码。
 *
 * **恢复方式**：从 \`fun root(…)\` 往下的**代码体未受影响**，故只重建了文件头。
 * 本 KDoc 是**重建的精简版**，不是原文 —— 判据逻辑本身逐字未变（见下方函数体）。
 *
 * ## 判据为什么是取共同根而不是 \`startsWith\`
 *
 * \`ToolWindows\` 传进来的键有三种形态：颜色组的**组前缀**（\`main_lyric.fill\`）、
 * 单键的**完整键**（\`spectrum.color\`）、通道键（\`main_lyric.fill.color.r\`）。
 * 而各处的判据写的是**完整键**。原先的 \`targetKey.startsWith(prefix)\` 对颜色组**恒为 false**，
 * 于是颜色组在示例里**永远不染色** —— 这正是 BUG 记录里只有单键能染色的成因。
 * 改成取共同根（\`root()\`）之后两类形态都能命中。
 */
// ⚠️ 2026-09-21：**必须 public，不能改 internal** ——
//    探针（`probe/ColorPickerProbe.kt`）是**独立编译单元**，看不到 `internal` 的顶层函数。
//    本函数此前是 public，`ColorPickerProbe` 因此绿过；
//    改成 `internal` 的那一刻，那条回归护栏**直接编译不过**（已真机发生）：
//      `e: ColorPickerProbe.kt:40:35 Cannot access 'fun previewTargetMatches(...)': it is internal in file.`
//    而后果不是"少一条断言"，是 **`ColorPickerProbe` 整个探针编译失败 → 全量门禁整条挂掉**
//    （它在 `run_all_probes.sh` 里排得很前，后面全部连坐）。
//    它的唯一外部消费者就是探针 —— `internal` 买不到任何封装收益，却打死一条护栏
//    （那条护栏里有**反向护栏**：`main_lyric.fillX` **不得**命中 `main_lyric.fill`，
//      裸 `startsWith` 会误命中）。
//    → 需要"不对外"的**意图标注**就用 KDoc 说明，**不要**用编译器级可见性收窄。
fun previewTargetMatches(targetKey: String, prefix: String): Boolean {
    fun root(k: String): String = k
        .removeSuffix(".r").removeSuffix(".g").removeSuffix(".b")
        .removeSuffix(".a").removeSuffix(".lightness")
    val a = root(targetKey)
    val b = root(prefix)
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || a.startsWith(b + ".") || b.startsWith(a + ".")
}

/**
 * **标准副歌词比例** —— 预览与真机共用的唯一来源。
 *
 * ⚠️ 它必须与 `dev.cdl.window.TRANSLATION_SCALE` **同值**。
 * 定义在这里（而不是任一侧的 companion 里）是因为**两侧都要用它**：
 * `PreviewSnapshot` 用它把 `layout.translation.scale_percent` 折算成实际比例，
 * `IslandPreviewPane` 用它做字号兜底。
 *
 * 0.64 = 2026-09-21 定下的标准（原 0.7 在"主行有描边、译文无描边"下主次不分）。
 */
internal const val TRANSLATION_SCALE_DEFAULT = 0.64f

data class PreviewSnapshot(
    /**
     * **词岛形态** —— `SQUARE` / `ROUNDED_RECT` / `PILL` / `NOTCH`。
     *
     * ⚠️ 2026-09-21 补：它此前**没进快照**，而 `IslandPreviewPane` 里
     * `grep "shape.type"` **0 命中** —— 即预览**恒画圆角矩形**，**根本不读这个键**。
     * 用户原话：「词岛形态**没快照到"打开前"的设置**（打开前方角，进去看还是**圆角**）。」
     *
     * ⚠️ **根因不是时序**（快照是打开时冻结的，这一点本来就对）——
     * 是**快照里压根没有这个字段**，预览无从知道。故修法是"补字段 + 按它选形状"，
     * 而不是去动 capture 的时机。
     */
    val shapeType: String,
    /**
     * 圆角大小 —— **相对短边的百分比**（0..100），**不是像素**。
     *
     * ⚠️ 2026-09-21 更正：本字段原先的 KDoc 写的是"圆角半径（像素）"，而
     * `SettingRegistry.shape.corner_radius` 的语义是**百分比**（其 summary 与
     * `IslandWindow.islandShapeOf` 的 `shortSide * percent / 100` 都写明了这一点）。
     * 预览按"像素"用 → 词岛尺寸一变，圆角的**视觉比例**就与真机不同。
     */
    val cornerRadius: Int,
    /**
     * **角曲率风格** —— `ARC`（圆弧角）/ `SQUIRCLE`（连续曲率超椭圆）。
     *
     * ⚠️ 2026-09-25 第 2 期波次 1 补：与 [shapeType] 那次（2026-09-21）**同一条教训** ——
     *    快照里没有这个字段时，预览**无从知道**用户把角切成了「连续曲率」，
     *    于是**真机变了、预览没变**（用户会以为设置没生效）。
     *    ⇒ 修法同样是「**补字段 + 按它选形状**」，而不是去动 capture 的时机。
     */
    val cornerStyle: String,
    /**
     * 连续曲率的**指数 `n`**（`2..10`，默认 `4`）。仅 [cornerStyle] = `SQUIRCLE` 时生效。
     * 与真机同源（`shape.corner_n`）—— 预览的全部意义是"所见即真机所示"。
     */
    val cornerN: Int,
    // ── 2026-09-25 第 2 期波次 2：材质三层（与 `dev.cdl.window.Appearance` **同源**）────
    // ⚠️ 六项的默认值全部是"零视觉变化"（`0` / `NONE`），与注册表一致。
    //    预览的全部意义是"所见即真机所示" —— 少了这几个字段，
    //    用户调材质时会**真机变、预览不变**（与 `shapeType` / `cornerStyle` 同一类缺口）。
    /** 顶部高光强度（`material.highlight.strength`，0..100）。`0` = 关。 */
    val materialHighlightStrength: Int,
    /**
     * 背景渐变档（`material.fill.gradient_style`）：
     * `NONE` / `LINEAR` / `FLOW`（封面多色流光）/ `CAMO`（封面迷彩碎色流光）。
     *
     * ⚠️ 原 KDoc 只写 `NONE` / `LINEAR` —— **自 `FLOW`（2026-10-03）起就已陈旧**，
     * 2026-10-14 用户报障纠正时补全（`CAMO` 见 [SettingRegistry] 的 `gradient_style` 选项表）。
     */
    val materialGradientStyle: String,
    /** 渐变强度 %（`material.fill.gradient_tint_percent`，0..60）。`0` ⇒ 不画渐变。 */
    val materialGradientTintPercent: Int,
    /** 渐变角度 °（`material.fill.gradient_angle_deg`）。`0` = 左→右，`90` = 上→下，顺时针。 */
    val materialGradientAngleDeg: Int,
    /** 渐变止色（`material.fill.color_end`，打包 ARGB）。 */
    val materialGradientEndArgb: Int,
    /** 噪点幅度（`material.noise.amplitude`，0..20）。`0` = 关。 */
    val materialNoiseAmplitude: Int,
    /** 材质画质档（`material.quality`）：`OFF` / `LITE` / `FULL`。`OFF` ⇒ 三层全不画。 */
    val materialQuality: String,
    /** 岛内内容模糊半径（`material.content.blur_radius`，0..40）。`0` = 关。 */
    val materialBlurRadius: Int,
    /**
     * ★★ 液态玻璃 · **高光强度**（`material.liquid.glare_strength`，0..100）。
     *
     * ## ⚠️ 2026-10-01 补：本组是**同一族缺口的第四次**
     *
     * 前三次：[shapeType]（09-21）· [cornerStyle]（09-25）· [backgroundColorMode]（09-26）——
     * 每一次的形态**完全一样**：**新增了设置键、真机接了、快照里没有** ⇒ 预览**读不到**
     * ⇒ 用户拖滑杆时「**真机变、预览不变**」，他会以为设置没生效。
     *
     * 本组（液态玻璃 4 键）在 `migration/69` / `70` 落地时**完全没提预览**
     * （两份施工记录里 grep「预览」= **0 命中**）⇒ 是**漏接**，不是有意不做。
     *
     * ★ 两个后果**不一样**，都要说清：
     *   · 本项与 [materialLiquidDepthEffect] 的**默认值恰好等于面板值**（`60` / `true`）
     *     ⇒ 预览**画了**液态玻璃，但**不跟随**设置（拖滑杆没反应）；
     *   · [materialLiquidSurfaceAlpha] 默认 **`0` = 不画** ⇒ 预览**根本不画**表面蒙版。
     *
     * ⚠️ `material.liquid.shadow_strength`（投影）**不在本快照里** —— 它由
     *   `MaterialLayers.drawDropShadow` 消费，而**预览侧不画投影**（真机的调用点在
     *   `IslandWindow`，预览无对应物）。★ 若将来要在预览里画投影，**先加字段再接**。
     */
    val materialLiquidGlarePercent: Int,
    /**
     * ★★ 液态玻璃 · **立体感**（`material.liquid.depth_effect`）。
     *
     * `false` ⇒ 关掉菲涅耳边缘光（只剩高光，更扁平）。见 [materialLiquidGlarePercent] 的 KDoc。
     */
    val materialLiquidDepthEffect: Boolean,
    /**
     * ★★ 液态玻璃 · **表面蒙版**（`material.liquid.surface_alpha`，0..100）。`0` = 不画。
     *
     * ⚠️ 它的默认值（`0`）与**面板值**（`10`）**不同** ⇒ 漏接的后果比另两项更重：
     *   预览**完全不画**这一层（不是"画了但不跟随"）。见 [materialLiquidGlarePercent] 的 KDoc。
     */
    val materialLiquidSurfaceAlpha: Int,
    /**
     * ★★★ **D-1（2026-10-09）：GPU 后端开关**（`material.render.gpu_backend`，默认 `false`）。
     *
     * ## 为什么它**必须**进快照（与 [materialLiquidSurfaceAlpha] 同一族判据）
     *
     * 它是 [dev.cdl.render.MaterialLayers.gpuEnabled] 的**唯一输入** ——
     * 而那个字段是 `MaterialLayers` 这个 **object** 的成员，**不在 `Appearance` 里**。
     * ⇒ ★ 若预览不推它，用户在预览里切了 GPU 后端会**完全看不出区别**
     *   （预览走 CPU、真机走 GPU）—— 那正是 BUG-149 的形态：
     *   「**同一个开关，两个界面，其中一个对它无感**」。
     *
     * ## ⚠️ 与真机的取值路径**必须逐字相同**
     *
     * 真机 = `Appearance.from` 的 `bool("material.render.gpu_backend", false)`
     * （见 `IslandAppearance.kt`）⇒ ★ 本字段的默认值也**必须是 `false`**
     * （与注册表默认一致；不一致会比"不接"更隐蔽 —— 两边都"看起来生效"、取值却不同）。
     *
     * ★ 消费点：`IslandPreviewPane.paintComponent` 在调 `MaterialLayers.paint` **之前**
     *   推一次 `MaterialLayers.gpuEnabled`（与真机 `applyAppearance` 同一条约定：
     *   **变更时推一次，而不是每帧读设置**）。
     */
    val materialRenderGpuBackend: Boolean,
    /**
     * `background.color_mode` —— 岛体底色的**来源**：`manual` / `cover` / `cover_image`。
     *
     * ⚠️ **2026-09-26 补**：与 [shapeType]（09-21）/ [cornerStyle]（09-25）**同一族缺口**。
     *   此前快照里没有它，于是预览只能用**硬编码常量**当底色
     *   （`IslandPreviewPane` 里的 `Color(0x14, 0x16, 0x1A, bgAlpha)`）——
     *   用户改 `background.color`、或把 `color_mode` 切到 `cover` 时，
     *   **真机变、预览不变**（他会以为设置没生效）。
     *
     * ⇒ 修法同前两次：**补字段 + 按它取色**；且取色**直接调真机的公开纯函数**
     *   [dev.cdl.render.CoverPalette.resolve]，**不是两边各写一份公式**。
     */
    val backgroundColorMode: String,
    /**
     * `background.color` —— **手动档**底色（打包 ARGB）。
     *
     * 与真机 `Appearance.backgroundManualColor` 同源；缺省值取
     * [dev.cdl.window.BACKGROUND_BASE_RGB]（`#14161A`，**公开顶层常量**，
     * 不在这里抄字面量 —— 注册表默认值与该常量的相等性已由 `RegistryProbe` §8b.1 钉住）。
     *
     * ★★★ 2026-10-07 **更正（`migration/98` · C-4）**：原写「已由 `IslandPolicyProbe` 钉住」
     * —— ★ **引错了探针**（该探针里 0 命中）；真实护栏 = `RegistryProbe §8b.1`。
     */
    val backgroundColorArgb: Int,
    /**
     * `background.cover.angle_deg` —— 「封面图」档的**旋转角度**（0..360）。
     *
     * ⚠️ **同样是"补字段"而非"预览自己猜"**：真机该档由
     * [dev.cdl.render.CoverBackdrop] 整块烘焙（等比铺满 + 旋转 + 同源蒙版），
     * 角度一变必然重烘。预览要反映这一档就必须知道角度。
     */
    val backgroundCoverAngleDeg: Int,
    /** 背景不透明度 0..100。 */
    val backgroundOpacity: Int,
    /** 主歌词已唱色（打包 ARGB，恒不透明）。 */
    val sungColor: Int,
    /** 主歌词未唱色。 */
    val unsungColor: Int,
    /** 译文色。 */
    val translationColor: Int,
    /** 主歌词字号（pt）。 */
    val mainFontSize: Int,
    /** 字体族名（空串 = 用自带默认）。 */
    val fontFamily: String,
    /**
     * **是否加粗**（`main_lyric.font.weight == "BOLD"`）。★ 2026-09-29 由 `fontWeightScale: Float` 改来。
     *
     * ⚠️ **必须与真机同源** —— 预览的全部意义是"所见即真机所示"。
     * 预览若不带字重，用户调完字重在预览里看不出变化（他会以为设置坏了）。
     */
    val fontBold: Boolean,
    /**
     * **副歌词字号比例**（`0.64f` = 标准）。
     * 来自 `layout.translation.scale_percent / 100 × TRANSLATION_SCALE`。
     *
     * ⚠️ 同样必须与真机同值（理由同上）。
     */
    val translationScale: Float,
    /** 内容对齐：`CENTER` / `LEFT` / `RIGHT`。 */
    val contentAlign: String,
    /** 是否显示封面。 */
    val coverEnabled: Boolean,
    /**
     * `cover.scale_with_font` —— 封面尺寸是否**跟随字号**（即跟随歌词区高度）。
     *
     * ⚠️ 2026-09-21 补：它此前**没进快照**，而预览里写死 `min(islandH - 12, 84)` ——
     * **硬上限 84px**，于是"封面随字号缩放"在预览里**恒等于关**（大字号下封面不动）。
     * 用户原话：「示例封面**没有适应示例词岛大小**。」
     *
     * 真机语义（`IslandWindow.leadingCoverSize`）：
     * `natural = (areaHeight - 2×10).coerceAtLeast(20)`，
     * `scale_with_font = true` → **不套上限**；`false` → 再 `coerceAtMost(56)`。
     */
    val coverScaleWithFont: Boolean,
    /** 封面清晰度（锐化，`cover.sharpness`）。★ 与渲染层**同一个键**。 */
    val coverSharpness: Int,
    /**
     * `cover.shape` —— `ROUNDED`（默认，圆角矩形）/ `CIRCLE`（圆形）/ `SQUARE`（方形）。
     *
     * ⚠️ 同样此前**没进快照** → 用户选了"圆形封面"在预览里也看不出变化。
     *
     * ⚠️⚠️ 2026-09-21 补 `SQUARE`：注册表与 `IslandWindow.drawCover` **早已**支持
     * 三档（见 `IslandWindow.drawCover`），而预览的 `coverMaskShape` 只有
     * `CIRCLE / else` 两条分支 ⇒ 用户选「方形」，预览画的是**圆角**矩形。
     * 这正是 `IslandPolicyProbe:399` 那条断言要防的「静默回落 ROUNDED」——
     * 真机侧防住了，**预览侧漏了**（同一族缺陷在相邻一层重演）。
     */
    val coverShape: String,
    /**
     * `cover.vinyl` —— **黑胶样式**：在封面**外围**加一圈黑环（`false` = 出厂默认）。
     *
     * 用户原话：「旋转圆形封面**加上黑边**，使其呈现黑胶样式
     * （在**外围**加黑边，**不要侵占封面内容**）。」
     *
     * ⚠️ 此前**没进快照** → 这个开关在预览里**恒等于关**，勾了没任何反应。
     * 更隐蔽的是它还**改变槽位宽度**：真机 `IslandWindow` 里那处用
     * `areaLeft = LEADING_INSET + coverSlotWidth(size, vinyl) + LEADING_GAP`
     * （`coverSlotWidth = size + 2 × ring`），开了黑胶歌词起点会**右移两个环宽**；
     * 预览若只画环不改槽位，用户看到的就是"预览里歌词位置和真机不一样"。
     */
    val coverVinyl: Boolean,
    /** 是否显示频谱。 */
    val spectrumEnabled: Boolean,
    /** 频谱色（打包 ARGB）。 */
    val spectrumColor: Int,
    /**
     * `spectrum.symmetric` —— **上下对称双峰**。
     *
     * ⚠️ 2026-09-20 补：它此前**没进快照**（虽然注册表里有），于是
     * 用户的词岛是双峰、而示例预览画的是**单峰** —— 一条**看得见**的偏差。
     * 用户原话：「你说会对当前词岛设置进行快照，**显然并没有**……
     *   其余像**字号、边框**等显示设置随打开前的设置快照。」
     */
    val spectrumSymmetric: Boolean,
    /**
     * `spectrum.bar_pitch_tenth_px` —— 柱间距（**单位 0.1 px**）。
     *
     * ★ 2026-09-27 补：它此前**没进快照** ⇒ 预览画的是固定 `w/bars × 0.6` 的柱宽，
     * 与真机**不同源**。用户拖"柱间距"滑杆时预览**毫无反应**
     * —— 正是本项目 `fixed_width` 那类**死设置**的观感形态。
     *
     * ⚠️ **0 是特例**：[isSolidSpectrumPitch] 判它为"实心" ⇒ 真机走**折线形态**。
     */
    val spectrumBarPitchTenthPx: Float,
    /** `spectrum.bar_shape` —— 柱体圆角 / 方角（`ROUND` / `SQUARE`）。 */
    val spectrumBarShape: String,
    /** `spectrum.bar_corner` —— 端头形状（`ROUND` / `SQUARE`）。 */
    val spectrumBarCorner: String,
    /**
     * `spectrum.color_mode` —— 颜色来源（`manual` / `cover` / **`level`**）。
     *
     * ★★ 2026-10-02 补（`level` 档 = "随音量流光"）：预览要**逐柱取色**就必须知道档位
     * —— 否则真机逐柱变、预览单色不变（正是 ⓪j 守卫盯的那种"真机变预览不变"）。
     *
     * ★ 与 `spectrumColor`（基色）**分工**：`color_mode` 决定"**怎么用**这个色"，
     * `spectrumColor` 决定"色是什么"。派生规则见 `render/SpectrumLevelColor.kt`。
     */
    val spectrumColorMode: String,
    /**
     * `main_lyric.color_mode` —— 主歌词的颜色来源（`manual` / `cover`）。
     *
     * ★★★ 2026-10-14（**BUG-201**）：本字段的**唯一用途**是喂给公开纯函数
     * [dev.cdl.window.needsCoverImageFor] —— 真机原名单里第一条
     * 颜色来源判据就是 `lyricColorMode == CoverPalette.COVER`。
     *
     * ★ 为什么必须进快照：预览要靠同一条判据决定"**要不要把示例封面的主色喂进去**"。
     *   此前预览侧**自己另写了一份**只看 `cover.enabled` 的判据 ⇒
     *   "不显示封面 + 歌词跟随封面取色"时预览会**沉默地退回手动色** ✗
     *   （用户报障逐字：「词岛背景颜色与封面颜色的不相符」）。
     *
     * ⚠️ 预览当前**不**用本字段去解析歌词色（歌词色走 `render/SpectrumLevelColor` 之外的
     *    另一些字段），它在这里**只服务于取色来源判据** —— 故它是"喂给共享函数的入参"，
     *    不是"预览自己要用的字段"。这正是 `check_preview_parity.py` 检查四（快照字段
     *    必须在预览侧至少出现一次）能够接受它的原因。
     */
    val lyricColorMode: String,
    /**
     * `spectrum.anchor` —— 频谱贴哪条边（`BOTTOM` / `TOP` / **`RIGHT`**）。
     *
     * ★★ 2026-10-02 补：★ 此前快照**根本没有这个字段** ⇒
     * 「预览的频谱位置」与「真机的」**一直可以不一致**（只是没人报过 —— 因为前两档
     * 的差别只在"贴顶还是贴底"这 16px 高度里，肉眼不易分辨）。
     *
     * ★ 新增 `RIGHT` 档后差别**变成整块区域的位置**（占满歌词区 vs 缩到右端窄带）
     * ⇒ 再不接线就是**一眼可见**的"真机变、预览不变"。
     */
    val spectrumAnchor: String,
    /** ★★ 2026-10-03（⑱ 时钟）：右端插槽的 occupant（`island.right_slot.component`）。 */
    val rightSlotComponent: String,
    /**
     * ★★★ 2026-10-13（**U16**）：小频谱的**条数**（`island.right_slot.spectrum_bars`）。
     *
     * ★ 用户原话（逐字）：「**新增5条小频谱的频谱条数选择（4和6根）**」
     * ★ 预览必须跟着变 —— 否则用户改了条数**在预览里看不出变化**
     * （= 本项目最忌讳的"死设置"观感）✗
     */
    val rightSlotSpectrumBars: String,
    /**
     * ★★★ 2026-10-15（**§3.75**）：插槽频谱的**取色档位**
     * （`island.right_slot.spectrum_color_mode`）。
     *
     * ★ 用户诉求（逐字）：「**开放对插槽频谱的独立选色**」—— 拍板范围 = **完整取色模式**。
     * ★ 预览必须跟着变 —— 否则用户改了档位**在预览里看不出变化**
     *（= 本项目最忌讳的"死设置"观感）✗
     *
     * ⚠️ 这里存的是**原样的 wire 值**；归一化（未知值 ⇒ `follow`）由
     *   `window/IslandAppearance.kt` 的 `rightSlotSpectrumColorModeFor` 做
     *   —— ★ **与真机同一个纯函数**（预览不撒谎）✓
     */
    val rightSlotSpectrumColorMode: String,
    /**
     * ★★★ 2026-10-15（**§3.75**）：插槽频谱的**自选色**
     * （`island.right_slot.spectrum_color`，单键 COLOR）。
     *
     * ⚠️ 读法与 [spectrumColor] **逐字相同**（单键 COLOR 走 `snap.int(it)`）——
     *   按组读会读不到 `….r` ⇒ **用户设的色在预览里被忽略且不报错** ✗
     */
    val rightSlotSpectrumColor: Int,
    /**
     * ★★★ 2026-10-16（**台账 §3.77-D**）：`spectrum.opacity_pct` —— 频谱**整帧不透明度**。
     *
     * ## 为什么必须进快照
     *
     * ★ 预览此前把**所有**频谱一律压到 **55%**（`(0.55f * 255).toInt()`，硬编码），
     *   而真机吃 `spectrum.opacity_pct`（用户本机 = **100**）⇒
     *   ★★ 用户原话：「**环形频谱显示不统一**」+「**和桌面词岛上的环不一样**」✓
     *
     * ★ 55% 那条策略的**初衷是对的但作用域错了**：它是为了"频谱是背景层、
     *   全不透明会把**歌词**盖住"。而 ★ **右端插槽的环 / 放射根本不压在歌词上**
     *   （它在岛体右端、与歌词**并排**）⇒ 对它压暗**没有理由**，只让预览撒谎 ✗
     *
     * ⇒ ★ 修法：**整帧不透明度改由本字段驱动**（与真机 `alphas.frameOpacity` 同源），
     *   并把 55% 那条护栏**收窄到主带**（它才是压在歌词上的那个）✓
     */
    val spectrumOpacityPct: Int,
    /**
     * ★★★ 2026-10-16（**台账 §3.77-F**）：极坐标**径向范围**三键
     * （`island.right_slot.radial_inner_percent` / `…ring_inner_percent` /
     * `…polar_outer_percent`）。
     *
     * ★ **必须进快照**（不能只读真机侧）—— 否则拖那三根滑杆时
     * 「**真机变、预览不变**」，而预览的职责正是"先在这里看好再上真机" ✗
     * ★ 三个回退值都取**出厂几何**（0 / 55 / 100）⇒ 开箱逐像素不变 ✓
     */
    val rightSlotRadialInnerPercent: Int,
    /** 见 [rightSlotRadialInnerPercent]。 */
    val rightSlotRingInnerPercent: Int,
    /** 见 [rightSlotRadialInnerPercent]。 */
    val rightSlotPolarOuterPercent: Int,
    /** ★★ 2026-10-03（⑱ 时钟 · ⑤b）：时制（`clock.hour_format`）—— 预览要跟着切。 */
    val clockHourFormat: String,
    /**
     * ★★ 2026-10-03（⑱ 时钟 · **⑥ 字形风格**）：`clock.style`。
     *
     * ★ **必须进快照**（不能只读真机侧）—— 否则切档时"真机变、预览不变"，
     *   正是 `BUG-149`（预览少传实参）的形态。
     * ★ 值**不在此处归一化**：归一化由 `ClockGlyphs.normalizeStyle` 在**两个绘制点之前**
     *   各做一次（同一函数）⇒ 两边口径不可能漂。
     */
    val clockStyle: String,
    /** 是否显示边框。 */
    val borderEnabled: Boolean,
    /** 边框样式（`island.border.mode`）。★ 与渲染层**同一个键**（预览与真机一致）。 */
    val borderMode: String,
    /**
     * 「溶边」过渡带占**岛体高度**的百分比（`island.border.dissolve_band_percent`）。
     *
     * ★ 2026-10-13（**BUG-186**）：★ 真机与预览**同一个键**（都经 `dissolveBandPx`）
     *   ⇒ 渐变范围不会出现「预览攒谎」✓（默认 50 = 已交付的观感）
     */
    val borderDissolveBandPercent: Int,
    /** 边框色。 */
    val borderColor: Int,
    /**
     * 边框**取色来源**（`island.border.color_mode`）—— ★★★ 2026-10-14（**BUG-199**）新增。
     *
     * ## 为什么必须带这个字段（★ 它正是纪律 94「字段带了没人读」的反面：**字段压根没带**）
     *
     * 用户原话：「**频谱条的取色也跟封面取色不一致**」+
     *   「预览应从示例封面里取色，且**背景 / 频谱 / 边框都取同一处**」。
     *
     * ★ 背景侧早在 2026-09-26 就走 `backgroundColorMode` 了（见 [backgroundColorMode]）；
     * ★★ 而**频谱与边框**两侧的预览实现**恒取手动色**、从不看 `*_color_mode`
     *   ⇒ 真机从封面派生、预览画手动色 ⇒ **预览撒谎** ✗
     *   （★ 频谱侧的快照字段 [spectrumColorMode] **本来就有**、只是没人用来解析色值；
     *     边框侧**连字段都没有** ⇒ 故本批新增它。）
     */
    val borderColorMode: String,
    /** 主歌词描边（`null` = 不描边）。 */
    val mainOutline: OutlineSpec?,
    /** 译文描边（`null` = 不描边）。 */
    val translationOutline: OutlineSpec?,
    /** 主歌词三档高亮（`LINE` / `SMOOTH` / `PER_CHAR`）。 */
    val karaokeMode: String,
) {
    /** 描边规格：宽度（像素）+ 颜色。 */
    data class OutlineSpec(val widthPx: Float, val color: Int)

    companion object {
        /** 示例歌词（用户 2026-09-19 指定，**不要改**）。 */
        const val SAMPLE_MAIN = "世界でひとつの君という奇跡が"
        const val SAMPLE_TRANSLATION = "在这世间 有一个被称为「你」的奇迹"

        /**
         * 从当前设置读一份快照。
         *
         * `runtime == null`（设置系统未就绪）时返回 [DEFAULTS] ——
         * 预览块仍然画得出来，而不是空白（"半成品"是本项目明确拒绝的形态）。
         */
        fun capture(runtime: SettingsRuntime?): PreviewSnapshot {
            if (runtime == null) return DEFAULTS
            val snap = runCatching { runtime.live }.getOrNull() ?: return DEFAULTS
            fun int(key: String, def: Int): Int =
                runCatching { SettingRegistry.descriptor(key)?.let { snap.int(it) } }.getOrNull() ?: def
            fun str(key: String, def: String): String =
                runCatching { SettingRegistry.descriptor(key)?.let { snap.text(it) } }.getOrNull() ?: def
            fun bool(key: String, def: Boolean): Boolean =
                runCatching { SettingRegistry.descriptor(key)?.let { snap.boolean(it) } }.getOrNull() ?: def

            // 颜色组统一走"三键 + 可选 lightness"读法（与 `ToolWindows.currentPacked` 同口径）。
            fun group(prefix: String, dr: Int, dg: Int, db: Int): Int {
                val r = int("$prefix.r", dr)
                val g = int("$prefix.g", dg)
                val b = int("$prefix.b", db)
                val l = if (SettingRegistry.descriptor("$prefix.lightness") != null) {
                    int("$prefix.lightness", 100).coerceIn(0, 100)
                } else {
                    100
                }
                return dev.cdl.settings.composePackedArgb(r, g, b, l)
            }

            fun outline(prefix: String): OutlineSpec? {
                if (!bool("$prefix.enabled", false)) return null
                val baseTenth = int("$prefix.width_tenth_px", 10)
                // ★ 描边宽度**联动字体粗细**（2026-09-21 用户要求）——
                //   与 `IslandWindow` 里**同一公式**：`基准 × (1 + (字重倍率 - 1) × 联动比例)`。
                //   预览必须照抄，否则用户开了联动在预览里看不出变化（会以为设置坏了）。
                val tenthPx = if (!bool("main_lyric.outline.follow_font_weight", false)) {
                    baseTenth
                } else {
                    // ★ 2026-09-29：`w` 从"倍率 Float"改成"是否加粗 Boolean"。
                    //   原式 `baseTenth × (1 + (w - 1) × r)` 在 `w = 2f`（加粗）时 = `baseTenth × (1 + r)`；
                    //   `w = 1f`（常规）时 = `baseTenth`。⇒ 布尔化后直接分两支，**数值完全等价**。
                    val bold = str("main_lyric.font.weight", "NORMAL") == "BOLD"
                    val r = (int("main_lyric.outline.weight_link_percent", 50) / 100f)
                        .coerceIn(0f, 1f)
                    val factor = if (bold) 1f + r else 1f
                    (baseTenth * factor).roundToInt().coerceAtLeast(0)
                }
                val w = tenthPx / 10f
                if (w <= 0f) return null
                return OutlineSpec(w, group("$prefix.color", 255, 255, 255))
            }

            // ⚠️ `translation.outline.sync_with_main = true` 时，译文描边**整组跟随主歌词**
            //    （含"主歌词没开"→ 译文也不描边）。这条语义在 `IslandWindow` 里由
            //    `Appearance.translationOutlineFor()` 实现，预览必须**照同样的规则** ——
            //    否则预览显示的描边与真机不一致，用户会以为设置没生效。
            val mainOutline = outline("main_lyric.outline")
            val translationOutline = if (bool("translation.outline.sync_with_main", true)) {
                mainOutline
            } else {
                outline("translation.outline")
            }

            return PreviewSnapshot(
                // ⚠️ 默认值与注册表一致（`shape.type` = SQUARE、`cover.shape` = ROUNDED）——
                //    否则"设置系统不可用"时的兜底预览会与真机出厂观感不符。
                shapeType = str("shape.type", "SQUARE"),
                cornerRadius = int("shape.corner_radius", 60),
                // 2026-09-25：角风格两键**与真机同源**（`IslandWindow` 的外观快照读同一对键）。
                // 默认值取注册表默认（`ARC` / 4）⇒ 设置系统不可用时预览仍与出厂观感一致。
                cornerStyle = str("shape.corner_style", "ARC"),
                cornerN = int("shape.corner_n", 4),
                // 材质三层：与真机**同一对键、同一套默认值**。
                materialHighlightStrength = int("material.highlight.strength", 0),
                materialGradientStyle = str("material.fill.gradient_style", "NONE"),
                materialGradientTintPercent = int("material.fill.gradient_tint_percent", 30),
                materialGradientAngleDeg = int("material.fill.gradient_angle_deg", 90),
                // ⚠️ 与注册表默认同值（`0xFF2A2E38`）；它是**字面量**，理由同真机侧。
                materialGradientEndArgb = runCatching {
                    SettingRegistry.descriptor("material.fill.color_end")?.let { snap.int(it) }
                }.getOrNull() ?: 0xFF2A2E38.toInt(),
                materialNoiseAmplitude = int("material.noise.amplitude", 0),
                materialQuality = str("material.quality", "FULL"),
                materialBlurRadius = int("material.content.blur_radius", 0),
                // ★★ 2026-10-01：液态玻璃子组 —— **与真机同一对键、同一套默认值**
                //   （真机侧见 `IslandWindow` 的 `Appearance.from`：
                //     `int("material.liquid.glare_strength", 60)` / `bool("material.liquid.depth_effect", true)`
                //     / `int("material.liquid.surface_alpha", 10)`）。
                //   ⚠️ 键名/默认值**必须与真机逐字相同** —— 否则预览与真机会"看起来都生效、
                //      实际取值不同"（比不接更隐蔽）。
                materialLiquidGlarePercent = int("material.liquid.glare_strength", 60),
                materialLiquidDepthEffect = bool("material.liquid.depth_effect", true),
                materialLiquidSurfaceAlpha = int("material.liquid.surface_alpha", 10),
                // ★★★ D-1（2026-10-09）：GPU 后端 —— **与真机逐字同键同默认**
                //   （真机侧 `Appearance.from` 的 `bool("material.render.gpu_backend", false)`）。
                //   见字段 KDoc：漏推它 = 预览对"GPU 后端"开关无感（BUG-149 形态）。
                materialRenderGpuBackend = bool("material.render.gpu_backend", false),
                // ★ 2026-09-26：补上真机决定底色的**全部输入** —— 见字段 KDoc。
                //   缺任何一个都会退化成"真机变、预览不变"。取色本身在 `IslandPreviewPane`
                //   里调真机的同一个公开纯函数，故这里只负责**把输入带全**。
                backgroundColorMode = str("background.color_mode", CoverPalette.MANUAL),
                backgroundColorArgb = runCatching {
                    SettingRegistry.descriptor("background.color")?.let { snap.int(it) }
                }.getOrNull() ?: BACKGROUND_BASE_RGB,
                backgroundCoverAngleDeg = int("background.cover.angle_deg", 0),
                backgroundOpacity = int("background.opacity", 96),
                sungColor = group("main_lyric.fill.color", 255, 255, 255),
                unsungColor = group("main_lyric.fill.unsung", 126, 129, 138),
                translationColor = group("main_lyric.translation.color", 177, 182, 195),
                mainFontSize = int("main_lyric.font.size", 22),
                fontFamily = str("main_lyric.font.family", ""),
                // 字重与副歌词比例：**与真机同一套折算**（100 = 标准）。
                fontBold = str("main_lyric.font.weight", "NORMAL") == "BOLD",
                translationScale = TRANSLATION_SCALE_DEFAULT *
                    (int("layout.translation.scale_percent", 100) / 100f).coerceIn(0.5f, 1.5f),
                contentAlign = str("layout.content_align", "CENTER"),
                coverEnabled = bool("cover.enabled", false),
                coverScaleWithFont = bool("cover.scale_with_font", true),
                coverSharpness = int("cover.sharpness", 25),
                coverShape = str("cover.shape", "ROUNDED"),
                coverVinyl = bool("cover.vinyl", false),
                spectrumEnabled = bool("spectrum.enabled", true),
                // ⚠️ `spectrum.color` 是**单键 `COLOR`**（hex 文本），**不是**三键组 ——
                //    按组读会读不到 `spectrum.color.r`（null → 用默认值），
                //    于是**用户设的频谱色在预览里被忽略**，而且**不报错**。
                //    （这是"逐键扫消费点"纪律的又一次命中：键存在 ≠ 读法正确。）
                spectrumColor = runCatching {
                    SettingRegistry.descriptor("spectrum.color")?.let { snap.int(it) }
                }.getOrNull()?.or(0xFF000000.toInt()) ?: 0xFF84D8BC.toInt(),
                spectrumSymmetric = bool("spectrum.symmetric", false),
                // ★ 2026-09-27 补（口径 B：预览跟形态走）——
                //   键名/默认值均取自 `SettingRegistry`，**不手抄数字**。
                //   ⚠️ `bar_pitch_tenth_px` 是 **Int**（0.1px 单位），故用 `int(...)` 读。
                spectrumBarPitchTenthPx = int("spectrum.bar_pitch_tenth_px", 70).toFloat(),
                spectrumBarShape = str("spectrum.bar_shape", "ROUND"),
                spectrumBarCorner = str("spectrum.bar_corner", "ROUND"),
                // ★ 2026-10-02：默认 `manual` ⇒ 与注册表出厂默认一致（开箱逐像素不变）。
                spectrumColorMode = str("spectrum.color_mode", "manual"),
                // ★★★ 2026-10-14（BUG-201）：喂给 `needsCoverImageFor` 的第一条颜色来源判据。
                lyricColorMode = str("main_lyric.color_mode", dev.cdl.render.CoverPalette.MANUAL),
                spectrumAnchor = str("spectrum.anchor", "BOTTOM"),
                rightSlotComponent = str("island.right_slot.component", "NONE"),
                rightSlotSpectrumBars = str("island.right_slot.spectrum_bars", "5"),
                // ★★★ 2026-10-15（§3.75）：插槽频谱的独立取色两个键。
                //   回退值与注册表出厂默认一致（follow / #84D8BC）⇒ 开箱逐像素不变 ✓
                rightSlotSpectrumColorMode = str("island.right_slot.spectrum_color_mode", "follow"),
                rightSlotSpectrumColor = runCatching {
                    SettingRegistry.descriptor("island.right_slot.spectrum_color")?.let { snap.int(it) }
                }.getOrNull()?.or(0xFF000000.toInt()) ?: 0xFF84D8BC.toInt(),
                // ★★★ 2026-10-16（§3.77-D）：频谱整帧不透明度（回退 100 = 改动前的真机值）✓
                spectrumOpacityPct = int("spectrum.opacity_pct", 100),
                // ★★★ 2026-10-16（§3.77-F）：极坐标径向范围三键（回退值 = 出厂几何）✓
                rightSlotRadialInnerPercent = int(
                    "island.right_slot.radial_inner_percent",
                    dev.cdl.render.POLAR_RADIAL_INNER_PERCENT_DEFAULT,
                ),
                rightSlotRingInnerPercent = int(
                    "island.right_slot.ring_inner_percent",
                    dev.cdl.render.POLAR_RING_INNER_PERCENT_DEFAULT,
                ),
                rightSlotPolarOuterPercent = int(
                    "island.right_slot.polar_outer_percent",
                    dev.cdl.render.POLAR_OUTER_PERCENT_DEFAULT,
                ),
                clockHourFormat = str("clock.hour_format", "H24"),
                clockStyle = str("clock.style", "SEGMENT"),
                borderEnabled = bool("island.border.enabled", false),
                borderMode = str("island.border.mode", "SOLID"),
                borderDissolveBandPercent =
                    int("island.border.dissolve_band_percent", 50),
                borderColor = group("island.border.color", 255, 255, 255),
                // ★ 2026-10-14（BUG-199）：边框取色来源 —— 与真机 `borderColorFor` 同一个键。
                borderColorMode = str("island.border.color_mode", CoverPalette.MANUAL),
                mainOutline = mainOutline,
                translationOutline = translationOutline,
                karaokeMode = str("main_lyric.karaoke.mode", "SMOOTH"),
            )
        }

        /**
         * 设置系统不可用时的兜底快照。
         *
         * ⚠️ 这里的默认值是**硬编码**的 —— 但它只在"设置系统整个不可用"时用，
         * 那种情况下 `SettingRegistry` 也读不到。数值与注册表默认一致。
         */
        val DEFAULTS = PreviewSnapshot(
            // 与注册表出厂默认一致：形态 = 方形、封面形状 = 圆角、封面随字号缩放 = 开。
            shapeType = "SQUARE",
            cornerRadius = 60,
            cornerStyle = "ARC",
            cornerN = 4,
            materialHighlightStrength = 0,
            materialGradientStyle = "NONE",
            materialGradientTintPercent = 30,
            materialGradientAngleDeg = 90,
            materialGradientEndArgb = 0xFF2A2E38.toInt(),
            materialNoiseAmplitude = 0,
            materialQuality = "FULL",
            materialBlurRadius = 0,
            // 液态玻璃子组：与**面板值**同值（`60` / `true` / `10`）——
            // 理由同上：DEFAULTS 的职责是"代表出厂观感"，而出厂观感就是面板值。
            materialLiquidGlarePercent = 60,
            materialLiquidDepthEffect = true,
            materialLiquidSurfaceAlpha = 10,
            // 兜底档与真机默认**逐位相同**（`manual` + `#14161A` + 角度 0）——
            // 故设置系统坏掉时预览画面与出厂观感一致。
            backgroundColorMode = CoverPalette.MANUAL,
            backgroundColorArgb = BACKGROUND_BASE_RGB,
            backgroundCoverAngleDeg = 0,
            backgroundOpacity = 96,
            sungColor = 0xFFFFFFFF.toInt(),
            unsungColor = 0xFF7E818A.toInt(),
            translationColor = 0xFFB1B6C3.toInt(),
            mainFontSize = 22,
            fontFamily = "",
            // 出厂：字重常规（1f）、比例标准（= 真机的 `TRANSLATION_SCALE`）。
            // 两者都取"标准值"而不是各自的极值 —— DEFAULTS 的职责是**代表出厂观感**。
            fontBold = false,
            translationScale = TRANSLATION_SCALE_DEFAULT,
            contentAlign = "CENTER",
            coverEnabled = false,
            coverScaleWithFont = true,
            coverSharpness = 25,
            coverShape = "ROUNDED",
            // 出厂默认关：与注册表 `cover.vinyl` 的 `default = false` 一致。
            coverVinyl = false,
            spectrumEnabled = true,
            spectrumColor = 0xFF84D8BC.toInt(),
            spectrumSymmetric = false,
            // 与注册表出厂默认一致：间距 7.0px（= 70 个 0.1px）、圆角、圆角。
            spectrumBarPitchTenthPx = 70f,
            spectrumBarShape = "ROUND",
            spectrumBarCorner = "ROUND",
            spectrumColorMode = "manual",
            // ★★★ 2026-10-14（BUG-201）：兜底构造也要带全 `needsCoverImageFor` 的四个入参
            //   ⇒ 与注册表默认一致（`main_lyric.color_mode` 出厂为 `manual`）✓
            lyricColorMode = dev.cdl.render.CoverPalette.MANUAL,
            spectrumAnchor = "BOTTOM",
            rightSlotComponent = "NONE",
            rightSlotSpectrumBars = "5",
            rightSlotSpectrumColorMode = "follow",
            rightSlotSpectrumColor = 0xFF84D8BC.toInt(),
            spectrumOpacityPct = 100,
            rightSlotRadialInnerPercent = dev.cdl.render.POLAR_RADIAL_INNER_PERCENT_DEFAULT,
            rightSlotRingInnerPercent = dev.cdl.render.POLAR_RING_INNER_PERCENT_DEFAULT,
            rightSlotPolarOuterPercent = dev.cdl.render.POLAR_OUTER_PERCENT_DEFAULT,
            clockHourFormat = "H24",
            // ★ 与注册表 `clock.style` 的出厂默认一致（= 改动前唯一能画的那一档）。
            clockStyle = "SEGMENT",
            borderEnabled = false,
            borderMode = "SOLID",
            borderDissolveBandPercent = 50,
            borderColor = 0xFFFFFFFF.toInt(),
            // ★ 2026-10-14（BUG-199）：兜底同注册表默认（`manual`）⇒ 与"设置系统坏掉"时同态。
            borderColorMode = CoverPalette.MANUAL,
            mainOutline = null,
            translationOutline = null,
            karaokeMode = "SMOOTH",
            // ★★★ D-1（2026-10-09）：GPU 后端 —— 出厂默认 `false`
            //   （与注册表 `material.render.gpu_backend` 的 `default = false` 一致）。
            materialRenderGpuBackend = false,
        )
    }
}
