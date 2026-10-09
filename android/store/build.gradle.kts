plugins {
    kotlin("jvm")
}

group = "cn.biuz.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
    // 2026-10-09（M3 装配轮补丁，修 :app:mergeLibDexDebug）：
    // interface 的 suspend 默认方法经 DefaultImpls 编译（ConversationStore$DefaultImpls）
    // 会被 AGP 8.13.2 的 D8 拒绝——"Multiple annotations of type
    // kotlin.coroutines.jvm.internal.DebugMetadata"（D8 已知问题）。-Xjvm-default=all 改为
    // 生成 JDK8 default 方法、不再产出 DefaultImpls 类（行为面等价：Kotlin 恒动态分派，
    // 见 StoreProtocols.kt 文件头映射约定）。消费方 :connection/:app 均 Kotlin 2.2，兼容。
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

// 业务 Store 层：三大 Store + 协议面 interface + 空实现（未连接态如实失败，不装配 Mock）
// （docs/Android开发交接.md §8.1；红线 §0-4：Mock 仅测试 target 装配）。
dependencies {
    // 协议面类型（Conversation/TaskRecord/JSONValue/RPCChannelTransport…）全部来自 :protocol
    // ——Store 层禁自备第二种 JSON/模型树（RPCSerialization.kt JSONValue KDoc 下游统一引用约束）。
    api(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
