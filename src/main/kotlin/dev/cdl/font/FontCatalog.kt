// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.Locale

/**
 * 字体来源的**四分法** —— 前三条来自用户 2026-09-16 的诉求原话：
 *
 * > 「SPW 插件配置字体选择那里使用近期使用字体列表，本机完全字体列表加从电脑中选择字体包的形式。」
 *
 * ★★ 第四条 [Source.HOST] 是 2026-09-29 增补的（`recon/65` 路线 ②）——
 * 用户指令：「**复用宿主字体用作默认字体**，所以也应该保留用户自导入和自选字体的入口」。
 * 它**不违背**上面那句原话，而是补上"本机字体"里**宿主进程自带**的那一部分
 * （宿主 jar 内的 16 个族，含 `Inter Light` 等细档族名 —— 见 [FontSource.HOST] 的 KDoc）。
 *
 * 本文件把这几条来源**归一成一个类型**，让原实现（设置窗口 / 右键菜单 / 未来的按钮入口）
 * 只面对一种数据结构，而不是几套各写各的 `List<String>`。
 *
 * ## 为什么必须归一（而不是几个独立函数）
 *
 * 各条来源的**下游用法完全一致**：都是"给用户一个族名列表，选中一个写进
 * `main_lyric.font.family`"。若不归一，三处调用点各自决定"怎么排序、要不要去重、
 * 选中后要不要记进历史"，这些规则会立刻开始漂移 —— 本项目已经在
 * `FontHistory.record` 与 `serialize` 的分工上踩过一次同类问题（见其 KDoc）。
 *
 * ## 各条来源的**能力差别**（这是归一不了的，必须如实标注）
 *
 * | 来源 | 覆盖范围 | 是否可离线枚举 | 是否需要用户交互 |
 * |---|---|---|---|
 * | [Source.RECENT] | 用户用过的（容量 10） | 是（读配置字符串） | 否 |
 * | [Source.SYSTEM] | 本机**已安装**的全部字体族 | 是（`GraphicsEnvironment`） | 否 |
 * | **[Source.HOST]** | **宿主进程自带**的族（宿主 jar 内条目） | ⚠️ **要预热**（[HostFonts.warmUpAsync]，≈1.4 s 后台） | 否 |
 * | [Source.FILE] | 用户手选的 `.ttf` / `.otf` / `.ttc` 文件包 | **否**（必须弹文件对话框） | **是** |
 *
 * [Source.FILE] 的"需要交互"是本质的，不是实现偷懒：字体包在磁盘上的位置
 * **只有用户知道**，插件不做目录扫描（项目纪律 §7.3：只读用户显式选中的文件，
 * 不扫描目录）。故它**不能**被折叠进"枚举可用字体"这个动作里。
 * ★ [Source.HOST] 的"要预热"同属**本质**约束（35 个 `Font.createFont` 实测 ≈1.4 s，
 * 不能压在 EDT 上），但它**不需要用户交互** ⇒ 与 [Source.FILE] 不同族。
 *
 * ## 许可证红线（务必保留这段）
 *
 * 本类**只产出族名与路径，从不复制字体文件**。
 * 「字体必须先查能不能随包而不是免不免费」（项目纪律 §7.4）——
 * MiSans 免费商用但**不可再分发**，故插件包内不放任何字体：
 * [Source.FILE] 只是"引用用户自己机器上的文件"，
 * ★ [Source.HOST] 只是"引用**宿主安装目录里已有**的字体"（那份字体随宿主分发，
 * **不是我们分发的**）⇒ 我们既不复制也不打包。
 */
enum class FontSource {
    /** 近期使用过的族名（[FontHistory] 的历史，容量 [FontHistory.CAPACITY]）。 */
    RECENT,

    /** 本机**已安装**的全部字体族（`GraphicsEnvironment.availableFontFamilyNames`）。 */
    SYSTEM,

    /**
     * **宿主进程自带**的字体族（宿主 jar 内的字体条目，经 [HostFonts] 枚举）。
     *
     * ★★ 2026-09-29 新增（`recon/65` **路线 ②** 的最后一块）：
     * 用户诉求「**复用宿主字体用作默认字体**，所以也应该保留用户自导入和自选字体的入口」。
     *
     * ## 它为什么不能并入 [SYSTEM]
     *
     * 本来源的族名在设置里是**哨兵值**（`@host:MiSans` / `@host:Inter Light`），
     * 而 [SYSTEM] 的族名是**裸族名**（`Microsoft YaHei UI`）。两者**必须**分开：
     *
     * | 维度 | [SYSTEM] | **本来源** |
     * |---|---|---|
     * | 取值形态 | 裸族名 | `@host:` **哨兵**（[HostFonts.PREFIX]） |
     * | 是否写进系统族表 | 是（AWT 直接找得到） | **否**（刻意不写，见 `HostFonts` 文件头 §一/§二） |
     * | 解析路径 | `Font(family, …)` | **[HostFonts.resolveOrNull]** → `Font` |
     * | 未预热时 | 正常可用 | **不可用**（回退系统字体，不阻塞 EDT） |
     *
     * ⇒ 合并会让"这个族名该走哪条解析路径"失去唯一判据（本项目纪律 15：
     * 两个能力不对等的收敛点 = 报告与实际生效必然不一致）。
     *
     * ## ★ 为什么它同时解决了「减细」
     *
     * 细字重在 JDK 里**是独立族名**（`Inter Light` / `Inter Thin` / `Inter ExtraLight`），
     * 而非某个可调的属性（实测：`TextAttribute.WEIGHT < 1.0` 无效、`Font.style` 无"更细"位）。
     * 故**用户能在本来源里选到那个族名 = 得到细字重** ✓ —— 不必动 `NORMAL`/`BOLD` 那个设置项。
     *
     * ⚠️ 与 [FILE] 相同的性质：**族名列表依赖运行时状态**（[HostFonts] 预热完成才有内容）。
     * 未预热 ⇒ 本来源为空列表（不是错误，见 [fromHost] 的契约）。
     */
    HOST,

    /**
     * 从磁盘上的字体文件（包）里读出来的族名。
     *
     * ⚠️ **本来源的族名只在本次会话内有效** —— 文件被删/被移走后，
     * 靠族名 `new Font(family, ...)` 仍能工作（`registerInto` 已把它登记进
     * `GraphicsEnvironment`），但**下次启动**就找不到了。
     * 这是"用户自选文件"这一来源的固有性质，不是缺陷；界面上必须提示。
     */
    FILE,
}

/**
 * 一条字体候选项。
 *
 * @param family 族名。**永远取自字体内部 `name` 表**（见 [LoadedFont] 的 KDoc），
 *   绝不从文件名反推 —— `msyh.ttc` → `Microsoft YaHei UI` 这件事只能靠解析。
 * @param source 这条候选项从哪个来源来。界面按它分组显示（"近期" / "本机" / "字体包"）。
 * @param filePath 仅 [FontSource.FILE] 非空：字体包的文件路径，用于在界面上显示
 *   "这个族名是从哪个文件来的"，以及"文件还在不在"的判据。
 */
data class FontCandidate(
    val family: String,
    val source: FontSource,
    val filePath: Path? = null,
)

/**
 * 字体候选的**组装器** —— 三条来源的唯一汇合点。
 *
 * ## 线程
 *
 * [fromSystem] 与 [fromFiles] 是 **CPU/IO 密集**的（前者遍历几百个族名，
 * 后者要 `Font.createFonts` 解析文件）。调用方**必须放到后台线程**，
 * 不得在 EDT 上调用 —— 见 [FontLoader] 的线程声明（同一约束）。
 * [fromRecent] 是纯字符串操作，任意线程可调。
 *
 * ## 无共享可变状态
 *
 * 本类不持有字段（`object` 但无 state），故线程安全。
 * 所有输入都由调用方传入 —— 这避免了"单例里缓存一份字体表、用户装了新字体后不刷新"
 * 的经典缺陷。
 */
object FontCatalog {

    /**
     * 近期使用字体 → 候选列表。
     *
     * @param historyRaw 设置项 `main_lyric.font.recent` 的**原始字符串**。
     *   为什么接收字符串而不是 `List<String>`：该值的权威格式是 [FontHistory.serialize]
     *   产出的单行文本，让本方法直接接收原始值，调用方就不必先自己 parse 一遍
     *   （少一次"谁来 parse"的选择，也就少一处可能跟 `FontHistory` 漂移的地方）。
     *   传入的若是坏值，[FontHistory.parse] 的契约保证不抛异常（回退空列表）。
     */
    fun fromRecent(historyRaw: String): List<FontCandidate> =
        FontHistory.parse(historyRaw).map { FontCandidate(it, FontSource.RECENT) }

    /**
     * 本机**完全**字体列表 → 候选列表。
     *
     * ## 为什么用 `availableFontFamilyNames` 而不是 `getAllFonts()`
     *
     * `GraphicsEnvironment.getAllFonts()` 返回的是**每个物理字体*风格***一个 `Font`
     * 对象 —— 装了 `msyh.ttc` 会得到"微软雅黑 常规 / 微软雅黑 粗体 / 微软雅黑 斜体…"
     * 十几个条目。而设置项 `main_lyric.font.family` 的取值域是**族名**
     * （`Font(family, style, size)` 的 `family` 参数），字号与字重由渲染层另行派生。
     * 用 `getAllFonts()` 会让用户看到同一族的十几个重复项，选哪个都一样。
     *
     * `availableFontFamilyNames` 正是"族"这一级的去重结果，与取值域**逐字对应**。
     * 本项目已在两个地方用过它（`FontPicker.loadFamilies`、探针），此处保持同一 API。
     *
     * ## 为什么 `distinct()`
     *
     * `availableFontFamilyNames` 的**契约不保证无重复**。JDK 在 Windows 上读注册表
     * 组装字体表时，同一个族可能因"用户字体 + 系统字体"两条路径各出现一次
     * （本机实测：同一 Windows 版本在不同补丁级别下表现不同）。
     * 而下游是 `JComboBox` / `JList`：一个重复项在界面上就是**两条一模一样的条目**，
     * 用户会以为是两款不同字体。故在这里去重 —— 这是**唯一**对族名做去重的系统级位置。
     *
     * ⚠️ 去重**保持原顺序**（`distinct()` 而非 `distinct().sorted()`）：
     * 该数组本身已是本地化排序（中文按拼音、英文按字母），
     * 再按 `String` 字典序排一次会把中文族名全部打乱到英文后面，反而更难找。
     *
     * @param environment 目标图形环境。接收参数而非内部取单例 —— 理由同
     *   [FontLoader.registerInto]：`getLocalGraphicsEnvironment()` 在无头环境抛
     *   `HeadlessException`，把它作为参数让调用方在**一个明确边界**上处理降级。
     */
    fun fromSystem(environment: GraphicsEnvironment): List<FontCandidate> =
        environment.availableFontFamilyNames
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .map { FontCandidate(it, FontSource.SYSTEM) }
            .toList()

    /**
     * 宿主内置字体（**哨兵值**） → 候选列表。
     *
     * ## ★★ 为什么接收 `List<String>` 而不是内部读 [HostFonts]
     *
     * 与 [fromSystem] 接收 `environment` 是**同一条理由**：把"从哪里取"作为参数，
     * 调用方就在一个**明确边界**上处理降级，本类则保持"无共享可变状态"（见类 KDoc）。
     * 附带三个好处：
     * 1. **可测** —— 探针可传任意列表（含空、含重复、含非哨兵），
     *    不必依赖"这台机器上宿主 jar 在不在"（那会让断言与机器绑定）。
     * 2. **不触发扫描** —— [HostFonts.SENTINELS] 未预热时只含 `@host:MiSans`
     *    （见其 KDoc）；本函数**只是把传进来的列表包成候选**，不做任何 IO
     *    ⇒ 在 EDT 上调它也**不会**造成 1.4 s 冻结。
     * 3. 与 [fromRecent] 同形（后者接收原始字符串而非自己去读设置）。
     *
     * ## 契约
     *
     * - **不抛异常**。空输入 ⇒ 空候选（**不是错误**：宿主未预热/不可用时就是这样，
     *   调用方据"返回空"决定要不要提示 —— 与 [fromFiles] 的"空文件列表 → 空候选"同形）。
     * - **保留传入顺序**（不排序、不去重）—— 排序与去重是 [dedupe] 的职责，
     *   本函数与 [fromRecent] / [fromSystem] 保持同一分工。
     * - **族名原样保留**（含 `@host:` 前缀）—— 那是写进设置项的**权威值**；
     *   给人看的短名由 `HostFonts.displayName` 在**界面层**转换，不在这里做。
     *
     * @param sentinels 哨兵族名列表（通常来自 [HostFonts.SENTINELS]）。
     *   ⚠️ 本函数**不校验**它们是不是真的哨兵 —— 校验属 [HostFonts.isSentinel]，
     *   重复一份判据就会漂移（本项目已在"同族两处实现只改一处"上吃过多次亏）。
     */
    fun fromHost(sentinels: List<String>): List<FontCandidate> =
        sentinels.asSequence()
            .filter { it.isNotBlank() }
            .map { FontCandidate(it, FontSource.HOST) }
            .toList()

    /**
     * 字体**包文件** → 候选列表。一个 `.ttc` 会展开成多条（每个子字体一条）。
     *
     * ## 契约
     *
     * - **不抛异常**。文件不存在、无权限、不是字体、`.ttc` 里只有一个能解析 ——
     *   一律返回**已成功解析的部分**（可能是空列表）。理由与 [FontLoader.loadFrom]
     *   完全相同：用户选错文件是真实场景，不该让词岛连带崩掉。
     * - **空文件列表 → 空候选**（不报错、不抛）。调用方据"返回空"提示用户。
     * - **按族名去重**，同一个族名只保留**首次**出现的那条。
     *
     * ## 为什么按族名去重（而不是按文件名）
     *
     * 用户可能选了同一个族的两个文件（`MiSans-Regular.otf` 与 `MiSans-Bold.otf`），
     * 两个文件的**族名相同**（字重由 `name` 表的 subfamily 承载，不进族名）。
     * 若不去重，列表里会出现两条同族名条目 —— 与 [fromSystem] 的问题一模一样。
     *
     * ## ⚠️ 不确定处（如实声明）
     *
     * 本方法**假定** `.ttc` 的多个子字体族名互不相同。这个假定在本机实测成立
     * （`msyh.ttc` → 3 个不同族名），但 JDK 对某些"同族多语言名"的集合
     * 可能返回**相同族名**的多个 `Font`（例如只有 `name` 表语言不同）。
     * 那时去重会**保留第一个**，用户少看到几个候选 ——
     * 这是"少几个候选"而非"错误结果"，按 [FontLoader] 的同一原则接受。
     *
     * @param files 用户选中的字体文件（可能多个）。
     * @param registerInto 可选的登记目标。若给出，加载成功的字体会被
     *   `registerFont` 进该环境，使 **Swing 按族名也能取回**（见
     *   [FontLoader.registerInto] 的 KDoc：不登记的话 `new Font(family, ...)`
     *   会静默回退成逻辑字体，用户看到"选了却没生效"）。
     */
    fun fromFiles(
        files: List<Path>,
        registerInto: GraphicsEnvironment? = null,
    ): List<FontCandidate> {
        if (files.isEmpty()) return emptyList()

        val loader = FontLoader()
        val seen = LinkedHashSet<String>(files.size)
        val result = ArrayList<FontCandidate>(files.size)

        for (file in files) {
            // 复用 FontLoader 的逐文件隔离与 .ttc 展开逻辑 ——
            // **绝不在这里重写一份**：族名解析、坏文件隔离、`.ttc` 用 createFonts
            // 这三条契约都已在 FontLoader 落地并有探针覆盖，重写必然漂移。
            val loaded: List<LoadedFont> = loader.loadFileReporting(file).loaded
            if (loaded.isEmpty()) {
                System.err.println(
                    "[CDL] 字体包未解析出任何字体（已跳过）：$file"
                )
                continue
            }
            // 登记必须在"进候选列表"之前/同时完成：否则用户从下拉框选了它，
            // Swing 取不到族名，表现是"选择被静默忽略"。
            if (registerInto != null) loader.registerInto(registerInto, loaded)

            for (item in loaded) {
                val family = item.familyName
                if (family.isBlank()) continue
                if (!seen.add(family)) continue
                result += FontCandidate(family, FontSource.FILE, item.source)
            }
        }
        return result
    }

    /**
     * 把候选列表按**族名**去重，**保留首次出现**（即优先级高的来源）。
     *
     * ## 为什么需要它（而不是让调用方自己 distinct）
     *
     * 三个来源天然重叠：用户刚用文件加载过"MiSans"，它同时会出现在
     * `FILE`（刚加载）与 `SYSTEM`（若已安装）里，甚至也在 `RECENT` 里。
     * 界面上显示三遍同一个族名是**噪声**，而且用户会怀疑"是不是三个不同的字体"。
     *
     * ## ★★ [FontSource.HOST] 与 [FontSource.SYSTEM] **不会互相吞掉**（2026-09-29）
     *
     * 本机**同时**装了 `MiSans` 时，列表里会出现**两条**：
     * `@host:MiSans`（来自宿主 jar）与 `MiSans`（来自系统安装）。
     * 它们**必须**都保留 —— 这不是"重复项"，而是**两个不同的东西**：
     * 前者随宿主分发、后者随系统分发，字形文件可能不同版本，解析路径也完全不同
     * （见 [FontSource.HOST] 的对照表）。
     *
     * 去重键是**族名字符串**（[FontCandidate.family]），而哨兵带 `@host:` 前缀
     * ⇒ 与裸族名**天然不同键** ⇒ 本函数**不需要**为它加特例。
     * ⚠️ 反过来说：**若有人日后"顺手"把前缀剥掉再比较，这两条就会被错误合并**
     * —— 那时用户会失去"用宿主那份"的能力，且**没有任何报错**。
     * 该契约由 `FontProbe` §7.4 的对照断言钉住（"哨兵与同名系统族**都**在结果里"）。
     *
     * ## 优先级由调用方**传入列表的顺序**决定
     *
     * 本方法**不自作主张**排序：`[recent, file, system]` 与 `[recent, system, file]`
     * 的结果不同（前者保留 file 的路径信息，后者保留 system 的无路径信息）。
     * 让顺序成为**调用点的显式决策**，而不是藏在这里的一条隐式规则。
     *
     * 典型用法（设置窗口的字体选择器）：
     * ```
     * FontCatalog.dedupe(
     *     FontCatalog.fromRecent(raw) + FontCatalog.fromFiles(picked, ge) +
     *         FontCatalog.fromSystem(ge)
     * )
     * ```
     * 即**近期优先、其次用户刚选的包、最后本机全量** —— 这让"最近用过的"
     * 与"刚导入的"都在列表顶部，符合用户找字体时的直觉顺序。
     */
    fun dedupe(candidates: List<FontCandidate>): List<FontCandidate> {
        val seen = HashSet<String>(candidates.size)
        val result = ArrayList<FontCandidate>(candidates.size)
        for (candidate in candidates) {
            // 大小写不敏感去重：族名在 `name` 表里可能写作 `MiSans` 或 `MISANS`，
            // 而 `Font(family, ...)` 的族名查找在 Windows 上**不区分大小写**。
            // 用 Locale.ROOT 小写化做键，避免土耳其语环境把 `I` 变成 `ı`。
            val key = candidate.family.lowercase(Locale.ROOT)
            if (seen.add(key)) result += candidate
        }
        return result
    }

    /**
     * 该候选的**来源标签**（给界面用）。
     *
     * 放在这里而不是各个界面里各写一份：同一个候选在不同界面（设置窗口的列表、
     * 右键菜单的子菜单）里必须显示**同一个**来源标签，否则用户会以为是不同的分类。
     */
    fun labelOf(candidate: FontCandidate): String = when (candidate.source) {
        FontSource.RECENT -> "近期"
        FontSource.SYSTEM -> "本机"
        // ★ 2026-09-29：措辞与 `HostFonts.displayName` 的「宿主内置 X」前缀**保持一致** ——
        //   本函数 KDoc 的要求是"同一个候选在不同界面里必须显示同一个来源标签"。
        //   ⚠️ 刻意**不**复用 `HostFonts.displayName`：那个是"族名的显示名"（含具体族），
        //   这里是"**来源**的标签"（不含族名），两者粒度不同（见 `FontSourceDialog.sourceTag`
        //   用的是更短的 `[宿主]`，也属同一层但更短）。
        FontSource.HOST -> "宿主内置"
        FontSource.FILE -> candidate.filePath?.fileName?.toString()?.let { "字体包 · $it" } ?: "字体包"
    }
}
