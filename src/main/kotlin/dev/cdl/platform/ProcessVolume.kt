// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

object ProcessVolume {

    @Volatile
    private var comReady = false

    private interface Ole32Lib : Library {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): WinNT.HRESULT

        fun CoCreateInstance(
            rclsid: Guid.GUID?,
            outer: Pointer?,
            clsctx: Int,
            riid: Guid.GUID?,
            out: PointerByReference?,
        ): WinNT.HRESULT
    }

    private val ole32: Ole32Lib? by lazy {
        if (!Platform.isWindows()) {
            null
        } else {
            runCatching { Native.load("ole32", Ole32Lib::class.java) }.getOrNull()
        }
    }

    private val CLSID_MMDeviceEnumerator =
        Guid.IID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    private val IID_IMMDeviceEnumerator =
        Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    private val IID_IAudioSessionManager2 =
        Guid.IID("{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}")
    private val IID_IAudioSessionControl2 =
        Guid.IID("{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}")
    private val IID_ISimpleAudioVolume =
        Guid.IID("{87CE5498-68D6-44E5-9215-6DA47EF883D8}")

    private const val CLSCTX_ALL = 0x17
    private const val COINIT_APARTMENTTHREADED = 2

    private fun call(self: Pointer?, slot: Int, vararg args: Any?): Int {
        if (self == null) return -1
        val fnPtr = self.getPointer(0L)?.getPointer(8L * slot) ?: return -1
        val values = arrayOfNulls<Any>(args.size + 1)
        values[0] = self
        System.arraycopy(args, 0, values, 1, args.size)
        return Function.getFunction(fnPtr).invokeInt(values)
    }

    private fun ensureCom(): Boolean {
        if (comReady) return true
        val lib = ole32 ?: return false

        val hr = runCatching {
            lib.CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED).toInt()
        }.getOrNull() ?: return false
        val ok = hr == 0 || hr == 1 || hr == 0x80010106.toInt()
        comReady = ok
        return ok
    }

    private fun findVolumeSession(pid: Int): Pointer? {
        if (!ensureCom()) return null
        val lib = ole32 ?: return null

        val enumeratorRef = PointerByReference()
        val created = runCatching {
            lib.CoCreateInstance(
                CLSID_MMDeviceEnumerator, Pointer.NULL, CLSCTX_ALL,
                IID_IMMDeviceEnumerator, enumeratorRef,
            ).toInt()
        }.getOrNull() ?: return null
        if (created != 0) return null
        val enumerator = enumeratorRef.value ?: return null

        val deviceRef = PointerByReference()
        if (call(enumerator, 4, 0, 0, deviceRef.pointer) != 0) return null
        val device = deviceRef.value ?: return null

        val managerRef = PointerByReference()
        if (call(
                device, 3, IID_IAudioSessionManager2.pointer, CLSCTX_ALL,
                Pointer.NULL, managerRef.pointer,
            ) != 0
        ) {
            return null
        }
        val manager = managerRef.value ?: return null

        val sessionsRef = PointerByReference()
        if (call(manager, 5, sessionsRef.pointer) != 0) return null
        val sessions = sessionsRef.value ?: return null

        val count = IntByReference()
        if (call(sessions, 3, count.pointer) != 0) return null

        for (index in 0 until count.value) {

            val controlRef = PointerByReference()
            if (call(sessions, 4, index, controlRef.pointer) != 0) continue
            val control = controlRef.value ?: continue

            val control2Ref = PointerByReference()
            if (call(control, 0, IID_IAudioSessionControl2.pointer, control2Ref.pointer) != 0) {
                continue
            }
            val control2 = control2Ref.value ?: continue

            val sessionPid = IntByReference()
            if (call(control2, 14, sessionPid.pointer) != 0) continue
            if (sessionPid.value != pid) continue

            val volumeRef = PointerByReference()
            if (call(control2, 0, IID_ISimpleAudioVolume.pointer, volumeRef.pointer) != 0) {
                continue
            }
            val volume = volumeRef.value ?: continue
            return volume
        }
        return null
    }

    fun readOf(pid: Int): Float? {
        val volume = findVolumeSession(pid) ?: return null
        val level = FloatByReference()

        if (call(volume, 4, level.pointer) != 0) return null
        val v = level.value
        if (v.isNaN() || !v.isFinite()) return null
        return v.coerceIn(0f, 1f)
    }

    fun writeOf(pid: Int, level: Float): Boolean {
        if (!level.isFinite()) return false
        val volume = findVolumeSession(pid) ?: return false

        val hr = call(volume, 3, level.coerceIn(0f, 1f), Pointer.NULL)
        return hr == 0
    }

    fun currentProcessId(): Int = runCatching {
        ProcessHandle.current().pid().toInt()
    }.getOrNull() ?: -1

    fun isSupported(): Boolean = ole32 != null

}
