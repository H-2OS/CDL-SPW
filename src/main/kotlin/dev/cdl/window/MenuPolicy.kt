// SPDX-License-Identifier: AGPL-3.0-only

/**
 * 右键菜单**步进器**的交互参数与纯函数。
 *
 * ## 为什么单独一个文件
 *
 * 与 `IslandPolicy.kt` / `render/SpectrumGeometry.kt` **同一条纪律**：
 * `IslandMenuSkin` 是 `internal`，探针是独立编译单元看不到 ——
 * **为测试发愁先抽纯函数，而不是改可见性**（见 §14.14）。
 *
 * 而且这几个常量之间有一条**容易写错的约束**（见 [STEPPER_ACCEL_STAGE1_MS]），
 * 放在这里才能被断言钉住。
 *
 * ## 用户原话（本轮诉求，逐字）
 *
 * > 「**步进器没有做到最细粒度**（数值增减逻辑可参考 0.8.9）」
 *
 * 拆成两半，**必须同时做**：
 *
 * | 一半 | 落点 | 缺了它会怎样 |
 * | --- | --- | --- |
 * | **点一下 = 明确一步** | ★ 步长 = `SettingDescriptor.step`（**默认 1**，域大的项可设 10） | 域大 + 步长 1 ⇒ 点 20 下看不出来（**BUG-198**） |
 * | **按住不放越来越快** | [stepperAccelerationFactor] + 下面几个常量 | `0..10000` 量级的项按到手酸 |
 *
 * > **纪律：用"降低精度"换"少点几次"是拿错了工具 —— 该用加速。**
 * > ★ 2026-10-14 补：★ 而"**量纲跨度大**"是另一件事 —— 那时该调
 * >   `SettingDescriptor.step`（**精度不丢**：内联编辑仍可打任意整数），**不是**恢复全局表。
 */

package dev.cdl.window

import dev.cdl.settings.SettingDescriptor
import dev.cdl.settings.SettingGroups
import dev.cdl.settings.SettingKind
import dev.cdl.settings.SettingRegistry

/**
 * 连点开始前的**等待**（毫秒）—— 即"按住多久才进入连点"。
 *
 * 取 400：Windows 微调框的通行量级。太短（< 200）会让"想点一下"变成"点了两下"；
 * 太长（> 800）会让用户以为按住没用。
 */
const val STEPPER_REPEAT_DELAY_MS = 400

/**
 * 连点期间**每次步进的间隔**（毫秒）。
 *
 * 取 110：约 9 步/秒。配合 [STEPPER_ACCEL_FACTOR1] 的第一档加速
 * （1 s 后 ×5 → 约 45 单位/秒）足以走完 `0..100` 量级。
 */
const val STEPPER_REPEAT_INTERVAL_MS = 110L

/**
 * 进入**第一档加速**所需的连点时长（毫秒）。
 *
 * ## ⚠️ 必须明显大于 [STEPPER_REPEAT_DELAY_MS]（这是本文件唯一容易写错的约束）
 *
 * 若 `STAGE1 ≤ REPEAT_DELAY`，那么"刚进入连点就已经加速" ——
 * 用户按一下稍微久一点（刚过 400 ms）就跳了 5 格，
 * 而"点一下 = 精确 ±1"的保证就**形同虚设**（他以为自己只点了一下）。
 *
 * 取 1000（= 2.5 × 400）：给用户留出"按住但还没想加速"的窗口。
 */
const val STEPPER_ACCEL_STAGE1_MS = 1000L

/**
 * 进入**第二档加速**所需的连点时长（毫秒）。
 *
 * 取 2500：第一档（×5）用 1 s 走到，第二档（×20）再用 1.5 s ——
 * 这个节奏让"想跨大量"的用户在约 2.5 秒内拿到 20 倍速，
 * 而不必先经历一段"慢得让人怀疑没生效"的过程。
 */
const val STEPPER_ACCEL_STAGE2_MS = 2500L

/** 第一档加速倍数（用于 `0..100` 量级）。 */
const val STEPPER_ACCEL_FACTOR1 = 5

/** 第二档加速倍数（用于 `0..10000` 量级，如 `controls.autoHideMs`）。 */
const val STEPPER_ACCEL_FACTOR2 = 20

/**
 * 连点已持续 [elapsedMs] 时的**加速倍数**。
 *
 * ## 为什么是"阶梯"而不是连续增长
 *
 * 连续增长（如 `factor = 1 + elapsed/100`）会让"按住 1.3 秒"与"按住 1.4 秒"
 * 落在不同的倍数上，用户**无法复现**上一次的操作 —— 想再走同样的量只能凭感觉。
 * 阶梯（[STEPPER_ACCEL_STAGE1_MS] / [STEPPER_ACCEL_STAGE2_MS] 两档）
 * 让"按住大约一秒"这类操作有**确定的含义**。
 *
 * ## 单调性
 *
 * 对 `elapsedMs` **单调不减**：按得越久，倍数越大（永不回落）。
 * 这是"按住不放"应有的语义；若实现写成"到点重置"，用户会看到
 * "按住 → 变快 → 又变慢"的抖动。
 *
 * @param elapsedMs 从**进入连点**算起的毫秒数（`0` = 刚进入，此时恒为 1）
 * @return 加速倍数（≥1）
 */
fun stepperAccelerationFactor(elapsedMs: Long): Int = when {
    elapsedMs >= STEPPER_ACCEL_STAGE2_MS -> STEPPER_ACCEL_FACTOR2
    elapsedMs >= STEPPER_ACCEL_STAGE1_MS -> STEPPER_ACCEL_FACTOR1
    else -> 1
}

// ─────────────────────────────────────────────────────────────────────────────
// 右键菜单的**组件分区**（2026-09-23：4 个"意图桶" → **组件**，用户要求"按组件来分组"）
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话（本轮诉求，逐字）
//
// > 「右键菜单重构，**按组件来分组**（**第一级菜单**放词岛穿透，投喂作者，源仓库跳转，
// >   更多设置，预设列表等**常用/需明示**的项。**二级菜单**比如更多设置的子菜单，
// >   该子菜单中**以组件名为选项名**，再接着展出**第三级菜单**，
// >   显示组件下的设置项）」
//
// ## 旧设计（4 桶，按"用户想干什么"切）**已废弃** —— 处置与理由
//
// 2026-09-20 曾把菜单树重设计成「5 桶 → 4 桶」，判据是**意图**：
// `"文字与颜色" / "外观与位置" / "元素与控件" / "行为与设置"`（完整的取舍论证见 git 历史）。
// 本轮**整体废弃**它 —— 不是"又换了个口味"，而是它的判据与用户新要求**直接冲突**：
//
// | | 旧 4 桶（判据 = 意图） | 用户新要求（判据 = 组件） |
// | --- | --- | --- |
// | 第二层叫什么 | 意图（"文字与颜色"） | **组件名**（"歌词"） |
// | 层数 | 更多设置 ▸ 桶 ▸ 组件 ▸ 项（**四层**） | 更多设置 ▸ 组件 ▸ 项（**三层**） |
// | 桶名 vs 组名 | **语义重叠**（桶"外观与字体"里又是"字体"）→ 读两遍 | 不重叠（就是组件名） |
//
// ⚠️ **但旧设计最值钱的那部分被保留了下来**：不是"怎么分桶"，而是那条护栏 ——
// 「**手写的结构表必须被断言"覆盖全部注册表分组"**」。它拦住的是 BUG-113 那一类
// "重分组后菜单里整组静默消失"的缺陷（不报错、不崩溃，只能等用户报"某项找不到了"）。
// 现把这条护栏**原样**搬到组件分区表上：表里漏一个组件 → 两个探针当场红。
//
// ## 为什么留一张**手写**表，而不是让菜单直接读 `SettingGroups.ALL`
//
// 直接读 `ALL` 时，"二级菜单就是这 10 个组件、名字与顺序都一致"这条断言**写不出来** ——
// 只能写成 `ALL == ALL`（恒真 = 空护栏）。留一张**独立写出来的**表之后，
// 探针才能断言「**本表 == `SettingGroups.ALL`（逐字 + 顺序）**」：
// 改名、漏写、写错顺序**都会当场红**。
//
// > **本项目已 3 次栽在"改名后联动静默失效"上**（最严重的一次是旧的 `MENU_BUCKETS`
// > 里逐条列着 10 个**已不存在**的组名 ⇒ 那 10 个子菜单整体空掉）。
// > 故"名字必须逐字一致"这件事**必须由机械断言守**，不能靠人对齐。
//
// ⚠️ **加新组件时必须同步这里** —— 与旧桶表同一条纪律：
// `IslandPopupMenu` 的构造期自检会打 stderr 告警，两个探针会把它变成**红色断言**。
//
// ── ★★ 2026-10-08（U12c）：8 → **11** 个 ────────────────────────────────────
//
// 用户原话：「**每组最好不超30项，但总的分组不要过多**」。
// 拆出 3 个新组件（`歌词颜色与描边` / `词岛外观` / `词岛材质`），详见
// `SettingGroups` 各常量的 KDoc。本表随之 8 → 11。
//
// ⚠️ **顺序不是随手排的** —— 两条约束（与 `SettingGroups.ALL` 的 KDoc 同源）：
//   ① 歌词两族**相邻**（`LYRIC` / `LYRIC_COLOR_OUTLINE`）；
//   ② 词岛三族**相邻**（`ISLAND` / `ISLAND_APPEARANCE` / `ISLAND_MATERIAL`）。
// ★ 这两条**能**被 §A6e / §J3 的"逐字逐序"断言守住（`ALL` 也是这个顺序）——
//   那两条断言正是防"本表漏一个组件 ⇒ 该组在菜单里静默消失"（BUG-113 那类）。
//
// ── ★★★ 2026-10-17：11 → **10** 个（撤销「总开关」组）──────────────────────
//
// 用户原话：「**取消总分组，将其下发回各自的组。**」
// ⇒ 本表**删掉**第 0 项 `TOP_LEVEL`，其余 10 项不动（顺序也不动）——
//   于是二级菜单**恰好就是 10 个组件**，与 `SettingGroups.ALL` 继续逐字同序 ✓
val MENU_COMPONENT_SECTIONS: List<String> = listOf(
    // 以下 10 项**全部是组件**（第 0 项不再是"总开关"特例组 —— 2026-10-17 已撤销）。
    // 名字与顺序必须与 `SettingGroups.ALL` **逐字一致** ——
    // 由 `MenuProbe` 的「命名空间断言」与 `MenuRegistrySyncProbe` §A6 共同守。
    SettingGroups.LYRIC,
    SettingGroups.LYRIC_COLOR_OUTLINE,
    SettingGroups.ISLAND,
    SettingGroups.ISLAND_APPEARANCE,
    SettingGroups.ISLAND_MATERIAL,
    SettingGroups.SPECTRUM,
    SettingGroups.COVER,
    SettingGroups.CONTROLS,
    SettingGroups.ANIMATION,
    SettingGroups.MENU_SETTINGS,
)

/**
 * 某个 `SettingKind` 能否在**右键菜单**里表达。
 *
 * ## 这张表是"菜单里少了几项"这类缺陷的判据
 *
 * | kind | 菜单里怎么表达 | 能吗 |
 * | --- | --- | --- |
 * | `SWITCH` | 勾选菜单项 | ✅ |
 * | `SEEKBAR` | `− 值 +` 步进器（[IslandMenuSkin.MenuStepper]） | ✅ |
 * | `LIST` | 单选子菜单 | ✅ |
 * | `COLOR` / `FONT` / `EDITTEXT` | —— | ❌ 见下 |
 *
 * `COLOR` / `FONT` / `EDITTEXT` **不进菜单**是有意的：
 * · `COLOR`（当前只有 `spectrum.color`）：菜单里没有取色控件，
 *   放进菜单只能再开一个窗口 —— 而用户明确否掉了"引去其他窗口"；
 * · `FONT`：候选是**本机 250 个字体族**，菜单放不下（放进去会变成 250 项的长列表）；
 * · `EDITTEXT`：菜单没有文本输入。
 *
 * 三者都由 **SPW 设置页**承载（`preferenceEditor` 那条路）。
 * 故"可达性"要**两处合起来看** —— 见 `MenuProbe` 的断言：
 * **每个注册表键都必须能在菜单或设置页里至少一处改到。**
 */
fun menuSupportsKind(kind: SettingKind): Boolean = when (kind) {
    SettingKind.SWITCH, SettingKind.SEEKBAR, SettingKind.LIST -> true
    SettingKind.COLOR, SettingKind.FONT, SettingKind.EDITTEXT -> false
}

/**
 * 注册表里**不是用户设置项**的键 —— 插件自己的内部状态。
 *
 * ## 为什么要把它们单独列出来（而不是靠"看起来像设置项"去猜）
 *
 * 注册表是**设置系统**的存储表，而设置系统也顺带承载了两项**内部状态**：
 *
 * | 键 | 它是什么 | 谁写它 |
 * | --- | --- | --- |
 * | `window.position` | 词岛被拖到哪了（`"x,y"`） | **拖动**（`persistPosition`），不是用户"设置" |
 * | `main_lyric.font.recent` | 近期用过的字体族（历史） | 字体选择时自动记录 |
 *
 * 它们进注册表是为了**持久化**（那是设置系统唯一的落盘通道），
 * 但它们**不是"用户该去改的东西"** —— 所以"可达性"检查必须把它们排除，
 * 否则会得到一条"永远红"的断言，而那种断言很快会被无视。
 *
 * ## 为什么做成显式集合而不是"按 kind 排除"
 *
 * 它们的 kind 恰好是 `EDITTEXT` / `LIST`，但**同类里还有真设置项**
 * （`spectrum.color` 是 EDITTEXT、其余 LIST 都是真枚举）——
 * 按 kind 排除会把真设置项一起漏掉。**只能按键名列**。
 *
 * ⚠️ **加新键时若它也是内部状态，必须加进这里**；
 * 否则 `MenuProbe` 会报"某键两处都改不到" —— 那条红正是为了逼你做出选择：
 * **要么让它可编辑，要么承认它是内部状态并登记在此。**
 */
val NON_SETTING_KEYS: Set<String> = setOf(
    "window.position",
    "main_lyric.font.recent",
)

/**
 * 状态字形（勾选 / 单选圆点）的 x 坐标 —— **保证不与标签重叠**。
 *
 * ## 用户原话（本轮诉求）
 *
 * > 「没有做到**避免元素重叠**（字与步进器，**范围提示，勾选状态提示**）」
 *
 * ## 原记与实为
 *
 * **原记**（照抄原实现 `089:ui/Windows11PopupStyle.kt:353-361`）：
 * ```
 * left  = rowStart + labelEnd + rangeGap
 * right = itemWidth - middleColumnInset
 * x     = left + (right - left - glyphBox) / 2
 * ```
 *
 * **实为**：该式**假设标签不会太长**。标签一长，`left` 就超过 `right`，
 * `(right - left - glyphBox)` 变成负数 → 字形被推到**标签上面**。
 * 用户看到的正是"字和勾号叠在一起"。
 *
 * ## 现做法：把 `left` 夹到"不越界"，并由调用方**保证标签不会长到那里**
 *
 * 本函数只做**夹取**（纯几何）；"标签不会长到那里"由调用方负责 ——
 * 状态项把**右边距**留出 `middleColumnInset + glyphBox + rangeGap`，
 * 于是 LAF 画标签时**自带省略号**（Swing 的 `layoutCompoundLabel` 会 `clipString`），
 * 永远到不了中间列。
 *
 * ⚠️ **两处必须配套**：只夹 `left` 而不留边距，标签仍会压到字形上
 * （因为 `labelEnd` 已经越过了 `right`，夹完只是"压在字形左边缘"）。
 * 故 [MenuProbe] 同时断言"**字形左边缘 ≥ 标签右边缘 + rangeGap**"这条不变量。
 *
 * @param itemWidth 菜单行宽
 * @param labelEnd 标签右边缘（`rowStart + 标签宽`）
 * @param columnInset 中间列右侧预留（[middleColumnInset] 的语义）
 * @param glyphWidth 字形宽度
 * @param rangeGap 标签与中间列之间的间隙
 */
fun middleColumnGlyphX(
    itemWidth: Int,
    labelEnd: Int,
    columnInset: Int,
    glyphWidth: Int,
    rangeGap: Int,
): Int {
    val right = itemWidth - columnInset
    // 夹到 `right - glyphWidth`：再往右字形就出列了。
    val left = (labelEnd + rangeGap).coerceAtMost(right - glyphWidth)
    val x = left + (right - left - glyphWidth) / 2
    // ⚠️ 最后再夹一次"不为负"：极窄行（`itemWidth < columnInset`，现实中由
    //    `minMenuWidth = 180` 挡住，但**手工构造的输入**能造出来）会让上面算出负数，
    //    那会把字形画到行外（视觉上是"菜单左边飘着一个勾"）。
    //    这是**防御**而不是修 bug —— 探针里那条"行宽为 0 不为负"的断言就是为此写的。
    return x.coerceAtLeast(0)
}

/**
 * 把 [text] 截到**不超过 [maxWidth]**，超长时以 `…` 结尾。
 *
 * ## 为什么自己写（而不是用 Swing 的现成方法）
 *
 * Swing 里能"按宽度截字符串"的入口（`SwingUtilities.clipString` 一族）
 * **不是稳定可用的公开 API** —— 实测 `SwingUtilities.clipString(FontMetrics, String, Int)`
 * **不存在**（编译期 `Unresolved reference`）。与其去猜哪个重载可用，
 * 不如写 12 行 —— 而且写成**纯函数**之后，它**可以被探针断言**
 * （那才是真正想要的性质：重叠这种缺陷必须能被拦）。
 *
 * ## 宽度由调用方注入（`widthOf`）
 *
 * 本函数**不碰 `FontMetrics`** —— 那样它就能在探针里用"每字符 10px"这种假度量断言，
 * 不需要真实字体。这是本项目"抽纯函数"纪律的同一做法。
 *
 * ## ⚠️ 不能切在代理对中间
 *
 * `String.length` 是**码元**数，一个 emoji / 生僻字占**两个**码元。
 * 若切点落在高代理（`\uD800..\uDBFF`）之后，会得到一个**半个字符** ——
 * 画出来是"豆腐块"，而且**只在含 emoji 的标签上出现**。
 * 故切点回退一格。本项目在 BUG-022 上踩过同类问题（把"码元"当成"字符"）。
 *
 * @param maxWidth 可用宽度（像素）。`<= 0` 时返回空串（什么都不放得下）
 * @param widthOf 度量函数（探针可注入假实现）
 * @return 截断后的文本；**保证 `widthOf(结果) <= maxWidth`**（`maxWidth > 0` 且 `…` 本身放得下时）
 */
fun ellipsize(text: String, maxWidth: Int, widthOf: (String) -> Int): String {
    if (maxWidth <= 0) return ""
    if (widthOf(text) <= maxWidth) return text
    val ellipsis = "…"
    if (widthOf(ellipsis) > maxWidth) return ""
    var end = text.length
    while (end > 0) {
        // 切点落在高代理之后 → 会切出半个字符，回退一格。
        if (text[end - 1].isHighSurrogate()) {
            end--
            continue
        }
        val candidate = text.substring(0, end) + ellipsis
        if (widthOf(candidate) <= maxWidth) return candidate
        end--
    }
    return ellipsis
}

// ─────────────────────────────────────────────────────────────────────────────
// 步进器的**内联数值编辑**（2026-09-17，对齐原实现 0.8.9）
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话
//
// > 「右键菜单的交互逻辑没有做到灵动岛 0.8.9 的**允许对步进器内数值编辑**」
//
// ## ★ 原实现怎么做的（这条**决定了整个实现形态**，不是可选的风格）
//
// 原实现 `StepperItem.kt` 的 KDoc 明确写着：
//
// > 「a child `JTextField` (or a Swing key listener) would **silently drop every
// > character**」—— 在 popup menu 里，子组件的 `JTextField` 会**静默丢掉每一个按键**。
//
// 所以它**不用 `JTextField`**，而是：
// ① 注册一个**全局 `KeyEventDispatcher`**（`KeyboardFocusManager`），
//    由一个静态 dispatcher 把按键路由给"当前在编辑的那一个步进器"；
// ② 自己维护一个**字符串缓冲 + 光标 + 选区**；
// ③ `isFocusable` **保持 false**（否则菜单的键盘导航会断）。
//
// 本文件把这些**纯逻辑**抽出来（原实现是混在组件里的）——
// 于是"选区替换 / 光标移动 / 退格 / 越界夹取"这些最容易写错的部分
// **可以被探针穷举断言**。`MenuStepper` 只负责"接线 + 画光标"。
//
// ⚠️ 原实现还有一条 **Win32 低级键盘钩子**，用于"按键根本没到本进程"的情形。
//    **本实现没有它** —— 见 `MenuStepper` 的 KDoc（如实写明后果）。

/** 内联编辑器的状态。不可变 —— 每次按键产出一个新值，便于断言"改了什么"。 */
data class StepperEditor(
    val buffer: String,
    val caret: Int,
    val anchor: Int,
) {
    /** 选区起点（`caret` 与 `anchor` 的较小者）。 */
    val selectionStart: Int get() = minOf(caret, anchor)

    /** 选区终点（不含）。 */
    val selectionEnd: Int get() = maxOf(caret, anchor)

    /** 是否有选区（有则下一次输入会**替换**它）。 */
    val hasSelection: Boolean get() = caret != anchor
}

/** 一次按键之后**该做什么** —— 让调用方（`MenuStepper`）决定是否提交/收尾。 */
enum class StepperEditSignal {
    /** 继续编辑（重绘即可）。 */
    CONTINUE,

    /** 提交缓冲里的值并关闭编辑器（`Enter`）。 */
    COMMIT,

    /** 放弃改动并关闭编辑器（`Esc`）。 */
    CANCEL,
}

/**
 * 打开编辑器。
 *
 * @param value 当前值（转成十进制文本）
 * @param selectAll 是否全选。原实现在**点击数值**时全选（于是直接输入就是替换），
 *   在**再次点击**时重新全选。全选让"改一个数"变成"直接打新数"，少一步删除。
 */
fun stepperEditorOpen(value: Int, selectAll: Boolean): StepperEditor {
    val text = value.toString()
    return if (selectAll) {
        StepperEditor(buffer = text, caret = text.length, anchor = 0)
    } else {
        StepperEditor(buffer = text, caret = text.length, anchor = text.length)
    }
}

/**
 * 输入一个**字符**（来自 `KEY_TYPED`）。
 *
 * ## 只接受数字与（允许时）负号
 *
 * 其它字符**一律不改状态** —— 调用方仍会**吞掉**它（编辑期间可打印字符
 * 不能漏到菜单后面的播放器窗口）。
 *
 * ## 负号：**在光标处插入**（有选区则替换），与原实现同法
 *
 * **原记**（我第一版）：只在"光标在开头且还没有负号"时接受负号 ——
 * 想着"这样不会打出 `--5` 这种解析不了的东西"。
 *
 * **实为**：那条限制会让用户在**别处**按 `-` 时**按键被静默吞掉** ——
 * 用户看到"我按了，什么都没发生"，比"打进去了但提交时解析失败"更让人困惑
 * （后者至少有可见的反馈）。而原实现就是"插在光标处"（`replaceSelection("-")`）。
 *
 * **故改为与原实现一致**：插在光标处。垃圾输入由 [stepperEditorCommitValue] 兜住 ——
 * 解析不了就返回 `null`，**不改设置**，不会写坏任何东西。
 *
 * @param allowNegative 该设置项的 `min < 0` 时才允许负号 ——
 *   否则用户能打出一个永远不会生效的负值（写入侧会夹回）。
 */
fun stepperEditorType(editor: StepperEditor, typed: Char, allowNegative: Boolean): StepperEditor {
    if (typed in '0'..'9') return replaceSelection(editor, typed.toString())
    if (typed == '-' && allowNegative) return replaceSelection(editor, "-")
    return editor
}

/**
 * 处理一个**功能键**（来自 `KEY_PRESSED`）。
 *
 * @param keyCode `java.awt.event.KeyEvent.VK_*`
 * @param ctrlDown 是否按住 Ctrl（只有 `Ctrl+A` 用它）
 * @return `(该做什么, 新状态)`
 */
fun stepperEditorPress(
    editor: StepperEditor,
    keyCode: Int,
    ctrlDown: Boolean,
): Pair<StepperEditSignal, StepperEditor> {
    val continueWith = { e: StepperEditor -> StepperEditSignal.CONTINUE to e }
    return when {
        // Ctrl+A 全选（与真实文本框一致）。
        keyCode == java.awt.event.KeyEvent.VK_A && ctrlDown ->
            continueWith(editor.copy(caret = editor.buffer.length, anchor = 0))

        keyCode == java.awt.event.KeyEvent.VK_ENTER ->
            StepperEditSignal.COMMIT to editor

        keyCode == java.awt.event.KeyEvent.VK_ESCAPE ->
            StepperEditSignal.CANCEL to editor

        keyCode == java.awt.event.KeyEvent.VK_LEFT -> {
            // 有选区时"左"= 收起到选区起点（文本框的通行行为）。
            val target = if (editor.hasSelection) editor.selectionStart else (editor.caret - 1)
            continueWith(editor.copy(caret = target.coerceIn(0, editor.buffer.length), anchor = target.coerceIn(0, editor.buffer.length)))
        }

        keyCode == java.awt.event.KeyEvent.VK_RIGHT -> {
            val target = if (editor.hasSelection) editor.selectionEnd else (editor.caret + 1)
            continueWith(editor.copy(caret = target.coerceIn(0, editor.buffer.length), anchor = target.coerceIn(0, editor.buffer.length)))
        }

        keyCode == java.awt.event.KeyEvent.VK_HOME ->
            continueWith(editor.copy(caret = 0, anchor = 0))

        keyCode == java.awt.event.KeyEvent.VK_END ->
            continueWith(editor.copy(caret = editor.buffer.length, anchor = editor.buffer.length))

        keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE -> {
            // 有选区 → 删选区；无选区 → 删光标前一个字符。
            if (editor.hasSelection) {
                continueWith(replaceSelection(editor, ""))
            } else if (editor.caret > 0) {
                val next = editor.buffer.removeRange(editor.caret - 1, editor.caret)
                continueWith(StepperEditor(next, editor.caret - 1, editor.caret - 1))
            } else {
                continueWith(editor)
            }
        }

        keyCode == java.awt.event.KeyEvent.VK_DELETE -> {
            if (editor.hasSelection) {
                continueWith(replaceSelection(editor, ""))
            } else if (editor.caret < editor.buffer.length) {
                val next = editor.buffer.removeRange(editor.caret, editor.caret + 1)
                continueWith(StepperEditor(next, editor.caret, editor.caret))
            } else {
                continueWith(editor)
            }
        }

        else -> continueWith(editor)
    }
}

/**
 * 提交时把缓冲解析成整数。
 *
 * ## 为什么返回 `null` 而不是"解析失败就用旧值"
 *
 * `null` 让调用方**什么都不做** —— 那正是"用户打了一串解析不了的东西然后回车"应有的结果：
 * **不改设置**（而不是改成一个猜测值）。空串与只有 `-` 也归入 `null`。
 *
 * ## 为什么用 `toDoubleOrNull` 再 `roundToInt`
 *
 * 与原实现同法：宿主滑杆写盘是浮点（本项目 BUG-005），故用户可能打出 `22.0` 这种。
 * `toIntOrNull` 会直接失败；`toDoubleOrNull` + `roundToInt` 能接住。
 * **越界不在这里夹** —— 由写入侧（`SettingDescriptor.coerce` / `MenuStepper.step`）
 * 统一夹，避免两处夹取规则漂移。
 */
fun stepperEditorCommitValue(buffer: String): Int? {
    val trimmed = buffer.trim()
    if (trimmed.isEmpty() || trimmed == "-") return null
    val parsed = trimmed.toDoubleOrNull() ?: return null
    if (!parsed.isFinite()) return null
    return kotlin.math.round(parsed).toInt()
}

/** 用 [replacement] 替换当前选区（无选区时即插入），并把光标放到替换内容之后。 */
private fun replaceSelection(editor: StepperEditor, replacement: String): StepperEditor {
    val start = editor.selectionStart
    val end = editor.selectionEnd
    val next = editor.buffer.substring(0, start) + replacement + editor.buffer.substring(end)
    val caret = start + replacement.length
    return StepperEditor(buffer = next, caret = caret, anchor = caret)
}

// ─────────────────────────────────────────────────────────────────────────────
// 子菜单的**展开 / 收起延迟**（2026-09-18）
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话（本轮诉求，逐字）
//
// > 「右键菜单内设置项完善显示设置，**使内容显示完整**，鼠标悬停和离开展开项时的
// >   子菜单出现、消失延迟要可调。」
//
// ## ★ 关键：这两条延迟不是"手感微调"，而是"点得到 / 点不到"的分界
//
// 菜单是**层级导航**：从父项移到子菜单，鼠标必须**横穿**中间项。
// JDK 对"离开即收起"**写死了 0 延迟**：
//
// ```java
// // Temurin 21 `plaf/basic/BasicMenuItemUI.java:1044`（Handler.mouseExited）
// if (path[path.length-1] == menuItem) { ...pop... }   // 离开 = 立即收起
// ```
//
// 于是"离开即收起"会在**横穿过程中把子菜单误收起** → 用户**根本点不到子菜单里的项**。
// 这正是用户说的「内容显示不完整」的真实根因之一 —— **不是画不出来，是还没点到就没了**。
//
// ## ⚠️ 2026-09-18 更正：`JMenu` 的悬停展开**不是 0 延迟，而是两阶段的**
//
// **原记（错，也是"子菜单变成必须点击才展开"这个回归的诊断根源）**：
// 上面这段曾写"JDK 的 `BasicMenuItemUI.Handler` 对 `mouseEntered` 也写死 0 延迟 →
// 悬停即展开"。**引错了类**：`JMenu` 用的监听器是 `BasicMenuUI.Handler`，
// 它**整个覆写**了 `mouseEntered`，而且是**两阶段**的：
//
// ```java
// // Temurin 21 `plaf/basic/BasicMenuUI.java:562-583`（Handler.mouseEntered）
// if (menu.getDelay() == 0) {
//     appendPath(getPath(), menu.getPopupMenu());   // 阶段①②一起：立即展开
// } else {
//     manager.setSelectedPath(getPath());           // 阶段①：只把本行选中（高亮）
//     setupPostTimer(menu);                         // 阶段②：delay 毫秒后再展开
// }
// ```
//
// 而 `delay` **不是 0** —— `BasicMenuUI.installDefaults` 写死了 200
// （`plaf/basic/BasicMenuUI.java:98`：`((JMenu)menuItem).setDelay(200)`）。
//
// **实测**（Temurin 21.0.12.1 / Win11，`JMenu` 默认 delay=200，heavyweight popup）：
// ```
// show 后                        选中路径长度=1 [JPopupMenu]
// dispatch MOUSE_ENTERED 后立刻   选中路径长度=2 [JPopupMenu JMenu]      子菜单可见=false
// sleep 300ms 后                 选中路径长度=3 [JPopupMenu JMenu JPopupMenu]  子菜单可见=true
// ```
//
// **这条实测是"子菜单只能点击展开"那个回归的钥匙**：旧实现在延迟分支里
// **既不转发 `mouseEntered`、也不做阶段②**，只在自己延时到点后 `setSelectedPath(getPath())`
// —— 那**恰好只兑现了阶段①**（选中行），于是子菜单永不出现。
// 现做法见 `IslandMenuSkin.DelayedHoverListener.fireIfStillHovered`：转发一个
// **新建的** `MouseEvent`，把阶段①②整条交回 JDK；并把 `JMenu.delay` 置 0，
// 使本设置项成为延迟的**唯一来源**（否则真实延迟 = 本项 + 200ms 的隐式叠加）。
//
// ## 为什么收起延迟必须**明显大于**展开延迟
//
// | 若 | 后果 |
// | --- | --- |
// | 收起 ≤ 展开 | 横穿的那几十毫秒里子菜单已经关了，用户永远追不上 |
// | 收起 > 展开 | 横穿时子菜单**还开着**，指针落进子菜单即稳定 |
//
// 默认 250 > 180 就是这个原因；[submenuDelayPairIsSane] 把这条关系做成**可断言**的。
//
// ## 为什么这些常量与判据放在这里（而不是留在 `IslandMenuSkin` 里）
//
// 与 `IslandPolicy.kt` / `render/SpectrumGeometry.kt` **同一条纪律**：
// `IslandMenuSkin` 是 `internal`，探针是独立编译单元看不到 ——
// **为测试发愁先抽纯函数，而不是改可见性**（见 §14.14）。

/** 注册表键：子菜单**展开**延迟（ms）。 */
const val SUBMENU_OPEN_DELAY_KEY = "menu.submenu_open_delay_ms"

/** 注册表键：子菜单**收起**延迟（ms）。 */
const val SUBMENU_CLOSE_DELAY_KEY = "menu.submenu_close_delay_ms"

/**
 * 延迟的可调下限（ms）。`0` = 不延迟（退回 JDK 的即时行为）。
 *
 * ⚠️ 与 `SettingRegistry.groupK()` 的 `range` 是**同一个域** —— 探针断言两者相等，
 * 故这里改了而注册表没改会当场变红（而不是让用户在设置页看到一个走不通的值）。
 */
const val SUBMENU_DELAY_MIN_MS = 0

/** 延迟的可调上限（ms）。1000 已远超"人能感觉到"的范围。 */
const val SUBMENU_DELAY_MAX_MS = 1000

/**
 * 注册表**读不到**这两个键时的兜底值（ms）。
 *
 * ⚠️ 与 `SettingRegistry.groupK()` 的 `default` 是同一个数 —— 探针断言两者相等。
 * 兜底存在的理由：设置系统启动失败时菜单仍要能弹出（见
 * `IslandPopupMenu.build` 的 `settings == null` 分支），那时读不到值，
 * 但"延迟手感"不该退化成 0（0 = 立刻收起 = 点不到子菜单，正是本轮要修的缺陷）。
 */
const val SUBMENU_OPEN_DELAY_FALLBACK_MS = 180

/** 见 [SUBMENU_OPEN_DELAY_FALLBACK_MS]。取 250：必须**明显大于**展开延迟。 */
const val SUBMENU_CLOSE_DELAY_FALLBACK_MS = 250

/**
 * 指针轮询间隔（ms）—— 收起判定的采样周期。
 *
 * ## 为什么收起只能靠轮询（而不是靠鼠标事件）
 *
 * "指针离开菜单树"这件事**不一定产生本进程的 AWT 事件**：词岛与菜单都是
 * `WS_EX_NOACTIVATE` 悬浮件，指针移到一个**别的进程**的窗口上时，
 * 鼠标消息发给那个窗口，AWT 什么都不产生（这与 `GlobalMenuDismisser` 必须自建
 * `WH_MOUSE_LL` 钩子是同一个根因，见 `IslandPopupMenu` 的类 KDoc）。
 * 故收起判定用 `MouseInfo` 主动采样 —— 这也是原实现 0.8.9 的做法
 * （`089:ui/Windows11PopupStyle.kt:140` 的 `submenuWatcher`，同样 120ms）。
 *
 * ⚠️ **代价：收起延迟的粒度是 120ms**（最坏多等一个周期）。这是有意的取舍 ——
 * 收起晚一点只会让菜单多留一瞬，而收起早了就是"点不到"。**方向上偏保守是安全的。**
 */
const val SUBMENU_HOVER_POLL_MS = 120

/**
 * 子菜单的展开 / 收起延迟（ms）。不可变 —— 便于断言"改了什么"。
 *
 * @param openMs 悬停多久才展开子菜单
 * @param closeMs 指针离开菜单树多久才收起已展开的子菜单
 */
data class SubmenuDelays(val openMs: Int, val closeMs: Int) {
    companion object {
        /** 注册表不可用时的兜底（见 [SUBMENU_OPEN_DELAY_FALLBACK_MS]）。 */
        val FALLBACK = SubmenuDelays(SUBMENU_OPEN_DELAY_FALLBACK_MS, SUBMENU_CLOSE_DELAY_FALLBACK_MS)
    }
}

/**
 * 把任意整数夹进可调域 `[0, 1000]`。
 *
 * 为什么需要它：这两个值除了注册表（那里已有 `coerceIn(range)`）还有**第二条来源** ——
 * 用户在步进器里**内联编辑**打出来的数。`MenuStepper` 提交时已按 `min/max` 夹过一次，
 * 这里是**第二道**：延迟最终会变成 `javax.swing.Timer` 的周期，
 * 负数或天文数字会让定时器行为不可预期（负值 `Timer` 会抛 `IllegalArgumentException`）。
 * 与 `stepperEditorCommitValue` 的"写入侧统一夹取"同一纪律：**夹取只在一处定义，但可以多处调用**。
 */
fun coerceSubmenuDelayMs(raw: Int): Int = raw.coerceIn(SUBMENU_DELAY_MIN_MS, SUBMENU_DELAY_MAX_MS)

/**
 * 悬停已持续 [hoveredMs] 时，是否该**展开**子菜单。
 *
 * ## 单调性（这是本条最该被钉住的性质）
 *
 * 对 [hoveredMs] **单调不减**：悬停越久越该展开。若实现写成"到点重置"，
 * 用户会看到"停住不动，子菜单却反复开了又关"。
 *
 * @param hoveredMs 从指针**进入该项**算起的毫秒数（`0` = 刚进入）
 * @param openDelayMs 展开延迟（越界会被 [coerceSubmenuDelayMs] 夹）
 */
fun shouldOpenSubmenu(hoveredMs: Long, openDelayMs: Int): Boolean =
    hoveredMs >= coerceSubmenuDelayMs(openDelayMs).toLong()

/**
 * 指针离开菜单树够久之后，该执行的**收起动作**。
 *
 * ## ★★★ 2026-10-15：新增 `ALL` —— 用户要求**一级菜单也自动收起**
 *
 * 用户报障（逐字）：
 *
 * > 「**右键菜单（第一级），同子菜单一样受收起延迟控制**（现在是**不会自行收起**的情况）」
 *
 * ★ **原记**：本节的判据写死 `pathDepth > 1`，于是路径里只有根菜单时**永不收起** ——
 *   根菜单只能靠"点菜单外"（`GlobalMenuDismisser`）或"选中某项"来关闭。
 * ★ **实为**：那对子菜单是对的（子菜单收起来后**根菜单不该跟着消失**，与原生
 *   Windows 一致），但对**根菜单本身**就是一个缺陷 —— 用户把鼠标移开、去干别的，
 *   菜单会**一直挂在屏幕上**，直到他回来点一下别处 ✗
 *
 * ⇒ 正解 = 把"何时收"（时间判据，**两种深度共用**）与"收什么"（动作，按深度分）
 *   **拆开**：时间判据只有一处定义，动作由本函数按 [pathDepth] 给出。
 *
 * | `pathDepth` | 动作 | 为什么 |
 * |---|---|---|
 * | `0` | [MenuCollapse.NONE] | 没有菜单 |
 * | `1` | [MenuCollapse.ALL] | ★ **只有根菜单** ⇒ 要收的就是它 |
 * | `≥ 2` | [MenuCollapse.SUBMENUS] | 只收子菜单、**保留根菜单**（原生 Windows 行为） |
 *
 * ## 三条时间语义（每条都有对应的失败形态）
 *
 * 1. **`outsideMs == 0`（刚离开）在 `closeDelayMs > 0` 时恒为 `NONE`** ——
 *    这是"横穿留时间"的兑现。指针刚离开那一瞬间**绝不能**收。
 * 2. **`closeDelayMs == 0` 时立即收** —— `0` 的语义是"不延迟"，
 *    此时第一条轮询就该收（而不是"等一个周期再收"）。
 * 3. **对 [outsideMs] 单调不减** —— 离开越久越该收；写成"到点重置"会让
 *    菜单反复开了又关。
 *
 * ⚠️ **`pathDepth == 1` 时不再返回 `NONE`** —— 这正是本次要改的那一条。
 *    旧实现担心的"轮询会把根菜单也 `setSelectedPath` 一次 ⇒ 菜单闪个不停"，
 *    根因是**动作**用错了（对根菜单用了"重设选中路径"），而不是时间判据不对：
 *    新实现在 `ALL` 分支走 `clearSelectedPath()`（关闭），**不会**引发那种闪烁 ✓
 *
 * @param outsideMs 指针离开菜单树的持续毫秒数（`0` = 本轮刚判定为"在外面"）
 * @param closeDelayMs 收起延迟（越界会被 [coerceSubmenuDelayMs] 夹）
 * @param pathDepth 当前 `MenuSelectionManager.selectedPath` 的长度
 */
fun collapseAction(outsideMs: Long, closeDelayMs: Int, pathDepth: Int): MenuCollapse {
    if (pathDepth < 1) return MenuCollapse.NONE
    if (outsideMs < coerceSubmenuDelayMs(closeDelayMs).toLong()) return MenuCollapse.NONE
    return if (pathDepth > 1) MenuCollapse.SUBMENUS else MenuCollapse.ALL
}

/**
 * 菜单行**该不该画高亮条** —— ★★★ 2026-10-15（用户报障）新增的**唯一判据**。
 *
 * ## 用户报障（逐字）
 *
 * > 「**被选中过的菜单背景变蓝不消退**（重开菜单蓝色消失）。」
 *
 * ## 根因：`JMenu` 行走的 UI 类与普通项**不是同一个**，而它漏了 `paintBackground`
 *
 * | 行类型 | UI 类 | 覆写了 `paintBackground`？ |
 * |---|---|---|
 * | 普通项（开关 / 步进器 / 普通命令） | `MenuItemUi`（`BasicMenuItemUI`） | ✅ 有 |
 * | **`JMenu` 行**（「频谱 ›」「更多设置」） | `DelayedMenuUi`（`BasicMenuUI`） | ❌ **漏了** |
 *
 * ⇒ `JMenu` 行在 `model.isSelected()` 时落到 **L&F 默认实现**
 *   （`plaf/basic/BasicMenuItemUI.paintBackground`）：
 *   ```java
 *   if (model.isArmed() || (menuItem instanceof JMenu && model.isSelected())) {
 *       g.setColor(bgColor);              // bgColor = selectionBackground
 *       g.fillRect(0, 0, menuWidth, menuHeight);
 *   }
 * ```
 *   ⇒ 用 `selectionBackground`（Windows L&F 的**蓝色**）填满整行 ✗
 *   ★ 而"**重开菜单蓝色消失**"正说明它是**状态残留**：菜单关闭前
 *   `MenuSelectionManager.selectedPath` 仍指着那一行 ⇒ 只要重绘就还是蓝的；
 *   重开菜单时路径重建 ⇒ 蓝消失 ✓ 与用户描述**逐字吻合**。
 *
 * ## 为什么把判据抽成纯函数
 *
 * 它现在有**两个消费点**（`MenuItemUi` 与 `DelayedMenuUi`）——
 * ★ 若各写一遍，将来改一处就会让"两类行的高亮规则"分叉
 *   （而**分叉本身正是本缺陷的成因**：两类行本来就该同规则，却因为"只在一处写了"而不同）。
 * ⇒ 按「**同一问题只准有一个定义点**」只留这一份实现。
 *
 * ## 语义表（★ 2026-10-16 加了第 4 个入参，见下）
 *
 * | `isArmed` | `isMenu` | `isSelected` | `submenuVisible` | `pointerOverRow` | 结果 | 场景 |
 * |---|---|---|---|---|---|---|
 * | 任意 | **true** | **true** | **true** | 任意 | **true** | ★ 子菜单**真的开着** ⇒ 父行保持高亮 ✓（与指针无关）|
 * | **true** | 任意 | 任意 | 任意 | **true** | **true** | 指针**真的**在这一行上 ⇒ 高亮 ✓ |
 * | **true** | 任意 | 任意 | 任意 | **false** | ★ **false** | ★★ **残留**：`isArmed` 还指着本行、而指针已离开 ✗ |
 * | `false` | **true** | **true** | **false** | 任意 | **false** | ★★ **残留**：`isSelected` 还指着本行、而子菜单已收 ✗ |
 * | `false` | `true` | `false` | 任意 | 任意 | `false` | JMenu 行未被选中 |
 * | `false` | `false` | `true` | 任意 | 任意 | **false** | ★ 普通项不因 `isSelected` 高亮 |
 *
 * ⚠️ **`isMenu` 是必需的，不是防御性参数**：普通项（尤其 `JCheckBoxMenuItem` /
 *   `JRadioButtonMenuItem`）的 `isSelected` 表示"**这一项被勾上了**"，
 *   **与高亮无关** ⇒ 若不判 `isMenu`，所有勾选项会**一直亮着** ✗
 *
 * ## ★★★ 2026-10-16（**BUG-215 · 台账 §3.77-A**）：为什么必须有 `submenuVisible`
 *
 * 用户报障（逐字）：「**划过置灰不消失**」（★ 本轮之前那条"变蓝"的**残留**问题 ——
 * 颜色已从 L&F 蓝改成我们的灰，**但残留本身没治**）。
 *
 * ★★ **根因**：`JMenu` 行与普通项的**状态来源不同** ——
 *   · 普通项：`JMenuItem.menuSelectionChanged` ⇒ `setArmed(...)`；
 *   · **`JMenu` 行**：`JMenu.menuSelectionChanged` 覆写成 `setSelected(...)`
 *     ⇒ ★ 它的 `isSelected` **就是**"在 `MenuSelectionManager` 的选中路径里"。
 *
 * ⇒ 而 `MenuSelectionManager.selectedPath` 是**状态**、不是"指针在哪"的实时读数：
 *   指针离开后，路径**不会**自动清（只在"进入别的项"/"折叠定时器到点"时更新）
 *   ⇒ ★ 那一行**只要重绘就还是高亮** ✗（用户原话「**不消失**」）
 *
 * ★ **修法 = 把判据钉在【结构性真值】上**：`isSelected` 只是**申报**，
 *   `menu.isPopupMenuVisible` 才是**事实** —— 子菜单没开 ⇒ 本行不该高亮 ✓
 *   ★★ 判据：**「申报的状态」永远要有一条「结构性真值」兜底**（同族：纪律 91/94）。
 *
 * ## ★★★ 2026-10-17（**BUG-217**）：为什么还要 `pointerOverRow`
 *
 * 用户第二次报障（逐字）：「**置灰恢复还是没能修复**」。
 *
 * ★★ **上一次（BUG-215）只堵了 `isSelected` 那条残留，而真正卡住的是 `isArmed`** ——
 *   它同样是**状态**：`MenuSelectionManager` 只在"指针进入**另一行**"时才更新路径，
 *   ★ 指针停在菜单里**但不在任何行上**（行间空隙 / 边框 / 分隔线）时，
 *   路径**保持不动** ⇒ 那一行的 `isArmed` **一直是 true** ⇒ 高亮**永不消退** ✗
 *
 * ★★ **本项目的菜单没有键盘导航**（行组件 `isFocusable = false`，
 *   `KeyEventDispatcher` 只服务步进器的内联编辑）⇒ **高亮 100% 由鼠标驱动**
 *   ⇒ ★ 可以**安全地**把判据钉在"指针真的在这一行上"这条**结构性真值**上 ✓
 *
 * ★ 为什么不用"`MOUSE_EXITED` 有没有送到"来判：★★ **那是"机制"、不是"事实"** ——
 *   实测（`_MenuExitProbe`）证明"**dispatch 了就会清**"，而那恰恰说明
 *   **"事件一定会到"这个前提没有被检验** ✓
 *
 * @param isArmed `item.model.isArmed`（鼠标压住 / 键盘导航）
 * @param isMenu 本行是不是 `JMenu`（有子菜单的那类行）
 * @param isSelected `item.model.isSelected`（JMenu = 被选中；复选/单选项 = 被勾上）
 * @param pointerOverRow ★★★ **指针此刻是否真的落在这一行的屏幕矩形内**
 *   （`MouseInfo` 实测；取不到光标时**保守返回 `true`** ⇒ 退回旧行为，不会"该亮不亮"）
 * @param submenuVisible 本行是 `JMenu` 时，它的子菜单**此刻是否真的显示着**
 *   （`(item as? JMenu)?.isPopupMenuVisible == true`）；非 `JMenu` 行传 `false` 即可
 *   —— 该值只在 `isMenu` 为真时才参与判据 ✓
 */
fun shouldPaintMenuRowHighlight(
    isArmed: Boolean,
    isMenu: Boolean,
    isSelected: Boolean,
    submenuVisible: Boolean,
    pointerOverRow: Boolean,
): Boolean = (isMenu && isSelected && submenuVisible) || (isArmed && pointerOverRow)

/**
 * 收起动作 —— [collapseAction] 的返回值。
 *
 * ★ 用 `enum` 而不是 `Boolean`：这里**有三种**结果（不动 / 收子菜单 / 全收），
 *   用布尔会把它压成两种，而"收子菜单"与"全收"的差别正是本类缺陷的落点
 *   （旧实现把两者混为"要不要 `setSelectedPath(arrayOf(path[0]))`"）。
 */
enum class MenuCollapse {
    /** 什么都不做。 */
    NONE,

    /** 只收起子菜单，**保留根菜单**（原生 Windows 行为）。 */
    SUBMENUS,

    /** ★ 连根菜单一起收起 —— 整条菜单链消失。 */
    ALL,
}

/**
 * 指针已在菜单树**之外**持续 [outsideMs] 时，是否该**收起已展开的子菜单**。
 *
 * ★ 保留此函数（= [collapseAction] 的 `SUBMENUS` 分支）是为了不改变既有调用方与探针的
 *   语义 —— 「根菜单要不要一起收」是**另一件事**，由 `ALL` 分支回答。
 *
 * @param pathDepth 当前选中路径长度；`<= 1` 时没有子菜单可收 ⇒ 恒 `false`。
 */
fun shouldCollapseSubmenu(outsideMs: Long, closeDelayMs: Int, pathDepth: Int): Boolean =
    collapseAction(outsideMs, closeDelayMs, pathDepth) == MenuCollapse.SUBMENUS

/**
 * 这两个延迟的**取值关系**是否合理 —— 即"收起是否明显晚于展开"。
 *
 * ## 为什么值得单独一条断言
 *
 * 见本节的 KDoc：**收起 ≤ 展开 = 用户永远点不到子菜单项**。
 * 这是一条"两个独立设置项之间的约束"，用户可以在设置页把它们调成任意组合 ——
 * 包括把自己调进坑里。故：
 * ① **默认值**必须满足它（探针断言注册表的两个 `default`）；
 * ② 界面上**不做**联动夹取（那会让用户改了 A 却发现 B 也变了，比"手感差"更困惑）——
 *    这里只提供判据，让探针能拦住"默认值被改坏"。
 */
fun submenuDelayPairIsSane(openMs: Int, closeMs: Int): Boolean = closeMs > openMs

// ─────────────────────────────────────────────────────────────────────────────
// 右键菜单的**透明度**（2026-09-18）
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话（本轮诉求，逐字）
//
// > 「**允许调整右键菜单的透明度**。」
//
// ## 落点是 `Window.setOpacity`，而它**确实可用**（实测，不是推断）
//
// 菜单是 heavyweight popup（`IslandPopupMenu` 的 `isLightWeightPopupEnabled = false`，
// 为的是子菜单能画出父窗之外），承载它的是 `javax.swing.Popup$HeavyWeightWindow`
// —— 一个 `JWindow` 子类。实测（Temurin 21.0.12.1 / Win11）：
//
// ```
// Window.getWindows() → javax.swing.Popup$HeavyWeightWindow showing=true
// 该窗口 setOpacity(0.5) → OK，getOpacity()=0.5（无异常）
// GraphicsDevice.isWindowTranslucencySupported(TRANSLUCENT) = true
// ```
//
// ⚠️ **JDK 21 的 `Window.setOpacity` 源码里根本没有"必须 undecorated"这条检查**
// （`java.desktop/java/awt/Window.java` 的 `setOpacity` 只校验：范围 `[0,1]`、
// 不是全屏窗、`TRANSLUCENT` 受支持）。javadoc 里那句"must be undecorated"是
// 面向 `Frame` / `Dialog` 的（那两个类的 peer 会拒绝）——
// `Window` / `JWindow` 本身没有装饰，天然满足。
// 另注：`java.awt.Window` **没有** `isUndecorated()` 这个方法（编译期实测
// `找不到符号`），所以"先判 isUndecorated 再 setOpacity"这种写法根本写不出来。

/** 注册表键：右键菜单不透明度（百分比）。 */
const val MENU_OPACITY_KEY = "menu.opacity_pct"

/**
 * 不透明度的可调下限（%）。
 *
 * ## ⚠️ 下限**不能是 0**（与 `spectrum.height_pct` 的下限不能是 0 同一条纪律）
 *
 * 0% = 完全透明，而 JDK `Window.setOpacity` 的 javadoc 原文写着：
 * *"Note that setting the opacity level of 0 may or may not disable the mouse event
 * handling on this window. This is a platform-dependent behavior."*
 * —— 在 Windows 上，layered 窗口的命中测试按**逐像素 alpha** 做，alpha=0 的像素
 * **不接收鼠标消息**。于是"0%"会让菜单变成**看得见、点不到**（比"调不了"严重得多）。
 * 取 30 是"明显变淡但仍能看清字、仍能点"的下限。
 */
const val MENU_OPACITY_MIN_PCT = 30

/** 不透明度的可调上限（%）。100 = 完全不透明（与不设 `setOpacity` 同观感）。 */
const val MENU_OPACITY_MAX_PCT = 100

/**
 * 注册表**读不到**该键时的兜底值（%）。
 *
 * 与 [SUBMENU_OPEN_DELAY_FALLBACK_MS] 同一条纪律：兜底值**必须等于**注册表默认值
 * （探针断言这一点）。否则"设置系统启动失败时"与"正常时"的观感会不一样，
 * 而这种差异**只在故障态出现**，最难被发现。
 */
const val MENU_OPACITY_FALLBACK_PCT = 100

/**
 * 把任意整数夹进可调域 `[30, 100]`。
 *
 * 与 [coerceSubmenuDelayMs] 同一条纪律：这个值有**两条来源**（注册表 / 步进器内联编辑），
 * 而它最终会变成 `Window.setOpacity` 的入参 —— 越界值会让 `setOpacity` 抛
 * `IllegalArgumentException`（< 0 或 > 1），那会**打断整个菜单弹出**。
 */
fun coerceMenuOpacityPct(raw: Int): Int = raw.coerceIn(MENU_OPACITY_MIN_PCT, MENU_OPACITY_MAX_PCT)

/**
 * 百分比 → `Window.setOpacity` 要的 `[0,1]` alpha。
 *
 * 单独抽出来，是为了让"30 → 0.30"这条换算**能被探针断言**：
 * 它是本项目唯一一处"设置项的单位与 API 的单位不同"的地方，
 * 最容易写成 `pct.toFloat()`（忘除 100）—— 那样 `setOpacity(100f)` 会抛
 * `IllegalArgumentException`，整个菜单弹不出来。
 */
fun menuOpacityAlpha(pct: Int): Float = coerceMenuOpacityPct(pct) / 100f

/**
 * 百分比 → 菜单**底板颜色**的 alpha（`0..255`）。
 *
 * ## ★★★ 2026-10-15（用户报障）：透明度的**落点**从"整窗"改成"背景色"
 *
 * 用户逐字：「**右键菜单的透明度设置改为不对文字内容生效**」。
 *
 * ★ **旧落点的问题**：`Window.setOpacity` 是**整窗合成** —— 窗口里**每一个像素**
 *   （含文字、箭头、勾号）都乘上这个 alpha ⇒ 调低透明度时**文字跟着发灰** ✗
 *   而用户要的是"菜单像毛玻璃一样透，字还是清清楚楚"。
 *
 * ★ **新落点**：窗口**不做**整窗 alpha（静态恒 `opacity = 1.0`），只把**菜单底板**
 *   那个颜色的 alpha 按本函数算出来 ⇒ ★ 文字像素仍是**全不透明** ✓
 *   （前提"逐像素透明可用"已实测，见 `IslandMenuSkin.perPixelTranslucent` 的 KDoc）
 *
 * ## 为什么这条换算要单独抽成纯函数
 *
 * 与 [menuOpacityAlpha] 同一条纪律：它是"设置项单位 → 绘制单位"的换算，
 * 而**取整方式**会直接决定探针能不能逐值断言。
 *
 * ⚠️ **四舍五入**（`+ 50` 再整除）而不是截断：30% ⇒ `30 × 255 / 100 = 76.5`
 *   ⇒ 截断得 76、四舍得 77。差 1 级肉眼不可见，但**必须有唯一一种**取整方式 ——
 *   留给"运行时随手写"会让同一条规则出现两个落点。
 *
 * | `pct` | 结果 |
 * |---|---|
 * | 100 | 255（完全不透明 ⇒ **开箱零变化**） |
 * | 50 | 128 |
 * | 30 | 77 |
 */
fun menuSurfaceAlpha(pct: Int): Int {
    val p = coerceMenuOpacityPct(pct)
    return (p * 255 + 50) / 100
}

// ─────────────────────────────────────────────────────────────────────────────
// 菜单标签的**换行**（2026-09-18）
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话（本轮诉求，逐字）
//
// > 「子菜单中**许多选项设置项名字显示不全**，建议**缩小字号多行显示**
// >   （但**不要侵占其他设置项**），**拉宽子菜单宽度**。」
//
// ## 为什么换行这件事必须抽成纯函数
//
// "名字显示不全"的现有兜底是 `ellipsize`（截断加 `…`），而**截断丢的是信息**。
// 换成换行之后，新的失败形态是"算错行数 → 文字画出组件外 / 行高不够被裁"——
// 那同样是"看不全"，只是换了样子。故把折行规则抽成纯函数（`widthOf` 由调用方注入），
// 让探针能**穷举断言**：每行宽度不超上限、不切半个代理对、避头尾禁则、行数不超上限。
//
// ## 「不要侵占其他设置项」= 行高按**本项实际行数**增长
//
// 这一条决定了实现形态：**不能**全局把行高加大（那会让每一行都变高，
// 正是用户说的"侵占"），只能在**需要换行的那一行**上把行高加上去。
// 故 [menuRowHeightForLines] 是"基础行高 + 额外的行数 × 每行增量"，
// 单行的项拿到的仍是原来的行高。

/**
 * 一个菜单标签最多折成几行。
 *
 * 取 3：3 行按 11.5pt 中文算能放下约 45 个汉字，而本注册表里最长的标题只有 13 个字
 * （`spectrum.height_pct` 的「频谱高度（占歌词区百分比）」）——
 * 3 行是"给将来的长标题留余量"，再长就该靠**拉宽**而不是继续加高
 * （菜单是竖向列表，无限加高会让一屏放不下）。
 */
const val MENU_LABEL_MAX_LINES = 3

/**
 * 换行后每**多**一行，给行高加多少像素。
 *
 * 取 18：11.5pt 中文的行距约 18–20px（`FontMetrics.getHeight()`）。
 * 单行的项**一分钱都不加**（这就是"不侵占其他设置项"）。
 */
const val MENU_LABEL_EXTRA_LINE_PX = 18

/** 行首禁则（避头点）：这些字符**不能**出现在一行的开头。 */
private const val NO_LINE_START = "）】」』、，。；：？！·…%‰”’"

/** 行尾禁则（避尾点）：这些字符**不能**出现在一行的末尾。 */
private const val NO_LINE_END = "（【「『“‘"

/**
 * 把 [text] 折成**每行不超过 [maxWidth]** 的多行文本。
 *
 * ## 折行规则（三条，按优先级）
 *
 * 1. **贪心**：一行能放多少放多少（中文没有词边界，这是唯一可行的默认）。
 * 2. **拉丁词优先断在空格上** —— 但仅当那个空格位于行**过半之后**
 *    （否则"a very long label"会在 `a` 之后断行，一行只剩一个字）。
 * 3. **避头尾禁则**：行首不出现 [NO_LINE_START] 的字符（如 `）`）、
 *    行尾不出现 [NO_LINE_END] 的字符（如 `（`）。这两条让「频谱高度（占歌词区百分比）」
 *    不会折成 `频谱高度（` / `占歌词区百分比）` 这种看起来像坏了的断法。
 *
 * ## 返回值的契约
 *
 * - `maxWidth <= 0` 或 `maxLines <= 1` → **原样返回单元素列表**（不折行）。
 *   这两种情形下"放不下"的兜底是 [ellipsize]（截断），**不在这里重复实现截断** ——
 *   两处都做截断会让"到底谁负责保证不越界"变得说不清。
 * - 行数**不超过 [maxLines]**；最后一行若仍放不下，超宽部分由调用方的 [ellipsize] 收尾。
 * - **不切在代理对中间**（与 [ellipsize] 同一条纪律：`String.length` 是**码元**数，
 *   emoji / 生僻字占两个码元，切在中间会画成豆腐块）。
 *
 * ⚠️ 参数顺序：`widthOf` 放在**最后**，是为了让调用方能写尾随 lambda
 * （`wrapMenuLabel(text, w) { metrics.stringWidth(it) }`）—— 与 [ellipsize] 的
 * `(text, maxWidth, widthOf)` 同一形态。`maxLines` 在它前面且有默认值，
 * 需要时用**具名实参**传（`maxLines = 2`）。
 *
 * @param maxLines 最多折几行（默认 [MENU_LABEL_MAX_LINES]）
 * @param widthOf 度量函数（探针可注入"每字符 10px"的假实现，不需要真实字体）
 */
fun wrapMenuLabel(
    text: String,
    maxWidth: Int,
    maxLines: Int = MENU_LABEL_MAX_LINES,
    widthOf: (String) -> Int,
): List<String> {
    if (text.isEmpty()) return listOf("")
    if (maxWidth <= 0 || maxLines <= 1) return listOf(text)

    val lines = ArrayList<String>(maxLines)
    var i = 0
    val n = text.length
    while (i < n) {
        // 行首不留空格（断在空格上时那个空格会被丢在上一行末尾或本行行首）。
        while (i < n && text[i] == ' ') i++
        if (i >= n) break
        if (lines.size == maxLines - 1) {
            // 最后一行：剩下的**全部**给它。仍超宽则由调用方的 ellipsize 截断 ——
            // 在这里截会让"截断规则"有两处定义。
            lines.add(text.substring(i))
            break
        }

        var end = i
        var lastSpace = -1
        while (end < n) {
            val step = if (isSurrogatePairAt(text, end)) 2 else 1
            if (widthOf(text.substring(i, end + step)) > maxWidth) break
            if (text[end] == ' ') lastSpace = end
            end += step
        }
        if (end == i) {
            // 一个字都放不下（可用宽度比单字还窄）—— **至少吃掉一个字符**，
            // 否则 `end` 不前进，这个 while 会死循环。
            end = i + if (isSurrogatePairAt(text, i)) 2 else 1
        } else if (end < n && lastSpace > i + (end - i) / 2) {
            end = lastSpace
        }
        // ── 避头尾禁则（中文排版）─────────────────────────────────────────
        //
        // ⚠️ 两个规则**都只在"真的断了行"时才适用**（`end < n`）。
        //    `end == n` 表示"本行吃下了剩余全部字符" —— 此时根本不存在断点，
        //    再去套禁则只会**凭空造出一次断行**。
        //
        //    这是真机探针抓到的 bug（`MenuProbe`「最长标题在 254px 下应折成 1 行」
        //    期望 1 行、实际 2 行）：标题「频谱高度（占歌词区百分比）」整串
        //    只有 208px < 254px，本该原样一行；但旧的避头点分支拿**本行末字**
        //    （`text[end-1]`）去判"不能行首"，把结尾的 `）` 判成违规 →
        //    `end--` → 于是 `）` 被挤到第二行。放得下的标签被硬拆成两行，
        //    而且第二行只有一个标点 —— 观感比截断更糟。
        if (end < n) {
            // 避头点：**下一行的首字**（`text[end]`）不能是不能行首的字符，
            // 否则把它前面的字一起推下去，让它不再落在行首。
            //
            // 方向选择：这里是 `end--`（把上一行末字推下去）而**不是** `end++`
            // （把标点挤进上一行）。因为本项目已经有一条硬约束 ——
            // `MenuProbe`「每行宽度都不超上限」：`end++` 会让该行宽出 1 个字符，
            // 而菜单项宽度是 `displaySize` 按"最宽行"算出来的单一事实源，
            // 放宽 1 个字会让**整个菜单变宽**。宁可让上一行短一个字。
            while (end > i + 1 && text[end] in NO_LINE_START) end--
            // 避尾点：本行末字（`text[end-1]`）不能是不能行尾的字符（如 `（`）。
            while (end > i + 1 && text[end - 1] in NO_LINE_END) end--
        }
        lines.add(text.substring(i, end))
        i = end
    }
    return if (lines.isEmpty()) listOf("") else lines
}

/** [index] 处是否是**完整**的代理对（高代理 + 低代理）。用于"不切半个字符"。 */
private fun isSurrogatePairAt(text: String, index: Int): Boolean =
    index + 1 < text.length && text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()

/**
 * 折成 [lineCount] 行之后，这一行需要多高。
 *
 * ## 为什么是"基础行高 + 增量"而不是"行数 × 行高"
 *
 * 因为「不要侵占其他设置项」：单行的项必须拿到**原来那个行高**（`baseRowHeight`），
 * 一点不变。只有多出来的行才额外加高，且增量 [MENU_LABEL_EXTRA_LINE_PX] 比
 * `baseRowHeight` 小（18 < 28）—— 行高里本来就有上下留白，第二行不需要再来一份。
 *
 * @param baseRowHeight `rowHeightFor` 算出的基础行高（24..28）
 * @param lineCount 折行后的行数（`<= 1` 时原样返回基础行高）
 */
fun menuRowHeightForLines(baseRowHeight: Int, lineCount: Int): Int =
    if (lineCount <= 1) baseRowHeight else baseRowHeight + (lineCount - 1) * MENU_LABEL_EXTRA_LINE_PX

// ─────────────────────────────────────────────────────────────────────────────
// 点击菜单项之后：**菜单该不该关**（2026-09-21）
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话（本轮诉求，逐字）
//
// > 「**右键菜单中有部分项点击即消失，不能连续调参，请你完善。**」
//
// ## 这句话里有两个独立的要求，必须分开对待
//
// | 要求 | 说的是什么 | 本条怎么落 |
// | --- | --- | --- |
// | 「部分项点击即消失」 | **有些项**点完菜单就没了 | 把"该关 / 不该关"**变成一张表**，逐项核对（见 [MenuItemRole]） |
// | 「不能连续调参」 | 调参要能**连着点**（连点 5 次 `+`） | 调参类一律**保持打开**（见 [menuDismissFor]） |
//
// ## 为什么这是"一类缺陷"而不是"漏了一个 `keepOpen()`"
//
// 菜单项的"点击后关不关"是**每个项各自的决定**，而它写在各处（`build` 的顶层、
// `switchItem`、`stepperItem`、`listItem`、`recentFontItem`、`buildPresetMenu` …）。
// 于是它天然会**漏**：2026-09-18 补过 `switchItem`、2026-09-19 补过顶层三个开关，
// 而**预设单选、最近字体单选**两处**一直漏着** —— 用户这次报的正是它们。
//
// > **纪律：散落在 N 处的同一个决定，必须收成一处判据 + 一张可断言的表。**
// > 靠"记得每个地方都加"是不行的 —— 那正是这条缺陷重复出现三次的原因。
//
// ## 分类依据：**点击之后用户还想不想继续待在这个菜单里**
//
// - **调参类（[MenuItemRole.ADJUST]）**：步进器 `±`、勾选开关、单选列表、应用预设。
//   效果**当场可见**（词岛立刻变），用户自然会连着试几项 —— **保持打开**。
// - **导航类（[MenuItemRole.NAVIGATE]）**：打开设置页 / 取色器 / 字体包 / 文件选择器。
//   点了会弹出**另一个窗口**，菜单留着只会挡住它 —— **关闭**。
// - **动作类（[MenuItemRole.ACTION]）**：找回词岛 / 关于 / 源码 / 退出。
//   一次性动作，没有"连续做"的语义 —— **关闭**。
//
// ⚠️ **反向也要守住**：把导航/动作类做成"保持打开"同样是缺陷 ——
//    打开取色器后菜单还挂在上面，用户第一反应是"菜单卡住了"。

/** 点击一个菜单项之后，**菜单该不该关**。 */
enum class MenuDismiss {
    /** 保持打开 —— 用户还要连着调。 */
    KEEP_OPEN,

    /** 关闭 —— 这一项是"离开菜单"的动作。 */
    CLOSE,
}

/**
 * 菜单项在"点击后关不关菜单"这件事上的**角色** —— 本文件的唯一判据来源。
 *
 * 把 `dismiss` 挂在枚举常量上（而不是写在 [menuDismissFor] 的 `when` 里）是为了
 * **加新角色时编译器逼你表态**：新增一个常量必须给它一个 `dismiss`，不能默认落进 `else`。
 */
enum class MenuItemRole(val dismiss: MenuDismiss) {
    /** 调参类：步进器 `±` / 勾选 / 单选 / 应用预设。效果当场可见 → 保持打开。 */
    ADJUST(MenuDismiss.KEEP_OPEN),

    /** 导航类：打开设置页 / 取色器 / 字体包 / 文件选择器。会弹别的窗口 → 关闭。 */
    NAVIGATE(MenuDismiss.CLOSE),

    /** 动作类：找回词岛 / 关于 / 源码 / 退出。一次性动作 → 关闭。 */
    ACTION(MenuDismiss.CLOSE),
}

/**
 * 本角色点击后菜单该不该关。
 *
 * ⚠️ **这个函数必须被生产代码真正调用**，否则它就是一段"只为了让探针变绿"的假判据 ——
 * 调用点是 `IslandPopupMenu.keepOpenFor(role)`（唯一入口，见其 KDoc）。
 * `MenuProbe` 断言的正是"调参类 → 保持打开"这一条。
 */
fun menuDismissFor(role: MenuItemRole): MenuDismiss = role.dismiss

/**
 * **一级菜单**（右键弹出来的那一层）里手写项的名字与角色。
 *
 * ## 为什么要把它做成公开表
 *
 * 「投喂作者」必须落在**一级菜单**（不是子菜单）—— 而"落在哪一层"这件事
 * **代码里看不出来**（`menu.add(...)` 与 `submenu.add(...)` 只差一个变量名，
 * 写错了不报错、不崩溃，只是用户得多点两层）。
 * 把一级项列成表之后，`MenuProbe` 就能断言"它在表里"，而
 * `IslandPopupMenu.build` 末尾的 [validateTopLevelEntries] 会断言
 * **表里的每一项真的出现在菜单顶层** —— 两张网一起才拦得住"写错层级"。
 *
 * ⚠️ **顺序即显示顺序**（除子菜单「调整词岛设置」恒在第一项）。
 *
 * ⚠️ 2026-09-22 修正一处**顺序漂移**：此前「投喂作者」登记在「找回词岛」**之前**，
 * 而 `IslandPopupMenu.build` 里它是**紧邻「退出」**的那一项（在「源仓库跳转」之后）。
 * 表与实现不一致时，用户看到的是实现的顺序、探针看到的是表的顺序 ——
 * 两边都"自洽"，缺陷就在中间。现已把表调成与 `build` **逐项同序**，
 * 并在 `MenuProbe` 加了「顶层项顺序 == 本表顺序」的机械断言（不再是靠人对齐）。
 */
data class TopLevelMenuEntry(
    /** 菜单上显示的文字（子菜单是 `JMenu` 的标题）。 */
    val title: String,
    /** 点击后关不关菜单 —— 见 [MenuItemRole]。 */
    val role: MenuItemRole,
)

/** 「投喂作者」的菜单文字。 */
const val MENU_TITLE_FEED_AUTHOR = "投喂作者"

/**
 * 设置树的**根子菜单**标题（一级菜单里那一行）。
 *
 * ## 为什么叫「更多设置」而不是原来的「调整词岛设置」（2026-09-22）
 *
 * 用户原话：
 * > 「一级菜单放**词岛穿透、投喂作者、源仓库跳转、更多设置、预设列表**等常用/需明示的项。」
 *
 * 「调整词岛设置」有两个问题：
 * ① **太长** —— 一级菜单里它旁边是「显示词岛」「鼠标穿透」这类 4 字项，7 字显得笨重；
 * ② **与子菜单重复** —— 它的子菜单里全是"词岛设置"，标题再写一遍"词岛"是冗余。
 * 改「更多设置」后，一级菜单读起来是"**常用项 + 更多设置**"这个自然的分工。
 */
const val MENU_TITLE_MORE_SETTINGS = "更多设置"

/**
 * 「投喂作者」弹窗里的**主标题** —— 用户原话就是这个（含全角感叹号）。
 *
 * ## 为什么「谢谢！」从"整窗正文"降为"主标题"（2026-10-17）
 *
 * 用户 2026-09-21 的原话是「内容是一句『谢谢！』」。2026-10-17 用户拍板
 * 「用三合一现成的（素材），但措辞要调整一下」+「谢谢！+三句说明」结构 ——
 * 即：**保留「谢谢！」作为标题**，下方扩展成完整赞助页（三句说明 +
 * 两张二维码 + PayPal 链接），对齐三合一启动器的「支持作者」弹窗设计
 * （`install_gui.ps1` 的 `Show-SupportWindow`）。
 *
 * ⚠️ 常量名沿用 `THANKS_WINDOW_TEXT` **不**改名：`MenuProbe` §e 断言
 * 「窗口正文 == [THANKS_WINDOW_TEXT]」，改名会让那条探针在**无需**改动的
 * 位置跟着红（改名理由不足，本窗是同一族）。
 */
const val THANKS_WINDOW_TEXT = "谢谢！"

/**
 * 「投喂作者」赞助页的**三句说明**正文 —— 对齐三合一启动器的 `supportMsg`，
 * **按本插件的语境调整措辞**（用户 2026-10-17 拍板「用三合一现成的，但措辞要调整一下」；
 * 2026-10-18 二次调整，见下面的差异表）。
 *
 * ## 为什么这三句要按「插件使用场景」改，而不只是改个词
 *
 * ★ 关键前提：**本插件不是"修问题的工具"，是"陪你听歌的界面"。**
 *   用户装它的理由是**喜欢**（桌面好看、歌词好看），不是**需要**（否则活不了）。
 *   而三合一 `supportMsg` 原文的语气是**工具作者对用户的致谢**——
 *   「这个小工具**帮到了**你」预设了"用户有个问题被解决了"。
 *   ⇒ 照搬会让词岛显得像"没它就少了点什么"，而它其实是**锦上添花**。
 *
 * ## 与三合一原文的差异（逐句，2026-10-18 定稿）
 *
 * | 三合一 `supportMsg`（原文） | 本插件（定稿） | 调整理由 |
 * | --- | --- | --- |
 * | 如果这个小工具**帮到了你**，可以请我喝瓶水，**谢谢**！ | 如果**你喜欢**这个**桌面歌词岛**，可以请我喝瓶水。 | ①「帮到了你」→「你喜欢」：见上面那条前提，**审美选择**而非**被解决的问题**；②「小工具」→「桌面歌词岛」：顺带**点名插件是什么**（用户是从词岛右键菜单点进来的，说清是什么更自然）；③ **删掉句末「谢谢！」** —— 窗口主标题已经是「谢谢！」（[THANKS_WINDOW_TEXT]），**一屏之内出现两次「谢谢！」是重复**，不是强调 |
 * | 完全自愿，不影响任何功能，**也不解锁任何内容**。 | 完全自愿，不影响任何功能。 | 「也不解锁任何内容」是**针对 QST 补丁**的声明（那工具带全解锁 / DLC 解锁勾选，必须声明"赞助不解锁"免得误导买家）。本插件**没有任何"解锁"语义**（它是歌词悬浮窗外观插件），照搬会让用户困惑"这插件有什么内容需要解锁？"⇒ **删掉**，只留中性的「不影响任何功能」 |
 * | 当然，比起赞助，我更希望你能把它分享给**有同样困扰**的人。 | 当然，比起赞助，我更希望你能把它分享给**同样喜欢它**的人。 | 「有同样困扰的人」预设了"困扰"（词岛不是修 bug 工具）；「需要它的人」（2026-10-17 版）虽已去掉"困扰"，但**"需要"仍然偏高**——桌面歌词是**喜欢才装**的东西，说"需要"抬高了它的必要性 ⇒ 回到与第一句一致的「**喜欢**」 |
 *
 * ## 三句之间的用词一致性（为什么第 1 句与第 3 句都用「喜欢」）
 *
 * 刻意为之：第 1 句说「**喜欢**这个桌面歌词岛」、第 3 句说「同样**喜欢**它的人」，
 * 读下来是**同一个价值判断的两次出现**（我按你的喜好做 → 也推荐给同样按喜好挑的人）。
 * 若第 3 句换成"需要"，两句就分成"帮到"与"需要"两套语义，读者会以为
 * "这插件既是救命的又是必需的"——而它其实**只是好看**。
 *
 * ⚠️ **不要在注释与代码里重复这一整段的结尾** —— 判据 `combineLines`
 * 用「三句并作一段」断言（见 `MenuPolicy` 的 [combineLines]），
 * 复制片段会让断言与文本本身失去「同源」证明。
 *
 * ## ⚠️ 改动本常量时**必须**同步的探针
 *
 * `MenuProbe` §e2 逐句断言「窗里含三句说明」—— 它读的是**本常量**
 * （不是字面量），故改文本不会让探针红；但**改句数**（增删元素）
 * 会让 §e2 的三/四条断言索引越界 ⇒ **句数恒为 3**，这是硬约束。
 */
val SPONSOR_BODY_LINES: List<String> = listOf(
    "如果你喜欢这个桌面歌词岛，可以请我喝瓶水。",
    "完全自愿，不影响任何功能。",
    "当然，比起赞助，我更希望你能把它分享给同样喜欢它的人。",
)

/**
 * PayPal 支持区上方的小标题。
 *
 * 对齐三合一启动器的 `paypalLabel`（`install_gui.ps1`：「或通过 PayPal 支持：」）。
 * 本插件没有「或」字 —— 三合一那个「或」是因为它前面还有微信/支付宝二维码
 * （本插件只有赞助码.png 一张国内码）+ 它自己的 PayPal 码两张并排。
 * 本插件只有「赞助码 + PayPal 二维码」两张并排，PayPal 链接是**同一条
 * 赞助通道的补充**，不是"另一条路"，故去掉「或」。见 [THANKS_WINDOW_TEXT] 的 KDoc。
 */
const val SPONSOR_PAYPAL_LABEL = "通过 PayPal 支持："

/**
 * PayPal 赞助链接 —— 与三合一启动器 `paypalLink` **同一个**（用户 2026-10-17 拍板
 * 「用三合一现成的」）。收款方 = 三合一同一作者（H-2OS / 自宅警備員）。
 */
const val SPONSOR_PAYPAL_LINK = "https://www.paypal.com/ncp/payment/3687MA8M4JHGG"

/**
 * 赞助二维码的资源路径（**classpath 根相对路径**）。
 *
 * ⚠️ 文件名**故意用 ASCII**（`donate_qr.png`）而不是中文（`赞助码.png`）：
 * jar 内资源由 `ClassLoader.getResourceAsStream` 按路径取，
 * 中文路径在部分 Windows 编码 / 打包浏览器下可能取不到（GBK vs UTF-8）——
 * 在资源路径上引入"编码"这个维度不值得（`CoverArtProvider` 那条教训同理）。
 * 内容 = 三合一仓库的 `赞助码.png`（380×380）；`paypal_qr.png`（500×561）。
 */
const val SPONSOR_QR_PATH = "/sponsor/donate_qr.png"

/** PayPal 二维码资源路径（内容 = 三合一仓库的 `paypal_qr.png`）。 */
const val SPONSOR_PAYPAL_QR_PATH = "/sponsor/paypal_qr.png"

/**
 * 「源仓库跳转」的菜单文字 —— **用户原话里的名字**。
 *
 * ## 为什么改名（2026-09-22 的「项目源代码（GitHub）」→ 2026-09-23 的「源仓库跳转」）
 *
 * 用户本轮点名的五个一级项里写的是「**源仓库跳转**」。旧名有三个毛病：
 * ① **把实现细节写进名字**（"GitHub"）—— 仓库若换到 Gitee / 自建，名字立刻说谎；
 * ② **"项目源代码"是个名词**，用户扫菜单时读不出"点了会发生什么"；
 * ③ 5 个字 + 括号太长，与同层的「显示词岛」「投喂作者」不齐整。
 *
 * ⚠️ **名字的来源只有一个**：`IslandPopupMenu.build` 与 `TOP_LEVEL_MENU_ENTRIES`
 * 都必须引用本常量。写两个字面量就是"改一处漏一处"（2026-09-22 在
 * 「调整词岛设置」上踩过：表与实现各写一个字面量，探针的顺序断言当场红）。
 */
const val MENU_TITLE_SOURCE_REPO = "源仓库跳转"

// ─────────────────────────────────────────────────────────────────────────────
// 「源仓库跳转」的**可用性判据**（2026-09-23）：占位符地址 ⇒ 菜单项置灰
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话（2026-09-23）
//
// > 「**仓库地址还没有，暂定**。」
//
// ⇒ 即：这一项**当时点开必然 404**。用户能看到的应该是一个**置灰**的项
//   （而不是"点了打开一个 404 页面"—— 那看起来像插件坏了）。
//
// ## ⚠️ **已解除置灰**（2026-10-18）
//
// 用户给定真实仓库 `https://github.com/H-2OS/CDL-SPW`，已写入
// `gradle.properties` 的 `projectUrl`（**唯一真相源**）。
// 判据函数本身**一字未改** —— 它读到的地址不再含占位标记 ⇒ 自然可点。
//
// ★ **置灰逻辑刻意保留**，不因"现在用不上了"而删：
//   它守的是**回归** —— 有人把 `projectUrl` 改回占位符 / 漏填时，
//   菜单项会重新变灰而不是让用户点到 404。`MenuProbe` §L1（纯函数真值表）
//   与 §L3b2（按实读地址独立判定）**仍在守**这条判据，删掉实现会让它们红。
//
// ## ⚠️ 这里有一个**已知的结构缺陷**，如实登记（我没有擅自改别人的文件）
//
// `/project.properties` 的 `source.url` **现在有两个读者**：
//   ① `CdlRuntime.Companion.projectUrl()`（`private`，真正执行跳转的那个）
//   ② 本文件下面的 [sourceUrlOrNull]（**只**用来判断"该不该置灰"）
//
// 正确做法是让 ① 的 `projectUrl()` 从 `private` 改成 `internal`，然后 ② 委托它 ——
// **一个读者、一个真相源**。我**没有**改那个文件：`host/CdlRuntime.kt`
// 不在我的独占文件清单里，本轮有多人在并行改代码，去动共享文件有被静默覆盖的风险。
//
// ⇒ 替代的**机械护栏**（`MenuProbe` §L2）：断言下面两个字面量
//   （[PROJECT_URL_PROPERTY_KEY] / [PROJECT_URL_PLACEHOLDER_MARKER]）
//   **也出现在 `CdlRuntime.kt` 的源码里** —— 任一侧改名，探针当场红，
//   于是"两个读者漂移"这件事**不会静默发生**。
//
// > **纪律：两处读同一个外部资源时，至少要有一条断言把"两处读的是同一个东西"钉住。**
// > 否则漂移的表现是"菜单不置灰、点了 404"或"置灰了但地址其实是对的"，
// > 两种都**不报错**、都只能等用户报上来。

/** `project.properties` 里放仓库地址的那个键。⚠️ 必须与 `CdlRuntime.projectUrl()` 一致（§L2 守）。 */
const val PROJECT_URL_PROPERTY_KEY = "source.url"

/**
 * 占位符地址的**标记串**。
 *
 * ⚠️ 必须与 `CdlRuntime.projectUrl()` 的兜底地址、以及 `gradle.properties` 里的
 * `projectUrl` 一致（§L2 守）。
 * `build.gradle.kts` 的 `verifyProjectUrl` 任务拦的也是这个串（打包期）。
 */
const val PROJECT_URL_PLACEHOLDER_MARKER = "CHANGE-ME"

/** 读 `/project.properties` 的 `source.url`；缺失 / 空白 / 读不到时返回 `null`。 */
fun sourceUrlOrNull(): String? = runCatching {
    val properties = java.util.Properties()
    SourceUrlAnchor::class.java.getResourceAsStream("/project.properties")?.use(properties::load)
    properties.getProperty(PROJECT_URL_PROPERTY_KEY)?.takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * 该地址是否**仍是占位符**（或压根没有）—— 即"点它只会打开一个 404"。
 *
 * ## 为什么 `null` / 空白也算占位符
 *
 * `null` = `project.properties` 没打进包 / 键被改名；空白 = 键存在但没填。
 * ★ 2026-10-17 起 `CdlRuntime.projectUrl()` 在取不到值时**返回 `null`**
 * （不再回退到硬编码的 `CHANGE-ME` 地址），并给用户一条 toast 说明
 * "源码随包提供"⇒ **两类都会导致"跳转无处可去"**，效果与占位符一样。
 * 把这两类归到"不可用"这一侧，方向是**安全**的（宁可置灰，不要给一个 404 链接）。
 *
 * ⚠️ **纯函数**：只看入参，不读任何全局状态 —— 故探针能直接穷举它的真值表。
 */
fun isPlaceholderSourceUrl(url: String?): Boolean =
    url.isNullOrBlank() || url.contains(PROJECT_URL_PLACEHOLDER_MARKER)

/** [sourceUrlOrNull] 的类加载锚点（顶层函数拿不到 `class`，故借一个私有对象）。 */
private object SourceUrlAnchor


/**
 * 「预设列表」的菜单文字 —— **用户原话里的名字**。
 *
 * 旧名是「配置预设」。改名的理由与 [MENU_TITLE_SOURCE_REPO] 同源：
 * 用户要的是一棵**列出 `presets/` 目录里那些预设**的子树（"预设列表 ▸"），
 * 而"配置预设"听起来像"配置这个预设"，与它的实际内容（导入 / 导出 / 删除 / 更改 / 选一个）
 * 对不上。名字与结构一起改，见 `IslandPopupMenu.buildPresetMenu`。
 */
const val MENU_TITLE_PRESET_LIST = "预设列表"

/**
 * 「鼠标穿透」的菜单文字 —— **用户拍板统一后的名字**。
 *
 * ## 命名史（这一项的名字来回过一次，必须记清楚）
 *
 * | 时间 | 谁说的 | 措辞 |
 * | --- | --- | --- |
 * | 2026-09-23（本轮首次） | 用户 | 「词岛**穿透**」 |
 * | 2026-09-23（用户回答追问） | 用户 | 「菜单文案**统一为鼠标穿透**」 |
 *
 * 用户第一次写的是「词岛穿透」，而注册表里 `island.interactive` 的 `title` 本来就是
 * 「**鼠标穿透**」—— 两者是**同一个开关**。我此前**没有擅自改名**，而是记成一张
 * **别名表**（`TOP_LEVEL_TITLE_ALIASES`）来绕开。
 *
 * ⚠️ **2026-09-23 用户拍板后，别名表已删除**：用户原话「菜单文案**统一为鼠标穿透**」——
 * 即**注册表那一侧本来就是对的**，要统一就统一到「鼠标穿透」。
 * 于是别名表**没有存在理由**了（它存在的唯一前提是"两处名字不同"）。
 *
 * ## ⚠️ 为什么留一个常量（而不是就地写 `"鼠标穿透"` 字面量）
 *
 * 这个标题有**三个**消费点：`MENU_TOP_LEVEL_SWITCHES`（一级开关表）、
 * `TOP_LEVEL_MENU_ENTRIES`（一级菜单声明表）、`TOP_LEVEL_REQUIRED_TITLES`（用户点名表）。
 * 三处各写一个字面量就是"改一处漏一处" —— 本项目在「调整词岛设置」上踩过
 * （表与实现各写一个字面量，探针的顺序断言当场红）。故收口成本常量。
 *
 * ⚠️ 它必须与 `SettingRegistry` 里 `island.interactive` 的 `title` **逐字相同**
 * （`MenuRegistrySyncProbe` §A2 断言"菜单文案 == 注册表 title"）。
 */
const val MENU_TITLE_CLICK_THROUGH = "鼠标穿透"

/**
 * **用户点名的五个一级菜单项**（原话逐字，`等`字之外的五个都在这里）。
 *
 * ## 用户原话
 *
 * > 「**第一级菜单**放词岛穿透，投喂作者，源仓库跳转，更多设置，预设列表等
 * >   **常用/需明示**的项。」
 *
 * ⚠️ **第一项的用户措辞是「词岛穿透」，落地标题是「鼠标穿透」** ——
 *    用户随后拍板「菜单文案**统一为鼠标穿透**」（见 [MENU_TITLE_CLICK_THROUGH]
 *    的命名史），故本表直接写落地标题，**不再有别名表**。
 *
 * ## 为什么做成公开表（而不是让探针就地写死 5 个字面量）
 *
 * 探针里写死 5 个字面量，就多了一份**与生产代码无关的**清单 —— 它守的是
 * "探针自己记不记得住"，不是"菜单里有没有"。做成公开表之后：
 * ① 生产侧（`IslandPopupMenu.build`）与探针读**同一份**；
 * ② 用户改口时只改一处；
 * ③ `MenuProbe` 可以断言「这 5 个标题都在一级菜单里」，
 *    而且把 `build` 里那一项删掉那样的变异**当场变红**。
 *    ⚠️ **实测（2026-09-23，变异 G）**：把「投喂作者」那一行 `menu.add(...)` 去掉 ⇒
 *    `MenuProbe` **220 通过 / 5 失败**，红的是：
 *    「真实构建的菜单顶层含「投喂作者」」·「且它**排在最末**」·
 *    「一级菜单里有「投喂作者」」· **§J1b**（缺的项变成 `[投喂作者, 预设列表]`）·
 *    **§J1d**（实际一级菜单 ≠ 声明表）。
 *    ⇒ 注意 **§J1a 不会红**（它查的是**表**，表里还有这一项）——
 *      "表里有" 与 "菜单里真有" 是**两条**判据，缺一条就会漏掉一类缺陷。
 *
 * ⚠️ **顺序即用户点名的顺序**，但它**不是**一级菜单的显示顺序 ——
 * 显示顺序由 [TOP_LEVEL_MENU_ENTRIES] 决定（两者用途不同，不要合并）。
 */
val TOP_LEVEL_REQUIRED_TITLES: List<String> = listOf(
    MENU_TITLE_CLICK_THROUGH,   // 「词岛穿透」→ 用户拍板统一为「鼠标穿透」
    MENU_TITLE_FEED_AUTHOR,
    MENU_TITLE_SOURCE_REPO,
    MENU_TITLE_MORE_SETTINGS,
    MENU_TITLE_PRESET_LIST,
)

/**
 * **用户点名的五个一级菜单项**（原话逐字，`等`字之外的五个都在这里）。
 *
 * ## 用户原话
 *
 * > 「**第一级菜单**放词岛穿透，投喂作者，源仓库跳转，更多设置，预设列表等
 * >   **常用/需明示**的项。」
 *
 * ## 为什么做成公开表（而不是让探针就地写死 5 个字面量）
 *
 * 探针里写死 5 个字面量，就多了一份**与生产代码无关的**清单 —— 它守的是
 * "探针自己记不记得住"，不是"菜单里有没有"。做成公开表之后：
 * ① 生产侧（`IslandPopupMenu.build`）与探针读**同一份**；
 * ② 用户改口时只改一处；
 * ③ `MenuProbe` 可以断言「**解析后的 5 个标题都在一级菜单里**」，
 *    而且把 `build` 里那一项删掉那样的变异**当场变红**。
 *    ⚠️ **实测（2026-09-23，变异 G）**：把「投喂作者」那一行 `menu.add(...)` 去掉 ⇒
 *    `MenuProbe` **220 通过 / 5 失败**，红的是：
 *    「真实构建的菜单顶层含「投喂作者」」·「且它**排在最末**」·
 *    「一级菜单里有「投喂作者」」· **§J1b**（缺的项变成 `[投喂作者, 预设列表]`）·
 *    **§J1d**（实际一级菜单 ≠ 声明表）。
 *    ⇒ 注意 **§J1a 不会红**（它查的是**表**，表里还有这一项）——
 *      "表里有" 与 "菜单里真有" 是**两条**判据，缺一条就会漏掉一类缺陷。
 *
 * ⚠️ **顺序即用户点名的顺序**，但它**不是**一级菜单的显示顺序 ——
 * 显示顺序由 [TOP_LEVEL_MENU_ENTRIES] 决定（两者用途不同，不要合并）。
 */
val TOP_LEVEL_MENU_ENTRIES: List<TopLevelMenuEntry> = listOf(
    // 设置树（唯一的"目的地"，恒在最前）
    // ⚠️ **用常量而不是字面量**（2026-09-22）：改名前这里写的是 `"调整词岛设置"`，
    //    而 `buildSettingsTree` 里用的是另一个字面量 —— 改一处漏一处，
    //    `MenuProbe` 的"顶层项顺序逐项一致"当场红。**同一个语义只允许有一个来源。**
    //
    // ⚠️ 位置恒在**第一**：用户 2026-09-19 原话「**调整词岛设置改到右键一级菜单的顶端**」。
    //    这是同一位用户对"一级菜单长什么样"的**在先指令**，本轮**不改** ——
    //    本轮用户说的是「第一级菜单**放**…更多设置…」（"放"= 在场，**没有**指定位置），
    //    故与那条在先指令**不冲突**：两个要求可以同时满足（在场 + 仍在顶端）。
    //    ⚠️ 若哪天要把它挪走，得先拿到用户明确的新指令 —— 那会推翻一条在先指令。
    TopLevelMenuEntry(MENU_TITLE_MORE_SETTINGS, MenuItemRole.NAVIGATE),
    // 三个状态开关 —— 调参类（勾选当场可见，且用户常连着调）
    TopLevelMenuEntry("显示词岛", MenuItemRole.ADJUST),
    // 用户本轮第一次称它为「词岛穿透」，随后拍板「菜单文案**统一为鼠标穿透**」
    // —— 见 [MENU_TITLE_CLICK_THROUGH] 的命名史。**不再有别名表**。
    // ⚠️ 它同时出现在**三级**（`SettingGroups.ISLAND` ▸ 交互）—— 两处由
    //    `IslandPopupMenu.syncSwitchInstances` 联动，见 `MenuProbe` §K。
    TopLevelMenuEntry(MENU_TITLE_CLICK_THROUGH, MenuItemRole.ADJUST),
    TopLevelMenuEntry("锁定位置", MenuItemRole.ADJUST),
    // 导入字体包：打开文件选择器 → 导航类
    TopLevelMenuEntry("导入字体包…", MenuItemRole.NAVIGATE),
    // 预设列表：根是子菜单（导航），**里面的预设项是调参类**（见 buildPresetMenu）。
    // 用户本轮点名的第五项；旧名「配置预设」，改名理由见 [MENU_TITLE_PRESET_LIST]。
    TopLevelMenuEntry(MENU_TITLE_PRESET_LIST, MenuItemRole.NAVIGATE),
    // 兜底与信息
    TopLevelMenuEntry("找回词岛", MenuItemRole.ACTION),
    TopLevelMenuEntry("关于与许可", MenuItemRole.ACTION),
    // 用户本轮点名的第三项；旧名「项目源代码（GitHub）」，改名理由见 [MENU_TITLE_SOURCE_REPO]。
    TopLevelMenuEntry(MENU_TITLE_SOURCE_REPO, MenuItemRole.ACTION),
    // 投喂作者：单击拉起自绘窗口 → 动作类。
    // ⚠️ 位置**必须在最末**（紧邻「退出」，见 `IslandPopupMenu.build` 第 6.5 节）——
    //    用户原话：「一级菜单的顺序：放在**最末**（靠近"退出"这类）」。
    //    顺序不是"随便排的"：它与上面三项同属"关于本项目"那一簇，
    //    而"退出"是菜单里唯一**不可逆**的一项，把「投喂作者」压在它前面
    //    恰好让"可逆的信息项"与"不可逆的退出项"分在两边。
    TopLevelMenuEntry(MENU_TITLE_FEED_AUTHOR, MenuItemRole.ACTION),
    TopLevelMenuEntry("退出", MenuItemRole.ACTION),
)

/**
 * 「投喂作者」弹窗的配色 —— **一律转发共享色板**（`settingsui/ToolWindowPalette`）。
 *
 * ## 用户原话
 *
 * > 「单击后**拉起自绘窗口**（**同样要服从主题切换**），内容是「谢谢！」」
 *
 * ## 为什么是一个"转发对象"，而不是几个颜色常量
 *
 * 本项目"自绘窗口的配色"**只有一处来源**：`settingsui/ToolWindowPalette`
 * （它的 KDoc 记着 BUG-108：同一套深浅色曾在三处各写一遍，于是"取色窗跟了主题、
 * 字体窗没跟"这种同族漏改发生了）。
 * 故这里**不出现任何颜色字面量** —— 三个 `get()` 全部转发到色板。
 * 于是"主题变了弹窗跟着变"是**结构性保证**，而不是"记得两处一起改"。
 *
 * ## 为什么是 `get()` 而不是 `val`（初始化时快照）
 *
 * 色板每次读取都**重新问系统主题**（`ToolWindowPalette.isLight`）——
 * 用户可能在弹窗开着的时候切深浅色。写成 `val` 会把第一次读到的颜色**冻住**，
 * 那正是"不服从主题切换"的另一种形态（能跟但只跟一次）。
 *
 * ## 探针怎么守它（`MenuProbe`）
 *
 * ① 三个槽位与色板**逐值相等** → 有人把颜色硬编码进弹窗，当场红；
 * ② 底色 ≠ 前景色 → 证明它真的取了两个**不同**的槽，而不是全填同一个值糊过去。
 */
object ThanksWindowSkin {
    /** 弹窗底（内容面板 + rootPane）。 */
    val background: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.bg

    /** 弹窗正文（[THANKS_WINDOW_TEXT]）的颜色。 */
    val foreground: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.fg

    /** 弹窗边线。 */
    val border: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.line

    /**
     * **次级文字**（三句说明正文 / 「通过 PayPal 支持：」小标题）。
     *
     * 底色之上正文之下 —— 说明文字不该与主标题抢视觉，用 [muted]。
     */
    val muted: java.awt.Color get() = dev.cdl.settingsui.ToolWindowPalette.muted

    /**
     * **PayPal 链接文字**（可点击，唯一带"链接感"的颜色）。
     *
     * 不走 `ToolWindowPalette`（它没有"链接"槽）—— 这里从前景色派生：
     * 红色通道提一档、蓝色通道压一档得到偏蓝的"链接蓝"，
     * 深浅主题下都可读，且**不以字面量硬编码**（色板一变它自动跟）。
     */
    val linkFg: java.awt.Color
        get() {
            val base = dev.cdl.settingsui.ToolWindowPalette.fg
            val r = (base.red * 5 / 6).coerceIn(0, 255)
            val g = base.green
            val b = (base.blue * 2).coerceIn(0, 255)
            return java.awt.Color(r, g, b)
        }
}

/**
 * **按与右键菜单完全相同的代码路径**构建一次菜单，但**不显示**它 —— 供探针检查。
 *
 * ## 为什么需要这个"转发函数"（而不是让探针直接调 `IslandPopupMenu.build`）
 *
 * `IslandPopupMenu` 是 `internal object`，且它的 `build` 第二个参数是
 * `internal class IslandWindow` —— 探针源集是**独立的编译单元**（`build.gradle.kts`
 * 的 `sourceSets.create("probe")`，**没有** `associateWith`），故 `internal` 一概看不到。
 *
 * ## 为什么这是"转发"而不是"为了探针造的假入口"
 *
 * 它**不含任何逻辑** —— 只是一句转调，走的是**同一条生产代码路径**
 * （`IslandPopupMenu.build`）。这与本项目里 `MENU_BUCKETS` /
 * `TOP_LEVEL_MENU_ENTRIES` / `menuDismissFor` 被公开的**同一条理由**：
 * 判据要能被断言，而断言必须落在**真实实现**上，不能落在复刻上。
 *
 * ## 为什么 `settings` 只能传 `null`（本函数的已知局限）
 *
 * `SettingsRuntime` 的构造器是 `internal`，探针造不出来。故这条路径等价于
 * 「设置系统不可用时的托盘兜底路径」—— 它**本身是生产路径之一**（见
 * `IslandPopupMenu.build` 的 KDoc），代价是 `settings != null` 才会 add 的
 * 「预设列表」子树不在结果里。`MenuProbe` 的顺序断言因此写成"表 ∩ 实际"。
 */
fun buildMenuForInspection(): javax.swing.JPopupMenu =
    IslandPopupMenu.build(settings = null, window = null)

// ─────────────────────────────────────────────────────────────────────────────
// 右键菜单 ↔ 设置注册表：**手写表**与**可达性判据**（2026-09-22）
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话（本轮诉求，逐字）
//
// > 「右键菜单的设置和 SPW 的设置项**同步一下。避免缺漏**。」
//
// ## 这句话落成两条可断言的判据
//
// | 判据 | 说的是什么 | 谁守它 |
// | --- | --- | --- |
// | **菜单引用的键必须存在** | 表里写了注册表没有的键 = 悬空引用（点了没反应） | `MenuRegistrySyncProbe` §A |
// | **每个设置项至少一处能改** | 菜单与设置页**合起来**不许有"两处都改不到"的项 | `MenuRegistrySyncProbe` §C |
//
// 第一条是本轮要修的**真缺陷类**（BUG-113 的同形：静默消失、不报错）；
// 第二条是用户说的"避免缺漏"的**字面含义**。
//
// ## 为什么要把这几张**手写表**从 `IslandPopupMenu` 搬到这里
//
// 与 `MENU_BUCKETS` / `TOP_LEVEL_MENU_ENTRIES` / `menuDismissFor` 被公开**同一条理由**：
// 它们是菜单侧**手写**的、**与注册表之间没有编译期约束**的那部分数据 ——
// 键名写错只会让"那一项在菜单里不对"，**不报错、不影响启动**。
// 探针源集是独立编译单元（看不到 `private` / `internal`），
// 故**要断言就得先让它可见**。搬到这里之后：
//   · `IslandPopupMenu` 用 `private val X = MENU_X` 别名（调用点不必全改）；
//   · 探针读 `MENU_X` 逐条核对 ⇒ 悬空引用从"运行期 stderr 告警"升级为**门禁红灯**。
//
// > **纪律：散落的手写表与注册表之间没有编译期约束时，必须把表本身暴露给探针。**
// > 只打 stderr 不算 —— 那种告警没有人跑，也就没有人看（BUG-113 的教训）。

/**
 * 一个**顶层状态开关**菜单项的描述：键 + 显示文案。
 *
 * ⚠️ 两项都必须在注册表里存在且为 `SWITCH`，且 [title] 必须与注册表里的
 * `SettingDescriptor.title` **逐字相同** —— 后者由 `MenuRegistrySyncProbe` 断言。
 * 菜单与设置页对同一项用两个文案，正是用户说的"没有同步"。
 */
data class MenuSwitch(
    /** 注册表键。必须在 `SettingRegistry` 里存在且 `kind == SWITCH`。 */
    val key: String,
    /** 菜单上显示的文字（必须 == 注册表里该项的 `title`）。 */
    val title: String,
)

/**
 * **顶层三个状态开关**的键与文案。**顺序即显示顺序**。
 *
 * ## 为什么这三项要留在顶层（而不并进注册表树）
 *
 * 它们是"词岛现在能不能用 / 能不能点"的**状态开关**，属于**每次右键都可能动**的那一类：
 * 想暂时藏起词岛、想临时穿透、想锁住位置防误拖 —— 都要一次点到。
 * 放进两层子菜单等于把最常用的三个动作藏起来
 * （原实现也是把它们平铺在最前面，`089:ui/IslandMenu.kt:29-30`）。
 *
 * ## 与 `SettingGroups.TOP_LEVEL`（「总开关」组）的关系
 *
 * **不同的一组**，别混：`TOP_LEVEL` 组装的是"整块功能开关"（频谱 / 封面 / 动画…），
 * 而本表是"词岛自己"的三个状态开关。`island.enabled` 同时属于两处 ——
 * 它在 `TOP_LEVEL` 组里，**也在树里出现**。
 *
 * ## ⚠️ 2026-09-23：**这三项现在会同时出现在一级与三级**
 *
 * 此前 `island.enabled` / `island.interactive` / `island.lock_position` 在树里被
 * `MENU_TOP_LEVEL_KEYS` **排除**（"别有两个入口"）。用户 2026-09-23 明确要求
 * **两处都要有**：
 *
 * > 「注意，**即使一级菜单中有这个设置项，在三级细分菜单中也应该还有这个设置项，
 * >   方便找**。同时**设置要同步**…不应该出现某处开，某处关的误导现象。」
 *
 * ⇒ 排除已删除；**"两处不一致"改由联动解决**（`IslandPopupMenu.syncSwitchInstances`，
 *    判据 `MenuProbe` §K）。见本文件里 `MENU_TOP_LEVEL_KEYS` 那段已删说明的完整论证。
 */
val MENU_TOP_LEVEL_SWITCHES: List<MenuSwitch> = listOf(
    MenuSwitch(key = "island.enabled", title = "显示词岛"),
    // ⚠️ 标题走常量（三个消费点共用，见 [MENU_TITLE_CLICK_THROUGH]）。
    MenuSwitch(key = "island.interactive", title = MENU_TITLE_CLICK_THROUGH),
    MenuSwitch(key = "island.lock_position", title = "锁定位置"),
)

/**
 * 数值项的**单位表**（只影响显示，如 `64%` / `640px` / `120ms`）。
 *
 * 空串 = 无单位（字号、圆角已是"%"之外的自然数）。
 *
 * ## ⚠️ 已知的**取值域不一致**（本轮登记，未修 —— 决定权留给 team-lead）
 *
 * 注册表里有 **52 个 `SEEKBAR`**，而本表只有 **12 条** ⇒ 另外 40 根步进器
 * **不显示单位**（`64` 而不是 `64px`）。而**设置页那一侧同样没有单位信息**
 * （`SettingDescriptor` 没有 `unit` 字段）——
 * 故这不是"菜单与设置页不一致"，而是**两边都缺**。
 *
 * 正确做法（`SettingDescriptor.step` 的 KDoc 里已用**同一个论证**兑现过一次）：
 * 在 `SettingDescriptor` 上加一个可选的 `unit` 字段，让单位跟着**设置项的定义**走 ——
 * 那时它就不再是"菜单侧的交互参数"，而是该项的属性。
 * ★ 2026-10-14：`step` 已经这样落地了（`SettingDescriptor.step`）——
 *   本条（`unit`）**仍待做**，理由见下。
 * ⚠️ 本轮**不动**它：改 `SettingDescriptor` 的 `unit` 要碰 `SettingRegistry` 的 143 项，
 * 且那是一次跨全表的改动，不属于"菜单与设置项同步"这条线。
 *
 * ⚠️ 表里每个键都必须存在且为 `SEEKBAR`（`MenuRegistrySyncProbe` §A 断言）。
 */
val MENU_UNIT_BY_KEY: Map<String, String> = mapOf(
    "layout.max_width" to "px",
    "layout.line_gap_px" to "px",
    "shape.corner_radius" to "%",
    "background.opacity" to "%",
    "controls.hitPaddingPx" to "px",
    "controls.gapPx" to "px",
    "lyric.offset_ms" to "ms",
    "controls.enterDelayMs" to "ms",
    "controls.exitDelayMs" to "ms",
    "controls.autoHideMs" to "ms",
    // 子菜单的展开/收起延迟（`SettingGroups.MENU_SETTINGS`）。
    // 不加这两条不会报错 —— 步进器会把 `180` 显示成没有单位的 `180`。
    // 故这条属于"漏了也不出声"的那类。
    SUBMENU_OPEN_DELAY_KEY to "ms",
    SUBMENU_CLOSE_DELAY_KEY to "ms",
)

/**
 * 「最近使用字体」子菜单在**换字体时要一起写的第二个键**（`main_lyric.font.family`）。
 *
 * ## 为什么把它抽成常量
 *
 * 它原先在 `IslandPopupMenu.recentFontItem` 里是**字符串字面量**，
 * 而字面量拼错**不报错** —— 只会静默回退默认值（表现为"换字体没反应"）。
 * 抽成常量后，`MenuRegistrySyncProbe` 能断言它存在且 `kind == FONT`。
 */
const val MENU_FONT_FAMILY_KEY = "main_lyric.font.family"

/**
 * 一个设置键在**右键菜单**里的暴露形态 —— 见 [menuExposureFor]。
 *
 * ⚠️ 加新常量时编译器会逼你表态：`IslandPopupMenu.menuItemFor` 的 `when`
 * **没有 `else`** ⇒ 漏一个分支就编译失败（与 `SettingKind` 的处置同一纪律）。
 */
enum class MenuExposure {
    /** 一个**打开取色器**的入口，承载一个颜色组的**全部键**（见 `SettingRegistry.COLOR_GROUPS`）。 */
    COLOR_ENTRY,

    /** 勾选菜单项（`JCheckBoxMenuItem`）。 */
    SWITCH,

    /** `− 值 +` 步进器（`IslandMenuSkin.MenuStepper`）。 */
    STEPPER,

    /** 单选子菜单（`JRadioButtonMenuItem` + `ButtonGroup`）。 */
    RADIO_LIST,

    /** 「最近使用字体」子菜单 —— `LIST` 且**没有固定候选**（`main_lyric.font.recent`）。 */
    FONT_RECENT,

    /** 菜单里**不单独出现** —— 由 SPW 设置页或取色器承载。 */
    NOT_IN_MENU,
}

/**
 * 某个设置项在右键菜单里的暴露形态 —— **设置树构建逻辑的唯一判据来源**。
 *
 * ## 为什么必须抽成公开纯函数
 *
 * 这段判断原先**只写在** `IslandPopupMenu.menuItemFor` 的 `when` 里，
 * 而探针（独立编译单元）看不到 `internal` 的 `IslandPopupMenu` ⇒
 * "注册表加了一项，菜单会不会自动出现"**只能靠读代码猜**。
 * 抽出来之后，探针能**穷举注册表的每一个键**逐一断言（见 `MenuRegistrySyncProbe`）。
 *
 * > **纪律：为测试发愁先抽纯函数，而不是改可见性**（见 §14.14）——
 * > 与 `menuSupportsKind` / `MENU_BUCKETS` / `menuDismissFor` 被公开同一条理由。
 *
 * ## ⚠️ 唯一真相源
 *
 * `IslandPopupMenu.menuItemFor` **必须**消费本函数（它现在确实消费）。
 * 若哪天有人在那边自己再写一套 `when`，两条判据会漂移，
 * 而漂移的表现是"**探针说在菜单里、实际不在**" —— 那正是本条要防的**假绿**。
 *
 * ## 判据（顺序与 `menuItemFor` **逐条对应**）
 *
 * | 情况 | 形态 | 为什么 |
 * | --- | --- | --- |
 * | 属于某个颜色组，且是该组的**第一个**键 | [MenuExposure.COLOR_ENTRY] | 一个取色器承载整组；给每个键各一个入口会并排出现 4 个同名项 |
 * | 属于某个颜色组，但不是第一个键 | [MenuExposure.NOT_IN_MENU] | 同上（去重）—— 但它**仍可改**（走那一组的入口） |
 * | `SWITCH` | [MenuExposure.SWITCH] | 勾选项 |
 * | `SEEKBAR` | [MenuExposure.STEPPER] | `− 值 +` |
 * | `LIST` 且有固定候选 | [MenuExposure.RADIO_LIST] | 单选子菜单 |
 * | `LIST` 且**无**固定候选 | [MenuExposure.FONT_RECENT] | 开放集合 → 「最近使用字体」 |
 * | `COLOR`（未被颜色组覆盖） | [MenuExposure.NOT_IN_MENU] | 菜单没有取色控件 |
 * | `FONT` / `EDITTEXT` | [MenuExposure.NOT_IN_MENU] | 250 个字体族 / 复合值，菜单放不下 |
 *
 * @return 该键在菜单里的形态；[MenuExposure.NOT_IN_MENU] 表示菜单里**没有它自己的条目**
 *   （仍可能通过所属颜色组的入口改到 —— 见 [menuReachableKeys]）。
 */
fun menuExposureFor(descriptor: SettingDescriptor): MenuExposure {
    // ★★★ 2026-10-12（**BUG-168**）：★ **已下架（`retired`）的项【不进右键菜单】** ✗
    //
    // ## 用户报障（逐字）
    //
    // > 「**右键菜单内还残留液态玻璃相关设置项未清理。**」
    //
    // ## 根因：★ 下架只做了一半
    //
    // ★ 2026-10-11 的液态玻璃下架引入了 `SettingDescriptor.retired`，其 KDoc 写的是
    //   「**从用户可见的设置面移除**」—— ★ 而**消费它的只有两处**：
    //   · `SettingRegistry` 的**设置页**链：`.filterNot { it.retired }` ✓
    //   · `tools/gen_preference_config.py`（生成 `preference_config.json`）✓
    // ★★ **`menuExposureFor`（右键菜单的**唯一判据**）没有过滤** ✗
    //   ⇒ ★ 13 个 retired 键里落在 `material` / `menu` 组的那些**照样长成菜单项** ✗
    //
    // ## 判据：与设置页**同一条语义**（而不是各写一套）
    //
    // ★ "用户可见的设置面" = **设置页 ∪ 右键菜单 ∪ 自绘窗口** ——
    //   ★ 任一处漏了，下架就没做干净（本次就是菜单这一处）✗
    // ★ 故本行是**必要的一行**，不是"顺手加的"✓
    //
    // ⚠️ **`MenuRegistrySyncProbe` §C 的不变式要同步**：★ 它断言"每个键要么在菜单里、
    //    要么在设置页里"—— ★ retired 键**两边都不在**（那正是"下架"的定义）
    //    ⇒ ★ 该不变式必须**排除 retired 键**，否则它会**误报** ✓
    if (descriptor.retired) return MenuExposure.NOT_IN_MENU
    // ① **颜色组优先于 kind**（与 `menuItemFor` 同序，顺序不能换）：
    //    颜色键的 kind 是 SEEKBAR（12 根 RGB/明度滑杆），若不先拦，
    //    它们会各自长成一个 `− 值 +` 步进器 —— 而用户明确要求「不再使用三色 RGB 滑杆选色」。
    SettingRegistry.colorGroupFor(descriptor.key)?.let { group ->
        return if (descriptor.key == group.keys.first()) {
            MenuExposure.COLOR_ENTRY
        } else {
            MenuExposure.NOT_IN_MENU
        }
    }
    return when (descriptor.kind) {
        SettingKind.SWITCH -> MenuExposure.SWITCH
        SettingKind.SEEKBAR -> MenuExposure.STEPPER
        SettingKind.LIST ->
            if (descriptor.options.isNullOrEmpty()) MenuExposure.FONT_RECENT else MenuExposure.RADIO_LIST
        // ⚠️ 不加 `else`：将来 `SettingKind` 新增类型时这里**编译失败**，
        //    而不是静默落进 `NOT_IN_MENU`（那正是"菜单里少了一项"的成因）。
        SettingKind.COLOR, SettingKind.FONT, SettingKind.EDITTEXT -> MenuExposure.NOT_IN_MENU
    }
}

/**
 * **右键菜单里能改到的键全集** —— 与 `IslandPopupMenu` 的构建逻辑一一对应。
 *
 * ## 为什么颜色组要**整组**算进来
 *
 * 一个颜色组只有**第一个键**有菜单条目（[MenuExposure.COLOR_ENTRY]），
 * 但那个取色器**读写整组 4 个键**（`CdlRuntime.openColorGroup`）。
 * 若只算"有自己的条目的键"，12 根歌词色滑杆会被误判成"菜单里改不到"——
 * 而它们**恰恰是**快捷调色的主路径。
 *
 * > **教训：判据漏掉一个"可达路径"就会把正常状态报成缺陷**，
 * > 而"报错报在正常状态上"的断言很快会被无视（比没有更糟）。
 *
 * ## 用它守什么
 *
 * `MenuRegistrySyncProbe` §C：**注册表里每个"用户设置项"都必须落在
 * [menuReachableKeys] 或 SPW 设置页（`spwPageDescriptors`）里至少一处** ——
 * 这正是用户说的「同步一下，避免缺漏」的**字面含义**。
 */
fun menuReachableKeys(): Set<String> =
    SettingRegistry.all.flatMap { descriptor ->
        val group = SettingRegistry.colorGroupFor(descriptor.key)
        when {
            // 颜色组：整组的键都由那一个取色器入口承载。
            group != null -> group.keys
            menuExposureFor(descriptor) != MenuExposure.NOT_IN_MENU -> listOf(descriptor.key)
            else -> emptyList()
        }
    }.toSet()

// ─────────────────────────────────────────────────────────────────────────────
// 设置树的**三级结构**（2026-09-23）：`更多设置 ▸ 组件 ▸ 设置项`
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 用户原话
//
// > 「**二级菜单**比如更多设置的子菜单，该子菜单中**以组件名为选项名**，
// >   再接着展出**第三级菜单**，显示**组件下的设置项**。」
//
// ## 这三条判据为什么必须抽成公开纯函数（而不是留在 `IslandPopupMenu` 里）
//
// 与 `menuExposureFor` 被抽出来**同一条理由**：`IslandPopupMenu` 是 `internal`，
// 探针源集是**独立编译单元**看不到它 ⇒ "二级是不是那 11 个组件""三级是不是该组件的全部项"
// 这两条**只能靠读代码猜**。抽成公开函数之后，探针能对**真实构建出的菜单**逐层断言。
//
// > **纪律：为测试发愁先抽纯函数，而不是改可见性**（见 §14.14）。

//
 // ⚠️ **2026-09-23 已删除 `MENU_TOP_LEVEL_KEYS`（原来在这里）。**
 //
 // ## 它原来做什么
 //
 // 从 [MENU_TOP_LEVEL_SWITCHES] 派生出一组键，`menuItemsForComponent` 用
 // `.filterNot { it.key in MENU_TOP_LEVEL_KEYS }` **把一级菜单已有独立入口的项
 // 从三级里剔除** —— 理由是"同一项别出现两个入口"。
 //
 // ## 为什么删（用户明确禁止了这个行为）
 //
 // 用户原话（2026-09-23，逐字）：
 //
 // > 「注意，**即使一级菜单中有这个设置项，在三级细分菜单中也应该还有这个设置项，
 // >   方便找**。同时**设置要同步**，不管是一级和三级，还是右键菜单和 SPW 设置页，
 // >   都应该是**同一种状态**，不应该出现某处开，某处关的误导现象。」
 //
 // 即：**一级与三级同时存在是用户要的**（"方便找"），而他担心的"两处不一致"
 // 用**同步**解决，**不是**用**删除一处**解决。
 //
 // ## ⚠️ 旧理由（"同一项两个入口 = 界面开始说谎"）**没有消失，而是换了兑现方式**
 //
 // 那个担忧是**对的** —— 两处独立勾选框、各写各的，确实会一处开一处关。
 // 但它**不能**用"删掉一处"来兑现（那正是用户禁止的），必须用"**两处联动**"来兑现：
 // `IslandPopupMenu` 里有一个**同键多实例同步器**（`syncSwitchInstances`），
 // 点任一处后把**同一份菜单里**该键的所有实例刷成同一个值。
 // 判据在 `MenuProbe` §K（4 条，两个方向都测）。
 //
 // > **纪律：两个视图不一致时，正确答案是"让它们同源"，不是"删掉一个视图"** ——
 // > 后者把"不一致"换成了"少一个入口"，而用户两个都要。
 //

/**
 * 记在一个菜单项上的**设置键**（Swing client property 名）—— 探针据此逐项核对。
 *
 * ## 为什么需要它
 *
 * "三级 = 该组件下的**全部**设置项"这条断言，如果只比**条目数**，会漏掉一整类错误：
 * 拿 A 项的标题去建 B 项的控件（数目对得上、键却是错的）。要比键就必须能从
 * `JMenuItem` 反查出键 —— 而 `JMenuItem` 上没有这个字段，只能自己打标记。
 *
 * ## 为什么用 client property 而不是 `name`
 *
 * `Component.getName()` 会被 LAF / 布局在某些路径上读写，语义不专属；
 * `putClientProperty` 是本项目既有做法（见 `IslandMenuSkin.KEEP_OPEN_KEY` 的 KDoc：
 * "两侧的耦合面就只剩一个字符串常量"）。
 *
 * ⚠️ 本字面量在 `MenuRegistrySyncProbe` 的 `NON_KEY_LITERALS` 里**已登记**
 * （它长得像设置键但不是）—— 登记它正是为了那条"源码里所有像键的字面量都必须在注册表里"
 * 的穷尽扫描不误报。
 */
const val MENU_ITEM_KEY_PROPERTY = "cdl.menu.itemKeys"

/**
 * 某个**组件**（`MENU_COMPONENT_SECTIONS` 的一项）在**菜单三级**里会出现的设置项，
 * 顺序 = 注册表顺序（`SettingRegistry.byGroup` 的顺序）。
 *
 * ## 判据（三条，缺一不可）
 *
 * ① 属于该组件（`byGroup`）；
 * ② [menuExposureFor] != [MenuExposure.NOT_IN_MENU] —— 菜单表达不了的不算
 *    （`FONT` / `EDITTEXT` / 颜色组的非首键，由设置页或取色器入口承载）；
 * ③ ★★★ **不是已下架项**（`retired`）—— 见下。
 *
 * ## ★★★ 2026-10-17：补上第 ③ 条（`retired` 过滤）
 *
 * 用户指令：「**将「锁定封面（悬浮窗态）」设置项隐藏，清理那些连带该隐藏的设置项**」。
 *
 * ★ **发现的问题**：`retired` 此前只被**两个**设置面消费 ——
 *   `gen_preference_config.py`（SPW 设置页）与 `SettingRegistry.spwPageDescriptors`
 *   （自绘窗口）⇒ ★ **右键菜单漏了**：已下架项（液态玻璃 9 项 / GPU 1 项 / 封面锁 1 项）
 *   仍会在三级菜单里生成条目 ✗
 * ⇒ 用户把设置页上的项隐藏了、菜单里却还在 ⇒ **"隐藏"只兑现了一半** ✗
 *
 * ★ 修法：在**唯一筛选出口**加一条 `filterNot { it.retired }` ——
 *   生产侧（`IslandPopupMenu.buildSettingsTree`）与探针侧（`MenuProbe` §J4）
 *   消费的是**同一个函数**，故两侧自动同源 ✓
 *
 * ⚠️ **副作用（如实登记）**：`ISLAND_MATERIAL` 的「身后窗口」细类 6 项**全部**是
 *   `retired`（液态玻璃/背景折射那批）⇒ 该细类在菜单里**整段消失**（分隔线也不出现，
 *   因为分隔线按 `subGroup` 变化插）。这是**正确**的：那批项已无设置面，
 *   菜单里留着等于"两个地方都能改"的镜像缺陷 ✓
 *
 * ## ⚠️ 2026-09-23：**删掉了旧的第三条判据**（"不在一级菜单已有入口里"）
 *
 * 原来还有一条 `.filterNot { it.key in MENU_TOP_LEVEL_KEYS }`，把一级菜单已有独立
 * 入口的项从三级里**剔除**。用户 2026-09-23 明确禁止了这个行为：
 *
 * > 「注意，**即使一级菜单中有这个设置项，在三级细分菜单中也应该还有这个设置项，
 * >   方便找**。同时**设置要同步**…不应该出现某处开，某处关的误导现象。」
 *
 * ⇒ 一级与三级**同时存在**是用户要的；"两处不一致"用**联动**解决，不用删除解决。
 * 完整论证与"旧担忧换了什么兑现方式"见 `MENU_TOP_LEVEL_KEYS` 那一段（已删，就地留说明）。
 *
 * ⚠️ 连带效果：本函数返回的项数**变多了**（一级那三个开关现在也在三级里），
 *    故 `MenuProbe` §J4 的"三级 = 该组件下**全部**设置项"那条断言**自动变严** ——
 *    它核的是**键集合相等**，不再有"哪些键被排除"这种隐藏规则。
 *
 * ⚠️ **唯一真相源**：`IslandPopupMenu.buildSettingsTree` **必须**消费本函数。
 * 若哪天有人在那边自己再筛一遍，两条判据会漂移，而漂移的表现是
 * "**探针说三级该有 20 项、实际菜单里 19 项**" —— 那正是本条要防的假绿。
 *
 * @return 会生成菜单条目的 descriptor（**不含**分隔线；分隔线由构建侧按 `subGroup` 插）。
 */
fun menuItemsForComponent(component: String): List<SettingDescriptor> =
    SettingRegistry.byGroup[component].orEmpty()
        // ★★★ 2026-10-17：**已下架项不进菜单**（与 SPW 设置页 / 自绘窗口同一条判据）。
        //   `retired` 的语义是"从**用户可见的设置面**移除"—— 菜单也是设置面 ✓
        .filterNot { it.retired }
        .filter { menuExposureFor(it) != MenuExposure.NOT_IN_MENU }

/**
 * 一个设置项在菜单上显示的**文字** —— 与 `IslandPopupMenu.menuItemFor` **逐条对应**。
 *
 * ## 为什么必须抽出来（不能各写一份）
 *
 * 绝大多数项的菜单文字就是 `descriptor.title`，**只有颜色入口是例外**：
 * 它显示的是 `${group.title}…`（一个取色器承载整组 4 键，故标题是**组名** + 省略号，
 * 表示"点了会开窗"）。若探针按 `descriptor.title` 去比对，那一项会**永远对不上**，
 * 于是那条断言只能被放宽成"只比数目" —— 而"比数目"拦不住"标题串了"。
 *
 * @return 菜单文字；`…` 是**取色器入口**的标记（与 `IslandPopupMenu` 同形）。
 */
fun menuItemTitleFor(descriptor: SettingDescriptor): String =
    SettingRegistry.colorGroupFor(descriptor.key)?.let { "${it.title}…" } ?: descriptor.title

/**
 * 一个**已构建的**三级菜单项对用户显示的标题 —— **唯一读取出口**（供 `MenuProbe` §J4b）。
 *
 * ## 为什么不能只读 `JMenuItem.text`
 *
 * 三级菜单项有**两种形态**（`IslandPopupMenu.menuItemFor` 的 `when` 决定）：
 *
 * | 形态 | 标题在哪 | `JMenuItem.text` |
 * | --- | --- | --- |
 * | `SWITCH` / `COLOR_ENTRY` / `RADIO_LIST` / `FONT_RECENT` | `JMenuItem` 自己 | **有值** |
 * | `STEPPER` | **`IslandMenuSkin.MenuStepper` 内部**（自绘组件） | **空串** |
 *
 * 2026-09-23 实测：只读 `text` 会让**全部 31 个步进器项**被报成
 * 「菜单「」≠ 期望「字号」」—— 那不是缺陷，是**探针读错了地方**。
 *
 * ## 为什么读 `MenuStepper.label` 而不是"在 `stepperItem` 里打个 title 标记再断言"
 *
 * 后者是**断言自己刚写下的值** ⇒ 恒真 ⇒ 假绿（改坏 `stepperItem` 里的 label 也不会红）。
 * 本函数读的是**真正被绘制的那一个字符串**（`MenuStepper` 用它画标签）。
 *
 * ⚠️ 这也是 `MenuStepper.label` 从 `private` 改为 `internal` 可见的**唯一理由** ——
 *    `MenuStepper` 本身是 `internal class`，故对外 API 没有变宽。
 *
 * @return 用户能看到的标题；两形态都不匹配时返回空串（调用方应把它当成**失败**）。
 */
fun menuItemVisibleTitle(item: javax.swing.JMenuItem): String {
    val text = item.text
    if (!text.isNullOrEmpty()) return text
    for (i in 0 until item.componentCount) {
        val child = item.getComponent(i)
        if (child is IslandMenuSkin.MenuStepper) return child.label
    }
    return ""
}
