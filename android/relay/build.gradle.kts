plugins {
    kotlin("jvm")
}

group = "cn.biuz.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

// 中继传输（2026-10-10 M6 实装）：iOS Services/Relay 四件的 :relay 侧两件——RelayTransport
// （WS JSON 文本帧 + auth 握手 + 心跳/重连 + rpc-frame 通道）与 RelayChannelClient（桥内
// 标准 RPC，实现 :protocol 的 RPCChannelTransport 门面）。RelayLink/RelayFrameCodec 在
// :protocol 的 relay 子包（M1 已落位）。传输挂 OkHttp（任务指定口径：wss://zcode.z.ai/remote/v4）。
dependencies {
    // 公开 API 携带 protocol 类型（RPCChannelTransport/RelayFrameCodec/RelayLinkConfig/
    // JSONValue/RPCValue…）→ api（与 :connection 同口径）
    api(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test-junit5"))
    // 中继全链替身级验收：对 :teststub 的 rpc-frame 中继模式端口起真实回环 WS。
    testImplementation(project(":teststub"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Ktor 内部异常经 SLF4J（缺省 NOP 全静默）——中继链路联调需要引擎日志可见
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
