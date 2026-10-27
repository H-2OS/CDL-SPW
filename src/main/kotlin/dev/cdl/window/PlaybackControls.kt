// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D

data class ControlsConfig(

    val triggerMode: String,
    val enterDelayMs: Long,
    val exitDelayMs: Long,
    val hitPaddingPx: Float,

    val position: String,
    val gapPx: Float,
    val showProgress: Boolean,

    val autoHideMs: Long,

    val progressStyle: String,
) {

    val glyphTrigger: Boolean get() = triggerMode != ISLAND_HIT

    val glowProgress: Boolean get() = progressStyle == GLOW

    companion object {

        const val ISLAND_HIT = "ISLAND_HIT"

        const val TOP = "TOP"

        const val CLASSIC = "CLASSIC"

        const val GLOW = "GLOW"

        val DEFAULTS = ControlsConfig(
            triggerMode = "GLYPH_HIT",
            enterDelayMs = 120,
            exitDelayMs = 250,
            hitPaddingPx = 2f,
            position = "BOTTOM",
            gapPx = 8f,
            showProgress = true,
            autoHideMs = 0,

            progressStyle = CLASSIC,
        )
    }
}

class PlaybackControls {

    enum class Id { PREVIOUS, TOGGLE, NEXT, PROGRESS }

    private enum class Icon { PLAY, PAUSE, PREVIOUS, NEXT }

    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun contains(px: Float, py: Float): Boolean =
            px >= x && px <= x + width && py >= y && py <= y + height

        val centerX: Float get() = x + width / 2f
        val centerY: Float get() = y + height / 2f
    }

    data class Button(val id: Id, val rect: Rect)

    data class Layout(
        val expanded: Boolean,
        val buttons: List<Button>,
        val progress: Rect?,
        val lyricsTop: Float,
        val lyricsHeight: Float,
        val stripHeight: Float,
    )

    fun stripHeight(cfg: ControlsConfig): Float =
        STRIP_PADDING + BUTTON_SIZE + (if (cfg.showProgress) ROW_GAP + PROGRESS_HEIGHT else 0f) + cfg.gapPx

    fun layout(width: Float, height: Float, cfg: ControlsConfig, expanded: Boolean): Layout {
        if (!expanded) {
            return Layout(false, emptyList(), null, 0f, height, 0f)
        }

        val strip = stripHeight(cfg)
        return layoutOf(width, (height - strip).coerceAtLeast(0f), strip, cfg)
    }

    fun layoutOf(
        width: Float,
        baseHeight: Float,
        stripPx: Float,
        cfg: ControlsConfig,
    ): Layout {
        val strip = stripPx.coerceAtLeast(0f)
        val base = baseHeight.coerceAtLeast(0f)
        val topStrip = cfg.position == ControlsConfig.TOP
        val lyricsTop = if (topStrip) strip else 0f
        val stripTop = if (topStrip) 0f else base

        var cursor = if (topStrip) stripTop + STRIP_PADDING else stripTop + cfg.gapPx
        var progressY = cursor
        val buttons: List<Button>
        if (topStrip) {

            buttons = buttonRow(cursor, width, buttonIds(width))
            cursor += BUTTON_SIZE + if (cfg.showProgress) ROW_GAP else 0f
            progressY = cursor
        } else {

            if (cfg.showProgress) {
                progressY = cursor
                cursor += PROGRESS_HEIGHT + ROW_GAP
            }
            buttons = buttonRow(cursor, width, buttonIds(width))
        }

        val progress = if (cfg.showProgress) {
            val side = PROGRESS_SIDE_PADDING
            Rect(side, progressY, (width - side * 2f).coerceAtLeast(0f), PROGRESS_HEIGHT)
        } else {
            null
        }
        return Layout(true, buttons, progress, lyricsTop, base, strip)
    }

    fun hit(layout: Layout, x: Float, y: Float): Id? {
        layout.buttons.firstOrNull { it.rect.contains(x, y) }?.let { return it.id }
        layout.progress?.let { if (it.contains(x, y)) return Id.PROGRESS }
        return null
    }

    fun trackRect(rect: Rect, metrics: java.awt.FontMetrics, positionMs: Long, durationMs: Long): Rect {
        val known = durationMs > 0
        val left = timeLabel(positionMs)
        val right = if (known) timeLabel(durationMs) else UNKNOWN_TIME

        val labelWidth = maxOf(metrics.stringWidth(left), metrics.stringWidth(right)) + LABEL_GAP
        val barX = rect.x + labelWidth
        val barWidth = (rect.width - labelWidth * 2f).coerceAtLeast(MIN_BAR_WIDTH)
        val barY = rect.centerY - BAR_THICKNESS / 2f
        return Rect(barX, barY, barWidth, BAR_THICKNESS)
    }

    fun seekTargetAt(
        progress: Rect?,
        metrics: java.awt.FontMetrics,
        x: Float,
        durationMs: Long,
    ): Long? {
        if (progress == null || durationMs <= 0) return null

        if (progress.width <= 0f) return null
        val ratio = ((x - progress.x) / progress.width).coerceIn(0f, 1f)
        return (ratio * durationMs).toLong().coerceIn(0L, durationMs)
    }

    fun paint(
        g2: Graphics2D,
        layout: Layout,
        hovered: Id?,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        accent: Color,
        cfg: ControlsConfig,
        alpha: Float = 1f,
        previewMs: Long? = null,

        transform: java.awt.geom.AffineTransform? = null,
    ) {
        if (!layout.expanded) return

        val layered = alpha < 1f || transform != null
        val g = if (!layered) {
            g2
        } else {
            (g2.create() as Graphics2D).also { layer ->
                if (alpha < 1f) {
                    layer.composite = java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f),
                    )
                }
                transform?.let { layer.transform(it) }
            }
        }
        try {
            layout.buttons.forEach { button ->
                if (button.id == hovered) {

                    g.color = HOVER_BACKDROP
                    g.fill(
                        RoundRectangle2D.Float(
                            button.rect.x, button.rect.y, button.rect.width, button.rect.height,
                            HOVER_CORNER, HOVER_CORNER,
                        )
                    )
                }
                drawIcon(g, iconFor(button.id, playing), button.rect.centerX, button.rect.centerY, ICON_SIZE)
            }
            layout.progress?.let {
                paintProgress(
                    g, it, previewMs ?: positionMs, durationMs, accent,

                    dragging = previewMs != null,
                    cfg = cfg,

                    hovering = hovered == Id.PROGRESS,
                )
            }
        } finally {
            if (g !== g2) g.dispose()
        }
    }

    private fun iconFor(id: Id, playing: Boolean): Icon = when (id) {
        Id.PREVIOUS -> Icon.PREVIOUS
        Id.NEXT -> Icon.NEXT

        Id.TOGGLE, Id.PROGRESS -> if (playing) Icon.PAUSE else Icon.PLAY
    }

    private fun buttonIds(width: Float): List<Id> {
        val all = listOf(Id.PREVIOUS, Id.TOGGLE, Id.NEXT)
        val total = all.size * BUTTON_SIZE + (all.size - 1) * BUTTON_SPACING

        return if (total <= width - STRIP_PADDING * 2) all else listOf(Id.TOGGLE)
    }

    private fun buttonRow(y: Float, width: Float, ids: List<Id>): List<Button> {
        val total = ids.size * BUTTON_SIZE + (ids.size - 1) * BUTTON_SPACING
        val left = (width - total) / 2f
        return ids.mapIndexed { index, id ->
            Button(id, Rect(left + index * (BUTTON_SIZE + BUTTON_SPACING), y, BUTTON_SIZE, BUTTON_SIZE))
        }
    }

    private fun paintProgress(
        g2: Graphics2D,
        rect: Rect,
        positionMs: Long,
        durationMs: Long,
        accent: Color,
        dragging: Boolean,
        cfg: ControlsConfig,
        hovering: Boolean = false,
    ) {
        val known = durationMs > 0
        g2.font = LABEL_FONT
        val metrics = g2.fontMetrics
        val left = timeLabel(positionMs)
        val right = if (known) timeLabel(durationMs) else UNKNOWN_TIME

        val track = trackRect(rect, metrics, positionMs, durationMs)
        val barX = track.x
        val barY = track.y
        val barWidth = track.width

        g2.color = TRACK_COLOR
        g2.fill(roundBar(barX, barY, barWidth))

        if (known) {
            val ratio = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
            val filled = (barWidth * ratio).toFloat()
            if (filled > 0.5f) {
                g2.paint = fillPaintFor(cfg, barX, barY, filled, accent)
                try {
                    g2.fill(roundBar(barX, barY, filled))
                } finally {

                    g2.paint = accent
                }
            }

            val knobX = (barX + filled).coerceIn(barX, barX + barWidth)

            val knobRadius = if (dragging) KNOB_RADIUS * 1.4f else KNOB_RADIUS

            val knobVisible = progressKnobVisible(cfg.glowProgress, dragging, hovering)
            if (knobVisible) {
                g2.color = accent
                g2.fillOval(
                    (knobX - knobRadius).toInt(),
                    (rect.centerY - knobRadius).toInt(),
                    (knobRadius * 2f).toInt(),
                    (knobRadius * 2f).toInt(),
                )
            }
        }

        g2.color = LABEL_COLOR
        val baseline = lyricBaselineY(rect.centerY, metrics.ascent.toFloat(), metrics.descent.toFloat())
        g2.drawString(left, rect.x, baseline)
        g2.drawString(right, rect.x + rect.width - metrics.stringWidth(right), baseline)
    }

    private fun roundBar(x: Float, y: Float, width: Float): RoundRectangle2D.Float =
        RoundRectangle2D.Float(x, y, width, BAR_THICKNESS, BAR_THICKNESS, BAR_THICKNESS)

    public fun progressKnobVisible(glow: Boolean, dragging: Boolean, hovering: Boolean): Boolean =

        !glow || dragging || hovering

    public fun hoverHighlightKept(expanded: Boolean, inside: Boolean): Boolean = expanded && inside

    private fun fillPaintFor(cfg: ControlsConfig, barX: Float, barY: Float, width: Float, accent: Color): java.awt.Paint =
        progressFillPaint(cfg.glowProgress, barX, barY, width, accent)

    public fun progressFillPaint(glow: Boolean, barX: Float, barY: Float, width: Float, accent: Color): java.awt.Paint {

        if (!glow) return accent
        val tail = width * GLOW_TAIL_RATIO

        if (width < GLOW_MIN_FILLED || tail <= 0f || tail >= width) return accent
        return java.awt.LinearGradientPaint(

            java.awt.geom.Point2D.Float(barX, barY),
            java.awt.geom.Point2D.Float(barX + width, barY),
            floatArrayOf(0f, 1f),

            arrayOf(
                Color(accent.red, accent.green, accent.blue, 0),
                accent,
            ),
        )
    }

    private fun drawIcon(g2: Graphics2D, icon: Icon, cx: Float, cy: Float, size: Float) {
        val scale = size / ICON_GRID

        fun gx(value: Float): Float = cx + (value - ICON_GRID / 2f) * scale
        fun gy(value: Float): Float = cy + (value - ICON_GRID / 2f) * scale

        g2.color = ICON_COLOR
        when (icon) {
            Icon.PAUSE -> {
                g2.fill(RoundRectangle2D.Float(gx(4f), gy(3f), 4f * scale, 12f * scale, 1f * scale, 1f * scale))
                g2.fill(RoundRectangle2D.Float(gx(11f), gy(3f), 4f * scale, 12f * scale, 1f * scale, 1f * scale))
            }

            Icon.PLAY -> g2.fillPolygon(
                intArrayOf(gx(5f).toInt(), gx(5f).toInt(), gx(15f).toInt()),
                intArrayOf(gy(3f).toInt(), gy(15f).toInt(), gy(9f).toInt()),
                3,
            )

            Icon.PREVIOUS -> {
                g2.fill(RoundRectangle2D.Float(gx(3f), gy(3f), 2f * scale, 12f * scale, 1f, 1f))
                g2.fillPolygon(
                    intArrayOf(gx(15f).toInt(), gx(15f).toInt(), gx(5f).toInt()),
                    intArrayOf(gy(3f).toInt(), gy(15f).toInt(), gy(9f).toInt()),
                    3,
                )
            }

            Icon.NEXT -> {
                g2.fill(RoundRectangle2D.Float(gx(13f), gy(3f), 2f * scale, 12f * scale, 1f, 1f))
                g2.fillPolygon(
                    intArrayOf(gx(3f).toInt(), gx(3f).toInt(), gx(13f).toInt()),
                    intArrayOf(gy(3f).toInt(), gy(15f).toInt(), gy(9f).toInt()),
                    3,
                )
            }
        }
    }

    fun timeLabel(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        return "%d:%02d".format(safe / 60_000, safe / 1000 % 60)
    }

    public companion object {

        const val STRIP_PADDING = 10f

        const val BUTTON_SIZE = 30f

        const val BUTTON_SPACING = 26f

        const val ICON_SIZE = 16f

        const val ICON_GRID = 18f

        const val PROGRESS_HEIGHT = 22f

        const val ROW_GAP = 6f

        const val PROGRESS_SIDE_PADDING = 16f

        const val BAR_THICKNESS = 4f

        const val LABEL_GAP = 6f

        const val MIN_BAR_WIDTH = 8f

        const val KNOB_RADIUS = 5f

        const val GLOW_TAIL_RATIO = 0.35f

        const val GLOW_MIN_FILLED = 8f

        const val HOVER_CORNER = 8f

        const val UNKNOWN_TIME = "--:--"

        val LABEL_FONT: Font = Font(Font.SANS_SERIF, Font.PLAIN, 10)

        fun labelFont(): Font = LABEL_FONT

        val ICON_COLOR: Color = Color(223, 228, 237)

        val LABEL_COLOR: Color = Color(147, 156, 174)

        val TRACK_COLOR: Color = Color(72, 77, 89)

        val HOVER_BACKDROP: Color = Color(255, 255, 255, 0x40)
    }
}
