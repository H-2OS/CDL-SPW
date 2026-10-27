// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

internal object SystemTheme {

    private const val PERSONALIZE =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    private const val OVERRIDE_PROPERTY = "cdl.menuTheme"

    fun isLight(): Boolean {
        when (System.getProperty(OVERRIDE_PROPERTY)?.lowercase()) {
            "light" -> return true
            "dark" -> return false
        }
        return runCatching {
            Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER,
                PERSONALIZE,
                "AppsUseLightTheme",
            ) != 0
        }.getOrDefault(false)
    }
}
