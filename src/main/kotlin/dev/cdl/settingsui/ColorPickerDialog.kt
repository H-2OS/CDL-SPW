// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.settings.composePackedArgb
import dev.cdl.settings.parseHexOrNull
import dev.cdl.platform.SystemTheme
import dev.cdl.settings.toHexString
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 完整取色器（色域板 + 色相条 + 明度条 + RGB 分组 + hex）。
 *
 * ## 为什么要有这个文件（而不是沿用 `ColorPicker.kt`）
 *
 * `ColorPicker.kt` 是**内联的"四根滑杆 + hex"**版（BUG-009 时期做的）——
 * 它能用，但**看不出"这个颜色长什么样"**：R/G/B 三个数字彼此独立，
 * 用户要在大脑里合成颜色，调一次得来回试。
 *
 * 用户 2026-09-18 给了 SPW 内置设置页的截图，要求换成那种样式：
 * **色域板（二维）+ 色相条 + 明度条 + RGB 分组 + hex**。
 *
 * ## 为什么不能直接在 SPW 设置页里画
 *
 * 已实测（2026-09-18）：
 * - `preference_config.json` 只支持 5 种 type（`switch`/`list`/`seekbar`/`edittext`/`button`）
 *   —— 实证在 `.diag18/outcfg2/androidx/compose/ui/fW.java`；
 * - `WorkshopApi$Ui` **只有 `toast` 一个方法**（`javap` 实证）。
 *
 * → **插件没有"在宿主设置页里嵌入自定义控件"的接口**。
 * 所以 1:1 还原截图**只能靠自绘窗** —— 这也是用户松口那条约束的原因
 * （原约束见 `SettingRegistry.kt` 的 "不要引去其他窗口"）。
 *
 * ## 纯函数与 UI 分离
 *
 * 颜色换算（[svToRgb] / [rgbToHsv] / [parseHexOrNull] / [toHexString]）是**顶层公开函数**，
 * 与 Swing 无关 —— 探针能直接断言它们。
 * **项目纪律：为测试发愁先抽纯函数，而不是放宽可见性**（先例：`window/IslandPolicy.kt`）。
 */

// ─────────────────────────────────────────────────────────────
// 纯函数：HSV ↔ RGB（顶层公开，探针可断言）
// ─────────────────────────────────────────────────────────────

/**
 * HSV → 打包 RGB（`0xRRGGBB`）。
 *
 * @param hue 色相，**度**（0..360，环形；360 与 0 等价）
 * @param sat 饱和度 0..1
 * @param value 明度 0..1
 */
fun svToRgb(hue: Float, sat: Float, value: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f / 60f      // 归一到 [0,6)
    val s = sat.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)

    val c = v * s
    val x = c * (1f - abs(h % 2f - 1f))
    val m = v - c

    val (r1, g1, b1) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
    // ── ★★ 2026-09-19：**这里也必须补 alpha**（同一缺陷的**第二处**）──────────
    //
    // 用户第二次发截图：`#00000000`（八位全零）、示例歌词仍然看不见。
    //
    // **上一轮我只修了 `ColorToolWindow.currentPacked()`（初始值），漏了本函数。**
    // 两者是**同一条数据链的两端**：
    //   · `currentPacked()` 决定**打开时**的初始色（上一张图的 `#00FFFFFF` 来自它）；
    //   · 本函数决定**对话框自己算出的当前色**（这一张图的 `#00000000` 来自它）——
    //     它同时喂给 hex 显示、RGB 字段、预览块与示例歌词。
    //
    // ⚠️ **"同一个缺陷出现在两处"是本项目的老形态**（BUG-057/069/070 都是
    //   "同族两处实现只改一处"）。故这里**不再手写位运算**，改为委托给
    //   [composePackedArgb] —— **公式只有一份，"忘了补 alpha"从此只可能发生在一个地方。**
    return composePackedArgb(r, g, b)
}

/**
 * 打包 RGB → HSV，返回 `floatArrayOf(hueDeg, sat, value)`。
 *
 * ## 灰色（sat = 0）的色相是未定义的 —— 本函数返回 0，但调用方**必须保留旧色相**
 *
 * 这是最容易出错的地方：黑白灰没有色相，`rgbToHsv` 只能给一个占位值（0 = 红）。
 * 若 UI 直接把返回值写回色相状态，用户把饱和度拖到 0 再拖回来时，
 * **色相会从原来的颜色跳成红色** —— 这是所有取色器的经典 bug。
 * 故 [ColorPickerDialog] 里色相是**独立状态**，只在用户直接操作色相条时改变。
 */
fun rgbToHsv(packed: Int): FloatArray {
    val r = ((packed shr 16) and 0xFF) / 255f
    val g = ((packed shr 8) and 0xFF) / 255f
    val b = (packed and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min

    val hue = when {
        d == 0f -> 0f                                     // 灰色：色相未定义，占位 0
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val h = if (hue < 0f) hue + 360f else hue
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(h, s, max)
}

// ─────────────────────────────────────────────────────────────
// 自绘组件
// ─────────────────────────────────────────────────────────────

/** 色域板：横轴 = 饱和度，纵轴 = 明度（上亮下暗）。左上白、右上纯色、下边黑。 */
private class SvPanel(
    private val hueProvider: () -> Float,
    // ⚠️ 第三个参数 `dragging` —— 见 [pick] 的 KDoc（2026-09-21 新增）。
    private val onPick: (sat: Float, value: Float, dragging: Boolean) -> Unit,
) : JPanel() {
    var sat = 0f; var value = 1f
    private var cache: BufferedImage? = null
    private var cacheHue = -1f

    /**
     * 上一次画出的**选点游标**外接矩形（组件坐标）。
     *
     * ## 为什么要有它（2026-09-21 修「取色板不跟手」的第二半）
     *
     * 色域板缓存**只在色相变化时重建** —— 拖动选点时逐像素**不变**。
     * 而原来 `pick()` 每次事件都 `repaint()`（整块）：等于每次鼠标事件都重贴
     * 一张 240×200 的位图（48k 像素），而真正变的只有 14×14 的游标。
     *
     * 改成"只重绘游标的新旧两个位置"后，每帧的贴图量从 48k 像素降到 ~400 像素。
     * **观感上这就是"跟手"与"不跟手"的差别**：EDT 上少做的事，鼠标事件就不用排队。
     */
    private var lastCursorBand: java.awt.Rectangle? = null

    init {
        preferredSize = Dimension(240, 200)
        isOpaque = true
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pick(e)
            override fun mouseDragged(e: MouseEvent) = pick(e)
            // ⚠️ **松手也必须 pick 一次**（`dragging = false`）——
            //    拖动中被跳过的文本框刷新要靠这一次补上。少了它，
            //    hex / RGB 框会停在拖动途中的**中间值**，而用户若照着它去别处用，
            //    拿到的就是**一个错的颜色**（比"慢一点"严重得多）。
            override fun mouseReleased(e: MouseEvent) = pick(e)
            /**
             * 取点并把「是否正在拖动」一并交给原实现。
             *
             * ## 为什么要区分拖动中（2026-09-21 修「取色板不跟手」）
             *
             * `mouseDragged` 按**系统鼠标采样率**派发（常见 125 Hz，即 8 ms 一次），
             * 而本回调的下游 `syncFromModel` 每次要写 **4 个文本框**
             * （`hexField` + 3 个 RGB 通道）。`JTextField.setText` 不是廉价操作 ——
             * 它会派发 `DocumentEvent`、触发 `revalidate` 与 `repaint`，
             * 于是**每秒数百次**文本重排把 EDT 塞满，鼠标事件开始排队 →
             * 用户看到的就是「**取色板操作不跟手**」。
             *
             * 而拖动过程中，文本框**并不是**视觉反馈的主线
             * （主线是选点、明度条与右侧示例预览，它们照常每帧更新）。
             * 故拖动中跳过文本框，松手时补一次 —— 视觉实时性不变，开销大幅下降。
             */
            private fun pick(e: MouseEvent) {
                val s = (e.x.toFloat() / width).coerceIn(0f, 1f)
                val v = 1f - (e.y.toFloat() / height).coerceIn(0f, 1f)
                sat = s; value = v
                onPick(s, v, e.id == MouseEvent.MOUSE_DRAGGED)
                repaintCursor()
            }
        }
        addMouseListener(drag)
        addMouseMotionListener(drag)
    }

    /**
     * 模型 → 本组件。由 [ColorPickerDialog.syncFromModel] 调用（**不要**直接 `repaint()`）。
     *
     * 判据：**色相变了才需要整块重绘** —— 缓存是按色相逐像素算出来的，色相不变则
     * 缓存内容逐位不变，此时只有游标在动（见 [lastCursorBand]）。
     * `cache == null` 时也必须整块重绘（首次绘制要建缓存）。
     */
    fun refreshFromModel() {
        if (cache == null || cacheHue != hueProvider()) {
            // ★★★ 2026-10-10（**U11-c**）：整块重绘时**必须同步 `lastCursorBand`**。
            //
            // ## 挡的是什么（★ 用户报障，逐字）
            //
            // > 「拖动顺畅了，但**有的时候会出现两个拖动点**。」
            //
            // ## 真因
            //
            // ★ `repaint()`（整块）会把游标画在**当前**位置，
            //   但 `lastCursorBand` 还停在**上一次局部重绘**的位置
            // ⇒ ★ 下一次 `repaintCursor()` 的 union（旧 ∪ 新）**不含"整块重绘时画的那个位置"**
            // ⇒ ★ 那个游标**残留** ⇒ **两个点** ✗
            //
            // ★ **触发条件 = "色相变化（走整块重绘）"与"游标移动"交错**
            //   ⇒ ★ 正是用户说的「**有的时候**」（不是每次）✓
            //
            // ★ 修法：整块重绘**之前**把 `lastCursorBand` 同步到"即将画出的位置" ✓
            //   （顺序要紧：必须在 `repaint()` 之前赋值，否则下一次仍会漏）
            lastCursorBand = cursorBand()
            repaint()
        } else {
            repaintCursor()
        }
    }

    /** 只重绘「游标的新位置 ∪ 旧位置」那一小块 —— 见 [lastCursorBand]。 */
    private fun repaintCursor() {
        val fresh = cursorBand()
        val band = lastCursorBand?.let { it.union(fresh) } ?: fresh
        lastCursorBand = fresh
        repaint(band.x, band.y, band.width, band.height)
    }

    /**
     * 游标（深色描边圆 `r=6` + 白色圆 `r=5`，见 [paintComponent]）的外接矩形，夹在组件内。
     *
     * `7` = 描边圆的半径 6 + 1 px 余量（抗锯齿会把边晕开一个像素）。
     */
    private fun cursorBand(): java.awt.Rectangle {
        if (width <= 0 || height <= 0) return java.awt.Rectangle(0, 0, 1, 1)
        val cx = (sat * width).roundToInt().coerceIn(0, width - 1)
        val cy = ((1f - value) * height).roundToInt().coerceIn(0, height - 1)
        val r = 7
        val x = (cx - r).coerceAtLeast(0)
        val y = (cy - r).coerceAtLeast(0)
        val w = ((cx + r).coerceAtMost(width - 1) - x + 1).coerceAtLeast(1)
        val h = ((cy + r).coerceAtMost(height - 1) - y + 1).coerceAtLeast(1)
        return java.awt.Rectangle(x, y, w, h)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        // ── ★ 2026-09-20：**先把自己整块方形填成父容器背景** ──────────────────
        //
        // 用户原话：「明度滑条不是胶囊型吗，**杂色像素就在包裹滑条的方形区域与
        //   滑条之间的空白区域闪烁**。」
        //
        // ## 根因
        //
        // 本组件只画**胶囊**（`fillRoundRect`），而胶囊**覆盖不到**它的
        // 四个圆角与上下留白（`y ∈ [0,4)` 与 `[h-4, h)`）。那块区域此前依赖
        // Swing 的 opaque 背景填充 —— 而那条路径在以下情形会留下**上一帧的残影**：
        //   · 父容器背景被改动（主题切换 / 主题跟随）；
        //   · 局部重绘（`repaint(x,y,w,h)` 只覆盖一部分，角上不被重绘）；
        //   · 半透明窗口下的合成。
        // 残影在连续重绘时表现为**杂色闪烁** ✓ 与用户描述完全吻合。
        //
        // ## 修法
        //
        // **自己**把整块方形填成**父容器**的背景（而不是依赖 Swing 的默认填充，
        // 也不用本组件自己的 `background` —— 那可能与父容器不一致，
        // 于是角上会出现一块**颜色不对的方块**，那是另一种可见缺陷）。
        //
        // ⚠️ 用父容器背景而非 `background`：本组件被放进带内边距的 `JPanel` 里，
        //    父容器才是「这块区域应该长什么样」的权威来源。
        //
        // ⚠️ 2026-09-21：**这里原先是同一段注释 + 同一行代码各写了两遍**（复制粘贴留下的），
        //    于是每次重绘都白填一次 240×200。删掉重复的一份 —— 本次修的就是
        //    "拖动时 EDT 上少做事"，留着它自相矛盾。
        g2.color = parent?.background ?: background
        g2.fillRect(0, 0, w, h)

        // 色域板缓存：只在色相变化时重建（逐像素画一次 ~48k 次，够快；但每帧重建没必要）
        val hue = hueProvider()
        if (cache == null || cache!!.width != w || cache!!.height != h || cacheHue != hue) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val row = IntArray(w)
            for (y in 0 until h) {
                val v = 1f - y.toFloat() / h
                for (x in 0 until w) {
                    row[x] = svToRgb(hue, x.toFloat() / w, v)
                }
                img.setRGB(0, y, w, 1, row, 0, w)
            }
            cache = img; cacheHue = hue
        }
        // ⚠️ 2026-09-21：**按组件当前尺寸画**，不要 `drawImage(cache, 0, 0, null)`。
        //    后者按缓存的**自然尺寸**落图 —— 缓存与组件尺寸一旦不一致（见 [HueBar]
        //    那个只比高度的判据），画出来的东西就与组件不是一回事。
        g2.drawImage(cache, 0, 0, w, h, null)

        // 选中游标：白色圆环 + 深色描边（保证在任何底色上都看得见）
        val cx = (sat * w).roundToInt().coerceIn(0, w - 1)
        val cy = ((1f - value) * h).roundToInt().coerceIn(0, h - 1)
        g2.color = Color(0, 0, 0, 140)
        g2.drawOval(cx - 6, cy - 6, 12, 12)
        g2.color = Color.WHITE
        g2.drawOval(cx - 5, cy - 5, 10, 10)
    }
}

/** 竖向色相条。 */
private class HueBar(
    // ⚠️ `dragging` 见 [SvPanel] 里 `pick` 的 KDoc（同一套"拖动中跳过文本框"的约定）。
    private val onPick: (hue: Float, dragging: Boolean) -> Unit,
) : JPanel() {
    var hue = 0f
    private var cache: BufferedImage? = null

    /**
     * 缓存**建立时的组件尺寸**（`宽 shl 16 or 高`）。
     *
     * ## ★★ 2026-09-21：这里原来只比**高度**（`cache!!.height != h`）—— 那是错的
     *
     * 用户原话（两轮）：「**边缘有杂色像素**」「杂色像素仍在（**一调色就出现**）」，
     * 追问后确认：「右下角的**彩虹条是在拖动取色板之后出现的**」。
     *
     * ### 为什么"只比高度"会画出尺寸不符的离屏缓存
     *
     * 本组件的缓存是一张**按尺寸逐像素算出来的色相渐变图**（`w × h`，每列相同）。
     * 只要**宽度**变了而高度没变，旧判据就认为"缓存还能用" ——
     * 于是 `drawImage` 画的是一张**宽度与组件不符**的彩虹图：
     * 缓存窄于组件 → 右边留下一条**没有彩虹的底色带**；
     * 缓存宽于组件 → 被裁掉一截。
     * 两种都是"**在错误的位置/宽度上出现了一条彩虹**" —— 与用户看到的形态同族。
     *
     * ### 为什么"拖动取色板之后"才出现
     *
     * 拖动会触发状态栏文字变化 → 工具窗重新布局 → 组件尺寸被重算。
     * **静止时没有、拖动后才出现** ⇒ 不是静态错位，而是"某次重绘用了尺寸不符的缓存" ✓
     * 与"离屏缓存尺寸失配"这条完全吻合。
     *
     * ### 修法
     *
     * 判据改成**宽高一起比**（`宽 shl 16 or 高` 这个 key 同时编码两者，改任一维都会失配），
     * 并且 [paintComponent] 里 `drawImage` **显式给出组件的当前尺寸** ——
     * 双保险：即使将来判据又被写错，也不会把图落在错误的大小上。
     */
    private var cacheKey = -1

    /** 上一次画出的游标带（见 [SvPanel.lastCursorBand] 的同款理由）。 */
    private var lastMarkerBand: java.awt.Rectangle? = null

    init {
        preferredSize = Dimension(28, 200)
        isOpaque = true
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pick(e)
            override fun mouseDragged(e: MouseEvent) = pick(e)
            // 松手补一次（理由见 [SvPanel] 的 `pick`）。
            override fun mouseReleased(e: MouseEvent) = pick(e)
            private fun pick(e: MouseEvent) {
                hue = (e.y.toFloat() / height).coerceIn(0f, 1f) * 360f
                onPick(hue, e.id == MouseEvent.MOUSE_DRAGGED)
                repaintMarker()
            }
        }
        addMouseListener(drag)
        addMouseMotionListener(drag)
    }

    /**
     * 模型 → 本组件。由 [ColorPickerDialog.syncFromModel] 调用。
     *
     * ⚠️ **本组件的渐变与当前色相无关**（整条就是 0°→360° 的彩虹），
     * 故色相变化**不需要**整块重绘 —— 只有游标在动。
     * 这也是"拖动色相条时最该省掉的那一次全条重贴"。
     */
    fun refreshFromModel() = repaintMarker()

    /** 只重绘「游标的新位置 ∪ 旧位置」那一小块。 */
    private fun repaintMarker() {
        if (width <= 0 || height <= 0) return
        val fresh = markerBand()
        val band = lastMarkerBand?.let { it.union(fresh) } ?: fresh
        lastMarkerBand = fresh
        repaint(band.x, band.y, band.width, band.height)
    }

    /** 游标（`fillRect(0, cy-2, w, 4)` + 白色 `fillRect(0, cy-1, w, 2)`）的外接矩形。 */
    private fun markerBand(): java.awt.Rectangle {
        val cy = (hue / 360f * height).roundToInt().coerceIn(0, height - 1)
        val y = (cy - 3).coerceAtLeast(0)
        val hh = ((cy + 3).coerceAtMost(height - 1) - y + 1).coerceAtLeast(1)
        return java.awt.Rectangle(0, y, width.coerceAtLeast(1), hh)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        // ★★ 2026-09-21：**宽与高都要比**（原判据只比高度，见 [cacheKey] 的 KDoc）。
        val key = (w shl 16) or (h and 0xFFFF)
        if (cache == null || cacheKey != key) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) {
                val c = svToRgb(y.toFloat() / h * 360f, 1f, 1f)
                for (x in 0 until w) img.setRGB(x, y, c)
            }
            cache = img
            cacheKey = key
        }
        // ★★ 显式按组件当前尺寸落图 —— 缓存与组件尺寸不一致时也不会画错大小。
        g2.drawImage(cache, 0, 0, w, h, null)

        val cy = (hue / 360f * h).roundToInt().coerceIn(0, h - 1)
        g2.color = Color(0, 0, 0, 140)
        g2.fillRect(0, cy - 2, w, 4)
        g2.color = Color.WHITE
        g2.fillRect(0, cy - 1, w, 2)
    }
}

/**
 * 横向明度条（**黑 → 当前基色**），带圆形手柄。
 *
 * ## ⚠️ 渐变右端为什么是"基色"而不是"白"
 *
 * 本条编辑的是**整体明度倍率**（`0..100`，见 `ColorPickerDialog.lightness`），
 * 打包值 = `基色 × 倍率/100`。于是它的**真实取值范围**是
 * 「黑 → 基色」——基色是蓝的，这条就该是"黑 → 蓝"。
 *
 * 原实现画的是「黑 → 白」（那是"往白色调"的语义），与它实际做的事不符：
 * 基色是红的时候，条的中点是**灰**、而结果色是**暗红** ——
 * 用户按条的显示去预期颜色必然落空。**这就是用户说的"逻辑混乱"的一部分。**
 */
private class LightnessBar(
    /** 当前基色（打包 ARGB）—— 渐变右端用它。拖动中基色不变，故只在选点/色相变化时重绘。 */
    private val baseColor: () -> Int,
    // ⚠️ `dragging` 见 [SvPanel] 里 `pick` 的 KDoc（同一套"拖动中跳过文本框"的约定）。
    private val onPick: (v: Float, dragging: Boolean) -> Unit,
) : JPanel() {
    var value = 1f

    /**
     * 上一次画渐变时用的**基色**。
     *
     * 本条的渐变右端 = 当前基色（见类 KDoc），故**基色变了就必须整条重绘**；
     * 基色没变时（只拖本条的游标）只有游标在动 —— 那时只重绘游标带。
     * 与 [SvPanel.refreshFromModel] / [HueBar.refreshFromModel] 同一套约定。
     */
    private var lastBaseColor = Int.MIN_VALUE

    /** 上一次画出的游标带（见 [SvPanel.lastCursorBand] 的同款理由）。 */
    private var lastMarkerBand: java.awt.Rectangle? = null

    init {
        preferredSize = Dimension(240, 24)
        isOpaque = true
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pick(e)
            override fun mouseDragged(e: MouseEvent) = pick(e)
            // 松手补一次（理由见 [SvPanel] 的 `pick`）。
            override fun mouseReleased(e: MouseEvent) = pick(e)
            private fun pick(e: MouseEvent) {
                value = (e.x.toFloat() / width).coerceIn(0f, 1f)
                onPick(value, e.id == MouseEvent.MOUSE_DRAGGED)
                repaintMarker()
            }
        }
        addMouseListener(drag)
        addMouseMotionListener(drag)
    }

    /** 模型 → 本组件。由 [ColorPickerDialog.syncFromModel] 调用。 */
    fun refreshFromModel() {
        if (lastBaseColor != baseColor()) {
            // ★★★ 2026-10-10（**U11-c**）：同 [SvPanel.refreshFromModel] ——
            //   ★ 整块重绘会把游标画在**当前**位置，而 `lastMarkerBand` 还停在旧位置
            //   ⇒ ★ 下次 union 漏掉它 ⇒ **游标残留**（"两个拖动点"）✗
            //   ★ 修法：整块重绘前同步 `lastMarkerBand` ✓
            lastMarkerBand = markerBand()
            repaint()
        } else {
            repaintMarker()
        }
    }

    /** 只重绘「游标的新位置 ∪ 旧位置」那一小块。 */
    private fun repaintMarker() {
        if (width <= 0 || height <= 0) return
        val fresh = markerBand()
        val band = lastMarkerBand?.let { it.union(fresh) } ?: fresh
        lastMarkerBand = fresh
        repaint(band.x, band.y, band.width, band.height)
    }

    /** 游标（深色圆 `r=7` + 白色圆 `r=6`）的外接矩形，夹在组件内。 */
    private fun markerBand(): java.awt.Rectangle {
        val markerR = 7
        val cx = (value * width).roundToInt()
            .coerceIn(markerR, (width - markerR - 1).coerceAtLeast(markerR))
        val cy = height / 2
        val x = (cx - 7).coerceAtLeast(0)
        val y = (cy - 7).coerceAtLeast(0)
        val w = ((cx + 7).coerceAtMost(width - 1) - x + 1).coerceAtLeast(1)
        val h = ((cy + 7).coerceAtMost(height - 1) - y + 1).coerceAtLeast(1)
        return java.awt.Rectangle(x, y, w, h)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        // ★ 2026-09-20：**先把自己整块方形填成父容器背景** ——
        //   修「明度条方形区域与胶囊之间的空白有杂色闪烁」（用户原话）。
        //   本组件只画**胶囊**（下面的 fillRoundRect），而胶囊**覆盖不到**它的
        //   四个圆角与上下留白（y ∈ [0,4) 与 [h-4, h)）。那块区域此前依赖
        //   Swing 的 opaque 背景填充 —— 而那条路径在主题切换 / 局部重绘 /
        //   半透明窗口合成下会留下**上一帧的残影** → 表现为**杂色闪烁**。
        //   修法：自己把整块方形填成**父容器**的背景（不用本组件的 background ——
        //   那可能与父容器不一致，于是角上会出现一块**颜色不对的方块**）。
        g2.color = parent?.background ?: background
        g2.fillRect(0, 0, w, h)

        val barH = h - 8
        // 右端 = **当前基色**（见类 KDoc：这条编辑的是乘性倍率，取值范围就是"黑 → 基色"）。
        // ⚠️ 记下它 —— [refreshFromModel] 靠"基色变了没有"决定整条重绘还是只重绘游标带。
        val base = baseColor()
        lastBaseColor = base
        g2.paint = GradientPaint(
            0f, 0f, Color.BLACK,
            w.toFloat(), 0f, Color(base or 0xFF000000.toInt()),
        )
        g2.fillRoundRect(0, 4, w, barH, barH, barH)

        // ⚠️ 2026-09-19：**标记圆心要留出半径**，否则贴到两端时被裁掉半个圆
        //    （真机截图里右端那个残缺的圆就是它）。
        //    半径 7（下面画的是 14×14 的底圆），故圆心夹在 `[7, w-8]`。
        val markerR = 7
        val cx = (value * w).roundToInt().coerceIn(markerR, (w - markerR - 1).coerceAtLeast(markerR))
        val cy = h / 2
        g2.color = Color(0, 0, 0, 150)
        g2.fillOval(cx - 7, cy - 7, 14, 14)
        g2.color = Color.WHITE
        g2.fillOval(cx - 6, cy - 6, 12, 12)
    }
}

// ─────────────────────────────────────────────────────────────
// 窗
// ─────────────────────────────────────────────────────────────

/**
 * 一次取色的**完整结果**。
 *
 * ## 为什么回调不能只给一个 `Int`（2026-09-19 修 P0 时改的）
 *
 * 4 键制颜色组在配置里存的是 `{r, g, b, lightness}` 四个数，最终色 = `RGB × lightness/100`。
 * 若回调只给"最终色"，接收方就得**反推** lightness —— 而反推是**有损且不可逆**的
 * （拖到黑之后 `rgbToHsv(黑)` 得 `sat=0, value=0`，基色彻底丢失）。
 *
 * 更糟的是：接收方为了反推，必须在**构造期间**（`onPreview` 第一次回调就发生在
 * `ColorPickerDialog.init` 里）去读**自己那个还没初始化完的 `picker` 字段** → **NPE**。
 * 真机日志里那个"关掉取色窗就再也打不开"就是这条链。
 *
 * 故把**三个数一起交出去**：接收方**零反推、零回读**，构造期间也不会碰未初始化的字段。
 *
 * @param packed 最终色（已含整体明度）—— 写进 `COLOR` 单键，或直接用于显示
 * @param baseRgb 基色（不含整体明度）—— 4 键制写进 `.r/.g/.b`
 * @param lightnessPercent 整体明度倍率 0..100 —— 4 键制写进 `.lightness`
 */
data class PickedColor(
    val packed: Int,
    val baseRgb: Int,
    val lightnessPercent: Int,
)

/**
 * 完整取色器窗（非模态）。
 *
 * **非模态是刻意的** —— 用户改色时要能同时看到词岛的实时变化，
 * 模态窗会把主窗挡住，等于让人盲调。
 *
 * 用法：
 * ```kotlin
 * val picked = ColorPickerDialog(owner = parentWindow, initial = 0xE6E6E6, title = "已唱歌词颜色").showAndGet()
 * if (picked != null) { /* 用户点了确定 */ }
 * ```
 */
class ColorPickerDialog(
    owner: Window?,
    initial: Int,
    title: String,
    /**
     * 颜色变化时的**实时预览**回调（拖动中也调）。
     *
     * ## 为什么需要它（2026-09-18 接线时加）
     *
     * 本窗有两种用法：
     *  - **对话框**（`showAndGet()`）：点"确定"才取值，不需要实时回调；
     *  - **嵌入工具窗**（[buildContent]）：必须**拖动中实时预览** ——
     *    这是用户第二轮拍板的纪律（`recon/07` §1 铁律①：拖动中只 publish、松手才 apply）。
     *
     * 故做成**可选**：`null` = 纯对话框形态，行为与之前完全一致。
     */
    private val onPreview: ((PickedColor) -> Unit)? = null,
    /**
     * **当前使用的字体**提供者（可为 null = 用 Swing 默认字体）。
     *
     * 用户 2026-09-19：「**自绘窗口使用当前使用的字体**」。
     * 做成 `() -> Font` 而不是 `Font`：字体可能被别的路径改（用户在设置页换了族名），
     * 每次重绘现取才能跟上 —— 与 `MenuStepper.readValue` 同一种"现读"约定。
     */
    private val fontProvider: (() -> java.awt.Font)? = null,
    /**
     * **初始的「整体明度」倍率**（0..100，见 [lightness]）。
     *
     * 4 键制颜色组（歌词三色）在配置里存的是 `{r,g,b,lightness}`，
     * 打包值 = `RGB × lightness/100`。**打开时必须把这个倍率一起带进来** ——
     * 否则取色器只能从"已经被压暗过的"打包值反推，那正是"黑色吸收态"的成因。
     */
    private val initialLightnessPercent: Int = 100,
    /**
     * 正在编辑的键（如 `translation.outline.color`）—— 决定示例预览里**哪一项**被染成待定色。
     * 空串 = 不染色（预览显示当前生效的样子）。
     */
    private val previewTargetKey: String = "",
    /**
     * 打开时**冻结**的词岛外观快照（见 [PreviewSnapshot] 的 KDoc）。
     * `null` = 调用方没给，用 [PreviewSnapshot.DEFAULTS] 兜底（预览仍画得出来）。
     */
    private val previewSnapshot: PreviewSnapshot? = null,
) : JDialog(owner, title, ModalityType.MODELESS) {

    // 色相**独立持有**：见 [rgbToHsv] 的 KDoc —— 灰色时色相未定义，
    // 若从 RGB 反推会丢掉用户选的色相。
    private var hue: Float
    private var sat: Float
    private var value: Float

    /**
     * **「整体明度」倍率**（0..100）—— 与色域板纵轴（HSV 的 `value`）**完全独立**。
     *
     * ## 为什么必须独立（用户 2026-09-19 第二次反馈）
     *
     * 用户原话：「无论怎么拖动选点，滑动滑杆，**最后都会落在蓝色区间**，
     * 明度滑杆**在光标拖动处和最黑端来回闪烁**，**松手后始终在最黑端**。」
     *
     * 原实现里明度条与色域板纵轴**写的是同一个 `value`**，于是：
     *   拖明度条 → `value` 变 → 打包值被压暗 → `adopt` 用 `rgbToHsv(压暗值)`
     *   反推 → `sat`/`value` 一起塌 → **黑色成了吸收态**（`0 × 任何倍率 = 0`）。
     *   而 `hue` 是独立保留的，所以色域板**还是蓝的**，选点塌在左下角 ——
     *   正是用户描述的"落在蓝色区间"。
     *
     * 分开之后：**基色（`hue`/`sat`/`value`）永不被明度污染**，明度拖到 0 再拖回来
     * 能逐位还原（`ColorPickerProbe` 有一条断言专门钉这个**可逆性**）。
     */
    private var lightness: Float

    private var result: Int? = null

    //
     // 上一次**由本窗发布出去**的打包值。
     //
     // ## 为什么用"比值"而不是"标志位"（这是结构性修法）
     //
     // 原实现用 `publishingFromSelf` 布尔标志挡住订阅回灌，但订阅回调是
     // `SwingUtilities.invokeLater { if (publishingFromSelf) return }` ——
     // **`invokeLater` 真正执行时标志早已在 `finally` 里复位**，
     // 于是守卫失效 → 回灌照旧 → 把值用往返误差改写 →
     // 拖动中**反复跳回黑端**（用户说的"来回闪烁"）。
     //
     // 判据改成"**收到的值就是我刚发出去的那个** → 什么都不用做"：
     // 它与回调的延迟无关，也与有几个订阅者无关。
     //
    /**
     * "我上次**发出去**的三个量"，供 `setValueFromModel` 判"这就是我刚发的回声"。
     *
     * ⚠️ **必须分开存、并分别与同量纲的入参比对**（2026-09-20 修 P0）：
     * 颜色组走 `setValueFromModel(基色, 明度)`，而**单键**走 `setValueFromModel(打包值)`。
     * 若只存一个"打包值"去比"基色"，则**整体明度 ≠ 100 时恒不相等** → 守卫失效
     * → 拖动被回灌打断（"取色点不能连续拖动"）。
     */
    private var lastPublishedPacked: Int? = null
    private var lastPublishedBase: Int? = null
    private var lastPublishedLightness: Int? = null

    /**
     * ★ **本窗发布过的值的历史** —— 回声判定的**权威**判据（2026-09-21 修 BUG-106）。
     *
     * ## 为什么单槽守卫不够（这是本轮 P0 的根因）
     *
     * 上面那三个"上一次发布的值"是**单槽**：每发一次就被**覆盖**。
     * 而订阅回声走 `SwingUtilities.invokeLater`，**晚于**后续的发布才被处理。于是：
     *
     * ```
     * t0 拖动帧 → publish(B0) 并排入回声 E0     → 单槽 = B0
     * t1 拖动帧 → publish(B1) 并排入回声 E1     → 单槽 = B1   ← B0 被覆盖
     * EDT 处理 E0：B0 != 单槽(B1) → 判为"外部改色" → adopt(B0) → 选点被拽回旧色
     * ```
     *
     * **用户症状**：「取色板操作**依然**不跟手」（第二次报）。
     * 注意它**不是**"守卫写错了量纲"（那是 BUG-100，已修）—— 单槽守卫**本身**就漏判
     * "迟到的回声"，无论量纲对不对。
     *
     * ## 判据
     *
     * **回声携带的值必定是本窗发布过的某个值** —— 故"收到的值在台账里"就等价于
     * "这是我自己发的回声"，与它是不是**最新**那一次无关。台账命中 → 直接返回。
     *
     * 这比"比值 + 标志位"都强：它**不依赖时序**（回声迟到多久都命中），
     * 也**不依赖调用方配合**（`publishingFromSelf` 那种标志位必然被 `finally` 提前复位）。
     *
     * ## 为什么可以"命中即忽略"而不会吞掉外部改色
     *
     * 台账里每个值都是**本窗自己产生过**的。收到它时，本窗状态**已经处于或新于**该值
     * （发布是按时间单调的）→ 采纳它只会把选点**往回拽**。故忽略恒为正确。
     *
     * 真正的**外部**改色（预设、菜单、另一个窗）携带的是本窗没发过的值 → 不在台账 →
     * 照常 `adopt`。见探针 §9.7 的反向护栏。
     *
     * ## 有界
     *
     * 拖动期按 [DRAG_PUBLISH_INTERVAL_MS] 节流，但仍会积累 —— 故保留最近
     * [SELF_PUBLISH_LEDGER_MAX] 个（超出丢最旧的）。丢掉一个极旧值意味着
     * "它对应的回声若在 N 次发布之后才到"会被误采纳 —— 需要一次跨越 500+ 次发布的拖动，
     * 且那一路回声都迟到，实际不可达；而**手势结束会整表清空**（见 [scheduleLedgerClear]），
     * 故它不会跨手势累积。
     */
    private val selfPublishedPacked = LinkedHashSet<Int>()
    private val selfPublishedBaseL = LinkedHashSet<Long>()

    /**
     * 把"本窗刚发布出去的三个量"记进单槽**与**台账。
     *
     * ⚠️ **台账只在"真的发出去了"时记账**（`transmitted`）—— 这条是必须的，不是优化：
     * `publishPreview` 在 `onPreview == null` 时**直接返回**（不发送 → 不可能有回声），
     * 若此时仍记账，台账里就会留下**永远不会被回声消费**的条目 →
     * 之后**外部**把颜色设成那个值时会被静默吞掉。
     * （实测后果：`ColorPickerProbe` 9.3「明度 0 → 100 逐位还原」由绿转红 ——
     *  **探针抓住了这个假阴性**，这正是反向护栏的价值。）
     *
     * 单槽则无条件记：它是"快路径 + 既有断言直接比的对象"，与"是否发送"无关。
     */
    private fun rememberSelfPublished(packed: Int, base: Int, l: Int, transmitted: Boolean) {
        lastPublishedPacked = packed
        lastPublishedBase = base
        lastPublishedLightness = l
        if (!transmitted) return
        selfPublishedPacked.add(packed)
        selfPublishedBaseL.add(ledgerKey(base, l))
        while (selfPublishedPacked.size > SELF_PUBLISH_LEDGER_MAX) {
            val it = selfPublishedPacked.iterator(); it.next(); it.remove()
        }
        while (selfPublishedBaseL.size > SELF_PUBLISH_LEDGER_MAX) {
            val it = selfPublishedBaseL.iterator(); it.next(); it.remove()
        }
    }

    /**
     * **手势结束时**（非拖动那次发布之后）把台账清空。
     *
     * ⚠️ 必须是**下一轮 EDT**，不能就地清 —— 本窗在 `cb(picked)` 里排下的回声
     * 也在同一批里，**先让它们把话说完**（此时台账仍在，命中 → 忽略），
     * 再清表。就地清会让"最后一次发布"的回声变成"外部改色"。
     *
     * 为什么**不能**在拖动中就清：那正是 BUG-106 的形态（清掉 = 迟到的回声全部漏判）。
     * 为什么**要**清：不清的话，台账会跨手势累积，用户下一次**真的**从预设/菜单
     * 设成一个"上次拖动经过过的颜色"时会被静默忽略。
     */
    private fun scheduleLedgerClear() {
        SwingUtilities.invokeLater {
            selfPublishedPacked.clear()
            selfPublishedBaseL.clear()
        }
    }

    private fun ledgerKey(base: Int, l: Int): Long =
        (base.toLong() shl 8) or (l.toLong() and 0xFFL)

    /**
     * [adopt] 被调用过几次。
     *
     * **存在的唯一理由**：把"回灌守卫有没有生效"变成**可计数的、可证伪的**事实 ——
     * 见 [adopt] 里的说明。
     *
     * ⚠️ **必须 `public`（不能用 `internal`）**：探针在**独立的 `probe` 源集**里编译，
     * 看不到 `main` 的 `internal` 成员（本项目已记过这条坑）。
     * setter 保持 `private` —— 只允许本类递增，外部只读。
     */
    var adoptCount = 0
        private set

    /**
     * ★★★ 2026-10-10（**U11 诊断**）：回调被 `syncing` 挡住的**次数**。
     *
     * ## 为什么加（用户报障，逐字）
     *
     * > 「我说的**拖不动**是指在**自绘窗口里拖动选点**，明度有没有变化我还是看得出来的，
     * >   **就是没有变化**，自绘窗口报告的**色号自从第一次拖动后就没有变过**（因为拖不动）」
     *
     * ★ 色号（`hexField`）只在 `syncFromModel` 的 `if (!dragging)` 块里更新
     * ⇒ ★ **色号一直不变 = `syncFromModel` 没被调用** ⇒
     * ★★ 最可能是 **`syncing` 卡在 `true`**（三个回调的 `if (!syncing)` 全被挡掉）。
     *
     * ★ 静态审查**没看出它怎么卡住**（`syncFromModel` 的 `try/finally` 是完整的）
     * ⇒ ★ 故加这条**只在异常路径打**的诊断：**正常时零输出**，一旦持续出现即为确诊 ✓
     */
    private var syncBlockedCount = 0

    /**
     * 回调被 `syncing` 挡住时的诊断输出（★ 只打前 8 次，避免刷屏）。
     *
     * ★ **正常路径不调用它** ⇒ 对"来之不易的流畅拖动"**零影响** ✓
     */
    private fun noteSyncBlocked(where: String) {
        syncBlockedCount++
        if (syncBlockedCount <= 8) {
            println(
                "[CDL] 取色器诊断：★ $where 回调被 syncing 挡住" +
                    "（第 $syncBlockedCount 次）—— 若持续出现 ⇒ syncing 未复位（BUG）",
            )
        }
    }

    /** 防循环：控件 → 模型 → 控件 的回写路径上必须跳过监听。 */
    private var syncing = false

    // ─────────────────────────────────────────────────────────────
    // 拖动节流（2026-09-21 修「色相板仍不跟手」）
    // ─────────────────────────────────────────────────────────────
    //
    // ## 为什么要节流（而不是"再优化某一处重绘"）
    //
    // 上一轮把 `IslandWindow` 的 `applyGeometry` 改成"只在 `layout.*` / `main_lyric.font.*`
    // 变化时才跑"，用户反馈**仍然不跟手**。说明瓶颈不在那一处，而在**整条同步链本身**：
    //
    // ```
    // mouseDragged（按系统采样率派发，常见 125 Hz，高刷鼠标可达 1000 Hz）
    //   → HueBar.pick
    //   → syncFromModel（本地 UI：4 个文本框 + 4 个 repaint + 示例预览重排文字）
    //   → onPreview → ColorToolWindow.preview
    //     → SettingsRuntime.publish            ← 建新快照 + 逐键 diff
    //     → notifySubscribers（进程级，含**词岛窗口**那条）
    //       → IslandWindow.onEdt { applyAppearance / applyExtensionStyles（Win32 调用）
    //                              / applyAnimationSettings / applyVisibility / syncFrameDriver }
    // ```
    //
    // 这条链**每个鼠标事件都完整走一遍**，而且**是同步的**（鼠标事件在 EDT 上派发，
    // 而 `onEdt` 发现"已经在 EDT"就直接执行，不再排队）。
    // 于是鼠标采样率有多高，EDT 就被占多少 —— 事件开始排队 ⇒ **用户看到的就是"不跟手"**。
    //
    // ## 为什么节流是安全的（不牺牲任何可见行为）
    //
    // 下游的消费者**只有两个**：词岛窗口的画面、工具窗的状态栏文字。
    // 两者都是**给人看的画面**，30 Hz 已经高于"看得出连续"的门槛；
    // 而**落盘根本不在这条链上**（`preview()` 只 `publish`，写盘在"确定"按钮里）。
    // 所以"把发布压到 30 Hz"不会让任何**数据**丢失或延迟 —— 只让**中间帧**少画几次。
    //
    // ⚠️ **最后一定不能丢**：节流掉的只是"途中的中间值"，松手那一次必须**立刻**发出去
    //    （`mouseReleased → pick(dragging = false)` 走的是**不过闸**的分支）。
    //    否则词岛与文本框会停在拖动途中的一个**错的值**上 —— 那比"慢一点"严重得多。

    /**
     * 真正调用 [onPreview] 的次数。
     *
     * **存在的唯一理由**：把"节流有没有生效"变成**可计数的、可证伪的**事实 ——
     * 与 [adoptCount] 同一条纪律（"不可证伪的检查等于没有检查"）。
     *
     * ⚠️ **必须 `public`**（不能用 `internal`）：探针在**独立的 `probe` 源集**里编译，
     * 看不到 `main` 的 `internal`。setter 保持 `private`。
     */
    var publishCount = 0
        private set

    /**
     * 收到的取色事件次数（按下 / 拖动 / 松手）。
     *
     * 它让探针能区分两种"绿"：
     * `publishCount` 小是因为**节流生效**，还是因为**事件压根没送到组件上**。
     * 少了它，一条"发布次数 ≤ 阈值"的断言可以在"事件全被吞掉"时**假绿**。
     */
    var pickEventCount = 0

        private set

    /** 上次真正发布的时间戳（纳秒）。`0` = 还没发过 → 第一次必发。 */
    private var lastPublishNanos = 0L

    /** 被节流掉、攒着等补发的最新值。 */
    private var pendingPreview: PickedColor? = null

    /** 一次性补发定时器（**只在拖动期间存在**，松手即停 —— 见 [publishPreview]）。 */
    private var previewFlushTimer: javax.swing.Timer? = null

    private val hexField = JTextField(9)
    private val channelFields = List(3) { JTextField(4) }
    private val channelLabels = List(3) { JLabel() }
    private val preview = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            g.color = Color(packed())
            g.fillRect(0, 0, width, height)
        }
    }

    //
     // **歌词展示栏** —— 固定在选色组件**下方**，实时反映当前选中的颜色。
     //
     // ## 用户原话（2026-09-19）
     //
     // > 「追加在自绘窗口的**选色组件下设置固定歌词展示栏**（并提供**深/浅双色背景**），
     // > 歌词展示框**可实时随当前设置而变化**，我**稍后指定歌词句**。」
     //
     // ## 三个设计决定
     //
     // 1. **固定位置**（不在滚动区里、不随控件伸缩）—— "固定"是用户的原词。
     //    实现上它和"确定/取消"一样挂在 SOUTH，故窗口怎么拉都在最下面。
     // 2. **深/浅双色背景** —— 用一个可点的按钮切换。为什么需要它：
     //    用户选的可能是**浅色歌词**，若预览底色恰好也浅，就"看不出是什么颜色"。
     //    两个底色各看一眼，才知道这个色在两种背景下分别是什么观感。
     // 3. **实时** —— 挂在 `syncFromModel` 的末尾（与 `onPreview` 同一处），
     //    故拖动色域板时**与词岛同步**变化，不需要点确定。
     //
     // ## "我稍后指定歌词句"
     //
     // 故 [LYRIC_PREVIEW_TEXT] 现在是一个**明确的占位句**，且**写清了它是占位** ——
     // 用户看到"占位"两字就知道这里等着被替换，而不是以为"预览坏了"。
     //
    /**
     * **词岛示例预览条** —— 固定在选色组件**下方**（用户 2026-09-19 要求）。
     *
     * ⚠️ 2026-09-19 换过实现：原为 `LyricPreviewBar`（一个深色方块 + 两行字）。
     * 用户第二次反馈「歌词示范的显示逻辑也很不理想……**其他设置被固定下来**
     * 并在示例歌词上渲染」→ 改成画一个**小词岛**（形状/背景/封面/频谱/边框/描边/两行歌词）。
     * 快照在**打开时冻结**，见 [PreviewSnapshot.capture]。
     */
    private val islandPreview = IslandPreviewPane(
        snapshot = previewSnapshot ?: PreviewSnapshot.DEFAULTS,
        targetKey = previewTargetKey,
    )

    private val sv = SvPanel({ hue }, { s, v, dragging ->
        pickEventCount++
        if (!syncing) {
            sat = s; value = v
            syncFromModel(updateHue = false, dragging = dragging)
        } else {
            noteSyncBlocked("色域板选点")
        }
    })
    private val hueBar = HueBar { h, dragging ->
        pickEventCount++
        if (!syncing) {
            hue = h
            // ★★★ 2026-10-10（**U11**）：拖色相板时，若饱和度过低 ⇒ **抬到可见下限**。
            //
            // ## 挡的是什么（★ 用户报障，逐字）
            //
            // > 「对主副歌词描边色自绘窗口明度条拖动无效果的修复**导致色相板拖动不了**，
            // >   明度条也拖动不了。」
            //
            // ★ 真因是**数学必然**，不是回归：`svToRgb` 里 `c = v × s`、`m = v − c`
            // ⇒ ★ **`sat = 0` 时 `r = g = b = v`** ⇒ ★ **任何 hue 都得同一块灰**。
            // ★ 而描边色这类"深灰/黑"的初始色经 `rgbToHsv` 反推出来的 `sat` **极低**
            // （真机日志实测：`picked=#ffe7d3d3` ⇒ `sat = (231−211)/231 ≈ 0.087`）
            // ⇒ ★ 拖色相板时**通道差异只有个位数** ⇒ ★ 用户看到「拖不动」✗
            //
            // ## 为什么"修复后才出现"
            //
            // ★ BUG-164 把 3 键制的通道值从 `baseRgb`（**不含明度 ⇒ 亮**）改成 `packed`
            // （**含明度 ⇒ 暗**）—— ★ 那个修复**本身是对的**（否则明度条完全无效）。
            // ★ 但颜色变暗后，**本就极低的饱和度更看不出来** ⇒ 观感上"色相板也不动了"✓
            // ⇒ ★ 故这里**不能回退 BUG-164**，只能**把色相可见性单独修好** ✓
            //
            // ## 为什么是"抬到下限"而不是别的
            //
            // ★ 与主流取色器一致：**在灰度上拖色相 ⇒ 应当上色**（否则色相条形同虚设）。
            // ★ 只动 `sat`、**不动 `value`** ⇒ ★ 用户的"明暗"意图不受影响 ✓
            // ★ 抬高后 `syncFromModel` 会把色域板选点同步过去（`sv.sat = sat`）⇒
            //   选点落在"有饱和度"处 ⇒ ★ 用户接着拖色域板行为正常 ✓
            if (sat < HUE_DRAG_MIN_SAT) sat = HUE_DRAG_MIN_SAT
            syncFromModel(updateHue = false, dragging = dragging)
        } else {
            noteSyncBlocked("色相条")
        }
    }

    /**
     * 明度条 —— 编辑的是 **[lightness]（整体明度倍率 0..100）**，**不是** HSV 的 `value`。
     *
     * 用户原话：「明度滑杆在光标拖动处和最黑端**来回闪烁**，松手后**始终在最黑端**。」
     * 原实现写的是 `value`（= 色域板纵轴），两者共用一个量 → 拖它必然把选点拖走，
     * 且打包值被压暗后经 `rgbToHsv` 反推会把基色塌成黑（**吸收态**）。
     * 现在两者独立：拖明度条**不动选点**，且**可逆**。
     */
    private val lightBar = LightnessBar(baseColor = { svToRgb(hue, sat, value) }) { v, dragging ->
        pickEventCount++
        if (!syncing) {
            lightness = (v * 100f).coerceIn(0f, 100f)
            syncFromModel(updateHue = false, dragging = dragging)
        } else {
            noteSyncBlocked("明度条")
        }
    }

    /**
     * **内容面板**（不含"确定/取消"）—— 供嵌入工具窗使用。
     *
     * 与 `init` 共用同一套控件实例与事件绑定，故两种形态行为一致。
     * 抽出它而不是让调用方 `add(dialog.contentPane)` 的原因：
     * `JDialog` 的 contentPane 被移走后对话框自身会空掉，且 `pack()` 语义混乱。
     *
     * ## ★ 本方法**保证返回已填充的面板**（2026-09-19 回归修复）
     *
     * 控件是在 [buildContentImpl] 里 `add` 进 [content] 的。若本方法只写
     * `= content`，则**在没人调 `buildContentImpl()` 时返回一个空面板**。
     *
     * ### 真机取证：这正是用户报的那个缺陷
     *
     * 上一版为避免 `by lazy { buildContentImpl() }` 的**无限递归**
     * （`content` 的初始化器 → `buildContentImpl` → 读 `content` → 再进初始化器），
     * 把 `content` 改成**直接 new** —— 但**唯一的调用点 `buildContentImpl()` 一起被删掉了**。
     *
     * 结果：`content` 恒为空 → 自绘取色窗**中间什么都没有**，只剩顶部一行提示文字
     * 和底部两个按钮。用户原话：
     *
     * > 「取色设置引出的自绘窗口中**根本就没有我想要的取色模块**」
     *
     * ### 为什么修法是"自填充"而不是"在 init 里补一句调用"
     *
     * 补一句调用**仍然是"靠人记得"** —— 下一次重构还会漏（这次就是这么漏的）。
     * 改成由本方法自己负责填充后，**"拿到空面板"这个状态在结构上不可达**：
     * 两条调用路径（对话框 `init` / `ToolWindows` 嵌入）都走本方法。
     *
     * > 纪律：**能靠结构消除的状态，不要靠纪律维持。**
     */
    fun buildContent(): JPanel {
        if (!contentPopulated) {
            contentPopulated = true
            buildContentImpl()
        }
        return content
    }

    /**
     * [buildContent] 的幂等标志 —— 保证面板只被填充一次。
     *
     * 两种形态（对话框自身 / 嵌入工具窗）都会调 [buildContent]，
     * 谁先调用谁触发填充，第二个调用直接复用。
     */
    private var contentPopulated = false

    /**
     * 面板本体。**直接 new，不能用 `by lazy { buildContentImpl() }`** ——
     * `buildContentImpl` 往它里面 `add(...)` 并返回它，
     * 用 lazy 会形成 `content → buildContentImpl → content` 的**无限递归**。
     *
     * 填充时机见 [buildContent]（**不要在别处再调 `buildContentImpl()`**）。
     */
    private val content: JPanel = JPanel(BorderLayout(0, 10))

    private fun buildContentImpl(): JPanel {
        // ── 上：色域板 + 色相条 ──
        val top = JPanel(BorderLayout(8, 0)).apply {
            add(sv, BorderLayout.CENTER)
            add(hueBar, BorderLayout.EAST)
        }
        content.add(top, BorderLayout.NORTH)

        // ── 中：明度条（**带标签**）────────────────────────────────────────────
        //
        // ⚠️ 2026-09-20 加标签：用户抱怨"**逻辑混乱**"，而**色域板的纵轴也是明度** ——
        //    两根控件调的是同一个观感维度，没有文字区分时根本分不清哪个是哪个
        //    （原来的症状之一就是"拖明度条，选点跟着跑"）。
        //    标签写明它调的是**整体明度**（基色 × 倍率），与"颜色本身的明度"区分开。
        content.add(
            JPanel(BorderLayout(8, 0)).apply {
                add(JLabel("整体明度"), BorderLayout.WEST)
                // ⚠️ 2026-09-20 加标签：用户抱怨"**逻辑混乱**"，而**色域板的纵轴也是明度** ——
                //    两根控件调的是同一个观感维度，没有文字区分时根本分不清哪个是哪个
                //    （原来的症状之一就是"拖明度条，选点跟着跑"）。
                //    标签写明它调的是**整体明度**（基色 × 倍率），与"颜色本身的明度"区分开。
                add(
                    JPanel(BorderLayout(8, 0)).apply {
                        add(JLabel("整体明度"), BorderLayout.WEST)
                        add(lightBar, BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.CENTER,
        )

        // ── 下：hex + RGB 三行 + 确定/取消 ──
        val bottom = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)

            val hexRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JLabel("Hex"))
                add(hexField)
                preview.preferredSize = Dimension(48, 22)
                add(preview)
            }
            add(hexRow)

            val names = listOf("Red", "Green", "Blue")
            for (i in 0..2) {
                val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                    channelLabels[i].text = names[i]
                    // ⚠️ 2026-09-19：**标签在前、字段在后** —— 原文案是「255 Red」，
                    //    读起来像"值是 255，单位 Red"。`Red 255` 才是通行写法。
                    add(channelLabels[i])
                    add(channelFields[i])
                }
                add(row)
            }

            // ── ⚠️ 2026-09-19：**删掉这里的「取消 / 确定」**（真机截图暴露）────────
            //
            // 用户截图里窗口底部**有两套按钮**：中间一组（取消/确定，字很淡）
            // 和底部一组（确定/关闭，清晰）。
            //
            // 根因：`buildContent()` 被**两种形态**共用 ——
            //   · **对话框形态**：`init` 自己在 SOUTH 加了「取消 / 确定」；
            //   · **嵌入工具窗形态**：`ColorToolWindow` 也在自己的 SOUTH 加了
            //     「确定 / 关闭」。
            // 而本方法**又**往 `bottom` 里加了一套 → 嵌入形态下**两套并存**。
            //
            // 故这一套**只属于对话框形态**，不该在共用内容里。删掉它：
            // 对话框形态仍有 `init` 里那套，嵌入形态则由工具窗自己那套负责。
            // ── 歌词展示栏：固定在选色组件**下方**（2026-09-19 用户要求）──
            //
            // 放在 bottom 的纵向盒子里（`bottom` 是 Y_AXIS 的 BoxLayout），
            // 故它位于 hex / RGB / 按钮**之后** = 窗口最下方，符合"固定在下方"。
            add(islandPreview)
            // ── 示例歌词的**出处与作者**（2026-09-22，用户要求）────────────────────
            //
            // 用户原话：「你是不是忘了我曾经要求过要**写明示例歌词的歌曲出处和作者**，你忘加了。」
            // 规格（同一轮给的）：文案 `Ceui -《今、歩き出す君へ。》`；
            //   · 位置 = 示例词岛**下方**、深浅色块范围**之外**、水平**正中**；
            //   · 作为文字**要能复制**；
            //   · **不参与示例词岛的渲染**。
            //
            // ## 为什么加在这里就同时满足前两条
            //
            // `bottom` 是 `Y_AXIS` 的 `BoxLayout`，而它紧跟在 `islandPreview` **之后** ——
            // 于是天然落在预览（含对比色块）**下方**；居中由
            // [SampleLyricCredit.sampleLyricCreditRow] 自带的两侧 `HorizontalGlue` 负责。
            //
            // ## 第三条（不参与渲染）为什么不用写代码
            //
            // 它是**兄弟组件**，不在 `PreviewSnapshot` 里、也不进 `IslandPreviewPane.paintComponent`
            // ⇒ 结构上就不可能被画进词岛。这正是 `SampleLyricCredit.kt` 单独成文件的原因。
            add(SampleLyricCredit.sampleLyricCreditRow())
        }
        content.add(bottom, BorderLayout.SOUTH)

        return content
    }

    /* 事件绑定 + 首次同步（两种形态共用）。 */
    //
     // **外观现代化**（2026-09-19）。
     //
     // 用户原话：「自绘窗口外观**过于陈旧**，怎么也得 **win10/win11 风格**吧？」
     //
     // ## 在纯 Swing 里能做什么、不能做什么（如实划界）
     //
     // **能做的**（本方法做的三件）：
     // 1. **跟随系统浅 / 深色** —— 读 `SystemTheme.isLight()`（它读注册表
     //    `AppsUseLightTheme`，失败降级为深色）。Win10/11 的"现代感"里
     //    **最大的一件就是跟随系统主题**，而这是零依赖就能拿到的。
     // 2. **扁平化按钮** —— 去掉焦点虚线框（`isFocusPainted = false`）、
     //    给统一的内边距与细边线，替掉 Metal LAF 那种带渐变与厚边框的按钮。
     // 3. **统一字体** —— 用用户设置的字体（用户：「自绘窗口使用当前使用的字体」），
     //    并**按控件原有字号等比缩放**（`deriveFont(原字号)`），
     //    这样"小字提示"仍然是小字、正文仍是正文，不会一刀切成同一个字号。
     //
     // **做不到的**：圆角窗口 / Mica 亚克力 / 原生控件观感 ——
     //   那些要么需要 Windows 11 的 DWM 接口（本项目已有 JNA，但 DWM 圆角对
     //   **无边框 Swing 窗口**不生效），要么需要引入一套第三方 LAF
     //   （FlatLaf 等，Apache-2.0）。**引入新依赖是产品决定，不是我能替你拍的** ——
     //   故这里不引，只用现有能力做到"跟随系统主题 + 扁平"。
     //
    /**
     * 把本窗刷成"跟随系统深浅主题"的样子。
     *
     * ⚠️ 2026-09-21：**实现已抽到 [applyToolWindowTheme]（唯一出口）** ——
     * 本函数只保留名字与调用点。理由见 [ToolWindowPalette] 的 KDoc：
     * 同一套配色此前在三个工具窗里各写一遍，于是"取色窗跟随了、字体窗没跟随"
     * 这种同族漏改发生了（BUG-108）。
     */
    private fun applyModernLook() =
        applyToolWindowTheme(contentPane, rootPane, fontProvider ?: toolWindowFontProvider())

    private fun wireEvents() {
        hexField.addActionListener { applyHex() }
        hexField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) = applyHex()
        })
        channelFields.forEachIndexed { i, f ->
            f.addActionListener { applyChannels() }
            f.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent) = applyChannels()
            })
            // 只允许数字（不静默吞掉：非数字直接不接受，输入框保持原值）
            f.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyTyped(e: java.awt.event.KeyEvent) {
                    if (!e.keyChar.isDigit() && e.keyChar != '\b') e.consume()
                }
            })
            @Suppress("UNUSED_PARAMETER") val unused = i
        }

        syncFromModel(updateHue = true)
    }

    init {
        // ⚠️ `initial` 是**已经乘过 lightness 的打包值**，故这里只取它的色相/饱和/明度
        //    作为"基色"的起点，而 `lightness` 由调用方单独给出（4 键制才有意义）。
        val hsv = rgbToHsv(initial)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        lightness = initialLightnessPercent.toFloat().coerceIn(0f, 100f)

        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout(0, 10)
        rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        add(buildContent(), BorderLayout.CENTER)

        // 对话框形态专属：底部"确定/取消"
        // ★ 2026-09-21（BUG-109）：`JButton` → `ThemeButton`（自绘，理由见 [ThemeButton]）。
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            add(ThemeButton("取消").apply { addActionListener { result = null; dispose() } })
            add(ThemeButton("确定").apply { addActionListener { result = packed(); dispose() } })
        }, BorderLayout.SOUTH)

        wireEvents()
        applyModernLook()
        pack()
        setLocationRelativeTo(owner)
        // ★ 2026-09-21：**标题栏必须在 peer 建立之后再刷一次**。
        //
        // `applyModernLook()` 在 `pack()` **之前**跑，而标题栏那条路走
        // `WindowInterop.setDarkTitleBar` → `hwndOf` → `if (!window.isDisplayable) return null`
        // → **构造期那次必然返回 false**（且**不报错**，属于静默失效）。
        // `pack()` 会建立 peer，`windowOpened` 才是真正生效的时机。
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowOpened(e: java.awt.event.WindowEvent) {
                syncNativeTitleBar(contentPane, dark = !ToolWindowPalette.isLight)
            }
        })
    }

    // ── 供「嵌入工具窗」使用的对外接口（2026-09-18 接线时加）──────────────
    // 这三个是 `ColorPicker`（旧内联版）对外暴露的最小面，接过来后调用方无需改逻辑。

    /** 当前颜色（打包 ARGB，**已含整体明度**）—— 这就是要写进配置的值。 */
    fun value(): Int = packed()

    /**
     * ★★★ 2026-10-14（**BUG-196**）：**刷新示例词岛的外观快照**。
     *
     * 用户报障（逐字）：「**预览词岛中没有应用当前设置。**」
     * （点名：频谱多色流光 · 溶边 · 背景不透明度 · 还有很多）
     *
     * ★ 根因 = **快照陈旧**：取色窗按 bucket **复用**（`ToolWindows.present` 走
     *   `existing.toFront()`），而快照是构造期一次性捕的 ⇒ ★ `windowOpened` 时
     *   必须**重捕一次**，否则"改设置 → 重开窗"看到的仍是**上一次**的观感 ✗
     *
     * ⚠️ **必须由调用方在 EDT 上捕好再传进来**（不在本方法里读设置）——
     *   这样本类对 `SettingsRuntime` 的依赖面**不扩大**，且探针可喂死数据 ✓
     *
     * @param next 已捕获好的快照（调用点通常传 `PreviewSnapshot.capture(settings)`）。
     */
    fun refreshPreviewSnapshot(next: PreviewSnapshot) {
        islandPreview.updateSnapshot(next)
    }

    /**
     * **基色**（不含整体明度）—— 4 键制颜色组要把它写进 `.r/.g/.b`，
     * 而把 [lightnessPercent] 写进 `.lightness`。
     *
     * 为什么要分开暴露：`IslandWindow` 的 `mixerColor` 是
     * `r × lightness/100` —— 若把**已压暗**的颜色写进 `.r/.g/.b` 再带上 `lightness`，
     * 就会**乘两次**（观感"越调越黑"，且再拖也回不去）。
     */
    fun baseRgb(): Int = svToRgb(hue, sat, value)

    /** 整体明度倍率（0..100）。 */
    fun lightnessPercent(): Int = lightness.roundToInt().coerceIn(0, 100)

    /**
     * 当前取色的**完整结果**（三个数一起给）。
     *
     * ⚠️ 调用方**不要**用 `value()` + `lightnessPercent()` 自己拼 —— 那需要反推，
     * 而反推正是"黑色吸收态"的成因。走本方法，口径只有一处。
     */
    fun picked(): PickedColor {
        val base = svToRgb(hue, sat, value)
        return PickedColor(packed(), base, lightnessPercent())
    }

    /**
     * 外部推来新值时刷新面板（订阅回调路径）。
     *
     * ## ⚠️ 判据是"**值相等就什么都不做**"，不是"我是不是正在发布"
     *
     * 原实现把守卫放在调用方（`ToolWindows.publishingFromSelf`），而订阅回调是
     * `SwingUtilities.invokeLater { … }` —— **回调真正执行时那个标志早已复位**，
     * 于是回灌照旧 → 拖动中值被往返误差改写 → **反复跳回黑端**（用户说的"来回闪烁"）。
     *
     * 现在判据落在**值**上：收到的就是我刚发出去的 → 无事可做。
     * 它与回调延迟无关、与订阅者数量无关，也不需要调用方配合。
     */
    fun setValueFromModel(rgb: Int) {
        if (rgb == lastPublishedPacked || selfPublishedPacked.contains(rgb)) return
        adopt(rgb, lightnessPercent = null)
    }

    /** 带整体明度的外部推值（4 键制颜色组用）。 */
    fun setValueFromModel(rgb: Int, lightnessPercent: Int) {
        // ⚠️⚠️ 2026-09-20 **修 P0：守卫比错了对象**（用户："取色点**不能连续拖动**…
        //      之后取色点就停留在这里无法拖动。**有时可以顺畅拖动，有时不行**"）。
        //
        // ## 原缺陷
        //
        // 原判据是 `rgb == lastPublished`，而 `lastPublished` 存的是 **`packed()`**
        // —— **已乘过整体明度**的最终色。但本重载收到的 `rgb` 是**基色**
        // （`composePackedArgb(r,g,b)`，不含明度）。于是只要 `整体明度 != 100`，
        // `基色 != 打包值` **恒成立** → 守卫**永远失效** →
        // `adopt(base, l)` 把 `sat`/`value` 从 8 位 RGB **反推**回来 →
        // 选点被拽离鼠标真实位置 → 表现为"拖不动 / 时好时坏"。
        //
        // 为什么"时好时坏"：整体明度恰好 100 时两者相等，守卫就"碰巧"生效。
        //
        // ## 修法：**同类相比**
        //
        // 单独存一份"我上次发出去的**基色**与**明度**"，与收到的两个参数逐一比对。
        // 这是本项目的既有纪律：**回灌守卫要用"比值"，不要用"标志位"**，
        // 而且**比值必须与收到的量同量纲**（这里就是踩了"量纲不同"）。
        // ★★★ 2026-10-10（**U11 复发**）：★ **同一个量纲缺陷以【相反方向】又出现了一次**。
        //
        // ## 本次现场（用户报障，逐字）
        //
        // > 「…**导致色相板拖动不了**，明度条也拖动不了。」
        // > 「我说的**拖不动**是指在**自绘窗口里拖动选点**…**色号自从第一次拖动后就没有变过**」
        // > （截图佐证：`Hex #000000` / `RGB 0/0/0`，而明度条停在 ~45%）
        //
        // ## 机制：**BUG-164 换了"收到的量纲"，而守卫还比着旧量纲**
        //
        // ★ BUG-164（同日）把 **3 键制**（描边色 / 边框色）的通道值从 `baseRgb` 改成 `packed`
        //   —— ★ 那个修复**本身是对的**（3 键制没有 `lightness` 键，明度必须烘进通道）。
        // ★★ 但**消费侧随之改变**：★ 3 键制的消费方（`KaraokeTextRenderer`）**直读三通道**
        //   ⇒ ★ 回灌进来的 `rgb` 现在是 **`packed`**（不再是 `base`）。
        // ★ 而本方法的守卫只比 `base` ⇒ ★★ **`packed != base`（只要整体明度 ≠ 100）恒成立**
        //   ⇒ ★ 守卫失效 ⇒ `adopt(packed, l)` ⇒ **从 packed 反推 `hue`/`sat`/`value`**
        //   ⇒ ★★ **用户刚拖到的位置被立刻覆盖** ⇒ **"拖不动"** ✗
        // ★ 且 ★ 明度 = 100 时 `packed == base` ⇒ 守卫"碰巧"生效
        //   ⇒ ★★ 与上面 2026-09-20 那次的"**时好时坏**"逐字一致 ✓
        //
        // ## 修法：守卫**同时接受两种量纲**（★ 因为消费侧确实有两种）
        //
        // | 键制 | 消费侧 | 回灌的 `rgb` |
        // |---|---|---|
        // | **4 键制**（歌词三色） | `mixerColor`：`base × lightness/100` | ★ **`base`** |
        // | **3 键制**（描边 / 边框） | **直读三通道** | ★ **`packed`** |
        //
        // ★ 故**两个都要判** —— ★ 沿用本项目纪律「回灌守卫要用**比值**，且**必须与收到的量同量纲**」；
        //   ★★ 而这里"收到的量"有**两种** ⇒ 守卫也要有**两条** ✓
        if (rgb == lastPublishedBase && lightnessPercent == lastPublishedLightness) return
        if (rgb == lastPublishedPacked && lightnessPercent == lastPublishedLightness) return
        // ⚠️ 2026-09-21：单槽只记得住**最新**那一次，而回声会**迟到** ——
        //    BUG-106 的形态就是"单槽已被下一次发布覆盖 → 旧回声被当成外部改色"。
        //    台账命中 = 这个值本窗发过 = 是回声（迟到多久都算）→ 忽略。
        if (selfPublishedBaseL.contains(ledgerKey(rgb, lightnessPercent))) return
        // ★ 2026-10-10（U11）：3 键制那条路回灌的是 `packed` ⇒ 也要查 packed 台账
        //   （★ 与上面不带明度的重载 `setValueFromModel(rgb)` 里的判据**同一条**）
        if (selfPublishedPacked.contains(rgb)) return
        adopt(rgb, lightnessPercent)
    }

    /** 聚焦并全选 hex 输入框 —— 工具窗打开时让用户能直接键入色号。 */
    fun focusHexField() {
        hexField.requestFocusInWindow()
        hexField.selectAll()
    }

    /**
     * 打包值 = **基色 × 整体明度**。
     *
     * ⚠️ 与 `SettingModel.composePackedArgb` **同一个公式**（那条链路是
     * `mixerColor` 的消费口径）。**不要在这里手写位运算** ——
     * 本项目已因"同一个位运算写两遍、只改一处"吃过多次亏（BUG-088/090）。
     */
    private fun packed(): Int {
        val base = svToRgb(hue, sat, value)
        return composePackedArgb(
            (base shr 16) and 0xFF,
            (base shr 8) and 0xFF,
            base and 0xFF,
            lightness.roundToInt().coerceIn(0, 100),
        )
    }

    /**
     * 模型 → 控件。
     *
     * @param updateHue 是否连色相条一起刷新。
     * @param dragging **本次变化是否来自"拖动中"的取色事件**（2026-09-21 新增）。
     *
     * ### 它同时管两件事（同一件事的两面：拖动中少做事）
     *
     * 1. **跳过 hex / RGB 四个文本框的写入**。`mouseDragged` 按系统鼠标采样率派发
     *    （常见 125 Hz），而 `JTextField.setText` 会派发 `DocumentEvent` +
     *    `revalidate` + `repaint` —— 每秒数百次文本重排是纯浪费：
     *    文本框**不是**拖动的视觉主线（主线是选点 / 明度条 / 示例预览）。
     * 2. **节流下游发布**（见 [publishPreview]）—— 那才是"不跟手"的主因。
     *
     * ⚠️ 松手时 `mouseReleased → pick(dragging = false)` 会**不过闸**地补发一次，
     * 所以两条都不会让用户读到"拖动途中的中间值"。
     */
    private fun syncFromModel(updateHue: Boolean, dragging: Boolean = false) {
        syncing = true
        try {
            // 基色**只算一次**，最终色 = 基色 × 整体明度。两者一起交给回调。
            val base = svToRgb(hue, sat, value)
            val l = lightnessPercent()
            val c = composePackedArgb((base shr 16) and 0xFF, (base shr 8) and 0xFF, base and 0xFF, l)
            // ★ 拖动中跳过（见 `dragging` 的说明）—— 松手会补。
            if (!dragging) {
                hexField.text = c.toHexString()
                channelFields[0].text = ((c shr 16) and 0xFF).toString()
                channelFields[1].text = ((c shr 8) and 0xFF).toString()
                channelFields[2].text = (c and 0xFF).toString()
            }
            // ⚠️ 色域板的选点用**基色**（`sat`/`value`），明度条用 `lightness` ——
            //    两者独立，拖明度条**不会**移动选点（这正是用户抱怨的那一点）。
            sv.sat = sat; sv.value = value
            lightBar.value = (lightness / 100f).coerceIn(0f, 1f)
            if (updateHue) hueBar.hue = hue
            // ⚠️ 2026-09-21：**不再整块 `repaint()`** —— 各组件自己知道"这次变的是什么"，
            //    只重绘真正变了的那一小块（色相条只有游标在动；色域板只在色相变化时整块重绘）。
            //    见 [SvPanel.refreshFromModel] / [HueBar.refreshFromModel] /
            //    [LightnessBar.refreshFromModel] 的 KDoc。
            preview.repaint()
            sv.refreshFromModel(); hueBar.refreshFromModel(); lightBar.refreshFromModel()
            islandPreview.applyEditedColor(c)   // 示例预览实时跟随
            // 供 `setValueFromModel` 判"这就是我刚发的"。
            // ⚠️ **三个量都要记**，且分别与同量纲的入参比对 —— 只记 `c`（打包值）
            //    会让颜色组那条路（收**基色**）的守卫恒失效，见 [lastPublishedPacked]。
            // ⚠️ 2026-09-21：改为**同时**记进台账（单槽会被下一次发布覆盖，见
            //    [selfPublishedPacked] 的 KDoc —— 那是 BUG-106 的根因）。
            //    `transmitted` 必须传 `onPreview != null`：没发送就没有回声，
            //    记账会让台账出现"永远不会被消费"的条目（见 [rememberSelfPublished]）。
            rememberSelfPublished(c, base, l, transmitted = onPreview != null)
            // ⚠️ 交**三个数**（见 [PickedColor]）：接收方零反推、零回读 ——
            //    这条回调会在**本窗构造期间**就触发一次，接收方那时还没初始化完自己。
            publishPreview(c, base, l, dragging)
        } finally {
            syncing = false
        }
    }

    /**
     * 把当前颜色交给 [onPreview] —— **拖动中按 [DRAG_PUBLISH_INTERVAL_MS] 节流**。
     *
     * ## 为什么闸门在这里，而不是在 `SettingsRuntime.publish` 里
     *
     * `publish` 是**通用**写路径（菜单、设置页、字体窗都在用），在那里丢事件会让
     * **每一个**调用方都变得不可预测；而"取色器拖动"是一个**已知的、局部的**
     * 高频源 —— 节流必须贴着它的源头做，影响面才只有一个。
     * 这也与 `SettingsRuntime.publish` 的 KDoc 一致（那里明确写了"节流不在本方法里"）。
     *
     * ## 三条分支
     *
     * | 情形 | 行为 |
     * | --- | --- |
     * | `dragging = false`（按下 / 松手 / 非拖动路径） | **不过闸，立刻发**，并清掉攒着的值 |
     * | `dragging = true` 且距上次 ≥ 间隔 | 立刻发 |
     * | `dragging = true` 且距上次 < 间隔 | 只记下最新值，起一个一次性定时器到点补发 |
     *
     * 第三条分支里的定时器保证"**拖到一半停住不动**"时画面也会跟上 ——
     * 只靠"松手补发"是不够的（用户可能一直按着不动）。
     */
    private fun publishPreview(c: Int, base: Int, l: Int, dragging: Boolean) {
        val cb = onPreview ?: return
        val picked = PickedColor(c, base, l)
        if (!dragging) {
            // 松手 / 非拖动：**必须立刻发**。否则词岛与文本框会停在拖动途中的中间值上。
            stopFlushTimer()
            pendingPreview = null
            lastPublishNanos = System.nanoTime()
            publishCount++
            cb(picked)
            // ★ 手势结束 → **延后一轮**清空"本窗发布过的值"台账（见 [scheduleLedgerClear]）。
            //   放在 `cb(picked)` **之后**是关键：`cb` 会把本次发布的回声排进 EDT，
            //   它们必须排在清表任务**之前**，否则那条回声会被当成外部改色。
            scheduleLedgerClear()
            return
        }
        val now = System.nanoTime()
        val elapsedMs = if (lastPublishNanos == 0L) Long.MAX_VALUE
        else (now - lastPublishNanos) / 1_000_000L
        if (elapsedMs >= DRAG_PUBLISH_INTERVAL_MS) {
            lastPublishNanos = now
            pendingPreview = null
            publishCount++
            cb(picked)
        } else {
            pendingPreview = picked
            ensureFlushTimer((DRAG_PUBLISH_INTERVAL_MS - elapsedMs).coerceAtLeast(1L))
        }
    }

    /** 起一个**一次性**补发定时器（已在跑就让它跑完 —— 它会取走最新的 [pendingPreview]）。 */
    private fun ensureFlushTimer(delayMs: Long) {
        val running = previewFlushTimer
        if (running != null && running.isRunning) return
        val timer = javax.swing.Timer(delayMs.toInt()) {
            previewFlushTimer = null
            val queued = pendingPreview
            val cb = onPreview
            if (queued != null && cb != null) {
                pendingPreview = null
                lastPublishNanos = System.nanoTime()
                publishCount++
                cb(queued)
            }
        }
        timer.isRepeats = false
        previewFlushTimer = timer
        timer.start()
    }

    private fun stopFlushTimer() {
        previewFlushTimer?.stop()
        previewFlushTimer = null
    }

    /**
     * 销毁时**必须停掉补发定时器**。
     *
     * 不这么做的话，一个已 dispose 的窗仍会持有一个已排队的 `javax.swing.Timer` ——
     * 它到点会回调 `onPreview`（进而 `publish` 到运行时），而那个回调属于一个
     * **用户已经关掉的窗**。本项目的既有纪律：**销毁路径上不得留下会再触发的回调**。
     */
    override fun dispose() {
        stopFlushTimer()
        pendingPreview = null
        // 台账同样不能留：一个已 dispose 的窗不需要再判"这是不是我的回声"。
        selfPublishedPacked.clear()
        selfPublishedBaseL.clear()
        super.dispose()
    }

    /** hex 输入 → 模型。非法输入**保持上一个合法值**，并把文本回滚（不静默吞掉）。 */
    private fun applyHex() {
        if (syncing) return
        val parsed = parseHexOrNull(hexField.text.trim())
        if (parsed == null) {
            syncFromModel(updateHue = false)     // 回滚显示，给出可见反馈
            return
        }
        // ── ⚠️ 2026-09-19：**与当前颜色一致时不采纳**（修「选点乱动 / 标点闪烁跳动」）──
        //
        // 用户原话：「自绘窗口内取色板和明度条在选色时**标点和边框会闪烁跳动**。
        //   **选点乱动**。改色体验极其糟糕。」
        //
        // ## 根因：RGB ↔ HSV 的**往返误差**
        //
        // `adopt` 会把 `sat` / `value` **从 RGB 反推**（`rgbToHsv`）。
        // 而 hex 只有 **8 位/通道**精度 —— 于是"采纳一次当前颜色"会让
        // `sat`/`value` 落到**略微不同的浮点值**上 → 选点**抖一个像素**。
        //
        // 而这条路径**每次失焦都会走**：用户点一下色域板 → hex 输入框失焦 →
        // `focusLost → applyHex()` → 抖一下；拖动中反复触发 → **看起来就是"乱动/闪烁"**。
        //
        // ## 判据
        //
        // `parsed == packed()` 说明"文本就是当前颜色" —— 此时采纳**没有任何信息量**，
        // 只会引入一次往返误差。**直接返回**。
        if (parsed == packed()) return
        // 用户键入的是**最终色**（hex 框显示的就是它）→ 直接当成基色、明度归 100。
        // ⚠️ 不能"保留明度、把键入值当基色"：那样最终色会立刻变成 `键入值 × 明度/100`，
        //    用户会看到"我输的值被改了"（本项目明确禁止的静默改写）。
        adopt(parsed, lightnessPercent = 100)
    }

    private fun applyChannels() {
        if (syncing) return
        val v = channelFields.map { it.text.trim().toIntOrNull() }
        if (v.any { it == null || it !in 0..255 }) {
            syncFromModel(updateHue = false)
            return
        }
        // ⚠️ 2026-09-19：**必须带 alpha**，否则下面那条判据**永远不成立** ——
        //    而那条判据正是上一轮为修"选点乱动"加的（`rgb == packed()` 时直接返回）。
        //    `packed()` 现在带 alpha，本行若不带 → 恒不相等 → **去抖逻辑被自己打死**。
        //    这就是"改了 A、B 把 A 的前提破坏掉"的形态，故这里也走同一个纯函数。
        val rgb = composePackedArgb(v[0]!!, v[1]!!, v[2]!!)
        if (rgb == packed()) return
        // 与 `applyHex` 同一条语义：三个通道框里显示的是**最终色**。
        adopt(rgb, lightnessPercent = 100)
    }

    /**
     * 采纳一个颜色值。
     *
     * **色相只在"该色不是灰色"时才更新** —— 见 [rgbToHsv] 的 KDoc。
     * 这样"把饱和度拖到 0 再拖回来"能回到原来的色相，而不是跳成红色。
     *
     * ## ★ 绝不从 [rgb] 反推 [lightness]（这是"黑色吸收态"的成因）
     *
     * `lightness` 是**乘性倍率**，一旦从"已被压暗的值"反推就会：
     * 拖到黑 → `rgbToHsv(黑)` 得 `sat=0, value=0` → 基色塌成纯黑 → **再也回不来**。
     * 故本函数的规则是：
     *   · `lightnessPercent != null` → **直接用给定的**（调用方知道真实值）；
     *   · `lightnessPercent == null` → **保留当前 `lightness` 不变**。
     * 两条路都不会去猜。
     *
     * @param rgb 颜色。给 `lightnessPercent` 时它是**基色**；不给时它是**最终色**。
     * @param lightnessPercent 整体明度倍率 0..100；`null` = 保持现值。
     */
    private fun adopt(rgb: Int, lightnessPercent: Int?) {
        // ── ★ 可观测计数（2026-09-20 加）────────────────────────────────────────
        //
        // 为什么需要它：`ColorPickerProbe` 9.5b 原判据是"回灌后**状态不变**"，
        // 但那条断言**不可证伪** —— 纯红（`0xFFFF0000`）在
        // `HSV → 8bit RGB → HSV` 往返上是**精确可逆**的，所以即使守卫失效、
        // 本函数真的跑了，状态也**没变** → 断言照样绿。
        // **变异测试实测**：注入旧守卫后 9.5b 仍绿，红的是另一条（9.2）。
        //
        // 现在改成数"本函数被调用了几次" —— 守卫失效 ⇒ 这里必然递增 ⇒ **必红**。
        // 这是"不可证伪的检查等于没有检查"那条纪律的直接落地。
        adoptCount++
        // ⚠️ 2026-09-19：**入口归一化** —— 本函数是"外部值进入取色器"的唯一闸口
        //    （hex 解析 / RGB 字段 / 订阅回灌 `setValueFromModel` 都走它）。
        //    在**这里**补 alpha，比在每个调用点各补一次更可靠：
        //    少补一处就是又一次"`#00000000`"。`or 0xFF000000` 对已带 alpha 的值幂等。
        val opaque = rgb or 0xFF000000.toInt()
        val hsv = rgbToHsv(opaque)
        if (hsv[1] > 0.0001f) hue = hsv[0]
        sat = hsv[1]; value = hsv[2]
        if (lightnessPercent != null) lightness = lightnessPercent.toFloat().coerceIn(0f, 100f)
        syncFromModel(updateHue = true)
    }

    /** 打开窗并等待结果；用户取消返回 `null`。 */
    fun showAndGet(): Int? {
        isVisible = true
        return result
    }

    companion object {
        /**
         * 拖动中**下游发布**的最小间隔（毫秒）。
         *
         * `33` ⇒ **≈30 Hz**。取这个数的理由：
         *  · 30 Hz 是"画面连续"的公认门槛（电影 24、常见 UI 动画 30/60）——
         *    再高对**肉眼**没有可感知的收益，而每高一次就多占一份 EDT；
         *  · 鼠标采样率常见 125 Hz（8 ms）、高刷鼠标可达 1000 Hz（1 ms），
         *    故这道闸门把下游工作量压到 **1/4 ~ 1/33**；
         *  · 与词岛自身的动画帧率同量级 —— 下游**本来**就是一帧一帧画的，
         *    比帧率更密的发布**不可能**多画出任何一帧。
         *
         * ⚠️ **它只约束"拖动中"**（见 [publishPreview]）：松手那一次不过闸。
         * 探针（`ColorPickerDragProbe`）按它算阈值，故它是 `public const`。
         */
        const val DRAG_PUBLISH_INTERVAL_MS = 33L

        /**
         * **拖色相板时允许的最小饱和度**（`0..1`）—— 低于它就抬到它。
         *
         * ## 为什么必须有（★ 用户报障，逐字）
         *
         * > 「…**导致色相板拖动不了**，明度条也拖动不了。」
         *
         * ★ `svToRgb` 是标准 HSV→RGB：`c = v × s`、`m = v − c`
         * ⇒ ★★ **`sat = 0` 时 `r = g = b = v`** ⇒ **任何 hue 都得同一块灰**。
         * ★ 描边色/边框色这类**深灰黑**的初始色反推出来的 `sat` 极低
         * （真机日志：`picked=#ffe7d3d3` ⇒ `sat ≈ 0.087`）⇒ ★ **拖色相板几乎不改变通道**
         * ⇒ ★ 用户看到「拖不动」✗
         *
         * ★ 取 0.25：★ 足够让**肉眼明确看出颜色**，又不至于一拖就"跳成艳色" ✓
         * ★ 与主流取色器一致（**在灰度上拖色相应当上色**）✓
         *
         * ⚠️ ★ **必须 `public`** —— ★ 探针是独立编译模块，**看不到 `internal`**
         *   （与 `colorGroupChannelRgb` / `composePackedArgb` 同口径）✓
         */
        const val HUE_DRAG_MIN_SAT = 0.25f

        /**
         * "本窗发布过的值"台账的上限（见 [selfPublishedPacked]）。
         *
         * 按 [DRAG_PUBLISH_INTERVAL_MS] = 33 ms 算，512 个值 ≈ **17 秒连续拖动** ——
         * 远超任何真实手势；而手势结束会整表清空，故它不会跨手势累积。
         * 取 512 而不是更小，是为了让"极旧回声"这类边角也不受影响。
         *
         * 探针按它算阈值，故它是 `public const`。
         */
        const val SELF_PUBLISH_LEDGER_MAX = 512

        /** 便捷入口：在 EDT 上打开并返回选中色。 */
        fun pick(owner: Window?, initial: Int, title: String): Int? {
            var out: Int? = null
            if (SwingUtilities.isEventDispatchThread()) {
                out = ColorPickerDialog(owner, initial, title).showAndGet()
            } else {
                SwingUtilities.invokeAndWait {
                    out = ColorPickerDialog(owner, initial, title).showAndGet()
                }
            }
            return out
        }
    }
}
