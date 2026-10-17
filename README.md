<!--
发布前替换清单（GitHub 不显示这段注释）：
1. 顶部 hero 图：把 docs/assets/README-hero.png 放进仓库后取消下一行注释。
2. NOTICE 里的 <AUTHOR> 占位符确认（署名待定，不影响 AGPL-3.0 §5(a) 的改动声明）。
3. 发布前确认 Releases 已上传 `cdl-for-spw-<版本>.spmod` 与对应 `-source.zip`。
-->

<!-- ![CDL for SPW](docs/assets/README-hero.png) -->

<a id="简体中文"></a>
<p align="center">
  <b>简体中文</b> &nbsp;|&nbsp;
  <a href="#繁體中文">繁體中文</a> &nbsp;|&nbsp;
  <a href="#English">English</a>
</p>

# CDL for SPW

Custom Desktop Lyrics · 为 Salt Player for Windows 提供更多桌面歌词样式选择。

[![Release](https://img.shields.io/badge/Release-Beta-orange)](https://github.com/H-2OS/CDL-SPW/releases/latest)
![Windows 10 / 11](https://img.shields.io/badge/Windows-10%20%2F%2011-0078D4)
![Salt Player for Windows](https://img.shields.io/badge/SPW-1.18.x-2ea44f)
[![License](https://img.shields.io/badge/License-AGPL--3.0--only-blue)](LICENSE)

把 SPW 当前播放的歌词显示在桌面上：一个具有多种外观选择的歌词岛。主歌词、译文、频谱、封面、背景、边框、播放控件各有独立的设置组，拥有上百种组合。

QQ 交流群：`1130794596`

> **当前为测试版（Beta）**：可能给您带来不好的体验，我们将会持续改进。
> 插件 ID 为 `dev.cdl.spw`，与其他歌词插件相互独立，可同时安装。

## 功能

- **预设系统**：把当前配置保存为预设，支持导入 / 导出（JSON 与 `键=值` 的 TXT，以文件名为预设名）；导入前可预览会改动哪些键，遇到本版没有的键会如实上报；预设可删除、覆盖、重命名；内置预设不可删除或覆盖。（布置在右键菜单中）
- **设置规模**：100+ 设置项，按 11 个组件分组（每组不超过 30 项）；常规项直接在 SPW 设置页调整，常用项也在右键菜单。
- **实时预览**：设置页内的示例词岛与真机共用同一套绘制路径，调整即时可见。
- **托盘图标**：可通过单/双击便捷地控制歌曲播放。
- **取色器**：色域板 + 色相条 + 明度条 + RGB 分组 + 十六进制输入；灰度时保留原色相。
- **字体**：系统字体、字体文件，或宿主内置字体（31 个族，含字重与斜体）；最近使用的 10 个字体单独列出；SPW 设置页的字体列表可搜索。
- **歌词**：逐字高亮（逐字 / 整行 / 平滑三档）、单行 / 双行、字号 / 字重 / 字距 / 行距、最大宽度与对齐。
- **译文**：独立开关、独立字号与颜色，可放在主歌词上方或下方，未唱段可单独淡化。
- **描边**：硬边 / 向外渐变 / 溶边三种模式；向外渐变按层递减，不使用预乘蒙版。
- **词岛**：方形 / 圆角矩形 / 胶囊，圆角支持连续曲率（squircle）；宽度、高度、透明度、内容对齐可调。
- **背景材质**：纯色 / 线性渐变 / 多色流光 / 封面迷彩碎色；流光速度可调。
- **频谱**：贴底边 / 右端五条 / 环状 / 放射四种布局；手动 / 封面 / 随音量 / 封面流光 / 碎色等五种取色；柱形圆角、实心与折线、透明度。
- **封面**：封面显示、缩放、封面取色、黑胶环、水波与封面态外观。
- **插槽**：右端插槽可在小频谱 / 环状频谱 / 放射频谱 / 时钟 / 无之间切换。
- **时钟**：12 / 24 时制；七段数码管、点阵、像素风三种字形。
- **播放控件**：悬停展开上一首 / 播放暂停 / 下一首与可拖动进度条；经典与流光两种样式。
- **菜单与托盘**：自绘右键菜单按组件分组，提供预设列表、导入 / 导出 / 删除 / 更改；托盘图标提供常用入口。
- **帧率**：可选帧率档，按实际呈现刷新率上限设档。
 ————更多设置请见实机设置页。

## 安装

需要 Salt Player for Windows **1.18.x**（Workshop API `0.1.0-dev20`）与 **Windows 10 / 11 x64**。

1. 前往 [Releases](https://github.com/H-2OS/CDL-SPW/releases/latest) 下载 `cdl-for-spw-<版本>.spmod`；或从 [Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3821829015) 订阅。
2. 在 SPW 的创意工坊 / 插件管理中导入该 zip；
3. 启用 **CDL for SPW**，播放一首已经有歌词的歌曲。

插件使用宿主自带的 Java 运行时，不需要单独安装 Java，也不访问网络。本插件只显示 SPW 已经加载的歌词，不负责搜索。

更新插件前请完全退出 SPW，替换插件包后重新启动（导入时可能会报错，无视即可）；插件文件不会被宿主热加载。

## 使用与设置

- **打开设置**：在 SPW 的插件设置页中打开 CDL 的设置页；或在词岛/托盘图标上右键打开菜单。
- **右键菜单**：按组件分组（歌词 / 词岛 / 频谱 / 封面 / 播放控件 / 动画 / 菜单与设置等），包含预设列表与导入 / 导出入口。
- **预设**：在预设列表中切换；导入时先预览差异，导出时可选择 JSON 或 TXT 格式。
- **取色器与字体**：由设置页按钮或菜单打开工具窗。
- **示例词岛**：设置页内的预览与真机使用同一套绘制路径。
- **配置文件**：位于 `%APPDATA%\Salt Player for Windows\workshop\data\dev.cdl.spw\`。

## 反馈与贡献

欢迎各位志同道合的朋友们一同共建！遇到问题或有好的功能想法，欢迎提issue或PR，或加入 QQ 群 `1130794596` 交流。

## 许可与致谢

本项目自有代码以 **AGPL-3.0-only** 发布，并依 **AGPLv3 §7(b)** 附加「显著署名」条款（分发时须在 README / 发布页 / 关于对话框等显著位置保留作者署名，详见 `NOTICE`）。第三方组件与代码来源以随包文件为准。其中 `HostLyricsProbe.kt` 与 `CoverColorExtractor.kt`（原实现 GaBoron 的两处通用算法）**保留 GPL-3.0-only 与原作者署名**，不单方面变更许可；组合作品整体按 AGPL-3.0-only 分发：

| 文件 | 内容 |
| --- | --- |
| `LICENSE` | AGPL-3.0-only 全文 |
| `NOTICE` | 署名与改动说明 |
| `THIRD_PARTY_NOTICES.md` | 第三方组件与许可 |
| `licenses/` | AGPL-3.0、Apache-2.0、CC-BY-SA-4.0、JNA、LGPL-2.1 等许可全文 |

### 致谢

- **Salt Player for Windows** 与 **SPW Workshop API** — Moriafly
- **灵动词岛（Dynamic Lyrics Island for SPW）（现SPW-island）** — GaBoron
- **Lyricify** 的视觉与交互原创 — WXRIW（XY Wang）
- **PF4J**、**JNA**、**Jaudiotagger** 及项目使用的其他开源组件
- 第三方代码的署名与改动说明：见随包 `NOTICE`
- 参与制作测试或提供意见建议的 @aodia8tssa @滨 @没有名字（QQ）等人。

CDL for SPW 是独立开发的项目，与 Salt Player 官方、灵动词岛及其他歌词插件均无隶属、赞助或背书关系。

---

<a id="繁體中文"></a>
<p align="center">
  <a href="#简体中文">简体中文</a> &nbsp;|&nbsp;
  <b>繁體中文</b> &nbsp;|&nbsp;
  <a href="#English">English</a>
</p>

# CDL for SPW

Custom Desktop Lyrics · 為 Salt Player for Windows 提供更多桌面歌詞樣式選擇。

把 SPW 目前播放的歌詞顯示在桌面上：一個具有多種外觀選擇的歌詞島。主歌詞、譯文、頻譜、封面、背景、邊框、播放控制項各有獨立的設定組，擁有上百種組合。

QQ 交流群：`1130794596`

> **目前為測試版（Beta）**：可能給您帶來不好的體驗，我們會持續改進。
> 外掛 ID 為 `dev.cdl.spw`，與其他歌詞外掛相互獨立，可同時安裝。

## 功能

- **預設系統**：把目前設定存為預設，支援匯入 / 匯出（JSON 與 `鍵=值` 的 TXT，以檔名為預設名）；匯入前可預覽會改動哪些鍵，遇到本版沒有的鍵會如實回報；預設可刪除（移入 `presets/_deleted/`，可手動移回）、覆蓋、重新命名；內建預設不可刪除或覆蓋。（佈置在右鍵選單中）
- **設定規模**：149 個設定鍵，依 11 個元件分組（每組不超過 30 項）；常規項直接在 SPW 設定頁調整，常用項也在右鍵選單。
- **即時預覽**：設定頁內的範例詞島與真機共用同一套繪製路徑，調整即時可見。
- **托盤圖示**：可透過單 / 雙擊便捷地控制歌曲播放。
- **取色器**：色域板 + 色相條 + 明度條 + RGB 分組 + 十六進位輸入；灰階時保留原色相。
- **字型**：系統字型、字型檔，或宿主內建字型（31 個族，含字重與斜體）；最近使用的 10 個字型單獨列出；SPW 設定頁的字型清單可搜尋。
- **歌詞**：逐字高亮（逐字 / 整行 / 平滑三檔）、單行 / 雙行、字號 / 字重 / 字距 / 行距、最大寬度與對齊。
- **譯文**：獨立開關、獨立字號與顏色，可放在主歌詞上方或下方，未唱段可單獨淡化。
- **描邊**：硬邊 / 向外漸層 / 溶邊三種模式；向外漸層依層遞減，不使用預乘遮罩。
- **詞島**：方形 / 圓角矩形 / 膠囊，圓角支援連續曲率（squircle）；寬度、高度、透明度、內容對齊可調。
- **背景材質**：純色 / 線性漸層 / 多色流光 / 封面迷彩碎色；流光速度可調。
- **頻譜**：貼底邊 / 右端五條 / 環狀 / 放射四種佈局；手動 / 封面 / 隨音量 / 封面流光 / 碎色等五種取色；柱形圓角、實心與折線、透明度。
- **封面**：封面顯示、縮放、封面取色、黑膠環、水波與封面態外觀。
- **插槽**：右端插槽可在小頻譜 / 環狀頻譜 / 放射頻譜 / 時鐘 / 無之間切換。
- **時鐘**：12 / 24 時制；七段數位管、點陣、像素風三種字形。
- **播放控制項**：懸停展開上一首 / 播放暫停 / 下一首與可拖動進度條；經典與流光兩種樣式。
- **選單與托盤**：自繪右鍵選單依元件分組，提供預設清單、匯入 / 匯出 / 刪除 / 更改；托盤圖示提供常用入口。
- **幀率**：可選幀率檔，依實際呈現刷新率上限設檔。
 ————更多設定請見實機設定頁。

## 安裝

需要 Salt Player for Windows **1.18.x**（Workshop API `0.1.0-dev20`）與 **Windows 10 / 11 x64**。

1. 前往 [Releases](https://github.com/H-2OS/CDL-SPW/releases/latest) 下載 `cdl-for-spw-<版本>.spmod`；或從 [Steam 創意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3821829015) 訂閱。
2. 在 SPW 的創意工坊 / 外掛管理中匯入該 zip；
3. 啟用 **CDL for SPW**，播放一首已經有歌詞的歌曲。

外掛使用宿主內建的 Java 執行環境，不需要單獨安裝 Java，也不存取網路。此外掛只顯示 SPW 已經載入的歌詞，不負責搜尋。

更新外掛前請完全結束 SPW，取代外掛包後重新啟動（匯入時可能會報錯，無視即可）；外掛檔案不會被宿主熱載入。

## 使用與設定

- **開啟設定**：在 SPW 的外掛設定頁中開啟 CDL 的設定頁；或在詞島 / 托盤圖示上按右鍵開啟選單。
- **右鍵選單**：依元件分組（歌詞 / 詞島 / 頻譜 / 封面 / 播放控制項 / 動畫 / 選單與設定等），包含預設清單與匯入 / 匯出入口。
- **預設**：在預設清單中切換；匯入時先預覽差異，匯出時可選擇 JSON 或 TXT 格式。
- **取色器與字型**：由設定頁按鈕或選單開啟工具視窗。
- **範例詞島**：設定頁內的預覽與真機使用同一套繪製路徑。
- **設定檔**：位於 `%APPDATA%\Salt Player for Windows\workshop\data\dev.cdl.spw\`。

## 回饋與貢獻

歡迎各位志同道合的朋友們一同共建！遇到問題或有好的功能想法，歡迎提 issue 或 PR，或加入 QQ 群 `1130794596` 交流。

## 授權與致謝

本專案自有程式碼以 **AGPL-3.0-only** 發布，並依 **AGPLv3 §7(b)** 附加「顯著署名」條款（分發時須在 README / 發布頁 / 關於對話框等顯著位置保留作者署名，詳見 `NOTICE`）。第三方元件與程式碼來源以隨包檔案為準。其中 `HostLyricsProbe.kt` 與 `CoverColorExtractor.kt`（原實作 GaBoron 的兩處通用演算法）**保留 GPL-3.0-only 與原作者署名**，不單方面變更授權；組合作品整體按 AGPL-3.0-only 分發：

| 檔案 | 內容 |
| --- | --- |
| `LICENSE` | AGPL-3.0-only 全文 |
| `NOTICE` | 署名與改動說明 |
| `THIRD_PARTY_NOTICES.md` | 第三方元件與授權 |
| `licenses/` | AGPL-3.0、Apache-2.0、CC-BY-SA-4.0、JNA、LGPL-2.1 等授權全文 |

### 致謝

- **Salt Player for Windows** 與 **SPW Workshop API** — Moriafly
- **靈動詞島（Dynamic Lyrics Island for SPW）（現SPW-island）** — GaBoron
- **Lyricify** 的視覺與互動原創 — WXRIW（XY Wang）
- **PF4J**、**JNA**、**Jaudiotagger** 及專案使用的其他開源元件
- 第三方程式碼的署名與改動說明：見隨包 `NOTICE`
- 參與製作測試或提供意見建議的 @aodia8tssa @濱 @沒有名字（QQ）等人。

CDL for SPW 是獨立開發的專案，與 Salt Player 官方、靈動詞島及其他歌詞外掛均無隸屬、贊助或背書關係。

---

<a id="English"></a>
<p align="center">
  <a href="#简体中文">简体中文</a> &nbsp;|&nbsp;
  <a href="#繁體中文">繁體中文</a> &nbsp;|&nbsp;
  <b>English</b>
</p>

# CDL for SPW

Custom Desktop Lyrics · more desktop lyrics styles for Salt Player for Windows.

Displays the lyrics SPW is currently playing on your desktop: a lyrics island with many appearance options. Main lyrics, translation, spectrum, cover, background, border, and playback controls each have their own settings group, with hundreds of combinations.

QQ group: `1130794596`

> **Beta**: the experience may not be perfect yet, and we will keep improving.
> Plugin ID: `dev.cdl.spw`. It is independent of other lyrics plugins and can be installed alongside them.

## Features

- **Preset system**: save the current configuration as a preset and import/export it (JSON, or `key=value` TXT, with the file name as the preset name). Import previews which keys will change and reports keys unknown to this version. Presets can be deleted (moved to `presets/_deleted/`, restorable by hand), overwritten, or renamed; built-in presets cannot be deleted or overwritten. (Located in the context menu.)
- **Scale**: 149 setting keys in 11 component groups (no group exceeds 30 items); common items are adjustable directly in SPW's settings page and also available from the context menu.
- **Live preview**: the sample island in the settings page shares the production drawing path, so changes are visible immediately.
- **Tray icon**: control playback conveniently with single or double clicks.
- **Color picker**: color field, hue bar, brightness bar, RGB fields, and hex input; hue is preserved at zero saturation.
- **Fonts**: system fonts, font files, or the host's built-in fonts (31 families, including weights and italics); the 10 most recently used fonts are listed separately; the font list in SPW's settings page is searchable.
- **Lyrics**: per-character highlighting (per-char / per-line / smooth), single or dual lines, size / weight / letter spacing / line gap, max width and alignment.
- **Translation**: independent toggle, size, and color; placed above or below the main line; unsung lines can be dimmed separately.
- **Outline**: hard, fade-out, and edge-dissolve modes; fade-out decreases layer by layer without a premultiplied mask.
- **Island**: square, rounded rectangle, or pill, with continuous-curvature (squircle) corners; adjustable width, height, opacity, and content alignment.
- **Materials**: solid, linear gradient, multi-color flow, and cover-camo fragment flow; flow speed is adjustable.
- **Spectrum**: bottom, right-edge five bars, ring, or radial layouts; manual, cover, level, cover-flow, or fragment color sources; bar corners, solid or polyline, and opacity.
- **Cover**: display and scaling, cover-based colors, vinyl ring, water level, and cover-mode appearance.
- **Slot**: the right slot can host a mini spectrum, ring spectrum, radial spectrum, a clock, or nothing.
- **Clock**: 12/24-hour format; seven-segment, dot-matrix, and pixel glyphs.
- **Playback controls**: hover to reveal previous / play-pause / next and a draggable progress bar; classic and glowing styles.
- **Menu and tray**: a self-drawn context menu grouped by component, with the preset list and import/export/delete/overwrite actions; the tray icon provides common entries.
- **Frame rate**: selectable tiers, capped by the actual rendering refresh rate.

See the in-app settings page for more settings.

## Installation

Requires Salt Player for Windows **1.18.x** (Workshop API `0.1.0-dev20`) and **Windows 10 / 11 x64**.

1. Download `cdl-for-spw-<version>.spmod` from [Releases](https://github.com/H-2OS/CDL-SPW/releases/latest), or subscribe on the [Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3821829015);
2. Import the zip in SPW's Workshop / plugin manager;
3. Enable **CDL for SPW** and play a track whose lyrics are already loaded.

The plugin uses the host's bundled Java runtime, requires no separate Java installation, and does not access the network. It only displays lyrics already loaded by SPW and does not search for lyrics.

Fully quit SPW before updating, replace the package, and restart (importing may report an error — just ignore it); plugin files are not hot-reloaded by the host.

## Usage and Settings

- **Open settings**: from SPW's plugin settings page, or right-click the island or the tray icon to open the menu.
- **Context menu**: grouped by component (lyrics / island / spectrum / cover / controls / animation / menu & settings), including the preset list and import/export entries.
- **Presets**: switch from the preset list; imports preview the diff first; exports can be JSON or TXT.
- **Color picker and fonts**: opened from settings-page buttons or the menu.
- **Sample island**: the preview uses the same drawing path as the real window.
- **Configuration**: stored in `%APPDATA%\Salt Player for Windows\workshop\data\dev.cdl.spw\`.

## Feedback and Contributions

Everyone is welcome to build this project together! For issues or feature ideas, open an Issue or PR, or join the QQ group `1130794596`.

## License and Credits

The project's own code is released under **AGPL-3.0-only**, with an **AGPLv3 §7(b)** additional attribution term (distributions must display the author attribution in a prominent place such as the README, release page, or About dialog — see `NOTICE`). Third-party components and code provenance are governed by the shipped files. `HostLyricsProbe.kt` and `CoverColorExtractor.kt` (two general-purpose algorithms from the original implementation by GaBoron) **retain GPL-3.0-only and the original author's attribution**; the combined work is distributed as AGPL-3.0-only:

| File | Content |
| --- | --- |
| `LICENSE` | Full text of AGPL-3.0-only |
| `NOTICE` | Attribution and change statement |
| `THIRD_PARTY_NOTICES.md` | Third-party components and licenses |
| `licenses/` | Full texts of AGPL-3.0, Apache-2.0, CC-BY-SA-4.0, JNA, LGPL-2.1, etc. |

### Credits

- **Salt Player for Windows** and **SPW Workshop API** — Moriafly
- **Dynamic Lyrics Island for SPW (now SPW-island)** — GaBoron
- **Lyricify**, original visual and interaction design — WXRIW (XY Wang)
- **PF4J**, **JNA**, **Jaudiotagger**, and other open-source components used by the project
- Third-party attribution and change statements: see the bundled `NOTICE`
- @aodia8tssa, @滨, @没有名字 (QQ), and others who helped with testing or provided suggestions.

CDL for SPW is an independent project with no affiliation, sponsorship, or endorsement from Salt Player, Dynamic Lyrics Island for SPW, or any other lyrics plugin.

<br>
<p align="center">© 2026 H-2OS (自宅警備員). Some rights reserved.&nbsp;|&nbsp; <a href="./LICENSE">LICENSE</a></p>
