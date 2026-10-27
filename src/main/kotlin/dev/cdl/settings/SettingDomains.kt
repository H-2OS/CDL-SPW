// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

object SettingDomains {

    val GEOMETRY: Set<String> = setOf("layout.", "main_lyric.font.", "shape.")

    val FRAME: Set<String> = setOf("animation.")

    val ALL: List<Set<String>> = listOf(GEOMETRY, FRAME)
}
