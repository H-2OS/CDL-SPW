// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * ★★★ **把词岛窗口排除出截屏**（`WDA_EXCLUDEFROMCAPTURE`）—— **L3（真透视）的前提条件**。
 *
 * ## ★★★ 为什么需要它（★ 这是"液态玻璃看起来假"的**真根因**之一）
 *
 * ★ L3 的做法是：**截下词岛身后的屏幕**，模糊后垫在词岛最底层，再按折射公式弯。
 * ★ 问题在于 —— ★ **词岛自己就画在屏幕上那个位置** ⇒ 直接截，截到的**就是词岛自己** ✗
 *
 * ★★ 正解：让**词岛窗口对截屏不可见**（`SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`）
 *   ⇒ ★ 截到的才是"**没有词岛时的桌面**" ✓
 *
 * ## ★★★ 缺了它会怎样（★ 真机实测的完整链条）
 *
 * 1. ★ `SelfCaptureDetect` 拿"截到的帧"与"自身帧"比 ⇒ ★ 判据是
 *    「**截屏里若含词岛自己 ⇒ 相关系数 s1 接近 1**」✓
 * 2. ★★ 而本标志**没设** ⇒ 截屏里**必然含词岛** ⇒ ★ **`s1 ≈ 0.995`**（实测）
 * 3. ⇒ ★ 护栏判"自捕获" ⇒ ★★ **退纯色玻璃并停用截屏** ⇒
 *    ★★★ **折射层永远不显示**（`交付=0`）✗
 *
 * ★ 真机日志取证（2026-10-10）：
 * ```
 * [L3] ★ 真透视暂不可用（疑似自捕获，连续 3 帧） s1=0.995 s1hp=0.974
 * [L3] ★★ 退纯色玻璃并停用截屏（连续 10 帧；s1=0.411 s1hp=0.853 reason=hit）
 * ```
 *
 * ## ⚠️ 本类补的是一处**"文档写了、代码没做"**
 *
 * ★ `BackdropCapture` / `SelfCaptureDetect` / `IslandWindow` 里**共 4 处** KDoc 都
 *   **假定**这个标志已经设上（逐字：「`WDA_EXCLUDEFROMCAPTURE` 让词岛…」）——
 * ★★ 而全仓 `grep SetWindowDisplayAffinity` ⇒ ★ **命中全是注释，生产代码里一次都没调过** ✗
 * ★ 台账 **C8** 其实记过：「**两侧都要新建**：① 截屏侧（`Robot` + `WDA_EXCLUDEFROMCAPTURE`）
 *   ② `ownLastFrame` 取帧侧」⇒ ★ **计划过、没做** ✓
 *
 * ## ⚠️ 另一处**未经实测的断言**（本类同时把它标出来）
 *
 * ★ `IslandWindow` 的 KDoc 写着：「**`Robot` 抓自己的窗口【不受】`WDA_EXCLUDEFROMCAPTURE` 限制**」——
 * ★★ 但那个标志**从来没被设过** ⇒ ★ **那条断言从未被验证** ✓
 * ★ 本类落地后，**日志里的 `s1` 就是它的判据**：
 *   ★ `s1` 掉下来 ⇒ 标志对**同进程**截屏也生效 ✓
 *   ★ `s1` 仍是 0.995 ⇒ 那条断言成立 ⇒ ★ **改走"面板帧导出"**（台账 C8 的第 ② 侧）✓
 *
 * ## 非 Windows / 调用失败时
 *
 * ★ **静默降级但留痕**：★ 返回 `false` 并打一行 stderr（★ 不打 stderr 就等于"改了没反应"，
 *   本项目已栽过多次）✓  ★ 调用方**不需要**据此改变行为 —— 拿不到就还是老样子 ✓
 */
internal object CaptureExclusion {

    /**
     * `WDA_EXCLUDEFROMCAPTURE` —— ★ **Windows 10 版本 2004 起支持**。
     *
     * ★ 逐字出处：MSDN `SetWindowDisplayAffinity` 的 `dwAffinity` 取值表 ✓
     */
    const val WDA_EXCLUDEFROMCAPTURE: Int = 0x00000011

    /**
     * `user32` 的最小扩展面。
     *
     * ★ **为什么自己声明**：★ JNA 的 `com.sun.jna.platform.win32.User32`
     *   **没有** `SetWindowDisplayAffinity`（已核 `javap` 5.17.0）✗
     * ★ 只声明本类用得到的这一个函数 —— ★ 不复制整个 `User32`（那会造第二份实现）✓
     */
    private interface User32Ext : StdCallLibrary {
        fun SetWindowDisplayAffinity(hwnd: HWND, dwAffinity: Int): Boolean

        companion object {
            /** ★ `Native.load` 在非 Windows 上会抛 ⇒ 用 `by lazy` + `runCatching` 兜住。 */
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

    /** ★ 本进程内**是否已经成功设过一次**（供诊断；★ 幂等，重复设无害）。 */
    @Volatile
    var lastResult: Boolean? = null
        private set

    /**
     * 把 [hwnd] 指向的窗口**排除出截屏**。
     *
     * ★ **必须传 `long` 句柄**（不是 `Pointer`）—— ★ 与 `IslandWindow` 里 `hwnd` 的口径一致，
     *   避免调用方多一次转换（★ 转换点越少，越不容易在某一处漏）✓
     *
     * @param hwnd Win32 窗口句柄（`0` ⇒ 直接返回 `false`）
     * @param log 日志落点（★ 默认 stderr —— 与 `IslandWindow` 的既有做法一致）
     * @return `true` ⇒ 调用成功（★ 语义 = "标志已设上"，**不等于**"截屏一定排除了它"）
     */
    fun applyTo(hwnd: Long, log: (String) -> Unit = { System.err.println(it) }): Boolean {
        if (hwnd == 0L) {
            lastResult = false
            log("[CDL] 截屏排除：HWND 为 0 ⇒ 跳过（折射将退回纯色）")
            return false
        }
        val api = User32Ext.INSTANCE
        if (api == null) {
            lastResult = false
            log("[CDL] 截屏排除：非 Windows 或 user32 不可用 ⇒ 跳过（折射将退回纯色）")
            return false
        }
        val ok = runCatching {
            api.SetWindowDisplayAffinity(HWND(com.sun.jna.Pointer(hwnd)), WDA_EXCLUDEFROMCAPTURE)
        }.getOrElse { e ->
            lastResult = false
            log("[CDL] 截屏排除：调用异常 ${e::class.simpleName}: ${e.message}（折射将退回纯色）")
            return false
        }
        lastResult = ok
        if (!ok) {
            // ★ 失败时**必须留痕**：否则表现为"折射永远出不来且没有任何线索"（本次就是）
            log(
                "[CDL] 截屏排除：SetWindowDisplayAffinity 返回 false " +
                    "（★ 需 Windows 10 2004+；折射将退回纯色）",
            )
        } else {
            log("[CDL] 截屏排除：已设 WDA_EXCLUDEFROMCAPTURE（0x11）⇒ 折射可用 ✓")
        }
        return ok
    }
}
