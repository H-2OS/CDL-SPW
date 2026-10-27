// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

import java.awt.Color

enum class SettingKind {

    SWITCH,

    SEEKBAR,

    LIST,

    EDITTEXT,

    COLOR,

    FONT,
}

enum class SettingPhase {

    P0,

    P1,

    P4,
}

enum class PreviewKind {

    IMMEDIATE,

    THROTTLED,

    DEFERRED,
}

data class SettingDescriptor(
    val key: String,

    val group: String,

    val subGroup: String? = null,
    val kind: SettingKind,
    val title: String,
    val summary: String? = null,
    val default: Any,
    val range: ClosedRange<Double>? = null,
    val options: List<Pair<String, String>>? = null,
    val phase: SettingPhase,
    val preview: PreviewKind,
    val alphaAllowed: Boolean = false,

    val retired: Boolean = false,

    val step: Int = 1,
) {
    init {

        when (kind) {
            SettingKind.SEEKBAR -> requireNotNull(range) { "SEEKBAR 项必须有 range：$key" }
            SettingKind.LIST -> requireNotNull(options) { "LIST 项必须有 options：$key" }
            else -> Unit
        }
        require(!alphaAllowed || kind == SettingKind.COLOR) {
            "alphaAllowed 只对 COLOR 有意义：$key"
        }

        require(step >= 1) { "step 必须是 ≥1 的整数（0/负数会让 ± 失效或反向）：$key=$step" }

        require(kind == SettingKind.SEEKBAR || step == 1) {
            "step 只对 SEEKBAR 有意义（$kind 项请勿设 step）：$key=$step"
        }
    }

    val expectedType: Class<*>
        get() = when (kind) {
            SettingKind.SWITCH -> java.lang.Boolean::class.java
            SettingKind.SEEKBAR -> java.lang.Integer::class.java
            SettingKind.LIST, SettingKind.EDITTEXT, SettingKind.FONT -> String::class.java
            SettingKind.COLOR -> Int::class.java
        }

    fun coerce(value: Any?): Any = when (kind) {
        SettingKind.SWITCH -> when (value) {
            is Boolean -> value

            is String -> value.trim().toBooleanStrictOrNull() ?: default
            else -> default
        }

        SettingKind.SEEKBAR -> {
            val number = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }
            if (number == null) default

            else if (!number.isFinite()) default
            else number.coerceIn(range!!.start, range.endInclusive).toInt()
        }

        SettingKind.LIST -> {
            val text = value as? String ?: return default

            options!!.firstOrNull { it.first == text }?.first ?: default
        }

        SettingKind.EDITTEXT -> value as? String ?: default
        SettingKind.FONT -> (value as? String)?.takeIf { it.isNotBlank() } ?: default

        SettingKind.COLOR -> {
            val packed = when (value) {

                is Number -> value.toInt()

                is String -> parseHexOrNull(value)
                else -> null
            }
            if (packed == null) default
            else {

                val rgb = packed and 0xFFFFFF
                if (alphaAllowed) packed else 0xFF000000.toInt() or rgb
            }
        }
    }

    fun coerceBoolean(value: Any?): Boolean = coerce(value) as Boolean

    fun coerceInt(value: Any?): Int = coerce(value) as Int

    fun coerceText(value: Any?): String = coerce(value) as String

    fun defaultValueBoolean(): Boolean = coerceBoolean(default)

    fun defaultValueInt(): Int = coerceInt(default)

    fun defaultValueText(): String = coerceText(default)

    val intRange: IntRange
        get() = requireNotNull(range) { "$key 不是 SEEKBAR" }
            .let { it.start.toInt()..it.endInclusive.toInt() }
}

fun Color.toPackedArgb(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

fun Int.toColor(): Color = Color(this, true)

fun parseHexOrNull(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val functional = Regex("""^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$""", RegexOption.IGNORE_CASE)
    functional.find(text)?.let { match ->
        val (r, g, b) = match.destructured
        return packRgb(r.toInt(), g.toInt(), b.toInt())
    }

    val body = text.removePrefix("#")

    val cleaned = body.replace("0x", "", ignoreCase = true)
    if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null

    return when (cleaned.length) {
        3 -> {

            val r = cleaned[0].digitToInt(16) * 17
            val g = cleaned[1].digitToInt(16) * 17
            val b = cleaned[2].digitToInt(16) * 17
            packRgb(r, g, b)
        }
        6 -> packRgb(
            cleaned.substring(0, 2).toInt(16),
            cleaned.substring(2, 4).toInt(16),
            cleaned.substring(4, 6).toInt(16),
        )

        else -> null
    }
}

private fun packRgb(r: Int, g: Int, b: Int): Int =
    0xFF000000.toInt() or
        (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or
        b.coerceIn(0, 255)

fun composePackedArgb(r: Int, g: Int, b: Int, lightnessPercent: Int = 100): Int {
    val l = lightnessPercent.coerceIn(0, 100) / 100f
    val rr = (r * l).toInt().coerceIn(0, 255)
    val gg = (g * l).toInt().coerceIn(0, 255)
    val bb = (b * l).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
}

fun blendPackedArgb(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun channel(packed: Int, shift: Int): Int = (packed ushr shift) and 0xFF
    fun mixChannel(shift: Int): Int {
        val from = channel(a, shift).toFloat()
        val to = channel(b, shift).toFloat()
        return (from + (to - from) * k).toInt().coerceIn(0, 255)
    }
    return composePackedArgb(mixChannel(16), mixChannel(8), mixChannel(0))
}

fun Int.toHexString(): String =
    if ((this ushr 24) and 0xFF == 0xFF) String.format("#%06X", this and 0xFFFFFF)
    else String.format("#%08X", this)

public const val UNSUNG_TRANSLATION_DIM: Float = 0.62f

public fun dimPackedArgb(argb: Int, factor: Float): Int {
    fun channel(shift: Int): Int =
        (((argb shr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
    return (argb and 0xFF000000.toInt()) or
        (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

fun SettingDescriptor.storageValue(value: Any): Any =
    if (kind == SettingKind.COLOR && value is Int) value.toHexString() else value

fun SettingDescriptor.differsFromRaw(coerced: Any, raw: Any): Boolean {
    if (coerced is Number && raw is Number) return coerced.toDouble() != raw.toDouble()
    return coerced != raw
}
