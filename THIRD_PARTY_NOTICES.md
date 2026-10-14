# THIRD_PARTY NOTICES / 第三方组件声明

本项目（LiquidGlassDemo，应用名 `Liquid Glass`，applicationId `com.liqglass.ultraclear`）为 Android 端技术演示应用，
包含并使用下列第三方开源组件。本文件用于随仓库与分发产物提供许可与版权信息。

生成依据：仓库 HEAD `de0d6f3`（工作树干净，只读审查）。所有版权行/许可文本均逐字取自上游文件，
并已在本仓库内逐字节核对（见「核对证据」）。

---

## 一、直接使用（含随附许可全文的组件）

### 1. Kyant0 / AndroidLiquidGlass（Backdrop）

| 项 | 内容 |
|---|---|
| 组件名 | Backdrop（Kotlin Multiplatform 玻璃捕获/渲染库） |
| 上游仓库 | https://github.com/Kyant0/AndroidLiquidGlass |
| 版本 | 2.0.0（commit `bebb11a`），以**源码模块**形式随本仓库分发：`backdrop/` |
| 许可证 | Apache License, Version 2.0 |
| 版权行 | `Copyright 2025 Kyant` |
| 许可副本 | 本仓库内 `LICENSES/Backdrop-APACHE-2.0.txt`（10750 字节，191 行，与上游 `LICENSE` 逐字节一致） |
| 本项目修改说明 | 见下「修改声明」第 1 条 |
| 上游 NOTICE | 上游仓库**不含** NOTICE 文件 ⇒ 无随附义务 |

### 2. Kyant0 / Shapes

| 项 | 内容 |
|---|---|
| 组件名 | Shapes（iOS 风格形状：Capsule / RoundedRectangularShape / Continuous 圆角等） |
| 上游仓库 | https://github.com/Kyant0/Shapes |
| 版本 | `io.github.kyant0:shapes:1.2.1`（Maven 依赖，非源码分发） |
| 许可证 | Apache License, Version 2.0 |
| 版权行 | `Copyright 2026 Kyant` |
| 许可副本 | 上游 `LICENSE` 第 179 行为 `Copyright 2026 Kyant`，与 Backdrop 那份除该年份外逐字相同；本仓库以 `LICENSES/Backdrop-APACHE-2.0.txt` + 下方「版权行」保留其归属，应用内许可页亦逐字展示该许可全文与版权行 |
| 本项目修改说明 | 未修改（按 Maven 坐标使用） |
| 上游 NOTICE | 上游仓库**不含** NOTICE 文件 ⇒ 无随附义务（其 AAR 内也未打包 LICENSE/NOTICE，已核对 1.2.1 AAR） |

### 3. QWEA0 / Liquid-Glass-Android（pandadog）—— 代码移植来源（非依赖）

| 项 | 内容 |
|---|---|
| 组件名 | Liquid Glass Android（其玻璃光学模型） |
| 上游仓库 | https://github.com/QWEA0/Liquid-Glass-Android |
| 使用方式 | **移植**其内联 AGSL 光学模型（逆幂衰减折射剖面、色散、边缘光照、触点凸起、shape2 融合段）到本项目的 AGSL 管线；未整体引入其代码库 |
| 许可证 | MIT License |
| 版权行 | `Copyright (c) 2025-2026 pandadog` |
| 许可副本 | MIT 全文随应用内「开源许可与致谢」页逐字展示（1070 字节，逐字取自上游 `LICENSE`） |
| 本项目修改说明 | 见下「修改声明」第 2 条 |

---

## 二、平台与框架（Apache License 2.0，按名称 + 链接标注）

以下组件以 Maven 依赖/构建工具形式使用，本项目未修改其源码。按其各自 AAR/JAR 内的许可信息（`META-INF/**/LICENSE.txt`，内容为 Apache License 2.0）标注：

| 组件 | 版本/坐标 | 许可证 | 版权/上游 |
|---|---|---|---|
| AndroidX / Jetpack Compose | Compose BOM `2026.08.00`（解析为 compose-* `1.12.0`）、`androidx.activity:activity-compose:1.13.0`、`androidx.core:core-ktx:1.18.0`、`androidx.lifecycle:*:2.9.4`、`androidx.savedstate:*:1.4.0`、`androidx.window:*:1.5.0`、`androidx.collection:1.5.0`、`androidx.profileinstaller:1.4.0` 等 | Apache-2.0 | The Android Open Source Project — https://github.com/androidx/androidx |
| AndroidX Metrics（JankStats） | `androidx.metrics:metrics-performance:1.0.0` | Apache-2.0 | The Android Open Source Project — https://github.com/androidx/androidx |
| AndroidX Graphics Path | `androidx.graphics:graphics-path:1.0.1`（APK 内含 `lib/arm64-v8a|armeabi-v7a|x86|x86_64/libandroidx.graphics.path.so`） | Apache-2.0 | The Android Open Source Project |
| Kotlin / kotlinx | `org.jetbrains.kotlin:kotlin-stdlib`（解析为 2.4.10）、`org.jetbrains.kotlinx:kotlinx-coroutines-*:1.9.0`、`org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3` | Apache-2.0 | JetBrains — https://github.com/JetBrains/kotlin |
| JetBrains Compose Multiplatform | `org.jetbrains.compose.*:1.11.0`（`:backdrop` 模块的 Compose 依赖） | Apache-2.0 | JetBrains — https://github.com/JetBrains/compose-multiplatform |
| Other annotations / utility | `org.jetbrains:annotations:26.1.0`、`org.jspecify:jspecify:1.0.0`、`com.google.guava:listenablefuture:1.0`（Apache-2.0） | Apache-2.0 | 各自上游项目 |

> 构建工具链（Android Gradle Plugin 9.2.1、Gradle、Kotlin Gradle Plugin）为 Apache-2.0，
> 但**不随 APK 分发**，通常不计入分发声明。

---

## 三、本项目对上游代码的修改声明（Apache-2.0 §4(b)）

1. **Kyant0/AndroidLiquidGlass（vendored 源码模块 `backdrop/`）**
   - `backdrop/build.gradle.kts`：移除上游的 `maven-publish` 发布插件（其余与上游一致）；
   - `backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt`：
     追加本地缺陷修复（黑卡缺陷）——① `observeEffects()` 内额外观察 `LayerBackdrop.layerCoordinates`；
     ② `onObservedReadsChanged()` 追加 `invalidateDraw()`。文件内第 373 行、第 387 行有
     `// 本地修改（黑卡修复）` 行内说明（即 §4(b) 要求的「prominent notices of change」）；
   - 其余 `backdrop/src/**` 文件原样保留（共 38 个 Kotlin 源文件）。
2. **QWEA0/Liquid-Glass-Android（MIT，代码移植）**：仅移植其玻璃光学模型到本项目的 AGSL 着色器
   （落地于 `app/src/main/java/com/example/liquidglass/glass/GlassShaders.kt`、
   `glass/FusionShaders.kt`、`backdrop/BackdropAdapter.kt` 等）；未移植其 View 体系、
   NDK C++ 高斯模糊、RenderNode 录制管线。项目源码中标注了移植来源
   （如 `FusionShaders.kt:63`、`BackdropAdapter.kt:403` 与 `:601`）。
3. **本项目自有代码**（`app/src/main/java/com/example/liquidglass/**` 中未标注移植的部分）为原创实现。

---

## 四、图片/视觉素材

| 素材 | 来源声明 | 处置 |
|---|---|---|
| `app/src/main/res/drawable-nodpi/bg_{clouds,meadow,lake,forest}.jpg` | 项目 README「上游来源与致谢」表：Unsplash（Unsplash License，免费使用，无需署名） | 保留；建议补充逐图作者/链接 |
| `app/src/main/res/drawable-nodpi/debug_{grid,colorgrid}.png` | 项目自产调试用网格图（含方向锚点） | 保留 |
| `app/src/main/res/mipmap-*/ic_launcher*.png`、`drawable/ic_launcher_foreground.xml`、`drawable/liquid_landscape.xml` | 项目自产（图标为自绘矢量 + 导出的位图；`liquid_landscape.xml` 为无网络图片的内置矢量风景） | 保留 |
| `707441DE-8606-46BC-8633-F367F04DF1BC.heic`（仓库根） | 项目作者本人设备截图（iOS 截图样本，用于 HEIC 解码验证）；无第三方素材特征 | 保留；**公开前请自行确认其中不含不希望公开的个人信息** |

本项目**不包含** Apple 的图片、图标、字体或其它资产；视觉参考仅来自 Apple 公开文档与公开报道的**文字描述**。

---

## 五、核对证据（可复核）

- `LICENSES/Backdrop-APACHE-2.0.txt` = 10750 字节 / SHA-256 `4b9f7e7c2821a5a1822a6cf1e4a34e877bba48a99b9bba1f03e5e2eccf6dec56`；
  与上游 `Kyant0/AndroidLiquidGlass` 的 `LICENSE` 逐字节相同（上游仓库无 NOTICE）。
- 应用内许可页所用文本：`app/src/main/java/com/example/liquidglass/ui/LicenseTexts.kt`
  （`APACHE_2_0_FULL` 10174 字节 + `BACKDROP_NOTICE` 575 字节 = 上述 LICENSE 文件 10750 字节逐字节一致；
  `SHAPES_NOTICE` 575 字节；`QWEA_MIT` 1070 字节），版权行由 `LicensePage.kt:96-99` 从上述文本派生。
- 依赖清单：`./gradlew --offline :app:dependencies --configuration releaseRuntimeClasspath`
  （124 个模块，其中 52 个 AAR/JAR 内部自带 `META-INF/**/LICENSE.txt`）。

> 本文件不构成法律意见；上线前如需对外分发，请同时确认应用内许可页与实际分发内容一致。
