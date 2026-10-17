// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.image.BufferedImage

/**
 * ★★★ **R7 · GPU 液态玻璃渲染后端**（2026-10-09 · 用户拍板「R7 开工」）。
 *
 * ## 它做什么
 *
 * ★ 把参考 HTML（`Code_20260908143339.html` 的 `FRAG`）的**完整逐像素管线**搬到 GPU 上：
 * ```
 * SDF → 法线(中心差分) → 圆弧剖面 → 折射位移 pow(1−t,1.35)
 *   → 色散(RGB 三通道) → vibrancy(提饱和 + 压对比)
 *   → 边缘内阴影 → 高光(rim + hairline) → 表面蒙版
 * ```
 * ★ 用 **JNA 直调 `opengl32.dll` + `gdi32.dll`**（★ **零新增第三方依赖** —— 项目已依赖 JNA 5.17.0）✓
 *
 * ## ★★★ 为什么可以不用第三方 GL 库（许可分析结论）
 *
 * | 候选 | 许可 | 判定 |
 * |---|---|---|
 * | JOGL | 主 BSD，但含 NVIDIA Cg 专有许可等杂项 | ⚠️ 需逐 jar 核实 |
 * | LWJGL 3 | BSD 3-Clause，但离屏需 GLFW/EGL | ✓ 干净但多依赖 |
 * | ★★★ **JNA 直调系统 DLL** | **只调 Windows 自带系统 DLL** | ★★★ **无第三方许可** ✓ |
 *
 * ⇒ ★ **零新增依赖 · 零许可风险 · 零体积增长**（见 `GpuOffscreenProbe` 的 KDoc）✓
 *
 * ## ★★★ 线程归属（★ 关键，与项目纪律一致）
 *
 * ★ **GL 上下文是【线程亲和】的** ⇒ ★ 本对象的所有 GL 调用**必须在同一个线程**上。
 * ★★ 本项目把它绑到 **L3 采集线程**（`BackdropCapture.L3CaptureWorker`，10 Hz）——
 * ★ 因为折射烘焙本来就在那里（`BackdropRefraction.bake` 的同族工作）✓
 * ⇒ ★ **EDT 侧一行都不改**（它只 `drawImage` 一张烘好的图）✓
 *
 * ## ★★ 失败即回退（★ 本类的核心契约）
 *
 * ★ **任何一步失败都抛异常**（拿不到 DC / 上下文 / 编译失败 / FBO 不完整 / 读回失败）
 * ★ 入口 [isAvailable] 供调用方**一次性探测**；★ 调用方（`MaterialLayers` / `BackdropCapture`）
 *   **捕获异常后回退 CPU 版** ⇒ ★ **绝不让 GPU 路径成为单点故障** ✓
 *
 * ## ⚠️ 已知边界（如实记录）
 *
 * · ★ **只在 Windows 上可用**（`opengl32.dll` / `gdi32.dll`）⇒ 非 Windows **直接 `isAvailable = false`** ✓
 * · ★ **驱动不支持的机器**（无 GL 2.0+ / 无 FBO）⇒ `isAvailable = false` ⇒ 回退 CPU ✓
 * · ★ **本类不处理多 GPU 切换**（用系统默认适配器）
 */
object GpuLiquidGlass {

    // ══════════════════════════════════════════════════════════════════════════
    // GL 常量（★ 只声明用到的）
    // ══════════════════════════════════════════════════════════════════════════

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

    // Win32
    private const val PFD_DRAW_TO_BITMAP = 0x00000008
    private const val PFD_SUPPORT_OPENGL = 0x00000020
    private const val PFD_DOUBLEBUFFER = 0x00000001
    private const val PFD_TYPE_RGBA = 0

    // ── Win32 绑定 ────────────────────────────────────────────────────────────

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

    /** ★ `PIXELFORMATDESCRIPTOR`（Win32，40 字节）—— 与 `GpuOffscreenProbe` 逐字相同。 */    @com.sun.jna.Structure.FieldOrder(
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

    // ── GL 函数指针（★ 由 `wglGetProcAddress` 取，缓存起来）──────────────────

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
        // ★★ 2026-10-09 R7 修复：**GL 1.1 之后的函数必须全走 `wglGetProcAddress`**
        //   ⚠️ 我原以为 `glActiveTexture`/`glBindTexture` 等能从 `opengl32.dll` 静态拿到
        //   ⇒ ★★ **错**：Windows 的 `opengl32.dll` **只静态导出 GL 1.1**（1997 年的那批）
        //   ⇒ ★ 报 `UnsatisfiedLinkError: Error looking up function 'glActiveTexture'` ✗
        //   ★ 正解 = 与其它 GL 2.0 函数一样走 `wglGetProcAddress` ✓
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

    // ── 状态（★ 全部绑在【单一线程】上，见类 KDoc）─────────────────────────────

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

    /** ★ 探测结果（**只算一次**，失败后再问也不重试 —— 避免每轮都付出异常代价）。 */
    @Volatile
    private var probeResult: Boolean? = null

    /** ★ 探测失败的原因（★ 报告用 —— 让"为什么没上 GPU"是可查的，而不是静默回退）。 */
    @Volatile
    var lastUnavailableReason: String = ""
        private set

    /** ★★ 最近一次 GPU 渲染的耗时（ns）；★ **只报告，不断言**（照项目纪律）。 */
    @Volatile
    var lastRenderNanos: Long = 0L
        private set

    // ══════════════════════════════════════════════════════════════════════════
    // 公开入口
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ★★ **GPU 路径是否可用**（★ 幂等探测，失败原因记在 [lastUnavailableReason]）。
     *
     * ⚠️ **必须在将要使用它的那个线程上调用**（GL 上下文是线程亲和的）——
     * ★ 生产路径 = **L3 采集线程** ✓
     */
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

    /**
     * ★★★ **用 GPU 渲染一张液态玻璃图**（= 参考 `FRAG` 的完整管线）。
     *
     * @param backdrop 已模糊的**身后背景**（RGB 被采样；尺寸 = 岛体 `w × h`）
     * @param params 与 CPU 版同构的参数（★ 复用 [BackdropRefraction.Params] 的物理量口径）
     * @param cornerRadius 岛体圆角（px）· `cornerRadiusBottom` = `NaN` ⇒ 与上边同值
     * @return 新位图（**不复用** —— 与 CPU 版同契约：下游会持有它）
     * @throws IllegalStateException GPU 路径不可用或任一步失败（★ **调用方必须回退 CPU**）
     */
    fun render(
        backdrop: BufferedImage,
        cornerRadius: Double,
        params: BackdropRefraction.Params,
        cornerRadiusBottom: Double = Double.NaN,
        surfaceAlpha: Double = 0.10,
        glarePercent: Int = 60,
    ): BufferedImage {
        if (!isAvailable()) {
            throw IllegalStateException("GPU 路径不可用：$lastUnavailableReason")
        }
        val g = gl ?: throw IllegalStateException("GL 函数表未初始化")
        val w = backdrop.width
        val h = backdrop.height
        require(w > 0 && h > 0) { "尺寸非法：$w×$h" }

        val t0 = System.nanoTime()
        ensureTargets(w, h)

        // ★★ 2026-10-09 R7 修复：**先把历史错误清空**
        //   ⚠️ 我原来只检查**渲染后**的 `glGetError()` ⇒ ★ 它会把**探测阶段**遗留的错误
        //      也算进来（★ `GL_INVALID_OPERATION` 0x502 就是这么误报的）✗
        //   ★ 正解 = 画之前**排空**错误队列（GL 的错误是**累积**的，不是"最后一次"）✓
        while (opengl32!!.glGetError() != GL_NO_ERROR) { /* drain */ }
        // ★ 并且**显式重绑 FBO** —— 探测阶段的其它调用可能改过绑定 ✓
        g.bindFramebuffer.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, fbo))

        // ── 上传背景 ────────────────────────────────────────────────────────
        val argb = IntArray(w * h)
        backdrop.getRGB(0, 0, w, h, argb, 0, w)
        // ★ ARGB(Int) → RGBA(byte[])：★ 注意 **Y 轴不翻转** ——
        //   GL 的纹理原点在左下，而 AWT 在左上 ⇒ 我们在 shader 里用
        //   `vUv.y = 1 - y/h` 对齐（★ 见 FRAG 的 `fy`），**不在这里翻转数据**（少一次全图拷贝）。
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

        // ── 传 uniform ──────────────────────────────────────────────────────
        g.useProgram.invokeVoid(arrayOf<Any>(program))
        // ★★ 2026-10-09 R7 修复：**画之前重新绑一次输入纹理到 0 号单元**
        //   ⚠️ `ensureTargets()` 会在 0 号单元上绑 `outputTex`（FBO 附件）
        //   ⇒ ★ 如果不重绑，采样器读到的就是**正在写的这张空图** ⇒ **整图全黑** ✗
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

        // ── 画 ──────────────────────────────────────────────────────────────
        // ★★ 再排空一次 —— `ensureTargets()` 里建纹理/FBO 的调用可能也会留下错误 ✓
        while (opengl32!!.glGetError() != GL_NO_ERROR) { /* drain */ }
        g.viewport.invokeVoid(arrayOf<Any>(0, 0, w, h))
        g.disable.invokeVoid(arrayOf<Any>(GL_DEPTH_TEST))
        g.disable.invokeVoid(arrayOf<Any>(GL_BLEND))

        // ★★ 2026-10-09 R7 修复（第四轮 · 关键）：**顶点坐标必须走 VBO，不能用客户端指针**
        //
        // ⚠️ 我原以为传 `FloatArray` 给 `glVertexAttribPointer` 就行
        //   ⇒ ★ 实测 **`GL_INVALID_OPERATION`（0x502）** ✗
        //   ★ 原因：**AMD 的兼容 profile 也不允许"无 VBO 的客户端属性数组"**（GL 3.1+ 移除）
        //     ⇒ ★ `glVertexAttribPointer(…, pointer=客户端数组)` 在 VBO 未绑定时非法 ✗
        // ★★ 正解 = **建一个静态 VBO，把 6 个顶点一次性传上去**（★ 每帧只 `drawArrays`）✓
        ensureVertexBuffer()
        g.bindBuffer.invokeVoid(arrayOf<Any>(GL_ARRAY_BUFFER, vbo))
        checkErr(g, "bindBuffer")
        g.enableVertexAttribArray.invokeVoid(arrayOf<Any>(0))
        checkErr(g, "enableVertexAttribArray")
        // ★ 末参 = **VBO 内的字节偏移 0**（★ 这时它是 `offset`，不是指针）✓
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

        // ── 读回 ────────────────────────────────────────────────────────────
        val out = ByteArray(w * h * 4)
        g.readPixels.invokeVoid(
            arrayOf<Any?>(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out),
        )

        lastRenderNanos = System.nanoTime() - t0

        // ★ RGBA(byte[]) → ARGB(Int)：★ 同样**不翻转 Y**（GL 的 (0,0) 在左下，
        //   我们把 shader 的输出写成"AWT 的 y 顺序" ⇒ 逐行对应）✓
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

    /** ★ 释放 GL 资源（★ 退出/线程结束前调用；失败不抛）。 */
    fun dispose() {
        try {
            val g = gl
            if (g != null) {
                // ① 程序对象
                if (program != 0) {
                    val r = IntByReference(program)
                    g.deleteProgram.invokeVoid(arrayOf<Any>(r))
                }
                // ② FBO
                if (fbo != 0) {
                    val r = IntByReference(fbo)
                    g.deleteFramebuffers.invokeVoid(arrayOf<Any>(1, r))
                }
                // ③ 纹理（★ 两张一起删 —— 与 FBO 同走函数表 ✓）
                val texs = intArrayOf(inputTex, outputTex).filter { it != 0 }.toIntArray()
                if (texs.isNotEmpty()) {
                    g.deleteTextures.invokeVoid(arrayOf<Any>(texs.size, texs))
                }
                // ④ VBO
                if (vbo != 0) {
                    val r = IntArray(1); r[0] = vbo
                    g.deleteBuffers.invokeVoid(arrayOf<Any>(1, r))
                }
                // ⑤ 着色器对象（★ 链接后即可删；GL 会保留引用）
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

    /** ★ 报告字符串（★ 供设置页/日志显示"用的是哪块 GPU"）。 */
    fun describe(): String {
        if (lastUnavailableReason.isNotEmpty()) return "GPU 不可用（$lastUnavailableReason）"
        val o = opengl32 ?: return "GPU 不可用（opengl32 未加载）"
        val r = o.glGetString(GL_RENDERER)?.getString(0) ?: "?"
        val v = o.glGetString(GL_VERSION)?.getString(0) ?: "?"
        return "$r · GL $v"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════════════════════

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

        // ★ 取函数指针（缺一个就失败 —— GPU 路径需要完整的 GL 3.0 集）
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
        // ⚠️ `glBindAttribLocation` 是 GL 2.0 核心函数 —— ★ 有些驱动只在 `wglGetProcAddress`
        //   提供（本项目已实测这条路可行）✓
        gg.bindAttribLocation = need("glBindAttribLocation")
        // ★ GL 1.1 之后（静态导出拿不到 ⇒ 必须走 wglGetProcAddress）
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

        // ★★ 2026-10-09 R7 修复：`glUniform*` 是 GL 2.0 的**核心**函数（不在 wglGetProcAddress
        //   的扩展表里，某些驱动会返回 null）⇒ ★ **先试 wglGetProcAddress，失败则用
        //   `opengl32.dll` 的静态导出**（`OpenGL32` 接口已声明 *1i/*1f 会走静态链接）。
        //   ⚠️ 第一次 R7 跑起来就是卡在这里（`pUniform1i` 未初始化 ⇒ 运行期炸）✗
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
        // ★★ **必须在 link 之前**把 `aPos` 绑到 0 号槽（★ 否则槽号由驱动指派，客户端不知道 ✗）
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
        // ★★★ 2026-10-09 R7 修复（关键）：**`glShaderSource` 的传参必须精确**
        //
        // ⚠️ 原写法：`arrayOf(sh, 1, arrayOf(src), intArrayOf(src.length))`
        //   ★ 两个问题：
        //     ① **`String[]` 不是 `char**`** —— JNA 会把它当**宽字符/对象数组**推，驱动读到的是垃圾 ✗
        //     ② **`src.length` 是【字符】数，不是【字节】数** —— ★ 我们的 GLSL 里有中文注释
        //        （★、⇒ 等 3 字节 UTF-8）⇒ ★ **字节数 > 字符数** ⇒ 驱动**只读到一半就 EOF**
        //        ⇒ ★★ **报的就是我看到的 `unexpected end of file`** ✗✗
        // ★★ 正解：① 传 **`Pointer` 数组**（JNA 会正确地当 `char**`）；② **长度传 `null`**
        //    ⇒ **让驱动用 NUL 终止**（★ 最稳，且不依赖长度口径）✓
        val bytes = src.toByteArray(Charsets.UTF_8)
        val mem = com.sun.jna.Memory((bytes.size + 1).toLong())
        mem.write(0, bytes, 0, bytes.size)
        mem.setByte(bytes.size.toLong(), 0)          // ★ NUL 终止
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

    /**
     * ★★★ **建全屏三角的 VBO**（★ 一次性 —— 顶点是常量，之后每帧只 `drawArrays`）✓
     *
     * ★ 内容 = 两个三角形覆盖 `[-1,1]²`（clip space）：
     * ```
     * (-1,-1) ( 1,-1) (-1, 1)     左下半
     * (-1, 1) ( 1,-1) ( 1, 1)     右上半
     * ```
     * ⚠️ **踩坑留痕**：★ 不用 VBO 直接把客户端 `FloatArray` 交给 `glVertexAttribPointer`
     *   ⇒ ★ **`GL_INVALID_OPERATION`（0x502）** —— ★ 因为 GL 3.1+ **移除了客户端属性数组**
     *   （★ AMD 的兼容 profile 也照此执行）⇒ ★ **必须走 VBO** ✗→✓
     */
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
        // ★★ JNA 传 `const void*` 的**可靠做法 = `Memory`**（★ `FloatArray` 直传在这里
        //   ★ 会让 `glBufferData` 拿到垃圾指针 ⇒ ★ 报 `GL_INVALID_OPERATION`）✗
        //   ★ 我们写清楚字节（★ 小端 float32）✓
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

        // 清理旧的
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

        // 输入纹理
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

        // 输出纹理（FBO 颜色附件）
        val tOut = IntArray(1)
        g.genTextures.invokeVoid(arrayOf<Any>(1, tOut))
        outputTex = tOut[0]
        g.bindTexture.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, outputTex))
        g.texImage2D.invokeVoid(
            arrayOf<Any?>(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null),
        )
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR))
        g.texParameteri.invokeVoid(arrayOf<Any>(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR))

        // FBO
        val fb = IntByReference(0)
        g.genFramebuffers.invokeVoid(arrayOf<Any>(1, fb))
        fbo = fb.value
        g.bindFramebuffer.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, fbo))
        g.framebufferTexture2D.invokeVoid(arrayOf<Any>(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTex, 0))
        val st = g.checkFramebufferStatus.invokeInt(arrayOf<Any>(GL_FRAMEBUFFER)) as Int
        if (st != GL_FRAMEBUFFER_COMPLETE) error("FBO 不完整：0x${Integer.toHexString(st)}")

        texW = w; texH = h
    }

    // ── `glUniform*` 的函数指针 ─────────────────────────────────────────────────
    //
    // ★★ **为什么不用 `OpenGL32` 接口直呼**：`glUniform1i` 是 GL 2.0 的**核心**函数，
    //   但 `opengl32.dll` 的**静态导出表里并没有它**（Windows 的 `opengl32.dll` 只导出 GL 1.1！
    //   ★ 这也是 `GpuOffscreenProbe` 里 `glGetProcAddress` 存在的原因）。
    //   ⇒ ★ **必须先试 `wglGetProcAddress`**，返回 null 时才回退系统 DLL 直呼 ✓
    private lateinit var pUniform1i: com.sun.jna.Function
    private lateinit var pUniform1f: com.sun.jna.Function

    /**
     * ★ **先 `wglGetProcAddress`，失败再退静态导出** —— ★ 只为 `glUniform*` 这类
     * "GL 1.1 之后的核心函数"准备（★ 其余 GL 3.0 函数没有静态路径，拿不到就失败 ✓）。
     *
     * ⚠️ **踩坑留痕**：★ 我第一版把第二个参数写成 `() -> Function`（lambda）⇒ ★ 调用点
     *   传的是 `Function` 对象本身 ⇒ **类型不匹配编译错**。★ 改成**直接传 `Function?`**
     *   （由调用方自行 `getFunction`，失败给 `null`）更直白 ✓
     */
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

    /** ★ 最近一次渲染走到哪个阶段（★ 供 `glGetError` 报错时定位 —— 排查用）。 */
    @Volatile
    private var lastStage: String = "init"

    /** ★ 阶段性检查（★ 只在渲染路径里用；★ 出错立即带阶段名抛出）。 */
    private fun checkErr(g: Gl, stage: String) {
        lastStage = stage
        val e = opengl32!!.glGetError()
        if (e != GL_NO_ERROR) {
            throw IllegalStateException("glGetError = 0x${Integer.toHexString(e)}（阶段：$stage）")
        }
    }

    /** ★ 从 `opengl32.dll` 取静态导出（★ 拿不到返回 `null`，不抛）。 */
    private fun staticFnOrNull(name: String): com.sun.jna.Function? = try {
        com.sun.jna.Function.getFunction(
            com.sun.jna.NativeLibrary.getInstance("opengl32").getFunction(name),
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * ★★★ **`glUniform*` 的调用方式**（★ 2026-10-09 R7 第六轮 · **实测定案**）
     *
     * ## ★ 真凶（终于抓到）
     *
     * ★★ C 签名是 **`void glUniform1i(GLint location, GLint v0)`** —— ★ **只有 2 个参数**。
     *   ★ 我原来写的是 `invokeVoid(arrayOf(program, loc, v))` —— ★ **多传了一个 `program`** ✗
     *   ⇒ 实际封送变成：`location ← program(=3)`、`v0 ← loc(=12)` ⇒
     *   ★★ **值 304.0 被当成 location 扔掉、location 12 被当成值** ⇒
     *   ★★ **`GL_INVALID_OPERATION (1282)`**（location 3 在这个 program 里不是 float uniform）✓
     *   ★ 这**完美解释了**「`loc` 打印正确、`v` 打印正确、却报 1282」的矛盾现象 ✓
     *
     * ## ⚠️ 为什么之前没查出来（★ 值得留档的弯路）
     *
     * ★ 我盯着"JNA 封送 `Float`"查了四轮（甚至写了 `T3.java` 独立程序验证封送正确），
     *   而 ★ **真正的错是"参数个数"**（比"参数类型"更基础）⇒
     *   ★★ **教训：拿到函数指针后，第一件事是【数 C 签名里的参数个数】，再查类型** ✓
     * ★ 前四轮的假设（"无类型路径下 `Float` 映射不可靠" / "`opts` 键名写错"）**全被推翻** ——
     *   ★ 实测 `invokeVoid` + `java.lang.Float` 的封送**一直是对的** ✓
     */
    private fun setUniform1i(g: Gl, name: String, v: Int) {
        val loc = uniformLocs[name] ?: -1
        if (loc == -1) return
        // ★★ `glUniform1i(location, v0)` —— **只要 2 个参数** ✓
        pUniform1i.invokeVoid(arrayOf<Any>(loc, v))
    }

    private fun setUniform1f(g: Gl, name: String, v: Float) {
        val loc = uniformLocs[name] ?: -1
        if (loc == -1) return
        // ★★ `glUniform1f(location, v0)` —— **只要 2 个参数** ✓
        pUniform1f.invokeVoid(arrayOf<Any>(loc, v))
    }

    private val UNIFORM_NAMES = listOf(
        "uBackdrop", "uW", "uH", "uCorner", "uCornerBottom", "uRoundness",
        "uRefHeight", "uRefAmount", "uProfileExp", "uDispersion", "uSaturation",
        "uSurface", "uSpecular",
    )

    // ══════════════════════════════════════════════════════════════════════════
    // Shader（★ 逐字移植参考 `FRAG` 的管线）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ★★★ **顶点着色器（`#version 120`）** —— ★ 只铺满全屏。
     *
     * ⚠️ **踩坑留痕（两轮）**：
     *   ① ★ 第一版用 **`gl_VertexID`** ⇒ ★ **GLSL 1.20 不存在**（1.30+ 才有）✗
     *   ② ★ 第二版改用 **`gl_Vertex`** ⇒ ★★ **它也不是内建属性**（那是**固定功能**管线的
     *      顶点入口，**不在 GLSL 的预定义变量表里**）⇒ 编译报 `unexpected end of file` ✗
     * ★★ **正解 = 显式 `attribute`**（GLSL 1.20 的标准做法）——
     *   ★ 用 `glBindAttribLocation` 把它**绑到 0 号槽**，客户端用 `glVertexAttribPointer` 喂进去 ✓
     */
    private val VERT = """
        #version 120
        attribute vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * ★★★ **逐字移植参考 `Code_20260908143339.html` 的 `FRAG`**（2026-10-09 · R7）。
     *
     * ★ 顺序：SDF → 法线 → 圆弧剖面 → 折射 `pow(1−t,1.35)` → 色散(三通道)
     *   → vibrancy(提饱和 + 压对比) → 内阴影 → 高光(rim + hairline) → 表面蒙版 ✓
     * ★ 与 CPU 版（[BackdropRefraction] / [MaterialLayers]）**同公式**，以便逐像素对照 ✓
     */
    private val FRAG = """
        #version 120
        uniform sampler2D uBackdrop;
        uniform float uW, uH, uCorner, uCornerBottom, uRoundness;
        uniform float uRefHeight, uRefAmount, uProfileExp;
        uniform float uDispersion, uSaturation, uSurface, uSpecular;

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

                float ca = uDispersion * 0.16;
                vec2 uvR = uvSample + offGL * (1.0 + ca) / vec2(uW, uH);
                vec2 uvG = uvSample + offGL / vec2(uW, uH);
                vec2 uvB = uvSample + offGL * (1.0 - ca) / vec2(uW, uH);
                float rCh = texture2D(uBackdrop, clamp(uvR, 0.0, 1.0)).r;
                float gCh = texture2D(uBackdrop, clamp(uvG, 0.0, 1.0)).g;
                float bCh = texture2D(uBackdrop, clamp(uvB, 0.0, 1.0)).b;

                // vibrancy：提饱和 + 压对比
                float lum = 0.2126 * rCh + 0.7152 * gCh + 0.0722 * bCh;
                rCh = lum + (rCh - lum) * uSaturation;
                gCh = lum + (gCh - lum) * uSaturation;
                bCh = lum + (bCh - lum) * uSaturation;
                rCh = clamp((rCh - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);
                gCh = clamp((gCh - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);
                bCh = clamp((bCh - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);

                // 边缘内阴影（造厚度）
                float inner = exp(-sd / max(uRefHeight * 0.42, 1.0));
                float dim = 1.0 + (0.90 - 1.0) * (inner * 0.55);
                rCh *= dim; gCh *= dim; bCh *= dim;

                // 高光：rim + hairline（★ 同一 uSpecular 驱动两处 —— 与参考一致）
                vec2 lightDir = vec2(-0.72, 0.69);
                float face = clamp(dot(nrm, lightDir), -1.0, 1.0);
                float rim = exp(-sd / max(uRefHeight * 0.30, 1.0));
                float spec = rim * (0.35 + 0.65 * max(face, 0.0)) * uSpecular * 0.55;
                float hair = (1.0 - clamp(sd / 1.6, 0.0, 1.0)) * uSpecular * 0.30;
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

                col = vec3(rCh, gCh, bCh);
            } else {
                col = texture2D(uBackdrop, clamp(uvSample, 0.0, 1.0)).rgb;
            }
            gl_FragColor = vec4(col, 1.0);
        }
    """.trimIndent()
}
