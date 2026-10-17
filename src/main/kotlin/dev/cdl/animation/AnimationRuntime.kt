// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.animation

import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.pow

/**
 * 缓动函数。
 *
 * ## 为什么只留这四种
 *
 * 缓动的种类可以列几十种（Material 有 16 种、iOS 有 8 种），但**每种都要有自己的取舍理由**，
 * 否则就是在"看起来更花哨"和"更难解释为什么是这个"之间选了前者。
 *
 * ⚠️ 注意：**这不是"少做"的理由**。本插件定位是**高度自由、个性化的桌面歌词定制平台**
 * （用户 2026-09-20 原话），故"只留四种"的正当性在于**每一种的语义都不重叠**，
 * 而不在于"多了会晃"。若将来出现语义确实不同的第五种，**应该加**。
 *
 * 四个值覆盖了过渡的全部基本语义：
 *
 * | 值 | 用途 | 为什么 |
 * | --- | --- | --- |
 * | [LINEAR] | 进度类（**不该用缓动的地方**） | 逐字高亮的进度是**时间本身**，缓动它等于"高亮跑得和歌不一样快" |
 * | [EASE_OUT] | **元素出现**（淡入、滑入） | 开头快、结尾慢 = "**它已经到位了**"的观感；出现类动效的默认 |
 * | [EASE_IN_OUT] | **原地变化**（尺寸、透明度） | 两头慢、中间快 = "**平滑地变了**"，不突兀也不拖沓 |
 * | [OVERSHOOT] | **物理感的落位**（频谱柱回落、菜单弹出） | 略微超过再回来 = "**有重量**"；**只用在短动效上**，长了会晕 |
 *
 * > **纪律：加第五种之前先问"现有四种里哪一个的语义不匹配"。**
 * > 答得出来 → 加（并在这里补一行理由）。答不出来 → 那不是缓动的问题，
 * > 而是这处动效**本身没有明确的语义**，先想清楚它要表达什么。
 */
enum class Easing {
    /** 匀速。**用于"进度"**（时间轴驱动的量），不用于"过渡"。 */
    LINEAR,

    /** 开头快、结尾慢。**元素出现的默认**。 */
    EASE_OUT,

    /** 两头慢、中间快。**原地变化**（尺寸 / 透明度）。 */
    EASE_IN_OUT,

    /** 超过终值再回来。**短动效**才有物理感，长了会晕。 */
    OVERSHOOT,
    ;

    /** 把 `t`（0..1）映射为缓动后的 0..1（[OVERSHOOT] 会略微超过 1）。 */
    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            // 三次方的 ease-out：比二次更"收得住"，又不像四次那样开头猛。
            EASE_OUT -> 1f - (1f - x).pow(3)
            // 标准 smoothstep 的三次版：两端一阶导为 0。
            EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
            // 经典 back-out（s = 1.70158 是"回弹幅度"的标准取值）。
            OVERSHOOT -> {
                val c1 = 1.70158f
                val c3 = c1 + 1f
                1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
            }
        }
    }
}

/**
 * **动效质量档**（`animation.quality`）—— 决定"这台机器要花多少性能换观感"。
 *
 * ## ★ 为什么必须有这个类型（而不是散着比字符串）
 *
 * 这个设置项**承诺了三档各有差异**（见注册表里的选项文案），而此前只有
 * "帧率"与"峰值保持"两处差异 —— 即**文案承诺了它没做到的事**。
 * 把它收成一个类型后，"每一档到底多给什么"**只有一处定义**，
 * 探针可以直接断言，而调用点也不必再各写一遍字符串比较
 * （那正是项目里"改名漏改引用点 → 静默失效"的温床）。
 *
 * ## 三档的实际差异（**这里是唯一权威**）
 *
 * ⚠️ **2026-09-23 更正（`recon/54` §6 记的漂移）**：本表原先写着「档位 → 帧率」，
 * 而帧率**已于 2026-09-21 从 `quality` 拆出**（改由 `animation.frame_rate` 独立承担，
 * 见 [applySettings] 的 KDoc）。留着一张"档位决定帧率"的表，会让人**按错误的预算读代码**
 * （16.7 / 33 ms 而不是真实的 6.9 ms）。故本表**只列"开哪些效果"**，不再出现帧率。
 *
 * | 档位 | 基础过渡 | 歌词行退场 | 频谱峰值保持 |
 * | --- | --- | --- | --- |
 * | [STANDARD] | ✓ | — | — |
 * | [HIGH] | ✓ | ✓ | — |
 * | [ULTRA] | ✓ | ✓ | ✓ |
 *
 * 帧率**不在这里** —— 它由 `animation.frame_rate` 决定，见 [frameIntervalMs] 与
 * [frameIntervalMsFor] 的 KDoc（含"请求值 vs 实得值"的平台限制）。
 *
 * "基础过渡"= 词岛显隐 / 右键菜单 / 封面交叉淡化 / 色彩过渡 / 控件条出现 ——
 * 它们**在任何档位都开**：要么是"状态变化被看见"的必需品，
 * 要么代价极低（一次 alpha 合成）。见 [advancedTransitions] 与 [highCostEffects]。
 */
enum class MotionQuality {
    /** **标准**：只做基础过渡。给"性能敏感"或"不想要额外动效"的场景。**帧率不在这里**（见 `animation.frame_rate`）。 */
    STANDARD,

    /** **高**（出厂默认）：加上歌词行退场。**帧率不在这里**（见 `animation.frame_rate`）。 */
    HIGH,

    /** **极致**：再加上频谱峰值保持（唯一需要**逐帧额外绘制**的效果）。**帧率不在这里**（见 `animation.frame_rate`）。 */
    ULTRA,
    ;

    /**
     * 是否启用**进阶过渡**（歌词行退场）。
     *
     * 为什么歌词行退场算"进阶"：它是**高频**效果（每几秒一次），
     * 且每次都要多画一段文字 —— 在弱机器上这是**稳定可感**的开销，
     * 而不是"偶发一次"。故 [STANDARD] 档不做它。
     */
    val advancedTransitions: Boolean get() = this != STANDARD

    /**
     * 是否启用**高开销效果**（频谱峰值保持）。
     *
     * 它是唯一需要"每频段多存一个数组 + 每帧多一趟绘制"的效果，故只在 [ULTRA] 开。
     */
    val highCostEffects: Boolean get() = this == ULTRA

    companion object {
        /** 出厂默认（与注册表 `animation.quality` 的 `default` 一致）。 */
        val DEFAULT = HIGH

        /** 把设置里的字符串解析成档位。**未知值回退 [DEFAULT]**（不抛）。 */
        fun parse(key: String?): MotionQuality = when {
            key == null -> DEFAULT
            key.equals("STANDARD", ignoreCase = true) -> STANDARD
            key.equals("HIGH", ignoreCase = true) -> HIGH
            key.equals("ULTRA", ignoreCase = true) -> ULTRA
            else -> DEFAULT
        }
    }
}

/**
 * 一个**正在跑的过渡**。
 *
 * ## 为什么用"值 + 起止时刻"而不是"每帧加一个步长"
 *
 * 步进式（`value += delta`）有两个毛病：
 * ① **帧率变了速度就变了** —— 30 fps 与 60 fps 下同一个动画快慢不同；
 * ② **误差累积** —— 浮点反复加会漂，长动画末尾对不上终值。
 *
 * 本类记的是 `startNanos` / `durationNanos` / `from` / `to`，
 * 每帧**由绝对时间重算**进度 → **帧率无关、无累积误差、跳帧也不会走过头**。
 *
 * > 这与项目里"**逐字高亮用绝对时间轴而非逐帧累加**"是同一条纪律：
 * > **凡是"随时间变化的量"，都要能由"当前时刻"直接算出，而不是靠上一帧推。**
 */
class Transition(
    private val from: Float,
    private val to: Float,
    /** 已按 `animation.duration_scale` 折算过的**实际时长**（毫秒）。 */
    val durationMs: Long,
    val easing: Easing,
    private val startNanos: Long,
    /** 每帧回调（`v` 在 `from`..`to` 之间；[Easing.OVERSHOOT] 时会短暂越界）。 */
    private val onUpdate: (Float) -> Unit,
    private val onDone: (() -> Unit)? = null,
) {
    /** 进度（0..1，未缓动）。 */
    private fun rawProgress(nowNanos: Long): Float =
        if (durationMs <= 0L) 1f
        else ((nowNanos - startNanos).toDouble() / (durationMs * 1_000_000.0)).toFloat().coerceIn(0f, 1f)

    /** 推进一帧。返回 `true` = 这一帧之后动画**已结束**。 */
    fun advance(nowNanos: Long): Boolean {
        val p = rawProgress(nowNanos)
        val v = from + (to - from) * easing.apply(p)
        onUpdate(v)
        if (p >= 1f) {
            // ⚠️ **结束时必须精确落到 `to`** —— 缓动函数在 p=1 处理论上就是 1，
            //    但 OVERSHOOT 这类有浮点运算的实现在边界上可能差一个 ulp，
            //    而"停在 0.9999 而不是 1.0"会让"动画结束"与"值到达终值"**不同步**
            //    （表现为最后一点点要等下一帧才对齐，或者干脆一直差一点）。
            onUpdate(to)
            onDone?.invoke()
            return true
        }
        return false
    }
}

/**
 * 动画运行时 —— **本插件所有过渡动画的唯一驱动**。
 *
 * ## 与主帧循环的关系
 *
 * 它**不自己起线程/定时器**，而是由 `IslandWindow` 的既有帧循环每帧调 [tick]：
 *
 * ```
 * onFrameTick() → animator.tick(now) → 返回"还有没有在跑" → 决定下一帧要不要继续
 * ```
 *
 * 这样做的理由：**帧循环只能有一个**。若每个动效各起一个 `javax.swing.Timer`，
 * 就会有 N 个定时器争 EDT，且"什么时候该停"要各自判断 —— 而**停不下来就是常驻烧 CPU**。
 *
 * > 这与项目里"`needsAnimationFrames()` 只认两个理由"是同一条纪律的延伸：
 * > **跑帧这件事必须集中判定**，否则"谁还在动"会散落各处、迟早漏掉一个。
 *
 * ## ★ 为什么用"命名槽位"而不是一个动画列表
 *
 * 每个动画必须声明它占用哪个 **slot**（见 [startIn]）。同一 slot 上**同时只能有一个**
 * 动画 —— 再起一个会**取消**前一个。
 *
 * 这不是为了好看，而是为了修掉一个**必然发生**的缺陷：
 * 「词岛淡出到一半，用户又让它显示」时，两个方向相反的过渡会同时写同一个
 * 透明度变量，表现为**互相抵消 → 画面卡在半透明抖动**。
 * 用"槽位互斥"从**结构上**消除它，而不是靠调用方记得先取消
 * （项目纪律：**能靠结构消除的状态，不靠纪律维持**）。
 *
 * 槽位名集中在 [Slots]，不要在调用点写裸字符串 ——
 * 拼错槽位名**不会报错**，只会让互斥**静默失效**。
 *
 * ## 三条设置如何生效
 *
 * | 设置 | 作用点 |
 * | --- | --- |
 * | `animation.enabled = false` | [startIn] **直接跳到终值**（不留任何过渡） |
 * | `animation.frame_rate` | [frameIntervalMs] —— 决定帧率上限；调用方据此调整 `Timer` 间隔 |
 * | `animation.duration_scale` | [startIn] 里乘到 `durationMs` 上 |
 *
 * **⚠️ 关于"关掉动画"的语义**：它是**真的没有动画**（值立即到终值），
 * 不是"动画变快"。用户关它是因为**嫌开销**，若只是变快则开销照旧、诉求没被满足。
 *
 * ## ★★ 定位（**高于本文件其他一切设计判断**）
 *
 * 用户 2026-09-20 的原话：
 *
 * > **「不是能看歌词的工具啊，是高度自由、个性化的桌面歌词定制平台。」**
 *
 * 故本文件**不是**"保守地少做动画"的辩护词。定位是**定制平台**，意味着
 * 「功能要足 / 要能切 / 默认值要稳」三件事同时成立：
 *
 * | 要求 | 本文件的做法 |
 * | --- | --- |
 * | **功能要足** | 风格（[MotionStyle]）、节奏（[Easing]）、时长（`animation.duration_scale`）三个维度**都能选** |
 * | **要能切** | 每个动效由 [MotionSpec] 声明 → 换风格是**改一处数据**，不是改调用点 |
 * | **默认值要稳** | 默认档不花哨（避免第一印象是"晃"），但用户**随时能开到最大** |
 *
 * > ⚠️ **纪律：不要把"不干扰阅读"当成拒绝做某个动效的理由。**
 * > 它是**默认值的取向**，不是**功能的上限**。用户要的是"我能不能自己调" ——
 * > 答"不该做"是把定位理解错了。正确的答法是"**能，且在设置里能切**"。
 *
 * ## 仍然不做的三件事（理由不是"克制"，而是它们在任何档位下都只是缺陷）
 *
 * ① **逐字高亮的进度不做缓动** —— 它代表时间本身，缓动它等于"高亮跑得和歌不一样快"。
 *   那是**算错了**，不是风格问题。
 * ② **没有任何常驻的 idle 微动** —— 与观感无关，纯粹白烧 CPU（每秒 30–60 次唤醒）。
 * ③ **动画的终值必须等于静态值** —— 否则动画结束时跳一下。那是**错误**，不是风格。
 *
 * > 判断一处动效该不该做，问的是：**"用户能不能选它、能不能关它？"**
 * > 两个都能 → 做。只能"永远开着" → 那才是问题。
 */
class AnimationRuntime(
    /**
     * **时钟**。默认取 [System.nanoTime]；探针可注入假时钟。
     *
     * ## 为什么必须有这个接缝（这是 2026-09-20 探针跑红后补的）
     *
     * 最初 [startIn] 直接调 `System.nanoTime()` 记起点，而 [tick] 允许调用方
     * 传入时刻 —— 两者**量纲相同但基准不同**（一个是真的当前时刻，
     * 一个是调用方编的）。探针喂 `0 / 50ms / 100ms` 时，
     * `now - start` **恒为负** → 进度恒被夹到 0 → 每个动画都"推不动"。
     *
     * 后果不是"探针报错"，而是**探针在断言一个恒为 `from` 的值**：
     * 它看起来在验证，实际上**永远验证不到插值逻辑**。
     * 项目纪律原话：「**不可证伪的检查等于没有检查** —— 构造不出让它失败的输入，
     * 先修检查」。这里"修检查"的唯一办法就是**给时间一个接缝**。
     *
     * ## 为什么不让探针改用真实时间（`Thread.sleep`）
     *
     * 那会让探针**变慢且不稳定**（睡眠精度、CI 负载都会影响结果），
     * 而"动画在 100ms 时走到一半"这种断言**必须精确**才有意义。
     * 注入时钟是唯一能同时做到"快"与"准"的办法。
     */
    private val clock: () -> Long = System::nanoTime,
) {

    /**
     * 在跑的过渡，按槽位索引。用 `LinkedHashMap` 而不是 `HashMap`：
     * [tick] 按插入顺序推进，**先起的先结束** —— 顺序稳定让"同一帧里
     * 谁先写值"可预测（两个动画写同一个目标变量时才看得出差别）。
     */
    private val running = LinkedHashMap<String, Transition>(4)

    /**
     * 帧间隔（毫秒）—— **交给帧驱动（`javax.swing.Timer`）的请求值**。
     *
     * ## ⚠️ 2026-09-23 更正（`recon/54` §6 记的漂移，本条是第 ② 处）
     *
     * **原记**：「由 `animation.quality` 决定：`STANDARD` → 33（≈30 fps）· 其余 → 16（≈60 fps）」。
     *
     * **实为**：帧率**已于 2026-09-21 拆成独立设置项** `animation.frame_rate`，
     * 取值 `AUTO / 60 / 90 / 120 / 144`，换算见 [frameIntervalMsFor]。
     * `animation.quality` 现在**只管"开哪些效果"**（见 [MotionQuality]）。
     *
     * ## ★★ 为什么是"请求值"而不是"实得值"（这是本字段最要紧的一句话）
     *
     * 帧驱动建在 `javax.swing.Timer` 上，而它继承 Windows 的**系统定时器量子**
     * （默认 **15.625 ms**）—— 实得间隔被**向上取整到量子的整数倍**。
     * 于是"请求 6 ms"与"实得 6 ms"**是两件事**：实测请求 6/8/11 ms 三者**实得都是 15.5 ms**。
     *
     * ⇒ 故本值必须由 [frameIntervalMsFor] **按量子吸附**给出，而不是裸的 `1000/hz`
     * （裸算会让 60 Hz 落在量子边界**上方 0.375 ms**，从而整整多付一个量子 ⇒ 32 Hz）。
     */
    var frameIntervalMs: Long = 33L
        private set

    /** 是否允许跑动画（`animation.enabled`）。**关掉时 [startIn] 会立即落终值。** */
    var enabled: Boolean = true
        private set

    /**
     * 是否开启**高开销效果**（`animation.quality = ULTRA`）。
     *
     * ## 为什么需要这个字段（它修掉了一处**承诺与实现不一致**）
     *
     * `animation.quality` 的 KDoc 写着「极致（60 fps，**全部效果**）」，
     * 但在此之前 `ULTRA` 与 `HIGH` 的**行为完全一样**（都只是 16ms）——
     * 即那个档位**什么也没多给**，是一个"看起来有三级、实际只有两级"的设置。
     *
     * 现在它有了第一个真正的增量：**频谱峰值保持**（见 `SpectralPainter.updatePeaks`）——
     * 那是"每频段多存一个数组 + 每帧多一趟绘制"的效果，
     * 正是用户要的「**对 CPU/GPU 性能高的动画效果**」，也正该只在最高档开。
     *
     * ## 为什么不做成独立设置项
     *
     * 因为它与"帧率"是**同一类东西**（都是"我要花多少机器性能换观感"）。
     * 拆成两个开关会让用户面对"60fps 但不开峰值"这种**没有意义**的组合。
     */
    var highCostEffects: Boolean = false
        private set

    /**
     * ★ **`animation.enabled = false` 必须让"重效果"也停下**（2026-09-20 修）。
     *
     * ## 原记
     *
     * `highCostEffects` 直接取 `quality == ULTRA` —— **不看 `enabled`**。
     *
     * ## 实为（逐项核对承诺时查出）
     *
     * `animation.enabled` 的文案承诺「关闭后**所有**过渡动画立即消失，回到无动画的行为
     * （不是变快，**是完全没有**）」。而"关掉动画 + 极致档"时
     * **频谱峰值保持照旧在动** —— 用户会说"关了没用"。
     *
     * ## 判据
     *
     * 峰值保持虽然**不是"过渡"**，但它**是"动"**，而用户关这个开关的动机是
     * **"我不要画面上有东西在动 / 省开销"**。故它必须一起停。
     * 这与 `startIn` 在 `enabled = false` 时"直接落终值"是同一条语义：
     * **关掉就是没有，不是变快、也不是"少一点"**。
     */
    val effectiveHighCostEffects: Boolean get() = enabled && highCostEffects

    /** 是否启用**进阶过渡**（歌词行退场）。**同样受 `enabled` 约束**（理由见上）。 */
    val effectiveAdvancedTransitions: Boolean get() = enabled && quality.advancedTransitions

    /**
     * 当前质量档（`animation.quality`）。**"每档多给什么"由 [MotionQuality] 唯一定义。**
     *
     * 调用点一律读它（或它派生的 [advancedTransitions] / [highCostEffects]），
     * **不要自己比字符串** —— 那会造出 N 份"哪一档开什么"的判断，迟早不一致。
     */
    var quality: MotionQuality = MotionQuality.DEFAULT
        private set

    /** 是否启用**进阶过渡**（歌词行退场）。见 [MotionQuality.advancedTransitions]。 */
    val advancedTransitions: Boolean get() = quality.advancedTransitions

    /**
     * 时长倍率（`animation.duration_scale` 的**原始百分比**，见 [MIN_DURATION_SCALE_PERCENT] /
     * [MAX_DURATION_SCALE_PERCENT]）。
     *
     * ⚠️ **2026-09-23 放宽：上限 `200` → `1000`**。
     * 原上限 200% 是"观感调优"的取值，而用户要的是**把动效放慢 10 倍逐个检查**
     * （调试 / 验收用途）—— 那是一个**正当且必须支持**的档位，不能靠"文案没写"来拒绝。
     *
     * ⚠️ 存**百分比整数**而不是浮点倍率：折算走 [scaledTransitionMs] 的整数运算，
     * 于是"1000% ⇒ 恰好 10 倍"是**逐毫秒精确**的，不受浮点舍入影响。
     */
    private var durationScalePercent: Int = 100

    /**
     * 应用设置。**调用方在设置变更时调它**（与"现读"约定一致：
     * 每次变更推一次，而不是每帧读设置）。
     *
     * ## 帧率从哪来（2026-09-21 变更）
     *
     * **原实现**：帧间隔由 `animation.quality` 决定（`STANDARD` → 33 ms，其余 16 ms），
     * 即**只有 30 / 60 两档，且与"效果数量"绑死在同一个设置项上**。
     *
     * 用户原话：
     * > 「**当前播放器播放歌曲动画能否帧率适配屏幕刷新率？我发现多数人的屏幕刷新率在 100 以上，
     * >   动画的刷新率不能太低。保持现有帧率档位，新增一个适应 60 / 90 / 120 / 144 Hz
     * >   不同刷新率的档位。**」
     *
     * 现在帧率由**独立的** `animation.frame_rate` 决定，`quality` 只管"开哪些效果"。
     * 两者分开的理由：它们是**正交**的两件事 —— 想看全部效果但屏幕只有 60 Hz，
     * 或想省 CPU 但屏幕是 144 Hz，都是合理组合，绑在一起就无法表达。
     *
     * @param frameRate `animation.frame_rate` 的原始值：`AUTO` 或 `"60"/"90"/"120"/"144"`。
     * @param quantumMs **平台定时器量子**（毫秒）。见 [frameIntervalMsFor]。
     *   默认 [Double.NaN] = **自动测量**（见 [detectTimerQuantumMs]）——
     *   探针可注入假值以断言"不同量子下的请求间隔"。
     */
    fun applySettings(
        enabled: Boolean,
        quality: String,
        durationScalePercent: Int,
        frameRate: String = FRAME_RATE_AUTO,
        quantumMs: Double = Double.NaN,
    ) {
        this.enabled = enabled
        this.frameIntervalMs = frameIntervalFor(frameRate, quantumMs)
        this.durationScalePercent = durationScalePercent.coerceIn(
            MIN_DURATION_SCALE_PERCENT, MAX_DURATION_SCALE_PERCENT,
        )
        // 高开销效果只在最高档开（见 [highCostEffects] 的 KDoc）。
        this.quality = MotionQuality.parse(quality)
        this.highCostEffects = this.quality.highCostEffects
        // ⚠️ 关掉动画时**立即把在跑的动画全部落到终值** ——
        //    否则"关掉开关"后已有的动画还会继续跑完，用户会觉得"开关没生效"。
        if (!enabled) finishAll()
    }

    /**
     * 把 `animation.frame_rate` 的取值折算成**请求间隔（毫秒）**。
     *
     * `AUTO` → 读显示器当前刷新率（[detectRefreshRateHz]）。
     *
     * ## 取不到刷新率时回退 60 Hz（而不是 30 或 144）
     *
     * `displayMode.refreshRate` **允许返回 0**（API 契约里就是"0 = 未知"），
     * 且远程桌面 / 虚拟机 / 无头环境上很常见。回退值的取舍：
     * · 回退 **30** 会让高刷屏用户觉得"明明是自动却更卡了"；
     * · 回退 **144** 会在低配机上白烧 CPU（而用户选 AUTO 的意图是"省心"，不是"最激进"）；
     * · **60 是所有现代显示器的最大公约数** —— 最坏情况下只是回到旧行为。
     *
     * ## 为什么夹在 30..240
     *
     * 低于 30 帧的动画是"幻灯片"，没有产品意义（用户要"降低开销"应该去关动画总开关）；
     * 上限 240 是防御性取值 —— 刷新率字段异常（如某些驱动返回 1000）时
     * 不至于把帧间隔算成 1 ms 而空转 EDT。
     *
     * ## ★★ 2026-09-23：本函数不再裸算 `1000/hz` —— 改为**按量子吸附**
     *
     * ### 缺陷（`recon/54` §7.3.1 ③ 记的"唯一一处改一行就能挽回 2 倍"）
     *
     * 原实现是 `(1000L / effective)`。对 **60 Hz** 它给出 **16 ms**，而真周期是 16.667 ms ——
     * 于是请求值**只比平台量子（15.625 ms）高 0.375 ms**，被**向上取整到 2 个量子**
     * （31.25 ms）⇒ 实得 **32 Hz**，而标签写着 60。
     *
     * **实测（`FramePacingProbe` 同一台机器、同一轮）**：
     * | 请求 | 实得 p50 | 实得 | 相对标签 |
     * |---|---|---|---|
     * | 16 ms（改前 60 Hz 档） | **31.02 ms** | 32 Hz | −47% ✗ |
     * | 15 ms（改后 60 Hz 档） | **15.87 ms** | 63 Hz | +5% ✓ |
     *
     * ⇒ 这条把"**选最低的档反而帧率只有一半**"这个**反直觉的锯齿映射**修掉了。
     *
     * ### 修法：把请求吸附到"不超过目标周期的最大量子整数倍"
     *
     * ```
     * 目标周期 T = 1000 / hz
     * 若 T < 量子 q → 请求值无论取多少，实得都是 1 个量子 ⇒ **保持原值**（零变化）
     * 否则         → 请求 = min(原值, ⌊⌊T/q⌋ × q⌋)
     * ```
     *
     * 三个性质（都由 `AnimationCalibrationProbe` 断言）：
     * ① **单调**：标定帧率越高，实得帧率**不得越低**（改前的映射违反它：60→32 而 144→64）；
     * ② **精确平台零变化**：`q ≤ 1 ms`（已调 `timeBeginPeriod(1)` 或非 Windows）时，
     *    上式恒等于原 `1000/hz` —— 即**在精确平台上本改动是空操作**；
     * ③ **不再多付量子**：请求值不会落在量子边界上方 —— 那是"整整多一个量子"的唯一来源。
     *
     * ### ⚠️ 它**不能**让 90 / 120 / 144 档变成字面值
     *
     * 在 q = 15.625 ms 的机器上，可达的周期只有 15.625 / 31.25 / 46.875 … ⇒
     * 90/120/144 三档的实得**都是 64 Hz**，与标签不符。这不是本函数能修的 ——
     * 要真正达到 144 Hz 只能**不进入阻塞等待**（纯自旋，代价是占满一个核）
     * 或**提升进程定时器精度**（`timeBeginPeriod(1)`，进程级副作用）。
     * 两条都**不在本函数职责内**，已在 `recon/54` §7.3 记为待决项。
     */
    private fun frameIntervalFor(frameRate: String, quantumMs: Double = Double.NaN): Long {
        // ★ 2026-09-26：`refresh` 现在**两种档位都要用** —— `AUTO` 拿它当目标帧率，
        //   而非 AUTO 档也要拿它把请求**对齐到刷新周期的整数倍**（否则 judder，
        //   见 [frameIntervalMsForRefresh] 的 KDoc 与实测表）。故提到外面读一次。
        val refresh = detectRefreshRateHz()
        val hz = if (frameRate == FRAME_RATE_AUTO) {
            refresh
        } else {
            frameRate.trim().toIntOrNull() ?: 0
        }
        val q = if (quantumMs.isNaN()) detectTimerQuantumMs() else quantumMs
        return frameIntervalMsForRefresh(hz, refresh, q)
    }

    /**
     * 读主显示器的当前刷新率（Hz）；**取不到返回 0**（调用方负责回退）。
     *
     * 用 `screenDevices[0]`（主屏）而不是"词岛当前所在的那块屏"：
     * 帧率是**全局的帧驱动节拍**，而词岛可以在多屏之间被拖动 ——
     * 若跟随所在屏，拖动到另一块屏时帧率会**突变**，观感比"统一按主屏"更差，
     * 且需要监听屏幕切换事件。这是有意的取舍（见本节 KDoc）。
     *
     * 全程 `runCatching`：无头环境 / 驱动异常不应让设置应用失败。
     */
    private fun detectRefreshRateHz(): Int = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .firstOrNull()
            ?.displayMode
            ?.refreshRate
            ?: 0
    }.getOrDefault(0)

    /**
     * 起一个过渡，占用槽位 [slot]。返回 `true` = **真的在跑**
     * （调用方据此决定要不要唤醒帧循环）。
     *
     * `durationMs <= 0`、总开关关闭、**或 `from` 与 `to` 已经几乎相等**时：
     * **同步落终值并返回 false**（不留任何帧）。
     *
     * ## 为什么"from ≈ to"要短路（这不是微优化）
     *
     * 它消除的是**不必要的帧**。每一帧都会让帧循环多醒一次
     * （`needsAnimationFrames` 返回 true → `startFrameDriver` → EDT 上一次
     * 完整的"取快照 + 判定 + 绘制"）。"淡入一个本来就是不透明的窗口"
     * 这类调用在设置变更路径上很常见（订阅回调不区分"哪个键变了"，
     * 每次变更都会走一遍 apply*），若每次都起一个空动画，
     * **改任何设置都会白跑 200 ms 的帧**。
     *
     * ## 同一槽位上的旧过渡会被**取消**（不触发它的 `onDone`）
     *
     * 取消时**不**调 `onDone` 是刻意的：`onDone` 的语义是"这个过渡正常走完了"，
     * 调用方常在里面做收尾（如"淡出完成 → 真正隐藏窗口"）。
     * 若取消也触发它，就会出现"淡出被打断却仍然把窗口藏了"这种反向后果。
     * 需要"被打断也要收尾"的调用方应显式处理（见 `IslandWindow.syncFade`）。
     *
     * @return 是否真的起了帧（`false` = 已同步落终值）
     */
    fun startIn(
        slot: String,
        from: Float,
        to: Float,
        durationMs: Long,
        easing: Easing,
        onUpdate: (Float) -> Unit,
        onDone: (() -> Unit)? = null,
    ): Boolean {
        // ⚠️ 这一句**必须在短路判定之前**（这是变异测试抓出来的，别顺手挪到下面）。
        //
        // 直觉上它只服务于"替换"（而 `LinkedHashMap` 的 `put` 本来就会覆盖同键的值），
        // 故看起来可以删 —— **删了也不会让"替换"出错**。
        //
        // 但它真正的职责是：**短路路径也要把旧动画摘掉**。少了它会出现：
        // ```
        // 槽位上有个 500ms 过渡在跑（值 0.2）
        //   → 用户关掉动画开关 / 起一个零时长过渡
        //   → 下面的 onUpdate(to) 把值落成终值   ✓ 看起来对
        //   → 但旧过渡**还在表里**，下一帧把值覆盖回去   ✗
        // ```
        // 表现为「**关掉动画之后画面还在动**」—— 而用户关它正是因为嫌开销，
        // 等于开关没生效。`AnimationProbe` §4 有专门一组断言钉这条路径。
        running.remove(slot)
        // ── 折算：**唯一落点是公开纯函数** [scaledTransitionMs] ──────────────────
        //
        // ⚠️ 2026-09-23：这里原先就地写 `(durationMs * durationScale).toLong()
        //    .coerceAtMost(MAX_TRANSITION_MS)`。抽出去的**两个**理由：
        //    ① 用户第 ⑤ 条要"放慢 10x 检查"，它的可验证形式是
        //       「**每条动效的实际时长 == 基准 × 10**」—— 那是对 `Motions.ALL` 全集的断言，
        //       只有把折算做成纯函数才能直接断言（否则只能"跑一遍看它何时结束"）；
        //    ② 软上限现在**分档**（≤200% 保持旧行为、>200% 不夹），
        //       分档规则必须只有一处定义，否则"滑杆拖到 1000 却被夹回 200"会复发。
        val scaled = scaledTransitionMs(durationMs, durationScalePercent)
        if (!enabled || scaled <= 0L || nearlyEqual(from, to)) {
            onUpdate(to)
            onDone?.invoke()
            return false
        }
        running[slot] = Transition(
            from = from,
            to = to,
            durationMs = scaled,
            easing = easing,
            startNanos = clock(),
            onUpdate = onUpdate,
            onDone = onDone,
        )
        return true
    }

    /**
     * 推进所有动画。返回 `true` = **还有动画在跑**（帧循环据此决定下一帧）。
     *
     * ## 为什么遍历的是**快照**
     *
     * `advance` 会调 `onDone`，而 `onDone` 里**可能又起一个新动画**
     * （典型：淡出完成 → 隐藏窗口 → 顺带起一个提示动画）。
     * 直接遍历 `running` 会在这种情形下抛 `ConcurrentModificationException`
     * —— 而且只在"某条特定路径"上抛，属于最难查的一类。
     */
    fun tick(nowNanos: Long = clock()): Boolean {
        if (running.isEmpty()) return false
        val snapshot = running.entries.toList()
        for ((slot, t) in snapshot) {
            if (t.advance(nowNanos)) {
                // ⚠️ 只有"槽位里还是**同一个**过渡"时才移除 ——
                //    `onDone` 可能已经在同一个槽位上起了新的（见上）。
                //    不加这个身份判断会把刚起的新动画一并删掉，
                //    表现为"衔接动画被吃掉一帧"甚至完全不出现。
                if (running[slot] === t) running.remove(slot)
            }
        }
        return running.isNotEmpty()
    }

    /** 有没有在跑的动画。**帧循环的判据之一**（见 `IslandWindow.needsAnimationFrames`）。 */
    fun hasRunning(): Boolean = running.isNotEmpty()

    /** 某个槽位上有没有动画在跑。 */
    fun isRunning(slot: String): Boolean = running.containsKey(slot)

    /** 把在跑的动画**立刻落到终值**（关开关、或窗口即将隐藏时用）。 */
    private fun finishAll() {
        // 先快照再清空：`advance` 的 onDone 里可能又 start 新动画。
        val snapshot = running.values.toList()
        running.clear()
        snapshot.forEach { it.advance(Long.MAX_VALUE) }
    }

    /** 丢弃全部动画，**不触发 onUpdate/onDone**（窗口销毁时用）。 */
    fun clear() = running.clear()
}

/**
 * **由 [AnimationRuntime] 排他调度的槽位名**。不要在调用点写裸字符串。
 *
 * ## ⚠️ 这里只列"归 AnimationRuntime 调度"的动效
 *
 * 不是所有动效都归它调度 —— 有的由**自带定时器的子系统**推进：
 *
 * | 动效 | 谁推进 | 为什么 |
 * | --- | --- | --- |
 * | 控件条出现 | `HoverState` 的定时器 | 状态机已持有"展开时刻"，见其 KDoc |
 * | 菜单淡入 | `IslandMenuSkin` 自己的定时器 | 菜单是独立原生窗，与播放态无关 |
 *
 * 那些动效的 `MotionSpec.slot` 是 `null`，"同时只能有一个"由各自子系统保证
 * （如 `IslandMenuSkin.menuFadeOwner`）。**把它们也列在这里会误导** ——
 * 会让人以为 AnimationRuntime 已经替它们排他了。
 *
 * ## 为什么必须集中
 *
 * 槽位名是**按字面量比较**的（`LinkedHashMap` 的键）。拼错一个字母
 * **不会报错、不会告警**，只会让"同一槽位互斥"**静默失效** ——
 * 表现是"两个动画互相打架"，而根因（少了一个字母）离现场很远。
 *
 * > 项目里同类事故已发生 3 次（设置项改名漏改引用点，`X in changedKeys` 恒 false）。
 * > 故这里的纪律是：**槽位名只在 [Slots] 里出现一次**，调用点一律用常量。
 */
object Slots {
    /** 词岛整体淡入淡出（`IslandWindow` 的显隐）。 */
    const val ISLAND_FADE = "island.fade"

    /** 封面交叉淡化（换曲 / 新图到达）。 */
    const val COVER_CROSSFADE = "cover.crossfade"

    /** 歌词行切换时**旧行的退场**。 */
    const val LYRIC_EXIT = "lyric.exit"

    /**
     * 歌词行切换时**新行的入场**（2026-09-28 用户要求）。
     *
     * ## 它是 `LYRIC_EXIT` 的镜像，但**不是同一个槽位**
     *
     * 两者**同时**在跑（换行那一刻，旧行开始退场、新行同时开始入场），
     * 故必须是**两个槽位** —— 共用一个槽位会让 `AnimationRuntime`（"同槽只留一个"）
     * 把先起的那条**就地取消**，表现是"只有一个方向有动画"。
     *
     * ## 为什么原先**有意不做**入场（原记，现被用户推翻）
     *
     * 见 [Motions.LYRIC_ENTER] 的 KDoc —— 那里完整记录了"原记 X，实为 Y"。
     */
    const val LYRIC_ENTER = "lyric.enter"
}

/**
 * **动效风格** —— 同一处状态变化可以换一种"演法"。
 *
 * ## 为什么需要它（而不是"每处动效各写一段"）
 *
 * 本插件定位是**高度自由、个性化的桌面歌词定制平台**（用户 2026-09-20 原话），
 * 故"用户能不能**换一种**动画"和"能不能关掉"同样重要。
 *
 * 把"一处动效"拆成**两个正交的维度**之后，"换风格"就不再是改代码：
 *
 * | 维度 | 回答的问题 | 载体 |
 * | --- | --- | --- |
 * | **节奏** | 什么时候快、什么时候慢 | [Easing] + `MotionSpec.durationMs` |
 * | **风格** | 哪些通道动、动多少 | 本枚举 |
 *
 * ## 三个 `*At` 是**纯函数**（这是"易拓展"的关键）
 *
 * 输入 `t`（**已缓动**的进度 0..1），输出通道值。没有状态、没有副作用、不碰 Swing ——
 * 故：① 探针可以直接喂值断言；② 加一种新风格只需在这里加一个 `when` 分支，
 * **不需要动任何调用点**。
 *
 * > **纪律：风格只做"通道配比"，不重做"时间曲线"。**
 * > 缓动已经决定了整体快慢；风格若再叠一层自己的时间曲线，两处节奏会打架
 * > （表现为"看着不对劲但说不出哪里不对"）。故三个 `*At` 里只允许出现
 * > "对 `t` 的线性/幂次映射"，不允许出现第二套缓动。
 *
 * ## 不透明度**不在**这里
 *
 * 三处动效的淡入淡出都用**原始进度** `t` 本身（不做风格化）。
 * 故 [alphaAt] 不存在 —— 调用方直接用 `t`。少一个恒等函数就少一处将来被误改的地方。
 */
enum class MotionStyle {
    /** **纯淡入淡出**。最保守，也最不容易出错。 */
    FADE,

    /** **从边缘滑入 + 淡入**。适合"从某条边挤出来"的元素（如控件条）。 */
    SLIDE,

    /** **轻微缩放弹出 + 淡入**。适合"弹出来"的元素。 */
    POP,
    ;

    /**
     * 垂直偏移（像素，**正 = 向下**）。`distance` = 起始偏移量；进度到 1 时归零。
     *
     * ⚠️ 约定"正 = 向下"而不是"正 = 远离边缘"：让本函数**与方向无关**，
     * 由调用方按元素所在边决定 `distance` 的符号（`controls.position = TOP` 时传负）。
     * 这样"上下两条边"共用同一份数学，不会出现"上半部分对、下半部分反了"。
     */
    fun offsetYAt(t: Float, distance: Float): Float = when (this) {
        FADE -> 0f
        SLIDE -> distance * (1f - t)
        POP -> 0f
    }

    /**
     * 缩放系数（1 = 原尺寸）。**调用方绕元素自身中心缩放**。
     *
     * [POP] 取 `0.94 → 1`：再小就像"从远处飞过来"，对桌面悬浮件太重；
     * 再大则看不出在缩放（等于白做）。
     */
    fun scaleAt(t: Float): Float = when (this) {
        FADE -> 1f
        SLIDE -> 1f
        POP -> POP_SCALE_FROM + (1f - POP_SCALE_FROM) * t
    }

    companion object {
        /** [POP] 的起始缩放。见 [scaleAt] 的取值理由。 */
        const val POP_SCALE_FROM = 0.94f

        /**
         * **出厂默认风格**。取 [SLIDE]。
         *
         * 理由：三处里最适合"出场"的是控件条，而控件条**从边上挤出来**比"原地浮现"
         * 更能说明"它是从哪儿来的"（见 [MotionSpec] 的 `followsGlobalStyle`）。
         * 而 [FADE] 是"什么都不额外做"，作为默认会让人以为没实现。
         */
        val DEFAULT = SLIDE

        /** 把设置里的字符串解析成风格。**未知值回退 [DEFAULT]**（不抛）。 */
        fun parse(key: String?): MotionStyle =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * **一处动效的完整声明**。
 *
 * ## 为什么把"时长 / 缓动 / 风格 / 距离"收成一份数据
 *
 * 散在调用点的话，"换风格"要改 N 处、且**没有一处能回答"我们一共有哪些动效"**。
 * 收成数据后：
 *
 * - 新增一处动效 = 在 [Motions] 加一条 + 在消费点调 `startIn`（**三步，见 [Motions] 的 KDoc**）
 * - 换风格 = 改 [style] 一行（或让用户通过 `animation.style` 换，见 [followsGlobalStyle]）
 * - 探针可以**遍历** [Motions] 断言"每条都自洽"（槽位不重复、时长在合理区间……）
 *
 * @param slot 占用的槽位（见 [Slots]）。**同一槽位同时只能有一个过渡在跑。**
 *   `null` = 该动效**不归 `AnimationRuntime` 调度** —— 由**自带定时器的子系统**
 *   推进进度（如控件条由 `HoverState` 的定时器推进，见其 KDoc 关于
 *   "动画进度放在状态机里"的论证）。那种情形下**不要**再挂一个驱动：
 *   两个驱动写同一个进度会互相打架。
 * @param durationMs 基准时长（毫秒）。会被 `animation.duration_scale` 折算。
 * @param easing 节奏。
 * @param style 出厂风格。
 * @param followsGlobalStyle 是否**跟随** `animation.style` 设置。
 *   `false` = 固定用 [style] —— 用于"换掉会不合理"的地方（理由写在 [Motions] 里）。
 * @param distancePx [MotionStyle.SLIDE] 的起始偏移量（像素，正 = 向下）。
 *   其它风格忽略它。**符号由调用方按元素所在边决定**（见 [MotionStyle.offsetYAt]）。
 * @param distanceFromLayout `true` = [distancePx] **被忽略**，距离由**绘制侧按布局算**。
 *   用于"距离本身随字号/行高变"的动效 —— 例如**歌词行退场要上移一个行高**，
 *   那个值只有绘制时拿得到，写死像素会在用户改字号后错位。
 *   ⚠️ 有这个标记，探针才能区分"**有意**不给距离"与"声明了 SLIDE 却忘了给距离"
 *   （后者会让滑入**静默退化成淡入**）。
 */
data class MotionSpec(
    val slot: String? = null,
    val durationMs: Long,
    val easing: Easing,
    val style: MotionStyle,
    val followsGlobalStyle: Boolean = true,
    val distancePx: Float = 0f,
    val distanceFromLayout: Boolean = false,
    /**
     * **起始缩放**（1f = 不缩放），与 [MotionStyle.scaleAt] **相乘**。
     *
     * ## 为什么需要它（2026-09-21 新增，用户要求"UX 至上"）
     *
     * [MotionStyle] 只有三档，而 `POP` 的起始缩放是**写死的 0.94** ——
     * 那是按"弹出来的控件条"标定的幅度。词岛是**贴在桌面上的大块悬浮件**，
     * 用 6% 缩放会像"从屏幕里弹出来"，这正是本项目 KDoc 一直反对的空间隐喻。
     *
     * 但"**完全不动**"又走向另一个极端：纯透明度变化**没有重量感** ——
     * 动效调研（`recon/46` §3.1）的结论是「进入用**减速曲线 + 微缩放 + 淡入**」，
     * 并给出「贴屏悬浮窗的**不浮夸上限是 4%**」。
     *
     * 故引入本字段：让**具体幅度**与**风格族**解耦 ——
     * 词岛可以要"FADE 的语义 + 3% 的缩放"，而不必被迫套用 POP 的 6%。
     *
     * ## 它不破坏"不跟随全局风格"
     *
     * [followsGlobalStyle] 管的是"**用户的 `animation.style` 能否改这里的演法**"；
     * 本字段管的是"**出厂幅度是多少**"。两者正交 ——
     * 词岛仍然**不跟随**用户风格（错误的隐喻不会被用户误选出来），
     * 但出厂就带一点克制的落位感。
     */
    val scaleFrom: Float = 1f,
)

/**
 * `animation.frame_rate` 的**「自动」档取值** —— 跟随显示器刷新率。
 *
 * 与 `SettingRegistry` 里该项的 `options` 值**必须逐字一致**（`RegistryProbe`
 * 有一致性断言；历史上 `SettingCoupling` 就因为"改名漏改一处"而静默失效过）。
 */
internal const val FRAME_RATE_AUTO = "AUTO"

/** 取不到刷新率时的回退帧率（Hz）。见 `AnimationRuntime.frameIntervalFor` 的 KDoc。 */
internal const val DEFAULT_FRAME_HZ = 60

/** 可用帧率的下限（Hz）。低于它的动画是"幻灯片"，没有产品意义。 */
internal const val MIN_FRAME_HZ = 30

/** 可用帧率的上限（Hz）。防御刷新率字段异常时把帧间隔算成 1 ms。 */
internal const val MAX_FRAME_HZ = 240

/**
 * 单条过渡的**时长软上限（毫秒）** —— `duration_scale` 折算**之后**再夹一次。
 *
 * ## 为什么要有它（2026-09-21，来自动效调研）
 *
 * `animation.duration_scale` 允许到 **200%**，而基准时长最长的是封面交叉淡化（260 ms）
 * → 200% 时是 **520 ms**。调研（`recon/46` §4 反例 2）给出了明确判据：
 * 「**单条动效 > 500 ms** 开始感觉拖沓恼人」，并建议「对 >250 ms 的动效设软上限」。
 *
 * 取 **450 ms**：留出一点余量，同时把最长的那条拉回可接受区间。
 *
 * ## 诚实记录这条设置项的取舍
 *
 * 它意味着 `duration_scale = 200%` 时，**长动效并不是字面上的 2 倍**。
 * 这是**刻意**的：该项的语义是"节奏舒舒缓一些"，而不是"让界面卡住"。
 * 短动效（如菜单 140 ms → 280 ms）不受影响，仍是完整的 2 倍。
 *
 * ⚠️ **刻意不用 `internal`**（与相邻的 `MAX_FRAME_HZ` 等不同）：探针源集是**独立的
 * Kotlin compilation**，`internal` 对它不可见 —— 而本常量**必须**能被
 * `AnimationProbe` 断言（否则"软上限"这条约定就没有护栏）。
 * 实测踩到：写成 `internal` 时探针编译报
 * `Cannot access 'val MAX_TRANSITION_MS: Long': it is internal in file`。
 */
const val MAX_TRANSITION_MS = 450L

/**
 * `animation.duration_scale` 的**下限**（百分比）。
 *
 * 50% = "干脆利落"。低于它的动效短到会被读成"突变"，没有产品意义
 * （要"没有动画"应该去关 `animation.enabled`，那才是那个开关的语义）。
 */
const val MIN_DURATION_SCALE_PERCENT = 50

/**
 * `animation.duration_scale` 的**上限**（百分比）—— **2026-09-23 由 200 抬到 1000**。
 *
 * ## 用户原话（逐字）
 *
 * > 「**目前已有的动效放慢 10x，供我检查效果**」
 *
 * ## 为什么必须抬到 1000（而不是"200 已经够了"）
 *
 * 用户要的不是"更舒缓"，而是**把动效放慢 10 倍逐个检查** —— 这是一个**调试/验收档位**，
 * 与 `animation.enabled`（要不要动效）、`animation.style`（怎么演）并列的第三种需求。
 * 上限停在 200% 会让这条诉求**根本表达不出来**（滑杆拖到头也只有 2 倍）。
 *
 * ## 与 `SettingRegistry.range` / `preference_config.json` 的同步义务
 *
 * 三者必须**同值**，否则会出现本项目反复栽过的
 * 「**能拖但被静默夹回**」：滑杆给到 1000，而这里 `coerceIn(…, 200)` 悄悄夹回 200，
 * 用户看到"拖了没用"。故本常量是**唯一权威**，另两处应向它对齐
 * （注册表 `range` 与生成器产物）。
 */
const val MAX_DURATION_SCALE_PERCENT = 1000

/**
 * **旧的** `duration_scale` 上限（百分比）。用于界定 [MAX_TRANSITION_MS] 的适用范围。
 *
 * ## 为什么需要这个界
 *
 * [MAX_TRANSITION_MS]（450 ms 软上限）的论证建立在「值域是 50..200%」这个前提上
 * （见它的 KDoc：200% 时封面 520 ms 越过 `recon/46` 的 500 ms 红线）。
 *
 * 值域抬到 1000% 之后，那条论证**不再适用**：用户显式要"10 倍慢"时把动效夹到 450 ms，
 * 就等于**这个设置项没有兑现**（`duration_scale = 1000` 却只放慢 1.7 倍）。
 *
 * ⇒ 规则：**≤200% 的档位保持旧行为（逐毫秒不变，零回归）；>200% 时不再夹**
 * —— 那时用户已显式选择"检查模式"，语义从"节奏调优"变成"看清每一帧"。
 */
const val LEGACY_DURATION_SCALE_PERCENT = 200

/**
 * 把「目标帧率 + 平台定时器量子」折算成**要交给帧驱动的请求间隔（毫秒）**。
 *
 * ## 它是纯函数（这是本轮把它抽出来的唯一理由）
 *
 * "请求多少 ms 才能在 q 毫秒的量子网格上拿到最接近目标的帧率"是一条**规则**，
 * 不是"某台机器的现象"。抽成纯函数后，探针可以**注入任意量子**
 * （`q = 1` 精确平台 / `q = 15.625` Windows 默认 / `q = 3.9` 假想值）
 * 并断言这条规则在**每一种量子下**都对 —— 而不是只在当前这台机器上"看着对"。
 *
 * ## 规则
 *
 * ```
 * T = 1000 / hz            （目标周期）
 * 若 T < q  → 返回原值 ⌊1000/hz⌋      （实得恒为 1 个量子，请求值取多少都一样）
 * 否则      → 返回 min(原值, ⌊⌊T/q⌋ × q⌋)
 * ```
 *
 * 第二条的含义：**把请求吸附到"不超过目标周期的最大量子整数倍"**。
 * 因为实得间隔 = `⌈请求/q⌉ × q`，只要请求 ≤ `k·q`，实得就恰好是 `k·q`；
 * 而"请求 = 原值"可能刚好**越过** `k·q`（60 Hz：16 ms > 15.625 ms），
 * 于是实得变成 `(k+1)·q` —— **整整多一个量子**（32 Hz）。
 *
 * ## 三个不变量（`AnimationCalibrationProbe` 逐条断言）
 *
 * | # | 不变量 | 改前是否成立 |
 * | --- | --- | --- |
 * | ① | **单调**：标定帧率越高，实得帧率不得越低 | ✗（60→32 Hz，144→64 Hz —— **反的**） |
 * | ② | `q ≤ 1` 时恒等于 `⌊1000/hz⌋`（精确平台零变化） | ✓ |
 * | ③ | 请求 ≤ 目标周期（不多付量子） | ✗（60 Hz 请求 16 ms > 16.667 的量子带下沿 15.625） |
 *
 * @param hz 目标帧率。越界值回退 [DEFAULT_FRAME_HZ]（与 `frameIntervalFor` 同口径）。
 * @param quantumMs 平台定时器量子（毫秒）。非有限 / < 1 时按 **1 ms**（= 不吸附）。
 */
fun frameIntervalMsFor(hz: Int, quantumMs: Double): Long {
    val effective = if (hz in MIN_FRAME_HZ..MAX_FRAME_HZ) hz else DEFAULT_FRAME_HZ
    return snapToQuantum(1000.0 / effective, quantumMs)
}

/**
 * 把**目标周期**（毫秒，可为小数）吸附到定时器量子网格 —— [frameIntervalMsFor] 的内核。
 *
 * 抽出来是为了让"先对齐刷新周期、再对齐量子"的两段式（见 [frameIntervalMsForRefresh]）
 * 能**复用同一段吸附逻辑**，而不是复制一份。**行为与抽出前逐值相同**
 * （`AnimationCalibrationProbe` 的三条不变量继续钉它）。
 */
private fun snapToQuantum(targetMs: Double, quantumMs: Double): Long {
    val naive = Math.floor(targetMs).toLong().coerceAtLeast(1L)
    val q = if (quantumMs.isFinite() && quantumMs >= 1.0) quantumMs else 1.0
    // 目标周期还在第一个量子之内 ⇒ 可达集合只有一个元素（1 个量子），
    // 请求值取 1 还是 15 都一样 ⇒ **保持原值**（在精确平台上这就是恒等映射）。
    if (targetMs < q) return naive
    val k = Math.floor(targetMs / q).toInt().coerceAtLeast(1)
    val snapped = Math.floor(k * q).toLong().coerceAtLeast(1L)
    return minOf(naive, snapped)
}

/**
 * 帧递送的**固定开销**（毫秒）—— 请求间隔与实际投递间隔之差。
 *
 * ## 实测依据（2026-09-26，装置 `_quantumlab/HighRateTimerProbe.java`）
 *
 * | 请求 | 实得 p50 | 差 |
 * |---|---|---|
 * | 1 ms | 2.145 ms | +1.15 |
 * | 8 ms | 9.013 ms | +1.01 |
 * | 11 ms | 12.038 ms | +1.04 |
 * | 16 ms | 16.890 ms | +0.89 |
 *
 * ⇒ **≈ 1 ms**（`javax.swing.Timer` 的 TimerQueue + EDT 投递的固有开销）。
 *
 * ⚠️ **这是一个标定常量**（单机实测），不是"平台属性"。若日后要跨机器精确，
 * 应像 [detectTimerQuantumMs] 那样做一次**运行时标定**并把结果喂给
 * [frameIntervalMsForRefresh] 的 `overheadMs`。
 */
const val FRAME_DELIVERY_OVERHEAD_MS: Double = 1.0

/**
 * 把目标帧率换算成**请求间隔**（毫秒）—— ★ **先按"最终呈现刷新率"取整**，
 * 再对齐定时器量子网格，并**扣掉 [FRAME_DELIVERY_OVERHEAD_MS]**。
 *
 * ## 为什么必须"对齐刷新周期"（2026-09-26 新增）
 *
 * 显示器**每秒只给 N 次呈现机会**（N = 刷新率）。帧间隔若不是刷新周期的**整数倍**，
 * 就必然 **judder** —— 帧落在两个刷新周期之间，只能在**下一个 vsync** 才被呈现。
 *
 * ★ 实测（`_quantumlab/HighRateTimerProbe.java`，本机 **120 Hz** ⇒ 周期 8.333 ms）：
 *
 * | 请求 | 实得 | 占刷新周期 | 判定 |
 * |---|---|---|---|
 * | 8 ms（120 Hz 意图） | 9.01 ms | **1.082** | ❌ 刚越过 1 个周期 ⇒ 等下一个 ⇒ **静默退化成 60 Hz** |
 * | 11 ms（90 Hz 意图） | 12.04 ms | **1.445** | ❌ judder（120 与 90 无整数关系） |
 * | 16 ms（60 Hz 意图） | 16.89 ms | 2.027 | ✅ 落在第 2 个周期内 |
 *
 * ⇒ 旧口径（[frameIntervalMsFor]）只对齐**定时器量子**；`q ≈ 1` 时它退化为恒等映射，
 * **刷新周期与投递开销都没被处理**。
 *
 * ## 算法
 *
 * ```
 * refreshMs = 1000 / refreshHz
 * k         = max(1, ceil(目标周期 / refreshMs))      ← 「按最终呈现刷新率取整」
 * idealMs   = k × refreshMs
 * wantMs    = idealMs − overheadMs                    （提前投递，使实际落在网格上）
 * 再交给 [snapToQuantum] 做量子吸附（q ≈ 1 时它是恒等）
 * ```
 *
 * ## ★ 取整方向：`ceil`（**2026-09-26 用户拍板**）
 *
 * 用户原话：「**帧率档按我们文档发现的实际帧率改掉，诚实一点，不要虚标。**」
 *
 * **原记**：`k` 用 `round`（取最近），KDoc 里标为「待拍板的产品决定」。
 *
 * **实为 `ceil`** —— 依据与后果（120 Hz 屏，`refreshMs = 8.333`）：
 *
 * | 选 | 目标周期 | 目标/8.333 | `round`（原） | `ceil`（现） |
 * |---|---|---|---|---|
 * | 120 | 8.333 | 1.000 | 1 → 120 Hz ✓ | 1 → **120 Hz** ✓ |
 * | 90 | 11.111 | 1.333 | 1 → **120 Hz**（比请求**更快** ✗ 虚标） | 2 → **60 Hz** |
 * | 144 | 6.944 | 0.833 | 1 → **120 Hz**（虚标 ✗） | 1 → **120 Hz** |
 *
 * ⇒ ★ `round` 让「90 / 120 / 144 三档折叠成同一档」，且**超出用户请求**
 * （选 90 想省电，实际拿到 120）—— 这正是"设置值 ≠ 生效值"的抱怨来源。
 * `ceil` 取"**不超出请求的最接近倍数**"⇒ **永不超出用户意图**。
 *
 * ⚠️ 配套：`animation.frame_rate` 的**选项文案已同步改为「不超过 X Hz」**
 * （见 `SettingRegistry` 该键的 KDoc）—— 界面承诺必须与实现语义一致，
 * 否则仍是"设置值 ≠ 生效值"。
 *
 * @param refreshHz 主屏刷新率；**非法值（≤ 0）时退回旧的纯量子吸附** ⇒
 *   无屏 / 驱动异常环境下行为与改动前**逐值相同**（不退化）。
 */
fun frameIntervalMsForRefresh(
    hz: Int,
    refreshHz: Int,
    quantumMs: Double,
    overheadMs: Double = FRAME_DELIVERY_OVERHEAD_MS,
): Long {
    val effective = if (hz in MIN_FRAME_HZ..MAX_FRAME_HZ) hz else DEFAULT_FRAME_HZ
    // 刷新率不可用 ⇒ 退回原口径（只对齐量子），保证无屏/异常环境行为不变。
    if (refreshHz <= 0) return frameIntervalMsFor(effective, quantumMs)

    val targetMs = 1000.0 / effective
    val refreshMs = 1000.0 / refreshHz
    // ★「按最终呈现刷新率取整」：取**最接近**的整数个刷新周期。
    val k = Math.max(1, Math.ceil(targetMs / refreshMs).toInt())
    val idealMs = k * refreshMs
    // 扣掉投递开销 ⇒ 实际投递落在 k×refreshMs 上（而不是再晚一个量子）。
    val wantMs = (idealMs - overheadMs).coerceAtLeast(1.0)
    return snapToQuantum(wantMs, quantumMs).coerceAtLeast(1L)
}

/**
 * 实测**当前帧驱动的投递网格**（毫秒）—— 帧间隔请求必须吸附到它的整数倍。
 *
 * ## ⚠️⚠️ 2026-09-24 重写：原量具**量错了对象**（原记 → 实为）
 *
 * **原记**：本函数"实测平台定时器量子 = `Thread.sleep(1)` 的最小时长 − 1 ms"，
 * 并据此论证"若宿主调用过 `timeBeginPeriod(1)`，量子可能是 1 ms"。
 *
 * **实为（本机实测，`FramePacingProbe` 的「量子量具口径阶梯」，同一 JVM 同一轮）**：
 *
 * | 量具 | 对 1 ms 请求的实得 | 结论 |
 * | --- | --- | --- |
 * | `Thread.sleep(1)` | **1.5 ms** | 不经过被量化的等待路径 ⇒ **原量具量错了对象** |
 * | `System.currentTimeMillis()` 粒度 | **0.99 ms** | "网格来自墙上时钟"**也不成立** |
 * | `LockSupport.parkNanos(1 ms)` | **15.5 ms** | 与帧驱动**同格** ✓ |
 * | `javax.swing.Timer(1)` / `(16)` | **15.63 / 31.10 ms** | ← 生产驱动的**实际网格** |
 *
 * 原量具测出的 0.073 ms 被 [frameIntervalMsFor] 夹成 **q = 1.0（不吸附）** ⇒
 * 2026-09-23 那条"把 60 Hz 档从 16 ms 吸附到 15 ms"的修复**在本机完全没生效**：
 * 请求仍是 16 ms ⇒ 实得仍是 31.25 ms（**32 Hz**，而标签写 60）。
 * **用户可见缺陷（"选最省电的档反而帧率只有一半"）因此一直还在。**
 *
 * 两条被证伪的解释（都做过实验，不是推演）：
 * · **"量子是阻塞等待的属性"**（过度概括）：同样阻塞的 `ScheduledThreadPoolExecutor` /
 *   `parkNanos` 截止期循环在机制表里**没有** ×1.87 超发 —— 但那是**拿双峰分布的 p50
 *   互相比**（它们靠"追帧"把 p50 拉到半格附近），口径没对齐。真实网格是**同一个**。
 * · **"网格来自 `System.currentTimeMillis()` 的粒度"**：JDK 源码取证
 *   `javax.swing.Timer` 的 `TimerQueue.run()` 用 `nanoTime` 算点火时刻
 *   （`NANO_ORIGIN`），等待走 `DelayQueue.take()` → AQS `awaitNanos`，
 *   **完全不经过 `currentTimeMillis`**；且实测它的粒度是 0.99 ms。
 *
 * ⇒ 网格是 **`WaitForSingleObject` 类等待**的属性：请求 1 ms 会被**推迟到下一个 tick 边界**。
 *
 * ## 现在怎么测：**相邻唤醒时刻的网格**
 *
 * 连续 `parkNanos(1 ms)`，记录每次返回的 `nanoTime`，取**相邻唤醒间隔的中位数**。
 * 被量化的等待总落在 tick 边界上 ⇒ 相邻间隔是 tick 的整数倍 ⇒ 中位数即 tick。
 *
 * **为什么不用 `javax.swing.Timer` 直接测**：本函数**是在 EDT 上调用的**
 * （`IslandWindow.applyAnimationSettings` 的 KDoc 明写"必须在 EDT 上调用"），
 * 而从 EDT 上等一个 `Timer` 的投递会**当场死锁**（EDT 被自己占住 ⇒ 投递不了）。
 * `parkNanos` **不依赖 EDT** ⇒ 同一条实现覆盖两条调用路径（"合并成唯一出口"）。
 *
 * **为什么取中位数而不是 min 或平均**：
 * · 平均会把"多跨一格"的间隔算进来 ⇒ 高估；
 * · min 只被**一次**异常提前唤醒污染 ⇒ 低估，而低估到 `target/2` 以下会让 60 Hz 档
 *   **吸附失败**（`floor(target/q)×q` 又跳回 16 ms）；
 * · 中位数对稀疏异常稳健，且实测落在安全区间内（见下）。
 *
 * 安全区间（60 Hz 档成立的条件）：`8.34 < q ≤ 16.6` —— 本机实测 ≈15.5 ✓。
 * 这条区间由 `FramePacingProbe` 的断言 ⑦ 机械守卫（它用**生产量具的返回值**去预测
 * `Timer(16)` 的实得 p50，偏差 > 2 ms 即报红）。
 *
 * 成本：约 100 ms 的连续短等待，**只做一次**（结果缓存）。它发生在第一次 `applySettings` 时
 * —— 那时词岛正在初始化，与原先 47 ms 的量具同量级。
 *
 * ## 为什么"失败"要回退 1 ms 而不是 15.625
 *
 * 回退 15.625 会在**精确平台上主动劣化**（把 60 Hz 档的实得从 62.5 Hz 漂到 64 Hz 且毫无收益）；
 * 回退 1 ms 则是**空操作**（[frameIntervalMsFor] 在 q=1 时恒等于原值）。
 * **劣化的风险远大于收益**，故取保守的一侧。
 */
fun detectTimerQuantumMs(): Double {
    TimerQuantumProbe.ensureMeasured()
    return TimerQuantumProbe.cached
}

/**
 * 网格测量的**缓存**（`detectTimerQuantumMs` 的实现细节）。
 *
 * 为什么用一个对象而不是"每次调 `applySettings` 都测"：
 * `applySettings` 在设置变更路径上**会被反复调用**（订阅回调不区分哪个键变了），
 * 每次测一遍 100 ms 会让"改任何一个设置"都卡一下。
 */
private object TimerQuantumProbe {
    /** 测量预算（毫秒）。整轮不超过它 —— 它发生在 **EDT** 上，必须可预期。 */
    private const val BUDGET_MS = 100L

    /** 单次等待的请求时长（毫秒）。取 1：最小非零请求，量的是"被推迟到哪个 tick 边界"。 */
    private const val REQUEST_MS = 1L

    /**
     * 低于此值的"相邻唤醒间隔中位数"视为**量不到网格**（精确平台上唤醒时刻连续）。
     *
     * 取 2.0 是**噪声地板**：`parkNanos(1 ms)` 在无网格的平台上也要 ≥1 ms + 调度开销，
     * 故 ~1.2 ms 的读数**无法与"没有网格"区分**。那时返回 1.0（不吸附）才是保守选择 ——
     * 否则会在精确平台上把 60 Hz 档的实得从 62.5 Hz 漂到 64 Hz（**主动劣化**）。
     */
    private const val MIN_PLAUSIBLE_QUANTUM_MS = 2.0

    /** `<= 0` = 尚未测量。**刻意不是 `Double` 的默认 0.0 语义**，见下。 */
    @Volatile
    private var value: Double = 0.0

    val cached: Double get() = if (value > 0.0) value else FALLBACK_QUANTUM_MS

    fun ensureMeasured() {
        if (value > 0.0) return
        value = measure()
    }

    /**
     * 见 [detectTimerQuantumMs] 的 KDoc：取**相邻唤醒间隔的中位数**。
     * 量不到（精确平台 / 中断 / 异常）回退 [FALLBACK_QUANTUM_MS]（= 不吸附，零变化）。
     */
    private fun measure(): Double = runCatching {
        val deadline = System.nanoTime() + BUDGET_MS * 1_000_000L
        val gaps = ArrayList<Double>(64)
        var prev = 0L
        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(REQUEST_MS * 1_000_000L)
            val now = System.nanoTime()
            if (prev != 0L) {
                val gap = (now - prev) / 1_000_000.0
                // 落在**同一个**边界上的两次唤醒（gap≈0）不是"网格"，丢弃。
                if (gap >= 0.05) gaps += gap
            }
            prev = now
        }
        if (gaps.size < 3) return@runCatching FALLBACK_QUANTUM_MS
        gaps.sort()
        val median = gaps[gaps.size / 2]
        if (median.isFinite() && median >= MIN_PLAUSIBLE_QUANTUM_MS) median else FALLBACK_QUANTUM_MS
    }.getOrDefault(FALLBACK_QUANTUM_MS)
}

/**
 * 测不到量子时的回退值（毫秒）。
 *
 * 取 **1.0** = **不做任何吸附** —— 见 [detectTimerQuantumMs] 的论证：
 * 在精确平台上"误吸附"会主动劣化，而"不吸附"只是回到改动前的行为。
 */
const val FALLBACK_QUANTUM_MS = 1.0

/**
 * 把**基准时长**按 `duration_scale` 折算成**实际时长（毫秒）**—— [startIn] 的唯一折算点。
 *
 * ## 为什么抽成公开纯函数
 *
 * 用户第 ⑤ 条要的是「**放慢 10x 供我检查**」，而这条诉求的可验证形式是
 * **"每个动效的实际时长 == 基准 × 10"** —— 那是一个**关于 `Motions.ALL` 全集的断言**。
 * 若折算写在 `startIn` 内部，探针只能通过"跑一遍过渡、看它何时结束"间接观察；
 * 抽成纯函数后可以直接对每一条 `MotionSpec` 断言（见 `AnimationCalibrationProbe`）。
 *
 * ## 软上限的分档（这是本轮唯一的语义改动）
 *
 * | `scalePercent` | 行为 |
 * | --- | --- |
 * | ≤ [LEGACY_DURATION_SCALE_PERCENT]（200） | `min(基准 × 倍率, [MAX_TRANSITION_MS])` —— **与改动前逐毫秒相同** |
 * | > 200 | **不夹** —— 用户显式选了"检查模式"，夹掉就等于这个设置项没兑现 |
 *
 * @param baseMs 基准时长（`MotionSpec.durationMs`）
 * @param scalePercent `animation.duration_scale` 的原始百分比
 */
fun scaledTransitionMs(baseMs: Long, scalePercent: Int): Long {
    val percent = scalePercent.coerceIn(MIN_DURATION_SCALE_PERCENT, MAX_DURATION_SCALE_PERCENT)
    val raw = (baseMs * percent) / 100L
    return if (percent <= LEGACY_DURATION_SCALE_PERCENT) raw.coerceAtMost(MAX_TRANSITION_MS) else raw
}

/**
 * **动效目录** —— 本插件全部动效的**唯一清单**。
 *
 * ## ★ 新增一处动效的三步（这就是"易拓展"的全部含义）
 *
 * 1. 在 [Slots] 加一个槽位常量；
 * 2. 在**本对象**加一条 [MotionSpec]（时长 / 缓动 / 风格 / 距离）；
 * 3. 在消费点调 `AnimationRuntime.startIn(spec.slot, from, to, spec.durationMs, spec.easing, …)`
 *    —— 若该处有位移/缩放，再按 `spec.style.offsetYAt(t, spec.distancePx)` / `scaleAt(t)` 取通道值。
 *
 * **不需要**碰：帧循环（`needsAnimationFrames` 已经统一认 `hasRunning()`）、
 * 设置系统（除非要新增一个开关）、其它动效。
 *
 * ## 哪些动效跟随全局风格（判据见下；**不要写「只有 X」**）
 *
 * [followsGlobalStyle] 的判据是"**换一种演法是否仍然合理**"：
 *
 * | 动效 | 跟随？ | 理由 |
 * | --- | --- | --- |
 * | [ISLAND_FADE] | **否** | 词岛是**桌面上的悬浮件**，它没有"从哪儿来"的方向。滑入/缩放都会让人以为"它是从屏幕边上飞出来的" —— 那是**错误的空间隐喻**，不是风格选择 |
 * | [MENU_FADE] | **否** | 菜单的透明度作用在**承载它的原生弹出窗**上（`Window.opacity`），而位移/缩放要在菜单内部做 —— 两者机制不同，强行统一会让"换风格"在菜单上**只生效一半**。故固定 FADE，诚实 |
 * | [CONTROLS_APPEAR] | **是** | 控件条**确实是从词岛的一条边挤出来的**，三种演法都成立且各有观感 —— 这正是"该让用户选"的地方 |
 * | [LYRIC_EXIT] | **是** | 旧歌词行的退场：淡出 / 上滑一个行高 / 缩小，三种都成立（见该 spec 的 KDoc） |
 * | [LYRIC_ENTER] | **是** | 新歌词行的入场 —— 与 [LYRIC_EXIT] 是**同一个"演法族"**（同一段动效的两端），风格必须**一致**才不割裂，故跟随判据与它相同（见该 spec 的 KDoc） |
 * | [COVER_CROSSFADE] | **否** | 封面占的是**固定的左侧方形槽位** —— 让它滑进来或弹出来会与「槽位」这个静态布局事实冲突（见该 spec 的 KDoc） |
 *
 * ## 时长的取值依据（**本表列全 5 条**；`CONTROLS_APPEAR` 沿用常量、另两条的依据在各自 spec 的 KDoc）
 *
 * | 动效 | 时长 | 依据 |
 * | --- | --- | --- |
 * | [ISLAND_FADE] | 220 ms | 低于 150 ms 时"渐变"会被读成"闪一下"（与突变几乎无差别）；高于 350 ms 时"消失"显得拖沓 —— 而词岛消失的典型场景是「暂停了，别挡着我干活」，用户此刻希望它**快点让开**。220 ms 落在两者之间。**与控件条的淡入时长不同值是有意的**：词岛面积大得多，同样时长在大面积上会显得更快 |
 * | [MENU_FADE] | 140 ms | 菜单是"点开就要用"的东西，比词岛更短。长于 200 ms 会让"右键 → 看到菜单"之间出现可感知的等待 |
 * | [CONTROLS_APPEAR] | = `HoverState.FADE_IN_MS` | **沿用控件条既有的淡入时长**（**本表不复述数值**，见该常量）—— 用户对"悬停多久弹出"已有手感，换风格不该顺手改快慢 |
 * | [COVER_CROSSFADE] | 260 ms | **比词岛的 220 ms 略长** —— 封面是**大色块**，同样时长在大色块上显得更快；且换曲是一个「时刻」，稍慢更有仪式感（详见该 spec 的 KDoc） |
 * | [LYRIC_EXIT] | 180 ms | 行切换是**高频**事件（每几秒一次），比词岛显隐的 220 ms 更短才不显拖沓（详见该 spec 的 KDoc） |
 * | [LYRIC_ENTER] | 180 ms | **与 [LYRIC_EXIT] 同值** —— 两者是同一段换行动效的两端，**必须同长**，否则会读出"旧行还在上面滑、新行已经站好了"的错拍 |
 */

object Motions {

    /**
     * 词岛整体显隐：**淡入淡出 + 3% 微缩放**。**固定 FADE 语义**（理由见 [Motions] 的表）。
     *
     * ## 2026-09-21 的改动：从"纯淡入"换成"淡入 + 微缩放"（用户要求 UX 至上）
     *
     * **原状**：`style = FADE`，即只有透明度变化。
     * **问题**：用户反馈「动效**一闪而过**、观感不好」。调研（`recon/46` §3.1）的结论是
     * 「进入用**减速曲线 + 8px 上移 + 4% 放大 + 淡入**」，并解释了为什么：
     * **位移与缩放提供"重量感"**，纯透明度变化在视觉上接近"闪烁"。
     *
     * **为什么只取缩放、不取位移**：词岛是**贴在桌面上的悬浮件**，没有"从哪来"的方向。
     * `translateY(8px→0)` 会让它像"从屏幕上方掉下来"—— 那是**错误的空间隐喻`
     * （本项目 KDoc 一直反对，探针也为此设了断言）。而**缩放在方向上中立**：
     * 它只表达"由小及大落位"，不暗示任何来向。
     *
     * **为什么是 3%（0.97）而不是调研建议的 4%**：调研给的是"**上限**"（8px / 4%）。
     * 词岛的可见面积远大于控件条与菜单，同样的百分比在大面积上**感知更强**，
     * 故取上限再收一档。这也回应了本项目原先"词岛缩放看着很怪"的判断 ——
     * **怪的不是缩放本身，是 6%（POP 的写死值）在那个尺寸上太夸张**。
     */
    val ISLAND_FADE = MotionSpec(
        slot = Slots.ISLAND_FADE,
        durationMs = 220L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.FADE,
        followsGlobalStyle = false,
        // ★ 新增：与 `FADE` 的 scaleAt（恒 1f）相乘 → 实际 0.97→1.0。
        scaleFrom = 0.97f,
    )

    /**
     * **封面交叉淡化**。换曲时旧封面淡出、新封面淡入。
     *
     * **固定 FADE**（不跟随 `animation.style`）：SLIDE/POP 都要**移动或缩放**元素，
     * 而封面占的是**固定的左侧方形槽位** —— 让它滑进来或弹出来会与"槽位"这个
     * 静态布局事实冲突（用户会看到封面在槽位里乱动）。交叉淡化是这个位置
     * 唯一说得通的演法，故不提供切换。
     *
     * 260 ms：比词岛的 220 ms 略长 —— 封面是**大色块**，同样的时长在大色块上
     * 显得更快；而且换曲是一个"时刻"，稍慢一点更有仪式感。
     * 缓动用 `EASE_IN_OUT`（两头慢、中间快）：它是**原地变化**，
     * 不是"出现"（见 [Easing] 的语义表）。
     */
    val COVER_CROSSFADE = MotionSpec(
        slot = Slots.COVER_CROSSFADE,
        durationMs = 260L,
        easing = Easing.EASE_IN_OUT,
        style = MotionStyle.FADE,
        followsGlobalStyle = false,
    )

    /**
     * **歌词行切换时旧行的退场**。
     *
     * ## 为什么**不做交叉淡化**（这条仍然成立）
     *
     * 本插件的歌词是**单槽**（用户明确要求"取消已唱歌词的显示"），
     * 于是"换行"发生时**新旧两段文字占的是同一个位置**。
     *
     * | 做法 | 为什么不行 |
     * | --- | --- |
     * | **交叉淡化** | 两段**不同的文字**叠在同一位置 → 糊成一团（不像两张图，文字重叠没有"交融"的观感） |
     *
     * ## ⚠️ 原记「新行原地出现、不做入场」**已被用户推翻**（2026-09-28）
     *
     * **原记**：新行是用户**马上要读**的东西，故**有意不做入场动画**。
     * **实为**：用户报「消失动画做了但显现动画没做，显得**割裂**」。
     * ⇒ 现由本 spec 的镜像 [LYRIC_ENTER] 承担入场（**判定与取证全文见那里**）。
     *
     * 旧行的 `-lineHeight` 与 [LYRIC_ENTER] 的 `+lineHeight` 拼成同一条向上的轨迹；
     * 两者**必须是两个槽位**（同时跑，见 [Slots.LYRIC_ENTER] 的说明）。
     *
     * ## 为什么距离来自布局
     *
     * 上移量是**一个行高**（`字号 × LINE_HEIGHT_RATIO + lineGap`），随字号变 ——
     * 写死像素会在用户把字号调到 42 时"只移了一点点"。故 `distanceFromLayout = true`。
     *
     * 180 ms：行切换是**高频**事件（每几秒一次），比词岛显隐的 220 ms 更短才不显拖沓。
     * 缓动 `EASE_OUT`（开头快、结尾慢）—— 退场要"赶紧让开"，与词岛淡出同一条理由。
     */
    val LYRIC_EXIT = MotionSpec(
        slot = Slots.LYRIC_EXIT,
        durationMs = 180L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.SLIDE,
        followsGlobalStyle = true,
        distanceFromLayout = true,
    )

    /**
     * **歌词行切换时新行的入场**（2026-09-28 新增，用户明确要求）。
     *
     * ## 原记 X，实为 Y（**这是一次被用户推翻的设计决定**）
     *
     * **原记**（本文件 [LYRIC_EXIT] 的 KDoc，2026-09-20 写下）：
     *
     * > 「**新行也滑动入场** | 新行是用户**马上要读**的东西，让它在 180 ms 里边动边亮
     * >  = 在最需要读的时刻给它加运动」
     *
     * 即：当时**有意只做退场**，理由是"入场的运动干扰阅读"。
     *
     * **实为**（2026-09-28 用户报障，原话）：
     *
     * > 「歌词的**消失动画是做了，但显现动画没做**，显得**割裂**（副歌词也是）」
     *
     * 用户的判断是：**只有一半的动效比没有动效更糟** —— 旧行优雅地滑走、新行"啪"地
     * 钉在原地，这个不对称本身就是可见的缺陷（他用的词是「割裂」）。
     * 而"干扰阅读"这个担心在实践中**没有兑现**：180 ms 的 EASE_OUT 在 60 Hz 下
     * 只有约 11 帧，且入场是"从偏移位置**收敛到**静止"——读者的视线本来就落在
     * 新行上，它落位的过程反而给了"这是新的一句"的提示。
     *
     * ## 结论：原记是**设计偏好**，不是"技术不可行"
     *
     * 本项目条款（`recon/26`）：**用户决策优先**于设计偏好，但必须**诚实记录**。
     * 故原记**不删**，改为在本 KDoc 里并列保存 —— 后来人若想"改回去"，
     * 应当看到当时的两条理由与用户的裁决，而不是只看到一条。
     *
     * ## 为什么必须与 [LYRIC_EXIT] **对称**
     *
     * | 维度 | 取值 | 为什么必须与退场一致 |
     * | --- | --- | --- |
     * | `durationMs` | 同 180 ms | 同一段动效的两端；不同长会读出"错拍"（旧行还在滑、新行已站定） |
     * | `easing` | 同 `EASE_OUT` | 换行是**高频**事件，两头都该"赶紧站好"，不能一个急一个缓 |
     * | `style` | 同 `SLIDE` + `followsGlobalStyle` | **割裂感的根源就是两端演法不同** —— 若入场固定 FADE 而退场跟随用户选 SLIDE，换行时会看到"上滑走 + 原地浮现" |
     *
     * ## 方向：入场距离取 `+lineHeight`（**从下方**滑入）
     *
     * 退场是 `-lineHeight`（上滑走）。入场取正值 = 从下方一个行高**升上来** ——
     * 于是两段拼起来是**同一条向上的轨迹**（旧行从原位上滑离去，新行从原位下方
     * 升到原位），读起来是"歌词在向上流动"，与"旧行原地向上飞走"的孤立感完全不同。
     *
     * ⚠️ 距离**由布局给**（`distanceFromLayout = true`），符号在**绘制点**取
     * （见 `IslandWindow` 的入场绘制）—— 与 [LYRIC_EXIT] 同一条纪律：
     * 写死像素会在用户改字号后错位。
     */
    val LYRIC_ENTER = MotionSpec(
        slot = Slots.LYRIC_ENTER,
        durationMs = 180L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.SLIDE,
        followsGlobalStyle = true,
        distanceFromLayout = true,
    )

    /** 右键菜单淡入。**固定 FADE**（理由见 [Motions] 的表）。 */
    val MENU_FADE = MotionSpec(
        // `slot = null`：菜单淡入走 `transitionOf`（**独立过渡**，见那里的 KDoc）——
        // 它由菜单自己的定时器推进，**不经过** `AnimationRuntime`，故不占槽位。
        // "同时只能有一个菜单淡入"由 `IslandMenuSkin.menuFadeOwner` 保证。
        slot = null,
        durationMs = 140L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.FADE,
        followsGlobalStyle = false,
    )

    /**
     * 播放控件条的出现。**跟随 `animation.style`**。
     *
     * 时长与 `HoverState.FADE_IN_MS` 同值（**本 KDoc 不复述数值**，见该常量）—— 那是控件条既有的淡入时长，
     * 这里**不改它**：用户对"悬停多久弹出"已有手感，换风格不该顺手改快慢。
     */
    val CONTROLS_APPEAR = MotionSpec(
        // `slot = null`：它的进度由 `HoverState` 的定时器推进（见 `MotionSpec.slot` 的说明），
        // **不经过** `AnimationRuntime` —— 故它不占槽位，也不会与帧循环产生第二个驱动。
        slot = null,
        // ⚠️ 与 `HoverState.FADE_IN_MS` **必须同值**（那边才是真正的驱动）。
        //    150 → 180（2026-09-21）：见 `HoverState.FADE_IN_MS` 的 KDoc 与 `recon/46` §3.3。
        durationMs = 180L,
        easing = Easing.EASE_OUT,
        style = MotionStyle.SLIDE,
        followsGlobalStyle = true,
        // 8 px（原 20 px）：见上方表格与 `recon/46` §3.3 ——
        // 跨体系观测到的共识是"小元素 8 px / 中等元素 16 px"，再大显浮夸。
        // 控件条是贴在词岛边上的一条，8 px 足够表达"从边缘挤出"的方向感。
        distancePx = 8f,
    )

    /** 全部动效。**探针遍历它做一致性断言**，也是"一共有哪些动效"的唯一答案。 */
    val ALL: List<MotionSpec> = listOf(ISLAND_FADE, COVER_CROSSFADE, LYRIC_EXIT, LYRIC_ENTER, MENU_FADE, CONTROLS_APPEAR)

    /**
     * 把用户选的**全局风格**套到一条 spec 上。
     *
     * **所有消费点都必须走它**，不要自己判 `followsGlobalStyle` ——
     * 那会把同一条规则复制到 N 处，迟早有一处漏掉（表现是"这个动效不跟随设置"）。
     */
    fun resolve(spec: MotionSpec, globalStyle: MotionStyle): MotionSpec =
        if (spec.followsGlobalStyle) spec.copy(style = globalStyle) else spec
}

/**
 * 造一个**不受 [AnimationRuntime] 调度**的过渡 —— 供**自带定时器**的子系统使用。
 *
 * ## 为什么需要它（而不是"所有动画都必须走 AnimationRuntime"）
 *
 * [AnimationRuntime] 由**主帧循环**驱动，而主帧循环的启停由**播放态**决定
 * （暂停即停帧，见 `IslandWindow.syncFrameDriver`）。有些子系统的动画
 * **不能**依赖它：
 *
 * - **右键菜单的弹出窗**是**独立的原生窗口**，存活期只有几百毫秒，
 *   与"歌在不在播"毫无关系。挂在帧循环上会出现"暂停时菜单不淡入"。
 *
 * 这类子系统用本工厂造一个 [Transition]，**用自己的定时器**按
 * [Transition.advance] 推进。
 *
 * > **复用的是"数学"，不是"调度"** —— 缓动公式与插值方式仍然只有一份
 * > （[Easing] / [Transition]），不会出现"两处对同一个缓动有两种实现"。
 *
 * ## 为什么它没有 `enabled` / `duration_scale`
 *
 * 因为那是 [AnimationRuntime] 的**实例状态**，而本函数是**无状态的工具**。
 * 调用方自己决定时长：**要关掉动画就传 `0`**（本函数会同步落终值）。
 * 这样"关动画"的语义（真的没有动画，而不是"变快"）由调用方一处决定，
 * 不需要在这里再复制一份判断。
 *
 * @return `null` = **不需要跑帧**（时长为 0 / 起止已相等），此时 [onUpdate] 与
 *   [onDone] **已被同步调用过**。非 null 时由调用方负责逐帧推进。
 */
fun transitionOf(
    from: Float,
    to: Float,
    durationMs: Long,
    easing: Easing,
    onUpdate: (Float) -> Unit,
    onDone: (() -> Unit)? = null,
    /**
     * 起点时刻（纳秒）。默认取 [System.nanoTime]；**探针注入假时刻**。
     *
     * 与 `AnimationRuntime` 的 `clock` 是同一个接缝、同一条理由：
     * 起点与 [Transition.advance] 收到的时刻必须**同基准**，
     * 否则进度恒被夹到 0（探针就在断言一个恒为 `from` 的值 —— 永远验不到插值）。
     */
    startNanos: Long = System.nanoTime(),
): Transition? {
    if (durationMs <= 0L || nearlyEqual(from, to)) {
        onUpdate(to)
        onDone?.invoke()
        return null
    }
    return Transition(
        from = from,
        to = to,
        durationMs = durationMs,
        easing = easing,
        startNanos = startNanos,
        onUpdate = onUpdate,
        onDone = onDone,
    )
}

/**
 * 两个浮点是否"足够接近"（用于判断"动画是否真的需要跑"）。
 *
 * 阈值 0.01 的依据：本插件动画插值的量纲是**像素**与**不透明度（0..1）**。
 * 0.01 px 在任何缩放下都不可见；0.01 的不透明度在 8 位通道上不足 3/255，
 * 同样不可见。故这个阈值下被跳过的动画**在观感上是恒等的**。
 */
internal fun nearlyEqual(a: Float, b: Float, eps: Float = 0.01f): Boolean = abs(a - b) < eps
