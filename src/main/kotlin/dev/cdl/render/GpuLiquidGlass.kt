// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.image.BufferedImage

object GpuLiquidGlass {

    private const val GL_VENDOR = 0x1F00
    private const val GL_RENDERER = 0x1F01
    private const val GL_VERSION = 0x1F02
    private const val GL_VERTEX_SHADER = 0x8B31
    private const val GL_FRAGMENT_SHADER = 0x8B30
    private const val GL_COMPILE_STATUS = 0x8B81
    private const val GL_LINK_STATUS = 0x8B82
    private const val GL_INFO_LOG_LENGTH = 0x8B84
    private const val GL_FRAMEBUFFER = 0x8D40
    private const val GL_COLOR_ATTACHMENT0 = 0x8CE0
    private const val GL_TEXTURE_2D = 0x0DE1
    private const val GL_RGBA8 = 0x8058
    private const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
    private const val GL_RGBA = 0x1908
    private const val GL_UNSIGNED_BYTE = 0x1401
    private const val GL_NO_ERROR = 0
    private const val GL_TEXTURE0 = 0x84C0
    private const val GL_TEXTURE_MIN_FILTER = 0x2801
    private const val GL_TEXTURE_MAG_FILTER = 0x2800
    private const val GL_TEXTURE_WRAP_S = 0x2802
    private const val GL_TEXTURE_WRAP_T = 0x2803
    private const val GL_LINEAR = 0x2601
    private const val GL_CLAMP_TO_EDGE = 0x812F
    private const val GL_UNPACK_ALIGNMENT = 0x0CF5
    private const val GL_DEPTH_TEST = 0x0B71
    private const val GL_BLEND = 0x0BE2
    private const val GL_TRIANGLES = 0x0004
    private const val GL_FLOAT = 0x1406
    private const val GL_VERTEX_ARRAY = 0x8074
    private const val GL_ARRAY_BUFFER = 0x8892
    private const val GL_STATIC_DRAW = 0x88E4

    private const val PFD_DRAW_TO_BITMAP = 0x00000008
    private const val PFD_SUPPORT_OPENGL = 0x00000020
    private const val PFD_DOUBLEBUFFER = 0x00000001
    private const val PFD_TYPE_RGBA = 0

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

    private val opengl32: OpenGL32? = try {
        Native.load("opengl32", OpenGL32::class.java)
    } catch (_: Throwable) {
        null
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

    private class Gl {
        lateinit var createShader: com.sun.jna.Function
        lateinit var shaderSource: com.sun.jna.Function
        lateinit var compileShader: com.sun.jna.Function
        lateinit var getShaderiv: com.sun.jna.Function
        lateinit var getShaderInfoLog: com.sun.jna.Function
        lateinit var createProgram: com.sun.jna.Function
        lateinit var attachShader: com.sun.jna.Function
        lateinit var linkProgram: com.sun.jna.Function
        lateinit var getProgramiv: com.sun.jna.Function
        lateinit var getProgramInfoLog: com.sun.jna.Function
        lateinit var useProgram: com.sun.jna.Function
        lateinit var getUniformLocation: com.sun.jna.Function
        lateinit var bindAttribLocation: com.sun.jna.Function

        lateinit var activeTexture: com.sun.jna.Function
        lateinit var bindTexture: com.sun.jna.Function
        lateinit var genTextures: com.sun.jna.Function
        lateinit var deleteTextures: com.sun.jna.Function
        lateinit var texParameteri: com.sun.jna.Function
        lateinit var texImage2D: com.sun.jna.Function
        lateinit var texSubImage2D: com.sun.jna.Function
        lateinit var pixelStorei: com.sun.jna.Function
        lateinit var readPixels: com.sun.jna.Function
        lateinit var viewport: com.sun.jna.Function
        lateinit var disable: com.sun.jna.Function
        lateinit var enableVertexAttribArray: com.sun.jna.Function
        lateinit var disableVertexAttribArray: com.sun.jna.Function
        lateinit var vertexAttribPointer: com.sun.jna.Function
        lateinit var genBuffers: com.sun.jna.Function
        lateinit var bindBuffer: com.sun.jna.Function
        lateinit var bufferData: com.sun.jna.Function
        lateinit var deleteBuffers: com.sun.jna.Function
        lateinit var genFramebuffers: com.sun.jna.Function
        lateinit var bindFramebuffer: com.sun.jna.Function
        lateinit var framebufferTexture2D: com.sun.jna.Function
        lateinit var checkFramebufferStatus: com.sun.jna.Function
        lateinit var deleteFramebuffers: com.sun.jna.Function
        lateinit var deleteShader: com.sun.jna.Function
        lateinit var deleteProgram: com.sun.jna.Function
        lateinit var drawArrays: com.sun.jna.Function
    }

    private val gdi32: Gdi32? = try {
        Native.load("gdi32", Gdi32::class.java)
    } catch (_: Throwable) {
        null
    }

    private var hdc: Pointer? = null
    private var hglrc: Pointer? = null
    private var gl: Gl? = null
    private var program = 0
    private var vsId = 0
    private var fsId = 0
    private var fbo = 0
    private var inputTex = 0
    private var outputTex = 0
    private var vbo = 0
    private var texW = 0
    private var texH = 0
    private var boundThread: Thread? = null
    private var uniformLocs: Map<String, Int> = emptyMap()

    @Volatile
    private var probeResult: Boolean? = null

    @Volatile
    var lastUnavailableReason: String = ""
        private set

    @Volatile
    var lastRenderNanos: Long = 0L
        private set

    fun isAvailable(): Boolean {
        val cached = probeResult
        if (cached != null) return cached
        val r = try {
            probe()
            true
        } catch (e: Throwable) {
            lastUnavailableReason = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
        probeResult = r
        return r
    }

    fun render(
        backdrop: BufferedImage,
        cornerRadius: Double,
        params: BackdropRefraction.Params,
        cornerRadiusBottom: Double = Double.NaN,
        surfaceAlpha: Double = 0.10,
        glarePercent: Int = 60,

        composite: Boolean = true,
    ): BufferedImage {
        if (!isAvailable()) {
            throw IllegalStateException("GPU 路径不可用：$lastUnavailableReason")
        }
        val g = gl ?: throw IllegalStateException("GL 函数表未初始化")

        run {
            val dc = hdc ?: throw IllegalStateException("无 HDC")
            val rc = hglrc ?: throw IllegalStateException("无 GL 上下文")
            if (!opengl32!!.wglMakeCurrent(dc, rc)) {
                throw IllegalStateException("wglMakeCurrent 失败（本线程拿不到 GL 上下文）")
            }
        }

        val w = backdrop.width
        val h = backdrop.height
        require(w > 0 && h > 0) { "尺寸非法：$w×$h" }

        val t0 = System.nanoTime()
        ensureTargets(w, h)

        while (opengl32!!.glGetError() != GL_NO_ERROR) {  }

        g.bindFramebuffer.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, fbo))

        val argb = IntArray(w * h)
        backdrop.getRGB(0, 0, w, h, argb, 0, w)

        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val p = argb[i]
            rgba[i * 4] = ((p ushr 16) and 0xFF).toByte()
            rgba[i * 4 + 1] = ((p ushr 8) and 0xFF).toByte()
            rgba[i * 4 + 2] = (p and 0xFF).toByte()
            rgba[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
        }

        g.activeTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE0))
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, inputTex))
        g.pixelStorei.invokeVoid(arrayOf<Any>(GL_UNPACK_ALIGNMENT, 1))
        g.texSubImage2D.invokeVoid(
            arrayOf<Any?>(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba),
        )
        checkErr(g, "texSubImage2D")

        g.useProgram.invokeVoid(arrayOf<Any>(program))

        g.activeTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE0))
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, inputTex))
        setUniform1i(g, "uBackdrop", 0)
        setUniform1f(g, "uW", w.toFloat())
        setUniform1f(g, "uH", h.toFloat())
        setUniform1f(g, "uCorner", cornerRadius.toFloat())
        setUniform1f(
            g, "uCornerBottom",
            (if (cornerRadiusBottom.isNaN()) cornerRadius else cornerRadiusBottom).toFloat(),
        )
        setUniform1f(g, "uRoundness", params.roundness.toFloat())
        setUniform1f(g, "uRefHeight", params.thicknessPx.toFloat())
        setUniform1f(g, "uRefAmount", params.amountPx.toFloat())
        setUniform1f(g, "uProfileExp", BackdropRefraction.PROFILE_EXPONENT.toFloat())
        setUniform1f(g, "uDispersion", params.dispersionFactor.toFloat())
        setUniform1f(g, "uSaturation", params.saturation.toFloat())
        setUniform1f(g, "uSurface", surfaceAlpha.toFloat())
        setUniform1f(g, "uSpecular", (glarePercent / 100.0).toFloat())

        setUniform1f(g, "uDepth", if (params.depthEffect) 1.0f else 0.0f)
        setUniform1f(g, "uComposite", if (composite) 1.0f else 0.0f)

        while (opengl32!!.glGetError() != GL_NO_ERROR) {  }
        g.viewport.invokeVoid(arrayOf<Any>(0, 0, w, h))
        g.disable.invokeVoid(arrayOf<Any>(GL_DEPTH_TEST))
        g.disable.invokeVoid(arrayOf<Any>(GL_BLEND))

        ensureVertexBuffer()
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, vbo))
        checkErr(g, "bindBuffer")
        g.enableVertexAttribArray.invokeVoid(arrayOf<Any>(0))
        checkErr(g, "enableVertexAttribArray")

        g.vertexAttribPointer.invokeVoid(arrayOf<Any>(0, 2, GL_FLOAT, false, 0, 0L))
        checkErr(g, "vertexAttribPointer")
        g.drawArrays.invokeVoid(arrayOf<Any>(GL_TRIANGLES, 0, 6))
        checkErr(g, "drawArrays")
        g.disableVertexAttribArray.invokeVoid(arrayOf<Any>(0))
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, 0))

        val err = opengl32!!.glGetError()
        if (err != GL_NO_ERROR) {
            throw IllegalStateException("glGetError = 0x${Integer.toHexString(err)}（阶段：$lastStage）")
        }

        val out = ByteArray(w * h * 4)
        g.readPixels.invokeVoid(
            arrayOf<Any?>(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out),
        )

        lastRenderNanos = System.nanoTime() - t0

        val outArgb = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = out[i * 4].toInt() and 0xFF
            val gg = out[i * 4 + 1].toInt() and 0xFF
            val b = out[i * 4 + 2].toInt() and 0xFF
            val a = out[i * 4 + 3].toInt() and 0xFF
            outArgb[i] = (a shl 24) or (r shl 16) or (gg shl 8) or b
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, outArgb, 0, w)
        return img
    }

    fun dispose() {
        try {
            val g = gl
            if (g != null) {

                if (program != 0) {
                    val r = IntByReference(program)
                    g.deleteProgram.invokeVoid(arrayOf<Any>(r))
                }

                if (fbo != 0) {
                    val r = IntByReference(fbo)
                    g.deleteFramebuffers.invokeVoid(arrayOf<Any>(1, r))
                }

                val texs = intArrayOf(inputTex, outputTex).filter { it != 0 }.toIntArray()
                if (texs.isNotEmpty()) {
                    g.deleteTextures.invokeVoid(arrayOf<Any>(texs.size, texs))
                }

                if (vbo != 0) {
                    val r = IntArray(1); r[0] = vbo
                    g.deleteBuffers.invokeVoid(arrayOf<Any>(1, r))
                }

                if (vsId != 0) { val r = IntByReference(vsId); g.deleteShader.invokeVoid(arrayOf<Any>(r)) }
                if (fsId != 0) { val r = IntByReference(fsId); g.deleteShader.invokeVoid(arrayOf<Any>(r)) }
            }
            program = 0; fbo = 0
            inputTex = 0; outputTex = 0; texW = 0; texH = 0; vbo = 0
            vsId = 0; fsId = 0
            opengl32?.wglMakeCurrent(null, null)
            if (hglrc != null) opengl32?.wglDeleteContext(hglrc)
            if (hdc != null) gdi32?.DeleteDC(hdc)
            hdc = null; hglrc = null; gl = null
            boundThread = null
            uniformLocs = emptyMap()
        } catch (_: Throwable) {
        }
    }

    fun describe(): String {
        if (lastUnavailableReason.isNotEmpty()) return "GPU 不可用（$lastUnavailableReason）"
        val o = opengl32 ?: return "GPU 不可用（opengl32 未加载）"
        val r = o.glGetString(GL_RENDERER)?.getString(0) ?: "?"
        val v = o.glGetString(GL_VERSION)?.getString(0) ?: "?"
        return "$r · GL $v"
    }

    private fun probe() {
        val os = System.getProperty("os.name") ?: ""
        if (!os.lowercase().contains("win")) error("非 Windows（os.name=$os）")
        val g = gdi32 ?: error("gdi32 未加载")
        val o = opengl32 ?: error("opengl32 未加载")

        val dc = g.CreateCompatibleDC(null) ?: error("CreateCompatibleDC 返回 null")
        val pfd = PixelFormatDescriptor()
        val fmt = g.ChoosePixelFormat(dc, pfd)
        if (fmt == 0) error("ChoosePixelFormat 返回 0")
        if (!g.SetPixelFormat(dc, fmt, pfd)) error("SetPixelFormat 失败")
        val rc = o.wglCreateContext(dc) ?: error("wglCreateContext 返回 null")
        if (!o.wglMakeCurrent(dc, rc)) error("wglMakeCurrent 失败")

        hdc = dc
        hglrc = rc
        boundThread = Thread.currentThread()

        val gg = Gl()
        fun need(name: String): com.sun.jna.Function {
            val p = o.wglGetProcAddress(name) ?: error("wglGetProcAddress($name) 返回 null")
            if (Pointer.nativeValue(p) == 0L) error("wglGetProcAddress($name) 为 NULL")
            return com.sun.jna.Function.getFunction(p)
        }
        gg.createShader = need("glCreateShader")
        gg.shaderSource = need("glShaderSource")
        gg.compileShader = need("glCompileShader")
        gg.getShaderiv = need("glGetShaderiv")
        gg.getShaderInfoLog = need("glGetShaderInfoLog")
        gg.createProgram = need("glCreateProgram")
        gg.attachShader = need("glAttachShader")
        gg.linkProgram = need("glLinkProgram")
        gg.getProgramiv = need("glGetProgramiv")
        gg.getProgramInfoLog = need("glGetProgramInfoLog")
        gg.useProgram = need("glUseProgram")
        gg.getUniformLocation = need("glGetUniformLocation")

        gg.bindAttribLocation = need("glBindAttribLocation")

        gg.activeTexture = need("glActiveTexture")
        gg.bindTexture = need("glBindTexture")
        gg.genTextures = need("glGenTextures")
        gg.deleteTextures = need("glDeleteTextures")
        gg.texParameteri = need("glTexParameteri")
        gg.texImage2D = need("glTexImage2D")
        gg.texSubImage2D = need("glTexSubImage2D")
        gg.pixelStorei = need("glPixelStorei")
        gg.readPixels = need("glReadPixels")
        gg.viewport = need("glViewport")
        gg.disable = need("glDisable")
        gg.enableVertexAttribArray = need("glEnableVertexAttribArray")
        gg.disableVertexAttribArray = need("glDisableVertexAttribArray")
        gg.vertexAttribPointer = need("glVertexAttribPointer")
        gg.genBuffers = need("glGenBuffers")
        gg.bindBuffer = need("glBindBuffer")
        gg.bufferData = need("glBufferData")
        gg.deleteBuffers = need("glDeleteBuffers")
        gg.genFramebuffers = need("glGenFramebuffers")
        gg.bindFramebuffer = need("glBindFramebuffer")
        gg.framebufferTexture2D = need("glFramebufferTexture2D")
        gg.checkFramebufferStatus = need("glCheckFramebufferStatus")
        gg.deleteFramebuffers = need("glDeleteFramebuffers")
        gg.deleteShader = need("glDeleteShader")
        gg.deleteProgram = need("glDeleteProgram")
        gg.drawArrays = need("glDrawArrays")
        gl = gg

        pUniform1i = needOrStatic("glUniform1i", staticFnOrNull("glUniform1i"))
        pUniform1f = needOrStatic("glUniform1f", staticFnOrNull("glUniform1f"))

        buildProgram(gg)
    }

    private fun buildProgram(g: Gl) {
        val vs = compileShader(g, GL_VERTEX_SHADER, VERT)
        val fs = compileShader(g, GL_FRAGMENT_SHADER, FRAG)
        vsId = vs
        fsId = fs
        val p = g.createProgram.invokeInt(arrayOf()) as Int
        if (p == 0) error("glCreateProgram 返回 0")
        g.attachShader.invokeVoid(arrayOf<Any>(p, vs))
        g.attachShader.invokeVoid(arrayOf<Any>(p, fs))

        g.bindAttribLocation.invokeVoid(arrayOf<Any>(p, 0, "aPos"))
        g.linkProgram.invokeVoid(arrayOf<Any>(p))
        val ok = IntByReference(0)
        g.getProgramiv.invokeVoid(arrayOf<Any>(p, GL_LINK_STATUS, ok))
        if (ok.value != 1) {
            val log = shaderLog(g, p, isProgram = true)
            error("program link 失败：$log")
        }
        program = p
        uniformLocs = UNIFORM_NAMES.associateWith {
            g.getUniformLocation.invokeInt(arrayOf<Any>(p, it)) as Int
        }
    }

    private fun compileShader(g: Gl, type: Int, src: String): Int {
        val sh = g.createShader.invokeInt(arrayOf<Any>(type)) as Int
        if (sh == 0) error("glCreateShader($type) 返回 0")

        val bytes = src.toByteArray(Charsets.UTF_8)
        val mem = com.sun.jna.Memory((bytes.size + 1).toLong())
        mem.write(0, bytes, 0, bytes.size)
        mem.setByte(bytes.size.toLong(), 0)
        val ptrs = com.sun.jna.Memory(com.sun.jna.Native.POINTER_SIZE.toLong())
        ptrs.setPointer(0, mem)
        g.shaderSource.invokeVoid(arrayOf<Any?>(sh, 1, ptrs, null))
        g.compileShader.invokeVoid(arrayOf<Any>(sh))
        val ok = IntByReference(0)
        g.getShaderiv.invokeVoid(arrayOf<Any>(sh, GL_COMPILE_STATUS, ok))
        if (ok.value != 1) error("shader 编译失败：${shaderLog(g, sh, isProgram = false)}")
        return sh
    }

    private fun shaderLog(g: Gl, id: Int, isProgram: Boolean): String {
        return try {
            val len = IntByReference(0)
            if (isProgram) {
                g.getProgramiv.invokeVoid(arrayOf<Any>(id, GL_INFO_LOG_LENGTH, len))
            } else {
                g.getShaderiv.invokeVoid(arrayOf<Any>(id, GL_INFO_LOG_LENGTH, len))
            }
            if (len.value <= 1) "(无日志)" else {
                val buf = com.sun.jna.Memory(len.value.toLong())
                if (isProgram) {
                    g.getProgramInfoLog.invokeVoid(arrayOf<Any?>(id, len.value, null, buf))
                } else {
                    g.getShaderInfoLog.invokeVoid(arrayOf<Any?>(id, len.value, null, buf))
                }
                buf.getString(0)
            }
        } catch (_: Throwable) {
            "(日志读取失败)"
        }
    }

    private fun ensureVertexBuffer() {
        if (vbo != 0) return
        val g = gl ?: error("GL 函数表未初始化")
        val verts = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f,
            -1f, 1f, 1f, -1f, 1f, 1f,
        )
        val id = IntArray(1)
        g.genBuffers.invokeVoid(arrayOf<Any>(1, id))
        vbo = id[0]
        checkErr(g, "ensureVertexBuffer.genBuffers(vbo=$vbo)")
        if (vbo == 0) error("glGenBuffers 返回 0（阶段：genBuffers）")
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, vbo))
        checkErr(g, "ensureVertexBuffer.bindBuffer")

        val mem = com.sun.jna.Memory((verts.size * 4).toLong())
        mem.write(0, verts, 0, verts.size)
        g.bufferData.invokeVoid(
            arrayOf<Any>(GL_ARRAY_BUFFER, verts.size * 4, mem, GL_STATIC_DRAW),
        )
        checkErr(g, "ensureVertexBuffer.bufferData")
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, 0))
    }

    private fun ensureTargets(w: Int, h: Int) {
        if (texW == w && texH == h && fbo != 0) return
        val g = gl ?: error("GL 函数表未初始化")

        if (fbo != 0) {
            val r = IntByReference(fbo)
            g.deleteFramebuffers.invokeVoid(arrayOf<Any>(1, r))
            fbo = 0
        }
        if (inputTex != 0 || outputTex != 0) {
            val old = intArrayOf(inputTex, outputTex).filter { it != 0 }.toIntArray()
            g.deleteTextures.invokeVoid(arrayOf<Any>(old.size, old))
            inputTex = 0; outputTex = 0
        }

        val tIn = IntArray(1)
        g.genTextures.invokeVoid(arrayOf<Any>(1, tIn))
        inputTex = tIn[0]
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, inputTex))
        g.texImage2D.invokeVoid(
            arrayOf<Any?>(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null),
        )
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE))

        val tOut = IntArray(1)
        g.genTextures.invokeVoid(arrayOf<Any>(1, tOut))
        outputTex = tOut[0]
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, outputTex))
        g.texImage2D.invokeVoid(
            arrayOf<Any?>(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null),
        )
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR))

        val fb = IntByReference(0)
        g.genFramebuffers.invokeVoid(arrayOf<Any>(1, fb))
        fbo = fb.value
        g.bindFramebuffer.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, fbo))
        g.framebufferTexture2D.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTex, 0))
        val st = g.checkFramebufferStatus.invokeInt(arrayOf<Any>(GL_FRAMEBUFFER)) as Int
        if (st != GL_FRAMEBUFFER_COMPLETE) error("FBO 不完整：0x${Integer.toHexString(st)}")

        texW = w; texH = h
    }

    private lateinit var pUniform1i: com.sun.jna.Function
    private lateinit var pUniform1f: com.sun.jna.Function

    private fun needOrStatic(name: String, staticFn: com.sun.jna.Function?): com.sun.jna.Function {
        val o = opengl32 ?: error("opengl32 未加载")
        val p = try {
            o.wglGetProcAddress(name)
        } catch (_: Throwable) {
            null
        }
        if (p != null && Pointer.nativeValue(p) != 0L) {
            return com.sun.jna.Function.getFunction(p)
        }
        return staticFn ?: error("$name 既不在 wglGetProcAddress 也不在 opengl32 静态导出")
    }

    @Volatile
    private var lastStage: String = "init"

    private fun checkErr(g: Gl, stage: String) {
        lastStage = stage
        val e = opengl32!!.glGetError()
        if (e != GL_NO_ERROR) {
            throw IllegalStateException("glGetError = 0x${Integer.toHexString(e)}（阶段：$stage）")
        }
    }

    private fun staticFnOrNull(name: String): com.sun.jna.Function? = try {
        com.sun.jna.Function.getFunction(
            com.sun.jna.NativeLibrary.getInstance("opengl32").getFunction(name),
        )
    } catch (_: Throwable) {
        null
    }

    private fun setUniform1i(g: Gl, name: String, v: Int) {
        val loc = uniformLocs[name] ?: -1
        if (loc == -1) return

        pUniform1i.invokeVoid(arrayOf<Any>(loc, v))
    }

    private fun setUniform1f(g: Gl, name: String, v: Float) {
        val loc = uniformLocs[name] ?: -1
        if (loc == -1) return

        pUniform1f.invokeVoid(arrayOf<Any>(loc, v))
    }

    private val UNIFORM_NAMES = listOf(
        "uBackdrop", "uW", "uH", "uCorner", "uCornerBottom", "uRoundness",
        "uRefHeight", "uRefAmount", "uProfileExp", "uDispersion", "uSaturation",
        "uSurface", "uSpecular", "uDepth", "uComposite",
    )

    private val VERT = """
        #version 120
        attribute vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    private val FRAG = """
        #version 120
        uniform sampler2D uBackdrop;
        uniform float uW, uH, uCorner, uCornerBottom, uRoundness;
        uniform float uRefHeight, uRefAmount, uProfileExp;
        uniform float uDispersion, uSaturation, uSurface, uSpecular, uDepth;
        // ★★★ 2026-10-18：**只做 L3** 还是**整块玻璃**（L3+L2）。
        //   `1`（默认）= 旧行为（内含内阴影/高光/表面）；`0` = 只出折射+色散+vibrancy，
        //   与 CPU 的 `BackdropRefraction.bake` **同口径**（用于 A/B 对照与"替换 L3"）。
        uniform float uComposite;

        // ★ 超椭圆 SDF（非角区 / 角区两段）—— 与 GlassSdf.roundedRect **同公式**
        float sdRoundRect(vec2 p, vec2 c, float hw, float hh, float r, float n) {
            vec2 d = abs(p - c) - vec2(hw, hh);
            if (r > 0.0 && d.x > -r && d.y > -r) {
                vec2 a = abs(p - c) - vec2(hw - r, hh - r);
                float v = pow(pow(a.x, n) + pow(a.y, n), 1.0 / n);
                return v - r;
            }
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
        }

        float sdf(vec2 p) {
            vec2 c = vec2(uW, uH) * 0.5;
            float r = (p.y < c.y) ? uCorner : uCornerBottom;
            return sdRoundRect(p, c, uW * 0.5, uH * 0.5, r, uRoundness);
        }

        void main() {
            // ★★ 2026-10-09 R7 修复：**用 `gl_FragCoord` 反推像素坐标**（★ 不用 varying/attribute）
            //
            // ⚠️ 原因：`#version 120` 里没有 `gl_VertexID`；而 `gl_Vertex` 内建属性在不同驱动上
            //   行为不完全一致 ⇒ ★ **最稳的是完全不依赖顶点着色器传参**：
            //   ★ `gl_FragCoord.xy` = **窗口坐标（原点在【左下】）**，`.xy` 在 [0.5, w-0.5]
            //   ★ AWT 的像素索引 **y 向下**，GL 纹理的 v **y 向上**
            //   ⇒ ★ 我们**同时**要两套坐标：
            //     · `pGL`（y 向上）—— 给 `texture2D(uBackdrop, …)` 采样用 ✓
            //     · `pAwt`（y 向下）—— 给 SDF 几何用（★ 因为 `uCorner`/`uCornerBottom`
            //        的上下之分是【屏幕语义】，屏幕的"上"= AWT 的 y 小 ✓）
            //   ★ 而 SDF 本身是**镜像对称**的（`abs(p-c)`），所以几何用哪套都一样；
            //     ★ 只有"上/下圆角"的判据依赖 y 方向 ⇒ 这里用 **AWT 语义**（与 CPU 版一致）✓
            float fyGL = gl_FragCoord.y;                       // [0, uH]，y 向上
            float fyAwt = uH - fyGL;                           // [0, uH]，y 向下（AWT 语义）
            vec2 pGL  = vec2(gl_FragCoord.x, fyGL);
            vec2 pAwt = vec2(gl_FragCoord.x, fyAwt);

            // ★ 几何/SDF 用 AWT 语义（决定上/下圆角）
            float d = sdf(pAwt);
            float e = 1.0;
            float gx = sdf(pAwt + vec2(e, 0.0)) - sdf(pAwt - vec2(e, 0.0));
            float gy = sdf(pAwt + vec2(0.0, e)) - sdf(pAwt - vec2(0.0, e));
            // ★★ 变量名**不能用 `gl_` 前缀**（GLSL 保留给内建）—— ★ 我第一次写成 `gl`
            //   再改成 `gl_` 都不行 ✗ ⇒ ★ 用 `gLen` ✓
            float gLen = max(length(vec2(gx, gy)), 1e-6);
            vec2 nrm = vec2(gx, gy) / gLen;

            // ★ 采样用 GL 语义（y 向上）—— 纹理上传时**没有翻转数据**（见 render 的注释）
            vec2 uvSample = pGL / vec2(uW, uH);

            vec3 col;
            if (d < 0.0) {
                float sd = -d;
                float tt = clamp(sd / max(uRefHeight, 1e-3), 0.0, 1.0);
                float k = pow(1.0 - tt, uProfileExp);
                // ★ 法线在 AWT 语义下 ⇒ 位移也要转到 GL 语义（y 取反）
                vec2 offAwt = -nrm * uRefAmount * k;
                vec2 offGL = vec2(offAwt.x, -offAwt.y);

                // ★★★ 2026-10-18（对齐参考 `uDepth`）：**深度径向项**（CPU 侧同款）
                //   `off += -p * 0.045 * h`，`h = sqrt(2t − t²)`
                vec2 pAwtC = pAwt - vec2(uW, uH) * 0.5;
                float hProf = sqrt(max(0.0, 2.0 * tt - tt * tt));
                offAwt += -pAwtC * 0.045 * hProf * uDepth;
                offGL = vec2(offAwt.x, -offAwt.y);

                // ★★★ 2026-10-18（对齐参考）：**5 档光谱 + 角落加权**（CPU 侧同款）
                //   原为 3 tap（RGB 各一次）⇒ 相邻通道无过渡；参考是连续光谱。
                //   `cornerW = clamp(cx·cy/(hx·hy))` ⇒ **四角色散强、边中弱**。
                float ca = uDispersion * 0.16;
                float cornerW = clamp(
                    (pAwtC.x * pAwtC.y) / max((uW * 0.5) * (uH * 0.5), 1.0), -1.0, 1.0);
                vec2 uvBase = uvSample + offGL / vec2(uW, uH);
                vec2 uvSpan = (offGL * ca * cornerW) / vec2(uW, uH);
                vec3 t1 = texture2D(uBackdrop, clamp(uvBase + uvSpan, 0.0, 1.0)).rgb;
                vec3 t2 = texture2D(uBackdrop, clamp(uvBase + uvSpan * 0.5, 0.0, 1.0)).rgb;
                vec3 t3 = texture2D(uBackdrop, clamp(uvBase, 0.0, 1.0)).rgb;
                vec3 t4 = texture2D(uBackdrop, clamp(uvBase - uvSpan * 0.5, 0.0, 1.0)).rgb;
                vec3 t5 = texture2D(uBackdrop, clamp(uvBase - uvSpan, 0.0, 1.0)).rgb;
                float rCh = (t1.r + t2.r * 0.5) / 1.5;
                float gCh = (t2.g * 0.5 + t3.g + t4.g * 0.5) / 2.0;
                float bCh = (t4.b * 0.5 + t5.b) / 1.5;

                // vibrancy：提饱和 + 压对比
                float lum = 0.2126 * rCh + 0.7152 * gCh + 0.0722 * bCh;
                rCh = lum + (rCh - lum) * uSaturation;
                gCh = lum + (gCh - lum) * uSaturation;
                bCh = lum + (bCh - lum) * uSaturation;
                rCh = clamp((rCh - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);
                gCh = clamp((gCh - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);
                bCh = clamp((bCh - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);

                // ★★ 2026-10-18：`uComposite = 0` ⇒ **跳过 L2 的三段**（内阴影/高光/表面），
                //   只留折射+色散+vibrancy ⇒ 与 CPU `bake` 同口径。
                if (uComposite > 0.5) {
                // 边缘内阴影（造厚度）
                float inner = exp(-sd / max(uRefHeight * 0.42, 1.0));
                float dim = 1.0 + (0.90 - 1.0) * (inner * 0.55);
                rCh *= dim; gCh *= dim; bCh *= dim;

                // 高光：rim + hairline（★ 同一 uSpecular 驱动两处 —— 与参考一致）
                vec2 lightDir = vec2(-0.72, 0.69);
                float face = clamp(dot(nrm, lightDir), -1.0, 1.0);
                float rim = exp(-sd / max(uRefHeight * 0.30, 1.0));
                float spec = rim * (0.35 + 0.65 * max(face, 0.0)) * uSpecular * 0.55;
                // ★ 2026-10-18：改 `smoothstep`（参考原文 `smoothstep(1.6*uDpr, 0, sd)`）——
                //   线性斜坡在 1~2px 的带子上是**硬折线**，观感"像像素块"。
                float hx = clamp(sd / 1.6, 0.0, 1.0);
                float hair = (1.0 - hx * hx * (3.0 - 2.0 * hx)) * uSpecular * 0.30;
                // ★★ 变量名不能叫 `add`（GLSL 保留字 —— 是 `fadd`/向量加法内建家族的词根）✗
                //   ⇒ ★ 用 `highlight` ✓
                float highlight = spec + hair;
                rCh = clamp(rCh + highlight, 0.0, 1.0);
                gCh = clamp(gCh + highlight, 0.0, 1.0);
                bCh = clamp(bCh + highlight, 0.0, 1.0);

                // 表面蒙版（混白）
                rCh += (1.0 - rCh) * uSurface;
                gCh += (1.0 - gCh) * uSurface;
                bCh += (1.0 - bCh) * uSurface;
                }

                col = vec3(rCh, gCh, bCh);
            } else {
                // ★★★ 2026-10-18：两种口径的输出不同 ——
                //   · `uComposite = 1`（整块玻璃）⇒ 形状外**透明**（否则输出是矩形，不能当玻璃片）；
                //   · `uComposite = 0`（只做 L3）⇒ 形状外**照抄背景**（它是"背景位图"，必须不透明）。
                col = (uComposite > 0.5)
                    ? vec3(0.0)
                    : texture2D(uBackdrop, clamp(uvSample, 0.0, 1.0)).rgb;
            }
            // ★★ alpha 同样按口径：
            //   · 整块玻璃 ⇒ 形状 alpha（`d<0` 内为 1，边界 1px AA）；
            //   · 只做 L3 ⇒ 恒 1（保持"不透明背景位图"的旧语义）。
            float outAlpha = (uComposite > 0.5) ? clamp(-d, 0.0, 1.0) : 1.0;
            gl_FragColor = vec4(col, outAlpha);
        }
    """.trimIndent()
}
