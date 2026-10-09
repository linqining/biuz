plugins {
    kotlin("jvm")
}

group = "cn.biuz.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

// 业务 Store 层（预留）：三大 Store + 协议面 interface + 空实现（未连接态如实失败，不装配 Mock）
// （docs/Android开发交接.md §8.1；红线 §0-4：Mock 仅测试 target 装配）。
dependencies {
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
