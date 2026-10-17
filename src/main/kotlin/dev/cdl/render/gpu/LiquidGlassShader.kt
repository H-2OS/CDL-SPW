// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render.gpu

import java.awt.image.BufferedImage

/**
 * ★★★ **液态玻璃的 GLSL 移植**（★ **逐字照抄**参考实现 `Code_20260908143339.html` 的 `FRAG`）。
 *
 * ## 它是什么
 *
 * 参考实现是 WebGL2（`#version 300 es`）的一段 fragment shader，管线：
 * ```
 * SDF → 法线(SDF 单位梯度) → 圆弧高度剖面 sqrt(2t−t²)
 *   → 折射位移 pow(1−t, 1.35) → 色散(RGB 三通道各采样一次)
 *   → 泊松盘模糊 → 提饱和(Rec.709 luma) + 压对比
 *   → 边缘内阴影 → 高光(rim + hairline) → 表面蒙版 → 边缘 AA
 * ```
 * ★ 本文件把它**搬到桌面 GLSL**，公式**一字未改**（★ 只有"多元素循环"被折叠成**单元素** ——
 * 因为词岛的玻璃就是**一张**圆角矩形；参考里的 `uRect[8]` / `uPara[8]` / `uCount` 因此不再需要）。
 *
 * ## ★★ 单元素版的几何约定
 *
 * ★ 玻璃元素 = **整个渲染目标**：中心 `uRes*0.5`、半尺寸 `uRes*0.5`、圆角 `uCorner`。
 * ⇒ ★ **目标矩形之外**（圆角切掉的那四角）由 SDF 判为"外部" ⇒ 显示**背景**（与参考的 `continue` 同义）✓
 * ⇒ ★ **投影**落在圆角外的那一圈上（参考的投影段原样保留）✓
 *
 * ## ★★ uniforms 与插件设置键的对应（★ 已核实）
 *
 * | shader | 插件键 | 说明 |
 * |---|---|---|
 * | `uRefHeight` | `material.backdrop.refraction_height` | 折射厚度（★ ×`dpr`） |
 * | `uRefAmount` | `material.backdrop.refraction_amount` | 折射量（★ ×`dpr`） |
 * | `uDisp` | `material.backdrop.dispersion` | 色散 |
 * | `uBlur` | `material.backdrop.blur_radius` | 模糊半径（★ ×`dpr`） |
 * | `uSat` | `material.backdrop.saturation` | 饱和度（★ **默认 150 ⇒ /100 = 1.5**） |
 * | `uSurf` | `material.liquid.surface_alpha` | 表面蒙版（★ /100） |
 * | `uSpec` | `material.liquid.glare_strength` | ★ **高光强度**（★ /100；★ 它**同时**驱动 rim 与 hairline，与参考一致） |
 * | `uShadow` | `material.liquid.shadow_strength` | 投影（★ /100） |
 * | `uDepth` | `material.liquid.depth_effect` | 立体感（`0` / `1`） |
 *
 * ★ 另有 `uBg`（背景纹理，由 [GlOffscreen.render] 上传）· `uRes` · `uDpr` · `uCorner`
 * （★ 圆角半径是**形状**量，不是设置键 —— 由调用方按当前岛体轮廓给）。
 *
 * ## ★★ 版本与 profile 兼容性（★ 诊断用）
 *
 * ★ 探针实测上下文是 **GL 4.6 Compatibility** ⇒ ★ **`#version 330 core` 可用** ✓
 * ★ 但**驱动差异**真实存在（`core` 在个别兼容上下文上会被拒）⇒ ★ 本文件**同时给出两版**：
 * [FRAGMENT_SHADER_330] 与 [FRAGMENT_SHADER_120]。
 * ★ 两版**共用同一段 shader 主体**（[BODY]），只在**头部**做语法桥接：
 * ```
 * 330： #define texture2D texture      + out vec4 outColor;
 * 120： #define outColor gl_FragColor
 * ```
 * ★ **编译失败时 [GlOffscreen] 会打印 `glGetShaderInfoLog`** ⇒ 版本/profile 不匹配是**可诊断**的 ✓
 */
object LiquidGlassShader {

    /**
     * ★★ **一次渲染的全部参数**（★ 值 = **插件设置项的原始值**，不是换算后的 uniform）。
     *
     * ★ 默认值 = 参考实现的**默认档**（"苹果默认"预设），故不传参数时得到的就是参考观感 ✓
     *
     * @param refractionHeight `material.backdrop.refraction_height`（dp）
     * @param refractionAmount `material.backdrop.refraction_amount`（dp）
     * @param dispersion `material.backdrop.dispersion`（`0..1`）
     * @param blurRadius `material.backdrop.blur_radius`（dp）
     * @param saturationPercent `material.backdrop.saturation`（`0..`，★ 默认 **150** ⇒ 1.5×）
     * @param surfaceAlphaPercent `material.liquid.surface_alpha`（`0..100`）
     * @param glarePercent `material.liquid.glare_strength`（`0..100`）
     * @param shadowPercent `material.liquid.shadow_strength`（`0..100`）
     * @param depthEffect `material.liquid.depth_effect`
     */
    data class Params(
        val refractionHeight: Double = 16.0,
        val refractionAmount: Double = 32.0,
        val dispersion: Double = 0.35,
        val blurRadius: Double = 4.0,
        val saturationPercent: Int = 150,
        val surfaceAlphaPercent: Int = 10,
        val glarePercent: Int = 60,
        val shadowPercent: Int = 60,
        val depthEffect: Boolean = true,
    )

    /** ★ 首选 fragment shader 源码（= [FRAGMENT_SHADER_330]）。 */
    fun fragmentShaderSource(): String = FRAGMENT_SHADER_330

    /** ★ 回退 fragment shader 源码（= [FRAGMENT_SHADER_120]）。 */
    fun fragmentShaderFallback(): String = FRAGMENT_SHADER_120

    /**
     * ★★ **把插件参数换算成 GLSL uniform 表**。
     *
     * ## 换算口径（★ 与参考实现的 `render()` 逐条对应）
     *
     * | 量 | 参考里的写法 | 本处 |
     * |---|---|---|
     * | 折射厚度 / 折射量 / 模糊 | `state.h * dpr` · `state.amt * dpr` · `state.blur * dpr` | ★ 同样 ×[dpr] |
     * | 饱和度 | `state.sat`（默认 1.5） | ★ `saturationPercent / 100`（★ 150 ⇒ 1.5） |
     * | 表面蒙版 / 高光 / 投影 | `state.surf` / `state.spec` / `state.shadow`（0..1） | ★ `percent / 100` |
     * | 立体感 | `state.depth ? 1 : 0` | ★ 同 |
     *
     * ★ `uRes` 取 [backdrop] 的尺寸 —— ★ **调用方须以同尺寸调用 [GlOffscreen.render]** ✓
     *
     * @param backdrop 背景位图（★ 同时作为 `uBg` 纹理；尺寸 = 渲染目标尺寸）
     * @param dpr 设备像素比（★ 把 dp 换算成设备像素）
     * @param cornerRadiusPx 岛体圆角半径（**设备像素**）
     */
    fun uniforms(
        backdrop: BufferedImage,
        dpr: Double,
        cornerRadiusPx: Double,
        params: Params = Params(),
    ): Map<String, Any?> = mapOf(
        "uBg" to backdrop,
        "uRes" to floatArrayOf(backdrop.width.toFloat(), backdrop.height.toFloat()),
        "uDpr" to dpr.toFloat(),
        "uCorner" to cornerRadiusPx.toFloat(),
        "uRefHeight" to (params.refractionHeight * dpr).toFloat(),
        "uRefAmount" to (params.refractionAmount * dpr).toFloat(),
        "uDisp" to params.dispersion.toFloat(),
        "uBlur" to (params.blurRadius * dpr).toFloat(),
        "uSat" to (params.saturationPercent / 100.0).toFloat(),
        "uSurf" to (params.surfaceAlphaPercent / 100.0).toFloat(),
        "uSpec" to (params.glarePercent / 100.0).toFloat(),
        "uShadow" to (params.shadowPercent / 100.0).toFloat(),
        "uDepth" to (if (params.depthEffect) 1f else 0f),
    )

    /**
     * ★★ **便利入口**：在 [gl] 上按本 shader 渲染一张液态玻璃位图。
     *
     * ★ 等价于手动调 [GlOffscreen.render]（目标尺寸 = [backdrop] 尺寸 ·
     * 主 shader = [FRAGMENT_SHADER_330] · 回退 = [FRAGMENT_SHADER_120]）✓
     * ★ **失败返回 `null`**（★ 与 [GlOffscreen] 同一契约 ⇒ 调用方回退 CPU）✓
     */
    fun render(
        gl: GlOffscreen,
        backdrop: BufferedImage,
        dpr: Double,
        cornerRadiusPx: Double,
        params: Params = Params(),
    ): BufferedImage? = gl.render(
        backdrop.width,
        backdrop.height,
        FRAGMENT_SHADER_330,
        uniforms(backdrop, dpr, cornerRadiusPx, params),
        FRAGMENT_SHADER_120,
    )

    // ══════════════════════════════════════════════════════════════════════════
    // 头部桥接（★ 唯一与版本相关的部分）
    // ══════════════════════════════════════════════════════════════════════════

    /** ★ GLSL 3.30 头部：`texture2D` → `texture`，输出变量 = `outColor`。 */
    private const val HEADER_330 =
        "#version 330 core\n" +
            "#define texture2D texture\n" +
            "out vec4 outColor;\n"

    /** ★ GLSL 1.20 头部：`outColor` → `gl_FragColor`（★ 120 无 `out` 变量）。 */
    private const val HEADER_120 =
        "#version 120\n" +
            "#define outColor gl_FragColor\n"

    // ══════════════════════════════════════════════════════════════════════════
    // shader 主体（★ 两版共用 · ★ 公式逐字照抄参考 `FRAG`）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ★★★ **shader 主体**（★ 版本无关 —— 见两个头部）。
     *
     * ★ 与参考 `FRAG` 的**唯一**差别：把 `uRect[i]` / `uPara[i]` / `uCount` 的循环
     * 折叠成**单元素**（词岛只有一张玻璃），几何取自 `uRes` / `uCorner`。
     * ★ 其余（SDF · 梯度 · 泊松盘 · 饱和度 · 主循环每一步）**逐字相同** ✓
     */
    private val BODY = """
        uniform sampler2D uBg;
        uniform vec2  uRes;        // 目标设备像素尺寸
        uniform float uDpr;        // 设备像素比
        uniform float uCorner;     // 圆角半径（设备像素）
        uniform float uRefHeight;  // 折射厚度（设备像素）
        uniform float uRefAmount;  // 折射量（设备像素）
        uniform float uDisp;       // 色散强度 0..1
        uniform float uBlur;       // 模糊半径（设备像素）
        uniform float uSat;        // 饱和度
        uniform float uSurf;       // 表面蒙版 alpha
        uniform float uSpec;       // 高光强度
        uniform float uShadow;     // 投影强度
        uniform float uDepth;      // depthEffect 0/1

        // ---- 圆角矩形 SDF：p 相对中心，b 半尺寸，r 圆角 ----
        float sdRoundRect(vec2 p, vec2 b, float r) {
          r = min(r, min(b.x, b.y));
          vec2 q = abs(p) - b + r;
          return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        // ---- 法线（SDF 单位梯度）----
        vec2 gradSDF(vec2 p, vec2 b, float r, float e) {
          return normalize(vec2(
            sdRoundRect(p + vec2(e, 0.0), b, r) - sdRoundRect(p - vec2(e, 0.0), b, r),
            sdRoundRect(p + vec2(0.0, e), b, r) - sdRoundRect(p - vec2(0.0, e), b, r)
          ) + 1e-6);
        }

        // ---- 采样背景纹理：px 为设备像素坐标（y 向上）----
        vec3 sampleBg(vec2 px) {
          vec2 uv = clamp(px / uRes, vec2(0.0), vec2(1.0));
          return texture2D(uBg, vec2(uv.x, 1.0 - uv.y)).rgb;
        }

        // ---- 12-tap 泊松盘（2 环 + 中心 = 25 taps）----
        vec3 blurBg(vec2 px, float radius) {
          if (radius <= 0.01) return sampleBg(px);
          const int N = 12;
          vec2 disk[12];
          disk[0]  = vec2( 0.926,  0.134);
          disk[1]  = vec2(-0.708,  0.592);
          disk[2]  = vec2( 0.381, -0.899);
          disk[3]  = vec2(-0.943, -0.282);
          disk[4]  = vec2( 0.152,  0.976);
          disk[5]  = vec2(-0.284, -0.941);
          disk[6]  = vec2( 0.687, -0.539);
          disk[7]  = vec2(-0.534,  0.813);
          disk[8]  = vec2( 0.976, -0.052);
          disk[9]  = vec2(-0.161, -0.261);
          disk[10] = vec2( 0.313,  0.417);
          disk[11] = vec2(-0.437, -0.118);
          vec3 acc = sampleBg(px);
          float w = 1.0;
          for (int i = 0; i < N; i++) {
            acc += sampleBg(px + disk[i] * radius);
            acc += sampleBg(px + disk[i] * radius * 0.55);
            w += 2.0;
          }
          return acc / w;
        }

        // ---- 饱和度（Rec.709 luma）----
        vec3 saturate3(vec3 c, float s) {
          float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
          return clamp(mix(vec3(l), c, s), 0.0, 1.0);
        }

        void main() {
          vec2 px = vec2(gl_FragCoord.x, gl_FragCoord.y);

          // ---------- 1) 背景 ----------
          vec3 col = sampleBg(px);

          // ---------- 2) 玻璃元素（单元素：整张目标 = 一块圆角玻璃）----------
          vec2  c  = uRes * 0.5;
          vec2  hb = uRes * 0.5;
          float corner = uCorner;
          float refH   = max(uRefHeight, 0.5);
          float refA   = uRefAmount;
          float rr = min(corner, min(hb.x, hb.y));

          vec2  p = px - c;
          float d = sdRoundRect(p, hb, rr);

          // ---------- 3) 投影 ----------
          if (uShadow > 0.001 && d > 0.0) {
            float spread = 26.0 * uDpr;
            float a = exp(-d / spread) * uShadow * 0.34;
            col *= (1.0 - clamp(a, 0.0, 0.75));
          }

          // ---------- 4) 玻璃 ----------
          if (d < 0.0) {
            float sd = -d;                         // 内部到边缘的距离
            vec2  g  = gradSDF(p, hb, rr, 1.0);    // SDF 单位梯度（指向外部）

            // 高度剖面：圆弧 h = sqrt(2t - t^2)，t 为到边缘的归一化距离
            float t = clamp(sd / refH, 0.0, 1.0);
            float h = sqrt(max(0.0, 2.0 * t - t * t));

            // 折射位移：边缘最强，向中心衰减
            float k = pow(1.0 - t, 1.35);
            vec2  off = -g * refA * k;             // 向内采样 → 边缘把外界内容"吸"进来

            if (uDepth > 0.5) {
              off += -p * 0.045 * h;
            }

            // 色散：RGB 三通道折射率略有差异
            float ca = uDisp * 0.16;
            vec3 sampled;
            if (ca > 0.001) {
              sampled.r = blurBg(px + off * (1.0 + ca), uBlur).r;
              sampled.g = blurBg(px + off, uBlur).g;
              sampled.b = blurBg(px + off * (1.0 - ca), uBlur).b;
            } else {
              sampled = blurBg(px + off, uBlur);
            }

            // vibrancy：先提饱和再压对比（贴近 iOS 观感）
            vec3 glass = saturate3(sampled, uSat);
            glass = clamp((glass - 0.5) * 1.02 + 0.5 + 0.012, 0.0, 1.0);

            // 边缘内阴影：制造"厚度"
            float inner = exp(-sd / max(refH * 0.42, 1.0));
            glass *= mix(1.0, 0.90, inner * 0.55);

            // 高光：沿光向（左上）的内缘亮环 + 整圈细亮边
            vec2  lightDir = normalize(vec2(-0.72, 0.69));
            float face = clamp(dot(g, lightDir), -1.0, 1.0);
            float rim  = exp(-sd / max(refH * 0.30, 1.0));
            float spec = rim * (0.35 + 0.65 * max(face, 0.0)) * uSpec * 0.55;
            float hair = smoothstep(1.6 * uDpr, 0.0, sd) * uSpec * 0.30;
            glass += (spec + hair) * vec3(1.0);

            // 表面蒙版（对应 onDrawSurface 的白 / 着色层）
            glass = mix(glass, vec3(1.0), uSurf);

            // 边缘抗锯齿
            float aa = 1.0 * uDpr;
            float edge = smoothstep(0.0, -aa, d);
            col = mix(col, clamp(glass, 0.0, 1.0), edge);
          }

          outColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // ===========================================================================
    // 声明顺序说明（2026-10-09 修）
    // ===========================================================================
    // 这两个 val 用 `by lazy` 引用 BODY（一个 val，非 const）⇒
    // 在 Kotlin 的 object 里，属性初始化按**声明顺序**执行 ⇒
    // 若把它们写在 BODY **之前**，编译期会报「Variable 'BODY' must be initialized」。
    // ⇒ 所以它们必须放在 BODY **之后**。（HEADER_330/120 是 const val，本不受此限。）

    /**
     * ★★ **主 fragment shader**（`#version 330 core`）。
     *
     * ★ 探针实测的上下文（GL 4.6 Compatibility）能直接吃它 ⇒ ★ **这是首选** ✓
     * ★ 编译失败 ⇒ 改试 [FRAGMENT_SHADER_120]（见 [GlOffscreen.render] 的回退参数）。
     */
    val FRAGMENT_SHADER_330: String by lazy { HEADER_330 + BODY }

    /**
     * ★★ **回退 fragment shader**（`#version 120`）。
     *
     * ★ 与 [FRAGMENT_SHADER_330] **共用同一段 [BODY]**，只在头部把
     * `texture2D` / `outColor` 桥接到 GLSL 1.20 的写法 ⇒ ★ **公式逐字相同** ✓
     * ⚠️ 若真走到这一版，`in/out` 语义已由头部宏处理，**主体无需改动** ✓
     */
    val FRAGMENT_SHADER_120: String by lazy { HEADER_120 + BODY }

}
