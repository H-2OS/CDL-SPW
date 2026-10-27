// SPDX-License-Identifier: GPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

object DwmMirror {

    private interface DwmApi : StdCallLibrary {
        fun DwmRegisterThumbnail(
            hwndDest: HWND, hwndSrc: HWND, phThumbnailId: PointerByReference,
        ): Int

        fun DwmUnregisterThumbnail(thumbnailId: Pointer): Int

        fun DwmUpdateThumbnailProperties(
            thumbnailId: Pointer, ptnProperties: ThumbProps,
        ): Int

        fun DwmFlush(): Int

        companion object {
            val I: DwmApi = Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    class ThumbProps : Structure() {
        @JvmField var dwFlags: Int = 0
        @JvmField var rcDestination: RECT = RECT()
        @JvmField var rcSource: RECT = RECT()
        @JvmField var opacity: Byte = 255.toByte()
        @JvmField var fVisible: Int = 1
        @JvmField var fSourceClientAreaOnly: Int = 0

        override fun getFieldOrder() =
            listOf("dwFlags", "rcDestination", "rcSource", "opacity", "fVisible", "fSourceClientAreaOnly")
    }

    private const val DWM_TNP_RECTDESTINATION = 0x00000001
    private const val DWM_TNP_RECTSOURCE = 0x00000002
    private const val DWM_TNP_OPACITY = 0x00000004
    private const val DWM_TNP_VISIBLE = 0x00000008
    private const val DWM_TNP_SOURCECLIENTAREAONLY = 0x00000010

    fun register(mirrorHwnd: HWND, srcHwnd: HWND): Pointer? {
        val ref = PointerByReference()
        val hr = DwmApi.I.DwmRegisterThumbnail(mirrorHwnd, srcHwnd, ref)
        if (hr != 0) return null
        return ref.value
    }

    fun update(thumb: Pointer, w: Int, h: Int, srcRect: RECT? = null): Boolean {
        val props = ThumbProps()
        props.dwFlags = DWM_TNP_RECTDESTINATION or DWM_TNP_OPACITY or DWM_TNP_VISIBLE or
            (if (srcRect != null) DWM_TNP_RECTSOURCE else 0)
        props.rcDestination.left = 0
        props.rcDestination.top = 0
        props.rcDestination.right = w
        props.rcDestination.bottom = h
        if (srcRect != null) {
            props.rcSource.left = srcRect.left
            props.rcSource.top = srcRect.top
            props.rcSource.right = srcRect.right
            props.rcSource.bottom = srcRect.bottom
        }
        props.opacity = 255.toByte()
        props.fVisible = 1
        return DwmApi.I.DwmUpdateThumbnailProperties(thumb, props) == 0
    }

    fun flush() {
        runCatching { DwmApi.I.DwmFlush() }
    }

    fun release(thumb: Pointer?) {
        if (thumb == null) return
        runCatching { DwmApi.I.DwmUnregisterThumbnail(thumb) }
    }
}
