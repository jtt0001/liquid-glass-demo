import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Backdrop 2.0.0 上游源码本地模块（Kyant0/AndroidLiquidGlass, commit bebb11a）
// 与上游 backdrop/build.gradle.kts 一致，仅移除 maven-publish 发布插件。
plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    android {
        minSdk = 21
        compileSdk = 37
        buildToolsVersion = "37.0.0"
        namespace = "com.kyant.backdrop"
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    jvm("desktop")

    js(IR) {
        browser()
    }
    wasmJs {
        browser()
    }

    macosArm64()
    iosArm64("iosArm64")
    iosSimulatorArm64("iosSimulatorArm64")

    sourceSets {
        val commonMain by getting {
            dependencies {
                // CMP 坐标（与上游 2.0.0 一致）
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.ui.graphics)
                implementation(libs.kyant.shapes)
                implementation(libs.jetbrains.annotations)
            }
        }

        val skikoMain by creating {
            dependsOn(commonMain)
        }

        val desktopMain by getting {
            dependsOn(skikoMain)
        }

        val macosArm64Main by getting {
            dependsOn(skikoMain)
        }

        val iosMain by creating {
            dependsOn(skikoMain)
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }

        val jsMain by getting {
            dependsOn(skikoMain)
        }

        val wasmJsMain by getting {
            dependsOn(skikoMain)
        }

        all {
            languageSettings.enableLanguageFeature("ContextParameters")
        }
    }
}
