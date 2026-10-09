// 根工程：只声明插件版本（均可从 Gradle 插件门户解析的稳定版），不放任何构建逻辑。
// Kotlin 2.2.0 与本工程 Gradle 8.14.3 官方兼容；各模块工具链/测试基建见模块级 build.gradle.kts。
plugins {
    kotlin("jvm") version "2.2.0" apply false
    // teststub 需要 @Serializable 编译期代码生成（kotlinx-serialization-json 运行时的前提）。
    kotlin("plugin.serialization") version "2.2.0" apply false
}
