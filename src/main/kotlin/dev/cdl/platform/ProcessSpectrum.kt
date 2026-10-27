// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.platform

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicReference

internal class ProcessSpectrum(

    private val pluginPath: String,
) : AutoCloseable {

    private val latest = AtomicReference<FloatArray?>(null)

    private val reason = AtomicReference<String?>(null)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var watchdog: Thread? = null

    @Volatile
    private var closed = false

    fun start() {
        if (closed) return
        if (process != null) return

        val exe = exeFile()
        if (!exe.isFile) {

            setReason("频谱助手不存在：${exe.absolutePath}")
            return
        }

        val started = runCatching {
            ProcessBuilder(exe.absolutePath, ProcessHandle.current().pid().toString())

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

            isDaemon = true
            start()
        }

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

    fun bands(): FloatArray? = latest.get()

    fun unavailableReason(): String? = reason.get()

    private fun setReason(text: String?) {
        reason.set(text)
    }

    fun isReady(): Boolean = latest.get() != null

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

            val exited = runCatching { started.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!exited) runCatching { started.destroyForcibly() }
        }

        reader = null
    }

    private fun exeFile(): File =
        File(File(File(pluginPath, "classes"), "native"), "spw-spectrum.exe")

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
                    line.startsWith("#") -> Unit
                    line == "READY" -> Unit
                    else -> {
                        val parsed = parseBands(line)
                        if (parsed != null) {
                            latest.set(parsed)
                            setReason(null)
                        } else {
                            setReason(line.trim())
                        }
                    }
                }
            }
        }

        if (!closed && latest.get() == null && reason.get() == null) {
            setReason("频谱助手未输出任何数据即退出")
        }
        process = null
    }

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

        const val READY_TIMEOUT_MS: Long = 8000L

    }
}
