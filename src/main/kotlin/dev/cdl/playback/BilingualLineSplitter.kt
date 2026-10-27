// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

fun splitBilingualLine(mainText: String?, subText: String?): Pair<String, String?> {

    val (main, sub) = splitMainAndTranslation(mainText, subText)
    if (sub != null) return main to sub

    return splitByScriptBoundary(main)
}

fun splitByScriptBoundary(text: String): Pair<String, String?> {
    if (text.isEmpty()) return text to null
    var space = text.indexOf(' ')
    while (space >= 0) {
        val head = text.substring(0, space)

        val tail = text.substring(space + 1).trim()
        if (tail.isNotEmpty() &&
            hasForeignScript(head) &&
            !hasForeignScript(tail) &&
            hasCjkIdeograph(tail)
        ) {
            val original = head.trimEnd()

            if (original.isNotEmpty()) return original to tail
        }
        space = text.indexOf(' ', space + 1)
    }
    return text to null
}

private fun hasForeignScript(s: CharSequence): Boolean {
    for (c in s) {
        if (isKana(c) || isLatinLetter(c) || isCyrillicLetter(c) || isHangul(c)) return true
    }
    return false
}

private fun hasCjkIdeograph(s: CharSequence): Boolean {
    for (c in s) if (isCjkIdeograph(c)) return true
    return false
}

private fun isKana(c: Char): Boolean =
    c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'

private fun isLatinLetter(c: Char): Boolean =
    Character.isLetter(c) && c.code <= 0x024F

private fun isCyrillicLetter(c: Char): Boolean =
    c in '\u0400'..'\u04FF'

private fun isHangul(c: Char): Boolean =
    c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F' || c in '\uAC00'..'\uD7AF'

private fun isCjkIdeograph(c: Char): Boolean =
    c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'

enum class Script {

    LATIN,

    HAN,

    KANA,

    HANGUL,

    CYRILLIC,
}

fun dominantScript(text: String?): Script? {
    if (text.isNullOrEmpty()) return null
    var latin = 0
    var han = 0
    var kana = 0
    var hangul = 0
    var cyrillic = 0
    var other = 0
    for (c in text) {
        when {

            c.isWhitespace() -> Unit
            isKana(c) -> kana++
            isHangul(c) -> hangul++
            isCjkIdeograph(c) -> han++
            isCyrillicLetter(c) -> cyrillic++
            isLatinLetter(c) -> latin++
            else -> other++
        }
    }

    if (kana > 0) return Script.KANA
    if (hangul > 0) return Script.HANGUL

    val present = ArrayList<Pair<Int, Script>>(3)
    if (latin > 0) present.add(latin to Script.LATIN)
    if (han > 0) present.add(han to Script.HAN)
    if (cyrillic > 0) present.add(cyrillic to Script.CYRILLIC)
    if (present.size != 1) return null
    val (count, script) = present[0]

    if (count < MIN_SCRIPT_CHARS) return null

    if (other > count) return null
    return script
}

fun isMetadataLine(text: String?): Boolean =
    text != null && METADATA_LINE_PATTERN.containsMatchIn(text)

fun isHostPlaceholderLine(text: String?): Boolean {
    val t = text?.trim() ?: return false
    if (t in HOST_PLACEHOLDER_LINES) return true

    if (normalizePlaceholder(t) in HOST_PLACEHOLDER_NORMALIZED_FORMS) return true

    if (looksLikeInstrumentalText(t)) return true

    return normalizePlaceholder(t) in HOST_PLACEHOLDER_LINES_NORMALIZED
}

private fun normalizePlaceholder(s: String): String =
    s.filterNot { it.isWhitespace() || it in "，,。.、！!~～·…—-" }

private val HOST_PLACEHOLDER_LINES = setOf(
    "纯音乐，请欣赏",
    "纯音乐请欣赏",
    "纯音乐",
    "暂无歌词",
)

private val HOST_PLACEHOLDER_LINES_NORMALIZED =
    HOST_PLACEHOLDER_LINES.map { normalizePlaceholder(it) }.toSet()

fun looksLikeInstrumentalText(text: String?): Boolean {
    val t = text?.trim() ?: return false
    if (t.isEmpty()) return false
    val n = normalizePlaceholder(t).lowercase()
    if (n.isEmpty()) return false

    if (n in INSTRUMENTAL_ENGLISH_FORMS) return true

    if (INSTRUMENTAL_TEXT_KEYWORDS.none { n.contains(it) }) return false
    return n.all { it in INSTRUMENTAL_TEXT_ALLOWED_CHARS }
}

private val INSTRUMENTAL_ENGLISH_FORMS = setOf(
    "instrumental", "instrumentalmusic", "instrumentalonly", "nolyrics",
)

private const val INSTRUMENTAL_TEXT_ALLOWED_CHARS =
    "纯音乐伴奏器无词未填暂歌词请欣赏该曲为的作品此首一首本段歌曲此首暂无" +
        "的了吧啊呀呢曲目内容信息提示"

private val INSTRUMENTAL_TEXT_KEYWORDS = listOf(
    "纯音乐", "伴奏", "器乐", "无词", "未填词", "暂无歌词",
)

const val INSTRUMENTAL_MIN_TRACK_MS = 60_000L

const val INSTRUMENTAL_MAX_COVER_PERCENT = 5L

fun lyricsLookInstrumental(lines: List<LyricLine>, trackDurationMs: Long): Boolean {
    if (trackDurationMs < INSTRUMENTAL_MIN_TRACK_MS) return false
    if (lines.isEmpty()) return true

    if (lines.all { isHostPlaceholderLine(it.text) || looksLikeCreditLine(it.text) }) return true
    val covered = coveredSpanMs(lines)
    return covered * 100L < trackDurationMs * INSTRUMENTAL_MAX_COVER_PERCENT
}

fun looksLikeCreditLine(text: String?): Boolean {
    val t = text?.trim() ?: return false
    if (t.isEmpty()) return false
    val hasColon = t.contains(':') || t.contains('：')

    if (hasColon) {
        val head = t.take(2)
        if (head == "词:" || head == "词：" || head == "曲:" || head == "曲：") return true
    }
    val n = normalizePlaceholder(t).lowercase()
    return CREDIT_PREFIXES.any { n.startsWith(it) }
}

private val CREDIT_PREFIXES = listOf(
    "作曲", "作词", "编曲", "制作人", "制作", "混音", "母带", "录音", "监制", "出品",
    "吉他", "贝斯", "鼓手", "键盘", "和声", "演唱", "原唱", "配唱", "弦乐", "合声",
)

fun coveredSpanMs(lines: List<LyricLine>): Long {
    if (lines.isEmpty()) return 0L
    val spans = lines
        .map { if (it.endMs > it.startMs) it.startMs to it.endMs else it.startMs to it.startMs }
        .sortedBy { it.first }
    var total = 0L
    var curStart = spans[0].first
    var curEnd = spans[0].second
    for (i in 1 until spans.size) {
        val (s, e) = spans[i]
        if (s <= curEnd) {
            if (e > curEnd) curEnd = e
        } else {
            total += curEnd - curStart
            curStart = s
            curEnd = e
        }
    }
    return total + (curEnd - curStart)
}

private val HOST_PLACEHOLDER_NORMALIZED_FORMS: Set<String> = buildSet {
    val prefixes = listOf("", "该歌曲为", "未填词的", "该歌曲为未填词的")
    val cores = listOf("纯音乐", "暂无歌词")
    val suffixes = listOf("", "请欣赏")
    for (p in prefixes) for (c in cores) for (s in suffixes) add(p + c + s)
}

private val METADATA_LINE_PATTERN = Regex("""^\s*\S+\s+:\s+\S""")

private const val MIN_SCRIPT_CHARS = 2
