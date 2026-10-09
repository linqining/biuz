plugins {
    kotlin("jvm")
}

group = "cn.biuz.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

// 连接层（预留）：Connection.call 唯一出口 + ReadOnlyGate + AckActivationBarrier + AppSession 装配
// （docs/Android开发交接.md §8.1；红线见 §0）。架构核心必须与 iOS ZCodeServerConnection 同构。
dependencies {
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
