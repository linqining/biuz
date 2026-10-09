// BiuZ Android（bundle cn.biuz.mobile 的移动遥控台，包名根 cn.biuz.android）。
// 多模块工程：protocol/teststub 保持纯 Kotlin JVM；:app 自 2026-10-09（M2 开工）起引入
// Android Gradle Plugin（AGP 8.13.2，与 Gradle 8.14.3 官方兼容、google() 可解析）+ Compose UI。
// 模块分层与职责见 docs/Android开发交接.md §8.1。
pluginManagement {
    repositories {
        google()          // AGP 与 androidx 制品
        mavenCentral()    // kotlin 插件
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()          // androidx.compose / activity / navigation / lifecycle
        mavenCentral()    // kotlinx-coroutines、OkHttp、Ktor
    }
}

rootProject.name = "biuz-android"

// 六个模块：
//   protocol  — 纯协议层（iOS 纯协议逻辑直译，仅 stdlib + coroutines，禁第三方 JSON/Android 依赖）
//   relay     — 中继传输（OkHttp WS；iOS Services/Relay 四件）
//   connection— Connection.call 唯一出口 + ReadOnlyGate + AckActivationBarrier + 装配
//   store     — 三大 Store + 协议面 interface + 空实现（未连接态）
//   teststub  — Ktor 本地替身服务器（E2ELoginStubServer 同构）
//   app       — 单 Activity Compose UI（Android 应用壳，applicationId cn.biuz.android）
include(":protocol")
include(":relay")
include(":connection")
include(":store")
include(":teststub")
include(":app")
