// 根构建脚本：仅声明插件版本，统一由 gradle/libs.versions.toml 管理。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.jetbrains.compose) apply false
}
