# `licenses/` 目录说明

本目录存放**随插件包分发的第三方许可全文**，以及本项目因「组合分发」而必须
一并提供的许可文本。目录内容随构建打包进插件 ZIP 的 `licenses/`。

**本目录不是「参考文档」，而是许可义务的履行载体**：删掉其中任何一份文件，
都会导致对应许可的合规失效。哪个文件对应哪个依赖、缺了会怎样，见下表。

---

## 1. 命名规范

| 规则 | 说明 |
| --- | --- |
| 格式 | `<许可标识>.txt`，许可标识用 SPDX 风格短名，**大小写与连字符保持固定** |
| 版本 | 必须带版本号（`-3.0` / `-2.1` / `-4.0`），不带版本号的许可文件不允许存在 |
| 例外 | 当一个**组件专属**的声明无法用通用许可名表达时，用 `<组件>-<类型>.txt`（如 `JNA-LICENSE.txt`） |
| 例外 2 | **官方原件本身就是 PDF**（如厂商自制协议）时：原件以 `<组件>-LICENSE.pdf` **逐字节原样**随包，「不得改写原文」的义务**由 PDF 原件承担**；可另附 `<组件>-LICENSE.txt` **全文转写**件，但**不得**用它替代 PDF。转写件必须在文件头声明「以 PDF 原件为准」及归一化范围（见 §2.2） |
| 例外 3 | 官方许可**未给出 SPDX 短名**时，用 `<组件>-LICENSE`，**不得杜撰**一个 SPDX 标识符 |
| 编码 | UTF-8 无 BOM；**不得改写原文**（许可全文必须逐字节保持原样） |
| 换行 | 与原始文件一致即可；**不要做行尾规范化**（会破坏「逐字节一致」的可验证性） |
| 禁止 | 不得合并多份许可到一个文件；不得在本目录放「摘要」或「自行编写的许可说明」 |

---

## 2. 文件清单（当前 10 份，已存在）

| # | 文件名 | 对应组件 / 内容 | 许可 | 强约束 | 缺失的后果 |
| --- | --- | --- | --- | --- | --- |
| 1 | `AGPL-3.0.txt` | **本项目自有代码的许可正文**（与仓库根 `LICENSE` 同一份） | AGPL-3.0-only | **是** | 强制项：自有代码以 AGPL-3.0-only 发布，必须随包提供 |
| 2 | `CC-BY-SA-4.0.txt` | 视觉与交互改编、`NOTICE` 等文字材料（原创 Lyricify / WXRIW） | CC BY-SA 4.0 | **是** | BY-SA 的「许可标识」义务落空；改编部分失去许可依据 |
| 3 | `LGPL-2.1.txt` | `lib/jaudiotagger-3.0.1.jar`；**同时**是 JNA 双许可中 LGPL 选项的引用文本 | LGPL-2.1-or-later | **是** | LGPL-2.1 §6 违反：Jaudiotagger 不可合法随包 |
| 5 | `Apache-2.0.txt` | **实际随包依赖仅 3 个**：JNA · JNA Platform（`lib/`）· Jaudiotagger（LGPL 主，Apache 用于其传递声明）；另有 **`compileOnly`（宿主提供、不打包）**：SPW Workshop API · PF4J · Kotlin stdlib。⚠️ **原列 `kotlinx-serialization` / `opencc4j` / `heaven` / `nlp-common` 四者经实测并非本插件依赖**（构建脚本零出现、`lib/` 无对应 jar），已从本行移除 | Apache-2.0 | 否（保留声明即可） | Apache-2.0 §4(a) 违反：未附许可副本 |
| 6 | `JNA-LICENSE.txt` | JNA 5.17.0 的**双许可选择声明**（`Apache-2.0 OR LGPL-2.1-or-later`） | 声明文件 | 否 | 无法证明我们「选定」了哪个许可；§4(d) 声明文件义务落空 |
| 7 | `JNA-THIRD-PARTY.txt` | JNA 的第三方组件声明 | 声明文件 | 否 | 同上 |
| 8 | `JNA-libffi-LICENSE.txt` | JNA 内嵌的 libffi | libffi 许可 | 否 | libffi 的许可与声明义务落空 |
| 9 | `MiSans-LICENSE.pdf` | ⚠️ **当前无对应组件** —— 2026-09-22 起包内**已无任何 MiSans 字体副本**（原记"随包的 10 个 MiSans 字体（`classes/fonts/`）"，该目录已整体删除） | 《MiSans 字体知识产权许可协议》（小米自制，**非开源**） | **是**（沿用） | **当前无缺失后果**（已无字体副本可触发 §2 第 4 条）。保留目的是**追溯历史版本**；**⚠️ 若日后重新随包分发字体，本条立即恢复为强制项**（见 `THIRD_PARTY_NOTICES.md` §9 的"必须整段重新评估"） |
| 10 | `MiSans-LICENSE.txt` | 同上，**中英全文转写件**（便于阅读/检索）。⚠️ 其**前言已更正**：不再声称"随包分发 10 个字体文件 / 本软件使用了 MiSans 字体"（那两句在 09-22 之后已不成立） | 同上 | 否（原件为 #9） | 无（仅为便利件；**不得**据此认为已合规） |

### 2.1 为什么 `LGPL-2.1.txt` 只有一份

`JNA-LICENSE.txt` 明确写出 JNA 采用 `Apache-2.0 OR LGPL-2.1-or-later` 双许可，
且其原文指向本目录同名的 `LGPL2.1` 文件。本项目已**选定 Apache-2.0**，
因此 JNA 不再需要 LGPL 全文；而 Jaudiotagger **必须**有 LGPL-2.1 全文。
两者共用这一份文本在内容上完全一致，**不需要拆成两个文件**。

> 反向注意：**不要**因为「JNA 选了 Apache」就把 `LGPL-2.1.txt` 删掉——
> 它是 Jaudiotagger 的强制材料。

### 2.2 为什么 MiSans 是「PDF 原件 + txt 转写」两件

MiSans 的官方许可**只以 PDF 发布**（`hyperos.mi.com`，79,535 字节），
协议内也**没有** `MIT` / `OFL-1.1` 这类 SPDX 短名（属小米自制协议）。故：

- **`MiSans-LICENSE.pdf` = 合规本体**：官方原件**逐字节原样**随包，
  「不得改写原文」由它承担。**删它即违约**（协议 §2 第 4 条）。
- **`MiSans-LICENSE.txt` = 便利转写件**：PDF 不可检索、不可 grep，
  而 `licenses/` 其余 8 份都是 `.txt`，读者会预期能直接读。
  故附一份**全文**（中英双语）转写，文件头写明来源 URL、原件大小、
  **原件 SHA-256** 与「歧义以 PDF 原件为准」。

**转写做了什么、没做什么**（不藏改动）：

| 动作 | 说明 |
| --- | --- |
| ✅ 做了什么 | 用 PDF 自带的 `ToUnicode` CMap（`Identity-H`）精确解码；把 PDF 子集字体里被映射到 **Kangxi 部首 / CJK 部首补充区**的 **19 个**字符归一回规范汉字（如 `⼩⽶`→`小米`、`⺠`→`民`、`⻄`→`西`、`⻛`→`风`）。这是**字形还原**，不是改字句 |
| ❌ 没做什么 | 未增删、未改写、未翻译、未摘要任何条款；**全角标点原样保留**（未做全角→半角降级）；未规范化行尾 |

> ⚠️ **不诚实做法（已避免）**：对整段文本做 `NFKC` 会把 `，：（）` 降成 `, : ( )` ——
> 那会破坏「逐字转写」的诚信。故归一化是**逐字符、只对部首码位**做的。

---

## 3. 与源码随包要求的对应关系（关键）

`licenses/` 只解决「附许可全文」。「附**对应源码**」由构建产物的 `source/` 目录解决。
两者的对应关系如下（**这是最容易漏的一块**）：

| 组件 | 许可 | 许可全文位置 | 对应源码位置 | 源码提供方式 |
| --- | --- | --- | --- | --- |
| 本项目自有代码 + 移植的菜单体系 | AGPL-3.0-only | 仓库根 `LICENSE` | `source/cdl-for-spw-<version>-source.zip` | 由 `sourceArchive` 任务生成，含 `src/`、构建脚本、Wrapper |
| `ui/AmllMotion.kt`（**仍未移植**） | AGPL-3.0-only | `licenses/AGPL-3.0.txt` | 同上（若移植，该文件在 `src/` 内） | 与自有部分同一份源码包即可 |
| **Jaudiotagger 3.0.1** | LGPL-2.1-or-later | `licenses/LGPL-2.1.txt` | **`source/jaudiotagger-3.0.1-sources.jar`** | **必须是「该版本的对应源码」**，见 §3.1 |
| JNA / 其他 Apache 组件 | Apache-2.0 | `licenses/Apache-2.0.txt` | 不需要 | Apache-2.0 不要求附源码 |

### 3.1 Jaudiotagger 的源码要求（LGPL-2.1 §6）

**要求**：随二进制分发 LGPL 库时，必须提供该库的**对应源码**
（"the complete corresponding machine-readable source code"，含可重新构建的完整材料）。

**执行**：

1. 来源：Maven Central 坐标 `net.jthink:jaudiotagger:3.0.1:sources`
   （即 `https://repo1.maven.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1-sources.jar`）。
2. 放置：`source/jaudiotagger-3.0.1-sources.jar`，**与 `lib/jaudiotagger-3.0.1.jar` 同版本**。
3. 校验：`unzip -l` 应能看到 `org/jaudiotagger/**/*.java`；构建后必须实际检查
   ZIP 内 `source/` 里存在该文件。
4. **禁止用其他版本的源码代替**，也禁止只放「仓库首页链接」——
   必须附**对应版本的源码文件本身**。
5. 该库**未被本项目修改**（未打补丁、未改类名），因此无需发布本项目的改动副本；
   若日后修改了它的源码，则改动部分须以 LGPL-2.1 发布。

> ⚠️ **当前构建脚本尚未抓取该文件**（`build.gradle.kts` 里没有下载/复制
> `jaudiotagger-3.0.1-sources.jar` 的任务）。**这是发布前的阻塞项**，
> 必须在 `sourceArchive` 或 `plugin` 任务中补上（见 `recon/11` 的必做清单）。

### 3.2 AGPL 的源码要求（AGPLv3 §13 + GPLv3 §13）

**要求**：组合分发的 AGPL 模块，其对应源码必须可由接收者获得。

**执行**：

1. `ui/AmllMotion.kt` 本身就在 `src/` 内，随 `source/` 的源码包一并提供，
   **无需单独出第二个源码包**；
2. 但**两份许可全文**（`AGPL-3.0.txt` 与 `LICENSE`）必须同时在包内；
3. 必须能**明确指认哪一份文件是 AGPL 的**（文件头 `SPDX-License-Identifier` +
   `NOTICE` 段落 + 本目录说明三重指认）；
4. 若日后把 AGPL 组件拆成独立 JAR，则需同时保留其「可替换性」，
   并继续随包提供该 JAR 的对应源码。

---

## 4. 新增依赖时的操作流程

引入任何新依赖前，按顺序做：

1. **确认许可**：解包该 JAR，读 `META-INF/LICENSE*` / `MANIFEST.MF` / POM 的 `<licenses>`。
   **不要凭项目名推测许可。**
2. **判断强度**：AGPL / CC BY-SA / LGPL → 强约束（本文档前几节口径）；
   Apache / MIT / BSD → 保留声明即可。
3. **检查兼容性**：新许可是否可与 GPL-3.0-only 组合？
   （GPL-2.0-only 与 GPL-3.0 **不兼容**；Apache-2.0、MIT、LGPL-2.1+ 兼容；
   AGPL-3.0 只能靠 §13 组合分发。）
4. **索取许可全文**：从**官方分发源**取，原文照抄，命名按 §1 规范。
5. **索取对应源码**（若为 LGPL/AGPL，且该组件随包）：放入 `source/`。
6. **同步更新三处**：`NOTICE`、`THIRD_PARTY_NOTICES.md`、`licenses/README.md`（本文件）。
   **只更新一处视为未完成。**
7. **同步更新 `recon/11-许可合规核对清单.md`** 的随包总表。
8. 拿不准的，**在文档里标注「待核实」并写明核实方法**，不要猜。

---

## 5. 目录结构（打包后）

```
cdl-for-spw-<version>.zip
├─ LICENSE                    ← GPL-3.0 全文（本项目自有代码）
├─ NOTICE                     ← 署名 + 改动说明 + 组合分发红线
├─ THIRD_PARTY_NOTICES.md     ← 逐条义务与强约束排序
├─ README.md
├─ classes/                   ← 编译产物（含 AmllMotion，AGPL）
├─ lib/                       ← 随包第三方 JAR（不含 kotlin-stdlib / annotations-*）
├─ licenses/                  ← 本目录：10 份许可与声明全文（含 MiSans 原件 PDF + 转写）
└─ source/
   ├─ cdl-for-spw-<version>-source.zip   ← 含 src/gradle/licenses/docs/构建脚本
   └─ jaudiotagger-3.0.1-sources.jar              ← LGPL 对应源码（**发布前必须存在**）
```

---

## 6. 禁止事项

- ❌ **不得**删除本目录任何文件（尤其 `LGPL-2.1.txt`、`AGPL-3.0.txt`）；
- ❌ **不得**改写许可全文的任何字句（含版权行、地址、版本号）；
- ❌ **不得**把 AGPL / LGPL / CC BY-SA 的文本改名为「本项目许可」；
- ❌ **不得**对 CC BY-SA 内容追加限制其权利的条款或技术措施；
- ❌ **不得**只放一个指向外部的链接来代替许可全文或对应源码；
- ❌ **不得**把 `licenses/` 排除在打包之外（它必须在插件 ZIP 里，而不只是在仓库里）；
- ❌ **不得**删除 `MiSans-LICENSE.pdf`（⚠️ **原理由已失效** —— 原写"删它 = 分发字体副本却未附协议 = 违约"，
  但**包内已无字体副本**（2026-09-22 起）⇒ §2 第 4 条不再被触发。**处置仍为"不删"**，理由改为：
  ① 追溯历史版本（曾有版本随包分发过字体）；② 若日后重新随包分发，它是**立即需要**的强制件）；
- ❌ **不得**用 `MiSans-LICENSE.txt` 替代 `MiSans-LICENSE.pdf`（转写件只是便利件）；
- ❌ **不得**把 MiSans 当作开源/可自由再分发的字体处理 ——
  它是**非开源**的自制协议，且授权**可撤销**。

> ## ⚠️ 一处**项目内部尚未收敛**的口径（2026-09-28 如实登记，**不替用户裁决**）
>
> 关于「协议 §2 第 3 条**是否禁止把 `.ttf` 原样打包**」，**本项目内部同时存在两种相反读法**，
> 且**从未收敛**：
>
> | 出处 | 读法 |
> |---|---|
> | 本文 §2 表格第 9 行（**原文**） | 「分发字体副本**即违约**」—— 即**禁止**（按禁止项处理） |
> | `THIRD_PARTY_NOTICES.md` §9.2（标题） | 「**未决**：第 3 条对本插件的适用（**不做「已合规」结论**）」—— 即**文本无唯一解** |
> | `THIRD_PARTY_NOTICES.md` C7 行 | 「**已闭合**（2026-09-22）—— 通过取消随包分发解决」，并称该歧义「**不再需要回答**」—— 即**回避** |
>
> **当前该分歧无实际影响**（包内无字体副本，三种读法都得出"现在合规"）。
> **⚠️ 但一旦重新随包分发字体，必须先收敛此分歧再动手** —— 否则三处会给出三种不同的行动指令。
> **取证参考**：协议 §2 第 3 条原文为「…**further distribute** the MiSans fonts or their components,
> **or any copies thereof**, nor shall you **redistribute** or sell them」，
> 其后豁免句仅覆盖「使用 MiSans 字体创作的作品（宣传素材 / logo / App）」——
> **按字面，`.ttf` 本身属"copies thereof"，不落在豁免句内**；
> 但本项目按"文本未给出唯一解"处理至今。**该判断属法务结论，留待用户/权利人澄清。**
