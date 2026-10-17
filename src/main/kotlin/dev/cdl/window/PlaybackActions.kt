// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

/**
 * 播放控件对宿主播放器的**写操作**抽象 —— 控件层唯一允许触碰播放器的地方。
 *
 * ## 为什么是一层接口，而不是让 `IslandWindow` 直接调 `WorkshopApi.playback()`
 *
 * 三个理由，缺一都足以否掉直接调用：
 *
 * ① **依赖方向**：`window/` 是纯 Swing/Java2D 的绘制层，它依赖 `playback/`（读快照）
 *    已经是"读"方向的最大让步。若再让它 import 宿主 API，`window/` 就同时依赖
 *    「宿主 SDK」与「Swing」，从此**无法在宿主之外构造**（探针、单测、离屏渲染都做不了）。
 *    本项目已有先例：`IslandWindow` 收的是 `snapshotSupplier: () -> TimelineSnapshot`
 *    而不是 `PlaybackTimeline` 实例（见该类 KDoc 的"为什么传 lambda"）—— 同一个理由。
 *
 * ② **语义收敛**：宿主 API 是 `play()` / `pause()` 两个**无状态**动作，
 *    而按钮要的是"播放/暂停切换"。**切换需要先读当前播放态**（`isPlaying`）才能决定调哪个 ——
 *    这个判断必须落在持有快照的一方，而不是宿主 API 的调用点。故接口按
 *    "用户意图"（[play] / [pause]）而不是"宿主方法名"来定义，让 `IslandWindow`
 *    只表达"用户点了暂停"，不去操心该调哪个方法。
 *
 * ③ **可测/可降级**：宿主 API 在非宿主环境（探针、离屏）不存在，接口可以给 [NONE] 兜底。
 *    直接调用则只能靠 `runCatching` 吞异常，而"吞掉的异常"与"正常无事发生"在日志上无法区分。
 *
 * ## 线程纪律
 *
 * 实现方（见 `host/HostPlaybackActions.kt`）会被**从 EDT 调用** —— 控件的点击处理天然在 EDT。
 * 宿主 API 本身不要求特定线程（`changeExclusive` 除外，它必须主线程，本接口不暴露它），
 * 但从 EDT 调用是最省事的正确选择：不需要再投递一跳，也不会与渲染争抢。
 */
internal interface PlaybackActions {

    /** 上一首。对应 `WorkshopApi.playback().previous()`。 */
    fun previous()

    /** 继续播放。对应 `WorkshopApi.playback().play()`。 */
    fun play()

    /** 暂停。对应 `WorkshopApi.playback().pause()`。 */
    fun pause()

    /** 下一首。对应 `WorkshopApi.playback().next()`。 */
    fun next()

    /**
     * 跳转到指定位置（毫秒）。对应 `WorkshopApi.playback().seekTo(ms)`。
     *
     * ## ⚠️ 2026-09-24 更正：**已接线**（原文写「首版没有任何调用点」，不成立）
     *
     * 调用链（全在 `IslandWindow`）：`Id.PROGRESS` 命中 → `seekTargetAt(...)` 算目标毫秒 →
     * 松手时 `performPlayback("跳转")` 真正调用本方法。拖动中**只改预览值并重绘**，不逐帧 seek
     * （否则播放器会被 seek 请求淹没，音频断断续续）。
     *
     * **仍然成立**的约束：**总时长不可得**（见 `recon/23-窗口交互与播放控件实现.md` 的缺口一节）
     * ⇒ 时长未知时 `seekTargetAt` 返回 null、进度条**不可交互**（只显示）。
     */
    fun seekTo(positionMs: Long)

    companion object {
        /**
         * 空实现 —— 宿主控制不可用时（设置系统坏了、非宿主环境）的兜底。
         *
         * 为什么不是 `null`：让调用点少一层判空。控件点了没反应是**可接受的降级**，
         * 而 `null` 会让"点击处理"这条路径多出一个必须处理的分支 ——
         * 那类分支在真实设备上几乎从不执行，因此最容易腐化。
         */
        val NONE: PlaybackActions = object : PlaybackActions {
            override fun previous() = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun next() = Unit
            override fun seekTo(positionMs: Long) = Unit
        }
    }
}
