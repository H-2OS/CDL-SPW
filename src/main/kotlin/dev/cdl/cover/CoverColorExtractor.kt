// SPDX-License-Identifier: GPL-3.0-only
// Ported in part from Dynamic Lyrics Island for SPW by GaBoron (GPL-3.0-only); see NOTICE.
package dev.cdl.cover

import dev.cdl.settings.composePackedArgb
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * 从封面图里提取**主色**（打包 **ARGB**，`0xFFRRGGBB` —— **alpha 恒为不透明**）。对应三个 `*_color_mode` 设置项的
 * 「跟随封面取色」那一档。
 *
 * ## 为什么不能用「平均色」
 *
 * 平均色是把**全图所有像素**的 RGB 各求一次算术平均。它在数学上"代表"整幅图，
 * 但在视觉上**几乎必然发灰**：一张红蓝各半的图会平均成紫色，一张有白色文字的
 * 深色封面会被文字拉亮成灰。原因是平均发生在**颜色空间**里，而人眼对颜色的
 * 感知是**簇**（cluster）式的 —— 我们要的是"这张图看起来是什么颜色"，
 * 而不是"这张图所有像素的重心在哪"。
 *
 * 所以本类的做法是**先分簇、再取最大簇的均值**：簇内颜色相近，均值不会发灰；
 * 而"最大簇"就是人眼认定的主色。
 *
 * ## 分簇规则（为什么分成两类、各分几档）
 *
 * ① **彩色簇**：按色相分 [HUE_BUCKETS] 档。色相是环形的，直接按 `hue * N` 取整
 *    会把**红色**劈到 0 与 N-1 两档（红的色相在 0 与 1 两端）—— 一张纯红封面
 *    会被拆成两个各占一半的簇。故先加半档再取模（见 [hueBucket]），让红合成一簇。
 * ② **中性簇**：黑白灰没有色相，只能按**亮度**分 [NEUTRAL_BUCKETS] 档。
 *    ⚠️ **这一条是本类满足"半黑半白图不能返回灰"的关键**：若把所有中性色
 *    塞进**一个**簇，半黑半白的图会得到 `(0 + 255) / 2 = 127` 的**中灰** ——
 *    正是"平均色发灰"的另一种表现。按亮度分档后，黑与白各成一簇，
 *    取最大簇（并列时取靠前者，即暗簇）得到的是**近黑**，而不是灰。
 *
 * ## 采样而不是全图遍历
 *
 * 提取发生在**渲染路径**上（每帧问一次"现在该用什么颜色"），而一张封面可能有
 * 1000×1000 甚至更大。全图遍历是百万次 `getRGB` + 百万次 `RGBtoHSB`，
 * 单帧就是几十毫秒 —— 直接把 30 fps 打成个位数。
 *
 * 故按**固定网格**采样（最多约 [SAMPLE_GRID]² 个点）。主色是**统计量**，
 * 均匀采样足以估计它：真实封面的主色区域远大于 1/1024 的像素占比。
 * 采样步长由图片尺寸推导，**大图与小图采到的点数同级**（小图退化为全遍历）。
 *
 * ## 与原实现 0.8.9 的关系
 *
 * 原实现 `platform/CoverColorExtractor.kt` 走的是「色相家族累加 → 取最大家族均值」
 * 的路线，本类**采用同一族思路**（分簇而非平均），但分簇方式不同：
 * 原实现把中性色混在一个"neutral"累加器里，且**兜底是全图平均** ——
 * 于是半黑半白的图在原实现会得到中灰。本类把中性色按亮度分档、
 * 且**不做全图平均兜底**（宁可返回 null 让调用方回退手动色），
 * 正是为了消除那个灰。差异清单见 `NOTICE`。
 *
 * ## 契约
 *
 * 任何失败都返回 `null`，不抛异常 —— 与 [CoverArtProvider] 同一条纪律。
 * `null` 的语义是"这张图没有可用的主色"，调用方应当**回退到手动颜色**。
 *
 * ## 为什么是 `object` 而不是 `internal object`（与项目既有取舍一致）
 *
 * 本类的全部价值在于"分簇规则**真的**能避开平均色的灰"—— 那是一条**统计性质**的
 * 断言，靠读代码审查不出来（"看起来对"与"跑起来对"是两回事），必须由离线探针
 * 用自造图片直接验证（`src/probe/kotlin/CoverPaletteProbe.kt`）。
 * 而探针是**独立的 Kotlin 编译单元**，看不到 `internal` 声明。
 *
 * 这与 `window/HoverState`、`window/PlaybackControls` 被 `ControlsProbe` 直接验证
 * 是**同一个取舍**（它们同样是 public）—— 项目对"需要被探针验证的纯计算类"
 * 一律公开，而不是为了可见性去改构建脚本的 friend 关系。
 */
object CoverColorExtractor {

    /**
     * 采样网格边长。采样点数约为它的平方（[SAMPLE_GRID]² = 1024）。
     *
     * 取 32 的依据：主色区域在真实封面上通常占 10% 以上，而 1024 个均匀采样点
     * 下"占 10% 的区域"平均会被采到约 100 次 —— 统计上足够稳定（不是个位数样本）。
     * 同时 1024 次 HSB 转换的量级是**几十微秒**，对每帧 16.6 ms 的预算无感。
     */
    private const val SAMPLE_GRID = 32

    /** 彩色簇数（色相分档）。12 档 = 每档 30°，与人眼对色相的粗略分辨力同量级。 */
    private const val HUE_BUCKETS = 12

    /**
     * 中性簇数（亮度分档）。取 4 档（暗 / 中暗 / 中亮 / 亮）。
     *
     * 档数不能取 1（会把黑白平均成灰，见类 KDoc），也不必取更多 ——
     * 中性色之间没有色相差异，唯一的区分维度是亮度，4 档足以把
     * "近黑 / 深灰 / 浅灰 / 近白"分开。
     */
    private const val NEUTRAL_BUCKETS = 4

    /** 低于此 alpha 的像素视为透明，不参与统计（PNG 封面常有透明边）。 */
    private const val ALPHA_THRESHOLD = 128

    /** 低于此饱和度的颜色视为中性色（没有可用的色相）。 */
    private const val CHROMA_MIN_SATURATION = 0.12f

    /** 低于此明度的颜色视为近黑 —— 它的色相没有意义（黑可以是任何色相）。 */
    private const val NEAR_BLACK_VALUE = 0.08f

    /** 高于此明度**且**低饱和的颜色视为近白，理由同上。 */
    private const val NEAR_WHITE_VALUE = 0.92f

    /**
     * 彩色簇胜出的**最小占比**（占非透明像素的比例）。
     *
     * 为什么需要它：一张近乎黑白、只在角落有一个红色 logo 的封面，
     * 那个 logo 可能只占 1% 的像素。若无阈值，它会"胜出"成为主色 ——
     * 而人眼看这张图是黑白的。阈值让这种情况落回中性簇（即真实的黑白灰）。
     *
     * 取 2%：低于它时"主色"已经小到不构成视觉印象；高于它的彩色区域
     * 在任何正常封面尺寸下都是显眼的色块。
     */
    private const val MIN_CHROMA_SHARE = 0.02f

    /**
     * 提取主色。
     *
     * @param image 封面图。**不会被修改**（只读 `getRGB`）
     * @return 打包 **ARGB**（`0xFFRRGGBB`，**alpha 恒为不透明** —— 见 [rgb] 的 KDoc）；没有可用像素时返回 `null`
     */
    fun dominant(image: BufferedImage): Int? = palette(image, 1).firstOrNull()

    /**
     * ★★ **从封面取「多色」**（2026-10-02 新增）—— 台账 §3.4 **⑰「封面多色流光」** 的**地基**。
     *
     * ## 为什么这条是"低风险改动"（台账 §B.3 的原话）
     *
     * 多色的**原料早就算出来了**：[dominant] 内部本来就建了
     * `Array(HUE_BUCKETS = 12)`（色相簇）+ `Array(NEUTRAL_BUCKETS = 4)`（中性簇），
     * 每簇带 `count` 与三通道和 —— **但只返回了"最大簇的均值"**。
     * ⇒ ★ 本函数只是**把同一个累加结果多导出几个**，**不新增任何采样或计算**。
     *
     * ## ★★★ 三条契约**原样保留**（`CoverPaletteProbe` 已钉，改错必红）
     *
     * | # | 契约 | 为什么它重要 |
     * |---|---|---|
     * | ① | **`MIN_CHROMA_SHARE` 门限**（彩色簇占比 < 2% ⇒ 不算数） | 它保证"封面只有一小块彩色"时**不会**把那块当成主色 |
     * | ② | ★ **并列取靠前** | 半黑半白图的暗簇与亮簇并列 ⇒ 靠前的是**暗簇** ⇒ 结果是"近黑"而不是"灰"（见类 KDoc） |
     * | ③ | ★ **不做全图平均兜底** | 原实现 0.8.9 有那一步，而它正是"半黑半白 → 中灰"的来源 |
     *
     * ## ★★ 为什么排序必须**稳定**（`thenBy { 索引 }`）
     *
     * 契约②的"并列取靠前"**依赖排序的稳定性**：`maxByOrNull` 在并列时返回**靠前**的元素，
     * 而 `sortedByDescending` **不保证**并列时的相对顺序。
     * ⇒ ★ 必须显式 `thenBy { 它在原数组里的下标 }` —— 否则 `dominant` 的
     * "半黑半白 → 近黑"这条行为会**随机漂移**（同一张图两次调用可能不同色）。
     *
     * ## 取色顺序（★ 彩色优先，中性兜底）
     *
     * 1. **过门限的彩色簇**，按 `count` 降序（并列按下标升序）；
     * 2. 不足 [n] 个时，**用中性簇**按同一规则补足；
     * 3. 仍不足 ⇒ 返回**短列表**（不补 null、不重复）—— 调用方自己处理"色不够"。
     *
     * ★ `n = 1` 时逐像素等价于改动前的 [dominant]（由 `CoverPaletteProbe` 钉住）。
     *
     * @param n 要取几个色。`<= 0` ⇒ 返回空列表。超过实际簇数 ⇒ 返回实际那么多。
     * @return 打包 ARGB 列表（`0xFFRRGGBB`，**alpha 恒不透明**，与 [dominant] 同口径）。
     *   没有可用像素时返回**空列表**。
     */
    fun palette(image: BufferedImage, n: Int): List<Int> {
        if (n <= 0) return emptyList()
        if (image.width <= 0 || image.height <= 0) return emptyList()

        val counted = accumulate(image) ?: return emptyList()
        val (chroma, neutral, opaque) = counted

        // ① 彩色簇：先过滤门限，再**稳定**降序（★ 见 KDoc 契约②）。
        //    ⚠️ `filter` 保留原下标顺序 ⇒ 后面 `thenBy` 才成立。
        val chromaRanked = chroma.withIndex()
            .filter { (_, acc) -> acc.count >= opaque * MIN_CHROMA_SHARE }
            .sortedWith(compareByDescending<IndexedValue<Accumulator>> { it.value.count }.thenBy { it.index })
            .map { it.value.rgb() }

        if (chromaRanked.size >= n) return chromaRanked.take(n)

        // ② 中性簇兜底（黑白灰封面：彩色门限为 0 时的正常情况）。
        val neutralRanked = neutral.withIndex()
            .filter { (_, acc) -> acc.count > 0 }
            .sortedWith(compareByDescending<IndexedValue<Accumulator>> { it.value.count }.thenBy { it.index })
            .map { it.value.rgb() }

        // ③ 仍不足 ⇒ 返回短列表（不补、不重复）。
        return chromaRanked + neutralRanked.take(n - chromaRanked.size)
    }

    /**
     * 采样 + 分簇累加 —— [dominant] 与 [palette] 的**唯一**共用实现。
     *
     * ★ 抽出来的理由：两条路必须走**同一份**累加逻辑，否则"多色"与"主色"
     * 会在门限 / 分桶 / 采样步长上**各自漂移**（本项目最忌讳的形态）。
     *
     * @return `(色相簇, 中性簇, 不透明像素数)`；**无可用像素**时返回 `null`。
     */
    private fun accumulate(image: BufferedImage): Triple<Array<Accumulator>, Array<Accumulator>, Int>? {
        // 采样步长：向上取整到"至少 1"，保证小图退化为全遍历、大图不超采样网格。
        val stepX = ((image.width + SAMPLE_GRID - 1) / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = ((image.height + SAMPLE_GRID - 1) / SAMPLE_GRID).coerceAtLeast(1)

        val chroma = Array(HUE_BUCKETS) { Accumulator() }
        val neutral = Array(NEUTRAL_BUCKETS) { Accumulator() }
        var opaque = 0
        val hsb = FloatArray(3)

        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val argb = image.getRGB(x, y)
                if (((argb ushr 24) and 0xFF) >= ALPHA_THRESHOLD) {
                    opaque++
                    val red = (argb shr 16) and 0xFF
                    val green = (argb shr 8) and 0xFF
                    val blue = argb and 0xFF
                    Color.RGBtoHSB(red, green, blue, hsb)
                    val saturation = hsb[1]
                    val brightness = hsb[2]
                    val nearBlack = brightness < NEAR_BLACK_VALUE
                    val nearWhite = brightness > NEAR_WHITE_VALUE && saturation < CHROMA_MIN_SATURATION
                    if (!nearBlack && !nearWhite && saturation >= CHROMA_MIN_SATURATION) {
                        chroma[hueBucket(hsb[0])].add(red, green, blue)
                    } else {
                        neutral[brightnessBucket(brightness)].add(red, green, blue)
                    }
                }
                x += stepX
            }
            y += stepY
        }

        if (opaque == 0) return null
        return Triple(chroma, neutral, opaque)
    }

    /**
     * ★ **改动前的 [dominant] 本体**（2026-10-02 保留作**等价性取证**）。
     *
     * ⚠️ **不是生产路径** —— 生产走 `dominant` → `palette(image, 1)`。
     * 保留它只有一个用途：探针可以**同时**调用新旧两条路，断言二者对同一张图
     * **返回同一个值**（这才是"逐像素等价"的可证伪形式；只断言"新值等于某个常量"
     * 证不了"没改行为"）。
     *
     * ★ 若将来确认无需取证，可删（但那时要同步删掉 `CoverPaletteProbe` 里对应的断言）。
     */
    fun dominantLegacyForProbe(image: BufferedImage): Int? {
        if (image.width <= 0 || image.height <= 0) return null
        val counted = accumulate(image) ?: return null
        val (chroma, neutral, opaque) = counted

        // ① 彩色簇优先（阈值见 MIN_CHROMA_SHARE）。
        //
        // `maxByOrNull` 在**并列**时返回靠前的元素 —— 这个细节在这里是有意义的：
        // 半黑半白图的暗簇与亮簇并列，靠前的是暗簇（见 [brightnessBucket] 的升序约定），
        // 于是结果是"近黑"而不是"灰"。见类 KDoc。
        val bestChroma = chroma.maxByOrNull { it.count }
        if (bestChroma != null && bestChroma.count >= opaque * MIN_CHROMA_SHARE) {
            return bestChroma.rgb()
        }

        // ② 退回中性簇（黑白灰封面：彩色阈值为 0 时的正常情况）。
        val bestNeutral = neutral.maxByOrNull { it.count }
        if (bestNeutral != null && bestNeutral.count > 0) return bestNeutral.rgb()

        // ③ 什么都不够格 → null（调用方回退手动色）。
        //
        // ⚠️ 这里**刻意不做"全图平均"兜底**。原实现 0.8.9 有那一步，
        // 而它正是"半黑半白 → 中灰"的来源（见类 KDoc 与 NOTICE 的差异清单）。
        // 返回 null 让调用方用手动色，比返回一个灰更符合用户预期。
        return null
    }

    /**
     * 色相 → 簇下标。
     *
     * 加半档再取模是为了让**红色**（色相横跨 0 与 1）落进**同一个**簇：
     * 纯红的色相是 0，偏一点的红是 0.99，两者相差 `* 12` 后是 0 与 11.88 ——
     * 一个落到簇 0、一个落到簇 11，纯红封面会被劈成两半。
     * 加 0.5 后 0.99 → 12.38 → 取整 12 → 取模 12 = 0，与纯红同簇。
     */
    private fun hueBucket(hue: Float): Int =
        ((hue * HUE_BUCKETS + 0.5f).toInt() % HUE_BUCKETS + HUE_BUCKETS) % HUE_BUCKETS

    /**
     * 亮度 → 中性簇下标（升序：0 = 最暗，[NEUTRAL_BUCKETS]-1 = 最亮）。
     *
     * ⚠️ **升序是刻意的**，不是随手写的：并列时 `maxByOrNull` 取靠前者，
     * 于是"半黑半白"这类**明暗各半**的图会落到**暗簇** ——
     * 一个深色强调色，而不是中灰。见类 KDoc。
     */
    private fun brightnessBucket(brightness: Float): Int =
        (brightness * NEUTRAL_BUCKETS).toInt().coerceIn(0, NEUTRAL_BUCKETS - 1)

    /**
     * 一个簇的累加器：像素数 + 三通道之和。
     *
     * 用 `Long` 累加：一张 8000×8000 的极端图在步长 1 下有 6400 万像素，
     * `Int` 的三通道和会溢出（255 × 6400 万 ≈ 1.6e10 > `Int.MAX_VALUE`）。
     * 溢出会让均值变成负数，进而被 `Color` 构造抛 `IllegalArgumentException` ——
     * 属于"只在超大图上炸"的隐蔽缺陷，故从类型上直接堵掉。
     */
    private class Accumulator {
        var count: Int = 0
            private set
        private var red = 0L
        private var green = 0L
        private var blue = 0L

        fun add(red: Int, green: Int, blue: Int) {
            count++
            this.red += red
            this.green += green
            this.blue += blue
        }

        /**
         * 簇内均值（**打包 ARGB**，alpha 恒为不透明）。**只在 [count] > 0 时调用**。
         *
         * ⚠️ 2026-09-19：**补上 alpha 位**（"一并修"的一部分）。
         *
         * **当前无害、但是陷阱**：`coverRgb` 的消费点全部经 `CoverPalette`
         * （`brightFrom` / `darkFrom` 走 `hsbOf`，**只取 16/8/0 三个通道**，
         * 且返回 `Color.getHSBColor(...)` 即不透明色）—— 所以缺 alpha 目前不影响观感。
         *
         * 但只要将来有**任何一个**消费点写成 `Color(coverRgb)`（这是最自然的写法），
         * 就会得到**全透明色** —— 而"跟随封面取色失效"这个症状我们已经查过两轮。
         * 与其留一颗定时炸弹，不如现在把契约钉死：**`coverRgb` 是一个颜色，不是一个三元组。**
         */
        fun rgb(): Int = composePackedArgb(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt(),
        )
    }
}
