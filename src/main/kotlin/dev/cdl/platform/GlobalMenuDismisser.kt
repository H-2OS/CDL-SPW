// SPDX-License-Identifier: AGPL-3.0-only
// Ported from 0.8.9 mod (H-2OS / 自宅警備員, commits 7bc0183/8c02841/3a347d5); see NOTICE.
//
// 改编自原仓库的同名类
//（原实现 0.8.9：`platform/GlobalMenuDismisser.kt`，GPL-3.0-only）。
// ⚠️ 作者勘误（2026-09-19，git 取证）：该文件为 **H-2OS（自宅警備員）** 所写 ——
// PR#11 创建版 `fc13744`、`insideAnyPopup` 增强 `3a347d5`；版权归 H-2OS。
// 与原实现相同：`WH_MOUSE_LL`(14) 低级鼠标钩子、只对 L/R/M **按下**反应、
// 独立消息泵线程（先 `PeekMessage(PM_NOREMOVE)` 建队列再发布 threadId）、
// 回调里无条件 `CallNextHookEx`（不吞点击）、`GetWindowRect` 取物理像素矩形做命中判定、
// 遍历全部可见窗口以照顾 `JMenu` 子菜单（独立顶层窗口）。
// 本地改动点：
//   (1) `arm(window: Window)` 取菜单窗口的 `GetWindowRect`，在**钩子回调里**先做
//       常数时间剔除（原实现 `:41-42` + `:47-50` 同法）；原实现无参数、每次都投递 EDT。
//   (2) `disarm()` 的 `PostThreadMessage(WM_QUIT)` 顺序与「装好之前已被 disarm」的自清理
//       逻辑（原实现靠 `activeSession` 身份比较，本实现的 `HookSession` 同此思路）。
//   (3) 全部 KDoc 与注释为本项目重写；错误信息为中文。
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

/**
 * 「点菜单外部即关闭」—— 用 `WH_MOUSE_LL` 低层鼠标钩子补上 Swing 在**非激活窗口**上
 * 拿不到的那次点击。
 *
 * ## 为什么 Swing 自己的机制在这里一定失效（这是本类存在的全部理由）
 *
 * Swing 的 popup 关闭链路（JDK 21 源码 `javax/swing/plaf/basic/BasicPopupMenuUI.java`
 * 的 `MouseGrabber` + `sun/awt/windows/WToolkit.java:1141` 的 `grab`）是：
 *
 * ```
 * MenuSelectionManager.selectedPath 由空变非空
 *   -> MouseGrabber.stateChanged -> grabWindow(path)
 *        invoker = path[0].component ; 若为 JPopupMenu 则取其 invoker
 *        grabbedWindow = if (invoker == null) null else windowAncestor(invoker)
 *        if (grabbedWindow != null) WToolkit.grab(it) -> WWindowPeer.nativeGrab()
 *                                     -> Win32 SetCapture(invokerHwnd)
 *   -> MouseGrabber.eventDispatched(MOUSE_PRESSED 且 src 不在 popup 内)
 *        -> cancelPopupMenu()
 * ```
 *
 * 这条链在我们的两种入口上**各断一处**（均已在 `recon/menu-lab/DismissLab.java` 实测）：
 *
 * 1. **托盘路径**（`JPopupMenu.show(null, x, y)`）：invoker 为 `null` →
 *    `grabbedWindow` 也是 `null` → **`SetCapture` 根本不会被调用**。
 *    实测：该路径下 `MenuSelectionManager` 的选中路径长度为 **0**，
 *    `GetCapture()` 为 `null`，Robot 在别的进程窗口上真点一下，菜单**不关**。
 * 2. **词岛路径**（invoker 是词岛内的组件）：`SetCapture` 会调，但调在词岛的 HWND 上，
 *    而词岛是 `WS_EX_NOACTIVATE`（实测 `EXSTYLE=0x08080008`，含 NOACTIVATE）
 *    的悬浮件，**永远不是前台窗口**。MSDN `SetCapture` 的 Remarks 原文：
 *    *"Only the foreground window can capture the mouse. When a background window
 *    attempts to do so, the window receives messages only for mouse events that occur
 *    when the cursor hot spot is within the visible portion of the window."*
 *    → 光标一离开词岛可见区，鼠标消息就照常发给光标下的那个窗口（宿主窗口 / 桌面，
 *    都不是 AWT 窗口）→ **AWT 连事件都不会产生**，`MouseGrabber` 永不触发。
 *    实测：该路径下 `GetCapture()` 同样是 `null`（捕获没装上）。
 *
 * 原实现（`089:platform/GlobalMenuDismisser.kt`）正是为此自建了本钩子 ——
 * 而且这个机制**在官方 0.6.0 就已存在**（`extract/ref060/060-src/.../GlobalMenuDismisser.kt:67`
 * 已有 `SetWindowsHookEx(WH_MOUSE_LL, ...)`），不是本地作者的独创。
 *
 * ## 三条硬约束（缺一条就会变成"修好一个 bug 换来两个"）
 *
 * 1. **绝不吞掉这次点击。** 回调**无条件** `CallNextHookEx` —— 不返回 `1`、不阻断。
 *    理由：用户点的是菜单**外面**，那一下点击是**给下面那个窗口的**；
 *    我们的菜单只是"顺带该关掉"。吞掉它会变成"第一次点击只用来关菜单、
 *    要点第二下才生效"，那是比"菜单不关"更让人恼火的体验。
 *    （对照：AWT 的 `SetCapture` 路线是**会**把这次点击改道到我们窗口的 ——
 *    它若真能生效，反而会吞点击。钩子方案在这点上严格更优。）
 * 2. **回调里不做重活。** `WH_MOUSE_LL` 的回调跑在**系统输入线程**上，
 *    它阻塞多久，整个桌面的鼠标就卡多久。故回调里只做两件事：
 *    判 `nCode` 与消息类型、`SwingUtilities.invokeLater` 投递到 EDT；
 *    窗口遍历（`Window.getWindows()`）与命中判定全部放到 EDT 上做。
 * 3. **菜单关闭后必须及时卸载。** 钩子是**全局资源**，装了不摘会让每一次鼠标按下
 *    都绕经本进程 —— 插件卸载后仍在跑就是实打实的系统级泄漏。
 *    故 `disarm()` 与 `arm()` 成对，由 [dev.cdl.window.IslandPopupMenu]
 *    挂在 `PopupMenuListener` 上（可见时装、不可见/取消时摘）。
 *
 * ## 钩子线程为什么要自己的消息队列
 *
 * `SetWindowsHookEx` 的 `WH_MOUSE_LL` 需要**安装线程有消息队列**才能把回调派发进来；
 * 而且 `disarm()` 是靠 `PostThreadMessage(WM_QUIT)` 让钩子线程退出循环的 ——
 * 线程若还没建队列，这条消息会丢，钩子线程就永远卡在 `GetMessage` 上。
 * 故线程体里**先 `PeekMessage(PM_NOREMOVE)` 强制建队列，再发布 `threadId`**
 * （原实现 `089:platform/GlobalMenuDismisser.kt:68-72` 同此顺序，这是能干净退出的前提）。
 *
 * ## 契约
 *
 * - `arm()` / `disarm()` 可在任意线程调用，**都不抛异常**（失败即降级为"菜单不因外点关闭"）。
 * - [onOutsidePress] **在 EDT 上被调用** —— 调用方可以安全地直接操作 Swing。
 * - 非 Windows 平台：`User32` 加载失败 → `arm()` 返回 `false`，其余行为不变。
 *
 * @param onOutsidePress 判定为「点在菜单外」时要执行的动作（EDT 上）。
 */
internal class GlobalMenuDismisser(private val onOutsidePress: () -> Unit) {

    /**
     * 一次"装钩子"的会话。**用对象身份而非布尔标志**来判定回调是否属于当前会话 ——
     * 因为旧会话的钩子可能在卸载途中仍投递一次回调（钩子线程与 EDT 不同步），
     * 用身份比较可以精确地把那次过期回调丢掉。
     */
    private class HookSession {
        lateinit var callback: LowLevelMouseProc

        @Volatile
        var hook: HHOOK? = null

        @Volatile
        var threadId: Int = 0
    }

    private val user32 = User32.INSTANCE
    private val kernel32 = Kernel32.INSTANCE

    /** 当前会话。`null` = 未装钩子。用 `@Volatile` 是因为它被钩子线程与调用线程共读。 */
    @Volatile
    private var activeSession: HookSession? = null

    /**
     * 装上钩子。
     *
     * ## 为什么先 `disarm()` 再装
     *
     * `arm()` 是**幂等**的：菜单被反复弹出时（例如用户连点右键）会重复调用。
     * 不先摘旧的就会同时存在两个钩子 —— 两个都会回调，菜单会被关两次（无害），
     * 但**旧钩子的线程永远退不出去**（没有引用能再摘它），那是真正的泄漏。
     *
     * ## 为什么在 EDT 上等待钩子就绪（最多 2 秒）
     *
     * 装钩子是跨线程的，而**第一次点击可能紧随菜单出现**（用户右键后立刻点别处）。
     * 若异步装钩子，这段窗口里的点击会被漏掉 —— 表现为"偶尔点一下不关"，
     * 那是最难复现的失败形态。故这里**阻塞等待就绪**。
     * 实测耗时在毫秒级（只是建线程 + `SetWindowsHookEx`），2 秒只是防御性的上限；
     * 超时即认为失败并降级，绝不无限等待（那会把 EDT 冻住）。
     *
     * ## 为什么参数是**菜单自己的窗口**（与原实现同签名）
     *
     * 原实现 `089:platform/GlobalMenuDismisser.kt:36` 的签名是 `arm(window: Window)`：
     * 它在 `arm` 时就把该窗口的 `GetWindowRect` 存进闭包，供回调做**廉价剔除**。
     * 我们原先的 `arm()` 无参数、每次鼠标按下都无条件投递 EDT，
     * 那是"能用但不够好"：钩子线程是**系统输入线程**，用户正常点击时
     * 每一次按下都会往 EDT 塞一个任务，而其中绝大多数（点在菜单外任意处）
     * 本来就需要投递 —— 真正能省掉的是**点在菜单矩形内**的那些。
     * 本次按原实现签名补齐，见回调内的 `insideMenuRect`。
     *
     * ## ⚠️ 2026-09-17 修正：`window` 是**可选**的，不再是装钩子的前提
     *
     * **原记**：「调用时机是承重的：必须在菜单的 popup 窗口已经存在之后才 `arm`，
     * 否则 `GetWindowRect` 拿到的是无意义矩形」→ 故调用方写成
     * `SwingUtilities.getWindowAncestor(menu)?.let(dismisser::arm)`，
     * 并在取不到 ancestor 时**直接放弃装钩子**。
     *
     * **实为**（真机取证，`super-island.log`）：那条降级日志出现了 **17 次**，
     * 且每次都是 `windowAncestor=null` ——
     * 即 **`popupMenuWillBecomeVisible` 触发时 `getWindowAncestor(menu)` 就是 `null`**，
     * 于是 `arm()` **从未被调用过**，钩子**从来没装上**。
     * 而 AWT 降级路径**看不到本进程之外的点击**（点桌面/别的程序不产生本 JVM 的 AWT 事件），
     * 所以"点菜单外面关不掉"**必然复现**。
     *
     * **根因**：把一个**纯粹的优化**（廉价剔除用的矩形）当成了**功能前提**。
     * `menuRect` 唯一的作用是"点在菜单矩形内的按下不投递到 EDT"（见回调里的说明）——
     * 拿不到它，最坏后果只是**每次鼠标按下多投一个 `invokeLater`**，
     * 而不是"外点关闭不可用"。
     *
     * **现做法**：`window` 改为可空；拿不到就 `menuRect = null`，
     * 回调里跳过廉价剔除、直接走 EDT 上的完整判定
     * （[insideAnyPopup] 本来就会遍历所有可见窗口取原生矩形，**它才是权威判据**）。
     * 于是"装钩子"与"菜单窗口何时出现"**彻底解耦**。
     *
     * @param window 菜单自己的顶层窗口（`getWindowAncestor(menu)`），
     *   **可以为 `null`**（真机常见）。它只用于可选的廉价剔除。
     * @return 钩子是否真的装上。`false` 只表示"外点关闭不可用"，不影响菜单本身。
     */
    fun arm(window: Window? = null): Boolean {
        disarm()
        // 菜单自己的原生矩形（**物理像素**）—— **可选**的廉价剔除依据。
        // 拿不到（窗口还没建 / 高 DPI 下取不到 / JNA 异常）都不影响装钩子。
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
                // ⚠️ 这里在**钩子线程**上 —— 它阻塞多久，整个桌面的鼠标就卡多久。
                // 故只允许做**常数时间**的判定；任何窗口遍历都推到 EDT。
                if (nCode >= 0 && activeSession === session && wParam.toInt() in PRESS_MESSAGES) {
                    val px = info.pt.x
                    val py = info.pt.y
                    // ★ 廉价剔除（与原实现 089:platform/GlobalMenuDismisser.kt:47-50 同法）：
                    // 绝大多数鼠标按下都落在菜单矩形**外**（用户就是要点别处），
                    // 但那**恰恰是我们唯一关心的**——不能在这里 return!
                    // 这里剔除的是"落在菜单矩形**内**"的按下：那种按下是用户在操作菜单本身，
                    // 不必也不该投递到 EDT 去遍历窗口树。
                    //
                    // 为什么值得做：钩子线程是**系统输入线程**，每次鼠标按下都投一个
                    // `invokeLater` 会让 EDT 队列在用户正常点击时也被塞满我们的任务；
                    // 而用户点菜单内部时，菜单本来就会自己处理，我们什么都不用做。
                    // ⚠️ `menuRect == null`（拿不到菜单窗口）时**不做剔除** ——
                    // 退化为"每次按下都投到 EDT 走完整判定"。代价只是多一次
                    // `invokeLater`，而它换来的是**钩子一定能装上**。
                    // 把这条优化当成"装钩子的前提"正是本条缺陷的根因，见 [arm] 的 KDoc。
                    val insideMenuRect = menuRect != null &&
                        px >= menuRect.left && px < menuRect.right &&
                        py >= menuRect.top && py < menuRect.bottom
                    if (!insideMenuRect) {
                        SwingUtilities.invokeLater {
                            // EDT 上**二次校验**会话身份：投递到执行之间菜单可能已经关了
                            // （此时 activeSession 已被清空），那次过期回调必须丢弃。
                            // 子菜单/owner 的命中判定也放这里 —— 它要遍历窗口树，是"重活"。
                            if (activeSession === session && !insideAnyPopup(px, py)) {
                                onOutsidePress()
                            }
                        }
                    }
                }
                // ★ 无条件放行：见类 KDoc 约束 1（不吞点击）。
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
            // 先建本线程的消息队列，再发布 threadId（顺序不可换，见类 KDoc）。
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
            // 装好之前若已被 disarm()（用户手速极快：弹出→点击→关闭），
            // 这里要自己收尾，否则钩子会挂着一个没人引用的会话。
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
                // 双保险：正常退出路径由 disarm() 摘钩子，但线程若是被 WM_QUIT 唤醒
                // 或发生异常，这里也要摘 —— 钩子是全局资源，宁可摘两次。
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

    /**
     * 摘掉钩子并让钩子线程退出。**幂等**，未装时是空操作。
     *
     * 顺序要紧：**先摘钩子、再 `PostThreadMessage(WM_QUIT)`**。
     * 反过来的话，线程可能已退出而钩子还挂着 —— 那是一个没人能摘的全局钩子。
     */
    fun disarm() {
        val session = activeSession ?: return
        activeSession = null
        session.hook?.let(user32::UnhookWindowsHookEx)
        session.hook = null
        val threadId = session.threadId
        // threadId == 0 表示线程还没走到"建队列 + 发布 id"那一步 —— 此时发消息会丢，
        // 但也没关系：线程随后会因 `activeSession !== session` 自行收尾退出。
        if (threadId != 0) user32.PostThreadMessage(threadId, WM_QUIT, WPARAM(0), LPARAM(0))
    }

    /**
     * 点是否落在**本进程任何一个可见的菜单弹出窗**内。
     *
     * ## 为什么比的是**原生 rect（物理像素）**而不是 Java 的 `bounds`（逻辑像素）
     *
     * 钩子回调拿到的是**物理像素**坐标（`MSLLHOOKSTRUCT.pt`），而 Java 的
     * `Component.getBounds()` 是**逻辑像素**。在 125% / 150% 缩放下两者不等，
     * 用逻辑 bounds 去比会让"菜单内"被判成"菜单外" → 点菜单自己也会把菜单关掉。
     * 故一律 `GetWindowRect` 取原生矩形（原实现 `089:platform/GlobalMenuDismisser.kt:126-139`
     * 的注释已点明这一点）。
     *
     * ## 为什么要遍历**所有**可见窗口而不是只比主菜单
     *
     * 子菜单（`JMenu` 展开出的那一个）是**独立的顶层 Popup 窗口**，
     * 不在主菜单的矩形内。只比主菜单的话，点子菜单会被判成"外部"而误关
     * （原实现 `089:platform/GlobalMenuDismisser.kt:54-55` 的注释同此）。
     * 本插件的首版菜单没有子菜单，但这个判定**不因此简化** ——
     * 简化了以后加子菜单时就会踩同一个坑，而那时没人会想到来这里看一眼。
     *
     * 必须在 EDT 上调用（会触碰 AWT 窗口树）。
     */
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

    /** 递归判定容器里是否含 `JPopupMenu` —— 用于区分"菜单弹出窗"与"别的窗口"。 */
    private fun containsPopup(container: Container): Boolean {
        if (container is JPopupMenu) return true
        for (child in container.components) {
            if (child is Container && containsPopup(child)) return true
        }
        return false
    }

    private companion object {
        /** `WH_MOUSE_LL` —— 低层鼠标钩子（来源：WinUser.h；数值不可臆造）。 */
        const val WH_MOUSE_LL = 14

        const val WM_LBUTTONDOWN = 0x0201
        const val WM_RBUTTONDOWN = 0x0204
        const val WM_MBUTTONDOWN = 0x0207

        /** `WM_QUIT` —— 投给钩子线程，让它退出 `GetMessage` 循环。 */
        const val WM_QUIT = 0x0012

        /** `PM_NOREMOVE` —— 只探测不取走，用于强制建立消息队列。 */
        const val PM_NOREMOVE = 0

        /**
         * 只对**按下**消息反应，不看抬起。
         *
         * 理由：抬起消息可能在按下之后很久、光标已在别处时才到（拖拽），
         * 那时用抬起位置判定"在菜单外"会把一次从菜单内开始的拖拽误判成外点。
         * 按下的那一刻才是"用户点到了哪里"的真实语义。
         */
        val PRESS_MESSAGES = setOf(WM_LBUTTONDOWN, WM_RBUTTONDOWN, WM_MBUTTONDOWN)
    }
}
