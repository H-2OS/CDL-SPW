// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

fun spectrumLayerArgb(packed: Int, alphaScale: Float): Int {
    val a = (255f * alphaScale.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return (a shl 24) or (packed and 0x00FFFFFF)
}

data class SpectrumSegmentColors(val unplayed: Int, val played: Int)

fun spectrumSegmentColors(packed: Int, alphas: SpectrumAlphas): SpectrumSegmentColors =
    SpectrumSegmentColors(
        unplayed = spectrumLayerArgb(packed, alphas.unplayed),
        played = spectrumLayerArgb(packed, alphas.played),
    )

data class SpectrumAlphas(

    val unplayed: Float,

    val played: Float,

    val frameOpacity: Float,
)

fun spectrumAlphas(opacityPct: Int, tintAlphaPct: Int, tintActive: Boolean): SpectrumAlphas {
    val base = opacityPct.coerceIn(0, 100) / 100f

    val diff = if (tintActive) tintAlphaPct.coerceIn(0, 100) / 100f else 0f
    return SpectrumAlphas(

        unplayed = 1f - diff,
        played = 1f,

        frameOpacity = base,
    )
}
