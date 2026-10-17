// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.animation

/**
 * ★★ **每秒节拍计量器** —— 把"每秒实际发生了多少次、每次实际花了多少 ms"
 * 打成**一行可读日志**（用户要求，2026-09-28）。
 *
 * ## 它为什么存在（与 [FramePacingMonitor] 的分工，别混）
 *
 * | | [FramePacingMonitor] | **本类** |
 * |---|---|---|
 * | 输出节奏 | 每 **2400 帧**（≈40 s）一行 | **每 1 秒**一行 |
 * | 回答的问题 | "这一段帧周期的**分布**好不好"（p05/p50/p95/p99、迟帧率） | "**此刻**每秒跑了几帧、每帧实际多少 ms" |
 * | 用途 | 事后分析（跑到哪儿掉的帧） | **现场核对**（"我说能跑 60 fps，现在真跑到了吗"） |
 * | 样本存储 | 8192 槽环形缓冲（为算分位数） | **零存储**（只累计 sum/max/count） |
 *
 * ★ **两者并存、互不干扰**（用户 2026-09-28 明确要求）。本类**不替代**它。
 *
 * ## ★ 为什么"每秒"是必要的（本类存在的根本理由）
 *
 * 本项目已有过一条教训（记在 L3 的待办里）：**`runOnce()` 成功路径不写日志
 * ⇒ "成功帧数"没有读数 ⇒ 验收时只能靠肉眼、事后无法复核。**
 * 分位数摘要是"每 40 秒一次的事后报告"，它证明不了"此刻正在发生"。
 * 而验收（尤其"帧率达成情况"）要的恰恰是**此刻**：
 * 用户拖一下滑杆、挪一下窗口，然后问"刚才那一下掉帧没有" ——
 * **只有每秒行能回答。**
 *
 * ## ★★ 为什么本类**不做任何断言**（纪律：时间只作报告）
 *
 * 帧率是**机器相关**的量（CPU 负载 / GC / 刷新率 / 宿主行为）。
 * 把它写成断言 ⇒ 换台机器就假红（"不可复现的红"）。
 * ⇒ 本类**只产出文本**，由人读；**永不参与门禁判定**。
 * 与 [FramePacingMonitor] 同一条纪律（`recon/54` §5.3）。
 *
 * ## 输出格式（固定，便于 grep 与人工判读）
 *
 * ```
 * [CDL] 帧率（动画帧）：序号=12 1秒内=58 帧 实得=58.0fps 平均=17.2ms 最大=34.1ms
 * [CDL] 帧率（L3采集）：序号=12 1秒内=10 轮 实得=10.0Hz 平均=16.9ms 最大=22.4ms
 * ```
 *
 * （末尾按需追加补充段，例如 L3 的"采屏/模糊"分项。见 [note] 的 `extra` 参数。）
 *
 * ## 为什么是"1 秒窗口"而不是"1 秒定时器"
 *
 * **定时器会引入一个新的调度源** —— 而本项目已实测 `Thread.sleep` 会改
 * swing `Timer` 的网格（`MEMORY` H2 / 纪律 25）。
 * ⇒ 本类**只在被调用时顺手判"是否跨过了 1 秒边界"**（`now - windowStart >= 1e9`），
 * **不持有线程、不持有 Timer、不碰 EDT**。代价是"秒边界不精确"
 * （取决于调用方的节拍），但**这正是我们要的**：它测的就是调用方的那条节拍。
 *
 * ## 线程安全
 *
 * 各持一个实例、各在自己那条线程上调用 ⇒ **无共享**，故不加锁。
 * 若将来要跨线程，调用方自己保证（本类 KDoc 已声明此约定）。
 *
 * ## ★ `enabled` 门：关闭时**零开销**（不是"跑了再丢掉"）
 *
 * 用户 2026-09-28 说这是「**测试用**」的功能 ⇒ 默认 **关**，只在诊断时打开。
 * 门控放在 [noteInternal] **最前面**（在算间隔之前就返回 `null`）：
 * 关掉时既不累计、也不建基线，且**不产生任何字符串** ——
 * 否则"关掉日志"这件事本身仍在每帧做字符串拼接（本项目记过 `render()` 里的
 * 格式化是**每几千帧一次**才敢做的，每秒一次同样不该在关掉时还付）。
 *
 * ⚠️ 关 → 开的那一刻，[hasBaseline] 已为 `false` ⇒ 从"打开后的第一帧"起算窗口，
 *   **不会**把关闭期间的时间算成一个超长窗口（那会报出一个荒谬的低帧率）。
 *   这正是 [reset] 在门控关闭时被顺手调用的理由。
 *
 * ## 为什么是 `public` 而不是 `internal`（与 [FramePacingMonitor] 不同）
 *
 * [FramePacingMonitor] 是 `internal`，且本工程**没有**配 `friendPaths`
 * ⇒ **探针源集访问不到它** ⇒ 它**从未被任何探针覆盖**。
 *
 * 本类要产出的是**会被当作验收依据读的数字**（"每秒几帧"），
 * 一旦算错（窗口算成 2 秒 / 平均算成总和 / 首次调用就报假行），
 * 用户会拿着**错的数字**判成败 —— 这正是本项目反复栽过的形态。
 * 故本类**刻意放宽为 `public`**，让 `PerSecondMeterProbe` 能真正覆盖它。
 *
 * ★ 代价如实登记：多一个公开符号。**用可测性换 API 面**，本项目已有先例
 *   （`L3BackdropState` / `L3CaptureWorker` 同为 public，理由同此）。
 *
 * @param label 日志里显示的名字（"动画帧" / "L3采集"）。
 * @param unit 次数的单位（"帧" / "轮"）。
 */
class PerSecondMeter(
    private val label: String,
    private val unit: String = "次",
) {

    /**
     * 是否启用。**默认 `false`**（用户 2026-09-28：这是"测试用"的功能）。
     *
     * 由调用方在**设置变化时**推入（与项目"每次变更推一次，而不是每帧读设置"的约定一致）。
     * 从 `true` 改为 `false` 时**顺手 [reset]**，避免"关掉再打开"后
     * 第一个窗口的起点还停在很久以前。
     */
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // ★ 关掉时丢弃半截窗口；打开时也复位一次 —— 两种情况都该从"现在"起算。
            reset()
        }

    /** 本窗口的起始时刻（纳秒）。仅当 [hasBaseline] 为 `true` 时有意义。 */
    private var windowStartNanos = 0L

    /**
     * 是否已建立基线（本窗口起点）。
     *
     * ★★ **刻意用独立的布尔量，而不是"`windowStartNanos == 0L`"当哨兵** ——
     * `0L` 是 `System.nanoTime()` 的**合法返回值**（且探针为了伪造时间轴
     * **必然**会传 0 进来）⇒ 用 0 当哨兵会与合法值冲突：
     * 首个调用传 `nowNanos = 0` 时，`windowStartNanos` 被赋 0 ⇒ **下一次调用
     * 仍认为"还没建基线"** ⇒ **永远不出行**（静默失效，且只在特定输入上出现）。
     *
     * 这是 `PerSecondMeterProbe` ①c 当场抓到的（**原记**：用 0 当哨兵够了；
     * **实为**：与合法值冲突 —— 取证：探针传 `nowNanos = 0L` 建基线后永不触发出行）。
     */
    private var hasBaseline = false

    /** 本窗口内的调用次数。 */
    private var count = 0

    /** 本窗口内累计耗时（纳秒）—— 用 `Long` 累计，避免逐次 `Double` 累计误差。 */
    private var totalNanos = 0L

    /** 本窗口内单次最大耗时（纳秒）。 */
    private var maxNanos = 0L

    /** 已输出的窗口序号（从 1 开始）。诊断用：判"日志是不是连续的"。 */
    var windowIndex: Long = 0L
        private set

    /** 本窗口的期望频率（Hz）。0 = 未知（不报"达成率"）。 */
    private var expectedHz = 0.0

    /** 上一次 `note` 的时刻（纳秒）。仅当 [hasLast] 为 `true` 时有意义。 */
    private var lastNanos = 0L

    /** 是否已有"上一次时刻"（用于算间隔）。★ 同 [hasBaseline]：不用 `0L` 当哨兵。 */
    private var hasLast = false

    /** 上一次 `note` 的间隔（纳秒）—— 供调用方取用（如"本帧帧间隔"）。 */
    var lastIntervalNanos: Long = 0L
        private set

    /** 上一次输出的那一行（诊断 / 探针用）。`null` = 还没输出过。 */
    var lastLine: String? = null
        private set

    /**
     * 记一次。**每次调用都要调**（无论这次有没有"实际耗时"可报）。
     *
     * @param nowNanos 本次的当前时刻。**调用方取一次 `System.nanoTime()` 传进来** ——
     *   在内部取会把这个方法自身的开销算进去（同 [FramePacingMonitor.onFrame] 的约定）。
     * @param costNanos 本次的**实际耗时**（纳秒）。没有可报的耗时就传 `0`
     *   （它只影响"平均/最大"，不影响"次数/频率"）。
     *   ⚠️ 传 `0` 时**不会**自动用"距上次的间隔"顶替 —— 那是两件事
     *   （"这一帧画了多久" vs "距上一帧多久"），由调用方明确选一个：
     *   要报间隔就把 [lastIntervalNanos] 传进来（见 [noteInterval]）。
     * @param expectedHz 期望频率（Hz）。传 `0` = 不报达成率。
     *   若中途变化（用户改了设置），**以最后一次传入的为准**（本类不追历史）。
     * @param extra 追加到行尾的补充段（如 `"采屏=8.3 模糊=5.9"`）。`null` = 不追加。
     * @return 跨过 1 秒边界时返回该行文本；否则 `null`。**调用方负责 println** ——
     *   本类不碰 `System.err`（同 [FramePacingMonitor]，便于换输出通道）。
     */
    fun note(
        nowNanos: Long,
        costNanos: Long = 0L,
        expectedHz: Double = 0.0,
        extra: (() -> String)? = null,
    ): String? = noteInternal(nowNanos, costNanos, expectedHz, extra, useIntervalAsCost = false)

    /**
     * 记一次，**自动用"距上次的间隔"当耗时** —— 适用于"上报的就是周期"的节拍
     * （动画帧 / 采集轮：前者要报帧间隔，后者要报轮周期）。
     *
     * ## ★★ 为什么不是 `note(…, costNanos = lastIntervalNanos)`
     *
     * **原记**（本类初版）：那样写"等价"，只是少一个方法。
     * **实为**：它**落后一拍**。Kotlin 在**进入 `note` 之前**求值实参，
     * 那时 `lastIntervalNanos` 还是**上一次调用算出的值** ⇒
     * · **首个真实间隔被丢弃**（第一次调用时它还是 `0`）；
     * · 每个样本**整体后移一位** —— 于是"最大"报的是**上一秒的最大**。
     *
     * **取证**：`PerSecondMeterProbe` ⑤ 首轮实测 `平均=20.0ms / 最大=48.0ms`，
     * 而伪造时间轴 `16,16,48,1020` ms 的**正确**答案是 `275.0 / 1020.0`。
     * ★ **红的不是期望，是实现**（按纪律 27 先分类：期望值 275/1020 由算术复核无误）。
     *
     * ⇒ 正确做法：**本次的间隔在 `note` 内部算出来之后**，立刻作为**本次**的 cost。
     *   用 [noteInternal] 的 `useIntervalAsCost` 表达，而不是从外部读字段。
     */
    fun noteInterval(
        nowNanos: Long,
        expectedHz: Double = 0.0,
        extra: (() -> String)? = null,
    ): String? = noteInternal(nowNanos, 0L, expectedHz, extra, useIntervalAsCost = true)

    private fun noteInternal(
        nowNanos: Long,
        costNanos: Long,
        expectedHz: Double,
        extra: (() -> String)?,
        useIntervalAsCost: Boolean,
    ): String? {
        // ★★ 门控在**最前**（在算间隔/建基线之前）：关掉时**零开销** ——
        //   不累计、不建基线、不产生字符串。见类 KDoc 的「`enabled` 门」一节。
        if (!enabled) return null
        // 间隔单独维护：即使本窗口还没满，也要更新（供"本帧帧间隔"读取）。
        val interval = if (hasLast) nowNanos - lastNanos else 0L
        lastNanos = nowNanos
        hasLast = true
        lastIntervalNanos = interval

        if (!hasBaseline) {
            // 首次调用：只建立基线（否则第一个窗口会从"进程启动时刻"算起，得到一个假的长窗口）。
            windowStartNanos = nowNanos
            hasBaseline = true
            this.expectedHz = expectedHz
            return null
        }
        // ★ 这里是"本次"的 cost（注意 `useIntervalAsCost` 用的是**刚算出的** `interval`，
        //   而不是字段 —— 字段是给外部读的，内部必须用局部量，否则又是"落后一拍"）。
        val effectiveCost = if (useIntervalAsCost) interval else costNanos
        count += 1
        if (effectiveCost > 0L) {
            totalNanos += effectiveCost
            if (effectiveCost > maxNanos) maxNanos = effectiveCost
        }
        if (expectedHz > 0.0) this.expectedHz = expectedHz

        val elapsedNanos = nowNanos - windowStartNanos
        if (elapsedNanos < WINDOW_NANOS) return null

        val line = render(elapsedNanos, extra)
        // ── 复位到新窗口。**基准取 `nowNanos` 而不是 `windowStart + 1s`**：
        //    后者在"调用节拍 > 1 s"时会累积欠账，且会把窗口标成"整 1 秒"
        //    而实际样本只覆盖了其中一小段 —— 那是**谎报窗口长度**。
        windowStartNanos = nowNanos
        count = 0
        totalNanos = 0L
        maxNanos = 0L
        lastLine = line
        return line
    }

    /** 显式复位（节拍停止时用：窗口销毁 / 线程停止）。 */
    fun reset() {
        windowStartNanos = 0L
        hasBaseline = false
        lastNanos = 0L
        hasLast = false
        lastIntervalNanos = 0L
        count = 0
        totalNanos = 0L
        maxNanos = 0L
    }

    /**
     * 出这一行。**字段顺序固定**（序号 / 次数 / 实得 / 平均 / 最大），
     * 这样 `grep` 与人工判读都不必猜位置。
     *
     * ⚠️ 平均值的分母是 **`count`**（本窗口内的调用次数），
     * 而不是"有耗时的次数" —— 想让"没耗时的调用"稀释平均时，
     * 传 `costNanos = 0` 即可（这是刻意的：**"有多少次"与"那几次多慢"必须能分开读**）。
     */
    private fun render(elapsedNanos: Long, extra: (() -> String)?): String {
        windowIndex += 1L
        val seconds = elapsedNanos / 1_000_000_000.0
        val hz = if (seconds > 0.0) count / seconds else Double.NaN
        val avgMs = if (count > 0) totalNanos / 1_000_000.0 / count else 0.0
        val maxMs = maxNanos / 1_000_000.0

        val head = StringBuilder()
        head.append("[CDL] 帧率（").append(label).append("）：序号=")
            .append(windowIndex)
            .append(" 1秒内=").append(count).append(' ').append(unit)
            .append(" 实得=").append(fmt(hz)).append("Hz")
            .append(" 平均=").append(fmt(avgMs)).append("ms")
            .append(" 最大=").append(fmt(maxMs)).append("ms")
            .append(" 窗口=").append(fmt(seconds * 1000.0)).append("ms")

        if (expectedHz > 0.0) {
            val ratio = if (expectedHz > 0.0) hz / expectedHz * 100.0 else Double.NaN
            head.append(" 期望=").append(fmt(expectedHz)).append("Hz")
                .append(" 达成=").append(fmt(ratio)).append('%')
        }
        extra?.let {
            runCatching { head.append(' ').append(it()) }
        }
        return head.toString()
    }

    private companion object {
        /** 窗口长度：**正好 1 秒**（用户要求"每秒打印"）。 */
        const val WINDOW_NANOS: Long = 1_000_000_000L

        /** 保留 1 位小数。`NaN` 显示为 `n/a`（避免日志里出现 `NaN`）。 */
        fun fmt(v: Double): String =
            if (!v.isFinite()) "n/a" else String.format(java.util.Locale.ROOT, "%.1f", v)
    }
}
