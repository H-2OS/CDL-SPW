// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

internal object WindowInterop {

    private const val GWL_EXSTYLE = -20

    private const val WS_EX_TRANSPARENT = 0x00000020

    private const val WS_EX_LAYERED = 0x00080000

    private const val WS_EX_TOOLWINDOW = 0x00000080

    private const val HWND_TOPMOST: Long = -1L

    private const val HWND_NOTOPMOST: Long = -2L

    private const val SWP_NOSIZE = 0x0001

    private const val SWP_NOMOVE = 0x0002

    private const val SWP_NOACTIVATE = 0x0010

    private interface User32 : StdCallLibrary {

        fun GetWindowLongW(hWnd: WinDef.HWND, nIndex: Int): Int

        fun SetWindowLongW(hWnd: WinDef.HWND, nIndex: Int, dwNewLong: Int): Int

        fun SetWindowPos(
            hWnd: WinDef.HWND,
            hWndInsertAfter: WinDef.HWND,
            X: Int,
            Y: Int,
            cx: Int,
            cy: Int,
            uFlags: Int,
        ): Boolean

        fun FindWindowW(lpClassName: com.sun.jna.WString?, lpWindowName: com.sun.jna.WString?): WinDef.HWND?

        fun GetWindowTextW(hWnd: WinDef.HWND, lpString: CharArray, nMaxCount: Int): Int

        fun GetForegroundWindow(): WinDef.HWND?

        fun MonitorFromWindow(hWnd: WinDef.HWND, dwFlags: Int): Pointer?

        fun GetMonitorInfoW(hMonitor: Pointer, lpmi: MonitorInfo): Boolean

        fun GetSystemMetrics(nIndex: Int): Int

        fun IsWindowVisible(hWnd: WinDef.HWND): Boolean

        fun IsWindow(hWnd: WinDef.HWND): Boolean

        fun GetWindowRect(hWnd: WinDef.HWND, lpRect: WinDef.RECT): Boolean

        fun EnumWindows(lpEnumFunc: EnumWindowsProc, lParam: Pointer?): Boolean

        fun GetWindowThreadProcessId(hWnd: WinDef.HWND, lpdwProcessId: IntByReference): Int
    }

    private interface EnumWindowsProc : StdCallLibrary.StdCallCallback {
        fun callback(hWnd: WinDef.HWND, lParam: Pointer?): Boolean
    }

    private val user32: User32? by lazy {
        runCatching {
            Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.onFailure {
        }.getOrNull()
    }

    val available: Boolean
        get() = user32 != null

    fun setClickThrough(hwnd: Long, enabled: Boolean): Boolean = withHwnd(hwnd) { handle ->
        val current = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)

        val target = if (enabled) {
            current or WS_EX_TRANSPARENT or WS_EX_LAYERED
        } else {
            current and WS_EX_TRANSPARENT.inv()
        }
        user32!!.SetWindowLongW(handle, GWL_EXSTYLE, target)

        val actual = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        val transparentNow = (actual and WS_EX_TRANSPARENT) != 0
        if (transparentNow == enabled) return@withHwnd true

        false
    }

    fun setAlwaysOnTop(hwnd: Long, enabled: Boolean): Boolean = withHwnd(hwnd) { handle ->

        user32!!.SetWindowPos(
            handle,
            WinDef.HWND(Pointer.createConstant(if (enabled) HWND_TOPMOST else HWND_NOTOPMOST)),
            0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE,
        )
    }

    fun setDarkTitleBar(window: java.awt.Window, dark: Boolean): Boolean = runCatching {
        val raw = hwndOf(window) ?: return@runCatching false
        val api = Dwmapi.INSTANCE ?: return@runCatching false
        val handle = WinDef.HWND(Pointer.createConstant(raw))
        val value = com.sun.jna.Memory(4).apply { setInt(0, if (dark) 1 else 0) }
        val newer = runCatching { api.DwmSetWindowAttribute(handle, 20, value, 4) }.getOrDefault(-1)
        val older = runCatching { api.DwmSetWindowAttribute(handle, 19, value, 4) }.getOrDefault(-1)
        newer == 0 || older == 0
    }.getOrDefault(false)

    private interface Dwmapi : StdCallLibrary {

        fun DwmFlush(): Int

        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            attribute: Int,
            value: Pointer,
            size: Int,
        ): Int

        companion object {

            val INSTANCE: Dwmapi? = runCatching {
                Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }.getOrNull() as? Dwmapi
        }
    }

    fun raiseToTop(hwnd: Long): Boolean = withHwnd(hwnd) { handle ->
        val ok = user32!!.SetWindowPos(
            handle,
            WinDef.HWND(Pointer.createConstant(HWND_TOPMOST)),
            0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE,
        )

        if (!ok && !topmostFailureLogged) {
            topmostFailureLogged = true
        }
        ok
    }

    @Volatile
    private var topmostFailureLogged = false

    fun isForegroundFullscreen(excludeHwnd: Long): Boolean {
        val api = user32 ?: return false
        val fg = api.GetForegroundWindow() ?: return false
        val fgValue = Pointer.nativeValue(fg.pointer)
        if (fgValue == excludeHwnd) return false

        val rect = WinDef.RECT()

        if (!api.GetWindowRect(fg, rect)) return false

        val monitor = api.MonitorFromWindow(fg, MONITOR_DEFAULTTONEAREST) ?: return false
        val mi = MonitorInfo()

        mi.cbSize = mi.size()
        if (!api.GetMonitorInfoW(monitor, mi)) return false
        val m = mi.rcMonitor
        if (m.right <= m.left || m.bottom <= m.top) return false
        return isFullscreenRect(
            rectLeft = rect.left,
            rectTop = rect.top,
            rectRight = rect.right,
            rectBottom = rect.bottom,
            monitorLeft = m.left,
            monitorTop = m.top,
            monitorRight = m.right,
            monitorBottom = m.bottom,
        )
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
    class MonitorInfo : Structure() {
        @JvmField
        var cbSize: Int = 0

        @JvmField
        var rcMonitor: WinDef.RECT = WinDef.RECT()

        @JvmField
        var rcWork: WinDef.RECT = WinDef.RECT()

        @JvmField
        var dwFlags: Int = 0
    }

    fun foregroundProcessId(): Int {
        val api = user32 ?: return 0
        val fg = api.GetForegroundWindow() ?: return 0
        val pid = IntByReference(0)
        api.GetWindowThreadProcessId(fg, pid)
        return pid.value
    }

    internal fun physicalBoundsOf(cfg: java.awt.GraphicsConfiguration): java.awt.Rectangle =
        runCatching {
            val b = cfg.bounds
            val t = cfg.defaultTransform
            physicalBounds(b.x, b.y, b.width, b.height, t.scaleX, t.scaleY)
        }.getOrElse { cfg.bounds }

    fun setToolWindow(hwnd: Long, enabled: Boolean): Boolean = withHwnd(hwnd) { handle ->
        val current = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        val target = if (enabled) {
            current or WS_EX_TOOLWINDOW
        } else {
            current and WS_EX_TOOLWINDOW.inv()
        }
        user32!!.SetWindowLongW(handle, GWL_EXSTYLE, target)
        true
    }

    fun hwndOf(window: java.awt.Window): Long? {
        if (!available) return null

        if (!window.isDisplayable) return null
        val pointer = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return null
        if (Pointer.nativeValue(pointer) == 0L) return null
        val handle = WinDef.HWND(pointer)
        val api = user32 ?: return null

        return if (runCatching { api.IsWindow(handle) }.getOrDefault(false)) {
            Pointer.nativeValue(pointer)
        } else {
            null
        }
    }

    fun findHwndByTitle(title: String): Long? {
        if (title.isEmpty()) return null
        val api = user32 ?: return null
        return runCatching {

            val direct = api.FindWindowW(null, com.sun.jna.WString(title))

            direct ?: return@runCatching findByEnumeration(api, title)
            val directHandle = Pointer.nativeValue(direct.pointer)
            if (directHandle != 0L) return@runCatching directHandle

            findByEnumeration(api, title)
        }.onFailure {
        }.getOrNull()
    }

    private fun findByEnumeration(api: User32, title: String): Long? {
        var found: Long = 0L
        val proc = object : EnumWindowsProc {
            override fun callback(hWnd: WinDef.HWND, lParam: Pointer?): Boolean {
                if (found != 0L) return false
                val value = Pointer.nativeValue(hWnd.pointer)
                if (value == 0L) return true

                if (!api.IsWindowVisible(hWnd)) return true
                val buffer = CharArray(512)
                val length = api.GetWindowTextW(hWnd, buffer, buffer.size)
                if (length > 0 && String(buffer, 0, length) == title) {
                    found = value
                    return false
                }
                return true
            }
        }
        api.EnumWindows(proc, null)
        if (found != 0L) return found

        val foreground = api.GetForegroundWindow() ?: return null
        val foregroundValue = Pointer.nativeValue(foreground.pointer)
        if (foregroundValue == 0L) return null
        val buffer = CharArray(512)
        val length = api.GetWindowTextW(foreground, buffer, buffer.size)
        return if (length > 0 && String(buffer, 0, length) == title) foregroundValue else null
    }

    fun isLayered(hwnd: Long): Boolean = withHwnd(hwnd) { handle ->
        (user32!!.GetWindowLongW(handle, GWL_EXSTYLE) and WS_EX_LAYERED) != 0
    }

    fun setLayered(hwnd: Long, on: Boolean): Boolean = withHwnd(hwnd) { handle ->
        val cur = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        val want = if (on) (cur or WS_EX_LAYERED) else (cur and WS_EX_LAYERED.inv())
        if (want != cur) user32!!.SetWindowLongW(handle, GWL_EXSTYLE, want)
        val after = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        ((after and WS_EX_LAYERED) != 0) == on
    }

    fun dwmFlush() {
        runCatching { Dwmapi.INSTANCE?.DwmFlush() }
    }

    fun clearLayered(hwnd: Long): Boolean = withHwnd(hwnd) { handle ->
        val cur = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        if ((cur and WS_EX_LAYERED) == 0) return@withHwnd true
        user32!!.SetWindowLongW(handle, GWL_EXSTYLE, cur and WS_EX_LAYERED.inv())
        val after = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        (after and WS_EX_LAYERED) == 0
    }

    private inline fun withHwnd(hwnd: Long, block: (WinDef.HWND) -> Boolean): Boolean {
        val api = user32 ?: return false
        if (hwnd == 0L) return false
        return runCatching { block(WinDef.HWND(Pointer.createConstant(hwnd))) }
            .onFailure {  }
            .getOrDefault(false)
    }
}

fun physicalBounds(
    logicalX: Int,
    logicalY: Int,
    logicalW: Int,
    logicalH: Int,
    scaleX: Double,
    scaleY: Double,
): java.awt.Rectangle {
    val bad = !scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0.0 || scaleY <= 0.0
    if (bad) return java.awt.Rectangle(logicalX, logicalY, logicalW, logicalH)
    return java.awt.Rectangle(
        Math.round(logicalX * scaleX).toInt(),
        Math.round(logicalY * scaleY).toInt(),
        Math.round(logicalW * scaleX).toInt(),
        Math.round(logicalH * scaleY).toInt(),
    )
}

fun isFullscreenRect(
    rectLeft: Int,
    rectTop: Int,
    rectRight: Int,
    rectBottom: Int,
    monitorLeft: Int,
    monitorTop: Int,
    monitorRight: Int,
    monitorBottom: Int,
): Boolean {

    if (monitorRight <= monitorLeft || monitorBottom <= monitorTop) return false
    return rectLeft <= monitorLeft &&
        rectTop <= monitorTop &&
        rectRight >= monitorRight &&
        rectBottom >= monitorBottom
}

const val MONITOR_DEFAULTTONEAREST = 2

const val SM_CXSCREEN = 0
const val SM_CYSCREEN = 1
