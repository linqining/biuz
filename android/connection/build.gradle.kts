plugins {
    kotlin("jvm")
}

group = "cn.biuz.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

// 连接层（2026-10-09 M2 实装）：ZCodeServerConnection 同构移植——Connection.call 唯一出口
// （ReadOnlyGate 接线）+ 帧面按 workspace 多路幂等挂载 + v4 握手单点（M6 桥开钩子接线面）
// + ServerConfig/ServerRemoteInfo 连接模型 + AppSession 装配层（连接生命周期 + Store 装配
// 策略 + 桌面只读信息投影）+ ServerRegistry。
// 传输：OkHttp WebSocket 承接 protocol 的 WebSocketTransport 接口（局域网直连
// GET /ws?token=… 形态，协议文档 §3.2）。
dependencies {
    // 公开 API 携带 protocol 类型（RPCChannelTransport/JSONValue/EventSubscription/…）→ api
    api(project(":protocol"))
    // AppSession 装配面公开携带 Store 类型（StoreSurface/RemoteTaskStore/
    // RemoteSessionsIndexStore/Empty*Store）→ api；:store 仅依赖 :protocol，无环。
    api(project(":store"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // WS 传输（任务指定 OkHttp；4.x 稳定线，与 Kotlin 2.2 兼容）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test-junit5"))
    // 预置：后续平移单测的协程驱动用例（与 protocol/teststub 同口径）。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // 直连 E2E：对 :teststub 的 Ktor 替身起真实回环 WS（建链→握手→call→帧序断言）。
    testImplementation(project(":teststub"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
