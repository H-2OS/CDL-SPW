// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

/**
 * 播放控件的**悬停状态机** —— 决定"什么时候展开控件、什么时候收起"。
 *
 * > `public` 的理由见 `ControlsConfig` 的类 KDoc（探针要直接喂时间戳断言时序，故不能是 `internal`）。
 *
 * ## 触发与维持用的是**两套不同的判据**（这是本类最重要的一条设计）
 *
 * | 阶段 | 判据 | 参数 |
 * | --- | --- | --- |
 * | 收起 → 展开 | **字形命中**（`hitGlyph`） | `controls.enterDelayMs` |
 * | 展开 → 保持 | **鼠标仍在词岛矩形内**（`inside`） | — |
 * | 展开 → 收起 | 鼠标离开词岛矩形 | `controls.exitDelayMs` |
 * | 展开 → 收起（无操作） | 展开后长时间无鼠标活动 | `controls.autoHideMs` |
 *
 * **为什么"维持"不用字形命中**：展开会让词岛**变高一个控件条**（见
 * `IslandWindow.applyWindowSize`），而 `controls.position = TOP` 时歌词会随之下移。
 * 若"保持展开"仍要求字形命中，那么歌词一移动，鼠标底下就不再是字形 →
 * 立刻开始收起 → 歌词移回 → 又命中 → **来回抖动**。
 * 用"鼠标是否还在词岛内"作为维持判据把这条反馈环**从结构上切断**：
 * 展开后的词岛比展开前大，鼠标不可能因为展开而跑到岛外。
 *
 * 这也正是用户原话的语义边界：「鼠标**移动到歌词上**（而不是一移到歌词栏框就弹出）」
 * —— 用户约束的是**弹出条件**，不是"弹出后必须一直压在字上"。
 *
 * ## 为什么所有延迟都必须由定时器推进，不能只在鼠标事件里判
 *
 * `enterDelayMs` 的语义是"命中持续这么久才弹出"。用户把鼠标**稳稳停在**字上不动时
 * 不会产生新的 `mouseMoved` 事件 —— 若只在事件里比较时间，控件永远不会弹出。
 * 同理 `exitDelayMs` 要求"离开后仍保持这么久"，也需要有人在那之后把状态收回来。
 * 故本类由 `IslandWindow` 用 `javax.swing.Timer` 按固定间隔驱动（见 [needsTicker]）。
 *
 * ## 纯状态机（不碰 Swing）
 *
 * 本类不持有任何 Swing 对象、不发起重绘 —— 它只回答"状态变了吗"，
 * 由调用方决定要不要重排窗口。这样它可以被独立验证（探针可直接喂时间戳），
 * 也不会因为"状态机里偷偷调了 repaint"而产生难以追踪的重绘风暴。
 */
class HoverState {

    /** 控件是否应当展开。**只由 [update] 改写**。 */
    var expanded: Boolean = false
        private set

    /**
     * 展开动画的进度 `0f..1f`（0 = 完全透明，1 = 完全展开）。
     *
     * ## 为什么动画进度放在状态机里，而不是让绘制层自己算
     *
     * 动画必须**有一个推进者**。绘制层是无状态的（每次 `paintComponent` 都从头算），
     * 它无法自己"记着动画从什么时候开始的"。若把起始时刻存进绘制层，
     * 那绘制层就有了跨帧状态 —— 而它同时还在被 `repaint()` 的调用时机影响，
     * 很容易出现"重绘时重启动画"导致浮动永远到不了终点。
     *
     * 放在状态机里则天然正确：状态机已经持有"展开时刻"，动画只是它对时间的函数，
     * 且它已有 [needsTicker] 这个"还要不要继续跑定时器"的判据 ——
     * 动画期间需要定时器，动画结束就不需要，两者是同一个机制。
     *
     * ## 为什么是"固定淡入"而不是 0.8.9 的五种动画
     *
     * `recon/13` §10.6 的拍板是「首版**不做弹出动画**（不做选择器），**用固定淡入**」。
     * 五种动画（N8b：淡入淡出 / 闪现 / 外移入 / 内弹出 / 左右向中移入）明确属 P1。
     * 故这里只做**一条固定时长、固定缓动的淡入**，不引入任何设置项 ——
     * 一旦开成设置项，它就变成了 N8b 的选择器，超出首版范围。
     *
     * 缓动用 smoothstep（`t²(3-2t)`）而不是线性：线性淡入在中段看起来"发死"，
     * smoothstep 两端导数为 0，起始与结束都不突兀。这是纯常数级开销
     * （两次乘法），不需要缓动函数表。
     */
    var expansion: Float = 0f
        private set

    /** 命中字形的起始时刻；[NONE] = 当前没有待判定的命中。 */
    private var enterSince: Long = NONE

    /** 鼠标离开词岛矩形的起始时刻；[NONE] = 当前鼠标在岛内。 */
    private var exitSince: Long = NONE

    /** 最近一次"鼠标在岛内"的时刻，供 `autoHideMs` 判定。 */
    private var lastInsideAt: Long = NONE

    /** 本次展开的起始时刻，供 [expansion] 的插值使用。 */
    private var expandedSince: Long = NONE

    /**
     * 推进一步。**鼠标事件与定时器都调它**，语义完全相同。
     *
     * @param hitGlyph 鼠标是否**命中了字形**（`controls.triggerMode = ISLAND_HIT` 时，
     *   调用方直接传"是否在岛内"，本类不关心两者的区别）
     * @param inside 鼠标是否在**词岛矩形内**（含展开后的控件条）
     * @param nowMs 当前时刻（毫秒）。用调用方传入而非内部读时钟，
     *   这样探针可以用假时间戳复现"延迟到点"这类时序分支
     * @param cfg 控件配置（本方法只用其中的三个延迟参数）
     * @param pointerEvent 本次调用是否**由真实的鼠标事件**驱动。
     *
     *   ## 为什么必须区分（**这是一处实测发现的真实缺陷**）
     *
     *   `autoHideMs` 的语义是"展开后**无交互**这么久就收起"。而"有没有交互"只能
     *   由**鼠标事件**回答 —— 鼠标停在原处不动时**不产生任何事件**。
     *   定时器 tick 是**内部推进**，不是用户行为：若把它也算作交互，
     *   那么 ticker 每 40 ms 就把"最近活动时刻"刷新一次，`autoHideMs` 将**永不触发**
     *   （离线自检 J 组直接复现了这个 bug：期望 1000 ms 后收起，实际不收起）。
     *
     *   故：只有 `pointerEvent = true` 且鼠标在岛内时才刷新活动时刻。
     * @return 本次调用是否**改变了** [expanded]。调用方据此决定要不要重排窗口 ——
     *   返回 `false` 时重排是纯浪费（窗口尺寸每次变更都会触发一次完整重绘）
     */
    fun update(
        hitGlyph: Boolean,
        inside: Boolean,
        nowMs: Long,
        cfg: ControlsConfig,
        pointerEvent: Boolean,
    ): Boolean {
        val was = expanded
        if (pointerEvent && inside) lastInsideAt = nowMs

        if (!expanded) {
            // ── 收起态：只等"命中持续 enterDelayMs" ──
            if (hitGlyph) {
                if (enterSince == NONE) enterSince = nowMs
                if (nowMs - enterSince >= cfg.enterDelayMs) {
                    expanded = true
                    // 展开的瞬间把三个计时器都归零：此刻鼠标一定在岛内，
                    // 于是 exitSince 必须是 NONE（否则会立刻开始倒计时收起）；
                    // lastInsideAt 刷新为现在，否则 autoHide 会拿"进入前的旧时刻"立刻触发。
                    enterSince = NONE
                    exitSince = NONE
                    lastInsideAt = nowMs
                    // 展开时刻 = 淡入动画的起点。
                    expandedSince = nowMs
                }
            } else {
                // 命中中断（鼠标移开了）→ 取消待判定。这就是"防划过误触"的实现：
                // 快速掠过时 hitGlyph 只在一两帧为真，enterSince 被反复重置，永远等不到延迟。
                enterSince = NONE
            }
            // 收起态：动画进度必须是 0。不做"淡出"—— 收起时窗口已经变矮，
            // 控件条所在的那条区域已不存在，淡出没有可绘制的载体。
            expansion = 0f
            return expanded != was
        }

        // ── 展开态：先推进淡入动画 ──
        // 用 coerce 而不是 clamp 到 [0,1] 后再判：动画放完（>= FADE_IN_MS）后
        // 每次 tick 都会重新算一次，值恒为 1f —— 这是幂等的，不会漂移。
        val elapsed = if (expandedSince == NONE) FADE_IN_MS else nowMs - expandedSince
        val t = (elapsed.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
        expansion = t * t * (3f - 2f * t)   // smoothstep

        // ── 展开态：等"离开持续 exitDelayMs"，或"无操作 autoHideMs" ──
        if (inside) {
            exitSince = NONE
        } else {
            if (exitSince == NONE) exitSince = nowMs
            if (nowMs - exitSince >= cfg.exitDelayMs) {
                expanded = false
                enterSince = NONE
                exitSince = NONE
                expandedSince = NONE
                expansion = 0f
            }
        }
        if (expanded && cfg.autoHideMs > 0 && lastInsideAt != NONE &&
            nowMs - lastInsideAt >= cfg.autoHideMs
        ) {
            expanded = false
            enterSince = NONE
            exitSince = NONE
            expandedSince = NONE
            expansion = 0f
        }
        return expanded != was
    }

    /**
     * 是否还需要继续跑悬停定时器。
     *
     * 存在的理由：本项目对**常驻定时器**很克制（见 `IslandWindow.syncFrameDriver`
     * 关于"暂停时停掉帧驱动"的论证）—— 一个每 40 ms 唤醒一次、绝大多数时候什么都不做的
     * 定时器会让 CPU 无法进入深度空闲。故这里给出"还需不需要"的判据，让调用方按需启停：
     *
     * - 收起态：只有**正在等进入延迟**时才需要（`enterSince != NONE`）。
     *   已判定"未命中"（`enterSince == NONE`）时，没有任何延迟在跑 —— 下一次
     *   状态变化必然由鼠标事件带来，不需要定时器。
     * - 展开态：**等离开延迟**或**开着自动隐藏**时才需要。
     *   鼠标在岛内且 `autoHideMs = 0` 时状态是稳定的，同样不需要定时器。
     *
     * ## [pollPointer] 的**真实契约**（2026-09-21 更正）
     *
     * 它回答的是「**调用方会不会在每个 tick 里自己取一份新指针**」——
     * 一个**关于调用方的陈述**，而不是"穿透了所以要常驻"。
     * 传 `true` ⇒ 调用方承诺每个 tick 都喂一份新指针给状态机，
     * 故本判据**不能**按"有没有延迟在跑"来停定时器（停了就没人喂了）。
     *
     * 真机里这个入参由 [hoverNeedsPointerPoll]（`IslandPolicy.kt`）唯一给出：
     * **整窗穿透 ⇒ `false`**（调用方会提前 `return`，根本不轮询）、
     * **区域级穿透 ⇒ `true`**（要回答"光标**离开**字形了吗"，而离开时**没有事件**可收）。
     *
     * ## 原记（BUG-023，2026-09-17）—— **已推翻**，保留供追溯
     *
     * 原记：穿透窗口（`WS_EX_TRANSPARENT`）**收不到任何鼠标事件**，
     * 于是 `onPointer` 不被调用 → `enterSince == NONE` → 本判据返回 `false`
     * → 定时器不启动 → **引导死锁**：
     * ```
     * 穿透 → 无鼠标事件 → onPointer 不被调用 → enterSince == NONE
     *      → needsTicker 返回 false → 定时器不启动 → enterSince 永远是 NONE
     * ```
     * 故当时判定"穿透态必须**无条件常驻轮询**"（照抄原实现 0.8.9 的每帧
     * `MouseInfo` 轮询，`089:ui/IslandWindow.kt:142-146`）。
     *
     * **实为：那是过度纠正。**「鼠标穿透」的语义**就是该窗口不接收鼠标交互** ——
     * "控件不可达"是穿透的**正常结果，不是缺陷**。用户原话：
     * > 「**设置鼠标穿透后，鼠标经过不应再感应或触发播放控件**」
     *
     * 故 `onHoverTick` 现在在整窗穿透时**第一件事就 `return`**
     * （`IslandWindow.kt:3215`），而本判据的"常驻"只对**区域级穿透**有意义。
     *
     * > ⚠️ 保留上面这段的理由：它是"为什么会有 `pollPointer` 这个参数"的**历史**，
     * > 删掉会让后来者以为这个参数是凭空长出来的。
     * > 但它**不是**当前行为的依据 —— 依据是本节开头那段「真实契约」。
     *
     * **代价（诚实记录）**：区域级穿透下多了一个 40 ms 的常驻定时器
     * （`HOVER_TICK_MS`）—— 这是"背景穿透 + 字形可交互"能同时成立的**唯一**办法
     * （不轮询就不知道"光标离开字形了没有"）。整窗穿透与不穿透时都按需启停。
     *
     * @param pollPointer 调用方是否处于"每个 tick 都会自己轮询指针"的模式
     *   —— 见 [hoverNeedsPointerPoll]（真机唯一的给出处）。
     */
    fun needsTicker(cfg: ControlsConfig, pollPointer: Boolean = false): Boolean = if (pollPointer) {
        true
    } else if (!expanded) {
        enterSince != NONE
    } else {
        // ⚠️ `expansion < 1f` 这一条是**淡入动画的生命线**：动画要每帧推进 alpha，
        // 而推进者就是这个定时器。若不看它，动画在展开后第一帧就会被"状态稳定"判据
        // 停掉定时器 —— 表现为控件条**卡在半透明**（永久停在第一个 alpha 值上）。
        // 这条与 autoHideMs / 两个延迟并列，都是"状态还在变"的判据。
        expansion < 1f || exitSince != NONE || cfg.autoHideMs > 0
    }

    /**
     * 复位到"收起且无待判定"。
     *
     * 调用时机：窗口被隐藏（`island.enabled = false` / `hide_paused`）时。
     * 不复位的话，窗口重新显示时可能带着一个陈旧的 `enterSince`，
     * 表现为"鼠标还没碰到字，控件就自己弹出来了"。
     */
    fun reset() {
        expanded = false
        enterSince = NONE
        exitSince = NONE
        lastInsideAt = NONE
        expandedSince = NONE
        expansion = 0f
    }

    /**
     * **立即展开**（不经过"命中持续 `enterDelayMs`"那条路径）。
     *
     * ## 谁需要它（2026-09-30，封面态 S5）
     *
     * 封面态的**锁头标志**：用户原话「**点锁标 = 展开**」。
     * 那条路径不是"鼠标停在某个东西上等一会儿"，而是**一次明确的点击**——
     * 语义上等价于"用户已确认要展开"，故**不该**再走 `enterDelayMs` 延迟
     * （那会让点击后还要愣一下才展开，像卡顿）。
     *
     * ## 为什么是本类的方法（而不是让调用方戳字段）
     *
     * `expanded` / `expandedSince` / `enterSince` / `exitSince` / `lastInsideAt`
     * 全是 `private set` 或 `private` —— 这是**刻意**的（见 [expanded] 的 KDoc：
     * "只由 [update] 改写"）。而"展开"这个状态转移涉及**五个字段必须同时归零**，
     * 散在调用方会让某一天只改其中几个 ⇒ 出现"刚展开就立刻收起"这类
     * **只在某条路径上**出现的缺陷。
     * ⇒ 把它收进本类，与 `update` 的展开分支**共用同一套状态转移**（唯一出口）。
     *
     * ## 与 `update` 展开分支的关系
     *
     * 状态转移与那个分支**逐字相同**（同一个不变量：展开瞬间鼠标一定在岛内 ⇒
     * `exitSince` 必须是 `NONE`、`lastInsideAt` 刷新为现在 ⇒ 否则 `autoHideMs`
     * 会拿"进入前的旧时刻"立刻触发）。改这里时**必须同时看那一处**。
     *
     * @param nowMs 当前时刻（毫秒）。入参化的理由与 [update] 相同：探针要用假时钟
     *   复现时序。★ **已经展开时是幂等的**（重复调用不改变状态）。
     */
    fun expandNow(nowMs: Long) {
        if (expanded) return
        expanded = true
        enterSince = NONE
        exitSince = NONE
        lastInsideAt = nowMs
        expandedSince = nowMs
    }

    private companion object {
        /**
         * "没有时刻"的哨兵。用 `Long.MIN_VALUE` 而不是 `0`：
         * `0` 是合法时刻（`System.currentTimeMillis()` 的零点虽远，但探针里传 0 很自然），
         * 用 0 会让"从未命中"与"在时刻 0 命中"混为一谈。
         */
        const val NONE = Long.MIN_VALUE

        /**
         * 控件条展开的淡入 / 滑入时长（毫秒）。**固定值，不是设置项** ——
         * 理由见 [expansion] 的 KDoc（`recon/13` §10.6：首版用固定淡入，五种动画属 P1）。
         *
         * ⚠️ **必须与 `Motions.CONTROLS_APPEAR.durationMs` 同值** ——
         * 本常量是**真正的驱动**（`expansion` 按它算进度），
         * 而 `Motions` 里那份是"声明值"（供绘制取 easing 与位移、供探针断言）。
         * 两处不同值会造成"进度早跑完了而声明说还在跑"的错配。
         * （跨文件一致性**有探针守着**：`ControlsProbe` 的 O 节以声明值驱动本类，
         * 两者不等则"恰好一半"或"恰好到 1"必有一个红。见 BUG-111。）
         *
         * ## 取值区间的依据
         *
         * 低于 100 ms 时人眼看不出"渐变"（只是"闪一下"）；
         * 高于 250 ms 会让控件条显得"迟迟不出现"，与"悬停即应可得"的预期冲突。
         *
         * ## 150 → **180**（2026-09-21）
         *
         * 动效调研（`recon/46` §3.3）的结论：150 ms 对"较宽的表面"偏快，
         * 建议 180–200 ms —— 元素越大，需要越多时间才读得顺。
         * 用户报的「**动效一闪而过**」里，控件条正是最明显的一条。
         *
         * ⚠️ **本 KDoc 刻意不复述"当前是多少毫秒"**（除上面那条"何时改的"历史）——
         * 数字只在下一行的 `= 180f` 一处。理由见 `SettingRegistry` 的
         * 「不要把项数硬编码进散文」：**"把它改对"不解决问题，删掉那个数字才解决。**
         *
         * （本次同时删掉了上方**一段孤立的死 KDoc**：它描述的还是 150 ms 时代，
         *  且因为后面紧接第二个 KDoc 块，Kotlin 根本不把它挂在常量上 —— 属于
         *  "看起来是文档、实际没人看"的文本，正是"文字与实现不一致"最爱藏的地方。）
         */
        const val FADE_IN_MS = 180f
    }
}
