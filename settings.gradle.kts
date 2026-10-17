pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // SPW Workshop API 由 JitPack 提供（与宿主 build.gradle.kts 的坐标一致）。
        //
        // ⚠️ 这里**必须 includeGroupByRegex**，不能只写 `includeGroup("com.github.Moriafly")`。
        // 现场（2026-10-18）：改成子模块坐标
        //   `com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev22`
        // 之后，Gradle 报 `Could not find …` 且**只去 mavenCentral 找过**
        // ⇒ 说明 JitPack 仓库被 content filter 整体排除了。
        // 原因：`includeGroup` 是**精确匹配**，而多模块坐标的 group 是
        //   `com.github.Moriafly.spw-workshop-api`（比 `com.github.Moriafly` 多一截）。
        // 用正则把 JitPack 组织下**所有** group 都放进来（含无子模块时的老坐标）。
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.Moriafly(\\..*)?") } }
    }
}
rootProject.name = "cdl-for-spw"
