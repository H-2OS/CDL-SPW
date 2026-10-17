// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.playback

/**
 * 双语行的**启发式分离器** —— 把宿主推来的「原文+译文混在同一串」拆成两段。
 *
 * ## 为什么需要它（真机取证，不是猜测）
 *
 * 原记（`recon/24` §1）：宿主 `LyricsLine.pureMainText` 只含原文、
 * 译文另由 `pureSubText` 承载 —— 那是 4 参构造 `(start, end, cells, subText)` 的语义，
 * 有 javap 与两个原实现插件的契约测试佐证。
 *
 * 实为（2026-09-17 真机日志，`super-island.log` 的 86 条 `callback line:` 全量统计）：
 * **`sub=<null>` 出现 86 次，非 null 出现 0 次** —— 对本用户实际使用的歌词源，
 * `pureSubText` **恒为 null**，译文被直接拼进了 `pureMainText`，以**空格**分隔：
 *
 * ```
 * main=そんな日はもうたくさんだ 这样的时光我受够了 | sub=<null> | cells=1
 * main=いつまでもなんてないんだ 世间没有 所谓的永远 | sub=<null> | cells=1
 * main=作詞 : 野田洋次郎 | sub=<null> | cells=1
 * main=Beat It (Album Version) | sub=<null> | cells=1
 * ```
 *
 * 后果：`splitMainAndTranslation` 只认「`pureSubText` 是后缀」这一条路，
 * 而 `pureSubText` 恒为 null → **永不剥离** → 译文行永远不画、
 * 原文行的高亮把译文一起染上（用户报的「译文仍和外文歌词混杂在一起」）。
 *
 * 故这里补一条**不依赖 `pureSubText`** 的分离路径。
 *
 * ## 切分规则（保守优先，**按书写系统**而不是按语言）
 *
 * 只在文本**看起来真的是双语行**时才切。判据全部来自**书写系统**（Unicode 区块），
 * 不需要词义、不需要词典、不需要语言识别：
 *
 * 1. 文本里存在一个**半角空格**（U+0020），使得
 *    - **前半含「原文书写系统」字符** —— 假名 / 拉丁字母 / 西里尔字母 / 谚文；
 *    - **后半不含任何「原文书写系统」字符**；
 *    - **后半含至少一个 CJK 汉字**。
 * 2. 切在**第一个**满足条件 1 的空格处：前半 = 原文，后半 = 译文。
 *
 * 任一条不满足 → **不切**（整串当原文，译文为 null）。
 *
 * ### 为什么是「书写系统」这个判据
 *
 * 因为「外文原文 + 中文译文」这个形态**本身就是书写系统的差异**：
 * 日语用假名、英语用拉丁字母、俄语用西里尔字母，而**中文译文用汉字**。
 * 于是"前半含外语书写系统、后半只含汉字"恰好刻画了它 ——
 * 这是**由文本自身给出的**判据，不依赖任何语言猜测。
 *
 * ### 2026-09-17 的泛化：从「假名边界」到「书写系统边界」
 *
 * 原记：判据只有**假名**（`splitByKanaBoundary`）。
 *
 * 实为：用户要求「**所有带译文的外文歌（英、日、俄等）**，需将主歌词行的译文
 * 下放到副歌词区域显示」。只认假名意味着**英文歌、俄文歌的译文永远不剥离** ——
 * 它们和日语歌是同一个需求，却走了另一条（不工作的）路径。
 *
 * 故判据从"含假名"扩展为"含假名 / 拉丁 / 西里尔 / 谚文"。
 * 泛化后**原有用例全部保持原判**（见下方逐条对照），新增的是英/俄两类的命中。
 *
 * 逐条对照真机样本与边界：
 *
 * | 样本 | 前半含外语 | 后半无外语 | 后半含汉字 | 判定 |
 * |---|---|---|---|---|
 * | `そんな日はもうたくさんだ 这样的时光我受够了` | ✓ | ✓ | ✓ | **切** |
 * | `いつまでもなんてないんだ 世间没有 所谓的永远` | ✓ | ✓（译文内部空格保留） | ✓ | **切** |
 * | `I do believe 我坚信不疑`（英文歌） | ✓ | ✓ | ✓ | **切**（泛化后新增） |
 * | `Привет мир 你好世界`（俄文歌） | ✓ | ✓ | ✓ | **切**（泛化后新增） |
 * | `Beat It 打败它`（英文歌） | ✓ | ✓ | ✓ | **切**（泛化后新增） |
 * | `作詞 : 野田洋次郎` | ✗（无外语） | — | — | 不切 |
 * | `Beat It (Album Version)` | ✓ | ✗（后半仍是拉丁） | ✗ | 不切 |
 * | `hello world` | ✓ | ✗ | ✗ | 不切 |
 * | `你好 世界` | ✗ | — | — | 不切 |
 * | `いつか また会おう` | ✓ | ✗（后半含假名） | — | 不切 |
 *
 * ### 为什么「切在第一个满足条件的空格」而不是「第一个空格」
 *
 * 因为原文自己**也可能含半角空格**（`Wow すごい 太厉害了`：第一个空格之后
 * 仍是日语；`I do believe 我坚信不疑`：前两个空格之后仍是英语）。
 * 只看第一个空格会把原文拦腰截断。逐个空格尝试、取**第一个让后半成立**的位置，
 * 才能跳过原文内部的空格。
 *
 * ### 为什么只认半角空格 U+0020
 *
 * 全角空格（U+3000）在日语正文里是**惯用排版**（`変わらぬ空に　君を映して`
 * 的「に　君」之间就有一个）。把它当分界会显著抬高误切率，
 * 而真机日志里原文/译文之间用的**都是半角空格**。故只认半角。
 *
 * ### 为什么后半必须**含汉字**
 *
 * 这一条挡掉的是"后半只是标点或拉丁残留"的情形（如 `Beat It (Album Version)`）。
 * 没有它，规则会在任何"外语 + 空格 + 非外语"处乱切。
 *
 * ## 已知的误切风险（必须明说，不做过度承诺）
 *
 * 规则只依赖书写系统，**无法区分「日语原文里的全汉字短语」与「中文译文」**。
 * 反例：`君の名は 永遠` —— 前半含假名、后半 `永遠` 全是汉字，会被切成
 * 原文 `君の名は` + 译文 `永遠`，而它其实是**一整句日语**。
 *
 * 这个代价是**有意接受**的：另一侧的失败模式（不切）会让译文跟着原文一起高亮，
 * 而这一侧只是把一句日语拆成两行显示，歌词**内容不丢**（两行都会被画出来）。
 * 两者的信息损失不对称，故偏向「宁多切、不少切」。
 *
 * ⚠️ **中日混杂是这条规则的能力边界**：日语用汉字，中文也用汉字，
 * 单凭书写系统**无法**把"日语原文里的汉字"与"中文译文"分开。
 * 上面那条 `君の名は 永遠` 就是这个边界的实例。收窄手段是把
 * 「后半必须含 CJK 汉字」升级为「后半必须含**简体中文特有**的字」，
 * 但那需要一张字表，成本远超当前收益 —— 且仍会漏（简繁共用字很多）。
 * 故此处**如实标注为能力边界**，而不是声称"已解决中日混杂"。
 *
 * @param mainText 宿主 `pureMainText`（可能已含译文，可能为 null）
 * @param subText 宿主 `pureSubText`（真机实测恒为 null；非 null 时优先走后缀剥离）
 * @return 原文与译文。译文为 null 表示"没有可单独显示的译文"
 */
fun splitBilingualLine(mainText: String?, subText: String?): Pair<String, String?> {
    // ① 宿主若**确实**给了译文（`pureSubText` 非空），优先走后缀剥离 ——
    //    那条路有数据本身作依据（译文是尾部那段），比启发式可靠得多。
    val (main, sub) = splitMainAndTranslation(mainText, subText)
    if (sub != null) return main to sub
    // ② 宿主没给译文（真机实测 86/86 全为 null）→ 退回按书写系统切分。
    return splitByScriptBoundary(main)
}

/**
 * 按**书写系统边界**切分 [text]（规则与理由见 [splitBilingualLine] 的 KDoc）。
 *
 * 单独拆出来是为了让探针能**直接**断言规则本身（不经由 `pureSubText` 那条分支）。
 *
 * 原记：本函数名为 `splitByKanaBoundary`，判据只有假名。
 * 实为：2026-09-17 泛化为假名 / 拉丁 / 西里尔 / 谚文（用户要求覆盖英、俄等语种）——
 * 改名是为了让函数名与它实际做的事一致（名字说谎比没名字更糟）。
 *
 * @return `(原文, 译文)`；不满足切分条件时返回 `(text, null)`（**原文原样返回，绝不改动**）
 */
fun splitByScriptBoundary(text: String): Pair<String, String?> {
    if (text.isEmpty()) return text to null
    var space = text.indexOf(' ')
    while (space >= 0) {
        val head = text.substring(0, space)
        // 后半取「该空格之后的**全部**剩余文本」—— 译文内部可能还有空格
        // （真机样本 `世间没有 所谓的永远` 就是这样），必须整体保留。
        val tail = text.substring(space + 1).trim()
        if (tail.isNotEmpty() &&
            hasForeignScript(head) &&
            !hasForeignScript(tail) &&
            hasCjkIdeograph(tail)
        ) {
            val original = head.trimEnd()
            // 前半为空（行首就是空格）→ 不是双语行，继续找下一个空格。
            if (original.isNotEmpty()) return original to tail
        }
        space = text.indexOf(' ', space + 1)
    }
    return text to null
}

// ── 书写系统判定 ────────────────────────────────────────────────────────────
//
// 全部按 Unicode 区块判定，**不做语言识别**（那需要模型或词典，与"零依赖、可离线"冲突）。
// 每个函数只回答"这个字符属不属于某个书写系统"，组合起来就是上面那条规则。

/**
 * 是否含**外语书写系统**字符 —— 假名 / 拉丁字母 / 西里尔字母 / 谚文。
 *
 * 之所以把这四类放在一起而不是逐个判断：它们在本规则里扮演**同一个角色**
 * （"这是原文"的证据），而汉字扮演另一个角色（"这是译文"的证据）。
 * 合成一个函数后，规则读起来就是「前半是外语、后半是汉字」这一句话。
 *
 * ⚠️ **有意不含汉字**：汉字中日共用，用它当"外语"证据会让**纯中文行**
 * 被判成"含外语"，从而在第一个空格处被误切（`你好 世界` → `你好` + `世界`）。
 */
private fun hasForeignScript(s: CharSequence): Boolean {
    for (c in s) {
        if (isKana(c) || isLatinLetter(c) || isCyrillicLetter(c) || isHangul(c)) return true
    }
    return false
}

/** 是否含 CJK 汉字。见 [splitByScriptBoundary] 关于"后半必须含汉字"的论证。 */
private fun hasCjkIdeograph(s: CharSequence): Boolean {
    for (c in s) if (isCjkIdeograph(c)) return true
    return false
}

/**
 * 是否为假名（平假名 / 片假名）。
 *
 * 区间取 Unicode 标准的两段：`U+3040..U+309F`（平假名）、`U+30A0..U+30FF`（片假名）。
 * 后者含 `・`（U+30FB）与长音符 `ー`（U+30FC）—— 它们确实只出现在日语文本里，
 * 计入"含外语"是正确的（不会把中文或英文误判成含日语）。
 */
private fun isKana(c: Char): Boolean =
    c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'

/**
 * 是否为拉丁字母。
 *
 * 用 `Character.isLetter` + 上界 `U+024F`（含 Latin-1 补充与 Latin Extended-A/B），
 * 而不是写 `'A'..'Z' || 'a'..'z'`：后者漏掉带音标的拉丁字母
 * （`Café`、`naïve`、`Señorita`）—— 而它们恰恰常出现在**歌名与歌词**里，
 * 漏判会让整行判成"不含外语"，从而**该切不切**。
 *
 * 上界取 `U+024F` 而不是"所有字母"：再往上就是国际音标扩展、希腊、西里尔、
 * 亚美尼亚……那些要么另属一个书写系统（西里尔已单独判），要么不该算拉丁。
 */
private fun isLatinLetter(c: Char): Boolean =
    Character.isLetter(c) && c.code <= 0x024F

/** 是否为西里尔字母（俄语、乌克兰语、保加利亚语等）。区块 `U+0400..U+04FF`。 */
private fun isCyrillicLetter(c: Char): Boolean =
    c in '\u0400'..'\u04FF'

/**
 * 是否为谚文（韩语）。
 *
 * 三段都算：`U+1100..U+11FF`（字母）、`U+3130..U+318F`（兼容字母）、
 * `U+AC00..U+D7AF`（预组合音节，即日常使用的那些方块字）。
 * 只判最后一段会漏掉"韩语原文用字母拼写"的少数写法。
 */
private fun isHangul(c: Char): Boolean =
    c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F' || c in '\uAC00'..'\uD7AF'

/**
 * 是否为 CJK 汉字。
 *
 * 三段：`U+4E00..U+9FFF`（基本区）、`U+3400..U+4DBF`（扩展 A）、
 * `U+F900..U+FAFF`（兼容汉字）。
 *
 * 有意**不含**扩展 B 及以后（`U+20000` 以上的代理对区）—— 那些是罕用字，
 * 出现在歌词里的概率极低，而处理它们要把判据改成按码点（`codePointAt`）遍历，
 * 复杂度上升一档。若将来真的遇到，正确的修法是改成本函数接受码点。
 */
private fun isCjkIdeograph(c: Char): Boolean =
    c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'

// ── 行的「主导书写系统」与「元数据行」判定 ────────────────────────────────────
//
// 这两个判定服务于 `PlaybackTimeline` 的**译文行配对**：把宿主以「独立行」形态
// 推送的译文降格为原文行的副行（用户诉求「副歌词完全不显示」的修法）。
//
// 它们放在**本文件**而不是 `LyricLine.kt`，是因为**全部书写系统判定都住在这里**
// （上面那六个 private 谓词）。换一个文件就得再抄一份 Unicode 区间 ——
// 而那正是本项目反复记录的「同一语义散在多处定义点，改一处漏一处」的形态。
//
// 与 [splitByScriptBoundary] 的分工：那个回答「**这一串**能不能切成原文+译文」
// （「同行拼接」形态），这里回答「**这一串整体**属于哪个书写系统」
// （「分行推送」形态）。两者互补，不重叠。

/**
 * 一行文本的**书写系统标签**。只用于「两行是不是互为原文/译文」这一个判断，
 * 不做语言识别（那需要词典或模型，与"零依赖、可离线"冲突）。
 */
enum class Script {
    /** 拉丁字母（英、法、德、西…）。 */
    LATIN,

    /** CJK 汉字（中文，以及日文里的汉字）。 */
    HAN,

    /** 假名（平假名 / 片假名）—— 出现即判为日语，见 [dominantScript]。 */
    KANA,

    /** 谚文（韩语）。 */
    HANGUL,

    /** 西里尔字母（俄、乌克兰、保加利亚…）。 */
    CYRILLIC,
}

/**
 * 判定 [text] 的**主导书写系统**；无法判定时返回 `null`。
 *
 * ## 为什么"混排返回 null"是刻意的
 *
 * 本函数唯一的消费者是译文行配对，而配对的失败模式是**把两句本来独立的歌词
 * 并成一主一副**（见 `isTranslationPair` 的 KDoc）—— 比"该配没配"更糟。
 * 故判据取严：一行里同时出现两种"有意义的"书写系统时**不表态**，
 * 让上层放弃配对。
 *
 * 这恰好挡掉了点名的那类误判风险：**中英混排的正常歌词**
 * （`I do believe 我坚信不疑`）会判成 `null` —— 它不属于"整行是另一种语言"，
 * 而属于"行内混排"，两者由本函数分开。
 *
 * ## 判定顺序与理由
 *
 * 1. **假名 / 谚文只要出现就定性**（`KANA` / `HANGUL`）：这两种书写系统
 *    不可能出现在纯英文或纯中文里，一个字符就足以排除其它可能。
 *    `変わらぬ空に君を映して`（含假名）判 `KANA`，与中文译文 `不变的天空映着你`
 *    （`HAN`）不同 → 可配对。
 * 2. 其余按**计数**：只允许**一种**"有意义的"书写系统（拉丁 / 汉字 / 西里尔）；
 *    两种及以上 → `null`（混排）。
 * 3. 该类字符少于 [MIN_SCRIPT_CHARS] → `null`：太短的行（`A`、`Oh`）不足以定性，
 *    判错会把歌名、叹词卷进配对。
 * 4. **标点/数字多于该类字符** → `null`：说明这一行主要是符号（`(Woo!)`、`…!?`），
 *    文本本身不携带书写系统信息。
 *
 * ## 已知边界（如实标注）
 *
 * 日语原文里的**全汉字短语**与中文译文无法区分（两侧都判 `HAN`）——
 * 本函数对它的作用只是"两侧同判 → 不配对"，方向是保守的
 * （不配对 = 保持现状，不丢信息）。那条路径的可切性由
 * [splitByScriptBoundary] 负责，见那里的「能力边界」一节。
 *
 * @param text 行文本；`null` / 空 / 全空白 → `null`
 * @return 主导书写系统；混排或无法判定 → `null`
 */
fun dominantScript(text: String?): Script? {
    if (text.isNullOrEmpty()) return null
    var latin = 0
    var han = 0
    var kana = 0
    var hangul = 0
    var cyrillic = 0
    var other = 0
    for (c in text) {
        when {
            // 空白不计入任何一类，也不计入 other —— 它是分隔符，不是"内容"。
            c.isWhitespace() -> Unit
            isKana(c) -> kana++
            isHangul(c) -> hangul++
            isCjkIdeograph(c) -> han++
            isCyrillicLetter(c) -> cyrillic++
            isLatinLetter(c) -> latin++
            else -> other++
        }
    }
    // ① 假名 / 谚文出现即定性（见 KDoc 第 1 条）。
    if (kana > 0) return Script.KANA
    if (hangul > 0) return Script.HANGUL
    // ② 只允许一种"有意义的"书写系统；两种及以上 → 混排 → 不表态。
    val present = ArrayList<Pair<Int, Script>>(3)
    if (latin > 0) present.add(latin to Script.LATIN)
    if (han > 0) present.add(han to Script.HAN)
    if (cyrillic > 0) present.add(cyrillic to Script.CYRILLIC)
    if (present.size != 1) return null
    val (count, script) = present[0]
    // ③ 太短不足以定性（`A` / `Oh`）。
    if (count < MIN_SCRIPT_CHARS) return null
    // ④ 标点/数字占多数 → 文本不携带书写系统信息。
    if (other > count) return null
    return script
}

/**
 * 判定 [text] 是不是**制作信息行**（`作词 : 小美` / `Publisher : TV Asahi Ms`）。
 *
 * ## 为什么配对必须显式挡掉它们
 *
 * 真机日志里这类行有 **42 条**（`作词 : …` / `作曲 : …` / `制作人 : …` /
 * `和声 : …` / `Publisher : …` …）。它们与歌词行一样是**独立推送的行**、
 * 也占时间轴，而且**相邻两条的间隔可以很短**（`作词 : 李克勤`@0-250 →
 * `作曲 : 立川俊之`@250-500，间隔 250 ms）。
 *
 * 于是"相邻 + 书写系统互补"这条判据会**真实地命中**它们：日志里就有一例 ——
 * `和声 : 雷有曜/…`（`HAN`）@1750-2000 → `Publisher : TV Asahi Ms (PolyGram)`
 * （`LATIN`）@2000-2500，间隔 250 ms、且时间紧邻。不挡的话，制作信息会被
 * 并成"一主一副"显示。
 *
 * ## 判据：` : `（半角空格 + 半角冒号 + 半角空格）
 *
 * 这是制作信息行的**格式特征**：真机 42/42 条全部符合，而 265 条唯一行里
 * **没有一条歌词**命中（已全量核对，见报告）。为什么必须带两侧空格：
 * 歌词里出现冒号是常见的（`然后他们会说：扯平了！`），但那是**全角** `：`；
 * 要求半角 `:` 且两侧有空白，把"歌词正文"与"键值对"分开。
 *
 * ## 失败方向
 *
 * 判成元数据 → 不配对（保持现状）。判错的最坏后果是"这一对没被配对"，
 * 即**退回改动前的行为**，不丢字。故这里宁可宽松一点。
 *
 * @param text 行文本；`null` → `false`
 * @return `true` = 看起来是制作信息行
 */
fun isMetadataLine(text: String?): Boolean =
    text != null && METADATA_LINE_PATTERN.containsMatchIn(text)

/**
 * **宿主推来的「无歌词提示行」** —— 它不是歌词，是提示。
 *
 * ## 真机取证（2026-09-20，`super-island.log` 3830 行）
 *
 * ```
 * callback line: main="纯音乐，请欣赏"(len=7) | sub=<null> | cells=1["纯音乐，请欣赏"@5000-8000] | span=5000-8000
 * ```
 *
 * 宿主把它**当成一行歌词推来**（走的就是 `callback line:` 歌词回调，`cells=1` 覆盖整行）。
 *
 * ## 为什么必须认出来（用户原话：「纯音乐/无歌词逻辑混乱」）
 *
 * 插件自己另有一套"无歌词"机制：`NO_LYRICS_PLACEHOLDER`（"暂无歌词"）+
 * `layout.noLyrics.mode` / `hideWhenInstrumental` / `showTrackTitle` 三个开关。
 * **两套机制并存**的后果是：用户打开「纯音乐时隐藏占位句」，
 * 宿主推来的这句**照样显示** —— 因为那个开关只管**我们自己画的那一句**。
 * 用户看到的是"我开了隐藏，它还在"，这就是"逻辑混乱"。
 *
 * ## 判据：只认**完全相等**（trim 后），不做"包含"匹配
 *
 * 「包含」会把真歌词里的同名字串一起误判掉（歌词里出现"纯音乐"三个字是可能的）。
 *
 * ## 命中后的处理：**当"没有歌词"**，交给 `layout.noLyrics.*` 统一决定
 *
 * 与过门间隙（`lineCleared()`）走**同一条路**，于是"显示与否"只有一个决定点。
 * ⚠️ 代价**如实记录**：`mode = PLACEHOLDER` 时会显示**我们自己的**"暂无歌词"，
 * 而不是宿主的"纯音乐，请欣赏"。要保留宿主那句就得把它存成占位文本 ——
 * 那是另一个设计决定，留给用户拍板。
 *
 * ⚠️ 这是**开放集**（别的歌词源可能推别的措辞），故只放**真机已见过**的；
 * 将来加一行即可。**没认出来**的后果只是"回到改动前的行为"，不会更糟。
 */
fun isHostPlaceholderLine(text: String?): Boolean {
    val t = text?.trim() ?: return false
    if (t in HOST_PLACEHOLDER_LINES) return true
    // ⚠️ 2026-09-20 加强：**归一化后再比一次**。
    //    真机见过的是全角逗号 `纯音乐，请欣赏`，但**宿主措辞与标点可能变**
    //   （半角逗号 / 无逗号 / 空格 / 句号）。归一化把标点与空白全去掉再比，
    //    于是 `纯音乐,请欣赏` / `纯音乐 请欣赏` / `纯音乐请欣赏` 都能命中。
    //
    // ⚠️ **有意不做"包含"匹配**：真歌词里出现「纯音乐」三个字是可能的，
    //    用包含匹配会把它一起吃掉。**宁可漏（回到改动前的行为），不可误杀真歌词。**
    return normalizePlaceholder(t) in HOST_PLACEHOLDER_LINES_NORMALIZED
}

/** 去掉空白与常见标点，用于"措辞相同但标点不同"的比对。 */
private fun normalizePlaceholder(s: String): String =
    s.filterNot { it.isWhitespace() || it in "，,。.、！!~～·…—-" }

/** 已知的宿主提示串（**只放真机已见过的**，见 [isHostPlaceholderLine] 的 KDoc）。 */
private val HOST_PLACEHOLDER_LINES = setOf(
    "纯音乐，请欣赏",
    "纯音乐请欣赏",
    "纯音乐",
    "暂无歌词",
)

/** [HOST_PLACEHOLDER_LINES] 归一化后的形态（标点已去掉）。 */
private val HOST_PLACEHOLDER_LINES_NORMALIZED =
    HOST_PLACEHOLDER_LINES.map { normalizePlaceholder(it) }.toSet()

/**
 * 元数据行的格式判据：**行首一个不含空白的词 + 空白 + 半角冒号 + 空白 + 内容**。
 *
 * `\S+` 用"不含空白"而不是"字母/汉字"，是为了同时覆盖
 * `作词` / `Publisher` / `OP` / `SP` 这几种真机出现过的写法。
 */
private val METADATA_LINE_PATTERN = Regex("""^\s*\S+\s+:\s+\S""")

/**
 * 一个书写系统至少要有多少个字符才足以定性（见 [dominantScript] 第 ③ 条）。
 *
 * 取 2：1 个字符的行几乎都是叹词或符号（`A`、`Oh`），
 * 用它们当"这是英语原文"的证据会把歌名、语气词卷进配对。
 */
private const val MIN_SCRIPT_CHARS = 2
