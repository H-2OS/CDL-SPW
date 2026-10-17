// SPDX-License-Identifier: AGPL-3.0-only

/**
 * **字间距（letter-spacing）** —— 2026-09-19 新增设置项
 * `main_lyric.letter_spacing_tenth_px` 的几何实现。
 *
 * ## 为什么单独成文件（而不是留在 `IslandWindow` 里）
 *
 * 这段逻辑有两个**必须被断言**的性质，而它们都不是"画出来看看"能验的：
 *
 * 1. **单位换算**：设置项是"十分之一像素"，要变成 `TextAttribute.TRACKING`
 *    所要求的"**占字号的比例**"。换算错了（比如漏掉 `/10`、或忘记除以字号）
 *    在视觉上只是"字距不太对"，不会有任何报错。
 * 2. **命中域跟着走**：字距一旦改变 `TextLayout.advance`，而命中判定读的正是
 *    `advance`（见 [GlyphHitTester]），那么"画在哪"与"点在哪"必须**同步**改变。
 *    若某处命中路径自己用 `FontMetrics` 算了一遍宽度，字距越大错得越离谱。
 *
 * 这两条都需要探针能调到**生产代码本身**（而不是在探针里抄一份换算），
 * 故这里按本项目既有做法（见 `SpectrumGeometry.kt`）把它写成**公开纯函数**：
 * 公开 ⇒ 探针可断言；纯函数 ⇒ 同一输入必得同一输出，断言才有意义。
 *
 * ## 为什么不"逐字符 drawString + 手工累加 x"
 *
 * `TextLayout` **没有**字间距参数，但字体属性表里有 [TextAttribute.TRACKING]。
 * 把它加在**字体**上，`TextLayout` 的 `advance`、每个 caret 的位置、`getOutline`
 * 的墨迹**全部**按新字距重算 —— 于是绘制与命中共用同一份几何。
 *
 * 反例（本文件要避开的形态）："逐字符 `drawString` + 手工累加 x" 必须在命中侧
 * **再写一遍**同样的累加，那是第二份排版实现，迟早漂移。
 */

package dev.cdl.render

import dev.cdl.font.HostFonts
import java.awt.Font
import java.awt.font.TextAttribute

/**
 * 把设置项的"十分之一像素"换算成 [TextAttribute.TRACKING] 要的**比例**。
 *
 * ## 单位链（每一环都不能省）
 *
 * ```
 * 设置项 main_lyric.letter_spacing_tenth_px  单位：0.1 px   （注册表 range 0..200）
 *   ↓ ÷ 10                                   单位：px
 * 像素字距
 *   ↓ ÷ 字号                                 单位：无量纲比例
 * TRACKING 的值
 * ```
 *
 * 实测（JDK 21）：字号 22、`tracking = 0.1`、文本 11 字符时
 * `TextLayout.advance` 恰好 **+22.0 px** = `0.1 × 22 × 10` —— 印证了
 * "`TRACKING` 是占字号的比例"以及"每字符各加一份"这两件事。
 *
 * ## 为什么必须除以字号（而不是把像素值直接当 TRACKING）
 *
 * `TRACKING` 是按字号缩放的：同一个 `tracking` 值在 22 pt 与 44 pt 下，
 * 像素字距是 **2 倍**关系。这正是"副歌词按比例联动"能免费实现的原因 ——
 * 副歌词字号 = `fontSize × TRANSLATION_SCALE`，复用同一个 tracking 时
 * 像素字距自动 = `主像素字距 × (副字号 / 主字号)`（见 [fontWithLetterSpacing]）。
 *
 * ## 为什么 `fontSize` 要 `coerceAtLeast(1)`
 *
 * 注册表把 `main_lyric.font.size` 的 range 定为 `8..200`，正常路径不可能为 0。
 * 但除零会让 `TRACKING` 变成 `Infinity`，而 `Infinity` 会让 `TextLayout`
 * 的构造**直接抛异常**（绘制路径抛异常 = 词岛白屏）。这是一行防御，
 * 换掉一类"只在畸形配置下出现"的崩溃。
 *
 * @param letterSpacingTenthPx 设置项的原始值，单位 0.1 px（`0` = 不加字距）。
 * @param fontSize 该行文字的**像素/磅值字号**（主歌词是 `main_lyric.font.size`）。
 * @return `TRACKING` 比例值；`letterSpacingTenthPx <= 0` 时返回 `0f`。
 */
fun letterSpacingTracking(letterSpacingTenthPx: Int, fontSize: Int): Float =
    (letterSpacingTenthPx / 10f) / fontSize.coerceAtLeast(1)

/**
 * 造一个带**字间距**的字体 —— 字体构造的**唯一漏斗**。
 *
 * ## 基准字体：`Font(family, …)`（2026-09-22 起，随包字体已取消）
 *
 * 这里曾经走 `BundledFonts.resolve(...)` —— 那是为**随包分发 MiSans** 接的一条线
 * （`Font("MiSans", …)` 在没装 MiSans 的机器上会静默退化成 `Dialog`，
 * 故需要从 classpath 把包内那份读出来）。用户 2026-09-22 拍板
 * 「**取消自带字体包，调用自带字体包的路线转到在本机选择的字体上**」，
 * 于是这条线整体取消，基准字体回到 `Font(family, …)` —— 这正是"只用本机字体"的语义。
 *
 * `family` 的取值域仍是**本机已注册的族名**（`FontCatalog.fromSystem` 枚举的那些），
 * 出厂默认见 [dev.cdl.font.DEFAULT_FONT_FAMILY]。
 * 族名不认识时 `Font` 构造器自己会退到 `Dialog`，不需要我们额外处理
 * （本项目一贯的"静默回退"语义；见 `FontDefaults.kt` 对"默认值必须本机存在"的说明）。
 *
 * 三处绘制路径（主歌词 / 副歌词 / 无歌词占位行）全部经本函数，故改一处即覆盖全部。
 *
 * ## `tracking <= 0f` 时不派生
 *
 * 保证"字距为 0"这条**默认路径与改动前逐位相同**：不多一层属性表，
 * `Font.equals` 也不会因浮点误差抖动（`RenderCache.setFont` 用它判缓存失效，
 * 而 `Font.equals` **会比较属性表** —— 抖动一次就白重建一遍 `TextLayout`）。
 *
 * ## 为什么不包 `runCatching`
 *
 * `Font(family, style, size)` 对**任何** `family` 字符串都不会抛异常
 * （不认识就退到 `Dialog`），故这里没有需要兜的异常。
 * 包一层 `runCatching` 只会多一层掩盖（真抛了也看不见）。
 *
 * @param family 族名（设置项 `main_lyric.font.family` 的值，须是本机已注册的族名）。
 * @param size 字号。`size <= 0` 时 `TextLayout` 的构造会抛，故原样返回不派生。
 * @param tracking [letterSpacingTracking] 的返回值（占字号的比例）。`0f` = 不加。
 */
fun fontWithLetterSpacing(
    family: String,
    size: Int,
    tracking: Float,
    /**
     * **是否加粗**。对应 `main_lyric.font.weight == "BOLD"`。
     *
     * ★★ **2026-09-29：从"字重倍率 Float"改成"布尔"**（用户指令「改，根治这个bug」）。
     * 原因见 `main_lyric.font.weight` 的 KDoc —— ★ **实测 `TextAttribute.WEIGHT` 只有两态**，
     * 原来的 `weightScale` 在 `1.0–1.99` 区间**完全不生效**。
     *
     * ★★ **为什么用 `Font.BOLD` 而不是 `deriveFont(WEIGHT, 2.0f)`**：
     * 用户提醒「**不同字体可能有不同字重档**」⇒ `Font.BOLD` 会让 **JDK 挑该族最接近的真字重**
     * （多字重字体能挑到真 Bold，单字重字体走算法合成）⇒ **自动适配**。
     *
     * ## 为什么放在这里（本函数是字体的**唯一构造入口**）
     *
     * 全工程的歌词字体都经由本函数产生，故"字重"只在这里接一次，
     * **主歌词 / 副歌词 / 提示行 / 预览**全部自动生效 —— 不需要在 4 个调用点各写一遍
     * （那正是本项目反复吃亏的"同一条规则写 N 遍、漏一处"的形态）。
     *
     * ## 实现要点
     *
     * `TextAttribute.WEIGHT` 是 **0.5..2.75 的连续量**（不是 `Font.BOLD` 那种布尔）。
     * 与 `TRACKING` **必须在同一次 `deriveFont` 里设** —— 分两次调用后者会丢掉前者。
     *
     * ## 能力边界
     *
     * Java2D 会挑字体族里**最接近**的字重；该族只有 Regular 时（很多中文字体如此）
     * 会走**算法合成**（笔画变粗，但不是真正的多字重设计）。
     * 带多字重的字体（如 MiSans / 思源黑体）效果最好 —— 这一点写进了设置项的 `summary`，
     * **不假装它对所有字体都等效**。
     * ⚠️ 2026-09-22：`summary` 原文写的是"**自带的**多字重字体（MiSans）"，
     * 随包字体取消后已改为"多字重字体（如 MiSans / 思源黑体）"—— 这里同步。
     */
    bold: Boolean = false,
): Font {
    // ★★ 2026-09-29 接线宿主内置字体（`recon/66` §0.2 的构造点 ①）。
    //    这是**渲染层唯一漏斗** —— 主歌词 / 副歌词 / 提示行 / 预览共 4 个调用点
    //    全部经由本函数 ⇒ 改这一行，四处全生效。
    //
    //    ★ **"先问解析器、问不到再回原路"** —— 这一个 `?:` 就是全部的接线方式：
    //    非哨兵族名（`Microsoft YaHei UI` / `MiSans` / …）时 `resolveOrNull` 返回
    //    `null` ⇒ 走原来的 `Font(family, PLAIN, size)` ⇒ **既有行为零改变**。
    //
    //    ⚠️ 为什么是"持对象"而不是 `registerFont` + 族名：`recon/65` C2 本机已实测复现
    //      （本机**真的装了** MiSans ⇒ `registerFont` 返回 `false`）⇒ 走族名会**静默地**
    //      拿到**系统那份**，而不是宿主那份。详见 `HostFonts` 文件头 §二。
    // ★★★ 2026-10-07 **BUG-161**：改用**唯一漏斗** `resolveFontOrFallback` ——
    //   原来这里写 `HostFonts.resolveOrNull(family)?.deriveFont(…) ?: Font(family, …)`，
    //   ★ **回退用的是同一个 `family` 字符串** ⇒ 哨兵解析失败时是 `Font("@host:MiSans", …)`
    //   ⇒ **AWT 静默退化成 `Dialog`**，而申报的回退族 `DEFAULT_FONT_FAMILY` 从没被用过。
    //   ★ 现在：哨兵失败 / 空值 ⇒ **回退族**；非哨兵 ⇒ 原路（既有行为零改变）。
    val base = dev.cdl.font.resolveFontOrFallback(family, size)
    // 字重与字距都收在**同一份 attributes** 上，最后一次性 deriveFont ——
    // 分两次 deriveFont 的话，第二次会基于第一次的结果、丢掉第一次设的属性。
    val attrs = mutableMapOf<TextAttribute, Any>()
    if (tracking > 0f && size > 0) attrs[TextAttribute.TRACKING] = tracking
    // ★★ 2026-09-29：**不再写 `TextAttribute.WEIGHT`**（实测它只有两态，写中间值等于没写）。
    //    加粗改走 `Font.BOLD` —— 见 [bold] 的 KDoc（JDK 会挑该族最接近的真字重）。
    val derived = if (attrs.isEmpty()) base else base.deriveFont(attrs)
    // ★ `Font.BOLD` 与 `deriveFont(attrs)` 的顺序：先 BOLD 再套 tracking 属性
    //   （`deriveFont(Map)` **不丢** `style`，两者可叠加）。
    return if (bold) derived.deriveFont(Font.BOLD) else derived
}
