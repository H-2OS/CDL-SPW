// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.cdl.cover.CoverArtProvider
import dev.cdl.platform.ProcessSpectrum
import dev.cdl.playback.LyricDocument
import dev.cdl.playback.LyricLine
import dev.cdl.playback.LyricWord
import dev.cdl.playback.PlaybackTimeline
import dev.cdl.playback.TimelineSnapshot
import dev.cdl.playback.bilingualLinesFrom
import dev.cdl.playback.HostLyricCell
import dev.cdl.playback.HostLyricLine
import dev.cdl.playback.narrowWordsToText
import dev.cdl.playback.narrowWordsToTranslation
import dev.cdl.playback.separatedMainText
import dev.cdl.playback.separatedTranslation
import dev.cdl.playback.splitBilingualLine
import dev.cdl.settings.SettingChange
import dev.cdl.settings.SettingCoupling
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingsRuntime
import dev.cdl.settingsui.PresetManagerMode
import dev.cdl.settingsui.SettingsRuntimeHolder
import dev.cdl.settingsui.ToolWindows
import dev.cdl.settingsui.showFontSourceDialog
import dev.cdl.settingsui.showPresetManagerDialog
import dev.cdl.window.IslandTray
import dev.cdl.window.IslandWindow
import dev.cdl.render.needsSpectrumData
import dev.cdl.window.coverShapeForcesSpectrum
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import java.util.Properties

class CdlRuntime(private val context: PluginContext) : AutoCloseable {

    @Volatile
    private var closed = false

    @Volatile
    internal var settings: SettingsRuntime? = null
        private set

    @Volatile
    internal var timeline: PlaybackTimeline? = null
        private set

    private fun registerSettingsHolder() {
        SettingsRuntimeHolder.current = settings
    }

    @Volatile
    internal var islandWindow: IslandWindow? = null
        private set

    @Volatile
    internal var islandTray: dev.cdl.window.IslandTray? = null
        private set

    private val trackDuration = TrackDurationLoader()

    private val coverArt = CoverArtProvider()

    @Volatile
    private var spectrum: ProcessSpectrum? = null

    private var spectrumSubscription: AutoCloseable? = null

    private var couplingSubscription: AutoCloseable? = null

    private var glassRebuildSubscription: AutoCloseable? = null

    fun start() {

        settings = runCatching { SettingsRuntime.create(context) }
            .onFailure {  }
            .getOrNull()

        runCatching {
            val selfLoader = this::class.java.classLoader
            val names = listOf("org.jetbrains.skia.ImageFilter", "androidx.compose.ui.Modifier")
            val lines = names.map { n ->
                val c = runCatching { Class.forName(n, false, selfLoader) }.getOrNull()
                if (c == null) {
                    "  $n → **加载失败**（不在插件可见的 classpath 上）"
                } else {
                    val l = c.classLoader
                    val src = runCatching {
                        c.protectionDomain?.codeSource?.location?.toString()
                    }.getOrNull()
                    "  $n → loader=${l?.javaClass?.name}@${System.identityHashCode(l)}" +
                        " sameAsPlugin=${l === selfLoader} src=$src"
                }
            }
        }.onFailure {
        }

        timeline = runCatching {
            PlaybackTimeline(durationSupplier = { trackDuration.current() })
        }
            .onFailure {  }
            .getOrNull()

        runCatching { syncSpectrum() }
            .onFailure {  }
        runCatching { subscribeSpectrum() }
            .onFailure {  }

        runCatching { subscribeSettingCoupling() }
            .onFailure {  }

        runCatching { subscribeGlassRebuild() }
            .onFailure {  }

        runCatching { registerSettingsHolder() }
            .onFailure {  }

        islandWindow = createIslandWindow()

        islandTray = runCatching {
            IslandTray(
                settings = settings,
                window = islandWindow,

                playbackActions = HostPlaybackActions,
            ).also { tray ->
                tray.install()

                tray.onPlaybackState(timeline?.snapshot()?.isPlaying ?: false)
            }
        }.onFailure {

            notifyWarning("CDL：托盘图标启动失败。右键菜单将无法呼出。")
        }.getOrNull()

        if (settings?.live?.boolean("playback.autoplay_on_start") == true) {
            val autoplayTimer = javax.swing.Timer(1500) { ev ->
                (ev.source as javax.swing.Timer).stop()
                val snap = timeline?.snapshot()
                if (snap != null && !snap.isPlaying) {
                    runCatching { HostPlaybackActions.play() }
                        .onFailure {  }
                }
            }
            autoplayTimer.isRepeats = false
            autoplayTimer.start()
        }

        activeIslandWindow = null
        CdlPlugin.playback.attach(this)
    }

    override fun close() {
        if (closed) return
        closed = true

        runCatching { SettingsRuntimeHolder.current = null }

        activeIslandWindow = null
        CdlPlugin.playback.detach(this)

        islandTray?.close()
        islandTray = null

        islandWindow?.close()
        islandWindow = null

        runCatching { spectrumSubscription?.close() }

        runCatching { couplingSubscription?.close() }
        runCatching { glassRebuildSubscription?.close() }
        spectrumSubscription = null
        spectrum?.let { runCatching { it.close() } }
        spectrum = null

        timeline = null

        trackDuration.close()

        coverArt.close()
        settings?.close()
        settings = null

    }

    internal fun recover() {
        if (closed) return
        runCatching {
            val delta = mapOf("island.enabled" to true, "island.interactive" to false)
            settings?.publish(delta)
            settings?.apply(delta)
        }.onFailure {

        }
        runCatching {
            islandWindow?.setVisible(true)
            islandWindow?.resetToPrimaryScreen()
        }.onFailure {
        }
    }

    private fun createIslandWindow(): IslandWindow? {
        return runCatching {
            IslandWindow(
                settings = settings,

                snapshotSupplier = { timeline?.snapshot() ?: TimelineSnapshot.EMPTY },

                playbackActions = HostPlaybackActions,

                durationSupplier = { trackDuration.current() },

                spectrumBandsSupplier = { spectrum?.bands() },

                coverArtProvider = coverArt,

                toastSink = { msg -> notifyUser(msg, warning = true) },
            ).also { window ->
                window.start()

                val enabled = settings?.live?.boolean("island.enabled") ?: true
                window.setVisible(enabled)
            }
        }.onFailure {

            notifyWarning("CDL：词岛窗口启动失败，已降级为无悬浮歌词。详情见插件日志。")
        }.getOrNull()
    }

    private fun recreateIslandWindow() {
        javax.swing.SwingUtilities.invokeLater {
            runCatching {
                islandWindow?.close()
                islandWindow = null
                val fresh = createIslandWindow()
                islandWindow = fresh

                islandTray?.updateWindow(fresh)
                val enabled = settings?.live?.boolean("island.enabled") ?: true
                fresh?.setVisible(enabled)
                notifyUser("液态玻璃档位已切换，词岛窗口已重建", warning = false)
            }.onFailure {
                notifyWarning("CDL：切换液态玻璃档位失败，词岛可能已消失，请重启宿主。")
            }
        }
    }

    private fun subscribeGlassRebuild() {
        val runtime = settings ?: return
        var last = runtime.live.text(dev.cdl.settings.SettingRegistry.descriptor("glass.material")!!)
        glassRebuildSubscription = runtime.subscribe { snapshot, change ->
            if ("glass.material" !in change.changedKeys) return@subscribe
            val now = snapshot.text(dev.cdl.settings.SettingRegistry.descriptor("glass.material")!!)
            if (now == last) return@subscribe
            last = now
            recreateIslandWindow()
        }
    }

    private fun syncSpectrum() {

        val enabled = runCatching { settings?.live?.boolean("spectrum.enabled") }.getOrNull() ?: true

        val slotKeyNow = textNow("island.right_slot.component") ?: "NONE"
        val anchorNow = textNow("spectrum.anchor") ?: "BOTTOM"
        val wanted = needsSpectrumData(slotKeyNow, anchorNow, enabled) ||
            coverShapeForcesSpectrum(shapeTypeNow())
        val current = spectrum
        when {
            wanted && current == null -> {
                val created = ProcessSpectrum(context.pluginPath)
                spectrum = created
                runCatching { created.start() }
                    .onFailure {  }
            }

            !wanted && current != null -> {
                spectrum = null
                runCatching { current.close() }
                    .onFailure {  }
            }

        }
    }

    private fun shapeTypeNow(): String =
        runCatching {

            val d = SettingRegistry.descriptor("shape.type")
            if (d == null) null else settings?.live?.text(d)
        }.getOrNull() ?: "SQUARE"

    private fun textNow(key: String): String? =
        runCatching {
            val d = SettingRegistry.descriptor(key)
            if (d == null) null else settings?.live?.text(d)
        }.getOrNull()

    private fun subscribeSpectrum() {
        val runtime = settings ?: return
        spectrumSubscription = runtime.subscribe { _, _ ->
            runCatching { syncSpectrum() }
                .onFailure {  }
        }
    }

    private fun subscribeSettingCoupling() {
        val runtime = settings ?: return
        couplingSubscription = runtime.subscribe { snapshot, change ->

            if (change.kind == SettingChange.Kind.PRESET) return@subscribe
            if (change.kind == SettingChange.Kind.EXTERNAL && change.changedKeys.size > 3) {
                return@subscribe
            }

            val patch = SettingCoupling.releasePatch(change.changedKeys) { key ->
                SettingRegistry.descriptor(key)?.let { descriptor ->
                    runCatching { snapshot.text(descriptor) }.getOrNull()
                }
            }
            if (patch.isEmpty()) return@subscribe
            SwingUtilities.invokeLater {
                runCatching { runtime.apply(patch) }
                    .onSuccess {

                    }
                    .onFailure {
                    }
            }
        }
    }

    internal fun onPlaybackEvent(kind: String, value: String) {
        if (closed) return
        lastEvent.set("$kind=$value")
    }

    internal fun onPlaybackCallback(event: CdlRuntime.StructuredEvent) {
        if (closed) return
        val target = timeline ?: return
        when (event) {
            is StructuredEvent.Track -> {
                val path = event.item.path.orEmpty()
                target.trackChanged(
                    title = event.item.title.orEmpty(),
                    artist = event.item.artist.orEmpty(),
                    album = event.item.album.orEmpty(),
                    path = path,
                )

                trackDuration.load(path)
                wakeWindow()
            }

            is StructuredEvent.Position -> target.positionChanged(event.positionMs)

            is StructuredEvent.Seek -> {
                target.seek(event.positionMs)
                wakeWindow()
            }

            is StructuredEvent.Playing -> {
                target.playingChanged(event.playing)

                islandTray?.onPlaybackState(event.playing)
                wakeWindow()
            }

            is StructuredEvent.State -> {
                target.stateChanged(event.state.name)
                wakeWindow()
            }

            is StructuredEvent.Line -> {
                val line = event.line

                if (line == null) {

                    target.lineCleared()
                } else {

                    val mainText = separatedMainText(line.pureMainText, line.pureSubText)
                    val subText = separatedTranslation(line.pureMainText, line.pureSubText)

                    val rawWords = line.lyricsCells.map {
                        LyricWord(it.startTime, it.endTime, it.text.orEmpty())
                    }

                    target.lineChanged(
                        startMs = line.startTime,
                        endMs = line.endTime,
                        text = mainText,
                        translation = subText,
                        words = narrowWordsToText(rawWords, mainText),
                        translationWords = subText?.let {
                            narrowWordsToTranslation(rawWords, mainText, it)
                        },
                    )
                }
                wakeWindow()
            }

            is StructuredEvent.Lines -> {
                val rawLines = event.lines

                val plain = bilingualLinesFrom(
                    rawLines.map {
                        HostLyricLine(
                            startMs = it.startTime,
                            endMs = it.endTime,
                            text = it.pureMainText,
                            translation = it.pureSubText,
                            cells = it.lyricsCells.map { c ->
                                HostLyricCell(c.startTime, c.endTime, c.text.orEmpty())
                            },
                        )
                    }
                )
                if (plain.isEmpty()) {

                    target.documentCleared()
                } else {
                    val lines = plain.map { p ->
                        val rawWords = p.cells.map { LyricWord(it.startMs, it.endMs, it.text) }
                        LyricLine(
                            startMs = p.startMs,
                            endMs = p.endMs,
                            text = p.text,
                            translation = p.translation,
                            words = narrowWordsToText(rawWords, p.text),
                            translationWords = p.translation?.let {
                                narrowWordsToTranslation(rawWords, p.text, it)
                            },
                        )
                    }

                    val sorted = lines.sortedWith(compareBy({ it.startMs }, { it.endMs }))
                    target.documentReplaced(LyricDocument(sorted, true))
                }
                wakeWindow()
            }
        }
    }

    private fun wakeWindow() {
        val window = islandWindow ?: return
        runCatching { window.onPlaybackStateMaybeChanged() }
            .onFailure {  }
    }

    private fun notifyWarning(message: String) {
        runCatching {
            WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
        }.onFailure {
        }
    }

    internal sealed interface StructuredEvent {
        data class Track(val item: PlaybackExtensionPoint.MediaItem) : StructuredEvent
        data class Playing(val playing: Boolean) : StructuredEvent
        data class State(val state: PlaybackExtensionPoint.State) : StructuredEvent
        data class Position(val positionMs: Long) : StructuredEvent
        data class Seek(val positionMs: Long) : StructuredEvent
        data class Line(val line: PlaybackExtensionPoint.LyricsLine?) : StructuredEvent
        data class Lines(val lines: List<PlaybackExtensionPoint.LyricsLine>) : StructuredEvent
    }

    internal val lastEvent = java.util.concurrent.atomic.AtomicReference<String?>(null)

    companion object {

        internal fun openColorTool(key: String) {
            ToolWindows.openColor(key)
        }

        internal fun openColorGroup(prefix: String, title: String) {

            onEdt {
                runCatching { ToolWindows.openColorGroup(prefix, title) }
                    .onFailure { error ->
                        Unit

                        runCatching {
                            WorkshopApi.ui.toast(
                                "取色器打开失败：${error.message ?: error.javaClass.simpleName}",
                                WorkshopApi.Ui.ToastType.Warning,
                            )
                        }
                    }
            }
        }

        internal fun openFontTool() {
            ToolWindows.openFont()
        }

        internal fun pickFont() {
            onEdt {
                runCatching { showFontSourceDialog(lastIslandWindow()) }
                    .onFailure { error ->
                        Unit

                        runCatching {
                            WorkshopApi.ui.toast(
                                "字体选择器打开失败：${error.message ?: error.javaClass.simpleName}",
                                WorkshopApi.Ui.ToastType.Warning,
                            )
                        }
                    }
            }
        }

        internal fun recoverIsland() {
            val target = CdlPlugin.runtime
            if (target == null) {
                return
            }
            target.recover()
        }

        @Volatile
        internal var activeIslandWindow: java.awt.Window? = null

        internal fun lastIslandWindow(): java.awt.Window? = activeIslandWindow

        internal fun showAbout() {
            onEdt {
                JOptionPane.showMessageDialog(
                    null,
                    "CDL for SPW（测试版）\n\n" +

                        buildList {
                            add("独立 SPW 插件：渲染与时序为独立实现；")
                            add("菜单外观体系移植自原实现（详见随包 NOTICE）。")
                            add("歌词悬浮岛的原创交互概念出自 Lyricify / WXRIW（CC BY-SA 4.0）。")
                            add("作者：H-2OS（自宅警備員）。本项目以 AGPL-3.0-only 发布；")
                            add("Jaudiotagger（LGPL-2.1-or-later）两个第三方库。")
                            add("完整对应源码随包提供（`source/`，AGPL-3.0 §6）。")
                            add("")
                            val url = projectUrl()
                            if (url != null) add("项目源代码：$url") else add("对应完整源码随包提供：`source/` 目录。")
                            add("完整许可、第三方声明与对应源码随插件 ZIP 提供。")
                        }.joinToString("\n"),
                    "关于与许可",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }

        internal fun openSource() {

            val url = projectUrl()
            if (url == null) {
                runCatching {
                    WorkshopApi.ui.toast(
                        "本版本未配置公开仓库；对应源码随插件包提供（source/ 目录）。",
                        WorkshopApi.Ui.ToastType.Warning,
                    )
                }
                return
            }
            runCatching {
                if (!Desktop.isDesktopSupported()) error("Desktop API 不可用")
                val desktop = Desktop.getDesktop()
                if (!desktop.isSupported(Desktop.Action.BROWSE)) error("不支持打开浏览器")
                desktop.browse(URI(url))
            }.onFailure {
                runCatching {
                    WorkshopApi.ui.toast("无法打开项目地址：$url", WorkshopApi.Ui.ToastType.Warning)
                }
            }
        }

        internal fun openPresetDeleter() = openPresetManager(PresetManagerMode.DELETE)

        internal fun openPresetChanger() = openPresetManager(PresetManagerMode.CHANGE)

        @Volatile
        private var presetDialogOpen = false

        private fun openPresetManager(mode: PresetManagerMode) {
            onEdt {
                if (presetDialogOpen) {
                    notifyUser("预设管理窗已经打开了", warning = false)
                    return@onEdt
                }
                presetDialogOpen = true
                try {
                    runCatching {

                        showPresetManagerDialog(lastIslandWindow(), mode) { message, warning ->
                            notifyUser(message, warning)
                        }
                    }.onFailure { error ->
                        Unit
                        notifyUser(
                            "预设管理窗打开失败：${error.message ?: error.javaClass.simpleName}",
                            warning = true,
                        )
                    }
                } finally {
                    presetDialogOpen = false
                }
            }
        }

        private fun notifyUser(message: String, warning: Boolean) {
            runCatching {
                WorkshopApi.ui.toast(
                    message,
                    if (warning) {
                        WorkshopApi.Ui.ToastType.Warning
                    } else {
                        WorkshopApi.Ui.ToastType.Success
                    },
                )
            }.onFailure {  }
        }

        private fun projectUrl(): String? = runCatching {
            val properties = Properties()
            CdlRuntime::class.java.getResourceAsStream("/project.properties")?.use(properties::load)
            val raw = properties.getProperty("source.url")?.trim()
            raw?.takeIf {
                it.isNotBlank() &&
                    !it.contains("CHANGE-ME", ignoreCase = true) &&
                    !it.contains("CHANGEME", ignoreCase = true)
            }
        }.getOrNull()

        private fun onEdt(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
        }
    }
}
