// SPDX-License-Identifier: AGPL-3.0-only
/**
 * **外观模型** —— 从 `SettingsSnapshot` 一次性派生渲染要用的全部值。
 *
 * ★★★ 2026-10-07 **从 `IslandWindow.kt` 外迁到本文件**（`migration/100` · B1）。
 *
 * ## 为什么要外迁（三个理由，第一条是决定性的）
 *
 * 1. ★★ **让探针能直接断言"设置 → 派生值"这条链** ——
 *    在此之前 `Appearance` 是 `IslandWindow` 的 `private` 嵌套类，
 *    而**探针是独立编译模块**（`build.gradle.kts` 的 `create("probe")`）
 *    ⇒ `private` 与 `internal` **都看不见** ⇒ 88 个派生值**一个都断言不了**。
 * 2. **可审查性**：它原本住在 13,523 行的 `IslandWindow.kt` 里（第 6,269 行起），
 *    外迁后独立成文，**"设置语义 → 像素/颜色"的映射可以单独读**。
 * 3. `packRgb` 随之搬入（**它的唯一消费者就在本文件内**，故保持 `private`）。
 *
 * ## ⚠️ 可见性为什么是 `public` 而不是 `internal`
 *
 * ★ 本项目有**真机事故先例**（2026-09-21）：把某符号从 `public` 改成 `internal` 后
 *   `ColorPickerProbe` **整个编译失败** ⇒ 该探针在 `ALL_PROBES` 排得很前 ⇒ **门禁连坐整条挂掉**。
 * ★ 根因：`internal` 的可见域**只到本 module**，而 probe 是**另一个编译模块**。
 * ⇒ **凡"要让探针看见"，必须是 `public`。**
 *
 * ## ★ 但公开面被**刻意收窄**（不为了"能看见"而泄露内部类型）
 *
 * | 成员 | 可见性 | 为什么 |
 * |---|---|---|
 * | `lines` / `contentAlign` / `noLyricsMode` 三个参数 | **`internal val`** | 它们的类型是 3 个 `internal enum`（`LineCount`/`Align`/`NoLyricsMode`）⇒ 若为 `public` 会**暴露 internal 类型**（`EXPOSED_PROPERTY_TYPE`）。★ 用 `internal` 就够了：`IslandPanel`（同 module）读得到 |
 * | `mainOutlineFor()` / `translationOutlineFor()` | **`internal fun`** | 同理 —— 返回 `internal data class TextOutline` |
 * | 其余 85 个参数 / 13 个方法 | `public` | 类型全是 `String`/`Int`/`Float`/`Boolean`/`Long`/`Color`/`MotionStyle` ⇒ 无暴露问题 |
 *
 * ★ 这样**一处公开面都没有多开**：那 3 个 enum 与 `TextOutline` 的可见性**一个字都没动**。
 *
 * ## ⚠️ 旧引用（`IslandWindow.Appearance`）已改指本文件
 *
 * 全库另有 **6 处注释**写的是【旧的限定名】（搬家后会指向**不存在的对象**），已在同批改掉：
 * · `IslandWindow.Appearance` ×4（`SettingsRuntime.kt` ×2 · `PreviewSnapshot.kt` · `PlaybackControls.kt`）
 * · `IslandWindow.TRANSLATION_SCALE` ×2（`IslandPreviewPane.kt` · `PreviewSnapshot.kt`）
 *   —— ★ 后者是因为那 8 个常量也在同批从 `private companion object` 提到了**文件级**（见 B1a）。
 */

package dev.cdl.window

import dev.cdl.animation.MotionStyle
import dev.cdl.animation.Motions
import dev.cdl.render.CoverPalette
import dev.cdl.render.TextOutline
import dev.cdl.render.letterSpacingTracking
import dev.cdl.render.resolveOutline
import dev.cdl.settings.AnimationStyleResolver
import dev.cdl.settings.KARAOKE_MODE_PER_CHAR
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingsSnapshot
import dev.cdl.settings.toColor
import java.awt.Color
import kotlin.math.roundToInt

/**
 * 三通道 0..255 → 打包 ARGB（alpha 恒为不透明）。
 *
 * ⚠️ 必须是**文件级（顶层）函数**，不能是 `IslandWindow` 的成员 ——
 *   [Appearance] 是**嵌套类**（不是 `inner`），拿不到外层实例的成员，
 *   调用成员版会编译报 `Outer class ... cannot be used as receiver`。
 *   这个错误离病根（"函数放错了作用域"）很远，故记在这里。
 */
private fun packRgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)


// ══════════════════════════════════════════════════════════════════════════════
// ★★★ 2026-10-15（**§3.75**）：插槽频谱的**独立取色** —— 两个**公开纯函数**
// ══════════════════════════════════════════════════════════════════════════════
//
// 用户诉求（逐字，台账 §3.75 登记）：「**开放对插槽频谱的独立选色**」。
// 用户拍板（2026-10-15）：范围 = **完整取色模式**（不是"只加一个自选色"）。
//
// ## 为什么必须是**纯函数**（而不是在绘制路径里现读现判）
//
// ① 探针看不到 `internal` 的 `IslandWindow` / `IslandMenuSkin`（独立编译单元）
//    ⇒ 规则抽成**顶层 public** 才能被逐值断言（本项目反复使用的手法）；
// ② 「插槽档位」与「主频谱档位」是**两个量**，必须有**唯一一处**决定"谁跟随谁"
//    —— 若在真机与预览各写一遍，两边必然分叉（本项目"预览撒谎"的经典成因）。

/**
 * ★★★ 2026-10-15（**§3.75**）：插槽频谱的**默认档** = **跟随主频谱**。
 *
 * ★ 默认取它 ⇒ **开箱逐像素不变**（零回归）—— 这是本条最硬的一条约束：
 *   用户此前从没设过插槽的色，默认值一变，"什么都不动"的用户观感就变了 ✗
 */
const val RIGHT_SLOT_SPECTRUM_MODE_FOLLOW: String = "follow"

/**
 * ★★★ 2026-10-15（**§3.75**）：**插槽频谱的取色档位**（唯一出口）。
 *
 * 值域 = 主频谱那五档（[CoverPalette.MANUAL] / [CoverPalette.COVER] /
 * [CoverPalette.LEVEL] / [CoverPalette.COVER_FLOW] / [CoverPalette.COVER_CAMO]）
 * **+ [RIGHT_SLOT_SPECTRUM_MODE_FOLLOW]**。
 *
 * ★ **未知值 / 空串 / `null` ⇒ 一律回落 `follow`** —— 与
 *   [dev.cdl.render.slotSideBarCountFor] 的"未知值回落默认"**同一条收敛纪律**：
 *   设置来自外部 JSON，**不能假设它合法**；回落"跟随主频谱"总比"插槽突然变个颜色"强 ✓
 *
 * @param wire `island.right_slot.spectrum_color_mode` 的值（`null` = 键缺失 ⇒ 走默认）
 */
fun rightSlotSpectrumColorModeFor(wire: String?): String = when (wire?.trim()) {
    CoverPalette.MANUAL,
    CoverPalette.COVER,
    CoverPalette.LEVEL,
    CoverPalette.COVER_FLOW,
    CoverPalette.COVER_CAMO,
    -> wire.trim()
    // 显式 `else`（而不是把 `follow` 也列进 `when`）——
    //   让"未知值回落默认"这条**一眼可见**（同 `slotSideBarCountFor` 的写法）✓
    else -> RIGHT_SLOT_SPECTRUM_MODE_FOLLOW
}

/**
 * ★★★ 2026-10-15（**§3.75**）：**插槽频谱的基色**（唯一出口）。
 *
 * | `slotMode` | 返回 |
 * |---|---|
 * | [RIGHT_SLOT_SPECTRUM_MODE_FOLLOW] | `mainColor`（**主频谱解析后的色** —— 跟随即同一份） |
 * | 其余五档 | `CoverPalette.resolve(slotMode, slotManualColor, coverRgb, brightFrom)` |
 *
 * ★ `follow` 传的是**主频谱已解析的颜色**（而不是主频谱的档位 + 主频谱的手动色）——
 *   这样"跟随"是**结构性**的：主频谱怎么变，插槽就怎么变，**不可能**漂移 ✓
 * ★ 五档的解析复用 [CoverPalette.resolve]（**同一个**函数，主频谱也走它）⇒
 *   "manual / cover / level / cover_flow / cover_camo"的语义在两侧**逐字相同** ✓
 *
 * @param mainColor 主频谱**已解析**的基色（`Appearance.spectrumColorFor(coverRgb)`）
 * @param slotManualColor 插槽自己的手动色（`island.right_slot.spectrum_color`）
 */
fun rightSlotSpectrumColorFor(
    slotMode: String,
    mainColor: Color,
    slotManualColor: Color,
    coverRgb: Int?,
): Color = if (slotMode == RIGHT_SLOT_SPECTRUM_MODE_FOLLOW) {
    mainColor
} else {
    CoverPalette.resolve(slotMode, slotManualColor, coverRgb, CoverPalette::brightFrom)
}

/**
 * ★★★ 2026-10-15（**§3.75**）：某档是否**多色**（`cover_flow` / `cover_camo`）。
 *
 * ★ 抽出来是因为这条判据**有三个消费点**（主带实心档 / 插槽实心档 / 副频谱），
 *   而它此前在每处**各写一遍**（`mode == COVER_FLOW || mode == COVER_CAMO`）——
 *   ★ BUG-204 的注释里已经记过一次"漏了一处 ⇒ 新档退化回单色"的教训 ✓
 */
fun isMultiColorSpectrumMode(mode: String): Boolean =
    mode == CoverPalette.COVER_FLOW || mode == CoverPalette.COVER_CAMO


/**
 * 从快照派生的**外观模型** —— 一次性把渲染要用的值都算好。
 *
 * 为什么要有这一层而不是渲染时现读：`paintComponent` 会被高频调用
 * （拖动、重绘、遮挡恢复），每次都遍历快照取 5 个键既浪费、
 * 又容易在将来漏掉某处导致「两个地方算法不一致」。
 * 派生成不可变值对象后，**渲染只读这一份**，也便于把「设置语义」
 * （如百分比 → 像素）集中在一处解释。
 */
class Appearance internal constructor(
    /** 形态枚举值：SQUARE / ROUNDED_RECT / PILL / NOTCH。 */
    val shapeType: String,
    /** 圆角半径，**按短边的百分比**（0..100）。 */
    val cornerRadiusPercent: Int,
    // ── 2026-09-25 第 2 期波次 2：材质三层（施工单 D/E/F）────────────────────
    // ⚠️ 六项的**默认值全部是"零视觉变化"**（`0` / `NONE` / `0`），
    //    与注册表一致 ⇒ 设置系统不可用时画面与改动前**逐像素一致**。
    /** 顶部高光强度（`material.highlight.strength`，0..100）。`0` = 关。 */
    val materialHighlightStrength: Int,
    /**
     * 背景渐变档（`material.fill.gradient_style`）：
     * `NONE` / `LINEAR` / `FLOW`（封面多色流光）/ `CAMO`（封面迷彩碎色流光）。
     *
     * ⚠️ 原 KDoc 只写 `NONE` / `LINEAR` —— **自 `FLOW`（2026-10-03）起就已陈旧**，
     * 2026-10-14 用户报障纠正时补全（`CAMO` 见 [dev.cdl.settings.SettingRegistry]
     * 的 `gradient_style` 选项表）。
     */
    val materialGradientStyle: String,
    /**
     * ★★★ 2026-10-16（**用户要求「加入对流光流速的流速调整设置」**）：
     * 流光流速 %（`material.fill.flow_speed`，10..300）。**100 = 原速**。
     *
     * ★ 换算**只在一处**：[dev.cdl.render.scaledFlowPeriodMs]。
     *   ★ 它**同时**缩放背景与频谱两条流光的基准周期（3× 的快慢关系保持不变）。
     */
    val materialFlowSpeed: Int,
    /** 渐变强度 %（`material.fill.gradient_tint_percent`，0..60）。`0` ⇒ 不画渐变。 */
    val materialGradientTintPercent: Int,
    /** 渐变角度 °（`material.fill.gradient_angle_deg`）。`0` = 左→右，`90` = 上→下，顺时针。 */
    val materialGradientAngleDeg: Int,
    /** 渐变止色（`material.fill.color_end`，打包 ARGB）。仅在渐变开启后被读。 */
    val materialGradientEndArgb: Int,
    /** 噪点幅度（`material.noise.amplitude`，0..20）。`0` = 关。 */
    val materialNoiseAmplitude: Int,
    // ── 2026-09-25 第 3 期 L2：画质档 + 岛内内容模糊 ────────────────────
    /** 材质画质档（`material.quality`）：`OFF` / `LITE` / `FULL`。`OFF` ⇒ 三层全不画。 */
    val materialQuality: String,
    /** 岛内内容模糊半径（`material.content.blur_radius`，0..40）。`0` = 关。 */
    val materialBlurRadius: Int,
    /**
     * ★★ **L3：身后窗口模糊半径**（`material.backdrop.blur_radius`，0..40）。
     *
     * ⚠️ **与 [materialBlurRadius] 不是一回事**（别搞混）：
     * · [materialBlurRadius] = **岛体自己的封面图**模糊（L2，已实现）
     * · 本项 = **身后窗口/桌面**模糊（L3，**本轮接线**）⇒ 要采屏，成本高
     *
     * `0` = 关闭（**默认**）⇒ L3 层完全不启用（不起线程、不开像素快照）。
     */
    val materialBackdropBlurRadius: Int,
    // ── 2026-10-01 ④b 第二批：背景折射 / 色散 / 饱和度（`migration/71-*`）────
    //
    // ★★ 这 4 项**只在** [materialBackdropBlurRadius] `> 0` 时才可能生效 ——
    //   它们改的是"L3 采到的那张背景图"，L3 关着时**根本没有那张图**。
    //   ⇒ ★ 开箱（`blur_radius = 0`）**逐像素不变** ✓
    //
    // ★ 值**全部照用户提供的 Android 面板**（★ 面板里没有 IOR —— 它原取业界默认 1.4，
    //   但**2026-10-10 起已不再使用**：只服务已收掉的 Snell 档，见 `BackdropRefraction` 文件头）：
    //   折射厚度 16 dp · 折射量 32 dp · 色散 0.35 · 饱和度 1.5×
    /**
     * ★ 折射厚度（`material.backdrop.refraction_height`，0..100 px）。
     * ★ 面板 `refractionHeight = 16.0 dp`。`0` ⇒ 不折射。
     */
    val materialBackdropRefractionHeight: Int,
    /**
     * ★ 折射量（`material.backdrop.refraction_amount`，0..100 px）。
     * ★ 面板 `refractionAmount = 32.0 dp`（bench 实测：在 304×154 上**可见**）。
     */
    val materialBackdropRefractionAmount: Int,
    /**
     * ★ 色散（`material.backdrop.dispersion`，0..100 %）。
     * ★ 面板 `dispersion = 0.35` ⇒ 默认 **35**；内部换算见
     * `BackdropRefraction.DISPERSION_SCALE`（⚠️ **面板刻度不可直接用**）。
     */
    val materialBackdropDispersion: Int,
    /**
     * ★ 饱和度（`material.backdrop.saturation`，0..200 %，**100 = 不变**）。
     * ★ 面板 `vibrancy = 1.5×` ⇒ 默认 **150**。
     */
    val materialBackdropSaturation: Int,
    /**
     * ★★★ **R7 GPU 后端开关**（`material.render.gpu_backend`，2026-10-09）。
     *
     * ★ `false`（默认）⇒ 走 CPU 的 `BackdropRefraction.bake`（**逐像素同改动前**）。
     * ★ `true` ⇒ 优先走 GPU（`GpuLiquidGlass.render`），**失败自动回退 CPU**。
     *   详见 `BackdropCaptureParams.preferGpu` 与 `SettingRegistry` 里该键的 KDoc。
     *
     * ⚠️ **它必须参与 `syncL3()` 的幂等判据** —— 与 [materialBackdropSaturation] 同因：
     *   它只在 `L3CaptureWorker` 的**构造参数**里被读 ⇒ 用户改它而半径不变时，
     *   worker 不重建 ⇒ **勾了没反应且不报错**（本项目的"死键"一族）✓
     */
    val materialRenderGpuBackend: Boolean,
    /**
     * ★★ **每秒帧率日志开关**（`animation.frame_log`，2026-09-28 用户要求，**测试用**）。
     *
     * ⚠️ **它不属于"外观"**，放进本快照的唯一理由是：`syncL3()` 需要它 ——
     * 而 `syncL3()` 的入参就是本快照（见 [syncL3]）。
     *
     * ## 为什么 L3 侧也要看它（而不是"只有动画帧那行受它管"）
     *
     * 用户要的是"**检查达成情况**"，而 L3 采集是**独立的一条节拍**（10 Hz）——
     * 它的"实际 ms"（采屏/模糊）与动画帧的"实际 ms"是**两个不同的预算**。
     * ⇒ 本开关同时管两条线（见 `PerSecondMeter` 的类 KDoc 里的分工表）。
     *
     * ## ⚠️ 它必须参与 `syncL3()` 的幂等判据
     *
     * `syncL3()` 原本"半径没变就直接 return"（避免反复重启采集线程）。
     * 若开关不进该判据 ⇒ **用户只勾开关（半径不变）时 worker 不重建
     * ⇒ L3 那行日志永远不会出现** —— 又一个"勾了没反应且不报错"。
     * ⇒ 见 [syncL3] 里把它并入复用条件。
     */
    val frameLogEnabled: Boolean,
    /** 角曲率风格（`shape.corner_style`）：`ARC` / `SQUIRCLE`。未知值由 [effectiveCornerStyle] 回退 `ARC`。 */
    val cornerStyle: String,
    /** 连续曲率指数 n（`shape.corner_n`，2..10）。仅在 `cornerStyle = SQUIRCLE` 时被读。 */
    val cornerN: Int,
    /** 背景不透明度百分比（35..100）。 */
    val opacityPercent: Int,
    /** 已唱歌词颜色。 */
    val sungColor: Color,
    /** 未唱歌词颜色。 */
    val unsungColor: Color,
    /** 歌词字体族名（如 `Microsoft YaHei UI`）。 */
    val fontFamily: String,
    // 歌词字号（14..42）。 
    /**
     * ★★ 液态玻璃 · **高光强度**（`0..100`，`material.liquid.glare_strength`）。
     * ★ 默认 `60` = 面板那一档（开箱观感）。
     */
    val materialLiquidGlarePercent: Int,
    /**
     * ★★ 液态玻璃 · **立体感**（`material.liquid.depth_effect`）。
     * `false` ⇒ 关掉菲涅耳边缘光（只剩高光，更扁平）。
     */
    val materialLiquidDepthEffect: Boolean,
    /**
     * ★★ 液态玻璃 · **表面蒙版**（`0..100`，`material.liquid.surface_alpha`）。
     * ★ 默认 `10` = 面板的 `alpha = 0.10`（开箱观感）。
     * 消费点：`MaterialLayers.paint(...)` 的第四层（`Graphics2D` 的层，不属逐像素）。
     */
    val materialLiquidSurfaceAlpha: Int,
    /**
     * ★★ 液态玻璃 · **投影强度**（`0..100`，`material.liquid.shadow_strength`）。
     * ★ 默认 `60` = 面板的 `0.60`（开箱观感）。
     * 消费点：`MaterialLayers.drawDropShadow(...)` —— ★ **必须画在岛体之前**。
     */
    val materialLiquidShadowStrength: Int,
    /**
     * **是否加粗**（`main_lyric.font.weight == "BOLD"`）。
     *
     * 由 `render/LetterSpacing.fontWithLetterSpacing` 的 `bold` 参数消费 ——
     * 那是**全工程字体的唯一构造入口**，故这里一个字段就覆盖了
     * 主歌词 / 副歌词 / 提示行 / 预览全部。
     *
     * ★★ 2026-09-29：从 `fontWeightScale: Float`（原 `main_lyric.font.weight_scale / 100`）
     * 改成布尔 —— **根治"设置项说谎"的 bug**：实测 `TextAttribute.WEIGHT` 对文字**只有两态**
     * （`< 2.0` 无效 / `≥ 2.0` 全同）⇒ 原来的滑杆 `50–199%` **全是死的**。
     * ★ 加粗走 `Font.BOLD`（**JDK 挑该族最接近的真字重** ⇒ 适配不同字体的可用字重档）。
     */
    val fontBold: Boolean,
    /**
     * **副歌词字号比例**（`0.64f` = 标准，与原实现 0.8.9 的 `fontSize * .7f` 一脉相承
     * 但在 2026-09-21 收了一档，见 `TRANSLATION_SCALE` 的 KDoc）。
     *
     * 来源：`layout.translation.scale_percent / 100 × TRANSLATION_SCALE`。
     *
     * ⚠️ **原先是个 `const`**（`TRANSLATION_SCALE`）—— 本轮为了让用户可调，
     * 改成**逐帧随快照读**。四个消费点（行高 / 字号 / 字体构造 / 译文行高）
     * 一律读它，**不再直接引用那个常量** —— 否则"设置改了但它没跟着变"，
     * 而那正是本项目记过的 `fixed_width` 那类**死设置**形态。
     */
    val translationScale: Float,
    val fontSize: Int,
    /**
     * `island.enabled`。false 时渲染层**直接不画**（不是隐藏窗口）。
     *
     * 为什么渲染层要重复判断一次（窗口显隐已经管了）：见 [applyVisibility] 的
     * 说明 —— 窗口显隐依赖 Win32 peer 状态，可能因 HWND 解析失败而没真正隐藏；
     * 而这里的判断是纯 Swing 层的、"必然生效"的那一道。
     */
    val islandEnabled: Boolean,
    /**
     * `layout.translation.enabled`。false 时不画译文行（BUG-015）。
     *
     * 为什么在渲染层判断而不是在时间轴侧"干脆不传译文"：译文是**行数据的一部分**，
     * 时间轴侧剔除它会让"开关关掉再打开"必须重建整份歌词文档；
     * 而这里只是一个绘制分支，切换开关零成本（下一帧即生效）。
     */
    val translationEnabled: Boolean,
    /**
     * `layout.translation.position` 是否等于 `ABOVE` —— 副歌词画在主歌词**上方**。
     *
     * 用户诉求：「副歌词和其他行设置一个独立设置项允许设置其在主歌词上方还是下方」。
     *
     * 存成布尔而不是字符串：绘制层只关心"在上还是在下"这一位，
     * 与 `spectrumAnchor == TOP` 的处置相同（在 [Appearance.from] 里解析一次）。
     */
    val translationAbove: Boolean,
    // ── 歌词描边（2026-09-18，用户诉求）────────────────────────────────
    //
    // 11 个字段与 11 个设置项一一对应。**不用一个 `TextOutline?` 直接存**
    // 是因为"副歌词同步主歌词"这条规则要在**读取时**生效 —— 存成解析后的
    // 值就无法区分"副歌词自己没开"与"副歌词同步了主歌词（而主歌词没开）"。
    // 两个解析函数见 [mainOutlineFor] / [translationOutlineFor]。
    val mainOutlineEnabled: Boolean,
    val mainOutlineWidthTenthPx: Int,
    val mainOutlineColorR: Int,
    val mainOutlineColorG: Int,
    val mainOutlineColorB: Int,
    val translationOutlineSync: Boolean,
    val translationOutlineEnabled: Boolean,
    val translationOutlineWidthTenthPx: Int,
    // ★ 2026-09-23 由 lead 补：`translation.outline.follow_weight` 的**接线** ✗
    //   在此之前三层里两层已就绪、**只差这一跳** ✗：
    //   · 设置项 ✓ 见 `SettingRegistry` 里 `translation.outline.follow_weight` 的登记
    //   · 渲染器 ✓ 见 `KaraokeTextRenderer` 的 `TextOutline.followWeight`（参数 + 消费点 + 换算）
    //   · **调用方零接线** ✗ —— `grep followWeight window/` 曾 0 命中
    //     ⇒ `TextOutline.followWeight` 恒用默认 `false` ⇒ **用户在设置页开关它什么都不会变** ✗
    //   ⚠️ 带默认值 `false` ⇒ **本字段的加入不改动任何既有调用点** ✓（`Appearance` 是 data class，
    //      新增字段会进入 `equals`/`copy` —— 但语义上它确实是"外观"的一部分 ✓ 故可接受 ✓）
    val translationOutlineFollowWeight: Boolean = false,
    val translationOutlineColorR: Int,
    val translationOutlineColorG: Int,
    val translationOutlineColorB: Int,
    //
     // `main_lyric.karaoke.mode`。`LINE` 档不做任何逐字分割。
     //
     // 语义是"关掉逐字，保留整行进度"—— 不是"关掉高亮"。故它只决定
     // 是否把 `wordBoundaries` 交给渲染器，整行进度仍照常参与。
     //
    /**
     * `main_lyric.karaoke.mode` —— **三档互斥**的歌词高亮方式。
     *
     * 用户 2026-09-19：「**给三个选项**，**不逐字（整行高亮）/ 平滑高亮 / 真-逐字高亮**」。
     *
     * ⚠️ 它取代了原先的 `main_lyric.karaoke.enabled`（开关）+ `karaoke.mode`（两档）——
     *   「开关 × 两档」有 4 种组合而真实状态只有 3 种，多出来的那个组合
     *   正是用户反复抱怨的"两个设置项表达同一种状态"。
     */
    val karaokeMode: String,
    /** `main_lyric.translation.color`。译文行颜色（独立可配置，默认 `#B1B6C3`）。 */
    val translationColor: Color,
    /**
     * `main_lyric.translation.color_mode` —— 译文色的**来源**：`manual` / `cover`。
     *
     * 用户 2026-09-19：「**歌词颜色跟随封面取色失效**」——
     * 根因就是这个键此前**不存在**：译文色恒走 `manual`，
     * 于是"跟随封面取色"对主歌词生效、对译文行不生效。
     */
    val translationColorMode: String,
    /**
     * `layout.lines` —— **显示哪几行歌词**（单行 / 双行）。
     *
     * 用户 2026-09-16 原话：「目前的歌词上下行是**已唱歌词和在唱歌词**，
     * 而我的预想中是在唱歌词和未唱歌词。」
     *
     * ⚠️ 2026-09-18：本字段承载的是**合并后的** `layout.lines`（单行 / 双行）；
     *   旧的 `layout.line_mode`（三选）与 `island.experimental_multi_line`（总闸）
     *   已整体删除 —— 两者只表达了两种状态，合并后**选项数 = 真实状态数**。
     *   详见 [LineCount] 的 KDoc。
     */
    internal val lines: LineCount,
    /**
     * `layout.line_gap_px` —— **额外**加到行高上的间距（px）。
     *
     * 用户原话：「一二行歌词间距调整选项也没做。」0 = 只用行高自带留白。
     */
    val lineGapPx: Float,
    /**
     * `lyric.offset_ms` —— 逐字高亮与整行进度的**统一时间偏移**（毫秒）。
     *
     * ## 为什么这个键此前是"死设置"（2026-09-16 修复）
     *
     * 它在注册表里存在（`range = -2000.0..2000.0`）、在 SPW 设置页里有滑杆，
     * 但**渲染层从没读过它** —— 用户拖动滑杆写进了配置，画面**零变化**。
     * 这正是用户投诉「颇多选项无法修改实际设置」的十项之一（BUG-025）。
     *
     * ## 语义与符号约定
     *
     * 正偏移 = 高亮**提前**（用"未来"的位置算进度）；负偏移 = 高亮**滞后**。
     * 用途：音源与歌词时间轴存在固定偏差时对齐（现场版、翻录、时间轴做得糙的 LRC）。
     * 与原实现 0.8.9 一致：`089:ui/IslandLyricsPainter.kt:40` 就是一行加法。
     *
     * ## 为什么同时作用于"整行进度"而不只是"逐字"
     *
     * 二者是同一个时间轴的两种粒度。只偏移逐字会让**逐字与整行互相错位**
     * （整行已唱满而最后一个字还没亮，或反之）—— 那比不偏移更难看。
     * 故本值在 `drawLyricSlots` 里**只算一次**，两个粒度共用。
     */
    val lyricOffsetMs: Long,
    /**
     * `layout.content_align` —— 歌词水平对齐。**原为死设置**（见 [Align] 的 KDoc）。
     *
     * 同时作用于**正文行与译文行** —— 两处若不同步，译文会相对正文偏移，
     * 表现为"左对齐时译文还在中间"。故两处共用 [alignBaselineX] 一个助手。
     */
    internal val contentAlign: Align,
    /**
     * `layout.noLyrics.mode` —— 无歌词时怎么办。**原为死设置**（见 [NoLyricsMode] 的 KDoc）。
     */
    internal val noLyricsMode: NoLyricsMode,
    /**
     * `layout.noLyrics.showTrackTitle` —— 无歌词时是否显示曲名。**原为死设置**。
     *
     * 只在 [noLyricsMode] 为 [NoLyricsMode.PLACEHOLDER] 时起作用：
     * 为 `true` 时占位文本就是曲名（"正在播放：xxx"），
     * 为 `false` 时是一句中性占位（"暂无歌词"）。
     *
     * 为什么这样组合而不是"独立开关"：两者都在描述同一件事
     * ——"没有歌词时那一行画什么"，分开判定会出现"显示了曲名但模式是 HIDE_LINE"
     * 这种自相矛盾的状态（曲名画出来了，可模式说"隐藏歌词行"）。
     */
    val showTrackTitle: Boolean,
    /**
     * `layout.noLyrics.showAlbum` —— 无歌词时是否**同时显示专辑**（2026-09-27 用户拍板）。
     *
     * 用户原话：「**② 新增"作者/专辑"**」。
     *
     * ⚠️ **更正（本轮实测）**：**"作者"早已实现** —— [drawNoLyricsNotice] 里写的是
     * `"$title · $artist"`（2026-09-18 补的移植遗漏），且 [showTrackTitle] 的 KDoc
     * 与注册表 title 本就写着「曲名**与作者**」⇒ 本次**实际新增的只有"专辑"**
     * （`TimelineSnapshot.album` 一直有数据、**无绘制消费点**）。
     */
    val showAlbum: Boolean,
    /**
     * `layout.noLyrics.hideWhenInstrumental` —— 无歌词时**整块文字都不画**（2026-09-19）。
     *
     * 用户原话：「**追加检测到纯音乐时不显示歌词/曲名/作者的开关**」。
     *
     * 与 [noLyricsMode] 的分工是**窗口还在不在**：
     *   · `noLyricsMode = HIDE_ISLAND` → 整个窗口隐藏（生命周期动作，见 [applyVisibility]）；
     *   · 本项为 `true` → 窗口留着（封面/频谱照常），但**那行文字**（占位或
     *     「曲名 · 作者」）不画。
     *
     * ⚠️ 它**只挡文字**，不挡封面与频谱 —— 这正是本项存在的意义：
     *   "把词岛当纯视觉件用"。若连窗口一起藏，那是 `noLyricsMode` 的职责，
     *   两个键做同一件事会让用户困惑。
     */
    val hideWhenInstrumental: Boolean,
    /**
     * `layout.noLyrics.titleInTranslation` —— 无歌词时**额外**在第二行（译文行位置）画曲名。
     *
     * 用户原话：「**增加此类纯音乐允许在译文行显示歌名的开关设置。**」
     */
    val titleInTranslation: Boolean,
    /**
     * `cover.enabled` —— 是否在**左侧信息区**显示专辑封面。
     *
     * ## 它是本轮的"死设置复活"之一
     *
     * 该键在注册表（`SettingRegistry` 的 `cover.enabled` 键）、宿主设置页与右键菜单里都有，
     * 但**渲染层从未读过它** —— 用户在设置里打开它，画面零变化。
     *
     * ## 与 `spectrum.enabled` 的关系（原实现是二选一，我们拆成两个开关）
     *
     * 原实现 0.8.9 的左侧内容是一个**互斥枚举**（`LeadingContent.SPECTRUM | COVER`），
     * 本项目按设置面拆成两个独立开关（见注册表 G 组的注释）。
     * ⚠️ 2026-09-17：频谱已移到**歌词背景**（见 `IslandPanel.drawBackgroundSpectrum`），
     * 不再与封面争左侧槽位 —— 故两者同开时**各画各的**（封面在左、频谱在背景），
     * 原实现那条"二选一"的约束在本项目里**已不存在**。
     */
    val coverEnabled: Boolean,
    /**
     * `cover.scale_with_font` —— 封面尺寸是否**跟随字号**（即跟随歌词区高度）。
     *
     * ## 为什么需要这个开关（2026-09-18 用户点名）
     *
     * 用户原话：
     * > 「歌曲封面要能**随字体缩放**，**或者**提供**随字体缩放和独立调整的开关**。」
     *
     * ## 原状：封面**名义上跟随、实际上被夹死**
     *
     * `leadingCoverSize` 一直用 `areaHeight`（歌词区高度，随字号推导）算尺寸 ——
     * 看起来"本来就跟随"。但它随后 `coerceIn(MIN_COVER_SIZE, MAX_COVER_SIZE)`
     * 而 **`MAX_COVER_SIZE = 56f`**：
     *
     * | 字号 | 歌词区高 | `areaHeight - 20` | 夹取后 |
     * |---|---|---|---|
     * | 22（默认） | ~125 | 105 | **56**（撞上限） |
     * | 40 | ~220 | 200 | **56**（还是 56） |
     *
     * → **字号翻倍，封面一动不动**。用户看到的正是"封面不随字体缩放"。
     *
     * ## 两种语义
     *
     * - `true`（默认）→ **不套上限**，封面随歌词区等比放大（下限仍保留，防过小）
     * - `false` → 保留 `MAX_COVER_SIZE` 夹取（= 改动前的行为）
     *
     * 默认 `true`：用户的主诉求就是"跟着字体走"，且它让封面与词岛整体缩放**不脱节**
     * （词岛高度本来就由字号推导，见 `islandHeightFor`）。
     */
    val coverScaleWithFont: Boolean,
    /**
     * 封面**清晰度（锐化）**（`cover.sharpness`，2026-10-13 BUG-188）。
     *
     * ★ 用户原话：「**能否优化封面显示?使其显示得更清晰可变，在大小形态不变的情况下。**」
     * ★ 范围 `0..100`；★ 它只影响**重采样后的锐度**（大小/形态逐位不变）✓
     */
    val coverSharpness: Int,
    /**
     * 封面**形状**（`cover.shape`，2026-09-21）。
     *
     * `"CIRCLE"` = 圆形（黑胶唱片观感）；其余值 = 圆角矩形（**现状，默认**）。
     */
    val coverShape: String,
    /** 封面是否**转动**（`cover.rotate`，2026-09-21）。 */
    val coverRotate: Boolean,
    /** 封面转一圈用时（秒，`cover.rotate_period_s`，2026-09-21）。 */
    val coverRotatePeriodS: Float,
    /**
     * **黑胶样式**（`cover.vinyl`，2026-09-21）—— 在封面**外围**加一圈黑环。
     *
     * 用户原话：「**旋转圆形封面加上黑边，使其呈现黑胶样式**
     * （在**外围**加黑边，不要侵占封面内容）。」
     *
     * ⚠️ 与 [coverRotate] / [coverShape] 是**三个独立的开关**（不是"开了黑胶就自动转"）——
     * 用户要的是"圆形 + 转动 + 黑边"三者组合出的黑胶观感，
     * 但每一项都能单独开（例如"不转的静态黑胶"也是合理选择）。
     *
     * ⚠️ 环画在封面 `size` **之外** ⇒ 左侧槽位要按 `coverSlotWidth(...)` 加宽，
     * 见 [leadingCoverSize] 与 `paintComponent` 里 `areaLeft` 的推导。
     */
    val coverVinyl: Boolean,
    /**
     * 封面锁（`cover.lock`，**2026-09-30 封面态的全案唯一新键**）。
     *
     * ## 语义（`recon/67` §3.5）
     *
     * **仅**在 `shape.type = "COVER_ONLY"`（悬浮窗形态）下有意义：
     *  · **未锁**（`false`，默认）⇒ 鼠标命中封面 ⇒ **展开**成完整词岛；
     *  · **已锁**（`true`）⇒ 命中**不展开**，只在右上角**弹一下锁头标志**；
     *    ★ 点那个锁头标志 ⇒ **展开**（用户原话「点锁标 = 展开」）。
     *  ⇒ 锁 = **一道防误触的确认**（封面态命中面大，误触概率高于歌词）。
     *
     * ## 为什么它进了 [Appearance]（而其它"锁/交互态"没有）
     *
     * `Appearance` 的字段都是"**由设置快照折叠出来的外观**"。本项确实是设置项，
     * 且**消费点在外观层**（决定画不画锁标、命中时展不展开）——
     * 与 `hitCoverEnabled` 同一性质（那个也是交互开关，同样住在这里）。
     *
     * ⚠️ **它落盘** ⇒ **重启后仍保持上锁**（v8 定案，§9-U）。这是**刻意**的：
     *    用户要的是"锁住"，重启就解开等于没锁。
     */
    val lockCover: Boolean,
    /**
     * 词岛边框（`island.border.*`，2026-09-18 用户点名新增）。
     *
     * 用户原话：「增加词岛**边线/边框**选项。」
     *
     * 四项合成一个视觉元素：开关 / 宽度 / RGB 三通道色。
     * 画在**背景形状的轮廓**上（与 `shape.type` 的形状一致），
     * 且**在歌词之前画** —— 否则边框会压在文字上。
     */
    val borderEnabled: Boolean,
    /**
     * `island.border.width_tenth_px`（0..80）—— **单位是十分之一像素**。
     *
     * 见注册表里该键的 KDoc：SPW 滑杆写盘是 Float，若直接用"像素"作整数单位，
     * 用户拖到 `0.5` 会被 `coerceInt` **截断成 0** → **边框直接消失且不报错**。
     * 放大 10 倍作单位后，`0.1 px` 的分辨率与"宿主是否写 Float"无关。
     */
    val borderWidthTenthPx: Int,
    /**
     * 「溶边」过渡带占**岛体高度**的百分比（`island.border.dissolve_band_percent`）。
     *
     * ★★★ 2026-10-13（**BUG-186**）：用户指令「**既然与边线宽度解耦，引入新键给用户调节渐变范围**」。
     *
     * ★ 仅在 `borderMode == EDGE_DISSOLVE` 时被读（★ 其余两档走 `borderWidthTenthPx`）✓
     * ★ 默认 **50** = 从边缘铺到岛体中线（= BUG-185 已交付的观感 ⇒ 加键**零变化**）✓
     * ★ 上限见 [dev.cdl.settings.MAX_DISSOLVE_BAND_PERCENT]（50 = 几何上限）✓
     */
    val borderDissolveBandPercent: Int,
    /**
     * 边框**样式**（`island.border.mode`）：`SOLID` / `FADE_OUT`。
     *
     * ★★ 2026-09-29 新增（用户拍板 D2/D3）。`SOLID` = 改动前的观感（**默认**）；
     * `FADE_OUT` = 不画实线，边缘由内向外柔和淡出（用户原话「无边框 / 无边界」）。
     * ★ D1 = **B（不外扩）** ⇒ 淡出带只画在形状**内部**。
     */
    val borderMode: String,
    val borderColorR: Int,
    val borderColorG: Int,
    val borderColorB: Int,
    /**
     * `island.border.color_mode` —— 边框颜色的**来源**：`manual` / `cover`。
     *
     * 用户诉求（2026-09-18）：「**允许词岛边框从封面取色**」。
     * 与 [backgroundColorMode] / [spectrumColorMode] 同一套词汇与同一套解析
     * （见 `CoverPalette`），故"跟随封面"的语义在三个元素上完全一致。
     */
    val borderColorMode: String,
    /**
     * `spectrum.enabled` —— 是否在**歌词背景**画实时频谱。
     *
     * ## 与 [coverEnabled] 的关系（**已解耦**）
     *
     * 原记：原实现 0.8.9 的左侧内容是一个互斥枚举（`LeadingContent.SPECTRUM | COVER`），
     * 本项目拆成两个独立开关后"两者可能同时打开"，故约定**封面优先**。
     *
     * 实为（2026-09-17，用户要求"频谱置于歌词背景"）：频谱不再占左侧槽位，
     * 于是两者画在**不同图层**上（封面在左侧、频谱在歌词背景）——
     * "同时打开"的结果**天然确定**，不再需要"谁优先"这条约定。
     *
     * ## 为什么需要这个字段（而不是让渲染层直接读设置）
     *
     * 与其余外观项同一个纪律：渲染层**只读快照**，不自己去问设置系统。
     * 否则"哪一帧读了、哪一帧没读"会变得不可推理，且渲染层要处理 I/O 失败。
     */
    val spectrumEnabled: Boolean,
    /**
     * `main_lyric.color_mode` —— 歌词颜色的**来源**：`manual` / `cover`。
     *
     * `manual` = 用 [sungColor] / [unsungColor]（**逐像素不变**，这是硬要求）；
     * `cover` = 由封面主色派生（见 [sungColorFor] / [unsungColorFor]）。
     *
     * 默认值是 `manual`（注册表 `groupG`），也正是用户当前配置的取值 ——
     * 故本项接线后**用户的画面不应有任何变化**。
     */
    val lyricColorMode: String,
    /** `background.color_mode` —— 背景颜色的来源。解析见 [backgroundColorFor]。 */
    val backgroundColorMode: String,
    /**
     * `background.color` —— **手动档**的底色（`background.color_mode = manual` 时生效）。
     *
     * 用户原话（2026-09-22）：「**有些选项提供自选色但是并没有提供选色入口，
     * 如背景设色**」。
     *
     * 真相**比"缺入口"更严重**：`background.*` 此前**没有任何颜色键** ——
     * 手动档用的是**写死的常量** [BASE_BACKGROUND]（`#14161A`），用户改不了。
     * 这与 `spectrum.color` 是**同一个问题的同一个解法**（后者 2026-09-17 已修，
     * 见 [spectrumColor] 的 KDoc）—— 本次是**同族排查**在背景上发现的同一形态。
     *
     * 默认 `#14161A`，与旧常量 [BASE_BACKGROUND] **逐位相同** ⇒
     * 本项接线后**开箱逐像素不变**，只是从"用户改不了"变成"能改"。
     *
     * ## ⚠️ 哪一档读它（两档互不干扰，说清边界）
     *
     * | `background.color_mode` | 底色来自 | 读本字段？ |
     * |---|---|---|
     * | `manual` | 本字段 | **是** |
     * | `cover` | 封面主色派生的深色 | 否（走 `CoverPalette.darkFrom`） |
     * | `cover_image` | 封面图本身 + 同源蒙版 | 否（走 [CoverBackdrop]） |
     */
    val backgroundManualColor: Color,
    /**
     * `background.cover.angle_deg` —— 「封面图」档的**旋转角度**（0..360 度）。
     *
     * 用户规格（2026-09-22）：「**允许用户调整封面角度（0-360 度）**，
     * **旋转后封面自动缩放铺满**词岛背景，**不再设调整设置**。」
     *
     * ⚠️ **没有配套的"缩放"键**（用户明确说「不再设调整设置」）——
     *    缩放是**算出来**的：见公开纯函数 [coverFillScale]（`IslandPolicy.kt`），
     *    它保证"旋转后等比放大到仍然盖住整个岛体"，且**绝不拉伸**。
     *
     * 默认 `0`（不旋转）⇒ 该档在默认角度下就是经典 cover 铺满。
     */
    val backgroundCoverAngleDeg: Int,
    /**
     * `spectrum.color_mode` —— 频谱颜色的来源。解析见 [spectrumColorFor]。
     *
     * 原记（已过期）：频谱的绘制尚未接线（`spectrum.enabled` 与它同为死设置）。
     * 实为（2026-09-17）：频谱子系统已接线 —— 数据源是 `ProcessSpectrum`
     * （WASAPI 进程回环），绘制在 `IslandPanel.drawBackgroundSpectrum`。
     * 故本项**视觉已生效**：`cover` 档下频谱色跟随封面主色（`coverRgb` 由
     * 封面图每帧算出并传进绘制），`manual` 档用固定青绿。
     */
    val spectrumColorMode: String,
    /**
     * `spectrum.color` —— 频谱色（`spectrum.color_mode = manual` 时生效）。
     *
     * 用户原话：「**自行设定颜色也没做到**」——
     * 此前手动档是硬编码常量，用户**没有键可改**。本项就是那个缺失的键。
     *
     * 默认 `#84D8BC`，与旧常量 `MANUAL_SPECTRUM_COLOR` 逐位相同 → 开箱零变化。
     * `alphaAllowed = false`（与其余颜色项一致，见总表 §1.6）。
     */
    val spectrumColor: Color,
    /**
     * ★★★ 2026-10-15（**§3.75**）：`island.right_slot.spectrum_color_mode` ——
     * **插槽频谱自己的取色档位**（用户拍板「完整取色模式」）。
     *
     * 值域 = 主频谱那五档 **+ `follow`**（[RIGHT_SLOT_SPECTRUM_MODE_FOLLOW]）：
     * `follow`（**默认**）/ `manual` / `cover` / `level` / `cover_flow` / `cover_camo`。
     *
     * ★ 默认 `follow` = **跟随主频谱** ⇒ 开箱**逐像素不变**（零回归）✓
     * ★ 未知值 / 缺键 ⇒ 同样回落 `follow`（由 [rightSlotSpectrumColorModeFor] 归一）。
     */
    val rightSlotSpectrumColorMode: String,
    /**
     * ★★★ 2026-10-15（**§3.75**）：`island.right_slot.spectrum_color` ——
     * 插槽频谱在 `manual` 档下的**自选色**。
     *
     * ★ 与 `spectrum.color`（主频谱的手动色）**是两个键** —— 这正是本条诉求
     *（「**开放对插槽频谱的独立选色**」）的全部意义：改插槽不得改主频谱 ✓
     * ★ 默认 `#84D8BC`，与 `spectrum.color` 的默认**逐位相同** ⇒
     *   「切到 manual 但没改过颜色」时插槽观感与主频谱一致（不会突然变一个色）✓
     */
    val rightSlotSpectrumColor: Color,
    /**
     * ★★★ 2026-10-16（**台账 §3.77-F**）：`island.right_slot.radial_inner_percent` ——
     * **放射状**档的内半径（百分比整数）。
     *
     * 用户诉求（逐字）：「**允许调整环形频谱的跳动幅度和范围**」+ 拍板
     * 「**径向范围（内外半径可调）**」·「**给放射+环形两档**」。
     *
     * ★ 出厂值 [dev.cdl.render.POLAR_RADIAL_INNER_PERCENT_DEFAULT]（= **0**，
     *   即"条交于圆心"）⇒ **开箱逐像素不变**（零回归）✓
     * ★ 归一（夹取）在 `render/SpectrumGeometry.kt` 的 `polarInnerRatioOf` 里
     *   —— **唯一出口**，本字段只负责"把键读出来" ✓
     */
    val rightSlotRadialInnerPercent: Int,
    /**
     * ★★★ 2026-10-16（**台账 §3.77-F**）：`island.right_slot.ring_inner_percent` ——
     * **环状**档的内半径（百分比整数）。
     *
     * ★ 出厂值 55（= 改动前的 `SpectrumPolarSpec.CIRCULAR_INNER_RATIO`）⇒ 零回归 ✓
     * ★★ **为什么与放射档各一个键**：两档出厂值不同（0 / 55），共用一个键就必然
     *   让其中一档的默认值变掉 ⇒ **必有一档回归** ✗
     */
    val rightSlotRingInnerPercent: Int,
    /**
     * ★★★ 2026-10-16（**台账 §3.77-F**）：`island.right_slot.polar_outer_percent` ——
     * 极坐标**两档共用**的外半径（百分比整数）。
     *
     * ★ 出厂值 100（= 改动前的 `min(w,h)/2`）⇒ 零回归 ✓
     */
    val rightSlotPolarOuterPercent: Int,
    /**
     * `spectrum.anchor` —— 频谱**贴哪条边**：`BOTTOM`（贴底，默认）/ `TOP`（贴顶）。
     *
     * 用户原话：「频谱**相对歌词框底边/顶边高度**也没做到」。
     *
     * 为什么"贴哪条边"要单独成键而不是靠"高度"隐含：
     * 柱子的**生长方向**与"贴哪条边"是同一件事的两面 ——
     * 贴底边时柱子从下往上长，贴顶边时从上往下长。若只有高度键，
     * 用户无法表达"我想让频谱从顶部垂下来"，而那是一个完全合理的观感选择。
     */
    val spectrumAnchor: String,
    /**
     * `spectrum.height_percent` —— 频谱高度占**歌词区高度**的百分比（10..100，默认 100）。
     *
     * 用户原话同上（「相对歌词框……高度」）。
     *
     * ⚠️ 是"占歌词区的百分比"而不是像素：歌词区高度随字号与行数变化，
     * 用像素会让"换个字号频谱就不成比例"。这与 `background.opacity` 用百分比同理。
     */
    val spectrumHeightPct: Int,
    /**
     * `spectrum.bar_pitch_mode == AUTO` —— 柱间距按词岛宽度自动推导。
     *
     * 与 `spectrumAnchor == TOP` 同一处置：在 [Appearance.from] 里把字符串
     * 解析成布尔，绘制层不再碰字符串。
     */
    val spectrumBarPitchAuto: Boolean,
    /** `spectrum.bar_pitch_tenth_px` —— 固定档的柱间距，**单位 0.1 像素**（1..200）。 */
    val spectrumBarPitchPx: Int,
    /**
     * `spectrum.secondary.mode` —— 副频谱画在哪：`OFF` / `BOTTOM` / `TOP` / `BOTH`。
     *
     * 用户原话见注册表该项的 KDoc（它是被用户收编的"进度染色"视觉）。
     */
    val spectrumSecondaryMode: String,
    /** `spectrum.secondary.opacity_percent` —— 副频谱的不透明度（0..100）。 */
    val spectrumSecondaryOpacityPct: Int,
    /**
     * `island.hit.cover` —— **封面是否进"命中域"**（2026-09-19）。
     *
     * 用户原话：「**追加封面命中开关。**」
     *
     * `true` 时，封面矩形与歌词字形一样参与命中判定（`REGIONAL_CLICK_THROUGH`
     * 下不穿透、可拖动词岛、会展开播放控件）；`false`（默认）时封面那一块
     * 是**穿透空洞** —— 与改动前一致。
     *
     * ⚠️ 它**只影响命中判定**，不影响任何绘制。默认 `false` 的理由见注册表该项 KDoc。
     */
    val hitCoverEnabled: Boolean,
    /**
     * `main_lyric.letter_spacing_tenth_px` —— **主歌词字间距**，单位 0.1 px
     * （2026-09-19）。
     *
     * 用户原话：「**允许调整主副歌词的字间距，可选以一定比例联动调整。**」
     *
     * ## 实现：`TextAttribute.TRACKING`，不是"逐字符画"
     *
     * Java 的 `TextLayout` **没有**"字间距"参数，但字体属性表里有
     * `TextAttribute.TRACKING` —— 语义是"**字距占字号的比例**"。
     * 实测（JDK 21 / Microsoft YaHei UI）：`tracking = 0.1`、`fontSize = 22`
     * 时每字加 `2.2 px`，整行 advance 恰好增加 `0.1 × 22 × 字符数`。
     *
     * 于是换算只有一步：`tracking = (本值 / 10f) / fontSize`。实现是
     * [dev.cdl.render.letterSpacingTracking]，字体构造是
     * [dev.cdl.render.fontWithLetterSpacing] —— 两者都是**公开纯函数**，
     * 因为这条单位链任何一环写错都**不会报错**（只表现为"字距看起来不太对"），
     * 必须能被探针断言，见 `render/LetterSpacing.kt`。
     *
     * ## 为什么这个方案让"命中"自动保持准确（**关键**）
     *
     * 字距加在**字体**上 → `TextLayout` 的 `advance`、每个 caret 的位置、
     * `getOutline` 的墨迹**全部**按新字距重算。而 `drawnRows` 里存的就是
     * **绘制时用的那个 `TextLayout` 实例**（见 [IslandPanel.drawnRows]），
     * `GlyphHitTester` 又只从 `layout` 取度量与 caret ——
     * 故"画在哪"与"点在哪"共用同一份几何，**字距变化不可能让命中错位**。
     *
     * 反例（本方案的对照）：若改成"逐字符分别 `drawString` 再手工累加 x"，
     * 就必须在命中侧**再写一遍**同样的累加 —— 那是第二份排版实现，
     * 迟早与绘制漂移。**这正是本项目反复记录的"两份实现"坑。**
     *
     * ⚠️ 缓存：`RenderCache.setFont` 用 `Font.equals` 判变化，而
     *   `Font.equals` **会比较属性表**（实测 `base.equals(tracked) == false`），
     *   故改字距会**自动作废** `TextLayout` 缓存，不需要额外的失效逻辑。
     */
    val letterSpacingTenthPx: Int,
    /**
     * `translation.letter_spacing.follow_main` —— 副歌词字间距是否跟随主歌词
     * （2026-09-19，默认 `true`）。
     *
     * ## "按比例联动"是 [letterSpacingTenthPx] 那套方案的**固有性质**
     *
     * 副歌词的字号是 `fontSize × TRANSLATION_SCALE`，而 `TRACKING` 的语义是
     * "占**字号**的比例" —— 故**同一个 tracking 值**用在副歌词的小字号上，
     * 像素字距自动是 `主字距 × (副字号 / 主字号)`。**不需要额外乘一个系数。**
     *
     * `false` → 副歌词用 `tracking = 0`（不加字距）。
     *
     * ⚠️ 本项目**没有**"副歌词自己的字距"键：用户原话只要求"**可选**联动"，
     *   没有要求副歌词独立可调。真需要时再在 `false` 分支上加一项即可 ——
     *   现在加会是一个"用户没要、且没有明确默认值"的键。
     */
    val translationLetterSpacingFollowMain: Boolean,
    /**
     * ★ **控件条**出现时的动效风格（`FADE` / `SLIDE` / `POP`）。
     *
     * 为什么要放进 `Appearance`：绘制层**不该自己去读设置**（它与设置的耦合
     * 只应通过这一个不可变模型）。而风格是"怎么画"的一部分，与形态/圆角同类。
     *
     * ⚠️ 只有**跟随全局风格**的动效读它（见 `Motions.resolve`）—— 词岛自身的
     * 淡入淡出固定 `FADE`，不读这个字段。
     *
     * ★★ 2026-09-26 **拆散**：本字段原为 `motionStyle`，被**控件条**与**歌词退场**
     *   两处共用（`Motions.CONTROLS_APPEAR` / `Motions.LYRIC_EXIT`）——
     *   那与"两个组件各有自己的子项"（`controls.animation_style` /
     *   `lyric.animation_style`）**矛盾**：一个字段喂不了两个组件。
     *   ⇒ 拆成本字段与 [lyricMotionStyle]。
     *   ⇒ 消费点：`Motions.resolve(Motions.CONTROLS_APPEAR, local.controlsMotionStyle)`。
     */
    val controlsMotionStyle: MotionStyle,
    /**
     * ★ **歌词行退场**时的动效风格。为什么拆见 [controlsMotionStyle] 的 KDoc。
     *
     * 由 `AnimationStyleResolver.effective(`lyric.animation_style`, `animation.style`, …)`
     * 合成；出厂风格取 `Motions.LYRIC_EXIT.style.name`（两条都是 `SLIDE`）。
     * ⇒ 消费点：`Motions.resolve(Motions.LYRIC_EXIT, local.lyricMotionStyle)`。
     */
    val lyricMotionStyle: MotionStyle,
) {
    /**
     * `main_lyric.color_mode` 解析后的**已唱歌词色**。
     *
     * `manual` → 原样返回 [sungColor]（同一个对象，逐像素不变）。
     */
    fun sungColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(lyricColorMode, sungColor, coverRgb, CoverPalette::brightFrom)

    //
     // `main_lyric.color_mode` 解析后的**未唱歌词色**。
     //
     // `manual` → 原样返回 [unsungColor]。
     // `cover` → 取已唱色（封面色相）按 [UNSUNG_LYRIC_DIM] 暗化的版本。
     //
     // ## 为什么未唱色是"派生"而不是"也取一个封面色"
     //
     // 已唱与未唱是**同一句话的两个状态**，人眼靠**明度差**区分它们。
     // 若两者各取一个封面色（比如已唱取主色、未唱取次色），
     // 两个颜色的明度可能几乎相同 —— 高亮就"看不见"了。
     // 同色相 + 固定明度差是唯一能保证对比度稳定的做法，
     // 与译文行用 [UNSUNG_TRANSLATION_DIM] 暗化是同一个理由。
     //
    /**
     * `main_lyric.translation.color_mode` 解析后的**译文色**。
     *
     * `manual` → 原样返回 [translationColor]。
     * `cover` → 取封面主色的亮化版（与已唱色同一个取向 [CoverPalette.brightFrom]）——
     *   译文行与主歌词同属"文字"，用同一种派生方式才能保持色调一致。
     */
    fun translationColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(
            translationColorMode, translationColor, coverRgb, CoverPalette::brightFrom,
        )

    fun unsungColorFor(coverRgb: Int?): Color =
        if (lyricColorMode == CoverPalette.COVER && coverRgb != null) {
            CoverPalette.dim(CoverPalette.brightFrom(coverRgb), UNSUNG_LYRIC_DIM)
        } else {
            unsungColor
        }

    /**
     * `background.color_mode` 解析后的**底色**（不含 alpha）。
     *
     * ## ⚠️ 2026-09-22 修订：手动档不再是硬编码常量
     *
     * **原记**：注册表里 `background.*` **只有 `opacity` 与 `color_mode` 两项**
     * （没有独立的颜色键），故手动档只能是内置常量 [BASE_BACKGROUND]。
     *
     * **实为**（用户原话）：「**有些选项提供自选色但是并没有提供选色入口，
     * 如背景设色**」。新增 `background.color`（COLOR）后，手动档改为读
     * [backgroundManualColor] —— 默认值与旧常量**逐位相同**（`#14161A`），
     * 故开箱**逐像素不变**，只是从"用户改不了"变成"能改"。
     *
     * ## 三档的行为
     *
     * | `background.color_mode` | 返回 |
     * |---|---|
     * | `manual`（默认） | [backgroundManualColor]（默认 `#14161A`，逐像素不变） |
     * | `cover` | 由封面主色派生的深色（[CoverPalette.darkFrom]） |
     * | `cover_image` | **不走本函数** —— 由 [drawCoverImageBackdrop] 整块烘焙；<br>本函数只在"没有封面图可烘"时兜底返回手动色 |
     *
     * ⚠️ alpha（`background.opacity`）**不在这里乘** —— 它由
     * [IslandPanel.backgroundWithOpacity]（纯色档）或
     * [IslandPanel.drawCoverImageBackdrop]（封面图档）各自处理。
     */
    fun backgroundColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(
            backgroundColorMode,
            backgroundManualColor,
            coverRgb,
            CoverPalette::darkFrom,
        )

    /**
     * `spectrum.color_mode` 解析后的**频谱色**。
     *
     * `manual` → [spectrumColor]（`spectrum.color`，默认 `#84D8BC`）。
     * `cover` → 与歌词色同一套亮色派生（保色相、压饱和、拉满明度）。
     *
     * ## ⚠️ 2026-09-17 修订：手动档不再是硬编码常量
     *
     * **原记**：注册表里 `spectrum.*` **只有 `enabled` 与 `color_mode` 两项**
     * （没有独立的颜色键），故手动档只能是内置常量 [MANUAL_SPECTRUM_COLOR]。
     *
     * **实为**（用户原话）：「**自行设定颜色也没做到**」。
     * 新增 `spectrum.color`（COLOR）后，手动档改为读它 ——
     * 默认值与旧常量**逐位相同**（`#84D8BC`），故开箱观感零变化，
     * 只是从"用户改不了"变成"能改"。
     *
     * ## ⚠️ 另一处修订：`cover` 档此前会**静默回退**
     *
     * 用户原话：「**频谱根据封面取色也没有做到**」。
     * 根因**不在本函数**（本函数的解析逻辑是对的），而在
     * `coverImageFor` —— 它只在 `cover.enabled` 时才加载封面图，
     * 于是"不开显示封面"时 `coverRgb` 恒为 `null` → 这里静默走 `manual`。
     * 已修（见 `coverImageFor` 的 KDoc）：**取色与显示解耦**。
     */
    fun spectrumColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(spectrumColorMode, spectrumColor, coverRgb, CoverPalette::brightFrom)

    /**
     * `island.border.color_mode` 解析后的**边框色**（2026-09-18 加）。
     *
     * 用户诉求：「**允许词岛边框从封面取色**」。
     *
     * ## 为什么用 `brightFrom` 而不是原样取封面主色
     *
     * 背景色的 `cover` 档走的是 `darkFrom`（把主色压暗当底衬）。若边框直接用
     * **原色**，它会与背景几乎同色 —— 用户开了"跟随封面取色"却看不到边框，
     * 只会以为"这个选项没生效"。`brightFrom` 与歌词、频谱同一个取向，
     * 于是"边框 vs 背景"的明度差与"歌词 vs 背景"一致，必然可见。
     *
     * `manual` → 原样返回 `Color(borderColorR, borderColorG, borderColorB)`。
     */
    fun borderColorFor(coverRgb: Int?): Color =
        CoverPalette.resolve(
            borderColorMode,
            Color(borderColorR, borderColorG, borderColorB),
            coverRgb,
            CoverPalette::brightFrom,
        )

    /**
     * 主歌词的**字距比例**（`java.awt.font.TextAttribute.TRACKING` 的值）。
     *
     * 换算实现已移到 [dev.cdl.render.letterSpacingTracking]（**公开纯函数**）。
     * 移出去的理由不是"整洁"，而是**可证伪**：单位链（0.1px → px → 占字号的比例）
     * 任何一环写错都**不会报错**，只表现为"字距看起来不太对"；
     * 而本类所在的 `window` 包在探针里够不到 `Appearance` 的内部，
     * 故把这条换算按 `SpectrumGeometry` 的先例提成公开纯函数。
     *
     * ## 为什么副歌词直接复用这个值（"按比例联动"的全部实现）
     *
     * 副歌词字号 = `fontSize × TRANSLATION_SCALE`，用**同一个 tracking** 时
     * 它的像素字距 = `tracking × 副字号 = 主像素字距 × (副字号 / 主字号)` ——
     * 正是用户要的"**以一定比例联动**"。若改成把**像素值**传给副歌词，
     * 得到的是"主副字距一样宽"，在视觉上副歌词（小字）会显得疏得多。
     */
    fun mainLetterTracking(): Float = letterSpacingTracking(letterSpacingTenthPx, fontSize)

    /**
     * **主歌词描边**（`main_lyric.outline.*`）。`null` = 不描边。
     *
     * 宽度从"十分之一像素"换算回像素（见注册表该键的 KDoc：
     * SPW 滑杆写 Float，用"像素"作整数单位会被截断成 0）。
     *
     * ⚠️ **2026-09-19 恢复**：本函数与 [translationOutlineFor] 曾被一次重构**误删**
     * （只留下 4 处调用点、没有改名后的替代），导致 `compileKotlin` 报
     * `Unresolved reference 'mainOutlineFor'`。从 `IslandWindow.kt.bak-fontwire` 恢复。
     * **教训：重构时"删了定义但调用点还在"是编译期能立刻抓到的错 ——
     * 前提是每改完一个文件就跑一次编译**（本轮有代理连续三次留下编译不过的中间态，
     * 每次都要中断别人的工作去修）。
     */
    internal fun mainOutlineFor(): TextOutline? {
        // ★★ 2026-10-08（U3 取证）：**转调**顶层公开纯函数 `resolveOutline`。
        //
        // ## 为什么必须转调（不是"整理代码"）
        //
        // 用户第二次报障证明"开关默认 false"不是全部原因 ——
        // 开关已开、颜色已改，真机仍无变化。要定位断点**只能**靠探针，
        // 而探针（独立编译模块）够不到 `internal fun` / `internal class`。
        // ⇒ 算式提成 `public fun resolveOutline(...)`，
        //   本函数只负责**喂参数**（这是"同语义唯一实现点"纪律 10 的落法）。
        //
        // ★ 若本函数与探针各留一份算式，就是**两个真相** ——
        //   "探针绿了但真机没变"会再次发生，且这次连线索都没有。
        val r = resolveOutline(
            enabled = mainOutlineEnabled,
            widthTenthPx = mainOutlineWidthTenthPx,
            r = mainOutlineColorR,
            g = mainOutlineColorG,
            b = mainOutlineColorB,
        ) ?: return null
        return TextOutline(r.widthPx, r.argb)
    }

    /**
     * **副歌词描边**（`translation.outline.*`）。`null` = 不描边。
     *
     * `translation.outline.sync_with_main = true`（默认）时**整组**跟随主歌词 ——
     * 包括"主歌词没开描边"这一情况（那时副歌词也不描边）。
     * 这正是同步的语义：用户不必在两边各调一遍。
     */
    internal fun translationOutlineFor(): TextOutline? {
        if (translationOutlineSync) return mainOutlineFor()
        // ★★ 2026-10-08（U3 取证）：同 `mainOutlineFor()`，转调公共纯函数。
        //   ⇒ 「副描边自己的那三个键」这条链与主描边走**同一个算式**，
        //     探针断言 `resolveOutline` 就等于断言副描边的解析。
        val r = resolveOutline(
            enabled = translationOutlineEnabled,
            widthTenthPx = translationOutlineWidthTenthPx,
            r = translationOutlineColorR,
            g = translationOutlineColorG,
            b = translationOutlineColorB,
        ) ?: return null
        return TextOutline(
            r.widthPx,
            r.argb,
            // ★ 2026-09-23 由 lead 补的接线 ✗ —— 在此之前这三行**没有**这一项，
            //    于是 `followWeight` 恒为 `false` ⇒ 用户开「副歌词描边跟随字体粗细」
            //    **什么都不会变** ✗（设置项与渲染器都就绪，只差这一跳 ✗）。
            //    ⚠️ 与 `mainOutlineFor()` 的 `weightLinkPercent` 是**两套独立开关** ✗：
            //    副歌词用本项（布尔，跟随/不跟随 ✓），主歌词用
            //    `main_lyric.outline.weight_link_percent`（百分比，半跟随 ✓）。
            followWeight = translationOutlineFollowWeight,
        )
    }

    /**
     * **主歌词描边的公开出口** —— 供探针断言"设置 → 描边值"这条链。
     *
     * ## 为什么需要它（这是 U3 取证的**唯一**机器手段）
     *
     * [mainOutlineFor] 是 `internal fun`、返回 `internal data class TextOutline`。
     * 而**探针是独立编译模块** ⇒ 那两样都看不见 ⇒ **一个值都断言不了**。
     *
     * ★ 本函数**只做一件事**：把 [mainOutlineFor] 的结果**翻译成公开类型**
     *   [dev.cdl.render.ResolvedOutline]。
     *   **不含任何新语义** —— 没有它，"探针能测的那份"与"真机画的那份"
     *   就会分裂成两个真相（本项目最忌讳的病）。
     *
     * ⚠️ 与 [mainOutlineFor] 的关系：**转调，不是并行实现**
     *   ⇒ `mainOutlineFor()` 改了什么（比如新加一项联动），本函数**自动**跟着变。
     */
    fun mainOutlineResolved(): dev.cdl.render.ResolvedOutline? =
        mainOutlineFor()?.let { dev.cdl.render.ResolvedOutline(it.widthPx, it.argb) }

    /**
     * **副歌词描边的公开出口** —— 同 [mainOutlineResolved]，但走 [translationOutlineFor]。
     *
     * ★ 这一格**特别重要**：`translation.outline.sync_with_main` 的**短路规则**
     *   （同步开 ⇒ 整组返回主歌词那份）**只**住在 [translationOutlineFor] 里。
     *   若探针自己拿公开字段复算一遍"要不要跟主歌词"，那条规则就有了**两个真相** ——
     *   将来改同步语义时只改一处 ⇒ 探针与真机分叉。
     *   ⇒ 故必须**转调**，而不是让探针复算。
     */
    fun translationOutlineResolved(): dev.cdl.render.ResolvedOutline? =
        translationOutlineFor()?.let { dev.cdl.render.ResolvedOutline(it.widthPx, it.argb) }

    companion object {
        fun from(fresh: SettingsSnapshot): Appearance {
            val registry = SettingRegistry
            fun bool(key: String, fallback: Boolean): Boolean =
                registry.descriptor(key)?.let { runCatching { fresh.boolean(it) }.getOrDefault(fallback) }
                    ?: fallback
            fun int(key: String, fallback: Int): Int =
                registry.descriptor(key)?.let { runCatching { fresh.int(it) }.getOrDefault(fallback) }
                    ?: fallback
            fun text(key: String, fallback: String): String =
                registry.descriptor(key)?.let { runCatching { fresh.text(it) }.getOrDefault(fallback) }
                    ?: fallback
            // ★★★ 2026-09-29 新增（修 B2：描边跟随字重恒失效）。
            //
            // ## 为什么需要在**这里**提取一个局部变量
            //
            // `fontBold` 同时是 [Appearance] 的**构造参数** —— 而在本 `from()` 里
            // 它**尚未构造出来**（正在求值名字实参）⇒ 直接写 `fontBold` 会
            // **`Unresolved reference`**（编译期即报，不是运行期）。
            // 故先把**取值表达式**提成局部 `val`，构造参数与描边折算**共用同一个值**。
            //
            // ## 为什么是它（而不是去解析字体）
            //
            // 描边折算是 `基准 × (1 + (字重倍率 - 1) × 联动比例)`，其中"字重倍率"的
            // **唯一出口**是 `render/KaraokeTextRenderer.karaokeFontWeightScale`（返回
            // `2f` = 加粗 / `1f` = 常规）。而该函数内部就是 `if (font.isBold()) 2f else 1f`，
            // 与 `fontBold` **同义**（渲染层正是用这个布尔去 `deriveFont(Font.BOLD)`）。
            // ⇒ 用 `fontBold` 既**同源**、又**不必在构造期解析字体**（后者会触发
            //   宿主 jar 扫描 / 族表查找，在设置快照路径上是不可接受的代价）。
            //
            // ★ 纪律 10「首选修法 = 合并成唯一出口」：本处不另造判据，而是复用既有语义。
            val fontBold = text("main_lyric.font.weight", "NORMAL") == "BOLD"

            // ★★ 2026-09-30（封面态 S5）：本态（`shape.type = COVER_ONLY`）的两处
            //    **死锁**处置 —— 同一个局部量喂给下面两处，保证"本态"在这两处
            //    **判据同源**（纪律 10：合并成唯一出口；两处各写一次 `text("shape.type")`
            //    迟早会漂移，比如将来改判据时只改一处）。
            //
            // ## 为什么在这里提成局部量
            //
            // 照 `fontBold` 的先例：`shapeType` 本身是 [Appearance] 的构造实参，
            // 在 `from()` 里**尚未构造出来** ⇒ 不能写 `shapeType == ...`（编译期报错）。
            // 故先把**取值表达式**提成局部 `val`，构造参数与两处处置共用同一个值。
            //
            // ## 处置的是哪两处（`recon/67` §3.4）
            //
            // | # | 死锁 | 现象 |
            // |---|---|---|
            // | **D1** | `island.hit.cover` 默认 `false` | 用户关着它时 `hitCover` 恒 false ⇒ **本态永远展开不了** |
            // | **D2** | `cover.enabled` 若为 `false` | `drawnCoverBounds` 恒 `null` ⇒ **画不出封面、又命中不到封面** ⇒ 彻底死锁 |
            //
            // ## 处置口径（§9-B / §3.4，均为 ①）
            //
            // **本态下强制视为开启**（读值仍照读，只是**取或 `true`**）——
            // ★ **绝不写回键值**（那会**偷改用户配置**，本项目已栽过：BUG-054）。
            // 用户关掉这两项的意图是"平时不要把封面画出来/别让封面吃点击"，
            // 而他**主动选了封面态** = 明确要求"就要封面" ⇒ 本态下这两项**无意义**。
            // 与 D2 同理的既有先例：`shape.corner_radius` 在 `SQUARE`/`NOTCH` 下不生效。
            val coverOnlyShape = text("shape.type", "SQUARE") == "COVER_ONLY"

            fun color(key: String, fallback: Int): Color =
                registry.descriptor(key)?.let { descriptor ->
                    runCatching {
                        val raw = fresh.raw(descriptor.key)
                        (descriptor.coerce(raw) as Int).toColor()
                    }.getOrDefault(fallback.toColor())
                } ?: fallback.toColor()

            /**
             * 由 **RGB + 明度** 四根滑杆合成一个颜色（2026-09-17「方案 A」）。
             *
             * ## 为什么是"合成"而不是读一个键
             *
             * SPW **没有颜色控件**，而用户明确要"RGB 三色滑杆 + 明度滑杆"、
             * 且"设置项不要引去其他窗口"（即不要跳自绘取色器）。
             * 故颜色的真相源就是这 4 根滑杆 —— 本函数是**唯一的合成点**。
             *
             * ## 明度的语义
             *
             * `最终通道 = 原通道 × (明度/100)`：`100` = 原色（默认），`0` = 全黑。
             * 它是一个**整体调暗**的旋钮（见 `groupF()` 的注释块说明为何不用 HSL 的 L）。
             *
             * ## 每一级都夹一次
             *
             * `int(...)` 已按注册表的 `range` 收敛过，这里再 `coerceIn` 是**防御**：
             * 注册表被手改坏时不至于算出负通道或 >255 的通道（那会让 `Color` 抛异常）。
             *
             * @param prefix 键前缀（如 `main_lyric.fill.color`）
             * @param fallback 设置系统坏掉时用的打包 RGB（与注册表默认值一致）
             */
            fun mixerColor(prefix: String, fallback: Int): Color {
                val base = fallback.toColor()
                val r = int("$prefix.r", base.red).coerceIn(0, 255)
                val g = int("$prefix.g", base.green).coerceIn(0, 255)
                val b = int("$prefix.b", base.blue).coerceIn(0, 255)
                val lightness = int("$prefix.lightness", 100).coerceIn(0, 100) / 100f
                return Color(
                    (r * lightness).toInt().coerceIn(0, 255),
                    (g * lightness).toInt().coerceIn(0, 255),
                    (b * lightness).toInt().coerceIn(0, 255),
                )
            }

            // 三个歌词色：**由 RGB + 明度滑杆合成**（2026-09-17 方案 A）。
            // 默认值与改动前那三个 hex 色**逐通道相同** → 开箱观感零变化。
            val unsung = mixerColor("main_lyric.fill.unsung", 0xFF7E818A.toInt())

            return Appearance(
                shapeType = text("shape.type", "SQUARE"),
                cornerRadiusPercent = int("shape.corner_radius", 60),
                // 2026-09-25 波次 2：材质三层。默认值与注册表**逐项一致**
                // （`0` / `NONE` / `30` / `90` / 止色 / `0`）—— 前两项即"关"。
                materialHighlightStrength = int("material.highlight.strength", 0),
                materialGradientStyle = text("material.fill.gradient_style", "NONE"),
                // ★ 2026-10-16：流光流速 %（默认 100 = 原速 ⇒ 存量配置逐帧不变）✓
                materialFlowSpeed = int("material.fill.flow_speed", 100),
                materialGradientTintPercent = int("material.fill.gradient_tint_percent", 30),
                materialGradientAngleDeg = int("material.fill.gradient_angle_deg", 90),
                // ⚠️ 与注册表默认同值（`0xFF2A2E38`）。它是**字面量**，理由同
                //    `background.color` 的 KDoc：`gen_preference_config.py` 认不出跨文件常量。
                materialGradientEndArgb = color("material.fill.color_end", 0xFF2A2E38.toInt()).rgb,
                materialNoiseAmplitude = int("material.noise.amplitude", 0),
                // ★★ 2026-10-01「液态玻璃」子组：高光强度 / 立体感
                materialLiquidGlarePercent = int("material.liquid.glare_strength", 60),
                materialLiquidDepthEffect = bool("material.liquid.depth_effect", true),
                // ★ 2026-10-01 第二批：两项都是 SEEKBAR ⇒ 走 `int(...)`
                //   （`bool(...)` 只用于 SWITCH —— 用错会拿到 `false`/默认值，静默不生效）。
                materialLiquidSurfaceAlpha = int("material.liquid.surface_alpha", 10),
                materialLiquidShadowStrength = int("material.liquid.shadow_strength", 60),
                materialQuality = text("material.quality", "FULL"),
                materialBlurRadius = int("material.content.blur_radius", 0),
                // ★★ 2026-09-28：L3（真透视）的身后模糊半径。**默认 0 = 关闭** ——
                //   与注册表默认同值 ⇒ 设置系统不可用时**一个分支都不多走**
                //   （与材质三层的"零视觉变化"同一约定）。
                //   ⚠️ 它是本插件**唯一**会启动采集线程 / 打开像素快照的键。
                materialBackdropBlurRadius = int("material.backdrop.blur_radius", 0),
                // ★ 2026-10-01 ④b 第二批：4 项都是 SEEKBAR ⇒ 走 `int(...)`
                //   （`bool(...)` 只用于 SWITCH —— 用错会静默拿到默认值）。
                materialBackdropRefractionHeight = int("material.backdrop.refraction_height", 16),
                materialBackdropRefractionAmount = int("material.backdrop.refraction_amount", 32),
                materialBackdropDispersion = int("material.backdrop.dispersion", 35),
                materialBackdropSaturation = int("material.backdrop.saturation", 150),
                // ★★ R7（2026-10-09）：GPU 后端开关。★ `bool(...)` 用于 SWITCH ✓
                //   回退值 = 注册表默认（`false`）⇒ 设置系统坏掉时**走 CPU**（最稳）✓
                materialRenderGpuBackend = bool("material.render.gpu_backend", false),
                // ★ 2026-09-28：每秒帧率日志（**测试用**，默认关 = 零开销）。
                //   回退值 = 注册表默认（`false`）⇒ 设置系统坏掉时不打日志。
                frameLogEnabled = bool("animation.frame_log", false),
                // ★ 2026-09-25 第 2 期波次 1：squircle 的两个设置项。
                //   回退值 = 注册表默认（`ARC` / `4`）⇒ **设置系统坏掉时画面逐像素不变**。
                cornerStyle = text("shape.corner_style", "ARC"),
                cornerN = int("shape.corner_n", 4),
                opacityPercent = int("background.opacity", 96),
                sungColor = mixerColor("main_lyric.fill.color", 0xFFFFFFFF.toInt()),
                unsungColor = unsung,
                fontFamily = text("main_lyric.font.family", "Microsoft YaHei UI"),
                // 字重：★ 2026-09-29 起**取值提成局部 `val fontBold`**（见 `from()` 开头），
                // 因为描边折算也要用同一个值 —— 两处各写一遍必然会漂移
                // （纪律 9：改名/改键必须穷尽，否则联动静默失效）。
                // 默认 `NORMAL`（注册表默认）⇒ 开箱零变化。
                fontBold = fontBold,
                // 副歌词比例：**100 = 标准**（= TRANSLATION_SCALE，出厂零变化）。
                // 上限 150% → 0.96，**副歌词不会超过主歌词**（见注册表该项的 KDoc）。
                translationScale = TRANSLATION_SCALE *
                    (int("layout.translation.scale_percent", 100) / 100f).coerceIn(0.5f, 1.5f),
                fontSize = int("main_lyric.font.size", 22),
                islandEnabled = bool("island.enabled", true),
                translationEnabled = bool("layout.translation.enabled", true),
                // 只有 `ABOVE` 才为真；未知值/缺键 → `false`（= 下方，改动前的观感）。
                mainOutlineEnabled = bool("main_lyric.outline.enabled", false),
                // ★ 描边宽度**联动字体粗细**（2026-09-21 用户要求新增）。
                //   公式：`实际宽 = 基准 × (1 + (字重倍率 - 1) × 联动比例)`
                //   中值 50% = 半跟随（见注册表该项 KDoc 里为什么这是"观感合适"的值）。
                //   ⚠️ 开关默认关闭 → 计算与旧行为**逐位相同**（开箱零变化）。
                mainOutlineWidthTenthPx = run {
                    val base = int("main_lyric.outline.width_tenth_px", 10)
                    if (!bool("main_lyric.outline.follow_font_weight", false)) {
                        base
                    } else {
                        // ★★★ 2026-09-29 修 B2（原读**已删除的键**，联动恒失效）
                        //
                        // ## 原记 / 实为
                        //
                        // **原记**：`val w = (int("main_lyric.font.weight_scale", 100) / 100f)…`
                        // **实为**：该键**已不存在**（`44-*` 把它改成 `main_lyric.font.weight`
                        //   —— SEEKBAR `50..200` → LIST `NORMAL`/`BOLD`）。
                        //   而 `int(key, fallback)` 的契约是「`descriptor(key)` 为 null ⇒ 返回 fallback」
                        //   （见本文件 `:6069`）⇒ 这里**恒得 `100` ⇒ `w` 恒 = `1.0f`**
                        //   ⇒ 联动项 `(w - 1f) * r` **恒为 0** ⇒ **开了开关也毫无效果**。
                        //
                        // ## 判据（★ 与 `KaraokeTextRenderer.karaokeFontWeightScale` **同源**）
                        //
                        // 那个函数（`render/KaraokeTextRenderer.kt` 里的
                        // `karaokeFontWeightScale`）是"当前字体有多粗"的
                        // **唯一出口**，其返回值语义为 `2f` = 加粗 / `1f` = 常规 ——
                        // 正是本公式原本期待的"字重倍率"。
                        // 而它内部就是 `if (font.isBold()) 2f else 1f`，与 `fontBold` 字段**同义**
                        //   （`fontBold` ← `main_lyric.font.weight == "BOLD"`，而 `fontWithLetterSpacing`
                        //   正是用这个布尔去 `deriveFont(Font.BOLD)`）。
                        // ⇒ 直接用 `fontBold`：**避免构造期去解析字体**，且与渲染层同源。
                        //   ☆ 纪律 10「首选修法 = 合并成唯一出口」：这里不另造判据，
                        //     而是复用已经存在的那一个语义（`isBold()` ⟺ `fontBold`）。
                        val w = if (fontBold) 2f else 1f
                        val r = (int("main_lyric.outline.weight_link_percent", 50) / 100f)
                            .coerceIn(0f, 1f)
                        (base * (1f + (w - 1f) * r)).roundToInt().coerceAtLeast(0)
                    }
                },
                mainOutlineColorR = int("main_lyric.outline.color.r", 255),
                mainOutlineColorG = int("main_lyric.outline.color.g", 255),
                mainOutlineColorB = int("main_lyric.outline.color.b", 255),
                // 默认 `true`（与注册表一致）：用户开了主歌词描边后，
                // 副歌词**默认跟随** —— 那是"描边"这种装饰性属性的合理默认
                // （两行歌词一边有描边一边没有，看起来像渲染出错）。
                translationOutlineSync = bool("translation.outline.sync_with_main", true),
                translationOutlineEnabled = bool("translation.outline.enabled", false),
                // 副歌词描边同样联动（与主歌词**同一套设置项与同一公式**）——
                // 两处若只改一处，会出现"主歌词描边跟着变粗、译文描边没变"的割裂观感。
                // ★ 2026-09-23 由 lead 补：这一行就是「声明了设置 ≠ 实现了功能」缺的那一跳 ✗。
                //    默认取 `false` —— 与 `TextOutline.followWeight` 的默认**逐字一致** ✓，
                //    也与本字段的默认一致 ✓ ⇒ **未动设置的用户观感零变化** ✓（本项目「默认值要稳」✓）。
                //    用户开启后，`KaraokeTextRenderer` 的换算 `f`（`:913`）才会被走到 ✓。
                translationOutlineFollowWeight = bool("translation.outline.follow_weight", false),
                translationOutlineWidthTenthPx = run {
                    val base = int("translation.outline.width_tenth_px", 10)
                    // ★★★ 2026-09-29 修 B4（副歌词开关**只加了字段、没接判定**）
                    //
                    // **声明**（`SettingRegistry.kt` 里 `translation.outline.follow_font_weight`
                    // 那一项的 KDoc，原文）：
                    //   「改前 …… 读的正是**主歌词那个开关** …… **本项把这一层解耦**：
                    //     副歌词有自己的跟随开关。」
                    // **实为**：新字段 `translationOutlineFollowWeight` 确实加了、也传给了
                    //   渲染器，但**这一处判定条件仍读主歌词开关** ⇒ 用户单开副歌词开关
                    //   **毫无效果**，反而单开主歌词开关会连带改变副歌词（与声明相反）。
                    // ⇒ 改为读**副歌词自己的**开关。（纪律 7「声明了设置 ≠ 实现了功能」）
                    if (!bool("translation.outline.follow_weight", false)) {
                        base
                    } else {
                        // ★★★ 同 B2：原读**已删除的** `main_lyric.font.weight_scale`
                        //   ⇒ 恒得 fallback `100` ⇒ `w` 恒 `1.0f` ⇒ 联动恒为 0。
                        //   判据与主歌词、与 `karaokeFontWeightScale` **同源**（见上方长注释）。
                        val w = if (fontBold) 2f else 1f
                        // ⚠️ 联动比例**仍复用主歌词那一项**（`SettingRegistry.kt` 里
                        //    `main_lyric.outline.weight_link_percent` 的取舍：
                        //    "用户这次只要一个开关"）—— 这不是漏改，是**刻意的**。
                        val r = (int("main_lyric.outline.weight_link_percent", 50) / 100f)
                            .coerceIn(0f, 1f)
                        (base * (1f + (w - 1f) * r)).roundToInt().coerceAtLeast(0)
                    }
                },
                translationOutlineColorR = int("translation.outline.color.r", 255),
                translationOutlineColorG = int("translation.outline.color.g", 255),
                translationOutlineColorB = int("translation.outline.color.b", 255),
                translationAbove = text("layout.translation.position", TRANSLATION_POSITION_BELOW)
                    .equals(TRANSLATION_POSITION_ABOVE, ignoreCase = true),
                // 未知值/缺键 → `PER_CHAR`（= 逐字，与改动前的默认观感一致）。
                karaokeMode = text("main_lyric.karaoke.mode", KARAOKE_MODE_PER_CHAR),
                // 译文颜色：**独立设置项**，默认 #B1B6C3。
                //
                // 为什么从「未唱色 × 0.88」改成独立项：真机对比原实现 0.8.9 后发现，
                // 原实现把译文色**写死**为 Color(177,182,195)，用户改歌词色时译文**不变**；
                // 而旧实现跟随未唱色，导致"改歌词色译文跟着变" —— 这是可见差异。
                // 本轮（用户真机反馈）要求对齐 0.8.9 的视觉：默认值取 #B1B6C3，
                // 但**保留可配置能力**（这是我们相对既有的产品优势）。
                translationColor = mixerColor("main_lyric.translation.color", 0xFFB1B6C3.toInt()),
                // 回退 `manual`：与其它三个 `*_color_mode` 同一取舍。
                translationColorMode = text(
                    "main_lyric.translation.color_mode", CoverPalette.MANUAL,
                ),
                lines = LineCount.parse(text("layout.lines", LineCount.SINGLE.wire)),
                // ⚠️ 2026-09-18：原先这里还读 `island.experimental_multi_line`（多行总闸）。
                //   该设置项已随 `layout.line_mode` 一起删除（见 [LineCount] 的 KDoc）——
                //   现在"哪几行"完全由 `layout.lines` 决定，**不存在第二层门控**。
                //   回退值取注册表默认值 `SINGLE`。
                lineGapPx = int("layout.line_gap_px", 0).toFloat().coerceAtLeast(0f),
                // `lyric.offset_ms`：注册表 range 是 -2000..2000，`int()` 已按
                // 注册表收敛，故这里不必再 clamp（再夹一次反而会掩盖注册表被改坏）。
                lyricOffsetMs = int("lyric.offset_ms", 0).toLong(),
                contentAlign = Align.parse(text("layout.content_align", Align.CENTER.wire)),
                noLyricsMode = NoLyricsMode.parse(
                    text("layout.noLyrics.mode", NoLyricsMode.HIDE_LINE.wire),
                ),
                showTrackTitle = bool("layout.noLyrics.showTrackTitle", true),
                // 默认 false（与上一项不同）：专辑名通常远长于曲名 ⇒ 默认开启会挤掉「曲名 · 作者」。
                showAlbum = bool("layout.noLyrics.showAlbum", false),
                // `layout.noLyrics.hideWhenInstrumental`（2026-09-19 新增）：
                // 无歌词时**整块文字都不画**。回退值取注册表默认 `false` ——
                // 设置系统坏掉时画面与改动前一致（照常显示占位/曲名）。
                hideWhenInstrumental = bool("layout.noLyrics.hideWhenInstrumental", false),
                titleInTranslation = bool("layout.noLyrics.titleInTranslation", false),
                // `cover.enabled`：左侧信息区的封面开关。**原为死设置**（见字段 KDoc）。
                // 回退值取注册表默认值 `false` —— 默认安装下左侧不出现封面，
                // 与原实现"默认显示频谱"的视觉差异已作为待确认项报给 team-lead。
                //
                // ★★ 2026-09-30（封面态 S5 · D2 死锁处置 ①）：本态**强制显示封面**。
                //   `cover.enabled = false` 时 `drawnCoverBounds` 恒 `null` ⇒
                //   **既画不出封面、又命中不到封面**（`hitCover` 依赖 `drawnCoverBounds`）
                //   ⇒ 用户选了封面态却看到一个空框、且怎么点都不展开。
                //   ⇒ 取或 `true`，**不写回键值**（见 `coverOnlyShape` 的 KDoc）。
                coverEnabled = bool("cover.enabled", false) || coverOnlyShape,
                // `cover.scale_with_font`（2026-09-18 新增）：封面是否随字号缩放。
                // 回退值取注册表默认 `true` —— 与"封面本就该随词岛整体缩放"的直觉一致。
                coverScaleWithFont = bool("cover.scale_with_font", true),
                // ★ 2026-10-13（BUG-188）：回退值取注册表默认 25（实测“更清晰且无可见代价”的那一档）✓
                coverSharpness = int("cover.sharpness", 25),
                // 2026-09-21：圆形封面 + 转动（用户附黑胶截图）。
                // 默认值刻意保持"开箱零变化"：`ROUNDED` + 不转 ——
                // 老用户升级后观感逐像素不变，新能力只在主动打开时出现。
                coverShape = text("cover.shape", "ROUNDED"),
                coverRotate = bool("cover.rotate", false),
                coverRotatePeriodS = int("cover.rotate_period_s", 20).toFloat(),
                // `cover.vinyl`（2026-09-21）：黑胶样式（外围黑环）。
                // 回退值取注册表默认 `false` —— 开箱零变化，与上面三项同一取舍。
                coverVinyl = bool("cover.vinyl", false),
                // ★★ 2026-09-30（封面态 S5）：`cover.lock` 的**消费点**。
                //   回退值 `false`（注册表默认）⇒ 未锁 ⇒ 命中即展开（最符合直觉）。
                //   ⚠️ 本项**只在本态生效** —— 非本态下即使 `true` 也无任何消费者
                //      （判据出现在绘制与命中两处，都以本态为前提）。
                lockCover = bool("cover.lock", false),
                // 边框六项（2026-09-18 新增；宽度与取色模式为当日二次修订）。
                // 回退值一律取注册表默认。
                borderEnabled = bool("island.border.enabled", false),
                // ⚠️ 键名带 `_tenth_`：单位是 0.1 像素。用整数单位是为了躲开
                // "SPW 滑杆写 Float → `coerceInt` 截断 → 边框消失"（见字段 KDoc）。
                borderWidthTenthPx = int("island.border.width_tenth_px", 10),
                // 回退 `SOLID`（注册表默认）⇒ 开箱零变化。
                borderMode = text("island.border.mode", "SOLID"),
                // ★ 回退 `50`（注册表默认）⇒ 与 BUG-185 已交付的观感**逐像素一致** ✓
                borderDissolveBandPercent =
                    int("island.border.dissolve_band_percent", 50),
                borderColorR = int("island.border.color.r", 255),
                borderColorG = int("island.border.color.g", 255),
                borderColorB = int("island.border.color.b", 255),
                // 回退 `manual`：与其它两个 `*_color_mode` 同取舍 ——
                // "设置系统坏了"时画面与改动前一致，不会莫名跟着封面变色。
                borderColorMode = text("island.border.color_mode", CoverPalette.MANUAL),
                // ⚠️ 2026-09-20 修：回退值原为 `false`，与注册表 `default = true` **不一致** ——
                // 读不到该键时词岛不画频谱，而示例预览（`PreviewSnapshot` 用 `true`）画频谱，
                // 两边对不上。统一为 `true`（注册表是真相源）。
                spectrumEnabled = bool("spectrum.enabled", true),
                // 三个 `*_color_mode`：**原为死设置**（见各字段 KDoc）。
                // 回退值一律取 `manual`（注册表默认值）—— 于是"设置系统坏了"
                // 时画面与改动前完全一致，不会莫名跟着封面变色。
                lyricColorMode = text("main_lyric.color_mode", CoverPalette.MANUAL),
                backgroundColorMode = text("background.color_mode", CoverPalette.MANUAL),
                // `background.color`（2026-09-22 新增）：手动档的底色。
                // 回退值取 `BACKGROUND_BASE_RGB`（= 旧硬编码常量逐位相同），
                // 故"设置系统坏了"时画面与改动前**逐像素一致**。
                backgroundManualColor = color("background.color", BACKGROUND_BASE_RGB),
                // `background.cover.angle_deg`（2026-09-22 新增）：封面图背景的旋转角度。
                // 回退值取注册表默认 `0`（不旋转）→ 开箱零变化。
                // `int()` 已按注册表 range（0..360）收敛，故这里不再夹一次。
                backgroundCoverAngleDeg = int("background.cover.angle_deg", 0),
                spectrumColorMode = text("spectrum.color_mode", CoverPalette.MANUAL),
                // `spectrum.color`：手动档的频谱色。回退值取注册表默认 `#84D8BC` ——
                // 与旧硬编码常量逐位相同，故"设置系统坏了"时画面不变。
                spectrumColor = color("spectrum.color", DEFAULT_SPECTRUM_COLOR),
                // ★★★ 2026-10-15（**§3.75**）：插槽频谱的**独立取色**两个键。
                //   回退值 `follow` ⇒ "设置系统坏了"时插槽**跟随主频谱**，
                //   与改动前（插槽恒用主频谱的色）**逐像素一致** ✓
                //   手动色回退 `DEFAULT_SPECTRUM_COLOR`（与 `spectrum.color` 同值）⇒
                //   「切到 manual 但没改过颜色」时插槽与主频谱**同色**，不会突然变一个色 ✓
                rightSlotSpectrumColorMode = text(
                    "island.right_slot.spectrum_color_mode",
                    RIGHT_SLOT_SPECTRUM_MODE_FOLLOW,
                ),
                rightSlotSpectrumColor = color(
                    "island.right_slot.spectrum_color",
                    DEFAULT_SPECTRUM_COLOR,
                ),
                // ★★★ 2026-10-16（**§3.77-F**）：极坐标**径向范围**三键。
                //   三个回退值都取**出厂几何**（0 / 55 / 100）⇒ "设置系统坏了"时
                //   与改动前的几何**逐位相同** ✓
                //   ⚠️ 回退值引用 `render/SpectrumGeometry.kt` 的常量，**不写数字** ——
                //     写数字就会与 `SpectrumPolarSpec.RADIAL_INNER_RATIO` 各存一份
                //     ⇒ 重演"两份实现各自演化"✗（由 `SpectrumGeometryProbe` 逐位断言）
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
                // `spectrum.anchor`：`BOTTOM`（默认，与改动前一致）/ `TOP`。
                // 未知值回退 `BOTTOM` —— 与注册表 LIST 项的收敛语义一致。
                spectrumAnchor = text("spectrum.anchor", SPECTRUM_ANCHOR_BOTTOM),
                // `spectrum.height_percent`：占歌词区高度的百分比。`int()` 已按注册表
                // 的 range（10..100）收敛，故这里不再夹一次（再夹会掩盖注册表被改坏）。
                spectrumHeightPct = int("spectrum.height_percent", 100),
                // 默认 `AUTO`（与注册表一致）—— 未知值/缺键都落到自动档。
                spectrumBarPitchAuto = text("spectrum.bar_pitch_mode", SPECTRUM_PITCH_AUTO)
                    .equals(SPECTRUM_PITCH_AUTO, ignoreCase = true),
                spectrumBarPitchPx = int("spectrum.bar_pitch_tenth_px", 70),
                spectrumSecondaryMode = text(
                    "spectrum.secondary.mode",
                    // ⚠️ 常量 2026-09-22 移到 `IslandPolicy.kt`（公开顶层）——
                    //    副频谱锚点抽成纯函数 [secondarySpectrumBands] 后，
                    //    探针需要**按真值**枚举四个档位（而不是抄一份字面量）。
                    SPECTRUM_SECONDARY_MODE_OFF,
                ),
                spectrumSecondaryOpacityPct = int("spectrum.secondary.opacity_percent", 35),
                // `island.hit.cover`（2026-09-19 新增）：封面是否进命中域。
                // 回退值取注册表默认 `false` —— "设置系统坏了"时封面那一块仍是
                // 穿透空洞（= 改动前的行为），不会莫名把下层窗口的点击吃掉。
                //
                // ★★ 2026-09-30（封面态 S5 · D1 死锁处置 ①）：本态**强制视为开启**。
                //   该键默认 `false` ⇒ 用户关着它时 `hitCover` 恒 `false`
                //   ⇒ 封面态**永远展开不了**（而本态的全部交互都挂在"命中封面"上）。
                //   ⇒ 取或 `true`，**不写回键值**（见 `coverOnlyShape` 的 KDoc）。
                hitCoverEnabled = bool("island.hit.cover", false) || coverOnlyShape,
                // `main_lyric.letter_spacing_tenth_px`（2026-09-19 新增）：单位 0.1 px。
                // 回退值取注册表默认 `0`（不加字距）→ 开箱观感零变化。
                letterSpacingTenthPx = int("main_lyric.letter_spacing_tenth_px", 0),
                // `translation.letter_spacing.follow_main`（2026-09-19 新增）。
                // 回退值取注册表默认 `true`（副歌词跟随主歌词按比例缩放）——
                // 与 `translation.outline.sync_with_main` 同一条"装饰性属性默认跟随"的取舍。
                translationLetterSpacingFollowMain =
                    bool("translation.letter_spacing.follow_main", true),
                // ★★ 2026-09-26 接线 `AnimationStyleResolver.effective()` ——
                //   把「总开关 `animation.style`」与「该组件的子项」合成出**实际生效**的风格。
                //
                // ★ 规则**只写在 `AnimationStyleResolver.effective()` 一处**（纯函数），
                //   两个组件各调一次。若让两处各自去判，这条规则就有**两份实现**，
                //   迟早漏改一处（本项目已因"同一规则写两遍"栽过多次）。
                //
                // ★ `factoryDefault` = 该动效的**出厂风格**（`MotionSpec.style.name`）——
                //   两条都是 `SLIDE`。故**默认配置下行为与拆散前逐帧一致**：
                //   总开关默认 `PER_COMPONENT`（不干预）→ 子项默认 `FOLLOW`（不指定）
                //   → 落回出厂 `SLIDE`。⚠️ 这正是"**开箱零变化**"的判据。
                //
                // ★ `MotionStyle.parse` 对未知值回退默认 ⇒ 设置文件被手改坏也不会让动效消失。
                controlsMotionStyle = MotionStyle.parse(
                    AnimationStyleResolver.effective(
                        componentValue = text(
                            "controls.animation_style",
                            AnimationStyleResolver.FOLLOW_TOTAL,
                        ),
                        totalValue = text(
                            "animation.style",
                            AnimationStyleResolver.TOTAL_PER_COMPONENT,
                        ),
                        factoryDefault = Motions.CONTROLS_APPEAR.style.name,
                    ),
                ),
                lyricMotionStyle = MotionStyle.parse(
                    AnimationStyleResolver.effective(
                        componentValue = text(
                            "lyric.animation_style",
                            AnimationStyleResolver.FOLLOW_TOTAL,
                        ),
                        totalValue = text(
                            "animation.style",
                            AnimationStyleResolver.TOTAL_PER_COMPONENT,
                        ),
                        factoryDefault = Motions.LYRIC_EXIT.style.name,
                    ),
                ),
            )
        }
    }
}

