// SPDX-License-Identifier: GPL-3.0-only
package dev.cdl.cover

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PaletteEntry(val argb: Int, val share: Float)

object CoverPaletteRich {

    const val SAMPLE_SIZE = 40

    private const val K = 6

    private const val ITERATIONS = 12

    private const val SEED = 20261020L

    private const val MIN_SHARE = 0.02f

    private const val DARK_L = 0.25f

    private const val DARK_SMALL_SHARE = 0.08f

    private const val DEDUPE_DIST = 0.08f

    private const val CHROMA_MIN = 0.04f

    const val RICH_MIN = 4

    const val RICH_MAX = 6

    private const val DERIVE_HUE_DEG = 10f

    private const val DERIVE_LIGHT = 0.08f

    private const val DERIVE_SHARE = 0.15f

    private val DERIVE_VARIANTS = arrayOf(
        intArrayOf(1, 1),
        intArrayOf(-1, 1),
        intArrayOf(1, -1),
        intArrayOf(-1, -1),
    )

    private fun mainCap(topShare: Float, secondShare: Float): Int = when {
        topShare >= 0.85f -> 1
        topShare >= 0.70f -> if (secondShare >= 0.10f) 2 else 1
        topShare >= 0.45f -> 2
        else -> 3
    }

    fun entries(image: BufferedImage, circleMask: Boolean = true): List<PaletteEntry> {
        val clusters = cluster(image, circleMask) ?: return emptyList()
        if (clusters.isEmpty()) return emptyList()

        val groups = merge(clusters)
        if (groups.isEmpty()) return emptyList()

        val picked = groups.filter { it.share >= MIN_SHARE }.take(RICH_MAX).toMutableList()
        if (picked.isEmpty()) picked.add(groups.first())
        val withDerived = deriveTo(picked, RICH_MIN)
        return normalize(withDerived)
    }

    fun mainEntries(image: BufferedImage, circleMask: Boolean = true): List<PaletteEntry> {
        val clusters = cluster(image, circleMask) ?: return emptyList()
        val groups = merge(clusters)
        if (groups.isEmpty()) return emptyList()
        val cap = mainCap(groups[0].share, groups.getOrNull(1)?.share ?: 0f)
        return normalize(groups.take(cap))
    }

    fun colorSource(image: BufferedImage, circleMask: Boolean = true): Int? {
        val clusters = cluster(image, circleMask) ?: return null
        val groups = merge(clusters)
        if (groups.isEmpty()) return null
        var best: Cluster? = null
        var bestRank = -1f
        for (c in groups) {
            if (c.chroma < CHROMA_MIN || c.share < MIN_SHARE) continue
            val rank = c.share * if (c.backdrop) 0.6f else 1f
            if (rank > bestRank) {
                bestRank = rank
                best = c
            }
        }
        return (best ?: groups.first()).argb
    }

    private class Cluster(
        val argb: Int,
        val share: Float,
        val chroma: Float,
        val sigmaL: Float,
        val edgeMask: Int,

        val family: Int = -1,
    ) {

        val backdrop: Boolean
            get() = chroma < CHROMA_MIN && sigmaL < 0.03f && Integer.bitCount(edgeMask) >= 3
    }

    private fun cluster(image: BufferedImage, circleMask: Boolean): List<Cluster>? {
        if (image.width <= 0 || image.height <= 0) return null
        val s = SAMPLE_SIZE
        val px = boxDown(image, s)
        val ring = frameRingPx(px, s)
        val cx = (s - 1) / 2f
        val rad = s / 2f

        val lab = ArrayList<FloatArray>(s * s)
        val xs = ArrayList<Int>(s * s)
        val ys = ArrayList<Int>(s * s)
        for (y in 0 until s) {
            for (x in 0 until s) {
                if (circleMask && hypot(x - cx, y - cx) > rad) continue
                if (ring > 0 && (x < ring || y < ring || x >= s - ring || y >= s - ring)) continue
                lab.add(srgbToOklab(px[y * s + x]))
                xs.add(x)
                ys.add(y)
            }
        }
        val n = lab.size
        if (n < K) return null

        val assign = kMeans(lab, K, ITERATIONS, SEED)
        val out = ArrayList<Cluster>(K)
        for (c in 0 until K) {
            val members = ArrayList<FloatArray>()
            for (i in 0 until n) if (assign[i] == c) members.add(lab[i])
            if (members.size < n * MIN_SHARE) continue
            val med = FloatArray(3)
            for (q in 0 until 3) {
                val v = FloatArray(members.size) { members[it][q] }
                v.sort()
                med[q] = v[v.size / 2]
            }
            var meanL = 0f
            for (m in members) meanL += m[0]
            meanL /= members.size
            var variance = 0f
            for (m in members) variance += (m[0] - meanL) * (m[0] - meanL)
            var mask = 0
            for (i in 0 until n) {
                if (assign[i] != c) continue
                val x = xs[i]
                val y = ys[i]
                if (y == 0) mask = mask or 1
                if (y == s - 1) mask = mask or 2
                if (x == 0) mask = mask or 4
                if (x == s - 1) mask = mask or 8
            }
            out.add(
                Cluster(
                    argb = oklabToSrgb(med[0], med[1], med[2]),
                    share = members.size.toFloat() / n,
                    chroma = hypot(med[1], med[2]),
                    sigmaL = sqrt(variance / members.size),
                    edgeMask = mask,
                    family = out.size,
                ),
            )
        }
        return out
    }

    private fun merge(clusters: List<Cluster>): List<Cluster> {
        if (clusters.isEmpty()) return emptyList()
        val allDark = clusters.all { srgbToOklab(it.argb)[0] < DARK_L }
        val kept = clusters.filter {
            !(!allDark && srgbToOklab(it.argb)[0] < DARK_L && it.share < DARK_SMALL_SHARE)
        }.ifEmpty { listOf(clusters.maxByOrNull { it.share }!!) }

        val out = ArrayList<Cluster>()
        val shares = ArrayList<Float>()
        for (c in kept.sortedByDescending { it.share }) {
            val hit = out.indexOfFirst { dist(srgbToOklab(it.argb), srgbToOklab(c.argb)) < DEDUPE_DIST }
            if (hit < 0) {
                out.add(c)
                shares.add(c.share)
            } else {
                shares[hit] += c.share
            }
        }

        val order = out.indices.sortedByDescending { shares[it] }
        return order.map { i ->
            val c = out[i]
            Cluster(c.argb, shares[i], c.chroma, c.sigmaL, c.edgeMask, c.family)
        }
    }

    private fun deriveTo(base: List<Cluster>, min: Int): List<Cluster> {
        if (base.size >= min) return base
        val items = base.map { it to it.share }.toMutableList()

        for (v in DERIVE_VARIANTS) {
            if (items.size >= min) break
            val idx = items.indices.maxByOrNull { items[it].second } ?: break
            val (parent, sh) = items[idx]
            val take = sh * DERIVE_SHARE
            if (take < 1e-4f) break
            val cand = deriveOne(parent, v[0].toFloat(), v[1].toFloat(), take)

            if (items.any { dist(srgbToOklab(it.first.argb), srgbToOklab(cand.argb)) < 0.04f }) continue
            items[idx] = parent to (sh - take)
            items.add(cand to take)
        }
        return items.map { (c, s) -> Cluster(c.argb, s, c.chroma, c.sigmaL, c.edgeMask, c.family) }
    }

    private fun deriveOne(parent: Cluster, hueSign: Float, lightSign: Float, share: Float): Cluster {
        val lab = srgbToOklab(parent.argb)
        val ang = Math.toRadians((DERIVE_HUE_DEG * hueSign).toDouble()).toFloat()
        val cos = kotlin.math.cos(ang)
        val sin = kotlin.math.sin(ang)
        val a = lab[1] * cos - lab[2] * sin
        val b = lab[1] * sin + lab[2] * cos
        val l = (lab[0] * (1f + DERIVE_LIGHT * lightSign)).coerceIn(0.05f, 0.98f)
        return Cluster(oklabToSrgb(l, a, b), share, hypot(a, b), parent.sigmaL, 0, parent.family)
    }

    private fun normalize(list: List<Cluster>): List<PaletteEntry> {
        if (list.isEmpty()) return emptyList()
        val sum = list.sumOf { it.share.toDouble() }.toFloat()
        if (sum <= 0f) return emptyList()

        val familyShare = HashMap<Int, Float>()
        for (c in list) familyShare[c.family] = (familyShare[c.family] ?: 0f) + c.share
        return list
            .sortedWith(
                compareByDescending<Cluster> { familyShare[it.family] ?: 0f }
                    .thenByDescending { it.share },
            )
            .map { PaletteEntry(it.argb, it.share / sum) }
    }

    private fun boxDown(image: BufferedImage, s: Int): IntArray {
        val out = IntArray(s * s)
        for (y in 0 until s) {
            val y0 = y * image.height / s
            val y1 = ((y + 1) * image.height / s).coerceAtLeast(y0 + 1).coerceAtMost(image.height)
            for (x in 0 until s) {
                val x0 = x * image.width / s
                val x1 = ((x + 1) * image.width / s).coerceAtLeast(x0 + 1).coerceAtMost(image.width)
                var r = 0L
                var g = 0L
                var b = 0L
                var c = 0
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        val p = image.getRGB(xx, yy)
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        c++
                    }
                }
                out[y * s + x] = ((r / c).toInt() shl 16) or ((g / c).toInt() shl 8) or (b / c).toInt()
            }
        }
        return out
    }

    private fun frameRingPx(px: IntArray, s: Int): Int {
        fun rowClose(y: Int, ref: FloatArray): Boolean {
            var acc = 0f
            for (x in 0 until s) acc += dist(srgbToOklab(px[y * s + x]), ref)
            return acc / s <= 0.035f
        }

        fun colClose(x: Int, ref: FloatArray): Boolean {
            var acc = 0f
            for (y in 0 until s) acc += dist(srgbToOklab(px[y * s + x]), ref)
            return acc / s <= 0.035f
        }

        var ref = 0
        for (x in 0 until s) ref = if (ref == 0) px[x] else blend(ref, px[x])
        val refLab = srgbToOklab(ref)
        var top = 0
        while (top < s / 2 && rowClose(top, refLab)) top++
        var bottom = 0
        while (bottom < s / 2 && rowClose(s - 1 - bottom, refLab)) bottom++
        var left = 0
        while (left < s / 2 && colClose(left, refLab)) left++
        var right = 0
        while (right < s / 2 && colClose(s - 1 - right, refLab)) right++

        val d = intArrayOf(top, bottom, left, right)
        val mn = d.min()
        val mx = d.max()
        if (mn < 2 || mx > mn * 1.6f) return 0
        val ring = (top + bottom + left + right) / 4
        if (ring <= 0) return 0

        val remain = ((s - 2 * ring).toFloat() / s) * ((s - 2 * ring).toFloat() / s)
        if (remain < 0.40f) return 0

        val inner = px[(s / 2) * s + s / 2]
        if (dist(refLab, srgbToOklab(inner)) <= 0.06f) return 0
        return ring
    }

    private fun blend(a: Int, b: Int): Int {
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
        val bl = ((a and 0xFF) + (b and 0xFF)) / 2
        return (r shl 16) or (g shl 8) or bl
    }

    private fun kMeans(pts: List<FloatArray>, k: Int, iterations: Int, seed: Long): IntArray {
        val n = pts.size
        val rnd = java.util.Random(seed)
        val centers = ArrayList<FloatArray>(k)
        centers.add(pts[rnd.nextInt(n)].copyOf())
        while (centers.size < k) {
            val d2 = DoubleArray(n)
            var sum = 0.0
            for (i in 0 until n) {
                var best = Double.MAX_VALUE
                for (c in centers) best = kotlin.math.min(best, dist2(pts[i], c).toDouble())
                d2[i] = best
                sum += best
            }
            if (sum <= 0.0) break
            var t = rnd.nextDouble() * sum
            var pick = n - 1
            for (i in 0 until n) {
                t -= d2[i]
                if (t <= 0.0) {
                    pick = i
                    break
                }
            }
            centers.add(pts[pick].copyOf())
        }
        val assign = IntArray(n)
        repeat(iterations) {
            for (i in 0 until n) {
                var best = 0
                var bestD = Float.MAX_VALUE
                for (c in centers.indices) {
                    val d = dist2(pts[i], centers[c])
                    if (d < bestD) {
                        bestD = d
                        best = c
                    }
                }
                assign[i] = best
            }
            for (c in centers.indices) {
                val m = FloatArray(3)
                var cnt = 0
                for (i in 0 until n) {
                    if (assign[i] != c) continue
                    m[0] += pts[i][0]
                    m[1] += pts[i][1]
                    m[2] += pts[i][2]
                    cnt++
                }
                if (cnt > 0) {
                    m[0] /= cnt
                    m[1] /= cnt
                    m[2] /= cnt
                    centers[c] = m
                }
            }
        }
        return assign
    }

    private fun dist2(a: FloatArray, b: FloatArray): Float {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return dl * dl + da * da + db * db
    }

    private fun dist(a: FloatArray, b: FloatArray): Float = sqrt(dist2(a, b))

    private fun srgbToOklab(rgb: Int): FloatArray {
        val r = lin(((rgb shr 16) and 0xFF) / 255.0)
        val g = lin(((rgb shr 8) and 0xFF) / 255.0)
        val b = lin((rgb and 0xFF) / 255.0)
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val l_ = cbrt(l)
        val m_ = cbrt(m)
        val s_ = cbrt(s)
        return floatArrayOf(
            (0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_).toFloat(),
            (1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_).toFloat(),
            (0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_).toFloat(),
        )
    }

    private fun oklabToSrgb(l: Float, a: Float, b: Float): Int {
        val l_ = l + 0.3963377774f * a + 0.2158037573f * b
        val m_ = l - 0.1055613458f * a - 0.0638541728f * b
        val s_ = l - 0.0894841775f * a - 1.2914855480f * b
        val ll = l_ * l_ * l_
        val mm = m_ * m_ * m_
        val ss = s_ * s_ * s_
        val r = 4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss
        val g = -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss
        val bl = -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss
        return (0xFF shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(bl)
    }

    private fun lin(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun enc(c: Float): Int {
        val v = if (c <= 0.0031308f) 12.92f * c else 1.055f * (c.toDouble().pow(1.0 / 2.4)).toFloat() - 0.055f
        return (v.coerceIn(0f, 1f) * 255f).roundToInt()
    }
}
