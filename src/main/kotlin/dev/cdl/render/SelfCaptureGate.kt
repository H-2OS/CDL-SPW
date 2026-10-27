// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

enum class SelfCaptureLevel {

    FULL,

    SUSPECT,

    FULL_WARNED,

    PURE_COLOR,
}

data class SelfCaptureGateResult(
    val accepted: Boolean,
    val level: SelfCaptureLevel,
    val streak: Int,
    val reading: SelfCaptureReading,
)

class SelfCaptureGate(
    private val k: Int = SelfCaptureRules.K,
    private val k2: Int = SelfCaptureRules.K2,
) {

    init {

        require(k >= 1) { "K 必须 >= 1（连续 1 帧就告警是允许的），实际 $k" }
        require(k2 > k) { "K2 必须 > K（否则告警永不亮），实际 K2=$k2 K=$k" }
    }

    var streak: Int = 0
        private set

    var level: SelfCaptureLevel = SelfCaptureLevel.FULL
        private set

    var warned: Boolean = false
        private set

    private var pureColorAnnounced: Boolean = false

    fun onReading(reading: SelfCaptureReading): SelfCaptureGateResult {

        if (level == SelfCaptureLevel.PURE_COLOR) {
            return SelfCaptureGateResult(
                accepted = false,
                level = SelfCaptureLevel.PURE_COLOR,
                streak = streak,
                reading = reading,
            )
        }

        if (reading.decision == SelfCaptureDecision.OK) {

            streak = 0
            warned = false
            level = SelfCaptureLevel.FULL
            return SelfCaptureGateResult(
                accepted = true,
                level = level,
                streak = streak,
                reading = reading,
            )
        }

        streak += 1
        level = when {
            streak >= k2 -> SelfCaptureLevel.PURE_COLOR
            streak >= k -> SelfCaptureLevel.FULL_WARNED
            else -> SelfCaptureLevel.SUSPECT
        }
        if (level != SelfCaptureLevel.FULL) warned = streak >= k
        return SelfCaptureGateResult(
            accepted = false,
            level = level,
            streak = streak,
            reading = reading,
        )
    }

    fun shouldWarn(result: SelfCaptureGateResult): Boolean =
        result.streak == k && result.level == SelfCaptureLevel.FULL_WARNED

    fun shouldFallbackToPureColor(result: SelfCaptureGateResult): Boolean =
        result.streak == k2 && result.level == SelfCaptureLevel.PURE_COLOR &&
            !pureColorAnnounced && markPureColorAnnounced()

    private fun markPureColorAnnounced(): Boolean {
        if (pureColorAnnounced) return false
        pureColorAnnounced = true
        return true
    }

    fun reset() {
        streak = 0
        level = SelfCaptureLevel.FULL
        warned = false
        pureColorAnnounced = false
    }
}

fun mustStartWithPureColor(ownPixels: Any?): Boolean = ownPixels == null

fun coldStartReading(): SelfCaptureReading = SelfCaptureReading(
    decision = SelfCaptureDecision.UNKNOWN,
    s1 = null,
    s1hp = null,
    reason = "cold-start",
)
