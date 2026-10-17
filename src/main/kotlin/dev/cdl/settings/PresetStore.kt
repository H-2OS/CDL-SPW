// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 词岛配置的**预设**：一组 `键 → 值`，可导出成文件、可从文件导入。
 *
 * @property name 预设名。**它就是文件名（去扩展名）** —— 用户原话
 *   「以 `.txt`/`.json` 形式存储/读取（**以文件名为预设名**）」。
 * @property values 设置快照。值是注册表里的**原始类型**（`Boolean`/`Int`/`Float`/`String`）。
 */
data class Preset(val name: String, val values: Map<String, Any>) {
    /** 与另一个预设的差异（用于"导入前预览会改什么"）。 */
    fun diffAgainst(other: Map<String, Any>): Map<String, Pair<Any?, Any?>> =
        (values.keys + other.keys).associateWith { it to (other[it] to values[it]) }
            .filter { (k, v) -> other[k] != values[k] && k.isNotEmpty() }
            .mapValues { (_, v) -> v }
}

/**
 * 预设的**存储与解析** —— 导入 / 导出 / 列举。
 *
 * ## 用户原话（2026-09-19）
 *
 * > **「重量级：允许导入导出词岛配置，以 `.txt`/`.json` 形式存储/读取
 * > （以文件名为预设名），在菜单新增导入导出配置选项，
 * > 新增预设选择子菜单（多选框，允许已导入和默认预设出现在列表中）。」**
 *
 * ## 为什么两种格式都支持
 *
 * - **`.json`** —— 结构化、可被别的工具处理、能带元信息（版本号）；
 * - **`.txt`** —— `键=值` 每行一条，用户**能直接看、能手改、能贴给别人**。
 *
 * 用户点名要这两种，**不是二选一**：`json` 是机器友好的默认，`txt` 是"我想看一眼"的路径。
 * 故 [exportTo] / [importFrom] **按扩展名自动选格式**（见 [Format.detect]）。
 *
 * ## 为什么不引 JSON 库
 *
 * 本插件的依赖表只有 JNA / jaudiotagger / PF4J（见 `build.gradle.kts`）——
 * **没有 JSON 库**，而设置值的类型只有 `Boolean`/`Int`/`Float`/`String` 四种，
 * 结构是"一个扁平对象"，**引入一个库的代价（体积 + 许可审查 + 传递依赖）
 * 远大于手写这 40 行**。手写的代价是"要自己保证转义正确"——
 * 这一点由 [jsonEscape] / [jsonUnescape] 承担，且探针会断言往返一致。
 *
 * ## 纪律：**不认识的键要报告，不要静默丢**
 *
 * 导入一份**别的版本**导出的预设时，可能含本版已删的键
 * （例如 2026-09-19 删掉的 `performance.host_lyrics_probe`）。
 * 静默忽略会让用户以为"导入成功了"，而实际上少了一批设置。
 * 故 [importFrom] 把未知键收在 [ImportReport.unknownKeys] 里**如实上报**。
 */
object PresetStore {

    /** 文件格式。 */
    enum class Format(val extension: String) {
        JSON("json"),
        TXT("txt"),
        ;

        companion object {
            /** 按扩展名判定；**未知扩展名按 [TXT]**（那是人可读的那份，出错时更好排查）。 */
            fun detect(file: File): Format =
                if (file.extension.equals("json", ignoreCase = true)) JSON else TXT
        }
    }

    /** 导入结果：成功的预设 + **被忽略的未知键**（不静默吞掉）。 */
    data class ImportReport(
        val preset: Preset,
        /** 文件里有、但本版注册表里没有的键。**调用方必须把非空结果告知用户**。 */
        val unknownKeys: List<String>,
        /** 文件里有、且键存在，但值无法解析的键。 */
        val unparsableKeys: List<String>,
    )

    /** 预设目录：`<数据目录>/presets/`。数据目录不可用时返回 null。 */
    fun directory(dataDirectory: Path?): Path? =
        dataDirectory?.resolve("presets")

    /**
     * 列举**已导入**的预设（扫 [directory]）。
     *
     * 读不出来的文件**跳过并计数**（返回 [Listing.skipped]）——
     * 一个坏文件不该让整个子菜单空掉。
     */
    data class Listing(val presets: List<Preset>, val skipped: List<String>)

    fun list(dataDirectory: Path?): Listing {
        val dir = directory(dataDirectory) ?: return Listing(emptyList(), emptyList())
        if (!Files.isDirectory(dir)) return Listing(emptyList(), emptyList())
        val presets = ArrayList<Preset>()
        val skipped = ArrayList<String>()
        Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { path ->
                    val file = path.toFile()
                    if (file.extension.lowercase() !in setOf("json", "txt")) return@forEach
                    importFrom(file).fold(
                        onSuccess = { presets += it.preset },
                        onFailure = { skipped += file.name },
                    )
                }
        }
        return Listing(presets, skipped)
    }

    /**
     * 把一份**外部文件**存进预设目录，使它出现在「选择预设」列表里。
     *
     * ## 为什么需要这个入口（P0-1）
     *
     * 用户原话是「新增预设选择子菜单（多选框，**允许已导入和默认预设出现在列表中**）」，
     * 而在此之前 `importFrom` 只负责**解析**、调用方只做 `settings.apply(...)` ——
     * **没有任何落盘动作**。于是"导入"的语义实际退化成「**应用一次**」：
     * 用户导入 `我的预设.json` 后打开「选择预设」，**里面没有它**。
     * 想让它出现，只能手工把文件拷进 `<数据目录>/presets/`，
     * 而那个路径在界面上无处可见（也没有"打开预设目录"入口）。
     *
     * ## 语义（三条都是刻意的）
     *
     * 1. **复制，不是移动** —— 用户选中的原文件不能被动过（他可能是从下载目录随手挑的）；
     * 2. **同名时不覆盖**，而是去重命名（`名字 (2).json`）—— 覆盖会把
     *    "我只是想试一下这份配置"变成"我原来那份预设没了"；
     * 3. **同一份内容只存一条**：源文件已在预设目录里、或目录里已有**逐字节相同**的文件时
     *    直接返回那一条 —— 否则"同一个文件导两次"会变成两条一模一样的预设，
     *    而用户没有任何办法分辨它们（预设名就是文件名）。
     *
     * @return 实际写入（或已存在）的路径。数据目录不可用时 `Result.failure`。
     */
    fun install(dataDirectory: Path?, source: File): Result<Path> = runCatching {
        val dir = directory(dataDirectory) ?: error("数据目录不可用，无法把预设存入列表")
        val dirAbs = dir.toAbsolutePath().normalize()
        val src = source.toPath().toAbsolutePath().normalize()
        // 已经在预设目录里：它就是列表里的那一条，不必（也不该）再复制一份。
        if (src.parent == dirAbs) return@runCatching src
        Files.createDirectories(dirAbs)
        // 未知扩展名按 TXT 落盘（与 `Format.detect` 的兜底一致），
        // 否则会写出一个 `名字.` 这种 `list()` 不认的文件（= 存了但看不见）。
        val ext = source.extension.lowercase().ifEmpty { Format.TXT.extension }
        val base = source.nameWithoutExtension.ifBlank { "预设" }
        var target = dirAbs.resolve("$base.$ext")
        var n = 2
        while (Files.exists(target)) {
            // 逐字节相同 → 同一份预设已经在了，返回它（语义 3）。
            if (Files.mismatch(src, target) == -1L) return@runCatching target
            target = dirAbs.resolve("$base ($n).$ext")
            n++
        }
        Files.copy(src, target)
        target
    }

    // ── 删除 / 更改 / 改名（2026-09-22 用户要求）────────────────────────────
    //
    // 用户原话（逐字）：「**在设置项中要提供删除、更改预设的入口**。」
    //
    // ## 为什么"删除"是**移走**而不是 `delete()`
    //
    // 预设就是文件（预设名 = 文件名，见 [Preset] 的 KDoc），而 `delete()` 不可逆 ——
    // 用户点错一次，手写的那份配置就没了。故 [moveToDeleted] 把它移进
    // `presets/_deleted/`：对用户而言"从列表里消失了"（[list] 只扫顶层、
    // `Files.isRegularFile` 会跳过子目录），但他随时能把文件**移回来**。
    //
    // ## 为什么内置预设**不可删、不可改**
    //
    // [BUILT_IN] 是**代码里的常量**，没有文件 —— 删了下次启动又回来
    //（用户会以为"删除失效"），改也没有可写的地方。判据是 [isBuiltIn]，
    // 由调用方**拒绝并说明原因**，不做静默失败。

    /**
     * 删除归档目录名（`presets/_deleted/`）。
     *
     * 它是 `presets/` 的**子目录**而不是直接子文件 —— 故 [list] 扫不到它
     *（那里按 `Files.isRegularFile` 过滤，目录会被跳过），删除后的预设立刻从列表消失。
     */
    const val DELETED_DIR = "_deleted"

    /**
     * 预设**不携带**的运行时状态键（2026-10-18）。
     *
     * 用户视角：预设是「样式」，不是「窗口此刻在哪、最近用过哪些字体」。
     * 把这两类键写进预设，导入后会出现「窗口突然跳回旧位置」「最近字体被清空」——
     * 用户不会认为这是「恢复样式」。
     *
     * ★ `SettingRegistry` 的 `SELF_DRAWN_KEYS` 注释也明确它们「不是样式」。
     * 导出 / 覆盖 / 导入 / 应用 / 「全部复原」五处**共用这一份定义** —— 不要各处再抄一遍。
     */
    val EXCLUDED_KEYS: Set<String> = setOf(
        "window.position",
        "main_lyric.font.recent",
    )

    /**
     * 从一份设置快照里剔除 [EXCLUDED_KEYS]。
     *
     * ## 只在**应用**侧用（2026-10-18 修正）
     *
     * `window.position` 是本项目**唯一的自由文本键**（EDITTEXT），
     * 探针用它验证"含换行/引号/反斜杠的值能否往返"。若导出侧也过滤它，
     * 转义链路就再没有可测的载体（`PresetProbe` / `PresetAuditProbe` 会当场崩）。
     *
     * ⇒ 口径：**导出 / 导入 / 覆盖保留全量**（那是"备份/保存当前配置"），
     *   **应用**时才剔除运行时键（用户不会希望点一下预设，窗口就跳回旧位置）。
     */
    fun <V> filterForPreset(values: Map<String, V>): Map<String, V> =
        values.filterKeys { it !in EXCLUDED_KEYS }

    /**
     * 预设 **JSON** 的格式标识 —— 写出（[toJson]）与读取（[parseJson]）**共用这一个真源**。
     *
     * 为什么必须是常量、而不是两侧各写一份字面量：本项目已有先例 ——
     * 同一个值有两个来源时，改一处漏一处**不报错**（纪律 #9「改名必须同步所有按字面量
     * 引用它的地方」，以及「设置值域两个来源必须一致」那条）。这里把它收敛成一个常量，
     * 让"改了写出侧、没改读取侧"变成**编译期不可能**。
     */
    const val FORMAT_ID = "cdl-preset"

    /**
     * 当前格式版本。**提高它之前先想好迁移路径。**
     *
     * [parseJson] 对 `version > FORMAT_VERSION` 是**明确拒绝**（不是尽力猜）：
     * 更晚版本的文件可能带本版读不懂的语义，静默按本版解析会让用户拿到一份
     * "看起来导入成功、实际错了"的配置。这与 [parseJson] 的既有立场一致。
     */
    const val FORMAT_VERSION = 1

    /** [name] 是不是 [BUILT_IN] 里的内置预设。内置项**没有文件**：不可删 / 不可改 / 不可改名。 */
    fun isBuiltIn(name: String): Boolean = BUILT_IN.any { it.name == name }

    /** 认作预设文件的扩展名（与 [Format] 的两个值一致）。 */
    private val PRESET_EXTENSIONS = setOf(Format.JSON.extension, Format.TXT.extension)

    /**
     * 列举预设**文件**（不含 [BUILT_IN]），按文件名排序。
     *
     * ⚠️ 排序口径与 [list] **不保证逐字相同**（那边按 `Path` 排序，这里按文件名字符串
     * 排序，大小写与 Unicode 的先后可能不同）—— 调用方不该依赖两者顺序一致。
     *
     * 与 [list] 的分工：`list` 返回**解析好的** [Preset]（读不出来的计入 `skipped`），
     * 本函数返回**文件本身** —— 删除 / 改名 / 覆盖的操作对象是文件，不是解析结果。
     *
     * ⚠️ **只看顶层、只看普通文件** —— `_deleted/` 是子目录，故归档掉的文件不会
     * 出现在这里（这正是"删除后从列表消失"的实现方式）。
     */
    fun files(dataDirectory: Path?): List<File> {
        val dir = directory(dataDirectory)?.toFile() ?: return emptyList()
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && it.extension.lowercase() in PRESET_EXTENSIONS }
            .sortedBy { it.name }
    }

    /** 文件名里非法 / 危险的字符（Windows 与跨平台都拒）。 */
    private const val ILLEGAL_NAME_CHARS = "\\/:*?\"<>|"

    /** Windows 保留设备名 —— `CON.txt` 会被当成设备，根本写不进去。 */
    private val RESERVED_NAMES: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    /**
     * 校验"新的预设名"。**纯函数**（不碰文件系统），故探针能直接断言。
     *
     * @param taken 已被占用的名字（**其余**预设的文件名去扩展名 + [BUILT_IN] 的名字）。
     *   调用方必须**把自己排除掉**，否则"改成同名"会被误拒。
     * @return `null` = 合法；否则返回**给用户看**的原因（指名道姓，不返回泛化文案）。
     */
    fun validatePresetName(newName: String, taken: Collection<String>): String? {
        if (newName.isBlank()) return "预设名不能为空"
        if (newName.length > 80) return "预设名不能超过 80 个字符（当前 ${newName.length} 个）"
        if (newName.any { it < ' ' || it == '\u007F' }) {
            return "预设名不能含换行、制表符等控制字符"
        }
        if (newName != newName.trim()) return "预设名首尾不要留空格（空白会被当成名字的一部分）"
        if (newName.any { it in ILLEGAL_NAME_CHARS }) return "预设名不能含这些字符：\\ / : * ? \" < > |"
        if (newName.endsWith(".")) return "预设名不能以句点结尾"
        if (newName.equals(DELETED_DIR, ignoreCase = true)) {
            return "「$DELETED_DIR」是删除归档目录名，不能用作预设名"
        }
        if (newName.uppercase() in RESERVED_NAMES) {
            return "「$newName」是 Windows 保留设备名，不能用作预设名"
        }
        val clash = taken.firstOrNull { it.equals(newName, ignoreCase = true) }
        if (clash != null) return "已有同名预设「$clash」"
        return null
    }

    /**
     * **删除**：把预设文件移到 `presets/_deleted/`（**不是 `delete()`**，理由见本段 KDoc）。
     *
     * 归档目录里已有同名文件时加**时间戳后缀**，**绝不覆盖** ——
     * 归档目录里的东西同样是用户的数据。
     *
     * @return 归档后的路径。**调用方必须把它告诉用户**，否则用户不知道去哪把预设找回来。
     */
    fun moveToDeleted(dataDirectory: Path?, file: File): Result<Path> = runCatching {
        val dir = directory(dataDirectory) ?: error("数据目录不可用，无法归档预设")
        require(file.isFile) { "预设文件不存在：${file.name}" }
        val archive = dir.resolve(DELETED_DIR)
        Files.createDirectories(archive)
        var target = archive.resolve(file.name)
        if (Files.exists(target)) {
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            target = archive.resolve("${file.nameWithoutExtension}-$stamp.${file.extension}")
        }
        Files.move(file.toPath(), target)
        target
    }

    /**
     * 覆盖前把原文件**复制**一份到 `presets/_deleted/`（2026-10-18）。
     *
     * 与 [moveToDeleted] 的区别：那条是"移走原文件"，本条是"留副本、原文件继续用"。
     * 归档目录里已有同名文件时加时间戳后缀（同 [moveToDeleted]），绝不覆盖旧归档。
     */
    private fun backupBeforeOverwrite(file: File) {
        val dir = file.parentFile ?: return
        // ⚠️ 这里用 `java.io.File` 而不是 `Path`：`file.parentFile` 是 File，
        //   而 `File.resolve(...)` 返回的仍是 File（不是 Path）—— 传给 `Files.*` 会编译失败。
        val archive = File(dir, DELETED_DIR)
        archive.mkdirs()
        var target = File(archive, file.name)
        if (target.exists()) {
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            target = File(archive, "${file.nameWithoutExtension}-$stamp.${file.extension}")
        }
        target.parentFile?.mkdirs()
        Files.copy(file.toPath(), target.toPath())
    }

    /**
     * **更改**：用 [values] 覆盖这个预设文件的内容。**文件名不变 ⇒ 预设名不变。**
     *
     * ## 为什么先写临时文件再替换（而不是直接 `writeText`）
     *
     * `File.writeText` 是**先截断再写**：磁盘满 / 进程被杀会让用户原来那份预设变成
     * 半截文件。而本操作的语义是"把当前配置存进这个预设" ——
     * **中途失败必须留下原来那份**，不能两头落空。
     * 故先写到**数据目录**（`presets/` 之外 —— 免得临时文件被 [list] 当成一条预设），
     * 再 `Files.move(..., REPLACE_EXISTING)`（同卷，等价于替换）。
     *
     * ⚠️ 临时文件**必须带同样的扩展名**：`Format.detect` 按扩展名选格式，
     * 写成 `x.json.tmp-123` 会被判成 TXT —— 于是 JSON 预设被改写成 TXT 内容，
     * 而这件事**不报错**（下次读回来照样能解析，但格式与用户预期不符）。
     */
    fun overwrite(file: File, values: Map<String, Any>): Result<Path> = runCatching {
        require(file.isFile) { "预设文件不存在：${file.name}" }
        val ext = file.extension.ifEmpty { Format.TXT.extension }
        val parent = file.parentFile?.parentFile ?: error("预设目录异常，无法定位数据目录")
        val scratch = File(parent, "${file.nameWithoutExtension}.tmp-${System.nanoTime()}.$ext")
        try {
            exportTo(scratch, file.nameWithoutExtension, values).getOrThrow()
            // ★ 2026-10-18：**覆盖前先备份原文件**（与删除同一归档目录）。
            //   删除都有 `_deleted/`，覆盖没有 —— 用户点错一次就永久丢配置。
            backupBeforeOverwrite(file)
            Files.move(
                scratch.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (scratch.exists()) scratch.delete()
        }
        file.toPath()
    }

    /**
     * **改名** = 改文件名（用户原话「以文件名为预设名」）。
     *
     * @param newName 新名字（**不带扩展名**）。
     * @return 新路径。名字非法 / 已被占用时 `Result.failure`，**原因指名道姓**。
     */
    fun rename(dataDirectory: Path?, file: File, newName: String): Result<Path> = runCatching {
        require(file.isFile) { "预设文件不存在：${file.name}" }
        val dir = directory(dataDirectory) ?: error("数据目录不可用，无法改名")
        // 占用名单 = **其余**预设的文件名 + 内置名（排除自己，否则"改成同名"会被误拒）。
        val taken = files(dataDirectory)
            .filter { it.absolutePath != file.absolutePath }
            .map { it.nameWithoutExtension } + BUILT_IN.map { it.name }
        // 名字不合法 → `Result.failure`，**原因指名道姓**（不是泛化的"改名失败"）。
        val problem = validatePresetName(newName, taken)
        if (problem != null) error(problem)
        val name = newName.trim()
        val ext = file.extension.ifEmpty { Format.TXT.extension }
        val target = dir.resolve("$name.$ext")
        // 二次护栏：`validatePresetName` 只比"名字"，这里比"文件真的在不在" ——
        // 大小写、Unicode 归一化等**文件系统层面**的等价只有它看得出来。
        //
        // ★ 2026-10-18：**目标存在且不是同一个文件**才冲突。
        //   原来只看 `exists` ⇒ 在大小写不敏感的 Windows 上，
        //   `My preset.json` → `my preset.json` 会命中自己 ⇒ 被误拒。
        val sameFile = runCatching {
            Files.exists(target) && Files.isSameFile(target, file.toPath())
        }.getOrDefault(false)
        if (Files.exists(target) && !sameFile) {
            error("已有同名预设「$name」，未改名（避免覆盖）")
        }
        Files.move(file.toPath(), target)
        target
    }

    /**
     * 菜单勾选判据：当前快照是否**就是**这个预设（用于「选择预设」子菜单的 radio）。
     *
     * ## 为什么空表预设必须排除（P0-2）
     *
     * `Map.all{}` 对**空集恒为 `true`** —— 而首条内置预设「默认（全部复原）」
     * 的值恰恰就是空表（见 [BUILT_IN]），于是这一行**在任何状态下都显示为已勾选**：
     * 用户刚把字号调大两档，菜单里"默认"仍然亮着 —— **界面在说谎**，
     * 而"界面说谎"是最伤信任的一类缺陷。
     *
     * 空表预设的语义是"回到开箱状态"，它**不是一份具体的配置**，
     * 没有"当前是不是它"可言，故**不参与勾选**。
     *
     * （不去改成"当前是否等于全量默认值"：SEEKBAR 的原始值是 `Float`
     * 而 `descriptor.default` 是 `Int`，逐键相等几乎永不成立 ——
     * 那只是换一种方式说谎，而且更难解释。）
     *
     * ## 判据为什么不 trim / 不归一化
     *
     * 这里比的是**已经过 [SettingDescriptor.coerce] 的当前值**与预设里声明过的键，
     * 两侧类型由各自的生产者保证（快照 = `expectedType`，预设 = 本对象的导入路径）。
     * 故只做一次"字符串形态相同也算相同"的兜底比较，用于跨 `Int`/`Float` 的数值形态差异。
     */
    fun matches(preset: Preset, current: Map<String, Any?>): Boolean {
        if (preset.values.isEmpty()) return false
        // ① 声明过的键：严格相等（保留"字符串形态相同也算"的跨类型兜底）。
        val declaredOk = preset.values.all { (k, v) ->
            val cur = current[k]
            cur == v || cur?.toString() == v.toString()
        }
        if (!declaredOk) return false
        // ② 未声明的键：必须仍是**注册表默认值**（2026-10-18）。
        //    原来只比声明键 ⇒ 用户改了预设没提到的键，勾仍然亮 = 界面在说谎。
        //    · 运行时键（`EXCLUDED_KEYS`）不参与 —— 窗口位置几乎永远不等于默认；
        //    · `retired` 键（已下架、设置页不可见）也不参与。
        return current.all { (k, cur) ->
            k in EXCLUDED_KEYS ||
                preset.values.containsKey(k) ||
                run {
                    val descriptor = SettingRegistry.descriptor(k) ?: return@run true
                    if (descriptor.retired) return@run true
                    val default = descriptor.default
                    cur == default || cur?.toString() == default?.toString()
                }
        }
    }

    /**
     * 导出。**文件名即预设名**，故 [name] 只用来校验（与文件去扩展名后的名字一致）。
     *
     * @return 实际写出的路径。父目录会自动创建。
     */
    fun exportTo(file: File, name: String, values: Map<String, Any>): Result<Path> = runCatching {
        file.parentFile?.mkdirs()
        val text = when (Format.detect(file)) {
            Format.JSON -> toJson(name, values)
            Format.TXT -> toTxt(name, values)
        }
        // ★ 2026-10-18：**先写同目录临时文件再原子替换**。
        //   `writeText` 是先截断再写：磁盘满 / 进程被杀会让用户原来那份预设变成半截文件。
        //   ⚠️ 临时文件**必须保留原扩展名结尾**（`Format.detect` 按扩展名选格式）。
        val ext = file.extension
        val scratch = File(
            file.parentFile,
            file.nameWithoutExtension + ".tmp-" + System.nanoTime() +
                (if (ext.isEmpty()) "" else ".$ext"),
        )
        try {
            scratch.writeText(text, Charsets.UTF_8)
            Files.move(
                scratch.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (scratch.exists()) scratch.delete()
        }
        file.toPath()
    }

    /**
     * 导入。
     *
     * @return [ImportReport]；解析失败（文件不存在 / 格式错）返回 `Result.failure`。
     */
    fun importFrom(file: File): Result<ImportReport> = runCatching {
        require(file.isFile) { "文件不存在：${file.absolutePath}" }
        val text = file.readText(Charsets.UTF_8)
        val raw = when (Format.detect(file)) {
            Format.JSON -> parseJson(text)
            Format.TXT -> parseTxt(text)
        }
        // 文件名（去扩展名）**优先于**文件里写的 name —— 用户原话"以文件名为预设名"。
        val name = file.nameWithoutExtension
        val known = raw.filterKeys { SettingRegistry.descriptor(it) != null }
        val unknown = raw.keys.filter { SettingRegistry.descriptor(it) == null }.sorted()
        // 值按注册表的 kind 归一化（`"true"` → `true`、`"96"` → `96`）。
        val typed = HashMap<String, Any>(known.size)
        val unparsable = ArrayList<String>()
        for ((k, v) in known) {
            val d = SettingRegistry.descriptor(k)!!
            val coerced = coerceByKind(d, v)
            if (coerced != null) typed[k] = coerced else unparsable.add(k)
        }
        // ★★ 2026-10-18：**整份文件解析不出任何键时必须失败**。
        //
        // 否则会走一条静默的数据损失路径：导入「成功」→ 存入预设列表 →
        // 用户点它 → `apply(空表)` 什么也不写，而界面已显示「已导入」。
        //
        // ⚠️ 判据是 **raw 为空**，不是 `typed` 为空：文件里可能全是
        //   「本版不认识」或「值非法」的键 —— 那种情况必须**照常返回报告**，
        //   让 `unknownKeys` / `unparsableKeys` 把问题如实列出来
        //   （探针 `PresetAuditProbe` 的 D/E 用例正是断言这条）。
        //   「0 项可用 ⇒ 不入库、不应用」由调用方 `importPreset` 负责。
        require(raw.isNotEmpty()) {
            "没有解析出任何设置项（文件为空或不是预设格式）"
        }
        ImportReport(Preset(name, typed), unknown, unparsable.sorted())
    }

    // ── 值归一化 ─────────────────────────────────────────────────────────

    /**
     * 按注册表的 kind 把"文本形态的值"归一化成正确类型。
     *
     * 为什么必须归一化：`.txt` 里一切都是字符串（`"true"`/`"96"`），
     * 直接 `apply(mapOf(k to "96"))` 会让 `int()` 取到 `0`（类型不符 → 回退默认），
     * 用户看到的是"导入成功了但设置没变"。**这是本项目反复出现的缺陷类型**：
     * 值进去了、但消费点读不出来，且**不报错**。
     *
     * 返回 `null` = 这个值**无法**按该 kind 兑现（调用方记入 `unparsableKeys`）。
     *
     * ## 这里**同时**做类型归一化与值域校验（2026-09-21 修，P1-1）
     *
     * 原先本方法只做**类型转换**，于是它与真正的收敛点 [SettingDescriptor.coerce]
     * **能力不对等**：`layout.lines=PREV_AND_CURRENT`（非法选项）与
     * `layout.line_gap_px=99999`（range 是 0..80）都被原样收下，
     * `unparsableKeys` 报 `[]`，而 `apply` 时 `descriptor.coerce` 把它们
     * **静默回退默认 / 静默 clamp 到 80** —— 用户看到"导入成功"，
     * 实际有若干项被悄悄改掉。**"报告"与"实际生效"由两个函数决定，必然不一致。**
     *
     * 现在的规则是**一条**：**无法被完整兑现的值一律返回 `null`**，
     * 由调用方如实上报，**不做静默降级**（静默改用户的值是本项目反复出现的缺陷类型）。
     * 这也是为什么超范围的 SEEKBAR **不 clamp** —— clamp 是"替用户决定"，
     * 而导入是用户的显式动作，他有权知道哪一项没被采纳。
     */
    // ⚠️ **public 而不是 internal**：探针看不到 `internal`（项目已知约束）。
    //    而本方法是**纯函数**（无状态、只做类型归一化与值域校验），把它公开的代价是零 ——
    //    比"为了测它而放宽某个类的可见性"干净得多。**项目纪律：首选抽公开纯函数。**
    fun coerceByKind(descriptor: SettingDescriptor, value: Any): Any? = when (descriptor.kind) {
        SettingKind.SWITCH -> when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
            is Number -> value.toInt() != 0
            else -> null
        }
        SettingKind.SEEKBAR -> {
            val n = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }
            // 与 `descriptor.coerce` 同一判据（`!isFinite()` 挡下 NaN/Infinity），
            // 但**不做 clamp** —— 见上面的 KDoc。判据用截断后的 `Int`，
            // 因为真正落盘的就是它（`96.9` 与 `96` 对用户是同一项）。
            val r = descriptor.range
            if (n == null || !n.isFinite()) {
                null
            } else {
                val i = n.toInt()
                if (r != null && (i < r.start || i > r.endInclusive)) null else i
            }
        }
        SettingKind.COLOR -> when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
                ?: dev.cdl.settings.parseHexOrNull(value.trim())
            else -> null
        }
        // LIST 的值是**该键 options 里的标识符**，故必须校验选项集合 ——
        // 不校验时 `coerceText` 会把它回退成默认值（静默），而报告说"没问题"。
        // 顺带 `trim()`：手写 `layout.lines = DUAL` 会带来一个前导空格，
        // 而标识符两侧的空白不承载语义（见 [parseTxt] 对值侧空白的处理）。
        SettingKind.LIST -> {
            val text = value.toString().trim()
            val options = descriptor.options
            if (options != null && options.none { it.first == text }) null else text
        }
        // EDITTEXT / FONT 是自由文本，任何字符串都是合法值（空串由 `coerce` 按 kind 兜底）。
        SettingKind.EDITTEXT, SettingKind.FONT -> value.toString()
    }

    // ── JSON（手写，无依赖）──────────────────────────────────────────────

    internal fun toJson(name: String, values: Map<String, Any>): String = buildString {
        appendLine("{")
        appendLine("  \"format\": \"$FORMAT_ID\",")
        appendLine("  \"version\": $FORMAT_VERSION,")
        appendLine("  \"name\": \"${jsonEscape(name)}\",")
        appendLine("  \"values\": {")
        val keys = values.keys.sorted()
        keys.forEachIndexed { i, k ->
            val v = values[k]
            append("    \"${jsonEscape(k)}\": ")
            append(jsonValue(v))
            appendLine(if (i == keys.lastIndex) "" else ",")
        }
        appendLine("  }")
        append("}")
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> v.toString()
        else -> "\"${jsonEscape(v.toString())}\""
    }

    // public 同理（纯函数，探针要直接断言转义往返）。
    fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    fun jsonUnescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { append(c); i++; continue }
            when (val e = s[i + 1]) {
                '"' -> { append('"'); i += 2 }
                '\\' -> { append('\\'); i += 2 }
                'n' -> { append('\n'); i += 2 }
                'r' -> { append('\r'); i += 2 }
                't' -> { append('\t'); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) { append(code.toChar()); i += 6 } else { append(e); i += 2 }
                }
                else -> { append(e); i += 2 }
            }
        }
    }

    /**
     * 解析 JSON 预设。
     *
     * **只认本对象写出的形状**（一个扁平对象 + 一个 `values` 子对象），
     * 不实现通用 JSON 解析器 —— 那需要处理嵌套数组/对象，
     * 而预设的数据形状是固定的。**遇到不认识的形状明确抛异常**，
     * 而不是"尽力猜"（猜错会让用户拿到一份静默错误的配置）。
     *
     * ## `format` / `version` 的兼容承诺（2026-09-24 施工单 B 补上读者）
     *
     * **原状**：[toJson] 一直在写 [FORMAT_ID] / [FORMAT_VERSION] 两个字段，
     * 但本方法**从来没读过它们** —— 全树读 `"cdl-preset"` 的地方 = 0 处
     *（唯一另一处在探针夹具里）。⇒ 那是一条**没有任何读者**的兼容承诺。
     *
     * 后果：一份**别的格式**、或**更晚版本**的文件，只要碰巧含 `values` 段，
     * 就会被当成正常预设解析 —— 用户拿到一份静默错误的配置，
     * 而 [ImportReport] 说"没问题"。（本项目反复出现的缺陷类型：
     * 写出去的东西没有读者、报告与实际生效不一致。）
     *
     * 现在补三条判据，**顺序即优先级**：**先认格式，再认版本**。
     * 拒绝时的措辞刻意让用户看得懂 —— 尤其 `version` 那条要**带上它看到的版本号**，
     * 否则用户只被告知"导入失败"（这正是本单要求"消息里必须带版本号"的原因）。
     */
    internal fun parseJson(text: String): Map<String, Any> {
        val start = text.indexOf("\"values\"")
        require(start >= 0) { "不是有效的预设文件：找不到 \"values\" 段" }

        // ── 兑现 `format` / `version` 的兼容承诺（施工单 B）────────────────────
        //
        // ⚠️ 两个字段在**顶层**（`values` 之前），故只在 `text[0, start)` 里找 ——
        //    不这样限定的话，`values` 里恰好叫 `format` 的**设置键**
        //   （现在没有，将来可能有）会被误当成文件头。
        //
        // ⚠️ **正则刻意不锚行首**（不写 `(?m)^\s*`）—— 这是探针逼出来的修正：
        //    第一版锚了行首，于是**单行 JSON**（`{ "format": … }` 全在一行）被判成
        //    "format 缺失"，而它的 format 明明在 ⇒ 3 条断言当场红。
        //    [toJson] 写的是多行，但**手写 / 别的工具生成**的预设常是单行，
        //    而 `parseJson` 的立场是"遇到不认识的形状明确抛异常" ——
        //    这里的形状**是认识的**，只是排版不同，**排版不承载语义**。
        //    （宽容输入、严格输出；把排版当语义会让用户看到误导性的报错。）
        //
        //    为什么不会误匹配到 `"name"` 的值里：`toJson` 会把值里的 `"` 转义成 `\"`，
        //    于是值内的 `\"format\"` 序列**结尾是 `\` 而不是 `"`**，本正则不成立。
        val header = text.substring(0, start)
        val formatFound = Regex("\"format\"\\s*:\\s*\"([^\"]*)\"")
            .find(header)?.groupValues?.get(1)
        require(formatFound == FORMAT_ID) {
            "不是本插件的预设文件：format=${formatFound ?: "（缺失）"}，应为 \"$FORMAT_ID\""
        }
        val versionFound = Regex("\"version\"\\s*:\\s*(-?\\d+)")
            .find(header)?.groupValues?.get(1)
        require(versionFound != null) { "不是本插件的预设文件：缺少 version 字段" }
        val version = versionFound.toInt()
        // `version < FORMAT_VERSION` 本版不存在（v1 是首版）⇒ 无需特判；
        // 将来加 v2 时，迁移挂载点就在这里（按 `version` 分支后回落）。
        require(version <= FORMAT_VERSION) {
            "预设文件的版本 $version 高于本插件支持的 $FORMAT_VERSION，请先升级插件再导入"
        }

        val brace = text.indexOf('{', start)
        require(brace >= 0) { "不是有效的预设文件：\"values\" 后没有对象" }
        // ⚠️ **不要去找"配对的 `}`"**（2026-09-21 修，P0-3）。
        //
        // 原实现用 `text.indexOf('}', brace)` 当作 values 对象的闭合，而 [jsonEscape]
        // **不转义 `}`** —— 于是值里只要出现 `}`（如 `"a}b"`），body 就被**提前截断**，
        // 读回的是 `["a` 这样的残片，**不报错、也不进 `unparsableKeys`**。
        //
        // 本格式是**扁平对象**：`"key": value` 的顺序序列，**排版不承载语义**
        //（与上面 `format` / `version` 的读法保持一致）。解析见 [parseFlatObject] ——
        // 它**不按行切**，因此多行与单行两种排版都能读。
        //
        // 这里只保留一条**粗粒度**的完整性护栏：文件里必须存在一个在 `brace` 之后的 `}`，
        // 否则说明文件被截断了（连 values 对象的闭合都没有）。它是"文件像不像预设"的
        // 最低限度检查，**不是括号配平** —— 括号配平正是上面被删掉的那个错误做法。
        require(text.lastIndexOf('}') > brace) { "不是有效的预设文件：\"values\" 对象没有闭合" }
        return parseFlatObject(text.substring(brace + 1))
    }

    /**
     * 解析 `values` 的**扁平对象体**（`"key": value` 的顺序序列）。
     *
     * ## ★ 为什么**不按行**切（2026-09-24 施工单 B 收口，由 `PresetProbe` §10 逼出）
     *
     * 上一版是 `body.lines()` ＋ 行正则 `^\s*"(…)"\s*:\s*(.+?)\s*,?\s*$`。它对
     * [toJson] 写出的**多行**排版没问题，但**单行 JSON** 下整段 body 只有一行，
     * 惰性 `.+?` 会把**后续的 `}` 一起吃进值里** ⇒ 解析出 `"true } }"` 这样的值，
     * [coerceByKind] 兑现不了 ⇒ 归入 `unparsableKeys` ⇒ **`importFrom` 仍然报"成功"，
     * 而值一个都没进来**。这正是本项目最忌讳的一类缺陷：
     * **报告与实际生效不一致，且不报错**（[coerceByKind] 的 KDoc 记的是同族缺陷）。
     *
     * 而且它与**文件头自相矛盾**：`format` / `version` 已明确"排版不承载语义
     *（宽容输入、严格输出）"，body 却仍要求多行 ⇒ 同一份文件在头部被接受、
     * 在 body 被静默丢弃。
     *
     * ## 扫描规则（与 [jsonValue] 写出的形状一一对应）
     *
     * - 跳过空白与 `,`；遇到 `}` 结束；遇到**不是 `"` 开头**的东西 ⇒ **停止**
     *  （保持"遇到不认识的形状明确抛异常 / 不尽力猜"的立场，不硬凑）。
     * - 键 = JSON 字符串（[readJsonString] 认 `\"` 等转义）。
     * - 值 = JSON 字符串 **或** 到下一个 `,` / `}` 为止的裸 token（交给 [parseJsonScalar]）。
     *  ★ **带引号的值整体读取** ⇒ 值里含 `,` / `}` / `:` 都不会破坏解析
     *  （原 P0-3 的 `"a}b"` 由这里天然覆盖，不再依赖"别去找配对括号"那条禁令）。
     */
    private fun parseFlatObject(body: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= body.length || body[i] == '}') break
            if (body[i] != '"') break
            val k = readJsonString(body, i) ?: break
            i = k.second
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != ':') break
            i++
            while (i < body.length && body[i].isWhitespace()) i++
            val raw: String
            if (i < body.length && body[i] == '"') {
                val v = readJsonString(body, i) ?: break
                raw = body.substring(i, v.second)
                i = v.second
            } else {
                val st = i
                while (i < body.length && body[i] != ',' && body[i] != '}') i++
                raw = body.substring(st, i).trim()
            }
            out[jsonUnescape(k.first)] = parseJsonScalar(raw)
        }
        return out
    }

    /**
     * 从 [at]（**必须**指向 `"`）读一个 JSON 字符串，返回
     * `(引号内的转义原文, 闭合引号之后的下标)`；读到结尾都没有闭合引号 ⇒ `null`
     * （调用方**停止**解析，不硬凑）。
     */
    private fun readJsonString(s: String, at: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = at + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(c).append(s[i + 1]); i += 2; continue
            }
            if (c == '"') return sb.toString() to (i + 1)
            sb.append(c); i++
        }
        return null
    }

    private fun parseJsonScalar(raw: String): Any = when {
        raw == "null" -> ""
        raw == "true" -> true
        raw == "false" -> false
        raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
            jsonUnescape(raw.substring(1, raw.length - 1))
        raw.toLongOrNull() != null -> raw.toLong()
        raw.toDoubleOrNull() != null -> raw.toDouble()
        else -> raw
    }

    // ── TXT（`key=value` 每行一条）───────────────────────────────────────

    internal fun toTxt(name: String, values: Map<String, Any>): String = buildString {
        appendLine("# CDL for SPW（测试版） · 配置预设")
        appendLine("# 预设名：$name")
        appendLine("# 格式：每行 `键=值`；以 # 开头的是注释。**可直接手改**。")
        appendLine("# ⚠️ `=` 两侧**不要加空格** —— 值里的首尾空白是数据的一部分，会被原样保留。")
        appendLine()
        values.keys.sorted().forEach { k ->
            val v = values[k]?.toString() ?: ""
            // 值里的换行会破坏"一行一条"的结构，故折成转义（**单遍扫描**，见 [txtEscape]）。
            appendLine("$k=${txtEscape(v)}")
        }
    }

    /**
     * TXT 值的转义 —— 与 [jsonEscape] **同一思路（单遍扫描 + 前缀无关）**，但表更小。
     *
     * ## 为什么不能"两次 `replace`"（P0-4 的根因）
     *
     * 原实现是
     * 写出 `v.replace("\\", "\\\\").replace("\n", "\\n")`、
     * 读回 `raw.replace("\\n", "\n").replace("\\\\", "\\")` ——
     * **两侧的替换顺序相反**，于是含**字面 `\n`**（反斜杠 + 字母 n，路径与正则里很常见）
     * 的值往返后被静默改写：写出 `a\nb`（4 字符）→ 读回 `a` + 真换行 + `b`。
     * 分步 `replace` 的每一步都会"看见"上一步的产物，顺序一错就产生歧义；
     * **单遍扫描没有这个问题**（同一个字符不会被处理两次）。
     *
     * ## 为什么不直接复用 [jsonEscape]
     *
     * TXT 的值不在引号里，`"` 无需转义 —— 复用它会把值写成 `a\"b`，降低"能手改"这一份的价值。
     */
    internal fun txtEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    /**
     * [txtEscape] 的**逆** —— 同样是单遍扫描，与它**逐字符对称**。
     *
     * ⚠️ 不认识的转义（手写 `C:\Users` 里的 `\U`）**原样保留反斜杠**。
     * 这一条是刻意的：TXT 是"用户能手改、能手贴"的那份格式，
     * 而 Windows 路径是它最常见的值 —— `jsonUnescape` 会把 `\U` 吃成 `U`，
     * 于是 `C:\Users\foo` 变成 `C:Usersfoo`。**"看不懂的转义"应当原样保留，而不是吃掉。**
     */
    internal fun txtUnescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { append(c); i++; continue }
            when (val e = s[i + 1]) {
                '\\' -> { append('\\'); i += 2 }
                'n' -> { append('\n'); i += 2 }
                'r' -> { append('\r'); i += 2 }
                // 未知转义：**连反斜杠一起**原样保留（见 KDoc）。
                else -> { append(c); append(e); i += 2 }
            }
        }
    }

    internal fun parseTxt(text: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (line in text.lines()) {
            // ⚠️ **只去行尾的 `\r`（CRLF 文件），不 trim 整行**（P0-5）。
            // 原实现对整行 `line.trim()`，于是值**尾部的空白被吃掉**：
            // `window.position=  padded  ` 读回来是 `  padded`。
            // 值里的首尾空白是数据的一部分（EDITTEXT 的自由文本、复合值都可能有），
            // 而 `=` 的**键侧**空白才是排版噪声 —— 故只 trim 键侧。
            val raw = line.removeSuffix("\r")
            val eq = raw.indexOf('=')
            if (eq <= 0) continue
            val key = raw.substring(0, eq).trim()
            // 注释与空行：键侧去掉空白后以 `#` 开头（或为空）即跳过。
            if (key.isEmpty() || key.startsWith("#")) continue
            out[key] = txtUnescape(raw.substring(eq + 1))
        }
        return out
    }

    // ── 内置默认预设 ─────────────────────────────────────────────────────

    /**
     * **内置预设**（用户原话："允许**已导入和默认预设**出现在列表中"）。
     *
     * 每个内置预设只列**与注册表默认值不同的键** —— 空表 = "全部回到默认"，
     * 那是最有用的一条（用户调乱了想一键复原）。
     *
     * ⚠️ 这里的键必须**真实存在于注册表**，且 `LIST` 的值必须是**该键的合法选项** ——
     *    两条都由 `PresetProbe` 断言（§7 断键存在、§9 断 `LIST` 值是合法选项）。
     *
     * （2026-09-21 更正：本注释原先只写了"由 `PresetProbe` 断言（见其 §7）"，
     * 而 §7 当时**只断言了键存在**，没有任何一条断言检查选项合法性 ——
     * 注释描述了一个不存在的检查，读者会以为它已被守住。
     * 现在 §9 补齐了那条断言，注释与探针一致。见 `recon/49` §4②。）
     *
     * **这条断言第一次跑就抓到了我自己的错**：本列表初版写了
     * `island.experimental_multi_line`（**已被删除**的键）与
     * `layout.lines = "PREV_AND_CURRENT"`（**不是合法选项** —— 该键只有 `SINGLE`/`DUAL`）。
     * 若没有这条断言，用户点那个预设会**静默无效**（写进去的键不存在 → 被 `apply` 忽略），
     * 而"点了没反应"正是本项目最难归因的缺陷类型。
     *
     * > **教训：内置数据（预设/模板/清单）必须与它的"真相源"一起被断言。**
     * > 它们看起来是"常量"、不会错 —— 而实际上**注册表会变，内置数据不会自己跟着变**。
     */
    val BUILT_IN: List<Preset> = listOf(
        Preset("默认（全部复原）", emptyMap()),
        // ★★★ 2026-10-18（**用户指令**）：内置 **三项** ——
        //   「默认、经典椒盐、实验预设」，且**分发包里就应有这三项**
        //   ⇒ 必须写进代码常量（文件型预设只在**本机数据目录**，不随包分发）✓
        //
        // ★ 为什么是**两份独立的 mapOf**（而不是共用一份常量）：
        //   用户明确要两个**可分别演进**的样式 —— 共用同一个 Map 实例的话，
        //   以后改「实验预设」会连带动到「经典椒盐」（那是"看起来是两个、其实是一个"）✗
        //
        // ★ 内置两份都排除 `window.position`（不是样式）与 `main_lyric.font.recent`
        //   （内部历史，不是选项 —— 预设探针会把 LIST 值逐项校验，它会被判非法 ✗）。
        // ★ 用户 01:1x 又改了 `main_lyric.font.weight`（NORMAL → **BOLD**）⇒ 「经典椒盐」已按新值更新 ✓
        //
        // ★★★ 2026-10-18（**用户指令**）：「实验预设」的键值已**整体替换为用户当时的
        //   当前设置**（源 = 宿主 `workshop/data/dev.cdl.spw/cdl.json`，147 键，
        //   与替换前**同数** ⇒ 不是"新增了一批键"，而是"同一批键换了值"）。
        //   两侧仍**故意不同**（`font.weight` BOLD/NORMAL、`shape.type` SQUARE/PILL、
        //   `material.fill.gradient_style` NONE/CAMO、`island.border.enabled` false/true …）
        //   ⇒ 「先各存一份，之后各自调」这条约定继续成立。
        //
        // ★ 为什么仍排除那两个键（**不要**因为"照抄当前设置"就把它们带上）：
        //   `window.position` 记的是**窗口坐标**（`"1107,764"`）—— 那是"我这台机器上
        //     窗口恰好在哪"，不是样式；随预设分发给别的用户会把他的词岛挪到屏幕别处。
        //   `main_lyric.font.recent` 是**字体历史**（当前是空串）—— 它不是注册表里的
        //     选项，`PresetProbe` §9 会按`LIST` 合法选项校验它 ⇒ 带上就**必红**。
        //   ⇒ 这两条是**判据**（"什么算样式"与"探针会校验什么"），不是临时取舍。
        Preset(
            "经典椒盐",
            mapOf(
                "animation.duration_scale" to 100,
                "animation.enabled" to true,
                "animation.frame_log" to false,
                "animation.frame_rate" to "AUTO",
                "animation.quality" to "HIGH",
                "animation.style" to "PER_COMPONENT",
                "background.color" to "#14161A",
                "background.color_mode" to "manual",
                "background.cover.angle_deg" to 0,
                "background.opacity" to 0,
                "clock.hour_format" to "H24",
                "clock.style" to "SEGMENT",
                "controls.animation_style" to "FOLLOW",
                "controls.autoHideMs" to 0,
                "controls.enterDelayMs" to 120,
                "controls.exitDelayMs" to 250,
                "controls.gapPx" to 0,
                "controls.hitPaddingPx" to 16,
                "controls.position" to "TOP",
                "controls.progress_style" to "GLOW",
                "controls.showProgress" to true,
                "controls.triggerMode" to "ISLAND_HIT",
                "cover.enabled" to false,
                "cover.lock" to false,
                "cover.rotate" to false,
                "cover.rotate_period_s" to 20,
                "cover.scale_with_font" to true,
                "cover.shape" to "ROUNDED",
                "cover.sharpness" to 25,
                "cover.vinyl" to false,
                "island.always_on_top" to true,
                "island.auto_hide_on_hover" to false,
                "island.border.color.b" to 255,
                "island.border.color.g" to 255,
                "island.border.color.r" to 255,
                "island.border.color_mode" to "manual",
                "island.border.dissolve_band_percent" to 50,
                "island.border.enabled" to false,
                "island.border.mode" to "SOLID",
                "island.border.width_tenth_px" to 10,
                "island.enabled" to true,
                "island.hide_fullscreen" to true,
                "island.hide_paused" to false,
                "island.hit.cover" to false,
                "island.interactive" to false,
                "island.lock_position" to false,
                "island.right_slot.component" to "NONE",
                "island.right_slot.polar_outer_percent" to 100,
                "island.right_slot.radial_inner_percent" to 0,
                "island.right_slot.ring_inner_percent" to 55,
                "island.right_slot.spectrum_bars" to "5",
                "island.right_slot.spectrum_color" to "#84D8BC",
                "island.right_slot.spectrum_color_mode" to "follow",
                "layout.content_align" to "CENTER",
                "layout.fixed_width" to false,
                "layout.island.height.follow_font" to true,
                "layout.island.height.ratio_percent" to 120,
                "layout.line_gap_px" to 0,
                "layout.lines" to "DUAL",
                "layout.max_width" to 1400,
                "layout.noLyrics.hideWhenInstrumental" to false,
                "layout.noLyrics.mode" to "PLACEHOLDER",
                "layout.noLyrics.showAlbum" to false,
                "layout.noLyrics.showTrackTitle" to true,
                "layout.noLyrics.titleInTranslation" to false,
                "layout.translation.enabled" to true,
                "layout.translation.position" to "BELOW",
                "layout.translation.scale_percent" to 100,
                "lyric.animation_style" to "FOLLOW",
                "lyric.offset_ms" to 0,
                "main_lyric.color_mode" to "manual",
                "main_lyric.fill.color.b" to 255,
                "main_lyric.fill.color.g" to 255,
                "main_lyric.fill.color.lightness" to 100,
                "main_lyric.fill.color.r" to 255,
                "main_lyric.fill.unsung.b" to 138,
                "main_lyric.fill.unsung.g" to 129,
                "main_lyric.fill.unsung.lightness" to 100,
                "main_lyric.fill.unsung.r" to 126,
                "main_lyric.font.family" to "@host:MiSans",
                "main_lyric.font.size" to 22,
                "main_lyric.font.weight" to "BOLD",
                "main_lyric.karaoke.mode" to "SMOOTH",
                "main_lyric.letter_spacing_tenth_px" to 0,
                "main_lyric.outline.color.b" to 255,
                "main_lyric.outline.color.g" to 255,
                "main_lyric.outline.color.r" to 255,
                "main_lyric.outline.enabled" to false,
                "main_lyric.outline.follow_font_weight" to false,
                "main_lyric.outline.weight_link_percent" to 50,
                "main_lyric.outline.width_tenth_px" to 10,
                "main_lyric.translation.color.b" to 195,
                "main_lyric.translation.color.g" to 182,
                "main_lyric.translation.color.lightness" to 100,
                "main_lyric.translation.color.r" to 177,
                "main_lyric.translation.color_mode" to "manual",
                "material.backdrop.blur_radius" to 0,
                "material.backdrop.dispersion" to 35,
                "material.backdrop.refraction_amount" to 32,
                "material.backdrop.refraction_height" to 16,
                "material.backdrop.saturation" to 150,
                "material.content.blur_radius" to 0,
                "material.fill.color_end" to "#2A2E38",
                "material.fill.flow_speed" to 100,
                "material.fill.gradient_angle_deg" to 90,
                "material.fill.gradient_style" to "NONE",
                "material.fill.gradient_tint_percent" to 30,
                "material.highlight.strength" to 0,
                "material.liquid.depth_effect" to false,
                "material.liquid.glare_strength" to 0,
                "material.liquid.shadow_strength" to 0,
                "material.liquid.surface_alpha" to 0,
                "material.noise.amplitude" to 0,
                "material.quality" to "FULL",
                "material.render.gpu_backend" to false,
                "menu.opacity_pct" to 100,
                "menu.submenu_close_delay_ms" to 250,
                "menu.submenu_open_delay_ms" to 180,
                "settings.auto_ungate_on_touch" to true,
                "shape.corner_n" to 4,
                "shape.corner_radius" to 60,
                "shape.corner_style" to "ARC",
                "shape.type" to "SQUARE",
                "spectrum.anchor" to "BOTTOM",
                "spectrum.bar_pitch_mode" to "FIXED",
                "spectrum.bar_pitch_tenth_px" to 70,
                "spectrum.bar_shape" to "ROUND",
                "spectrum.bounce_pct" to 100,
                "spectrum.color" to "#84D8BC",
                "spectrum.color_mode" to "manual",
                "spectrum.enabled" to false,
                "spectrum.fade_top" to true,
                "spectrum.height_percent" to 100,
                "spectrum.opacity_pct" to 75,
                "spectrum.progress_tint" to false,
                "spectrum.progress_tint_alpha" to 100,
                "spectrum.secondary.mode" to "OFF",
                "spectrum.secondary.opacity_percent" to 35,
                "spectrum.symmetric" to false,
                "translation.letter_spacing.follow_main" to true,
                "translation.outline.color.b" to 255,
                "translation.outline.color.g" to 255,
                "translation.outline.color.r" to 255,
                "translation.outline.enabled" to false,
                "translation.outline.follow_weight" to false,
                "translation.outline.sync_with_main" to true,
                "translation.outline.width_tenth_px" to 10,
            ),
        ),
        Preset(
            "实验预设",
            mapOf(
                "animation.duration_scale" to 500,
                "animation.enabled" to true,
                "animation.frame_log" to false,
                "animation.frame_rate" to "AUTO",
                "animation.quality" to "HIGH",
                "animation.style" to "PER_COMPONENT",
                "background.color" to "#14161A",
                "background.color_mode" to "manual",
                "background.cover.angle_deg" to 0,
                "background.opacity" to 80,
                "clock.hour_format" to "H24",
                "clock.style" to "SEGMENT",
                "controls.animation_style" to "FOLLOW",
                "controls.autoHideMs" to 0,
                "controls.enterDelayMs" to 120,
                "controls.exitDelayMs" to 250,
                "controls.gapPx" to 0,
                "controls.hitPaddingPx" to 16,
                "controls.position" to "BOTTOM",
                "controls.progress_style" to "GLOW",
                "controls.showProgress" to true,
                "controls.triggerMode" to "GLYPH_HIT",
                "cover.enabled" to true,
                "cover.lock" to false,
                "cover.rotate" to true,
                "cover.rotate_period_s" to 25,
                "cover.scale_with_font" to true,
                "cover.shape" to "CIRCLE",
                "cover.sharpness" to 25,
                "cover.vinyl" to true,
                "island.always_on_top" to true,
                "island.auto_hide_on_hover" to false,
                "island.border.color.b" to 255,
                "island.border.color.g" to 255,
                "island.border.color.r" to 255,
                "island.border.color_mode" to "manual",
                "island.border.dissolve_band_percent" to 15,
                "island.border.enabled" to true,
                "island.border.mode" to "EDGE_DISSOLVE",
                "island.border.width_tenth_px" to 10,
                "island.enabled" to true,
                "island.hide_fullscreen" to true,
                "island.hide_paused" to false,
                "island.hit.cover" to false,
                "island.interactive" to false,
                "island.lock_position" to false,
                "island.right_slot.component" to "SPECTRUM",
                "island.right_slot.polar_outer_percent" to 100,
                "island.right_slot.radial_inner_percent" to 0,
                "island.right_slot.ring_inner_percent" to 55,
                "island.right_slot.spectrum_bars" to "5",
                "island.right_slot.spectrum_color" to "#84D8BC",
                "island.right_slot.spectrum_color_mode" to "cover",
                "layout.content_align" to "CENTER",
                "layout.fixed_width" to false,
                "layout.island.height.follow_font" to true,
                "layout.island.height.ratio_percent" to 125,
                "layout.line_gap_px" to 0,
                "layout.lines" to "DUAL",
                "layout.max_width" to 1400,
                "layout.noLyrics.hideWhenInstrumental" to false,
                "layout.noLyrics.mode" to "PLACEHOLDER",
                "layout.noLyrics.showAlbum" to false,
                "layout.noLyrics.showTrackTitle" to true,
                "layout.noLyrics.titleInTranslation" to false,
                "layout.translation.enabled" to true,
                "layout.translation.position" to "BELOW",
                "layout.translation.scale_percent" to 100,
                "lyric.animation_style" to "FADE",
                "lyric.offset_ms" to 0,
                "main_lyric.color_mode" to "manual",
                "main_lyric.fill.color.b" to 255,
                "main_lyric.fill.color.g" to 255,
                "main_lyric.fill.color.lightness" to 100,
                "main_lyric.fill.color.r" to 255,
                "main_lyric.fill.unsung.b" to 138,
                "main_lyric.fill.unsung.g" to 129,
                "main_lyric.fill.unsung.lightness" to 100,
                "main_lyric.fill.unsung.r" to 126,
                "main_lyric.font.family" to "@host:MiSans",
                "main_lyric.font.size" to 22,
                "main_lyric.font.weight" to "NORMAL",
                "main_lyric.karaoke.mode" to "SMOOTH",
                "main_lyric.letter_spacing_tenth_px" to 0,
                "main_lyric.outline.color.b" to 45,
                "main_lyric.outline.color.g" to 45,
                "main_lyric.outline.color.r" to 45,
                "main_lyric.outline.enabled" to true,
                "main_lyric.outline.follow_font_weight" to false,
                "main_lyric.outline.weight_link_percent" to 50,
                "main_lyric.outline.width_tenth_px" to 10,
                "main_lyric.translation.color.b" to 195,
                "main_lyric.translation.color.g" to 182,
                "main_lyric.translation.color.lightness" to 100,
                "main_lyric.translation.color.r" to 177,
                "main_lyric.translation.color_mode" to "manual",
                "material.backdrop.blur_radius" to 0,
                "material.backdrop.dispersion" to 35,
                "material.backdrop.refraction_amount" to 32,
                "material.backdrop.refraction_height" to 16,
                "material.backdrop.saturation" to 150,
                "material.content.blur_radius" to 0,
                "material.fill.color_end" to "#2A2E38",
                "material.fill.flow_speed" to 20,
                "material.fill.gradient_angle_deg" to 90,
                "material.fill.gradient_style" to "CAMO",
                "material.fill.gradient_tint_percent" to 30,
                "material.highlight.strength" to 0,
                "material.liquid.depth_effect" to false,
                "material.liquid.glare_strength" to 0,
                "material.liquid.shadow_strength" to 0,
                "material.liquid.surface_alpha" to 0,
                "material.noise.amplitude" to 0,
                "material.quality" to "FULL",
                "material.render.gpu_backend" to false,
                "menu.opacity_pct" to 85,
                "menu.submenu_close_delay_ms" to 250,
                "menu.submenu_open_delay_ms" to 180,
                "settings.auto_ungate_on_touch" to true,
                "shape.corner_n" to 4,
                "shape.corner_radius" to 60,
                "shape.corner_style" to "ARC",
                "shape.type" to "PILL",
                "spectrum.anchor" to "BOTTOM",
                "spectrum.bar_pitch_mode" to "FIXED",
                "spectrum.bar_pitch_tenth_px" to 70,
                "spectrum.bar_shape" to "ROUND",
                "spectrum.bounce_pct" to 100,
                "spectrum.color" to "#84D8BC",
                "spectrum.color_mode" to "manual",
                "spectrum.enabled" to false,
                "spectrum.fade_top" to true,
                "spectrum.height_percent" to 100,
                "spectrum.opacity_pct" to 75,
                "spectrum.progress_tint" to false,
                "spectrum.progress_tint_alpha" to 100,
                "spectrum.secondary.mode" to "OFF",
                "spectrum.secondary.opacity_percent" to 35,
                "spectrum.symmetric" to false,
                "translation.letter_spacing.follow_main" to true,
                "translation.outline.color.b" to 255,
                "translation.outline.color.g" to 255,
                "translation.outline.color.r" to 255,
                "translation.outline.enabled" to true,
                "translation.outline.follow_weight" to false,
                "translation.outline.sync_with_main" to true,
                "translation.outline.width_tenth_px" to 10,
            ),
        ),
    )
}
