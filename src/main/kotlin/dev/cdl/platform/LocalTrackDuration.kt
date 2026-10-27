// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import kotlin.math.roundToLong

internal object LocalTrackDuration {

    fun read(path: String): Long {
        if (path.isEmpty()) return 0L
        val file = File(path)

        if (!file.isFile) return 0L
        val audio = runCatching { AudioFileIO.read(file) }.getOrNull() ?: return 0L

        val seconds = runCatching { audio.audioHeader?.preciseTrackLength }.getOrNull() ?: return 0L
        if (!seconds.isFinite() || seconds <= 0.0) return 0L

        val ms = (seconds * 1000.0).roundToLong()
        return if (ms in 1..MAX_DURATION_MS) ms else 0L
    }

    private const val MAX_DURATION_MS = 100L * 3600L * 1000L
}
