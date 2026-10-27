// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class Preset(val name: String, val values: Map<String, Any>) {

    fun diffAgainst(other: Map<String, Any>): Map<String, Pair<Any?, Any?>> =
        (values.keys + other.keys).associateWith { it to (other[it] to values[it]) }
            .filter { (k, v) -> other[k] != values[k] && k.isNotEmpty() }
            .mapValues { (_, v) -> v }
}

object PresetStore {

    enum class Format(val extension: String) {
        JSON("json"),
        TXT("txt"),
        ;

        companion object {

            fun detect(file: File): Format =
                if (file.extension.equals("json", ignoreCase = true)) JSON else TXT
        }
    }

    data class ImportReport(
        val preset: Preset,

        val unknownKeys: List<String>,

        val unparsableKeys: List<String>,
    )

    fun directory(dataDirectory: Path?): Path? =
        dataDirectory?.resolve("presets")

    data class Listing(val presets: List<Preset>, val skipped: List<String>)

    fun list(dataDirectory: Path?): Listing {
        val dir = directory(dataDirectory) ?: return Listing(emptyList(), emptyList())
        if (!Files.isDirectory(dir)) return Listing(emptyList(), emptyList())
        val presets = ArrayList<Preset>()
        val skipped = ArrayList<String>()
        Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { path ->
                    val file = path.toFile()
                    if (file.extension.lowercase() !in setOf("json", "txt")) return@forEach
                    importFrom(file).fold(
                        onSuccess = { presets += it.preset },
                        onFailure = { skipped += file.name },
                    )
                }
        }
        return Listing(presets, skipped)
    }

    fun install(dataDirectory: Path?, source: File): Result<Path> = runCatching {
        val dir = directory(dataDirectory) ?: error("数据目录不可用，无法把预设存入列表")
        val dirAbs = dir.toAbsolutePath().normalize()
        val src = source.toPath().toAbsolutePath().normalize()

        if (src.parent == dirAbs) return@runCatching src
        Files.createDirectories(dirAbs)

        val ext = source.extension.lowercase().ifEmpty { Format.TXT.extension }
        val base = source.nameWithoutExtension.ifBlank { "预设" }
        var target = dirAbs.resolve("$base.$ext")
        var n = 2
        while (Files.exists(target)) {

            if (Files.mismatch(src, target) == -1L) return@runCatching target
            target = dirAbs.resolve("$base ($n).$ext")
            n++
        }
        Files.copy(src, target)
        target
    }

    const val DELETED_DIR = "_deleted"

    val EXCLUDED_KEYS: Set<String> = setOf(
        "window.position",
        "main_lyric.font.recent",
    )

    val GATING_KEYS_DENIED_IN_PRESET: Set<String> = setOf(
        "translation.outline.sync_with_main",
    )

    fun <V> filterForPreset(values: Map<String, V>): Map<String, V> =
        values.filterKeys { it !in EXCLUDED_KEYS }

    fun applyMapFor(preset: Preset): Map<String, Any> {
        val defaults = SettingRegistry.all.associate { it.key to it.default }
        return filterForPreset(defaults + preset.values)
    }

    const val FORMAT_ID = "cdl-preset"

    const val FORMAT_VERSION = 1

    fun isBuiltIn(name: String): Boolean = BUILT_IN.any { it.name == name }

    private val PRESET_EXTENSIONS = setOf(Format.JSON.extension, Format.TXT.extension)

    fun files(dataDirectory: Path?): List<File> {
        val dir = directory(dataDirectory)?.toFile() ?: return emptyList()
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && it.extension.lowercase() in PRESET_EXTENSIONS }
            .sortedBy { it.name }
    }

    private const val ILLEGAL_NAME_CHARS = "\\/:*?\"<>|"

    private val RESERVED_NAMES: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    fun validatePresetName(newName: String, taken: Collection<String>): String? {
        if (newName.isBlank()) return "预设名不能为空"
        if (newName.length > 80) return "预设名不能超过 80 个字符（当前 ${newName.length} 个）"
        if (newName.any { it < ' ' || it == '\u007F' }) {
            return "预设名不能含换行、制表符等控制字符"
        }
        if (newName != newName.trim()) return "预设名首尾不要留空格（空白会被当成名字的一部分）"
        if (newName.any { it in ILLEGAL_NAME_CHARS }) return "预设名不能含这些字符：\\ / : * ? \" < > |"
        if (newName.endsWith(".")) return "预设名不能以句点结尾"
        if (newName.equals(DELETED_DIR, ignoreCase = true)) {
            return "「$DELETED_DIR」是删除归档目录名，不能用作预设名"
        }
        if (newName.uppercase() in RESERVED_NAMES) {
            return "「$newName」是 Windows 保留设备名，不能用作预设名"
        }
        val clash = taken.firstOrNull { it.equals(newName, ignoreCase = true) }
        if (clash != null) return "已有同名预设「$clash」"
        return null
    }

    fun moveToDeleted(dataDirectory: Path?, file: File): Result<Path> = runCatching {
        val dir = directory(dataDirectory) ?: error("数据目录不可用，无法归档预设")
        require(file.isFile) { "预设文件不存在：${file.name}" }
        val archive = dir.resolve(DELETED_DIR)
        Files.createDirectories(archive)
        var target = archive.resolve(file.name)
        if (Files.exists(target)) {
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            target = archive.resolve("${file.nameWithoutExtension}-$stamp.${file.extension}")
        }
        Files.move(file.toPath(), target)
        target
    }

    private fun backupBeforeOverwrite(file: File) {
        val dir = file.parentFile ?: return

        val archive = File(dir, DELETED_DIR)
        archive.mkdirs()
        var target = File(archive, file.name)
        if (target.exists()) {
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            target = File(archive, "${file.nameWithoutExtension}-$stamp.${file.extension}")
        }
        target.parentFile?.mkdirs()
        Files.copy(file.toPath(), target.toPath())
    }

    fun overwrite(file: File, values: Map<String, Any>): Result<Path> = runCatching {
        require(file.isFile) { "预设文件不存在：${file.name}" }
        val ext = file.extension.ifEmpty { Format.TXT.extension }
        val parent = file.parentFile?.parentFile ?: error("预设目录异常，无法定位数据目录")
        val scratch = File(parent, "${file.nameWithoutExtension}.tmp-${System.nanoTime()}.$ext")
        try {
            exportTo(scratch, file.nameWithoutExtension, values).getOrThrow()

            backupBeforeOverwrite(file)
            Files.move(
                scratch.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (scratch.exists()) scratch.delete()
        }
        file.toPath()
    }

    fun rename(dataDirectory: Path?, file: File, newName: String): Result<Path> = runCatching {
        require(file.isFile) { "预设文件不存在：${file.name}" }
        val dir = directory(dataDirectory) ?: error("数据目录不可用，无法改名")

        val taken = files(dataDirectory)
            .filter { it.absolutePath != file.absolutePath }
            .map { it.nameWithoutExtension } + BUILT_IN.map { it.name }

        val problem = validatePresetName(newName, taken)
        if (problem != null) error(problem)
        val name = newName.trim()
        val ext = file.extension.ifEmpty { Format.TXT.extension }
        val target = dir.resolve("$name.$ext")

        val sameFile = runCatching {
            Files.exists(target) && Files.isSameFile(target, file.toPath())
        }.getOrDefault(false)
        if (Files.exists(target) && !sameFile) {
            error("已有同名预设「$name」，未改名（避免覆盖）")
        }
        Files.move(file.toPath(), target)
        target
    }

    fun matches(preset: Preset, current: Map<String, Any?>): Boolean {
        if (preset.values.isEmpty()) return false

        val declaredOk = preset.values.all { (k, v) ->
            val cur = current[k]
            cur == v || cur?.toString() == v.toString()
        }
        if (!declaredOk) return false

        return current.all { (k, cur) ->
            k in EXCLUDED_KEYS ||
                preset.values.containsKey(k) ||
                run {
                    val descriptor = SettingRegistry.descriptor(k) ?: return@run true
                    if (descriptor.retired) return@run true
                    val default = descriptor.default
                    cur == default || cur?.toString() == default?.toString()
                }
        }
    }

    fun exportTo(file: File, name: String, values: Map<String, Any>): Result<Path> = runCatching {
        file.parentFile?.mkdirs()
        val text = when (Format.detect(file)) {
            Format.JSON -> toJson(name, values)
            Format.TXT -> toTxt(name, values)
        }

        val ext = file.extension
        val scratch = File(
            file.parentFile,
            file.nameWithoutExtension + ".tmp-" + System.nanoTime() +
                (if (ext.isEmpty()) "" else ".$ext"),
        )
        try {
            scratch.writeText(text, Charsets.UTF_8)
            Files.move(
                scratch.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (scratch.exists()) scratch.delete()
        }
        file.toPath()
    }

    fun importFrom(file: File): Result<ImportReport> = runCatching {
        require(file.isFile) { "文件不存在：${file.absolutePath}" }
        val text = file.readText(Charsets.UTF_8)
        val raw = when (Format.detect(file)) {
            Format.JSON -> parseJson(text)
            Format.TXT -> parseTxt(text)
        }

        val name = file.nameWithoutExtension
        val known = raw.filterKeys { SettingRegistry.descriptor(it) != null }
        val unknown = raw.keys.filter { SettingRegistry.descriptor(it) == null }.sorted()

        val typed = HashMap<String, Any>(known.size)
        val unparsable = ArrayList<String>()
        for ((k, v) in known) {
            val d = SettingRegistry.descriptor(k)!!
            val coerced = coerceByKind(d, v)
            if (coerced != null) typed[k] = coerced else unparsable.add(k)
        }

        require(raw.isNotEmpty()) {
            "没有解析出任何设置项（文件为空或不是预设格式）"
        }
        ImportReport(Preset(name, typed), unknown, unparsable.sorted())
    }

    fun coerceByKind(descriptor: SettingDescriptor, value: Any): Any? = when (descriptor.kind) {
        SettingKind.SWITCH -> when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
            is Number -> value.toInt() != 0
            else -> null
        }
        SettingKind.SEEKBAR -> {
            val n = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }

            val r = descriptor.range
            if (n == null || !n.isFinite()) {
                null
            } else {
                val i = n.toInt()
                if (r != null && (i < r.start || i > r.endInclusive)) null else i
            }
        }
        SettingKind.COLOR -> when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
                ?: dev.cdl.settings.parseHexOrNull(value.trim())
            else -> null
        }

        SettingKind.LIST -> {
            val text = value.toString().trim()
            val options = descriptor.options
            if (options != null && options.none { it.first == text }) null else text
        }

        SettingKind.EDITTEXT, SettingKind.FONT -> value.toString()
    }

    internal fun toJson(name: String, values: Map<String, Any>): String = buildString {
        appendLine("{")
        appendLine("  \"format\": \"$FORMAT_ID\",")
        appendLine("  \"version\": $FORMAT_VERSION,")
        appendLine("  \"name\": \"${jsonEscape(name)}\",")
        appendLine("  \"values\": {")
        val keys = values.keys.sorted()
        keys.forEachIndexed { i, k ->
            val v = values[k]
            append("    \"${jsonEscape(k)}\": ")
            append(jsonValue(v))
            appendLine(if (i == keys.lastIndex) "" else ",")
        }
        appendLine("  }")
        append("}")
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> v.toString()
        else -> "\"${jsonEscape(v.toString())}\""
    }

    fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    fun jsonUnescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { append(c); i++; continue }
            when (val e = s[i + 1]) {
                '"' -> { append('"'); i += 2 }
                '\\' -> { append('\\'); i += 2 }
                'n' -> { append('\n'); i += 2 }
                'r' -> { append('\r'); i += 2 }
                't' -> { append('\t'); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) { append(code.toChar()); i += 6 } else { append(e); i += 2 }
                }
                else -> { append(e); i += 2 }
            }
        }
    }

    internal fun parseJson(text: String): Map<String, Any> {
        val start = text.indexOf("\"values\"")
        require(start >= 0) { "不是有效的预设文件：找不到 \"values\" 段" }

        val header = text.substring(0, start)
        val formatFound = Regex("\"format\"\\s*:\\s*\"([^\"]*)\"")
            .find(header)?.groupValues?.get(1)
        require(formatFound == FORMAT_ID) {
            "不是本插件的预设文件：format=${formatFound ?: "（缺失）"}，应为 \"$FORMAT_ID\""
        }
        val versionFound = Regex("\"version\"\\s*:\\s*(-?\\d+)")
            .find(header)?.groupValues?.get(1)
        require(versionFound != null) { "不是本插件的预设文件：缺少 version 字段" }
        val version = versionFound.toInt()

        require(version <= FORMAT_VERSION) {
            "预设文件的版本 $version 高于本插件支持的 $FORMAT_VERSION，请先升级插件再导入"
        }

        val brace = text.indexOf('{', start)
        require(brace >= 0) { "不是有效的预设文件：\"values\" 后没有对象" }

        require(text.lastIndexOf('}') > brace) { "不是有效的预设文件：\"values\" 对象没有闭合" }
        return parseFlatObject(text.substring(brace + 1))
    }

    private fun parseFlatObject(body: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= body.length || body[i] == '}') break
            if (body[i] != '"') break
            val k = readJsonString(body, i) ?: break
            i = k.second
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != ':') break
            i++
            while (i < body.length && body[i].isWhitespace()) i++
            val raw: String
            if (i < body.length && body[i] == '"') {
                val v = readJsonString(body, i) ?: break
                raw = body.substring(i, v.second)
                i = v.second
            } else {
                val st = i
                while (i < body.length && body[i] != ',' && body[i] != '}') i++
                raw = body.substring(st, i).trim()
            }
            out[jsonUnescape(k.first)] = parseJsonScalar(raw)
        }
        return out
    }

    private fun readJsonString(s: String, at: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = at + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(c).append(s[i + 1]); i += 2; continue
            }
            if (c == '"') return sb.toString() to (i + 1)
            sb.append(c); i++
        }
        return null
    }

    private fun parseJsonScalar(raw: String): Any = when {
        raw == "null" -> ""
        raw == "true" -> true
        raw == "false" -> false
        raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
            jsonUnescape(raw.substring(1, raw.length - 1))
        raw.toLongOrNull() != null -> raw.toLong()
        raw.toDoubleOrNull() != null -> raw.toDouble()
        else -> raw
    }

    internal fun toTxt(name: String, values: Map<String, Any>): String = buildString {
        appendLine("# CDL for SPW（测试版） · 配置预设")
        appendLine("# 预设名：$name")
        appendLine("# 格式：每行 `键=值`；以 # 开头的是注释。**可直接手改**。")
        appendLine("# ⚠️ `=` 两侧**不要加空格** —— 值里的首尾空白是数据的一部分，会被原样保留。")
        appendLine()
        values.keys.sorted().forEach { k ->
            val v = values[k]?.toString() ?: ""

            appendLine("$k=${txtEscape(v)}")
        }
    }

    internal fun txtEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    internal fun txtUnescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { append(c); i++; continue }
            when (val e = s[i + 1]) {
                '\\' -> { append('\\'); i += 2 }
                'n' -> { append('\n'); i += 2 }
                'r' -> { append('\r'); i += 2 }

                else -> { append(c); append(e); i += 2 }
            }
        }
    }

    internal fun parseTxt(text: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (line in text.lines()) {

            val raw = line.removeSuffix("\r")
            val eq = raw.indexOf('=')
            if (eq <= 0) continue
            val key = raw.substring(0, eq).trim()

            if (key.isEmpty() || key.startsWith("#")) continue
            out[key] = txtUnescape(raw.substring(eq + 1))
        }
        return out
    }

    val BUILT_IN: List<Preset> = listOf(
        Preset("默认（全部复原）", emptyMap()),

        Preset(
            "经典椒盐",
            mapOf(
                "animation.duration_scale" to 100,
                "animation.enabled" to true,
                "animation.frame_log" to false,
                "animation.frame_rate" to "AUTO",
                "animation.quality" to "HIGH",
                "animation.style" to "PER_COMPONENT",
                "background.color" to "#14161A",
                "background.color_mode" to "manual",
                "background.cover.angle_deg" to 0,
                "background.opacity" to 0,
                "clock.hour_format" to "H24",
                "clock.style" to "SEGMENT",
                "controls.animation_style" to "FOLLOW",
                "controls.autoHideMs" to 0,
                "controls.enterDelayMs" to 120,
                "controls.exitDelayMs" to 250,
                "controls.gapPx" to 0,
                "controls.hitPaddingPx" to 16,
                "controls.position" to "TOP",
                "controls.progress_style" to "GLOW",
                "controls.showProgress" to true,
                "controls.triggerMode" to "ISLAND_HIT",
                "cover.enabled" to false,
                "cover.lock" to false,
                "cover.rotate" to false,
                "cover.rotate_period_s" to 20,
                "cover.scale_with_font" to true,
                "cover.shape" to "ROUNDED",
                "cover.sharpness" to 25,
                "cover.vinyl" to false,
                "island.always_on_top" to true,
                "island.auto_hide_on_hover" to false,
                "island.border.color.b" to 255,
                "island.border.color.g" to 255,
                "island.border.color.r" to 255,
                "island.border.color_mode" to "manual",
                "island.border.dissolve_band_percent" to 50,
                "island.border.enabled" to false,
                "island.border.mode" to "SOLID",
                "island.border.width_tenth_px" to 10,
                "island.enabled" to true,
                "island.hide_fullscreen" to true,
                "island.hide_paused" to false,
                "island.hit.cover" to false,
                "island.interactive" to false,
                "island.lock_position" to false,
                "island.right_slot.component" to "NONE",
                "island.right_slot.polar_outer_percent" to 100,
                "island.right_slot.radial_inner_percent" to 0,
                "island.right_slot.ring_inner_percent" to 55,
                "island.right_slot.spectrum_bars" to "5",
                "island.right_slot.spectrum_color" to "#84D8BC",
                "island.right_slot.spectrum_color_mode" to "follow",
                "layout.content_align" to "CENTER",
                "layout.fixed_width" to false,
                "layout.island.height.follow_font" to true,
                "layout.island.height.ratio_percent" to 120,
                "layout.line_gap_px" to 0,
                "layout.lines" to "DUAL",
                "layout.max_width" to 1400,
                "layout.noLyrics.hideWhenInstrumental" to false,
                "layout.noLyrics.mode" to "PLACEHOLDER",
                "layout.noLyrics.showAlbum" to false,
                "layout.noLyrics.showTrackTitle" to true,
                "layout.noLyrics.titleInTranslation" to false,
                "layout.translation.enabled" to true,
                "layout.translation.position" to "BELOW",
                "layout.translation.scale_percent" to 100,
                "lyric.animation_style" to "FOLLOW",
                "lyric.offset_ms" to 0,
                "main_lyric.color_mode" to "manual",
                "main_lyric.fill.color.b" to 255,
                "main_lyric.fill.color.g" to 255,
                "main_lyric.fill.color.lightness" to 100,
                "main_lyric.fill.color.r" to 255,
                "main_lyric.fill.unsung.b" to 138,
                "main_lyric.fill.unsung.g" to 129,
                "main_lyric.fill.unsung.lightness" to 100,
                "main_lyric.fill.unsung.r" to 126,
                "main_lyric.font.family" to "@host:MiSans",
                "main_lyric.font.size" to 22,
                "main_lyric.font.weight" to "BOLD",
                "main_lyric.karaoke.mode" to "SMOOTH",
                "main_lyric.letter_spacing_tenth_px" to 0,
                "main_lyric.outline.color.b" to 255,
                "main_lyric.outline.color.g" to 255,
                "main_lyric.outline.color.r" to 255,
                "main_lyric.outline.enabled" to false,
                "main_lyric.outline.follow_font_weight" to false,
                "main_lyric.outline.weight_link_percent" to 50,
                "main_lyric.outline.width_tenth_px" to 10,
                "main_lyric.translation.color.b" to 195,
                "main_lyric.translation.color.g" to 182,
                "main_lyric.translation.color.lightness" to 100,
                "main_lyric.translation.color.r" to 177,
                "main_lyric.translation.color_mode" to "manual",
                "material.content.blur_radius" to 0,
                "material.fill.color_end" to "#2A2E38",
                "material.fill.flow_speed" to 15,
                "material.fill.gradient_angle_deg" to 90,
                "material.fill.gradient_style" to "NONE",
                "material.fill.gradient_tint_percent" to 30,
                "material.highlight.strength" to 0,
                "material.liquid.depth_effect" to false,
                "material.liquid.glare_strength" to 0,
                "material.liquid.shadow_strength" to 0,
                "material.liquid.surface_alpha" to 0,
                "material.noise.amplitude" to 0,
                "material.quality" to "FULL",
                "material.render.gpu_backend" to false,
                "menu.opacity_pct" to 100,
                "menu.submenu_close_delay_ms" to 250,
                "menu.submenu_open_delay_ms" to 180,
                "settings.auto_ungate_on_touch" to true,
                "shape.corner_n" to 4,
                "shape.corner_radius" to 60,
                "shape.corner_style" to "ARC",
                "shape.type" to "SQUARE",
                "spectrum.anchor" to "BOTTOM",
                "spectrum.bar_pitch_mode" to "FIXED",
                "spectrum.bar_pitch_tenth_px" to 70,
                "spectrum.bar_shape" to "ROUND",
                "spectrum.bounce_pct" to 100,
                "spectrum.color" to "#84D8BC",
                "spectrum.color_mode" to "manual",
                "spectrum.enabled" to false,
                "spectrum.fade_top" to true,
                "spectrum.height_percent" to 100,
                "spectrum.opacity_pct" to 75,
                "spectrum.progress_tint" to false,
                "spectrum.progress_tint_alpha" to 100,
                "spectrum.secondary.mode" to "OFF",
                "spectrum.secondary.opacity_percent" to 35,
                "spectrum.symmetric" to false,
                "translation.letter_spacing.follow_main" to true,
                "translation.outline.color.b" to 255,
                "translation.outline.color.g" to 255,
                "translation.outline.color.r" to 255,
                "translation.outline.enabled" to false,
                "translation.outline.follow_weight" to false,

                "translation.outline.width_tenth_px" to 10,
                "playback.autoplay_on_start" to true,
                "island.lock_collapsed" to false,
                "wheel.volume.enabled" to false,
                "wheel.volume.step_percent" to 5,
                "glass.blur_radius" to 10,
                "glass.clarity" to "HIGH",
                "glass.dispersion" to 35,
                "glass.saturation" to 150,
                "glass.surface_alpha" to 10,
                "glass.material" to "NONE",
                "animation.width_grow.enabled" to true,
                "animation.width_grow.duration_ms" to 220,
                "animation.width_grow.style" to "SMOOTH",
                "island.right_slot.polar_style" to "BARS",
                "island.right_slot.spectrum_bar_pitch_tenth_px" to 70,
                "spectrum.secondary.bar_pitch_tenth_px" to 70,
                "island.right_slot.spectrum_opacity_pct" to 100,
            ),
        ),
        Preset(
            "实验预设",
            mapOf(
                "animation.duration_scale" to 500,
                "animation.enabled" to true,
                "animation.frame_log" to false,
                "animation.frame_rate" to "AUTO",
                "animation.quality" to "HIGH",
                "animation.style" to "PER_COMPONENT",
                "background.color" to "#14161A",
                "background.color_mode" to "manual",
                "background.cover.angle_deg" to 0,
                "background.opacity" to 80,
                "clock.hour_format" to "H24",
                "clock.style" to "SEGMENT",
                "controls.animation_style" to "FOLLOW",
                "controls.autoHideMs" to 0,
                "controls.enterDelayMs" to 120,
                "controls.exitDelayMs" to 250,
                "controls.gapPx" to 0,
                "controls.hitPaddingPx" to 16,
                "controls.position" to "BOTTOM",
                "controls.progress_style" to "GLOW",
                "controls.showProgress" to true,
                "controls.triggerMode" to "GLYPH_HIT",
                "cover.enabled" to true,
                "cover.lock" to false,
                "cover.rotate" to true,
                "cover.rotate_period_s" to 25,
                "cover.scale_with_font" to true,
                "cover.shape" to "CIRCLE",
                "cover.sharpness" to 25,
                "cover.vinyl" to true,
                "island.always_on_top" to true,
                "island.auto_hide_on_hover" to false,
                "island.border.color.b" to 255,
                "island.border.color.g" to 255,
                "island.border.color.r" to 255,
                "island.border.color_mode" to "manual",
                "island.border.dissolve_band_percent" to 15,
                "island.border.enabled" to true,
                "island.border.mode" to "EDGE_DISSOLVE",
                "island.border.width_tenth_px" to 10,
                "island.enabled" to true,
                "island.hide_fullscreen" to true,
                "island.hide_paused" to false,
                "island.hit.cover" to false,
                "island.interactive" to false,
                "island.lock_position" to false,
                "island.right_slot.component" to "SPECTRUM",
                "island.right_slot.polar_outer_percent" to 100,
                "island.right_slot.radial_inner_percent" to 0,
                "island.right_slot.ring_inner_percent" to 55,
                "island.right_slot.spectrum_bars" to "5",
                "island.right_slot.spectrum_color" to "#84D8BC",
                "island.right_slot.spectrum_color_mode" to "cover",
                "layout.content_align" to "CENTER",
                "layout.fixed_width" to false,
                "layout.island.height.follow_font" to true,
                "layout.island.height.ratio_percent" to 125,
                "layout.line_gap_px" to 0,
                "layout.lines" to "DUAL",
                "layout.max_width" to 1400,
                "layout.noLyrics.hideWhenInstrumental" to false,
                "layout.noLyrics.mode" to "PLACEHOLDER",
                "layout.noLyrics.showAlbum" to false,
                "layout.noLyrics.showTrackTitle" to true,
                "layout.noLyrics.titleInTranslation" to false,
                "layout.translation.enabled" to true,
                "layout.translation.position" to "BELOW",
                "layout.translation.scale_percent" to 100,
                "lyric.animation_style" to "FADE",
                "lyric.offset_ms" to 0,
                "main_lyric.color_mode" to "manual",
                "main_lyric.fill.color.b" to 255,
                "main_lyric.fill.color.g" to 255,
                "main_lyric.fill.color.lightness" to 100,
                "main_lyric.fill.color.r" to 255,
                "main_lyric.fill.unsung.b" to 138,
                "main_lyric.fill.unsung.g" to 129,
                "main_lyric.fill.unsung.lightness" to 100,
                "main_lyric.fill.unsung.r" to 126,
                "main_lyric.font.family" to "@host:MiSans",
                "main_lyric.font.size" to 22,
                "main_lyric.font.weight" to "NORMAL",
                "main_lyric.karaoke.mode" to "SMOOTH",
                "main_lyric.letter_spacing_tenth_px" to 0,
                "main_lyric.outline.color.b" to 45,
                "main_lyric.outline.color.g" to 45,
                "main_lyric.outline.color.r" to 45,
                "main_lyric.outline.enabled" to true,
                "main_lyric.outline.follow_font_weight" to false,
                "main_lyric.outline.weight_link_percent" to 50,
                "main_lyric.outline.width_tenth_px" to 10,
                "main_lyric.translation.color.b" to 195,
                "main_lyric.translation.color.g" to 182,
                "main_lyric.translation.color.lightness" to 100,
                "main_lyric.translation.color.r" to 177,
                "main_lyric.translation.color_mode" to "manual",
                "material.content.blur_radius" to 0,
                "material.fill.color_end" to "#2A2E38",
                "material.fill.flow_speed" to 20,
                "material.fill.gradient_angle_deg" to 90,
                "material.fill.gradient_style" to "CAMO",
                "material.fill.gradient_tint_percent" to 30,
                "material.highlight.strength" to 0,
                "material.liquid.depth_effect" to false,
                "material.liquid.glare_strength" to 0,
                "material.liquid.shadow_strength" to 0,
                "material.liquid.surface_alpha" to 0,
                "material.noise.amplitude" to 0,
                "material.quality" to "FULL",
                "material.render.gpu_backend" to false,
                "menu.opacity_pct" to 85,
                "menu.submenu_close_delay_ms" to 250,
                "menu.submenu_open_delay_ms" to 180,
                "settings.auto_ungate_on_touch" to true,
                "shape.corner_n" to 4,
                "shape.corner_radius" to 60,
                "shape.corner_style" to "ARC",
                "shape.type" to "PILL",
                "spectrum.anchor" to "BOTTOM",
                "spectrum.bar_pitch_mode" to "FIXED",
                "spectrum.bar_pitch_tenth_px" to 70,
                "spectrum.bar_shape" to "ROUND",
                "spectrum.bounce_pct" to 100,
                "spectrum.color" to "#84D8BC",
                "spectrum.color_mode" to "manual",
                "spectrum.enabled" to false,
                "spectrum.fade_top" to true,
                "spectrum.height_percent" to 100,
                "spectrum.opacity_pct" to 75,
                "spectrum.progress_tint" to false,
                "spectrum.progress_tint_alpha" to 100,
                "spectrum.secondary.mode" to "OFF",
                "spectrum.secondary.opacity_percent" to 35,
                "spectrum.symmetric" to false,
                "translation.letter_spacing.follow_main" to true,
                "translation.outline.color.b" to 255,
                "translation.outline.color.g" to 255,
                "translation.outline.color.r" to 255,
                "translation.outline.enabled" to true,
                "translation.outline.follow_weight" to false,

                "translation.outline.width_tenth_px" to 10,
                "playback.autoplay_on_start" to true,
                "island.lock_collapsed" to false,
                "wheel.volume.enabled" to false,
                "wheel.volume.step_percent" to 5,
                "glass.blur_radius" to 10,
                "glass.clarity" to "HIGH",
                "glass.dispersion" to 35,
                "glass.saturation" to 150,
                "glass.surface_alpha" to 10,
                "glass.material" to "NONE",
                "animation.width_grow.enabled" to true,
                "animation.width_grow.duration_ms" to 220,
                "animation.width_grow.style" to "SMOOTH",
                "island.right_slot.polar_style" to "BARS",
                "island.right_slot.spectrum_bar_pitch_tenth_px" to 70,
                "spectrum.secondary.bar_pitch_tenth_px" to 70,
                "island.right_slot.spectrum_opacity_pct" to 100,
            ),
        ),

        Preset(
            "玻璃示例",
            mapOf(
                "animation.duration_scale" to 200,
                "animation.enabled" to true,
                "animation.frame_log" to false,
                "animation.frame_rate" to "AUTO",
                "animation.quality" to "HIGH",
                "animation.style" to "FADE",
                "animation.width_grow.duration_ms" to 220,
                "animation.width_grow.enabled" to true,
                "animation.width_grow.style" to "SMOOTH",
                "background.color" to "#14161A",
                "background.color_mode" to "manual",
                "background.cover.angle_deg" to 0,
                "background.opacity" to 96,
                "clock.hour_format" to "H24",
                "clock.style" to "SEGMENT",
                "controls.animation_style" to "FOLLOW",
                "controls.autoHideMs" to 0,
                "controls.enterDelayMs" to 120,
                "controls.exitDelayMs" to 250,
                "controls.gapPx" to 8,
                "controls.hitPaddingPx" to 2,
                "controls.position" to "BOTTOM",
                "controls.progress_style" to "CLASSIC",
                "controls.showProgress" to true,
                "controls.triggerMode" to "GLYPH_HIT",
                "cover.enabled" to true,
                "cover.lock" to false,
                "cover.rotate" to true,
                "cover.rotate_period_s" to 20,
                "cover.scale_with_font" to true,
                "cover.shape" to "CIRCLE",
                "cover.sharpness" to 25,
                "cover.vinyl" to true,
                "glass.blur_radius" to 16,
                "glass.clarity" to "MAX",
                "glass.dispersion" to 35,
                "glass.material" to "LIQUID",
                "glass.saturation" to 150,
                "glass.surface_alpha" to 0,
                "island.always_on_top" to true,
                "island.auto_hide_on_hover" to false,
                "island.border.color.b" to 255,
                "island.border.color.g" to 255,
                "island.border.color.r" to 255,
                "island.border.color_mode" to "manual",
                "island.border.dissolve_band_percent" to 50,
                "island.border.enabled" to false,
                "island.border.mode" to "SOLID",
                "island.border.width_tenth_px" to 10,
                "island.enabled" to true,
                "island.hide_fullscreen" to true,
                "island.hide_paused" to false,
                "island.hit.cover" to true,
                "island.interactive" to false,
                "island.lock_collapsed" to false,
                "island.lock_position" to false,
                "island.right_slot.component" to "SPECTRUM",
                "island.right_slot.polar_outer_percent" to 100,
                "island.right_slot.polar_style" to "BARS",
                "island.right_slot.radial_inner_percent" to 0,
                "island.right_slot.ring_inner_percent" to 55,
                "island.right_slot.spectrum_bar_pitch_tenth_px" to 70,
                "island.right_slot.spectrum_bars" to "5",
                "island.right_slot.spectrum_color" to "#84D8BC",
                "island.right_slot.spectrum_color_mode" to "cover_camo",
                "island.right_slot.spectrum_opacity_pct" to 100,
                "layout.content_align" to "CENTER",
                "layout.fixed_width" to false,
                "layout.island.height.follow_font" to true,
                "layout.island.height.ratio_percent" to 120,
                "layout.line_gap_px" to 0,
                "layout.lines" to "DUAL",
                "layout.max_width" to 1200,
                "layout.noLyrics.hideWhenInstrumental" to false,
                "layout.noLyrics.mode" to "PLACEHOLDER",
                "layout.noLyrics.showAlbum" to false,
                "layout.noLyrics.showTrackTitle" to true,
                "layout.noLyrics.titleInTranslation" to false,
                "layout.translation.enabled" to true,
                "layout.translation.position" to "BELOW",
                "layout.translation.scale_percent" to 100,
                "lyric.animation_style" to "FOLLOW",
                "lyric.offset_ms" to 0,
                "main_lyric.color_mode" to "manual",
                "main_lyric.fill.color.b" to 255,
                "main_lyric.fill.color.g" to 255,
                "main_lyric.fill.color.lightness" to 100,
                "main_lyric.fill.color.r" to 255,
                "main_lyric.fill.unsung.b" to 138,
                "main_lyric.fill.unsung.g" to 129,
                "main_lyric.fill.unsung.lightness" to 100,
                "main_lyric.fill.unsung.r" to 126,
                "main_lyric.font.family" to "@host:MiSans",
                "main_lyric.font.size" to 22,
                "main_lyric.font.weight" to "NORMAL",
                "main_lyric.karaoke.mode" to "SMOOTH",
                "main_lyric.letter_spacing_tenth_px" to 0,
                "main_lyric.outline.color.b" to 255,
                "main_lyric.outline.color.g" to 255,
                "main_lyric.outline.color.r" to 255,
                "main_lyric.outline.enabled" to false,
                "main_lyric.outline.follow_font_weight" to false,
                "main_lyric.outline.weight_link_percent" to 50,
                "main_lyric.outline.width_tenth_px" to 10,
                "main_lyric.translation.color.b" to 195,
                "main_lyric.translation.color.g" to 182,
                "main_lyric.translation.color.lightness" to 100,
                "main_lyric.translation.color.r" to 177,
                "main_lyric.translation.color_mode" to "manual",
                "material.content.blur_radius" to 0,
                "material.fill.color_end" to "#2A2E38",
                "material.fill.flow_speed" to 15,
                "material.fill.gradient_angle_deg" to 90,
                "material.fill.gradient_style" to "NONE",
                "material.fill.gradient_tint_percent" to 30,
                "material.highlight.strength" to 0,
                "material.liquid.depth_effect" to true,
                "material.liquid.glare_strength" to 60,
                "material.liquid.shadow_strength" to 0,
                "material.liquid.surface_alpha" to 10,
                "material.noise.amplitude" to 0,
                "material.quality" to "FULL",
                "material.render.gpu_backend" to false,
                "menu.opacity_pct" to 100,
                "menu.submenu_close_delay_ms" to 450,
                "menu.submenu_open_delay_ms" to 150,
                "playback.autoplay_on_start" to true,
                "settings.auto_ungate_on_touch" to true,
                "shape.corner_n" to 4,
                "shape.corner_radius" to 100,
                "shape.corner_style" to "ARC",
                "shape.type" to "PILL",
                "spectrum.anchor" to "BOTTOM",
                "spectrum.bar_pitch_mode" to "AUTO",
                "spectrum.bar_pitch_tenth_px" to 70,
                "spectrum.bar_shape" to "ROUND",
                "spectrum.bounce_pct" to 100,
                "spectrum.color" to "#84D8BC",
                "spectrum.color_mode" to "manual",
                "spectrum.enabled" to false,
                "spectrum.fade_top" to true,
                "spectrum.height_percent" to 100,
                "spectrum.opacity_pct" to 75,
                "spectrum.progress_tint" to false,
                "spectrum.progress_tint_alpha" to 100,
                "spectrum.secondary.bar_pitch_tenth_px" to 70,
                "spectrum.secondary.mode" to "OFF",
                "spectrum.secondary.opacity_percent" to 35,
                "spectrum.symmetric" to false,
                "translation.letter_spacing.follow_main" to true,
                "translation.outline.color.b" to 255,
                "translation.outline.color.g" to 255,
                "translation.outline.color.r" to 255,
                "translation.outline.enabled" to false,
                "translation.outline.follow_weight" to false,
                "translation.outline.width_tenth_px" to 10,
                "wheel.volume.enabled" to false,
                "wheel.volume.step_percent" to 5,
            ),
        ),
    )
}
