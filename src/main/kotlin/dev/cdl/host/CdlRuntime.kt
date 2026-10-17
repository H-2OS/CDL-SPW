// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.cdl.cover.CoverArtProvider
import dev.cdl.platform.ProcessSpectrum
import dev.cdl.playback.LyricDocument
import dev.cdl.playback.LyricLine
import dev.cdl.playback.LyricWord
import dev.cdl.playback.PlaybackTimeline
import dev.cdl.playback.TimelineSnapshot
import dev.cdl.playback.bilingualLinesFrom
import dev.cdl.playback.HostLyricCell
import dev.cdl.playback.HostLyricLine
import dev.cdl.playback.narrowWordsToText
import dev.cdl.playback.narrowWordsToTranslation
import dev.cdl.playback.separatedMainText
import dev.cdl.playback.separatedTranslation
import dev.cdl.playback.splitBilingualLine
import dev.cdl.settings.SettingChange
import dev.cdl.settings.SettingCoupling
import dev.cdl.settings.SettingRegistry
import dev.cdl.settings.SettingsRuntime
import dev.cdl.settingsui.PresetManagerMode
import dev.cdl.settingsui.SettingsRuntimeHolder
import dev.cdl.settingsui.ToolWindows
import dev.cdl.settingsui.showFontSourceDialog
import dev.cdl.settingsui.showPresetManagerDialog
import dev.cdl.window.IslandTray
import dev.cdl.window.IslandWindow
import dev.cdl.render.needsSpectrumData
import dev.cdl.window.coverShapeForcesSpectrum
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import java.util.Properties

/**
 * 组合根：负责装配与释放本插件的全部子系统。
 *
 * P0 阶段：只持有 [PluginContext]、打印启动/停止日志，并提供设置页按钮的静态入口。
 * P1 起在这里装配 `PlaybackTimeline`、窗口、频谱进程等（参照同类实现的 `IslandRuntime`）。
 */
class CdlRuntime(private val context: PluginContext) : AutoCloseable {

    @Volatile
    private var closed = false

    /**
     * 设置系统的组合根（注册表 + 存储 + 变更总线 + 撤销栈）。
     *
     * 为什么由运行时持有、而不是做成单例：设置系统的生命周期与插件实例**同生共死**。
     * PF4J 卸载插件时会走 `stop()`，若设置系统是全局单例，卸载后仍持有 `ConfigHelper`
     * 与监听器，宿主再次加载插件就会拿到一份**指向已销毁宿主对象的陈旧状态**。
     * 由运行时持有的写法让 `close()` 能一并释放，避免这类跨生命周期残留。
     */
    @Volatile
    internal var settings: SettingsRuntime? = null
        private set

    /**
     * 播放时间轴：把宿主回调整理成渲染线程可每帧直接读的平滑状态。
     *
     * 与 [settings] 同为"运行时持有、随插件卸载一并释放"的子系统，理由相同
     * （见 [settings] 的注释）：进程级的单例会在 PF4J 重载插件后残留，
     * 而时间轴持有曲目、位置、歌词等**与本次播放会话强绑定**的状态，
     * 残留一份就等于用上一会话的位置去渲染这一会话的歌词。
     */
    @Volatile
    internal var timeline: PlaybackTimeline? = null
        private set

    /**
     * 静态桥：把当前设置运行时交给 `settingsui` 包。
     *
     * ## 为什么需要它
     *
     * `preference_config.json` 的 `on_click` 靠反射按「全限定类名.静态方法名」调用，
     * 链路上拿不到任何实例。本项目已有同类先例（`CdlPlugin.playback`）。
     *
     * ## 为什么存在 `settingsui` 而不是 `host`
     *
     * 若 `settingsui` 反向 import `host`，就形成 `host → settingsui → host` 的包级环。
     * 把静态引用放在表现层、由组合根（本类）去登记，依赖方向保持单向：
     * `host` → `settingsui` → `settings`。
     *
     * ## 为什么必须在 `close()` 里清空
     *
     * 该引用持有 `SettingsRuntime`，而后者持有 `ConfigHelper` 与订阅者列表。
     * PF4J 卸载插件后若不清空，静态引用会**跨越生命周期**指向已销毁的运行时的
     * 配置对象；用户再次加载插件时，设置窗口可能取到那一份陈旧实例，
     * 表现为"改了设置但词岛没反应"。
     */
    private fun registerSettingsHolder() {
        SettingsRuntimeHolder.current = settings
    }

    /**
     * 桌面词岛窗口。
     *
     * 与 [settings] / [timeline] 同为"运行时持有、随插件卸载一并释放"的子系统，
     * 理由相同（见 [settings] 的注释）：窗口持有一个 33 ms 的 Swing 定时器与
     * 一份渲染缓存（`TextLayout` / `Color` / `Shape`），若做成进程级单例，
     * PF4J 重载插件后旧窗口会继续在 EDT 上跑、继续持有指向已销毁宿主的资源 ——
     * 那是真实的内存泄漏加后台空转。由运行时持有让 [close] 能一并停掉它。
     *
     * 类型为可空：窗口创建失败（图形环境异常、无头环境、Swing 初始化失败）时
     * 降级为 `null`，插件其余功能照常 —— 见 [start] 的装配代码。
     */
    @Volatile
    internal var islandWindow: IslandWindow? = null
        private set

    /**
     * 系统托盘图标 —— 词岛的**兜底入口**。
     *
     * 为什么是必需而非可选：词岛无边框、可穿透、不进 Alt+Tab，
     * 一旦被拖出屏幕或关掉，就**没有其它入口能再操作它**。
     * 详见 [dev.cdl.window.IslandTray] 的类 KDoc。
     */
    @Volatile
    internal var islandTray: dev.cdl.window.IslandTray? = null
        private set

    /**
     * 曲目时长加载器 —— 进度条总时长的来源。
     *
     * 与其它子系统同为"运行时持有、随插件卸载一并释放"，理由相同
     * （见 [settings] 的注释）：它持有一个后台线程，若做成进程级单例，
     * PF4J 重载插件后会留下一个指向已销毁宿主的线程。
     *
     * 类型**不可空**（不是 `?`）：它的构造不会失败（只建一个线程池），
     * 而绘制路径每帧都要读它 —— 做成可空就等于让渲染层每帧判一次 null，
     * 而那个 null 分支在实践中永不执行（因此最容易腐化）。
     */
    private val trackDuration = TrackDurationLoader()

    /**
     * 专辑封面来源（`cover.enabled` + 三个 `*_color_mode`）。
     *
     * ## 为什么由运行时持有（而不是窗口自己 new 一个）
     *
     * 它持有一个**后台线程池**与一份封面缓存（一张 1000×1000 的 ARGB 图是 4 MB）。
     * 若由窗口持有：
     * ① 窗口重建（设置系统重载 / 词岛恢复）会丢掉缓存，同一首歌要重新读盘解码；
     * ② 窗口的 `close()` 与"插件卸载"是**两个**不同的时机 —— 前者在
     *    `setVisible(false)` 之类的路径上也可能被调到，把线程池关在那里会让
     *    "隐藏再显示词岛"之后封面彻底失效（而那是**用户可见**的功能消失）。
     *
     * 与 [trackDuration] 完全同一模式：**运行时持有、随插件卸载一并释放**。
     * 窗口只拿到一个 `coverArtProvider` 参数（只读用），不负责它的生命周期。
     *
     * 类型**不可空**（与 [trackDuration] 同）：构造不会失败（只建线程池 + 几个字段），
     * 而 `close()` 要无条件释放它 —— 做成可空只会让 `close()` 多一个无意义的判空。
     */
    private val coverArt = CoverArtProvider()

    /**
     * 频谱数据源（`spectrum.enabled` —— `BUG-043` 里最后一项死设置）。
     *
     * ## 为什么由运行时持有（而不是窗口自己 new 一个）
     *
     * 它持有**一个子进程 + 一条读线程**。若由窗口持有：
     * ① 窗口重建（设置重载 / 词岛恢复）会重启进程 —— 助手启动要几百毫秒，
     *    用户会看到频谱"闪一下又没"；
     * ② 窗口的 `close()` 与"插件卸载"是**两个**不同的时机 —— 把进程关在那里，
     *    "隐藏再显示词岛"之后频谱会彻底失效。
     *
     * 与 [coverArt] / [trackDuration] 完全同一模式：**运行时持有、随插件卸载一并释放**。
     * 窗口只拿到一个 `spectrumBandsSupplier`（只读用）。
     *
     * ## 类型**可空**（与上面两个不同，这是有意的）
     *
     * 因为**它的存在本身取决于设置**：`spectrum.enabled` 关时**不该起进程**
     * —— 用户关了开关却还有个子进程在后台跑，是明确的错误行为。
     * 故 `null` 表示"用户关掉了 / 尚未装配"，这是**有意义的业务状态**，
     * 不是"构造失败"（对比 [coverArt]：那里构造不会失败，故不可空）。
     */
    @Volatile
    private var spectrum: ProcessSpectrum? = null

    /** `spectrum.enabled` 变更的订阅句柄，`close()` 时必须反注册。 */
    private var spectrumSubscription: AutoCloseable? = null

    /**
     * 设置项**依赖联动**的订阅句柄（见 [SettingCoupling]）。
     *
     * 与 [spectrumSubscription] 同一纪律：必须在 `settings.close()` **之前**关闭，
     * 否则回调会在一个已关闭的 runtime 上跑。
     */
    private var couplingSubscription: AutoCloseable? = null

    /**
     * 完整歌词轴探针（裁定 ①，2026-09-17）。
     *
     * 与 [trackDuration] 同类：**不可空** —— 它的构造不会失败（只初始化几个字段），
     * 而回调路径每次 `Line` 事件都要问它"本曲接管了吗"，
     * 做成可空就等于让那条路径每次都判 null，而那个 null 分支在实践中永不执行。
     *
     * 探针**内部**已保证 fail-open：探不到就返回 null，调用方走原有逐行累积。
     * 详见 `HostLyricsProbe` 的类 KDoc。
     */
    fun start() {
        // ⚠️ 顺序说明：原来这里第一行就打启动日志，但那条日志**会丢** ——
        // 宿主是 jpackage 的 GUI 子系统，没有控制台，STDERR 无处可去。
        // 故改为：先装配设置系统 → 拿到数据目录 → 接管日志 → **再补打**启动信息。
        // 见 PluginLog 的类 KDoc（真机验收时"插件跑过但零痕迹"的教训）。
        //
        // 设置系统先于回调桥启动：回调处理可能读设置，若顺序反了，
        // 启动瞬间的宿主回调会读到 null 设置。失败不阻塞插件启动 ——
        // 配置坏了也要能进插件（降级到默认值），这是「插件不能因为配置坏了就崩」的落地。
        settings = runCatching { SettingsRuntime.create(context) }
            .onFailure { System.err.println("[CDL] 设置系统启动失败，降级为默认值：$it") }
            .getOrNull()
        // 日志落盘接管：stderr 镜像到 `<数据目录>/super-island.log`。
        // 必须在 settings 之后 —— 数据目录来自 ConfigHelper，只有它知道真实路径。
        // 失败只是降级（回到"日志会丢"的原状），不影响插件功能。
        runCatching { PluginLog.install(settings?.dataDirectory()) }
        // 补打启动信息（此时日志镜像已生效，这条**会**进文件）。
        System.err.println(
            "[CDL] start: pluginId=${context.pluginId} " +
                "pluginVersion=${context.pluginVersion} spwVersion=${context.spwVersion} " +
                "channel=${context.spwChannel} path=${context.pluginPath} " +
                "java=${System.getProperty("java.version")} " +
                "os=${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        )
        // ★ 补打**生效设置快照**（2026-09-17 补）—— 与上面 `start:` **同一个理由**。
        //
        // 真机取证：日志里 4 次 `start:` 只对应 **1 行**快照。原因见
        // `SettingsRuntime.logEffectiveSettings` 的 KDoc ——
        // 它的首次调用在 `SettingsRuntime.create()` 里，**发生在日志接管之前**，
        // 而宿主是 jpackage GUI 子系统、没有控制台 → 接管前打的一切**无处可去**。
        //
        // 这正是本方法开头那段"顺序说明"已经修过一次的同一个坑
        // （当时把 `start:` 搬到了接管之后），**只是漏了设置那两条**。
        //
        // 为什么值得单独补：快照是"设置到底读到了没有"的**唯一直接证据** ——
        // 用户报"设置改了没用"时，没有它就只能靠猜（见该方法的 KDoc 表格）。
        // 用 `stage` 与首次调用区分，避免读者当成重复输出。
        runCatching { settings?.logEffectiveSettings(stage = "启动快照 · 接管日志后复核") }
            .onFailure { System.err.println("[CDL] 补打生效设置失败：$it") }

        // ── ★★ 第 4 期实验①：**宿主类可达性**（2026-09-27 新增，一次性诊断）──────
        //
        // ## 它回答什么（`migration/32` §5 实验①第 7 步）
        //
        // C1（复用宿主 Skiko）的**唯一硬缺口**是：**插件的类加载器能否拿到宿主的
        // `org.jetbrains.skia.*`**。`SkikoReachabilityProbe` 只证明了"宿主 jar 里有这些类"
        // —— 它用的是**自造**的 URLClassLoader；而真机上是 **PF4J 的 PluginClassLoader**
        // 在跑，它的委派方向**未核**。
        //
        // ## 为什么必须放在生产启动路径（不能做成独立探针）
        //
        // `runProbe` 是**独立 JVM**，那里根本没有 PF4J 的 loader ⇒ 只有**在宿主进程内**
        // 打印才作数。`runCatching` 包裹 ⇒ 失败只是少一条诊断，不影响插件启动。
        //
        // ## 判读
        //
        // · `sameAsPlugin=true` 且 `src` 指向 `app\ffmpeg-x64.dll`（宿主 fat jar）
        //   ⇒ **拿到了宿主的类** ⇒ C1 缺口闭合，可走「档 0-C1（+0 MB）」；
        // · `加载失败` ⇒ 委派没走到宿主 ⇒ C1 需要自带 Skiko（档 1，+11.08 MiB）。
        runCatching {
            val selfLoader = this::class.java.classLoader
            val names = listOf("org.jetbrains.skia.ImageFilter", "androidx.compose.ui.Modifier")
            val lines = names.map { n ->
                val c = runCatching { Class.forName(n, false, selfLoader) }.getOrNull()
                if (c == null) {
                    "  $n → **加载失败**（不在插件可见的 classpath 上）"
                } else {
                    val l = c.classLoader
                    val src = runCatching {
                        c.protectionDomain?.codeSource?.location?.toString()
                    }.getOrNull()
                    "  $n → loader=${l?.javaClass?.name}@${System.identityHashCode(l)}" +
                        " sameAsPlugin=${l === selfLoader} src=$src"
                }
            }
            System.err.println(
                buildString {
                    append("[CDL] ★ 第4期实验①·宿主类可达性：")
                    append("\n  pluginClassLoader=${selfLoader?.javaClass?.name}")
                    append(
                        "\n  contextClassLoader=" +
                            "${Thread.currentThread().contextClassLoader?.javaClass?.name}"
                    )
                    append(
                        "\n  systemClassLoader=" +
                            "${ClassLoader.getSystemClassLoader()?.javaClass?.name}"
                    )
                    lines.forEach { append("\n"); append(it) }
                }
            )
        }.onFailure {
            System.err.println("[CDL] 宿主类可达性诊断失败（不影响启动）：$it")
        }
        // 时间轴必须在 attach 之前装配完成：attach 会**回放**宿主在插件就绪前
        // 缓冲的全部事件（见 CallbackSink.attach），而回放会立刻调用
        // [onPlaybackEvent] 转发到 timeline。若顺序反了，那段回放会被静默丢弃，
        // 表现为"插件启动后要等下一次切歌才认识当前曲目"。
        //
        // 失败降级为空：时间轴构造本身几乎不可能失败（只读 System.nanoTime），
        // 但仍按与设置系统相同的风格包裹，确保任何意外都不会阻塞插件启动。
        timeline = runCatching { PlaybackTimeline() }
            .onFailure { System.err.println("[CDL] 播放时间轴启动失败，降级为无歌词状态：$it") }
            .getOrNull()
        // ⚠️ 2026-09-18：此处原有 `applyKeepPlaceholder()` / `subscribeKeepPlaceholder()`
        // 一对调用（设置项 `layout.emptySlot.keepPlaceholder`）。该设置项因**两个分支
        // 在真机上均不可达**（即"能拖但画面毫无变化"）被用户裁定移除，
        // 时间轴的 `nextLine` 语义固化为"有下一行就用、没有就 null"，
        // 不再需要从设置侧同步任何东西。理由见 `SettingRegistry.groupI()` 与
        // `PlaybackTimeline.nextLineFor` 的 KDoc。
        // 频谱助手的启停（`spectrum.enabled`）。同一模式：
        // **先按当前设置定初值，再订阅变更** —— 否则用户改了开关要重启插件才生效，
        // 那正是"死设置"的形态（BUG-043）。
        runCatching { syncSpectrum() }
            .onFailure { System.err.println("[CDL] 启动频谱助手失败（频谱将不显示，音乐不受影响）：$it") }
        runCatching { subscribeSpectrum() }
            .onFailure { System.err.println("[CDL] 订阅频谱开关失败，该项将只在启动时生效：$it") }
        // 设置项的**依赖联动**（见 `SettingCoupling`）：拖了被门控的项就自动解门控。
        // 失败不致命 —— 顶多退回"拖了没反应"的旧行为，故只记录不抛。
        runCatching { subscribeSettingCoupling() }
            .onFailure { System.err.println("[CDL] 订阅设置联动失败，门控项将退回旧行为：$it") }
        // 登记到静态桥，让 `preference_config.json` 的按钮能打开设置窗口。
        // 见 registerSettingsHolder 的 KDoc：这是"静态入口拿不到实例"的既定解法，
        // 与 CdlPlugin.playback 是同一模式。
        //
        // 即便 settings 为 null 也登记（窗口会进入只读形态）——
        // 比"点了按钮完全没反应"更让用户明白发生了什么。
        runCatching { registerSettingsHolder() }
            .onFailure { System.err.println("[CDL] 登记设置静态桥失败，设置按钮将不可用：$it") }
        // 窗口装配必须在 timeline 之后（窗口的 snapshotSupplier 要读 timeline），
        // 且在 attach 之前 —— 这样 attach 回放缓冲事件期间窗口已就绪，
        // 否则"插件启动后到下一次切歌"这段时间词岛会画不出内容。
        //
        // 整段 runCatching 是硬要求：本方法是**插件启动路径**，任何异常都会让
        // PF4J 判定加载失败、整个插件不可用。而降级只需失去一个悬浮窗 ——
        // 代价极不对称，故与上面 settings / timeline 的装配保持同一风格：
        // 失败 → 打 stderr → 置 null → 继续。
        islandWindow = runCatching {
            IslandWindow(
                settings = settings,
                // 时间轴缺失（启动时降级）时返回 EMPTY，而不是抛异常。
                // 这样渲染层永远不必处理 null 时间轴 —— 见 TimelineSnapshot.EMPTY 的说明。
                snapshotSupplier = { timeline?.snapshot() ?: TimelineSnapshot.EMPTY },
                // 播放控件的写入口。控件点下去要能真的控制播放器，这是本轮
                // 「鼠标移到歌词上弹出播放控件」能成立的前提（0.8.9 的对应物是
                // `IslandRuntime.kt:57-64` 的 PlaybackActions 匿名实现）。
                playbackActions = HostPlaybackActions,
                // 总时长：读 `snapshot.path` 指向的**本地音频文件**（宿主 API 不提供时长，
                // 取证与决策见 recon/26 §2）。读取在后台线程进行，这里每帧读结果。
                // 时长未知时返回 0 → 进度条只画轨道 + `--:--`，且不响应点击
                // （见 PlaybackControls.paintProgress 与 seekTargetAt）。
                durationSupplier = { trackDuration.current() },
                // 频谱：**每帧读一次最新电平**。`spectrum` 为 null（开关关着 / 助手起不来）
                // 时返回 null，渲染层据此不画 —— "没有数据"是一种正常状态，不是异常
                // （见 `IslandWindow.spectrumBandsSupplier` 的 KDoc）。
                //
                // 这里读的是 `@Volatile` 字段 + 内部的 `AtomicReference`，
                // **无锁、无 I/O、不阻塞帧**。
                spectrumBandsSupplier = { spectrum?.bands() },
                // 封面来源：`cover.enabled` 打开时窗口每帧向它要一次当前曲目的封面
                // （非阻塞，见 CoverArtProvider 的契约），并从中提取主色供
                // `*_color_mode = cover` 使用。**生命周期归运行时** —— 见 [coverArt]。
                coverArtProvider = coverArt,
                // ★ 2026-09-28：L3（真透视）的降级告警通道。
                //   分层约定：`window/` **不碰 `WorkshopApi`** —— 窗口要发提示时走这条注入的
                //   lambda，由这里接到 `WorkshopApi.ui.toast(...)`，与 `notifyUser` 同一条通道。
                //   当前唯一用途：`SelfCaptureGate` 连续 3 帧判定"截到了自己"时提示用户
                //   「真透视暂不可用」（= 磨砂背景实质失效）。宿主对 `stderr` 是黑洞，
                //   故这条路必须存在，否则用户只会看到"背景突然变纯色"而毫无解释。
                //   ⚠️ 线程：`onWarn` 的调用点在**采集线程**上 ⇒ 窗口侧已负责切回 EDT
                //   （见 `IslandPanel.syncL3` 的 `onWarn`），这里只管把消息送给宿主。
                toastSink = { msg -> notifyUser(msg, warning = true) },
            ).also { window ->
                window.start()
                // `island.enabled = false` → 不显示（用户在设置里关掉了词岛）。
                // 与 applyKeepPlaceholder 同一读法：settings 为 null 时回退默认值 true，
                // 保证"设置系统坏了"不会把词岛一起关掉。
                val enabled = settings?.live?.boolean("island.enabled") ?: true
                window.setVisible(enabled)
            }
        }.onFailure {
            System.err.println("[CDL] 词岛窗口启动失败，插件以降级形态继续运行（无悬浮歌词）：$it")
            // 同时 toast —— 宿主对插件的 stdout/stderr 是**黑洞**（logback 只挂了
            // ConsoleAppender，而启动器是 GUI 子系统，句柄无处可去）。
            // 这条曾经掩盖了一个 **100% 必现**的启动期 NPE（见 recon/21-真机失败复核），
            // 代价是"词岛完全不出现且毫无提示"。故此处必须肉眼可见。
            notifyWarning("CDL：词岛窗口启动失败，已降级为无悬浮歌词。详情见插件日志。")
        }.getOrNull()
        // 托盘图标：**不受窗口成败影响**。
        //
        // ⚠️ 这里刻意**不写成 `islandWindow?.let { ... }`**。第一版正是那样写的，
        // 结果是"窗口创建失败 → 托盘也不装" —— 而托盘存在的意义**正是**
        // "窗口不可用时还能操作它"（词岛无边框、可穿透、不进 Alt+Tab，
        // 一旦窗口丢了就再没有入口）。兜底入口先于被兜底者失效，是逻辑倒置。
        //
        // 故把 `islandWindow`（可能为 null）直接传给 IslandTray：
        // 窗口为 null 时菜单里需要窗口的动作降级为"只改设置"，
        // 而"打开设置""关于与许可""退出"这些不依赖窗口的项照常可用。
        islandTray = runCatching {
            IslandTray(
                settings = settings,
                window = islandWindow,
                // 左键"暂停 / 继续"用它。与 `IslandWindow` 的播放控件**同一个实现**
                // （`HostPlaybackActions`）—— 两个入口走同一条宿主 API 调用链，
                // 不会出现"词岛上的按钮能暂停、托盘上的不能"这类不一致。
                playbackActions = HostPlaybackActions,
            ).also { tray ->
                tray.install()
                // 初始状态同步：插件可能在**播放中途**启动（用户先放歌、再启用插件），
                // 此时宿主不一定立刻推 `Playing` —— 图标会一直错显"已暂停"，
                // 直到用户碰一次播放/暂停才纠正。用时间轴的当前快照补上这一格。
                //
                // 快照缺失（首帧早于第一次宿主回调）时 `isPlaying` 为 false，
                // 与"已暂停"的默认图标一致，不会引入错误信息。
                tray.onPlaybackState(timeline?.snapshot()?.isPlaying ?: false)
            }
        }.onFailure {
            System.err.println("[CDL] 托盘图标启动失败（词岛本体不受影响）：$it")
            // 同词岛窗口：stderr 不可见，托盘又是"窗口丢了之后的唯一入口"，
            // 它失效必须让人知道，否则用户以为插件根本没装。
            notifyWarning("CDL：托盘图标启动失败。右键菜单将无法呼出。")
        }.getOrNull()
        // `null`：不把词岛窗口设为设置窗口的 owner。
        //
        // 词岛是 `JWindow`（无装饰悬浮件），设置窗口挂它做 owner 会继承
        // "always-on-top + 工具窗口" 的窗口样式 —— 设置窗口会浮在所有程序之上、
        // 且不进任务栏，用户切到别的程序后找不回来。这是不该有的副作用。
        // 独立 owner 反而让设置窗口以正常窗口形态出现（可最小化、在任务栏可见）。
        activeIslandWindow = null
        CdlPlugin.playback.attach(this)
    }

    override fun close() {
        if (closed) return
        closed = true
        // 先清静态桥再释放其余子系统：清掉之后打开设置窗口会看到只读形态，
        // 而不是拿到一个 settings 已置空、界面控件却还显示着旧值的窗口 ——
        // 后者会让用户误以为改动生效了。
        runCatching { SettingsRuntimeHolder.current = null }
        // 窗口也要清空：静态引用指着一个已销毁的窗口会让设置窗口把 owner
        // 定位到一个不存在的窗口上（表现的定位跑偏到屏幕角落）。
        activeIslandWindow = null
        CdlPlugin.playback.detach(this)
        // 托盘先于窗口卸载：托盘的右键菜单持有窗口引用（`IslandPopupMenu.build(settings, window)`），
        // 若先销毁窗口，托盘在消失前的最后一次点击会构造出引用已销毁窗口的菜单。
        // 顺序上"消费者先于被消费者销毁"是明确正确的方向。
        //
        // 而且托盘必须卸载 —— 否则插件卸载后系统托盘里会残留一个幽灵图标
        // （Windows 上要鼠标划过才消失，用户会以为插件崩了）。
        islandTray?.close()
        islandTray = null
        // 窗口先于时间轴释放：窗口的帧驱动（33 ms 定时器）会读快照并重绘，
        // 若先置空 timeline，窗口在停止前的最后一次 tick 会走 EMPTY 分支
        // （虽然安全，但"消费者先于生产者销毁"更直观，也避免 close 期间
        // 一帧无意义的重绘）。窗口自己会在 close 里停掉定时器并清空渲染缓存。
        islandWindow?.close()
        islandWindow = null
        // 订阅必须先于 settings.close() 反注册：settings 一旦关闭，
        // 订阅列表已被清空，句柄就再也关不掉了（虽然无害，但顺序上
        // "先撤订阅、再关被订阅者"是明确正确的方向）。
        //
        // 频谱：先反注册订阅（否则订阅回调会去碰一个正在关闭的进程），再停助手。
        // 先断通知源，再拆被通知的东西。
        runCatching { spectrumSubscription?.close() }
        // 与上一条同理：必须在 settings.close() 之前反注册。
        runCatching { couplingSubscription?.close() }
        spectrumSubscription = null
        spectrum?.let { runCatching { it.close() } }
        spectrum = null
        // 同上：探针开关的订阅句柄。不关就是"插件卸载后设置系统仍持有指向
        // 已销毁运行时的 lambda"，与 `spectrumSubscription` 是同一类泄漏。
        // 时间轴没有需要释放的原生资源，置空即可 —— 目的是让渲染层持有的引用
        // 尽快落到"无时间轴"这一明确状态，而不是继续读一份属于已销毁会话的位置。
        timeline = null
        // 时长加载器的后台线程必须显式停掉。虽然线程是 daemon（不会阻止进程退出），
        // 但它持有指向本运行时的 lambda 与一个正在读的文件句柄 —— 不关就是真实的资源泄漏。
        // 放在 timeline 之后：窗口（消费者）已经先关掉了，此处不再有人读它的结果。
        trackDuration.close()
        // 封面的后台线程池同理必须显式停掉（理由同 trackDuration，见 [coverArt] 的 KDoc）：
        // 它是 daemon 线程，不阻止进程退出，但会让已卸载插件的类加载器无法回收 ——
        // PF4J 反复重载插件时是真实的内存泄漏（CoverArtProvider.close 的 KDoc 详述）。
        coverArt.close()
        settings?.close()
        settings = null
        // ★ 2026-10-18（P0）：撤销 stderr 镜像（否则 classloader 与日志流随卸载泄漏）。
        //   放在最后：上面所有子系统的关闭日志仍进文件，收尾这一行之后才还原。
        runCatching { PluginLog.uninstall() }
        System.err.println("[CDL] stop")
    }

    /**
     * **找回词岛**（实例实现）：把词岛恢复成"看得见、能操作"的状态。
     *
     * 语义对齐既有的 `IslandPlugin.recover()` —— 三件事：
     * ① 打开词岛（`island.enabled = true`）；
     * ② 解除鼠标穿透（`island.interactive = false`）；
     * ③ 居中显示（清掉可能落在屏幕外的 `window.position`）。
     *
     * ## 为什么三件事都要做（缺一不可）
     *
     * 用户需要"找回"的典型情形是**词岛被拖到屏幕外**。但只看这一个原因会漏掉：
     * - 若此前用户关掉了 `island.enabled`，找回后**窗口仍不显示** → 用户以为没生效；
     * - 若此前开着鼠标穿透，找回后窗口显示但**鼠标点不到它** → 无法拖动，等于没找回。
     *
     * 故这是一个**复合动作**，不能只做居中。
     *
     * ## 写设置 vs 直接操作窗口
     *
     * 前两件事**走设置**（而非直接 `setVisible`）—— 因为渲染层与 Win32 层都以设置为准，
     * 只改窗口状态会让"设置值"与"实际表现"脱节：下次重启又变回去。
     * 第三件（居中）由窗口自己完成，因为它同时要清设置（见 `resetToPrimaryScreen` 的 KDoc）。
     */
    internal fun recover() {
        if (closed) return
        runCatching {
            val delta = mapOf("island.enabled" to true, "island.interactive" to false)
            settings?.publish(delta)
            settings?.apply(delta)
        }.onFailure {
            // 写设置失败不阻止"把窗口拉回来" —— 那是用户最迫切的需求。
            System.err.println("[CDL] 找回词岛：写设置失败（仍会尝试居中显示）：$it")
        }
        runCatching {
            islandWindow?.setVisible(true)
            islandWindow?.resetToPrimaryScreen()
        }.onFailure {
            System.err.println("[CDL] 找回词岛：窗口操作失败：$it")
        }
    }

    /**
     * 按 `spectrum.enabled` 同步频谱助手的**启停**。
     *
     * ## 为什么是"同步"而不是"启动时读一次"
     *
     * 用户可以在设置页随时开关它。只在启动时读一次的话，
     * 「开了但没反应 / 关了还在后台跑」**两种都会出现** ——
     * 前者正是本项目反复出现的"死设置"形态（BUG-025 / BUG-043），
     * 后者更糟：用户以为关掉了，实际还有个进程在跑。
     *
     * ## 幂等（硬要求）
     *
     * 设置变更会被**高频**触发（宿主滑杆每次 `onValueChange` 都写盘）。
     * 本方法必须能承受"用同样的值反复调用"：已经在想要的状态就**什么都不做**，
     * 否则滑杆每动一下都会重启一次助手进程。
     *
     * ## 失败不抛
     *
     * `ProcessSpectrum.start()` 内部已把失败原因记进 `unavailableReason()`，
     * 渲染层照常不画频谱 —— **音乐不受影响**（故障域隔离，见该类的类 KDoc）。
     */
    private fun syncSpectrum() {
        // ⚠️ 2026-09-20 修：回退值原为 `false`，与注册表 `default = true` 不一致（同上）。
        val enabled = runCatching { settings?.live?.boolean("spectrum.enabled") }.getOrNull() ?: true
        // ★ 2026-09-30（封面态水位，`recon/67` §9-T 定案 ①）：**本态强制开启**。
        //
        //   理由：封面态的「水位波动」数据源就是频谱（§2.4.7 B），而本态**画面
        //   上不显示频谱** ⇒ 用户很可能顺手把「频谱」开关关掉 ⇒ 水位纹丝不动 ⇒
        //   表现为"设了没反应"（本项目最多次的缺陷形态）。
        //
        //   ⚠️ 与 D1/D2 **同一处置手法**：**视为开启，但绝不改键值** ——
        //      改键值就是偷改用户配置（用户回头会发现"我没开过频谱，怎么自己开了"）。
        //      故这里只改**本次判定**的入参，`spectrum.enabled` 的落盘值原样不动。
        //
        //   ★ 判据走 `coverShapeForcesSpectrum`（`IslandPolicy` 里的公开纯函数），
        //     不在这里就地写 `== "COVER_ONLY"` —— 判据只此一处，且可被探针断言。
        // ★★★ 2026-10-05 **BUG-157**：**插槽档也要数据源**（即使总开关关着）——
        //   否则"绘制侧会画、数据侧没起" ⇒ 插槽频谱**静默空白** ✗
        //   ★ 判据走纯函数 `needsSpectrumData`（与 `shouldDrawSpectrumBand` **语义对称**）✓
        // ⚠️ 取值照 `shapeTypeNow()` 的既有写法（`descriptor` 可空 + `live.text`）——
        //    `live.text` 吃 `SettingDescriptor` 而**不是**键名 ✓
        val slotKeyNow = textNow("island.right_slot.component") ?: "NONE"
        val anchorNow = textNow("spectrum.anchor") ?: "BOTTOM"
        val wanted = needsSpectrumData(slotKeyNow, anchorNow, enabled) ||
            coverShapeForcesSpectrum(shapeTypeNow())
        val current = spectrum
        when {
            wanted && current == null -> {
                val created = ProcessSpectrum(context.pluginPath)
                spectrum = created
                runCatching { created.start() }
                    .onFailure { System.err.println("[CDL] 频谱助手启动失败：$it") }
            }

            !wanted && current != null -> {
                spectrum = null
                runCatching { current.close() }
                    .onFailure { System.err.println("[CDL] 频谱助手停止失败：$it") }
            }
            // 其余两种情形（要开且已开 / 要关且已关）→ 无事可做。这就是幂等。
        }
    }

    /**
     * 当前 `shape.type`（外观形态）的 wire 值 —— 读不到时回退 `"SQUARE"`。
     *
     * ## 为什么回退是 `"SQUARE"` 而不是 `"COVER_ONLY"`
     *
     * 回退值会被 [coverShapeForcesSpectrum] 消费，而它 `true` ⇒ **多起一个进程**。
     * 故回退必须落在**不会引入额外进程**的那一侧 —— 与"失败降级"的一贯方向一致
     * （拿不准时选代价小的那个）。
     *
     * ## 为什么需要它（而不是复用 `IslandWindow` 的 `shapeTypeNow`）
     *
     * `IslandWindow` 的那个是**私有**且依赖窗口自己的 `snapshot` ——
     * 本类在**窗口装配之前**就要调 [syncSpectrum]（见 `start()` 的调用顺序），
     * 那时窗口可能还不存在。⇒ 本类自己读设置，与窗口无关。
     */

    private fun shapeTypeNow(): String =
        runCatching {
            // ⚠️ `SettingRegistry.descriptor(key)` 返回**可空**（未知 key ⇒ null），
            //    而 `live.text(descriptor)` 要非空 —— 照 `SettingsSnapshot.boolean(key)`
            //    那个便捷重载的既有写法（`let { if (d == null) … }`）。
            val d = SettingRegistry.descriptor("shape.type")
            if (d == null) null else settings?.live?.text(d)
        }.getOrNull() ?: "SQUARE"

    /** 按**键名**读一个文本设置（读不到 ⇒ `null`）。★ 与 [shapeTypeNow] 同一写法。 */
    private fun textNow(key: String): String? =
        runCatching {
            val d = SettingRegistry.descriptor(key)
            if (d == null) null else settings?.live?.text(d)
        }.getOrNull()

    /**
     * 订阅 `spectrum.enabled` 的变更。
     *
     * 与 [subscribeKeepPlaceholder] 同一模式：订阅回调可能在任意线程，
     * 而 [syncSpectrum] 内部只碰 `@Volatile` 字段与进程句柄，不需要切 EDT。
     */
    private fun subscribeSpectrum() {
        val runtime = settings ?: return
        spectrumSubscription = runtime.subscribe { _, _ ->
            runCatching { syncSpectrum() }
                .onFailure { System.err.println("[CDL] 频谱开关同步失败：$it") }
        }
    }

    /**
     * 订阅设置变更，**解开被门控项的开关**（见 [SettingCoupling]）。
     *
     * ## 为什么需要它（真机取证）
     *
     * 用户报「死设置项变多了」。审计结论：真正没有消费点的只有 1 项，
     * 但有一批项**挂在另一个开关后面**，门没开时拖它零变化。
     * 真机日志（`super-island.log.1`）里用户拖了
     * `spectrum.secondary.opacity_percent` **128 次**、`spectrum.bar_pitch_px` **72 次** ——
     * 而这两项当时都被各自的门控挡住，**一次都没生效**。
     *
     * 规则本身是纯函数（[SettingCoupling.releasePatch]），本方法只负责接线。
     *
     * ## ★ 为什么必须 `invokeLater` 而不能直接 `apply`
     *
     * `SettingsRuntime.notifySubscribers` 是
     * `for (subscriber in subscribers) { subscriber(...) }` —— **直接遍历**。
     * 在订阅回调里调 `apply` 会**重入** `notifySubscribers`，
     * 于是内层派发在外层循环还没跑完时就开始了：同一批订阅者被嵌套调用，
     * 顺序与"一次变更一次派发"的假设不符（渲染层与菜单层会看到交错的快照）。
     *
     * `invokeLater` 把写入**排到当前派发之后**，重入被结构性地排除；
     * 顺带把"可能在宿主回调线程"的调用点统一挪到 EDT ——
     * 与 `SettingsRuntime.apply` 在自绘工具窗里的调用线程一致。
     *
     * ## 失败不静默
     *
     * 联动写不进去时打一行并**带上键名**（本项目纪律：告警要么指名道姓、要么别打）。
     */
    private fun subscribeSettingCoupling() {
        val runtime = settings ?: return
        couplingSubscription = runtime.subscribe { snapshot, change ->
            // ── ★ BUG-054（P0-3）：一次回灌很多项 ⇒ **绝不联动写盘** ──────────────
            //
            // 联动要表达的是「**用户手点了一个门控控件**」—— 一次最多 1~2 项。
            // 一次 `EXTERNAL` 回灌十几项 = 读取异常 / 设置页把旧快照整份写回。
            //
            // ⚠️ 而下面的 `runtime.apply(patch)` 是**把可疑快照整份写盘的唯一执行者**
            //    （`SettingsRuntime.apply` → `store.save(live)` 整份覆盖）。
            //    ⇒ 这一条正是把"读异常"升级成"**永久数据损坏**"的那一步，必须掐掉。
            //
            // 阈值取 3：门控控件一次最多带 1~2 项（如取色器一次写 4 个分量，
            // 但那是 `APPLY` 不是 `EXTERNAL`），3 是宽裕分界。
            // ★★ 2026-10-18：**预设应用不触发联动** —— 见 `SettingChange.Kind.PRESET`。
            //   否则预设刚写好的门控键会被联动再改一遍，用户点了预设却得到别的样子。
            if (change.kind == SettingChange.Kind.PRESET) return@subscribe
            if (change.kind == SettingChange.Kind.EXTERNAL && change.changedKeys.size > 3) {
                return@subscribe
            }
            // `valueOf` 只被"按值决定写什么"的规则用到（如「歌词行数」→「显示翻译」）。
            // 读不到 → 返回 null → 那条规则**什么都不写**（不猜用户的意思）。
            val patch = SettingCoupling.releasePatch(change.changedKeys) { key ->
                SettingRegistry.descriptor(key)?.let { descriptor ->
                    runCatching { snapshot.text(descriptor) }.getOrNull()
                }
            }
            if (patch.isEmpty()) return@subscribe
            SwingUtilities.invokeLater {
                runCatching { runtime.apply(patch) }
                    .onSuccess {
                        // ── ★ 自动改设置**必须留痕**（2026-09-21，用户要求）──────────
                        //
                        // 用户原话：「…**不要误导用户**」。
                        //
                        // 自动联动会**改一个用户没碰的开关**。若日志里一个字都没有，
                        // 事后没人能解释"这个开关是什么时候、被谁改的" ——
                        // **那才是真正的误导**（用户只会以为是自己手滑）。
                        //
                        // 故每次成功写入都打一行，**同时写出触发键与被改的键值** ——
                        // 与"告警要么指名道姓、要么别打"同一条纪律。
                        // （失败路径本来就有日志，见下面 `onFailure`。）
                        System.err.println(
                            "[CDL] 设置联动：碰了 ${change.changedKeys.sorted()} → 自动写入 $patch",
                        )
                    }
                    .onFailure {
                        System.err.println(
                            "[CDL] 设置联动写入失败（${patch.keys}）：$it",
                        )
                    }
            }
        }
    }




    /**
     * 宿主回调的统一落点（由 [CallbackSink] 在**串行化后**调用，且已保证不持有 Sink 的锁）。
     *
     * P0 阶段：只记录。**注意**：这里**不在 EDT 上** —— 是宿主的回调线程。
     * 因此本方法**绝不能直接触碰 Swing 组件**；P1 起应把事件投给 `PlaybackTimeline`
     * （其内部自行用 `@Volatile` 快照 + 渲染帧读取的模型，见大纲 §10）。
     *
     * @param kind 事件类别（`track` / `state` / `playing` / `position` / `seek` / `line`）
     * @param value 已格式化的值（P0 仅用于日志；P1 会替换为结构化参数）
     */
    internal fun onPlaybackEvent(kind: String, value: String) {
        if (closed) return
        lastEvent.set("$kind=$value")
    }

    /**
     * 结构化回调落点。
     *
     * ## 为什么除了 [onPlaybackEvent] 还要有这个方法（而不是复用它的 String 参数）
     * [onPlaybackEvent] 拿到的 `value` 是 [CallbackSink] 格式化后的字符串，
     * 例如 track 事件是 `"标题 — 艺术家"`。这足以做日志，但**丢掉了时间轴真正需要的字段**：
     * 曲目的 `path` / `album` 被拼没了，歌词行的 `startTime` / `endTime` / `lyricsCells`
     * （逐字）也被压扁成一句文本。时间轴要算位置、选行、做逐字高亮，这两类字段都是必需的，
     * 且**无法从字符串里还原**（标题里本来就可能含 " — "，反解不可靠）。
     *
     * 因此保留 [onPlaybackEvent] 的原有契约（日志与 [lastEvent] 不受影响），
     * 另开这一条结构化通道承载富数据。两条通道并行，互不干扰。
     *
     * ## 线程与性能
     * 由 [CallbackSink.dispatch] 在**宿主回调线程**上调用（不是 EDT）。
     * 因此这里**不做任何 Swing 操作、不 invokeLater** —— 时间轴内部用 `@Volatile`
     * 写入自己的字段，本身就是无锁且线程安全的，无需切线程。
     * 切线程反而有害：会把状态更新推迟到不确定的时刻，并在高频位置回调下堆积任务。
     */
    internal fun onPlaybackCallback(event: CdlRuntime.StructuredEvent) {
        if (closed) return
        val target = timeline ?: return
        when (event) {
            is StructuredEvent.Track -> {
                val path = event.item.path.orEmpty()
                target.trackChanged(
                    title = event.item.title.orEmpty(),
                    artist = event.item.artist.orEmpty(),
                    album = event.item.album.orEmpty(),
                    path = path,
                )
                // 时长读取：**在这里发起，不在渲染帧里**。
                //
                // 为什么不在渲染侧按需读：渲染是 EDT 上的每帧路径，而读文件是磁盘 I/O
                // （网络盘上百毫秒级）—— 放在那里会把每一帧都卡住，表现为词岛整体卡顿。
                // 换曲是**离散事件**，正是发起一次读取的正确时机。
                //
                // 传 `path` 而不是让 loader 自己去取快照：这里的 `path` 与刚写进时间轴的
                // 是同一个值，用它就不存在"读快照时已被下一首覆盖"的竞态。
                // 换曲：清掉"本曲已探过整轴"的标记 —— 否则会把**上一曲**的歌词轴
                trackDuration.load(path)
                wakeWindow()
            }

            is StructuredEvent.Position -> target.positionChanged(event.positionMs)

            is StructuredEvent.Seek -> {
                target.seek(event.positionMs)
                wakeWindow()
            }

            is StructuredEvent.Playing -> {
                target.playingChanged(event.playing)
                // 托盘图标随播放态切换（用户 2026-09-16 要求）。
                //
                // ⚠️ **必须放在这里而不是渲染帧里**：`Playing` 是**离散状态变化事件**，
                // 而帧是每秒 30 次的连续路径 —— 在帧里更新图标会让
                // `TrayIcon.setImage` 被高频调用（Windows 每次都要重绘托盘），
                // 而 `onPlaybackState` 内部虽有"先比再改"的守卫，
                // 但那是**第二道**防线，第一道是"别在帧路径上问这件事"。
                islandTray?.onPlaybackState(event.playing)
                wakeWindow()
            }

            is StructuredEvent.State -> {
                target.stateChanged(event.state.name)
                wakeWindow()
            }

            is StructuredEvent.Line -> {
                val line = event.line
                // ── 2026-09-19：完整歌词轴探针已**整体移除** ──────────────────
                //
                // 原来这里是三态：`探针接管 / line == null / 逐行累积`。
                // 探针的**唯一用途**是补出宿主不推送的"未唱行"，而未唱行已不显示
                // （用户 2026-09-17 拍板），故它只剩反射读宿主内部的开销、无可见收益。
                //
                // 用户 2026-09-19 要求清理：「鉴于已取消显示未唱行，「读取宿主完整时间轴」
                // 这个设置也可以去除吧？那些因设置项变动**无效的关联功能**你还没有清理干净。」
                // → 设置项 + 字段 + 订阅 + 接管函数**全部删除**，只剩这一条逐行累积路径。
                //
                // `host/HostLyricsProbe.kt` **文件保留**（未接线）：若将来恢复未唱行，
                // 接线点就在这里，重新引入即可。
                if (line == null) {
                    // 宿主用 null 表示"这段没有歌词"（过门间隙）。
                    target.lineCleared()
                } else {
                    // 原文 / 译文分离（BUG-015）。
                    //
                    // 原记（recon/24 §1）：`pureMainText` 只含原文，译文另由 `pureSubText`
                    // 承载（4 参构造 `(start, end, cells, sub)` 的语义）。据此只实现了
                    // 「`pureSubText` 是主串后缀 → 剥掉它」这一条路。
                    //
                    // 实为（2026-09-17 真机日志 `super-island.log`，86 条 `callback line:`
                    // 全量统计）：**`sub=<null>` 出现 86 次、非 null 出现 0 次** ——
                    // 对本用户实际使用的歌词源，`pureSubText` **恒为 null**，
                    // 译文被直接拼进了 `pureMainText`，以**半角空格**分隔：
                    //   main=そんな日はもうたくさんだ 这样的时光我受够了 | sub=<null> | cells=1
                    // 于是那条后缀剥离路**永不命中** → 译文行永不绘制，
                    // 原文行的高亮把译文一起染上（用户报的「译文仍和外文歌词混杂」）。
                    //
                    // 故改走 `splitBilingualLine`：先试后缀剥离（宿主真给了译文时更可靠），
                    // 没给就退回**按书写系统**的启发式切分（前含假名、后不含假名的首个半角空格）。
                    // 规则、理由与**已知误切风险**见该函数的 KDoc。
                    //
                    // ── 2026-10-18 修：`pureSubText` 非空时必须**直接信它** ────────────
                    //
                    // 原记（上一行的调用方式）：`splitBilingualLine(main, sub)` 会正确处理
                    // "宿主给了译文"的情况。
                    //
                    // 实为：该函数只有**两条**路，而"宿主给了译文、但译文**不是主串尾缀**"
                    // 这个输入**两条都不满足** —— 后缀剥离要求尾缀、书写系统切分只看主串
                    // 内部的空格、压根不看 `sub` 参数 ⇒ **译文被静默丢掉**。
                    // 取证：`HostLyricAxisProbe` §3.1
                    // `splitBilingualLine("Hello world", "你好世界")` → 译文 `null`。
                    //
                    // 真机 86/86 都是 `sub=<null>`，所以这条路**从没被走到过** ——
                    // 属于"条件不满足所以从没跑到"的死代码缺陷，改它对现有观感零风险。
                    // 判据见 `separatedMainText` / `separatedTranslation` 的 KDoc。
                    val mainText = separatedMainText(line.pureMainText, line.pureSubText)
                    val subText = separatedTranslation(line.pureMainText, line.pureSubText)

                    // 逐字来源：LyricsLine.Cell(startTime, endTime, text)。
                    // ⚠️ 编译器已确认 `lyricsCells` 是**非空** List（不是平台类型），
                    // 因此这里不做判空。但它**可能为空列表**——空列表与"单 cell 覆盖整行"
                    // 两种情况都由 LyricLine.effectiveWords 的校验统一兜住，
                    // 上层据此决定是否开启逐字高亮。
                    val rawWords = line.lyricsCells.map {
                        LyricWord(it.startTime, it.endTime, it.text.orEmpty())
                    }
                    // 逐字必须裁到**纯原文**长度：cells 拼出的是含译文的整串，
                    // 而 effectiveWords 要求"拼接精确等于 text"，不裁就整行 fail-closed。
                    // 裁剪同时把属于译文的那部分 cell 整体剔除 ——
                    // 译文的时间不属于原文的逐字进度（见 narrowWordsToText 的 KDoc）。
                    //
                    // ── 译文的逐字（2026-09-17 新增，用户诉求「原文和译文**分别**逐字高亮」）──
                    // 同一批 cells 的**尾部那一段**就是译文的逐字（因为 cells 拼出的正是
                    // 含译文的整串）。这里把它单独切出来交给 `translationWords`，
                    // 而不是让译文去借原文的时间轴 —— 借来的时间轴会按原文的字数切译文，
                    // 把高亮落在错误的字上。规则与 fail-closed 理由见
                    // `narrowWordsToTranslation` 的 KDoc。
                    // 真机 `cells=1`（86/86）下这里返回空列表 → 译文退回整行进度高亮，
                    // **不会伪造任何时间轴**。
                    target.lineChanged(
                        startMs = line.startTime,
                        endMs = line.endTime,
                        text = mainText,
                        translation = subText,
                        words = narrowWordsToText(rawWords, mainText),
                        translationWords = subText?.let {
                            narrowWordsToTranslation(rawWords, mainText, it)
                        },
                    )
                }
                wakeWindow()
            }

            // ── 整轴（`onLyricsLinesUpdated`，宿主 1.19.0）───────────────────
            //
            // ## 这条分支存在的意义：**把猜测从主干上摘掉**
            //
            // `Line` 分支里那一整段（译文拼接启发式、`isTranslationPair` 配对、
            // `PairRole` 主副角色、`isTranslationContinuation` 续行）之所以存在，
            // 全是因为逐行推送**只给一行**。整轴把每行的 `pureMainText` 与
            // `pureSubText` 一次性给全，那批推断在这里**没有对应物** ——
            // 于是这条路径上的逐字准确度问题不是"调得更准"，而是"**不存在**"。
            //
            // ## 逐字裁剪**必须复用**同一套（不是重写）
            //
            // `narrowWordsToText` / `narrowWordsToTranslation` 的 fail-closed
            // （拼接必须精确等于文本，否则整组作废退回整行进度）已被
            // `KaraokeSweepProbe` / `TranslationKaraokeProbe` 等探针钉死。
            // 若在整轴路径上另写一份裁剪，两份规则将来必然分叉，
            // 而分叉之后**只有其中一条路径会被探针覆盖到** —— 正是本项目
            // 反复记录的那类缺陷。故这里**只做一次翻译，裁剪交给同一批函数**。
            is StructuredEvent.Lines -> {
                val rawLines = event.lines
                // 宿主类型 → 纯数据。过滤规则（空行 / 提示行 / 倒置区间 / 重复键）
                // 全在 `bilingualLinesFrom` 里，探针可直接断言它。
                val plain = bilingualLinesFrom(
                    rawLines.map {
                        HostLyricLine(
                            startMs = it.startTime,
                            endMs = it.endTime,
                            text = it.pureMainText,
                            translation = it.pureSubText,
                            cells = it.lyricsCells.map { c ->
                                HostLyricCell(c.startTime, c.endTime, c.text.orEmpty())
                            },
                        )
                    }
                )
                if (plain.isEmpty()) {
                    // 空轴 = 换曲瞬间 / 本曲无歌词。**不清空**旧轴，理由见
                    // `PlaybackTimeline.documentCleared` 的 KDoc（避免换曲间隙闪空）。
                    target.documentCleared()
                } else {
                    val lines = plain.map { p ->
                        val rawWords = p.cells.map { LyricWord(it.startMs, it.endMs, it.text) }
                        LyricLine(
                            startMs = p.startMs,
                            endMs = p.endMs,
                            text = p.text,
                            translation = p.translation,
                            words = narrowWordsToText(rawWords, p.text),
                            translationWords = p.translation?.let {
                                narrowWordsToTranslation(rawWords, p.text, it)
                            },
                        )
                    }
                    // ⚠️ 这里**必须排序**，虽然 `bilingualLinesFrom` 有意不排：
                    //   `LyricDocument.lineAt` 是**二分查找**，它要求 `startMs` 非降序。
                    //   "保留原顺序"的目的是**不掩盖宿主乱序**这个缺陷 ——
                    //   而这里排序恰恰是最容易被误读为"掩盖"的地方，故写明：
                    //   排序是 `lineAt` 的**前置条件**，不是对宿主数据的修正；
                    //   宿主乱序与否由 `CallbackSink` 的 `lines:` 日志（首末行时间）暴露。
                    val sorted = lines.sortedWith(compareBy({ it.startMs }, { it.endMs }))
                    target.documentReplaced(LyricDocument(sorted, true))
                }
                wakeWindow()
            }
        }
    }

    /**
     * 唤醒窗口的帧驱动（若有窗口）。
     *
     * ## 为什么需要这个显式唤醒（而不是让窗口自己轮询）
     *
     * 窗口的帧驱动在发现"暂停了"时会**自停**（见 `IslandWindow.onFrameTick`），
     * 因为暂停期间画面是静止的、继续跑是每秒 30 次的纯空转。
     * 但自停之后，"重新开始播放"这件事**没有任何人通知窗口** ——
     * 若只靠设置变更或窗口启动来启动它，用户在播放器里按下播放后歌词不会动。
     *
     * 故在**改变播放语义的离散事件**之后显式唤醒一次。
     *
     * ## 为什么不在这里直接触 Repaint
     *
     * 唤醒只做"确保定时器在跑"这一件事，真正的重绘决策留在窗口的 tick 里
     * （它要按 isPlaying / docVersion / 位置跳变分别判定）。把决策分散到
     * 两处会让"什么情况下重绘"有两个不一致的答案 —— 这正是要避免的。
     *
     * ## 线程
     * 本方法在**宿主回调线程**上被调用（见 [onPlaybackCallback] 的说明），
     * 而 `IslandWindow.onPlaybackStateMaybeChanged` 内部会自己切到 EDT，
     * 故这里不必关心线程。
     * 但**不能**对 `Position` 事件也调用它：位置回调约 1 秒一次且频率不定，
     * 每次投递一个 EDT 任务会在这个高频路径上堆积任务队列（见上面的调用点）。
     */
    private fun wakeWindow() {
        val window = islandWindow ?: return
        runCatching { window.onPlaybackStateMaybeChanged() }
            .onFailure { System.err.println("[CDL] 唤醒词岛帧驱动失败：$it") }
    }

    /**
     * 把一条"用户可感知的降级"同时报到 stderr 与宿主 toast。
     *
     * ## 为什么必须有 toast 这一路
     *
     * 宿主对插件 stdout/stderr 是**黑洞**：logback 只挂了 `ConsoleAppender`，
     * 而宿主启动器是 **GUI 子系统（PE subsystem = 2）**，标准句柄没有去处。
     * 只写 stderr 等于"没人看得见"。
     *
     * 这不是理论风险 —— 它实际掩盖过一个 **100% 必现**的启动期 NPE：
     * `IslandWindow` 的属性初始化顺序问题导致 `IslandPanel` 构造期读到 `null` 快照，
     * 抛 NPE 后被 `runCatching` 静默降级，**词岛完全不出现且毫无提示**
     * （取证：`recon/21-真机失败复核.md`）。
     *
     * 故纪律：**凡是"功能降级但插件仍活着"的失败，都必须 toast。**
     * 用 `runCatching` 包住是为了不让"提示本身失败"再引发一次崩溃。
     */
    private fun notifyWarning(message: String) {
        runCatching {
            WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
        }.onFailure {
            System.err.println("[CDL] 发送提示失败（提示内容：$message）：$it")
        }
    }

    /**
     * 富数据的结构化事件。
     *
     * 定义在这里、而不是让 [dev.cdl.playback.PlaybackTimeline] 直接依赖宿主类型：
     * 时间轴是纯逻辑（可脱离宿主单独验证），不应把 `PlaybackExtensionPoint` 的类型
     * 渗透进去。翻译发生在本文件的 [onPlaybackCallback]，这是组合根的职责。
     */
    internal sealed interface StructuredEvent {
        data class Track(val item: PlaybackExtensionPoint.MediaItem) : StructuredEvent
        data class Playing(val playing: Boolean) : StructuredEvent
        data class State(val state: PlaybackExtensionPoint.State) : StructuredEvent
        data class Position(val positionMs: Long) : StructuredEvent
        data class Seek(val positionMs: Long) : StructuredEvent
        data class Line(val line: PlaybackExtensionPoint.LyricsLine?) : StructuredEvent
        data class Lines(val lines: List<PlaybackExtensionPoint.LyricsLine>) : StructuredEvent
    }

    /** 最近一次宿主回调的可观测痕迹（调试用；`null` 表示尚未收到任何回调）。 */
    internal val lastEvent = java.util.concurrent.atomic.AtomicReference<String?>(null)

    companion object {
        //
         // 设置页按钮入口的落点（`preference_config.json` 的 `on_click` 目标）。
         //
         // ## 为什么只转发不实现
         //
         // 窗口的构造、幂等前置、订阅退订等全部细节都在 `settingsui` 包内
         // （见 `showSettingsWindow` 的 KDoc）。本方法作为组合根只做一件事：
         // **把"词岛窗口在哪"这个只有 `host` 知道的信息惰性传下去**。
         //
         // ## 为什么 owner 用惰性 provider 而不是直接传窗口
         //
         // 词岛窗口可能已被用户关掉或尚未创建。若在调用点先取好再传进去，
         // 取的时刻与用的时刻之间它可能已经销毁，`JDialog` 会拿一个死窗口当 owner
         // 而定位到屏幕角落。provider 让"取"发生在真正要用的那一刻。
         //
         // ## 线程
         //
         // `showSettingsWindow` 内部自行 `SwingUtilities.invokeLater` 并包裹了
         // 异常处理（含非 EDT 调用者的情况 —— 宿主按钮的点击线程不确定），
         // 因此这里**不再套一层 onEdt**，避免双重投递把异常吞在不同层级。
         //
        /**
         * 打开（或前置）**取色器工具窗**（`preference_config.json` 的 `on_click` 目标）。
         *
         * ## 为什么是"工具窗"而不是"设置窗口"（2026-09-16 结构性改造）
         *
         * 用户诉求：「高级设置的独立窗口（**不要此独立窗口**）没有并入 SPW 设置页中」。
         * 故 40 项设置**已全部**声明进 `preference_config.json`（SPW 设置页原生渲染），
         * 词岛右键菜单另有「高级设置」子菜单承载高频项。
         *
         * **仅剩**颜色与字体族留在自绘窗 —— 因为宿主的 `type` 只有 5 种、**没有取色器、
         * 没有字体选择器**（`recon/18` §A / `recon/20` §A.7，字节码穷尽确证）。
         * 这两个工具窗**不是设置页的替代品**，而是那两类开放取值域的**唯一交互手段**。
         *
         * ## 为什么按颜色键分成三个入口
         *
         * `on_click` 的反射契约是**无参静态方法**（`recon/20` §A.3），
         * 一个按钮**无法**区分"要改哪个颜色" —— 故三个颜色各需一个 `@JvmStatic` 方法。
         *
         * ## 线程
         *
         * [ToolWindows.openColor] 内部自行 `SwingUtilities.invokeLater` 并包裹异常
         * （宿主按钮的点击线程不确定），故这里**不再套一层 onEdt** ——
         * 双重投递会把异常吞在不同层级。
         */
        internal fun openColorTool(key: String) {
            ToolWindows.openColor(key)
        }

        /**
         * 打开**四键制颜色组**的取色器（`prefix.r/.g/.b/.lightness`）。
         *
         * 与 [openColorTool] 并存：单键制（`spectrum.color`）走前者，
         * 三个歌词色（v0.20 方案 A 拆成四根滑杆）走本方法。
         * 为什么必须分开见 `ToolWindows.openColorGroup` 的 KDoc。
         */
        internal fun openColorGroup(prefix: String, title: String) {
            // 与 [pickFont] 同构：Swing 操作走 EDT，失败时用 toast 让**用户能看见**。
            // 为什么必须在这里兜底（而不是在 `CdlPlugin` 的入口里）：
            // 那个入口是 `@JvmStatic`、拿不到 `PluginContext`，**用不了 `WorkshopApi`**。
            onEdt {
                runCatching { ToolWindows.openColorGroup(prefix, title) }
                    .onFailure { error ->
                        System.err.println("[CDL] 取色器打开失败（$title）：$error")
                        // 宿主 API 唯一能让用户看见失败的通道。再套一层 runCatching：
                        // toast 自身也可能因宿主状态异常而抛，而"弹提示失败"绝不该掩盖上面那条日志。
                        runCatching {
                            WorkshopApi.ui.toast(
                                "取色器打开失败：${error.message ?: error.javaClass.simpleName}",
                                WorkshopApi.Ui.ToastType.Warning,
                            )
                        }
                    }
            }
        }

        /** 打开（或前置）**字体选择工具窗**。语义同上。 */
        internal fun openFontTool() {
            ToolWindows.openFont()
        }

        /**
         * **字体三来源选择器**的静态入口（`preference_config.json` 的 `on_click` 目标）。
         *
         * ## 为什么这里必须自己包 try/catch（而不是信任宿主）
         *
         * `recon/20` §A.3 已确证：`on_click` 的整段反射被宿主 `try/catch(Exception)`
         * 包住，只记 `VLog`（**用户不可见**）。这意味着**任何**从本方法逃逸的异常，
         * 用户侧的表现是"点了按钮完全没反应，也没有任何提示" —— 这是最难归因的缺陷类型。
         *
         * 本方法内部调的是 Swing 组件构造 + 文件对话框 + 字体解析，三者都可能抛
         * （无头环境、字体子系统异常、用户选了坏文件）。故**全段包住**，
         * 失败时用 `WorkshopApi.ui.toast` 明确告知用户（宿主 API 里有 toast，
         * 这是"用户能看见失败"的唯一通道）。
         *
         * ## 为什么 `owner = activeIslandWindow`
         *
         * `on_click` 拿不到宿主窗口，但**我们自己知道**词岛窗口在哪
         * （[activeIslandWindow]）。用词岛作 owner 能让对话框在词岛附近居中弹出，
         * 而不是飞到主屏角落 —— 它同时保证了与设置窗口入口的**定位一致**
         * （`openSettingsWindow` 用的是同一个 provider）。
         *
         * ⚠️ 该字段当前恒为 `null`（见其 KDoc：窗口装配尚未登记），
         * 此时 `setLocationRelativeTo(null)` 是合法调用，表现为屏幕居中，不会崩。
         *
         * ## 线程
         *
         * `on_click` 的调用线程**不确定**（宿主渲染层的点击回调，`recon/20` 未确证
         * 是 EDT 还是 Compose 的线程）。而 `FontSourceDialog.show` 构造 Swing 组件，
         * **必须**在 EDT 上。故这里显式投递到 EDT —— 不依赖"宿主恰好是 EDT"这个假设。
         */
        internal fun pickFont() {
            onEdt {
                runCatching { showFontSourceDialog(lastIslandWindow()) }
                    .onFailure { error ->
                        System.err.println("[CDL] 字体选择器失败：$error")
                        // 宿主 API 唯一能让用户看见失败的通道。
                        // 再套一层 runCatching：toast 自身也可能因宿主状态异常而抛，
                        // 而"弹提示失败"绝不该掩盖"字体选择器失败"这条日志。
                        runCatching {
                            WorkshopApi.ui.toast(
                                "字体选择器打开失败：${error.message ?: error.javaClass.simpleName}",
                                WorkshopApi.Ui.ToastType.Warning,
                            )
                        }
                    }
            }
        }

        /**
         * **找回词岛**的静态入口（`preference_config.json` 的 `on_click` 目标）。
         *
         * ## 为什么只转发不实现
         *
         * 恢复动作需要访问当前运行时的 `settings` 与 `islandWindow`（都是实例字段），
         * 而本方法是静态的。故从 `CdlPlugin.runtime` 取实例后转发 ——
         * 这是本项目"静态入口拿不到实例"的既定解法（同 `playback` 静态桥）。
         *
         * ## 实例不存在时为什么静默
         *
         * 这条链路只在用户点按钮时触发，而按钮**只能从 SPW 设置页点到** ——
         * 能点到按钮，说明插件已加载、`start()` 已跑过，实例**必然存在**。
         * 真的为 `null`（如插件正在卸载的竞态）时无事可做，打一行日志即可，
         * 不该弹窗打断用户（与 `openSettingsWindow` 的"弹提示"不同 ——
         * 那里是用户主动要打开设置，值得告知；这里只是恢复动作，静默失败更合适）。
         */
        internal fun recoverIsland() {
            val target = CdlPlugin.runtime
            if (target == null) {
                System.err.println("[CDL] 找回词岛：运行时不存在（插件未启动或正在卸载）")
                return
            }
            target.recover()
        }

        /**
         * 由 `start()` 维护，供工具窗（取色器 / 字体选择器）提供 `owner`。
         * 当前恒为 `null`（见 `start()` 的说明：窗口装配尚未登记到此处）。
         */
        @Volatile
        internal var activeIslandWindow: java.awt.Window? = null

        /**
         * 词岛窗口，作为工具窗（取色器 / 字体选择器）的 `owner`。
         *
         * ## 为什么是 `internal` 而不是 `private`（本轮修正，2026-09-16）
         *
         * `settingsui/ToolWindows.kt` 里给 `JDialog` 定位那处需要它 ——
         * 原为 `private` 时**编译失败**（`Cannot access 'fun lastIslandWindow()':
         * it is private in 'CdlRuntime.Companion'`）。
         *
         * 可见性收到 `internal` 是**有意的最小放开**：`settingsui` 与 `host`
         * 同处一个 Gradle module，故 `internal` 恰好够用，且**不对外暴露**
         * （插件包外不可见）。不改 `public` 是因为词岛窗口是实现细节，
         * 没有任何外部调用方该拿到它。
         *
         * 当前恒为 `null`（见 [activeIslandWindow] 的说明：窗口装配尚未登记）；
         * 此时 `setLocationRelativeTo(null)` 是合法调用，表现为屏幕居中，不会崩。
         */
        internal fun lastIslandWindow(): java.awt.Window? = activeIslandWindow

        internal fun showAbout() {
            onEdt {
                JOptionPane.showMessageDialog(
                    null,
                    "CDL for SPW（测试版）\n\n" +
                        // ★★ 2026-10-17：**按当前代码实际重写**。
                        //   旧文案有两个问题：① 显示 `CHANGE-ME` 占位链接（死链）；
                        //   ② 罗列了实现类名（对用户无意义）。
                        //   ⇒ 现在只陈述**用户可核的事实**，链接缺失时明确说"源码随包"。
                        buildList {
                            add("独立 SPW 插件：渲染与时序为独立实现；")
                            add("菜单外观体系移植自原实现（详见随包 NOTICE）。")
                            add("歌词悬浮岛的原创交互概念出自 Lyricify / WXRIW（CC BY-SA 4.0）。")
                            add("作者：H-2OS（自宅警備員）。本项目以 AGPL-3.0-only 发布；")
                            add("Jaudiotagger（LGPL-2.1-or-later）两个第三方库。")
                            add("完整对应源码随包提供（`source/`，AGPL-3.0 §6）。")
                            add("")
                            val url = projectUrl()
                            if (url != null) add("项目源代码：$url") else add("对应完整源码随包提供：`source/` 目录。")
                            add("完整许可、第三方声明与对应源码随插件 ZIP 提供。")
                        }.joinToString("\n"),
                    "关于与许可",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }

        internal fun openSource() {
            // ★★ 2026-10-17：**未配置公开仓库时不再打开死链**。
            //   原行为：`projectUrl()` 回退成 `CHANGE-ME` 占位地址 ⇒ 点「源代码」会打开 404。
            //   现在：`null` ⇒ 用 toast 明确告知"源码随包提供"，不弹浏览器。
            val url = projectUrl()
            if (url == null) {
                runCatching {
                    WorkshopApi.ui.toast(
                        "本版本未配置公开仓库；对应源码随插件包提供（source/ 目录）。",
                        WorkshopApi.Ui.ToastType.Warning,
                    )
                }
                return
            }
            runCatching {
                if (!Desktop.isDesktopSupported()) error("Desktop API 不可用")
                val desktop = Desktop.getDesktop()
                if (!desktop.isSupported(Desktop.Action.BROWSE)) error("不支持打开浏览器")
                desktop.browse(URI(url))
            }.onFailure {
                runCatching {
                    WorkshopApi.ui.toast("无法打开项目地址：$url", WorkshopApi.Ui.ToastType.Warning)
                }
            }
        }

        /**
         * **删除预设** / **更改预设**的静态入口（`preference_config.json` 的 `on_click` 目标）。
         *
         * ## 用户原话（逐字）
         *
         * > 「**在设置项中要提供删除、更改预设的入口**。」
         *
         * ## 为什么是"两个方法 + 一个模式枚举"而不是两个各自实现的入口
         *
         * `on_click` 的反射契约是**无参静态方法**（`recon/20` §A.3），
         * 一个按钮无法区分"要删还是要改" —— 故两个入口各需一个 `@JvmStatic` 方法。
         * 但它们打开的窗、走的落盘路径、用的 toast 全部相同，
         * 差别只有标题与提示语（见 [PresetManagerMode]）——
         * **实现只允许有一处**，否则"删到哪 / 覆盖哪些键 / 改名怎么校验"早晚会漂移。
         *
         * ## 线程与兜底
         *
         * 与 [pickFont] 同构：Swing 组件构造走 EDT，失败时用 toast 让**用户能看见**
         * （宿主 stdout/stderr 是黑洞，见 [notifyWarning] 的 KDoc）。
         * `on_click` 的调用线程不确定，故不依赖"宿主恰好是 EDT"这个假设。
         */
        internal fun openPresetDeleter() = openPresetManager(PresetManagerMode.DELETE)

        internal fun openPresetChanger() = openPresetManager(PresetManagerMode.CHANGE)

        /**
         * 预设管理窗是否已经打开（2026-10-18）。
         *
         * 模态窗会在 EDT 上开一个嵌套事件循环 ⇒ 用户**连点两次**「删除预设…」
         * 会叠出两个窗，且下层窗里的操作已经过期。这个标志在 `show` 返回前一直为 true。
         */
        @Volatile
        private var presetDialogOpen = false

        /** 预设管理窗的**唯一打开路径**（设置页两个按钮 + 右键菜单两条，共四个入口共用）。 */
        private fun openPresetManager(mode: PresetManagerMode) {
            onEdt {
                if (presetDialogOpen) {
                    notifyUser("预设管理窗已经打开了", warning = false)
                    return@onEdt
                }
                presetDialogOpen = true
                try {
                    runCatching {
                        // toast 通道由这里注入：`settingsui` **不碰 `WorkshopApi`**（分层约定）。
                        showPresetManagerDialog(lastIslandWindow(), mode) { message, warning ->
                            notifyUser(message, warning)
                        }
                    }.onFailure { error ->
                        System.err.println("[CDL] 预设管理窗打开失败：$error")
                        notifyUser(
                            "预设管理窗打开失败：${error.message ?: error.javaClass.simpleName}",
                            warning = true,
                        )
                    }
                } finally {
                    presetDialogOpen = false
                }
            }
        }

        /**
         * 发一条宿主 toast。**成功与失败都发** —— 用户明确要求过
         * 「删除 / 更改成功或失败都要让用户看得见」。
         *
         * `runCatching` 包住是刻意的：**不让"提示本身失败"再引发一次崩溃**
         * （与 `notifyWarning` / `IslandPopupMenu.notifyToast` 同一处理）。
         */
        private fun notifyUser(message: String, warning: Boolean) {
            runCatching {
                WorkshopApi.ui.toast(
                    message,
                    if (warning) {
                        WorkshopApi.Ui.ToastType.Warning
                    } else {
                        WorkshopApi.Ui.ToastType.Success
                    },
                )
            }.onFailure { System.err.println("[CDL] 发送提示失败（提示内容：$message）：$it") }
        }

        /**
         * 读 `project.properties`（构建时由 `processResources` 展开 `projectUrl`），
         * 返回公开仓库地址；**未配置时返回 `null`**（不再回退成 `CHANGE-ME` 占位链接）。
         *
         * ★ 2026-10-17：原实现在取不到值时回退到 `https://github.com/CHANGE-ME/...` ⇒
         *   「关于与许可」窗口会**把占位链接直接显示给用户**，点「源代码」还会打开死链。
         *   ⇒ 判据：**占位值 = 没有值**（`CHANGE-ME` / `CHANGEME` / 空）统一返回 `null`，
         *   由调用方显示"源码随包提供"。
         */
        private fun projectUrl(): String? = runCatching {
            val properties = Properties()
            CdlRuntime::class.java.getResourceAsStream("/project.properties")?.use(properties::load)
            val raw = properties.getProperty("source.url")?.trim()
            raw?.takeIf {
                it.isNotBlank() &&
                    !it.contains("CHANGE-ME", ignoreCase = true) &&
                    !it.contains("CHANGEME", ignoreCase = true)
            }
        }.getOrNull()

        private fun onEdt(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
        }
    }
}
