// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Library
import com.sun.jna.Native

object TimerResolution {

    interface Backend {
        fun timeBeginPeriod(uPeriod: Int): Int
        fun timeEndPeriod(uPeriod: Int): Int
    }

    private interface Winmm : Library, Backend

    private const val PERIOD_MS = 1

    @Volatile
    private var raised = false

    private val winmm: Backend? by lazy {

        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        if (!isWindows) {
            null
        } else {
            runCatching { Native.load("winmm", Winmm::class.java) as Backend }.getOrNull()
        }
    }

    private fun backend(): Backend? = winmm


    fun raise() {
        if (raised) return
        val lib = backend() ?: return
        runCatching {
            val rc = lib.timeBeginPeriod(PERIOD_MS)
            if (rc == 0) {
                raised = true
            } else {
            }
        }
    }

    fun restore() {
        if (!raised) return
        val lib = backend()

        if (lib == null) {
            raised = false
            return
        }
        runCatching {
            lib.timeEndPeriod(PERIOD_MS)
            raised = false
        }
    }

    fun isRaised(): Boolean = raised
}
