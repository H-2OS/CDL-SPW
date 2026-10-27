// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

class ShapeMaskRaster(
    val width: Int,
    val height: Int,
) {

    var insideCount: Int = 0
        private set

    var mask: BooleanArray = BooleanArray(0)
        private set

    private var cachedKey: String? = null

    private val scratch: BufferedImage? =
        if (width > 0 && height > 0) {
            BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        } else {
            null
        }

    fun maskFor(shape: Shape): BooleanArray {
        val img = scratch
        if (img == null) {
            insideCount = 0
            mask = BooleanArray(0)
            cachedKey = null
            return mask
        }
        val key = keyOf(shape)
        if (key == cachedKey && mask.size == width * height) return mask

        val g = img.createGraphics()
        try {
            g.setColor(java.awt.Color(0, 0, 0))
            g.fillRect(0, 0, width, height)
            g.setColor(java.awt.Color(255, 255, 255))
            g.fill(shape)
        } finally {
            g.dispose()
        }

        val out = BooleanArray(width * height)
        val row = IntArray(width)
        var count = 0
        for (y in 0 until height) {
            img.getRGB(0, y, width, 1, row, 0, width)
            val base = y * width
            for (x in 0 until width) {
                val v = row[x] and 0xFF
                if (v >= 128) {
                    out[base + x] = true
                    count++
                }
            }
        }
        mask = out
        insideCount = count
        cachedKey = key
        return out
    }

    private fun keyOf(shape: Shape): String {
        val b: Rectangle2D = shape.getBounds2D()
        val head = "${b.x}|${b.y}|${b.width}|${b.height}|${shape.javaClass.name}"
        val body = runCatching {
            val bos = java.io.ByteArrayOutputStream(256)
            java.io.ObjectOutputStream(bos).use { it.writeObject(shape) }
            val bytes = bos.toByteArray()

            var h = 1125899906842597L
            for (byte in bytes) h = 31 * h + byte
            bytes.size.toString() + ":" + h
        }.getOrElse {

            "uid:" + System.identityHashCode(shape)
        }
        return "$head|$body"
    }

    fun lastKey(): String? = cachedKey

    override fun toString(): String =
        "ShapeMaskRaster(${width}x$height, inside=$insideCount, key=$cachedKey)"

    companion object {

        fun rasterizeOnce(shape: Shape, width: Int, height: Int): BooleanArray =
            ShapeMaskRaster(width, height).maskFor(shape)
    }
}

fun toArgbArray(image: BufferedImage): IntArray {
    val w = image.width
    val h = image.height
    val out = IntArray(w * h)
    image.getRGB(0, 0, w, h, out, 0, w)
    return out
}

fun cropArgb(screen: BufferedImage, originOnScreen: Rectangle, w: Int, h: Int): IntArray? {
    if (w <= 0 || h <= 0) return null
    if (originOnScreen.x < 0 || originOnScreen.y < 0) return null
    if (originOnScreen.x + w > screen.width || originOnScreen.y + h > screen.height) return null
    val out = IntArray(w * h)
    screen.getRGB(originOnScreen.x, originOnScreen.y, w, h, out, 0, w)
    return out
}
