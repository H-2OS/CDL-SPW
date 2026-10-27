// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

fun loopPhase(positionMs: Long, periodMs: Long): Float {
    if (periodMs <= 0L) return 0f
    val m = positionMs % periodMs

    val v = if (m < 0L) m + periodMs else m
    return v.toFloat() / periodMs.toFloat()
}

const val FLOW_SPEED_MIN = -50

const val FLOW_SPEED_MAX = 50

const val FLOW_SPEED_DEFAULT = 15

fun scaledFlowPeriodMs(basePeriodMs: Long, speedPercent: Int): Long {
    if (basePeriodMs <= 0L) return 1L
    val s = speedPercent.coerceIn(FLOW_SPEED_MIN, FLOW_SPEED_MAX)
    if (s == 0) return Long.MAX_VALUE
    return (basePeriodMs * 100L / kotlin.math.abs(s)).coerceAtLeast(1L)
}

fun flowPhase(positionMs: Long, basePeriodMs: Long, speedPercent: Int): Float {
    val period = scaledFlowPeriodMs(basePeriodMs, speedPercent)
    if (period == Long.MAX_VALUE) return 0f
    val p = loopPhase(positionMs, period)
    return if (speedPercent < 0) (1f - p) % 1f else p
}
