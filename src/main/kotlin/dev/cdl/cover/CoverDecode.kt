// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.cover

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt

const val COVER_DECODE_MAX_SIDE: Int = 1024

fun decodeCoverImage(data: ByteArray, maxSide: Int = COVER_DECODE_MAX_SIDE): BufferedImage? {
    if (data.isEmpty()) return null

    val input = MemoryCacheImageInputStream(ByteArrayInputStream(data))
    return decodeFrom(input, maxSide)
}

fun decodeCoverFile(path: Path, maxSide: Int = COVER_DECODE_MAX_SIDE): BufferedImage? {
    val input = runCatching { ImageIO.createImageInputStream(path.toFile()) }.getOrNull()
        ?: return null
    return decodeFrom(input, maxSide)
}

private fun decodeFrom(input: ImageInputStream, maxSide: Int): BufferedImage? {
    val limit = maxSide.coerceAtLeast(1)
    try {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) return null
            val sample = decodeSampleFor(max(width, height), limit)
            val param = reader.defaultReadParam
            if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
            val decoded = reader.read(0, param) ?: return null
            return if (max(decoded.width, decoded.height) > limit) {
                downscaleToLimit(decoded, limit)
            } else {
                decoded
            }
        } finally {
            reader.dispose()
        }
    } catch (_: Throwable) {

        return null
    } finally {
        runCatching { input.close() }
    }
}

fun decodeSampleFor(longSide: Int, limit: Int): Int {
    val safeLimit = limit.coerceAtLeast(1)
    if (longSide <= safeLimit) return 1
    val ceilFactor = (longSide + safeLimit - 1) / safeLimit

    return if (longSide / ceilFactor >= (safeLimit * 3) / 4) ceilFactor else 1
}

private fun downscaleToLimit(src: BufferedImage, limit: Int): BufferedImage {
    val scale = limit.toDouble() / max(src.width, src.height)
    val width = max(1, (src.width * scale).roundToInt())
    val height = max(1, (src.height * scale).roundToInt())
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(src, 0, 0, width, height, null)
    } finally {
        g.dispose()
    }
    return out
}
