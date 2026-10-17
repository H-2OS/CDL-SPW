// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.TexturePaint
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.Random
import dev.cdl.render.gpu.GlOffscreen
import dev.cdl.render.gpu.LiquidGlassShader

/**
 * **材质四层**（第 2 期 · 波次 2 + 2026-10-01 液态玻璃第二批）：
 * 顶部高光 → 背景渐变 → **表面蒙版** → 噪点。
 *
 * ★ 另有一层**不在** [paint] 里、且**必须画在岛体之前**的 [drawDropShadow]（投影）——
 *   它不是"岛体上的膜"，而是"岛体在桌面上的影子"。
 *
 * 施工规格：`spw-materials-survey/F-材质外观落地施工规格-2026-09-22.md` §6（施工单 D/E/F）。
 *
 * ## ★ 为什么三层都走 `setPaint` + `fill(shape)`，**不引入 `clip`**
 *
 * `F §6.1` 的硬约束是「材质层必须在 `fill(shape)` 之后、在 `g2.clip = shape` 之前
 * **（或自己裁到 shape）**」—— 否则矩形渐变会**涂满整个窗口**（词岛是圆角/胶囊，四角会露出方块）。
 *
 * 但"自己裁到 shape"**不能用 `setClip`**：它是**硬边**（`Region`，像素中心判定）、
 * **忽略抗锯齿**，会在圆角处留下一圈 1px 锯齿 —— 而它下面是**抗锯齿画出来**的背景，
 * 两层边缘对不齐。本项目已吃过这个亏（BUG-117/121 的同一族）。
 *
 * ⇒ 本文件的做法是：**把 paint 换成渐变/纹理，然后 `fill(shape)`**。
 *   填充路径与背景那条**完全同一个 `shape`** ⇒ 边缘由同一套 AA 规则产生，
 *   **不可能对不齐**，而且**一次 `fill` 就自带"裁到 shape"**（不需要 clip）。
 *
 * ## 顺序（`F §6.2`）
 *
 * 高光 → 渐变 → **表面蒙版** → 噪点。**一次做完，不要几进几出**（`F §8.2` 第 3 步）。
 * （表面蒙版在 2026-10-01 插入，位置理由见 [paint] 里那一行的注释。）
 *
 * ## 与 `cover_image` 档互斥
 *
 * 调用方**只在纯色路径**（`manual` / `cover`）调本函数。`cover_image` 档已经是一张
 * "离屏烘焙后一次画上"的完整背景（`IslandWindow.drawCoverImageBackdrop`），
 * 再叠加三层会让观感不可预测 —— 那条互斥判断在**调用点**（`IslandWindow`），
 * 不是在这里（本文件看不到 `color_mode`）。
 *
 * ## 热路径零分配
 *
 * - 噪点 tile / `TexturePaint` / extra-alpha 合成：**进程内各建一次**（`by lazy`）。
 * - 渐变笔刷：**1 槽缓存**（键 = 岛体矩形 + 角度 + 两端色）。实测构造只占
 *   "构造+fill" 的 **0.09%**（`B §A-4.1`），缓存主要是省掉每帧 2384 B 的分配压力。
 * - 每帧只新建**一个** `LinearGradientPaint`（缓存未命中时）—— 不做逐帧 `new Color()`。
 *
 * ## ⚠️ 已知限制（如实记录，**不得声称"已自适应"**）
 *
 * 1. **白色高光在浅色底上不可见甚至反向**（`B §A-3` R3，证据等级【推断】）。
 *    本版**不做**亮度自适应 —— 用户把背景调成浅色时高光会"看不见"，
 *    这是**已知取舍**，不是 bug。
 *
 *    ★★ **2026-09-29 用户拍板 D4 =「留空间」** —— 即：**现在不做，但代码里留出扩展位**，
 *    将来实现它时**不必重构**。扩展位就是下面这个占位函数 [highlightAlphaFor]：
 *    现在它**恒返回** [HIGHLIGHT_MAX_ALPHA]（= 现行为，逐像素不变）；
 *    将来把"背景亮度采样"接进去即可 —— **调用点无需改动**。
 *    ⚠️ **刻意不写成"TODO"注释**：注释里的计划会腐烂（本项目已多次记录），
 *    而**一个真实的函数**是**可被替换的实现点**，且能被探针断言"当前恒为常量"。
 * 2. **噪点的 128×128 tile 在 304×154 的岛上平铺 2.4×1.2 次**，
 *    **可能看出周期纹理**【未验证】（`B §A-2` R1）。缓解方案（256×256 tile，内存 256 KB）留作后续。
 * 3. **高 DPI 下 tile 被放大 ⇒ 颗粒变粗**（`B §A-2` R3）。本版不处理。
 * 4. 上述 2 / 3 两条**必须出现在真机验收清单里**，否则"未验证"会变成"已验证"。
 */
object MaterialLayers {

    /** 噪点幅度上限（与注册表 `material.noise.amplitude` 的值域上界一致）。 */
    const val MAX_NOISE_AMPLITUDE = 20

    /** 噪点 tile 边长（px）。见"已知限制"第 2 条。 */
    const val NOISE_TILE_SIZE = 128

    /** 高光满强度时的顶部 alpha（`GlassLab` 的实测值，`F §6.2`）。 */
    private const val HIGHLIGHT_MAX_ALPHA = 46

    /** 高光的纵向跨度占岛高比例（`F §6.2`）。 */
    private const val HIGHLIGHT_SPAN_RATIO = 0.55f

    // ── 液态玻璃第二批（2026-10-01）：表面蒙版 + 投影 ─────────────────────────
    //
    // ★ 全部常量都是 `_diag_spw/SurfaceShadowProbe.java` 的**实测值**，
    //   不是拍脑袋 —— 见各自 KDoc 的「实测」小节。

    /**
     * 投影位图四周的留白（px，**全分辨率口径**）。
     *
     * ★ **实测来源**：bench 的 `pad = 24`。
     * 层数 18、每层外扩 `1.6 px` ⇒ 最外层 `grow = 28.8`，但 alpha 已衰减到 0
     * （`(1 - 18/18)^1.6 = 0`）⇒ **有效**外扩 ≈ 24 px，故留白取 24 刚好包住。
     */
    const val SHADOW_PAD_PX = 24

    /** 投影位图相对岛体的**水平**偏移（px，右为正）。★ bench 值。 */
    const val SHADOW_OFFSET_X_PX = 2

    /** 投影位图相对岛体的**纵向**偏移**绝对值**（px）。★ bench 值。
     *  ⚠️ 符号由 [shadowOffsetY] 决定（见那里的 KDoc：窗口只有一侧有预留带）。 */
    const val SHADOW_OFFSET_Y_PX = 6

    /**
     * 投影位图的构建**降采样倍数**（★ 实测选定的 2）。
     *
     * ## 为什么需要降采样（不是提前优化，是**实测逼出来的**）
     *
     * bench 实测：**全分辨率构建 = 8.3 ms**（≈ 半个 60fps 帧预算）。
     * 而**悬停展开动画期间岛体形状每帧都在变** ⇒ 1 槽缓存必然每帧失效
     * ⇒ 每帧 8.3 ms ⇒ 动画掉到 ~30fps。本项目为"展开闪动"打过一轮，不能再引入同族缺陷。
     *
     * ## 为什么是 2（实测对照，`SurfaceShadowProbe` 的 B3 段）
     *
     * | 降采样 | 下方亮度 vs 全分辨率 | 构建成本 |
     * |---|---|---|
     * | **1/2** | **−0.2% / +0.2% / +0.9%**（强度 0.3 / 0.6 / 0.9） | **2.3 ms（3.5× 快）** |
     * | 1/4 | −10.2% / −19.4% / −26.5% ❌ **明显偏暗** | 0.8 ms |
     *
     * ⇒ 1/4 之所以偏暗：`grow` 缩到 1.6/4 = 0.4 px 后**落在同一像素网格上**，
     *   19 层被压进少数几个像素 ⇒ alpha 叠加密度变了（**换了一种模糊，不是同一种**）。
     *   1/2 时 `grow = 0.8 px` 仍有区分度 ⇒ 观感一致。
     *
     * ⚠️ **这是"同一套算法、不同采样密度"**，不是"换一种模糊"——
     *   放大时的双线性插值补回丢掉的高频。故 bench 的暗度曲线仍然有效。
     */
    const val SHADOW_SHRINK = 2

    /**
     * ★★ **逐像素玻璃的「动画期降采样」倍率**（2026-10-02 新增 · **T1 降级链**）。
     *
     * ## 为什么需要它（问题）
     *
     * `liquidGlassImage` 的成本是 **13.6 ms/次**（304×154 ⇒ 47k 像素，每像素算 SDF 梯度）
     * —— 那是 **60fps 预算（16.67 ms）的 81.5%**（`migration/66` §3 实测）。
     *
     * ★ 但它**有缓存**（形状与参数不变 ⇒ 只算一次）⇒ **稳态成本 ≈ 0**。
     * ★★ **只有形状变化时**（悬停展开/收起那 180 ms）才**每帧**付 13.6 ms
     * ⇒ 那 180 ms 内**必然掉帧**。
     *
     * ## 为什么是「降采样」而不是「换成另一种实现」
     *
     * `migration/66` §6.2 原话是「形状变化时 ⇒ **可降级到丙（6.3 ms）或乙（8.3 ms）**」。
     * ★ 但那两条路**都会让玻璃"变样"**（乙 = 径向渐变光晕 · 丙 = 22 层菲涅耳近似）
     * ⇒ 动画期间观感会**跳变**（用户会看到"玻璃闪了一下"）。
     *
     * ⇒ ★★ 改用**同款算法的降采样**（与 [SHADOW_SHRINK] **同一个思路**）：
     *   像素数降到 `1/shrink²` ⇒ 成本 **13.6 → ≈3.4 ms**（`shrink = 2`），
     *   而**算法一字未改** ⇒ 放大后**观感几乎不变**。
     *
     * ★ **实测（探针 ⑬-23）**：降采样版放大回全分辨率后，与全分辨率版的
     *   **平均通道差 = 0.148 / 255**（≈ **0.06%**）—— ★ 比投影侧的 ±0.9% 还小。
     *
     * ## 取值依据
     *
     * `shrink = 2` ⇒ 成本 1/4。★ **不取 4**：投影侧已实测"1/4 会**换一种模糊**"
     * （`SHADOW_SHRINK` 的 KDoc：`grow` 缩到 0.4 px 后落在同一像素网格）。
     * 玻璃侧同理 —— `shrink` 越大，**边缘光带的采样密度越低**，
     * 而玻璃的观感**全在边缘那几像素**上（`fresnelRange = glareRange = 64`）。
     */
    const val GLASS_ANIM_SHRINK = 2

    /**
     * ★★★ **稳态**（非动画中）的降采样倍率（2026-10-06 · **R2**）。
     *
     * ## ★★★ 为什么【不】稳态降采样（2026-10-06 · **R2 被探针否决**，值仍为 1）
     *
     * ★ 我一度按"**平均通道差 0.148/255（0.06%）**"（探针 ⑬-23）判定"稳态也可降到 2" ⇒
     * ★★ **被探针当场否决**：`MaterialProbe` ⑫-5 / ⑫-6 / ⑫-5b / ⑫-7 **四条全红**
     *   （「LIQUID **四边都有**边缘光（每侧亮度 > 20）」等）✓
     *
     * ★★★ **根因**：★ **「平均偏差 0.06%」是【整体平均】，而玻璃的观感【集中在边缘那几像素】** ——
     *   降采样**主要伤边缘**，而**平均值把它稀释掉了** ✗
     *   ⇒ ★ 原文其实**早就警告**：「`shrink` 越大，**边缘光带的采样密度越低**，
     *     而玻璃的观感**全在边缘那几像素上**（`fresnelRange = glareRange = 64`）」✓
     *
     * ★★ **⇒ 决策**：**稳态保持 1** —— ★ 因为 **R1（移出 EDT）已经解决了"卡顿"**，
     *   ★ **不必再用边缘观感去换性能** ✓
     *
     * ★ **判据（可复用）**：★★ **"平均值"不能代表"观感"** ——
     *   当一个效果的**能量集中在少数像素**上时（如玻璃的边缘光、文字的描边），
     *   ★ **必须看【局部指标】，不能用全局平均** ✓
     */
    const val GLASS_STEADY_SHRINK = 1

    /**
     * ★★ `paint(...)` 的 `glassAnimating` 参数的**默认值**（**T1 降级链**）。
     *
     * ★ **必须是 `false`** —— 语义是"**漏传 ⇒ 退化为不降级**"（= 改动前行为，只是可能掉帧），
     * 而**不是**"一直降采样"（那会让**静态观感**也变差，且探针的逐像素断言会全红）。
     *
     * ★ 抽成常量的理由（**与 BUG-148 的教训同族**）：让"默认值"这件事**可被探针读到** ——
     * 参数默认值本身在 Kotlin 里**不可反射**，写死一个常量并让参数引用它，
     * 探针就能断言"它确实是 `false`"（`MaterialProbe` **⑬-25**）。
     */
    const val DEFAULT_GLASS_ANIMATING = false

    /** 投影的层数（★ bench 值：18 层递减 alpha 逼近高斯）。 */
    private const val SHADOW_LAYERS = 18

    /** 每层的外扩步长（px，**全分辨率口径**）。★ bench 值。 */
    private const val SHADOW_GROW_STEP = 1.6

    /** 最内层（`i = 0`）的 alpha 基数。★ bench 值：`strength × 150`。 */
    private const val SHADOW_MAX_ALPHA = 150.0

    // ── 噪点：一次烘焙 + 预建 paint / 合成 ───────────────────────────────────

    /**
     * 噪点 tile —— **只烘焙一份**，幅度固定为最大值。
     *
     * ★ 这是对 `B §A-2` 一处**会带来卡顿**的写法的修正：`B` 的示例把"强度"编码进
     *   tile 的 alpha 并按强度烘焙 ⇒ 用户拖滑杆时**每变一档都要重建 128×128 tile**
     *   （21 档 × 16384 次 `setRGB`），而那是**在 EDT 上**发生的。
     *   ⇒ 本实现**只烘焙一份**，强度由 [AlphaComposite] 的 **extra alpha** 统一调制
     *   ⇒ **一次烘焙、零重建、拖滑杆无卡顿**。
     *
     * 种子固定（`99`）⇒ **同一份 tile 每次运行都一样**（否则每帧颗粒都在跳）。
     */
    private val noiseTile: BufferedImage by lazy {
        val img = BufferedImage(NOISE_TILE_SIZE, NOISE_TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
        val rnd = Random(99L)
        for (y in 0 until NOISE_TILE_SIZE) {
            for (x in 0 until NOISE_TILE_SIZE) {
                // 灰度噪点，**alpha 恒为 255**（强度不编码在这里，见上面的说明）。
                val v = rnd.nextInt(256)
                img.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
        }
        img
    }

    private val noisePaint: TexturePaint by lazy {
        TexturePaint(noiseTile, Rectangle(0, 0, NOISE_TILE_SIZE, NOISE_TILE_SIZE))
    }

    /**
     * `0..20` → 预建的 extra-alpha 合成。
     *
     * 系数取 `amplitude * 2 / 255`：满幅度 20 ⇒ alpha ≈ 0.157 —— 与 `B §A-2`
     * "满幅度下颗粒可见但不糊"的实测观感一致。
     */
    private val noiseComposites: Array<AlphaComposite> = Array(MAX_NOISE_AMPLITUDE + 1) { a ->
        AlphaComposite.getInstance(AlphaComposite.SRC_OVER, if (a <= 0) 0f else a * 2f / 255f)
    }

    // ── 渐变：1 槽缓存 ──────────────────────────────────────────────────────

    private var cachedGradient: LinearGradientPaint? = null
    private var cachedKey: String = ""
    /** ★★ 逐像素液态玻璃的 1 槽缓存（`migration/66-*`：13.6 ms/帧 ⇒ **必须**缓存）。 */
    private var cachedLiquid: BufferedImage? = null
    private var cachedLiquidKey: String = ""

    /**
     * ★★ 缓存里的那张位图**是不是降采样版**（`shrink > 1`）—— **T1 降级链**用。
     *
     * ★ 只作**记录**（供探针与诊断读）：缓存命中与否**不依赖它** ——
     * 因为 `shrink` 已进 [liquidGlassImage] 的 `key`（见那里的 KDoc：
     * "动画中"与"稳态"是**两个不同的 key** ⇒ 各自缓存、自动切换）。
     */
    private var cachedLiquidShrunk: Boolean = false

    // ═══════════════════════════════════════════════════════════════════════════
    // ★★★ D-1（2026-10-09）：GPU 后端分派（`GlOffscreen` + `LiquidGlassShader`）
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // ## 出处（★ 复用既有成果，**不另写一份 GL**）
    //   · 渲染器 = `GlOffscreen.render`（`render/gpu/GlOffscreen.kt`）—— ★ 返回 `BufferedImage?`，
    //     与 CPU 路径 [liquidGlassImage] **同类型** ⇒ 分派只需在**一处**做 ✓
    //   · shader = `LiquidGlassShader`（参考实现的逐字移植版）的
    //     `fragmentShaderSource()` / `fragmentShaderFallback()` / `uniforms(...)` ✓
    //   · 线程 = `GlOffscreen` **自带单线程执行器**（`spw-gl` · `MIN_PRIORITY`）⇒
    //     ★ 可在 [glassPool] 的线程上安全调用（GL 上下文**不**跨线程）✓
    //
    // ## 为什么要"失败记忆"（一次性降级）
    //   ★ 若 GPU 因**环境**不可用（无 GL / 驱动不支持 / 上下文丢失），它会**每帧**失败一次
    //     ⇒ ★ 每帧白付一次"建上下文 / 编译 / 读回"的固定代价 ⇒ 必须**连续失败 N 次后永久关闭** ✓
    //   ★ 与"CPU 路径逐位不变"的关系：默认 `gpuEnabled = false` ⇒ 本分支**永不进入** ⇒
    //     118 条 `MaterialProbe` 断言**逐位不变** ✓

    /**
     * ★★★ **D-1：GPU 后端开关**（= 设置键 `material.render.gpu_backend`）—— **由上层注入**。
     *
     * ## ★ 为什么不在这里直读设置（分层限制）
     *
     * ★ [MaterialLayers] 是 `object`、**看不到设置层** —— 设置值由 `IslandAppearance`
     *   读出（`bool("material.render.gpu_backend", false)`）、经 `IslandWindow` 传下来
     *   （与 `BackdropCaptureParams.preferGpu` **同一条路**）⇒ ★ 本文件**不 import 设置层**，
     *   改由**注入** ✓
     *
     * ## ★★ 注入点（谁写它）
     *
     * ★ **`IslandWindow.applyAppearance`** —— 它已经从 `IslandAppearance.materialRenderGpuBackend`
     *   拿到该键的值，在应用外观时写一行
     *   `MaterialLayers.gpuEnabled = panel.appearance.materialRenderGpuBackend` ✓
     * ★ **`IslandPreviewPane.paintComponent`** —— 预览侧的同一行（它读的是
     *   `PreviewSnapshot.materialRenderGpuBackend`）⇒ ★ 两侧**都推**，否则会出现
     *   「**同一个开关，两个界面，其中一个对它无感**」（= BUG-149 的形态）✗
     *
     * ★ **2026-10-10 更正（原记"尚未接线"）**：该行**已接线**（真机 + 预览两处），
     *   原 KDoc 写于"只允许改本文件"的受限批次 ⇒ ★ **当时为真，现在已过期**。
     *   取证：`grep -rn "MaterialLayers.gpuEnabled" plugin/src/main/kotlin/` ⇒ 2 个写入点 ✓
     *   ★ 故"当前恒为 `false`"一句**也不再成立** —— 它现在**跟随设置键** ✓
     *
     * ## ★ 默认 `false`（"默认值要稳"）
     * ★ 设置键 `material.render.gpu_backend` 的注册表默认 = `false` ⇒ 未主动开启时
     *   生产路径**逐位不变**（GPU 分支不进入）✓
     * ★ GPU 与 CPU 的输出**不是逐像素相同**（分阶段 vs 单趟）
     *   ⇒ 新后端**不该**在用户没主动开的情况下改变观感 ✓
     */
    @Volatile
    var gpuEnabled: Boolean = false

    /** 连续失败达此数 ⇒ **永久关闭** GPU 分支（见 [gpuDisabled]）。★ 取值 3：一次偶发不算数。 */
    private const val GPU_FAILURE_LIMIT = 3

    /**
     * GPU 连续失败计数。
     *
     * ★ 只在**工作线程**上读写（[liquidGlassImage] 跑在 [glassPool] 的单线程上；探针直接调
     *   也是单线程）⇒ ★ **不需要原子**（与 [cachedLiquid] 同一条并发口径）✓
     */
    private var gpuFailStreak = 0

    /**
     * ★★ **永久关闭标志**（**一次性记忆**）—— 达 [GPU_FAILURE_LIMIT] 后恒 `true`。
     *
     * ★ 目的：环境性失败（无 GL / 驱动不支持 / 上下文丢失）**不会自愈** ⇒ 关掉之后
     *   ★ **不再每帧白试一次**（否则每帧都付一次失败的固定代价）✓
     * ★ `@Volatile`：让诊断/探针能在别的线程读到它（本层只在工作线程写）✓
     */
    @Volatile
    private var gpuDisabled = false

    /**
     * ★★ **`GlOffscreen` 单例**（懒建）—— D-1 的 GPU 离屏渲染器。
     *
     * ★ 它**内部自带一个 GL 线程**（`spw-gl` · daemon · `MIN_PRIORITY`）⇒
     *   [liquidGlassImageOnGpu] 在 [glassPool] 的线程上调用它**是安全的**（见 `GlOffscreen` 的 KDoc）✓
     * ★ 懒建 ⇒ **默认（GPU 关闭）时连对象都不创建**，零开销 ✓
     */
    private val glOffscreen: GlOffscreen by lazy { GlOffscreen() }

    /** ★ D-1：传给 shader 的设备像素比。★ 取 `1.0` —— 本层只有设备像素口径（见 [liquidGlassImageOnGpu]）。 */
    private const val GPU_DPR = 1.0

    // ===========================================================================
    // 2026-10-06  R1：把逐像素玻璃移出 EDT（用户顾虑：「操作卡顿」）
    // ===========================================================================
    //
    // ## 为什么（取证）
    //
    //  GlassSdf 的 KDoc 表原文：MaterialLayers.liquidGlassImage **跑在 EDT**
    //  成本 **13.6 ms/次** = **81.5% 的 16.7 ms 帧预算**  它一慢 UI 就卡
    //  而同族的 BackdropRefraction.bake（16.6 ms）**早就被挪到采集线程了**
    //    （`BackdropCapture.L3CaptureWorker` 原文：「放在 EDT  直接吃掉一整个 60fps 帧预算；
    //     放在这里  EDT 侧一行都不改」）
    //  本改动 = 把【已有做法】用到漏掉的地方（不是新架构）
    //
    // ## 怎么做的（照 L3CaptureWorker 的模式）
    //
    // | 项 | 做法 |
    // |---|---|
    // | 线程 | **独立 Daemon 线程**（单线程池）——  不得自起 Timer（帧循环唯一） |
    // | 交接 | **@Volatile 引用替换**（照 L3，**不加锁**） |
    // | 快照 | **GlassSnap 把 key 与 img 绑成一个不可变对象**  一次读拿到**一致组合** |
    // | 首帧 | 未算完  返回 null  调用方已有回退（纯色渐变） |
    // | 尺寸变化 | 上一张尺寸不匹配  返回 null（不拉伸旧图） |
    // | 去重 | glassBusy（AtomicBoolean）  同时只跑一趟，不堆队列 |

    /** **不可变快照**：key 与位图**绑在一起**  读侧一次拿到一致组合 */
    private class GlassSnap(val key: String, val img: BufferedImage, val w: Int, val h: Int)

    /** 待算的请求（工作线程需要全部参数） */
    private class GlassReq(
        val key: String, val w: Int, val h: Int,
        val cornerRadius: Double, val roundness: Double, val tintPercent: Int,
        val baseColor: java.awt.Color, val endArgb: Int, val glareAngleDeg: Double,
        val glarePercent: Int, val depthEffect: Boolean, val cornerRadiusBottom: Double,
        val animating: Boolean,
        /** ★★ 2026-10-17（对齐 A1）：表面不透明度 `0..100`。 */
        val surfaceOpacityPercent: Int = 100,
    )

    @Volatile
    private var glassSnap: GlassSnap? = null

    /** **同时只跑一趟**（避免队列堆积；新的请求覆盖旧的 —— 只有最新那帧有意义） */
    private val glassBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var glassReq: GlassReq? = null

    /**
     * **单线程 Daemon 池**（照 L3CaptureWorker 的 isDaemon = true）
     *
     * ## 为什么把优先级降到最低（2026-10-09，由 `FramePacingProbe` ⑦ 抓到）
     *
     * **实测对照实验**（同一台机器、同一时刻）：
     *
     * | 调用点 | `FramePacingProbe` ⑦ |
     * |---|---|
     * | 同步版（原） | **19 通过 / 0 失败** |
     * | 异步版（R1） | **18 通过 / 1 失败** |
     *
     * 失败内容：`Timer(16ms)` 的实得间隔 **31 ms**（= 掉一帧）—— 模型预测 16.03 ms。
     *
     * **根因**：R1 把玻璃计算移到后台线程 ⇒ 它一次 **13.6 ms** 的满核计算
     * **与 EDT 的 `Timer` 抢 CPU** ⇒ 帧节奏被推迟。
     *
     * **⇒ 这正是用户担心的事**（「CPU占用一高会影响使用体验」）——
     * 本改动把「**阻塞 EDT**」换成了「**后台线程抢 CPU**」：
     * **改善是真的**（不再阻塞 UI），**但没到"零影响"**。
     *
     * **⇒ 处置**：把工作线程降到 **`Thread.MIN_PRIORITY`** ——
     * 让 EDT 的 `Timer` **始终优先**；玻璃晚一两帧出来**看不出来**（它本来就有缓存与回退）。
     */
    private val glassPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "spw-glass-worker").apply {
                isDaemon = true
                // 关键：不与 EDT 的帧节奏抢 CPU（见上面的实测）
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /**
     * ★★ **逐像素玻璃「按行分片」的线程数上限**（B-1，2026-10-09）。
     *
     * ## 为什么是 **4**（★ 不是拍脑袋）
     *
     * ★ **探针实测：分片的多线程加速上限 = 4.16×** ⇒ ★ 线程数超过 ~4 后**收益递减**
     *   —— 逐像素计算很快撞上**内存带宽**瓶颈，而不是 CPU 核数。
     * ★ **另一条更硬的约束**：★ 本机**还有 EDT 的帧节奏与采集线程在跑** ⇒
     *   分片线程越多，**留给 EDT 的 CPU 越少** ⇒ ★ `FramePacingProbe` ⑦ 会红
     *   （★ R1 已实测过一次：后台满核计算把 `Timer(16ms)` 拖到 **31 ms**，见 [glassPool]）✓
     * ⇒ ★ **封顶 4**：吃到探针实测的加速上限，同时**给 EDT 留出余量** ✓
     */
    private const val GLASS_SHARD_MAX = 4

    /**
     * ★★ **分片并行池**（B-1，2026-10-09）—— 把 [liquidGlassImage] 的**逐像素行循环**
     * 按行切成 N 片、并行算。
     *
     * ## 为什么可并行（★ 探针实测的加速上限）
     *
     * ★ 探针实测：**分片的多线程加速上限 = 4.16×** ⇒ 这个逐像素算法**确实可并行** ✓
     * ★ 可行性的**结构性依据**：内层循环**只写 `px[by * bw + bx]`**（每行写**自己那一段**内存），
     *   **无跨行依赖** ⇒ ★ **按行分片 = 各片写不相交的内存** ⇒ **结果与单线程【逐位相同】** ✓
     *   （★ 这是本改动的**核心不变式**，任何"看起来更快"的改法都**不得破坏**它。）
     *
     * ## ★★ 为什么**必须是另一个池**（★ 死锁红线）
     *
     * ★ [liquidGlassImage] 本身**已经**跑在 [glassPool] 的**单线程**上（R1 移出 EDT）。
     * ⚠️ 若在 [glassPool] 的线程里把分片任务**提交给同一个 [glassPool]** 再 `get()` 等待
     *   ⇒ ★ **新任务排在当前任务之后、而当前任务又在等它** ⇒ **死锁** ✗
     * ⇒ ★ 本池**与 [glassPool] 是两个独立对象**（各自线程）⇒ 提交 + 等待**不会**自锁 ✓
     *
     * ## 为什么线程数**封顶 [GLASS_SHARD_MAX]**（= 4）
     *
     * ★ 上限来自**探针实测的 4.16×**（收益递减）+ ★ **给 EDT 留余量**（见常量 KDoc）✓
     *
     * ## ★★ 为什么优先级也是 `MIN_PRIORITY`
     *
     * ⚠️ **本项目刚踩过的坑**：R1 把玻璃计算移到后台后，`FramePacingProbe` ⑦ 变红 ——
     *   `Timer(16ms)` 实得 **31 ms**，**根因 = 后台满核计算与 EDT 的帧节奏抢 CPU**。
     * ⇒ ★ 本池的每个线程**一律 `Thread.MIN_PRIORITY`**（与 [glassPool] **同口径**）
     *   ⇒ 分片**永远让位于 EDT** ✓ ★ **不要重犯**。
     *
     * ## 大小 = [GLASS_SHARD_MAX] - 1
     *
     * ★ 分片总数 = [glassShardCount]（≤ [GLASS_SHARD_MAX]），其中★ **当前线程自己也干一片**
     *   （见 [liquidGlassImage] 的分片调度）⇒ 池里**只需 `总数 - 1` 个线程** ✓
     *   ★ 这样**少起一个线程、少一次调度**（"当前线程也参与"更省）。
     */
    private val glassShardPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(GLASS_SHARD_MAX - 1) { r ->
            Thread(r, "spw-glass-shard").apply {
                isDaemon = true
                // ★ 与 glassPool 同口径：绝不与 EDT 的帧节奏抢 CPU（见上面的 FramePacingProbe ⑦）
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /**
     * ★★ **分片线程数**：`min(可用核数, [GLASS_SHARD_MAX])`，**至少 1**。
     *
     * ★ **惰性求值一次**（`by lazy`）：核数在进程生命周期内**不会变**，
     *   而 `availableProcessors()` 每次调用都要问一次运行时 ⇒ 缓存掉 ✓
     * ★ 结果为 `1` ⇒ [liquidGlassImage] **走单线程**（= 改动前行为，**逐位相同**）✓
     */
    private val glassShardCount: Int by lazy {
        Runtime.getRuntime().availableProcessors().coerceIn(1, GLASS_SHARD_MAX)
    }

    /**
     * **异步取玻璃位图**（生产路径；探针仍用同步的 [liquidGlassImage]）
     *
     * ## 契约
     *
     * 1. **命中**（key 相同） **立即返回**该位图
     * 2. **未命中**  **提交后台**，并**返回「尺寸匹配的上一张」**；无匹配  null
     * 3. **本函数【永不阻塞】** —— 它只做 @Volatile 读 + 一次 CAS + 一次 submit
     *
     * 返回 null 是正常的（首帧 / 尺寸变化那一帧）—— **调用方已有回退**：
     * if (img != null) drawImage else { 纯色渐变 }
     */
    fun liquidGlassImageAsync(
        w: Int, h: Int, cornerRadius: Double, roundness: Double,
        tintPercent: Int, baseColor: java.awt.Color, endArgb: Int, glareAngleDeg: Double,
        glarePercent: Int = 60, depthEffect: Boolean = true,
        animating: Boolean = false, cornerRadiusBottom: Double = Double.NaN,
        /** ★★ 2026-10-17（对齐 A1）：表面不透明度 `0..100`（见 [liquidGlassImage]）。 */
        surfaceOpacityPercent: Int = 100,
    ): BufferedImage? {
        val crBottom = if (cornerRadiusBottom.isNaN()) cornerRadius else cornerRadiusBottom
        val shrinkForKey = if (animating) GLASS_ANIM_SHRINK else 1
        val key = "" + w + "|" + h + "|" + cornerRadius + "|" + crBottom + "|" + roundness +
            "|" + tintPercent + "|" + baseColor.rgb + "|" + endArgb +
            "|" + glareAngleDeg + "|" + glarePercent + "|" + depthEffect + "|s" + shrinkForKey +
            "|a" + surfaceOpacityPercent
        val snap = glassSnap
        // 1 命中  立即返回
        if (snap != null && snap.key == key) return snap.img
        // 2 未命中  提交后台（新请求覆盖旧的：只有最新那帧有意义）
        glassReq = GlassReq(
            key, w, h, cornerRadius, roundness, tintPercent, baseColor, endArgb,
            glareAngleDeg, glarePercent, depthEffect, crBottom, animating, surfaceOpacityPercent,
        )
        if (glassBusy.compareAndSet(false, true)) {
            glassPool.submit { glassLoop() }
        }
        // 3 返回「尺寸匹配的上一张」（尺寸不符  null，不拉伸旧图）
        return if (snap != null && snap.w == w && snap.h == h) snap.img else null
    }

    /** 工作线程主体：**循环取最新请求**直到没有 */
    private fun glassLoop() {
        try {
            while (true) {
                val req = glassReq ?: break
                glassReq = null
                try {
                    // 用**命名参数**：签名里 shrinkOverride 在 animating 与 cornerRadiusBottom 之间
                    val img = liquidGlassImage(
                        w = req.w, h = req.h, cornerRadius = req.cornerRadius,
                        roundness = req.roundness, tintPercent = req.tintPercent,
                        baseColor = req.baseColor, endArgb = req.endArgb,
                        glareAngleDeg = req.glareAngleDeg, glarePercent = req.glarePercent,
                        depthEffect = req.depthEffect, animating = req.animating,
                        cornerRadiusBottom = req.cornerRadiusBottom,
                        // ★★ 2026-10-17（对齐 A1）
                        surfaceOpacityPercent = req.surfaceOpacityPercent,
                    )
                    if (img != null) {
                        // **原子换引用**（照 L3 的 acceptCapture）
                        glassSnap = GlassSnap(req.key, img, req.w, req.h)
                    }
                } catch (t: Throwable) {
                    // 故障域隔离：算失败  保留上一张（调用方照常回退）
                }
            }
        } finally {
            glassBusy.set(false)
            // 竞态收口：若在 set(false) 前又来了新请求  补一趟
            if (glassReq != null && glassBusy.compareAndSet(false, true)) {
                glassPool.submit { glassLoop() }
            }
        }
    }

    /**
     * ★★ 投影位图的 1 槽缓存（键 = 尺寸 + 圆角 + **强度**）。
     *
     * ⚠️ **键里必须有强度** —— 投影**不是**"一层固定位图 + 额外 alpha"：
     *   多层 `SRC_OVER` 叠加是**非线性**的（`a₁⊕a₂ = a₁ + a₂(1−a₁)`），
     *   故"按强度各建一份"与"建满强度再统一压 alpha"**结果不同**。
     *   ★ 实测差别：两层各 0.5、强度 0.5 时，前者 0.4375、后者 0.375。
     *   ⇒ 为保证「设置值 0.60 的观感 == bench 实测的 0.60」，**必须按强度建**。
     *   （噪点那层能共用位图，是因为它的强度只乘**单层** alpha，见 [noiseComposites]。）
     */
    private var cachedShadow: BufferedImage? = null
    private var cachedShadowKey: String = ""

    // ── 是否需要画（调用方与探针共用同一判据）────────────────────────────────

    /** 高光：`0` = 关。 */
    fun shouldDrawHighlight(strength: Int): Boolean = strength > 0

    /**
     * ★★ **D4 的扩展位**（用户 2026-09-29 拍板「留空间」）。
     *
     * 高光在浅色底上会"看不见甚至反向"（见文件头「已知限制」第 1 条）。
     * 本版**不做**亮度自适应，但把"高光 alpha"这一步**抽成一个函数**，
     * 将来要接"背景亮度采样"时**只改这里**，调用点一行不动。
     *
     * ## 现在的行为（= 改动前，逐像素不变）
     *
     * **恒返回** [HIGHLIGHT_MAX_ALPHA] —— 与 [highlightPaint] 原来的内联常量**完全等价**。
     *
     * ## 将来怎么扩（写在这里，免得下次重新想）
     *
     * 1. 调用方需提供**背景亮度**（当前 `paint(...)` 收不到它 ⇒ 需加参数）；
     * 2. 亮度高时**降低** alpha（浅底上白高光本就不可见），或换成**暗色高光**；
     * 3. ★ 无论怎么改，**必须保留"默认配置下逐像素不变"**这条判据
     *    （本项目「默认值要稳」纪律）—— 故新逻辑只应在**非默认**输入下生效。
     *
     * @param backgroundLuminance 背景亮度（0..1）。★ **当前实现忽略它** —— 这是刻意的，
     *   它的存在就是为了**固定住将来的接口形状**。
     *
     * ★★★ **2026-10-10（审计 v4 · C-2）· 硬规矩：不得为它写断言** ✗
     *   ★ 它**忽略入参、恒返 [HIGHLIGHT_MAX_ALPHA]** ⇒ **任何断言它都必然绿**
     *     ⇒ 一旦有人为它写断言，**当场变成空护栏**（纪律 71 / 77）✗
     *   ★ 判据：**判据函数必须"取值真的会变"** —— 见同文件的
     *     [shouldDrawHighlight]（`0 ⇒ false` / `1 ⇒ true`，**会变**）✓
     *   ★ 同族的活例（已删）：`BackdropRefraction.shouldSaturate` 恒真 ⇒ 曾被写 3 条空断言 ✓
     *   ⇒ ★ **本函数是"接口形状先行"的延期实现**，它的护栏应当加在**将来真正接线时**
     *     （那时它不再忽略入参，断言才有判别力）✓
     */
    @Suppress("UNUSED_PARAMETER")
    fun highlightAlphaFor(backgroundLuminance: Float = 0.5f): Int = HIGHLIGHT_MAX_ALPHA

    /** 渐变：`style` 必须是 `LINEAR`，且强度 `> 0`（`0` ⇒ 两色相同 ⇒ 不画）。 */
    fun shouldDrawGradient(style: String, tintPercent: Int): Boolean =
        (style == "LINEAR" || style == "LIQUID") && tintPercent > 0

    /** 噪点：`0` = 关；超出上界按上界处理（注册表已夹过，这里是纵深防御）。 */
    fun shouldDrawNoise(amplitude: Int): Boolean = amplitude > 0

    // ── 液态玻璃第二批：表面蒙版 ─────────────────────────────────────────────

    /**
     * 表面蒙版（`material.liquid.surface_alpha`）：`0` = 关。
     *
     * ★ 与 [shouldDrawNoise] 同形（`> 0` 才画）—— 与探针共用同一判据，避免两处口径漂移。
     */
    fun shouldDrawSurfaceAlpha(percent: Int): Boolean = percent > 0

    /**
     * 表面蒙版的**不透明度**（`0f..1f`）。
     *
     * ★ 设置项是 `0..100` 的百分比，**除以 100** 才是 `AlphaComposite` 要的值 ——
     *   这个换算只在这里做一次（调用方与探针都读它，不各写一遍）。
     */
    fun surfaceAlphaFraction(percent: Int): Float = percent.coerceIn(0, 100) / 100f

    // ── 液态玻璃第二批：投影 ─────────────────────────────────────────────────

    /** 投影强度（`material.liquid.shadow_strength`）：`0` = 关。 */
    fun shouldDrawDropShadow(percent: Int): Boolean = percent > 0

    /**
     * 投影强度的**分数**（`0f..1f`）—— 即 bench 里的 `strength`。
     *
     * ★ 换算只在这里做一次（理由同 [surfaceAlphaFraction]）。
     */
    fun shadowStrengthFraction(percent: Int): Float = percent.coerceIn(0, 100) / 100f

    /**
     * 投影的**纵向偏移**（px，正 = 朝下）。★ 由「窗口预留带在哪一侧」决定。
     *
     * ## 为什么方向要跟着预留带走（**这是本项唯一的硬约束，不这么做就是个"不诚实的设置项"**）
     *
     * 词岛窗口**比岛体大**，但只在**一侧**多出预留带（[bandGeometry] 的 `stripAtTop`）：
     *
     * | `controls.position` | 岛体在窗口里的位置 | 下方余量 | 上方余量 |
     * |---|---|---|---|
     * | `BOTTOM`（**默认**） | 贴窗口**上**边 | **= 带高（≈76 px）** | 0 |
     * | `TOP` | 贴窗口**下**边 | 0 | = 带高 |
     *
     * ⇒ 若方向**固定朝下**：`TOP` 档时岛体下边缘**就是窗口下边缘**，
     *   投影 **100% 被裁掉** ⇒ 用户拖「投影强度」**什么都不会发生**。
     *   ★ 这正是用户点名要根治的那类缺陷（「滑杆调节**不诚实**的设置项」）。
     *
     * ⇒ **朝预留带那一侧投**：`BOTTOM` ⇒ 朝下（自然，与面板意图一致）；
     *   `TOP` ⇒ 朝上（★ 如实记录：这不是"面板的本意方向"，但它是**唯一看得见**的方向）。
     *
     * ⚠️ 横向**恒为 +[SHADOW_OFFSET_X_PX]** —— 左右两侧的余量是对称的
     *   （`grow / 2`，默认 ≈ 38 px，够放 26 px），**不存在**"被裁"问题，故不跟着翻。
     */
    fun shadowOffsetY(stripAtTop: Boolean): Int = if (stripAtTop) -SHADOW_OFFSET_Y_PX else SHADOW_OFFSET_Y_PX

    /**
     * 把三层一次画完（顺序：高光 → 渐变 → 噪点）。
     *
     * ⚠️ **调用方必须保证**：① 已画完纯色背景；② `shape` 与背景用的**是同一个对象**；
     *    ③ 当前处于 `background.color_mode != cover_image` 的路径。
     *
     * @param shape 岛体形状（**窗口坐标**，与 `g2.fill(shape)` 用的是同一个）
     * @param baseColor 起色（= 背景当前实际用的那个颜色，含 alpha）
     * @param gradientEndArgb 止色（`material.fill.color_end`，打包 ARGB）
     */
    fun paint(
        g: Graphics2D,
        shape: Shape,
        highlightStrength: Int,
        gradientStyle: String,
        gradientTintPercent: Int,
        gradientAngleDeg: Int,
        gradientEndArgb: Int,
        baseColor: Color,
        noiseAmplitude: Int,
        quality: String,
        /**
         * ★★ 2026-10-01（逐像素液态玻璃需要）：形状的**圆角半径**（px）。
         *
         * ## 取值约定（⚠️ **2026-10-01 更正，原来把 `0` 也当成"没给"**）
         *
         * | 值 | 含义 |
         * |---|---|
         * | `< 0`（默认） | **没给** ⇒ 从 `shape.bounds2D` 短边推断 `min(w,h)*0.5*0.4` |
         * | `0` | ★ **直角**（**合法值**，`shape.type = SQUARE` 就是它） |
         * | `> 0` | 该圆角半径 |
         *
         * ★★ **原记**「`<= 0` ⇒ 推断」——**实为**：那把 `SQUARE`（半径真的是 `0`）
         * 也推进了"推断"分支 ⇒ 逐像素玻璃**在方形岛体上仍然画成圆角矩形**，
         * 与岛体形状**对不上**（记为 **BUG-148**）。
         * ⇒ 判据改成 `>= 0`，并**由调用方传真实半径**（`islandOutlineAt` 给出的那条边）。
         */
        shapeCornerRadius: Double = -1.0,
        /**
         * ★★ **下边圆角半径**（T5，2026-10-02）—— `NaN`（默认）⇒ **与上边同值**（= 改动前行为）。
         *
         * ★ 只有 `shape.type = NOTCH`（**上大下小**）才需要它：
         * 不传 ⇒ 逐像素玻璃只能取 `maxOf(上, 下)` ⇒ **玻璃边缘与岛体轮廓对不上**（近似）。
         *
         * ⚠️ 语义与 [shapeCornerRadius] **完全一致**（`>= 0` 有效、`< 0` 走兜底）——
         * 见那条 KDoc（BUG-148 的 `> 0` 陷阱）。
         */
        shapeCornerRadiusBottom: Double = Double.NaN,
        /**
         * ★★ 2026-10-03（⑰「封面多色流光」第 2 步 · **F1 接线**）：
         * `material.fill.gradient_style == "FLOW"` 时用的**多色原料**
         * （`CoverColorExtractor.palette()` 的返回，`0xFFRRGGBB` 列表）。
         *
         * ## 默认值 = 空 ⇒ **既有三档逐像素不变**
         *
         * 本参数**只在 `FLOW` 档被读**。而 `FLOW` 档是**新增**的
         * （`material.fill.gradient_style` 的选项表 +1）⇒ 存量配置里没有这个值
         * ⇒ **开箱零变化**（与 `LIQUID` 当年同一条纪律）。
         *
         * ## ★ 为什么"色数 < 2 ⇒ 退回 `LINEAR`"而不是"不画"
         *
         * `palette()` 的契约**允许返回短列表**（封面单色 / 取色失败 / 纯黑白封面）。
         * ⇒ 若此时"不画"，用户看到的是**选了 `FLOW` 却什么都没有**（比"渐变不对"更糟，
         *   因为它看起来像"设了没反应" —— 本项目最多次的缺陷形态）。
         * ⇒ 退回 `LINEAR`（用户已经设过的角度 + 起止色）**至少画出一个渐变**。
         * ★ 且这条与 `FlowGradient.flowGradientPaint` 的"三种退化 ⇒ `null`"配套：
         *   **它返回 `null` 正是让调用方走这条回退**。
         */
        flowPaletteArgb: List<Int> = emptyList(),
        /**
         * ★★ 2026-10-03（**F2**）：流光的**相位**（`0f..1f` = 一个完整周期）。
         *
         * ★ 必须由 `render/PhaseAdvance.loopPhase(播放位置, FLOW_PERIOD_MS)` 给出 ——
         * **不是墙钟** ⇒ **暂停即冻结**（与水位波动、封面转动同一条纪律；
         * `loopPhase` 只吃 `positionMs`，**调用方无法用错时钟**）。
         * ★ `0f` = 静止（默认）⇒ 不传时与 F1 的静态观感**逐像素一致**。
         */
        flowPhase: Float = 0f,
        /**
         * ★★ 超椭圆指数（`2` = 标准圆角；越大角越方，业界 `2..8`）。
         * ★ 只对 `gradientStyle == "LIQUID"`（逐像素）生效。
         *
         * ## ⚠️ 2026-10-01 更正：默认值 `2.0` 是**残留**
         *
         * `migration/68-*` §2 已把本参数定为 **5**（出处：`LiquidGlassWinUI` 的
         * `ShapeRoundness` 默认 5，范围 2–8），**但那个值从来没被接线** ——
         * 本参数默认 `2.0` 且**调用点不传** ⇒ ★ 生产恒为 `2.0`，
         * **与折射侧（`GlassSdf.DEFAULT_ROUNDNESS = 5`）不一致**
         * ⇒ 两条 SDF 的角**不一样**（"玻璃边缘"与"折射带"会错开）。
         * ⇒ 已改为引用 [GlassSdf.DEFAULT_ROUNDNESS]，且调用点显式传它。
         *
         * ★ 与 `shapeCornerRadius`（BUG-148）**同一族**：**文档写了值、代码没接**。
         * ★ 注意：默认形态是 `SQUARE`（`cornerRadius = 0`）⇒ 超椭圆指数**不参与运算**
         *   ⇒ 本修正在默认配置下**零可见变化**（只在 `PILL` / `ROUNDED_RECT` / `NOTCH` 下生效）。
         */
        shapeRoundness: Double = GlassSdf.DEFAULT_ROUNDNESS,
        /**
         * ★★ 液态玻璃的**高光强度**（`0..100`，对应 `material.liquid.glare_strength`）。
         * 内部 `glareFactor = 本值 / 100`。★ 默认 **60** = 面板那一档（开箱观感）。
         */
        liquidGlarePercent: Int = 60,
        /**
         * ★★ 液态玻璃的**立体感**（`material.liquid.depth_effect`）。
         * `false` ⇒ **关掉菲涅耳边缘光**（只剩高光，更扁平）。★ 默认 `true` = 现有行为。
         */
        liquidDepthEffect: Boolean = true,
        /**
         * ★★ **表面蒙版**（`material.liquid.surface_alpha`，`0..100`）。
         * `0` ⇒ **不画**（★ 默认 `0` ⇒ 现有调用点逐像素不变）。
         *
         * 面板里的 `alpha = 0.10`（截图值）⇒ 本项取 **10**。
         * 实现在 [drawSurfaceAlpha]（铺一层极淡白，让"玻璃面"更**实**）。
         */
        liquidSurfaceAlphaPercent: Int = 0,
        /**
         * ★★ **形状是否正在动画**（`hover.fraction ∈ (0f, 1f)`）—— **T1 降级链的开关**。
         *
         * `true` ⇒ 逐像素玻璃**降采样**（13.6 → ≈3.4 ms，见 [GLASS_ANIM_SHRINK]）；
         * `false`（默认）⇒ 全分辨率。
         *
         * ★ **默认 `false` 是有意的**：漏传 ⇒ **退化为"不降级"**（= 改动前行为，只是可能掉帧），
         * 而**不是**"一直降采样"（那会让静态观感也变差）。★ 这是"默认值要选安全侧"的应用。
         */
        glassAnimating: Boolean = DEFAULT_GLASS_ANIMATING,
        /**
         * ★★★ **表面不透明度**（`0..100`，来自 `background.opacity`）—— 2026-10-17 对齐 A1。
         * 玻璃位图的 alpha = **覆盖率 × 本值/100 × 0.5** ⇒ 永远保留 ≥50% 身后内容。
         * 默认 `100` = 取上限 0.5（**不是**"全不透明"）。
         */
        surfaceOpacityPercent: Int = 100,
    ) {
        // ★ 2026-09-25 第 3 期 L2：画质档 `OFF` ⇒ **什么都不画**。
        //   `B §F.5`：「不加档位直接上材质，低配机器会直接卡死」——
        //   `OFF` 就是那个"别上材质"的档。放在**函数第一行**而不是各层里，
        //   是为了让它**结构上**不可能漏掉某层（三层共用一个出口）。
        if (quality == "OFF") return
        val b = shape.bounds2D
        if (!(b.width > 0.0) || !(b.height > 0.0)) return
        val oldPaint = g.paint
        val oldComposite = g.composite
        try {
            if (shouldDrawHighlight(highlightStrength)) {
                val hl = highlightPaint(b.x, b.y, b.height, highlightStrength)
                if (hl != null) {
                    g.paint = hl
                    g.fill(shape)
                }
            }
            // ★★ 2026-10-03（⑰ F1 接线）：`FLOW` = **封面多色**渐变（N 色 ⇒ N 停靠点）。
            //
            // ⚠️ **单独一条分支，不改 [shouldDrawGradient]** —— 那个判据被 `MaterialProbe` 钉着，
            //    且它的语义是"起止色混合比 > 0"；而 FLOW **不用** `gradient_tint_percent`
            //    （它的"强度"由**色数**决定）⇒ 把 FLOW 塞进那个判据会让两者语义互相污染。
            //
            // ★★★ 2026-10-14（**用户报障纠正** · ⑱ 的真正落点）：本分支同时接 **`CAMO`**
            //    （「封面迷彩碎色流光」）。用户逐字：「**碎色流光是提供给词岛外观的**」
            //    ⇒ 我先前把它做在 `spectrum.color_mode`（`cover_camo`）上是**对象错了**；
            //    本键 `material.fill.gradient_style` 才是"岛体背景渐变"这个同形态设置项。
            //    ★ **本分支是预览与真机的共同绘制路径**（★ 2026-10-16 §3.77-E 起
            //      预览传的也是**示例封面的多色** `demoCoverPalette()` ——
            //      此前它传一组硬编码的"代表色"，与真机不同源 ⇒ 已收敛）
            //      ⇒ 在此接线 = 两边同时生效，**不会**重演"预览同源缺口"。
            if (gradientStyle == "FLOW" || gradientStyle == "CAMO") {
                val brush = if (gradientStyle == "CAMO") {
                    // ★★★ 2026-10-15：迷彩档 = **二维细胞噪声贴图**
                    //   （唯一出口 `CamoTexture.camoTexturePaint`）。
                    //
                    // ★ **原记**：用 `LinearGradientPaint` 的"不等宽碎色块平台"
                    //   （旧 `flowCamoPaint`）。**实为**：线性渐变是**一维**的 ——
                    //   颜色只沿一个轴变化、垂直于轴的方向上恒定 ⇒ 在"横向长条岛体 +
                    //   角度 0°"的默认配置下**必然**是**竖条纹** ✗
                    //   （用户报障逐字：「**完全没做到应有效果，还是竖条纹状**」）
                    //   ⇒ ★ **调参救不了，换数据结构**：贴图是二维的 ✓
                    //
                    // ⚠️ `gradientAngleDeg` **不参与**迷彩档：二维图案没有"渐变方向"
                    //   这个自由度（图案自身就是各向异性的）。那个设置项仍服务
                    //   `LINEAR` / `FLOW` 两档，故保留。
                    camoTexturePaint(
                        b.x, b.y, b.width, b.height,
                        islandHeightPx = b.height.toInt(),
                        paletteArgb = flowPaletteArgb,
                        alpha = baseColor.alpha,
                        // ★ 相位（由播放位置推进 ⇒ 暂停即冻结）—— 与 FLOW 同一套语义。
                        phase = flowPhase,
                    )
                } else {
                    flowGradientPaint(
                        b.x, b.y, b.width, b.height,
                        gradientAngleDeg,
                        flowPaletteArgb,
                        baseColor.alpha,
                        // ★ F2：相位（由播放位置推进 ⇒ 暂停即冻结）。
                        phase = flowPhase,
                    )
                } ?: if (shouldDrawGradient("LINEAR", gradientTintPercent)) {
                    // ★ 退化（色数 < 2 / 跨度退化 / 浮点塌陷）⇒ 回退既有 `LINEAR` 路径。
                    //   理由见 `flowPaletteArgb` 的 KDoc：宁可画一个渐变，
                    //   也不要"选了却没东西画"（= 本项目最多次的"设了没反应"形态）。
                    gradientPaint(
                        b.x, b.y, b.width, b.height,
                        gradientAngleDeg, gradientTintPercent, baseColor, gradientEndArgb,
                    )
                } else {
                    null
                }
                if (brush != null) {
                    g.paint = brush
                    g.fill(shape)
                }
            }
            if (shouldDrawGradient(gradientStyle, gradientTintPercent)) {
                // ★★ 2026-10-01（用户拍板 A）：按**档**选实现 ——
                //    `LINEAR` = 沿角度的线性渐变（未变）；★ `LIQUID` = **逐像素液态玻璃**
                //    （SDF → 法线 → 菲涅耳 + 双向 Glare，**照抄业界**，见 `recon/69-*`）。
                //    ⚠️ 两条路都**不做 `setClip`**（本文件文件头的硬约束）——
                //      ★ 逐像素版**在像素级自己判内外**（`m > 0.5` 跳过）⇒ 天然贴合任意形状 ✓
                if (gradientStyle == "LIQUID") {
                    val wPx = Math.ceil(b.width).toInt()
                    val hPx = Math.ceil(b.height).toInt()
                    // ⚠️ `>= 0`（不是 `> 0`）：`0` = **直角**，是合法值 —— 见 `shapeCornerRadius` 的 KDoc。
                    val cr = if (shapeCornerRadius >= 0) shapeCornerRadius
                             else minOf(b.width, b.height) * 0.5 * 0.4
                    // ★★ T5：下边半径（`NaN` ⇒ 与上边同值 ⇒ **改动前行为**；
                    //   `< 0` ⇒ 与上边**同一兜底式** —— 语义与 `shapeCornerRadius` 对齐）。
                    val crBottom = if (shapeCornerRadiusBottom.isNaN()) cr
                                   else if (shapeCornerRadiusBottom >= 0) shapeCornerRadiusBottom
                                   else minOf(b.width, b.height) * 0.5 * 0.4
                    // R1（2026-10-06）：改调**异步**版 —— 逐像素玻璃移出 EDT（详见 liquidGlassImageAsync 的 KDoc）
                    val img = liquidGlassImageAsync(
                        wPx, hPx, cr, shapeRoundness,
                        gradientTintPercent, baseColor, gradientEndArgb, gradientAngleDeg.toDouble(),
                        liquidGlarePercent, liquidDepthEffect,
                        // ★★ T1 降级链：动画期间降采样（形状每帧都变 ⇒ 缓存每帧失效）。
                        animating = glassAnimating,
                        // ★★ T5：双半径（`NOTCH` 档下玻璃边缘与岛体轮廓逐像素贴合）。
                        cornerRadiusBottom = crBottom,
                        // ★★★ 2026-10-17（对齐 A1）：表面 α ≤ 0.5 ⇒ 玻璃半透明
                        surfaceOpacityPercent = surfaceOpacityPercent,
                    )
                    if (img != null) {
                        // ★ 位图已按 SDF 裁好（含 AA）⇒ 直接叠上去（`SrcOver` 保留旧 composite 语义）
                        g.drawImage(img, b.x.toInt(), b.y.toInt(), null)
                    } else {
                        // ★ 兜底：位图不可用（尺寸非法）⇒ 回退旧径向实现（**不崩**）
                        g.paint = liquidPaint(b.x, b.y, b.width, b.height, gradientTintPercent, baseColor, gradientEndArgb)
                        g.fill(shape)
                    }
                } else {
                    g.paint = gradientPaint(
                        b.x, b.y, b.width, b.height,
                        gradientAngleDeg, gradientTintPercent, baseColor, gradientEndArgb,
                    )
                    g.fill(shape)
                }
            }
            // ★★ 2026-10-01（液态玻璃第二批）：**表面蒙版** —— 铺在玻璃之上、噪点之下。
            //
            // ## 为什么在这个位置（渐变之后、噪点之前）
            //
            // 语义上它是**玻璃体本身**的一部分（"这块玻璃有多实"），而噪点是**最外层**的
            // 磨砂颗粒 ⇒ 蒙版压在噪点下面，颗粒才不会被白纱冲淡。
            //
            // ⚠️ 与 [liquidGlarePercent] / [liquidDepthEffect] 不同：**它不属逐像素着色** ——
            //   它是 `Graphics2D` 的一层（与高光/渐变/噪点同级），故对 `LINEAR` 档**同样生效**。
            //   ★ 理由：面板把「表面蒙版」列为**材质**属性，而不是"液态玻璃专属"。
            if (shouldDrawSurfaceAlpha(liquidSurfaceAlphaPercent)) {
                drawSurfaceAlpha(g, shape, liquidSurfaceAlphaPercent)
            }
            if (shouldDrawNoise(noiseAmplitude)) {
                val a = noiseAmplitude.coerceIn(0, MAX_NOISE_AMPLITUDE)
                g.paint = noisePaint
                g.composite = noiseComposites[a]
                g.fill(shape)
            }
        } finally {
            g.paint = oldPaint
            g.composite = oldComposite
        }
    }

    // ── 表面蒙版（液态玻璃第二批）─────────────────────────────────────────────

    /**
     * ★★ **表面蒙版**（`material.liquid.surface_alpha`）—— 面板里的 `alpha = 0.10`。
     *
     * ## 它是什么（`_diag_spw/SurfaceShadowProbe.java` 实验 A 实测确认）
     *
     * 在玻璃的**边缘光之外**，再铺一层**覆盖整个形状内部**的**极淡白** ——
     * 让"玻璃面"看起来更**实**（像一块实体玻璃，而不是空心的发光边框）。
     *
     * ## 实测（用户要求"不可猜测臆断"⇒ 值来自 bench，不来自手感）
     *
     * 判据 = 形状**中心** 40×40 的平均亮度（`luma × alpha`）；底图是模拟的玻璃（暗底 + 边缘光）：
     *
     * | `alpha` | 中心亮度 | 相对无蒙版 |
     * |---|---|---|
     * | `0.00` | 34.7 | 基准 |
     * | **`0.10`（截图值）** | **57.3** | **+65.4%** |
     * | `0.20` | 78.7 | +126.9% |
     *
     * ⇒ ★ `0.10` **远未过曝**（bench 的过曝阈值取 200，实测 57.3）
     *   ⇒ 面板那个值**可以直接用**，不需要为了"别太亮"而打折。
     *
     * ## 性能
     *
     * bench：**0.826 ms/帧**（含底图拷贝；60fps 预算的 **5.0%**）。
     * ★ 它是**单次 `fill`**，无位图、无缓存 —— 与噪点同级的成本，**不需要缓存**。
     *
     * ## 为什么用 `AlphaComposite` 而不是 `Color(255,255,255,26)`
     *
     * 两者等价（`0.10 × 255 = 25.5 ≈ 26`），但 `AlphaComposite` 的**分数**是
     * bench 直接量过的量（`alpha`），而 26 是它的一次取整 ——
     * ★ 取整会让"设置 10"与"设置 11"在某些值上**落到同一个 alpha**（滑杆不诚实）。
     *   `AlphaComposite` 用 float，101 档**逐档可分**。
     *
     * ⚠️ **不做 `setClip`**：与 [paint] 的硬约束一致 —— 用 `fill(shape)`，
     *   边缘与背景那条**同一个 `shape`** ⇒ AA 规则相同，不可能对不齐（见文件头）。
     *
     * @param percent `0..100`（`0` 由 [shouldDrawSurfaceAlpha] 在调用点挡掉）
     */
    fun drawSurfaceAlpha(g: Graphics2D, shape: Shape, percent: Int) {
        val oldPaint = g.paint
        val oldComposite = g.composite
        try {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, surfaceAlphaFraction(percent))
            g.paint = Color(255, 255, 255)
            g.fill(shape)
        } finally {
            g.paint = oldPaint
            g.composite = oldComposite
        }
    }

    // ── 投影（液态玻璃第二批）─────────────────────────────────────────────────

    /**
     * ★★ **投影**（`material.liquid.shadow_strength`）—— 面板里的「投影强度 0.60」。
     *
     * ## 调用位置是**硬约束**：必须在**岛体之前**画
     *
     * 投影的语义是"面板**浮在**桌面上"⇒ 它必须在**岛体底下**。
     * 画在岛体之后会**盖住玻璃**（变成"面板上蒙了一层灰"）。
     * ★ 调用点在 `IslandWindow.paintComponent` 的 ③ 之前（与形状算完、背景画之前**同一处**）。
     *
     * ## 实测（`SurfaceShadowProbe` 实验 B）
     *
     * 判据 = 形状**下方 20 px** 的平均亮度（越低越暗）；桌面基准 = 209.2：
     *
     * | 强度 | 下方亮度 | 相对桌面 |
     * |---|---|---|
     * | `0.00` | 209.2 | 基准 |
     * | `0.30` | 109.5 | −47.7% |
     * | **`0.60`（截图值）** | **62.0** | **−70.3%** |
     * | `0.90` | 39.0 | −81.4% |
     *
     * ⇒ ★ 单调、且 `0.60` 处**明显可见但不死黑** ⇒ 面板值可直接用。
     *
     * ## 为什么是"预烘焙位图"（B1/B3）而不是"每帧现画"（B2）
     *
     * bench：B2 每帧现画 **7.7 ms**；B1 缓存后 **0.86 ms**（含桌面拷贝）
     * ⇒ ★ 每帧 `drawImage` 单独只有 **0.57 ms**。
     * ⇒ 选 B1/B3（位图），且位图**构建降采样 1/2**（理由见 [SHADOW_SHRINK]）。
     *
     * ## 方向（★ 见 [shadowOffsetY] 的 KDoc）
     *
     * 纵向符号**跟着窗口预留带走** —— 否则 `controls.position = TOP` 档下
     * 投影会被窗口下边缘**整条裁掉**（= 一个"不诚实的设置项"）。
     *
     * @param x/y 岛体左上角（**窗口坐标**）
     * @param w/h 岛体宽高
     * @param cornerRadius 岛体圆角半径（px）—— ★ 让阴影贴合形状的圆角
     * @param percent `0..100`（`0` 由 [shouldDrawDropShadow] 在调用点挡掉）
     * @param stripAtTop `controls.position == TOP` ⇒ 预留带在**上**方 ⇒ 投影朝上
     */
    fun drawDropShadow(
        g: Graphics2D,
        x: Double, y: Double, w: Double, h: Double,
        cornerRadius: Double,
        percent: Int,
        stripAtTop: Boolean,
    ) {
        val wPx = Math.ceil(w).toInt()
        val hPx = Math.ceil(h).toInt()
        if (wPx <= 0 || hPx <= 0) return
        val bmp = shadowBitmap(wPx, hPx, cornerRadius, percent) ?: return
        // ★ 位图是 **1/SHRINK 分辨率** ⇒ 目标尺寸取**全分辨率**那一份
        //   （`w + 2·pad`），源位图比它小 `SHRINK` 倍 ⇒ `drawImage` 自动放大 `SHRINK` 倍。
        //
        // ⚠️ **不要**在这里再乘一次 `SHADOW_SHRINK` —— 那会变成放大 `SHRINK²` = 4 倍。
        //   本探针的 ⑫-13b 正是这么抓到它的：影子被放大 2 倍 ⇒ 下方亮度从
        //   桌面的 ~30% 掉到 **5.2%**（几乎全黑），而 bench 实测是 29.6%。
        val fullW = wPx + SHADOW_PAD_PX * 2
        val fullH = hPx + SHADOW_PAD_PX * 2
        val dx = (x - SHADOW_PAD_PX + SHADOW_OFFSET_X_PX).toInt()
        val dy = (y - SHADOW_PAD_PX + shadowOffsetY(stripAtTop)).toInt()
        val oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(bmp, dx, dy, fullW, fullH, null)
        } finally {
            // ★ 还原 —— 别把插值提示泄漏给后面的绘制（歌词/封面的缩放都吃它）。
            if (oldInterp != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp)
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            }
        }
    }

    /**
     * 构建投影位图（**1/[SHADOW_SHRINK] 分辨率**，1 槽缓存）。
     *
     * ## 算法 = bench 的 B1，只把坐标除以 [SHADOW_SHRINK]
     *
     * 从**最外层**（大、淡）到**最内层**（小、浓）叠画 `SHADOW_LAYERS` 层，
     * 每层 alpha `= strength × 150 × (1 − i/layers)^1.6` ⇒ 逼近高斯衰减。
     *
     * ★ 层数与指数都是 bench 的实测值（`strength = 0.60` 时下方亮度 62.0）。
     *
     * ⚠️ **偏移（`+2 / +6`）不在位图里** —— 它在 [drawDropShadow] 用 `drawImage`
     *   的落点表达。两者**数学等价**（所有层共享同一个偏移 ⇒ 整体平移位图即可），
     *   但这样**同一份位图能服务两个方向**（`BOTTOM` 朝下 / `TOP` 朝上），
     *   缓存键里就不必带方向。
     *
     * ⚠️ **位图不含颜色信息**（纯黑 + alpha）—— 投影是"遮住桌面"，不是"画一块灰"。
     *
     * @return `null` ⇒ 尺寸非法（调用方已挡 `<= 0`，这里是纵深防御）
     */
    private fun shadowBitmap(w: Int, h: Int, cornerRadius: Double, percent: Int): BufferedImage? {
        val strength = shadowStrengthFraction(percent).toDouble()
        val key = "$w|$h|$cornerRadius|$percent"
        cachedShadow?.let { if (cachedShadowKey == key) return it }
        if (strength <= 0.0) return null

        val s = SHADOW_SHRINK
        val pad = SHADOW_PAD_PX.toDouble() / s
        val bw = (w + SHADOW_PAD_PX * 2) / s
        val bh = (h + SHADOW_PAD_PX * 2) / s
        if (bw <= 0 || bh <= 0) return null
        // ★ 圆角：与形状同源（`cornerRadius`），并按同一个倍数缩小。
        val arc = (if (cornerRadius > 0) cornerRadius else minOf(w, h) * 0.5 * 0.4) * 2.0 / s
        val img = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (i in SHADOW_LAYERS downTo 0) {
                val grow = i * SHADOW_GROW_STEP / s
                val a = (strength * SHADOW_MAX_ALPHA * Math.pow(1.0 - i / SHADOW_LAYERS.toDouble(), 1.6)).toInt()
                if (a <= 0) continue
                g.color = Color(0, 0, 0, a)
                g.fill(
                    RoundRectangle2D.Double(
                        pad - grow, pad - grow,
                        w / s.toDouble() + grow * 2, h / s.toDouble() + grow * 2,
                        arc, arc,
                    ),
                )
            }
        } finally {
            g.dispose()
        }
        cachedShadow = img
        cachedShadowKey = key
        return img
    }

    // ── 高光 ────────────────────────────────────────────────────────────────

    /**
     * 顶部高光的笔刷：白色从顶部向下淡出（"玻璃厚度"）。
     *
     * `α = strength / 100 × 46`（46 是 `GlassLab` 满强度的实测值）。
     *
     * ⚠️ `LinearGradientPaint` 的**关键帧必须严格递增**（相等也不行）—— 项目已知陷阱。
     *    本函数把"跨度退化"（`h` 极小）显式挡掉并返回 `null`，而不是交给 Java2D 抛异常。
     */
    private fun highlightPaint(x: Double, y: Double, h: Double, strength: Int): LinearGradientPaint? {
        val span = h * HIGHLIGHT_SPAN_RATIO
        if (!(span > 0.0)) return null
        val alpha = (strength.coerceIn(0, 100) / 100f * HIGHLIGHT_MAX_ALPHA).toInt()
            .coerceIn(1, 255)
        val from = Color(255, 255, 255, alpha)
        val to = Color(255, 255, 255, 0)
        return LinearGradientPaint(
            x.toFloat(), y.toFloat(),
            x.toFloat(), (y + span).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(from, to),
        )
    }

    // ── 渐变 ────────────────────────────────────────────────────────────────

    /**
     * ★★★ **④a「液态玻璃」的逐像素实现**（2026-10-01，用户拍板 A 方案）。
     *
     * ## 为什么换成逐像素（`migration/66-*` 的三方对照实测）
     *
     * 旧实现（[liquidPaint]，`RadialGradientPaint` 4 色标）**不是 Liquid Glass**：
     * ★ 它的"边缘亮环"是**沿半径**的（圆的），而业界是**沿法线**的（贴形状轮廓）
     * ⇒ 实测观感是"**一个发光的球**"（`migration/66-*` §4 的对照图）。
     *
     * ## ★★ 公式来源（**逐字照抄业界**，见 `recon/69-*` §4）
     *
     * · **SDF**：超椭圆角矩形（`roundedRectSDF` + `superellipseCornerSDF`）
     * · **法线** = SDF 的**中心差分梯度**（★ 业界刻意**不归一化**，用 `length` 恢复强度）
     * · **菲涅耳**：`clamp(pow(1 + mergedPx/1500*(500/range)² + hardness, 5), 0, 1)`
     * · **Glare**：几何因子（同构，换 range/hardness）× 角度因子（含**双向**）
     *
     * ⚠️ **`mergedPx` 是 SDF 值（内部为负）** —— 边界处 = 0 ⇒ `fresnel = 1`（最亮），
     *   向**内**快速衰减。★ 这正是"边缘光"的物理含义（掠射角）。
     *
     * ## ★★ 参数为什么取 `range = 100`（不是业界默认的 30）
     *
     * 实测（`migration/66-*` §2）：`range = 30` ⇒ **衰减宽度仅 4 px** ——
     * ★ 那对**桌面 UI**（按钮几十像素）合理，★ 但**词岛是 304×154** ⇒ 太细看不见。
     * `range = 100` ⇒ **40 px** ✓（实测观感才对）。
     *
     * ## ★★ 缓存（这是它能用的**唯一**理由）
     *
     * 逐像素实测 **13.6 ms/帧**（304×154，81.5% 的 60fps 预算）—— **不能每帧算**。
     * ⇒ **1 槽缓存**：★ 形状与参数不变时**只算一次** ⇒ **稳态成本 ≈ 0** ✓
     * ⇒ 只有**形状变化**（收展动画）时才重算 —— ★ 那时若成为瓶颈，**下一步做降级链**
     *   （降级到分层近似，见 `migration/66-*` §6.2）。
     *
     * @param w/h 目标位图尺寸（像素）
     * @param cornerRadius 圆角半径（px）
     * @param roundness 超椭圆指数（`2` = 标准圆角；越大角越方，业界 `2..8`）
     * @param tintPercent 染色强度（与 `LINEAR` 同语义）
     * @param baseColor 底色
     * @param endArgb 染色止色
     * @param glareAngleDeg Glare 方向（度）
     */
    private fun liquidGlassImage(
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double,
        tintPercent: Int,
        baseColor: Color,
        endArgb: Int,
        glareAngleDeg: Double,
        /** ★ 高光强度 `0..100`（`material.liquid.glare_strength`）⇒ 内部 `/100`。 */
        glarePercent: Int = 60,
        /** ★ 立体感（`material.liquid.depth_effect`）：`false` ⇒ 关掉菲涅耳边缘光。 */
        depthEffect: Boolean = true,
        /**
         * ★★ **是否处于"形状正在动画"的状态**（T1 降级链的**唯一开关**）。
         *
         * `true` ⇒ 用 [GLASS_ANIM_SHRINK] 降采样（成本 ≈3.4 ms）；
         * `false`（默认）⇒ 全分辨率（成本 13.6 ms，但有缓存 ⇒ 稳态 ≈0）。
         *
         * ## ⚠️ 为什么必须**显式传**（而不是内部自适应）
         *
         * 初版设计是"内部检测 key 变化频率"（距上次变化 < 200 ms ⇒ 动画中）——
         * ★★ **探针把它当场证伪了**：探针顺序调用 `paint` 时间隔 **< 1 ms**
         * ⇒ 恒判"动画中" ⇒ 恒降采样 ⇒ `⑫-5 / ⑫-6 / ⑫-5b / ⑫-7` 四条**观感断言全部转红**。
         * ⇒ ★ **自适应在"离屏逐像素断言"面前不可靠** —— 那类断言要的是**确定性**。
         *
         * ## 真机的判据（由调用点给）
         *
         * `bg.fraction ∈ (0f, 1f)` **开区间** ⇒ 正在展开/收起 ✓
         * （`0` = 完全收起、`1` = 完全展开 ⇒ 都是**稳态**，不降级）。
         * ★ 该判据**无状态、语义清晰**，且不会把"连续两次静态绘制"误判成动画。
         */
        animating: Boolean = false,
        /**
         * ★★ **显式指定降采样倍率**（★ 仅**探针**用；生产代码传 `null`）。
         *
         * ## 为什么需要它（2026-10-06 · **R2** 连带）
         *
         * ★ `shrink` 原来**只由 `animating` 间接决定** ⇒ ★ **探针无法指定"我要 shrink=1"** ✗
         * ★ 本项目的纪律是「**能显式传的就显式传**」（★ 启发式已被证伪，见 `migration/74`）✓
         * ⇒ 加本参数后：★ **生产 = 走 `animating` 逻辑** · ★ **探针 = 精确指定** ✓
         */
        shrinkOverride: Int? = null,
        /**
         * ★★ **下边圆角半径**（T5，2026-10-02）—— `NaN`（默认）⇒ **与上边同值**（= 改动前行为）。
         *
         * `shape.type = NOTCH` 是**上大下小** ⇒ 传了它，玻璃边缘才与岛体轮廓**逐像素贴合**；
         * 不传则退化为"取较大者"的近似（★ 改动前就是这个行为）。
         */
        cornerRadiusBottom: Double = Double.NaN,
        /**
         * ★★★ **表面不透明度**（`0..100`，来自 `background.opacity`）—— 2026-10-17 对齐 A1。
         *
         * ## 语义（与参考/竞品一致）
         * `glassAlpha = 本值 / 100 × **0.5**` ⇒ **上限 0.5** ⇒ 玻璃永远保留 ≥50% 的身后内容。
         * ★ 此前本函数把 alpha 写成**覆盖率**（形状内恒 255）⇒ 玻璃是不透明的实色板。
         *
         * ## 默认 100 的含义
         * 不是"全不透明"，而是"**取上限 0.5**"（`100/100 × 0.5`）。
         * ★ 传 0 ⇒ 完全透明（只剩高光/菲涅耳在 alpha 极低处，几乎不可见）。
         */
        surfaceOpacityPercent: Int = 100,
    ): BufferedImage? {
        if (w <= 0 || h <= 0) return null
        // ── ★★★ T1 降级链（2026-10-02）：动画期间**降采样** ──────────────────
        //
        // ## 为什么 `shrink` **进 key**
        //   "动画中"（`shrink = 2`）与"稳态"（`shrink = 1`）是**两种不同的产物**
        //   ⇒ 各占一槽缓存。★ 进 key 的收益：**切换自动生效**（key 变了 ⇒ 不命中 ⇒ 重建），
        //   不需要任何"动画停了要补一张全分辨率"的额外逻辑。
        //   ★ 代价：两态**来回切换时各重建一次** —— 而切换只发生在"动画开始/结束"各一次
        //   （每轮悬停 2 次）⇒ 可忽略。
        // ★★★ 2026-10-06 **R2**：稳态也走降采样（★ 原为 `else 1`）
        //   ★ 依据：探针 ⑬-23 实测"降采样版与全分辨率版平均通道差 **0.148/255（0.06%）**"✓
        //   ★ 收益：成本 1/4；★ 配合 R1 后**EDT 不受影响 + 总 CPU 降 4×** ✓
        // ★★★ 2026-10-06 **R2 已【回退】**（★ 探针实测否决，见 `GLASS_STEADY_SHRINK` 的 KDoc）
        val shrink = shrinkOverride ?: if (animating) GLASS_ANIM_SHRINK else 1
        // ★★★ 2026-10-03 **补 `cornerRadiusBottom`**（一处"改了参数、忘了改缓存键"的静默缺陷）：
        //   `cornerRadiusBottom` 是 2026-10-02 加的（T5 双半径，`NOTCH` 用），
        //   **但它没进本键** ⇒ ★ **改「下边圆角」时缓存不失效** ⇒ 玻璃仍按**旧半径**渲染
        //   （`NOTCH` 下边缘与岛体轮廓错开），**不报错、不警告**。
        //   ★ 同族判据（**可复用**）：**凡是进了函数体的参数，都必须进缓存键** ——
        //     漏一个就是"改了设置不重绘"，而它的症状与"设置无效"**完全一样**，极难归因。
        //   ★ 本键已逐参数核对（13 个形参 → 12 项）：`w/h/cornerRadius/cornerRadiusBottom/
        //     roundness/tintPercent/baseColor/endArgb/angleDeg/glarePercent/depthEffect/animating/
        //     **surfaceOpacityPercent**` 全覆盖 ✓
        val key = "$w|$h|$cornerRadius|$cornerRadiusBottom|$roundness|$tintPercent|${baseColor.rgb}|$endArgb" +
            "|$glareAngleDeg|$glarePercent|$depthEffect|s$shrink|a$surfaceOpacityPercent"
        cachedLiquid?.let { if (cachedLiquidKey == key) return it }

        // ═══════════════════════════════════════════════════════════════════════
        // ★★★ D-1（2026-10-09）：**GPU 分派（入口）**
        // ═══════════════════════════════════════════════════════════════════════
        // ## 判据（两条全真才走 GPU）
        //   ① [gpuEnabled] —— 上层注入的 `material.render.gpu_backend`（默认 `false` ⇒ 逐位不变）
        //   ② `!gpuDisabled` —— 未因连续失败被永久关闭（见 [GPU_FAILURE_LIMIT]）
        //
        // ## 回退（★ 三条，缺一不可 —— 见 [liquidGlassImageOnGpu] 的 KDoc）
        //   · `render()` 返回 `null`（编译失败 / 上下文丢失 / 驱动不支持）⇒ 记一次失败 ⇒ 走 CPU
        //   · 抛 `Throwable` ⇒ 记一次失败 ⇒ 走 CPU
        //   · 连续失败达 [GPU_FAILURE_LIMIT] ⇒ **永久关闭**（`gpuDisabled = true`，不再每帧白试）
        //
        // ## ★★ 绝不返回 null
        //   ★ 无论 GPU 成败，本函数都**落到下面那条 CPU 循环**（或直接返回 GPU 图）⇒
        //     **调用方拿到的永远是一张可用位图**（★ 拿 `null` 会画纯色 ⇒ "没玻璃"）✓
        //
        // ## ★ 缓存口径
        //   ★ 本分支在**缓存未命中之后**才走 ⇒ 命中时**零开销**（连 `gpuEnabled` 都不看）✓
        //   ★ GPU 结果**照常写入 1 槽缓存** ⇒ 稳态不会每帧重渲染 ✓
        if (gpuEnabled && !gpuDisabled) {
            val gpuImg = try {
                liquidGlassImageOnGpu(
                    w, h, cornerRadius, roundness, tintPercent, baseColor, endArgb,
                    glarePercent, depthEffect, cornerRadiusBottom,
                )
            } catch (t: Throwable) {
                // ★ 故障域隔离：GPU 侧任何异常都不外泄 ⇒ 落到下面的 CPU 循环
                null
            }
            if (gpuImg != null) {
                gpuFailStreak = 0
                cachedLiquid = gpuImg
                cachedLiquidKey = key
                cachedLiquidShrunk = false
                return gpuImg
            }
            // ★ 失败 ⇒ 计数；达上限 ⇒ **一次性记忆**地永久关闭（避免每帧白试）
            if (++gpuFailStreak >= GPU_FAILURE_LIMIT) gpuDisabled = true
            // ★ 落到 CPU（下面那段一行未改）
        }

        // ── ★★★ 材质参数（**每个值都有出处，不臆断**）────────────────────────
        //
        // ## 有"那份 Android 参数面板"直接对应的（用户 2026-10-01 指令：**用它的值**）
        //
        // · `glareFactor = 0.60` ← 面板的「**高光强度 0.60**」
        //   ★★ 而我们原先用 **1.0** ⇒ **那正是"糊"的主因**（面板 0.60 才是对的）
        //
        // ## 面板里没有、但**业界有默认值**的（`LiquidGlassWinUI`，见 `recon/69-*` §2）
        //
        // · `fresnelHardness = 0.20` ← `RefFresnelHardness` = 20（/100）
        // · `fresnelFactor  = 0.20` ← `RefFresnelFactor`  = 20（/100）
        // · `glareHardness  = 0.20` ← `GlareHardness`     = 20（/100）
        // · `glareConvergence = 0.50` ← `GlareConvergence` = 50（/100）
        // · `glareOpposite  = 0.80` ← `GlareOppositeFactor` = 80（/100）
        // · `roundness      = 5`    ← `ShapeRoundness` 默认 **5**（范围 2..8）
        //
        // ## ★★★ `fresnelRange` / `glareRange` = 64 —— 这一条是**推导出来的**，不是抄的
        //
        // 业界默认 `range = 30` 是**绝对值** ⇒ 实测衰减宽度只有 **4 px** ——
        // ★ 那对**桌面按钮**（40px 高）合理，但**词岛 154px 高**就显得太细。
        // ⇒ 说明"**绝对值**"本身就不对，应该按**目标宽度**反推：
        //
        //   设目标衰减宽度 `W`（px），由 `(1 - W·k + hardness)^5 = 0.05` 解得
        //   `k = 0.651 / W`，而 `k = (500/range)² / 1500`
        //   ⇒ ★ **`range = 16·√W`**
        //
        // ★ **`W` 取多少？** —— 面板里 **`refractionHeight = 16 dp`（折射厚度）**
        //   正是"**边缘厚度**"的对应量 ⇒ **`W = 16`** ⇒ ★★ **`range = 16·√16 = 64`** ✓
        //
        // ⚠️ 这套值**只在不开放给用户时使用**；开放的那几项由设置项覆盖（见 `liquidGlassImage` 的实参）。
        val fresnelRange = 64.0
        val fresnelHardness = 0.20
        // ★★ 立体感：关掉 ⇒ 菲涅耳边缘光为 0（只剩 Glare）
        val fresnelFactor = if (depthEffect) 0.20 else 0.0
        val glareRange = 64.0
        val glareHardness = 0.20
        // ★★ 高光强度：来自设置项（`material.liquid.glare_strength` / 100）
        val glareFactor = (glarePercent.coerceIn(0, 100)) / 100.0
        val glareConvergence = 0.50
        val glareOpposite = 0.80

        val t = (tintPercent.coerceIn(0, 100)) / 100.0
        val end = Color((endArgb ushr 16) and 0xFF, (endArgb ushr 8) and 0xFF, endArgb and 0xFF)
        val br = baseColor.red; val bg = baseColor.green; val bb = baseColor.blue
        // ★ 染色后的底色（与 `LINEAR`/旧 `LIQUID` 同语义：向止色混 t）
        val cr0 = br + (end.red - br) * t
        val cg0 = bg + (end.green - bg) * t
        val cb0 = bb + (end.blue - bb) * t

        // ★★★ 2026-10-17（对齐 A1）：**表面 α 上限 0.5** —— 与参考的 `MATERIAL` 层同语义：
        //   `mix(surfaceTint, backdrop, 1 − α)`，α 最高 **0.5** ⇒ 玻璃**永远保留 ≥50% 身后内容**。
        //   ★ 此前这里写死 `aa`（形状覆盖率）⇒ 形状内恒 255 ⇒ 玻璃是一块实色板，
        //     把底下的 L3 整个盖死。这是"完全不透明"的直接原因之一。
        val glassAlpha = (surfaceOpacityPercent.coerceIn(0, 100) / 100.0) * 0.5

        // ★★ T1 降级链：位图按 `shrink` **降采样**（`shrink = 1` ⇒ 与改动前**逐像素相同**）。
        //   ⚠️ SDF 与所有几何量仍按**全分辨率**（`w` / `h`）算 —— 降的只是**采样密度**。
        //   绘制侧 `drawImage` 会自动放大 `shrink` 倍（与 [drawDropShadow] 同款）。
        val bw = (w + shrink - 1) / shrink
        val bh = (h + shrink - 1) / shrink
        val img = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB)
        val px = IntArray(bw * bh)
        val eps = GlassSdf.NORMAL_EPS_PX
        // ★★ 2026-10-01 ④b 开工：SDF **不再是本文件的内联函数** ——
        //   它被抽成 [GlassSdf]（公开纯函数），因为**采集侧的背景折射要算同一条边**
        //   （见 `GlassSdf` 的 KDoc「为什么必须抽出来」）。
        //   ⚠️ 抽出去**逐像素结果不变**：`GlassSdf.of(x,y,w,h,cr,n)` 与原来的
        //      `sdf(x,y)` 是**同一个式子**（中心/半宽都由 w/h 推），只是把捕获改成传参。
        val kFr = Math.pow(500.0 / fresnelRange, 2.0) / 1500.0
        val kGl = Math.pow(500.0 / glareRange, 2.0) / 1500.0
        val glareBase = Math.toRadians(glareAngleDeg)

        // ═══════════════════════════════════════════════════════════════════════
        // ★★★ B-1（2026-10-09）：**逐像素行循环 → 按行分片并行**
        // ═══════════════════════════════════════════════════════════════════════
        // ## 不变式（★ 断言级说明）
        //   ★ 内层循环**只写 `px[by * bw + bx]`** —— ★ **每行写自己那一段内存、无跨行依赖**
        //   ⇒ ★ **按行分片 = 各片写不相交的内存** ⇒ ★ **结果与单线程【逐位相同】** ✓
        //   （★ 任何"更快"的改法都**不得**破坏这条 —— 它是本改动的**正确性依据**。）
        // ## 分片策略
        //   ★ 粒度 = **行**（每片 `chunk = ceil(bh / 分片数)` 行，连续区间）。
        //   ★ 分片数 = [glassShardCount]（= `min(核数, [GLASS_SHARD_MAX])`，≥ 1）。
        //   ★ **当前线程干第 0 片**，其余 `分片数 - 1` 片提交给 [glassShardPool] ——
        //     ★ 这样**少起一个线程、少一次调度**（"当前线程也参与"更省）✓
        //   ★ 为何可并行 / 为何封顶 4 / 为何 MIN_PRIORITY —— 见 [glassShardPool] 的 KDoc
        //     （★ 出处：探针实测的**分片加速上限 4.16×**）。
        fun renderRows(y0: Int, y1: Int) {
            val grad = DoubleArray(2)
            for (by in y0 until y1) {
                for (bx in 0 until bw) {
                    // ★ 采样点 = **块中心**（`shrink = 1` ⇒ `bx + 0.5`，与改动前**逐位相同**）。
                    val fx = bx * shrink + shrink * 0.5; val fy = by * shrink + shrink * 0.5
                    val m = GlassSdf.of(fx, fy, w, h, cornerRadius, roundness, cornerRadiusBottom)
                    if (m > 0.5) continue                      // ★ 形状外（留 0.5px 做 AA）
                    val aa = (0.5 - m).coerceIn(0.0, 1.0)
                    // ★ 法线 = SDF 中心差分（**未归一化**，见 [GlassSdf.gradient] 的 KDoc）
                    GlassSdf.gradient(fx, fy, w, h, cornerRadius, roundness, grad, eps, cornerRadiusBottom)
                    val gx = grad[0]
                    val gy = grad[1]
                    val gl = Math.hypot(gx, gy)
                    val nx = if (gl > 1e-9) gx / gl else 0.0
                    val ny = if (gl > 1e-9) gy / gl else 0.0
                    // ★ 菲涅耳
                    var fr = 1.0 + m * kFr + fresnelHardness
                    fr = if (fr < 0) 0.0 else Math.pow(fr, 5.0)
                    fr = fr.coerceIn(0.0, 1.0) * fresnelFactor
                    // ★ Glare（几何 × 角度，含双向）
                    var gg = 1.0 + m * kGl + glareHardness
                    gg = if (gg < 0) 0.0 else Math.pow(gg, 5.0)
                    gg = gg.coerceIn(0.0, 1.0)
                    var a = Math.atan2(ny, nx)
                    if (a < 0) a += 2 * Math.PI
                    val ga = (a - Math.PI / 4.0 + glareBase) * 2.0
                    val far = (ga > Math.PI * 1.5 && ga < Math.PI * 3.5) || ga < -Math.PI * 0.5
                    var af = (0.5 + Math.sin(ga) * 0.5) * (if (far) 1.2 * glareOpposite else 1.2) * glareFactor
                    if (af < 0) af = 0.0
                    af = Math.pow(af, 0.1 + glareConvergence * 2.0).coerceIn(0.0, 1.0)
                    // ═══════════════════════════════════════════════════════════════
                    // ★★★ 2026-10-06 **R3：补两处参考实现的步骤**（用户要求"最接近网页效果"）
                    //   来源：`Code_20260908143339.html` 的 `FRAG` L335-344（★ 逐字照抄公式）
                    //
                    // ## ① 边缘内阴影（★ 造"厚度"）—— ★ 本插件原来【完全没有】
                    //
                    // ★ 参考：`inner = exp(-sd / max(refH*0.42, 1))`；`glass *= mix(1, 0.90, inner*0.55)`
                    // ★ 本处：`m` 就是 SDF ⇒ **`sd = -m`**（形状内 `m < 0`）✓
                    //   ⇒ `inner = exp(m / (refH*0.42))` —— 边缘 `m→0` ⇒ `inner→1`（最暗）·
                    //     中心 `m` 很负 ⇒ `inner→0`（不变暗）✓
                    //
                    // ⚠️ **必须插在【混白之前】** —— 因为参考的菲涅耳/Glare 都是**加白**，
                    //   若插在之后，变暗会被前面的白冲淡（★ 本插件原来的两层都是加白）✓
                    //
                    // ## ② hairline 细亮边（★ 参考 L344）—— ★ 本插件原来【没有】
                    //
                    // ★ 参考：`hair = smoothstep(1.6*dpr, 0, sd) * uSpec * 0.30`
                    // ★ 本处：`smoothstep(1.6, 0, -m)` ⇒ 距边缘 <1.6px ⇒ 1，>1.6px ⇒ 0 ✓
                    //   ⇒ ★ **1.6px 宽的锐利亮边**（★ 与插件的菲涅耳"≈40px 宽"形成对比）✓
                    //
                    // ★★ `refH` 取 `min(w,h)*0.08` —— ★ **不改函数签名**（★ 零影响面）；
                    //   ⚠️ 若日后要开放可调，再加参数（★ 参考的 refH = 16dp，本式在 304×154 下 ≈12.3）✓
                    val refH = (if (w < h) w else h) * 0.08
                    // ⚠️ **符号**：参考是 `exp(-sd / …)`（`sd` = 到边缘的距离，**形状内为正**）。
                    //   ★ 本处 `m` 就是 SDF ⇒ **`sd = -m`** ⇒ `exp(-sd/…) = exp(m/…)` ✓
                    //   ★ 语义：`m < 0`（形状内）⇒ 中心很负 ⇒ `exp→0`（**不变暗**）·
                    //     边缘 `m→0` ⇒ `exp→1`（**最暗**）✓
                    // ★★ **我第一次写成 `exp(sd/…)`** ⇒ ★ 中心处 `exp(14.9)` ⇒ 巨大 ⇒
                    //    **被探针 ⑫-5/⑫-6/⑫-5b/⑫-7 当场抓到**（★ 护栏的价值）✗
                    val sd = -m
                    val innerShadow = Math.exp(m / (refH * 0.42).coerceAtLeast(1.0))
                    val dimFactor = 1.0 - 0.10 * (innerShadow * 0.55)

                    // ★ 合成：内阴影（变暗）→ 底色 → 菲涅耳（混白）→ Glare（再混白）→ hairline
                    var r = cr0 * dimFactor
                    var g2 = cg0 * dimFactor
                    var b2 = cb0 * dimFactor
                    r += (255 - r) * fr; g2 += (255 - g2) * fr; b2 += (255 - b2) * fr
                    val gw = af * gg * 0.9
                    r += (255 - r) * gw; g2 += (255 - g2) * gw; b2 += (255 - b2) * gw
                    // ② hairline：★ 只在距边缘 1.6px 内加白
                    //
                    // ★★★ 2026-10-09 批 A-3 核（★ 结论：**与参考同源，不改**）——
                    //   ⚠️ 原注释写「强度 = glarePercent 的一半」，容易被读成"与参考不同源"。★ 实为：
                    //   ★ 参考（`FRAG`）：`spec = rim*(…) * uSpec * 0.55` · `hair = (1−sd/1.6) * uSpec * 0.30`
                    //   ⇒ ★★ **`uSpec` 同时驱动 rim 高光与 hairline** —— ★ 它们**本来就是同一个 intensity** ✓
                    //   ★ 本插件的 `glarePercent`（`material.liquid.glare_strength`）同样驱动
                    //     菲涅耳/Glare（`glareFactor = glarePercent/100`）与 hairline
                    //   ⇒ ★ **语义与参考一致**（一个强度旋钮管整组高光）✓
                    //   ★ 唯一差异是**量纲**：参考 `uSpec ∈ 0..1`，这里 `glarePercent ∈ 0..100` ⇒ `/100` ✓
                    // ===============================================================
                    // A-3（2026-10-09）：**hairline 的强度来源已核，结论 = 保持绑死**
                    // ===============================================================
                    //  参考实现（`Code_20260908143339.html` 的 `FRAG`）原文：
                    //    float spec = rim * (0.35 + 0.65 * max(face, 0.0)) * uSpec * 0.55;
                    //    float hair = smoothstep(1.6 * uDpr, 0.0, sd) * uSpec * 0.30;
                    //  ⇒ **rim 与 hairline 共用【同一个 uSpec】** ✓
                    //  ⇒ 本处把 hairline 绑到 `glarePercent`（= `material.liquid.glare_strength`，
                    //     即插件的"高光强度"）**是照参考的**，★ **不是缺陷** ✓
                    //  ⇒ ★ **结论：不解绑**（★ 若解绑反而【偏离参考】，且要多一个键）✓
                    //
                    // ★★ **核对来源（2026-10-09 · 本次 A-3 的"先核"）**：
                    //   ★ 查 `LiquidGlassShader.uniforms` 的 **uniform 表**（★ 那份是参考 shader 的
                    //     **逐字移植版**）—— 表**原文**写明：`uSpec ← material.liquid.glare_strength`
                    //     （★ 换算 `params.glarePercent / 100`），且注明它**同时**驱动 rim 与 hairline ✓
                    //   ⇒ ★ **`uSpec` 确 = `glarePercent`**（= `material.liquid.glare_strength`）✓
                    //   ⇒ ★ **原对应关系正确，本次【不改来源】**，只补这条权威出处（★ 之前只引了 HTML）✓
                    val hairT = (1.0 - (sd / 1.6).coerceIn(0.0, 1.0))
                    val hair = hairT * (glarePercent / 100.0) * 0.30
                    if (hair > 0.0) {
                        r += (255 - r) * hair; g2 += (255 - g2) * hair; b2 += (255 - b2) * hair
                    }
                    // ★★★ 2026-10-17（对齐 A1）：alpha = **覆盖率 × 表面 α**（表面 α ≤ 0.5）。
                    //   此前是 `aa` 单独 ⇒ 形状内恒 255 ⇒ 不透明。
                    px[by * bw + bx] = ((aa * glassAlpha * 255).toInt() shl 24) or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g2.toInt().coerceIn(0, 255) shl 8) or
                        b2.toInt().coerceIn(0, 255)
                }
            }
        }

        // ★★ 分片调度（★ 降级：任何异常 ⇒ 单线程重算整张；★ 绝不因此不画）
        val shardN = glassShardCount
        if (shardN <= 1 || bh <= 1) {
            renderRows(0, bh)                       // ★ 单线程（= 改动前行为，逐位相同）
        } else {
            val chunk = (bh + shardN - 1) / shardN  // 每片行数（向上取整，≥ 1）
            val futures = ArrayList<java.util.concurrent.Future<*>>(shardN - 1)
            var degraded = false
            try {
                for (i in 1 until shardN) {
                    val y0 = i * chunk
                    if (y0 >= bh) break
                    val y1 = if (y0 + chunk < bh) y0 + chunk else bh
                    futures.add(glassShardPool.submit(java.lang.Runnable { renderRows(y0, y1) }))
                }
                renderRows(0, if (chunk < bh) chunk else bh)   // ★ 当前线程也干一片
            } catch (t: Throwable) {
                degraded = true
            }
            // ★ 收口：**先等全部分片结束**，再决定是否降级 ——
            //   ⚠️ 否则残留分片会在"单线程重算"【之后】写回 ⇒ 覆盖重算结果 ✗
            for (f in futures) {
                try { f.get() } catch (t: Throwable) { degraded = true }
            }
            if (degraded) renderRows(0, bh)         // ★ 退化为单线程（覆盖式写入 ⇒ 结果正确）
        }
        img.setRGB(0, 0, bw, bh, px, 0, bw)
        cachedLiquid = img
        cachedLiquidKey = key
        // ★★ T1 降级链：记下"这张缓存是降采样版" ⇒ 动画停后**不命中** ⇒ 会补一张全分辨率。
        cachedLiquidShrunk = shrink > 1
        return img
    }

    /**
     * ★★★ **D-1：试走 GPU 渲染一张液态玻璃位图**（2026-10-09）。
     *
     * ## 出处（★ 复用，**不另写一份 GL**）
     * ★ 渲染器 = `GlOffscreen.render`（`render/gpu/GlOffscreen.kt`）—— ★ **返回 `BufferedImage?`**，
     *   与 CPU 路径 [liquidGlassImage] **同类型** ⇒ 调用方无需区分来源 ✓
     * ★ uniform 表 = `LiquidGlassShader.uniforms(...)`（★ 参考 shader 的逐字移植版 + 参数换算）·
     *   shader 源码 = `LiquidGlassShader.fragmentShaderSource()` / `fragmentShaderFallback()` ✓
     *
     * ## ★★ 为什么在 [glassPool] 上调它**是安全的**（线程）
     * ★ `GlOffscreen` 把**全部 GL 调用钉在它自己的单线程执行器**上（`spw-gl` · `MIN_PRIORITY`）——
     *   本函数只是「提交任务 + `get()` 等结果」⇒ ★ **在哪个线程调都安全** ✓
     * ★ 代价：**阻塞**（`Future.get()`）。本层跑在 [glassPool]（单线程 · `MIN_PRIORITY`）⇒
     *   ★ 阻塞的是**玻璃工作线程**，**不是 EDT** ✓（与 R1 的既定分工一致）
     *
     * ## ★★★ 形状 alpha 适配（**必须** —— 否则四角会变成方块）
     * ⚠️ `LiquidGlassShader` 的 `outColor = vec4(col, 1.0)` ⇒ ★ **输出 alpha 恒 255**
     *   （它是给 L3「整张矩形都是背景」那条路设计的）。★ 而本层要求**形状外透明** ——
     *   [paint] 在 `g2.clip = shape` **之前**就 `drawImage` 本图（见文件头硬约束：材质层
     *   **不裁剪**，靠"自己裁到 shape"）⇒ ★ 不补 alpha 的话，圆角/胶囊的四角会**糊上一块方角** ✗
     * ⇒ ★ 本函数在 GPU 出图后**补一次形状 alpha 掩膜**（[maskAlphaToShape]，复用 [GlassSdf.of]，
     *   与 CPU 路径**同一个 SDF、同一条 AA 判据** ⇒ 边缘与 CPU 版对齐）✓
     * ★★ **干净的修法是给 shader 末尾那行改成 `vec4(col, edge)`** —— 但那在
     *   `render/gpu/LiquidGlassShader.kt`（**本次任务的只读文件**）⇒ 本处用掩膜适配，
     *   **如实记录**：这是 D-1 的**临时适配**，不是终态 ✓
     *
     * ## ⚠️ 已知近似（如实记录 —— 也是"开关默认关"的理由之一）
     * · **`roundness` 不参与 GPU shader** —— `LiquidGlassShader` 的 `sdRoundRect` 是**圆形**圆角
     *   （无超椭圆项），而 CPU 路径用**超椭圆**（`n = 5`）⇒ 两版的**角形状不同**；掩膜用超椭圆
     *   ⇒ 角上可能露出几像素的底色（近似）。★ 注意：就"逼近网页参考"而言，GPU 版**更忠实**
     *   （参考本身就是圆角）—— 差异是"参考 vs 插件自有变体"，不是"对 vs 错"。
     * · **`glareAngleDeg` 被 shader 硬编码** —— 参考用 `lightDir = normalize(vec2(-0.72, 0.69))`，
     *   无 uniform ⇒ ★ 该设置项对 GPU 路径**无效**（CPU 版用它转 Glare 角）。
     * · `LiquidGlassShader` 只有**一个** `uCorner`（无 `uCornerBottom`）⇒ `NOTCH`（上大下小）
     *   档下**玻璃几何**按上边半径 ⇒ 与掩膜（双半径）在下边**略有错开**（近似）。
     * · `dpr` 取 **1.0** —— 本层只有**设备像素**口径（无 dp）⇒ 与 CPU 版的 1.6 px
     *   hairline / AA 对齐 ✓
     * · backdrop = **染色后的底色纯色图**（见 [solidTintBackdrop]）⇒ 折射/色散/模糊在其上
     *   **不可见**；GPU 与 CPU 的差异主要在**边缘内阴影 / rim / hairline** 的实现方式上 ✓
     *
     * @return GPU 位图；**不可用 / 失败 ⇒ `null`**（★ 调用方负责计数与回退 CPU）
     */
    private fun liquidGlassImageOnGpu(
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double,
        tintPercent: Int,
        baseColor: Color,
        endArgb: Int,
        glarePercent: Int,
        depthEffect: Boolean,
        cornerRadiusBottom: Double,
    ): BufferedImage? {
        val backdrop = solidTintBackdrop(w, h, tintPercent, baseColor, endArgb)
        val params = LiquidGlassShader.Params(
            // ★ 表面蒙版 / 投影由 [paint] 的其它层单独画（`drawSurfaceAlpha` / `drawDropShadow`）
            //   ⇒ 这里**必须 0**，否则会被叠两次 ✗
            surfaceAlphaPercent = 0,
            shadowPercent = 0,
            glarePercent = glarePercent.coerceIn(0, 100),
            depthEffect = depthEffect,
        )
        // ★ uniform 表 = `LiquidGlassShader` 的换算函数（键 = GLSL 里的 uniform 名）。
        val uniforms = LiquidGlassShader.uniforms(backdrop, GPU_DPR, cornerRadius, params)
        val img = glOffscreen.render(
            w, h,
            LiquidGlassShader.fragmentShaderSource(),
            uniforms,
            LiquidGlassShader.fragmentShaderFallback(),
        ) ?: return null
        // ★ 形状 alpha 掩膜（见本函数 KDoc：shader 输出恒不透明）。
        maskAlphaToShape(img, w, h, cornerRadius, roundness, cornerRadiusBottom)
        return img
    }

    /**
     * ★★ **D-1：造一张纯色 backdrop**（= 染色后的底色）供 GPU shader 采样。
     *
     * ★ 本层（L2 玻璃）**没有真实背景** —— 它的语义是"在半透明底色上再叠一层玻璃"
     *   （与 L3 的"折射真实桌面"不同，见 `docs/D-1与D-2对比-*` 的分阶段说明）。
     * ⇒ ★ 用 [liquidGlassImage] 里**同一条染色式**（底色向止色混 `t = tint/100`）铺一张纯色图；
     *   GPU 的"折射 / 色散 / 模糊"在其上**不可见**（纯色平移 / 模糊后仍是同色）⇒
     *   ★ 与 CPU 版的差异只剩**边缘内阴影 / rim / hairline** 的实现方式 ✓
     *
     * ⚠️ 每次调用分配一张 `w×h` 位图 + 一个 `Graphics2D` —— ★ 与 CPU 路径**同量级**
     *   （它本来就分配 `BufferedImage` + `IntArray`），且**只在缓存未命中时**才走到这里 ✓
     */
    private fun solidTintBackdrop(
        w: Int, h: Int, tintPercent: Int, baseColor: Color, endArgb: Int,
    ): BufferedImage {
        val t = tintPercent.coerceIn(0, 100) / 100.0
        val er = (endArgb ushr 16) and 0xFF
        val eg = (endArgb ushr 8) and 0xFF
        val eb = endArgb and 0xFF
        val r = (baseColor.red + (er - baseColor.red) * t).toInt().coerceIn(0, 255)
        val g = (baseColor.green + (eg - baseColor.green) * t).toInt().coerceIn(0, 255)
        val b = (baseColor.blue + (eb - baseColor.blue) * t).toInt().coerceIn(0, 255)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            g2.color = Color(r, g, b)
            g2.fillRect(0, 0, w, h)
        } finally {
            g2.dispose()
        }
        return img
    }

    /**
     * ★★★ **D-1：把 GPU 出图的 alpha 按形状掩膜**（理由见 [liquidGlassImageOnGpu] 的 KDoc）。
     *
     * ★ 判据与 CPU 路径**逐字相同**：`aa = (0.5 − m).coerceIn(0,1)`（`m` = [GlassSdf.of]，
     *   采样点 = 像素中心 `+0.5`）⇒ ★ 边缘与 CPU 版落在**同一条线**上（"不要造第二份实现"）✓
     * ★ **只改 alpha**（保留 GPU 的 RGB）—— 形状外像素的 RGB 无所谓（`aa = 0` ⇒ SrcOver 下不可见）✓
     * ★ 单线程（与 CPU 分片不同）—— 本趟只做**一次** SDF 求值/像素（CPU 那趟还要 4 次梯度 + 着色），
     *   成本远低于 CPU 全循环 ⇒ 不在本层再起分片（避免与 [glassShardPool] 抢 CPU）✓
     */
    private fun maskAlphaToShape(
        img: BufferedImage, w: Int, h: Int,
        cornerRadius: Double, roundness: Double, cornerRadiusBottom: Double,
    ) {
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        for (y in 0 until h) {
            val fy = y + 0.5
            val row = y * w
            for (x in 0 until w) {
                val m = GlassSdf.of(x + 0.5, fy, w, h, cornerRadius, roundness, cornerRadiusBottom)
                val a = ((0.5 - m).coerceIn(0.0, 1.0) * 255.0).toInt()
                val i = row + x
                px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w)
    }

    /**
     * ★★ 供探针断言 **T1 降级链**：按**指定的 `shrink`** 构一张逐像素玻璃位图。
     *
     * ## 为什么需要它
     *
     * [liquidGlassImage] 的 `shrink` 由**自适应**决定（见 [GLASS_ANIM_WINDOW_MS]）——
     * 探针**无法**从外部指定"我要全分辨率版 / 降采样版"来做比对。
     * ⇒ 本入口**绕过缓存与自适应**，把 `shrink` 直接传下去。
     *
     * ## 探针该断言什么（★ 不是"能不能跑"，而是"**降级有没有代价**"）
     *
     * ① **`shrink = 1` ⇒ 尺寸 = `w × h`** —— 与改动前**逐位相同**由算式保证
     *    （`bw = w` · `fx = bx + 0.5` · `px[by*bw+bx]` 与 `px[y*w+x]` 等价）。
     * ② ★★ **`shrink = 2` 的观感偏差 < 阈值** —— 这是**降级链能否成立的全部依据**：
     *    若降采样让玻璃明显变样，动画期间用户就会看到"玻璃闪一下"
     *    ⇒ 那降级链**就不该上**（宁可掉帧，也不能有可见跳变）。
     *
     * ⚠️ **生产不调用它**（只在 `src/probe` 里用）。
     */
    fun liquidGlassImageForProbe(
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double,
        tintPercent: Int,
        baseColorArgb: Int,
        endArgb: Int,
        glareAngleDeg: Double,
        glarePercent: Int,
        depthEffect: Boolean,
        animating: Boolean,
        /** ★★ **显式降采样倍率**（★ 探针用；★ 生产传 `null`）—— 见 [liquidGlassImage] 的同名参数。 */
        shrinkOverride: Int? = null,
    ): BufferedImage? {
        // ★ 清缓存 ⇒ 确保**真的重建**（否则 key 相同时会直接返回上一张）。
        cachedLiquid = null
        cachedLiquidKey = ""
        return liquidGlassImage(
            w, h, cornerRadius, roundness, tintPercent,
            Color((baseColorArgb ushr 16) and 0xFF, (baseColorArgb ushr 8) and 0xFF, baseColorArgb and 0xFF),
            endArgb, glareAngleDeg, glarePercent, depthEffect,
            animating = animating,
            shrinkOverride = shrinkOverride,
        )
    }

    /**
     * ★★★ 探针专用：**保留缓存**地取一张液态玻璃位图（2026-10-03）。
     *
     * ## 为什么需要它（与 [liquidGlassImageForProbe] 的唯一区别）
     *
     * 后者每次**先清缓存** ⇒ 它只能测"画出来长什么样"，**测不出缓存键**。
     * 而"**缓存键漏一项**"是**静默**缺陷：用户改了那一项 ⇒ 键没变 ⇒ **命中旧图**
     * ⇒ 表现与"设置项无效"**完全一样**。
     * ★ 现场：`cornerRadiusBottom`（2026-10-02 T5 加的）**没进缓存键** ⇒
     *   改「下边圆角」（`NOTCH` 的下边）时玻璃**不重绘**。
     *
     * ⇒ 本入口**不清缓存**，于是可以断言：
     * · 同参数连调两次 ⇒ **返回同一个对象**（`===`，证明真的命中）；
     * · **只改某一项** ⇒ 必须**不是**同一个对象（证明那一项在键里）。
     */
    fun liquidGlassImageCachedForProbe(
        w: Int, h: Int,
        cornerRadius: Double,
        cornerRadiusBottom: Double,
        roundness: Double,
        tintPercent: Int,
        baseColorArgb: Int,
        endArgb: Int,
        glareAngleDeg: Double,
        glarePercent: Int,
        depthEffect: Boolean,
        animating: Boolean,
    ): BufferedImage? = liquidGlassImage(
        w, h, cornerRadius, roundness, tintPercent,
        Color((baseColorArgb ushr 16) and 0xFF, (baseColorArgb ushr 8) and 0xFF, baseColorArgb and 0xFF),
        endArgb, glareAngleDeg, glarePercent, depthEffect,
        animating = animating,
        cornerRadiusBottom = cornerRadiusBottom,
    )

    /**
     * ★★ **④a「液态玻璃」档**（2026-09-29，用户指令「开 ④a」）。
     *
     * ## 观感定义（`recon/68` §2.2）
     *
     * 「液态玻璃」相对"线性渐变"的差别是**由内向外**：
     *   ① **内部柔化** —— 中心柔亮，向外渐暗（让岛体"有厚度"而不是一块平色）；
     *   ② **边缘亮环** —— 最外缘一圈**回亮**（模拟玻璃边缘的折射高光）。
     *
     * ## 实现要点
     *
     * - 用 [RadialGradientPaint]，**4 个色标**：`0 / 0.55 / 0.88 / 1.0`。
     *   ★ 前两个色标管"内部柔化"，后两个管"边缘亮环"。
     * - ★ **与 ⑨（边框向外渐变）共用同一个 `RadialGradientPaint` 机制** ——
     *   这正是 `recon/68` 说的"两项共用掩膜管线"。
     * - ★ **不缓存**（与 [gradientPaint] 的 1 槽缓存不同）：本档的键多了 2 个色标，
     *   缓存收益 < 复杂度；且 `RadialGradientPaint` 的构造实测**远低于**一次 `fill`
     *   （`B §A-4.1`：线性渐变构造只占"构造+fill"的 **0.09%**）。
     *   ⚠️ 若真机实测发现它是热点，再加缓存 —— **不要预先优化**。
     *
     * @param tintPercent 渐变强度（0..100）。与 `LINEAR` 同一语义：越大 → 与止色越接近。
     */
    private fun liquidPaint(
        x: Double, y: Double, w: Double, h: Double,
        tintPercent: Int, baseColor: Color, endArgb: Int,
    ): RadialGradientPaint {
        val cx = (x + w / 2.0).toFloat()
        val cy = (y + h / 2.0).toFloat()
        // 半径取"能盖住整个形状"的值（对角线一半）—— 否则四角会出现渐变截断。
        val radius = (Math.hypot(w, h) / 2.0).toFloat().coerceAtLeast(1f)
        val t = (tintPercent.coerceIn(0, 100)) / 100f
        val end = Color((endArgb ushr 16) and 0xFF, (endArgb ushr 8) and 0xFF, endArgb and 0xFF, baseColor.alpha)

        fun mix(f: Float): Color = Color(
            (baseColor.red + (end.red - baseColor.red) * f).toInt().coerceIn(0, 255),
            (baseColor.green + (end.green - baseColor.green) * f).toInt().coerceIn(0, 255),
            (baseColor.blue + (end.blue - baseColor.blue) * f).toInt().coerceIn(0, 255),
            baseColor.alpha,
        )

        // ① 中心：底色（不偏）
        // ② 0.55：向止色偏一点 ⇒ **内部柔化**
        // ③ 0.88：回到接近底色 ⇒ 让"亮环"与内部**分离**（否则整个边缘一起亮，看不出环）
        // ④ 1.0：明显偏向**亮**侧 ⇒ **边缘亮环**
        val colors = arrayOf(
            baseColor,
            mix(0.55f * t),
            mix(0.20f * t),
            mix(1.0f * t),
        )
        return RadialGradientPaint(
            java.awt.geom.Point2D.Float(cx, cy),
            radius,
            java.awt.geom.Point2D.Float(cx, cy),
            floatArrayOf(0f, 0.55f, 0.88f, 1f),
            colors,
            java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
    }

    /**
     * 背景渐变的笔刷（**带 1 槽缓存**）。
     *
     * ## 几何（★ 含必须写进注释的反例）
     *
     * 以形状**中心**为原点，方向 `(cosθ, sinθ)`，投影长度
     * `L = |w·cosθ| + |h·sinθ|`，两端点 = 中心 ∓ `(L/2)·(cosθ, sinθ)`。
     *
     * > ★ **反例**：若图省事直接用 `(x, y) → (x+w, y+h)` 当端点，
     * > 则 **45° 时四个角会落到渐变区间之外** ⇒ 角上出现未覆盖/钳制色块。
     * > **"外接盒盖住" ≠ "矩形盖住"** —— 本项目已用 `1×1 → 1×1 @45°` 一个反例推翻过同类推理。
     *
     * ## 角度定义（**必须写进 KDoc，否则下一个人会改反**）
     *
     * `0°` = 从左到右，`90°` = 从上到下，**顺时针**。
     *
     * ## 止色
     *
     * `gradient_tint_percent` 是**起色向止色混合的比例**（用户 2026-09-25 拍板"显式止色"后
     * 由本文件定义的语义）：`0` ⇒ 不画（调用方已挡）；`100` ⇒ 完全取止色。
     * 实际两端色 = `起色` 与 `mix(起色, 止色, tint/100)`。
     */
    private fun gradientPaint(
        x: Double, y: Double, w: Double, h: Double,
        angleDeg: Int, tintPercent: Int, baseColor: Color, endArgb: Int,
    ): LinearGradientPaint {
        val key = "$x|$y|$w|$h|$angleDeg|$tintPercent|${baseColor.rgb}|$endArgb"
        cachedGradient?.let { if (cachedKey == key) return it }

        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = Math.cos(rad)
        val dy = Math.sin(rad)
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val l = Math.abs(w * dx) + Math.abs(h * dy)
        val half = l / 2.0
        val from = Color(baseColor.red, baseColor.green, baseColor.blue, baseColor.alpha)
        val end = Color(
            (endArgb ushr 16) and 0xFF,
            (endArgb ushr 8) and 0xFF,
            endArgb and 0xFF,
            baseColor.alpha,
        )
        val t = (tintPercent.coerceIn(0, 100)) / 100f
        val to = Color(
            (from.red + (end.red - from.red) * t).toInt().coerceIn(0, 255),
            (from.green + (end.green - from.green) * t).toInt().coerceIn(0, 255),
            (from.blue + (end.blue - from.blue) * t).toInt().coerceIn(0, 255),
            from.alpha,
        )
        val p = LinearGradientPaint(
            (cx - half * dx).toFloat(), (cy - half * dy).toFloat(),
            (cx + half * dx).toFloat(), (cy + half * dy).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(from, to),
        )
        cachedGradient = p
        cachedKey = key
        return p
    }
}
