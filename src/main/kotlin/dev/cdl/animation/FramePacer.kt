// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.animation

/**
 * 帧节拍的**绝对截止期**驱动器 —— 第 1 期 ⑦ 的本体。
 *
 * ## 它修的是什么（实测取证，见 `02-第1期执行记录` §10.2）
 *
 * `javax.swing.Timer` 用**固定延迟**重复时，实测周期恒为 `delay + δ`，其中 δ 是
 * `TimerQueue` 的投递延迟（本机 ≈1.08 ms）。于是"请求 16.667 ms"实得 **17.751 ms**：
 *
 * | 目标 | `javax.swing.Timer`（固定延迟） | STPE / `parkNanos` 截止期循环 |
 * | --- | --- | --- |
 * | 6.944 ms（144 Hz 真值） | 7.626 ms · **超发 ×1.10** | 7.022 ms · **×1.01** |
 * | 16.667 ms（60 fps 真值） | 17.751 ms · **超发 ×1.07** | 16.652 ms · **×1.00** |
 *
 * 关键观察：**超发是"恒定偏移、不累积"**（两个目标的 ×1.07 / ×1.10 几乎同值）
 * ⇒ 它来自投递延迟，**不是周期误差**。所以只要让 `delay` 随每帧**重算**为
 * "距下一个**绝对**截止期还有多久"，就有：
 *
 * ```
 * scheduled(n+1) = actual(n) + delay(n)，  delay(n) = deadline(n+1) − actual(n)
 * ⇒ actual(n+1) = deadline(n+1) + δ
 * ⇒ 周期 = deadline(n+1) − deadline(n) = 请求间隔   ← 精确
 * ```
 *
 * δ 仍在，但它退化成**恒定滞后**（每帧都晚 δ）—— 恒定滞后不产生 judder，也不漂移。
 *
 * ## 为什么不是"换掉 `javax.swing.Timer`"
 *
 * 换成 `parkNanos` 截止期循环只多拿 0.07 ms 的精度，却要付出：改线程模型、
 * 重新论证 `onFrameTick` 的 EDT 语义、处理帧回调积压。而本机主屏 120 Hz 的
 * **刷新周期是 8.3333 ms（非整数毫秒）** ⇒ 那 0.07 ms 在刷新网格前**没有意义**
 * （§10.3 的对照表：即使量子降到 1 ms，对齐仍几乎全 ✗）。
 * 所以本类**只做截止期修正，不动驱动本身** —— EDT 语义、"帧循环唯一"、
 * 以及 `IslandWindow` 全部既有调用点（`isRunning` / `restart()` / `delay =`）都不变。
 *
 * ## 时间接缝（D24 ④）
 *
 * 取时间**只**经由构造参数 [clockNanos]。探针可以喂**绝对小时间戳**（甚至纯假时钟）
 * 来断言"第 n 帧的截止期恰好是 `epoch + n × interval`" —— 若直接调
 * `System.nanoTime()`，喂小时间戳时进度会恒为 0（这条坑本项目踩过）。
 *
 * ## 用法（三步，缺一不可）
 *
 * 1. 起驱动 / 因离散事件重排节拍时 → [reset]；
 * 2. 每帧回调**最开头** → [onFrameDelivered]（必须在做本帧工作**之前**，
 *    这样 `delay` 与"本帧耗时"无关）；
 * 3. 把 `Timer.delay` 设为 [delayToNextMs]。
 *
 * @param intervalMs 请求的帧间隔（毫秒）。**每次读取**，故设置项改动能立刻生效。
 * @param clockNanos 单调时钟（纳秒）。默认 `System::nanoTime`。
 */
class FramePacer(
    private val intervalMs: () -> Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    /** 第 0 帧的绝对起点（纳秒）。 */
    private var epochNanos = 0L

    /** 自 [epochNanos] 起**已投递**的帧数。 */
    private var frameIndex = 0L

    /**
     * 把截止期序列的起点与计数复位到**现在**。
     *
     * 调用时机（三处，都在 EDT 上）：
     * · 驱动**启动**时；
     * · **离散事件**（换曲 / seek）立刻重绘时 —— 那是一次不连续，旧截止期序列无意义；
     * · `animation.quality` 改帧间隔时 —— 间隔变了，旧序列同样无意义。
     */
    fun reset() {
        epochNanos = clockNanos()
        frameIndex = 0L
    }

    /**
     * 记一帧已投递。**必须在帧回调的最开头调用**。
     *
     * 放在开头（而不是结尾）是为了让 [delayToNextMs] 的结果**与本帧耗时无关**：
     * 若放在结尾，一帧画得慢就会把下一帧的截止期推后，慢帧会被放大成抖动。
     */
    fun onFrameDelivered() {
        frameIndex++
    }

    /** 下一个截止期的绝对时刻（纳秒）。 */
    fun nextDeadlineNanos(): Long =
        epochNanos + (frameIndex + 1) * intervalMs() * NANOS_PER_MILLI

    /**
     * 距下一个截止期还有多少毫秒（**已向下取整**）。
     *
     * 取整方向刻意选"向下"：宁可早一格到（Swing 的 `delay` 本来就是**不早于**语义，
     * 早到会被 `TimerQueue` 推到下一个 tick），也不要晚一整毫秒。
     *
     * 结果夹在 `[minMs, maxMs]`：下限 1 ms 避免把 EDT 空转；
     * 上限 [MAX_DELAY_MS] 是**兜底** —— 若时钟被注入成跳变值，不至于把下一帧排到天荒地老。
     */
    fun delayToNextMs(): Int {
        val remainingNanos = nextDeadlineNanos() - clockNanos()
        val ms = Math.floorDiv(remainingNanos, NANOS_PER_MILLI)
        return ms.toInt().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /** `Timer.delay` 下限：1 ms（`Timer` 的合法最小间隔）。 */
        const val MIN_DELAY_MS = 1

        /** `Timer.delay` 兜底上限（毫秒）。正常路径永远到不了它。 */
        const val MAX_DELAY_MS = 1_000
    }
}
