// SPDX-License-Identifier: AGPL-3.0-only
// Ported from 0.8.9 mod (H-2OS / 自宅警備員, commits 7bc0183/8c02841/3a347d5); see NOTICE.
package dev.cdl.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * 读取 Windows 的「应用深浅色」设置，让自绘菜单能跟随系统主题。
 *
 * ## 为什么需要它（而不是直接用 Swing 的 L&F 默认色）
 *
 * 菜单是**自绘**的（见 `window/IslandMenuSkin.kt`），配色由我们自己的调色板决定，
 * 因此没有「L&F 帮我跟着系统走」这回事 —— 深浅两套色**必须自己选一套**。
 * 判断依据只有一个可靠来源：注册表
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`。
 *
 * ## 为什么默认是**深色**（读不到就返回 false）
 *
 * 词岛是常驻深色悬浮件（默认外观就是深色系），菜单若在**读不到主题**时回退到浅色，
 * 会出现"词岛深、菜单白"的割裂感。故读不到一律按深色处理 —— 这与原实现
 * `089:platform/SystemTheme.kt:14` 的取值方向一致。
 *
 * ## 为什么保留 `-Dcdl.menuTheme` 覆盖
 *
 * 原实现（`089:platform/SystemTheme.kt:17-20`）留了 `-Dspwisland.menuTheme=light|dark`。
 * 本插件改名 `-Dcdl.menuTheme`（前缀换成我们的包名，避免与原实现属性撞车）。
 * **它是浅色调色板唯一的免重启测试手段** —— 否则要验证浅色菜单就得改系统主题
 * （会连带影响整个桌面），代价过高。规格 `recon/08` 的移植清单 M5 明确要求保留。
 */
internal object SystemTheme {

    /** 主题设置所在的注册表路径。 */
    private const val PERSONALIZE =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    /** 覆盖用的系统属性名。取值 `light` / `dark`（大小写不敏感），其余值忽略。 */
    private const val OVERRIDE_PROPERTY = "cdl.menuTheme"

    /**
     * 当前是否为浅色模式。
     *
     * 顺序：**先看覆盖属性，再读注册表**。
     * 覆盖放前面是刻意的 —— 测试时希望它**无条件生效**，
     * 若放在注册表之后就成了"只在注册表读不到时才生效"，那这条测试通道就废了。
     *
     * 注册表读取用 `runCatching` 包住：非 Windows 平台会抛 `UnsatisfiedLinkError`
     * （`Error` 而非 `Exception`，`catch (e: Exception)` 抓不到），
     * 注册表项缺失则抛 `Win32Exception`。两者都应降级为深色，绝不让异常外逸 ——
     * 菜单配色判断失败不该让右键菜单弹不出来。
     */
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
