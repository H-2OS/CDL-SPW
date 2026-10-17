// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

/**
 * 宿主播放扩展点（由 `META-INF/extensions.idx` 注册）。
 *
 * 本类只做**类型翻译**，不含业务逻辑：把宿主的回调类型转成插件内部类型后交给静态桥。
 * 之所以走静态桥而不是直接调运行时，是因为 PF4J 会独立构造扩展实例（见 [CdlPlugin]）。
 *
 * P0 阶段歌词来源：**不接管**（`onBeforeLoadLyrics` 恒返回 `null`），宿主继续用自己的歌词逻辑。
 * 后续阶段若接入多来源歌词搜索，再改为返回缓存的歌词文本。
 */
class CdlExtension : PlaybackExtensionPoint {

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        CdlPlugin.playback.trackChanged(mediaItem)
        // null = 不接管宿主歌词来源
        return null
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        // 宿主本地歌词缺失时的兜底来源；P0 暂不提供。
        return null
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        CdlPlugin.playback.stateChanged(state)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        CdlPlugin.playback.playingChanged(isPlaying)
    }

    override fun onPositionUpdated(position: Long) {
        CdlPlugin.playback.positionChanged(position)
    }

    override fun onSeekTo(position: Long) {
        CdlPlugin.playback.seek(position)
    }

    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        CdlPlugin.playback.lineChanged(lyricsLine)
    }

    /**
     * 宿主推来**整条歌词轴**（`0.1.0-dev21` 新增，宿主 1.19.0 起可用）。
     *
     * ## 为什么值得多接这一个回调
     *
     * 逐行回调只给"当前这一行"，于是插件必须靠启发式去推断主副角色、译文续行、
     * 配对关系（见 `PlaybackTimeline` 下方各 `is*` 函数的 KDoc）——
     * 而整轴里每行的 `pureMainText` / `pureSubText` 是**宿主已经分好的**，
     * 那批猜测在这一条路径上**整体不必要**。
     *
     * ## 为什么它和 [onLyricsLineUpdated] **不冲突**
     *
     * 两者是同一件事的两个粒度，宿主会**都推**（整轴一次 + 当前行逐行）。
     * 谁后到谁生效，最终状态一致：
     *   · 整轴先到 → 文档被整体替换；随后逐行推送会命中"同一行重复推送"分支，原样保留；
     *   · 逐行先到 → 照旧累积；整轴后到 → 整体替换，**顺手把累积路径里的猜测结果覆盖掉**。
     * 因此**不需要**在这里做"只认一种"的取舍，也不需要加锁或版本号。
     *
     * ## 降级
     *
     * 宿主 < 1.19.0 时根本没有这个方法（它是 dev21 的 default 方法），
     * PF4J 也不会调用它 —— 于是自动退回逐行路径。**不需要运行时版本判断。**
     */
    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        CdlPlugin.playback.linesChanged(lyricsLines)
    }
}
