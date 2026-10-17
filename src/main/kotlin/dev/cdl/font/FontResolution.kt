// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font

/**
 * ★★★ **字体解析的唯一漏斗** —— 「哨兵 → 宿主字体；失败 → **回退族**；非哨兵 → 原路」。
 *
 * ## 为什么必须有它（BUG-161 的根因，2026-10-07）
 *
 * ### 时间线：**「默认值」与「回退值」曾经是同一个东西，后来分家了**
 *
 * | 时点 | 出厂默认值 | 回退族名 | 关系 |
 * |---|---|---|---|
 * | 2026-09-22（取消随包字体） | `Microsoft YaHei UI`（**系统族名**） | `DEFAULT_FONT_FAMILY` = 同一个 | ★ **同一个** ⇒ `Font(family, …)` 的回退**恰好等于**它 |
 * | **2026-09-29**（用户「复用宿主字体作默认」） | **`@host:MiSans`（哨兵）** | 仍是 `Microsoft YaHei UI` | ★★ **分家了** |
 *
 * ⇒ 而各处回退写的都是 `?: Font(family, Font.PLAIN, size)` —— **用的是同一个 `family` 字符串**。
 * 哨兵解析失败时那就是 `Font("@host:MiSans", …)` ⇒ ★ **AWT 静默退化成 `Dialog`**，
 * 而 `DEFAULT_FONT_FAMILY`（`Microsoft YaHei UI`）**在任何生产路径上都没被当过回退**。
 *
 * ★★ 后果（用户可见）：**渲染漏斗与工具窗都回退到 `Dialog`**，而不是申报的回退族。
 * 而护栏**全绿** —— 因为 `FontDefaultProbe` ②③ 守的是 `DEFAULT_FONT_FAMILY`，
 * **它不在渲染路径上**（详见 `migration/97-护栏与生产路径错位-2026-10-07.md`）。
 *
 * ### 判据（本项目可复用的一条）
 *
 * > **当一条护栏的「守护对象」与「生产路径的取值」不是同一个变量时，护栏必然错位。**
 *
 * ## 语义（三条，**都要可断言**）
 *
 * | 输入 | 行为 |
 * |---|---|
 * | **哨兵**且宿主字体已预热命中 | 返回宿主字体（`deriveFont(size)`） |
 * | **哨兵**但未预热 / 宿主不可得 | ★ 返回 **`DEFAULT_FONT_FAMILY`**（**申报的回退族**，本机必有） |
 * | **空 / 全空白**（值被清空或写坏） | ★ 同上 ⇒ **回退族**（与 `FontPicker` 既有的 `ifBlank { FALLBACK_FAMILY }` 同口径） |
 * | **非哨兵**（系统族名 / 用户自选 / 用户导入） | ★ 原路 `Font(family, PLAIN, size)` —— **既有行为零改变** |
 *
 * ★ **为什么非哨兵不也回退到 `DEFAULT_FONT_FAMILY`**：那一档是**用户显式选的**族名，
 * 选错是用户可自行更正的（且 `FontPicker` 的 `isFamilyAvailable` 会把它标成"渲染不出区别"）。
 * ★ 而哨兵与空值**不是用户的错** —— 前者是我们自己的出厂默认值，后者是"值坏了"
 * ⇒ 这两档必须兜住，否则用户会拿到一个我们**没申报过**的字体。
 *
 * ★ 调用方一律用本函数，**不要**再各写 `HostFonts.resolveOrNull(f) ?: Font(f, …)`
 * —— 那正是"同一份规则多个落点"（`BUG-150` / `BUG-161` 同一族）。
 *
 * @param family 设置里的族名（可能是 `@host:` 哨兵、系统族名、或空/坏值）
 * @param size 字号（px）
 * @return **永不为 `null`、永不抛**（坏输入交给 `Font` 自己回退/钳制）
 */
fun resolveFontOrFallback(family: String?, size: Int): Font {
    // ⓪ 空 / 全空白 ⇒ 回退族（"值坏了"不是用户的错）。
    val raw = family?.trim().orEmpty()
    if (raw.isEmpty()) return Font(DEFAULT_FONT_FAMILY, Font.PLAIN, size)
    // ① 哨兵：先问宿主（未预热 ⇒ null，**不同步扫描**，见 `HostFonts.resolveOrNull` 的 KDoc）。
    val host = HostFonts.resolveOrNull(raw)
    if (host != null) return host.deriveFont(size.toFloat())
    // ② ★ 哨兵但拿不到宿主 ⇒ **回到申报的回退族**（而不是 `Font(哨兵)` ⇒ Dialog）。
    if (HostFonts.isSentinel(raw)) return Font(DEFAULT_FONT_FAMILY, Font.PLAIN, size)
    // ③ 非哨兵 ⇒ 原路（既有行为零改变）。
    return Font(raw, Font.PLAIN, size)
}

/**
 * ★★ 与 [resolveFontOrFallback] **同一个判据**，但返回"是否**真的**拿到了请求的族"
 * —— 供需要区分「可用 / 回退」的调用方（如候选列表项：可用才用自己画自己）。
 *
 * @return `true` ⇒ 拿到的是**请求的那个**（宿主字体，或系统里真有这个族）；
 *   `false` ⇒ 走了回退（空值 / 哨兵失败 / 非哨兵但本机没有）
 */
fun fontMatchesRequest(family: String?, size: Int): Boolean {
    val raw = family?.trim().orEmpty()
    if (raw.isEmpty()) return false
    if (HostFonts.resolveOrNull(raw) != null) return true
    if (HostFonts.isSentinel(raw)) return false
    return Font(raw, Font.PLAIN, size).family.equals(raw, ignoreCase = true)
}
