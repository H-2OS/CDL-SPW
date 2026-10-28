// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Rectangle
import java.awt.Shape
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean

data class BackdropCaptureParams(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val downsample: Int = DEFAULT_DOWNSAMPLE,
    val blurRadius: Int = 0,
    val highpassRadius: Int = SelfCaptureRules.HIGHPASS_RADIUS,

    val cornerRadiusPx: Double = -1.0,

    val cornerRadiusBottomPx: Double = Double.NaN,

    val glassCompose: Boolean = false,
    val glassGlarePercent: Int = 60,
    val glassDepthEffect: Boolean = true,
    val glassSurfaceAlpha: Int = 0,
    val dumpDir: java.io.File? = null,

    val dumpEnabled: Boolean = false,

    val blurKernel: String = CoverBackdrop.KERNEL_POISSON,

    val tileIsWholeWindow: Boolean = false,

    val keepAlpha: Boolean = false,

    val dumpDirProvider: () -> String? = { null },

    val targetRegionProvider: () -> java.awt.Rectangle? = { null },

    val supersetActiveProvider: () -> Boolean = { false },
    val useWindowBackdrop: Boolean = false,
    val selfHwndProvider: () -> Long = { 0L },
    val dpiScale: Double = 1.0,
    val bypassSelfGate: Boolean = false,

    val toggleWdaForCapture: Boolean = false,

    val stillModeProvider: () -> Boolean = { false },

    val onPublished: () -> Unit = {},

    val skipUnchangedFrames: Boolean = false,

    val splitCaptureThread: Boolean = false,

    val mirrorHwndProvider: () -> Long = { 0L },
    val fastIntervalMs: Long = 33L,

    val refraction: BackdropRefraction.Params? = null,

    val preferGpu: Boolean = false,
) {

    val enabled: Boolean get() = blurRadius > 0

    companion object {

        const val DEFAULT_INTERVAL_MS: Long = 50L

        const val DEFAULT_DOWNSAMPLE: Int = 4

        const val THREAD_NAME: String = "cdl-l3-capture"
    }
}

data class BackdropCaptureReport(
    val accepted: Boolean,
    val level: SelfCaptureLevel,
    val streak: Int,
    val s1: Double?,
    val s1hp: Double?,
    val reason: String,
    val captureMs: Double,
    val blurMs: Double,
    val maskInside: Int,
)

class L3BackdropState {

    @Volatile
    var backdrop: BufferedImage? = null
        private set

    @Volatile
    var deliveredSeq: Long = 0L
        private set

    @Volatile
    var rejectedCount: Long = 0L
        private set

    @Volatile
    var lastReport: BackdropCaptureReport? = null
        private set

    @Volatile var backdropRegion: java.awt.Rectangle? = null

    var fastUntilMs: Long = 0L

    @Volatile var tileScreenRect: java.awt.Rectangle? = null

    val gate: SelfCaptureGate = SelfCaptureGate()

    @Volatile
    var captureDisabled: Boolean = false
        private set

    @Volatile
    var composited: Boolean = false
        private set

    @Volatile
    var fastRate: Boolean = false

    @Volatile
    var lastPath: String = "?"

    @Volatile
    var skippedSame: Long = 0
        private set

    fun noteSkippedSame() { skippedSame += 1L }

    fun seedFrom(previous: L3BackdropState?) {
        val img = previous?.backdrop ?: return
        backdrop = img
        composited = previous.composited
    }

    fun acceptCapture(
        blurred: BufferedImage,
        report: BackdropCaptureReport,
        composited: Boolean = false,
        screenRect: java.awt.Rectangle? = null,
    ) {
        tileScreenRect = screenRect
        backdrop = blurred
        this.composited = composited
        deliveredSeq += 1L
        lastReport = report
    }

    fun noteRejected(report: BackdropCaptureReport) {
        rejectedCount += 1L
        lastReport = report
    }

    fun fallbackToPureColor() {
        backdrop = null
        captureDisabled = true
    }

    fun reset() {
        gate.reset()
        backdrop = null
        captureDisabled = false
        lastReport = null
    }
}

class L3CaptureWorker(
    private val params: BackdropCaptureParams,
    private val state: L3BackdropState,

    private val ownFrameProvider: () -> OwnFrame?,

    private val regionProvider: () -> Rectangle?,

    private val onWarn: (String) -> Unit = {},

    private val maskProvider: (Shape, Int, Int) -> BooleanArray?,

) {

    private var thread: Thread? = null

    val running: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    var rounds: Long = 0L
        private set

    private var lastGlassNanos = 0L
    private var lastGpuNanos = 0L

    private var lastProducerRegion: java.awt.Rectangle? = null

    private var geoFastUntil: Long = 0L

    private var moveFastUntil: Long = 0L

    private val MOVE_INTERVAL_MS = 2L
    private var moveRounds = 0
    private var moveStartedAt = 0L

    private var lastNullLogAt: Long = 0L
    private var publishedSinceLog: Int = 0
    private var lastPubLogAt: Long = 0L

    private var geoLogAt: Long = 0L
    private var lastTileDumpAt = 0L
    private var tileDumpSeq: Long = 0L
    @Volatile private var lastStillMode: Boolean = false

    private val frameSlot = java.util.concurrent.ArrayBlockingQueue<Pair<BufferedImage, Rectangle>>(1)
    @Volatile
    private var producerRunning = false
    private var producerThread: Thread? = null

    private var startedAtMs: Long = 0L
    private var firstPublishAtMs: Long = 0L

    private var lastCaptureHash: Int = 0

    private var lastProducerFp: Long = 0L
    private var idleRounds: Int = 0

    private var lastWdaCheckAt: Long = 0L
    private var hasFp: Boolean = false

    private fun fingerprintOf(img: BufferedImage): Long {
        var h = 0x9E3779B97F4A7C15uL.toLong()
        val sx = (img.width / 16).coerceAtLeast(1)
        val sy = (img.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                h = h * 1099511628211L + img.getRGB(x, y).toLong()
                x += sx
            }
            y += sy
        }
        return h
    }

    private var skippedSameCount: Long = 0L

    private fun startProducer() {
        if (producerRunning) return
        producerRunning = true

        resetProducerDedup()

        val timerOk = runCatching { dev.cdl.platform.WinTimer.beginPeriod(1) }
            .getOrDefault(false)
        runCatching {
        }
        val t = Thread({
            while (producerRunning) {
                runCatching {
                    val region = regionProvider()
                    if (region == null) {
                        Thread.sleep(20L)
                        return@runCatching
                    }

            run {
                val stillNow = params.stillModeProvider()
                if (stillNow != lastStillMode) {
                    lastStillMode = stillNow
                    val h = params.selfHwndProvider()
                    runCatching { dev.cdl.platform.CaptureExclusion.set(h, !stillNow) }
                }
            }
            if (params.stillModeProvider()) {
                        Thread.sleep(100L)
                        return@runCatching
                    }

                    val lastRegion = lastProducerRegion
                    if (lastRegion == null || lastRegion != region) {
                        val onlyMoved = lastRegion != null &&
                            lastRegion.width == region.width && lastRegion.height == region.height
                        lastProducerRegion = java.awt.Rectangle(region)
                        geoFastUntil = System.currentTimeMillis() + 300L

                        if (onlyMoved) moveFastUntil = System.currentTimeMillis() + 300L
                    }
                    val img = captureScreen(region)
                    if (img != null) {
                        val fp = fingerprintOf(img)
                        if (!hasFp || fp != lastProducerFp) {
                            hasFp = true
                            lastProducerFp = fp
                            idleRounds = 0

                            frameSlot.poll()
                            frameSlot.offer(img to region)
                        } else {
                            idleRounds += 1
                        }
                    }
                }

                run {
                    val n = System.currentTimeMillis()
                    if (n - lastPubLogAt > 2000L) {
                        lastPubLogAt = n
                        runCatching {
                            val hz = moveRounds / 2.0
                        moveRounds = 0
                        }
                        publishedSinceLog = 0
                    }
                }
                val nowFast = System.currentTimeMillis()
                val moveFast = nowFast < moveFastUntil
                if (moveFast) moveRounds += 1
                val geoFast = nowFast < geoFastUntil || nowFast < state.fastUntilMs
                val baseInterval = when {

                    moveFast -> MOVE_INTERVAL_MS
                    state.fastRate || geoFast -> params.fastIntervalMs
                    else -> params.intervalMs
                }

                val mul = when {
                    state.fastRate -> 1
                    idleRounds < 20 -> 1
                    idleRounds < 60 -> 2
                    idleRounds < 180 -> 4
                    else -> 8
                }
                val interval = (baseInterval * mul).coerceAtMost(400L)

                try {
                    Thread.sleep(interval.coerceAtLeast(16L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "cdl-l3-capture")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
        producerThread = t
    }

    private fun stopProducer() {
        producerRunning = false
        producerThread?.interrupt()
        producerThread = null
        frameSlot.clear()
        resetProducerDedup()
    }

    private fun resetProducerDedup() {
        hasFp = false
        lastProducerFp = 0L
        idleRounds = 0
        lastProducerRegion = null
    }

    fun start() {
        if (!params.enabled) return
        if (!running.compareAndSet(false, true)) return

        startedAtMs = System.currentTimeMillis()
        if (params.splitCaptureThread) startProducer()
        val t = Thread({ loop() }, BackdropCaptureParams.THREAD_NAME)
        t.isDaemon = true

        t.priority = Thread.MIN_PRIORITY
        thread = t
        t.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        stopProducer()
    }

    private fun loop() {
        var nextDue = System.nanoTime()
        while (running.get()) {

            val interval = if (state.fastRate) params.fastIntervalMs else params.intervalMs
            nextDue += interval * 1_000_000L
            runOnce()

            val now = System.nanoTime()
            val waitNanos = (nextDue - now).coerceAtMost(interval * 1_000_000L)
            if (waitNanos > 0) {
                try {
                    Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                } catch (_: InterruptedException) {

                    Thread.currentThread().interrupt()
                    return
                }
            } else {

                nextDue = System.nanoTime()
            }
        }
    }

    fun runOnce() {
        rounds += 1L

        var delivered = false
        var capNanos = 0L
        var blurNanos = 0L
        var refractNanos = 0L
        try {
            if (state.captureDisabled) return
            val own = ownFrameProvider()

            if (!params.bypassSelfGate && mustStartWithPureColor(own?.pixels)) {
                val reading = coldStartReading()
                val res = state.gate.onReading(reading)
                noteAndReport(res, reading, captureMs = 0.0, blurMs = 0.0, maskInside = 0)
                return
            }

            val frame = own ?: return
            val region = regionProvider() ?: return

            val wIsland = frame.islandWidth
            val hIsland = frame.islandHeight
            if (wIsland <= 0 || hIsland <= 0) return

            val t0 = System.nanoTime()

            run {
                val nowW = System.currentTimeMillis()
                if (nowW - lastWdaCheckAt > 1000L) {
                    lastWdaCheckAt = nowW
                    val hw = params.selfHwndProvider()
                    if (hw != 0L) {
                        val got = IntArray(1)
                        val okRead = dev.cdl.platform.CaptureExclusion.query(hw, got)
                        if (!okRead || got[0] != 0x11) {
                            dev.cdl.platform.CaptureExclusion.set(hw, true)
                            runCatching {
                            }
                        }
                    }
                }
            }
            val screen = if (params.splitCaptureThread) {
                frameSlot.poll(120, java.util.concurrent.TimeUnit.MILLISECONDS)?.first
            } else {
                captureScreen(region)
            }
            val t1 = System.nanoTime()
            capNanos = t1 - t0

            if (screen == null) {

                val n = System.currentTimeMillis()
                if (n - lastNullLogAt > 2000L) {
                    lastNullLogAt = n
                    runCatching {
                    }
                }
                return
            }

            val targetRect = if (params.supersetActiveProvider()) {
                params.targetRegionProvider()
            } else {
                null
            }
            val cropDx = if (targetRect != null) targetRect.x - region.x else 0
            val cropDy = if (targetRect != null) targetRect.y - region.y else 0
            val w = if (params.bypassSelfGate) screen.width else wIsland
            val h = if (params.bypassSelfGate) screen.height else hIsland
            if (w <= 0 || h <= 0) return

            val capPixels = cropArgb(screen, Rectangle(0, 0, w, h), w, h)

            if (capPixels == null) {

                if (!params.bypassSelfGate) {
                    val reading = SelfCaptureReading(
                        decision = SelfCaptureDecision.UNKNOWN,
                        s1 = null,
                        s1hp = null,
                        reason = "no-own-pixels",
                    )
                    val res = state.gate.onReading(reading)
                    noteAndReport(res, reading, ms(t0, t1), 0.0, 0)
                }
                return
            }

            var gateRes: SelfCaptureGateResult? = null
            var gateReading: SelfCaptureReading? = null
            var maskInside = 0
            if (!params.bypassSelfGate) {
                val ownPixels = ownPixelsOrNull(frame.pixels, w, h)
                if (ownPixels == null) {

                    val reading = SelfCaptureReading(
                        decision = SelfCaptureDecision.UNKNOWN,
                        s1 = null,
                        s1hp = null,
                        reason = "no-own-pixels",
                    )
                    val res = state.gate.onReading(reading)
                    noteAndReport(res, reading, ms(t0, t1), 0.0, 0)
                    return
                }
                val mask = maskProvider(frame.shape, w, h)
                if (mask == null) {

                    val reading = SelfCaptureReading(
                        decision = SelfCaptureDecision.UNKNOWN,
                        s1 = null,
                        s1hp = null,
                        reason = "mask-size-mismatch",
                    )
                    val res = state.gate.onReading(reading)
                    noteAndReport(res, reading, ms(t0, t1), 0.0, 0)
                    return
                }
                val reading = detectSelfCapture(
                    capturePixels = capPixels,
                    ownPixels = ownPixels,
                    insideMask = mask,
                    w = w,
                    h = h,
                    highpassRadius = params.highpassRadius,
                )
                val res = state.gate.onReading(reading)
                gateRes = res
                gateReading = reading
                maskInside = mask.count { it }

                if (state.gate.shouldWarn(res)) {
                    val msg = "真透视暂不可用（疑似自捕获，连续 ${res.streak} 帧）"

                    runCatching { onWarn(msg) }
                }
                if (state.gate.shouldFallbackToPureColor(res)) {
                    state.fallbackToPureColor()
                    noteAndReport(res, reading, ms(t0, t1), 0.0, mask.count { it })
                    return
                }
                if (!res.accepted) {

                    noteAndReport(res, reading, ms(t0, t1), 0.0, mask.count { it })
                    return
                }
            }

            val t2 = System.nanoTime()
            val blurred = blurBackdrop(capPixels, w, h)
            val t3 = System.nanoTime()
            blurNanos = t3 - t2

            val t4 = System.nanoTime()
            val baked = bakeRefraction(blurred, w, h, capPixels)
            val t5 = System.nanoTime()
            refractNanos = t5 - t4

            delivered = true

            run {
                val n = System.currentTimeMillis()
                if (n - geoLogAt > 800L) {
                    geoLogAt = n
                    runCatching {
                    }
                }
            }
            publishedSinceLog += 1
            state.acceptCapture(
                blurred = baked.img,
                report = BackdropCaptureReport(
                    accepted = true,
                    level = gateRes?.level ?: dev.cdl.render.SelfCaptureLevel.FULL,
                    streak = gateRes?.streak ?: 0,
                    s1 = gateReading?.s1,
                    s1hp = gateReading?.s1hp,
                    reason = gateReading?.reason ?: "bypass-self-gate",
                    captureMs = ms(t0, t1),
                    blurMs = ms(t2, t3),
                    maskInside = maskInside,
                ),

                screenRect = region,
            )

            runCatching { params.onPublished() }
        } catch (t: Throwable) {

            runCatching {  }
        } finally {

        }
    }

    private fun flattenOverBackdrop(
        tile: BufferedImage,
        backdrop: BufferedImage,
        cornerRadius: Double = Double.NaN,
        cornerRadiusBottom: Double = Double.NaN,

        insetPx: Int = dev.cdl.window.FEATHER_MARGIN,
    ): BufferedImage {
        val w = tile.width
        val h = tile.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val gg = out.createGraphics()
        try {
            gg.drawImage(backdrop, 0, 0, null)
            gg.drawImage(tile, 0, 0, null)
        } finally {
            gg.dispose()
        }
        val px = IntArray(w * h)
        out.getRGB(0, 0, w, h, px, 0, w)
        val bd = IntArray(w * h)
        backdrop.getRGB(0, 0, w, h, bd, 0, w)

        val FEATHER_PX = 1.5

        val maxR = ((minOf(w, h) - 1) / 2.0).coerceAtLeast(0.0)
        var rt = if (cornerRadius.isFinite() && cornerRadius >= 0.0) cornerRadius else 0.0
        val rb0 = if (cornerRadiusBottom.isFinite() && cornerRadiusBottom >= 0.0) {
            cornerRadiusBottom
        } else {
            rt
        }
        var rb = rb0
        rt = rt.coerceIn(0.0, maxR)
        rb = rb.coerceIn(0.0, maxR)
        val ins = insetPx.toDouble()
        for (y in 0 until h) {
            val r = if (y < h / 2) rt else rb
            val loX = r.coerceAtMost((w - 1) / 2.0)
            val loY = r.coerceAtMost((h - 1) / 2.0)
            for (x in 0 until w) {

                val cx = x.toDouble().coerceIn(
                    (ins + loX), ((w - 1 - ins - loX)).coerceAtLeast(ins + loX),
                )
                val cy = y.toDouble().coerceIn(
                    (ins + loY), ((h - 1 - ins - loY)).coerceAtLeast(ins + loY),
                )
                val dx = x - cx
                val dy = y - cy

                val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                val cov = if (r <= 0.0) {
                    1.0
                } else {
                    (0.5 - (dist - r) / FEATHER_PX).coerceIn(0.0, 1.0)
                }
                val i = y * w + x
                px[i] = when {

                    cov >= 1.0 -> {
                        if ((px[i] ushr 24) == 0) {
                            (bd[i] or (0xFF shl 24))
                        } else {
                            (px[i] or (0xFF shl 24))
                        }
                    }
                    cov <= 0.0 -> (bd[i] or (0xFF shl 24))
                    else -> {
                        val g0 = px[i]
                        val b0 = bd[i]
                        fun mix(shift: Int): Int {
                            val gv = (g0 shr shift) and 0xFF
                            val bv = (b0 shr shift) and 0xFF
                            val v = (gv * cov + bv * (1.0 - cov)).toInt().coerceIn(0, 255)
                            return v shl shift
                        }
                        (0xFF shl 24) or mix(16) or mix(8) or mix(0)
                    }
                }
            }
        }
        out.setRGB(0, 0, w, h, px, 0, w)
        return out
    }

    private class Baked(val img: BufferedImage, val composited: Boolean)

    private fun bakeRefraction(
        blurred: BufferedImage, w: Int, h: Int, capPixels: IntArray,
    ): Baked {
        val p = params.refraction ?: return Baked(blurred, false)
        if (p.isIdentity) return Baked(blurred, false)
        if (params.cornerRadiusPx < 0.0) return Baked(blurred, false)

        if (params.preferGpu) {
            val t0 = System.nanoTime()
            val gpu = gpuRefractionOrNull(blurred, p)
            lastGpuNanos = if (gpu != null) System.nanoTime() - t0 else 0L
            if (gpu != null) {
                state.lastPath = "GPU"

                return Baked(
                    if (params.keepAlpha) {
                        gpu
                    } else {
                        flattenOverBackdrop(
                            gpu, blurred,
                            if (params.tileIsWholeWindow) Double.NaN else params.cornerRadiusPx,
                            if (params.tileIsWholeWindow) Double.NaN else params.cornerRadiusBottomPx,
                        )
                    },
                    true,
                )
            }
        }

        val l3 = runCatching {

            BackdropRefraction.bake(
                blurred, params.cornerRadiusPx, p,
                cornerRadiusBottom = params.cornerRadiusBottomPx,
            )
        }.getOrElse { t ->

            runCatching {
            }
            blurred
        }

        if (params.glassCompose) {
            val tg = System.nanoTime()
            val tile = runCatching {
                MaterialLayers.composeGlassTile(
                    srcBackdrop = l3,
                    cornerRadius = params.cornerRadiusPx,
                    cornerRadiusBottom = params.cornerRadiusBottomPx,

                    roundness = 2.0,
                    glarePercent = params.glassGlarePercent,
                    depthEffect = params.glassDepthEffect,
                    surfaceAlphaPercent = params.glassSurfaceAlpha,
                    refHeightPx = p.thicknessPx,
                )
            }.getOrNull()
            lastGlassNanos = System.nanoTime() - tg
            if (tile != null) {

                val flat = flattenOverBackdrop(
                    tile, l3,
                    if (params.tileIsWholeWindow) Double.NaN else params.cornerRadiusPx,
                    if (params.tileIsWholeWindow) Double.NaN else params.cornerRadiusBottomPx,
                )
                state.lastPath = "CPU"
                return Baked(flat, true)
            }
        }
        state.lastPath = "CPU-raw"
        return Baked(l3, false)
    }

    private fun gpuRefractionOrNull(
        blurred: BufferedImage,
        p: BackdropRefraction.Params,
    ): BufferedImage? {
        if (!GpuLiquidGlass.isAvailable()) return null
        return runCatching {

            GpuLiquidGlass.render(
                backdrop = blurred,
                cornerRadius = params.cornerRadiusPx,
                params = p,
                cornerRadiusBottom = params.cornerRadiusBottomPx,

                surfaceAlpha = params.glassSurfaceAlpha / 100.0,
                glarePercent = params.glassGlarePercent,
            )
        }.getOrElse { t ->

            runCatching {
            }
            null
        }
    }

    private fun noteAndReport(
        res: SelfCaptureGateResult,
        reading: SelfCaptureReading,
        captureMs: Double,
        blurMs: Double,
        maskInside: Int,
    ) {
        state.noteRejected(
            BackdropCaptureReport(
                accepted = false,
                level = res.level,
                streak = res.streak,
                s1 = reading.s1,
                s1hp = reading.s1hp,
                reason = reading.reason,
                captureMs = captureMs,
                blurMs = blurMs,
                maskInside = maskInside,
            ),
        )
    }

    private fun captureScreen(region: Rectangle): BufferedImage? =
        runCatching { captureProvider(region) }
            .getOrNull()

    private fun blurBackdrop(pixels: IntArray, w: Int, h: Int): BufferedImage {
        val src = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        src.setRGB(0, 0, w, h, pixels, 0, w)

        return CoverBackdrop.blurForBackdrop(
            src,
            params.blurRadius,
            params.downsample,
            blurKernel = params.blurKernel,
        )
    }

    private fun ownPixelsOrNull(img: BufferedImage?, w: Int, h: Int): IntArray? {
        if (img == null) return null
        if (img.width != w || img.height != h) return null
        return toArgbArray(img)
    }

    var robotProvider: () -> java.awt.Robot? = { null }

    var captureProvider: (Rectangle) -> BufferedImage? = { region ->
        robotProvider()?.createScreenCapture(region)
    }

    private fun ms(from: Long, to: Long): Double = (to - from) / 1_000_000.0

    private fun fmt(v: Double?): String =
        if (v == null) "null" else String.format(java.util.Locale.ROOT, "%.3f", v)

}
