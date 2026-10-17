// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

// ═══════════════════════════════════════════════════════════════════════════
// ★★★ 2026-10-10（审计 v4 · **D-1**）：**本文件整子系统【尚未接线】**（386 行）
// ═══════════════════════════════════════════════════════════════════════════
//
// ## 现状（★ 已实测，不是推测）
//
// ★ **生产调用点 = 0 · 探针调用点 = 0** —— `grep -rn "MenuSessionHistory\|beginSession\|endSession"`
//   在**本文件之外零命中** ✓
// ★ 本文件提供的 `MenuSessionHistory` / `beginSession` / `endSession` / `undo` / `redo` /
//   `hasCommittedEdits` / `shouldRecordInHistory` 等符号，**没有任何消费者**。
//
// ## 为什么会这样（★ 不是遗漏，是**有意延期**）
//
// ★ 立项 `recon/09` §9.7 定过「撤销栈只支持**菜单内撤销**」，且用户拍板过；
// ★ 但 **2026-09-15 v0.6.2 收窄首版范围**时把「**回退/撤销**」**延期**了
//   ⇒ ★ 实现留在生产源集里，**接线从未发生** ✓
//
// ## ★★ 处置：**标注，不删**（本项目对"延期需求"的一贯口径）
//
// ★ 理由：★ 它**不是死代码**（不是"写了没人要"），而是**已拍板、待接线的功能**；
//   ★ 删掉会把 `recon/09` §9.7 的设计一起删掉（将来恢复时要重新推导）✗
// ★ ⇒ ★ **接线点**（将来恢复时从这里开始）：
//   · 菜单的"撤销/重做"项（`MenuRegistry` 侧）
//   · `SettingsRuntime` 的变更发布路径（`SettingChange.Kind.APPLY` 已就位）
//
// ⚠️ **维护提示**：改本文件**不影响任何用户可见行为** ——
//   若你在排查"撤销没反应"，★ 那不是 bug，是**这个子系统还没接** ✓
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 一次设置变更的描述 —— 变更通知的载荷，也是撤销栈的记录单元。
 *
 * ## 为什么"一次拖动 = 一条记录"
 *
 * 用户拍板的回退粒度是"**拖动中间态不进栈，松手提交才进栈**"，
 * 即 `recon/09` §0 第 4 条「一次拖拽 = 一步撤销」。
 * 落到数据结构上：拖动中的每一次变更都是 [Kind.PUBLISH]（**不入栈**），
 * 松手那一次是 [Kind.APPLY]（**入栈**，且是**整批**入栈 ——
 * 若一次拖动同时改了 4 个键，4 个键合成一条记录，而不是 4 条）。
 *
 * ## 为什么记录"键集合"而不是"键→值"
 *
 * 撤销是按**整份快照**回放的（`recon/09` §3.1 快照式设计），
 * 记录里**不需要**存值 —— 值在快照里。这里存键集合只为两个用途：
 * ① 菜单撤销后**行高亮**要知道该闪哪几行（`recon/09` §3.5 的补偿手段）；
 * ② 渲染层据此判断派生资源粒度（改颜色不必重建字体度量）。
 * 存值反而会引出"记录里的值与快照不一致"这类不一致 bug。
 *
 * @param kind 本次变更的语义类别（决定订阅者是否应把它当"可撤销的一步"）
 * @param changedKeys 实际发生变化的键集合（**已排除值未变的键**）
 * @param preview 节流档位 —— 由 [SettingsRuntime] 取本次涉及键中最保守的一档，
 *   渲染层据此决定"当帧生效"还是"攒到 80 ms 网格边沿"（大纲 §9.7.2）
 */
data class SettingChange(
    val kind: Kind,
    val changedKeys: Set<String>,
    val preview: PreviewKind,
) {
    /**
     * 变更的语义类别。
     *
     * 区分 [PUBLISH] 与 [APPLY] 是本设计的核心：订阅者必须能回答
     * "我该不该把这次变更当成一条历史记录"。首版虽无撤销 UI，
     * 但这个区分**必须保留**（大纲 §9.7 纪律 1：两段式是将来加回退的唯一挂载点）。
     */
    enum class Kind {
        /** 拖动中：只改内存 [SettingsRuntime.live]，**不落盘、不入栈**。 */
        PUBLISH,

        /** 松手/失焦/回车：落盘 + **整批入栈**（一次拖动 = 一条记录）。 */
        APPLY,

        /** 撤销/重做回灌（[SettingsRuntime.restore]）。**绝不能再次入栈**（否则栈自噬）。 */
        UNDO,

        /** 放弃未保存的修改（回到 [SettingsRuntime.saved]）。**不入栈**。 */
        DISCARD,

        /**
         * **应用预设**（整份预设一次写入）。
         *
         * 为什么不复用 [APPLY]：联动订阅（`CdlRuntime.subscribeSettingCoupling`）
         * 必须**跳过**预设 —— 否则它会把预设刚写好的门控键再改一遍
         * （例：预设写 `cover.enabled=false` 但声明了 `cover.shape` ⇒ 联动改回 `true`）
         * ⇒ 用户点了预设，画面不是预设的样子，菜单勾也永远不亮。
         * 联动只服务「用户手点单个控件」，不服务「整份预设」。
         *
         * 同样**不入栈**（撤销栈目前未接线；将来接线时预设应记为一条带 label 的原子记录）。
         */
        PRESET,

        /** 外部改动（用户手改文件 / 宿主推送）。**不入栈** —— 它不是用户在本会话内的编辑。 */
        EXTERNAL,
    }

    /** 没有任何实际变化的变更（订阅者不应据此重算任何东西）。 */
    val isEmpty: Boolean get() = changedKeys.isEmpty()

    /** 本次变更涉及的设置项描述符（菜单行高亮、渲染层粒度判断用）。 */
    val descriptors: List<SettingDescriptor>
        get() = changedKeys.mapNotNull { SettingRegistry.descriptor(it) }

    /** 本次变更涉及的分组（设置窗口按分组重绘用）。 */
    val groups: Set<String>
        get() = descriptors.mapTo(LinkedHashSet()) { it.group }

    /**
     * 本次变更是否触及给定的**变更域**（键命名空间集合，见 [SettingDomains]）。
     *
     * ## 为什么订阅者要用它，而不是自己写 `changedKeys.any { it.startsWith(…) }`
     *
     * ① **唯一实现**：判定点散落各处时，"哪个键影响哪个消费者"这件事就有 N 份答案，
     *    改一处必然漏另一处；
     * ② **可机械守卫**：域集中在 [SettingDomains]，`SettingDomainProbe` 能断言
     *    "每个域名都命中 ≥1 个注册表键"（域变空 = 改名事故，本类静默失效的经典形态）；
     * ③ **防重蹈**：`changedKeys.any { it.startsWith("layout.") || … }` 这种字面量
     *    在设置项改名时**不报错**，只是判定恒 false ⇒ 联动静默失效（本项目已 3 次）。
     *
     * ## 语义
     *
     * 任一变更键落在任一名空间前缀下即为真 —— 即"**域被触及**"，不区分具体键。
     * 取超集是刻意的（见 [SettingDomains] 的取向说明）。
     *
     * @param namespaces 前缀集合，如 [SettingDomains.FRAME]；空集 ⇒ 恒 false
     */
    fun touchesAny(namespaces: Set<String>): Boolean =
        changedKeys.any { key -> namespaces.any { key.startsWith(it) } }

    /** 是否应当入撤销栈。**唯一的判定点** —— 避免"该不该记历史"的判断散落各处。 */
    val shouldRecordInHistory: Boolean
        get() = kind == Kind.APPLY && !isEmpty

    companion object {
        /** 无变化的变更。用 [isEmpty] 判定，不要用 `== null`。 */
        fun empty(before: SettingsSnapshot): SettingChange =
            SettingChange(Kind.PUBLISH, emptySet(), PreviewKind.IMMEDIATE)
    }
}

/**
 * 撤销栈 —— **菜单会话级**，随菜单面板开关而建/毁（用户 2026-09-15 拍板）。
 *
 * ## ⚠️ 与大纲 §9.7 的关系（**这是本实现唯一一处与大纲冲突的地方，必须写明**）
 *
 * 大纲 §9.7 明示「**回退小箭头延期**，`UndoStack` 不进首版」，而任务要求实现撤销栈。
 * 本实现的处置是**两者都满足**：
 *
 * | 大纲的要求 | 本实现的落地 |
 * |---|---|
 * | `publish`/`apply` 两段式**必须保留**（纪律 1，不可合并） | ✅ [SettingsRuntime] 严格两段式，且**注释里写死"不许合并"** |
 * | 设置项由 `SettingRegistry` 驱动（纪律 2） | ✅ 全部设置项都在注册表（[SettingRegistry.EXPECTED_TOTAL]），[SettingChange] 只带键名 |
 * | `recon/09` 规格不删（纪律 3） | ✅ `recon/09` 未改动；本类**逐条落实其 §3** |
 *
 * → 即：**数据结构就位但默认不接线**。设置窗口不挂撤销按钮，`Ctrl+Z` 不路由
 * （那两条属"UI 层接入"，是延期的那部分）；而**记录/回放能力本身是两段式的自然结果**，
 * 建起来后将来接 UI 只需挂两个按钮，**不必推翻写路径**。
 * 这与 `recon/09` 开头的"**规格保留、标为『已设计、未实施』**"一致 ——
 * 本类把"已设计"变成了"已实现且可测"，比只留文档更符合"防止『临时』变『永久』"的纪律。
 *
 * ## 快照式而非逆操作式（`recon/09` §3.1 的论证）
 *
 * 逆操作方案（"把字号从 26 改回 24"）在**用户连续改两次同一项**时会出错，
 * 且需要为每个字段写一份逆操作代码。快照式一次性消灭这类 bug ——
 * 本项目的 [SettingsSnapshot] 是 `Map` 浅拷贝，单份 ≈ 全部设置项个槽位，代价可忽略。
 *
 * ## 作用域：随菜单面板开关而建/毁
 *
 * [reset] 在菜单打开时调用，实例在菜单关闭时被**整个丢弃**
 * （不是清空，是丢实例 —— 语义更明确，且杜绝"上次会话残留"）。
 * 因此本类**刻意不提供持久化**，也不该被做成单例。
 *
 * @param limit 栈深。默认 200 —— 取值理由（`recon/09` §3.6）：**不是**内存（单快照
 *   约 1 KB，200 步 < 300 KB 完全可接受），而是"撤回 200 步以上没有交互意义"。
 */
class EditHistory(private var limit: Int = DEFAULT_LIMIT) {

    /** 一条历史格：状态 + 该状态的来源记录（基线格 `record == null`）。 */
    private data class Entry(val state: SettingsSnapshot, val record: SettingChange?)

    private val entries = ArrayList<Entry>(limit + 1)

    private var cursor = 0

    /**
     * 最近一次入栈的时间戳（毫秒）。仅用于 [mergeWindowMs] 的合并判断。
     *
     * ## 为什么需要"合并窗口"而非"每次 PSSH 都新建一步"
     *
     * 滑杆的拖动**不产生历史条目**（`recon/09` §0 第 4 条：`LIVE` 阶段根本不入栈），
     * 所以滑杆的合并是**自动的**。合并窗口只服务于**另一类连续输入**：
     * 数字框逐位输入、`StepperItem` 的**按住连点** —— 那些会**连续产生多次 `APPLY`**，
     * 若不合并，"按住 + 键 5 秒"会压进 100 步历史，撤销一下只退回 1 个单位，体验很糟。
     */
    private var lastCommitAtMs = 0L

    val canUndo: Boolean get() = cursor > 0

    val canRedo: Boolean get() = cursor < entries.lastIndex

    /** 当前状态（游标所指的快照）。**这是"应该长什么样"的唯一来源**。 */
    val state: SettingsSnapshot
        get() = entries.getOrNull(cursor)?.state ?: SettingsSnapshot.defaults()

    /** 待撤销的那一步涉及的键（菜单行高亮用）。基线格返回 `null`。 */
    val undoRecord: SettingChange?
        get() = if (canUndo) entries[cursor].record else null

    /** 待重做的那一步涉及的键。 */
    val redoRecord: SettingChange?
        get() = if (canRedo) entries[cursor + 1].record else null

    /** 当前步数（不含基线格）—— 供调试与状态栏显示。 */
    val size: Int get() = entries.size - 1

    /**
     * 建立基线并复位栈。**在菜单面板打开时调用**（`popupMenuWillBecomeVisible`）。
     *
     * 基线格（`entries[0]`，`record == null`）**永不被淘汰** —— 见 [commit] 的溢出处理。
     * 它保证"无论如何都能一路撤回到打开菜单那一刻"。
     */
    fun reset(baseline: SettingsSnapshot) {
        entries.clear()
        entries += Entry(baseline, null)
        cursor = 0
        lastCommitAtMs = 0L
    }

    /**
     * 把一次变更压入栈（一次拖动/一次松手 = 一条，**整批**）。
     *
     * @param state 提交后的**整份**状态（来自 [SettingsRuntime.live]，已是同一引用，
     *   故本类**不重复 publish** —— 见 `recon/09` §3.2 的桥接说明）
     * @param change 本次变更；`changedKeys` 非空才应调用
     * @param mergeWindowMs 连续输入的合并窗口。
     *   默认 400 ms —— `recon/09` §3.3 从 `recon/07` 的 800 ms **收紧**到 400 ms，
     *   理由：800 ms 会让"两次有意的独立调整"被误合并成一步，用户按撤销会觉得"跳多了"。
     * @return `true` = 已并入上一条（**UI 不必刷新撤销按钮状态**，因为步数没变）
     */
    fun commit(
        state: SettingsSnapshot,
        change: SettingChange,
        mergeWindowMs: Long = DEFAULT_MERGE_WINDOW_MS,
    ): Boolean {
        if (change.isEmpty) return false

        val head = entries.getOrNull(cursor)?.record
        val now = System.currentTimeMillis()

        // ★ 三道合并闸门，缺一不可（`recon/09` §3.2 用 `★` 标注）：
        //   ① 上一条存在且是离散型记录（滑杆的空 keyset 不会走到这里）
        //   ② 改的是**同一批键** —— 不同键的连续调整是不同意图，不该合并
        //   ③ **游标在末尾** —— 有 redo 分支时禁止合并，否则会覆盖 B 的状态却保留 C，
        //      产生"不可达的历史"（`recon/09` §3.4）
        val mergeable = head != null &&
            head.changedKeys == change.changedKeys &&
            cursor == entries.lastIndex &&
            now - lastCommitAtMs <= mergeWindowMs

        if (mergeable) {
            // 并入上一条：只更新状态与时间戳，**不增加步数**。
            entries[cursor] = Entry(state, head)
            lastCommitAtMs = now
            return true
        }

        // 截断 redo 分支：`Ctrl+Z` 后做新编辑 = 放弃 redo 链。
        // 这是所有编辑器的标准语义（`recon/09` §3.4），**不要试图保留**。
        if (cursor < entries.lastIndex) {
            entries.subList(cursor + 1, entries.size).clear()
        }

        entries += Entry(state, change)
        cursor = entries.lastIndex
        lastCommitAtMs = now

        // 溢出丢最老的，**但永远保留基线格**（`entries[0]`）——
        // 故上限是 `limit + 1`（含基线）。`cursor--` 与 `removeAt(0)` 必须配对，
        // 否则游标会指错（`recon/09` §3.6 明确要求这一对）。
        while (entries.size > limit + 1) {
            entries.removeAt(0)
            cursor--
        }
        return false
    }

    /**
     * 撤销一步。
     *
     * ⚠️ **调用方必须把返回值送去 [SettingsRuntime.restore]** —— 直接赋给 [SettingsRuntime.live]
     * 会导致 `version` 不递增、渲染层派生资源不失效，用户看到"撤销了但画面没变"
     * （`recon/09` §3.2 标 `★` 的最易错点）。
     *
     * @return 回退到的状态；已无可撤销时返回 `null`
     */
    fun undo(): SettingsSnapshot? = if (canUndo) entries[--cursor].state else null

    /** 重做一步。返回值同样必须走 [SettingsRuntime.restore]。 */
    fun redo(): SettingsSnapshot? = if (canRedo) entries[++cursor].state else null

    /**
     * **调整栈深** —— 立即按新上限 trim，且**不动游标语义**（`cursor--` 与 `removeAt(0)` 配对）。
     *
     * ## ⚠️ 2026-09-23 修两处（**这一行 KDoc 里原先有两个假声明**）
     *
     * **① 本函数原先完全不生效** ✗
     * `limit` 是构造 **`val`**，而 `resize` 只 trim 了 `entries` ——
     * 截断用的却是 `limit`（`:218` 的 `while (entries.size > limit + 1)`）⇒
     * **下一次 `push` 立刻按旧上限截断** ⇒ "调整栈深"这个能力**实际不存在** ✗。
     * ⇒ 改为 **`var`**，并**先改 `limit` 再 trim** ✓。
     *
     * **② KDoc 原写「（用户在高级设置里改）」** ✗ —— **那个设置项不存在** ✓：
     * 全库 grep「撤销 / 栈深」在 `SettingRegistry` 里 **0 命中**；
     * `.resize(` 也 **0 调用** ✓。⇒ 如实改为"**目前没有消费点，是预留接缝**" ✓。
     * **不要写一个不存在的设置** —— 本项目纪律：**文档与事实相反是危险的一类错**。
     */
    fun resize(newLimit: Int) {
        require(newLimit >= MIN_LIMIT) { "栈深不得小于 $MIN_LIMIT：$newLimit" }
        // ★★★ 2026-10-07 **修复（`migration/98` · E-2）**：**上界原来从未生效** ——
        //   `MAX_LIMIT` 的 KDoc 写着「可配范围上界」，而本函数**只判了下界**，
        //   ⇒ 传 `Int.MAX_VALUE` 也会被接受（无界增长）。★ 现已补上。
        require(newLimit <= MAX_LIMIT) { "栈深不得大于 $MAX_LIMIT：$newLimit" }
        // ⚠️ 必须是 `==` 而不是 `>=`：**变大也要生效**（旧写法的 `>= limit` 让"调大"静默无效 ✗）。
        if (newLimit == limit) return
        limit = newLimit
        while (entries.size > limit + 1) {
            entries.removeAt(0)
            cursor--
        }
    }

    companion object {
        /** `recon/09` §3.6：默认 200 步。 */
        const val DEFAULT_LIMIT = 200

        /** `recon/09` §3.6：可配范围下界。 */
        const val MIN_LIMIT = 50

        /** `recon/09` §3.6：可配范围上界。 */
        const val MAX_LIMIT = 500

        /**
         * 连续输入的合并窗口。**400 ms** —— `recon/09` §3.3 从 `recon/07` 的 800 ms 收紧，
         * 理由见 [commit] 的参数说明。
         */
        const val DEFAULT_MERGE_WINDOW_MS = 400L
    }
}

/**
 * 菜单会话级的撤销栈持有者 —— **随菜单面板开关而建/毁**。
 *
 * ## 为什么要有这个包装类（不直接用 [EditHistory] 的 `var`）
 *
 * "建/毁"需要一个**明确的开关点**，且这个点必须能被 UI 层的
 * `popupMenuWillBecomeVisible` / `popupMenuWillBecomeInvisible` 一对回调直接映射。
 * 用 `var history: EditHistory?` 表达的话，"菜单关了"只是把变量设为 `null`，
 * 而"菜单又开了"需要重新 `reset` —— 两处手写容易漏一处（漏了就会跨会话残留历史）。
 * 本类把这对操作收敛成 [beginSession] / [endSession] 两个原子动作。
 *
 * ## ⚠️ 两个"收起"必须区分（`recon/09` §3.5.1，自洽性的关键）
 *
 * | 场景 | 本类的处置 |
 * |---|---|
 * | **A. 菜单面板整体关闭**（点到菜单外 / `Esc`） | [endSession] → **栈销毁**。**不补 COMMIT** |
 * | **B. 菜单内一个内联面板收起**（如取色器折叠），**菜单还开着** | **保持会话**，由 UI 层补一次 `apply()`（若期间只发生过 `publish`） |
 *
 * B 若不补 `apply`，同一次菜单会话内的撤销会**缺一步**（用户视线里菜单一直没关，历史却漏了）。
 * 本类**不自动处理 B** —— 因为"有没有发生未提交的 live 变更"只有 UI 层知道，
 * 但 [hasUncommittedSessionEdits] 提供了判定入口，便于 UI 层实现 B 的补偿。
 */
class MenuSessionHistory {

    private var history: EditHistory? = null

    /** 会话是否进行中（菜单面板开着）。 */
    val isActive: Boolean get() = history != null

    /** 当前会话的栈（未开会话时 `null`）。**UI 层读它来刷新撤销/重做按钮的可用态**。 */
    val current: EditHistory? get() = history

    /**
     * 开始一次菜单会话：**新建**栈并以 [baseline] 为基线。
     *
     * 若上次会话没正常结束（[endSession] 未被调用），这里会**直接丢弃旧栈**再建新的 ——
     * 与"跨菜单会话不保留"的拍板一致，也顺带修掉"回调没成对触发"的残留。
     */
    fun beginSession(baseline: SettingsSnapshot, limit: Int = EditHistory.DEFAULT_LIMIT): EditHistory {
        val fresh = EditHistory(limit)
        fresh.reset(baseline)
        history = fresh
        return fresh
    }

    /**
     * 结束会话：**丢弃整个栈实例**。
     *
     * 刻意"丢实例"而不是"清空" —— 语义更明确（会话结束后不存在任何历史），
     * 也杜绝将来有人在别处持有引用导致跨会话泄漏。
     */
    fun endSession() {
        history = null
    }

    /**
     * 本会话内是否发生过**未提交**的编辑（只有 `publish` 没有 `apply`）。
     *
     * 供 UI 层实现"场景 B"的补偿：菜单内联面板收起时，
     * 若返回 `true`，UI 层应补一次 [SettingsRuntime.apply]。
     *
     * 判定方式：栈里最后一条记录的时间戳晚于本次会话开始……
     * 更直接的等价判定是"栈里没有任何 APPLY 记录，但 live 与基线不同"——
     * 不过这需要读 [SettingsRuntime]，故本类不做这个判断，只暴露"这个会话有没有记录过"。
     */
    val hasCommittedEdits: Boolean
        get() = (history?.size ?: 0) > 0

    /**
     * 本会话内**是否可能**存在未提交的 live 变更 —— 保守返回 `true`。
     *
     * 完全准确的判断需要比较 `live` 与栈顶状态，那要求本类依赖 [SettingsRuntime]，
     * 而 `recon/09` §4.1 的分层规则明确要求"**历史栈不反向依赖 `SettingsRuntime`**"。
     * 故这里只给出保守信号，由 UI 层（它两者都可见）做最终判断。
     */
    val mayHaveUncommittedEdits: Boolean get() = isActive
}
