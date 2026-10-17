# CDL for SPW · V0.0.0

> 整理时间：2026-10-18 05:14
> 作者：**H-2OS（自宅警備員）** · <https://github.com/H-2OS/CDL-SPW>
> 许可：**AGPL-3.0-only**，并按 **AGPLv3 §7(b)** 附加「显著署名」条款（见 `NOTICE`）。

本仓库只含**构建包（最终分发物）所需的源码**与附属文件。

| 路径 | 说明 |
|---|---|
| `src/main/` | 插件源码（101 个 `.kt`，其中 2 个为原实现 GPL 文件） |
| `native/` | 频谱助手源码 |
| `licenses/` | 第三方许可全文（含 `AGPL-3.0.txt`） |
| `LICENSE` / `NOTICE` / `THIRD_PARTY_NOTICES.md` | 许可、署名与第三方声明 |
| `README.md` | 项目说明（三语） |
| `dist/` | 构建产物 |

**不包含**：`src/probe/`（探针）、`*.bak-*`、`build/`、`.gradle/`。

本仓库**只包含分发物所需的源码**：构建工程（`build.gradle.kts` / `settings.gradle.kts` /
`gradle.properties`）与 Gradle Wrapper 不在本仓库。需要从源码构建时，请使用开发仓库
（构建环境：JDK 21 + `spw-workshop-api 0.1.0-dev21`）。

## 产物

| 文件 | 大小 | md5 |
|---|---|---|
| `dist/cdl-for-spw-0.0.0.zip` | 10099364 B | `03094a31f6edab1087ad5283cbbd479a` |
| `dist/cdl-for-spw-0.0.0-source.zip` | 3166655 B | `d720937862600514a5a49795f4bb50fc` |

## 许可与署名

- 自有代码与移植的菜单体系：**AGPL-3.0-only**（作者 H-2OS）。
- **原实现（GaBoron）的两处通用算法**：`HostLyricsProbe.kt`、`CoverColorExtractor.kt`
  保留 **GPL-3.0-only** 与原作者署名（文件头 `Ported in part from ... by GaBoron`），
  **不单方面变更许可**；组合作品整体按 AGPL-3.0-only 分发。
- **附加条款（AGPLv3 §7(b)）**：分发时须在显著位置保留
  「CDL for SPW · 作者 H-2OS（自宅警備員） · https://github.com/H-2OS/CDL-SPW」。
- 第三方组件见 `THIRD_PARTY_NOTICES.md`；分发须随包提供对应源码（AGPL-3.0 §6）。
