// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

/**
 * 设置变更的「**变更域**」—— 键命名空间 → 消费者 的**唯一**映射表。
 *
 * ## 它守的是什么缺陷（第 1 期 ⑧）
 *
 * `IslandWindow.installSettingsSubscription` 的订阅体**没有相关性过滤**：
 * 任何键的变更都会跑完整套 `applyAppearance` → `applyExtensionStyles` →
 * `applyAnimationSettings` → `applyVisibility` → `syncFrameDriver`。
 *
 * 其中 `applyAnimationSettings` 末尾是
 * `framePacer.reset()` + `frameTimer?.delay = animator.frameIntervalMs.toInt()`。
 *
 * ⇒ 于是**拖一根颜色滑杆**（`publish` 按鼠标采样率同步通知订阅者）会把
 * ⑦（`FramePacer` 绝对截止期）的**epoch 反复作废** ——
 * `reset()` 把起点改成"现在"，截止期序列退化成"每帧 delay = interval"，
 * 也就是 ⑦ 修掉的那个 **×1.07 超发**又回来了。
 *
 * ★ 这正是本项目 D10 那一类的又一例：**「改了 A 但 A 的输入被 B 掐断」**
 * （A = ⑦ 的帧节拍修正；B = 无关变更路径）。
 * 首选修法即纪律里写的「**合并成唯一出口**」—— 故有了本表 + [SettingChange.touchesAny]。
 *
 * ## 为什么按**键命名空间**派生，而不是按设置页分组
 *
 * 设置页分组（`SettingDescriptor.group`）**不是**按消费者划分的：
 * `animation.enabled` 的 group 是 `SettingGroups.ANIMATION`
 * （★ **2026-10-17 更正**：原记「是 `SettingGroups.TOP_LEVEL`（第 0 组「总开关」）」——
 *   ★ **那个组已撤销**，用户指令「取消总分组，将其下发回各自的组」⇒
 *   本项已回到「动画」组。**原记 vs 实为**留在此处，防后人按旧结论推导。）
 * ⇒ 若按 `group == ANIMATION` 派生「帧域」，**会漏掉**任何**不在**该组、但键名属于
 * `animation.…` 的项 —— 而 `animation.enabled` 决定 `animator.enabled`
 * （关掉即 `finishAll()`），正是帧驱动的输入之一。
 *
 * ⇒ 命名空间是**更稳的契约**：注册表里 `key = "animation.…"` 的写法就是它的定义。
 * 这与本项目既有纪律一致 —— 「护栏写成**命名空间断言**」（设置项改名会静默失效，已 3 次）。
 *
 * ## 为什么不是手写的键清单
 *
 * 手写 `setOf("animation.frame_rate", "animation.quality", …)` 会随设置项增删而**腐烂**，
 * 且腐烂时**不报错**（漏掉的键 = 该域判定恒 false = 修正静默失效）。
 * 命名空间是**开集**：新增 `animation.*` 自动入域。
 *
 * ## 取向：域是**超集**，宁多勿漏
 *
 * 多命中一次（如改 `animation.style` 也 reset 一次 pacer）代价 = **一帧的相位重锚**，
 * 不可见；漏命中一次 = ⑦ 的修正失效，且**没有任何报错**。
 * 两侧代价差着量级 ⇒ 一律取超集。
 *
 * ## 域之间必须**不相交**
 *
 * 同属两域会让「无关变更」的判定自相矛盾（同一键既算几何又算帧）。
 * `SettingDomainProbe` 机械断言这一点。
 */
object SettingDomains {

    /**
     * **几何域** —— 变更会改变词岛尺寸/位置 ⇒ 必须重算窗口几何。
     *
     * 这原先是 `IslandWindow` 里的一句字面量
     * `changedKeys.any { it.startsWith("layout.") || it.startsWith("main_lyric.font.") }`，
     * 现收敛到这里（**唯一实现**）。
     *
     * 为什么是这两个命名空间：字号变化会改变窗口高度（见 `applyGeometry` 的说明），
     * 布局项同理 —— 而颜色/描边这类外观项**便宜**，不该触发几何重算。
     *
     * ## ★★★ 2026-10-10 **补 `shape.`**（BUG-161：**词岛形态切换"看着没反应"**）
     *
     * ★★ **症状（用户报障）**：把「外观形态」从「悬浮窗（封面态）」换成「圆角矩形」，
     *   岛体**看起来没变** —— ★ 鼠标移开仍缩回封面态 ✓
     *
     * ★★★ **根因**：★ `bandGeometryNow()` 里
     *   `anchorAtLeftEdge = coverShapeSkipsLyricChain(shapeTypeNow())`
     *   ⇒ ★★ **岛体几何（宽度增长方向 / 左锚）依赖 `shape.type`** ——
     *   而 ★ `COVER_ONLY` 的定义就是「**词岛缩到封面大小**」⇒ **换形态必然改窗口尺寸** ✓
     * ★ 但 `GEOMETRY` 里**没有 `shape.`** ⇒ 改 `shape.type` **只重绘、不重算几何**
     *   ⇒ ★★ **窗口仍停在封面态的尺寸** ⇒ ★ **表现就是"切换失败"**（★ 不报错、不告警）✗
     *
     * ★ **修法**：★ 把整个 `shape.` 命名空间并入本域 ✓
     *   ★ 按本域既有的取向「**域是超集，宁多勿漏**」——
     *     多算一次几何的代价 = **重设几个字段 + 一次 repaint**（可忽略）；
     *     漏一次的代价 = ★ **用户改了没反应且无任何报错**（本次就是这个）✓
     *
     * ⚠️ **`shape.corner_radius` / `corner_style` / `corner_n` 严格说只改形状、不改尺寸** ——
     *   一并入域是**有意的**：① 它们是**同一个 UI 细类**（用户连着调），
     *   ② 判据只按**命名空间**写（本文件开头那条：手写键清单会腐烂）✓
     */
    val GEOMETRY: Set<String> = setOf("layout.", "main_lyric.font.", "shape.")

    /**
     * **帧驱动域** —— 变更会改变 `animator` 的帧间隔/启停 ⇒ 必须重臂帧驱动
     * （`framePacer.reset()` + `frameTimer.delay`）。
     *
     * 为什么整个 `animation.` 命名空间都算：`applyAnimationSettings` 读的**全部**四个键
     * 都在此（`enabled` / `quality` / `frame_rate` / `duration_scale`），
     * 且 `quality` 会改 `animator.frameIntervalMs` 的档位。
     * `SettingDomainProbe` §B 用**源码级断言**保证这一点不被将来改坏。
     */
    val FRAME: Set<String> = setOf("animation.")

    /** 全部域（供守卫穷尽遍历）。 */
    val ALL: List<Set<String>> = listOf(GEOMETRY, FRAME)
}
