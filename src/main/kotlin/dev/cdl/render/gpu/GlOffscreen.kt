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

class GlOffscreen {

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

    fun describe(): String {
        val e = executorRef ?: return "未启动（尚未渲染）"
        return try {
            e.submit(Callable { describeOnGlThread() }).get()
        } catch (_: Throwable) {
            "查询失败（$lastError）"
        }
    }

    @Volatile
    var lastError: String = ""
        private set

    private val executorLock = Any()

    @Volatile
    private var executorRef: ExecutorService? = null

    @Volatile
    private var closed = false

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

        drainErrors()

        val bg = uniforms.values.firstOrNull { it is BufferedImage } as? BufferedImage
        if (bg != null) uploadTexture(g, inputTex, bg, width, height)

        g.bindFramebuffer.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, fbo))
        g.viewport.invokeVoid(arrayOf<Any>(0, 0, width, height))
        g.disable.invokeVoid(arrayOf<Any>(GL_DEPTH_TEST))
        g.disable.invokeVoid(arrayOf<Any>(GL_BLEND))
        g.clearColor.invokeVoid(arrayOf<Any>(0f, 0f, 0f, 0f))
        g.clear.invokeVoid(arrayOf<Any>(GL_COLOR_BUFFER_BIT))

        g.useProgram.invokeVoid(arrayOf<Any>(prog))
        g.activeTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE0))
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, inputTex))
        applyUniforms(g, prog, uniforms)

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

        val out = ByteArray(width * height * 4)
        g.readPixels.invokeVoid(
            arrayOf<Any?>(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, out),
        )

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

            t.uniform1i = require(ogl, "glUniform1i")
            t.uniform1f = require(ogl, "glUniform1f")
            t.uniform2f = require(ogl, "glUniform2f")

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

        g.bindAttribLocation.invokeVoid(arrayOf<Any>(p, 0, "aPos"))
        g.linkProgram.invokeVoid(arrayOf<Any>(p))
        val ok = IntByReference(0)
        g.getProgramiv.invokeVoid(arrayOf<Any>(p, GL_LINK_STATUS, ok))
        if (ok.value != 1) {
            val log = programLog(g, p)
            lastError = "program link 失败：$log"
            g.deleteProgram.invokeVoid(arrayOf<Any>(IntByReference(p)))
            deleteShader(g, vs)
            deleteShader(g, fs)
            return null
        }

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

    private fun ensureQuad(g: Gl) {
        if (vbo != 0) return

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

    private fun applyUniforms(g: Gl, prog: Int, uniforms: Map<String, Any?>) {
        for ((name, value) in uniforms) {
            val loc = uniformLoc(g, prog, name)
            if (loc < 0) continue
            when (value) {
                null -> {}

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

            }
        } catch (_: Throwable) {
        }
    }

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

        var genVertexArrays: Function? = null
        var bindVertexArray: Function? = null
        var deleteVertexArrays: Function? = null
    }

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

        fun glGetString(name: Int): Pointer?
        fun glGetError(): Int
    }

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

        const val PFD_DRAW_TO_BITMAP = 0x00000008
        const val PFD_SUPPORT_OPENGL = 0x00000020
        const val PFD_DOUBLEBUFFER = 0x00000001
        const val PFD_TYPE_RGBA = 0

        const val VERT_330 =
            "#version 330 core\nin vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n"
        const val VERT_120 =
            "#version 120\nattribute vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n"

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
