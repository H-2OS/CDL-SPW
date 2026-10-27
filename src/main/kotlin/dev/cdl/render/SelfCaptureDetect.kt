// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

object SelfCaptureRules {

    const val TAU1: Double = 0.75

    const val TAU1HP: Double = 0.70

    const val K: Int = 3

    const val K2: Int = 10

    const val RECOMMENDED_HZ: Int = 10

    const val HIGHPASS_RADIUS: Int = 8
}

enum class SelfCaptureDecision {

    SELF_CAPTURE,

    OK,

    UNKNOWN,
}

data class SelfCaptureReading(
    val decision: SelfCaptureDecision,
    val s1: Double?,
    val s1hp: Double?,
    val reason: String,
)

fun decideByScores(s1: Double, s1hp: Double): SelfCaptureDecision =
    if (s1 >= SelfCaptureRules.TAU1 || s1hp >= SelfCaptureRules.TAU1HP) {
        SelfCaptureDecision.SELF_CAPTURE
    } else {
        SelfCaptureDecision.OK
    }

fun luminanceOf(argb: Int): Double {
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b
}

fun pearsonOf(a: DoubleArray, b: DoubleArray): Double? {
    if (a.size != b.size || a.size < 2) return null
    val n = a.size
    var sa = 0.0
    var sb = 0.0
    for (i in 0 until n) {
        sa += a[i]
        sb += b[i]
    }
    val ma = sa / n
    val mb = sb / n
    var cov = 0.0
    var va = 0.0
    var vb = 0.0
    for (i in 0 until n) {
        val da = a[i] - ma
        val db = b[i] - mb
        cov += da * db
        va += da * da
        vb += db * db
    }
    return cov / kotlin.math.sqrt(va * vb + 1e-9)
}

fun highpassLuminance(pixels: IntArray, w: Int, h: Int, r: Int): DoubleArray {
    require(pixels.size >= w * h) { "pixels 长度 ${pixels.size} < w*h ${w * h}" }
    require(r >= 0) { "半径不得为负：$r" }
    val lum = DoubleArray(w * h)
    for (i in 0 until w * h) lum[i] = luminanceOf(pixels[i])
    if (r == 0) return lum

    val tmp = DoubleArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var sum = 0.0
            var count = 0
            var dx = -r
            while (dx <= r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                sum += lum[row + xx]
                count++
                dx++
            }
            tmp[row + x] = sum / count
        }
    }

    val mean = DoubleArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var sum = 0.0
            var count = 0
            var dy = -r
            while (dy <= r) {
                val yy = (y + dy).coerceIn(0, h - 1)
                sum += tmp[yy * w + x]
                count++
                dy++
            }
            mean[y * w + x] = sum / count
        }
    }

    for (i in 0 until w * h) lum[i] -= mean[i]
    return lum
}

fun detectSelfCapture(
    capturePixels: IntArray,
    ownPixels: IntArray,
    insideMask: BooleanArray,
    w: Int,
    h: Int,
    highpassRadius: Int = SelfCaptureRules.HIGHPASS_RADIUS,
): SelfCaptureReading {
    if (w <= 0 || h <= 0) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "bad-size")
    }
    val n = w * h
    if (capturePixels.size < n || ownPixels.size < n || insideMask.size < n) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "length-mismatch")
    }

    var count = 0
    for (i in 0 until n) if (insideMask[i]) count++
    if (count < 2) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "empty-mask")
    }
    val ca = DoubleArray(count)
    val oa = DoubleArray(count)
    var k = 0
    for (i in 0 until n) {
        if (insideMask[i]) {
            ca[k] = luminanceOf(capturePixels[i])
            oa[k] = luminanceOf(ownPixels[i])
            k++
        }
    }

    val s1 = pearsonOf(ca, oa)

    val hpCap = highpassLuminance(capturePixels, w, h, highpassRadius)
    val hpOwn = highpassLuminance(ownPixels, w, h, highpassRadius)
    val hca = DoubleArray(count)
    val hoa = DoubleArray(count)
    var m = 0
    for (i in 0 until n) {
        if (insideMask[i]) {
            hca[m] = hpCap[i]
            hoa[m] = hpOwn[i]
            m++
        }
    }
    val s1hp = pearsonOf(hca, hoa)

    if (s1 == null && s1hp == null) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, null, null, "non-finite")
    }
    if ((s1 != null && !s1.isFinite()) || (s1hp != null && !s1hp.isFinite())) {
        return SelfCaptureReading(SelfCaptureDecision.UNKNOWN, s1, s1hp, "non-finite")
    }

    val decision = when {
        s1 != null && s1hp != null -> decideByScores(s1, s1hp)
        s1 != null -> if (s1 >= SelfCaptureRules.TAU1) {
            SelfCaptureDecision.SELF_CAPTURE
        } else {
            SelfCaptureDecision.UNKNOWN
        }
        else -> if (s1hp!! >= SelfCaptureRules.TAU1HP) {
            SelfCaptureDecision.SELF_CAPTURE
        } else {
            SelfCaptureDecision.UNKNOWN
        }
    }
    val reason = when (decision) {
        SelfCaptureDecision.SELF_CAPTURE -> "hit"
        SelfCaptureDecision.UNKNOWN -> "partial-scores"
        SelfCaptureDecision.OK -> "ok"
    }
    return SelfCaptureReading(decision, s1, s1hp, reason)
}
