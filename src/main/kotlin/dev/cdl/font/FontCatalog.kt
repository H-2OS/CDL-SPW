// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.Locale

enum class FontSource {

    RECENT,

    SYSTEM,

    HOST,

    FILE,
}

data class FontCandidate(
    val family: String,
    val source: FontSource,
    val filePath: Path? = null,
)

object FontCatalog {

    fun fromRecent(historyRaw: String): List<FontCandidate> =
        FontHistory.parse(historyRaw).map { FontCandidate(it, FontSource.RECENT) }

    fun fromSystem(environment: GraphicsEnvironment): List<FontCandidate> =
        environment.availableFontFamilyNames
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .map { FontCandidate(it, FontSource.SYSTEM) }
            .toList()

    fun fromHost(sentinels: List<String>): List<FontCandidate> =
        sentinels.asSequence()
            .filter { it.isNotBlank() }
            .map { FontCandidate(it, FontSource.HOST) }
            .toList()

    fun fromFiles(
        files: List<Path>,
        registerInto: GraphicsEnvironment? = null,
    ): List<FontCandidate> {
        if (files.isEmpty()) return emptyList()

        val loader = FontLoader()
        val seen = LinkedHashSet<String>(files.size)
        val result = ArrayList<FontCandidate>(files.size)

        for (file in files) {

            val loaded: List<LoadedFont> = loader.loadFileReporting(file).loaded
            if (loaded.isEmpty()) {
                continue
            }

            if (registerInto != null) loader.registerInto(registerInto, loaded)

            for (item in loaded) {
                val family = item.familyName
                if (family.isBlank()) continue
                if (!seen.add(family)) continue
                result += FontCandidate(family, FontSource.FILE, item.source)
            }
        }
        return result
    }

    fun dedupe(candidates: List<FontCandidate>): List<FontCandidate> {
        val seen = HashSet<String>(candidates.size)
        val result = ArrayList<FontCandidate>(candidates.size)
        for (candidate in candidates) {

            val key = candidate.family.lowercase(Locale.ROOT)
            if (seen.add(key)) result += candidate
        }
        return result
    }

    fun labelOf(candidate: FontCandidate): String = when (candidate.source) {
        FontSource.RECENT -> "近期"
        FontSource.SYSTEM -> "本机"

        FontSource.HOST -> "宿主内置"
        FontSource.FILE -> candidate.filePath?.fileName?.toString()?.let { "字体包 · $it" } ?: "字体包"
    }
}
