// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

internal class CallbackSink {

    private sealed interface Event {
        val kind: String

        data class Track(val item: PlaybackExtensionPoint.MediaItem) : Event {
            override val kind = "track"
        }

        data class State(val state: PlaybackExtensionPoint.State) : Event {
            override val kind = "state"
        }

        data class Playing(val playing: Boolean) : Event {
            override val kind = "playing"
        }

        data class Position(val positionMs: Long) : Event {
            override val kind = "position"
        }

        data class Seek(val positionMs: Long) : Event {
            override val kind = "seek"
        }

        data class Line(val line: PlaybackExtensionPoint.LyricsLine?) : Event {
            override val kind = "line"
        }

        data class Lines(val lines: List<PlaybackExtensionPoint.LyricsLine>) : Event {
            override val kind = "lines"
        }
    }

    private val lock = Any()

    private val pending = ArrayDeque<Event>()

    private var dropped = 0

    @Volatile
    private var target: CdlRuntime? = null

    fun attach(runtime: CdlRuntime) {
        val backlog: List<Event>
        synchronized(lock) {
            target = runtime
            backlog = pending.toList()
            pending.clear()
            if (backlog.isNotEmpty()) {
            }
        }

        backlog.forEach { dispatch(runtime, it) }
    }

    fun detach(runtime: CdlRuntime) {
        synchronized(lock) {
            if (target === runtime) target = null
        }
    }

    fun trackChanged(mediaItem: PlaybackExtensionPoint.MediaItem) =
        offer(Event.Track(mediaItem))

    fun stateChanged(state: PlaybackExtensionPoint.State) = offer(Event.State(state))

    fun playingChanged(playing: Boolean) = offer(Event.Playing(playing))

    fun positionChanged(positionMs: Long) = offer(Event.Position(positionMs))

    fun seek(positionMs: Long) = offer(Event.Seek(positionMs))

    fun lineChanged(line: PlaybackExtensionPoint.LyricsLine?) = offer(Event.Line(line))

    fun linesChanged(lines: List<PlaybackExtensionPoint.LyricsLine>) =
        offer(Event.Lines(ArrayList(lines)))

    private fun offer(event: Event) {

        val ready: CdlRuntime = synchronized(lock) {
            val current = target
            if (current == null) {
                if (pending.size >= MAX_BUFFERED) {
                    pending.removeFirst()
                    dropped++
                }
                pending.addLast(event)
                return
            }
            current
        }
        dispatch(ready, event)
    }

    private fun dispatch(runtime: CdlRuntime, event: Event) {
        val value = when (event) {
            is Event.Track -> "${event.item.title} — ${event.item.artist}"
            is Event.State -> event.state.name
            is Event.Playing -> event.playing.toString()
            is Event.Position -> event.positionMs.toString()
            is Event.Seek -> event.positionMs.toString()

            is Event.Line -> event.line?.let { line ->
                val cells = line.lyricsCells
                val dump = cells.take(MAX_LOGGED_CELLS).joinToString(",") {
                    "\"${it.text}\"@${it.startTime}-${it.endTime}"
                }
                val tail = if (cells.size > MAX_LOGGED_CELLS) {
                    ",…（共 ${cells.size} 项，仅列前 $MAX_LOGGED_CELLS）"
                } else {
                    ""
                }
                "main=\"${line.pureMainText}\"(len=${line.pureMainText.length}) | " +
                    "sub=${line.pureSubText?.let { "\"$it\"" } ?: "<null>"} | " +
                    "cells=${cells.size}[$dump$tail] | span=${line.startTime}-${line.endTime}"
            } ?: "<null>"

            is Event.Lines -> event.lines.let { lines ->
                if (lines.isEmpty()) {
                    "<empty>"
                } else {
                    val first = lines.first()
                    val last = lines.last()
                    val withSub = lines.count { !it.pureSubText.isNullOrBlank() }
                    "count=${lines.size} | sub!=null=$withSub | " +
                        "first=${first.startTime}ms\"${first.pureMainText.take(LOGGED_AXIS_TEXT).let {
                            if (first.pureMainText.length > LOGGED_AXIS_TEXT) "$it…" else it
                        }}\" | last=${last.startTime}-${last.endTime}ms"
                }
            }
        }

        runCatching { runtime.onPlaybackEvent(event.kind, value) }
            .onFailure {  }

        runCatching {
            runtime.onPlaybackCallback(
                when (event) {
                is Event.Track -> CdlRuntime.StructuredEvent.Track(event.item)
                is Event.State -> CdlRuntime.StructuredEvent.State(event.state)
                is Event.Playing -> CdlRuntime.StructuredEvent.Playing(event.playing)
                is Event.Position -> CdlRuntime.StructuredEvent.Position(event.positionMs)
                is Event.Seek -> CdlRuntime.StructuredEvent.Seek(event.positionMs)
                is Event.Line -> CdlRuntime.StructuredEvent.Line(event.line)
                    is Event.Lines -> CdlRuntime.StructuredEvent.Lines(event.lines)
                }
            )
        }.onFailure {  }

    }

    private companion object {

        const val MAX_BUFFERED = 256

        const val MAX_LOGGED_CELLS = 16

        const val LOGGED_AXIS_TEXT = 24
    }
}
