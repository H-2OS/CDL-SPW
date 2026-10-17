// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

/**
 * 一份完整的歌词文档：若干行 + 是否带时间轴。
 *
 * [isSynced] 为 false 表示"纯文本歌词"（没有可靠时间标签）。这个标记必须显式保留，
 * 而不是用"行时间全为 0"来隐式表达 —— 因为 0 是合法时间，一份恰好从 0ms 开始
 * 且只有一行的歌词，与一份纯文本歌词在数据上无法区分。渲染层要据此关闭时间驱动的
 * 高亮与滚动（纯文本歌词只做静态显示），所以这个位必须准确。
 */
data class LyricDocument(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
) {

    /**
     * 二分查找 [positionMs] 时刻应显示的歌词行下标。
     *
     * ## 为什么保留间隙期的上一行（而不是返回 -1）
     * 歌词行之间存在间隙（纯音乐过门），间隙里"当前没有正在唱的行"。若此时返回 -1，
     * 词岛会在每段过门**闪成空白**，紧接着下一行又出现 —— 视觉上反复闪断。
     * 因此约定：返回**最后一个 `startMs <= positionMs` 的行**，让上一行在过门期间
     * 保持显示。这与原实现灵动词岛 `ActiveLyrics` 的处理一致（GPL-3.0-only 参考实现）。
     *
     * ## 返回值契约
     * - 空文档 / 非同步歌词 / 时间早于第一行 → -1（"无当前行"）
     * - 否则 → 最后一个 `startMs <= positionMs` 的下标
     *
     * ## 为什么手写二分而不是调 stdlib
     * stdlib 的 `binarySearch` 在未命中时返回的是"插入点"的负数编码（`-insertionPoint - 1`），
     * 直接拿来当中缀需要额外转换，且容易漏掉"未命中时应取前一个元素"这一语义。
     * 这里每帧都要调用，正确性比简洁重要 —— 手写的 lo/hi 循环能把不变量写清楚：
     * 循环结束时 `hi` 恰好指向答案（最后一个 `startMs <= positionMs`）。
     */
    fun lineAt(positionMs: Long): Int {
        if (!isSynced) return -1
        val count = lines.size
        if (count == 0) return -1
        // 早于第一行：无当前行（此时还没有任何一行开始，保留空显示是正确的）。
        if (positionMs < lines[0].startMs) return -1

        // 不变量：答案必然落在 [lo, hi] 闭区间内。
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            // 上取整中点。若用下取整，`lo = mid` 在 lo 与 hi 相邻时会原地死循环。
            val mid = lo + (hi - lo + 1) / 2
            if (lines[mid].startMs <= positionMs) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        // 同一 startMs 出现多行时（一行多标签会展开成多行，时间可能相同），
        // 向前线性推进到最后一行 —— 让这一组里的最后一行生效，避免下标停在组内随机位置。
        var result = lo
        while (result + 1 < count && lines[result + 1].startMs == lines[result].startMs) {
            result++
        }
        return result
    }

    companion object {
        /** 空文档：用于"尚未收到任何歌词"的初始态，避免上层到处判 null。 */
        val EMPTY = LyricDocument(emptyList(), false)

        /**
         * 解析 LRC 文本。
         *
         * ## 绝不抛异常 —— 这是本方法的硬契约
         * 本方法运行在**宿主回调线程**内（歌词文本经 `onBeforeLoadLyrics` /
         * `onAfterLoadLyrics` 返回给宿主时，或由上层在回调路径上解析）。宿主回调里
         * 逃逸出去的异常会升级成宿主级故障，而且用户完全无从归因（"装了这个插件
         * 播放就崩"）。因此任何意外输入都必须降级为纯文本歌词，而不是抛。
         *
         * ## 支持的格式
         * - 时间标签 `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss.x]` / `[mm:ss]`
         * - 一行多标签：`[00:01.00][00:05.00]歌词` → 展开成两行（这是 LRC 的既有写法，
         *   用于同一句歌词重复出现，不展开会丢掉后面那次出现）
         * - 逐字标签 `<mm:ss.xx>`（Enhanced LRC），填充 [LyricLine.words]
         * - 可选 [translationText]：另一份 LRC，按 `startMs` 精确对齐后挂到行的 translation
         *
         * @param text 主歌词（LRC 或纯文本）
         * @param translationText 翻译歌词（LRC），无翻译时传 null
         */
        fun parse(text: String, translationText: String? = null): LyricDocument =
            runCatching { parseInternal(text, translationText) }
                .getOrElse {
                    // 走到这里说明解析器本身出了预期外的错。此时**连纯文本降级都不可信**，
                    // 最保守且仍然有用的做法：把原文按行原样给出、标记为非同步。
                    // 用户至少能看到歌词内容（而不是一片空白），且不会有错误的时间驱动。
                    val fallback = text.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { LyricLine(0L, 0L, it) }
                    LyricDocument(fallback, false)
                }

        private fun parseInternal(text: String, translationText: String?): LyricDocument {
            val parsed = parseLines(text)
            if (parsed.isEmpty()) {
                // 一行都没解析出时间标签 → 判为纯文本歌词，保留原文（丢掉纯空白行）。
                val plain = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LyricLine(0L, 0L, it) }
                return LyricDocument(plain, false)
            }

            val translations = if (translationText.isNullOrBlank()) {
                emptyMap()
            } else {
                parseLines(translationText).associateBy { it.startMs }
            }

            // 逐字结束时间需要"下一个字"的信息，因此在建行时先保留原始字序，
            // 这里补齐最后一个字的 endMs = 整行 endMs（逐字不可能唱到行尾之后）。
            // ⚠️ 这一步必须在**行 endMs 补齐之后**做，否则 line.endMs 仍等于行起点，
            // 会把最后一个字的区间压成零宽甚至反序（end < start）。
            // 因此这里先只做排序与行 endMs 补齐，逐字收尾放到最后统一处理。
            val withWords = parsed

            // 排序：先按 startMs，再按 endMs。加 endMs 次序是为了让顺序**稳定可预测** ——
            // 相同 startMs 的多标签展开行，其 endMs 由"下一行"决定，次序不确定会让
            // 二分查找的结果在两次相同的输入下发生漂移。
            val sorted = withWords.sortedWith(compareBy({ it.startMs }, { it.endMs }))

            // 补齐每行的 endMs = 下一行的 startMs。
            // 最后一行保持 endMs = startMs（开放式）：它没有后继，无法知道何时结束；
            // 置为 startMs 表示"零宽"，配合 progress 的退化分支即"立即视为已完成"，
            // 于是最后一行会一直停留显示而不会卡在进度 0（这正是"最后一句不消失"的预期）。
            val bounded = sorted.mapIndexed { index, line ->
                val next = sorted.getOrNull(index + 1)
                val end = if (next != null) next.startMs else line.startMs
                // endMs 不得小于 startMs（乱序输入可能造成），否则进度计算失去意义。
                line.copy(endMs = maxOf(end, line.startMs))
            }

            // 逐字收尾：把每行最后一个字的 endMs 修正为整行 endMs。
            // 放在这里（而非 stripWordTags 内）是因为此刻行的真实结束时间才确定。
            // 若该行被判定为零宽（end == start），最后一个字也会是零宽 —— 这是正确的，
            // 上层 LyricLine.effectiveWords 会因区间退化而放弃逐字，退回整行进度。
            val closedWords = bounded.map { line ->
                val cells = line.words
                if (cells.isNullOrEmpty()) {
                    line
                } else {
                    line.copy(
                        words = cells.mapIndexed { index, word ->
                            if (index == cells.lastIndex) word.copy(endMs = line.endMs) else word
                        }
                    )
                }
            }

            val withTranslation = closedWords.map { line ->
                val translation = translations[line.startMs]?.text
                line.copy(translation = translation)
            }

            return LyricDocument(withTranslation, true)
        }

        /**
         * 单份文本 → 行列表（不做 endMs 补齐、不做翻译对齐，由 [parseInternal] 统一处理）。
         * 返回空列表表示"这份文本里没有任何可识别的时间标签"。
         */
        private fun parseLines(text: String): List<LyricLine> {
            val out = ArrayList<LyricLine>()
            for (raw in text.lineSequence()) {
                val lineStart = findMetadataEnd(raw)
                // 整行都是元数据标签（如 `[ar:xxx]`）→ 跳过，不作为歌词内容。
                if (lineStart == null) continue
                val stamps = parseTimeTags(raw, lineStart)
                if (stamps.isEmpty()) continue
                // ⚠️ 正文必须从**时间标签之后**开始，不能从 lineStart 开始 ——
                // lineStart 指向的是第一个时间标签的 `[`，从那里取会把标签本身当成正文，
                // 用户会看到 "[00:01.00]歌词" 这种原始标签。这是必须单独跟踪正文起点的原因。
                val bodyStart = skipTimeTags(raw, lineStart)
                val body = raw.substring(bodyStart)
                val stripped = stripWordTags(body)
                if (stripped.text.isBlank()) continue
                val words = stripped.words
                for (stamp in stamps) {
                    out.add(
                        LyricLine(
                            startMs = stamp,
                            endMs = stamp,
                            text = stripped.text,
                            translation = null,
                            words = words?.map { it },
                        )
                    )
                }
            }
            return out
        }

        /**
         * 找到"非元数据"内容的起点；整行都是元数据时返回 null。
         *
         * ## 为什么必须有这一步
         * LRC 里 `[ar:歌手]`、`[ti:标题]`、`[al:专辑]`、`[by:制作]`、`[offset:±ms]`、
         * `[length:mm:ss]` 这些是**元数据标签**，与时间标签共用 `[` `]` 语法。
         * 朴素的解析器会把 `[ar:周杰伦]` 也当成一行歌词显示出来 —— 用户会在歌词顶部
         * 看到 "ar:周杰伦" 这种明显的脏数据。
         *
         * 判定依据：`[` 之后紧跟数字的是时间标签，否则是元数据。这里逐段跳过开头的
         * 元数据标签，返回第一个时间标签或正文的位置。
         */
        private fun findMetadataEnd(raw: String): Int? {
            var i = 0
            while (i < raw.length && raw[i] == '[') {
                val close = raw.indexOf(']', i + 1)
                if (close < 0) return null
                val head = raw.getOrNull(i + 1)
                if (head == null || !head.isDigit()) {
                    // 元数据标签：跳过它，继续看后面。
                    i = close + 1
                    continue
                }
                // 命中时间标签：从这里开始是正文（后续时间标签由 parseTimeTags 处理）。
                return i
            }
            // 没有以 `[` 开头：可能是纯正文（纯文本歌词），也可能整行都是元数据（已消费完）。
            return if (i == 0) 0 else if (i >= raw.length) null else i
        }

        /** 解析开头的连续时间标签，返回毫秒列表（一行多标签会返回多个）。 */
        private fun parseTimeTags(raw: String, from: Int): List<Long> {
            val out = ArrayList<Long>()
            var i = from
            while (i < raw.length && raw[i] == '[') {
                val close = raw.indexOf(']', i + 1)
                if (close < 0) break
                val ms = parseTimeTag(raw.substring(i + 1, close))
                if (ms == null) break
                out.add(ms)
                i = close + 1
            }
            return out
        }

        /** 跳过开头的连续时间标签，返回**正文**的起始下标。 */
        private fun skipTimeTags(raw: String, from: Int): Int {
            var i = from
            while (i < raw.length && raw[i] == '[') {
                val close = raw.indexOf(']', i + 1)
                if (close < 0) break
                if (parseTimeTag(raw.substring(i + 1, close)) == null) break
                i = close + 1
            }
            return i
        }

        /**
         * 解析单个时间标签体（不含方括号）。无法解析返回 null。
         * 接受 `mm:ss`、`mm:ss.x`、`mm:ss.xx`、`mm:ss.xxx`；也接受超过 60 的分钟后缀
         * 与超过 60 的秒（有些歌词源不规范），不做范围校验 —— 能算出来就用。
         */
        private fun parseTimeTag(body: String): Long? {
            val colon = body.indexOf(':')
            if (colon <= 0) return null
            val minutes = body.substring(0, colon).trim().toLongOrNull() ?: return null
            val rest = body.substring(colon + 1).trim()
            if (rest.isEmpty()) return null
            val dot = rest.indexOf('.')
            val secondsText = if (dot < 0) rest else rest.substring(0, dot)
            val seconds = secondsText.trim().toLongOrNull() ?: return null
            val fractionText = if (dot < 0) "" else rest.substring(dot + 1).trim()
            // 小数部分按位数补零到毫秒：`.5` = 500ms，`.05` = 50ms，`.005` = 5ms。
            // 直接 toLong 后乘是常见错法（会把 `.5` 当成 5ms），故补零到 3 位再取前 3 位。
            val millis = when {
                fractionText.isEmpty() -> 0L
                else -> fractionText.padEnd(3, '0').take(3).toLongOrNull() ?: return null
            }
            return minutes * 60_000L + seconds * 1_000L + millis
        }

        /** [stripWordTags] 的返回值：剥离逐字标签后的正文，以及逐字列表（无则 null）。 */
        private data class StrippedText(val text: String, val words: List<LyricWord>?)

        /**
         * 剥离正文中的逐字标签 `<mm:ss.xx>`，同时收集逐字时间轴。
         *
         * ## endMs 怎么定
         * 逐字标签只标注**每个字的开始时间**，没有结束时间。约定：一个字持续到
         * **下一个字开始**为止。最后一个字的结束时间在 [parseInternal] 里补成整行结束时间
         * （此处先填 0，因为此刻还不知道整行何时结束）。
         *
         * ## 为什么剥离后的文本要与字拼接结果保持一致
         * 上层 [LyricLine.effectiveWords] 会用"字拼接 == 整行文本"来校验对位是否可信。
         * 这里的实现天然满足该条件（正文与字来自同一次扫描），但保持这个性质很重要，
         * 它是唯一能发现"标签被嵌在词中间导致错位"的防线。
         */
        private fun stripWordTags(body: String): StrippedText {
            if ('<' !in body) return StrippedText(body.trim(), null)
            val text = StringBuilder()
            val words = ArrayList<LyricWord>()
            var i = 0
            var pendingStart: Long? = null
            var pendingText = StringBuilder()

            fun flush() {
                val start = pendingStart ?: return
                if (pendingText.isEmpty()) return
                // endMs 暂填 start，稍后由调用方按"下一个字"或"行尾"修正。
                words.add(LyricWord(startMs = start, endMs = start, text = pendingText.toString()))
                pendingText = StringBuilder()
            }

            while (i < body.length) {
                val ch = body[i]
                if (ch == '<') {
                    val close = body.indexOf('>', i + 1)
                    if (close < 0) {
                        // 未闭合的 `<`：当作普通字符，不要吞掉用户可见的文本。
                        text.append(ch)
                        if (pendingStart != null) pendingText.append(ch)
                        i++
                        continue
                    }
                    val ms = parseTimeTag(body.substring(i + 1, close))
                    if (ms == null) {
                        // 尖括号里不是合法时间（可能是普通文本如 `<3`）：原样保留。
                        val literal = body.substring(i, close + 1)
                        text.append(literal)
                        if (pendingStart != null) pendingText.append(literal)
                        i = close + 1
                        continue
                    }
                    // 遇到新的时间标签 → 结算上一个字。
                    flush()
                    pendingStart = ms
                    i = close + 1
                } else {
                    text.append(ch)
                    if (pendingStart != null) pendingText.append(ch)
                    i++
                }
            }
            flush()

            val visible = text.toString().trim()
            if (words.isEmpty()) return StrippedText(visible, null)
            // 把每个字的 endMs 修正为"下一个字的 startMs"。
            val closed = words.mapIndexed { index, word ->
                val next = words.getOrNull(index + 1)
                word.copy(endMs = next?.startMs ?: word.startMs)
            }
            // 单字且其区间必然等于"整行"（因为只有一个字）—— 交给 effectiveWords 判定即可，
            // 此处不做特殊处理，保持解析层只负责"忠实还原数据"。
            return StrippedText(visible, closed)
        }
    }
}
