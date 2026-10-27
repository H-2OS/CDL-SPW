// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

object GlassSdf {

    const val DEFAULT_ROUNDNESS = 5.0

    const val NORMAL_EPS_PX = 0.75

    fun roundedRect(
        x: Double, y: Double,
        cx: Double, cy: Double,
        hw: Double, hh: Double,
        cornerRadius: Double,
        roundness: Double,

        cornerRadiusBottom: Double = Double.NaN,
    ): Double {
        val dx0 = Math.abs(x - cx) - hw
        val dy0 = Math.abs(y - cy) - hh

        val rTop = if (cornerRadius > 0.0) cornerRadius else 0.0
        val r = if (cornerRadiusBottom.isNaN()) {
            rTop
        } else {
            val rBot = if (cornerRadiusBottom > 0.0) cornerRadiusBottom else 0.0
            if (y < cy) rTop else rBot
        }
        if (r > 0.0 && dx0 > -r && dy0 > -r) {

            val n = if (roundness.isFinite() && roundness >= 2.0) roundness else DEFAULT_ROUNDNESS
            val ax = Math.abs(x - cx) - (hw - r)
            val ay = Math.abs(y - cy) - (hh - r)
            val v = Math.pow(Math.pow(ax, n) + Math.pow(ay, n), 1.0 / n)
            return v - r
        }

        return Math.min(Math.max(dx0, dy0), 0.0) +
            Math.hypot(Math.max(dx0, 0.0), Math.max(dy0, 0.0))
    }

    fun of(
        x: Double, y: Double,
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double = DEFAULT_ROUNDNESS,

        cornerRadiusBottom: Double = Double.NaN,
    ): Double = roundedRect(
        x, y,
        w * 0.5, h * 0.5,
        w * 0.5, h * 0.5,
        cornerRadius, roundness, cornerRadiusBottom,
    )

    fun gradient(
        x: Double, y: Double,
        w: Int, h: Int,
        cornerRadius: Double,
        roundness: Double,
        out: DoubleArray,
        eps: Double = NORMAL_EPS_PX,

        cornerRadiusBottom: Double = Double.NaN,
    ) {
        out[0] = of(x + eps, y, w, h, cornerRadius, roundness, cornerRadiusBottom) -
            of(x - eps, y, w, h, cornerRadius, roundness, cornerRadiusBottom)
        out[1] = of(x, y + eps, w, h, cornerRadius, roundness, cornerRadiusBottom) -
            of(x, y - eps, w, h, cornerRadius, roundness, cornerRadiusBottom)
    }
}
