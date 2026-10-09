// BiuZ Android（bundle cn.biuz.mobile 的移动遥控台，包名根 cn.biuz.android）。
// 纯 Kotlin JVM 多模块工程：禁止引入 Android Gradle 插件或任何 android 库依赖
// （模块分层与职责见 docs/Android开发交接.md §8.1）。
rootProject.name = "biuz-android"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// 五个 JVM 模块：
//   protocol  — 纯协议层（iOS 纯协议逻辑直译，仅 stdlib + coroutines）
//   relay     — 中继传输（OkHttp WS；iOS Services/Relay 四件）
//   connection— Connection.call 唯一出口 + ReadOnlyGate + AckActivationBarrier + 装配
//   store     — 三大 Store + 协议面 interface + 空实现（未连接态）
//   teststub  — Ktor 本地替身服务器（E2ELoginStubServer 同构）
include(":protocol")
include(":relay")
include(":connection")
include(":store")
include(":teststub")
