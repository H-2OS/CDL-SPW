// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

/**
 * 宿主回调的落地目标（静态桥）。
 *
 * ## 为什么必须是静态桥
 * PF4J 会**独立构造扩展实例**，扩展拿不到插件实例的引用，只能访问静态成员
 * （见 [CdlPlugin.playback]）。
 *
 * ## 为什么必须带缓冲（本类存在的核心理由）
 * 宿主可能在**插件运行时就绪之前**就开始回调 —— 尤其是启动瞬间的
 * `onStateChanged` / `onIsPlayingChanged` / `onLyricsLineUpdated`。
 * 若此时 [target] 仍为 `null` 就把事件丢掉，运行时就绪后**永远等不到那个状态**，
 * 表现为"刚启动时词岛没有歌词、要等下一次切歌才对"这类难查的时序 bug。
 *
 * 因此本类的契约是：
 * 1. 运行时就绪前 → **入队缓冲**（不是丢弃）；
 * 2. [attach] 时 → 按**到达顺序回放**缓冲，再清空；
 * 3. 所有入口 `@Synchronized`，把宿主任意线程的回调串行化。
 *
 * ## 缓冲上界（防内存泄漏）
 * 缓冲不是无界的。若运行时**长时间**无法启动（如渲染初始化失败），宿主仍会持续回调，
 * 无界队列会一直涨。因此设 [MAX_BUFFERED] 上限，超出后**丢最旧的**并计数告警 ——
 * 理由：最新状态才是渲染需要的，旧状态已无意义。
 */
internal class CallbackSink {

    /** 缓冲事件的可辨识联合。新增回调时**必须**在这里加分支并在 [dispatch] 里处理。 */
    private sealed interface Event {
        val kind: String

        data class Track(val item: PlaybackExtensionPoint.MediaItem) : Event {
            override val kind = "track"
        }

        data class State(val state: PlaybackExtensionPoint.State) : Event {
            override val kind = "state"
        }

        data class Playing(val playing: Boolean) : Event {
            override val kind = "playing"
        }

        data class Position(val positionMs: Long) : Event {
            override val kind = "position"
        }

        data class Seek(val positionMs: Long) : Event {
            override val kind = "seek"
        }

        data class Line(val line: PlaybackExtensionPoint.LyricsLine?) : Event {
            override val kind = "line"
        }

        /**
         * 宿主的**整条歌词轴**（`onLyricsLinesUpdated`，API 0.1.0-dev21 新增）。
         *
         * 与 [Line] 是**同一件事的两个粒度**：宿主在 1.19.0 上会把整轴推一次，
         * 之后仍逐行推当前行。两条通道**并存**，谁后到谁生效 ——
         * 这不是冗余，而是降级路径：旧宿主（< 1.19.0）只会有 [Line]。
         */
        data class Lines(val lines: List<PlaybackExtensionPoint.LyricsLine>) : Event {
            override val kind = "lines"
        }
    }

    private val lock = Any()

    /** 运行时就绪前的事件缓冲。仅在 [lock] 内访问。 */
    private val pending = ArrayDeque<Event>()

    /** 因超上界被丢弃的事件数（仅用于告警，不做业务用途）。 */
    private var dropped = 0

    @Volatile
    private var target: CdlRuntime? = null

    /**
     * 绑定运行时并**回放**缓冲事件。
     *
     * 回放在锁内拷贝快照、锁外派发：因为 [CdlRuntime] 的消费者可能反手调用
     * 本类的其它方法（重入），持锁派发会死锁。
     */
    fun attach(runtime: CdlRuntime) {
        val backlog: List<Event>
        synchronized(lock) {
            target = runtime
            backlog = pending.toList()
            pending.clear()
            if (backlog.isNotEmpty()) {
                log("attach", "回放缓冲事件 ${backlog.size} 条" +
                    if (dropped > 0) "（期间因超上界丢弃 $dropped 条）" else "")
            }
        }
        // 锁外派发，避免与运行时内部的重入调用互相等待。
        backlog.forEach { dispatch(runtime, it) }
    }

    fun detach(runtime: CdlRuntime) {
        synchronized(lock) {
            if (target === runtime) target = null
        }
    }

    // ── 宿主回调入口（任意线程） ──────────────────────────────────────────
    // 统一走 offer()：要么交给运行时，要么先入队，绝不静默丢弃。

    fun trackChanged(mediaItem: PlaybackExtensionPoint.MediaItem) =
        offer(Event.Track(mediaItem))

    fun stateChanged(state: PlaybackExtensionPoint.State) = offer(Event.State(state))

    fun playingChanged(playing: Boolean) = offer(Event.Playing(playing))

    fun positionChanged(positionMs: Long) = offer(Event.Position(positionMs))

    fun seek(positionMs: Long) = offer(Event.Seek(positionMs))

    fun lineChanged(line: PlaybackExtensionPoint.LyricsLine?) = offer(Event.Line(line))

    /**
     * 整轴回调入口（`onLyricsLinesUpdated`）。
     *
     * ⚠️ **入队时立刻拷贝一份**：宿主可能复用/清空那个 `List`（它传的是 `java.util.List`，
     * 没有"不可变"的契约）。若原样持有引用，缓冲回放时可能读到**已被宿主改过的**列表，
     * 表现为"回放出来的轴是旧的/空的"，且只在慢启动的机器上偶发 —— 极难查。
     * 拷贝成本是每首歌一次、几百个引用，可以忽略。
     */
    fun linesChanged(lines: List<PlaybackExtensionPoint.LyricsLine>) =
        offer(Event.Lines(ArrayList(lines)))

    /** 就绪 → 直接派发；未就绪 → 入队等待 [attach] 时回放。 */
    private fun offer(event: Event) {
        // ⚠️ 锁内只做两件事：读到 target / 入队，然后用 `ready` 这个**非空**局部变量
        // 把"该派发给谁"带出临界区。注意不能用 `var ready: T? = null` + 智能转换：
        // 赋值发生在 `synchronized { }` 这个 inline lambda 里，Kotlin 不保证它只执行一次，
        // 因此出锁后**不会**做非空智能转换，`dispatch(ready, ...)` 会报实参类型不匹配。
        // 改用"锁内 return、出锁时直接构造非空值"的写法绕开这个限制。
        val ready: CdlRuntime = synchronized(lock) {
            val current = target
            if (current == null) {
                if (pending.size >= MAX_BUFFERED) {
                    pending.removeFirst()
                    dropped++
                }
                pending.addLast(event)
                return
            }
            current
        }
        dispatch(ready, event)
    }

    /**
     * P0 阶段：只打印到 stderr，用于验证"扩展点确实收到了宿主回调"这条链路。
     * P1 起把这里换成交给 `PlaybackTimeline`（保持同一调用点，不改 [offer] 的契约）。
     */
    private fun dispatch(runtime: CdlRuntime, event: Event) {
        val value = when (event) {
            is Event.Track -> "${event.item.title} — ${event.item.artist}"
            is Event.State -> event.state.name
            is Event.Playing -> event.playing.toString()
            is Event.Position -> event.positionMs.toString()
            is Event.Seek -> event.positionMs.toString()
            // ★ 2026-10-18：dev21 新增的**整轴歌词**回调（`Lines`）。
            //   一条事件带整首歌的全部行，逐行 dump 会把日志刷爆（1 MB 轮转），
            //   故只打行数；逐行内容由结构化通道交给时间轴。
            is Event.Lines -> "lines=${event.lines.size}"
            // ⚠️ 歌词行**必须分字段打**（2026-09-17 修）。
            //
            // 原记：`is Event.Line -> event.line?.pureMainText ?: "<null>"`
            // 实为：只打 `pureMainText` **一个字段**，导致一条**关键证据链断裂** ——
            //   它无法回答"宿主到底有没有独立译文通道（`pureSubText`）"这个问题。
            //   排查译文缺陷时，全工作区 grep `pureSubText` / `sub=` / `cells=` **零命中**，
            //   于是"宿主把译文拼进主文本"这个假设**既无法证实也无法证伪**（见 recon/36）。
            //
            // 为什么分字段能定案：宿主给译文有两种形态，日志一打就能区分 ——
            //   · 形态 A（同行拼接）：`main` 里含译文，而 `sub=<null>`
            //   · 形态 B（分行推送）：出现两条 `Line` 事件，其中一条的 `sub` 非空
            // 故这里把三个字段与 cell 数一并打出。**这是后续一切判断的前提。**
            //
            // ── 2026-09-17 第二次增强：把 cells 的**逐项**内容也打出来 ──────────
            //
            // 原记：`cells` 只打数量与首个 cell 的时长。
            // 实为：**"逐字时间轴到底长什么样"是"译文能不能独立逐字"的唯一判据**
            //   （见 `narrowWordsToTranslation` 的 KDoc），而"数量 + 首个 cell"答不了它 ——
            //   分不出下面这两种形态：
            //     · `cells=1` 且区间 == 整行        → 无亚行粒度 → 译文只能整行进度；
            //     · `cells=1` 但区间只是整行的一小段 → 有亚行粒度，但 cells 不覆盖译文。
            //   两者在旧格式下**打印完全相同**。故改为逐项 dump（文本 + 起止毫秒）。
            //
            // `main` 额外带 `len=`：`narrowWordsToTranslation` 的边界计算依赖
            //   「拼接串长度 − 译文长度」，长度一旦对不上（例如宿主 trim 过文本），
            //   译文逐字就会静默退回整行进度 —— 打出来才能在日志里直接算。
            is Event.Line -> event.line?.let { line ->
                val cells = line.lyricsCells
                val dump = cells.take(MAX_LOGGED_CELLS).joinToString(",") {
                    "\"${it.text}\"@${it.startTime}-${it.endTime}"
                }
                val tail = if (cells.size > MAX_LOGGED_CELLS) {
                    ",…（共 ${cells.size} 项，仅列前 $MAX_LOGGED_CELLS）"
                } else {
                    ""
                }
                "main=\"${line.pureMainText}\"(len=${line.pureMainText.length}) | " +
                    "sub=${line.pureSubText?.let { "\"$it\"" } ?: "<null>"} | " +
                    "cells=${cells.size}[$dump$tail] | span=${line.startTime}-${line.endTime}"
            } ?: "<null>"
            // 整轴只打**摘要**：一条日志打成几万字符会把 stderr 镜像文件撑爆，
            // 而且逐行的细节在同一首歌里已经由 `line` 事件打过了。
            // 打"轴长 + 首末行 + 带译文的行数"就是为了回答三个问题：
            //   ① 宿主到底推没推整轴（推了 → 才有本行）；
            //   ② 轴是不是真的覆盖整首（首末行的 startMs 一看就知道是不是只给了当前段）；
            //   ③ 整轴路径上 `pureSubText` 是否仍然全 null（决定能不能省掉书写系统启发式）。
            is Event.Lines -> event.lines.let { lines ->
                if (lines.isEmpty()) {
                    "<empty>"
                } else {
                    val first = lines.first()
                    val last = lines.last()
                    val withSub = lines.count { !it.pureSubText.isNullOrBlank() }
                    "count=${lines.size} | sub!=null=$withSub | " +
                        "first=${first.startTime}ms\"${first.pureMainText.take(LOGGED_AXIS_TEXT).let {
                            if (first.pureMainText.length > LOGGED_AXIS_TEXT) "$it…" else it
                        }}\" | last=${last.startTime}-${last.endTime}ms"
                }
            }
        }
        // ⚠️⚠️ 2026-09-20 修「高亮轴延迟」（用户点名彻查）——
        //
        // ## 原缺陷
        //
        // `log(...)` 是 `System.err.println` → 插件把 stderr **镜像到文件**
        // （`PluginLog.install`），也就是**一次阻塞写盘**。
        // 而它原本排在**结构化通道之前** —— 而时间轴的**位置锚点**正是在结构化通道里取的
        // （`PlaybackTimeline.positionChanged` → `anchorNs = nanoTime()`）。
        //
        // 于是**每一次位置回调**都要先付一次写盘，锚点才在 I/O **之后**被取：
        //   `锚点时刻 = 宿主回调时刻 + 写盘耗时`
        // 而外推算的是 `位置值 + (现在 − 锚点)` —— **恒少算一个写盘耗时**。
        // 真机证据：宿主位置回调 **~1000 ms 一次**（日志实测 `1164, 2173, 3187, 4202…`），
        // 两次回调之间全靠外推；锚点晚多少，高亮就**恒定滞后**多少，
        // 且写盘耗时随磁盘/杀软波动 → 用户看到"**有些延迟**"且时好时坏。
        //
        // ## 修法：**把日志移出关键路径**
        //
        // 先转发（时间轴拿到值、锚点取在 I/O 之前），再打日志。
        // 原注释担心"日志放在转发之后会丢掉'事件到达'的记录语义" —— 这一点**并未丢失**：
        //   · 转发是纯内存操作（`offer` 在就绪时**同步派发**，无队列）；
        //   · 时间轴未装配时转发是 no-op，日志照样打；
        //   · 唯一的差别是"转发抛异常时日志不打" —— 而转发路径本身不抛（有 runCatching）。
        //
        // ⚠️ **更好的修法**（下一轮）：在 `dispatch` 入口就取 `nanoTime()`，
        //    作为**到达时刻**随 `StructuredEvent.Position` 一起交给时间轴 ——
        //    那样既能保留"日志在前"，又能让锚点用**真正的到达时刻**。
        //    本次先做零风险的重排，把 I/O 移出关键路径。
        // ★ 2026-10-18（P1）：**每条转发各自兜底**。
        //   原来这里全无 try/catch，而 KDoc 却写着「转发路径本身不抛（有 runCatching）」
        //   —— 断言与实现相反。一旦转发抛出：异常逃进宿主回调线程，
        //   且末尾 `log()` 永不执行 ⇒ 该事件在诊断日志里彻底消失。
        runCatching { runtime.onPlaybackEvent(event.kind, value) }
            .onFailure { System.err.println("[CDL] 事件转发失败（日志通道 ${event.kind}）：$it") }

        // ── 结构化通道（P1）────────────────────────────────────────────────
        // 上面的 `value` 是为了日志与 P0 的可观测性而做的字符串化，但它**有损**：
        // 曲目的 `path` / `album` 被拼没了，歌词行的 `startTime` / `endTime` /
        // `lyricsCells`（逐字）也被压扁成一句文本，而这些正是时间轴必需的字段。
        // 字符串一旦拼好就无法可靠反解（标题本身可能含 " — "），因此富数据必须
        // 走这条独立通道原样传递。
        //
        // 为什么放在日志之后：日志保持"事件到达"的记录语义不变，
        // 且即便时间轴尚未装配（timeline 为 null），日志与 [lastEvent] 依然完整 ——
        // 结构化通道的缺失不会影响原有的可观测性。
        runCatching {
            runtime.onPlaybackCallback(
                when (event) {
                is Event.Track -> CdlRuntime.StructuredEvent.Track(event.item)
                is Event.State -> CdlRuntime.StructuredEvent.State(event.state)
                is Event.Playing -> CdlRuntime.StructuredEvent.Playing(event.playing)
                is Event.Position -> CdlRuntime.StructuredEvent.Position(event.positionMs)
                is Event.Seek -> CdlRuntime.StructuredEvent.Seek(event.positionMs)
                is Event.Line -> CdlRuntime.StructuredEvent.Line(event.line)
                    is Event.Lines -> CdlRuntime.StructuredEvent.Lines(event.lines)
                }
            )
        }.onFailure { System.err.println("[CDL] 事件转发失败（结构化通道 ${event.kind}）：$it") }

        // ★ 2026-09-20：日志**必须排在两条通道都转发之后**（见本函数上方的详细说明）。
        //   原因：`System.err.println` → 镜像到文件 = **阻塞写盘**，
        //   而时间轴的位置锚点是在上面那条**结构化通道**里取的
        //   （`PlaybackTimeline.positionChanged` → `anchorNs = nanoTime()`）。
        //   日志排在它前面 → 锚点晚一个写盘耗时 → **高亮恒定滞后**且随磁盘波动。
        //   ⚠️ 只把日志移到 `onPlaybackEvent` 之后是**不够的** —— 锚点在**这一条**通道里。
        log(event.kind, value)
    }

    private fun log(kind: String, value: String) {
        System.err.println("[CDL] callback $kind: $value")
    }

    private companion object {
        /**
         * 缓冲上界。取值理由：宿主正常启动下，就绪前的事件数是个位数到几十；
         * 256 足以覆盖任何正常启动窗口，又不至于在异常情况下吃掉可观内存
         * （单条事件最大的是 `MediaItem`，通常几百字节）。
         */
        const val MAX_BUFFERED = 256

        /**
         * 单条歌词行日志里最多 dump 几个 cell。
         *
         * 为什么要有这个上界：日志文件按 1 MB 轮转（见 [PluginLog]），而 `Line` 回调
         * 是**每句歌词一次**的高频事件。逐字歌词源的 cells 通常是 10–30 项/行，
         * 一首歌 100 行 → 无上界时约 100 KB/首，长会话下会把日志轮转掉，
         * 反而丢掉真正的诊断信息。
         *
         * 16 的取值理由：**真机实测 `cells=1`**（86/86），而上限要能覆盖
         * "逐字源"的典型行（中文 10–20 字/句）。超过 16 项的极端行只列前 16 项
         * 并标出总数 —— 那已足够判断"有没有亚行粒度"这个唯一要回答的问题。
         */
        const val MAX_LOGGED_CELLS = 16

        /**
         * 整轴日志里首行文本的截断长度。
         *
         * 与 [MAX_LOGGED_CELLS] 同精神但**理由不同**：这里只取 1 行的前 N 个字符，
         * 目的是"认得出这是哪首、哪一句"，不是"看清每个字"。
         * 取 24：中文一句通常是 10–20 字，24 足够看出句意且不撑长日志。
         */
        const val LOGGED_AXIS_TEXT = 24
    }
}
