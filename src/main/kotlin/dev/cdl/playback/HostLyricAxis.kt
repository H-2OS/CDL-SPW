// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

data class HostLyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String?,
    val cells: List<HostLyricCell>,
)

data class HostLyricCell(val startMs: Long, val endMs: Long, val text: String)

fun bilingualLinesFrom(lines: List<HostLyricLine>): List<HostLyricLine> {
    val out = ArrayList<HostLyricLine>(lines.size)
    val seen = HashSet<Triple<Long, Long, String>>(lines.size * 2)
    for (line in lines) {
        val mainText = separatedMainText(line.text, line.translation)

        if (mainText.isBlank() || isHostPlaceholderLine(mainText)) continue
        if (line.endMs < line.startMs) continue
        if (!seen.add(Triple(line.startMs, line.endMs, mainText))) continue
        out.add(
            HostLyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
                text = mainText,
                translation = separatedTranslation(line.text, line.translation),
                cells = line.cells,
            )
        )
    }
    return out
}

fun separatedMainText(mainText: String?, subText: String?): String {
    val sub = subText?.trim().orEmpty()

    if (sub.isNotEmpty()) return mainText.orEmpty().trimEnd()
    return splitBilingualLine(mainText, null).first
}

fun separatedTranslation(mainText: String?, subText: String?): String? {
    val sub = subText?.trim().orEmpty()
    if (sub.isNotEmpty()) return sub
    return splitBilingualLine(mainText, null).second
}
