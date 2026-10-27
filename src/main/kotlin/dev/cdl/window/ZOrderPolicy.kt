// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

fun shouldHoldTempTopmost(
    manualRaised: Boolean,
    foregroundPid: Int,
    selfPid: Int,
): Boolean {
    if (!manualRaised) return false
    if (selfPid == 0) return false
    if (foregroundPid == 0) return true
    return foregroundPid == selfPid
}
