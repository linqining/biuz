plugins {
    kotlin("jvm")
}

group = "cn.zhupi.android"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

// 纯协议层：仅 kotlin-stdlib（Kotlin 插件自动注入）+ kotlinx-coroutines-core。
// 禁止引入 OkHttp/Ktor 或任何 android 库（docs/Android开发交接.md §8.1）。
// 实装内容：iOS 纯协议逻辑直译（V4Wire/RPCSerialization/ChannelClient/ProtocolSchema/
// CommandSchemas/ConversationCommandFactory/RelayFrameCodec/ReadOnlyGate/Models，§2.1）。M1 里程碑。
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test-junit5"))
    // 预置：M1 平移 ProtocolSchemaTests/CommandFactoryTests 等协程单测用，
    // 后续平移单测的人禁止再改本 build 文件。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
