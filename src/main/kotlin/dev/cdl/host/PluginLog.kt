// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 把插件的诊断输出**落盘**。
 *
 * ## 为什么必须有它（2026-09-16 真机验收的教训）
 *
 * 首次真机验收时，插件"不显示歌词、无托盘图标"，而我们**完全无法诊断** ——
 * 因为插件 61 处 `System.err.println` 的输出**全部丢失了**：
 *
 * - 宿主的日志配置只有 `ConsoleAppender`（输出到 **STDOUT**，见 `logs.txt` 的生成方式）；
 * - 而宿主是 **jpackage 打的 GUI 子系统**（`Salt Player for Windows.exe`），
 *   **GUI 子系统没有控制台**，STDERR 无处可去；
 * - 结果：插件确实运行了（`workshop/data/dev.cdl.spw/config.json`
 *   被写入，含全部 40 项设置值），但我们**看不到它做了什么的任何记录**。
 *
 * **"插件跑过但没留痕迹"比"插件崩溃"更难查** —— 后者至少有堆栈。
 * 这个类就是为了消除这种状态。
 *
 * ## 为什么用"镜像 stderr"而不是"新建一个日志 API"
 *
 * 两条路：
 *
 * | 方案 | 改动量 | 风险 |
 * |---|---|---|
 * | 新建 `PluginLog.info(...)` 并替换全部 61 处调用 | 61 处编辑 | 漏改一处就少一条关键日志；且新增代码容易忘记用它 |
 * | **镜像 `System.err`** | **1 处安装点** | 全局副作用（见下） |
 *
 * 选后者。理由：**"将来新写的日志自动落盘"** 是它最大的价值 ——
 * 不依赖任何人记得"要用 PluginLog"。
 *
 * ## 全局副作用与它的边界（必须知道）
 *
 * `System.setErr` 是**进程级**的，会影响同进程的所有代码 ——
 * 包括**宿主自己**写到 stderr 的内容。
 *
 * 处置：**用 Tee，不是重定向** —— 原 stderr 仍然收到全部输出
 * （宿主的日志行为完全不变），文件只是**额外**的副本。
 * 这是我们能接受的副作用边界：**只增不减**。
 *
 * ## 落盘位置与轮转
 *
 * `%APPDATA%\Salt Player for Windows\workshop\data\<Plugin-Id>\super-island.log`
 *
 * 与配置文件同目录（`ConfigHelper.getConfigPath().parent`）——
 * 这样"打开数据目录"就能同时拿到配置与日志，排查时不必四处找。
 *
 * **轮转**：超过 [MAX_BYTES] 时改名为 `super-island.log.1`（覆盖旧的）。
 * 单文件上限 1 MB 足够容纳数十次启动的诊断输出，且不会无限增长。
 * 不做多份轮转 —— 排查插件问题只需要"最近这一次"，保留太多份反而难找。
 */
internal object PluginLog {

    private const val LOG_FILE_NAME = "cdl.log"
    private const val ROTATED_FILE_NAME = "cdl.log.1"

    /** 单文件上限（字节）。超过则轮转（旧文件被覆盖）。 */
    private const val MAX_BYTES: Long = 1L * 1024 * 1024

    @Volatile
    private var installed = false

    /** 已落盘的日志文件路径（供"关于"对话框或诊断提示显示）。 */
    @Volatile
    var logFile: Path? = null
        private set

    /**
     * 安装 stderr 镜像。**幂等** —— 重复调用只有第一次生效。
     *
     * ## 契约：永不抛异常
     *
     * 本方法在插件启动路径上被调用。日志系统装不上是**降级**（回到"日志丢失"的原状），
     * 不该让插件启动失败 —— 那会因为一个诊断辅助把整个插件搞挂，代价极不对称。
     *
     * @param dataDir 插件数据目录。`null`（设置系统不可用）时**不安装**，
     *   因为此时拿不到 `ConfigHelper.getConfigPath()` —— 宁可没有文件日志，
     *   也不要往一个猜出来的路径写（可能污染用户目录）。
     */
    /**
     * **撤销 stderr 镜像**（2026-10-18，P0）。
     *
     * 不撤销的后果：`System.err` 是一个**进程级**的静态引用，指向持有
     * 插件 classloader 与日志文件流的 `PrintStream` —— 插件被 PF4J 卸载后
     * 这份引用仍在，classloader 无法回收、文件句柄不释放（反复重载即持续泄漏）。
     *
     * 必须在 `CdlRuntime.close()` 里调用。
     */
    fun uninstall() {
        if (!installed) return
        installed = false
        runCatching { originalErr?.let { System.setErr(it) } }
        runCatching { fileStream?.close() }
        originalErr = null
        fileStream = null
    }

    /** 安装前的 `System.err`（供 [uninstall] 还原）。 */
    private var originalErr: PrintStream? = null

    /** 镜像文件流（供 [uninstall] 关闭）。 */
    private var fileStream: PrintStream? = null

    fun install(dataDir: Path?) {
        if (installed) return
        installed = true
        if (dataDir == null) {
            // 设置系统降级时走到这里。此时 stderr 仍在（只是会丢），
            // 保留原样比往未知位置写文件更安全。
            return
        }

        runCatching {
            Files.createDirectories(dataDir)
            val target = dataDir.resolve(LOG_FILE_NAME)
            rotateIfNeeded(target)

            val fileOut = PrintStream(
                Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                ),
                // autoFlush = true：**必须**。插件可能在任意时刻被宿主终止，
                // 缓冲未刷出的日志会整段丢失 —— 那正是最需要它们的时候。
                true,
                StandardCharsets.UTF_8,
            )

            val original = System.err
            originalErr = original
            fileStream = fileOut
            System.setErr(PrintStream(TeeOutputStream(original, fileOut), true, StandardCharsets.UTF_8))
            logFile = target

            // 这条日志本身会进文件（此时镜像已生效）—— 它是"日志可用了"的标记。
            System.err.println("[CDL] 日志已接管 stderr → $target")
        }.onFailure {
            // 装不上就装不上：退回"只有 stderr"的原状。
            System.err.println("[CDL] 日志落盘安装失败（诊断输出将只到 stderr）：$it")
        }
    }

    /**
     * 轮转：把超限的日志改名，让新日志从头写。
     *
     * 用 `REPLACE_EXISTING` 覆盖旧的 `.1` —— 只保留**上一份**，
     * 理由见类 KDoc（排查插件问题只需最近一次）。
     */
    private fun rotateIfNeeded(target: Path) {
        runCatching {
            if (!Files.exists(target)) return
            if (Files.size(target) < MAX_BYTES) return
            Files.move(
                target,
                target.resolveSibling(ROTATED_FILE_NAME),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /**
     * 同时写入两个流的输出流。
     *
     * ## 为什么失败要吞掉
     *
     * 写日志失败（磁盘满、权限变化）**绝不能**让调用方的 `println` 抛异常 ——
     * 那会把"记日志"这个辅助动作变成新的故障源。
     * 故 [write] 里的两路都各自 try/catch，任一失败都不影响另一路，也不外抛。
     */
    private class TeeOutputStream(
        private val first: OutputStream,
        private val second: OutputStream,
    ) : OutputStream() {

        override fun write(byte: Int) {
            runCatching { first.write(byte) }
            runCatching { second.write(byte) }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            runCatching { first.write(bytes, offset, length) }
            runCatching { second.write(bytes, offset, length) }
        }

        override fun flush() {
            runCatching { first.flush() }
            runCatching { second.flush() }
        }

        override fun close() {
            // **不关 first**（那是宿主的 stderr，关掉会让宿主后续日志全部丢失），
            // 只刷 second。这是"只增不减"边界的又一次体现。
            runCatching { second.flush() }
        }
    }
}
