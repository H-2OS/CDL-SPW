// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.settings.SettingsRuntime
import java.awt.AWTException
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

// ═══════════════════════════════════════════════════════════════════════════
//  托盘图标的**几何与尺寸契约**（★ 2026-10-08 · U10）
//
//  ## 为什么这些是【文件顶层】函数而不是 [IslandTray] 的伴生成员
//
//  ★ **`IslandTray` 是 `internal`、它的 `companion object` 是 `private`**，
//    而 `probe` 是**独立编译单元** —— 它看不到 `internal` / `private`
//    （本项目已多次踩过，见 `ColorPickerProbe` 里"够不到 `internal`"那一段说明 /
//    `IslandPolicy` 的 `effectiveTranslationGapPx` 同样提到顶层）。
//
//  ★ 为什么不是"把类改成 public"：那会把整个托盘实现（含 `install` / `close` /
//    事件监听）一并暴露成公开 API —— 为了几条断言放宽**整个类**的可见性，
//    代价远大于收益，且会让"公开面"与"实际约定"脱节。
//    这些函数本来就**只依赖入参**（纯函数），提到顶层是它们的**自然位置**。
//
//  ★ 这与 `physicalBounds` / `shouldHoldTempTopmost` / `islandScreenRect` 是
//    **同一条既有做法**：把"必须离线验证"的那一层抽成顶层纯函数，
//    Swing / Win32 相关的那一层留在类里。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 图标边长的**兜底**值（像素）—— 仅在取不到系统托盘尺寸时使用。
 *
 * ★ **2026-10-08（U10）**：这里**曾经**是 [IslandTray] 里的 `ICON_SIZE = 32`，
 * 被当作"Windows 托盘的标准尺寸"写死。**实际是错的**：
 *
 * 1. Windows 通知区域（托盘）在 **100% 缩放**下一个图标只有 **16×16 逻辑像素**，
 *    32 是"大图标模式"或部分 Linux 桌面（如 GNOME）的尺寸；
 * 2. Hi-DPI（125%/150%/200%）下，系统按**缩放后的逻辑尺寸**索取位图 ——
 *    写死 32 意味着在 200% 下我们交的是 32 px 图、系统要放成 32 逻辑 px（=64 物理 px），
 *    于是**被系统双向拉大**，这正是用户看到的"糊"。
 *
 * 正确来源是 [trayIconSizePx]。
 */
public const val FALLBACK_ICON_SIZE: Int = 32

/**
 * 图标边长下限（像素）。
 *
 * 取 **16**：低于 16 时胶囊的描边与字形都会退化成一两个像素的糊团。
 * 16 恰好是 Windows 100% 缩放下托盘的标准尺寸 ⇒ 它是"最小的可用值"，
 * 而不是随便挑的。
 */
public const val MIN_ICON_SIZE: Int = 16

/**
 * 托盘图标边长上限（px）—— ★ 防异常环境给出荒谬值（如某些虚拟屏报超大 DPI）。
 *
 * ★ 取 64：★ 已覆盖 400% 缩放下的 16 px 逻辑尺寸（16 × 4 = 64）；
 * 再大对托盘图标毫无收益，只会浪费内存与绘制时间 ✓
 */
public const val MAX_ICON_SIZE: Int = 64

/**
 * **托盘图标的超采样倍数**（★ 2026-10-10 · U11）。
 *
 * ## 为什么需要（用户报障，逐字）
 *
 * > 「改的托盘图标**像素感太强**了，不清晰不好看。」
 *
 * ★ 真因：★ 图标此前**按系统托盘尺寸（Windows 常见 16 px）直接渲染** ——
 * 而 16 px 下画"字形 + 1 px 描边"，**抗锯齿能做的非常有限** ⇒ 边缘必然发糊/有台阶；
 * ★ 若系统有 DPI 缩放（125% / 150%），16 px 图标还会被**放大** ⇒ ★ **更糊** ✗
 *
 * ## 超采样怎么解决
 *
 * ★ 先按 `目标尺寸 × 本倍数` 渲染，再**缩回**目标尺寸。
 * ★★ 关键：**缩小的抗锯齿质量远好于放大**（这是所有图标渲染的通用做法）——
 * 边缘像素是"多个高分辨率采样点的平均"，而放大只能"插值猜" ✓
 *
 * ★ 取 2：★ 收益最大的一档（2× ⇒ 4 个采样点/像素），
 * 而 3× / 4× 的观感提升已不明显、内存与时间翻倍 ⇒ ★ 性价比拐点 ✓
 *
 * ⚠️ ★ **必须 `public`** —— ★ 探针是独立编译模块，**看不到 `internal`** ✓
 */
public const val TRAY_SUPERSAMPLE: Int = 2

/**
 * 描边宽度占图标边长的比例。
 *
 * ★ **2026-10-10（U10 终版）**：★ 本常量现在用于**字形自己的描边**
 * （▶ 三角的边 / ⏸ 双竖条的边）—— ★ 用户拍板「**沿形状加描边**」✓
 * ★ 它**不再**用于胶囊外框（那条路已删）✓
 *
 * 取 **0.0625**（= 1/16）：在 16 px 图标上是 **1 px**、32 px 上是 2 px ——
 * 恰好是"能看清但不吞掉字形"的一档。★ 必须是**比例**而不是固定像素：
 * 固定 2 px 在 16 px 图标上会占掉 1/8 的宽度，字形看起来像实心块。
 */
public const val OUTLINE_STROKE_RATIO: Float = 0.02f
// ★★★ 2026-10-10（**U11-d**）：`0.0625 → 0.02`（用户选定「A3」）。
//
// ## 为什么（★ 用户报障 + 出图取证）
//
// > 「改的托盘图标**像素感太强**了，不清晰不好看。」
//
// ★ 出图对照（`TraySheet` ⇒ `_tray_icon_preview/sheet_u11.png`，16/20/24/32 × ▶/⏸）
//   一眼看出：★ **16 px 下 `0.0625` 的描边糊成一圈灰边**，把本来就细的字形裹住 ✗
// ★ 原因：★ `0.0625` 是按 **32 px 设计稿**定的（32 × 0.0625 = 2 px），
//   而 **Windows 托盘真实尺寸是 16 px** ⇒ ★ 描边只有 1 px，
//   在抗锯齿下与字形**混成一团灰** ✓
//
// ★ `0.02` = 出图里 A3 档的实测最优值（16 px ⇒ ≈ 0.32 px 描边）：
//   ★ 仍保留**一丝边界**（浅色任务栏上白字形不至于完全看不见），
//   但**不再糊成灰圈** ✓
//
// ⚠️ ★ **必须 `public`**（探针断言它）✓

/**
 * 当前系统托盘的图标边长（**逻辑像素**，已含 Hi-DPI 缩放）。
 *
 * ★ **2026-10-08（U10）** 新增。为什么不是写死 32：
 *
 * - `SystemTray.getSystemTray().trayIconSize` 是 JDK 提供的**权威值**
 *   （Windows 上取自 `GetSystemMetrics(SM_CXSMICON)` 并按缩放换算）；
 * - 交付一张与它**同尺寸**的位图 ⇒ 系统不必再缩放 ⇒ **不再糊**；
 * - 这也是"适当放大图标"（用户原话）的正确实现：不是把 32 改成 48，
 *   而是**跟随系统**。在 150% 缩放下它会自然变成 24 逻辑 px（=36 物理 px）；
 * - `getSystemTray()` 在平台不支持托盘时抛 `HeadlessException` /
 *   `UnsupportedOperationException`，故整段包在 `runCatching` 里兜底。
 *
 * ⚠️ 返回的尺寸每次调用都重新问系统（不缓存）：多显示器/缩放变更后
 * 系统值会变，而托盘图标在播放态变化时重建 —— 每次都取最新值，
 * 天然跟得上。缓存反而会引入"改了缩放要重启"的问题。
 *
 * 下限钳到 [MIN_ICON_SIZE]：某些桌面环境（如无托盘支持的会话）会返回 0 或负数。
 */
public fun trayIconSizePx(): Int {
    val logical = runCatching { SystemTray.getSystemTray().trayIconSize.width }
        .getOrNull()
        ?.takeIf { it > 0 }
        ?: FALLBACK_ICON_SIZE
    // ★★★ 2026-10-11（**U11-e**）：按 **逻辑尺寸 × 屏幕缩放 = 物理像素** 渲染。
    //
    // ## 为什么（★ 用户报障，逐字）
    //
    // > 「改的托盘图标**像素感太强**了，不清晰不好看。」
    // > （★ 加粗字形 / 减细描边 / 超采样都试过，**仍嫌糊**）
    //
    // ## 真因：**给的是逻辑像素，而系统要的是物理像素**
    //
    // ★ `SystemTray.getTrayIconSize()` 返回的是**逻辑**尺寸（Windows 常见 **16**），
    //   而托盘实际占用的**物理**像素 = `逻辑 × 屏幕缩放`（125% ⇒ 20 · 150% ⇒ 24 · 200% ⇒ 32）。
    // ★★ 若只按逻辑尺寸渲染 ⇒ ★ **系统必须【放大】这张图** ⇒
    //   ★★★ **放大必然糊 + 像素感**（无论字形多粗、描边多细、是否超采样）✗
    // ★ 反过来：按**物理**尺寸渲染 ⇒ 系统**缩小或原样用** ⇒ **清晰的抗锯齿** ✓
    //
    // ⚠️ ★ 本函数**不假设任何特定缩放值 / 特定平台**：
    //   · 缩放从 `GraphicsEnvironment` 的 `defaultTransform` **读**（不是写死 1.5）；
    //   · 取不到时走**回退链**（`Toolkit.screenResolution/96` → `1.0`）；
    //   · ★ **多屏**：取 `max(scaleX, scaleY)`，且**向上取整**（宁大勿小 ——
    //     大了系统会缩，小了只能放大，而放大就是本缺陷）；
    //   · ★ **上限** [MAX_ICON_SIZE] 防异常环境给出荒谬值。
    //
    // ⚠️ ★ **必须 `public`**（探针断言它）✓
    val scale = screenScale()
    val physical = kotlin.math.ceil(logical * scale).toInt()
    return physical.coerceIn(MIN_ICON_SIZE, MAX_ICON_SIZE)
}

/**
 * 当前屏幕的**缩放系数**（1.0 = 100%）—— ★ **跨平台、多屏、带回退链**。
 *
 * 回退链（★ 前者取不到才用后者）：
 *  1. `GraphicsEnvironment.defaultScreenDevice.defaultConfiguration.defaultTransform`
 *     —— ★ 最准（JRE 与 OS 的 DPI 感知一致时）；
 *  2. `Toolkit.getScreenResolution() / 96.0` —— ★ 老接口，某些环境下唯一可用；
 *  3. ★ `1.0`（**不缩放**）—— ★ 宁可不缩放，也不要猜错。
 *
 * ⚠️ 取 `max(scaleX, scaleY)`：非等比缩放（罕见但存在）下**宁大勿小** ✓
 */
private fun screenScale(): Double {
    val fromTransform = runCatching {
        val tr = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform
        maxOf(tr.scaleX, tr.scaleY)
    }.getOrNull()
    if (fromTransform != null && fromTransform > 0.0) return fromTransform

    val fromToolkit = runCatching {
        java.awt.Toolkit.getDefaultToolkit().screenResolution / 96.0
    }.getOrNull()
    if (fromToolkit != null && fromToolkit > 0.0) return fromToolkit

    return 1.0
}

/**
 * 胶囊轮廓的几何（**相对图标边长的比例**）。
 *
 * ★ **2026-10-08（U10）** 抽出：把"画在哪"与"画什么"分开 ⇒
 * 几何**可被探针逐项断言**（见 `TrayIconProbe`），而绘制函数保持私有。
 *
 * 命名后各字段含义：`left/top` 是**左上角**，`width/height` 是**尺寸**，
 * `arcW/arcH` 是圆角半径 —— 与 `Graphics2D.drawRoundRect` 的入参**同序同义**，
 * 调用点只会是纯透传，不会再出现"哪个数是宽哪个是高"的歧义。
 *
 * ★ 原设计稿基准是 32 px 图标，各比例即"设计稿像素 / 32"：
 * `x=2, y=5, w=27, h=21, arc=12` ⇒ `0.0625 / 0.15625 / 0.84375 / 0.65625 / 0.375`。
 */
public data class PillOutline(
    val leftRatio: Float,
    val topRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val arcRatio: Float,
)

/**
 * 胶囊轮廓比例（**不含**描边内缩，内缩由 [pillOutlineFor] 施加）。
 *
 * 与旧实现的关系（★ 逐项对应，便于核对没有改设计）：
 * 旧 `fillRoundRect(2, 5, ICON_SIZE - 5, ICON_SIZE - 11, 12, 12)` 在 `ICON_SIZE = 32` 时
 * 就是 `(2, 5, 27, 21, 12, 12)` ⇒ 下面这组数字**是它除以 32**，不是新设计。
 */
public val PILL_OUTLINE_RATIO: PillOutline = PillOutline(
    leftRatio = 2f / 32f,
    topRatio = 5f / 32f,
    widthRatio = 27f / 32f,
    heightRatio = 21f / 32f,
    arcRatio = 12f / 32f,
)

/**
 * 按实际图标边长算出**可直接交给 `drawRoundRect` 的整型矩形**。
 *
 * ## ⚠️ ★★★ 2026-10-10（**U10 返工**）：★ **本函数已【不再用于绘制图标】**
 *
 * ★ 用户报「**还是包裹样式**」⇒ ★ 那个"包裹"就是**胶囊外框** ⇒ 已从
 *   [IslandTray.createTrayIconImage] 里**移除**（改为"字形自己带深灰描边"）✗
 *
 * ★★ **保留本函数 + [PILL_OUTLINE_RATIO] + [PillOutline] 是【有意的】**，理由两条：
 * 1. ★ `TrayIconProbe` 的 **A/B/C 对照图**要用它画"**改前的样子**"（A 候选）——
 *    ★ 那是返工决策的**证据**，删了就再也出不了那张对照图 ✓
 * 2. ★ 它记录了**原设计稿**（`2, 5, 27×21, arc 12 @32`）—— ★ 若将来要做
 *    "可选外框"档，判据已就绪 ✓
 *
 * ⚠️ **登记为「待评估删除」**：★ 若确认不再需要 A/B/C 对照（返工已验收），
 * 本函数与 [PILL_OUTLINE_RATIO] / [PillOutline] 应**一并删除**（本项目不容死代码）✓
 *
 * ★ **`inset` 的来历（这条最容易写错）**：`drawRoundRect` 的描边**以路径为中线**
 * 向内外各扩 `stroke/2`。若直接用原比例坐标，描边外沿会超出图像边界被裁切 ——
 * 表现为胶囊上下左右"缺一块"。故四周各内缩 `stroke/2`（= `inset`），
 * 让**描边外沿**恰好落在设计稿的轮廓线上。
 *
 * @param sizePx 图标边长（像素），应取 [trayIconSizePx]。
 * @return `intArrayOf(x, y, w, h, arcW, arcH)` —— 与 `drawRoundRect` 入参同序。
 *         `w`/`h` 已扣掉 2×inset，故下限钳到 1（极端小尺寸下不产生 0 或负数）。
 */
public fun pillOutlineFor(sizePx: Int): IntArray {
    val stroke = (sizePx * OUTLINE_STROKE_RATIO).coerceAtLeast(1f)
    val inset = stroke / 2f
    val x = sizePx * PILL_OUTLINE_RATIO.leftRatio + inset
    val y = sizePx * PILL_OUTLINE_RATIO.topRatio + inset
    val w = sizePx * PILL_OUTLINE_RATIO.widthRatio - stroke
    val h = sizePx * PILL_OUTLINE_RATIO.heightRatio - stroke
    val arc = sizePx * PILL_OUTLINE_RATIO.arcRatio
    return intArrayOf(
        x.toInt(), y.toInt(),
        w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1),
        arc.toInt(), arc.toInt(),
    )
}

/**
 * 状态字形的几何（比例 ⇒ 绝对像素）。
 *
 * ★ **2026-10-08（U10）** 抽出，理由同 [pillOutlineFor]：可断言。
 *
 * ## 返回值契约（★ 两种状态**编码不同**，调用点必须按状态取用）
 *
 * - `playing = true`  ⇒ `first`/`second` = `Polygon` 的 `xs` / `ys`（**各 3 个点**）；
 * - `playing = false` ⇒ `first`/`second` = 两根竖条各自的 `(x, y, w, h, arc)`
 *   （**各 5 个数**，可直接喂 `fillRoundRect` 的前三个 + 圆角）。
 *
 * ⚠️ 之所以不是"统一成多边形"：竖条用 `fillRoundRect` 有**圆角**，
 * 而 `Polygon` 只能画直角 —— 统一会改变观感。两种编码的**区别必须写在契约里**，
 * 否则调用点会用错（这是"返回值形状随分支变"这种设计的老问题，故显式声明）。
 *
 * ⚠️ **三角形刻意左移 `0.5/32`**：设计稿的 `(12,9)-(12,23)-(23,16)` 视觉重心
 * 偏右（底边 14 × 高 11，但胶囊中线在 x=15.5 而三角中线在 x=17.5）⇒
 * 在胶囊里看起来"贴着右边"。左移半个设计稿像素后与胶囊同心。
 * 这是**既有问题的一并修正**，不是本次新引入的偏移。
 */
public fun glyphPolygonsFor(sizePx: Int, playing: Boolean): Pair<IntArray, IntArray> {
    val unit = sizePx / 32f
    return if (playing) {
        // ★★★ 2026-10-10（**U11-d**，用户选定「A3」）：**三角放大**
        //   设计稿 `(12,9)-(12,23)-(23,16)` ⇒ **`(11,8)-(11,24)-(24,16)`**（宽 11→13、高 14→16）。
        //   ★ 理由：★ 16 px 下原三角只有 **5.5 × 7 px** ⇒ 抗锯齿一糊就"像素感强" ✗
        //   ★ 同时**去掉原先的 `dx = -0.5` 左移** —— 那个修正的前提是"要与**胶囊**同心"，
        //     而★ 胶囊**已不再绘制**（U10 终版）⇒ 左移失去依据 ✓
        //   ★ 取证：`_tray_icon_preview/sheet_u11.png` 的 A3 列 ✓
        val x12 = (11f * unit).toInt()
        val x23 = (24f * unit).toInt()
        Pair(
            intArrayOf(x12, x12, x23),
            intArrayOf((8f * unit).toInt(), (24f * unit).toInt(), (16f * unit).toInt()),
        )
    } else {
        // 双竖条：★ 2026-10-10（U11-d）**加粗 `4 → 6`**，`x = 11/18 → 9/18`，y = 9。
        //   ★ 理由：★ 16 px 下原竖条只有 **2 px 宽**（`4 × 0.5`）⇒ 加 1 px 描边后必然糊 ✗
        //   ★ 现在 16 px 下是 **3 px 宽**，两根间距 **1.5 px** ⇒ 既够粗、又不粘连 ✓
        //   ★ 编码成 "(x, y, w, h, arc)" ⇒ 探针可逐项核对。
        val w = (6f * unit).toInt().coerceAtLeast(2)
        val h = (14f * unit).toInt()
        val arc = (2f * unit).toInt()
        Pair(
            intArrayOf((9f * unit).toInt(), (9f * unit).toInt(), w, h, arc),
            intArrayOf((18f * unit).toInt(), (9f * unit).toInt(), w, h, arc),
        )
    }
}

/**
 * 系统托盘图标 —— 词岛的**兜底入口**。
 *
 * ## 为什么必须有它（这不是"多个图标"那么简单）
 *
 * 词岛是**无边框 + 可选鼠标穿透 + 不进 Alt+Tab** 的悬浮件。这意味着：
 *
 * - 用户把它拖到屏幕外 → **没有标题栏可以拖回来**；
 * - 用户开启了鼠标穿透 → **鼠标点不到它**，连右键菜单都呼不出；
 * - 用户把它关掉（`island.enabled = false`）→ **没有任何界面能再打开它**。
 *
 * 三种情形都会让词岛变成"看得见但摸不着"或"彻底消失"的状态。
 * **托盘图标是唯一始终可达的入口** —— 它不依赖词岛自身的可见性与可交互性。
 *
 * 原实现同样这么做（`ui/IslandMenu.kt:22-44` 的 `installTray()`），
 * 并且额外提供了「找回词岛」按钮（我们已补齐，见 `BUG-与修复记录.md` BUG-008）——
 * 两者是同一需求的两种入口，不重复：托盘图标随时可达，设置页按钮在托盘被占用时兜底。
 *
 * ## 为什么用 `java.awt.SystemTray` 而不是 JNA `Shell_NotifyIcon`
 *
 * `SystemTray` 是 **JDK 内置**能力，跨平台且无需 JNA 声明与消息循环。
 * 原实现也选它（`IslandMenu.kt:23` `SystemTray.isSupported()`）。
 * 用 JNA 直调 `Shell_NotifyIcon` 要自己处理窗口消息回调，收益为零、风险更高。
 *
 * ## 为什么图标是**代码手绘**而不是图片资源
 *
 * 两条理由，第二条是硬约束：
 * ① 原实现就是这么做的（`IslandMenu.kt:24-30`：`BufferedImage(32,32,TYPE_INT_ARGB)` + `fillRoundRect`）；
 * ② **本项目纪律：`src/main/resources/` 不放二进制资源** ——
 *    打包脚本对资源有许可审查要求（见 `THIRD_PARTY_NOTICES.md`），
 *    一个手绘图标既避免了"这图哪来的"的许可追问，也让图标能**跟随主题色**变化。
 *
 * ## 生命周期
 *
 * [install] 在窗口启动后调用；[close] **必须**调用 —— 否则插件卸载后
 * 托盘里会残留一个幽灵图标（Windows 上要鼠标划过才消失，看起来像崩溃残留）。
 *
 * @param settings 设置运行时。为 `null` 时图标仍会安装，但点击无效（降级）。
 * @param window 词岛窗口，用于构造右键菜单。**可以为 `null`** ——
 *   托盘是兜底入口，它存在的意义正是"窗口不可用时还能操作"；
 *   若把窗口作为安装前提，窗口一旦创建失败（或用户此前关掉了词岛），
 *   托盘就会跟着消失，**兜底入口反而先于被兜底者失效**。
 *   窗口为 `null` 时菜单里需要窗口的动作（显隐联动）只改设置，不再联动窗口。
 */
internal class IslandTray(    private val settings: SettingsRuntime?,
    private val window: IslandWindow?,
    /**
     * 播放控制入口 —— 左键"暂停 / 继续"用它。默认 [PlaybackActions.NONE]（点了没反应）。
     *
     * 为什么默认值而不是必填：与 `IslandWindow` 同一理由（见其构造参数的 KDoc）——
     * 组合根在"宿主 API 不可用"时会走降级路径，那时托盘**仍应安装**
     * （它正是"窗口不可用时的兜底入口"），只是左键点了不生效。
     */
    private val playbackActions: PlaybackActions = PlaybackActions.NONE,
) : AutoCloseable {

    /** 已安装的托盘图标。`null` = 未安装（平台不支持 / 安装失败 / 已卸载）。 */
    @Volatile
    private var tray: TrayIcon? = null

    /**
     * 最近一次已知的播放态。**由 [onPlaybackState] 从宿主回调侧写入**（见其 KDoc）。
     *
     * `@Volatile`：写入在宿主回调线程，读取在 EDT（左键点击），跨线程必须可见。
     */
    @Volatile
    private var playing: Boolean = false

    /**
     * **挂起的单击动作**（见 [onTrayClick] 的 KDoc）。
     *
     * 单击必须**延后**到双击间隔之后才能执行 —— 否则双击会先触发一次"暂停"再切歌。
     * 这个定时器就是"等一等，看用户还要不要点第二下"。
     * 值为 `null` = 当前没有挂起的单击。
     *
     * 只在 EDT 上读写（`mouseClicked` 与 `Timer` 回调都在 EDT），故不需要同步。
     */
    private var pendingSingleClick: javax.swing.Timer? = null

    /**
     * 双击判定间隔（毫秒），**取自系统设置**而不是写死。
     *
     * ## 为什么必须读系统值
     *
     * 用户在 Windows 里调过双击速度时，这个值会变。若写死 500 ms：
     * 用户把双击调快（如 200 ms）后，两次快点之间的间隔短于 500 ms 但**长于**他习惯的
     * 200 ms —— 那在系统看来是**两次单击**，而我们会当成双击。
     * 反向也一样：写死值太小会把用户的正常双击拆成"暂停 + 暂停"。
     *
     * `awt.multiClickInterval` 是 AWT 从系统读来的桌面属性（Windows 上有），
     * 取不到时退回 [FALLBACK_DOUBLE_CLICK_MS]。
     *
     * 用 `by lazy`：这个属性要问一次 Toolkit，而托盘可能装不上（那时根本不需要它）。
     */
    private val doubleClickIntervalMs: Int by lazy {
        runCatching {
            (java.awt.Toolkit.getDefaultToolkit()
                .getDesktopProperty("awt.multiClickInterval") as? Int)
                ?.takeIf { it > 0 }
        }.getOrNull() ?: FALLBACK_DOUBLE_CLICK_MS
    }

    /** 安装是否成功（供组合根决定要不要打日志）。 */
    val isInstalled: Boolean get() = tray != null

    /**
     * 安装托盘图标。**幂等** —— 重复调用不会装出两个图标。
     *
     * ## 契约：不抛异常
     *
     * 托盘是**增强入口**，装不上不该影响词岛本体。三类失败都降级为日志：
     * - `SystemTray.isSupported() == false`（极少数桌面环境）；
     * - `AWTException`（托盘被系统或别的程序占满 —— Windows 上有数量上限）；
     * - `HeadlessException`（无图形环境，理论上不会出现在这里）。
     *
     * ## 为什么左键是"播放控制"而不是"切换显示"
     *
     * **原记**（本方法早期 KDoc）：「左键 = 主要动作（这里是显隐词岛，最常用），
     * 右键 = 菜单」，对齐原实现 `IslandMenu.kt:33`。
     *
     * **实为**：用户 2026-09-16 明确要求
     * 「托盘图标左键**单击**时可以暂停/继续，**双击**时可以下一首，
     * 同时图标换成播放状态对应的暂停和播放中图标」。
     * → 左键改为**两档播放控制**（见 [onTrayClick]），图标随播放态切换。
     * 显隐词岛仍可从右键菜单的「显示词岛」操作，**能力没有丢**。
     */
    fun install() {
        if (tray != null) return
        runCatching {
            if (!SystemTray.isSupported()) {
                System.err.println("[CDL] 当前环境不支持系统托盘，跳过托盘图标")
                return
            }
            // 图标带上当前播放态：托盘可能在播放中安装（`start()` 时曲目已在播）。
            val icon = createTrayIconImage(playing)
            val created = TrayIcon(icon, tooltipFor(playing)).apply {
                // 自动调整尺寸：不同 DPI 下 Windows 会缩放图标，
                // 不设这个标志时手绘的 32×32 在某些缩放下会显示成裁剪过的。
                isImageAutoSize = true
                installListeners(this)
            }
            SystemTray.getSystemTray().add(created)
            tray = created
            System.err.println("[CDL] 托盘图标已安装（当前播放态：$playing）")
        }.onFailure {
            // 用 `onFailure` 而非 `catch`：`runCatching` 会把所有 Throwable 都收进来，
            // 包括 AWTException / HeadlessException —— 它们都该降级而非外抛。
            System.err.println("[CDL] 托盘图标安装失败（词岛本体不受影响）：$it")
        }
    }

    /**
     * 播放态变化时更新图标与悬浮提示。**由组合根从宿主回调侧调用**
     * （`CdlRuntime.onPlaybackCallback` 的 `StructuredEvent.Playing` 分支）。
     *
     * ## 为什么不自己轮询
     *
     * 宿主**本来就会推** `Playing(playing)` 事件（真机日志里的 `callback playing: true`
     * 就是它）。挂上去是**零新增开销**的事件驱动；自己起一个定时器轮询则是
     * "每秒醒来几次问一件别人已经会告诉你的事"—— 而托盘图标是**长期存在**的，
     * 那个定时器会跟着插件活整个生命周期。
     *
     * ## 为什么先比再改
     *
     * `Playing` 事件在**每次状态变化**时都推，而状态机可能在一首歌里推很多次
     * （缓冲、seek 都会经过 playing 状态）。每次重建 `BufferedImage` 是浪费，
     * 且 `setImage` 会让 Windows 重绘托盘 —— 无变化时不该做。
     *
     * 可从**任意线程**调用（宿主回调线程）；`TrayIcon.setImage` 本身线程安全。
     */
    fun onPlaybackState(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        val current = tray ?: return
        runCatching {
            current.image = createTrayIconImage(playing)
            current.toolTip = tooltipFor(playing)
        }.onFailure {
            // 图标更新失败不该影响播放 —— 记一行即可（托盘仍是可用入口）。
            System.err.println("[CDL] 更新托盘图标失败：$it")
        }
    }

    /**
     * 左键动作：**播放 / 暂停切换**。
     *
     * 与 `IslandWindow` 的播放按钮同一个语义（见 `PlaybackActions` 的 KDoc：
     * 接口按"用户意图"而不是"宿主方法名"定义，故这里只表达"用户想切换"）。
     *
     * **为什么用 `playing` 字段而不是现读快照**：托盘拿不到 `PlaybackTimeline`
     * （依赖方向 —— `window/` 不该依赖 `playback/` 的实现），而宿主推的
     * `Playing` 事件已经把状态送到了 [onPlaybackState]。用它就是最新值。
     */
    private fun togglePlayback() {
        runCatching {
            if (playing) playbackActions.pause() else playbackActions.play()
        }.onFailure {
            System.err.println("[CDL] 托盘播放/暂停切换失败：$it")
        }
    }

    /**
     * 装监听器。
     *
     * ## 为什么要同时处理 `mouseReleased` 与 `mousePressed` 的 `isPopupTrigger`
     *
     * **弹出菜单的触发时机是平台相关的**：Windows 在 `mouseReleased` 触发，
     * 而某些平台/某些鼠标驱动在 `mousePressed` 触发。
     * 只在 `mouseReleased` 里判断会在部分环境下"右键没反应"。
     * 故两处都判断 `isPopupTrigger`（这个标志由 AWT 按平台语义填好），
     * 并让 [showMenu] 自身幂等 —— 若两处都触发，菜单会被重建，
     * 但 `JPopupMenu.show` 会先关闭已显示的实例，视觉上无异常。
     */
    private fun installListeners(target: TrayIcon) {
        // ⚠️ **不用 `addActionListener`**。它会在一次点击里也派发一个 `ActionEvent`，
        // 与下面的 `mouseClicked` 叠加 → **同一次点击被处理两遍**
        // （单击变成"暂停 + 下一首"）。托盘左键的语义由 `clickCount` 决定，
        // 必须**只有一个**入口。
        target.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                // 只处理左键。右键走下面的 `isPopupTrigger` 分支（弹菜单）。
                if (event.button == MouseEvent.BUTTON1) onTrayClick(event.clickCount)
            }

            override fun mouseReleased(event: MouseEvent) {
                if (event.isPopupTrigger) showMenu(event)
            }

            override fun mousePressed(event: MouseEvent) {
                if (event.isPopupTrigger) showMenu(event)
            }
        })
    }

    /**
     * 托盘左键的**两档手势**分发（用户 2026-09-16 明确要求）。
     *
     * | 手势 | 动作 |
     * |---|---|
     * | 单击 | **暂停 / 继续** |
     * | 双击 | **下一首** |
     *
     * ## ⚠️ 为什么单击必须**延后**执行（这是本方法唯一的难点）
     *
     * AWT 派发双击时，**先**给一次 `clickCount == 1`、**再**给一次 `clickCount == 2`。
     * 若单击动作立即执行，一次双击就会变成「**先暂停、再切歌**」——
     * 用户想切歌，结果播放状态也被翻了。这是托盘类控件最经典的坑。
     *
     * 解法是标准的"等一等看还有没有第二下"：单击动作挂到
     * [pendingSingleClick] 上，延迟 [doubleClickIntervalMs] 后执行；
     * 若期间来了第二下，**先取消挂起的单击**，再走双击分支。
     *
     * ## 代价（诚实记录）
     *
     * 单击因此有 [doubleClickIntervalMs] 的**可感延迟**（Windows 默认约 500 ms）。
     * 这是"同时支持单击与双击"的**固有代价**，不是实现偷懒 ——
     * 系统必须先区分完手势才能确定该执行哪个动作。
     * 想要"单击零延迟"就只能放弃双击（或把双击换成别的键）。
     *
     * ## 为什么延时间隔取自系统设置而不是写死
     *
     * 用户在 Windows 里调过双击速度时，系统值会变。用写死的 500 ms：
     * 用户把双击调快（如 200 ms）后，两次快点会被误判成"两次单击" →
     * **连切两首歌**。故读 `awt.multiClickInterval` 桌面属性，
     * 取不到时退回 [FALLBACK_DOUBLE_CLICK_MS]。
     *
     * @param clickCount AWT 给的连击计数（1 = 单击，2 = 双击）。
     */
    private fun onTrayClick(clickCount: Int) {
        when {
            // 双击及以上：切下一首。**必须先取消挂起的单击**（见上面的说明）。
            clickCount >= 2 -> {
                pendingSingleClick?.stop()
                pendingSingleClick = null
                skipToNext()
            }

            clickCount == 1 -> {
                // 重入时先停掉上一个（连点三下这类情形），保证只有一个挂起动作。
                pendingSingleClick?.stop()
                pendingSingleClick = javax.swing.Timer(doubleClickIntervalMs) {
                    pendingSingleClick = null
                    togglePlayback()
                }.apply {
                    // 一次性：不重复触发。忘了设会让"单击 = 每秒暂停一次"。
                    isRepeats = false
                    start()
                }
            }
        }
    }

    /** 切下一首。与 [togglePlayback] 同理由：接口按"用户意图"定义。 */
    private fun skipToNext() {
        runCatching { playbackActions.next() }.onFailure {
            System.err.println("[CDL] 托盘切下一首失败：$it")
        }
    }

    // ⚠️ 2026-09-16 删除了 `toggleEnabled()`（左键切换 `island.enabled`）。
    //
    // 左键改为播放/暂停后它没有调用点了。**能力没有丢** ——
    // 右键菜单里仍有「显示词岛」checkbox（`IslandPopupMenu.build` 的第 1 项），
    // 它做的是同一件事（`applySwitch(settings, "island.enabled", …)` + 同步联动窗口）。
    //
    // 保留一个没人调用的私有函数会长期占着"这段代码在干什么"的注意力，
    // 而它的逻辑在菜单那条路径上是重复的 —— 删掉比留着更诚实。
    // 若将来要把它挂回某个手势（例如双击托盘），从菜单那一项抄回即可。

    /**
     * 弹出右键菜单。
     *
     * ## 为什么只是转调 [IslandPopupMenu.showAtScreen]
     *
     * ① 词岛窗口右键与托盘右键**应当是同一个菜单**（原实现也是共用，
     *    见 `IslandMenu.kt:38` 与 `IslandWindow.kt:64,73` 都进 `IslandMenu.showPopup()`）。
     *    两处各写一份必然漂移 —— 加了一项只改一处，用户会发现"托盘里没有那一项"。
     * ② 更要紧的是**弹出路径上的两件公共事务**（套皮肤、装外点关闭钩子）只能有一份实现：
     *    分散在调用点就会变成"词岛那边修好了、托盘那边还是老样子"——
     *    而本次要修的正是这个 bug，绝不能再复制一份出去。
     *    故 `build()` 与 `show()` 之间的所有动作都收进 `IslandPopupMenu.present(...)`，
     *    这里只负责把**事件坐标**递进去当回退值。
     *
     * ## 事件坐标现在只是**回退值**（2026-09-16 修复）
     *
     * **原记**（本方法早期 KDoc）：「这里传的是 `MouseEvent.xOnScreen/yOnScreen`，
     * 而 `TrayIcon` 在 Hi-DPI 下给的是**物理像素**、`JPopupMenu.show` 要的是逻辑像素 ——
     * 125%/150% 缩放时菜单会偏到右下。本版**未改**。」
     *
     * **实为：已改。** [IslandPopupMenu.showAtScreen] 现在与原实现 0.8.9 一致地
     * **丢弃主路径上的入参**，改用 `MouseInfo.getPointerInfo().location`
     * （`089:ui/SwingTrayPopup.kt:27-32`），并自己做边缘翻转 + 钳制。
     * 这里传进去的 `xOnScreen/yOnScreen` 仅在**取不到光标**时兜底
     * （`MouseInfo` 在无头/远程会话下会返回 `null`）。
     * 之所以仍然传、而不是删掉这个参数：真到了取不到光标的情形，
     * 有一个近似值总好过菜单弹在 (0,0)。
     */
    private fun showMenu(event: MouseEvent) {
        IslandPopupMenu.showAtScreen(settings, window, event.xOnScreen, event.yOnScreen)
    }

    /**
     * 卸载托盘图标。**必须在插件停止时调用。**
     *
     * `SystemTray.remove` 之后 Windows 才会真正移除图标；
     * 不调用的话残留图标会一直显示（且鼠标悬停才消失），用户会以为插件崩了。
     */
    override fun close() {
        // ⚠️ **必须放在下面的提前返回之前**。`tray ?: return` 会在"托盘没装上"
        // （平台不支持 / 安装失败）时直接返回 —— 那种情况下挂起的单击定时器
        // 会被漏掉，而它持有一个指向本对象的 lambda：插件停掉后定时器仍活着，
        // 到点还会去调 `playbackActions`（此时宿主 API 已不可用）。
        // 停一个定时器是零成本的，别让它依赖"托盘装上了"这个条件。
        pendingSingleClick?.stop()
        pendingSingleClick = null

        val current = tray ?: return
        tray = null
        runCatching { SystemTray.getSystemTray().remove(current) }
            .onFailure { System.err.println("[CDL] 移除托盘图标失败：$it") }
    }

    private companion object {
        const val TRAY_TOOLTIP = "个性桌面歌词（测试版）"

        /**
         * 取不到系统双击间隔时的兜底值（毫秒）。
         *
         * 取 **500**：Windows 的默认双击速度就是 500 ms（控制面板的滑块中位）。
         * 这个值只在不支持 `awt.multiClickInterval` 的平台上生效 ——
         * 它偏保守（偏大），因为"把双击误判成两次单击"（连切两首歌）比
         * "单击稍慢一点响应"更难接受。
         */
        const val FALLBACK_DOUBLE_CLICK_MS = 500

        /**
         * 手绘托盘图标：**圆角胶囊底 + 播放状态字形**。
         *
         * ## 为什么改成"状态字形"（2026-09-16，用户要求）
         *
         * 用户原话：「托盘图标左键时可以暂停/继续，同时图标换成**播放状态对应的
         * 暂停和播放中图标**」。
         *
         * **原设计**是"胶囊 + 三根歌词行"（抽象词岛本体的三槽位布局）。
         * 现在左键变成了播放控制，图标就必须能**一眼看出当前是播是停** ——
         * 而三根横线表达不了这件事。故字形改为状态指示：
         *
         * | 播放态 | 字形 | 含义 |
         * |---|---|---|
         * | 播放中 | ▶ 三角 | "正在播放" |
         * | 已暂停 | ⏸ 双竖条 | "已暂停" |
         *
         * ## ⚠️ 字形语义是"状态"而不是"动作"（一处刻意的选择）
         *
         * 媒体播放器有两种惯例：**显示状态**（播放中→▶）或**显示动作**
         * （可暂停→⏸）。本实现按**用户原话**取前者（"播放状态对应的…图标"）。
         *
         * 两种惯例的字形恰好**互为对方**，所以用户若觉得"图标像是告诉我点了会怎样"
         * 而想反过来，只需把下面两处的字形互换 —— 逻辑上是把
         * `if (playing)` 的两个分支对调。为消除歧义，
         * [tooltipFor] 的悬浮提示**显式写出了左键会做什么**（"左键暂停" / "左键继续"）。
         *
         * ## 为什么保留胶囊底
         *
         * 胶囊是**品牌标识**（词岛形态的抽象），且它让图标在深浅色托盘背景上
         * 都有稳定对比度 —— 纯字形在浅色任务栏上会糊掉。
         *
         * ## 为什么用 `TYPE_INT_ARGB`
         *
         * 托盘图标需要**透明背景**，否则会显示成一个方块。
         * `TYPE_INT_ARGB` 带 alpha 通道，是唯一正确选择。
         * ★ **U10 后仍然必须保留**：现在胶囊底从"实心填充"改成"描边"，
         * 透明区域**更多**了，一旦丢了这个 Type 会立刻显示成白色方块。
         *
         * ## ★ 2026-10-08（U10）：从"实心胶囊 + 硬编码坐标"改为"描边轮廓 + 比例坐标"
         *
         * 用户原话：「现有的托盘图标样式改为**带深灰描边**的图标样式，而不是现在
         * **黑方块包裹**的样式。提升托盘图标标志**清晰度**。提升易读性（**适当放大图标**）」
         *
         * 三件事各自的解法：
         *
         * | 用户诉求 | 原状 | 现在 |
         * |---|---|---|
         * | 不要黑方块包裹 | `fillRoundRect` 实心深色胶囊 | `drawRoundRect` **只画描边**，内部透明 |
         * | 提升清晰度 | 尺寸写死 32，被系统缩放 | 取 `trayIconSize.trayIconSize` **同尺寸交付，零缩放** |
         * | 适当放大 | 同上 | 跟随系统尺寸（150% 缩放 ⇒ 自然变大） |
         *
         * ★ **一处刻意的保留**：胶囊**轮廓**仍在。KDoc 上文说过它是品牌标识 +
         * 深浅色背景的对比度保证 —— 用户要的是"**不要黑方块**"（＝不要实心填充），
         * 不是"不要胶囊"。改成描边后，对比度靠**描边色**保证（见 [PILL_STROKE]）。
         *
         * ★ **所有坐标都是比例**（相对 `size`）：这是"结构上避免再硬编码"的做法。
         * 原实现的 `(2, 5, ICON_SIZE - 5, ICON_SIZE - 11, 12, 12)` / `(12, 12, 23)` /
         * `(11, 9, 4, 14, 2, 2)` 在 `ICON_SIZE` 变化时会**全部失真** ——
         * 而尺寸现在由系统决定（会变），所以必须比例化。
         */
        fun createTrayIconImage(playing: Boolean): BufferedImage {
            val size = trayIconSizePx()
            // ★★★ 2026-10-10（U11）：**超采样**渲染（见 [TRAY_SUPERSAMPLE] 的 KDoc）。
            //   ★ 用户报障：「托盘图标**像素感太强**，不清晰不好看。」
            //   ★ 真因 = 按系统尺寸（常见 16 px）直接画 ⇒ AA 能做的有限；
            //     有 DPI 缩放时还会被**放大** ⇒ 更糊。
            //   ★ 修法 = 按 `size × TRAY_SUPERSAMPLE` 画，再**缩回** `size`
            //     （★ 缩小的 AA 远好于放大）✓
            val hi = size * TRAY_SUPERSAMPLE
            val big = BufferedImage(hi, hi, BufferedImage.TYPE_INT_ARGB)
            val g = big.createGraphics()
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                // ★ 超采样下再加一条：让描边**沿真实几何**走（不吸附到像素格），
                //   否则缩小后仍会看到"半像素抖动"的台阶。
                g.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE,
                )
                // 描边/填充都按同一条 AA 路径，避免圆角处出现锯齿台阶。

                val stroke = (hi * OUTLINE_STROKE_RATIO).coerceAtLeast(1f)
                val (a, b) = glyphPolygonsFor(hi, playing)

                // ① 状态字形**填充**
                g.color = GLYPH
                if (playing) {
                    // 播放中 → ▶ 三角。用 `Polygon` 而不是 `fillRect` 拼：
                    // 三角形的斜边需要抗锯齿，而 `Polygon` 走的是同一条 AA 路径。
                    g.fillPolygon(java.awt.Polygon(a, b, 3))
                } else {
                    // 已暂停 → ⏸ 双竖条。两根等宽、间距与条宽相同，
                    // 是"暂停"最易识别的比例（条太细会被托盘缩放糊掉）。
                    // 数组编码见 [glyphPolygonsFor] 的 KDoc：(x, y, w, h, arc)。
                    g.fillRoundRect(a[0], a[1], a[2], a[3], a[4], a[4])
                    g.fillRoundRect(b[0], b[1], b[2], b[3], b[4], b[4])
                }

                // ② 字形**自己的深灰描边**（★ 沿形状走，不是胶囊框）
                //
                // ★★★ 2026-10-10 **U10 终版** —— ★ 用户逐字拍板：
                //   > 「**要加描边，沿形状加描边，不是空心胶囊**」
                //
                // ## 四次迭代（★ 前三次都在"猜"）
                //
                // | 版本 | 形态 | 用户反馈 |
                // |---|---|---|
                // | 初版 | `fillRoundRect` **实心深色胶囊** + 白字形 | 「**黑方块包裹**」✗ |
                // | 10-08 | 胶囊**改描边**（★ **框还在**） | 「**还是包裹样式**」✗ |
                // | 10-10 我出的 C | **无框 + 无描边 + 纯字形** | ★ 用户看快选 C ⇒ 随即更正 |
                // | ★ **终版 = B** | ★ **无框 + 沿字形描边** | ★ 已落地 ✓ |
                //
                // ★ 关键区分（★ 这是前三次都没抓住的一点）：
                //   · **胶囊框** = 一个**独立于字形**的圆角矩形轮廓 ✗
                //   · **沿形状描边** = 描边**贴着字形自己的边界**（▶ 的斜边 / ⏸ 的竖条边）✓
                //   ★ 两者在 16 px 下观感差别很大：前者像"框里装东西"，后者就是"一个带边的字形" ✓
                //
                // ★ **作用**：原胶囊承担的**对比度保证**搬到字形边界 ⇒
                //   ★ 浅色任务栏上白字形也有边界可辨 ✓（★ 出 C 版时就发现纯白会糊掉）
                // ★ 出图对照（A/B/C × 16/24/32/48）见 `TrayIconProbe` ⇒
                //   `spw-super-island/_tray_icon_preview/tray_sheet.png` ✓
                g.color = PILL_STROKE
                g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                if (playing) {
                    g.drawPolygon(java.awt.Polygon(a, b, 3))
                } else {
                    g.drawRoundRect(a[0], a[1], a[2], a[3], a[4], a[4])
                    g.drawRoundRect(b[0], b[1], b[2], b[3], b[4], b[4])
                }
            } finally {
                g.dispose()
            }
            // ★ 缩回目标尺寸 —— ★ 高质量缩小 ⇒ 边缘是"多个高分辨率采样点的平均"，
            //   比"在 16 px 上直接画"干净得多 ✓
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2 = image.createGraphics()
            try {
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.drawImage(big, 0, 0, size, size, null)
            } finally {
                g2.dispose()
            }
            return image
        }

        /**
         * 悬浮提示。**显式写出两个手势各自做什么** ——
         * 这是消除"字形是状态还是动作"歧义、以及"双击能干嘛"的地方。
         *
         * 同时带上当前状态词：鼠标悬停是最容易看到的信息位，
         * 让用户不必从图标形状去猜。
         *
         * ⚠️ 两个手势都要写。只写单击会让"双击切歌"变成**只能靠碰巧发现**的功能 ——
         * 而托盘图标的悬浮提示正是系统提供的、唯一能说明它的地方。
         */
        fun tooltipFor(playing: Boolean): String {
            val state = if (playing) "播放中" else "已暂停"
            val action = if (playing) "单击暂停" else "单击继续"
            return "$TRAY_TOOLTIP · $state — $action / 双击下一首"
        }

        /**
         * **字形描边**色：深灰，不透明。
         *
         * ★ **2026-10-10（U10 终版）**：★ 本常量现在描的是**字形自己的边界**
         * （沿 ▶ 的斜边 / ⏸ 的竖条边）—— ★ 用户拍板「**沿形状加描边，不是空心胶囊**」✓
         * ★ **它不再是"胶囊框"的颜色**（胶囊那条路已删）✓
         *
         * ## 取值理由（2026-10-08 定下，至今未变）
         *
         * ★ **2026-10-08（U10）**：由 `PILL_FILL`（`Color(0x2B,0x2D,0x36,0xF0)`，
         * 半透明深灰**填充**）改为**不透明深灰描边**。两处刻意的差异：
         *
         * 1. **不透明**（alpha = 0xFF，原为 0xF0）：描边只有 1–2 px 宽，
         *    半透明会让它和背后的托盘背景混色 ⇒ 在浅色任务栏上"描边就消失了"。
         *    填充时代 alpha 是为了"隐约可见的品牌底"，描边时代**可见性优先**。
         * 2. **稍亮**（`0x3A/0x3D/0x46`，原 `0x2B/0x2D/0x36`）：用户要的是
         *    "带**深灰**描边"。纯 `0x2B` 在深色任务栏（Windows 深色模式的托盘区
         *    接近 `0x1F1F1F`）上几乎与背景同色 —— 提亮半档才能同时满足
         *    "深灰"与"深浅背景都看得见"。
         *
         * ★ 名字从 `PILL_FILL` 改为 `PILL_STROKE`：**填充变描边是语义变化**，
         * 留着旧名会让下一个读代码的人以为它还是填色。
         */
        val PILL_STROKE: Color = Color(0x3A, 0x3D, 0x46)

        /**
         * 状态字形的颜色：亮白。
         *
         * 与旧版 `LINE_BRIGHT` 同一个值（`0xF2F4F8`）—— 字形从"三根歌词行"换成
         * 播放状态符号后，**在胶囊底上的对比度要求没变**，故沿用同一档亮度。
         * 旧的 `LINE_DIM` / `LINE_BRIGHT` 已随字形一起删除（不再有"暗行/亮行"之分）。
         */
        val GLYPH: Color = Color(0xF2, 0xF4, 0xF8)
    }
}
