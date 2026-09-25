pluginManagement {
    repositories {
        // 全部使用阿里云镜像（与境外源版本一致），不留境外兜底
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "LiquidGlassDemo"
include(":app")
// Backdrop 2.0.0 真实上游源码（Kyant0/AndroidLiquidGlass, commit bebb11a）本地模块
include(":backdrop")
