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

public const val FALLBACK_ICON_SIZE: Int = 32

public const val MIN_ICON_SIZE: Int = 16

public const val MAX_ICON_SIZE: Int = 64

public const val TRAY_SUPERSAMPLE: Int = 2

public const val OUTLINE_STROKE_RATIO: Float = 0.02f

public fun trayIconSizePx(): Int {
    val logical = runCatching { SystemTray.getSystemTray().trayIconSize.width }
        .getOrNull()
        ?.takeIf { it > 0 }
        ?: FALLBACK_ICON_SIZE

    val scale = screenScale()
    val physical = kotlin.math.ceil(logical * scale).toInt()
    return physical.coerceIn(MIN_ICON_SIZE, MAX_ICON_SIZE)
}

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

public data class PillOutline(
    val leftRatio: Float,
    val topRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val arcRatio: Float,
)

public val PILL_OUTLINE_RATIO: PillOutline = PillOutline(
    leftRatio = 2f / 32f,
    topRatio = 5f / 32f,
    widthRatio = 27f / 32f,
    heightRatio = 21f / 32f,
    arcRatio = 12f / 32f,
)

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

public fun glyphPolygonsFor(sizePx: Int, playing: Boolean): Pair<IntArray, IntArray> {
    val unit = sizePx / 32f

    return if (playing) {

        val w = (6f * unit).toInt().coerceAtLeast(2)
        val h = (14f * unit).toInt()
        val arc = (2f * unit).toInt()
        Pair(
            intArrayOf((9f * unit).toInt(), (9f * unit).toInt(), w, h, arc),
            intArrayOf((18f * unit).toInt(), (9f * unit).toInt(), w, h, arc),
        )
    } else {

        val x12 = (11f * unit).toInt()
        val x23 = (24f * unit).toInt()
        Pair(
            intArrayOf(x12, x12, x23),
            intArrayOf((8f * unit).toInt(), (24f * unit).toInt(), (16f * unit).toInt()),
        )
    }
}

internal class IslandTray(    private val settings: SettingsRuntime?,

    private var window: IslandWindow?,

    private val playbackActions: PlaybackActions = PlaybackActions.NONE,
) : AutoCloseable {

    @Volatile
    private var tray: TrayIcon? = null

    @Volatile
    private var playing: Boolean = false

    private var pendingSingleClick: javax.swing.Timer? = null

    private val doubleClickIntervalMs: Int by lazy {
        runCatching {
            (java.awt.Toolkit.getDefaultToolkit()
                .getDesktopProperty("awt.multiClickInterval") as? Int)
                ?.takeIf { it > 0 }
        }.getOrNull() ?: FALLBACK_DOUBLE_CLICK_MS
    }

    val isInstalled: Boolean get() = tray != null

    fun updateWindow(next: IslandWindow?) {
        window = next
    }

    fun install() {
        if (tray != null) return
        runCatching {
            if (!SystemTray.isSupported()) {
                return
            }

            val icon = createTrayIconImage(playing)
            val created = TrayIcon(icon, tooltipFor(playing)).apply {

                isImageAutoSize = true
                installListeners(this)
            }
            SystemTray.getSystemTray().add(created)
            tray = created
        }.onFailure {

        }
    }

    fun onPlaybackState(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        val current = tray ?: return
        runCatching {
            current.image = createTrayIconImage(playing)
            current.toolTip = tooltipFor(playing)
        }.onFailure {

        }
    }

    private fun togglePlayback() {
        runCatching {
            if (playing) playbackActions.pause() else playbackActions.play()
        }.onFailure {
        }
    }

    private fun installListeners(target: TrayIcon) {

        target.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {

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

    private fun onTrayClick(clickCount: Int) {
        when {

            clickCount >= 2 -> {
                pendingSingleClick?.stop()
                pendingSingleClick = null
                skipToNext()
            }

            clickCount == 1 -> {

                pendingSingleClick?.stop()
                pendingSingleClick = javax.swing.Timer(doubleClickIntervalMs) {
                    pendingSingleClick = null
                    togglePlayback()
                }.apply {

                    isRepeats = false
                    start()
                }
            }
        }
    }

    private fun skipToNext() {
        runCatching { playbackActions.next() }.onFailure {
        }
    }

    private fun showMenu(event: MouseEvent) {
        IslandPopupMenu.showAtScreen(settings, window, event.xOnScreen, event.yOnScreen)
    }

    override fun close() {

        pendingSingleClick?.stop()
        pendingSingleClick = null

        val current = tray ?: return
        tray = null
        runCatching { SystemTray.getSystemTray().remove(current) }
            .onFailure {  }
    }

    private companion object {

        const val TRAY_TOOLTIP = "个性词岛"

        const val FALLBACK_DOUBLE_CLICK_MS = 500

        fun createTrayIconImage(playing: Boolean): BufferedImage {
            val size = trayIconSizePx()

            val hi = size * TRAY_SUPERSAMPLE
            val big = BufferedImage(hi, hi, BufferedImage.TYPE_INT_ARGB)
            val g = big.createGraphics()
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )

                g.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE,
                )

                val stroke = (hi * OUTLINE_STROKE_RATIO).coerceAtLeast(1f)
                val (a, b) = glyphPolygonsFor(hi, playing)

                g.color = GLYPH
                if (playing) {

                    g.fillRoundRect(a[0], a[1], a[2], a[3], a[4], a[4])
                    g.fillRoundRect(b[0], b[1], b[2], b[3], b[4], b[4])
                } else {

                    g.fillPolygon(java.awt.Polygon(a, b, 3))
                }

                g.color = PILL_STROKE
                g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                if (playing) {
                    g.drawRoundRect(a[0], a[1], a[2], a[3], a[4], a[4])
                    g.drawRoundRect(b[0], b[1], b[2], b[3], b[4], b[4])
                } else {
                    g.drawPolygon(java.awt.Polygon(a, b, 3))
                }
            } finally {
                g.dispose()
            }

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

        fun tooltipFor(@Suppress("UNUSED_PARAMETER") playing: Boolean): String {

            return TRAY_TOOLTIP
        }

        val PILL_STROKE: Color = Color(0x3A, 0x3D, 0x46)

        val GLYPH: Color = Color(0xF2, 0xF4, 0xF8)
    }
}
