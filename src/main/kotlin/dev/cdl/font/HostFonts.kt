// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

object HostFonts {

    const val PREFIX: String = "@host:"

    const val MI_SANS: String = PREFIX + "MiSans"

    val SENTINELS: List<String>
        get() {
            val rest = cachedFonts.orEmpty().keys
                .filter { it != "MiSans" }
                .sortedWith(hostFamilyOrder)
                .map { PREFIX + it }
            return listOf(MI_SANS) + rest
        }

    internal val hostFamilyOrder: Comparator<String> = Comparator { a, b ->
        val rootA = a.removeSuffix(ITALIC_SUFFIX)
        val rootB = b.removeSuffix(ITALIC_SUFFIX)
        val byRoot = rootA.compareTo(rootB)
        if (byRoot != 0) byRoot
        else {
            val italicA = a.endsWith(ITALIC_SUFFIX)
            val italicB = b.endsWith(ITALIC_SUFFIX)
            if (italicA != italicB) if (italicA) 1 else -1 else a.compareTo(b)
        }
    }

    private val JAR_NAME_CANDIDATES: List<String> =
        listOf("ffmpeg-x64.dll", "app/ffmpeg-x64.dll")

    private val FONT_SUFFIXES: List<String> = listOf(".ttf", ".otf")

    const val ITALIC_SUFFIX: String = " Italic"

    @Volatile
    private var cachedFonts: Map<String, Font>? = null

    @Volatile
    private var cachedFailure: String? = null

    val lastFailureReason: String? get() = cachedFailure

    val isResolved: Boolean get() = cachedFonts != null

    @Volatile
    private var cachedMeta: Map<String, FontMeta>? = null



    val hostFamilies: List<String> get() = cachedFonts?.keys?.sortedWith(hostFamilyOrder).orEmpty()

    fun isSentinel(family: String?): Boolean =
        family != null && family.startsWith(PREFIX)

    fun sentinelFamily(family: String): String =
        if (isSentinel(family)) family.removePrefix(PREFIX) else family

    fun displayName(family: String): String {
        if (!isSentinel(family)) return family
        val raw = sentinelFamily(family)
        return if (raw.endsWith(ITALIC_SUFFIX)) {
            "宿主内置 " + raw.removeSuffix(ITALIC_SUFFIX) + "（斜体）"
        } else {
            "宿主内置 $raw"
        }
    }

    fun resolveOrNull(family: String?): Font? {
        if (!isSentinel(family)) return null

        val name = sentinelFamily(family ?: return null)
        return cachedFonts?.get(name)
    }

    fun warmUpAsync() {
        if (cachedFonts != null || cachedFailure != null) return
        val t = Thread({ runCatching { scanAll() } }, "cdl-hostfont")
        t.isDaemon = true
        t.start()
    }


    private fun scanAll() {
        if (cachedFonts != null || cachedFailure != null) return
        val jar = locateHostJar()
        if (jar == null) {
            fail("定位不到宿主 jar（skiko.library.path=${System.getProperty("skiko.library.path")}）")
            return
        }
        val out = LinkedHashMap<String, Font>()
        var seen = 0
        var bad = 0

        val collected = ArrayList<Triple<String, Font, ByteArray>>()
        runCatching {
            ZipFile(jar.toFile()).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val name = en.nextElement().name
                    val ln = name.lowercase()
                    if (FONT_SUFFIXES.none { ln.endsWith(it) }) continue
                    seen++
                    runCatching {
                        val bytes = zf.getInputStream(zf.getEntry(name)).use { it.readAllBytes() }
                        val f = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes))

                        collected += Triple(f.getFamily(), f, bytes)
                    }.onFailure { bad++ }
                }
            }
        }.onFailure {
            fail("读宿主 jar 失败：${it.javaClass.simpleName}: ${it.message}")
            return
        }

        val italicFlags = BooleanArray(collected.size)
        val metas = ArrayList<FontMeta?>(collected.size)
        for (i in collected.indices) {
            val m = runCatching { readFontMeta(collected[i].third) }.getOrNull()
            metas += m
            italicFlags[i] = m?.italic ?: false
        }

        val metaOut = HashMap<String, FontMeta>()

        for (i in collected.indices) {
            if (italicFlags[i]) continue
            val (family, font, _) = collected[i]
            out.putIfAbsent(family, font)
            metas[i]?.let { metaOut.putIfAbsent(family, it) }
        }

        for (i in collected.indices) {
            if (!italicFlags[i]) continue
            val (family, font, _) = collected[i]
            out.putIfAbsent(family + ITALIC_SUFFIX, font)
            metas[i]?.let { metaOut.putIfAbsent(family + ITALIC_SUFFIX, it) }
        }
        if (out.isEmpty()) {
            fail("宿主 jar 内没有可解析的字体（条目 $seen 个，全部失败）")
            return
        }
        cachedFonts = out
        cachedMeta = metaOut
        val italicCount = out.keys.count { it.endsWith(ITALIC_SUFFIX) }
    }

    private fun locateHostJar(): Path? {
        val roots = mutableListOf<Path>()

        System.getProperty("skiko.library.path")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.takeIf { Files.isDirectory(it) }
            ?.let { roots.add(it) }

        runCatching {
            Path.of(System.getProperty("java.home") ?: "").parent?.let { roots.add(it) }
        }
        for (root in roots) {
            for (name in JAR_NAME_CANDIDATES) {
                val f = root.resolve(name)
                if (Files.isRegularFile(f) && looksLikeZip(f)) return f
            }
        }
        return null
    }

    private fun looksLikeZip(f: Path): Boolean = runCatching {
        Files.newInputStream(f).use { ins ->
            val h = ins.readNBytes(2)
            h.size == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun fail(reason: String) {
        cachedFailure = reason
    }

}
