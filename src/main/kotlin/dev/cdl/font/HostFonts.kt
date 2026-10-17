// SPDX-License-Identifier: AGPL-3.0-only
//
// 超级岛 · 宿主内置字体解析器
//
// ## 这个文件解决什么问题
//
// 用户诉求：「**用宿主同款字体**」。宿主 Salt Player for Windows 打包了**全量 MiSans**
// （`recon/64` 实测 7,924,400 B，中文/日文齐全），而插件默认是 `Microsoft YaHei UI`
// ⇒ **字面不同**。本对象让插件能**直接读宿主那一份**，从而：
//   ① 拿到与宿主 UI **逐像素同源**的中文观感；
//   ② **不打包、不复制任何字体文件**（插件包体积不变）⇒ 绕开 `recon/44` 的许可未决
//      （不新增分发行为：读的是宿主**自己已经分发**的那一份）。
//
// ## 设计要点（照 `recon/66` §0.1–§2.2，逐条都有实测依据）
//
// ### 一、哨兵值 `@host:MiSans` —— 为什么不直接用族名
//
// 设置项 `main_lyric.font.family` 存的是**族名字符串**。而"宿主内置的 MiSans"与
// "用户自己装的 MiSans" **度量不同**（`recon/65` §3.3 实测拉丁宽度差 1.9%）。
// 若都用 `MiSans` 表达，两者**无法区分来源** ⇒「选了没变化 / 变化莫名其妙」会变成常态。
//
// ⇒ 约定：**前缀 `@host:` 保留给宿主内置字体**，不写入系统族表、不参与
//   `availableFontFamilyNames`。`@` 在 Windows 族名里几乎不可能出现（它是 `@font-face`
//   语义的符号），冲突概率极低，且**人读得懂**（配置文件里 `"@host:MiSans"` 自解释）。
//
// ### 二、★★ **绝不调 `GraphicsEnvironment.registerFont`**（本文件最重要的一条）
//
// `recon/65` C2 的担忧，**本机 2026-09-29 已实测复现**：
//
// ```
//   本机是否已有族「MiSans」: true
//   registerFont 返回: false（★ 静默失败）
// ```
//
// ⇒ 若走 `registerFont` + `Font("MiSans", …)`，用户装了 MiSans 时会**静默地**拿到
//   **系统那份**，而不是宿主那份 —— 与用户的选择不符，且**不报错**。
// ⇒ 故本对象**持对象**（返回 `Font` 实例，由调用方 `deriveFont` 到目标字号），
//   **全程不碰全局族表**。这与 `recon/44` §2.1「只按需解析、从不写全局族表」**同源**。
//
// ### 三、永不抛、失败可诊断（G4）
//
// 宿主缺失/换代/条目改名都**不能**让词岛画不出来 ⇒ 所有失败路径**返回 `null`**，
// 调用方 `?: Font(family, …)` 自然回退到既有行为。
// ★ 但**不能静默**：失败原因记在 [lastFailureReason] 并打一条 `System.err`
//   （项目惯例，`PluginLog` 会接管 ⇒ 落到 `super-island.log`）。
//
// ### 四、★ 耗时实测 40.5 ms ⇒ **必须在后台预热**
//
// `recon/65` C1 估 130–420 ms；**本机实测 p50 = 40.5 ms**（37.2–42.8，7 次）。
// 比预期快 3–10 倍，但 **40 ms 在 EDT 上仍会掉 2–3 帧**（16.667 ms 网格）
// ⇒ [warmUpAsync] 提供一次性后台预热；未预热时 [resolveOrNull] 仍能工作（只是慢一次）。
//
// ## 数据源（`recon/64` 实测 + 本机 2026-09-29 复测）
//
// 宿主把 Compose Resources 打进了 `app/ffmpeg-x64.dll` —— ★ **那个文件其实是 jar**
// （magic = `PK`，71,956,779 B，内含 **35 个**字体条目）。目标条目：
//
// ```
// composeResources/voxzen.composeapp.generated.resources/font/misans.ttf
// ```
//
// ⇒ ★ 这个"伪装文件名"是**对宿主目录布局的运行时依赖**（`recon/64` §10 的 **R-5**）：
//   宿主换代可能变。故 [locateHostJar] 做**多候选探测**（按 magic 认，不按文件名认）。

package dev.cdl.font

import java.awt.Font
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 宿主内置字体的**解析器**（旁路，不改变既有行为）。
 *
 * 用法：**先问解析器，问不到再回原路** —— 这一个"或"字就是全部的接线方式：
 * ```
 * val base = HostFonts.resolveOrNull(family)?.deriveFont(size.toFloat())
 *     ?: Font(family, Font.PLAIN, size)
 * ```
 *
 * @see dev.cdl.render.fontWithLetterSpacing 渲染层唯一漏斗（4 个调用点共用）
 */
object HostFonts {

    /** 哨兵前缀：**保留给宿主内置字体**，不写入系统族表。 */
    const val PREFIX: String = "@host:"

    /** 宿主内置 MiSans 的哨兵值（**向后兼容**：L3 之前它只支持这一个）。 */
    const val MI_SANS: String = PREFIX + "MiSans"

    /**
     * 全部哨兵（供字体选择器枚举、供探针穷举）。
     *
     * ★★ 2026-09-29（用户指令「把从宿主复用字体这个做完」= `recon/65` **路线 ②**）：
     * 从"只有 MiSans 一个"扩展到「**宿主 jar 内所有字体族**」——
     * ★ 这同时解决了「**减细**」：细字重（`Inter Light` / `Inter Thin`）在 JDK 里是
     * **独立族名**（实测：`TextAttribute.WEIGHT < 1.0` 无效、`Font.style` 无"更细"位），
     * 故**选到那个族名 = 得到细字重** ✓
     *
     * ⚠️ **未预热时只有 [MI_SANS]** —— 因为枚举要扫 35 个字体（**≈1.4 s**），
     * 只能在 [warmUpAsync] 的后台线程里做（见 [resolveOrNull] 的说明）。
     *
     * ★ 2026-09-29（随 B1 修复）：**排序改为「族根相邻 + 常规在斜体前」** ——
     *   原来用 `.sorted()`（纯字典序），斜体条目会**散开**到字母序各处
     *   （`Inter` < `Inter Black` < `Inter Black Italic` < `Inter ExtraBold` …）⇒
     *   用户想给某个族挑斜体时**要在列表里跳很远**，且 `Inter Italic` 与 `Inter`
     *   的邻近关系丢失。改为按 `(族根, 是否斜体, 原名)` 三元组排序 ⇒
     *   `Inter` → `Inter Italic` → `Inter Black` → `Inter Black Italic` → …
     */
    val SENTINELS: List<String>
        get() {
            val rest = cachedFonts.orEmpty().keys
                .filter { it != "MiSans" }
                .sortedWith(hostFamilyOrder)
                .map { PREFIX + it }
            return listOf(MI_SANS) + rest
        }

    /**
     * 宿主族名的**展示顺序**：先按"族根"（去掉斜体后缀）排，同族内**常规在前、斜体在后**。
     *
     * ★ 抽出来是为了**可被探针断言**（`SENTINELS` 是 getter，探针能读；
     *   但"排序规则"本身若内联在 getter 里就无法单独验）。
     */
    internal val hostFamilyOrder: Comparator<String> = Comparator { a, b ->
        val rootA = a.removeSuffix(ITALIC_SUFFIX)
        val rootB = b.removeSuffix(ITALIC_SUFFIX)
        val byRoot = rootA.compareTo(rootB)
        if (byRoot != 0) byRoot
        else {
            val italicA = a.endsWith(ITALIC_SUFFIX)
            val italicB = b.endsWith(ITALIC_SUFFIX)
            if (italicA != italicB) if (italicA) 1 else -1 else a.compareTo(b)
        }
    }

    /** 宿主 jar 的候选文件名（按 `recon/64`：它是伪装成 dll 的 jar）。 */
    private val JAR_NAME_CANDIDATES: List<String> =
        listOf("ffmpeg-x64.dll", "app/ffmpeg-x64.dll")

    /** 字体条目的后缀（`.ttf` / `.otf`）—— ★ 按**内容**认，不按文件名猜族名。 */
    private val FONT_SUFFIXES: List<String> = listOf(".ttf", ".otf")

    /**
     * 斜体条目的**键名后缀**（`Inter` + ` Italic` ⇒ `Inter Italic`）。
     *
     * ★ 为什么用后缀而不是另起命名空间：
     *   ① 与 `Inter Light` / `Inter Thin` 这类**既有档位族名**同一形态
     *      （宿主自己的命名就是"族根 + 空格 + 档位词"）⇒ 用户读得懂、排序也相邻；
     *   ② 哨兵值随之变成 `@host:Inter Italic`，与 `@host:Inter Light` 完全同构
     *      ⇒ **不需要改任何消费点**（`resolveOrNull` 只是按名字查表）。
     * ★ 冲突概率：实测宿主 35 个条目里**没有任何族名叫 `X Italic`**
     *   （`Inter` 的斜体字形族名仍是 `Inter`，靠本文件区分）⇒ 零冲突。
     */
    const val ITALIC_SUFFIX: String = " Italic"

    // ── 缓存 ────────────────────────────────────────────────────────────
    // ★ 三份：族名→Font（主缓存）· 失败原因 · 是否已扫过
    @Volatile
    private var cachedFonts: Map<String, Font>? = null

    @Volatile
    private var cachedFailure: String? = null

    /** 上次失败的原因（`null` = 没有失败过）。★ 供探针断言与日志，不参与渲染。 */
    val lastFailureReason: String? get() = cachedFailure

    /** 是否已扫描成功（★ 供探针断言"缓存生效"）。 */
    val isResolved: Boolean get() = cachedFonts != null

    /**
     * 族名（**含**斜体后缀）→ 该字形的元数据。
     *
     * ★ **仅供探针**：`java.awt.Font` 无法反查斜体位 / 字重（实测 `isItalic()` 恒 `false`）
     * ⇒ 扫描时顺手把 [FontMeta] 存下来，否则"常规档确实不是斜体"这条断言**没法验**
     * （只能验"键存在"，而那在修复前也成立 ⇒ 空护栏）。
     */
    @Volatile
    private var cachedMeta: Map<String, FontMeta>? = null

    /** **仅供探针**：读某个族名（含斜体后缀）的元数据。`null` = 未预热 / 读不到。 */
    fun metaForProbe(key: String): FontMeta? = cachedMeta?.get(key)

    /** **仅供探针**：全部元数据（键 = 与 [hostFamilies] 同一套键名）。 */
    fun metaAllForProbe(): Map<String, FontMeta> = cachedMeta.orEmpty()

    /**
     * 已枚举到的宿主族名（**不含** `@host:` 前缀）。★ 未预热 ⇒ 空列表。
     *
     * ★ 与 [SENTINELS] 用**同一个**排序器（[hostFamilyOrder]）—— 两者是同一份数据的
     *   两种视图，顺序不一致会让"界面上看到的次序"与"探针断言的次序"漂移。
     */
    val hostFamilies: List<String> get() = cachedFonts?.keys?.sortedWith(hostFamilyOrder).orEmpty()

    /**
     * 是否是宿主内置字体的**哨兵值**。
     *
     * ★ 只有哨兵值才走本解析器；普通族名（`Microsoft YaHei UI` / `MiSans` / …）
     *   **一律返回 `false`** ⇒ 调用方走原路 ⇒ **既有行为零改变**。
     */
    fun isSentinel(family: String?): Boolean =
        family != null && family.startsWith(PREFIX)

    /** 去掉 `@host:` 前缀，得到宿主 jar 内的**族名**（非哨兵值原样返回）。 */
    fun sentinelFamily(family: String): String =
        if (isSentinel(family)) family.removePrefix(PREFIX) else family

    /**
     * 把哨兵值转成**给人看的显示名**（字体选择器列表项）。
     *
     * 例：`@host:MiSans` → `宿主内置 MiSans`。
     * 非哨兵值**原样返回**（调用方不必先判）。
     *
     * ★ 2026-09-29（随 B1 修复）：斜体条目额外标注 ——
     *   `@host:Inter Italic` → `宿主内置 Inter（斜体）`。
     *   理由：`Inter Italic` 与 `Inter` 在列表里**只差一个词**，用户很容易选错；
     *   而"选错"的后果是**整个歌词变斜**（很显眼，但不一定立刻归因到字体项）。
     *   ★ 判据用 [ITALIC_SUFFIX] 派生，**不手抄字面量**（纪律 9）。
     */
    fun displayName(family: String): String {
        if (!isSentinel(family)) return family
        val raw = sentinelFamily(family)
        return if (raw.endsWith(ITALIC_SUFFIX)) {
            "宿主内置 " + raw.removeSuffix(ITALIC_SUFFIX) + "（斜体）"
        } else {
            "宿主内置 $raw"
        }
    }

    /**
     * 解析哨兵值 → 宿主字体。
     *
     * @return 非哨兵值 / **未预热** / 宿主不可用 ⇒ **`null`**（调用方回退既有行为）。
     *   ★ **永不抛**。
     *
     * ## ★★ 为什么**不在这里同步扫描**（本设计最关键的一条）
     *
     * 宿主 jar 内有 **35 个**字体条目，每个 `Font.createFont` 实测 **≈40 ms**
     * ⇒ 全量枚举 **≈1.4 s**。而本函数会在**渲染路径（EDT）**被调用
     * ⇒ 若在这里同步扫描，**用户会看到 1.4 秒的冻结**。
     *
     * ⇒ ★ **扫描只由 [warmUpAsync] 在后台做**；未预热时本函数**直接返回 `null`**
     *   （调用方回退系统字体，**不阻塞、不卡顿**）。预热完成后命中缓存（≈0 ms）。
     */
    fun resolveOrNull(family: String?): Font? {
        if (!isSentinel(family)) return null
        // ★ 不同步扫描（见上面的说明）—— 未预热 ⇒ null ⇒ 调用方回退
        val name = sentinelFamily(family ?: return null)
        return cachedFonts?.get(name)
    }

    /**
     * **一次性后台预热**（`recon/66` G6）—— 枚举宿主 jar 内的**全部**字体族。
     *
     * ★ 实测：单个 `createFont` p50 = **40.5 ms**；**35 个 ≈ 1.4 s** ⇒ **必须在后台**。
     * ★ **幂等**：已扫描 / 已失败 ⇒ 直接返回。★ **不抛**。
     */
    fun warmUpAsync() {
        if (cachedFonts != null || cachedFailure != null) return
        val t = Thread({ runCatching { scanAll() } }, "cdl-hostfont")
        t.isDaemon = true
        t.start()
    }

    /** **仅供探针**：同步扫描一次（生产代码**不得**调用 —— 它会在 EDT 上花 1.4 s）。 */
    fun scanForProbe(): Map<String, Font> {
        scanAll()
        return cachedFonts.orEmpty()
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun scanAll() {
        if (cachedFonts != null || cachedFailure != null) return
        val jar = locateHostJar()
        if (jar == null) {
            fail("定位不到宿主 jar（skiko.library.path=${System.getProperty("skiko.library.path")}）")
            return
        }
        val out = LinkedHashMap<String, Font>()
        var seen = 0
        var bad = 0
        // ★★ 2026-09-29（修 B1）：先**全量收集**，最后再建表 —— 见下面 `pickEntry` 的说明。
        //   ⚠️ 必须**连字节一起存**：`java.awt.Font` 没有任何"导出原始字节"的 API ⇒
        //     斜体元数据只能在**扫描时**读（事后从 `Font` 反取是不可能的）。
        val collected = ArrayList<Triple<String, Font, ByteArray>>()
        runCatching {
            ZipFile(jar.toFile()).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val name = en.nextElement().name
                    val ln = name.lowercase()
                    if (FONT_SUFFIXES.none { ln.endsWith(it) }) continue
                    seen++
                    runCatching {
                        val bytes = zf.getInputStream(zf.getEntry(name)).use { it.readAllBytes() }
                        val f = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes))
                        // ★★ 族名**必须**从字体内部 `name` 表取（`Font.getFamily()`），
                        //    **绝不从文件名猜** —— 见 `font/FontLoader.kt` 的 KDoc（本项目踩过）。
                        //
                        // ★★ 2026-09-29（修 B1）：
                        //    ① 原记 `out.putIfAbsent(f.getFamily(), f)` —— **只按族名去重**，
                        //       于是同一族名下的多个字形（实测 `Inter` 有 4 个：
                        //       Regular / Italic / Bold / BoldItalic）**只剩最先遍历到的那个**。
                        //       ★ 而 zip 条目遍历顺序**不是**常规档优先 ⇒ 实测 15 个多字形族里
                        //       **8 个**被斜体顶替（`Inter` → `Inter-Italic.ttf`、
                        //       `JetBrains Mono` → `JetBrainsMono-Italic.ttf`、…）。
                        //       ⇒ 症状：用户选「宿主内置 Inter」拿到的是**斜体**，且**没有任何提示**。
                        //    ② 更麻烦的是它**不确定** —— 同一批文件里 `Inter Black` 选到常规档、
                        //       `Inter Thin` 选到斜体，**取决于遍历顺序**（`LinkedHashMap` 保留先到的）。
                        //    ⇒ 修法：登记前先读每个文件自己的 **斜体位**（`FontMeta.readFontMeta`，
                        //      `java.awt.Font.isItalic()` 对 `createFont` 产物**恒 false** ⇒ 不可用），
                        //      再按「**常规档优先**」建表；斜体档另立一个键（`族名 + " Italic"`）。
                        collected += Triple(f.getFamily(), f, bytes)
                    }.onFailure { bad++ }
                }
            }
        }.onFailure {
            fail("读宿主 jar 失败：${it.javaClass.simpleName}: ${it.message}")
            return
        }
        // ★ 键名在**这里**由元数据派生（`Font.getFamily()` 给不出斜体位）。
        //   ⚠️ 元数据读不到（返回 null）⇒ 按**常规档**处理 —— 这与修复前的行为一致，
        //     故"读不出元数据的畸形字体"不会因为本修复而**消失**（只会不参与斜体分档）。
        val italicFlags = BooleanArray(collected.size)
        val metas = ArrayList<FontMeta?>(collected.size)
        for (i in collected.indices) {
            val m = runCatching { readFontMeta(collected[i].third) }.getOrNull()
            metas += m
            italicFlags[i] = m?.italic ?: false
        }
        // ★ 键名 → 元数据（与 `out` 同一套键名规则），供探针断言"常规档确实不是斜体"。
        val metaOut = HashMap<String, FontMeta>()
        // ① 先放**常规**档（键 = 原族名，**与修复前同名** ⇒ 既有配置与断言零变化）
        for (i in collected.indices) {
            if (italicFlags[i]) continue
            val (family, font, _) = collected[i]
            out.putIfAbsent(family, font)
            metas[i]?.let { metaOut.putIfAbsent(family, it) }
        }
        // ② 再放**斜体**档（键 = 族名 + " Italic"）
        //    ★ 若同一族的常规档缺失（宿主只给了斜体），斜体仍能拿到 —— 键是 `X Italic`，
        //      **不会**顶替 `X`（`X` 键不存在 ⇒ 用户选不到，符合"不静默给错东西"）。
        for (i in collected.indices) {
            if (!italicFlags[i]) continue
            val (family, font, _) = collected[i]
            out.putIfAbsent(family + ITALIC_SUFFIX, font)
            metas[i]?.let { metaOut.putIfAbsent(family + ITALIC_SUFFIX, it) }
        }
        if (out.isEmpty()) {
            fail("宿主 jar 内没有可解析的字体（条目 $seen 个，全部失败）")
            return
        }
        cachedFonts = out
        cachedMeta = metaOut
        val italicCount = out.keys.count { it.endsWith(ITALIC_SUFFIX) }
        System.err.println(
            "[CDL] 宿主字体已就绪：${out.size} 个族（条目 $seen，失败 $bad，"
                + "其中斜体 $italicCount）"
                + " · 例：${out.keys.take(4).joinToString()}（来自 ${jar.fileName}）"
        )
    }

    /**
     * 定位宿主安装目录下的那个"jar"（★ **按 magic 认，不按文件名认**）。
     */
    private fun locateHostJar(): Path? {
        val roots = mutableListOf<Path>()
        // ① 主路径：宿主自己在 `.cfg` 里设的 `-Dskiko.library.path=$APPDIR`
        System.getProperty("skiko.library.path")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.takeIf { Files.isDirectory(it) }
            ?.let { roots.add(it) }
        // ② 兜底：`java.home` 的父目录（jpackage 布局里 java.home = <app>/runtime）
        runCatching {
            Path.of(System.getProperty("java.home") ?: "").parent?.let { roots.add(it) }
        }
        for (root in roots) {
            for (name in JAR_NAME_CANDIDATES) {
                val f = root.resolve(name)
                if (Files.isRegularFile(f) && looksLikeZip(f)) return f
            }
        }
        return null
    }

    /** 前两字节是不是 `PK`（Zip 的 magic）。★ 这是"它是 jar 不是 dll"的唯一可靠判据。 */
    private fun looksLikeZip(f: Path): Boolean = runCatching {
        Files.newInputStream(f).use { ins ->
            val h = ins.readNBytes(2)
            h.size == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun fail(reason: String) {
        cachedFailure = reason
        System.err.println("[CDL] 宿主字体不可用，已回退系统字体：$reason")
    }

    /**
     * **仅供探针**：清空缓存，让下一次扫描重新走一遍完整路径。
     * ★ 生产代码**不得**调用。
     */
    fun resetForProbe() {
        cachedFonts = null
        cachedMeta = null
        cachedFailure = null
    }
}
