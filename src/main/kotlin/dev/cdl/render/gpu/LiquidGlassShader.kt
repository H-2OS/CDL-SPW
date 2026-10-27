// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render.gpu

import java.awt.image.BufferedImage

object LiquidGlassShader {

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

    fun fragmentShaderSource(): String = FRAGMENT_SHADER_330

    fun fragmentShaderFallback(): String = FRAGMENT_SHADER_120

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

    private const val HEADER_330 =
        "#version 330 core\n" +
            "#define texture2D texture\n" +
            "out vec4 outColor;\n"

    private const val HEADER_120 =
        "#version 120\n" +
            "#define outColor gl_FragColor\n"

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
            //
            // ★★★ 2026-10-21 **修边缘杂色细边**：色散在"玻璃/背景"分界处仍全强度时，
            //   R/G/B 三个采样点跨过分界 ⇒ 出现天蓝/紫色描边（真机反复反馈）。
            //   CPU 路径早已按 `-m` 做过 2px 淡出，**GPU 着色器没有** —— 而真机跑的是 GPU。
            //   ⇒ 这里按到边缘的距离 `sd` 做 **2 逻辑像素**线性淡出，内部强度不变。
            float edgeFade = clamp(sd / (2.0 * uDpr), 0.0, 1.0);
            float ca = uDisp * 0.16 * edgeFade;
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

    val FRAGMENT_SHADER_330: String by lazy { HEADER_330 + BODY }

    val FRAGMENT_SHADER_120: String by lazy { HEADER_120 + BODY }

}
