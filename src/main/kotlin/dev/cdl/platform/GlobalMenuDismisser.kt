// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.HHOOK
import com.sun.jna.platform.win32.WinUser.LowLevelMouseProc
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.platform.win32.WinUser.MSLLHOOKSTRUCT
import java.awt.Container
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

internal class GlobalMenuDismisser(private val onOutsidePress: () -> Unit) {

    private class HookSession {
        lateinit var callback: LowLevelMouseProc

        @Volatile
        var hook: HHOOK? = null

        @Volatile
        var threadId: Int = 0
    }

    private val user32 = User32.INSTANCE
    private val kernel32 = Kernel32.INSTANCE

    @Volatile
    private var activeSession: HookSession? = null

    fun arm(window: Window? = null): Boolean {
        disarm()

        val menuRect: RECT? = runCatching {
            val w = window ?: return@runCatching null
            val handle = Native.getWindowPointer(w) ?: return@runCatching null
            if (Pointer.nativeValue(handle) == 0L) return@runCatching null
            val rect = RECT()
            if (user32.GetWindowRect(HWND(handle), rect)) rect else null
        }.getOrNull()

        val session = HookSession()
        session.callback = object : LowLevelMouseProc {
            override fun callback(nCode: Int, wParam: WPARAM, info: MSLLHOOKSTRUCT): LRESULT {

                if (nCode >= 0 && activeSession === session && wParam.toInt() in PRESS_MESSAGES) {
                    val px = info.pt.x
                    val py = info.pt.y

                    val insideMenuRect = menuRect != null &&
                        px >= menuRect.left && px < menuRect.right &&
                        py >= menuRect.top && py < menuRect.bottom
                    if (!insideMenuRect) {
                        SwingUtilities.invokeLater {

                            if (activeSession === session && !insideAnyPopup(px, py)) {
                                onOutsidePress()
                            }
                        }
                    }
                }

                return user32.CallNextHookEx(
                    null, nCode, wParam, LPARAM(Pointer.nativeValue(info.pointer)),
                )
            }
        }
        activeSession = session

        val ready = CountDownLatch(1)
        val installed = AtomicInteger(0)
        Thread({
            val message = MSG()

            user32.PeekMessage(message, null, 0, 0, PM_NOREMOVE)
            session.threadId = kernel32.GetCurrentThreadId()

            val hook = user32.SetWindowsHookEx(
                WH_MOUSE_LL, session.callback, kernel32.GetModuleHandle(null), 0,
            )
            if (hook == null) {
                installed.set(0)
                ready.countDown()
                return@Thread
            }
            session.hook = hook

            if (activeSession !== session) {
                user32.UnhookWindowsHookEx(hook)
                session.hook = null
                installed.set(0)
                ready.countDown()
                return@Thread
            }
            installed.set(1)
            ready.countDown()
            try {
                while (true) {
                    val result = user32.GetMessage(message, null, 0, 0)
                    if (result <= 0) break
                    user32.TranslateMessage(message)
                    user32.DispatchMessage(message)
                }
            } finally {

                session.hook?.let(user32::UnhookWindowsHookEx)
                session.hook = null
            }
        }, "cdl-menu-hook").apply { isDaemon = true }.start()

        val ok = try {
            ready.await(2, TimeUnit.SECONDS) && installed.get() == 1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ok) disarm()
        return ok
    }

    fun disarm() {
        val session = activeSession ?: return
        activeSession = null
        session.hook?.let(user32::UnhookWindowsHookEx)
        session.hook = null
        val threadId = session.threadId

        if (threadId != 0) user32.PostThreadMessage(threadId, WM_QUIT, WPARAM(0), LPARAM(0))
    }

    private fun insideAnyPopup(x: Int, y: Int): Boolean {
        for (window in Window.getWindows()) {
            if (!window.isShowing) continue
            val pointer = Native.getWindowPointer(window) ?: continue
            val rect = RECT()
            if (!user32.GetWindowRect(HWND(pointer), rect)) continue
            if (x < rect.left || x >= rect.right || y < rect.top || y >= rect.bottom) continue
            if (containsPopup(window)) return true
        }
        return false
    }

    private fun containsPopup(container: Container): Boolean {
        if (container is JPopupMenu) return true
        for (child in container.components) {
            if (child is Container && containsPopup(child)) return true
        }
        return false
    }

    private companion object {

        const val WH_MOUSE_LL = 14

        const val WM_LBUTTONDOWN = 0x0201
        const val WM_RBUTTONDOWN = 0x0204
        const val WM_MBUTTONDOWN = 0x0207

        const val WM_QUIT = 0x0012

        const val PM_NOREMOVE = 0

        val PRESS_MESSAGES = setOf(WM_LBUTTONDOWN, WM_RBUTTONDOWN, WM_MBUTTONDOWN)
    }
}
