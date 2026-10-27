// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.TexturePaint
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.Random
import dev.cdl.render.gpu.GlOffscreen
import dev.cdl.render.gpu.LiquidGlassShader

object MaterialLayers {

    const val MAX_NOISE_AMPLITUDE = 20

    const val NOISE_TILE_SIZE = 128

    private const val HIGHLIGHT_MAX_ALPHA = 46

    private const val HIGHLIGHT_SPAN_RATIO = 0.55f

    const val SHADOW_PAD_PX = 24

    const val SHADOW_OFFSET_X_PX = 2

    const val SHADOW_OFFSET_Y_PX = 6

    const val SHADOW_SHRINK = 2

    const val GLASS_ANIM_SHRINK = 2

    const val GLASS_STEADY_SHRINK = 1

    const val DEFAULT_GLASS_ANIMATING = false

    private const val SHADOW_LAYERS = 18

    private const val SHADOW_GROW_STEP = 1.6

    private const val SHADOW_MAX_ALPHA = 150.0

    private val noiseTile: BufferedImage by lazy {
        val img = BufferedImage(NOISE_TILE_SIZE, NOISE_TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
        val rnd = Random(99L)
        for (y in 0 until NOISE_TILE_SIZE) {
            for (x in 0 until NOISE_TILE_SIZE) {

                val v = rnd.nextInt(256)
                img.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
        }
        img
    }

    private val noisePaint: TexturePaint by lazy {
        TexturePaint(noiseTile, Rectangle(0, 0, NOISE_TILE_SIZE, NOISE_TILE_SIZE))
    }

    private val noiseComposites: Array<AlphaComposite> = Array(MAX_NOISE_AMPLITUDE + 1) { a ->
        AlphaComposite.getInstance(AlphaComposite.SRC_OVER, if (a <= 0) 0f else a * 2f / 255f)
    }

    private var cachedGradient: LinearGradientPaint? = null
    private var cachedKey: String = ""

    private var cachedLiquid: BufferedImage? = null
    private var cachedLiquidKey: String = ""

    private var cachedLiquidShrunk: Boolean = false

    @Volatile
    var gpuEnabled: Boolean = false

    private const val GPU_FAILURE_LIMIT = 3

    private var gpuFailStreak = 0

    @Volatile
    private var gpuDisabled = false

    private val glOffscreen: GlOffscreen by lazy { GlOffscreen() }

    private const val GPU_DPR = 1.0

    private class GlassSnap(val key: String, val img: BufferedImage, val w: Int, val h: Int)

    private class GlassReq(
        val key: String, val w: Int, val h: Int,
        val cornerRadius: Double, val roundness: Double, val tintPercent: Int,
        val baseColor: java.awt.Color, val endArgb: Int, val glareAngleDeg: Double,
        val glarePercent: Int, val depthEffect: Boolean, val cornerRadiusBottom: Double,
        val animating: Boolean,

        val surfaceOpacityPercent: Int = 100,
    )

    @Volatile
    private var glassSnap: GlassSnap? = null

    private val glassBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var glassReq: GlassReq? = null

    private val glassPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "spw-glass-worker").apply {
                isDaemon = true

                priority = Thread.MIN_PRIORITY
            }
        }
    }

    private const val GLASS_SHARD_MAX = 4

    private val glassShardPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(GLASS_SHARD_MAX - 1) { r ->
            Thread(r, "spw-glass-shard").apply {
                isDaemon = true

                priority = Thread.MIN_PRIORITY
            }
        }
    }

    private val glassShardCount: Int by lazy {
        Runtime.getRuntime().availableProcessors().coerceIn(1, GLASS_SHARD_MAX)
    }

    fun liquidGlassImageAsync(
        w: Int, h: Int, cornerRadius: Double, roundness: Double,
        tintPercent: Int, baseColor: java.awt.Color, endArgb: Int, glareAngleDeg: Double,
        glarePercent: Int = 60, depthEffect: Boolean = true,
        animating: Boolean = false, cornerRadiusBottom: Double = Double.NaN,

        surfaceOpacityPercent: Int = 100,
    ): BufferedImage? {
        val crBottom = if (cornerRadiusBottom.isNaN()) cornerRadius else cornerRadiusBottom
        val shrinkForKey = if (animating) GLASS_ANIM_SHRINK else 1
        val key = "" + w + "|" + h + "|" + cornerRadius + "|" + crBottom + "|" + roundness +
            "|" + tintPercent + "|" + baseColor.rgb + "|" + endArgb +
            "|" + glareAngleDeg + "|" + glarePercent + "|" + depthEffect + "|s" + shrinkForKey +
            "|a" + surfaceOpacityPercent
        val snap = glassSnap

        if (snap != null && snap.key == key) return snap.img

        glassReq = GlassReq(
            key, w, h, cornerRadius, roundness, tintPercent, baseColor, endArgb,
            glareAngleDeg, glarePercent, depthEffect, crBottom, animating, surfaceOpacityPercent,
        )
        if (glassBusy.compareAndSet(false, true)) {
            glassPool.submit { glassLoop() }
        }

        return if (snap != null && snap.w == w && snap.h == h) snap.img else null
    }

    private fun glassLoop() {
        try {
            while (true) {
                val req = glassReq ?: break
                glassReq = null
                try {

                    val img = liquidGlassImage(
                        w = req.w, h = req.h, cornerRadius = req.cornerRadius,
                        roundness = req.roundness, tintPercent = req.tintPercent,
                        baseColor = req.baseColor, endArgb = req.endArgb,
                        glareAngleDeg = req.glareAngleDeg, glarePercent = req.glarePercent,
                        depthEffect = req.depthEffect, animating = req.animating,
                        cornerRadiusBottom = req.cornerRadiusBottom,

                        surfaceOpacityPercent = req.surfaceOpacityPercent,
                    )
                    if (img != null) {

                        glassSnap = GlassSnap(req.key, img, req.w, req.h)
                    }
                } catch (t: Throwable) {

                }
            }
        } finally {
            glassBusy.set(false)

            if (glassReq != null && glassBusy.compareAndSet(false, true)) {
                glassPool.submit { glassLoop() }
            }
        }
    }

    private var cachedShadow: BufferedImage? = null
    private var cachedShadowKey: String = ""

    fun shouldDrawHighlight(strength: Int): Boolean = strength > 0

    @Suppress("UNUSED_PARAMETER")
    fun highlightAlphaFor(backgroundLuminance: Float = 0.5f): Int = HIGHLIGHT_MAX_ALPHA

    fun shouldDrawGradient(style: String, tintPercent: Int): Boolean =
        (style == "LINEAR" || style == "LIQUID") && tintPercent > 0

    fun shouldDrawNoise(amplitude: Int): Boolean = amplitude > 0

    fun shouldDrawSurfaceAlpha(percent: Int): Boolean = percent > 0

    fun surfaceAlphaFraction(percent: Int): Float = percent.coerceIn(0, 100) / 100f

    fun shouldDrawDropShadow(percent: Int): Boolean = percent > 0

    fun shadowStrengthFraction(percent: Int): Float = percent.coerceIn(0, 100) / 100f

    fun shadowOffsetY(stripAtTop: Boolean): Int = if (stripAtTop) -SHADOW_OFFSET_Y_PX else SHADOW_OFFSET_Y_PX

    fun paint(
        g: Graphics2D,
        shape: Shape,
        highlightStrength: Int,
        gradientStyle: String,
        gradientTintPercent: Int,
        gradientAngleDeg: Int,
        gradientEndArgb: Int,
        baseColor: Color,
        noiseAmplitude: Int,
        quality: String,

        shapeCornerRadius: Double = -1.0,

        shapeCornerRadiusBottom: Double = Double.NaN,

        flowPaletteArgb: List<Int> = emptyList(),

        flowPaletteShares: FloatArray? = null,

        flowPhase: Float = 0f,

        shapeRoundness: Double = GlassSdf.DEFAULT_ROUNDNESS,

        liquidGlarePercent: Int = 60,

        liquidDepthEffect: Boolean = true,

        liquidSurfaceAlphaPercent: Int = 0,

        glassAnimating: Boolean = DEFAULT_GLASS_ANIMATING,

        surfaceOpacityPercent: Int = 100,

        surfaceSuppressed: Boolean = false,
    ) {
        if (surfaceSuppressed) return

        if (quality == "OFF") return
        val b = shape.bounds2D
        if (!(b.width > 0.0) || !(b.height > 0.0)) return
        val oldPaint = g.paint
        val oldComposite = g.composite
        try {
            if (shouldDrawHighlight(highlightStrength)) {
                val hl = highlightPaint(b.x, b.y, b.height, highlightStrength)
                if (hl != null) {
                    g.paint = hl
                    g.fill(shape)
                }
            }

            if (gradientStyle == "FLOW" || gradientStyle == "CAMO") {
                val brush = if (gradientStyle == "CAMO") {

                    camoTexturePaint(
                        b.x, b.y, b.width, b.height,
                        islandHeightPx = b.height.toInt(),
                        paletteArgb = flowPaletteArgb,
                        alpha = baseColor.alpha,

                        shares = flowPaletteShares,

                        phase = flowPhase,
                    )
                } else {
                    flowGradientPaint(
                        b.x, b.y, b.width, b.height,
                        gradientAngleDeg,
                        flowPaletteArgb,
                        baseColor.alpha,

                        shares = flowPaletteShares,

                        phase = flowPhase,
                    )
                } ?: if (shouldDrawGradient("LINEAR", gradientTintPercent)) {

                    gradientPaint(
                        b.x, b.y, b.width, b.height,
                        gradientAngleDeg, gradientTintPercent, baseColor, gradientEndArgb,
                    )
                } else {
                    null
                }
                if (brush != null) {
                    g.paint = brush
                    g.fill(shape)
                }
            }
            if (shouldDrawGradient(gradientStyle, gradientTintPercent)) {

                if (gradientStyle == "LIQUID") {
                    val wPx = Math.ceil(b.width).toInt()
                    val hPx = Math.ceil(b.height).toInt()

                    val cr = if (shapeCornerRadius >= 0) shapeCornerRadius
                             else minOf(b.width, b.height) * 0.5 * 0.4

                    val crBottom = if (shapeCornerRadiusBottom.isNaN()) cr
                                   else if (shapeCornerRadiusBottom >= 0) shapeCornerRadiusBottom
                                   else minOf(b.width, b.height) * 0.5 * 0.4

                    val img = liquidGlassImageAsync(
                        wPx, hPx, cr, shapeRoundness,
                        gradientTintPercent, baseColor, gradientEndArgb, gradientAngleDeg.toDouble(),
                        liquidGlarePercent, liquidDepthEffect,

                        animating = glassAnimating,

                        cornerRadiusBottom = crBottom,

                        surfaceOpacityPercent = surfaceOpacityPercent,
                    )

                    val bakedImg = img ?: liquidGlassImage(
                        wPx, hPx, cr, shapeRoundness,
                        gradientTintPercent, baseColor, gradientEndArgb,
                        gradientAngleDeg.toDouble(),
                        liquidGlarePercent, liquidDepthEffect,

                        animating = glassAnimating,
                        cornerRadiusBottom = crBottom,
                        surfaceOpacityPercent = surfaceOpacityPercent,
                    )
                    if (bakedImg != null) {

                        g.drawImage(bakedImg, b.x.toInt(), b.y.toInt(), null)
                    } else {

                        g.paint = liquidPaint(b.x, b.y, b.width, b.height, gradientTintPercent, baseColor, gradientEndArgb)
                        g.fill(shape)
                    }
                } else {
                    g.paint = gradientPaint(
                        b.x, b.y, b.width, b.height,
                        gradientAngleDeg, gradientTintPercent, baseColor, gradientEndArgb,
                    )
                    g.fill(shape)
                }
            }

            if (shouldDrawSurfaceAlpha(liquidSurfaceAlphaPercent)) {
                drawSurfaceAlpha(g, shape, liquidSurfaceAlphaPercent)
            }
            if (shouldDrawNoise(noiseAmplitude)) {
                val a = noiseAmplitude.coerceIn(0, MAX_NOISE_AMPLITUDE)
                g.paint = noisePaint
                g.composite = noiseComposites[a]
                g.fill(shape)
            }
        } finally {
            g.paint = oldPaint
            g.composite = oldComposite
        }
    }

    fun drawSurfaceAlpha(g: Graphics2D, shape: Shape, percent: Int) {
        val oldPaint = g.paint
        val oldComposite = g.composite
        try {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, surfaceAlphaFraction(percent))
            g.paint = Color(255, 255, 255)
            g.fill(shape)
        } finally {
            g.paint = oldPaint
            g.composite = oldComposite
        }
    }

    fun drawDropShadow(
        g: Graphics2D,
        x: Double, y: Double, w: Double, h: Double,
        cornerRadius: Double,
        percent: Int,
        stripAtTop: Boolean,
    ) {
        val wPx = Math.ceil(w).toInt()
        val hPx = Math.ceil(h).toInt()
        if (wPx <= 0 || hPx <= 0) return
        val bmp = shadowBitmap(wPx, hPx, cornerRadius, percent) ?: return

        val fullW = wPx + SHADOW_PAD_PX * 2
        val fullH = hPx + SHADOW_PAD_PX * 2
        val dx = (x - SHADOW_PAD_PX + SHADOW_OFFSET_X_PX).toInt()
        val dy = (y - SHADOW_PAD_PX + shadowOffsetY(stripAtTop)).toInt()
        val oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(bmp, dx, dy, fullW, fullH, null)
        } finally {

            if (oldInterp != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp)
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            }
        }
    }

    private fun shadowBitmap(w: Int, h: Int, cornerRadius: Double, percent: Int): BufferedImage? {
        val strength = shadowStrengthFraction(percent).toDouble()
        val key = "$w|$h|$cornerRadius|$percent"
        cachedShadow?.let { if (cachedShadowKey == key) return it }
        if (strength <= 0.0) return null

        val s = SHADOW_SHRINK
        val pad = SHADOW_PAD_PX.toDouble() / s
        val bw = (w + SHADOW_PAD_PX * 2) / s
        val bh = (h + SHADOW_PAD_PX * 2) / s
        if (bw <= 0 || bh <= 0) return null

        val arc = (if (cornerRadius > 0) cornerRadius else minOf(w, h) * 0.5 * 0.4) * 2.0 / s
        val img = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (i in SHADOW_LAYERS downTo 0) {
                val grow = i * SHADOW_GROW_STEP / s
                val a = (strength * SHADOW_MAX_ALPHA * Math.pow(1.0 - i / SHADOW_LAYERS.toDouble(), 1.6)).toInt()
                if (a <= 0) continue
                g.color = Color(0, 0, 0, a)
                g.fill(
                    RoundRectangle2D.Double(
                        pad - grow, pad - grow,
                        w / s.toDouble() + grow * 2, h / s.toDouble() + grow * 2,
                        arc, arc,
                    ),
                )
            }
        } finally {
            g.dispose()
        }
        cachedShadow = img
        cachedShadowKey = key
        return img
    }

    private fun highlightPaint(x: Double, y: Double, h: Double, strength: Int): LinearGradientPaint? {
        val span = h * HIGHLIGHT_SPAN_RATIO
        if (!(span > 0.0)) return null
        val alpha = (strength.coerceIn(0, 100) / 100f * HIGHLIGHT_MAX_ALPHA).toInt()
            .coerceIn(1, 255)
        val from = Color(255, 255, 255, alpha)
        val to = Color(255, 255, 255, 0)
        return LinearGradientPaint(
            x.toFloat(), y.toFloat(),
            x.toFloat(), (y + span).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(from, to),
        )
    }

    fun composeGlassTile(
        srcBackdrop: BufferedImage,
        cornerRadius: Double,
        cornerRadiusBottom: Double,
        roundness: Double,
        glarePercent: Int,
        depthEffect: Boolean,
        surfaceAlphaPercent: Int = 10,

        refHeightPx: Double? = null,
    ): BufferedImage? = liquidGlassImage(
        w = srcBackdrop.width,
        h = srcBackdrop.height,
        cornerRadius = cornerRadius,
        roundness = roundness,

        tintPercent = 0,
        baseColor = Color.BLACK,
        endArgb = 0,
        glareAngleDeg = 0.0,
        glarePercent = glarePercent,
        depthEffect = depthEffect,
        animating = false,
        cornerRadiusBottom = cornerRadiusBottom,

        surfaceOpacityPercent = 100,
        backdrop = srcBackdrop,
        refHOverride = refHeightPx,
        surfaceAlphaPercent = surfaceAlphaPercent,
    )

    private class SdfField(
        val key: String,
        val bw: Int,
        val bh: Int,
        val m: DoubleArray,
        val nx: DoubleArray,
        val ny: DoubleArray,
    )

    @Volatile
    private var sdfField: SdfField? = null

    private fun ensureSdfField(
        w: Int, h: Int, bw: Int, bh: Int, shrink: Int,
        cornerRadius: Double, cornerRadiusBottom: Double, roundness: Double,
        eps: Double,
    ): SdfField {
        val key = "$w|$h|$bw|$bh|$shrink|$cornerRadius|$cornerRadiusBottom|$roundness"
        val cur = sdfField
        if (cur != null && cur.key == key) return cur
        val n = bw * bh
        val m = DoubleArray(n)
        val nx = DoubleArray(n)
        val ny = DoubleArray(n)
        val grad = DoubleArray(2)
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                val fx = bx * shrink + shrink * 0.5
                val fy = by * shrink + shrink * 0.5
                val i = by * bw + bx
                val mm = GlassSdf.of(fx, fy, w, h, cornerRadius, roundness, cornerRadiusBottom)
                m[i] = mm

                if (mm <= 0.5) {
                    GlassSdf.gradient(
                        fx, fy, w, h, cornerRadius, roundness, grad, eps, cornerRadiusBottom,
                    )
                    val gl = Math.hypot(grad[0], grad[1])
                    nx[i] = if (gl > 1e-9) grad[0] / gl else 0.0
                    ny[i] = if (gl > 1e-9) grad[1] / gl else 0.0
                }
            }
        }
        val f = SdfField(key, bw, bh, m, nx, ny)
        sdfField = f
        return f
    }

    private fun liquidGlassImage(
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double,
        tintPercent: Int,
        baseColor: Color,
        endArgb: Int,
        glareAngleDeg: Double,

        glarePercent: Int = 60,

        depthEffect: Boolean = true,

        animating: Boolean = false,

        shrinkOverride: Int? = null,

        cornerRadiusBottom: Double = Double.NaN,

        surfaceOpacityPercent: Int = 100,

        backdrop: BufferedImage? = null,

        refHOverride: Double? = null,

        surfaceAlphaPercent: Int = 0,
    ): BufferedImage? {
        if (w <= 0 || h <= 0) return null

        val shrink = shrinkOverride ?: if (animating) GLASS_ANIM_SHRINK else 1

        val key = "$w|$h|$cornerRadius|$cornerRadiusBottom|$roundness|$tintPercent|${baseColor.rgb}|$endArgb" +
            "|$glareAngleDeg|$glarePercent|$depthEffect|s$shrink|a$surfaceOpacityPercent"

        val bdPx: IntArray? = backdrop
            ?.takeIf { it.width == w && it.height == h }
            ?.let { bd -> IntArray(w * h).also { bd.getRGB(0, 0, w, h, it, 0, w) } }
        val opaqueTile = bdPx != null
        val glassAlpha = if (opaqueTile) 1.0
                         else (surfaceOpacityPercent.coerceIn(0, 100) / 100.0) * 0.5

        if (!opaqueTile) cachedLiquid?.let { if (cachedLiquidKey == key) return it }

        if (gpuEnabled && !gpuDisabled) {
            val gpuImg = try {
                liquidGlassImageOnGpu(
                    w, h, cornerRadius, roundness, tintPercent, baseColor, endArgb,
                    glarePercent, depthEffect, cornerRadiusBottom,
                )
            } catch (t: Throwable) {

                null
            }
            if (gpuImg != null) {
                gpuFailStreak = 0
                cachedLiquid = gpuImg
                cachedLiquidKey = key
                cachedLiquidShrunk = false
                return gpuImg
            }

            if (++gpuFailStreak >= GPU_FAILURE_LIMIT) gpuDisabled = true

        }

        val fresnelRange = 64.0
        val fresnelHardness = 0.20

        val fresnelFactor = if (depthEffect) 0.20 else 0.0
        val glareRange = 64.0
        val glareHardness = 0.20

        val glareFactor = (glarePercent.coerceIn(0, 100)) / 100.0
        val glareConvergence = 0.50
        val glareOpposite = 0.80

        val t = (tintPercent.coerceIn(0, 100)) / 100.0
        val end = Color((endArgb ushr 16) and 0xFF, (endArgb ushr 8) and 0xFF, endArgb and 0xFF)
        val br = baseColor.red; val bg = baseColor.green; val bb = baseColor.blue

        val cr0 = br + (end.red - br) * t
        val cg0 = bg + (end.green - bg) * t
        val cb0 = bb + (end.blue - bb) * t

        val bw = (w + shrink - 1) / shrink
        val bh = (h + shrink - 1) / shrink
        val img = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB)
        val px = IntArray(bw * bh)
        val eps = GlassSdf.NORMAL_EPS_PX

        val kFr = Math.pow(500.0 / fresnelRange, 2.0) / 1500.0
        val kGl = Math.pow(500.0 / glareRange, 2.0) / 1500.0
        val glareBase = Math.toRadians(glareAngleDeg)

        val LIGHT_NX = -0.722
        val LIGHT_NY = 0.692

        val field = ensureSdfField(
            w, h, bw, bh, shrink, cornerRadius, cornerRadiusBottom, roundness, eps,
        )
        fun renderRows(y0: Int, y1: Int) {
            for (by in y0 until y1) {
                for (bx in 0 until bw) {

                    val fx = bx * shrink + shrink * 0.5; val fy = by * shrink + shrink * 0.5

                    val fidx = by * bw + bx
                    val m = field.m[fidx]
                    if (m > 0.5) {

                        if (opaqueTile) {
                            val bdIdxOut = (by * shrink) * w + (bx * shrink)
                            px[fidx] = bdPx!![bdIdxOut] or (0xFF shl 24)
                        }
                        continue
                    }
                    val aa = (0.5 - m).coerceIn(0.0, 1.0)
                    val nx = field.nx[fidx]
                    val ny = field.ny[fidx]
                    val refH = refHOverride ?: ((if (w < h) w else h) * 0.08)
                    val sdEdge = -m

                    val rim = Math.exp(-sdEdge / (refH * 0.30).coerceAtLeast(1.0))
                    val face = (nx * LIGHT_NX + ny * LIGHT_NY).coerceIn(-1.0, 1.0)
                    var fr = rim * (0.35 + 0.65 * maxOf(face, 0.0)) * glareFactor * 0.55

                    if (!depthEffect) fr *= 0.60
                    fr = fr.coerceIn(0.0, 1.0)

                    val sd = sdEdge
                    val innerShadow = Math.exp(m / (refH * 0.42).coerceAtLeast(1.0))
                    val dimFactor = 1.0 - 0.10 * (innerShadow * 0.55)

                    val bdIdx = (by * shrink) * w + (bx * shrink)
                    val bd = if (opaqueTile) bdPx!![bdIdx] else 0
                    var r = (if (opaqueTile) (bd ushr 16 and 0xFF).toDouble() else cr0) * dimFactor
                    var g2 = (if (opaqueTile) (bd ushr 8 and 0xFF).toDouble() else cg0) * dimFactor
                    var b2 = (if (opaqueTile) (bd and 0xFF).toDouble() else cb0) * dimFactor
                    r += (255 - r) * fr; g2 += (255 - g2) * fr; b2 += (255 - b2) * fr

                    val hairT = (1.0 - (sd / 1.6).coerceIn(0.0, 1.0))
                    val hair = hairT * (glarePercent / 100.0) * 0.30
                    if (hair > 0.0) {
                        r += (255 - r) * hair; g2 += (255 - g2) * hair; b2 += (255 - b2) * hair
                    }

                    px[by * bw + bx] = ((aa * glassAlpha * 255).toInt() shl 24) or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g2.toInt().coerceIn(0, 255) shl 8) or
                        b2.toInt().coerceIn(0, 255)
                }
            }
        }

        val shardN = glassShardCount
        if (shardN <= 1 || bh <= 1) {
            renderRows(0, bh)
        } else {
            val chunk = (bh + shardN - 1) / shardN
            val futures = ArrayList<java.util.concurrent.Future<*>>(shardN - 1)
            var degraded = false
            try {
                for (i in 1 until shardN) {
                    val y0 = i * chunk
                    if (y0 >= bh) break
                    val y1 = if (y0 + chunk < bh) y0 + chunk else bh
                    futures.add(glassShardPool.submit(java.lang.Runnable { renderRows(y0, y1) }))
                }
                renderRows(0, if (chunk < bh) chunk else bh)
            } catch (t: Throwable) {
                degraded = true
            }

            for (f in futures) {
                try { f.get() } catch (t: Throwable) { degraded = true }
            }
            if (degraded) renderRows(0, bh)
        }
        img.setRGB(0, 0, bw, bh, px, 0, bw)
        cachedLiquid = img
        cachedLiquidKey = key

        cachedLiquidShrunk = shrink > 1
        return img
    }

    private fun liquidGlassImageOnGpu(
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double,
        tintPercent: Int,
        baseColor: Color,
        endArgb: Int,
        glarePercent: Int,
        depthEffect: Boolean,
        cornerRadiusBottom: Double,
    ): BufferedImage? {
        val backdrop = solidTintBackdrop(w, h, tintPercent, baseColor, endArgb)
        val params = LiquidGlassShader.Params(

            surfaceAlphaPercent = 0,
            shadowPercent = 0,
            glarePercent = glarePercent.coerceIn(0, 100),
            depthEffect = depthEffect,
        )

        val uniforms = LiquidGlassShader.uniforms(backdrop, GPU_DPR, cornerRadius, params)
        val img = glOffscreen.render(
            w, h,
            LiquidGlassShader.fragmentShaderSource(),
            uniforms,
            LiquidGlassShader.fragmentShaderFallback(),
        ) ?: return null

        maskAlphaToShape(img, w, h, cornerRadius, roundness, cornerRadiusBottom)
        return img
    }

    private fun solidTintBackdrop(
        w: Int, h: Int, tintPercent: Int, baseColor: Color, endArgb: Int,
    ): BufferedImage {
        val t = tintPercent.coerceIn(0, 100) / 100.0
        val er = (endArgb ushr 16) and 0xFF
        val eg = (endArgb ushr 8) and 0xFF
        val eb = endArgb and 0xFF
        val r = (baseColor.red + (er - baseColor.red) * t).toInt().coerceIn(0, 255)
        val g = (baseColor.green + (eg - baseColor.green) * t).toInt().coerceIn(0, 255)
        val b = (baseColor.blue + (eb - baseColor.blue) * t).toInt().coerceIn(0, 255)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            g2.color = Color(r, g, b)
            g2.fillRect(0, 0, w, h)
        } finally {
            g2.dispose()
        }
        return img
    }

    private fun maskAlphaToShape(
        img: BufferedImage, w: Int, h: Int,
        cornerRadius: Double, roundness: Double, cornerRadiusBottom: Double,
    ) {
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        for (y in 0 until h) {
            val fy = y + 0.5
            val row = y * w
            for (x in 0 until w) {
                val m = GlassSdf.of(x + 0.5, fy, w, h, cornerRadius, roundness, cornerRadiusBottom)
                val a = ((0.5 - m).coerceIn(0.0, 1.0) * 255.0).toInt()
                val i = row + x
                px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w)
    }



    private fun liquidPaint(
        x: Double, y: Double, w: Double, h: Double,
        tintPercent: Int, baseColor: Color, endArgb: Int,
    ): RadialGradientPaint {
        val cx = (x + w / 2.0).toFloat()
        val cy = (y + h / 2.0).toFloat()

        val radius = (Math.hypot(w, h) / 2.0).toFloat().coerceAtLeast(1f)
        val t = (tintPercent.coerceIn(0, 100)) / 100f
        val end = Color((endArgb ushr 16) and 0xFF, (endArgb ushr 8) and 0xFF, endArgb and 0xFF, baseColor.alpha)

        fun mix(f: Float): Color = Color(
            (baseColor.red + (end.red - baseColor.red) * f).toInt().coerceIn(0, 255),
            (baseColor.green + (end.green - baseColor.green) * f).toInt().coerceIn(0, 255),
            (baseColor.blue + (end.blue - baseColor.blue) * f).toInt().coerceIn(0, 255),
            baseColor.alpha,
        )

        val colors = arrayOf(
            baseColor,
            mix(0.55f * t),
            mix(0.20f * t),
            mix(1.0f * t),
        )
        return RadialGradientPaint(
            java.awt.geom.Point2D.Float(cx, cy),
            radius,
            java.awt.geom.Point2D.Float(cx, cy),
            floatArrayOf(0f, 0.55f, 0.88f, 1f),
            colors,
            java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
    }

    private fun gradientPaint(
        x: Double, y: Double, w: Double, h: Double,
        angleDeg: Int, tintPercent: Int, baseColor: Color, endArgb: Int,
    ): LinearGradientPaint {
        val key = "$x|$y|$w|$h|$angleDeg|$tintPercent|${baseColor.rgb}|$endArgb"
        cachedGradient?.let { if (cachedKey == key) return it }

        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = Math.cos(rad)
        val dy = Math.sin(rad)
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val l = Math.abs(w * dx) + Math.abs(h * dy)
        val half = l / 2.0
        val from = Color(baseColor.red, baseColor.green, baseColor.blue, baseColor.alpha)
        val end = Color(
            (endArgb ushr 16) and 0xFF,
            (endArgb ushr 8) and 0xFF,
            endArgb and 0xFF,
            baseColor.alpha,
        )
        val t = (tintPercent.coerceIn(0, 100)) / 100f
        val to = Color(
            (from.red + (end.red - from.red) * t).toInt().coerceIn(0, 255),
            (from.green + (end.green - from.green) * t).toInt().coerceIn(0, 255),
            (from.blue + (end.blue - from.blue) * t).toInt().coerceIn(0, 255),
            from.alpha,
        )
        val p = LinearGradientPaint(
            (cx - half * dx).toFloat(), (cy - half * dy).toFloat(),
            (cx + half * dx).toFloat(), (cy + half * dy).toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(from, to),
        )
        cachedGradient = p
        cachedKey = key
        return p
    }
}
