// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

data class LyricDocument(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
) {

    fun lineAt(positionMs: Long): Int {
        if (!isSynced) return -1
        val count = lines.size
        if (count == 0) return -1

        if (positionMs < lines[0].startMs) return -1

        var lo = 0
        var hi = count - 1
        while (lo < hi) {

            val mid = lo + (hi - lo + 1) / 2
            if (lines[mid].startMs <= positionMs) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }

        var result = lo
        while (result + 1 < count && lines[result + 1].startMs == lines[result].startMs) {
            result++
        }
        return result
    }

    companion object {

        val EMPTY = LyricDocument(emptyList(), false)

        fun parse(text: String, translationText: String? = null): LyricDocument =
            runCatching { parseInternal(text, translationText) }
                .getOrElse {

                    val fallback = text.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { LyricLine(0L, 0L, it) }
                    LyricDocument(fallback, false)
                }

        private fun parseInternal(text: String, translationText: String?): LyricDocument {
            val parsed = parseLines(text)
            if (parsed.isEmpty()) {

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

            val withWords = parsed

            val sorted = withWords.sortedWith(compareBy({ it.startMs }, { it.endMs }))

            val bounded = sorted.mapIndexed { index, line ->
                val next = sorted.getOrNull(index + 1)
                val end = if (next != null) next.startMs else line.startMs

                line.copy(endMs = maxOf(end, line.startMs))
            }

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

        private fun parseLines(text: String): List<LyricLine> {
            val out = ArrayList<LyricLine>()
            for (raw in text.lineSequence()) {
                val lineStart = findMetadataEnd(raw)

                if (lineStart == null) continue
                val stamps = parseTimeTags(raw, lineStart)
                if (stamps.isEmpty()) continue

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

        private fun findMetadataEnd(raw: String): Int? {
            var i = 0
            while (i < raw.length && raw[i] == '[') {
                val close = raw.indexOf(']', i + 1)
                if (close < 0) return null
                val head = raw.getOrNull(i + 1)
                if (head == null || !head.isDigit()) {

                    i = close + 1
                    continue
                }

                return i
            }

            return if (i == 0) 0 else if (i >= raw.length) null else i
        }

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

            val millis = when {
                fractionText.isEmpty() -> 0L
                else -> fractionText.padEnd(3, '0').take(3).toLongOrNull() ?: return null
            }
            return minutes * 60_000L + seconds * 1_000L + millis
        }

        private data class StrippedText(val text: String, val words: List<LyricWord>?)

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

                words.add(LyricWord(startMs = start, endMs = start, text = pendingText.toString()))
                pendingText = StringBuilder()
            }

            while (i < body.length) {
                val ch = body[i]
                if (ch == '<') {
                    val close = body.indexOf('>', i + 1)
                    if (close < 0) {

                        text.append(ch)
                        if (pendingStart != null) pendingText.append(ch)
                        i++
                        continue
                    }
                    val ms = parseTimeTag(body.substring(i + 1, close))
                    if (ms == null) {

                        val literal = body.substring(i, close + 1)
                        text.append(literal)
                        if (pendingStart != null) pendingText.append(literal)
                        i = close + 1
                        continue
                    }

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

            val closed = words.mapIndexed { index, word ->
                val next = words.getOrNull(index + 1)
                word.copy(endMs = next?.startMs ?: word.startMs)
            }

            return StrippedText(visible, closed)
        }
    }
}
