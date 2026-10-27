// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

public object ClockText {

    public const val HOUR_FORMAT_24: String = "H24"

    public const val HOUR_FORMAT_12: String = "H12"

    private val formatters = java.util.concurrent.ConcurrentHashMap<String, DateTimeFormatter>()

    private fun formatterFor(zone: ZoneId, hourFormat: String): DateTimeFormatter {
        val pattern = if (hourFormat == HOUR_FORMAT_12) "h:mm" else "HH:mm"
        return formatters.computeIfAbsent("${zone.id}|$pattern") {
            DateTimeFormatter.ofPattern(pattern).withZone(zone)
        }
    }

    public fun format(epochMs: Long, zone: ZoneId, hourFormat: String = HOUR_FORMAT_24): String =
        formatterFor(zone, hourFormat).format(Instant.ofEpochMilli(epochMs))

    public fun isPm(epochMs: Long, zone: ZoneId): Boolean {
        val hour = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).hour
        return hour >= 12
    }

    public fun secondOf(epochMs: Long): Long {

        return Math.floorDiv(epochMs, 1000L)
    }

    public fun colonVisible(epochMs: Long): Boolean = repaintTickOf(epochMs) % 2L == 0L

    public fun repaintTickOf(epochMs: Long): Long {

        return Math.floorDiv(epochMs, 500L)
    }

    public fun shouldRepaint(prevTick: Long, nowTick: Long): Boolean = prevTick != nowTick
}
