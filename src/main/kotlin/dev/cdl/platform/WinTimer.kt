// SPDX-License-Identifier: GPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Library
import com.sun.jna.Native

internal object WinTimer {

    private interface Winmm : Library {
        fun timeBeginPeriod(period: Int): Int
        fun timeEndPeriod(period: Int): Int
    }

    private val winmm: Winmm? = runCatching { Native.load("winmm", Winmm::class.java) }.getOrNull()

    fun beginPeriod(ms: Int): Boolean =
        runCatching { winmm?.timeBeginPeriod(ms) == 0 }.getOrDefault(false)

    fun endPeriod(ms: Int): Boolean =
        runCatching { winmm?.timeEndPeriod(ms) == 0 }.getOrDefault(false)
}
