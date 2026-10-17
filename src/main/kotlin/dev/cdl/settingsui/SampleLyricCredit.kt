// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settingsui

import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JTextField
import javax.swing.SwingConstants

/**
 * **示例歌词的出处与作者** —— 取色窗口"示例词岛"下方那行可复制的说明文字。
 *
 * ## 用户原话（逐字，2026-09-21 真机反馈；见 `recon/_第二十二轮真机反馈分诊-2026-09-21.md` §B）
 *
 * > 「出处与作者写在**示例词岛下方（深浅色块范围外）正中**："**Ceui -《今、歩き出す君へ。》**"
 * >   出处与作者作为文字**要能复制**，**不参与示例词岛的渲染**。」
 *
 * 更早一轮（BUG-085）用户也给过同一首歌的出处，写法略有不同
 * （`《今、歩き出す君へ。》- Ceui`），本轮**以最新原话的写法为准**：
 * `Ceui -《今、歩き出す君へ。》`（**逐字照抄，不要改写**）。
 *
 * ## 三条硬约束，以及本文件为什么长这样
 *
 * | 约束 | 本文件的处置 |
 * | --- | --- |
 * | **位置** = 示例词岛**下方** · 深浅色块范围**外** · **水平居中** | 本文件只提供**一个组件**（[sampleLyricCreditRow]），由调用方把它**加在 `IslandPreviewPane` 之后**（同一个 `Y_AXIS` 盒子里）—— 于是它天然在下方、且在对比底色块之外 |
 * | **要能复制** | 用只读 [JTextField]（`JLabel` **不可选中**因而无法复制；`JTextPane` 偏重）—— 见 [sampleLyricCreditField] 的注释 |
 * | **不参与示例词岛的渲染** | ⚠️ 本常量**刻意不放进** [PreviewSnapshot]，也不走 `IslandPreviewPane.paintComponent`。它**不是**快照字段，**不该**被 `ColorPickerProbe` 的像素断言覆盖 |
 *
 * ## ⚠️ 为什么单独开一个文件，而不是塞进 `PreviewSnapshot.kt`
 *
 * 因为第三条约束把话说死了：它**不得进入示例词岛的渲染链**。`PreviewSnapshot` 的**每一个**
 * 字段都通过 `IslandPreviewPane` 变成像素；把出处放进去，等于把它画进词岛 ——
 * 正是用户明确否掉的。放这里还有一个好处：**它是"说明文字"，不是"设置"**，
 * 与设置注册表（`SettingRegistry`）也不该有耦合。
 *
 * ## 许可与出处（演示素材纪律）
 *
 * ⚠️ 这两行歌词与歌名是**第三方版权作品**，**不属于**本项目 GPL-3.0-only 的授权范围。
 * 它由项目所有者**明确指示**用作取色器的**演示预览**（同 `resources/demo/cover-demo.jpg`，
 * 见 `resources/demo/README.txt`）。对外发布前请自行确认授权，或换成原创示例句。
 */
object SampleLyricCredit {

    /** 歌名 —— 与用户原话逐字一致（含全角句点）。 */
    const val TITLE = "今、歩き出す君へ。"

    /** 演唱 / 作者。 */
    const val ARTIST = "Ceui"

    /**
     * **要显示的那一行**（用户原话里逐字给出）：`Ceui -《今、歩き出す君へ。》`。
     *
     * ⚠️ 格式是"**作者 -《歌名》**"，**不是**"《歌名》- 作者"（BUG-085 那轮的写法已被
     * 本轮原话取代）。改写会让它与用户给的字符串对不上。
     *
     * ⚠️ 2026-09-22 更正：本段原写「`RegistryProbe` §11 有一条断言钉住这个**逐字**值」——
     * 实为**假承诺**：`grep RegistryProbe.kt` 对 `SampleLyricCredit` / `CREDIT` **零命中**。
     * 逐字值现由 `SampleLyricCreditProbe` §1 直接钉住（它就在本探针的同一批门禁里）。
     */
    const val CREDIT = "$ARTIST -《$TITLE》"

    /**
     * **可复制的出处文字**（只读文本框）。
     *
     * ## 为什么是 `JTextField` 而不是 `JLabel`
     *
     * 用户要求「出处与作者作为文字**要能复制**」。`JLabel` **不可选中**，
     * 因而**无法复制** —— 本项目在 BUG-085 里已经因为这个理由换过一次实现
     * （那次是从 `JLabel` 换成只读 `JTextField`）。
     *
     * 只读但可选中：`isEditable = false`（不能改）+ `isFocusable = true`（能聚焦、能 Ctrl+C）。
     * ⚠️ 不要用 `setEnabled(false)` —— 那会让文字变灰且**不可选中**，等于把"能复制"打掉。
     */
    fun sampleLyricCreditField(): JTextField = JTextField(CREDIT).apply {
        isEditable = false
        isFocusable = true
        isOpaque = false
        // ⚠️⚠️ 2026-09-22 修正（**原写 `isBorderPainted = false` 会让整个模块编译不过**）：
        //    `setBorderPainted` 是 `AbstractButton` / `JToolBar` 的 API，
        //    **`JTextField` 没有它** —— 实测报
        //      `e: SampleLyricCredit.kt:76:9 Unresolved reference 'isBorderPainted'`
        //    （整棵 `compileKotlin` 因此 FAILED，全队都跑不了探针）。
        //    "无边框"在 `JTextField` 上的正确写法是 `border = null`。
        //
        //    ⚠️ 第二条坑（**已修，2026-09-22**）：`applyToolWindowTheme` 的 `is JTextField` 分支
        //    原本会**无条件**重设 `background = fieldBg` 与 `border = 细边线`
        //    （见 `applyToolWindowTheme` 的 `is JTextField` 分支），于是这里设的"透明底 + 无边框"会被
        //    打回"白底输入框"外观。
        //    处置：给那个分支加了 `if (c.isEditable)` —— 只读文本框（全 `settingsui/`
        //    就本字段一个）不再被套底色/边框，**前景色仍无条件设**（深色主题下要可读）。
        //    其余所有 `JTextField` 都是可编辑的，路径逐像素不变。
        border = null
        horizontalAlignment = SwingConstants.CENTER
        // 比正文小一档（用户要的是"小字提示"）：`deriveFont` 而不是换字号常量，
        // 这样调用方 `applyToolWindowTheme` 按用户字体等比缩放时，本行仍然"比正文小"。
        font = font.deriveFont(font.size2D - 2f).deriveFont(Font.PLAIN)
        // 外观仍像标签：无边框、透明背景、光标为文本光标（暗示可选中）。
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR)
        toolTipText = "可选中复制"
    }

    /**
     * **水平居中的一行**（= 文本框 + 两侧弹性空隙）—— 直接 `add(...)` 到纵向盒子里即可。
     *
     * ## 为什么要包一层（而不是直接给文本框）
     *
     * 用户要求「**正中**」。文本框若直接进 `BoxLayout(Y_AXIS)`，会被**拉伸到满宽**，
     * 文字虽居中、但"可选中区域"横跨整行 —— 用户点空白处也会选中它，观感不像一句说明文字。
     * 故包一层：两侧 `Box.createHorizontalGlue()`，让**文本框自身**居中且只有内容那么宽。
     */
    fun sampleLyricCreditRow(): javax.swing.JComponent = javax.swing.JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(Box.createHorizontalGlue())
        add(sampleLyricCreditField())
        add(Box.createHorizontalGlue())
    }
}
