// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

internal object CaptureExclusion {

    const val WDA_EXCLUDEFROMCAPTURE: Int = 0x00000011

    private interface User32Ext : StdCallLibrary {
        fun SetWindowDisplayAffinity(hwnd: HWND, dwAffinity: Int): Boolean

        fun GetWindowDisplayAffinity(hwnd: HWND, out: com.sun.jna.ptr.IntByReference): Boolean

        companion object {

            val INSTANCE: User32Ext? by lazy {
                if (!Platform.isWindows()) {
                    null
                } else {
                    runCatching {
                        Native.load("user32", User32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
                    }.getOrNull()
                }
            }
        }
    }

    @Volatile
    var lastResult: Boolean? = null
        private set

    fun set(hwnd: Long, exclude: Boolean): Boolean {
        if (hwnd == 0L) return false
        val api = User32Ext.INSTANCE ?: return false
        return runCatching {
            api.SetWindowDisplayAffinity(
                com.sun.jna.platform.win32.WinDef.HWND(
                    com.sun.jna.Pointer.createConstant(hwnd),
                ),
                if (exclude) WDA_EXCLUDEFROMCAPTURE else 0,
            )
        }.getOrDefault(false)
    }

    fun query(hwnd: Long, out: IntArray): Boolean {
        val api = User32Ext.INSTANCE ?: return false
        return runCatching {
            val ref = com.sun.jna.ptr.IntByReference(0)
            val ok = api.GetWindowDisplayAffinity(HWND(com.sun.jna.Pointer(hwnd)), ref)
            out[0] = ref.value
            ok
        }.getOrDefault(false)
    }

    fun applyTo(hwnd: Long, log: (String) -> Unit = {  }): Boolean {
        if (hwnd == 0L) {
            lastResult = false
            return false
        }
        val api = User32Ext.INSTANCE
        if (api == null) {
            lastResult = false
            return false
        }
        val ok = runCatching {
            api.SetWindowDisplayAffinity(HWND(com.sun.jna.Pointer(hwnd)), WDA_EXCLUDEFROMCAPTURE)
        }.getOrElse { e ->
            lastResult = false
            return false
        }
        lastResult = ok
        if (!ok) {

        } else {
        }
        return ok
    }
}
