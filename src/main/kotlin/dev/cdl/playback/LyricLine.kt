// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {

    fun progress(positionMs: Long): Float = when {
        positionMs < startMs -> 0f
        endMs <= startMs -> 1f
        else -> ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }
}

fun splitMainAndTranslation(mainText: String?, subText: String?): Pair<String, String?> {
    val main = mainText.orEmpty()
    val sub = subText?.trim().orEmpty()

    if (sub.isEmpty()) return main to null

    if (!main.endsWith(sub)) return main to null
    val stripped = main.dropLast(sub.length).trimEnd()
    if (stripped.isEmpty()) return main to null
    return stripped to sub
}

fun narrowWordsToText(words: List<LyricWord>, text: String): List<LyricWord> {
    if (text.isEmpty() || words.isEmpty()) return emptyList()
    val total = words.sumOf { it.text.length }

    if (total < text.length) return emptyList()

    val out = ArrayList<LyricWord>(words.size)
    var consumed = 0
    for (word in words) {
        if (consumed >= text.length) break
        val remaining = text.length - consumed
        if (word.text.length <= remaining) {

            out.add(word)
            consumed += word.text.length
        } else {

            out.add(word.copy(text = word.text.take(remaining)))
            consumed = text.length
        }
    }

    if (out.joinToString("") { it.text } != text) return emptyList()
    return out
}

fun narrowWordsToTranslation(
    words: List<LyricWord>,
    mainText: String,
    translationText: String,
): List<LyricWord> {
    if (words.isEmpty() || mainText.isEmpty() || translationText.isEmpty()) return emptyList()
    val joined = words.joinToString("") { it.text }

    if (!joined.startsWith(mainText)) return emptyList()
    if (!joined.endsWith(translationText)) return emptyList()

    val start = joined.length - translationText.length
    if (start < mainText.length) return emptyList()

    val out = ArrayList<LyricWord>(words.size)
    var offset = 0
    for (word in words) {
        val end = offset + word.text.length
        when {

            offset >= start -> out.add(word)

            end > start -> return emptyList()

        }
        offset = end
    }
    if (out.isEmpty()) return emptyList()

    if (out.joinToString("") { it.text } != translationText) return emptyList()
    return out
}

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null,

    val translationWords: List<LyricWord>? = null,

    val translationSegments: Int = 0,

    val translationEndMs: Long? = null,
) {

    val effectiveWords: List<LyricWord>
        get() = checkedWords(words, text)

    val effectiveTranslationWords: List<LyricWord>
        get() = checkedWords(translationWords, translation?.takeIf { it.isNotBlank() })

    val hasWordTimingEvidence: Boolean
        get() {
            val cells = effectiveWords
            if (cells.isEmpty()) return false
            return cells.any { it.startMs != startMs || it.endMs != endMs }
        }

    val hasTranslationTimingEvidence: Boolean
        get() {
            val cells = effectiveTranslationWords
            if (cells.isEmpty()) return false
            return cells.any { it.startMs != startMs || it.endMs != endMs }
        }

    fun progress(positionMs: Long): Float = when {
        positionMs < startMs -> 0f
        endMs <= startMs -> 1f
        else -> ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }
}

private fun checkedWords(cells: List<LyricWord>?, text: String?): List<LyricWord> {
    if (cells == null || cells.isEmpty()) return emptyList()
    if (text.isNullOrEmpty()) return emptyList()
    if (cells.joinToString("") { it.text } != text) return emptyList()
    if (cells.any { it.endMs < it.startMs }) return emptyList()
    if (cells.zipWithNext().any { (a, b) -> a.startMs > b.startMs }) return emptyList()
    return cells
}

fun sharedSweepFraction(line: LyricLine, positionMs: Long): Float {
    val words = line.effectiveWords
    if (words.isEmpty() || !line.hasWordTimingEvidence) return line.progress(positionMs)
    val total = words.sumOf { it.text.length }
    if (total <= 0) return line.progress(positionMs)
    var charsBefore = 0
    for (word in words) {
        val p = word.progress(positionMs).coerceIn(0f, 1f)
        if (p < 1f) {
            return ((charsBefore + word.text.length * p) / total).coerceIn(0f, 1f)
        }
        charsBefore += word.text.length
    }
    return 1f
}
