// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Shape
import java.awt.image.BufferedImage

class OwnFrame(
    val seq: Long,
    val shape: Shape,
    val islandLeft: Float,
    val islandTop: Float,
    val islandWidth: Int,
    val islandHeight: Int,
    val pixels: BufferedImage?,
)
