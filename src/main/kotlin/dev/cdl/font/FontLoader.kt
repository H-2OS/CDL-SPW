// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 一个已成功加载的字体文件条目。
 *
 * ## 为什么族名必须从 `font.getFamily()` 取、**不能从文件名猜**
 *
 * 这是本项目实际踩过的坑，写在这里避免后人重犯：
 *
 * - 文件名与族名**没有强制对应关系**。`msyh.ttc` 的族名是 `Microsoft YaHei UI`
 *   （以及 `Microsoft YaHei` / `微软雅黑`，见 [FontLoader] 关于 `.ttc` 多字体的说明），
 *   只看文件名根本不可能把它归到 `Microsoft YaHei UI` 这个设置项取值上。
 * - 字体族名里**可以含空格、逗号、括号**（如 `Noto Sans CJK SC`、`Source Han Sans,
 *   Regular`），而文件名出于兼容性通常会把这类字符换成下划线或短横线 ——
 *   于是「从文件名反推族名」还要再猜一遍替换规则，属于**猜测套猜测**。
 * - 同一份字体在不同厂商的打包里文件名各不相同（`msyh.ttc` / `MSYH.TTC` /
 *   `微软雅黑.ttc`），但族名是**字体文件内部自带的权威标识**。
 *
 * 结论：族名只能由字体解析器给出。`Font.getFamily()` 返回的是 `Font` 对象内
 * `familyName` 字段，该字段由 JDK 的字体解析器从字体表的 `name` 表中读出，
 * 是**唯一可信来源**。文件名仅用于 [source]（错误提示、去重、日志），
 * **绝不参与族名计算**。
 *
 * @param familyName 族名（取自 `font.getFamily()`）。设置项 `main_lyric.font.family`
 *   存的就是这个值；用户按族名选字体，而不是按文件名。
 * @param font 已加载的 [Font]。注意：它的**大小**是 `createFont` 给的字号（1 pt），
 *   真正渲染用的字号由设置项 `main_lyric.font.size` 通过 `font.deriveFont(size)`
 *   派生 —— 所以这里不要把它当成"可以直接画的字体"。
 * @param source 来源文件绝对路径。仅用于日志与「同一族名多来源」时的定位。
 */
data class LoadedFont(
    val familyName: String,
    val font: Font,
    val source: Path,
)

/**
 * 从**用户指定的目录**加载字体文件，供词岛渲染使用。
 *
 * ## 为什么走"读普通文件"这条路（而不是随包分发字体）
 *
 * 项目纪律与法务约束共同决定了这个设计：
 *
 * 1. **插件包内不得含字体文件**。这曾是**法务约束**：本项目一度把 MiSans 作为默认字体，
 *    而《MiSans 字体知识产权许可协议》第 2 条第 (3) 款**明文禁止再分发字体文件**
 *    （豁免只覆盖"用该字体创作的作品"）。⚠️ **2026-09-22：随包分发已整体取消**
 *    （用户拍板「取消自带字体包，调用自带字体包的路线转到在本机选择的字体上」，
 *    见 `font/FontDefaults.kt` 与 `THIRD_PARTY_NOTICES.md` §9）——
 *    但**本模块的设计不受影响**：它本来就只提供"从磁盘加载"的通道，
 *    「包内不放字体」这条纪律现在由**事实**（目录已删）而非只由纪律保证。
 * 2. **SPW 没有沙箱**。`java.io.File` / `java.nio.file.Files` 是 JVM 能力，
 *    不属 SPW API 表面，宿主在 `[JavaOptions]` 里也没有 `-Djava.security.manager`，
 *    故宿主无法禁止插件读普通文件。读用户字体目录与读音频文件同属"读普通文件"，
 *    不触碰「禁止反射 / 进程注入 / 读写宿主私有文件」这条边界。
 * 3. **用户的字体由用户自己拥有**。用户机器上（如 `C:\Windows\Fonts` 或自己的
 *    素材目录）已装的字体，加载它不产生任何再分发。
 *
 * ## 线程
 *
 * 本类**不做线程切换**，也不假设在哪个线程被调用。目录扫描 + `Font.createFont`
 * 是纯 CPU/IO 操作，**不应在 EDT 上做**（扫一个装了几百个字体文件的目录会有可感卡顿）。
 * 调用方负责把它放到后台线程；[FontLoader] 自身无共享可变状态，是线程安全的。
 */
class FontLoader {

    /**
     * 扫描 [directory] 下的字体文件并加载。
     *
     * ## 契约
     *
     * - **不抛异常**。目录不存在、无读权限、不是目录、中途 IO 失败，一律返回已成功
     *   加载的部分（可能是空列表）。这是刻意的：字体目录配错、U 盘拔掉、
     *   网络盘断连都是真实的用户环境，**不该让词岛连带崩掉**。
     * - **单个文件失败不中断整个扫描**。见下方逐文件 `runCatching` 的理由。
     * - 只扫描**当前目录**，不递归 —— 见 [MAX_FILES] 的说明。
     *
     * ## 为什么逐文件 `runCatching` 而不是整段 try
     *
     * `Font.createFont` 对损坏文件、伪装成字体的非字体文件（改后缀的图片、
     * 半个文件）、以及 JDK 解析器不认识的变体都会抛 `FontFormatException` 或
     * `IOException`。而**一个坏文件不该毁掉整批字体**：用户的字体目录里混进
     * 一个坏文件是很常见的（下载不全、磁盘错误），若整段 try 包裹，用户会得到
     * "加载了 0 个字体"这样一个与真实原因（只有 1 个文件坏了）完全不成比例的后果，
     * 而且极难排查。逐文件隔离把故障半径限制在单个文件。
     *
     * @param directory 要扫描的目录（用户指定）。可为不存在/无权限的路径，不会抛异常。
     * @return 成功加载的条目；**失败的文件不会出现在返回值里**。
     *   调用方若需要知道"哪些文件失败了"，请看 stderr 日志或改用 [loadFromReporting]。
     */
    fun loadFrom(directory: Path): List<LoadedFont> = loadFromReporting(directory).loaded

    /**
     * 与 [loadFrom] 相同的扫描逻辑，但**额外返回失败清单**。
     *
     * ## 为什么单独开这个方法而不是让 [loadFrom] 返回一个带失败列表的结果对象
     *
     * 交付契约要求 `loadFrom` 的返回类型是 `List<LoadedFont>`（见任务书 A 节），
     * 而失败清单只在**设置窗口的字体导入反馈**这类场景才用得上（"3 个文件解析失败"）。
     * 把失败清单塞进 `loadFrom` 的返回类型会让最常见的调用点（只要字体列表）
     * 多写一层解构。故 `loadFrom` 保持契约要求的窄签名，另开本方法给需要细节的调用方。
     *
     * @return [ScanResult]，含成功列表与失败清单（失败项含原因，供 UI 提示）。
     */
    fun loadFromReporting(directory: Path): ScanResult {
        // 目录本身不可用（不存在 / 不是目录 / 无权限）：属于"配置指向了错地方"，
        // 返回空结果并记一条日志。**不抛异常** —— 调用方（组合根）已用 runCatching
        // 兜底，但那是防线而非借口，本方法自己就应当是可安全调用的。
        val entries = runCatching {
            Files.newDirectoryStream(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        }.getOrElse {
            System.err.println("[CDL] 字体目录不可用（$directory）：$it")
            return ScanResult(emptyList(), emptyList())
        }

        val loaded = ArrayList<LoadedFont>()
        val failed = ArrayList<LoadFailure>()

        // 上限：只取前 MAX_FILES 个候选文件。为什么要有上限 ——
        // 用户可能误把整个 C:\Windows\Fonts（数千个文件）或一个素材大盘的根目录
        // 填进来。无上限的扫描会在后台线程上跑很久，且会往 LoadedFont 列表里
        // 塞进几百个 [Font] 对象（每个都持有字体的字形解析结构，堆占用很可观）。
        // 取 2000：足以覆盖 Windows 自带字体全量 + 用户自装字体，
        // 超出这个数量说明目录选错了，此时"截断"比"跑爆内存"更合适。
        var examined = 0
        for (path in entries) {
            if (examined >= MAX_FILES) {
                System.err.println(
                    "[CDL] 字体目录文件数超过上限 $MAX_FILES，已截断扫描：$directory"
                )
                break
            }
            if (!isFontCandidate(path)) continue
            examined++

            // 逐文件隔离：见 loadFrom 的 KDoc。失败只记录、不中断。
            runCatching { loadOne(path) }
                .onSuccess { loaded += it }
                .onFailure { failed += LoadFailure(path, it) }
        }

        if (failed.isNotEmpty()) {
            System.err.println(
                "[CDL] 字体加载：${loaded.size} 个成功，${failed.size} 个失败（已跳过）"
            )
            failed.forEach { System.err.println("  · ${it.source}：${it.reason}") }
        }

        // ── 诊断：扫了文件却一个字体都没出，且不是因为解析失败 ──
        //
        // 这是**最容易让用户困惑的一种失败**，必须显式说明而不是静默返回空。
        // 实测遇到过的真实情形（MiSans Global 字体包）：
        //   用户选的是 `MiSans/MiSans/`，但字体实际在 `MiSans/MiSans/ttf/` 与 `.../otf/` 下。
        //   本类**刻意不递归扫描**（理由见 loadFromReporting 的 KDoc：字体目录可能上万文件），
        //   于是返回 0 个字体、0 个失败 —— 用户看到的是"我放了一堆字体，一个都没加载"，
        //   而界面上没有任何线索指向"层级不对"。
        //
        // 判据用 `entries.isNotEmpty() && examined == 0`：目录里有文件，但没有一个
        // 是候选字体 —— 这精确对应"选错层级"（子目录里有字体，但本层全是目录/其它文件），
        // 而不会误报"目录真的是空的"（那种情况 entries 为空，另有其语义）。
        if (loaded.isEmpty() && failed.isEmpty() && entries.isNotEmpty() && examined == 0) {
            // 进一步：探测**一级子目录**里有没有字体，好在提示里直接告诉用户该选哪个。
            // 只探一级 —— 与"不递归扫描"保持同一克制，避免为了做提示反而去遍历整棵树。
            val childWithFonts = runCatching {
                Files.list(directory).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .filter { child ->
                            Files.list(child).use { inner ->
                                inner.anyMatch { Files.isRegularFile(it) && isFontCandidate(it) }
                            }
                        }
                        .map { it.fileName.toString() }
                        .toList()
                }
            }.getOrNull().orEmpty()

            System.err.println(
                "[CDL] 字体目录里没有字体文件（已检查 ${entries.size} 个条目）：$directory\n" +
                    if (childWithFonts.isEmpty()) {
                        "  → 请选择放着 .ttf / .otf / .ttc 的目录。"
                    } else {
                        "  → 字体可能在子目录里：${childWithFonts.joinToString("、")}\n" +
                            "     请**直接选择那个子目录**。本插件刻意不递归扫描" +
                            "（避免误选到上万文件的目录时拖慢启动）。"
                    }
            )
        }
        return ScanResult(loaded, failed)
    }

    /**
     * 从**单个用户选中的文件**加载字体 —— 「从电脑中选择字体包」这条来源的入口。
     *
     * ## 与 [loadFrom] 的分工（为什么单独开一个方法）
     *
     * [loadFrom] 接收**目录**、按扩展名粗筛、受 [MAX_FILES] 上限约束 ——
     * 那套逻辑是为"扫一个字体目录"设计的。而"用户在文件对话框里选中一个（或几个）
     * 文件"这条路径**根本不需要粗筛与上限**：
     *
     * - **不需要扩展名粗筛**：用户是在原生文件对话框里、按我们给的过滤器选的，
     *   他已经表达了"这就是字体文件"的意图。此时再按扩展名挡一道，
     *   唯一的效果是**挡住一个用户明确想加载的文件**（例如 `.woff` 改名成 `.ttf`
     *   之外的情况、或某些厂商用 `.bin` 打包）—— 那是把用户的显式意图
     *   用一个我们猜的规则否决掉。
     * - **不需要 [MAX_FILES] 上限**：用户不会在文件对话框里一次选中 2000 个文件
     *   （真选了，逐文件隔离也会把坏的挑出来，代价只是慢，不是崩）。
     *
     * 共用 [loadOne] 的实际解析（`.ttc` 展开、族名取自 `name` 表），
     * 但不共用外层扫描 —— 这是**同一底层能力、两种入口语义**。
     *
     * ## 契约
     *
     * **不抛异常**。文件不存在、是目录、无权限、不是字体格式，一律计入
     * [ScanResult.failed]（或对"根本不是常规文件"的输入直接返回失败项）。
     * 逐项隔离，一个坏文件不影响另一个。
     *
     * ## 线程
     *
     * 同 [loadFrom]：**CPU/IO 操作，不得在 EDT 上调用**。
     *
     * @param file 用户选中的文件。可为不存在/是目录的路径，不会抛异常。
     * @return [ScanResult] —— `loaded` 为该文件展开出的全部字体（`.ttc` 会有多个），
     *   `failed` 为失败原因（若不是常规文件则恰好一项）。
     */
    fun loadFileReporting(file: Path): ScanResult {
        if (!Files.isRegularFile(file)) {
            // 把"用户选了个目录 / 文件已消失"归为**失败项而非异常**：
            // 调用方（设置窗口）要能据此给出一句人话提示（"该文件不存在或不是文件"），
            // 而不是让异常冒泡到 EDT 把宿主的事件泵打挂（项目铁律）。
            val reason = IllegalStateException("不是常规文件（可能是目录，或文件已被移动/删除）")
            System.err.println("[CDL] 字体包不可用：$file —— ${reason.message}")
            return ScanResult(emptyList(), listOf(LoadFailure(file, reason)))
        }
        return runCatching { loadOne(file) }
            .fold(
                onSuccess = { ScanResult(it, emptyList()) },
                onFailure = { error ->
                    System.err.println("[CDL] 字体包解析失败：$file —— $error")
                    ScanResult(emptyList(), listOf(LoadFailure(file, error)))
                },
            )
    }

    /**
     * 从**单个文件**加载字体。文件是 `.ttc`（字体集合）时会展开**全部**子字体。
     *
     * ## 为什么 `.ttc` 走 `Font.createFonts` 而不是 `Font.createFont`
     *
     * 任务书已明确要求，这里记录**原因**：`.ttc`（TrueType Collection）是**一个文件装多份字体**。
     * `Font.createFont(Font.TRUETYPE_FONT, file)` **只返回集合里的第一个**字体，
     * 其余子字体**静默丢失**。而 Windows 的 CJK 主力字体恰恰都是 `.ttc`：
     * `msyh.ttc` 内含 `Microsoft YaHei` / `Microsoft YaHei UI` / `微软雅黑`，
     * `simsun.ttc`、`msjh.ttc` 同理。若用 `createFont`，用户装了一个 ttc
     * 却只看到一个族名，且很可能是他**不想要的那个**。
     * `Font.createFonts` 返回集合内全部字体，这才是 `.ttc` 的正确读法。
     *
     * ⚠️ **不确定处（如实声明，未在真机逐一验证）**：`Font.createFonts` 对 `.ttc`
     * 返回的数组**顺序**是否与集合内的字体顺序一致、以及是否**必然返回全部子字体**
     * （有些 JDK 实现对集合的支持是"能读到几个算几个"），本实现**不做假设** ——
     * 它把返回值里每一个都当作独立字体登记，顺序不参与任何逻辑。
     * 若某 JDK 版本只返回第一个，表现是"少几个族名"，而不是错误结果。
     *
     * ## 为什么非 `.ttc` 也统一走 `createFonts`
     *
     * 对单字体文件，`createFonts` 返回只含一个元素的数组（这是它的定义行为），
     * 与 `createFont` 等价。统一走一条路径，就不必维护"按后缀分派两条解析路径"
     * —— 后缀与实际内容不符（改名的 `.otf`）时，单路径不会走岔。
     *
     * @return 该文件展开出的全部字体条目（可能为空 —— 见下方对空返回的处理）。
     * @throws Exception 解析失败时抛出（由调用方的 `runCatching` 捕获并记入失败清单）。
     */
    private fun loadOne(path: Path): List<LoadedFont> {
        // 显式判空：`Font.createFonts` 对无法识别的文件可能返回**空数组**而非抛异常。
        // 若不处理，这个文件会"成功加载 0 个字体"并被静默忽略 —— 结果上没错，
        // 但排查时看不到它，故这里把它归为失败以便出现在失败清单里。
        val fonts = Font.createFonts(path.toFile())
        if (fonts.isEmpty()) error("字体解析器未返回任何字体（文件可能损坏或非字体格式）")

        return fonts.map { font ->
            LoadedFont(
                // 族名取自 Font 对象，**不从文件名猜** —— 理由见 LoadedFont 的 KDoc。
                familyName = font.getFamily(),
                font = font,
                source = path,
            )
        }
    }

    /**
     * 判断文件是否值得尝试解析（按扩展名粗筛）。
     *
     * 为什么按扩展名先筛、而不是把所有文件都丢给 `Font.createFonts` 试一遍：
     * 字体目录里常混有 `README.txt`、`.DS_Store`、几百 MB 的安装包等无关文件。
     * 逐个大文件去解析会白读大量字节并产生噪声失败。扩展名粗筛是**廉价的预过滤**，
     * 它**不追求完备**（漏掉一个罕见后缀只是"没加载"，不会有错误结果），
     * 但能挡掉绝大多数无关文件。真正的正确性由解析器保证 —— 改名成 `.ttf`
     * 的图片仍会走到解析器并失败，被计入失败清单，不会变成假字体。
     */
    private fun isFontCandidate(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase(Locale.ROOT) ?: return false
        return FONT_EXTENSIONS.any { name.endsWith(it) }
    }

    private companion object {
        /**
         * 认作候选字体的扩展名。小写比较（见 [isFontCandidate]）。
         *
         * `ttc` / `otc` 是字体**集合**容器，`ttf` / `otf` 是单体字体，
         * `fon` 是 Windows 的位图字体（`.fon` 通常无法被 `createFonts` 解析，
         * 保留它只是为了"尝试一下"，失败会被正常计入清单，不产生假结果）。
         */
        val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".ttc", ".otc", ".fon")

        /** 单次扫描的文件数上限。理由见 [loadFromReporting]。 */
        const val MAX_FILES = 2000
    }

    /**
     * 扫描结果。见 [loadFromReporting]。
     *
     * @param loaded 成功加载的字体（[loadFrom] 返回的就是它）
     * @param failed 失败的文件与原因（供设置窗口提示"哪几个文件没读进来"）
     */
    data class ScanResult(
        val loaded: List<LoadedFont>,
        val failed: List<LoadFailure>,
    )

    /**
     * 一个加载失败的字体文件。
     *
     * @param source 失败的文件路径
     * @param reason 失败原因（异常）。**保留异常对象而非字符串** —— 调用方可能想
     *   按类型分支（如"只是格式不支持"vs"权限不足"给不同提示），转成字符串就丢了信息。
     */
    data class LoadFailure(
        val source: Path,
        val reason: Throwable,
    )

    /**
     * 把已加载的字体登记进 `GraphicsEnvironment`，让 **Swing 组件也能按族名选到它们**。
     *
     * ## 为什么必须单独做这一步（而不是"加载完就能用"）
     *
     * `Font.createFont` 返回的 [Font] 对象**只对它自己被使用时有效**：
     * 你拿这个对象调用 `g.setFont(it)` 是能画出字的，但 `new Font("某某字体", ...)`
     * 或 `JLabel.setFont(new Font(family, ...))` 会走 `GraphicsEnvironment` 的
     * **已注册字体族表**去查族名 —— 表里没有这个族名，`new Font` 就会
     * **静默回退**到 `Dialog` 逻辑字体，用户看到的是"选了字体但没生效"。
     *
     * 设置窗口的字体下拉框、预览标签都属于后者（它们按**族名字符串**工作），
     * 故必须 `registerFont` 把族名补进全局表。
     *
     * ## 为什么接收 `GraphicsEnvironment` 参数而不是在内部取单例
     *
     * `GraphicsEnvironment.getLocalGraphicsEnvironment()` 在**无头环境**下会抛
     * `HeadlessException`。把它作为参数传入，调用方（组合根）就能在**一个明确的
     * 边界**上处理"无图形环境"这件事（降级为不登记），而不是让本类在内部
     * 藏一个可能抛异常的隐式依赖。这符合本项目"可测 + 降级明确"的取向。
     *
     * ## 契约
     * **不抛异常**：单个 `registerFont` 失败（某些 JDK 拒收缺字形的字体）只记日志，
     * 不影响其余字体，也不影响调用方。
     *
     * @param environment 目标图形环境（通常 `GraphicsEnvironment.getLocalGraphicsEnvironment()`）
     * @param fonts 已加载的字体（[loadFrom] 的返回值）
     */
    fun registerInto(environment: GraphicsEnvironment, fonts: List<LoadedFont>) {
        for (loaded in fonts) {
            // 逐项隔离：registerFont 在某些平台上会因字体与系统冲突而抛
            // IllegalArgumentException。一个失败不该连累其余字体。
            runCatching { environment.registerFont(loaded.font) }
                .onFailure {
                    System.err.println(
                        "[CDL] 字体登记失败（${loaded.familyName} · ${loaded.source}）：$it"
                    )
                }
        }
    }
}
