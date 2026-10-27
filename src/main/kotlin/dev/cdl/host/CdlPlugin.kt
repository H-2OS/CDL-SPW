// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin

class CdlPlugin(context: PluginContext) : SpwPlugin(context) {

    override fun start() {
        if (runtime != null) return

        val created = CdlRuntime(pluginContext)
        runtime = created

        dev.cdl.font.HostFonts.warmUpAsync()

        dev.cdl.platform.TimerResolution.raise()
        try {
            created.start()
        } catch (error: Throwable) {

            stopRuntime()
            throw error
        }
    }

    override fun stop() = stopRuntime()

    override fun update() {
        stop()
        start()
    }

    override fun delete() = stop()

    companion object {

        @Volatile
        internal var runtime: CdlRuntime? = null

        private fun stopRuntime() {

            val old = runtime

            runtime = null
            old?.close()

            dev.cdl.platform.TimerResolution.restore()
        }


        internal fun requestStop() = stopRuntime()

        internal val playback = CallbackSink()

        @JvmStatic
        @JvmName("pickSungColor")
        fun pickSungColor() =
            openColorGroupSafely("pickSungColor", "main_lyric.fill.color", "已唱歌词颜色")

        @JvmStatic
        @JvmName("pickUnsungColor")
        fun pickUnsungColor() =
            openColorGroupSafely("pickUnsungColor", "main_lyric.fill.unsung", "未唱歌词颜色")

        @JvmStatic
        @JvmName("pickTranslationColor")
        fun pickTranslationColor() =
            openColorGroupSafely("pickTranslationColor", "main_lyric.translation.color", "译文歌词颜色")

        @JvmStatic
        @JvmName("pickMainOutlineColor")
        fun pickMainOutlineColor() =
            openColorGroupSafely("pickMainOutlineColor", "main_lyric.outline.color", "主歌词描边色")

        @JvmStatic
        @JvmName("pickTranslationOutlineColor")
        fun pickTranslationOutlineColor() =
            openColorGroupSafely(
                "pickTranslationOutlineColor", "translation.outline.color", "副歌词描边色",
            )

        @JvmStatic
        @JvmName("pickBorderColor")
        fun pickBorderColor() =
            openColorGroupSafely("pickBorderColor", "island.border.color", "边框色")

        @JvmStatic
        @JvmName("pickSpectrumColor")
        fun pickSpectrumColor() = openColorGroupSafely("pickSpectrumColor", "spectrum.color", "频谱颜色")

        @JvmStatic
        @JvmName("pickBackgroundColor")
        fun pickBackgroundColor() =

            openColorGroupSafely("pickBackgroundColor", "background.color", "背景颜色")

        @JvmStatic
        @JvmName("pickGradientEndColor")
        fun pickGradientEndColor() =
            openColorGroupSafely("pickGradientEndColor", "material.fill.color_end", "渐变止色")

        @JvmStatic
        @JvmName("pickRightSlotSpectrumColor")
        fun pickRightSlotSpectrumColor() =
            openColorGroupSafely(
                "pickRightSlotSpectrumColor",
                "island.right_slot.spectrum_color",
                "插槽频谱颜色",
            )

        private fun openColorGroupSafely(entry: String, prefix: String, title: String) {

            val isGroup = dev.cdl.settings.SettingRegistry.descriptor("$prefix.r") != null
            if (isGroup) {
                CdlRuntime.openColorGroup(prefix, title)
            } else {
                CdlRuntime.openColorTool(prefix)
            }
        }

        private fun reportRetiredColorTool(entry: String, title: String) {
        }

        @JvmStatic
        @JvmName("pickFont")
        fun pickFont() {
            CdlRuntime.pickFont()
        }

        @JvmStatic
        @JvmName("recover")
        fun recover() {
            CdlRuntime.recoverIsland()
        }

        @JvmStatic
        @JvmName("about")
        fun about() {
            CdlRuntime.showAbout()
        }

        @JvmStatic
        @JvmName("openSource")
        fun openSource() {
            CdlRuntime.openSource()
        }

        @JvmStatic
        @JvmName("deletePreset")
        fun deletePreset() {
            CdlRuntime.openPresetDeleter()
        }

        @JvmStatic
        @JvmName("changePreset")
        fun changePreset() {
            CdlRuntime.openPresetChanger()
        }
    }
}
