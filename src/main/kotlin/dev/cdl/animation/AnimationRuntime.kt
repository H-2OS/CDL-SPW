// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.animation

import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.pow

enum class Easing {

    LINEAR,

    EASE_OUT,

    EASE_IN_OUT,

    OVERSHOOT,
    ;

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x

            EASE_OUT -> 1f - (1f - x).pow(3)

            EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

            OVERSHOOT -> {
                val c1 = 1.70158f
                val c3 = c1 + 1f
                1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
            }
        }
    }
}

enum class MotionQuality {

    STANDARD,

    HIGH,

    ULTRA,
    ;

    val advancedTransitions: Boolean get() = this != STANDARD

    val highCostEffects: Boolean get() = this == ULTRA

    companion object {

        val DEFAULT = HIGH

        fun parse(key: String?): MotionQuality = when {
            key == null -> DEFAULT
            key.equals("STANDARD", ignoreCase = true) -> STANDARD
            key.equals("HIGH", ignoreCase = true) -> HIGH
            key.equals("ULTRA", ignoreCase = true) -> ULTRA
            else -> DEFAULT
        }
    }
}

class Transition(
    private val from: Float,
    private val to: Float,

    val durationMs: Long,
    val easing: Easing,
    private val startNanos: Long,

    private val onUpdate: (Float) -> Unit,
    private val onDone: (() -> Unit)? = null,
) {

    private fun rawProgress(nowNanos: Long): Float =
        if (durationMs <= 0L) 1f
        else ((nowNanos - startNanos).toDouble() / (durationMs * 1_000_000.0)).toFloat().coerceIn(0f, 1f)

    fun advance(nowNanos: Long): Boolean {
        val p = rawProgress(nowNanos)
        val v = from + (to - from) * easing.apply(p)
        onUpdate(v)
        if (p >= 1f) {

            onUpdate(to)
            onDone?.invoke()
            return true
        }
        return false
    }
}

class AnimationRuntime(

    private val clock: () -> Long = System::nanoTime,
) {

    private val running = LinkedHashMap<String, Transition>(4)

    var frameIntervalMs: Long = 33L
        private set

    var enabled: Boolean = true
        private set

    var highCostEffects: Boolean = false
        private set

    val effectiveHighCostEffects: Boolean get() = enabled && highCostEffects

    val effectiveAdvancedTransitions: Boolean get() = enabled && quality.advancedTransitions

    var quality: MotionQuality = MotionQuality.DEFAULT
        private set

    val advancedTransitions: Boolean get() = quality.advancedTransitions

    private var durationScalePercent: Int = 100

    fun applySettings(
        enabled: Boolean,
        quality: String,
        durationScalePercent: Int,
        frameRate: String = FRAME_RATE_AUTO,
        quantumMs: Double = Double.NaN,
    ) {
        this.enabled = enabled
        this.frameIntervalMs = frameIntervalFor(frameRate, quantumMs)
        this.durationScalePercent = durationScalePercent.coerceIn(
            MIN_DURATION_SCALE_PERCENT, MAX_DURATION_SCALE_PERCENT,
        )

        this.quality = MotionQuality.parse(quality)
        this.highCostEffects = this.quality.highCostEffects

        if (!enabled) finishAll()
    }

    private fun frameIntervalFor(frameRate: String, quantumMs: Double = Double.NaN): Long {

        val refresh = detectRefreshRateHz()
        val hz = if (frameRate == FRAME_RATE_AUTO) {
            refresh
        } else {
            frameRate.trim().toIntOrNull() ?: 0
        }
        val q = if (quantumMs.isNaN()) detectTimerQuantumMs() else quantumMs
        return frameIntervalMsForRefresh(hz, refresh, q)
    }

    private fun detectRefreshRateHz(): Int = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .firstOrNull()
            ?.displayMode
            ?.refreshRate
            ?: 0
    }.getOrDefault(0)

    fun startIn(
        slot: String,
        from: Float,
        to: Float,
        durationMs: Long,
        easing: Easing,
        onUpdate: (Float) -> Unit,
        onDone: (() -> Unit)? = null,
    ): Boolean {

        running.remove(slot)

        val scaled = scaledTransitionMs(durationMs, durationScalePercent)
        if (!enabled || scaled <= 0L || nearlyEqual(from, to)) {
            onUpdate(to)
            onDone?.invoke()
            return false
        }
        running[slot] = Transition(
            from = from,
            to = to,
            durationMs = scaled,
            easing = easing,
            startNanos = clock(),
            onUpdate = onUpdate,
            onDone = onDone,
        )
        return true
    }

    fun tick(nowNanos: Long = clock()): Boolean {
        if (running.isEmpty()) return false
        val snapshot = running.entries.toList()
        for ((slot, t) in snapshot) {
            if (t.advance(nowNanos)) {

                if (running[slot] === t) running.remove(slot)
            }
        }
        return running.isNotEmpty()
    }

    fun hasRunning(): Boolean = running.isNotEmpty()

    fun isRunning(slot: String): Boolean = running.containsKey(slot)

    private fun finishAll() {

        val snapshot = running.values.toList()
        running.clear()
        snapshot.forEach { it.advance(Long.MAX_VALUE) }
    }

    fun clear() = running.clear()
}

object Slots {

    const val ISLAND_FADE = "island.fade"

    const val COVER_CROSSFADE = "cover.crossfade"

    const val LYRIC_EXIT = "lyric.exit"

    const val LYRIC_ENTER = "lyric.enter"
}

enum class MotionStyle {

    FADE,

    SLIDE,

    POP,
    ;

    fun offsetYAt(t: Float, distance: Float): Float = when (this) {
        FADE -> 0f
        SLIDE -> distance * (1f - t)
        POP -> 0f
    }

    fun scaleAt(t: Float): Float = when (this) {
        FADE -> 1f
        SLIDE -> 1f
        POP -> POP_SCALE_FROM + (1f - POP_SCALE_FROM) * t
    }

    companion object {

        const val POP_SCALE_FROM = 0.94f

        val DEFAULT = SLIDE

        fun parse(key: String?): MotionStyle =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

data class MotionSpec(
    val slot: String? = null,
    val durationMs: Long,
    val easing: Easing,
    val style: MotionStyle,
    val followsGlobalStyle: Boolean = true,
    val distancePx: Float = 0f,
    val distanceFromLayout: Boolean = false,

    val scaleFrom: Float = 1f,
)

internal const val FRAME_RATE_AUTO = "AUTO"

internal const val DEFAULT_FRAME_HZ = 60

internal const val MIN_FRAME_HZ = 30

internal const val MAX_FRAME_HZ = 240

const val MAX_TRANSITION_MS = 450L

const val MIN_DURATION_SCALE_PERCENT = 50

const val MAX_DURATION_SCALE_PERCENT = 1000

const val LEGACY_DURATION_SCALE_PERCENT = 200

fun frameIntervalMsFor(hz: Int, quantumMs: Double): Long {
    val effective = if (hz in MIN_FRAME_HZ..MAX_FRAME_HZ) hz else DEFAULT_FRAME_HZ
    return snapToQuantum(1000.0 / effective, quantumMs)
}

private fun snapToQuantum(targetMs: Double, quantumMs: Double): Long {
    val naive = Math.floor(targetMs).toLong().coerceAtLeast(1L)
    val q = if (quantumMs.isFinite() && quantumMs >= 1.0) quantumMs else 1.0

    if (targetMs < q) return naive
    val k = Math.floor(targetMs / q).toInt().coerceAtLeast(1)
    val snapped = Math.floor(k * q).toLong().coerceAtLeast(1L)
    return minOf(naive, snapped)
}

const val FRAME_DELIVERY_OVERHEAD_MS: Double = 1.0

fun frameIntervalMsForRefresh(
    hz: Int,
    refreshHz: Int,
    quantumMs: Double,
    overheadMs: Double = FRAME_DELIVERY_OVERHEAD_MS,
): Long {
    val effective = if (hz in MIN_FRAME_HZ..MAX_FRAME_HZ) hz else DEFAULT_FRAME_HZ

    if (refreshHz <= 0) return frameIntervalMsFor(effective, quantumMs)

    val targetMs = 1000.0 / effective
    val refreshMs = 1000.0 / refreshHz

    val k = Math.max(1, Math.ceil(targetMs / refreshMs).toInt())
    val idealMs = k * refreshMs

    val wantMs = (idealMs - overheadMs).coerceAtLeast(1.0)
    return snapToQuantum(wantMs, quantumMs).coerceAtLeast(1L)
}

fun detectTimerQuantumMs(): Double {
    TimerQuantumCalibration.ensureMeasured()
    return TimerQuantumCalibration.cached
}

private object TimerQuantumCalibration {

    private const val BUDGET_MS = 100L

    private const val REQUEST_MS = 1L

    private const val MIN_PLAUSIBLE_QUANTUM_MS = 2.0

    @Volatile
    private var value: Double = 0.0

    val cached: Double get() = if (value > 0.0) value else FALLBACK_QUANTUM_MS

    fun ensureMeasured() {
        if (value > 0.0) return
        value = measure()
    }

    private fun measure(): Double = runCatching {
        val deadline = System.nanoTime() + BUDGET_MS * 1_000_000L
        val gaps = ArrayList<Double>(64)
        var prev = 0L
        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(REQUEST_MS * 1_000_000L)
            val now = System.nanoTime()
            if (prev != 0L) {
                val gap = (now - prev) / 1_000_000.0

                if (gap >= 0.05) gaps += gap
            }
            prev = now
        }
        if (gaps.size < 3) return@runCatching FALLBACK_QUANTUM_MS
        gaps.sort()
        val median = gaps[gaps.size / 2]
        if (median.isFinite() && median >= MIN_PLAUSIBLE_QUANTUM_MS) median else FALLBACK_QUANTUM_MS
    }.getOrDefault(FALLBACK_QUANTUM_MS)
}

const val FALLBACK_QUANTUM_MS = 1.0

fun scaledTransitionMs(baseMs: Long, scalePercent: Int): Long {
    val percent = scalePercent.coerceIn(MIN_DURATION_SCALE_PERCENT, MAX_DURATION_SCALE_PERCENT)
    val raw = (baseMs * percent) / 100L
    return if (percent <= LEGACY_DURATION_SCALE_PERCENT) raw.coerceAtMost(MAX_TRANSITION_MS) else raw
}

object Motions {

    val ISLAND_FADE = MotionSpec(
        slot = Slots.ISLAND_FADE,
        durationMs = 220L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.FADE,
        followsGlobalStyle = false,

        scaleFrom = 0.97f,
    )

    val COVER_CROSSFADE = MotionSpec(
        slot = Slots.COVER_CROSSFADE,
        durationMs = 260L,
        easing = Easing.EASE_IN_OUT,
        style = MotionStyle.FADE,
        followsGlobalStyle = false,
    )

    val LYRIC_EXIT = MotionSpec(
        slot = Slots.LYRIC_EXIT,
        durationMs = 180L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.SLIDE,
        followsGlobalStyle = true,
        distanceFromLayout = true,
    )

    val LYRIC_ENTER = MotionSpec(
        slot = Slots.LYRIC_ENTER,
        durationMs = 180L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.SLIDE,
        followsGlobalStyle = true,
        distanceFromLayout = true,
    )

    val MENU_FADE = MotionSpec(

        slot = null,
        durationMs = 140L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.FADE,
        followsGlobalStyle = false,
    )

    val CONTROLS_APPEAR = MotionSpec(

        slot = null,

        durationMs = 180L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.SLIDE,
        followsGlobalStyle = true,

        distancePx = 8f,
    )

    val ALL: List<MotionSpec> = listOf(ISLAND_FADE, COVER_CROSSFADE, LYRIC_EXIT, LYRIC_ENTER, MENU_FADE, CONTROLS_APPEAR)

    fun resolve(spec: MotionSpec, globalStyle: MotionStyle): MotionSpec =
        if (spec.followsGlobalStyle) spec.copy(style = globalStyle) else spec
}

fun transitionOf(
    from: Float,
    to: Float,
    durationMs: Long,
    easing: Easing,
    onUpdate: (Float) -> Unit,
    onDone: (() -> Unit)? = null,

    startNanos: Long = System.nanoTime(),
): Transition? {
    if (durationMs <= 0L || nearlyEqual(from, to)) {
        onUpdate(to)
        onDone?.invoke()
        return null
    }
    return Transition(
        from = from,
        to = to,
        durationMs = durationMs,
        easing = easing,
        startNanos = startNanos,
        onUpdate = onUpdate,
        onDone = onDone,
    )
}

internal fun nearlyEqual(a: Float, b: Float, eps: Float = 0.01f): Boolean = abs(a - b) < eps
