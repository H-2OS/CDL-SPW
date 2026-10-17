// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render.gpu

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.image.BufferedImage
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ★★★ **可复用的离屏 GL 渲染器**（JNA 直调 `opengl32.dll` + `gdi32.dll`，★ 零新增第三方依赖）。
 *
 * ## 它做什么
 *
 * 把「**一段 fragment shader 源码 + 一张背景位图 + 一组 uniform**」跑成「**一张 `TYPE_INT_ARGB` 位图**」，
 * 全程**不需要窗口**。管线：
 * ```
 * 懒建上下文 → 编译/链接 program → 建 FBO（颜色附件 = RGBA8 纹理）
 *   → 上传背景纹理 → 设 uniform → 全屏 quad → glReadPixels → 转 TYPE_INT_ARGB
 * ```
 *
 * ## ★★★ 为什么可以不用第三方 GL 库（许可分析结论）
 *
 * | 候选 | 许可 | 判定 |
 * |---|---|---|
 * | JOGL | 主 BSD，但源码树含 NVIDIA Cg 等杂项 | ⚠️ 需逐 jar 核实 |
 * | LWJGL 3 | BSD 3-Clause，但离屏需 GLFW/EGL | ✓ 干净但多依赖 |
 * | ★★★ **JNA 直调系统 DLL** | ★ **只调 Windows 自带系统 DLL** | ★★★ **无第三方许可** ✓ |
 *
 * ⇒ ★ 项目**已依赖 JNA 5.17.0** ⇒ **零新增依赖 · 零许可风险 · 零体积增长** ✓
 * ★ 这条路已被 `GpuOffscreenProbe` 验证可跑（16 通过 / 0 失败，GL 4.6 / AMD Radeon 780M）。
 *
 * ## ★★★ 线程归属（★ 关键）
 *
 * ★ **GL 上下文是【线程亲和】的**（`wglMakeCurrent` 之后只在当前线程有效）——
 * ★ 故本类**把全部 GL 调用钉在【一个】后台线程上**：内部持有一个**单线程执行器**，
 * [render] 只是「提交任务 + 等结果」，**调用方在哪个线程调都安全** ✓
 *
 * ★★ 该线程**降到 `Thread.MIN_PRIORITY`** —— 本项目实测过：后台满核计算会
 * **与 EDT 的帧节奏抢 CPU**（`FramePacingProbe` 从 19/0 掉到 18/1），降到最低优先级后才恢复 ✓
 *
 * ## ★★ 失败即降级（★ 本类的核心契约）
 *
 * ★ **任何一步失败都返回 `null`，绝不抛异常**（拿不到 DC / 上下文 / 编译失败 / FBO 不完整 /
 * `glGetError` 非零 / 读回失败）⇒ ★ **调用方（CPU 路径）永远有回退** ✓
 * ★ 失败原因记在 [lastError]（★ 让"为什么没上 GPU"**可查**，而不是静默回退）。
 *
 * ## ★ shader 版本与 profile 兼容性（★ 诊断用）
 *
 * ★ 探针实测上下文是 **GL 4.6 Compatibility** ⇒ ★ **`#version 330 core` 可用** ✓
 * ★ 但**驱动差异**真实存在 ⇒ [render] 接受一个**可选的回退 shader 源码**
 * （★ 惯例是 `#version 120`，那时 `in/out` 要写成 `attribute/varying`）：
 * **主版本编译失败 ⇒ 打印 `glGetShaderInfoLog` ⇒ 自动改试回退版本** ✓
 *
 * ## ⚠️ 已知边界（如实记录）
 *
 * · ★ **只在 Windows 可用**（`opengl32.dll` / `gdi32.dll`）⇒ 非 Windows ⇒ 返回 `null` ✓
 * · ★ **驱动不支持 GL 3.0+ / FBO** ⇒ 返回 `null` ⇒ 调用方回退 CPU ✓
 * · ★ **只支持一个 `sampler2D`**（约定名 = uniform 表里值为 [BufferedImage] 的那个）——
 *   本用途（液态玻璃）只有一张背景纹理 ✓
 * · ★ **不处理多 GPU 切换**（用系统默认适配器）
 */
class GlOffscreen {

    // ══════════════════════════════════════════════════════════════════════════
    // 公开入口
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ★★★ **离屏渲染一张图**。
     *
     * ## 契约
     *
     * 1. ★ **懒建上下文**（首次调用才建；★ 建失败 ⇒ 返回 `null`，且**不抛异常**）
     * 2. ★ 编译 [fragmentShaderSource]（失败 ⇒ 打印日志 ⇒ 改试 [fallbackFragmentShaderSource]）
     * 3. ★ 建 FBO（颜色附件纹理 `GL_RGBA8`）→ 上传背景 → 全屏 quad → 读回
     * 4. ★ 返回 **`TYPE_INT_ARGB`** 的位图（★ **已按 AWT 语义翻转 Y**）
     * 5. ★ **任何一步失败 ⇒ 返回 `null`**（★ 调用方回退 CPU）
     *
     * ## uniform 表（[uniforms]）的取值约定
     *
     * | 值类型 | 行为 |
     * |---|---|
     * | [BufferedImage] | ★ 作为 `sampler2D` 上传到 **0 号纹理单元**，并把该 uniform 设为 `0` |
     * | [Int] | `glUniform1i` |
     * | [Float] / [Double] | `glUniform1f` |
     * | [FloatArray] / [DoubleArray]（≥2） | `glUniform2f`（取前两个） |
     * | 其它 / `null` | 跳过（★ 名字取不到 location 也跳过 —— 允许被优化掉的 uniform） |
     *
     * ⚠️ **背景位图必须与目标同尺寸**（`width × height`）；不同尺寸时**内部缩放到目标尺寸**
     * （★ 宁可多一次缩放，也不要 `getRGB` 越界抛异常）。
     *
     * @param width 目标宽（设备像素）· `<= 0` ⇒ 返回 `null`
     * @param height 目标高（设备像素）· `<= 0` ⇒ 返回 `null`
     * @param fragmentShaderSource **主** fragment shader 源码（含 `#version`）
     * @param uniforms uniform 表（★ 键 = GLSL 里的 uniform 名）
     * @param fallbackFragmentShaderSource **回退**源码（★ 主版本编译失败时改试它）；
     *   ★ 默认 `null` ⇒ 不回退。★ 顶点着色器会**按 `#version` 自动配对**（330 / 120 两套）
     */
    fun render(
        width: Int,
        height: Int,
        fragmentShaderSource: String,
        uniforms: Map<String, Any?>,
        fallbackFragmentShaderSource: String? = null,
    ): BufferedImage? {
        if (width <= 0 || height <= 0) return null
        if (closed) return null
        val e = executor()
        val task = Callable {
            try {
                renderOnGlThread(width, height, fragmentShaderSource, uniforms, fallbackFragmentShaderSource)
            } catch (t: Throwable) {
                // ★ 故障域隔离：GL 线程上任何异常都不外泄 ⇒ 调用方回退 CPU
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                null
            }
        }
        return try {
            e.submit(task).get()
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    /**
     * ★★ **释放 GL 资源与上下文**（★ 在 GL 线程上做；★ 失败不抛）。
     *
     * ★ 语义：删除 program / FBO / 纹理 / VBO / VAO → `wglMakeCurrent(null,null)`
     * → `wglDeleteContext` → `DeleteDC` → 关闭执行器。
     * ★ 之后 [render] 恒返回 `null`（★ 不再重建 —— 与"失败即降级"同一口径）。
     */
    fun close() {
        val e = executorRef ?: return
        try {
            e.submit { destroyGl() }.get()
        } catch (_: Throwable) {
        }
        try {
            e.shutdown()
        } catch (_: Throwable) {
        }
        executorRef = null
        closed = true
    }

    /**
     * ★ **报告用的是哪块 GPU**（★ 诊断用；★ 会**懒建上下文**，失败返回原因串）。
     *
     * ★ 形如 `AMD Radeon 780M · GL 4.6.0 ...`；★ 不可用时返回 `GPU 不可用（<原因>）`。
     */
    fun describe(): String {
        val e = executorRef ?: return "未启动（尚未渲染）"
        return try {
            e.submit(Callable { describeOnGlThread() }).get()
        } catch (_: Throwable) {
            "查询失败（$lastError）"
        }
    }

    /** ★★ 最近一次失败原因（★ **只报告，不参与逻辑** —— 供探针/日志读）。 */
    @Volatile
    var lastError: String = ""
        private set

    // ══════════════════════════════════════════════════════════════════════════
    // 单线程执行器（★ GL 上下文绑在它上面）
    // ══════════════════════════════════════════════════════════════════════════

    private val executorLock = Any()

    @Volatile
    private var executorRef: ExecutorService? = null

    @Volatile
    private var closed = false

    /**
     * ★ **单线程 Daemon 池**（★ 懒创建）。
     *
     * ★ 线程名 `spw-gl`（照项目里 `spw-glass-worker` 的写法）·
     * ★ `isDaemon = true`（不挡进程退出）·
     * ★★ `priority = MIN_PRIORITY`（★ **不与 EDT 的帧节奏抢 CPU** —— 见类 KDoc 的实测）。
     */
    private fun executor(): ExecutorService {
        executorRef?.let { return it }
        synchronized(executorLock) {
            executorRef?.let { return it }
            val e = Executors.newSingleThreadExecutor { r ->
                Thread(r, "spw-gl").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            executorRef = e
            return e
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 上下文状态（★ 全部只在 GL 线程上读写）
    // ══════════════════════════════════════════════════════════════════════════

    private var hdc: Pointer? = null
    private var hglrc: Pointer? = null
    private var gl: Gl? = null
    private var ctxReady = false
    private var ctxFailed = false

    private var program = 0
    private var programKey = ""
    private val uniformLocs = HashMap<String, Int>()

    private var fbo = 0
    private var inputTex = 0
    private var outputTex = 0
    private var vbo = 0
    private var vao = 0
    private var texW = 0
    private var texH = 0

    // ══════════════════════════════════════════════════════════════════════════
    // 渲染主体（★ 只在 GL 线程上跑）
    // ══════════════════════════════════════════════════════════════════════════

    private fun renderOnGlThread(
        width: Int,
        height: Int,
        fs: String,
        uniforms: Map<String, Any?>,
        fsFallback: String?,
    ): BufferedImage? {
        if (!ensureContext()) return null
        val g = gl ?: return null
        val prog = ensureProgram(g, fs, fsFallback) ?: return null
        if (!ensureTargets(g, width, height)) return null
        ensureQuad(g)

        // ★ 排空历史错误 —— GL 的错误是**累积**的（不是"最后一次"），
        //   否则探测阶段的遗留错误会被算到本帧头上（本项目 R7 已踩过这个坑）✓
        drainErrors()

        // ── ① 上传背景（uniform 表里值为 BufferedImage 的那个）────────────────
        val bg = uniforms.values.firstOrNull { it is BufferedImage } as? BufferedImage
        if (bg != null) uploadTexture(g, inputTex, bg, width, height)

        // ── ② 绑 FBO + viewport + 清屏 ───────────────────────────────────────
        g.bindFramebuffer.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, fbo))
        g.viewport.invokeVoid(arrayOf<Any>(0, 0, width, height))
        g.disable.invokeVoid(arrayOf<Any>(GL_DEPTH_TEST))
        g.disable.invokeVoid(arrayOf<Any>(GL_BLEND))
        g.clearColor.invokeVoid(arrayOf<Any>(0f, 0f, 0f, 0f))
        g.clear.invokeVoid(arrayOf<Any>(GL_COLOR_BUFFER_BIT))

        // ── ③ 程序 + uniform（★ 采样器恒在 0 号单元）──────────────────────────
        g.useProgram.invokeVoid(arrayOf<Any>(prog))
        g.activeTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE0))
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, inputTex))
        applyUniforms(g, prog, uniforms)

        // ── ④ 全屏 quad ─────────────────────────────────────────────────────
        // ★ 顶点**必须走 VBO** —— GL 3.1+ 移除了"无 VBO 的客户端属性数组"
        //   （★ 本项目 R7 实测：客户端指针 ⇒ `GL_INVALID_OPERATION` 0x502）✓
        if (vao != 0) g.bindVertexArray!!.invokeVoid(arrayOf<Any>(vao))
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, vbo))
        g.enableVertexAttribArray.invokeVoid(arrayOf<Any>(0))
        g.vertexAttribPointer.invokeVoid(arrayOf<Any>(0, 2, GL_FLOAT, false, 0, 0L))
        g.drawArrays.invokeVoid(arrayOf<Any>(GL_TRIANGLES, 0, 6))
        g.disableVertexAttribArray.invokeVoid(arrayOf<Any>(0))
        if (vao != 0) g.bindVertexArray!!.invokeVoid(arrayOf<Any>(0))

        val err = opengl32!!.glGetError()
        if (err != GL_NO_ERROR) {
            lastError = "glGetError = 0x${Integer.toHexString(err)}（draw 之后）"
            return null
        }

        // ── ⑤ 读回 + 转 TYPE_INT_ARGB ────────────────────────────────────────
        val out = ByteArray(width * height * 4)
        g.readPixels.invokeVoid(
            arrayOf<Any?>(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, out),
        )

        // ★★ Y 翻转：GL 的 `glReadPixels` 第 0 行是**下边**，而 AWT 位图第 0 行是**上边**
        //   ⇒ 逐行对调（★ 不做整幅镜像 —— 只换行号，列不变）✓
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            val src = y * width
            val dst = (height - 1 - y) * width
            for (x in 0 until width) {
                val i = (src + x) * 4
                val r = out[i].toInt() and 0xFF
                val gg = out[i + 1].toInt() and 0xFF
                val b = out[i + 2].toInt() and 0xFF
                val a = out[i + 3].toInt() and 0xFF
                argb[dst + x] = (a shl 24) or (r shl 16) or (gg shl 8) or b
            }
        }
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, width, height, argb, 0, width)
        return img
    }

    private fun describeOnGlThread(): String {
        if (!ensureContext()) return "GPU 不可用（$lastError）"
        val o = opengl32 ?: return "GPU 不可用（opengl32 未加载）"
        val r = o.glGetString(GL_RENDERER)?.getString(0) ?: "?"
        val v = o.glGetString(GL_VERSION)?.getString(0) ?: "?"
        return "$r · GL $v"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 上下文
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ★★ **懒建上下文**（★ 照 `GpuOffscreenProbe` 已验证的顺序）：
     * ```
     * CreateCompatibleDC(NULL) → ChoosePixelFormat → SetPixelFormat
     *   → wglCreateContext → wglMakeCurrent → 取 GL 函数
     * ```
     * ★ **内存 DC 必须用 `PFD_DRAW_TO_BITMAP`**（不是 `PFD_DRAW_TO_WINDOW`）—— 那是**离屏**语义 ✓
     * ★ 失败 ⇒ 记 [lastError] 并**缓存失败**（★ 不每轮重试 —— 避免反复付异常代价）。
     */
    private fun ensureContext(): Boolean {
        if (ctxReady) return true
        if (ctxFailed) return false
        try {
            val os = System.getProperty("os.name") ?: ""
            if (!os.lowercase().contains("win")) return failContext("非 Windows（os.name=$os）")
            val gdi = gdi32 ?: return failContext("gdi32 未加载")
            val ogl = opengl32 ?: return failContext("opengl32 未加载")

            val dc = gdi.CreateCompatibleDC(null) ?: return failContext("CreateCompatibleDC 返回 null")
            val pfd = PixelFormatDescriptor()
            val fmt = gdi.ChoosePixelFormat(dc, pfd)
            if (fmt == 0) {
                gdi.DeleteDC(dc)
                return failContext("ChoosePixelFormat 返回 0")
            }
            if (!gdi.SetPixelFormat(dc, fmt, pfd)) {
                gdi.DeleteDC(dc)
                return failContext("SetPixelFormat 失败")
            }
            val rc = ogl.wglCreateContext(dc)
            if (rc == null) {
                gdi.DeleteDC(dc)
                return failContext("wglCreateContext 返回 null")
            }
            if (!ogl.wglMakeCurrent(dc, rc)) {
                ogl.wglDeleteContext(rc)
                gdi.DeleteDC(dc)
                return failContext("wglMakeCurrent 失败")
            }
            hdc = dc
            hglrc = rc

            // ★ 取函数（★ 必须在 `wglMakeCurrent` **之后** —— `wglGetProcAddress` 要求当前上下文）
            val t = Gl()
            t.createShader = require(ogl, "glCreateShader")
            t.shaderSource = require(ogl, "glShaderSource")
            t.compileShader = require(ogl, "glCompileShader")
            t.getShaderiv = require(ogl, "glGetShaderiv")
            t.getShaderInfoLog = require(ogl, "glGetShaderInfoLog")
            t.createProgram = require(ogl, "glCreateProgram")
            t.attachShader = require(ogl, "glAttachShader")
            t.linkProgram = require(ogl, "glLinkProgram")
            t.getProgramiv = require(ogl, "glGetProgramiv")
            t.getProgramInfoLog = require(ogl, "glGetProgramInfoLog")
            t.useProgram = require(ogl, "glUseProgram")
            t.deleteShader = require(ogl, "glDeleteShader")
            t.deleteProgram = require(ogl, "glDeleteProgram")
            t.bindAttribLocation = require(ogl, "glBindAttribLocation")
            t.getUniformLocation = require(ogl, "glGetUniformLocation")
            // ★★ `glUniform*` 是 GL 2.0 **核心**函数：某些驱动不在 `wglGetProcAddress` 里
            //   ⇒ 走"先 wglGetProcAddress、再静态导出"的兜底（见 resolve）✓
            t.uniform1i = require(ogl, "glUniform1i")
            t.uniform1f = require(ogl, "glUniform1f")
            t.uniform2f = require(ogl, "glUniform2f")
            // ★ GL 1.1 之后的函数：Windows 的 `opengl32.dll` **只静态导出 GL 1.1** ⇒ 必须走 wgl ✓
            t.activeTexture = require(ogl, "glActiveTexture")
            t.bindTexture = require(ogl, "glBindTexture")
            t.genTextures = require(ogl, "glGenTextures")
            t.deleteTextures = require(ogl, "glDeleteTextures")
            t.texParameteri = require(ogl, "glTexParameteri")
            t.texImage2D = require(ogl, "glTexImage2D")
            t.texSubImage2D = require(ogl, "glTexSubImage2D")
            t.pixelStorei = require(ogl, "glPixelStorei")
            t.readPixels = require(ogl, "glReadPixels")
            t.viewport = require(ogl, "glViewport")
            t.clear = require(ogl, "glClear")
            t.clearColor = require(ogl, "glClearColor")
            t.enable = require(ogl, "glEnable")
            t.disable = require(ogl, "glDisable")
            t.genBuffers = require(ogl, "glGenBuffers")
            t.bindBuffer = require(ogl, "glBindBuffer")
            t.bufferData = require(ogl, "glBufferData")
            t.deleteBuffers = require(ogl, "glDeleteBuffers")
            t.enableVertexAttribArray = require(ogl, "glEnableVertexAttribArray")
            t.disableVertexAttribArray = require(ogl, "glDisableVertexAttribArray")
            t.vertexAttribPointer = require(ogl, "glVertexAttribPointer")
            t.genFramebuffers = require(ogl, "glGenFramebuffers")
            t.bindFramebuffer = require(ogl, "glBindFramebuffer")
            t.framebufferTexture2D = require(ogl, "glFramebufferTexture2D")
            t.checkFramebufferStatus = require(ogl, "glCheckFramebufferStatus")
            t.deleteFramebuffers = require(ogl, "glDeleteFramebuffers")
            t.drawArrays = require(ogl, "glDrawArrays")
            // ★ VAO 是 GL 3.0+：**可取则用**（core profile 必需），取不到 ⇒ 兼容 profile 下可不用
            t.genVertexArrays = resolve(ogl, "glGenVertexArrays")
            t.bindVertexArray = resolve(ogl, "glBindVertexArray")
            t.deleteVertexArrays = resolve(ogl, "glDeleteVertexArrays")
            gl = t

            ctxReady = true
            return true
        } catch (e: Throwable) {
            return failContext("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun failContext(reason: String): Boolean {
        lastError = reason
        ctxFailed = true
        return false
    }

    // ══════════════════════════════════════════════════════════════════════════
    // program / 资源
    // ══════════════════════════════════════════════════════════════════════════

    private fun ensureProgram(g: Gl, fs: String, fsFallback: String?): Int? {
        if (program != 0 && programKey == fs) return program
        if (program != 0) {
            g.deleteProgram.invokeVoid(arrayOf<Any>(IntByReference(program)))
            program = 0
            programKey = ""
            uniformLocs.clear()
        }
        val candidates = ArrayList<String>(2)
        candidates.add(fs)
        if (fsFallback != null && fsFallback != fs) candidates.add(fsFallback)
        for (src in candidates) {
            // ★ 顶点着色器按 fragment 的 `#version` **自动配对**（330 / 120 两套）✓
            val vs = if (src.contains("#version 120")) VERT_120 else VERT_330
            val p = tryBuildProgram(g, vs, src)
            if (p != null) {
                program = p
                programKey = fs
                return p
            }
        }
        return null
    }

    private fun tryBuildProgram(g: Gl, vsSrc: String, fsSrc: String): Int? {
        val vs = compileShader(g, GL_VERTEX_SHADER, vsSrc) ?: return null
        val fs = compileShader(g, GL_FRAGMENT_SHADER, fsSrc)
        if (fs == null) {
            deleteShader(g, vs)
            return null
        }
        val p = g.createProgram.invokeInt(arrayOf<Any>())
        if (p == 0) {
            deleteShader(g, vs)
            deleteShader(g, fs)
            lastError = "glCreateProgram 返回 0"
            return null
        }
        g.attachShader.invokeVoid(arrayOf<Any>(p, vs))
        g.attachShader.invokeVoid(arrayOf<Any>(p, fs))
        // ★ **必须在 link 之前**把 `aPos` 绑到 0 号槽（否则槽号由驱动指派，客户端不知道）
        g.bindAttribLocation.invokeVoid(arrayOf<Any>(p, 0, "aPos"))
        g.linkProgram.invokeVoid(arrayOf<Any>(p))
        val ok = IntByReference(0)
        g.getProgramiv.invokeVoid(arrayOf<Any>(p, GL_LINK_STATUS, ok))
        if (ok.value != 1) {
            val log = programLog(g, p)
            lastError = "program link 失败：$log"
            System.err.println("[GlOffscreen] program link 失败：\n$log")
            g.deleteProgram.invokeVoid(arrayOf<Any>(IntByReference(p)))
            deleteShader(g, vs)
            deleteShader(g, fs)
            return null
        }
        // ★ 链接成功后 shader 对象即可删（GL 会保留引用）
        deleteShader(g, vs)
        deleteShader(g, fs)
        return p
    }

    private fun compileShader(g: Gl, type: Int, src: String): Int? {
        val sh = g.createShader.invokeInt(arrayOf<Any>(type))
        if (sh == 0) {
            lastError = "glCreateShader($type) 返回 0"
            return null
        }
        // ★★ `glShaderSource` 的传参必须精确（本项目 R7 已踩过）：
        //   ① 传 **`Pointer` 数组**（`String[]` 不是 `char**`）；② **长度传 `null`**
        //   ⇒ 让驱动用 NUL 终止（★ 源码里有中文注释，UTF-8 字节数 ≠ 字符数）✓
        val bytes = src.toByteArray(Charsets.UTF_8)
        val mem = Memory((bytes.size + 1).toLong())
        mem.write(0, bytes, 0, bytes.size)
        mem.setByte(bytes.size.toLong(), 0)
        val ptrs = Memory(Native.POINTER_SIZE.toLong())
        ptrs.setPointer(0, mem)
        g.shaderSource.invokeVoid(arrayOf<Any?>(sh, 1, ptrs, null))
        g.compileShader.invokeVoid(arrayOf<Any>(sh))
        val ok = IntByReference(0)
        g.getShaderiv.invokeVoid(arrayOf<Any>(sh, GL_COMPILE_STATUS, ok))
        if (ok.value != 1) {
            val log = shaderLog(g, sh)
            lastError = "shader 编译失败：$log"
            // ★★ **打印 `glGetShaderInfoLog`**（★ 便于诊断版本/profile 不匹配 —— 见类 KDoc）✓
            System.err.println("[GlOffscreen] shader 编译失败（type=$type）：\n$log")
            g.deleteShader.invokeVoid(arrayOf<Any>(IntByReference(sh)))
            return null
        }
        return sh
    }

    private fun shaderLog(g: Gl, sh: Int): String = try {
        val len = IntByReference(0)
        g.getShaderiv.invokeVoid(arrayOf<Any>(sh, GL_INFO_LOG_LENGTH, len))
        if (len.value <= 1) {
            "(无日志)"
        } else {
            val buf = Memory(len.value.toLong())
            g.getShaderInfoLog.invokeVoid(arrayOf<Any?>(sh, len.value, null, buf))
            buf.getString(0)
        }
    } catch (_: Throwable) {
        "(日志读取失败)"
    }

    private fun programLog(g: Gl, p: Int): String = try {
        val len = IntByReference(0)
        g.getProgramiv.invokeVoid(arrayOf<Any>(p, GL_INFO_LOG_LENGTH, len))
        if (len.value <= 1) {
            "(无日志)"
        } else {
            val buf = Memory(len.value.toLong())
            g.getProgramInfoLog.invokeVoid(arrayOf<Any?>(p, len.value, null, buf))
            buf.getString(0)
        }
    } catch (_: Throwable) {
        "(日志读取失败)"
    }

    private fun deleteShader(g: Gl, id: Int) {
        if (id != 0) g.deleteShader.invokeVoid(arrayOf<Any>(IntByReference(id)))
    }

    /**
     * ★★★ **建全屏 quad 的 VBO**（一次性 —— 顶点是常量，之后每帧只 `drawArrays`）。
     *
     * ★ 内容 = 两个三角形覆盖 `[-1,1]²`（clip space）。
     * ★★ **必须走 VBO**：GL 3.1+ 移除了"无 VBO 的客户端属性数组"（AMD 的兼容 profile 也照此执行）
     * ⇒ ★ 客户端 `FloatArray` 直传 `glVertexAttribPointer` 会报 `GL_INVALID_OPERATION` ✗
     */
    private fun ensureQuad(g: Gl) {
        if (vbo != 0) return
        // ★ VAO（可取则建；★ 建了它，`vertexAttribPointer` 的状态就记在里面）
        val genVao = g.genVertexArrays
        if (genVao != null) {
            val a = IntArray(1)
            genVao.invokeVoid(arrayOf<Any>(1, a))
            vao = a[0]
            if (vao != 0) g.bindVertexArray!!.invokeVoid(arrayOf<Any>(vao))
        }
        val verts = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f,
            -1f, 1f, 1f, -1f, 1f, 1f,
        )
        val id = IntArray(1)
        g.genBuffers.invokeVoid(arrayOf<Any>(1, id))
        vbo = id[0]
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, vbo))
        // ★★ JNA 传 `const void*` 的可靠做法 = `Memory`（`FloatArray` 直传会让驱动拿到垃圾指针）
        val mem = Memory((verts.size * 4).toLong())
        mem.write(0, verts, 0, verts.size)
        g.bufferData.invokeVoid(
            arrayOf<Any>(GL_ARRAY_BUFFER, (verts.size * 4).toLong(), mem, GL_STATIC_DRAW),
        )
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, 0))
        if (vao != 0) g.bindVertexArray!!.invokeVoid(arrayOf<Any>(0))
    }

    private fun ensureTargets(g: Gl, w: Int, h: Int): Boolean {
        if (fbo != 0 && texW == w && texH == h) return true
        deleteTargets(g)

        // 输入纹理（`sampler2D` 的来源）
        val tIn = IntArray(1)
        g.genTextures.invokeVoid(arrayOf<Any>(1, tIn))
        inputTex = tIn[0]
        if (inputTex == 0) {
            lastError = "glGenTextures(输入) 返回 0"
            return false
        }
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, inputTex))
        g.texImage2D.invokeVoid(
            arrayOf<Any?>(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null),
        )
        setTexParams(g)

        // 输出纹理（FBO 颜色附件）
        val tOut = IntArray(1)
        g.genTextures.invokeVoid(arrayOf<Any>(1, tOut))
        outputTex = tOut[0]
        if (outputTex == 0) {
            lastError = "glGenTextures(输出) 返回 0"
            return false
        }
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, outputTex))
        g.texImage2D.invokeVoid(
            arrayOf<Any?>(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null),
        )
        setTexParams(g)

        // FBO
        val fb = IntArray(1)
        g.genFramebuffers.invokeVoid(arrayOf<Any>(1, fb))
        fbo = fb[0]
        if (fbo == 0) {
            lastError = "glGenFramebuffers 返回 0"
            return false
        }
        g.bindFramebuffer.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, fbo))
        g.framebufferTexture2D.invokeVoid(
            arrayOf<Any>(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTex, 0),
        )
        val st = g.checkFramebufferStatus.invokeInt(arrayOf<Any>(GL_FRAMEBUFFER))
        if (st != GL_FRAMEBUFFER_COMPLETE) {
            lastError = "FBO 不完整 0x${Integer.toHexString(st)}"
            return false
        }
        texW = w
        texH = h
        return true
    }

    private fun setTexParams(g: Gl) {
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE))
    }

    private fun deleteTargets(g: Gl) {
        if (fbo != 0) {
            g.deleteFramebuffers.invokeVoid(arrayOf<Any>(1, intArrayOf(fbo)))
            fbo = 0
        }
        val texs = intArrayOf(inputTex, outputTex).filter { it != 0 }.toIntArray()
        if (texs.isNotEmpty()) g.deleteTextures.invokeVoid(arrayOf<Any>(texs.size, texs))
        inputTex = 0
        outputTex = 0
        texW = 0
        texH = 0
    }

    private fun uploadTexture(g: Gl, tex: Int, img: BufferedImage, w: Int, h: Int) {
        // ★ 尺寸不符 ⇒ 内部缩放到目标尺寸（★ 宁可多一次缩放，也不要 `getRGB` 越界抛异常）
        val src = if (img.width == w && img.height == h) {
            img
        } else {
            val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2 = scaled.createGraphics()
            try {
                g2.drawImage(img, 0, 0, w, h, null)
            } finally {
                g2.dispose()
            }
            scaled
        }
        val argb = IntArray(w * h)
        src.getRGB(0, 0, w, h, argb, 0, w)
        // ★ ARGB(Int) → RGBA(byte[])：★ **不翻转数据** —— 行序按 AWT 自然序上传，
        //   shader 侧用 `1 - v` 对齐（见 shader 的 `sampleBg`）✓
        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val p = argb[i]
            rgba[i * 4] = ((p ushr 16) and 0xFF).toByte()
            rgba[i * 4 + 1] = ((p ushr 8) and 0xFF).toByte()
            rgba[i * 4 + 2] = (p and 0xFF).toByte()
            rgba[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
        }
        g.activeTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE0))
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, tex))
        g.pixelStorei.invokeVoid(arrayOf<Any>(GL_UNPACK_ALIGNMENT, 1))
        g.texSubImage2D.invokeVoid(
            arrayOf<Any?>(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba),
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // uniform
    // ══════════════════════════════════════════════════════════════════════════

    private fun applyUniforms(g: Gl, prog: Int, uniforms: Map<String, Any?>) {
        for ((name, value) in uniforms) {
            val loc = uniformLoc(g, prog, name)
            if (loc < 0) continue
            when (value) {
                null -> {}
                // ★ 采样器：纹理已在 0 号单元（见 renderOnGlThread ③）
                is BufferedImage -> g.uniform1i.invokeVoid(arrayOf<Any>(loc, 0))
                is Int -> g.uniform1i.invokeVoid(arrayOf<Any>(loc, value))
                is Float -> g.uniform1f.invokeVoid(arrayOf<Any>(loc, value))
                is Double -> g.uniform1f.invokeVoid(arrayOf<Any>(loc, value.toFloat()))
                is FloatArray ->
                    if (value.size >= 2) g.uniform2f.invokeVoid(arrayOf<Any>(loc, value[0], value[1]))
                is DoubleArray ->
                    if (value.size >= 2) {
                        g.uniform2f.invokeVoid(arrayOf<Any>(loc, value[0].toFloat(), value[1].toFloat()))
                    }
                else -> {}
            }
        }
    }

    private fun uniformLoc(g: Gl, prog: Int, name: String): Int {
        uniformLocs[name]?.let { return it }
        val loc = g.getUniformLocation.invokeInt(arrayOf<Any>(prog, name))
        uniformLocs[name] = loc
        return loc
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 清理
    // ══════════════════════════════════════════════════════════════════════════

    private fun destroyGl() {
        try {
            val g = gl
            if (g != null) {
                if (program != 0) g.deleteProgram.invokeVoid(arrayOf<Any>(IntByReference(program)))
                deleteTargets(g)
                if (vbo != 0) g.deleteBuffers.invokeVoid(arrayOf<Any>(1, intArrayOf(vbo)))
                val delVao = g.deleteVertexArrays
                if (vao != 0 && delVao != null) delVao.invokeVoid(arrayOf<Any>(1, intArrayOf(vao)))
            }
        } catch (_: Throwable) {
        }
        program = 0
        programKey = ""
        uniformLocs.clear()
        vbo = 0
        vao = 0
        try {
            opengl32?.wglMakeCurrent(null, null)
            if (hglrc != null) opengl32?.wglDeleteContext(hglrc)
            if (hdc != null) gdi32?.DeleteDC(hdc)
        } catch (_: Throwable) {
        }
        hdc = null
        hglrc = null
        gl = null
        ctxReady = false
    }

    private fun drainErrors() {
        try {
            while (opengl32!!.glGetError() != GL_NO_ERROR) {
                // ★ GL 的错误是**累积**的 ⇒ 排空，别把历史错误算到本帧头上
            }
        } catch (_: Throwable) {
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GL 函数解析
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ★★ **取一个 GL 函数指针**（★ 先 `wglGetProcAddress`，再退 `opengl32.dll` 静态导出）。
     *
     * ## 为什么要两条路
     *
     * ★ Windows 的 `opengl32.dll` **只静态导出 GL 1.1**（`glViewport` / `glClear` /
     * `glReadPixels` / `glTexImage2D` 那一批）⇒ 它们**只能**走静态导出；
     * ★ 而 GL 1.2+ 的函数（`glActiveTexture` / `glCreateShader` / `glGenFramebuffers` …）
     * **只能**走 `wglGetProcAddress`。★ 两条路都试 ⇒ 覆盖面最广 ✓
     *
     * ⚠️ **`wglGetProcAddress` 的哨兵值**：某些驱动对不支持的函数返回 `1 / 2 / 3 / -1`
     *   （**不是 NULL**）⇒ ★ 必须显式挡掉，否则会把哨兵当函数指针调用 ⇒ 崩溃 ✗
     */
    private fun resolve(o: OpenGL32, name: String): Function? {
        try {
            val p = o.wglGetProcAddress(name)
            if (p != null) {
                val v = Pointer.nativeValue(p)
                if (v != 0L && v != 1L && v != 2L && v != 3L && v != -1L) {
                    return Function.getFunction(p)
                }
            }
        } catch (_: Throwable) {
        }
        return try {
            Function.getFunction(NativeLibrary.getInstance("opengl32").getFunction(name))
        } catch (_: Throwable) {
            null
        }
    }

    private fun require(o: OpenGL32, name: String): Function =
        resolve(o, name) ?: throw IllegalStateException("取不到 GL 函数：$name")

    // ══════════════════════════════════════════════════════════════════════════
    // GL 函数表
    // ══════════════════════════════════════════════════════════════════════════

    private class Gl {
        lateinit var createShader: Function
        lateinit var shaderSource: Function
        lateinit var compileShader: Function
        lateinit var getShaderiv: Function
        lateinit var getShaderInfoLog: Function
        lateinit var createProgram: Function
        lateinit var attachShader: Function
        lateinit var linkProgram: Function
        lateinit var getProgramiv: Function
        lateinit var getProgramInfoLog: Function
        lateinit var useProgram: Function
        lateinit var deleteShader: Function
        lateinit var deleteProgram: Function
        lateinit var bindAttribLocation: Function
        lateinit var getUniformLocation: Function
        lateinit var uniform1i: Function
        lateinit var uniform1f: Function
        lateinit var uniform2f: Function
        lateinit var activeTexture: Function
        lateinit var bindTexture: Function
        lateinit var genTextures: Function
        lateinit var deleteTextures: Function
        lateinit var texParameteri: Function
        lateinit var texImage2D: Function
        lateinit var texSubImage2D: Function
        lateinit var pixelStorei: Function
        lateinit var readPixels: Function
        lateinit var viewport: Function
        lateinit var clear: Function
        lateinit var clearColor: Function
        lateinit var enable: Function
        lateinit var disable: Function
        lateinit var genBuffers: Function
        lateinit var bindBuffer: Function
        lateinit var bufferData: Function
        lateinit var deleteBuffers: Function
        lateinit var enableVertexAttribArray: Function
        lateinit var disableVertexAttribArray: Function
        lateinit var vertexAttribPointer: Function
        lateinit var genFramebuffers: Function
        lateinit var bindFramebuffer: Function
        lateinit var framebufferTexture2D: Function
        lateinit var checkFramebufferStatus: Function
        lateinit var deleteFramebuffers: Function
        lateinit var drawArrays: Function

        // ★ 可选（GL 3.0+；取不到 ⇒ 兼容 profile 下可不用）
        var genVertexArrays: Function? = null
        var bindVertexArray: Function? = null
        var deleteVertexArrays: Function? = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Win32 绑定
    // ══════════════════════════════════════════════════════════════════════════

    private interface Gdi32 : Library {
        fun CreateCompatibleDC(hdc: Pointer?): Pointer?
        fun DeleteDC(hdc: Pointer?): Boolean
        fun ChoosePixelFormat(hdc: Pointer?, pfd: PixelFormatDescriptor): Int
        fun SetPixelFormat(hdc: Pointer?, fmt: Int, pfd: PixelFormatDescriptor): Boolean
    }

    private interface OpenGL32 : Library {
        fun wglCreateContext(hdc: Pointer?): Pointer?
        fun wglMakeCurrent(hdc: Pointer?, hglrc: Pointer?): Boolean
        fun wglDeleteContext(hglrc: Pointer?): Boolean
        fun wglGetProcAddress(name: String): Pointer?
        /** ★ GL 1.1，静态导出 ✓ */
        fun glGetString(name: Int): Pointer?
        fun glGetError(): Int
    }

    /**
     * ★ `PIXELFORMATDESCRIPTOR`（Win32，40 字节）—— 与 `GpuOffscreenProbe` 逐字相同。
     *
     * ★★ **内存 DC（`CreateCompatibleDC(NULL)`）必须用 `PFD_DRAW_TO_BITMAP`**，
     *    不是 `PFD_DRAW_TO_WINDOW` —— 前者是**离屏**语义 ✓
     */
    @com.sun.jna.Structure.FieldOrder(
        "nSize", "nVersion", "dwFlags", "iPixelType", "cColorBits",
        "cRedBits", "cRedShift", "cGreenBits", "cGreenShift", "cBlueBits", "cBlueShift",
        "cAlphaBits", "cAlphaShift", "cAccumBits",
        "cAccumRedBits", "cAccumGreenBits", "cAccumBlueBits", "cAccumAlphaBits",
        "cDepthBits", "cStencilBits", "cAuxBuffers", "iLayerType", "bReserved",
        "dwLayerMask", "dwVisibleMask", "dwDamageMask",
    )
    class PixelFormatDescriptor : com.sun.jna.Structure() {
        @JvmField var nSize: Short = 40
        @JvmField var nVersion: Short = 1
        @JvmField var dwFlags: Int = PFD_DRAW_TO_BITMAP or PFD_SUPPORT_OPENGL or PFD_DOUBLEBUFFER
        @JvmField var iPixelType: Byte = PFD_TYPE_RGBA.toByte()
        @JvmField var cColorBits: Byte = 32
        @JvmField var cRedBits: Byte = 0
        @JvmField var cRedShift: Byte = 0
        @JvmField var cGreenBits: Byte = 0
        @JvmField var cGreenShift: Byte = 0
        @JvmField var cBlueBits: Byte = 0
        @JvmField var cBlueShift: Byte = 0
        @JvmField var cAlphaBits: Byte = 8
        @JvmField var cAlphaShift: Byte = 0
        @JvmField var cAccumBits: Byte = 0
        @JvmField var cAccumRedBits: Byte = 0
        @JvmField var cAccumGreenBits: Byte = 0
        @JvmField var cAccumBlueBits: Byte = 0
        @JvmField var cAccumAlphaBits: Byte = 0
        @JvmField var cDepthBits: Byte = 0
        @JvmField var cStencilBits: Byte = 0
        @JvmField var cAuxBuffers: Byte = 0
        @JvmField var iLayerType: Byte = 0
        @JvmField var bReserved: Byte = 0
        @JvmField var dwLayerMask: Int = 0
        @JvmField var dwVisibleMask: Int = 0
        @JvmField var dwDamageMask: Int = 0
    }

    private companion object {
        // ── GL 常量（★ 只声明用到的）─────────────────────────────────────────
        const val GL_RENDERER = 0x1F01
        const val GL_VERSION = 0x1F02

        const val GL_VERTEX_SHADER = 0x8B31
        const val GL_FRAGMENT_SHADER = 0x8B30
        const val GL_COMPILE_STATUS = 0x8B81
        const val GL_LINK_STATUS = 0x8B82
        const val GL_INFO_LOG_LENGTH = 0x8B84

        const val GL_FRAMEBUFFER = 0x8D40
        const val GL_COLOR_ATTACHMENT0 = 0x8CE0
        const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5

        const val GL_TEXTURE_2D = 0x0DE1
        const val GL_RGBA8 = 0x8058
        const val GL_RGBA = 0x1908
        const val GL_UNSIGNED_BYTE = 0x1401
        const val GL_TEXTURE0 = 0x84C0
        const val GL_TEXTURE_MIN_FILTER = 0x2801
        const val GL_TEXTURE_MAG_FILTER = 0x2800
        const val GL_TEXTURE_WRAP_S = 0x2802
        const val GL_TEXTURE_WRAP_T = 0x2803
        const val GL_LINEAR = 0x2601
        const val GL_CLAMP_TO_EDGE = 0x812F
        const val GL_UNPACK_ALIGNMENT = 0x0CF5

        const val GL_DEPTH_TEST = 0x0B71
        const val GL_BLEND = 0x0BE2
        const val GL_COLOR_BUFFER_BIT = 0x00004000
        const val GL_TRIANGLES = 0x0004
        const val GL_FLOAT = 0x1406
        const val GL_ARRAY_BUFFER = 0x8892
        const val GL_STATIC_DRAW = 0x88E4
        const val GL_NO_ERROR = 0

        // ── Win32 常量 ───────────────────────────────────────────────────────
        const val PFD_DRAW_TO_BITMAP = 0x00000008
        const val PFD_SUPPORT_OPENGL = 0x00000020
        const val PFD_DOUBLEBUFFER = 0x00000001
        const val PFD_TYPE_RGBA = 0

        // ── 顶点着色器（★ 按 `#version` 与 fragment **配对**）──────────────────
        //
        // ★ 330 与 120 的差别只有「`in` vs `attribute`」——
        //   `aPos` 一律由 `glBindAttribLocation` 绑到 **0 号槽**（两版通用）✓
        const val VERT_330 =
            "#version 330 core\nin vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n"
        const val VERT_120 =
            "#version 120\nattribute vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n"

        // ── 系统 DLL（★ 加载失败 ⇒ null，不抛）────────────────────────────────
        val gdi32: Gdi32? = try {
            Native.load("gdi32", Gdi32::class.java)
        } catch (_: Throwable) {
            null
        }

        val opengl32: OpenGL32? = try {
            Native.load("opengl32", OpenGL32::class.java)
        } catch (_: Throwable) {
            null
        }
    }
}
