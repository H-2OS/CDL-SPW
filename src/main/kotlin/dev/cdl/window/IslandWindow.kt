// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.animation.AnimationRuntime
import dev.cdl.animation.Easing
import dev.cdl.animation.FramePacer
import dev.cdl.animation.MotionStyle
import dev.cdl.animation.Motions
import dev.cdl.cover.CoverArtProvider
import dev.cdl.cover.CoverColorExtractor
import dev.cdl.playback.LyricLine
import dev.cdl.playback.TimelineSnapshot
import dev.cdl.playback.sharedSweepFraction
import dev.cdl.render.CoverBackdrop
import dev.cdl.render.CoverPalette
import dev.cdl.render.CoverScaler
import dev.cdl.render.CoverWaterLevel
import dev.cdl.render.FLOW_PALETTE_COLORS
import dev.cdl.render.FLOW_PERIOD_MS
import dev.cdl.render.loopPhase
import dev.cdl.render.rightSlotExtraWidthPx
import dev.cdl.render.GlyphHitTester
import dev.cdl.render.KaraokeTextRenderer
import dev.cdl.render.MaterialLayers
import dev.cdl.render.NO_PROGRESS
import dev.cdl.render.OwnFrame
import dev.cdl.render.RenderCache
import dev.cdl.render.TextOutline
import dev.cdl.render.fontWithLetterSpacing
import dev.cdl.render.letterSpacingTracking
import dev.cdl.render.spectrumAutoPitch
import dev.cdl.render.symmetricBandTop
import dev.cdl.render.SpectralPainter
import dev.cdl.render.playbackProgress
import dev.cdl.render.spectrumBandGeometry
import dev.cdl.render.spectrumFadeStartFraction
import dev.cdl.settings.AnimationStyleResolver
import dev.cdl.settings.BORDER_MODE_FADE_OUT
import dev.cdl.settings.BORDER_MODE_EDGE_DISSOLVE
import dev.cdl.settings.borderFadeLayers
import dev.cdl.settings.KARAOKE_MODE_LINE
import dev.cdl.settings.KARAOKE_MODE_PER_CHAR
import dev.cdl.settings.KARAOKE_MODE_SMOOTH
import dev.cdl.settings.UNSUNG_TRANSLATION_DIM
import dev.cdl.settings.dimPackedArgb
import dev.cdl.settings.SettingDomains
import dev.cdl.settings.SettingRegistry
import dev.cdl.platform.ProcessVolume
import dev.cdl.settings.blendPackedArgb
import dev.cdl.settings.spectrumBarCornerRatio
import dev.cdl.settings.SettingsRuntime
import dev.cdl.settings.SettingsSnapshot
import dev.cdl.settings.toColor
import dev.cdl.settings.toPackedArgb
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MouseInfo
import java.awt.Paint
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

fun trackCreditLine(snap: TimelineSnapshot, showAlbum: Boolean): String {
    val title = snap.trackTitle.trim()
    if (title.isEmpty()) return ""
    val artist = snap.artist.trim()
    val album = snap.album.trim()
    return buildString {        append(title)
        if (artist.isNotEmpty()) append(" · ").append(artist)
        if (showAlbum && album.isNotEmpty()) append(" · ").append(album)
    }
}

fun noLyricsMainRow(
    forceShowCredits: Boolean,
    showTrackTitle: Boolean,
    titleIsEmpty: Boolean,
    suppressPlaceholder: Boolean,
    creditLine: String,
    placeholder: String,
): String? {

    if (forceShowCredits) return creditLine

    if (showTrackTitle && !titleIsEmpty) return creditLine

    if (suppressPlaceholder) return null
    return placeholder
}

private fun hexRgb(packed: Int): String {
    val s = Integer.toHexString(packed and 0xFFFFFF).uppercase()
    return "#" + "0".repeat(6 - s.length) + s
}

internal const val TRANSLATION_POSITION_BELOW = "BELOW"
internal const val TRANSLATION_POSITION_ABOVE = "ABOVE"

internal const val SPECTRUM_PITCH_AUTO = "AUTO"

internal const val TRANSLATION_SCALE = 0.64f

internal val DEFAULT_SPECTRUM_COLOR: Int = 0xFF84D8BC.toInt()

internal const val SPECTRUM_ANCHOR_BOTTOM: String = "BOTTOM"

const val COVER_RGB_HOLD_GRACE_MS: Long = 1800L

fun coverRgbHoldOrNull(held: Int?, heldAtMs: Long, nowMs: Long, stillWaiting: Boolean): Int? {
    if (held == null) return null
    return if (coverHoldActive(heldAtMs, nowMs, stillWaiting)) held else null
}

fun coverHoldActive(heldAtMs: Long, nowMs: Long, stillWaiting: Boolean): Boolean {

    if (stillWaiting) return true
    return withinCoverHoldGrace(heldAtMs, nowMs)
}

fun withinCoverHoldGrace(heldAtMs: Long, nowMs: Long): Boolean {
    val age = nowMs - heldAtMs

    if (age < 0L) return true
    return age <= COVER_RGB_HOLD_GRACE_MS
}

fun coverPaletteHoldOrEmpty(
    held: List<Int>,
    heldAtMs: Long,
    nowMs: Long,
    stillWaiting: Boolean,
): List<Int> {
    if (held.size < 2) return emptyList()
    return if (coverHoldActive(heldAtMs, nowMs, stillWaiting)) held else emptyList()
}

internal const val UNSUNG_LYRIC_DIM = 0.5f

internal class IslandWindow(

    private val settings: SettingsRuntime?,

    private val snapshotSupplier: () -> TimelineSnapshot,

    private val playbackActions: PlaybackActions = PlaybackActions.NONE,

    private val durationSupplier: () -> Long = { 0L },

    private val spectrumBandsSupplier: () -> FloatArray? = { null },

    private val coverArtProvider: CoverArtProvider? = null,

    private val onEnabledChanged: ((Boolean) -> Unit)? = null,

    private val toastSink: ((String) -> Unit)? = null,
) : AutoCloseable {

    private val windowTitle = "CDL Lyric Window"

    private val window: JWindow = object : JWindow() {}

    @Volatile
    private var snapshot: SettingsSnapshot = settings?.live ?: SettingsSnapshot.defaults()

    private val panel = IslandPanel()

    @Volatile
    private var hwnd: Long = 0L

    private var mirrorWindow: JWindow? = null
    private var mirrorHwnd: Long = 0L

    @Volatile
    private var glassMode: Boolean = false

    private var widthAnimFrom: Int = 0
    private var widthAnimTo: Int = 0
    private var widthAnimStartNanos: Long = 0L
    private var widthAnimDurationMs: Double = 220.0
    private var widthAnimStyle: String = "SMOOTH"

    private var lastGlassDiagAt: Long = 0L

    private var lastLayeredCheckAt: Long = 0L

    private var lastWindowShapeKey: String = ""

    @Volatile
    private var shapeSetCount: Long = 0L

    private var lastL3Frosted: Boolean? = null

    private fun glassKeyOf(local: dev.cdl.window.Appearance): String =
        local.glassBlurRadius.toString() + "|" + local.glassClarity + "|" +
            local.glassDispersionPercent + "|" + local.glassSaturationPercent + "|" +
            local.glassSurfaceAlpha

    private var lastL3GlassKey: String? = null

    private var lastMaskW: Int = -1
    private var lastMaskH: Int = -1
    private var pendingMaskW: Int = -1
    private var pendingMaskH: Int = -1
    private var pendingMaskAt: Long = 0L

    private var lastShapeSetAt: Long = 0L

    @Volatile
    private var dragFastUntilMs: Long = 0L

    private var lastGlassSizeLogAt: Long = 0L

    private var settingsSubscription: AutoCloseable? = null

    private var dragOrigin: Point? = null

    private var dragWindowOrigin: Point? = null

    private var coverClickArmed = false

    private var coverPressScreen: Point? = null

    private var coverClickTimer: Timer? = null

    private var dragTopRaised = false

    @Volatile
    private var started = false

    private val renderCache = RenderCache()

    private val spectrumCache = SpectralPainter.SpectrumCache()

    @Volatile
    private var naturalWidthPx: Int = 0

    @Volatile
    private var measureCoverInsetPx: Float = Float.NaN

    private fun widthCoverInsetPx(): Float =
        if (measureCoverInsetPx.isFinite() && measureCoverInsetPx >= 0f) {
            measureCoverInsetPx
        } else {
            collapsedCoverInsetRel(LEADING_INSET, LEADING_GAP)
        }


    private val karaokeRenderer = KaraokeTextRenderer()

    private val controls = PlaybackControls()

    private val hover = HoverState()

    private val glyphHitTester = GlyphHitTester()

    private var hoverTimer: Timer? = null

    private var lastHitGlyph: Boolean = false

    private var lastInside: Boolean = false

    private var wheelBurstLastMs = 0L

    private val wheelBurstIdleMs: Long = 400L

    private var wheelVolumeCache: Float? = null

    private var bandFraction: Float = 0f

    private var bandCollapseRunning: Boolean = false

    private var bandCollapseStartNanos: Long = 0L

    private var bandCollapseFrom: Float = 1f

    private var bandCollapseDurationMs: Long = 0L

    private var bandCollapseEasing: Easing = Easing.EASE_OUT

    private val bandFractionNow: Float
        get() = if (boolOf(snapshot, "island.lock_collapsed")) {

            0f
        } else {
            bandFractionCombined(hover.expanded, hover.expansion, bandFraction)
        }

    private var windowYShiftPx: Int = 0

    private fun syncWindowYShift() {
        val cfg = controlsConfig()
        windowYShiftPx = windowYOffsetPx(controls.stripHeight(cfg), cfg.position == ControlsConfig.TOP)
    }

    private fun bandGeometryNow(): BandGeometry {
        val cfg = controlsConfig()
        return bandGeometry(
            baseHeightPx = baseHeightPx,
            stripHeightPx = controls.stripHeight(cfg),
            fraction = bandFractionNow,
            stripAtTop = cfg.position == ControlsConfig.TOP,

            baseWidthPx = baseWidthPx,

            anchorAtLeftEdge = coverShapeSkipsLyricChain(shapeTypeNow()),

            widthGrowOverridePx = widthGrowOverridePx(),
        )
    }

    private fun shapeTypeNow(): String = textOf(snapshot, "shape.type")

    private fun lockBlocksExpandNow(): Boolean =
        coverLockBlocksExpand(shapeTypeNow(), boolOf(snapshot, "cover.lock"))

    private fun coverLockBadgeHit(x: Float, y: Float): java.awt.geom.Rectangle2D? {
        val rect = coverLockBadgeRectOrNull()
        return if (rect != null && rect.contains(x.toDouble(), y.toDouble())) rect else null
    }

    private fun coverLockBadgeRectOrNull(): java.awt.geom.Rectangle2D? {
        if (!coverLockBadgeVisible()) return null
        if (!coverLockBlocksExpand(shapeTypeNow(), boolOf(snapshot, "cover.lock"))) return null
        val cover = panel.drawnCoverRect() ?: return null
        return coverLockBadgeRect(cover)
    }

    private fun coverLockBadgeRect(cover: java.awt.geom.Rectangle2D): java.awt.geom.Rectangle2D? {
        if (cover.width <= 0.0 || cover.height <= 0.0) return null
        val size = COVER_LOCK_BADGE_PX
        val inset = COVER_LOCK_BADGE_INSET_PX
        return java.awt.geom.Rectangle2D.Float(
            (cover.maxX - inset - size).toFloat(),
            (cover.minY + inset).toFloat(),
            size,
            size,
        )
    }

    private fun triggerCoverLockBadge() {
        if (!coverLockBlocksExpand(shapeTypeNow(), boolOf(snapshot, "cover.lock"))) return
        coverLockBadgeUntilNanos = System.nanoTime() + COVER_LOCK_BADGE_MS * 1_000_000L
        panel.repaint()
        syncFrameDriver()
    }

    private fun expandForCoverBadge() {
        coverLockBadgeUntilNanos = 0L
        hover.expandNow(System.currentTimeMillis())
        bandFraction = maxOf(bandFraction, hover.expansion)
        applyWindowSize()
        panel.repaint()
        syncHoverTicker()
        syncFrameDriver()
    }

    private var clickThroughActive: Boolean = false

    @Volatile
    private var foregroundFullscreen: Boolean = false

    private var fullscreenCandidate: Boolean? = null

    private var fullscreenCandidateTicks: Int = 0

    private var watchdogTimer: Timer? = null

    private var lastRaiseNanos: Long = 0L

    private var panelOriginCache: Point? = null

    private var lastRegionalHit: Boolean = true

    private var hoveredControl: PlaybackControls.Id? = null

    private var controlPressConsumed: Boolean = false

    private var seekPreviewMs: Long? = null

    private var seekGestureKey: String? = null

    private var committedSeekMs: Long? = null

    private var committedSeekDeadlineNanos: Long = 0L

    private var committedSeekTrackKey: String = ""

    private var baseWidthPx: Int = 0

    private var maxWidthPxCache: Int = 0

    private var coverExpandedWidthPx: Int = 0

    private fun widthGrowOverridePx(): Int =
        if (coverExpandedWidthPx > 0 && baseWidthPx > 0) {
            (coverExpandedWidthPx - baseWidthPx).coerceAtLeast(0)
        } else {
            -1
        }
    private var baseHeightPx: Int = 0

    private var coverRequestPath: String = ""

    private var coverPending: Boolean = false

    private var coverRepaintAttempts: Int = 0

    private var coverWaitDeadlineNanos: Long = 0L

    private var coverRepaintTimer: Timer? = null

    private var coverLockBadgeUntilNanos: Long = 0L

    private fun coverLockBadgeVisible(): Boolean =
        coverLockBadgeVisibleAt(System.nanoTime(), coverLockBadgeUntilNanos)

    private var coverColorImage: BufferedImage? = null

    private var coverColorRgb: Int? = null

    private var coverPaletteImage: BufferedImage? = null

    private var coverPalette: List<Int> = emptyList()

    private var coverPaletteShares: FloatArray = FloatArray(0)

    private var stickyCoverColorPath: String = ""

    private var stickyPalettePath: String = ""

    private var stickyPalette: List<Int> = emptyList()

    private var paletteHold: List<Int> = emptyList()

    private var paletteHoldAtMs: Long = 0L

    private var palettePrev: List<Int> = emptyList()

    private var stickyCoverColorRgb: Int? = null

    private var frameTimer: Timer? = null

    private val framePacer = FramePacer({ animator.frameIntervalMs })

    private val animator = AnimationRuntime()

    private var desiredVisible = true

    private var fadeAlpha = 1f

    private var fadeScale = 1f

    private var fadeRunning = false

    private var fadeTarget = 0f

    private var fadePrimed = false

    private var coverShown: BufferedImage? = null

    private var coverPrev: BufferedImage? = null

    private var coverMix = 1f

    private var coverMixRunning = false

    private var coverRgbPrev: Int? = null

    private var coverRgbShown: Int? = null

    private var coverRgbHold: Int? = null

    private var coverRgbHoldAtMs: Long = 0L

    private var lastMainText: String? = null

    private var exitingMainText: String? = null

    private var mainExit = 1f

    private var mainExitRunning = false

    private var mainEnter = 1f

    private var mainEnterRunning = false

    private var lastDocVersion: Long = -1L

    private var lastPositionMs: Long = -1L

    fun start() = onEdt {
        if (started) return@onEdt
        started = true

        window.name = windowTitle

        initGlassMode()
        val dbgGlassA = glassMode

        val aaGlassA = dbgGlassA && glassAaEnabled()
        if (dbgGlassA && !aaGlassA) {
            window.background = Color(0x10, 0x10, 0x10)
            window.contentPane.background = Color(0x10, 0x10, 0x10)
        } else {
            window.background = Color(0, 0, 0, 0)
            window.contentPane.background = Color(0, 0, 0, 0)
        }
        window.contentPane.add(panel)

        installPopupHandler()

        applyGeometry(snapshot)

        applyAnimationSettings(snapshot)
        restoreSavedPosition(snapshot)
        window.isVisible = true

        window.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) {

                dragFastUntilMs = System.currentTimeMillis() + 400L
                panel.repaint()
            }
        })

        if (glassMode) {

            val timer = javax.swing.Timer(100) { ev ->
                runCatching {
                    panel.ensureL3Started()
                    if (panel.isL3Running) (ev.source as javax.swing.Timer).stop()
                }
            }
            timer.initialDelay = 100
            timer.start()
            javax.swing.Timer(5000) { ev ->
                (ev.source as javax.swing.Timer).stop()
                if (!panel.isL3Running) timer.stop()
            }.apply { isRepeats = false; start() }
        }

        if (!supportsPerPixelTranslucency()) {
            window.background = DEGRADED_BACKGROUND
        }

        installDragHandler()

        installHoverHandler()

        installWheelHandler()
        resolveHwnd()
        applyExtensionStyles(snapshot)
        installSettingsSubscription()

        applyVisibility(snapshot)

        fadePrimed = true
        desiredVisible = window.isVisible
        fadeAlpha = if (window.isVisible) 1f else 0f
        syncFrameDriver()

        startWatchdog()
    }

    override fun close() = onEdt {
        started = false

        stopFrameDriver()

        runCatching { panel.disposeL3() }
            .onFailure {  }

        stopHoverTicker()

        stopWatchdog()

        coverRepaintTimer?.stop()
        coverRepaintTimer = null

        clockWakeTimer?.stop()
        clockWakeTimer = null

        coverClickTimer?.stop()
        coverClickTimer = null

        coverColorImage = null
        coverColorRgb = null
        runCatching { settingsSubscription?.close() }
        settingsSubscription = null

        runCatching { renderCache.clear() }
        runCatching { karaokeRenderer.invalidate() }

        runCatching { animator.clear() }
        fadeRunning = false
        runCatching { window.isVisible = false }
        runCatching { window.dispose() }
    }

    val isVisible: Boolean
        get() = window.isVisible

    fun setVisible(visible: Boolean) = onEdt {
        if (!started) return@onEdt

        val target = if (visible) 1f else 0f
        if (visible == desiredVisible && (!fadeRunning || fadeTarget == target)) return@onEdt
        desiredVisible = visible
        if (visible) {
            if (!window.isVisible) {

                fadeAlpha = 0f
                window.isVisible = true

                resolveHwnd()
                applyExtensionStyles(snapshot)

                raiseToTopNow()
            }
            startFade(to = 1f)
        } else {
            if (!window.isVisible) {

                fadeAlpha = 0f
                return@onEdt
            }
            startFade(to = 0f)
        }
    }

    private fun startFade(to: Float) {
        val spec = Motions.ISLAND_FADE

        val slot = requireNotNull(spec.slot) {
            "Motions.ISLAND_FADE 必须声明槽位（它由帧循环驱动）"
        }

        val durationMs = if (fadePrimed) spec.durationMs else 0L
        fadePrimed = true

        fadeTarget = to
        fadeRunning = animator.startIn(
            slot = slot,
            from = fadeAlpha,
            to = to,
            durationMs = durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                fadeAlpha = v

                val startScale = if (spec.scaleFrom != 1f) {
                    spec.scaleFrom
                } else {
                    spec.style.scaleAt(0f)
                }
                fadeScale = startScale + (1f - startScale) * v
                panel.repaint()
            },
            onDone = {
                fadeRunning = false

                if (to == 0f && !desiredVisible) hideWindowNow()
            },
        )

        syncFrameDriver()
    }

    private fun hideWindowNow() {
        if (!window.isVisible) return
        window.isVisible = false
        fadeAlpha = 0f

        fadeScale = 1f

        hover.reset()

        resetControlsBand()
        hoveredControl = null
        syncHoverTicker()
        applyWindowSize()

        releaseIdleCaches()
    }

    private fun releaseIdleCaches() {
        renderCache.clear()
        CoverScaler.release()
        dev.cdl.render.releaseCamoCache()
    }

    private fun installPopupHandler() {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                maybeShowPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowPopup(e)
            }

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return

                IslandPopupMenu.showAt(settings, this@IslandWindow, panel, e.x, e.y)
            }
        }
        panel.addMouseListener(listener)
    }

    fun repaint() = onEdt { panel.repaint() }

    private fun applyVisibility(fresh: SettingsSnapshot, playing: Boolean? = null) {
        if (!started) return
        val enabled = boolOf(fresh, "island.enabled")
        onEnabledChanged?.invoke(enabled)
        if (!enabled) {
            setVisible(false)
            logVisibilityChange("island.enabled=false")
            return
        }

        val hidePaused = boolOf(fresh, "island.hide_paused")
        val isPlaying = playing ?: snapshotSupplier().isPlaying
        if (hidePaused && !isPlaying) {
            setVisible(false)
            logVisibilityChange("island.hide_paused")
            return
        }

        if (boolOf(fresh, "island.hide_fullscreen") && foregroundFullscreen) {
            setVisible(false)
            logVisibilityChange("island.hide_fullscreen")
            return
        }

        if (boolOf(fresh, "island.auto_hide_on_hover") &&
            shouldDriveHover(clickThroughActive) &&
            lastInside
        ) {
            setVisible(false)
            logVisibilityChange("island.auto_hide_on_hover")
            return
        }

        val hideForNoLyrics = NoLyricsMode.parse(textOf(fresh, "layout.noLyrics.mode")) ==
            NoLyricsMode.HIDE_ISLAND
        if (hideForNoLyrics) {
            val snap = snapshotSupplier()

            val noLyrics = noLyricsDisplay(
                lyricLineCount = snap.lyricLineCount,
                hasCurrentLine = snap.currentLine != null,
            ) == NoLyricsDisplay.NO_LYRICS
            if (noLyrics) {
                setVisible(false)
                logVisibilityChange("layout.noLyrics.mode=HIDE_ISLAND")
                return
            }
        }
        setVisible(true)
        logVisibilityChange("")
    }

    private fun logVisibilityChange(reason: String) {
        if (reason == lastVisibilityReason) return
        lastVisibilityReason = reason
    }

    private var lastVisibilityReason: String? = null

    fun onPlaybackStateMaybeChanged() = onEdt {

        applyVisibility(snapshot, playing = snapshotSupplier().isPlaying)
        syncFrameDriver()
    }

    private fun syncFrameDriver() {
        if (!started) return
        if (snapshotSupplier().isPlaying) {
            startFrameDriver()
        } else if (panel.needsAnimationFrames()) {

            startFrameDriver()
        } else {
            stopFrameDriver()
        }

        rescheduleClockWake()
    }

    private var clockWakeTimer: Timer? = null

    private fun rightSlotShowsClock(): Boolean =
        dev.cdl.render.rightSlotOccupant(
            textOf(snapshot, "island.right_slot.component"),
            textOf(snapshot, "spectrum.anchor"),
        ) == dev.cdl.render.RIGHT_SLOT_CLOCK

    private fun rescheduleClockWake() {

        clockWakeTimer?.stop()
        clockWakeTimer = null
        if (!started || !window.isShowing || !rightSlotShowsClock()) return

        val toNextTick = 500L - (System.currentTimeMillis() % 500L)
        val t = Timer(toNextTick.toInt().coerceAtLeast(1)) {
            panel.repaint()

            rescheduleClockWake()
        }
        t.isRepeats = false
        clockWakeTimer = t
        t.start()
    }

    private fun startFrameDriver() {
        if (frameTimer != null) {

            val existing = frameTimer!!
            if (!existing.isRunning) {

                framePacer.reset()
                existing.start()
            }
            return
        }

        framePacer.reset()
        frameTimer = Timer(animator.frameIntervalMs.toInt()) { onFrameTick() }.apply { start() }
    }

    private fun restartFrameDriverNow() {
        val t = frameTimer ?: return
        framePacer.reset()
        t.delay = animator.frameIntervalMs.toInt()
        t.restart()
    }

    private fun stopFrameDriver() {
        frameTimer?.stop()

    }

    private fun onFrameTick() {
        if (widthAnimStartNanos > 0L) {
            val elapsedMs = (System.nanoTime() - widthAnimStartNanos) / 1_000_000.0
            val t = (elapsedMs / widthAnimDurationMs).coerceIn(0.0, 1.0)

            panel.requestFastCapture(System.currentTimeMillis() + 300L + widthAnimDurationMs.toLong())
            val eased = when (widthAnimStyle) {
                "LINEAR" -> t
                "EASE_OUT" -> 1.0 - (1.0 - t) * (1.0 - t)
                else -> t * t * (3.0 - 2.0 * t)
            }
            val span = (widthAnimTo - widthAnimFrom).toDouble()
            baseWidthPx = (widthAnimFrom + span * eased).toInt()
            if (t >= 1.0) {
                baseWidthPx = widthAnimTo
                widthAnimStartNanos = 0L
            }
            applyWindowSize()
            panel.repaint()
        }
        if (!started) {
            stopFrameDriver()
            return
        }

        val tickNanos = System.nanoTime()

        framePacer.onFrameDelivered()
        frameTimer?.let { it.delay = framePacer.delayToNextMs() }

        animator.tick()

        advanceBandCollapse()
        val snap = snapshotSupplier()

        run {
            val cur = snap.currentLine
            val nxt = snap.nextLine
            if (snap.isPlaying && cur != null && nxt != null &&
                nxt.text.length != cur.text.length
            ) {
                val toNext = nxt.startMs - snap.positionMs
                if (toNext in -120L..220L) {
                    panel.requestFastCapture(System.currentTimeMillis() + 520L)
                }
            }
        }

        if (!snap.isPlaying) {

            applyVisibility(snapshot, playing = false)

            if (panel.needsAnimationFrames()) {
                panel.repaint()
                return
            }
            stopFrameDriver()
            panel.repaint()
            return
        }

        applyVisibility(snapshot, playing = true)

        if (!window.isShowing) {
            stopFrameDriver()
            return
        }

        val docChanged = snap.docVersion != lastDocVersion
        val jumped = lastPositionMs >= 0 && abs(snap.positionMs - lastPositionMs) > SEEK_JUMP_THRESHOLD_MS
        lastDocVersion = snap.docVersion
        lastPositionMs = snap.positionMs

        if (docChanged || jumped) restartFrameDriverNow()
        panel.repaint()
    }

    private fun coverImageFor(local: Appearance): BufferedImage? {

        val needsCoverImage = needsCoverImageFor(
            coverEnabled = local.coverEnabled,
            lyricColorMode = local.lyricColorMode,
            backgroundColorMode = local.backgroundColorMode,
            spectrumColorMode = local.spectrumColorMode,

            slotSpectrumColorMode = dev.cdl.window.rightSlotSpectrumColorModeFor(
                local.rightSlotSpectrumColorMode,
            ),

            borderColorMode = local.borderColorMode,
            translationColorMode = local.translationColorMode,
            gradientStyle = local.materialGradientStyle,
        )
        if (!needsCoverImage) {
            resetCoverWait()
            return null
        }
        val provider = coverArtProvider ?: run {
            resetCoverWait()
            return null
        }
        val path = snapshotSupplier().path
        if (path.isEmpty()) {
            resetCoverWait()
            return null
        }

        if (path != coverRequestPath) {

            coverRequestPath = path
            coverPending = true
            coverRepaintAttempts = 0

            coverWaitDeadlineNanos = System.nanoTime() +
                COVER_REPAINT_DELAY_MS.toLong() * MAX_COVER_REPAINT_ATTEMPTS * 1_000_000L
        }

        val image = provider.coverFor(path)
        if (image != null) {
            coverPending = false
            coverRepaintAttempts = 0
            coverWaitDeadlineNanos = 0L
            return image
        }

        if (coverWaitElapsed(coverPending, System.nanoTime(), coverWaitDeadlineNanos)) {
            coverPending = false
        }

        if (coverPending) scheduleCoverRepaint()
        return null
    }

    private fun scheduleCoverRepaint() {

        if (coverWaitElapsed(coverPending, System.nanoTime(), coverWaitDeadlineNanos)) {
            coverPending = false
            return
        }

        if (frameTimer?.isRunning == true) return
        if (coverRepaintAttempts >= MAX_COVER_REPAINT_ATTEMPTS) {

            coverPending = false
            return
        }

        if (coverRepaintTimer?.isRunning == true) return
        coverRepaintAttempts++
        coverRepaintTimer = Timer(COVER_REPAINT_DELAY_MS) { panel.repaint() }.apply {
            isRepeats = false
            start()
        }
    }

    private fun resetCoverWait() {
        coverRequestPath = ""
        coverPending = false
        coverRepaintAttempts = 0
        coverWaitDeadlineNanos = 0L
    }

    private class CoverPaint(val shown: BufferedImage?, val prev: BufferedImage?, val mix: Float) {
        companion object {

            val NONE = CoverPaint(null, null, 1f)
        }
    }

    private fun resolveCoverMix(newImage: BufferedImage?, enabled: Boolean): CoverPaint {

        if (!enabled) {
            coverShown = null
            coverPrev = null
            coverMix = 1f
            coverMixRunning = false
            return CoverPaint.NONE
        }

        if (newImage != null && newImage !== coverShown) {
            coverPrev = coverShown
            coverShown = newImage

            coverRgbPrev = coverRgbShown

            startCoverMix(from = if (coverPrev != null) 0f else 1f)
        } else if (newImage == null && !coverPending && coverShown != null) {

            coverPrev = coverShown
            coverShown = null

            coverRgbPrev = coverRgbShown
            startCoverMix(from = 0f)
        }
        return if (coverPrev == null) {
            CoverPaint(coverShown, null, 1f)
        } else {
            CoverPaint(coverShown, coverPrev, coverMix)
        }
    }

    private fun startCoverMix(from: Float) {
        coverMix = from

        val spec = Motions.COVER_CROSSFADE
        val slot = requireNotNull(spec.slot) {
            "Motions.COVER_CROSSFADE 必须声明槽位（它由帧循环驱动）"
        }
        coverMixRunning = animator.startIn(
            slot = slot,
            from = from,
            to = 1f,
            durationMs = spec.durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                coverMix = v
                panel.repaint()
            },
            onDone = {

                coverMixRunning = false
                coverMix = 1f
                coverPrev = null
            },
        )
    }

    private fun blendedCoverRgb(raw: Int?, paint: CoverPaint): Int? {

        if (paint.prev == null) return raw
        val a = coverRgbPrev
        val b = raw
        if (a == null) return b
        if (b == null) return a

        return blendPackedArgb(a, b, paint.mix)
    }

    private fun syncMainExit(text: String?) {
        if (text == lastMainText) return
        val prev = lastMainText

        lastMainText = text

        if (!animator.effectiveAdvancedTransitions) {
            exitingMainText = null
            mainExit = 1f
            mainExitRunning = false
            mainEnter = 1f
            mainEnterRunning = false
            return
        }
        if (prev == null || text == null) {

            exitingMainText = null
            mainExit = 1f
            mainExitRunning = false

            if (text != null) startMainEnter() else {
                mainEnter = 1f
                mainEnterRunning = false
            }
            return
        }
        exitingMainText = prev
        startMainExit()

        startMainEnter()
    }

    private fun startMainExit() {
        val spec = Motions.LYRIC_EXIT
        val slot = requireNotNull(spec.slot) {
            "Motions.LYRIC_EXIT 必须声明槽位（它由帧循环驱动）"
        }
        mainExit = 0f
        mainExitRunning = animator.startIn(
            slot = slot,
            from = 0f,
            to = 1f,
            durationMs = spec.durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                mainExit = v
                panel.repaint()
            },
            onDone = {
                mainExitRunning = false
                mainExit = 1f
                exitingMainText = null
            },
        )
    }

    private fun startMainEnter() {
        val spec = Motions.LYRIC_ENTER
        val slot = requireNotNull(spec.slot) {
            "Motions.LYRIC_ENTER 必须声明槽位（它由帧循环驱动）"
        }
        mainEnter = 0f
        mainEnterRunning = animator.startIn(
            slot = slot,
            from = 0f,
            to = 1f,
            durationMs = spec.durationMs,
            easing = spec.easing,
            onUpdate = { v ->
                mainEnter = v
                panel.repaint()
            },
            onDone = {
                mainEnterRunning = false
                mainEnter = 1f
            },
        )
    }

    private fun coverRgbOf(image: BufferedImage): Int? {
        if (image !== coverColorImage) {
            coverColorImage = image

            coverColorRgb = if (useRichPalette) {
                dev.cdl.cover.CoverPaletteRich.colorSource(image, circleMask = coverCircleMask())
            } else {
                CoverColorExtractor.dominant(image)
            }
        }
        return coverColorRgb
    }

    private fun coverCircleMask(): Boolean = textOf(snapshot, "cover.shape") == COVER_SHAPE_CIRCLE

    private val useRichPalette: Boolean = true

    private fun coverPaletteFor(coverImage: BufferedImage?): List<Int> {

        val gradientStyle = textOf(snapshot, "material.fill.gradient_style")
        val spectrumMode = textOf(snapshot, "spectrum.color_mode")

        val slotSpectrumMode = dev.cdl.window.rightSlotSpectrumColorModeFor(
            textOf(snapshot, "island.right_slot.spectrum_color_mode"),
        )
        val needFlow = gradientStyle == "FLOW" || gradientStyle == "CAMO" ||
            spectrumMode == dev.cdl.render.CoverPalette.COVER_FLOW ||
            spectrumMode == dev.cdl.render.CoverPalette.COVER_CAMO ||

            slotSpectrumMode == dev.cdl.render.CoverPalette.COVER_FLOW ||
            slotSpectrumMode == dev.cdl.render.CoverPalette.COVER_CAMO
        if (!needFlow) return emptyList()

        val path = snapshotSupplier().path
        if (coverImage == null) {
            if (path.isNotEmpty() && path == stickyPalettePath) return stickyPalette

            return coverPaletteHoldOrEmpty(
                paletteHold, paletteHoldAtMs, System.currentTimeMillis(), coverPending,
            )
        }
        if (coverImage !== coverPaletteImage) {
            coverPaletteImage = coverImage

            val richEntries = if (useRichPalette) {
                dev.cdl.cover.CoverPaletteRich.entries(coverImage, circleMask = coverCircleMask())
            } else {
                emptyList()
            }
            coverPaletteShares = richEntries.map { it.share }.toFloatArray()
            coverPalette = if (useRichPalette) {

                richEntries.map { it.argb }
            } else {
                dev.cdl.render.augmentPalette(
                    CoverColorExtractor.palette(coverImage, FLOW_PALETTE_COLORS),
                    FLOW_PALETTE_COLORS,
                )
            }

        }
        if (path.isNotEmpty()) {
            stickyPalettePath = path
            stickyPalette = coverPalette
        }

        return holdAndBlendPalette(coverPalette)
    }

    private fun holdAndBlendPalette(raw: List<Int>): List<Int> {
        val now = System.currentTimeMillis()
        if (raw.size < 2) {

            return coverPaletteHoldOrEmpty(paletteHold, paletteHoldAtMs, now, coverPending)
        }
        if (paletteHold.size < 2) {

            paletteHold = raw
            paletteHoldAtMs = now
            palettePrev = emptyList()
            return raw
        }
        if (raw != paletteHold) {

            palettePrev = paletteHold
            paletteHold = raw
            paletteHoldAtMs = now
            val from = palettePrev

            if (from.size == raw.size && coverMixRunning) {
                return raw.indices.map { i ->
                    blendPackedArgb(from[i], raw[i], coverMix)
                }
            }
            return raw
        }

        paletteHoldAtMs = now
        return raw
    }

    private fun resolveCoverRgb(coverImage: BufferedImage?): Int? {
        if (coverImage != null) {
            val rgb = coverRgbOf(coverImage)
            val path = snapshotSupplier().path
            if (path.isNotEmpty()) {
                stickyCoverColorPath = path
                stickyCoverColorRgb = rgb
            }

            if (rgb != null) {
                coverRgbHold = rgb
                coverRgbHoldAtMs = System.currentTimeMillis()
            }
            return rgb
        }
        val path = snapshotSupplier().path
        if (path.isNotEmpty() && path == stickyCoverColorPath) {
            return stickyCoverColorRgb
        }

        if (path.isNotEmpty()) {

            return coverRgbHoldOrNull(
                coverRgbHold, coverRgbHoldAtMs, System.currentTimeMillis(), coverPending,
            )
        }
        return coverRgbHoldOrNull(
            coverRgbHold, coverRgbHoldAtMs, System.currentTimeMillis(), coverPending,
        )
    }

    private fun leadingCoverSize(
        local: Appearance,
        image: BufferedImage?,

        geometry: BandGeometry,

        reserveWhileFading: Boolean = false,
    ): Float? {
        if (!local.coverEnabled) return null
        if (coverRequestPath.isEmpty()) return null
        if (image == null && !coverPending && !reserveWhileFading) return null

        return geometry.coverOuterSizePx(local.coverScaleWithFont)
    }

    private fun installSettingsSubscription() {
        val runtime = settings ?: return
        settingsSubscription = runtime.subscribe { fresh, change ->

            onEdt {

                if (change.changedKeys.any {
                        it.contains("color") || it.contains("outline")
                    }
                ) {
                }
                snapshot = fresh
                applyAppearance(fresh)
                applyExtensionStyles(fresh)

                if (change.touchesAny(SettingDomains.GEOMETRY)) {
                    applyGeometry(fresh)
                }

                if (change.touchesAny(SettingDomains.FRAME)) {
                    applyAnimationSettings(fresh)
                }

                applyVisibility(fresh)
                syncFrameDriver()
            }
        }
    }

    private fun applyAppearance(fresh: SettingsSnapshot) {
        panel.appearance = Appearance.from(fresh)

        panel.syncL3(panel.appearance)

        dev.cdl.render.MaterialLayers.gpuEnabled = panel.appearance.materialRenderGpuBackend
        panel.repaint()
    }

    private fun applyAnimationSettings(fresh: SettingsSnapshot) {
        animator.applySettings(
            enabled = boolOf(fresh, "animation.enabled"),
            quality = textOf(fresh, "animation.quality"),
            durationScalePercent = intOf(fresh, "animation.duration_scale"),

            frameRate = textOf(fresh, "animation.frame_rate"),
        )

        framePacer.reset()
        frameTimer?.delay = animator.frameIntervalMs.toInt()

    }

    private fun effectiveMaxWidth(configured: Int): Int {
        val screen = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        }.getOrNull() ?: return configured
        if (screen.width <= 0) return configured
        val byScreen = screen.width - 2 * SCREEN_SIDE_MARGIN_PX
        return minOf(configured, byScreen).coerceAtLeast(MIN_ISLAND_WIDTH)
    }

    private fun applyGeometry(fresh: SettingsSnapshot) {
        val maxWidth = effectiveMaxWidth(intOf(fresh, "layout.max_width"))

        maxWidthPxCache = maxWidth

        val fontSize = intOf(fresh, "main_lyric.font.size")

        val translationEnabled = boolOf(fresh, "layout.translation.enabled")

        val lines = LineCount.parse(textOf(fresh, "layout.lines"))
        val lineGapPx = intOf(fresh, "layout.line_gap_px").toFloat().coerceAtLeast(0f)

        val fixedWidth = boolOf(fresh, "layout.fixed_width")

        val genericWidthPx = if (fixedWidth || naturalWidthPx <= 0) {
            maxWidth
        } else {
            naturalWidthPx.coerceIn(MIN_ISLAND_WIDTH, maxWidth)
        }
        if (coverShapeSkipsLyricChain(textOf(fresh, "shape.type"))) {

            coverExpandedWidthPx = genericWidthPx
            baseWidthPx = coverShapeCollapsedHeightPx(coverOuterSizePx = COVER_MAX_OUTER_SIZE_PX)
        } else {
            coverExpandedWidthPx = 0

            baseWidthPx = genericWidthPx
        }

        baseHeightPx = if (coverShapeSkipsLyricChain(textOf(fresh, "shape.type"))) {

            coverShapeCollapsedHeightPx(coverOuterSizePx = COVER_MAX_OUTER_SIZE_PX)
        } else {
            islandHeightFor(
                fontSize, translationEnabled, lines, lineGapPx,

                (intOf(fresh, "layout.translation.scale_percent") / 100f)
                    .coerceIn(0.5f, 1.5f) * TRANSLATION_SCALE,

                followFont = boolOf(fresh, "layout.island.height.follow_font"),
                heightRatioPercent = intOf(fresh, "layout.island.height.ratio_percent"),
            )
        }

        if (!coverShapeSkipsLyricChain(textOf(fresh, "shape.type"))) {
            val slotOccupantForWidth = dev.cdl.render.rightSlotOccupant(
                textOf(fresh, "island.right_slot.component"),
                textOf(fresh, "spectrum.anchor"),
            )

            baseWidthPx += dev.cdl.render.rightSlotTotalExtraWidthPx(
                slotOccupantForWidth,
                baseHeightPx.toFloat(),

                widthCoverInsetPx(),

                coverOuterPx = leadingCoverOuterSizePx(
                    baseHeightPx.toFloat(),
                    boolOf(fresh, "cover.scale_with_font"),
                ),
            )

        }
        applyWindowSize()

         rescheduleClockWake()
    }

    private fun applyNaturalWidth() {
        val runtime = settings ?: return
        val snapshot = runtime.live

        if (boolOf(snapshot, "layout.fixed_width")) return

        if (coverShapeSkipsLyricChain(textOf(snapshot, "shape.type"))) return
        val maxWidth = effectiveMaxWidth(intOf(snapshot, "layout.max_width"))

        val slotExtra = dev.cdl.render.rightSlotTotalExtraWidthPx(
            dev.cdl.render.rightSlotOccupant(
                textOf(snapshot, "island.right_slot.component"),
                textOf(snapshot, "spectrum.anchor"),
            ),
            baseHeightPx.toFloat(),

            widthCoverInsetPx(),

            coverOuterPx = leadingCoverOuterSizePx(
                baseHeightPx.toFloat(),
                boolOf(snapshot, "cover.scale_with_font"),
            ),
        )
        val target = naturalWidthPx.coerceIn(MIN_ISLAND_WIDTH, maxWidth) + slotExtra
        if (target == baseWidthPx) return
        widthAnimFrom = baseWidthPx
        widthAnimTo = target
        widthAnimDurationMs = intOf(snapshot, "animation.width_grow.duration_ms")
            .toDouble().coerceIn(80.0, 500.0)
        widthAnimStyle = textOf(snapshot, "animation.width_grow.style").ifEmpty { "SMOOTH" }

        if ((glassMode && !GLASS_GROWTH_ANIM_ENABLED) ||
            !boolOf(snapshot, "animation.width_grow.enabled")
        ) {
            baseWidthPx = target
            applyWindowSize()
            return
        }
        widthAnimStartNanos = System.nanoTime()

        if (ANIM_FAST_CAPTURE_ENABLED) {
            panel.requestFastCapture(System.currentTimeMillis() + 400L + widthAnimDurationMs.toLong())
        }
        syncFrameDriver()
    }

    private fun publishNaturalWidth(widest: Float, measureAreaLeft: Float) {
        if (widest <= 0f) return

        val snap = settings?.live ?: return
        val measured = dev.cdl.render.naturalWidthForContentPx(
            widestPx = widest,
            coverInsetCollapsedPx = measureAreaLeft,
            occupant = dev.cdl.render.rightSlotOccupant(
                textOf(snap, "island.right_slot.component"),
                textOf(snap, "spectrum.anchor"),
            ),
            islandHeightPx = baseHeightPx.toFloat(),
            horizontalInsetPx = HORIZONTAL_INSET,
            glyphSlackPx = WIDTH_GLYPH_SLACK_PX,

            coverOuterPx = leadingCoverOuterSizePx(
                baseHeightPx.toFloat(),
                boolOf(snap, "cover.scale_with_font"),
            ),
        )
        if (measured <= 0) return
        if (kotlin.math.abs(measured - naturalWidthPx) <= WIDTH_HYSTERESIS_PX) return
        naturalWidthPx = measured
        javax.swing.SwingUtilities.invokeLater {
            runCatching { applyNaturalWidth() }
        }
    }

    private var boundsSetCount = 0
    private var boundsSetDuringBandAnimCount = 0

    private fun applyWindowSize(force: Boolean = false) {
        if (baseWidthPx <= 0 || baseHeightPx <= 0) return
        val cfg = controlsConfig()

        val target = windowTargetBounds(
            oldX = window.x,
            oldY = window.y,
            oldWidth = window.width,
            currentYOffsetPx = windowYShiftPx,
            baseWidthPx = baseWidthPx,
            baseHeightPx = baseHeightPx,
            stripHeightPx = controls.stripHeight(cfg),
            stripAtTop = cfg.position == ControlsConfig.TOP,

            widthGrowOverridePx = widthGrowOverridePx(),
        )

        windowYShiftPx = target.yOffsetPx

        val unchanged = window.x == target.x && window.y == target.y &&
            window.width == target.width && window.height == target.height

        if (unchanged && !force) {

            return
        }

        window.setBounds(
            target.x - FEATHER_MARGIN,
            target.y - FEATHER_MARGIN,
            target.width + FEATHER_MARGIN * 2,
            target.height + FEATHER_MARGIN * 2,
        )

        boundsSetCount++
        val bandAnimating = bandFractionNow > 0f && bandFractionNow < 1f
        if (bandAnimating) {
            boundsSetDuringBandAnimCount++
        }

        runCatching { window.validate() }

        runCatching { panel.paintImmediately(0, 0, panel.width, panel.height) }
    }

    private fun restoreSavedPosition(fresh: SettingsSnapshot) {
        val raw = textOf(fresh, "window.position").trim()
        val parsed = dev.cdl.window.parseSavedWindowPosition(raw)
        if (parsed == null) {
            window.setLocationRelativeTo(null)

            syncWindowYShift()
            return
        }
        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .maximumWindowBounds

        val restoredX = dev.cdl.window.restoredWindowX(parsed.x, parsed.width, window.width)

        val centerX = restoredX + window.width / 2
        val centerY = parsed.y + window.height / 2
        val centerInside = centerX in bounds.x until (bounds.x + bounds.width) &&
            centerY in bounds.y until (bounds.y + bounds.height)
        if (!centerInside) {

            window.setLocationRelativeTo(null)
            syncWindowYShift()
            return
        }

        val x = restoredX.coerceIn(bounds.x, (bounds.x + bounds.width - window.width).coerceAtLeast(bounds.x))
        val y = parsed.y.coerceIn(bounds.y, (bounds.y + bounds.height - window.height).coerceAtLeast(bounds.y))

        val cfg = controlsConfig()
        val shift = windowYOffsetPx(controls.stripHeight(cfg), cfg.position == ControlsConfig.TOP)
        window.setLocation(x, y - shift)
        windowYShiftPx = shift
    }

    fun quickPlace(edge: QuickPlaceEdge, onTaskbar: Boolean) = onEdt {
        if (!started) return@onEdt
        runCatching {
            val gc = window.graphicsConfiguration
                ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration
            val b = gc.bounds
            val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val target = dev.cdl.window.quickPlaceTarget(
                screenX = b.x,
                screenY = b.y,
                screenWidth = b.width,
                screenHeight = b.height,
                insetTop = ins.top,
                insetBottom = ins.bottom,
                insetLeft = ins.left,
                insetRight = ins.right,
                windowWidth = window.width,
                windowHeight = window.height,

                islandHeightPx = bandGeometryNow().islandHeight,
                yShiftPx = windowYShiftPx,
                edge = edge,
                onTaskbar = onTaskbar,
            )
            window.setLocation(target.x, target.y)

            persistPosition()

            runCatching {
                val where = when (edge) {
                    QuickPlaceEdge.TOP ->
                        if (onTaskbar) "屏幕顶部正中（任务栏上）" else "屏幕顶部正中（完整可见）"
                    QuickPlaceEdge.BOTTOM -> "屏幕底部正中"
                }
                com.xuncorp.spw.workshop.api.WorkshopApi.ui.toast(
                    "已布置到$where",
                    com.xuncorp.spw.workshop.api.WorkshopApi.Ui.ToastType.Success,
                )
            }
        }.onFailure {
        }
    }

    fun resetToPrimaryScreen() = onEdt {
        if (!started) return@onEdt
        runCatching {
            val delta = mapOf("window.position" to "")
            settings?.publish(delta)
            settings?.apply(delta)
        }.onFailure {

        }

        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val target = centeredWindowLocationForIsland(
            screenX = bounds.x,
            screenY = bounds.y,
            screenW = bounds.width,
            screenH = bounds.height,
            windowW = window.width,
            windowH = window.height,
            bg = bandGeometryNow(),
        )
        if (target != null) {
            window.setLocation(target.x, target.y)
        } else {

            window.setLocationRelativeTo(null)
        }

        syncWindowYShift()

        if (!window.isVisible) window.isVisible = true

        if (!dragTopRaised) {
            dragTopRaised = true
            raiseToTopForDrag()
        } else {

            raiseToTopForDrag()
        }

        panel.repaint()
    }

    private fun installDragHandler() {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {

                if (!SwingUtilities.isLeftMouseButton(e)) return

                window.toFront()

                runCatching {
                    window.setFocusableWindowState(true)
                    window.requestFocus()
                }

                if (!dragTopRaised) {
                    dragTopRaised = true
                    raiseToTopForDrag()
                }

                if (consumeControlPress(e)) return

                coverClickArmed = boolOf(snapshot, "island.hit.cover_playback") &&
                    panel.hitCoverGeometry(e.x.toFloat(), e.y.toFloat())
                coverPressScreen = if (coverClickArmed) e.locationOnScreen else null
                if (boolOf(snapshot, "island.lock_position")) return
                dragOrigin = e.locationOnScreen
                dragWindowOrigin = window.location
                panel.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }

            override fun mouseDragged(e: MouseEvent) {

                if (seekPreviewMs != null) {
                    updateSeekPreview(e.x)
                    return
                }
                if (controlPressConsumed) return
                val origin = dragOrigin ?: return
                val windowOrigin = dragWindowOrigin ?: return

                if (!dragTopRaised) {
                    dragTopRaised = true
                    raiseToTopForDrag()
                }
                val now = e.locationOnScreen
                window.setLocation(
                    windowOrigin.x + (now.x - origin.x),
                    windowOrigin.y + (now.y - origin.y),
                )
            }

            override fun mouseReleased(e: MouseEvent) {

                releaseTempTopmostIfFocusLeft()

                val coverPress = coverPressScreen
                val coverArmed = coverClickArmed
                coverClickArmed = false
                coverPressScreen = null

                if (seekPreviewMs != null) {
                    commitSeek(e.x)
                    return
                }
                if (controlPressConsumed) {
                    controlPressConsumed = false
                    return
                }

                if (coverArmed && coverPress != null) {
                    val up = e.locationOnScreen
                    val dx = up.x - coverPress.x
                    val dy = up.y - coverPress.y

                    val maxMove = COVER_CLICK_MAX_MOVE_PX
                    if (dx * dx + dy * dy <= maxMove * maxMove) {
                        handleCoverClick()
                    }
                }
                if (dragOrigin == null) return
                dragOrigin = null
                dragWindowOrigin = null
                panel.cursor = Cursor.getDefaultCursor()

                persistPosition()
            }
        }
        panel.addMouseListener(listener)
        panel.addMouseMotionListener(listener)
    }

    private fun installHoverHandler() {
        val listener = object : MouseAdapter() {

            override fun mouseEntered(e: MouseEvent) = onPointer(e.point)

            override fun mouseMoved(e: MouseEvent) = onPointer(e.point)

            override fun mouseExited(e: MouseEvent) {

                onPointer(pollPointer())
            }
        }
        panel.addMouseListener(listener)
        panel.addMouseMotionListener(listener)
    }

    private fun installWheelHandler() {
        panel.addMouseWheelListener { event ->

            if (!boolOf(snapshot, "wheel.volume.enabled")) return@addMouseWheelListener

            val p = event.point
            val cfg = controlsConfig()
            val inside = insideIsland(p)
            if (!wheelVolumeActive(true, hitTestAt(p, cfg, inside))) return@addMouseWheelListener

            val step = intOf(snapshot, "wheel.volume.step_percent")
            val delta = wheelVolumeStepPercent(event.wheelRotation, step)
            if (delta == 0) return@addMouseWheelListener

            val now = System.currentTimeMillis()
            val idle = now - wheelBurstLastMs > wheelBurstIdleMs
            wheelBurstLastMs = now
            val current = if (idle) {
                readVolume()
            } else {
                wheelVolumeCache ?: readVolume()
            } ?: return@addMouseWheelListener

            val next = applyWheelToVolume(current, event.wheelRotation, step)
            wheelVolumeCache =
                if (ProcessVolume.writeOf(ProcessVolume.currentProcessId(), next)) next else null
        }
    }

    private fun readVolume(): Float? =
        ProcessVolume.readOf(ProcessVolume.currentProcessId())?.also { wheelVolumeCache = it }

    private fun hitTestAt(point: Point, cfg: ControlsConfig, inside: Boolean): Boolean = when {

        !cfg.glyphTrigger -> inside

        else -> glyphHitAccepted(
            insideIsland = inside,
            glyphOrCoverHit = panel.hitGlyph(point.x.toFloat(), point.y.toFloat(), cfg.hitPaddingPx) ||
                panel.hitCover(point.x.toFloat(), point.y.toFloat()),
        )
    }

    private fun onPointer(point: Point?) {
        if (!started) return
        val cfg = controlsConfig()

        val inside = point != null && insideIsland(point)

        reportInside(inside)

        if (!window.isVisible) {

            lastHitGlyph = false
            return
        }
        lastHitGlyph = point != null && hitTestAt(point, cfg, inside)

        if (lastHitGlyph && lockBlocksExpandNow()) {
            triggerCoverLockBadge()
        }

        advanceHover(cfg, pointerEvent = true)
        updateHoveredControl(point, cfg)
    }

    private fun advanceHover(cfg: ControlsConfig, pointerEvent: Boolean) {

        val before = hover.expansion

        val hitGlyphForMachine =
            if (!hover.expanded && lockBlocksExpandNow()) false else lastHitGlyph
        val changed = hover.update(hitGlyphForMachine, lastInside, System.currentTimeMillis(), cfg, pointerEvent)

        if (!controls.hoverHighlightKept(hover.expanded, lastInside) && hoveredControl != null) {
            hoveredControl = null
            panel.repaint()
        }

        if (hover.expanded) bandFraction = maxOf(bandFraction, hover.expansion)
        if (changed) {

            if (hover.expanded) {
                bandCollapseRunning = false
                applyWindowSize()
            } else {
                startBandCollapse()
            }
            panel.repaint()
        } else if (hover.expansion != before) {

            panel.repaint()
        }
        syncHoverTicker()
    }

    private fun startBandCollapse() {
        val spec = Motions.CONTROLS_APPEAR
        bandCollapseEasing = spec.easing

        bandCollapseDurationMs = if (animator.enabled) spec.durationMs else 0L
        bandCollapseStartNanos = System.nanoTime()

        bandCollapseFrom = bandFractionNow
        bandCollapseRunning = true

        syncFrameDriver()
    }

    private fun advanceBandCollapse() {
        if (!bandCollapseRunning) return
        val elapsedMs = (System.nanoTime() - bandCollapseStartNanos) / 1_000_000L
        val t = if (bandCollapseDurationMs <= 0L) {
            1f
        } else {
            (elapsedMs.toFloat() / bandCollapseDurationMs).coerceIn(0f, 1f)
        }

        bandFraction = bandCollapseFraction(bandCollapseFrom, bandCollapseEasing.apply(t))
        if (t >= 1f) {
            bandCollapseRunning = false
            bandFraction = 0f

            applyWindowSize()
        }
        panel.repaint()
    }

    private fun resetControlsBand() {
        bandCollapseRunning = false
        bandFraction = 0f
    }

    private fun onHoverTick() {
        if (!started) {
            stopHoverTicker()
            return
        }

        val nowNanos = System.nanoTime()
        if (nowNanos - lastRaiseNanos >= RAISE_THROTTLE_MS * 1_000_000L) {
            lastRaiseNanos = nowNanos
            raiseToTopNow()
        }

        if (!shouldDriveHover(clickThroughActive)) {

            if (hover.expanded || hover.expansion > 0f || bandFraction > 0f) {
                hover.reset()
                resetControlsBand()
                applyWindowSize()
                panel.repaint()
            }
            return
        }
        if (REGIONAL_CLICK_THROUGH) {

            val point = pollPointer()
            if (point != null) {

                val inside = insideIsland(point)

                if (!window.isVisible) {
                    reportInside(inside)
                    return
                }
                applyRegionalClickThrough(point)

                onPointer(point)
                return
            }
        }

        if (!window.isVisible) return
        advanceHover(controlsConfig(), pointerEvent = false)
    }

    private fun insideIsland(local: Point): Boolean {

        return islandHitTest(local.x.toFloat(), local.y.toFloat(), bandGeometryNow())
    }

    private fun reportInside(inside: Boolean) {
        val crossed = lastInside != inside
        lastInside = inside
        if (crossed && boolOf(snapshot, "island.auto_hide_on_hover")) {
            applyVisibility(snapshot)
        }
    }

    private fun applyRegionalClickThrough(point: Point) {
        val overInteractive = runCatching {
            val cfg = controlsConfig()
            val x = point.x.toFloat()
            val y = point.y.toFloat()

            hitTestAt(point, cfg, insideIsland(point)) ||

                panel.hitCoverGeometry(x, y) ||
                controls.hit(panel.controlsLayout(), x, y) != null
        }.getOrElse { true }

        if (overInteractive == lastRegionalHit) return
        lastRegionalHit = overInteractive

        val handle = hwnd
        if (handle == 0L) return

        WindowInterop.setClickThrough(handle, !overInteractive)
    }

    private fun pollPointer(): Point? {
        val screen = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return null

        val origin = runCatching { panel.locationOnScreen }.getOrNull()
            ?: panelOriginCache
            ?: return null

        if (panel.isShowing) panelOriginCache = origin
        return Point(screen.x - origin.x, screen.y - origin.y)
    }

    private fun syncHoverTicker() {
        val cfg = controlsConfig()

        if (!started || !hover.needsTicker(cfg, clickThroughActive || REGIONAL_CLICK_THROUGH)) {
            stopHoverTicker()
            return
        }
        val wanted = if (hover.expanded && hover.expansion < 1f) FADE_TICK_MS else HOVER_TICK_MS
        val timer = hoverTimer

        if (timer != null && timer.delay != wanted) {
            timer.stop()
            hoverTimer = Timer(wanted) { onHoverTick() }.also { it.start() }
            return
        }
        val active = timer ?: Timer(wanted) { onHoverTick() }.also { hoverTimer = it }
        if (!active.isRunning) active.start()
    }

    private fun stopHoverTicker() {
        hoverTimer?.stop()
    }

    private fun startWatchdog() {
        val existing = watchdogTimer
        if (existing != null) {
            if (!existing.isRunning) existing.start()
            return
        }
        watchdogTimer = Timer(WATCHDOG_TICK_MS) { onWatchdogTick() }.apply { start() }
    }

    private fun stopWatchdog() {
        watchdogTimer?.stop()
    }

    private fun ownPopupVisible(): Boolean = runCatching {
        java.awt.Window.getWindows().any { w ->
            w !== window && w.isVisible && w is JWindow && containsVisiblePopup(w)
        }
    }.getOrDefault(false)

    private fun containsVisiblePopup(container: java.awt.Container): Boolean {
        for (child in container.components) {
            if (child is javax.swing.JPopupMenu && child.isShowing) return true
            if (child is java.awt.Container && containsVisiblePopup(child)) return true
        }
        return false
    }

    private fun raiseToTopNow() {
        if (hwnd == 0L) return
        if (!window.isVisible) return
        if (!boolOf(snapshot, "island.always_on_top")) return
        if (ownPopupVisible()) return
        WindowInterop.raiseToTop(hwnd)
    }

    private fun raiseToTopForDrag() {
        if (hwnd == 0L) return
        if (!window.isVisible) return

        if (ownPopupVisible()) return

        WindowInterop.raiseToTop(hwnd)
    }

    private fun restoreAlwaysOnTop() {
        val wanted = boolOf(snapshot, "island.always_on_top")
        runCatching { window.isAlwaysOnTop = wanted }
        if (hwnd != 0L) runCatching { WindowInterop.setAlwaysOnTop(hwnd, wanted) }
    }

    private val selfPid: Int by lazy {
        runCatching { ProcessHandle.current().pid().toInt() }.getOrDefault(0)
    }

    private fun maintainTempTopmost() {
        if (!dragTopRaised) return
        val hold = shouldHoldTempTopmost(
            manualRaised = true,
            foregroundPid = WindowInterop.foregroundProcessId(),
            selfPid = selfPid,
        )
        if (hold) {
            if (hwnd != 0L && window.isVisible) WindowInterop.raiseToTop(hwnd)
        } else {
            dragTopRaised = false
            restoreAlwaysOnTop()
        }
    }

    private fun releaseTempTopmostIfFocusLeft() = maintainTempTopmost()

    private fun onWatchdogTick() {
        if (!started) {
            stopWatchdog()
            return
        }
        val fresh = snapshot

        val observed = WindowInterop.isForegroundFullscreen(hwnd)
        if (observed == foregroundFullscreen) {

            fullscreenCandidate = null
            fullscreenCandidateTicks = 0
        } else {
            if (fullscreenCandidate == observed) {
                fullscreenCandidateTicks++
            } else {
                fullscreenCandidate = observed
                fullscreenCandidateTicks = 1
            }
            if (fullscreenCandidateTicks >= FULLSCREEN_CONFIRM_TICKS) {
                foregroundFullscreen = observed
                fullscreenCandidate = null
                fullscreenCandidateTicks = 0

                if (boolOf(fresh, "island.hide_fullscreen")) applyVisibility(fresh)
            }
        }

        raiseToTopNow()

        maintainTempTopmost()
    }

    private fun updateHoveredControl(point: Point?, cfg: ControlsConfig) {
        val next = if (point != null && hover.expanded) {
            controls.hit(panel.controlsLayout(), point.x.toFloat(), point.y.toFloat())
        } else {
            null
        }
        if (dragOrigin == null) {
            panel.cursor = cursorFor(
                hoverCursorFor(
                    hitControl = next != null,

                    overGlyph = point != null && lastHitGlyph,

                    positionLocked = boolOf(snapshot, "island.lock_position"),
                )
            )
        }
        if (next != hoveredControl) {
            hoveredControl = next
            panel.repaint()
        }
    }

    private fun cursorFor(kind: HoverCursor): Cursor = when (kind) {
        HoverCursor.HAND -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        HoverCursor.MOVE -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        HoverCursor.DEFAULT -> Cursor.getDefaultCursor()
    }

    private fun consumeControlPress(e: MouseEvent): Boolean {

        if (coverLockBadgeHit(e.x.toFloat(), e.y.toFloat()) != null) {

            expandForCoverBadge()
            return true
        }
        if (!hover.expanded) return false
        val hit = controls.hit(panel.controlsLayout(), e.x.toFloat(), e.y.toFloat()) ?: return false
        controlPressConsumed = true
        when (hit) {
            PlaybackControls.Id.PREVIOUS -> performPlayback("上一首") { playbackActions.previous() }
            PlaybackControls.Id.NEXT -> performPlayback("下一首") { playbackActions.next() }
            PlaybackControls.Id.TOGGLE -> {

                val playing = snapshotSupplier().isPlaying
                performPlayback(if (playing) "暂停" else "播放") {
                    if (playing) playbackActions.pause() else playbackActions.play()
                }
            }

            PlaybackControls.Id.PROGRESS -> {
                beginSeekGesture(e.x)
            }
        }

        panel.repaint()
        return true
    }

    private fun currentTrackKey(): String = trackKeyOf(snapshotSupplier())

    private fun progressTrackRect(): PlaybackControls.Rect? {
        val row = panel.controlsLayout().progress ?: return null
        val snap = snapshotSupplier()
        val metrics = runCatching { panel.fontMetricsOf() }.getOrNull() ?: return null
        return controls.trackRect(row, metrics, snap.positionMs, durationSupplier())
    }

    private fun seekTarget(x: Int): Long? {
        val track = progressTrackRect() ?: return null
        val metrics = runCatching { panel.fontMetricsOf() }.getOrNull() ?: return null
        return controls.seekTargetAt(track, metrics, x.toFloat(), durationSupplier())
    }

    private fun beginSeekGesture(x: Int) {

        if (seekTarget(x) == null) return

        clearCommittedSeekHold()
        seekGestureKey = currentTrackKey()
        seekPreviewMs = seekTarget(x)
        panel.repaint()
    }

    private fun updateSeekPreview(x: Int) {
        val target = seekTarget(x) ?: return
        if (target == seekPreviewMs) return
        seekPreviewMs = target
        panel.repaint()
    }

    private fun clearCommittedSeekHold() {
        committedSeekMs = null
        committedSeekDeadlineNanos = 0L
    }

    private fun progressDisplayMs(snap: TimelineSnapshot): Long {
        seekPreviewMs?.let { return it }
        val target = committedSeekMs ?: return snap.positionMs
        val now = System.nanoTime()

        if (trackKeyOf(snap) != committedSeekTrackKey) {
            clearCommittedSeekHold()
            return snap.positionMs
        }

        if (kotlin.math.abs(snap.positionMs - target) <= SEEK_CONFIRM_TOLERANCE_MS) {
            clearCommittedSeekHold()
            return snap.positionMs
        }

        if (now >= committedSeekDeadlineNanos) {
            clearCommittedSeekHold()
            return snap.positionMs
        }
        return target
    }

    private fun trackKeyOf(snap: TimelineSnapshot): String = "${snap.path}#${snap.docVersion}"

    private fun commitSeek(x: Int) {
        val gestureKey = seekGestureKey
        val hadPreview = seekPreviewMs != null
        seekGestureKey = null

        if (gestureKey != null && hadPreview) {
            val target = seekTarget(x)
            if (target != null && gestureKey == currentTrackKey()) {

                committedSeekMs = target
                committedSeekTrackKey = gestureKey
                committedSeekDeadlineNanos = System.nanoTime() + SEEK_DISPLAY_HOLD_MS * 1_000_000L

                seekPreviewMs = null
                panel.cursor = Cursor.getDefaultCursor()
                panel.repaint()
                performPlayback("跳转") { playbackActions.seekTo(target) }
                return
            }
        }

        clearCommittedSeekHold()
        seekPreviewMs = null
        panel.cursor = Cursor.getDefaultCursor()
        panel.repaint()
    }

    private inline fun performPlayback(label: String, block: () -> Unit) {
        runCatching { block() }.onFailure {
        }
    }

    private fun handleCoverClick() {

        val pending = coverClickTimer
        if (pending != null && pending.isRunning) {
            pending.stop()
            coverClickTimer = null
            performPlayback("下一首") { playbackActions.next() }
            panel.repaint()
            return
        }

        val t = Timer(coverClickDebounceMs) {
            coverClickTimer = null
            val playing = snapshotSupplier().isPlaying
            performPlayback(if (playing) "暂停" else "播放") {
                if (playing) playbackActions.pause() else playbackActions.play()
            }

            panel.repaint()
        }
        t.isRepeats = false
        coverClickTimer = t
        t.start()
    }

    private fun persistPosition() {
        val runtime = settings ?: return
        val location = window.location

        val shiftedUp = windowYShiftPx

        val value = "${location.x},${location.y + shiftedUp},${window.width}"

        runCatching { runtime.apply(mapOf("window.position" to value)) }
            .onFailure {  }

        raiseToTopNow()
    }

    private fun initGlassMode() {

        val fromSettings = runCatching {
            textOf(snapshot, "glass.material").ifEmpty { "NONE" } != "NONE"
        }.getOrDefault(false)
        glassMode = fromSettings
    }

    private fun resolveHwnd() {
        if (!WindowInterop.available) {
            return
        }

        val direct = WindowInterop.hwndOf(window)
        if (direct != null) {
            hwnd = direct
            return
        }
        SwingUtilities.invokeLater {

            val retryDirect = WindowInterop.hwndOf(window)
            if (retryDirect != null) {
                hwnd = retryDirect
                applyExtensionStyles(snapshot)
                return@invokeLater
            }

            val byTitle = WindowInterop.findHwndByTitle(windowTitle)
            if (byTitle != null) {
                hwnd = byTitle
                applyExtensionStyles(snapshot)
            } else {
            }
        }
    }

    private fun applyExtensionStyles(fresh: SettingsSnapshot) {
        val alwaysOnTop = boolOf(fresh, "island.always_on_top")

        runCatching { window.isAlwaysOnTop = alwaysOnTop }

        val handle = hwnd
        if (handle == 0L) {

            return
        }

        if (glassMode) {
            val layeredBefore = dev.cdl.window.WindowInterop.isLayered(handle)
            val cleared = dev.cdl.window.WindowInterop.clearLayered(handle)
            val layeredAfter = dev.cdl.window.WindowInterop.isLayered(handle)
            if (!layeredAfter) {

                runCatching {
                    val ok = dev.cdl.platform.CaptureExclusion.applyTo(handle)

                    val realHwnd = runCatching {
                        dev.cdl.window.WindowInterop.hwndOf(window) ?: 0L
                    }.getOrDefault(0L)
                    val readBack = runCatching {
                        val arr = IntArray(1)
                        dev.cdl.platform.CaptureExclusion.query(handle, arr)
                        arr[0]
                    }.getOrDefault(-1)

                    if (glassAaEnabled()) {
                        runCatching {
                            dev.cdl.window.UIAA.enable(handle)
                        }.onFailure {
                        }
                    }
                }
            } else {
            }
        }

        val clickThrough = clickThroughFromSetting(boolOf(fresh, "island.interactive"))

        val effectiveClickThrough = if (REGIONAL_CLICK_THROUGH && !clickThrough) {
            !lastRegionalHit
        } else {
            clickThrough
        }
        WindowInterop.setClickThrough(handle, effectiveClickThrough)

        if (clickThroughActive != clickThrough) {
            clickThroughActive = clickThrough

            if (clickThrough) {
                hover.reset()

                resetControlsBand()
                runCatching { applyWindowSize() }
                runCatching { panel.repaint() }
            }
            syncHoverTicker()
        }

        WindowInterop.setAlwaysOnTop(handle, alwaysOnTop)

        WindowInterop.setToolWindow(handle, true)
    }

    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }

    private fun supportsPerPixelTranslucency(): Boolean = runCatching {
        val device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        device.isWindowTranslucencySupported(
            java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
        )
    }.getOrDefault(false)

    private fun boolOf(fresh: SettingsSnapshot, key: String): Boolean {
        val descriptor = SettingRegistry.descriptor(key) ?: return false
        return runCatching { fresh.boolean(descriptor) }.getOrDefault(descriptor.defaultValueBoolean())
    }

    private fun intOf(fresh: SettingsSnapshot, key: String): Int {
        val descriptor = SettingRegistry.descriptor(key) ?: return 0
        return runCatching { fresh.int(descriptor) }.getOrDefault(descriptor.defaultValueInt())
    }

    private fun textOf(fresh: SettingsSnapshot, key: String): String {
        val descriptor = SettingRegistry.descriptor(key) ?: return ""
        return runCatching { fresh.text(descriptor) }.getOrDefault(descriptor.defaultValueText())
    }

    private fun controlsConfig(fresh: SettingsSnapshot = snapshot): ControlsConfig {
        val defaults = ControlsConfig.DEFAULTS
        return ControlsConfig(
            triggerMode = textOf(fresh, "controls.triggerMode").ifEmpty { defaults.triggerMode },
            enterDelayMs = intOf(fresh, "controls.enterDelayMs").toLong(),
            exitDelayMs = intOf(fresh, "controls.exitDelayMs").toLong(),
            hitPaddingPx = intOf(fresh, "controls.hitPaddingPx").toFloat(),
            position = textOf(fresh, "controls.position").ifEmpty { defaults.position },
            gapPx = intOf(fresh, "controls.gapPx").toFloat(),
            showProgress = boolOf(fresh, "controls.showProgress"),
            autoHideMs = intOf(fresh, "controls.autoHideMs").toLong(),

            progressStyle = textOf(fresh, "controls.progress_style").ifEmpty { defaults.progressStyle },
        )
    }

    private class LinearBrushMemo(

        private val colorOf: (Int) -> Color,

        private val baseAlpha: Float,
    ) {
        private var cached: Paint? = null
        private var geomKey: Long = 0L
        private var colorKey: Int = 0
        private var alphaKey: Int = -1

        fun verticalFade(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,
            alphaScale: Float = 1f,
        ): Paint {
            val geomKey = geomKeyOf(left, bottom, top)
            val alphaKey = (alphaScale * 100f).toInt()
            val cached = this.cached
            if (cached != null && this.geomKey == geomKey &&
                this.colorKey == packed && this.alphaKey == alphaKey
            ) {
                return cached
            }
            val paint = buildVerticalFade(packed, left, bottom, top, alphaScale)
            this.cached = paint
            this.geomKey = geomKey
            this.colorKey = packed
            this.alphaKey = alphaKey
            return paint
        }

        fun solidProgress(packed: Int, alphaScale: Float): Paint {
            val key = (alphaScale * 100f).toInt()
            val cached = this.cached
            if (cached != null && this.colorKey == packed && this.alphaKey == key) {
                return cached
            }
            val base = colorOf(packed)
            val paint = Color(
                base.red, base.green, base.blue,
                (255 * baseAlpha * alphaScale.coerceIn(0f, 1f)).toInt(),
            )
            this.cached = paint
            this.colorKey = packed
            this.alphaKey = key
            return paint
        }

        private fun geomKeyOf(left: Float, bottom: Float, top: Float): Long =
            (left.toInt().toLong() shl 42) or
                (bottom.toInt().toLong() shl 21) or
                top.toInt().toLong()

        private fun buildVerticalFade(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,
            alphaScale: Float,
        ): Paint {
            val base = colorOf(packed)
            val solid = Color(
                base.red, base.green, base.blue,
                (255 * baseAlpha * alphaScale.coerceIn(0f, 1f)).toInt(),
            )
            val clear = Color(base.red, base.green, base.blue, 0)
            return LinearGradientPaint(
                left, bottom, left, top,
                floatArrayOf(0f, spectrumFadeStartFraction(bottom - top), 1f),
                arrayOf<Color>(solid, solid, clear),
            )
        }
    }

    private inner class IslandPanel : JPanel() {

        private var supersetRect: java.awt.Rectangle? = null

        private var spectrumFadeLayer: java.awt.image.BufferedImage? = null

        private fun spectrumFadeLayerOf(w: Int, h: Int): java.awt.image.BufferedImage {
            val cur = spectrumFadeLayer
            if (cur != null && cur.width == w && cur.height == h) return cur
            val made = java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB,
            )
            spectrumFadeLayer = made
            return made
        }

        @Volatile
        var appearance: Appearance = Appearance.from(snapshot)

        private val backgroundBrush = LinearBrushMemo(
            colorOf = { renderCache.color(it) },
            baseAlpha = SPECTRUM_BACKGROUND_ALPHA,
        )

        private val progressBrush = LinearBrushMemo(
            colorOf = { renderCache.color(it) },
            baseAlpha = SPECTRUM_BACKGROUND_ALPHA,
        )

        private val solidProgressBrush = LinearBrushMemo(
            colorOf = { renderCache.color(it) },
            baseAlpha = SPECTRUM_BACKGROUND_ALPHA,
        )

        private val drawnRows = ArrayList<DrawnRow>()

        private var timingMainDesc: String? = null
        private var timingSubDesc: String? = null

        private var lastTimingDesc: String? = null

        private var drawnCoverBounds: Shape? = null

        fun drawnCoverRect(): java.awt.geom.Rectangle2D? =
            drawnCoverBounds?.bounds2D

        fun needsAnimationFrames(): Boolean {
            if (!window.isShowing) return false

            if (appearance.spectrumEnabled &&
                (snapshotSupplier().isPlaying || !SpectralPainter.isSilent(spectrumCache))
            ) {
                return true
            }

            if (appearance.coverRotate && appearance.coverEnabled && snapshotSupplier().isPlaying) {
                return true
            }

            return animator.hasRunning() || bandCollapseRunning || coverLockBadgeVisible()
        }

        init {

            isOpaque = false

        }

        fun hitGlyph(x: Float, y: Float, padding: Float): Boolean {
            for (row in drawnRows) {

                runCatching {
                    glyphHitTester.hitTest(
                        cache = renderCache,
                        layout = row.layout,
                        text = row.text,
                        x = x,
                        y = y,
                        baselineX = row.baselineX,
                        baselineY = row.baselineY,
                        padding = padding,
                    )
                }.getOrNull() ?: continue
                return true
            }
            return false
        }

        fun hitCover(x: Float, y: Float): Boolean =
            appearance.hitCoverEnabled && hitCoverGeometry(x, y)

        fun hitCoverGeometry(x: Float, y: Float): Boolean =
            drawnCoverBounds?.contains(x.toDouble(), y.toDouble()) == true

        fun controlsLayout(): PlaybackControls.Layout =
            controls.layout(width.toFloat(), height.toFloat(), controlsConfig(), hover.expanded)

        private fun controlsDrawLayout(): PlaybackControls.Layout {
            val cfg = controlsConfig()
            return islandDrawLayout(
                controls = controls,
                widthPx = width,
                baseHeightPx = baseHeightPx,
                stripHeightPx = controls.stripHeight(cfg),
                cfg = cfg,
            )
        }

        fun fontMetricsOf(): java.awt.FontMetrics {
            val g = graphics ?: error("面板尚未 realized，无法取 FontMetrics")
            return try {
                g.font = PlaybackControls.labelFont()
                g.fontMetrics
            } finally {
                g.dispose()
            }
        }

        private fun syncGlassTierIfChanged(local: Appearance) {

            val wantGlassNow = runCatching {
                textOf(snapshot, "glass.material").ifEmpty { "NONE" } != "NONE"
            }.getOrDefault(false)
            if (wantGlassNow != glassMode) {
                glassMode = wantGlassNow
                val bgColor = if (wantGlassNow) Color(0x10, 0x10, 0x10) else Color(0, 0, 0, 0)
                window.background = bgColor
                window.contentPane.background = bgColor

                val layeredOk = dev.cdl.window.WindowInterop.setLayered(hwnd, !wantGlassNow)
                runCatching { syncL3(local) }

                runCatching { applyWindowSize(force = true) }

                runCatching { resetControlsBand() }

                val nowDiag = System.currentTimeMillis()
                if (nowDiag - lastGlassDiagAt > 1000L) {
                    lastGlassDiagAt = nowDiag
                }

                javax.swing.SwingUtilities.invokeLater { runCatching { window.repaint() } }
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )

                g2.translate(FEATHER_MARGIN.toDouble(), FEATHER_MARGIN.toDouble())
                if (glassAaEnabled()) {
                    g2.composite = java.awt.AlphaComposite.Src
                    g2.color = java.awt.Color(0, 0, 0, 0)
                    g2.fillRect(0, 0, width, height)
                    g2.composite = java.awt.AlphaComposite.SrcOver
                }

                if (fadeAlpha < 1f) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fadeAlpha)
                }

                if (fadeScale != 1f) {

                    val cx = (this.width / 2f).toDouble()
                    val cy = (this.height / 2f).toDouble()
                    g2.translate(cx, cy)
                    g2.scale(fadeScale.toDouble(), fadeScale.toDouble())
                    g2.translate(-cx, -cy)
                }
                val local = appearance
                val width = width
                val height = height
                if (width <= 0 || height <= 0) return

                syncGlassTierIfChanged(local)

                drawnRows.clear()

                drawnCoverBounds = null

                val coverImage = coverImageFor(local)

                val coverPaint = resolveCoverMix(coverImage, local.coverEnabled)

                val coverRgbRaw = resolveCoverRgb(coverImage)

                val coverPalette = coverPaletteFor(coverImage)

                val flowPhase = dev.cdl.render.flowPhase(
                    snapshotSupplier().positionMs,
                    FLOW_PERIOD_MS,
                    local.materialFlowSpeed,
                )
                coverRgbShown = coverRgbRaw

                val coverRgb = blendedCoverRgb(coverRgbRaw, coverPaint)

                val bg = bandGeometryNow()

                run {
                    val nowDiag = System.currentTimeMillis()
                    if (nowDiag - lastGlassDiagAt > 1000L) {
                        lastGlassDiagAt = nowDiag
                    }
                }

                val outline = islandOutlineAt(
                    shapeType = local.shapeType,
                    cornerRadiusPercent = local.cornerRadiusPercent,

                    radiusBasisPx = islandRadiusBasisPx(
                        bg.islandWidth.toFloat().takeIf { it > 0f } ?: baseWidthPx.toFloat(),
                        baseHeightPx.toFloat(),
                    ),
                    fraction = bg.fraction,
                )
                val shape = AffineTransform
                    .getTranslateInstance(bg.islandLeft.toDouble(), bg.islandTop.toDouble())
                    .createTransformedShape(
                        islandShapeOf(
                            width = bg.islandWidth.toFloat(),
                            height = bg.islandHeight.toFloat(),
                            topRadiusPx = outline.topRadiusPx,
                            bottomRadiusPx = outline.bottomRadiusPx,

                            cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                            cornerN = local.cornerN,
                        ),
                    )

                if (local.materialQuality != "OFF" &&
                    MaterialLayers.shouldDrawDropShadow(local.materialLiquidShadowStrength)
                ) {
                    val sb = shape.bounds2D

                    MaterialLayers.drawDropShadow(
                        g = g2,
                        x = sb.x, y = sb.y, w = sb.width, h = sb.height,
                        cornerRadius = maxOf(outline.topRadiusPx, outline.bottomRadiusPx).toDouble(),
                        percent = local.materialLiquidShadowStrength,
                        stripAtTop = controlsConfig().position == ControlsConfig.TOP,
                    )
                }

                val setShapeEnabled = !glassAaEnabled()
                if (glassMode && setShapeEnabled) {
                    val sb = shape.bounds
                    val shapeKey = "${sb.x.toInt()}:${sb.y.toInt()}:" +
                        "${sb.width.toInt()}:${sb.height.toInt()}|${width}x$height"
                    if (shapeKey != lastWindowShapeKey) {
                        lastWindowShapeKey = shapeKey
                        shapeSetCount += 1L
                        runCatching { window.shape = shape }
                    }
                }

                paintL3Backdrop(
                    g2 = g2,
                    local = local,
                    shape = shape,
                    islandLeft = bg.islandLeft,
                    islandTop = bg.islandTop,
                    islandWidth = bg.islandWidth,
                    islandHeight = bg.islandHeight,
                )

                if (local.backgroundColorMode == BACKGROUND_MODE_COVER_IMAGE && coverImage != null) {
                    drawCoverImageBackdrop(
                        g2 = g2,
                        local = local,
                        shape = shape,
                        src = coverImage,
                        coverRgb = coverRgbRaw,

                        islandLeft = bg.islandLeft,
                        islandTop = bg.islandTop,
                        islandWidth = bg.islandWidth,
                        islandHeight = bg.islandHeight,
                    )
                } else {
                    val base = backgroundWithOpacity(local, coverRgb)

                    val dbgGlass = glassMode

                    run {
                        val iw = ownFrame?.islandWidth ?: 0
                        val ih = ownFrame?.islandHeight ?: 0
                        if (iw <= 0 || ih <= 0) return@run
                        val nowM = System.currentTimeMillis()
                        if (iw != lastMaskW || ih != lastMaskH) {

                            pendingMaskW = iw
                            pendingMaskH = ih
                            pendingMaskAt = nowM
                        } else if (pendingMaskAt > 0L && nowM - pendingMaskAt > 150L) {

                            pendingMaskAt = 0L
                            runCatching { syncL3(appearance) }
                        }
                    }

                    if (glassMode && l3State == null && width > 0 && height > 0) {
                        runCatching { syncL3(appearance) }
                    }

                    l3State?.fastRate = (bg.fraction > 0f && bg.fraction < 1f) ||
                        System.currentTimeMillis() < dragFastUntilMs

                    val nowTick = System.currentTimeMillis()
                    if (glassMode && nowTick - lastLayeredCheckAt > 1000L) {
                        lastLayeredCheckAt = nowTick
                        if (dev.cdl.window.WindowInterop.isLayered(hwnd)) {
                            val ok = dev.cdl.window.WindowInterop.clearLayered(hwnd)
                        }
                    }
                    val glassComposited = l3State?.composited == true && l3State?.backdrop != null

                    val glassOverBackdrop =
                        (dbgGlass || glassComposited) && l3State?.backdrop != null
                    if (!glassOverBackdrop) {
                        g2.color = base
                        g2.fill(shape)
                    }

                    val surfaceSuppressed = glassComposited

                    MaterialLayers.paint(
                        g = g2,
                        shape = shape,
                        highlightStrength = local.materialHighlightStrength,

                        gradientStyle = local.materialGradientStyle,

                        flowPaletteArgb = coverPalette,

                        flowPaletteShares = coverPaletteShares.takeIf { it.isNotEmpty() },

                        flowPhase = flowPhase,
                        gradientTintPercent = local.materialGradientTintPercent,
                        gradientAngleDeg = local.materialGradientAngleDeg,
                        gradientEndArgb = local.materialGradientEndArgb,
                        baseColor = base,
                        noiseAmplitude = local.materialNoiseAmplitude,
                        quality = local.materialQuality,

                        liquidGlarePercent = local.materialLiquidGlarePercent,
                        liquidDepthEffect = local.materialLiquidDepthEffect,

                        liquidSurfaceAlphaPercent = local.materialLiquidSurfaceAlpha,

                        shapeCornerRadius = outline.topRadiusPx.toDouble(),
                        shapeCornerRadiusBottom = outline.bottomRadiusPx.toDouble(),

                        shapeRoundness = dev.cdl.render.GlassSdf.DEFAULT_ROUNDNESS,

                        glassAnimating = bg.fraction > 0f && bg.fraction < 1f,

                        surfaceOpacityPercent = local.opacityPercent,

                        surfaceSuppressed = surfaceSuppressed,
                    )
                }

                val oldClip = g2.clip
                try {
                    g2.clip = shape

                    val layout = controlsDrawLayout()

                    val coverBox = bg.coverRect(
                        local.coverScaleWithFont,
                        dev.cdl.render.sideGapPx(bg.lyricsHeight),
                    )
                    val coverSize = leadingCoverSize(
                        local = local,
                        image = coverPaint.shown,
                        geometry = bg,
                        reserveWhileFading = coverPaint.prev != null,
                    )

                    val coverSizeCollapsed = coverSize

                    val slotOccupantEarly = dev.cdl.render.rightSlotOccupant(
                        textOf(snapshot, "island.right_slot.component"),
                        textOf(snapshot, "spectrum.anchor"),
                    )

                    val rightBandWidthEarly = dev.cdl.render.rightSlotWidthPx(
                        slotOccupantEarly,
                        layout.lyricsHeight,
                        coverOuterPx = leadingCoverOuterSizePx(
                            layout.lyricsHeight,
                            local.coverScaleWithFont,
                        ),
                    )

                    val sideGap = dev.cdl.render.sideGapPx(layout.lyricsHeight)
                    val coverInsetRel = leadingCoverAreaLeftPx(
                        outerSizePx = coverSize ?: 0f,
                        leadingInsetPx = sideGap,
                        leadingGapPx = sideGap,
                        slotWidthPx = if (coverSize != null) {
                            coverSlotWidth(coverSize, local.coverVinyl)
                        } else {
                            0f
                        },
                    )

                    val sideInsetRel = dev.cdl.render.lyricSideInsetPx(
                        coverInsetRel,
                        if (dev.cdl.render.rightSlotOccupied(
                                textOf(snapshot, "island.right_slot.component"),
                                textOf(snapshot, "spectrum.anchor"),
                            )
                        ) {
                            rightBandWidthEarly
                        } else {
                            0f
                        },
                    )
                    val areaLeftRel = sideInsetRel

                    val areaLeft = bg.islandLeft + areaLeftRel

                    val measureAreaLeft = leadingCoverAreaLeftPx(
                        outerSizePx = coverSizeCollapsed ?: 0f,
                        leadingInsetPx = sideGap,
                        leadingGapPx = sideGap,
                        slotWidthPx = if (coverSizeCollapsed != null) {
                            coverSlotWidth(coverSizeCollapsed, local.coverVinyl)
                        } else {
                            0f
                        },
                    )

                    measureCoverInsetPx = measureAreaLeft

                    val rightBand = dev.cdl.render.rightSlotOccupied(
                        textOf(snapshot, "island.right_slot.component"),
                        textOf(snapshot, "spectrum.anchor"),
                    )

                    val slotOccupant = slotOccupantEarly

                    val clockNowMs = if (slotOccupant == dev.cdl.render.RIGHT_SLOT_CLOCK) {
                        System.currentTimeMillis()
                    } else {
                        0L
                    }
                    val clockText: String? =
                        if (slotOccupant == dev.cdl.render.RIGHT_SLOT_CLOCK) {

                            dev.cdl.render.ClockText.format(
                                clockNowMs,
                                java.time.ZoneId.systemDefault(),
                                textOf(snapshot, "clock.hour_format"),
                            )
                        } else {
                            null
                        }

                    val clockColonVisible = dev.cdl.render.ClockText.colonVisible(clockNowMs)

                    val clockStyle = dev.cdl.render.ClockGlyphs.normalizeStyle(
                        textOf(snapshot, "clock.style"),
                    )

                    val rightBandWidth = rightBandWidthEarly

                    val textRightEdge = if (rightBand) {
                        (bg.islandRight - sideInsetRel).coerceAtLeast(areaLeft + 1f)
                    } else {
                        bg.islandRight
                    }
                    val textWidth = (textRightEdge - areaLeft).toInt().coerceAtLeast(1)

                    val skipLyricChain = coverShapeSkipsLyricChain(local.shapeType)
                    if (!skipLyricChain) {

                    val slotAreaWidth = if (rightBand) {

                        rightBandWidth.coerceAtMost(bg.islandRight - areaLeft).coerceAtLeast(1f)
                    } else {
                        textWidth.toFloat()
                    }

                    val slotBoxLeft = if (rightBand) {
                        dev.cdl.render.rightSlotBoxLeftPx(
                            islandRightPx = bg.islandRight,
                            bandInsetPx = sideInsetRel,
                            boxWidthPx = slotAreaWidth,

                            minLeftPx = textRightEdge,
                        )
                    } else {
                        areaLeft
                    }

                    if (slotOccupant == dev.cdl.render.RIGHT_SLOT_CLOCK && clockText != null) {

                        val minInk = dev.cdl.render.minInkPxFor(layout.lyricsHeight)
                        val dw = dev.cdl.render.ClockGlyphs.fitDigitWidth(
                            clockStyle, clockText, slotAreaWidth, minInk,
                        )

                        if (dw != null) {
                            val dh = dev.cdl.render.ClockGlyphs.digitHeightPx(clockStyle, dw)

                            val clockBoxLeft = slotBoxLeft

                            val polys = dev.cdl.render.ClockGlyphs.centeredTextPolygons(
                                clockStyle,
                                clockText,
                                clockBoxLeft,
                                layout.lyricsTop,
                                slotAreaWidth,
                                layout.lyricsHeight,

                                colonVisible = clockColonVisible,

                                minInkPx = dev.cdl.render.minInkPxFor(layout.lyricsHeight),
                            )
                            val oldPaint = g2.paint

                            g2.paint = local.sungColor
                            for (poly in polys) {
                                val path = java.awt.geom.Path2D.Float()
                                path.moveTo(poly[0], poly[1])
                                for (i in 2 until poly.size step 2) path.lineTo(poly[i], poly[i + 1])
                                path.closePath()
                                g2.fill(path)
                            }
                            g2.paint = oldPaint
                        }
                    }

                    val anchorNow = textOf(snapshot, "spectrum.anchor")

                    val coverSidePx = bg.coverOuterSizePx(local.coverScaleWithFont)
                    val coverTopPx = bg.coverTopFor(coverSidePx)
                    val slotArea = dev.cdl.render.polarSlotArea(
                        slotOccupant,
                        bg.islandRight,
                        coverTopPx,
                        coverSidePx,

                        bandInsetPx = sideInsetRel,

                        coverOuterPx = bg.coverOuterSizePx(local.coverScaleWithFont),
                    )
                    val slotRectNow = java.awt.Rectangle(
                        slotArea[0].toInt(),
                        slotArea[1].toInt(),
                        slotArea[2].toInt().coerceAtLeast(1),
                        slotArea[3].toInt().coerceAtLeast(1),
                    )

                    fun paintSpectrumTarget(
                        areaLeftForTarget: Float,
                        areaWidthForTarget: Float,
                        occupantForTarget: String,

                        areaTopForTarget: Float,
                        areaHeightForTarget: Float,

                        islandBox: FloatArray,
                    ) {
                        drawBackgroundSpectrum(
                            g2 = g2,
                            local = local,

                            bands = spectrumBandsSupplier(),
                            areaLeft = areaLeftForTarget,
                            areaTop = areaTopForTarget,
                            areaWidth = areaWidthForTarget,
                            areaHeight = areaHeightForTarget,

                            coverRgb = coverRgb,
                            playing = snapshotSupplier().isPlaying,
                            slotRect = slotRectNow,
                            slotOccupant = occupantForTarget,

                            peakHold = false,

                            topRadiusPx = outline.topRadiusPx,
                            bottomRadiusPx = outline.bottomRadiusPx,

                            coverPalette = coverPalette,
                            flowPhase = flowPhase,

                            islandBox = islandBox,
                        )
                    }

                    if (dev.cdl.render.shouldDrawSpectrumBand(anchorNow, local.spectrumEnabled)) {
                        paintSpectrumTarget(
                            areaLeftForTarget = areaLeft,
                            areaWidthForTarget = textWidth.toFloat(),
                            occupantForTarget = dev.cdl.render.RIGHT_SLOT_NONE,

                            areaTopForTarget = layout.lyricsTop,
                            areaHeightForTarget = layout.lyricsHeight,
                            islandBox = floatArrayOf(
                                bg.islandLeft, bg.islandTop,
                                bg.islandWidth.toFloat(), bg.islandHeight.toFloat(),
                            ),
                        )
                    }

                    val islandBounds = shape.bounds2D
                    applyDissolveMask(
                        g2 = g2,
                        local = local,
                        islandLeftPx = islandBounds.x.toFloat(),
                        islandTopPx = islandBounds.y.toFloat(),
                        islandWidthPx = islandBounds.width.toFloat(),
                        islandHeightPx = islandBounds.height.toFloat(),
                        topRadiusPx = outline.topRadiusPx,
                        bottomRadiusPx = outline.bottomRadiusPx,
                    )

                    if (dev.cdl.render.shouldDrawSpectrumInSlot(slotOccupant)) {

                        val coverBox = dev.cdl.render.smallSpectrumAreaPx(
                            coverTopPx = bg.coverTopFor(bg.coverOuterSizePx(local.coverScaleWithFont)),
                            coverOuterSizePx = bg.coverOuterSizePx(local.coverScaleWithFont),
                        )
                        paintSpectrumTarget(

                            areaLeftForTarget = slotBoxLeft.coerceAtLeast(areaLeft),
                            areaWidthForTarget = slotAreaWidth,
                            occupantForTarget = slotOccupant,
                            areaTopForTarget = coverBox[0],
                            areaHeightForTarget = coverBox[1],
                            islandBox = floatArrayOf(
                                bg.islandLeft, bg.islandTop,
                                bg.islandWidth.toFloat(), bg.islandHeight.toFloat(),
                            ),
                        )
                    }

                    drawLyricSlots(
                        g2, local, textWidth, areaLeft, layout.lyricsTop, layout.lyricsHeight, coverRgb,
                        measureAreaLeft = measureAreaLeft,
                    )
                    }

                    if (skipLyricChain) {
                        val waterRect = CoverWaterLevel.waterFillRect(
                            islandLeft = bg.islandLeft,
                            islandTop = bg.islandTop,
                            islandWidth = bg.islandWidth.toFloat(),
                            islandHeight = bg.islandHeight.toFloat(),
                            progress = CoverWaterLevel.progressOf(
                                snapshotSupplier().positionMs,
                                durationSupplier(),
                            ),
                        )
                        if (waterRect != null) {
                            CoverWaterLevel.paint(
                                g = g2,
                                rect = waterRect,

                                accent = CoverPalette.resolve(
                                    mode = CoverPalette.COVER,
                                    manual = local.sungColor,
                                    coverRgb = coverRgb,
                                ) { CoverPalette.brightFrom(it) },
                                bands = spectrumBandsSupplier(),

                                phase = CoverWaterLevel.wavePhase(
                                    snapshotSupplier().positionMs,
                                    COVER_WATER_WAVE_PERIOD_MS,
                                ),

                                baseColor = local.backgroundColorFor(coverRgb),
                            )
                        }
                    }

                    if (coverSize != null) {

                        drawCover(
                            g2, local, coverPaint, coverSize,

                            coverLeft = coverBox.x,
                            coverTop = coverBox.y,

                            positionMs = snapshotSupplier().positionMs,
                        )
                    }

                    if (skipLyricChain && bandFractionNow > 0f) {
                        drawNoLyricsNotice(
                            g2, local, textWidth, areaLeft, layout.lyricsTop, layout.lyricsHeight,
                            snapshotSupplier(), coverRgb,
                            forceShowCredits = true,
                        )

                        val badge = if (coverLockBadgeVisible() && local.lockCover) {
                            coverLockBadgeRect(coverBox)
                        } else {
                            null
                        }
                        if (badge != null) {
                            drawCoverLockBadge(g2, badge)
                        }
                    }
                    drawControls(g2, local, layout)

                    if (needsAnimationFrames() && frameTimer?.isRunning != true) {
                        syncFrameDriver()
                    }
                } finally {

                    g2.clip = oldClip
                }

                drawIslandBorder(
                    g2 = g2,
                    local = local,
                    islandLeft = bg.islandLeft,
                    width = bg.islandWidth,
                    islandTop = bg.islandTop,
                    height = bg.islandHeight,
                    topRadiusPx = outline.topRadiusPx,
                    bottomRadiusPx = outline.bottomRadiusPx,
                    coverRgb = coverRgb,
                )

                publishOwnFrame(
                    shape = shape,
                    islandLeft = bg.islandLeft,
                    islandTop = bg.islandTop,
                    islandWidth = bg.islandWidth,
                    islandHeight = bg.islandHeight,
                )
            } finally {

                g2.dispose()
            }
        }

        private fun publishOwnFrame(
            shape: Shape,
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ) {
            ownFrameSeq += 1L
            val armed = ownFrameCaptureArmed

            val snapshot: java.awt.image.BufferedImage? = if (armed) {
                captureOwnRegion(islandLeft, islandTop, islandWidth, islandHeight)
            } else {
                null
            }
            ownFrame = OwnFrame(
                seq = ownFrameSeq,
                shape = shape,
                islandLeft = islandLeft,
                islandTop = islandTop,
                islandWidth = islandWidth,
                islandHeight = islandHeight,

                pixels = snapshot,
            )

            lastIslandW = islandWidth
            lastIslandH = islandHeight
        }

        private fun captureOwnRegion(
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ): java.awt.image.BufferedImage? = runCatching {
            if (islandWidth <= 0 || islandHeight <= 0) return@runCatching null
            val robot = ownCaptureRobot ?: return@runCatching null
            val origin = locationOnScreen ?: return@runCatching null
            val x = origin.x + islandLeft.toInt()
            val y = origin.y + islandTop.toInt()

            robot.createScreenCapture(java.awt.Rectangle(x, y, islandWidth, islandHeight))
        }.getOrNull()

        private val ownCaptureRobot: java.awt.Robot? by lazy {
            runCatching { java.awt.Robot() }.getOrElse { e ->
                Unit
                null
            }
        }

        @Volatile
        var ownFrame: OwnFrame? = null
            private set

        @Volatile
        var ownFrameSeq: Long = 0L
            private set

        @Volatile
        var ownFrameCaptureArmed: Boolean = false

        fun armOwnFrameCapture(armed: Boolean) {
            ownFrameCaptureArmed = armed
            if (!armed) ownFrame = null
        }

        @Volatile
        var l3State: dev.cdl.render.L3BackdropState? = null
            private set

        @Volatile
        private var l3MaskRaster: dev.cdl.render.ShapeMaskRaster? = null

        @Volatile
        private var l3Worker: dev.cdl.render.L3CaptureWorker? = null

        @Volatile
        private var lastL3FrameLogEnabled: Boolean = false

        @Volatile
        private var lastL3RefractionKey: String = ""

        private fun l3RefractionKey(
            local: Appearance,
            cornerRadiusTop: Double,
            cornerRadiusBottom: Double,
        ): String = dev.cdl.window.l3WorkerKey(

            blurRadius = local.glassBlurRadius,
            frameLogEnabled = local.frameLogEnabled,
            refractionHeight = 16,
            refractionAmount = 32,
            dispersion = local.glassDispersionPercent,
            saturation = local.glassSaturationPercent,
            cornerRadiusTop = cornerRadiusTop,
            cornerRadiusBottom = cornerRadiusBottom,

            gpuBackend = true,
        )

        val isL3Running: Boolean get() = l3State != null

        fun ensureL3Started() {
            if (l3State != null) return
            if (width <= 0 || height <= 0) return
            runCatching { syncL3(appearance) }
            if (l3State != null) {
            }
        }

        fun syncL3(local: Appearance) {

            if (!glassMode) {

                l3Worker?.stop()
                l3Worker = null
                l3State = null

                lastL3FrameLogEnabled = false
                lastL3RefractionKey = ""
                armOwnFrameCapture(false)
                return
            }
            val radius = local.glassBlurRadius.coerceAtLeast(1)
            val existing = l3State

            val wCached = lastIslandWidthForL3()
            val hCached = lastIslandHeightForL3()
            val w = if (wCached > 0) wCached else width
            val h = if (hCached > 0) hCached else height
            if (w <= 0 || h <= 0) return

            val radiusBasis = islandRadiusBasisPx(
                w.toFloat().takeIf { it > 0f } ?: baseWidthPx.toFloat(),
                baseHeightPx.toFloat(),
            )
            val outline = islandOutlineAt(
                shapeType = local.shapeType,
                cornerRadiusPercent = local.cornerRadiusPercent,
                radiusBasisPx = radiusBasis,
                fraction = bandFractionNow,
            )

            run {
                val key = "${local.shapeType}/${local.cornerRadiusPercent}/${radiusBasis.toInt()}/" +
                    "${outline.topRadiusPx.toInt()}/${outline.bottomRadiusPx.toInt()}/$bandFractionNow"
                if (key != glassShapeLogged) {
                    glassShapeLogged = key
                }
            }
            val glassCornerRadius = outline.topRadiusPx.toDouble()
            val glassCornerRadiusBottom = outline.bottomRadiusPx.toDouble()

            val refractionKey = l3RefractionKey(local, glassCornerRadius, glassCornerRadiusBottom)
            if (existing != null && l3Worker?.running?.get() == true &&
                lastL3FrameLogEnabled == local.frameLogEnabled &&
                lastL3RefractionKey == refractionKey &&

                lastMaskW == w && lastMaskH == h &&
                lastL3Frosted == local.glassFrosted &&
                lastL3GlassKey == glassKeyOf(local)
            ) {
                return
            }

            val state = dev.cdl.render.L3BackdropState()

            runCatching { state.seedFrom(existing) }
            lastMaskW = w
            lastMaskH = h
            lastL3Frosted = local.glassFrosted

            ownFrameCaptureArmed = false
            lastL3GlassKey = glassKeyOf(local)

            runCatching {
                val hw = dev.cdl.window.WindowInterop.hwndOf(window) ?: 0L
                if (hw != 0L) dev.cdl.platform.CaptureExclusion.set(hw, true)
            }
            val raster = dev.cdl.render.ShapeMaskRaster(w, h)
            val params = dev.cdl.render.BackdropCaptureParams(
                blurRadius = radius,

                blurKernel = if (local.glassFrosted) {
                    dev.cdl.render.CoverBackdrop.KERNEL_BOX
                } else {
                    dev.cdl.render.CoverBackdrop.KERNEL_POISSON
                },

                downsample = when (local.glassClarity.uppercase()) {
                    "MAX" -> 1
                    "STANDARD" -> 4
                    else -> 2
                },
                cornerRadiusPx = glassCornerRadius,
                cornerRadiusBottomPx = glassCornerRadiusBottom,

                refraction = dev.cdl.render.BackdropRefraction.Params(

                    thicknessPx = 16.0,

                    amountPx = 32.0,

                    dispersionFactor = local.glassDispersionPercent / 100.0,
                    saturation = local.glassSaturationPercent / 100.0,
                ),

                preferGpu = true,

                glassCompose = true,
                tileIsWholeWindow = false,

                keepAlpha = glassAaEnabled(),

                glassGlarePercent = local.materialLiquidGlarePercent,

                glassDepthEffect = local.materialLiquidDepthEffect,
                glassSurfaceAlpha = local.glassSurfaceAlpha,

                bypassSelfGate = true,

                onPublished = { SwingUtilities.invokeLater { panel.repaint() } },
                skipUnchangedFrames = true,

                splitCaptureThread = true,

                toggleWdaForCapture = false,

                stillModeProvider = { false },
                useWindowBackdrop = false,
                mirrorHwndProvider = { mirrorHwnd },
                fastIntervalMs = 33L,
                selfHwndProvider = { hwnd },
                dpiScale = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX,

                dumpEnabled = false,
            )
            val worker = dev.cdl.render.L3CaptureWorker(
                params = params,
                state = state,
                ownFrameProvider = { ownFrame },
                regionProvider = { l3RegionOnScreen() },

                onWarn = { msg ->
                    SwingUtilities.invokeLater { runCatching { toastSink?.invoke(msg) } }
                },

                maskProvider = { shape, ww, hh ->
                    if (raster.width == ww && raster.height == hh) raster.maskFor(shape) else null
                },
            )
            worker.robotProvider = { ownCaptureRobot }

            l3Worker?.stop()
            l3State = state
            l3MaskRaster = raster
            l3Worker = worker

            lastL3FrameLogEnabled = local.frameLogEnabled
            lastL3RefractionKey = refractionKey

            armOwnFrameCapture(true)
            worker.start()
        }

        fun disposeL3() {
            l3Worker?.stop()
            l3Worker = null
            l3State = null
            l3MaskRaster = null

            lastL3FrameLogEnabled = false
            lastL3RefractionKey = ""
            armOwnFrameCapture(false)
        }

        fun requestFastCapture(untilMs: Long) {
            l3State?.fastUntilMs = untilMs
        }

        private fun l3RegionOnScreen(): java.awt.Rectangle? = runCatching {
            val origin = locationOnScreen ?: return@runCatching null
            val f = ownFrame ?: return@runCatching null

            return java.awt.Rectangle(origin.x, origin.y, window.width, window.height)
            @Suppress("UNREACHABLE_CODE")
            val win = java.awt.Rectangle(origin.x, origin.y, window.width, window.height)

            val active = System.currentTimeMillis() < (l3State?.fastUntilMs ?: 0L)
            if (active) {
                val merged = supersetRect?.union(win) ?: win
                supersetRect = merged
                merged
            } else {
                supersetRect = null
                win
            }
        }.getOrNull()

        private var lastIslandW: Int = 0
        private var lastIslandH: Int = 0

        private fun lastIslandWidthForL3(): Int = ownFrame?.islandWidth ?: lastIslandW
        private fun lastIslandHeightForL3(): Int = ownFrame?.islandHeight ?: lastIslandH

        private fun coverShapeAt(left: Float, top: Float, side: Float, local: Appearance): Shape =
            when (local.coverShape) {
                COVER_SHAPE_CIRCLE -> Ellipse2D.Float(left, top, side, side)
                COVER_SHAPE_SQUARE -> Rectangle2D.Float(left, top, side, side)
                else -> {
                    val arc = side *
                        if (local.shapeType == "SQUARE") COVER_ARC_RATIO_SQUARE else COVER_ARC_RATIO
                    RoundRectangle2D.Float(left, top, side, side, arc, arc)
                }
            }

        private fun applyDissolveMask(
            g2: Graphics2D,
            local: Appearance,
            islandLeftPx: Float,
            islandTopPx: Float,
            islandWidthPx: Float,
            islandHeightPx: Float,
            topRadiusPx: Float,
            bottomRadiusPx: Float,
        ) {
            if (!local.borderEnabled) return

            if (local.borderMode != BORDER_MODE_EDGE_DISSOLVE) return

            if (local.opacityPercent == 0) return
            val widthPx = Math.round(islandWidthPx)
            val heightPx = Math.round(islandHeightPx)
            if (widthPx <= 0 || heightPx <= 0) return
            val band = dev.cdl.settings.dissolveBandPx(
                heightPx.toFloat(), local.borderDissolveBandPercent,
            )
            if (band <= 0f) return
            val mask = DissolveMask.get(
                widthPx = widthPx,
                heightPx = heightPx,
                bandPx = band,
                topRadiusPx = topRadiusPx,
                bottomRadiusPx = bottomRadiusPx,
                cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                cornerN = local.cornerN,

                subpixelXPx = islandLeftPx,
                subpixelYPx = islandTopPx,
            ) ?: return
            val savedComposite = g2.composite
            val savedInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION)

            val savedClip = g2.clip
            try {

                g2.composite = java.awt.AlphaComposite.DstIn

                g2.clip = null

                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )

                g2.drawImage(
                    mask.image,
                    floor(islandLeftPx).toInt() - mask.padPx,
                    floor(islandTopPx).toInt() - mask.padPx,
                    null,
                )
            } finally {

                g2.clip = savedClip
                g2.composite = savedComposite

                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    savedInterp ?: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )
            }
        }

        private fun drawIslandBorder(
            g2: Graphics2D,
            local: Appearance,

            islandLeft: Float,
            width: Int,

            islandTop: Float,
            height: Int,

            topRadiusPx: Float,
            bottomRadiusPx: Float,
            coverRgb: Int?,
        ) {
            if (!local.borderEnabled) return

            val strokeWidth = local.borderWidthTenthPx / 10f

            if (strokeWidth <= 0f) return

            val insetShape = islandShapeOf(
                width = (width - strokeWidth).toInt().coerceAtLeast(1).toFloat(),
                height = (height - strokeWidth).toInt().coerceAtLeast(1).toFloat(),
                topRadiusPx = topRadiusPx,
                bottomRadiusPx = bottomRadiusPx,

                cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                cornerN = local.cornerN,
            )
            val savedTransform = g2.transform
            val savedStroke = g2.stroke
            val savedColor = g2.color
            try {

                g2.translate(
                    islandLeft.toDouble() + strokeWidth / 2.0,
                    islandTop.toDouble() + strokeWidth / 2.0,
                )
                val borderColor = local.borderColorFor(coverRgb)

                if (local.borderMode == BORDER_MODE_FADE_OUT) {

                    for (layer in borderFadeLayers(strokeWidth)) {
                        if (layer.alpha <= 0) continue
                        val inset = layer.insetPx
                        val ring = islandShapeOf(
                            width = (width - strokeWidth - inset * 2f).toInt().coerceAtLeast(1).toFloat(),
                            height = (height - strokeWidth - inset * 2f).toInt().coerceAtLeast(1).toFloat(),
                            topRadiusPx = (topRadiusPx - inset).coerceAtLeast(0f),
                            bottomRadiusPx = (bottomRadiusPx - inset).coerceAtLeast(0f),
                            cornerStyle = effectiveCornerStyle(local.shapeType, local.cornerStyle),
                            cornerN = local.cornerN,
                        )
                        g2.stroke = BasicStroke(layer.widthPx)
                        g2.color = Color(
                            borderColor.red, borderColor.green, borderColor.blue, layer.alpha,
                        )
                        g2.draw(ring)
                    }
                } else if (local.borderMode == BORDER_MODE_EDGE_DISSOLVE) {

                } else {

                    g2.stroke = BasicStroke(strokeWidth)

                    g2.color = borderColor
                    g2.draw(insetShape)
                }
            } finally {

                g2.transform = savedTransform
                g2.stroke = savedStroke
                g2.color = savedColor
            }
        }

        private fun drawControls(g2: Graphics2D, local: Appearance, layout: PlaybackControls.Layout) {
            if (!layout.expanded) return
            val snap = snapshotSupplier()

            val progress = bandFractionNow

            if (progress <= 0f) return
            val spec = Motions.resolve(Motions.CONTROLS_APPEAR, local.controlsMotionStyle)

            val cfg = controlsConfig()

            val fromTop = cfg.position == ControlsConfig.TOP
            val signedDistance = if (fromTop) -spec.distancePx else spec.distancePx

            val motionOn = animator.enabled
            val offsetY = if (motionOn) spec.style.offsetYAt(progress, signedDistance) else 0f
            val scale = if (motionOn) spec.style.scaleAt(progress) else 1f
            val motionTransform = if (offsetY == 0f && scale == 1f) {
                null
            } else {

                val cx = width / 2f
                val stripCenterY = if (fromTop) {
                    layout.stripHeight / 2f
                } else {
                    height - layout.stripHeight / 2f
                }
                AffineTransform().apply {

                    translate(0.0, offsetY.toDouble())
                    translate(cx.toDouble(), stripCenterY.toDouble())
                    scale(scale.toDouble(), scale.toDouble())
                    translate(-cx.toDouble(), -stripCenterY.toDouble())
                }
            }
            runCatching {
                controls.paint(
                    g2 = g2,
                    layout = layout,
                    hovered = hoveredControl,
                    playing = snap.isPlaying,

                    positionMs = progressDisplayMs(snap),

                    durationMs = durationSupplier(),
                    accent = local.sungColor,

                    cfg = cfg,

                    alpha = progress,

                    previewMs = seekPreviewMs,

                    transform = motionTransform,
                )
            }.onFailure {

            }
        }

        private var avgCacheFor: java.awt.image.BufferedImage? = null
        private var avgCacheColor: java.awt.Color? = null

        private fun averageColorOf(img: java.awt.image.BufferedImage): java.awt.Color {
            val cached = avgCacheColor
            if (cached != null && avgCacheFor === img) return cached
            var r = 0L; var g0 = 0L; var b0 = 0L; var n = 0
            val stepX = (img.width / 8).coerceAtLeast(1)
            val stepY = (img.height / 4).coerceAtLeast(1)
            var y = 0
            while (y < img.height) {
                var x = 0
                while (x < img.width) {
                    val c = img.getRGB(x, y)
                    r += (c ushr 16) and 0xFF; g0 += (c ushr 8) and 0xFF; b0 += c and 0xFF
                    n++
                    x += stepX
                }
                y += stepY
            }
            val out = if (n == 0) {
                java.awt.Color(0x10, 0x10, 0x10)
            } else {
                java.awt.Color((r / n).toInt(), (g0 / n).toInt(), (b0 / n).toInt())
            }
            avgCacheFor = img
            avgCacheColor = out
            return out
        }

        private fun paintL3Backdrop(
            g2: Graphics2D,
            local: Appearance,
            shape: Shape,
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ) {

            val backdrop = l3State?.backdrop ?: return

            if (backdrop.width == window.width && backdrop.height == window.height) {
                g2.drawImage(backdrop, 0, 0, null)
                return
            }

            run {
                val now2 = System.currentTimeMillis()
                if (now2 - lastGlassSizeLogAt > 5000L) {
                    lastGlassSizeLogAt = now2
                }
            }

            val isWindowSized = backdrop.width == window.width && backdrop.height == window.height

            run {
                val savedFillClip = g2.clip
                g2.clip = null

                run {
                    val tw = backdrop.width
                    val th = backdrop.height
                    if (tw <= 0 || th <= 0) return@run

                    g2.color = averageColorOf(backdrop)
                    g2.fillRect(0, 0, width, height)
                    if (tw < width) {
                        g2.drawImage(backdrop, tw - 1, 0, tw, th, tw, 0, width, height, null)
                    }
                    if (th < height) {
                        g2.drawImage(backdrop, 0, th - 1, tw, th, 0, th, tw, height, null)
                    }
                }
                g2.clip = savedFillClip
            }

            g2.translate(-FEATHER_MARGIN.toDouble(), -FEATHER_MARGIN.toDouble())

            run {
                val savedClip = g2.clip
                val savedColor = g2.color
                g2.clip = null
                g2.color = averageColorOf(backdrop)
                g2.fillRect(0, 0, window.width, window.height)
                g2.clip = savedClip
                g2.color = savedColor
            }

            val tileRegion = l3State?.tileScreenRect
            val winOriginNow = locationOnScreen
            if (tileRegion != null && winOriginNow != null) {
                val sx = winOriginNow.x - tileRegion.x
                val sy = winOriginNow.y - tileRegion.y
                g2.drawImage(
                    backdrop,
                    0, 0, window.width, window.height,
                    sx, sy, sx + window.width, sy + window.height,
                    null,
                )
            } else {
                g2.drawImage(backdrop, 0, 0, null)
            }
            g2.translate(FEATHER_MARGIN.toDouble(), FEATHER_MARGIN.toDouble())
        }

        private fun backgroundWithOpacity(local: Appearance, coverRgb: Int?): Color {
            val percent = local.opacityPercent.coerceIn(0, 100)

            val alpha = (255 * percent / 100).coerceIn(0, 255)
            val base = local.backgroundColorFor(coverRgb)
            return Color(base.red, base.green, base.blue, alpha)
        }

        private fun drawCoverImageBackdrop(
            g2: Graphics2D,
            local: Appearance,
            shape: Shape,
            src: BufferedImage,
            coverRgb: Int?,
            islandLeft: Float,
            islandTop: Float,
            islandWidth: Int,
            islandHeight: Int,
        ) {
            val w = islandWidth
            val h = islandHeight
            if (w <= 0 || h <= 0) return
            val percent = local.opacityPercent.coerceIn(0, 100)

            if (percent <= 0) return

            val maskBase = coverRgb?.let { CoverPalette.darkFrom(it) }
                ?: Color(BACKGROUND_BASE_RGB)
            val maskArgb = (CoverBackdrop.MASK_ALPHA shl 24) or (maskBase.rgb and 0xFFFFFF)

            val baked = CoverBackdrop.baked(
                src = src,
                targetW = w,
                targetH = h,
                angleDeg = local.backgroundCoverAngleDeg,
                maskArgb = maskArgb,

                blurRadius = local.materialBlurRadius,

                downsample = if (local.materialQuality == "LITE") 4 else 1,
            )

            val oldClip = g2.clip
            val oldComposite = g2.composite
            try {
                g2.clip = shape
                if (percent < 100) {
                    g2.composite = AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER,
                        percent / 100f,
                    )
                }
                g2.drawImage(
                    baked,
                    AffineTransform.getTranslateInstance(
                        islandLeft.toDouble(),
                        islandTop.toDouble(),
                    ),
                    null,
                )
            } finally {

                g2.composite = oldComposite
                g2.clip = oldClip
            }
        }

        private fun drawCoverLockBadge(g2: Graphics2D, badge: java.awt.geom.Rectangle2D) {
            val x = badge.x.toFloat()
            val y = badge.y.toFloat()
            val s = badge.width.toFloat()

            g2.color = java.awt.Color(0, 0, 0, COVER_LOCK_BADGE_SCRIM_ALPHA)
            g2.fill(RoundRectangle2D.Float(x, y, s, badge.height.toFloat(), s * 0.3f, s * 0.3f))

            val pad = s * 0.22f
            val bodyW = s - 2f * pad
            val bodyH = bodyW * 0.78f
            val bodyY = y + s - pad - bodyH
            val shackleH = bodyH * 0.72f
            val shackleW = bodyW * 0.62f
            val shackleX = x + (s - shackleW) / 2f
            val shackleY = bodyY - shackleH

            g2.color = PlaybackControls.ICON_COLOR
            g2.stroke = BasicStroke(
                (s * 0.11f).coerceAtLeast(1f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )

            g2.draw(
                java.awt.geom.Arc2D.Float(
                    shackleX, shackleY, shackleW, shackleH * 2f,
                    0f, 180f, java.awt.geom.Arc2D.OPEN,
                ),
            )

            g2.fill(
                RoundRectangle2D.Float(
                    x + pad, bodyY, bodyW, bodyH,
                    s * 0.14f, s * 0.14f,
                ),
            )
        }

        private fun drawCover(
            g2: Graphics2D,
            local: Appearance,
            cover: CoverPaint,
            size: Float,

            coverLeft: Float,

            coverTop: Float,

            positionMs: Long,
        ) {
            if (cover.shown == null && cover.prev == null) return
            if (size <= 0f) return

            val ring = coverVinylRingWidth(size, local.coverVinyl)

            val content = (size - 2f * ring).coerceAtLeast(1f)

            val left = coverLeft
            val contentLeft = left + ring

            val top = coverTop
            val contentTop = top + ring

            val shape: Shape = coverShapeAt(contentLeft, contentTop, content, local)

            if (cover.shown != null) {
                drawnCoverBounds = if (ring > 0f) coverShapeAt(left, top, size, local) else shape
            }

            val copy = g2.create() as Graphics2D
            try {

                copy.clip(shape)

                val rotation = if (local.coverRotate) {
                    coverRotationDegrees(positionMs, local.coverRotatePeriodS)
                } else {
                    0f
                }
                if (rotation != 0f) {
                    copy.rotate(
                        Math.toRadians(rotation.toDouble()),
                        (left + size / 2f).toDouble(),
                        (top + size / 2f).toDouble(),
                    )
                }

                copy.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                )

                cover.prev?.let {
                    drawCoverImage(copy, it, contentLeft, contentTop, content, 1f - cover.mix, local.coverSharpness)
                }
                cover.shown?.let {
                    drawCoverImage(copy, it, contentLeft, contentTop, content, cover.mix, local.coverSharpness)
                }
            } finally {

                copy.dispose()
            }

            g2.color = COVER_OUTLINE
            g2.draw(shape)

            if (ring > 0f) {

                g2.color = VINYL_RING_COLOR
                g2.fill(dev.cdl.render.CoverRing.ringArea(shape, ring))
            }
        }

        private fun drawCoverImage(
            g: Graphics2D,
            image: BufferedImage,
            left: Float,
            top: Float,
            size: Float,
            alpha: Float,

            sharpness: Int,
        ) {
            val a = alpha.coerceIn(0f, 1f)
            if (a <= 0f) return
            val layer = if (a >= 1f) {
                g
            } else {
                (g.create() as Graphics2D).also {
                    it.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)
                }
            }
            try {

                val px = bitmapRenderPx(size, deviceScaleOf(g))
                layer.drawImage(
                    CoverScaler.squareScaled(image, px, sharpness),
                    left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt(),
                    0, 0, px, px,
                    null,
                )
            } finally {
                if (layer !== g) layer.dispose()
            }
        }

        private inline fun drawWithEnterTransform(
            g2: Graphics2D,
            local: Appearance,
            restingBaselineX: Float,
            restingBaselineY: Float,
            layout: TextLayout,
            lineHeight: Float,
            body: (Graphics2D) -> Unit,
        ) {
            val progress = mainEnter

            if (progress >= 1f) {
                body(g2)
                return
            }
            val alpha = progress.coerceIn(0f, 1f)
            if (alpha <= 0f) return
            val spec = Motions.resolve(Motions.LYRIC_ENTER, local.lyricMotionStyle)

            val dy = spec.style.offsetYAt(progress, spec.distancePx)
            val scale = spec.style.scaleAt(progress)
            val layer = g2.create() as Graphics2D
            try {
                layer.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                if (dy != 0f || scale != 1f) {

                    val cx = restingBaselineX + layout.advance / 2f
                    val cy = lyricCenterYFromBaseline(restingBaselineY, layout.ascent, layout.descent)
                    layer.transform(
                        AffineTransform().apply {
                            translate(0.0, dy.toDouble())
                            translate(cx.toDouble(), cy.toDouble())
                            scale(scale.toDouble(), scale.toDouble())
                            translate(-cx.toDouble(), -cy.toDouble())
                        },
                    )
                }
                body(layer)
            } finally {
                layer.dispose()
            }
        }

        private fun drawExitingMainLine(
            g2: Graphics2D,
            local: Appearance,
            coverRgb: Int?,
            width: Int,
            areaLeft: Float,
            baselineY: Float,
            lineHeight: Float,
        ) {
            val text = exitingMainText ?: return
            val progress = mainExit
            if (progress >= 1f) return
            val alpha = (1f - progress).coerceIn(0f, 1f)
            if (alpha <= 0f) return

            val layout = runCatching { renderCache.textLayoutFor(text) }.getOrNull() ?: return
            val spec = Motions.resolve(Motions.LYRIC_EXIT, local.lyricMotionStyle)
            val x = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)

            val dy = spec.style.offsetYAt(progress, -spec.distancePx)
            val scale = spec.style.scaleAt(progress)
            val layer = g2.create() as Graphics2D
            try {
                layer.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                if (dy != 0f || scale != 1f) {

                    val cx = x + layout.advance / 2f
                    val cy = lyricCenterYFromBaseline(baselineY, layout.ascent, layout.descent)
                    layer.transform(
                        AffineTransform().apply {
                            translate(0.0, dy.toDouble())
                            translate(cx.toDouble(), cy.toDouble())
                            scale(scale.toDouble(), scale.toDouble())
                            translate(-cx.toDouble(), -cy.toDouble())
                        },
                    )
                }
                val packed = local.sungColorFor(coverRgb).toPackedArgb()
                karaokeRenderer.render(
                    g = layer,
                    cache = renderCache,
                    layout = layout,
                    text = text,
                    baselineX = x,
                    baselineY = baselineY,
                    sungColor = packed,
                    unsungColor = packed,
                    progress = 1f,
                    karaoke = false,
                    outline = local.mainOutlineFor(),
                    smooth = false,
                )
            } finally {

                layer.dispose()
            }
        }

        private fun drawBackgroundSpectrum(
            g2: Graphics2D,
            local: Appearance,
            bands: FloatArray?,
            areaLeft: Float,
            areaTop: Float,
            areaWidth: Float,
            areaHeight: Float,
            coverRgb: Int?,
            playing: Boolean,

            slotRect: java.awt.Rectangle,

            slotOccupant: String,

            peakHold: Boolean,

            topRadiusPx: Float,
            bottomRadiusPx: Float,

            coverPalette: List<Int>,

            flowPhase: Float,

            islandBox: FloatArray,
        ) {

            if (bands == null || bands.isEmpty()) return
            if (areaWidth <= 1f || areaHeight <= 1f) return

            val slotSideBars = slotOccupant == dev.cdl.render.RIGHT_SLOT_SPECTRUM

            val pct = if (slotSideBars) {
                1f
            } else {
                local.spectrumHeightPct.coerceIn(MIN_SPECTRUM_HEIGHT_PCT, 100) / 100f
            }

            val slotPolar = dev.cdl.render.isPolarSlotOccupant(slotOccupant)
            val slotOwn = slotSideBars || slotPolar

            val symmetric = slotSideBars ||
                (slotPolar && slotOccupant == dev.cdl.render.RIGHT_SLOT_RING) ||
                (!slotPolar && boolOf(snapshot, "spectrum.symmetric"))
            val bandRaw = spectrumBandGeometry(
                areaTop = areaTop,
                areaHeight = areaHeight,
                heightFraction = pct,

                anchorTop = !slotSideBars && local.spectrumAnchor == SPECTRUM_ANCHOR_TOP,
            )

            val band = if (symmetric) {

                val bandHeight = (areaHeight * pct).coerceAtLeast(1f)
                bandRaw.copy(
                    top = symmetricBandTop(areaTop, areaHeight, pct),
                    bottom = symmetricBandTop(areaTop, areaHeight, pct) + bandHeight,
                )
            } else {
                bandRaw
            }
            val top = band.top
            val bottom = band.bottom
            if (band.height <= 1f) return

            val edgeInsetRaw = dev.cdl.window.spectrumBandInsetFor(
                slotOwn = slotOwn,
                topRadiusPx = topRadiusPx,
                bottomRadiusPx = bottomRadiusPx,
                areaTop = areaTop,
                areaHeight = areaHeight,
                bandTop = top,
                bandBottom = bottom,
                baseInset = HORIZONTAL_INSET,
            )

            val edgeInset = if (slotOwn) {

                dev.cdl.render.slotSpectrumInsetPx(areaWidth)
            } else {
                edgeInsetRaw
            }
            val left = areaLeft + edgeInset
            val right = areaLeft + areaWidth - edgeInset
            val barWidth = right - left

            if (barWidth <= 1f) return

            val mainSpectrumColor = local.spectrumColorFor(coverRgb)
            val packed = mainSpectrumColor.toPackedArgb()

            val slotColorMode = dev.cdl.window.rightSlotSpectrumColorModeFor(
                local.rightSlotSpectrumColorMode,
            )
            val slotPacked = dev.cdl.window.rightSlotSpectrumColorFor(
                slotColorMode,
                mainSpectrumColor,
                local.rightSlotSpectrumColor,
                coverRgb,
            ).toPackedArgb()
            val slotMultiColor = dev.cdl.window.isMultiColorSpectrumMode(slotColorMode)

            val spectrumFlowPhase = dev.cdl.render.flowPhase(
                snapshotSupplier().positionMs,
                dev.cdl.render.SPECTRUM_FLOW_PERIOD_MS,
                local.materialFlowSpeed,
            )
            val barColorFn = spectrumBarColorFn(
                local.spectrumColorMode, coverPalette, spectrumFlowPhase,

                coverPaletteShares.takeIf { it.isNotEmpty() },
            )

            val slotCamoPaint: Paint? = if (slotOwn &&
                slotColorMode == dev.cdl.render.CoverPalette.COVER_CAMO
            ) {
                dev.cdl.render.camoTexturePaint(
                    islandBox[0].toDouble(), islandBox[1].toDouble(),
                    islandBox[2].toDouble(), islandBox[3].toDouble(),
                    islandHeightPx = islandBox[3].toInt(),
                    paletteArgb = coverPalette,
                    alpha = (slotPacked ushr 24) and 0xFF,
                    phase = spectrumFlowPhase,

                    shares = coverPaletteShares.takeIf { it.isNotEmpty() },
                )
            } else {
                null
            }

            val bounceScale = (intOf(snapshot, "spectrum.bounce_pct") / 100f).coerceIn(1f, 3f)

            val progress = if (boolOf(snapshot, "spectrum.progress_tint")) {
                val timeline = snapshotSupplier()
                playbackProgress(timeline.positionMs, durationSupplier())
            } else {
                NO_PROGRESS
            }

            val tintActive = progress > 0f && progress <= 1f
            val alphas = spectrumAlphas(
                opacityPct = intOf(snapshot, "spectrum.opacity_pct"),
                tintAlphaPct = intOf(snapshot, "spectrum.progress_tint_alpha"),
                tintActive = tintActive,
            )

            val segColors = spectrumSegmentColors(packed, alphas)

            val slotOpacityPct = intOf(snapshot, "island.right_slot.spectrum_opacity_pct")
            val slotAlphas = alphas.copy(unplayed = (slotOpacityPct / 100f).coerceIn(0f, 1f))

            val slotSegColors = spectrumSegmentColors(slotPacked, slotAlphas)

            val multiColorSolid = dev.cdl.window.isMultiColorSpectrumMode(local.spectrumColorMode)
            val solidFlowPaint: Paint? = if (multiColorSolid) {
                spectrumFlowPaintFor(
                    left, top, right, bottom, coverPalette, spectrumFlowPhase, alphas.unplayed,
                    local.spectrumColorMode,
                )
            } else {
                null
            }

            val slotSolidFlowPaint: Paint? = if (slotMultiColor) {
                spectrumFlowPaintFor(

                    left, top, right, bottom, coverPalette, spectrumFlowPhase,
                    slotAlphas.unplayed, slotColorMode,
                )
            } else {
                null
            }

            val solidFlowProgressPaint: Paint? = if (multiColorSolid) {
                spectrumFlowPaintFor(
                    left, top, right, bottom, coverPalette, spectrumFlowPhase, alphas.played,
                    local.spectrumColorMode,
                )
            } else {
                null
            }

            val unplayedPacked = segColors.unplayed

            val playedPacked = segColors.played

            val flip = if (band.growDown) g2.transform else null
            if (flip != null) {
                g2.translate(0.0, 2.0 * top + band.height)
                g2.scale(1.0, -1.0)
            }
            try {

                val polarArea = if (dev.cdl.render.isPolarSlotOccupant(slotOccupant)) {
                    floatArrayOf(
                        slotRect.x.toFloat(),
                        slotRect.y.toFloat(),
                        slotRect.width.toFloat(),
                        slotRect.height.toFloat(),
                    )
                } else {
                    floatArrayOf(areaLeft, areaTop, areaWidth, areaHeight)
                }
                val polarSpecFinal = dev.cdl.render.spectrumPolarFor(
                    slotOccupant, polarArea[0], polarArea[1], polarArea[2], polarArea[3],

                    local.rightSlotRadialInnerPercent,
                    local.rightSlotRingInnerPercent,
                    local.rightSlotPolarOuterPercent,
                )
                val spectrumBounds = if (polarSpecFinal != null) {

                    val circumferencePx =
                        (2.0 * Math.PI * dev.cdl.render.polarSpacingRadiusPx(polarSpecFinal)).toInt()
                    Rectangle(
                        polarArea[0].toInt(),
                        polarArea[1].toInt(),
                        circumferencePx.coerceAtLeast(1),
                        polarArea[3].toInt(),
                    )
                } else {
                    Rectangle(left.toInt(), top.toInt(), barWidth.toInt(), band.height.toInt())
                }

                val mainCamoPaint: java.awt.Paint? = if (!slotOwn &&
                    local.spectrumColorMode == dev.cdl.render.CoverPalette.COVER_CAMO
                ) {
                    dev.cdl.render.camoTexturePaint(
                        spectrumBounds.x.toDouble(), spectrumBounds.y.toDouble(),
                        spectrumBounds.width.toDouble(), spectrumBounds.height.toDouble(),
                        islandHeightPx = spectrumBounds.height,
                        paletteArgb = coverPalette,
                        alpha = (unplayedPacked ushr 24) and 0xFF,
                        phase = spectrumFlowPhase,

                        shares = coverPaletteShares.takeIf { it.isNotEmpty() },
                    )
                } else {
                    null
                }

                val fadeLayerNeeded = mainCamoPaint != null &&
                    boolOf(snapshot, "spectrum.fade_top")
                val fadeLayerDev = if (fadeLayerNeeded) {

                    val bb = g2.transform.createTransformedShape(spectrumBounds).bounds
                    java.awt.Rectangle(bb.x - 8, bb.y - 8, bb.width + 16, bb.height + 16)
                } else {
                    null
                }
                val fadeLayerImg = fadeLayerDev
                    ?.takeIf { it.width > 0 && it.height > 0 }
                    ?.let { spectrumFadeLayerOf(it.width, it.height) }
                val fadeLayerG: java.awt.Graphics2D? =
                    if (fadeLayerImg != null && fadeLayerDev != null) {
                        fadeLayerImg.createGraphics().also { og ->
                            og.setRenderingHints(g2.renderingHints)

                            og.composite = java.awt.AlphaComposite.Clear
                            og.fillRect(0, 0, fadeLayerImg.width, fadeLayerImg.height)
                            og.composite = java.awt.AlphaComposite.SrcOver

                            og.transform = g2.transform.clone() as java.awt.geom.AffineTransform
                            og.translate(-fadeLayerDev.x.toDouble(), -fadeLayerDev.y.toDouble())
                            og.clip = g2.clip
                        }
                    } else {
                        null
                    }
                SpectralPainter.paint(
                    g = fadeLayerG ?: g2,
                    cache = renderCache,
                    bands = bands,
                    bounds = spectrumBounds,

                    color = if (slotOwn) slotSegColors.unplayed else unplayedPacked,

                    argbForBar = if (slotOwn &&
                        slotColorMode == dev.cdl.render.CoverPalette.COVER_FLOW
                    ) {
                        { i2, count2, _value2, base2 ->
                            dev.cdl.render.spectrumFlowColorAt(
                                base2, i2, count2, coverPalette, spectrumFlowPhase,
                                coverPaletteShares.takeIf { it.isNotEmpty() },
                            )
                        }
                    } else {
                        barColorFn
                    },

                    argbForBarSegment = if (slotCamoPaint == null && mainCamoPaint == null &&
                        (if (slotOwn) slotColorMode else local.spectrumColorMode) ==
                        dev.cdl.render.CoverPalette.COVER_CAMO
                    ) {
                        { i2, count2, seg2, segs2, base2 ->
                            dev.cdl.render.spectrumCamoBlockColorAt(

                                base2, i2, seg2, count2, segs2, coverPalette, spectrumFlowPhase,
                            )
                        }
                    } else {
                        null
                    },

                    fadeMaskForBar = if (mainCamoPaint != null &&
                        boolOf(snapshot, "spectrum.fade_top")
                    ) {
                        { topY2, bottomY2, baseArgb2, corner2 ->

                            spectrumTipFadeMask(
                                topY2, bottomY2, symmetric, corner2,
                            )
                        }
                    } else {
                        null
                    },

                    polar = polarSpecFinal,

                    polarLine = slotPolar && dev.cdl.render.polarLineStyleFor(
                        textOf(snapshot, "island.right_slot.polar_style"),
                    ),
                    state = spectrumCache,
                    style = SpectralPainter.Style.BARS,
                    barShapeRatio = spectrumBarCornerRatio(
                        textOf(snapshot, "spectrum.bar_shape"),
                    ),
                    nowNanos = System.nanoTime(),
                    playing = playing,

                    peakHold = peakHold && !slotOwn,

                    paintOverride = if (!slotOwn && boolOf(snapshot, "spectrum.fade_top")) {

                        backgroundSpectrumPaint(packed, left, bottom, top, alphas.unplayed)
                    } else if (slotOwn) {

                        if (slotColorMode == dev.cdl.render.CoverPalette.COVER_FLOW) {
                            null
                        } else {
                            slotSolidFlowPaint
                        }
                    } else {

                        solidFlowPaint
                    },
                    bounceScale = bounceScale,
                    progressFraction = progress,

                    progressColor = if (progress > 0f && !slotOwn) {
                        playedPacked
                    } else null,

                    progressPaintOverride = if (progress > 0f && !slotOwn) {

                        val tintAlpha = alphas.played

                        if (slotOwn) {

                            solidFlowProgressPaint ?: solidProgressPaint(playedPacked, tintAlpha)
                        } else if (boolOf(snapshot, "spectrum.fade_top")) {
                            backgroundSpectrumProgressPaint(playedPacked, left, bottom, top, tintAlpha)
                        } else {
                            solidFlowProgressPaint ?: solidProgressPaint(playedPacked, tintAlpha)
                        }
                    } else {
                        null
                    },

                    fadeTop = !slotOwn && mainCamoPaint == null && boolOf(snapshot, "spectrum.fade_top"),

                    paintForBar = when {

                        mainCamoPaint != null -> { _, _, _, _ -> mainCamoPaint }

                        slotCamoPaint != null -> { _, _, _, _ -> slotCamoPaint }
                        !slotOwn && boolOf(snapshot, "spectrum.fade_top") -> {

                            { topY, bottomY, tipCornerRadiusPx, baseArgb ->

                                spectrumTipFadePaint(
                                    baseArgb, topY, bottomY, symmetric, alphas.unplayed,

                                    cornerRadiusPx = tipCornerRadiusPx,
                                )
                            }
                        }
                        else -> null
                    },

                    opacity = alphas.frameOpacity,

                    symmetric = symmetric,

                    symmetryCenterY = areaTop + areaHeight / 2f,

                    barPitchPx = if (slotOwn) {

                        dev.cdl.render.slotBarPitchPx(
                            isPolar = slotPolar,
                            auto = local.spectrumBarPitchAuto,

                            userPitchPx = if (slotPolar) {

                                val rawTenth = intOf(snapshot, "island.right_slot.spectrum_bar_pitch_tenth_px")
                                run {
                                    val n = System.currentTimeMillis()
                                    if (rawTenth != slotPitchLoggedTenth || n - slotPitchLoggedAt > 1000L) {
                                        slotPitchLoggedTenth = rawTenth
                                        slotPitchLoggedAt = n
                                    }
                                }
                                rawTenth / 10f
                            } else {
                                local.spectrumBarPitchPx / 10f
                            },
                        )
                    } else if (local.spectrumBarPitchAuto) {
                        spectrumAutoPitch((right - left).toInt())
                    } else {

                        local.spectrumBarPitchPx / 10f
                    },

                    forceBarCount = when {

                        slotSideBars -> dev.cdl.render.slotSideBarCountFor(
                            textOf(snapshot, "island.right_slot.spectrum_bars"),
                        )

                        polarSpecFinal != null -> {

                            val mainTenth = local.spectrumBarPitchPx
                            val slotRaw = intOf(snapshot, "island.right_slot.spectrum_bar_pitch_tenth_px")

                            val slotTenth = if (slotOwn) {
                                intOf(snapshot, "island.right_slot.spectrum_bar_pitch_tenth_px")
                            } else {
                                mainTenth
                            }

                            val rawPitchPx = slotTenth / 10f
                            val minVisiblePitchPx = 2.14f
                            val pitchPx = if (rawPitchPx > 0f && rawPitchPx < minVisiblePitchPx) {
                                0f
                            } else {
                                dev.cdl.render.polarCountPitchPx(rawPitchPx)
                            }

                            val spacingRadiusPx = dev.cdl.render.polarSpacingRadiusPx(polarSpecFinal)
                                .coerceAtLeast(polarSpecFinal.maxLength * 0.35f)
                            val spanPx = (2.0 * Math.PI * spacingRadiusPx).toFloat()

                            val cnt = dev.cdl.render.polarBarCount(polarSpecFinal, pitchPx)
                            run {
                                val n = System.currentTimeMillis()
                                if (slotCountLogged != "$slotOwn/$slotPolar/$mainTenth/$slotRaw/$cnt" ||
                                    n - slotCountLoggedAt > 2000L
                                ) {
                                    slotCountLogged = "$slotOwn/$slotPolar/$mainTenth/$slotRaw/$cnt"
                                    slotCountLoggedAt = n
                                }
                            }
                            cnt
                        }
                        else -> 0
                    },
                )

                if (fadeLayerG != null && fadeLayerImg != null && fadeLayerDev != null) {

                    if (fadeAlpha < 1f) {
                        fadeLayerG.composite = java.awt.AlphaComposite.DstIn
                        fadeLayerG.paint = java.awt.Color(
                            0, 0, 0, (255f * fadeAlpha.coerceIn(0f, 1f)).toInt(),
                        )
                        fadeLayerG.fillRect(0, 0, fadeLayerImg.width, fadeLayerImg.height)
                        fadeLayerG.composite = java.awt.AlphaComposite.SrcOver
                    }
                    fadeLayerG.dispose()
                    val savedTransform = g2.transform
                    val savedComposite = g2.composite
                    g2.transform = java.awt.geom.AffineTransform()

                    g2.composite = java.awt.AlphaComposite.SrcOver
                    g2.drawImage(fadeLayerImg, fadeLayerDev.x, fadeLayerDev.y, null)
                    g2.composite = savedComposite
                    g2.transform = savedTransform
                }
            } finally {

                if (flip != null) g2.setTransform(flip)
            }

            val secMode = local.spectrumSecondaryMode

            if (secMode != SPECTRUM_SECONDARY_MODE_OFF && local.spectrumEnabled && !slotOwn) {
                val secHeight = band.height * SECONDARY_SPECTRUM_HEIGHT_SCALE
                if (secHeight > 1f) {

                    val secAlphas = spectrumAlphas(
                        opacityPct = local.spectrumSecondaryOpacityPct,
                        tintAlphaPct = intOf(snapshot, "spectrum.progress_tint_alpha"),
                        tintActive = tintActive,
                    )

                    val secUnplayedPacked = spectrumLayerArgb(packed, secAlphas.unplayed)

                    val secPitch = if (local.spectrumBarPitchAuto) {
                        spectrumAutoPitch((right - left).toInt())
                    } else {

                        intOf(snapshot, "spectrum.secondary.bar_pitch_tenth_px") / 10f
                    }

                    val secBands = secondarySpectrumBands(
                        mode = secMode,
                        areaTop = areaTop,
                        areaHeight = areaHeight,
                        bandHeight = secHeight,
                    )
                    for ((secTop, secGrowDown) in secBands) {
                        val secFlip = if (secGrowDown) g2.transform else null
                        if (secFlip != null) {
                            g2.translate(0.0, 2.0 * secTop + secHeight)
                            g2.scale(1.0, -1.0)
                        }
                        try {
                            SpectralPainter.paint(
                                g = g2,
                                cache = renderCache,
                                bands = bands,
                                bounds = Rectangle(
                                    left.toInt(), secTop.toInt(),
                                    barWidth.toInt(), secHeight.toInt(),
                                ),

                                color = secUnplayedPacked,
                                state = spectrumCache,
                                style = SpectralPainter.Style.BARS,
                                nowNanos = System.nanoTime(),
                                playing = playing,

                                peakHold = peakHold && !slotOwn,

                                opacity = secAlphas.frameOpacity,
                                barPitchPx = secPitch,

                                fadeTop = boolOf(snapshot, "spectrum.fade_top"),

                                bounceScale = bounceScale,
                                progressFraction = progress,

                                barShapeRatio = spectrumBarCornerRatio(
                                    textOf(snapshot, "spectrum.bar_shape"),
                                ),

                                argbForBar = barColorFn,

                                paintOverride = if (boolOf(snapshot, "spectrum.fade_top")) {
                                    backgroundSpectrumPaint(
                                        packed, left, secTop + secHeight, secTop, secAlphas.unplayed,
                                    )
                                } else {

                                    if (local.spectrumColorMode ==
                                        dev.cdl.render.CoverPalette.COVER_FLOW ||
                                        local.spectrumColorMode ==
                                        dev.cdl.render.CoverPalette.COVER_CAMO
                                    ) {
                                        spectrumFlowPaintFor(
                                            left, secTop, right, secTop + secHeight,
                                            coverPalette, flowPhase,

                                            secAlphas.unplayed.coerceIn(0f, 1f),
                                            mode = local.spectrumColorMode,
                                        )
                                    } else {
                                        null
                                    }
                                },

                                progressColor = if (progress > 0f && !slotOwn) playedPacked else null,

                                progressPaintOverride = if (progress > 0f && !slotOwn) {

                                    val tintAlpha = secAlphas.played
                                    if (boolOf(snapshot, "spectrum.fade_top")) {
                                        backgroundSpectrumProgressPaint(
                                            playedPacked, left, secTop + secHeight, secTop, tintAlpha,
                                        )
                                    } else {

                                        if (local.spectrumColorMode ==
                                            dev.cdl.render.CoverPalette.COVER_FLOW ||
                                            local.spectrumColorMode ==
                                            dev.cdl.render.CoverPalette.COVER_CAMO
                                        ) {
                                            spectrumFlowPaintFor(
                                                left, secTop, right, secTop + secHeight,
                                                coverPalette, flowPhase, tintAlpha,
                                                mode = local.spectrumColorMode,
                                            ) ?: solidProgressPaint(playedPacked, tintAlpha)
                                        } else {
                                            solidProgressPaint(playedPacked, tintAlpha)
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        } finally {

                            if (secFlip != null) g2.setTransform(secFlip)
                        }
                    }
                }
            }
        }

        private fun spectrumTipFadeMask(
            topY: Float,
            bottomY: Float,
            symmetric: Boolean,
            cornerRadiusPx: Float,
        ): Paint {
            val h = (bottomY - topY).coerceAtLeast(1f)

            val fade = maxOf(h * 0.35f, cornerRadiusPx * 4f + 4f)
            val cap = if (symmetric) 0.49f else 0.70f
            val bandPx = fade.coerceAtMost(h * cap)
            val clear = Color(255, 255, 255, 0)
            val solid = Color(255, 255, 255, 255)
            return if (symmetric) {
                val band = (bandPx / h).coerceAtMost(0.49f)
                LinearGradientPaint(
                    0f, topY, 0f, bottomY,
                    floatArrayOf(0f, band, 1f - band, 1f),
                    arrayOf<Color>(clear, solid, solid, clear),
                )
            } else {

                LinearGradientPaint(
                    0f, topY, 0f, topY + bandPx,
                    floatArrayOf(0f, 1f),
                    arrayOf<Color>(clear, solid),
                )
            }
        }

        private fun spectrumTipFadePaint(
            packed: Int,
            topY: Float,
            bottomY: Float,
            symmetric: Boolean,

            alphaScale: Float = 1f,

            cornerRadiusPx: Float = 0f,
        ): Paint {
            val base = renderCache.color(packed)
            val solid = Color(
                base.red, base.green, base.blue,
                (255 * SPECTRUM_BACKGROUND_ALPHA * alphaScale.coerceIn(0f, 1f)).toInt(),
            )
            val clear = Color(base.red, base.green, base.blue, 0)
            val h = (bottomY - topY).coerceAtLeast(1f)

            val fractions = spectrumTipFadeFractions(h, symmetric, cornerRadiusPx)
            return if (symmetric) {
                LinearGradientPaint(
                    0f, topY, 0f, bottomY,
                    fractions,
                    arrayOf<Color>(clear, solid, solid, clear),
                )
            } else {
                LinearGradientPaint(
                    0f, topY, 0f, bottomY,
                    fractions,
                    arrayOf<Color>(clear, solid, solid),
                )
            }
        }

        private fun spectrumBarColorFn(
            mode: String,
            coverPalette: List<Int>,
            flowPhase: Float,

            shares: FloatArray? = null,
        ): ((index: Int, count: Int, value: Float, base: Int) -> Int)? = when (mode) {
            dev.cdl.render.CoverPalette.LEVEL ->
                { _, _, value, base -> dev.cdl.render.levelColorArgb(base, value) }
            dev.cdl.render.CoverPalette.COVER_FLOW ->
                { i, count, _, base ->
                    dev.cdl.render.spectrumFlowColorAt(base, i, count, coverPalette, flowPhase, shares)
                }

            dev.cdl.render.CoverPalette.COVER_CAMO ->
                { i, count, _, base ->
                    dev.cdl.render.spectrumCamoColorAt(base, i, count, coverPalette, flowPhase)
                }
            else -> null
        }

        private fun spectrumFlowPaintFor(
            left: Float, top: Float, right: Float, bottom: Float,
            coverPalette: List<Int>, flowPhase: Float, alphaScale: Float,

            mode: String = dev.cdl.render.CoverPalette.COVER_FLOW,
        ): Paint? {
            val alpha = (255 * SPECTRUM_BACKGROUND_ALPHA * alphaScale.coerceIn(0f, 1f)).toInt()
                .coerceIn(0, 255)
            return if (mode == dev.cdl.render.CoverPalette.COVER_CAMO) {

                dev.cdl.render.flowCamoPaint(
                    left.toDouble(), top.toDouble(),
                    (right - left).toDouble(), (bottom - top).toDouble(),
                    0,
                    dev.cdl.render.CAMO_BLOCK_COUNT,
                    coverPalette,
                    alpha,
                    phase = flowPhase,
                )
            } else {
                dev.cdl.render.flowGradientPaint(
                    left.toDouble(), top.toDouble(),
                    (right - left).toDouble(), (bottom - top).toDouble(),
                    0,
                    coverPalette,
                    alpha,
                    phase = flowPhase,
                )
            }
        }

        private fun backgroundSpectrumPaint(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,

            alphaScale: Float = 1f,
        ): Paint = backgroundBrush.verticalFade(packed, left, bottom, top, alphaScale)

        private fun solidProgressPaint(packed: Int, alphaScale: Float): Paint =
            solidProgressBrush.solidProgress(packed, alphaScale)

        private fun backgroundSpectrumProgressPaint(
            packed: Int,
            left: Float,
            bottom: Float,
            top: Float,

            alphaScale: Float = 1f,
        ): Paint = progressBrush.verticalFade(packed, left, bottom, top, alphaScale)

        private fun slotsFor(snap: TimelineSnapshot, local: Appearance): List<Slot> {

            return currentLineSlots(snap.currentLine).map { Slot(it, isCurrent = true) }
        }

        private fun drawLyricSlots(
            g2: Graphics2D,
            local: Appearance,
            width: Int,
            areaLeft: Float,
            areaTop: Float,
            areaHeight: Float,
            coverRgb: Int?,

            measureAreaLeft: Float = areaLeft,
        ) {

            if (!local.islandEnabled) return

            val snap = snapshotSupplier()

            val effectiveMs = snap.positionMs + local.lyricOffsetMs

            runCatching {
                renderCache.setFont(
                    fontWithLetterSpacing(
                        local.fontFamily,
                        local.fontSize,
                        local.mainLetterTracking(),
                        local.fontBold,
                    ),
                )
            }

            runCatching { renderCache.syncFrom(g2) }

            val slots = slotsFor(snap, local)

            val present = slots.filter { it.line != null }

            syncMainExit(present.firstOrNull { it.isCurrent }?.line?.text)

            val lyricsState = noLyricsDisplay(
                lyricLineCount = snap.lyricLineCount,
                hasCurrentLine = snap.currentLine != null,
            )

            if (lyricsState == NoLyricsDisplay.NOT_YET) return
            if (lyricsState == NoLyricsDisplay.NO_LYRICS) {

                if (local.noLyricsMode == NoLyricsMode.PLACEHOLDER) {

                    val noticeWidth = drawNoLyricsNotice(
                        g2, local, width, areaLeft, areaTop, areaHeight, snap, coverRgb,
                        suppressPlaceholder = local.hideWhenInstrumental,
                    )
                    publishNaturalWidth(noticeWidth, measureAreaLeft)
                }
                return
            }

            val lineHeight = local.fontSize * LINE_HEIGHT_RATIO + local.lineGapPx

            val currentSub = snap.currentLine
                ?.let { it.translation?.takeIf(String::isNotBlank) }
                ?.takeIf { local.translationEnabled }

            val subRowHeight = if (currentSub != null) {
                translationRowHeightPx(local.fontSize, local.translationScale, local.lineGapPx)
            } else {
                0f
            }

            val subAdvance = subRowHeight

            val centerY = areaTop + areaHeight / 2f

            val anchorIndex = present.indexOfFirst { it.isCurrent }.let { if (it < 0) 0 else it }

            val subAbove = currentSub != null && local.translationAbove

            val firstY = lyricFirstCenterY(
                areaTop = areaTop,
                areaHeight = areaHeight,
                anchorIndex = anchorIndex,
                rows = present.size,
                lineHeightPx = lineHeight,
                subRowHeightPx = subRowHeight,
                translationAbove = subAbove,
            )

            runCatching {
                var widest = 0f
                for (slot in present) {
                    val text = slot.line?.text ?: continue
                    if (text.isBlank()) continue
                    val advance = renderCache.textLayoutFor(text)?.advance ?: continue
                    if (advance > widest) widest = advance
                }

                currentSub?.takeIf { it.isNotBlank() }?.let { sub ->
                    translationFontFor(local)?.let { subFont ->
                        renderCache.textLayoutFor(sub, subFont)?.advance?.let { adv ->
                            if (adv > widest) widest = adv
                        }
                    }
                }
                if (widest > 0f) {

                    publishNaturalWidth(widest, measureAreaLeft)
                }
            }

            timingMainDesc = null
            timingSubDesc = null

            var cursorY = firstY

            for (slot in present) {

                val step = lineHeight + if (slot.isCurrent) subAdvance else 0f
                val line = slot.line
                val text = line?.text

                if (line != null && !text.isNullOrBlank()) {
                    val center = cursorY
                    runCatching {
                        drawOneLine(g2, local, effectiveMs, slot, line, text, width, areaLeft, center, coverRgb, lineHeight)
                    }.onFailure {
                    }

                    if (slot.isCurrent && currentSub != null) {
                        drawTranslationLine(
                            g2, local, line, currentSub, width, areaLeft,

                            center + translationCenterOffsetPx(
                                fontSize = local.fontSize,
                                translationScale = local.translationScale,
                                above = subAbove,
                                lineGapPx = local.lineGapPx,
                            ),

                            effectiveMs = effectiveMs,
                            coverRgb = coverRgb,
                        )
                    }
                }

                cursorY += step
            }

            val mainDesc = timingMainDesc
            if (mainDesc != null) {
                val desc = "mode=${local.karaokeMode} 主=$mainDesc 译=${timingSubDesc ?: "无译文"}"
                if (desc != lastTimingDesc) {
                    lastTimingDesc = desc
                }
            }
        }

        private fun drawOneLine(
            g2: Graphics2D,
            local: Appearance,
            effectiveMs: Long,
            slot: Slot,
            line: LyricLine,
            text: String,
            width: Int,
            areaLeft: Float,
            center: Float,
            coverRgb: Int?,

            lineHeight: Float,
        ) {

            val useWordTiming = slot.isCurrent &&
                local.karaokeMode != KARAOKE_MODE_LINE && line.hasWordTimingEvidence

            timingMainDesc = when {
                !slot.isCurrent -> "非当前行"
                local.karaokeMode == KARAOKE_MODE_LINE -> "整行(LINE 档)"
                line.hasWordTimingEvidence -> "逐字(${line.effectiveWords.size} 字)"
                else -> "整行(无逐字轴)"
            }

            val layout: TextLayout = renderCache.textLayoutFor(text) ?: return

            val alignedX = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)

            val regionLeft = lyricTextRegionLeft(areaLeft, HORIZONTAL_INSET)

            val availWidth = lyricTextAreaWidth(width, HORIZONTAL_INSET)
            val overflow = layout.advance - availWidth

            val availWidthCap =
                (lyricTextAreaWidth(maxWidthPxCache, HORIZONTAL_INSET) - areaLeft).coerceAtLeast(0f)
            val atWidthCap = maxWidthPxCache > 0 && availWidth >= availWidthCap - 1f
            val scrolling = overflow > SCROLL_MIN_OVERFLOW_PX && atWidthCap

            val scrollProgress = if (slot.isCurrent) sharedSweepFraction(line, effectiveMs) else 0f
            val baselineX = alignedX + if (scrolling) {
                marqueeOffsetForProgress(layout.advance, scrollProgress, availWidth)
            } else {
                0f
            }
            val baselineY = lyricBaselineY(center, layout.ascent, layout.descent)

            drawnRows.add(DrawnRow(text, layout, baselineX, baselineY))

            val savedClip = if (scrolling) g2.clip else null
            if (scrolling) {
                g2.clip(
                    Rectangle2D.Float(
                        areaLeft + HORIZONTAL_INSET / 2f,
                        0f,
                        availWidth + HORIZONTAL_INSET,
                        height.toFloat(),
                    ),
                )
            }

            if (slot.isCurrent) {

                val sungPacked = local.sungColorFor(coverRgb).toPackedArgb()
                val unsungPacked = local.unsungColorFor(coverRgb).toPackedArgb()

                val boundaries: FloatArray? = if (useWordTiming) {

                    val words = line.effectiveWords

                    FloatArray(words.size) { words[it].progress(effectiveMs) }
                } else {
                    null
                }

                val lengths: IntArray = if (useWordTiming) {
                    line.effectiveWords.map { it.text.length }.toIntArray()
                } else {
                    IntArray(0)
                }

                drawWithEnterTransform(
                    g2 = g2,
                    local = local,
                    restingBaselineX = baselineX,
                    restingBaselineY = baselineY,
                    layout = layout,
                    lineHeight = lineHeight,
                ) { target ->
                karaokeRenderer.render(
                    g = target,
                    cache = renderCache,
                    layout = layout,
                    text = text,
                    baselineX = baselineX,
                    baselineY = baselineY,
                    sungColor = sungPacked,
                    unsungColor = unsungPacked,

                    progress = sharedSweepFraction(line, effectiveMs).let {

                        if (local.karaokeMode == KARAOKE_MODE_LINE) {
                            if (it > 0f) 1f else 0f
                        } else {
                            it
                        }
                    },
                    karaoke = true,
                    wordBoundaries = boundaries,
                    wordLengths = lengths,

                    outline = local.mainOutlineFor(),

                    smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
                )
                }

                drawExitingMainLine(g2, local, coverRgb, width, areaLeft, baselineY, lineHeight)
            } else {

                val snapped = local.unsungColorFor(coverRgb).toPackedArgb()
                karaokeRenderer.render(
                    g = g2,
                    cache = renderCache,
                    layout = layout,
                    text = text,
                    baselineX = baselineX,
                    baselineY = baselineY,
                    sungColor = snapped,
                    unsungColor = snapped,
                    progress = 0f,
                    karaoke = false,

                    outline = local.mainOutlineFor(),

                    smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
                )
            }

            if (savedClip != null) g2.clip = savedClip
        }

        private fun translationFontFor(local: Appearance): Font? {

            val subTracking = if (local.translationLetterSpacingFollowMain) {
                local.mainLetterTracking()
            } else {
                0f
            }
            return fontWithLetterSpacing(
                local.fontFamily,
                (local.fontSize * local.translationScale).toInt(),
                subTracking,
            ).takeIf { it.size > 0 }
        }

        private fun drawTranslationLine(
            g2: Graphics2D,
            local: Appearance,
            line: LyricLine,
            translation: String,
            width: Int,
            areaLeft: Float,
            center: Float,
            effectiveMs: Long,

            coverRgb: Int?,
        ) {

            val subFont = translationFontFor(local) ?: return
            val layout = renderCache.textLayoutFor(translation, subFont) ?: return

            val alignedX = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)

            val regionLeft = lyricTextRegionLeft(areaLeft, HORIZONTAL_INSET)

            val availWidth = lyricTextAreaWidth(width, HORIZONTAL_INSET)
            val overflow = layout.advance - availWidth

            val availWidthCap =
                (lyricTextAreaWidth(maxWidthPxCache, HORIZONTAL_INSET) - areaLeft).coerceAtLeast(0f)
            val atWidthCap = maxWidthPxCache > 0 && availWidth >= availWidthCap - 1f
            val baselineX = alignedX + if (overflow > SCROLL_MIN_OVERFLOW_PX && atWidthCap) {
                marqueeOffsetForProgress(
                    layout.advance,
                    sharedSweepFraction(line, effectiveMs),
                    availWidth,
                )
            } else {
                0f
            }
            val baselineY = lyricBaselineY(center, layout.ascent, layout.descent)

            drawnRows.add(DrawnRow(translation, layout, baselineX, baselineY))

            val useWordTiming = local.karaokeMode != KARAOKE_MODE_LINE &&
                line.hasTranslationTimingEvidence

            timingSubDesc = when {
                local.karaokeMode == KARAOKE_MODE_LINE -> "整行(LINE 档)"
                line.hasTranslationTimingEvidence ->
                    "逐字(${line.effectiveTranslationWords.size} 字)"
                line.effectiveTranslationWords.isEmpty() -> "整行(译文无逐字轴)"
                else -> "整行(译文逐字无亚行证据)"
            }
            val words = if (useWordTiming) line.effectiveTranslationWords else emptyList()

            val boundaries: FloatArray? = if (words.isEmpty()) {
                null
            } else {
                FloatArray(words.size) { words[it].progress(effectiveMs) }
            }
            val lengths: IntArray = if (words.isEmpty()) {
                IntArray(0)
            } else {
                words.map { it.text.length }.toIntArray()
            }

            val sung = local.translationColorFor(coverRgb).toPackedArgb()
            val unsung = dimPackedArgb(sung, UNSUNG_TRANSLATION_DIM)

            drawWithEnterTransform(
                g2 = g2,
                local = local,
                restingBaselineX = baselineX,
                restingBaselineY = baselineY,
                layout = layout,
                lineHeight = local.fontSize * local.translationScale * LINE_HEIGHT_RATIO,
            ) { target ->
            karaokeRenderer.render(
                g = target,
                cache = renderCache,
                layout = layout,
                text = translation,
                baselineX = baselineX,
                baselineY = baselineY,
                sungColor = sung,
                unsungColor = unsung,

                progress = sharedSweepFraction(line, effectiveMs).let {

                    if (local.karaokeMode == KARAOKE_MODE_LINE) {
                        if (it > 0f) 1f else 0f
                    } else {
                        it
                    }
                },
                karaoke = true,
                wordBoundaries = boundaries,
                wordLengths = lengths,

                featherFontSize = subFont.size2D,

                outline = local.translationOutlineFor(),

                smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
            )
            }
        }

        private var lastNoLyricsDiag: String = ""

        private fun drawNoLyricsNotice(
            g2: Graphics2D,
            local: Appearance,
            width: Int,
            areaLeft: Float,
            areaTop: Float,
            areaHeight: Float,
            snap: TimelineSnapshot,
            coverRgb: Int?,

            suppressPlaceholder: Boolean = false,

            forceShowCredits: Boolean = false,
        ): Float {

            val title = snap.trackTitle.trim()

            val mainRowText = noLyricsMainRow(
                forceShowCredits = forceShowCredits,
                showTrackTitle = local.showTrackTitle,
                titleIsEmpty = title.isEmpty(),
                suppressPlaceholder = suppressPlaceholder,
                creditLine = trackCreditLine(snap, local.showAlbum),
                placeholder = NO_LYRICS_PLACEHOLDER,
            )

            val suppressMainRow = mainRowText == null

            val text = mainRowText.orEmpty()

            val noticeFont = fontWithLetterSpacing(local.fontFamily, local.fontSize, 0f)

            val fullAdvance = renderCache.textLayoutFor(text, noticeFont)?.advance ?: 0f

            var widestUnclipped = if (suppressMainRow) 0f else fullAdvance

            val regionLeft = lyricTextRegionLeft(areaLeft, HORIZONTAL_INSET)

            val availWidth = lyricTextAreaWidth(width, HORIZONTAL_INSET)

            runCatching {
                val key = "$width|${areaLeft.toInt()}|${availWidth.toInt()}|" +
                    "${fullAdvance.toInt()}|$text"
                if (key != lastNoLyricsDiag) {
                    lastNoLyricsDiag = key
                }
            }

            val centerY = areaTop + areaHeight / 2f

            if (!suppressMainRow) {
                val shown = ellipsizeToWidth(text, availWidth) { candidate ->
                    renderCache.textLayoutFor(candidate, noticeFont)?.advance ?: 0f
                }
                val layout =
                    renderCache.textLayoutFor(shown, noticeFont) ?: return widestUnclipped
                val baselineX = alignBaselineX(width, layout.advance, local.contentAlign, areaLeft)
                val baselineY = lyricBaselineY(centerY, layout.ascent, layout.descent)

                drawnRows.add(DrawnRow(shown, layout, baselineX, baselineY))

                val packed = local.unsungColorFor(coverRgb).toPackedArgb()
                karaokeRenderer.render(
                    g = g2,
                    cache = renderCache,
                    layout = layout,

                    text = shown,
                    baselineX = baselineX,
                    baselineY = baselineY,

                    sungColor = packed,
                    unsungColor = packed,
                    progress = 0f,
                    karaoke = false,

                    outline = local.translationOutlineFor(),

                    smooth = local.karaokeMode == KARAOKE_MODE_SMOOTH,
                )
            }

            if (local.titleInTranslation && title.isNotEmpty()) {
                val subFont = fontWithLetterSpacing(
                    local.fontFamily, (local.fontSize * local.translationScale).toInt(), 0f,
                    local.fontBold,
                )

                renderCache.textLayoutFor(title, subFont)?.advance?.let { titleAdvance ->
                    if (titleAdvance > widestUnclipped) widestUnclipped = titleAdvance
                }
                val shownTitle = ellipsizeToWidth(title, availWidth) { candidate ->
                    renderCache.textLayoutFor(candidate, subFont)?.advance ?: 0f
                }
                val subLayout = renderCache.textLayoutFor(shownTitle, subFont)
                if (subLayout != null) {

                    val subCenter = centerY + translationCenterOffsetPx(
                        fontSize = local.fontSize,
                        translationScale = local.translationScale,
                        above = false,
                        lineGapPx = local.lineGapPx,
                    )
                    val subBaselineX = alignBaselineX(
                        width, subLayout.advance, local.contentAlign, areaLeft,
                    )
                    val subBaselineY = lyricBaselineY(subCenter, subLayout.ascent, subLayout.descent)

                    drawnRows.add(DrawnRow(shownTitle, subLayout, subBaselineX, subBaselineY))
                    val subPacked = local.translationColorFor(coverRgb).toPackedArgb()
                    karaokeRenderer.render(
                        g = g2, cache = renderCache, layout = subLayout, text = shownTitle,
                        baselineX = subBaselineX, baselineY = subBaselineY,
                        sungColor = subPacked, unsungColor = subPacked,
                        progress = 0f, karaoke = false,
                        outline = local.translationOutlineFor(),
                    )
                }
            }

            return widestUnclipped
        }
    }

    private companion object {

        const val SINGLE_ROW = 1

        const val SECONDARY_SPECTRUM_HEIGHT_SCALE = 0.5f

        const val NO_LYRICS_PLACEHOLDER = "暂无歌词"

        const val TRANSLATION_GAP = TRANSLATION_GAP_PX

        const val VERTICAL_PADDING = ISLAND_VERTICAL_PADDING_PX

        private fun translationRowHeight(
            fontSize: Int,
            translationScale: Float,
            lineGapPx: Float,
        ): Float = translationRowHeightPx(fontSize, translationScale, lineGapPx)

        fun alignBaselineX(width: Int, advance: Float, align: Align, areaLeft: Float = 0f): Float =

            lyricAlignBaselineX(width, advance, align.wire, areaLeft, HORIZONTAL_INSET)

        fun effectiveRows(lines: LineCount): Int = lines.rows

        fun islandHeightFor(
            fontSize: Int,
            translationEnabled: Boolean,
            lines: LineCount = LineCount.SINGLE,
            lineGapPx: Float = 0f,

            translationScale: Float = 0.64f,

            followFont: Boolean = ISLAND_HEIGHT_FOLLOW_FONT_DEFAULT,

            heightRatioPercent: Int = ISLAND_HEIGHT_RATIO_DEFAULT_PERCENT,
        ): Int {

            return islandCollapsedHeightPx(
                fontSize = fontSize,
                rows = effectiveRows(lines),
                lineGapPx = lineGapPx,
                translationRowHeightPx = if (translationEnabled) {
                    translationRowHeight(fontSize, translationScale, lineGapPx)
                } else {
                    0f
                },
                followFont = followFont,
                heightRatioPercent = heightRatioPercent,
            )
        }

        const val BASE_BACKGROUND_RED = BACKGROUND_BASE_RED
        const val BASE_BACKGROUND_GREEN = BACKGROUND_BASE_GREEN
        const val BASE_BACKGROUND_BLUE = BACKGROUND_BASE_BLUE

        val BASE_BACKGROUND: Color = Color(BASE_BACKGROUND_RED, BASE_BACKGROUND_GREEN, BASE_BACKGROUND_BLUE)

        const val SPECTRUM_ANCHOR_TOP: String = "TOP"

        const val SPECTRUM_ANCHOR_RIGHT: String = "RIGHT"

        const val LEADING_INSET = HORIZONTAL_INSET

        const val LEADING_GAP = 12f

        const val MIN_ISLAND_WIDTH = 280

        const val SCREEN_SIDE_MARGIN_PX = 80

        const val WIDTH_HYSTERESIS_PX = 24

        const val MAX_COVER_SIZE = COVER_MAX_OUTER_SIZE_PX

        const val MIN_COVER_SIZE = COVER_MIN_OUTER_SIZE_PX

        const val COVER_ARC_RATIO = 0.18f

        const val COVER_ARC_RATIO_SQUARE = 0.06f

        val COVER_OUTLINE: Color = Color(255, 255, 255, 35)

        val VINYL_RING_COLOR: Color = Color(18, 18, 18)

        const val SPECTRUM_BACKGROUND_ALPHA = 1.0f

        const val MIN_SPECTRUM_HEIGHT_PCT = 10

        const val COVER_REPAINT_DELAY_MS = 120

        const val MAX_COVER_REPAINT_ATTEMPTS = 8

        const val COVER_LOCK_BADGE_PX = 16f

        const val COVER_LOCK_BADGE_INSET_PX = 4f

        const val COVER_LOCK_BADGE_MS = 1200L

        const val COVER_LOCK_BADGE_SCRIM_ALPHA = 140

        const val COVER_WATER_WAVE_PERIOD_MS = 4000L

        const val SEEK_DISPLAY_HOLD_MS = 3_000L

        const val SEEK_CONFIRM_TOLERANCE_MS = 250L

        val DEGRADED_BACKGROUND: Color = Color(0x14, 0x16, 0x1A)

        const val SEEK_JUMP_THRESHOLD_MS = 800L

        const val COVER_CLICK_MAX_MOVE_PX = 4

        val coverClickDebounceMs: Int by lazy {
            runCatching {
                java.awt.Toolkit.getDefaultToolkit()
                    .getDesktopProperty("awt.multiClickInterval") as? Int
            }.getOrNull()?.coerceAtLeast(1) ?: 500
        }

        const val HOVER_TICK_MS = 40

        const val REGIONAL_CLICK_THROUGH = true

        const val FADE_TICK_MS = 16

        const val WATCHDOG_TICK_MS = 400

        const val FULLSCREEN_CONFIRM_TICKS = 2

        const val RAISE_THROTTLE_MS = 200L
    }
}

private data class DrawnRow(
    val text: String,
    val layout: TextLayout,
    val baselineX: Float,
    val baselineY: Float,
)

    internal enum class LineCount(val wire: String, val rows: Int) {

        SINGLE("SINGLE", 1),

        DUAL("DUAL", 1),
        ;

        companion object {

            fun parse(raw: String?): LineCount =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
                    ?: SINGLE
        }
    }

    internal enum class Align(val wire: String) {

        LEFT("LEFT"),

        CENTER("CENTER"),

        RIGHT("RIGHT"),
        ;

        companion object {

            fun parse(raw: String?): Align =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: CENTER
        }
    }

    internal enum class NoLyricsMode(val wire: String) {

        HIDE_LINE("HIDE_LINE"),

        PLACEHOLDER("PLACEHOLDER"),

        HIDE_ISLAND("HIDE_ISLAND"),
        ;

        companion object {

            fun parse(raw: String?): NoLyricsMode =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: HIDE_LINE
        }
    }

private data class Slot(val line: LyricLine?, val isCurrent: Boolean)

internal const val FEATHER_MARGIN: Int = 0

internal fun glassAaEnabled(): Boolean = false

private const val GLASS_GROWTH_ANIM_ENABLED: Boolean = false

private const val ANIM_FAST_CAPTURE_ENABLED: Boolean = false

private var slotPitchLoggedTenth: Int = -1
private var slotPitchLoggedAt: Long = 0L

private var slotCountLogged: String = ""
private var slotCountLoggedAt: Long = 0L

private var glassShapeLogged: String = ""
