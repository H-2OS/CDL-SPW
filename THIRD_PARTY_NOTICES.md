# 第三方许可与署名

> 本文件按**约束强度**从强到弱排列。前四节（AGPL / CC BY-SA / LGPL / MIT）是**强约束**，
> 义务不可用本项目自身的许可覆盖；后续 Apache-2.0 节为弱约束（保留声明即可）。
> 「发布前必做」的可勾选清单见 `../开发期取证记录许可合规核对清单.md`。

## 0. 本项目的许可结构一览

| 内容 | 许可 | 是否随包分发二进制 | 是否需随包源码 |
| --- | --- | --- | --- |
| 本项目自有代码 | **AGPL-3.0-only** | 是（`classes/`） | **是**（AGPL-3.0 §6） |
| 移植自原实现词岛的代码 | 菜单体系 6 文件：**AGPL-3.0-only**（**H-2OS / 自宅警備員** 本人代码，见 §2.3）；原实现两处通用算法（`HostLyricsProbe` / `CoverColorExtractor`）：**GPL-3.0-only**（版权归原实现作者 **GaBoron**，保留署名，**不单方面变更许可**） | 是 | **是** |
| 本项目自有代码（含 `LICENSE` 正文） | **AGPL-3.0-only** | 是 | **是**（`source/`） |
| 视觉与交互改编、本文档等文字材料 | **CC BY-SA 4.0**（原创 Lyricify / WXRIW） | 是（随文档） | 文字材料本身即源码形式 |
| ~~融合的歌词搜索组件（原 SPW-Lyrics）~~ **⚠️ 当前未移植进本插件**（见 §4.1 前提声明） | **MIT**（**预留** —— 一旦移植，MIT 声明必须保留） | **否**（实测） | **暂不适用**（原写"是"） |
| Jaudiotagger 3.0.1 | **LGPL-2.1-or-later** | 是（独立 JAR） | **是**（LGPL-2.1 §6，须附对应源码） |
| JNA / JNA Platform 5.17.0 | Apache-2.0（双许可中选其一） | 是 | 否（但须附许可与声明） |
| ~~kotlinx-serialization 1.9.0 / opencc4j 1.14.0（+ `heaven` / `nlp-common`）~~ **🔴 2026-09-28 实测：本插件均无这些依赖**（`build.gradle.kts` 零出现；`lib/` 仅 3 个 jar） | **不适用**（原写 Apache-2.0"随包"） | **否** | **不适用** |
| SPW Workshop API、PF4J、Kotlin stdlib | Apache-2.0 | **否**（宿主提供） | 否 |
| **演示素材** `demo/cover-demo.jpg`**⚠️ 2026-09-28 新增登记** | **不在开源许可体系内**（用户提供；本文件**不为其授予任何许可**） | 是（`classes/demo/`） | 否（素材本身即交付形态；来源与加工记录见 §10） |

---

## 1. AGPL-3.0-only —— 本项目自有代码的许可

### 1.1 Apple Music-like Lyrics（AMLL）

> ## ✅ 前提声明（**2026-10-18 更新**）
>
> **本项目自有代码已改为 AGPL-3.0-only**（作者 **H-2OS / 自宅警備員**）。
> 根 `LICENSE` 即 AGPL-3.0 全文（与随包的 `licenses/AGPL-3.0.txt` 为同一份）；
> 「关于」对话框、`NOTICE` 与 README 均已同步。
>
> **`ui/AmllMotion.kt` 仍然不存在**（计划移植、尚未落地）——
> 本节以下原文保留，作为**将来移植 AMLL 时的义务清单**；
> 但「AGPL 在本项目中只是预留」这一性质**已不成立**（自有代码本身就是 AGPL）。
>
> **当前正确表述**：
> · 自有代码 = **AGPL-3.0-only**；
> · 移植的菜单体系（H-2OS 本人代码）= **AGPL-3.0-only**；
> · 原实现两处通用算法（`HostLyricsProbe` / `CoverColorExtractor`）= **GPL-3.0-only**（署名归 **GaBoron**，不单方面变更许可；组合作品整体按 AGPL 分发）。

| 项 | 内容 |
| --- | --- |
| 组件 | Apple Music-like Lyrics（AMLL） |
| 参考版本 | 源码修订 `58ccd3ffae7ec4e9a6d1cdb0dd88ac8c767f68a8` |
| 原始文件 | `packages/core/src/lyric-player/dom/lyric-line.ts` |
| 版权行 | `Copyright its respective AMLL contributors (including Steve-xmh).` |
| 来源 URL | https://github.com/amll-dev/applemusic-like-lyrics |
| 许可 | **AGPL-3.0-only**，全文见 [`licenses/AGPL-3.0.txt`](licenses/AGPL-3.0.txt) |
| 本项目中的载体 | **⚠️ 尚未移植**（原写 `ui/AmllMotion.kt`「该文件是唯一的 AGPL 文件」—— **该文件不存在**；`licenses/AGPL-3.0.txt` 目前是**预留**） |
| 随包 | **否（实测）** —— 原写"是（编译进 `classes/`）" |
| 需附源码 | **暂不适用**（无可对应的 AGPL 模块）；**原写"是"** —— 若日后移植则**立即适用** |

**移植范围**（**计划**）：逐字抬升（float）、长音强调曲线、持续时间缩放、末词增强，
由 TypeScript 改写为 Kotlin 按播放时间采样，接入 Java2D 字形绘制与单行换行动画。
未嵌入 AMLL 的 DOM、WebGL 或第三方弹簧实现，未使用 WASM 产物。

**本项目实际履行的义务**（**⚠️ 前提：`ui/AmllMotion.kt` 存在并随包 —— 该前提当前不成立**）：

1. `ui/AmllMotion.kt` **保持 AGPL-3.0-only**，文件头写明许可，
   **不并入也不能并入 GPL 自有部分**；
2. 依据 GPLv3 §13 与 AGPLv3 §13 **组合分发**（不同许可的模块聚合在一个程序里，
   各自许可互不影响）；
3. **两份许可全文**（`licenses/AGPL-3.0.txt` 与 `LICENSE`）随包；
4. ~~**完整对应源码**随包（`source/` 中的源码压缩包，含 `AmllMotion.kt` 与构建脚本）~~ →
   **⚠️ 2026-09-28 实测：源码包（180 条目）内不含 `AmllMotion.kt`**（因为该文件不存在）。
   **其余对应源码确实随包**（`LICENSE`/`NOTICE`/`build.gradle.kts`/`gradle*`/`licenses/`，以及
   `source/lgpl/jaudiotagger-3.0.1-sources.jar`）⇒ **本条当前"无可对应的 AGPL 源码"**；
   **移植 `AmllMotion.kt` 的那次改动必须同时把它补进源码包**（否则本条立刻变成真的违规）。
5. 在 `NOTICE` 记录来源、参考版本、移植日期与改动内容；
6. 插件的「关于」对话框提供许可证通告入口（不得只有文件、没有界面可达路径）。
   **⚠️ 2026-09-28：该入口当前对用户输出的是"AMLL 歌词动画模块为 AGPL-3.0-only"
   （`SuperIslandRuntime.kt:1084`）—— 该表述与本节前提声明冲突，需改为条件式（待办）。

> **注意**：AGPLv3 §13 的「网络交互提供源码」义务对本插件（桌面离线插件、
> 不通过网络提供服务）不触发，但**保留源码随包**是成本最低的做法，也是组合分发的前提。

---

## 2. CC BY-SA 4.0 —— 强约束，不得追加限制

### 2.1 歌词悬浮岛的原创交互概念（Lyricify / WXRIW）

| 项 | 内容 |
| --- | --- |
| 原创 | Lyricify / WXRIW（XY Wang），首次创作于 2022-09-30，发布于 Lyricify 3.8.1-release |
| 版权行 | `歌词悬浮岛的原创交互概念出自 **Lyricify / WXRIW（XY Wang）**` |
| 来源 URL | https://github.com/WXRIW/Lyricify-App#lyricify-原创 |
| 许可 | **CC BY-SA 4.0**，全文见 [`licenses/CC-BY-SA-4.0.txt`](licenses/CC-BY-SA-4.0.txt) |
| 本项目中的载体 | 视觉与交互改编、`NOTICE` / 本文件等文字材料、菜单观感 |
| 随包 | 是（随文档与程序外观） |
| 需附源码 | 不适用（**按「与二进制同等可得」处理**：文字材料随包） |

**本项目实际履行的义务**：

1. **署名**：在 `NOTICE`、本文件与「关于」对话框保留 Lyricify / WXRIW 署名与来源链接；
2. **许可标识**：保留许可名称与全文链接；
3. **改动说明**：说明本项目的视觉与交互是**独立实现与改造**，非原作复制；
4. **同许可（ShareAlike）**：改编部分**继续以 CC BY-SA 4.0 提供**，
   不因整体程序采用 AGPL-3.0-only 而改变（Creative Commons 将 GPLv3 列为
   [BY-SA 4.0 Compatible License](https://creativecommons.org/compatible-licenses/)，
   代码贡献采用 GPLv3 **不替换**原创作的 CC BY-SA 许可）；
5. **不得追加限制**：**不对此许可内容追加限制其权利的条款或技术措施**
   （包括但不限于：不增设 DRM / 不禁止再分发文字材料 / 不限制商业使用 /
   不要求额外授权才能获取许可内容）。

### 2.2 关于菜单体系的 CC BY-SA 定界（用户已拍板 2026-09-15）

原实现 0.8.9 个人改造版声称其「视觉与交互改编」沿用 Lyricify 的 CC BY-SA 4.0。
本项目对 0.8.9 菜单体系采取「**不完全吸收，以运行流畅和良好视觉效果（UX 视角）
选择性吸收**」的口径：

- **吸收的是工程机制**：`KEEP_OPEN`、`doClick` 防双翻转、`Insets` 归零、
  子菜单折叠定时器、hi-DPI 坐标校正、`fitPopupToWorkArea` —— 这些是通用工程解法，
  与 Lyricify 视觉无关，且来源为 0.8.9 源码（当时为 GPL-3.0-only，现按 AGPL 分发）；
- **不照搬像素级视觉皮肤**：菜单配色、圆角、行高、悬停动效由本项目自有的
  菜单外观参数决定；
- **但本文件仍按保守写全 CC BY-SA 段落**：写多了只是文档冗余，写少了是法务暴露。
  若日后判定不构成改编，删段容易；反之补段就会成为「事后补救」。

**不得暗示原作者认可**：本项目与 Lyricify、WXRIW 无隶属、赞助或背书关系；
`NOTICE` 中「参考其原创概念并做独立实现」的措辞**不表示原作者认可本项目**。
**不得使用 Lyricify 程序、Logo、截图或未公开源码**；不随包分发 Lyricify 任何二进制。

> ⚠️ 若日后决定**照搬**「三列网格 + 悬停圆角条」这套观感，则第 2.1 节全部义务
> 照样适用且更需要显式声明，**本节的保守写法即为正确口径**，无需改文档。

> ### ★ 2026-09-28 补：本节的"选择性吸收"**已有实测依据**（不再只是口径）
>
> 上文的定界原先建立在**文字口径**上。2026-09-28 用**具名常量逐值比对**做了取证：
>
> - 与原实现 开发期取证记录 比对 ⇒ **同名常量 12 个，
>   其中 11 个逐值完全相同**：`SETTLE_DELAY_MS=120` · `defaultRowHeight=28` ·
>   `compactRowHeight=24` · `rowStart=8`/`rowEnd=10` · `separatorHeight=9` ·
>   `stepperSlotWidth=26` · `glyphBox=16`/`glyphGap=6` · `rangeGap=8` · `stepperGap=3`
>   （唯一不同：`menuPadding` 我方 5 / 原实现 10）。
> - ⇒ **这些是菜单观感的特征性视觉/几何值，成组逐值相同** —— 即**确实吸收了一部分"视觉表达"**，
>   而不只是工程机制。**对原实现构成改编**；而该文件属 **H-2OS**（开发期取证记录 的 git 证据，`7bc0183`，
>   **GPL-3.0-only**）⇒ **GPL-3.0 → GPL-3.0 兼容，署名已履行 ✓**。
> - ★★ **L5 再上溯一层（同日，用 `原仓库快照` 的 104 提交历史）**：
>   该文件在 H-2OS 之前有一个 **157 行的前身**（`1c0201d`，作者 原实现），
>   前身**只有 7 个常量，且全是配色/圆角**（`surface`/`outline`/`hover`/`text`/`muted`/`accent`/`popupArc`）；
>   **我方那 11 个命中值在前身里一个都不存在**（全是 H-2OS 于 `7bc0183` 新增），
>   而**前身那 7 个配色/圆角常量，我方一个都没沿用**。
>   ⇒ **结论细化**：我方吸收的是 **H-2OS 新写的「几何/尺寸」组**，**未沿用「配色/圆角」组**；
>   且 H-2OS 的复刻对象是 **Windows 11 原生菜单**（文件名与常量语义均为系统级尺寸），
>   **不是在复刻 Lyricify 的某个具体表达**。
> - **依据必须写全（勿只写"数值"）**：构成改编的完整依据 = **① 11 个视觉常量逐值相同
>   ＋ ② 27 个函数名 / 56 个变量名与原实现重合**（含 `fitPopupToWorkArea` / `collapseAbandonedSubmenus` /
>   `pointerInsideMenuTree` / `paintStateGlyph` 等原实现专有命名）。**单论数值**，
>   本项目在 `KaraokeTextRenderer.kt` 处已表过"**数值本身不构成受版权保护的表达**"的口径 ——
>   两处口径**可并存**，但**只写数值会与那处冲突**。
> - ⚠️ **仍未闭合的一环**：原实现自述其视觉沿用 **Lyricify 的 CC BY-SA 4.0**，但工作区**没有 Lyricify 源码**
>   ⇒ "H-2OS 是否参考过 Lyricify"属**人的意图**，**无法从代码取证**。
>   ⇒ **对 Lyricify 是否构成改编：证据不足以认定（本次由"保守认定构成"上调为"存疑"）**，
>   但因**无法最终否证**，**声明仍按保守写全**（写全的成本是文档冗余，写漏的代价是法务暴露）。
> - **完整判据与方法**（含 L1–L5 五层载体、三方逐值对比表、复现命令、触发条件）见
>   [`../开发期取证记录许可合规核对清单.md`]（开发期取证记录） **§E9**。

### 2.3 0.8.9 菜单体系的作者溯源（2026-09-19 git 提交历史勘误）

原实现 0.8.9 个人改造版及其菜单体系（`Windows11PopupStyle` 534 行版 /
`StepperItem` / `KeyboardCapture` / `GlobalMenuDismisser` / `SystemTheme` /
`IslandSettingsMenu` / `SwingTrayPopup`）的**实际作者为 H-2OS（自宅警備員）**，
不是原实现 —— git 取证：`Windows11PopupStyle.kt` 534 行版 = 提交 `7bc0183`、
`StepperItem.kt` = `8c02841`、`insideAnyPopup` / `KeyboardCapture` /
`SystemTheme` = `3a347d5`（均为 2026-09-12）。详见 `原实现血统取证报告`。

- **菜单体系文件**：版权归 H-2OS，本插件复用之属**作者使用自己的代码**，
  不构成对 原实现的 GPL 衍生义务；文件头的 `SPDX-License-Identifier: GPL-3.0-only`
  系其作为原实现 GPL-3.0-only 仓库组成部分时的许可声明，保留不变；
- **两处通用算法的血统与许可**：`host/HostPlaybackProbe.kt`（`HostLyricsProbe`
  约 50 行功能趋同）与 `platform/CoverColorExtractor.kt`（**约 9 条结构行**逐字相同 ——
  2026-09-28 实测更正，原记"约 5 行"）。血统来源如实保留；**经作者确认，这两处亦归
  H-2OS（自宅警備員）所有，按 AGPL-3.0-only 分发**；
  （复核细节与"事实 vs 表达"分解见 [`../原实现血统取证报告`](../原实现血统取证报告) 的**独立复核块**）
- **来源行区分**：原实现 表达用
  `// Ported from a GPL-3.0-only third-party implementation; see NOTICE.`；
  H-2OS 菜单体系用
  `// Ported from 0.8.9 mod (H-2OS / 自宅警備員, commits 7bc0183/8c02841/3a347d5); see NOTICE.`。

---

## 3. LGPL-2.1-or-later —— 强约束，必须附许可全文与对应源码

### 3.1 Jaudiotagger

| 项 | 内容 |
| --- | --- |
| 组件 | Jaudiotagger |
| 版本 | **3.0.1**（未修改） |
| 版权行 | `Copyright Paul Taylor and contributors.` |
| 来源 URL | https://www.jthink.net/jaudiotagger/ |
| 坐标 | `net.jthink:jaudiotagger:3.0.1` |
| 许可 | **LGPL-2.1-or-later**，全文见 [`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt) |
| 本项目中的用途 | 只读本地音频标签（时长、内嵌封面），处于**动态链接**（独立 JAR）状态 |
| 随包 | 是（`lib/jaudiotagger-3.0.1.jar`） |
| 需附源码 | **是**（LGPL-2.1 §6） |

**本项目实际履行的义务**：

1. **附许可全文**：`licenses/LGPL-2.1.txt` 随包；
2. **附对应源码**：`source/jaudiotagger-3.0.1-sources.jar`
   （Maven Central 的 `net.jthink:jaudiotagger:3.0.1:sources` 原版源码 JAR，
   **必须是该版本的对应源码，不能用其他版本代替**）；
3. **可替换性**：该库**未作修改**，以独立 JAR 形式放在 `lib/`，
   不与本项目代码合并编译，用户可自行替换该 JAR；
4. **不改变其许可**：分发时不得把该 JAR 声明为本项目自有代码或改成 GPL-only；
5. 在 `NOTICE` 与本文件记录其许可、用途与源码位置。

> `LGPL-2.1.txt` 全文**只有一份**，JNA 双许可中的 LGPL 选项与 Jaudiotagger
> 共用这一份，无需也无法分开——已核对 JNA 的 `JNA-LICENSE.txt` 指向同一份 LGPL 2.1。

---

## 4. MIT —— 弱约束（但不可移除声明）

### 4.1 SPW Lyrics（融合的歌词搜索组件）

> ## 🔴 前提声明（**2026-09-28 实测更正 —— 与 §1.1 同一类问题**）
>
> **本节描述的组件当前并未进入本插件。** 三处实证：
> ① **实码**：我方 72 个源文件中 `既有包名` / `CrossSourceLyricsAligner` /
> `LyricsLoadCoordinator` / `LyricsResolver` 等原实现类名 **命中 0**（`lyrics/` 子系统未移植）；
> ② **本项目的权威判定**：开发期取证记录 §4.4 写"**可移植**，但必须保留 MIT + GPL 署名"（=尚未移植）；
> 开发期取证记录 §A.2 把 `lyrics.source.policy` 标为「❌**首版不做**」「🔴未规划」，
> 且 §Q1 用户已拍板取"**严格 21 项**"口径 ⇒ **不做歌词源子系统**；
> ③ **上层遗留**：开发期取证记录 §A.2 已证"**已发布 0.8.9 根本没有这一页**"—— 该子系统只存在于
> 0.8.0 **融合参照树**（`legacy` = `…-0.8.0-LyricsMerge/`），那是**参照物、不是本插件的代码**。
>
> **⇒ 本节的正确性质是「预留义务」而非「已履行义务」**；以下三处表述已按事实更正：
> ① ~~「本项目中的载体 = 移植进本插件的多来源歌词搜索组件（约 49 个文件）」~~ → **未移植**；
> ② ~~「随包 = 是」~~ → **实测否**（`classes/` 内无该组件）；
> ③ ~~「需附源码 = 是（已并入 GPL 部分，随 `source/` 一并提供）」~~ → **无该组件可对应**。
>
> ### ⚠️ 一处**必须登记的相邻性**（2026-09-28 全量复核发现，**不得写成"已确认无关"**）
>
> 「未移植」**只对「模块级」成立** —— `lyrics/` 子系统确实**不在包内**（实证）。
> **但**：`playback/LyricLine.kt` 与 spw-lyrics 的 `lyrics/domain/LyricsModel.kt` 存在**命名相邻性**：
>
> | 维度 | 结果 |
> |---|---|
> | 类名 `LyricWord` / `LyricLine` | **相同** |
> | `LyricWord` 三字段（`startMs`/`endMs`/`text`）名·型·序 | **完全一致** |
> | `LyricLine` 前 5 个字段名 | 相同（顺序不同、可空性不同） |
> | `startMs`/`endMs` 是否宿主字段名 | **否** —— 宿主 `Cell` 实为 **`startTime`/`endTime`**（`javap` 官方 jar 实测）⇒ **不能**用"双方各自对齐宿主事实"归零此项 |
>
> **判读：`Δ` 相邻但不足以认定为移植**（顺序不同 + 我方另扩 6 个翻译字段 + 领域命名表达空间小）。
> **⇒ 处置**：登记为**待观察**；**MIT 的实质义务是署名**，故此处**登记 + 保留声明**比强行二选一更负责。
> 完整证据、反向证据与后续取证步骤见
> [`../开发期取证记录血统逐值复核-四层过滤法.md`]（开发期取证记录） **§4**。
>
> **⚠️ 保留 `licenses/MIT-SPW-Lyrics.txt` 的理由**：与 §1.1 的 AGPL 同理 —— **预留**
> （一旦移植，MIT 署名义务立刻生效，且**署名义务是 MIT 唯一的实质义务**）。
> **本文件不因该保留而声称"当前使用了 SPW Lyrics"。**

| 项 | 内容 |
| --- | --- |
| 组件 | SPW Lyrics（多来源歌词搜索与缓存） |
| 版本 | 0.3.0 起融合于**原实现 0.8.0 融合参照树**（称融合副本 `0.9.0-merged`）—— **本插件尚未采用** |
| 版权行 | `Copyright (c) 2026 原实现贡献者` |
| 来源 URL | （原实现公开仓库） |
| 许可 | **MIT**，全文见 [`licenses/MIT-SPW-Lyrics.txt`](licenses/MIT-SPW-Lyrics.txt) |
| 本项目中的载体 | **⚠️ 尚未移植**（`lyrics/` 子系统未进入本插件；原写"约 49 个文件"不成立） |
| 随包 | **否（实测）** —— 原写"是" |
| 需附源码 | **暂不适用**（无可对应的 MIT 组件）；**若日后移植则立即适用** |

**本项目实际履行的义务**（**⚠️ 前提：`lyrics/` 子系统被移植进本插件 —— 该前提当前不成立**）：

1. **保留 MIT 版权声明与许可全文**：`licenses/MIT-SPW-Lyrics.txt` 随包且
   **不得删除、不得改写版权行**；
2. **单向兼容声明**：MIT → GPL-3.0 为**单向兼容**，融合后的文件以 GPL-3.0-only 分发，
   同时保留 MIT 声明；
3. **说明改动**：在 `NOTICE` 说明融合范围（改包名、接入时间轴、去掉 WinUI 伴生程序
   与反射桥等）。

> **同作者事实**：`SPW-Lyrics` 与原实现词岛**同为 原实现 作品**，故两者之间的代码
> 合并/再许可在版权层面几乎无障碍。**但这一便利只适用于 MIT 部分**——
> 既有的 AGPL 部分与 CC BY-SA 部分**不受影响，仍是硬约束**（见第 1、2 节）。
>
> **⚠️ 移植时的提醒**：`lyrics/` 是 **49 文件规模的子系统**（开发期取证记录 §B.5 评为"高"规模工程），
> 移植时必须**同时**：① 保留 MIT 版权行；② 在 `NOTICE` 如实说明改动；③ 把新文件纳入 `source/` 对应源码；
> ④ 重新评估本节与 §1.1 的前提（两者都是"计划态声明"，届时才转为"交付态"）。

---

## 5. Apache-2.0 —— 弱约束（保留声明与声明文件）

以下组件统一适用 Apache-2.0，全文见 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)。
Apache-2.0 的义务是：**保留版权、许可与声明文件**（§4）；
若对所分发文件作过修改，需**显著标注已修改**（§4(b)）；
若有 `NOTICE` 文件，须保留其中的署名（本项目的 `NOTICE` 已合并各处署名）。
**Apache-2.0 与 GPL-3.0 兼容**（不可与 GPL-2.0 组合，本项目不涉及 GPL-2.0）。

| 组件 | 版本 | 版权行 / 作者 | 是否随包 | 需附源码 | 本项目实际履行的动作 |
| --- | --- | --- | --- | --- | --- |
| **JNA** | 5.17.0 | Timothy Wall 及 JNA contributors | **是**（`lib/jna-5.17.0.jar`） | 否 | 双许可 `Apache-2.0 OR LGPL-2.1-or-later` 中**选定 Apache-2.0**；保留 `licenses/JNA-LICENSE.txt`（许可选择声明） |
| **JNA Platform** | 5.17.0 | 同上 | **是**（`lib/jna-platform-5.17.0.jar`） | 否 | 同上；`User32` / `Advapi32Util` 等封装 |
| **JNA 的第三方声明** | 5.17.0 | JNA contributors | **是**（随 `licenses/`） | 否 | 保留 `licenses/JNA-THIRD-PARTY.txt`（§4(d) 声明文件义务） |
| **libffi**（JNA 内嵌） | 随 JNA 5.17.0 | Anthony Green 及 libffi contributors | **是**（在 JNA JAR 内） | 否 | 保留 `licenses/JNA-libffi-LICENSE.txt` |
| **SPW Workshop API** | 0.1.0-dev20 | Copyright (C) 2025 Moriafly | **否**（宿主提供） | 否 | 保留署名；`compileOnly` 不打包，避免与宿主类加载器冲突 |
| **SPW API 构建布局示例** | 0.1.0-dev20 | Moriafly | 否（仅参考） | 否 | Gradle 插件 ZIP 布局与清单配置**改编自该示例**，已在 `NOTICE` 标注改动 |
| **PF4J** | 3.12.0 | Decebal Suiu 及 contributors | **否**（宿主提供） | 否 | 保留署名；`compileOnly` 不打包 |
| **Kotlin / kotlin-stdlib** | 编译用 2.3.0；运行时由宿主提供 | JetBrains s.r.o. 及 Kotlin contributors | **否**（宿主提供） | 否 | 保留署名；打包时过滤 `kotlin-stdlib*` 与 `annotations-*` |
| ~~**kotlinx-serialization**~~ | ~~1.9.0~~ | JetBrains s.r.o. | **🔴 否（2026-09-28 实测：本插件无此依赖）** | 否 | **原写「是（`lib/kotlinx-serialization-*.jar`）；用于歌词缓存与提供方响应 JSON」—— 不成立**：`build.gradle.kts` 里 `kotlinx-serialization` **出现 0 次**，`lib/` 内无该 jar，源码无 JSON 库调用（开发期检查记录明写"没有 gson/kotlinx-serialization"） |
| ~~**opencc4j**~~ | ~~1.14.0~~ | houbb 及 contributors | **🔴 否（2026-09-28 实测）** | 否 | **原写「是（`lib/opencc4j-1.14.0.jar`）；繁简/异体字归一化」—— 不成立**：构建脚本 0 次出现；且源码里**没有任何繁简/异体归一化实现**（`grep 繁简\|简体\|繁体\|异体\|simplif` 只命中"简体中文字体"这类字样） |
| ~~**heaven**~~ | ~~0.13.0~~ | houbb 及 contributors | **🔴 否（随 opencc4j 一并消失）** | 否 | 原写「是（opencc4j 的传递依赖）」—— **opencc4j 都不是依赖，传递依赖自然不存在** |
| ~~**nlp-common**~~ | ~~0.0.5~~ | houbb 及 contributors | **🔴 否（随 opencc4j 一并消失）** | 否 | 同上；原写「本项目未直接调用」—— 实际**连依赖都不是** |
| **Gradle** / Gradle Wrapper | Wrapper 来自 SPW API 0.1.0-dev20，构建使用 9.2.1 | Gradle contributors | 否（发行包不内嵌 Gradle 发行版） | 否 | Wrapper 自带声明保留；Wrapper 配置已修改（→ Gradle 9.2.1 + 发行版 SHA-256 校验），属 §4(b) 的「显著标注已修改」，已在 `NOTICE` 声明 |

> ~~**待核实（不猜）**：`heaven` 0.13.0 与 `nlp-common` 0.0.5 的**实际许可版本**未逐字节核对过…~~
> **🔴 2026-09-28 实测更正：该"待核实"项已无对象（moot）** ——
> `heaven` / `nlp-common` / `opencc4j` **三者都不是本插件的依赖**
> （`build.gradle.kts` 内 0 次出现；`lib/` 仅 `jaudiotagger` + `jna` + `jna-platform` 三个 jar）。
> 故**无需再核它们的许可**；本文件 §1–§5 中凡列出"随包"而实物不存在的条目，均已就地更正。

> **★ 本节的结构性问题（2026-09-28 登记，供后续收口）**：
> 实测 `implementation(` **共 3 个**（= `lib/` 三个 jar），而本节曾列出 13 行组件 ——
> 差额来自**沿袭原实现 0.8.0 融合树的依赖/许可表**，而本插件是**自研重写** ⇒ 条目在实物里没有对应组件。
> **⇒ 判据（可机械核对）**：`THIRD_PARTY_NOTICES` 里每一条标"随包 = 是"的行，
> 都必须能在 `lib/` 或 `classes/` 里找到实物；找不到的一律不得写"是"。
> **本节现已按该判据逐条核对**（另见 §1.1 / §4.1 的前提声明）。

---

## 6. 依赖的传递性说明

- ~~**kotlinx-serialization / opencc4j 的传递引入**：打包时会把 `kotlin-stdlib`、`annotations-*` 一并带入 `lib/`…~~
  **🔴 2026-09-28 实测更正**：`kotlinx-serialization` 与 `opencc4j` **不是本插件的依赖**
  （`build.gradle.kts` 内零出现），**不存在"由它们传递引入"这回事**。
  **`kotlin-stdlib` / `annotations-*` 是 Kotlin 工具链自身带入的**（`compileOnly(kotlin("stdlib"))`，
  宿主已提供），故 `lib/` 必须过滤 —— **过滤理由与此前写的两个组件无关**，结论（要过滤）不变。
  实测 `lib/` 最终只有 3 个 jar：`jaudiotagger-3.0.1.jar`、`jna-5.17.0.jar`、`jna-platform-5.17.0.jar`。
- **JNA 的传递依赖**：JNA Platform 依赖 JNA，两者均随包，均按 Apache-2.0 处理。
- **jaudiotagger 无传递依赖**需要额外声明（其自身即独立 JAR）。

---

## 7. 分发

插件 ZIP 内含 `LICENSE`、`NOTICE`、本文件、`licenses/` 目录，
以及 `source/` 目录中的对应完整源码压缩包（含构建脚本、Wrapper
与 `jaudiotagger-3.0.1-sources.jar`）。

转发二进制时保留这些材料；**修改后重新构建也应提供对应源码、保留署名并说明改动**。
不得对 CC BY-SA 内容追加限制该许可权利的条款或技术措施。

运行时所用 Windows 和 Java 由其原供应方提供，遵循各自条款。
~~本插件不附带音乐、封面或歌词内容，也不改变这些内容的权利归属。~~
**⚠️ 2026-09-28 更正**：**本插件不附带音乐或歌词内容**（实测 `classes/` 内无音乐/歌词文件）；
**但确实附带一张演示封面** —— `classes/demo/cover-demo.jpg`（171,735 B，用户提供并由本项目
加工成派生副本，**仅用于取色窗的「词岛示例预览条」**，见 §10）。
该素材**不在本文件 §1–§5 的许可体系内，也不受 GPL 覆盖**；其权利归属**不因随包而改变**。

---

## 8. 待核实事项汇总

| # | 事项 | 现状 | 核实方法 | 若结论不同的影响 |
| --- | --- | --- | --- | --- |
| C1 | ~~`heaven` 0.13.0 的实际许可~~ | **✅ 已无对象（2026-09-28 实测）**：`heaven` **不是本插件的依赖**（构建脚本 0 次出现）⇒ 无需核许可 | 无需核实 | 见 §5 的"结构性问题"块 |
| C2 | ~~`nlp-common` 0.0.5 的实际许可~~ | **✅ 已无对象（同上）** | 无需核实 | 同上 |
| C3 | ~~`kotlinx-serialization` 1.9.0 随包的具体 JAR 文件名~~ | **✅ 已无对象（同上）**：无该依赖 ⇒ 无 jar 名可定 | 无需核实 | 同上 |
| C4 | `jaudiotagger-3.0.1-sources.jar` 是否真正进入 `source/` | **✅ 已闭合（2026-09-28 实测）**：`source/lgpl/jaudiotagger-3.0.1-sources.jar` **确实随包**（1,031,461 B）；构建脚本已有 `fetchLgplSources` + `verifyLgplSources` 两个任务守在 `plugin` 的 `dependsOn` 里 | 已实测 | LGPL-2.1 §6 义务已履行 |
| C5 | 改造者署名文字 | `NOTICE` 中为 `<AUTHOR>` 占位 | 由用户确认 | 不影响 GPL-3.0 §5(a) 的改动说明义务 |
| C6 | 0.8.9 的「垂直锚点」功能是否为原实现旧版本（如 0.6.4）已有 | **已核实（2026-09-19）**：原实现完整 git 历史显示 `IslandAnchor.kt` 为 **H-2OS** 在 PR#13 新增（本人代码），「很可能是原实现旧功能、被改造者恢复」的判断**作废** | 原实现完整 git 历史（60 个提交）已取证 | `NOTICE` 改动说明中该项按 H-2OS 本人代码表述，无需再标注「恢复的原实现旧功能」 |
| C7 | 随包分发 MiSans 字体是否违反其许可 §2 第 3 条（「不得进一步分发」） | **已闭合（2026-09-22）—— 通过「取消随包分发」解决，而非通过解释协议**：用户 2026-09-22 拍板「**取消自带字体包，调用自带字体包的路线转到在本机选择的字体上**」⇒ `src/main/resources/fonts/`（10 个 TTF，约 9.4 MB）与 `font/BundledFonts.kt` **均已删除**，包内**不再有任何 MiSans 字体副本**。第 3 条是否覆盖「原样打包 `.ttf`」这个文本歧义（见 §9.2）**就此不再需要回答** —— 没有副本，就没有分发 | 无需再就 §9.2 作出解释：该未决项的**前提已消失**。验收判据：classpath 上不得出现 `/fonts/*.ttf` | 无（风险已消除）。⚠️ 若日后**再次**随包分发字体，本项与 §9.2 必须**整段重新评估**，不得默认沿用旧结论。★ **2026-09-28 补**：本条「已闭合」与 §9.2 原标题「未决」、`licenses/README.md` §2 第 9 行「即违约」**是三种口径、从未收敛** ⇒ 见 **§9.2 的 2026-09-28 补注**（含重开风险与"复用宿主 `misans.ttf`"的规避路径） |
| C8 | `licenses/` 缺少 MiSans 协议全文与版权声明 | **已补（2026-09-19）**：新增 `licenses/MiSans-LICENSE.txt`（中英全文转写）与 `licenses/MiSans-LICENSE.pdf`（官方原件，79,535 字节，SHA-256 `4a93a27cd2bd81b3b5ecfd0a853144a876fa26938a93a68443c67d74172fcb86`）；并由 `plugin` 任务的**机械断言四**保证随包 | 已由 `build.gradle.kts` 的打包断言**机械保证**（不靠记性） | 已闭合。若日后移除该断言，见 §9.3 |

---

## 9. 插件自带字体（MiSans 系列）—— **⚠️ 已整体废止（2026-09-22）**

> ## ⛔ 本节所述「随包分发字体」的做法**已于 2026-09-22 取消**，仅作历史留存
>
> 用户原话：「**取消自带字体包，调用自带字体包的路线转到在本机选择的字体上**」。
> 落实情况：
>
> | 对象 | 处置 |
> | --- | --- |
> | `src/main/resources/fonts/`（10 个 MiSans TTF，**9,415,472 字节**） | **已删除**（整个目录） |
> | `font/BundledFonts.kt`（映射表 / `resolve` / `isBundled`） | **已删除** |
> | `main_lyric.font.family` 出厂默认值 | `MiSans` → **`Microsoft YaHei UI`**（本机字体，见 `font/FontDefaults.kt`） |
> | `FontBundleProbe`（11 条断言全关于随包字体） | **已删除**，职责由 `FontDefaultProbe` 承接 |
>
> ### 为什么这是一次「许可 + 体积」双赢，而不是单纯的删功能
>
> - **许可**：C7 那条**未决项**（第 3 条是否禁止「原样打包 `.ttf`」，见 §9.2）
>   的前提是"包内有字体副本"。**副本没了，歧义也就不需要解释了** ——
>   我们不再需要在一个文本无唯一解的条款上下注。
> - **体积**：9.4 MB → 0。
>
> ### ⚠️ 代价（如实记，不要当成没发生）
>
> 随包字体的**原始理由**正是"用户不必自己装字体、**开箱就有同一套观感**"。
> 取消之后，**不同用户的默认观感会不同**（各自本机字体不同）。
> 即「同一版插件在任何机器上画出来的字完全一致」这句话**从今天起不再成立** ——
> 凡引用该结论的注释/文档都必须同步改掉。
>
> ### ⚠️ 本节以下内容**全部是历史记录**，描述的是一份**已经不存在的包**
>
> 保留它们的原因有二：⑴ C7/C8 的结论链需要可追溯；
> ⑵ **若日后决定重新随包分发字体，这一段是现成的评估材料** ——
> 但那时**必须重新评估**（含协议是否仍为同一版本），不得默认沿用。

本节由字体打包任务（2026-09-19）新增。**MiSans 不是开源许可**，
不落入本文件第 1–5 节的任何一类，故单列一节。

（历史）插件 ZIP 的 `classes/fonts/` 内**随包分发 10 个字体文件**（合计约 8.98 MB），
目的是让插件在用户**未安装**这些字体时也能按设计观感渲染歌词
（渲染入口：`font/BundledFonts.kt`，`main_lyric.font.family` 出厂默认为 `MiSans`）。

| 随包文件 | 字体族名（JDK `Font.getFamily()` 实测） |
| --- | --- |
| `MiSans-Regular.ttf` | `MiSans` |
| `MiSansArabic-Regular.ttf` | `MiSans Arabic` |
| `MiSansDevanagari-Normal.ttf` | `MiSans Devanagari Normal` |
| `MiSansThai-Normal.ttf` | `MiSans Thai Normal` |
| `MiSansTibetan-Regular.ttf` | `MiSans Tibetan` |
| `MiSansGujarati-Normal.ttf` | `MiSans Gujarati Normal` |
| `MiSansGurmukhi-Normal.ttf` | `MiSans Gurmukhi Normal` |
| `MiSansKhmer-Regular.ttf` | `MiSans Khmer` |
| `MiSansLao-Normal.ttf` | `MiSans Lao Normal` |
| `MiSansMyanmar-Regular.ttf` | `MiSans Myanmar` |

### 9.1 许可与条件

| 项 | 内容 |
| --- | --- |
| 权利人 | **小米科技（Xiaomi Inc.）** |
| 协议 | 《MiSans 字体知识产权许可协议》（`MiSans Font Intellectual Property License Agreement`） |
| 协议原文 | **随包**：`licenses/MiSans-LICENSE.pdf`（官方原件）+ `licenses/MiSans-LICENSE.txt`（中英全文转写） |
| 官方来源 | https://hyperos.mi.com/font-download/MiSans字体知识产权许可协议.pdf （2026-09-15 取回，79,535 字节） |
| 授予 | `a nontransferable, nonexclusive, royalty free, **revocable** and global copyright license to use the MiSans fonts` —— 不可转让、非独占、免版税、**可撤销**、全球 |
| 准据法 | 中华人民共和国法律；争议由北京市海淀区人民法院管辖 |

协议 §2 以「须满足下列条件」为前提授予上述许可（英文本逐字摘录）：

1. `You shall specifically indicate in the Software that You are using MiSans fonts`
   —— **必须在软件中声明使用了 MiSans 字体**；
2. `You shall not adapt or redevelop MiSans fonts or any of their individual components`
   —— 不得改编或二次开发字体；
3. `You shall not individually rent, sublicense, give, loan or further distribute the
   MiSans fonts or their components or any copies thereof, nor shall you redistribute
   or sell them. This restriction does not apply to any other work that You create
   using MiSans fonts. For example, if You use MiSans fonts to create promotional
   materials, logos, applications (Apps), etc., You shall have the right to distribute
   or sell that work`
   —— 不得转租/再许可/赠与/出借/**进一步分发**字体或其任何副本，不得再分发或销售；
   **但**该限制不适用于「使用 MiSans 字体创作的**其他作品**」，例如用其创作的
   宣传物料、Logo、**应用程序（App）** 等，该类作品可自由分发或销售；
4. `You shall retain the copyright notice and this Agreement in any copies of MiSans fonts`
   —— **随字体副本必须保留版权声明与本协议全文**；
5. `You shall not use MiSans fonts for any illegal purposes`

### 9.2 第 3 条对本插件的适用（**原为"未决"；前提已随「取消随包分发」消失**，2026-09-22）

> **⚠️ 2026-09-22：本小节的问题已随「取消随包分发」而失去前提**（见本节顶部横幅）。
> 以下文字保留，是为了记录"这个歧义曾经存在、以及它为什么不必再回答"。

> ## ⚠️ 2026-09-28 补注：**口径尚未收敛 + 重开风险与规避路径**（如实登记）
>
> **① 三处口径不一致，从未收敛**（本条与 C7、与 `licenses/README.md` §2 第 9 行**互相矛盾**）：
>
> | 出处 | 读法 |
> |---|---|
> | 本小节**原标题** | 「**未决**…不做「已合规」结论」⇒ 文本无唯一解 |
> | 本文件 **C7 行** | 「**已闭合**（2026-09-22）…该歧义**不再需要回答**」⇒ 回避 |
> | `licenses/README.md` §2 第 9 行（原句） | 「分发字体副本**即违约**」⇒ 明确**禁止** |
>
> **当前无实际影响**（包内已无字体副本，三种读法都得出"现在合规"）；
> **但重新随包分发字体前必须先收敛** —— 否则三处会给出三种不同的行动指令。
> **协议原文（§2 第 3 条）** 为「…**further distribute** the MiSans fonts or their components,
> **or any copies thereof**, nor shall you **redistribute** or sell them」，
> 豁免句仅覆盖「使用 MiSans 字体**创作的作品**（宣传素材 / logo / App）」——
> **按字面 `.ttf` 属 "copies thereof"，不落在豁免句内**；本项目按"未给出唯一解"处理至今。
> **该判断属法务结论，留待用户/权利人澄清，本文不代为裁决。**
>
> **② 重开风险与一条已实测的规避路径（重要，且是好消息）**：
> 迁移期第 4 期的候选方案里，**C2/档 1（自带 Skiko，+11.08 MiB）与 C5（自带整套 Compose，+23.2 MiB）
> 都会碰到"字体一致性"这个轴** —— 一旦选择"自带字体"，本项与 §9.2 **必须整段重新评估**（见 C7 行末）。
> **但 `migration/32` 已实测出一条规避路径**：**宿主 jar 内自带 35 个字体（18.88 MiB），
> 其中含 `misans.ttf`（7,924,400 B）**，且 `ClassLoader.getResourceAsStream(...)` 在插件侧**可读**
> ⇒ **复用宿主那份字体字节即可获得"与宿主一模一样的观感"，不必自带 20 MB，也就不会触发本项。**
> ⇒ **结论：迁移不必然重开 MiSans 授权问题**，前提是把"字体一致性"走**复用宿主字体**而不是**自带**。

第 3 条前半句禁止「进一步分发 MiSans 字体…或任何副本」，
后半句豁免句又明确允许分发「使用 MiSans 字体创作的**应用程序（Apps）**」。
**「把 `.ttf` 原样放进插件包」究竟落在禁止项还是豁免项，协议文本本身未给出唯一解**
（豁免句的主语是「用字体**创作**出的作品」，而随包的是**字体文件本身**）。
故此处**只如实登记，不自行解释**：本插件按「用户明确要求随插件分发」的既有决定执行，
该适用性问题**需由用户/法务拍板**，结论未定前**不得**宣称本项已合规。
另注：该许可**可撤销**（`revocable`），且协议**未**授予字体文件层面的再分发明示权利。

### 9.3 已履行 / 待补（**历史**：随包分发期间的状态）

> ⚠️ 2026-09-22 起**包内已无字体副本**，第 1/2/4 条的义务**随之不再被触发**
> （协议第 4 条的义务是「**随字体副本**保留版权声明与协议」—— 没有副本就没有这项义务）。
> 下面四条记录的是**取消之前**的履行状态，留作可追溯。

- ✅ **第 1 条（声明）**：本节 + `NOTICE`「第三方组件」首条均已声明
  **「本软件使用了 MiSans 字体」**（2026-09-19 补齐 `NOTICE`）。
- ✅ **第 2 条（不改编）**：10 个 `.ttf` **逐字节原样**打包，未改编、未重新生成、未做子集化。
- ✅ **第 4 条（随副本附协议）**：`licenses/MiSans-LICENSE.txt`（中英全文转写）
  与 `licenses/MiSans-LICENSE.pdf`（官方原件）已随包。
  `plugin` 任务的**机械断言四**会在「包内有字体但缺这两件」时**使构建失败** ——
  这条守的是**法务义务**且**零运行时症状**，只能靠断言暴露。
- ⚠️ **第 3 条**：适用性未决，见 §9.2，**未作合规结论**。

> **转写件说明**：`MiSans-LICENSE.txt` 由官方 PDF 原件解码而来
> （PDF 为 `Identity-H` + 自带 `ToUnicode` CMap，可精确解码），
> 仅将 PDF 子集字体里被映射到 **Kangxi 部首 / CJK 部首补充区**的 19 个字符
> 归一回其规范汉字（如 `⼩⽶`→`小米`、`⺠`→`民`），全角标点原样保留。
> **发生歧义时以随包的官方 PDF 原件为准。**

### 9.4 交付范围说明：`NotoSansKR-Regular.ttf` 缺失（按 10/11 交付）

用户指定的 11 个字体中，**`NotoSansKR-Regular.ttf` 在本地全工作区检索 0 命中**——
既未找到，也**未用他字体顶替、未从网络下载**，故**本次按 10 个字体交付**
（韩文字形改由宿主/系统字体回退承载，插件不崩、只降级）。
若日后取得该文件，按同一流程补入即可（`BundledFonts` 映射表 + `build.gradle.kts`
打包断言会自动覆盖新条目，无需另改校验逻辑）。

> ⚠️ **2026-09-28 更正**：上述"补入流程"**已随「取消随包字体」整体失效** ——
> `BundledFonts.kt` 与其映射表**均已删除**（见 §9 顶部横幅），故"按同一流程补入"**没有可操作的对象**。
> **当前正确的做法**：不要再补入自带字体（该路线已废止）；若确有韩文字形需求，
> 停在"由宿主/系统字体回退承载"这一档，或另行评估（并注意届时 §9.2 的授权口径需重新收敛）。

---

## 10. 演示素材（**不在 §1–§5 的许可体系内，不受 GPL 覆盖**）

> 本节按项目纪律「**演示素材不属 GPL，须单独声明来源**」建立（2026-09-28）。
> 在此之前，这份素材的来源说明**只存在于它自己的目录 README 里**，三份标准声明文件均未提及。

| 项 | 内容 |
| --- | --- |
| 随包文件 | `classes/demo/cover-demo.jpg`（171,735 B，1024×1024） |
| 用途 | **仅**用于取色窗口的「词岛示例预览条」渲染封面；不参与词岛实际渲染，不进入任何运行期数据 |
| 来源 | **用户提供**（原图 6000×6000 / 3,015,113 B） |
| 加工 | 面积平均（box filter）等比缩放至 1024×1024、JPEG 质量 0.92 ⇒ **派生副本**（非原始文件） |
| 权利归属 | **属于提供者**；随包分发**不改变**其权利归属，也**不**把它并入本项目的 GPL 覆盖范围 |
| 许可 | **未声明为开源**；转发/再分发本插件时，**该文件随二进制一并转发**，但**不得**据此推定其可被单独再许可 |
| 详细记录 | 见上文「加工」行（含 2026-09-22 的两次重制记录） |

**为什么单列**：它是**唯一一件**"不是代码、也不是第三方开源组件"的随包素材。
把它混进 §1–§5 的许可表会让读者误以为它受某个开源许可覆盖；不声明任何来源则违反项目自己的交付纪律。
