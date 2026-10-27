// SPDX-License-Identifier: GPL-3.0-only
package dev.cdl.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal object UIAA {

    private const val GWL_EXSTYLE = -20
    private const val WS_EX_LAYERED = 0x00080000
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_FRAMECHANGED = 0x0020

    private const val DWMWA_REDIRECTIONBITMAP_ALPHA = 39

    private interface User32 : Library {
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun SetWindowLongW(hwnd: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(
            hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int,
        ): Boolean
    }

    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntArray, size: Int): Int
    }

    private val user32: User32? = runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    private val dwm: DwmApi? = runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()

    fun enable(hwnd: Long): Boolean {
        if (hwnd == 0L) return false
        val p = Pointer(hwnd)
        val u = user32 ?: return false
        val d = dwm ?: return false
        val okLayered = runCatching {
            val st = u.GetWindowLongW(p, GWL_EXSTYLE)
            if (st and WS_EX_LAYERED != 0) {
                u.SetWindowLongW(p, GWL_EXSTYLE, st and WS_EX_LAYERED.inv())
            }
            u.SetWindowPos(
                p, null, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED,
            )
            true
        }.getOrDefault(false)

        val hr = runCatching {
            d.DwmSetWindowAttribute(p, DWMWA_REDIRECTIONBITMAP_ALPHA, intArrayOf(1), 4)
        }.getOrDefault(-1)
        return okLayered && hr == 0
    }
}
