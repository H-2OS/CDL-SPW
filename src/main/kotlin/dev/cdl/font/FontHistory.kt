// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

/**
 * 「最近使用字体」历史 —— 对应设置项 `main_lyric.font.recent`。
 *
 * ## 容量为什么取 [CAPACITY] = 10
 *
 * 任务书要求容量落在 8–12，取 10 的理由：
 *
 * - **8 偏少**：用户常年在 3–4 个正文字体间切换（如雅黑 / 思源黑 / MiSans / 苹方），
 *   再偶尔试几个候选，8 个会被最近的试错挤掉常用项，历史就失去"回到上次那个"的价值。
 * - **12 偏多**：下拉/子菜单一屏放不下，用户要滚动才能看到 —— 而"最近使用"的全部意义
 *   就是**不用找**。超过一屏的列表退化成普通列表，失去了它存在的理由。
 * - **10** 是常见字体选择器的一屏容量，也是本项目设置窗口字体菜单能完整展示的长度。
 *
 * ## 语义（与设置总表 §5.5 一致）
 *
 * - **按使用时间倒序**：`[0]` 是最近用过的。
 * - **去重**：同一族名只保留最近一次（再选一次要把它顶到最前，而不是新增一条）。
 * - **超容量截断**：保留前 [CAPACITY] 个，丢最旧的。
 * - **空串 = 无历史**：这是该设置项的默认值（见 SettingRegistry 的 `default = ""`），
 *   [parse] 必须把它当作"空列表"而不是"一个空族名"。
 *
 * ## ⚠️ 关于该设置项的真实类型（实现前必须知道的一处事实）
 *
 * 任务书说该项是 `EDITTEXT`/String。**实际注册表里它是 `SettingKind.LIST`**
 * （见 `SettingRegistry` 里 `main_lyric.font.recent` 的注册项），配 `options = emptyList()`。
 * 而 `SettingKind.LIST` 的收敛路径（`SettingDescriptor.coerce` 的 `LIST` 分支）是：
 *
 * ```
 * options!!.firstOrNull { it.first == text }?.first ?: default
 * ```
 *
 * `options` 为空 → `firstOrNull` 恒为 `null` → **任何写入的值都会被收敛回 `default`（`""`）**。
 *
 * 也就是说：**走设置系统的写路径，历史写不进去**。这不是本类能修的 bug，
 * 而是"该项被登记成 LIST 但没有固定候选值"的登记错误。本类因此**不依赖设置系统
 * 来持久化**，而是把 [serialize]/[parse] 设计成纯函数，由调用方决定往哪里存
 * （设置项可用时用之；不可用时用 `SettingsStore.dataDirectory()` 下的自有文件）。
 *
 * ⚠️ **不确定处（如实声明）**：我按 `SettingKind.LIST` + 空 options 的代码路径
 * 推出的结论是"写入必被丢弃"，但**未在真机跑一次验证**。若实际行为与推断不符
 * （例如宿主侧的 `ConfigHelper` 不走 `coerce`），本类的 [serialize]/[parse]
 * 仍然正确可用 —— 它们不依赖设置系统的行为。
 */
object FontHistory {

    /**
     * 历史上限。理由见类 KDoc。
     *
     * `internal`（而非 private）：设置窗口可能需要按同一容量渲染占位行数，
     * 让容量只有**一个来源**比在两个文件里各写一个 `10` 更不容易腐化。
     */
    const val CAPACITY = 10

    /**
     * 记录分隔符：`\n`（换行）。
     *
     * ## 为什么选换行、而不是逗号/分号/竖线
     *
     * 字体族名**可能含** `,`、`;`、`|`、空格 —— 这不是理论担忧：
     * `Source Han Sans, Regular` 这类"族名+样式"的写法、以及某些厂商在族名里
     * 直接放括号与逗号都很常见。用这些字符做分隔符必然产生歧义。
     *
     * 换行的入选理由是**最强的一条**：字体族名**不可能含换行**。
     * 族名最终要进 Swing 的字体选择器与 `new Font(family, ...)`，
     * 而 `GraphicsEnvironment` 的族名表按单行文本处理；含控制字符的族名
     * 在任何真实字体文件里都不会出现（且 [sanitize] 会主动剔除控制字符，
     * 把这条"不可能"变成"由本类保证"）。
     *
     * ## 与"JSON 数组"方案的对比（为什么没选 JSON）
     *
     * JSON 数组（`["a","b"]`）同样无歧义，但：
     * 1. **`parse` 要写一个 JSON 解析器**。本项目**禁止引入新依赖**，
     *    手写 JSON 字符串解析（转义、代理对、`\uXXXX`）是几百行且极易出错的代码，
     *    为了存 10 个族名不值得。
     * 2. 若用 `org.json` 之类现成库 —— 破坏"不引入新依赖"的硬约束。
     * 3. 换行分隔 + 转义（见下）用 **不到 20 行**就能做到同等无歧义。
     *
     * ## 转义表（[escape] / [unescape] 必须严格互为逆运算）
     *
     * 因为换行是分隔符，族名里**理论上可能出现的反斜杠**必须转义，
     * 否则一个含 `\` 的族名（罕见但非不可能）会在解析时吃掉后一个字符：
     *
     * | 原文 | 编码 |
     * |---|---|
     * | `\` | `\\` |
     * | `\n` | `\n`（两字符：反斜杠 + n） |
     * | `\r` | `\r`（两字符） |
     *
     * **失效边界（必须写明）**：
     * - [serialize] 前会 [sanitize] 剔除所有**控制字符**（含换行、制表、NUL）。
     *   因此**编码后的文本里不会再出现真正的换行**（只有 [RECORD_SEPARATOR] 自身）。
     * - 若有人**手工**往配置里写一个含真换行的值，[parse] 会把它当作**两条记录**
     *   （这是"格式被破坏"的正常表现，会切出两个族名，而不是崩溃或吞掉整份历史）。
     * - 若有人手工写入**未转义的反斜杠 + n**，它会被解码成换行，进而被 [sanitize]
     *   剔除为单条记录内的连接 —— 结果是族名少一个字符，不会崩溃。
     * - 族名本身**含 `\n` 字形序列**（字面反斜杠后跟字母 n）的情况，
     *   一旦经过 [serialize]/[parse] 往返会**变成真换行再被剔除** —— 这是本格式
     *   唯一会**丢失信息**的场景。判断为可接受：真实字体族名不含反斜杠，
     *   而换取的是"不引入依赖、代码量极小"。
     */
    private const val RECORD_SEPARATOR = '\n'

    /**
     * 序列化族名列表为单行字符串。
     *
     * ## 契约
     * - 顺序**原样保留**（本方法不做排序 —— 排序是 [record] 的职责，不是编码的职责）。
     * - 空白项（空白串、`null` 之外的空值）被丢弃，避免在历史里留下"点不动的空条目"。
     * - 去重（保留**首次**出现的位置），保证同一个族名不会在列表里出现两次。
     * - 超 [CAPACITY] 时截断**尾部**（尾部 = 最旧，因为约定 [0] 是最新的）。
     * - 空列表 → 返回空串（与设置项 `default = ""` 一致，表示"无历史"）。
     *
     * @return 单行字符串；无有效项时为空串
     */
    fun serialize(families: List<String>): String {
        val seen = LinkedHashSet<String>(families.size)
        for (family in families) {
            val clean = sanitize(family)
            // 判空放在 sanitize 之后：`"  "`（纯空白）净化后为空，同样该丢。
            if (clean.isNotEmpty()) seen += clean
            // 提前退出：已经装够 CAPACITY 条，后面的必然被截断，不必再净化。
            if (seen.size >= CAPACITY) break
        }
        if (seen.isEmpty()) return ""
        return seen.joinToString(RECORD_SEPARATOR.toString()) { escape(it) }
    }

    /**
     * 反序列化。**契约：永不抛异常**（配置坏了就回退空列表）。
     *
     * ## 为什么不抛异常是硬要求（而不是"顺手兜一下"）
     *
     * 这个字符串来自用户配置文件，可能被手改、被旧版本写坏、被另一个插件覆盖。
     * 它在下拉菜单被打开时就要解析 —— 若抛异常，用户的表现是**设置窗口打不开**，
     * 而真实原因只是一个历史字符串格式不对。代价极不对称。
     * 故这里对所有输入都返回一个可用的列表（最坏是空列表）。
     *
     * ## 容错规则
     * - 空串 / 纯空白 → 空列表（对应"无历史"）
     * - 含真换行 → 按换行切成多条（手工改坏的常见形态，能救则救）
     * - 未转义的反斜杠 → 原样保留（[unescape] 对"反斜杠后跟无关字符"不吞字符）
     * - 解码后为空白的条目 → 丢弃
     * - 重复条目 → 去重，保留首次出现位置
     * - 超过 [CAPACITY] 条 → 截断尾部
     *
     * @return 族名列表，[0] 为最近使用；输入不可用时为空列表
     */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val result = ArrayList<String>(CAPACITY)
            val seen = HashSet<String>(CAPACITY)
            for (record in raw.split(RECORD_SEPARATOR)) {
                val family = sanitize(unescape(record))
                if (family.isEmpty()) continue
                if (!seen.add(family)) continue
                result += family
                if (result.size >= CAPACITY) break
            }
            result
        } catch (t: Throwable) {
            // 理论上不可达（上面全是字符串操作，不抛异常）。保留这一层是因为：
            // 本方法的契约是"永不抛异常"，而契约不该依赖"我记得 split 不抛"这种记忆。
            // 代价是一次永不命中的 try，收益是契约由代码结构保证而非由注释保证。
            System.err.println("[CDL] 字体历史解析异常，回退为空列表：$t")
            emptyList()
        }
    }

    /**
     * 把一个族名**记录进历史**（去重 + 置顶 + 截断）。
     *
     * 与 [serialize] 的分工：[serialize] 是"把给定列表编码"（不重排），
     * 本方法是"应用历史的语义规则"（重排）。分开的理由是 ——
     * `serialize(parse(s)) == s` 这条**往返性质**要求 [serialize] 不得重排
     * （否则历史顺序在每次存取间都会被破坏）；而重排是用户点选字体时的**动作**，
     * 不是编码的属性。混在一起会让"存一次读一次"意外改变历史顺序。
     *
     * @param families 现有历史（通常来自 [parse]）
     * @param used 本次使用的族名
     * @return 新的历史列表（已置顶、去重、截断）；[used] 空白时原样返回 [families] 的规范化结果
     */
    fun record(families: List<String>, used: String): List<String> {
        val clean = sanitize(used)
        if (clean.isEmpty()) return parse(serialize(families))
        // 先移除旧的同族名（若存在），再插到最前 —— 这就是"置顶"。
        // 显式走 parse/serialize 而非直接 listOf(clean) + families.filter：
        // 这样"去重用的是 sanitize 后的键"这条规则只有一处实现（见 sanitize 的 KDoc）。
        val existing = parse(serialize(families))
        val reordered = ArrayList<String>(existing.size + 1)
        reordered += clean
        for (family in existing) {
            if (family != clean) reordered += family
        }
        return parse(serialize(reordered))
    }

    /**
     * 剔除**控制字符**，并 trim 首尾空白。
     *
     * 为什么要主动剔除，而不是"信任调用方给的是干净族名"：
     * 族名会从两个不可信来源进来 —— ① 用户手改的配置文件；② 字体文件内部的
     * `name` 表（理论上可信，但**第三方字体打包不规范是常态**，且 [FontLoader]
     * 对 `.ttc` 展开出的子字体族名未做逐字校验）。
     *
     * 而控制字符的存在会**破坏本格式的核心前提**（"族名不含换行"），
     * 也会让 Swing 的字体选择器显示异常。故在这里统一剔除 ——
     * 这是**唯一**收窄族名的位置，让"什么算合法族名"只有一处定义。
     *
     * trim 的理由：族名首尾空白在 `new Font("  雅黑  ", ...)` 里会导致查不到，
     * 而用户从网页复制字体名时几乎必然带上首尾空白。
     */
    private fun sanitize(family: String): String {
        val builder = StringBuilder(family.length)
        for (ch in family) {
            // 控制字符（U+0000..U+001F 与 U+007F）一律丢弃。
            // 注意这也覆盖了制表符与换行 —— 换行既是分隔符又是控制字符，
            // 剔除它同时解决了"族名含分隔符"这个歧义源。
            if (ch.code < 0x20 || ch.code == 0x7F) continue
            builder.append(ch)
        }
        return builder.toString().trim()
    }

    /**
     * 转义：把反斜杠本身编成 `\\`，把真换行/回车编成 `\n` / `\r`（两字符）。
     *
     * 顺序**必须**是先处理反斜杠 —— 否则 `\` → `\\` 会把自己刚生成的
     * 转义序列再转义一遍，产生错误的加倍。这是转义类代码最经典的错误，
     * 故在实现里把反斜杠放在第一个 `when` 分支（而非依赖"先后两遍 replace"）。
     */
    private fun escape(record: String): String {
        val builder = StringBuilder(record.length)
        for (ch in record) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * [escape] 的逆运算。
     *
     * 关键容错：反斜杠**后跟未定义的字符时，保留反斜杠本身**（连它一起输出两个字符），
     * 而不是吞掉反斜杠。理由：手工改坏的配置里可能出现在反斜杠后跟 `x` 这类情况，
     * "吞字符"会静默改变族名内容，而"原样保留"至少让用户一眼看出哪里不对
     * （族名里有个多余的反斜杠，比"某字符凭空消失"好排查得多）。
     */
    private fun unescape(record: String): String {
        if (!record.contains('\\')) return record
        val builder = StringBuilder(record.length)
        var index = 0
        while (index < record.length) {
            val ch = record[index]
            if (ch != '\\' || index == record.length - 1) {
                builder.append(ch)
                index++
                continue
            }
            when (val next = record[index + 1]) {
                '\\' -> builder.append('\\')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                // 未定义转义：反斜杠与后一个字符**都保留**（见 KDoc 的容错说明）。
                else -> {
                    builder.append(ch)
                    builder.append(next)
                }
            }
            index += 2
        }
        return builder.toString()
    }
}
