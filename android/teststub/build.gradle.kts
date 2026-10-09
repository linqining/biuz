plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "cn.biuz.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

// 本地替身服务器（预留）：Ktor 同构 iOS Tests/E2ELoginStubServer——协议握手/快照/增量/
// 命令回执伪造，必须校验载荷形状（strict 对拍，docs/Android开发交接.md §2.4/§9.1）。
// CIO 引擎（纯 JVM，无原生依赖）+ ktor-network（原始套接字层）+ websockets 插件（WS 端点）。
dependencies {
    // 替身与被测客户端「构造与校验同源」：JSONValue/RPCSerialization/RPCFrame/
    // PValidator/CommandSchemas 全部复用 protocol（api：替身公开 API 携带 protocol 类型）。
    api(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-server-core:3.2.0")
    implementation("io.ktor:ktor-network:3.2.0")
    implementation("io.ktor:ktor-server-cio:3.2.0")
    implementation("io.ktor:ktor-server-websockets:3.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test-junit5"))
    // 预置：替身服务器场景单测（协程驱动）用，后续平移单测的人禁止再改本 build 文件。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
