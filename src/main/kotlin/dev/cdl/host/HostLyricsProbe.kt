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

/**
 * **完整歌词轴探针** —— 用反射从宿主内部取出整份歌词文档（而不只是当前那一行）。
 *
 * ## 为什么必须存在（BUG-041 的根因，2026-09-17 裁定 ①）
 *
 * 宿主给插件的公开 API **只逐行推送**歌词（`PlaybackExtensionPoint.onLyricsLineUpdated`），
 * 而且只提前约 0.1–0.9 秒推**下一行**。于是"未唱行"在插件可见的数据里**结构上不存在**：
 * 探针实测 115/115 次位置心跳时 `rawNext == null`（取证：`recon/26`）。
 * 后果是词岛永远画不出第三行 —— 用户报的「多行歌词无法呈现」。
 *
 * 补齐的办法只有一条：**反射读宿主内部的完整歌词轴**
 * （`com.xuncorp.voxzen.service.PlaybackMonitor.getLyricsDocument()`）。
 * 原实现灵动词岛 0.8.9 也是这么做的（其 `HostPlaybackProbe.readLyrics()`）。
 *
 * ## ⚠️ 关于 `--add-opens`（做这件事的**前提**，2026-09-17 补做取证）
 *
 * 结论：**不需要任何新的 `--add-opens`，这条路径在语义上是通的。**
 *
 * - 被访问的是 `com.xuncorp.voxzen.service` 包 —— 它是**宿主类路径上的类**，
 *   即 **unnamed module（未具名模块）**，不是任何 JDK 具名模块。
 * - 三份独立取证证明宿主是**非模块化**部署：
 *   ① 宿主 `app/Salt Player for Windows.cfg` 里 `app.mainclass=MainKt`，
 *      且**没有** `--module-path` / `--add-modules` / `app.module`；
 *   ② 两份 `hs_err_pid*.log` 的命令行末尾都是
 *      `… app-path=…\Salt Player for Windows.exe MainKt`（主类，而非 `-m 模块/主类`），
 *      且 JVM 版本串带 `nomod` 后缀；
 *   ③ 宿主根目录**没有任何 jar**（宿主类在 `voxzen.aot` 的 AOT 缓存里）。
 * - 语义论证：`--add-opens <module>/<package>` 的 `<module>` 必须是**具名模块**；
 *   unnamed module 没有 `module-info`，**根本不存在"未导出的包"**，
 *   故模块访问检查对它是 no-op。现有三条 `--add-opens`
 *   （`java.base/java.lang`、`java.desktop/sun.awt`、`sun.awt.windows`）
 *   **既不覆盖、也不需要使用**。
 * - 实证：原实现用 `setAccessible` 成功，且用户确认原实现多行歌词可用。
 *
 * **残余风险（必须知情）**：上述推理基于当前版本（1.18.0 / JBR 25，`nomod`）的取证，
 * **若宿主将来改为模块化部署，这条路径立即失效**。故本类的纪律是
 * **整体 `runCatching` + 失败静默返回 `null`**，让调用方**完全退回**逐行累积。
 *
 * ## fail-open 是硬要求（不是"尽量"）
 *
 * 探针失败时，插件的行为必须与**没有本类时逐字节相同** ——
 * 即今天的逐行累积路径。因此：
 * - 任何一步出错（找不到类 / 方法不存在 / 反射被拒 / 字段改名）都返回 `null`；
 * - `null` 意味着"本次没探到"，调用方走原有分支，**不做任何替代方案**；
 * - 设置项 `performance.host_lyrics_probe` 关掉即回到现状（一键回退）。
 *
 * ## 线程
 *
 * 只在**宿主回调线程**上被调用（`CdlRuntime.onPlaybackCallback`），
 * 与 `PlaybackTimeline` 的写入方是同一个线程，故内部状态用 `@Volatile`
 * 即可，**不需要锁**（与时间轴同一套无锁纪律，见 `PlaybackTimeline` 的类 KDoc）。
 *
 * ## 许可
 *
 * 设计思路借鉴原实现 `HostPlaybackProbe.readLyrics()`（GPL-3.0-only，组合作品整体按 AGPL-3.0-only 分发），
 * 但按本项目自己的类型（[LyricDocument] / [LyricLine] / [LyricWord]）与
 * 「fail-open + 中文注释」纪律**重写**，不是逐字复制。
 */
internal class HostLyricsProbe {

    /** 缓存 `PlaybackService` 实例（进程内稳定，找到一次就不再遍历对象图）。 */
    @Volatile
    private var service: Any? = null

    /** 缓存 `PlaybackMonitor` 实例（同上）。 */
    @Volatile
    private var monitor: Any? = null

    /**
     * 结构性不可用闩锁：**连 `PlaybackService` 都找不到**时置位，此后永不再探。
     *
     * 为什么需要它（而不是让 [probeOnce] 一直重试）：探针要在对象图里做反射遍历，
     * 那是**高频路径上的昂贵操作**。若宿主换了包名/类名，"找不到"这个事实
     * 是**结构性**的、不会随时间变好，重试只是白白烧 CPU。
     */
    @Volatile
    private var unavailable = false

    /** 本曲是否已由整轴接管。见 [axisInstalled] 与 [onTrackChanged]。 */
    @Volatile
    private var installed = false

    /** 本曲已尝试探测的次数（换曲清零）。见 [MAX_ATTEMPTS_PER_TRACK]。 */
    @Volatile
    private var attempts = 0

    /**
     * 本曲是否已由整轴接管。
     *
     * 调用方用它决定"要不要走逐行累积"。为 true 时**必须跳过逐行累积** ——
     * 理由见 [probeOnce] 的「为什么接管后要跳过逐行累积」。
     */
    val axisInstalled: Boolean get() = installed

    /**
     * 换曲：清掉"本曲已探过"的标记。
     *
     * ## 为什么必须清（而不是"一辈子只探一次"）
     *
     * 每首歌的歌词文档是不同的对象 —— 不清就会拿**上一曲的歌词轴**去渲染这一曲，
     * 表现为"切歌后歌词还是上一首的"。
     *
     * ## 为什么不清 [service] / [monitor] / [unavailable]
     *
     * 那三者的生命周期是**进程级**的（同一个 `PlaybackService` 实例服务所有曲目），
     * 清掉只会让下一曲重新遍历一次对象图，纯浪费。
     */
    fun onTrackChanged() {
        installed = false
        attempts = 0
    }

    /**
     * 尝试探测一次完整歌词轴。
     *
     * ## 「只探一次」的**准确边界**（2026-09-17 明确，写在此处以免日后被改坏）
     *
     * 探针是**反射 + 对象图遍历**，绝不能挂在**每一次** `Line` 回调上（高频）。
     * 准确的边界是：
     *
     * | 事件 | 是否探 |
     * |---|---|
     * | 换曲（`onTrackChanged`） | **重置**，让本曲可以重新探（必须，见该方法的说明） |
     * | 本曲**已成功探到**并接管 | **不再探**（`installed` 短路，一次都不多做） |
     * | 本曲**尚未探到**，且 `Line` 回调到来 | 探 —— **但每曲最多 [MAX_ATTEMPTS_PER_TRACK] 次** |
     * | 结构上不可用（[unavailable] 置位） | **永不探** |
     *
     * 即：**成功即止、失败有上限、换曲重来**。之所以"失败要重试几次"而不是
     * "第一次失败就放弃"：`Line` 回调到来时歌词**通常**已就绪，但宿主加载歌词
     * 与推第一行之间存在竞态，头一两次可能读到空文档。给 3 次机会覆盖这个窗口，
     * 又不至于在"这首歌压根没有时间轴"时无限重试。
     *
     * ## 为什么接管后要跳过逐行累积（调用方必须遵守）
     *
     * 逐行累积（`PlaybackTimeline.lineChanged`）是按
     * `(startMs, endMs, text)` 三元组**去重后合并**的。整轴里的行，其 `endMs`
     * 来自宿主文档本身；而逐行推送的行，`endMs` 来自推送时的 `LyricsLine.endTime`。
     * 两者**不保证相等** —— 若同时启用，同一句歌词会因 `endMs` 不同而**各存一份**，
     * 于是 `lineAt` 的"同 startMs 组取最后一行"会把 prev/next 的索引整体挤偏，
     * 表现为"多行歌词错位"。故整轴接管后，逐行推送**不再写入文档**。
     *
     * @return 非 null 表示**本次**拿到了可用的整轴（调用方应替换整份文档）；
     *   null 表示"本次没探到"，调用方走逐行累积 —— 这是 fail-open 的落点。
     */
    fun probeOnce(): LyricDocument? {
        if (unavailable || installed) return null
        if (attempts >= MAX_ATTEMPTS_PER_TRACK) return null
        attempts++
        val lines = runCatching { readAxis() }.getOrNull() ?: return null
        // 空文档 = 这首歌没有歌词（或还没加载完）→ **不接管**，
        // 让逐行路径去处理"无歌词"的显示（它比我们更清楚该显示曲名还是空白）。
        if (lines.isEmpty()) return null
        // 全是 0 起点 = 这份文档没有时间轴（纯文本歌词）。整轴对纯文本毫无价值
        // （`lineAt` 会永远返回最后一行），故判为"整轴不可用"退回逐行 —— fail-open。
        if (lines.size > 1 && lines.all { it.startMs == 0L }) return null
        installed = true
        return LyricDocument(lines, true)
    }

    // ── 反射读取 ──────────────────────────────────────────────────────────────

    /** 读整轴。任何一步失败返回 null；**只有**"连 service 都找不到"才置 [unavailable]。 */
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

    /**
     * 找到 `PlaybackService` 实例。
     *
     * 两条路，按"便宜优先"排序：
     * ① `PlaybackController` 的**静态字段**（类型即 `PlaybackService`）—— 一次类加载即可，
     *    不需要遍历对象图；
     * ② 从 `WorkshopApi.instance` / `WorkshopApi.playback` 出发做**有界**遍历兜底。
     *
     * ⚠️ 只有当**确实搜过**（roots 非空）却一无所获时才置 [unavailable]。
     * 若 `WorkshopApi` 尚未注入（`lateinit` 未初始化会抛），roots 为空 ——
     * 那是"还没轮到我们"，不是"这条路不通"，不该闩死。
     */
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
            // 走到这里说明：宿主类路径上**没有** `PlaybackService`（换包名 / 换版本 /
            // 改了部署形态）。这是结构性事实，闩死，不再在每次 `Line` 回调上重试。
            unavailable = true
            System.err.println(
                "[CDL] 完整歌词轴探针：未找到 $PLAYBACK_SERVICE，" +
                    "已停用探针（歌词退回逐行推送模式，功能不受影响）。"
            )
        }
        return traversed
    }

    private fun isMonitor(value: Any): Boolean = value.javaClass.name == PLAYBACK_MONITOR

    /**
     * 把 `getLyricsDocument()` 的返回值剥到"看起来像文档"的那一层。
     *
     * 为什么需要：该方法可能返回**容器**（如 `StateFlow` / `AtomicReference` 之类），
     * 真正的文档在 `getValue()` 之后。判定方式是**"它有没有行列表"**，
     * 而不是"它是不是某个具体类型" —— 后者会把宿主版本差异变成硬失败。
     */
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

    /**
     * 从一个"文档"对象里取出原始行列表。
     *
     * 先试具名成员（`getLyricsLines` / `lyricsLines` / `_lyricsLines`），
     * 再退回"扫所有集合字段，取第一个**第一项能映射成行**的"。
     * 后者是宿主改字段名时的兜底 —— 代价是可能多读几个字段，只在每曲一次。
     */
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

    /** 把宿主的一行映射成 [LyricLine]；映射不出必要字段则返回 null（该行被跳过）。 */
    private fun mapLine(value: Any?): LyricLine? {
        value ?: return null
        val start = longOf(value, "getStartTime", "getStartMs")
            ?: longField(value, "startTime", "startMs") ?: return null
        val end = longOf(value, "getEndTime", "getEndMs")
            ?: longField(value, "endTime", "endMs") ?: start

        val cells = invokeNoArg(value, "getLyricsCells") ?: fieldValue(value, "lyricsCells")
            ?: invokeNoArg(value, "getWords") ?: fieldValue(value, "words")
        val words = cells.values()?.mapNotNull(::mapWord).orEmpty()

        // 主文本的三级回退：具名 getter → 具名字段 → **逐字拼接**。
        // 最后一级是有依据的：宿主的 `pureMainText` 就是 `lyricsCells` 各 text 的拼接
        // （见 `LyricLine.kt` 的类 KDoc 与 `recon/24` §1 的三重取证）。
        val rawMain = stringOf(value, "getPureMainText", "getText")
            ?: stringField(value, "pureMainText", "text")
            ?: words.joinToString("").takeIf { it.isNotBlank() }
            ?: return null
        val rawSub = stringOf(value, "getPureSubText", "getTranslation")
            ?: stringField(value, "pureSubText", "translation")

        // 原文 / 译文分离与逐行路径**用同一个函数**（BUG-015 的修复）。
        // 两处若各写一套，会出现"逐行模式译文对、整轴模式译文错"这类不一致。
        val (main, sub) = splitBilingualLine(rawMain, rawSub)
        if (main.isBlank()) return null

        return LyricLine(
            startMs = start,
            endMs = end,
            text = main,
            translation = sub,
            // 逐字同样要裁到**纯原文**长度：cells 拼出的是含译文的整串，
            // 不裁会让 `effectiveWords` 的"拼接精确等于 text"校验整行 fail-closed。
            words = narrowWordsToText(words, main),
            // ⚠️ 译文侧必须**同时**处理，否则整轴接管后译文会丢掉自己的逐字
            // （`CdlRuntime` 的 `Line` 分支在整轴接管后不再执行，
            // 见 `takeOverWithLyricsAxis`）。两处若各写一套，就会出现
            // "逐行模式译文逐字正常、整轴模式译文不逐字"这类不一致。
            translationWords = sub?.let { narrowWordsToTranslation(words, main, it) },
        )
    }

    /** 把一个逐字单元映射成 [LyricWord]。 */
    private fun mapWord(value: Any?): LyricWord? {
        value ?: return null
        val start = longOf(value, "getStartTime", "getStartMs")
            ?: longField(value, "startTime", "startMs") ?: return null
        val end = longOf(value, "getEndTime", "getEndMs")
            ?: longField(value, "endTime", "endMs") ?: start
        val text = stringOf(value, "getText") ?: stringField(value, "text") ?: return null
        return LyricWord(start, end, text)
    }

    // ── 通用反射工具 ──────────────────────────────────────────────────────────

    /**
     * 从 [roots] 出发做**有界广度优先**遍历，返回第一个满足 [matches] 的对象。
     *
     * - `IdentityHashMap` 去重：宿主对象图里**必然有环**（父子互指），
     *   不做身份去重会原地死循环。用身份而非 `equals`，因为宿主对象的 `equals`
     *   语义未知（可能很贵，也可能把两个不同对象判成相等而漏掉目标）。
     * - [traverse] 限定可下钻的包：只走宿主与协程流，**不走我们自己的 `dev.cdl`**
     *   （走进自己的对象图既无意义，又会把插件的引用意外带进遍历范围）。
     */
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

    /** 可下钻的包白名单。**必须**包含 `com.xuncorp.spc.lyrics`（歌词在另一个根包下）。 */
    private fun mayTraverse(type: Class<*>): Boolean = type.name.run {
        startsWith("com.xuncorp.voxzen") ||
            startsWith("com.xuncorp.spc") ||
            startsWith("com.xuncorp.spw") ||
            startsWith("kotlinx.coroutines.flow")
    }

    /** 调一个**无参**方法。找不到 / 调用抛异常 / 访问被拒 → null。 */
    private fun invokeNoArg(owner: Any, name: String): Any? = allMethods(owner.javaClass)
        .firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.let { method -> runCatching { method.trySetAccessible(); method.invoke(owner) }.getOrNull() }

    /** 读一个**具名**字段（沿继承链向上找）。访问被拒 → null。 */
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

    /** `Field.get` 的安全包装：先 `trySetAccessible`，被拒则返回 null（不抛）。 */
    private fun Field.read(owner: Any?): Any? =
        runCatching { trySetAccessible(); get(owner) }.getOrNull()

    /**
     * 类及其全部祖先的**声明**方法。
     *
     * 为什么不用 `type.methods`（只有 public）：宿主这些 getter 可能是包级私有，
     * 只扫 public 会**一个都找不到**，而失败又是静默的（返回 null）。
     * `distinctBy(名字, 参数类型)` 是因为子类可能覆写父类同名方法，重复调用没有意义。
     */
    private fun allMethods(type: Class<*>): Sequence<Method> = sequence {
        yieldAll(type.methods.asSequence())
        yieldAll(generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() })
    }.distinctBy { method -> method.name to method.parameterTypes.toList() }

    /** 类及其全部祖先的声明字段。 */
    private fun allFields(type: Class<*>): Sequence<Field> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }

    /** `Iterable` / `Array` / `Map` 统一取元素列表；其它类型返回 null。 */
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

        /** 从 `WorkshopApi` 出发找 `PlaybackService` 的遍历深度上限（原实现同值）。 */
        private const val SERVICE_DEPTH = 5

        /** 从 `PlaybackService` 出发找 `PlaybackMonitor` 的遍历深度上限（原实现同值）。 */
        private const val MONITOR_DEPTH = 2

        /** `getLyricsDocument()` 返回值最多剥几层 `getValue()`。 */
        private const val MAX_UNWRAP = 3

        /** 每曲最多尝试探测几次。见 [probeOnce] 的「只探一次的准确边界」。 */
        private const val MAX_ATTEMPTS_PER_TRACK = 3

        // ── 2026-09-19 删除 `shouldProbe(hostLyricsProbeEnabled, lowPerformance)` ──
        //
        // 它是一个**从未接线的死函数**：全工程只有定义、**没有任何调用点**
        // （逐键审计 `performance.low` 时顺带发现）。
        //
        // 它本该是"什么时候该探"的判据（用户没关 `performance.host_lyrics_probe`
        // 且没开低性能档）。但探针后来被停用（见 `CdlRuntime` 的
        // `hostLyricsProbeEnabled`），这个判据就**再也没有住户**；
        // 而它引用的 `performance.low` 本身也已被删除（真死设置，见 `SettingRegistry`）。
        //
        // > 两个都没有住户的东西互相引用，是"死代码守恒"的典型形态 ——
        // > 单看任何一个都像是"还有人用"。
        //
        // 若将来要恢复探针，判据直接写"读 `performance.host_lyrics_probe`"即可，
        // 不必再引入一个纯函数。
    }
}
