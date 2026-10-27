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

fun svToRgb(hue: Float, sat: Float, value: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f / 60f
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

    return composePackedArgb(r, g, b)
}

fun rgbToHsv(packed: Int): FloatArray {
    val r = ((packed shr 16) and 0xFF) / 255f
    val g = ((packed shr 8) and 0xFF) / 255f
    val b = (packed and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min

    val hue = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val h = if (hue < 0f) hue + 360f else hue
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(h, s, max)
}

private class SvPanel(
    private val hueProvider: () -> Float,

    private val onPick: (sat: Float, value: Float, dragging: Boolean) -> Unit,
) : JPanel() {
    var sat = 0f; var value = 1f
    private var cache: BufferedImage? = null
    private var cacheHue = -1f

    private var lastCursorBand: java.awt.Rectangle? = null

    init {
        preferredSize = Dimension(240, 200)
        isOpaque = true
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pick(e)
            override fun mouseDragged(e: MouseEvent) = pick(e)

            override fun mouseReleased(e: MouseEvent) = pick(e)

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

    fun refreshFromModel() {
        if (cache == null || cacheHue != hueProvider()) {

            lastCursorBand = cursorBand()
            repaint()
        } else {
            repaintCursor()
        }
    }

    private fun repaintCursor() {
        val fresh = cursorBand()
        val band = lastCursorBand?.let { it.union(fresh) } ?: fresh
        lastCursorBand = fresh
        repaint(band.x, band.y, band.width, band.height)
    }

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

        g2.color = parent?.background ?: background
        g2.fillRect(0, 0, w, h)

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

        g2.drawImage(cache, 0, 0, w, h, null)

        val cx = (sat * w).roundToInt().coerceIn(0, w - 1)
        val cy = ((1f - value) * h).roundToInt().coerceIn(0, h - 1)
        g2.color = Color(0, 0, 0, 140)
        g2.drawOval(cx - 6, cy - 6, 12, 12)
        g2.color = Color.WHITE
        g2.drawOval(cx - 5, cy - 5, 10, 10)
    }
}

private class HueBar(

    private val onPick: (hue: Float, dragging: Boolean) -> Unit,
) : JPanel() {
    var hue = 0f
    private var cache: BufferedImage? = null

    private var cacheKey = -1

    private var lastMarkerBand: java.awt.Rectangle? = null

    init {
        preferredSize = Dimension(28, 200)
        isOpaque = true
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pick(e)
            override fun mouseDragged(e: MouseEvent) = pick(e)

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

    fun refreshFromModel() = repaintMarker()

    private fun repaintMarker() {
        if (width <= 0 || height <= 0) return
        val fresh = markerBand()
        val band = lastMarkerBand?.let { it.union(fresh) } ?: fresh
        lastMarkerBand = fresh
        repaint(band.x, band.y, band.width, band.height)
    }

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

        g2.drawImage(cache, 0, 0, w, h, null)

        val cy = (hue / 360f * h).roundToInt().coerceIn(0, h - 1)
        g2.color = Color(0, 0, 0, 140)
        g2.fillRect(0, cy - 2, w, 4)
        g2.color = Color.WHITE
        g2.fillRect(0, cy - 1, w, 2)
    }
}

private class LightnessBar(

    private val baseColor: () -> Int,

    private val onPick: (v: Float, dragging: Boolean) -> Unit,
) : JPanel() {
    var value = 1f

    private var lastBaseColor = Int.MIN_VALUE

    private var lastMarkerBand: java.awt.Rectangle? = null

    init {
        preferredSize = Dimension(240, 24)
        isOpaque = true
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pick(e)
            override fun mouseDragged(e: MouseEvent) = pick(e)

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

    fun refreshFromModel() {
        if (lastBaseColor != baseColor()) {

            lastMarkerBand = markerBand()
            repaint()
        } else {
            repaintMarker()
        }
    }

    private fun repaintMarker() {
        if (width <= 0 || height <= 0) return
        val fresh = markerBand()
        val band = lastMarkerBand?.let { it.union(fresh) } ?: fresh
        lastMarkerBand = fresh
        repaint(band.x, band.y, band.width, band.height)
    }

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

        g2.color = parent?.background ?: background
        g2.fillRect(0, 0, w, h)

        val barH = h - 8

        val base = baseColor()
        lastBaseColor = base
        g2.paint = GradientPaint(
            0f, 0f, Color.BLACK,
            w.toFloat(), 0f, Color(base or 0xFF000000.toInt()),
        )
        g2.fillRoundRect(0, 4, w, barH, barH, barH)

        val markerR = 7
        val cx = (value * w).roundToInt().coerceIn(markerR, (w - markerR - 1).coerceAtLeast(markerR))
        val cy = h / 2
        g2.color = Color(0, 0, 0, 150)
        g2.fillOval(cx - 7, cy - 7, 14, 14)
        g2.color = Color.WHITE
        g2.fillOval(cx - 6, cy - 6, 12, 12)
    }
}

data class PickedColor(
    val packed: Int,
    val baseRgb: Int,
    val lightnessPercent: Int,
)

class ColorPickerDialog(
    owner: Window?,
    initial: Int,
    title: String,

    private val onPreview: ((PickedColor) -> Unit)? = null,

    private val fontProvider: (() -> java.awt.Font)? = null,

    private val initialLightnessPercent: Int = 100,

    private val previewTargetKey: String = "",

    private val previewSnapshot: PreviewSnapshot? = null,
) : JDialog(owner, title, ModalityType.MODELESS) {

    private var hue: Float
    private var sat: Float
    private var value: Float

    private var lightness: Float

    private var result: Int? = null

    private var lastPublishedPacked: Int? = null
    private var lastPublishedBase: Int? = null
    private var lastPublishedLightness: Int? = null

    private val selfPublishedPacked = LinkedHashSet<Int>()
    private val selfPublishedBaseL = LinkedHashSet<Long>()

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

    private fun scheduleLedgerClear() {
        SwingUtilities.invokeLater {
            selfPublishedPacked.clear()
            selfPublishedBaseL.clear()
        }
    }

    private fun ledgerKey(base: Int, l: Int): Long =
        (base.toLong() shl 8) or (l.toLong() and 0xFFL)

    var adoptCount = 0
        private set

    private var syncBlockedCount = 0

    private fun noteSyncBlocked(where: String) {
        syncBlockedCount++
        if (syncBlockedCount <= 8) {
        }
    }

    private var syncing = false

    var publishCount = 0
        private set

    var pickEventCount = 0

        private set

    private var lastPublishNanos = 0L

    private var pendingPreview: PickedColor? = null

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

            if (sat < HUE_DRAG_MIN_SAT) sat = HUE_DRAG_MIN_SAT
            syncFromModel(updateHue = false, dragging = dragging)
        } else {
            noteSyncBlocked("色相条")
        }
    }

    private val lightBar = LightnessBar(baseColor = { svToRgb(hue, sat, value) }) { v, dragging ->
        pickEventCount++
        if (!syncing) {
            lightness = (v * 100f).coerceIn(0f, 100f)
            syncFromModel(updateHue = false, dragging = dragging)
        } else {
            noteSyncBlocked("明度条")
        }
    }

    fun buildContent(): JPanel {
        if (!contentPopulated) {
            contentPopulated = true
            buildContentImpl()
        }
        return content
    }

    private var contentPopulated = false

    private val content: JPanel = JPanel(BorderLayout(0, 10))

    private fun buildContentImpl(): JPanel {

        val top = JPanel(BorderLayout(8, 0)).apply {
            add(sv, BorderLayout.CENTER)
            add(hueBar, BorderLayout.EAST)
        }
        content.add(top, BorderLayout.NORTH)

        content.add(
            JPanel(BorderLayout(8, 0)).apply {
                add(JLabel("整体明度"), BorderLayout.WEST)

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

                    add(channelLabels[i])
                    add(channelFields[i])
                }
                add(row)
            }

            add(islandPreview)

            add(SampleLyricCredit.sampleLyricCreditRow())
        }
        content.add(bottom, BorderLayout.SOUTH)

        return content
    }

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

        val hsv = rgbToHsv(initial)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        lightness = initialLightnessPercent.toFloat().coerceIn(0f, 100f)

        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout(0, 10)
        rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        add(buildContent(), BorderLayout.CENTER)

        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            add(ThemeButton("取消").apply { addActionListener { result = null; dispose() } })
            add(ThemeButton("确定").apply { addActionListener { result = packed(); dispose() } })
        }, BorderLayout.SOUTH)

        wireEvents()
        applyModernLook()
        pack()
        setLocationRelativeTo(owner)

        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowOpened(e: java.awt.event.WindowEvent) {
                syncNativeTitleBar(contentPane, dark = !ToolWindowPalette.isLight)
            }
        })
    }

    fun value(): Int = packed()

    fun refreshPreviewSnapshot(next: PreviewSnapshot) {
        islandPreview.updateSnapshot(next)
    }

    fun baseRgb(): Int = svToRgb(hue, sat, value)

    fun lightnessPercent(): Int = lightness.roundToInt().coerceIn(0, 100)

    fun picked(): PickedColor {
        val base = svToRgb(hue, sat, value)
        return PickedColor(packed(), base, lightnessPercent())
    }

    fun setValueFromModel(rgb: Int) {
        if (rgb == lastPublishedPacked || selfPublishedPacked.contains(rgb)) return
        adopt(rgb, lightnessPercent = null)
    }

    fun setValueFromModel(rgb: Int, lightnessPercent: Int) {

        if (rgb == lastPublishedBase && lightnessPercent == lastPublishedLightness) return
        if (rgb == lastPublishedPacked && lightnessPercent == lastPublishedLightness) return

        if (selfPublishedBaseL.contains(ledgerKey(rgb, lightnessPercent))) return

        if (selfPublishedPacked.contains(rgb)) return
        adopt(rgb, lightnessPercent)
    }

    fun focusHexField() {
        hexField.requestFocusInWindow()
        hexField.selectAll()
    }

    private fun packed(): Int {
        val base = svToRgb(hue, sat, value)
        return composePackedArgb(
            (base shr 16) and 0xFF,
            (base shr 8) and 0xFF,
            base and 0xFF,
            lightness.roundToInt().coerceIn(0, 100),
        )
    }

    private fun syncFromModel(updateHue: Boolean, dragging: Boolean = false) {
        syncing = true
        try {

            val base = svToRgb(hue, sat, value)
            val l = lightnessPercent()
            val c = composePackedArgb((base shr 16) and 0xFF, (base shr 8) and 0xFF, base and 0xFF, l)

            if (!dragging) {
                hexField.text = c.toHexString()
                channelFields[0].text = ((c shr 16) and 0xFF).toString()
                channelFields[1].text = ((c shr 8) and 0xFF).toString()
                channelFields[2].text = (c and 0xFF).toString()
            }

            sv.sat = sat; sv.value = value
            lightBar.value = (lightness / 100f).coerceIn(0f, 1f)
            if (updateHue) hueBar.hue = hue

            preview.repaint()
            sv.refreshFromModel(); hueBar.refreshFromModel(); lightBar.refreshFromModel()
            islandPreview.applyEditedColor(c)

            rememberSelfPublished(c, base, l, transmitted = onPreview != null)

            publishPreview(c, base, l, dragging)
        } finally {
            syncing = false
        }
    }

    private fun publishPreview(c: Int, base: Int, l: Int, dragging: Boolean) {
        val cb = onPreview ?: return
        val picked = PickedColor(c, base, l)
        if (!dragging) {

            stopFlushTimer()
            pendingPreview = null
            lastPublishNanos = System.nanoTime()
            publishCount++
            cb(picked)

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

    override fun dispose() {
        stopFlushTimer()
        pendingPreview = null

        selfPublishedPacked.clear()
        selfPublishedBaseL.clear()
        super.dispose()
    }

    private fun applyHex() {
        if (syncing) return
        val parsed = parseHexOrNull(hexField.text.trim())
        if (parsed == null) {
            syncFromModel(updateHue = false)
            return
        }

        if (parsed == packed()) return

        adopt(parsed, lightnessPercent = 100)
    }

    private fun applyChannels() {
        if (syncing) return
        val v = channelFields.map { it.text.trim().toIntOrNull() }
        if (v.any { it == null || it !in 0..255 }) {
            syncFromModel(updateHue = false)
            return
        }

        val rgb = composePackedArgb(v[0]!!, v[1]!!, v[2]!!)
        if (rgb == packed()) return

        adopt(rgb, lightnessPercent = 100)
    }

    private fun adopt(rgb: Int, lightnessPercent: Int?) {

        adoptCount++

        val opaque = rgb or 0xFF000000.toInt()
        val hsv = rgbToHsv(opaque)
        if (hsv[1] > 0.0001f) hue = hsv[0]
        sat = hsv[1]; value = hsv[2]
        if (lightnessPercent != null) lightness = lightnessPercent.toFloat().coerceIn(0f, 100f)
        syncFromModel(updateHue = true)
    }

    fun showAndGet(): Int? {
        isVisible = true
        return result
    }

    companion object {

        const val DRAG_PUBLISH_INTERVAL_MS = 33L

        const val HUE_DRAG_MIN_SAT = 0.25f

        const val SELF_PUBLISH_LEDGER_MAX = 512

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
