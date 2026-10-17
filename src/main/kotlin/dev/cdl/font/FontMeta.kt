// SPDX-License-Identifier: AGPL-3.0-only
//
// 超级岛 · 字体文件的「斜体 / 字重」元数据读取（纯函数，无副作用）
//
// ## 为什么必须自己解析（而不是用 `java.awt.Font`）
//
// ★★ 2026-09-29 实测（`tools/hostfont/B1EnumProbe.java`，宿主 jar 全部 35 个条目）：
//
// ```
//   Inter-Italic.ttf        isItalic=false  getStyle()=0
//   Inter-Regular.ttf       isItalic=false  getStyle()=0
//   Inter-Bold.ttf          isItalic=false  getStyle()=0
// ```
//
// ⇒ ★ **从 `Font.createFont` 出来的字体，`isItalic()` / `getStyle()` 恒为 `false` / `0`**
//   （JDK 只有"用 `Font` 构造器 + style 参数"造出来的实例才带 style 位）。
//   ⇒ **Java 层的斜体判据完全不可用**，只能读字体文件自己的表。
//
// ## 权威判据：OS/2 `fsSelection` bit 0（ITALIC）
//
// 实测交叉表（35 条，**零分歧**）：
//
// | 字形 | `fsSelection` | `macStyle` | `post.italicAngle` |
// |---|---|---|---|
// | 斜体 | `0x0081` / `0x00A1`（bit0=1） | `2` / `3`（bit1=1） | **−9.4** |
// | 常规 | `0x00C0` / `0x00A0`（bit0=0） | `0` / `1`（bit1=0） | `0.00` |
//
// ⇒ 取 `fsSelection` bit0 与 `macStyle` bit1 的**与**（两个独立来源互相印证），
//   任一表缺失时回退到另一个。
//
// ## 为什么不用 `post.italicAngle`
//
// 它的**语义是"倾斜角"**，理论上可以"斜体但角度为 0"（合成斜体），
// 也可以"常规但带一点斜" ⇒ 不如 `fsSelection` 这种**布尔位**干脆。
// ★ 故本文件**只把它作为诊断输出**（[FontMeta.italicAngle]），不参与判定。
//
// ## 为什么不用"文件名后缀"
//
// `Inter-ThinItalic.ttf` 看起来能猜，但：
//   ① 本项目已有明确纪律 ——「族名绝不从文件名猜」（见 `font/FontLoader.kt`）；
//   ② 宿主换代可能改命名；③ 字体命名惯例本身不统一（`-Oblique` / `-It` / `-Italic`）。
// ⇒ 一律**按文件内容认**。

package dev.cdl.font

/**
 * 一个字体文件的**元数据**（只含与本项目决策相关的字段）。
 *
 * @param family 字体**内部** `name` 表给出的族名（**不是**文件名）。
 * @param subfamily `name[2]` 子族名（如 `Regular` / `Italic` / `Bold Italic`）。供诊断。
 * @param weightClass OS/2 `usWeightClass`（100..900）。★ **不是** `Font.getStyle()`。
 * @param italic 是否斜体（见文件头：`fsSelection` bit0 ∧ `macStyle` bit1）。
 * @param italicAngle `post.italicAngle`（度，斜体为负）。**仅供诊断**。
 */
data class FontMeta(
    val family: String,
    val subfamily: String?,
    val weightClass: Int,
    val italic: Boolean,
    val italicAngle: Double,
) {
    /**
     * 是否**同时**是粗体 —— 由 `usWeightClass >= 600` 判定。
     *
     * ★ 不参与斜体判定（[italic] 是独立位）；供"斜体条目显示名"用（`Inter Bold Italic`）。
     */
    val boldByWeight: Boolean get() = weightClass >= 600
}

/**
 * 从字体文件的**原始字节**读出斜体 / 字重元数据。
 *
 * ★ **纯函数、永不抛**：任何解析失败都返回 `null`（调用方回退到"按常规档处理"）。
 *
 * ## 支持的字形格式
 *
 * - `.ttf` / `.otf`（`sfntVersion` = `0x00010000` / `OTTO` / `true`）
 * - **不支持** `.ttc`（那需要 `createFonts` 逐个取）—— 宿主 jar 内实测无 `.ttc`。
 *
 * @param bytes 完整的字体文件字节。
 */
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

    // ── 斜体：两个独立来源 ────────────────────────────────────────────────
    // fsSelection bit0 = ITALIC；macStyle bit1 = italic。
    // ★ 取"任一为 1 即斜体"**太宽**（会把别的位误当斜体）⇒ 只在两表都存在时取**与**；
    //   缺一表时用另一表。实测两表在该字体集上**零分歧**（35/35 一致）。
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

/** 读 `name` 表里的 Windows 平台记录（nameID → 字符串）。`1` = family，`2` = subfamily。 */
private fun readNames(b: ByteArray, base: Int): Map<Int, String> {
    val out = HashMap<Int, String>()
    return runCatching {
        val count = u16(b, base + 2)
        val strOff = u16(b, base + 4)
        for (i in 0 until count) {
            val rec = base + 6 + i * 12
            if (rec + 12 > b.size) break
            if (u16(b, rec) != 3) continue                 // platformID 3 = Windows
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
