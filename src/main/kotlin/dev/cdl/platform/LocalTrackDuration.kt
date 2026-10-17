// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import kotlin.math.roundToLong

/**
 * 本地音频文件的**时长读取** —— 播放进度条的总时长来源。
 *
 * ## 为什么需要这个类（宿主不提供时长）
 *
 * 已逐项排除宿主侧的全部来源（取证见 `recon/_javap_api_dump.txt` 与 `recon/26` §2）：
 *
 * | 候选 | 结论 |
 * | --- | --- |
 * | `WorkshopApi$Playback` | 只有 `changeExclusive / pause / play / previous / next / seekTo` —— **无时长** |
 * | `PlaybackExtensionPoint` 回调 | `onStateChanged / onIsPlayingChanged / onSeekTo / onPositionUpdated(long) / …` —— **无时长** |
 * | `PlaybackExtensionPoint.MediaItem` | `title / artist / album / path` —— **无时长** |
 * | `PlaybackExtensionPoint.LyricsLine` | `startTime / endTime / …` —— 是**歌词行**的时间，不是歌曲时长 |
 * | `TimelineSnapshot` 全部 12 字段 | 无时长 |
 *
 * 原实现 0.8.9 的进度条**是可用的**（能显示已播填充、能拖动 seek），它的时长正是
 * 来自读本地音频文件（`platform/LocalTrackMetadata.kt` + jaudiotagger）。
 * 用户要求「优先实现 0.8.9 的功能」，故本插件走同一条路 —— 但**只读时长**，
 * 不读封面/标签（那些本插件已有独立的 `cover/CoverArtProvider`，不该重复实现）。
 *
 * ## 为什么是"只读用户显式选中的那一个文件"
 *
 * 本类**不扫描目录、不遍历媒体库**。它只接受一个来自
 * `TimelineSnapshot.path`（即宿主 `MediaItem.path`）的路径 —— 那是**用户播放器当前
 * 正在播的那首歌**，用户已经通过"按下播放"表达了对这个文件的访问意图。
 * 这与"扫描用户磁盘找音频"是完全不同的行为，后者既无必要也不该做。
 *
 * ## 三个已知代价（均已在实现中处理）
 *
 * | 代价 | 处理 |
 * | --- | --- |
 * | **读取耗时**（解析文件头，网络盘上可能到百毫秒级） | 调用方**必须在后台线程**读；本类自身不做线程切换（纯函数风格，便于探针直接调） |
 * | **非本地文件读不到**（流媒体 / URL / 已删除） | 返回 `0`（= 未知），进度条退回"只画轨道 + `--:--`"，不报错、不影响其它功能 |
 * | **格式不支持**（jaudiotagger 支持 mp3/flac/m4a/ogg/wav/wma 等主流格式） | 同上：返回 `0`。异常在内部被 `runCatching` 吞掉并降级 |
 *
 * ## 许可
 *
 * 依赖 `net.jthink:jaudiotagger:3.0.1`，**LGPL-2.1-or-later**。
 * 按 LGPL-2.1 §6，随包分发时必须同时提供对应源码 —— 由 `build.gradle.kts` 的
 * `lgplDeps` / `fetchLgplSources` / `verifyLgplSources` 三个任务机械化保证
 * （`verifyLgplSources` 在缺失源码时**让构建失败**，把静默的法务漏项变成红灯）。
 * 详见 `plugin/NOTICE` 与 `THIRD_PARTY_NOTICES.md`。
 */
internal object LocalTrackDuration {

    /**
     * 读 [path] 指向的音频文件的时长（毫秒）。
     *
     * @return 时长毫秒；**`0` 表示未知**（文件不存在 / 格式不支持 / 读取失败 /
     *   时长非有限正数）。调用方按"时长未知"处理即可，不必区分具体原因 ——
     *   四种原因的**处置完全相同**（退回无填充的进度条），区分它们只会
     *   在调用点多出四个永不执行的分支。
     *
     * 本方法**不吞掉性能问题**：它是一次同步的磁盘 I/O + 解析。
     * 调用方必须保证它在后台线程上跑（见 [dev.cdl.host.TrackDurationLoader]）。
     */
    fun read(path: String): Long {
        if (path.isEmpty()) return 0L
        val file = File(path)
        // 先判 isFile：jaudiotagger 对"不存在的文件"会抛异常，而"文件不存在"
        // 是本项目的**常态**（流媒体曲目、网络盘断开、用户删了文件），
        // 不该走异常路径 —— 用一次 stat 换掉一次异常构造 + 栈填充。
        if (!file.isFile) return 0L
        val audio = runCatching { AudioFileIO.read(file) }.getOrNull() ?: return 0L
        // `preciseTrackLength` 是**秒**（Double），比整秒的 `trackLength` 精确。
        // 用计时器实测的帧数/采样数换算，故能给出小数秒。
        val seconds = runCatching { audio.audioHeader?.preciseTrackLength }.getOrNull() ?: return 0L
        if (!seconds.isFinite() || seconds <= 0.0) return 0L
        // 上限 100 小时：防御**畸形元数据**（实测有文件头写着天文数字的时长）。
        // 不加上限时，进度条的 ratio 会恒为 0，表现为"进度条永远不动"——
        // 那比"时长未知"更难诊断，因为它看起来是正常的。
        val ms = (seconds * 1000.0).roundToLong()
        return if (ms in 1..MAX_DURATION_MS) ms else 0L
    }

    /** 时长上限（毫秒）：100 小时。超过它的一律判定为"元数据畸形"→ 未知。 */
    private const val MAX_DURATION_MS = 100L * 3600L * 1000L
}
