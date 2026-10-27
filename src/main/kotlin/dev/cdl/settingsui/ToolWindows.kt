// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import dev.cdl.host.CdlRuntime
import dev.cdl.settings.SettingDescriptor
import dev.cdl.settings.SettingKind
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.composePackedArgb
import dev.cdl.settings.SettingsRuntime
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

internal class ColorToolWindow(
    owner: Window?,

    private val descriptor: SettingDescriptor,
    private val settings: SettingsRuntime?,

    private val groupPrefix: String? = null,
) : JDialog(owner, "个性歌词 · ${descriptor.title}", ModalityType.MODELESS) {

    private val statusLabel = JLabel(" ").apply {
        foreground = MUTED
        border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
    }

    private val previewSnapshot = PreviewSnapshot.capture(settings)

    private val picker = ColorPickerDialog(
        owner = null,

        initial = currentBasePacked(),
        title = descriptor.title,
        onPreview = { picked -> preview(picked) },
        initialLightnessPercent = currentLightnessPercent(),
        previewTargetKey = groupPrefix ?: descriptor.key,
        previewSnapshot = previewSnapshot,
    )

    private var subscription: AutoCloseable? = null

    private var publishingFromSelf = false

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) = release()

            override fun windowOpened(event: WindowEvent) = refreshPreviewSnapshotFromSettings()

            override fun windowActivated(event: WindowEvent) = refreshPreviewSnapshotFromSettings()
        })
        contentPane = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 14, 8, 14)
            background = ToolWindowPalette.bg

            add(picker.buildContent(), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(statusLabel, BorderLayout.WEST)
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {

                            background = ToolWindowPalette.bg

                            add(ThemeButton("确定").apply {
                                addActionListener {
                                    commit(picker.picked())
                                    release()
                                    dispose()
                                }
                            })
                            add(ThemeButton("关闭").apply {
                                addActionListener {
                                    release()
                                    dispose()
                                }
                            })
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.SOUTH,
            )
        }

        applyToolWindowTheme(contentPane, rootPane, toolWindowFontProvider())

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                syncNativeTitleBar(contentPane, dark = !ToolWindowPalette.isLight)
            }
        })
        pack()
        minimumSize = Dimension(400, 320)
        setLocationRelativeTo(owner)
        installSubscription()

        SwingUtilities.invokeLater { picker.focusHexField() }
    }

    private fun currentBasePacked(): Int {
        if (groupPrefix == null) return currentPacked()
        val runtime = settings ?: return descriptor.default as? Int ?: 0
        val snap = runCatching { runtime.live }.getOrNull()
            ?: return descriptor.default as? Int ?: 0
        fun ch(name: String, def: Int): Int =
            runCatching {
                snap.int(SettingRegistry.descriptor("$groupPrefix.$name")!!)
            }.getOrDefault(def)
        return composePackedArgb(ch("r", 255), ch("g", 255), ch("b", 255), 100)
    }

    private fun currentPacked(): Int {
        val fallback = descriptor.default as? Int ?: 0
        val runtime = settings ?: return fallback
        if (groupPrefix == null) {

            return (runCatching { runtime.live.int(descriptor) }.getOrDefault(fallback)) or
                0xFF000000.toInt()
        }

        val snap = runCatching { runtime.live }.getOrNull() ?: return fallback
        fun ch(name: String, def: Int) =
            runCatching { snap.int(SettingRegistry.descriptor("$groupPrefix.$name")!!) }.getOrDefault(def)

        val lightnessKey = SettingRegistry.descriptor("$groupPrefix.lightness")
        val l = if (lightnessKey != null) {
            ch("lightness", 100).coerceIn(0, 100) / 100f
        } else {
            1f
        }
        val r = ch("r", 255)
        val g = ch("g", 255)
        val b = ch("b", 255)

        return composePackedArgb(r, g, b, (l * 100f).toInt())
    }

    private fun currentLightnessPercent(): Int {
        if (groupPrefix == null) return 100
        val key = SettingRegistry.descriptor("$groupPrefix.lightness") ?: return 100
        val runtime = settings ?: return 100
        return runCatching { runtime.live.int(key) }.getOrDefault(100).coerceIn(0, 100)
    }

    private fun colorPatch(picked: PickedColor): Map<String, Any> =
        buildMap {
            if (groupPrefix == null) {
                put(descriptor.key, descriptor.coerce(picked.packed))
            } else {
                val hasLightness = SettingRegistry.descriptor("$groupPrefix.lightness") != null

                val rgb = colorGroupChannelRgb(hasLightness, picked)
                put("$groupPrefix.r", (rgb shr 16) and 0xFF)
                put("$groupPrefix.g", (rgb shr 8) and 0xFF)
                put("$groupPrefix.b", rgb and 0xFF)

                if (hasLightness) {
                    put("$groupPrefix.lightness", picked.lightnessPercent)
                }
            }

            putAll(autoEnableGates())
        }

    private fun autoEnableGates(): Map<String, Any> {

        val runtime = settings ?: return emptyMap()

        val enabled = runCatching { runtime.live.boolean(AUTO_UNGATE_KEY) }.getOrNull() ?: true
        if (!enabled) return emptyMap()

        return autoUngateFor(descriptor.key)
    }

    private companion object {

        const val AUTO_UNGATE_KEY = "settings.auto_ungate_on_touch"
    }

    private fun preview(picked: PickedColor) {

        val runtime = settings ?: run {
            return
        }
        publishingFromSelf = true
        try {
            val patch = colorPatch(picked)
            val change = runtime.publish(patch)

        } catch (error: Throwable) {
        } finally {
            publishingFromSelf = false
        }

    }

    private fun commit(picked: PickedColor) {

        val runtime = settings ?: run {
            return
        }
        publishingFromSelf = true
        try {
            val patch = colorPatch(picked)
            val change = runtime.apply(patch)
            statusLabel.text = "已写入 ${picked.packed.toHexString()}"
        } catch (error: Throwable) {
        } finally {
            publishingFromSelf = false
        }
    }

    private fun installSubscription() {
        val runtime = settings ?: return
        subscription = runtime.subscribe { snapshot, change ->
            SwingUtilities.invokeLater {
                if (publishingFromSelf || !isDisplayable) return@invokeLater
                if (descriptor.group !in change.groups) return@invokeLater
                runCatching {
                    if (groupPrefix == null) {
                        picker.setValueFromModel(snapshot.int(descriptor))
                    } else {

                        fun ch(n: String): Int =
                            SettingRegistry.descriptor("$groupPrefix.$n")?.let { snapshot.int(it) } ?: 255
                        val base = composePackedArgb(ch("r"), ch("g"), ch("b"))
                        val l = SettingRegistry.descriptor("$groupPrefix.lightness")
                            ?.let { snapshot.int(it) } ?: 100
                        picker.setValueFromModel(base, l)
                    }
                }

                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        val runtime = settings ?: return
        if (runtime.isDirty) statusLabel.text = "有未保存的修改"
    }

    private fun release() {
        subscription?.let { runCatching { it.close() } }
        subscription = null
    }

    private fun refreshPreviewSnapshotFromSettings() {
        val captured = runCatching { PreviewSnapshot.capture(settings) }.getOrNull() ?: return
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            runCatching { picker.refreshPreviewSnapshot(captured) }
        } else {
            javax.swing.SwingUtilities.invokeLater {
                runCatching { picker.refreshPreviewSnapshot(captured) }
            }
        }
    }
}

internal class FontToolWindow(
    owner: Window?,
    private val settings: SettingsRuntime?,
) : JDialog(owner, "个性歌词 · 字体选择", ModalityType.MODELESS) {

    private val statusLabel = JLabel(" ").apply {
        foreground = MUTED
        border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
    }

    private val familyDescriptor: SettingDescriptor? = SettingRegistry.descriptor(FONT_FAMILY_KEY)

    private val picker: FontPicker? = familyDescriptor?.let { descriptor ->
        FontPicker(
            originalFamily = currentFamily(descriptor),
            previewSize = currentPreviewSize(),
            onCommit = { family -> commit(descriptor, family) },
        ).also { it.setExpanded(true) }
    }

    private var subscription: AutoCloseable? = null
    private var publishingFromSelf = false

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) = release()
        })

        contentPane = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 14, 8, 14)
            background = ToolWindowPalette.bg
            add(
                JLabel(
                    "<html>键：<code>$FONT_FAMILY_KEY</code><br>" +
                        "选择后<b>双击 / 回车 / 点「确定」</b>才写入（滚动选择不算）。<br>" +
                        "也可直接在 SPW 插件设置里手填字体族名 —— 两处写同一个键。</html>",
                ),
                BorderLayout.NORTH,
            )
            if (picker != null) {
                add(picker.root, BorderLayout.CENTER)
            } else {

                add(JLabel("字体设置项不可用（注册表未就绪）"), BorderLayout.CENTER)
            }
            add(
                JPanel(BorderLayout()).apply {
                    add(statusLabel, BorderLayout.WEST)
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {

                            background = ToolWindowPalette.bg

                            add(ThemeButton("关闭").apply {
                                addActionListener {
                                    release()
                                    dispose()
                                }
                            })
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.SOUTH,
            )
        }

        applyToolWindowTheme(contentPane, rootPane, toolWindowFontProvider())

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                syncNativeTitleBar(contentPane, dark = !ToolWindowPalette.isLight)
            }
        })
        pack()
        minimumSize = Dimension(420, 380)
        setLocationRelativeTo(owner)
        installSubscription()
    }

    private fun currentFamily(descriptor: SettingDescriptor): String {
        val fallback = descriptor.default as? String ?: DEFAULT_FONT_FAMILY
        val runtime = settings ?: return fallback
        return runCatching { runtime.live.text(descriptor) }.getOrDefault(fallback)
    }

    private fun currentPreviewSize(): Int {
        val descriptor = SettingRegistry.descriptor(FONT_SIZE_KEY) ?: return DEFAULT_FONT_PREVIEW_SIZE
        val fallback = descriptor.default as? Int ?: DEFAULT_FONT_PREVIEW_SIZE
        val runtime = settings ?: return fallback
        return runCatching { runtime.live.int(descriptor) }.getOrDefault(fallback)
    }

    private fun commit(descriptor: SettingDescriptor, family: String) {
        val runtime = settings ?: return

        if (family.isBlank()) {
            statusLabel.text = "字体族名不能为空，未写入"
            return
        }
        publishingFromSelf = true
        try {
            runtime.apply(mapOf(descriptor.key to descriptor.coerce(family)))
            statusLabel.text = "已写入「$family」"
        } catch (error: Throwable) {
        } finally {
            publishingFromSelf = false
        }
    }

    private fun installSubscription() {
        val runtime = settings ?: return
        subscription = runtime.subscribe { snapshot, _ ->
            SwingUtilities.invokeLater {
                if (publishingFromSelf || !isDisplayable) return@invokeLater
                familyDescriptor?.let { picker?.setValueFromModel(snapshot.text(it)) }
            }
        }
    }

    private fun release() {
        subscription?.let { runCatching { it.close() } }
        subscription = null
    }

    companion object {
        const val FONT_FAMILY_KEY = "main_lyric.font.family"
        const val FONT_SIZE_KEY = "main_lyric.font.size"

        const val DEFAULT_FONT_FAMILY = dev.cdl.font.DEFAULT_FONT_FAMILY
        const val DEFAULT_FONT_PREVIEW_SIZE = 22
    }
}

internal object ToolWindows {

    private val open = HashMap<String, JDialog>()

    private const val FONT_BUCKET = "font"

    fun openColor(key: String) {
        val descriptor = SettingRegistry.descriptor(key)
        if (descriptor == null) {
            warn("颜色工具窗：键不存在：$key")
            return
        }
        if (descriptor.kind != SettingKind.COLOR) {

            warn("颜色工具窗要求 COLOR 项，实为 ${descriptor.kind}：$key")
            return
        }
        present(key) { owner -> ColorToolWindow(owner, descriptor, SettingsRuntimeHolder.current) }
    }

    fun openColorGroup(prefix: String, title: String) {

        val hasLightness = SettingRegistry.descriptor("$prefix.lightness") != null
        val required = if (hasLightness) listOf("r", "g", "b", "lightness") else listOf("r", "g", "b")
        val missing = required.filter {
            SettingRegistry.descriptor("$prefix.$it") == null
        }
        if (missing.isNotEmpty()) {
            warn("颜色组 $prefix 缺键：$missing")
            return
        }
        present(prefix) { owner ->
            ColorToolWindow(
                owner = owner,
                descriptor = SettingRegistry.descriptor("$prefix.r")!!,
                settings = SettingsRuntimeHolder.current,
                groupPrefix = prefix,
            ).also { it.title = "个性歌词 · $title" }
        }
    }

    fun openFont() {
        present(FONT_BUCKET) { owner -> FontToolWindow(owner, SettingsRuntimeHolder.current) }
    }

    private fun present(bucket: String, factory: (Window?) -> JDialog) {
        SwingUtilities.invokeLater {
            runCatching {
                val existing = open[bucket]
                if (existing != null && existing.isDisplayable) {
                    existing.toFront()
                    existing.requestFocus()
                    return@runCatching
                }
                val created = factory(CdlRuntime.lastIslandWindow())
                open[bucket] = created

                created.addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(event: WindowEvent) {
                        if (open[bucket] === created) open.remove(bucket)
                    }
                })
                created.isVisible = true

                runCatching {
                    dev.cdl.window.WindowInterop.setDarkTitleBar(
                        created,
                        !dev.cdl.platform.SystemTheme.isLight(),
                    )
                }
            }.onFailure {

                warn("打开工具窗失败（$bucket）：${it.stackTraceToString().take(1500)}")
            }
        }
    }

    private fun warn(message: String) {
    }
}

internal object SettingsRuntimeHolder {
    @Volatile
    var current: SettingsRuntime? = null
}

internal fun settingsAvailable(): Boolean = SettingsRuntimeHolder.current != null

public fun colorGroupChannelRgb(hasLightnessKey: Boolean, picked: PickedColor): Int =

    if (hasLightnessKey) picked.baseRgb else picked.packed

internal fun autoUngateFor(targetKey: String): Map<String, Any> = when {
    targetKey.startsWith("main_lyric.outline") -> mapOf("main_lyric.outline.enabled" to true)

    targetKey.startsWith("translation.outline") -> mapOf(
        "translation.outline.enabled" to true,
        "translation.outline.sync_with_main" to false,
    )
    else -> emptyMap()
}

private val MUTED: Color get() = ToolWindowPalette.muted
