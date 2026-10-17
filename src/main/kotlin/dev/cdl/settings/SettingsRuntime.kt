// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.cdl.settings

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

/**
 * 设置值的不可变快照 —— 每个 `key` 对应一个已收敛的值。
 *
 * ## 为什么用"扁平 `Map<String, Any>`"而不是为每个设置项各写一个字段的 data class
 *
 * 大纲 §9.7 / `recon/09` §1.2 描述的是 `Appearance` 这种"150+ 字段的强类型不可变 data class"。
 * 本实现**不这么做**，理由有三条，且都是被本版约束逼出来的：
 *
 * 1. **本版没有强类型模型可依附**。`Appearance` / `LyricTextStyle` 等渲染模型属**另一路**（渲染器）
 *    的实现范围。此刻若在本模块自定义一份 40 字段的 `IslandAppearance`，就会出现
 *    「设置模块一份模型 + 渲染模块一份模型」的双清单 —— 正是大纲 §9.1 要消灭的东西。
 * 2. **快照式的撤销栈需要"整份状态"可拷贝**。`Map` 的 `toMap()` 是浅拷贝，
 *    所有项都是不可变值（Boolean/Int/String），**等价于深拷贝且 O(项数)** —— 与
 *    `recon/09` §3.1 论证"快照式优于逆操作式"的结论一致，只是实现更省。
 * 3. **注册表是唯一事实来源**，字段名不该在两处硬编码。强类型 data class 必然在
 *    设置模块里再抄一遍 40 个键名，加一项就要改两个文件（漏改 = 静默不同步）。
 *
 * ## 不可变性
 * 实例创建后 [values] 不再变；[with] 返回新实例。订阅者可以安全持有它跨帧读。
 * **值一律是 [SettingDescriptor.expectedType] 声明的类型**（本类不负责校验，
 * 校验统一由 [SettingDescriptor.coerce] 在写入前完成）。
 */
class SettingsSnapshot internal constructor(
    private val values: Map<String, Any>,
) {
    /** 读取原始值；不存在时返回 `null`（调用方应改用带 descriptor 的访问器）。 */
    fun raw(key: String): Any? = values[key]

    /** 派生新快照（单键覆盖）。用于 `publish(next)` 的构造。 */
    fun with(key: String, value: Any): SettingsSnapshot =
        SettingsSnapshot(values + (key to value))

    /** 派生新快照（批量覆盖）。用于一次提交里改多项，或 undo/redo 回灌整份状态。 */
    fun withAll(overrides: Map<String, Any>): SettingsSnapshot =
        if (overrides.isEmpty()) this else SettingsSnapshot(values + overrides)

    /** 全量视图（只读语义；调用方不应修改返回值）。 */
    fun asMap(): Map<String, Any> = values

    /**
     * 本快照是否**几乎全是默认值**（2026-09-18 加，供重置检测用）。
     *
     * ## 判据：**与默认值相同的项 ≥ 80%**
     *
     * 不取 100% 是因为：即便配置被重置，也总有一两项"默认值恰好等于当前值"
     * 或者"该项的默认值与重置前的值碰巧相同"—— 要求 100% 会漏判。
     * 取 80% 则能在"整份回到默认"时稳定命中，而对"用户改了几项"不误报
     * （用户只改少数几项时，绝大多数仍是默认）。
     *
     * ## ⚠️ 它**已不再是**「疑似回退」判据的一部分（2026-09-22，BUG-054 / P0-2）
     *
     * 早先它与「变化键数 ≥ [RESET_SUSPECT_MIN_KEYS]」**联合**判定，
     * 但真机取证（`super-island.log:7284`）证明：用户自定义 >20% 的键时
     * 本方法恒为 `false` ⇒ 判据**整体失效**，而那正是最需要保护的场景。
     * 故判据已改为只看变化键数；本方法**当前没有生产调用点**，
     * 保留公开是因为它是"阈值结构"的**可断言面**
     * （`SettingsRevertGateProbe` §B 直接断言它）。
     */
    fun isMostlyDefaults(): Boolean {
        val total = SettingRegistry.all.size
        if (total == 0) return false
        val sameAsDefault = SettingRegistry.all.count { descriptor ->
            val current = values[descriptor.key] ?: return@count false
            // 用 `coerce` 后的语义比较（与 `differsFromRaw` 同一套），
            // 避免"Float 60.0 vs Int 60"这类表示差异被算成"不同"。
            runCatching { descriptor.coerce(current) == descriptor.default }.getOrDefault(false)
        }
        return sameAsDefault * 100 >= total * 80
    }

    // ── 强类型访问器 ────────────────────────────────────────────────────
    // 每个访问器都要求传入 descriptor：这样"取哪个 key"与"要哪种类型"绑在一起，
    // 不可能出现 `boolean("font.size")` 这类调用。

    /**
     * ★★★ 2026-10-11（**U12**）：**已下架（[SettingDescriptor.retired]）的项一律取【默认值】**，
     * ★ 而不是读盘值。
     *
     * ## 为什么必须这样做（★ 用户审计抓到的 P0，见 `_遗漏项检查-液态玻璃下架-2026-10-11.md` §1）
     *
     * ★ 用户原话：「**液态玻璃路线完全回退了，请检查有没有遗漏项**」。
     * ★★ 他给的取证（★ 读的是**真机配置**，不是默认值）：
     *   `cdl.json` 里 `material.backdrop.blur_radius = 16` ⇒
     *   ★ `BackdropCaptureParams.enabled = blurRadius > 0` ⇒ **采屏线程照跑** ✗
     *
     * ★★ **`retired` 此前只被【设置面】消费**（`spwPageDescriptors` + 生成器）
     *   ⇒ ★ **运行时完全不读它** ⇒ ★★ **玻璃渲染路径一条都没停，只是用户看不见那些滑杆了** ✗
     * ★★★ **更严重**：设置项已隐藏 ⇒ ★ **用户无法把值改回 0** ⇒ **自锁**（想关也关不掉）✓
     *
     * ## 修法（★ 一处覆盖全部 13 个 retired 键）
     *
     * ★ `coerce(null)` 的契约是「**永不抛异常，一切非法输入都退化为 [default]**」
     *   （见 `SettingDescriptor.coerce` 的 KDoc）⇒ ★ **`retired` 项传 `null` 即得默认值** ✓
     * ★ 本方法是**所有强类型访问器的唯一入口** ⇒ 覆盖 `boolean` / `int` / `text` 三种 ✓
     *
     * ⚠️ ★ 这是"**截断渲染路径**"的**执行点** —— ★ 与"隐藏设置项"（`spwPageDescriptors`
     *   的 `filterNot { it.retired }`）**合起来才是用户原话的完整两半** ✓
     * ★ 判据：★ 任何 `retired` 键的盘值**都不再影响渲染** ✓
     */
    private fun rawFor(descriptor: SettingDescriptor): Any? =
        if (descriptor.retired) null else values[descriptor.key]

    fun boolean(descriptor: SettingDescriptor): Boolean =
        descriptor.coerceBoolean(rawFor(descriptor))

    fun int(descriptor: SettingDescriptor): Int = descriptor.coerceInt(rawFor(descriptor))

    fun text(descriptor: SettingDescriptor): String = descriptor.coerceText(rawFor(descriptor))

    /** 便捷按键读取（内部用；越界/未知 key 回退该项默认值）。 */
    fun boolean(key: String): Boolean = SettingRegistry.descriptor(key).let { d ->
        if (d == null) false else boolean(d)
    }

    /**
     * 与另一份快照的差异键集合。撤销栈用于判断"这次提交是否真的改了东西"
     * （没改就不该入栈 —— 否则用户点一下滑杆松手会多出一条空记录）。
     */
    fun changedKeysAgainst(other: SettingsSnapshot): Set<String> {
        val keys = values.keys + other.values.keys
        return keys.filterTo(LinkedHashSet()) { values[it] != other.values[it] }
    }

    companion object {
        /**
         * 全部取默认值的快照。
         *
         * **这是"配置坏了也能进插件"的最后一道兜底**（项目铁律）：
         * 任何读取失败、类型不符、文件缺失，最终都退化到这份快照。
         */
        fun defaults(): SettingsSnapshot =
            SettingsSnapshot(SettingRegistry.all.associate { it.key to it.default })
    }
}

//
 // 配置持久化 —— **唯一官方持久化通道**（`ConfigManager` / `ConfigHelper`）。
 //
 // ## 位置纪律（项目铁律的落地）
 //
 // 数据放 `ConfigHelper.getConfigPath()` 指定的文件，**不自建
 // `%LOCALAPPDATA%\SPW Lyrics` 之类目录**（总表与大纲 §12 的项目纪律原文）。
 // 本类**不做任何目录扫描**，只读写这**一个**由宿主指定的文件。
 //
 // ## 为什么"读"不能直接用 `get(key, null)` 探针（**原结论已被推翻**）
 //
 // **原记**：「用 `get(key, null)` 读回一个类型未知的 `Any?`，再交给 `coerce` 收敛，
 // 可避开泛型擦除导致的 `ClassCastException`。」
 //
 // **实为**：该做法**恒返回 `null`**，会让**全部设置失效**。取证见 [readRaw] 的 KDoc
 // （宿主 `JsonConfigHelper.get` 的分派依据是默认值的运行时类型，**没有 `null` 分支**）。
 // 代价：用户在 SPW 设置页做的任何修改，插件一项都读不回来。
 //
 // **现做法**：按 `kind` 传**类型正确且等于描述符默认值**的探针（见 [readRaw]），
 // 键缺失时宿主直接返回该探针值，因此**无需区分"缺失"**，也**不再有类型擦除问题**
 // （探针类型与宿主写盘类型一致，强转必然成立）。
 // 读到之后仍交给 [SettingDescriptor.coerce] 收敛 —— 它负责的是
 // "值被手改成别的类型"这类**容错**，不再是"读不出来"的补救。
 //
/**
 * 读 COLOR 时用于判定"存的到底是 Int 还是 String"的哨兵值。
 *
 * 宿主 `get` 在类型不匹配时会回落到传入的默认值，因此"返回值 == 哨兵"等价于
 * "JSON 里存的不是 Int"。取 `Int.MIN_VALUE` 是因为它是合法的 ARGB 编码里
 * 最不可能被用户选中的值（alpha=0x80 的纯黑），且即便命中，降级方向也是
 * **回退默认色**而非**改成另一个颜色**（见 [SettingsStore.readRaw] 的 COLOR 分支）。
 */
private const val COLOR_INT_SENTINEL: Int = Int.MIN_VALUE

/**
 * 告警里最多列出几个键名（超出部分压成"…等 N 项"）。
 *
 * 取 10：正常情况只有 1–4 项（用户拖过几个滑杆），全列出来最有帮助；
 * 但"配置来自极旧版本"时可能几十项同时不符 —— 那种情况下把所有键名全打出来
 * 会又变成一行噪声。10 足够表达"是哪一类项出了问题"，又不会撑爆一行。
 */
private const val MAX_REPORTED_FALLBACK_KEYS: Int = 10

class SettingsStore(private val config: ConfigHelper) {

    companion object {
        /**
         * 上一次「被收敛过」的**键集合** —— 用来把重复告警压成"只在集合变化时打一行"。
         *
         * ## 为什么必须有它（真机取证：一条告警占了日志的 75%）
         *
         * [load] 被 **250 ms 文件轮询**反复调用（每秒 4 次）。而"值被收敛"这件事
         * 对**同一份配置**是**恒定的** —— 于是同一行告警会被无限重复。
         *
         * 真机日志实测：一条消息占了 **6007 / 7965 行**。
         * **真正需要看的异常被它淹没了** —— 这与 BUG-017 是同一类缺陷
         * （那次是"10 个滑杆项被误报"，已修判据；这次是"判据对了但仍按轮询重复"）。
         *
         * > 本项目纪律：**日志噪声是缺陷，不是无害的啰嗦。**
         *
         * ## ★ 为什么在**伴生对象**里，而不是实例字段（2026-09-18 修正 —— 这是我第一版的错）
         *
         * **原记**：放在实例字段上，理由是"[load] 只在设置线程调用，不需要 volatile"。
         *
         * **实为**：**有一条调用路径每次都 new 一个 `SettingsStore`** ——
         * `SettingsRuntime.onExternalConfigChanged` 里写的是
         * `SettingsStore(external).load()`（它需要一个"独立的读"来与 `saved` 比对）。
         * 那个新实例的 `lastFallbackKeys` **永远是初始值** →
         * 于是它**每次轮询都打一行**，去噪完全没生效。
         *
         * **真机证据**：修完部署后日志里那条消息**仍然是 3592 行**，
         * 且相邻两条**逐字相同**（说明"集合没变却重复打了"）。
         * 我去读日志才发现 —— **代码本身看着是对的**。
         *
         * > **纪律：把状态放在实例上之前，先确认"这个类的所有调用点都复用同一个实例"。**
         * > 一个 `Foo(...).bar()` 的写法（为了拿一份"干净的读"）会静默绕过实例状态。
         *
         * ## 为什么记"集合"而不是"计数"
         *
         * 计数相同但**成员不同**时（用户拖了 A 项、放开了 B 项）也必须打新的一行，
         * 否则真正新出现的那一项会被"计数没变"吞掉。集合比较能捕捉到这种替换。
         *
         * ## 线程纪律
         *
         * 两条调用路径（轮询线程 / 启动线程）可能并发 → `@Volatile`。
         * 竞争的后果只是"多打一行"或"少打一行"，不影响取值，故不必加锁。
         */
        @Volatile
        private var lastFallbackKeys: Set<String> = emptySet()
    }

    /**
     * 上次成功读/写的快照，用于 250 ms 文件轮询的差异检测
     * （大纲 §9.3「变更监听 + 250 ms 文件轮询双保险」要求）。
     */
    @Volatile
    var lastKnown: SettingsSnapshot = SettingsSnapshot.defaults()
        private set

    // ⚠️ 去噪状态**已从实例字段改为伴生字段**（2026-09-18 修正）—— 见下面伴生对象里的说明。

    /**
     * 从磁盘读全量设置。
     *
     * **契约：永不抛异常**，一切异常都退化为"该项用默认值"。
     * 单个键坏掉不影响其余键 —— 逐键 try 而非整体 try，这是有意的：
     * 用户手改坏了一个颜色值，不该把整份设置清零。
     */
    fun load(): SettingsSnapshot {
        val values = LinkedHashMap<String, Any>(SettingRegistry.all.size)
        // 收集**键**而不是只计数 —— 告警必须能指名道姓，否则用户拿着
        // 「4 项值类型不符或超范围」无从下手（真机实测：这条消息出现了 6007 次，
        // 而它从未说过是哪 4 项）。见 [lastFallbackKeys] 与 [reportFallbacks]。
        val fallbackKeys = LinkedHashSet<String>()
        for (descriptor in SettingRegistry.all) {
            val raw = readRaw(descriptor)
            if (raw == null) {
                // 正常路径下不会走到这里（readRaw 已用描述符默认值作探针，
                // 键缺失时宿主会返回该探针值）。仍保留此分支：readRaw 内部
                // runCatching 吞掉异常时返回 null，此时回退默认值是唯一安全动作。
                values[descriptor.key] = descriptor.default
                continue
            }
            val coerced = runCatching { descriptor.coerce(raw) }.getOrElse { descriptor.default }
            // ⚠️ 必须用**语义比较**而不是 `coerced != raw`：
            // 宿主滑杆写盘是 `Float 60.0`，`coerce` 收敛成 `Int 60`，
            // 而 Kotlin 的 `Int != Float` 恒为 `true` —— 用 `!=` 会把**全部滑杆项**
            // 误报成"类型不符"（实测 10 项 × 每 250 ms 一次 = 5071 行日志噪声，
            // 把真正的异常淹没了）。详见 `SettingDescriptor.differsFromRaw` 的 KDoc。
            if (descriptor.differsFromRaw(coerced, raw)) {
                // 值确实被修正过（类型不符或超范围）→ 记下键，便于排查"配置来自旧版本"。
                fallbackKeys.add(descriptor.key)
            }
            values[descriptor.key] = coerced
        }
        reportFallbacks(fallbackKeys)
        val snapshot = SettingsSnapshot(values)
        lastKnown = snapshot
        return snapshot
    }

    /**
     * 报告"被收敛过"的项。**只在集合变化时打一行，且指名道姓。**
     *
     * ## 两条约束都来自真机取证
     *
     * 真机日志（7965 行）里有 **6007 行**是这条告警，内容是
     * `1 项` / `2 项` / `4 项` 三种之一 —— 用户拖过几个滑杆，就有几项。
     * 而复算用户真实配置（`cdl.json`）确认那 4 项是：
     *
     * ```
     * background.opacity   = 99.60135 → 99
     * controls.gapPx       = 0.347027 → 0
     * main_lyric.font.size = 37.33189 → 37
     * shape.corner_radius  = 16.614864 → 16
     * ```
     *
     * 即：**SPW 的滑杆写盘是 Float，整数项被截断**（BUG-005 的现象）。
     * 读路径本身是**对的**（收敛后取整，设置照常生效），
     * 所以这里**不是修取值，是修可观测性**：
     *
     * ① **指名道姓** —— 原消息只说"4 项"，用户无从下手；
     * ② **只在集合变化时打一行** —— 原实现按轮询重复（每秒 4 次），
     *    把真正需要看的异常淹没了。这与 BUG-017 同族：
     *    那次修的是**判据错**（10 个滑杆项被误报），这次修的是**重复打**。
     *
     * > 本项目纪律：**日志噪声是缺陷，不是无害的啰嗦。**
     *
     * @param keys 本次 load 中被收敛过的键（顺序 = 注册表顺序，稳定可读）
     */
    private fun reportFallbacks(keys: Set<String>) {
        if (keys == lastFallbackKeys) return
        lastFallbackKeys = keys
        if (keys.isEmpty()) return
        val shown = keys.take(MAX_REPORTED_FALLBACK_KEYS)
        val suffix = if (keys.size > shown.size) " …等 ${keys.size} 项" else ""
        System.err.println(
            "[CDL] 设置读取：${keys.size} 项值类型不符或超范围，已收敛到合法值" +
                " → ${shown.joinToString(", ")}$suffix" +
                "（常见原因：SPW 滑杆写盘为 Float，整数项被截断；读路径已收敛，" +
                "本行只在**集合变化**时打印一次，不按轮询重复）"
        )
    }

    /**
     * 写入全部设置并落盘。
     *
     * ## 为什么先 `reload()` 再写（大纲 §9.3 的既有经验第 2 条）
     *
     * `ConfigHelper` 是**整文件**语义：`save()` 会把当前内存中的配置整份写回。
     * 若我们在两个写盘之间没有 reload，而宿主或另一个插件实例（或用户手改）
     * 在此期间改了**别的键**，我们就会用陈旧的内存覆盖掉那次修改。
     * 故写入前先 `reload()` 把磁盘上的最新状态拉进内存，再叠加我们的键。
     *
     * ## 为什么 `save()` 失败不抛异常
     *
     * 磁盘满 / 权限不足 / 文件被占用都可能让 `save()` 返回 `false`。
     * 此时**内存里的设置仍然是新值**（用户已经看到效果了），
     * 只是重启后会回退。抛异常会让设置窗口崩在用户手上，代价远大于"这次没存上"。
     * 故返回布尔值让调用方决定要不要 toast 提示。
     *
     * @return `true` = 已成功落盘
     */
    fun save(snapshot: SettingsSnapshot): Boolean {
        // ① 拉取磁盘最新状态，避免覆盖别的键（见 KDoc）。
        runCatching { config.reload() }
            .onFailure { System.err.println("[CDL] 写盘前 reload 失败（继续写入）：$it") }

        // ② 逐键写入。同样逐键 try —— 一个键的序列化问题不该中断整次保存。
        var failed = 0
        for (descriptor in SettingRegistry.all) {
            // `storageValue` 负责"内部表示 → 落盘表示"的转换（目前只有 COLOR：
            // 打包 ARGB `Int` → `"#RRGGBB"` 文本，因为 SPW 设置页的 edittext 会直接显示它）。
            val value = descriptor.storageValue(snapshot.raw(descriptor.key) ?: descriptor.default)
            runCatching { config.set(descriptor.key, value) }
                .onFailure { failed++ }
        }
        if (failed > 0) {
            System.err.println("[CDL] 设置写入：$failed 项未能写入内存配置")
        }

        // ③ 落盘。
        val saved = runCatching { config.save() }.getOrElse { false }
        if (saved) lastKnown = snapshot
        else System.err.println("[CDL] 设置保存失败：ConfigHelper.save() 返回 false")
        // ★★ 2026-10-18（P0）：**单键写入失败也算整次保存失败**。
        //   原来只看 `config.save()`：某些键没写进内存配置、但整份落盘"成功"⇒
        //   返回 true ⇒ 调用方把这次当作已保存，那几个键的值静默丢失。
        return saved && failed == 0
    }

    /**
     * 从磁盘重载设置（供 250 ms 轮询或外部变更监听使用）。
     *
     * 与 [load] 的区别：本方法会**先比较**上次已知快照，内容一致则返回 `null`
     * （表示"无变化"），避免调用方对每次都做无意义的重绘。
     */
    fun reloadIfChanged(): SettingsSnapshot? {
        // ⚠️ **必须在 `load()` 之前取旧快照。**
        //
        // `load()` 结尾会执行 `lastKnown = snapshot`（它同时承担"初始化 lastKnown"的职责）。
        // 若在 `load()` **之后**才读 `lastKnown`，拿到的就是刚读进来的新快照 ——
        // `fresh.changedKeysAgainst(lastKnown)` 变成"**拿自己比自己**"，**恒为空集**，
        // 本方法于是**永远返回 `null`**。
        //
        // 后果（真实缺陷，曾长期存在）：250 ms 轮询**彻底空转**，而它正是
        // "宿主 watcher 注册不上"时的**唯一**兜底通道 → 用户在 SPW 设置页改的值
        // **永远回灌不到词岛**，表现为"设置项全是摆设"。
        val previous = lastKnown

        // ── ★ BUG-054（P0-1）：`reload()` 失败 ⇒ 本次读取**内容不可信** ──────────
        //
        // 宿主 `hl.java:192-205` 的取证：`reload()` 解析失败时会把内存
        // `configJson` **清成 `JsonObject(emptyMap())`** 并 `return false`。
        // 也就是说「`reload()` 返回 false」**等价于**「宿主内存已是 `{}`」。
        //
        // 此时 `load()` 读到的只会是**全默认快照** —— 它**没有任何信息量**，
        // 却被下游当成一次有效的读取。故失败一律当作"读不到"（返回 `null`）。
        val ok = runCatching { config.reload() }.getOrElse { false }
        if (!ok) return null
        val fresh = load()
        return if (fresh.changedKeysAgainst(previous).isEmpty()) null else fresh
    }

    /**
     * **强制复读**：先让宿主从磁盘重新加载，再**整份读回**（不做差异比较）。
     *
     * ## 与 [reloadIfChanged] 的分工
     *
     * | 方法 | 回答的问题 | 返回值语义 |
     * | --- | --- | --- |
     * | [reloadIfChanged] | 「**变了没有**」 | 没变 → `null` |
     * | 本方法 | 「**现在到底是什么**」 | 读到的整份快照；`reload()` 失败 / 通道未就绪 → `null` |
     *
     * ## 为什么必须有它（2026-09-19，为"疑似整份回退"的强制复核加）
     *
     * 判定"这份读回来的配置是不是瞬时异常"时，需要一次**独立的、不做差异比较的**
     * 复读 —— 而 [reloadIfChanged] 正好做不了这件事：它内部会把 `lastKnown`
     * 更新为刚读到的可疑快照，于是**紧接着再调一次必然返回 `null`**
     * （"拿可疑快照比自己"），复核无从谈起。
     *
     * ## 判据与 [reloadIfChanged] 逐字一致（**不许各写一套**）
     *
     * `reload()` 返回 `false` → **视为"读不到"**，返回 `null`。
     *
     * ## ★ 为什么"`reload()` 失败"必须当成"内容不可信"（BUG-054 / P0-1）
     *
     * 宿主 `hl.java:192-205` 的取证：`reload()` **解析失败时会把内存 `configJson`
     * 清成 `JsonObject(emptyMap())` 并 `return false`**。⇒
     * 「`reload()` 返回 false」**等价于**「宿主内存已被清空」。
     *
     * 此时 `load()` 只能给出**全默认快照** —— 它**没有任何信息量**，
     * 却被 [holdIfSuspectRevert] 当成"**第二次独立读取**"，并据此判
     * "两次一致 ⇒ 真实变更 ⇒ 采纳"。那正是 BUG-054 的**数据丢失路径**：
     * 一次读取异常被升级成"整份回默认"，且被 [SettingsRuntime.apply] 写回磁盘。
     *
     * 改为 `null` 之后，复读**重新变回真正的独立第二次读**：
     * 要么拿到真值，要么拿不到（走扣住分支，继续用上一次已知良好快照）。
     *
     * ⚠️ 早先的判据是"`reload()` 失败 **且** 连 `getConfigPath()` 都取不到 → `null`"，
     * 并自陈"`reload()` 返回 false 时也要读一次，因为 `get` 走的是宿主内存" ——
     * **那个前提是错的**：内存此时已经是空的。
     */
    fun reloadAndLoad(): SettingsSnapshot? {
        val ok = runCatching { config.reload() }.getOrElse { false }
        if (!ok) return null
        return load()
    }

    /** 配置文件绝对路径（诊断与"打开数据目录"用）。取不到时返回 `null`。 */
    fun configPath(): java.nio.file.Path? =
        runCatching { config.getConfigPath() }.getOrNull()

    /**
     * 数据目录 —— `ConfigHelper.getConfigPath().parent`。
     *
     * **项目纪律**：自有数据（随带字体等）只能落在这个目录下，
     * **不许自建 `%LOCALAPPDATA%\SPW Lyrics`**（大纲 §12 项目纪律原文）。
     * 这里返回 `parent` 而不是自造路径，正是为了让这条纪律**只有一个来源**。
     */
    fun dataDirectory(): java.nio.file.Path? = configPath()?.parent

    /**
     * 读单个键的值。
     *
     * ## ⚠️ 绝不能传 `null` 作默认值（这是一个已确证的 P0）
     *
     * 宿主 `JsonConfigHelper.get` 的**分派依据是「默认值的运行时类型」**。
     * 反编译取证（`hl.java:109-138`）：它对
     * `String / Double / Boolean / Integer / Long / Float` 六个类型做 `typeSwitch`，
     * **没有 `null` 分支** —— 默认值为 `null` 时**无 case 命中**，直接落到
     * `return defaultValue`，于是**恒返回 `null`**。
     *
     * 后果：`load()` 里 `raw == null` 恒成立 ⇒ **全部设置回落到 `descriptor.default`** ⇒
     * **用户在 SPW 设置页做的任何修改，插件一项都读不回来**。
     * （本类早先的 KDoc 曾把 `get(key, null)` 当作"类型安全的探针"，
     * 并自陈"未在真机验证" —— 本次即为验证结果：**该前提是错的**。）
     *
     * ## 正确做法：按 kind 传**类型正确**且**等于描述符默认值**的探针
     *
     * 默认值取 [SettingDescriptor.default] 本身，于是：
     * - 键**缺失** → 宿主返回该探针值 = 我们想要的默认值（**无需再区分"缺失"**）；
     * - 键**存在** → 宿主按探针类型取值返回。
     *
     * ## ⚠️ SEEKBAR 必须用 `Float` 探针
     *
     * 宿主滑杆**写盘的是 `Float`**（反编译取证：`fX.java:982` `set(key, Float)`），
     * JSON 里是 `60.0`。若用 `Int` 探针会走 `getIntOrNull`，
     * 对 `60.0` **解析失败** → 回落到探针默认值 → **永远拿不到用户拖出来的值**。
     *
     * @see SettingDescriptor.coerce 读到之后的类型收敛（对字符串宽容，兼容手改配置）
     */
    private fun readRaw(descriptor: SettingDescriptor): Any? = runCatching {
        when (descriptor.kind) {
            SettingKind.SWITCH ->
                config.get(descriptor.key, descriptor.defaultValueBoolean())

            SettingKind.SEEKBAR ->
                config.get(descriptor.key, descriptor.defaultValueInt().toFloat())

            // COLOR：**同一个键存在两种合法 JSON 类型**，必须两种都认。
            //
            // - 自绘取色器写 **打包 ARGB `Int`**；
            // - SPW 设置页的 `edittext` 路径由**宿主**写用户输入的 **`String`**（如 `"#FF0000"`）
            //   —— 这一条我们控制不了，只要用 edittext 就一定有 String 进 JSON。
            //
            // ## 为什么不用"Int 探针 + 读到默认值时补字符串探针"（更早的写法）
            //
            // 那个写法有一个**会读出错误颜色**的洞：它用 `packed == default` 当"可能是字符串"的判据，
            // 于是"值恰好等于默认值且是 Int"时也会去跑 `parseHexOrNull(十进制文本)`。
            // 打包 ARGB 只在 **alpha ≥ 0x80 时**才是负数（十进制文本含 `-`，会被解析器挡掉）；
            // **alpha < 0x80 时是正数**，其十进制文本若恰好 3 或 6 位，就会被当成 hex 解析成
            // **另一个颜色** —— 静默出错色，比回退默认值更糟。
            //
            // ## 现写法：先用**存在性**判据，再用**哨兵**判类型
            //
            // 1. 存在性：我们不存空字符串颜色，故 `get(key, "")` 返回空串 ⟺ 键缺失；
            // 2. 类型：用 `Int.MIN_VALUE` 作哨兵探 Int —— JSON 里是字符串时 `getIntOrNull`
            //    必然失败并回落到哨兵，于是"不等于哨兵"就等价于"存的确实是 Int"。
            //
            // 残余风险（**降级方向是安全的**）：若存的 Int 恰好等于 `Int.MIN_VALUE`
            // （alpha=0x80 的纯黑），会被误判为字符串，最终**回退默认色** ——
            // 是"颜色不对"，不是"颜色被改错成别的色"。
            SettingKind.COLOR -> {
                val text = config.get(descriptor.key, "")
                if (text.isEmpty()) {
                    descriptor.defaultValueInt()
                } else {
                    val native = config.get(descriptor.key, COLOR_INT_SENTINEL)
                    if (native != COLOR_INT_SENTINEL) {
                        native
                    } else {
                        parseHexOrNull(text) ?: descriptor.defaultValueInt()
                    }
                }
            }

            SettingKind.LIST, SettingKind.EDITTEXT, SettingKind.FONT ->
                config.get(descriptor.key, descriptor.defaultValueText())
        }
    }.getOrNull()
}

/**
 * 设置系统的组合根 —— 装配注册表、存储、变更总线，并**保持三者的一致性**。
 *
 * ## 分层（`recon/09` §4.1 的落点表）
 *
 * ```
 * settings/
 *   SettingRegistry   元数据单一事实来源 —— 不知道值、不知道历史
 *   SettingsSnapshot  当前值（不可变）
 *   SettingsStore     持久化 —— 只认"已保存的"，不知道 live 预览态
 *   SettingsRuntime   ★ 编排者：live / saved / version / 订阅者
 *   EditHistory       历史 —— 纯数据结构，不反向依赖 Runtime
 * ```
 *
 * ## `live` 与 `saved` 的分离（大纲 §9.7 + `recon/09` §1.2）
 *
 * - [live]：**当前生效态**。拖动中不断被 [publish] 替换 → 渲染层当帧即见效果。
 * - [saved]：**已落盘态**。只有 [apply] 才更新。
 * - 两者分离使得"预览物理上不可能污染磁盘"（`recon/09` §2.2.1 性质 1），
 *   同时拖动中**不产生任何磁盘 I/O**（`recon/09` §1.4 明确排除 250 ms 轮询参与预览）。
 *
 * ## 为什么 `version` 是一个单调递增的长整数而不是布尔 `dirty`
 *
 * 渲染层需要知道"派生资源（字体度量/描边路径/辉光位图）要不要重算"。
 * 用布尔标记的话，"改了两次颜色但字体没变"也会触发重建；
 * 用版本号 + 缓存上次重建时的版本，就能让渲染层自己决定粒度。
 * **这也是为什么 [publish] 必须保证 `version` 严格递增**（`recon/09` §0 第 9 条
 * 要求"撤销本身也不能让 version 抖动两帧"）。
 */
class SettingsRuntime internal constructor(
    private val store: SettingsStore,
    private val configManager: com.xuncorp.spw.workshop.api.config.ConfigManager,
) : AutoCloseable {

    /** 当前生效态。**渲染层每帧只读一次**到局部变量，避免半更新帧（大纲 §10）。 */
    @Volatile
    var live: SettingsSnapshot = SettingsSnapshot.defaults()
        private set

    /** 已落盘态。`live != saved` 即"有未保存的修改"。 */
    @Volatile
    var saved: SettingsSnapshot = SettingsSnapshot.defaults()
        private set

    /**
     * 变更版本号。**每次 [publish] 递增**（含拖动中的每一次）。
     * 渲染层用它判断"派生资源要不要重算"，见类 KDoc。
     */
    @Volatile
    var version: Long = 0L
        private set

    /** 订阅者列表（渲染层/菜单在此登记）。见 [subscribe]。 */
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<(SettingsSnapshot, SettingChange) -> Unit>()

    /**
     * 「疑似读取异常导致的整份回默认」的**待确认快照**（2026-09-18 二次修订加）。
     *
     * ## 为什么需要它 —— 真机取证
     *
     * 用户报「**外观有一次短暂变回默认**」。真机日志（`super-island.log`）里
     * 「⚠️ 配置疑似被重置」出现 **8 次**，每次都是 **15~20 项同时变为默认值**，
     * 且键名集合**每次都是同一批**（`window.position` / `layout.max_width` /
     * `shape.type` / `shape.corner_radius` / `background.opacity` /
     * `main_lyric.font.size` / `island.experimental_multi_line` …）——
     * **正是用户自定义过的那一批**。这就是"外观整份回默认"的签名：
     *
     * > 只要**所有**键都读成默认值，那么"与 `saved` 不同"的键就恰好是
     * > "用户改过的那批"。所以**这个键名集合本身**就是"整份读取失败"的证据。
     *
     * 触发时机也清楚了：8 次全部发生在**用户拖滑杆期间**（相邻日志是
     * 「外部设置变更已回灌（1 项）：spectrum.height_pct / spectrum.opacity_pct」），
     * 即**宿主正在写盘/重载配置**的窗口内，某一次读取拿到了全默认。
     *
     * ## 为什么必须"扣住"而不是"只告警"
     *
     * 上一版只告警不拦截（当时的理由是"分不清宿主重置与用户主动恢复默认"）。
     * 但真机证明**代价极不对称**：
     *   · 误采纳一次读坏 → 用户看到**整份外观闪成默认**（就是本条缺陷）；
     *   · 误扣住一次真实变更 → **强制复读一次就确认并采纳**，用户不可感。
     * 故现在扣住，并以"**两次独立读取是否一致**"作为真实变更的判据。
     *
     * ## ⚠️ 2026-09-19 修订：确认机制从"跨轮询"改为"**同一次调用内强制复读**"
     *
     * 原设计（"连续两次读到同一份"）在真机里**从未生效**：日志里 25 次
     * 「已扣住」、**0 次**「已确认恢复为默认」。原因是第二次读取被
     * [SettingsStore.reloadIfChanged] 的差异检测吃掉（`load()` 已把 `lastKnown`
     * 更新成可疑快照 → "拿自己比自己" → 恒 `null` → 提前 return）——
     * **确认路径不可达**。而不可达的确认路径 + 会残留的待确认标记 =
     * 那个陈旧标记会在某个**无关的**后续读取里被误判成"连续两次一致"而采纳，
     * 这正是 H1（整份回默认被**永久**采纳、且磁盘未变故无自愈）的成因。
     *
     * 现设计（见 [holdIfSuspectRevert]）：判定可疑后**在同一次调用内**
     * 强制复读（[SettingsStore.reloadAndLoad]，不做差异比较），
     * 复读不一致即以读回为准、一致才采纳。
     *
     * ## 本字段现在的角色（**已不再参与"是否采纳"的判定**）
     *
     * 只在**复读拿不到内容**（宿主配置通道未就绪）时被置上，含义是
     * 「有一份可疑快照，我们扣住了、但还没能复核」。它**只用于诊断与状态收尾**：
     *   · [clearSuspectRevertPending] 在任何"读回来的不是可疑回退"的读取里清掉它；
     *   · [resolveHeldSuspect] 在一次"没有新变更"的轮询里用强制复读了结它。
     * **是否采纳由复读结果当场决定**，与本字段无关 —— 故陈旧标记**不可能**
     * 再造成"误采纳"（这是与 2026-09-18 版最大的语义差别）。
     *
     * | 情形 | 第一次读 | 强制复读 | 判定 |
     * | --- | --- | --- | --- |
     * | 读取异常 | 全默认 | **真值**（异常已过去） | 不一致 → 以读回为准 |
     * | 用户真的恢复默认 | 全默认 | **同一份全默认** | 一致 → 采纳 |
     * | 通道未就绪 | 全默认 | 拿不到内容 | 扣住（本字段置上），下次轮询复核 |
     *
     * `null` = 当前没有待确认的快照。
     *
     * `@Volatile`：写者是轮询线程与宿主监听线程，读者也可能是它们 —— 防御性加上。
     */
    @Volatile
    private var suspectRevertPending: SettingsSnapshot? = null

    /**
     * 宿主侧配置变更监听（外部改了文件时把新值推给我们）。
     *
     * ⚠️ 必须持有引用以便 `close()` 时反注册 —— 否则插件卸载后宿主仍持有
     * 指向已销毁运行时的 lambda，是真实的内存泄漏（`recon/09` §4.3 的回路风险）。
     */
    private val hostListener: java.util.function.Consumer<ConfigHelper> =
        java.util.function.Consumer { external ->
            onExternalConfigChanged(external)
        }

    /** 关闭标记。轮询线程与监听回调都靠它判断"运行时已销毁"。 */
    @Volatile
    private var closed = false

    /**
     * 上次打印「外部设置变更已回灌」的时刻（毫秒）。
     *
     * 只为**节流日志**存在，不参与任何业务逻辑 —— 故用 `@Volatile` 而非锁：
     * 最坏情况是两条日志挤在一起，代价远小于在回灌路径上加同步。
     */
    @Volatile
    private var lastExternalLogMs = 0L

    /**
     * 设置同步的**轮询兜底**线程（守护线程，250 ms 周期）。
     *
     * ## 为什么必须有它（不是冗余，是必需）
     *
     * 原实现 `HostSettings.kt:36-37` 的注释原文：
     *
     * > SPW can fail to register its watcher when a plugin's data directory does not yet exist.
     * > Re-read the actual file independently; notifications remain a fast path only.
     *
     * → **插件数据目录尚不存在时，宿主可能注册不上 watcher**。这不是理论风险 ——
     * 原实现为此专门写了轮询，并明说"通知只是快路径"。
     *
     * 我们此前**只依赖监听器**（且用的是无参重载，见 `attachHostListener`），
     * 两道防线同时失效 → 设置同步完全靠"宿主 watcher 恰好正常工作"。
     * 更糟的是 `SettingsStore.reloadIfChanged()` **写了却从未被调用** ——
     * 它是死代码（见 `BUG-与修复记录.md` BUG-006）。
     *
     * ## 为什么 `isDaemon = true` 是硬要求
     *
     * 非守护线程会**阻止 JVM 退出**。宿主关闭时若这个线程还活着，
     * 进程会挂住不退 —— 那是比"设置不刷新"严重得多的问题。
     *
     * ## 为什么单线程
     *
     * 轮询体只做"读文件 + 比较 + 可能通知"，没有并发需求；
     * 单线程还顺带保证了**通知不会重入**（同一时刻只有一个 poll 在跑）。
     */
    private val poller: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "CDL settings sync").apply { isDaemon = true }
        }

    init {
        val loaded = store.load()
        live = loaded
        saved = loaded
        version = 1L
    }

    /** 装配宿主变更监听。由 [SettingsRuntime.create] 调用（构造期注册会让 `this` 逸出）。 */
    private fun attachHostListener() {
        // ⚠️ 必须用**带文件名**的重载。`ConfigManager` 声明了两个（见 _javap_api_dump.txt:138-139）：
        //   addConfigChangeListener(Consumer)          → 无参版本
        //   addConfigChangeListener(String, Consumer)  → 按文件名监听   ← 正确
        // 用无参版本时，我们在 SPW 设置页改的值**不会触发通知**，只能靠 250 ms 轮询兜底
        // （轮询见 startPolling 的 KDoc —— 它是兜底，不该是唯一通道）。
        // 原实现两个配置实例都用带名版本（HostSettings.kt:35 / LyricsSettings.kt:35）。
        runCatching { configManager.addConfigChangeListener(CONFIG_FILE, hostListener) }
            .onFailure { System.err.println("[CDL] 注册配置变更监听失败（$CONFIG_FILE）：$it") }
    }

    /**
     * 启动轮询兜底。由 [SettingsRuntime.create] 调用（与 [attachHostListener] 同理，
     * 构造期启动会让 `this` 逸出到线程里）。
     *
     * 见 [poller] 的 KDoc 说明为什么必须有它。
     */
    private fun startPolling() {
        runCatching {
            poller.scheduleWithFixedDelay(
                { runCatching { pollOnce() }.onFailure {
                    // 轮询失败只记日志：它本来就是兜底路径，不该因为它出问题而影响插件。
                    System.err.println("[CDL] 设置轮询失败：$it")
                } },
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }.onFailure { System.err.println("[CDL] 启动设置轮询失败（将只依赖变更监听）：$it") }
    }

    /**
     * 一次轮询：读盘 → 与已落盘态比对 → 有变化则回灌并通知。
     *
     * ## 自写回路怎么避免
     *
     * `SettingsStore.reloadIfChanged()` 内部用 `lastKnown` 做差异检测，
     * 而 `SettingsStore.save()` 会同步更新 `lastKnown` —— 所以我们自己刚写的值
     * 不会让它报"有变化"。**这是第一道防线。**
     *
     * 第二道：本方法再与 [saved] 比对一次。两者都通过才通知订阅者。
     * 双保险的理由：`lastKnown` 的更新时机在 `SettingsStore` 内，
     * 而 [saved] 是本类的"已确认落盘态"—— 用两个独立来源判定，
     * 比只信一个更不容易在将来改坏。
     *
     * ## 为什么走 `restore` 而不是直接赋 [live]
     *
     * `restore` 会**递增 [version]** —— 渲染层靠 version 判断"派生资源要不要重算"。
     * 直接赋值会让用户看到"设置变了但画面没变"（这是 `restore` 的 KDoc 里
     * 用 `★` 标注过的最容易写错的地方）。
     */
    private fun pollOnce() {
        if (closed) return
        val fresh = store.reloadIfChanged()
        if (fresh == null) {
            // 本轮没有"新变更"（`null` 同时覆盖"内容没变"与"通道未就绪"两种情况）。
            //
            // ⚠️ 但这正是**了结上一轮扣住的疑点**的时机（任务 C）：`reloadIfChanged`
            //    的 `null` 有歧义，故用一次**强制复读**明确"磁盘上到底是不是它"。
            resolveHeldSuspect()
            return
        }
        if (fresh.changedKeysAgainst(saved).isEmpty()) {
            // 读回来的就是已落盘态 → 上一轮扣住的疑点已被否证，必须了结它（任务 C）。
            clearSuspectRevertPending("文件轮询：读回与已落盘态一致")
            return
        }
        // 与 [onExternalConfigChanged] 走**同一份判据**（2026-09-18 二次修订 +
        // 2026-09-19 任务 D）：轮询与宿主推送是同一个"外部变更"的两条通道，
        // 判据若只加在一条上，缺陷会从另一条通道照样进来。真机日志显示 8 次
        // 疑似重置里两种通道都有份，故必须两处都挡。
        val adopt = holdIfSuspectRevert(saved, fresh, "文件轮询") { store.reloadAndLoad() } ?: return
        // 外部（SPW 设置页 / 用户手改文件）改了配置 → 按外部变更回灌。
        restore(adopt, SettingChange.Kind.EXTERNAL)
    }

    /**
     * 一次"没有新变更"的轮询里，**了结**上一轮扣住的疑似回退（任务 C）。
     *
     * ## 为什么不能直接把标记清掉
     *
     * `reloadIfChanged()` 返回 `null` 有两种含义：**① 内容与上次一致**、
     * **② 通道未就绪**。只有 ① 能证明"磁盘上不是那份可疑快照"；
     * ② 什么都没证明（那时连内容都读不到）。故这里**强制复读一次**来消歧：
     *
     * | 复读结果 | 处置 |
     * | --- | --- |
     * | 复读不到（通道仍未就绪） | **保持扣住**，下一次轮询再判 |
     * | 复读 == 扣住的那份 | 磁盘上确实就是"全默认" → 用户主动恢复默认 → **采纳** |
     * | 复读 != 扣住的那份 | 那次是瞬时异常 → **丢弃标记**，继续用已落盘态 |
     *
     * 第 3 行正是任务 C 要修的那个洞：原实现里这条路径**不进**
     * [holdIfSuspectRevert]，标记会一直残留。
     */
    private fun resolveHeldSuspect() {
        val held = suspectRevertPending ?: return
        val now = store.reloadAndLoad() ?: return
        if (now.changedKeysAgainst(held).isEmpty()) {
            suspectRevertPending = null
            System.err.println(
                "[CDL] 配置已确认恢复为默认（轮询复核一致）：" +
                    "${held.changedKeysAgainst(saved).size} 项变化。已采纳。"
            )
            saved = held
            restore(held, SettingChange.Kind.EXTERNAL)
        } else {
            clearSuspectRevertPending("文件轮询：复核显示它不是当前配置")
        }
    }

    /**
     * 订阅变更。**渲染层与菜单是主要订阅者**。
     *
     * @param listener 参数：① 新的整份快照（渲染层应整帧用它）；② 本次变更的描述
     *   （含涉及的键与阶段，供菜单做行高亮/撤销按钮状态刷新）
     * @return 取消订阅的句柄；调用方应持有并在销毁时调用（通常是 `close()` 里）
     */
    fun subscribe(listener: (SettingsSnapshot, SettingChange) -> Unit): AutoCloseable {
        subscribers += listener
        return AutoCloseable { subscribers.remove(listener) }
    }

    // ── 两段式写路径（大纲 §9.7 骨架纪律，不可合并） ─────────────────────

    /**
     * **第一段：publish**（拖动中调用）—— 更新 [live] 并通知订阅者，**不落盘**。
     *
     * ⚠️ **这是将来加回退功能的唯一挂载点**（大纲 §9.7 纪律 1 原文：
     * 「`publish`/`apply` 两段式必须保留 —— 若首版图省事把两者合并成
     * 『改了直接落盘』，将来加回退要**推翻设置系统的写路径**」）。
     * 因此**即便本版无撤销 UI，也绝不允许把 [publish] 与 [apply] 合并成一个方法**。
     *
     * ## 节流在哪里做？——**不在本方法里**
     *
     * 大纲 §9.7.2 要求"颜色不节流 / 数值 80 ms 合并"，但**合并的判定点在词岛帧首**，
     * 不在写路径（`recon/09` §1.4 的理由：只有帧首知道"最近编辑时间"+ 是否连续编辑中）。
     * 故本方法**一律同步 publish**，由 [SettingChange.preview] 把档位信息**透传**给订阅者，
     * 由渲染层按档位决定"当帧生效还是攒到 80 ms 网格边沿"。
     *
     * **为什么消息要透传而不是在本层丢弃**：若本层做了节流丢弃，
     * 颜色项与数值项就会走两条不同时序的路径，而渲染层无从知道"刚才那次抖动是丢弃还是延迟" ——
     * 透传档位让节流策略只有一处实现（渲染层帧首），避免两处各写一半。
     *
     * @param delta 本次要改的键值对（一次拖动可能只涉及 1 个键，取色器可能一次改 4 个）
     */
    fun publish(delta: Map<String, Any?>): SettingChange {
        val before = live
        val overrides = coerceDelta(delta)
        if (overrides.isEmpty()) {
            // 空变更（值没变/全部非法）→ 不递增版本、不通知。
            // 为什么必须挡：否则用户点一下滑杆松手会制造一次"假变更"，
            // 订阅者收到无差异通知会做无意义的重算。
            return SettingChange.empty(before)
        }
        val next = before.withAll(overrides)
        live = next
        version++
        val change = SettingChange(
            kind = SettingChange.Kind.PUBLISH,
            changedKeys = before.changedKeysAgainst(next),
            preview = resolvePreview(overrides.keys),
        )
        notifySubscribers(next, change)
        return change
    }

    /**
     * **第二段：apply**（松手/失焦/回车时调用）—— 落盘并更新 [saved]。
     *
     * 与 [publish] 的职责边界：
     * - [publish] 管"此刻看起来是什么样"（内存，可高频）；
     * - [apply] 管"下次启动还是什么样"（磁盘，低频、显式）。
     *
     * **"松手才应用，不自动"**（`recon/09` §2.2.1 用户拍板）：
     * 本类**不提供**任何"停手 N 秒自动 apply"的定时器。用户不松手，就永不落盘。
     * 这条是刻意设计（"用户不再遇到『我还在犹豫，它已经存了』"），
     * **不要为了"体验顺滑"自作主张加 autosave 定时器** —— 那正是被推翻的方案。
     *
     * @param delta 可选的收尾变更（通常为空 —— 拖动中已经 publish 过了）。
     *   传值适用于"松手瞬间还要补最后一次值"的场景（如步进器按住连点）。
     * @return 本次提交的 [SettingChange]；若没有实际变化则 [SettingChange.isEmpty]
     */
    fun apply(
        delta: Map<String, Any?> = emptyMap(),
        kind: SettingChange.Kind = SettingChange.Kind.APPLY,
    ): SettingChange {
        val change = publish(delta)
        // 即便 publish 报"无变化"，也要落盘 —— 可能上一次 publish 之后
        // saved 已被外部（文件轮询）改动过，本次显式 apply 是用户的"确认"动作。
        val ok = store.save(live)
        // ★★ 2026-10-18（P0）：**只有真的落盘成功才认账**。
        //   原来无条件 `saved = live` ⇒ 落盘失败后 `isDirty` 归零、关闭不再提醒、
        //   重启即丢 —— 用户以为存了，其实没有。
        if (ok) saved = live
        if (!ok) {
            // 落盘失败仅告警，不抛异常（见 SettingsStore.save 的 KDoc）。
            runCatching {
                WorkshopApi.ui.toast("词岛设置未能写入磁盘（内存已生效）", WorkshopApi.Ui.ToastType.Warning)
            }
        }
        return change.copy(kind = kind)
    }

    /**
     * 供撤销栈回灌整份状态（`recon/09` §3.2 的 undo/redo 回灌路径）。
     *
     * ⚠️ **必须经过本方法（而非直接赋值 [live]）** —— 否则 `version` 不递增，
     * 渲染层的派生资源不会失效，用户会看到"撤销了但画面没变"
     * （`recon/09` §3.2 用 `★` 标注了这个最容易写错的地方）。
     */
    fun restore(snapshot: SettingsSnapshot, kind: SettingChange.Kind = SettingChange.Kind.UNDO): SettingChange {
        val before = live
        val changed = before.changedKeysAgainst(snapshot)
        if (changed.isEmpty()) return SettingChange.empty(before)
        live = snapshot
        version++
        val change = SettingChange(
            kind = kind,
            changedKeys = changed,
            // 回灌一律按 IMMEDIATE 处理：这是一次完整的"应该长这样"，
            // 没有拖动中的连续中间态，不该被 80 ms 网格延迟。
            preview = PreviewKind.IMMEDIATE,
        )
        // ★ 外部变更必须留下可观察痕迹（BUG-039 的可观测性补强）。
        //
        // ## 为什么这条日志值得加
        //
        // BUG-039 的症状是「**用户在设置页改了，词岛毫无反应**」，而它的成因
        // （轮询拿自己比自己 → 恒空转）**在日志里完全看不出来** ——
        // 轮询看起来在工作（每 250 ms 都打「设置读取」），只是结果被丢掉了。
        // 结果是**排查只能靠肉眼比画面**，而"画面没变"既可能是没读到、
        // 也可能是读到了但该项没有消费点（BUG-025/043）—— **两者无法区分**。
        //
        // 这条日志把"**同步通道确实把新值送到运行时了**"变成一行可 grep 的事实。
        //
        // ## 为什么要节流
        //
        // 宿主滑杆在拖动中会**每次 `onValueChange` 都写盘**（实测行为），
        // 于是这里会被高频触发。不节流会把日志刷爆 —— 那正是 BUG-017 的形态
        // （「日志噪声淹没真信号」）。
        if (kind == SettingChange.Kind.EXTERNAL) {
            val now = System.currentTimeMillis()
            if (now - lastExternalLogMs >= EXTERNAL_LOG_INTERVAL_MS) {
                lastExternalLogMs = now
                val shown = changed.take(MAX_LOGGED_KEYS)
                val more = if (changed.size > shown.size) " …共 ${changed.size} 项" else ""
                System.err.println(
                    "[CDL] 外部设置变更已回灌（${changed.size} 项）：" +
                        shown.joinToString(", ") + more
                )
            }
        }
        notifySubscribers(snapshot, change)
        return change
    }

    /**
     * 把**关键设置的当前生效值**打一行日志（启动时调用一次）。
     *
     * ## 为什么需要它
     *
     * 用户报「设置改了没用」时，排查者面对的第一个问题是：
     * **设置到底读到了没有？**
     *
     * 在加这条之前，启动日志只报「**N 项定义**」—— 那是**数量**，不是**值**。
     * 于是下面两种情形**在日志里完全无法区分**，而它们的修法截然不同：
     *
     * | 情形 | 真因 | 修法 |
     * |---|---|---|
     * | 值读出来是**默认值** | 读取/同步通道坏了 | 查 BUG-039 那条路（轮询是否空转、监听是否注册上） |
     * | 值读出来是**用户设的** | 读到了，但该项**没有消费点** | 查 BUG-025 / BUG-043（渲染层没人读它） |
     *
     * 打**值**之后，一眼可辨。
     *
     * ## 为什么只打一小撮而不是**全部项**
     *
     * 全打出来是一屏噪声，而且绝大多数项与"外观看起来对不对"无关。
     * 这里挑的是**用户能肉眼看到效果**的那几项 —— 出问题时它们最能说明问题。
     *
     * ## ⚠️ 为什么会有**两次**调用（2026-09-17 补）
     *
     * 真机取证：`super-island.log` 里有 **4 次 `start:`，却只有 1 行快照** ——
     * 因为本方法在 `SettingsRuntime.create()` 里被调用，而那次调用发生在
     * **日志接管之前**；宿主是 jpackage 的 GUI 子系统、**没有控制台**，
     * 于是接管前打的一切**无处可去**（这正是 `CdlRuntime.start()` 开头
     * 那段"顺序说明"已经修过一次的同一个坑 —— 当时只搬了 `start:`，漏了本条）。
     *
     * 处置：**保留**这里的调用（在已接管的会话里它是"最早的现场"），
     * 并在 `CdlRuntime.start()` 的日志接管之后**再补打一次**。
     * 两次用 [stage] 区分，避免读者以为是重复输出。
     *
     * @param stage 快照的阶段标签，出现在日志里用于区分两次调用
     */
    internal fun logEffectiveSettings(stage: String = "启动快照") {
        val keys = listOf(
            "island.enabled", "island.interactive",
            // 2026-09-18：`island.experimental_multi_line` + `layout.line_mode`
            // 已合并为 `layout.lines`（单行 / 双行），故这里跟着换键。
            "layout.lines", "layout.translation.enabled",
            "main_lyric.font.family", "main_lyric.font.size",
            // ⚠️ 2026-09-17：原来这里是 3 个颜色 hex 键 —— 它们**已改为 12 根滑杆**
            // （用户拍板的「方案 A」）。12 个通道值排在一行里没人能一眼看懂，
            // 故改在下面**合成 hex 后**再打（见 `colors`）。
            "background.opacity", "shape.corner_radius",
            "cover.enabled", "spectrum.enabled",
        )
        val dump = keys.joinToString("  ") { key ->
            val value = runCatching { live.raw(key) }.getOrNull()
            "$key=${value ?: "<缺失>"}"
        }
        // ── 歌词三色：打**合成后**的 hex ──────────────────────────────────────
        //
        // 为什么合成而不是逐个通道打：本行的用途是"**一眼看出设置读到的是不是用户设的**"
        // （见本方法 KDoc 的那张对照表）。用户设的是"颜色"，不是"R=177, G=182, B=195" ——
        // 打 12 个数字等于把判断工作又推回给读日志的人。
        //
        // ⚠️ 这里的合成**必须与 `dev.cdl.window.Appearance.mixerColor` 同式**
        //   （`通道 × 明度/100`）—— 否则日志显示的颜色与屏幕上画的**不一致**，
        //   而"日志说对了、屏幕不对"是最难查的一类。两处都是 4 行，刻意不抽公共函数：
        //   一处是渲染层（要 `java.awt.Color`），一处是设置层（只要数字），
        //   强行共用会引入一个两边都不属于的中间类型。
        val colors = COLOR_PREFIXES.joinToString("  ") { prefix ->
            fun channel(suffix: String, fallback: Int): Int {
                val raw = runCatching { live.raw("$prefix.$suffix") }.getOrNull() as? Number
                return raw?.toInt()?.coerceIn(0, 255) ?: fallback
            }
            val lightness = (runCatching { live.raw("$prefix.lightness") }.getOrNull() as? Number)
                ?.toInt()?.coerceIn(0, 100) ?: 100
            val dim = lightness / 100f
            val hex = java.lang.String.format(
                "#%02X%02X%02X",
                (channel("r", 0) * dim).toInt().coerceIn(0, 255),
                (channel("g", 0) * dim).toInt().coerceIn(0, 255),
                (channel("b", 0) * dim).toInt().coerceIn(0, 255),
            )
            "$prefix=$hex"
        }
        System.err.println("[CDL] 生效设置（$stage）：$dump  $colors")
    }

    /** 有未保存的修改？（设置窗口的 `● 有未保存的修改` 状态栏与关闭保护用） */
    val isDirty: Boolean
        get() = live.changedKeysAgainst(saved).isNotEmpty()

    /**
     * 插件数据目录（配置文件所在目录）。
     *
     * 供**基础设施**使用（日志落盘、诊断输出），不是设置系统自身的需求。
     * 暴露它的理由：数据目录只有 `ConfigHelper` 知道，而需要用它的模块
     * （如 `host` 包的日志接管）不该自己去猜路径 —— 猜出来的路径可能污染用户目录。
     *
     * 取不到时返回 `null`（宿主配置通道未就绪）。
     */
    fun dataDirectory(): java.nio.file.Path? =
        runCatching { store.configPath()?.parent }.getOrNull()

    /**
     * 丢弃未落盘的修改，回到 [saved]（"放弃修改"按钮）。
     * 走 [restore] 以保证 `version` 递增、订阅者被通知。
     */
    fun discardUnsaved(): SettingChange = restore(saved, SettingChange.Kind.DISCARD)

    //
     // 外部（宿主/文件轮询）改了配置时的回灌。
     //
     // ## 为什么要防"自写回路"（`recon/09` §4.3 明确要求）
     //
     // 我们自己 [apply] 落盘 → 宿主触发 change 监听 → 若这里无条件把新值推给 [live]，
     // 就会"在用户拖滑杆时把 live 打回磁盘上的旧值"。
     // 对策：**若落盘后的内容与我们刚写的 [saved] 一致，就认定是自己写的，直接忽略。**
     //
    /**
     * 清除"待确认的疑似回退"标记。**只在确实扣着东西时打一行日志**。
     *
     * ## 为什么必须存在（2026-09-19，任务 C）
     *
     * 原实现里 [suspectRevertPending] **只在 [holdIfSuspectRevert] 内部被清**。
     * 而两条通道都有"本次读取与 [saved] 无差异就提前 return"的分支
     * （[pollOnce] 的两处、[onExternalConfigChanged] 的一处）——
     * 那些分支**根本不进** [holdIfSuspectRevert]，于是标记会**一直残留**。
     *
     * 残留的后果：某个**无关的**后续可疑读取只要恰好与这份陈旧标记相同，
     * 就会被"连续两次一致"误判成真实变更而采纳 → 这正是 H1
     * （"整份回默认被永久采纳、磁盘未变、轮询再也看不到差异"）的成因。
     *
     * 故：**任何一次"读回来的不是可疑回退"的读取，都必须了结这份标记。**
     * 判据与"按集合变化去噪"同源 —— 状态一旦不再成立，就不能留着。
     */
    private fun clearSuspectRevertPending(reason: String) {
        val had = suspectRevertPending ?: return
        suspectRevertPending = null
        System.err.println(
            "[CDL] 已丢弃扣住的疑似回退（$reason）：" +
                "扣住的是 ${had.changedKeysAgainst(saved).size} 项回默认的快照，" +
                "但本次读取说明它**不是**当前配置 —— 词岛继续使用已落盘态。"
        )
    }

    /**
     * 处理一份"疑似整份回退"的读取。**任务 C + 任务 D 的落点**。
     *
     * ## 判据：**变化键数 ≥ [RESET_SUSPECT_MIN_KEYS]**（BUG-054 / P0-2）
     *
     * ⚠️ **有意不再与 `isMostlyDefaults()` 取合取**。
     *
     * 早先的判据是"变了 ≥5 项 **且** 新快照几乎全是默认值（≥80%）"，
     * 真机日志（`super-island.log:7284`）证明那个合取项**整体失效**：
     * 有一次 **13 项（含 `window.position`）被无条件采纳**，
     * 且前后**没有任何**「已扣住」/「疑似读取异常已排除」/「确认恢复为默认」日志
     * —— 判据**根本没进入**。原因正是：用户已自定义 >20% 的键 ⇒
     * `isMostlyDefaults()` = false ⇒ `suspect` = false。
     *
     * ⇒ 那个 80% 阈值在「**用户自定义得多**」时失效，
     * **而那恰恰是最需要保护的场景**（自定义越多，一次误采纳丢得越多）。
     *
     * 去掉之后的代价：用户真在设置页连改 3~5 项时，会先被扣住、
     * 经一次强制复读确认后才采纳 —— 晚 250 ms 生效，**不可感**。
     *
     * 单用"变了 ≥5 项"的误判风险由**强制复读**（任务 D，见下）承担：
     * 真实变更会在复读里**逐字重现**，于是被确认为真实变更并采纳。
     *
     * ## ⚠️ 这里**有意不再要求** `previous` 不是"几乎全默认"
     *
     * 早先的设计加了这一条（想避免误判），但真机日志显示用户自定义了 15~20 项、
     * `isMostlyDefaults()` 仍可能为 true（阈值是 80%）。加那条会让判据**在
     * "用户只改了几项"时整体失效** —— 而那恰恰是最需要保护的情形。
     * 去掉之后代价只是"真实恢复默认会晚一步生效"，不可感知。
     *
     * ## 任务 D：**强制复读配置来源**（而不是"等下一次轮询"）
     *
     * 原实现要求"**连续两次读到同一份**"才认定真实变更，但那一步在真机里
     * **从未生效过**：真机日志里 25 次「已扣住」，**0 次**「已确认恢复为默认」。
     * 原因是 `pollOnce` 的第二次读取会被 [SettingsStore.reloadIfChanged] 的
     * 差异检测吃掉（`load()` 已把 `lastKnown` 更新成可疑快照，"拿自己比自己" →
     * 恒返回 `null` → 提前 return）—— **确认路径不可达**。
     *
     * 故改为**在同一次调用内**强制复读（[SettingsStore.reloadAndLoad]，
     * 不做差异比较）：
     *
     * | 复读结果 | 处置 |
     * | --- | --- |
     * | **与可疑快照不一致** | **以读回为准**，采纳复读结果（那次是瞬时异常） |
     * | 与可疑快照一致 | 两次**独立**读取一致 → 认定用户主动恢复默认 → 采纳 |
     * | 复读不到（`reload()` 失败 / 通道未就绪） | **退一步**：扣住，继续用上一次已知良好快照 [previous] |
     *
     * ⚠️ 复读走的是**既有的宿主配置通道**（`ConfigHelper.reload()`）——
     * 没有新增反射、没有去读宿主的私有文件（那是明令禁止的）。
     *
     * @param reread 强制复读：返回**当前**配置；`reload()` 失败 / 通道未就绪时返回 `null`。
     * @return `null` = 扣住（本次不采纳，继续用 [previous]）；
     *         非 null = 应采纳这一份（通常是入参 `fresh`，复读不一致时是复读结果）。
     */
    private fun holdIfSuspectRevert(
        previous: SettingsSnapshot,
        fresh: SettingsSnapshot,
        source: String,
        reread: () -> SettingsSnapshot?,
    ): SettingsSnapshot? {
        val changed = fresh.changedKeysAgainst(previous)
        // ── ★ BUG-054（P0-2）：判据**只**看"变化键数 ≥ 阈值" ────────────────
        //
        // 早先是 `… && fresh.isMostlyDefaults()`。真机取证（`super-island.log:7284`）
        // 有一次 13 项（含 `window.position`）被**无条件采纳**、且前后没有任何
        // 扣住/排除/确认日志 —— 说明判据根本没进入：用户自定义 >20% ⇒
        // `isMostlyDefaults()` = false ⇒ `suspect` = false。
        // 那个 80% 阈值在"用户自定义得多"时整体失效，而那正是最需要保护的场景。
        val suspect = changed.size >= RESET_SUSPECT_MIN_KEYS
        if (!suspect) {
            // 不是可疑情形 → 清掉待确认（上一轮的疑点已被这次正常读取否证）。
            clearSuspectRevertPending("$source：本轮读取不是整份回退")
            return fresh
        }

        // ── ① 强制复读配置来源（任务 D）─────────────────────────────────────
        val again = runCatching { reread() }.getOrNull()
        if (again == null) {
            // ── ② 复读不到 → 退一步：保留上一次已知良好快照并持续使用它 ──────
            if (suspectRevertPending == null) {
                suspectRevertPending = fresh
                val shown = changed.take(8).joinToString(", ")
                val more = if (changed.size > 8) " 等 ${changed.size} 项" else ""
                System.err.println(
                    "[CDL] ⚠️ 已扣住一次疑似读取异常（$source）：" +
                        "${changed.size} 项同时变为默认值（$shown$more）。" +
                        "**本次不采纳**；且复读配置来源拿不到内容（通道未就绪），" +
                        "故继续使用上一次已知良好快照。下一次读取会重新判定。"
                )
            }
            return null
        }
        if (again.changedKeysAgainst(fresh).isNotEmpty()) {
            // ── ③ 读回与可疑快照**不一致** → 以读回为准，不采纳可疑快照 ──────
            clearSuspectRevertPending("$source：复读结果与可疑快照不一致")
            System.err.println(
                "[CDL] ✅ 疑似读取异常已排除（$source）：可疑快照说 " +
                    "${changed.size} 项回默认，但强制复读拿到的**不是**它 —— " +
                    "**以复读结果为准**，词岛按复读到的配置渲染（未采纳可疑快照）。"
            )
            return again
        }
        // ── ④ 两次独立读取一致 → 认定为真实变更（用户主动恢复默认）────────
        clearSuspectRevertPending("$source：复读与可疑快照一致，确认为真实变更")
        System.err.println(
            "[CDL] 配置已确认恢复为默认（复读一致，$source）：" +
                "${changed.size} 项变化。已采纳。"
        )
        return fresh
    }

    private fun onExternalConfigChanged(external: ConfigHelper) {
        val externalSnapshot = runCatching { SettingsStore(external).load() }.getOrNull() ?: return
        // 自写检测：内容与 saved 一致 → 是我们刚写的，不回灌。
        val changed = externalSnapshot.changedKeysAgainst(saved)
        if (changed.isEmpty()) {
            // 读回来的就是已落盘态 → 上一轮扣住的疑点已被否证，**必须了结它**（任务 C）。
            // 不清的后果：这份陈旧快照会在某个**无关的**后续读取里被误判成
            // "连续两次一致"而采纳 —— 那正是 H1（永久回默认）的成因。
            clearSuspectRevertPending("宿主推送：读回与已落盘态一致")
            return
        }

        // ── ⚠️ 疑似"整份回默认"的**扣留**（2026-09-18 二次修订 + 2026-09-19 任务 D）──
        //
        // 原记（本方法第一版）：只打一行告警，仍**整份采纳**。当时的理由是
        //   "分不清宿主重置与用户主动恢复默认，擅自拒绝会让用户改不回来"。
        //
        // 实为：**代价不对称，原判据站不住**。真机日志里这条告警出现 8 次，
        //   每次都是 15~20 项同时回默认、且**下一次读取就恢复真值** ——
        //   即全部是"读取异常"而非"用户操作"。而每次误采纳都让用户看到
        //   **整份外观闪成默认**（用户原话：「外观有一次短暂变回默认」）。
        //   反过来，"误扣住一次真实变更"的代价只是**延迟一步生效**。
        //
        // 2026-09-19 追加（任务 D）：判定可疑后**不再只等下一次轮询**，而是
        //   **强制复读配置来源**（走同一个宿主通道 `ConfigHelper.reload()`，
        //   没有新增反射、没有读宿主私有文件）。复读不一致 → 以读回为准。
        //   判据与真机取证见 [holdIfSuspectRevert] 与 [suspectRevertPending] 的 KDoc。
        val adopt = holdIfSuspectRevert(saved, externalSnapshot, "宿主推送") {
            // 与上面的读法**同一条通道**：新建一个 store 再整份读一次
            // （`reloadAndLoad` 会先让宿主 reload，故拿到的不是内存里的陈旧值）。
            SettingsStore(external).reloadAndLoad()
        } ?: return

        // 用户手改/旧版本迁移：整份采纳，并把它记为已落盘态。
        saved = adopt
        restore(adopt, SettingChange.Kind.EXTERNAL)
    }

    override fun close() {
        // 顺序：① 先置关闭标记（让正在跑的 pollOnce 尽快返回，不再通知订阅者）；
        //      ② 停轮询线程（shutdownNow 会中断，但 pollOnce 本身很快，不必等）；
        //      ③ 清订阅者；④ 反注册宿主监听。
        //
        // 为什么"先置标记"重要：轮询线程可能在 close 的任意时刻执行到
        // `restore(...)` → `notifySubscribers(...)`。若先清空了 subscribers，
        // 那是无害的（空列表）；但若先置标记，它能**在更早的位置**就返回，
        // 连读盘都省掉。这是"先断源、再拆管"的顺序。
        closed = true
        poller.shutdownNow()
        subscribers.clear()
        runCatching { configManager.removeConfigChangeListener(hostListener) }
    }

    /**
     * 把"任意类型的入参"收敛成"合法值"，并**丢弃非法项**。
     *
     * 非法项丢弃而非回退默认值 —— 与 [SettingDescriptor.coerce] 在读盘路径的
     * 策略不同，这里是有意的差别：
     * - **读盘**（coerce）：来源是磁盘，值坏了也**得有一个值**，故回退默认；
     * - **写**（本方法）：来源是 UI，值坏了说明**控件给错了键**，回退默认反而会
     *   把用户没碰的设置项悄悄改掉 —— 那更糟。故直接丢弃并告警。
     */
    private fun coerceDelta(delta: Map<String, Any?>): Map<String, Any> {
        if (delta.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Any>(delta.size)
        for ((key, value) in delta) {
            val descriptor = SettingRegistry.descriptor(key)
            if (descriptor == null) {
                System.err.println("[CDL] 未知设置键（已忽略）：$key")
                continue
            }
            if (value == null) continue
            val coerced = runCatching { descriptor.coerce(value) }.getOrNull() ?: continue
            // 与当前值相同 → 不列入（避免制造无差异变更）。
            if (coerced != live.raw(key)) result[key] = coerced
        }
        return result
    }

    /**
     * 由本次涉及的键推断节流档位 —— **取最保守（最慢）的一档**。
     *
     * 为什么取最慢：一次提交可能同时改字体族（DEFERRED）与颜色（IMMEDIATE）。
     * 若报告 IMMEDIATE，渲染层会在字体还没准备好时就宣称"已生效"；
     * 取最慢档让渲染层知道"这次里有一项要等"，不会过早宣称完成。
     * 顺序：DEFERRED > THROTTLED > IMMEDIATE。
     */
    private fun resolvePreview(keys: Set<String>): PreviewKind {
        var result = PreviewKind.IMMEDIATE
        for (key in keys) {
            when (SettingRegistry.descriptor(key)?.preview) {
                PreviewKind.DEFERRED -> return PreviewKind.DEFERRED
                PreviewKind.THROTTLED -> result = PreviewKind.THROTTLED
                else -> Unit
            }
        }
        return result
    }

    private fun notifySubscribers(snapshot: SettingsSnapshot, change: SettingChange) {
        for (subscriber in subscribers) {
            // 单个订阅者抛异常不能影响其余订阅者（渲染层崩了不该连累菜单）。
            runCatching { subscriber(snapshot, change) }
                .onFailure { System.err.println("[CDL] 设置订阅者异常：$it") }
        }
    }

    companion object {
        /**
         * 「疑似被重置」的**同时变化键数**下限（2026-09-18 加）。
         *
         * 取 5 的理由：用户手动调设置**一次只动一两项**（拖一根滑杆、点一个开关）；
         * 而"整份被重置"会一次动几十项。5 是这两者之间的宽裕分界 ——
         * 取 2 会把"用户连着改了三项"误报，取 20 会漏掉"只重置了部分组"的情形。
         *
         * ⚠️ **它单独使用**（2026-09-22，BUG-054 / P0-2）。
         *
         * 早先它与 [SettingsSnapshot.isMostlyDefaults]（80% 阈值）**联合使用**，
         * 但真机取证证明那个合取项在「用户自定义得多」时恒为 false ⇒
         * 判据**整体失效**（`super-island.log:7284`：13 项被无条件采纳、判据根本没进入）。
         * 现在只看本阈值；误判由 `holdIfSuspectRevert` 里的**强制复读**兜住。
         * 见 [holdIfSuspectRevert] 与 [onExternalConfigChanged] 的调用处。
         */
        const val RESET_SUSPECT_MIN_KEYS = 5

        /**
         * 本插件的配置文件**文件名**。
         *
         * ## 铁律：必须与 `preference_config.json` 的 `config` 字段逐字一致
         *
         * 这个字符串出现在**两处**，必须完全相同：
         * ① `plugin/src/main/resources/preference_config.json` 的 `"config": "cdl.json"`
         *    —— 宿主据此创建/托管配置文件，并把它与 SPW 设置页绑定；
         * ② 本常量 —— 插件据此取 `ConfigHelper` 读写。
         *
         * 两者不一致的后果：**设置页改了没反应，且不报任何错**。
         * 取证与原实现对照见 `recon/18-宿主设置页机制规格.md` §B.3 与
         * `BUG-与修复记录.md` 的 BUG-002。
         *
         * 既有的对应物：`LyricsSettings.CONFIG_FILE = "spw-lyrics.json"`
         * （与其 `preference_config.json` 第二页的 `config` 字段一致）。
         *
         * ⚠️ 改这个值时**必须同步改 `preference_config.json`**；
         * `tools/gen_preference_config.py` 里的 `CONFIG_FILE` 也要一起改
         * （该脚本是 JSON 的生成源）。
         */
        const val CONFIG_FILE: String = "cdl.json"

        /**
         * 歌词三色的**键前缀**（2026-09-17「方案 A」）。
         *
         * 每色由 4 根滑杆构成：`<前缀>.r` / `.g` / `.b` / `.lightness`。
         *
         * ⚠️ 与 `SettingRegistry.groupF()` 里那 12 个键**必须逐字对应** ——
         * 散在两处时改一处漏一处**不报错**，只会让"启动快照里少一个颜色"。
         * 之所以不抽到注册表里：注册表是"设置项的定义"，而这是"日志要打哪几个"，
         * 属于**消费侧**的关注点（同 `dev.cdl.window.Appearance` 里的读取点）。
         */
        val COLOR_PREFIXES: List<String> = listOf(
            "main_lyric.fill.color",
            "main_lyric.fill.unsung",
            "main_lyric.translation.color",
        )

        /**
         * 轮询周期（毫秒）。
         *
         * 取 250 ms 是**对齐原实现**（`HostSettings.kt:38-40` 的
         * `scheduleWithFixedDelay(..., 250, 250, MILLISECONDS)`），不自行发挥。
         *
         * 为什么是 250 而不是更小：轮询要读文件 + 解析 JSON + 全量比对全部设置项，
         * 更小的周期（如 50 ms）在用户连续拖动滑杆时会产生大量无用比对；
         * 而 250 ms 是"人感觉不到延迟"与"开销可忽略"的平衡点。
         *
         * 为什么不能更大（如 1 s）：SPW 设置页改一项后，若 watcher 恰好失效，
         * 用户会看到词岛"慢半拍"才反应 —— 超过约 300 ms 就会被感知为卡顿。
         */
        const val POLL_INTERVAL_MS: Long = 250L

        /**
         * 「外部设置变更已回灌」日志的最小间隔（毫秒）。
         *
         * **为什么需要节流**：宿主滑杆在拖动中会**每次 `onValueChange` 都写盘**，
         * 回灌路径因此会被高频触发。不节流会把日志刷爆 ——
         * 那正是 BUG-017 的形态（「日志噪声淹没真信号」），
         * 本项目为此专门修过一次。
         *
         * 取 1 s：拖动期间每秒一条足以证明"通道在工作"，而不会淹没其它信息。
         */
        const val EXTERNAL_LOG_INTERVAL_MS: Long = 1000L

        /**
         * 单条回灌日志里最多列出几个键名。
         *
         * 拖动一次可能一次改动十几项（例如换预设），全列出来会让单行过长、
         * 反而不好读。超出部分只报总数。
         */
        const val MAX_LOGGED_KEYS: Int = 6

        /**
         * 创建并装配。**返回类型可空**：宿主配置通道不可用时返回 `null`，
         * 让插件以"无设置系统"的降级形态继续运行（项目铁律：插件不能因为配置坏了就崩）。
         *
         * ## 关于文件级 `@file:OptIn(UnstableSpwWorkshopApi::class)`（实测取证）
         *
         * SPW 把配置通道整体标注为不稳定：
         * `javap -v` 显示 `ConfigHelper` / `WorkshopApi$Manager` 的**类型级**
         * `RuntimeInvisibleAnnotations` 里有 `@kotlin.RequiresOptIn(level = ERROR)`，
         * 而 `WorkshopApi$Ui` / `WorkshopApi$Playback` **没有**该注解。
         * → 即**只有配置读写这条通道是显式不稳定的**，`ui.toast` 等是稳定面。
         *
         * 因此 opt-in **必须是文件级**：注解挂在 `ConfigHelper` 这个**类型**上，
         * 任何触碰它的成员（`get`/`set`/`save`/`reload`/`getConfigPath`）都会触发告警。
         * 先前只在 `create()` 上写 `@OptIn` 会**挡住 30 处类型级用法**（构建实测：
         * 30 条 `e:` 全部指向 `config.xxx` 调用点），故改为文件级。
         *
         * **代价与防线**：文件级 opt-in 的坏处是"整个文件的 API 用法都被免检"。
         * 为此本文件把配置通道的使用**收敛到 [SettingsStore] 一个类**，
         * 其余部分只碰 [SettingsSnapshot]（自有类型）。将来 SPW 升级破坏了这个通道，
         * 需要改的地方就被限制在这一个类里。
         */
        internal fun create(context: PluginContext): SettingsRuntime? {
            val manager = runCatching { WorkshopApi.manager.createConfigManager() }
                .getOrElse {
                    System.err.println("[CDL] 无法创建 ConfigManager：$it")
                    return null
                }
            // ⚠️ 必须写成 `getConfig(CONFIG_FILE)` 而不是 `getConfig()`。
            //
            // `ConfigManager` 声明了两个重载（见 recon/_javap_api_dump.txt:135-141）：
            //   getConfig()              → **默认配置**，不是我们声明的那份
            //   getConfig(String)        → 按名字取声明文件   ← 正确
            // 用无参版本会让设置读写落到**另一个文件**上，而 SPW 设置页读写的
            // 是我们声明的那份 → 用户在设置页改了，词岛毫无反应，且**没有任何报错**。
            //
            // 原实现两个配置实例都用带名版本（HostSettings.kt:16 / LyricsSettings.kt:25），
            // 且 `LyricsSettings.CONFIG_FILE = "spw-lyrics.json"` 与其 preference_config.json
            // 的 `config` 字段逐字一致 —— 这是**铁律**，见 CONFIG_FILE 的 KDoc。
            val helper = runCatching { manager.getConfig(CONFIG_FILE) }
                .getOrElse {
                    System.err.println("[CDL] 无法取得 ConfigHelper（$CONFIG_FILE）：$it")
                    return null
                }
            val runtime = SettingsRuntime(SettingsStore(helper), manager)
            runtime.attachHostListener()
            runtime.startPolling()
            val path = runtime.store.configPath()
            System.err.println(
                "[CDL] 设置系统就绪：${SettingRegistry.all.size} 项定义 · " +
                    "配置文件=$CONFIG_FILE（$path）"
            )
            // 紧接着打出**关键项的生效值** —— 只报数量的话，
            // "没读到"与"读到了但没消费点"在日志里无法区分（见该方法 KDoc）。
            runCatching { runtime.logEffectiveSettings() }
                .onFailure { System.err.println("[CDL] 打印生效设置失败：$it") }
            return runtime
        }
    }
}
