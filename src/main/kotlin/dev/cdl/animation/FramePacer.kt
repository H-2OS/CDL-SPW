// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.animation

class FramePacer(
    private val intervalMs: () -> Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {

    private var epochNanos = 0L

    private var frameIndex = 0L

    fun reset() {
        epochNanos = clockNanos()
        frameIndex = 0L
    }

    fun onFrameDelivered() {
        frameIndex++
    }

    fun nextDeadlineNanos(): Long =
        epochNanos + (frameIndex + 1) * intervalMs() * NANOS_PER_MILLI

    fun delayToNextMs(): Int {
        val remainingNanos = nextDeadlineNanos() - clockNanos()
        val ms = Math.floorDiv(remainingNanos, NANOS_PER_MILLI)
        return ms.toInt().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        const val MIN_DELAY_MS = 1

        const val MAX_DELAY_MS = 1_000
    }
}
