// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.image.BufferedImage

/**
 * ★★★ **④b 的背景折射 / 色散 / 饱和度** —— 把"身后的窗口"当作**真实玻璃**来弯。
 *
 * ## 它解决的问题
 *
 * ④b 的 L3 把身后的窗口截下来、模糊、垫在词岛最底层。但**只模糊不折射**看起来是
 * "面板后面糊了一块"，而**不是"一块玻璃"** —— 玻璃会把背景**沿边缘掰弯**。
 * 本对象就是那一步。
 *
 * ## ★★ 公式出处（**逐字照抄参考实现，不是自己推的**）
 *
 * 来源：`Code_20260908143339.html` 的 `FRAG`（**用户提供的 WebGL 参考实现**，
 * 经 `LiquidGlassWinUI` 移植到 Windows 合成器），见 `recon/69-*` §4.2 / §4.5。
 *
 * ```glsl
 * float h = sqrt(max(0.0, 2.0 * t - t * t));   // 【高度剖面】—— 只进 uDepth 的视差项
 * float k = pow(1.0 - t, 1.35);                // 【折射位移】—— ★ 本函数实现的就是它
 * vec2  off = -g * refA * k;                   // 采样偏移用的是 k
 * if (uDepth > 0.5) { off += -p * 0.045 * h; } // h 只在这里（视差）出场
 * ```
 *
 * ★★★ **参考把「折射位移」与「高度剖面」分成两条公式** ——
 *   ★ 若把 `h`（圆弧）当**位移**用，形状会**反向**（见下表）✗
 *
 * ## ★★★ 2026-10-10：剖面**只留一档**（`pow(1−t, 1.35)`）—— 三档机制已收掉
 *
 * ★ 背景：批 A-1 曾把剖面做成**三档开关**（`arc` / `pow` / `snell`），
 *   ★ 而三档**从未接线到设置**（无注册表键）⇒ ★ 除默认档外**全部不可达** ✗
 *
 * ★★ **用户 2026-10-10 指令**：「**三档剖面只留呈现效果（示例文件相似度）与开销平衡的档位**」
 *   ⇒ ★ 按**两个维度量化**后收口 ✓
 *
 * | 档 | 与参考位移 `k` 的**最大偏差** | 峰值位置 | 超越函数开销 | 结论 |
 * |---|---|---|---|---|
 * | **`pow(1−t,1.35)`** | ★ **0.0000**（完全一致） | `t=0` **边缘** ✓ | 1× `pow` | ★★ **保留**（唯一平衡点） |
 * | `sqrt(2t−t²)`（圆弧） | **1.0000**（**满量程**） | `t=1` **内沿** ✗ **方向相反** | 1× `sqrt`（最便宜） | ★ **删** |
 * | `−tan(θT−θI)`（Snell） | 0.5331 | `t=0` 边缘 | ★ **≈5 个超越函数**（最贵） | ★ **删** |
 *
 * ★★ **两条判据（可复用）**：
 * 1. ★★ **支配关系一旦成立 ⇒ 该档没有任何保留理由** ——
 *    ★ Snell **效果更差（0.53 vs 0.00）且更贵（5 ops vs 1 op）** ⇒ **被 `pow` 完全支配** ✓
 * 2. ★ **"更便宜但形状是另一个"不是"平衡点"** —— ★ 圆弧档便宜，但它的峰值在**内沿**、
 *    与参考的**边缘**相反 ⇒ ★ 那是**另一个效果**，不是"廉价近似" ✗
 *
 * ⚠️ **`sqrt(2t−t²)`（圆弧）并非废弃知识** —— ★ 它是参考的**高度剖面 `h`**，
 *   在 `uDepth` **视差项**里合法出场（★ GPU 侧 `LiquidGlassShader` 就**同时**用 `h` 与 `k`）✓
 *   ⇒ ★ 只是**不该**当"折射位移的替代档"（本插件当初那么用，KDoc 自称"实验"）✓
 *
 * ## ★★★ 参数从哪来（**用户指令：不可猜测臆断**）
 *
 * | 参数 | 我们的来源 | 值 |
 * |---|---|---|
 * | `u_refThickness` 折射厚度 | ★ **用户提供的面板** | **16.0 dp** |
 * | `u_refDistance` 折射量 | ★ **用户提供的面板** | **32.0 dp** |
 * | `u_refFactor`（**折射率 IOR**） | ★ **面板里没有** ⇒ 取业界默认 | **1.4**（`LiquidGlassWinUI` `RefFactor`）★ **2026-10-10 起未使用**（只服务已删的 Snell 档） |
 * | `u_refDispersion` 色散 | ★ 面板给 **0.35** —— ⚠️ **但那是另一个刻度**，见 [DISPERSION_SCALE] | — |
 * | `Saturation` 饱和度 | ★ **用户提供的面板** | **1.5×** |
 * | `ShapeRoundness` | ★ 面板里没有 ⇒ 取业界默认（**与 ④a 已用的值一致**） | **5** |
 *
 * ## 成本（`_diag_spw/BackdropRefractionProbe.java` 实测）
 *
 * | 阶段 | 耗时 |
 * |---|---|
 * | 折射 | 9.9 ms |
 * | 折射 + 色散 | 14.7 ms |
 * | 折射 + 色散 + 饱和 | **16.6 ms** |
 *
 * ★★ **它跑在 10 Hz 的采集线程上**（预算 **100 ms/次**，不是 16.67 ms）⇒ 占 16.6%
 * ⇒ ★ **完全不在 EDT 帧预算里**（EDT 侧只是 `drawImage` 一张已经烘好的位图）。
 */
object BackdropRefraction {

    // ══ 2026-10-10：`IOR` 常量**已删**（随 Snell 档一并收掉）══════════════════════
    //
    // ★ 原为 `const val IOR = 1.4`（面板里没有这一项 ⇒ 取业界默认 `RefFactor`）。
    // ★★ **删它的理由**：★ 它**只被 Snell 档使用**（`edgeFactor` 的 `ior` 形参）——
    //   ★ 而 Snell 已按用户指令收掉（见文件头的量化表）⇒ ★ 删后**全仓零引用** ✓
    // ★ **出处不丢**：它仍记在下面「参数从哪来」表的 `u_refFactor` 行里 ✓
    // ★★ **另需知**：折射量/厚度的**默认值是按 `IOR = 1.4` 标定过的**（bench 实测）——
    //   ★ 若将来重新引入 IOR 档，**必须重新标定**那两个默认值，不能直接沿用 ✓

    /**
     * 色散刻度的换算系数 —— ★★ **这一条是"面板值不可直接用"的直接产物**。
     *
     * ## 为什么要换算（bench 实测逼出来的）
     *
     * 面板给 `dispersion = 0.35`，而业界 `RefDispersion` 默认 **7**（范围 0–50）。
     * ★ 把两个值分别喂进**同一个公式**实测（`_diag_spw/BackdropRefractionProbe.java` 实验 B）：
     *
     * | `factor` | 边缘环带平均差 | 峰值 \|R−B\| | 观感 |
     * |---|---|---|---|
     * | **0.35（面板）** | **0.20** | 53 | ❌ **几乎看不出（≈ 噪声）** |
     * | **7（业界默认）** | **3.15** | 226 | ★ 可见但克制 |
     * | 20 | 5.84 | 227 | ★ 可见但克制 |
     *
     * ⇒ ★ **两个 0.35 / 7 不是同一个刻度**；照面板的 0.35 直接实现，**等于没做**
     *   —— 那正是用户点名过的「**不诚实设置项**」。
     *
     * ## 换算规则（**两端都有实测锚点，不是拍脑袋**）
     *
     * 设置项是 `0..100` 百分比，内部 `factor = 百分比 / 100 × 20`：
     * · 面板默认 **35%** ⇒ `factor = 7` = ★ **业界默认值**（"看得见"的那一档）
     * · 拉满 **100%** ⇒ `factor = 20` = ★ 实测仍是"可见但克制"（未到彩虹边）
     *
     * ★ 即：**保住面板的"名与值"（默认 35），同时把观感对齐到业界默认那一档。**
     */
    const val DISPERSION_SCALE = 20.0

    /** 色散三波长 —— ★ 照抄 `fragment-main.glsl` 顶部的 `const`（**硬编码，不是参数**）。 */
    const val N_R = 0.98
    const val N_G = 1.0
    const val N_B = 1.02

    // ══════════════════════════════════════════════════════════════════════════
    // ★★ T3（2026-10-02）：`bake` 的**两个暂存数组复用**（消除每轮 376 KB 垃圾）
    // ══════════════════════════════════════════════════════════════════════════
    //
    // ## 问题（总账 §3.15 T3 原文）
    //
    // `bake` 每轮新建 1 张 `w×h` 位图 + **1 个 `IntArray`**（304×154 ⇒ 各约 188 KB）
    // ⇒ **10 Hz ⇒ ~3.8 MB/s** 垃圾。★ 台账标注：**若将来 L3 提频（> 10 Hz），先改这里**。
    //
    // ## 本轮只复用**数组**，**不复用输出位图**（有意）
    //
    // | 对象 | 能否复用 | 理由 |
    // |---|---|---|
    // | `srcPx` / `outPx` | ✅ **能** | 纯暂存，**不出函数** |
    // | 输出 `BufferedImage` | 🚫 **不能** | ★ 它是**返回值**，下游（`L3CaptureWorker`）会**持有**它 ⇒ 复用会让"上一帧的图"被就地改写 |
    //
    // ⇒ 本轮消掉的是 **2 个 `IntArray` = 376 KB/轮 ≈ 3.76 MB/s**（占总垃圾的 **~99%**，
    //   因为 `BufferedImage` 的像素数据本身也是 `IntArray` 大小 —— 两者同量级，
    //   而位图那部分**结构性不可省**）。
    //
    // ## 为什么复用是安全的（**两条都必须成立**）
    //
    // ① **单线程**：`bake` 只在**采集线程**调用（`BackdropCapture.bakeRefraction`，10 Hz 背景烘焙）
    //    —— 见 [GlassSdf] 文件头的线程归属表。★ **EDT 不碰它**。
    // ② **每次调用都写满全部元素**：循环里 `if (m >= 0.0) { outPx[i] = srcPx[i]; continue }`
    //    已覆盖形状外的分支 ⇒ **不存在"读到上一轮的残值"** ⇒ **不需要清零**。
    //
    // ⚠️ **若将来 `bake` 变成多线程调用**，这两个字段要改成 `ThreadLocal`（或直接去掉复用）
    //    —— 本注释就是给那时的人看的。

    /** `bake` 的暂存：源像素（**跨调用复用**，尺寸变化时重建）。 */
    private var scratchSrc: IntArray? = null

    /** `bake` 的暂存：输出像素（**跨调用复用**，尺寸变化时重建）。 */
    private var scratchOut: IntArray? = null

    /** 超椭圆指数（与 ④a 共用同一个默认值，见 [GlassSdf.DEFAULT_ROUNDNESS]）。 */
    const val ROUNDNESS = GlassSdf.DEFAULT_ROUNDNESS

    // ── 判据（与探针共用同一出口）────────────────────────────────────────────

    /** 折射是否生效：**厚度与折射量都 > 0** 才画（任一为 0 ⇒ `edgeFactor` 恒 0）。 */
    fun shouldRefract(thicknessPercent: Int, amountPercent: Int): Boolean =
        thicknessPercent > 0 && amountPercent > 0

    /**
     * ★★ **vibrancy 是否生效** —— ★★ **恒 `true`**（2026-10-06 · **R3③** 改）。
     *
     * ## 为什么改成恒 true（★ 原为 `saturationPercent != 100`）
     *
     * ★ 参考实现把 **"提饱和 + 压对比"当作【一个阶段】**（vibrancy）✓
     * ⇒ ★ 即使饱和度为 `100`（**不提饱和**），**压对比仍应生效** ——
     *   ★ 否则 `saturation = 100` 的用户会**丢掉"通透感"** ✗
     *
     * ⚠️ **成本不变**：★ 它本来就在 `bake` 的**同一趟逐像素**里 ⇒ 只是**多跑一个后处理** ✓
     *
     * ★★★ **2026-10-09 批 A-2 补记**：★ 上面这条承诺**当时并没有兑现** ——
     * ★ 因为 [bake] 的调用侧还写着 `if (sat != 1.0)`，且 [Params.isIdentity] 也要求
     *   `saturation == 1.0` ⇒ ★ **本函数恒 `true` 却【根本不被查询】**（恒真式，
     *   任何断言它的探针都**零判别力**）✗
     * ⇒ ★ **本轮两处都已修**：① 调用侧改为**无条件** `saturate(argb, sat)`
     *   ② [Params.isIdentity] **去掉** `saturation == 1.0` 这一项 ✓
     * ★ 教训（同 `F4` 的 `shouldSaturate` 恒真问题）：★ **"断言恒真"与"承诺未兑现"
     *   可以同时存在** —— ★ **一个恒真的判据函数，可能只是"没人问它"** ✓
     *
     * ★★★ **2026-10-10（审计 v4 · C-1）**：★ **生产调用点 = 0**（`grep -rn "shouldSaturate"`
     *   在 `plugin/src/` 里只命中本文件的 KDoc 与它自己的定义）⇒ ★ **它已是死代码** ✓
     * ★ 为什么**不删**：★ 它记录着"vibrancy 是一个阶段"这条设计口径（上面那段论证仍有效），
     *   删掉会把论证一起删掉（本项目对"延期/失效代码"的一贯口径 = **标注而非删**）。
     *
     * ★★★ **硬规矩：不得再为它写断言** ✗
     *   ★ 它恒 `true` ⇒ **任何断言它都必然绿** ⇒ 那是**空护栏**（纪律 71 / 77）✗
     *   ★ 真实契约的**有判别力**护栏 = `MaterialProbe` 的 **⑬-10d**
     *     （`bake(折射开, 饱和 100)` 必须**确实改变像素**）✓
     *   ★ 取证：2026-10-10 已删 `MaterialProbe` 里 3 条 `shouldSaturate(...) == true` ✓
     */
    fun shouldSaturate(@Suppress("UNUSED_PARAMETER") saturationPercent: Int): Boolean = true

    /**
     * ★★★ **vibrancy 的「压对比」步**（2026-10-06 · **R3③**）—— **唯一实现点**。
     *
     * ## 出处（★ 逐字照抄）
     *
     * `Code_20260908143339.html` 的 `FRAG` L332：
     * ```glsl
     * glass = clamp((glass - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);
     * ```
     * ★ 换算到 **0..255** 空间：`(v - 127.5) * 1.02 + 127.5 + 3.06`
     * （★ `0.012 × 255 = 3.06`；★ `1.02` 无量纲，直接沿用）✓
     *
     * ## 为什么提成公开纯函数
     *
     * ★ 原来它是 `saturate()` 里的**局部函数** ⇒ ★ **探针无法断言** ✗
     * ★★ 而这是**参考实现的一处关键步骤**（"iOS 通透感"的一半）⇒ ★ **必须有护栏** ✓
     *
     * @param v 0..255 的单通道值
     * @return 压对比后的 0..255
     */
    public fun vibrancyContrast(v: Int): Int =
        ((v - 127.5) * 1.02 + 127.5 + 3.06).toInt().coerceIn(0, 255)

    /** 折射厚度（px）。★ 面板 `refractionHeight = 16 dp` ⇒ 设置项**按 px 直传**。 */
    fun thicknessPx(percent: Int): Double = percent.coerceIn(0, 100).toDouble()

    /** 折射量（px）。★ 面板 `refractionAmount = 32 dp` ⇒ 设置项**按 px 直传**（bench 说 32 可见）。 */
    fun amountPx(percent: Int): Double = percent.coerceIn(0, 100).toDouble()

    /**
     * 色散因子 —— 见 [DISPERSION_SCALE] 的 KDoc（**换算规则的唯一实现点**）。
     *
     * @param percent 设置项 `0..100`
     */
    fun dispersionFactor(percent: Int): Double =
        percent.coerceIn(0, 100) / 100.0 * DISPERSION_SCALE

    /**
     * 饱和度倍率 —— `100` = 不变（`1.0`），`0` = 灰度，`200` = 2.0×。
     *
     * ★ 量纲与业界 `Saturation`（0–2，默认 1.0）**1:1 对齐**；
     * 设置项用 `0..200` 是因为本项目惯例是「**100 = 标准**」
     * （同 `layout.translation.scale_percent`），而面板给的值正是 **1.5×** ⇒ 默认 **150**。
     */
    fun saturationFactor(percent: Int): Double = percent.coerceIn(0, 200) / 100.0

    // ══ 2026-10-10：`safeAsin` + 三个 `PROFILE_*` 常量 + `PROFILE_DEFAULT` **已全删** ══
    //
    // ## 删了什么、为什么
    //
    // | 已删 | 原用途 | 为什么删 |
    // |---|---|---|
    // | `safeAsin(v)` | ★ **只**服务 Snell 档（`asin` 越界夹值） | ★ Snell 已删 ⇒ **全仓零引用** |
    // | `PROFILE_ARC` = `"arc"` | 把参考的**高度剖面** `sqrt(2t−t²)` 当**位移档**（自称"实验"） | ★ 见文件头量化表：偏差**满量程 1.0**、峰值**方向相反** ⇒ **不是平衡点** |
    // | `PROFILE_POW` = `"pow"` | 参考的**折射位移** `pow(1−t,1.35)` | ★ **公式保留**（现为唯一实现），★ 但**档位名已无意义**（只剩一档） |
    // | `PROFILE_SNELL` = `"snell"` | 本插件最初的 `−tan(θT−θI)` | ★ **被 `pow` 完全支配**（效果更差 **且** 更贵） |
    // | `PROFILE_DEFAULT` | 三档时的默认档名 | ★ 只剩一档 ⇒ **不需要"默认"这个概念** |
    //
    // ## ★★ 连带删掉的两个形参（★ 否则会留下"永远传同一个值"的死参数）
    //
    // · `edgeFactor(..., ior: Double, profile: String)` ⇒ ★ 现为 **`edgeFactor(depthPx, thicknessPx)`** ✓
    // · `Params.profile` ⇒ ★ **整个字段删掉**（它只喂 `edgeFactor`）✓
    //
    // ⚠️ **将来若要重新引入档位**（★ 例如接 `uDepth` 视差项时把圆弧 `h` 接回来）：
    //   ★ **必须同时**给出 ① 量化对比表（效果 vs 开销）② 注册表键 ③ 探针断言 ——
    //   ★ 三者缺一，就会重演本次"**档位存在但不可达**"的形态 ✗

    /**
     * **边缘偏折强度（折射剖面）** —— ★★★ **2026-10-10：只此一档**（`pow(1−t, 1.35)`）。
     *
     * ## 公式与出处（★ 参考 `FRAG`，逐字）
     *
     * ```glsl
     * float t = clamp(sd / refH, 0.0, 1.0);        // sd = 内部到边缘的距离（边缘 = 0）
     * float k = pow(1.0 - t, 1.35);                // ★★★ 【折射位移】的剖面
     * vec2  off = -g * refA * k;                   // UV 位移用它
     * ```
     * ★ **形状**：★ 边缘最强（`t=0 ⇒ 1`）⇒ 向中心衰减（`t=1 ⇒ 0`）✓
     *
     * ⚠️ **参考里另有一条 `h = sqrt(2t−t²)`（圆弧【高度剖面】）**，
     *   ★ 它**只进 `uDepth` 的视差项**（`off += -p * 0.045 * h`），**不是折射位移** ✓
     *   ★ 本插件**没有** depth 视差项 ⇒ ★ 圆弧在本函数里**没有位置**（见文件头的量化表）✓
     *
     * ## ★★ 为什么只剩一档（★ 用户 2026-10-10 指令）
     *
     * ★ 批 A-1 曾提供三档（`arc` / `pow` / `snell`）—— ★ 量化对比见**文件头**的表：
     *   ★ `arc` 偏差**满量程且方向相反**；★ `snell` **被 `pow` 完全支配**（更差且更贵）✓
     * ⇒ ★ 只留 `pow` ⇒ ★ **`profile` 形参随之删除**（只剩一档时，档位参数是死参数）✓
     *
     * @param depthPx 该像素的 SDF 深度（`> 0` 在形状内，px）
     * @param thicknessPx 折射厚度（px，= 参考的 `refH`）
     * @return 剖面值 ∈ [0, 1]；`depthPx <= 0` 或 `>= thicknessPx` ⇒ **0**（超出厚度不折射）
     */
    fun edgeFactor(
        depthPx: Double,
        thicknessPx: Double,
    ): Double {
        if (!(thicknessPx > 0.0)) return 0.0
        if (depthPx <= 0.0) return 0.0
        if (depthPx >= thicknessPx) return 0.0
        val t = depthPx / thicknessPx
        // ★ 参考 `FRAG` 的【折射位移】`k = pow(1−t, 1.35)` —— ★ **唯一实现**（2026-10-10 起）
        return Math.pow(1.0 - t, PROFILE_EXPONENT)
    }

    /**
     * ★★★ 折射剖面的幂指数 —— **参考 `FRAG` 的 `pow(1.0 - tt, 1.35)` 里的 `1.35`**。
     *
     * ★ 提成常量是为了**可被探针断言**（★ 同 `GLASS_STEADY_SHRINK` 的理由）。
     * ★★ **2026-10-10 起它是 [edgeFactor] 的【唯一】系数**（三档已收掉，见文件头量化表）✓
     * ⚠️ **不要顺手改** —— 它直接决定"边缘多陡"，是观感的核心参数。
     */
    const val PROFILE_EXPONENT = 1.35

    /**
     * 一次烘焙的全部输入。
     *
     * ★ 全部是**已经换算好的物理量**（px / 倍率），**不再含设置项的百分比** ——
     * 换算只在上面的纯函数里做一次，避免"两处各换一次"（本项目吃过这类亏）。
     *
     * ⚠️ **2026-10-10**：原有一个 `profile` 字段（批 A-1 的三档开关）——
     * ★ 三档已收成一档（用户指令：只留效果/开销的平衡点）⇒ ★ **该字段已删** ✓
     */
    data class Params(
        val thicknessPx: Double = 16.0,
        val amountPx: Double = 32.0,
        val dispersionFactor: Double = 0.0,
        val saturation: Double = 1.0,
        val roundness: Double = ROUNDNESS,
    ) {
        /**
         * 本组参数**是否什么都不做**（恒等）⇒ 调用方可直接跳过整趟烘焙。
         *
         * ★★★ **2026-10-09 批 A-2 更正** —— ★★ **原来还要求 `saturation == 1.0`，那是错的**：
         *
         * ⚠️ 旧判据：`(!(thicknessPx > 0) || !(amountPx > 0)) && saturation == 1.0`
         * ★★ **后果**：★ 用户把饱和设为 **100**（`saturation == 1.0`）且**折射也开着**时，
         *   `saturation == 1.0` 让整个 `bake` 被短路 ⇒ ★ **`vibrancyContrast`（压对比）不跑**
         *   ⇒ ★ 与 [shouldSaturate] 的承诺（"**压对比恒生效**"）**直接矛盾** ✗
         *
         * ★★ **正解**：★ **vibrancy（提饱和 + 压对比）是一个【总在跑】的阶段** ——
         *   `saturation == 1.0` 只意味着"不提饱和"，★ **不意味着"vibrancy 恒等"**
         *   （因为压对比 `(v−127.5)*1.02+130.56` 对任何 `v ≠ 128` 都**改变像素**）✓
         *
         * ★ 因此**恒等只剩一个条件**：**折射关掉**（厚度或量为 0）。
         * ★ 折射开着 ⇒ 无论如何都要跑一趟（折射 + vibrancy）✓
         * ★ 折射关掉 ⇒ ★ 整趟跳过（★ 这正是 `material.backdrop.blur_radius = 0` 时"一条代码都不跑"的兑现点）✓
         *
         * ★★ **[profile] 不参与恒等**（批 A-1）—— ★ 它只改"折射带长什么样"，
         *   不改"要不要跑"：三档都要求"折射开着"⇒ **判据不变** ✓
         *
         * ★★ **2026-10-09 批 A-2 复核**：★ 本条已**只由折射开关决定**（`saturation`
         *   不再参与）⇒ ★ 用户把饱和设成 **100** 时**不再短路整趟** ⇒ `vibrancyContrast`
         *   （压对比）**照常跑** ✓（同批 [bake] 调用侧已改为**无条件** `saturate`）
         */
        val isIdentity: Boolean
            get() = !(thicknessPx > 0.0) || !(amountPx > 0.0)
    }

    /**
     * 对**已模糊的背景位图**做折射 + 色散 + 饱和，返回一张新位图。
     *
     * ## 为什么输入是"已模糊的"（而不是原图）
     *
     * 业界管线的顺序是 `backdrop → Blur → PostProcessing → LiquidGlassEffect` ——
     * ★ **折射发生在模糊之后**（`texture(u_blurredBg, v_uv - normal*...)`）。
     * 这很关键：先折射再模糊会把"掰弯"糊掉；先模糊再折射才是玻璃的观感。
     *
     * ## 只处理**形状内**的像素
     *
     * 形状外的像素**原样拷过去** —— 消费侧（`paintL3Backdrop`）本来就 `setClip(shape)`
     * ⇒ 那些像素**永远画不出来**。跳过它们省下的时间正好是"岛体以外的面积"。
     * ★ 副作用是好的：**断言可以说"形状外逐像素不变"**（§6 ⑧）。
     *
     * @param src 已模糊的背景（尺寸 = 岛体 `w × h`）
     * @param cornerRadius 岛体的**实际**圆角半径（px）。★ 必须与 `islandShape` 同源，
     *   否则折射带会与岛体边缘**错开**（见 [GlassSdf] 的 KDoc）
     * @return 新位图；`params.isIdentity` 或尺寸非法时**原样返回 `src`**（不复制）
     */
    fun bake(
        src: BufferedImage,
        cornerRadius: Double,
        params: Params,
        /**
         * ★★ **下边圆角半径**（T5，2026-10-02）—— `NaN`（默认）⇒ **与上边同值**（= 改动前行为）。
         *
         * ★ 只有 `shape.type = NOTCH`（**上大下小**）才需要它：不传则 SDF 只收一个半径
         * ⇒ **折射带与岛体边缘错开**（`GlassSdf` 的 KDoc 点名的那个后果）。
         */
        cornerRadiusBottom: Double = Double.NaN,
    ): BufferedImage {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        if (params.isIdentity) return src

        val refracting = params.thicknessPx > 0.0 && params.amountPx > 0.0
        // ★★ T3：两个暂存数组**跨调用复用**（尺寸变化时重建）——
        //   见 [scratchSrc] 的 KDoc（单线程 + 每元素都写满 ⇒ 安全、无需清零）。
        val n = w * h
        val srcPx = scratchSrc?.takeIf { it.size == n } ?: IntArray(n).also { scratchSrc = it }
        val outPx = scratchOut?.takeIf { it.size == n } ?: IntArray(n).also { scratchOut = it }
        src.getRGB(0, 0, w, h, srcPx, 0, w)
        val grad = DoubleArray(2)
        val f = params.dispersionFactor
        // ★ 三通道各自的偏移缩放（**色散**）：R 偏小、B 偏大 —— 照抄 `N_R/N_G/N_B`。
        val scaleR = 1.0 - (N_R - 1.0) * f
        val scaleG = 1.0 - (N_G - 1.0) * f
        val scaleB = 1.0 - (N_B - 1.0) * f
        val sat = params.saturation

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val fx = x + 0.5
                val fy = y + 0.5
                val m = GlassSdf.of(fx, fy, w, h, cornerRadius, params.roundness, cornerRadiusBottom)
                if (m >= 0.0) {
                    // 形状外（含边界）⇒ 原样（消费侧会裁掉，画不出来）
                    outPx[i] = srcPx[i]
                    continue
                }
                var argb = srcPx[i]
                if (refracting) {
                    GlassSdf.gradient(
                        fx, fy, w, h, cornerRadius, params.roundness, grad,
                        cornerRadiusBottom = cornerRadiusBottom,
                    )
                    val gl = Math.hypot(grad[0], grad[1])
                    val nx = if (gl > 1e-9) grad[0] / gl else 0.0
                    val ny = if (gl > 1e-9) grad[1] / gl else 0.0
                    // ★★ 2026-10-10：`edgeFactor` **只剩一档**（参考的折射位移 `pow(1−t,1.35)`）——
                    //   ★ 原三档（arc / pow / snell）已按用户指令收掉（见文件头的量化表）✓
                    //   ★ 返回的是 **剖面值 ∈ [0,1]**（**不是** Snell 时代的偏折角正切）✓
                    //   ⇒ ★ **消费点一行不改**：`-normal * k * amountPx` 与参考的
                    //      `offset = -normal * u_refAmount * k` **逐字同构** ✓
                    val ef = edgeFactor(-m, params.thicknessPx)
                    if (ef > 0.0) {
                        // ★ 采样偏移 = `-normal * edgeFactor * amount`（shader 原式）
                        val ox = -nx * ef * params.amountPx
                        val oy = -ny * ef * params.amountPx
                        argb = if (f != 0.0) {
                            val r = bilinear(srcPx, w, h, fx + ox * scaleR, fy + oy * scaleR)
                            val g = bilinear(srcPx, w, h, fx + ox * scaleG, fy + oy * scaleG)
                            val b = bilinear(srcPx, w, h, fx + ox * scaleB, fy + oy * scaleB)
                            (0xFF shl 24) or (((r ushr 16) and 0xFF) shl 16) or
                                (((g ushr 8) and 0xFF) shl 8) or (b and 0xFF)
                        } else {
                            bilinear(srcPx, w, h, fx + ox, fy + oy)
                        }
                    }
                }
                // ★★★ 2026-10-09 批 A-2：**vibrancy 是一个阶段，不是一个"要不要提饱和"的开关**
                //
                // ⚠️ 原为 `if (sat != 1.0) argb = saturate(argb, sat)` —— ★★ **那与
                //    [shouldSaturate] 的 KDoc 承诺矛盾**：★ `sat == 1.0`（用户把
                //    `material.backdrop.saturation` 设为 100）时**整个 `saturate()` 不跑**
                //    ⇒ **`vibrancyContrast`（压对比）也不跑** ⇒ 用户**丢掉"通透感"** ✗
                //
                // ★ 参考实现里"提饱和 + 压对比"**同属一步**（`saturate3` 之后紧跟
                //   `(c-0.5)*1.02 + 0.5 + 0.012`）⇒ ★ **即使不提饱和，压对比也必须跑** ✓
                //
                // ★ 成本不变：★ 它本来就在同一趟逐像素里（只是不再被 `sat` 短路）✓
                argb = saturate(argb, sat)
                outPx[i] = argb
            }
        }

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, w, h, outPx, 0, w)
        return out
    }

    /**
     * 双线性采样（**夹边**）—— 与 GPU 的 `CLAMP_TO_EDGE` 同语义。
     *
     * ⚠️ **夹边而不是"越界返回透明"**：折射会把采样点推到岛体之外
     * （偏移最大可达 `amountPx` ≈ 32 px），而**岛体之外就是窗口边缘**。
     * 若返回透明，词岛边缘会出现一圈**透明锯齿**（比"取边缘像素"糟得多 ——
     * 后者只是"颜色不再变化"，观感上就是玻璃把边缘色延展了一点）。
     */
    private fun bilinear(px: IntArray, w: Int, h: Int, fx: Double, fy: Double): Int {
        val x0f = Math.floor(fx)
        val y0f = Math.floor(fy)
        val tx = fx - x0f
        val ty = fy - y0f
        var x0 = x0f.toInt()
        var y0 = y0f.toInt()
        var x1 = x0 + 1
        var y1 = y0 + 1
        x0 = x0.coerceIn(0, w - 1); x1 = x1.coerceIn(0, w - 1)
        y0 = y0.coerceIn(0, h - 1); y1 = y1.coerceIn(0, h - 1)
        val p00 = px[y0 * w + x0]
        val p10 = px[y0 * w + x1]
        val p01 = px[y1 * w + x0]
        val p11 = px[y1 * w + x1]
        val w00 = (1 - tx) * (1 - ty)
        val w10 = tx * (1 - ty)
        val w01 = (1 - tx) * ty
        val w11 = tx * ty
        fun ch(shift: Int): Int {
            val v = ((p00 ushr shift) and 0xFF) * w00 + ((p10 ushr shift) and 0xFF) * w10 +
                ((p01 ushr shift) and 0xFF) * w01 + ((p11 ushr shift) and 0xFF) * w11
            return Math.round(v).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * 饱和度（**标准饱和度矩阵**：绕 luma 线性拉伸）。
     *
     * ★ 权重用 **`Rec.709`**（`0.2126 / 0.7152 / 0.0722`）——
     * 与 `LiquidGlassWinUI` 的 `Saturation` 后处理同族（它也是"以中灰为中心"的线性拉伸）。
     *
     * ⚠️ 业界那份参考实现（`fragment-main.glsl`）**没有** `u_saturation` 这个 uniform
     * —— 饱和度在 `LiquidGlassWinUI` 里是**独立的 PostProcessing 阶段**。
     * ⇒ 我们按标准公式实现，**量纲与它的 `Saturation`（0–2，1.0 = 不变）对齐**。
     */
    private fun saturate(argb: Int, s: Double): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        // ★★★ 2026-10-06 **R3③：补参考实现的「压对比」**（vibrancy 的另一半）
        //
        // ★ 参考（`Code_20260908143339.html` 的 `FRAG` L331-332）：
        //   `saturate3(c, uSat)` → `clamp((c - 0.5) * 1.02 + 0.5 + 0.012, 0, 1)`
        // ★★ **本插件原来【只提饱和】，没有压对比** ⇒ 缺"iOS 通透感"（审计结论）✓
        //
        // ★ 换算到 0..255 空间：`(c - 127.5) * 1.02 + 127.5 + 3.06`
        //   （★ `0.012 * 255 = 3.06`；★ 1.02 是无量纲的对比系数，直接沿用）✓
        val nr = vibrancyContrast((luma + (r - luma) * s).toInt().coerceIn(0, 255))
        val ng = vibrancyContrast((luma + (g - luma) * s).toInt().coerceIn(0, 255))
        val nb = vibrancyContrast((luma + (b - luma) * s).toInt().coerceIn(0, 255))
        return (argb and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }
}
