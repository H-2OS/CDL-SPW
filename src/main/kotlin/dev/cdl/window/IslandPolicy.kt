// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

import dev.cdl.playback.LyricLine
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 词岛的**纯策略函数** —— 把"只有一两行、但错过两次或容易算错"的判断，
 * 从 `IslandWindow` 的私有成员里**抽成公开的纯函数**。
 *
 * ## 为什么值得单独一个文件（三条理由，都不是"为了好看"）
 *
 * ### ① 让探针能断言它们
 *
 * `IslandWindow` 是 `internal class`、其常量在 `private companion object` 里，
 * 而探针是**独立编译单元**（`build.gradle.kts` 未配 `associate`/friend-paths）——
 * **看不到 `internal`，更看不到 `private`**。于是这三条判断此前**没有任何断言**：
 *
 * | 判断 | 此前的问题 |
 * | --- | --- |
 * | 穿透态要不要驱动悬停 | 用户的原始投诉（BUG-046），**回归了也没人知道** |
 * | 穿透设置的取值方向 | **已被改反两次**（BUG-020），是本项目最贵的一个坑 |
 * | 长歌词滚动的相位数学 | 新写的代码，含取余 / 缓动 / 分段，**最容易被"顺手改坏"** |
 *
 * 本项目对"为测试放宽可见性"的门槛较高（`HoverState` / `SpectralPainter` 仍保持 `internal`）——
 * 但**抽一个纯函数出来**不是放宽可见性：它没有暴露任何内部状态，
 * 只是把"一条规则"从"某个类的私有实现细节"提升为"一个有名字的定义点"。
 * 这也正是本项目既有纪律「**同一语义散在多处定义点时，收敛到一处**」的做法。
 *
 * ### ② 它们本来就是纯的
 *
 * 四个函数**不读任何字段、不碰 Swing、不取系统时间**（时间由参数传入）——
 * 抽出来是零成本的，且顺带让"时间基准"这件事从"藏在方法体里"变成"写在签名上"。
 *
 * ### ③ 名字本身就是文档
 *
 * `clickThroughFromSetting(v)` 比 `boolOf(fresh, "island.interactive")` 多说了两件事：
 * 它**是**设置项的值，而且**不做任何取反**。BUG-020 那两次误改，
 * 根因正是"看着像 bug 但其实不是"——一个把语义写进名字的函数，
 * 让"取反"这个动作必须**显式地**发生在别处。
 */

// ── 长歌词水平滚动的参数（`SCROLL_*`）────────────────────────────────────────
//
// 放在这里而不是 `IslandWindow` 的私有 companion：探针要拿**真值**断言相位数学，
// 而不是抄一份常量（抄来的常量会漂移，`MultiLineProbe` 就吃过这个亏）。

// ⚠️ 2026-09-23：`SCROLL_SPEED_PX_PER_SEC` 与 `SCROLL_HOLD_MS` **已删除**。
//
// 用户第 ④ 条把滑移从"时间驱动"改成"**进度驱动**"：
// > 「词岛宽度不足以显示完整主歌词时，主歌词自动滑移时，**尽量保持最低匀速**，
// >   使**高亮进度保持在歌词显示部分的 1/2 处**。」
//
// 于是"每秒滑多少像素"与"两端各停留多久"这两个量**不再存在** ——
// 滑移速度由**歌词自身的演唱速率**决定（见 [marqueeOffsetForProgress]），
// 两端停留由 `clamp` 的自然端点给出。
// 留着它们会让"速度"有两个真相源：一个没人读的常量 + 一个真实公式。

/**
 * 触发滚动的最小溢出量（px）。
 *
 * 取 8：小于这个量的溢出**肉眼几乎看不出被截**（默认字号下一个汉字的三分之一），
 * 而滚动本身是"持续动"的强视觉信号 —— 为了几像素的截断让整行来回晃，
 * 代价远大于收益。这也顺带吸收了宽度测量的 1–2 px 噪声。
 */
const val SCROLL_MIN_OVERFLOW_PX = 8f

/**
 * ★★★ 2026-10-17（**BUG-225**）：自然宽度测量的**字形余量**（px）。
 *
 * ## 为什么必须有（用户报障逐字）
 *
 * > 「改了最大宽度，并取消自动宽度，但在**高亮到句尾时歌词会移动**，
 * >   就像**宽度不足**一样。」
 *
 * ## 根因
 *
 * `TextLayout.advance` 只含**字形前进量**，**不含**：
 * · 描边外扩（`main_lyric.outline` 开着时，每个字左右各多 1~2 px）
 * · 抗锯齿覆盖的半个像素
 *
 * 于是 `naturalWidthPx` 算出来的宽度**恰好**比"真正画得下"少一点点 ⇒
 * `availWidth < advance` ⇒ `overflow > 0` ⇒ **触发水平滚动** ⇒
 * 滚动是**进度驱动**的 ⇒ 唱到句尾时整行移到最右 ⇒ 用户看到"歌词在动" ✗
 *
 * ★ 4 px = 两侧各 2 px（覆盖 1 px 描边 + 1 px 抗锯齿余量）。
 *   **不要调到 0** —— 那正是这个 bug 的成因。
 */
const val WIDTH_GLYPH_SLACK_PX = 4f

// ── 穿透 ────────────────────────────────────────────────────────────────────

/**
 * `island.interactive` 的值 → **是否开启整窗鼠标穿透**。
 *
 * ## 这个函数存在的全部理由：把"不取反"这件事变成代码
 *
 * ⚠️ 本项的**键名/标题是「鼠标穿透」，而值语义是 `true` = 穿透开启**。
 * 也就是说 `setClickThrough(handle, 值)` **直传就是对的** ——
 * 它看着像 bug（"勾了穿透，却传 true 给 setClickThrough？"）但**不是**。
 *
 * 这个坑**已被误改两次**（BUG-020：错法 A 靠双否定碰巧对、错法 B 直接取反导致全反），
 * 而两次误改的**探针都是全绿的** —— 靠断言拦不住"方向"这类错误。
 *
 * 故这里把语义钉成一个**有名字的函数**：想取反的人必须删掉它、或改掉它，
 * 而 [IslandPolicyProbe] 会立刻变红。**"直传"从此不是注释里的约定，而是代码。**
 *
 * @param islandInteractive 设置项 `island.interactive` 的原始值
 * @return 是否开启整窗穿透（**与入参相同，不做任何变换**）
 */
fun clickThroughFromSetting(islandInteractive: Boolean): Boolean = islandInteractive

/**
 * 本帧**要不要驱动悬停状态机**（展开控件条、更新高亮控件）。
 *
 * ## 用户原话（本轮诉求）
 *
 * > 「**设置鼠标穿透后，鼠标经过不应再感应或触发播放控件**」
 *
 * ## 为什么是"穿透开 → 不驱动"，而不是"穿透开 → 仍轮询指针"
 *
 * 原记（BUG-023 的结论）：穿透态下窗口收不到鼠标事件，若不自轮询，
 * 悬停状态机会永远停在 `enterSince == NONE`（"引导死锁"），播放控件**永不可达** ——
 * 当时判定为缺陷并加了轮询。
 *
 * 实为：**那是过度纠正**。「鼠标穿透」的语义**就是该窗口不接收鼠标交互** ——
 * "控件不可达"是穿透的**正常结果，不是缺陷**；用户想交互时会关掉穿透。
 * 加了轮询之后，用户勾了穿透、鼠标划过却**照样弹控件**，与预期**正好相反**。
 *
 * ## ⚠️ 只作用于**整窗穿透**，不要顺手把区域级穿透也关掉
 *
 * 区域级穿透（未勾穿透时"背景可穿透、字形可交互"）**必须**继续轮询指针 ——
 * 它要回答"光标现在**离开了**字形吗"，而离开时通常**没有事件**可收。
 *
 * @param clickThroughActive 当前是否处于**整窗**穿透态
 * @return `true` = 正常驱动悬停；`false` = 完全不驱动（且应把已展开的控件条收起）
 */
fun shouldDriveHover(clickThroughActive: Boolean): Boolean = !clickThroughActive

/**
 * 悬停定时器是否**必须自己轮询指针** —— `HoverState.needsTicker` 的 `pollPointer` 入参。
 *
 * ## 为什么要有这个函数（它是那个入参的**唯一出口**，2026-09-21）
 *
 * 此前调用点写的是 `clickThroughActive || REGIONAL_CLICK_THROUGH`
 * （`IslandWindow` 的 `shouldDriveHover` 调用点），而 `REGIONAL_CLICK_THROUGH` 是 `const val = true`
 * ⇒ 实参**恒为 true**。后果有两条，都不报错：
 *   · `HoverState.needsTicker` 的 `else` 分支成为**死代码**；
 *   · `@param pollPointer` 的契约（"调用方会自己在 tick 里轮询指针"）**无法被证伪** ——
 *     它永远收到 `true`，于是"传错了会怎样"这件事**从来没人测过**。
 *
 * 抽成本函数后，"该不该轮询"**只有一处定义**，探针可以对它穷举真值表
 * （见 `HoverGuidanceProbe` 与 `ControlsProbe` §H2）。
 *
 * ## 语义（BUG-023 被推翻后的**当前**语义）
 *
 * | 整窗穿透 | 区域级穿透 | 返回 | 理由 |
 * |---|---|---|---|
 * | `true` | 任意 | **`false`** | 整窗穿透 ⇒ [shouldDriveHover] 为假 ⇒ tick **提前 `return`，根本不轮询** |
 * | `false` | `true` | `true` | 要回答"光标**离开了**字形吗"，而离开的那一刻**没有事件**可收 |
 * | `false` | `false` | `false` | 事件驱动即可，不需要常驻轮询 |
 *
 * ⚠️ **整窗穿透那一行优先**：两者同时为真时返回 `false`。这不是口味问题 ——
 * 调用方 `IslandWindow.onHoverTick` 里 `shouldDriveHover` 的判断
 * 排在区域穿透的轮询分支**之前**，整窗穿透时后者**不可达**。
 * 返回值必须与调用方的**真实行为**一致：传 `true` 却不会轮询，就是让参数撒谎。
 *
 * ## ⚠️ 诚实声明：它**今天没有任何用户可见症状**
 *
 * 上面那两条后果都是**潜在**的：`REGIONAL_CLICK_THROUGH` 是编译期常量 `true`，
 * 故本函数在真机上**恒等于 `!fullClickThrough`**，行为与改动前**逐像素一致**。
 * 它只在"区域级穿透被关掉"时才会显现。
 * ⇒ **不要把这次改动说成"修好了引导死锁"** —— 引导死锁的前提
 * （"穿透态本该有悬停"）已被用户明确否决，见 [shouldDriveHover] 的 KDoc。
 *
 * @param fullClickThrough 是否处于**整窗**穿透（`island.interactive` = 开）
 * @param regionalClickThrough 是否启用**区域级**穿透（背景穿透、字形仍可交互）
 * @return `true` = 调用方会在每个 tick 里自己取一份新指针喂给状态机
 */
fun hoverNeedsPointerPoll(
    fullClickThrough: Boolean,
    regionalClickThrough: Boolean,
): Boolean = if (fullClickThrough) false else regionalClickThrough

// ── 悬停光标 ────────────────────────────────────────────────────────────────

/**
 * 悬停时应显示的光标形状。抽成枚举是为了让 [hoverCursorFor] 保持**纯**——
 * `java.awt.Cursor` 的构造要走 Toolkit（无头环境会抛 `HeadlessException`），
 * 而探针是**不带显示环境**跑的普通 JVM 进程。把"决定形状"与"取 Cursor 对象"
 * 分开，判断就变成可断言的了。
 */
enum class HoverCursor {
    /** 默认箭头。 */
    DEFAULT,

    /** 手形：悬停在播放控件上（可点击）。 */
    HAND,

    /** 四向移动箭头：悬停在词岛本体上（可拖动）。 */
    MOVE,
}

/**
 * 悬停时该用哪个光标。
 *
 * ## 用户原话（本轮诉求）
 *
 * > 「鼠标光标**命中字形后仍不会改变箭头光标**，需要**左键单击后**才能显现
 * >   抓取手型或可移动的箭头光标。」
 *
 * ## 根因：光标只在**按下**路径上被设置
 *
 * 改动前只有两处设光标：`mousePressed` 里 `panel.cursor = MOVE`（拖动开始）、
 * 以及 [updateHoveredControl] 里"命中控件 → HAND，否则 DEFAULT"。
 * 于是**命中字形（可以拖动）这件事从未在悬停时被表达过** ——
 * 用户必须先按一下，才会看到那个"可移动的箭头"。
 *
 * ## 判据：**悬停时的光标 = 按下后会变成的那个光标**
 *
 * 这是本函数唯一的规则，也是它好用的原因：用户看到什么形状，
 * 就知道按下去会发生什么。两条对应关系：
 *
 * | 悬停位置 | 按下会发生 | 悬停光标 |
 * |---|---|---|
 * | 播放控件 | 执行动作（[consumeControlPress]） | [HoverCursor.HAND] |
 * | 词岛本体、且**未锁定位置** | 开始拖动窗口 | [HoverCursor.MOVE] |
 * | 其它（含**锁定位置**时的词岛本体） | 什么也不发生 | [HoverCursor.DEFAULT] |
 *
 * ## 为什么"锁定位置"要退回箭头（而不是照样给 MOVE）
 *
 * 锁定（`island.lock_position`）时 `mousePressed` **在记拖动起点之前就返回**，
 * 按下后光标不会变成 MOVE。若悬停时给 MOVE，就违反了上面那条"悬停 = 按下后"的
 * 一致性 —— 用户看到可拖动的箭头、按下去却纹丝不动，比不给箭头更迷惑。
 * 宁可"看起来不能拖"（诚实），也不要"看起来能拖但拖不动"（撒谎）。
 *
 * ## 优先级：控件 > 词岛本体
 *
 * 与 `consumeControlPress`（先判控件、命中就直接返回）保持一致 ——
 * 两处若顺序不同，会出现"光标说能拖、按下却执行了动作"这种自相矛盾。
 *
 * @param hitControl 指针是否落在播放控件上
 * @param overGlyph 指针是否落在**字格**上（`panel.hitGlyph`；`ISLAND_HIT` 触发模式下
 *                  退化为"整个词岛矩形"，那是该模式的定义，不是本函数的责任）
 * @param positionLocked 用户是否锁定了词岛位置（`island.lock_position`）
 * @return 应当设置的光标形状
 */
fun hoverCursorFor(
    hitControl: Boolean,
    overGlyph: Boolean,
    positionLocked: Boolean,
): HoverCursor = when {
    hitControl -> HoverCursor.HAND
    overGlyph && !positionLocked -> HoverCursor.MOVE
    else -> HoverCursor.DEFAULT
}

// ── 窗口几何 ────────────────────────────────────────────────────────────────

/**
 * 宽度变化时，让窗口**以原横向中心为锚**的新 x 坐标。
 *
 * ## 用户原话（本轮诉求）
 *
 * > 「歌词框应以**两边向中间**的方式伸缩（变长或变短），而非当前的伸缩方式」
 *
 * ## 原记与实为
 *
 * 原记：`window.setSize(w, h)` —— 那会**保持左上角不动**，
 * 于是宽度变化时只向**右侧**伸缩（左边钉死）。
 *
 * 实为：本版实现了 `layout.fixed_width = false` 时**按歌词长度收缩**，
 * 宽度**每句都会变** —— 单侧伸缩从"看不出来"变成"主要观感问题"。
 * **一个旧的小瑕疵，被一个新特性放大成了主要观感问题。**
 *
 * ## 为什么算术要写成"减两次整数除法"而不是先算浮点中心
 *
 * 与改动前**逐像素一致**：原式是 `window.x + oldWidth / 2 - newWidth / 2`
 * （`Int` 除法，各自向下取整）。若改成先算浮点中心再取整，
 * 奇数宽度下会与旧行为差 1 px —— 而"看起来一样但差 1 px"是最难查的那类回归。
 *
 * ⚠️ 调用方**只在宽度真的变了时才调用**它：高度变化（控件条展开/收起）
 * 不该引起横向位移，否则每次展开控件条词岛都会左右跳一下。
 */
fun centeredWindowX(oldX: Int, oldWidth: Int, newWidth: Int): Int =
    oldX + oldWidth / 2 - newWidth / 2

/**
 * 控件条展开/收起导致窗口高度变化时，窗口**该被放到哪个 y**。
 *
 * ## 目标只有一个：**歌词在屏幕上不动**
 *
 * ## 用户原话（2026-09-17）
 *
 * > 「在设置控件为**上方**时，光标移到弹出控件判定范围，**移动的是整个歌词行和
 * >   背景频谱**，而不是设置控件位置为**下方**时整个歌词行和背景频谱位置不变的
 * >   情形，**修复它**。」
 *
 * ## 原记与实为
 *
 * 原记：`window.setSize(w, h)` 只改宽高、不动 `y` —— "窗口向下生长"，看着没问题。
 *
 * 实为：**那只对 `BOTTOM` 成立。** `layout.lyricsTop` 在 `TOP` 时 = 控件条高度
 * （歌词被推下去让出顶部那段），而窗口顶边没动 → 歌词在**屏幕上**真的下移一条。
 * 而 `BOTTOM` 时 `lyricsTop = 0`、窗口向下生长 → 歌词屏幕位置不变。
 * **同一个机制，两种位置两种观感** —— 正是用户说的"而不是……的情形"。
 *
 * ## 判据：`TOP` → 把高度增量记在 `y` 上（窗口向上生长）
 *
 * - **展开**（`heightGrowth > 0`）→ `y` 减小，新长出的那段正好放控件条；
 *   歌词相对窗口顶下移了同样多 → **屏幕上完全不动**。
 * - **收起**（`heightGrowth < 0`）→ 反向，同样抵消。
 * - `BOTTOM` → `y` 不变（窗口向下生长），歌词屏幕位置本来就不动。
 *
 * ⚠️ **`stripAtTop` 必须由"用户设置"给出，不能由"当前是否展开"推导** ——
 *   收起时"是否展开"已是 `false`，用它会让收起这一半不还原 `y`，
 *   词岛**一条一条往上爬**。这是本函数把两个概念分成两个参数的唯一原因。
 *
 * @param oldY 变化前的窗口 y
 * @param heightGrowth 本次高度增量（`新高度 - 旧高度`，可正可负）
 * @param stripAtTop 控件条是否在**顶部**（来自 `controls.position`，与展开态无关）
 * @return 变化后窗口应有的 y
 */
fun anchoredWindowY(oldY: Int, heightGrowth: Int, stripAtTop: Boolean): Int =
    if (stripAtTop) oldY - heightGrowth else oldY

// ── 长歌词滚动 ──────────────────────────────────────────────────────────────

/**
 * 歌词行的**行高比率** —— 必须等于「两行墨迹**真正接触**」的比率。
 *
 * ## 为什么它定义在**公开**文件里（而不是 `IslandWindow` 的 companion）
 *
 * `IslandWindow` 是 `internal`，**探针（独立源集）看不到它** ——
 * 于是那条「行高常数 == 真实墨迹比率」的自校验护栏就没法写。
 * 本项目已为这条坑吃过亏（见 `MEMORY.md` 的「探针看不到 internal」），
 * 所以把它放在公开文件里，**窗口与探针共用同一定义**。
 *
 * ## 取值取证（2026-09-20 实测，`FontMetrics`，字体族 = MiSans）
 *
 * | 字号 | ascent | descent | (ascent+descent)/字号 |
 * | --- | --- | --- | --- |
 * | 22 | 23 | 7 | **1.364** |
 * | 40 | 42 | 12 | **1.350** |
 *
 * 原值 `1.6` **偏大**：两行墨迹接触只需约 **1.36**，用 1.6 时每行多出
 * `0.236 × 字号` 的空白（22 号 ≈ **5.2 px**）。用户原话：
 * 「**歌词行间距最低值从两行文字接触线起算**」—— 指的就是这个。
 *
 * 取 1.36（而非精确 1.350）是留半个像素的余量：`ascent`/`descent` 是字体的
 * **声明**度量，不同字号下整数取整方向不同（实测在 1.350~1.364 之间浮动）。
 *
 * ## ⚠️ 2026-09-22 补测：默认字体换成了 `Microsoft YaHei UI`，它的比率**略小**
 *
 * 上表是**旧默认**（随包的 `MiSans`）测出来的。随包字体取消、出厂默认改为
 * `Microsoft YaHei UI`（见 `font/FontDefaults.kt`）后补测（同 JDK、同 API）：
 *
 * | 字体族 | 22 号 | 40 号 |
 * | --- | --- | --- |
 * | `MiSans`（旧默认） | 1.3636 | 1.3500 |
 * | `Microsoft YaHei UI`（**现默认**） | **1.3182** | 1.3000 |
 * | `Microsoft YaHei` / `微软雅黑` | 1.3636 | 1.3500 |
 *
 * ⇒ 常数 1.36 对**新默认**偏大约 `0.042 × 字号`（22 号 ≈ **0.9 px**）：
 * 即"行间距调到最低"时两行墨迹之间会留下不到一个像素的空隙，**不再严格相切**。
 *
 * **如实登记，本轮不擅自改常数**，理由：
 * 1. 这是**产品取值**问题，且与用户的**具体字体**绑定 —— 而取消随包字体之后
 *    "用户的字体"本来就不再统一（有人用雅黑 UI、有人用雅黑、有人用思源黑），
 *    任何单一常数都只能对**某一款**严格成立；
 * 2. 0.9 px 的偏差**小于一个像素**，视觉上不可辨；
 * 3. 探针 `MultiLineProbe` 的容差是 ±0.06，`|1.36 − 1.3182| = 0.0418` 仍在其中，
 *    故护栏不会因此变红 —— 但它**只剩 0.018 的余量**，这一点已记进该探针的 KDoc。
 *
 * 若日后要"常数与默认字体严格吻合"，把默认值改成 `Microsoft YaHei`
 * （不带 UI，比率恰为 1.3636、与旧锚点逐位相同）是一个现成候选 ——
 * 但那要与"原实现用的是带 UI 那档"这一取向权衡，属**产品决策**。
 */
const val LINE_HEIGHT_RATIO = 1.36f

/**
 * 长歌词**进度驱动**的水平滑移偏移（px，负数 = 向左移）。
 *
 * ## 用户原话（第 ④ 条）
 *
 * > 「词岛宽度不足以显示完整主歌词时，主歌词自动滑移时，**尽量保持最低匀速**，
 * >   使**高亮进度保持在歌词显示部分的 1/2 处**。」
 *
 * ## 为什么滑移量必须是"已唱进度"的函数，而不是"时间"的函数
 *
 * 时间驱动的滑移（本函数取代的那一版）有三处**不可修的**失同步：
 *
 * | 场景 | 时间驱动 | 进度驱动（本函数） |
 * | --- | --- | --- |
 * | 暂停 | 相位继续跑 ⇒ **画面在动而音乐停了** | 进度不动 ⇒ 滑移**自动停住** |
 * | 拖动进度条 / 跳转 | 相位与新的播放位置**无关** ⇒ 高亮与滑移错位 | 立刻跟随新的已唱位置 |
 * | 重播 / 循环 | 需要有人"重置锚点"（那正是 `ScrollAnchorTable` 的职责） | 不需要锚点 —— 它是纯函数 |
 *
 * ⇒ 相位锚点、`ScrollAnchorTable`、"还在滑吗"这三样**一并删除**：
 * 进度驱动下它们都没有意义（滑移是进度的纯函数，进度由播放驱动）。
 *
 * ## 公式与它的三条性质（探针逐条断言）
 *
 * ```
 * sungW  = advancePx × progress         // 已唱部分的像素宽度
 * target = sungW − visibleWidthPx / 2   // 让"已唱位置"落在可视区**中点**
 * offset = −clamp(target, 0, advancePx − visibleWidthPx)
 * ```
 *
 * | 性质 | 为什么成立 |
 * | --- | --- |
 * | **已唱位置在可视区 1/2 处** | 可滑区间内 `sungW + offset == visibleWidthPx / 2`，恒等式 |
 * | **匀速** | `d(offset)/d(progress) = −advancePx`（**常数**）⇒ 进度匀速时滑移匀速 |
 * | **不越界** | `clamp` 到 `[0, advance − visible]` ⇒ 左不露空白、右不越出行尾 |
 *
 * ### "最低匀速"的落点
 *
 * 滑移速度 = `advancePx × d(progress)/dt` = **歌词自身的演唱像素速率**。
 * 它不是"另取一个速度常数"，而是**与演唱同速** —— 这就是"最低"的含义：
 * 再慢就追不上已唱位置（1/2 处会右移），再快就超过它（1/2 处会左移）。
 *
 * ### 两端的自然停留（不需要 `holdMs`）
 *
 * · `progress` 小（还没唱到可视区中点）→ `target < 0` ⇒ `clamp` 到 `0` ⇒ **停在行首**；
 * · `progress` 大（已滑到尾部）→ `target > overflow` ⇒ `clamp` 到 `overflow` ⇒ **停在行尾**。
 *
 * 两端停留由**几何本身**给出，不是额外的时间参数。
 *
 * ## 历史（为什么这里曾经有"停留 / 左移 / 回跳"三段）
 *
 * 2026-09-20 与 09-21 用户两次报「过长歌词**不要往返**」「**快唱完时会回跳**」，
 * 那两次改的都是**时间驱动**那版的相位形状（先去掉右移、再去掉循环回跳）。
 * 本次（第 ④ 条）把驱动量从"时间"换成"进度"，那三段**整体不再存在** ——
 * 且"回跳"在进度驱动下**在结构上不可能发生**：
 * `offset` 是 `progress` 的**单调**函数（`−advance × p` 再夹取）。
 *
 * ## 边界
 *
 * - `advancePx <= visibleWidthPx`（不溢出）→ `0f`（**不滑**，与改动前一致）；
 * - 任一入参非有限 → `0f`（渲染路径不抛异常）；
 * - **`visibleWidthPx <= 0f` → `0f`**（"可见宽为负"不该发生，但它是**安全兜底**：
 *   少了这一条时 `overflow = advance − (−5) > 0`，函数会返回一个**非零偏移** ——
 *   实测被 `IslandPolicyProbe` §④ 抓到（`可见宽为负（不该发生）→ 偏移 0` 报 FAIL）。
 *   与 [marqueeSungFraction] 的同一条兜底对齐，两函数口径一致）；
 * - `progress` 越界 → 夹到 `0f..1f`。
 *
 * @param advancePx 这一行的排版宽度（`TextLayout.advance`）
 * @param progress 这一行的**已唱进度**（`0f..1f`，来自 `sharedSweepFraction`）
 * @param visibleWidthPx 文字区可用宽度
 * @return 负的水平偏移，直接加在 `baselineX` 上
 */
fun marqueeOffsetForProgress(
    advancePx: Float,
    progress: Float,
    visibleWidthPx: Float,
): Float {
    if (!advancePx.isFinite() || !visibleWidthPx.isFinite()) return 0f
    // ⚠️ `<= 0f`（不只是"非有限"）：见 KDoc「边界」最后一条 —— 这条兜底是被探针抓出来的。
    if (visibleWidthPx <= 0f) return 0f
    val overflow = advancePx - visibleWidthPx
    if (overflow <= 0f) return 0f
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val target = advancePx * p - visibleWidthPx / 2f
    return -target.coerceIn(0f, overflow)
}

/**
 * 滑移之后，**已唱位置**在可视区里的比例（`0f` = 贴左边缘，`1f` = 贴右边缘）。
 *
 * ## 为什么单独一个函数（而不是让探针在断言里现算）
 *
 * 它是用户第 ④ 条那句「**使高亮进度保持在歌词显示部分的 1/2 处**」的**算术形式**。
 * 若探针自己写一遍这个除法，那"生产用的是哪条公式"与"探针断的是哪条公式"
 * 就成了两个真相源 —— 本项目为此栽过（`IslandPolicyProbe` 的 KDoc 记着：
 * "原先是复刻 `marqueeOffsetPx` 内部的一行除法"）。故它是**公开纯函数**。
 *
 * 与 [marqueeOffsetForProgress] 的关系是**恒等式**：可滑区间内它恒为 `0.5f` ——
 * 那正是那条断言的全部内容。
 *
 * @return `0f` = 已唱位置贴可视区左边缘；`1f` = 贴右边缘
 */
fun marqueeSungFraction(
    advancePx: Float,
    progress: Float,
    visibleWidthPx: Float,
): Float {
    if (!advancePx.isFinite() || !visibleWidthPx.isFinite() || visibleWidthPx <= 0f) return 0f
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val sungW = advancePx * p
    // `marqueeOffsetForProgress` 返回**负**偏移 ⇒ 加它等于把已唱位置左移。
    return (sungW + marqueeOffsetForProgress(advancePx, p, visibleWidthPx)) / visibleWidthPx
}

/**
 * 把 [text] 截到能放进 [availWidth] 的宽度，超出部分换成 `…`（省略号）。
 *
 * ## 为什么是"省略号"而不是"滚动"（2026-09-21）
 *
 * 本函数服务于**「无歌词」占位提示**（`layout.noLyrics.mode = PLACEHOLDER`，
 * 文本可能是 `layout.noLyrics.showTrackTitle` 给的**曲名**）。
 *
 * 本项目的滚动（[marqueeOffsetPx]）是给**歌词**用的 —— 那是"跟着唱/跟着读"的内容，
 * 值得为它加运动。而占位提示是**静态提示**（`IslandWindow` 的 KDoc 原文：
 * 「这是**提示**不是歌词」）：
 *
 * - **滚动一个静态提示**会让画面无谓地动，反而更难读；
 * - **省略号能"说出"自己被截断了**，而裁剪什么都不说 —— 用户只会以为
 *   "曲名就这么短"或"这里显示坏了"。
 *
 * ## 为什么必须修（同族缺陷的第三处）
 *
 * 绘制文本全文件三处：`drawOneLine`（有滚动）、`drawTranslationLine`（2026-09-21 补滚动）、
 * 以及本函数服务的 `drawNoLyricsNotice` —— **它此前完全没有溢出处理**。
 * 而它用的是 [alignBaselineX]，**居中对齐时两端一起被裁**（开头和结尾都看不到），
 * 比单侧裁剪更糟。
 *
 * ## 为什么注入测量器而不是直接用 `TextLayout`
 *
 * 这样它就是**纯函数**：探针可以喂"每字符 10 px"的假测量器**穷举边界**
 * （恰好放得下 / 差 1 px / 连省略号都放不下），不必构造字体与图形环境。
 * 生产侧传 `{ s -> renderCache.textLayoutFor(s, font)?.advance }`。
 *
 * ⚠️ 二分查找是 `O(log n)` 次测量。占位提示**只在无歌词时绘制**、
 * 且那时通常没有动画（帧循环不跑），故这点开销可忽略 —— **故不做缓存**。
 *
 * @param advanceOf 量一段文本的排版宽度（生产侧走 `TextLayout.advance`）。
 * @return 放得下就原样返回；否则返回"截断 + `…`"；**连 `…` 都放不下时返回空串**。
 */
fun ellipsizeToWidth(
    text: String,
    availWidth: Float,
    advanceOf: (String) -> Float,
): String {
    if (text.isEmpty()) return text
    if (availWidth <= 0f) return ""
    if (advanceOf(text) <= availWidth) return text

    val ellipsis = "…"
    val ellipsisWidth = advanceOf(ellipsis)
    // 连省略号都放不下 → 返回空串（画出来只会是半个字，比不画更糟）
    if (ellipsisWidth > availWidth) return ""

    // 二分找**最长**的、加上省略号后仍放得下的前缀。
    // `lo` 是"确定放得下"的前缀长度，`hi` 是"确定放不下"的上界。
    var lo = 0
    var hi = text.length - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (advanceOf(text.substring(0, mid)) + ellipsisWidth <= availWidth) lo = mid else hi = mid - 1
    }
    // 一个字符都放不下（连同省略号）→ 只画省略号
    return if (lo <= 0) ellipsis else text.substring(0, lo) + ellipsis
}

/**
 * 封面**转动角度**（度，恒在 `0..360)`）—— 由**播放位置**推导，不是墙钟时间。
 *
 * ## 为什么用播放位置而不是墙钟（2026-09-21）
 *
 * 用户附黑胶唱片截图：「**新增圆形封面选项并允许其转动。**」
 *
 * | 方案 | 暂停时 | 拖动进度条时 |
 * | --- | --- | --- |
 * | 墙钟时间 | **还在转** —— 音乐停了唱片还在转，语义错 | 不受影响 |
 * | **播放位置**（本实现） | 位置不动 → **自然停住**，不需任何额外判定 | 封面跟着转过去（"唱片在唱机上"的正确语义） |
 *
 * 用位置还有个附带好处：**它让本函数是纯函数**，探针可以穷举
 * （周期是否准确、是否单调、是否恒在值域内、暂停/负数是否兜住）。
 *
 * ## 边界
 *
 * - `periodSeconds <= 0`（不该发生）→ 返回 `0`，**不抛异常**（渲染路径不抛）。
 * - `positionMs <= 0`（曲首 / 未知）→ 返回 `0`。
 *   这也顺带挡住"负位置"——Kotlin 的 `%` 对负数返回负数，会让封面倒转。
 *
 * @param positionMs 播放位置（毫秒）
 * @param periodSeconds 转一圈用时（秒），来自 `cover.rotate_period_s`
 */
fun coverRotationDegrees(positionMs: Long, periodSeconds: Float): Float {
    if (periodSeconds <= 0f) return 0f
    if (positionMs <= 0L) return 0f
    val turns = positionMs / 1000.0 / periodSeconds.toDouble()
    return (turns * 360.0 % 360.0).toFloat()
}

/**
 * `cover.shape` 里**"圆形"**那一档的取值（2026-09-21）。
 *
 * ## 为什么定义在**公开**文件里（而不是 `IslandWindow` 的 companion）
 *
 * `IslandWindow` 是 `internal`，**探针（独立源集）看不到它** ——
 * 于是"这个字符串与注册表 `options` 逐字一致吗"这条断言就没法写。
 *
 * 而**字符串对不上的后果是静默失效**：
 * `if (coverShape == COVER_SHAPE_CIRCLE)` 恒为 `false` → 用户选了「圆形」却没变，
 * **不报错、不打日志**。这类"选项名漂移"在本项目已出现过多次
 * （见 `MEMORY.md` 纪律 8：改名必须同步所有按字面量引用它的地方）。
 *
 * 故按项目纪律**把它抽到公开文件**（"首选抽公开纯函数/常量，
 * 而不是为了测它而放宽可见性"），让探针能把它与注册表选项钉在一起。
 */
const val COVER_SHAPE_CIRCLE = "CIRCLE"

/**
 * `cover.shape` 里**"方形"**那一档的取值（2026-09-21）。
 *
 * 用户原话：「封面形状**再增加一个方形选项**。」
 *
 * ## ⚠️ 与 `shape.type` 的 `"SQUARE"` **同字面量、不同键**
 *
 * `shape.type`（词岛整体形态）**早就有** `"SQUARE"`；本轮给 `cover.shape`
 * 也加了 `"SQUARE"`。两个键互不冲突（一个是词岛的、一个是封面的），
 * 但**字面量相同**意味着 `IslandWindow.drawCover` 里
 * `local.shapeType == "SQUARE"`（决定圆角比例）与新加的封面分支**相邻出现** ——
 * 极易被后来者看成一个东西。
 * ⇒ 那两处各写了一句注释点明"这是词岛形态 / 这是封面形状"，且**刻意不改名**：
 *   改名会让用户已存进 json 的 `shape.type` 值失效（静默回落默认）。
 *
 * 与 [COVER_SHAPE_CIRCLE] 同理放在公开文件里：探针（独立源集）要能把它与
 * 注册表 `options` 钉在一起，防"选了方形却静默回落 ROUNDED"。
 */
const val COVER_SHAPE_SQUARE = "SQUARE"

/**
 * `cover.shape` 里**"圆角矩形"**那一档的取值（2026-09-30）。
 *
 * ## ⚠️ 本常量是**默认值**、不是新选项
 *
 * `cover.shape` 的 `default_value` = `"ROUNDED"`。在本常量出现之前，
 * 各处只能写裸字面量 `"ROUNDED"`（`SettingRegistry` 的 `options`、
 * `IslandWindow.drawCover` 的 `when`）—— 与 [COVER_SHAPE_CIRCLE] /
 * [COVER_SHAPE_SQUARE] **同一形态**（"选项名漂移 ⇒ 静默回落"），
 * 故一并抽到公开文件。
 *
 * ## 为什么要在这里用到它（`recon/67` §5-②③ 的接口）
 *
 * `islandEdgeRadiusPx` 新增了可选参数 `coverShape`，默认值取本常量 ——
 * `recon/67` 只说"封面态轮廓跟 `cover.shape`"，**没说这个值怎么传进来**。
 * 取"可选参数 + 默认值 = 现状"是**最小侵入**选择：既有 4 型的调用点
 * 一个不改、**零行为改动**；将来若要"按 `cover.shape` 逐档算封面态轮廓"，
 * 调用点传实际值即可，无需再动签名。
 *
 * ⚠️ **与 `shape.type` 的 `"ROUNDED_RECT"` 不是一回事**：两个键、
 * 两个取值域（`cover.shape` = {ROUNDED, CIRCLE, SQUARE}，
 * `shape.type` = {SQUARE, ROUNDED_RECT, PILL, NOTCH, COVER_ONLY}）。
 * 字面量不同（`ROUNDED` vs `ROUNDED_RECT`），但**极易看串** ⇒ 引用时带键名。
 */
const val COVER_SHAPE_ROUNDED = "ROUNDED"

// ── 黑胶样式：外围黑环的几何（`cover.vinyl`，2026-09-21）────────────────────
//
// 用户原话：「**旋转圆形封面加上黑边，使其呈现黑胶样式**
// （在**外围**加黑边，不要侵占封面内容）。」
//
// ## 为什么要抽成公开纯函数（而不是写在 `IslandWindow` 里）
//
// 与 `coverRotationDegrees` 同一条理由：`IslandWindow` 是 `internal`，
// **探针（独立源集）看不到它** —— 于是"环宽真的 ≥ 外半径/3 吗""加了黑边真的
// 整体缩小了吗"这两条断言就没法写。而这两条恰恰是**几何正确性的全部**。
//
// ⚠️⚠️ **接线状态（部署前必读）** —— `IslandWindow.drawCover` 仍按**旧语义**在画：
//   它把 `leadingCoverSize` 的返回值当"**内容**"，并把环画在内容**之外**。
//   本文件已改成新语义（入参 = 外径、环**向内占**），故**部署前必须先改
//   `IslandWindow` 的那 4 行**（`:6183 / :6204 / :6212 / :6218`），否则真机的
//   黑环会戳出槽位约 `2×b`（默认 ≈35px）、盖在歌词上 —— 那比"黑边太窄"更糟。
//   精确补丁见 `render/CoverRing` 的类 KDoc。

/**
 * 黑胶环宽占**外径**的比例。
 *
 * ## ★★ 2026-09-22：入参语义从"内容边长"改成"外径"，数值 `0.07` → `1/6`
 *
 * 用户原话（2026-09-22，**推翻了上一轮对同一句话的理解**）：
 * > 「**黑边太窄了**，怎么说也得**和圆形封面的半径的 1/3**，同时为了保证封面协调，
 * >  **加了黑边的封面要适当整体缩小一下**。」
 *
 * `1/6` 不是手感值 —— 它是「**和圆形封面的半径的 1/3**」的直接换算：
 * 外半径 `R = S/2` ⇒ `b = R/3 = S/6`。
 *
 * ⚠️ 它同时**替换掉了旧的那对 `MIN/MAX` 夹取**（`[2, 6]`）：那个上限 `6`
 * 正是"太窄了"的直接成因 —— 默认封面（105）算出来 7.35 被**夹到 6**，
 * 于是"环宽 / 外半径"只有 **0.114**，仅为用户要求的 **34%**。
 * 新几何下环宽**按比例**推导（`b = S/6`），**不再需要夹取**：
 *   · 下限由 `MIN_COVER_SIZE = 20`（`leadingCoverSize` 的护栏）保证 ⇒ `b ≥ 3.33px`；
 *   · 上限由"环向内占"天然满足 —— 外径不变 ⇒ 环永远落在槽位内，
 *     **不会像旧几何那样被词岛轮廓 `clip` 掉上下两头**（旧的 6px 上限就是在防这个）。
 */
const val VINYL_RING_OVER_OUTER = 1f / 6f

/**
 * 黑胶环的**宽度**（px）—— **外径** → 环宽。
 *
 * ## ⚠️ 入参语义已变（2026-09-22）：这是**外径**，不是内容边长
 *
 * 旧签名 `coverVinylRingWidth(contentSize, vinyl)` 的入参是**内容边长**，
 * 因为旧几何把环画在内容**之外** ⇒ 外径 = 内容 + 2×环宽（**外径随环宽变化**，
 * 于是"环宽"与"外径"互为因果，`coverSlotWidth` 必须再加一项才自洽）。
 * 新几何下环**向内占** ⇒ **外径是输入、恒定不变**，内容才是派生量
 * （见 [coverContentSize]）。方向不能反，反了就是循环定义。
 *
 * @param outerSize 封面**外径**（px）—— 槽位里那个圆的外接正方形边长。
 * @param vinyl 是否开启 `cover.vinyl`
 * @return 环宽；未开启 / 外径非正时返回 `0f`（**不抛异常**：渲染路径不抛）
 */
fun coverVinylRingWidth(outerSize: Float, vinyl: Boolean): Float {
    if (!vinyl) return 0f
    if (outerSize <= 0f) return 0f
    return outerSize * VINYL_RING_OVER_OUTER
}

/**
 * 封面**内容（图片）直径**（px）—— 黑边**向内占**之后剩给图片的那部分。
 *
 * `2r = S − 2b = S − S/3 = 2S/3`。
 *
 * 用户原话里的「加了黑边的封面要适当**整体缩小**一下」在几何上**就是这一行**：
 * 开启黑胶后内容**必然小于**外径（默认 105 → **70**）；未开启时**恒等于**外径。
 *
 * @return 内容直径；`outerSize <= 0` → `0f`
 */
fun coverContentSize(outerSize: Float, vinyl: Boolean): Float {
    if (outerSize <= 0f) return 0f
    return outerSize - 2f * coverVinylRingWidth(outerSize, vinyl)
}

/**
 * 封面占用的**槽位宽度**（px）—— **恒等于外径**。
 *
 * ## 这是 `areaLeft` 的唯一真相源
 *
 * `IslandWindow` 用它算 `areaLeft = LEADING_INSET + coverSlotWidth(size, vinyl) + LEADING_GAP`。
 *
 * ## ⚠️ 2026-09-22 语义变更（从"内容 + 2×环宽"改成"恒等于外径"）
 *
 * 旧几何下环画在内容**之外** ⇒ 槽位必须同步加宽 `2×环宽`，否则环会盖住歌词 /
 * 戳出词岛边缘（那段旧 KDoc 的推理本身没错，错的是**前提**）。
 * 用户 2026-09-22 明确否掉了"加黑边让整个封面变大"（"要适当**整体缩小**一下"），
 * 于是环改成**向内占** ⇒ **外径不变 ⇒ 槽位恒等于外径**，开不开黑胶一样宽。
 *
 * ⚠️ **`vinyl` 参数现在不参与计算** —— 保留它是为了**调用点签名不变**
 * （`IslandWindow` / `IslandPreviewPane` 多处按旧签名调用），
 * 也顺带把"槽位宽度只由外径决定"这件事写在了签名上。
 *
 * @return 槽位宽度；`outerSize <= 0` 时返回 `0f`（调用方据此判定"不显示封面"）
 */
@Suppress("UNUSED_PARAMETER")
fun coverSlotWidth(outerSize: Float, vinyl: Boolean): Float {
    if (outerSize <= 0f) return 0f
    return outerSize
}

// ══════════════════════════════════════════════════════════════════════════════
// 「蒙版封面背景」（`background.color_mode = cover_image`，2026-09-22 新增）
// ══════════════════════════════════════════════════════════════════════════════
//
// 用户原话：
// > 「大致为**歌曲封面作为封面底色**，然后**上覆与从封面取色同源的自动取色
// >  半透明蒙版**。同时，因为**词岛是长条型**，歌曲封面可能要**进行一定缩放才能
// >  铺满词岛背景**，同时要**允许用户调整封面角度（0-360 度）**，**旋转后封面自动
// >  缩放铺满**词岛背景，**不再设调整设置**。**不可拉伸封面原图**。」
//
// 三条硬约束（都在这两个定义里落地）：
//  ① **等比**（`scale` 对 x/y 同一个值）⇒ 结构上不可能拉伸；
//  ② **旋转后仍铺满** ⇒ 由 [coverFillScale] 保证（见它的推导）；
//  ③ **没有"缩放"设置项** ⇒ 缩放是**算出来**的，不是用户输进去的。

/**
 * `background.color_mode` 的第三档取值：**把封面图本身当底色**（2026-09-22 新增）。
 *
 * ## 三档的分工（**哪一档读哪些键**，一处说清）
 *
 * | `background.color_mode` | 底色来自 | 读哪些键 |
 * |---|---|---|
 * | `manual`（默认） | 纯色 | `background.color`（+ `background.opacity`） |
 * | `cover` | 封面**主色**派生的深色（`CoverPalette.darkFrom`） | `background.opacity` |
 * | `cover_image`（本档） | **封面图本身** + 同源取色蒙版 | `background.cover.angle_deg`（+ `background.opacity`） |
 *
 * ⚠️ **两档互不干扰**：`cover_image` 档**不读** `background.color` ——
 *    它由 [dev.cdl.render.CoverBackdrop] 整块烘焙，不走 `backgroundWithOpacity`。
 *    反过来说，`background.color` 只影响 `manual` 档（`cover` 档也不读它）。
 *
 * ## 为什么这个字符串必须放在**公开**文件里
 *
 * 与 [COVER_SHAPE_CIRCLE] 同一条理由：`IslandWindow` 是 `internal`，探针看不到它；
 * 而"注册表 `options` 里的 wire 值与绘制侧比较的值**必须逐字一致**"这条契约
 * 一旦破了就是**静默失效**（用户选了「封面图」，画面还是纯色，不报错、不打日志）。
 * 故抽到公开顶层，由 `IslandPolicyProbe` 把它与注册表 `options` 钉在一起。
 *
 * ⚠️ **注册表那一侧必须写字符串字面量**（不能引用本常量）——
 *    `tools/gen_preference_config.py` 的 `parse_options` 用
 *    `(?:"([^"]*)"|([A-Za-z_][A-Za-z0-9_]*))\s+to\s+"…"` 解析候选值，
 *    **只认裸标识符或字面量**；写成 `dev.cdl.window.BACKGROUND_MODE_COVER_IMAGE`
 *    会被整对**静默丢掉** ⇒ SPW 设置页上少一档（用户根本选不到）。
 *    故这里采用项目既有的"两处写死 + 探针钉同源"做法（同 `cover.shape`）。
 */
const val BACKGROUND_MODE_COVER_IMAGE = "cover_image"

/**
 * **本套配置是否需要"封面图"**（用于取色，或用于 `cover_image` 档整块烘焙）。
 *
 * ## 为什么它是一个**公开纯函数**（2026-10-14 · BUG-201）
 *
 * 这条判断原本以 `val needsCoverImage = …` 的形式**内联**在
 * `IslandWindow.coverImageFor` 里（`internal`），于是**只有真机**在用它。
 * 预览侧（`IslandPreviewPane`）要回答同一个问题（"要不要把示例封面的主色喂给
 * `CoverPalette.resolve`"），却**自己另写了一份判据** ✗：
 *
 * ```kotlin
 * // 预览旧写法（错）
 * val previewCoverRgb = if (snapshot.coverEnabled) demoCoverRgb() else null
 * ```
 *
 * ★★ 它只看了 `cover.enabled`，**漏掉了三个 `*_color_mode == cover` 的同族情形** ——
 * 于是当用户配置为「**不显示封面** + 背景/频谱/边框跟随封面取色」时：
 *   · 真机：`coverImage` 仍会加载 ⇒ 三路取色**全部生效** ✓
 *   · 预览：`previewCoverRgb = null` ⇒ **背景**退回手动色，而 `spectrum()` /
 *     `border()` 仍各自调 `demoCoverRgb()` ⇒ **背景 ≠ 频谱 ≠ 边框** ✗
 *
 * 恰好是用户报的两条（逐字）：
 *   · 「**词岛背景颜色与封面颜色的不相符**」
 *   · 「**频谱条的取色也跟封面取色不一致**」
 * ⇒ 「**预览应从示例封面里取色，且背景/频谱/边框都取同一处**」。
 *
 * ## 判据（本项目的纪律）
 *
 * ★ 「**同一件事实只准有一个定义点**」—— 真机与预览必须调**同一个函数**，
 *   而不是"两边各写一份、靠人记得同步"。这正是 `CoverPalette.resolve`
 *   （09-26 起）与 `bandGeometry` 用过的同一手法。
 *
 * ## 输入为什么是这四个（而不是 `Appearance`）
 *
 * 因为预览拿到的是 `PreviewSnapshot`、真机拿到的是 `Appearance` —— 两者的
 * **公共可观测面**恰是这四个 `String`/`Boolean`。函数只吃它们，故两边都能直接调。
 *
 * @param coverEnabled `cover.enabled`（要不要把封面**画出来**）
 * @param lyricColorMode `main_lyric.color_mode`
 * @param backgroundColorMode `background.color_mode`
 * @param spectrumColorMode `spectrum.color_mode`
 * @return `true` ⇒ 调用方**必须**把封面图准备好（真机加载 / 预览用示例封面）
 *
 * ⚠️ `borderColorMode`（`island.border.color_mode`）**不在入参里**：边框色只有
 *    `manual` / `cover` 两档，而"要不要封面"这个问题已由 `lyricColorMode`
 *    那一项覆盖（两者恒同步由设置页的语义保证）—— ★ 但**真机原名单里也没有它**，
 *    本函数**逐字镜像**真机原名单，不擅自增删（见 `IslandWindow.coverImageFor` 的 KDoc）。
 */
fun needsCoverImageFor(
    coverEnabled: Boolean,
    lyricColorMode: String,
    backgroundColorMode: String,
    spectrumColorMode: String,
): Boolean =
    coverEnabled ||
        lyricColorMode == dev.cdl.render.CoverPalette.COVER ||
        backgroundColorMode == dev.cdl.render.CoverPalette.COVER ||
        backgroundColorMode == BACKGROUND_MODE_COVER_IMAGE ||
        spectrumColorMode == dev.cdl.render.CoverPalette.COVER ||
        // ★★ 2026-10-14：`cover_camo` **与 `cover_flow` 同族** —— 也是"逐柱取封面多色"的档，
        //   也必须进本名单（否则调色板恒空 ⇒ 迷彩档退回单色）✗
        //   ★ 这正是 `IslandWindow.coverImageFor` 的 KDoc 立的判据：
        //     「**新加一个依赖封面的档 ⇒ 必须同时进本名单**」✓
        spectrumColorMode == dev.cdl.render.CoverPalette.COVER_FLOW ||
        spectrumColorMode == dev.cdl.render.CoverPalette.COVER_CAMO

/**
 * `manual` 档底色的**三个通道常量** —— 「默认底色是什么」的**唯一可读来源**。
 *
 * ## 为什么放在这个公开文件里（而不是 `IslandWindow` 的私有 companion）
 *
 * 2026-09-22 新增 `background.color`（COLOR 键）之后，默认色有了**两个引用点**：
 *   · `IslandWindow` 的 `BASE_BACKGROUND`（打包成 `java.awt.Color` 给绘制用）；
 *   · `SettingRegistry` 的 `default = 0xFF14161A.toInt()`（给设置系统用）。
 * 两处一旦漂移，用户会看到「没改设置，底色却和出厂不一样」——
 * 而 `IslandPanel` 是 `private inner class`，它的 companion **外部看不到**，
 * 故默认值不可能从那边派生。⇒ 把通道常量提到**公开顶层**，
 * 让 `IslandWindow` 与探针都能引用它，并由 `RegistryProbe` §8b.1 把
 * 注册表默认值与这里的派生值钉在一起。
 *
 * ★★★ 2026-10-07 **更正（`migration/98` · C-3）**：本段原写「由 `IslandPolicyProbe`…
 * （见该探针 §13）」—— ★ **引错了探针**：`IslandPolicyProbe` 里 `BACKGROUND_BASE_RGB`
 * **0 命中**（它的 §13 是 `coverFillScale`）；真实护栏 = **`RegistryProbe §8b.1`**。
 *
 * ⚠️ **注册表那一侧只能写字面量** `0xFF14161A.toInt()`：
 *    `tools/gen_preference_config.py` 的 `parse_default` 只认字面量 / `0x…toInt()` /
 *    字符串 / 布尔，**认不出跨文件 Int 常量**（认不出时会把裸标识符原样写进 JSON，
 *    那是一个宿主读不懂的默认值）。故"不漂移"由**探针**保证，而不是由派生表达式保证。
 */
const val BACKGROUND_BASE_RED: Int = 0x14

/** 见 [BACKGROUND_BASE_RED]。 */
const val BACKGROUND_BASE_GREEN: Int = 0x16

/** 见 [BACKGROUND_BASE_RED]。 */
const val BACKGROUND_BASE_BLUE: Int = 0x1A

/**
 * 三个通道常量打包成的不透明 ARGB（`#FF14161A`）——
 * `background.color` 的默认值必须与它**逐位相同**。
 */
const val BACKGROUND_BASE_RGB: Int =
    (0xFF shl 24) or
        (BACKGROUND_BASE_RED shl 16) or
        (BACKGROUND_BASE_GREEN shl 8) or
        BACKGROUND_BASE_BLUE

/**
 * 「封面图铺满」在给定旋转角度下的**最小等比缩放系数**。
 *
 * ## 判据
 *
 * 把 `srcWidth × srcHeight` 的原图**等比**放大 `s` 倍、再绕中心旋转 `angleDeg` 之后，
 * 它的**轴对齐外接盒**必须盖住 `targetWidth × targetHeight`。
 * 本函数返回满足该条件的**最小** `s`。
 *
 * ## ★★ 推导（不是手感值；**这一条必须能被探针穷举**）
 *
 * 旋转后的图在**它自己的坐标系**里是 `[−dw/2, dw/2] × [−dh/2, dh/2]`
 * （`dw = s·srcWidth`，`dh = s·srcHeight`）。世界坐标里的点 `p = (x, y)` 落在
 * 旋转后的图内，当且仅当把它转回图坐标后仍落在那个矩形内：
 *
 * ```
 * u =  x·cosθ + y·sinθ      |u| ≤ dw/2
 * v = −x·sinθ + y·cosθ      |v| ≤ dh/2
 * ```
 *
 * 要"盖住目标矩形"，只需（且必须）**目标矩形的四个角**都在图内。取四角
 * `(±W/2, ±H/2)` 上的极值：
 *
 * ```
 * max|u| = (W/2)·|cosθ| + (H/2)·|sinθ| ≤ dw/2 = s·srcWidth/2
 * max|v| = (W/2)·|sinθ| + (H/2)·|cosθ| ≤ dh/2 = s·srcHeight/2
 * ```
 *
 * ⇒
 *
 * ```
 * s = max( (W·|cosθ| + H·|sinθ|) / srcWidth ,
 *          (W·|sinθ| + H·|cosθ|) / srcHeight )
 * ```
 *
 * `θ = 0` 时退化为 `max(W/srcWidth, H/srcHeight)` —— 正是经典 cover 系数 ✓。
 * 该条件是**充分必要**的（极值处约束恰好取等），故这是**最小**的铺满系数。
 *
 * ## ⚠️⚠️ 与"外接盒公式"的区别（**差一个转置，后果是四角露底**）
 *
 * 一个容易写错的写法是把 `|cos|/|sin|` 乘在**源**尺寸上：
 * `s' = max(W / (w·|cos| + h·|sin|), H / (w·|sin| + h·|cos|))`。
 * 那个式子的含义是"**旋转后的外接盒**盖住 `W×H`"—— 而**外接盒盖住 ≠ 矩形盖住**。
 *
 * 反例（`src = 1×1`、`target = 1×1`、`θ = 45°`）：`s' = 0.7071`，
 * 旋转后是一个菱形 `|x| + |y| ≤ 0.5`，而目标矩形的角 `(0.5, 0.5)` 满足
 * `|x| + |y| = 1.0 > 0.5` ⇒ **角是空的**。正确公式给出 `s = √2 ≈ 1.4142`。
 * 这条反例由 `IslandPolicyProbe` 的 73 角度穷举**当场**抓出（见其 §13）。
 *
 * ## 边界
 *
 * 任一边长非正 / 非有限 → 返回 `1f`（**不抛异常**：渲染路径不抛；`1f` = 不改观感）。
 * 角度按 `360°` 归一（`|cos|`/`|sin|` 本就以 180° 为周期，归一只是为了可复现）。
 *
 * @param angleDeg 旋转角度（度）。用户可调 `0..360`（`background.cover.angle_deg`）。
 * @param srcWidth 原图宽（px）
 * @param srcHeight 原图高（px）
 * @param targetWidth 要铺满的矩形宽（px）—— 词岛**岛体**宽
 * @param targetHeight 要铺满的矩形高（px）—— 词岛**岛体**高
 */
fun coverFillScale(
    angleDeg: Float,
    srcWidth: Float,
    srcHeight: Float,
    targetWidth: Float,
    targetHeight: Float,
): Float {
    if (!srcWidth.isFinite() || !srcHeight.isFinite()) return 1f
    if (!targetWidth.isFinite() || !targetHeight.isFinite()) return 1f
    if (srcWidth <= 0f || srcHeight <= 0f || targetWidth <= 0f || targetHeight <= 0f) return 1f
    val deg = if (angleDeg.isFinite()) ((angleDeg % 360f) + 360f) % 360f else 0f
    val rad = Math.toRadians(deg.toDouble())
    // `abs` 把负角/超过 90° 的象限折回第一象限 —— 外接盒只关心轴向投影的绝对值。
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val scaleX = (targetWidth * c + targetHeight * s) / srcWidth
    val scaleY = (targetWidth * s + targetHeight * c) / srcHeight
    return maxOf(scaleX, scaleY)
}

/**
 * 频谱「逐柱末端淡出」的**关键帧分数**（供 `LinearGradientPaint` 用）。
 *
 * ## ★ 为什么要抽成公开纯函数（2026-09-21 真机崩溃）
 *
 * 用户报：**一开启「顶端淡出」就抛异常**
 * ```
 * java.lang.IllegalArgumentException: Keyframe fractions must be increasing: 0.1160751
 *   at java.awt.LinearGradientPaint.<init>
 *   at IslandWindow.spectrumTipFadePaint(IslandWindow.kt:6839)
 * ```
 *
 * **根因**：对称分支用四段色标 `(0, f, 1-f, 1)`，这要求 **`f <= 1-f`，即 `f <= 0.5`**；
 * 而当时的 `f = 1 - fade/h`，`fade = min(柱高×30%, 6px)` 且 ≥1 ——
 * 于是 `f` 在**几乎所有柱高下都是 0.70~0.94** → **必然越界**。
 * 用户配置里 `spectrum.symmetric = true`，所以"一开就崩"。
 *
 * ## 顺带修正：单侧分支的淡出带**位置**也错了
 *
 * `f` 的语义应当是"**淡出带占柱高的比例**"（`fade / h`）——
 * 因为渐变是 `topY → bottomY`（分数 0 在**柱尖**、1 在柱根），
 * 色标 `(clear, solid, solid)` 配 `(0, f, 1)` 才是"**只有柱尖那 `fade` 像素在淡出**"。
 * 原写法 `1 - fade/h` 让淡出覆盖了从柱尖到接近柱根的大半根柱子 ——
 * 即它自己 KDoc 里明确不要的「**整条发虚**」。
 *
 * ## 不变量（探针穷举的那条）
 *
 * 返回的分数数组**必须严格递增**，否则 `LinearGradientPaint` 构造即抛。
 * 故对称分支把 `band` 夹到 `<= 0.49`（留出 `1 - band > band`），
 * 单侧分支夹到 `<= 0.99`（`band == 1` 会让后两个停点相等）。
 *
 * ## ★★ 2026-10-03（㉒ 收口）：`cornerRadiusPx` **不再有默认值**（原记见下）
 *
 * **原记**（本参数 2026-09-29 引入时）：`cornerRadiusPx: Float = 0f`，
 * 理由写的是"默认 `0f` ⇒ `maxOf(base, 0f) == base` ⇒ **与改动前逐值相同**"
 * （= 保证"新增形状维度时零回归"）。
 *
 * **实为**：那个默认值**把一处真实的漏接藏了起来**。本函数在**生产**里只有一个调用点
 * （`IslandWindow.spectrumTipFadePaint`），而它当时写的是 `spectrumTipFadeFractions(h, symmetric)`
 * —— **没传第三个实参** ⇒ 形状项**恒取 `0f`** ⇒ 用户报的「**圆角档顶端淡出与方角档差别不大**」
 * 在这条路径上**从未修好**（形状项只在 `spectrumFadeBandPx` 那一处生效）。
 * ★ 症状与"参数不存在"完全一样，**不报错、不警告** —— 这是本项目的**已知病**：
 * 「声明了 ≠ 实现了」（纪律 15 同族：同一份数据两个收敛点，只接了一处）。
 *
 * ⇒ **修法（两条同时做，缺一不可）**：
 *  1. **删掉默认值** —— 于是"漏传"由**运行时静默**变成**编译期失败**
 *     （与 `SpectralPainter.paint` 的 `fillArgb` / `tipFade` / `brushes` **同一条纪律**：
 *      "刻意不给默认值，是为了让第二条调用路径漏传**编译期就失败**"）；
 *  2. 交叉一致性断言（两处实现**逐值同带**）—— 见 `SpectrumFadeBandProbe` §E。
 *
 * ⚠️ **代价（如实写明）**：调用方必须显式写 `0f`。这是**有意**的 ——
 * "方角档 / 实心档传 `0f`"从此是**调用方的显式声明**，而不是一个可以被忽略的默认。
 *
 * @param barHeight 这一柱的纵向高度（px，**已 `coerceAtLeast(1f)`**）
 * @param symmetric 是否上下对称双峰（此时两端各自淡出）
 */
fun spectrumTipFadeFractions(
    barHeight: Float,
    symmetric: Boolean,
    /**
     * 本帧柱体的**圆头半径**（px）。★ 方角档 / 实心档**显式传 `0f`**（**无默认值**，理由见上）。
     *
     * ★★ 2026-09-29（`recon/69` §5 风险 1）：本函数是**淡出带的第二处实现**，
     * 与 `render/SpectrumGeometry.spectrumFadeBandPx` **同公式**（`× 0.30` + 上限 `6`）。
     * ⇒ 两者**必须同批改**，否则"改了那处、漏了这处"会让症状**时好时坏**
     * （哪条生效取决于调用方）—— 这正是**纪律 15**（同一份数据两个收敛点）的形态。
     */
    cornerRadiusPx: Float,
): FloatArray {
    // ⚠️ 2026-09-21：**`coerceAtLeast` 兜不住 `NaN`** —— Kotlin 的 `coerceAtLeast` 内部用
    //   `<` 比较，而 `NaN < 1f` 恒 `false` → **原值 `NaN` 被原样返回**，一路传到
    //   `LinearGradientPaint` 的色标分数上（它**不抛**，但笔刷退化：整柱不可见/全黑）。
    //   `±Inf` 同样过不了 `coerceAtLeast`（`Inf` 通过、`-Inf` 被夹到 1f，但 `+Inf` 会让
    //   `band = fade / Inf = 0` → 对称分支 `[0, 0, 1, 1]` → **JDK 抛**）。
    //   故这里显式要求**有限**：没有可信几何时退化为"最小柱高"这个保守值。
    //   这是**零代价加固** —— 真实几何永远是有限正数，走的是同一条路径。
    val h = barHeight.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    // 淡出带宽度：柱高的 30%，上限 6px（"仅末端"的量化），下限 1px（极矮柱也软化一点）。
    // ★ 2026-09-29：形状项 —— 带**至少覆盖圆头半径**（与 `spectrumFadeBandPx` 同规则）。
    //   默认 `0f` ⇒ `maxOf(base, 0f) == base` ⇒ **与改动前逐值相同**。
    val base = (h * 0.30f).coerceAtMost(6f).coerceAtLeast(1f)
    val byShape = cornerRadiusPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val fade = maxOf(base, byShape)
    // 上限必须**严格**留出余地 —— 相等也会被判"没有递增"。
    val upper = if (symmetric) 0.49f else 0.99f
    // ⚠️ 2026-09-21：**这里曾经加过一个 `1e-6f` 下界，现已删除** —— 理由是它与另一处修复**冗余**，
    //   而冗余的那个**制造了与断言的不一致**。
    //
    // ## 当时的推理（不完整）
    //
    // "`h = +Inf` 时 `band = fade/Inf = 0` → 对称分支 `[0,0,1,1]` → JDK 抛" ⇒ 加下界。
    //
    // ## 实为（`triage-spectrum` 指出，我复核成立）
    //
    // 上面那行 `h = barHeight.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f`
    // **已经把 `+Inf` / `NaN` 全部兜成有限值 ≥ 1** ⇒ `fade ∈ [1, 6]` ⇒ `band = fade/h > 0`
    // **恒成立**，下界**永远不生效** —— 除了在 `h > 6e6` 时它**会**生效：
    //   `band = 6/h < 1e-6` ⟺ `h > 6e6`，那时下界把 `band` 抬到 `1e-6`，
    //   于是 `band × h = 1e-6 × h > 6` —— **与探针里"淡出带绝对宽度 ≤ 6px"那条断言直接冲突**。
    //
    // ⇒ **两个修复互相冗余，而冗余的那个引入了新矛盾。删掉冗余的。**
    //   `band` 的取值范围由 `fade ∈ [1,6]` 与 `h ≥ 1` 天然保证是 `(0, 6]`，不需要额外夹。
    val band = (fade / h).coerceAtMost(upper)
    return if (symmetric) {
        floatArrayOf(0f, band, 1f - band, 1f)
    } else {
        floatArrayOf(0f, band, 1f)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 「窗口恒为展开尺寸」的全部算术（2026-09-22，BUG-125 第三次复发）
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 用户原话（第三次报同一件事）
//
// > 「**上方控件的弹出收回动画时间内词岛的上下闪动依旧，能不能彻底根治它？**」
//
// ## 为什么前两次都没治好（结构原因）
//
// | 轮次 | 修法 | 为什么不够 |
// | --- | --- | --- |
// | 一 | `setLocation` + `setSize` → 一次 `setBounds` | 只消除了"两次 OS 调用之间的中间帧"，**窗口矩形本身仍在跳** |
// | 二 | 岛体形状改由 `bandGeometry` 连续驱动 | 只让**画出来的岛体**连续，而窗口矩形**仍在悬停那一帧跳变** ⇒ 一帧旧内容出现在新位置 |
//
// 两次都在修**同一帧里的两个量之一**。真正的判据是：**悬停路径不该碰窗口几何**。
//
// ## 现判据（本段三条函数就是它的全部内容）
//
// 1. **窗口目标尺寸与悬停态无关** —— 恒为 `base + 带高`（[windowTargetBounds] 的入参里
//    **没有**任何悬停量，这是结构上的保证，不是靠调用方自觉）；
// 2. **岛体几何与悬停态无关** —— `bandGeometryNow` 恒用真实带高（见其注释），
//    于是收起态那条"预留高度"**落在岛体形状之外**，观感与改动前一致；
// 3. **命中判定用岛体矩形**（[islandHitTest]）—— 否则那条**永久透明**的预留带
//    会让 `inside` 恒为 `true` ⇒ **控件永远收不回去**（这是本修法唯一的真实代价）。
//
// ## 一个必须一起改的推论：`position = TOP` 时窗口 y 要**永久**上移一条带高
//
// 窗口恒为展开尺寸 ⇒ 它比岛体多出的一条带高必须落在**某一侧**：
//   · `BOTTOM`（默认）→ 多出来的一段在岛体**下方**，窗口 y 就是用户摆的位置（**零变化**）；
//   · `TOP` → 多出来的一段在岛体**上方**（否则带子没地方画），故窗口 y = 用户位置 − 带高。
//
// 这不是新引入的偏移 —— 改动前**展开态**的窗口 y 就是"用户位置 − 带高"
// （`anchoredWindowY`）。本段只是把那个偏移从"只在展开态成立"改成**恒成立**，
// 于是"位置档位切换"与"启动恢复位置"都必须按同一个口径换算（见 [windowYOffsetPx]）。

/**
 * 窗口 y 相对**逻辑位置**（用户摆的那个位置）的偏移量（px）。
 *
 * - `BOTTOM` → `0`：窗口顶边就是用户摆的位置（多出的带高在**下方**）；
 * - `TOP` → 带高：窗口顶边比用户摆的位置**高一条带高**（多出的那段在**上方**）。
 *
 * ⚠️ 它必须与 [anchoredWindowY] **同源**：后者把"窗口顶边相对逻辑位置"写成
 * `if (stripAtTop) oldY - heightGrowth else oldY`，本函数就是那个 `heightGrowth`
 * 在"窗口恒为展开尺寸"下的取值（= 带高，而**不是**"本次尺寸增量"）。
 * 两者若不一致，表现是"每次改设置词岛就往上/下爬一条"。
 */
fun windowYOffsetPx(stripHeightPx: Float, stripAtTop: Boolean): Int =
    if (stripAtTop && stripHeightPx.isFinite()) {
        stripHeightPx.roundToInt().coerceAtLeast(0)
    } else {
        0
    }

/** 一次窗口几何更新的结果。见 [windowTargetBounds]。 */
data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** 本次应有的 [windowYOffsetPx] —— 调用方**必须**把它记下来（下次算逻辑位置要用）。 */
    val yOffsetPx: Int,
)

/**
 * 一次窗口几何更新的**全部算术** —— `IslandWindow.applyWindowSize` 的唯一决策点。
 *
 * ## 为什么抽成公开纯函数（而不是留在 `applyWindowSize` 里）
 *
 * 本次修法的**全部意义**是一条可数的不变量：
 *
 * > **从收起态进入悬停、直到完全展开，`setBounds` 调用次数 == 0。**
 *
 * 而 `IslandWindow` 是 `internal`、`setBounds` 又只能真机调用 ——
 * 探针**够不到**。把决策抽到这里之后，探针可以喂一整个"启动 → 悬停 11 帧"的
 * 序列，逐帧断言**目标矩形恒等于当前矩形**（⇒ 调用点不可能走到 `setBounds`）。
 * 这正是本项目那条纪律的兑现：「**不可证伪的检查等于没有检查**」。
 *
 * ## 为什么入参里**没有**任何悬停量（这才是修法本身）
 *
 * 目标尺寸恒为 `baseHeightPx + 带高`，**不**再判 `bandReserved()`
 * （那正是"悬停才加高"的来源）。于是悬停期间目标与当前恒等 ⇒ `setBounds` 不可达。
 *
 * @param oldX/oldY/oldWidth 当前窗口几何
 * @param currentYOffsetPx 当前窗口 y 相对逻辑位置的偏移（= 上次返回的 `yOffsetPx`）
 * @param baseWidthPx **收起态岛宽**（`IslandWindow.baseWidthPx`）。
 *   ⚠️ 2026-09-22 起它**不再等于窗口宽** —— 窗口宽 = 本值 + [expandWidthGrowPx]（横向长大增量），
 *   岛体在窗口内居中。两者的关系只在本函数里定义一次。
 * @param baseHeightPx 收起态岛高（`IslandWindow.baseHeightPx`）
 * @param stripHeightPx 控件带需要的高度（`PlaybackControls.stripHeight`，`Float`）
 * @param stripAtTop `controls.position = TOP`
 */
fun windowTargetBounds(
    oldX: Int,
    oldY: Int,
    oldWidth: Int,
    currentYOffsetPx: Int,
    baseWidthPx: Int,
    baseHeightPx: Int,
    stripHeightPx: Float,
    stripAtTop: Boolean,
    widthGrowOverridePx: Int = -1,
): WindowBounds {
    // ⚠️ 量化**一次**（`roundToInt`，不是截断）—— 与绘制侧 `bandGeometry` 共用同一个值，
    //    否则 `fraction = 1` 时岛体会比窗口差最多 1px（被裁一条边 / 留一条缝）。
    val stripQ = if (stripHeightPx.isFinite()) stripHeightPx.roundToInt().coerceAtLeast(0) else 0
    val height = (baseHeightPx + stripQ).coerceAtLeast(0)
    // ── ★ 2026-09-22：窗口宽 = 岛宽 + **横向长大增量** ──────────────────────────
    //
    // 用户第 ① 条要求"词岛长大"，而"悬停路径不碰窗口几何"这条不变量**必须同时成立** ——
    // 两者的结合点就是这一行：**窗口从一开窗起就是展开态宽**（含增量），
    // 岛体在窗口内**居中**并随进度连续变宽（见 [bandGeometry]）。
    // 于是窗口宽与悬停态**无关**，目标矩形在整段动画里恒等于当前矩形 ⇒ `setBounds` 不可达。
    //
    // ⚠️ 增量来自 [expandWidthGrowPx]（= 控件带高，取偶数）—— 与绘制侧**同一个**函数，
    //    故"窗口多宽"与"岛体最多长多宽"不可能漂移。
    val width = (baseWidthPx + if (widthGrowOverridePx >= 0) widthGrowOverridePx else expandWidthGrowPx(stripHeightPx)).coerceAtLeast(0)
    // 逻辑位置：把当前的偏移**加回去**，否则"从 TOP 切到 BOTTOM"会丢掉那一条带高。
    val logicalY = oldY + currentYOffsetPx
    // 偏移算术复用 [anchoredWindowY]（它守着"歌词屏幕位置不变"这条用户诉求）——
    // 此处第二个入参是"窗口相对收起态岛体的高度增量"，恒为带高（见 [windowYOffsetPx]）。
    val y = anchoredWindowY(logicalY, stripQ, stripAtTop)
    // 宽度变化时以**中心**为锚（`centeredWindowX`）——只在宽度真的变了时才挪位置，
    // 否则每次高度变化词岛都会左右跳一下。
    val x = if (oldWidth != width) centeredWindowX(oldX, oldWidth, width) else oldX
    return WindowBounds(x, y, width, height, windowYOffsetPx(stripHeightPx, stripAtTop))
}

/**
 * 光标是否落在**岛体矩形**内（面板局部坐标）—— `IslandWindow.insideIsland` 的唯一判据。
 *
 * ## 为什么不能用"窗口/面板矩形"（这是本次修法唯一的真实代价）
 *
 * 窗口恒为展开尺寸之后，它比岛体多出一条**永久透明**的预留带
 * （`BOTTOM` 在下方、`TOP` 在上方）。若仍按窗口矩形判"鼠标在不在岛内"：
 *
 * ```
 * 鼠标落在预留带上 → inside = true → 状态机认为"还没离开" → 控件**永远收不回去**
 * ```
 *
 * 即：修好了闪动，却换来"控件收不回去"——**比原缺陷更糟**（用户没法关掉那条控件条）。
 *
 * ## 判据取"岛体矩形"而不是"窗口矩形减去带高"
 *
 * 两者在收起态等价，但**展开态**只有前者是对的：展开时岛体**就是**整个窗口
 * （`bandGeometry` 的 `fraction = 1`），而"窗口减带高"会把已经长出来的控件条
 * 判成"岛外" ⇒ 鼠标移到控件条上就判为离开 ⇒ 控件条立刻收回（点了就消失）。
 *
 * ## ★ 2026-09-22 补：**横向**也要用岛体矩形（同一条代价的第二半）
 *
 * 窗口现在不仅恒为展开**高**，还恒为展开**宽**（岛体在窗口内居中，
 * 两侧各让出 `expandWidthGrowPx / 2`）。故"面板矩形"在**横向**也多出两条
 * **永久透明**的预留区 —— 鼠标落在那两条上若判为"岛内"，同样会让控件收不回去。
 *
 * 判据因此取 [BandGeometry.islandLeft] / [BandGeometry.islandWidth]（岛体矩形），
 * 而不是面板宽。⚠️ `islandWidth <= 0`（首帧之前没有可信宽度）→ 恒 `false`，
 * 与"面板宽 <= 0 → false"同一条保守回退。
 *
 * @param x/y 面板局部坐标
 * @param geometry 本帧的岛体几何（`bandGeometryNow()`）
 */
fun islandHitTest(x: Float, y: Float, geometry: BandGeometry): Boolean {
    if (!x.isFinite() || !y.isFinite()) return false
    val width = geometry.islandWidth
    if (width <= 0) return false
    if (x < geometry.islandLeft || x >= geometry.islandLeft + width) return false
    return y >= geometry.islandTop && y < geometry.islandBottom
}

// ══════════════════════════════════════════════════════════════════════════════
// 「岛体屏幕矩形」—— **唯一**取法（㉙ 吸附 / ㉚ 找回居中 / 将来 ㉗ 出屏判定，三处共用）
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 为什么必须收成一处（这不是"抽个 helper"，是一条被踩过两次的纪律）
//
// 词岛有**三个不是一回事**的层次（`未完成工作总账.md` §3.9 已单列归档）：
//
// | 层 | 代码里的名字 | 尺寸 |
// |---|---|---|
// | **窗口** | `window.location` / `setBounds` | ★ **恒为展开态尺寸**（动画期间只改一次） |
// | **面板** | `panel.width/height` | ≈ 窗口（无边框） |
// | **岛体** | `BandGeometry` 的 `islandLeft/islandTop/islandWidth/islandHeight` | ★ **随展开进度 `f` 变化** |
//
// 差量 = **永久透明的预留区**（`BandGeometry` KDoc 逐字："收起态下岛体两侧各有一段
// **永久透明**的预留区"）。按"窗口"算一切几何量都会偏一个预留量：
//
// | # | 场景 | 按窗口算的后果 |
// |---|---|---|
// | 1 | 贴边吸附的判定量（㉙） | 看着还有几十 px 空隙时就已吸附 |
// | 2 | 找回词岛的居中（㉚） | `controls.position = TOP` 时岛体偏下 ≈ `strip/2` |
// | 3 | 将来的出屏判定（㉗） | 各偏一个预留量 |
//
// ⇒ 用户已在两轮对话里各踩一次（㉙ 一次、㉚ 一次）⇒ **同一语义禁止两个实现点**。
//
// ## 坐标系（这是本段唯一的"纪律"）
//
// 窗口给的是 **AWT 逻辑坐标**（`setLocation` / `location`），岛体给的是**面板局部坐标**。
// 两者相加即得**逻辑坐标下的岛体屏幕矩形** —— 全程逻辑，**不出现任何物理像素 API**
// （`tools/check_coord_hygiene.py` 会守；㉚ 的 S-C 是用户点名的要求）。
// ⚠️ 若将来需要物理像素（例如截图区域），**换算必须发生在消费端**
// （`physicalBounds` 是那一步的唯一实现点）—— 不得污染本函数。

/**
 * 岛体在**屏幕逻辑坐标**下的外接矩形 —— **唯一**取法。
 *
 * ## 公式（一行，但它是三处共用的判据输入）
 *
 * ```
 * 岛体屏幕矩形 = window.location + (bg.islandLeft, bg.islandTop, bg.islandWidth, bg.islandHeight)
 * ```
 *
 * ## 退化契约（探针逐条断言）
 *
 * - `islandWidth <= 0` ⇒ 返回 `null`（**不是**零宽矩形 —— 那会被下游当"一个很窄的岛体"
 *   而算出"居中了但差几 px"这种**静默错**）。零宽只有一种含义：**本帧还没有可信宽度**
 *   （首帧之前，`baseWidthPx <= 0`，见 `bandGeometry` 的同一个回退）。
 * - `bg` 的任一成员非有限 ⇒ 返回 `null`（`NaN` 参与加减会把结果污染成 `NaN`，
 *   而 `Rectangle2D` 不会报错，只会静默给出不可用的矩形）。
 * - 返回值**可能是负坐标**（岛体在屏幕左上之外）—— 那是**合法**的中间状态，
 *   调用方自己决定"要不要钳制"。本函数**不做任何钳制**：
 *   钳制是策略，取法是算术，混在一起会让"取到的是什么"变得不可断言。
 *
 * @param windowX 窗口左上角的屏幕 x（`window.location.x`，**AWT 逻辑坐标**）
 * @param windowY 窗口左上角的屏幕 y（同上）
 * @param bg 本帧的岛体几何（`bandGeometryNow()`）
 */
fun islandScreenRect(
    windowX: Int,
    windowY: Int,
    bg: BandGeometry,
): Rectangle2D.Float? {
    if (bg.islandWidth <= 0) return null
    val l = bg.islandLeft
    val t = bg.islandTop
    val w = bg.islandWidth.toFloat()
    val h = bg.islandHeight.toFloat()
    if (!l.isFinite() || !t.isFinite() || !w.isFinite() || !h.isFinite()) return null
    return Rectangle2D.Float(windowX + l, windowY + t, w, h)
}

/**
 * 「找回词岛」时，为让**岛体外接矩形中心**落在屏幕中心，窗口左上角该放在哪。
 *
 * ## 它修的是什么（用户原话，2026-09-29 21:2x）
 *
 * > 「**找回词岛应该使词岛立即回到屏幕正中央（注意逻辑/物理位置）并置顶
 * >   （不打开设置项，只是维持置顶状态直到被操作）**」
 *
 * 原实现走 `window.setLocationRelativeTo(null)` —— 那让**窗口**居中。
 * 而**窗口比岛体大**（纵向多出控件带 `stripQ`，横向在非左锚态两侧各让 `growQ/2`）：
 *
 * ```
 * 底层几何（bandGeometry 逐行读出，stripAtTop = true）：
 *   岛体高 = base + stripQ·f          岛体顶 = (stripQ + base) − 岛体高
 *   ⇒ f = 1（展开态）时 岛体 == 整个窗口      ← 只有这一帧"窗口居中 == 岛体居中"
 *   ⇒ f = 0（收起态）时 岛体顶 = stripQ       ← ★ 窗口居中 ⇒ 岛体整体偏下 stripQ/2
 * ```
 *
 * 真机 `stripQ ≈ 76` ⇒ **偏下 ≈ 38 px** —— 正是用户看到的"没在正中央"。
 * （`BOTTOM` 档同构：偏上同量。横向同构：非左锚态偏左/右 `growQ/2`。）
 *
 * ## 与 `restoreSavedPosition` 的关系（同一条纪律，不是两套算术）
 *
 * 那条路径（启动恢复）用的是"**中心必须在屏内**"，本函数用的是"**岛体中心 == 屏心**"。
 * 两者**不是**重复实现：前者是**可用性检验**（越界回退），后者是**主动归位**（用户点击）。
 * 共用的量只有"屏幕 bounds"与"岛体尺寸"，两者都由调用方传入（本函数不读系统）。
 *
 * ## 为什么不做"钳制到屏幕内"
 *
 * 居中总会可见（只要岛体不大于屏幕）—— 这正是"**绝不制造用户无法自救的状态**"
 * 那条纪律的兑现（`resetToPrimaryScreen` 的 KDoc 逐字论证过）。
 * 若岛体**大于**屏幕（极端窄屏 / 巨字号），居中会让两边各出界 `(islandW − screenW)/2`，
 * 仍然"中心可见" —— 比贴边更好（贴边时用户看不到中心内容）。
 * 故本函数**不钳制**，并把"不钳制"作为一条**探针可断言的性质**。
 *
 * ## 坐标系（S-C）
 *
 * 全程 **AWT 逻辑坐标**：`screenBounds` 由调用方用
 * `GraphicsEnvironment.maximumWindowBounds` 给出（**逻辑**）；
 * 返回值直接喂给 `window.setLocation`（**逻辑**）。
 * ★ **本函数不出现 `GetWindowRect`，也不接受物理像素入参** ——
 * 物理/逻辑的换算只允许发生在 `physicalBounds` 那一个地方。
 *
 * @param screenX 屏幕可用区左上角 x（`maximumWindowBounds.x`，**逻辑**）
 * @param screenY 同上
 * @param screenW 屏幕可用区宽（**逻辑**）
 * @param screenH 屏幕可用区高（**逻辑**）
 * @param windowW 窗口宽（`window.width`）—— 用于把"岛体中心"换算回"窗口左上角"
 * @param windowH 窗口高（`window.height`）
 * @param bg 本帧的岛体几何
 * @return 窗口左上角该在的逻辑坐标；**岛体不可用**（`islandScreenRect` 为 `null`）
 *   时返回 `null`（调用方应退化为"什么都不动"，而**不是**猜一个位置）。
 */
fun centeredWindowLocationForIsland(
    screenX: Int,
    screenY: Int,
    screenW: Int,
    screenH: Int,
    windowW: Int,
    windowH: Int,
    bg: BandGeometry,
): java.awt.Point? {
    if (screenW <= 0 || screenH <= 0) return null
    if (windowW <= 0 || windowH <= 0) return null
    val l = bg.islandLeft
    val t = bg.islandTop
    val islandW = bg.islandWidth
    val islandH = bg.islandHeight
    if (islandW <= 0 || islandH <= 0) return null
    if (!l.isFinite() || !t.isFinite()) return null

    // ★ 屏心（用 2·center 与 2·left 比较的等价形式做整数算术，避免半像素）
    //   岛体中心 = window + (l + islandW/2, t + islandH/2)
    //   令它 == 屏心 ⇒ window = 屏心 − (l + islandW/2, t + islandH/2)
    //
    // ⚠️ 用 `roundToInt` 而不是截断：`islandW/2` 在奇宽时是 `.5`，
    //    截断会让"奇数宽岛体"系统性偏 0.5 px（多屏拼接时可见的 1 px 缝）。
    val islandCenterInWindowX = l + islandW / 2f
    val islandCenterInWindowY = t + islandH / 2f
    val screenCenterX = screenX + screenW / 2f
    val screenCenterY = screenY + screenH / 2f
    val wx = (screenCenterX - islandCenterInWindowX).roundToInt()
    val wy = (screenCenterY - islandCenterInWindowY).roundToInt()
    return java.awt.Point(wx, wy)
}

/**
 * **字形命中的完整判据** —— `IslandPanel.hitGlyph` / `hitCover` 调用方的**唯一出口**。
 *
 * ## 它修的是什么（用户第 ② 条）
 *
 * 用户原话：
 * > 「**字形命中时甚至鼠标还在框外就命中了。**」
 *
 * ## 根因：两条判据各自独立，而「字格命中域」比岛体宽得多
 *
 * | 判据 | 定义 | 谁算 |
 * | --- | --- | --- |
 * | 命中字形 | 点落在某个**字格**（advance 区间 × 行高）上 | `render/HitGeometry.kt` 的 `glyphHitBox` |
 * | 在岛体内 | 点落在**岛体矩形**内 | [islandHitTest] |
 *
 * `glyphHitBox` 的宽度是**整行 `advance` + 余量**，而它的落点 `baselineX` 与岛体左边缘
 * **不是同一个锚**，两个方向都会越界：
 *
 * 1. **收起态（`fraction = 0`）横向越界** —— `alignBaselineX(width, …)` 里的 `width` 是
 *    **面板宽**，而窗口恒为展开宽、岛体在窗口内**居中**（[BandGeometry.islandLeft]）
 *    ⇒ 文本区右边缘可以落在岛体右边缘**之外**（实测见 `GlyphHitOutsideProbe` §3）。
 * 2. **长歌词滑移时整体平移出岛** —— `layout.advance > availWidth` 时
 *    `baselineX += marqueeOffsetForProgress(...)`（**负数**，最多 `−overflow`）
 *    ⇒ 整条命中域可以滑到岛体**左侧之外**（实测最大越界量同样在 §3）。
 *
 * ## 修法：把「在岛内」作为**合取项**，而不是去收紧字格
 *
 * 收紧 `glyphHitBox` 是**错的方向**：它会把"点在汉字正中央"这类**正确**的命中一起挡掉
 * （`GlyphHitTester` 的类 KDoc 实测：汉字墨迹只占包围盒 **29%**，「命」字中心恰是笔画空隙
 * ⇒ 6 个字里有 3 个点不中）。而"在岛内"是**已经算好**的量 ——
 * `onPointer` 里的 `inside` 就是 `insideIsland` —— 取合取**零额外几何**。
 *
 * ## 附带效果：与第 ① 条同源
 *
 * 进入判据（本函数）与维持判据（`HoverState.update` 的 `inside`）现在**都含
 * 「在岛体内」这一项**，且 `inside` 是同一个量 ⇒ "命中"与"维持"不可能由展开本身翻转
 * ⇒ 第 ① 条的反馈环在结构上不存在（详见 `IslandWindow.onPointer` 的 KDoc）。
 *
 * ## 语义
 *
 * 「字形命中」的完整含义是「鼠标**落在词岛里**、且**压在一个字（或封面）上**」。
 * 注册表该项的描述「悬停到词岛**可见区域**即展开」里的"可见区域"就是岛体 ——
 * 不含面板上那两条**永久透明**的预留带。
 *
 * @param insideIsland 点是否在**岛体矩形**内（[islandHitTest]，即 `insideIsland`）
 * @param glyphOrCoverHit 点是否命中**字格**或**封面**（`hitGlyph || hitCover`）
 */
fun glyphHitAccepted(insideIsland: Boolean, glyphOrCoverHit: Boolean): Boolean =
    insideIsland && glyphOrCoverHit

/**
 * **绘制路径**的控件布局 —— `IslandWindow.controlsDrawLayout` 的**唯一**决策点。
 *
 * ## 它修的是什么（2026-09-22，用户第 ①② 条，同一个根因）
 *
 * 用户原话：
 *
 * > ① 「**改为词岛轮廓命中之后词岛一直在展开收缩反复。**」
 * > ② 「切换为控件向上方展开之后，**词岛收缩状态词岛内容消失，频谱又上移**。」
 *
 * ## 根因：**同一份「内容区」有两份互相矛盾的推导**
 *
 * 改动前 `controlsDrawLayout()` 按"带子是否被预留"分了两支：
 *
 * ```
 * if (!bandReserved())  Layout(lyricsTop = 0f,        lyricsHeight = baseHeightPx)  // 收起态
 * else                  layoutOf(..., stripQ, ...)    // lyricsTop = stripQ（TOP 档）
 * ```
 *
 * 而**岛体形状**由 [bandGeometry] 给出，它的 `lyricsTop` 是
 * `if (stripAtTop) stripQ else 0`（**与动画进度 `f` 无关**）。于是 `position = TOP` 时：
 *
 * | 状态 | 内容区（面板坐标） | 岛体矩形（面板坐标） | 结果 |
 * |---|---|---|---|
 * | 收起（`!bandReserved`） | `[0, base]` | `[stripQ, stripQ+base]` | 内容**整体落在岛体之外**，被 `g2.clip = shape` 裁掉 |
 * | 展开（`bandReserved`） | `[stripQ, stripQ+base]` | `[0, base+stripQ]` | 内容在岛体内 |
 *
 * 两个症状都由这一张表解释：
 *
 * ① **收起态内容消失 / 频谱上移** —— 那一行"内容落在岛体之外"。
 * ② **自激振荡** —— 内容区在**屏幕上**整体平移 `stripQ`（窗口 `y` 与 `f` 无关，
 *    见 [windowYOffsetPx]），而字形命中域就是内容区里画出来的那几行
 *    （`IslandPanel.drawnRows` 由 `drawLyricSlots(..., lyricsTop, lyricsHeight, ...)` 登记）：
 *    ```
 *    光标压在字上 → 命中 → 展开 → 字形随内容区上移 stripQ 离开光标 → 未命中
 *                 → 250ms 后收起 → 字形回到光标处 → 又命中 → …
 *    ```
 *    即命中判据**随动画在屏幕上平移** ⇒ 判据与它自己的结果构成反馈环。
 *
 * ## 修法：**判据不含动画状态**（结构上，不是靠调参）
 *
 * 内容区与动画进度无关 —— 这是**用户明确要求**的另一面：
 * 「歌词屏幕位置不变」。故本函数**没有任何动画入参**（没有 `f`、
 * 没有 `expanded` / `bandReserved`）：绘制布局只有**一个**取值，
 * 收起态与展开态**逐像素相同**，于是
 *
 * - 内容区恒落在岛体矩形内（收起态不再被裁掉）；
 * - 字形命中域在屏幕上**不动** ⇒ 判据单调 ⇒ 反馈环不存在。
 *
 * ⚠️ **不是**给命中判据加延迟 / 迟滞 / 阈值 —— 那是**掩盖**症状：判据本身仍在平移，
 *    只是被阻尼压到看不见，换个动效时长就会复发。
 *
 * ## 与 [bandGeometry] 的关系（两条不变量，探针 `IslandExpandHitProbe` 逐帧断言）
 *
 * 1. `islandDrawLayout(...).lyricsTop == bandGeometry(...).lyricsTop`（对**每个** `f`）；
 * 2. 同上，`lyricsHeight`。两者共用**同一个**带高量化值（`roundToInt`），
 *    故 `f = 0` 时内容区与岛体矩形**逐像素重合**。
 *
 * @param controls 控件层实例（无状态，只是绘制/布局的入口；由调用方持有，避免每帧新建）。
 * @param widthPx 面板宽度（窗口恒为展开态宽，与悬停态无关）。
 * @param baseHeightPx 控件条**收起时**的词岛高度（`IslandWindow.baseHeightPx`）。
 * @param stripHeightPx 控件带需要的高度（`PlaybackControls.stripHeight`，`Float`）。
 * @param cfg 控件配置快照（决定 `position = TOP/BOTTOM` 与带高）。
 */
fun islandDrawLayout(
    controls: PlaybackControls,
    widthPx: Int,
    baseHeightPx: Int,
    stripHeightPx: Float,
    cfg: ControlsConfig,
): PlaybackControls.Layout {
    // ⚠️ 量化**一次**（`roundToInt`，不是截断）—— 与 `bandGeometry` / `windowTargetBounds`
    //    共用同一个值；三者若各量化一次，"画在哪"与"岛体有多大"会差最多 1px。
    val stripQ = if (stripHeightPx.isFinite()) stripHeightPx.roundToInt().coerceAtLeast(0) else 0
    return controls.layoutOf(
        width = widthPx.coerceAtLeast(0).toFloat(),
        baseHeight = baseHeightPx.coerceAtLeast(0).toFloat(),
        stripPx = stripQ.toFloat(),
        cfg = cfg,
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 词岛形状：构造 + 「圆角感知」的几何（2026-09-22，BUG-127）
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 用户原话
//
// > 「**贴边频谱左右端超出词岛**（大圆角下）」
//
// ## 根因（**与直觉不同**）
//
// `clip` 是有的（`IslandWindow` 里 `g2.clip = shape`，频谱在 `try` 内，
// 物理上画不到形状外）。真因是**横向内缩是常量 `HORIZONTAL_INSET = 14f`、
// 完全不感知圆角半径**：大圆角下带子两端落在弧区里，被 `setClip` 的**硬掩膜**
// 沿弧线切掉（`setClip` 忽略抗锯齿，BUG-121）⇒ 观感就是"戳到外面去了"。
//
// 故修法是**圆角感知的横向内缩**（[spectrumEdgeInset]）：
//
// ```
// edgeInset = max(HORIZONTAL_INSET, 圆角在"带子所在纵向位置"造成的水平收缩量)
// left  = areaLeft + edgeInset
// right = areaLeft + areaWidth - edgeInset
// ```
//
// ## 判据（这条决定 `edgeInset` 取多少，**不是观感取舍**）
//
// **带子矩形的四个角必须落在形状的闭包内** —— 于是 `edgeInset` 被"让四角都在形状内"
// **唯一确定**（取满足条件的最小值）。形状与它两条横向边的圆角半径**由同一个文件给出**
// （[islandShape] / [islandEdgeRadiusPx]），故"生产画出来的形状"与"探针断言的形状"
// 不可能漂移 —— 这是把三者放进同一个文件（而不是各自抄一份）的**唯一**理由。
//
// ⚠️ **不要**再依赖 clip 去切：`setClip` 忽略 AA，沿弧切出来的边是**锯齿**的
//    （BUG-121 已记录），用户看到的正是那条锯齿。

/**
 * `NOTCH`（刘海）形态**下方两角**的半径占短边的比例。
 *
 * 取 0.12：与改动前 `IslandWindow.bottomNotchRadius` **逐字相同**（本次只是搬家）。
 */
const val NOTCH_BOTTOM_RADIUS_RATIO = 0.12f

/**
 * 词岛形状的**圆角基准短边**（px）。
 *
 * ## 为什么不能再用"当前岛高"（BUG-127 的第二半，与「闪动」同源）
 *
 * 改动前 `buildShape` 取 `shortSide = min(w, h)`，而 `h` 传的是**动画中的岛高**
 * （从 `base` 连续长到 `base + strip`）⇒ 控件条展开/收起的整段动画里，
 * **整条岛体轮廓的圆角在连续变形**。这是"看起来在抖"的**第二个来源**
 * （第一个是窗口矩形跳变，见 [windowTargetBounds]）。
 *
 * ## 为什么锚在**收起态岛高**而不是宽度
 *
 * `shape.corner_radius` 的语义是「相对**短边**的百分比」（注册表注释逐字如此），
 * 而词岛是长条形（宽 ≫ 高）⇒ 短边**就是高度**。改用宽度会让 60% 变成 384px，
 * 把"圆角矩形"直接变成"胶囊" —— 那是**观感巨变**，不是修 bug。
 *
 * 且 `BandGeometry` 那边已经以 `baseHeightPx` 为"岛体的静止尺寸"
 * （见 `bandGeometry` 的 `baseHeightPx` 入参）—— **两处必须同一个口径**，
 * 否则"形状按动画高度画、圆角按收起高度算"又会分成两套。
 */
fun islandRadiusBasisPx(widthPx: Float, collapsedHeightPx: Float): Float {
    if (!widthPx.isFinite() || !collapsedHeightPx.isFinite()) return 0f
    return minOf(widthPx, collapsedHeightPx).coerceAtLeast(0f)
}

/**
 * `shape.corner_radius`（短边百分比 `0..100`）→ 像素半径。
 *
 * 上限是**短边的一半**：`RoundRectangle2D` 的 arc 超过半边长时会画出畸形形状
 * （Java2D 不报错，只是结果不可预期）。100% 时恰好等于半短边，即**胶囊**。
 */
fun islandCornerRadiusPx(cornerRadiusPercent: Int, radiusBasisPx: Float): Float {
    if (!radiusBasisPx.isFinite() || radiusBasisPx <= 0f) return 0f
    val maxRadius = radiusBasisPx / 2f
    return (radiusBasisPx * cornerRadiusPercent / 100f).coerceIn(0f, maxRadius)
}

/**
 * 形状在**某一条横向边**上的有效圆角半径（px）—— [spectrumEdgeInset] 的输入。
 *
 * 与 [islandShape] **必须一致**：两者在同一个文件、相邻定义，且后者对本函数
 * 用到的每一档都调它（`PILL` / `SQUARE` 的常量值也在这里给出）。
 * 一旦分成两处，表现就是"频谱按 62px 内缩、而形状的圆角其实只有 12px"
 * （内缩过头，频谱无谓地变窄）或反之（**又戳出去**）。
 *
 * @param atTopEdge `true` = 形状的**上**边（`NOTCH` 的两条边半径不同）
 * @param coverShape `cover.shape` 的当前值（`ROUNDED` / `CIRCLE` / `SQUARE`）。
 *   ★ **当前不参与运算**，只作**签名占位 + 文档锚点**：`recon/67` 原文说封面态
 *   "轮廓跟 `cover.shape`"，但逐档实现会让 `CIRCLE` 档退化成 `PILL`（与既有
 *   形态**碰撞**）⇒ 用户 2026-09-30 拍板改为「**跟随 `PILL` / `ROUNDED_RECT`
 *   的判定范围**」（见 `COVER_ONLY` 分支的 KDoc）。保留参数是为了将来真按
 *   `cover.shape` 分档时**不必再改签名**。默认值 = `cover.shape` 的注册表默认值。
 */
fun islandEdgeRadiusPx(
    shapeType: String,
    cornerRadiusPercent: Int,
    radiusBasisPx: Float,
    atTopEdge: Boolean,
    coverShape: String = COVER_SHAPE_ROUNDED,
): Float {
    if (!radiusBasisPx.isFinite() || radiusBasisPx <= 0f) return 0f
    val maxRadius = radiusBasisPx / 2f
    return when (shapeType) {
        // 胶囊：忽略用户圆角，直接取最大半径（两端半圆）。
        "PILL" -> maxRadius
        // 方形：直角。**不自作主张加圆角** —— 否则"方形 vs 圆角矩形"看不出区别。
        "SQUARE" -> 0f
        // 刘海：上方两个角大圆角、下方两个角小圆角（形似 iPhone 的刘海）。
        "NOTCH" -> if (atTopEdge) {
            maxRadius
        } else {
            (radiusBasisPx * NOTCH_BOTTOM_RADIUS_RATIO).coerceIn(0f, maxRadius)
        }
        // ── 2026-09-30 · 封面态（`recon/67`）────────────────────────────────
        //
        // ★★ **本态与 `ROUNDED_RECT` 同款：四角都取用户的圆角** ——
        //   用户 2026-09-30 原话「**跟随胶囊和圆角矩形外观的词岛判定范围设定**」
        //   ⇒ **不单开分支**，直接落下面的 `else`。
        //
        // ⚠️⚠️ **2026-10-01 更正（一次"被探针逼歪"的设计）**：本态原先单开了一个
        //   **"上圆下直"**分支（上边 = 用户圆角、**下边 = 直角**）——
        //   ★ 那**不是用户要的**，而是我为了让 `ColorPickerProbe` §⑯.7 的
        //   「**形态可区分性**」断言变绿而加的：四角同半径 ⇒ `COVER_ONLY` 与
        //   `ROUNDED_RECT` 的轮廓**逐像素相同** ⇒ 那条断言红。
        //   ⇒ 用户 2026-10-01 报：「**岛体外形也非常丑，没有其他形态的圆润协调对称感**」。
        //
        //   ★★ **教训：探针的"可区分性"是【我的】护栏，不是【用户的】契约。**
        //     本态的**身份在行为**（只画封面、无歌词、左锚生长），**不在轮廓**。
        //     ⇒ 已放宽那条断言（见 `ColorPickerProbe` §⑯.7 的
        //     `SHAPE_PAIRS_INDISTINGUISHABLE_BY_DESIGN`）。
        //
        // ## 三档 `coverShape`（`cover.shape`）为什么不参与运算
        //
        //   `recon/67` 原文是"轮廓跟 `cover.shape`（圆/方）"，但**逐档字面实现不可行**：
        //   `cover.shape = CIRCLE` ⇒ 圆形 = `PILL`（与既有形态碰撞）。
        //   故本参数**只作签名占位 + 文档锚点**，三档同半径。
        // 默认 / ROUNDED_RECT / 未知值：按用户圆角走（未知值也走这里是有意的：
        // 将来加了形态而这里没更新时，回退到"圆角矩形"总比不画强）。
        else -> islandCornerRadiusPx(cornerRadiusPercent, radiusBasisPx)
    }
}

/**
 * 依据「形态 + 圆角百分比」构造词岛形状 —— **生产与探针共用的唯一定义点**。
 *
 * ## 为什么它必须公开（而不是留在 `IslandPanel` 的私有方法里）
 *
 * BUG-127 的验收判据是「**带子矩形的四个角必须落在 `shape.contains(...)` 之内**」——
 * 探针必须拿到**生产真正画的那个形状**。若让探针自己抄一份构造代码，
 * 两份迟早漂移，而漂移的表现是"探针绿了、真机仍然戳出去"（本项目吃过这类亏：
 * 探针里复刻了生产的一行除法，于是它**测着一个不存在的世界**）。
 *
 * ## 与改动前的差异（只有一处）
 *
 * 短边基准从"当前岛高"改成 [radiusBasisPx]（= 收起态岛高）。
 * `radiusBasisPx` 非正/非有限时**退回旧口径** `min(w, h)` —— 那是"没有可信基准"的
 * 保守回退（渲染路径不抛异常）。
 *
 * @param width/height 形状自身的宽高（形状恒从 `(0, 0)` 起算，见调用方）
 */
fun islandShape(
    shapeType: String,
    cornerRadiusPercent: Int,
    width: Float,
    height: Float,
    radiusBasisPx: Float,
    cornerStyle: String,
    cornerN: Int,
): Shape {
    val w = if (width.isFinite()) width else 0f
    val h = if (height.isFinite()) height else 0f
    val basis = if (radiusBasisPx.isFinite() && radiusBasisPx > 0f) {
        radiusBasisPx
    } else {
        minOf(w, h)
    }
    // ⚠️ 与改动前**逐像素等价**（四种形态逐一核对过）：本函数现在只是"取两条边的半径"
    //    再交给 [islandShapeOf] 构造 —— 构造代码从两处收敛成一处，故"形状怎么造"
    //    只有**一个**定义点（`shape.type` 的四档与"按进度插值"共用它）。
    return islandShapeOf(
        width = w,
        height = h,
        topRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, basis, true),
        bottomRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, basis, false),
        // ★ 形态门在这一层压 —— 它是**唯一**同时知道 `shapeType` 与设置值的地方。
        cornerStyle = effectiveCornerStyle(shapeType, cornerStyle),
        cornerN = cornerN,
    )
}

/**
 * 按**上下两条边各自的圆角半径**构造词岛形状 —— 形状构造的**唯一定义点**。
 *
 * ## 为什么需要"分别给两条边"（而不是只给一个 `corner_radius` 百分比）
 *
 * 两种调用方需要它：
 *   · `shape.type = NOTCH`（刘海）本来就是"上大下小"的**不对称**形态；
 *   · **展开变形**（2026-09-22）—— 圆角随展开进度 `f` **连续插值**，
 *     而插值的两个端点由形态决定（见 [islandOutlineAt]）。
 *     插值出的是**两个数**，不是"某个百分比"，故构造侧必须能直接收两个半径。
 *
 * ## 与旧实现的等价性
 *
 * `topRadius == bottomRadius` 时走 `RoundRectangle2D`、两者都为 0 时走 `Rectangle2D`、
 * 不等时走 `NOTCH` 那套差集构造 —— 与改动前 `islandShape` 的四条分支**逐条对应**，
 * 故 `shape.type` 的四档输出**逐像素不变**。
 *
 * ⚠️ 两个半径都先夹到 `min(w, h) / 2`：`RoundRectangle2D` 的弧长超过半边长时
 *    Java2D **不报错**，只画出不可预期的形状（同 [islandCornerRadiusPx] 的理由）。
 *
 * @param width/height 形状自身的宽高（形状恒从 `(0, 0)` 起算，见调用方）
 * @param topRadiusPx/bottomRadiusPx 上边 / 下边的圆角半径（px）。非有限值按 `0` 处理
 */
fun islandShapeOf(
    width: Float,
    height: Float,
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    cornerStyle: String,
    cornerN: Int,
): Shape {
    val w = if (width.isFinite()) width else 0f
    val h = if (height.isFinite()) height else 0f
    val maxRadius = (minOf(w, h) / 2f).coerceAtLeast(0f)
    val topRadius = if (topRadiusPx.isFinite()) topRadiusPx.coerceIn(0f, maxRadius) else 0f
    val bottomRadius = if (bottomRadiusPx.isFinite()) bottomRadiusPx.coerceIn(0f, maxRadius) else 0f

    // ★ 2026-09-25 第 2 期波次 1：**squircle 分支**。
    //   放在最前，但它自己会处理 `半径 <= 0 ⇒ Rectangle2D`（见 [squirclePath]），
    //   故与下面"直角短路"那条**不冲突、也不重复**。
    //   ⚠️ `PILL` / `SQUARE` 的形态门**不在这里** —— 由调用方的 [effectiveCornerStyle]
    //     在更原实现压掉（本函数只知道半径，不知道形态）。
    if (cornerStyle == "SQUIRCLE") {
        return squirclePath(w, h, topRadius, bottomRadius, cornerN.toFloat())
    }
    // ★ 2026-09-25 第 2 期波次 1：**squircle 分支**。
    //   放在最前，但它自己会处理 `半径 <= 0 ⇒ Rectangle2D`（见 [squirclePath]），
    //   故与下面"直角短路"那条**不冲突、也不重复**。
    //   ⚠️ `PILL` / `SQUARE` 的形态门**不在这里** —— 由调用方的 [effectiveCornerStyle]
    //     在更原实现压掉（本函数只知道半径，不知道形态）。
    if (cornerStyle == "SQUIRCLE") {
        return squirclePath(w, h, topRadius, bottomRadius, cornerN.toFloat())
    }
    // 直角（`SQUARE` / 插值到 0 的端点）：**不自作主张加圆角**。
    if (topRadius <= 0f && bottomRadius <= 0f) return Rectangle2D.Float(0f, 0f, w, h)
    // 四角同半径：最常见的一条（`PILL` / `ROUNDED_RECT` / 展开态圆角矩形）。
    if (topRadius == bottomRadius) {
        return RoundRectangle2D.Float(0f, 0f, w, h, topRadius * 2f, topRadius * 2f)
    }

    // 顶部大圆角、底部小圆角（`NOTCH`）—— 见 [islandShape] 的 KDoc。
    // 用「大圆角矩形 减去 下方两角的补角」的差集构造 —— 这是唯一能用一条路径
    // 画出「四角不同半径」的简洁写法（`RoundRectangle2D` 只支持"四角同半径"）。
    val outer = Area(RoundRectangle2D.Float(0f, 0f, w, h, topRadius * 2f, topRadius * 2f))
    if (bottomRadius < topRadius) {
        val cornerBox = topRadius * 2f
        // 左下角补丁：长在左边缘上、位于底部的一个方块，
        // 它相对"大圆角"来说是方的，相对"小圆角"来说是圆的。
        val leftBottom = Area(
            Rectangle2D.Float(0f, h - cornerBox, cornerBox, cornerBox),
        ).apply {
            subtract(
                Area(
                    RoundRectangle2D.Float(
                        0f, h - cornerBox, cornerBox, cornerBox,
                        topRadius * 2f, topRadius * 2f,
                    ),
                ),
            )
            add(
                Area(
                    RoundRectangle2D.Float(
                        0f, h - cornerBox, cornerBox, cornerBox,
                        bottomRadius * 2f, bottomRadius * 2f,
                    ),
                ),
            )
        }
        val rightBottom = Area(
            Rectangle2D.Float(w - cornerBox, h - cornerBox, cornerBox, cornerBox),
        ).apply {
            subtract(
                Area(
                    RoundRectangle2D.Float(
                        w - cornerBox, h - cornerBox, cornerBox, cornerBox,
                        topRadius * 2f, topRadius * 2f,
                    ),
                ),
            )
            add(
                Area(
                    RoundRectangle2D.Float(
                        w - cornerBox, h - cornerBox, cornerBox, cornerBox,
                        bottomRadius * 2f, bottomRadius * 2f,
                    ),
                ),
            )
        }
        // 先"挖掉"外层的方角区域，再"补上"小圆角区域，得到下小上大的形状。
        outer.subtract(Area(Rectangle2D.Float(0f, h - cornerBox, cornerBox, cornerBox)))
        outer.subtract(Area(Rectangle2D.Float(w - cornerBox, h - cornerBox, cornerBox, cornerBox)))
        outer.add(leftBottom)
        outer.add(rightBottom)
    }
    return outer
}

// ══════════════════════════════════════════════════════════════════════════
// 2026-09-25 · 第 2 期材质 · 波次 1：**squircle（连续曲率圆角）**
//
// 施工单 C（`spw-materials-survey/F-材质外观落地施工规格-2026-09-22.md` §5）。
// 算法**逐行移植**自已有实测装置 `_matbench-squircle/SquircleLab.java:115-146`
// （本机实测 **6.2 µs/次**，属第一梯队）。
//
// ## 为什么这三个都是**公开纯函数**
//
// 与 [islandShape] / [islandShapeOf] 同一个理由（见前者 KDoc）：**探针必须能断言
// "生产真正用的那份几何"**。让探针抄一份镜像，两份迟早漂移 —— 而漂移的表现是
// "探针绿了、真机仍然画错"。
// ══════════════════════════════════════════════════════════════════════════

/** squircle 的默认曲率指数（经典值）。设置项 `shape.corner_n` 缺省时用它。 */
const val SQUIRCLE_N_DEFAULT: Float = 4f

/** 每个角的分段数 —— 固定 `24`，与 `SquircleLab` 的实测口径一致。 */
const val SQUIRCLE_SEGMENTS: Int = 24

/**
 * 设置值 → 角风格。**未知值回退 `ARC`**（= 现状）。
 *
 * 与项目 `text(key, 默认)` 的既有取舍一致：设置系统坏掉时画面与改动前
 * **逐像素一致**，而不是"画不出来"。
 */
fun cornerStyleOf(wire: String): String = if (wire == "SQUIRCLE") "SQUIRCLE" else "ARC"

/**
 * **实际生效**的角风格 —— 在 [cornerStyleOf] 之上再压一道**形态门**。
 *
 * ## 为什么 `PILL` / `SQUARE` 必须恒为 `ARC`
 *
 * 这两种形态**没有"用户圆角"这一维**：
 *   · `SQUARE` 的半径恒为 `0` ⇒ 超椭圆退化成矩形，等价性天然成立；
 *   · `PILL` 的半径**已满载**（`min(w,h)/2`）⇒ 若还叠加超椭圆，画出来的是
 *     "超椭圆胶囊"而不是**正圆端**的胶囊 ⇒ 与改动前**不等价**（观感会变）。
 *
 * ⇒ 在**入口**压掉，而不是指望下游各自判断。
 * 判据来自 `F §5.4`：`PILL` / `SQUARE` 下两种 `corner_style` 必须**几何等价**。
 */
fun effectiveCornerStyle(shapeType: String, wire: String): String =
    if (shapeType == "PILL" || shapeType == "SQUARE") "ARC" else cornerStyleOf(wire)

// ★★★ 2026-10-13（**BUG-187**）：**`dissolveRingShape` 已删除** ——
//   ★ 它原来给「溶边的描边环」就位（`bounds.x == insetPx`），是 BUG-184 的修复点。
//   ★★ 但 U17 实测坐实：**描边环**在 `squirclePath` 的折线上会串珠 ⇒ 两端 **152** 个麻点 ✗
//   ⇒ ★ 溶边改为**距离场掩版**（[dev.cdl.window.DissolveMask]，栅格化 + 倒角，**完全不描边**）
//     ⇒ 麻点 **0** ✓ —— ★ 本函数因此没有消费者 ⇒ 删除（★ A3：删要留痕）✓
//   ★ 它守的「锚点口径」（`inset` 从岛体边缘起算）由**距离场**天然满足 ✓


/**
 * 超椭圆（squircle）圆角路径 —— 四角**各自**半径（上两个 / 下两个）。
 *
 * ## 公式与关键点
 *
 * `|x/r|^n + |y/r|^n = 1`，参数化 `(r·cos^e(a), r·sin^e(a))`，`e = 2/n`。
 * ★ **每段曲线从各自的圆心起算** ⇒ 曲线**留在角盒内**。
 *   （"从外接盒起算"会让曲线**戳出盒外** —— 本项目已用 `1×1 → 1×1 @45°`
 *    一个反例推翻过同类推理：「外接盒盖住 ≠ 矩形盖住」。）
 *
 * 与 [java.awt.geom.RoundRectangle2D] 的关系：`n = 2` 时趋近圆弧角；
 * `n = 4` 是经典 squircle（角区面积 `r²·Γ(1.25)²/Γ(1.5)`，比圆弧角 **+4.9%**，
 * 即"更方的角"）。
 *
 * @param width/height 形状自身的宽高（形状恒从 `(0, 0)` 起算）
 * @param topRadiusPx/bottomRadiusPx 上边 / 下边的圆角半径（px）。非有限值按 `0` 处理，
 *   并**各自夹到 `min(w,h)/2`**（与 [islandShapeOf] 同一条理由：超长半径 Java2D 不报错，
 *   只画出不可预期的形状）。
 * @param n 曲率指数（设置项 `shape.corner_n`）。非有限 / 非正 ⇒ 回退 [SQUIRCLE_N_DEFAULT]。
 */
fun squirclePath(
    width: Float,
    height: Float,
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    n: Float,
): Shape {
    val w = if (width.isFinite()) width else 0f
    val h = if (height.isFinite()) height else 0f
    val maxRadius = (minOf(w, h) / 2f).coerceAtLeast(0f)
    val tr = if (topRadiusPx.isFinite()) topRadiusPx.coerceIn(0f, maxRadius) else 0f
    val br = if (bottomRadiusPx.isFinite()) bottomRadiusPx.coerceIn(0f, maxRadius) else 0f
    val exponent = if (n.isFinite() && n > 0f) n else SQUIRCLE_N_DEFAULT
    // 直角（`SQUARE` / 插值到 0 的端点）：**不自作主张加圆角**（与 [islandShapeOf] 一致）。
    if (tr <= 0f && br <= 0f) return Rectangle2D.Float(0f, 0f, w, h)

    val e = 2.0 / exponent.toDouble()
    val seg = SQUIRCLE_SEGMENTS
    val p = Path2D.Double()
    p.moveTo(tr.toDouble(), 0.0)
    p.lineTo((w - tr).toDouble(), 0.0)
    appendSquircleCorner(p, (w - tr).toDouble(), tr.toDouble(), tr.toDouble(), e, seg, 0.0, -1.0, 1.0, 0.0)
    p.lineTo(w.toDouble(), (h - br).toDouble())
    appendSquircleCorner(p, (w - br).toDouble(), (h - br).toDouble(), br.toDouble(), e, seg, 1.0, 0.0, 0.0, 1.0)
    p.lineTo(br.toDouble(), h.toDouble())
    appendSquircleCorner(p, br.toDouble(), (h - br).toDouble(), br.toDouble(), e, seg, 0.0, 1.0, -1.0, 0.0)
    p.lineTo(0.0, tr.toDouble())
    appendSquircleCorner(p, tr.toDouble(), tr.toDouble(), tr.toDouble(), e, seg, -1.0, 0.0, 0.0, -1.0)
    p.closePath()
    return p
}

/**
 * 追加**一个角**的超椭圆弧（`seg` 段折线逼近）。
 *
 * @param ccx/ccy 该角**自己的圆心**
 * @param ax/ay/bx/by 该角的两条基向量（决定从哪条边起算、往哪条边去）
 */
private fun appendSquircleCorner(
    p: Path2D.Double,
    ccx: Double,
    ccy: Double,
    r: Double,
    e: Double,
    seg: Int,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
) {
    // 半径 0 ⇒ 该角是**直角**，直接落到角点（不产生退化弧）。
    if (r <= 0.0) {
        p.lineTo(ccx, ccy)
        return
    }
    for (i in 1..seg) {
        val a = (Math.PI / 2.0) * i / seg
        val ca = Math.pow(Math.cos(a), e)
        val sa = Math.pow(Math.sin(a), e)
        p.lineTo(ccx + r * (ax * ca + bx * sa), ccy + r * (ay * ca + by * sa))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 「词岛长大」：形状随展开进度连续变形（2026-09-22）
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 用户原话
//
// > 「字形命中后展开控件改为词岛"**长大**"，**胶囊和圆角矩形长成比原来词岛宽度略大的
// >   圆角矩形**，**R 角取适当值，不可调**。」
//
// ## 为什么要"另立一个纯函数"而不是把插值写在绘制里
//
// 「连续变形」是本轮**唯一的可证伪性质**（逐帧插值、不跳），而 `IslandWindow` 是
// `internal`、绘制路径又只能真机跑 —— 探针**够不到**。抽到公开顶层后，探针可以
// 穷举 `f ∈ [0, 1]` 断言「两端精确 / 中间连续 / 单调」，并构造"跳变"版本作**反例**
// （本项目纪律：「不可证伪的检查 = 没有检查」）。

/**
 * 展开态形状的 **R 角**（px）—— 用户原话「R 角取适当值，**不可调**」。
 *
 * ## 取值依据（不是手感值）
 *
 * 展开态岛高 = `baseHeightPx + 控件带高`（默认字号 / 三行 / 有进度条下 ≈ **201 px**）。
 * 取 **16** 的判据有三条，都可核对：
 *
 * 1. **与"圆角矩形"的观感一致而不像胶囊**：胶囊的 R = 短边/2 ≈ 100，
 *    16 远小于它 ⇒ 展开态一眼能看出是"圆角矩形"（用户要的形态）而不是胶囊。
 * 2. **不小于默认字号（22px）的一个字高**：R < 字号时圆角在视觉上会"吃掉"左端
 *    第一个字的边缘（`HORIZONTAL_INSET = 14` 正是为这个留的余量）。
 * 3. **≤ 最矮可信岛高的 1/2**：单行 / 最小字号（`main_lyric.font.size` 下限）
 *    下收起态岛高约 `20 × 1.36 + 20 ≈ 47`，展开态 ≈ 47 + 76 = 123，
 *    `123 / 2 = 61 > 16` ⇒ 任何档位下都不会退化成"弧长超过半边长"的畸形形状
 *    （构造侧另有夹取兜底，见 [islandShapeOf]）。
 *
 * ⚠️ **不新增设置项** —— 用户明确说"不可调"。想改就改这个常量（它只有一个消费点）。
 */
const val EXPAND_CORNER_RADIUS_PX = 16f

/** 词岛形状在某一帧的**上下两条边的圆角半径**（px）。见 [islandOutlineAt]。 */
data class IslandOutline(
    val topRadiusPx: Float,
    val bottomRadiusPx: Float,
)

/**
 * 展开态下**某一档形态**应收敛到的两条边圆角半径。
 *
 * ## 判定（`shape.type` 四档逐档给理由）
 *
 * | 档 | 展开态半径 | 理由 |
 * |---|---|---|
 * | `PILL` | [EXPAND_CORNER_RADIUS_PX] | 用户原话点名的"**胶囊**长成圆角矩形" |
 * | `ROUNDED_RECT`（及未知值） | [EXPAND_CORNER_RADIUS_PX] | 用户原话点名的"**圆角矩形**长成圆角矩形" |
 * | `SQUARE` | `0`（保持直角） | 用户**没点它**；而 `islandEdgeRadiusPx` 的 KDoc 已定死"方形 = 直角，**不自作主张加圆角**，否则方形 vs 圆角矩形看不出区别"。展开时给它圆角 = 让"方形"这一档在展开态**失去身份** |
 * | `NOTCH` | 与收起态**相同** | 刘海是**刻意的不对称**形态（上大下小）。把它抹平成一个圆角矩形 = 让"刘海"这一档在展开态失效 —— 与上一条同一种错 |
 *
 * ## ⚠️ 但**横向长大对四档一视同仁**
 *
 * 上面只决定**圆角**。宽度那一侧（[dev.cdl.window.expandWidthGrowPx]）
 * 对四档**全部生效** —— 否则"换成方形档，长大就没了"，
 * 而用户要的是"命中后词岛长大"，与形态档无关。
 * ⇒ 判定口径：**长大是共有的，形态是各自的**。
 *
 * @param shapeType `shape.type` 的 wire 值
 * @param collapsedTopRadiusPx 收起态上边半径（由 `islandEdgeRadiusPx` 给出）
 * @param collapsedBottomRadiusPx 收起态下边半径
 */
fun expandedOutlineRadii(
    shapeType: String,
    collapsedTopRadiusPx: Float,
    collapsedBottomRadiusPx: Float,
): IslandOutline = when (shapeType) {
    "SQUARE" -> IslandOutline(0f, 0f)
    "NOTCH" -> IslandOutline(collapsedTopRadiusPx, collapsedBottomRadiusPx)
    // ── 2026-09-30 · 封面态（`recon/67` §5 的 ⭐③ —— **也带 `else`**）──────────
    // ★★ 与 [islandEdgeRadiusPx] 的 `COVER_ONLY` 处理**必须成对**：
    //   只改一处 ⇒ 收起态对了、**展开态落 `else`** 用 `EXPAND_CORNER_RADIUS_PX`
    //   ⇒ "展开后形状突然变了样"。两处一起改才自洽。
    //
    // ★ 本态在展开态**保持收起态的那对半径**（`NOTCH` 同款处理）——
    //   理由：本态收起态用的是**用户的圆角**（见 [islandEdgeRadiusPx]），
    //   若展开时收敛到 `EXPAND_CORNER_RADIUS_PX`，用户设的圆角会在展开瞬间被改掉
    //   ⇒ 与"NOTCH 展开时不许被抹平"同一条纪律。
    //   ⚠️ 2026-10-01：本态收起态已改为**四角同半径**，故这里传进来的两个值相等。
    "COVER_ONLY" -> IslandOutline(collapsedTopRadiusPx, collapsedBottomRadiusPx)
    else -> IslandOutline(EXPAND_CORNER_RADIUS_PX, EXPAND_CORNER_RADIUS_PX)
}

/**
 * 形状在展开进度 `f` 处的两条边圆角半径 —— **逐帧插值**（不是跳变）。
 *
 * `f = 0` → 收起态半径（与 `shape.type` 四档的既有观感**逐像素相同**）；
 * `f = 1` → [expandedOutlineRadii] 给出的展开态半径。
 *
 * ⚠️ 线性插值即可：两个端点都是**半径**（同一量纲、同一坐标系），
 * 中间值天然是合法半径（≤ 两端较大者 ≤ 短边/2）。
 * 用缓动反而会让"形状"与"控件条 alpha"两条进度**不同步**（后者走的是
 * `bandFractionNow` 的原始值，见 `IslandWindow.drawControls`）。
 *
 * @param radiusBasisPx 圆角基准短边（`islandRadiusBasisPx`）—— 收起态半径由它推出
 * @param fraction 展开进度（越界被夹住；非有限按 `0` 处理）
 */
fun islandOutlineAt(
    shapeType: String,
    cornerRadiusPercent: Int,
    radiusBasisPx: Float,
    fraction: Float,
): IslandOutline {
    val collapsedTop = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, true)
    val collapsedBottom = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, false)
    val expanded = expandedOutlineRadii(shapeType, collapsedTop, collapsedBottom)
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    return IslandOutline(
        topRadiusPx = collapsedTop + (expanded.topRadiusPx - collapsedTop) * f,
        bottomRadiusPx = collapsedBottom + (expanded.bottomRadiusPx - collapsedBottom) * f,
    )
}

/**
 * 贴边频谱**一侧**的横向内缩量（px）—— 使带子该侧的两条竖边完全落在形状的闭包内。
 *
 * ## 推导（不是手感值）
 *
 * 圆角矩形的角是一个半径 `r` 的**四分之一圆**，弧心在 `(r, r)`。在**距边 `d`**
 * 的高度上，形状的左边界横坐标是 `x(d) = r − √(r² − (r−d)²)`，故
 *
 * ```
 * inset(d) = max(baseInset, r − √(2rd − d²))        （d < r；d ≥ r 时为 baseInset）
 * ```
 *
 * 三个端点都对：
 *   · `d = 0`（带子**贴边**，最常见的档：`pct = 100`）→ `inset = r` —— 即"整段弧的
 *     水平投影"，这正是"带子四角都落在闭包内"的最小值；
 *   · `d = r`（带子正好在弧的终点）→ `inset = baseInset`（弧区之外，不受影响）；
 *   · `d > r` → `baseInset`。
 *
 * ## 为什么不用 `shape.contains(...)` 二分求（诚实记录一个**不可解**）
 *
 * 判据写的是"四角落在 `shape.contains` 之内"，而 `Shape.contains` 对**边界点**
 * 返回 `false`；带子贴边时四角**必然**落在边界上 ⇒ 该判据**无解**
 * （二分只会一路推到"带子宽度为 0"）。
 * 故生产侧用上面的**闭包解析式**（等价于"允许落在边界上"），探针侧断言
 * "四角沿两条轴各向内收 1px 后 `contains` 为真" —— 见 `SpectrumGeometryProbe` §⑩
 * 与 `IslandPolicyProbe` §⑭ 的说明。
 *
 * @param baseInset 基础内缩（`HORIZONTAL_INSET`）—— 返回值**永不小于**它
 * @param edgeRadiusPx 该侧的圆角半径（[islandEdgeRadiusPx]）
 * @param distanceFromEdgePx 带子该侧的边到**歌词块**同侧边的距离（`≥ 0`）
 */
fun spectrumEdgeInset(
    baseInset: Float,
    edgeRadiusPx: Float,
    distanceFromEdgePx: Float,
): Float {
    val base = if (baseInset.isFinite()) baseInset.coerceAtLeast(0f) else 0f
    if (!edgeRadiusPx.isFinite() || edgeRadiusPx <= 0f) return base
    val r = edgeRadiusPx
    val d = if (distanceFromEdgePx.isFinite()) distanceFromEdgePx.coerceIn(0f, r) else 0f
    // `coerceAtLeast(0)` 兜住浮点误差（`d` 已夹在 [0, r] 内，被开方项理论上非负）。
    val arc = r - sqrt((r * r - (r - d) * (r - d)).coerceAtLeast(0f))
    return maxOf(base, arc)
}

/**
 * 频谱带子的**横向内缩量**（左右各一份）—— 由"**四角必须落在形状内**"唯一确定。
 *
 * 这是 `IslandWindow.drawBackgroundSpectrum` 里那两行的**唯一定义点**：
 *
 * ```
 * left  = areaLeft + inset
 * right = areaLeft + areaWidth - inset
 * ```
 *
 * ## 为什么参照的是**歌词区**而不是 `bg.islandTop / islandBottom`
 *
 * 后者在控件带动画的 180 ms 里会随带子"长出去" ⇒ 内缩量每帧变化 ⇒ 频谱左右抖。
 * 而词岛轮廓的圆角半径**恒等于歌词块那一侧的圆角**：
 *   · `BOTTOM` 档下两者本来就是同一个矩形；
 *   · `TOP` 档下岛体只比歌词块多出**上方**那一段（下方两条边仍然重合）。
 * 故用歌词块作参照既**稳定**又**不低估**（探针在 f=0 与 f=1 两个端点都验过四角在形状内）。
 *
 * @param areaTop 歌词区上边缘（`position = TOP` 时为控件带高）
 * @param areaHeight 歌词区高度
 * @param bandTop/bandBottom 带子矩形的纵向范围（面板坐标）
 * @return 左右各需内缩的像素数（**永不小于** [baseInset]）
 */
fun spectrumBandInset(
    shapeType: String,
    cornerRadiusPercent: Int,
    radiusBasisPx: Float,
    areaTop: Float,
    areaHeight: Float,
    bandTop: Float,
    bandBottom: Float,
    baseInset: Float,
): Float = spectrumBandInsetForRadii(
    topRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, true),
    bottomRadiusPx = islandEdgeRadiusPx(shapeType, cornerRadiusPercent, radiusBasisPx, false),
    areaTop = areaTop,
    areaHeight = areaHeight,
    bandTop = bandTop,
    bandBottom = bandBottom,
    baseInset = baseInset,
)

/**
 * 同 [spectrumBandInset]，但**直接给两条边的圆角半径** —— 供"形状随展开进度变形"时使用。
 *
 * ## 为什么需要它（2026-09-22）
 *
 * [spectrumBandInset] 按 `shape.type` + `corner_radius` 反推半径，而那个反推**只描述
 * 收起态**。本轮起圆角随展开进度连续变化（`PILL` 档：收起 `R = 短边/2` ≈ 62px →
 * 展开 [EXPAND_CORNER_RADIUS_PX] = 16px）。若频谱内缩仍按收起态半径算，展开态会
 * **多内缩约 46px** —— 表现为"悬停时频谱左右各缩一截"。
 *
 * 故绘制侧改为传入**本帧真正用的**两个半径（来自 [islandOutlineAt]）。
 * 这样"内缩量"与"画出来的轮廓"由**同一个**半径推出，两者不可能漂移。
 */
fun spectrumBandInsetForRadii(
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    areaTop: Float,
    areaHeight: Float,
    bandTop: Float,
    bandBottom: Float,
    baseInset: Float,
): Float = maxOf(
    // 上边：带子顶边到歌词区顶边的距离（贴边时为 0 ⇒ 内缩 = 该侧圆角半径）。
    spectrumEdgeInset(
        baseInset = baseInset,
        edgeRadiusPx = topRadiusPx,
        distanceFromEdgePx = bandTop - areaTop,
    ),
    // 下边：带子底边到歌词区底边的距离。
    spectrumEdgeInset(
        baseInset = baseInset,
        edgeRadiusPx = bottomRadiusPx,
        distanceFromEdgePx = (areaTop + areaHeight) - bandBottom,
    ),
)

/**
 * 频谱带子的**横向内缩** —— **唯一出口**（主带 / 插槽两条语义在此分流）。
 *
 * ## ★★★ 2026-10-11（**BUG-166**）：插槽档**不走**圆角内缩
 *
 * 用户报障（逐字）：「三型侧边频谱……在**收起状态下的词岛仍不可见**，**展开时才可见**。」
 *
 * ## 根因（★ 「布局常量不得取自动画量」在**另一个量**上的复发）
 *
 * [spectrumBandInsetForRadii] 的输入 `topRadiusPx`/`bottomRadiusPx` 是**动画量**
 * （收起态半径 → `EXPAND_CORNER_RADIUS_PX = 16` 插值）。而**收起态**半径
 * = `min(岛宽, 收起岛高) × 圆角% / 2` —— 用户 `corner_radius = 100` 时**恰为岛高的一半**
 * （= 胶囊）。插槽的**宽**又恰好 ≈ 岛高 ⇒ ★ **内缩把整条带宽吃光**：
 *
 * | 档 | 插槽宽 | 收起内缩 | 带净宽 | 展开内缩 | 带净宽 |
 * |---|---|---|---|---|---|
 * | `SPECTRUM` | 56 | **32** | **−8** ✗ | 16 | 24 ✓ |
 * | `RADIAL` / `RING` | 64 | **32** | **0** ✗ | 16 | 32 ✓ |
 *
 * ⇒ 撞上调用方的 `if (barWidth <= 1f) return` ⇒ **三型在收起态全部不画** ✓
 *
 * ## 为什么正解是"插槽档恒用基础内缩"
 *
 * 1. **插槽是自足组件**：几何由 `lyricsHeight`（稳态量）定死 ⇒ 内缩也必须是**稳态量**。
 * 2. **圆角内缩的模型不适用**：它是为**主带**（横跨歌词区、贴上/下边）推的
 *    ——「带子四角落在圆角弧内」；插槽是**右端的一小块**，圆角交给**岛体自己的 clip**
 *    （本项目一贯做法："clip 到 shape 最省事且必然正确"）。
 * 3. **极坐标两档根本不用 `left`/`right`**（画布是 `slotRect`）⇒ 内缩只影响那道守卫。
 *
 * ⚠️ **代价（如实记）**：`SPECTRUM` 档展开态带净宽 24 → **28**（+4px）；
 * 收起态由"不画"变成"正常画"；两态**恒为 28**（不再随悬停变化）。
 *
 * @param slotOwn 是否**插槽档**（`SPECTRUM` 侧边条 / `RADIAL` / `RING`）——
 *   判据见 `IslandWindow.drawBackgroundSpectrum` 的 `slotOwn`（一处算、多处共用）。
 */
fun spectrumBandInsetFor(
    slotOwn: Boolean,
    topRadiusPx: Float,
    bottomRadiusPx: Float,
    areaTop: Float,
    areaHeight: Float,
    bandTop: Float,
    bandBottom: Float,
    baseInset: Float,
): Float = if (slotOwn) {
    if (baseInset.isFinite()) baseInset.coerceAtLeast(0f) else 0f
} else {
    spectrumBandInsetForRadii(
        topRadiusPx = topRadiusPx,
        bottomRadiusPx = bottomRadiusPx,
        areaTop = areaTop,
        areaHeight = areaHeight,
        bandTop = bandTop,
        bandBottom = bandBottom,
        baseInset = baseInset,
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 副频谱的纵向锚点（2026-09-22，BUG-126）
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 用户原话
//
// > 「**副频谱跟着控件条走**」
//
// ## 根因
//
// 改动前两个锚点都是**面板绝对坐标**：
//
// ```
// BOTTOM 档：secBands += (islandHeight - secHeight) to false
// TOP    档：secBands += 0f to true
// ```
//
// 而 `position = TOP` 时词岛从 `stripQ`（控件带高）开始 ⇒ TOP 档的副频谱被画在
// `[0, secHeight]` —— **正是控件条那条带**。
//
// ## 为什么 BUG-077 只对了一半
//
// 它把 `islandHeight` 从 `height`（面板高）改成 `baseHeightPx`（收起态岛高）——
// **修了"高"、没修"顶"**。而 `position = BOTTOM` 下 `baseHeightPx` 与歌词区底边
// 恰好重合 ⇒ **它的验收在 BOTTOM 下必然通过、TOP 下漏网**。
//
// ## 现判据
//
// 锚在**歌词区那块词岛**的两条边（`areaTop` / `areaTop + areaHeight`，本就是
// `drawBackgroundSpectrum` 的入参）：
//   · `BOTTOM` → 带子底边贴歌词区底边；`BOTTOM` 档下 `areaTop = 0` ⇒ **与今天逐像素相同**；
//   · `TOP`    → 带子顶边贴歌词区顶边（`position = TOP` 时它是 `stripQ`，**不在控件条上**）。
//
// ⚠️ **不要**改成 `bg.islandTop / bg.islandBottom`：那会让副频谱在动画期间
//    随带子一起"长出去"，而用户要的是**静止的歌词块**的边缘。

/** `spectrum.secondary.mode` 的取值（与注册表 `options` 逐字一致）。 */
const val SPECTRUM_SECONDARY_MODE_OFF = "OFF"

/** 见 [SPECTRUM_SECONDARY_MODE_OFF]。 */
const val SPECTRUM_SECONDARY_MODE_BOTTOM = "BOTTOM"

/** 见 [SPECTRUM_SECONDARY_MODE_OFF]。 */
const val SPECTRUM_SECONDARY_MODE_TOP = "TOP"

/** 见 [SPECTRUM_SECONDARY_MODE_OFF]。 */
const val SPECTRUM_SECONDARY_MODE_BOTH = "BOTH"

/**
 * 副频谱要画的**两条带**各自的「上边缘 + 生长方向」—— 唯一计算点。
 *
 * @param mode `spectrum.secondary.mode`（[SPECTRUM_SECONDARY_MODE_OFF] 起头的四档）
 * @param areaTop 歌词区上边缘（面板坐标）—— `position = TOP` 时为控件带高
 * @param areaHeight 歌词区高度
 * @param bandHeight 副频谱带高
 * @return 每项 = (带子上边缘, 是否**向下**长)。`OFF` → 空表。
 */
fun secondarySpectrumBands(
    mode: String,
    areaTop: Float,
    areaHeight: Float,
    bandHeight: Float,
): List<Pair<Float, Boolean>> {
    val bottom = mode == SPECTRUM_SECONDARY_MODE_BOTTOM || mode == SPECTRUM_SECONDARY_MODE_BOTH
    val top = mode == SPECTRUM_SECONDARY_MODE_TOP || mode == SPECTRUM_SECONDARY_MODE_BOTH
    val out = ArrayList<Pair<Float, Boolean>>(2)
    // BOTTOM：带子**底边**贴歌词区底边（painter 默认向上长）。
    if (bottom) out += ((areaTop + areaHeight) - bandHeight) to false
    // TOP：带子**顶边**贴歌词区顶边（复用主频谱那套 translate + scale(1,-1) 翻转）。
    if (top) out += areaTop to true
    return out
}

// ══════════════════════════════════════════════════════════════════════════════
// 「词岛尺寸与字号解耦」（2026-09-22，用户第 5 条）
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 用户原话
//
// > 「将词岛大小随字体大小变化**剥离**出来，改为**是否跟随**和**手动调整**两档选择。
// >   允许调整词岛**收起状态下的高度**（**用与所应用行数的歌词行总高度的比值**）。」
//
// ## ★ 比值口径（用户已明确选定）
//
// 基准 = **`base + extra`**（"歌词块真实占用"）：
//   · `base = step × (rows − 1) + lineHeight`（纯歌词行）
//   · `extra = translationRowHeight(...)`（译文行，`layout.translation.enabled` 时才计入）
//
// ⚠️ 不是 `base` —— `LineCount.DUAL.rows == 1`（译文行**不算进 rows**），
//    故"所应用行数的歌词行总高度"必须把译文行单独加回来。

/** 词岛上下各留的垂直内边距（px）—— 与 `IslandWindow.VERTICAL_PADDING` 同源。 */
const val ISLAND_VERTICAL_PADDING_PX = 10f

/** 「词岛高度是否跟随字号」的默认值 —— `true` = 旧行为（**开箱零变化**）。 */
const val ISLAND_HEIGHT_FOLLOW_FONT_DEFAULT = true

/**
 * 「收起态高度 / 歌词块高度」的默认比值（百分比）。
 *
 * ## 为什么默认 120 而不是 100
 *
 * 100% 的语义是"词岛高**恰好**等于歌词块（上下无内边距）"—— 那是**合法但紧**的档位。
 * 而"跟随字号"档（默认）的实际高度 = `歌词块 + 20px`，等价比值 =
 * `1 + 20 / 歌词块高`（默认字号 22 / 三行下 ≈ **122%**，单行下 ≈ 167%）。
 *
 * 取 120 是让用户**切到「不跟随」的那一刻观感基本不变**（默认档下 121%），
 * 而不是突然收到一个"文字贴着边"的词岛。**它是起点，不是推荐值**。
 */
const val ISLAND_HEIGHT_RATIO_DEFAULT_PERCENT = 120

/**
 * 收起态词岛高度（px）—— **唯一**的推导点（`IslandWindow.islandHeightFor` 只是壳）。
 *
 * ## 为什么抽到公开文件
 *
 * 本函数是"词岛尺寸"的**唯一**定义点，而它此前是 `IslandWindow` 的
 * `private companion object` 成员 —— 探针（独立源集）**看不到**，
 * 于是"比值档真的按 `base + extra` 算吗"这条断言**写不出来**。
 * 本项目纪律：**首选抽公开纯函数，而不是为了测它而放宽可见性**。
 *
 * ## 两档的公式
 *
 * ```
 * 歌词块 block = lineHeight × (rows − 1) + lineHeight + translationRowHeight   （见上）
 * 跟随字号（true） → block + 2 × VERTICAL_PADDING      ← 与改动前**逐像素相同**
 * 手动（false）    → block × ratio                     ← 用户选定口径
 * ```
 *
 * ## ⚠️ 诚实记录一处张力（不掩盖）
 *
 * 「手动」档下 `block ∝ 字号` ⇒ **高度仍随字号缩放**，只是比例由用户定。
 * 也就是说：本开关改变的是"**内边距占比**由谁决定"（固定 20px vs 用户比值），
 * **不是**"冻结字号的影响"。用户原话里"剥离随字号变化"与"用比值"两半**在数学上
 * 不能同时成立**（比值的分子分母同随字号变化）—— 本实现按 lead 拍板的**比值口径**落地，
 * 并把这条张力写在这里，供下一轮与用户确认。**不假装它被解决了。**
 *
 * @param fontSize 主歌词字号（`main_lyric.font.size`）
 * @param rows 有效行数（`LineCount.rows`；`DUAL` 也是 1，见上）
 * @param lineGapPx 行间距（`layout.line_gap_px`）
 * @param translationRowHeightPx 译文行高度（`0f` = 不显示译文）
 * @param followFont 「词岛高度跟随字号」
 * @param heightRatioPercent 「收起态高度 / 歌词块高度」的百分比（仅在 `followFont = false` 时生效）
 * @return 高度（px，`toInt()` 截断 —— 与改动前**同一个取整方向**）
 */
fun islandCollapsedHeightPx(
    fontSize: Int,
    rows: Int,
    lineGapPx: Float,
    translationRowHeightPx: Float,
    followFont: Boolean = ISLAND_HEIGHT_FOLLOW_FONT_DEFAULT,
    heightRatioPercent: Int = ISLAND_HEIGHT_RATIO_DEFAULT_PERCENT,
): Int {
    val lineHeight = fontSize * LINE_HEIGHT_RATIO
    val gap = if (lineGapPx.isFinite()) lineGapPx.coerceAtLeast(0f) else 0f
    val step = lineHeight + gap
    val base = step * (rows - 1).coerceAtLeast(0) + lineHeight
    val extra = if (translationRowHeightPx.isFinite()) translationRowHeightPx.coerceAtLeast(0f) else 0f
    // ⚠️ `base + extra` 是**用户选定的比值基准**（= 歌词块真实占用）。
    val block = base + extra
    val total = if (followFont) {
        block + ISLAND_VERTICAL_PADDING_PX * 2f
    } else {
        block * (heightRatioPercent / 100f)
    }
    return total.toInt()
}

// ══════════════════════════════════════════════════════════════════════════════
// ★★★ 歌词块纵向几何（2026-10-03）—— **唯一实现点**
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 为什么要有这一组函数（**不是"顺手抽一下"**）
//
// 2026-10-03 用户报：「主歌词顶部到词岛边 / 副歌词底边到词岛边的距离不一致」。
// 复算确认**不是错觉**：**上 = 10 px（= [ISLAND_VERTICAL_PADDING_PX]，设计值），
// 下 = 10 + 副行行高/2 ≈ 19.6 px**（默认 22 号 / 副歌词 100%）。
//
// ## 根因（**两处算式不一致**，不是字体问题）
//
// | | 预留 | 落点 |
// |---|---|---|
// | 副歌词行 | 槽高 `副行行高 + GAP` | 中心 = `主行中心 + 主行行高/2 + GAP` |
//
// ⇒ 副行**墨迹没填满它的槽**（少 `副行行高/2`），而整块按**槽高**居中
//   ⇒ 墨迹整体**偏上**，多出来的那截全落在下方。
//
// ★ **判为缺陷而非取舍**：`ISLAND_VERTICAL_PADDING_PX` 的 KDoc 原文是
//   「词岛**上下各**留的垂直内边距」⇒ **设计意图对称**。
//
// ## 修法（**一行语义**）：副行中心 **`+ 副行行高/2`** ⇒ 墨迹正好填满块 ⇒ 上下都 = 10
//
// ★★ **不需要动岛高**：`block = 主行行高 + 副行槽高` 本来就对，只是**落点没居中**。
//   （原先我按"改块高"估过一版，那会让岛高矮 ≈9.6 px —— **那一版是错的，已更正**。）
//
// ## 为什么抽成公开纯函数
//
// 因为**有两个消费者，且此前各写一份**：
// ① `IslandWindow.drawLyricSlots`（歌词主路径）② 同文件的**无歌词占位**路径
// （`layout.noLyrics.titleInTranslation` 在第二行画曲名）。
// ★ 两份的 `lineHeight` 甚至**定义不同**（② 多加了 `lineGapPx`）⇒ 用户调「行间距」时
//   副行会被推下去 `lineGap/2` 而**岛高不变** ⇒ 极端值下**溢出岛底**（同族第三处缺陷）。
// ⇒ 收敛到本文件，两处都调它 —— 本项目纪律「**不要造第二份实现**」。
//
// ★ 抽成**公开顶层**是因为探针要断言「上下留白相等」（`IslandPanel` 是 private，探针看不到）。

/**
 * 副歌词与主歌词之间的**行间间隙**（px）。
 *
 * ★ 语义（**本次修正后的定义**）：**主行盒底 → 副行盒顶**的距离。
 * ⚠️ **原语义**是"主行下沿 → 副行**中心**"—— 那正是本次缺陷的来源
 * （它让副行盒与主行盒**重叠** `副行行高/2 − GAP`，见本组函数的总说明）。
 *
 * ★★ 2026-10-13（BUG-190）：它**不再是"无条件底距"** —— 由 [translationExtraGapPx]
 *   在 `lineGapPx ∈ [0, 8]` 上**线性淡入**（`lineGapPx ≥ 8` 时全额计入）✓
 */
const val TRANSLATION_GAP_PX = 8f

/**
 * ★★★ 2026-10-13（**BUG-190**）：**两行盒相接时，墨迹之间那道缝**（px）。
 *
 * ## ⚠️ 前情：上一轮的 `effectiveTranslationGapPx` **已删除**（A3 留痕）
 *
 * ★ 它的定义是 `if (lineGapPx > 0f) TRANSLATION_GAP_PX else 0f` ——
 *   ★★ **用户 2026-10-13 报的两个毛病它都有**：
 *   ① `lineGapPx == 0` 时只去掉那 8 px，**行盒余量（本函数的 `trim`）还在**
 *      ⇒ 墨迹之间仍有缝（用户配置实测 ≈ 9.2 px）✗
 *   ② `if (lineGapPx > 0f)` 是**阶跃** ⇒ `0 → 0.0001` 跳一整个 8 px ✗
 * ★ 它原来的 KDoc（"U2 修复"那一整段）已**移入 `migration/147`** 归档 ✓
 * ⇒ 由 [translationExtraGapPx] 取代（**同时含字号与副行比例**，且连续）✓
 *
 * ## 它是什么（纯几何，`LINE_HEIGHT_RATIO` 的必然产物）
 *
 * 行盒高 = `字号 × LINE_HEIGHT_RATIO`（1.36），而**墨迹高 ≈ 字号**
 * ⇒ 每个行盒在墨迹上下各多出 `字号 × (1.36 − 1) / 2` = `字号 × 0.18`。
 *
 * 两行盒**首尾相接**（`lineGapPx = 0` 的旧行为）时，这两份余量**叠在一起** ⇒
 *
 * ```
 * trim = 字号 × 0.18 × (1 + 副行比例)
 * ```
 *
 * ★ 用户配置（22 号 / 副行 133%）⇒ `trim ≈ 9.2 px` ——
 * ★★ 这正是他报的「**行间距为 0 时字迹仍不是相接触的状态**」✓
 *
 * ## 为什么用 `字号` 近似墨迹高（而不是 `FontMetrics`）
 *
 * ★ 行盒高**本来就是**用 `LINE_HEIGHT_RATIO` 从字号算的 ⇒ 用同一个口径反推余量
 * **自洽**（两者必然相加等于行盒高）；★ 用 `FontMetrics` 会引入**字体依赖**
 * ⇒ 同一份配置在不同字体下"0 点"位置漂移，而本函数在**纯函数层**（无 `Font`）✓
 *
 * @return 缝（px，≥ 0）；非法输入退 `0f`
 */
fun lineBoxTrimPx(fontSize: Int, translationScale: Float): Float {
    val f = if (fontSize > 0) fontSize.toFloat() else 0f
    val s = if (translationScale.isFinite() && translationScale > 0f) translationScale else 1f
    return f * (LINE_HEIGHT_RATIO - 1f) / 2f * (1f + s)
}

/**
 * ★★★ 2026-10-13（**BUG-190**）：**行间距档位真正加在纵向偏移上的净量**（px）。
 *
 * ## 它同时修两个缺陷（用户原话）
 *
 * > 「**歌词行间距，即主副歌词行间距为 0 时字迹仍不是相接触的状态**，
 * >  **还有 0 到 1 时，存在跳变**。」
 *
 * ### ① `lineGapPx = 0` ⇒ **墨迹相接触**（缝 = 0）
 *
 * ★ 旧行为：两行**行盒**相接 ⇒ 墨迹间仍留 [lineBoxTrimPx] 的缝（实测 ≈ 9.2px）✗
 * ★ 本式：`lineGapPx = 0` 时**减掉** `trim` ⇒ 两行**墨迹相贴** ✓
 *
 * ### ② `0 → 1` **不再跳变**（连续）
 *
 * ★ 旧式是 `if (lineGapPx > 0f) TRANSLATION_GAP_PX else 0f` —— **阶跃**：
 *   `0` 与 `0.0001` 之间差一整个 `TRANSLATION_GAP_PX`（8px）✗
 * ★ 本式让 `TRANSLATION_GAP_PX` 与 `trim` **在 `[0, RAMP]` 上同步线性爬升/淡出**：
 *
 * | `lineGapPx` | `t` | 净量 | 含义 |
 * |---|---|---|---|
 * | **0** | 0 | `−trim` | ★ **墨迹相接触** |
 * | 4 | .5 | `8×.5 + 4 − trim×.5` | 连续过渡 |
 * | **≥ 8** | 1 | `8 + lineGapPx` | ★★ **与改前逐像素相同**（零回归）✓ |
 *
 * ★★ **零回归的保证**：`RAMP = TRANSLATION_GAP_PX = 8` ⇒ 只要用户的行间距 **≥ 8**
 * （含默认值），本式恒等于旧式 `TRANSLATION_GAP_PX + lineGapPx` ✓
 * ⇒ ★ 只有"拖到 8 以下"的用户会看到变化 —— 而那正是他要调的区间 ✓
 *
 * @param lineGapPx 行间距（`layout.line_gap_px`）
 */
fun translationExtraGapPx(fontSize: Int, translationScale: Float, lineGapPx: Float): Float {
    val g = if (lineGapPx.isFinite()) lineGapPx.coerceAtLeast(0f) else 0f
    val trim = lineBoxTrimPx(fontSize, translationScale)
    // ★ 过渡进度：0（相接触）→ 1（旧行为）
    val t = (g / TRANSLATION_GAP_PX).coerceIn(0f, 1f)
    // ★ `t = 0` ⇒ `−trim`（贴紧）；`t = 1` ⇒ `TRANSLATION_GAP_PX + g`（旧行为）
    return TRANSLATION_GAP_PX * t + g - trim * (1f - t)
}

// ★★★ 2026-10-13（**BUG-190**）：`effectiveTranslationGapPx(lineGapPx)` **已删除**。
//   ★ 它原来是 `if (lineGapPx > 0f) TRANSLATION_GAP_PX else 0f` ——
//     ★★ **那个阶跃正是用户报的「0 到 1 时存在跳变」** ✗
//     （且它只解决"0 时不留 8px"，没解决"0 时墨迹仍不相接"）
//   ⇒ 由 [translationExtraGapPx]（**同时含字号与副行比例**）取代 ✓
//   ★ 两个消费点（[translationRowHeightPx] / [translationCenterOffsetPx]）**仍成对**改用新式 ✓


/**
 * 歌词**文字区**左右各留出的水平内缩（px）。取 14 而不是 `VERTICAL_PADDING` 的 10：
 * 圆角在水平方向的**视觉侵入**比垂直方向明显（字贴着圆角更容易被切到笔画）。
 *
 * ★★★ 2026-10-07 **从 `IslandWindow` 的 `private companion object` 提到本文件顶层**
 * （`migration/103` · 方案 A 的 A2a）。理由：
 * · ★ **探针看不见 `private companion`** ⇒ 探针里只能**硬编码 `14f`**
 *   （`GlyphHitOutsideProbe` 的夹具表当时逐字写着「`IslandWindow` companion
 *   （**探针看不到，按当前值取**）」）⇒ ★ **常量一改，探针静默失配**。
 *   ★★ 2026-10-08 **A4 已收口**：该处与另 4 处硬编码 / 复刻已全部改指本常量
 *   （`GlyphHitOutsideProbe` · `IslandExpandHitProbe` · `SpectrumGeometryProbe` ·
 *   `IslandPolicyProbe`）⇒ ★ 常量一改，**探针的夹具跟着改**（实测：14 → 15 时
 *   `GlyphHitOutsideProbe` 的 `availWidth` 由 848 变 846）。见 `migration/107`。
 * · ★ 本文件的注释**早已在引用它**（BUG-121 的复盘段 · 圆角下限推导段 ·
 *   `spectrumBandInset` 的 `@param`）⇒ ★ 提到顶层**正好给那些注释一个真对象**。
 * · 同包 ⇒ **调用点零改动**（`IslandWindow.kt` 内 **9 处**代码引用全部无限定名照用）。
 *
 * ★ 用 `public` 而非 `internal`：★ 探针是**独立编译模块**，`internal` 看不见
 *   （同 `migration/101` §3 那条墙）；而它是 `Float` ⇒ **无 `EXPOSED_*` 问题**。
 */
const val HORIZONTAL_INSET = 14f

/** 主歌词**行盒**高度（px）—— 与 [islandCollapsedHeightPx] 用的是**同一个**公式。 */
fun lyricMainLineHeightPx(fontSize: Int): Float = fontSize * LINE_HEIGHT_RATIO

/** 副歌词**行盒**高度（px）—— 与 `islandCollapsedHeightPx` 的译文行同源。 */
fun lyricSubLineHeightPx(fontSize: Int, translationScale: Float): Float =
    fontSize * translationScale * LINE_HEIGHT_RATIO

/**
 * 副歌词**预留槽**高度（px）= 副行盒高 + [TRANSLATION_GAP_PX] + `lineGapPx`。
 *
 * ★ 它是 `islandCollapsedHeightPx` 的 `translationRowHeightPx` 入参 ——
 *   **槽高进岛高，落点进绘制**，两者必须由**同一个**函数给出（否则就是 2026-10-03 那个缺陷）。
 *
 * ## ★★★ 为什么含 `lineGapPx`（**2026-10-03 第二次修正**）
 *
 * **原记**（我当天第一版）：「`layout.line_gap_px` 是**歌词行与歌词行之间**的额外间距；
 *   副歌词是当前行的**子行**，不参与行间步进」⇒ 把它从副行落点里**剔掉**。
 * **实为**：`layout.line_gap_px` 的**用户诉求就是「一二行歌词间距」** ——
 *   它与「副歌词（译文的独立行）」是**同一条诉求**里一起提出来的
 *   （取证：`CHANGELOG` v0.12 §④「本轮新增设计：**副歌词**（译文的独立行）· **一二行歌词间距设置项** · …」）。
 *   ⇒ ★ **"一二行" = 主歌词行 + 副歌词行**，不是"多行模式的行间距"。
 *
 * ★★ **剔掉的后果（当天实测）**：`line_gap_px` 在**排版上彻底零效果** ——
 *   `rows == 1` 时 `islandCollapsedHeightPx` 的 `step × (rows−1)` 项为 **0**，
 *   而落点又被剔掉 ⇒ **用户拖这根滑杆，歌词纹丝不动**（= BUG-025 那一族的"死设置"）。
 *   ★ 且它的 `preview` 档是 **`IMMEDIATE`** ⇒ 设计上本就应当**立刻可见**。
 *
 * ★★★ **正确修法：`lineGapPx` 要**成对**加在「槽高」与「落点」上**（不是只加落点、也不是都不加）：
 * | | 槽高 | 落点 | 结果 |
 * |---|---|---|---|
 * | 原实现 | ✗ 不含 | ✓ 含（`(主行行高+gap)/2`） | ⚠️ 墨迹比槽高 `gap/2` ⇒ **溢出岛底** |
 * | 我当天第一版 | ✗ 不含 | ✗ 不含 | ⚠️ **零效果**（死设置） |
 * | **本版** | ✓ 含 | ✓ 含 | ✅ **主副拉开 + 上下留白仍相等 + 岛高跟着长** |
 *
 * ⚠️ **多行模式不冲突**：`step × (rows−1)` 管的是**主行之间**，本项管的是**主行 ↔ 副行**，
 *   两者都是"相邻行之间"的同一语义 ⇒ `rows = 3` 时 `gap` 恰好计入 3 次（2 个行间 + 1 个主副间）✓
 *
 * @param lineGapPx 行间距（`layout.line_gap_px`）。★ **刻意不给默认值** ——
 *   给了默认值就会有人"忘了传"，而那正是本键失效的方式（纪律 64）。
 *
 * ★★ **2026-10-08（U2）**：`TRANSLATION_GAP_PX` 已换成 [effectiveTranslationGapPx]（★ **同 [translationCenterOffsetPx] 成对**）。
 */
fun translationRowHeightPx(fontSize: Int, translationScale: Float, lineGapPx: Float): Float =
    lyricSubLineHeightPx(fontSize, translationScale) +
        // ★★ BUG-190：净量收口到 [translationExtraGapPx]（**同时含字号与副行比例**）——
        //    旧式的 `effectiveTranslationGapPx(g) + g` 已并进它内部 ✓
        translationExtraGapPx(fontSize, translationScale, lineGapPx)

/**
 * ★★★ 副歌词行相对**当前主行中心**的垂直偏移（px，**恒为正**；调用方按上/下取符号）。
 *
 * ```
 * offset = 主行行高/2 + TRANSLATION_GAP_PX + 副行行高/2
 * ```
 *
 * ## ★ 为什么有 `+ 副行行高/2`（**这就是本次修复的那一项**）
 *
 * 副行盒高是 `副行行高`，而它从"主行盒底 + GAP"处开始 ——
 * 故其**中心**比主行盒底低 `GAP + 副行行高/2`。
 * ⚠️ 漏掉最后那半截 ⇒ 副行整体**上浮** `副行行高/2` ⇒ 墨迹填不满预留槽 ⇒ **上下留白不等**。
 *
 * ## ★★★ 关于 `lineGapPx`（**2026-10-03 第二次修正，一次方案翻转**）
 *
 * **原记**（我当天第一版）：「本式**不含** `lineGapPx` —— 行间距不该推动子行」。
 * **实为**：`layout.line_gap_px` 的**用户诉求就是「一二行歌词间距」**
 *   （= 主歌词行 ↔ 副歌词行，见 [translationRowHeightPx] 的 KDoc）。
 *   ★ 剔掉它 ⇒ 该键**排版零效果** ⇒ **死设置**。
 * ⇒ **本式与 [translationRowHeightPx] 必须同时含 `lineGapPx`**（成对）：
 *   槽高 +gap 与落点 +gap ⇒ 墨迹**仍恰好填满槽** ⇒ 上下留白**仍相等**、且**岛高跟着长**（不溢出）。
 *
 * @param above `true` = 副歌词在**上方**（`layout.translation.position = ABOVE`）
 * @param lineGapPx 行间距（`layout.line_gap_px`）。★ **刻意不给默认值**（见 [translationRowHeightPx]）
 *
 * ★★ **2026-10-08（U2）**：与 [translationRowHeightPx] **成对**改用 [effectiveTranslationGapPx]
 *   ⇒ 行间距 0 时两行**紧密相贴**，且**槽高与落点仍自洽**（墨迹恰好填满槽）。
 */
fun translationCenterOffsetPx(
    fontSize: Int,
    translationScale: Float,
    above: Boolean,
    lineGapPx: Float,
): Float {
    val d = lyricMainLineHeightPx(fontSize) / 2f +
        // ★★ BUG-190：与 [translationRowHeightPx] **成对**改用 [translationExtraGapPx] ✓
        translationExtraGapPx(fontSize, translationScale, lineGapPx) +
        lyricSubLineHeightPx(fontSize, translationScale) / 2f
    return if (above) -d else d
}

/**
 * **主歌词首行**（= 当前行）中心在歌词区内的 y。
 *
 * 与 `IslandWindow.drawLyricSlots` 的 `firstY` **同一个式子**（块居中 + 译文在上时下移）。
 *
 * ⚠️ **两档的式子不同，这里**逐字保留**（不是"统一成一个"）**：
 * · **无译文**档走 `centerY − anchorIndex × lineHeight`（**锚点行居中**）；
 * · **有译文**档走 `centerY − ((rows−1) × lineHeight + subRowHeight)/2 + shift`（**整块居中**）。
 * ★ 当前 `slotsFor` **恒返回单槽** ⇒ `anchorIndex = 0`、`rows = 1` ⇒ **两档退化成同一个值**
 *   （都是 `centerY`）。保留两档是因为"将来恢复多行"时它们**不再等价**
 *   （锚点居中 vs 整块居中，见 `drawLyricSlots` 里那段长注释的实测结论）。
 *
 * @param areaTop 歌词区顶边（收起态 = 0）
 * @param areaHeight 歌词区高（收起态 = 岛高）
 * @param anchorIndex 锚点槽下标（= `present.indexOfFirst { it.isCurrent }`，取不到时为 0）
 * @param rows 有效槽数（`present.size`）
 * @param lineHeightPx 主歌词**步进**（含 `lineGapPx` —— 与 `drawLyricSlots` 同名变量同义）
 * @param subRowHeightPx 副歌词预留槽高（[translationRowHeightPx]；无译文 = `0f`）
 * @param translationAbove 副歌词是否在上方
 */
fun lyricFirstCenterY(
    areaTop: Float,
    areaHeight: Float,
    anchorIndex: Int,
    rows: Int,
    lineHeightPx: Float,
    subRowHeightPx: Float,
    translationAbove: Boolean,
): Float {
    val centerY = areaTop + areaHeight / 2f
    if (subRowHeightPx == 0f) {
        return centerY - anchorIndex * lineHeightPx
    }
    val subRowShift = if (translationAbove) subRowHeightPx else 0f
    return centerY - ((rows - 1) * lineHeightPx + subRowHeightPx) / 2f + subRowShift
}

// ══════════════════════════════════════════════════════════════════════════════
// 封面边长：真机与设置页预览的**同一个**定义点（2026-09-22）
// ══════════════════════════════════════════════════════════════════════════════
//
// `IslandWindow.leadingCoverSize`（真机）与 `IslandPreviewPane.coverSideFor`（设置页）
// 此前是**两份逐字相同的公式**（预览那份的 KDoc 自己写着"与真机同一套公式"）。
// 本轮把封面尺寸变成展开进度 `f` 的函数（用户第 2 条），两份若各改一次必然漂移 ——
// 而漂移的表现是"设置页示例与真机不一致"（正是 lead 点名要防的那件事）。
// ⇒ 公式收敛到**这一处**，两侧都调它。

/** 封面**外径**的下限（px）—— "封面不能小到看不清"的护栏（与真机 `MIN_COVER_SIZE` 同值）。 */
const val COVER_MIN_OUTER_SIZE_PX = 20f

/** `cover.scale_with_font = false` 时的外径上限（px）（与真机 `MAX_COVER_SIZE` 同值）。 */
const val COVER_MAX_OUTER_SIZE_PX = 56f

/**
 * ★★★ 2026-10-13（**BUG-189**）：**设备缩放**（HiDPI / 系统缩放）—— **位图**内容的渲染倍率。
 *
 * ## 为什么需要它（用户原话）
 *
 * > 「**词岛显示的封面分辨率太低**」（附截图：**歌词字清晰、左端封面糊**）
 *
 * Swing 组件的 `Graphics2D` 带着**设备变换**（`getDeviceConfiguration().getDefaultTransform()`）。
 * 在 125% / 150% 缩放下，一次"逻辑坐标 1:1"的 `drawImage` 在**设备坐标系**里其实是**放大** ——
 * 而 Java2D 的默认插值是**最近邻** ⇒ 位图出块、发糊。
 *
 * ★ 对**矢量**（文字、形状）这不是问题：它们按设备坐标**重新光栅化**；
 * ★★ 只有**位图**（封面）会暴露 ⇒ 这正是"歌词清晰、封面糊"的成因 ✓
 *
 * ## 唯一正确用法
 *
 * ```
 * 位图渲染像素 = ceil(逻辑尺寸 × deviceScale)
 * ```
 * **而绘制目标矩形保持逻辑坐标** ⇒ 设备坐标系下 **1:1** ⇒
 * ★ **屏幕上的大小形态逐像素不变**（正是用户要的「大小形态不变」），只是像素更多 ✓
 *
 * ## 为什么退 `1f` 而不是抛
 *
 * 这是**绘制路径**（每帧调用）。取不到设备配置时退 `1f` = **改前行为**
 * ⇒ 最坏情况是"和以前一样"，不会更差 ✓
 *
 * @return 缩放；取不到 / 非有限 / `≤ 0` ⇒ **`1f`**
 */
fun deviceScaleOf(g: Graphics2D): Float {
    val tx = runCatching { g.deviceConfiguration?.defaultTransform }.getOrNull()
    val s = tx?.scaleX?.toFloat() ?: 1f
    return if (s.isFinite() && s > 0f) s else 1f
}

/**
 * ★★★ 2026-10-13（**BUG-189**）：**位图的渲染像素数** —— `ceil(逻辑尺寸 × 设备缩放)`。
 *
 * ## 为什么必须是一个**函数**（而不是两处各写一遍）
 *
 * ★ 它有两个消费点（真机 `IslandWindow.drawCoverImage` / 预览 `IslandPreviewPane.drawMaskedCover`），
 * 而本项目栽过"同一算式两处各写一遍 ⇒ 漂移"（`releasing` 系列）。
 * ★★ 更要紧的是：**判据必须建在调用点上**。若把它写在两个 `private` 方法里，
 * 探针就只能测"模拟版本"（**恒真式**）⇒ 谁把生产代码改回"只渲染逻辑像素"**都不会红** ✗
 *
 * ## 语义（两个方向的夹取都在这里）
 *
 * - 逻辑尺寸非有限 / `≤ 0` ⇒ 退 `1`
 * - 设备缩放非有限 / `≤ 0` ⇒ **退 `1f`**（= 改前行为，最坏不会更差）✓
 * - 结果 `≥ 1`（`BufferedImage` 不允许 0 尺寸）
 *
 * @param logicalSizePx 位图要占的**逻辑**尺寸（封面这里是"内容直径"）
 * @param deviceScale 设备缩放（来自 [deviceScaleOf]）
 */
fun bitmapRenderPx(logicalSizePx: Float, deviceScale: Float): Int {
    val l = if (logicalSizePx.isFinite() && logicalSizePx > 0f) logicalSizePx else 0f
    val s = if (deviceScale.isFinite() && deviceScale > 0f) deviceScale else 1f
    return kotlin.math.ceil(l * s).toInt().coerceAtLeast(1)
}

/**
 * 封面**外径**边长（px）—— 由"它所依附的那块纵向空间高度"推导。
 *
 * ```
 * box   = max(0, areaHeight − 2 × VERTICAL_PADDING)   ← 盒子里真正能用的高度
 * floor = min(MIN, box)                                ← ★ 下限自己也服从盒子（BUG-200）
 * size  = max(box, floor)                              ← == box（floor ≤ box 恒成立）
 * scale_with_font = true  → size                       （**不套上限**）
 * scale_with_font = false → size.coerceAtMost(MAX)     （保留旧上限夹取）
 * ```
 *
 * ⚠️ 上下限**只在 `scale_with_font = false` 时套上限**是 2026-09-18 用户报
 * 「封面不随字体缩放」的修法：`MAX_COVER_SIZE = 56` 在默认字号下就已经撞到，
 * 于是"跟随字号"名存实亡。下限两种情况都保留（它与"是否跟随"无关）——
 * ★★ **但下限从 2026-10-14 起会服从盒子**（BUG-200：原写下限可越界 ⇒ 封面脱出）。
 *
 * @param areaHeightPx 纵向空间高度。**真机传的是岛体绘制高度**
 *   （`BandGeometry.islandHeight`）—— 于是它天然随展开进度从"歌词区高"连续长到
 *   "整个岛体高"，这就是用户要的"封面跟着放大"。
 *   ⚠️ `f = 0` 时 `islandHeight == lyricsHeight` ⇒ **与改动前逐像素相同**。
 */
fun leadingCoverOuterSizePx(areaHeightPx: Float, scaleWithFont: Boolean): Float {
    val h = if (areaHeightPx.isFinite()) areaHeightPx else 0f
    // ★★★ 2026-10-14（**BUG-200**）：**下限不许超过"盒子里放得下的尺寸"** ✗
    //
    // ## 用户报障（逐字，本批第 1 条）
    //
    // > 「**封面的脱出**」（截图用红圈圈出**真实桌面词岛**左端的封面）
    //
    // ## ★★ 根因（纯算术，`CoverProtrusionProbe` 已坐实）
    //
    // 原实现：`natural = (h − 2×PAD).coerceAtLeast(MIN)`
    // ★ `coerceAtLeast(MIN)` 是**硬下限** —— 当 `h − 2×PAD < MIN` 时它**盖过**了
    //   "放进盒子里"这个本意 ⇒ 外径 = MIN = 20 > h − 2×PAD ⇒ **必然溢出** ✗
    //
    // ★ 触发条件（实测最小场景）：字号 12 + 1 行 + 高度档 100%
    //   ⇒ 岛体高 = `int(12 × 1.36) = 16` px，而 `16 − 20 = −4 < 20` ⇒ 外径 = 20
    //   ⇒ `coverTopFor(20) = (16 − 20)/2 = −2` ⇒ 封面上下各**戳出 2.00 px** ✓
    //   （探针在全区间上量到的最大值正是 **2.00 px**，与手算逐位吻合）
    //
    // ## ★ 修法：`MIN` 只作"**盒子放得下时**的下限"，绝不越界
    //
    // ```
    // box   = max(0, h − 2×PAD)   ← "盒子里真正能用的高度"
    // floor = min(MIN, box)        ← ★ 下限**自己也服从盒子**
    // size  = max(box, floor)      ← == box（因 floor ≤ box 恒成立）
    // ```
    //
    // ★ 为什么是 `min(MIN, box)` 而不是 `MIN`（**这是本修法的关键一行**）：
    //   直接用 `MIN` 就是把原来的缺陷换个写法 ✗ —— 必须让下限**自己也服从盒子**。
    // ★ 形状：`h` 足够大时 `box >= MIN` ⇒ 退化成**改动前逐字相同**的 `coerceAtLeast(MIN)` ✓
    //   ⇒ **只修"盒子本来就比 MIN 小"这一档**（那正是唯一会溢出的档）✓
    val box = (h - 2f * ISLAND_VERTICAL_PADDING_PX).coerceAtLeast(0f)
    // ★ 下限**自己也必须服从盒子** —— 直接用 MIN 就是把原缺陷换个写法 ✗
    val floor = COVER_MIN_OUTER_SIZE_PX.coerceAtMost(box)
    // ★ 盒子里能用的最大值就是 box ⇒ `natural == box`，再抬到 floor（floor ≤ box 恒成立）
    val sized = box.coerceAtLeast(floor)
    return if (scaleWithFont) sized else sized.coerceAtMost(COVER_MAX_OUTER_SIZE_PX)
}

/**
 * 封面让位的**相对**左内缩（px）—— `LEADING_INSET + 槽宽 + LEADING_GAP`。
 *
 * 这是 `areaLeft` 的**唯一**定义点（真机与预览共用）。它的输入只有"封面外径"与两个
 * 间距常量，故"封面变大 ⇒ 歌词区左边界右移"这条耦合**只在这里**发生一次。
 *
 * ## ⚠️ 为什么它是"相对"的（不含岛体左边缘）
 *
 * 因为**自然宽度测量**用的是它：`naturalWidthPx = 最宽行 advance + 本值 + 2 × HORIZONTAL_INSET`。
 * 若把"岛体左边缘"（它本身由岛宽派生）算进来，就会形成
 * **岛宽 → 让位宽 → 自然宽 → 岛宽** 的闭环（一个自激振荡的反馈环）。
 * 绘制侧再单独加上岛体左边缘 —— 见 `IslandWindow` 的 `areaLeft`。
 *
 * @param outerSizePx 封面外径（`<= 0` → 返回 `0f`，即"不显示封面"）
 * @param leadingInsetPx 封面上/左的内缩（真机 `LEADING_INSET`）
 * @param leadingGapPx 封面槽位与歌词区之间的间距（真机 `LEADING_GAP`）
 * @param slotWidthPx 槽位宽度（真机 `coverSlotWidth(outerSize, vinyl)`，恒等于外径）
 */
fun leadingCoverAreaLeftPx(
    outerSizePx: Float,
    leadingInsetPx: Float,
    leadingGapPx: Float,
    slotWidthPx: Float = outerSizePx,
): Float {
    if (!outerSizePx.isFinite() || outerSizePx <= 0f) return 0f
    val inset = if (leadingInsetPx.isFinite()) leadingInsetPx.coerceAtLeast(0f) else 0f
    val gap = if (leadingGapPx.isFinite()) leadingGapPx.coerceAtLeast(0f) else 0f
    val slot = if (slotWidthPx.isFinite()) slotWidthPx.coerceAtLeast(0f) else 0f
    return inset + slot + gap
}

/**
 * ★★ 2026-10-04：**收起态**下的左让位（封面侧，相对岛体左边缘）—— ★ **唯一口径**。
 *
 * ## 为什么单独成函数（本轮复核抓到的一处重复）
 *
 * `applyGeometry` 与 `applyNaturalWidth` **都要**它（前者算"两侧对称化"增量、
 * 后者算总增量）⇒ 若两处各写一遍 `leadingCoverAreaLeftPx(COVER_MAX_OUTER_SIZE_PX, …)`
 * ⇒ ★ **迟早漂移**（本项目最多次的缺陷形态）。
 *
 * ## ★ 为什么用**常量** `COVER_MAX_OUTER_SIZE_PX` 而不是实时封面外径
 *
 * 收起态封面外径**恒为该常量**（见 `coverShapeCollapsedHeightPx(coverOuterSizePx = …)`）。
 * ★ 且**必须**用收起态值：`leadingCoverSize` 在**展开动画期会变大** ⇒ 用它算增量
 * ⇒ 岛宽随悬停变化 ⇒ `setBounds` ⇒ **闪动复发**（本项目 2026-09-21 根治过这个闭环）。
 *
 * ⚠️ 若将来收起态封面外径变成"按高度算"，**本函数是唯一要改的地方** ✓
 *
 * @param leadingInsetPx 岛体内缩（`LEADING_INSET`）
 * @param leadingGapPx 封面与歌词之间的间隙（`LEADING_GAP`）
 */
fun collapsedCoverInsetRel(leadingInsetPx: Float, leadingGapPx: Float): Float =
    leadingCoverAreaLeftPx(
        outerSizePx = COVER_MAX_OUTER_SIZE_PX,
        leadingInsetPx = leadingInsetPx,
        leadingGapPx = leadingGapPx,
        slotWidthPx = coverSlotWidth(COVER_MAX_OUTER_SIZE_PX, false),
    )

// ══════════════════════════════════════════════════════════════════════════════
// 「悬浮窗（封面态）」的收起态尺寸（封面态 S3，2026-09-30）

/**
 * 「封面态」收起态（S1）的岛体高度（px）—— **唯一**推导点。
 *
 * ## 口径来源（`recon/67` §9-A 定案 ①「不生效」）
 *
 * 用户拍板：本态下 `layout.island.height.ratio_percent` **不生效**。
 * 理由：那个键的语义是「收起态高度 ÷ **歌词块高度**」，而**本态压根没有歌词块**
 * ⇒ 基准缺失，比值无从谈起。
 *
 * 故本态高度改为由**封面口径**直接给出：
 *
 * ```
 * 岛高 = 封面外径 + 2 × ISLAND_VERTICAL_PADDING_PX
 * ```
 *
 * ★ 这与"跟随字号"档（[islandCollapsedHeightPx] 的 `followFont = true` 分支）
 * **是同一条式子**（`block + 2 × padding`）—— 只是把 `block`（歌词块）
 * 换成了「封面外径」。故本态下 `height.follow_font` 同样不参与。
 *
 * ## 为什么抽成公开纯函数（而不是写进 `islandHeightFor` 的 `when`）
 *
 * 与 [islandCollapsedHeightPx] 同一理由：`IslandPanel` 的 companion 是 `private`，
 * 探针（独立源集）看不到 ⇒ 「本态真的按封面口径算、且**不**受 ratio 影响」
 * 这条断言写不出来。本项目纪律：**首选抽公开纯函数，而不是为了测它而放宽可见性**。
 *
 * ## 边界
 *
 * - `coverOuterSizePx <= 0` 或非有限 ⇒ 退回 [COVER_MIN_OUTER_SIZE_PX]（封面不能小到看不见）。
 * - 返回值**不含** `layout.island.height.ratio_percent` —— 这是本函数的**契约**，
 *   由 `CoverShapeSizeProbe` 断言（翻那个比值不得改变本函数输出）。
 *
 * @param coverOuterSizePx 封面外径（px）。真机由 [leadingCoverOuterSizePx] 给出。
 * @param verticalPaddingPx 上下内边距（真机 `ISLAND_VERTICAL_PADDING_PX`）。
 */
fun coverShapeCollapsedHeightPx(
    coverOuterSizePx: Float,
    verticalPaddingPx: Float = ISLAND_VERTICAL_PADDING_PX,
): Int {
    val size = if (coverOuterSizePx.isFinite() && coverOuterSizePx > 0f) {
        coverOuterSizePx
    } else {
        COVER_MIN_OUTER_SIZE_PX
    }
    val pad = if (verticalPaddingPx.isFinite()) verticalPaddingPx.coerceAtLeast(0f) else 0f
    return (size + 2f * pad).toInt()
}

/**
 * 「封面态」**是否跳过歌词链**（频谱 / 歌词槽位）—— 本态只画「壳 + 封面 + 文本」。
 *
 * ## 为什么需要它（`recon/67` §5-⑤）
 *
 * 本态的内容是「封面 + 曲名/作者」（§9-G 定案：**一行**，复用 `trackCreditLine`）。
 * 若不跳过，本态会**照旧画歌词** —— 而本态的岛体尺寸是按**封面**算的，
 * 歌词链进来会画到一个与它无关的尺寸体系里。
 *
 * ## ★ 为什么不直接写 `shapeType == "COVER_ONLY"`
 *
 * 那样会在**多个**消费点各写一次字面量（本项目已因"同一规则多处出现"栽过多次）。
 * 提成函数后，判据**只此一处**，且**可被探针断言**（这是本项目抽公开纯函数的一贯理由）。
 *
 * ★ 注意：它**只管"内容段"**，不管轮廓（轮廓由 `islandEdgeRadiusPx` /
 * `expandedOutlineRadii` 各自的 `when` 负责）—— 那两处是 S1 已接的线，不归本函数。
 */
fun coverShapeSkipsLyricChain(shapeType: String): Boolean = shapeType == "COVER_ONLY"

/**
 * 「本态 + 已锁」是否**阻止**鼠标命中触发展开（`recon/67` §3.5）。
 *
 * ## 语义（三分支，用户 2026-09-29 00:4x 定案）
 *
 * | 锁状态 | 命中封面 | 结果 |
 * |---|---|---|
 * | **未上锁** | ✔ | **完整展开**（= S2，向右生长） |
 * | **已上锁** | ✔ | 只在**右上角弹一下锁头标志**，**词岛不展开**（防误触） |
 * | **已上锁** | ✔ 且**点那锁头标志** | **展开**（用户原话「点锁标 = 展开」） |
 *
 * ⇒ 本函数回答的是第 2 行那一条：**`true` ⇒ 把命中折成"未命中"**，
 *   于是 `HoverState` 永远不会进入展开 —— 这正是"防误触"。
 *
 * ## 为什么抽成公开纯函数（而非内联在 `advanceHover`）
 *
 * 与 [coverShapeSkipsLyricChain] 同一条理由，且**本项尤其需要**：
 *  · 它是"锁"这个**新概念**的**唯一行为判据** —— 分散写会让"锁到底管什么"
 *    在绘制 / 命中 / 菜单三处各自漂移；
 *  · 它是**布尔翻转语义**（`true` = 阻止），最容易被写反，**必须能被探针钉住**；
 *  · 探针读不到 `private`（probe 是独立编译模块）⇒ 必须 `public`。
 *
 * ## ⚠️ 为什么"本态"是前置条件（而不是只判 `locked`）
 *
 * `cover.lock` 是**全局开关**（落盘、进预设），但它的**语义只在本态成立** ——
 * 既有 4 型里根本没有"命中封面 ⇒ 展开"这条交互（那是封面态独有的）。
 * 若只判 `locked`，用户在有歌词的形态下误开此键 ⇒ 行为**莫名其妙地变了**
 * （说不出哪里变，因为那条交互本来不存在）⇒ 是"死设置"的另一种形态。
 *
 * @param shapeType `shape.type` 的 wire 值。非 `"COVER_ONLY"` ⇒ 恒 `false`。
 * @param locked `cover.lock` 的当前值。
 */
fun coverLockBlocksExpand(shapeType: String, locked: Boolean): Boolean =
    locked && coverShapeSkipsLyricChain(shapeType)

/**
 * 锁头标志在 [nowNanos] 时刻是否应当**显示**（且因而**可点**）。
 *
 * ## ★★★ 为什么抽成公开顶层纯函数（2026-10-07，`migration/98` · C-2）
 *
 * ★ 这条判据原来在 `IslandWindow` 里是**两个 `private fun`**（`coverLockBadgeVisibleAt` /
 * `coverLockBadgeVisible`），而 `IslandWindow` 的 KDoc **声称**「它的时刻入参可注入，
 * 故这条**可被探针钉住**」—— ★★ **那是假的**：
 *  · 探针是**独立编译模块**，**读不到 `private`**（同文件另一处 KDoc 自己就写着这句）；
 *  · 全探针树对该函数名 **0 命中**。
 * ⇒ ★ **"锁头标志的显示窗口期"当时【零护栏】** —— 判据写反（`until <= 0` / `now < until`）
 *   无人拦，表现为「标志该显示时不显示」或「常驻」。
 *
 * ★ 现按本项目既有做法（`coverLockBlocksExpand` 的先例）**抽成公开纯函数**：
 * 真机传 `coverLockBadgeUntilNanos`、探针传**注入时刻**，两侧共用同一实现。
 *
 * ## 为什么显式判 `untilNanos > 0`
 *
 * `untilNanos <= 0` 表示"**从未触发过**" ⇒ 不显示。不能用 `now < until` 一个式子兜住：
 * `now` 也可能很大而 `until = 0`，那时 `now < until` 为假 —— 结果**恰好对**，
 * 但语义是**偶然正确**的。故显式判 `> 0`，把"未触发"与"已过期"分开。
 *
 * @param nowNanos 当前时刻（`System.nanoTime()` 口径；探针注入任意值）。
 * @param untilNanos 标志的到期时刻；`<= 0` = 从未触发过。
 */
fun coverLockBadgeVisibleAt(nowNanos: Long, untilNanos: Long): Boolean =
    untilNanos > 0L && nowNanos < untilNanos

/**
 * 封面态下是否**强制需要频谱数据**（`recon/67` §9-T 定案 ①）。
 *
 * ## 为什么封面态需要频谱（本态**不显示**频谱，却要采）
 *
 * 封面态的「水位光感」进度里，**波动水面**的数据源就是频谱
 * （§2.4.7 B：用户定案「用**低高度的多条曲线状频谱音频线**模拟，成本更低」）。
 * ⇒ 本态虽然**画面上没有频谱柱**，但**水位波动**吃的是同一份数据。
 *
 * ## ★★ 为什么必须"强制"（不强制就会踩一个已验证的坑）
 *
 * `CdlRuntime` 逐字写着：
 *
 * > 「因为**它的存在本身取决于设置**：`spectrum.enabled` **关时不该起进程**」
 *
 * ⇒ 若用户关掉「频谱」开关 ⇒ **频谱助手进程不启动** ⇒ 没有数据 ⇒ **水位纹丝不动**。
 * ★ 而本态**本来就不显示频谱** ⇒ 用户**很可能顺手把它关掉**
 * ⇒ 然后水位波动也没了 ⇒ **表现为"设了没反应"**（本项目最多次的缺陷形态）。
 *
 * ⇒ 与 D1（`island.hit.cover`）/ D2（`cover.enabled`）**同一处置手法**：
 *   **本态强制视为开启**，且 —— 关键 —— **绝不改键值**（改键值 = 偷改用户配置，
 *   用户回头会发现"我没开过频谱，怎么自己开了"）。
 *
 * ## ⚠️ 代价（如实记，用户已知悉并拍板）
 *
 * 频谱是**独立进程**（`ProcessSpectrum`）⇒ 本态会**多跑一个进程**。
 * 这与"省占用"有冲突 —— 用户在 2026-09-30 拍板时已知悉此代价
 * （当时他刚为"进程数爆掉提交量"折腾过一轮），仍选"按方案定案：强制开频谱"。
 * ⇒ 判据保持**可被探针钉住**，日后若要改（例如退回合成曲线），改这一处即可。
 *
 * ## 为什么抽成公开纯函数
 *
 * 与 [coverShapeSkipsLyricChain] / [coverLockBlocksExpand] 同一理由：
 *  `CdlRuntime` 与 `IslandWindow` **两处**都要问这个问题
 *  （进程启停 + 渲染降级）⇒ 不收成一处必然漂移；
 *  且它是"本态强制"这个**第 3 个特例** —— 前两个已经有了（D1 / D2），
 *  特例越多，越需要能被一眼数清。
 *
 * @param shapeType `shape.type` 的 wire 值。非 `"COVER_ONLY"` ⇒ 恒 `false`
 *   （既有 4 型**不新增任何进程** —— 这是"默认值要稳"的硬要求）。
 */
fun coverShapeForcesSpectrum(shapeType: String): Boolean = coverShapeSkipsLyricChain(shapeType)

// ── 「无歌词」状态判定（唯一真源）

/**
 * 「无歌词」到底是什么 —— **三态**，不是两态。
 *
 * ## 为什么需要它（用户报障）
 *
 * 用户原话（逐字）：「**无歌词时的显示逻辑混乱(开关逻辑不清)，判定不准确(时显时不显)**」。
 *
 * ## 原实现：判据是 `currentLine == null`，它把**三种不同状态**混成一种
 *
 * | 状态 | `lyricLineCount` | `currentLine` | 应然行为 |
 * |---|---|---|---|
 * | **(A) 本曲确实没有歌词** | `0` | `null` | 按 `noLyrics.mode` 走（占位 / 隐藏 / 隐整个词岛） |
 * | **(B) 还没唱到第一行**（歌曲开头 / seek 到开头） | `> 0` | `null` | **什么都不画**——歌词马上就来 |
 * | **(C) 过门间隙** | `> 0` | 上一行 | 保持显示上一行（`lineAt` 已处理） |
 *
 * 改动前只能判 `currentLine`，于是 **(B) 被当成 (A)** ——
 * **每一首歌的开头几秒**都会画一下占位提示（随后第一行来了又消失）
 * —— 这就是用户说的「**时显时不显**」。
 * 同时 `HIDE_ISLAND` 也会在开头几秒**把整个词岛藏掉、第一行来了又冒出来**。
 *
 * 另一个“逻辑不清”的来源：**同一个判据在两处各写了一份**。
 * `applyVisibility` 写的是 `currentLine == null && prevLine == null`，
 * 而 `drawLyricSlots` 写的是 `present.isEmpty()`（即 `currentLine == null`）。
 * 它们在本项目的数据上**恰好等价**（`prevLine` 在 `index == -1` 时恒为 `null`，
 * 故第二个条件**恒为真**），但两处**看起来**不一样
 * —— 后来者看不出“它还要跟什么共享条件”，改一处漏一处的典型形态。
 *
 * ## 为什么抽成**公开纯函数**
 *
 * 它有两个消费者（`applyVisibility` 与 `drawLyricSlots`）且负责**画面状态**。
 * 抽成纯函数后，探针可以**穷举所有边界组合**并逐一断言；
 * 而“两处各写一份”的话，探针只能把**同一份判据抄两遍**，
 * 两者一旦分岔它们**仍都绿**—— 正是本项目反复记录的那类空护栏。
 *
 * @param lyricLineCount 文档里的**总行数**（`TimelineSnapshot.lyricLineCount`）
 * @param hasCurrentLine 当前时刻**是否**有应显示的行
 *   （`snapshot.currentLine != null`）
 * @return 当前应处于哪一态
 */
fun noLyricsDisplay(lyricLineCount: Int, hasCurrentLine: Boolean): NoLyricsDisplay = when {
    // (C) 有当前行：照歌词画（与空文档情况无关）。
    hasCurrentLine -> NoLyricsDisplay.LYRIC
    // (A) 文档确实是空的 ⇒ 本曲没有歌词。
    lyricLineCount <= 0 -> NoLyricsDisplay.NO_LYRICS
    // (B) 有行但还没到 ⇒ 前奏，**什么都不画**。
    else -> NoLyricsDisplay.NOT_YET
}

/**
 * [noLyricsDisplay] 的三态结果。放在顶层是刻意的：
 * 探针不能看到 `private`/`internal` 嵌套，而“把前奏当成无歌词”这种失败
 * **不抛任何异常**，只能靠断言兜住。
 */
enum class NoLyricsDisplay {
    /** 有当前行，照常画歌词。 */
    LYRIC,

    /** 本曲确实没有歌词 —— 按 `layout.noLyrics.mode` 处理。 */
    NO_LYRICS,

    /** 有歌词但还没唱到第一行 —— **什么都不画**。 */
    NOT_YET,
}

/**
 * **"还在等封面"是否已经结束** —— 唯一判据（2026-09-28 用户报障修复）。
 *
 * ## 它守的是什么（用户原话）
 *
 * > 「**无歌词和封面的歌曲会复用上一首歌曲的封面**」
 *
 * ## 根因：判据与"帧定时器在不在跑"耦合
 *
 * 原设计用 `coverRepaintAttempts >= MAX_COVER_REPAINT_ATTEMPTS`（**计数**）判定
 * "本曲没有封面了"。但那个计数只在 [scheduleCoverRepaint] 里推进，而它的第一件事是
 * `if (frameTimer?.isRunning == true) return` —— **播放中直接返回，计数一次都不加**。
 * 于是**无封面的歌一放起来**，`coverPending` 就永远停在 `true`
 * ⇒ `resolveCoverMix` 一直走"等新图"档 ⇒ **旧封面被原样继续画**。
 *
 * ⚠️ 暂停时反而正常（帧定时器停了 ⇒ 计数真的会跑满）—— 这才是本缺陷"时好时坏"的来源。
 *
 * ## 修法：把判据从"数了几次"换成"过了多久"
 *
 * 本函数是那条判据的**唯一实现**，供两处调用（`coverImageFor` / `scheduleCoverRepaint`）
 * —— 各写一份就是本项目反复吃亏的"同族两处实现只改一处"（纪律 11）。
 *
 * ★ 与纪律 14 同源：「回灌/反弹守卫用**比值/时间**不用标志位」——
 *   标志/计数与时序耦合，迟早失效；**绝对时间与之无关**。
 *
 * ## 为什么它是**无状态纯函数**（这是能被探针断言的前提）
 *
 * 判据只依赖"现在几点 / 截止期几点 / 还在不在等"三个**入参**，不读任何字段 ——
 * 于是探针可以**直接喂时间断言**，不必去驱动一个真实的 `Timer`。
 *
 * ⚠️ **`deadline == 0L`（未在等）必须返回 `false`**：
 *   `nanoTime()` 可以是任意值（甚至负数），若把 `0L` 当成一个真实的截止期，
 *   "未在等"会被误判成"早就过期了"。故必须先判 `pending`。
 *
 * @param pending 当前是否处于"等待封面"状态（= `coverPending`）
 * @param nowNanos `System.nanoTime()` 的当前值
 * @param deadlineNanos 本次等待的**绝对截止期**（`0L` = 未起算）
 * @return `true` = **等待已结束**（可认定"本曲没有封面"）
 */
fun coverWaitElapsed(pending: Boolean, nowNanos: Long, deadlineNanos: Long): Boolean {
    // 未在等 → 无所谓"结束"（把 0L 当截止期会让它恒真）。
    if (!pending) return false
    // 未起算截止期（0L）→ **不起作用**，不判定结束（由计数那一路兜底）。
    if (deadlineNanos == 0L) return false
    return nowNanos >= deadlineNanos
}

// ══════════════════════════════════════════════════════════════════════════════
// ★★★ L3 采集 worker 的**幂等指纹**（2026-10-03）—— **唯一实现点**
// ══════════════════════════════════════════════════════════════════════════════
//
// ## 为什么它必须是公开纯函数
//
// `IslandPanel.syncL3` 是 `private inner class` 的成员 ⇒ 探针够不到，
// 于是"**指纹有没有漏项**"这条判据**写不出来**。而漏项的后果是**静默的**：
// 用户改了某项设置、worker 不重建 ⇒ 仍用旧值 ⇒ 表现为"**改了没反应**"，
// 与"设置项本身无效"**完全一样**，极难归因。
//
// ★ 本项目已经栽过**两次**（都在这个函数上）：
// · **模糊半径**：注释写着「已在跑且**半径与日志开关都没变**」，而代码里
//   **从来没有比较半径的项** ⇒ 从一个非 0 半径拖到另一个非 0 半径**不生效**；
// · **折射的 SDF 圆角**：它是 `BackdropCaptureParams` 的构造参数，却**不在判据里**
//   ⇒ 改「外观形态」/「圆角大小」时折射仍用旧圆角（折射带与岛体边缘错开）。
//
// ## 判据（**可机械核**）
//
// **凡是进了 `BackdropCaptureParams` 构造参数的量，都必须出现在本指纹里。**
// 探针 `IslandPolicyProbe` 用"逐项扰动 ⇒ 指纹必须变"把它钉住。

/**
 * L3 采集 worker 的**幂等指纹**。
 *
 * @param blurRadius `material.backdrop.blur_radius`（★ 曾漏项）
 * @param frameLogEnabled `animation.frame_log`（构造参数，运行期不可改）
 * @param refractionHeight / refractionAmount / dispersion / saturation ④b 的 4 个键
 * @param cornerRadiusTop / cornerRadiusBottom 折射 SDF 的圆角（★ 曾漏项）——
 *   它们随 `shape.type` / `shape.corner_radius` / 展开进度变化
 * @param gpuBackend `material.render.gpu_backend`（R7 · 2026-10-09 加）——
 *   ★★ **2026-10-11（U12 全面清理）后该键已 `retired`** ⇒ 生产侧 `rawFor` 返回
 *   `null` ⇒ `bool(...)` 取默认 `false` ⇒ ★ **实参恒为 `false`**（指纹里那一项恒为
 *   `gfalse`，**不再抖动** ⇒ 不会引起 worker 反复重建）✓
 *   ⚠️ ★ **但这一项不能删**：它覆盖的是 `BackdropCaptureParams.gpuBackend` 这个
 *   **构造参数** —— ★ 键被 retired **不等于**参数被删（用户规则①「不删实际代码链路」）；
 *   删掉它 ⇒ 将来若有人复活 GPU 后端并让实参可变，**指纹就漏项了**（= 本函数开头
 *   那两次历史事故的形态）✓
 *   ★ 判据：`IslandPolicyProbe` §L3K 的「扰动『GPU 后端』⇒ 指纹必须变」✓
 */
fun l3WorkerKey(
    blurRadius: Int,
    frameLogEnabled: Boolean,
    refractionHeight: Int,
    refractionAmount: Int,
    dispersion: Int,
    saturation: Int,
    cornerRadiusTop: Double,
    cornerRadiusBottom: Double,
    gpuBackend: Boolean = false,
): String =
    "b$blurRadius|f$frameLogEnabled|" +
        "h$refractionHeight|a$refractionAmount|d$dispersion|s$saturation|" +
        "ct$cornerRadiusTop|cb$cornerRadiusBottom|g$gpuBackend"

/**
 * "**显示哪几行（主歌词）**"的**唯一判定点**。
 *
 * ★★★ 2026-10-07 **从 `IslandPanel.slotsFor` 外提**（`migration/100` · B3a）。
 *
 * ## 为什么要外提（纪律 17 的原意：**抽纯函数，而不是改可见性**）
 *
 * 原判据住在 `private inner class IslandPanel` 里，而 **probe 是独立编译模块**
 * ⇒ 它**够不到**。按本项目纪律，此时**先问"能不能抽纯函数"**：
 * 实测该判据**不访问任何实例成员**（连形参 `local: Appearance` 都没用到）
 * ⇒ **可以整段抽成顶层纯函数** ⇒ 于是**一个字都不用改可见性**就让它可断言了。
 *
 * ## 当前模型（2026-09-18 起）：**恒为单槽**
 *
 * 恒返回一个槽位（`currentLine`）。
 * 旧的「`island.experimental_multi_line` 总闸 × `layout.line_mode` 三选」两层模型已**整体删除**
 * —— 自"未唱行一律不显示"后，三选里有两个内容完全相同 ⇒ 合并为 `layout.lines`。
 *
 * ⚠️ **"双行"不在这里实现** —— 双行的第二行是**副歌词（译文）**，
 *   由 `IslandPanel.drawTranslationLine` 单独绘制、单独占高，受 `layout.translation.enabled` 控制。
 *   本函数只负责"**主歌词**占几个槽"（恒为 1）。
 *
 * ## 为什么取当前行
 *
 * 对齐原实现 0.8.9 `ActiveLyrics.select` 的 `!experimental` 分支：`listOfNotNull(snapshot.line)`
 * —— 即**宿主最后推送的那一行**。本插件里"最后推送的那一行"与"位置落在的那一行"在曲中恒等
 * （`lineAt` 的语义），故取 `snap.currentLine` 等价，且无需把宿主回调行单独存一份。
 *
 * ## 返回类型为什么是 `List<LyricLine?>` 而不是 `List<Slot>`
 *
 * `Slot` 是 `IslandWindow.kt` 的 **`private` 顶层类型**，**不适合出现在公开面上**。
 * 本函数只表达"**画哪几行**"（= 列表里是哪些行）；"这一行是不是当前行"
 * 是**渲染侧的包装**，由调用方（`IslandPanel.slotsFor`）补上。
 *
 * @param currentLine 宿主最后推送的那一行；`null` = 没有歌词。
 */
fun currentLineSlots(currentLine: LyricLine?): List<LyricLine?> = listOf(currentLine)

/**
 * 「**行中心 y → 文字基线 y**」—— Java2D 里让文字**视觉居中**的那一步。
 *
 * ★★★ 2026-10-07 **收口**（`migration/103` · 方案 A 的 A1）。
 *
 * ## 为什么要收成一个出口
 *
 * 这条式子此前**散落 6 处**（`IslandWindow.kt` 的 `drawOneLine` /
 * `drawTranslationLine` / `drawNoLyricsNotice` / `drawCoverImage` / `drawExitingMainLine`），
 * ★ **4 处正向 + 2 处逆向**，且**没有任何公开函数** ⇒ 探针断言不了。
 *
 * ★★ 而"正向 4 处 + 逆向 2 处"意味着一条**比"值正确"更强的契约**：
 *   **两者必须【互为反函数】** —— 若只改了正向（比如把 `/2f` 改成别的），
 *   绘制位置会变、而命中测试用的逆运算没变 ⇒
 *   ★ 症状是「**某一行画得出来却点不中**」，**且不报错**。
 *
 * ## 为什么取 `TextLayout` 的 ascent/descent（而不是 `fontMetrics`）
 *
 * 见 `IslandWindow.kt` 里 `drawOneLine` 的 KDoc：字体的**声明** ascent/descent
 * 与 `Graphics2D` 的 `fontMetrics` 可能不同，本项目统一取 `TextLayout` 那一份。
 *
 * @param centerY 该行的**中心** y（由 `lyricFirstCenterY` 等给出）。
 * @param ascent `TextLayout.ascent`。
 * @param descent `TextLayout.descent`。
 * @return 该行的**文字基线** y。
 */
fun lyricBaselineY(centerY: Float, ascent: Float, descent: Float): Float =
    centerY + (ascent - descent) / 2f

/**
 * [lyricBaselineY] 的**逆运算**：由**文字基线 y** 反推该行的**中心 y**。
 *
 * ★ 用途：对已算好的基线做"绕文字自身中心"的变换（缩放 / 退场），
 * 需要把基线换回中心，才能让变换锚点落在文字中心而不是基线。
 *
 * ★★ 与 [lyricBaselineY] **必须互为反函数** —— 探针有专门一条断言钉住这一点。
 */
fun lyricCenterYFromBaseline(baselineY: Float, ascent: Float, descent: Float): Float =
    baselineY - (ascent - descent) / 2f

/**
 * 歌词**文字区**的**可用宽度**（px）—— 左右**各**内缩 [HORIZONTAL_INSET]。
 *
 * ★★★ 2026-10-07 **收口**（`migration/103` · 方案 A 的 A2b）：这条式子此前散落 **3 处**
 * （`IslandWindow.kt` 的 `drawOneLine` / `drawTranslationLine` / `drawNoLyricsNotice`），
 * ★ **无公开函数** ⇒ 探针断言不了。
 *
 * ## 它同时是"溢出判据"的输入
 *
 * 调用方紧接着算 `overflow = layout.advance - availWidth`，
 * 再由 `overflow > SCROLL_MIN_OVERFLOW_PX` 决定**是否水平滚动** ——
 * ★ 所以本值**错一点点**，就会让"该滚的不滚 / 不该滚的滚"（本项目记过的形态）。
 *
 * @param widthPx 面板宽度（px）。
 * @param horizontalInsetPx 左右各留的内缩（传 [HORIZONTAL_INSET]）。
 */
fun lyricTextAreaWidth(widthPx: Int, horizontalInsetPx: Float): Float =
    widthPx - 2f * horizontalInsetPx

/**
 * 歌词**可见文字区左边界**（px）—— 水平滚动与裁剪的起点。
 *
 * ★★★ 2026-10-17（用户报障「改了最大宽度并取消自动宽度，但在高亮到句尾时歌词会移动，
 * 就像宽度不足一样」）新增。
 *
 * ## 为什么必须减去 `areaLeft`
 *
 * `areaLeft` 是**左侧信息区**的宽（封面 + 封面与歌词的间距）——
 * 歌词真正能画的左边界是 `areaLeft + HORIZONTAL_INSET`，不是 `HORIZONTAL_INSET`。
 * 而此前三处绘制都直接用 `lyricTextAreaWidth(width, inset) = width - 2*inset`
 * 当"可用宽" ⇒ **没有扣 `areaLeft`** ⇒ 溢出量与裁剪矩形都偏大一个封面宽。
 *
 * @param areaLeft 文字区左边界（封面/信息区让位之后的 x）。
 * @param horizontalInsetPx 左右内缩（传 [HORIZONTAL_INSET]）。
 */
fun lyricTextRegionLeft(areaLeft: Float, horizontalInsetPx: Float): Float =
    areaLeft + horizontalInsetPx

/**
 * 歌词**可见文字区宽度**（px）—— 溢出判据、跑马灯位移、裁剪矩形共用的那一个数。
 *
 * ## 公式：`面板宽 − 左侧让位 − 2 × 内缩`
 *
 * `[areaLeft + inset, width − inset]` 这段才是歌词真正能显示的区间。
 *
 * ## 与 `lyricTextAreaWidth` 的分工（★ 别混）
 *
 * | 函数 | 含义 | 用途 |
 * |---|---|---|
 * | [lyricTextAreaWidth] | 面板宽 − 2×内缩（**不含左侧让位**） | 保留给"面板级"断言 |
 * | [lyricTextRegionWidth] | 面板宽 − 让位 − 2×内缩 | **歌词绘制/滚动/裁剪** |
 *
 * ★ 旧代码误用前者当后者 ⇒ 开封面时可用宽被高估约一个封面宽，
 *   用户看到的就是「明明设了最大宽度，句尾还是移动/尾部被吃」。
 */
fun lyricTextRegionWidth(widthPx: Int, areaLeft: Float, horizontalInsetPx: Float): Float =
    (widthPx - areaLeft - 2f * horizontalInsetPx).coerceAtLeast(0f)

/**
 * 歌词行的**水平对齐落点**（x）—— `LEFT` / `CENTER` / `RIGHT` 三档。
 *
 * ★★★ 2026-10-08 **收口**（`migration/103` · 方案 A 的 A3）。
 *
 * ## 为什么要收（它此前**已经被收过一次**，但收错了地方）
 *
 * 这条三分支策略原本住在 `IslandWindow` 的 **`private companion object`** 里
 * （`alignBaselineX`）—— ★ **逻辑是对的、也确实只有一个出口**，
 * ★★ **但探针看不见 `private companion`** ⇒ ★ **探针被迫复刻「居中」那一档**
 * （`TranslationBoundaryProbe` 的 `split` 里重写了一遍 `(avail − advance) / 2f`）
 * ⇒ ★ 这正是本项目反复吃亏的「**同族两处实现**」形态。
 * ★ 2026-10-08（A4）：**那一处有意【不改指】** —— 它是**独立模型**
 * （`split = drawX + advance × p`，用来把「译文高亮落后于主歌词」这个主观观察
 * 变成**可证伪的推导**），改指生产函数会把它退化成「用生产验证生产」的**空护栏**。
 * 见 `migration/107` 的牵动面一节。
 *
 * ## ★ 为什么参数是 `String` wire 而不是 `Align`
 *
 * `Align` 是 **`internal enum`** ⇒ ★ 若本函数签名暴露它，会报
 * `'public' function exposes its 'internal' parameter type`
 * —— ★ **与 `migration/101` §3 撞过的同一堵墙**。
 * ⇒ 用 `String` wire + **内部照 `Align.parse` 的语义**（见下）就**不用动 `Align` 的可见性** ✓
 *
 * ## ★★ 与 `Align.parse` 必须**逐字同语义**
 *
 * `Align.parse` 的判据是 `entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: CENTER`
 * ⇒ 本函数照抄三点：**trim** · **忽略大小写** · **未知值回退 CENTER**（**不抛异常**）。
 * ★ 探针有一条断言专门钉「三档 + 未知值回退」这四点。
 *
 * @param widthPx 面板宽（px）。
 * @param advance 该行的 `TextLayout.advance`（文字宽度）。
 * @param alignWire `Align` 的 wire 值（`LEFT` / `CENTER` / `RIGHT`；未知值回退 `CENTER`）。
 * @param areaLeft 文字区左边界。
 * @param horizontalInsetPx 左右内缩（传 [HORIZONTAL_INSET]）。
 */
fun lyricAlignBaselineX(
    widthPx: Int,
    advance: Float,
    alignWire: String,
    areaLeft: Float,
    horizontalInsetPx: Float,
): Float = when (alignWire.trim().uppercase()) {
    "LEFT" -> areaLeft + horizontalInsetPx
    "RIGHT" -> (areaLeft + widthPx - horizontalInsetPx - advance)
        .coerceAtLeast(areaLeft + horizontalInsetPx)
    // CENTER 与**未知值**同一档（= `Align.parse` 的回退行为）
    else -> areaLeft + (widthPx - advance) / 2f
}
