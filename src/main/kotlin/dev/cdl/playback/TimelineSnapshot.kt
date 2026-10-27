// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

data class TimelineSnapshot(

    val trackTitle: String,

    val artist: String,

    val album: String,

    val path: String,

    val isPlaying: Boolean,

    val positionMs: Long,

    val currentLineIndex: Int,

    val prevLine: LyricLine?,

    val currentLine: LyricLine?,

    val nextLine: LyricLine?,

    val currentLineProgress: Float,

    val docVersion: Long,

    val lyricLineCount: Int = 0,
) {
    companion object {

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
