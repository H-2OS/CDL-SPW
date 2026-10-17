// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

import java.awt.Font

/**
 * 列表项/标签文字要用的字体：**候选字体能完整显示这段文字才用它**，否则回退 [fallback]。
 *
 * ## 为什么需要它（2026-10-18，用户报「字体名显示乱码」）
 *
 * 字体选择器的列表项**用候选字体画自己的名字**（这样用户不必点开就知道那一项长什么样）。
 * 但显示名里含中文来源前缀 —— 宿主族 `@host:Inter` 的显示名是
 * 「宿主内置 Inter」，而 `Inter` **没有中文字形** ⇒ 四个汉字被画成方框
 * （用户截图里就是「□□□ Inter」）。
 *
 * 判据用 [Font.canDisplayUpTo]：返回 `-1` 表示整段文字都能显示。
 *
 * ## 取舍（刻意的）
 *
 * 含中文的显示名 ⇒ 拉丁字体项**不再按自身字形渲染**。
 * 理由：「看得懂名字」优先于「看到字形」；纯拉丁族名（`Inter`）仍走自身字体，
 * 所以"这一项长什么样"这个信息绝大部分没有丢。
 *
 * ## 为什么是 public
 *
 * 两个渲染器（`FontSourceDialog` / `FontPicker`）都要用它，探针也要能直接断言
 * （probe 是独立 Kotlin 编译模块，`internal` 看不见）。
 * 请求封装请用 KDoc，不要收窄可见性 —— 本项目已因 `internal` 撞过 21 条编译错误。
 */
fun fontForLabelText(candidate: Font, text: String, fallback: Font): Font =
    if (candidate.canDisplayUpTo(text) == -1) candidate else fallback
