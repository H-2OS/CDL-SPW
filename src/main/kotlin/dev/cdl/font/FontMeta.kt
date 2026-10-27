// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

data class FontMeta(
    val family: String,
    val subfamily: String?,
    val weightClass: Int,
    val italic: Boolean,
    val italicAngle: Double,
) {

    val boldByWeight: Boolean get() = weightClass >= 600
}

fun readFontMeta(bytes: ByteArray): FontMeta? = runCatching {
    val numTables = u16(bytes, 4)
    if (numTables <= 0 || numTables > 512) return@runCatching null

    var headOff = -1
    var os2Off = -1
    var postOff = -1
    var nameOff = -1
    for (i in 0 until numTables) {
        val rec = 12 + i * 16
        if (rec + 16 > bytes.size) return@runCatching null
        when (String(bytes, rec, 4, Charsets.US_ASCII)) {
            "head" -> headOff = s32(bytes, rec + 8)
            "OS/2" -> os2Off = s32(bytes, rec + 8)
            "post" -> postOff = s32(bytes, rec + 8)
            "name" -> nameOff = s32(bytes, rec + 8)
        }
    }
    if (os2Off < 0 && headOff < 0) return@runCatching null

    val fsSel = if (os2Off in 0..bytes.size - 64) u16(bytes, os2Off + 62) else -1
    val macStyle = if (headOff in 0..bytes.size - 46) u16(bytes, headOff + 44) else -1
    val italic = when {
        fsSel >= 0 && macStyle >= 0 -> (fsSel and 0x01) != 0 && (macStyle and 0x02) != 0
        fsSel >= 0 -> (fsSel and 0x01) != 0
        macStyle >= 0 -> (macStyle and 0x02) != 0
        else -> false
    }

    val weight = if (os2Off in 0..bytes.size - 6) u16(bytes, os2Off + 4) else 400
    val angle = if (postOff in 0..bytes.size - 8) s32(bytes, postOff + 4) / 65536.0 else 0.0

    val names = if (nameOff > 0) readNames(bytes, nameOff) else emptyMap()
    FontMeta(
        family = names[1] ?: "",
        subfamily = names[2],
        weightClass = weight,
        italic = italic,
        italicAngle = angle,
    )
}.getOrNull()

private fun readNames(b: ByteArray, base: Int): Map<Int, String> {
    val out = HashMap<Int, String>()
    return runCatching {
        val count = u16(b, base + 2)
        val strOff = u16(b, base + 4)
        for (i in 0 until count) {
            val rec = base + 6 + i * 12
            if (rec + 12 > b.size) break
            if (u16(b, rec) != 3) continue
            val nameId = u16(b, rec + 6)
            val len = u16(b, rec + 8)
            val off = u16(b, rec + 10)
            if (nameId != 1 && nameId != 2) continue
            val start = base + strOff + off
            if (start + len > b.size) continue
            out[nameId] = decodeUtf16Be(b, start, len)
        }
        out
    }.getOrDefault(out)
}

private fun decodeUtf16Be(b: ByteArray, off: Int, len: Int): String {
    val sb = StringBuilder(len / 2)
    var i = 0
    while (i + 1 < len) {
        sb.append((((b[off + i].toInt() and 0xFF) shl 8) or (b[off + i + 1].toInt() and 0xFF)).toChar())
        i += 2
    }
    return sb.toString()
}

private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

private fun s32(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
