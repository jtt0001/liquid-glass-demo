# NOTICE

## Backdrop（io.github.kyant0:backdrop:2.0.0）

- 项目：https://github.com/Kyant0/AndroidLiquidGlass
- 作者：Kyant
- 许可：Apache License 2.0
- 用途：本项目依赖该库提供的 Compose Multiplatform Liquid Glass 捕获层与
  渲染管线（`rememberLayerBackdrop` / `Modifier.layerBackdrop` /
  `Modifier.drawBackdrop` / `BackdropEffectScope.runtimeShaderEffect` 等）。

### AGSL 派生代码声明

本项目 `app/src/main/java/com/example/liquidglass/glass/GlassShaders.kt` 中的圆角矩形 SDF 工具函数
（`radiusAt` / `sdRoundedRect` / `gradSdRoundedRect`）与 `circleMap` 透镜曲线，
改编自 Backdrop 库源码
`backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt`
（Apache License 2.0, Copyright 2025 Kyant），使用方式与许可证保持一致。

### Apache License 2.0 摘要

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

> 说明：本仓库的第三方组件**完整清单**（逐个列明上游仓库 / 版本 / 许可证 / 版权行 / 本项目修改说明）
> 见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)，随附许可副本在 [`LICENSES/`](LICENSES/)；
> 本节仅保留 Backdrop 相关的派生代码声明，避免两份说法并存。

## 其他依赖

- Jetpack Compose / Material 3 / AndroidX（Android Jetpack，Apache License 2.0）
- Kotlin（Apache License 2.0）
- Android Gradle Plugin / Gradle（Apache License 2.0）

## 视觉参考

- iOS 26 Liquid Glass（Apple Developer 公开描述）：实时 Lensing、光线弯曲、
  透明轻量感、环境自适应色调与阴影；Regular 与 Clear 两种材质。
- iOS 26 Developer Beta 1 / Beta 3 视觉差异说明（MacRumors 公开报道）。

本项目为 Android 屏幕空间渲染条件下的高保真视觉近似，
与 Apple 私有渲染器无任何代码或资产关联。
