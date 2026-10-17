// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.cover

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 封面解码的**长边上限**（像素）。
 *
 * ## 为什么必须有上限（2026-10-17 用户报障）
 *
 * 用户原话：「**切歌瞬间内存占用会飙高 100-200 多 M**」。
 *
 * 根因不是"泄漏"，而是**按原图分辨率解码**：一张 4000×4000 的内嵌封面，
 * `TYPE_INT_ARGB` 一张就是 **61 MB**；而切歌时同一条链上会同时出现：
 *
 * | # | 分配点 | 4000×4000 时 |
 * |---|---|---|
 * | ① | 解码结果（本文件修掉） | 61 MB |
 * | ② | `CoverScaler` 的中心方形副本 | 61 MB |
 * | ③ | `areaAverage` 的 `IntArray(sw*sh)` | 61 MB |
 * | ④ | **上一首**的图（两个 `srcRef` 还强引用着） | 61 MB |
 *
 * ⇒ **峰值 ≈ 244 MB**，与用户观察到的"100-200 多 M"同一量级 ✓
 *
 * ## 为什么是 1024
 *
 * 封面在屏幕上的显示边长 = `bitmapRenderPx(size, deviceScale)`：
 * 词岛封面通常 80~160 逻辑 px，2× DPI 下 ≤ **320 物理 px** ⇒ 1024 已有 **3.2×** 余量。
 * 背景封面（`CoverBackdrop`）虽然会铺满岛宽，但它**必过模糊**，1024 足够。
 * ★ 代价如实登记：**4K 屏 + 超大岛宽 + 关闭模糊**时，背景封面可能略软；
 *   换来的是峰值从 ~244 MB 降到 ~17 MB（4 张 1024² ARGB）。
 *
 * ## 为什么用"降采样解码"而不是"解码后再缩"
 *
 * `ImageReadParam.setSourceSubsampling` 让 **JPEG/PNG 解码器直接跳过像素**
 * ⇒ 61 MB 的那张图**根本不会被分配**。若先 `ImageIO.read` 再 `drawImage` 缩小，
 * 峰值仍然包含那张 61 MB —— 治标不治本 ✗
 */
const val COVER_DECODE_MAX_SIDE: Int = 1024

/**
 * 解码**内存里的**封面字节，并保证长边 ≤ [maxSide]。
 *
 * 实现分两层：
 * 1. **降采样解码**（首选）—— 让解码器直接按 [decodeSampleFor] 的因子跳像素；
 * 2. **兜底缩放** —— 某些 reader 会忽略 subsampling（或图只比上限大一点、
 *    用降采样会损失过多细节）⇒ 解码后再双线性缩到上限。
 *
 * 任何失败都返回 `null`（与 `CoverArtProvider` 的契约一致：没有封面是常态，不是异常）。
 *
 * @param data 图片字节（内嵌 APIC / FLAC PICTURE / MP4 covr）
 * @param maxSide 长边上限，默认 [COVER_DECODE_MAX_SIDE]
 */
fun decodeCoverImage(data: ByteArray, maxSide: Int = COVER_DECODE_MAX_SIDE): BufferedImage? {
    if (data.isEmpty()) return null
    // ★ 用 `MemoryCacheImageInputStream` 而不是 `ImageIO.createImageInputStream`：
    //   后者对 `InputStream` 默认可能落**磁盘缓存文件**（`ImageIO.useCache`），
    //   切歌时多一次写盘 + 临时文件，纯属浪费。
    val input = MemoryCacheImageInputStream(ByteArrayInputStream(data))
    return decodeFrom(input, maxSide)
}

/**
 * 解码**磁盘上的**封面文件（同目录 `cover.jpg` / `folder.jpg` 那条兜底路径），
 * 同样保证长边 ≤ [maxSide]。
 *
 * ★ 这条路径以前直接用 `ImageIO.read` —— 一张 4000×4000 的 `cover.jpg`
 *   会和内嵌图一样分配 61 MB，**同一个缺陷的第二处入口** ✗
 */
fun decodeCoverFile(path: Path, maxSide: Int = COVER_DECODE_MAX_SIDE): BufferedImage? {
    val input = runCatching { ImageIO.createImageInputStream(path.toFile()) }.getOrNull()
        ?: return null
    return decodeFrom(input, maxSide)
}

/** 两条入口的共用实现：降采样读取 → 必要时兜底缩放。 */
private fun decodeFrom(input: ImageInputStream, maxSide: Int): BufferedImage? {
    val limit = maxSide.coerceAtLeast(1)
    try {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) return null
            val sample = decodeSampleFor(max(width, height), limit)
            val param = reader.defaultReadParam
            if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
            val decoded = reader.read(0, param) ?: return null
            return if (max(decoded.width, decoded.height) > limit) {
                downscaleToLimit(decoded, limit)
            } else {
                decoded
            }
        } finally {
            reader.dispose()
        }
    } catch (_: Throwable) {
        // 渲染路径不抛：损坏的内嵌图 / 非图片文件在真实曲库里很常见。
        return null
    } finally {
        runCatching { input.close() }
    }
}

/**
 * 选**降采样因子**：让解码后的长边落在 `[0.75×limit, limit]` 之间。
 *
 * - 太小（结果远小于上限）⇒ 白丢细节；
 * - 太大（结果略超上限）⇒ 兜底再缩一次，仍然只多一张"接近上限"的图（可接受）。
 *
 * 例（limit = 1024）：
 *
 * | 原图长边 | 因子 | 解码后 |
 * |---|---|---|
 * | 4000 | 4 | 1000 |
 * | 3000 | 3 | 1000 |
 * | 2000 | 2 | 1000 |
 * | 1600 | 2 | 800 |
 * | 1500 | 1（2 会掉到 750 < 768） | 1500 → 兜底缩到 1024 |
 * | 1024 | 1 | 1024 |
 *
 * ★ 公开是为了让探针能**逐值断言**这张表（`CoverScalerProbe` §7）。
 */
fun decodeSampleFor(longSide: Int, limit: Int): Int {
    val safeLimit = limit.coerceAtLeast(1)
    if (longSide <= safeLimit) return 1
    val ceilFactor = (longSide + safeLimit - 1) / safeLimit
    // 0.75 的下限：低于它说明这一档降采样丢得太多，宁可全解再缩。
    return if (longSide / ceilFactor >= (safeLimit * 3) / 4) ceilFactor else 1
}

/** 兜底：把已解码的图缩到长边 ≤ [limit]（双线性，ARGB 输出）。 */
private fun downscaleToLimit(src: BufferedImage, limit: Int): BufferedImage {
    val scale = limit.toDouble() / max(src.width, src.height)
    val width = max(1, (src.width * scale).roundToInt())
    val height = max(1, (src.height * scale).roundToInt())
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(src, 0, 0, width, height, null)
    } finally {
        g.dispose()
    }
    return out
}
