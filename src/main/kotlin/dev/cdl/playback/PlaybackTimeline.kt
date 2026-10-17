// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

import java.util.concurrent.atomic.AtomicReference

/**
 * 播放时间轴：把宿主那套**低频、无序、且可能缺失**的回调，整理成渲染线程可以
 * 每帧直接读的平滑状态。
 *
 * ## 它解决的问题
 * 宿主的回调有几个特性，任何一个单独看来都不算严重，合起来却使"直接拿回调值渲染"
 * 完全不可用：
 *
 * 1. **位置回调频率低**（原实现实现注释为 "the host's one-second clock"，约 1 秒一次），
 *    而渲染是 60fps 级。直接把回调的位置画出去，歌词会以秒为单位跳变。
 * 2. **回调可能缺失**：插件可能在宿主**已经开始播放之后**才被加载，此前的
 *    `onIsPlayingChanged` / `onStateChanged` 永远收不到，于是"明明在播，插件却认为暂停"。
 * 3. **回调顺序不保证**：`onSeekTo` 之后，宿主可能**再送来一个 seek 前的旧位置**，
 *    若直接采纳，进度条会瞬间弹回旧位置再跳过来（视觉上是一次抽搐）。
 *
 * 对应三套对策：**单调时钟外推**（解决 1）、**心跳恢复推断**（解决 2）、
 * **seek 确认窗口**（解决 3）。
 *
 * ## 为什么全程不用锁
 * 本类的写入方是**宿主回调线程**，读取方是渲染线程。若用 `synchronized` 保护状态，
 * 这把锁就会跨越回调边界：渲染线程持锁读取期间，回调线程会在宿主自己的调用栈里等锁，
 * 从而**阻塞宿主**（宿主回调是串行的，阻塞它会拖住播放器本体，极端情况死锁）。
 *
 * 因此这里只使用两种无锁原语：
 * - `@Volatile` 字段：单次读写的可见性保证，足以支撑"写方整体覆盖、读方整体读取"。
 * - `AtomicReference`（[documentRef] / [snapshotRef]）：引用的发布/获取是**一次原子读或写**。
 *   ⚠️ 其中 [snapshotRef] 目前**只写不读**（渲染层走现算的 [snapshot]，见其 KDoc）——
 *   真正被读的是 [documentRef]。别按"渲染线程从 `snapshotRef` 读整份快照"去推理。
 *
 * ⚠️ 代价与前提：`snapshot()` 读到的是若干字段的**某一瞬时组合**，不保证是"同一次
 * 回调之后的完整一致状态"。这在渲染场景下是可接受的 —— 快照之间的不一致最多只体现在
 * 一两帧（约 16ms），肉眼不可见；而用锁换来的强一致性会让宿主承担阻塞风险。
 * **这个取舍是本类的核心设计决策，改动前请先想清楚。**
 *
 * ## 线程安全边界（务必遵守）
 * - `on*` 系列方法：**只允许宿主回调线程调用**（在 `CdlRuntime.onPlaybackEvent`
 *   的调用栈上）。这里是"宿主线程"，**不是 EDT** —— 因此本类内**绝不能触碰 Swing**，
 *   也**绝不能 `invokeLater`**（那会把状态更新推迟到不确定的时刻，且在高频回调下堆积）。
 * - [snapshot] / [currentLineIndex] / [lineProgress]：**只允许渲染线程调用**，纯读，无副作用。
 *
 * @param nanoTime 单调时钟来源。做成可注入是为了能确定性地验证外推行为
 *                 （测试里给一个手动推进的假时钟即可），生产环境用 [System.nanoTime]。
 */
class PlaybackTimeline(
    private val nanoTime: () -> Long = System::nanoTime,
) {

    // ── 快照发布点（⚠️ 目前只写不读，见 publish 的 KDoc）──────────────────────
    // 所有写入方法的最后一步都是 publish()，把状态打包成一份不可变快照存到这里。
    // 但**渲染线程并不读它** —— 渲染走的是 snapshot()，那里会按当前时刻重新外推位置。
    // 保留它是为了"写入方总有一个完整的原子发布点"这一契约，删它要动 8 处调用点。
    private val snapshotRef = AtomicReference(TimelineSnapshot.EMPTY)

    // ── 曲目信息 ──────────────────────────────────────────────────────────────
    @Volatile private var trackTitle: String = ""
    @Volatile private var artist: String = ""
    @Volatile private var album: String = ""
    @Volatile private var path: String = ""

    // ── 播放态 ────────────────────────────────────────────────────────────────
    /**
     * 宿主明确告知的播放态。⚠️ 这个值**单独并不可信** —— 插件晚于播放启动加载时它恒为
     * false，因此实际渲染判断用的是 [isEffectivelyPlaying]（叠加心跳推断）。
     */
    @Volatile private var playing: Boolean = false

    /** 宿主播放状态机的原样值：`Idle` / `Buffering` / `Ready` / `Ended`。 */
    @Volatile private var hostState: String = "Idle"

    // ── 位置与锚点 ────────────────────────────────────────────────────────────
    /**
     * 最近一次宿主给出的位置（毫秒），是外推的**基准点**。
     * 外推值 = [positionMs] + (当前 nanoTime − [anchorNs]) 的毫秒数。
     */
    @Volatile private var positionMs: Long = 0L

    /**
     * [positionMs] 被写入时刻的单调时钟读数。
     *
     * 为什么用 `System.nanoTime()` 而不是 `System.currentTimeMillis()`：
     * 后者是**墙上时钟**，会被 NTP 校时、用户改系统时间、夏令时调整影响而**跳变**
     * （可正可负）。用它做差分会算出负的经过时间或巨大的正数，表现为歌词突然倒退
     * 或瞬间跳到末尾。`nanoTime` 单调递增、不受校时影响，只用于测"经过了多久"，
     * 正是这里需要的语义。
     */
    @Volatile private var anchorNs: Long = nanoTime()

    // ── seek 确认窗口 ─────────────────────────────────────────────────────────
    /** 待确认的目标位置；非 null 表示"已发出 seek，正在等宿主用新位置确认"。 */
    @Volatile private var pendingSeekTarget: Long? = null

    /** 确认窗口的截止时刻（nanoTime）；超过它就不再怀疑后续位置值。 */
    @Volatile private var pendingSeekDeadlineNs: Long = 0L

    // ── 心跳推断状态 ──────────────────────────────────────────────────────────
    /** 上一个心跳位置，用于判断"是否在连续前进"。 -1 表示无基准。 */
    @Volatile private var previousHeartbeatMs: Long = -1L

    /** 是否已经从宿主收到过**明确的**播放态回调（收到了就不再靠心跳瞎猜）。 */
    @Volatile private var playingSignalObserved: Boolean = false

    /** 心跳推断是否被允许（Idle / Ended 期间不允许 —— 那时位置本就不该前进）。 */
    @Volatile private var heartbeatInferenceAllowed: Boolean = true

    // ── 歌词文档 ──────────────────────────────────────────────────────────────
    private val documentRef = AtomicReference(LyricDocument.EMPTY)

    /** 文档版本号，仅在歌词被替换时自增（见 [TimelineSnapshot.docVersion] 的说明）。 */
    @Volatile private var docVersion: Long = 0L

    // ── 空槽位（2026-09-18：占位开关已移除）──────────────────────────────────
    // 原有一个 `keepPlaceholder: Boolean` 字段 + 设置项 `layout.emptySlot.keepPlaceholder`。
    // 为什么删：它的两个分支在真机上**都不可达** ——
    //   ① `rawNext == null` 时无条件返回 null，完全绕过该开关；
    //   ② `rawNext.text.isBlank()` 永不为真（两条入表路径都过滤了空文本）。
    // 于是"能拖但画面毫无变化"。用户裁定移除，语义固化为
    // [nextLineFor] 里的"有下一行就用、没有就 null"。取证见 `recon/_设置项有效性审计.md`。

    // ─────────────────────────────────────────────────────────────────────────
    // 宿主回调入口（宿主线程；不要 invokeLater）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 曲目切换。清空一切与上一曲相关的状态。
     *
     * 为什么必须清空位置与锚点：新曲目的位置从 0 开始，若沿用上一曲的位置与锚点，
     * 外推会在新曲目开头算出一个很大的位置，表现为"新歌一开头歌词就跳到中间"。
     */
    fun trackChanged(title: String, artist: String, album: String, path: String) {
        trackTitle = title
        this.artist = artist
        this.album = album
        this.path = path
        positionMs = 0L
        anchorNs = nanoTime()
        pendingSeekTarget = null
        resetHeartbeat()
        // 换曲即视为歌词作废：保留旧歌词会让新歌开头显示上一首的词。
        replaceDocument(LyricDocument.EMPTY)
        publish()
    }

    /**
     * 位置心跳。约 1 秒一次（原实现实现注释为 "the host's one-second clock"），
     * 是**外推的基准来源**。
     */
    fun positionChanged(value: Long) {
        val now = nanoTime()
        val next = value.coerceAtLeast(0)

        // ── seek 确认窗口 ──
        // 宿主可能在 onSeekTo 之后**再补送一个 seek 前的旧位置**（回调时序不保证）。
        // 若直接采纳，进度会先弹回旧值再跳到新值，看起来像抽搐。
        // 对策：在窗口期内，凡是与目标位置相差超过容忍度的值，一律视为"宿主的陈旧心跳"丢弃。
        val target = pendingSeekTarget
        if (target != null) {
            val expired = now >= pendingSeekDeadlineNs
            val closeEnough = kotlin.math.abs(next - target) <= SEEK_ACK_TOLERANCE_MS
            if (!expired && !closeEnough) {
                // 丢弃这次心跳，但**不重置锚点** —— 位置与锚点保持 seek 时的设定。
                return
            }
            pendingSeekTarget = null
        }

        // 连续前进的心跳可以证明"时钟在走"（见 [updateHeartbeatInference]）。
        if (updateHeartbeatInference(next)) {
            playing = true
            if (hostState == STATE_IDLE) hostState = STATE_READY
        }

        positionMs = next
        anchorNs = now
        publish()
    }

    /**
     * 宿主执行了 seek。
     *
     * 关键动作有三个，缺一不可：
     * 1. 把位置**立即**设为目标值并重置锚点 —— 若等宿主的心跳确认，用户会看到
     *    进度条在拖完之后延迟约 1 秒才跳过去（可感知的迟钝）。
     * 2. 打开确认窗口（[pendingSeekTarget]）—— 用来挡掉紧随其后的陈旧心跳。
     * 3. 丢弃当前歌词行 —— 旧位置的歌词已经不作数，等宿主补送新行；
     *    保留它会让 seek 后短暂显示**旧位置那句词**，与听到的内容不符。
     */
    fun seek(value: Long) {
        val now = nanoTime()
        val next = value.coerceAtLeast(0)
        positionMs = next
        anchorNs = now
        pendingSeekTarget = next
        pendingSeekDeadlineNs = now + SEEK_ACK_WINDOW_NS
        previousHeartbeatMs = -1L
        // 不丢弃整个文档（同一首歌 seek 后歌词仍是同一份），只丢弃"当前行"的锚定。
        // 由 lineAt() 在新位置重新选出正确的行，因此这里只需重置心跳基准。
        publish()
    }

    /** 播放/暂停切换。 */
    fun playingChanged(value: Boolean) {
        val now = nanoTime()
        // 先把"到此刻为止外推出的位置"固化下来，再重置锚点。
        // 为什么不能直接重置锚点：暂停期间外推必须停止，若不固化，暂停后锚点仍指向
        // 上一次心跳时刻，恢复播放时会把这中间**暂停了多久**也算成播放进度，
        // 表现为"暂停五分钟后继续，歌词直接跳到五分钟后的位置"。
        positionMs = extrapolatedPosition(now)
        anchorNs = now
        playingSignalObserved = true
        playing = value
        if (value && hostState == STATE_IDLE) hostState = STATE_READY
        publish()
    }

    /** 宿主播放状态机变化。 */
    fun stateChanged(state: String) {
        val now = nanoTime()
        positionMs = extrapolatedPosition(now)
        anchorNs = now
        hostState = state
        heartbeatInferenceAllowed = state != STATE_IDLE && state != STATE_ENDED
        if (!heartbeatInferenceAllowed) previousHeartbeatMs = -1L
        if (state == STATE_IDLE || state == STATE_ENDED) {
            playing = false
        }
        if (state == STATE_IDLE) {
            // Idle 表示"没有曲目在播"，此时曲目信息已失效。
            trackTitle = ""
            artist = ""
            album = ""
            path = ""
            positionMs = 0L
        }
        publish()
    }

    /**
     * 收到宿主的结构化歌词行（来自 `onLyricsLineUpdated`，宿主逐行推送）。
     *
     * ⚠️ 这是**逐行**推送，不是整份歌词。因此这里做的是"追加/更新一行"，
     * 而不是替换整份文档 —— 后者会导致每来一行就把之前所有行删掉，词岛永远只有一句话。
     *
     * ## 2026-09-18：新增「译文行配对」（用户诉求「副歌词完全不显示」的修法）
     *
     * 原记（`recon/24` §1）：译文由 `pureSubText` 单独承载，与原文**同一行**送来。
     * 据此只实现了「同一串里剥出译文」（`splitBilingualLine`）这一条路。
     *
     * 实为（2026-09-18 真机日志 `super-island.log`，527 条 `callback line:` 全量统计）：
     * **`sub=<null>` 527 次、非 null 0 次** —— 对本用户实际使用的歌词源，
     * 译文是以**独立歌词行**推送的，原文行与译文行**成对交替**、各自是独立的
     * `LyricsLine`、**都走 `pureMainText`**。真机样本（Beat It）：
     *
     * ```
     * main="They'll kick you, then they'll beat you…"  @106830-106850
     * main="他们先是又踢有打"                            @106850-108750
     * ```
     *
     * 本方法此前**从不做配对**，于是译文被当成**独立歌词行**收进行表，
     * 与原文交替占据「当前行」→ 用户看到的就是「**副歌词完全不显示**」
     * （译文不是没画，而是**被画在了主歌词槽位上**）。
     *
     * 修法：新行若与**上一行**构成译文对（判据见 [isTranslationPair]），
     * 就把它的文本塞进上一行的 `translation` 字段，**本行不入表** ——
     * 译文于是回到「当前行的副行」这一语义上，由渲染层画在副歌词位置。
     *
     * ## 为什么不在这里判 `layout.translation.enabled`
     *
     * 配对发生在**时间轴层**，与显示开关无关。开关的消费点在渲染层
     * （`IslandWindow` 的 `currentSub` 判定 + `islandHeightFor` 的高度预留）。
     * 刻意如此：开关切换因此**零成本**（不必重建文档），
     * 且关掉开关时译文仍在数据里，重新打开立刻可见。
     * 若在入表处就按开关丢弃译文，开关会变成"一次性决定"——
     * 打开时只对**之后**推送的行生效，历史行永远没有译文。
     */
    fun lineChanged(
        startMs: Long,
        endMs: Long,
        text: String,
        translation: String?,
        words: List<LyricWord>?,
        /**
         * **译文自己的**逐字时间轴（可为 null）。
         *
         * 为什么必须单独一个参数而不是从 [words] 里推：原文与译文的逐字**互不借用**，
         * 两者来自同一批 cells 的不同区段（见 `narrowWordsToTranslation`）。
         * 缺省 null 让既有的 5 参调用点（探针）保持原样。
         */
        translationWords: List<LyricWord>? = null,
    ) {
        // 空行 / 空文本的推送表示"这里是过门间隙"，**不应当**被当成一行歌词存起来
        // （否则过门处会显示一句空话，把上一行挤掉）。
        //
        // ⚠️ 2026-09-20：**宿主的「纯音乐提示行」同样按"没有歌词"处理**。
        //    真机取证：宿主把 `纯音乐，请欣赏` 走**歌词回调**推来（`cells=1` 覆盖整行）。
        //    若不拦，插件自己的 `layout.noLyrics.*`（含「纯音乐时隐藏占位句」）
        //    **根本管不到它** —— 用户开了隐藏却照样显示，这就是他说的"逻辑混乱"。
        //    拦在这里 = 与过门间隙走同一条路 = **"显示与否"只有一个决定点**。
        if (text.isBlank() || isHostPlaceholderLine(text)) {
            // ⚠️ **能观测比能修复更优先**：这一行日志让"宿主提示行到底有没有被拦下"
            //    变成可读事实。用户此前只能靠肉眼判断，无法区分
            //    "没拦下" 与 "拦下了但别处在画"。
            if (text.isNotBlank()) {
                System.err.println("[CDL] 宿主的无歌词提示行已拦截（不当歌词）：\"$text\"")
            }
            return
        }

        val current = documentRef.get()
        val existing = current.lines

        // ── ① 同一行被重复推送 ──────────────────────────────────────────────
        //
        // 为什么不直接走 ③ 的"先删同键再加新"：那样会**丢掉这一行已经配好的译文**
        // （译文是配对上之后才写进 `translation` 的，新推来的行里没有它）。
        // seek 回退会真实地重复推送同一行（宿主按"当前行下标变化"推送，
        // 回退后下标又变了回去），此时用户会看到"译文闪一下消失"。
        // 故这里把旧行**原样保留**，只在本次带了新译文时才补写。
        val dupIndex = existing.indexOfFirst {
            it.startMs == startMs && it.endMs == endMs && it.text == text
        }
        if (dupIndex >= 0) {
            val old = existing[dupIndex]
            if (old.translation != translation || old.translationWords != translationWords) {
                val updated = old.copy(
                    // `?:` 而不是直传：本次没带译文（正常情况）时保留旧值。
                    translation = translation ?: old.translation,
                    translationWords = translationWords ?: old.translationWords,
                )
                documentRef.set(
                    LyricDocument(existing.toMutableList().also { it[dupIndex] = updated }, true)
                )
                docVersion++
                publish()
            }
            return
        }

        val line = LyricLine(startMs, endMs, text, translation, words, translationWords)

        // ── ② 译文行配对（形态 B）────────────────────────────────────────────
        //
        // "上一行"取行表**最后入表的那一行**（本方法的 ③ 分支始终按 startMs 排序，
        // 故它就是时间上最靠后的那一行，也就是宿主上一次推来的行）。
        // 就算取错了（例如 `documentChanged` 刚换过整份文档），
        // [isTranslationPair] 的两条时间判据也会把它挡掉 ——
        // 真正的保护在那里，不在这里选哪一行。
        val previous = existing.lastOrNull()
        if (previous != null && isTranslationPair(previous, line)) {
            // 译文只写进 `translation`，**不新增行** —— 这是"副歌词行"与
            // "又一个主歌词槽位"的唯一区别（见本方法的 KDoc）。
            //
            // ── ⚠️ 原记与实为：逐字时间轴的搬运（2026-09-19 修正）────────────────
            //
            // **原记**（本段上一版）：「逐字时间轴**不带过去**：译文行是独立推送的，
            //   它自带的 cells 属于它自己，而不是原文的时间轴。硬塞给**原文**会让原文的
            //   逐字高亮错位（`effectiveWords` 要求"各字拼接精确等于 text"）。」
            //
            // **实为**：那段理由说的是 `words`（原文的轴），**却连 `translationWords`
            //   一起不写** —— 而 `translationWords` 要装的**正是**"译文行自带的那批 cells"，
            //   它们本来就属于译文。后果是：
            //     · 主行有自己的 cells → `hasWordTimingEvidence` 为真 → 走**逐字**时序；
            //     · 译文 `translationWords` 恒为 null → `hasTranslationTimingEvidence`
            //       恒为假 → 只能退回**整行进度**（`(t−start)/(end−start)`，分母含句尾空白）。
            //   **两条时序不同源 → 译文稳定落后主行 1-3 个字**（用户报过两次，
            //   而上一轮改 `useWordTiming` 档位判据对它**完全无效** —— 卡住它的不是档位，
            //   是这里从来没把轴交过去）。
            //
            // **安全性**：交过去**不会**产生错亮 —— `effectiveTranslationWords` 走
            //   `checkedWords` 的 fail-closed（拼出来必须精确等于译文，否则整组作废退回整行）。
            //   所以这是"把已有的可信数据接上"，不是"编造时间轴"。
            //
            // ── 2026-09-18：主副角色改由**曲目标题语言**决定 ────────────────────
            // 原为"先到的当主行"。真机全量统计显示两种顺序**几乎各半**
            // （中文先到 39 次 / 英文先到 37 次），于是"先到的是原文"这个前提不成立，
            // 有 5 对把译文当成了主行（大字显示译文、小字显示原文）。
            // 判据与四条边界情形的处理见 [translationPairRole]。
            val role = translationPairRole(previous, line, dominantScript(trackTitle))
            val merged = when (role) {
                PairRole.PREV_IS_MAIN -> previous.copy(
                    translation = line.text,
                    // ★ 译文**自己的**逐字轴 = 译文行自带的 cells（见上面的"原记与实为"）。
                    translationWords = line.words,
                    translationSegments = 1,
                    // 锚点 = **译文那一侧**的结束时间（不是主行自己的 endMs，理由见该字段）。
                    translationEndMs = line.endMs,
                )
                // 角色反转：后到的当主行，先到的降为译文。
                // ⚠️ 行的**区间仍取先到者**（`previous` 的 start/end），
                // 这与"一对一"的既有行为一致（原实现从不改动 endMs），
                // 因而不影响 `lineAt`（只看 startMs）与既有 32 对的进度观感。
                //
                // ⚠️⚠️ **文本与逐字轴必须一起换**：原实现只换了 `text`/`translation`，
                //   把 `words`/`translationWords` 留在原处 —— 于是新的主行是
                //   "B 的文本 + A 的逐字轴"，`checkedWords` 判"拼接不等于 text" →
                //   **整组 fail-closed** → 主行**静默失去**逐字高亮（不报错、只是不再逐字）。
                PairRole.NEXT_IS_MAIN -> previous.copy(
                    text = line.text,
                    words = line.words,
                    translation = previous.text,
                    translationWords = previous.words,
                    translationSegments = 1,
                    translationEndMs = previous.endMs,
                )
            }
            documentRef.set(LyricDocument(existing.dropLast(1) + merged, true))
            docVersion++
            publish()
            return
        }

        // ── ②' 一个原文 ↔ 连续多个译文行（2026-09-18 新增）───────────────────
        //
        // 真机样本（Beat It，`:3152`–`:3158`）：源 LRC 把**一句**英文的译文拆成了两行中文，
        // 而 ② 是一对一的 —— 第二行中文于是成了**独立主行**（用户看到它单独显示、
        // 且没有原文配它）。判据、上限与它为什么这么保守见 [isTranslationContinuation]。
        if (previous != null && isTranslationContinuation(previous, line)) {
            // 多段译文用**半角空格**拼接，而不是换行符 —— 渲染层只画一行，
            // 且 `java.awt.font.TextLayout` 对 `\n` 的 advance 为 0（实测），
            // 用换行符会让两段**直接粘连**成一句看不出分隔的话。理由见该常量。
            //
            // 逐字（`translationWords`）**有意不清空**：它仍是第一段的字，
            // 拼接后与 `translation` 对不上 → `effectiveTranslationWords` 自动
            // fail-closed 返回空 → 译文退回整行进度高亮。这是安全的默认行为，
            // 显式清空只是把同一件事写两遍。
            val merged = previous.copy(
                translation = previous.translation + TRANSLATION_SEGMENT_SEPARATOR + line.text,
                translationSegments = previous.translationSegments + 1,
                translationEndMs = line.endMs,
            )
            documentRef.set(LyricDocument(existing.dropLast(1) + merged, true))
            docVersion++
            publish()
            return
        }

        // ── ③ 常规追加 ───────────────────────────────────────────────────────
        // 逐行推送本身就意味着带时间轴。
        val mergedLines = (existing + line).sortedWith(compareBy({ it.startMs }, { it.endMs }))
        documentRef.set(LyricDocument(mergedLines, true))
        docVersion++
        publish()
    }

    /** 宿主的 `onLyricsLineUpdated(null)`：过门间隙信号。同上，不改变已有歌词。 */
    fun lineCleared() {
        // 有意为空实现：宿主用 null 表示"这一段没有歌词"，而我们希望过门期间**保留**
        // 上一句继续显示（见 LyricDocument.lineAt 中"保留间隙期上一行"的说明）。
        // 因此这里刻意什么也不做，而不是把当前行清掉。
    }

    /**
     * 替换整份歌词文档（例如插件自己解析出的 LRC，或宿主换曲后载入的新歌词）。
     */
    fun documentChanged(document: LyricDocument) {
        replaceDocument(document)
        publish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 整轴入口（API 0.1.0-dev21，宿主 1.19.0）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 用宿主一次性给出的**整条歌词轴**替换当前文档（`PlaybackExtensionPoint.onLyricsLinesUpdated`）。
     *
     * ## 为什么它比 [lineChanged] 的逐行累积更准
     *
     * 逐行推送时，插件在**收到下一行之前**根本不知道"下一行是什么"，于是不得不用一堆
     * 启发式去猜，而这些猜测全都有已知的失败面（见本文件下半部分各 `is*` 函数的 KDoc）：
     *
     * | 逐行累积必须猜的东西 | 猜错的后果 | 整轴是否还需要猜 |
     * |---|---|---|
     * | 这一行是原文还是译文（[translationPairRole]） | 主副角色互换 | **不需要**：一份 `LyricsLine` 自带 `pureMainText` + `pureSubText` |
     * | 下一行是不是这一段译文的续行（[isTranslationContinuation]） | 译文拆成两行、其中一行变独立主行 | **不需要** |
     * | 对不对得上"上一段译文"（[isTranslationPair]） | 译文配错行 | **不需要** |
     * | 行表之外还有什么（未推送的行） | seek 之后无处可去 | **不需要**：整轴一次给全 |
     *
     * ⇒ 这条路径把上述四类猜测**整体去掉**：宿主已经把"主文本 / 译文本"在同一份
     * 构造参数里分好了，`(startTime, endTime, lyricsCells, pureMainText, pureSubText)`
     * 五个字段就是全部信息，不存在"配对"这个动作。
     *
     * ## 为什么仍然保留逐行路径
     *
     * 宿主版本低于 1.19.0 时，`onLyricsLinesUpdated` 这个方法**根本不存在**
     * （它是 dev21 新增的 default 方法），插件只会收到 `onLyricsLineUpdated`。
     * 故两条路径并存：整轴到了 [bilingualDocumentFrom] 生成的文档，
     * 逐行到了走 [lineChanged]。**能力探测在装配层**（`CdlRuntime`），
     * 本类只做"你给文档、我换文档"，不判断宿主版本。
     *
     * ## ⚠️ 逐字**不走这里**
     *
     * 本入口只接文本与时间区间。逐字轴仍然由 `CdlRuntime` 用同一套
     * `narrowWordsToText` / `narrowWordsToTranslation` 从 cells 裁出来 ——
     * **不在这条路径上另写一套裁剪规则**。理由：那套规则的 fail-closed
     * （拼接必须精确等于文本）已经被大量探针钉死，重写一遍等于把护栏复制一份，
     * 将来必然分叉。见 `CdlRuntime.onPlaybackCallback` 的 `Lines` 分支。
     *
     * @param document 已由装配层构造好的文档（必须已去重、已按 `startMs` 排序）
     */
    fun documentReplaced(document: LyricDocument) {
        replaceDocument(document)
        publish()
    }

    /**
     * 宿主报告"当前没有歌词"（整轴为空列表，或逐行 `null`）。
     *
     * ## 为什么**不**清空
     *
     * 与 [lineCleared] 同源：宿主在**换曲的瞬间**会先发一个空轴，紧接着才推新轴的。
     * 若此刻清空，用户会看到"歌词在整个换曲间隙里消失"——而这段时间恰好长达
     * 0.5–2 秒（要等新歌词载入），是最容易被注意到的闪烁。
     *
     * 保留旧轴的成本是"上一首的最后一行多显示一会儿"，收益是"没有空档"。
     * 两者不对称，故**保留**。
     *
     * ⚠️ 但有例外：真换曲（[trackChanged]）本来就 `replaceDocument(LyricDocument.EMPTY)`，
     * 旧轴在换曲那一刻就已清掉，所以这里的"保留"**不会**把上一首的歌词带进下一首。
     */
    fun documentCleared() {
        // 有意为空实现 —— 理由见本方法 KDoc。
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 渲染线程读取接口（纯读，无副作用）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 取当前快照。渲染线程每帧调用。
     *
     * ## 它**不是**"读已发布的那份快照"（原注释如此说，实为不符）
     *
     * 原记（本方法 KDoc 原文）：「这里复用了 `snapshotRef` 里已发布的快照对象，
     * 但**位置与进度按当前时刻重算**」。
     *
     * 实为：本方法**从不读 `snapshotRef`**（全文件仅有 `publish()` 里的
     * `snapshotRef.set(...)` 一处写入，**没有任何读取点**）。位置、行下标、
     * prev/current/next、进度全部在这里**现算**。因此：
     *   · 类注释里"快照发布/获取是一次引用的原子读或写"描述的是**一套未落地的机制**；
     *     实际的一致性来自 `documentRef`（一次原子读）加上各 `@Volatile` 字段的
     *     单次读 —— 与类注释结尾"最多只体现在一两帧"的**结论**仍然成立，
     *     但**机制不同**，改动前不要按那套机制去推理。
     *   · `publish()` / `snapshotRef` 目前是**只写不读**的（见 [publish] 的说明）。
     *
     * 为什么保留"现算"而不是改成读 `snapshotRef`：位置必须按**当前时刻**外推
     * （见 [extrapolatedPosition]），而 `snapshotRef` 里那份是"写入时刻"的产物 ——
     * 直接读它会让歌词退回 1 秒一跳，正是外推要消除的现象。
     */
    fun snapshot(): TimelineSnapshot {
        val now = nanoTime()
        val currentMs = extrapolatedPosition(now)
        val doc = documentRef.get()
        val index = doc.lineAt(currentMs)
        val currentLine = doc.lines.getOrNull(index)
        val prevLine = if (index > 0) doc.lines.getOrNull(index - 1) else null
        val rawNext = doc.lines.getOrNull(index + 1)
        // 判定抽到 [nextLineFor] —— **它必须与 `publish()` 共用同一份实现**。
        // 2026-09-17 复核发现：`publish()` 里曾留着一段**修前的**旧代码，
        // 于是同一个判定在同一文件里有了**两个不一致的定义点**，故一并收敛。
        val nextLine = nextLineFor(rawNext)
        return TimelineSnapshot(
            trackTitle = trackTitle,
            artist = artist,
            album = album,
            path = path,
            isPlaying = isEffectivelyPlaying(now),
            positionMs = currentMs,
            currentLineIndex = index,
            prevLine = prevLine,
            currentLine = currentLine,
            nextLine = nextLine,
            currentLineProgress = currentLine?.progress(currentMs) ?: 0f,
            docVersion = docVersion,
            // ★ 2026-09-28：文档**总行数** —— 见 TimelineSnapshot.lyricLineCount 的 KDoc。
            //   用 `doc.lines.size` 而不是"当前下标"，因为要区分的正是
            //   "文档为空"与"文档有行但位置还没到"这两种 currentLine 都是 null 的状态。
            lyricLineCount = doc.lines.size,
        )
    }

    /**
     * [atMs] 时刻的当前行下标；`-1` 表示无当前行。
     * 复用 [PlaybackTimeline] 里已解析的文档，供不便取整个快照的调用点使用。
     */
    fun currentLineIndex(atMs: Long): Int = documentRef.get().lineAt(atMs)

    /**
     * [atMs] 时刻当前行的卡拉 OK 进度 0f..1f；无当前行时为 0f。
     *
     * 说明：首版返回的是**整行**进度。若当前行带有可信的逐字时间轴
     * （[LyricLine.hasWordTimingEvidence] 为 true），渲染层应改用
     * [LyricLine.effectiveWords] 逐字计算高亮；本方法作为无可信逐字时的退路。
     */
    fun lineProgress(atMs: Long): Float {
        val doc = documentRef.get()
        val line = doc.lines.getOrNull(doc.lineAt(atMs)) ?: return 0f
        return line.progress(atMs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 外推位置：基准位置 + 自锚点以来经过的时间。
     *
     * ## 为什么要有 [MAX_EXTRAPOLATION_MS] 上限（关键）
     * 外推的危险在于它是**无条件**的：只要"在播放"，它就一直往前跑。若宿主因卡顿、
     * 缓冲、或干脆不再回调而停止发心跳，外推会毫无根据地把歌词推到很远的地方 ——
     * 用户看到歌词自顾自地往前唱，与听到的声音完全脱节，而且**不会再回来**
     * （直到下一次心跳才被拉正）。
     *
     * 因此设一个上限：超过它就不再继续外推（位置"冻结"）。冻结的观感是"歌词卡住了"，
     * 这比"歌词自说自唱"好得多 —— 卡住只是延迟，脱节是**错误信息**。
     */
    private fun extrapolatedPosition(now: Long): Long {
        if (!isEffectivelyPlaying(now)) return positionMs
        val elapsedMs = (now - anchorNs) / 1_000_000L
        if (elapsedMs <= 0) return positionMs
        return positionMs + elapsedMs.coerceIn(0L, MAX_EXTRAPOLATION_MS)
    }

    /**
     * 综合判断"是否在播"。
     *
     * 为什么不能只看 [playing]：插件可能在宿主**已经播放中**才被加载，此时
     * `onIsPlayingChanged(true)` 早就发生过了、永远不会再补发，[playing] 恒为 false。
     * 于是"明明在放歌，歌词却不动"。
     *
     * 为什么也不能只看状态：`Ready` 表示"曲目已就绪"，但不代表正在出声（可能是暂停中）。
     * 因此以 [playing] 为准，仅在**从未收到过明确播放态信号**时，才采信心跳推断出的结果。
     */
    private fun isEffectivelyPlaying(now: Long): Boolean {
        if (playingSignalObserved) return playing
        // 未曾收到明确信号：用"状态允许 + 近期有前进的心跳"来推断。
        if (hostState == STATE_IDLE || hostState == STATE_ENDED) return false
        if (!heartbeatInferenceAllowed) return false
        return now - anchorNs <= HEARTBEAT_FRESHNESS_NS
    }

    /**
     * 心跳推断：连续前进的位置值可以证明"时钟在走"。
     *
     * 返回 true 表示"本次心跳构成了播放证据"。
     *
     * 为什么用"连续前进"而不是"单次变化"：seek 会造成位置突变，单次变化无法区分
     * "正在播放"与"用户拖了进度条"。而**连续两次以上的小幅前进**只可能来自播放。
     *
     * 上限 [MAX_HEARTBEAT_STEP_MS] 用于排除 seek 造成的跳跃（那不是正常播放前进）。
     */
    private fun updateHeartbeatInference(value: Long): Boolean {
        val previous = previousHeartbeatMs
        previousHeartbeatMs = value
        if (!heartbeatInferenceAllowed) return false
        if (playingSignalObserved) return false
        if (previous < 0) return false
        val step = value - previous
        return step in 1..MAX_HEARTBEAT_STEP_MS
    }

    private fun resetHeartbeat() {
        previousHeartbeatMs = -1L
        playingSignalObserved = false
        heartbeatInferenceAllowed = true
    }

    private fun replaceDocument(document: LyricDocument) {
        documentRef.set(document)
        docVersion++
    }

    /**
     * `nextLine` 的**唯一判定点** —— [snapshot] 与 [publish] 必须共用它。
     *
     * 规则：**有下一行就用，没有就是 `null`**。
     *
     * ## ⚠️ 2026-09-18 修订：这里原来还受设置项 `layout.emptySlot.keepPlaceholder` 影响
     *
     * 原实现是三分支：`rawNext != null → 用它` / `keepPlaceholder → 空文本占位行` /
     * `else → null`。用户裁定**移除该设置项**，理由是它是个**假开关**：
     *   · 第一分支已经无条件覆盖了"有下一行"的全部情形，**绕过**了该开关；
     *   · 第二分支依赖 `rawNext.text.isBlank()`，而两条入表路径
     *     （[lineChanged] 的 `text.isBlank()` 守卫 与 `LyricDocument.parse`）**都过滤了空文本**，
     *     故该条件**永不为真** —— 占位行**从未被构造出来过**。
     * 于是"能拖但画面毫无变化"。删掉它把**假开关**换成**确定行为**，
     * 这正是用户原话「未实现的实现，无效（未唱行相关）的移除」所指。
     *
     * 取证见 `recon/_设置项有效性审计.md`。
     *
     * ⚠️ 为什么必须保持"抽成方法"（2026-09-17 复核）：修判定时曾只改了 [snapshot] 里的
     *   一处，`publish()` 里那段**修前的旧代码**被留下了 —— 同一个判定在同一文件里
     *   有了两个**不一致**的定义点。虽然渲染层只读 [snapshot]
     *   （`snapshotRef` 只写不读，见 [publish]），但"两处各解释一半"
     *   本身就是本项目反复记录的那类缺陷（改一处漏一处），故收敛为一份。
     */
    private fun nextLineFor(rawNext: LyricLine?): LyricLine? = rawNext

    /**
     * 把所有可变字段打包发布成一份不可变快照。所有写入方法的最后一步。
     *
     * ## ⚠️ 它目前是**只写不读**的（2026-09-17 复核取证）
     *
     * `snapshotRef` 在全文件只有 `:set(...)` 一处写入，**没有任何读取点** ——
     * 渲染层走的是 [snapshot]，而 [snapshot] 是**现算**的（见其 KDoc）。
     *
     * 保留它（而不是删掉）的理由：删掉要动 7 个写入方法（`trackChanged` /
     * `positionChanged` / `seek` / `playingChanged` / `stateChanged` / `lineChanged` /
     * `documentChanged`）的末尾调用，改动面大、且与
     * "本类只做无锁状态发布"这一契约无关；而它**不产生任何行为**，留着无害。
     * （2026-09-18：原为 8 个 —— 第 8 个 `setKeepPlaceholder` 随设置项一并移除。）
     * 但**不要**据此认为渲染层读到的是"一次原子发布的结果" —— 见 [snapshot] 的 KDoc。
     *
     * ## 为什么这里也要算 `nextLine`
     * 因为它构造的是**同一个 `TimelineSnapshot` 类型**。若与 [snapshot] 用不同规则算
     * `nextLine`，一旦将来有人改成读 `snapshotRef`，"三行显示"就会按**另一套规则**工作
     * （正是上面那个不一致的定义点）。故共用 [nextLineFor]。
     */
    private fun publish() {
        val now = nanoTime()
        val currentMs = extrapolatedPosition(now)
        val doc = documentRef.get()
        val index = doc.lineAt(currentMs)
        val currentLine = doc.lines.getOrNull(index)
        val prevLine = if (index > 0) doc.lines.getOrNull(index - 1) else null
        val rawNext = doc.lines.getOrNull(index + 1)
        val nextLine = nextLineFor(rawNext)
        snapshotRef.set(
            TimelineSnapshot(
                trackTitle = trackTitle,
                artist = artist,
                album = album,
                path = path,
                isPlaying = isEffectivelyPlaying(now),
                positionMs = currentMs,
                currentLineIndex = index,
                prevLine = prevLine,
                currentLine = currentLine,
                nextLine = nextLine,
                currentLineProgress = currentLine?.progress(currentMs) ?: 0f,
                docVersion = docVersion,
                lyricLineCount = doc.lines.size,
            )
        )
    }

    companion object {
        /** 宿主状态机取值（与 `PlaybackExtensionPoint.State` 的枚举名一致）。 */
        const val STATE_IDLE = "Idle"

        const val STATE_BUFFERING = "Buffering"
        const val STATE_READY = "Ready"
        const val STATE_ENDED = "Ended"

        /**
         * 外推上限：2.5 秒。见 [extrapolatedPosition] 的说明。
         *
         * 取值理由：心跳约 1 秒一次，2.5 秒意味着"连续错过两次以上心跳"才冻结 ——
         * 足以吸收单次丢包或一次卡顿，又能在真正停更时及时止住错误的外推。
         */
        private const val MAX_EXTRAPOLATION_MS = 2_500L

        /**
         * seek 确认窗口：2.5 秒。窗口内出现与目标位置相差过大的位置值，判为陈旧心跳丢弃。
         * 取值与原实现灵动词岛实现一致（GPL-3.0-only 参考实现）。
         */
        private const val SEEK_ACK_WINDOW_NS = 2_500_000_000L

        /** seek 容忍度：2 秒内的偏差视为"宿主已确认到新位置附近"，不再怀疑。 */
        private const val SEEK_ACK_TOLERANCE_MS = 2_000L

        /** 心跳前进步长上限：超过这个步长不算"正常播放前进"（可能是 seek）。 */
        private const val MAX_HEARTBEAT_STEP_MS = 2_500L

        /**
         * 心跳新鲜度：锚点距今超过这个时长，就认为"心跳已断"，不再凭推断认定在播放。
         * 取 2.5 秒，与 [MAX_EXTRAPOLATION_MS] 同量级 —— 二者共同表达
         * "连续两次以上收不到心跳就停止一切基于播放的假设"。
         */
        private const val HEARTBEAT_FRESHNESS_NS = 2_500_000_000L
    }
}

// ── 译文行配对（「分行推送」形态）────────────────────────────────────────────
//
// 放在**类外的顶层**（而不是 `private` 成员）是刻意的：探针是独立编译单元，
// 看不到 `internal` 更看不到 `private` —— 而这条判据的失败模式（把两句独立的歌词
// 并成一主一副）**不会抛任何异常**，只能靠断言兜住。这与 `IslandPolicy.kt`
// 把 `shouldDriveHover` 抽成公开纯函数是同一套理由。

/**
 * 译文配对的**同时性窗口**（毫秒）：[next] 的起点与 [prev] 的起点相差不超过它，
 * 才可能"是同一句词的两个版本"。
 *
 * ## 为什么用"两行几乎同时开始"当判据（而不是"时间紧邻"）
 *
 * "时间紧邻"（`prev.endMs == next.startMs`）在本数据集上**区分不了任何东西** ——
 * 宿主推送的相邻行**永远**是首尾相接的（真机 78 组候选对全部满足），
 * 正常的连续歌词也一样。真正的区别在别处：
 *
 * **译文与原文是同一句词的两个版本，它们在时间上必然同时开始。**
 * 而先后演唱的两句词，起点至少相隔一个乐句（真机实测 ≥ 1150 ms）。
 * 于是"起点差"把两类彻底分开：
 *
 * | 真机样本 | 起点差 | 判定 |
 * |---|---|---|
 * | `They told him…`(38220) → `他们叫他不准再踏入这个地方`(38440) | **220 ms** | 配对（真译文） |
 * | `怒火在他们眼中燃烧…`(44350) → `The fire's in their eyes…`(44400) | **50 ms** | 配对（真译文） |
 * | `You better run…`(51900) → `你最好逃走…`(52300) | **400 ms** | 配对（真译文） |
 * | `未去想失声呼叫`(59800) → `I'll never die I'll never cry`(62780) | 2980 ms | **不配对**（是两句词） |
 * | `Woo……A`(14940) → `可否争番一口气`(18060) | 3120 ms | **不配对**（是两句词） |
 * | `我愿能一生永远陪伴你`(48313) → `AH.. OH.. AH.. OH.. AH`(52774) | 4461 ms | **不配对**（是两句词） |
 *
 * 全量统计（78 组"脚本互补且首尾相接"的候选对）：**真译文对的起点差最大 700 ms，
 * 假阳性的最小起点差 1150 ms** —— 取 1000 ms 落在空档正中，两侧各留 ~300 ms 余量。
 *
 * ## 为什么不是"看时长比例"（试过，会误杀真译文）
 *
 * `recon/36` §4.1 曾提议"译文与原文字数量级相近（`ratio ∈ [0.2, 5]`）"。
 * 真机数据否掉了它：`They'll kick you, then they'll beat you, then they'll tell
 * you it's fair`（72 字符）→ `他们先是又踢有打`（8 字符）ratio = **0.11**，
 * 而它**确实**是一对真译文（见 `:3152`–`:3153`）。
 * 中译英的字数差本来就大，用它当判据会把最需要修的那些行漏掉。
 */
private const val TRANSLATION_PAIR_ONSET_MS = 1_000L

/**
 * 译文配对允许的**首尾间隙**（毫秒，取绝对值）。
 *
 * 真机里相邻行是**精确首尾相接**的（`prev.endMs == next.startMs`），
 * 但宿主可能对时间做取整，1–2 ms 的误差会让"精确相等"这条判据脆断。
 * 取 50 ms 既能吸收取整误差，又远小于任何一个真实乐句的长度 ——
 * 真有 50 ms 以上的空隙，说明两行之间还夹着别的东西，不该配对。
 */
private const val TRANSLATION_PAIR_GAP_TOLERANCE_MS = 50L

/**
 * [next] 是不是 [prev] 的**译文行**（宿主以独立行形态推送的译文）。
 *
 * ## 全部条件必须同时成立；任一不成立就不配对
 *
 * 配对错的后果是「**两句本来独立的歌词被并成一主一副**」—— 比"该配没配"更糟：
 * 后者只是退回改动前的行为（译文占一个主槽位），而前者会让**一句真歌词变小字**。
 * 故判据取严，且**每条判据的失败方向都是"不配对"**。
 *
 * ## 逐条判据与它们各自挡掉的东西
 *
 * | 判据 | 挡掉的是 | 真机取证 |
 * |---|---|---|
 * | 任一侧是元数据行（[isMetadataLine]） | 制作信息被并成一对 | `和声 : …` → `Publisher : …`（间隔 250 ms，脚本互补 —— 不挡就真的会配） |
 * | [prev] 尚无译文 | 三行叠成两层（原文→译文→又一行） | Beat It 的 `Just beat it (Beat it)` 连续出现三次 |
 * | [next] 自带译文 | 已经把译文写在自己身上的行 | 宿主偶尔会走「同行拼接」形态 |
 * | 起点差 ∈ `0..`[TRANSLATION_PAIR_ONSET_MS] | 先后演唱的两句词 | 见该常量的表 |
 * | 首尾间隙 ≤ [TRANSLATION_PAIR_GAP_TOLERANCE_MS] | 中间夹了别的行 | 真机全部精确相接 |
 * | 两侧主导书写系统**都判得出**且**不同** | **行内混排**的正常歌词 | `I do believe 我坚信不疑` 判 `null` → 不配对 |
 *
 * ## ⚠️ 有意**不包含**的两条判据
 *
 * 1. **"译文行不带逐字时间轴"**（`recon/36` §4.1 的 ⑤）。真机日志里**每一行**
 *    都是 `cells=1`（单个 cell 覆盖整行），经 `narrowWordsToText` 后 `words`
 *    是**非空的单元素列表** —— 这条判据在真机上恒为 false，加上去会让配对
 *    **一次都不发生**。有没有亚行信息要看 [LyricLine.hasWordTimingEvidence]，
 *    而那个问题与本判据无关（译文配对后走的是整行进度高亮）。
 * 2. **字数量级相近**（`ratio ∈ [0.2, 5]`）。见 [TRANSLATION_PAIR_ONSET_MS] 的说明。
 *
 * ## 「哪一侧才是原文」：2026-09-18 起**由曲目标题语言决定**（原为"先到的当主行"）
 *
 * 真机里两种顺序都真实出现：`They told him…`→`他们叫他不准…`（原文在前），
 * 以及 `怒火在他们眼中燃烧…`→`The fire's in their eyes…`（**译文在前**）。
 * 全量统计（527 条 `callback line`）显示两者**几乎各半**（中文先到 39 次 / 英文先到 37 次），
 * 故"先到的是原文"这个前提不成立 —— 32 对里有 5 对把译文当成了主行
 * （大字显示译文、小字显示原文，用户看到的就是"主副颠倒"）。
 *
 * 现改为：**用当前曲目标题的语言判断原文语言** —— 标题脚本与哪一侧一致，那一侧就是主行。
 * 判据、四条边界情形的处理与"判不出就回退"的纪律见 [translationPairRole]。
 *
 * ⚠️ 本函数**只回答"能不能配"**，不回答"谁当主行" —— 后者是 [translationPairRole]。
 * 分成两个函数是刻意的：配错（两句独立歌词被并成一主一副）与**角色**错
 * （并对了但大小字反了）是两种不同的失败模式，混在一个返回值里就没法分别断言。
 *
 * @param prev 行表里最后入表的那一行
 * @param next 本次新推来的行
 * @return `true` = 应当把 [next] 的文本并入 [prev] 的 `translation`，且 [next] 不入表
 */
fun isTranslationPair(prev: LyricLine, next: LyricLine): Boolean {
    // ① 制作信息行永不参与配对（见 [isMetadataLine] 的 KDoc：真机有一例会命中）。
    if (isMetadataLine(prev.text) || isMetadataLine(next.text)) return false
    // ② 主行已经有译文了 → 不再叠一层。
    if (!prev.translation.isNullOrBlank()) return false
    // ③ 新行自己带着译文 → 它是一行"完整"的歌词，不是纯译文行。
    if (!next.translation.isNullOrBlank()) return false
    // ④ 同时性：两行必须几乎同时开始（这是本判据的核心，见常量的 KDoc）。
    val onsetDelta = next.startMs - prev.startMs
    if (onsetDelta < 0L || onsetDelta > TRANSLATION_PAIR_ONSET_MS) return false
    // ⑤ 首尾相接（容忍宿主的时间取整）。
    val gap = next.startMs - prev.endMs
    if (gap < -TRANSLATION_PAIR_GAP_TOLERANCE_MS || gap > TRANSLATION_PAIR_GAP_TOLERANCE_MS) return false
    // ⑥ 书写系统互补。任一侧判不出（混排 / 太短 / 全是符号）→ 不配对。
    val prevScript = dominantScript(prev.text) ?: return false
    val nextScript = dominantScript(next.text) ?: return false
    return prevScript != nextScript
}

// ── 配对后的「主副角色」──────────────────────────────────────────────────────

/**
 * 配对后哪一侧当**主行**（大字、原文），另一侧进 `translation`（小字、译文）。
 *
 * 放在类外顶层，理由与 [isTranslationPair] 相同：探针是独立编译单元，
 * 看不到 `private`，而"角色反了"这种失败模式**不抛任何异常**，只能靠断言兜住。
 */
enum class PairRole {
    /** 先到的 [prev] 当主行。**这是回退值**，也是标题判不出时的唯一选择。 */
    PREV_IS_MAIN,

    /** 后到的 [next] 当主行（标题脚本指向它）。 */
    NEXT_IS_MAIN,
}

/**
 * 判定配对后的主副角色：**当前曲目标题的语言**决定哪一侧是原文。
 *
 * ## 为什么标题可用（这是真机上唯一可用的信号）
 *
 * 宿主没有给出任何直接标明"这一行是原文还是译文"的字段。但曲目标题**必然**与
 * 原文同语言 —— `Beat It`（Michael Jackson）是英文歌、`海阔天空`（Beyond）是中文歌。
 * 于是"标题的书写系统 == 哪一侧的书写系统，那一侧就是原文"。
 * 它用的是已有的 [dominantScript]，不需要词典、不需要联网。
 *
 * ## 四条边界情形（逐条都想过，且都**回退**而不是猜）
 *
 * | 情形 | [titleScript] | 处理 |
 * |---|---|---|
 * | 标题**中英混排**（`光辉岁月 - BEYOND`） | `null`（混排不表态） | **回退** `PREV_IS_MAIN` |
 * | 标题**为空**（宿主还没报曲目） | `null` | **回退** `PREV_IS_MAIN` |
 * | 标题是**第三种语言**（日/韩/俄） | 如 `KANA` | 只有某一侧的脚本**恰好等于**它才定性；否则回退 |
 * | **纯音乐 / 无人声**（标题是任意文本） | 任意或 `null` | 无歌词行 → 本函数根本不被调用；真有行时按上面三条走 |
 *
 * 第三种语言那条值得展开：日语歌标题判 `KANA`，而日语原文行**通常含假名**（判 `KANA`）
 * → 命中、定性正确；若原文行是**全汉字**（判 `HAN`），它与中文译文同判 `HAN`，
 * [isTranslationPair] 第 ⑥ 条就已经拒绝配对了，轮不到本函数。
 *
 * ## 为什么宁可回退也不猜
 *
 * 回退的后果只是"这一对的主副可能反"（改动前的行为），而一个"看起来有道理但会
 * 系统性搞错"的规则会让**所有**判不出的曲目一起错。两者不对称，故取回退。
 * 回退值选 [PairRole.PREV_IS_MAIN] 而不是别的：它就是改动前的行为，
 * 判不出时至少不比以前差。
 *
 * @param prev 先到的行（配对判据里的 `prev`）
 * @param next 后到的行
 * @param titleScript `dominantScript(当前曲目标题)`；`null` 表示判不出
 * @return 主行在哪一侧
 */
fun translationPairRole(prev: LyricLine, next: LyricLine, titleScript: Script?): PairRole {
    // 标题判不出 → 回退"先到的是主行"（= 改动前的行为）。
    if (titleScript == null) return PairRole.PREV_IS_MAIN
    val prevScript = dominantScript(prev.text)
    val nextScript = dominantScript(next.text)
    // 调用点已保证 `isTranslationPair` 为 true（两侧脚本都判得出且不同），
    // 故下面两个分支最多命中一个；都不命中时同样回退。
    if (prevScript == titleScript && nextScript != titleScript) return PairRole.PREV_IS_MAIN
    if (nextScript == titleScript && prevScript != titleScript) return PairRole.NEXT_IS_MAIN
    return PairRole.PREV_IS_MAIN
}

// ── 一个原文 ↔ 连续多个译文行 ────────────────────────────────────────────────

/**
 * 多段译文之间的**连接符**：一个半角空格。
 *
 * ## 为什么不是换行符 `\n`（实测取证，不是猜的）
 *
 * `translation` 是单个 `String?`，而渲染层 `IslandWindow.drawTranslationLine`
 * **只画一行**（它建一个 `TextLayout` 后一次 `render`）。实测
 * （JDK 21，`java.awt.font.TextLayout`）：
 *
 * | 文本 | advance | bounds |
 * |---|---|---|
 * | `"abc\ndef"` | **50.0**（与 `"abcdef"` 完全相同） | 与 `"abcdef"` 完全相同 |
 * | `"\n"` 单独 | **0.0** | 空 |
 *
 * 即 `TextLayout` **不把 `\n` 当换行**，它只是一个宽度为 0 的字符 ——
 * 用换行符拼接，两段译文会**直接粘连**成 `他们先是又踢有打然后他们会说：扯平了！`，
 * 看起来像一句没断开的话，比现在（第二段单独占一个主槽位）更难读。
 *
 * 用空格则：① 渲染层零改动；② 两段之间有可见间隔；③ 字形命中域（`GlyphHitTester`）
 * 照常工作。代价是译文变长、可能超出面板宽度 —— 那是**既有**问题
 * （窗口宽度只按主行文本测量，译文从不参与测宽），本改动只是让它更容易被看到。
 */
private const val TRANSLATION_SEGMENT_SEPARATOR = " "

/**
 * 一个原文最多能并入多少段译文。再多就不像"一句话被拆开"了，
 * 而更像"相邻的异脚本行被一路吞进来"。
 *
 * 真机取证：全量 527 行里只有 **1 处**真续接（Beat It 的 2 段），
 * 其余 5 处"相邻 + 异脚本 + 紧邻"的候选**全是下一对的引导行**（见 [isTranslationContinuation]）。
 */
const val MAX_TRANSLATION_SEGMENTS = 3

/**
 * 续接段时长的下限（相对**已并入译文的总时长**）：`本段时长 * 3 >= 已并入总时长`。
 *
 * ## 为什么必须有这条守卫（这是本轮最重要的发现）
 *
 * 只看"异脚本 + 与上一段紧邻"会**系统性地误合并**：真机全量复算里，
 * 该判据给出的 6 个候选里 **5 个是错的**，只有 1 个是真的。
 * 错的 5 个全是**下一对的引导行** —— 本歌词源把"一对"写成
 * 【引导行(极短) + 正式行(长)】，而当下一对是"译文在前"的顺序时，
 * 它的引导行恰好是异脚本且紧邻上一段译文的末尾，长得和"续接"一模一样。
 *
 * 区分二者的是**时长量级**：一句话被拆成的两半，时长在同一量级；
 * 而引导行的时长总是极短（真机实测 50 / 180 / 290 / 350 / 520 ms，
 * 对应的已并入译文是 3000 / 2950 / 2750 / 2800 / 2600 ms，比值全部 ≤ 0.2）。
 * 真的那一处是 1150 ms 对 1920 ms（比值 0.6）。
 *
 * ## 这条守卫的代价（如实说明）
 *
 * 若某首歌真的把译文拆成"长段 + 很短的半句"，续接会被拒绝 → 退回改动前的行为
 * （第二段单独占一个主槽位）。**漏配不丢字**，而误合并会把一句真歌词变小字。
 * 两者不对称，故偏向"宁可不合"。
 */
private const val MIN_CONTINUATION_DURATION_DIVISOR = 3L

/**
 * [next] 是不是 [prev] 已并入译文的**续接段**（同一个原文的第二、三段译文）。
 *
 * ## 与 [isTranslationPair] 的分工
 *
 * [isTranslationPair] 管**第一段**译文，它的核心判据是"两行几乎同时开始"
 * （`next.startMs - prev.startMs ∈ 0..1000`）—— 因为原文与译文是同一句词的两个版本，
 * 必然同时开始。而**续接段不是**：它是译文的后半句，起点在译文前半段的末尾，
 * 与主行的起点可以相差数秒（真机样本 1920 ms）。故这里**不用起点差**，
 * 改用"与**上一段译文的结束时间**紧邻"（`prev.translationEndMs`）。
 *
 * ## 全部条件（任一不成立就不合并）
 *
 * | 判据 | 挡掉的是 |
 * |---|---|
 * | [prev] 已有译文 | 把"原文→译文→又一行"叠成两层 |
 * | 段数 < [MAX_TRANSLATION_SEGMENTS] | 相邻异脚本行被一路吞进来 |
 * | 任一侧是元数据行 | 制作信息被并进译文 |
 * | [next] 自带译文 | 它是一行"完整"的歌词，不是纯译文行 |
 * | 与 [LyricLine.translationEndMs] 紧邻（±[TRANSLATION_PAIR_GAP_TOLERANCE_MS]） | 中间隔了别的行的情形 |
 * | 书写系统与**主行**互补（两侧都要判得出） | 下一句原文（与主行同脚本）被当成续接 |
 * | 时长 ≥ 已并入译文总时长的 1/[MIN_CONTINUATION_DURATION_DIVISOR] | **下一对的引导行**（见该常量的取证） |
 *
 * @param prev 行表里最后入表的那一行（可能已带译文）
 * @param next 本次新推来的行
 * @return `true` = 应当把 [next] 的文本接到 [prev] 的 `translation` 末尾，且 [next] 不入表
 */
fun isTranslationContinuation(prev: LyricLine, next: LyricLine): Boolean {
    // ① 只有"已经配过对"的行才有续接可言（否则那是 [isTranslationPair] 的事）。
    val existing = prev.translation
    if (existing.isNullOrBlank()) return false
    if (prev.translationSegments <= 0) return false
    // ② 上限。
    if (prev.translationSegments >= MAX_TRANSLATION_SEGMENTS) return false
    // ③ 制作信息行永不参与（与 [isTranslationPair] 同一条防线）。
    if (isMetadataLine(prev.text) || isMetadataLine(next.text)) return false
    // ④ 新行自己带着译文 → 它是完整歌词，不是纯译文行。
    if (!next.translation.isNullOrBlank()) return false
    // ⑤ 与**上一段译文**的末尾紧邻（不是与主行的 endMs，理由见 translationEndMs 的说明）。
    val anchor = prev.translationEndMs ?: prev.endMs
    val gap = next.startMs - anchor
    if (gap < -TRANSLATION_PAIR_GAP_TOLERANCE_MS || gap > TRANSLATION_PAIR_GAP_TOLERANCE_MS) return false
    // ⑥ 书写系统必须与**主行**互补 —— 与主行同脚本的行是"下一句原文"，不是译文。
    val mainScript = dominantScript(prev.text) ?: return false
    val nextScript = dominantScript(next.text) ?: return false
    if (mainScript == nextScript) return false
    // ⑦ 时长量级守卫：挡住"下一对的引导行"。理由与取证见该常量。
    //
    // 比较基准 = **已并入译文的总时长**（`translationEndMs − prev.startMs`）。
    // 为什么不拿"上一段自己的时长"：本类只存了译文的**结束**时间，没存各段的起点 ——
    // 要精确知道上一段的起点就得再加一个字段，而本改动刻意把新增字段压到两个。
    // 用 `prev.startMs` 当译文起点是一个**下界**（[isTranslationPair] 保证第一段译文
    // 不早于主行开始，续接段更在其后），故算出的"已并入时长"**偏大**。
    // 偏大是安全方向：它只会让更多候选被**拒绝**（漏配 → 退回改动前的行为），
    // 不会让不该合的合上。漏配不丢字，误合会把一句真歌词变小字 —— 两者不对称。
    val mergedTranslationMs = (prev.translationEndMs ?: prev.endMs) - prev.startMs
    val nextMs = next.endMs - next.startMs
    if (mergedTranslationMs > 0L && nextMs * MIN_CONTINUATION_DURATION_DIVISOR < mergedTranslationMs) return false
    return true
}
