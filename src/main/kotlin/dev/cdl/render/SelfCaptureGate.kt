// SPDX-License-Identifier: AGPL-3.0-only
//
// R5 护栏的**降级链状态机** —— 把 [SelfCaptureReading] 的**单帧判决**变成
// 「弃帧 / 告警 / 退纯色」的**多帧持久化**决策（`migration/31-*` §4.6）。
//
// ## 为什么单帧判决**不够用**（这是本文件存在的全部理由）
//
// 判据本体（[detectSelfCapture]）在**纯自捕获**上有 ≥0.18 裕度，但在**稀释应力态**
// （玻璃把自捕获稀释到 35%）裕度**仅 0.018**（0.768 vs τ1 = 0.75）。
// ⇒ **单帧判对/判错都是掷硬币**。若拿单帧去"退纯色"，用户会在正常看歌词时
//    随机看到背景**闪成纯色再变回来** —— 那比"背景晚更新 100 ms"刺眼得多。
//
// ## 状态机（`migration/31-*` §4.6 的四条原文，逐条对应）
//
// | 条件 | 动作 |
// |---|---|
// | 单帧 `SELF_CAPTURE` / `UNKNOWN` | **弃该帧** —— 沿用上一帧有效背景 |
// | 连续 `K`（3）帧 | **告警**「真透视暂不可用」（日志 + `ui.toast`） |
// | 连续 `K2`（10，≈1 s @10 Hz）帧 | **退纯色玻璃** + **停用 B4 截屏** |
// | 冷启动首帧 `ownFrame == null`（⇒ `UNKNOWN`） | **直接纯色**（**不许"先糊一次"**） |
//
// ## ★★ 铁律（违反即回授循环，`migration/23-*` §3）
//
// > **任何情况下都不得静默把（可能含自己的）糊图当背景。**
//
// 三条推论，逐条落在本文件的实现里：
// ① `UNKNOWN` **与 `SELF_CAPTURE` 同向处理** —— 不是"算不出来就放行"
//    （若 `UNKNOWN` 当 `OK`，冷启动首帧若已是自捕获，护栏会因为"读不到自己"而**失效**）。
// ② 冷启动**不试**：`ownFrame == null` 的首帧直接进 [SelfCaptureGate.Level.PURE_COLOR`，
//    **不先糊一次看看** —— "先糊一次"就是在赌那一次恰好不是自捕获。
// ③ **退纯色是吸收态**：`migration/31-*` §4.6 说"退纯色 + 停用截屏"，
//    而"停用截屏"后**永远拿不到新证据** ⇒ 若允许 `OK` 帧回到 `FULL`，
//    就会出现"已经停止采集、却又声称恢复正常"——**自相矛盾**。
//    ⇒ 恢复**只能靠外部显式复位**（[SelfCaptureGate.reset]），由 L3 的重启路径调用。
//
// ## 为什么是 `class` 而不是 `object`
//
// 状态是要**逐个 L3 实例**持有的（窗口重建、设置切换都会重来一次），
// 而 `object` 会把状态串到下一次会话 —— 本项目已栽过"静态状态跨会话残留"。
// 同时它是**纯 Kotlin、不碰 AWT/Swing** 的 ⇒ 探针可直接构造并断言转移表。
//
// ## 线程
//
// **不内置锁**。约定：**单线程驱动**（采集线程），`migration/31-*` §4.3 已把它
// 排除在 EDT 预算之外。若将来要多线程驱动，加锁点应在**调用方**，
// 而不是在这里埋一把看不见的锁（本项目纪律：能靠结构消除的状态不靠纪律维持）。
//
// @see SelfCaptureReading
// @see SelfCaptureRules
package dev.cdl.render

/**
 * 降级链的**四种档位**（`migration/31-*` §4.6 的状态集合）。
 *
 * 排序即**降级方向**：`FULL` → `FULL_WARNED` → `PURE_COLOR`。
 * [SUSPECT] 与 [FULL] 同级（单帧、无持久证据），只是为了**可观测**才分开命名。
 */
enum class SelfCaptureLevel {
    /** 正常：判据通过 ⇒ 这一帧可以用来更新模糊层。 */
    FULL,

    /**
     * **单帧疑似**（`1 <= streak < K`）—— 已弃帧，但**尚未**告警。
     *
     * 为什么需要这一档（而不是"直接算个计数器"）：本项目纪律要求状态**可观测** ——
     * 告警文案里要写明"已连续 N 帧"，那 N 就必须是**状态的一部分**而不是局部变量。
     */
    SUSPECT,

    /** **已告警**（`streak >= K`）—— 真透视暂不可用，但仍**维持**不清真透视（继续截屏）。 */
    FULL_WARNED,

    /**
     * **退纯色玻璃**（`streak >= K2`）—— 真透视已关闭、**截屏已停用**、**吸收态**。
     *
     * 「吸收」的意思是：进入本档后 `OK` 帧**不再**把它带回 [FULL] ——
     * 因为"停用截屏"后本来就没有新证据（见文件头铁律推论③）。
     */
    PURE_COLOR,
}

/**
 * 状态机的**一次转移结果** —— 调用方据此决定"这一帧画什么、要不要提示用户"。
 *
 * ## 为什么把 `accepted` 与 `level` 分开给（而不是只给一个枚举）
 *
 * 两件事**互相独立**：
 * · `accepted` = **这一帧的像素能不能用**（逐帧决策，`false` ⇒ 沿用上一帧有效背景）
 * · `level`    = **当前处于哪一档**（跨帧状态，决定用户看得到的行为）
 *
 * 例：刚进 [SelfCaptureLevel.FULL_WARNED] 的那一帧 `accepted = false`（弃帧），
 * 而 `level` 已经是 `FULL_WARNED`；若只返回一个枚举，调用方就得**反推**能不能用 ——
 * 那正是本项目栽过的"同一份数据被两个函数各自收敛"的形态。
 *
 * @property accepted 本帧像素**是否可用**（`true` = 可更新模糊层）
 * @property level 转移**之后**的档位
 * @property streak 当前连续"非 OK"帧数（`OK` 帧会把它清 0）
 * @property reading 本帧原始读数（**原样带出** —— 告警文案要用它的 `s1` / `s1hp` /
 *   `reason` 说人话，而不是只说"失败了"）
 */
data class SelfCaptureGateResult(
    val accepted: Boolean,
    val level: SelfCaptureLevel,
    val streak: Int,
    val reading: SelfCaptureReading,
)

/**
 * ★★ **降级链状态机**（R5 护栏的执行侧）。
 *
 * 用法（采集线程）：
 * ```
 * val gate = SelfCaptureGate()                       // 每个 L3 实例一个
 * // 每帧：
 * val r = detectSelfCapture(capPixels, ownPixels, mask, w, h)
 * val g = gate.onReading(r)
 * if (g.accepted) blurLayer.update(capPixels)        // 只有 accepted 才更新
 * else if (g.level == SelfCaptureLevel.PURE_COLOR) renderPureColor()
 * ```
 *
 * ⚠️ **调用方必须把 `accepted == false` 译成"沿用上一帧有效背景"** ——
 *    本类**不持有**模糊层（那是 L3 侧的状态），只负责**判决**。
 *    这样状态机是纯逻辑 ⇒ 探针可以在毫秒级跑完整条链，不必起 AWT。
 */
class SelfCaptureGate(
    private val k: Int = SelfCaptureRules.K,
    private val k2: Int = SelfCaptureRules.K2,
) {

    init {
        // 参数不变量：K2 必须严格大于 K，否则"先告警再退纯色"这条链会在
        // 同一帧内被跳过 —— 变成一个**永不亮**的告警（本项目最忌讳的形态）。
        require(k >= 1) { "K 必须 >= 1（连续 1 帧就告警是允许的），实际 $k" }
        require(k2 > k) { "K2 必须 > K（否则告警永不亮），实际 K2=$k2 K=$k" }
    }

    /** 连续"非 OK"帧数。**唯一**的持久状态。 */
    var streak: Int = 0
        private set

    /** 当前档位。初值 [SelfCaptureLevel.FULL]（还没看过任何一帧 ⇒ 谈不上降级）。 */
    var level: SelfCaptureLevel = SelfCaptureLevel.FULL
        private set

    /** 是否已经因本次连续失败告过一次警（避免每帧重复 toast）。 */
    var warned: Boolean = false
        private set

    /**
     * 是否已经宣告过"退纯色"（**一次性边沿的锁存位**）。
     *
     * 见 [shouldFallbackToPureColor] 的 KDoc —— 没有这个位，退纯色动作会在
     * `PURE_COLOR` 档里**每一帧**被触发一次。
     */
    private var pureColorAnnounced: Boolean = false

    /**
     * 送入一帧的读数，得到转移后的结果。
     *
     * ## 转移表（**逐行可断言**，探针 `SelfCaptureGateProbe` §转移表）
     *
     * | 输入 | 前置档位 | streak | 后置档位 | accepted |
     * |---|---|---|---|---|
     * | `OK` | `FULL` | 0 | `FULL` | `true` |
     * | `OK` | `FULL_WARNED` | 0 | `FULL` | `true` |
     * | `OK` | `PURE_COLOR` | **不变** | `PURE_COLOR` | **`false`** |
     * | 非 `OK` | `FULL` | +1 | `SUSPECT` | `false` |
     * | 非 `OK` | `SUSPECT` | +1 < K2 | `SUSPECT` | `false` |
     * | 非 `OK` | `SUSPECT` | +1 == K | `FULL_WARNED` | `false` |
     * | 非 `OK` | `FULL_WARNED` | +1 < K2 | `FULL_WARNED` | `false` |
     * | 非 `OK` | `FULL_WARNED` | +1 >= K2 | `PURE_COLOR` | `false` |
     *
     * ⚠️ **`OK` 在 `PURE_COLOR` 档被拒**（第 3 行）—— 这正是文件头铁律推论③。
     *    若把它改成回到 `FULL`，就出现"已停采集却声称恢复"的矛盾状态。
     */
    fun onReading(reading: SelfCaptureReading): SelfCaptureGateResult {
        // ── 吸收态：不再接受任何输入（除了显式 [reset]）───────────────────────
        if (level == SelfCaptureLevel.PURE_COLOR) {
            return SelfCaptureGateResult(
                accepted = false,
                level = SelfCaptureLevel.PURE_COLOR,
                streak = streak,
                reading = reading,
            )
        }

        if (reading.decision == SelfCaptureDecision.OK) {
            // `OK` ⇒ 计数器归零并**回满档**。这是"真透视恢复"的唯一途径
            // （在退纯色之前）：一帧可信证据就够 —— 因为 `OK` 的裕度 ≥0.18，
            // 而误判（把正常帧判成自捕获）只会让 streak 涨、不会让 `OK` 假出现
            //（`OK` 要求**两条判据都不亮**，误判方向是"宁枉勿纵"，见 `decideByScores`）。
            streak = 0
            warned = false
            level = SelfCaptureLevel.FULL
            return SelfCaptureGateResult(
                accepted = true,
                level = level,
                streak = streak,
                reading = reading,
            )
        }

        // ── 非 OK（`SELF_CAPTURE` 或 `UNKNOWN`）：弃帧 + 累计 ──────────────────
        // ⚠️ `UNKNOWN` 与 `SELF_CAPTURE` **走同一条路**（文件头铁律推论①）。
        streak += 1
        level = when {
            streak >= k2 -> SelfCaptureLevel.PURE_COLOR
            streak >= k -> SelfCaptureLevel.FULL_WARNED
            else -> SelfCaptureLevel.SUSPECT
        }
        if (level != SelfCaptureLevel.FULL) warned = streak >= k
        return SelfCaptureGateResult(
            accepted = false,
            level = level,
            streak = streak,
            reading = reading,
        )
    }

    /**
     * 判定**是否应当在此刻发出告警** —— 与转移分离，避免"每帧都 toast"。
     *
     * 返回 `true` 的**唯一**条件：本帧刚跨过 `K`（`streak == k`）且此前未告警。
     * 调用方拿 `true` 时执行 `ui.toast("真透视暂不可用")` + 写日志。
     *
     * ## 为什么不做成 `onReading` 的返回值
     *
     * 因为"要不要弹提示"是**用户可见**的决策，而本项目纪律是
     * 「**用户可见的事走 `ui.toast`，日志走数据目录**」——
     * 把两者塞进一个返回值会让状态机耦合到 UI 层（它现在**零 AWT 依赖**，
     * 这正是探针能在毫秒级跑完的原因）。
     */
    fun shouldWarn(result: SelfCaptureGateResult): Boolean =
        result.streak == k && result.level == SelfCaptureLevel.FULL_WARNED

    /**
     * 判定**是否应当在此刻退纯色** —— **一次性边沿**，不是"当前是否在纯色档"。
     *
     * ## ★ 为什么必须是**边沿**而不是**电平**（2026-09-27 实测发现）
     *
     * 初版写成 `result.streak == k2 && level == PURE_COLOR`，**看着是边沿**，
     * 实际是**电平** —— 因为 [SelfCaptureLevel.PURE_COLOR] 是吸收态，
     * `streak` 进去之后**就冻在 `k2` 不动了**（见 [onReading] 的吸收态分支）
     * ⇒ 第 10、11、12、13 … 帧**全都**满足 `streak == k2` ⇒ **每帧都返回 `true`**。
     *
     * 探针第 5.4 段当场抓到了它：`实际=[10, 11, 12, 13] 期望=[10]`。
     *
     * 后果（若没抓住）：调用方每帧执行一次"退纯色"动作 ——
     * 轻则**每帧重建一次纯色渲染状态**（白烧 CPU、还可能每帧分配），
     * 重则**每帧弹一次 toast / 每帧写一行日志**（把 `super-island.log` 刷爆，
     * 而日志刷屏正是本项目纪律明确禁止的形态）。
     *
     * ## 修法：用**独立的锁存位** `pureColorAnnounced`
     *
     * 不去改 `streak` 的语义（它是"连续失败帧数"，冻在 `k2` 是**对的**语义），
     * 而是**新增一个只表达"这次降级是否已经宣告过"的位**。两条理由：
     * ① **不混淆两个概念** —— "失败了多少帧"与"降级动作做了没有"是两件事，
     *    用一个字段兼职表达两者，就是本项目反复栽的"同一份数据被两处各自收敛"。
     * ② **与 [shouldWarn] 的 `warned` 位对称** —— 告警本来就是这么做的
     *    （它靠 `warned` 而不是靠 `streak == k`，所以它**没有**这个 bug）。
     *    本轮实测正好证明：**同一类边界，一处做了锁存、一处没做 ⇒ 没做的那处漏**。
     *
     * 该位由 [onReading] 在**进入** `PURE_COLOR` 那一帧置起，由 [reset] 清掉。
     */
    fun shouldFallbackToPureColor(result: SelfCaptureGateResult): Boolean =
        result.streak == k2 && result.level == SelfCaptureLevel.PURE_COLOR &&
            !pureColorAnnounced && markPureColorAnnounced()

    /** 置位并返回 `true`（**只在第一次**），用于把"已宣告"写进状态。 */
    private fun markPureColorAnnounced(): Boolean {
        if (pureColorAnnounced) return false
        pureColorAnnounced = true
        return true
    }

    /**
     * **显式复位**（回到 [SelfCaptureLevel.FULL]，清空计数与告警标记）。
     *
     * ## 为什么必须存在
     *
     * 因为 [SelfCaptureLevel.PURE_COLOR] 是**吸收态**（文件头铁律推论③）——
     * 若没有复位入口，用户一旦触发过降级，**本次会话内真透视再也回不来**：
     * 他把窗口挪到别的显示器、关掉某个全屏应用之后，本可以恢复，却恢复不了。
     *
     * ## 谁调它
     *
     * **L3 的重启路径**，而不是"每隔几秒自动试一次"。
     * 自动重试会造成"糊一帧 → 退纯色 → 再糊一帧"的**周期性闪烁**，
     * 比"一直纯色"更糟（用户会以为插件在抽搐）。
     * ⇒ **试的对象必须由外部事件驱动**（用户改设置、窗口移动、宿主重载插件）。
     */
    fun reset() {
        streak = 0
        level = SelfCaptureLevel.FULL
        warned = false
        pureColorAnnounced = false
    }
}

// ── 冷启动 ──────────────────────────────────────────────────────────────────

/**
 * ★★ **冷启动首帧的判决**（`migration/31-*` §4.6 ④，铁律推论②）。
 *
 * ```
 * ownFrame == null  ⇒  UNKNOWN  ⇒  直接纯色
 * ```
 *
 * ## 为什么这条要单独抽出来（而不是"交给 `detectSelfCapture` 自然会返回 UNKNOWN"）
 *
 * 因为 `detectSelfCapture` 的 `UNKNOWN` **在静默** —— 它只是返回一个枚举，
 * 而"**首帧必须直接纯色、不许先糊一次**"是一条**行为承诺**，不是"某个函数恰好返回了什么"。
 * 本项目栽过太多次「**声明了** ≠ **实现了**」（BUG-079：注册表让设置项存在，
 * **消费点**才让它生效）⇒ 这条承诺必须有**自己的、可被探针直接断言的入口**。
 *
 * ## `ownFrame == null` 的两种来源，**判决相同**
 *
 * | 来源 | 含义 |
 * |---|---|
 * | 面板**还没渲染过**（`publishOwnFrame` 未跑过） | 冷启动 |
 * | 面板渲染过但**没 armed**（`pixels == null`） | L3 接线漏了开快照 |
 *
 * 两者的共同后果都是"**没有自己的像素证据**"⇒ 都**不得**当"正常帧"放行。
 * 于是本函数**只看"有没有像素证据"**，不看它为什么没有 ——
 * 少一个分支就少一处能和原实现不一致的地方。
 *
 * @param ownPixels `IslandPanel.ownFrame?.pixels`（`null` = 无证据）
 * @return `true` ⇒ 必须**直接画纯色**；`false` ⇒ 可以进正常判据流程
 */
fun mustStartWithPureColor(ownPixels: Any?): Boolean = ownPixels == null

/**
 * 冷启动判决的**读数**（供日志与探针统一口径 —— 不要在两处各写一份字符串）。
 */
fun coldStartReading(): SelfCaptureReading = SelfCaptureReading(
    decision = SelfCaptureDecision.UNKNOWN,
    s1 = null,
    s1hp = null,
    reason = "cold-start",
)
