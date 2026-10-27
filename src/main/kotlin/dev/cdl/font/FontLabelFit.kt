// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font

fun fontForLabelText(candidate: Font, text: String, fallback: Font): Font =
    if (candidate.canDisplayUpTo(text) == -1) candidate else fallback
