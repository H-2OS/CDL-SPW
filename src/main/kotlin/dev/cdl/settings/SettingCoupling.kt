// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

object SettingCoupling {

    const val BAR_PITCH_KEY = "spectrum.bar_pitch_tenth_px"

    const val BAR_PITCH_MODE_KEY = "spectrum.bar_pitch_mode"

    const val SECONDARY_OPACITY_KEY = "spectrum.secondary.opacity_percent"

    const val SECONDARY_PITCH_KEY = "spectrum.secondary.bar_pitch_tenth_px"

    const val SECONDARY_MODE_KEY = "spectrum.secondary.mode"

    const val BAR_PITCH_MODE_RELEASED = "FIXED"

    const val SECONDARY_MODE_RELEASED = "BOTTOM"

    const val TRANSLATION_OUTLINE_SYNC_KEY = "translation.outline.sync_with_main"

    val TRANSLATION_OUTLINE_KEYS: Set<String> = setOf(
        "translation.outline.enabled",
        "translation.outline.width_tenth_px",
        "translation.outline.color.r",
        "translation.outline.color.g",
        "translation.outline.color.b",

        "translation.outline.follow_weight",
    )

    const val TRANSLATION_OUTLINE_SYNC_RELEASED = false

    const val LAYOUT_LINES_KEY = "layout.lines"

    const val TRANSLATION_ENABLED_KEY = "layout.translation.enabled"

    const val LAYOUT_LINES_DUAL = "DUAL"

    const val LAYOUT_LINES_SINGLE = "SINGLE"

    const val COVER_ENABLED_KEY = "cover.enabled"

    val COVER_KEYS: Set<String> = setOf(
        "cover.scale_with_font",
        "cover.shape",
        "cover.rotate",
        "cover.rotate_period_s",

        "cover.vinyl",

        "cover.sharpness",
    )

    val COVER_LOCK_KEY = "cover.lock"

    fun releasePatch(
        changedKeys: Set<String>,

        valueOf: (String) -> String? = { null },
    ): Map<String, Any?> = buildMap {
        if (BAR_PITCH_KEY in changedKeys) {
            put(BAR_PITCH_MODE_KEY, BAR_PITCH_MODE_RELEASED)
        }

        if (SECONDARY_OPACITY_KEY in changedKeys || SECONDARY_PITCH_KEY in changedKeys) {
            put(SECONDARY_MODE_KEY, SECONDARY_MODE_RELEASED)
        }

        if (changedKeys.any { it in TRANSLATION_OUTLINE_KEYS }) {
            put(TRANSLATION_OUTLINE_SYNC_KEY, TRANSLATION_OUTLINE_SYNC_RELEASED)
        }

        if (LAYOUT_LINES_KEY in changedKeys) {
            when (valueOf(LAYOUT_LINES_KEY)) {
                LAYOUT_LINES_DUAL -> put(TRANSLATION_ENABLED_KEY, true)
                LAYOUT_LINES_SINGLE -> put(TRANSLATION_ENABLED_KEY, false)
                else -> Unit
            }
        }

        if (changedKeys.any { it in COVER_KEYS }) {
            put(COVER_ENABLED_KEY, true)
        }

    }
}
