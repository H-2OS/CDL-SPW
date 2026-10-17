// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.cover

import java.awt.image.BufferedImage
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * 专辑封面获取。对应设置项 `cover.enabled`（默认 `false`）。
 *
 * ## 数据来源（按优先级）
 *
 * 1. **音频文件内嵌图**：ID3v2 APIC 帧（MP3）/ FLAC PICTURE 块 / MP4 `covr` atom（M4A/AAC）
 * 2. **音频文件同目录的图片**：`cover.jpg` / `cover.png` / `folder.jpg` / `front.jpg`
 *
 * 内嵌图优先，因为它与**这一首歌**精确绑定；同目录图片只与**目录**绑定
 * （一个专辑目录共用一张图是常态，但目录里放单曲时那张图很可能根本不是这首歌的）。
 *
 * ## 为什么必须缓存
 *
 * 取封面要**读音频文件的前若干 MB 并解析**，而且渲染是**每帧**在跑的（33 ms 一帧）。
 * 若每帧都去读盘：
 *
 * - 一张 30 MB 的 FLAC，每秒要被完整读 30 次 —— 磁盘/SSD 带宽会被一个纯装饰性元素吃干；
 * - 歌词渲染的每帧预算只有 16.6 ms，而一次封面解码（含 JPEG 解码 + 缩放到目标尺寸）
 *   是**几十毫秒量级**，直接把帧率打到个位数；
 * - 播放列表里反复切歌时，同一首歌会被反复取用（用户来回切），浪费是重复的。
 *
 * 因此本类**必须**缓存。缓存的失效点是**曲目切换**（[coverFor] 的 `audioPath` 变了），
 * 而不是时间或帧号 —— 封面只在换歌时才会变。
 *
 * ## 为什么在后台线程读取
 *
 * `coverFor` 是**渲染路径**上的调用（每帧或换歌时问一次"封面呢"）。
 * 读盘 + 解码是几十毫秒的阻塞操作，若在 EDT 上做，会造成**可感的界面卡顿**
 * （词岛停在旧画面、设置窗口不响应点击）。故本类用一个单线程执行器串行地做
 * 后台读取，EDT 侧只做"取缓存 / 拿不到就返回 null 并触发一次后台加载"这个**无阻塞**动作。
 *
 * ## 契约：任何失败都返回 `null`，不抛异常
 *
 * 没有封面的歌**非常常见**（大部分单曲文件不带内嵌图，目录里也没有图片）。
 * 这**不是错误**，而是正常状态 —— 调用方（渲染层）应当把它画成"没有封面"，
 * 而不是处理一个异常。因此本类所有公开方法都不抛异常。
 */
class CoverArtProvider {

    /**
     * 封面缓存。**当前只缓存一首歌**（LRU 容量 1）—— 理由见类 KDoc 与 [MAX_CACHED]。
     *
     * ## 为什么容量 1 就够（而不是做一个多条目 LRU）
     *
     * 渲染路径**任何时刻只关心当前这一首歌**的封面。用户切歌 → 缓存失效 → 加载新封面。
     * 缓存 2 首的收益仅限于"用户在两首歌之间来回切"，而代价是多一份
     * **解码后的 `BufferedImage`** 常驻堆里 —— 一张 1000×1000 的 ARGB 图是 **4 MB**，
     * 两首就是 8 MB，纯粹为一个小概率交互模式白占。
     *
     * 容量 1 的实现也**避免了维护 LRU 链表**（移动节点、淘汰、加锁），
     * 而那份复杂度在容量 1 下完全无意义。这是"用最小的机制满足真实需求"的取舍。
     *
     * [MAX_CACHED] 写成常量而非硬编码 `1`，是为了让"容量确实是 1"这个决定
     * 在代码里**可见**（而不是藏在 `if (cachedPath != path) cached = null` 里 ——
     * 后者看起来像个没写完的缓存）。
     */
    private val lock = Any()

    @Volatile
    private var cachedPath: String = ""

    @Volatile
    private var cachedImage: BufferedImage? = null

    /**
     * 是否已经为 [cachedPath] 尝试过加载（无论成功还是失败）。
     *
     * ## 为什么必须区分"没加载过"与"加载过但失败"
     *
     * 这是本类最关键的一处设计。若只用 [cachedImage] 是否为 null 来判断
     * "要不要去加载"，那么**没有封面的歌会每帧都触发一次后台加载** ——
     * 因为加载"成功"的结果是 `null`，与"还没加载"无法区分。后果是：
     * 一个没有封面的歌在播放的每一帧都提交一个后台任务，后台线程永远在
     * 重复读同一个文件并失败，白白吃掉 CPU 与磁盘 IO。这是**真实的空转 bug**。
     *
     * 用这个布尔位把"已尝试"这一事实与"结果"分开记录，是修复它的唯一正确方式。
     */
    @Volatile
    private var loadAttempted: Boolean = false

    /**
     * **是否有一次装载正在途中** —— 防"同一首歌重复提交"。
     *
     * ## ★★ 2026-09-26 修：原先没有这个位，导致**每帧重复提交**
     *
     * `coverFor` 的调用方是**渲染层**（`IslandWindow:6341` 的 `coverImageFor` **每帧调一次**）。
     * 而原先的判据只有 `if (loadAttempted) return null` —— 它**只有在装载完成之后**才为真
     * ⇒ **装载"在途"的那整段窗口里，每一次调用都会再提交一个后台任务**。
     *
     * 实测（给生产代码加临时追踪，见 `recon/60` §2.5(b)）：**一次装载窗口内重复提交 18 次**。
     * 执行器是**单线程**的 ⇒ 队列被灌满 ⇒ **同一个文件被重读 N 次**、
     * 单线程忙在冗余任务上、并与 EDT 抢 CPU ⇒ **首次封面出现被拖到数秒**。
     *
     * ⚠️ [loadAttempted] 的 KDoc 早就警告过"没有封面的歌会**每帧都触发一次后台加载**
     * ——这是**真实的空转 bug**"，并用它修好了。**但它只堵住了"加载完成后"那一半**；
     * "加载**进行中**"那一半一直漏着（`loadAttempted` 在完成前恒为 `false`）。
     *
     * ⇒ 本字段补上另一半：**check-then-set 必须在 [lock] 内**，否则两个并发调用会都看到 `false`。
     */
    @Volatile
    private var loadInFlight: Boolean = false

    /** 已提交的装载任务总数 —— 配合 [loadSubmissions]（**诊断/探针用**）。 */
    private val submissions = AtomicLong(0L)

    /**
     * 已提交的装载任务总数。
     *
     * **诊断 / 探针用**，不参与业务判断 —— 用来断言"**一次装载只提交一次**"
     * （修复前实测会到 18+ 次，见 [loadInFlight] 的 KDoc）。
     */
    val loadSubmissions: Long get() = submissions.get()

    /**
     * 后台读取线程的世代号。每换一次曲目自增。
     *
     * ## 为什么需要它（"迟到的结果"问题）
     *
     * 后台加载是**异步**的：用户在第 0 ms 切到 A 歌，后台开始读 A；
     * 第 30 ms 用户又切到 B 歌，后台开始读 B。若 A 的读取在第 50 ms 完成，
     * 它会**把已经过期的 A 封面写进缓存**，覆盖掉正在加载的 B ——
     * 表现是"显示的是上一首歌的封面"，而且会一直错到下次切歌。
     *
     * 世代号让完成回调能判断"我这次加载是否仍然属于当前曲目"：
     * 世代不匹配 → 丢弃结果。这是**异步 UI 的经典竞态**，必须在设计里解决，
     * 不能靠"实际很少同时切歌"来赌。
     */
    private val generation = AtomicLong(0L)

    /**
     * 后台执行器。**单线程** —— 理由是"封面读取天然串行"：
     *
     * 它读写的是同一份缓存，多线程只会引入竞争；而用户最多一秒切几首歌，
     * 单线程的吞吐远超需求。用**守护线程**（daemon）是为了不让一个卡住的
     * 字体/网络盘读取阻止 JVM 退出 —— 插件被卸载时不该让进程挂住。
     */
    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "cdl-cover").apply { isDaemon = true }
        }
    )

    /**
     * 取某首歌的封面。
     *
     * ## 调用契约（**这条对渲染层很重要**）
     *
     * 本方法**永不阻塞**，因此它是"**尽力而为**"的：
     * - 命中缓存 → 立刻返回图片（或 `null`，表示"这首歌已确认没有封面"）；
     * - 未命中且从未尝试 → **立刻返回 `null`**，并在后台启动一次加载；
     *   加载完成后图片会在**后续某一帧**出现在缓存里。
     *
     * 也就是说，**切歌后的最初一两帧拿不到封面是正常的**，调用方应当把
     * `null` 当作"暂无封面"来画（不画封面），而不是当作错误。
     * 这个"先 null 后出现"是异步读取不可避免的代价，换来的是 EDT 永不卡顿。
     *
     * ## 为什么不在后台加载完成后主动通知调用方（回调/重绘触发）
     *
     * 本类**不持有**窗口引用，也不该持有 —— 那会把"封面获取"与"某个具体窗口"
     * 绑死，破坏分层（`cover` 包不应依赖 `window` 包）。调用方本来就在**每帧**
     * 读一次快照并重绘，下一帧自然就会看到新封面。用"轮询下一帧"换掉
     * "回调 + 重绘触发"这条更复杂的链路，在这里是划算的：代价是最多延迟一帧
     * （33 ms），收益是**零耦合**。
     *
     * @param audioPath 音频文件绝对路径（来自 `TimelineSnapshot.path`）。
     *   空串或指向不存在/不可读的文件 → 返回 `null`（正常降级）
     * @return 封面图片；**尚无或没有封面时为 `null`**（凡失败一律 `null`，不抛异常）
     */
    fun coverFor(audioPath: String): BufferedImage? {
        if (audioPath.isEmpty()) return null

        // 换曲检测：路径变了就作废旧缓存与"已尝试"标记，并递增世代号。
        // 用路径字符串作主键 —— 标题/艺术家会重复（同名歌曲、同专辑多版本），
        // 路径才是稳定身份（见 TimelineSnapshot.path 的 KDoc）。
        if (cachedPath != audioPath) {
            synchronized(lock) {
                if (cachedPath != audioPath) {
                    cachedPath = audioPath
                    cachedImage = null
                    loadAttempted = false
                    // ★ 换曲时**必须一并解除"在途"** —— 否则上一首的在途装载会把新歌挡在门外
                    //   （新歌要等到旧任务跑完才被允许提交）。旧任务的完成回调会再解除一次，无害。
                    loadInFlight = false
                    generation.incrementAndGet()
                }
            }
        }

        cachedImage?.let { return it }
        // 已经尝试过且结果是 null → 这首歌确认没有封面。**直接返回，不再触发加载**。
        // 这一行是防止"无封面歌曲每帧空转"的关键（见 loadAttempted 的 KDoc）。
        if (loadAttempted) return null

        // ★ 2026-09-26：**在途守卫**（见 [loadInFlight]）——
        //   装载已经在跑时不得再提交；否则渲染层**每帧**都会灌一个任务进来
        //   （实测一次装载窗口内重复提交 18 次）。
        //   ⚠️ **check-then-set 必须在锁内**：两个并发调用若都在锁外读到 `false`，
        //      就都会提交 —— 那正是这个守卫要防的事。
        synchronized(lock) {
            if (loadInFlight) return null
            loadInFlight = true
        }

        // 尚未尝试：触发一次后台加载，本次返回 null（见方法 KDoc 的"尽力而为"）。
        val requestedPath = audioPath
        val requestedGeneration = generation.get()
        submissions.incrementAndGet()
        executor.execute {
            val image = runCatching { loadCover(requestedPath) }
                .onFailure { System.err.println("[CDL] 封面读取失败（$requestedPath）：$it") }
                .getOrNull()
            synchronized(lock) {
                // ★ **无条件**解除"在途"：若只在世代匹配时解除，一次"迟到的结果"
                //   （或一次失败）就会把这首歌**永久锁死**在 `loadInFlight = true`。
                //   最坏后果只是"多提交一次"，代价可忽略。
                loadInFlight = false
                // 世代校验：读取过程中用户可能已切歌（见 generation 的 KDoc）。
                // 世代不匹配 → 结果属于上一首歌，**丢弃**，绝不写进当前缓存。
                if (requestedGeneration != generation.get()) return@execute
                if (cachedPath != requestedPath) return@execute
                cachedImage = image
                loadAttempted = true
            }
        }
        return null
    }

    /**
     * 释放后台线程与缓存。**必须在插件卸载时调用**（由组合根的 `close()`）。
     *
     * 不调用的话，那个守护线程池会一直活着；虽然它不阻止 JVM 退出，
     * 但会让已卸载插件的类加载器**无法被回收**（线程持有 Runnable → 持有本类 →
     * 持有 CoverArtProvider 的类加载器），PF4J 反复重载插件时是真实的内存泄漏。
     */
    fun close() {
        executor.shutdownNow()
        synchronized(lock) {
            cachedImage = null
            cachedPath = ""
            loadAttempted = false
            // ★ 一并解除"在途" —— `shutdownNow()` 之后那个任务不会再回来写它，
            //   不复位就会让本实例永久停在"有装载在跑"（虽已 close，但状态必须自洽）。
            loadInFlight = false
            generation.incrementAndGet()
        }
    }

    // ── 实际读取（只在后台线程上执行） ──────────────────────────────────────

    /**
     * 按优先级依次尝试两条来源。**只在后台线程上调用**。
     *
     * @return 图片；两条来源都没有时返回 `null`
     */
    private fun loadCover(audioPath: String): BufferedImage? {
        val path = runCatching { Path.of(audioPath) }.getOrNull() ?: return null
        // 文件不存在/不可读 → 直接走同目录兜底。为什么不在内嵌图失败后
        // 跳过同目录检查：文件缺失是"路径过期"（歌曲已移走），此时同目录图片
        // 大概率也没有，查一次代价极小，不查反而漏掉"文件被移动但图片还在"的情况。
        if (Files.isRegularFile(path) && Files.isReadable(path)) {
            embeddedCover(path)?.let { return it }
        }
        return sidecarCover(path)
    }

    /**
     * 读**内嵌图**。按容器格式分派到三个解析器。
     *
     * ## 为什么不引入库（如 Jaudiotagger）
     *
     * 1. **项目硬约束**：本任务明确要求"不引入新依赖、`build.gradle.kts` 不许改"。
     * 2. `build.gradle.kts:140` 里 `lgplDeps` 已为未来的 Jaudiotagger 预留了
     *    **LGPL-2.1 §6 的对应源码分发义务**（`fetchLgplSources` / `verifyLgplSources`）。
     *    引入它会把"发布一个插件包"变成"还要随包分发它的源码"，成本远超收益。
     * 3. **APIC 帧的解析确实不复杂**：帧头 10 字节 + 编码字节 + MIME 串 + 图片类型字节
     *    + 描述串 + 图片数据。这正是任务书推荐的"纯 Java 手写解析"路线。
     *
     * ## 分派依据
     *
     * 按**文件头魔数**而不是扩展名分派 —— 扩展名经常与实际格式不符
     * （`.m4a` 里装 FLAC、`.mp3` 其实是无标签的 AAC）。魔数才是内容的权威标识。
     * 这和 [FontLoader] 的"族名不能从文件名猜"是同一个原则。
     */
    private fun embeddedCover(path: Path): BufferedImage? {
        val header = runCatching { readHeader(path, 12) }.getOrNull() ?: return null
        if (header.size < 4) return null

        return when {
            isId3(header) -> runCatching { readId3Cover(path) }.getOrNull()
            isFlac(header) -> runCatching { readFlacCover(path) }.getOrNull()
            isMp4(header) -> runCatching { readMp4Cover(path) }.getOrNull()
            else -> null
        }
    }

    // ── ID3v2（MP3）────────────────────────────────────────────────────────

    /**
     * 解析 ID3v2 的 APIC 帧，取出图片数据。
     *
     * ## ID3v2 结构（本方法用到的部分）
     *
     * ```
     * 标签头 10 字节： "ID3" + 版本2B + 标志1B + 大小4B(Synchsafe)
     * 标签体：一串帧，每帧
     *   帧头 10 字节： 帧ID 4B + 大小4B + 标志2B
     *   APIC 帧体： 编码1B + MIME串(NUL 结尾) + 图片类型1B + 描述串 + 图片数据
     * ```
     *
     * ## 三个必须处理的坑
     *
     * 1. **Synchsafe 整数**：标签头里的 4 字节大小**每字节只用低 7 位**
     *    （为兼容某些会把高位吃掉的旧播放器）。直接当普通 int 读会得到**错误的值**
     *    （通常偏大 1~2 倍），进而越界读到标签外。必须逐字节取低 7 位拼。
     * 2. **帧大小在 v2.4 是 Synchsafe、v2.3 是普通 int**。用错会把帧长读错，
     *    后面全乱。故按首字节的版本号分派。
     * 3. **文本长度前缀的字节数取决于编码**：UTF-16 系（编码 1、2）的
     *    MIME/描述串长度是**累计到 NUL 的字节数**，而 NUL 在 UTF-16 里是**两个字节**。
     *    按"单字节 NUL"去扫会停在 UTF-16 字符的中间，把描述串的后半截当成图片开头。
     *    故 [skipString] 必须感知编码。
     *
     * ## 为什么只取第一个 APIC 帧
     *
     * 一首歌可能有多个 APIC（前封面、后封面、艺人照）。`图片类型` 字节里
     * `0x03` = 前封面（Front Cover），这是唯一该被显示的那个 —— 故优先找它。
     * 若没有类型 3 的，退回"第一个找到的 APIC"（好过没有）。
     */
    private fun readId3Cover(path: Path): BufferedImage? {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val header = ByteArray(10)
            if (file.read(header) != 10) return null
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                return null
            }
            val majorVersion = header[3].toInt() and 0xFF
            // 标签头里的总大小是 Synchsafe —— 见 KDoc 坑 #1。
            val tagSize = synchsafeInt(header, 6)
            // 防呆：标签大小若离谱（超过文件长度），说明头被改坏或不是真的 ID3，
            // 直接放弃而不是按它去读（否则会分配一个巨大的数组）。
            val fileLength = runCatching { file.length() }.getOrElse { 0L }
            if (tagSize <= 0L || tagSize > fileLength) return null

            val tagEnd = 10L + tagSize
            val body = ByteArray(tagSize.toInt())
            if (file.read(body) != body.size) return null
            file.seek(10L)   // 回到标签体开头，逐帧顺序读

            var fallback: ByteArray? = null
            var offset = 0L
            while (offset + 10 <= tagSize) {
                file.seek(10L + offset)
                val frameHeader = ByteArray(10)
                if (file.read(frameHeader) != 10) break

                val frameId = String(frameHeader, 0, 4, StandardCharsets.ISO_8859_1)
                // 全零帧头 = 到达标签内的填充区，停止。
                if (frameId[0] == '\u0000') break

                // 帧大小：v2.4 用 Synchsafe，v2.2/v2.3 用普通大端 int（见坑 #2）。
                //
                // ⚠️ 两条路径的返回类型不同（`Long` vs `Int?`），必须**显式归一化**：
                //   · `synchsafeInt` 返回 Long 是因为 syncsafe 的 28 位在 Int 里安全，
                //     但统一用 Long 做后续偏移运算可避免 Int 溢出（帧大小 × 帧数）；
                //   · `bigEndianInt` 返回可空是因为它要表达"越界"，而越界时
                //     唯一正确的处置就是放弃这一帧 —— 故 `?: break`。
                // 若不做这层归一化（曾如此，编译不过），`frameSize` 会推导成
                // `Number? & Comparable<*>?` 这种交叉类型，后续 `<=` / `+` 全部报错。
                val frameSize: Long = if (majorVersion >= 4) {
                    synchsafeInt(frameHeader, 4)
                } else {
                    (bigEndianInt(frameHeader, 4) ?: break).toLong()
                }
                if (frameSize <= 0L) break
                // 帧头 10 字节 + 帧体 frameSize 字节，越过它读下一帧。
                if (offset + 10L + frameSize > tagSize) break   // 帧长度越过标签边界 → 数据不可信
                val nextOffset = offset + 10L + frameSize

                if (frameId == "APIC") {
                    file.seek(10L + offset + 10L)
                    val frame = ByteArray(frameSize.toInt())
                    if (file.read(frame) != frame.size) break
                    // 图片类型字节的位置：编码 1B + MIME(NUL 结尾) 之后。
                    val pictureType = apicPictureType(frame)
                    val data = apicImageData(frame)
                    if (data != null && data.isNotEmpty()) {
                        // 类型 3 = 前封面，立刻就收。
                        if (pictureType == PICTURE_TYPE_FRONT_COVER) return decode(data)
                        // 否则先留着，继续找有没有前封面。
                        if (fallback == null) fallback = data
                    }
                }
                offset = nextOffset
            }
            return fallback?.let { decode(it) }
        }
    }

    /** 从 APIC 帧体里读出"图片类型"字节；结构非法时返回 -1。 */
    private fun apicPictureType(frame: ByteArray): Int {
        // 编码 1 字节在最前。
        val encoding = frame[0].toInt() and 0xFF
        // 跳过 MIME 串（ISO-8859-1，总是单字节 NUL 结尾 —— MIME 永远是 ASCII）。
        val mimeEnd = indexOfNul(frame, 1, singleByteNul = true) ?: return -1
        val typeIndex = mimeEnd + 1
        if (typeIndex >= frame.size) return -1
        return frame[typeIndex].toInt() and 0xFF
    }

    /** 从 APIC 帧体里读出图片数据；结构非法时返回 `null`。 */
    private fun apicImageData(frame: ByteArray): ByteArray? {
        if (frame.size < 4) return null
        val encoding = frame[0].toInt() and 0xFF
        val mimeEnd = indexOfNul(frame, 1, singleByteNul = true) ?: return null
        val typeIndex = mimeEnd + 1
        if (typeIndex >= frame.size) return null
        // 描述串紧跟图片类型字节，其 NUL 宽度取决于文本编码（见 KDoc 坑 #3）。
        val descriptionEnd = indexOfNul(frame, typeIndex + 1, singleByteNul = !isWideEncoding(encoding))
        if (descriptionEnd == null) return null
        val dataStart = descriptionEnd + if (isWideEncoding(encoding)) 2 else 1
        if (dataStart >= frame.size) return null
        return frame.copyOfRange(dataStart, frame.size)
    }

    /** 编码 1（UTF-16+BOM）、2（UTF-16BE）用 2 字节 NUL；0（ISO-8859-1）、3（UTF-8）用 1 字节。 */
    private fun isWideEncoding(encoding: Int): Boolean = encoding == 1 || encoding == 2

    /**
     * 从 [from] 起找 NUL 终结符，返回**它的下标**。
     *
     * [singleByteNul] 为 false 时找的是 UTF-16 的 `00 00`（双字节），
     * 且必须**对齐**扫描步长（按 2 字节前进）—— 否则会把一个 UTF-16 字符的
     * 高位字节 0 误判成终结符（这正是 KDoc 坑 #3 描述的失败模式）。
     */
    private fun indexOfNul(bytes: ByteArray, from: Int, singleByteNul: Boolean): Int? {
        if (from >= bytes.size) return null
        if (singleByteNul) {
            for (index in from until bytes.size) {
                if (bytes[index].toInt() == 0) return index
            }
            return null
        }
        var index = from
        while (index + 1 < bytes.size) {
            if (bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0) return index
            index += 2
        }
        return null
    }

    // ── FLAC ───────────────────────────────────────────────────────────────

    /**
     * 解析 FLAC 的 METADATA_BLOCK_PICTURE 块。
     *
     * ## FLAC 结构（本方法用到的部分）
     *
     * ```
     * 4 字节 "fLaC"
     * 一串元数据块，每块：
     *   块头 4 字节： 最后块标志1位 + 类型7位 + 长度3B(大端)
     *   块体
     * 类型 6 = PICTURE：
     *   图片类型 4B(大端) + MIME长度 4B + MIME + 描述长度 4B + 描述 + 宽4B + 高4B
     *   + 色深4B + 颜色数4B + 数据长度4B + 图片数据
     * ```
     *
     * 与 ID3 的区别：这里所有长度都是**普通大端整数**（不是 Synchsafe），
     * 且所有字段长度都是**显式的前缀**（不需要靠 NUL 分隔），因此**没有**
     * ID3 那种"编码决定 NUL 宽度"的坑 —— 这是 FLAC 比 ID3 好解析的地方。
     */
    private fun readFlacCover(path: Path): BufferedImage? {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val magic = ByteArray(4)
            if (file.read(magic) != 4) return null
            if (String(magic, StandardCharsets.ISO_8859_1) != "fLaC") return null

            var fallback: ByteArray? = null
            var isLast = false
            var guard = 0
            while (!isLast) {
                // 防呆：元数据块数量有硬上限（真实文件通常 < 10），
                // 遇到损坏文件时不至于无限循环（guard 是"读到垃圾数据"的兜底）。
                if (guard++ > MAX_FLAC_BLOCKS) break

                val blockHeader = ByteArray(4)
                if (file.read(blockHeader) != 4) break
                val headerByte = blockHeader[0].toInt() and 0xFF
                isLast = (headerByte and 0x80) != 0
                val blockType = headerByte and 0x7F
                // `bigEndianInt24` 返回可空（越界 = null）—— 越界时块头不可信，直接停。
                // `?: 0` 会把"读坏了"与"长度真的是 0"混为一谈，故显式 break。
                val blockLength = bigEndianInt24(blockHeader, 1) ?: break
                if (blockLength < 0) break

                if (blockType == FLAC_BLOCK_PICTURE) {
                    val block = ByteArray(blockLength)
                    if (file.read(block) != block.size) break
                    // 图片类型字段也走同一个可空读取；取不到就不做"前封面优先"的判断，
                    // 当作普通图片留作兜底 —— 这比因为一个可选字段读不到就放弃整块更稳。
                    val pictureType = bigEndianInt(block, 0)
                    val data = flacPictureData(block)
                    if (data != null && data.isNotEmpty()) {
                        // 同 ID3：优先前封面（类型 3），否则留作兜底。
                        if (pictureType == PICTURE_TYPE_FRONT_COVER) return decode(data)
                        if (fallback == null) fallback = data
                    }
                } else {
                    // 非 PICTURE 块：直接跳过（不读进内存 —— 一个 FLAC 的
                    // STREAMINFO/SEEKTABLE 可能很大，没必要整块读进来）。
                    if (!skipFully(file, blockLength.toLong())) break
                }
            }
            return fallback?.let { decode(it) }
        }
    }

    /** 从 FLAC PICTURE 块体里取出图片数据（各字段都是 4 字节大端长度前缀）。 */
    private fun flacPictureData(block: ByteArray): ByteArray? {
        var cursor = 4   // 跳过图片类型
        // MIME 串：长度 4B 大端 + 内容
        val mimeLength = bigEndianInt(block, cursor) ?: return null
        cursor += 4 + mimeLength
        // 描述串：长度 4B 大端 + 内容
        val descriptionLength = bigEndianInt(block, cursor) ?: return null
        cursor += 4 + descriptionLength
        // 宽/高/色深/颜色数：4 个 4 字节字段
        cursor += 16
        val dataLength = bigEndianInt(block, cursor) ?: return null
        cursor += 4
        if (cursor < 0 || dataLength <= 0) return null
        if (cursor + dataLength > block.size) {
            // 声明的长度超出实得块体 → 数据不可信，但**尽力取剩下的部分**：
            // 有些写入工具会把长度字段算错，而图片数据本身是完整的。
            // 取剩余全部，交给 ImageIO 判断（解不出来自然返回 null）。
            val remaining = block.size - cursor
            if (remaining <= 0) return null
            return block.copyOfRange(cursor, block.size)
        }
        return block.copyOfRange(cursor, cursor + dataLength)
    }

    // ── MP4 / M4A ──────────────────────────────────────────────────────────

    /**
     * 在 MP4/M4A 的 atom 树里找 `covr` 封面 atom。
     *
     * ## MP4 结构（本方法用到的部分）
     *
     * ```
     * 每个 atom：[大小 4B 大端][类型 4B] + 数据
     *   moov → udta → meta → ilst → covr → data
     * ```
     *
     * ## 本实现的取舍：**只按名字查找，不做完整的树遍历**
     *
     * 完整的 MP4 树遍历需要处理 `size == 1`（64 位大小）、`size == 0`（延伸到文件尾）、
     * `meta` atom 里额外的 4 字节版本/标志等特例，代码量会翻几倍。
     *
     * 而 `covr` 这个 atom 类型名在文件里的出现**极难误撞**（它是 4 字节 ASCII 标识，
     * 恰好出现在别处的概率可忽略）。故本实现采用的策略是：
     * **在文件的头部区域里顺序扫描 atom 头，寻找类型为 `covr` 的 atom**，
     * 找到后从它的 `data` 子 atom 里取图片。
     *
     * ⚠️ **明确的局限（如实声明）**：这是一种**启发式**扫描，不是严格的树遍历。
     * 它在绝大多数真实 M4A 文件上有效（`covr` 通常就在 `moov.udta.meta.ilst` 下，
     * 且 `moov` 一般在文件前部或尾部）。**可能失败的情况**：
     * - `covr` 位于**文件尾部**且超出了 [MP4_SCAN_LIMIT] 的扫描窗口 → 漏掉（返回 null，
     *   随后走同目录图片兜底）；
     * - 文件被 **faststart 优化且 `moov` 在头、但 `covr` 嵌套在更深层且中间夹着
     *   大小为 0/1 的特殊 atom** → 跳过逻辑会迷失，可能漏掉。
     *
     * 这些情况的结果**只是"没找到封面"**（降级为同目录图片或 null），
     * **不会返回错误的图片**。判断为可接受的取舍。
     */
    private fun readMp4Cover(path: Path): BufferedImage? {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val window = runCatching { readHeader(file, MP4_SCAN_LIMIT, path) }.getOrNull() ?: return null
            val covrIndex = indexOfAtom(window, "covr")
            if (covrIndex < 0) return null
            // covr atom 的体从类型字段之后开始（+8：4B 大小 + 4B 类型）。
            val covrStart = covrIndex + 8
            if (covrStart >= window.size) return null
            // covr 体内是一个 data atom：[大小][`data`][版本+标志 4B][保留 4B][图片数据]
            val dataAtom = indexOfAtom(window, "data", from = covrStart)
            if (dataAtom < 0) return null
            // 跳过 data atom 的 [大小][类型] 8 字节 + 版本标志 4 字节 + 保留 4 字节。
            val payloadStart = dataAtom + 8 + 8
            if (payloadStart >= window.size) return null
            val payload = window.copyOfRange(payloadStart, window.size)
            // 图片数据可能在 payload 里带尾部填充，交由 ImageIO 从流里解码
            // （它会读到图片自身的结束标记，忽略后面多余字节）。
            return decode(payload)
        }
    }

    /**
     * 在 [window] 里从 [from] 起找类型为 [type] 的 atom，返回**该 atom 头的下标**。
     *
     * 只检查 4 字节对齐外的每一字节位置（不做对齐假设 —— MP4 的 atom 是连续的，
     * 但我们是在一个启发式窗口里扫描，位置不一定从 0 对齐）。
     */
    private fun indexOfAtom(window: ByteArray, type: String, from: Int = 0): Int {
        val bytes = type.toByteArray(StandardCharsets.ISO_8859_1)
        if (bytes.size != 4) return -1
        var index = from
        while (index + 4 <= window.size) {
            if (window[index] == bytes[0] &&
                window[index + 1] == bytes[1] &&
                window[index + 2] == bytes[2] &&
                window[index + 3] == bytes[3]
            ) {
                return index
            }
            index++
        }
        return -1
    }

    // ── 同目录图片（第二条来源）─────────────────────────────────────────────

    /**
     * 在音频文件**同目录**下找常见封面文件名。
     *
     * ## 为什么只认这几个固定名
     *
     * 这是跨播放器/媒体库的事实标准命名：`cover.jpg`（最通用）、
     * `folder.jpg`（Windows Media Player / 多数整理工具）、
     * `front.jpg`（部分抓轨工具）、以及对应的 `.png`。
     * 无脑扫描目录里所有图片会**误取**（一首歌的目录里可能有截图、
     * 歌词配图、无关的 `.jpg`），那种错误比"没有封面"更糟 —— 用户会看到
     * 一张与音乐无关的图，且难以理解为什么。
     *
     * 顺序按"通用性降序"：先 `cover`，再 `folder`，再 `front`。
     * 每个名字先试 `.jpg` 再试 `.png` —— `.jpg` 是绝大多数抓轨工具的输出格式。
     *
     * ## 大小写
     *
     * Windows 文件系统**不区分大小写**，故 `Files.isRegularFile(Path.of("cover.jpg"))`
     * 在文件实际名为 `Cover.JPG` 时**也会命中**（这正是我们要的）。
     * 因此这里只写小写名即可，不需要枚举大小写变体。
     * ⚠️ 在区分大小写的文件系统（Linux 上的音乐库）上这种行为反之 —— 会漏掉
     * `Cover.JPG`。这是**有意的取舍**：本插件是 Windows 桌面歌词插件
     * （SPW = Salt Player for Windows），枚举变体会让 8 个候选膨胀到几十个，
     * 而收益仅存在于非目标平台。
     */
    private fun sidecarCover(audioPath: Path): BufferedImage? {
        val directory = audioPath.parent ?: return null
        for (name in SIDECAR_NAMES) {
            val candidate = runCatching { directory.resolve(name) }.getOrNull() ?: continue
            if (!runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) continue
            val image = runCatching {
                // ★ 2026-10-17：**必须走带长边上限的解码**（同 `decode`）——
                //   这条路径以前直接用 `ImageIO.read`，一张 4000×4000 的 `cover.jpg`
                //   会和内嵌图一样分配 61 MB，是同一个内存尖峰的**第二处入口** ✗
                decodeCoverFile(candidate)
            }.getOrNull()
            if (image != null) return image
        }
        return null
    }

    // ── 公共小工具 ─────────────────────────────────────────────────────────

    /**
     * 把图片字节解码成 `BufferedImage`；失败返回 `null`。
     *
     * ★★★ 2026-10-17：**改走 [decodeCoverImage]**（长边上限 [COVER_DECODE_MAX_SIDE]）。
     *
     * 原实现是 `ImageIO.read(ByteArrayInputStream(data))` —— **按原图分辨率解码**：
     * 4000×4000 的内嵌封面一张 **61 MB**，而切歌瞬间同一条链上还有
     * `CoverScaler` 的整幅方形副本、`areaAverage` 的同尺寸 `IntArray`、
     * 以及上一首尚未被回收的图 ⇒ 峰值 ~244 MB（用户报「切歌瞬间飙高 100-200 多 M」）。
     *
     * 新实现让解码器**直接按因子跳像素**（`setSourceSubsampling`）⇒
     * 那张 61 MB 的图**根本不会被分配**；长边固定 ≤ 1024（4 MB）✓
     */
    private fun decode(data: ByteArray): BufferedImage? = decodeCoverImage(data)

    /** 读文件前 [count] 字节。不足时返回实得的部分（不抛异常）。 */
    private fun readHeader(path: Path, count: Int): ByteArray =
        RandomAccessFile(path.toFile(), "r").use { readHeader(it, count, path) }

    /** 从已打开的 [file] 当前位置读 [count] 字节（供 `use` 块内复用，避免重复打开）。 */
    private fun readHeader(file: RandomAccessFile, count: Int, path: Path): ByteArray {
        val available = runCatching { file.length() }.getOrElse { 0L }
        val size = minOf(count.toLong(), available).toInt()
        if (size <= 0) return ByteArray(0)
        val buffer = ByteArray(size)
        val read = file.read(buffer)
        return if (read <= 0) ByteArray(0) else if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    /** 跳过 [count] 字节；超出文件尾时返回 false。 */
    private fun skipFully(file: RandomAccessFile, count: Long): Boolean =
        runCatching { file.seek(file.filePointer + count); true }.getOrDefault(false)

    /**
     * 读 ID3v2 的 **Synchsafe** 4 字节整数（每字节只用低 7 位）。
     *
     * 见 [readId3Cover] 的 KDoc 坑 #1 —— 这是 ID3 解析里最容易出错的一处。
     */
    private fun synchsafeInt(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return -1L
        var result = 0L
        for (index in 0 until 4) {
            result = (result shl 7) or (bytes[offset + index].toLong() and 0x7FL)
        }
        return result
    }

    /** 读普通大端 4 字节整数（可空 —— 越界返回 null，供"取不到就放弃"的路径用）。 */
    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int? = bigEndianInt(bytes, offset, 4)

    /**
     * 读 [size] 字节的大端无符号整数；越界返回 null。
     * 返回值是 `Int` —— 封面相关字段（长度、类型）都在 Int 范围内；
     * 超过 `Int.MAX_VALUE` 的长度会被截成负数，调用方的 `<= 0` 判定会自然拒绝它。
     */
    private fun bigEndianInt(bytes: ByteArray, offset: Int, size: Int): Int? {
        if (offset < 0 || offset + size > bytes.size) return null
        var result = 0
        for (index in 0 until size) {
            result = (result shl 8) or (bytes[offset + index].toInt() and 0xFF)
        }
        return result
    }

    /** 读 3 字节大端整数（FLAC 的块长度字段是 3 字节）。 */
    private fun bigEndianInt24(bytes: ByteArray, offset: Int): Int? = bigEndianInt(bytes, offset, 3)

    private fun isId3(header: ByteArray): Boolean =
        header.size >= 3 &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()

    private fun isFlac(header: ByteArray): Boolean =
        header.size >= 4 && String(header, 0, 4, StandardCharsets.ISO_8859_1) == "fLaC"

    /**
     * MP4 家族判定：第 5–8 字节是 `ftyp`（ISO Base Media 的固定标志）。
     *
     * 为什么不看第 1–4 字节：那是 atom 大小（随文件变化）；
     * `ftyp` 这个类型标识才是格式的权威标志。
     */
    private fun isMp4(header: ByteArray): Boolean =
        header.size >= 8 && String(header, 4, 4, StandardCharsets.ISO_8859_1) == "ftyp"

    private companion object {
        /**
         * 缓存容量。**取 1** —— 论证见 [cachedPath] 的 KDoc。
         * 写成具名常量是为了让这个决定在代码里显式可见。
         */
        @Suppress("unused")
        const val MAX_CACHED = 1

        /** ID3 APIC 的"前封面"类型值（ID3v2 规范：`$03` = Front cover）。 */
        const val PICTURE_TYPE_FRONT_COVER = 3

        /** FLAC 元数据块类型 6 = PICTURE。 */
        const val FLAC_BLOCK_PICTURE = 6

        /** FLAC 元数据块数上限（防损坏文件导致的无限循环）。 */
        const val MAX_FLAC_BLOCKS = 256

        /**
         * MP4 封面扫描窗口：前 4 MB。
         *
         * 为什么是 4 MB：`covr` 位于 `moov.udta.meta.ilst` 下，
         * 未做 faststart 的文件里 `moov` 在**尾部**、faststart 后在**头部**。
         * 4 MB 足以覆盖"头部"这一侧（`moov` 通常在几百 KB 内），
         * 且不会为了一次封面读取把整个文件（可能上百 MB）拉进内存。
         * 尾部的情况按 [readMp4Cover] 的 KDoc 属于已知局限。
         */
        const val MP4_SCAN_LIMIT = 4 * 1024 * 1024

        /**
         * 同目录封面候选文件名。顺序 = 优先级。理由见 [sidecarCover]。
         *
         * 只写小写：Windows 文件系统不区分大小写，`isRegularFile` 会命中
         * `Cover.JPG` 这类实际名（详见 [sidecarCover] 的 KDoc）。
         */
        val SIDECAR_NAMES = listOf(
            "cover.jpg",
            "cover.png",
            "folder.jpg",
            "folder.png",
            "front.jpg",
            "front.png",
        )
    }
}
