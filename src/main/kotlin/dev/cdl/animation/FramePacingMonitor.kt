// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.animation

/**
 * **帧节拍监视器** —— 在**宿主进程内**测量真实帧周期（`recon/54` §5.2 的 **C2**）。
 *
 * ## 它回答什么（这是它存在的唯一理由）
 *
 * `recon/54` §7 的 C0 探针在 **Gradle 探针 JVM** 里实测出：`javax.swing.Timer` 的投递节拍被
 * **量化到 15.625 ms 的整数倍**（6/15 ms → 1×、16 ms → 2×、33 ms → 3×、100 ms → 7×），
 * ⇒ `animation.frame_rate` 的 **5 个选项只产出 2 种实得帧率**（32 / 64 Hz），
 * **与标签相符的选项数 = 0**。
 *
 * **⚠️ 但那一整套数字有一个未堵的缺口**：15.625 ms 是**进程级**的系统定时器精度产物。
 * **若宿主（SPW，媒体播放器）自己调用过 `timeBeginPeriod(1)`，宿主内的量子可能是 1 ms。**
 * ⇒ **C0 的结论不能直接搬到宿主。** 本类就是去宿主里取真值的那只温度计。
 *
 * ## ★★ 判据纪律（`recon/54` §5.3）—— 本类**只产出一行日志，不含任何断言**
 *
 * **时间只作报告，断言必须结构性。** 帧周期是**机器相关**的量：
 * 它随 CPU 负载、GC、屏幕刷新率、宿主行为而变。把它写成断言，
 * 换一台机器（或同一台机器上一边播放一边编译）就会**假红** ——
 * 属于"不可复现的红"。故本类**只 `System.err.println` 一行摘要**，
 * 由人（或文档）去读，**永不让它参与门禁判定**。
 *
 * ## 为什么搭在 `onFrameTick` 上，而**不是**自己起定时器
 *
 * 这是硬约束，不是偏好：
 *
 * 1. **帧循环只能有一个**（`AnimationRuntime` 的类 KDoc 已论证）。再起一个 `Timer`
 *    就会加进 EDT 的争用名单 —— 而**EDT 争用本身会改变帧间隔**（C0 实测：
 *    16 ms 请求 + 24 ms 负载，投递从 30.97 ms **变成** 24.19 ms）。
 *    ⇒ **新起定时器 = 自己污染自己要测的量。**
 * 2. 挂载点在**未被任何东西计时的空档**上：`onFrameTick` 的入口处取一个时间戳，
 *    相减即得"上一帧到现在"的真实间隔（含 EDT 排队）。
 *
 * ## 为什么每帧**零分配**（这一点不是优化，是纪律）
 *
 * 本类要测的正是"帧间隔"，而**分配会引来 GC，GC 会拉长帧间隔** ——
 * 一个每帧分配的实现会**制造它自己要观测的现象**。
 * ⇒ 故：[onFrame] 只做"写一个 `LongArray` 槽位 + 一个 `IntArray` 槽位 + 自增"，
 * **不装箱、不建对象、不格式化**（格式化只发生在摘要时，而摘要是每几千帧一次）。
 *
 * > 这条与 `recon/54` §7.4 / §3 R1 直接相关：项目**已经**因为"每帧新建渐变"
 * > 修过一次真实的卡顿（`SpectrumTipFadeBrushCache`）。本类刻意不重复那个错误。
 *
 * ## 输出频率与去噪
 *
 * 项目现有的诊断输出只有两种模式：**"变化触发"**（如 `logColorSourceDiag`）与
 * **"一次性哨兵"**。本类引入**第三种：按帧数触发的滚动摘要**
 * （`recon/54` §2.3 已记：这个模式此前**没有先例**，故必须在这里把约定写清）。
 *
 * | 触发 | 何时 |
 * | --- | --- |
 * | 滚动摘要 | 累计 [EMIT_EVERY_FRAMES] 帧（≈40 s @60 fps） |
 * | 收尾摘要 | [flush]（帧驱动停止时调用） |
 *
 * 两个阈值都保证**最多几十秒一行**，不会刷屏（1 MB 日志的轮转上限见 `PluginLog`）。
 *
 * ## 与其它诊断的关系
 *
 * [flush] 产出的行里**同时**给出了 `请求` 与 `实得`，于是它**自证**了
 * `recon/54` §7.3 的选项矩阵在**宿主**里是否同样成立 —— 不需要额外工具。
 *
 * @param capacity 环形缓冲容量（样本数）。满后**停止累积**（不覆盖），
 *   以保证摘要是"最近一段连续帧"而不是"被覆盖过的混合"。上限见 [CAPACITY]。
 */
internal class FramePacingMonitor(private val capacity: Int = CAPACITY) {

    /** 帧间隔（纳秒），按到达顺序。 */
    private val intervalsNs = LongArray(capacity)

    /** 每个样本**当时**的请求间隔（毫秒）。**必须逐样本存** ——
     * 用户可能在会话中途改 `animation.frame_rate`，用"摘要时刻的请求值"去判
     * 整段样本是否迟帧会得出错误结论。 */
    private val requestsMs = IntArray(capacity)

    private var n = 0

    /** 上一帧的时刻（纳秒）。`0` = 尚无基线（首帧不产出样本）。 */
    private var lastNanos = 0L

    /**
     * 记一帧。**每帧调用，必须零分配。**
     *
     * @param nowNanos 本帧入口时刻（调用方取一次 `System.nanoTime()`，**不要在这里取** ——
     *   那样会把本方法自身的耗时算进间隔）。
     * @param requestMs 本帧**当时**的请求间隔（`AnimationRuntime.frameIntervalMs`）。
     * @return 需要输出摘要时返回该行文本；否则 `null`。调用方负责 `println`。
     *   **返回值而不是自己打印**：本类不碰 `System.err`，便于将来换输出通道
     *   （也让它不与 `PluginLog` 的安装时机耦合）。
     */
    fun onFrame(nowNanos: Long, requestMs: Long): String? {
        val previous = lastNanos
        lastNanos = nowNanos
        if (previous == 0L) return null
        if (n < capacity) {
            intervalsNs[n] = nowNanos - previous
            requestsMs[n] = requestMs.toInt()
            n++
        }
        return if (n >= EMIT_EVERY_FRAMES) summarize("滚动") else null
    }

    /**
     * 收尾：把当前累积的样本出一行摘要并清空。
     *
     * 调用时机：**帧驱动停止时**（`IslandWindow.stopFrameDriver`）。
     * 样本不足 [MIN_SAMPLES] 时**直接丢弃**（返回 `null`）——
     * 播放后立刻暂停会产出极短样本，"3 帧的 p95"没有意义却会被当成事实读。
     */
    fun flush(label: String): String? {
        if (n < MIN_SAMPLES) {
            reset()
            return null
        }
        return summarize(label)
    }

    /** 丢弃全部样本（窗口销毁 / 复位时用）。 */
    fun reset() {
        n = 0
        lastNanos = 0L
    }

    /**
     * 出摘要。**会重置缓冲**（摘要即窗口边界）。
     *
     * 实现顺序是刻意的：**先从原数组一次性算完"逐样本统计"（min / max / 迟帧数），
     * 再排序求分位数** —— 因为排序会打乱"间隔 ↔ 请求"的配对，
     * 排序后再统计迟帧会拿错请求值。
     */
    private fun summarize(label: String): String {
        val count = n
        val sorted = LongArray(count)
        System.arraycopy(intervalsNs, 0, sorted, 0, count)

        var minimum = Long.MAX_VALUE
        var maximum = 0L
        var late = 0
        var request = 0
        var requestChanged = false
        for (i in 0 until count) {
            val value = intervalsNs[i]
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
            val current = requestsMs[i]
            if (i == 0) request = current else if (current != request) requestChanged = true
            // 「迟帧」= 超过了请求值的 1.5 倍。阈值取 1.5 而不是 1.0：
            // 投递本来就被允许比请求晚一点（`Timer` 的语义是「至少这么久」），
            // 用 1.0 会把正常的调度抖动全算成迟帧。
            if (value > current * 1_500_000L) late++
        }
        reset()
        sorted.sort()

        fun percentile(q: Double): Double =
            sorted[((count - 1) * q).toInt().coerceIn(0, count - 1)] / 1_000_000.0

        val p50 = percentile(0.50)
        val hz = if (p50 > 0.0) 1000.0 / p50 else Double.NaN
        val latePercent = late * 100.0 / count
        val requestText = if (requestChanged) "请求=${request}ms(会话内变过)" else "请求=${request}ms"
        return ("[CDL] 帧节拍（%s）：%s 样本=%d p05=%.1f p50=%.1f p95=%.1f p99=%.1f " +
            "min=%.1f max=%.1f ms ⇒ 实得=%.0fHz 迟帧率=%.1f%%")
            .format(
                label, requestText, count,
                percentile(0.05), p50, percentile(0.95), percentile(0.99),
                minimum / 1_000_000.0, maximum / 1_000_000.0, hz, latePercent,
            )
    }

    private companion object {
        /** 环形缓冲容量。8192 帧 ≈ 2.3 min @60 fps —— 足够看出分布，又不至于肥大。 */
        const val CAPACITY = 8192

        /** 滚动摘要的间隔帧数。2400 ≈ 40 s @60 fps / 17 s @144 请求。 */
        const val EMIT_EVERY_FRAMES = 2400

        /** 出摘要所需的最少样本。低于它说明这不是一段"连续播放"，不值得报。 */
        const val MIN_SAMPLES = 30
    }
}
