// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

import java.awt.Color

/**
 * 设置项的类型。
 *
 * ## 为什么与大纲 §9.1 的枚举**不完全一致**（这是有意的偏离，必须写明理由）
 *
 * 大纲 §9.1 原定义是：
 * ```
 * enum class SettingKind { SWITCH, SLIDER, COLOR, GRADIENT, FONT, TEXT, LIST, BUTTON, OFFSET }
 * ```
 * 而 `首版设置总表.md` §1 的列说明用的是另一套：
 * `switch / seekbar / list / edittext / color / font / button`。
 *
 * 两套的差异有三处，**本实现取交集并按首版的语义重命名**：
 *
 * | 大纲 §9.1 | 首版设置总表 | 本实现的处置 |
 * |---|---|---|
 * | `SLIDER` | `seekbar` | 合并为 [SEEKBAR]。**理由**：总表 §1 的 10 个数值项全部是"整数滑杆 + 范围"，`SLIDER` 与 `seekbar` 指同一种控件；保留两个名字只会让注册表出现"同一控件两种 kind"的分裂 |
 * | `TEXT` | `edittext` | 合并为 [EDITTEXT]。同上，总表 §1 序号 11 是唯一的文本项 |
 * | `OFFSET` | （无） | **删除**。总表的 `lyric.offset_ms`（序号 39）类型写的是 `seekbar`（范围 −2000–2000），不是独立类型 |
 * | `GRADIENT` / `BUTTON` | （首版无 / 有但非设置项） | **删除**。渐变在首版 40 项里没有住户（大纲 §9.6 明示渐变属 P2+）；`button` 是动作入口不是设置项（总表 §1.11 明示"**非设置项**"），按钮不走注册表读写路径 |
 *
 * **净结果**：6 个 kind 恰好覆盖总表 §1 的全部设置项，无一个 kind 空置、
 * 无一项落在 kind 之外。这比照抄 §9.1 的 9 个 kind 留下 3 个空壳更诚实。
 *
 * ⚠️ **将来 P2+ 若引入渐变设置项，需要把 `GRADIENT` 加回来** —— 届时是**加**而非改，
 * 因为 [SettingRegistry] 是唯一事实来源，加 kind 只影响新增项。
 */
enum class SettingKind {
    /** 布尔开关。总表 17 项。 */
    SWITCH,

    /** 整数滑杆（含范围 clamp）。总表 10 项。 */
    SEEKBAR,

    /** 枚举列表（值 → 显示名）。总表 9 项。 */
    LIST,

    /** 文本输入。总表仅 1 项（`window.position` 的复合值）。 */
    EDITTEXT,

    /** 取色器（RGB 三滑杆 + hex 双向同步）。总表 2 项。 */
    COLOR,

    /** 字体选择器（系统字体族 + 随带字体）。总表 1 项。 */
    FONT,
}

/**
 * 设置项归属的实现阶段。
 *
 * 口径取自 `首版设置总表.md` §0.2 口径 3，**不是**"当前是否已实现"。
 * 它决定的是**界面何时接**，而不是**值何时可读写** —— 全部设置项的读写链路在本版一律可用，
 * 这正是"P0 只落 6 项到可观察状态"的准确含义：6 项有界面/消费者，34 项有定义与读写。
 */
enum class SettingPhase {
    /** 骨架 + 设置读写与持久化。6 项：序号 1 · 9 · 11 · 36 · 37 · 38。 */
    P0,

    /** 极简设置窗口 + 取色器 + 拖动中实时预览。34 项。 */
    P1,

    /** 首版之后的全量扩充。本版 0 项。 */
    P4,
}

/**
 * 该设置项如何影响实时预览 —— 决定变更走哪条节流通道。
 *
 * 口径取自大纲 §9.7.2「实时预览的粒度」的代价分类表。**这是本类的存在理由**：
 * 若不把"代价档"写进 descriptor，节流策略就只能散落在 UI 代码里硬编码，
 * 加一项设置就要去 UI 里补一次判断（双清单漂移的翻版）。
 */
enum class PreviewKind {
    /**
     * 颜色类：拖动一次的代价近零（只换一个 `Color` 引用）→ **不节流**。
     *
     * 为什么不能对颜色做 80 ms 合并：大纲 §9.7.2 引 `recon/07` §1.2 的判定 ——
     * 合并会让调色**发涩**（用户拖滑杆时颜色跳变而非连续），体验反而变差。
     */
    IMMEDIATE,

    /**
     * 数值类：触发字形重塑 / 布局重算，代价中 → **80 ms 合并**（≈12.5 Hz）。
     *
     * 为什么是 80 ms 而不是 16 ms：合并的目标是"限制重算频率"而非"每帧都算"；
     * 80 ms 在人眼仍感知为连续（≈12.5 Hz），又足以把重算次数压到 1/5。
     */
    THROTTLED,

    /**
     * 重资源类（字体族切换）：`Font.createFont` / 全量字形重塑**无法在 80 ms 内完成**。
     *
     * ⚠️ 这是**技术不可能，不是取舍**（大纲 §9.7.2 硬要求 3 原文）。
     * 处理方式：拖动中先按回退字体渲染并保持布局稳定，松手后才换真字体。
     * **唯一的延迟例外**，且必须在验收时明确告知用户"字体切换在松手后完全生效"。
     */
    DEFERRED,
}

/**
 * 单个设置项的元数据 —— **单一事实来源**（大纲 §9.1）。
 *
 * 加一个设置项只需在 [SettingRegistry] 加一行描述符：界面、校验、clamp、
 * 默认值、持久化键全部由本类驱动，杜绝"UI 一份、持久化一份"的双清单漂移。
 *
 * ## 类型安全的设计取舍
 *
 * [default] 声明为 `Any`（而非泛型），因为异构描述符要放进同一个 `List`。
 * 代价是取值时需要按 [kind] 分发，收益是注册表能写成平铺的一长串。
 * 为约束住这个宽松度，本类提供 [coerce] 作为**唯一的类型收敛点** ——
 * 任何从 [ConfigHelper] 读回来、或从 UI 传进来的值，都必须过它。
 *
 * @param key 存储键，**点号嵌套**（如 `island.enabled`、`main_lyric.fill.color`）
 * @param group 分组路径（总表 §2 的 15 个分组之一）
 * @param kind 控件类型，决定读写与 clamp 方式
 * @param title 中文显示名（总表 §1 的「显示名」列）
 * @param summary 副标题说明文字（**可选**）。见下方「为什么要有 summary」
 * @param default 默认值。运行时类型必须与 [kind] 匹配（见 [expectedType]）
 * @param range SEEKBAR 的取值范围（**闭区间**）。非 SEEKBAR 项为 `null`
 * @param options LIST 的选项（`值` → `显示名`）。顺序即 UI 显示顺序
 * @param phase 归属实现阶段（P0/P1/P4）
 * @param alphaAllowed COLOR 是否允许带 alpha。**首版 2 项均强制 `false`**
 *   （`recon/10` §4.5；总表 §1.6 的「取值范围/选项」列明文写 `alphaAllowed = false`）
 *
 * ## 为什么要有 `summary`（2026-09-17 新增）
 *
 * 它是宿主的**原生字段**：`recon/18` §A.2 与 `recon/19:525-529` 的构造器签名显示，
 * `switch` / `list` / `seekbar` / `edittext` / `button` **五种类型全都有 `summary`**
 * （`fW.java:111-122`，可选、默认 `null`）。原实现 0.8.9 已在用它写说明与免责声明
 * （其 `preference_config.json:30` 就是 `experimental_multi_line` 的 summary）。
 *
 * **本项目此前没有这个字段**，于是生成的 JSON 里一个 `summary` 都没有 ——
 * 用户只能看到标题、看不到"这一项到底影响什么"。而本轮（BUG-041 收尾）恰恰
 * 需要把「总闸」与「行数模式」的**两层关系**写给用户看：这是本项目**第一次**
 * 有"两个设置项互相约束"的情形，光靠标题无法表达。
 *
 * 放在 [title] 之后而不是末尾：与宿主构造器的参数顺序一致（`title, summary, …`），
 * 便于将来对照 `recon/19:525-529` 的签名核对。所有调用点都用**具名参数**，故安全。
 */
data class SettingDescriptor(
    val key: String,
    /**
     * **一级分组 = 组件**（如「歌词」「频谱」「词岛」）。
     *
     * 2026-09-21 重分组：分组判据从"**被调整的方面**"改为"**被调整的组件**"。
     * 用户原话：「**在组件分组的基础上更细。**」——
     * 旧分法把同一个组件的设置拆到了多个组里（实测 `main_lyric.*` 散在 4 个组、
     * `island.*` 散在 3 个、`layout.*` 散在 3 个、`translation.*` 散在 2 个）。
     * 现改为**组件为一级**，组件内的细类见 [subGroup]。
     */
    val group: String,
    /**
     * **二级分组 = 组件内的细类**（如「歌词 ▸ 歌词描边」里的「歌词描边」）。
     *
     * 用户要求"更细"，故一级只到组件是不够的 —— 最大的组件「歌词」有 45 项，
     * 必须再切一层才能扫得动。取值必须在 [SettingGroups.SUBS] 里对 `group` 声明过
     * （`SettingRegistry.verify()` 断言这一点，拼错会当场失败而不是静默消失）。
     *
     * `null` 表示"该项不参与二级分类" —— 当前**没有任何一项**是 `null`，
     * 保留默认值是为了让 `SettingDescriptor` 的构造点不必逐个补齐。
     */
    val subGroup: String? = null,
    val kind: SettingKind,
    val title: String,
    val summary: String? = null,
    val default: Any,
    val range: ClosedRange<Double>? = null,
    val options: List<Pair<String, String>>? = null,
    val phase: SettingPhase,
    val preview: PreviewKind,
    val alphaAllowed: Boolean = false,
    /**
     * ★★★ 2026-10-11（**U12**）：**该项已下架**（不再出现在用户可编辑的设置面）。
     *
     * ## 为什么是"标记"而不是"删掉 descriptor"
     *
     * ★ 用户指令：「液态玻璃呈现始终不达标，**截断其渲染路径以及显示的设置项**，
     *   但有收益的以及对其他功能还有用的建设可以保留。」
     *
     * ★★ 但**不能物理删除** —— 理由（★ 已逐条 grep 核对）：
     *   · ★ 消费点仍在读这些键（`IslandWindow` / `MaterialLayers` / `IslandAppearance`）
     *     ⇒ ★ 删了会让它们读到"未知键"并**每次都告警** ✗
     *   · ★ 用户配置里可能已有这些键的值 ⇒ ★ 删了会变成"孤儿键" ✗
     *   · ★★ 路线文档说"补不齐的上限"要靠 **D-2 架构重构** ⇒ ★ **将来可能复活** ✓
     *
     * ## 语义（★ 三者分工明确，别混）
     *
     * | 标记 | 含义 | 谁消费 |
     * |---|---|---|
     * | [retired] | ★ **从【用户可见的设置面】移除** | `gen_preference_config.py`（不生成到 `preference_config.json`） |
     * | [default] | ★ 默认值（★ 下架项照旧生效） | 运行时读盘 |
     * | （无标记） | 正常项 | 全部 |
     *
     * ⚠️ ★ `retired = true` **不影响运行时行为** —— ★ 值照读、消费点照跑。
     *   若要让某项**真的不生效**，要改的是**它的默认值 / 消费侧开关**（如 L3 的
     *   `blur_radius = 0 ⇒ 整链不跑`）✓
     */
    val retired: Boolean = false,
    /**
     * ★★★ 2026-10-14（**BUG-198**）：菜单步进器**点一下 ±** 的步长 —— 默认 `1`（最细粒度）。
     *
     * ## 用户原话
     *
     * > 「另外，**子菜单的消失延迟调整后不应用**。」
     *
     * ## 根因：★ 不是"设置没写进去"，而是**步长与量纲不匹配**
     *
     * 逐条核过写读两条链，**每一环都是对的**：
     *   · 步进器 `writeValue` → `applyValue` → `settings.apply` → `publish` ⇒ `live` **立刻**更新 ✓
     *   · 收起轮询 `delays()` **每次 tick 现读** `live` ⇒ 菜单已弹出时改设置**当帧生效** ✓
     *   · 范围/默认值/兜底常量三处一致（`MenuPolicy` 的探针断言）✓
     *
     * ★★ **真凶**：`menu.submenu_close_delay_ms` 的域是 **0–1000 ms**，而步长是 **1**
     *   ⇒ 用户点 20 下才 +20ms —— **手感上根本分辨不出来** ⇒
     *   他得到的是「数字变了、菜单行为一点没变」⇒ 结论必然是"**设置了不应用**" ✗
     *   （★ 挂靠纪律：「**单位变了名字没跟着变**」的同族 —— 这里是"**量纲变了步长没跟着变**"。）
     *
     * ## 为什么加字段而不是恢复一张全局步长表
     *
     * ★ `IslandPopupMenu` 里**曾经**的 `STEP_ALL` 常量的 KDoc **自己**写下了正确做法（原文）：
     *   > 「若将来确实需要某个项走粗步长（例如某个域大到 0..100000），**不要恢复一张全局表** ——
     *   >   表会让"哪些项被特殊对待"变得不可见。正确做法是在 `SettingDescriptor` 上加一个
     *   >   可选的 `step` 字段，让"步长"跟着**设置项的定义**走。」
     *   ⇒ ★ 本条就是这个预告的兑现 ✓（★ 那个常量已随之删除，论证原文搬到了本字段上方）
     *
     * ## 与用户"最细粒度"那条指令的关系（★ 不冲突，别误读）
     *
     * ★ 用户 2026-09-17 原话：「步进器**没有做到最细粒度**（数值增减逻辑可参考 0.8.9）」——
     *   那条针对的是**当时用粗步长**（`lyric.offset_ms` 10 / `controls.autoHideMs` 100 …）
     *   导致"**永远调不出差 1 的值**"✗
     * ★★ 本条**不改**那些项的步长（它们**仍是 1**）—— 只给**域大到 0..1000 ms** 的两项
     *   设 10 ⇒ ★ "差 1" 仍然可达（**内联编辑**直接打任意整数，见
     *   `IslandMenuSkin.MenuStepper.beginEdit`），而"少点几次"由 `step` 兑现 ✓
     *   ⇒ ★ 两条诉求**同时满足**：**精度不丢**（编辑框）+ **手感可及**（步长）✓
     */
    val step: Int = 1,
) {
    init {
        // 这些断言是给**将来加设置项的人**用的：写错一个字段立刻在类加载时炸掉，
        // 而不是等到用户拖滑杆时才发现 range 是 null。注册表在进程内只加载一次，
        // 失败的代价极低，收益是"加项时就报错"而非"运行时才报错"。
        when (kind) {
            SettingKind.SEEKBAR -> requireNotNull(range) { "SEEKBAR 项必须有 range：$key" }
            SettingKind.LIST -> requireNotNull(options) { "LIST 项必须有 options：$key" }
            else -> Unit
        }
        require(!alphaAllowed || kind == SettingKind.COLOR) {
            "alphaAllowed 只对 COLOR 有意义：$key"
        }
        // ★ 2026-10-14（BUG-198）：步长必须是正整数。
        //   0 会让"点一下"变成**无变化**（用户会报"点了没反应"）——
        //   与本次缺陷（"改了没反应"）是同一个观感，只是换了成因。
        //   负值会让 `±` 方向反转（点 `+` 变小），同样必须在此拦下。
        require(step >= 1) { "step 必须是 ≥1 的整数（0/负数会让 ± 失效或反向）：$key=$step" }
        // ★ step 只对 SEEKBAR 有意义 —— 其余 kind 的步长字段是**没人读的字段**
        //   （正是纪律 94「字段带了没人读」要防的形态）⇒ 在此拦下它对非 SEEKBAR 的设置。
        require(kind == SettingKind.SEEKBAR || step == 1) {
            "step 只对 SEEKBAR 有意义（$kind 项请勿设 step）：$key=$step"
        }
    }

    /** 该 descriptor 期望的运行时类型。用于发现"存进去的类型与期望不符"。 */
    val expectedType: Class<*>
        get() = when (kind) {
            SettingKind.SWITCH -> java.lang.Boolean::class.java
            SettingKind.SEEKBAR -> java.lang.Integer::class.java
            SettingKind.LIST, SettingKind.EDITTEXT, SettingKind.FONT -> String::class.java
            SettingKind.COLOR -> Int::class.java
        }

    /**
     * 把任意来源的值收敛到本 descriptor 期望的类型与范围内。
     *
     * **这是全项目唯一的类型收敛点**，必须在两处调用：
     * ① 从配置读回来时（磁盘上可能是旧版本/手改的坏值）；
     * ② 从 UI 写进来时（防御未来某个控件传错类型）。
     *
     * ## 为什么"类型不符"必须回退默认值而不是抛异常
     *
     * 项目铁律：「插件不能因为配置坏了就崩」。用户在配置文件里手改一个
     * `"font_size": "big"`，插件应当忽略这一项并继续跑，而不是启动失败。
     * 因此本方法的契约是**永不抛异常**，一切非法输入都退化为 [default]。
     *
     * 特别地，[ConfigHelper.get] 的签名是 `fun <T> get(key: String, default: T): T`，
     * **它的泛型擦除后不保证类型安全** —— 若磁盘上是 `String` 而期望 `Boolean`，
     * JVM 会在调用点抛 `ClassCastException`。因此读盘路径**不能直接信 `get` 的返回**，
     * 必须先按 [expectedType] 做 `is` 判定（见 [SettingsStore.readRaw]）。
     */
    fun coerce(value: Any?): Any = when (kind) {
        SettingKind.SWITCH -> when (value) {
            is Boolean -> value
            // 字符串化的布尔（手改配置常见）也接受，避免"改了就失效"的困惑。
            is String -> value.trim().toBooleanStrictOrNull() ?: default
            else -> default
        }

        SettingKind.SEEKBAR -> {
            val number = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }
            if (number == null) default
            // clamp 到 range。`!number.isFinite()` 挡下 NaN/Infinity ——
            // NaN 参与比较恒为 false，`coerceIn` 会把它原样放行，进而污染布局计算。
            else if (!number.isFinite()) default
            else number.coerceIn(range!!.start, range.endInclusive).toInt()
        }

        SettingKind.LIST -> {
            val text = value as? String ?: return default
            // 只接受 options 里声明的值。**为什么不容忍未知值**：
            // 未知值意味着"配置来自更新/更旧的版本"，此时静默接受会让 UI 显示空白选中项；
            // 回退默认值是可预期的降级。
            options!!.firstOrNull { it.first == text }?.first ?: default
        }

        // ── EDITTEXT 与 FONT 分开处理（2026-09-16 修）─────────────────────
        //
        // **原实现把两者合并**：`EDITTEXT, FONT -> value as? String ?: default`。
        // 后果：**空串是 String，会被原样接受** —— 于是 `main_lyric.font.family`
        // 可以落成 `""`，而 `Font("", ...)` 会退化成系统默认字体，
        // 表现为"用户设了字体但画面没变"（真机配置文件实测 `"family": ""`）。
        //
        // 为什么 FONT 必须拒绝空串而 EDITTEXT 不能：两者的**语义**不同。
        // `FONT` 的值是**字体族名**，空族名不是一个可渲染的值 ——
        // 它没有"用户故意清空"这个合法含义（不像普通文本框可以留空）。
        // 故空/纯空白一律回退 [default]，让"写坏了"退化成"用默认字体"而不是"字体失效"。
        SettingKind.EDITTEXT -> value as? String ?: default
        SettingKind.FONT -> (value as? String)?.takeIf { it.isNotBlank() } ?: default

        SettingKind.COLOR -> {
            val packed = when (value) {
                // 配置里存整数 ARGB（`Int`）。用 `Long` 兜一下，因为手改的 JSON
                // 可能被解析成更大的数值类型。
                is Number -> value.toInt()
                // 也接受 `#RRGGBB` / `RRGGBB` 文本形式（便于用户手改配置）。
                is String -> parseHexOrNull(value)
                else -> null
            }
            if (packed == null) default
            else {
                // alphaAllowed=false 时强制不透明：把 alpha 位抹成 0xFF。
                // 为什么"强制"而不是"拒绝"：读盘路径的回退原则是"能救则救" ——
                // 颜色本身有效，只是多了 alpha，抹掉 alpha 比整项回退默认更贴近用户意图。
                val rgb = packed and 0xFFFFFF
                if (alphaAllowed) packed else 0xFF000000.toInt() or rgb
            }
        }
    }

    /** 把 [coerce] 的结果转成强类型访问器的返回值。调用前须确认与 [expectedType] 一致。 */
    fun coerceBoolean(value: Any?): Boolean = coerce(value) as Boolean

    fun coerceInt(value: Any?): Int = coerce(value) as Int

    fun coerceText(value: Any?): String = coerce(value) as String

    /** [default] 已收敛后的值（调用方不必再 coerce 一次）。 */
    fun defaultValueBoolean(): Boolean = coerceBoolean(default)

    fun defaultValueInt(): Int = coerceInt(default)

    fun defaultValueText(): String = coerceText(default)

    /** SEEKBAR 项的范围，像素/毫秒整数形式。非 SEEKBAR 抛异常（调用方应先判 kind）。 */
    val intRange: IntRange
        get() = requireNotNull(range) { "$key 不是 SEEKBAR" }
            .let { it.start.toInt()..it.endInclusive.toInt() }
}

/**
 * 颜色 → **内部表示**（打包 ARGB）的统一打包。
 *
 * ⚠️ **这不是落盘格式。** 内部表示是给渲染层用的（`java.awt.Color` 直接吃 `Int`）；
 * **落盘格式由 [SettingDescriptor.storageValue] 决定** —— 对 COLOR 是 `"#RRGGBB"` 文本。
 *
 * **原记**：「为什么 COLOR 存 `Int` 而不是字符串：存 `Int` 让 JSON 里是数字，
 * 比 `"#FFFFFF"` 少 8 个字节且不需要解析。存得紧凑、读得宽容，这个不对称是有意的。」
 *
 * **实为**：**落盘改成 `"#RRGGBB"` 文本**。理由是"紧凑"不是第一约束、"可读"才是 ——
 * SPW 设置页的 `edittext` **直接显示该键的原始值**，存 `Int` 会让用户看到
 * `-16777216` 这类十进制负数（首版 `alphaAllowed = false`，alpha 被强制成 `0xFF`，
 * 故打包值**恒为负**）。
 *
 * 读侧**两种都接受**（见 [SettingsStore.readRaw] 的 COLOR 分支），
 * 所以这次改格式**不影响旧数据**。
 */
fun Color.toPackedArgb(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

/** 打包 ARGB → `java.awt.Color`。`java.awt` 是本项目设置层的既定内部表示（大纲 §9.6）。 */
fun Int.toColor(): Color = Color(this, true)

/**
 * 解析 hex 文本为打包 ARGB；失败返回 `null`。
 *
 * 容错规则**逐条取自 `首版设置总表.md` §4.3**（那份表的验收口径）：
 * - `#RRGGBB` / `RRGGBB` / `#RGB`（缩写每位 ×17 展开）→ 接受
 * - 大小写混用 → 接受
 * - 前后空格 → 忽略
 * - `#AARRGGBB` → **首版不接受**（总表 §1.6 明文 `alphaAllowed = false`）
 * - `RGB(r,g,b)` 函数式 → 接受，各分量 `coerceIn(0, 255)`
 *
 * ⚠️ 本函数**只负责解析**，不负责 UI 反馈（标红边框、灰字提示等在取色器组件里）。
 * 让持久化层与 UI 层共享同一份解析规则，是为了避免"配置读得进、输入框不认"这类不一致。
 */
fun parseHexOrNull(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    // `RGB(r,g,b)` 函数式：正则容忍空格与大小写。
    val functional = Regex("""^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$""", RegexOption.IGNORE_CASE)
    functional.find(text)?.let { match ->
        val (r, g, b) = match.destructured
        return packRgb(r.toInt(), g.toInt(), b.toInt())
    }

    val body = text.removePrefix("#")
    // 去掉内部可能残留的 `0x` 前缀（Ctrl+V 从代码里粘贴的常见形态）。
    val cleaned = body.replace("0x", "", ignoreCase = true)
    if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null

    return when (cleaned.length) {
        3 -> {
            // 缩写展开：`f80` → `#FF8800`（每位 ×17，即 `0xF` → `0xFF`）。
            val r = cleaned[0].digitToInt(16) * 17
            val g = cleaned[1].digitToInt(16) * 17
            val b = cleaned[2].digitToInt(16) * 17
            packRgb(r, g, b)
        }
        6 -> packRgb(
            cleaned.substring(0, 2).toInt(16),
            cleaned.substring(2, 4).toInt(16),
            cleaned.substring(4, 6).toInt(16),
        )
        // 8 位（`#AARRGGBB`）按总表 §1.6 拒绝；其余长度一律非法。
        else -> null
    }
}

private fun packRgb(r: Int, g: Int, b: Int): Int =
    0xFF000000.toInt() or
        (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or
        b.coerceIn(0, 255)

// 打包 ARGB → `#RRGGBB` 或 `#AARRGGBB`（视 alpha 是否为不透明）。UI 回显用。 
/**
 * 三通道 + 明度百分比 → **打包 ARGB**（**alpha 恒为不透明**）。
 *
 * ## 为什么单独抽出来（2026-09-19 真机缺陷）
 *
 * 取色器窗口的 `currentPacked()` 原本这样合成：
 *
 * ```
 * return (r shl 16) or (g shl 8) or b      // ← 少了 alpha！
 * ```
 *
 * 于是打包值 alpha = `0x00` → **全透明**。三个可观察后果（用户截图里全出现了）：
 *   · `toHexString()` 见 alpha ≠ `0xFF` → 走**八位**分支 → 显示 `#00FFFFFF`；
 *   · 预览色块 `Color(packed, true)` → **画了但看不见**；
 *   · 示例歌词同色 → **一个字都看不到**。
 *
 * 抽成纯函数后，探针可以直接断言"**alpha 必须是不透明**"，
 * 而不是靠"下次谁再忘了补"。
 *
 * ## 明度语义
 *
 * `lightnessPercent = 100` → 通道值**原样**（3 键制的正确取值）；
 * 更小则按比例压暗（4 键制 `mixerColor` 的语义）。
 *
 * ⚠️ **与 `mixerColor` 是同一套公式，但那份在渲染层**（`IslandWindow`）。
 *    两处必须一致 —— 不一致会让"取色器里看到的"与"词岛上画的"不同色。
 *
 * @param lightnessPercent `0..100`，越界自动收敛
 */
fun composePackedArgb(r: Int, g: Int, b: Int, lightnessPercent: Int = 100): Int {
    val l = lightnessPercent.coerceIn(0, 100) / 100f
    val rr = (r * l).toInt().coerceIn(0, 255)
    val gg = (g * l).toInt().coerceIn(0, 255)
    val bb = (b * l).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
}

/**
 * 按比例**混合两个打包 ARGB 值**（`t = 0` → [a]，`t = 1` → [b]）。
 *
 * ## ★ 为什么必须拆通道（**两个各自独立的理由，都由实测证实**）
 *
 * 直接对整数插值（`a + (b - a) * t`）有两个毛病：
 *
 * **① 通道串扰 —— 偏差可达 ~128，不是 1。**
 * 它把 ARGB 当成**一个 32 位整数**在插，于是"低位字节的进位会爬到高位字节上"，
 * 结果**不是**逐通道的线性插值。**实测**（`AnimationProbe` §10 的变异测试）：
 *
 * ```
 * 黑 0xFF000000 → 白 0xFFFFFFFF，t = 0.5
 *   逐通道应为  0xFF7F7F7F   （R/G/B 各 127）
 *   整数插值给出 0xFF7FFFFF   （G 与 B 都是 255）  ← 偏差 128，肉眼一眼可见
 * ```
 *
 * 原因：增量 `0x00FFFFFF` 折半得 `0x007FFFFF`，那个 `0xFF` 落在了**最低字节**上。
 *
 * **② alpha 也跟着插。**
 * 若两个端点的 alpha 不同（任何一个来源不是 `0xFF`），结果就是一个**半透明的主色**；
 * 下游 `Int.toColor()` 会把它当有效颜色用 → 背景 / 歌词**整体变淡**，而且**不报错**。
 *
 * 故这里拆通道后**用 [composePackedArgb] 重新打包** ——
 * "alpha 恒为 `0xFF`"由**构造**保证，而不是靠调用方记得。
 * 这正是项目纪律「**打包 ARGB 必须带 alpha**」要防的那一类缺陷。
 *
 * > ⚠️ **本注释有过一次错误的"自我纠正"，值得记下**：
 * > 初版写"进位污染邻通道"（**正确**）→ 我后来以为夸大了，改成"逐通道偏差最多 1，
 * > 真正的问题只有 alpha"（**错误**）→ 变异测试把两处偏差都打了出来（`0xFF7FFFFF` vs `0xFF7F7F7F`），
 * > 于是回改为现在这版。**教训：纠正一条结论前，先跑一次能证伪它的检查。**
 *
 * ## 为什么抽成公开顶层函数
 *
 * 它是**纯函数**（无状态、不碰 Swing），故可以被探针直接喂值断言。
 * 项目纪律：「**首选抽公开纯函数而非放宽可见性**」——
 * 若留在 `IslandWindow` 里做私有方法，就只能靠放宽可见性才测得到。
 *
 * @param a 起点（打包 ARGB）
 * @param b 终点（打包 ARGB）
 * @param t 比例。**越界会被夹到 `0..1`** —— 帧间隔抖动会让调用方算出略超界的值
 */
fun blendPackedArgb(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun channel(packed: Int, shift: Int): Int = (packed ushr shift) and 0xFF
    fun mixChannel(shift: Int): Int {
        val from = channel(a, shift).toFloat()
        val to = channel(b, shift).toFloat()
        return (from + (to - from) * k).toInt().coerceIn(0, 255)
    }
    return composePackedArgb(mixChannel(16), mixChannel(8), mixChannel(0))
}

fun Int.toHexString(): String =
    if ((this ushr 24) and 0xFF == 0xFF) String.format("#%06X", this and 0xFFFFFF)
    else String.format("#%08X", this)

/**
 * 译文**未唱**部分的暗化系数（相对已唱色）。
 *
 * ★★★ 2026-10-10（审计 v4 · **B-3**）**收敛到唯一出口**：
 *   此前**真机与预览各写一份** `const val = 0.62f`（`IslandWindow` 的 `private companion`
 *   ↔ `IslandPreviewPane` 的 `private companion`），★ 且预览那份的 KDoc 只能写
 *   「必须与 `IslandWindow.UNSUNG_TRANSLATION_DIM`（0.62）**同值**」——
 *   ★ 那正是本项目最忌的「**同一常量两处各写一份，靠注释提醒**」✗
 * ⇒ ★ 移到 `settings/`（与 [blendPackedArgb] 同处）⇒ 两侧**引用同一个常量** ✓
 *
 * ★ 取 `0.62`：★ 与「跟随封面时未唱色」的 `0.5` **取值不同是有意的**
 *   （见 `IslandWindow` 里那条 KDoc：两者对比度量级不同）。
 *
 * ## ★ 取值 `0.62` 的原始论证（2026-09-17 · 随常量一并迁入）
 *
 * 用户诉求：「副歌词行**同步高亮**」。而 `drawTranslationLine` 原本传的是
 * `sungColor == unsungColor`（同一个色）⇒ **根本没有"已唱/未唱"的视觉区分** ✗
 *
 * 取 0.62 而非更暗/更亮：
 * · 太暗（如 0.4）→ 未唱部分与背景糊在一起，整行像只剩一半字；
 * · 太亮（如 0.85）→ 已唱与未唱差异过小，高亮"看不见"。
 * 0.62 是"能看出分层、但未唱部分仍然清晰可读"的折中，与主歌词的
 * 已唱/未唱对比度量级相当 ✓
 *
 * ⚠️ **原实现 0.8.9 的译文是静态平铺**（无已唱/未唱之分）——
 * 故本项是**用户的新增诉求**，不是"对齐原实现"。别拿 0.8.9 当参照反推它不该有。
 */
public const val UNSUNG_TRANSLATION_DIM: Float = 0.62f

/**
 * 按固定系数**逐通道压暗**一个打包 ARGB（**不动 alpha 位**）。
 *
 * ★★★ 2026-10-10（审计 v4 · **B-3**）**收敛到唯一出口**：
 *   此前 `IslandWindow.dimPacked` 与 `IslandPreviewPane.dimPacked` 是**逐字相同的两份**
 *   （各 ~5 行，`private`）—— ★ 这正是纪律 73 点名要拦的形态
 *   （「镜像实现必须改成直接调生产函数」）✗ ⇒ ★ 抽成本公开纯函数，两侧都调 ✓
 *
 * ★ **为什么不动 alpha**：暗化是"同一种颜色暗一档"，不是"变透明"。
 *   ★ 若连 alpha 一起乘，未唱部分会变半透明 —— 在透明悬浮窗上会与背景混色，
 *   观感是"未唱的字在闪"，而不是"未唱的字更暗" ✓
 *
 * ★ 纯函数（无状态、不碰 Swing）⇒ ★ **可被探针直接喂值断言** ✓
 *
 * @param argb 打包 ARGB（alpha 位被**原样保留**）。
 * @param factor 每通道的乘数（`0..1` 变暗，`> 1` 变亮）。
 */
public fun dimPackedArgb(argb: Int, factor: Float): Int {
    fun channel(shift: Int): Int =
        (((argb shr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
    return (argb and 0xFF000000.toInt()) or
        (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * **内部表示 → 落盘表示**。
 *
 * ## 为什么需要这一层
 *
 * 内部表示是给渲染层用的（COLOR = 打包 ARGB `Int`，直接喂 `java.awt.Color`），
 * 但**落盘表示要面向两个外部读者**：
 * ① **SPW 设置页的 `edittext`** —— 它**直接显示该键的原始值**；
 * ② 手改配置文件的用户。
 *
 * 对 COLOR 来说，存 `Int` 会让这两个读者都看到 `-16777216` 这种十进制负数
 * （首版 `alphaAllowed = false`，alpha 被强制成 `0xFF`，故打包值**恒为负**）。
 * 故落盘时转成 `"#RRGGBB"` 文本。
 *
 * ## 读侧不受影响（这是"改格式"能安全落地的前提）
 *
 * [SettingsStore.readRaw] 的 COLOR 分支**两种类型都认**（Int 与 String），
 * 所以本函数改变的只是**新写入的格式**，**旧数据（Int）照常读得出来**，
 * 手改写的 `#RRGGBB` 也照常读得出来。
 *
 * ## 新增 kind 时
 *
 * 若将来有别的 kind 也需要"内部表示 ≠ 落盘表示"，在此处加分支，
 * **不要**把这个转换散到调用点 —— 落盘格式必须只有一个来源。
 */
fun SettingDescriptor.storageValue(value: Any): Any =
    if (kind == SettingKind.COLOR && value is Int) value.toHexString() else value

/**
 * 「读到并收敛后的值」与「原始值」是否**语义上**不同。
 *
 * ## 为什么不能用 `coerced != raw`
 *
 * 数值类型的 `!=` 在 Kotlin 里是**类型敏感**的：`60 != 60.0f` 为 `true`。
 * 而宿主的滑杆**写盘就是 `Float`**（JSON 里 `60.0`），[SettingDescriptor.coerce]
 * 又把 SEEKBAR 收敛成 `Int` —— 于是**每一个滑杆项都会被误判为"类型不符"**。
 *
 * 实测后果（真机日志）：**10 个滑杆项 × 每次 250 ms 轮询 = 5071 行噪声**，
 * 把真正需要看的异常**淹没了**。这类"报错太多导致没人看报错"比不报更糟。
 *
 * ## 判据
 *
 * - 双方都是数值 → **按 `Double` 比数值**（`60` 与 `60.0f` 视为相同）；
 * - 其余 → 直接比（字符串/布尔/颜色打包值都是精确类型）。
 *
 * 注意这是**给日志用的语义比较**，不参与取值 —— 取值仍走 [SettingDescriptor.coerce]。
 */
fun SettingDescriptor.differsFromRaw(coerced: Any, raw: Any): Boolean {
    if (coerced is Number && raw is Number) return coerced.toDouble() != raw.toDouble()
    return coerced != raw
}
