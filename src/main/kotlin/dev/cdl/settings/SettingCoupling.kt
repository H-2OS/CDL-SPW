// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

/**
 * 设置项之间的**依赖联动** —— 用户碰了"被门控的项"时，**自动解开它的门控**。
 *
 * ## 为什么要有这个（真机取证）
 *
 * 用户报：「SPW 插件配置页内**死设置项变多了**」。
 *
 * 死设置审计（2026-09-19）逐个键追消费点，结论是：**真正没有任何消费点的只有 1 项**
 * （`performance.low`）。但有一批项**有消费点、却挂在另一个开关后面**，
 * 门没开时拖它**零变化** —— 用户看到的就是"死设置"。
 *
 * 真机日志给出了**用户试过哪些**（`super-island.log.1`，13613 行）：
 *
 * | 键 | 被改动次数 | 当时它的门 | 结果 |
 * | --- | --- | --- | --- |
 * | `spectrum.secondary.opacity_percent` | **128** | `secondary.mode = OFF` | 零变化 |
 * | `spectrum.bar_pitch_px`（**已改名** `spectrum.bar_pitch_tenth_px`） | **72** | `bar_pitch_mode = AUTO` | 零变化 |
 * | `translation.outline.width_tenth_px` | —— | `sync_with_main = true` | **整组 5 键被短路**（2026-09-21 补） |
 *
 * 第三行是 2026-09-21 补的：用户配置里 `sync_with_main = true` 且
 * `width_tenth_px = **40**`（默认 **10**）—— **调到 4 倍毫无效果**，
 * 因为 `Appearance.translationOutlineFor()` 第一行就 `return mainOutlineFor()`，
 * 该组的 `enabled` / 宽度 / 三个颜色通道**全部被短路**。
 *
 * **128 次**不是"随手拖了一下"，是"我一直拖它，它一直不动"。
 *
 * ## 为什么不是"把 summary 写清楚一点"
 *
 * 这两项的 `summary` **本来就写着**依赖：
 * 「仅在副频谱不为「关闭」时生效」/「仅在「频谱条宽度」选「固定」时生效」。
 * 写了，用户还是拖了 128 次 —— **说明"写清楚"解决不了"拖了没反应"**。
 *
 * ## 为什么"自动解门控"不算过度纠正
 *
 * 判据是**用户意图是否唯一**：
 *
 * - 拖「频谱条间距」→ 意图唯一：**我要自己定间距**（不是"我想让间距自动算"）。
 * - 拖「副频谱不透明度」→ 意图唯一：**我要副频谱，且要它这个透明度**。
 *
 * 两者都不存在第二种合理解释。所以这不是"替用户做决定"，而是
 * **"用户已经做了决定，只是这个决定被另一个开关挡住了"**。
 *
 * > 对照 `BUG-046`（"过度纠正"）：那次错在**语义边界不清**时替用户拿主意。
 * > 本处的边界是清的 —— 用户主动操作了那个控件本身。
 *
 * ## 选值理由
 *
 * - `bar_pitch_mode` → `FIXED`：唯一能表达"用下面那个间距值"的档。
 * - `secondary.mode` → `BOTTOM`：三个非 OFF 档里最保守的一个
 *   （只加一条、加在底边），且正是用户最初描述的那条
 *   （「双峰频谱背景中**靠底边**的另一频谱在跳动」）。
 *
 * ## 为什么做成纯函数
 *
 * 它是一条**规则**，不是一段流程。抽成纯函数后探针可以穷举
 * （含"不该触发时不得触发"的反向护栏），且调用方不必知道任何键的语义。
 */
object SettingCoupling {

    /**
     * 频谱条间距（被 `spectrum.bar_pitch_mode` 门控）。
     *
     * ## ⚠️ 键名必须与注册表**逐字相同**（2026-09-19 修正，BUG-092）
     *
     * 这里原先是 `"spectrum.bar_pitch_px"`。而该键在"整数键 + 单位放大 10 倍"
     * 那次改造里已改名 `spectrum.bar_pitch_tenth_px`（见 `SettingRegistry` 的 KDoc：
     * 滑杆写盘是 Float，`coerceInt` 会把 `0.5` **截断成 0**）——
     * **本常量没跟着改**，于是：
     *
     * ```
     * BAR_PITCH_KEY in changedKeys   // 恒为 false —— 集合里永远不会有这个不存在的键
     * ```
     *
     * → `releasePatch` 对频谱条间距**恒不触发** → 用户拖「频谱条宽度」时档位仍是 `AUTO`
     * → **零变化**。这恰好就是当初做这条联动要解决的那个症状（见本文件 KDoc 的表），
     * 只是换了个方向复发：**不是"规则没写"，而是"规则写了但触发键是死的"。**
     *
     * **为什么探针没拦住**：拦住了 —— `SettingCouplingProbe` §⑦「触发键必须真实存在于注册表」
     * 一直是**红的**，只是没被当成门禁跑。故本轮同时把该断言留作硬门禁（见探针）。
     *
     * **为什么不用字符串拼接/正则去"猜"键名**：那只是把同一个脆弱点挪个位置。
     * 真正的护栏是**探针拿注册表比对** —— 键名错了就红，改对了才绿。
     */
    const val BAR_PITCH_KEY = "spectrum.bar_pitch_tenth_px"

    /** 频谱条宽度档位 —— 门控 [BAR_PITCH_KEY]。 */
    const val BAR_PITCH_MODE_KEY = "spectrum.bar_pitch_mode"

    /** 副频谱不透明度（被 `spectrum.secondary.mode` 门控）。 */
    const val SECONDARY_OPACITY_KEY = "spectrum.secondary.opacity_percent"

    /** 副频谱档位 —— 门控 [SECONDARY_OPACITY_KEY]。 */
    const val SECONDARY_MODE_KEY = "spectrum.secondary.mode"

    /** 拖 [BAR_PITCH_KEY] 时把档位切到这个值。 */
    const val BAR_PITCH_MODE_RELEASED = "FIXED"

    /** 拖 [SECONDARY_OPACITY_KEY] 时把档位切到这个值。 */
    const val SECONDARY_MODE_RELEASED = "BOTTOM"

    /**
     * **副歌词描边组**的同步开关 —— 它开着时**整组**（含 `enabled` / 宽度 / 颜色）都被短路。
     *
     * `Appearance.translationOutlineFor()` 第一行就是
     * `if (translationOutlineSync) return mainOutlineFor()` ——
     * 于是这一组 5 个键在 `sync_with_main = true` 时**全部无效**。
     *
     * ## 真机取证（2026-09-21）
     *
     * 用户配置：`sync_with_main = true` 且 `width_tenth_px = **40**`（默认是 **10**）——
     * **他把副描边宽度调到了 4 倍，一点效果都没有。**
     */
    const val TRANSLATION_OUTLINE_SYNC_KEY = "translation.outline.sync_with_main"

    /**
     * 副歌词描边组的**成员键** —— 用户碰其中任何一个，就说明"他要自己调副描边"。
     *
     * ⚠️ **不得包含 [TRANSLATION_OUTLINE_SYNC_KEY] 本身** —— 那会让联动自激成环
     * （写 sync → 触发 → 再写 sync → …）。探针里有一条断言专门钉这个性质。
     *
     * ⚠️ **公开**（不是 `private`）：探针要用它做**命名空间护栏**
     * （"`translation.outline*` 下的注册表键必须恰好是联动认识的这些"）——
     * 那条护栏正是为了拦"改名后常量变孤儿"（本项目栽过一次，见 `BAR_PITCH_KEY`）。
     * 收窄可见性会让探针看不到它，等于拆掉护栏。
     */
    val TRANSLATION_OUTLINE_KEYS: Set<String> = setOf(
        "translation.outline.enabled",
        "translation.outline.width_tenth_px",
        "translation.outline.color.r",
        "translation.outline.color.g",
        "translation.outline.color.b",
        // 2026-09-23 追加（用户：「**允许副歌词的描边跟随字体粗细**」）。
        // ⚠️ **漏加这一行的后果**：`sync_with_main` 默认 `true` ⇒ 整组被短路
        //    ⇒ 用户拨这个新开关**一点反应都没有**（`translationOutlineFor()` 第一行
        //    就 `return mainOutlineFor()`）。这正是本文件 KDoc 表里第三行那个症状
        //    （用户把宽度调到 4 倍毫无效果）的**同族复发形态** ——
        //    只是这次不是"没消费点"，而是"消费点被门控挡住"。
        //    `SettingCouplingProbe` ⑧ 的 `"translation.outline"` 命名空间护栏会
        //    **当场拦住**漏加（注册表里有、联动不认识 → 红）。
        "translation.outline.follow_weight",
    )

    /** 拖副描边组任一成员时，把同步开关切到这个值。 */
    const val TRANSLATION_OUTLINE_SYNC_RELEASED = false

    // ── 「歌词行数」→「显示翻译」（BUG-079，2026-09-21）────────────────────
    //
    // ## 为什么这一组需要"读值"（而前三组不需要）
    //
    // 前三组的规则是**与值无关**的（"拖了 A 就把 B 设成固定值"）。
    // 而这一组要问"用户选了**哪一档**"才能决定写什么 —— 故 [releasePatch]
    // 多了一个可选的 `valueOf` 读数器。

    /** 「歌词行数」（单行 / 双行）—— **它自己已经不生效了**，见 [LAYOUT_LINES_DUAL]。 */
    const val LAYOUT_LINES_KEY = "layout.lines"

    /** 真正驱动第二行（副歌词）绘制与高度的键。 */
    const val TRANSLATION_ENABLED_KEY = "layout.translation.enabled"

    /** `layout.lines` = 双行 → 应当显示第二行。 */
    const val LAYOUT_LINES_DUAL = "DUAL"

    /** `layout.lines` = 单行 → 应当不显示第二行。 */
    const val LAYOUT_LINES_SINGLE = "SINGLE"

    // ── 封面组 →「显示专辑封面」（2026-09-21，审计发现）────────────────────
    //
    // ## 为什么它比其它几组更严重
    //
    // `cover.enabled` **默认 `false`**，而 `cover.shape` / `cover.rotate` /
    // `cover.rotate_period_s` / `cover.scale_with_font` **全被它门控**。
    // → 用户在设置页把「封面形状」改成**圆形**、把「封面转动」打开，
    //   **词岛一动不动**（封面根本没被绘制）——**这正是用户反复报的"改了没反应"**。
    //
    // ⚠️ 注意这**不是** `ToolWindows.autoEnableGates()` 的活：
    //   那个是从**自绘取色窗口**调用的（只在挑颜色时触发），而封面这几项是
    //   **设置页上的 LIST / SWITCH / SEEKBAR** —— 走的是本文件这条路径。
    //   （那段 KDoc 说的是"新增带门控的**颜色组**"，封面组不是颜色组。）

    /** 「显示专辑封面」—— 它关着时，下面整组封面设置**全部无效**。 */
    const val COVER_ENABLED_KEY = "cover.enabled"

    /**
     * 封面组的**成员键**（**不含** [COVER_ENABLED_KEY] 自己 —— 含了会自激成环）。
     *
     * 公开的理由同 [TRANSLATION_OUTLINE_KEYS]：探针要用它做**命名空间护栏**，
     * 防"改名后常量变孤儿"（本项目栽过一次，见 `BAR_PITCH_KEY`）。
     */
    val COVER_KEYS: Set<String> = setOf(
        "cover.scale_with_font",
        "cover.shape",
        "cover.rotate",
        "cover.rotate_period_s",
        // 2026-09-21 追加：用户要求「旋转圆形封面加上黑边」（黑胶样式）。
        // ⚠️ **漏加这一行的后果**：`cover.vinyl` 是 SWITCH、默认 `false`，
        //    用户拨它时 `cover.enabled` 仍是 `false` → 封面根本不画 → **毫无反应**。
        //    这正是上面 KDoc 点名的"最容易被误报成死设置"那种形状。
        //    `SettingCouplingProbe` §⑧ 的 `"cover."` 命名空间护栏会**当场拦住**漏加
        //    （注册表里有、联动不认识 → 红）。
        "cover.vinyl",
        // ★ 2026-10-13（**BUG-188**）追加：`cover.sharpness`（封面清晰度）。
        //   ★ 它**描述封面外观**（与 `cover.vinyl` 同族）⇒ 拨它就说明用户要自己调封面
        //   ⇒ **应当**顺手打开 `cover.enabled` ✓
        //   ★★ **漏加这一行的后果**：与 `cover.vinyl` 完全相同 —— 它是 SEEKBAR、默认 25，
        //     而 `cover.enabled` 默认 `false` ⇒ 用户拖滑杆时**封面根本没画** ⇒
        //     **毫无反应**（"最容易被误报成死设置"的那种形状）✗
        //   ★★★ 本批实测：**探针当场抓住了** —— `SettingCouplingProbe` §⑧ 的 `"cover."`
        //     命名空间护栏报「注册表 8 键 vs 联动认识 7 键」⇒ 红 ⇒ 补上 ✓
        //     （这正是那条护栏存在的意义：**加键的人不必记得这里**）
        "cover.sharpness",
    )

    // ── 封面态 · 「锁定封面」（S5 落地；**不联动**，2026-09-30）──────────────
    //
    // ## 它为什么是 `cover.` 命名空间的成员，却**不在** [COVER_KEYS] 里
    //
    // [COVER_KEYS] 的语义是「**碰它就说明用户要自己调封面** ⇒ 顺手打开显示封面」。
    // 而 `cover.lock` 的语义是「**锁定 ⇒ 命中封面时不展开**」—— 它**不描述封面外观**、
    // **不依赖 `cover.enabled` 被打开**（锁的是"展开行为"，不是"画不画封面"）。
    //
    // ⚠️ **把它加进 [COVER_KEYS] 的后果**：用户只是想「锁住不展开」，
    //    联动却会顺手把「显示专辑封面」**打开** —— 语义全错、且是用户没要的副作用。
    //    （这正是本项目栽过 6 次的「改了 A 但 A 的输入被 B 掐断」的镜像形态：
    //     这里是「没想改 A，A 却被 B 改了」。）
    //
    // 先例 = [TRANSLATION_OUTLINE_SYNC_KEY]：**命名空间成员 + 不进触发集**。
    // 探针 §⑧ 的 `"cover."` 命名空间护栏需要它才能对账，故必须 `val` 公开。
    //
    // ★★★ 2026-10-17：**本项已下架**（`cover.lock` 的 descriptor 标了 `retired = true`，
    //   见 `SettingRegistry` 的 KDoc：悬浮窗形态已从设置面撤下 ⇒ 本项无入口）。
    //   ★ 但**本常量必须保留**：`SettingCouplingProbe` §⑧ 的 `"cover."` 命名空间护栏
    //     按**注册表全部 `cover.*` 键**（含 `retired`）对账 —— 删掉它会让护栏
    //     报「注册表 8 键 vs 联动认识 7 键」✗
    //   ★ 语义不变：**即使被程序化写入，也不得顺手打开 `cover.enabled`** ✓
    val COVER_LOCK_KEY = "cover.lock"

    /**
     * 本次变更需要**附带写入**的键值对。无联动时返回空 Map。
     *
     * @param changedKeys 本次实际发生变化的键集合（[SettingChange.changedKeys]）。
     *
     * ⚠️ **幂等且不成环**：返回的键（`*.mode`）本身不在任何触发条件里，
     * 故调用方把结果再 `apply` 一次时，本函数返回空 —— 不会自激。
     * 探针里有一条断言专门钉这个性质。
     */
    // ── 插槽组件 **不**做联动（2026-10-05 · **用户拍板后撤回**）────────────────────
    //
    // ## ★★ 用户原话：「**总开关不管小组件**」
    //
    // ⇒ 我一度想照 `cover` 的模式加一条「**选插槽组件 ⇒ 自动打开 `spectrum.enabled`**」——
    //   ★★ **方向错了**，已被用户否定 ✗
    //
    // ## 正确的分层（★ 用户的口径）
    //
    // | 档位 | 谁管它 |
    // |---|---|
    // | `anchor = BOTTOM` / `TOP`（**歌词区 / 贴边**） | ★ **`spectrum.enabled`（总开关）** |
    // | `anchor = RIGHT`（**右端插槽 = 小组件**） | ★★ **`island.right_slot.component` 自己** —— **总开关不管** |
    //
    // ★ **理由**：插槽是"**组件**"（和时钟、封面并列），它有自己的选择项（关闭/小频谱/时钟/放射）——
    //   ★ **那 4 个选项本身就是它的开关** ⇒ 再拿总开关去门控它，等于**同一个东西有两个开关** ⇒
    //   用户会撞上"我选了小频谱却没反应"（**本轮真实发生**）✓
    //
    // ⇒ ★ **修法在绘制侧**（`drawBackgroundSpectrum`：总开关只对非插槽档生效），
    //   **不在本文件** —— 联动是"改 A 顺手写 B"，而这里**根本不需要写 B** ✓

    fun releasePatch(
        changedKeys: Set<String>,
        /**
         * 读某个键的**当前文本值**。
         *
         * ## 为什么是可选参数、默认 `{ null }`
         *
         * 前三组联动**与值无关**（"拖了 A 就把 B 设成固定值"），不需要读数；
         * 只有「歌词行数 → 显示翻译」那一组要问"用户选了哪一档"。
         * 给它默认值的好处：**纯逻辑断言不必构造 `SettingsSnapshot`**
         * （那要一套夹具），而"读不到值"时的行为是**什么都不写**（不猜）。
         */
        valueOf: (String) -> String? = { null },
    ): Map<String, Any?> = buildMap {
        if (BAR_PITCH_KEY in changedKeys) {
            put(BAR_PITCH_MODE_KEY, BAR_PITCH_MODE_RELEASED)
        }
        if (SECONDARY_OPACITY_KEY in changedKeys) {
            put(SECONDARY_MODE_KEY, SECONDARY_MODE_RELEASED)
        }
        // 副歌词描边组：碰其中**任一成员** → 关掉「跟随主歌词」。
        // 否则整组都被它短路（`translationOutlineFor()` 第一行就 return 主描边），
        // 用户改了宽度/颜色也看不到任何变化。
        if (changedKeys.any { it in TRANSLATION_OUTLINE_KEYS }) {
            put(TRANSLATION_OUTLINE_SYNC_KEY, TRANSLATION_OUTLINE_SYNC_RELEASED)
        }
        // 「歌词行数」→「显示翻译」（BUG-079）。
        //
        // `layout.lines` 自 2026-09-18 合并枚举后**已无任何效果**：
        // `LineCount.SINGLE.rows == DUAL.rows == 1`，而它的唯一消费链
        // （`islandHeightFor` → `effectiveRows`）终点就是那个常量
        // → **用户拨它什么都不发生**（纯 no-op，不是"影响高度"）。
        //
        // 这里让它去驱动**真正生效**的那个键，于是它重新有了意义。
        //
        // ⚠️ **读不到值就不写**（不猜）—— 这与"不静默吞掉用户输入"是同一族纪律：
        //    宁可不生效，也不要把用户的设置改成我们猜的东西。
        if (LAYOUT_LINES_KEY in changedKeys) {
            when (valueOf(LAYOUT_LINES_KEY)) {
                LAYOUT_LINES_DUAL -> put(TRANSLATION_ENABLED_KEY, true)
                LAYOUT_LINES_SINGLE -> put(TRANSLATION_ENABLED_KEY, false)
                else -> Unit
            }
        }
        // 封面组：碰其中**任一成员** → 打开「显示专辑封面」。
        //
        // `cover.enabled` **默认 false**，而封面形状 / 转动 / 转速 / 缩放全被它门控。
        // 用户把「封面形状」改成圆形、把「封面转动」打开却**什么都没发生** ——
        // 因为他根本没开「显示专辑封面」。**这是最容易被误报成"死设置"的一种。**
        if (changedKeys.any { it in COVER_KEYS }) {
            put(COVER_ENABLED_KEY, true)
        }
        // ★★ 2026-10-05：**此处刻意【没有】"插槽组件 → 打开频谱总开关"** ——
        //   用户拍板「**总开关不管小组件**」⇒ 插槽档由 `island.right_slot.component` **自己**决定，
        //   不该去写 `spectrum.enabled`。★ 理由与修法见上方那段 KDoc ✓
    }
}
