// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.font.TextHitInfo
import java.awt.font.TextLayout

const val KARAOKE_ALL_SUNG = -1

data class KaraokeBoundaryPick(
    val charIndex: Int,
    val ratio: Float,
    val length: Int,
)

fun karaokeBoundaryPick(
    wordBoundaries: FloatArray,
    wordLengths: IntArray,
    totalChars: Int = Int.MAX_VALUE,
): KaraokeBoundaryPick {
    var leadingChars = 0
    for (i in wordBoundaries.indices) {
        val ratio = wordBoundaries[i]
        if (ratio < 1f) {
            val len = wordLengths.getOrElse(i) { 1 }.coerceAtLeast(1)

            val room = if (totalChars <= leadingChars) 1 else totalChars - leadingChars
            return KaraokeBoundaryPick(
                charIndex = leadingChars,
                ratio = ratio.coerceIn(0f, 1f),
                length = len.coerceAtMost(room),
            )
        }
        leadingChars += wordLengths.getOrElse(i) { 1 }
    }
    return KaraokeBoundaryPick(charIndex = KARAOKE_ALL_SUNG, ratio = 1f, length = 1)
}

fun karaokeCaretX(layout: TextLayout, offset: Int): Float {
    val n = layout.characterCount

    if (n <= 0) return 0f
    return when {
        offset <= 0 -> layout.getCaretInfo(TextHitInfo.leading(0))[0]
        offset >= n -> layout.getCaretInfo(TextHitInfo.trailing(n - 1))[0]
        else -> layout.getCaretInfo(TextHitInfo.leading(offset))[0]
    }
}

fun karaokeSungCharCount(clusterStarts: IntArray, progress: Float): Int {
    if (clusterStarts.size < 2) return 0
    val n = clusterStarts.size - 1
    val clamped = progress.coerceIn(0f, 1f)
    if (clamped <= 0f) return clusterStarts[0]
    if (clamped >= 1f) return clusterStarts[n]
    val k = (clamped * n).toInt().coerceIn(0, n)
    return clusterStarts[k]
}
