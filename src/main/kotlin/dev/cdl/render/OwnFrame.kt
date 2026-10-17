// SPDX-License-Identifier: AGPL-3.0-only
//
// L3（真透视 / ④b）判据的**输入类型**：一帧"自身画面"的不可变快照。
//
// ## 为什么这个类型在 `render/` 而不是留在 `window/`
//
// 它原本是 `IslandWindow.OwnFrame`（`window/` 的嵌套类），因为"产出它的人"是
// 面板的绘制路径（`IslandPanel.publishOwnFrame`）。但 L3 落地后出现了一个
// **反向依赖**：`render/BackdropCapture` 的采集线程要消费它做 S1 判据，
// 于是 `render/` 得 import `window/` —— 而本项目的分层方向是
// **`window/` → `render/`**（`window/` 用 `SelfCaptureGate` / `MaterialLayers` 等
// 一堆 `render/` 的东西，反向一条都没有）。
//
// 让它反向 = 埋一个环：以后 `render/` 里任何一次"顺手用个窗口类型"都不会报错，
// 直到某天要单独编译 `render/`（探针、单元测试）时才发现拉不出来。
//
// ## 判定依据：**谁依赖谁**
//
// | 类型 | 语义归属 | 消费者 |
// |---|---|---|
// | [OwnFrame] | "**自身画面**"（一个渲染概念，与"窗口"无关） | `BackdropCapture`（`render/`） |
// | `Appearance` | "窗口外观设置" | `IslandPanel` 绘制路径 |
//
// ⇒ 前者放 `render/`，后者留 `window/`。这不是"哪个包更顺眼"，是**依赖方向**。
//
// ## 不变性（跨线程共享的前提）
//
// **所有字段只读** —— 跨线程共享一份不可变对象，是"不做锁也能安全"的前提。
// 面板在 EDT 上**一次性替换**引用（`IslandPanel.ownFrame` 是 `@Volatile`），
// 采集线程读到的是**某一个完整帧**，不会读到半个。
package dev.cdl.render

import java.awt.Shape
import java.awt.image.BufferedImage

/**
 * 一帧"自身画面"的不可变快照（面板坐标 + 可选的岛体区域像素）。
 *
 * @property seq 帧序号（单调递增）—— 诊断用：可判"EDT 是否还在出帧"
 * @property shape **岛体形状**（面板坐标）。S1 只在此形状**内部**取样 ——
 *   圆角外的像素是桌面，掺进来会把相关拉低
 * @property islandLeft 岛体左边缘（面板坐标，**Float** —— 与 `BandGeometry` 同源）
 * @property islandTop 岛体上边缘（面板坐标，**Float**）
 * @property islandWidth 岛体宽（**Int** —— `BandGeometry` 把它量化成整数）
 * @property islandHeight 岛体高（**Int**）
 * @property pixels 岛体区域的 ARGB 像素（**仅 armed 时非 null**）；
 *   尺寸为 `islandWidth × islandHeight`，原点 = 岛体左上角
 */
class OwnFrame(
    val seq: Long,
    val shape: Shape,
    val islandLeft: Float,
    val islandTop: Float,
    val islandWidth: Int,
    val islandHeight: Int,
    val pixels: BufferedImage?,
)
