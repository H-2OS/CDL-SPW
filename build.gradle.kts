// CDL for SPW（测试版） —— 构建脚本
//
// 布局参照 Moriafly/spw-workshop-api（Apache-2.0）与 Dynamic-Lyrics-Island-for-SPW（GPL-3.0-only）。
// 本项目自有代码：AGPL-3.0-only（作者 H-2OS / 自宅警備員）。
// 第三方与移植组件的许可见 NOTICE / THIRD_PARTY_NOTICES.md。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// Ant 的路径匹配器。Gradle 的 `include`/`exclude` 内部用的就是它 ——
// 在 `sourceArchive` 的守门断言里复用它，可以保证"断言用的规则"与
// "过滤用的规则"**是同一套**，不会各自漂移。（Gradle 自带 Ant，无需额外依赖。）
import org.apache.tools.ant.types.selectors.SelectorUtils
// ⚠️ 必须显式 import 这个类：在 Kotlin DSL 里写全限定名 `java.util.zip.ZipFile`
// 会被解析成 Gradle 的 `java` **扩展**（`JavaPluginExtension`）的成员，
// 于是报一片 `Unresolved reference 'util'` —— 报错信息完全指不到真正的原因。
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
}

group = "dev.cdl"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

// ─────────────────────────────────────────────────────────────────────────────
// 宿主 API 坐标（⚠️ 不是 `com.github.Moriafly:spw-workshop-api`！）
// ─────────────────────────────────────────────────────────────────────────────
// 为什么不用官网上写的那个坐标：`com.github.Moriafly:spw-workshop-api` 在 JitPack 上
// **从 dev21 起变成了"多模块聚合壳"** —— 它的 jar 里只有一个 `META-INF/MANIFEST.MF`
// （198 字节），POM 里挂着 `spw-workshop-api` 与 `spw-workshop-gradle-plugin` 两个
// 子模块依赖。本工程对所有宿主依赖都用 `isTransitive = false`（宿主自己提供 runtime，
// 传递依赖是**必须**掐掉的，否则会把 Compose / salt-ui / gradle-plugin 拖进 classpath，
// 甚至触发 `build.gradle.kts:300` 的"危险冲突"守卫）⇒ 掐掉传递依赖后**一个类都拿不到**。
//
// 现场证据（2026-10-18，`./gradlew compileKotlin` 全红）：
//   e: ...IslandPopupMenu.kt:4:17 Unresolved reference 'xuncorp'
//   e: ...SettingsRuntime.kt:922:17 Unresolved reference 'WorkshopApi'
// 而 dev20 的 POM 是**扁平的**（直接把 pf4j / compose / salt-ui 列为依赖），所以
// `isTransitive = false` 在 dev20 上恰好还能用 —— 这是**版本升级带来的坐标形状变化**，
// 不是代码问题。故这里改指向**真正装 class 的子模块坐标**。
//
// ★ 版本选择：**必须是 dev21**，不能图新用 dev22 / dev23。
//   · dev21 的 class 文件 major=**65**（Java 21），与本工程 toolchain 21 相容；
//   · **dev22 / dev23 是 major=69（Java 25）** ⇒ Gradle 直接拒绝：
//     `is only compatible with JVM runtime version 25 or newer`。
//   实测（2026-10-18，`od -j6 -N2` 读 class 头）：dev20=65 · dev21=65 · dev22=69 · dev23=69。
//   歌词侧 API 在 dev21 与 dev22 上签名逐字相同（javap 比对），用 dev21 **不损失任何能力**。
//
//   （过程留痕）一度判定"dev21 子模块只有 jar 没有 POM"并改选 dev22 —— 那是 JitPack
//   懒构建的 404 抖动，稍后重测 `pom=200`。**先复测再据此改坐标**。
val workshop = "com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev21"
val projectUrl = providers.gradleProperty("projectUrl")

// ─────────────────────────────────────────────────────────────────────────────
// 发布防呆：projectUrl 不得残留占位符
// ─────────────────────────────────────────────────────────────────────────────
// 为什么值得一道专用检查：`projectUrl` 会进两个**用户可见且难改**的地方 ——
//   ① JAR manifest 的 `Plugin-Open-Source-Url`（宿主"关于/开源地址"直接读它）
//   ② `project.properties` → 插件内"关于与许可"对话框的"项目源代码"一行
// 一旦带着 `CHANGE-ME` 发布，用户点"开源地址"会跳到 404 的 GitHub 用户名，
// **而且插件已分发出去，改不回来**。这类"发布前容易漏、漏了很尴尬"的值，
// 适合用构建期断言兜住，而不是靠人记得。
//
// 设计取舍：**只在打包时硬失败，不影响日常 `compileKotlin` / `build`** ——
// 开发中尚未定仓库地址是正常状态，不该天天挡着人。
//
// ⚠️ 但硬失败还有一个真实副作用：**它把"真机验证"也一并挡住了**。而 A1–A7 的验收
// 恰恰需要先产出一个 ZIP 装进宿主。若为此就把占位符填成假地址，等于放弃了这道防线。
// 故提供**显式的本地豁免开关** `-PallowPlaceholder=true`：
//   · 只影响打包，且**必须在命令行显式给出**（不写进 gradle.properties，避免被当成默认值）；
//   · 命中时打印醒目警告并把产物改名加 `-LOCAL-ONLY` 后缀，**防止误分发**；
//   · 真正的发布流程仍应填真实地址后走无参 `./gradlew plugin`。
val PLACEHOLDER_MARKERS = listOf("CHANGE-ME", "CHANGEME", "TODO", "example.com", "your-name")
val allowPlaceholder = providers.gradleProperty("allowPlaceholder").orNull?.toBoolean() ?: false

val verifyProjectUrl by tasks.registering {
    description = "打包前校验 projectUrl 已填真实地址（非占位符）。"
    val url = projectUrl.orNull
    doLast {
        val value = url?.trim().orEmpty()
        val isPlaceholder = PLACEHOLDER_MARKERS.any { value.contains(it, ignoreCase = true) }
        when {
            value.isEmpty() -> throw GradleException(
                "发布校验失败：`projectUrl` 未设置。\n" +
                    "请在 `gradle.properties` 填入真实仓库地址，例如：\n" +
                    "  projectUrl=https://github.com/<你的用户名>/super-island-for-spw"
            )
            isPlaceholder && !allowPlaceholder -> throw GradleException(
                "发布校验失败：`projectUrl` 仍是占位符 → $value\n" +
                    "该值会写进 manifest 的 `Plugin-Open-Source-Url` 与插件内「关于」对话框，\n" +
                    "带着它发布会让用户点到 404，且分发后无法追回。\n" +
                    "请在 `gradle.properties` 改成真实地址。\n" +
                    "—— 若只是**本地真机验证**、不对外分发，可显式加 `-PallowPlaceholder=true` 绕过\n" +
                    "（产物会带 `-LOCAL-ONLY` 后缀，不要分发该包）。"
            )
            isPlaceholder -> logger.warn(
                "[发布校验] ⚠️ 已按 -PallowPlaceholder=true 豁免占位符：$value\n" +
                    "[发布校验] ⚠️ 此产物仅供本地验证，**不可对外分发**。"
            )
            else -> logger.lifecycle("[发布校验] projectUrl 有效：$value")
        }
    }
}

dependencies {
    // 由宿主提供，不打包
    compileOnly(kotlin("stdlib"))
    compileOnly(workshop) { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")

    // 随包
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    // ── LGPL 依赖（**必须同步 lgplDeps 列表与 NOTICE**）─────────────────────
    // 用途：只读本地音频文件的时长（`platform/LocalTrackDuration.kt`），
    // 供进度条的总时长使用 —— 宿主 API 不提供时长（取证见 recon/26 §2）。
    //
    // ⚠️ 加依赖 = 加**法务义务**：LGPL-2.1 §6 要求随二进制一并提供「对应源码」。
    //    这不是注释提醒，而是由下面的 `lgplDeps` 声明 + `verifyLgplSources`
    //    守门人机械保证的（缺源码时**构建失败**，见该任务的 KDoc）。
    //    许可全文（licenses/LGPL-2.1.txt）与用法说明（NOTICE / THIRD_PARTY_NOTICES.md）
    //    在本次改动前就已经备好，故这里只需声明坐标。
    implementation("net.jthink:jaudiotagger:3.0.1")
}

// ─────────────────────────────────────────────────────────────────────────────
// 临时/备份文件：**必须在每个打包任务上各排除一次**（踩过两次，第二次在源码包）
// ─────────────────────────────────────────────────────────────────────────────
// 2026-09-16 真机部署时发现：改 `preference_config.json` 前用 `cp` 留的备份
// `preference_config.json.bak-before-fix` **被打进了包**（解压后出现在
// `classes/` 里）。后果不是崩溃，而是**包里混着无用文件**——
// 用户/审查者看到会困惑"这是不是漏删了"，且备份内容可能包含已废弃的声明。
//
// ⚠️ **2026-09-19 修正一处自欺**：原先这段注释声称"在资源处理的源头拦截，
//    能同时覆盖 jar / sourceArchive / plugin 三个下游任务"。**这是错的** ——
//    `processResources` 的 `exclude` 只作用于**它自己的输出**（→ jar → plugin 的
//    `classes/`），而 `sourceArchive` 走的是 `from("src")` **直读源码目录**，
//    根本不过 `processResources`。实测：源码包里混进了 **46 个 `.bak-*`**
//    （约 4.5 MB 的陈旧源码副本，其中 `IslandWindow.kt.bak-*` 单文件就 400 KB）。
//
//    教训：`exclude` 是**按任务**生效的，不是"按文件"全局生效的。任何新增的
//    打包任务（Zip / Jar / Copy）都必须**自己**加一遍这个过滤 —— 故把它提成
//    共享常量，让"漏加"至少能通过 grep 这个常量名被发现。
val backupNoisePatterns = listOf(
    "**/*.bak", "**/*.bak-*", "**/*.orig", "**/*.old", "**/*.tmp",
    "**/*.rej", "**/*~", "**/.DS_Store", "**/Thumbs.db",
)

tasks.processResources {
    // ── 频谱助手 exe：**构建产物**，不是仓库里的二进制 ──────────────────────
    // 由 `native/spectrum/*.cs` 现编译（见 buildSpectrum 任务）。
    //
    // ⚠️ 必须显式 dependsOn：`from(build/native/…)` 只是声明"要打哪些文件"，
    //    Gradle **不会**据此推断出"那些文件还没生成"。漏了它的后果有两种，
    //    都很隐蔽：首次构建时该文件不存在 → 静默跳过（包能装、频谱永远不亮）；
    //    二次构建时读到上一轮的陈旧 exe → 源码改了但包里的行为没变。
    //    这与本项目 `plugin` 任务踩过的"陈旧 jar"（BUG-011 一族）是同一类坑，
    //    在这里从**声明依赖**这个源头堵住，而不是靠人记得先手动编译。
    dependsOn("buildSpectrum")
    from(layout.buildDirectory.file("native/spw-spectrum.exe")) { into("native") }
    inputs.property("projectUrl", projectUrl)
    filesMatching("project.properties") { expand("projectUrl" to projectUrl.get()) }
    // 见文件上方 `backupNoisePatterns` 的说明。
    // ⚠️ 这里的 `exclude` 只覆盖 jar（及其下游 `plugin` 的 `classes/`）——
    //    它**不会**影响 `sourceArchive`，那边需要单独再排除一次。
    exclude(backupNoisePatterns)
}

// ─────────────────────────────────────────────────────────────────────────────
// 频谱助手：把 native/spectrum/*.cs 编译成 spw-spectrum.exe
// ─────────────────────────────────────────────────────────────────────────────
// ## 为什么用系统自带的 csc.exe，而不是 .NET SDK
//
// `%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe` 是 **.NET Framework 的
// C# 编译器**，随 Windows 一起装，**不需要用户装任何 SDK**。本项目目前
// 没有任何 C# 工具链，引入 `dotnet` 会带来一个"用户必须先装 .NET SDK 才能构建"
// 的前置条件 —— 对一个插件项目来说代价过大。
//
// 代价是这个编译器只支持到 C# 5 语法（无 `nameof`、无字符串插值、无
// 表达式体成员、无 `?.`）。`native/spectrum/` 下的源码**刻意**只用 C# 5 能编译的写法，
// 这不是风格偏好，是**构建前提**。
//
// ## 为什么目标子系统是 winexe（而不是 exe）
//
// 助手由 GUI 进程（SPW）启动。若编译成控制台程序（`/target:exe`），
// Windows 会为它**新建一个控制台窗口** —— 用户每次开频谱都会看到一个黑框闪一下。
// `winexe` 不分配控制台，而 stdout/stdin 的重定向管道句柄由父进程通过
// CreateProcess 传入，仍然有效（`Console.OpenStandardOutput()` 拿的就是它）。
//
// ## 为什么 csc 缺失时**直接构建失败**，而不是跳过
//
// 跳过的话，包能正常构建、能装进宿主，但频谱永远是空的 —— 而用户
// 无从判断是"功能没做"还是"环境不对"。失败并给出确切路径与补救建议，
// 是把一个沉默的缺陷换成一次响亮的报错。这与 `verifyLgplSources` 是同一取舍。
val spectrumSourceDir = layout.projectDirectory.dir("native/spectrum")
val spectrumExe = layout.buildDirectory.file("native/spw-spectrum.exe")

// csc.exe 的位置由系统决定，故在**配置期**解析一次并写进任务。
// 用 providers 而不是直接读环境变量，是为了让配置缓存/增量构建看到稳定的输入。
val dotNetFrameworkDir: String = (System.getenv("WINDIR") ?: "C:/Windows") +
    "/Microsoft.NET/Framework64/v4.0.30319"

tasks.register<Exec>("buildSpectrum") {
    group = "build"
    description = "把 native/spectrum/*.cs 编译成 spw-spectrum.exe（进程回环频谱助手）。"
    val sources = spectrumSourceDir.asFileTree.matching { include("*.cs") }
    val compiler = file("$dotNetFrameworkDir/csc.exe")

    inputs.files(sources).withPropertyName("spectrumSources")
    outputs.file(spectrumExe).withPropertyName("spectrumExe")

    doFirst {
        // ⚠️ 断言放在 doFirst 而不是配置期：配置期抛异常会让**所有**任务
        //    （包括 `compileKotlin`）都失败，连"看一眼别的错误"都做不到。
        //    放在执行期，则只有真的要打频谱时才要求编译器存在。
        if (!compiler.isFile) {
            throw GradleException(
                "找不到 C# 编译器：${compiler.absolutePath}\n" +
                    "它是 .NET Framework 自带的 csc.exe，通常随 Windows 一起安装。\n" +
                    "补救：在「Windows 功能」中启用「.NET Framework 4.x」相关组件，" +
                    "或安装 .NET Framework 4.8 运行时/开发者包。\n" +
                    "（本项目**不需要** .NET SDK —— 见 buildSpectrum 任务的注释。）"
            )
        }
        spectrumExe.get().asFile.parentFile.mkdirs()
    }

    executable = compiler.absolutePath
    args(
        "/nologo",
        // 见上文"为什么目标子系统是 winexe"。
        "/target:winexe",
        // 宿主 SPW 是 64 位进程；助手要与它同位数才能被它正常拉起并共享管道。
        "/platform:x64",
        "/optimize+",
        "/warnaserror+",   // 警告即错误：这个编译器的告警往往指向真实缺陷（如未赋值字段）
        "/out:${spectrumExe.get().asFile.absolutePath}",
    )
    args(sources.files.sortedBy { it.name }.map { it.absolutePath })
}

tasks.jar {
    manifest.attributes(
        "Plugin-Class" to "dev.cdl.host.CdlPlugin",
        "Plugin-Id" to "dev.cdl.spw",
        "Plugin-Name" to "CDL for SPW（测试版）",
        "Plugin-Version" to project.version.toString(),
        "Plugin-Provider" to "CDL for SPW",
        "Plugin-Description" to "Custom Desktop Lyrics for SPW · Beta",
        "Plugin-Has-Config" to "true",
        "Plugin-Open-Source-Url" to projectUrl.get()
    )
}

tasks.register<Zip>("sourceArchive") {
    archiveFileName.set("cdl-for-spw-${project.version}-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    // ⚠️ 自带字体（`src/main/resources/fonts/*.ttf`）**不进源码包**。
    //   它们是第三方二进制字体文件，不是本项目的"源码"—— 源码包的用途是让人
    //   能重建本项目，而字体只是随包分发的**资源**。放进来的后果有两个：
    //   ① 源码包平白多出约 9 MB；② 把字体的再分发扩散到**第二个分发渠道**
    //   （许可问题见 NOTICE / THIRD_PARTY_NOTICES.md）。
    //   运行时链路不受影响：字体经 `processResources` → `jar` → `plugin` 的
    //   `into("classes")` 随插件包分发，与源码包无关。
    // ⚠️ 2026-09-22：**该目录与其随包分发已整体取消**（用户拍板「取消自带字体包，
    //   调用自带字体包的路线转到在本机选择的字体上」），见 THIRD_PARTY_NOTICES.md §9。
    //   这条 `exclude` **保留不动**：它守的是"万一字体被加回来，也不许进源码包"
    //   —— 是一道**条件式**护栏，不是历史遗留（与下面断言三/四同一口径）。
    // ⚠️ `from("src")` 是**直读源码目录**，不过 `processResources` ——
    //   故这里必须**自己**再排除一遍临时/备份文件（见 `backupNoisePatterns` 的说明：
    //   实测源码包里混进过 46 个 `.bak-*`，约 4.5 MB）。
    from("src") { into("src"); exclude("main/resources/fonts/**"); exclude(backupNoisePatterns) }
    // 频谱助手的 C# 源码。**必须随源码包分发**：exe 是 GPL-3.0-only 的产物，
    // 而"分发二进制时必须提供对应源码"是 GPL 的核心义务。
    // 这里随的是**源码**（不是 exe）—— exe 由使用者用同一份 build.gradle.kts 现编译。
    from("native") { into("native") }
    from("gradle") { into("gradle") }
    from("licenses") { into("licenses") }
    from("docs") { into("docs") }
    from("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
        "README.md", "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", ".gitignore")

    // ── 机械守门断言：源码包里不许有临时/备份文件 ───────────────────────────
    //
    // 这条缺陷**复发过一次**（见 `backupNoisePatterns` 的说明：先是 jar，
    // 后是源码包），根因都是"`exclude` 按任务生效、而新加的打包任务忘了加"。
    // 上面已经加了过滤，这里断言的是**结果** —— 将来再有人新增打包任务、
    // 或改了过滤规则，只要包里出现 `.bak`，构建就**响亮地失败**。
    //
    // 为什么不在 `plugin` 任务里断言：源码包是被 `plugin` 以**嵌套 zip**
    // （`into("source")`）的形式带进去的，在 `plugin` 里要读它得再解一层；
    // 在这里断言则恰好卡在**产生**它的地方，报错位置离病根最近。
    doLast {
        val zipFile = archiveFile.get().asFile
        val noise = ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { name ->
                    // ⚠️ 用 Ant 的 `SelectorUtils.matchPath` —— 这正是 Gradle 在
                    //    `include`/`exclude` 内部用的**同一个**匹配器。自己写
                    //    "把 glob 简化成 contains" 那种近似匹配会与真实过滤规则
                    //    悄悄漂移（本任务已经因为"以为 exclude 是全局的"栽过一次），
                    //    故这里刻意复用权威实现，而不是再发明一个。
                    backupNoisePatterns.any { SelectorUtils.matchPath(it, name, true) }
                }
                .toList()
        }
        if (noise.isNotEmpty()) {
            throw GradleException(
                "源码包混入了临时/备份文件（${noise.size} 个）：\n" +
                    noise.take(10).joinToString("\n") { "  - $it" } +
                    (if (noise.size > 10) "\n  … 另 ${noise.size - 10} 个" else "") +
                    "\n请检查本任务的 `exclude(backupNoisePatterns)` 是否被移除。\n" +
                    "产物：${zipFile.absolutePath}"
            )
        }
        logger.lifecycle("[源码包] ✅ 无临时/备份文件（exclude 生效）")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 随包必须过滤掉的宿主提供件
// ─────────────────────────────────────────────────────────────────────────────
// 宿主已提供 Kotlin 标准库、JetBrains annotations、PF4J 与 SPW Workshop API。
// 重复打包会与宿主类加载器冲突（同一类两个 Class 对象 → ClassCastException）。
//
// 前两组（kotlin-stdlib / annotations-）在 `compileOnly` 下本就不会进
// `runtimeClasspath`，因此现在的过滤是**防御性**的 —— 保留它们是为了防止
// 将来有人把 `compileOnly` 误改成 `implementation` 时静默冲突。
// 后两组（pf4j-* / spw-workshop-api-*）覆盖的是**将来**可能被拖进来的传递依赖，
// 当前 `isTransitive = false` 已挡住，同样属防御性条款。
val hostProvidedPrefixes = listOf(
    "kotlin-stdlib",
    "annotations-",
    "pf4j-",
    "spw-workshop-api-",
)

// ─────────────────────────────────────────────────────────────────────────────
// LGPL 合规：随包提供「对应源码」
// ─────────────────────────────────────────────────────────────────────────────
// Jaudiotagger 是 LGPL-2.1-or-later。LGPL-2.1 §6 要求：分发目标代码时，必须
// 同时提供「对应的机器可读源码」（可经由同一渠道）。仅附许可全文**不满足**该义务。
//
// 设计取舍：**不把 sources jar 解压进包**，而是以 `-sources.jar` 原样随包 ——
// 原因有三：① 保留既有实现原始构件，可校验哈希、不产生"改编"歧义；
// ② 无需处理源码内的 `META-INF/` 与嵌套目录结构；③ 体积与解压等价（JAR 本身即 ZIP）。
//
// ⚠️ 该任务在依赖尚未加入时**不报错、产出空集合**，因此**不会**阻止早期构建。
//    这意味着「忘了加源码」会静默通过 —— 所以 `verifyLgplSources`（见下）是必需的守门人。
val lgplDeps = listOf("jaudiotagger")   // 未来若新增 LGPL 依赖，必须同步加到这里

// LGPL 依赖的 **sources 分类器**必须在 dependencies 里显式声明，Gradle 才会解析它 ——
// sources 是一个**分类器构件**，不是传递依赖，不会随主构件被解析出来。
//
// ─────────────────────────────────────────────────────────────────────────────
// ⚠️ **必须用专用配置，不能声明成 `runtimeOnly`（2026-09-16 真机事故，BUG-037）**
// ─────────────────────────────────────────────────────────────────────────────
// 原实现写的是 `"runtimeOnly"("net.jthink:jaudiotagger:3.0.1:sources")`，
// 理由只是"让 `runtimeClasspath` 能解析到它"。但 `runtimeOnly` 的语义是
// **进运行时类路径**，而打包任务用
// `into("lib") { from(configurations.runtimeClasspath) }` 填 `lib/` ——
// 于是 **sources jar 被当成运行时依赖打进了插件包**。
//
// **后果（真机实测）**：SPW 加载插件时把 `lib/jaudiotagger-3.0.1-sources.jar`
// 也加进类路径，**插件主类随即找不到**：
// ```
// java.lang.ClassNotFoundException: dev.cdl.host.CdlPlugin
//     at org.pf4j.PluginClassLoader.loadClass
// ```
// 且**在插件设置页做任何操作（启用/禁用/删除/配置）都报错**。
// 两次复现：2026-09-16 23:51、2026-09-17 00:00。
//
// **对照证据**：能正常工作的 `dynamic-lyrics-island-for-spw-0.8.9` 的 `lib/` 里
// **只有 `jaudiotagger-3.0.1.jar`，没有任何 sources jar**。
//
// **修法**：专用配置 `lgplSources` —— 可解析、且**不参与 `runtimeClasspath`**，
// 于是"能解析到源码"（LGPL 义务）与"源码不进运行时"（可加载性）两件事同时成立。
val lgplSources: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "只用于解析 LGPL 的 -sources 构件；**不进运行时类路径**。"
}

dependencies {
    lgplSources("net.jthink:jaudiotagger:3.0.1:sources")
}

val fetchLgplSources by tasks.registering {
    description = "解析所有 LGPL 依赖的 -sources 构件，供随包分发（LGPL-2.1 §6）。"
    val targetDir = layout.buildDirectory.dir("lgpl-sources")
    // ⚠️ 从**专用配置** `lgplSources` 解析，**不是** `runtimeClasspath`（BUG-037）。
    // 用 `runtimeClasspath` 会连主 JAR 一起拿到，需要靠文件名过滤；
    // 而专用配置里**只有 sources 构件**，筛选条件天然成立，也不可能把
    // sources 混进 `lib/`（那条路径只读 `runtimeClasspath`）。
    val sourceFiles = lgplSources
    val needles = lgplDeps.toList()
    outputs.dir(targetDir)
    doLast {
        val outDir = targetDir.get().asFile
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }   // 清掉上一轮，避免残留陈旧源码
        // 必须自己从解析结果里**复制** sources jar。
        //
        // ⚠️ 这里曾经只打日志、不复制文件 —— 后果是一个**静默的假通过链路**：
        //   `fetchLgplSources` 什么都不产出 → `lgpl-sources/` 恒为空 →
        //   若 `verifyLgplSources` 也恰好没在 activeLgpl 里匹配到东西（依赖未声明时），
        //   守门人会打「当前无 LGPL 依赖，守门人放行」并放行。
        //   即：**"忘了抓源码"与"没有 LGPL 依赖"在日志上长得一样**。
        //   本次接入 jaudiotagger 时才暴露：必须在源头真正复制，守门人才有意义。
        //
        // 筛选条件同时要求名字含 needle 且含 "sources"：
        //   · 只含 needle     → 会连主 JAR 一起复制（多一个无用文件，且守门人
        //                       可能把主 JAR 误认为"源码"）
        //   · 只含 "sources"  → 会把 kotlin-stdlib 等无关构件的 sources 也抓进来
        val matched = sourceFiles.filter { f ->
            f.name.contains("sources") && needles.any { f.name.contains(it) }
        }.toList()
        if (matched.isEmpty()) {
            logger.lifecycle("[LGPL] 未发现 LGPL 依赖的 sources 构件（${lgplDeps.joinToString()}），跳过源码抓取。")
            return@doLast
        }
        matched.forEach { artifact ->
            val dest = outDir.resolve(artifact.name)
            artifact.copyTo(dest, overwrite = true)
            logger.lifecycle("[LGPL] 已随包源码：${artifact.name}（${dest.length()} 字节）")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LGPL 合规守门人：若声明了 LGPL 依赖却没有对应源码，**让构建失败**
// ─────────────────────────────────────────────────────────────────────────────
// 这是「发布阻塞项」的机械化防线。R1 之所以危险，是因为它**静默** ——
// 打包照样成功，只有法务义务悄悄没履行。此任务把静默失败变成响亮的红灯。
val verifyLgplSources by tasks.registering {
    description = "校验每个 LGPL 依赖都存在对应的 -sources 构件，缺失则构建失败。"
    val srcDir = layout.buildDirectory.dir("lgpl-sources")
    // 同上：必须写 `project.configurations`，否则类型解析到 Kotlin 扩展的同名成员。
    val runtimeFiles = project.configurations.runtimeClasspath
    val needles = lgplDeps.toList()
    doLast {
        // ⚠️ 必须 `.toList()` 物化：Gradle 的 `FileCollection` 只实现 `Iterable<File>`，
        // **不是** `Collection<File>` —— 因此它（及其 `filter` 的结果）没有
        // `isNotEmpty` / `size`，只有 `isEmpty`（`Iterable` 扩展）。
        // 不物化就会在下面两处报 Unresolved reference，且报错位置离病根很远。
        val activeLgpl = runtimeFiles.get()
            .filter { f -> needles.any { f.name.contains(it) } }
            .toList()
        if (activeLgpl.isEmpty()) {
            logger.lifecycle("[LGPL] 当前无 LGPL 依赖，守门人放行。")
            return@doLast
        }
        val available = srcDir.get().asFile.listFiles()?.map { it.name } ?: emptyList()
        val missing = activeLgpl.filter { artifact ->
            val base = artifact.name.removeSuffix(".jar")
            available.none { it.startsWith(base) && it.contains("sources") }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "LGPL 合规失败：以下依赖缺少随包源码（LGPL-2.1 §6 要求）：\n" +
                    missing.joinToString("\n") { "  - ${it.name}" } +
                    "\n请添加对应的 sources 构件，或更新 lgplDeps 列表。"
            )
        }
        logger.lifecycle("[LGPL] 守门人通过：${activeLgpl.size} 个 LGPL 依赖均有源码。")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ★ P0 陷阱：`plugin` 曾把**陈旧 jar** 打进包（2026-09-16 真机实测踩到）
// ─────────────────────────────────────────────────────────────────────────────
// **现象**：真机装上的包只有 131 个 class，且 `platform/`、`window/HoverState`、
// `window/PlaybackControls` **整个不存在** —— 而那些源码在 6 小时前就写好了、
// `compileKotlin` 也过了。用户因此报了一整轮"功能没实现"的假缺陷。
//
// **根因（两条独立缺陷叠加）**：
//   ① `tasks.jar` 的 **up-to-date 判定把它自己骗了**：Gradle 认为 jar 的 inputs 没变，
//      于是**保留 08:55 的旧 jar**，而 `build/classes` 里是 09:49/09:55 的新类。
//   ② `plugin` 这个 Zip **没有任何声明式的 input** —— 它只 `dependsOn(tasks.jar)`，
//      而 `from(tasks.jar.map { zipTree(it.archiveFile) })` 里的 `archiveFile` 是
//      `Provider<RegularFile>`，Gradle **不会**据此推断出"jar 变了我就得重做"。
//      Zip 的 up-to-date 只看 `from` 解析出的**文件集合与内容**，而它读到的是旧 jar。
//
// **为什么这条特别隐蔽**：`compileKotlin` 是绿的、探针是绿的、打包是成功的、
// 日志里也没有任何警告 —— **四道防线全部放行**，只有把包拆开数 class 才看得见。
// 这与本项目已有的 `BUG-011`（换文件不热切换）是两条不同的坑，叠加后表现为
// "代码写了但真机没变"。
//
// **修法**：把编译产物显式声明为这个 Zip 的 **input**，并让它同时 `dependsOn("classes")`。
// 于是"包里的类"与"刚编译的类"在**结构上**必然一致，不依赖任何人的记性。
tasks.register<Zip>("plugin") {
    dependsOn("classes", tasks.jar, "sourceArchive", "fetchLgplSources", "verifyLgplSources", "verifyProjectUrl")
    // 豁免占位符时给产物改名，避免"本地验证包"被误当成发布包分发出去。
    archiveFileName.set(
        if (allowPlaceholder) "cdl-for-spw-${project.version}-LOCAL-ONLY.zip"
        else "cdl-for-spw-${project.version}.zip"
    )
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    // 显式 input：编译产物一变，本任务必被判定为过期而重做。
    // 注意 `main` sourceSet 的 output 已在下面的 `into("classes")` 里被间接使用，
    // 这里再声明一次是为了**只声明、不重复打包**（`inputs.files` 不影响产物内容）。
    inputs.files(sourceSets["main"].output)
        .withPropertyName("compiledClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    into("classes") { from(tasks.jar.map { zipTree(it.archiveFile) }) }
    into("lib") {
        from(configurations.runtimeClasspath.map { files ->
            files.filter {
                it.extension == "jar" &&
                    // ⚠️ **`-sources.jar` 永不进 `lib/`**（BUG-037 的防御性第二道防线）。
                    //
                    // 第一道是"把 sources 依赖声明在专用配置 `lgplSources` 上"，
                    // 那道修的是**根因**。但这类缺陷的后果是"插件整包加载不了"
                    // （`ClassNotFoundException`），代价极高；而它的成因很朴素 ——
                    // 任何人把 sources 构件重新声明成 `runtimeOnly` 就会复发。
                    // 故这里再加一道**与配置无关**的过滤：只要名字是 sources，就不进运行时。
                    //
                    // 源码仍然随包（`source/lgpl/`），LGPL-2.1 §6 的义务不受影响。
                    !it.name.contains("sources") &&
                    hostProvidedPrefixes.none { p -> it.name.startsWith(p) }
            }
        })
    }
    into("licenses") { from("licenses") }
    into("source") { from(tasks.named("sourceArchive")) }
    // LGPL 对应源码：与目标代码同渠道分发（LGPL-2.1 §6 允许）。
    into("source/lgpl") { from(layout.buildDirectory.dir("lgpl-sources")) }
    from("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "README.md")

    // ── 机械守门断言：包内 class 数必须等于刚编译出来的 class 数 ──────────────
    // 这是"陈旧 jar"这一类缺陷的**根因级拦截** —— 上面那两条 input 声明修的是
    // 触发条件，这一段断言的是**结果**：就算将来有人重构任务图、让 input 声明失效，
    // 只要"包里的类"与"编译出的类"对不上，构建就会**响亮地失败**，
    // 而不是安静地产出一个能装进宿主却什么功能都没有的包。
    //
    // 为什么用"数量相等"而不是"逐个比对"：数量不等是最常见也最致命的那种漂移
    // （整包陈旧 / 漏打某个包）。逐个比对成本更高，且哈希比对会被
    // manifest 时间戳之类的非确定性噪声干扰。这里要的是一道**便宜且不会误报**的哨兵。
    doLast {
        val zipFile = archiveFile.get().asFile
        val compiled = sourceSets["main"].output.classesDirs.asFileTree
            .matching { include("**/*.class") }.files.size
        val packed = ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().count {
                it.name.startsWith("classes/") && it.name.endsWith(".class")
            }
        }
        if (packed != compiled) {
            throw GradleException(
                "打包一致性校验失败：包内 class 数 ($packed) != 编译产物 class 数 ($compiled)\n" +
                    "这说明打进包里的**不是刚编译出来的类**（陈旧 jar 的典型症状）。\n" +
                    "请先 `./gradlew clean plugin` 重试；若仍失败，检查 `plugin` 任务的 input 声明。\n" +
                    "产物：${zipFile.absolutePath}"
            )
        }
        logger.lifecycle("[打包一致性] ✅ 包内 class 数 = 编译产物 class 数 = $compiled")

        // ── 机械守门断言二：`lib/` 里**不许有 sources jar**（BUG-037）───────────
        //
        // 真机事故：`lib/jaudiotagger-3.0.1-sources.jar` 被打进包 →
        // SPW 加载插件时类路径被污染 → `ClassNotFoundException: …CdlPlugin`
        // → **在插件设置页做任何操作都报错**（启用/禁用/删除/配置全部失效）。
        //
        // 上面 `into("lib")` 的过滤已经拦掉了它，但那条是**实现**；
        // 这里断言的是**结果** —— 就算将来有人改了过滤条件、或换了打包方式，
        // 只要 `lib/` 里出现 sources jar，构建就**响亮地失败**。
        //
        // 为什么值得单独立一道：这个缺陷的表现是"插件整个装不上"，
        // 而归因时会先怀疑类路径、宿主、打包工具 —— **很难想到是一个源码包**。
        val sourcesInLib = ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".jar") && it.contains("sources") }
                .toList()
        }
        if (sourcesInLib.isNotEmpty()) {
            throw GradleException(
                "打包一致性校验失败：`lib/` 里出现了 sources jar：$sourcesInLib\n" +
                    "这会污染插件运行时类路径，导致宿主加载插件时报 " +
                    "`ClassNotFoundException`（BUG-037）。\n" +
                    "请检查 LGPL sources 依赖是否被声明成了 `runtimeOnly`" +
                    "（应声明在专用配置 `lgplSources` 上）。\n" +
                    "产物：${zipFile.absolutePath}"
            )
        }
        logger.lifecycle("[打包一致性] ✅ lib/ 无 sources jar（运行时类路径干净）")

        // ── 机械守门断言三：自带字体**确实进了包** ─────────────────────────────
        //
        // 自带字体走的是 `src/main/resources/fonts/` → `processResources` → `jar`
        // → 本任务 `into("classes")` 这条**隐式**链路：它不需要任何 `from(...)`
        // 声明就能生效。正因为它隐式，才必须在这里断言 ——
        // 一旦有人改了资源过滤、或把字体挪出 `resources/`，**包会照常构建成功、
        // 只是里面没有字体**。而开发机上字体文件一直都在磁盘上，
        // 本地跑起来一切正常，只有装到用户机器上才暴露为"没有自带字体"。
        //
        // 断言写成"磁盘上有几个、包里就必须有几个"，而不是硬编码 10 个文件名：
        // （原始需求为 11 个，其中 `NotoSansKR-Regular.ttf` 本地未找到、按 10 个交付；
        //   见 THIRD_PARTY_NOTICES.md §9.4。计数式断言对这类增删天然免疫。）
        // 这样字体清单增删时不必改构建脚本，而"漏打"仍然会被拦住。
        // 字体尚未落地时（目录为空）**跳过而非报错** —— 否则在许可/素材未定时
        // 会挡住所有打包。
        //
        // ⚠️ **2026-09-22：本断言当前处于"休眠"状态，这是预期的，不是故障。**
        //    用户拍板取消随包字体（见 THIRD_PARTY_NOTICES.md §9），
        //    `src/main/resources/fonts/` 已整体删除 ⇒ `fontsOnDisk` 恒为空 ⇒
        //    走"跳过"分支、只打一行 lifecycle 日志。
        //
        //    **为什么保留而不删**：它是**条件式**护栏 —— 判据是"磁盘上有字体就必须
        //    全在包里"，只要**没有**字体，它就没有可断言的对象（真值为空）。
        //    这与"恒真的空护栏"不同：把任一个 `.ttf` 放回该目录，它立刻恢复判别力
        //    （漏打即红）。若日后确定永不再随包字体，可连同断言四一起删除。
        val fontDir = layout.projectDirectory.dir("src/main/resources/fonts").asFile
        val fontsOnDisk = fontDir.listFiles { f -> f.isFile && f.name.endsWith(".ttf", true) }
            ?.map { it.name }?.sorted().orEmpty()
        // ★★ 2026-09-23 **反转断言**（用户决定「取消自带字体包」）。
        //
        // 原先 `fontsOnDisk.isEmpty()` 时只打一行「跳过」—— 那让这条检查在"字体不存在"时
        // **永远不会红**。本项目纪律：门禁必须区分「检查坏了」与「检查没跑起来」，
        // 而「跳过」是第三种、也是最坏的一种：**看起来没事**。
        //
        // 现在的判据**两支都是断言**：
        //   · 磁盘无 .ttf ⇒ 断言**包内也不得**有 `classes/fonts/*.ttf` —— 把「取消随包分发」
        //     这个**决定**钉住（否则有人把字体重新放回包，没有任何东西会响）；
        //   · 磁盘有 .ttf ⇒ 断言它们**全部进包**（原判据，防 `processResources` 静默漏掉）。
        // 无论哪一支，「重新随包分发字体」都必须有人**显式**改这条断言才能通过。
        //
        // 用户原话：「**取消自带字体包，调用自带字体包的路线转到在本机选择的字体上**」
        // （理由含许可：MiSans 协议 §2 第 3 条禁止分发字体副本，见 THIRD_PARTY_NOTICES.md C7/§9.2）。
        val packedFontNames = ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("classes/fonts/") && it.endsWith(".ttf", true) }
                .map { it.removePrefix("classes/fonts/") }
                .toList()
        }
        if (fontsOnDisk.isEmpty()) {
            if (packedFontNames.isNotEmpty()) {
                throw GradleException(
                    "打包一致性校验失败：磁盘 `src/main/resources/fonts/` 已无字体，" +
                        "但**包内仍有** ${packedFontNames.size} 个 .ttf：$packedFontNames\n" +
                        "用户 2026-09-22 已决定「取消自带字体包，调用自带字体包的路线转到在本机选择的字体上」\n" +
                        "（理由含许可：MiSans 协议 §2 第 3 条禁止分发字体副本）。\n" +
                        "若这是**有意**恢复随包分发，请先恢复许可材料并**显式**改本断言；\n" +
                        "否则请检查 `processResources` / `into(\"classes/fonts\")` 的打包声明。\n" +
                        "产物：${zipFile.absolutePath}"
                )
            }
            logger.lifecycle("[打包一致性] ✅ 自带字体已按决定移除（磁盘与包内均无 .ttf）")
        } else {
            val missingFonts = fontsOnDisk.filter { it !in packedFontNames }
            if (missingFonts.isNotEmpty()) {
                throw GradleException(
                    "打包一致性校验失败：以下自带字体在磁盘上存在，却没有进包：$missingFonts\n" +
                        "自带字体经 `src/main/resources/fonts/` → jar → `classes/` 分发，\n" +
                        "请检查 `processResources` 的过滤规则（或 `exclude`）是否把它排除了。\n" +
                        "产物：${zipFile.absolutePath}"
                )
            }
            logger.lifecycle(
                "[打包一致性] ✅ 自带字体 ${fontsOnDisk.size} 个全部在包内（classes/fonts/）"
            )
        }

        // ── 机械守门断言四：MiSans 许可材料**确实随包**（协议 §2 第 4 条）────────
        //
        // 为什么这条值得单独立：它守的不是「功能对不对」，而是**一份法务义务**。
        // MiSans 字体知识产权许可协议 §2 第 4 条原文：
        //   "You shall retain the copyright notice and this Agreement in any copies
        //    of MiSans fonts"
        // 即：**只要有字体副本，就必须带着版权声明与协议全文**。
        // 漏了的后果不是崩溃、不是功能缺失，而是**分发即违约** —— 没有任何运行时
        // 症状能把它暴露出来，只能靠这道断言。（详见 THIRD_PARTY_NOTICES.md §9）
        //
        // 上面 `into("licenses") { from("licenses") }` 是**目录级**声明，
        // 新增文件天然被覆盖 —— 正因为它「天然生效」，将来一旦有人把 licenses
        // 的打包改成白名单式（逐个 `from("licenses/xxx.txt")`），新文件就会
        // **静默漏掉**，而包照常构建成功。故这里断言的是**结果**，与实现无关。
        //
        // 义务**随字体副本触发**：包内没有自带字体时，无需许可材料，故跳过而非报错
        // （与断言三同一口径：许可/素材未定时不该挡住所有打包）。
        //
        // ⚠️ **2026-09-22：本断言同样处于"休眠"状态**（包内已无字体，见断言三的说明）。
        //    它与断言三是同一形状的**条件式**护栏：只要没有字体副本，
        //    §2 第 4 条的义务就不被触发，断言没有可断言的对象。
        //    若日后重新随包字体，它会立刻恢复判别力（缺许可材料即红）。
        //    ⚠️ `licenses/MiSans-LICENSE.*` 本身**仍随包**（`into("licenses")` 未动）——
        //    留作历史可追溯，见 `NOTICE` 里那条已改为"历史记录"的 MiSans 声明。
        val packedFontCount = ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().count {
                it.name.startsWith("classes/fonts/") && it.name.endsWith(".ttf")
            }
        }
        if (packedFontCount == 0) {
            logger.lifecycle("[打包一致性] ⚠️ 包内无自带字体，跳过 MiSans 许可材料断言")
        } else {
            val requiredLicenseFiles =
                listOf("licenses/MiSans-LICENSE.txt", "licenses/MiSans-LICENSE.pdf")
            val packedLicenses = ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
            val missingLicenses = requiredLicenseFiles.filter { it !in packedLicenses }
            if (missingLicenses.isNotEmpty()) {
                throw GradleException(
                    "打包一致性校验失败：包内有 $packedFontCount 个 MiSans 字体，但以下许可材料没有进包：$missingLicenses\n" +
                        "MiSans 协议 §2 第 4 条要求「任何字体副本都须保留版权声明与本协议」，\n" +
                        "缺了它们，**分发这个包本身就是违约**，且没有任何运行时症状能暴露它。\n" +
                        "请检查 `plugin` 任务里 `into(\"licenses\")` 的打包声明。\n" +
                        "产物：${zipFile.absolutePath}"
                )
            }
            logger.lifecycle(
                "[打包一致性] ✅ MiSans 许可材料 ${requiredLicenseFiles.size} 件在包内（licenses/）"
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 验证探针（**仅供本地验证，不随包分发**）
// ─────────────────────────────────────────────────────────────────────────────
// 目的：证明设置系统 / 播放时间轴 / 字体历史 / 封面解析**真的能干活**，
// 而不只是"能编译"。全部为独立计算，不依赖宿主运行环境。
//
// 为什么单独建 sourceSet 而不是塞进 `test`：
//   `test` 需要引入 JUnit 等测试框架依赖，而本项目**严禁随意引依赖**
//   （每多一个依赖就多一份许可合规义务，见 THIRD_PARTY_NOTICES.md）。
//   本探针只用 `kotlin("stdlib")` 就能跑，代价是不进 CI 的测试报告 ——
//   这是刻意的取舍，本地验证的收益远大于"报告好看"。
//
// ⚠️ `main` 的 stdlib 是 `compileOnly`（宿主提供，不随包）——
//    `compileOnly` **不会**传递给 probe，若不显式补上，编译期会报一大片
//    `Unresolved reference 'println'`，看上去像代码坏了，实际只是探针缺 stdlib。
//
sourceSets {
    create("probe") {
        kotlin.setSrcDirs(emptyList<String>())
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
        // ⚠️ 各探针（ControlsProbe / MenuProbe / LyricsSplitProbe）都在文件顶层
        //    声明了同名的 `var ok`、`var bad`、`fun t(...)`。同一个 Kotlin 编译
        //    单元里顶层同名即 `Conflicting declarations`，会让**所有**探针都编译不过，
        //    且报错位置指向"先被读到的那个文件"，很容易被误判成那个文件写坏了。
        //    下面的 `runProbe` 会按 `-PprobeMain=` 用 `-P` 把参与编译的**文件**
        //    收窄到所选探针，详情见紧随其后的注释。
    }
}

// ⚠️ stdlib 必须在**顶层的 `dependencies {}` 块**里按 configuration 名声明。
//    写在 `sourceSets { create("probe") { dependencies { ... } } }` 里**不生效** ——
//    `SourceSet` 上的 `dependencies {}` 作用域解析不到 `implementation`，
//    配置被静默忽略，表现为编译期一大片 `Cannot access built-in declaration 'kotlin.String'`。
//    （这一点与"写错了会报错"的直觉相反 —— 它是**静默失败**，故记在此处。）
dependencies {
    "probeImplementation"("org.jetbrains.kotlin:kotlin-stdlib")
    // ⚠️ 探针**必须**能看到宿主 API —— `main` 里 workshop 是 `compileOnly`，
    //    **不会传递给 probe 源集**（这是本项目记录过的静默失败：`compileOnly`
    //    只作用于声明它的那一个源集）。
    //    需要它的探针（如 `SettingsSyncProbe`）要自己实现 `ConfigHelper` 桩，
    //    而没有这个依赖就会报 `Unresolved reference: ConfigHelper`。
    //    探针不进插件包，故这里是 `probeImplementation` 而非 `compileOnly`。
    //
    // ⚠️ **`isTransitive = false` 是硬要求，与 `main` 的写法一致**：
    //    workshop 的 POM 会把 Compose UI / foundation / salt-ui 等**宿主侧**依赖
    //    一起拉进来，而它们在离线模式下**没有缓存** →
    //    `Could not resolve org.jetbrains.compose.ui:ui:1.11.0-alpha01 … for offline mode`。
    //    我们只需要那几个**接口**（`ConfigHelper` 等），传递依赖一个都不要。
    "probeImplementation"(workshop) { isTransitive = false }
    // ⚠️ 2026-09-24 补：`pf4j` 也**必须**给探针 —— 同一条"`compileOnly` 不传递给 probe"的坑，
    //    这次栽在**运行时**而不是编译期（编译过了，跑起来才炸）。
    //
    //    取证（`TimerPairingProbe`，2026-09-24）：探针要驱动 `CdlPlugin` 的
    //    **释放序列**（`releaseRuntimeForProbe()`）—— 而 `CdlPlugin : SpwPlugin`，
    //    `SpwPlugin` 又继承 `org.pf4j.Plugin`。workshop 那个 jar 探针有 ✓，
    //    **pf4j 没有** ✗ ⇒ 类**定义**阶段就炸：
    //        `NoClassDefFoundError: org/pf4j/Plugin` ← `ClassNotFoundException: org.pf4j.Plugin`
    //    症状有迷惑性：**前面 13 条 `TimerResolution` 断言全绿**，只在切到"插件释放序列"
    //    那一段时才炸 —— 看上去像产品坏了，实际只是探针 classpath 缺一个 jar。
    //
    //    为什么是 `probeImplementation` 而不是 `probeRuntimeOnly`：
    //    `main` 里 pf4j 是 `compileOnly` ⇒ 探针编译期把 `CdlPlugin` 的**父类型**
    //    解析成了 error type（能编译过，但那是不诚实的 classpath）。写成
    //    `probeImplementation` 让编译期与运行期看到同一份类型信息，
    //    **顺带消掉这个潜伏的未解析父类型**。
    //
    //    ⚠️ 与铁律 A2「PF4J 宿主提供、不打包」**不冲突**：探针源集**不进插件包**
    //    （`lib/` 的过滤规则见本文件下方 `"pf4j-"` 那条），这里只是让探针 JVM 能加载宿主类。
    //    pf4j 是 Apache-2.0，且**早已是**本项目的声明依赖 ⇒ 不新增任何许可义务。
    "probeImplementation"("org.pf4j:pf4j:3.12.0")
    // ⚠️ 2026-09-24 补：**JNA 也要给探针** —— 同一条"`main` 的依赖不传递给 probe"的坑，
    //    这是**第三次**同族（第一次 pf4j 编译期、第二次 pf4j 运行期、本次 JNA 运行期）。
    //
    //    取证（`FramePacingProbe` 的 A/B 段，2026-09-24）：
    //    `timeBeginPeriod(1) = 失败 / 非 Windows` —— 而**本机就是 Windows**。
    //    根因不是"平台不支持"，而是探针 JVM 里 `com.sun.jna.Native` **不存在**：
    //    `main` 里 JNA 是 `implementation`（第 85–86 行）⇒ 它只在 `mainRuntimeClasspath` 上，
    //    而 `runProbe` 的 classpath 是
    //    `files(probe.output, main.output) + probeRuntimeClasspath`
    //    —— `main.output` 只是 **classes 目录**，不含 main 的任何依赖。
    //    ⇒ `Native.load` 抛 `NoClassDefFoundError` ⇒ 被 `TimerResolution` 自己的
    //    `runCatching` **静默吞掉** ⇒ `backend()` 返回 null ⇒ `raise()` 直接 `return`。
    //
    //    症状有迷惑性：**看起来像"本机不支持高精度定时器"**（一个环境结论），
    //    实际只是**探针少一个 jar**。⇒ 那一整段 A/B 实验**是空转的**：
    //    它测的是"没提精度时的表现"，却被当成"提了精度后的表现"来读。
    //    **这是"假绿"的一种：实验没报错，但它没有测它声称要测的东西。**
    //
    //    为什么是 `probeImplementation` 而不是 `probeRuntimeOnly`：与 pf4j 同理 ——
    //    让编译期与运行期看到同一份类型信息（探针源码里 `import com.sun.jna.*` 的
    //    任何使用都不至于解析成 error type）。
    //
    //    ⚠️ 与铁律 A2「依赖只增许可义务」**不冲突**：JNA 是 **Apache-2.0 / LGPL-2.1 双许可**，
    //    且**早已是**本项目的声明依赖（`TimerResolution` 用它调 `winmm.dll`）⇒
    //    不新增任何许可义务。探针源集**不进插件包**，故也不会改变随包分发的依赖清单。
    "probeImplementation"("net.java.dev.jna:jna:5.17.0")
    "probeImplementation"("net.java.dev.jna:jna-platform:5.17.0")
}

// 为什么支持 `-PprobeMain=` 切换入口：
//   各探针的**前置条件不同** —— `RegistryProbeKt` 是纯计算（任何环境都能跑），
//   `FontProbeKt` 需要真实字体文件（依赖 `实验/fonts/MiSans_Global` 存在）。
//   若合成一个 main，字体文件缺失时会把整个验证卡住；
//   分开则"能跑哪个跑哪个"，验证的颗粒度更细。
//
// ⚠️ 为什么 `--probeMain` 对应的探针要**单独编译**：
//   `probe` 是一个独立的 Kotlin 编译单元，源集里所有 `.kt` **一起**编译。
//   而各探针（历史原因）都在文件顶层声明了同名的断言助手：
//     `ControlsProbe.kt` / `MenuProbe.kt` / `LyricsSplitProbe.kt` 都定义了顶层
//     `var ok`、`var bad`、`fun t(label, actual, expected)`。
//   顶层同名声明在同一个编译单元里是 `Conflicting declarations`，
//   于是**任何一个探针都编译不过**，且报错位置指向"先被读到的那个文件"，
//   极易被误读成"那个文件写坏了"。
//   （`RegistryProbe.kt` 侥幸逃过是靠它用了 `passed`/`failed`/`check` 这套不同命名。）
//
//   所以：只把**所选探针那一个文件**喂给 `compileProbeKotlin`，一次编译一个探针。
//   这样既不改动任何探针文件（不触碰队友的代码），又让每个探针都能独立运行。
//   新增探针时**无需改这里** —— 默认就是"只编译被选中的那一个"，
//   只有当某探针额外依赖同目录的辅助文件时，才在 `probeExtraSources` 里补一行。
//
// ⚠️ 为什么是 `-P`（传给 kotlinc 的 "文件"）而不是 `setSrcDirs`：
//   `KotlinCompile` 的源集目录**只接受目录**，传 `Xxx.kt` 会报
//   `Source directory '…\ControlsProbe.kt' is not a directory`（实测）。
//   而 kotlinc 命令行本身接受 `.kt` 文件，`-P` 正是往命令行追加参数的官方口子，
//   故用 `-P <绝对路径>` 精确指定文件。副作用是这里必须给出绝对路径。
val probeMainName: String = providers.gradleProperty("probeMain").getOrElse("RegistryProbeKt")
val probeDir = layout.projectDirectory.dir("src/probe/kotlin")
// 探针名 → 文件名：Kotlin 的 `@file:JvmName` 默认取文件名，故 `XxxProbeKt` → `XxxProbe.kt`。
val probeSelected = probeMainName.removeSuffix("Kt") + ".kt"
val probeExtraSources: List<String> = emptyList()  // 某探针需要同目录辅助文件时在此补
val probeFiles = (listOf(probeSelected) + probeExtraSources).map { probeDir.file(it) }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileProbeKotlin") {
    notCompatibleWithConfigurationCache("探针参与编译的文件按 -PprobeMain 动态收窄")
    // 传**目录的父级**会连带编到邻居探针，所以这里逐个给文件。
    source(probeFiles.map { it.asFile })

    // ══════════════════════════════════════════════════════════════════════════
    // ★★ 2026-10-01（P1，「甲」方案）：**强制重编探针**，取代脚本里的 `--rerun-tasks`
    // ══════════════════════════════════════════════════════════════════════════
    //
    // ## 为什么需要它（不写就会**假红/假绿**）
    //
    // 本任务的输入 `source(probeFiles)` **只含 `-PprobeMain` 指定的那一个 `.kt`**。
    // ⇒ 当**只有 `main` 源改了、探针源没改**时，本任务的输入快照**没变**
    //   ⇒ 被判 `UP-TO-DATE` ⇒ **不重编** ⇒ `build/classes/kotlin/probe/` 里留着
    //   **上一次（甚至更早）某个 `-PprobeMain`** 编出来的 `.class`
    //   ⇒ **用旧探针代码去断言新主源码**。
    //
    // ★ 两个方向都**实测出现过**（详见 `tools/run_all_probes.sh` 的同段注释）：
    //   · **假红**：`ColorPickerProbe` 报 119/1，单独重跑 ⇒ 120/0（源码与产物均未变）
    //   · **假绿**（更危险）：`RegistryProbe` §11.9 读到旧值，而源码里恒为新值
    //
    // ## ★★ 为什么这一行与 `--rerun-tasks` **语义等价**（这是本改动的全部依据）
    //
    // `--rerun-tasks` 对**本次构建**强制重跑**全部** task ⇒ 它当然也重编了本任务，
    // 但**代价是连 `compileKotlin` 也重编** —— 实测 `compileKotlin --rerun-tasks` = **101 s**，
    // 而**每个探针都要付这 101 s** ⇒ 56 探针 ≈ **86 min**（这就是门禁慢的**唯一**成因）。
    //
    // ★ 而 `--rerun-tasks` 想保的语义**只有一条**：「**探针一定重编**」。
    //   本行 `upToDateWhen { false }` 恰好**只**提供这一条 ⇒
    //   **`main` 的 up-to-date 判定交回 Gradle 自己**（它是**正确**的：基于输入快照，
    //   不是"我以为没变"）⇒ 实测单探针 **92.6 s → 24 s**，56 探针 **86 min → ~22 min**（3.9×）。
    //
    // ⚠️ **不要**改成 `dependsOn("compileProbeKotlin")` 之类 ——
    //   依赖**本来就有**（`runProbe` 的 `classpath` 引用 `sourceSets["probe"].output`，
    //   经 Gradle 的 `builtBy` **隐式**建立；判据 = `runProbe --dry-run` 里
    //   `:compileProbeKotlin` 出现在 `:runProbe` **之前**）。
    //   ★★ **「`dependsOn` 块里没写」≠「没有依赖」** —— 2026-10-01 实测更正了旧注释。
    //   本改动**只需**让它**不被跳过**。
    //
    // ★ **逃生口**：若怀疑"class 陈旧"，用 `SPW_PROBE_FULL=1` 跑门禁
    //   （脚本会退回 `--rerun-tasks`，语义更保守）。
    //
    // ── ★★ 边界登记（2026-10-01 安全性复审，`migration/64-*`）─────────────────
    //
    // ⚠️ **B1（真实但当前不成立）**：本行 `upToDateWhen { false }` **只关掉 `UP-TO-DATE`**，
    //    ★ **不关 `FROM-CACHE`**（构建缓存）。
    //    ⇒ **若将来有人开启 `org.gradle.caching`**，本任务仍可能**从缓存取** class
    //      ⇒ 那时必须**复核本改动**（可能需 `outputs.cacheIf { false }`）。
    //    ★ **当前不成立**：本项目**未开** build cache（已复审，见 `migration/64-*` §3.1）。
    //
    // ⚠️ **B2**：探针源集**当前没有 `.java`**（已复审）⇒ `compileProbeJava` 恒为 `NO-SOURCE`。
    //    ⇒ **若将来新增 `.java`**，那个任务**同样需要**强制（它不在本行的保护范围内）。
    // ══════════════════════════════════════════════════════════════════════════
    outputs.upToDateWhen { false }
}
tasks.register<JavaExec>("runProbe") {
    group = "verification"
    description = "运行端到端验证探针（默认 RegistryProbeKt；-PprobeMain=<Xxx>Kt 跑其它探针）。"
    mainClass.set(probeMainName)
    // ── ⚠️⚠️ 2026-09-21：**必须显式依赖 `compileKotlin`**（否则会**假绿**）──────────
    //
    // `classpath` 里虽然含 `sourceSets["main"].output`（理论上带 `builtBy` 隐式依赖），
    // 但实测发生过：**`compileKotlin` 失败时 `runProbe` 仍然跑起来了**，
    // 用的是**上一次成功的 class** —— 于是探针**全绿**，而它测的那份 `main` **根本没编过**。
    //
    // 真机取证（2026-09-21，`PresetAuditProbe`）：
    //   `compileKotlin` FAILED（`IslandPopupMenu.kt` 有 5 处编译错误），
    //   紧接着跑 `PresetAuditProbe` → **`PASS=5  FAIL=0`**。
    //   那条"5/5 全绿"**什么都没证明** —— 它跑的是旧 class。
    //
    // 危害等级：**这是本项目最危险的一类假信号** ——
    //   "门禁绿了"会让所有人停止怀疑，而它测的是一份不存在的代码。
    // → **把依赖写死**，不依赖 Gradle 的隐式语义（"能靠结构消除的状态，不靠纪律维持"）。
    dependsOn("compileKotlin")
    classpath = files(sourceSets["probe"].output, sourceSets["main"].output) +
        configurations["probeRuntimeClasspath"]
    // 探针输出含中文，控制台默认编码在 Windows 上是 GBK，会乱码。
    // 设为 UTF-8 让 PASS/FAIL 与说明文字可读 —— 否则验证结论没法直接引用。
    //
    // ⚠️ 2026-09-20：**显式给小堆 + SerialGC**。
    // 本机提交限额偏紧，而默认堆是物理内存的 1/4 —— G1 会一次性预留几百 MB 虚拟空间，
    // 直接报 `os::commit_memory(…) failed; error='页面文件太小' (DOS error/errno=1455)`
    // → 探针 JVM **起不来** → **一行输出都没有** → 汇总脚本只能报"认不出汇总行"
    // （把"机器资源不够"误报成"探针没有汇总行"）。
    // 探针都是单线程短命程序，SerialGC 完全够用，且几乎不预留虚拟空间。
    jvmArgs(
        "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
        "-XX:+UseSerialGC", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
    )
}
