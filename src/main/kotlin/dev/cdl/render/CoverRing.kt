// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.Area

object CoverRing {

    const val RING_OVER_OUTER_RADIUS = 1f / 3f

    const val CONTENT_OVER_OUTER_RADIUS = 1f - RING_OVER_OUTER_RADIUS

    fun ringWidth(outerSize: Float, vinyl: Boolean): Float =
        if (!vinyl || outerSize <= 0f) 0f else outerSize * RING_OVER_OUTER_RADIUS / 2f

    fun contentSize(outerSize: Float, vinyl: Boolean): Float =
        if (outerSize <= 0f) 0f else outerSize - 2f * ringWidth(outerSize, vinyl)

    const val SEAM_SEAL_PX = 1f

    fun ringArea(shape: Shape, ring: Float, sealPx: Float = SEAM_SEAL_PX): Area {
        if (ring <= 0f) return Area()
        val outer = Area(BasicStroke(ring * 2f).createStrokedShape(shape))

        val b = shape.bounds2D
        val limit = (minOf(b.width, b.height) / 8.0).toFloat()
        val seal = if (sealPx.isFinite()) sealPx.coerceIn(0f, limit.coerceAtLeast(0f)) else 0f
        val hole = Area(shape)
        if (seal > 0f) hole.subtract(Area(BasicStroke(seal * 2f).createStrokedShape(shape)))
        outer.subtract(hole)
        return outer
    }
}
