// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.cdl.window.PlaybackActions

/**
 * [PlaybackActions] 的**宿主实现** —— 控件层与 SPW 播放器之间唯一的接线点。
 *
 * ## 为什么放在 `host/` 而不是 `window/`
 *
 * `window/` 是纯 Swing/Java2D 的绘制层，它**不该 import 宿主 SDK**（见 [PlaybackActions]
 * 的类 KDoc）。把宿主调用放在组合根所在的包里，依赖方向是单向的：
 * `host/ → window/`，而 `window/` 只知道一个接口。这样探针、离屏渲染、
 * 将来的单测都能在没有宿主的 JVM 里构造 `IslandWindow`。
 *
 * ## 线程
 *
 * 本对象由 `IslandWindow` 从 **EDT** 调用（控件点击天然在 EDT）。
 * 宿主这几个方法本身不限定线程，但 `WorkshopApi.playback.changeExclusive()`
 * **必须主线程** —— 本对象刻意**不暴露**它：词岛的控件里没有任何"独占模式"开关，
 * 暴露一个我们从不调用的方法只会让"谁该在主线程调"这条纪律变模糊。
 *
 * ## 异常
 *
 * **不在这里 catch**：调用方（`IslandWindow.consumeControlPress`）已用
 * `runCatching` 统一包裹并打了带动作名的日志。在这里再包一层会让异常被吞两次，
 * 且"哪一层记的日志"变得不确定。宿主 API 在"播放器正在退出"等状态下确实会抛，
 * 但那由调用方兜住即可。
 */
internal object HostPlaybackActions : PlaybackActions {

    override fun previous() {
        WorkshopApi.playback.previous()
    }

    override fun play() {
        WorkshopApi.playback.play()
    }

    override fun pause() {
        WorkshopApi.playback.pause()
    }

    override fun next() {
        WorkshopApi.playback.next()
    }

    /**
     * ⚠️ **当前无调用点**：进度条首版只显示、不响应点击（总时长不可得，
     * 见 `recon/23-窗口交互与播放控件实现.md` 的缺口一节）。
     * 实现它是为了让"接上时长"这件事只需加一处调用，不必改接口。
     */
    override fun seekTo(positionMs: Long) {
        WorkshopApi.playback.seekTo(positionMs)
    }
}
