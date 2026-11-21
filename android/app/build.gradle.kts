import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.biuz.android.app"
    compileSdk = 36

    defaultConfig {
        // 命名裁决（branding/naming.md）：上架名 BiuZ，applicationId 自有域名反写；
        // namespace（代码/R 类）为 cn.biuz.android.app，与模块目录一致。
        applicationId = "cn.biuz.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG 承接开发者模式调试开关（交接 §5-17：手动输入/指引/令牌说明收进
        // 开发者模式；正式包默认关闭，连点手势预留见 ConnectScreen 品牌行）
        buildConfig = true
    }

    testOptions {
        // 占位：JVM 单测平台切换统一走文件尾 tasks.withType<Test>（与 store/connection 模块同口径）
    }
}

dependencies {
    // 工程内分层（docs/Android开发交接.md §8.1）：UI 只触达 store/protocol 面，
    // 一切 RPC 经 connection 的唯一出口（红线 §0；M2 起逐步接线）。
    implementation(project(":protocol"))
    implementation(project(":connection"))
    implementation(project(":store"))
    implementation(project(":relay"))

    // Compose：BOM 统一口径（ui/material3 等免版本号）。
    // 选 2025.06.01 而非更新 BOM：本工程 Kotlin 固定 2.2.0，过新 BOM 的制品可能用更高
    // Kotlin 编译（metadata 版本超出 2.2.0 可读范围）。BOM 不覆盖 activity/navigation/lifecycle，需显式版本。
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")

    // 扫码链路（M2 界面轮）：CameraX 分析帧取景（PreviewView 互操作 + ImageAnalysis Y 平面）
    // + zxing core 纯解码（无 javase/Android 扩展；分析与解码全在分析线程，主线程零阻塞）。
    // CameraX 选 1.4.2 稳定线（compileSdk 36 兼容；bind/unbind 契约要求主线程，禁同步等待见 ScanScreen）。
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.zxing:core:3.5.3")

    // JVM 单测：QR 识别链（zxing QRCodeWriter 编码回灌）+ 会话列表分组纯函数
    // + 替身服务器活体（StubLiveServerTest：真机/模拟器链路验证时在宿主机起替身）
    // + 聊天 VM/渲染层解析（kotlinx-coroutines-test 虚拟时钟驱动投影/重绑/延迟面板）
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(project(":teststub"))
}

// 与 protocol 等纯 JVM 模块的工具链（jvmToolchain(17)）保持同一字节码层级。
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// JVM 单测平台（testDebugUnitTest/testReleaseUnitTest）：与 store/connection 模块同口径。
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 替身活体宿主开关（StubLiveServerTest 文档口径 `gradle -D… -Dbiuz.stubLive=true`）：
    // gradle -D 落在 daemon JVM，不自动透传 fork 的 test worker——显式转发
    System.getProperty("biuz.stubLive")?.let { systemProperty("biuz.stubLive", it) }
}
