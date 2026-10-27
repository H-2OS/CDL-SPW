// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

import java.util.concurrent.atomic.AtomicReference

class PlaybackTimeline(
    private val nanoTime: () -> Long = System::nanoTime,

    private val durationSupplier: () -> Long = { 0L },
) {

    private val snapshotRef = AtomicReference(TimelineSnapshot.EMPTY)

    @Volatile private var trackTitle: String = ""
    @Volatile private var artist: String = ""
    @Volatile private var album: String = ""
    @Volatile private var path: String = ""

    @Volatile private var playing: Boolean = false

    @Volatile private var hostState: String = "Idle"

    @Volatile private var positionMs: Long = 0L

    @Volatile private var anchorNs: Long = nanoTime()

    @Volatile private var pendingSeekTarget: Long? = null

    @Volatile private var pendingSeekDeadlineNs: Long = 0L

    @Volatile private var previousHeartbeatMs: Long = -1L

    @Volatile private var playingSignalObserved: Boolean = false

    @Volatile private var heartbeatInferenceAllowed: Boolean = true

    private val documentRef = AtomicReference(LyricDocument.EMPTY)

    @Volatile private var docVersion: Long = 0L

    fun trackChanged(title: String, artist: String, album: String, path: String) {
        trackTitle = title
        this.artist = artist
        this.album = album
        this.path = path
        positionMs = 0L
        anchorNs = nanoTime()
        pendingSeekTarget = null
        resetHeartbeat()

        replaceDocument(LyricDocument.EMPTY)
        publish()
    }

    fun positionChanged(value: Long) {
        val now = nanoTime()
        val next = value.coerceAtLeast(0)

        val target = pendingSeekTarget
        if (target != null) {
            val expired = now >= pendingSeekDeadlineNs
            val closeEnough = kotlin.math.abs(next - target) <= SEEK_ACK_TOLERANCE_MS
            if (!expired && !closeEnough) {

                return
            }
            pendingSeekTarget = null
        }

        if (updateHeartbeatInference(next)) {
            playing = true
            if (hostState == STATE_IDLE) hostState = STATE_READY
        }

        positionMs = next
        anchorNs = now
        publish()
    }

    fun seek(value: Long) {
        val now = nanoTime()
        val next = value.coerceAtLeast(0)
        positionMs = next
        anchorNs = now
        pendingSeekTarget = next
        pendingSeekDeadlineNs = now + SEEK_ACK_WINDOW_NS
        previousHeartbeatMs = -1L

        publish()
    }

    fun playingChanged(value: Boolean) {
        val now = nanoTime()

        positionMs = extrapolatedPosition(now)
        anchorNs = now
        playingSignalObserved = true
        playing = value
        if (value && hostState == STATE_IDLE) hostState = STATE_READY
        publish()
    }

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

            trackTitle = ""
            artist = ""
            album = ""
            path = ""
            positionMs = 0L
        }
        publish()
    }

    fun lineChanged(
        startMs: Long,
        endMs: Long,
        text: String,
        translation: String?,
        words: List<LyricWord>?,

        translationWords: List<LyricWord>? = null,
    ) {

        if (text.isBlank() || isHostPlaceholderLine(text)) {

            if (text.isNotBlank()) {
            }
            return
        }

        if (endMs < startMs) {

            return
        }

        val current = documentRef.get()
        val existing = current.lines

        val dupIndex = existing.indexOfFirst {
            it.startMs == startMs && it.endMs == endMs && it.text == text
        }
        if (dupIndex >= 0) {
            val old = existing[dupIndex]
            if (old.translation != translation || old.translationWords != translationWords) {
                val updated = old.copy(

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

        val previous = existing.lastOrNull()
        if (previous != null && isTranslationPair(previous, line)) {

            val merged = mergeTranslationPair(previous, line, dominantScript(trackTitle))
            documentRef.set(LyricDocument(existing.dropLast(1) + merged, true))
            docVersion++
            publish()
            return
        }

        if (previous != null && isTranslationContinuation(previous, line)) {

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

        val mergedLines = (existing + line).sortedWith(compareBy({ it.startMs }, { it.endMs }))

        documentRef.set(LyricDocument(mergedLines, true))
        docVersion++
        publish()
    }

    fun lineCleared() {

    }

    fun documentChanged(document: LyricDocument) {
        replaceDocument(document)
        publish()
    }

    fun documentReplaced(document: LyricDocument) {

        val folded = foldTranslationPairs(document.lines, dominantScript(trackTitle))
        if (folded.size != document.lines.size) {

        }
        replaceDocument(LyricDocument(folded, document.isSynced))
        publish()
    }

    fun documentCleared() {

    }

    fun snapshot(): TimelineSnapshot {
        val now = nanoTime()
        val currentMs = extrapolatedPosition(now)
        val rawDoc = documentRef.get()

        val doc = if (lyricsLookInstrumental(rawDoc.lines, safeTrackDurationMs())) {
            LyricDocument.EMPTY
        } else {
            rawDoc
        }
        val index = doc.lineAt(currentMs)
        val currentLine = doc.lines.getOrNull(index)
        val prevLine = if (index > 0) doc.lines.getOrNull(index - 1) else null
        val rawNext = doc.lines.getOrNull(index + 1)

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

            lyricLineCount = doc.lines.size,
        )
    }

    fun currentLineIndex(atMs: Long): Int = documentRef.get().lineAt(atMs)

    fun lineProgress(atMs: Long): Float {
        val doc = documentRef.get()
        val line = doc.lines.getOrNull(doc.lineAt(atMs)) ?: return 0f
        return line.progress(atMs)
    }

    private fun extrapolatedPosition(now: Long): Long {
        if (!isEffectivelyPlaying(now)) return positionMs
        val elapsedMs = (now - anchorNs) / 1_000_000L
        if (elapsedMs <= 0) return positionMs
        return positionMs + elapsedMs.coerceIn(0L, MAX_EXTRAPOLATION_MS)
    }

    private fun isEffectivelyPlaying(now: Long): Boolean {
        if (playingSignalObserved) return playing

        if (hostState == STATE_IDLE || hostState == STATE_ENDED) return false
        if (!heartbeatInferenceAllowed) return false
        return now - anchorNs <= HEARTBEAT_FRESHNESS_NS
    }

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

    private fun safeTrackDurationMs(): Long {
        val v = durationSupplier()
        return if (v in 1..MAX_PLAUSIBLE_TRACK_MS) v else 0L
    }

    private fun replaceDocument(document: LyricDocument) {

        documentRef.set(document)
        docVersion++
    }

    private fun nextLineFor(rawNext: LyricLine?): LyricLine? = rawNext

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

        const val STATE_IDLE = "Idle"

        const val STATE_BUFFERING = "Buffering"
        const val STATE_READY = "Ready"
        const val STATE_ENDED = "Ended"

        private const val MAX_EXTRAPOLATION_MS = 2_500L

        private const val SEEK_ACK_WINDOW_NS = 2_500_000_000L

        private const val SEEK_ACK_TOLERANCE_MS = 2_000L

        private const val MAX_HEARTBEAT_STEP_MS = 2_500L

        private const val HEARTBEAT_FRESHNESS_NS = 2_500_000_000L
    }
}

private const val TRANSLATION_PAIR_ONSET_MS = 1_000L

private const val TRANSLATION_PAIR_GAP_TOLERANCE_MS = 50L

fun isTranslationPair(prev: LyricLine, next: LyricLine): Boolean {

    if (isMetadataLine(prev.text) || isMetadataLine(next.text)) return false

    if (!prev.translation.isNullOrBlank()) return false

    if (!next.translation.isNullOrBlank()) return false

    val onsetDelta = next.startMs - prev.startMs
    if (onsetDelta < 0L || onsetDelta > TRANSLATION_PAIR_ONSET_MS) return false

    val gap = next.startMs - prev.endMs
    if (gap < -TRANSLATION_PAIR_GAP_TOLERANCE_MS || gap > TRANSLATION_PAIR_GAP_TOLERANCE_MS) return false

    val prevScript = dominantScript(prev.text) ?: return false
    val nextScript = dominantScript(next.text) ?: return false
    return prevScript != nextScript
}

enum class PairRole {

    PREV_IS_MAIN,

    NEXT_IS_MAIN,
}

fun translationPairRole(prev: LyricLine, next: LyricLine, titleScript: Script?): PairRole {

    if (titleScript == null) return PairRole.PREV_IS_MAIN
    val prevScript = dominantScript(prev.text)
    val nextScript = dominantScript(next.text)

    if (prevScript == titleScript && nextScript != titleScript) return PairRole.PREV_IS_MAIN
    if (nextScript == titleScript && prevScript != titleScript) return PairRole.NEXT_IS_MAIN
    return PairRole.PREV_IS_MAIN
}

fun mergeTranslationPair(prev: LyricLine, next: LyricLine, titleScript: Script?): LyricLine =
    when (translationPairRole(prev, next, titleScript)) {
        PairRole.PREV_IS_MAIN -> prev.copy(
            translation = next.text,

            translationWords = next.words,
            translationSegments = 1,

            translationEndMs = next.endMs,
        )

        PairRole.NEXT_IS_MAIN -> prev.copy(
            text = next.text,
            words = next.words,
            translation = prev.text,
            translationWords = prev.words,
            translationSegments = 1,
            translationEndMs = prev.endMs,
        )
    }

private const val TRANSLATION_SEGMENT_SEPARATOR = " "

const val MAX_TRANSLATION_SEGMENTS = 3

private const val MIN_CONTINUATION_DURATION_DIVISOR = 3L

fun isTranslationContinuation(prev: LyricLine, next: LyricLine): Boolean {

    val existing = prev.translation
    if (existing.isNullOrBlank()) return false
    if (prev.translationSegments <= 0) return false

    if (prev.translationSegments >= MAX_TRANSLATION_SEGMENTS) return false

    if (isMetadataLine(prev.text) || isMetadataLine(next.text)) return false

    if (!next.translation.isNullOrBlank()) return false

    val anchor = prev.translationEndMs ?: prev.endMs
    val gap = next.startMs - anchor
    if (gap < -TRANSLATION_PAIR_GAP_TOLERANCE_MS || gap > TRANSLATION_PAIR_GAP_TOLERANCE_MS) return false

    val mainScript = dominantScript(prev.text) ?: return false
    val nextScript = dominantScript(next.text) ?: return false
    if (mainScript == nextScript) return false

    val mergedTranslationMs = (prev.translationEndMs ?: prev.endMs) - prev.startMs
    val nextMs = next.endMs - next.startMs
    if (mergedTranslationMs > 0L && nextMs * MIN_CONTINUATION_DURATION_DIVISOR < mergedTranslationMs) return false
    return true
}

fun foldTranslationPairs(
    lines: List<LyricLine>,
    titleScript: Script?,
): List<LyricLine> {
    if (lines.isEmpty()) return lines
    val out = ArrayList<LyricLine>(lines.size)
    var i = 0
    while (i < lines.size) {
        val head = lines[i]

        if (!head.translation.isNullOrBlank()) {
            out.add(head)
            i++
            continue
        }
        var merged = head
        var j = i + 1

        if (j < lines.size && isTranslationPair(merged, lines[j])) {

            merged = mergeTranslationPair(merged, lines[j], titleScript)
            j++

            while (j < lines.size && isTranslationContinuation(merged, lines[j])) {
                merged = merged.copy(
                    translation = merged.translation.orEmpty() +
                        TRANSLATION_SEGMENT_SEPARATOR + lines[j].text,
                    translationSegments = merged.translationSegments + 1,
                    translationEndMs = lines[j].endMs,
                )
                j++
            }
        }
        out.add(merged)
        i = j
    }
    return out
}

private const val MAX_PLAUSIBLE_TRACK_MS = 6L * 3600 * 1000
