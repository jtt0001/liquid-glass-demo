import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.util.Properties

// 【2026-09-18】签名口令不再硬编码入库 ✗ 改从 local.properties 读（该文件被 .gitignore 覆盖 ✓）
val lgKeyPass: String = run {
    val f = rootProject.file("local.properties")
    val p = Properties()
    if (f.exists()) f.inputStream().use { p.load(it) }
    p.getProperty("liquidglass.storePassword", "")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.liquidglass"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // 独立包名：GitHub 上不少同类示例都用 com.example.*，
        // 与它们"包名相同 + 签名不同"会导致无法覆盖安装 / 无法并存。
        // ✓ 并入 feat/diamond-demo 后仍为主线口径：包名保持 com.liqglass.ultraclear 不变
        // （钻石分支自己的 com.liqglass.diamond / app_name=Liquid Diamond 只属于钻石独立构建）
        applicationId = "com.liqglass.ultraclear"
        minSdk = 33
        targetSdk = 36
        // 主线口径（取 HEAD 侧 P44 的 vc/名，✗ 拒绝钻石侧 vc300/3.0.0-diamond）
        // vc 288→289 / 名 edgesharp→glassdiamond：本次 = P43+P44+P37... 主线全部修复 + 钻石演示，
        // 便于在 dumpsys/桌面上直接判别"装机的是哪一版"（✗ 与 P44 的 288-edgesharp 同号会分不清）
        versionCode = 296
        versionName = "2.49.0"
    }

    // 正式签名（不再使用 Android 默认 debug 签名）。
    // 密钥库随项目提供：liquidglass-release.jks；口令不入版本库，从 local.properties 读（liquidglass.storePassword / keyPassword）。
    signingConfigs {
        create("liquidglass") {
            storeFile = rootProject.file("liquidglass-release.jks")
            storePassword = lgKeyPass
            keyAlias = "liquidglass"
            keyPassword = lgKeyPass
        }
    }

    buildTypes {
        debug {
            // 调试包也用同一把正式密钥，避免"debug 签名 → 换签名后必须卸载重装"
            signingConfig = signingConfigs.getByName("liquidglass")
        }
        release {
            signingConfig = signingConfigs.getByName("liquidglass")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        // 【P29·开源许可页】打开 BuildConfig 生成：许可页的"版本"文案必须动态取
        // `BuildConfig.VERSION_NAME`（✗ 不写死字符串 —— 写死的版本号在下次改 versionName 时必错）。
        // 本工程此前未声明该 feature（AGP 9 默认 buildConfig=false）⇒ 这里显式打开。
        // release 构建同样生成（AGP 默认 debug 也生成）；BuildConfig.DEBUG 的运行期语义不受影响
        // （debug/DebugBridge.kt 用的是自己的 isDebugBuild 判据，不依赖 BuildConfig）。
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // Liquid Glass：Backdrop 2.0.0 真实上游源码本地模块（Kyant0/AndroidLiquidGlass, commit bebb11a）
    implementation(project(":backdrop"))
    // P18：官方 JankStats（androidx.metrics）—— 与自写 FrameTimelineLogger 并存不冲突
    // 版本 1.0.0 已对阿里云镜像核实（证据：~/Downloads/LG-p18/jankstats_version.xml + metrics-performance-1.0.0.aar）
    implementation("androidx.metrics:metrics-performance:1.0.0")
    // 【P28a·批 1】iOS 风格形状库（Capsule / RoundedRectangularShape 等）：
    // 上游 catalog 组件（LiquidButton/LiquidToggle/LiquidSlider）就用它取形状，
    // 版本与上游 libs.versions.toml 一致（1.2.1）✓；按上游方式走 Maven 坐标，不 vendor 源码 ✓
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
