// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

/**
 * 渲染线程每帧读取的**不可变快照**。
 *
 * ## 为什么是值对象，而不是共享一个可变的时间轴对象
 * 本插件的写入方是**宿主回调线程**，读取方是 **EDT / 渲染线程**，两边并发。
 * 若共享一个可变对象，就必须用锁保护它 —— 而那把锁会**跨越回调边界**：
 * 回调持锁写入时，若渲染线程正好持锁读取，回调线程就会阻塞在宿主自己的调用栈里。
 * 宿主的回调是串行的，阻塞它会拖住整个播放器（严重时死锁宿主）。
 *
 * 快照模型把这件事化掉：渲染侧每次调 `snapshot()` **现算**出一个不可变快照
 * （逐字段从 `@Volatile` 字段读），因此**一个锁都不需要**
 * （只用 `@Volatile` 保证各字段发布的可见性）。
 *
 * ⚠️ **2026-09-24 更正**：原文写「回调侧…**发布一个新的不可变快照引用**，
 *   渲染侧**一次性取走引用**…持有的是私有副本」—— **那套机制从未落地**。
 *   实测 `PlaybackTimeline` 里 `snapshotRef` **只有 `publish()` 一处写入、无任何读取点**；
 *   渲染层走的是 `snapshot()` 的**现算**路径（见 `PlaybackTimeline` 里那段自述）。
 *
 * ## 为什么要有 [EMPTY]
 * 首帧渲染必然早于第一次宿主回调。若此时没有快照，渲染路径就得每帧做 null 判断，
 * 且很容易在某个分支漏判而 NPE 崩掉整个词岛。用一个全空的合法快照作为初始值，
 * 把"没有数据"变成一种**正常状态**而非异常状态，渲染代码因此得以保持无分支直白。
 */
data class TimelineSnapshot(
    /** 曲目标题；未知时为空串（不用 null，省掉渲染侧的空判断）。 */
    val trackTitle: String,
    /** 艺术家；未知时为空串。 */
    val artist: String,
    /** 专辑；未知时为空串。 */
    val album: String,
    /**
     * 音频文件绝对路径；未知时为空串。
     *
     * 来源：宿主 `PlaybackExtensionPoint.MediaItem.path`（已反编译取证该字段确切存在，
     * 类型 `java.lang.String`）。这个词用于"按曲目定位封面 / 缓存"等需要**稳定身份**
     * 的场景 —— 标题与艺术家会重复（同名歌曲、同专辑多版本），路径才是可用主键。
     */
    val path: String,
    /**
     * 是否正在播放。
     *
     * ⚠️ **2026-09-24 更正**：原文写「这是『宿主明确告知的播放态』，**不是外推推断出来的**」——
     *   **不成立**。实测 `PlaybackTimeline.isEffectivelyPlaying` 在宿主未明确告知时
     *   **会叠加心跳推断**（`playingSignalObserved == false` 时看心跳新鲜度）。
     *   该字段单独并不可信；渲染侧真正的判断用的是 `isEffectivelyPlaying`。
     */
    val isPlaying: Boolean,
    /**
     * 当前位置（毫秒），**已含外推**。
     *
     * 为什么不是"最后一次回调给的值"：`onPositionUpdated` 的调用频率远低于渲染帧率
     * （原实现实现注释为 "the host's one-second clock"，即约 1 秒一次）。若直接使用回调值，
     * 位置会以 1 秒为台阶跳变，逐字高亮表现为"一秒一跳"，完全不像在唱歌。
     * 因此时间轴用单调时钟在两次回调之间外推，快照里给出的是外推后的平滑值。
     */
    val positionMs: Long,
    /**
     * 当前歌词行下标；`-1` 表示"无当前行"（早于第一行，或歌词为非同步的纯文本）。
     *
     * 保留 -1 而不是用 null/0 代替：0 是合法下标，"第一行"与"没有行"必须能区分，
     * 否则纯文本歌词会错误地高亮第一行。
     */
    val currentLineIndex: Int,
    /** 上一行（三槽位模型的上一槽）；无则为 null。 */
    val prevLine: LyricLine?,
    /** 当前行；无则为 null。 */
    val currentLine: LyricLine?,
    /** 下一行（三槽位模型的下一槽）；无则为 null。 */
    val nextLine: LyricLine?,
    /** 当前行的卡拉 OK 进度 0f..1f；[currentLine] 为 null 时是 0f。 */
    val currentLineProgress: Float,
    /**
     * 歌词文档的版本号，每次歌词被替换（换曲 / 重新载入歌词）时自增。
     *
     * ## 为什么用版本号而不是让渲染层比较歌词列表
     * 渲染层需要知道"歌词换了吗"来决定是否重建文本布局缓存（字形测量、折行、宽度计算
     * 都是贵的）。若每帧去比较新旧列表，有两个问题：
     * 1. 比较是 O(n)，而这是**每帧**的开销（60fps 下每秒 60 次），纯浪费；
     * 2. 更关键的是它**漏判**：新曲目的歌词可能与旧曲目在文本上完全相同
     *    （同一句副歌、或同一首歌的不同版本），但时间轴不同，必须重建。
     * 一个单调自增的计数器把这件事变成 O(1) 的整数值比较，且不会漏判。
     */
    val docVersion: Long,
    /**
     * ★★ 2026-09-28 新增：当前歌词文档里**总共有多少行**（含尚未唱到的）。
     *
     * ## 为什么必须有它（用户报障「无歌词时的显示逻辑混乱，判定不准确(时显时不显)」）
     *
     * 「本曲没有歌词」与「还没唱到第一行」在 `currentLine == null` 上是**完全无法区分**的
     * （见 `LyricDocument.lineAt`：位置早于第一行时同样返回 `-1`）。原实现只判
     * `currentLine == null`，于是**每一首歌的开头几秒**都被当成"没有歌词"
     * —— 用户看到「暂无歌词 / 曲名」闪一下、第一行来了又消失（= "时显时不显"）。
     *
     * 本字段把两种状态分开：`lyricLineCount == 0` ⇒ 真的没有歌词；`> 0` 且
     * `currentLine == null` ⇒ 只是还没唱到（或 seek 到了第一行之前）。判据见
     * [dev.cdl.window.noLyricsDisplay]。
     *
     * 缺省 `0` 让既有的 `EMPTY` 与探针构造点无需改动（它们本就表达"没有歌词"）。
     */
    val lyricLineCount: Int = 0,
) {
    companion object {
        /**
         * 初始空快照。见类注释中"为什么要有 EMPTY"。
         * `currentLineIndex = -1` 与 `currentLine = null` 二者一致，表示"无当前行"。
         */
        val EMPTY = TimelineSnapshot(
            trackTitle = "",
            artist = "",
            album = "",
            path = "",
            isPlaying = false,
            positionMs = 0L,
            currentLineIndex = -1,
            prevLine = null,
            currentLine = null,
            nextLine = null,
            currentLineProgress = 0f,
            docVersion = 0L,
            lyricLineCount = 0,
        )
    }
}
