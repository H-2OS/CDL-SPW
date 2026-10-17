// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

/**
 * 逐字（卡拉 OK）时间轴的最小单元。
 *
 * 为什么需要它、而不是只保留整行时间：整行时间只能回答"这一句唱到哪了"，
 * 无法回答"这一句里唱到哪个字了"。词岛的逐字高亮、以及"未唱到的字以低亮度显示"
 * 这类效果，都要求亚行级的时间信息。
 *
 * 数据来源（已取证）：宿主 `PlaybackExtensionPoint.LyricsLine.Cell`，
 * 其确切字段为 `private final long startTime / endTime / String text`。
 */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    /**
     * 该字在 [positionMs] 时刻的演唱进度，0f..1f。
     *
     * 为什么退化区间（`endMs <= startMs`）返回 1f 而不是 0f：
     * 原地不动的字（如某些伴奏里的装饰性标记）时间区间是零宽或反序的。
     * 若返回 0f，它会**永远**显示为"未唱"，包括在它之后的所有时间点，
     * 表现为一个字永久暗着 —— 那是明显的视觉 bug。返回 1f 表示"已完成"，
     * 至少与"它已经被唱过"这一事实一致。同时该分支也顺带避免了除零。
     */
    fun progress(positionMs: Long): Float = when {
        positionMs < startMs -> 0f
        endMs <= startMs -> 1f
        else -> ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }
}

/**
 * 从宿主给出的「主文本」里剥掉译文，返回 `(原文, 译文)`。**BUG-015 的修复核心。**
 *
 * ## 为什么需要剥离（这是实测出来的，不是假设）
 *
 * **原记**（本函数 KDoc 初版）：「宿主 `pureMainText` **不含**译文 ——
 * 它就是 `lyricsCells` 各 `text` 的拼接，译文另由 `pureSubText` 独立承载。」
 *
 * **实为**：`pureMainText` **确实**是 `lyricsCells` 各 `text` 的拼接
 * （取证：`spw-re/decompiled/com/xuncorp/spc/lyrics/ԫ.java:41-49` 的 `pureMainText`
 * 由 cells 现场拼接得到；`javap` 实测 API 类为
 * `(long startTime, long endTime, List<Cell> lyricsCells, String pureMainText, String pureSubText)`
 * 五字段 ABI）—— **但由此推不出"cells 里没有译文"**。
 * 宿主推什么就拼什么：**若歌词源把译文并进了正文，cells 与 `pureMainText` 里就都有译文**
 * （取证：2026-09-17 真机日志 `super-island.log`，86 条 `callback line:` 全量统计
 * `sub=<null>` 86 次 / 非 null 0 次，而 `main` 形如
 * `そんな日はもうたくさんだ 这样的时光我受够了`）。
 * 这条推论错误的后果是：只实现了「`pureSubText` 是后缀 → 剥掉」一条路，
 * 而真机 `pureSubText` 恒为 null，于是译文行永不绘制、原文行的高亮把译文一起染上。
 *
 * 故剥离有**两条路**，本函数只负责第一条（有数据依据的那条），
 * 第二条（按书写系统启发式切分）见 `BilingualLineSplitter.kt` 的 `splitBilingualLine`。
 *
 * ## 判定规则与它为什么是**保守**的
 *
 * 唯一被接受的剥离条件是：**译文（trim 后）是主串的严格后缀，且剥下的原文非空**。
 * 只认后缀而不认"出现在任意位置"，是因为前缀/中缀匹配无法区分
 * 「英文原文里本来就有的空格」与「原文/译文的分界」—— 两者都表现为空格，
 * 没有额外信息可依。后缀匹配则是**由数据本身**（`pureSubText` 是尾部那段）给出的，
 * 不需要猜测。
 *
 * 任何一条不满足就**整体放弃剥离**（返回原文=整串、译文=null）。
 * 这不是"保守求稳"，而是**正确性方向**：错误剥离会让歌词凭空少一段（信息丢失），
 * 而不剥离最多是"译文跟着原文一起高亮"（观感退步）。两害相权，退观感。
 *
 * 同理，剥完为空（主串与译文全等）时也放弃 —— **绝不让歌词变空**。
 *
 * @param mainText 宿主 `pureMainText`（可能已含译文，可能为 null）
 * @param subText 宿主 `pureSubText`（译文；可能为 null 或空白）
 * @return 原文与译文。译文为 null 表示"没有可单独显示的译文"
 */
fun splitMainAndTranslation(mainText: String?, subText: String?): Pair<String, String?> {
    val main = mainText.orEmpty()
    val sub = subText?.trim().orEmpty()
    // 译文缺失或为空白 → 没有可分离的东西。整串就是原文。
    if (sub.isEmpty()) return main to null
    // 译文必须是主串的后缀，且主串去掉它之后仍有内容 —— 两个条件都不满足时放弃剥离。
    if (!main.endsWith(sub)) return main to null
    val stripped = main.dropLast(sub.length).trimEnd()
    if (stripped.isEmpty()) return main to null
    return stripped to sub
}

/**
 * 把逐字 [words] 裁剪到 [text] 的长度，使裁剪结果**精确拼接成 [text]**。
 *
 * ## 为什么必须裁剪（否则逐字会静默失效）
 *
 * [LyricLine.effectiveWords] 的第一条校验是"各字拼接必须精确等于 `text`"，
 * 不过就整行放弃逐字。而宿主给的 cells 拼出来的是 `pureMainText`
 * （可能含译文），`text` 却是剥离后的纯原文 —— 两者必然不等。
 *
 * 因此必须把 cells 裁到原文长度。**这也不是"能省就省"的优化**：
 * 不裁剪 = 逐字高亮彻底不工作（fail-closed），且不会有任何报错。
 *
 * ## 裁剪规则
 *
 * 按 cells 的原始顺序累加字符数，取前 `len(text)` 个字符所属的 cells：
 * - 完整落在区间内的 cell 原样保留（时间区间不变）；
 * - 跨越边界的那个 cell **截短文本**，但**保留原时间区间** ——
 *   它的时间信息仍然描述"这个字在唱"，丢掉反而会让进度错位；
 * - 边界之后（即属于译文）的 cells **整体舍弃**，它们的时间属于译文，
 *   不能参与原文的逐字进度。
 *
 * 任一环节对不上（cells 总长不足以覆盖 `text`、或拼接后仍不等）→ 返回**空列表**，
 * 让 `effectiveWords` 正常 fail-closed 退回整行进度。
 * 返回"大致对得上"的结果比返回空更危险：那会产生**错位的高亮**。
 *
 * @param words 宿主 cells 转成的逐字列表
 * @param text 目标文本（已是剥离译文后的纯原文）
 */
fun narrowWordsToText(words: List<LyricWord>, text: String): List<LyricWord> {
    if (text.isEmpty() || words.isEmpty()) return emptyList()
    val total = words.sumOf { it.text.length }
    // 不足以覆盖目标文本 → 无法裁剪出精确结果。
    if (total < text.length) return emptyList()

    val out = ArrayList<LyricWord>(words.size)
    var consumed = 0
    for (word in words) {
        if (consumed >= text.length) break
        val remaining = text.length - consumed
        if (word.text.length <= remaining) {
            // 整个 cell 都落在原文区间内。
            out.add(word)
            consumed += word.text.length
        } else {
            // 跨越边界：截短文本，保留时间区间（见 KDoc）。
            out.add(word.copy(text = word.text.take(remaining)))
            consumed = text.length
        }
    }
    // 最后一道防线：拼出来必须精确等于 text。不等就放弃逐字，绝不交出错位的结果。
    if (out.joinToString("") { it.text } != text) return emptyList()
    return out
}

/**
 * 从同一份逐字 [words] 里取出**属于译文**的那一段 —— 译文的独立逐字时间轴。
 *
 * ## 为什么会有"译文的逐字"可用（这直接回答了用户诉求）
 *
 * 用户的诉求是「原文和译文**分别**进行逐字高亮」。译文能不能逐字，
 * 只取决于一件事：**宿主给的 cells 有没有覆盖到译文那一段**。
 *
 * 由于 `pureMainText` 就是 `lyricsCells` 的拼接（见 [splitMainAndTranslation] 的
 * 「原记 / 实为」），**译文既然出现在 `pureMainText` 里，它就必然也在 cells 里** ——
 * 于是"cells 是否覆盖译文"等价于"cells 有没有亚行粒度"：
 *
 * | cells 形态 | 原文侧 | 译文侧 |
 * |---|---|---|
 * | `cells=1` 覆盖含译文的整串（真机 86/86） | 无亚行信息 → 整行进度 | 无亚行信息 → 整行进度 |
 * | 逐字 cells（每字一个，覆盖整串） | 用自己的 cells 逐字 | **用尾部那段 cells 逐字** |
 *
 * 故本函数是"译文逐字"的**唯一数据来源**，而它**绝不借用原文的时间轴** ——
 * 借来的时间轴会按原文的字数切译文，把高亮落在错误的字上（中译日译文字数、语序都不同）。
 *
 * ## 判定规则（与 [narrowWordsToText] 有意不同）
 *
 * 只接受**分界点恰好落在 cell 边界**的情形：
 *
 * 1. 拼接串以 `mainText` 开头、以 `translationText` 结尾（否则 cells 与文本对不上）；
 * 2. 分界点 `start = 拼接串长度 − 译文长度` 必须 `>= mainText.length`
 *    （原文与译文之间的半角空格既不属于原文也不属于译文，跳过即可）；
 * 3. **任何一个 cell 都不得跨越分界点** —— 跨越者的时间区间同时覆盖
 *    "原文的最后几个字"与"译文的头几个字"，无法在不猜测的前提下划给任一侧。
 *
 * ⚠️ 第 3 条与 [narrowWordsToText] 对原文的处理**有意不同**：
 * 原文侧可以"截短文本、保留时间区间"（后果只是最后一个字亮得久一点），
 * 而译文侧若照做，得到的是"译文的第一个字从原文最后几个字就开始亮"——
 * 高亮走在人声**前面**，是明确错误的信息。故译文侧整体放弃（fail-closed）。
 *
 * 任何一条不满足 → 返回**空列表**，上层据此让译文退回整行进度高亮。
 * 这与 [LyricLine.effectiveWords] 的 fail-closed 纪律是同一条：
 * **宁可退回整行，也不显示错亮**。
 *
 * @param words 宿主 cells 转成的逐字列表（**未经裁剪的原始列表**）
 * @param mainText 分离后的纯原文
 * @param translationText 分离后的译文
 * @return 属于译文的逐字单元；空列表表示"译文没有独立的逐字时间轴"
 */
fun narrowWordsToTranslation(
    words: List<LyricWord>,
    mainText: String,
    translationText: String,
): List<LyricWord> {
    if (words.isEmpty() || mainText.isEmpty() || translationText.isEmpty()) return emptyList()
    val joined = words.joinToString("") { it.text }
    // ① cells 与文本必须对得上：原文是首段、译文是尾段。对不上就说明这批 cells
    //    不是这份文本的（或宿主改过文本），任何切法都是猜的 → 放弃。
    if (!joined.startsWith(mainText)) return emptyList()
    if (!joined.endsWith(translationText)) return emptyList()
    // ② 译文在拼接串里的起点。用「总长 − 译文长」而不是 `indexOf`：后者会在
    //    译文短语恰好也出现在原文里时命中错位置。中间的半角空格被自然跳过。
    val start = joined.length - translationText.length
    if (start < mainText.length) return emptyList()

    val out = ArrayList<LyricWord>(words.size)
    var offset = 0
    for (word in words) {
        val end = offset + word.text.length
        when {
            // 完全落在译文区间内 → 原样保留（时间区间不变，这正是逐字的依据）。
            offset >= start -> out.add(word)
            // 跨越分界点 → 时间区间两侧都覆盖，见 KDoc 第 3 条 → 整体放弃。
            end > start -> return emptyList()
            // 完全落在原文（或分隔空格）区间内 → 不属于译文。
        }
        offset = end
    }
    if (out.isEmpty()) return emptyList()
    // 最后一道防线：拼出来必须精确等于译文，否则说明边界算错了。
    if (out.joinToString("") { it.text } != translationText) return emptyList()
    return out
}

/**
 * 一行歌词：整行时间 + 文本 + 可选翻译 + 可选逐字时间轴。
 *
 * [translation] / [words] / [translationWords] 允许为 null —— 翻译常缺失，
 * 逐字时间轴更是只有部分歌词源提供。用 null 而非空集合表达"没有"，
 * 是为了让上层能区分"这份歌词源不提供逐字"与"提供了逐字但解析后为空"，
 * 前者应关闭卡拉 OK 效果，后者应当作异常数据处理。
 *
 * ⚠️ 原文与译文各有**自己的一套**逐字时间轴（[words] / [translationWords]），
 * 二者互不借用 —— 理由见 [narrowWordsToTranslation] 的 KDoc。
 */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null,
    /**
     * **译文自己的**逐字时间轴。来源见 [narrowWordsToTranslation]。
     * 为 null 或空列表表示"译文没有独立时间轴"，此时译文只能走整行进度高亮。
     */
    val translationWords: List<LyricWord>? = null,
    /**
     * 已并入本行的**译文段数**（0 = 本行没有译文）。
     *
     * 为什么需要它（而不是"有 translation 就够"）：译文可能由宿主**拆成连续多行**
     * 推送（真机样本：Beat It 的 `他们先是又踢有打` + `然后他们会说：扯平了！`
     * 是同一句英文的译文被拆成两行）。合并要有上限，否则一串相邻的异脚本行会被
     * 无限吞进同一行 —— 上限就是靠这个计数判的（见 `isTranslationContinuation`）。
     *
     * 它由 `PlaybackTimeline.lineChanged` 维护，**渲染层不读它**。
     * 之所以挂在数据行上而不是放在时间轴的私有字段里：这一行会被
     * 「重复推送原样保留」（seek 回退）与「整份文档替换」两条路径搬运，
     * 挂在行上就自动跟着走，不会出现"计数与行内容脱节"的静默不一致。
     */
    val translationSegments: Int = 0,
    /**
     * 已并入译文中**最后一段**的结束时间（毫秒）；没有译文时为 null。
     *
     * 用途只有一个：判定"后续的译文行是否紧邻已并入的译文"。
     * ⚠️ 不能用 [endMs] 代替 —— [endMs] 是**主行自己的**区间（真机里常常只有
     * 20 ms，是歌词源用来占位的引导行），而译文的区间通常远长于它。
     * 用 [endMs] 判紧邻会让真正的续接行（起点在译文末尾）被判成"隔了很远"而漏配。
     */
    val translationEndMs: Long? = null,
) {

    /**
     * 经过可信度校验、真正可用于卡拉 OK 的逐字列表；不可用时为空列表。
     *
     * ## 为什么必须做这轮校验（而不是有就给用）
     * 逐字数据一旦部分错误，高亮会**落在错误的字上** —— 例如把整行的时间区间
     * 当成第一个字的时间，结果是"整句都亮在第一个字上"，这比完全没有逐字效果更糟：
     * 用户看到的是**明确错误的信息**，而不是信息的缺失。
     *
     * 因此这里采取 **fail-closed（失败即关闭）** 策略：任何一项校验不过，
     * 就整行放弃逐字，退回整行进度。宁可退回整行高亮，也不显示错误的高亮。
     *
     * ## 校验项（逐条对应的真实失败模式）
     * 1. 非空 —— 空列表无意义。
     * 2. 各字文本拼接后必须**精确等于**整行文本 —— 若不等，说明 `<...>` 标签的
     *    剥离与整行文本的重建对不上（例如标签嵌在词中间、或文本被改写），
     *    此时按索引对位高亮会错位。这是最重要的一条。
     * 3. 每个字 `endMs >= startMs` —— 反序区间是解析错误的强信号。
     * 4. `startMs` 单调不减 —— 逆序的字说明标签顺序被破坏，对位不可信。
     *
     * 校验逻辑与 [effectiveTranslationWords] **共用** [checkedWords] ——
     * 两处各写一份的话，"改一处漏一处"会让译文与原文的严格程度不一致，
     * 而那种不一致是静默的（只在某些歌词源上表现成错亮）。
     */
    val effectiveWords: List<LyricWord>
        get() = checkedWords(words, text)

    /**
     * 经过同一套可信度校验、真正可用于**译文**逐字高亮的逐字列表；不可用时为空列表。
     *
     * 校验项与 [effectiveWords] **完全相同**（见那里的逐条说明），只是把"整行文本"
     * 换成 [translation]：译文的逐字拼接必须精确等于译文本身。
     *
     * ## 为什么译文必须单独校验而不能复用原文的结论
     *
     * 译文的逐字来自 [narrowWordsToTranslation] 从同一批 cells 尾部切出的那一段
     * （见该函数的 KDoc）。切分是启发式的：它可能切在错误的位置上。校验"拼接精确
     * 等于译文"是唯一能挡住错位的**数据自洽性检查** —— 过不了就说明切错了，
     * 此时退回整行进度，绝不把错误的高亮画出来。
     *
     * 没有 [translation] 时返回空列表：**空白译文一律视为不存在**
     * （与原实现 `TranslationProjectionPolicy.cleanSecondaryText` 同一纪律）。
     */
    val effectiveTranslationWords: List<LyricWord>
        get() = checkedWords(translationWords, translation?.takeIf { it.isNotBlank() })

    /**
     * 逐字时间轴是否携带**真正的亚行信息**。
     *
     * ## 为什么单靠"有 words"不足以决定开启卡拉 OK
     * 宿主的 `LyricsLine.lyricsCells` 在部分歌词源下会是**单个 cell 覆盖整行** ——
     * 即这一行确实"有逐字数据"，但那个 cell 的时间区间恰好等于整行。
     * 若据此开启卡拉 OK，会得到一个**跟随整行进度、但永远只作用于一个字**的假效果：
     * 视觉上像是整行在渐变，却无法与整行进度区分开，白白付出逐字渲染的成本。
     *
     * 因此这里要求"至少有一个字的时间区间与整行不同"，才认为逐字信息是有效的。
     */
    val hasWordTimingEvidence: Boolean
        get() {
            val cells = effectiveWords
            if (cells.isEmpty()) return false
            return cells.any { it.startMs != startMs || it.endMs != endMs }
        }

    /**
     * **译文**的逐字时间轴是否携带真正的亚行信息。判据与 [hasWordTimingEvidence] 相同。
     *
     * 真机（`cells=1`，86/86）下本值恒为 false —— 那时译文的逐字是**不存在的**，
     * 渲染层据此让译文退回整行进度高亮，而不是伪造一套时间轴。
     */
    val hasTranslationTimingEvidence: Boolean
        get() {
            val cells = effectiveTranslationWords
            if (cells.isEmpty()) return false
            return cells.any { it.startMs != startMs || it.endMs != endMs }
        }

    /**
     * 整行演唱进度，0f..1f。
     *
     * 与 [LyricWord.progress] 保持同一套退化区间约定（`endMs <= startMs` 视为已完成），
     * 理由同上：零宽/反序的整行区间若返回 0f，该行会永久停留在"未开始"状态。
     */
    fun progress(positionMs: Long): Float = when {
        positionMs < startMs -> 0f
        endMs <= startMs -> 1f
        else -> ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }
}

/**
 * 逐字列表的**可信度校验**（[LyricLine.effectiveWords] 与
 * [LyricLine.effectiveTranslationWords] 共用的唯一实现）。
 *
 * 四条校验与它们的理由见 [LyricLine.effectiveWords] 的 KDoc —— 这里不重复，
 * 只强调**为什么必须是同一个函数**：原文与译文各写一份校验的话，
 * "改一处漏一处"会让两侧的严格程度悄悄分叉，而分叉的表现是**静默的错亮**
 * （某一侧少了一条校验，只在特定歌词源上把高亮画到错字上，不报任何错）。
 *
 * 抽出来还有一个好处：译文侧的失败模式（切分位置算错）与原文侧
 * （标签剥离与文本重建对不上）虽然成因不同，但**判据是同一条**：
 * 拼接必须精确等于目标文本。共用实现让这条判据只有一个定义点。
 *
 * @param cells 候选逐字列表；null 表示"这份歌词源不提供"
 * @param text 目标文本；null / 空 表示"没有可校验的目标"（如译文缺失）
 * @return 通过校验的 [cells] 本身；任一项不过则返回空列表（fail-closed）
 */
private fun checkedWords(cells: List<LyricWord>?, text: String?): List<LyricWord> {
    if (cells == null || cells.isEmpty()) return emptyList()
    if (text.isNullOrEmpty()) return emptyList()
    if (cells.joinToString("") { it.text } != text) return emptyList()
    if (cells.any { it.endMs < it.startMs }) return emptyList()
    if (cells.zipWithNext().any { (a, b) -> a.startMs > b.startMs }) return emptyList()
    return cells
}

/**
 * **共享推进比例** —— 主歌词与译文行在"自己没有可信逐字轴"时**共用的**那个 `0f..1f`。
 *
 * ## 为什么必须共享（用户原话，2026-09-19）
 *
 * > 「你前面说实现的**主副歌词高亮同一时刻两行推进到同一比例**并没有实现
 * > （**平滑高亮设置下**）。」
 *
 * 原实现里两条路径的**分母不同**：
 *
 * | 行 | 没有自己逐字轴时的 `progress` |
 * | --- | --- |
 * | 主歌词 | `line.progress(t)` = `(t − start) / (end − start)` |
 * | 译文 | 同上 —— **但当主行有逐字轴时，主行根本不走这条路** |
 *
 * 于是"主行有逐字轴、译文没有"时（真机很常见：`narrowWordsToTranslation`
 * 遇到跨越分界点的 cell 会**整体放弃**），主行按**逐字时间轴**推进、
 * 译文按**整行线性进度**推进 —— **两条曲线不同 → 同一时刻比例不同**。
 *
 * ## 语义
 *
 * - 主行**有**亚行时间证据时：返回**主行边界在字符空间的占比**
 *   `(前置字符数 + 当前字字符数 × 该字进度) / 总字符数`
 *   —— 它正是主行高亮边界所在的位置，只是用"占比"表达。
 *   译文用它当 `progress` 时，边界会落在**同一个占比**上 → **同一比例**。
 * - 否则退回 [LyricLine.progress]（整行线性进度）。
 *
 * ## 为什么是顶层公开函数
 *
 * 两处调用点（`IslandWindow.drawOneLine` / `drawTranslationLine`）**必须用同一个** ——
 * 各写一份就是本项目反复吃亏的"**同族两处实现只改一处**"。
 * 抽成纯函数还让探针能直接断言它（不必放宽任何可见性）。
 *
 * @param line 歌词行（主行；译文行与它共享同一 `startMs`/`endMs`）
 * @param positionMs 当前播放位置（**已含 `lyric.offset_ms`**）
 */
fun sharedSweepFraction(line: LyricLine, positionMs: Long): Float {
    val words = line.effectiveWords
    if (words.isEmpty() || !line.hasWordTimingEvidence) return line.progress(positionMs)
    val total = words.sumOf { it.text.length }
    if (total <= 0) return line.progress(positionMs)
    var charsBefore = 0
    for (word in words) {
        val p = word.progress(positionMs).coerceIn(0f, 1f)
        if (p < 1f) {
            return ((charsBefore + word.text.length * p) / total).coerceIn(0f, 1f)
        }
        charsBefore += word.text.length
    }
    return 1f
}

