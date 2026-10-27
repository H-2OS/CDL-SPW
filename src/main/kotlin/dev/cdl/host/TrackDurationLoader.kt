// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import dev.cdl.platform.LocalTrackDuration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class TrackDurationLoader(

    private val read: (String) -> Long = LocalTrackDuration::read,
) : AutoCloseable {

    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { task -> Thread(task, "SPW Island track duration").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private val durationMs = AtomicLong(0L)

    private val requestedPath = AtomicReference("")

    private val token = AtomicLong(0L)

    @Volatile
    private var closed = false

    fun load(path: String) {
        if (closed) return
        if (path == requestedPath.get()) return
        requestedPath.set(path)
        durationMs.set(0L)

        if (path.isBlank()) {
            return
        }
        val mine = token.incrementAndGet()

        runCatching {
            executor.execute {
                val value = runCatching { read(path) }.getOrDefault(0L)

                if (!closed && token.get() == mine) {
                    durationMs.set(value)

                    if (value > 0L) {
                    } else {
                    }
                }
            }
        }
    }

    fun current(): Long = durationMs.get()

    override fun close() {
        closed = true

        runCatching { executor.shutdownNow() }
    }
}
