// SPDX-License-Identifier: GPL-3.0-only
// Ported in part from Dynamic Lyrics Island for SPW by GaBoron (GPL-3.0-only); see NOTICE.
package dev.cdl.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.cdl.playback.LyricDocument
import dev.cdl.playback.LyricLine
import dev.cdl.playback.LyricWord
import dev.cdl.playback.narrowWordsToText
import dev.cdl.playback.narrowWordsToTranslation
import dev.cdl.playback.splitBilingualLine
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

internal class HostLyricsProbe {

    @Volatile
    private var service: Any? = null

    @Volatile
    private var monitor: Any? = null

    @Volatile
    private var unavailable = false

    @Volatile
    private var installed = false

    @Volatile
    private var attempts = 0

    val axisInstalled: Boolean get() = installed

    fun onTrackChanged() {
        installed = false
        attempts = 0
    }

    fun probeOnce(): LyricDocument? {
        if (unavailable || installed) return null
        if (attempts >= MAX_ATTEMPTS_PER_TRACK) return null
        attempts++
        val lines = runCatching { readAxis() }.getOrNull() ?: return null

        if (lines.isEmpty()) return null

        if (lines.size > 1 && lines.all { it.startMs == 0L }) return null
        installed = true
        return LyricDocument(lines, true)
    }

    private fun readAxis(): List<LyricLine>? {
        val playbackService = service ?: findService()?.also { service = it } ?: return null
        val monitorInstance = monitor
            ?: findObject(listOf(playbackService), MONITOR_DEPTH, ::isMonitor, ::mayTraverse)
                ?.also { monitor = it }
            ?: return null
        val document = invokeNoArg(monitorInstance, "getLyricsDocument")?.let(::unwrapToDocument)
            ?: return null
        val raw = document.lineValues() ?: return null
        return raw.mapNotNull(::mapLine).sortedBy { it.startMs }
    }

    private fun findService(): Any? {
        val roots = runCatching { listOf(WorkshopApi.instance, WorkshopApi.playback) }
            .getOrDefault(emptyList())
        if (roots.isEmpty()) return null

        for (loader in roots.map { it.javaClass.classLoader }.distinct()) {
            val found = runCatching {
                val controller = Class.forName(PLAYBACK_CONTROLLER, false, loader)
                allFields(controller)
                    .firstOrNull { Modifier.isStatic(it.modifiers) && it.type.name == PLAYBACK_SERVICE }
                    ?.read(null)
            }.getOrNull()
            if (found != null) return found
        }

        val traversed = findObject(roots, SERVICE_DEPTH, { it.javaClass.name == PLAYBACK_SERVICE }, ::mayTraverse)
        if (traversed == null) {

            unavailable = true
        }
        return traversed
    }

    private fun isMonitor(value: Any): Boolean = value.javaClass.name == PLAYBACK_MONITOR

    private fun unwrapToDocument(value: Any): Any {
        var current = value
        repeat(MAX_UNWRAP) {
            if (current.lineValues() != null) return current
            val next = invokeNoArg(current, "getValue") ?: return current
            if (next === current) return current
            current = next
        }
        return current
    }

    private fun Any.lineValues(): List<Any?>? {
        val named = (
            invokeNoArg(this, "getLyricsLines")
                ?: fieldValue(this, "lyricsLines")
                ?: fieldValue(this, "_lyricsLines")
            ).values()
        if (named != null) return named

        for (field in allFields(javaClass)) {
            if (Modifier.isStatic(field.modifiers)) continue
            val candidate = field.read(this).values() ?: continue
            if (candidate.firstOrNull()?.let(::mapLine) != null) return candidate
        }
        return null
    }

    private fun mapLine(value: Any?): LyricLine? {
        value ?: return null
        val start = longOf(value, "getStartTime", "getStartMs")
            ?: longField(value, "startTime", "startMs") ?: return null
        val end = longOf(value, "getEndTime", "getEndMs")
            ?: longField(value, "endTime", "endMs") ?: start

        val cells = invokeNoArg(value, "getLyricsCells") ?: fieldValue(value, "lyricsCells")
            ?: invokeNoArg(value, "getWords") ?: fieldValue(value, "words")
        val words = cells.values()?.mapNotNull(::mapWord).orEmpty()

        val rawMain = stringOf(value, "getPureMainText", "getText")
            ?: stringField(value, "pureMainText", "text")
            ?: words.joinToString("").takeIf { it.isNotBlank() }
            ?: return null
        val rawSub = stringOf(value, "getPureSubText", "getTranslation")
            ?: stringField(value, "pureSubText", "translation")

        val (main, sub) = splitBilingualLine(rawMain, rawSub)
        if (main.isBlank()) return null

        return LyricLine(
            startMs = start,
            endMs = end,
            text = main,
            translation = sub,

            words = narrowWordsToText(words, main),

            translationWords = sub?.let { narrowWordsToTranslation(words, main, it) },
        )
    }

    private fun mapWord(value: Any?): LyricWord? {
        value ?: return null
        val start = longOf(value, "getStartTime", "getStartMs")
            ?: longField(value, "startTime", "startMs") ?: return null
        val end = longOf(value, "getEndTime", "getEndMs")
            ?: longField(value, "endTime", "endMs") ?: start
        val text = stringOf(value, "getText") ?: stringField(value, "text") ?: return null
        return LyricWord(start, end, text)
    }

    private fun findObject(
        roots: List<Any>,
        maxDepth: Int,
        matches: (Any) -> Boolean,
        traverse: (Class<*>) -> Boolean,
    ): Any? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var level = roots
        repeat(maxDepth + 1) { depth ->
            val next = ArrayList<Any>()
            for (value in level) {
                if (!seen.add(value)) continue
                if (matches(value)) return value
                if (depth == maxDepth || !traverse(value.javaClass)) continue
                for (field in allFields(value.javaClass)) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    val child = field.read(value) ?: continue
                    if (traverse(child.javaClass)) next.add(child)
                }
            }
            level = next
        }
        return null
    }

    private fun mayTraverse(type: Class<*>): Boolean = type.name.run {
        startsWith("com.xuncorp.voxzen") ||
            startsWith("com.xuncorp.spc") ||
            startsWith("com.xuncorp.spw") ||
            startsWith("kotlinx.coroutines.flow")
    }

    private fun invokeNoArg(owner: Any, name: String): Any? = allMethods(owner.javaClass)
        .firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.let { method -> runCatching { method.trySetAccessible(); method.invoke(owner) }.getOrNull() }

    private fun fieldValue(owner: Any, name: String): Any? =
        allFields(owner.javaClass).firstOrNull { it.name == name }?.read(owner)

    private fun stringOf(owner: Any, vararg names: String): String? = names.firstNotNullOfOrNull {
        (invokeNoArg(owner, it) as? String)?.takeIf { text -> text.isNotBlank() }
    }

    private fun stringField(owner: Any, vararg names: String): String? = names.firstNotNullOfOrNull {
        (fieldValue(owner, it) as? String)?.takeIf { text -> text.isNotBlank() }
    }

    private fun longOf(owner: Any, vararg names: String): Long? = names.firstNotNullOfOrNull {
        (invokeNoArg(owner, it) as? Number)?.toLong()
    }

    private fun longField(owner: Any, vararg names: String): Long? = names.firstNotNullOfOrNull {
        (fieldValue(owner, it) as? Number)?.toLong()
    }

    private fun Field.read(owner: Any?): Any? =
        runCatching { trySetAccessible(); get(owner) }.getOrNull()

    private fun allMethods(type: Class<*>): Sequence<Method> = sequence {
        yieldAll(type.methods.asSequence())
        yieldAll(generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() })
    }.distinctBy { method -> method.name to method.parameterTypes.toList() }

    private fun allFields(type: Class<*>): Sequence<Field> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }

    private fun Any?.values(): List<Any?>? = when (this) {
        is Iterable<*> -> toList()
        is Array<*> -> toList()
        is Map<*, *> -> values.toList()
        else -> null
    }

    companion object {
        private const val PLAYBACK_SERVICE = "com.xuncorp.voxzen.service.PlaybackService"
        private const val PLAYBACK_CONTROLLER = "com.xuncorp.voxzen.service.PlaybackController"
        private const val PLAYBACK_MONITOR = "com.xuncorp.voxzen.service.PlaybackMonitor"

        private const val SERVICE_DEPTH = 5

        private const val MONITOR_DEPTH = 2

        private const val MAX_UNWRAP = 3

        private const val MAX_ATTEMPTS_PER_TRACK = 3

    }
}
