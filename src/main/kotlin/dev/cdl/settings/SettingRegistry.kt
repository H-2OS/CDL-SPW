// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

/**
 * 分组 —— **两级：一级 = 组件，二级 = 组件内的细类**。
 *
 * ## 本轮（2026-09-21）改了什么
 *
 * 用户原话：「**在组件分组的基础上更细。**」
 *
 * 上一轮把判据定成"**按被调整的方面**归组"，结果正是用户看到的那个毛病：
 * **同一个组件的设置被拆散到多个组里**。实测（**2026-09-24 快照**；
 * 总数以 [EXPECTED_TOTAL] 为准）：
 *
 * | 组件前缀 | 项数 | 上一轮被拆到 |
 * | --- | --- | --- |
 * | `main_lyric.*` | 27 | 歌词颜色(14) · 歌词描边(7) · 字体(4) · 歌词(2) |
 * | `island.*` | 14 | 外观(6) · 词岛显示(4) · 词岛交互(4) |
 * | `layout.*` | 14 | 歌词(5) · 无歌词(4) · 位置与尺寸(3) |
 * | `translation.*` | 8 | 歌词描边(6) · 歌词(1) |
 *
 * ⚠️ 与上一轮的记录对照可以看出：**「组件」与「方面」是两根不同的轴**。
 * 上一轮把「歌词颜色 / 歌词描边 / 字体」各自独立成组，理由是"用户要调描边时会想
 * 『我去哪调描边』"—— 那个理由本身没错，错在**把它当成了一级轴**：
 * 于是一个 `main_lyric` 组件被切成 4 组，用户想"把主歌词整体调一遍"时要跨 4 个组。
 * 本轮把**组件立为一级**，把"方面"降为二级（`subGroup`）—— 两轮的诉求因此同时满足：
 * 一级扫"我要调哪个东西"，二级扫"那个东西的哪一面"。
 *
 * ## 一级（组件）的 **10** 个取值
 *
 * 按"**这个设置调整的是画面上的哪个东西**"划分：
 * · **7 个"画面上的东西"** —— 歌词 / 词岛 / 频谱 / 封面 / 播放控件 / 动画 / 菜单与设置；
 * · ★ **3 个 U12c 新拆出的**（判据不同：**"体量超 30 且自成一族"**，见各自 KDoc）——
 *   `歌词颜色与描边`（28）· `词岛外观`（15）· `词岛材质`（17）。
 *
 * ⚠️ ★★★ **2026-10-17：撤销了 2026-09-22 的「总开关」组（`TOP_LEVEL`）** ——
 * 用户原话：「**取消总分组，将其下发回各自的组。**」
 * ⇒ 6 个成员（`island.enabled` / `spectrum.enabled` / `cover.enabled` /
 *   `animation.enabled` / `animation.style` / `settings.auto_ungate_on_touch`）
 *   **回到各自的组件组**；`ALL` 由 11 组回到 **10 组**，第 0 位不再是特例组。
 * ★ 同时**删除**了只为"设置页移回原组"而存在的 `PAGE_GROUP_OVERRIDES` ——
 *   组本身就是原组了，覆盖表已无对象 ⇒ 留着就是**不可证伪的空护栏**。
 * ★ **一级 = 组件**这条判据**重新成立**（不再有"跨组件特例组"）。
 *
 * ⚠️ ★★ **2026-10-08（U12c）又加了 3 个** —— 触发条件不同：不是新功能，
 * 而是用户提出**体量约束**「**每组最好不超30项，但总的分组不要过多**」。
 * ⇒ **拆分判据与"是不是一个画面上的东西"无关**，而是
 * **"这一族够不够大（独立后自身仍 ≤30）+ 语义自洽 + 不破坏『同组件同细类』"**。
 * ★ 三条判据的完整论证见 [LYRIC_COLOR_OUTLINE] / [ISLAND_APPEARANCE] / [ISLAND_MATERIAL]
 * 三个常量的 KDoc。★★ **这两个判据（语义 vs 体量）不要混用** ——
 * 前者决定"一个组件是什么"，后者决定"多大的组件该被切开"。
 *
 * ⚠️ **7 个而不是更多**（★ 此句为 2026-09-21 的原判据，**已不适用**，保留备查）：
 * `shape.*` `background.*` `island.border.*` `window.position`
 * 调整的都是**词岛这个窗口本身**（它长什么样、在哪儿），故归「词岛」；
 * 它们上一轮在「外观」里 —— 那是个**方面名**（"外观"不是组件），
 * 作为一级轴会与「词岛显示」「词岛交互」打架（三者都是词岛的方面）。
 * ★★ **U12c 之后，`shape.*` / `background.*` / `island.border.*` 已**不再**归「词岛」**
 *   （它们进了 [ISLAND_APPEARANCE]），`window.position` 与材质族同理 ——
 *   ★ 这**不推翻**上面那条判据，而是**加了第二层**：
 *   它们**仍然**是"词岛这个窗口本身"的东西，只是**体量太大、需要分屏**。
 *   ⇒ ★ 组件的**归属判据（语义）** 与 **切分判据（体量）** 是**两层**，不是一个。
 *
 * ## 二级（细类）为什么必须存在
 *
 * 一级最大的组件是 `歌词颜色与描边`（**28 项**，U12c 前是「歌词」48 项）——
 * 只到一级的话，用户要在一屏 28 项里找一项。
 * 故每个组件内再按"**操作上的相邻性**"切细类（如「歌词 ▸ 歌词颜色 / 歌词描边 / 字体 …」）。
 * 细类**只声明一次**（[SUBS]），`SettingRegistry.verify()` 断言：
 * 每个 `subGroup` 都在其 `group` 下声明过，且每个声明的细类都**非空**。
 *
 * ## `ALL` 的顺序 = **使用频率**（用户指定"自分类树顶端向下排"）
 *
 * 最常改的排前面：**歌词 → 词岛 → 频谱 → 封面 → 播放控件 → 动画 → 菜单与设置**。
 * 顺序**只有这一处**（`ALL` / [SUBS]）——
 * `MenuPolicy.MENU_BUCKETS`、`byComponentThenSub` 与设置页的扁平顺序都从它派生。
 *
 * ## 为什么一级常量只有 7 个（而上一轮有 14 个）
 *
 * 分组名有 4 个消费者：① `SettingRegistry.byGroup` 的 key；② `MenuPolicy.MENU_BUCKETS`
 * 的桶成员；③ 右键菜单的子菜单标题；④ `EditHistory` 的"哪些组变了"。
 * 若两处各写各的字符串，一个错别字就会让某组在菜单里**静默消失**（`byGroup` 查不到 key 就返回空）。
 * 常量让编译器替我们挡住这类拼写错误。
 * ⚠️ **改这里必须同步 `window/MenuPolicy.kt` 的 `MENU_BUCKETS`** ——
 * 它逐条引用下面的常量，且 `MenuProbe` 断言"每个一级组恰好落在一个桶里"。
 */
object SettingGroups {
    // ── 一级：组件（7 个，按使用频率）────────────────────────────────────────

    /**
     * `island.enabled` 的键名 —— 供 `RegistryProbe` §11 的成员断言引用。
     *
     * ⚠️ 2026-10-17：原 KDoc 写「[TOP_LEVEL] 里"整块功能开关"的成员键」——
     * **那个组已撤销**，本项现在属 `ISLAND · 显隐与交互`。常量本身保留：
     * 它仍是"词岛总开关"这个键的**唯一名字来源**，探针用它断言"成员回到哪个组"。
     *
     * ⚠️ 生产消费点（`CdlRuntime` / `IslandWindow` / `IslandPopupMenu`）仍写字面量，
     * 本轮**未改**它们（跨文件、各自在飞），这一点**如实记在这里**而不是假装已统一。
     */
    const val ISLAND_ENABLED_KEY = "island.enabled"

    /**
     * **歌词** —— 主歌词 / 副歌词的**文本与排版**：行数、行间距、对齐、字间距、
     * 逐字高亮、时间轴偏移、字体族 / 字号 / 粗细、译文开关与位置、无歌词处理。
     *
     * ## ★★ 2026-10-08（U12c）拆分：本组件由 48 项降为 **20 项**
     *
     * 用户原话（**决定性的那条**）：
     *
     * > 「**每组最好不超30项，但总的分组不要过多**」
     *
     * 经询问确认「组」指**一级组件**（不是二级细类）—— 实测本组件 **48 项**是本注册表
     * 最大的组件（第二名「词岛」47），**两个都超 30**，其余 6 个组件全部 ≤15。
     *
     * 故把「歌词」里**成对且体量最大**的两族（`歌词颜色` 14 + `歌词描边` 14 = **28 项**）
     * 拆成新组件 [LYRIC_COLOR_OUTLINE]。★ 拆这两族的判据：
     *
     * | 判据 | 说明 |
     * | --- | --- |
     * | **① 体量够大** | 28 项，独立后**自身仍 ≤30**（否则拆完那个新组件也超，白拆） |
     * | **② 语义自洽** | 颜色与描边是"**同一批文字的两种着色方式**"，用户调色时本就一起看 |
     * | **③ 不破坏「同组件同细类」** | 两族各含 14 项、且**都是** `COLOR`/`SEEKBAR`，没有一个 `LIST`/`SWITCH` 混进来 |
     * | **④ 剩下的仍成块** | 20 项 = 布局 · 字体 · 间距 · 高亮 · 译文 · 无歌词，是完整的"排版"语义 |
     *
     * ⚠️ **命名刻意用「歌词颜色与描边」而非「歌词外观」**：后者会把「字体」「字间距」
     * 也吸走（它们**也是**外观）⇒ 反而制造歧义。名字**逐字描述内容**，不留解释空间。
     *
     * ⚠️ 本组件名 [LYRIC] = `"歌词"` **不变** —— 改名会让用户在菜单里找不到原位置
     * （且 `MENU_COMPONENT_SECTIONS` 的命名空间断言会当场红，见 `MenuPolicy`）。
     */
    const val LYRIC = "歌词"

    /**
     * **歌词颜色与描边** —— 已唱 / 未唱 / 译文三色的 RGB 与明度、两个颜色来源、
     * 主歌词描边与副歌词描边（开关 / 宽度 / 颜色 / 联动）。**28 项**。
     *
     * ★ 2026-10-08（U12c）**新立的一级组件** —— 拆自 [LYRIC]（详见其 KDoc 的四条判据）。
     *
     * ## 为什么它与 [LYRIC] **必须**是两个组件（而不是一个组件里的两个细类）
     *
     * 「歌词」原本 48 项，在**右键菜单三级**里是 **48 行连排**（分段只靠**不带名字**的
     * 分隔线），用户要滚很久才能找到"描边宽度"。拆成两个**一级**组件后：
     * · 二级菜单多一行「歌词颜色与描边」（用户悬停一次即可直达）；
     * · 「歌词」自己降到 20 行 —— 一眼能扫完。
     * ⇒ 这正是用户要的「每组不超 30」的**可操作**含义。
     *
     * ## 排序位置（在 [ALL] 里紧邻 [LYRIC] 之后）
     *
     * 两个组件**语义同族**（都是歌词），故在二级菜单里必须**挨着** —— 中间隔一个
     * 「词岛」会让用户以为它们没关系。`ALL` 的第 1、2 位就是这件事的唯一定义处。
     */
    const val LYRIC_COLOR_OUTLINE = "歌词颜色与描边"

    /**
     * **词岛** —— 词岛窗口本身：**显隐与交互**、**位置与尺寸**、**右端插槽**。**15 项**。
     *
     * ## ★★ 2026-10-08（U12c）拆分：本组件由 47 项降为 **15 项**
     *
     * 同 [LYRIC]（用户原话「每组最好不超30项，但总的分组不要过多」）。
     * 原 47 项里含**三类与"窗口本身"关系较弱**的族，各自独立成组件：
     *
     * | 新组件 | 收进 | 项数 | 为什么独立 |
     * | --- | --- | --- | --- |
     * | [ISLAND_APPEARANCE] | 形状与背景(8) + 边框(7) | 15 | "长什么样"（形状/圆角/背景/边框）—— 与"窗口在哪、怎么交互"是两件事 |
     * | [ISLAND_MATERIAL] | 材质(12) + 身后窗口(5) | 17 | "用什么材质"（高光/渐变/液态玻璃/毛玻璃）—— 用户 U5 报的"假玻璃"正因它与形状混在一起 |
     *
     * ⚠️ 本组件名 [ISLAND] = `"词岛"` **不变**（理由同 [LYRIC]）。
     * ★ 保留的 15 项 = 显隐与交互(7) + 位置与尺寸(5) + 右端插槽(3) ——
     * 三者共同回答"**词岛在哪、怎么被看见、右端放什么**"，是一块完整的"窗口行为"语义。
     */
    const val ISLAND = "词岛"

    /**
     * **词岛外观** —— 形状（形态 / 圆角 / 角曲率风格 / 曲率指数）、背景
     * （不透明度 / 背景色 / 旋转角 / 颜色来源）、边框（开关 / 宽度 / 颜色来源 / RGB）。**15 项**。
     *
     * ★ 2026-10-08（U12c）**新立的一级组件** —— 拆自 [ISLAND]（详见其 KDoc 的表）。
     *
     * ## 为什么把「形状」「背景」「边框」合成**一个**组件（而不是三个）
     *
     * 三者都是**视觉几何**：用户改"圆角"和改"边框宽度"是**同一个动作**（让词岛看起来
     * 更像某个样子），中间隔一次悬停没有信息量。合成一个 15 项的组件**既 ≤30、
     * 又不制造无谓的层**（这正是用户「总的分组不要过多」那半句的落地）。
     *
     * ⚠️ 若将来还要细拆，唯一正确的依据是**用户反馈**"外观里还是太杂"，
     * 而不是我在这里预判（本项目纪律：不为拆而拆）。
     */
    const val ISLAND_APPEARANCE = "词岛外观"

    /**
     * **词岛材质** —— 高光、背景渐变（风格 / 强度 / 角度 / 止色）、液态玻璃
     * （高光强度 / 立体感 / 表面蒙版 / 投影强度）、颗粒幅度、画质档、封面图模糊、
     * 身后窗口（模糊半径 / 折射厚度 / 折射量 / 色散 / 饱和度）。**17 项**。
     *
     * ★ 2026-10-08（U12c）**新立的一级组件** —— 拆自 [ISLAND]（详见其 KDoc 的表）。
     *
     * ## 为什么「材质」+「身后窗口」合在一起（而不是分成两个）
     *
     * 「身后窗口」5 项是**毛玻璃**（采样词岛**背后的桌面**），而「材质」12 项里
     * 那 4 项「液态玻璃」是**在词岛自身表面**做高光与折射 —— 两者是**同一条光学链**
     * 的两端（`BackdropCaptureParams` 采完背景，`LiquidGlass` 再在其上叠折射）。
     * 合成一个组件后，"玻璃感"的所有旋钮在**同一屏**，用户不必来回跳。
     *
     * ⚠️ 但两者**仍是两个细类**（`材质` / `身后窗口`）—— 因为「身后窗口」有
     * **前置条件**（`blur_radius = 0` ⇒ 整链不跑，默认值就是 0，见 U5 取证）。
     * 细类边界的理由**不因升格为组件而消失**，故保留。
     */
    const val ISLAND_MATERIAL = "词岛材质"

    /** **频谱** —— 实时频谱条。 */
    const val SPECTRUM = "频谱"

    /** **封面** —— 专辑封面。 */
    const val COVER = "封面"

    /** **播放控件** —— 悬停出现的播放控件（上一首 / 播放 / 下一首 / 进度条）。 */
    const val CONTROLS = "播放控件"

    /**
     * **动画**（2026-09-20 新增，2026-09-21 由一级保留）。
     *
     * 用户原话：「**我觉得我们可以引入动画开关，然后增加对CPU GPU性能高的动画效果，
     * 以大大提升UX，当然，用户在发觉性能开销过大时也可以关闭动画开关。
     * （引入的动画效果需要保证观感）**」
     */
    const val ANIMATION = "动画"

    /**
     * **菜单与设置** —— 右键菜单本身（不透明度 / 子菜单延迟）与设置系统的行为。
     *
     * 上一轮叫「设置界面」（常量 `SETTINGS_UI`）。改名理由：这个组里 3 项是**右键菜单**
     * 的属性、1 项是设置行为 —— "设置界面"这个名字既不覆盖菜单，也与
     * `settingsui/` 包名撞车（那个包是自绘工具窗，不是这个组）。
     */
    const val MENU_SETTINGS = "菜单与设置"

    /**
     * 全部一级组，**按使用频率**（决定菜单子菜单顺序与设置页的扁平顺序）。
     *
     * ## ★★ 2026-10-17：11 → **10** 个（撤销「总开关」组）
     *
     * 用户原话：「**取消总分组，将其下发回各自的组。**」
     * ⇒ `TOP_LEVEL` 删除，6 个成员回到各自组件组；第 0 位现在是 [LYRIC]。
     * ★ 因此**不再有"跨组件特例组"** —— "一级 = 组件"这条判据重新成立。
     *
     * ## ★★ 2026-10-08（U12c）：8 → **11** 个（历史，2026-10-17 起为 10 个）
     *
     * 用户原话：「**每组最好不超30项，但总的分组不要过多**」。
     * ★ 拆分前后逐组项数（**机械核对**，见本文件 `expectedByGroup` 与 §H35）：
     *
     * | # | 组件 | 拆前 | 拆后 | 变化 |
     * | --- | --- | --- | --- | --- |
     * | ~~0~~ | ~~总开关~~ | ~~6~~ | **0** | ★ 2026-10-17 撤销，6 项下发回各组件 |
     * | 1 | 歌词 | **48** ⚠️ | **20** | 拆出 [LYRIC_COLOR_OUTLINE] |
     * | 2 | 歌词颜色与描边 | — | **28** | ★ 新 |
     * | 3 | 词岛 | **47** ⚠️ | **15** | 拆出 [ISLAND_APPEARANCE] / [ISLAND_MATERIAL] |
     * | 4 | 词岛外观 | — | **15** | ★ 新 |
     * | 5 | 词岛材质 | — | **17** | ★ 新 |
     * | 6 | 频谱 | 15 | 15 | — |
     * | 7 | 封面 | 6 | 6 | — |
     * | 8 | 播放控件 | 10 | 10 | — |
     * | 9 | 动画 | 4 | 4 | — |
     * | 10 | 菜单与设置 | 3 | 3 | — |
     *
     * ⇒ **最大 28 ≤ 30** ✓ · 拆前**只有 2 个超 30**（48 / 47），拆后**全部合规**。
     *
     * ## 顺序的三条约束（都不是随手排的）
     *
     * ① [LYRIC] 与 [LYRIC_COLOR_OUTLINE] **必须相邻** —— 同族组件隔开会让用户
     *   以为它们无关（明细见 [LYRIC_COLOR_OUTLINE] 的 KDoc）；
     * ② [ISLAND] / [ISLAND_APPEARANCE] / [ISLAND_MATERIAL] **必须相邻**，同上。
     *
     * ⚠️ 这两条**没有**机械断言守（"相邻性"是语义判据，写不成纯字符串比较）——
     * **如实登记在此**，未来重排 [ALL] 的读者须人眼核对（本项目纪律：不假装
     * 语义判据有机械护栏）。**能机械守的那条**（表 == ALL 逐字逐序）由
     * `MenuProbe` §J3 与 `MenuRegistrySyncProbe` §A6e 守着。
     *
     * ⚠️ 与 [SUBS] 的 key 集合必须一致 —— `verify()` 断言这一点。
     */
    val ALL: List<String> = listOf(
        LYRIC,
        LYRIC_COLOR_OUTLINE,
        ISLAND,
        ISLAND_APPEARANCE,
        ISLAND_MATERIAL,
        SPECTRUM,
        COVER,
        CONTROLS,
        ANIMATION,
        MENU_SETTINGS,
    )

    /**
     * 二级组（细类）：**组件 → 该组件内的细类，按显示顺序**。
     *
     * ## 切分判据
     *
     * 按"**操作上的相邻性**"切 —— 用户想改 X 时，会顺带看一眼的那几项放一起。
     * 不按 kind（"开关类 / 滑杆类"那种切法会把"描边开关"和"动画开关"放一起）。
     *
     * ## 为什么细类写在这里，而不是各 descriptor 自己声明顺序
     *
     * `subGroup` 是**每个 descriptor 自己带的字符串**（那样才不会有两份清单）。
     * 但**顺序**需要一个声明处 —— 这里就是它。`verify()` 双向断言：
     * ① 每个 descriptor 的 `subGroup` 在本表里、且属于它的 `group`；
     * ② 本表里每个细类都**至少有一项**（防"声明了却没人用"的空壳细类）。
     */
    val SUBS: Map<String, List<String>> = mapOf(
        LYRIC to listOf(
            "布局与行数",   // 行数 / 行间距 / 对齐
            "高亮与同步",   // 逐字高亮方式 / 时间轴偏移 / 歌词行切换的动效风格
            "字间距",       // 主歌词字间距 + 副歌词是否跟随
            "字体",         // 族名 / 字号 / 粗细 / 最近使用
            "译文",         // 副歌词开关 / 字号 / 位置
            "无歌词",       // 无歌词时的显示方式与曲名
            // ── ★★★ 2026-10-08（U12c）：原「歌词颜色」「歌词描边」两个细类（各 14 项）
            //    已**整族搬走**，成为新的一级组件 `LYRIC_COLOR_OUTLINE`。─────────────
            //
            // ★ 为什么搬的是这两族（四条判据的全文见 `LYRIC_COLOR_OUTLINE` 的 KDoc）：
            //   ① 体量够大（28，独立后自身仍 ≤30）；
            //   ② 语义自洽（同一批文字的两种着色方式）；
            //   ③ **不破坏「同组件同细类」** —— 两族里没有一个 `LIST`/`SWITCH` 混进来；
            //   ④ 剩下的 6 个细类仍是完整的"排版"语义。
            //
            // ⚠️ 留下 6 个细类 = 20 项。**细类名一个没改** —— 只搬走，不改名
            //   （改名会让"细类必须被使用"的双向断言与生成器同时失败，是没必要的风险）。
        ),
        LYRIC_COLOR_OUTLINE to listOf(
            // ★ 2026-10-08（U12c）新组件：细类**逐字沿用**从 `LYRIC` 搬来的两个名字。
            //   ⚠️ 沿用而非重命名，是刻意的：① 用户已认识这两个词；
            //   ② `RegistryProbe` §12.3 等断言按 `LYRIC to "歌词描边"` 定位，
            //      沿用可让**断言只需改组名**（改动面最小，纪律：不为改名而改名）。
            "歌词颜色",     // 已唱 / 未唱 / 译文 三色的 RGB + 明度，及两个颜色来源
            "歌词描边",     // 主歌词描边 + 副歌词描边（开关 / 宽度 / 颜色 / 联动）
        ),
        ISLAND to listOf(
            // ⚠️ 2026-09-22 修：这里原写「全屏隐藏 / 暂停隐藏 / 置顶 / **词岛动效风格**」——
            //    最后一项**不存在**，是个"声明了却没有"的幽灵（词岛显隐的动效
            //    `Motions.ISLAND_FADE` 是 `followsGlobalStyle = false`，
            //    故**故意**没有设置项，理由见 `Motions` 的 KDoc）。
            //    留着它会让下一个读者以为"词岛动效风格丢了"，从而**加回一个死设置**。
            "显隐与交互",   // 全屏隐藏 / 暂停隐藏 / 置顶 / 鼠标穿透 / 悬停隐藏 / 锁定位置 / 封面命中
            // ⚠️ ★★★ 2026-10-08（U12b）：声明序的**唯一消费者是 `SUBS.indexOf`**（见下）。
            //
            // ★★ **先纠正一条我自己犯过的错**（留在这里防止下一个读者再犯）：
            //    我在 U12b 施工中一度断定"**右键菜单按注册序渲染、不重排；设置页按声明序重排
            //    ⇒ 两者不一致 ⇒ 同一细类会在菜单里被劈成两段**"，**这是错的**。
            //    ★ 真相：`SettingRegistry.all`（`:4265`）是**唯一的排序出口** ——
            //      `declared.sortedBy { ALL.indexOf(group)*1000 + subOrder() }`，
            //      而 `subOrder()` 就是 `SUBS[group].indexOf(subGroup)`。
            //      **菜单读 `byGroup`（= `all.groupBy`）、设置页读 `byComponentThenSub`
            //      （= 从 `all` 过滤）—— 两者同源**，物理声明序**根本不参与渲染**。
            //    ★ `sortedBy` 是**稳定排序**：同名细类的项 `subOrder()` 相等 ⇒ **聚成连续一块**
            //      ⇒ **永不劈段**（实测：把某项物理挪走，呈现序完全不变）。
            //    ⇒ ★ 故**改 `SUBS` 顺序的唯一后果 = 改变呈现顺序**（这正是我们要的），
            //      **不会**造成"两处不一致"。详见 §H34 / 纪律 88。
            //
            // ★ 本轮为什么把顺序改成"位置与尺寸"在前：
            //   · 原序把「位置与尺寸」放在**倒数第 2**（在「边框」之后）—— 语义上不顺
            //     （位置尺寸是词岛的**基本属性**，应当在"外观"之前）；
            //   · 改后与**物理声明序一致** ⇒ 可读性更好（本文件里 `layout.*` 本来就写在
            //     `shape.*` 之前）。
            //
            // ★★ **纪律 88（新）：量"显示顺序"要读渲染代码读的那个变量（= `all`），
            //    不要扫本文件的物理行序。** 自查句：「我这个数字，是从渲染代码
            //    读的那个变量算出来的吗？」
            "位置与尺寸",   // 位置 / 最大宽度 / 固定宽度 / 高度跟随 / 高度比例
            // ── ★★★ 2026-10-08（U12）：原「位置与尺寸」里混着 3 条**跟尺寸无关**的项 ──
            //
            // 取证（原 `subGroup = "位置与尺寸"` 的 8 条里有 3 条是插槽/时钟）：
            // `island.right_slot.component`（右端插槽放什么）·
            // `clock.hour_format`（时钟时制）· `clock.style`（时钟字形）。
            //
            // ★ **它们与"位置与尺寸"无关**：用户要调"右插槽显示时钟"时，
            //   按语义会去「词岛」组找，却在**五个尺寸项后面**才发现它们
            //   （原顺序：位置/最大宽度/固定宽度/高度跟随/高度比例/插槽/时制/字形）。
            //   ⇒ ★ 这是**归类错误**（不是"太长"），单独立一类即修好。
            //
            // ⚠️ 顺序上**排在最前**的 `right_slot.component` 是另外两条的**前置**：
            //    它选了「时钟」时制/字形才有意义（选「关闭/频谱」时那两条空转）。
            //    故本细类的声明顺序 = 使用顺序，`subOrder()` 会自然照此排。
            "右端插槽",     // 插槽内容（前置）/ 时钟时制 / 时钟字形
            // ── ★★★ 2026-10-08（U12c）：以下两个细类（共 22 项）已**整族搬走** ────────
            //
            //   · 原 `形状与背景`(8) + `边框`(7) ⇒ 新组件 `ISLAND_APPEARANCE`（15 项）
            //   · 原 `材质`(12) + `身后窗口`(5)   ⇒ 新组件 `ISLAND_MATERIAL`（17 项）
            //
            // ★ 搬运后本组件剩 **15 项 / 3 个细类** = 显隐与交互(7) + 位置与尺寸(5) + 右端插槽(3)。
            // ★ **细类名逐字保留**（只换父组件），理由同 `LYRIC_COLOR_OUTLINE`。
            //
            // ⚠️ 拆法判据（为什么这两个族各自独立成组件，而不是留在词岛里）：
            //   ① 它们是**"长什么样 / 什么材质"**，与"窗口在哪、怎么交互"是不同问题；
            //   ② 22 项若留在本组件，本组件 37 项**仍超 30**（没解决问题）；
            //   ③ 拆开后两个新组件分别 15 / 17，**都 ≤30** ✓。
            //
            // ★ 两族的**细类边界仍然保留**（`形状与背景` vs `边框` / `材质` vs `身后窗口`）——
            //   「身后窗口」有前置条件（`blur_radius = 0` 时整链不跑）这件事
            //   **不因升格为组件而消失**。完整论证见 U12 期的 `材质` 段（现移至
            //   `ISLAND_MATERIAL` 的 KDoc 与该组件细类表）。
        ),
        ISLAND_APPEARANCE to listOf(
            "形状与背景",   // 形态 / 圆角 / 角曲率风格 / 曲率指数 · 不透明度 / 背景色 / 旋转角 / 颜色来源
            "边框",         // 开关 / 宽度 / 颜色来源 / RGB
        ),
        ISLAND_MATERIAL to listOf(
            "材质",         // ★ U12 下架后剩 5：渐变 3 + 颗粒 1 + 封面图模糊 1
            // ── ★★★ 2026-10-11（**U12**）**「身后窗口」细类已整体下架** ──────────────
            //
            // ★ 用户指令：「液态玻璃呈现**始终不达标**，**截断其渲染路径以及显示的设置项**，
            //   但有收益的以及对其他功能还有用的建设可以保留。」
            //
            // ★ 该细类的 **6 个键**（模糊半径 / 折射厚度 / 折射量 / 色散 / 饱和度 / GPU 后端）
            //   全部标了 `retired = true` ⇒ ★ **不再出现在设置页** ✓
            //
            // ★★ **为什么整个细类一起移除**：`gen_preference_config.py` 有一条既有校验 ——
            //   ★ **"声明了细类却没有任何设置项用它" ⇒ 判为空壳分类并【拒绝生成】** ✓
            //   ★★ 本轮实测：下架后第一次生成**直接失败**，报的就是
            //   `SUBS["词岛材质"]` 的 `身后窗口` 是空壳 —— ★ 正是这条校验拦住了
            //   "留下一个空分组"这种半成品 ✓
            //
            // ★ 完整的下架评估（哪些专有 / 哪些通用 / 为什么保留）见
            //   `docs/液态玻璃-下架评估-2026-10-11.md` ✓
        ),
        SPECTRUM to listOf(
            "开关与位置",   // 贴边 / 副频谱位置与不透明度（总开关已移到「总开关」组）
            "条与高度",     // 条宽 / 条间距 / 高度 / 跳动幅度 / 对称
            "外观",         // 颜色来源 / 颜色 / 不透明度 / 顶端淡出
            "进度色差",     // 用色差表示进度的开关与压暗程度
        ),
        COVER to listOf(
            // 随字号缩放（总开关已移到「总开关」组）/ 形状 / 转动 / 转速 / 黑胶样式
            "开关与缩放",
        ),
        CONTROLS to listOf(
            "触发",         // 触发方式 / 进入延迟 / 离开延迟 / 控件条出现的动效风格
            "布局",         // 位置 / 与歌词间距 / 命中框外扩
            "进度与隐藏",   // 进度条开关 / 自动隐藏延迟
        ),
        ANIMATION to listOf(
            "质量与帧率",   // 质量档 / 帧率（2026-10-17：`animation.enabled` 已回到本细类）
            "动效风格",     // ★ 2026-10-17 新增细类：`animation.style` 回到「动画」组
            "时长",         // 时长倍率
            // ★ 2026-09-28 新增：`animation.frame_log`（每秒帧率日志）→ 细类「诊断」。
            // ★★ 2026-10-18（**用户审核文案后**）：该键已 `retired` ⇒ 本细类**空壳**
            //   ⇒ 必须一并删除，否则注册表自检直接失败（"空壳分类"）。
            //   ⚠️ 想恢复诊断项时：把本行加回来 + 去掉 `animation.frame_log` 的 `retired` ✓
        ),
        MENU_SETTINGS to listOf(
            "菜单外观",     // 右键菜单不透明度
            "菜单延迟",     // 子菜单展开 / 收起延迟
            // ★ 2026-10-17：`"行为"` **回到本组** —— 它唯一的那一项
            //   （`settings.auto_ungate_on_touch`）随「总开关」组撤销而下发回来。
            //   ⚠️ 2026-09-22 那次移动时它被删掉，是因为留着会被 `verify()` 判为
            //   「空壳分类」（声明了却没有任何设置项）—— 本次回来，它又有成员了 ✓
            "行为",
        ),
    )

    /** 全部二级组（细类）的扁平顺序 —— `一级序 × 组内序`。 */
    val ALL_SUBS: List<String> = ALL.flatMap { SUBS.getValue(it) }

    // ⚠️ 2026-09-21 已删除 10 个临时编译垫片（`LYRIC_COLOR` / `FONT` / `APPEARANCE` /
    //    `ISLAND_VISIBILITY` / `POSITION_SIZE` / `LYRIC_OUTLINE` / `NO_LYRICS` /
    //    `PLAYBACK_CONTROLS` / `ISLAND_INTERACTION` / `SETTINGS_UI`）。
    //
    //    为什么必须删：垫片在时，任何残留的 `group = SettingGroups.FONT` 之类**照样编译过**，
    //    只在**运行期**被 `verify()` 的 `undeclared` 检查炸掉 —— 而 `verify()` 只在
    //    宿主加载插件时才跑，**探针跑不到**。于是"分组名写错"这类缺陷会**静默穿过门禁**。
    //    删掉之后，它退化成**编译期错误**，这正是我们要的（错得越早越便宜）。
    //
    //    删除前置条件（已逐条核对）：`MENU_BUCKETS` **覆盖全部一级组**（形状可变，覆盖不变）；
    //    全树对旧常量的引用**只剩注释**（`MenuProbe` / `MenuPolicy` 均已改用组件常量）。
    //
    //    ⚠️ 2026-09-22 修：这里原先写的是"`MENU_BUCKETS` 已换成 **7 个组件常量**"+
    //      "`MenuProbe.kt:434` 已改成…"—— 两句**都已过时**：分桶形状已被重设计
    //      （不再是"一组一桶"），行号也会随那个文件被编辑而漂移。
    //      故本句改为陈述**不变式**（覆盖全部一级组），而不是复述当时的形状。
    //      ⚠️ 另注：第 0 组「总开关」加进来之后，该覆盖**出现过缺口**（`MenuProbe`
    //      的「分组桶覆盖全部注册表分组」断言会红）—— 缺口修好前，这条前置条件不成立。
}

/**
 * **动效风格**的取值与**总开关 / 子项**的合成规则 —— 拆散 `animation.style` 之后的**唯一判据**。
 *
 * ## 为什么需要它（而不是让每个消费点自己判）
 *
 * 2026-09-22 用户要求把「动效风格总开关」**拆散到各组件组**、但**保留总开关**：
 *
 * > 「动效风格总开关管太多控件的动画了，不好细究，**拆散开来，分到各自的组件组里，
 * >   保留总开关**。」
 *
 * 于是"某一处动效到底用哪一档风格"由**两个**设置项共同决定（总开关 + 该组件的子项）。
 * 若让 `IslandWindow` 的两个消费点各自去判，这条规则就有**两份实现** ——
 * 迟早有一处漏改（本项目已因"同一规则写两遍"栽过多次，见 `SettingCoupling` 的 KDoc）。
 *
 * 故规则只在这里写一次，[effective] 是**纯函数**（无状态、不碰 Swing、不碰 `MotionStyle`），
 * 消费点只需一行：
 *
 * ```kotlin
 * // IslandWindow 里（示意；实际写法见本文件 KDoc 的"消费点接线"）
 * val style = MotionStyle.parse(
 *     AnimationStyleResolver.effective(
 *         componentValue = text("lyric.animation_style", AnimationStyleResolver.FOLLOW_TOTAL),
 *         totalValue = text("animation.style", AnimationStyleResolver.TOTAL_PER_COMPONENT),
 *         factoryDefault = Motions.LYRIC_EXIT.style.name,
 *     )
 * )
 * ```
 *
 * ## ⚠️ 为什么返回值是 `String` 而不是 `MotionStyle`
 *
 * 本文件属于 `settings` 包，而 `MotionStyle` 在 `animation` 包。让 settings 依赖 animation
 * 会把"设置系统"与"动效实现"焊死（设置系统在探针里可单独加载，不应当被 Swing/AWT 拖下水）。
 * 故本对象**只认字符串**，把"字符串 → 枚举"这一步留给消费点（那里本来就 import 了 `MotionStyle`）。
 *
 * ## 语义：**总覆盖**（不是"仅当子项为默认时生效"）
 *
 * | 总开关 `animation.style` | 子项 `*.animation_style` | 结果 |
 * | --- | --- | --- |
 * | `FADE` / `SLIDE` / `POP`（**具体风格**） | 任意值（含 `FOLLOW`） | **总开关的值** —— 子项被覆盖 |
 * | `PER_COMPONENT`（不干预，**默认**） | `FADE` / `SLIDE` / `POP` | 子项的值 |
 * | `PER_COMPONENT` | `FOLLOW`（默认） | `factoryDefault`（该动效的出厂风格） |
 *
 * **为什么选"总覆盖"而不是"仅当子项为默认时生效"**：用户说的是"**保留总开关**"——
 * 保留意味着它**仍然说了算**。而"仅当子项为默认"会让总开关在用户动过任一子项之后
 * **永久失效**（且没有任何界面提示"你的总开关已经不管用了"），那是比拆散更难理解的形状。
 *
 * **用户怎么改回"跟随总开关"**：把子项拨回 `FOLLOW`（LIST 的**第 0 项**，
 * 也是子项的默认值）。[componentOptions] 把这一项**固定在索引 0** ——
 * `RegistryProbe` §11 有一条断言钉住这个位置，因为"跟随总开关"一旦不在第 0 项，
 * 用户就找不到回到总开关的路（拆散等于没拆）。
 */
object AnimationStyleResolver {

    /** 子项取值：**跟随总开关**（用总开关的风格；总开关不干预时落回该动效的出厂风格）。 */
    const val FOLLOW_TOTAL = "FOLLOW"

    /** 总开关取值：**不干预** —— 各组件用各自的子项（这是总开关的默认值）。 */
    const val TOTAL_PER_COMPONENT = "PER_COMPONENT"

    /**
     * 可选的具体风格 —— **必须与 `dev.cdl.animation.MotionStyle` 的条目逐字一致**。
     *
     * ⚠️ 这里**不能** import 那个枚举（理由见本对象 KDoc），故这条一致性只能由
     * `RegistryProbe` §11 断言（它同时 import 两侧，逐字比对）。
     * 不一致的后果：用户在设置页选了「弹出」，`MotionStyle.parse` 认不出 → **静默回退默认**。
     */
    val STYLES: List<String> = listOf("FADE", "SLIDE", "POP")

    /**
     * **子项**的选项表 —— `FOLLOW` 固定在第 0 项（理由见本对象 KDoc）。
     *
     * 消费点（`SettingDescriptor.options`）一律引用它，不要各写一份：
     * 三处各写一份的话，"加一档风格"就会变成改三处。
     */
    fun componentOptions(): List<Pair<String, String>> = listOf(
        FOLLOW_TOTAL to "跟随总开关",
        "FADE" to "淡入",
        "SLIDE" to "滑入",
        "POP" to "弹出",
    )

    /**
     * 合成出**实际生效**的风格名（`FADE` / `SLIDE` / `POP` 之一，恒不是 [FOLLOW_TOTAL]）。
     *
     * @param componentValue 该组件的子项（如 `lyric.animation_style`）。`null` / 空 / 未知值
     *   → 视作 [FOLLOW_TOTAL]（**不抛**：设置文件可能被手改坏，最坏是回到总开关）。
     * @param totalValue 总开关 `animation.style`。`null` / 空 / 未知值 → 视作 [TOTAL_PER_COMPONENT]。
     * @param factoryDefault 该动效的**出厂风格**（`MotionSpec.style.name`）——
     *   由调用方传，本对象因此不必知道任何 `MotionSpec`。
     */
    fun effective(
        componentValue: String?,
        totalValue: String?,
        factoryDefault: String,
    ): String {
        // ① 总覆盖：总开关是**具体风格**时，子项一律不算数。
        val total = totalValue?.trim()?.uppercase()
        if (total != null && total in STYLES) return total
        // ② 总开关不干预 → 看子项。
        val component = componentValue?.trim()?.uppercase()
        if (component != null && component in STYLES) return component
        // ③ 子项是「跟随总开关」（或值坏了）→ 落回该动效的出厂风格。
        return factoryDefault
    }
}

/**
 * 全部设置项的**单一事实来源**（大纲 §9.1）。
 *
 * ## 权威性声明
 *
 * 本对象的项数**只看 [EXPECTED_TOTAL]** —— 本段**不写数字**（理由见下）。
 * 基线 40 项，之后由若干次**产品决定**增补（译文色、`layout.line_mode` /
 * `layout.line_gap_px`、`performance.host_lyrics_probe`、频谱三项、
 * 歌词三色改 12 根滑杆、`spectrum.bounce_pct`、`spectrum.progress_tint`、
 * 两个菜单延迟项…），并在 2026-09-18 移除 `layout.emptySlot.keepPlaceholder`。
 * 每一次增删的理由都记在 [EXPECTED_TOTAL] 的 KDoc 里。
 *
 * ⚠️ **不要把项数硬编码进散文** —— 这条已经**复发过一次**，别让它再犯：
 * · 原记「本对象的 **44 项**」→ 到 2026-09-18 已陈旧 15 项而无人发现；
 * · 改写成「**当前 60 项**」→ 到 2026-09-21 已陈旧 **40 项**（实为 100）而无人发现。
 * · 结论：**"把它改对"这个动作本身不解决问题** —— 只要散文里还有数字，它就一定会再陈旧。
 *   故本段现在**一个数字都不写**，项数只有 `[EXPECTED_TOTAL]` 一处。
 *
 * （本段自身的教训：注释里的数字没有断言保护 —— 而"再检查一遍"不是保护，
 * **删掉那个数字**才是。）
 *
 * 逐项取自 `首版设置总表.md` §1 主表，是该表的代码化。
 * 任何与本对象冲突的旧文档（`大纲 §9.8` 的 170–260 项愿景、`大纲 §12` 的"约 28 项"、
 * `recon/13` 的 11 项、`recon/14` 的 18 项）**均以总表为准**（总表 §0.1 的定位声明）。
 *
 * 总表 §10.2 已用脚本交叉验证其计数闭合（**基线 40 项**）：类型分布
 * `switch 17 / seekbar 10 / list 9 / edittext 1 / color 2 / font 1 = 40`，
 * 阶段分布 `P0 6 + P1 34 = 40`，分组数 15。
 * [verify] 把这套校验**变成代码里可执行的断言**，防止将来手工加项时把数字搞乱。
 * 基线之后新增的 4 项（译文色、line_mode、line_gap_px、host_lyrics_probe）
 * 都是**产品决定**带来的偏离，各自在 [EXPECTED_TOTAL] 的 KDoc 里记了理由。
 *
 * ## 组织方式（为 `code-index` 可定位而设计，见任务要求）
 *
 * 按总表 §1 的字母组分成**独立私有函数**（`groupA`…`groupJ`），
 * 而不是糅在一个巨型 `buildList` 里。理由：速查表要能按功能定位到"某一项在哪"，
 * 一个函数对应一个语义组，就能给出"`island.*` 在 `groupA()`"这样的精确落点。
 *
 * ## 命名规范
 *
 * 键名一律**点号嵌套**（如 `island.enabled`、`main_lyric.fill.color`），
 * 与总表 §1 的「设置键名（点号嵌套）」列逐字一致。
 * 分组内用 `_` 连接多词（`hide_fullscreen`），**两种风格并存是有意的** ——
 * 它保留了总表原样（那个表混用了 snake_case 与 camelCase），
 * 若我们擅自统一，会让"总表某个键"与"代码里某个键"对不上，反而增加核对成本。
 */
object SettingRegistry {

    // ── A 组 · 行为与显隐（8 项，总表 §1.1 序号 1–8） ──────────────────────
    private fun groupA(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "island.enabled",
            // ★★★ 2026-10-17（用户：「**取消总分组，将其下发回各自的组**」）：
            //   本项**回到「词岛」组**的「显隐与交互」细类（它 2026-09-22 曾被移到
            //   「总开关」组，该组现已撤销 —— 记录见 `SettingGroups.ALL` 的 KDoc）。
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "显示词岛",
            default = true,
            phase = SettingPhase.P0,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.interactive",
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "鼠标穿透",
            summary = "**勾选后鼠标可穿过词岛**（词岛不再响应点击与拖动）；取消勾选则词岛可交互。若已穿透而想操作词岛，用右键菜单里的「找回词岛」。",
            // 默认 false（可交互）。语义注意：本项名为「鼠标穿透」，`true` 表示**穿透**，
            // 与原实现 `click_through` 同名同义（总表 §1.1 序号 2 的「原实现对应项」列）。
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.hide_fullscreen",
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "全屏时隐藏",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.hide_paused",
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "暂停时隐藏",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.auto_hide_on_hover",
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "鼠标经过时自动隐藏",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.always_on_top",
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "窗口置顶",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.lock_position",
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "锁定位置",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        // ── 2026-09-19：封面是否进"命中域" ─────────────────────────────────
        //
        // 用户原话：「**追加封面命中开关。**」
        //
        // ## 什么叫"进命中域"
        //
        // `IslandWindow` 在 `REGIONAL_CLICK_THROUGH = true` 下**只在命中字形
        // 或播放控件处不穿透**（见 `applyRegionalClickThrough`）。未登记的矩形是
        // **穿透空洞**：鼠标移上去既点不中也**拖不动词岛**（拖动要求窗口先收到按下事件）。
        // 封面此前**不在**命中域里 —— 于是"封面那一块"正是这样一个空洞。
        //
        // ## 为什么默认 `false`（不是 `true`）
        //
        // 登记封面会**顺带改变两处既有行为**：
        //   ① 鼠标停在封面上会像停在歌词上一样**展开播放控件**（命中域扩大）；
        //   ② 封面上不再穿透，下层窗口在该处收不到点击。
        // 这两条都是**可见的行为变化** —— 与 `spectrum.progress_tint` / `island.border.enabled`
        // 同一条纪律：新增的交互/视觉不该改变既有用户的观感，默认必须是"关"。
        // 用户要的是"**能**让封面进命中域"，那是一个开关，不是一个新默认。
        SettingDescriptor(
            key = "island.hit.cover",
            group = SettingGroups.ISLAND,
            subGroup = "显隐与交互",
            kind = SettingKind.SWITCH,
            title = "封面参与鼠标命中",
            summary = "开启后，鼠标停在封面上与停在歌词上一样：词岛不穿透、可拖动，并会展开播放控件。",
            // ★ 2026-10-17：删掉「仅在形态为悬浮窗（封面态）时本项无效」一句 ——
            //   该形态已从 `shape.type` 的选项里撤下（用户同日指令），
            //   再提它只会让用户去找一个**选不到**的形态 ✓（连带清理，见 `cover.lock` 的 KDoc）
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    // ── B 组 · 歌词内容与同步（2 项，总表 §1.2 序号 9–10） ────────────────
    private fun groupB(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "layout.translation.enabled",
            group = SettingGroups.LYRIC,
            subGroup = "译文",
            kind = SettingKind.SWITCH,
            title = "显示翻译（副歌词）",
            default = true,
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.translation.scale_percent",
            group = SettingGroups.LYRIC,
            subGroup = "译文",
            kind = SettingKind.SEEKBAR,
            title = "副歌词字号（%）",
            /**
             * 用户 2026-09-21：「加入**主副歌词字号比例**（**1.0 为中值**）」。
             *
             * ## 为什么"中值 = 100"而不是"中值 = 0.64"
             *
             * 用户明确要求「**1.0 为中值**」。若把滑杆做成"直接填比例"
             * （0.5..1.5，中值 0.64 附近），滑杆中点就不是 1.0 —— 与要求不符，且
             * "偏离中值多少"这个最直观的心智模型会失效。
             *
             * 故做成**百分比**：`100 = 标准比例`（= 出厂观感，**开箱零变化**），
             * 实际比例 = `0.64 × (本值 / 100)`。**滑杆中点是 100，就是"1.0"** ✓
             *
             * ## 为什么上限是 150 而不是 200
             *
             * 150% → 实际比例 `0.96`，副歌词**仍未超过主歌词** —— 这是有意的：
             * 副歌词比主歌词还大，会立刻破坏"主行是视觉焦点"这条基本层级。
             * 若真要让两者一样大，150 已经足够接近（0.96 ≈ 1.0）。
             */
            summary = "副歌词相对主歌词的字号。100 = 标准（副歌词为主歌词的 64%）；越大副歌词越接近主歌词。",
            default = 100,
            range = 50.0..150.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 歌词描边（2026-09-18）────────────────────────────────────────
            //
            // 用户原话：
            // > 「**加入主歌词和副歌词的字体描边，两种歌词独立设置，也可设置两者同步**。」
            //
            // 结构：主歌词 5 项（开关 / 宽度 / RGB 三根滑杆）+ 副歌词 6 项
            // （同步开关 + 开关 / 宽度 / RGB）。
            //
            // ## 为什么"同步"是一个**开关**而不是"两套值自动一致"
            //
            // 用户要的是"**可**设置两者同步"—— 即**同步与否由他决定**。
            // 若强行让两者永远一致，就丢掉了"主歌词描边、副歌词不描边"这种合理组合；
            // 若完全不提供同步，用户改主歌词描边时要手动改 5 项。
            // 开关是唯一同时满足"省事"与"可不同"的形态。
            //
            // ## 为什么颜色用 3 根滑杆而不是 COLOR 类型
            //
            // 与 `main_lyric.fill.color` 同一处置（宿主 5 种控件里没有取色器，
            // 见 `recon/20` §A.7；三根滑杆是本项目既有的"颜色"做法）。
            // ⚠️ 描边宽度用 `_tenth_px`（0.1 像素）单位 —— 与 `island.border.width_tenth_px`
            //   同一条理由：SPW 滑杆写 Float，用"像素"作整数单位会被截断成 0。
        SettingDescriptor(
            key = "main_lyric.outline.enabled",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "主歌词描边",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.width_tenth_px",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边宽度（0.1 像素）",
            default = 10,
            range = 0.0..40.0,
            summary = "仅在开启描边时生效。10 = 1.0 像素（描边在字形外侧的可见厚度约为其一半）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.follow_font_weight",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "描边粗细跟随字体粗细",
            /**
             * 用户 2026-09-21：「再加一个**描边粗细与字体粗细联动的开关**，
             * **并提供联动比值**，选择一个观感合适的值作为**中值**。」
             *
             * 开启后，`main_lyric.font.weight`（**加粗**）的变化会按
             * `main_lyric.outline.weight_link_percent` 指定的比例**带动描边宽度**。
             *
             * ⚠️ **默认关闭**（开箱零变化）：这是**新增的联动**，
             * 不该改变现有用户已经调好的观感。想用的人自己开。
             */
            summary = "开启后，字体变粗/变细会按下面的比例带动描边宽度。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.weight_link_percent",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "描边联动比例（%）",
            /**
             * ## 联动公式
             *
             * ```
             * 实际描边宽度 = 基准宽度 × (1 + (字重倍率 - 1) × 联动比例)
             * ```
             *
             * | 联动比例 | 字重 2.0（最粗）时 | 观感 |
             * | --- | --- | --- |
             * | 0% | ×1.00（不变） | 字变粗但描边不动 → 描边被笔画"吃掉"，字看起来发糊 |
             * | **50%（中值）** | **×1.50** | **描边同步变粗但不失控 —— 推荐** |
             * | 100% | ×2.00 | 描边与字重等比 → 字重 2.0 时描边过粗，细笔画会被填死 |
             *
             * ## 为什么中值取 50%（这是"观感合适"的那个值）
             *
             * 描边的职责是**让字在复杂背景上仍可辨认**（本质是给字形加一圈对比色）。
             * 字体变粗时，**笔画本身已经变粗**，描边若 1:1 等比跟随，两者会**同时增厚**，
             * 结果是**字腔（笔画之间的空隙）被填死** —— 尤其小字号下会糊成一团实心块。
             *
             * 而 0%（完全不跟随）又走向另一个极端：字重 2.5 的粗字配一根细描边，
             * 描边相对笔画显得"没存在感"，对比度保护失效。
             *
             * **半跟随**恰好落在两者之间：描边**感得到同步**（相对笔画的比例大致守恒），
             * 又**不会吃掉字腔**。故取 50 为中值 —— 滑杆中点即推荐值。
             */
            summary = "描边跟随字体粗细的程度。0 = 完全不跟随；50 = 半跟随（推荐，中值）；100 = 等比跟随。",
            default = 50,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边色 R",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边色 G",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边色 B",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.sync_with_main",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "副歌词描边跟随主歌词",
            // ⚠️ **本项是 11 个描边设置里最容易漏掉的一个** ——
            //   2026-09-18 我第一次生成这批描述符时，生成器只产出了
            //   "开关 / 宽度 / RGB"（5 项 × 2），**漏了这一个**，
            //   于是注册表 82 项而  写的是 83，探针直接拦下。
            //   而  那边**已经**在读这个键了 —— 若不补，
            //    会因为描述符不存在而恒回退默认值，
            //   表现为"同步开关设了没反应"（一个静默失效）。
            //
            // 语义：开启时副歌词描边**整组**跟随主歌词（含"主歌词没开"→ 也不描边）。
            default = true,
            summary = "开启后副歌词描边完全跟随主歌词；关闭后可用下方各项单独设置。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.enabled",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "副歌词描边",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.width_tenth_px",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边宽度（0.1 像素）",
            default = 10,
            range = 0.0..40.0,
            summary = "仅在开启描边时生效。10 = 1.0 像素（描边在字形外侧的可见厚度约为其一半）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // 用户原话（2026-09-23）：「**允许副歌词的描边跟随字体粗细**」。
            //
            // ## 它补的是哪个缺口（改前必须读这一段，否则会以为它是重复项）
            //
            // 主歌词早有一对联动键：`main_lyric.outline.follow_font_weight`（开关）+
            // `main_lyric.outline.weight_link_percent`（比例）。而**副歌词没有自己的开关** ——
            // 改前 `IslandWindow` 的 `translationOutlineWidthTenthPx` 读的正是
            // **主歌词那个开关**（`main_lyric.outline.follow_font_weight`）。
            // 于是副歌词的跟随与否**完全由主歌词决定**，用户无法单独控制。
            // 本项把这一层**解耦**：副歌词有自己的跟随开关。
            //
            // ⚠️ **联动比例仍复用 `main_lyric.outline.weight_link_percent`**（不另设一项）：
            //   用户这次只要"一个开关"；再造一个同义的百分比滑杆会得到
            //   "两个键描述同一件事、用户不知道该信哪个"（`animation.quality` /
            //   `animation.frame_rate` 那次拆分正是为了**消除**这种重复）。
            //
            // ## 公式（与主歌词**逐字相同**，故两行描边的观感不会割裂）
            //
            // ```
            // 实际描边宽度(px) = 基准宽度(px) × (1 + (字重倍率 − 1) × 联动比例)
            //   字重倍率 = main_lyric.font.weight_scale / 100   （默认 1.0）
            //   联动比例 = main_lyric.outline.weight_link_percent / 100（默认 0.5）
            // ```
            //
            // 实现是**公开纯函数** `render/karaokeOutlineWidthPx(...)` ——
            // 探针可直接喂值断言（含单调性与变异测试），见 `TextAntialiasProbe` §F。
            //
            // ## ★ 默认 `false` 的理由（"默认值要稳"）
            //
            // **不是**因为 `weight_scale` 的默认 100 会让 `f(1.0) = 1`（那只是巧合：
            // 默认 true 时出厂画面确实也逐像素不变）。真正的判据是**升级零变化**：
            //
            // | 若默认 | 对一个"改过字重、且开着副歌词描边"的老用户 |
            // | --- | --- |
            // | `true` | 升级后**副歌词描边突然变粗 / 变细**（`weight_scale ≠ 100` ⇒ `f ≠ 1`） |
            // | **`false`（本项）** | 升级后画面**逐像素不变**，想用的人自己去开 |
            //
            // 且这与同族项的既有取舍一致：`main_lyric.outline.follow_font_weight`、
            // `spectrum.progress_tint`、`cover.rotate`、`spectrum.fade_top` 的
            // **新增联动一律默认关 / 默认保持现状**（只有"修好了某个投诉"的项才敢改默认）。
            // 用户原话里的「**允许**」也是这个语义：给能力，不替用户决定。
            //
            // ## ⚠️ 与 `translation.outline.sync_with_main` 的关系（不是死设置）
            //
            // `sync_with_main = true`（默认）时整组副描边被短路（`translationOutlineFor()`
            // 第一行就 `return mainOutlineFor()`），本项**不生效** —— 这是**正确**的：
            // 那一档的语义就是"完全跟随主歌词"。碰本项会自动把同步开关关掉
            // （见 `SettingCoupling.TRANSLATION_OUTLINE_KEYS`），故用户不会撞上
            // "拨了没反应"（本项目在 `recon/39` 里点名的那类死设置）。
            key = "translation.outline.follow_weight",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "副歌词描边跟随字体粗细",
            summary = "开启后，副歌词描边宽度随「字体粗细」按「描边联动比例」变化；" +
                "关闭时恒用上面的「副歌词描边宽度」。仅在关闭「副歌词描边跟随主歌词」时生效。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边色 R",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边色 G",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边色 B",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.translation.position",
            group = SettingGroups.LYRIC,
            subGroup = "译文",
            kind = SettingKind.LIST,
            title = "副歌词位置",
            // 用户诉求（2026-09-18）：
            // > 「**副歌词和其他行设置一个独立设置项允许设置其在主歌词上方还是下方**。」
            //
            // 默认 `BELOW`（副歌词在下方）—— 与原实现 0.8.9 与改动前的观感一致
            // （`recon/13` §4：原实现把译文画在原文行下方）。**新增的选项不该改变既有观感。**
            //
            // ⚠️ 与「歌词行数」的关系：行数选「单行」时本项**不生效**
            //   （没有副歌词行可摆）。写进 summary 是为了避免"设了没反应"。
            default = "BELOW",
            summary = "仅在「歌词行数」为双行且开启了翻译时生效。",
            options = listOf(
                "BELOW" to "主歌词下方（默认）",
                "ABOVE" to "主歌词上方",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.karaoke.mode",
            group = SettingGroups.LYRIC,
            subGroup = "高亮与同步",
            kind = SettingKind.LIST,
            title = "歌词高亮方式",
            // 用户原话（2026-09-19）：「逐字高亮提供**另一种形式**，
            //   **已读颜色平滑在字间移动**，而不是目前的**真·逐字**。」
            //
            // 两档的几何差别（实现在 `KaraokeTextRenderer.render` 的 `smooth` 参数）：
            //   · `PER_CHAR`（默认，= 改动前）—— 分界点**吸附到字符簇边界**，
            //     一个一个字地亮（`getLogicalHighlightShape(0, charIndex)`）。
            //   · `SMOOTH` —— 分界点**线性扫过整行**（`advance × progress`），
            //     落在字的**内部**，颜色像一条线一样推过去。
            //
            // 默认 `PER_CHAR`：**新增选项不该改变既有观感**（与其它几个 LIST 同取舍）。
            //
            // ⚠️ 有**真实逐字时间轴**（宿主给了 `lyricsCells`）时，本项**不生效** ——
            //    那条路径更准，优先级更高。写进 summary 免得用户"设了没反应"。
            // ── 2026-09-19：扩为**三档**，并**删掉** `main_lyric.karaoke.enabled` ──
            //
            // 用户原话：「关掉逐字高亮后主歌词是整行同色还是一个字一个字变色？
            //   **给三个选项**，**不逐字（整行高亮）/ 平滑高亮 / 真-逐字高亮**。」
            //
            // ## 为什么删掉那个开关
            //
            // 「开关（enabled）× 两档（mode）」能表达 4 种状态，而**真实状态只有 3 种**
            // —— `enabled=false` 与 `mode=LINE` 是同一种观感。
            // **两个设置项表达同一种状态**正是用户反复抱怨的"相冲突的设置项"。
            // 扩成三档后开关没有信息量了，故删。
            //
            // ## 三档的几何差别（实现在 `KaraokeTextRenderer.render`）
            //
            // | 档 | `wordBoundaries` | `smooth` | `progress` | 观感 |
            // | --- | --- | --- | --- | --- |
            // | `LINE` | `null` | `false` | **二值化** | 整行同色（开唱即整行已唱色） |
            // | `SMOOTH` | `null` | `true` | 连续 | 已读色像一条线扫过整行 |
            // | `PER_CHAR` | 有则用 | `false` | 连续 | 一个字一个字亮 |
            //
            // 默认 `PER_CHAR`：与改动前（开关默认 `true`）的观感一致。
            default = "PER_CHAR",
            summary = "三档互斥：整行高亮 / 平滑 / 逐字。有逐字时间轴时「逐字」档以时间轴为准。",
            options = listOf(
                "LINE" to "不逐字（整行高亮）",
                "SMOOTH" to "平滑高亮（已读色在字间移动）",
                "PER_CHAR" to "逐字高亮",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    // ── C 组 · 位置与尺寸（4 项，总表 §1.3 序号 11–14） ───────────────────
    private fun groupC(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "window.position",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.EDITTEXT,
            title = "词岛位置",
            // 默认值语义（总表 §1.3 序号 11 原文）：`null`（= 未拖动过，用默认位置）。
            // 存成空字符串而非字面量 "null"，是为了让"未设置"与"用户输入了文本 null"
            // 在类型层面就是两回事 —— 空字符串即"未拖动过"的哨兵值。
            default = "",
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.max_width",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SEEKBAR,
            title = "最大宽度",
            summary = "开启「固定词岛宽度」时它决定词岛宽度；关闭时它是宽度上限（按歌词长度收缩到此值以内）。",
            default = 640,
            // ⚠️ **原记 `280.0..1200.0`，实为 `280.0..2400.0`**（2026-09-17 放宽）。
            //
            // 用户原话：「**加大允许的歌词框最大长度**」。
            // 1200 对长句不够用 —— 尤其本版实现了 `layout.fixed_width = false`
            // 时**按歌词长度收缩**之后，长句会一路顶到上限而被迫换行/裁切。
            //
            // 2400 的取值：在 2560 宽的屏上留 80 px 边距（词岛居中，两侧各 80）。
            // 小屏用户拖不到那么远，**不会因此变差** —— 上限只是"允许"，不是"默认"。
            //
            // ⚠️ **值域有两个来源，必须同步**（`BUG-024` 的教训）：
            //   ① 本处的 `range` —— 落盘时 `coerceInt` 的 **clamp 区间**；
            //   ② `preference_config.json` 的 `min`/`max` —— 宿主滑块的**可拖区间**。
            //   两者不一致 → **滑块能拖、值被静默夹回**，用户看到的值 ≠ 生效值且**无报错**。
            //   本项目的 json 由 `tools/gen_preference_config.py` **从本文件生成**，
            //   故改这里就够了 —— 但**必须重跑生成脚本**。
            //   `RegistryProbe` §6 会逐项比对两者，改漏了会红。
            range = 280.0..2400.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.fixed_width",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SWITCH,
            title = "固定词岛宽度",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── ★ 2026-09-22（用户第 5 条）：词岛尺寸与字号的耦合改为**两档** ────────────
        //
        // 用户原话：
        // > 「将词岛大小随字体大小变化**剥离**出来，改为**是否跟随**和**手动调整**
        // >   两档选择。允许调整词岛**收起状态下的高度**（**用与所应用行数的歌词行
        // >   总高度的比值**）。」
        //
        // ## 为什么是"开关 + 一个比值滑杆"（照 `cover.scale_with_font` 的先例）
        //
        // 那个键的 KDoc 原文就写着「**若将来真需要独立尺寸，再在 `false` 分支上加一项
        // 即可（那时它才是有意义的）**」—— 用户要的**正是那个 `false` 分支**。
        // 开关 default = `true` = 旧行为 ⇒ **开箱零变化**。
        //
        // ## 比值口径（用户已明确选定 = `base + extra`，见 `islandCollapsedHeightPx`）
        //
        // ⚠️ **键必须落在 `layout.` 或 `main_lyric.font.` 前缀下** ——
        //    `IslandWindow.installSettingsSubscription` 按前缀决定"要不要重算几何"，
        //    落在别的前缀下 = 用户拖了**没反应**且**不报错**（本项目最贵的一类坑）。
        SettingDescriptor(
            key = "layout.island.height.follow_font",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SWITCH,
            title = "词岛高度跟随字号",
            summary = "关闭后可手动指定高度（相对歌词行总高的比值）。",
            // `true` = 旧行为（`歌词块 + 上下各 10px`）—— 与 `cover.scale_with_font` 同一条纪律。
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.island.height.ratio_percent",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SEEKBAR,
            title = "词岛高度比例",
            // ★ 2026-10-01 更正：原句只说了"关闭跟随时生效"，**漏了封面态**——
            //   实测「悬浮窗（封面态）」下本项**恒不生效**，与该开关无关：
            //   `IslandWindow.applyGeometry` 在该形态下走 `coverShapeCollapsedHeightPx`
            //   （高度 = 封面外径 + 2×内边距），**根本不读本键**。
            //   ★ `shape.type` 的 KDoc 亦要求「本态不生效的既有设置各自加 summary 注明」。
            summary = "词岛收起态高度 ÷ 歌词行总高（含译文行）。仅在关闭「词岛高度跟随字号」时生效。" +
                "100% = 恰好等于歌词块。",
            // ★ 2026-10-17：删掉「悬浮窗（封面态）下本项恒不生效」一句 ——
            //   该形态已从设置面撤下；条件从句指向一个选不到的选项 ✓
            // ## 为什么默认 120
            //
            // 100% 的语义是"词岛高**恰好**等于歌词块（上下无内边距）"—— 合法但很紧。
            // 而"跟随字号"档（默认）的高度 = `歌词块 + 20px`，等价比值 =
            // `1 + 20 / 歌词块高`（默认字号 22 / 三行 ≈ **122%**，单行 ≈ 167%）。
            // 取 120 是让用户**切到「不跟随」的那一刻观感基本不变**。
            default = 120,
            // ## 为什么下限是 100 而不是更低
            //
            // 比值基准**就是**歌词块的真实占用 ⇒ 低于 100% 时**必然裁掉歌词**
            // （不是观感取舍，是算术）。下限 100 是**声明在值域里的**，
            // 与"静默把用户输入夹回去"是两回事（本项目反对的是后者）。
            range = 100.0..250.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.content_align",
            group = SettingGroups.LYRIC,
            subGroup = "布局与行数",
            kind = SettingKind.LIST,
            // 2026-09-20 定名修正：原「内容对齐」不贴合 —— 它**只对歌词做水平对齐**
            // （取证：`IslandWindow.kt` 的 `Align` 枚举 KDoc 即"歌词在词岛内的**水平对齐**"；
            //  `IslandPreviewPane.kt` 的 `alignX(left, avail, advance)` 也只作用于歌词行）。
            //
            // ⚠️ 2026-09-22：这里原先写的是**行号**（`IslandWindow.kt:3690` /
            //   `IslandPreviewPane.kt:551`）—— 两个都已漂（现为 9483 / 682）。
            //   行号会随别人编辑那两个文件而过期，过期后把人指到**别的代码**上，
            //   比不写还坏。故本文件里指向**其它文件**的引用一律改成**可 grep 的符号片段**。
            // 封面与频谱**不受本项影响**，故"内容"二字会误导。
            title = "歌词对齐",
            // ⚠️ 默认值 CENTER 属**本表推断**，非输入文档拍板 —— 总表 §11 待确认项 U6
            // 明示"输入文档中未找到出处，属本文推断，请实施时以现有实现行为为准"。
            // 本实现照总表落地，并把这段出处标注在此，便于日后若有拍板就地修正。
            default = "CENTER",
            options = listOf("LEFT" to "左对齐", "CENTER" to "居中", "RIGHT" to "右对齐"),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.lines",
            group = SettingGroups.LYRIC,
            subGroup = "布局与行数",
            kind = SettingKind.LIST,
            title = "歌词行数",
            // ── 用户 2026-09-18 诉求 ────────────────────────────────────────
            //
            // 原话：「**取消多行歌词设置**，只保留选择**单行（主歌词）**、
            //   **双行（主+副）**的设置，**取消已唱歌词的显示**。」
            //
            // ## 本项取代了两项（ + ）
            //
            // 那两项的组合语义是「总闸 × 三选模式」，而实际效果只有两种
            // （2026-09-17 起未唱行一律不显示 → 三选里有两个内容相同），
            // 于是**两个设置项只表达了两种状态** —— 正是用户说的
            // 「相冲突的设置选项给用户造成困扰」。合并成一项后
            // **选项数 = 真实状态数**。
            //
            // ## 已唱歌词的去向
            //
            // 旧  /  会显示刚唱完的那句。
            // 用户明确要求取消 → 新模型里**没有**这个状态，
            // 于是「已唱歌词」作为一个**可显示的行**被彻底移除。
            // （逐字高亮里的已唱部分是另一回事，它仍在 —— 那是同一行内的进度染色。）
            //
            // ## 与  的关系
            //
            // 选「双行」时**第二行是副歌词（译文）**，而译文本身还有一个开关
            // （，原实现键，保留）。
            // 即： +  = 实际一行。这个组合是**有意的**
            // （用户可能想临时关掉译文而不用改行数），但必须在两边的 summary 里写明，
            // 否则就是又一个设了没反应。
            default = "SINGLE",
            summary = "双行时第二行为副歌词，仍受「显示译文行」开关控制。",
            options = listOf(
                "SINGLE" to "单行（仅主歌词）",
                "DUAL" to "双行（主歌词 + 副歌词）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "layout.line_gap_px",
            group = SettingGroups.LYRIC,
            subGroup = "布局与行数",
            kind = SettingKind.SEEKBAR,
            title = "歌词行间距",
            // 用户原话：「一二行歌词间距调整选项也没做。」
            //
            // 语义：**额外**加到行高上的间距（px）。0 = 只保留行高本身
            // （`LINE_HEIGHT_RATIO` 已含舒适的行间留白 —— **只写常量名、不抄数值**，抄了会在常量改动后静默漂移），
            // 调大则两行拉得更开。做成"额外量"而不是"总行高"的理由：
            // 总行高会与字号耦合 —— 用户改字号时间距会被动变化，
            // 而"行间距"在直觉上是独立于字号的一项。
            default = 0,
            range = 0.0..80.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    // ── D 组 · 外观形态与材质（3 项，总表 §1.4 序号 15–17） ───────────────
    private fun groupD(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "shape.type",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.LIST,
            title = "外观形态",
            // 4 型 vs 原实现 2 型属**扩展对应**（总表 §1.4 序号 15）。
            // ⚠️ 总表 §11 U4：首版"只需保证 SQUARE 正确"，余下 3 型随 P2 形状系统补齐 ——
            // 即**接口先行**：这里给全 4 个选项，但渲染层暂不保证 4 型都好看。
            default = "SQUARE",
            options = listOf(
                "SQUARE" to "方形",
                "ROUNDED_RECT" to "圆角矩形",
                "PILL" to "胶囊",
                // ★ 2026-10-17 用户指令：**删掉刘海形态设置**（选项撤下，渲染代码保留）
                // ── 2026-09-30 · 第 5 型「悬浮窗」= 封面态（`recon/67` 规格，v8 决策全闭合）──
                //
                // ## 语义（用户 2026-09-29 00:1x 逐字，recon/67 §0.2）
                //   词岛缩到封面大小、只画封面（壳上"水位光感"表示进度、封面里放时间）；
                //   鼠标命中封面 ⇒ **向右生长**成一个"完整词岛"（封面 + 播放控件 + 曲名·作者，
                //   **不显示歌词**）；鼠标移开 ⇒ 收回封面。
                //
                // ## ★★ 为什么用 `COVER_ONLY` 这个 wire 值（recon/67 §9-C 定案）
                //   它**描述内容**（"只有封面"），而其它候选 `FLOATING` 易与
                //   "悬浮 / 置顶"（`island.always_on_top`）混淆 —— 两者是**无关**的概念。
                //
                // ## ★★ 加取值时**必须同时改**的 3 处（漏掉都不报错，只是静默失效）
                //   ① [IslandPolicy.islandEdgeRadiusPx] 的 `when (shapeType)` —— **带 `else`**；
                //   ② [IslandPolicy.expandedOutlineRadii] 的 `when (shapeType)` —— **也带 `else`**；
                //   ③ `tools/gen_preference_config.py` 重跑（**不手改 JSON**）+ 同步 4 张计数表。
                //   ⇒ ① ② 漏掉的后果：新值落 `else` ⇒ **画成普通圆角矩形**，
                //      用户表现为"**选了没反应**"（`recon/67` §5 ②③ 列为**最可能踩的坑**）。
                //   ⇒ 探针 M2 专门钉这一条（新值必须返回"本态自己的半径"，不是 `else` 的）。
                //
                // ## ⚠️ 本态**不生效**的既有设置（各自加 summary 注明，见 recon/67 §4）
                //   `layout.island.height.ratio_percent`（本态无"歌词块"这个基准）·
                //   `controls.progress_style`（进度改用壳上水位表示）。
                //   且本态**强制视为开启**两项（**绝不改键值**）：`island.hit.cover` · `spectrum.enabled`。
                // ★ 2026-10-17 用户指令：**隐去「词岛外观-悬浮窗」设置项**
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "shape.corner_radius",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "圆角大小 %",
            // ★ 2026-10-01 更正：原写「**仅在形态为「圆角矩形」时生效**」——
            //   **枚举漏了第 5 型** `COVER_ONLY`（悬浮窗（封面态））。
            //   实测：`IslandPolicy.islandEdgeRadiusPx` 的 `COVER_ONLY` **落 `else`**
            //   ⇒ 与 `ROUNDED_RECT` 同款，**四角都取本项的用户圆角**。
            //   （2026-10-01 的封面态返工把本态从"上圆下直"改回"四角同用户圆角"后，原句**更错**了。）
            summary = "仅在形态为「圆角矩形」时生效；选「方形」「胶囊」时本项被忽略。",
            // ★ 2026-10-17：原句是「仅在圆角矩形与悬浮窗（封面态）时生效；选方形/胶囊/刘海
            //   时被忽略」—— ★ **两个选项都已撤下**（悬浮窗见 `cover.lock` 的 KDoc；
            //   刘海 = 同日更早的「删掉刘海形态设置」）⇒ 只留当前真实存在的三型 ✓
            default = 60,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-25 · 第 2 期「材质」新增 8 键（波次 0–2）──────────────────
        //
        // 来源：`spw-materials-survey/F-材质外观落地施工规格-2026-09-22.md` §2.3（6 键总表），
        // 外加**两处用户拍板**的偏离：
        //   ① `shape.corner_n` —— F §2.3 建议「n 固定 4.0、**不暴露**」，
        //      用户 2026-09-25 明确改为**暴露为设置项**。
        //   ② `material.fill.color_end` —— F §2.2 建议「止色由 `gradient_tint_percent`
        //      **派生**」，用户 2026-09-25 明确改为**显式 COLOR 键**。
        //      ⚠️ 这一条是**主动踩进** F §1.3 的「**断点 A**」：`kind = COLOR` 的键
        //      在 SPW 设置页上**没有任何控件能承载它**，后果不是报错而是
        //      **该项彻底看不见**。故必须同批完成那套「**3 处 + 1 按钮**」配方 ——
        //      见 [COLOR_GROUPS] / [SELF_DRAWN_KEYS] / `tools/gen_preference_config.py`
        //      的 `BUTTONS` + `BUTTON_ANCHOR`，以及
        //      `host/CdlPlugin.pickGradientEndColor`。**少一处就静默失效。**
        //
        // ## 8 键的公共约束（都是刻意的）
        //
        // - **全部 `P1`、全部落 `ISLAND`**（细类见各自 descriptor）。
        // - **全部默认"零视觉变化"**：`ARC` / `NONE` / `0` / `0` 与改动前逐像素一致。
        //   `material.fill.color_end` 的默认色**不参与**这个保证 —— 它只在
        //   `gradient_style != NONE` 时才被读到，而那默认是 `NONE`。
        // - **"开关"用 `0` 表达，不另设 SWITCH** —— 沿用 `spectrum.height_pct` 的既有惯例，
        //   少 3 个键、少 3 次"开关与数值不同步"的状态。
        SettingDescriptor(
            key = "shape.corner_style",
            // ★ 2026-10-18（**用户审核文案后**）：本项**不再出现在任何设置面** ——
            //   用户审核后从可见文案中删除。descriptor 保留 ⇒ 消费点仍能读到默认值/旧值 ✓
            retired = true,
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.LIST,
            title = "角曲率风格",
            summary = "「圆弧角」= 现有行为；「连续曲率」= 超椭圆（squircle）角。",
            // `ARC` = 现状（`RoundRectangle2D`）⇒ 开箱**逐像素不变**。
            // 未知值回退 `ARC`（与项目 `text(key, 默认)` 的既有取舍一致）。
            default = "ARC",
            options = listOf(
                "ARC" to "圆弧角",
                "SQUIRCLE" to "连续曲率",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "shape.corner_n",
            // ★ 2026-10-18（**用户审核文案后**）：本项**不再出现在任何设置面** ——
            //   用户审核后从可见文案中删除。descriptor 保留 ⇒ 消费点仍能读到默认值/旧值 ✓
            retired = true,
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "连续曲率指数 n",
            summary = "仅在角曲率风格为「连续曲率」时生效。4 = 经典 squircle；越大角越方。",
            // ⚠️ 与 F §2.3 的「不暴露」相反 —— **用户 2026-09-25 明确要求暴露**。
            // 记在这里是为了让下一个读者知道这是**产品决定**，不是漏读规格。
            default = 4,
            range = 2.0..10.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── ★ 2026-10-08（U12b）：`background.*` 4 条**从「身后窗口」之后搬到这里** ──
        //
        // 目的：让 `shape.*`（形态/圆角/角曲率）与 `background.*`（不透明度/背景色/旋转角/
        //      颜色来源）在**物理声明序**上相邻（它们同属「形状与背景」细类）。
        //
        // ⚠️ ★★ **本次搬运对用户可见效果 = 0** —— 因为 `SettingRegistry.all`（`:4265`）
        //    用 `sortedBy { subOrder() }` 排序，**稳定排序**本来就把同名细类聚成一块。
        //    ★ 保留这次搬运的**唯一理由是"可读性"**：避免下一个读者扫物理序时
        //      误以为"这个细类劈成了两段、菜单会渲染两个同名段"（我在 U12b 就误判过一次，
        //      详见本节开头的 `SUBS` 长 KDoc 与 §H34 / 纪律 88）。
        //
        // ★ 纪律 88 自查句：「我这个数字，是从**渲染代码读的那个变量**算出来的吗？」
        SettingDescriptor(
            key = "background.opacity",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "背景不透明度 %",
            default = 96,
            // 2026-09-16 用户要求「透明度范围放宽」：下界 35 → 0。
            // 原先 35 是**素材观感**的保守下界（再低就看不清歌词底衬），
            // 但它同时挡住了两个正当用法：① 全透明背景（只留字与频谱，做成悬浮字）；
            // ② 与「跟随封面取色」组合出半透玻璃。用户要的是**能调到任意值**，
            // 观感的责任交回用户。上界 100 本就是自然上限（不透明度 100%），不动。
            //
            // ⚠️ 0 表示**完全不透明**的反面：本项是"不透明度"，0 = 背景全透明。
            // 渲染层对 0 的处理（不画背景）已存在（`island.enabled` 与封面/频谱是独立
            // 图层），故这里不需要为 0 新增分支。
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        // ── 2026-09-22 新增 2 项（用户点名 + 用户新给的规格）──────────────────
        //
        // ① `background.color`（COLOR）——
        //    用户原话：「**有些选项提供自选色但是并没有提供选色入口，如背景设色**」。
        //    真相**比"缺入口"更严重**：`background.*` 此前**只有 `opacity` 与
        //    `color_mode` 两项，没有任何颜色键** —— `manual` 档用的是**写死的常量**
        //    `IslandPanel.BASE_BACKGROUND`（`#14161A`），用户**没有键可改**。
        //
        //    这与 `spectrum.color` 是**同一个问题的同一个解法**（2026-09-17 已修过一遍，
        //    见 `Appearance.spectrumColorFor` 的 KDoc）：
        //    **补一个 COLOR 键，默认值 = 旧硬编码常量（逐位相同）** ⇒
        //    开箱观感零变化，只是从"用户改不了"变成"能改"。
        //
        // ② `background.cover.angle_deg`（SEEKBAR 0..360）——
        //    用户新给的规格：「**歌曲封面作为封面底色**……**允许用户调整封面角度
        //    （0-360 度）**，**旋转后封面自动缩放铺满**词岛背景，**不再设调整设置**。」
        //    ⚠️ **刻意不提供"缩放"项** —— 缩放是**算出来**的（`coverFillScale`）。
        SettingDescriptor(
            key = "background.color",
            // ★ 2026-10-18：**不标 `retired`** —— 它本来就在 `SELF_DRAWN_KEYS` 里
            //   （不上 SPW 设置页，由取色按钮写入）⇒ 再标 retired 会被**重复计算**
            //   （`149 − 27 − retired` 里减两次）✗
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.COLOR,
            title = "背景颜色",
            // 默认 `#14161A` —— 与旧硬编码常量 `IslandPanel.BASE_BACKGROUND` **逐位相同**，
            // 故本项接线后**开箱逐像素不变**。
            //
            // ⚠️ **这里只能写字面量**（不能写 `dev.cdl.window.BACKGROUND_BASE_RGB`）：
            //    `tools/gen_preference_config.py` 的 `parse_default` 只认字面量 /
            //    `0x…toInt()` / 字符串 / 布尔 —— 跨文件 Int 常量**认不出来**，
            //    会把裸标识符原样写进 JSON（宿主读不懂的默认值）。
            //    ⇒ "不漂移"由 **`RegistryProbe` §8b.1** 保证：它断言
            //      `descriptor("background.color").default == BACKGROUND_BASE_RGB`，
            //      而 `BACKGROUND_BASE_RGB` 由那三个通道常量派生（**单一可读来源**）。
            //      改一处漏一处 → 探针当场红。
            // ★★★ 2026-10-07 **更正（`migration/98` · C-3）**：本段原写「由
            //    **`IslandPolicyProbe`** 保证」—— ★ **引错了探针**：该探针里
            //    `BACKGROUND_BASE_RGB` **0 命中**；真实护栏是 `RegistryProbe §8b.1`
            //    （`check("8b.1 ★ 默认值 = 旧硬编码底色…")`）。
            default = 0xFF14161A.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,
            // ⚠️ `summary` 在 `edittext` 上有**功能作用**（宿主弹输入框时标题取
            //    `summary ?: key`，没有它用户会看到**裸键名**，见 BUG-009 的定案）。
            //    本项与 `spectrum.color` 一样降级为 `edittext` 承载（宿主无颜色控件）。
            summary = "背景颜色（背景颜色来源选「手动指定」时生效）",
        ),

        SettingDescriptor(
            key = "background.cover.angle_deg",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "封面图旋转角度",
            summary = "仅在「背景颜色来源」选「封面图」时生效。旋转后自动重新等比缩放铺满词岛，无需另设缩放。",
            // 默认 `0` = 不旋转 —— 与"没开这一档"时逐像素一致，开箱零变化。
            default = 0,
            range = 0.0..360.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "background.color_mode",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.LIST,
            title = "背景颜色来源",
            // 默认 `manual` 保持不变 ⇒ **开箱逐像素不变**（新增的两档只在用户主动选时才生效）。
            default = "manual",
            options = listOf(
                "manual" to "手动指定",
                "cover" to "跟随封面取色",
                // ⚠️ 2026-09-22 新增第三档（用户新给的规格：「**歌曲封面作为封面底色**……
                //   **旋转后封面自动缩放铺满**」）。
                //
                // ⚠️ 这个 wire 值**必须逐字等于** `dev.cdl.window.BACKGROUND_MODE_COVER_IMAGE`。
                //   这里只能写字面量（生成脚本的 `parse_options` 只认裸标识符/字面量，
                //    写成全限定名会把**整对选项静默丢掉** ⇒ 设置页少一档，用户选不到）。
                //    ⇒ 由 `IslandPolicyProbe` 把两者钉在一起（同 `cover.shape` 的做法）。
                "cover_image" to "封面图",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "material.highlight.strength",
            // ★ 2026-10-18（**用户审核文案后**）：本项**不再出现在任何设置面** ——
            //   用户审核后从可见文案中删除。descriptor 保留 ⇒ 消费点仍能读到默认值/旧值 ✓
            retired = true,
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "顶部高光强度",
            summary = "从顶部向下淡出的白色高光（玻璃厚度感）。0 = 关闭。",
            // `0` ⇒ 该层**不被绘制**（不是"画了但全透明"）⇒ 开箱零变化。
            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            // ★ **P1-6 误判修正**（★ 由用户审计 `_遗漏项检查-液态玻璃下架-2026-10-11.md` §5 抓出）：
            // 我原判它是「**专有 + 消费点 = 0（死键）**」⇒ ★ **两条都错**：
            // · ★ 它**有完整消费链**：`IslandAppearance` 读 → `PreviewSnapshot` → `IslandPreviewPane` → `MaterialLayers.paint` ✓
            // · ★ 它是 `paint` 的**第一层（顶部高光）**，**对所有档生效**（不只玻璃）✓
            // ⇒ ★ **恢复**：它是**通用外观功能**，与液态玻璃无关 ✓
        ),
        SettingDescriptor(
            key = "material.fill.gradient_style",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.LIST,
            title = "背景渐变",
            summary = "「关闭」= 现有纯色背景；「线性」= 沿指定角度的线性渐变；"
                + "「封面多色流光」= 从封面取**多个**颜色做多停靠点渐变（颜色随每首歌变）。",
            // 用 LIST（而不是 SWITCH + style）沿用 `background.color_mode` 的既有做法：
            // 避免"开关关了但 style 还留着"这种两个键互相矛盾的状态。
            default = "NONE",
            options = listOf(
                "NONE" to "关闭",
                "LINEAR" to "线性",
                // ★★ 2026-09-29（用户指令「开 ④a」）：新增**档**而非改现有观感 ⇒
                //    **默认值仍是 `NONE` ⇒ 开箱零变化**（本项目「默认值要稳」纪律）。
                // ★★★ 2026-10-11（**U12 全面清理**）：★ **`LIQUID` 档已下架**（用户指令「与玻璃相关的都下」）。
            //   ★ 原档位 = 「液态玻璃（边缘亮环 + 内部柔化）」—— ★ 它是**玻璃的填充形态** ✓
            //   ⚠️ 用户配置里若仍是 `LIQUID` ⇒ `coerce` 会**回退到默认档**（`NONE`）✓
                // ★★ 2026-10-03（⑰「封面多色流光」第 2 步 · **F3**）：新增**档**而非改现有观感 ⇒
                //    **默认值仍是 `NONE` ⇒ 开箱零变化**（与 `LIQUID` 同一条纪律）。
                //    停靠点来自封面取色（`CoverColorExtractor.palette`），
                //    经 `render/FlowGradient.kt` 构造（**唯一构造点**）。
                //
                // ## ★★ 为什么**复用本键**而不是新开 `material.fill.flow_style`
                //
                //   ① 用户 2026-09-30 自定的纪律逐字：「**已有同形态的设置项**（比如词岛背景，
                //      封面样式等等）**不再设立独特的调整选项，而是使用通用控制项**」
                //      —— 本键正是"背景渐变"这个**同形态**设置项。
                //   ② ★ **加档不动任何计数表**：`check_derived_counts` 数的是**键**
                //      （136 / 26 / 110），而 `all.size == EXPECTED_TOTAL` 也只看键
                //      ⇒ **加档零计数表churn**（加键要同步 4 张表 + 重跑生成脚本）。
                //   ③ ★ **代价如实记录**：**速度暂时写死**（`FLOW_PERIOD_MS` 常量）。
                //      若你要可调，那才需要加键（`material.fill.flow_speed`），
                //      届时按台账 §3.15′ F3 的清单走（4 张表 + `gen_preference_config.py`）。
                "FLOW" to "封面多色流光",
                // ★★★ 2026-10-14（**用户报障纠正** · ⑱ 的**真正落点**）：
                //
                // > 用户逐字：「**你完全理解错了，碎色流光是提供给词岛外观的。**」
                //
                // ★★ **我原先把碎色做在了 `spectrum.color_mode`（`cover_camo`）上，对象错了** ——
                //    用户说的「多色流光」是**岛体背景**这条（`FLOW` 档就是它），
                //    描述「呈现是竖色条水平向右」正是**沿岛宽铺的多停靠点渐变在向右推进**；
                //    频谱柱的颜色走向只是我把"竖色条"错听成了频谱。
                //
                // ## ★ 为什么加在**本键**（`gradient_style`）而不是新开一键
                //
                //   沿用上面 ① 的同一条用户纪律（2026-09-30 自定）：
                //   「**已有同形态的设置项（比如词岛背景、封面样式）不再设立独特的调整选项，
                //     而是使用通用控制项**」—— 本键正是"背景渐变"这个同形态设置项 ⇒
                //   碎色只是 `FLOW` 的**同族新档**（等距停靠点 ⇄ 不等宽碎块平台）。
                //   ② 加档不动任何计数表（数的是**键**）⇒ 与 `FLOW` 当年零 churn。
                //
                // ## ★ 观感（与 FLOW 的差别）
                //
                //   `FLOW` = 等距 N 停靠点 ⇒ 软色带；`CAMO` = **不等宽碎色块平台** + 块间一小段斜坡
                //   ⇒ 迷彩碎块感。块数取 `SpectrumFlowColor.CAMO_BLOCK_COUNT`（**与频谱侧同源**）。
                //   ★ 走向仍是**水平向右**（`gradientAngleDeg`，默认由用户已设角度决定）。
                //
                // ## ⚠️ 三条必须同步（漏一条 ⇒ "选了没反应"）
                //
                //   ① [MaterialLayers.paint] 的绘制分支要接 `flowCamoPaint`；
                //   ② `IslandWindow.coverPaletteFor` 的 `needFlow` 要含本档（否则拿不到封面调色板）；
                //   ③ `check_deploy_freshness` / 计数表**不受影响**（加档不加键）✓
                "CAMO" to "封面迷彩碎色流光",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ★★★ 2026-10-16（**用户要求**）：「**加入对流光流速的流速调整设置**」
        //
        // ★ 这一键是**早就登记在案的欠账** —— `gradient_style` 的 KDoc 当时就写明：
        //   > 「★ **代价如实记录**：**速度暂时写死**（`FLOW_PERIOD_MS` 常量）。
        //   >   若你要可调，那才需要加键（`material.fill.flow_speed`）」
        //   ⇒ 本轮按那条既定计划落地，键名沿用。
        //
        // ## 语义：**百分比，100 = 原速**（★ 所以开箱逐像素不变）
        //
        //   周期 = 基准周期 × 100 / 流速%  ⇒ 数**越大越快**（与"流速"这个词一致）
        //   · 10%  ⇒ 10 倍慢（背景 60 s 一个周期）
        //   · 100% ⇒ **原速**（背景 6 s，与改动前**逐帧相同**）✓
        //   · 300% ⇒ 3 倍快（背景 2 s）
        //
        // ## ★★ 它同时管**背景**与**频谱**两条流光（这是有意为之）
        //
        //   工程里有两个基准周期：`FLOW_PERIOD_MS`（背景 6 s）与
        //   `SPECTRUM_FLOW_PERIOD_MS`（频谱 18 s，**BUG-172** 定为 3 倍慢 ——
        //   频谱带比整岛背景短一个量级，相位是相对量 ⇒ 同样的相位速度看起来更快）。
        //   ★ 本键**按同一比例缩放两者** ⇒ ★★ **3× 的快慢关系保持不变** ✓
        //   （若只缩放一条，那条 3× 关系就被这个键悄悄改掉了 ✗）
        //   ★ 这也是用户 2026-09-30 自定的纪律：「**已有同形态的设置项不再设立独特的
        //     调整选项，而是使用通用控制项**」—— 「流光流速」正是那个通用项 ✓
        //
        // ## 为什么下限取 10 而不是 0
        //
        //   ★ `0` 会让"周期 = 基准 × 100 / 0"**除零** ⇒ 必须排除；
        //   ★ 取 10（= 10 倍慢）而不是 1：1% 意味着背景 600 s 一个周期，
        //     用户看到的将是**静止** —— 那与"流光坏了"无法区分（本项目最忌讳的形态）。
        SettingDescriptor(
            key = "material.fill.flow_speed",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "流光流速 %",
            summary = "「封面多色流光 / 封面迷彩碎色流光」的流动速度。100 = 原速；" +
                "越大越快（背景与频谱两条流光的周期**按同一比例**缩放，3× 的快慢关系不变）。",
            default = 100,
            range = 10.0..300.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "material.fill.gradient_tint_percent",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "渐变强度 %",
            summary = "起色向止色混合的比例。0 = 不画渐变（走纯色路径）。",
            // ⚠️ 语义**已按用户 2026-09-25 的选择调整**：F §2.3 原定义是
            //   「止色相对起色的**明度偏移**百分比」——那是"止色派生"方案下的说法。
            //   既然止色改为**显式 COLOR 键**，本项改为**混合比例**（0 = 不画），
            //   否则两个键会争夺"止色到底是什么"。`0` 仍保留"不画渐变"的语义。
            default = 30,
            range = 0.0..60.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "material.fill.gradient_angle_deg",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "渐变角度 °",
            // ★ 角度定义**必须**写进 KDoc，否则下一个人会改反：
            //   `0°` = 从左到右，`90°` = 从上到下，**顺时针**。
            summary = "0° = 从左到右，90° = 从上到下（顺时针）。",
            // ★ 2026-09-25 用户拍板：默认 `90`（**从上到下**）。
            //   原照抄 `F §2.3` 写的是 `180`（= 从右到左），用户确认那处是规格笔误。
            //   判据：`gradient_style` 默认 `NONE` ⇒ 本键不参与绘制 ⇒ **开箱仍零变化**；
            //   用户一旦切到「线性」，看到的会是"上暗下亮/上亮下暗"这种最常见的背景渐变。
            default = 90,
            range = 0.0..360.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "material.fill.color_end",
            // ★ 2026-10-18：**不标 `retired`** —— 它本来就在 `SELF_DRAWN_KEYS` 里
            //   （不上 SPW 设置页，由取色按钮写入）⇒ 再标 retired 会被**重复计算**
            //   （`149 − 27 − retired` 里减两次）✗
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.COLOR,
            title = "渐变止色",
            // ⚠️ `summary` 在 `edittext` 上有**功能作用**：宿主弹输入框时标题取
            //   `summary ?: key`，没有它用户会看到**裸键名**（BUG-009 的定案）。
            summary = "渐变的另一端颜色。仅在「背景渐变」开启后生效。",
            // 默认取比 `background.color`（`0xFF14161A`）**亮一档**的值：
            // 用户一旦把「背景渐变」切到「线性」就**立刻看得见**效果；
            // 而「背景渐变」默认 `NONE` ⇒ 本键不参与任何绘制 ⇒ **开箱仍零变化**。
            //
            // ⚠️ **这里只能写字面量**：`gen_preference_config.py` 的 `parse_default`
            //   只认字面量 / `0x…toInt()` / 字符串 / 布尔，跨文件 Int 常量认不出来。
            default = 0xFF2A2E38.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,
        ),
        // ══════════════════════════════════════════════════════════════════════
        // ★★★ 「液态玻璃」子组（2026-10-01，用户指令：**新建子组** + **照截图的参数名与值**）
        //
        // ## 只暴露"我们能做、且截图里有"的
        //
        // 用户提供了一份 Android 液态玻璃参数面板（9 滑块 + 3 开关）。逐条核对后：
        //   · ★ **能做**：圆角 / 表面蒙版 / **高光强度** / 投影强度 / 立体感
        //   · ❌ **做不到**（**需要读背景** ⇒ 属 ④b）：模糊 / 折射厚度 / 折射量 / 色散 / 饱和度
        //   · ★ **剔除**（用户指令）：背景流动
        //
        // ⇒ ★ 本子组只放**能做**的；★ 且**不重复既有键**：
        //   · 「圆角」**复用** `shape.corner_radius`（那是**形状**的属性，已在「形状」组）——
        //     ★ 液态玻璃的逐像素渲染**自动跟随它**（`paint` 从 `shape.bounds2D` 取尺寸）。
        //   · 「投影强度」「表面蒙版」**第一批未实现**（它们**不属逐像素着色** ⇒ 是 `Graphics2D` 的层，
        //     落地方式与「高光强度 / 立体感」不同）⇒ ★ **第二批（同日）已补齐**，见下面两个 descriptor。
        //
        // ## ★ 落地顺序（两批，不是一次做完的）
        //
        // | 批 | 键 | 落地位置 |
        // |---|---|---|
        // | 第一批 | `glare_strength` / `depth_effect` | `MaterialLayers.liquidGlassImage`（**逐像素**） |
        // | 第二批 | `surface_alpha` / `shadow_strength` | `MaterialLayers.drawSurfaceAlpha` / `drawDropShadow`（**`Graphics2D` 的层**） |
        //
        // ★ 第二批两项的**值**都经 `_diag_spw/SurfaceShadowProbe.java` 实测复核后才定为默认值 ——
        //   面板给的 `0.10 / 0.60` **不能直接假定**"大众观感合适"，要量。
        //
        // ## ★ 不暴露的参数（值都在 `MaterialLayers.liquidGlassImage` 里，**每个都有出处**）
        //
        // 见那里的 KDoc：`fresnelRange/glareRange = 64`（**推导**）· 其余取 `LiquidGlassWinUI` 默认值。
        // ══════════════════════════════════════════════════════════════════════
        SettingDescriptor(
            key = "material.liquid.glare_strength",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "液态玻璃 · 高光强度",
            /**
             * ★★ 照**用户提供的 Android 参数面板**的「**高光强度 0.60**」（用户指令：用它的值）。
             *
             * ## 为什么需要它
             *
             * 逐像素玻璃渲染里，Glare（双向镜面高光）的强度原本**硬编码 1.0**
             * ⇒ 实测**边缘过曝**，观感"**糊**"（像发光而不是玻璃）。
             * ★ 改成面板的 **0.60** 后观感才对（`migration/68-*` 的对照图）。
             *
             * ## 值域与换算
             *
             * `0..100`（百分比）⇒ 内部 `glareFactor = 本值 / 100`。
             * ★ 默认 **60** = 面板的 0.60 ⇒ ★ **开箱观感 = 面板那一档**。
 * ★★ 2026-10-05 **用户拍板改默认值**：原记「60」⇒ ★ **现改为 `0`** ——
 *   **理由**：原来那组"面板照抄值"会让**开箱就变样**（即使从没用过液态玻璃，词岛也会多出"一层极淡白 + 一圈投影"，台账 R18 早就预告）⇒ ★ **默认归零 = 开箱零变化**；★ **推荐值已存成内置预设「液态玻璃（推荐）」**（`PresetStore.BUILT_IN`），想要一键切 ✓
             */
            summary = "液态玻璃的高光（Glare）强度。100 = 最强；建议 40–70，过高会过曝发糊。",
            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.liquid.depth_effect",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SWITCH,
            title = "液态玻璃 · 立体感",
            /**
             * ★★ 照**用户提供的参数面板**的「**立体感 depthEffect**」（开关，默认 ✅）。
             *
             * ## 它是什么
             *
             * 逐像素玻璃渲染的**菲涅耳边缘光**那一层 —— 它让面板看起来"**有厚度**"
             * （掠射角更亮 ⇒ 像一块有体积的玻璃，而不是一张贴纸）。
             *
             * ★ **关掉** ⇒ 只剩 Glare 高光 ⇒ 更"平"、更接近普通半透明面板。
             * ★ **开着**（默认）⇒ 有边缘厚度感 ⇒ 更像玻璃。
             *
             * ⚠️ **默认 `true`** 与面板一致；★ 且它**不改变开箱观感**（因为逐像素实现
 * ★★ 2026-10-05 **用户拍板改默认值**：原记「true」⇒ ★ **现改为 `false`** ——
 *   **理由**：原来那组"面板照抄值"会让**开箱就变样**（即使从没用过液态玻璃，词岛也会多出"一层极淡白 + 一圈投影"，台账 R18 早就预告）⇒ ★ **默认归零 = 开箱零变化**；★ **推荐值已存成内置预设「液态玻璃（推荐）」**（`PresetStore.BUILT_IN`），想要一键切 ✓
             *   本来就把菲涅耳算在里面了 ⇒ 本键的默认值 = 现有行为）。
             */
            summary = "开启后玻璃有边缘厚度感（菲涅耳边缘光）；关闭则只剩高光，更扁平。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        // ── 2026-10-01 第二批（用户指令：「**两批都做**」）────────────────────
        //
        // 面板里还有两项**能做、且截图有值**的，第一批没做（它们**不属逐像素着色**，
        // 而是 `Graphics2D` 的层 ⇒ 与第一批的落地方式不同，故分两批）：
        //   · **表面蒙版** `alpha = 0.10`   → 叠在玻璃之上的一层极淡白
        //   · **投影强度** `0.60`            → 画在岛体**之前**的柔和阴影
        //
        // ★ 两者的取值**都来自 `_diag_spw/SurfaceShadowProbe.java` 的实测**，
        //   不是把面板的 0.10 / 0.60 直接抄过来当"观感合适" ——
        //   用户要求「不开放给用户的设置项需取大众观感合适的值（**不可猜测臆断**）」，
        //   而这两项**开放给用户**，故取面板值；但**面板值是否真的合适**仍要实测
        //   （万一 0.10 就过曝呢？实测 57.3 / 过曝阈值 200 ⇒ 合适）。
        SettingDescriptor(
            key = "material.liquid.surface_alpha",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "液态玻璃 · 表面蒙版",
            /**
             * ★★ 照**用户提供的 Android 参数面板**的「**表面蒙版 `alpha` = 0.10**」。
             *
             * ## 它是什么
             *
             * 在玻璃的**边缘光之外**，再铺一层**覆盖整个形状内部**的**极淡白** ——
             * 让"玻璃面"看起来更**实**（像一块实体玻璃，而不是空心的发光边框）。
             *
             * ## 值域与换算
             *
             * `0..100`（百分比）⇒ 内部 `AlphaComposite` 的分数 = 本值 / 100。
             * ★ 默认 **10** = 面板的 `0.10` ⇒ **开箱观感 = 面板那一档**。
 * ★★ 2026-10-05 **用户拍板改默认值**：原记「10」⇒ ★ **现改为 `0`** ——
 *   **理由**：原来那组"面板照抄值"会让**开箱就变样**（即使从没用过液态玻璃，词岛也会多出"一层极淡白 + 一圈投影"，台账 R18 早就预告）⇒ ★ **默认归零 = 开箱零变化**；★ **推荐值已存成内置预设「液态玻璃（推荐）」**（`PresetStore.BUILT_IN`），想要一键切 ✓
             *
             * ## ★ 为什么不用 `0..255`（那是 `Color` 的 alpha 量纲）
             *
             * 面板给的是**分数** `0.10`；换成 `0..255` 要乘 255 再取整 ⇒
             * **101 档会被压成 ~26 个可区分档**（"滑杆拖了没反应"）。
             * ★ 这正是用户点名的「滑杆调节**不诚实**」那类缺陷 —— 用百分比天然规避。
             *
             * ## 实测（`SurfaceShadowProbe` 实验 A）
             *
             * 中心亮度：无蒙版 34.7 → `0.10` 时 **57.3（+65.4%）**，过曝阈值取 200
             * ⇒ **远未过曝**，面板值可直接用。
             *
             * ## 与画质档的关系
             *
             * 它是 [MaterialLayers.paint] 的**第四层** ⇒ 与高光/渐变/噪点**同一条**
             * `quality == "OFF"` 早退路径（结构上不可能漏掉）。
             */
            summary = "玻璃表面再铺一层极淡白，让面板更「实」。100 = 最实；建议 5–20，过高会发灰。",
            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.liquid.shadow_strength",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "液态玻璃 · 投影强度",
            /**
             * ★★ 照**用户提供的 Android 参数面板**的「**投影强度 `0.60`**」。
             *
             * ## 它是什么
             *
             * 词岛**下方**的一层柔和阴影 ⇒ 让面板看起来"**浮在桌面上**"。
             * ★ **画在岛体之前**（画在之后会盖住玻璃 —— 那是"蒙了一层灰"，不是投影）。
             *
             * ## 值域与换算
             *
             * `0..100`（百分比）⇒ 内部 `strength` = 本值 / 100。
             * ★ 默认 **60** = 面板的 `0.60`。
 * ★★ 2026-10-05 **用户拍板改默认值**：原记「60」⇒ ★ **现改为 `0`** ——
 *   **理由**：原来那组"面板照抄值"会让**开箱就变样**（即使从没用过液态玻璃，词岛也会多出"一层极淡白 + 一圈投影"，台账 R18 早就预告）⇒ ★ **默认归零 = 开箱零变化**；★ **推荐值已存成内置预设「液态玻璃（推荐）」**（`PresetStore.BUILT_IN`），想要一键切 ✓
             *
             * ## 实测（`SurfaceShadowProbe` 实验 B）
             *
             * 岛体下方 20 px 的平均亮度（桌面基准 209.2）：
             * `0.30` → 109.5（−47.7%）· **`0.60` → 62.0（−70.3%）** · `0.90` → 39.0（−81.4%）
             * ⇒ **单调、且 0.60 处明显可见但不死黑**。
             *
             * ## ★ 方向不是固定的（这一条影响"设置是否诚实"）
             *
             * 窗口**只在带子那一侧**多出预留带（`controls.position`）——
             * 若投影固定朝下，则「控件位置 = 上方」时岛体下边缘**就是窗口下边缘**，
             * 投影**整条被裁掉** ⇒ 拖这根滑杆**什么都不会发生**。
             * ⇒ 实现里**朝预留带那一侧投**（见 `MaterialLayers.shadowOffsetY` 的 KDoc）。
             *
             * ## 性能
             *
             * 位图**按强度缓存**（1 槽），构建走 1/2 降采样 = **2.3 ms**；
             * 稳态每帧只 `drawImage` = **0.57 ms**（bench 实测）。
             */
            summary = "词岛下方的柔和阴影，让面板「浮」在桌面上。100 = 最深；建议 40–80，过高会发黑。",
            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.noise.amplitude",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "颗粒幅度",
            summary = "背景噪点（磨砂颗粒感）。0 = 关闭。",
            default = 0,
            range = 0.0..20.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-25 · 第 3 期「材质 L2」新增 2 键 ────────────────────────
        //
        // 来源：`04-*` §5（第 3 期）· `G:144-152`（L2 行）· `B §F.5` 尾注（画质档的硬约束）。
        //
        // ## ⑪ `material.quality`：画质档 —— **一组开关的集合**，不是滑杆
        //
        // `B §F.5`：「档位 = **一组功能的开关集合**（参考竞品 `PerformanceProfile`）。
        // 本项目当前只有 `animation.quality` / `animation.frame_rate`，**没有材质档** ——
        // 不加档位直接上材质，低配机器会直接卡死。」
        //
        // ★ 默认 `FULL` 的论证：现有三层**全部默认关闭**（`0` / `NONE` / `0`）⇒
        //   档位默认 `FULL` **不会**让任何人的画面变化（没有东西可关）。
        //   反之若默认 `LITE`，用户把三个滑杆都拉满却看不到全部效果 ⇒ 会以为设置坏了。
        //
        // ## ⑫ `material.content.blur_radius`：岛内内容模糊半径（L2 的载体）
        //
        // ⚠️ **它不是 Q3 的"背景模糊（真透视玻璃）"** —— 那需要截屏（L3）。
        //   本键模糊的是**岛体自己的内容**（封面图背景），走**既有烘焙管线**
        //   `CoverBackdrop.bake()`（实测 4× 降采样 + 3 遍 box blur = 1.68 ms，`B §A-1`）。
        //   ⇒ 零截屏、零 native、零新依赖。
        //   `0` = 关 ⇒ **开箱逐像素零变化**（这条是硬判据，见 `CoverBackdrop` 的 KDoc）。
        //
        // ⚠️ 两键**都落「材质」细类**（该细类已于波次 0 建立，无需新增）。
        SettingDescriptor(
            key = "material.quality",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.LIST,
            title = "材质画质档",
            // ★ 2026-10-01 更正：原写「「关闭」= **不画任何材质**」——**过宽**。
            //   实测 `OFF` 只让 `MaterialLayers.paint` 早退（**高光 / 渐变 / 噪点三层**）；
            //   而**同组的 `material.content.blur_radius`（封面图模糊）仍按自己的开关绘制**
            //   （`IslandWindow` 里它只按 `LITE` 调降采样，**没有 `OFF` 分支**）。
            //   ⇒ 说"不画任何材质"会让用户以为"封面图模糊也一起关了"。
            summary = "「关闭」= 不画材质的高光 / 渐变 / 噪点三层（「封面图模糊」仍按它自己的开关绘制）；" +
                "「精简」/「完整」= 都画全部材质，差别在模糊的成本路径。",
            // 用 LIST 而不是 SWITCH —— 与 `background.color_mode` / `material.fill.gradient_style`
            // 同一条惯例（一个 LIST 同时表达"哪种模式"）。
            default = "FULL",
            options = listOf(
                "OFF" to "关闭",
                "LITE" to "精简",
                "FULL" to "完整",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            // ★ **P1-8 误判修正**（★ 同上）：
            // ★ `MaterialLayers.paint` 的**第一行**就是 `if (quality == "OFF") return`
            // ⇒ ★ 它是**材质的 OFF 总开关** ⇒ ★ 下架后**用户无法关闭材质层** ✗
            // ⇒ ★ **恢复**：三层材质（高光 / 渐变 / 噪点）保留 ⇒ 它们的总开关必须留 ✓
        ),
        SettingDescriptor(
            key = "material.content.blur_radius",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "封面图模糊",
            summary = "模糊岛体自己的封面图背景（不采桌面）。0 = 关闭。它不是「身后窗口模糊」（那个要采桌面，见 L3）。",
            // 上界 40：`B §A-1` 的实测档位到 4× 降采样 + 3 遍 box blur，
            // 再大的半径在 304×154 的岛上已经"糊成一片"，没有信息量。
            default = 0,
            range = 0.0..40.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── `material.backdrop.blur_radius`（L3 的滑杆）────────────────────────
        //
        // 用户原话：「**滑杆控制词岛身后窗口的模糊，这是目的**」。
        //
        // ★★ **2026-09-28 已接线**（`migration/35-④b-L3主链路接线-施工记录`）：
        //   消费点是 `IslandPanel.syncL3()`（由 `applyAppearance` 在设置变化时调用）
        //   —— 半径 0 ⇒ 停采集线程；>0 ⇒ 起线程 + 按本值做模糊。
        //   **原记**：「本键没有任何消费点，渲染层先不读」（刻意的"预留"，为了让 L3
        //   落地时不必改名）；**实为**：已接线，见上。
        //   ⚠️ 但**"接线"≠"真机验证过"** —— 磨砂背景是否真的出现属 R9。
        //
        // ⚠️ 与 `material.content.blur_radius` 的分工（**别搞混**）：
        //   · `material.content.blur_radius` = **岛体自己的封面图**模糊（L2，**已实现**）
        //   · `material.backdrop.blur_radius` = **身后窗口/桌面**模糊（L3，**已接线**）
        //
        // ⚠️ L3 的成本模型已实测（`20-*` / `23-*`）：截屏 **8.25 ms/次**（与面积基本无关，
        //   但在 496→544 宽度处有**阶跃**，见 `25-*` §1）⇒ **不能每帧采**，必须降频。
        //   ⇒ 采集走**独立线程 10 Hz**（`BackdropCapture.DEFAULT_INTERVAL_MS`），
        //     故"拖滑杆"的反馈**有约 100 ms 延迟** —— 见 `PreviewKind.THROTTLED`。
        //
        // ★★ 关于"用户决策可能失误"（用户 2026-09-26 授权）：
        //   用户的判断**可能基于不完整的信息**（例如只看了一张对照图）。
        //   在**事实证据确凿**且**用户明确授意**时，可以**突破**用户的既有决策。
        //   本键就是一个例子：用户先说"不接受 B5（预模糊壁纸）"，那是基于
        //   "B5 不含身后窗口"这一条 —— 若后续实测发现 B5 的观感其实够用，
        //   且用户授意，则可以**推翻**那条决策。见 `26-*`。
        SettingDescriptor(
            key = "material.backdrop.blur_radius",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "身后窗口模糊",
            /**
             * ## ★★ 本键是**整个「身后窗口」细类的前置条件**
             *
             * ★ 判据（`BackdropCaptureParams`）：**`blur_radius = 0` ⇒ 整条折射链不跑**。
             * 而本键的**默认值就是 0** ⇒ 开箱时同组的「折射厚度 / 折射量 / 色散 / 饱和度」
             * **全部不生效**（逐像素不变，这是"默认值要稳"的兑现点）。
             *
             * ⚠️ 这会造成**静默失效**：用户只调那 4 项、不动本键时"看不出任何变化"。
             * 同组 4 项的 `summary` 已各自注明；★ **一键配好的入口是预设「液态玻璃（推荐）」**
             * （它现在会一并把本键设为 16 —— 该预设曾**漏掉本键**、导致"点了预设也看不到折射"，已修）。
             */
            summary = "模糊词岛身后的窗口/桌面（要采屏，成本高）。0 = 关闭" +
                "（同组的折射 / 色散 / 饱和度会随之全部不生效，想一键配好可直接用预设「液态玻璃（推荐）」）。" +
                "约 100 ms 延迟（10 Hz 采集）。",
            default = 0,
            range = 0.0..40.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        // ══════════════════════════════════════════════════════════════════════
        // ★★★ ④b 第二批：「身后窗口」的**折射 / 色散 / 饱和度**（2026-10-01，用户「开4b」）
        //
        // ## 它们是什么（一句话）
        //
        // L3 把身后的窗口截下来、模糊、垫在词岛底层 —— 但**只模糊不折射**看起来是
        // "面板后面糊了一块"，而**不是"一块玻璃"**。这 3 项（+ 上面那个模糊半径）
        // 把那张背景图**当作真实玻璃来弯**。
        //
        // ## ★★ 4 项**全部只在 `material.backdrop.blur_radius > 0` 时才有效果**
        //
        // 它们改的是"L3 采到的那张背景图" —— L3 关着时**根本没有那张图**。
        // ⇒ ★ **开箱（`blur_radius` 默认 `0`）逐像素不变** ✓（本项目"默认值要稳"的兑现点）
        // ⚠️ 但这意味着**用户必须先开「身后窗口模糊」**才看得到这 4 项 ——
        //    各 summary 里都写明了，避免"拖了没反应"。
        //
        // ## ★ 值全部照**用户提供的 Android 面板**（用户指令：他给的值我们也使用）
        //
        // | 面板项 | 值 | 我们的键 |
        // |---|---|---|
        // | `refractionHeight` 折射厚度 | 16.0 dp | `material.backdrop.refraction_height` |
        // | `refractionAmount` 折射量 | 32.0 dp | `material.backdrop.refraction_amount` |
        // | `dispersion` 色散 | 0.35 | `material.backdrop.dispersion` |
        // | `vibrancy` 饱和度 | 1.5× | `material.backdrop.saturation` |
        //
        // ⚠️ **面板里没有的**：折射率 IOR —— ★★ **2026-10-10 起已【不再使用】** ✓
        //   ★ 它原只服务 Snell 档（`BackdropRefraction.IOR = 1.4`，出处 `LiquidGlassWinUI` `RefFactor`）；
        //   ★★ Snell 已按用户指令收掉（「三档剖面只留呈现效果与开销平衡的档位」）⇒ ★ 该常量已删 ✓
        //   ★ 出处置于 `BackdropRefraction` 文件头的「参数从哪来」表（`u_refFactor` 行）✓
        //
        // ## ★★★ 色散那一项**不能照抄 0.35**（实测逼出来的，见 `DISPERSION_SCALE` 的 KDoc）
        //
        // 面板的 `0.35` 与业界默认 `7` **不是同一个刻度** —— 实测把 0.35 喂进公式，
        // 边缘环带差只有 **0.20**（≈ 噪声，**等于没做**）；7 才有 **3.15**（可见）。
        // ⇒ 我们的键取 `0..100` 百分比、默认 **35**（= 面板的 0.35），
        //   内部 `factor = 百分比/100 × 20` ⇒ **默认 35% 恰好落在业界默认 7**。
        // ══════════════════════════════════════════════════════════════════════
        SettingDescriptor(
            key = "material.backdrop.refraction_height",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "身后窗口 · 折射厚度",
            /**
             * ★ 照**用户提供的面板**的「`refractionHeight` 折射厚度 **16.0 dp**」。
             *
             * ## 它是什么
             *
             * 玻璃边缘**能弯折背景的那一层有多厚**（px）。
             * 越厚 ⇒ 边缘的扭曲带越宽、越"厚玻璃"；越薄 ⇒ 只有极边缘一点点。
             * ★ 公式上它就是 `nmerged >= thickness ⇒ edgeFactor = 0`（**超出厚度不折射**）。
             *
             * ## 值域
             *
             * `0..100` px。★ `0` = 不折射。默认 **16**（面板值）。
             * ★ 业界 `LiquidGlassWinUI` 的 `RefThickness` 是 1–80、默认 20 —— 我们取
             * **面板的 16**（用户指令优先），并把上界放宽到 100（面板未给上界）。
             *
             * ⚠️ **只在「身后窗口模糊」> 0 时才有效果**。
             */
            summary = "玻璃边缘能弯折背景的那一层有多厚。0 = 不折射。⚠️ 需先把「身后窗口模糊」调到大于 0（或直接用预设「液态玻璃（推荐）」），否则本项不生效。",
            default = 16,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.backdrop.refraction_amount",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "身后窗口 · 折射量",
            /**
             * ★ 照**用户提供的面板**的「`refractionAmount` 折射量 **32.0 dp**」。
             *
             * ## 它是什么
             *
             * 边缘处**采样点沿法线偏移多少像素** ⇒ 直接决定"背景被掰弯得多厉害"。
             *
             * ## ★ bench 实测（`_diag_spw/BackdropRefractionProbe.java` 实验 A）
             *
             * | 值(px) | 与"无折射"的平均差 | 观感 |
             * |---|---|---|
             * | 0 | 0.00 | 基准 |
             * | 16 | 0.76 | 几乎看不出 |
             * | **32（面板值）** | **2.43** | ★ **图上边缘网格可见地弯了** ✓ |
             * | 64 | 5.36 | ★ 可见但克制 |
             *
             * ⇒ ★ **面板的 32 在 304×154 的词岛上"看得见但不夸张"** ⇒ **可直接用** ✓
             * （★ 平均差小是因为**大块平坦内部本来就不该变** —— 折射只发生在边缘带。）
             *
             * ⚠️ **只在「身后窗口模糊」> 0 时才有效果**。
             */
            summary = "边缘处背景被掰弯多少像素。越大扭曲越明显。0 = 不折射。⚠️ 需先把「身后窗口模糊」调到大于 0（或直接用预设「液态玻璃（推荐）」），否则本项不生效。",
            default = 32,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.backdrop.dispersion",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "身后窗口 · 色散",
            /**
             * ★ 照**用户提供的面板**的「`dispersion` 色散 **0.35**」——
             * ⚠️ **但值不能直接照抄**，见下。
             *
             * ## 它是什么
             *
             * 红/绿/蓝三个通道**按不同倍率偏移**采样（业界 `N_R = 0.98 / N_G = 1.0 / N_B = 1.02`）
             * ⇒ 边缘出现**红蓝分离的彩边**（真实玻璃的色散）。
             *
             * ## ★★★ 为什么默认是 35 而不是 0.35（**实测逼出来的**）
             *
             * 面板的 `0.35` 与业界 `RefDispersion` 默认 `7` **不是同一个刻度**。
             * 把两个值分别喂进**同一个公式**实测：
             *
             * | `factor` | 边缘环带平均差 | 观感 |
             * |---|---|---|
             * | **0.35（面板）** | **0.20** | ❌ **几乎看不出（≈ 噪声）** |
             * | **7（业界默认）** | **3.15** | ★ 可见但克制 |
             *
             * ⇒ ★ 若照面板的 0.35 直接实现，**在词岛上等于没做** ——
             *   那正是用户点名过的「**不诚实设置项**」。
             * ⇒ 我们的键取 `0..100` 百分比、**默认 35**（= 面板的 `0.35`），
             *   内部 `factor = 百分比 / 100 × 20` ⇒ ★ **35% 恰好落在业界默认 7**。
             *   ⚠️ 换算的完整论证见 `BackdropRefraction.DISPERSION_SCALE` 的 KDoc。
             *
             * ⚠️ **只在「身后窗口模糊」> 0 时才有效果**。
             */
            summary = "边缘红蓝分离的彩边强度。0 = 无色散。建议 20–60，过高会出现彩虹边。⚠️ 需先把「身后窗口模糊」调到大于 0（或直接用预设「液态玻璃（推荐）」），否则本项不生效。",
            default = 35,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.backdrop.saturation",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SEEKBAR,
            title = "身后窗口 · 饱和度",
            /**
             * ★ 照**用户提供的面板**的「`vibrancy` 饱和度 **1.5×**」。
             *
             * ## 它是什么
             *
             * 对**透出来的那张背景图**做饱和度拉伸（绕 `Rec.709` luma 线性拉伸）——
             * 让"透过玻璃看到的世界"比真实世界更鲜艳一点（这是各家玻璃材质的常见手法）。
             *
             * ## 值域与换算
             *
             * `0..200`（百分比），★ **`100` = 不变**（本项目惯例：100 = 标准）。
             * ⇒ 面板的 **1.5×** ⇒ 默认 **150**。
             * ★ 量纲与业界 `LiquidGlassWinUI` 的 `Saturation`（0–2，1.0 = 不变）**1:1 对齐**。
             *
             * ## bench 实测（实验 C）
             *
             * `1.2×` ⇒ 平均差 4.10（克制）· **`1.5×` ⇒ 9.64（明显）** · `2.0×` ⇒ 16.65
             * ⇒ ★ 面板的 1.5× **可直接用** ✓
             *
             * ⚠️ **只在「身后窗口模糊」> 0 时才有效果**。
             */
            summary = "透过玻璃看到的背景的鲜艳程度。100 = 不变，0 = 灰度，200 = 最艳。⚠️ 需先把「身后窗口模糊」调到大于 0（或直接用预设「液态玻璃（推荐）」），否则本项不生效。",
            default = 150,
            range = 0.0..200.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.render.gpu_backend",
            group = SettingGroups.ISLAND_MATERIAL,
            subGroup = "材质",
            kind = SettingKind.SWITCH,
            title = "身后窗口 · 用显卡算折射",
            /**
             * ★★★ **R7 GPU 后端开关**（2026-10-09）。
             *
             * ## 它是什么
             *
             * 把「身后窗口的折射烘焙」从 **CPU**（`BackdropRefraction.bake`，本项目
             * 单线程 47 ms / 8 线程 11 ms）换成 **GPU**（`GpuLiquidGlass.render`，
             * JNA 直调 `opengl32.dll`，实测 **2.86 ms**）。
             *
             * ## ★★ 为什么默认【关】
             *
             * ① **默认值要稳** —— GPU 输出与 CPU 输出**不是逐像素相同**
             *    （GPU = 参考 `FRAG` 的**单趟**管线；CPU = **分阶段**：采样 → 色散 → 饱和度）。
             *    ⇒ 开箱即改观感**不是**本项目允许的默认行为 ✓
             * ② **故障隔离** —— 关掉即回到验证最充分的 CPU 路径 ✓
             * ③ **驱动差异** —— 探针只覆盖了 AMD 780M；Intel 核显 / NVIDIA / 混合显卡
             *    / 远程桌面 等场景**未验证**（★ 失败会**自动回退 CPU**，但会多一次建上下文的开销）✓
             *
             * ## ⚠️ 三条使用前提
             *
             * · **只在「身后窗口模糊」> 0 时才有效果**（折射层挂在模糊层之后）✓
             * · **只在 Windows 上可用**（`opengl32.dll` / `wgl*` 是 Win32 API）⇒
             *   其他平台该键**恒为无效**（`isAvailable()` 返回 false ⇒ 静默走 CPU）✓
             * · **失败即回退** —— 驱动不支持 / GL 建上下文失败 / 渲染抛错 ⇒
             *   本帧自动回到 CPU 路径，不会黑屏、不会卡死 ✓
             *
             * ## 值域
             *
             * `false` = 用 CPU（默认）· `true` = 优先用 GPU（失败自动回退 CPU）✓
             *
             * ## ★★ 为什么把「渲染负载」写进本项的 `summary`
             *
             * 本项是**唯一**让用户做"算力放哪"选择（CPU / GPU）的条目 —— 也正是
             * "开了显卡却没感到变快"时用户会来看的那一条。故把负载实测值直接写进
             * 它的 `summary`（**静态文案**：这些量都是**实测常量**，不随运行变化，
             * **不需要运行时读取** ⇒ **不加动作入口 / 不动架构 / 不加设置项**）✓
             *
             * ★ **每个数字的出处（一律用符号名，不写行号）**：
             * · 词岛玻璃逐像素 **13.6 ms/次**（单线程）与加速上限 **4.16×** ——
             *   `LiquidGlassPerfProbe` 实测；
             * · 背景折射整轮 **≈33.4 ms** 的四段分解（截屏 8.25 + 判据 2.6 + 模糊 5.9
             *   + 折射 16.6）—— `BackdropRefraction` / `BackdropCapture` 的 KDoc；
             * · 背景折射 **10 Hz**（占单核 ≈33.4%）—— `BackdropCaptureParams.intervalMs`；
             * · 帧预算 **16.7 ms** —— 60 fps 的 1/60 s 换算值。
             * · "采集线程已降到最低优先级" —— 见采集线程的优先级设置（`BackdropCapture`）。
             *
             * ★ 这组数字同时**回答"独显用户为何感知不到好处"**：玻璃那部分**已在 CPU
             *   分片并行到接近上限**，采集线程也已降到最低优先级 ⇒ 换独显的边际收益有限。
             */
            summary = "把液态玻璃折射交给显卡计算（更快）。关闭则用 CPU。仅 Windows 有效；显卡不支持时自动回退 CPU。" +
                "渲染负载实测（帧预算 16.7 ms / 60 fps）：" +
                "· 词岛玻璃逐像素 13.6 ms/次（单线程），已分片并行、加速上限 4.16×；" +
                "· 背景折射整轮 ≈33.4 ms = 截屏 8.25 + 判据 2.6 + 模糊 5.9 + 折射 16.6（折射占 ≈50%），按 10 Hz 采集、约占单核 33.4%；" +
                "· 采集线程已降到最低优先级 ⇒ 独显提升不明显，是因玻璃那部分已在 CPU 分片并行到接近上限。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            // ★★★ 2026-10-17（用户指令「删掉不上 GPU 这个自我限制」）：**取消下架，恢复为正式选项**。
            //
            // ## 为什么撤掉"不上 GPU"
            // 这条限制原本来自项目纪律 **A1「只用 SPW API 能力范围」** —— 它把"自带 GPU 栈"判为例外。
            // 竞品 v0.14.0 用 Skiko+Direct3D 证明了这条路可行，且是观感上限所在。
            // ⇒ 用户拍板 **撤销该自我限制**：GPU 从"破例"变成**正当路线**。
            //
            // ## 现状（如实，不夸大）
            // · 实现已在：`render/GpuLiquidGlass.kt`（JNA 直调 `opengl32.dll` · GL 4.6 · FBO · 实测 **2.86 ms**）
            // · 当前**只接在「背景折射」这一条**（`BackdropCapture.gpuRefractionOrNull`）
            // · **尚未接**「词岛玻璃面（L2 逐像素）」—— 那是下一步
            // ⇒ 所以标题/文案写"折射"，不写"液态玻璃全部"。
            // ★ 2026-10-17（静默推进）：**暂不对用户开放** —— 用户指令「暂不开放设置项给用户」
            retired = true,
        ),
    )

    // ── E 组 · 字体（3 项，总表 §1.5 序号 18–20） ────────────────────────
    private fun groupE(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "main_lyric.font.family",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.FONT,
            title = "字体",
            // 默认 "Microsoft YaHei UI" —— **本机字体**（Windows 10/11 自带的微软雅黑）。
            //
            // ## 2026-09-22：这里曾是随包字体 `MiSans`，现已改回
            //
            // 用户原话：「**取消自带字体包，调用自带字体包的路线转到在本机选择的字体上**」。
            // 此前插件随包分发 10 个 MiSans TTF（约 9.4 MB）、默认值就是它；
            // 取消该路线有两个理由，**许可那条是决定性的**：
            // MiSans 协议 §2 第 3 条禁止分发字体副本、第 2 条明文禁止改编/二次开发，
            // 「原样把 `.ttf` 打进包」落在许可的哪一侧**文本无唯一解**
            // （`THIRD_PARTY_NOTICES.md` C7 / §9.2 一直记为**未决**）。
            // 取消随包分发后，这条未决项连同它的风险一并消失。
            //
            // ## 为什么取 `Microsoft YaHei UI`
            //
            // 判据是**"任何 Windows 上都存在、中文覆盖完整"**，且它与**原实现一致**：
            // 原实现 SPW 的 `font_family` 默认值本来就是 `Microsoft YaHei UI`
            // （`HostSettings.kt` 的 `config.get("font_family", "Microsoft YaHei UI")`）。
            // 2026-09-19 改成 `MiSans` 只是为那条**现已取消**的随包路线，故改回。
            // 完整理由（含 `Microsoft YaHei` / `微软雅黑` 三者的区别）见
            // `font/FontDefaults.kt` 的 `DEFAULT_FONT_FAMILY` KDoc。
            //
            // ⚠️ **"首次默认、之后尊重用户"由设置系统本身保证，不需要额外代码**：
            // 只有当宿主配置里**没有**这个键时，`SettingsRuntime.readRaw` 才会回落到
            // `descriptor.defaultValueText()`（见 `config.get(key, defaultValueText())`）。
            // 用户一旦改过，该键就存在于配置中，此后**永远读用户的值**。
            // 故本行只影响"从未设过这一项"的新用户，不会覆盖老用户的既有选择
            // —— 老用户仍会继续用他们原来选的族名（含 `MiSans`，若本机装过）。
            //
            // ⚠️★★ **2026-10-07 更正（原记已不成立）**：本段旧文写
            //   「这个字面量必须与 `font/FontDefaults.kt` 的 `DEFAULT_FONT_FAMILY` **逐字相同**，
            //    故由探针 `FontDefaultProbe` 做机械比对」——
            //   ★ **实为**：自 2026-09-29 起本键默认值改成 **`@host:MiSans` 哨兵**，
            //     两者**语义不同、本就不该相等**，故 `FontDefaultProbe` ① 已**放宽**为
            //     「等于 `DEFAULT_FONT_FAMILY` **或** `@host:` 哨兵」⇒ ★ **「机械比对」不再成立**。
            //   ★★ 而 `DEFAULT_FONT_FAMILY` 现在的职责是**哨兵 / 空值解析失败时的回退族**
            //     （见 `font/FontResolution.resolveFontOrFallback` 与 `font/FontDefaults.kt`）。
            //   ★ 教训（可复用）：**「A 必须等于 B」这类申报，在 B 的语义改变后必须重新核对** ——
            //     否则会留下一条**守错对象**的护栏（本项目的「护栏与生产路径错位」一族）。
            // ★★ 2026-09-29（用户指令：「**我们复用宿主字体用作默认字体**」）：
            //    默认从 `Microsoft YaHei UI` 改成 **`@host:MiSans`**。
            //    ⇒ 开箱即与**宿主 UI 同款**（同一份 `misans.ttf` ⇒ 逐像素同源）。
            //    ⚠️ **未预热 / 宿主不可得时**：`HostFonts.resolveOrNull` 返回 `null`
            //      ⇒ `fontWithLetterSpacing` 回退到 `Font("@host:MiSans", …)`
            //      ⇒ 该族名不存在 ⇒ **JDK 再回退到默认字体** ⇒ **不崩、不卡**（fail-open）。
            //    ⚠️ 预热由 `CdlPlugin.start()` 的后台线程做（见那里的 KDoc）。
            //    ★ **用户自选 / 自导入的入口一个都没动**（`FontPicker` 的系统族 + `FontSourceDialog`
            //      的 `FontCatalog`（RECENT/FILE/SYSTEM））—— 默认值只是"出厂选哪一款"。
            default = "@host:MiSans",
            phase = SettingPhase.P1,
            // 字体族切换是**唯一允许延迟的例外**（大纲 §9.7.2 硬要求 3）。
            preview = PreviewKind.DEFERRED,
        ),
        SettingDescriptor(
            key = "main_lyric.font.size",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.SEEKBAR,
            title = "字号",
            default = 22,
            // 2026-09-16 用户要求「字体大小范围放宽」：原 14..42 → 8..200。
            //
            // ⚠️ **这里曾经是本轮一个真实缺陷的现场**（BUG-024）：
            // `preference_config.json` 已把该项改成 `min: 8 / max: 200`，
            // 但**注册表仍留在 `14.0..42.0`** —— 于是滑块能拖到 8 或 200，
            // 值却在 `coerceInt` 里被**静默夹回 14..42**。
            // 用户侧的表现正是他报的那条：「配置页中颇多选项无法修改实际设置」。
            //
            // **两处范围必须一致**：json 决定滑块的可拖区间，注册表决定落盘时的 clamp。
            // 二者不一致时，**用户看到的值与实际生效的值不同** —— 且没有任何报错。
            // 已加 `RegistryProbe` 断言（见其「json 与注册表范围一致性」一节）防止复发。
            //
            // 下界 8：再小已不可读（22 是默认，8 约为其 1/3）。
            // 上界 200：超大字号用于"歌词铺满屏幕"的玩法；渲染层按字宽自动换行/裁剪，
            // 不需要为 200 新增分支（`KaraokeTextRenderer` 的度量链与字号无关）。
            range = 8.0..200.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.font.weight",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.LIST,
            title = "字体粗细",
            /**
             * ★★ **2026-09-29：从 SEEKBAR（`50..200`）改成 LIST（`NORMAL` / `BOLD`）** ——
             * **根治一个"设置项说谎"的 bug**（用户指令：「改，根治这个bug」）。
             *
             * ## ★★★ 原设计为什么是错的（实测，见 `migration/42-*`）
             *
             * 原 KDoc 写着「字体粗细在 Java2D 里是**连续量**（`TextAttribute.WEIGHT`，0.5..2.75）」
             * —— ★★ **这句话是错的**，**本 bug 的认知根源就是它**。
             *
             * **实测（17 档 × 两款字体，`_diag_spw/WeightScaleProbe.java`）**：
             *
             * | `WEIGHT` | 宿主 MiSans | Microsoft YaHei UI（有真 Bold） |
             * |---|---|---|
             * | `0.5 … 1.9`（17 档） | **全程 ink 1.3 不变** | **全程 2194.2 不变** |
             * | `2.0` | **1.7（+38.5%）** | **3466.8（+58.0%）** |
             * | `2.5` | **与 2.0 完全相同** | **与 2.0 完全相同** |
             *
             * ⇒ ★★ **`TextAttribute.WEIGHT` 对文字只有"两态"**：
             * **`< 2.0` 完全无效**、**`≥ 2.0` 全部得到同一个效果**。
             * ⇒ 原来的滑杆（`50..200`）**`50–199%` 全是死的**，拖到 `200%` 才突然变粗一次
             * —— **用户拖了四分之三的行程，画面毫无反应**。
             *
             * ## ★★ 新设计：**选择项**，且**让 JDK 挑字重**（不硬编码 `WEIGHT`）
             *
             * 用户 2026-09-29 提醒：「**不同字体可能有不同字重档**」⇒ ★ **不能硬编码"两档 WEIGHT"**。
             *
             * | 做法 | 多字重字体（Inter 18 档 / 思源黑体） | 单字重（MiSans） |
             * |---|---|---|
             * | `deriveFont(WEIGHT, 2.0f)` | ⚠️ **也是合成加粗**（不走真字重） | 合成 |
             * | ★ **`deriveFont(Font.BOLD)`** | ★ **JDK 挑该族最接近的真字重** ✓ | 合成（无真 Bold 时） |
             *
             * ⇒ ★★ **实现用 `Font.BOLD`**（`render/LetterSpacing.kt`）⇒ **自动适配不同字体的可用字重档**。
             *
             * ## 默认值
             *
             * `NORMAL` = 改动前的观感（原 `100`）⇒ **开箱零变化** ✓
             *
             * ⚠️ **老配置迁移**：原键 `main_lyric.font.weight_scale`（数值 `50..200`）**不再存在** ⇒
             * 老配置里的该键会走 `unknownKeys`（被忽略，不报错）⇒ 用户重新选一次即可。
             * ★ 默认 `NORMAL` 与原默认 `100` **观感相同** ⇒ 迁移无感。
             */
            summary = "常规 = 改动前的观感；加粗 = 用**该字体自己的粗体**" +
                "（多字重字体会挑到真字重，单字重字体走算法合成）。",
            default = "NORMAL",
            options = listOf(
                "NORMAL" to "常规",
                "BOLD" to "加粗",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.font.recent",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.LIST,
            title = "最近使用字体",
            // 总表 §1.5 序号 20：默认 `[]`（空列表）。
            //
            // ⚠️ 类型取舍（本实现的一处判断，必须写明）：总表标它 `list`，但它与其余 8 个
            // LIST 项**有本质区别** —— 那 8 项是"从固定 options 里选一个"，而本项是
            // "一个容量 8–12 的有序字符串列表"（总表 §5.5：按使用时间倒序、去重、超容量截断）。
            // 二者都叫 `list` 但语义不同。本实现仍归 [SettingKind.LIST]（**尊重总表的类型列**），
            // 但因它没有固定 options，无法用 LIST 的 `options` 校验路径 —— 见下方 `options` 的处置。
            default = "",
            // options 传空列表而非 null：init 块要求 LIST 必须有 options，
            // 而本项的合法取值是**开放集合**（用户装过的任何字体）。
            // 空列表 = "无固定候选"，由消费方（字体菜单）自行解析管分隔的族名。
            options = emptyList(),
            phase = SettingPhase.P1,
            preview = PreviewKind.DEFERRED,
        ),
    )

    // ── F 组 · 文字颜色（2 项，总表 §1.6 序号 21–22） ─────────────────────
    private fun groupF(): List<SettingDescriptor> = listOf(
        // ── 歌词三色：**RGB + 明度滑杆**（2026-09-17 用户拍板「方案 A」）──────────
        //
        // ## 用户原话
        //
        // > 「SPW 设置页中**我指名的 RGB 三色滑杆，明度滑杆呢**？」
        // > 「**在 SPW 设置页的设置项不要引去其他窗口**。」
        //
        // ## 原记与实为
        //
        // **原记**：三个颜色各是一个 `COLOR` 键，降级为 `edittext`（手填 `#RRGGBB`），
        //   旁边配一个取色器按钮（`pickSungColor` 等）跳到自绘窗口去挑。
        //
        // **实为**：SPW **没有颜色控件**，而"手填十六进制 + 跳窗口取色"都不好用 ——
        //   前者要求用户知道色值，后者正是用户明确否掉的"引去其他窗口"。
        //   **SPW 能表达颜色的唯一原生手段就是滑杆**，故改为 RGB + 明度共 4 根。
        //
        // ## 为什么是"方案 A：滑杆即真相源"
        //
        // SPW 的设置项是**各自独立的键**，所以"颜色的真相源是谁"必须明确。
        // 用户拍板 A：**12 个滑杆键合成颜色，删掉 3 个旧 hex 键** ——
        // 单一真相源、可解释。代价是**旧配置里的自定义色需要重设一次**
        // （用户已知悉并接受）。
        //
        // ## 明度（lightness）的语义
        //
        // **在 RGB 之上再乘一个系数**：`最终色 = RGB(r,g,b) × (明度/100)`。
        // `100` = 原色（默认），`0` = 全黑。它是一个**整体调暗**的旋钮 ——
        // 例如把"未唱歌词"再压暗一档，而不必手算三个通道。
        // 不采用 HSL 的 L（那会让 R=G=B 时明度无效），也不用它提亮
        // （提亮请直接调 RGB —— 一个旋钮做两件事会让"这个值到底在干什么"变得不可推理）。
        //
        // ## 默认值 = 改动前那三个颜色的**逐通道分解**（故开箱观感零变化）
        //
        // | 颜色 | 原默认 | R/G/B |
        // |---|---|---|
        // | 已唱 | `#FFFFFF` | 255 / 255 / 255 |
        // | 未唱 | `#7E818A` | 126 / 129 / 138 |
        // | 译文 | `#B1B6C3` | 177 / 182 / 195 |
        //
        // ⚠️ **必须是字面 `SettingDescriptor(...)` 块**，不能用辅助函数批量生成 ——
        //   `tools/gen_preference_config.py` 是**按正则从源码文本解析**这些块的
        //   （见它自己的 KDoc），生成式的项**它扫不到**，会直接漏出设置页。
        // ── 2026-09-18 恢复：12 根颜色滑杆（改成取色器样式后仍保留键）────────
        //
        // 用户要求「把 RGB 滑杆和明度滑杆以及色号输入框换成取色器样式」→
        // **设置页不再显示这 12 根滑杆**，但键**必须存在**：
        //   · `IslandWindow.mixerColor` 仍按 `$prefix.r/.g/.b/.lightness` 合成颜色，
        //     键不存在会**静默回退默认值**（不报错，用户以为"改不了颜色"）；
        //   · 取色器要把用户选的颜色**写回这 4 个键**。
        // 故它们全部加进 `SELF_DRAWN_KEYS`（= 不上 SPW 设置页，但仍是注册表项）。
        SettingDescriptor(
            key = "main_lyric.fill.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 红",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 绿",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 蓝",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.color.lightness",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 明度",
            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 红",
            default = 126,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 绿",
            default = 129,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 蓝",
            default = 138,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.lightness",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 明度",
            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 红",
            default = 177,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 绿",
            default = 182,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 蓝",
            default = 195,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.lightness",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 明度",
            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    // ── G 组 · 左侧内容：频谱与封面（4 项，总表 §1.7 序号 23–27 中的 23–24 + 25/26/27） ──
    // ⚠️ 总表 §1.7 标题写「4 项」，但其表体列的是序号 23–27（**5 行**）。
    // 这是总表自身的一处数字不一致，见本文件末 [verify] 的说明；本实现按**表体 5 行**落地。
    private fun groupG(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "spectrum.enabled",
            // ★★★ 2026-10-17：**回到「频谱」组**的「开关与位置」细类（总开关组已撤销）。
            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.SWITCH,
            title = "显示实时频谱",
            // 总表 §11 U9（暂定）：保持与原实现默认视觉一致（原实现 leading_content 默认 spectrum）。
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.enabled",
            // ★★★ 2026-10-17：**回到「封面」组**的「开关与缩放」细类（总开关组已撤销）。
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "显示专辑封面",
            summary = "关闭后词岛不显示专辑封面（歌词、频谱与播放控件不受影响）。",
            // ★ 2026-10-17：原 summary 是「仅在形态为悬浮窗（封面态）时本项无效」——
            //   该形态已从设置面撤下 ⇒ 条件从句失效；改写成**本项自己的**说明 ✓
            // 同上 U9：原实现是 spectrum/cover 互斥的 list，拆成两个开关后
            // "谱 true + 封面 false"才等价于原实现默认视觉。
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.scale_with_font",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "封面随字号缩放",
            summary = "仅在开启「显示专辑封面」时生效。",
            // 用户诉求（2026-09-18）：
            // > 「歌曲封面要能**随字体缩放**，**或者**提供**随字体缩放和独立调整的开关**。」
            //
            // **取"开关"这条**，不取"独立调整"：
            //   · 独立调整要新增一个尺寸项（又一个数值滑杆），而用户的**主诉求是"跟着字体走"**；
            //   · 开关把"跟随 / 不跟随"这个决定权交给用户，**代价是一个布尔**；
            //   · 若将来真需要独立尺寸，再在 `false` 分支上加一项即可（那时它才是有意义的）。
            // **默认 `true`** = 跟随字号 —— 与"封面本就该随词岛整体缩放"的直觉一致，
            //   且词岛高度本来就由字号推导（见 `islandHeightFor`），封面跟随才不脱节。
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.sharpness",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SEEKBAR,
            title = "封面清晰度（锐化）",
            summary = "仅在开启「显示专辑封面」时生效。0 = 只做高质量缩放；" +
                "值越大细节越锐，过高会在锐边处出现光晕。",
            // ★★★ 2026-10-13（**BUG-188**）：
            //   ★ 用户原话：「**能否优化封面显示?使其显示得更清晰可变，在大小形态不变的情况下。**」
            //   ★ 缩放核已从 box 换成 **Lanczos3**（真实封面锐度 **+7.3%**、混叠 **−75%**，
            //     见 `CoverScaler` 的第三轮 KDoc）；★ **本项是"再往上加"的那一档**。
            //   ★ 默认 **25**：实测阶跃边缘的过冲只比纯 Lanczos3 多 **1.0/255**（不可辨），
            //     而锐度到 **+16.4%** ⇒ 「更清晰」可感、且无可见副作用 ✓
            //   ★ 上限 100 会让过冲到 **+30 以上**（锐边出光晕）—— 留着是因为本项目的定位是
            //     「能，且在设置里能切」，★ 但默认值必须落在"看不出代价"的那一段 ✓
            default = 25,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.shape",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.LIST,
            title = "封面形状",
            // 用户诉求（2026-09-21，附**黑胶唱片**截图）：
            // > 「**新增圆形封面选项**并允许其转动。」
            //
            // 现状：封面是**圆角矩形**，圆角随 `shape.type`（`SQUARE` 时更小）。
            // 加 `CIRCLE` 后与「封面转动」配起来才是黑胶那个观感 ——
            // **方形封面转起来只是"图片在转"，圆形才像唱片**。
            //
            // ⚠️ 为什么用独立键而**不**复用 `shape.type`：
            //   `shape.type` 描述的是**词岛整体**的形态（方 / 圆角 / 胶囊 / 刘海），
            //   拿它当封面形状会让"改词岛形态"顺带改封面 —— 那是两件事。
            default = "ROUNDED",
            summary = "选「圆形」再开「封面转动」，就是黑胶唱片效果。",
            // ⚠️ **选项值是 `SettingModel` 解析 LIST 时的白名单** ——
            //    未在此声明的值会被**静默回落 `default`**（不报错、不打日志）。
            //    故新增一档必须**同时**改这里与绘制分支（见 `IslandWindow.drawCover`），
            //    并让 `IslandPolicyProbe` 用常量钉住字面量一致（`COVER_SHAPE_*`）。
            options = listOf(
                "ROUNDED" to "圆角（默认）",
                "CIRCLE" to "圆形",
                // 用户原话（2026-09-21）：「封面形状**再增加一个方形选项**。」
                // 直角封面（`arc = 0`）——与 `shape.type` 的「方形」是**两个不同的键**，
                // 同名只是巧合（那个管词岛整体轮廓，这个只管封面）。
                "SQUARE" to "方形",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "cover.rotate",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "封面转动",
            // 用户原话（2026-09-21）：「新增圆形封面选项**并允许其转动**」。
            //
            // ## ⚠️ 角度由**播放位置**推导，不是墙钟时间
            //
            // | 方案 | 暂停时 | 拖动进度条时 |
            // | --- | --- | --- |
            // | 墙钟时间 | **还在转**（音乐停了唱片还在转，不对） | 不受影响 |
            // | **播放位置**（本实现） | 位置不动 → **自然停住**，不需额外判定 | 封面跟着转过去（"唱片在唱机上"的正确语义） |
            //
            // 且它是**纯函数**（见 `coverRotationDegrees`），探针可穷举。
            default = false,
            summary = "按播放进度匀速旋转（暂停即停）。配「封面形状 = 圆形」是黑胶唱片效果。",
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "cover.rotate_period_s",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SEEKBAR,
            title = "封面转动一圈用时（秒）",
            // ## 取值依据
            //
            // 真黑胶 33⅓ rpm ≈ **1.8 s/圈** —— 但那是**唱盘**的转速，
            // 而这里封面只有几十像素：1.8 s/圈会晃眼，且与歌词**抢注意力**。
            //
            // 默认 **20 s/圈**（≈ 3 rpm）是"看得出在慢慢转、但不会分神"的量级。
            // 下限 **4**（再快就晃眼）、上限 **120**（再慢就"看不出在转"，等于没开）。
            default = 20,
            range = 4.0..120.0,
            summary = "仅在开启「封面转动」时生效。数值越小转得越快。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.vinyl",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "黑胶样式（外围黑环）",
            // 用户原话（2026-09-21）：
            // > 「**旋转圆形封面加上黑边，使其呈现黑胶样式**
            // >   （在外围加黑边，**不要侵占封面内容**）。」
            //
            // ## 为什么是"外围"而不是"内缩一圈"
            //
            // 黑胶唱片上，黑边是**唱片本体**、封面图是**片芯标签** ——
            // 环必须比封面大。若把黑边画在封面**之内**，封面图会被吃掉一圈，
            // 那正是用户点名不要的"侵占封面内容"。
            // 故本项把黑环画在封面 `size` **之外**，并**同步加宽左侧槽位**
            // （`IslandWindow` 的 `areaLeft` 用 `coverSlotWidth(...)` 推导）——
            // 不加宽的话环会盖到歌词、或从词岛左边缘戳出去。
            //
            // ## 为什么**不**做成"只有圆形才生效"
            //
            // 门控会造出一个**看起来是死设置**的开关（用户拨了没反应）——
            // 那是本项目反复出现过的一类误报（见 `SettingCoupling` 的 KDoc）。
            // 环的形状**跟随** `cover.shape`：圆形 → 圆环（黑胶）、
            // 方形 → 方框、圆角 → 圆角框。三种形状都有可见变化。
            default = false,
            summary = "在封面外围加一圈黑环（不遮住封面本身）。配「圆形 + 转动」是黑胶唱片效果。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.lock",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "锁定封面（悬浮窗形态）",
            // ★★★ 2026-10-17（用户指令：「**将「锁定封面（悬浮窗态）」设置项隐藏，
            //   清理那些连带该隐藏的设置项**」）：**本项下架**（标 `retired`）。
            //
            // ## 为什么下架（★ 不是"不好用"，是**它已无入口**）
            //
            // 同一天更早的指令「**隐去「词岛外观-悬浮窗」设置项**」已把
            // `shape.type` 的 `COVER_ONLY` 选项从设置面撤下（渲染代码保留）。
            // 而本项的语义**只在** `shape.type = "COVER_ONLY"` 下成立
            // ⇒ ★ 用户已无法选中那个形态 ⇒ **本项永远拨不到有效场景**，
            //   留着就是「**看起来能拨、拨了毫无反应**」的死设置
            //   （本项目反复记录过的那类误报形状）✗
            //
            // ## 下架口径（与既有 `retired` 完全一致，不另造机制）
            //
            // | 面 | 结果 |
            // |---|---|
            // | SPW 设置页 | ★ 不生成（`gen_preference_config.py` 过滤 `retired`） |
            // | 自绘设置窗 | ★ 不显示（`spwPageDescriptors` 过滤 `retired`） |
            // | 运行时 | ★ 读**默认值** `false`（`SettingsRuntime` 对 `retired` 项传 `null`）⇒ 永不锁 |
            // | 右键菜单 | ★ **本来就没有**「锁定 / 解锁」项（全仓 `grep` 核实：只有 `island.lock_position`） |
            //
            // ★ **保留 descriptor 而不是物理删除**：消费点仍在（`IslandAppearance.lockCover` /
            //   `IslandWindow` 的 `coverLockBlocksExpand` 调用链 / `IslandPolicy` 的两个纯函数），
            //   删键会让它们读到未知键并每次告警；且旧配置里可能已有 `cover.lock` 的值 ✓
            //   （理由与 `material.liquid.*` / `material.backdrop.*` 那批下架项**同一条**，
            //     见 [SettingDescriptor.retired] 的 KDoc）
            //
            // ## 连带清理（同一批指令的后半句）
            //
            // 「悬浮窗（封面态）」既已从设置面消失，**其余设置项的 summary 里那些
            //   『仅在悬浮窗下生效 / 无效』的条件从句也一并删掉** —— 它们指向一个
            //   用户再也选不到的形态，留着只会让人去找那个不存在的选项 ✓
            //   涉及 4 项：`island.hit.cover` · `layout.island.height.ratio_percent` ·
            //   `shape.corner_radius` · `cover.enabled`（详见各自的 2026-10-17 注释）。
            //
            // ── 2026-09-30 · 封面态（`recon/67`）的**全案唯一新键**（历史）────────
            //
            // ## 语义（recon/67 §3.5，v5/v6 定案）
            //   仅在 `shape.type = "COVER_ONLY"`（悬浮窗/封面态）下有意义：
            //     · **未锁** ⇒ 鼠标命中封面 = **展开**成完整词岛（正常按 hover 链走）；
            //     · **已锁** ⇒ 命中**不展开**，只在右上角**弹一下锁头标志**。
            //   ★ **解锁入口 = 右键菜单「锁定 / 解锁」+ 本设置项**（两条）。
            //     ⚠️ v6 明确**不采用**"点锁头标志解锁"—— 因为**点锁头标志 = 展开**，
            //        同一个控件不能两用（见 recon/67 §9-S）。
            //
            // ## ★★ 为什么这是"唯一新键"（用户 2026-09-29 00:5x 的纪律边界，逐字）
            //   > 「我指的是**已有同形态的设置项**（比如词岛背景，封面样式等等）不再设立独特的
            //   >   调整选项，而是使用通用控制项。**锁是新功能，不在其列**」
            //   ⇒ **"外观 / 形态"不该有自己的选项组；"新功能"才配拥有键**（recon/67 §14.1）。
            //   封面态其余**全部元素**都从既有键派生（背景/材质/边框/字体/`cover.*` 6 键…）
            //   ⇒ 见 recon/67 §14 的复用表，**零新键**。
            //
            // ## ⚠️ 落盘 ⇒ **进预设** ⇒ ★ 重启后仍保持上锁
            //   这是 v8 的定案（§9-U）。★ 后果已**如实报给用户**：若不合意，改成"不落盘"
            //   只需删掉本键、状态留在内存（`recon/67` §14.1 已写明）。
            //
            // ## ★★ 顺序约束（`recon/67` §9-S 的明文，BUG-113 前车之鉴）
            //   **先加本键、再加右键菜单项** —— 菜单若先引用本键，
            //   `MenuRegistrySyncProbe` §A 会抓悬空引用，而**失败形态是"点了没反应且不报错"**。
            //   ⇒ 本键已加；菜单项是否同批落地见施工记录。
            //
            // ## 默认值
            //   `false`（**不锁**）—— 与"默认值要稳"一致：封面态首次使用时**未被锁**，
            //   命中即展开（最符合直觉的那条）。★ 且它是**新键**，默认值不影响任何既有行为。
            default = false,
            summary = "【已下架 2026-10-17】仅在「外观形态 = 悬浮窗」时生效；该形态已从设置面撤下，" +
                "本项随之隐藏（值不再影响渲染，恒为未锁）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            // ★★★ 2026-10-17：**从用户可见的设置面移除**（理由见上方 KDoc）✓
            retired = true,
        ),
        SettingDescriptor(
            key = "main_lyric.color_mode",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.LIST,
            title = "主歌词颜色来源",
            default = "manual",
            options = listOf("manual" to "手动指定", "cover" to "跟随封面取色"),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color_mode",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词颜色",
            kind = SettingKind.LIST,
            title = "副歌词颜色来源",
            // 用户原话（2026-09-19）：「**歌词颜色跟随封面取色失效**」。
            //
            // ## 为什么它是"失效"而不是"没做"
            //
            // 主歌词色（`main_lyric.color_mode`）**一直有**这个键且工作正常；
            // 而**译文色只有 `manual`** —— 它的 4 根滑杆直接经 `mixerColor` 合成，
            // **从不查任何 mode**。于是用户把「歌词颜色来源」设成「跟随封面取色」后，
            // 主歌词变了色、**译文行不变** —— 看起来就是"跟随封面取色失效"。
            //
            // 这条是 2026-09-19 那轮**如实记录但未处理**的遗留
            // （当时的结论是"交用户定"）。用户现在明确报了，故补上。
            //
            // 默认 `manual`：与其它三个 `*_color_mode` 同一取舍 ——
            // 新增的颜色来源不该改变既有观感。
            default = "manual",
            options = listOf("manual" to "手动指定", "cover" to "跟随封面取色"),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        // ── 以下 5 项：词岛边框（2026-09-18 用户点名新增）─────────────────────
        //
        // 用户原话：「增加词岛**边线/边框**选项。」
        //
        // ## 为什么是 5 项而不是 1 项
        //
        // "加个边框"最少需要三个自由度：**要不要**（开关）、**多粗**（宽度）、**什么颜色**（颜色）。
        // 只有开关的话，用户得到的是一条写死的 1px 白线 —— 那和"没有这个选项"差别不大。
        //
        // ## 为什么颜色用 3 根 RGB 滑杆而不是 `COLOR` 类型
        //
        // 照**项目既有做法**：歌词三色已从 `COLOR` 改成 3 根 `SEEKBAR`
        // （见 `groupF()` 的注释块，用户原话「SPW 设置页中**我指名的 RGB 三色滑杆**」）。
        // 边框色与歌词色是**同一类"用户要能精确指定"的颜色**，故用同一套表达 ——
        // 两类颜色在设置页里的操作方式一致，用户不必学两种。
        //
        // ⚠️ **默认 `false`**：边框是**新增的视觉元素**，默认开着会改变所有既有用户的观感。
        //   与 `spectrum.progress_tint` / `spectrum.symmetric` 同一条纪律。
        // ═══════════════════════════════════════════════════════════════════════════
        // 动画组（2026-09-20 新增，3 项）
        //
        // 用户原话：「**我觉得我们可以引入动画开关，然后增加对CPU GPU性能高的动画效果，
        // 以大大提升UX，当然，用户在发觉性能开销过大时也可以关闭动画开关。
        // （引入的动画效果需要保证观感）**」
        //
        // ## 三条设计要点
        //
        // ① **总开关默认开** —— 否则用户看不到新效果，等于没做；
        //    而"关掉"这条路必须**真的回到改动前的行为**（不是"减弱"）。
        // ② **质量档同时决定"跑多少帧"与"开多少效果"** —— 不只是改帧率：
        //    低端机上"效果少但流畅"远好于"效果多但掉帧"。
        // ③ **时长可调** —— 有人喜欢干脆（50%），有人喜欢舒缓（200%）；
        //    它只改时长、不改效果数量，故与质量档正交。
        SettingDescriptor(
            key = "animation.enabled",
            // ★★★ 2026-10-17：**回到「动画」组**的「质量与帧率」细类（总开关组已撤销）。
            //   ⚠️ 它是**开关**，管的是"有没有动效"；而 `animation.style` 管的是
            //      "动效长什么样"。两者都在「动画」组，但**不是同一件事**（见各自 summary）。
            group = SettingGroups.ANIMATION,
            subGroup = "质量与帧率",
            kind = SettingKind.SWITCH,
            title = "启用动画",
            summary = "关闭后所有过渡与重效果立即消失。控件条的悬停淡入是既有行为，不受本开关影响。",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "animation.quality",
            group = SettingGroups.ANIMATION,
            subGroup = "质量与帧率",
            kind = SettingKind.LIST,
            title = "动画质量",
            // ⚠️ 2026-09-21：**帧率描述已从本项移出**（改由 `animation.frame_rate` 独立承担）。
            //    原文案写「标准 = 30 fps / 高 = 60 fps」，而新设置项也管帧率 ——
            //    两个设置项描述同一件事，用户会不知道该信哪个（本项目纪律 57：
            //    「文案承诺的 vs 代码实际做的」必须逐项一致）。
            summary = "决定**开启哪些动画效果**。标准 = 仅基础过渡；高 = 加歌词行退场；极致 = 再加频谱峰值保持。帧率由「动画帧率」单独设置。",
            default = "HIGH",
            options = listOf(
                "STANDARD" to "标准（仅基础过渡）",
                "HIGH" to "高（基础 + 歌词行退场）",
                "ULTRA" to "极致（全部 + 频谱峰值保持）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "animation.frame_rate",
            group = SettingGroups.ANIMATION,
            subGroup = "质量与帧率",
            kind = SettingKind.LIST,
            title = "动画帧率",
            /**
             * ## 这个设置项解决什么（用户 2026-09-21 提出）
             *
             * 用户原话：
             * > 「当前播放器播放歌曲动画能否帧率适配屏幕刷新率？我发现**多数人的屏幕刷新率
             * >   在 100 以上**，动画的刷新率不能太低。……新增一个适应 60 / 90 / 120 / 144 Hz
             * >   不同刷新率的档位。」
             *
             * 原实现只有 30 / 60 两档，且**绑在 `animation.quality` 上** ——
             * 在 144 Hz 屏上动画只有 60 fps，观感就是"该顺的地方不顺"
             * （用户报的「动效一闪而过 / 观感不好」里，帧率不足是结构性的一条）。
             *
             * ## 默认值为什么是 `AUTO`（而不是固定 60 / 120）
             *
             * 判据是「**普通用户的偏好**」：绝大多数用户不会主动去想"我的屏多少赫兹"，
             * 而 `AUTO` 的行为恰好是"什么都不用管，自动最好" ——
             * · 在 60 Hz 屏上 → 与**旧行为逐帧一致**（零变化，不冒进）；
             * · 在高刷屏上 → 自动变顺滑（正是这次要解决的问题）。
             *
             * 没有默认到 `144`：那会让 60 Hz 屏白烧 CPU（帧率高于屏幕刷新率是纯浪费，
             * 甚至因丢帧显得更差）。没有默认到 `60`：那等于这次的需求没做。
             *
             * ## 取不到刷新率时回退 60
             *
             * 见 `AnimationRuntime.frameIntervalFor` 的 KDoc（远程桌面 / 虚拟机上常见）。
             */
            summary = "动画帧率上限。自动 = 跟随显示器刷新率（推荐）。各档取「不超过该值的最高可达帧率」（显示器刷新率的整数分之一），实际值可能低于档位数字、但绝不会超出；CPU 占用偏高时可降到 60。",
            default = "AUTO",
            options = listOf(
                "AUTO" to "自动（跟随显示器刷新率）",
                "60" to "不超过 60 Hz（最省电）",
                "90" to "不超过 90 Hz",
                "120" to "不超过 120 Hz",
                "144" to "不超过 144 Hz（最顺滑）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "animation.duration_scale",
            group = SettingGroups.ANIMATION,
            subGroup = "时长",
            kind = SettingKind.SEEKBAR,
            title = "动画时长（%）",
            summary = "50 = 干脆利落，100 = 默认，200 = 舒缓，1000 = 放慢 10 倍。只改时长、不改效果数量；作用于词岛自身的过渡（显隐 / 封面 / 歌词行），菜单与控件条有各自的即时反馈节奏，不跟随。⚠️ 数值越大，动画持续重绘越久 —— 切歌等场景会出现数秒的「卡顿感」（实测 1000 时约 2.6 秒）。",
            default = 100,
            // ★ 2026-09-23：上限 200 → **1000** —— 用户原话「**目前已有的动效放慢 10x，供我检查效果**」✗
            //   旧上限 200% 拖不到 10 倍 ⇒ 这条要求**在界面上无法满足** ✗。
            //   ⚠️ 改这里必须**重跑 `tools/gen_preference_config.py`**（生成物侧 min/max 同步），
            //      否则 `RegistryProbe` 的「设置页顺序 / 值域一致」会红 ✗（本轮已实测踩过 ✗）。
            range = 50.0..1000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── `animation.frame_log`（每秒帧率日志）────────────────────────────────
        //
        // 用户原话（2026-09-28）：「**日志每秒打印帧率和实际ms，方便检查达成情况**」，
        // 并明确这是「**测试用**」⇒ **默认关**，验收时打开。
        //
        // ★★ **键名为什么在 `animation.` 命名空间下**（而不是我最初想的 `debug.`）：
        //   `SettingDomains.FRAME = setOf("animation.")` —— 帧域是**按命名空间派生**的
        //   （见 `SettingDomains` 的 KDoc，纪律 9：**变更域按命名空间派生，不按设置页分组**）。
        //   ⇒ 若键名取 `debug.frame_log`，改它**不进帧域** ⇒ 订阅体里
        //     `if (change.touchesAny(SettingDomains.FRAME)) applyAnimationSettings(fresh)`
        //     **不会执行** ⇒ 本开关**静默失效**（勾了没反应，且不报错）。
        //   ★ **原记**（我写第一版时）：「放 `debug.` 命名空间语义更清楚」；
        //     **实为**：语义清楚 0 分，静默失效 -100 分 —— 且这正是本项目已记过 3 次的形态。
        //   ⇒ 取 `animation.frame_log`：它报的**正是动画帧率**（语义成立），
        //     且**自动入帧域**（不碰 `SettingDomains` = 少一处将来会漂移的地方）。
        //
        // ## 它报什么（两条节拍，各一行）
        //
        // | 行 | 报的 | 消费点 |
        // |---|---|---|
        // | `帧率（动画帧）` | 词岛动画帧：实得 fps · 平均/最大帧间隔 ms · 达成率 | `IslandWindow.onFrameTick` |
        // | `帧率（L3采集）` | L3 采屏线程：实得 Hz · 平均/最大轮周期 ms · **交付数** · 采屏/模糊均 ms | `L3CaptureWorker.runOnce` |
        //
        // ★ 与既有 `FramePacingMonitor`（每 2400 帧 ≈40 s 报 p05/p50/p95/p99）**并存**：
        //   那条答"这一段的**分布**"，本项答"**此刻**每秒跑了几帧"（用户原话的"达成情况"）。
        //   两者的分工写在 `animation/PerSecondMeter.kt` 的类 KDoc 里（**唯一**说明处）。
        //
        // ## ⚠️ 为什么默认 `false` 而不是 `true`
        //
        // 行数密度是 **1 行/秒/节拍**（两条节拍 ⇒ 播放时约 2 行/秒）。
        // 宿主用固定大小的日志文件 + 轮转（见 `PluginLog`）⇒ 常开会**把有用的事件
        // 挤出轮转窗口**（比如那 20 条 `[L3]` 启动/已停记录）。这与"诊断要永久打印"
        // 那条纪律**不矛盾**：那条要的是"**出事时**能查到"，而本项是"**平时的**节拍读数"，
        // 两者用不同开关分开正是为了互不挤占。验收时打开即可，看完关掉。
        //
        // ## 它不影响任何判定（纪律：时间只作报告）
        //
        // 关与开对**画面与动画**逐帧一致（关掉时 `PerSecondMeter.noteInternal` 在
        // 最前面直接 `return null`，不累计、不建基线、不产生字符串）。
        // ⇒ 本项**不参与任何断言**，也不能被别的逻辑读作行为开关。
        SettingDescriptor(
            key = "animation.frame_log",
            // ★ 2026-10-18（**用户审核文案后**）：本项**不再出现在任何设置面** ——
            //   用户审核后从可见文案中删除（诊断项）。descriptor 保留 ⇒ 消费点仍能读到默认值/旧值 ✓
            retired = true,
            group = SettingGroups.ANIMATION,
            // ★ 2026-10-18：细类从「诊断」改为「质量与帧率」——
            //   本项已 `retired`，若保留独立的「诊断」细类会变成**空壳**（自检失败）✗
            //   它报的就是帧率 ⇒ 归到「质量与帧率」语义也正确 ✓
            subGroup = "质量与帧率",
            kind = SettingKind.SWITCH,
            title = "每秒帧率日志",
            summary = "每秒在日志里打印一次实得帧率与实际 ms（动画帧 + 身后窗口采集两条节拍），用来核对设置里的帧率/延迟是否真的达成。默认关 —— 常开会以约 2 行/秒的速度挤占日志轮转窗口。仅诊断用，不影响画面与动画。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            // ★ 2026-09-22：本项**保留**（用户原话：「**保留总开关**」），
            //   但**脱离「动画」组** → 第 0 组「总开关」，排在设置页最前面
            //   （用户：「像频谱、**动效总开关**之类的顶层项放到 SPW 配置页**前面的位置**」）。
            //
            // ## 它现在管什么（拆散之后）
            //
            // 用户原话：「动效风格总开关**管太多控件的动画了，不好细究，拆散开来，
            //          分到各自的组件组里，保留总开关**。」
            //
            // 拆散前：它经 `Motions.resolve(spec, style)` 同时改**两处**动效的演法 ——
            //   · `Motions.CONTROLS_APPEAR`（播放控件条出现）—— 消费点见 `IslandWindow.kt` 里
            //     `Motions.resolve(Motions.CONTROLS_APPEAR, …)`
            //   · `Motions.LYRIC_EXIT`（歌词行切换时旧行退场）—— 消费点见 `IslandWindow.kt` 里
            //     `Motions.resolve(Motions.LYRIC_EXIT, …)`
            //
            //   ⚠️ 2026-09-22：这里原先写的是**行号**（`IslandWindow.kt:5774` / `:6224`）。
            //     行号**每次别人编辑那个文件就过期**，实测本轮已经漂到 6181 / 6632 ——
            //     而过期的行号会把人指到**别的代码**上，比不写还坏。
            //     故改用**符号引用**（`Motions.resolve(…, …)` 这种片段是可以直接 grep 的）。
            //
            // （`AnimationRuntime.kt` 的 `Motions` KDoc 判据表写明：**只有这两条**
            //  `followsGlobalStyle = true`；
            //  词岛显隐 / 封面交叉淡化 / 菜单淡入是 `false` —— 它们**从来不受本项管辖**，
            //  故本轮**不给它们造子项**，否则就是"声明了设置却没有实现"的死设置。）
            //
            // 拆散后：新增两个**子项**，各自落在**自己组件的组**里：
            //   · `lyric.animation_style`（歌词组 ▸ 高亮与同步）
            //   · `controls.animation_style`（播放控件组 ▸ 触发）
            // 本项退化为**总覆盖**，语义见下。
            //
            // ## ★ 语义（二选一，这里选了"总覆盖"，并写死）
            //
            // | 本项取值 | 含义 |
            // | --- | --- |
            // | `PER_COMPONENT`（**默认**） | **不干预** —— 各组件用各自子项的风格 |
            // | `FADE` / `SLIDE` / `POP` | **总覆盖** —— 无视全部子项，所有受管动效都用这一档 |
            //
            // 判据是 [AnimationStyleResolver.effective]：**本项是具体风格时，子项一律被覆盖**。
            // 子项的「跟随总开关」（`FOLLOW`，LIST 第 0 项）是**改回去的唯一入口** ——
            // 没有它，"拆散"就等于没拆（用户被锁死在总开关上）。
            //
            // ## 为什么默认 `PER_COMPONENT` 不改变老用户观感
            //
            // 子项默认也是 `FOLLOW`；总开关不干预时 `FOLLOW` 落回**该动效的出厂风格**
            // （两条都是 `SLIDE`，见 `Motions.CONTROLS_APPEAR.style` / `LYRIC_EXIT.style`）——
            // 与拆散前 `animation.style = "SLIDE"` 的默认行为**逐帧一致**。
            // 而**已存过配置**的用户：其 `animation.style` 若是 `FADE`/`SLIDE`/`POP`，
            // 本项仍是具体风格 → 仍是总覆盖 → 行为与拆散前一致（**无迁移**，键名不变）。
            //
            // ⚠️ **键名刻意不变**（仍是 `animation.style`）—— 改名会让老用户的配置静默失效
            //   （`SettingsStore` 读不到就回退默认），而这次改的是**语义**不是身份。
            key = "animation.style",
            // ★★★ 2026-10-17：**回到「动画」组**，落在**新细类「动效风格」** ——
            //   它不属「质量与帧率」（那是画质档），也不属「时长」；单独一个细类
            //   才能让「覆盖全部组件的风格」在菜单里**自成一段**（分隔线表达边界）✓
            group = SettingGroups.ANIMATION,
            subGroup = "动效风格",
            kind = SettingKind.LIST,
            title = "动效风格（总开关）",
            summary = "选具体风格 = **覆盖全部组件**（忽略各组件自己的设置）；选「各组件各自设置」= 总开关不干预，" +
                "由「歌词动效风格」「播放控件动效风格」分别决定。",
            default = AnimationStyleResolver.TOTAL_PER_COMPONENT,
            options = listOf(
                AnimationStyleResolver.TOTAL_PER_COMPONENT to "各组件各自设置（总开关不干预）",
                "FADE" to "淡入（覆盖全部组件）",
                "SLIDE" to "滑入（覆盖全部组件）",
                "POP" to "弹出（覆盖全部组件）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★ 2026-09-20 新增（用户点名要求做成开关）：
            //   「被门控的控件在被触碰时应自动解门控」这个行为**本身**要能关。
            //
            // ## 它解决什么
            //
            // 用户报「我要求的实时更新设置变化，怎么我在调色时词岛里的歌词颜色不变?」——
            // 真因是他去调「主歌词描边色」，而 `main_lyric.outline.enabled = false`：
            // **描边根本没画**，调什么都不会变。
            // 按纪律「被门控的控件在被触碰时自动解门控（判据：用户意图是否唯一）」，
            // 打开颜色窗时会**一并把对应开关打开**。
            //
            // ## 为什么必须是设置项而不是写死
            //
            // 「自动替我改另一个设置」是**有副作用的便利**：
            // 有人希望它自动（省一步），也有人**不希望自己的开关被悄悄改动**。
            // 故默认 `true`（保持本轮之前的行为），可关。
            //
            // ## 关闭后的行为
            //
            // 只写颜色、**不碰**任何 `enabled`。用户仍能手动打开那个开关。
            // ⚠️ 注意：这不是「静默吞掉输入」（颜色照写），只是「不多做一件事」。
            // ## ★★★ 2026-10-17：**回到「菜单与设置」组**的「行为」细类
            //
            // 用户原话：「**取消总分组，将其下发回各自的组。**」
            // ⇒ 2026-09-22 那次"判据放宽为跨组件、提到最前"的处置**被撤销**；
            //   本项回到它 2026-09-22 之前的归属（`MENU_SETTINGS · 行为`）。
            //
            // ⚠️ **不要再按"跨组件"把它搬走** —— 那个特例组已不存在；
            //   一级判据重新是「**画面上的哪个东西**」，而本项属于"设置界面自身的行为"。
            key = "settings.auto_ungate_on_touch",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "行为",
            kind = SettingKind.SWITCH,
            title = "调整颜色时自动打开被关掉的功能",
            default = true,
            phase = SettingPhase.P1,
            // 颜色预览本身是 IMMEDIATE；本项只影响「要不要顺带写另一个键」，
            // 取 THROTTLED 与同组的交互类开关一致。
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "island.border.enabled",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SWITCH,
            title = "显示边框",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ⚠️ 键名从 `island.border.width_px` 改成 `...width_tenth_px`（2026-09-18 真机回归）。
            //
            // ## 原记与实为
            //
            // 原记：`width_px`（Int，范围 0..8）—— 以为"像素"是整数就够。
            //
            // 实为：**SPW 滑杆写盘是 Float**（本项目已知事实，见 `differsFromRaw` 的 KDoc）。
            //   用户把滑杆拖到 **0.5** 时，宿主存 `0.5`，而 `coerceInt` **截断成 0** ——
            //   于是"设置小于 1.000 的值时**直接不显示边框**"（用户原话）。
            //   而且**不报错**：滑杆位置在 0.5，实际画出来是 0px。
            //
            // ## 现做法：用"十分之一像素"作单位
            //
            // 整数键 + 单位放大 10 倍 → `0.1 px` 的分辨率，且**不依赖宿主是否写 Float**
            // （`coerceInt` 对 `5.0` 与 `5` 都得 5）。`title` 里标明单位，
            // 避免用户看到 "10" 以为是 10px。
            //
            // **这就是项目纪律"名字说谎比没名字更糟"的处置**：语义变了，键名必须跟着变。
            key = "island.border.width_tenth_px",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框宽度（0.1 像素）",
            summary = "仅在开启「显示边框」时生效。10 = 1.0 像素。",
            // 范围 `0..80`（= 0.0..8.0 px）：0 等效于不画（但开关仍开着，
            // 便于"先调宽再开"）；8px 已是"粗框"的上限，再粗会明显吃掉歌词区内边距。
            default = 10,
            range = 0.0..80.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.mode",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.LIST,
            title = "边框样式",
            summary = "硬边：画一条实线边框。",
            // ★★ 2026-09-29（用户拍板 D2/D3/D1）：键名走 `island.` 命名空间（与既有两条边框键一致）；
            //    新增**档**而非改现有观感 ⇒ **默认 `SOLID` ⇒ 开箱零变化**。
            //    ★ D1 = **B（不外扩）** ⇒ 淡出带**只画在形状内部**（不外扩 ⇒ 不需要预乘蒙版，
            //      `MaterialLayers` 的"用 paint 不用 clip"原则直接适用）。
            default = "SOLID",
            options = listOf(
                "SOLID" to "硬边（实线边框）",
                // ★ 2026-10-18（用户审核文案后）：**「向外渐变」档已移除** ——
                //   用户定稿的选项表只有「硬边 / 溶边」两档。
                //   ★ 渲染层的 `FADE_OUT` 分支保留（旧配置读到该值会由 `coerce` 回退默认）✓
                // ★★★ 2026-10-12（**BUG-182**）：★ **溶边** —— 最外一圈整体擦成透明 ✓
                //   ★ 仅当「背景不透明度 ≠ 0」时生效（背景全透明时无物可溶 ✓）
                "EDGE_DISSOLVE" to "溶边（最外一圈整体淡化，无实线；需背景不透明）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ★★★ 2026-10-13（**BUG-186** · 用户指令：「**既然与边线宽度解耦，引入新键给用户调节渐变范围**」）
        //
        // ## 为什么需要它
        //
        // ★ BUG-185 把「溶边」的过渡带从**边框宽度**解耦了（边框宽度上限只有 8px ⇒
        //   过渡带最多 8 个像素 ⇒ alpha 从 178 降到 0 只有 8~9 级 ⇒ **台阶 ~22** ✗）。
        // ★★ 解耦之后带宽改由 `dissolveBandPx` 给（= 岛体半高），**但用户无从调节** ✗
        // ⇒ ★ 本键把那个量**交给用户**（A0 定位：「**能，且在设置里能切**」）✓
        //
        // ## 为什么单位是「岛体高度的 %」而不是像素
        //
        // ★ 岛体高度**随字号变**（`layout.island.height.follow_font`）⇒
        //   若用像素，用户换字号后要重调；★ 用百分比 ⇒ **自动跟着缩放** ✓
        //
        // ## ★★ 为什么上限是 50%（不是 100%）
        //
        // ★ 过渡带是**从每条边向内**量的：上边一条、下边一条 ⇒ 带宽 > 半高时**两条带重叠** ✗
        // ★★ 而溶边是 `DstOut`（**擦除**）⇒ 重叠处**连乘擦除**（与 SRC_OVER"混色"相反 ✗）⇒
        //   中线附近被擦两次 ⇒ ★ 剖面**先升后降**（U16 实测：带宽 40px / 岛高 56px 时
        //   `…119,124,89,64,66,68,69,70,71,72,72,72,72,71,69,64,60` —— 到中线就塌 ✗✗）
        // ⇒ ★ **50% 是几何上限**（恰好两条带在中线相接、不重叠）✓
        // ★ 故 `range` 的上限就是 50，且 `dissolveBandPx` 里**再夹一次**（纵深防御）✓
        SettingDescriptor(
            key = "island.border.dissolve_band_percent",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "溶边渐变范围（岛体高度的 %）",
            summary = "仅在边框样式选「溶边」时生效。50 = 渐变带从边缘一直铺到岛体中线" +
                "（最平滑）；值越小边缘越硬，0 = 完全不渐变。",
            // ★ 默认 50 = **BUG-185 已交付并获认可的那个观感** ⇒ 加键**零变化** ✓
            default = 50,
            range = 0.0..50.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.color_mode",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.LIST,
            title = "边框颜色来源",
            summary = "仅在开启「显示边框」时生效。",
            // 用户诉求（2026-09-18）：「**允许词岛边框从封面取色**」。
            //
            // 与 `background.color_mode` / `spectrum.color_mode` 同一套词汇
            // （`manual` / `cover`）—— 三个"颜色来源"用同一组选项值，
            // 用户在设置页看到的语义一致，不必各记一套。
            // 默认 `manual`：新增的颜色来源不该改变既有观感（与频谱/背景同取舍）。
            default = "manual",
            options = listOf("manual" to "手动指定", "cover" to "跟随封面取色"),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.border.color.r",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框色 R",
            summary = "仅在开启「显示边框」时生效。",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.color.g",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框色 G",
            summary = "仅在开启「显示边框」时生效。",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.color.b",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框色 B",
            summary = "仅在开启「显示边框」时生效。",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★ 2026-10-03（⑱ 时钟）**新增键** —— 用户拍板逐字：
            //   「**5段小频谱的位置用来放时钟，可以选择切换词岛右侧，歌词右侧的组件类型**」
            //   追问确认：**「一个插槽，只切组件类型」** + 清单 **「小频谱 / 时钟 / 后面的环形·放射频谱」**。
            //
            // ## 它是什么
            //   词岛**右端**有一块**固定宽**的区域（`RIGHT_SLOT_WIDTH_PX` = 封面宽 56 px，
            //   2026-10-03 由 35 提到 56 ⇒ 与左端封面**同宽 ⇒ 两端对称**）。
            //   本键决定那块里**放什么组件**。★ 位置固定 ⇒ **只有一个维度**（组件类型）。
            //
            // ## ★★ 迁移口径（`migration/80` §2.2）—— 老配置**逐像素不变**
            //   老配置表达"右端有频谱"用的是 `spectrum.anchor == "RIGHT"`。
            //   ⇒ **本键默认 `NONE`**（否则从没用过 RIGHT 档的用户会凭空多出一块插槽 =
            //     **开箱观感变化**，违反"默认值要稳"）。
            //   ⇒ 迁移在**消费点**做：`anchor == "RIGHT"` 且本键为 `NONE` ⇒ **视为 `SPECTRUM`**。
            //   ⚠️ 已知代价（如实记）：**要彻底关掉插槽，得把 `spectrum.anchor` 也改回「贴底边」**
            //     —— 因为本键没有"用户是否显式设过"这个状态。留待 ①b 收口时再看要不要加。
            key = "island.right_slot.component",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.LIST,
            title = "右端插槽内容",
            summary = "词岛右端那块固定宽的区域里放什么。「关闭」= 留空（歌词区不再让位）；" +
                // ★ 2026-10-04（⑱ 字形风格）：原写「（七段数码管）」—— 那**只描述了三种字形之一**
                //   ⇒ 用户会以为"时钟只能是七段"。改成"字形由 `clock.style` 另选"。
                //   ★ 这是"注释/文案与事实相反"的又一现场（本项目已第 N 次）。
                "「小频谱」= 5 根细条（颜色可随音量流光）；「放射状频谱」= 从中心向外辐射的条"
                + "（★ 本档下插槽会变宽：宽 = 词岛高）；「时钟」= 当前时间" +
                "（字形由「时钟字形」另选：七段 / 点阵 / 像素风）。",
            default = "NONE",
            options = listOf(
                "NONE" to "关闭",
                "SPECTRUM" to "小频谱（5 根细条）",
                // ★ 2026-10-04：**`RADIAL` 档已落地** —— 原注释写「等真做出来再加」，
                //   ★ 现在真做出来了 ⇒ 加档（这正是那条纪律的**正向应用**：
                //   "选项数 = 真实状态数" ⇒ 能做就必须给档，否则用户**选不到**）。
                //
                // ★★ 用户 2026-10-04 指令：「**新增的这两个频谱样式是归属于五条小频谱的
                //   那个词岛右侧插槽的**」+ 拍板「**只加一档（固定一种）**」。
                //   ⇒ ★ 取**放射**（内半径 0 ⇒ 条从圆心射出）—— 与档名一致，
                //     且它在**方形区域**里最"满"（环形中心空着，小尺寸下会显得空荡）。
                //
                // ★ 几何与业界对照见 `render/SpectrumGeometry.kt` 的 `SpectrumPolarSpec` KDoc。
                //   ⚠️ 本档下**插槽宽 = 词岛高**（用户拍板：自适应）⇒ 词岛会比 SPECTRUM 档**更宽**。
"RADIAL" to "放射状频谱",
"RING" to "环状频谱",
"CLOCK" to "时钟",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★★ 2026-10-13（**U16**）**新增键** —— 用户原话（逐字）：
            //
            // > 「**新增5条小频谱的频谱条数选择（4和6根）**」
            //
            // ## 口径
            //
            // 右端插槽选「小频谱」时，**画几根条**。三档：**4 / 5 / 6**。
            // ★ 默认 **5** —— 与既有观感**逐像素相同**（`RIGHT_BAR_COUNT` 一直是 5），
            //   故"新增本键"本身**零回归** ✓
            // ★ 用户点名要的是 **4 和 6**；**5 保留为默认档**（否则"存量用户升级后
            //   条数凭空变化"= 开箱观感变化，违反"默认值要稳"）。
            //
            // ## 为什么用 `LIST` 而不是 `SEEKBAR`
            //
            // 条数是**离散小集合**（4/5/6），滑杆会造出"5.7 根"这种无意义值；
            // ★ 与同细类的 `island.right_slot.component` **同一个 kind**（LIST）✓
            //
            // ## ⚠️ 本键**只对 `SPECTRUM` 档生效**
            //
            // `RADIAL` / `RING` 的条数由**内缘弧长**推（见 `polarBarCount` 的 KDoc），
            // `CLOCK` / `NONE` 不画频谱 ⇒ ★ 选那三档时本键**空转**（不报错、不生效）。
            // ★ 这是**有意的不加前置**（与 `clock.hour_format` 同款）：
            //   加"前置条件"需要宿主支持，而"选错档位时该键无效"是**可自解释**的 ✓
            key = "island.right_slot.spectrum_bars",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.LIST,
            title = "小频谱条数",
            summary = "右端插槽放「小频谱」时画几根条。仅在该档生效" +
                "（「放射状 / 环状频谱」的条数由几何自动推导，不受本项影响）。",
            default = "5",
            options = listOf(
                "4" to "4 根",
                "5" to "5 根（默认）",
                "6" to "6 根",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★★ 2026-10-15（**§3.75**）**新增键** —— 用户诉求（逐字）：
            //
            // > 「**开放对插槽频谱的独立选色**」
            //
            // ★ 用户拍板（2026-10-15）范围 = **完整取色模式**（不是"只加一个自选色"）。
            //
            // ## 口径：主频谱那五档 **+ `follow`**
            //
            // `follow`（**跟随主频谱**，默认）/ `manual` / `cover` / `level` /
            // `cover_flow` / `cover_camo` —— 与 `spectrum.color_mode` 的选项表
            // **逐字同词汇**（★ 见 `CoverPalette` 的常量），只多一个 `follow` ✓
            //
            // ★ **默认 `follow` ⇒ 开箱逐像素不变**：改动前插槽恒用主频谱的色，
            //   `follow` 就是那件事的**具名化** ⇒ 存量用户观感零变化 ✓
            //   （若默认取 `manual`，用户升级后插槽会凭空变成 `#84D8BC` ✗）
            //
            // ## ★ 为什么是**独立键**而不是复用 `spectrum.color_mode`
            //
            // 复用的话"改插槽"必然"改主频谱" ⇒ 与诉求（**独立**选色）**正面冲突** ✗
            //
            // ## ⚠️ 本键**只对插槽的频谱三型生效**
            //
            // `SPECTRUM` / `RADIAL` / `RING` 三档吃它；`CLOCK` / `NONE` 不画频谱 ⇒
            // ★ 选那两档时本键**空转**（不报错、不生效）—— 与 `island.right_slot.spectrum_bars`
            // **同一条"有意不加前置"**（加前置需宿主支持；"选错档位时该键无效"可自解释）✓
            //
            // ★ 唯一出口 = `window/IslandAppearance.kt` 的 `rightSlotSpectrumColorModeFor(wire)`
            //   （未知值 / 空串 / `null` ⇒ **回落 `follow`**）✓
            key = "island.right_slot.spectrum_color_mode",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.LIST,
            title = "插槽频谱颜色来源",
            summary = "右端插槽的频谱用哪个颜色来源。默认「跟随主频谱」——" +
                "改成别的档位后，插槽与主频谱**互不影响**。",
            default = "follow",
            options = listOf(
                "follow" to "跟随主频谱（默认）",
                "manual" to "手动指定",
                "cover" to "跟随封面取色",
                "level" to "随音量变化",
                "cover_flow" to "封面多色流光",
                "cover_camo" to "封面迷彩碎色流光",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★★ 2026-10-15（**§3.75**）**新增键**：插槽频谱在 `manual` 档下的**自选色**。
            //
            // ★ 与 `spectrum.color`（主频谱的手动色）**是两个键** —— 这正是"独立选色"的落点：
            //   改插槽的手动色**不得**改主频谱，反之亦然 ✓
            // ★ 默认 `#84D8BC` 与 `spectrum.color` 的默认**逐位相同** ⇒
            //   「切到 manual 但没改过颜色」时插槽与主频谱**同色**（不会突然变一个色）✓
            key = "island.right_slot.spectrum_color",
            // ★ 2026-10-18：**不标 `retired`** —— 它本来就在 `SELF_DRAWN_KEYS` 里
            //   （不上 SPW 设置页，由取色按钮写入）⇒ 再标 retired 会被**重复计算**
            //   （`149 − 27 − retired` 里减两次）✗
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.COLOR,
            title = "插槽频谱颜色",
            summary = "插槽频谱颜色（插槽频谱颜色来源选「手动指定」时生效）",
            default = 0xFF84D8BC.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,
        ),
        SettingDescriptor(
            // ★★★ 2026-10-16（**台账 §3.77-F**）**新增键** —— 用户诉求（逐字）：
            //
            // > 「**允许调整环形频谱的跳动幅度和范围**」
            // > 追问后拍板：「**径向范围（内外半径可调）**」+「**给放射+环形两档**」
            //
            // ## 口径：把"极坐标的两条半径"从**几何硬定**变成**可调**
            //
            // ★ 改前：内半径 = 档位常量（`RADIAL` 0 / `RING` 0.55），
            //   外半径 = `min(w,h)/2`（**区域短边的一半**）⇒ ★ **用户改不了** ✗
            // ★ 改后：**外半径**走一个**两档共用**的百分比；**内半径按档各一个键**
            //   —— ★★ 为什么内半径**不能共用**：两档的出厂值不同（0 vs 55），
            //     共用一个键就必然让其中一档的默认值变掉 ⇒ **必有一档回归** ✗
            //     （★ 判据：**"默认值不同"的两个档，不能共用一个带默认值的键**）
            //
            // ★ 三个键的出厂值都取**改动前的几何值** ⇒ **开箱逐像素不变**（零回归）✓
            //
            // ## ⚠️ 本键只对**极坐标两档**（`RADIAL` / `RING`）生效
            //
            // `SPECTRUM` / `CLOCK` / `NONE` 不走极坐标 ⇒ 选那三档时本键**空转**
            // （不报错、不生效）—— 与 `island.right_slot.spectrum_bars` 的
            // 「有意不加前置」**同一条**（加前置需宿主支持；"选错档位时该键无效"可自解释）✓
            //
            // ★ 唯一出口 = `render/SpectrumGeometry.kt` 的 `spectrumPolarFor(...)` 新增的
            //   三个入参（`radialInnerPercent` / `ringInnerPercent` / `outerPercent`）✓
            key = "island.right_slot.radial_inner_percent",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.SEEKBAR,
            title = "放射·内半径（%）",
            summary = "「放射状频谱」条形从多远处开始（占外半径的百分比）。" +
                "0 = 交于圆心；调大后中心会空出一块（变成环形观感）。",
            default = 0,
            range = 0.0..85.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★★ 2026-10-16（**台账 §3.77-F**）：环状档的**内半径**（= 中心空洞）。
            // ★ 出厂值 **55** = 改动前的 `SpectrumPolarSpec.CIRCULAR_INNER_RATIO` ⇒ 零回归 ✓
            key = "island.right_slot.ring_inner_percent",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.SEEKBAR,
            title = "环状·内半径（%）",
            summary = "「环状频谱」中心空洞的大小（占外半径的百分比）。" +
                "调小 = 环变厚、中心洞变小。",
            default = 55,
            range = 0.0..85.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★★ 2026-10-16（**台账 §3.77-F**）：**两档共用**的**外半径**。
            // ★ 出厂值 **100** = 改动前的 `min(w,h)/2` ⇒ 零回归 ✓
            key = "island.right_slot.polar_outer_percent",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.SEEKBAR,
            title = "极坐标·外半径（%）",
            summary = "「放射状 / 环状频谱」条形能伸到多远（占可用半径的百分比）。" +
                "100 = 撑满插槽；调小 = 整体缩小。",
            default = 100,
            range = 10.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★ 2026-10-03（⑱ 时钟 · ③c）**新增键** —— 用户：「**12/24时制切换别忘记做**」。
            //
            // ## 口径：`H24` 默认（补零）/ `H12`（**不补零**，`0` ⇒ `12`）
            //   ⇒ ★ 见 `render/ClockText` 的 KDoc：**12 制文本恒 ≤ 24 制宽**
            //     （`12:34` 比例 5.55 vs `1:05` 比例 4.25）⇒ **两档都放得下**。
            //
            // ## ★★★ AM/PM **不显示**（2026-10-10 更正：原写"调用方画小标记"）
            //
            // ★ **原记**（本行上一版逐字）：「AM/PM 由 `ClockText.isPm` **单独返回**，
            //   调用方画**小标记**，**不占文本宽**」——
            //   ★★ 而**没有任何绘制代码**（`isPm` 只被 KDoc 与探针引用）⇒
            //      ★ 用户切 12 小时制只看到 `h:mm` ⇒ ★ **那是写给用户的虚假承诺** ✗
            //      （★ 审计 v4 · A-1，P0：文案与事实相反）
            //
            // ★★ 两条硬约束（都实测过，故"补绘制"这条路是死的）：
            //   ① 若 AM/PM 占第 6 个字形 ⇒ 比例 6.85 ⇒ 56 px 下段厚 **1.31 px < 可读下限 1.5**
            //      ⇒ **放不下**（`SevenSegmentProbe` ⑨″ 已钉）；
            //   ② ★★ 2026-10-10 实现时才发现：**字形渲染器只支持 `0-9` 与 `:`**
            //      （`SevenSegment.maskOf` / `DotMatrix.maskOf` 的 `else -> -1`）
            //      ⇒ ★ **字母画不出来** —— 实测 `totalWidthRatio("PM") == 0`
            //      ⇒ 想画必须扩**三张**字形真值表，而**七段上 `M` 没有标准写法** ✗
            //
            // ⇒ ★★★ **用户 2026-10-10 拍板：不显示上下午，把承诺从文案里删掉** ✓
            //   （★ 修法 = **改文案**，而不是"补一个半吊子的绘制" —— 后者会造成档间不一致）
            //   ★ 中间那版"补标记"的实现已**整体回退**（`render/ClockStack.kt` 已删）✓
            key = "clock.hour_format",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.LIST,
            title = "时钟时制",
            summary = "右端插槽放「时钟」时，时间用 24 小时制还是 12 小时制。" +
                "12 小时制不补零（如「9:05」）。",
            default = "H24",
            options = listOf(
                "H24" to "24 小时制",
                "H12" to "12 小时制",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★★ 2026-10-03（⑱ 时钟 · **⑥ 字形风格**）**新增键** —— 用户原话（逐字，2026-09-28）：
            //   「**七段数码管、点阵、像素风的时间显示样式**」
            //
            // ## 为什么现在才加（如实记）
            //   2026-10-03 只做了「七段数码管」一档 ⇒ 当时**刻意没有本键**。
            //   本项目纪律：**选项数 = 真实状态数**（给了档但选了没反应 = 最多次的缺陷形态）。
            //   ⇒ 三档都真能画之后，本键才出现。
            //
            // ## 三档的几何实现
            //   `SEGMENT` → `render/SevenSegment.kt`（既有）·
            //   `DOT` / `PIXEL` → `render/DotMatrix.kt`（**共用同一张 5×7 真值表**，
            //   区别在"一格画成什么"：八边形（有缝）vs 正方形（重叠））。
            //   ★ 两者都由门面 `render/ClockGlyphs.kt` 收口 ⇒
            //     **真机与预览两个绘制点都只调门面** ⇒ 不可能"只改一处"（BUG-149/150 的根治）。
            //
            // ## ★★ 布局不变量：切档**不会重新排版**
            //   三档**共用同一套宽度模型**（数字 `1.0` · 冒号 `0.35` · 字距 `0.30`）
            //   ⇒ `HH:MM` 在三种风格下占据**同一个包围框** ⇒ 用户来回切时看到的是"字变了"，
            //   而**不是"整块跳了一下"**。★ 由 `ClockGlyphProbe` 断言（三档 `totalWidthRatio` 逐值相同）。
            //
            // ## ★ 默认 `SEGMENT` ⇒ **老配置零回归**
            //   （本项目纪律「默认值要稳」：默认档必须是"改动前唯一能画的那一档"。）
            key = "clock.style",
            group = SettingGroups.ISLAND,
            subGroup = "右端插槽",
            kind = SettingKind.LIST,
            title = "时钟字形",
            summary = "右端插槽放「时钟」时，时间用什么字形画。" +
                "「七段数码管」= 经典数字钟；「点阵」= LED 点阵屏（点之间有缝）；" +
                "「像素风」= 方块位图字（方块连成笔画）。",
            default = "SEGMENT",
            options = listOf(
                "SEGMENT" to "七段数码管",
                "DOT" to "点阵",
                "PIXEL" to "像素风",
            ),
            phase = SettingPhase.P1,
            // ★ 切档要**立刻可见**（用户就是在挑字形，不是调数值）⇒ IMMEDIATE。
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "spectrum.color_mode",
            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.LIST,
            title = "频谱颜色来源",
            default = "manual",
            options = listOf(
                "manual" to "手动指定",
                "cover" to "跟随封面取色",
                // ★★ 2026-10-02 新增第三档（用户指令：「**颜色需要有渐变流光效果**」，
                //   并明确排除"x/y 轴渐变（缺乏新意）"）。
                //
                // ★ 语义 = 业界 [audioMotion] 的 **`Level`** Bar Color Mode：
                //   「**按当前振幅取色 —— 音量越高颜色越「烫」**」。
                //
                // ★★ 为什么它就是用户要的"流光"，而**不是** x/y 轴渐变：
                //   · x/y 轴渐变 = **几何方向上的**颜色过渡（用户已排除）；
                //   · 本档 = **振幅方向上的**颜色过渡 —— 颜色随**音乐本身**流动，
                //     安静段暗而灰、峰值亮而饱和。
                //   ⇒ ★ **零额外帧开销**（颜色只是"这一帧振幅"的函数，不需要帧驱动）。
                //
                // ★ 派生规则见 `render/SpectrumLevelColor.kt`（纯函数，探针可断言）。
                // ⚠️ 默认仍是 `manual` ⇒ **开箱逐像素不变**。
                "level" to "随音量流光",
                // ★★★ 2026-10-11（⑰「封面多色流光」· **频谱侧落地**）：
                //   用户指令：「**继续完善从封面取色机制，从取单色扩展为取多色
                //   （渐变/流光基底），并应用到那些从封面取色的呈现上
                //   （主副频谱，小插槽三型频谱）**」。
                //
                // ★ 语义 = 把 `CoverColorExtractor.palette(image, n)` 取到的**多个**封面色
                //   沿**柱序**铺成一条连续渐变（相位可推进 ⇒ 流光）。
                //
                // ★★ 与既有 `cover` 档的区别（**这是加档而不是改档的全部理由**）：
                //   · `cover`  = 取**一个**主色当强调色（`brightFrom`：保色相 / 压饱和 / 拉满明度）
                //   · 本档      = 把**整张封面的色彩**铺成渐变（多色，不再只用一个色相）
                //   ⇒ ★ 直接改 `cover` 会**悄悄改掉存量用户的观感**（本项目硬纪律禁止）
                //
                // ★ 取色规则见 `render/SpectrumFlowColor.kt`（纯函数，探针可断言）。
                // ★ 三型频谱（主 / 副 / 插槽 SPECTRUM·RADIAL·RING）**共用同一实现** ✓
                // ⚠️ 默认仍是 `manual` ⇒ **开箱逐像素不变**（"加档不动既有观感"纪律）✓
                "cover_flow" to "封面多色流光",
                // ★★★ 2026-10-14：**封面迷彩碎色块流光** —— `cover_flow` 的**同族新档**。
                //
                // 用户指令（逐字）：「多色流光目前的呈现是**竖色条水平向右**，能否做到
                //   类似**迷彩样式的碎色块**，在呈现上实现**无规则的颜色出现规律**水平向右？」
                //
                // ★ 语义 = 把封面的多色铺成**离散的、宽度不等的色块**（柔边），相位推进 ⇒
                //   **碎色块水平向右流动**（"迷彩"观感）。
                //
                // ★★ 与 `cover_flow` 的唯一区别 = **"色块"vs"连续渐变"**：
                //   · `cover_flow` = 沿柱序**连续插值**（一条平滑彩色带）；
                //   · 本档          = **离散热色块**（宽不等、色号哈希给，无规则）。
                //
                // ★★ 为什么**加档而不是改档**（与 `cover_flow` 加档时同一条理由）：
                //   直接改 `cover_flow` 会**悄悄改掉存量用户的观感**（本项目硬纪律禁止）✗
                //   ⇒ 两档并存，用户在设置里自己切 ✓
                //
                // ★ 取色规则见 `render/SpectrumFlowColor.kt`（纯函数，探针可断言）。
                // ★ 三型频谱（主 / 副 / 插槽 SPECTRUM·RADIAL·RING）**共用同一实现** ✓
                // ⚠️ 默认仍是 `manual` ⇒ **开箱逐像素不变**（"加档不动既有观感"纪律）✓
                "cover_camo" to "封面迷彩碎色流光",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        // ── 2026-09-17 新增 3 项（用户点名要的）────────────────────────────
        //
        // 用户原话：「频谱**根据封面取色也没有做到**，**自行设定颜色也没做到**，
        //   频谱**相对歌词框底边/顶边高度**也没做到。」
        //
        // 前两条：`spectrum.color_mode = cover` 此前**静默回退**到手动色
        //   （根因是封面图只在 `cover.enabled` 时才加载，已修，见 `coverImageFor`）；
        //   而"手动色"此前**根本没有键** —— `manual` 档用的是硬编码常量
        //   `MANUAL_SPECTRUM_COLOR`，用户改不了。故补 `spectrum.color`。
        //
        // 第三条：频谱此前**恒占满整个歌词区高度**（靠渐变淡出）。
        //   用户要的是"相对歌词框**底边或顶边**的高度"→ 拆成
        //   `spectrum.anchor`（贴哪条边）+ `spectrum.height_pct`（占歌词区高度的百分比）。
        SettingDescriptor(
            key = "spectrum.color",
            // ★ 2026-10-18：**不标 `retired`** —— 它本来就在 `SELF_DRAWN_KEYS` 里
            //   （不上 SPW 设置页，由取色按钮写入）⇒ 再标 retired 会被**重复计算**
            //   （`149 − 27 − retired` 里减两次）✗
            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.COLOR,
            title = "频谱颜色",
            // 默认 #84D8BC —— 与旧硬编码常量 `MANUAL_SPECTRUM_COLOR` **逐位相同**，
            // 故本项接线后**开箱观感零变化**（只是从"改不了"变成"能改"）。
            default = 0xFF84D8BC.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,
            // ⚠️ `summary` 在 `edittext` 上有**功能作用**（不只是说明）——
            // 宿主弹输入框时标题取 `summary ?: key`，没有它用户会看到**裸键名**。
            // 见 BUG-009 的定案。
            summary = "频谱颜色（频谱颜色来源选「手动指定」时生效）",
        ),
        SettingDescriptor(
            key = "spectrum.anchor",
            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.LIST,
            title = "频谱贴边",
            // 默认 BOTTOM：与改动前一致（柱子的底边贴着歌词区底部往上长）。
            default = "BOTTOM",
            options = listOf(
                "BOTTOM" to "贴底边",
                "TOP" to "贴顶边",
                // ★★ 2026-10-02 新增第三档（用户指令：「**实现那个总账里的词岛右侧的小频谱**」）
                //   —— 台账 `migration/未达成项可行性评估-2026-09-26.md` §2 ⑧ 的第三种形态
                //   「**五根细条 + 词岛右端**」。
                //
                // ★ 与前两档**根本不同**：前两档是"频谱占满整个歌词区，贴哪条边"，
                //   本档是"频谱**缩到词岛右端的一条窄带**"（宽 = 5 根柱）。
                //   ⇒ 本档下"贴顶/贴底"没有意义（窄带内固定贴底），见 `SPECTRUM_ANCHOR_RIGHT`。
                "RIGHT" to "词岛右端（5 根细条）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            // （本项无改键记录 —— 原先挂在这里的那段"`height_pct` → `height_percent`"
            //   注释是**错挂**的，已于 2026-09-20 移到 `spectrum.height_percent` 上方。）
            key = "spectrum.secondary.mode",
            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.LIST,
            // 2026-09-20 定名修正：原「副频谱」不贴合 —— 它的选项是
            // `OFF / BOTTOM / TOP / BOTH`，即**选的是"画在哪"**，是一个位置项。
            // 只写"副频谱"会让用户以为它是总开关（总开关是「显示实时频谱」）。
            title = "副频谱位置",
            // 用户原话（2026-09-18）：
            // > 「双峰频谱**背景中有靠底边的另一频谱在跳动**（随歌曲播放进度前进），
            // >   将这个 bug **改为独立设置**（副频谱跳动，有**三个选项，底/顶/底+顶**，
            // >   受**显示频谱总开关**管理，副频谱需要与主频谱在**透明度上做出差异**）」
            //
            // ## 它原本是什么
            //
            // 用户看到的"另一条频谱"其实是**进度染色**那一遍绘制 ——
            // 2026-09-18 之前它**漏传了 `symmetric`**（见 `SpectralPainter` 的修复），
            // 于是底图画双峰、染色层画"从底部长"的柱子，看起来像"背景里另一条频谱
            // 在随进度前进"。那一处**已按缺陷修好**（两层形态一致）。
            //
            // 而用户随后明确要求：**把这个视觉留下来、但做成可选项** ——
            // 于是有了本项。**它不是缺陷的残留，是被用户收编的视觉。**
            //
            // ## 三个选项而不是两个
            //
            // 用户点名「底 / 顶 / 底+顶」。`BOTH` 让上下两条副频谱同时跳，
            // 与主频谱的"对称双峰"是两种不同的对称感，故三个都要有。
            //
            // ## 为什么"受显示频谱总开关管理"
            //
            // 用户明确要求。语义上也对：副频谱是**频谱的一部分**，
            // 关掉 `spectrum.enabled` 时不该还留着两条在跳。
            // 实现上不需要额外代码 —— 本项在 [drawBackgroundSpectrum] 里消费，
            // 而那个函数的第一行就是 `if (!local.spectrumEnabled) return`。
            default = "OFF",
            summary = "受「显示频谱」总开关管理。副频谱画在词岛底边/顶边，透明度单独可调。",
            options = listOf(
                "OFF" to "关闭（默认）",
                "BOTTOM" to "底边",
                "TOP" to "顶边",
                "BOTH" to "底边 + 顶边",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "spectrum.secondary.opacity_percent",
            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.SEEKBAR,
            title = "副频谱不透明度 %",
            // 用户要求「副频谱需要与主频谱在**透明度上做出差异**」——
            // 这正是"它是背景层、不是第二个主角"的视觉表达。
            // 默认 35：明显可见、但一眼能看出在主频谱之下。
            default = 35,
            range = 0.0..100.0,
            summary = "仅在副频谱不为「关闭」时生效。建议明显低于「频谱不透明度」。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.bar_pitch_mode",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.LIST,
            title = "频谱条宽度",
            // 用户原话（2026-09-18）：
            // > 「**不固定词岛宽度又是短歌词时，频谱条应该变细，以塞下更多频谱条**。
            // >   增加设置频谱条宽度范围的独立选项**或自动**（频谱条宽度与词岛宽度呈一比例）」
            //
            // 默认 `AUTO` —— 用户提这条就是在投诉现状（窄岛条太粗、条数太少），
            // 故把修好的那一档设为默认，而不是让用户自己去发现。
            // 宽岛的观感在 AUTO 下**逐像素不变**（见 `spectrumAutoPitch` 的对照表），
            // 故这个默认值不会给"宽岛用户"带来任何变化。
            default = "AUTO",
            summary = "自动：窄词岛时把频谱条做细以塞下更多条（宽词岛观感不变）。固定：用下面「频谱条间距」的值。",
            options = listOf(
                "AUTO" to "自动（窄岛变细）",
                "FIXED" to "固定（用下方间距值）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            // ⚠️ 键名带 `_tenth_`：**单位是 0.1 像素**（2026-09-19 改）。
            //
            // 用户原话：「频谱条间距不能再小吗？」→「**最低低至 0.1px**」。
            //
            // ## 为什么不用"小数范围"（0.1 到 20.0）
            //
            // `tools/gen_preference_config.py` 用**正则解析源码文本**取字段值
            // （`re.search(r"\brange\s*=\s*", block)`）。
            // ⚠️ **本注释块内绝不能出现"range"后面跟等号的写法** ——
            //    实测踩过：我在这里举了个例子，正则**先命中注释里的那一处**，
            //    取到一段带反引号的文本 → `parse_range` 解析失败
            //    → **整个设置项被静默跳过**（只在 stderr 打一行"跳过"），
            //    后果是"设置页上这一项不见了"，比"值被截断"更难发现。
            //
            // **同族纪律**：本项目已有"`SELF_DRAWN_KEYS` 块内不许写带引号的字符串"
            // （同一条理由）。凡**用正则解析源码**的工具，注释就是它的输入。
            //
            // 而"十分之一像素的整数键"是本项目**已经用过两次**的模式
            // （`island.border.width_tenth_px` / `main_lyric.outline.width_tenth_px`）：
            // 整数键 + 单位放大 10 倍，`0.1px` 的分辨率与"宿主是否写 Float"无关。
            // **沿用既有模式，不为了一个设置项去改生成器。**
            key = "spectrum.bar_pitch_tenth_px",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SEEKBAR,
            title = "频谱条间距（0.1 像素）",
            // 语义：**柱间距**（条宽 + 缝），不是条宽本身。
            // 用间距而不是条宽的理由见 `SPECTRUM_BAR_PITCH_PX` 的 KDoc
            // （直接定条宽会把缝挤成余数 → 宽度变化时"忽密忽疏"）。
            //
            // 范围的历史（每一步都是用户点名的，不许回退）：
            //   · 3..20（首版）→ 2..20（2026-09-19，用户「**频谱条间距不能再小吗？**」）
            //     → 0.1..20（同日，「**最低低至 0.1px**」）
            //     → **0..200（2026-09-22，本轮）**。
            //
            // ⚠️ **单位：本段所有 `0` / `20` / `200` 都是 `px`** ✓；
            //   而**本键是 `0.1px` 单位** ⇒ `range` 的字面量是 **`0.0..2000.0`** ✓。
            //   （勘察报告 S2-02 指出：裸写 "200" 会被读者与字面量 `2000` 混淆，差 10 倍 ✗。
            //    单位在别处其实标了 —— `:2289` 逐字引用户「0-200**px**，0.1px 梯度」✓、
            //    `:2299`「间距 200**px** 时…」✓、标题「频谱条间距（**0.1 像素**）」✓ ——
            //    但**在读到 `:2287` 那一行时**仍可能误读，故在此处补一句。）
            //
            // 现在的上限 200 与下限 0 都来自用户原话（逐字）：
            // 「频谱条手动间隙改为 **0-200px**，**0.1px 梯度**的滑杆，
            //   **当间距为 0px 时呈现实心频谱**。」
            //
            // ★ 下限 0 的语义**不是**"再细一点"，而是**换了一种画法**：
            //   0 ⇒ 缝宽 0、柱宽吃满整格 ⇒ 相邻柱**无缝** ⇒ 视觉上实心。
            //   落点在 `SpectrumGeometry.spectrumBarSlot` 的 `solid` 档
            //   （那里的 KDoc 记了为什么"缝 0 + 柱宽吃满"还不够、还必须是**方角**）。
            //   故本项的下限**没有物理地板**了 —— 旧注释里那段"slot < 2px 会重叠"
            //   论证说的是**非 0 档**（缝与柱各带地板），对 0 档不适用。
            //
            // ⚠️ 200 这一端：间距 200px 时 640px 宽的词岛只剩 3 根柱子。
            //   这是用户明确要的可达区间（他写的就是 0-200），不做额外封顶。
            default = 70,
            // ⚠️ 2026-09-19：**下限 2 → 0.1**。用户原话：
            //   「频谱条间距不能再小吗？」→「**最低低至 0.1px**」。
            //
            // 配套改动：`SPECTRUM_MAX_BARS` **384 → 1024**（`render/SpectrumGeometry.kt`）。
            // **少了这一步，把间距拖到 0.1 会"没反应"** —— 条数被上限夹住，
            // 间距再小也塞不进更多柱子（600px 宽下 0.1px 间距理论上要 6000 根）。
            // 这正是"设置项的值 ≠ 生效值"的形态，故两处必须同步改。
            //
            // 代价（如实记录）：1024 根柱子 = 每帧 1024 次 `fillRect`。
            // 默认值仍是 70（= 7.0px，约 85 根），只有用户主动拖到极小间距才会到那个量级。
            //
            // ⚠️ 2026-09-22：**下限 0.1 → 0、上限 20 → 200**（本轮，用户原话见上）。
            //   下限这一端**不需要**再抬 `SPECTRUM_MAX_BARS`：0 档走的是"实心"分支
            //   （条数回落到 `SPECTRUM_MIN_BARS`，见 `spectrumBarCount` 的 `pitch <= 0` 早退），
            //   与"间距极小时要更多柱子"是两件事。
            //
            // ⚠️ 两处值域必须同档（本项目栽过 BUG-024）：本项的上/下界与
            //   `preference_config.json` 的 `min`/`max` —— 后者是**生成物**，
            //   改完必须重跑 `tools/gen_preference_config.py`，由 `RegistryProbe` §6 兜住。
            range = 0.0..2000.0,
            summary = "仅在「频谱条宽度」选「固定」时生效。这是柱间距（条 + 缝），不是条宽本身。" +
                "拖到 0 时缝宽为 0、相邻条无缝 ⇒ 呈现实心频谱。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // 用户原话（2026-09-23）：「**频谱条的样式提供圆角和方角**」。
            //
            // ## 为什么是 LIST 而不是 SWITCH
            //
            // 语义是"**两种形态二选一**"，不是"某个功能的开 / 关"。
            // 写成 SWITCH 的话标题只能是「方角频谱」，而"关"这个状态读不出
            // "那就是圆角" —— 用户会以为"没生效"（本项目在 `spectrum.fade_top` /
            // `cover.rotate` 上正是用 SWITCH 表达"开 / 关一种附加效果"，
            // 而本项是**替换**柱体形状，不是叠加效果，故形状不同）。
            // 选项文案直接写「圆角 / 方角」，值与既有 LIST 项同一风格（英文枚举常量）。
            //
            // ## 默认 [SPECTRUM_BAR_SHAPE_ROUND] = **开箱零变化**
            //
            // 改动前柱角恒为 `min(柱宽, MIN_BAR_HEIGHT)`（完全圆头，见
            // `SpectrumGeometry.spectrumBarCornerPx`），故默认取 `ROUND` 时画面
            // **逐像素不变** —— 新能力只在用户主动选「方角」时出现。
            // 这是本项目每加一项的既定纪律（见 `spectrum.fade_top` / `cover.shape` 的同类说明）。
            //
            // ## 值域：LIST 项没有 `range`，**两个来源**的一致性走另一条路
            //
            // 本项没有 `range`，故不存在"滑块可拖区间 vs `coerceInt` clamp 区间不一致"
            // （BUG-024）那种风险 —— LIST 的合法性由 `options` 白名单决定，
            // `coerce` 只接受声明过的值，其余回退 `default`。
            // 而 `options` 与 `preference_config.json` 的 `entries` / `entry_values`
            // 的一致性由**生成脚本 + `RegistryProbe` §6 的逐项比对**兜住。
            //
            // ## ⚠️ 绘制侧不在这里
            //
            // 消费点是 `render/SpectrumGeometry.spectrumBarCornerPx(...)`（归 `y2-spectrum`）——
            // 它已有一个 `solid` 参数在"实心档"把圆角强制成 0（方角）。
            // 本项要做的是**再叠一个来源**：用户选 `SQUARE` 时同样取 0。
            // 设置值 → 渲染参数的换算**只此一处**：见顶层 [spectrumBarCornerRatio]。
            key = "spectrum.bar_shape",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.LIST,
            title = "频谱条形状",
            summary = "圆角：柱体两端为圆头（改动前的观感）。方角：柱体四角为直角。" +
                "间距拖到 0（实心频谱）时恒为方角 —— 圆角会在相邻柱之间留出腰形缺口。",
            // ⚠️ 2026-09-23：本处曾由 lead **就地补成字面量** ——
            //    写这处的我上一轮引用了两个**尚未声明**的顶层常量
            //    （`SPECTRUM_BAR_SHAPE_ROUND` / `_SQUARE`），整棵树编译不过。
            //    现已按原设计补齐：两个常量声明在本文件**末尾的顶层**
            //    （那里写了"为什么必须是顶层 const val"），这里恢复引用。
            //    ⇒ 设置值 → 渲染参数的换算**只此一处**（[spectrumBarCornerRatio]）。
            default = SPECTRUM_BAR_SHAPE_ROUND,
            options = listOf(
                SPECTRUM_BAR_SHAPE_ROUND to "圆角",
                SPECTRUM_BAR_SHAPE_SQUARE to "方角",
            ),
            phase = SettingPhase.P1,
            // 形状是"改完立刻看得出来"的项（只换一个几何参数），故标 IMMEDIATE ——
            // 与 `spectrum.bar_pitch_mode` / `cover.shape` 同档。
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            // ⚠️ 键名从 `spectrum.height_pct` 改成 `spectrum.height_percent`（2026-09-18）。
            //
            // 用户原话：「双峰频谱的频谱高度是高度而不是占歌词栏百分比
            //   （**这个设置项名字本身就不贴切，改掉它**）」。
            //
            // ## 用户看到的现象与它的两个成因
            //
            // ① **行为侧**（主因，已修）：双峰模式下对称中线取了"频谱带"的中心，
            //    而带是歌词区的**子矩形** —— `height_percent < 100` 时带中心 ≠ 歌词区中心，
            //    于是双峰**不以歌词行/两行为中心**，看起来像"一条被摆在某处的带"。
            //    修法见 `IslandWindow.drawBackgroundSpectrum` 的 `symmetryCenterY`。
            // ② **命名侧**：`_pct` 这种缩写让人读不出"占什么"的百分比。改成全称
            //    `height_percent`，title 里写明**占歌词区**。
            //
            // ⚠️ 改键名会让用户已存的该项值失效（回落到默认 100）。
            //   本次用户的值本来就是 100，故**无可感知影响**；换别的值时要留意。
            key = "spectrum.height_percent",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SEEKBAR,
            title = "频谱高度（占歌词区百分比）",
            summary = "100% = 占满歌词区。开启「上下对称双峰」时，双峰以歌词行/两行的中线为对称轴，上下各占该百分比的一半。",
            // 默认 100 = 占满整个歌词区 —— 与改动前一致。
            // 下限取 10 而不是 0：0% 等于"不画"，那是 `spectrum.enabled` 的语义，
            // 两个键做同一件事会让用户困惑（"我开了频谱怎么没有"）。
            default = 100,
            range = 10.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.bounce_pct",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SEEKBAR,
            title = "频谱跳动幅度（%）",
            // 用户诉求（2026-09-18）：「频谱设置增加一项相对跳动高度（即频谱高低跳动更明显）」。
            //
            // 语义：把**已归一化的柱高**做一次**围绕中值的对比度拉伸** ——
            //   `h' = 0.5 + (h - 0.5) * k`，再 clamp 回 [0,1]。
            // 100% = 原样（不拉伸）；上限 300% = 跳动幅度放大 3 倍。
            //
            // ⚠️ 为什么不是"乘一个系数"（`h * k`）而是**围绕中值拉伸**：
            // 乘法会把整体推高（安静段也变高），用户看到的不是"跳动更明显"而是"整体更高"。
            // 围绕 0.5 拉伸则**中值不动**，只放大偏离中值的部分 —— 这才是"跳动更明显"。
            default = 100,
            range = 100.0..300.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.progress_tint",
            group = SettingGroups.SPECTRUM,
            subGroup = "进度色差",
            kind = SettingKind.SWITCH,
            title = "用频谱色差表示播放进度",
            // 用户诉求（2026-09-18）：「允许通过频谱色差表示歌曲播放进度」。
            //
            // 语义：**已播放比例**越大的那一侧，频谱色越接近"已唱色"，
            // 未播放侧保持频谱本色 —— 于是频谱本身成了一条进度指示。
            // 默认关：它是新增的视觉表达，不该改变既有观感。
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 以下 3 项：2026-09-18 用户点名新增 ──────────────────────────────
        SettingDescriptor(
            key = "spectrum.fade_top",
            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.SWITCH,
            title = "顶端淡出",
            // 用户原话：「注意到频谱**高峰顶端有淡化效果**，我并没有指定这个效果，
            //   请把它**改成一个设置项**。」
            //
            // **原状**：背景频谱用一条纵向渐变笔刷（向上淡出），是**硬编码**的观感取舍。
            //   它不是缺陷（淡出能让频谱"融进"背景而不抢歌词），但**不该由实现替用户决定**。
            //
            // 默认 `true` = **保持改动前的观感**（接线后开箱零变化）——
            //   与 `spectrum.color` 默认取旧硬编码值同一个原则。
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.opacity_pct",
            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.SEEKBAR,
            title = "频谱不透明度 %",
            // ⚠️ 2026-09-19：**默认值 100 → 75**。
            //
            // 笔刷 alpha 原为 `255 × SPECTRUM_BACKGROUND_ALPHA(0.75) × opacity`，
            // 即"拖到 100 也只有 75% 不透明" —— 而用户明确观察到
            // 「拖到 100 比关掉顶端淡化**更透**」（后者走实心路径 = 100%）。
            // 根因与修法见 `IslandWindow.SPECTRUM_BACKGROUND_ALPHA` 的 KDoc。
            //
            // 基准改 1.0 之后，默认值必须同步改成 **75** ——
            // 于是**默认观感逐像素不变**（`255 × 1.0 × 0.75` = 原来的 191），
            // 而"拖到 100"终于真的等于"完全不透明"。
            // 用户诉求：「增加调节**频谱透明度**的调整滑杆。」
            //
            // 语义：`100` = **完全不透明**（2026-09-19 起**真的**成立，见上方注释）；
            //      越小越透。
            // ⚠️ **下限取 0 而不是留出余量**：透明度 0 = 完全看不见，
            //   那与 `spectrum.enabled = false` 效果重复 —— 但**不冲突**：
            //   用户可能想要"几乎看不见但仍在动"的极淡效果，那是合理诉求。
            //   与 `spectrum.height_pct` 下限取 10 的理由不同（那里 0% 等于不画，
            //   且高度是**布局量**；透明度只是**观感量**，0 是一个诚实的取值）。
            default = 75,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.symmetric",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SWITCH,
            title = "上下对称双峰",
            // 用户诉求：「增加频谱的又一显示模式，**上下对称的双峰频谱**，
            //   对称中线（若开启了副歌词（即两行）时处于**两行之间**，
            //   单行时以**该行中线**为对称中线）」
            //
            // 语义：以对称中线为轴，上下各画一份镜像的频谱。
            // **对称中线的取法由渲染层算**（它知道布局），本项只是开关。
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.progress_tint_alpha",
            group = SettingGroups.SPECTRUM,
            subGroup = "进度色差",
            kind = SettingKind.SEEKBAR,
            // 2026-09-20 定名修正：原「已播放与未播放的透明度差值」不贴合 ——
            // 2026-09-20 的实现是**单向压暗**（取证 `window/SpectrumAlphas.kt` 的
            //   `spectrumAlphas(opacityPct, tintAlphaPct, tintActive)`）：
            //   `unplayed = 1 - diff`、`played = 1` —— 这是**相对**系数，
            //   整体不透明度由 `frameOpacity = base` **独自**承载。
            //
            // ⚠️ 2026-09-22：本段原先引的是行号 `IslandWindow.kt:5274-5280`（已漂），
            //   且把公式抄成了 `unplayedAlpha = baseAlpha * (1 - diff)` ——
            //   **那正是 2026-09-21 修掉的缺陷形状**（`base` 被乘两次 → `base²`，
            //   会让整条频谱一起变淡）。引用实现时**抄错的公式比没有公式更危险**，
            //   故这里改为按 `SpectrumAlphas.kt` 逐字写，并附上"不要那样写"的警告。
            // ★★★ 2026-10-12（**BUG-178**）：★ **定名修正 —— "压暗"是错的，它其实是"透明度"** ✓
            //
            // ★ 用户原话（逐字）：
            // > 「**频谱条主带的色差表示进度，更改设置项以及相关说明。
            // >   落实该值为透明度差值，值=0%时已播放与未播放无区别，
            // >   值为100%时只显示已播放（未播放透明）**」
            //
            // ## ★ 语义（用户逐字钉死，与本项实现**完全一致**）
            //
            // | 值 | 未播放段的 alpha | 观感 |
            // |---|---|---|
            // | ★ **0%** | `1 − 0 = 1`（与已播放**相同**） | ★ **已播放与未播放无区别** ✓ |
            // | ★★ **100%** | `1 − 1 = 0`（**全透明**） | ★★ **只显示已播放**（未播放透明）✓ |
            //
            // ★ 实现 = `window/SpectrumAlphas.kt` 的 `unplayed = 1f - diff`（`diff = 本值 / 100`）✓
            // ⇒ ★★ **行为无需改动**（用户描述与现状逐条吻合）——
            //   ★ 本批**只改名称与说明** ✓
            //
            // ## ★ 为什么"压暗"不准确
            //
            // ★ "压暗"暗示**降低亮度**（RGB 变暗），而实现改的是 **alpha（透明度）** ✗ ——
            //   ★★ 用户看到的是"未播放段**透出背景**"，不是"颜色变深" ✓
            // ★ 且"压暗程度"容易与 `spectrum.opacity`（**整条**的不透明度）混淆 ✗
            //
            // ## ⚠️ 适用范围：★ **只有主带**（用户第一句逐字「频谱条**主带**的色差表示进度」）
            //
            // ★ 右侧三型插槽频谱**不启用**本项（用户 2026-10-12 另一句：
            //   「**右侧三型频谱不启用透明度差表播放进度**」）✓
            // ★ 见 `IslandWindow` 的 `slotAlphas`（插槽另用 `unplayed = 1f`）✓
            title = "进度色差：未播放段的透明度（%　0=无区别 / 100=只显示已播放）",
            // 用户诉求（2026-09-19）：「**频谱色差显示播放进度有点难辨认**，
            // 加入一个单独的调节滑杆（主要是**已播放/未播放的频谱的透明度**）」。
            //
            // ## 为什么原来难辨认
            //
            // 已播放段用的笔刷是 `verticalFadePaint`（底部实、**顶部全透明**）——
            // 于是色差**只在带子底部明显**，上半截几乎看不出区别。
            // 用户看到的就是"色差没生效"（其实是画了、但透明度把它吃掉了）。
            //
            // ★★★ 2026-10-12（**BUG-178**）：★ **方向修正 —— 本项控制的是【未播放段】** ✓
            //
            // ⚠️ 原记「本项控制**已播放段的整体不透明度**」**方向反了** ✗ ——
            //   ★ 实现是 `unplayed = 1f - diff`、`played = 1f`（`SpectrumAlphas.kt`）⇒
            //   ★★ **已播放段恒不透明，动的是【未播放段】** ✓
            //   ★ 与用户逐字一致：「值=100%时**只显示已播放（未播放透明）**」✓
            //
            // 本项的实际语义：
            //   · **0**   = 未播放段**与已播放相同** ⇒ **无区别** ✓
            //   · **100** = 未播放段**全透明** ⇒ **只显示已播放** ✓
            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            // ★★★ 2026-10-12（**BUG-174 的更正**）：★ **本项【不下架】—— 主带照旧用它** ✓
            //
            // ★ 我上一批曾把本项标为下架，依据是用户 10-12 的第一句
            //   「频谱显示进度，即使值为0。但实际已播放与未播放仍存在透明度差异。」
            // ★★ 而用户随后的第二句把范围说清了：
            //   > 「**右侧三型频谱不启用透明度差表播放进度。**」
            // ⇒ ★★★ **"不启用"只针对【右侧三型插槽频谱】**，**主带照旧** ✓
            // ★ 且"恒 0"会把进度**彻底弄没**（`spectrumSegmentColors` 的两段**只差 alpha**、
            //   RGB 逐位相同 ⇒ 两段逐位相同 ⇒ 两趟画出同一个颜色）✗
            // ★ 插槽侧的"不启用"改由调用方另传一份 alpha（见 `IslandWindow` 的 `slotAlphas`）✓
        ),
    )

    // ── H 组 · 播放控件（8 项，总表 §1.8 序号 28–35） ─────────────────────
    // 原实现**完全没有这一整个设置域**（它没有"悬停弹控件"行为），
    // 键名/默认值/范围取自 `recon/13` §1.1 总表（总表 §1.8 注）。
    private fun groupH(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "controls.triggerMode",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.LIST,
            title = "控件触发方式",
            // 默认 GLYPH_HIT：首版按用户拍板直接上字形命中（recon/13 §12.0 D-a）。
            // ISLAND_HIT 首版可不提供，保留给后续（总表 §1.8 序号 28 的「取值范围」列原文）。
            default = "GLYPH_HIT",
            options = listOf(
                "GLYPH_HIT" to "字形命中",
                "ISLAND_HIT" to "轮廓命中（悬停到词岛可见区域即展开）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.enterDelayMs",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.SEEKBAR,
            title = "悬停进入延迟",
            default = 120,
            range = 0.0..2000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.exitDelayMs",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.SEEKBAR,
            title = "悬停离开延迟",
            default = 250,
            range = 0.0..3000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.hitPaddingPx",
            group = SettingGroups.CONTROLS,
            subGroup = "布局",
            kind = SettingKind.SEEKBAR,
            title = "命中框外扩",
            default = 2,
            range = 0.0..16.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.position",
            group = SettingGroups.CONTROLS,
            subGroup = "布局",
            kind = SettingKind.LIST,
            title = "控件位置",
            // 注意键名是 camelCase 的 `position`（总表原样），与 `window.position` 同名不同域。
            default = "BOTTOM",
            options = listOf("TOP" to "上方", "BOTTOM" to "下方"),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.gapPx",
            group = SettingGroups.CONTROLS,
            subGroup = "布局",
            kind = SettingKind.SEEKBAR,
            title = "控件与歌词间距",
            default = 8,
            range = 0.0..48.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.showProgress",
            group = SettingGroups.CONTROLS,
            subGroup = "进度与隐藏",
            kind = SettingKind.SWITCH,
            title = "显示进度条",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.autoHideMs",
            group = SettingGroups.CONTROLS,
            subGroup = "进度与隐藏",
            kind = SettingKind.SEEKBAR,
            title = "控件自动隐藏延迟",
            // 默认 0 = 禁用（总表 §1.8 序号 35 的「默认值」列原文）。
            default = 0,
            range = 0.0..30000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-22 新增：**播放控件组自己的动效风格**（用户要求"拆散总开关"）──
        //
        // 用户原话：「动效风格总开关管太多**控件**的动画了，不好细究，**拆散开来，
        //          分到各自的组件组里，保留总开关**。」—— "控件"指的就是本组件。
        //
        // ## 它管哪一处动效
        //
        // `Motions.CONTROLS_APPEAR` —— **控件条的出现**（见 `AnimationRuntime.kt` 的 `Motions`）。
        // 消费点：`IslandWindow.kt` 里 `Motions.resolve(Motions.CONTROLS_APPEAR, local.controlsMotionStyle)`
        // （⚠️ 不写行号 —— 行号会随别人编辑那个文件而过期，见 `animation.style` 项的 KDoc）。
        // 取值改变的输出：
        //
        // | 取值 | 位移 | 缩放 | 观感 |
        // | --- | --- | --- | --- |
        // | `FADE` | `0` | `1` | 原地淡入 |
        // | `SLIDE` | `±8px × (1-t)`（符号按控件条在上/下边） | `1` | 从词岛边缘挤出 |
        // | `POP` | `0` | `0.94 → 1` | 轻微缩放 + 淡入 |
        //
        // ⚠️ 位移的**符号**由 `controls.position` 决定（`TOP` 传负、`BOTTOM` 传正）——
        //    故"同一个 `SLIDE` 在上边/下边的演法相反"，这是有意的（见 `MotionStyle.offsetYAt` 的约定）。
        //
        // ⚠️ **它的进度不由 `AnimationRuntime` 驱动**（`slot = null`）—— 由 `HoverState`
        //    的定时器推进，故 `animation.duration_scale` 对它无效（时长恒 = `HoverState.FADE_IN_MS`）。
        //    本项只改**风格**（位移/缩放通道），不改时长。
        SettingDescriptor(
            key = "controls.animation_style",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.LIST,
            title = "播放控件动效风格",
            summary = "控件条出现的方式。默认「跟随总开关」；选具体风格可让控件条单独一套。",
            default = AnimationStyleResolver.FOLLOW_TOTAL,
            options = AnimationStyleResolver.componentOptions(),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-27 新增：**进度条形态**（用户诉求 ⑭）──
        //
        // 用户原话（`migration/任务排序-副线-2026-09-26.md` 的 14 项诉求表）：
        //   「**新增进度条形态（小圆不恒显示 + 平时羽化高光推进）**」
        //
        // ## 它改变哪个输出（**必须能指出"哪个取值改变哪个输出"**）
        //
        // 消费点：`IslandWindow` 把本键读进 `ControlsConfig.progressStyle`，
        // 由 `PlaybackControls.paintProgress` 的两条分支各自消费：
        //
        // | 取值 | 已播放段 | 圆点 | 观感 |
        // | --- | --- | --- | --- |
        // | `CLASSIC`（默认） | **硬边实心**圆角矩形 | **恒显示** | 改动前的原样行为 |
        // | `GLOW` | **羽化高光推进**（线性渐变：尾部透明 → 头部实色） | **仅拖动中 / 悬停时显示** | 光带推进，静止时不抢注意力 |
        //
        // ⚠️ **`CLASSIC` 是默认值**（不是 `GLOW`）：本项目"默认值稳"那条纪律 ——
        //    新形态必须先被用户主动选中。老用户升级后画面**逐像素不变**（除非他改这一项）。
        //    `RegistryProbe` 有一条反向护栏断言"默认 = CLASSIC"。
        //
        // ## 为什么是**枚举**而不是两个独立开关
        //
        // 两个布尔（`knob_auto_hide` + `glow_trail`）会产生 4 种组合，其中
        // "羽化推进 + 圆点恒显示"与"硬边实心 + 圆点自动隐藏"是**没有设计意图**的中间态，
        // 却各自需要一条断言与一次真机观感判断。枚举把可达状态收敛到**已设计的那些**，
        // 也是本项目既有形态的惯例（`island.shape` / `animation.quality` / `spectrum.bar_shape`）。
        SettingDescriptor(
            key = "controls.progress_style",
            group = SettingGroups.CONTROLS,
            subGroup = "进度与隐藏",
            kind = SettingKind.LIST,
            title = "进度条形态",
            summary = "经典 = 实心条 + 常显圆点（原样）；" +
                "流光 = 已播放段用羽化光带推进，圆点仅在悬停或拖动时出现。",
            default = PROGRESS_STYLE_CLASSIC,
            options = listOf(
                PROGRESS_STYLE_CLASSIC to "经典（实心 + 常显圆点）",
                PROGRESS_STYLE_GLOW to "流光（羽化推进 + 圆点不常驻）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
    )

    // ── I 组 · 歌词排版正确性（2 项，总表 §1.9 序号 37–38） ───────────────
    // 这两项是**正确性修复**（当前实现会显示错误内容），不是精调（recon/13 §12.3 D2/D3 已取证）。
    //
    // ⚠️ 2026-09-18 **移除** `layout.emptySlot.keepPlaceholder`（原序号 36）。
    // 为什么删：它不是"未实现"，而是**实现出来也无作用** —— 它唯一的两个分支在真机上
    // 都不可达（`rawNext == null` 时无条件塌缩、`rawNext.text.isBlank()` 恒为假，
    // 见 `PlaybackTimeline.nextLineFor` 的修订记录与 `recon/_设置项有效性审计.md`）。
    // 用户裁定原话：「**未实现的实现，无效（未唱行相关）的移除**」。
    // 留着的代价是"能拖但画面毫无变化"，比没有这个键更糟。
    private fun groupI(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "layout.noLyrics.mode",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.LIST,
            title = "无歌词时的显示",
            // ★ 2026-09-27 用户拍板：默认值 `HIDE_LINE` → **`PLACEHOLDER`**。
            //   **原记**：默认"隐藏歌词行"（无歌词时什么都不显示），理由是"克制"。
            //   **实为**：用户实测「无歌词时显示标题作者之类的…**体现不佳，没效果**」
            //   —— 而 `cdl.json` 里**根本没有这个键** ⇒ 他用的就是默认值
            //   ⇒ "没效果"是**默认行为**造成的，不是 bug。
            //   `showTrackTitle` 默认已是 `true` ⇒ 本档**开箱即见曲名**，符合预期。
            default = "PLACEHOLDER",
            options = listOf(
                "HIDE_LINE" to "隐藏歌词行",
                "PLACEHOLDER" to "显示占位文本",
                "HIDE_ISLAND" to "隐藏整个词岛",
            ),
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.noLyrics.showTrackTitle",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时显示曲名与作者",
            default = true,
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            // ★ 2026-09-27 用户拍板：「**新增"作者/专辑"**」。
            //
            // ⚠️ **更正（本轮实测）**：**"作者"早已实现** ——
            //    `drawNoLyricsNotice` 里写的是 `"$title · $artist"`（2026-09-18 补的移植遗漏），
            //    且上一项 `showTrackTitle` 的 title 本就写着「无歌词时显示**曲名与作者**」。
            //    ⇒ **本次实际新增的是"专辑"** —— `TimelineSnapshot.album` 一直有数据
            //    （`PlaybackTimeline.trackChanged(title, artist, album, path)` 就存了它），
            //    但**没有任何绘制消费点** ⇒ 属"**数据有、未渲染**"。
            key = "layout.noLyrics.showAlbum",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时同时显示专辑",
            // 默认 **false**（与上一项的 `true` 不同）：专辑名通常远长于曲名
            // （如「某动画 OST 集 Vol.3」），而词岛宽度有限 ⇒ 默认开启会把
            // 「曲名 · 作者」挤掉或整行截断。用户要的"开箱即见"是**曲名与作者**（上一项已默认 true），
            // 专辑是**可选增强**。
            default = false,
            // 阶段取 **P1**（不是上一项的 P0）：专辑**不属于最小可用链路**
            // （P0 的 5 项由 `verify()` 用键名逐字钉住，加进去会破坏那条断言）。
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-19：检测到纯音乐时整块内容不画 ──────────────────────────
        //
        // 用户原话：「**追加检测到纯音乐时不显示歌词/曲名/作者的开关**」。
        //
        // ## 与 `layout.noLyrics.mode` 的分工（**不是重复项**）
        //
        // | 键 | 管什么 | 词岛本身 |
        // |---|---|---|
        // | `layout.noLyrics.mode = HIDE_ISLAND` | 无歌词时**整个窗口隐藏** | 消失 |
        // | `layout.noLyrics.mode = PLACEHOLDER` | 无歌词时画「曲名 · 作者」占位 | 留着 |
        // | **本项** | 无歌词时**不画任何文字**（占位也不画） | **留着**（封面/频谱照常） |
        //
        // 三者的区别是**窗口还在不在**：本项存在的意义是"把词岛当纯视觉件用" ——
        // 纯音乐时保留封面与频谱，只把「暂无歌词 / 曲名」那行字去掉。
        // 若用户要的是"连窗口一起消失"，那是 `layout.noLyrics.mode = HIDE_ISLAND`，
        // 故本项**不是**那条设置的重复。
        //
        // ## 为什么默认 `false`
        //
        // 默认 `true` 会让**所有**默认用户在没有歌词的歌上看到一个"空词岛" ——
        // 而"看不到任何字"与"窗口坏了"在观感上无法区分。默认关，
        // 与 `spectrum.progress_tint` / `island.hit.cover` 同一条纪律。
        //
        // ⚠️ 判据是**当前曲目没有可显示的歌词行**（`snap.currentLine == null`），
        //   与 `layout.noLyrics.mode` 的判据**同一处**（`drawLyricSlots` 的
        //   `present.isEmpty()` 分支）—— 两个键不可能各自解释一半。
        //
        // ⚠️ 分组：**2026-09-19 team-lead 裁定挪到 `LYRIC_LAYOUT`**。
        //   起初按 lead 的口头指定落在 `LYRIC_CONTENT`，但它的两个兄弟键
        //   `layout.noLyrics.mode`（:1369）与 `layout.noLyrics.showTrackTitle`（:1383）
        //   都在 `LYRIC_LAYOUT` —— 于是 `layout.noLyrics.*` 这一族被分到两个组，
        //   用户在菜单里会在两个不同位置看到"无歌词时…"的项。
        //   裁定依据是**键前缀的一致性**（`layout.*` → LYRIC_LAYOUT），
        //   而不是"内容要不要显示"这句语义描述 —— 后者两个组都说得通，不是判据。
        SettingDescriptor(
            key = "layout.noLyrics.hideWhenInstrumental",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时隐藏歌词占位句",
            // ── 2026-09-19 二次修订：**语义收窄**（用户明确纠正）────────────────
            //
            // 用户原话：「纯音乐和无歌词歌曲的歌名/作者/歌词**隐藏逻辑过于混乱**。
            //   无歌词歌曲本身就不会显示歌词，而**纯音乐作为无歌词歌曲，真正需要隐藏的
            //   是歌名之后一直显示到播放结束的「纯音乐，请欣赏」**，
            //   而不是**连歌名/作者都不显示了**。」
            //
            // **原记**：本项为 `true` 时"**整块文字都不画**"（含「曲名 · 作者」）。
            // **实为**：那是**过度隐藏** —— 用户要隐藏的只是那句**占位句**
            //   （宿主推来的「纯音乐，请欣赏」或我们的「暂无歌词」），
            //   **曲名与作者必须留着**（它们正是"这首歌是什么"的唯一线索）。
            //
            // 现语义：**只挡占位句**，不挡「曲名 · 作者」。
            summary = "只隐藏占位句（如「纯音乐，请欣赏」「暂无歌词」）；**曲名与作者仍然显示**。窗口、封面与频谱不受影响。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.noLyrics.titleInTranslation",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时在译文行显示曲名",
            // 用户原话：「**增加此类纯音乐允许在译文行显示歌名的开关设置。**」
            //
            // 场景：纯音乐没有歌词，主行被占位句占着（或按上一项隐藏了），
            // 而用户仍希望**看到曲名** —— 把它放到**第二行**（译文行的位置），
            // 于是"主行干净、曲名在下方"这个布局成为可能。
            //
            // 与 `layout.noLyrics.showTrackTitle` 的区别：
            //   · 那一项决定**主行**画「曲名 · 作者」还是占位句；
            //   · 本项决定**额外**在译文行再画一遍**曲名**（只有曲名，不带作者）。
            default = false,
            summary = "仅在当前曲目没有歌词时生效。开启后曲名会额外画在第二行（译文行的位置）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-19：主/副歌词**字间距**（不是行间距）────────────────────
        //
        // 用户原话：「**允许调整主副歌词的字间距，可选以一定比例联动调整。**」
        //
        // ## 与 `layout.line_gap_px` 的区别（**不能混**）
        //
        // | 键 | 单位 | 作用 |
        // |---|---|---|
        // | `layout.line_gap_px` | px | **行与行之间**的额外间距（纵向） |
        // | **本项** | 0.1 px | **同一行内字符之间**的间距（横向，= letter-spacing） |
        //
        // ## 为什么单位是"十分之一像素"
        //
        // 与 `island.border.width_tenth_px` 同一条理由（真机实测）：SPW 滑杆写盘是
        // **Float**，用"像素"作整数单位时用户拖到 `0.5` 会被 `coerceInt` 截断成 `0`，
        // 表现为"设置了但完全没效果"，且**不报错**。放大 10 倍即得 `0.1 px` 分辨率。
        //
        // ## 范围为什么是 0..200（= 0.0..20.0 px）
        //
        // 下限 `0` = 不加字距（**默认，开箱观感零变化**）。
        // 上限 `20 px`：中文字宽在默认字号 22 时约 22 px —— 字距加到 20 px
        // 已接近"字与字之间空出一个字"，再大就散成单个字了，没有排版意义。
        // 且上限不设成"占字号百分比"是因为本项的**单位必须是像素**（见上），
        // 而字号本身另有一个 8..200 的滑杆，两者独立才可推理。
        SettingDescriptor(
            key = "main_lyric.letter_spacing_tenth_px",
            group = SettingGroups.LYRIC,
            subGroup = "字间距",
            kind = SettingKind.SEEKBAR,
            title = "主歌词字间距（0.1 像素）",
            summary = "同一行内字符之间的距离。0 = 不加（默认）；10 = 1.0 像素；上限 200 = 20.0 像素。字号越大，同样的像素字距看起来越紧。",
            default = 0,
            range = 0.0..200.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ## 副歌词"跟随"为什么是**开关**而不是"两套值自动一致"
        //
        // 与 `translation.outline.sync_with_main` 同一个形态与同一条理由：
        // 用户要的是"**可**以联动" —— 即**联动与否由他决定**。
        // 若强行让两者永远一致，就丢掉了"主歌词加宽字距、副歌词保持原样"这种合理组合；
        // 若完全不提供联动，用户改主歌词字距时要手动改第二项。
        //
        // ## "按比例"是**怎么实现**的（关键，见报告）
        //
        // 字距的实现是给字体加 `java.awt.font.TextAttribute.TRACKING`
        // （= "字距占**字号**的比例"，实测 `tracking × fontSize` 就是像素字距）。
        // 于是**同一个 tracking 值**用在副歌词的小字号上时，
        // 像素字距自动变成 `主字距 × (副字号 / 主字号)` ——
        // **"按比例联动"是这个方案的固有性质，不需要额外算一个系数**。
        //
        // 关闭本项 = 副歌词用 `tracking = 0`（**保持原样，不加字距**）。
        // ⚠️ 本项目**没有**"副歌词自己的字距"键 —— 用户原话只说"可选联动"，
        //   没有要求副歌词独立可调；真需要时再在 `false` 分支上加一项即可。
        SettingDescriptor(
            key = "translation.letter_spacing.follow_main",
            group = SettingGroups.LYRIC,
            subGroup = "字间距",
            kind = SettingKind.SWITCH,
            title = "副歌词字间距跟随主歌词",
            summary = "开启时副歌词按主歌词的字距按字号比例缩放（字越大间距越大）；关闭时副歌词不加字距。",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
    )

    // ── J 组 · 歌词同步与性能（3 项，总表 §1.10 序号 39–40 + 2026-09-17 新增 1 项） ──
    private fun groupJ(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "lyric.offset_ms",
            group = SettingGroups.LYRIC,
            subGroup = "高亮与同步",
            kind = SettingKind.SEEKBAR,
            title = "歌词高亮偏移 ms",
            default = 0,
            // 负值范围（−2000–2000）：clamp 到 range 时用的是 `coerceIn(start, endInclusive)`，
            // 对负下界天然成立 —— 这也是本实现把 range 用 `ClosedRange<Double>` 而非
            // `IntRange` 表达的原因之一（无需为负值特殊处理）。
            range = -2000.0..2000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-22 新增：**歌词组自己的动效风格**（用户要求"拆散总开关"）──
        //
        // 用户原话：「动效风格总开关管太多控件的动画了，不好细究，**拆散开来，
        //          分到各自的组件组里，保留总开关**。」
        //
        // ## 它管哪一处动效（**必须能指出"哪个取值改变哪个输出"**）
        //
        // `Motions.LYRIC_EXIT` —— **歌词行切换时旧行的退场**（见 `AnimationRuntime.kt` 的 `Motions`）。
        // 消费点：`IslandWindow.kt` 里 `val spec = Motions.resolve(Motions.LYRIC_EXIT, local.lyricMotionStyle)`
        // （⚠️ 不写行号 —— 理由见 `animation.style` 项的 KDoc）。
        // 取值改变的输出（`MotionStyle.offsetYAt` / `scaleAt`）：
        //
        // | 取值 | 位移 | 缩放 | 观感 |
        // | --- | --- | --- | --- |
        // | `FADE` | `0` | `1` | 原地淡出 |
        // | `SLIDE` | `-lineHeight × (1-t)` | `1` | 上滑一个行高 + 淡出 |
        // | `POP` | `0` | `0.94 → 1` | 原地缩小 + 淡出 |
        //
        // ⚠️ **三个取值两两可分辨**（`FADE` vs `SLIDE` 差在位移、`SLIDE` vs `POP` 差在缩放、
        //    `FADE` vs `POP` 两处都差）—— 这正是本项目要求"取值必须改变输出"的那条纪律；
        //    `RegistryProbe` §11 用 `offsetYAt`/`scaleAt` 的**实际返回值**断言了这一点。
        //
        // ## 为什么第 0 项是「跟随总开关」
        //
        // 没有它，"拆散"就等于没拆 —— 用户会被**锁死在总开关**上（用户要求"保留总开关"，
        // 但保留的前提是**子项能脱离它**）。默认 `FOLLOW` 保证开箱行为与拆散前**逐帧一致**
        // （总开关默认 `PER_COMPONENT` 不干预 → 落回出厂 `SLIDE`）。
        SettingDescriptor(
            key = "lyric.animation_style",
            group = SettingGroups.LYRIC,
            subGroup = "高亮与同步",
            kind = SettingKind.LIST,
            title = "歌词动效风格",
            summary = "歌词换行时**旧行**的退场方式（新行不受影响）。默认「跟随总开关」；" +
                "选具体风格可让歌词单独一套，而总开关保持不动。",
            default = AnimationStyleResolver.FOLLOW_TOTAL,
            options = AnimationStyleResolver.componentOptions(),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        // ── 2026-09-19 移除 `performance.low`（原「手动低性能模式」/ 菜单「减少动画」）──
        //
        // ## 为什么删
        //
        // 用户报「SPW 插件配置页内**死设置项变多了**」。逐键审计（89 键）的结论是
        // **真正没有任何消费点的只有这一项** —— 全工程只有两处提到它：
        //   · `SettingRegistry` 的声明（本处，已删）
        //   · `IslandPopupMenu` 的菜单项（已删）
        // **没有任何读取点** —— 勾了、翻了，行为零变化。
        //
        // 它原本的住户是「低性能档跳过宿主歌词探测」，而那条探测已停用
        // （见 `HostLyricsProbe`）；连带 `shouldProbe(hostLyricsProbeEnabled, lowPerformance)`
        // **全树无调用点**，也已一并删除。
        // > **没有住户的开关就是一句谎言。**
        //
        // ## （2026-09-20 已删除 `SettingGroups.PERFORMANCE`）
        //
        // **原记**：保留一个 0 项的空分组，理由是"删它要同步三张表"。
        //
        // **实为**：重分组时那三张表**本来就要重写**，故"保留"的代价优势不存在了 ——
        // 且一个 0 项分组会在 `SettingGroups.ALL` 里留一个**永远不会被填**的占位。
        // 已随重分组一并删除。原注释保留如下（作为"当时的判断"的记录）：
        //
        // ## 原记：为什么保留 `SettingGroups.PERFORMANCE` 这个**空分组**
        //
        // 删它要同步三张表（`SettingGroups.ALL` / `expectedByGroup` /
        // `MenuPolicy` 的分组白名单），而**留一个 0 项的分组没有任何副作用**：
        // `byGroup[PERFORMANCE] ?: 0 == 0` 恒成立，菜单里也不会出现空的「性能」子菜单。
        // 若将来要做真正的「减少动画」，直接用回这一组即可。
        //
        // > 用户配置里会留下 `performance.low` 这个**陈旧键** —— 无害
        // > （读路径按注册表取键，注册表里没有它就不会被读）。
    )

    /**
     * K 组 · 右键菜单（3 项，2026-09-18 新增）。
     *
     * 用户诉求（同一天的两条，都落在这一组）：
     * 1. 「右键菜单内设置项完善显示设置，使内容显示完整，
     *    **鼠标悬停和离开展开项时的子菜单出现、消失延迟要可调**」；
     * 2. 「**允许调整右键菜单的透明度**」。
     *
     * ## 为什么需要"可调延迟"（前两项）
     *
     * 菜单是**层级导航**：从父项移到子菜单，鼠标必须**横穿**中间项。
     * 若"离开即收起"，横穿过程中子菜单会被误收起 —— 于是**根本点不到子菜单里的项**。
     * 这正是用户反馈"设置项显示不完整"的根因之一：**不是画不出来，是还没点到就没了**。
     *
     * 故需要两个独立延迟：
     *  - **展开延迟**：悬停多久才展开（防"快速掠过时一路弹开"）；
     *  - **收起延迟**：离开多久才收起（给横穿留时间）。
     * 原实现 0.8.9 用固定值，本版做成可调 —— 手感因人而异。
     *
     * ## 为什么透明度也归这一组（第 3 项）
     *
     * 它是"菜单这个控件本身长什么样"的属性，与两个延迟同类；且放进这一组后
     * 它在右键菜单里的路径是「调整词岛设置 ▸ 显示与行为 ▸ 菜单 ▸ 右键菜单透明度」——
     * 用户**就在菜单里**把它调淡，正是这一项最自然的用法（改完立刻生效，见
     * `IslandPopupMenu.applyValue`）。
     *
     * ⚠️ 它同时**上 SPW 设置页**（不是 `SELF_DRAWN_KEYS`）—— 透明度是纯数值，
     * 宿主原生 `seekbar` 表达得了，没有理由只在自绘窗口里给。
     */
    private fun groupK(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "menu.submenu_open_delay_ms",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "菜单延迟",
            kind = SettingKind.SEEKBAR,
            title = "子菜单展开延迟（ms）",
            // 默认 180：与 `recon/08` §3.1 的悬停展开延迟一致（那是实测手感值）。
            // 下限 0 = 立即展开；上限 1000 已远超"能感觉到"的范围。
            default = 180,
            range = 0.0..1000.0,
            // ★★★ 2026-10-14（**BUG-198**）：步长 **10**（默认 1 时点 20 下才 +20ms ⇒ 手感上
            //   分辨不出 ⇒ 用户报「子菜单的消失延迟调整后不应用」）。
            //   ★ "差 1" 仍可达（点数值框**内联编辑**直接打任意整数）；见 `SettingDescriptor.step` 的 KDoc。
            step = 10,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "menu.submenu_close_delay_ms",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "菜单延迟",
            kind = SettingKind.SEEKBAR,
            title = "子菜单收起延迟（ms）",
            // 默认 250：必须**明显大于**展开延迟 —— 横穿父项需要时间，
            // 收起太快会让用户"永远点不到子菜单项"（见本函数 KDoc）。
            default = 250,
            range = 0.0..1000.0,
            // ★★★ 2026-10-14（**BUG-198**）：同上，步长 **10** ——
            //   ★ 用户报障（逐字）：「**子菜单的消失延迟调整后不应用**」。
            //   ★ 写读两条链逐条核过**都对**（`live` 立刻更新 + 轮询每次现读）⇒
            //     真凶是**步长与量纲不匹配**（0–1000 ms 的域配步长 1）✓
            step = 10,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "menu.opacity_pct",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "菜单外观",
            kind = SettingKind.SEEKBAR,
            title = "右键菜单不透明度（%）",
            // 用户原话（本轮诉求）：「**允许调整右键菜单的透明度**。」
            //
            // 默认 100 = 完全不透明，即"改动前"的观感 → **开箱零变化**。
            //
            // ⚠️ 下限 30 **不能是 0**：0% 在 Windows 上是"窗口完全透明且**不再接收
            //    鼠标消息**"（layered 窗口按逐像素 alpha 做命中测试），
            //    菜单会变成**看得见点不到** —— 比"调不了"严重得多。
            //    与 `spectrum.height_pct` 的下限不能是 0 是同一条纪律。
            //
            // ⚠️ 这里**故意写死**这三个数（键名 / 30 / 100），不在本文件 import
            //    `dev.cdl.window` 的常量 —— 那会让 `settings` 与 `window`
            //    两个包**互相依赖**（`MenuPolicy` 已经 import 了本包的 `SettingGroups`）。
            //    同源由**探针**保证：`MenuProbe` 断言本项的 key / range / default
            //    与 `MenuPolicy` 的 `MENU_OPACITY_*` 逐值相等
            //    —— 与 `menu.submenu_open_delay_ms` 那两项用的是同一套做法。
            default = 100,
            range = 30.0..100.0,
            phase = SettingPhase.P1,
            // 透明度是"改完立刻看得出来"的项（`IslandPopupMenu.applyValue` 会对
            // 已弹出的菜单窗立刻生效），故标 IMMEDIATE。
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    /**
     * **声明顺序**：`groupA()`…`groupK()` 的原始次序（= `首版设置总表.md` §1 的字母组）。
     *
     * 私有 —— 对外只有 [all]（已按组件/细类重排）。保留这个"原样声明"的中间层，
     * 是为了让重排逻辑**只有一处**（[all] 的 `sortedBy`），而不是去手工搬 100 个描述符。
     */
    private val declared: List<SettingDescriptor> = buildList {
        addAll(groupA())
        addAll(groupB())
        addAll(groupC())
        addAll(groupD())
        addAll(groupE())
        addAll(groupF())
        addAll(groupG())
        addAll(groupH())
        addAll(groupI())
        addAll(groupJ())
        addAll(groupK())
    }

    /**
     * 全部设置描述符，**按「一级组件 → 二级细类」重排**。
     *
     * ## 为什么在这里重排，而不是去搬 100 个 `SettingDescriptor(...)` 块
     *
     * 用户原话：「**整理设置项，使同类同组件同功能设置项归在一起。自分类树顶端向下排。**」
     *
     * 这句话要求的是**顺序**，而顺序在这份注册表里有两条路可走：
     * ① 物理搬动 100 个描述符块（巨大的 diff，且与 `groupA()`…`groupK()` 的
     *    "按总表字母组组织"结构打架 —— 那正是本文件刻意的组织方式）；
     * ② 保留声明顺序，在**唯一的出口** [all] 处按 (组件序, 细类序) 稳定排序。
     *
     * 取 ②。`sortedBy` 是**稳定**排序，故同一 (组件, 细类) 内的相对顺序
     * 仍是**声明顺序** —— 即 `groupA()`…`groupK()` 的原序。
     *
     * ## 这个顺序的三个消费者
     *
     * ① **SPW 设置页的扁平顺序** —— `preference_config.json` 的 `preferences` 数组
     *    （生成器逐项遍历本列表）。上一轮用户投诉「取色设置项**位置一点也不符合设置项排列**」，
     *    针对的就是这个顺序。
     * ② **右键菜单**里每个子菜单内部的条目顺序（`IslandPopupMenu` 遍历 `byGroup`）。
     * ③ `spwPageDescriptors` 的顺序。
     *
     * ⚠️ 因此 `all` 的顺序**不再**等于 `首版设置总表.md` §1 的序号顺序 ——
     * 想按总表逐行核对时请看 [declared]，不要看本列表。
     */
    val all: List<SettingDescriptor> = declared.sortedBy { d ->
        SettingGroups.ALL.indexOf(d.group) * 1000 + d.subOrder()
    }

    /**
     * 该项在其组件内的**细类序** —— [all] 重排的第二关键字。
     *
     * 未声明 `subGroup`（或声明了一个该组件下没有的细类）时排到**最后**，
     * 而不是抛异常：重排只是显示顺序，不该让类加载失败；
     * 而"细类名写错"这件事由 [verify] 单独硬失败（那里才是判据所在）。
     */
    private fun SettingDescriptor.subOrder(): Int {
        val subs = SettingGroups.SUBS[group].orEmpty()
        val at = subs.indexOf(subGroup)
        return if (at >= 0) at else subs.size
    }

    /**
     * 按**一级组（组件）**归类。键序即 [SettingGroups.ALL] 的顺序。
     *
     * ⚠️ 键集**必须**等于 [SettingGroups.ALL] —— `verify()` 用 `expectedByGroup` 断言。
     * 这也是 `window/MenuPolicy.MENU_BUCKETS` 的成员来源（右键菜单的子菜单标题）。
     */
    val byGroup: Map<String, List<SettingDescriptor>> = all.groupBy { it.group }

    /**
     * **两级索引**：组件 → 细类 → 该项。
     *
     * 这是"组件分组的基础上更细"的**唯一数据出口** ——
     * 想渲染两级树（设置窗口、将来的菜单三层）只需遍历它，
     * 不必自己去拼 `group` + `subGroup`。
     *
     * 两级 key 的顺序都按 [SettingGroups.ALL] / [SettingGroups.SUBS] 的声明顺序，
     * 而不是 `groupBy` 的首次出现顺序 —— 后者会随声明顺序漂移。
     */
    val byComponentThenSub: Map<String, Map<String, List<SettingDescriptor>>> =
        SettingGroups.ALL.associateWith { component ->
            val subs = SettingGroups.SUBS.getValue(component)
            subs.associateWith { sub ->
                all.filter { it.group == component && it.subGroup == sub }
            }
        }

    /** 按键查找。**键名唯一性由 [verify] 保证**，故这里用 `associateBy` 是安全的。 */
    val byKey: Map<String, SettingDescriptor> = all.associateBy { it.key }

    /** 按阶段筛选（P0 的 6 项用于"最小可用链路"自检）。 */
    val byPhase: Map<SettingPhase, List<SettingDescriptor>> = all.groupBy { it.phase }

    fun descriptor(key: String): SettingDescriptor? = byKey[key]

    /**
     * 注册表自洽性校验。**在对象初始化时执行**（`init` 块），失败即类加载失败。
     *
     * ## 为什么值得在启动路径上跑这些断言
     *
     * 总表 §10 花了整整一节用脚本核对"40 项是否闭合"，并在 §10.1 诚实记录了自己
     * **一度手工算错 1 项**（把 `shape.type` 误计入 seekbar）。这说明"项数对不对"
     * 是这份表**真实出过错的维度**，靠人工复核不可靠。
     * 把它变成代码断言后，将来任何人加/删一项而没同步更新其它计数，都会**当场**失败。
     *
     * 校验项（逐条对应总表 §10.2 的交叉验证表）：
     * 1. 总数为 [EXPECTED_TOTAL]
     * 2. 键名唯一
     * 3. 键名均为点号嵌套（≥1 个 `.`）且不以 `.` 开头/结尾
     * 4. 类型分布（**当前实测 2026-09-19**：
     *    `switch 26 / seekbar 45 / list 15 / edittext 1 / color 1 / font 1 = 89`）
     * 5. 阶段分布（**当前实测 2026-09-19**：`P0 5 / P1 84 / P4 0`）
     * 6. 分组数 15，且每组至少 1 项、无未声明的分组名
     * 7. 分组计数与总表 §2 的逐组项数一致
     *
     * ⚠️ **2026-09-18 修订**：本列表原写「总数为 44 / `switch 18 / seekbar 11 / list 10 /
     * edittext 1 / color 3 / font 1` / `P0 6 / P1 38`」—— 那**三行数字全部陈旧**
     * （当时实际是 59 项，且 seekbar 从 11 涨到 27、color 从 3 降到 1；
     * 2026-09-18 再加 `menu.opacity_pct` 后为 **60 项 / seekbar 28**）。
     *
     * **为什么之前没人发现**：这些数字写在**注释**里，而 `verify()` 的断言用的是
     * 独立常量 —— 注释陈旧**不会让编译或探针失败**。
     * 故本轮起**改为引用 `[EXPECTED_TOTAL]`**（那是有断言保护的单一事实源），
     * 其余无法引用的数字**标注实测日期**，让读者知道它是什么时候的。
     */
    init {
        verify()
    }

    private fun verify() {
        check(all.size == EXPECTED_TOTAL) {
            "注册表项数应为 $EXPECTED_TOTAL，实为 ${all.size}（首版设置总表 §2）"
        }

        val duplicated = all.groupBy { it.key }.filterValues { it.size > 1 }.keys
        check(duplicated.isEmpty()) { "键名重复：$duplicated" }

        all.forEach { descriptor ->
            val key = descriptor.key
            check('.' in key) { "键名应为点号嵌套：$key" }
            check(!key.startsWith('.') && !key.endsWith('.')) { "键名首尾不应有句点：$key" }
            check(key.none { it.isWhitespace() }) { "键名不应含空白：$key" }
        }

        // 类型分布 —— 期望值逐字取自总表 §2.2 的"脚本产出，权威"表。
        // 注意总表自己记录过：这里一度手工写成 18/11/8（合计 39），
        // 差额根因是"把 shape.type 误填入 seekbar 行 + 漏计 experimental_multi_line"。
        // 故本断言用**逐 kind 显式期望值**，而不是只校验总和 —— 只校验总和的话，
        // 两个 kind 各错 1 项相互抵消仍会通过。
        val expectedByKind = mapOf(
            // 基线 17 + 2026-09-17 `performance.host_lyrics_probe`
            // + 2026-09-18 `spectrum.progress_tint` = 19
            // − 2026-09-18 移除 `layout.emptySlot.keepPlaceholder`（SWITCH）= 18。
            // + 2026-09-19 `island.hit.cover` / `layout.noLyrics.hideWhenInstrumental`
            //   / `translation.letter_spacing.follow_main`（三个开关）= 26。
            // − 2026-09-19 移除 `performance.low`（真死设置）= 25。
            // + 2026-09-20 `settings.auto_ungate_on_touch`（用户要求做成开关）= 26。
            // + 2026-09-21 晚 `main_lyric.outline.follow_font_weight` = 28。
            // + 2026-09-21 `cover.rotate`（用户附黑胶截图：「**允许其转动**」）= 29。
            // + 2026-09-21 `cover.vinyl`（用户：「旋转圆形封面**加上黑边**……外围加黑边」）= 30。
            // + 2026-09-22 `layout.island.height.follow_font`（词岛尺寸两档）= 31。
            // + 2026-09-23 `translation.outline.follow_weight`（副描边是否跟随字重）= 32。
            // + 2026-09-28 `animation.frame_log`（每秒帧率日志，用户：「日志每秒打印
            //   帧率和实际ms，方便检查达成情况」，**测试用** ⇒ 默认关）= 34。
            // + 2026-09-30 `cover.lock`（封面态「锁定封面」= `recon/67` 全案唯一新键）= 35。
            // + 2026-10-09 `material.render.gpu_backend`（R7 GPU 后端开关）= 37。
            SettingKind.SWITCH to 37,   // + 2026-10-01 `material.liquid.depth_effect`
            // 基线 10 + 2026-09-16 `layout.line_gap_px` + 2026-09-17 `spectrum.height_pct`
            // + 2026-09-17 歌词三色的 12 根滑杆（−3 个 COLOR、+12 个 SEEKBAR）
            // + 2026-09-18 `spectrum.bounce_pct` / `menu.submenu_open_delay_ms`
            //   / `menu.submenu_close_delay_ms`
            // + 2026-09-18 `menu.opacity_pct`（用户点名「允许调整右键菜单的透明度」）= 28。
            // + 2026-09-19 `main_lyric.letter_spacing_tenth_px`（用户点名「允许调整
            //   主副歌词的字间距」）= 45。
            // + 2026-09-21 晚：`main_lyric.font.weight_scale` + `layout.translation.scale_percent` = 48。
            // + 2026-09-21 晚：`outline.weight_link_percent` = 49。
            // + 2026-09-21 `cover.rotate_period_s`（黑胶转速）= 50。
            // + 2026-09-22 `background.cover.angle_deg`（封面图背景的旋转角度）= 51。
            // + 2026-09-22 `layout.island.height.ratio_percent`（词岛高度比值）= 52。
            // + 2026-09-25 第 2 期「材质」+5（`gradient_tint_percent` /
            //   `gradient_angle_deg` / `highlight_strength` / `noise_amplitude`
            //   / `shape.corner_n`）= 57。
            // + 2026-09-25 第 3 期 L2 +1（`material.content.blur_radius`）= 58。
            // + 2026-09-26 预留 `material.backdrop.blur_radius` = 59。
            // + 2026-10-01 第二批（「液态玻璃」子组）：59 → **61**
            //   （`material.liquid.surface_alpha` / `material.liquid.shadow_strength`，
            //    两项都是 SEEKBAR ⇒ **SWITCH / LIST / COLOR / FONT 均不变**）
            // + 2026-10-01 ④b 第二批（用户「开4b」）：61 → **65**
            //   （`material.backdrop.refraction_height` / `refraction_amount` /
            //     `dispersion` / `saturation`，**四项全是 SEEKBAR**）
            // + 2026-10-13 `island.border.dissolve_band_percent`（溶边渐变范围）= **66**。
            // ★ 2026-10-13（**BUG-188**）：66 → **67**
            //   （`cover.sharpness`，用户「使其显示得更清晰可变」）✓
            // ★ 2026-10-16（**§3.77-F**）：67 → **70**（极坐标径向范围三键：
            //   `radial_inner_percent` / `ring_inner_percent` / `polar_outer_percent`）✓
            // ★ 2026-10-16（**BUG-219**）：70 → **71**（`material.fill.flow_speed`，
            //   用户「**加入对流光流速的流速调整设置**」）✓
            SettingKind.SEEKBAR to 71,
            // 基线 9 + 2026-09-16 `layout.line_mode` + 2026-09-17 `spectrum.anchor` = 11。
            // + 2026-09-21 `animation.frame_rate`（帧率从质量档解耦成独立项）= 20。
            // + 2026-09-21 `cover.shape`（圆形封面）= 21。
            // + 2026-09-22 `lyric.animation_style` / `controls.animation_style`
            //   （用户要求把「动效风格总开关」**拆散到各组件组**、保留总开关）= 23。
            // + 2026-09-23 `spectrum.bar_shape`（频谱柱圆角 / 方角）= 24。
            // + 2026-09-25 第 2 期「材质」+2（`shape.corner_style` /
            //   `material.fill.gradient_style`）= 26。
            // + 2026-09-25 第 3 期 L2 +1（`material.quality`）= 27。
            // + 2026-09-27 `controls.progress_style`（用户诉求 ⑭ 进度条形态）= 28。
            // ★ 2026-10-13（**U16**）：33 → **34**（`island.right_slot.spectrum_bars`，
            //   用户「新增5条小频谱的频谱条数选择（4和6根）」）✓
            // ★ 2026-10-15（**§3.75**）：34 → **35**（`island.right_slot.spectrum_color_mode`，
            //   用户「开放对插槽频谱的独立选色」）✓
            SettingKind.LIST to 35,   // + 2026-10-03 `clock.hour_format`（12/24 时制）· `clock.style`（⑱ 字形风格）
            SettingKind.EDITTEXT to 1,
            // 2026-09-17：3 个歌词色键改为滑杆后**只剩 1 个 COLOR**
            // （`spectrum.color`，频谱色 —— 它是新加的、暂时保留 hex 手填）。
            // 2026-09-22：**+1** —— `background.color`（用户：「**背景设色**没有选色入口」）。
            //   同族排查的产物：`spectrum.color` 是 2026-09-17 修的同一形态，
            //   而 `background.*` 当时被漏掉了。
            // ★ 2026-09-25 **+1** —— `material.fill.color_end`（用户拍板"显式止色"）。
            //   ⚠️ 这一条**主动踩进** F §1.3 的「断点 A」：COLOR 键在设置页无控件承载，
            //   必须同批做「3 处 + 1 按钮」配方，否则该项**静默看不见**。
            // ★ 2026-10-15（**§3.75**）：3 → **4**（`island.right_slot.spectrum_color`，
            //   ★ 该键**已按配方做全**：注册表 + `preference_config.json`（重跑生成器）+
            //     `pickRightSlotSpectrumColor` 入口 + 预览快照）✓
            SettingKind.COLOR to 4,
            SettingKind.FONT to 1,
        )
        expectedByKind.forEach { (kind, expected) ->
            val actual = all.count { it.kind == kind }
            check(actual == expected) {
                "类型 ${kind.name} 应有 $expected 项，实为 $actual（首版设置总表 §2.2）"
            }
        }

        // 阶段分布 —— 总表 §2.1。P0 的 6 项是"最小可用链路"的定义，错一个就名不副实。
        // P1 = 34（基线）+ 1（新增译文色）+ 2（line_mode / line_gap_px，2026-09-16）
        //      + 1（host_lyrics_probe，2026-09-17）
        //      + 3（spectrum.color / anchor / height_pct，2026-09-17 用户点名）
        //      + 9（歌词三色：−3 个 COLOR、+12 根滑杆，2026-09-17 方案 A）
        //      + 4（2026-09-18 频谱跳动 / 频谱进度色 / 菜单两个延迟）
        //      + 1（2026-09-18 `menu.opacity_pct`）= 55。
        //      − 2026-09-18 移除 `layout.emptySlot.keepPlaceholder` —— 它是 **P0**，
        //        故 P1 不受影响（该项是 P0），P0 由 6 降为 5。见 `groupI()` 的移除说明。
        //      + 4（2026-09-19：`island.hit.cover` / `layout.noLyrics.hideWhenInstrumental`
        //        / `main_lyric.letter_spacing_tenth_px` / `translation.letter_spacing.follow_main`
        //        四项**全部标 P1**）= 84。
        //      − 1（2026-09-19 移除 `performance.low` —— 它是 P1）= 83。
        val expectedByPhase = mapOf(
            SettingPhase.P0 to 5,
            //      + 1（2026-09-20 `settings.auto_ungate_on_touch`）= 86。
            //      + 1（2026-09-21 `animation.frame_rate`）= 91。
            //      + 2（2026-09-21 晚：`font.weight_scale` / `translation.scale_percent`）= 93。
            //      + 2（2026-09-21 深夜：`outline.follow_font_weight` / `outline.weight_link_percent`）= 95。
            //      + 3（2026-09-21 `cover.shape` / `cover.rotate` / `cover.rotate_period_s`
            //        —— 用户附**黑胶唱片**截图要求「新增圆形封面选项**并允许其转动**」）= 98。
            //      + 1（2026-09-21 `cover.vinyl` —— 用户要求「旋转圆形封面加上黑边，
            //        使其呈现黑胶样式（**外围**加黑边，不侵占封面内容）」）= 99。
            //      + 2（2026-09-22 `lyric.animation_style` / `controls.animation_style`
            //        —— 动效风格拆散到各组件组）= 101。
            //      + 2（2026-09-22 `background.color` / `background.cover.angle_deg`
            //        —— 背景自选色 + 封面图背景的旋转角度）= 103。
            //      + 2（2026-09-22 晚 `layout.island.height.follow_font` /
            //        `layout.island.height.ratio_percent`
            //        —— 用户：「将词岛大小随字体大小变化**剥离**出来，改为**是否跟随**和
            //        **手动调整**两档选择」）= 105。
            //      + 2（2026-09-23 `spectrum.bar_shape` / `translation.outline.follow_weight`
            //        —— 用户：「**频谱条的样式提供圆角和方角**」+
            //        「**允许副歌词的描边跟随字体粗细**」）= 107。
            // + 2026-09-25 第 2 期「材质」+8（8 键**全部**标 P1）= 115。
            // + 2026-09-25 第 3 期 L2 +2 = 117。
            // + 2026-09-26 预留 1 键 = 118。
            // + 1（2026-09-27 `controls.progress_style`，用户诉求 ⑭）。
            // ⚠️ **实测修正**：上面两行注释累加得的 "118" 与**实际值不符** ——
            //    加本项之前，`verify()` 从未报过 P1 计数错，而加本项后
            //    实测报「P1 应有 119 项，实为 120」⇒ **改前实际是 119**（不是 118）。
            //    ⇒ 本项使 P1 **119 → 120**。
            //    这条正是"注释里的派生计数会腐烂"的现场例证（纪律 34①）：
            //    注释是手抄的，`verify()` 才是真值 ⇒ **每次都以实测为准，不信注释**。
            // + 1（2026-09-28 `animation.frame_log`，**测试用** ⇒ 标 P1）。
            // + 2026-10-01 第二批「液态玻璃」2 键（**都取 P1**）（125 → **127**）。
            // + 2026-10-01 ④b 第二批 4 键（**都取 P1**）（127 → **131**）。
            // + 2026-10-09 R7 `material.render.gpu_backend`（取 P1）（134 → **135**）。
            // + 2026-10-13 （**BUG-186**）`island.border.dissolve_band_percent`（取 P1）（135 → **136**）。
            // ★ 2026-10-13（**BUG-188**）：136 → **137**
            //   （`cover.sharpness`，取 P1）✓
            // ★ 2026-10-13（**U16**）：137 → **138**（`island.right_slot.spectrum_bars`，取 P1）✓
            // ★ 2026-10-15（**§3.75**）：138 → **140**（`island.right_slot.spectrum_color_mode`
            //   + `island.right_slot.spectrum_color`，两项都取 P1）✓
            // ★ 2026-10-16（**§3.77-F**）：140 → **143**（极坐标径向范围三键，均取 P1）✓
            // ★ 2026-10-16（**BUG-219**）：143 → **144**（`material.fill.flow_speed`，取 P1）✓
            SettingPhase.P1 to 144,
            SettingPhase.P4 to 0,
        )
        expectedByPhase.forEach { (phase, expected) ->
            val actual = all.count { it.phase == phase }
            check(actual == expected) {
                "阶段 ${phase.name} 应有 $expected 项，实为 $actual（首版设置总表 §2.1）"
            }
        }

        // ── 一级组（组件）的逐组项数 ────────────────────────────────────────
        //
        // ⚠️ **2026-09-21 重分组**：14 组（按"被调整的方面"）→ **7 组（按组件）**。
        // ⚠️ **2026-10-08（U12c）再分组**：8 → **11 组**（用户：「每组最好不超30项，
        //    但总的分组不要过多」）。★ 本表**必须与 `SettingGroups.ALL` 逐一对应** ——
        //    上面 `check(expectedByGroup.size == SettingGroups.ALL.size)` 守这条。
        // 本表是**计数**，不是判据 —— 判据（"同一组件的键必须在同一个一级组里"）
        // 在 `RegistryProbe` 里以结构性断言写死，见该文件 §10。
        // 这里仍然逐组写死数字，理由同 `expectedByKind`：
        // **只校验总和的话，两组各错 1 项相互抵消仍会通过。**
        val expectedByGroup = mapOf(
            // 顺序与 `SettingGroups.ALL` 一致（按使用频率，第 0 位是特例组）。
            // ── ★★★ 2026-10-08（U12c）：48 → **20** ────────────────────────────
            //
            // 原 48 = 布局与行数 3 + 高亮与同步 3 + 字间距 2 + **歌词颜色 14**
            //        + **歌词描边 14** + 字体 4 + 译文 3 + 无歌词 5。
            // 拆走后 = 20 = 布局与行数 3 + 高亮与同步 3 + 字间距 2 + 字体 4
            //            + 译文 3 + 无歌词 5 = **20** ✓（6 个细类）
            // ⚠️ 搬运的两族**逐项**记在新组件那两行（28 = 14 + 14）。
            SettingGroups.LYRIC to 20,
            // ── ★★★ 2026-10-08（U12c）**新组件** ─────────────────────────────
            // 28 = 歌词颜色 14（主歌词 4 已唱 RGB+明度 / 4 未唱 RGB+明度 /
            //        4 译文 RGB+明度 / 2 颜色来源）
            //    + 歌词描边 14（主歌词 4：开关/宽度/跟随字重/联动比例 + 3 色 RGB
            //                  + 副歌词 3：开关/宽度/跟随字重 + 3 色 RGB
            //                  + 副歌词同步开关 1）
            // ⚠️ 28 是拆后**最大的组件**，仍 ≤30 ✓（这正是"拆完两个都不超"的判据①）。
            SettingGroups.LYRIC_COLOR_OUTLINE to 28,
            // ── ★★★ 2026-10-08（U12c）：47 → **15** ────────────────────────────
            //
            // 原 47 = 显隐与交互 7 + 位置与尺寸 5 + 形状与背景 8 + 边框 7
            //        + 材质 12 + 身后窗口 5 + 右端插槽 3。
            // 拆走「形状与背景」+「边框」→ `ISLAND_APPEARANCE`（15），
            // 拆走「材质」+「身后窗口」→ `ISLAND_MATERIAL`（17）。
            // 剩 = 15 = 显隐与交互 7 + 位置与尺寸 5 + 右端插槽 3 = **15** ✓（3 个细类）
            // ★ 2026-10-13（**U16**）：15 → **16**（右端插槽 3 → **4**，
            //   新增 `island.right_slot.spectrum_bars`）✓
            // ★ 2026-10-15（**§3.75**）：16 → **18**（右端插槽 4 → **6**，
            //   新增 `island.right_slot.spectrum_color_mode` + `…spectrum_color`）✓
            // ★ 2026-10-16（**§3.77-F**）：18 → **21**（右端插槽 6 → **9**）✓
            // ★ 2026-10-17（**撤销总开关组**）：21 → **22**（`island.enabled` 回到
            //   「显隐与交互」细类）✓
            SettingGroups.ISLAND to 22,
            // ── ★★★ 2026-10-08（U12c）**新组件** ─────────────────────────────
            // 15 = 形状与背景 8（形态 1 / 圆角 1 / 角曲率风格 1 / 曲率指数 1 /
            //                    背景不透明度 1 / 背景色 1 / 旋转角 1 / 颜色来源 1）
            //    + 边框 8（开关 1 / 宽度 1 / 模式 1 / **溶边范围 1** / 颜色来源 1 / RGB 3）
            // ── ★★★ 2026-10-13（BUG-186）：15 → **16** ───────────────────────────
            // + 1 = 边框 · **溶边渐变范围**（`island.border.dissolve_band_percent`，
            //   ★ 仅在「溶边」档生效 · 默认 50 = 已交付的那个观感 ⇒ **加键零变化**）✓
            SettingGroups.ISLAND_APPEARANCE to 16,
            // ── ★★★ 2026-10-08（U12c）**新组件** ─────────────────────────────
            // 17 = 材质 12（高光 1 + 渐变 4 + 液态玻璃 4 + 颗粒 1 + 画质 1 + 封面图模糊 1）
            //    + 身后窗口 5（模糊半径 / 折射厚度 / 折射量 / 色散 / 饱和度）
            // ── ★★★ 2026-10-09（R7）：17 → **18** ────────────────────────────
            // + 1 = 身后窗口 · 用显卡算折射（`material.render.gpu_backend`，
            //        默认 false ⇒ 走 CPU ⇒ **开箱逐像素不变**）✓
            // ── ★★★ 2026-10-16（**BUG-219**）：18 → **19** ──────────────────
            // + 1 = 材质 · 流光流速（`material.fill.flow_speed`，默认 100 = 原速
            //        ⇒ **开箱逐帧不变**）✓
            SettingGroups.ISLAND_MATERIAL to 19,
            // 15 = 开关与位置 3 + 条与高度 6 + 外观 4 + 进度色差 2
            // − 1（2026-09-22 `spectrum.enabled` 移入「总开关」）= 14。
            // + 1（2026-09-23 `spectrum.bar_shape`「频谱条形状」并入「条与高度」）= 15。
            // ★ 2026-10-17（**撤销总开关组**）：15 → **16**（`spectrum.enabled` 回到
            //   「开关与位置」细类）✓
            SettingGroups.SPECTRUM to 16,
            // 6 = 显示开关 1 + 随字号缩放 1 + 形状 1 + 转动 1 + 转速 1 + 黑胶样式 1
            //     （2026-09-21 加后三项：用户附黑胶截图要求「圆形封面 + 允许转动」；
            //      同日再加 `cover.vinyl`：用户要求「旋转圆形封面**加上黑边**」）
            // − 1（2026-09-22 `cover.enabled` 移入「总开关」）= 5。
            // + 1（2026-09-30 `cover.lock`，封面态「锁定封面」**唯一新键**）= 6。
            // ── ★★★ 2026-10-13（BUG-188）：6 → **7** ─────────────────────────
            // + 1 = 封面 · **封面清晰度（锐化）**（`cover.sharpness`）✓
            // ★ 2026-10-17（**撤销总开关组**）：7 → **8**（`cover.enabled` 回到
            //   「开关与缩放」细类）✓
            SettingGroups.COVER to 8,
            // 8 = 触发 3 + 布局 3 + 进度与隐藏 2
            // + 1（2026-09-22 `controls.animation_style`，并入「触发」）= 9。
            // + 1（2026-09-27 `controls.progress_style`，并入「进度与隐藏」）= 10。
            SettingGroups.CONTROLS to 10,
            // 5 = 开关与质量 3 + 时长与风格 2
            // − 2（2026-09-22 `animation.enabled` 与 `animation.style` 移入「总开关」）= 3
            //     （余：`quality` / `frame_rate` / `duration_scale`）。
            // + 1（2026-09-28 `animation.frame_log`，落新增「诊断」细类）= **4**。
            // ★ 2026-10-17（**撤销总开关组**）：4 → **6**（`animation.enabled` +
            //   `animation.style` 回到「动画」组；后者落在新增细类「动效风格」）✓
            SettingGroups.ANIMATION to 6,
            // 4 = 菜单外观 1 + 菜单延迟 2 + 行为 1
            // ★ 2026-09-22：4 → **3** —— `settings.auto_ungate_on_touch` 已移入 `TOP_LEVEL`。
            // ★ 2026-10-17（**撤销总开关组**）：3 → **4**（该键回到「行为」细类）✓
            SettingGroups.MENU_SETTINGS to 4,
        )
        check(expectedByGroup.size == SettingGroups.ALL.size) {
            "期望分组数与 SettingGroups.ALL 不一致：${expectedByGroup.size} vs ${SettingGroups.ALL.size}"
        }
        check(expectedByGroup.values.sum() == EXPECTED_TOTAL) {
            "一级组的项数之和应为 $EXPECTED_TOTAL，实为 ${expectedByGroup.values.sum()}"
        }
        expectedByGroup.forEach { (group, expected) ->
            val actual = byGroup[group]?.size ?: 0
            check(actual == expected) {
                "组件「$group」应有 $expected 项，实为 $actual"
            }
        }
        // 反向校验：不应存在期望表之外的"野生分组"（拼错组名会让某项从树上消失）。
        val stray = byGroup.keys - expectedByGroup.keys
        check(stray.isEmpty()) { "存在未在 SettingGroups.ALL 声明的分组：$stray" }

        // ── 二级组（细类）的结构校验（2026-09-21 新增）────────────────────────
        //
        // 这里刻意**不写第五张"逐细类计数"表**。理由：细类有 29 个，而它们
        // 全部由 `SettingGroups.SUBS` 一处声明 —— 再抄一份计数表就是**同一件事写两遍**，
        // 且每次加项都要改两处（本项目在四张计数表上已经反复吃过这个亏）。
        // 改用**双向结构断言**，它比计数更强：
        //   ① 每个 descriptor 的 `subGroup` 必须在其 `group` 下声明过
        //      （写错细类名 → 当场失败，而不是"该项在树里静默消失"）；
        //   ② 每个声明过的细类必须**至少有一项**（防"声明了却没人用"的空壳细类，
        //      那会让设置窗口渲染出一个点进去是空的分类）。
        check(SettingGroups.SUBS.keys == SettingGroups.ALL.toSet()) {
            "SettingGroups.SUBS 的组件集合与 ALL 不一致：" +
                "SUBS=${SettingGroups.SUBS.keys}，ALL=${SettingGroups.ALL}"
        }
        val undeclared = all.filter { it.subGroup !in SettingGroups.SUBS[it.group].orEmpty() }
        check(undeclared.isEmpty()) {
            "以下设置项的 subGroup 未在其组件下声明：${
                undeclared.map { "${it.key}(group=${it.group}, subGroup=${it.subGroup})" }
            }"
        }
        val emptySubs = SettingGroups.ALL.flatMap { component ->
            SettingGroups.SUBS.getValue(component).filter { sub ->
                all.none { it.group == component && it.subGroup == sub }
            }.map { "$component · $it" }
        }
        check(emptySubs.isEmpty()) { "以下细类声明了但没有任何设置项（空壳分类）：$emptySubs" }

        // 颜色项一律禁止 alpha（总表 §1.6 + recon/10 §4.5 强制）。
        val alphaAllowedColors = all.filter { it.kind == SettingKind.COLOR && it.alphaAllowed }
        check(alphaAllowedColors.isEmpty()) {
            "首版颜色项均须 alphaAllowed=false（recon/10 §4.5），违规：${alphaAllowedColors.map { it.key }}"
        }

        // P0 的 5 项应是总表 §2.1 逐字点名的那 5 个键 —— 用键名而非数量校验，
        // 因为"数量对但内容是别 6 项"会让 P0 验收失去意义。
        // ⚠️ 2026-09-18：原 6 项中的 `layout.emptySlot.keepPlaceholder`（序号 36）
        // 已按用户裁定移除（理由见 `groupI()`），故这里同步降为 5 项。
        val expectedP0Keys = setOf(
            "island.enabled",                    // 序号 1
            "layout.translation.enabled",        // 序号 9
            "window.position",                   // 序号 11
            "layout.noLyrics.mode",              // 序号 37
            "layout.noLyrics.showTrackTitle",    // 序号 38
        )
        val actualP0Keys = byPhase[SettingPhase.P0].orEmpty().map { it.key }.toSet()
        check(actualP0Keys == expectedP0Keys) {
            "P0 键名与总表 §2.1 不符。缺：${expectedP0Keys - actualP0Keys}；多：${actualP0Keys - expectedP0Keys}"
        }
    }

    /**
     * 权威项数。**写成常量而不是字面量**，是为了让这个数字只有一处定义，
     * 且能被将来的测试直接引用。
     *
     * 基线是总表 §2 的 **40 项**；2026-09-16 因用户真机对比原实现 0.8.9 后
     * 要求「译文色不跟随歌词色」，**新增 1 项** `main_lyric.translation.color`
     * → 41 项。这是**产品决定**带来的偏离，不是统计错误：
     * 总表 §2 记录的是首版基线，而本项是基线之后新增的能力
     * （理由见 recon/24 §8 的「待用户裁决取舍点」）。
     *
     * ---
     * ⚠️ **以下各条是「历史快照」** —— 每条写的是**当时那一刻**的总数，
     * **不是当前值**。当前值**只由本常量的初始化式决定**（`EXPECTED_TOTAL = …`）。
     *
     * 本段**刻意不写"当前是 N 项"** —— 写了就一定会陈旧（见本类 KDoc 里
     * 「44 项 → 60 项 → 实为 100 项」那两次复发的记录）。
     * 故读到这里时**不要**把最后一个 `→ **N 项**` 当成当前值：它不是。
     * ---
     *
     * ## 再 +2（2026-09-16 第三轮真机反馈）→ **43 项**
     *
     * 用户原话：「目前的歌词上下行是**已唱歌词和在唱歌词**，而我的预想中是在唱歌词
     * 和未唱歌词」「**一二行歌词间距调整选项也没做**」→ 新增：
     *   · `layout.line_mode`（LIST）—— 显示哪几行，默认「在唱 + 未唱」
     *   · `layout.line_gap_px`（SEEKBAR）—— 行间距
     *
     * ## 再 +1（2026-09-17 裁定 ①）→ **44 项**
     *
     * `performance.host_lyrics_probe`（SWITCH，默认 `true`）——
     * 反射读宿主内部完整歌词轴，让「未唱行」能显示（BUG-041 的收尾）。
     * 为什么做成**独立设置项**而不是照抄既有的"性能档位字段"：
     * 原实现把它挂在 `PerformanceProfile` 里（低性能档关闭它），
     * 但"要不要读整轴"与"要不要降性能"是两件事 —— 独立成项后，
     * 用户可以只关探针而保留性能，也可以只降性能而保留探针。
     * `performance.low` 打开时**同样会**跳过探针（对齐原实现），见 `HostLyricsProbe`。
     * > ⚠️ **2026-09-19 更正**：`performance.low` **已删除**（逐键审计确认它没有任何读取点），
     * >   `HostLyricsProbe.shouldProbe` 也一并删除。故上面这句只描述**当时**的设计，
     * >   不代表现状。（本段是**计数历史**，按约定"追加不覆盖、更正另记一行"。）
     *
     * ## 再 +3（2026-09-17 用户点名）→ **47 项**
     *
     * `spectrum.color`（COLOR）· `spectrum.anchor`（LIST）· `spectrum.height_pct`（SEEKBAR）。
     *
     * 用户原话：「频谱**根据封面取色也没有做到**，**自行设定颜色也没做到**，
     * 频谱**相对歌词框底边/顶边高度**也没做到。」
     *
     * 前两条的根因**不是"没做"而是"做了但静默回退"**：
     * `spectrum.color_mode = cover` 依赖封面图，而封面图只在 `cover.enabled` 时才加载
     * → 用户不开"显示封面"就永远拿不到主色，且**不报错**（见 `coverImageFor` 的 KDoc）；
     * 而 `manual` 档用的是**硬编码常量**，用户根本没有键可改 → 补 `spectrum.color`。
     * 第三条：频谱此前恒占满歌词区高度，补"贴哪条边 + 占多少百分比"两个键。
     *
     * ## 再 +9（2026-09-17 用户拍板「方案 A」）→ **56 项**
     *
     * **−3 +12**：删掉 3 个 `COLOR` 键（`main_lyric.fill.color` / `fill.unsung` /
     * `translation.color`），换成 **12 个 `SEEKBAR`**（三色各 R / G / B / 明度）。
     *
     * 用户原话：「SPW 设置页中**我指名的 RGB 三色滑杆，明度滑杆呢**？」
     * + 「**在 SPW 设置页的设置项不要引去其他窗口**」。
     * 理由与取舍见 `groupF()` 的注释块。
     *
     * ## −1（2026-09-18 用户裁定）→ **59 项**
     *
     * **移除** `layout.emptySlot.keepPlaceholder`（SWITCH / P0）。
     *
     * 用户原话：「**未实现的实现，无效（未唱行相关）的移除**」。
     * 该项既**不是**"没做"、也**不是**"做了有用" —— 它的两个分支在真机上**都不可达**
     * （`rawNext == null` 时无条件塌缩、`rawNext.text.isBlank()` 恒为假），
     * 于是"能拖但画面毫无变化"。审计取证见 `recon/_设置项有效性审计.md`。
     *
     * 这不是"放弃修复"，而是把**一个假开关**换成**一段确定行为**：
     * 时间轴现在固定为"有下一行就用、没有就 null"（`PlaybackTimeline.nextLineFor`），
     * 渲染层的槽位塌缩逻辑不变。P0 因此由 6 降为 **5**，SWITCH 由 19 降为 **18**，
     * `LYRIC_LAYOUT` 组由 3 降为 **2**；P1 不变（该项是 P0）。
     *
     * ## 再 +1（2026-09-18 用户点名）→ **60 项**
     *
     * `menu.opacity_pct`（SEEKBAR，默认 `100`，值域 `30..100`）——
     * 用户原话：「**允许调整右键菜单的透明度**」。
     *
     * 它进 `SettingGroups.MENU_SETTINGS`（2026-09-21 重分组后的一级组件「菜单与设置」；
     * 此前叫 `SETTINGS_UI`、再往前 `MENU`）；
     * 于是自动落在右键菜单的「行为与设置 ▸ 菜单与设置」下，
     * 与两个延迟并排），并上 SPW 设置页。SEEKBAR 27 → **28**、P1 54 → **55**、
     * `MENU` 组 2 → **3**，其余分布不变。
     *
     * ## 再 +4（2026-09-19 用户点名，四条各自独立）→ **89 项**
     *
     * | 键 | kind | 组 | 用户原话 |
     * |---|---|---|---|
     * | `island.hit.cover` | SWITCH | `BEHAVIOR_INTERACTION` | 「追加**封面命中**开关。」 |
     * | `layout.noLyrics.hideWhenInstrumental` | SWITCH | `LYRIC_LAYOUT` | 「追加**检测到纯音乐时不显示歌词/曲名/作者**的开关」 |
     * | `main_lyric.letter_spacing_tenth_px` | SEEKBAR | `LYRIC_LAYOUT` | 「允许调整主副歌词的**字间距**，可选以一定比例联动调整。」 |
     * | `translation.letter_spacing.follow_main` | SWITCH | `LYRIC_LAYOUT` | 同上（"可选联动"的那一半） |
     *
     * 四项**全部标 P1**，故 P0 仍为 **5**、P1 80 → **84**；
     * SWITCH 23 → **26**、SEEKBAR 44 → **45**；
     * `BEHAVIOR_INTERACTION` 3 → **4**、`LYRIC_LAYOUT` 2 → **5**。
     *
     * ⚠️ `layout.noLyrics.hideWhenInstrumental` 的组：**先落 `LYRIC_CONTENT`、后按
     *   team-lead 裁定挪回 `LYRIC_LAYOUT`**（兄弟键 `layout.noLyrics.mode` /
     *   `.showTrackTitle` 都在那儿）。故 `LYRIC_CONTENT` 最终仍是 **14**（净 0），
     *   而 `LYRIC_LAYOUT` 是 2 → **5**。两张表的分组数之和不变，故本常量不受影响。
     *
     * ⚠️ **`spectrum.bar_pitch_px` 的 range 由 `3..20` 放宽为 `2..20`**（同一天，
     *   用户原话「频谱条间距不能再小吗？」）—— 那**不改项数**，故本常量不变；
     *   依据（1 px 柱 + 1 px 缝的物理下限）见 `groupG()` 里该项的注释。
     *
     * ## +3（2026-09-19 / 20）→ **91 项**
     *
     * ⚠️ **本条是 2026-09-20 的文档一致性核查"补记"的，不是一次新改动。**
     *   常量在此之前已从 88 涨到 91，而**计数史停在 88**
     *   （`CHANGELOG.md` 的最后一条"设置面"记录也停在 90）。
     *
     *   **那 +3 具体是哪三项，未能从现有记录复原** —— 如实记录这个缺口，
     *   **而不是编造三条填上**（本项目纪律：拿不到证据就写"未找到证据"）。
     *
     * > **教训：计数史的"最后一条"必须与常量同步更新。**
     * > `verify()` 守的是"**三张表之和 == 常量**"，但它**守不住 KDoc 里的历史叙述** ——
     * > 那只能靠人。而一条停在旧值的计数史，会让下一个人要么以为"常量是 88"，
     * > 要么花时间去查一个**本来就不存在**的改动。
     * >
     * > **同族纪律**：删项要同步 4 张计数表（`EXPECTED_TOTAL` / `byKind` / `byGroup` / `byPhase`）——
     * > 这里要补第 5 处：**KDoc 的计数史**。
     *
     * ## −1（2026-09-19）→ **88 项**
     *
     * **移除 `performance.low`**（原「手动低性能模式」/ 右键菜单「减少动画」）。
     *
     * 用户报「SPW 插件配置页内**死设置项变多了**」。逐键审计（89 键）的结论是
     * **真正没有任何消费点的只有这一项** —— 全工程只有"声明"与"菜单项"两处，
     * **没有任何读取点**。它原本的住户（低性能档跳过宿主歌词探测）已停用，
     * 连带的 `HostLyricsProbe.shouldProbe` 也全树无调用点。
     * 详见其原声明处留下的注释。
     *
     * SWITCH 26 → **25**；`PERFORMANCE` 组 1 → **0**（分组常量保留，理由见上）。
     *
     * ## +1（2026-09-21）→ **96 项**
     *
     * **新增 `animation.frame_rate`**（LIST，默认 `AUTO`）。
     *
     * 用户原话：「当前播放器播放歌曲动画能否**帧率适配屏幕刷新率**？我发现多数人的屏幕
     * 刷新率在 100 以上，动画的刷新率不能太低。……新增一个适应 60 / 90 / 120 / 144 Hz
     * 不同刷新率的档位。」
     *
     * 原先帧率**绑在 `animation.quality` 上**（`STANDARD` → 33 ms，其余 16 ms），
     * 只有 30 / 60 两档。本轮把它拆成独立设置项 —— 理由：
     * 「**开哪些效果**」与「**跑到多少帧**」是正交的两件事，绑在一起就无法表达
     * "全效果 + 144 Hz" 或 "省 CPU + 60 Hz" 这类合理组合。
     *
     * 连带改动：`animation.quality` 的**选项文案**去掉了 fps 描述
     * （两个设置项描述同一件事，用户会不知道该信哪个），语义收窄为"开哪些效果"。
     *
     * ## +2（2026-09-21 晚）→ **98 项**
     *
     * 用户要求：「加入**字体粗细缩放滑杆**，加入**主副歌词字号比例**（1.0 为中值）」。
     *
     * | 新键 | 组 | 说明 |
     * | --- | --- | --- |
     * | `main_lyric.font.weight` | LIST | 字体粗细（`NORMAL` / `BOLD`），**`NORMAL` = 常规**（开箱零变化） |
     * | `layout.translation.scale_percent` | LYRIC | 副歌词字号百分比，**100 = 标准 0.64** |
     *
     * 两项**都做成百分比、且 100 为中值** —— 理由是同一个：
     * 用户的心智模型是"**偏离标准多少**"，而不是"绝对值是多少"。
     * 百分比让"中点 = 1.0 = 出厂观感"成立，且与既有的
     * `letter_spacing_tenth_px` / `line_gap_px` 等滑杆风格一致。
     *
     * ## 再 +3（2026-09-21）→ **103 项**
     *
     * 用户附**黑胶唱片**截图：「**新增圆形封面选项并允许其转动。**」
     *
     * | 键 | kind | 组 |
     * |---|---|---|
     * | `cover.shape` | LIST | `COVER` |
     * | `cover.rotate` | SWITCH | `COVER` |
     * | `cover.rotate_period_s` | SEEKBAR | `COVER` |
     *
     * 三项全标 P1：LIST 20 → **21**、SWITCH 28 → **29**、SEEKBAR 49 → **50**；
     * P1 95 → **98**；`COVER` 组 2 → **5**。
     *
     * **默认全部保持"开箱零变化"**：`ROUNDED`（现状）、`rotate = false`（不转）。
     * 故老用户升级后观感**逐像素不变** —— 新能力只在用户主动打开时才出现。
     *
     * ## 再 +1（2026-09-21）→ **104 项**
     *
     * 用户原话：「**旋转圆形封面加上黑边，使其呈现黑胶样式**
     * （在**外围**加黑边，不要侵占封面内容）。」
     *
     * | 键 | kind | 组 |
     * |---|---|---|
     * | `cover.vinyl` | SWITCH | `COVER` |
     *
     * P1：SWITCH 29 → **30**、P1 98 → **99**、`COVER` 组 5 → **6**。
     *
     * 默认 `false`（开箱零变化）。**环画在封面 `size` 之外**，并同步加宽左侧槽位
     * （`IslandWindow` 的 `areaLeft` 改用 `coverSlotWidth(...)`）——
     * 否则环会盖到歌词或从词岛左边缘戳出去。
     *
     * ## 再 +2（2026-09-22）→ **106 项**
     *
     * 用户原话：「动效风格总开关管太多控件的动画了，不好细究，**拆散开来，分到各自的组件组里，
     * 保留总开关**。」+「像**频谱、动效总开关**之类的**顶层项**放到 SPW 配置页**前面的位置**
     * （**脱离原来的组件组，这是特例**）。」
     *
     * | 变更 | 键 | 去向 |
     * | --- | --- | --- |
     * | **新增** | `lyric.animation_style` | 「歌词」组 ▸ 高亮与同步 |
     * | **新增** | `controls.animation_style` | 「播放控件」组 ▸ 触发 |
     * | **移组** | `island.enabled` / `spectrum.enabled` / `cover.enabled` / `animation.enabled` | → 新第 0 组「总开关」▸ 组件开关 |
     * | **移组** | `animation.style`（**保留**，语义改为"总覆盖"） | → 新第 0 组「总开关」▸ 动效风格总开关 |
     *
     * ⚠️ **只新增 2 项、不是"每个组件一项"** —— 判据是"**总开关到底管了谁**"：
     * `AnimationRuntime.kt` 的 `Motions` KDoc 判据表写明**只有 `CONTROLS_APPEAR` 与 `LYRIC_EXIT`
     * 两条 `followsGlobalStyle = true`**；词岛显隐 / 封面交叉淡化 / 菜单淡入是 `false`
     * （各自的理由见 `Motions` 的 KDoc）。给那三个组件造子项 = **造死设置**，
     * 正是用户在 `recon/39` 里点名反对的形状。
     *
     * **计数变化**：LIST 21 → **23**、P1 99 → **101**；
     * 组：歌词 45 → **46**、词岛 21 → **20**、频谱 15 → **14**、封面 6 → **5**、
     * 播放控件 8 → **9**、动画 5 → **3**，新增「总开关」**5**。
     * SWITCH / SEEKBAR / P0 **均不变**（移组不改 kind，也不改 phase）。
     *
     * ## 再 +2（2026-09-22）→ **108 项**
     *
     * | 新键 | kind | 组 | 用户原话 / 规格 |
     * |---|---|---|---|
     * | `background.color` | COLOR | `ISLAND` ▸ 背景 | 「**有些选项提供自选色但是并没有提供选色入口，如背景设色**」 |
     * | `background.cover.angle_deg` | SEEKBAR | `ISLAND` ▸ 背景 | 「**允许用户调整封面角度（0-360 度）**，**旋转后封面自动缩放铺满**词岛背景，**不再设调整设置**」 |
     *
     * ⚠️ `background.color` 与 `spectrum.color` 是**同一个问题的同一个解法**：
     *   后者 2026-09-17 已修过一遍（`spectrum.*` 也没有颜色键、手动档写死常量）。
     *   本次是**同族排查**（MEMORY 纪律 12）在 `background.*` 上发现的同一形态。
     *
     * ⚠️ `background.color_mode` 同轮**加了一档** `cover_image`（「封面图」）——
     *   **LIST 的项数不变**（加选项不是加键），故本常量不受它影响。
     *
     * **计数变化**：SEEKBAR 50 → **51**、COLOR 1 → **2**；
     * P1 101 → **103**；词岛组 20 → **22**；
     * SWITCH / LIST / EDITTEXT / FONT / P0 / 其余分组**均不变**。
     *
     * 两项**默认值都保持"开箱零变化"**：`background.color` 默认 `#14161A`
     * （= 旧硬编码底色逐位相同）、`angle_deg` 默认 `0`（不旋转），
     * 且 `background.color_mode` 默认仍是 `manual` —— 老用户升级后**逐像素不变**。
     *
     * ## 再 +2（2026-09-22 晚）→ **110 项**
     *
     * | 新键 | kind | 组 | 用户原话 |
     * |---|---|---|---|
     * | `layout.island.height.follow_font` | SWITCH | `ISLAND` ▸ 位置与尺寸 | 「将词岛大小随字体大小变化**剥离**出来，改为**是否跟随**和**手动调整**两档选择」 |
     * | `layout.island.height.ratio_percent` | SEEKBAR | `ISLAND` ▸ 位置与尺寸 | 「允许调整词岛**收起状态下的高度**（**用与所应用行数的歌词行总高度的比值**）」 |
     *
     * ⚠️ 两者都落在 **`layout.` 前缀**下（不是随便放的）：`IslandWindow` 按该前缀
     *   决定"要不要重算几何" —— 换个前缀就是**改了没反应**。
     * ⚠️ `follow_font` 默认 `true` = 旧行为 ⇒ **开箱零变化**；`ratio_percent` 只在
     *   `follow_font = false` 时被读，故它的默认值不影响出厂画面。
     *
     * **计数变化**：SWITCH 30 → **31**、SEEKBAR 51 → **52**；P1 103 → **105**；
     * 词岛组 22 → **24**；其余（LIST / COLOR / EDITTEXT / FONT / P0 / 其余分组）**均不变**。
     *
     * ## 再 +2（2026-09-23）→ **112 项**
     *
     * | 新键 | kind | 组 ▸ 细类 | 用户原话 |
     * |---|---|---|---|
     * | `spectrum.bar_shape` | LIST | `SPECTRUM` ▸ 条与高度 | 「**频谱条的样式提供圆角和方角**」 |
     * | `translation.outline.follow_weight` | SWITCH | `LYRIC` ▸ 歌词描边 | 「**允许副歌词的描边跟随字体粗细**」 |
     *
     * 两项**默认值都保持"开箱零变化"**：`ROUND`（= 改动前柱体两端圆头）、
     * `follow_weight = false`（= 改动前副描边宽度不随字重变化）。
     * 老用户升级后观感**逐像素不变** —— 新能力只在用户主动选 / 主动开时出现。
     *
     * ⚠️ `follow_weight` 的默认 `false` **不是**因为"出厂字重是 100 所以无所谓"：
     *   `weight_scale` 默认 100 时 `f(1.0) = 1`，两种默认确实都零变化 ——
     *   但那是**巧合**。判据是"**改过字重的老用户升级后画面是否变化**"，
     *   而默认 `true` 会让那批人的副描边宽度突然变（`f(1.5) = 1.25`）。
     *   完整论证见该 descriptor 的 KDoc。
     *
     * **计数变化**：SWITCH 31 → **32**、LIST 23 → **24**；P1 105 → **107**；
     * 歌词组 46 → **47**、频谱组 14 → **15**；
     * 其余（SEEKBAR / COLOR / EDITTEXT / FONT / P0 / 其余分组）**均不变**。
     */
    // **2026-09-25 第 2 期「材质」**：112 → **120**（+8）。
    // SWITCH 32 / **SEEKBAR 57** / **LIST 26** / EDITTEXT 1 / **COLOR 3** / FONT 1；
    // P1 107 → **115**；ISLAND 24 → **32**（新增「材质」细类）；
    // `SELF_DRAWN_KEYS` 25 → **26**（`material.fill.color_end` 是 COLOR ⇒ 不上设置页）。
    // **2026-09-25 第 3 期「材质 L2」**：120 → **122**（+2）。
    // SWITCH 32 / **SEEKBAR 58** / **LIST 27** / EDITTEXT 1 / COLOR 3 / FONT 1；
    // P1 115 → **117**；ISLAND 32 → **34**（材质细类 6 → 8）。
    // `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（两键都不是 COLOR）。
    // **2026-09-26 预留**：122 -> **123**（+1，`material.backdrop.blur_radius`）。
    // SWITCH 32 / **SEEKBAR 59** / LIST 27 / EDITTEXT 1 / COLOR 3 / FONT 1；
    // P1 117 -> **118**；ISLAND 34 -> **35**（材质细类 8 -> 9）。
    // `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**。
    // **2026-09-27 用户诉求 ⑭**：124 -> **125**（+1，`controls.progress_style`）。
    // SWITCH 32 / SEEKBAR 59 / **LIST 28** / EDITTEXT 1 / COLOR 3 / FONT 1；
    // P1 118 -> **119**；CONTROLS 9 -> **10**（「进度与隐藏」细类 2 -> 3）。
    // `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（LIST 键由宿主设置页承载）。
    // **2026-09-28 每秒帧率日志**：125 -> **126**（+1，`animation.frame_log`）。
    // 用户原话：「**日志每秒打印帧率和实际ms，方便检查达成情况**」+「**测试用**」。
    // **SWITCH 33 -> 34** / SEEKBAR 59 / LIST 28 / EDITTEXT 1 / COLOR 3 / FONT 1；
    // **P1 120 -> 121**；**ANIMATION 3 -> 4**（新增「诊断」细类）。
    // `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（SWITCH 键由宿主设置页承载）。
    // ⚠️ 键名取 `animation.` 前缀**是刻意的**：`SettingDomains.FRAME = setOf("animation.")`
    //    按命名空间派生 ⇒ 换前缀会让"改它 ⇒ 推入计量器"这条链**静默失效**。
    // **2026-09-29**：126 -> **127**（+1，`island.border.mode`）。
    // LIST 28 -> 29；P1 121 -> 122；ISLAND 35 -> 36（边框细类 6 -> 7）。
    // **2026-09-29**：总数**不变**（`font.weight_scale` SEEKBAR -> `font.weight` LIST）。
    // SEEKBAR 59 -> **58**；LIST 29 -> **30**。
    // **2026-09-30 · 封面态（`recon/67`）**：127 -> **128**（+1，`cover.lock`）。
    // ★ **全案唯一新键** —— 封面态其余元素**全部从既有键派生**（`recon/67` §14 复用表）。
    // 用户定的边界（§14.1 逐字）：「**已有同形态的设置项**…**不再设立独特的调整选项**，
    // 而是使用通用控制项。**锁是新功能，不在其列**」。
    // **SWITCH 34 -> 35** / SEEKBAR 58 / LIST 30 / EDITTEXT 1 / COLOR 3 / FONT 1；
    // **P1 122 -> 123**；**COVER 6 -> 7**（「开关与缩放」细类 3 -> 4）。
    // `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（SWITCH 键由宿主设置页承载）。
    // ★ 附：`shape.type` 的 options 4 -> **5**（加 `"COVER_ONLY"`）**不改变任何键数** ——
    //   它是**既有 LIST 键的一个新取值**，故四张计数表里只有上面那些数字动。
    //   但它会让 `ColorPickerProbe` §⑯.7 的**形态两两组合数 6 -> 10**（期望值必须重算）。
    // ★ 2026-10-01：**128 → 130** —— 新增「液态玻璃」子组 2 键
    //   （`material.liquid.glare_strength` SEEKBAR / `material.liquid.depth_effect` SWITCH）
    //   ★ 用户指令：照**他提供的 Android 参数面板**建子组，只暴露"我们能做且面板里有"的。
    // ★ 2026-10-01 第二批（用户指令：「**两批都做**」）：**130 → 132**
    //   （`material.liquid.surface_alpha` / `material.liquid.shadow_strength`，两项都 SEEKBAR）。
    //   **SEEKBAR 59 → 61** / SWITCH 36 / LIST 30 / EDITTEXT 1 / COLOR 3 / FONT 1；
    //   **P1 125 → 127**；**ISLAND 38 → 40**（材质细类 11 → 13）。
    //   `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（SEEKBAR 键由宿主设置页承载）。
    //   ★ 两项的**取值**都经 `_diag_spw/SurfaceShadowProbe.java` 实测复核（不是照抄面板）：
    //     `alpha = 0.10` ⇒ 中心亮度 +65.4%、**远未过曝**；`投影强度 0.60` ⇒ 下方 −70.3%、**不死黑**。
    // ★ 2026-10-01 ④b 第二批（用户「**开4b**」）：**132 → 136**
    //   （`material.backdrop.refraction_height` / `refraction_amount` / `dispersion` /
    //     `saturation`，**四项全是 SEEKBAR**）。
    //   **SEEKBAR 61 → 65** / SWITCH 36 / LIST 30 / EDITTEXT 1 / COLOR 3 / FONT 1；
    //   **P1 127 → 131**；**ISLAND 40 → 44**（材质细类 13 → 17）。
    //   `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（SEEKBAR 键由宿主设置页承载）。
    //   ★ 四项**只在 `material.backdrop.blur_radius > 0` 时才有效果** ⇒ **开箱逐像素不变** ✓
    //   ★ 值全部照用户提供的 Android 面板（折射厚度 16 / 折射量 32 / 色散 0.35 / 饱和度 1.5×），
    //     ★★ 其中**色散不能照抄 0.35**（面板刻度与业界差 20 倍，实测 0.35 等于没做）——
    //     见 `BackdropRefraction.DISPERSION_SCALE` 与 `material.backdrop.dispersion` 的 KDoc。
    // ★ 2026-10-03（⑱ 时钟 · ③c）：**136 → 137**（+1，`clock.hour_format` LIST）。
    //   **LIST 30 → 31**；**P1 131 → 132**；**ISLAND 44 → 45**（「位置与尺寸」细类 +1）。
    // ★ 2026-10-03（⑱ 时钟 · **⑥ 字形风格**）：**138 → 139**（+1，`clock.style` LIST）。
    //   用户原话（逐字，2026-09-28）：「**七段数码管、点阵、像素风的时间显示样式**」。
    //   **LIST 32 → 33**；**P1 133 → 134**；**ISLAND 46 → 47**（「位置与尺寸」细类 13 → 14）。
    //   `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（LIST 键由宿主设置页承载）。
    //   ★★ **为什么现在才加**：2026-10-03 只做了「七段数码管」一档 ⇒ 当时**刻意不加本键**
    //     （纪律：**选项数 = 真实状态数**；给了档但选了没反应 = 最多次的缺陷形态）。
    //     三档都真能画之后，本键才出现。★ 默认 `SEGMENT` ⇒ **老配置零回归**。
    //   ★ 几何：`render/DotMatrix.kt`（点阵 / 像素风共用同一张 5×7 真值表）+
    //     门面 `render/ClockGlyphs.kt`（真机与预览两个绘制点都只调它 ⇒ 不可能"只改一处"）。
    //
    // ★ 2026-10-09（**R7 GPU 后端**）：**139 → 140**（+1，`material.render.gpu_backend` SWITCH）。
    //   **SWITCH 36 → 37**；**P1 134 → 135**；**ISLAND_MATERIAL 17 → 18**（「身后窗口」细类 5 → 6）。
    //   `SELF_DRAWN_KEYS` 26 / `COLOR_GROUPS` 9 **均不变**（SWITCH 由宿主设置页承载）✓
    //   ★ 它**只影响"折射烘焙走 CPU 还是 GPU"**，默认 `false` = CPU
    //     ⇒ **开箱逐像素不变**（"默认值要稳"的兑现点）✓
    //   ★ 动因：`GpuLiquidGlass`（JNA 直调 `opengl32.dll`，**零新增依赖 / 零许可风险**）
    //     实测 **2.86 ms** vs CPU 单线程 **47.4 ms** ⇒ 值得给用户一个开关 ✓
    //   ⚠️ **失败自动回退 CPU**（驱动不支持 / 非 Windows / GL 建上下文失败）⇒ 不会黑屏 ✓
    // ★ 2026-10-13（BUG-186）：140 → **141**（`island.border.dissolve_band_percent`，
//   用户「引入新键给用户调节渐变范围」）✓
// ★ 2026-10-13（**BUG-188**）：141 → **142**（`cover.sharpness`，
//   用户「使其显示得更清晰可变」）✓
// ★ 2026-10-13（**U16**）：142 → **143**（`island.right_slot.spectrum_bars`，
//   用户「新增5条小频谱的频谱条数选择（4和6根）」）✓
// ★ 2026-10-15（**§3.75**）：143 → **145**（`island.right_slot.spectrum_color_mode`
//   + `island.right_slot.spectrum_color`，用户「**开放对插槽频谱的独立选色**」，
//   拍板范围 = **完整取色模式**）✓
// ★ 2026-10-16（**§3.77-F**）：145 → **148**（极坐标径向范围三键，用户「**允许调整环形频谱的
//   跳动幅度和范围**」+ 拍板「径向范围（内外半径可调）」·「给放射+环形两档」）✓
// ★ 2026-10-16（**用户要求「加入对流光流速的流速调整设置」**）：148 → **149**
//   （新增 `material.fill.flow_speed`「流光流速 %」—— `gradient_style` 的 KDoc 里
//   早先登记的欠账，见该键的 KDoc）✓
const val EXPECTED_TOTAL = 149

    /**
     * **不上 SPW 设置页**的设置键 —— 即只能由本插件自绘窗口承载的项。
     *
     * ## 为什么需要这个集合（2026-09-16 真机验收后新增）
     *
     * SPW 插件的设置是**声明式**的：`preference_config.json` 里声明的项由**宿主渲染**
     * 在它自己的设置页内。但宿主的控件只有 5 种（`switch` / `list` / `seekbar` /
     * `edittext` / `button`，见 `recon/18` §A），**没有取色器、没有字体选择器**。
     *
     * 因此 [EXPECTED_TOTAL] 项里有 **27 项**无法声明进 `preference_config.json`，
     * 必须留在自绘窗口。本集合就是那份名单 —— **两处消费者共用它**：
     *
     * | 消费者 | 用途 |
     * |---|---|
     * | [dev.cdl.settingsui.SettingsWindow] | 只显示这些项（其余 107 项已由 SPW 设置页承载，重复显示会造成"两个地方都能改"的混乱） |
     * | `tools/gen_preference_config.py` | 生成 JSON 时跳过这些键（脚本**从本文件解析**该集合，不自行维护副本） |
     *
     * ⚠️ 上句的两个数字是**派生计数**，**不要手抄**：
     *   · 不可声明的 = `SELF_DRAWN_KEYS` 的项数；
     *   · ★ 其余 = `EXPECTED_TOTAL` − `SELF_DRAWN_KEYS` − **`retired = true` 的项数**
     *     （★ 2026-10-11 **U12** 新增第三项：液态玻璃下架后，12 个 `material.*` 项标了
     *     [SettingDescriptor.retired] ⇒ ★ 它们**既不在自绘窗口、也不在 SPW 设置页**）✓
     * 2026-09-24 取证：`EXPECTED_TOTAL` 从 108 涨到 112，此处**原记 83、实为 87**，
     * 漂移 4 而无人报错 —— 现由 `tools/check_derived_counts.py` 守卫
     * （源码真值 + 产物 `preference_config.json` 双重交叉核对）。
     *
     * ## 为什么以 Kotlin 侧为单一事实来源
     *
     * 生成脚本是 Python、运行期是 Kotlin，两者无法共享常量。
     * 故把名单定义在**运行期代码**里（它必须永远正确，否则界面会漏项或重项），
     * 让 Python 脚本去解析它 —— 方向是"脚本跟随代码"，而不是两边各写一份。
     *
     * ## 逐项理由
     *
     * - `main_lyric.fill.color` / `main_lyric.fill.unsung` / `main_lyric.translation.color`
     *   —— SPW 无原生取色器。
     *   既有的处理是**把颜色写死**、只暴露"是否从封面取色"的开关（见 `recon/18` §D.3）；
     *   我们选择保留自定义能力，代价是这些项必须自绘。
     * - `main_lyric.font.recent` —— 它是**开放集合**（用户装过的任意字体），
     *   而 SPW 的 `list` 控件**只支持静态选项**（原实现 `docs/development.md:23` 明文）。
     * - `window.position` —— 是"屏幕 + 坐标 + 锚点"的**复合值**，且由**拖动**写入，
     *   不是用户手调的项。原实现同类键（`screen` / `position_x` / …）也不上设置页
     *   （`HostSettings.kt:119-129`）。
     */
    //
     // **仍**需要自绘承载的键（`preference_config.json` 生成时会被排除）。
     //
     // ## 原记：3 个颜色键也在这里（BUG-009 的成因）
     //
     // 原记：三个颜色键（已唱 / 未唱 / 译文）归自绘窗口承载。
     //
     // 实为：**那让它们在 SPW 设置页里彻底消失** —— 用户在设置页既看不到当前颜色，
     // 也不能手改，只能靠一个按钮去弹自绘窗口。而用户报的正是
     // 「SPW 内设置栏与预期不符」（BUG-009）。
     //
     // 现做法（`recon/20` 的 D 节方案）：三个颜色键**上 SPW 设置页**，
     // 以 `edittext` 形式呈现 —— **真值可见、可手改**（写 `#RRGGBB`），
     // 取色器改为**每色一个 `button`**（`pickSungColor` 等，见生成脚本的 `BUTTONS`）。
     // 两条路径写**同一个键**，且读侧两种类型都认（见 `SettingsStore.readRaw` 的 COLOR 分支）
     // —— 故手改 hex 与取色器写值可以共存。
     //
     // ## 仍留在这里的两项：**确实无法**在 SPW 设置页表达
     //
     // · `main_lyric.font.recent` —— **开放集合**（近期使用字体），
     //   而宿主 `list` **只支持静态选项**（`recon/20` §A.7 字节码穷尽确证）；
     // · `window.position` —— **复合值**（`x,y`），且语义是"窗口自己写的状态"
     //   而非用户可编辑项。
     //
     // ## ⚠️ 本块内**不许写带引号的字符串**（哪怕在注释里）
     //
     // `tools/gen_preference_config.py` 用正则从**源码文本**里解析这个集合
     // （`setOf\(` 到第一个 `)`），故注释里的引号字符串会被**当成键**读进去。
     // 2026-09-17 实测踩过：注释里写了两个带引号的例子，生成脚本报
     // 「以下 key 不在注册表里（拼写错误？）」。
     // → 要举例就举**不带引号**的，或把例子写在 `val` **上方**的 KDoc 里。
     //
    //
     // **颜色组** —— 一个取色器承载的一组键。
     //
     // ## 为什么要有这张表（2026-09-19）
     //
     // 用户原话：「**RGB 数值步进器不再放到右键菜单中**」+
     // 「**右键菜单中不再使用三色 RGB 滑杆选色，而是直接拉起自绘取色窗口**」。
     //
     // 菜单的设置树是**按 `kind` 自动生成**的（`SEEKBAR → − 值 + 步进器`），
     // 而颜色键的 `kind` 正是 `SEEKBAR` —— 于是「歌词颜色」分类下自动长出
     // 12 根 RGB 步进器。要让它们变成"一个打开取色器的入口"，
     // 就必须有一处**知道"哪几个键属于同一个颜色"**的信息。
     //
     // 那张信息**只能有一份** —— 菜单、SPW 设置页的按钮、取色器写回，
     // 三处都依赖同一个分组。故放在注册表里（本文件是键的权威来源）。
     //
     // ⚠️ 与 `gen_preference_config.py` 的 `BUTTON_ANCHOR` 是**同一组前缀**
     //    （那个脚本在构建期读源码，读不到 Kotlin 的运行时表）。
     //    **改这里必须同步改那里** —— 否则设置页的按钮会插到错位置。
     //
    data class ColorGroup(val prefix: String, val title: String, val keys: List<String>)

    /** 见 [ColorGroup] 的 KDoc。顺序 = 菜单里的出现顺序。 */
    val COLOR_GROUPS: List<ColorGroup> = listOf(
        ColorGroup(
            "main_lyric.fill.color", "已唱歌词颜色",
            listOf(
                "main_lyric.fill.color.r", "main_lyric.fill.color.g",
                "main_lyric.fill.color.b", "main_lyric.fill.color.lightness",
            ),
        ),
        ColorGroup(
            "main_lyric.fill.unsung", "未唱歌词颜色",
            listOf(
                "main_lyric.fill.unsung.r", "main_lyric.fill.unsung.g",
                "main_lyric.fill.unsung.b", "main_lyric.fill.unsung.lightness",
            ),
        ),
        ColorGroup(
            "main_lyric.translation.color", "译文歌词颜色",
            listOf(
                "main_lyric.translation.color.r", "main_lyric.translation.color.g",
                "main_lyric.translation.color.b", "main_lyric.translation.color.lightness",
            ),
        ),
        ColorGroup(
            "main_lyric.outline.color", "主歌词描边色",
            listOf(
                "main_lyric.outline.color.r", "main_lyric.outline.color.g",
                "main_lyric.outline.color.b",
            ),
        ),
        ColorGroup(
            "translation.outline.color", "副歌词描边色",
            listOf(
                "translation.outline.color.r", "translation.outline.color.g",
                "translation.outline.color.b",
            ),
        ),
        ColorGroup(
            "island.border.color", "边框色",
            listOf(
                "island.border.color.r", "island.border.color.g", "island.border.color.b",
            ),
        ),
        // 单键组（`kind = COLOR`）：取色器走 `openColorTool` 那条单键路径。
        ColorGroup("spectrum.color", "频谱颜色", listOf("spectrum.color")),
        // 2026-09-22：背景色同样是**单键 COLOR**（用户：「**背景设色**没有选色入口」）。
        // 与 `spectrum.color` 走同一条单键路径（`openColorTool`）——
        // 于是取色器承载的颜色组由 7 组变为 **8 组**，不需要任何新代码。
        ColorGroup("background.color", "背景颜色", listOf("background.color")),
        // 2026-09-25 第 2 期「材质」：显式渐变止色，同样是**单键 COLOR**，
        // 走 `openColorTool` 的单键路径 ⇒ 取色器承载的颜色组 8 → **9 组**。
        ColorGroup("material.fill.color_end", "渐变止色", listOf("material.fill.color_end")),
        // ★★★ 2026-10-15（**§3.75**）：插槽频谱的自选色，同样是**单键 COLOR**，
        // 走 `openColorTool` 的单键路径 ⇒ 取色器承载的颜色组 9 → **10 组**。
        // ⚠️ 标题与注册表里该键的 `title`（「插槽频谱颜色」）**逐字一致** ——
        //   按钮与窗口标题都取它，不一致会让用户以为点错了东西。
        ColorGroup(
            "island.right_slot.spectrum_color",
            "插槽频谱颜色",
            listOf("island.right_slot.spectrum_color"),
        ),
    )

    /** [key] 属于哪个颜色组；不属于任何组时返回 `null`。 */
    fun colorGroupFor(key: String): ColorGroup? = COLOR_GROUPS.firstOrNull { key in it.keys }

    val SELF_DRAWN_KEYS: Set<String> = setOf(
        "main_lyric.font.recent",
        "window.position",
        // 2026-09-25 第 2 期材质：显式渐变止色（COLOR）。宿主无颜色控件 ⇒ 不上设置页，
        // 由取色器承载。入口见 host/CdlPlugin 的 pickGradientEndColor
        // 与 tools/gen_preference_config.py 的 BUTTONS 表。
        "material.fill.color_end",
        // ── 2026-09-18：12 根颜色滑杆改为「取色器」承载 ─────────────────────
        //
        // 用户要求「把 RGB 滑杆和明度滑杆以及色号输入框**换成**取色器样式」。
        // 「换成」= **设置页不再显示这 12 根滑杆**，但**键必须留着**：
        //   · `IslandWindow.mixerColor` 仍按 `$prefix.r/.g/.b/.lightness` 合成，
        //     键没了会**静默回退默认值**（不报错 —— 用户只会觉得颜色改不动）；
        //     ⚠️ 本块内**不得出现带引号的字符串**（哪怕注释里）——
        //        `gen_preference_config.py` 按正则取引号内容当键，会把注释里的引号也读进去。
        //        实测踩过：注释里写了引号包裹的四个字，生成脚本报「以下 key 不在注册表里」。
        //   · 取色器把选中的颜色写回这 4 个键。
        // 加进本集合 = 不上 SPW 设置页（`spwPageDescriptors = all - SELF_DRAWN_KEYS`）。
        "main_lyric.fill.color.r",
        "main_lyric.fill.color.g",
        "main_lyric.fill.color.b",
        "main_lyric.fill.color.lightness",
        "main_lyric.fill.unsung.r",
        "main_lyric.fill.unsung.g",
        "main_lyric.fill.unsung.b",
        "main_lyric.fill.unsung.lightness",
        "main_lyric.translation.color.r",
        "main_lyric.translation.color.g",
        "main_lyric.translation.color.b",
        "main_lyric.translation.color.lightness",
        // ── 2026-09-19：其余颜色组同样改由取色器承载 ──────────────────────
        //
        // 用户追问：「前面不是让你改颜色控件了吗？为什么现在还有设置项是滑杆改色或者输入色号改色?」
        // 上一轮**只处理了歌词三色**（4 键制），漏了这三组：
        //   · 主歌词描边色（3 键制：r/g/b）
        //   · 副歌词描边色（3 键制）
        //   · 边框色（3 键制）
        // 外加 `spectrum.color`（单键 COLOR，走 `openColor` 那条路径）。
        //
        // ⚠️ 注意键制不同：**这三组没有 lightness**（消费点是按三通道分别直接读，
        //    不走 mixerColor 的合成公式），
        //    ⚠️ 本块内**不得出现带引号的字符串**（哪怕注释里）——
        //       生成脚本按正则取引号内容当键。**这条我已经踩过两次了。**
        //    而歌词三色是 `mixerColor` 的 4 键制。`openColorGroup` 要能同时处理两种。
        "main_lyric.outline.color.r",
        "main_lyric.outline.color.g",
        "main_lyric.outline.color.b",
        "translation.outline.color.r",
        "translation.outline.color.g",
        "translation.outline.color.b",
        "island.border.color.r",
        "island.border.color.g",
        "island.border.color.b",
        // 频谱色是**单键 COLOR**（不是 r/g/b 组），同样改由取色器承载。
        // 它留在注册表里且 kind 仍是 COLOR，故走 openColorTool 那条单键路径。
        "spectrum.color",
        // 2026-09-22：背景色（单键 COLOR）。用户原话「有些选项提供自选色但是并没有
        // 提供选色入口，如背景设色」—— 它与频谱色是同一个形态、同一个解法。
        // 加进本集合 = 不上 SPW 设置页（那边没有颜色控件，上去等于该键消失）。
        "background.color",
        // ★★★ 2026-10-15（§3.75）：插槽频谱的**自选色**（单键 COLOR）。
        // 与上面两项同一个形态、同一个解法：宿主无颜色控件 ⇒ 必须留在自绘窗口，
        // 否则该键在 SPW 设置页上**彻底消失**（F §1.3 断点 A）。
        // 入口见 host/CdlPlugin 的 pickRightSlotSpectrumColor
        // 与 tools/gen_preference_config.py 的 BUTTONS 表。
        "island.right_slot.spectrum_color",
    )

    init {
        // 自检：SELF_DRAWN_KEYS 里的键必须真实存在（拼写错误会让"某项本该自绘"
        // 变成"它悄悄上了 SPW 设置页"，而 SPW 那边又没有对应控件 → 该项**彻底消失**）。
        val unknown = SELF_DRAWN_KEYS - all.map { it.key }.toSet()
        check(unknown.isEmpty()) { "SELF_DRAWN_KEYS 含未定义的键：$unknown" }
    }

    /**
     * 应当由 SPW 设置页承载的项（`preference_config.json` 的声明源）。
     *
     * ## 2026-10-17：与 [all] **只差"自绘/下架"过滤**，不再有组归属覆盖
     *
     * 「总开关」组撤销后，设置页与右键菜单读**同一份组归属** ——
     * `PAGE_GROUP_OVERRIDES` 已删除（它存在的唯一理由就是"设置页移回原组、
     * 菜单保持不变"，而现在两边都已是原组）。
     *
     * ⚠️ 输入仍是 [declared]（声明顺序），因为排序是**稳定**的 ——
     * 同 (组, 细类) 内的顺序 = **输入顺序**，必须与
     * `tools/gen_preference_config.py` 的 `order_by_group`（输入同样是声明顺序）一致。
     */
    val spwPageDescriptors: List<SettingDescriptor>
        get() = declared
            .filterNot { it.key in SELF_DRAWN_KEYS }
            // ★★★ 2026-10-11（**U12**）：**已下架的项不上设置页**。
            //
            // ★ 判据与 `tools/gen_preference_config.py` 的 `retired` 过滤**必须是同一条** ——
            //   ★ 两侧不一致时 `RegistryProbe` §10.10（"设置页顺序 == 注册表顺序"）会红：
            //   ★★ 本轮实测正是如此（JSON = 102 项 / 注册表 = 114 项）✗
            // ★ 语义：`retired = true` ⇒ ★ 既不在自绘窗口、**也不在 SPW 设置页** ✓
            // 见 `SettingDescriptor.retired` 的 KDoc ✓
            .filterNot { it.retired }
            // 稳定排序：同 (组, 细类) 内仍是声明顺序 —— 与 `all` 同一个 rank 公式，
            // 也与 `tools/gen_preference_config.py` 的 `order_by_group` 同源。
            .sortedBy { d -> SettingGroups.ALL.indexOf(d.group) * 1000 + d.subOrder() }

    /** 应当由自绘窗口承载的项。 */
    val selfDrawnDescriptors: List<SettingDescriptor>
        get() = all.filter { it.key in SELF_DRAWN_KEYS }
}

// ─────────────────────────────────────────────────────────────────────────────
// `spectrum.bar_shape` 的选项值 与 值 → 渲染参数的换算
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 为什么这两个常量必须是**顶层 `const val`**（而不是写在 descriptor 里的字面量）
//
// 它们有**两个消费者**，分属两个包：
//
// | 消费者 | 用途 |
// |---|---|
// | 本文件的 `spectrum.bar_shape` descriptor | `default` / `options` 的候选值 |
// | `render/SpectrumGeometry.spectrumBarCornerPx(...)`（**绘制侧**） | 按值决定圆角半径 |
//
// 若各写各的字符串，一次改名就会让绘制侧**静默收不到**（`coerce` 认不出 → 回退
// `default` → 表现为"选了方角没反应"）。这与 `cover.shape` 的 `COVER_SHAPE_*`
// 是同一条纪律。
//
// ## ⚠️ 生成脚本靠**正则**从本文件读这两个常量
//
// `tools/gen_preference_config.py` 的 `parse_string_consts` 用
// `const val X = "…"` 的形态把常量名还原成字面量，再写进 JSON 的
// `entry_values` / `default_value`。故：
//   · 必须是 `const val`（不是 `val`、不是 `enum`）；
//   · 值必须是**字符串字面量**（不能是拼接式）。

/** `spectrum.bar_shape` = **圆角**（柱体两端为圆头）。**默认值** —— 等于改动前的观感。 */
const val SPECTRUM_BAR_SHAPE_ROUND = "ROUND"

/** `spectrum.bar_shape` = **方角**（柱体四角为直角）。 */
const val SPECTRUM_BAR_SHAPE_SQUARE = "SQUARE"

/**
 * `controls.progress_style` = **经典**（已播放段实心 + 圆点恒显示）。
 *
 * **默认值** —— 等于改动前（2026-09-27 之前）的观感。
 */
const val PROGRESS_STYLE_CLASSIC = "CLASSIC"

/**
 * `controls.progress_style` = **流光**（已播放段羽化高光推进 + 圆点仅悬停/拖动时显示）。
 *
 * 用户诉求 ⑭ 原文：「新增进度条形态（**小圆不恒显示** + **平时羽化高光推进**）」。
 */
const val PROGRESS_STYLE_GLOW = "GLOW"

/**
 * `spectrum.bar_shape` 的取值 → **柱体圆角比例**（`1f` = 现状圆角，`0f` = 方角）。
 *
 * ## 为什么返回"比例"而不是"布尔"或"px"
 *
 * 绘制侧的圆角**已经有一个上限来源**（`min(柱宽, MIN_BAR_HEIGHT)`，见
 * `SpectrumGeometry.spectrumBarCornerPx`）。若这里返回 px，就会出现**第二处**
 * 决定"圆角多大"的算式 —— 而本项目在 `SpectrumGeometry` 的 KDoc 里反复写明
 * "圆角上限只有一个来源"。返回**比例**则只改"取多少"，不改"上限从哪来"：
 *
 * ```
 * 圆角(px) = min(柱宽, MIN_BAR_HEIGHT) × spectrumBarCornerRatio(shape)
 *            └── 上限仍由绘制侧唯一决定 ──┘   └── 本函数只决定这一项 ──┘
 * ```
 *
 * ## 为什么是纯函数、为什么公开
 *
 * 与 `letterSpacingTracking` / `spectrumBarSlot` 同一理由：**单位与取值的换算
 * 错了不会报错**，只表现为"看起来不太对"。抽成公开纯函数后探针可以直接断言
 * （见 `RegistryProbe` §12.2），而不是靠"下次谁再忘了同步"。
 *
 * ## 未知值一律回退 `1f`（圆角）
 *
 * `SettingDescriptor.coerce` 对 LIST 项**只接受声明过的值**，故生产路径不会
 * 传进未知值；这里的回退是**纵深防御**（配置来自更新/更旧的版本时，
 * 宁可保持改动前的观感，也不要突然变成方角）。
 *
 * @param shape `spectrum.bar_shape` 的当前值（`SPECTRUM_BAR_SHAPE_*`）；
 *   `null`（键不存在）或任何未知值 → `1f`。
 */
fun spectrumBarCornerRatio(shape: String?): Float =
    if (shape == SPECTRUM_BAR_SHAPE_SQUARE) 0f else 1f

/**
 * ★★ `main_lyric.karaoke.mode` 的三个 wire 值（**与注册表 `options` 逐字一致**）。
 *
 * ★★★ 2026-10-10（审计 v4 · B-2）**从 `IslandWindow` 的 `private companion object` 迁到这里**：
 *   ★ 原位置是 **private** ⇒ 预览侧（`settingsui/IslandPreviewPane`）**根本取不到**
 *     ⇒ 它只能拿字面量比 ⇒ 那正是本项目最忌的「同一 wire 值两处各写一份」✗
 *   ★ 迁到 `settings/`（**wire 值常量本来就住这里**，如 `SPECTRUM_BAR_SHAPE_*`）
 *     ⇒ ★ 真机与预览**引用同一个常量** ⇒ 档位值不可能漂移 ✓
 *   ⚠️ **不改名**：13 处调用点一行不动（只换定义位置 + 加 import）✓
 *
 * ★ 三档语义（几何差别实现在 `KaraokeTextRenderer.render` 的 `smooth` 参数）：
 *   · [KARAOKE_MODE_PER_CHAR]（**默认**，= 改动前）—— 分界点**吸附到字符簇边界**，一个字一个字地亮；
 *   · [KARAOKE_MODE_SMOOTH] —— 分界点**线性扫过整行**（`advance × progress`），像一条线推过去；
 *   · [KARAOKE_MODE_LINE] —— **整行**：开唱即整行已唱色（进度**二值化**）。
 */
public const val KARAOKE_MODE_PER_CHAR: String = "PER_CHAR"
public const val KARAOKE_MODE_SMOOTH: String = "SMOOTH"
public const val KARAOKE_MODE_LINE: String = "LINE"

/**
 * 边框样式档 · **实线**（`island.border.mode` 的 wire 值，**默认**）。
 * ★★★ 2026-10-10（审计 v4 · B-1）新增 —— **为什么必须抽成常量**：
 *   真机（`IslandWindow.drawIslandBorder`）与预览（`IslandPreviewPane`）**都要判这一档**，
 *   而此前真机写的是**字面量** `"FADE_OUT"`、预览**根本没判**（= 该档在预览里纹丝不动）。
 *   ⇒ ★ 若只在预览里再抄一个字面量，就是本项目最多次的
 *     「**同一规则两处各写一份 ⇒ 迟早漂移**」形态 ✗
 *   ⇒ ★ 收敛到**唯一出口**：两个调用点都引用本常量 ✓
 */
public const val BORDER_MODE_SOLID: String = "SOLID"

/**
 * 边框样式档 · **向外渐变**（`island.border.mode` 的 wire 值）。
 *
 * ★ 语义：**不画实线**，边缘由内向外柔和淡出（用户原话「无边框 / 无边界」）✓
 * ★ 层参数由 [borderFadeLayers] 给出（**唯一出口**）—— 真机与预览**都调它** ✓
 * ★ 值必须与注册表 `island.border.mode` 的 `options` 里的 wire 值**逐字相同**。
 */
public const val BORDER_MODE_FADE_OUT: String = "FADE_OUT"

/**
 * ★★★ 2026-10-12（**BUG-182**）：**边界溶边档** —— ★ **不画线、也不用边框色**。
 *
 * ## 用户原话（逐字）+ 两次确认
 *
 * > 「**词岛边界增加新类型，无边线内边缘向边缘梯度淡化边界（当背景不透明度不为0时）**」
 * > （★ 两问确认：淡化对象 = **岛屿边缘整体溶边**；带宽 = **复用「边框宽度」**）
 *
 * ## 与 [BORDER_MODE_FADE_OUT] 的差别（★ 为什么它不算重复）
 *
 * | | `FADE_OUT` | **本档** |
 * |---|---|---|
 * | 淡化的对象 | ★ **边框色**（把 `borderColor` 画成多层描边） | ★★ **岛屿内容本身**（把最外一圈**擦**成透明） |
 * | 效果 | ★ 边缘有一条"柔化的边框色带" | ★★ **岛屿边缘整体消散**（像没有边） |
 *
 * ## 实现（★ 用 `AlphaComposite.DstOut`，**不是 clip**）
 *
 * ★ 层参数仍由 [borderFadeLayers] 同源的斜坡给出（`inset` 递增 / `alpha` 由外向内递减 ✓）
 *   ⇒ ★ **最外圈全擦、最内圈不擦** ✓ —— 与 `FADE_OUT` **同一个几何**、只换**合成方式** ✓
 * ★ `DstOut` 是**合成方式**（不是 `setClip`）⇒ 与 `MaterialLayers` 文件头"绝不能用 `setClip`"
 *   的纪律**不冲突** ✓（AA 仍由 Java2D 产生 ✓）
 *
 * ## ⚠️ 生效条件：**`background.opacity != 0`**
 *
 * ★ 用户原话逐字带了这个括号 ✓ —— ★ 背景不透明度为 0 ⇒ 岛体本来就透明 ⇒ **无物可溶** ✓
 */
public const val BORDER_MODE_EDGE_DISSOLVE: String = "EDGE_DISSOLVE"

/**
 * 「向外渐变」档的**层参数**（⑨，2026-09-29 用户拍板 D1=B/D3）。
 *
 * ## 为什么是顶层纯函数
 *
 * `IslandWindow.drawIslandBorder` 是 `private fun` ⇒ **探针源集看不到**
 * （本项目已知限制）。⇒ 把"设置值 → 渲染参数"的换算抽到这里，
 * 与 [spectrumBarCornerRatio] **同一形态**（换算只此一处，且**可被断言**）。
 *
 * ## ★★ D1 = B（不外扩）—— 这是本函数最该被钉住的性质
 *
 * 用户 2026-09-29 拍板 **D1 = B**：淡出带**只画在形状内部**，不外扩。
 * ⇒ 返回的每一层 **`inset >= 0`**（向内缩）。
 * ★ 若将来有人把它改成"外扩"，`inset` 会变负 ⇒ `RegistryProbe` 的 **A3 当场红**。
 *
 * ## 参数
 *
 * @param strokeWidthPx 边框线宽（px，已由 `borderWidthTenthPx / 10f` 换算）。
 * @return 由**外圈到内圈**的层列表（`inset` 递增、`alpha` 递减）。
 *   层数为 0 表示**本档不适用**（调用方走 `SOLID` 原路径）。
 */
fun borderFadeLayers(strokeWidthPx: Float): List<BorderFadeLayer> {
    if (!(strokeWidthPx > 0f)) return emptyList()
    val layers = 5
    val band = strokeWidthPx * 3.5f
    return (0 until layers).map { i ->
        val t = i / (layers - 1f)                    // 0 = 最外圈，1 = 最内圈
        BorderFadeLayer(
            insetPx = band * t,                      // ★ ≥ 0 ⇒ 只向内（D1=B）
            alpha = ((1f - t) * (1f - t) * 255f).toInt().coerceIn(0, 255),
            widthPx = strokeWidthPx * (1f - t * 0.6f),
        )
    }
}

/**
 * ★★★ 「溶边」过渡带**占岛体高度的上限**（%）。
 *
 * ## ★★ 为什么是 50 而不是 100（几何上限，不是偏好）
 *
 * ★ 过渡带是**从每条边向内**量的：上边一条、下边一条 ⇒ 带宽 > 半高时**两条带重叠** ✗
 * ★★ 而溶边走 `AlphaComposite.DstOut`（**擦除**）⇒ 重叠处**连乘擦除**
 *   （★ 与 SRC_OVER"重叠混色"的直觉**相反** ✗ —— 本项目在 BUG-182b 已踩过一次）⇒
 *   中线附近被擦两次 ⇒ ★★ 剖面**先升后降**（U16 实测：带宽 40px / 岛高 56px ⇒
 *   `…119,124,89,64,66,68,69,70,71,72,72,72,72,71,69,64,60` —— 到中线就塌 ✗✗）
 * ⇒ ★ **50% 恰好让两条带在中线相接、不重叠** = 该口径的**几何上限** ✓
 *
 * ★ 设置项的 `range` 上限与此常量同值；`dissolveBandPx` 里**再夹一次**（纵深防御）✓
 */
public const val MAX_DISSOLVE_BAND_PERCENT: Int = 50

/**
 * ★★★ 2026-10-13（**BUG-185**）：「溶边」的**过渡带宽度**（px）—— ★ **唯一出口**。
 *
 * ## 为什么不再等于「边框宽度」（BUG-182 的口径被取代）
 *
 * ★ BUG-182 时用户拍板「**带宽复用边框宽度**」；★★ 但 2026-10-13 用户反馈
 *   「**阶梯还是很明显，有没有平滑过渡的呈现形式**」⇒ ★ U16 实测量化：
 *
 * | 过渡带 | 最大台阶 | 级数 |
 * |---|---|---|
 * | **8 px（= 边宽上限）** | **26** | **8** |
 * | 16 px | 13 | 16 |
 * | 24 px | 8 | 25 |
 *
 * ★★ **台阶 = alpha 量程 ÷ 带宽像素数** —— 要从 178 降到 0，而只有 8 个像素
 *   ⇒ ★ **最多 8~9 级 ⇒ 每级 ~22** ✗ —— ★★ 这是**像素数的天花板，换任何算法都改不掉**。
 * ★★ 且 `island.border.width` 的**上限就是 8 px** ⇒ ★ 用户**无法**靠它拿到更长的带 ✗
 * ⇒ ★ 用户 2026-10-13 拍板：**过渡带跟随岛体高度（= 半高）** ✓
 *
 * ## 口径
 *
 * `band = 岛体高度 × 0.5` ⇒ ★ 胶囊岛上 = 「**从上下边缘向中线整体渐隐**」
 *   · ★ 实测（56 px 岛 ⇒ 28 px 带）：**最大台阶 ≤ 8**（对比 8 px 带的 26）✓
 *   · ★ **随岛体大小自动缩放** ⇒ 大字号岛不会"带太短"，小字号岛不会"带太长" ✓
 *   · ★ **不需要新设置项**（用户选的就是"自动"）✓
 *
 * ⚠️ **返回值取整到 1 px** —— ★ 让下游的描边片宽恰好 **1.0 px**（★ 现已改距离场，仅作口径记录）：
 *   ★★ 非整数片宽会让相邻像素的覆盖率交替 ⇒ **剖面非单调** ✗
 *   （U16 实测：带宽 24 px ⇒ `0,102,9,18,112,…` 来回跳 ✗）
 *
 * ★★★ 2026-10-13（**BUG-186**）：★ 比例改由**设置项**给
 * （`island.border.dissolve_band_percent`，用户指令：「**引入新键给用户调节渐变范围**」）✓
 *
 * @param islandHeightPx **岛体**高度（px）—— ★ 不是面板高度、不是歌词区高度
 * @param bandPercent 过渡带占岛体高度的百分比 —— ★ 键 `island.border.dissolve_band_percent`
 *   （默认 **50**；★ 上限 [MAX_DISSOLVE_BAND_PERCENT]，**这里再夹一次** = 纵深防御 ✓）
 * @return 过渡带宽度（px，≥ 1）；非有限 / 非正输入 / 百分比 0 ⇒ `0f`（调用方据此**整档不画**）
 */
fun dissolveBandPx(islandHeightPx: Float, bandPercent: Int): Float {
    if (!islandHeightPx.isFinite() || islandHeightPx <= 0f) return 0f
    val pct = bandPercent.coerceIn(0, MAX_DISSOLVE_BAND_PERCENT)
    if (pct <= 0) return 0f
    // ★ `+0.5f` 再取整 = 四舍五入（只对正数；上面已挡非正）—— 不引 kotlin.math 的 import ✓
    return (islandHeightPx * pct / 100f + 0.5f).toInt().coerceAtLeast(1).toFloat()
}

// ★★★ 2026-10-13（**BUG-187**）：**`borderDissolveLayers` 已删除** ——
//   ★ 它原来给出「溶边的 28 条描边环」的层参数（带宽 / 片宽 / alpha 斜坡）。
//   ★★ 而 U17 实测坐实：**用 1px 描边去描 `squirclePath` 的折线短弦会串珠** ⇒
//     真机两端出现 **152 个孤立麻点** ✗（用户报的「**左右两端出现黑色麻点**」）
//   ⇒ ★ 溶边改为**距离场掩版**（[dev.cdl.window.DissolveMask]，逐像素解析、**不描边**）
//     ⇒ 麻点 **0** ✓ —— ★ 该函数因此**没有任何生产消费者** ⇒ 删除（★ A3：删要留痕）✓
//   ★ 它的口径（带宽 = 岛体高度的百分比）由 [dissolveBandPx] 保留 ✓


/**
 * 「向外渐变」的一层。
 *
 * @param insetPx 相对形状边缘的**向内**缩进（★ **恒 ≥ 0** —— D1=B 的机械判据）。
 * @param alpha 该层的不透明度（0..255，**由外向内递减**）。
 * @param widthPx 该层的描边宽度。
 */
data class BorderFadeLayer(
    val insetPx: Float,
    val alpha: Int,
    val widthPx: Float,
)

