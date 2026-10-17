// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

/**
 * 内存读数 —— **只写日志，不参与任何判定**。
 *
 * ## 为什么需要它（2026-10-18 用户报「开启本插件后内存占用 20-100+ M」）
 *
 * 在这之前，插件对"自己用了多少内存"**完全没有读数**：
 * 用户看到的是任务管理器里的进程工作集，而我们无法区分
 * 「JVM 堆涨了」「缓存压着位图」「只是 JIT / 类加载」这三类完全不同的原因。
 *
 * 没有读数时的任何优化都是猜。本类先把**堆已用 / 已提交 / 上限 / 线程数**
 * 打成一行，让下一次报障能直接定位。
 *
 * ## 为什么放在帧循环里调用（而不是新起一个线程 / Timer）
 *
 * 本项目已实测：**多一个调度源会改帧节拍**（`MEMORY` 纪律 25 / `recon/54`）。
 * 故本类**不持有线程、不持有 Timer**，只由 `IslandWindow.onFrameTick` 顺手调一次；
 * 判定成本是**一次 Long 比较**（未到 60 s 立即返回）。
 *
 * ## 为什么"变化 < 4 MB 不打"
 *
 * 堆已用在稳定播放时会小幅抖动（GC 前后、临时对象）。每 60 s 无条件打一行
 * 会让日志变成噪声，反而没人看。只记**有意义的变化** + 启动基线。
 *
 * ## 读数怎么解读
 *
 * | 现象 | 含义 |
 * |---|---|
 * | 已用稳定在某个值，切歌时短时上扬后回落 | 正常：封面解码等临时分配，GC 已回收 |
 * | 已用**单调上升**、长时间不回落 | 疑似持有未释放（缓存 / 监听器 / 静态集合） |
 * | 已提交远大于已用 | JVM 保留但未使用，通常不必处理（GC 会按需归还） |
 * | 上限远大于已用 | 不是"内存不够"，是"占用变多" —— 优化目标是**降低已用**，不是调大上限 |
 */
object MemoryReport {

    /** 两次读数的最小间隔（纳秒）。 */
    private const val INTERVAL_NANOS = 60_000_000_000L

    /** 字节 → MB。 */
    private const val MB = 1024L * 1024L

    /** 上次读数时刻（0 = 还没读过 ⇒ 下一次调用立刻打基线）。 */
    private var lastAtNanos = 0L

    /** 上次报告的已用 MB（-1 = 还没报过）。 */
    private var lastUsedMb = -1L

    /**
     * 到点就打一行；否则立即返回。
     *
     * @param nowNanos 调用方**当前这一帧**的时刻（与帧节拍共用，不另取时间）。
     */
    fun noteIfDue(nowNanos: Long) {
        if (lastAtNanos != 0L && nowNanos - lastAtNanos < INTERVAL_NANOS) return
        lastAtNanos = nowNanos
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val committedMb = runtime.totalMemory() / MB
        val maxMb = runtime.maxMemory() / MB
        // 启动基线一定打；之后只有变化 ≥ 4 MB 才打。
        if (lastUsedMb >= 0L && kotlin.math.abs(usedMb - lastUsedMb) < 4L) return
        lastUsedMb = usedMb
        System.err.println(
            "[CDL] 内存：已用=${usedMb}MB 已提交=${committedMb}MB 上限=${maxMb}MB " +
                "线程=${Thread.activeCount()}"
        )
    }

    /** 供探针复位（避免上一条用例的窗口污染下一条）。 */
    fun resetForTest() {
        lastAtNanos = 0L
        lastUsedMb = -1L
    }
}
