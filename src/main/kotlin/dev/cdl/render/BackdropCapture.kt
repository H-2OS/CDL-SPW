// SPDX-License-Identifier: AGPL-3.0-only
//
// L3（真透视 / ④b）的**采集侧**：低频采屏 → 裁剪 → 模糊 → 发布为"当前有效背景"。
//
// ## 本文件在 L3 里的位置
//
// L3 一共三块，本文件是**中间那块**：
//
// | 块 | 内容 | 状态 |
// |---|---|---|
// | 判据 | [detectSelfCapture] + [SelfCaptureGate] + [ShapeMaskRaster] | ✅ 2026-09-27 已就绪 |
// | **采集** | **本文件**：采屏 + 裁剪 + 模糊 + 门控降级 | ✅ 本文件 |
// | 消费 | `IslandPanel` 每帧 `drawImage(预烘焙蒙版位图)` | ✅ 见 `IslandWindow` |
//
// ## ★★ 三条硬约束（都来自实测，不是设计偏好）
//
// ### ① 截屏**必须降频**（`migration/20-*` E1 / `25-*` §1）
//
// 实测：`Robot.createScreenCapture` 在 `304×154` 上要 **8.25–8.50 ms**
// （与面积基本无关，但在宽度 496→544 处有**阶跃**到 16.3 ms）。
// 而 60 fps 的帧预算只有 **16.67 ms** ⇒ **每帧采一次会吃掉一半预算**。
//
// ⇒ 本文件用**独立线程 + 固定间隔**（默认 100 ms = 10 Hz），
//   EDT 侧只在"有新帧"时取一次引用（`@Volatile`），**零等待**。
//
// ### ② 蒙版（形状 → 位图）**预烘焙**，绝不每帧重建（`migration/31-*` §1 / E5）
//
// 实测（E5，`304×154`，p50）：
//
// | 路线 | p50 |
// |---|---|
// | **预烘焙蒙版位图 + `drawImage`** | **0.150 ms** |
// | `setClip(Shape)` | 0.186 ms —— 且**圆角恒无抗锯齿**（半透明像素 = 0） |
// | 每帧重建蒙版 + `SRC_IN` | **1.749 ms**（11.7×，且每帧 2 张 ARGB 分配） |
//
// ⇒ 蒙版由**形状变化事件**驱动重烘焙，消费侧只做一次 `drawImage`。
//
// ### ③ 降采样倍率 `ds=4`，不是 8/16（`migration/24-*` §3 / `25-*` §2）
//
// 实测 PSNR（含文字，文字最容易被模糊质量暴露）：
// `ds=4 → 43.19 dB`（安全，> 40）· `ds=8 → 33.99`（临界）· `ds=16 → 30.56`（危险）。
// ⇒ LITE 档 `ds=4`、FULL 档 `ds=1`（与 L2 的 `CoverBackdrop` 同一档位约定）。
//
// ## 与 `CoverBackdrop` 的关系
//
// 模糊**直接复用** [CoverBackdrop] 的 `int[]` 双缓冲可分离 box blur ——
// 那是本项目已验证的实现（`18-*`：每帧 6 次分配 + 12 次整幅搬运 → 进出各一次）。
// 本文件**不重写**模糊（重写 = 造第二份实现，两边一旦不同步就是"糊得不一样"）。
//
// ## 线程模型
//
// ```
//   EDT（paintComponent）          采集线程（Daemon, 10 Hz）
//  ─────────────────────          ──────────────────────────
//  publishOwnFrame(shape,…) ──┐
//                             ├─▶ ownFrame (@Volatile)
//                             │        │
//                             │        ├─ detectSelfCapture(capture, own, mask)
//                             │        ├─ gate.onReading(reading)
//                             │        └─ if accepted → 裁剪+模糊 → backdrop (@Volatile)
//                             │
//  取 backdrop（非阻塞）◀──────┘
// ```
//
// ★ 采集线程**只读** `ownFrame`、**只写** `backdrop`；EDT 反之。
//   两侧都只碰"不可变对象的一次性引用替换" ⇒ **不需要锁**（`@Volatile` 足够）。
//
// ## ⚠️ 本文件**不**包含的（如实登记）
//
// · **真机上 `WDA_EXCLUDEFROMCAPTURE` 失败的实际形态** —— 见 `_blurpaths` 与 `31-*` §6
//   （本文件按"失败时整帧含自己"的上界设计判据，与威胁模型同源）。
// · **`PluginClassLoader` 与宿主 Skiko 的共存** —— 第 4 期实验① 已闭（档 0-C1 可行）。
// · **128 MB 软堆**下能否容纳 —— 第 4 期实验② **未做**。
//   ★ 本文件的采集线程**不持有 Skia 管线**（纯 Java2D）⇒ **不占用该条件的额度**；
//     若将来改走 C1（宿主 Skiko `ImageFilter.makeBlur`），**必须先做实验②**。
package dev.cdl.render

// ⚠️ **一处新的分层边（2026-09-28 登记）**：本文件 import `animation` 包。
//    本项目恒定的方向纪律是 `window/ → render/`（见 `render/OwnFrame.kt` 的 KDoc）。
//    `animation/` 是一个**零依赖的叶子包**（它不 import 任何 `dev.cdl.*`），
//    故 `render → animation` 与既有的 `window → animation` **同向**，
//    **不构成环**（`animation` 不会反过来依赖 `render`）。
//    ★ 若将来有人给 `animation/` 加了 `dev.cdl.*` 的依赖，这条边会立刻变成环 ——
//      改之前先读这里。
import dev.cdl.animation.PerSecondMeter

import java.awt.Rectangle
import java.awt.Shape
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L3 采集侧的**参数**（一次性定，运行期不改）。
 *
 * ## 为什么把参数收进一个值对象而不是散在构造里
 *
 * 因为**判据与渲染必须读同一份**：探针要断言"降采样倍率 = 4"、
 * 渲染要按同一档位决定 `ds`。散成多个 `Int` 形参时，两处极易各传一个值
 * （本项目已犯多次：「同一份数据被两个函数各自收敛」）。
 *
 * @property intervalMs 采屏间隔（ms）。默认 **100**（10 Hz，`31-*` §4.6）。
 *   ⚠️ 小于 **50** 就要重新算预算：截屏 8.25 ms + 判据 2.6 ms + 模糊 5.9 ms ≈ 16.8 ms/次，
 *   50 ms 间隔下占 **34%** 的一个核（采集线程，不占 EDT，但仍是 CPU）。
 * @property downsample 模糊降采样倍率。**默认 4**（`24-*` §3：ds=4 = 43.19 dB 安全）。
 *   `1` = 不降采样（FULL 档，成本 ×2.77）。`8`/`16` 实测临界/危险，**不要用**。
 * @property blurRadius 模糊半径（原分辨率像素，0..40，= `material.backdrop.blur_radius`）。
 *   **`0` ⇒ 本层不启用**（判据：调用方不启动采集线程）。
 * @property highpassRadius S1-hp 半径（透传给 [detectSelfCapture]）。
 */
data class BackdropCaptureParams(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val downsample: Int = DEFAULT_DOWNSAMPLE,
    val blurRadius: Int = 0,
    val highpassRadius: Int = SelfCaptureRules.HIGHPASS_RADIUS,
    /**
     * 岛体**实际**圆角半径（px）—— ★ 折射的 SDF 用它。
     *
     * ⚠️ **必须与 `islandShape` 同源**（`islandOutlineAt` 给出的那条边半径）。
     * 用错会让"背景折射带"与"岛体边缘"**错开几像素**，而那是**看得见**的
     * （一条扭曲带与形状边缘不重合）。见 [GlassSdf] 的 KDoc。
     *
     * `< 0` ⇒ **未知** ⇒ **整趟折射不做**（宁可不动，也不要错开）。
     */
    val cornerRadiusPx: Double = -1.0,
    /**
     * ★★ **下边圆角半径**（T5，2026-10-02）—— `NaN`（默认）⇒ **与上边同值**（= 改动前行为）。
     *
     * ★ 只有 `shape.type = NOTCH`（**上大下小**）才需要它：不传 ⇒ SDF 只收一个半径
     * ⇒ **折射带与岛体边缘错开**（`cornerRadiusPx` 的 KDoc 点名的那个后果）。
     *
     * ⚠️ 与 [cornerRadiusPx] **同一判据**：`< 0` ⇒ 未知 ⇒ **整趟折射不做**（`bakeRefraction` 判的是上边那个）。
     */
    val cornerRadiusBottomPx: Double = Double.NaN,
    /**
     * ★★ ④b 第二批：**折射 / 色散 / 饱和度**（`migration/71-*`）。
     *
     * `null` ⇒ **不做**（= 改动前的行为，**逐像素相同**）。
     * ★ 它是本批 4 个新设置键的**唯一入口** —— 4 个键全部只在
     * `blurRadius > 0`（L3 开着）时才可能生效，见 `SettingRegistry` 里那 4 个 descriptor。
     */
    val refraction: BackdropRefraction.Params? = null,
    /**
     * ★★★ **R7：是否优先走 GPU**（`material.render.gpu_backend`，2026-10-09）。
     *
     * ★ **默认 `false`** ⇒ 与改动前**逐像素相同**（走 CPU 的 `BackdropRefraction.bake`）。
     * ★ 置 `true` 时：**先试 GPU**（[GpuLiquidGlass.render]）⇒ 任何失败（GL 不可用 /
     *   驱动不支持 / 抛异常）**立即回退 CPU**，并记一条日志。
     *
     * ## ★★ 为什么必须是"可关的开关"而不是"默认开"
     *
     * ★ 三条理由：
     *   ① **GPU 路径是新的** —— 虽然探针 8/0 全绿，但探针只覆盖了**几何/折射/圆角**三态，
     *      真机还有多显示器 DPI / 混合显卡 / 驱动差异等**探针覆盖不到**的场景 ✓
     *   ② **"默认值要稳"是本项目的既定纪律** —— 新后端**不该**在用户没主动开的情况下
     *      改变观感（★ 且 GPU 与 CPU 的输出**不是逐像素相同**：单趟 vs 分阶段）✓
     *   ③ **故障可隔离** —— 用户觉得 GPU 有问题时，关掉即回到已验证的 CPU 路径 ✓
     *
     * ## ⚠️ 线程归属
     *
     * ★ 本参数只被 **L3 采集线程**消费（`bakeRefraction` 在 `runOnce` 里被调）。
     * ★ GL 上下文是**线程亲和**的 ⇒ [GpuLiquidGlass] 的所有调用都在同一线程 ✓
     */
    val preferGpu: Boolean = false,
) {
    /** 本层是否应当启用（半径 0 = 用户关闭）。 */
    val enabled: Boolean get() = blurRadius > 0

    companion object {
        /** 10 Hz（`migration/31-*` §4.6 的 K2 = 10 帧 ≈ 1 s）。 */
        const val DEFAULT_INTERVAL_MS: Long = 100L

        /**
         * `ds = 4` —— `migration/24-*` §3 实测 **43.19 dB**（> 40 通常肉眼不可辨）。
         *
         * ⚠️ **不要改成 8 或 16**：实测 33.99 / 30.56 dB，文字边缘会呈阶梯。
         *    这是 E4 的**定稿结论**（`25-*` §2.2，客观 PSNR + 主观目视两套独立判据一致）。
         */
        const val DEFAULT_DOWNSAMPLE: Int = 4

        /** 采集线程名（诊断用：`jstack` / 日志里能认出是谁）。 */
        const val THREAD_NAME: String = "cdl-l3-capture"
    }
}

/**
 * 一次采集的**完整读数** —— 供日志与探针统一口径。
 *
 * @property accepted 本帧像素是否可用（`false` ⇒ 保留了上一帧的有效背景）
 * @property level 转移后的降级档位
 * @property streak 连续"非 OK"帧数
 * @property s1 本帧 S1 读数（`null` = 算不出来）
 * @property s1hp 本帧 S1-hp 读数
 * @property reason 判据给出的原因串（[SelfCaptureReading.reason]）
 * @property captureMs 本帧截屏耗时（ms）—— **只报告，不作断言**（机器相关）
 * @property blurMs 本帧模糊耗时（ms）—— 同上
 * @property maskInside 掩膜内像素数（诊断"形状是否在栅格内"）
 */
data class BackdropCaptureReport(
    val accepted: Boolean,
    val level: SelfCaptureLevel,
    val streak: Int,
    val s1: Double?,
    val s1hp: Double?,
    val reason: String,
    val captureMs: Double,
    val blurMs: Double,
    val maskInside: Int,
)

/**
 * ★★ **L3 采集侧的状态容器**（无 AWT 事件依赖，线程安全靠"引用替换"）。
 *
 * ## 为什么把"状态"与"线程"分成两个类
 *
 * 因为**可断言的部分**是状态转移（纯逻辑），而**不好断言的部分**是线程调度。
 * 拆开后：
 * · 本类可被探针直接驱动（喂 [acceptCapture] / [noteRejected]），**不需要起线程、不需要真屏**；
 * · 线程循环（[L3CaptureWorker]）只剩"定时 + 调本类 + 处理异常"，没有判据逻辑。
 *
 * 这正是本项目反复验证过的形状：**把判据放在有数据的那一侧**。
 *
 * ## 为什么 `backdrop` 是 `@Volatile` 的不可变引用
 *
 * EDT 每帧读它、采集线程低频写它。若用 `synchronized`，EDT 会被采集线程的
 * 8.25 ms 截屏**阻塞**（最坏情况：一帧多等 8 ms）—— 那是**直接违反帧预算**的。
 * 引用替换则让 EDT **永远拿到一个完整对象**（要么旧要么新），零等待。
 *
 * ⚠️ **代价**：EDT 可能比采集线程**晚一帧**看到新背景。这是**有意的** ——
 *    10 Hz 的采集本来就意味着"背景本来就不是逐帧新的"。
 */
class L3BackdropState {

    /**
     * 当前**有效的模糊背景位图**（岛体尺寸，`TYPE_INT_ARGB`）。
     *
     * `null` = 还没有任何一帧通过判据 ⇒ **消费侧必须画纯色**（铁律推论②：
     * 冷启动**不许"先糊一次"**）。**这个 `null` 是有语义的，不要用空图代替** ——
     * 空图会被当成"有效背景"画上去 ⇒ 词岛背景变全黑（比"晚一点模糊"糟得多）。
     */
    @Volatile
    var backdrop: BufferedImage? = null
        private set

    /** 交付序号（单调递增）。诊断用：可判"采集线程是否还在出帧"。 */
    @Volatile
    var deliveredSeq: Long = 0L
        private set

    /** 被拒帧数（判据不通过）—— 诊断用。 */
    @Volatile
    var rejectedCount: Long = 0L
        private set

    /** 最近一次读数（诊断 / 告警文案用）。`null` = 还没采过。 */
    @Volatile
    var lastReport: BackdropCaptureReport? = null
        private set

    /** 降级链状态机（**每个 L3 实例一个**，见 [SelfCaptureGate] 的 KDoc）。 */
    val gate: SelfCaptureGate = SelfCaptureGate()

    /** 是否已停用采屏（退纯色后置 `true`，[reset] 清掉）。 */
    @Volatile
    var captureDisabled: Boolean = false
        private set

    /**
     * 采到一帧且**判据通过** ⇒ 更新有效背景。
     *
     * @param blurred 已经模糊好的岛体尺寸位图（调用方负责模糊 —— 本类不碰像素）
     * @param report 本帧读数（`accepted` 必须为 `true`）
     */
    fun acceptCapture(blurred: BufferedImage, report: BackdropCaptureReport) {
        backdrop = blurred
        deliveredSeq += 1L
        lastReport = report
    }

    /**
     * 本帧**被拒**（判据不通过）⇒ **不更新** [backdrop]（沿用上一帧有效背景）。
     *
     * ★ 本方法**刻意不接收像素** —— 那是一条结构性保证：
     *   "被拒的帧没有进入渲染的通道"这件事**由类型系统保证**，不靠调用方自觉。
     *   （铁律：**任何情况下都不得静默把（可能含自己的）糊图当背景**。）
     */
    fun noteRejected(report: BackdropCaptureReport) {
        rejectedCount += 1L
        lastReport = report
    }

    /** 退纯色：清空有效背景（消费侧随即回落到纯色路径）+ 停用采屏。 */
    fun fallbackToPureColor() {
        backdrop = null
        captureDisabled = true
    }

    /**
     * 显式复位（[SelfCaptureGate.reset] 的同伴）。
     *
     * ★ 由**外部事件**驱动（用户改设置 / 窗口移动 / 宿主重载插件），
     *   **不是**"每隔几秒自动试一次" —— 后者会造成"糊一帧 → 退纯色 → 再糊一帧"
     *   的周期闪烁，比"一直纯色"更糟（见 [SelfCaptureGate.reset] 的 KDoc）。
     */
    fun reset() {
        gate.reset()
        backdrop = null
        captureDisabled = false
        lastReport = null
    }
}

/**
 * ★★ **L3 采集线程**（10 Hz 低频采屏 → 判据 → 模糊 → 发布）。
 *
 * ## 为什么是**独立线程**而不是 `javax.swing.Timer`
 *
 * 三条，逐条都是本项目的既有事实：
 *
 * ① `Timer` 的回调跑在 **EDT** 上 ⇒ 8.25 ms 截屏 + 2.6 ms 判据 + 5.9 ms 模糊
 *    ≈ **16.8 ms 全部落在帧预算里** ⇒ **必然掉帧**。而 `31-*` §4.3 明确把判据
 *    排除在 EDT 预算外。
 * ② 本项目已实测 **`Thread.sleep` 会改 swing `Timer` 的网格**（`MEMORY` H2 /
 *    纪律 25）⇒ 在 EDT 上做周期性重活会**连带**影响所有动画的节拍。
 * ③ 采集是**可丢弃**的（判据容许丢帧，`SelfCaptureGate` 的 KDoc 已写明）
 *    ⇒ 它天然适合"落后就跳过"，而不是"必须按时完成"。
 *
 * ## 为什么用 `Thread` 而不是 `ScheduledExecutorService`
 *
 * 单线程 + 固定间隔 + 不可取消的中断语义。`ScheduledExecutorService` 在
 * "任务耗时 > 间隔"时会**堆积**（fixed-rate 语义），而我们要的恰恰是
 * **跳过**（见下）。用一个显式循环表达"跳过"比调用 `scheduleAtFixedRate`
 * 再自己处理堆积更直接 —— 也更容易被探针读懂。
 *
 * ## ★ 跳过语义（"落后就跳过"，不是"补跑"）
 *
 * `sleep` 的算法是"睡到下一个绝对时刻"，**上限 `intervalMs`**：
 * 若上一轮耗时超过一个间隔，就**只睡 `intervalMs`**，不补跑前面的轮次。
 * ⇒ 稳态是"尽量 10 Hz"，偶发落后时**自动降频**，而**不会**连续背靠背采屏
 * （那会把一个核吃满，且"补跑"对背景而言**毫无意义** —— 它本来就是低压素材）。
 *
 * ## 生命周期
 *
 * `start()` / `stop()` 一对。`stop` 会打断睡眠（`interrupt`）并把线程标记停，
 * **是幂等的**（重复 `stop` 无副作用）。**daemon = true** —— 宿主退出时不阻塞。
 */
class L3CaptureWorker(
    private val params: BackdropCaptureParams,
    private val state: L3BackdropState,
    /** 取当前自身帧（EDT 交付的 `OwnFrame`）。返回 `null` = 还没渲染过 ⇒ 冷启动。 */
    private val ownFrameProvider: () -> OwnFrame?,
    /** 取岛体在屏幕上的矩形（`Robot` 坐标口径）。`null` ⇒ 本帧放弃。 */
    private val regionProvider: () -> Rectangle?,
    /** 告警出口（用户可见的事走 `ui.toast`，纪律 21）。 */
    private val onWarn: (String) -> Unit = {},
    /**
     * 取每帧掩膜（形状 → 布尔数组）。**返回 `null` = 掩膜尺寸与当帧岛体不符**。
     *
     * ⚠️ 由**调用方**持有 [ShapeMaskRaster]（它有尺寸与缓存，跨帧复用才划算）。
     *    本类每轮调一次 —— 掩膜内部**自己判形状有没有变**，所以这里"每轮调"
     *    仍是 O(1)（形状没变就返回同一个数组）。
     *
     * ⚠️ **为什么允许 `null` 而不在调用方"凑合一个"**：`ShapeMaskRaster` 的
     *    尺寸在构造时定死，而采集每轮传入的是**当帧**岛体尺寸 —— 形状展开动画
     *    期间两者必然不等。若拿错尺寸的掩膜硬算，相关系数**静默出错**（不抛、
     *    不报，只是数字没意义）。⇒ 不匹配时返回 `null` ⇒ 本轮走 `UNKNOWN`
     *    （弃帧，沿用上一帧背景），比"算出一个错的 s1"安全。
     */
    // ⚠️ **刻意不给默认值**：`maskProvider` 是判据的**必需输入** —— 给了默认 `null`
    //    的话，真机哪天漏传就会"每轮 UNKNOWN ⇒ 背景永不更新"，而且**不报错**
    //    （UNKNOWN 是合法读数）。必需参数靠编译期拦住，比靠人记得可靠。
    private val maskProvider: (Shape, Int, Int) -> BooleanArray?,
    /**
     * 每秒帧率日志开关（`animation.frame_log`，用户 2026-09-28 要求，**测试用**）。
     *
     * ⚠️ **默认为 `false`**：这是"测试用"的功能，且行数密度是 1 行/秒 ⇒
     *    常开会挤占日志轮转窗口（见 `SettingRegistry` 里 `animation.frame_log` 的 KDoc）。
     *
     * ★ 为什么是**构造参数**而不是 `var`：本类的生命周期由 `syncL3()` 全权管理 ——
     *    开关值变化时它**重建整个 worker**（走 `SettingsDomains.FRAME` 那条帧域路径）。
     *    故只需构造期定值，无需运行期可变（少一个 `@Volatile` = 少一处并发面）。
     *    ⚠️ 若将来改成"不重建 worker 也能切开关"，必须把它改成 `@Volatile var`
     *      —— 否则跨线程读会读到过期值（本字段由 EDT 写、采集线程读）。
     */
    private val perSecondLogEnabled: Boolean = false,
    /** 日志出口（走 `System.err` ⇒ 宿主重定向到 `super-island.log`）。 */
    private val log: (String) -> Unit = { System.err.println(it) },
) {

    private var thread: Thread? = null

    /** 是否在跑（诊断 / 幂等 `stop` 用）。 */
    val running: AtomicBoolean = AtomicBoolean(false)

    /** 已经跑了多少轮（诊断用：判"采集线程是否还活着"）。 */
    @Volatile
    var rounds: Long = 0L
        private set

    // ── 每秒计量（用户要求，2026-09-28）────────────────────────────────────
    //   ★ 为什么采集线程也需要它：`rounds` 是**累计值**，它只回答"线程还活着吗"，
    //     回答不了"**此刻**每秒真采了几轮、每轮花多久"。而 L3 的**成功路径不写日志**
    //     （见 `runOnce` 的 KDoc）⇒ 在那之前，验收时"背景到底有没有在更新"
    //     只能靠肉眼。本计量器补的就是这个缺口（每秒一行 => 可事后复核）。

    /**
     * 每秒节拍读数。**只产出一行日志，不参与任何判定**
     * （纪律：时间只作报告，断言必须结构性）。
     *
     * ⚠️ 它报的"实得 Hz"口径 = **每秒实际完成了几轮 `runOnce`**
     *   （含被否决/弃帧的轮次 —— 那正是"线程在不在干活"的真相）。
     *   想要"每秒成功发布了几帧"，看行尾的 `交付=` 分项。
     *
     * ★ `enabled` 由构造参数 [perSecondLogEnabled] 定值（关掉时 `noteInternal`
     *   在最前面就 `return null` ⇒ **零开销**，不累计、不建基线、不拼字符串）。
     */
    private val perSecond = PerSecondMeter(label = "L3采集", unit = "轮").also {
        it.enabled = perSecondLogEnabled
    }

    /** 本窗口内**成功发布**（`acceptCapture`）的次数 —— 行尾 `交付=` 用。 */
    private var windowDelivered = 0

    /** 本窗口内采屏累计耗时（纳秒）—— 行尾 `采屏均=` 用。 */
    private var windowCaptureNanos = 0L

    /** 本窗口内采屏次数（只有真采到屏的轮次才计）—— 求平均用。 */
    private var windowCaptureCount = 0

    /** 本窗口内模糊累计耗时（纳秒）—— 行尾 `模糊均=` 用。 */
    private var windowBlurNanos = 0L

    /** 本窗口内模糊次数 —— 求平均用。 */
    private var windowBlurCount = 0

    /**
     * 本窗口内**折射烘焙**累计耗时（纳秒）—— 行尾 `折射均=` 用。
     *
     * ★ 2026-10-01（④b 第二批）新增。**为什么值得单列一行读数**：
     *   本项目的教训是「**给"成功"留一个可读的计数，与给"失败"留日志同等重要**」
     *   （`migration/35-*` §8.5.3）—— 折射是这一步里**最贵**的一项（实测 16.6 ms），
     *   而它**成功时不写日志** ⇒ 不给它一个周期读数，验收时"折射到底跑没跑、多贵"
     *   就只能靠猜。
     */
    private var windowRefractNanos = 0L

    /** 本窗口内折射烘焙次数 —— 求平均用。 */
    private var windowRefractCount = 0

    /**
     * 启动采集线程。**幂等**（已在跑时直接返回）。
     *
     * ⚠️ 调用方必须保证 [params].enabled 为 `true` —— 否则本方法**什么都不做**，
     *    而不是"启动一个什么都不干的线程"（后者会让 `running` 撒谎）。
     */
    fun start() {
        if (!params.enabled) return
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ loop() }, BackdropCaptureParams.THREAD_NAME)
        t.isDaemon = true
        // ★★★ 2026-10-09 **补上降优先级**（B-3 的交付 · 由 `FramePacingProbe` ⑦ 事故推得）
        //
        // ## 为什么
        //
        // ★ 本线程一次 **≈33.4 ms**（截屏 8.25 + 判据 2.6 + 模糊 5.9 + 折射 16.6，均实测），
        //   10 Hz ⇒ 占**单核 ≈33.4%**；★ 而它在 **EDT 上创建** ⇒ 默认**继承 EDT 的 `NORM_PRIORITY`** ✗
        // ★★ 对照：`MaterialLayers.glassPool` 与 `GlOffscreen` **都已显式降到 `MIN_PRIORITY`** ——
        //   **唯独本线程没有** ⇒ ★ 这是**同类隐患**（★ 见下）
        //
        // ## 事故先例（★ 同一个坑）
        //
        // ★ 2026-10-09：把玻璃计算移到后台线程后，`FramePacingProbe` ⑦ **红了** ——
        //   `Timer(16ms)` 的实得间隔变成 **31 ms**（掉一帧）。
        // ★★ **根因**：后台的满核计算**与 EDT 的帧节奏抢 CPU** ⇒ **帧被推迟** ✓
        // ★★★ **⇒ 处置同上**：降优先级，让 EDT 的 `Timer` **始终优先**。
        //   采集晚一两轮出来**看不出来**（★ 折射内容本来就有缓存与插值）✓
        t.priority = Thread.MIN_PRIORITY
        thread = t
        t.start()
        log(
            "[L3] 采集线程启动：interval=${params.intervalMs}ms ds=${params.downsample} " +
                "blur=${params.blurRadius} τ1=${SelfCaptureRules.TAU1} τ1hp=${SelfCaptureRules.TAU1HP} " +
                "K=${SelfCaptureRules.K} K2=${SelfCaptureRules.K2}",
        )
    }

    /** 停止采集线程。**幂等**。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        log("[L3] 采集线程已停（rounds=$rounds）")
    }

    /**
     * 主循环 —— **唯一**的采屏节拍。
     *
     * 结构上刻意**没有** `while (running)` 之外的任何退出点：所有异常都在
     * [runOnce] 内被吞掉（采集失败不该杀死线程 —— 下一轮可能就好了）。
     */
    private fun loop() {
        var nextDue = System.nanoTime()
        while (running.get()) {
            nextDue += params.intervalMs * 1_000_000L
            runOnce()
            // ── 每秒计量（用户要求，2026-09-28）──────────────────────────────
            //   放在 `runOnce()` **之后、sleep 之前**：这样"本轮花了多久"已经定型
            //   （`runOnce` 内部的 `noteRoundCost` 已把耗时记进本窗口），
            //   而跨过 1 秒边界时立刻出报告 —— 不必等下一轮。
            emitPerSecondIfDue(System.nanoTime())
            // ── 睡到下一个绝对时刻；**上限一个间隔**（落后就跳过，不补跑）──
            val now = System.nanoTime()
            val waitNanos = (nextDue - now).coerceAtMost(params.intervalMs * 1_000_000L)
            if (waitNanos > 0) {
                try {
                    Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    // 被 stop() 打断 ⇒ 立刻退出（不再跑一轮）。
                    Thread.currentThread().interrupt()
                    return
                }
            } else {
                // 落后了 ⇒ 把基准拉到当前时刻（**丢弃欠账**，不背靠背补跑）。
                nextDue = System.nanoTime()
            }
        }
    }

    /**
     * 每秒出报告（跨过 1 秒边界时才真的打印）。**无异常路径、无状态依赖**。
     *
     * ## 为什么把"分项"（采屏/模糊/交付）也用同一个窗口
     *
     * 因为它们与"轮数"必须**读同一段时间** —— 否则"10 轮 / 采屏均 8.3 ms"可能
     * 是拿"这一秒的轮数"除以"上一秒的采屏累计"，而那是个**编造出来的比值**。
     * ⇒ 本方法在出行的同一个 `if` 分支里**一起复位**（[PerSecondMeter] 的窗口
     *   与这里的四个累计量**同生同灭**）。
     */
    private fun emitPerSecondIfDue(nowNanos: Long) {
        val line = perSecond.noteInterval(
            nowNanos = nowNanos,
            expectedHz = if (params.intervalMs > 0L) 1000.0 / params.intervalMs else 0.0,
        ) ?: return
        // ── 到这里说明刚好跨过 1 秒边界 ⇒ 用本窗口的四个累计量拼出补充段 ─────
        val delivered = windowDelivered
        val capN = windowCaptureCount
        val blurN = windowBlurCount
        val refN = windowRefractCount
        val capMs = if (capN > 0) windowCaptureNanos / 1_000_000.0 / capN else 0.0
        val blurMs = if (blurN > 0) windowBlurNanos / 1_000_000.0 / blurN else 0.0
        val refMs = if (refN > 0) windowRefractNanos / 1_000_000.0 / refN else 0.0
        windowDelivered = 0
        windowCaptureNanos = 0L
        windowCaptureCount = 0
        windowBlurNanos = 0L
        windowBlurCount = 0
        windowRefractNanos = 0L
        windowRefractCount = 0

        val suffix = StringBuilder()
        suffix.append("交付=").append(delivered)
        if (capN > 0) suffix.append(" 采屏均=").append(fmtMs(capMs)).append("ms(").append(capN).append("次)")
        if (blurN > 0) suffix.append(" 模糊均=").append(fmtMs(blurMs)).append("ms(").append(blurN).append("次)")
        // ★ 折射**只在真的做了**时才出行尾（恒等快路径 ⇒ 一次都不计 ⇒ 这一项根本不出现）
        //   ⇒ 反过来，日志里**有** `折射均=` 就等于"折射真的在跑" —— 这正是"跑的是不是
        //   新构建 + 折射有没有生效"的硬证据。
        if (refN > 0) suffix.append(" 折射均=").append(fmtMs(refMs)).append("ms(").append(refN).append("次)")
        if (state.captureDisabled) suffix.append(" ★已退纯色/停采屏")
        runCatching { log("$line $suffix") }
    }

    /**
     * 记账：**本轮是否交付** + **采屏/模糊耗时**。由 [runOnce] 调用（成功/失败两条路都调）。
     *
     * ★ 原记：本函数收一个 `roundNanos`（"本轮总耗时"），实为**死参数** ——
     *   函数体从未使用它，`runOnce` 的 `roundStart` 被算出、传入、丢弃。
     *   功能上没错（"每轮平均耗时"由 [PerSecondMeter.noteInterval] 用**两次调用之间的间隔**
     *   自动得出，无需外部喂），但它让读者以为"轮耗时在这里被用了"，
     *   且每轮白搭一次 `System.nanoTime()`。**2026-09-28 删除**（取证：全文件仅签名处出现 1 次）。
     */
    private fun noteRoundCost(
        delivered: Boolean,
        captureNanos: Long,
        blurNanos: Long,
        refractNanos: Long,
    ) {
        if (delivered) windowDelivered += 1
        if (captureNanos > 0L) {
            windowCaptureNanos += captureNanos
            windowCaptureCount += 1
        }
        if (blurNanos > 0L) {
            windowBlurNanos += blurNanos
            windowBlurCount += 1
        }
        // ⚠️ `> 0` 而不是"每轮都计"：**恒等快路径下 `bakeRefraction` 一次都不进循环**
        //    ⇒ 耗时是 0（或极小）⇒ 若无条件计，`折射均=` 会显示一个"看起来在跑但接近 0"
        //    的假读数。用 `> 0` ⇒ 日志里出现 `折射均=` **就等于折射真的跑了**。
        if (refractNanos > 0L) {
            windowRefractNanos += refractNanos
            windowRefractCount += 1
        }
    }

    /**
     * 采集**一轮**。异常一律吞掉并记日志（下一轮再试）。
     *
     * ## 为什么"退纯色后直接 return"放在最前面
     *
     * 因为退纯色的语义是"**停用 B4 截屏**"（`31-*` §4.6 ③）——
     * 若这里仍去采屏，就变成"声称停用、实际每 100 ms 仍花 8.25 ms"
     * ⇒ 既省不下成本，也断了不 R2 糊团的承诺。
     */
    fun runOnce() {
        rounds += 1L
        // ── 每秒计量：本轮的耗时/交付在**所有 return 路径**上都要被记到 ──────
        //   ★ 用"记账变量 + finally 统一登记"而不是在每个出口各记一次：
        //     因为 `delivered` 只有走到 ⑤ 之后才可能为 true，而中间有 5 条 early return。
        //     用一个局部 `var delivered` 由出口写入 + `finally` 统一登记 ——
        //     这样**新增一条 return 也不会漏记**（漏记会让每秒行的"交付"数撒谎）。
        //   ⚠️ 本处**不取"本轮起点时间戳"**：每轮耗时由 `PerSecondMeter.noteInterval`
        //     用相邻两次调用的间隔自动得出（见 `noteRoundCost` 的 KDoc）。
        var delivered = false
        var capNanos = 0L
        var blurNanos = 0L
        var refractNanos = 0L
        try {
            if (state.captureDisabled) return
            val own = ownFrameProvider()
            // ── 冷启动：没有任何自身帧证据 ⇒ **直接纯色**，不许"先糊一次" ────────
            //
            // ★ 这一条的判据是 [mustStartWithPureColor]，**不是** `own == null`
            //   写在两处 —— 两处写就有一天会不同步（BUG-079 的形态）。
            if (mustStartWithPureColor(own?.pixels)) {
                val reading = coldStartReading()
                val res = state.gate.onReading(reading)
                noteAndReport(res, reading, captureMs = 0.0, blurMs = 0.0, maskInside = 0)
                return
            }
            val frame = own!!
            val w = frame.islandWidth
            val h = frame.islandHeight
            if (w <= 0 || h <= 0) return

            val region = regionProvider() ?: return

            // ── ① 采屏（成本 8.25 ms @304×154；**只管采集，不采样**）────────────
            val t0 = System.nanoTime()
            val screen = captureScreen(region)
            val t1 = System.nanoTime()
            capNanos = t1 - t0
            if (screen == null) return

            // ── ② 裁剪到岛体区域（与 ownFrame.pixels **逐索引对应**）───────────
            //
            // ★★★ 2026-10-10 **BUG-160 修复**：★ 原为 `Rectangle(region.x, region.y, w, h)` ✗
            //
            // ## 症状（真机日志取证）
            //
            // ★ `帧率（L3采集）：… 交付=0 采屏均=9.3ms(11次)` —— ★ **采屏在跑，但一帧都没交付**；
            //   ★ 且**无「模糊均」、无「折射均」、无任何 `[L3]` 告警**
            //   ⇒ ★ 早退点落在「**采屏之后、模糊之前**」= 本行 ✓
            //
            // ## 根因：**两个坐标口径对不上**
            //
            // ★ ① 的 `captureProvider` 默认实现是 `createScreenCapture(region)`
            //   ⇒ ★ 返回的是**区域大小**的图（`region.width × region.height`，如 505×154）✓
            // ★ 而本行原来拿**屏幕绝对坐标**去裁（如 `986 + 505 = 1491 > 505`）
            //   ⇒ ★ `cropArgb` 的越界判据**恒真** ⇒ 返回 `null`
            //   ⇒ ★ 走下面 `no-own-pixels` 的 UNKNOWN 早退 ⇒ ★★ **`交付=0` 永远** ✗
            //
            // ⚠️ **契约本来是对的，是"可注入化"那一步打破的** ——
            //   `cropArgb` 的 KDoc 至今写着「**本函数假定整屏截图的坐标原点 = `(0,0)`**……
            //   `Robot` 抓的是**整屏**」，而 `captureProvider` 改成抓**区域**之后
            //   **没人回头改这一行**（★ 本项目最多次的「改了 A 忘了改 B」形态）✓
            //
            // ## ★★ 为什么探针一直是绿的（★ 这才是最该记的一条）
            //
            // ★ `L3BackdropCaptureProbe` 的 `regionProvider = { Rectangle(0, 0, W, H) }`
            //   —— ★★ **它把区域摆在屏幕原点** ⇒ `0 + 96 > 96` 为假 ⇒ **同一段代码恰好成立** ✗
            //   ⇒ ★★ **探针与生产不同源**（纪律 88 的同族）：探针喂的"场景"在生产里不存在 ✓
            //   ⇒ ★ 已同批修探针（区域改**非零原点**）⇒ ★ **回退本行会当场变红** ✓
            //
            // ## 修法
            //
            // ★ 截图**就是**区域 ⇒ 裁剪原点在**图内**恒为 `(0, 0)` ✓
            //   （★ `cropArgb` 的越界判据**保留** —— 它现在守的是
            //    "**截回来的图必须至少有 w×h**"，这正是区域截图该守的那一条）✓
            val capPixels = cropArgb(screen, Rectangle(0, 0, w, h), w, h)
            // ★ 自身像素是 **位图**（`OwnFrame.pixels`，由 EDT 交付）——
            //   判据要的是 `IntArray` ⇒ 这里摊平。位图本身就**只有岛体那 w×h**，
            //   故直接整幅读出即与 `capPixels` 逐索引对应（勿再套 `cropArgb`）。
            val ownPixels = ownPixelsOrNull(frame.pixels, w, h)
            if (capPixels == null || ownPixels == null) {
                // 自身像素没 armed / 尺寸不匹配 ⇒ **没有可比对的两组** ⇒ UNKNOWN。
                val reading = SelfCaptureReading(
                    decision = SelfCaptureDecision.UNKNOWN,
                    s1 = null,
                    s1hp = null,
                    reason = "no-own-pixels",
                )
                val res = state.gate.onReading(reading)
                noteAndReport(res, reading, ms(t0, t1), 0.0, 0)
                return
            }

            // ── ③ 判据（S1 + S1-hp；掩膜内取样）───────────────────────────────
            val mask = maskProvider(frame.shape, w, h)
            if (mask == null) {
                // 掩膜尺寸与当帧岛体不符（形状动画中）⇒ 没法比 ⇒ UNKNOWN（弃帧）。
                val reading = SelfCaptureReading(
                    decision = SelfCaptureDecision.UNKNOWN,
                    s1 = null,
                    s1hp = null,
                    reason = "mask-size-mismatch",
                )
                val res = state.gate.onReading(reading)
                noteAndReport(res, reading, ms(t0, t1), 0.0, 0)
                return
            }
            val reading = detectSelfCapture(
                capturePixels = capPixels,
                ownPixels = ownPixels,
                insideMask = mask,
                w = w,
                h = h,
                highpassRadius = params.highpassRadius,
            )
            val res = state.gate.onReading(reading)

            // ── ④ 门控：告警 / 退纯色 是**边沿**（不是电平）───────────────────
            if (state.gate.shouldWarn(res)) {
                val msg = "真透视暂不可用（疑似自捕获，连续 ${res.streak} 帧）"
                log("[L3] ★ $msg s1=${fmt(res.reading.s1)} s1hp=${fmt(res.reading.s1hp)}")
                // 纪律 21：用户可见的事必须走 ui.toast（宿主无控制台）。
                runCatching { onWarn(msg) }
            }
            if (state.gate.shouldFallbackToPureColor(res)) {
                state.fallbackToPureColor()
                log(
                    "[L3] ★★ 退纯色玻璃并停用截屏（连续 ${res.streak} 帧；" +
                        "s1=${fmt(res.reading.s1)} s1hp=${fmt(res.reading.s1hp)} reason=${res.reading.reason}）",
                )
                noteAndReport(res, reading, ms(t0, t1), 0.0, mask.count { it })
                return
            }

            if (!res.accepted) {
                // 弃帧：**不更新背景**（沿用上一帧有效背景）—— 铁律。
                noteAndReport(res, reading, ms(t0, t1), 0.0, mask.count { it })
                return
            }

            // ── ⑤ 模糊（复用 `CoverBackdrop` 的 int[] 双缓冲实现）──────────────
            val t2 = System.nanoTime()
            val blurred = blurBackdrop(capPixels, w, h)
            val t3 = System.nanoTime()
            blurNanos = t3 - t2

            // ── ⑤′ ★★ ④b 第二批：**折射 / 色散 / 饱和度**（2026-10-01）──────────
            //
            // ## 为什么在模糊**之后**（不是之前）
            //
            // 业界管线的顺序是 `backdrop → Blur → PostProcessing → LiquidGlassEffect`，
            // 且折射采样的是 `u_blurredBg`（**模糊后**那张）⇒ 先折射再模糊会把"掰弯"糊掉。
            //
            // ## 为什么放在**采集线程**而不是 EDT
            //
            // 实测 **16.6 ms/次**（`_diag_spw/BackdropRefractionProbe.java`）。
            // 放在 EDT ⇒ 直接吃掉一整个 60fps 帧预算；放在这里 ⇒ 占 10 Hz 采集
            // 预算（100 ms）的 16.6%，★ **EDT 侧一行都不改**（它只是 `drawImage` 一张烘好的图）。
            //
            // ## 恒等快路径
            //
            // `refraction == null`（L3 关 / 4 个键都没开）或 `isIdentity` ⇒ **整趟跳过**
            // ⇒ ★ **与改动前逐像素相同**（"默认值要稳"的兑现点）。
            val t4 = System.nanoTime()
            val baked = bakeRefraction(blurred, w, h)
            val t5 = System.nanoTime()
            refractNanos = t5 - t4

            delivered = true
            state.acceptCapture(
                blurred = baked,
                report = BackdropCaptureReport(
                    accepted = true,
                    level = res.level,
                    streak = res.streak,
                    s1 = reading.s1,
                    s1hp = reading.s1hp,
                    reason = reading.reason,
                    captureMs = ms(t0, t1),
                    blurMs = ms(t2, t3),
                    maskInside = mask.count { it },
                ),
            )
        } catch (t: Throwable) {
            // 采集线程**绝不能因单轮异常而死** —— 死了就永远停在最后一帧背景上，
            // 而用户看到的是"背景不更新了"，没有任何提示。故吞掉 + 记日志。
            runCatching { log("[L3] ⚠️ 采集轮异常（已忽略，下一轮重试）：${t::class.simpleName}: ${t.message}") }
        } finally {
            // ★ 每秒计量的记账口（**唯一**）：无论走哪条 return / 抛异常，都登记一次。
            //   放 finally 是为了"新增 return 也不会漏记"—— 漏记会让每秒行的
            //   "交付"数与"轮数"对不上，而那正是本计量器要回答的问题。
            runCatching { noteRoundCost(delivered, capNanos, blurNanos, refractNanos) }
        }
    }

    /**
     * ⑤′ 的**唯一实现点**：折射 / 色散 / 饱和度。
     *
     * ## 为什么单独抽一个方法（而不是在 `runOnce` 里内联三行）
     *
     * 因为它有**三条恒等快路径**，而这三条都必须**可断言**（"默认档零变化"的钉子）：
     * · `params.refraction == null`（L3 关 / 4 个键都没开）
     * · `params.refraction.isIdentity`（厚度或折射量 = 0 且饱和度 = 1.0）
     * · `params.cornerRadiusPx < 0`（**圆角未知** ⇒ 折射带会与岛体边缘错开 ⇒ **宁可不做**）
     *
     * ★ 三条都走同一个 `return src` ⇒ 消费侧拿到的**就是模糊那张位图本身**
     * （不是复制品）⇒ 逐像素相同这件事**不靠约定**。
     */
    private fun bakeRefraction(blurred: BufferedImage, w: Int, h: Int): BufferedImage {
        val p = params.refraction ?: return blurred
        if (p.isIdentity) return blurred
        if (params.cornerRadiusPx < 0.0) return blurred
        // ★★★ R7：先试 GPU（`preferGpu` 默认 false ⇒ 不动老路径）✓
        //   ★ 契约：**GPU 失败不外泄** —— 拿不到结果就静默回退 CPU（下面那段）✓
        if (params.preferGpu) {
            gpuRefractionOrNull(blurred, p)?.let { return it }
        }
        return runCatching {
            // ★★ T5：下边半径一并传（`NaN` ⇒ `bake` 内部与上边同值 ⇒ 改动前行为）。
            BackdropRefraction.bake(
                blurred, params.cornerRadiusPx, p,
                cornerRadiusBottom = params.cornerRadiusBottomPx,
            )
        }.getOrElse { t ->
            // ★ 烘焙失败（内存 / 尺寸）⇒ **退回模糊那张**，不让采集线程死。
            //   ⚠️ 与 `runOnce` 的外层 catch 是**两道**：那一层兜"整轮"，
            //     这一层兜"这一步" —— 后者能让**本轮的背景仍然被发布**（只是没折射），
            //     而前者会让本轮**完全弃帧**。粗活失败不该让细活也白干。
            runCatching {
                log("[L3] ⚠️ 折射烘焙失败（本轮退回纯模糊）：${t::class.simpleName}: ${t.message}")
            }
            blurred
        }
    }

    /**
     * ★★★ **R7：GPU 折射（唯一入口）** —— 2026-10-09。
     *
     * ## 契约（★ 三条，缺一不可）
     *
     * ① **不可用即静默走 CPU** —— [GpuLiquidGlass.isAvailable] 为 false（非 Windows /
     *    驱动不支持 / GL 建上下文失败）⇒ 返回 `null` ⇒ 上层回退 CPU ✓
     * ② **任何异常都吞掉** —— GL 调用链长（建上下文 → 编译 → link → 纹理 → FBO →
     *    draw → 读回），任一步抛错都**不该**影响这一帧的背景 ⇒ `runCatching` 兜住 ✓
     * ③ **只在本线程建/用 GL 上下文** —— 由 `GpuLiquidGlass` 内部的 `ThreadLocal`
     *    持有（GL 上下文是**线程亲和**的，跨线程用会拿到别人的上下文或直接失败）✓
     *
     * ## ⚠️ 为什么要单独抽一个方法
     *
     * ★ 因为"回退"这件事必须有**唯一**的实现点 —— 若散落在 `if/else` 里，
     *   将来加一条"GPU 半途失败也要回退"的分支时**必然会漏** ✓
     *
     * @return GPU 渲染结果（`w×h` 位图）；不可用或失败 ⇒ `null`（调用方回退 CPU）
     */
    private fun gpuRefractionOrNull(
        blurred: BufferedImage,
        p: BackdropRefraction.Params,
    ): BufferedImage? {
        if (!GpuLiquidGlass.isAvailable()) return null
        return runCatching {
            // ★ 参数口径与 `GpuLiquidGlassProbe` **完全一致**（`surfaceAlpha`=0.10 /
            //   `glarePercent`=60 都是 `render` 的默认值）⇒ ★ 探针验过的就是生产跑的 ✓
            GpuLiquidGlass.render(
                backdrop = blurred,
                cornerRadius = params.cornerRadiusPx,
                params = p,
                cornerRadiusBottom = params.cornerRadiusBottomPx,
            )
        }.getOrElse { t ->
            // ★ 只记一次（`preferGpu` 开着才可能走到这里，不会刷屏）✓
            runCatching {
                log("[L3] ⚠️ GPU 折射失败（本轮退回 CPU）：${t::class.simpleName}: ${t.message}")
            }
            null
        }
    }

    private fun noteAndReport(
        res: SelfCaptureGateResult,
        reading: SelfCaptureReading,
        captureMs: Double,
        blurMs: Double,
        maskInside: Int,
    ) {
        state.noteRejected(
            BackdropCaptureReport(
                accepted = false,
                level = res.level,
                streak = res.streak,
                s1 = reading.s1,
                s1hp = reading.s1hp,
                reason = reading.reason,
                captureMs = captureMs,
                blurMs = blurMs,
                maskInside = maskInside,
            ),
        )
    }

    /**
     * 采屏（`Robot.createScreenCapture`）。
     *
     * ⚠️ `Robot` 由调用方**惰性创建一次**并传进来（`IslandPanel.ownCaptureRobot`
     *    已有这个惰性单例）—— 本类**不自己 new**：`Robot` 构造会抛
     *    （无图形环境 / 权限不足），而"抛了要记住并永久降级"这件事已经有主了。
     *
     * ## ★ 为什么要再包一层 [captureProvider]，而不是直接调 `robotProvider()`
     *
     * 因为 **`Robot` 是 `final` 类、`createScreenCapture` 是 `final` 方法**，
     * 探针无法伪造它（`Robot` 构造本身在 headless 下就抛）。
     * 若注入点只落在 `Robot` 那一层 ⇒ **探针永远只能测到"冷启动"和"尺寸不符"
     * 两条边界**，测不到真正的主判决路径（采屏 → 裁剪 → 判据 → 门控 → 模糊）。
     * 那等于"护栏只护住了门口的台阶"。
     *
     * ⇒ 把"**怎么拿到一张屏幕图**"变成可注入的一等公民：
     *   默认走 `Robot`（真机），探针注入合成图。**分界线正好落在系统调用上。**
     */
    private fun captureScreen(region: Rectangle): BufferedImage? =
        runCatching { captureProvider(region) }.getOrNull()

    /** 模糊：走 [CoverBackdrop] 的原实现（**不重写**）。 */
    private fun blurBackdrop(pixels: IntArray, w: Int, h: Int): BufferedImage {
        val src = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        src.setRGB(0, 0, w, h, pixels, 0, w)
        return CoverBackdrop.blurForBackdrop(src, params.blurRadius, params.downsample)
    }

    /**
     * 取 [OwnFrame] 的像素并按尺寸校验后摊平。**尺寸不符返回 `null`**。
     *
     * ## 为什么不自己写一遍 `getRGB`
     *
     * 同包的顶层 `toArgbArray(BufferedImage)`（`ShapeMaskRaster.kt`）就是干这个的，
     * 且它的 KDoc 已把"为什么判据收 `IntArray` 而不是两个位图"讲清了。
     * **再写一遍 = 造第二份实现** —— 两份一旦有一份改了（比如将来改成 `TYPE_INT_RGB`
     * 快路径）就会出现"采集侧与判据侧对像素的理解不一致"，而那是静默的
     * （数字照样算得出来，只是没意义）。⇒ **只做尺寸校验，摊平交给它。**
     *
     * ⚠️ **不做缩放**：尺寸不符 = 采集侧与 EDT 侧不在同一帧（形状动画中）
     * ⇒ 返回 `null` 走 `UNKNOWN`，**不得**拉伸/裁剪去凑。
     */
    private fun ownPixelsOrNull(img: BufferedImage?, w: Int, h: Int): IntArray? {
        if (img == null) return null
        if (img.width != w || img.height != h) return null
        return toArgbArray(img)
    }

    /**
     * 注入式 `Robot` 提供者。
     *
     * ⚠️ 用**函数**而不是直接在类里 `by lazy { Robot() }`：本类要能被探针构造
     *    （探针不能创建真 `Robot` —— headless / 无权限）。这样"逻辑"与"系统调用"
     *    就分开了，而**分界线正好落在系统调用上**。
     */
    var robotProvider: () -> java.awt.Robot? = { null }

    /**
     * **采屏函数本体**（`Rectangle` → 屏幕图）。默认走 [robotProvider] 的 `Robot`。
     *
     * ## 为什么在 `robotProvider` 之上再开这一个口子
     *
     * `Robot` 是 `final`，`createScreenCapture` 也是 `final` —— 探针**没法伪造**。
     * 若只有 `robotProvider`，探针最多验证到"`robotProvider()` 返回 null ⇒ 不崩"，
     * 而**主判决路径（采屏 → 裁剪 → 判据 → 门控 → 模糊）一条都测不到**。
     * 那等于把护栏做成了"只拦不存在的车"。
     *
     * ⇒ 把"怎么拿到一张屏幕图"整体做成可替换：真机用默认（`Robot`），
     *   探针用合成图。**分界线仍然落在系统调用上**（合成图那个实现里没有系统调用）。
     *
     * ⚠️ 真机路径**必须**是默认值 —— 不然"探针注入的实现"会悄悄变成生产实现
     *    （本项目反复记的形态：探针绿了，因为它测的是探针自己的替身）。
     */
    var captureProvider: (Rectangle) -> BufferedImage? = { region ->
        robotProvider()?.createScreenCapture(region)
    }

    private fun ms(from: Long, to: Long): Double = (to - from) / 1_000_000.0

    private fun fmt(v: Double?): String =
        if (v == null) "null" else String.format(java.util.Locale.ROOT, "%.3f", v)

    /** 每秒行的补充段用：1 位小数（与 `PerSecondMeter` 的同名口径一致）。 */
    private fun fmtMs(v: Double): String =
        if (!v.isFinite()) "n/a" else String.format(java.util.Locale.ROOT, "%.1f", v)
}
