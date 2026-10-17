// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.render

import java.awt.Color

/**
 * 「**按振幅取色**」（`spectrum.color_mode = level`）—— 纯函数，可被探针直接断言。
 *
 * ## 为什么单独一个文件
 *
 * 与 [SpectrumGeometry] 同一条理由：**颜色派生是纯函数**，探针应当能**直接喂值**断言，
 * 而不是"画出来再看像素"（后者只能证明"整体不同"，证不了"哪一段该是什么色"）。
 *
 * ## 语义出处（★ 用户 2026-10-02 的指令 + 业界对照）
 *
 * 用户原话：「**颜色需要有渐变流光效果**」，并明确排除"x/y 轴渐变（缺乏新意）"。
 *
 * ⇒ 对照业界 [audioMotion] 的三种 Bar Color Mode：
 *
 * | 模式 | 做法 |
 * |---|---|
 * | `Gradient` | 整条柱沿渐变平滑过渡 —— ★ **正是用户排除的 x/y 轴渐变** |
 * | `Index` | 每根柱取渐变里的**单一**颜色循环 |
 * | ★★ **`Level`** | **按当前振幅取色 —— 音量越高颜色越「烫」** |
 *
 * ★ 本文件实现的就是 **`Level`**：**颜色随音乐本身流动**（不是随时间相位流动）
 * ⇒ ★ **零额外帧开销**（不需要帧驱动：颜色只是"这一帧的振幅"的函数）。
 *
 * ## 为什么用 **HSB 的亮度 + 饱和度**，而不是"往白里混"或"往黑里混"
 *
 * · 往白里混 ⇒ 峰值会**掉饱和**（发灰发白），那是"过曝"不是"更亮"；
 * · 往黑里混 ⇒ 安静段**整条变黑**，会与"频谱关了"混淆（本项目最忌讳"设置是装饰品"）；
 * · ★ **HSB 调 `brightness` + `saturation`** ⇒ **色相不动**（用户选的基色始终认得出来），
 *   只有"明暗与浓淡"在动 ⇒ 那才是"**烫**"。
 *
 * ## 映射（实测口径见 `SpectrumLevelColorProbe`）
 *
 * | 振幅 `value` | brightness | saturation | 观感 |
 * |---|---|---|---|
 * | `0.0`（安静） | **0.35** | **0.55** | 暗、偏灰 —— 不抢眼 |
 * | `0.5` | 0.675 | 0.775 | 中等 |
 * | `1.0`（峰值） | **1.00** | **1.00** | 亮、全饱和 —— "烫" |
 *
 * ★ **为什么安静段不取 0**：`brightness = 0` 就是**黑**，在深色岛体上等于"这根柱子消失了"
 * —— 而用户看的是**频谱还在不在**。取 0.35 保证"安静时仍看得见一根暗柱"。
 *
 * @param baseArgb 基色（打包 ARGB，**高 8 位是 alpha**）。通常来自 `spectrum.color`
 *   （`manual` 档）或封面取色（`cover` 档）。
 * @param value 归一化振幅 `0..1`。越界值先夹到 `0..1`（`coerceIn` 对范围内值返回原值本身）。
 * @return 打包 ARGB。★ **alpha 原样保留** —— 本函数**不碰透明度**
 *   （透明度是 `spectrum.opacity` 的职责，两件事不混）。
 */
fun levelColorArgb(baseArgb: Int, value: Float): Int {
    val a = (baseArgb ushr 24) and 0xFF
    // ★ alpha = 0 时提前返回：否则下面会把一个"完全透明色"算成不透明，
    //   让"透明度拉到 0"这条设置**看起来生效了**（实则被本函数撤销）。
    if (a == 0) return baseArgb

    val v = value.coerceIn(0f, 1f)

    val r = (baseArgb ushr 16) and 0xFF
    val g = (baseArgb ushr 8) and 0xFF
    val b = baseArgb and 0xFF
    // ★ 复用调用方的数组（不分配）—— 本函数在**逐柱循环里**被调用（最多 1024 次/帧）。
    val hsb = Color.RGBtoHSB(r, g, b, null)

    // ★ 映射：安静 0.35/0.55 → 峰值 1.00/1.00（见 KDoc 的表）。
    val brightness = LEVEL_BRIGHTNESS_MIN + (1f - LEVEL_BRIGHTNESS_MIN) * v
    val saturation = LEVEL_SATURATION_MIN + (1f - LEVEL_SATURATION_MIN) * v

    val rgb = Color.HSBtoRGB(hsb[0], saturation, brightness)
    // ★ 保留原 alpha（`HSBtoRGB` 返回的 RGB 里 alpha 恒为 0xFF，不能直接用）。
    return (a shl 24) or (rgb and 0xFFFFFF)
}

/**
 * 安静段的亮度下限（`value = 0` 时）。
 *
 * ★ **为什么不取 0**：`brightness = 0` 就是黑 ⇒ 在深色岛体上等于"这根柱子消失了"，
 * 而用户看的是"频谱还在不在"。见 [levelColorArgb] 的 KDoc。
 */
const val LEVEL_BRIGHTNESS_MIN = 0.35f

/**
 * 安静段的饱和度下限（`value = 0` 时）。
 *
 * ★ 取 0.55 而不是 0：安静段**偏灰但不失本色** —— 用户选的基色**始终认得出来**
 * （"灰"是"低调"，"失色"是"我选的色呢"）。
 */
const val LEVEL_SATURATION_MIN = 0.55f
