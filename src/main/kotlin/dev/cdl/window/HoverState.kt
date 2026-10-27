// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

class HoverState {

    var expanded: Boolean = false
        private set

    var expansion: Float = 0f
        private set

    private var enterSince: Long = NONE

    private var exitSince: Long = NONE

    private var lastInsideAt: Long = NONE

    private var expandedSince: Long = NONE

    fun update(
        hitGlyph: Boolean,
        inside: Boolean,
        nowMs: Long,
        cfg: ControlsConfig,
        pointerEvent: Boolean,
    ): Boolean {
        val was = expanded
        if (pointerEvent && inside) lastInsideAt = nowMs

        if (!expanded) {

            if (hitGlyph) {
                if (enterSince == NONE) enterSince = nowMs
                if (nowMs - enterSince >= cfg.enterDelayMs) {
                    expanded = true

                    enterSince = NONE
                    exitSince = NONE
                    lastInsideAt = nowMs

                    expandedSince = nowMs
                }
            } else {

                enterSince = NONE
            }

            expansion = 0f
            return expanded != was
        }

        val elapsed = if (expandedSince == NONE) FADE_IN_MS else nowMs - expandedSince
        val t = (elapsed.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
        expansion = t * t * (3f - 2f * t)

        if (inside) {
            exitSince = NONE
        } else {
            if (exitSince == NONE) exitSince = nowMs
            if (nowMs - exitSince >= cfg.exitDelayMs) {
                expanded = false
                enterSince = NONE
                exitSince = NONE
                expandedSince = NONE
                expansion = 0f
            }
        }
        if (expanded && cfg.autoHideMs > 0 && lastInsideAt != NONE &&
            nowMs - lastInsideAt >= cfg.autoHideMs
        ) {
            expanded = false
            enterSince = NONE
            exitSince = NONE
            expandedSince = NONE
            expansion = 0f
        }
        return expanded != was
    }

    fun needsTicker(cfg: ControlsConfig, pollPointer: Boolean = false): Boolean = if (pollPointer) {
        true
    } else if (!expanded) {
        enterSince != NONE
    } else {

        expansion < 1f || exitSince != NONE || cfg.autoHideMs > 0
    }

    fun reset() {
        expanded = false
        enterSince = NONE
        exitSince = NONE
        lastInsideAt = NONE
        expandedSince = NONE
        expansion = 0f
    }

    fun expandNow(nowMs: Long) {
        if (expanded) return
        expanded = true
        enterSince = NONE
        exitSince = NONE
        lastInsideAt = nowMs
        expandedSince = nowMs
    }

    private companion object {

        const val NONE = Long.MIN_VALUE

        const val FADE_IN_MS = 180f
    }
}
