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
}

// 与 protocol 等纯 JVM 模块的工具链（jvmToolchain(17)）保持同一字节码层级。
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
