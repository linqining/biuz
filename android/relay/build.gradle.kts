plugins {
    kotlin("jvm")
}

group = "cn.biuz.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

// 中继传输（预留）：WebSocket/HTTP 传输挂 OkHttp。
// 实装内容：iOS Services/Relay 四件（RelayFrameCodec/RelayTransport/RelayChannelClient/
// RelayLink），URLSession→OkHttp 替换（docs/Android开发交接.md §2.1/§8.1）。M6 里程碑。
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
