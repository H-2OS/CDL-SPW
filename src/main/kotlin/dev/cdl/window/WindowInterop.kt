// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Win32 User32 的最小封装 —— 「API 之外但合法」的那一层。
 *
 * ## 为什么需要这个文件（约束与理由）
 *
 * SPW 只暴露三组宿主 API：`WorkshopApi.playback`（播放控制）、`WorkshopApi.ui.toast`、
 * `WorkshopApi.manager`。**没有窗口/菜单/托盘 API** —— 因此「无边框、透明、置顶、
 * 可鼠标穿透的悬浮窗」这三个诉求，宿主一个都满足不了，只能落到 JVM/Swing/Java2D 自绘
 * 加上 Win32 User32 扩展样式这一层。
 *
 * 这么做合法，是因为 SPW 插件跑在**与宿主同进程、同一个 JVM** 里且**没有沙箱**：
 * JNA 能直接拿到 `User32.dll` 的函数地址。代价是**平台绑定** —— 本文件的所有能力
 * 在非 Windows 上都不可能成立，故每一个入口都必须能「静默降级」而不是抛异常
 * （项目铁律：插件不能因为环境不支持就崩）。
 *
 * ## 为什么「鼠标穿透」必须用 WS_EX_TRANSPARENT 而不是 Swing 的 setEnabled(false)
 *
 * Swing 层面**没有**鼠标穿透的概念。`Component.setEnabled(false)` 只是让组件不响应
 * 自己的鼠标事件，但**事件仍然会被 Swing 的 `AWTEvent` 分派到该窗口**，更关键的是
 * **底层窗口（HWND）依然会在 `WindowFromPoint` 里命中** —— 于是宿主窗口（或桌面）
 * 就永远收不到那些点击。真正的穿透是**窗口管理器层**的属性：
 * `WS_EX_TRANSPARENT` 让该 HWND 在命中测试（hit-test）中被跳过，
 * 鼠标消息直接下传到它下面的窗口。这就是为什么必须走 User32 而不是 Swing。
 *
 * 顺带需要 `WS_EX_LAYERED`：`WS_EX_TRANSPARENT` 只有在窗口是 layered 时才能可靠地
 * 参与「命中测试透传」这条路径（见 Microsoft 文档对 layered window 的说明）。
 * 注意 `WS_EX_LAYERED` **不等于**开启 `SetLayeredWindowAttributes` 的 alpha 混合 ——
 * 后者是「整个窗口一个统一 alpha」，会毁掉我们自绘的圆角与阴影渐变。
 * 我们要的透明度是**逐像素**的，那一条走 Swing/JVM 的 per-pixel translucency
 * （`setBackground(Color(0,0,0,0))` + `Window.setOpacity` 以外的路径），
 * 而不是 `SetLayeredWindowAttributes`。二者都要求 `WS_EX_LAYERED` 位存在，恰好同路。
 *
 * ## 为什么不用 `sun.awt` 内部类反射取 HWND
 *
 * `sun.awt.windows.WComponentPeer.getHWnd()`、`JAWT` 之类**都是 JDK 内部 API**：
 * ① 模块系统（JPMS）在 JDK 9+ 对 `sun.*` 做了封装，反射会撞 `InaccessibleObjectException`，
 * 除非插件额外加 `--add-opens` —— 而启动参数由**宿主**决定，我们改不了；
 * ② 即使今天能用，任何一次 JDK 小版本升级都可能悄悄移除，属于「发布后才发现坏了」的坑。
 * 故退一步用**公开、稳定、自 1993 年就在的** `FindWindowW` / `EnumWindows` 按标题查找 ——
 * 代价是依赖「窗口标题唯一」这一前提（见 [findHwndByTitle] 的降级说明）。
 *
 * ## 线程与异常契约
 *
 * - 所有方法**可在任意线程调用**，但通常从 EDT 调用（因为需要在窗口显示后取 HWND）。
 * - **任何 JNA 调用都不得让异常逃逸**：平台不支持（Linux/macOS）、DLL 加载失败、
 *   HWND 为 0 —— 一律记录到 stderr 并返回 `false` / `null`，让调用方无脑降级。
 */
internal object WindowInterop {

    // ── Win32 常量（来源：WinUser.h；数值不可臆造，逐条注明出处） ──────────

    /** `GWL_EXSTYLE` —— `GetWindowLongW`/`SetWindowLongW` 的索引，取「扩展窗口样式」。 */
    private const val GWL_EXSTYLE = -20

    /**
     * `WS_EX_TRANSPARENT` —— 命中测试透传。
     * 让该窗口在 `WindowFromPoint` 中不被命中，鼠标消息下落到其下方的窗口。
     */
    private const val WS_EX_TRANSPARENT = 0x00000020

    /**
     * `WS_EX_LAYERED` —— 分层窗口。
     * `WS_EX_TRANSPARENT` 的透传要可靠生效，窗口需为 layered；
     * per-pixel 半透明窗口在 Windows 上也要求这一位。
     */
    private const val WS_EX_LAYERED = 0x00080000

    /**
     * `WS_EX_TOOLWINDOW` —— 工具窗口。
     * 效果：**不出现在 Alt+Tab 与任务栏**里。词岛是常驻悬浮件，
     * 混进 Alt+Tab 列表会干扰用户切换真实应用。
     */
    private const val WS_EX_TOOLWINDOW = 0x00000080

    /** `HWND_TOPMOST` —— `SetWindowPos` 的置顶锚点（值 `-1`，按 `WinDef.HWND` 传 -1）。 */
    private const val HWND_TOPMOST = -1

    /** `HWND_NOTOPMOST` —— 取消置顶（值 `-2`）。 */
    private const val HWND_NOTOPMOST = -2

    /** `SWP_NOSIZE` —— 不改尺寸。 */
    private const val SWP_NOSIZE = 0x0001

    /** `SWP_NOMOVE` —— 不改位置。 */
    private const val SWP_NOMOVE = 0x0002

    /**
     * `SWP_NOACTIVATE` —— **不要激活窗口**。
     * 这条对词岛是必须的：`SetWindowPos` 默认会把窗口带到前台并激活，
     * 那会让词岛抢走当前播放器的焦点（用户正在键盘操作时会突然「断掉」）。
     */
    private const val SWP_NOACTIVATE = 0x0010

    // ── JNA 接口声明 ──────────────────────────────────────────────────────

    /**
     * `User32.dll` 的函数子集。
     *
     * 用 `StdCallLibrary`（`__stdcall` 调用约定）而非 `Library`：x86 上两者不同，
     * x64 上恰好合一 —— 但声明成 `StdCallLibrary` 才在两种架构下都正确。
     * `W32APIOptions.DEFAULT_OPTIONS` 让 JNA 自动选用 `...W`（宽字符）变体并映射
     * `WinDef.HWND` 等类型，避免手工处理 `TCHAR` 宽度问题。
     *
     * ## `SetWindowLongW` 的返回值截断问题（**重要，见下方 `setClickThrough` 的「⚠️ 不确定项」小注**）
     *
     * Win32 的 `SetWindowLongW` 返回 **`LONG`（32 位）**，而我们读写的是
     * `GWL_EXSTYLE` 这个 **32 位**样式位 —— 二者的宽度是匹配的，**样式值本身不会截断**。
     * 真正微妙的是 x64 的 ABI：`LONG_PTR` 是 64 位，微软为此另有一个
     * `SetWindowLongPtrW`。在本用例（扩展样式仅 32 位有效位）下用 `SetWindowLongW`
     * 是官方允许的（`GWL_EXSTYLE` 明确不在「必须用 Ptr 版本」的清单里），
     * 但**返回值的「上一个样式」是 32 位**，若将来有人拿它去读写 64 位指针型索引
     * （如 `GWLP_WNDPROC`）就会截断。故本文件**只**用 `GWL_EXSTYLE`，
     * 并在这一步显式声明为 `int` 让截断语义显式化。
     */
    private interface User32 : StdCallLibrary {
        /** 读窗口的 32 位「长」值（`nIndex` 为 `GWL_EXSTYLE` 时即扩展样式）。 */
        fun GetWindowLongW(hWnd: WinDef.HWND, nIndex: Int): Int

        /** 写窗口的 32 位「长」值。返回**修改前**的旧值。 */
        fun SetWindowLongW(hWnd: WinDef.HWND, nIndex: Int, dwNewLong: Int): Int

        /** 调整窗口的 Z 序 / 位置 / 尺寸。此处只用它改 Z 序（置顶开关）。 */
        fun SetWindowPos(
            hWnd: WinDef.HWND,
            hWndInsertAfter: WinDef.HWND,
            X: Int,
            Y: Int,
            cx: Int,
            cy: Int,
            uFlags: Int,
        ): Boolean

        /** 按「类名 + 窗口标题」精确查找顶层窗口。标题必须完全相等（大小写敏感）。 */
        fun FindWindowW(lpClassName: com.sun.jna.WString?, lpWindowName: com.sun.jna.WString?): WinDef.HWND?

        /** 读窗口标题到缓冲区，返回写入的**字符数**（不含结尾 NUL）。 */
        fun GetWindowTextW(hWnd: WinDef.HWND, lpString: CharArray, nMaxCount: Int): Int

        /**
         * 当前前台窗口。作为 `FindWindowW` 找不到时的兜底候选来源。
         *
         * 可空：JNA 把 Win32 的 NULL HWND 映射成 Java `null`（见 [findHwndByTitle] ①）。
         */
        fun GetForegroundWindow(): WinDef.HWND?

        /** 窗口是否可见。枚举回调里用于跳过隐藏窗口，避免命中同名隐藏窗。 */
        fun IsWindowVisible(hWnd: WinDef.HWND): Boolean

        /**
         * 句柄是否**仍然有效**。
         *
         * 用途：`Native.getWindowPointer(window)` 拿到的是「当时那一刻」的 peer 指针，
         * 而 AWT 在**可见性切换**时会换掉原生 peer（见原实现 `WindowsOverlay.kt:22` 的
         * `takeIf { api.IsWindow(it) }`）。故用之前必须校验一次 ——
         * 拿一个已销毁的 HWND 去 `SetWindowLongW`，轻则无效、重则改错窗口。
         */
        fun IsWindow(hWnd: WinDef.HWND): Boolean

        /**
         * 读窗口的**屏幕坐标**边界矩形（`GetWindowRect`）。
         *
         * 用途：判断"前台窗口是否铺满屏幕"（见 [isForegroundFullscreen]）。
         * 返回 `false` 表示调用失败（句柄无效等）—— **调用方必须检查**，
         * 因为失败时 `RECT` 的内容是未定义的（JNA 会给零值，那是"左上角 0,0 的零尺寸矩形"，
         * 拿它去判"是否铺满屏幕"会得出**恒假**的结论，静默失效）。
         *
         * 2026-09-18 新增（`island.hide_fullscreen` 与"任务栏遮挡"两处都要它）。
         */
        fun GetWindowRect(hWnd: WinDef.HWND, lpRect: WinDef.RECT): Boolean

        /**
         * 枚举全部顶层窗口。回调返回 `true` 继续、`false` 停止。
         *
         * `lParam` 原样透传给回调（此处不用，传 `null`）；返回 `Boolean` 表示枚举是否
         * 正常跑完 —— 但「回调主动返回 false 停止」也会让它返回 `false`。因此
         * **不能**把返回值当失败判据（这正是「命中即停」后不该报错的原因）。
         */
        fun EnumWindows(lpEnumFunc: EnumWindowsProc, lParam: Pointer?): Boolean

        /**
         * 取窗口所属的**进程 id**（写入 `lpdwProcessId`），返回**线程 id**。
         *
         * ## 用途：判断"前台窗口是否属于本进程"（BUG-145 · F-4′）
         *
         * 词岛与宿主**同进程、同 JVM**（插件的既定架构）⇒ "用户是否还在本 App 里"
         * 等价于"前台窗口的 pid 是否等于本进程 pid"。
         *
         * ★ **2026-10-01 01:2x 起重新成为产品判据的底层调用**（用户澄清「点击词岛
         * **外部**才释放」⇒ 判据回到"前台是否属于本进程"）。中间一度被误读的
         * "点了才释放 = 点词岛"取代，已回退（见 `migration/57`）。
         *
         * ⚠️ 返回值是**线程 id**（可能为 0 表示失败），**进程 id 走出参**。
         * 拿返回的线程 id 当 pid 比是**错的** —— 这两个 id 值域重叠但语义不同，
         * 且错误不会报错、只会静默给出错误结论。
         *
         * 2026-09-30 新增（F-4′）。实测依据：`_f4prime_probe/ZOrderProbe2.java`。
         */
        fun GetWindowThreadProcessId(hWnd: WinDef.HWND, lpdwProcessId: IntByReference): Int
    }

    /** `EnumWindows` 的回调原型。返回 `true` 表示继续枚举，`false` 表示停止。 */
    private interface EnumWindowsProc : StdCallLibrary.StdCallCallback {
        fun callback(hWnd: WinDef.HWND, lParam: Pointer?): Boolean
    }

    /**
     * 惰性初始化的 `User32` 句柄。
     *
     * `null` 表示加载失败（非 Windows 平台 / DLL 缺失），此后**所有**方法都走降级路径。
     * 用 `runCatching` 包住是**关键**：`Native.load` 在 Linux 上会抛
     * `UnsatisfiedLinkError`，那是 `Error` 而非 `Exception`，不 catch 就会直接
     * 打穿到宿主 —— 这是本项目最不能接受的失败方式。
     */
    private val user32: User32? by lazy {
        runCatching {
            Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.onFailure {
            System.err.println("[CDL] User32 不可用（非 Windows 或加载失败），窗口扩展能力降级：$it")
        }.getOrNull()
    }

    /** 平台能力是否可用（User32 已加载）。调用方可用它决定要不要提示用户。 */
    val available: Boolean
        get() = user32 != null

    // ── 公开能力 ─────────────────────────────────────────────────────────

    /**
     * 设置鼠标穿透（`WS_EX_TRANSPARENT | WS_EX_LAYERED`）。
     *
     * ## 为什么切换时要做「读-改-写」而不是直接 `|=`
     *
     * 扩展样式是一个**位集合**，同一个位段里还住着 `WS_EX_TOOLWINDOW` 等我们
     * 依赖的其他位。若直接 `SetWindowLongW(..., current or WS_EX_TRANSPARENT)`，
     * 就必须自己维护一份「当前样式」的影子状态 —— 而这份影子会与真实 HWND 漂移
     * （例如 DWM 或别的代码改过样式）。故**每次都从 HWND 现读**，
     * 在此基础上加减位，再写回。读-改-写的窗口极短，且这两个调用本来就在同一线程，
     * 没有竞争问题。
     *
     * ⚠️ **不确定项**：`SetWindowLongW` 的返回值是「旧值」而非成功标志。
     * Win32 文档要求「调用后清 `GetLastError` 再检查」才能判断失败，
     * 而 JNA 的 `LastErrorException` 需要 `declareLastError` 才生效 ——
     * 本文件**没有**依赖错误码做判断（那会让代码更脆），而是读回样式验证。
     *
     * @return 是否成功写入（HWND 有效且 User32 可用）。失败不抛异常。
     */
    fun setClickThrough(hwnd: Long, enabled: Boolean): Boolean = withHwnd(hwnd) { handle ->
        val current = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        // 开启时同时置 LAYERED 与 TRANSPARENT；关闭时只摘 TRANSPARENT，
        // **保留 LAYERED** —— 因为 per-pixel 半透明窗口本身就需要它，
        // 一并摘掉会让窗口变成不透明的黑底。
        val target = if (enabled) {
            current or WS_EX_TRANSPARENT or WS_EX_LAYERED
        } else {
            current and WS_EX_TRANSPARENT.inv()
        }
        user32!!.SetWindowLongW(handle, GWL_EXSTYLE, target)

        // ── 读回校验（2026-09-17 新增）───────────────────────────────────
        //
        // 原记：`SetWindowLongW(...)` 之后**直接 `true`**。
        // 实为：那掩盖了失败 —— `SetWindowLongW` 失败时返回 0，而这里从不检查，
        //   于是**调用方以为设上了、实际没有**，用户看到的是"勾了穿透没反应"。
        //   这是「词岛锁定和鼠标穿透无法作用于词岛」这条真机反馈的**首要嫌疑**
        //   （见 recon/37 §1.3）。
        //
        // 为什么用"读回校验"而不是检查 `SetWindowLongW` 的返回值：
        //   它的返回值是**旧值**，而旧值本身可能就是 0（合法的"原本没样式"），
        //   故"返回 0"无法区分"失败"与"原本就是 0"。读回一次最直接。
        //
        // ⚠️ 本校验**只判断"样式位有没有设上"**，不判断"设上了是否生效" ——
        //   后者（如 layered 时序问题）需真机验证，不是这里能覆盖的。
        val actual = user32!!.GetWindowLongW(handle, GWL_EXSTYLE)
        val transparentNow = (actual and WS_EX_TRANSPARENT) != 0
        if (transparentNow == enabled) return@withHwnd true

        System.err.println(
            "[CDL] ⚠️ 设置鼠标穿透**未生效**：期望 enabled=$enabled，" +
                "读回 TRANSPARENT=$transparentNow" +
                "（exStyle before=0x${current.toString(16)} target=0x${target.toString(16)} " +
                "after=0x${actual.toString(16)}）"
        )
        false
    }

    /**
     * 设置置顶（`HWND_TOPMOST` / `HWND_NOTOPMOST`）。
     *
     * ## 为什么走 `SetWindowPos` 而不是 `Topmost` 样式的位操作
     *
     * 「置顶」在 Win32 里**不是扩展样式位**，而是 Z 序（Z-order）状态 ——
     * 由 `SetWindowPos` 携带 `HWND_TOPMOST`/`HWND_NOTOPMOST` 完成。
     * Swing 的 `Window.setAlwaysOnTop(true)` 内部也是同一个调用，
     * 但它**必须在 EDT 上**执行；本函数可在任意线程调用（JNA 调用本身线程无关）。
     *
     * `SWP_NOMOVE | SWP_NOSIZE`：只改 Z 序，绝不动几何 —— 若漏了这两个标志，
     * `SetWindowPos` 会把窗口挪到 (0,0) 并改用传入的尺寸（此处传 0），
     * 那是极易发生且看起来像「窗口莫名跳走」的 bug。
     */
    fun setAlwaysOnTop(hwnd: Long, enabled: Boolean): Boolean = withHwnd(hwnd) { handle ->
        user32!!.SetWindowPos(
            handle,
            WinDef.HWND(Pointer.createConstant(if (enabled) HWND_TOPMOST else HWND_NOTOPMOST)),
            0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE,
        )
        true
    }

    /**
     * 让**原生标题栏**跟随深浅色（Windows 10 1809+ / Windows 11）。
     *
     * ## 为什么需要它（2026-09-21，用户截图取证）
     *
     * 用户原话：「窗口**仍有未跟随主题颜色部分**」。看截图，最显眼的一处就是**标题栏**：
     * 窗口内容已经是深色，而标题栏仍是**浅色**，整窗看起来像"拼起来的"。
     *
     * 纯 Swing **做不到**这件事 —— `JDialog` 的标题栏由 **OS 绘制**，
     * 它不读 Swing 的 `background`，而是读 DWM 的"沉浸式深色模式"属性
     * （`DWMWA_USE_IMMERSIVE_DARK_MODE`）。故只能走 DWM。
     *
     * ## ⚠️ 属性编号随 Windows 版本变（这是本函数最容易错的点）
     *
     * | 系统 | 编号 |
     * | --- | --- |
     * | Windows 10 1809 – 1903 | **19** |
     * | Windows 10 1903+ / Windows 11 | **20** |
     *
     * 故**两个都设** —— 设一个不存在的属性只会返回非零错误码，**没有副作用**
     * （微软自己的示例代码也是这么处理的）。只要有一个返回 0 就算成功。
     *
     * ## 降级
     *
     * 非 Windows / 老系统 / `dwmapi.dll` 不可用 / 拿不到 HWND → **静默返回 false**，
     * 标题栏保持系统默认色。**功能不受任何影响**（内容区的主题化不依赖它）。
     *
     * @return 是否至少有一个属性设置成功（供探针/日志判断，不影响调用方行为）。
     */
    fun setDarkTitleBar(window: java.awt.Window, dark: Boolean): Boolean = runCatching {
        val raw = hwndOf(window) ?: return@runCatching false
        val api = Dwmapi.INSTANCE ?: return@runCatching false
        val handle = WinDef.HWND(Pointer.createConstant(raw))
        val value = com.sun.jna.Memory(4).apply { setInt(0, if (dark) 1 else 0) }
        val newer = runCatching { api.DwmSetWindowAttribute(handle, 20, value, 4) }.getOrDefault(-1)
        val older = runCatching { api.DwmSetWindowAttribute(handle, 19, value, 4) }.getOrDefault(-1)
        newer == 0 || older == 0
    }.getOrDefault(false)

    /**
     * `dwmapi.dll` 的**最小**封装 —— 只用到「沉浸式深色标题栏」这一个函数。
     *
     * `INSTANCE` 可为 `null`（加载失败）：调用方按"这台机器不支持"处理，
     * **不要**在这里抛异常 —— 与 [user32] 同样的降级纪律
     * （本文件所有能力在非 Windows 上都必须能静默降级）。
     */
    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            attribute: Int,
            value: Pointer,
            size: Int,
        ): Int

        companion object {
            /** 加载失败 → `null`（老系统 / 非 Windows）。 */
            val INSTANCE: Dwmapi? = runCatching {
                Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }.getOrNull() as? Dwmapi
        }
    }

    /**
     * 把窗口**重新提到 topmost 组的最前**（2026-09-18 新增）。
     *
     * ## 为什么需要它：同为 topmost 的窗口之间还有 Z 序
     *
     * `HWND_TOPMOST` 不是"永远在最上"，而是"加入 topmost 组"。
     * **组内仍按 Z 序排** —— 而后加入者在上。**任务栏也是 topmost 组的成员**，
     * 且 Windows 会在若干时机（窗口切换、全屏应用退出、Shell 重绘）**把它自己重新提上来**，
     * 于是词岛会被压到任务栏下面 —— 这正是用户报的「**全屏时任务栏会遮挡词岛**」。
     *
     * ## 与 [setAlwaysOnTop] 的区别（别用错）
     *
     * | | 用途 | 何时调 |
     * |---|---|---|
     * | [setAlwaysOnTop] | **开/关**置顶（设置变更） | 设置变化时 |
     * | 本函数 | **重申**置顶（把自己提回组内最前） | 检测到可能被压时 |
     *
     * 本函数**不改** topmost 的开关状态 —— 若窗口本来不是 topmost，
     * `SetWindowPos(HWND_TOPMOST)` 会**把它变成** topmost。故调用方**必须先确认
     * 用户开启了置顶**，否则会凭空造出一个置顶窗口。
     *
     * `SWP_NOACTIVATE` 是必须的：否则每次重申都会把焦点抢过来，
     * 用户在全屏应用里打字会被打断 —— 那比"被遮挡"更糟。
     */
    fun raiseToTop(hwnd: Long): Boolean = withHwnd(hwnd) { handle ->
        user32!!.SetWindowPos(
            handle,
            WinDef.HWND(Pointer.createConstant(HWND_TOPMOST)),
            0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE,
        )
    }

    /**
     * 前台窗口是否**铺满屏幕**（全屏应用判定）。
     *
     * ## 判定方法：矩形覆盖，不用 `SHQueryUserNotificationState`
     *
     * 后者（Shell32）能识别 D3D 独占全屏，但：
     * ① 本项目的 `User32` 接口是**自建的最小集**，引 Shell32 要多一套 JNA 绑定；
     * ② 它只回答"用户是否处于免打扰状态"，**不告诉你哪个窗口全屏** ——
     *    而我们需要排除"词岛自己"（词岛也是铺满的，不排除就会自我判定成全屏）。
     * 矩形法用已有的 `GetForegroundWindow` + 新增的 `GetWindowRect` 就能做，且可排除自身。
     *
     * ## 边界取 `<=` / `>=` 而不是 `==`
     *
     * 全屏窗口的矩形**常比屏幕大 1~8 px**（无边框全屏的实现差异），
     * 用等号判定会**漏判**。故用"覆盖"语义：左/上不晚于屏幕左上、右/下不早于屏幕右下。
     *
     * @param excludeHwnd 要排除的窗口（传词岛自己的 hwnd）——
     *   否则词岛在 topmost 且铺满时会把自己判成"全屏应用"，
     *   表现为「一开全屏隐藏就立刻隐藏自己」，且**看不出为什么**。
     */
    fun isForegroundFullscreen(excludeHwnd: Long): Boolean {
        val api = user32 ?: return false
        val fg = api.GetForegroundWindow() ?: return false
        val fgValue = Pointer.nativeValue(fg.pointer)
        if (fgValue == excludeHwnd) return false

        val rect = WinDef.RECT()
        // ⚠️ 失败时 `rect` 是零值矩形（0,0,0,0），拿它判"覆盖屏幕"会**恒假** ——
        // 那是静默失效。故这里必须检查返回值并直接返回 false（= 判为"非全屏"）。
        if (!api.GetWindowRect(fg, rect)) return false

        val cfg = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration
        // ★★ 2026-09-29 修 BUG-144：两侧必须**同一坐标系**再比较。
        //
        // ## 原记（错）
        // 直接拿 `rect`（`GetWindowRect` ⇒ **物理像素**）与
        // `cfg.bounds`（Java2D ⇒ **逻辑/用户坐标**）比大小。
        //
        // ## 实为（本机实测 2026-09-29）
        // ```
        //   defaultConfiguration.bounds = 1707x1067   ← 逻辑
        //   getDefaultTransform()       = scale 1.5   ← 150% 缩放
        //   物理                         = 2560x1600
        // ```
        // ⇒ 设缩放 `s`，窗口在逻辑上覆盖屏幕 `r` 比例时判据退化成 `r·LW·s ≥ LW` ⇒ **`r ≥ 1/s`**：
        //
        // | 缩放 | 覆盖多少就被判成"全屏" |
        // |---|---|
        // | 100% | 100% ⇒ ✅ 正确 |
        // | 125% | 80% |
        // | **150%** | ★ **66.7%** |
        // | 200% | 50% |
        //
        // ⇒ 症状：窗口拉到约 2/3 屏，词岛**自己消失**（`island.hide_fullscreen` 默认 `true`）。
        // ★ 且 `s = 1` 时两种写法**逐值相等** ⇒ 本修复对 100% 缩放**零回归**。
        //
        // ★ 换算抽成纯函数 [physicalBoundsOf] —— 这样"物理/逻辑是否已统一"**可被探针断言**
        //   （本函数依赖真实窗口句柄，探针无法构造）。
        val screen = physicalBoundsOf(cfg)
        return rect.left <= screen.x &&
            rect.top <= screen.y &&
            rect.right >= screen.x + screen.width &&
            rect.bottom >= screen.y + screen.height
    }

    /**
     * 当前**前台窗口**所属的**进程 id**（2026-09-30 新增，BUG-145 · F-4′）。
     *
     * ## 用途
     *
     * 判断"用户是否还在本 App 里"。词岛与宿主**同进程、同 JVM** ——
     * 故 `foregroundProcessId() == 本进程 pid` 就等价于"焦点还在本 App（词岛或 SPW 窗口）"。
     *
     * ★★ **2026-10-01 01:2x：本方法就是现行产品判据的底层调用** ——
     * 用户逐字澄清「我说的是**点击词岛外部**就释放，**不是点击词岛**」
     * ⇒ 判据回到"前台窗口是否属于本进程"（`ZOrderPolicy.shouldHoldTempTopmost`）：
     * 点词岛 ⇒ 前台仍是本进程 ⇒ **保持**；点别的应用 / 桌面 / 任务栏 ⇒ 前台离开 ⇒ **释放**。
     *
     * ⚠️ 中间一度（2026-09-30 16:3x – 2026-10-01 01:2x）被一个**误读**的判据取代
     * （`shouldKeepTempTopmostManualOnly` —— 把"点了才释放"读成"点**词岛**才释放"），
     * 用户 2026-10-01 01:2x 纠正后**已回退**，那个误读的函数**已删除**。见 `migration/57`。
     *
     * ## ★ 为什么不能用 `GetForegroundWindow` 的 hwnd 去比对"词岛 hwnd"
     *
     * 实测（`_f4prime_probe/ZOrderProbe2.java`）：词岛是**无边框 `JWindow` 且不进任务栏**，
     * 它成为前台窗口时 `GetForegroundWindow` 返回的 hwnd **未必**是我们传给
     * `WindowInterop` 的那个 peer 句柄（AWT 在可见性切换时会换原生 peer，见 [IsWindow] 的 KDoc）。
     * 而**进程 id 是稳定的** —— 比 hwnd 可靠。
     *
     * ## 返回 0 表示"取不到"
     *
     * 调用方**必须**把 0 当作"判据不可用"（而不是"前台属于 0 号进程"）——
     * 见 `shouldHoldTempTopmost` 的真值表。取不到时**不应**据此释放临时 topmost
     * （宁可多保持一会儿，也不要因为一次 API 抖动就闪烁）。
     *
     * ⚠️ `GetWindowThreadProcessId` 的**返回值是线程 id**，进程 id 走出参 ——
     * 拿返回值当 pid 比是错的（见该声明的 KDoc）。
     */
    fun foregroundProcessId(): Int {
        val api = user32 ?: return 0
        val fg = api.GetForegroundWindow() ?: return 0
        val pid = IntByReference(0)
        api.GetWindowThreadProcessId(fg, pid)
        return pid.value
    }

    /**
     * 把一个 `GraphicsConfiguration` 的**逻辑 bounds** 换算成**物理像素** bounds。
     *
     * ## 为什么需要它（BUG-144）
     *
     * `GetWindowRect` 给的是**物理像素**，而 Java2D 的 `bounds` 是**逻辑坐标**
     * （已除过缩放）⇒ 两者**不能直接比较**。
     *
     * ## 契约
     *
     * - ★ **缩放 = 1（100%）时返回值与入参逐值相同** ⇒ 零回归。
     * - ★ `getDefaultTransform()` 不可用（老 JDK / 无头环境）⇒ **回退为逻辑 bounds**
     *   （= 改动前的行为，宁可少一层保护，也不抛）。
     * - ★ **不抛**（`runCatching`）—— 与 `WindowInterop` 其余方法的降级约定一致
     *   （见文件头："`Native.load` 在 Linux 上抛 `UnsatisfiedLinkError`"那段）。
     *
     * ⚠️ **本函数是 `internal` 的** ⇒ 探针（独立编译单元）**看不到**。
     *   故真正被断言的纯函数是**顶层**的 [physicalBounds]（它接收原始数字，无 Java2D 依赖）。
     *
     * @param cfg 屏幕配置。取 `.bounds` 与 `.defaultTransform`。
     */
    internal fun physicalBoundsOf(cfg: java.awt.GraphicsConfiguration): java.awt.Rectangle =
        runCatching {
            val b = cfg.bounds
            val t = cfg.defaultTransform
            physicalBounds(b.x, b.y, b.width, b.height, t.scaleX, t.scaleY)
        }.getOrElse { cfg.bounds }

    /**
     * 设置工具窗口（`WS_EX_TOOLWINDOW`）—— 从 Alt+Tab 与任务栏隐去。
     *
     * 同 [setClickThrough]，用读-改-写。注意：这一位**对已有窗口是「显示后设置」**，
     * 在某些 Windows 版本上需要重新显示窗口才能让任务栏图标消失；
     * 本函数只负责置位，不做重显示（那属于窗口生命周期，归 `IslandWindow` 管）。
     */
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

    /**
     * **从 AWT `Window` 直接取原生 HWND** —— 本文件**首选**的定位方式（2026-09-16 新增）。
     *
     * ## 为什么这是首选，而 [findHwndByTitle] 退为兜底
     *
     * 真机日志（12:36 构建）显示「鼠标穿透无效」的根因是**窗口根本没定位到**：
     *
     * ```
     * [CDL] 按标题查找窗口失败（降级为无扩展能力）：java.lang.NullPointerException:
     *     Cannot invoke "com.sun.jna.platform.win32.WinDef$HWND.getPointer()" because "direct" is null
     * [CDL] 未能按标题找到词岛窗口（HWND 未知）：鼠标穿透与「不显示在 Alt+Tab」将不可用
     * ```
     *
     * 按标题查找是**脆弱设计**：它依赖「窗口标题对 `GetWindowTextW` 可见」，
     * 而 `JWindow` 没有标题栏（`Window.setName` 是否真的传到 peer 的窗口文本
     * **在 JDK 实现上是不可靠的**）。**原实现 0.8.9 根本不用标题**：
     *
     * ```kotlin
     * // 089:platform/WindowsOverlay.kt:17
     * private fun handle(window: Window) = HWND(Native.getWindowPointer(window))
     * ```
     *
     * `Native.getWindowPointer` 是 **JNA 内建**能力（不是反射、不是私有 API）：
     * 它向 AWT 的 peer 要那个 `HWND` 字段。**我们的窗口是我们自己创建的，
     * 因此句柄是确定的**，不存在「标题重名取错窗口」的风险 —— 比标题匹配**更**安全。
     *
     * ## 三道校验（缺一不可，逐条对应真实失效模式）
     *
     * 1. `available` —— User32 没加载时后续调用全无意义（非 Windows）。
     * 2. `window.isDisplayable` —— **没有 peer 就取不到指针**。`isVisible = true`
     *    之后 native 建窗是**异步**的，故调用方需要「立刻试一次 + 下一轮 EDT 再试」
     *    （见 `IslandWindow.resolveHwnd`）。原实现同样先判 `isDisplayable`
     *    （`WindowsOverlay.kt:20`）。
     * 3. `IsWindow(hwnd)` —— `getWindowPointer` 给的是「当时那一刻」的指针，
     *    而 AWT 在**可见性切换**时会换掉原生 peer。拿已销毁的 HWND 去
     *    `SetWindowLongW` 轻则无效、重则改错窗口。既有的 `existingHandle` 也做这一步。
     *
     * ## 与 `FindWindowW` 的关系
     *
     * [findHwndByTitle] **保留**，但降为兜底：极少数情况下 `getWindowPointer`
     * 可能拿不到（peer 尚未建立而我们又错过了重试窗口），那时按标题枚举**聊胜于无**。
     * 两条路径都失败时仍按原设计**静默降级** —— 窗口照常显示，只是没有扩展能力。
     *
     * @return HWND 的数值形式；任何一步不成立都返回 `null`（**不抛异常**）。
     */
    fun hwndOf(window: java.awt.Window): Long? {
        if (!available) return null
        // 没有 peer 时 `getWindowPointer` 会返回 null 或 0；先判 `isDisplayable`
        // 是为了**避免**在 peer 缺失时进 native —— 那会走一条更慢的失败路径。
        if (!window.isDisplayable) return null
        val pointer = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return null
        if (Pointer.nativeValue(pointer) == 0L) return null
        val handle = WinDef.HWND(pointer)
        val api = user32 ?: return null
        // 句柄可能已随 peer 更替而失效 —— 校验通过才交出去。
        return if (runCatching { api.IsWindow(handle) }.getOrDefault(false)) {
            Pointer.nativeValue(pointer)
        } else {
            null
        }
    }

    /**
     * 按**精确标题**查找顶层窗口的 HWND；找不到返回 `null`。
     *
     * ⚠️ **2026-09-16 起本函数降为兜底路径**，首选是 [hwndOf]（从 AWT `Window`
     * 直接取句柄，见其 KDoc 的完整理由）。本函数保留的原因：`getWindowPointer`
     * 在 peer 未建立时取不到，此时按标题枚举**聊胜于无**。
     *
     * ## 三级策略与降级路径（这是本文件最不确定的一处，必须写清）
     *
     * 1. **`FindWindowW(null, title)`** —— 精确匹配、O(1) 量级。
     *    局限：标题必须**完全相等**（含不可见字符差异都会失败），且若存在**多个**
     *    同名窗口，返回哪一个由系统决定。
     * 2. **`EnumWindows` 回调遍历** —— 首位失败时逐个看标题，配合
     *    [IsWindowVisible] 跳过隐藏窗（Swing 的 `JWindow` 在某些时序下存在一个
     *    不可见的「代理」顶层窗；不筛掉会拿到错误句柄），并挑选**第一个匹配**。
     *    局限：标题相同仍有歧义；且 Swing 的 `Frame` 实际 HWND 标题可能被
     *    `JLayeredPane` 等中间容器的行为影响。
     * 3. **返回 `null` 并静默降级** —— 调用方（`IslandWindow`）拿到 `null` 时必须
     *    **照常显示窗口**，只是失去穿透/置顶/工具窗这三项扩展能力；
     *    Swing 自带的 `setAlwaysOnTop` 仍可作为置顶的退路。
     *
     * ## 为什么不用 `GetForegroundWindow` 作为主路径
     *
     * 题目里提到可用它，但它的语义是「**当前**前台窗口」——在插件里几乎总是错的：
     * 词岛创建后若用户切走了别的应用（或宿主在后面抢了焦点），前台窗口就不是我们了。
     * 故**只把它作为最后的候选**（见 [findHwndByTitle] 的 fallback 分支），
     * 并且在采纳前**校验它的标题与我们期望的一致** —— 否则宁可返回 `null` 降级，
     * 也绝不拿一个别的窗口的 HWND 去改样式（那会污染宿主的窗口！）。
     */
    fun findHwndByTitle(title: String): Long? {
        if (title.isEmpty()) return null
        val api = user32 ?: return null
        return runCatching {
            // ① 精确查找。
            val direct = api.FindWindowW(null, com.sun.jna.WString(title))
            // ⚠️ `FindWindowW` 找不到窗口时返回的是 **NULL HWND**，而 JNA 会把
            //    `WinDef.HWND` 的 NULL 映射成 **Java `null`**（不是"指针为 0 的对象"）。
            //    故 `direct.pointer` 会抛 NPE —— 且这个 NPE 被下面的 `runCatching`
            //    吞成"降级"，于是 `findHwndByTitle` **永远返回 null**，
            //    穿透/置顶/工具窗三项扩展能力**全部静默失效**。
            //    真机日志铁证（super-island.log 第 3/5/154/155 行）：
            //      Cannot invoke "WinDef$HWND.getPointer()" because "direct" is null
            //    危害之所以极大，是因为**功能照常显示**：窗口画得出来、歌词正常滚动，
            //    只有"鼠标进不来"这一个后果 —— 而它看起来像"功能没做"，
            //    不像"取句柄失败"。故必须显式判 null，走枚举兜底。
            direct ?: return@runCatching findByEnumeration(api, title)
            val directHandle = Pointer.nativeValue(direct.pointer)
            if (directHandle != 0L) return@runCatching directHandle

            // ② 枚举遍历（带可见性筛选）。FindWindowW 返回了非 null 但指针为 0 的
            //    极端情形也一并落到这里。
            findByEnumeration(api, title)
        }.onFailure {
            System.err.println("[CDL] 按标题查找窗口失败（降级为无扩展能力）：$it")
        }.getOrNull()
    }

    /**
     * `EnumWindows` 回调遍历找同名顶层窗口；找不到返回 `null`。
     *
     * 单独抽出来是因为它有**两个**调用者（`FindWindowW` 返回 null / 返回空句柄），
     * 而且它自己带可见性筛选 —— 这段逻辑重复两遍必然会漂移。
     */
    private fun findByEnumeration(api: User32, title: String): Long? {
        var found: Long = 0L
        val proc = object : EnumWindowsProc {
            override fun callback(hWnd: WinDef.HWND, lParam: Pointer?): Boolean {
                if (found != 0L) return false
                val value = Pointer.nativeValue(hWnd.pointer)
                if (value == 0L) return true
                // 跳过不可见窗口：Swing 可能残留同名隐藏窗，取到它会样式改错对象。
                if (!api.IsWindowVisible(hWnd)) return true
                val buffer = CharArray(512)
                val length = api.GetWindowTextW(hWnd, buffer, buffer.size)
                if (length > 0 && String(buffer, 0, length) == title) {
                    found = value
                    return false // 命中即停，避免无谓遍历。
                }
                return true
            }
        }
        api.EnumWindows(proc, null)
        if (found != 0L) return found

        // ③ 最后兜底：前台窗口 + **标题校验**。校验不通过宁可失败，
        //    绝不对一个不属于我们的 HWND 改样式（会污染宿主）。
        val foreground = api.GetForegroundWindow() ?: return null
        val foregroundValue = Pointer.nativeValue(foreground.pointer)
        if (foregroundValue == 0L) return null
        val buffer = CharArray(512)
        val length = api.GetWindowTextW(foreground, buffer, buffer.size)
        return if (length > 0 && String(buffer, 0, length) == title) foregroundValue else null
    }

    /**
     * 统一的 HWND 前置校验 —— 把「句柄无效 / User32 不可用」收敛成一次判断，
     * 避免在每个 setter 里重复写三行守卫。
     *
     * `hwnd == 0L` 是无效句柄的哨兵（Win32 里 `NULL` HWND 即 0），
     * 传入 0 会让 `GetWindowLongW` 失败并返回 0，进而可能被误当成「当前样式为 0」
     * 而把整个样式段清空 —— 这是**必须前置挡住**的破坏性路径。
     */
    private inline fun withHwnd(hwnd: Long, block: (WinDef.HWND) -> Boolean): Boolean {
        val api = user32 ?: return false
        if (hwnd == 0L) return false
        return runCatching { block(WinDef.HWND(Pointer.createConstant(hwnd))) }
            .onFailure { System.err.println("[CDL] 窗口扩展调用失败（已降级）：$it") }
            .getOrDefault(false)
    }
}

/**
 * 把**逻辑** bounds 按缩放换算成**物理像素** bounds（BUG-144 的核心换算）。
 *
 * ## 为什么是**顶层 public 纯函数**
 *
 * `WindowInterop` 是 `internal object`、`physicalBoundsOf` 是 `internal fun`
 * ⇒ **探针（独立编译单元）看不到**（本项目已知限制，见 `ColorPickerProbe` 里
 * 「探针看不到 internal 符号」那段 KDoc）。
 * 于是把"逻辑 × 缩放 = 物理"这条**唯一换算**抽到顶层 —— 与
 * `spectrumBarCornerRatio` / `borderFadeLayers` **完全同一形态**（换算只此一处，且可被断言）。
 *
 * ## 契约（每条都有探针）
 *
 * - ★ **`scaleX`/`scaleY` 均为 1 ⇒ 返回值与入参逐值相同**（零回归的铁证）。
 * - ★ 入参缩放**非法**（`NaN` / `0` / 负 / 非有限）⇒ 返回**原逻辑值** ——
 *   绝不造出零尺寸屏幕（那会让"全屏判定"恒假 ⇒ 隐藏功能永久失效 = 静默失效）。
 * - ★ **不抛**。
 *
 * @param scaleX 水平缩放（`GraphicsConfiguration.getDefaultTransform().scaleX`）。
 * @param scaleY 垂直缩放。
 */
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
