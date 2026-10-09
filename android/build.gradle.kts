// 根工程：只声明插件版本（均可从 google()/mavenCentral()/插件门户解析的稳定版），不放任何构建逻辑。
// Kotlin 2.2.0 与本工程 Gradle 8.14.3 官方兼容；AGP 8.13.2 官方最低 Gradle 8.13、原生支持 compileSdk 36，
// 是 8.x 稳定线最新（9.x 需更新 Gradle 且 DSL 有破坏性变更，不采用）。各模块工具链/测试基建见模块级 build.gradle.kts。
plugins {
    kotlin("jvm") version "2.2.0" apply false
    // teststub 需要 @Serializable 编译期代码生成（kotlinx-serialization-json 运行时的前提）。
    kotlin("plugin.serialization") version "2.2.0" apply false
    // :app 的 Android/Compose 侧插件（与 Kotlin 2.2.0 严格同版本）。
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("com.android.application") version "8.13.2" apply false
}
