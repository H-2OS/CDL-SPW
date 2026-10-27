// SPDX-License-Identifier: GPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Rectangle
import java.awt.image.BufferedImage

object WindowBackdrop {

    private const val PW_RENDERFULLCONTENT = 0x2

    private interface U32 : StdCallLibrary {
        fun WindowFromPoint(p: POINT): HWND?
        fun GetWindowRect(h: HWND, r: RECT): Boolean
        fun PrintWindow(h: HWND, hdc: Pointer, flags: Int): Boolean
        fun GetAncestor(h: HWND, ga: Int): HWND?
        fun GetClassName(h: HWND, buf: CharArray, n: Int): Int

        class RECT : Structure() {
            @JvmField var left = 0
            @JvmField var top = 0
            @JvmField var right = 0
            @JvmField var bottom = 0
            override fun getFieldOrder() = listOf("left", "top", "right", "bottom")
        }

        class POINT : Structure(), Structure.ByValue {
            @JvmField var x = 0
            @JvmField var y = 0
            override fun getFieldOrder() = listOf("x", "y")
        }

        companion object {
            val I: U32 = Native.load("user32", U32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private interface G32 : StdCallLibrary {
        fun CreateCompatibleDC(hdc: Pointer?): Pointer
        fun DeleteDC(hdc: Pointer): Boolean
        fun CreateDIBSection(
            hdc: Pointer?, pbmi: Pointer, usage: Int,
            ppvBits: PointerByReference, hSection: Pointer?, offset: Int,
        ): Pointer
        fun SelectObject(hdc: Pointer, obj: Pointer): Pointer
        fun DeleteObject(obj: Pointer): Boolean

        companion object {
            val I: G32 = Native.load("gdi32", G32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private class Dib(val dc: Pointer, val bmp: Pointer, val bits: Pointer) {
        fun release() {
            runCatching { G32.I.DeleteObject(bmp) }
            runCatching { G32.I.DeleteDC(dc) }
        }
    }

    private fun newDib(w: Int, h: Int): Dib {
        val bmi = Memory(44)
        bmi.clear()
        bmi.setInt(0, 40)
        bmi.setInt(4, w)
        bmi.setInt(8, -h)
        bmi.setShort(12, 1)
        bmi.setShort(14, 32)
        bmi.setInt(16, 0)
        val bitsRef = PointerByReference()
        val dc = G32.I.CreateCompatibleDC(null)
        val bmp = G32.I.CreateDIBSection(dc, bmi, 0, bitsRef, null, 0)
        G32.I.SelectObject(dc, bmp)
        return Dib(dc, bmp, bitsRef.value)
    }

    fun captureBehind(region: Rectangle, selfHwnd: Long, scale: Double = 1.0): BufferedImage? {
        if (region.width <= 0 || region.height <= 0) return null
        val candidates = findCandidates(region, selfHwnd, scale)
        if (candidates.isEmpty()) {
            return null
        }
        for ((hwnd, rect) in candidates) {
            val img = runCatching { printAndCrop(hwnd, rect, region, scale) }.getOrNull()
            if (img != null) {
                return img
            }
        }
        return null
    }

    private fun printAndCrop(
        hwnd: HWND, rect: U32.RECT, region: Rectangle, scale: Double,
    ): BufferedImage? {
        val ww = rect.right - rect.left
        val wh = rect.bottom - rect.top
        if (ww <= 0 || wh <= 0) return null
        val dib = newDib(ww, wh)
        return try {
            if (!U32.I.PrintWindow(hwnd, dib.dc, PW_RENDERFULLCONTENT)) return null
            val raw = ByteArray(ww * wh * 4)
            dib.bits.read(0, raw, 0, raw.size)

            val pw = (region.width * scale).toInt().coerceAtLeast(1)
            val ph = (region.height * scale).toInt().coerceAtLeast(1)
            val rx = (region.x * scale).toInt() - rect.left
            val ry = (region.y * scale).toInt() - rect.top

            val phys = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
            val ppx = IntArray(pw * ph)
            for (y in 0 until ph) {
                val sy = (ry + y).coerceIn(0, wh - 1)
                for (x in 0 until pw) {
                    val sx = (rx + x).coerceIn(0, ww - 1)
                    val i = (sy * ww + sx) * 4
                    val b = raw[i].toInt() and 0xFF
                    val g = raw[i + 1].toInt() and 0xFF
                    val r = raw[i + 2].toInt() and 0xFF
                    ppx[y * pw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            phys.setRGB(0, 0, pw, ph, ppx, 0, pw)

            if (pw == region.width && ph == region.height) {
                phys
            } else {
                val out = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
                val gg = out.createGraphics()
                try {
                    gg.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                    )
                    gg.drawImage(phys, 0, 0, region.width, region.height, null)
                } finally {
                    gg.dispose()
                }
                out
            }
        } catch (_: Throwable) {
            null
        } finally {
            dib.release()
        }
    }

    private fun findCandidates(
        region: Rectangle, selfHwnd: Long, scale: Double,
    ): List<Pair<HWND, U32.RECT>> {
        val cx = (region.x + region.width / 2) * scale
        val cy = (region.y + region.height / 2) * scale
        val needL = (region.x * scale).toInt()
        val needT = (region.y * scale).toInt()
        val needR = ((region.x + region.width) * scale).toInt()
        val needB = ((region.y + region.height) * scale).toInt()

        val selfRect = U32.RECT()
        val selfHwndRef = runCatching { HWND(Pointer.createConstant(selfHwnd)) }.getOrNull()
        val haveSelf = selfHwnd != 0L && selfHwndRef != null && U32.I.GetWindowRect(selfHwndRef, selfRect)
        val baseL = (if (haveSelf) selfRect.left else (region.x * scale).toInt()).toDouble()
        val baseT = (if (haveSelf) selfRect.top else (region.y * scale).toInt()).toDouble()
        val baseR = (if (haveSelf) selfRect.right else ((region.x + region.width) * scale).toInt()).toDouble()
        val baseB = (if (haveSelf) selfRect.bottom else ((region.y + region.height) * scale).toInt()).toDouble()

        val seen = HashSet<Long>()
        val out = ArrayList<Pair<HWND, U32.RECT>>()
        for (i in 0 until 12) {
            val d = 8.0 + i * 18.0
            val pts = listOf(
                (baseL - d) to cy, (baseR + d) to cy, cx to (baseT - d), cx to (baseB + d),
                (baseL - d) to (cy - d), (baseR + d) to (cy - d),
                (baseL - d) to (cy + d), (baseR + d) to (cy + d),
            )
            for ((px, py) in pts) {
                val hwnd = runCatching {
                    U32.I.WindowFromPoint(U32.POINT().apply { x = px.toInt(); y = py.toInt() })
                }.getOrNull() ?: continue
                val hv = Pointer.nativeValue(hwnd.pointer)
                if (hv == 0L || hv == selfHwnd) continue
                if (!seen.add(hv)) continue
                if (isOwnAwtWindow(hwnd)) continue
                val rect = U32.RECT()
                if (!U32.I.GetWindowRect(hwnd, rect)) continue
                if (rect.right <= rect.left || rect.bottom <= rect.top) continue

                val ix = minOf(rect.right, needR) - maxOf(rect.left, needL)
                val iy = minOf(rect.bottom, needB) - maxOf(rect.top, needT)
                val area = (needR - needL).toLong() * (needB - needT).toLong()
                if (ix <= 0 || iy <= 0 || area <= 0L) continue
                if (ix.toLong() * iy.toLong() * 2 < area) continue
                out.add(hwnd to rect)
            }
        }

        val desktopish = out.filter { isDesktopLike(it.first) }
        val real = out.filter { !isDesktopLike(it.first) }
        return (real + desktopish)
    }

    private fun isDesktopLike(hwnd: HWND): Boolean {
        val c = className(hwnd)
        return c == "SysListView32" || c == "Progman" ||
            c == "WorkerW" || c == "LockScreenBackstopFrame" ||
            c.startsWith("Windows.UI.Core.CoreWindow")
    }

    private fun className(hwnd: HWND): String {
        val buf = CharArray(128)
        val n = runCatching { U32.I.GetClassName(hwnd, buf, buf.size) }.getOrDefault(0)
        return if (n > 0) String(buf, 0, n) else "?"
    }

    @Volatile
    private var thumbCache: Triple<Pointer, Long, Long>? = null

    fun captureMirrored(
        region: Rectangle, scale: Double, mirrorHwnd: Long,
    ): BufferedImage? {
        if (mirrorHwnd == 0L || region.width <= 0 || region.height <= 0) return null
        val mirrorRef = runCatching { HWND(com.sun.jna.Pointer.createConstant(mirrorHwnd)) }.getOrNull()
            ?: return null
        val src = findSourceTopLevel(region, scale)
        if (src == null) {
            return null
        }

        val srcVal = Pointer.nativeValue(src.first.pointer)
        val cached = thumbCache
        val thumb = if (cached != null && cached.second == srcVal && cached.third == mirrorHwnd) {
            cached.first
        } else {
            runCatching { dev.cdl.platform.DwmMirror.release(cached?.first) }
            val t = dev.cdl.platform.DwmMirror.register(mirrorRef, src.first)
            if (t == null) {
                return null
            }
            thumbCache = Triple(t, srcVal, mirrorHwnd)
            t
        }
        val pw = (region.width * scale).toInt().coerceAtLeast(1)
        val ph = (region.height * scale).toInt().coerceAtLeast(1)

        val srcRect = com.sun.jna.platform.win32.WinDef.RECT().apply {
            left = src.second.left; top = src.second.top
            right = src.second.right; bottom = src.second.bottom
        }
        if (!dev.cdl.platform.DwmMirror.update(thumb, pw, ph, srcRect)) {
            return null
        }

        val dib = newDib(pw, ph)
        return try {
            if (!U32.I.PrintWindow(mirrorRef, dib.dc, PW_RENDERFULLCONTENT)) {
                return null
            }
            val raw = ByteArray(pw * ph * 4)
            dib.bits.read(0, raw, 0, raw.size)
            val out = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
            val px = IntArray(region.width * region.height)
            for (y in 0 until region.height) {
                val sy = (y * (ph.toDouble() / region.height)).toInt().coerceIn(0, ph - 1)
                for (x in 0 until region.width) {
                    val sx = (x * (pw.toDouble() / region.width)).toInt().coerceIn(0, pw - 1)
                    val i = (sy * pw + sx) * 4
                    val b = raw[i].toInt() and 0xFF
                    val g = raw[i + 1].toInt() and 0xFF
                    val r = raw[i + 2].toInt() and 0xFF
                    px[y * region.width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            out.setRGB(0, 0, region.width, region.height, px, 0, region.width)
            out
        } catch (_: Throwable) {
            null
        } finally {
            dib.release()
        }
    }

    private fun findSourceTopLevel(
        region: Rectangle, scale: Double,
    ): Pair<HWND, U32.RECT>? {
        val cands = findCandidates(region, 0L, scale)
        for ((hwnd, rect) in cands) {
            if (isDesktopLike(hwnd)) continue
            val root = runCatching { U32.I.GetAncestor(hwnd, 2 ) }.getOrNull() ?: continue
            val r2 = U32.RECT()
            if (!U32.I.GetWindowRect(root, r2)) continue
            if (r2.right <= r2.left || r2.bottom <= r2.top) continue
            return root to r2
        }

        return cands.firstOrNull()?.let { (hwnd, rect) -> hwnd to rect }
    }

    private fun isOwnAwtWindow(hwnd: HWND): Boolean {
        val buf = CharArray(128)
        val n = runCatching { U32.I.GetClassName(hwnd, buf, buf.size) }.getOrDefault(0)
        if (n <= 0) return false
        val cls = String(buf, 0, n)
        return cls.startsWith("SunAwt", ignoreCase = true) || cls.startsWith("Awt", ignoreCase = true)
    }
}
