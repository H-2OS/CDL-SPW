// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import dev.cdl.platform.LocalTrackDuration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 曲目时长的**后台加载器** —— 把 `LocalTrackDuration.read` 的磁盘 I/O 挪出 EDT。
 *
 * 参照 0.8.9 的 `host/TrackMetadataLoader.kt`（单一工作线程 + latest-wins + 代次校验），
 * 但**只做时长**，不含封面与标签 —— 本插件的封面由 `cover/CoverArtProvider` 独立负责，
 * 在这里一并读会是同一份数据的第二处实现。
 *
 * ## 为什么必须是后台线程（不是"优化"，是硬要求）
 *
 * `jaudiotagger` 解析文件要**读磁盘**并解压/扫描帧头。本地 SSD 上约几毫秒，
 * 但**网络盘 / 机械盘 / 大 flac** 上可以到百毫秒级 —— 而调用点在宿主回调线程
 * （换曲事件到来时），那里阻塞会拖住建在宿主回调链上的整个播放器。
 * 更关键的是：宿主回调链**串行**，卡住它 = 卡住播放器的切歌。
 *
 * ## 为什么是"单一工作线程 + 队列长度 1 + 丢弃最旧"
 *
 * 用户快速连点"下一首"时会瞬间产生多次请求。若用无界队列，每个请求都排队，
 * 而**只有最后一个的时长有意义**（前面几首已经不在播了）—— 那是白烧磁盘。
 * [ThreadPoolExecutor.DiscardOldestPolicy] + `ArrayBlockingQueue(1)` 让
 * "最新的请求挤掉排队中的旧请求"，工作线程永远只处理"当前想要的那个"。
 *
 * 队列长度取 1 而不是更大：多排一个请求就意味着多读一个**已经过期的文件**。
 *
 * ## 为什么还要"代次校验"（`DiscardOldestPolicy` 不够）
 *
 * 丢弃最旧只作用于**还没开始执行**的任务。已经在跑的那个任务无法被挤掉，
 * 而它可能正是用户已经切走的那首歌 —— 它跑完后若无条件写入，
 * 就会把**上一首的时长**写到当前曲目上（表现为"进度条总时长是上一首的"）。
 *
 * 故这里用 [token]：每次请求自增，任务完成时比对"我启动时的代次"与"当前代次"，
 * 不等则**丢弃结果**。这与 0.8.9 的 `TrackMetadataLoader` 用的是同一手法
 * （它用 `timeline.trackChanged` 的返回值当代次，本项目没有那套代次机制，
 * 故自带一个）。
 *
 * ## 为什么结果用 [AtomicLong] 而不是 `@Volatile var`
 *
 * 读侧是 EDT 上的绘制路径（每帧一次），写侧是工作线程。`AtomicLong` 提供
 * 无锁的 get/set 且语义明确 —— 用 `@Volatile` 也能保证可见性，
 * 但 `AtomicLong` 让"这是一个跨线程共享的可变值"在类型上就看得出来。
 */
internal class TrackDurationLoader(
    /** 实际读取函数。默认为 [LocalTrackDuration.read]；探针可注入假实现以免碰磁盘。 */
    private val read: (String) -> Long = LocalTrackDuration::read,
) : AutoCloseable {

    /**
     * 工作线程。`daemon = true` 是硬要求：插件若被卸载而线程还在，
     * 非守护线程会让宿主进程无法退出（用户看到的症状是"关了播放器进程还在"）。
     */
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { task -> Thread(task, "SPW Island track duration").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    /** 当前结果（毫秒）。`0` = 未知/尚未读完。 */
    private val durationMs = AtomicLong(0L)

    /** 当前请求的路径 —— 用来判断"待读的这首是否就是正在播的那首"。 */
    private val requestedPath = AtomicReference("")

    /** 代次：每次发起新请求自增；结果回来时比对，不等则丢弃。 */
    private val token = AtomicLong(0L)

    @Volatile
    private var closed = false

    /**
     * 请求读取 [path] 的时长。**可从任意线程调用**（实际是宿主回调线程）。
     *
     * 幂等：同一个路径重复请求不会重复读取（宿主可能在一次换曲里发多次 track 事件）。
     *
     * 路径变化时**立即把结果清零** —— 否则新曲目在读完之前的这段时间里，
     * 进度条会拿**上一首的时长**去算 ratio，表现为"刚切歌时进度条猛地跳一下"。
     * 清零的代价只是"读完前显示 `--:--`"，这是诚实的。
     */
    fun load(path: String) {
        if (closed) return
        if (path == requestedPath.get()) return
        requestedPath.set(path)
        durationMs.set(0L)
        // ── 空路径：**必须留下痕迹**（2026-09-16 新增）────────────────────
        //
        // 用户真机反馈「进度条不显示进度和歌曲时长」。这条症状只有两个可能根因：
        //   ① 宿主没给文件路径（`MediaItem.path` 为空）→ 时长**不可得**；
        //   ② 路径有值但读文件失败/格式不支持 → 时长**读不到**。
        // 二者在行为上完全一样（都是 `0`），**但修法完全不同**（① 无解，② 可修）。
        //
        // 原实现两者都不打日志，且空路径会被下面的幂等判断**静默吃掉**
        // （`path == requestedPath.get()` 在首次调用时是 `"" == ""` → 直接 return）。
        // 于是"进度条没时长"在日志里**查无此事** —— 这正是 BUG-017 那一类
        // 「真信号丢失」，比噪声更糟。故这里显式记一次。
        //
        // 频率可控：本方法只在**换曲**时被调用（`CdlRuntime:420`），
        // 且 `requestedPath` 的幂等判断保证同一首只记一次 —— 不会变成噪声。
        if (path.isBlank()) {
            System.err.println(
                "[CDL] 曲目时长：宿主未提供文件路径（path 为空），时长不可得，" +
                    "进度条只画轨道不画填充。这是宿主 API 的边界，不是本插件的缺陷。",
            )
            return
        }
        val mine = token.incrementAndGet()
        // 提交任务时的异常不该传播到宿主回调链上（那会拖住播放器）。
        // 被 `DiscardOldestPolicy` 丢弃时不会抛，但 `submit` 在 `shutdownNow()` 后
        // 会抛 `RejectedExecutionException` —— 那正是"插件正在卸载"的竞态，
        // 静默忽略即可（此时结果也不再有消费者）。
        runCatching {
            executor.execute {
                val value = runCatching { read(path) }.getOrDefault(0L)
                // 代次不符 = 用户已切走 → 丢弃（见类 KDoc 的"为什么还要代次校验"）。
                // `closed` 也要判：卸载后写入一个已无人读的值无害，但语义上应停止工作。
                if (!closed && token.get() == mine) {
                    durationMs.set(value)
                    // 结果也记一次：区分「读到了」与「读了但失败」。
                    // 同样只在换曲时打，不是每帧。
                    if (value > 0L) {
                        System.err.println("[CDL] 曲目时长：${value} ms（$path）")
                    } else {
                        System.err.println(
                            "[CDL] 曲目时长读取失败或为 0（$path）：" +
                                "进度条不画填充。若该文件确实是音频，可能是格式不支持。",
                        )
                    }
                }
            }
        }
    }

    /** 当前已知时长（毫秒）；`0` = 未知。**可从任意线程调用**（EDT 每帧读它）。 */
    fun current(): Long = durationMs.get()

    override fun close() {
        closed = true
        // `shutdownNow()` 中断正在跑的任务。jaudiotagger 不响应中断，
        // 故正在读的那个文件仍会读完 —— 但它的结果会被 `closed` 判断挡掉，
        // 且线程是 daemon，不会阻止进程退出。
        runCatching { executor.shutdownNow() }
    }
}
