// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import dev.cdl.animation.Motions
import dev.cdl.animation.Transition
import dev.cdl.animation.transitionOf
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Container
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.Timer

private const val GWL_EXSTYLE = -20

private const val WS_EX_TRANSPARENT = 0x00000020

private const val WS_EX_NOACTIVATE = 0x08000000

const val FADE_OUT_MIN_START_ALPHA: Float = 0.01f

fun menuFadeOutShouldRun(
    fadeEnabled: Boolean,
    alreadyInFlight: Boolean,
    showing: Boolean,
    startOpacity: Float,
): Boolean = fadeEnabled && !alreadyInFlight && showing && startOpacity > FADE_OUT_MIN_START_ALPHA

private class Shot(

    val menu: JPopupMenu,

    val window: JWindow,

    val timer: Timer,
)

internal object MenuFadeOut {

    private val inFlight = HashMap<JPopupMenu, Shot>()

    private var degradedLogged = false

    private val pressCut = AWTEventListener { event ->
        val press = event as? MouseEvent ?: return@AWTEventListener
        if (press.id == MouseEvent.MOUSE_PRESSED) finishAll()
    }

    private var pressCutInstalled = false

    fun onMenuHiding(menu: JPopupMenu, fadeEnabled: () -> Boolean) {
        val window = SwingUtilities.getWindowAncestor(menu) ?: return
        val startOpacity = runCatching { window.opacity }.getOrDefault(1f)
        if (!menuFadeOutShouldRun(fadeEnabled(), inFlight.containsKey(menu), menu.isShowing, startOpacity)) return
        val content = (window as? RootPaneContainer)?.contentPane ?: return
        val image = capture(content) ?: return
        val shot = showShot(menu, content, image, window.bounds, startOpacity) ?: return
        inFlight[menu] = shot
        installPressCut()
    }

    private fun capture(view: Container): BufferedImage? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        return try {
            view.print(g2)
            image
        } catch (_: Throwable) {
            null
        } finally {
            g2.dispose()
        }
    }

    private fun showShot(
        menu: JPopupMenu,
        content: Container,
        image: BufferedImage,
        bounds: Rectangle,
        startOpacity: Float,
    ): Shot? {
        val window = JWindow()

        window.background = Color(0, 0, 0, 0)
        window.contentPane.background = Color(0, 0, 0, 0)
        (window.contentPane as? JComponent)?.isOpaque = false
        window.contentPane.add(JLabel(ImageIcon(image)).apply { isOpaque = false })
        window.bounds = bounds
        window.isAlwaysOnTop = true

        window.focusableWindowState = false

        makeClickThrough(window)
        window.isVisible = true
        if (runCatching { window.opacity = startOpacity }.isFailure) {
            runCatching { window.dispose() }
            logDegradedOnce()
            return null
        }
        val spec = Motions.MENU_FADE
        val transition = transitionOf(
            from = startOpacity,
            to = 0f,
            durationMs = spec.durationMs,
            easing = spec.easing,
            onUpdate = { v -> runCatching { window.opacity = v } },
        ) ?: run {

            runCatching { window.dispose() }
            return null
        }
        val timer = Timer(IslandMenuSkin.MENU_FADE_TICK_MS) { _ ->

            if (transition.advance(System.nanoTime())) finish(menu)
        }
        timer.isRepeats = true
        timer.start()
        return Shot(menu, window, timer)
    }

    private fun makeClickThrough(window: Window) {
        runCatching {
            val handle = Native.getWindowPointer(window) ?: return
            val hwnd = HWND(handle)
            val user32 = User32.INSTANCE
            val current = user32.GetWindowLong(hwnd, GWL_EXSTYLE)
            if (current == 0) return
            user32.SetWindowLong(hwnd, GWL_EXSTYLE, current or WS_EX_TRANSPARENT or WS_EX_NOACTIVATE)
        }
    }

    private fun finish(menu: JPopupMenu) {
        val shot = inFlight.remove(menu) ?: return
        disposeShot(shot)
        if (inFlight.isEmpty()) removePressCut()
    }

    internal fun finishAll() {
        if (inFlight.isEmpty()) return
        val shots = ArrayList(inFlight.values)
        inFlight.clear()
        shots.forEach(::disposeShot)
        removePressCut()
    }

    private fun disposeShot(shot: Shot) {
        runCatching { shot.timer.stop() }
        runCatching { shot.window.dispose() }
    }

    private fun installPressCut() {
        if (pressCutInstalled) return
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(pressCut, AWTEvent.MOUSE_EVENT_MASK)
        }
        pressCutInstalled = true
    }

    private fun removePressCut() {
        if (!pressCutInstalled) return
        runCatching {
            Toolkit.getDefaultToolkit().removeAWTEventListener(pressCut)
        }
        pressCutInstalled = false
    }

    private fun logDegradedOnce() {
        if (degradedLogged) return
        degradedLogged = true
    }
}
