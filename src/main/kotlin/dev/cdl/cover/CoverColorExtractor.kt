// SPDX-License-Identifier: GPL-3.0-only
// Ported in part from Dynamic Lyrics Island for SPW by GaBoron (GPL-3.0-only); see NOTICE.
package dev.cdl.cover

import dev.cdl.settings.composePackedArgb
import java.awt.Color
import java.awt.image.BufferedImage

object CoverColorExtractor {

    private const val SAMPLE_GRID = 32

    private const val HUE_BUCKETS = 12

    private const val NEUTRAL_BUCKETS = 4

    private const val ALPHA_THRESHOLD = 128

    private const val CHROMA_MIN_SATURATION = 0.12f

    private const val NEAR_BLACK_VALUE = 0.08f

    private const val NEAR_WHITE_VALUE = 0.92f

    private const val MIN_CHROMA_SHARE = 0.02f

    fun dominant(image: BufferedImage): Int? = palette(image, 1).firstOrNull()

    fun palette(image: BufferedImage, n: Int): List<Int> {
        if (n <= 0) return emptyList()
        if (image.width <= 0 || image.height <= 0) return emptyList()

        val counted = accumulate(image) ?: return emptyList()
        val (chroma, neutral, opaque) = counted

        val chromaRanked = chroma.withIndex()
            .filter { (_, acc) -> acc.count >= opaque * MIN_CHROMA_SHARE }
            .sortedWith(compareByDescending<IndexedValue<Accumulator>> { it.value.count }.thenBy { it.index })
            .map { it.value.rgb() }

        if (chromaRanked.size >= n) return chromaRanked.take(n)

        val neutralRanked = neutral.withIndex()
            .filter { (_, acc) -> acc.count > 0 }
            .sortedWith(compareByDescending<IndexedValue<Accumulator>> { it.value.count }.thenBy { it.index })
            .map { it.value.rgb() }

        return chromaRanked + neutralRanked.take(n - chromaRanked.size)
    }

    private fun accumulate(image: BufferedImage): Triple<Array<Accumulator>, Array<Accumulator>, Int>? {

        val stepX = ((image.width + SAMPLE_GRID - 1) / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = ((image.height + SAMPLE_GRID - 1) / SAMPLE_GRID).coerceAtLeast(1)

        val chroma = Array(HUE_BUCKETS) { Accumulator() }
        val neutral = Array(NEUTRAL_BUCKETS) { Accumulator() }
        var opaque = 0
        val hsb = FloatArray(3)

        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val argb = image.getRGB(x, y)
                if (((argb ushr 24) and 0xFF) >= ALPHA_THRESHOLD) {
                    opaque++
                    val red = (argb shr 16) and 0xFF
                    val green = (argb shr 8) and 0xFF
                    val blue = argb and 0xFF
                    Color.RGBtoHSB(red, green, blue, hsb)
                    val saturation = hsb[1]
                    val brightness = hsb[2]
                    val nearBlack = brightness < NEAR_BLACK_VALUE
                    val nearWhite = brightness > NEAR_WHITE_VALUE && saturation < CHROMA_MIN_SATURATION
                    if (!nearBlack && !nearWhite && saturation >= CHROMA_MIN_SATURATION) {
                        chroma[hueBucket(hsb[0])].add(red, green, blue)
                    } else {
                        neutral[brightnessBucket(brightness)].add(red, green, blue)
                    }
                }
                x += stepX
            }
            y += stepY
        }

        if (opaque == 0) return null
        return Triple(chroma, neutral, opaque)
    }


    private fun hueBucket(hue: Float): Int =
        ((hue * HUE_BUCKETS + 0.5f).toInt() % HUE_BUCKETS + HUE_BUCKETS) % HUE_BUCKETS

    private fun brightnessBucket(brightness: Float): Int =
        (brightness * NEUTRAL_BUCKETS).toInt().coerceIn(0, NEUTRAL_BUCKETS - 1)

    private class Accumulator {
        var count: Int = 0
            private set
        private var red = 0L
        private var green = 0L
        private var blue = 0L

        fun add(red: Int, green: Int, blue: Int) {
            count++
            this.red += red
            this.green += green
            this.blue += blue
        }

        fun rgb(): Int = composePackedArgb(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt(),
        )
    }
}
