// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class LoadedFont(
    val familyName: String,
    val font: Font,
    val source: Path,
)

class FontLoader {

    fun loadFrom(directory: Path): List<LoadedFont> = loadFromReporting(directory).loaded

    fun loadFromReporting(directory: Path): ScanResult {

        val entries = runCatching {
            Files.newDirectoryStream(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        }.getOrElse {
            return ScanResult(emptyList(), emptyList())
        }

        val loaded = ArrayList<LoadedFont>()
        val failed = ArrayList<LoadFailure>()

        var examined = 0
        for (path in entries) {
            if (examined >= MAX_FILES) {
                break
            }
            if (!isFontCandidate(path)) continue
            examined++

            runCatching { loadOne(path) }
                .onSuccess { loaded += it }
                .onFailure { failed += LoadFailure(path, it) }
        }

        if (failed.isNotEmpty()) {
            failed.forEach {  }
        }

        if (loaded.isEmpty() && failed.isEmpty() && entries.isNotEmpty() && examined == 0) {

            val childWithFonts = runCatching {
                Files.list(directory).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .filter { child ->
                            Files.list(child).use { inner ->
                                inner.anyMatch { Files.isRegularFile(it) && isFontCandidate(it) }
                            }
                        }
                        .map { it.fileName.toString() }
                        .toList()
                }
            }.getOrNull().orEmpty()

        }
        return ScanResult(loaded, failed)
    }

    fun loadFileReporting(file: Path): ScanResult {
        if (!Files.isRegularFile(file)) {

            val reason = IllegalStateException("不是常规文件（可能是目录，或文件已被移动/删除）")
            return ScanResult(emptyList(), listOf(LoadFailure(file, reason)))
        }
        return runCatching { loadOne(file) }
            .fold(
                onSuccess = { ScanResult(it, emptyList()) },
                onFailure = { error ->
                    Unit
                    ScanResult(emptyList(), listOf(LoadFailure(file, error)))
                },
            )
    }

    private fun loadOne(path: Path): List<LoadedFont> {

        val fonts = Font.createFonts(path.toFile())
        if (fonts.isEmpty()) error("字体解析器未返回任何字体（文件可能损坏或非字体格式）")

        return fonts.map { font ->
            LoadedFont(

                familyName = font.getFamily(),
                font = font,
                source = path,
            )
        }
    }

    private fun isFontCandidate(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase(Locale.ROOT) ?: return false
        return FONT_EXTENSIONS.any { name.endsWith(it) }
    }

    private companion object {

        val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".ttc", ".otc", ".fon")

        const val MAX_FILES = 2000
    }

    data class ScanResult(
        val loaded: List<LoadedFont>,
        val failed: List<LoadFailure>,
    )

    data class LoadFailure(
        val source: Path,
        val reason: Throwable,
    )

    fun registerInto(environment: GraphicsEnvironment, fonts: List<LoadedFont>) {
        for (loaded in fonts) {

            runCatching { environment.registerFont(loaded.font) }
                .onFailure {
                }
        }
    }
}
