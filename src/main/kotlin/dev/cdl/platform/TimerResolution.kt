// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * **进程定时器精度**（Windows `timeBeginPeriod` / `timeEndPeriod`）。
 *
 * ## 它解决什么（用户原话，逐字）
 *
 * > 「**根据工作区的相关侦查/评估文档修复实际动画帧率与标定不符的问题**。」
 *
 * 侦查文档 = **`recon/54-帧测量可靠性风险册.md`**（§7 的 C0 探针实测 ✗）。
 *
 * ### 根因（`AnimationRuntime.kt:480-489` 与 `recon/54` §7 一致）
 *
 * 帧驱动建在 **`javax.swing.Timer`** 上，而它继承 **Windows 的系统定时器量子** ✗。
 * 默认量子是 **15.625 ms** ⇒ 请求 6/15/16/33/100 ms 会被**量化成量子的整数倍**：
 *
 * | 请求 | 实得 | 倍数 |
 * | --- | --- | --- |
 * | 6 / 15 ms | 15.625 ms | 1× |
 * | 16 ms | 31.25 ms | 2× |
 * | 33 ms | 46.875 ms | 3× |
 * | 100 ms | 109.375 ms | 7× |
 *
 * ⇒ **`animation.frame_rate` 的 5 个选项只产出 2 种实得帧率（32 / 64 Hz）**，
 * **与标签相符的选项数 = 0** ✗ —— 这就是用户看到的"帧率与标定不符"。
 *
 * ### 修法：把进程量子从 15.625 ms 降到 **1 ms**
 *
 * `timeBeginPeriod(1)` 是 **Windows 官方 API**（`winmm.dll`），媒体播放器类软件的标准做法
 * （宿主 SPW 自己作为播放器**可能也调过** ✗ —— 那样本调用是幂等的无害叠加 ✓）。
 *
 * ⚠️ **`recon/54` §5.3 的判据纪律（必须遵守）**：
 * **「时间只作报告，断言必须结构性。」** 帧周期是**机器相关**的量（随 CPU 负载 / GC /
 * 屏幕刷新率 / 宿主行为而变 ✗）⇒ 本类**不产出任何断言**，也**不承诺**"调了就一定是 144 fps" ✗。
 * 它只做一件事：**把可用的分辨率交还给系统** ✓，剩下的由 `FramePacingMonitor`
 * 在宿主里**实测报告** ✓（那才是唯一可信的验收口径 ✗）。
 *
 * ## ⚠️ 这是「API 之外」的能力（本项目铁律 A1 要求显式标注 + 说明代价）
 *
 * SPW Workshop API **没有任何"设置定时器精度"的能力** ✗ ⇒ 本类走 **JNA 直调 `winmm.dll`** ✗。
 *
 * **代价（逐条，均已兜底）**：
 * 1. **进程级副作用** ✗ —— 精度提升对**整个宿主进程**生效（不只是我们 ✗）。
 *    这是 Windows 的设计（量子是进程级 ✗）。**兜底**：`timeBeginPeriod`/`timeEndPeriod`
 *    在 Windows 内部**按进程计数** ✓ ⇒ 我们 `end()` 后**回到调用前的状态** ✓（不会把宿主的设置抹掉 ✗）。
 * 2. **必须配对** ✗ —— 只 `begin` 不 `end` 会让宿主**永久**处于高精度态（增加耗电 ✗）。
 *
 *    ⚠️ **2026-09-24 更正：原文的兜底论证不成立。** 原文写「`begin` 在 `start()`、
 *    `end` 在 `stopRuntime()`（stop 与「退出」共用的唯一实现）⇒ 两条路径行为一致，不会漏」——
 *    实测 `restore()`（即 `end()`）**只在 `stopRuntime()` 内**，而它首句曾是
 *    `val old = runtime ?: return` ⇒ **`start()` 抛异常时 `runtime` 已置 null**
 *    ⇒ **`restore()` 永不执行** ⇒ **确实会漏**（宿主停在永久高精度态）。
 *
 *    ✅ **已修（2026-09-24，本类 + `host/CdlPlugin.kt`）**：
 *    根因是**释放动作被一个无关状态门控** —— `restore()` 要撤销的是 `raised`，
 *    却以 `runtime != null` 为前置。修法两条：
 *    1. `CdlPlugin.stopRuntime()` 去掉以 `runtime` 为条件的提前返回，
 *       **无条件**调用 `restore()`（`restore()` 自带 `if (!raised) return` ⇒ 幂等）；
 *    2. `CdlPlugin.start()` 的失败路径改走 `stopRuntime()` 这个**唯一出口**，
 *       不再自己写一份 `runtime = null; close()`（那份漏了 `restore()`）。
 *    ⇒ 不变量：**`raise()` 与 `restore()` 之间不得存在以 `runtime` 为条件的守卫**。
 *    守卫 = 探针 `TimerPairingProbe`（驱动后端计数，**不依赖 winmm**）。
 * 3. **非 Windows / JNA 缺失 / `winmm` 加载失败** ✗ ⇒ 全部 `runCatching` 接住，**静默降级**
 *    （保持系统默认量子 ✗）。**不抛异常、不打扰用户** ✓ —— 因为这只是"锦上添花"的优化 ✗，
 *    失败时行为与修复前**完全一致** ✓。
 * 4. **无控制台可见性** ✗ ⇒ 结果走 `System.err`（落日志 ✓）。用户可见的"有没有变快"
 *    只能靠 `FramePacingMonitor` 的摘要 + 肉眼 ✓。
 */
object TimerResolution {

    /**
     * 定时器精度的两个原语。**生产实现 = `winmm.dll`**；**探针可替换**（见 [installBackendForProbe]）。
     *
     * 方法名与 Win32 头文件**逐字一致**（`UINT timeBeginPeriod(UINT)`）——
     * 这样 JNA 动态代理不需要额外映射（若接口里多一个不同名的方法，
     * 代理会去 `winmm.dll` 里找一个**不存在的符号** ✗）。
     */
    interface Backend {
        fun timeBeginPeriod(uPeriod: Int): Int
        fun timeEndPeriod(uPeriod: Int): Int
    }

    /** `winmm.dll` 的 JNA 绑定。签名与 Win32 头文件逐字一致。 */
    private interface Winmm : Library, Backend

    /** 期望的量子（毫秒）。1 ms 是 Windows 允许的最小值。 */
    private const val PERIOD_MS = 1

    /** 是否**由我们**成功提升了精度（`end` 时据此决定要不要回退 ✗）。 */
    @Volatile
    private var raised = false

    /**
     * **仅供探针**的替换后端（非 null 时**完全替代** `winmm`；生产路径从不写它）。
     *
     * 与 `animation/AnimationRuntime.kt` 的 `startNanos: Long = System.nanoTime()` 是**同一条理由**：
     * 若探针只能打真实 `winmm`，它就退化成"在测本机有没有 winmm" = **环境断言，换机器即假红** ✗
     * （`recon/54` §5.3 的判据纪律：断言必须结构性）。
     */
    @Volatile
    private var backendForProbe: Backend? = null

    private val winmm: Backend? by lazy {
        // 非 Windows 直接不加载（`winmm` 不存在 ⇒ `Native.load` 会抛 ✗）。
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        if (!isWindows) {
            null
        } else {
            runCatching { Native.load("winmm", Winmm::class.java) as Backend }.getOrNull()
        }
    }

    /** 当前生效的后端：探针替换优先，否则真实 `winmm`。 */
    private fun backend(): Backend? = backendForProbe ?: winmm

    /**
     * **仅供探针**：替换后端（`null` = 恢复真实 `winmm`），并把 [raised] 复位，
     * 使探针能从干净状态开始断言配对性。**生产路径从不调用它。**
     */
    fun installBackendForProbe(backend: Backend?) {
        backendForProbe = backend
        raised = false
    }

    /**
     * 提升进程定时器精度到 [PERIOD_MS]。**幂等** ✓（重复调用只生效一次 ✗）。
     *
     * 返回值只作**日志**用，**不参与任何断言** ✓（`recon/54` §5.3 ✗）。
     */
    fun raise() {
        if (raised) return
        val lib = backend() ?: return
        runCatching {
            val rc = lib.timeBeginPeriod(PERIOD_MS)
            if (rc == 0) {
                raised = true
                System.err.println(
                    "[CDL] 定时器精度已提升到 ${PERIOD_MS}ms（timeBeginPeriod）" +
                        " —— 这是修「实际帧率与标定不符」的前提（recon/54 §7）。" +
                        "⚠️ API 之外（JNA 直调 winmm.dll），进程级副作用，退出时回退。",
                )
            } else {
                System.err.println(
                    "[CDL] timeBeginPeriod(${PERIOD_MS}) 返回 $rc，保持系统默认量子" +
                        "（帧率可能仍与标定不符，见 recon/54 §7）。",
                )
            }
        }
    }

    /**
     * 回退精度。**只在我们确实提升过时**才调用 ✗
     * （否则会把宿主自己设的精度一起抹掉 ✗ —— 那才是真正的副作用 ✗）。
     */
    fun restore() {
        if (!raised) return
        val lib = backend()
        // ⚠️ 后端不可用时**也必须复位** `raised` —— 否则状态永久错位：
        //    `raise()` 会因 `raised == true` 短路 ⇒ 精度再也提升不了 ✗。
        //    （`raised` 是"我们要撤销什么"的唯一真相，不能依赖 IO 是否可用。）
        if (lib == null) {
            raised = false
            return
        }
        runCatching {
            lib.timeEndPeriod(PERIOD_MS)
            raised = false
        }
    }

    /** 供探针/日志查询"我们现在是否处于提升态"。**纯查询，无副作用** ✓。 */
    fun isRaised(): Boolean = raised
}
