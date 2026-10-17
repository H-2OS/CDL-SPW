// SPDX-License-Identifier: AGPL-3.0-only
//
// ═══════════════════════════════════════════════════════════════════════════
// 整轴（`onLyricsLinesUpdated`）→ 文档 的转换层
// ═══════════════════════════════════════════════════════════════════════════
//
// ## 它解决的是"逐行累积必须靠猜"这件事
//
// 逐行推送时，插件每次只拿到一行，于是不得不基于**上下文**去推断：
// 这行是原文还是译文（`translationPairRole`）、下一行是不是译文的续行
// （`isTranslationContinuation`）、它跟上一段译文对不对得上（`isTranslationPair`）。
// 这些启发式每一条都有已知的失败面 —— 本文件的职责就是**把它们从主干上摘掉**。
//
// ## 为什么摘得掉
//
// `PlaybackExtensionPoint.LyricsLine` 的构造是
// `(startTime, endTime, lyricsCells, pureMainText, pureSubText)` —— **主文本与译文本
// 是同一份对象里的两个字段**，宿主在推送时就已经分好了。逐行路径之所以还要猜，
// 是因为它拿到的其实是**已经拼成一串**的 `pureMainText`
// （真机 86/86 都是 `sub=<null>`，译文被嵌进主串里），不得不靠书写系统边界反切。
//
// ⚠️ **但这里不能假定"整轴就一定带 pureSubText"**。整轴与逐行**是否来自同一个
// 宿主内部实现**，本工程没有取证（没有真机日志）。所以：
//   · `pureSubText` **非空** → **直接信它**（见 `separatedMainText` 的"原记与实为"）；
//   · `pureSubText` 为空 → 退回书写系统边界（与逐行路径**同一套规则**）。
// 这条写法是有意的：**在没取证之前，不押注任何一种宿主行为**，
// 而是让两种行为**都能正确工作**。
//
// ## ⚠️ 逐字轴**不在这里**
//
// 本文件只产文本与时间区间。`words` / `translationWords` 由装配层
// （`CdlRuntime`）用既有的 `narrowWordsToText` / `narrowWordsToTranslation` 裁出 ——
// 那一套的 fail-closed 已被探针钉死，**不复制第二份**。见 `bilingualLinesFrom` 的 `cells` 输出。
package dev.cdl.playback

/**
 * 从**一条**宿主 `LyricsLine` 翻出的纯数据（不含宿主类型，便于探针直接构造）。
 *
 * @param startMs 行起始
 * @param endMs 行结束
 * @param text 原文（已从主串里分离）
 * @param translation 译文；`null` = 这一行没有可单独显示的译文
 * @param cells 逐字单元，**原样**交给装配层去裁（本层不裁）
 */
data class HostLyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String?,
    val cells: List<HostLyricCell>,
)

/**
 * 从**一条**宿主 `LyricsLine` 翻出的逐字单元（纯数据）。
 *
 * ⚠️ 字段与宿主 `LyricsLine.Cell` **一一对应，刻意不合并/不排序**：
 * 顺序本身是证据（`checkedWords` 要求 `startMs` 单调不减），
 * 在这里重排等于替宿主"修正"数据，会把"数据坏了"变成"看起来是好的"。
 */
data class HostLyricCell(val startMs: Long, val endMs: Long, val text: String)

/**
 * 把整轴翻成纯数据列表。
 *
 * ## 过滤规则（每条都对应一个真机见过的现象）
 *
 * | 情形 | 处理 | 理由 |
 * |---|---|---|
 * | `text` 空 / 全空白 | **丢** | 过门间隙；与 `PlaybackTimeline.lineChanged` 的 `isBlank` 守卫**同一条规则** |
 * | 宿主提示行（`纯音乐，请欣赏` 等） | **丢** | 与逐行路径**同一个判据** [isHostPlaceholderLine]，见其 KDoc |
 * | `endMs < startMs` | **丢** | 区间倒置的行在任何时序计算里都会算出负进度；丢了只是"少一句"，留着会让高亮倒退 |
 * | 与已有行**完全同键**（`startMs`+`endMs`+`text`） | **丢** | 整轴也可能重复；不去重会在 `lineAt` 上产生"同一句占两个下标"，seek 时来回跳 |
 *
 * ## 为什么**不**排序
 *
 * 宿主给的整轴按定义是时间序的。若这里再 `sortedBy`，**一旦宿主给的确实是乱序**，
 * 我们就把它"修好了"—— 于是"宿主乱序"这个缺陷永远不会被发现，而排序本身
 * 也无法消除重叠区间带来的问题。**保留原顺序 + 不假设有序**：
 * [lineAt]（二分）对乱序的处理见其 KDoc。
 *
 * @return 已过滤、未排序的列表；空列表表示"这一轴里没有可显示的行"
 */
fun bilingualLinesFrom(lines: List<HostLyricLine>): List<HostLyricLine> {
    val out = ArrayList<HostLyricLine>(lines.size)
    val seen = HashSet<Triple<Long, Long, String>>(lines.size * 2)
    for (line in lines) {
        val mainText = separatedMainText(line.text, line.translation)
        // 空行 / 提示行：与 `PlaybackTimeline.lineChanged` 完全相同的两条守卫。
        if (mainText.isBlank() || isHostPlaceholderLine(mainText)) continue
        if (line.endMs < line.startMs) continue
        if (!seen.add(Triple(line.startMs, line.endMs, mainText))) continue
        out.add(
            HostLyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
                text = mainText,
                translation = separatedTranslation(line.text, line.translation),
                cells = line.cells,
            )
        )
    }
    return out
}

/**
 * 分离原文与译文 —— **整轴路径必须用这一份，不能直接调 `splitBilingualLine`。**
 *
 * ## 原记（本文件初版）与实为
 *
 * **原记**：整轴路径「一律仍然调用 `splitBilingualLine`」，理由是不押注
 * "宿主整轴一定带 `pureSubText`"。
 *
 * **实为**（2026-10-18，`HostLyricAxisProbe` §3.1 **红灯**取证）：
 * `splitBilingualLine(main="Hello world", sub="你好世界")` 返回的译文是 **null** ——
 * 因为该函数只有两条路，而这个输入**两条都不满足**：
 *   ① 后缀剥离：译文必须是主串的**尾部子串**，而 `"Hello world"` 的尾部不是中文；
 *   ② 书写系统切分：它**只在主串内部找分隔空格**，压根不看 `sub` 参数。
 * ⇒ 结果就是**译文凭空丢失**，而 `LyricsLine.pureSubText` 恰恰是宿主分好的译文，
 *   这是整轴路径相对逐行路径**唯一的真实优势**，丢了它等于白接。
 *
 * ## 修法：**有 `pureSubText` 就直接信它**（优先于后缀剥离）
 *
 * 判据很硬：`pureSubText` 非空（trim 后）时，它就是这一行的译文 ——
 * 这是**数据本身**说的，不需要任何启发式。只有它为 null / 空白时，
 * 才退回 [splitBilingualLine]（那才是"译文嵌在主串里"的场合）。
 *
 * ⚠️ 为什么这也修好了**逐行**路径（`splitBilingualLine` 的行为对两条路径是同一个）：
 *   逐行路径上若宿主真给了 `pureSubText`，旧行为同样是 **丢弃译文**。
 *   真机 86/86 都是 `sub=<null>`，所以这个缺陷**一直没被观察到** ——
 *   属于"条件不满足所以从没跑到"的那类死代码缺陷，不是"从没写过"。
 *
 * ⚠️ **只信"非空"**：`pureSubText = ""` 的语义是"这一行没有译文"，
 *   不是"译文是空串"。若把它当译文，渲染层会为它预留一行副歌词高度。
 */
fun separatedMainText(mainText: String?, subText: String?): String {
    val sub = subText?.trim().orEmpty()
    // 有宿主给的真译文 → 主串就是原文（不再做后缀剥离：那是"译文被拼进主串"的补救手段）。
    if (sub.isNotEmpty()) return mainText.orEmpty().trimEnd()
    return splitBilingualLine(mainText, null).first
}

/** [separatedMainText] 的另一半。两者**必须成对调用**，否则会返回互不匹配原文/译文。 */
fun separatedTranslation(mainText: String?, subText: String?): String? {
    val sub = subText?.trim().orEmpty()
    if (sub.isNotEmpty()) return sub
    return splitBilingualLine(mainText, null).second
}
