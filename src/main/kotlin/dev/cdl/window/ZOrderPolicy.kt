// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 「临时 topmost」的**判定层**（BUG-145 · F-4′）。
 *
 * ## 这是什么问题
 *
 * 用户关掉「窗口置顶」后，词岛变成普通窗口 ⇒ **跨进程**时会被别的应用盖住
 * （`BUG-与修复记录.md` 的 BUG-145：同进程 OK / 跨进程不 OK / 打开置顶 OK）。
 * 根因是 Windows 的**前台锁** —— 非前台进程不许抢焦点，所以
 * `toFront()` / `requestFocus()` 跨进程**静默失败**。
 *
 * 唯一的绕法：改用 **topmost**（`SetWindowPos(HWND_TOPMOST)`）——
 * topmost 组**不需要焦点**就能压住别人。实测可逆性成立
 * （`_f4prime_probe/ZOrderProbe2.java`：`false → true → false`）。
 *
 * ## 用户定的语义（★ 最终版：2026-10-01 01:2x）
 *
 * > 「**点击词岛外部就释放，不是点击词岛**」（用户逐字）
 *
 * ⇒ 判据 = "**前台窗口是否属于本进程**"（[shouldHoldTempTopmost]）：
 * 点词岛 ⇒ 词岛成为前台（仍是本进程）⇒ **保持**；
 * 点别的应用 / 桌面 / 任务栏 ⇒ 前台离开本进程 ⇒ **释放**。
 *
 * ★★ **演进（三轮翻覆，见 `migration/57`）**：
 *
 * | 时间 | 语义 | 判据 |
 * |---|---|---|
 * | 2026-09-30 09:xx（F-4′） | 「直到焦点切换走，离开词岛」 | [shouldHoldTempTopmost]（前台 pid） |
 * | 2026-09-30 16:3x（㉚ 追加） | 我误读成「点**词岛**才释放」 | ~~`shouldKeepTempTopmostManualOnly`~~（**已删**） |
 * | **2026-10-01 01:2x（现行）** | 「点**词岛外部**才释放」 | ★ **[shouldHoldTempTopmost]**（前台 pid）—— 与 F-4′ 相同 |
 *
 * 这两条语义各自修掉的旧问题：
 * ① 点一下词岛后**切不回**（原来只有拖动词才临时提顶）；
 * ② 拖到窗口外松手 ⇒ `mouseReleased` 丢失 ⇒ **卡在 topmost**（原来没有兜底）。
 * ② 的兜底由**看门狗**承担 —— 它每 400 ms 判一次前台，发现离开就释放。
 *
 * ## 为什么单独抽成纯函数
 *
 * 本项目的既定形态（见 `CoordHygieneProbe` 与 `physicalBounds` 的先例）：
 * `WindowInterop` 是 `internal object`，**探针看不到**；且真实 Win32 调用
 * **无法在探针里 mock**。故把"该不该保持"这个**判定**抽成顶层纯函数，
 * 让探针能对**真值表**逐格断言（`ZOrderPolicyProbe` / `RecoverIslandProbe`）。
 *
 * ★ **判定与执行分离**：本文件**不碰**任何窗口；只回答"要不要保持"。
 * 真正的 `raiseToTop` / `restoreAlwaysOnTop` 由 `IslandWindow` 调用。
 */
package dev.cdl.window

/**
 * 临时 topmost 是否应**继续保持** —— **产品判据**（BUG-145 · F-4′）。
 *
 * ## 用户原话（逐字，2026-10-01 01:2x 澄清）
 *
 * > 「我说的是**点击词岛外部**就释放，**不是点击词岛**」
 *
 * 本函数在 2026-09-30 16:3x – 2026-10-01 01:2x 之间曾被一个**误读的判据**取代
 * （`shouldKeepTempTopmostManualOnly` —— 我把"点了才释放"读成"点**词岛**才释放"，
 * 把释放入口放进了 `mousePressed`）。用户 2026-10-01 01:2x 纠正后**已回退到本判据**，
 * 那个误读的函数**已删除**（不是降级保留 —— 它编码的是**反的语义**，留着有害）。
 *
 * ## 真值表（探针逐格断言，见 `ZOrderPolicyProbe`）
 *
 * | `manualRaised` | `foregroundPid` | `selfPid` | 返回 | 理由 |
 * |---|---|---|---|---|
 * | `false` | 任意 | 任意 | **false** | 没有"用户操作过"这个前提，不该提顶（否则推翻用户设置） |
 * | `true` | `0`（取不到） | 任意 | **true** | ★ **宁可多保持** —— API 抖动不该造成闪烁 |
 * | `true` | `= selfPid` | 同 | **true** | ★ 前台还在本 App（**点词岛 / SPW 窗口**）⇒ **保持** |
 * | `true` | `≠ selfPid` 且非 0 | 同 | **false** | ★ 前台已离开 ⇒ 立刻降回用户设置（= 用户说的"点词岛外部"） |
 *
 * ⚠️ `selfPid` 传 **0** 时（调用方取不到自己的 pid）**恒返回 false** ——
 * 判据说服不了自己，就不该提顶。这与第 2 行**方向相反**，是**刻意的**：
 * - `foregroundPid == 0` ⇒ 这是**运行中**的一次瞬时失败，保守保持（避免闪烁）；
 * - `selfPid == 0` ⇒ 这是**环境**不具备能力（非 Windows / pid 拿不到），
 *   此时提顶本身就不可靠 ⇒ 保守放弃（避免造出一个收不回的 topmost）。
 *
 * ## 为什么"焦点离开就释放"而不是"超时释放"
 *
 * 超时会在"用户按住词岛思考"时误释放（窗口掉下去），而"焦点归属"是**直接观测**，
 * 不含猜测成分 —— 符合纪律 25「时间只作报告，断言必须结构性」的取向。
 *
 * @param manualRaised 用户操作（按下 / 拖动）触发的临时提顶是否仍生效中。
 * @param foregroundPid 当前前台窗口的进程 id；**0 = 取不到**（见
 *   `WindowInterop.foregroundProcessId`）。
 * @param selfPid 本进程 id；**0 = 取不到**。
 */
fun shouldHoldTempTopmost(
    manualRaised: Boolean,
    foregroundPid: Int,
    selfPid: Int,
): Boolean {
    if (!manualRaised) return false
    if (selfPid == 0) return false
    if (foregroundPid == 0) return true
    return foregroundPid == selfPid
}

