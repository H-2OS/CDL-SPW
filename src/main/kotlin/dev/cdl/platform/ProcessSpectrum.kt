// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程回环频谱的**数据源**（Java 侧）。
 *
 * ## 它解决什么问题
 *
 * `render/SpectralPainter` 早就有**绘制能力**，但一直没有**数据** ——
 * 于是 `spectrum.enabled` 长期是**死设置**：用户在设置里打开它，画面零变化
 * （见 `BUG-025` / `BUG-043`）。
 *
 * 本类负责把「进程回环频谱助手」（`native/spectrum/` 下的 `.cs` 源码编译成 `spw-spectrum.exe`）
 * 拉起来、读它的输出、把频段电平交给渲染层。
 *
 * ## 协议（**与 C# 侧的唯一契约**，逐字对应 `SpectrumProcess.cs` 的注释）
 *
 * ```
 *   argv[1]  = 目标进程 PID（十进制）
 *   stdout   = 逐行文本：
 *                "# …"          → 信息性输出，**忽略**（只转日志）
 *                "READY"        → 已开始抓取，之后才会有电平行
 *                "0.12,0.34,…"  → 一组频段电平，0..1
 *                其它           → 错误说明 → 记为"不可用原因"
 *   stderr   = 与 stdout 同流（本类用 `redirectErrorStream(true)` 合并）
 *   stdin    = 读到任意一行即**优雅退出**（[close] 用它）
 *   退出码   = 0 正常；1 出错
 * ```
 *
 * ## 为什么目标 PID 就是"当前进程"
 *
 * 插件与宿主**同进程同一个 JVM**（单 JVM，`app.mainclass=MainKt` ——
 * 见 `recon/17-沙箱文件访问可行性.md`）。故 `ProcessHandle.current().pid()`
 * **就是宿主 SPW 的 PID**，不需要去枚举窗口反查宿主。
 * 这与 `WindowInterop` 找 HWND 的处境不同 —— 那里要定位的是**另一个**窗口。
 *
 * ## 线程纪律（两条硬要求）
 *
 * 1. **读线程必须是 daemon** —— 非守护线程会**阻止 JVM 退出**，
 *    宿主关闭时进程会挂住不退。本项目为此专门记录过（见 `SettingsRuntime` 的轮询线程）。
 * 2. **不阻塞 EDT** —— 启动进程、读管道全在后台线程；渲染层只读
 *    [bands] 这个 `AtomicReference`，**无锁、无 I/O**。
 *
 * ## 故障域隔离（为什么值得起一个独立进程）
 *
 * 进程回环是 COM + 实时音频线程的活。放进 JVM 要走 JNI/JNA，
 * 出错时崩的是**整个播放器**。独立进程的故障域是隔离的：
 * **助手挂了，词岛只是没有频谱，音乐照放。**
 * 故本类对"进程起不来 / 中途挂掉"一律按**不可用**处理，绝不向上抛。
 */
internal class ProcessSpectrum(
    /** 插件安装根（`PluginContext.pluginPath`）。exe 在其 `classes/native/` 下。 */
    private val pluginPath: String,
) : AutoCloseable {

    /** 最新一组频段电平；`null` = 尚无数据（未 READY / 已停止 / 出错）。 */
    private val latest = AtomicReference<FloatArray?>(null)

    /** 不可用原因；`null` = 一切正常。渲染层可据此提示用户。 */
    private val reason = AtomicReference<String?>(null)

    /** 当前进程句柄；`null` = 未启动。 */
    @Volatile
    private var process: Process? = null

    /** 读线程；`null` = 未启动。持有它只为 `join` 时等它收尾。 */
    @Volatile
    private var reader: Thread? = null

    /**
     * **就绪看门狗** —— 超时未就绪且无原因时补一条原因。
     *
     * ## 为什么必须有它（2026-09-17 实测发现）
     *
     * C# 侧的 `# 格式：` / `READY` 写在**回环激活 + `Initialize` 成功之后**
     * （见 `SpectrumProcess.cs` 的 `RunCapture`）。实测：拿一个**没有音频会话**的
     * 进程（`explorer.exe`）当目标时，它**20 秒仍无任何输出且不退出** ——
     * 卡在 `ActivateAudioInterfaceAsync` 里。
     *
     * 于是**没有任何线索**留给用户：渲染层拿不到 `bands`、也拿不到 `reason`
     * → 频谱就是不亮，而用户无从判断是"没开开关"还是"助手卡住了"。
     *
     * 这正是本项目反复吃的「**静默失败**」：**能观测**比**能修复**更优先 ——
     * 先让它说话，再谈怎么修。
     *
     * ## 为什么用独立 daemon 线程而不是定时器
     *
     * 它只跑一次（睡一段时间后检查一次）—— 为一次检查建一个
     * `ScheduledExecutorService` 是杀鸡用牛刀，且多一个需要 `shutdown` 的资源。
     * **daemon 是硬要求**（同 [reader]）：非守护线程会阻止 JVM 退出。
     */
    @Volatile
    private var watchdog: Thread? = null

    /** 关闭标记：读线程与 [close] 都看它。 */
    @Volatile
    private var closed = false

    /**
     * 启动助手。
     *
     * **幂等**：已在运行时直接返回（`spectrum.enabled` 可能被反复切换）。
     * **永不抛**：任何失败都落到 [reason]，由渲染层决定怎么呈现。
     */
    fun start() {
        if (closed) return
        if (process != null) return

        val exe = exeFile()
        if (!exe.isFile) {
            // 常见于"用 `compileKotlin` 跑了但没打 `plugin` 包"的开发场景 ——
            // 或者构建时 `buildSpectrum` 静默跳过了（那条路已在构建脚本里堵住）。
            setReason("频谱助手不存在：${exe.absolutePath}")
            return
        }

        val started = runCatching {
            ProcessBuilder(exe.absolutePath, ProcessHandle.current().pid().toString())
                // 协议规定 stderr 与 stdout 同流（C# 侧把错误也写 stdout，
                // 这里合并是为了"无论它写哪一路，我们都读得到原因"）。
                .redirectErrorStream(true)
                .directory(exe.parentFile)
                .start()
        }.getOrElse {
            setReason("频谱助手启动失败：$it")
            return
        }

        process = started
        setReason(null)
        latest.set(null)

        reader = Thread({ readLoop(started) }, "CDL spectrum reader").apply {
            // ⚠️ daemon 是硬要求 —— 见类 KDoc 的线程纪律第 1 条。
            isDaemon = true
            start()
        }

        // 就绪看门狗 —— 见 [watchdog] 的 KDoc（实测发现助手可能静默卡在激活里）。
        watchdog = Thread({
            runCatching {
                Thread.sleep(READY_TIMEOUT_MS)
                if (!closed && !isReady() && reason.get() == null) {
                    setReason(
                        "频谱助手启动后 ${READY_TIMEOUT_MS / 1000} 秒内未就绪" +
                            "（目标进程可能暂无音频；播放开始后会自动恢复）"
                    )
                }
            }
        }, "CDL spectrum watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /** 最新一组频段电平；无数据时返回 `null`（渲染层据此跳过绘制）。 */
    fun bands(): FloatArray? = latest.get()

    /**
     * 是否已经打过「首帧」诊断。**只由读线程写**（见 [logFrameDiagnostics]）。
     */
    @Volatile
    private var firstFrameLogged = false

    /**
     * 是否已经打过「首个**非静音**帧」诊断。见 [logFrameDiagnostics]。
     */
    @Volatile
    private var firstLoudFrameLogged = false

    /**
     * 把**首帧**与**首个非静音帧**的诊断写进日志（各只打一次）。
     *
     * ## 为什么必须有它（2026-09-21，用户第二次报「频谱依旧不显示」）
     *
     * 截至本轮，日志能告诉我们的只有"助手**已就绪**"，而**就绪 ≠ 有数据**。
     * 于是三种完全不同的故障在日志里长得一模一样：
     *
     * | 真实情况 | 日志里的样子 |
     * | --- | --- |
     * | 助手没输出任何数据 | 有 `已就绪`，无后续 |
     * | 有数据但**全是 0**（解码格式不符 / 采集到静音） | 有 `已就绪`，无后续 |
     * | 数据正常，是**渲染层**没画 | 有 `已就绪`，无后续 |
     *
     * 而这三者的修法**完全不同**（改 C# 的格式协商 / 改解码 / 改绘制），
     * 故"猜"的代价很高 —— 本轮真机上就无法区分，只能再要一轮日志。
     *
     * 打这两条之后，一次真机即可判定：
     * · **没有**「首帧」→ 助手没吐出可解析的数据（回去看 C# 侧）；
     * · 有「首帧」且**非零段 = 0**，且**没有**「首个非静音帧」→ 采到了静音/解码全零；
     * · 有「首个非静音帧」→ 数据链路正常，问题在**渲染层**。
     *
     * ## 为什么只打两条、且每帧不算额外东西
     *
     * `bands.maxOrNull()` 是 O(24)，相对"每 20 ms 一次"的调用频率可忽略。
     * 而"只在首次"打印是为了避开 **BUG-017**（日志噪声淹没真信号）——
     * 电平行本身**每帧都来**，无条件打会瞬间刷出几万行。
     *
     * @param bands 本帧频段电平（已按 0..1 收敛）。
     */
    private fun logFrameDiagnostics(bands: FloatArray) {
        val peak = bands.maxOrNull() ?: 0f
        if (!firstFrameLogged) {
            firstFrameLogged = true
            val nonZero = bands.count { it > SILENT_EPSILON }
            System.err.println(
                "[CDL] 频谱首帧：${bands.size} 段 · 峰值=" +
                    String.format(java.util.Locale.US, "%.4f", peak) +
                    " · 非零段=$nonZero" +
                    if (nonZero == 0) "（⚠️ 全零：采到的是静音或解码失败 → 频谱将不可见）" else "",
            )
        }
        if (peak > SILENT_EPSILON && !firstLoudFrameLogged) {
            firstLoudFrameLogged = true
            System.err.println(
                "[CDL] 频谱首个非静音帧：峰值=" +
                    String.format(java.util.Locale.US, "%.4f", peak) +
                    "（数据链路正常）",
            )
        }
    }

    /** 不可用原因；正常时 `null`。 */
    fun unavailableReason(): String? = reason.get()

    /**
     * 记录不可用原因，**并写进日志**。
     *
     * ## 为什么必须打日志（而不是只存进字段）
     *
     * `unavailableReason()` 目前**没有消费者** —— 渲染层只用 `bands()` 决定画不画。
     * 若只 `reason.set(...)`，原因就**只存在于内存里**：
     * 用户报"频谱不亮"时，日志里什么也没有 —— 又是一次「静默失败」。
     *
     * 打进 stderr 之后，它会随 `PluginLog` 落到
     * `workshop/data/<插件ID>/super-island.log`，用户能直接贴给我们。
     *
     * ## 只在**原因变化**时打
     *
     * `readLoop` 对每一行不可解析的输出都会调它（助手可能连打多行），
     * 无条件打会刷屏 —— 那正是 **BUG-017** 的形态（「日志噪声淹没真信号」）。
     * 故先比对再打。
     */
    private fun setReason(text: String?) {
        if (reason.getAndSet(text) == text) return
        if (text != null) System.err.println("[CDL] 频谱不可用：$text")
    }

    /** 是否已拿到过数据（用于"已就绪"判断，与 [bands] 的瞬时性区分开）。 */
    fun isReady(): Boolean = latest.get() != null

    /**
     * 停止助手并释放资源。**幂等，永不抛。**
     *
     * ## 为什么先往 stdin 写一行
     *
     * 协议约定「stdin 读到任意一行即**优雅退出**」——
     * 让 C# 侧有机会收尾（释放回环客户端、停音频线程）。
     * 直接 `destroy()` 也能杀掉它，但那是**硬杀**：COM 侧来不及释放，
     * 在部分设备上会留下占用。故先给机会、再兜底。
     */
    override fun close() {
        if (closed) return
        closed = true

        val started = process
        process = null
        latest.set(null)

        if (started != null) {
            runCatching {
                BufferedWriter(OutputStreamWriter(started.outputStream)).use { it.write("\n") }
            }
            // 给它一小段时间优雅退出；超时再硬杀。
            // 用 `waitFor` 的带超时重载而不是 `Thread.sleep` —— 前者进程退出即刻返回。
            val exited = runCatching { started.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!exited) runCatching { started.destroyForcibly() }
        }

        // 读线程会在管道关闭后自然结束（`readLine()` 返回 null）；
        // 这里不等它 —— 它是 daemon，且 `close()` 可能在 EDT 上被调，不该阻塞。
        reader = null
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * exe 的运行时路径。
     *
     * 打包布局（见 `build.gradle.kts` 的 `processResources` + `plugin` 任务）：
     * `processResources` 把 `build/native/spw-spectrum.exe` 放进 jar 的 `native/`，
     * `plugin` 任务再把 jar 解开到包内的 `classes/`。
     * → 运行时它在 **`<pluginPath>/classes/native/spw-spectrum.exe`**。
     */
    private fun exeFile(): File =
        File(File(File(pluginPath, "classes"), "native"), "spw-spectrum.exe")

    /**
     * 读循环。**在后台 daemon 线程上跑。**
     *
     * 按协议分类每一行：
     * - `#` 开头 → 信息性，转日志（**不**当数据、**不**当错误）
     * - `READY`   → 只记日志（"已就绪"本身不是数据）
     * - 逗号分隔的数字 → 频段电平
     * - 其它       → 不可用原因
     *
     * ## 为什么"其它"一律当错误原因
     *
     * C# 侧的错误说明就是**裸文本**（协议里没有 `ERROR:` 前缀）。
     * 若把无法解析的行**静默丢掉**，助手起不来时用户只会看到"频谱不亮"
     * 而**没有任何线索** —— 这正是本项目反复吃亏的"静默失败"形态。
     * 故宁可把它当原因记下来（哪怕偶尔误记一条调试输出，代价也远小于无线索）。
     */
    private fun readLoop(started: Process) {
        val stream = runCatching { started.inputStream }.getOrNull() ?: run {
            setReason("频谱助手没有可读输出")
            return
        }
        BufferedReader(InputStreamReader(stream)).use { reader ->
            while (!closed) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                if (line.isBlank()) continue
                when {
                    line.startsWith("#") -> System.err.println("[CDL] 频谱助手：${line.removePrefix("#").trim()}")
                    line == "READY" -> System.err.println("[CDL] 频谱助手已就绪")
                    else -> {
                        val parsed = parseBands(line)
                        if (parsed != null) {
                            latest.set(parsed)
                            setReason(null)
                            logFrameDiagnostics(parsed)
                        } else {
                            setReason(line.trim())
                        }
                    }
                }
            }
        }
        // 走到这里说明管道关了（助手退出 / 被 close()）。若此时还没拿到过数据，
        // 那就是"从头到尾没成功"—— 记一条原因，别让用户对着不亮的频谱猜。
        if (!closed && latest.get() == null && reason.get() == null) {
            setReason("频谱助手未输出任何数据即退出")
        }
        process = null
    }

    /**
     * 解析一行频段电平。**任何不合规的输入返回 `null`**（由调用方当错误原因）。
     *
     * 合规性要求（与 C# 侧 `SpectrumAnalyzer.BandCount` 对应）：
     * - 至少两个字段（单值不是"一组频段"）；
     * - 每个字段可解析为数字；
     * - 值域按 `0..1` **收敛**而不是拒绝 —— 助手在极端音频下可能吐出略超界的值
     *   （浮点累积误差），为此丢一整帧数据不值得。
     *
     * ⚠️ **不校验字段个数**：本方法只要求"≥2"，段数由助手决定。
     * 2026-09-17 C# 侧把 `BandCount` 从 12 提到 **24**（用户要求"覆盖的频率更完整"），
     * Kotlin 侧**一行未改** —— 这正是当初"不写死段数"的收益。
     * 渲染侧的条数另由宽度推导（见 `render/SpectrumGeometry.kt`），
     * 与数据源的段数是两个独立的量。
     */
    private fun parseBands(line: String): FloatArray? {
        val parts = line.split(',')
        if (parts.size < 2) return null
        val out = FloatArray(parts.size)
        for (i in parts.indices) {
            val v = parts[i].trim().toFloatOrNull() ?: return null
            out[i] = v.coerceIn(0f, 1f)
        }
        return out
    }

    private companion object {
        /**
         * 「就绪」的等待上限（毫秒）。
         *
         * ## 为什么是 8 秒（2026-09-17 实测标定）
         *
         * 目标进程**有**音频会话时，从启动到 `READY` 通常**在一秒内**
         * （回环激活 + 格式协商都是毫秒级）。
         * 而目标**没有**音频会话时，`ActivateAudioInterfaceAsync` **一直不返回**
         * —— 实测拿 `explorer.exe` 当目标，**20 秒仍无任何输出且不退出**。
         *
         * 8 秒：远大于正常路径（< 1 s），又短到用户不会觉得"卡了很久"。
         *
         * ## 它只影响"多久之后给出原因"，**不影响功能**
         *
         * 助手若在第 9 秒真的就绪了，`readLoop` 收到数据时会
         * `reason.set(null)` 把这条原因清掉 —— 频谱照常出现。
         */
        const val READY_TIMEOUT_MS: Long = 8000L

        /**
         * 判定"这一帧算静音"的阈值。
         *
         * **必须与 `SpectralPainter.SILENT_EPSILON` 同值**（同为 `0.004f`）——
         * 那边用它决定"频谱是否已衰减干净"（进而决定帧驱动要不要停）。
         * 两处若不同值，会出现"这里说还有声音、那边说已经静音"的错配，
         * 而表现只是"频谱偶尔闪一下"，极难定位。
         */
        const val SILENT_EPSILON: Float = 0.004f
    }
}
