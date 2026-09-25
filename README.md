# Liquid Glass · Ultra Clear（Android Compose Demo）

基于 **io.github.kyant0:backdrop:2.0.0**（Backdrop 2.0.0，Compose Multiplatform Liquid Glass 效果库）
+ **Android RuntimeShader / AGSL** 构建的完整 Jetpack Compose 单模块 App，
高保真近似复刻 **iOS 26 Developer Beta 1 / WWDC25 初期**版本的超透明 Liquid Glass 效果。

---

## 0. 重要声明（务必先读）

本项目是在 **Android 屏幕空间渲染条件**下对 **iOS 26 Developer Beta 1 Liquid Glass** 的
**高保真视觉近似**，**不是** Apple 私有系统渲染器的像素级复制。

Apple 的系统材质包含私有的环境光、内容感知、动态范围、设备姿态与多层合成逻辑，
因此本项目**不得声称与 Apple 渲染器 100% 完全一致**。

视觉基准说明：

- 目标不是后续 Beta 3 那种明显增加磨砂与不透明度的版本，而是早期版本的表现：
  **高背景透过率、极低白色填充、中心接近清透、边缘折射明显、
  边缘高光与暗边共同定义玻璃轮廓、背景文字与图像清晰可见被透镜扭曲**。
- 玻璃存在感主要来自**折射、光线集中与边缘响应**，而不是大面积白雾。
- iOS 26 Developer Beta 3 起提高了导航栏、按钮与标签栏的不透明度；
  本项目的默认视觉基准更接近 Beta 1 / WWDC25 初期的高透明表现。

---

## 1. 版本与架构决策

| 项 | 决策 | 说明 |
|---|---|---|
| Liquid Glass 库 | `io.github.kyant0:backdrop:2.0.0` | **固定版本，不得替换**；不混用 1.x API；未引入 com.qmdeve.liquidglass |
| 项目类型 | 单模块 Android App | `:app` |
| 构建脚本 | Kotlin DSL + 版本目录 | `gradle/libs.versions.toml`，全部确定版本 |
| Gradle | 9.5.0（wrapper 固定） | 与 Backdrop 2.0.0 官方仓库构建链一致 |
| AGP | 9.2.1 | 支持 compileSdk 37；AGP 9 内置 Kotlin，无需 kotlin-android 插件 |
| Kotlin Compose 插件 | 2.3.21 | `org.jetbrains.kotlin.plugin.compose` |
| compileSdk | 37 | 满足“编译 SDK 至少为 36” |
| targetSdk | 36 | 按需求固定 |
| minSdk | 33 | Android 13+；RuntimeShader 自 API 33 提供 |
| JDK | 21 | 构建要求 |
| Compose BOM | 2026.08.00 | androidx.compose 稳定版 |
| Material | Material 3 | 滑块 / Chip / Switch / 主题 |
| 渲染技术 | Backdrop + RuntimeShader + AGSL | 唯一光学实现路径 |
| 捕获机制 | `rememberLayerBackdrop` + `Modifier.layerBackdrop` + `Modifier.drawBackdrop` | 单捕获源，卡片按窗口坐标采样 |

**不使用**：RenderScript、CPU Bitmap 模糊、截图重生成 Bitmap、GPU→CPU 像素回读、
网络图片、动态依赖版本、全卡片 `Modifier.alpha` 控透明。

## 2. 超透玻璃实现原则

### 透明度必须可自定义，但绝不用整卡片 Alpha

对整个玻璃卡片应用 `Modifier.alpha(...)` 会同时淡化折射、边缘高光、阴影、卡片文字与图标，
最终只会让卡片“消失”，而不是形成清透玻璃。本项目：

- 玻璃容器整体合成 Alpha **恒为 1.0**；
- 卡片前景文字与图标 Alpha **默认恒为 1.0**；
- 透明度通过 **Shader uniform 分别调节**：背景透射率、材质填充不透明度、色调不透明度、
  模糊混合比例、局部暗化强度、边缘高光强度、边缘暗边强度。

### 透明度映射规则（0～1 宏观参数 t）

```
backgroundTransmission = lerp(0.72, 0.97, t)
materialOpacity         = lerp(0.18, 0.015, t)
tintOpacity             = lerp(0.12, 0.004, t)
blurRadiusDp            = lerp(20.0, 2.5, t)
edgeBlurRadiusDp        = lerp(24.0, 6.0, t)
refractionOffsetDp      = lerp(5.0, 10.0, t)
fresnelStrength         = lerp(0.60, 1.00, t)
edgeHighlightOpacity    = lerp(0.18, 0.34, t)
（另：edgeShadowOpacity = lerp(0.28, 0.10, t)，localDimmingOpacity = lerp(0.16, 0.00, t)，
      dispersionOffsetDp = lerp(0.5, 1.5, t)，saturation = lerp(0.98, 1.06, t)）
```

- 透明度升高：中心填充与模糊减少；**边缘折射与高光不能一起消失**；
- 超透玻璃的轮廓主要由边缘光学效果定义；色散保持克制，不构成 RGB 故障风格；
- **以上数值是 Android 近似实现的视觉起点，不是 Apple 的内部参数**；
- 所有 dp 光学参数进入 AGSL 前必须转换为 px（`BackdropAdapter.kt` 中统一转换）。

### 三个预设

| 预设 | glassTransparency | 特点 |
|---|---|---|
| `IOS26_BETA1_ULTRA_CLEAR`（默认） | 0.94 | 中心高度清透、背景颜色基本保留、透镜位移明显、边缘折射强于中心、无乳白雾层；全卡片白色填充 Alpha 不超过约 0.03 |
| `BALANCED` | 0.78 | 可读性与视觉平衡 |
| `FROSTED_ACCESSIBLE` | 0.48 | 高对比度 / 减少透明效果 |
| `CUSTOM` | — | 独立调节每个参数 |

预设切换使用平滑动画更新 uniform（参数即状态，由 `observeReads` 驱动），**不重新编译 Shader**。

## 3. 页面结构与采样原理

```
BackgroundScene（矢量风景 + REFRACTION TEST / ULTRA CLEAR GLASS 测试文字）
    ↓ 唯一捕获源（Modifier.layerBackdrop 注册一次）
Backdrop Layer（GraphicsLayer，整页只此一个）
    ↓ 按卡片窗口坐标采样（LayerBackdrop.drawBackdrop 自动计算偏移）
Liquid Glass Optical Layer（drawBackdrop + runtimeShaderEffect，AGSL 光学）
    ↓
Clear Foreground Content（Alpha 恒 1.0，Shader 之后绘制，保持清晰）
    ↓
Controls / Performance Panel（兄弟节点，不参与捕获）
```

- 背景场景支持缓慢自动纵向移动（约 50s 往返，可关闭）与手动拖动；
- 背景滚动 / 卡片拖动后，玻璃内部采样实时变化（捕获层按新坐标重新记录）；
- 旋转屏幕、分屏、窗口尺寸变化后通过 `onSizeChanged` 与 Insets 读取更新
  resolution / cardOrigin / cardSize，不硬编码屏幕宽高；
- 坐标空间明确区分：dp、px、卡片局部坐标、根布局坐标、Window 坐标、Backdrop 采样坐标
  （`BackdropAdapter.kt` 统一换算，Shader 内统一为捕获层像素空间）。

## 4. AGSL 光学实现

`shader/GlassShaders.kt` 提供三段自定义 AGSL 源码（5 / 9 / 13 taps），实现：

1. 圆角矩形 SDF 与距离梯度法线（源自 Backdrop 库 Apache-2.0 源码，见 NOTICE.md）；
2. 按法线偏移背景采样坐标：**中心折射弱、边缘折射强**（circleMap 透镜曲线）；
3. R / G / B 通道采用略微不同的采样坐标实现**克制色散**；
4. 固定采样点数背景模糊（中心低模糊、边缘稍高模糊，权重恒定）；
5. 菲涅尔式边缘高光 + 四角镜面点光 + 相对侧的轻微暗边；
6. 按压位置附近的径向凹陷 + 环状鼓起（含轻量 ripple）；
7. 拖动速度驱动的方向性凝胶拉伸（速度限幅 2400px/s）；
8. 正确的**预乘 Alpha** 输出；所有采样坐标 clamp 到捕获层范围防黑边；
9. 高光与光照混合在**线性颜色空间**（`toLinearSrgb` / `fromLinearSrgb`）完成，
   减少高光发灰与白色污染；
10. 内容感知可读性：Shader 内对标签区域采样亮度判断 → 局部暗化（0.00～0.12）/ 提亮，
    **无 GPU→CPU 像素回读**；`adaptiveLegibilityEnabled` 关闭后严格使用手动参数。

超透默认预设把视觉预算放在边缘折射、法线变化、高光与色散，而非大半径模糊。

## 5. 画质与性能档位

| 档位 | 采样点 | 说明 |
|---|---|---|
| `PERFORMANCE` | 5 taps | 省电模式自动切换目标 |
| `BALANCED`（默认） | 9 taps | 画质与性能平衡 |
| `QUALITY` | 13 taps | 最高画质 |

- 三个 Shader 变体在初始化阶段分别编译并通过 Backdrop `RuntimeShaderCache`
  （`obtainRuntimeShader(key, source)`）缓存；切换档位直接选择已缓存 Shader，**不重新编译**；
- 质量下降优先减少模糊采样点，**保留边缘折射与高光**；
- 性能模式不是“只留透明背景”。

## 6. 性能监控面板

`performance/PerformanceMonitor.kt` + `PerformanceSnapshot.kt` + `PerformancePanel.kt`：

- **FPS / Avg / P95 / Jank**：`Window.OnFrameMetricsAvailableListener`（独立 HandlerThread），
  统计最近 1 秒（窗口上限 120 帧）；静止无新帧显示 IDLE；
  不为测量 FPS 主动强制持续重绘。
- **App CPU**：`Process.getElapsedCpuTime()` 增量 ÷ `SystemClock.elapsedRealtime()` 增量
  ÷ `Runtime.getRuntime().availableProcessors()`，**按全部逻辑核心归一化**；
  不使用 /proc/stat 的整机 CPU。
- **GPU 帧耗时**：`FrameMetrics.GPU_DURATION`，显示 Median / P95；
  `getMetric()` 返回 -1 时显示 **N/A**；`TOTAL_DURATION` / `COMMAND_ISSUE_DURATION`
  仅作为 **Proxy** 附加展示，不伪装成真实 GPU 占用。
- **电池**：`BATTERY_PROPERTY_CURRENT_NOW`（µA → mA，正=充电/负=放电，按设备返回值解释）、
  `BATTERY_PROPERTY_CHARGE_COUNTER`（µAh）；会话能耗 = 电流 × 时间积分（**mAh**），
  不存在“瞬时 mAh”；不支持时显示 **N/A**，不显示 0。
- 回调内只复制必要数值，聚合在后台线程，UI 更新节流 700ms，StateFlow 暴露不可变快照；
  Activity 销毁时注销监听并安全退出 HandlerThread。

> 性能面板显示的是 **GPU 帧耗时**，不是 GPU 利用率百分比。
> GPU 百分比需要 Perfetto / Android Studio Profiler / 芯片厂商工具测量。

## 7. 参考目标与测试方法（用户提供的目标参考，非本项目实测）

以下数据为**用户提供的目标参考**，不得声称是本项目已完成实测：

| 设备 | 静态 GPU 占用 | 静态 App CPU | 放电电流 | 一小时累计 | 交互峰值 GPU |
|---|---|---|---|---|---|
| 骁龙 8 Gen 3 | 8%～14% | < 5% | 120～180mA | 约 120～180mAh | 30%～45% |
| 骁龙 7+ Gen 2 | 12%～20% | — | — | — | 50%～65% |

- 超透预设因模糊半径较低，理论上可能比大半径磨砂预设减少部分采样成本，
  **但没有真机数据前不得声称一定更省电**。

真机测试必须记录：设备型号、Android 版本、分辨率、刷新率、屏幕亮度、电池温度、
构建类型、是否连接调试器、运行时间、当前玻璃预设、是否持续拖动。

## 8. 双卡液态融合

“双卡演示”开启后显示第二张较小玻璃卡片：

- 两张卡片位于共享父层，各自独立采样同一个捕获层（Backdrop 架构不允许
  两卡共享同一材质 Shader，本项目**未编造任何“自动融合 API”**）；
- 两卡接近时，父层 Canvas 绘制 **metaball 风格连接桥**（胶囊代谢球近似：
  距离越近连接颈越粗，越远越细直至断开；连接处高光随距离降低）；
- 连接桥使用共享父层绘制，绘制于卡片之下。

> **说明**：这是二维 SDF 视觉近似，**不是真正的单一材质背景采样融合**。
> 代码注释与 README 中均已明确标注。

## 9. 按压与拖动形变

- **按压**：记录触点卡片局部坐标，`pressProgress` 0→1 快速上升（140ms），
  松开后按 `spring(dampingRatio, stiffness)` 回 0；触点附近轻微凹陷、周围环状鼓起、
  边缘高光随 ripple 扩散；不突变、不是普通缩放。
- **拖动**：`VelocityTracker` 计算速度 → 限幅 2400px/s → 转为 `dragVelocity` /
  `stretchDirection` uniform；速度越高凝胶拉伸越明显；释放后位置与视觉形变弹簧回弹；
  卡片限制在安全显示区域（含状态栏 / 导航栏 Insets），不会拖出屏幕无法恢复。
- 位置由 `graphicsLayer.translationX/Y` 控制，凝胶轮廓由 AGSL/SDF 形变完成。
- **注意**：视觉边界（Shader 折射造成的视觉偏移）与真实 Compose 触摸边界并不完全相同。

## 10. 动画性能

- 仅在交互或回弹期间更新 `time` uniform（`GlassCardTimeDriver` 用快照流挂起，
  静止后完全停止，**不为保持 Shader 活跃而永久每帧重绘**）；
- 卡片拖动不触发整页重组：高频状态（偏移 / 按压 / 速度）在绘制层读取；
- Shader 对象只初始化一次（按 key 缓存）；滑块变化只更新 uniform。

## 11. 设备兼容性

- **主项目：minSdk 33（Android 13+）**。RuntimeShader / AGSL 自 API 33 提供，
  因此不生成任何 API 32 以下运行时分支。
- 若未来需要支持 Android 12 及以下：应降低 minSdk，并采用普通透明渐变、边框与
  模糊降级方案；该降级分支不属于本次主项目。
- Edge-to-Edge，正确处理状态栏 / 导航栏 / Display Cutout；支持浅色 / 深色模式、
  屏幕旋转与分屏；不锁定固定分辨率。
- 可访问性：滑块提供 contentDescription，按钮提供 TalkBack 语义；
  “减少动态效果”开关：停止背景自动滚动、降低凝胶拉伸、提高弹簧阻尼、
  冻结 time uniform、关闭双卡融合动画（连接桥仅随位置更新）。

## 12. 构建

```bash
# 环境：JDK 21（JAVA_HOME 指向 JDK 21）、Android SDK（platforms;android-37、
#       build-tools;37.0.0、platform-tools，licenses 已接受）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

所有依赖均为确定版本（`gradle/libs.versions.toml`），禁止 + / latest.release / 动态版本。

## 13. 已核实 / 未核实项

**已核实（源码级）**：

- Backdrop 2.0.0 真实 API（克隆官方仓库 tag 2.0.0 逐文件核对）：
  `rememberLayerBackdrop` / `Modifier.layerBackdrop` / `Modifier.drawBackdrop` /
  `BackdropEffectScope.runtimeShaderEffect(key, shaderString, uniformShaderName, block)` /
  `RuntimeShader.setFloatUniform / setIntUniform / setColorUniform` /
  `padding` 语义（捕获层外扩边距）/ `obtainRuntimeShader` 按 key 缓存；
- AGSL 输入 Shader uniform 名与 `createRuntimeShaderEffect` 绑定方式；
- 库依赖版本：backdrop-android 2.0.0 POM（Compose 1.11.0 / kotlin-stdlib 2.3.21）；
- 本仓库构建链（AGP 9.2.1 + Gradle 9.5.0 + Kotlin Compose 2.3.21 + compileSdk 37）。

**未在真机验证（如实声明）**：AGSL 着色器的运行时编译与视觉效果、FrameMetrics
在具体设备上的数值、电池属性在具体设备上的支持情况。上述项需在 Android 13+ 真机上验证。

---

# 第二阶段：真实源码移植 + Ultra Clear 视觉修复（Phase 2）

## P0：Backdrop 2.0.0 真实源码本地移植

| 项 | 值 |
|---|---|
| 上游仓库 | Kyant0/AndroidLiquidGlass |
| 上游版本 | 2.0.0（tag 2.0.0） |
| 上游 commit | `bebb11a`（Bump to 2.0.0） |
| 本地模块 | `backdrop/`（39 个 Kotlin 源文件＝实测值；除下述本地缺陷修复外，与上游 commit bebb11a 一致） |
| 模块配置 | 与上游 backdrop/build.gradle.kts 一致，仅移除 maven-publish 发布插件 |
| App 依赖 | `implementation(project(":backdrop"))`（不再使用 Maven 二进制） |
| License | Apache-2.0，LICENSES/Backdrop-APACHE-2.0.txt + NOTICE.md 保留上游版权 |

上游 `backdrop/src` 中仅 `DrawBackdropModifier.kt` 有 1 处缺陷修复（详见上方 Backdrop Source Audit），
其余上游源码原样编译；全部自定义光学能力位于 app 的 `glass/` 包与 `backdrop/BackdropAdapter.kt`（唯一 Backdrop 封装层）。

## Backdrop Source Audit

- 上游仓库：Kyant0/AndroidLiquidGlass
- 上游版本：2.0.0，commit bebb11a
- 本地 module：`backdrop/`
- 实际源码文件数量：38（commonMain + androidMain + skikoMain 等，与上游一致）
- 保留原样的源码：backdrop/src/**（除下方 1 处缺陷修复外的全部文件）
- 修改过的上游源码：`backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt`
  - 修改原因（黑卡缺陷）：`LayerBackdrop.drawBackdrop()` 在 `layerCoordinates == null`
    时静默 `return`，会把**空内容**录进玻璃层 → 玻璃整片变黑（只剩边缘高光）。
    更关键的是，上游 `onObservedReadsChanged()` 只重新挂载观察、不主动 `invalidateDraw()`，
    因此"坐标恢复"不会触发重绘；静止画面会把黑帧**永久停留**（重启才恢复）。
  - 修改内容（2 处，均为追加）：① `observeEffects()` 内额外观察 `LayerBackdrop.layerCoordinates`；
    ② `onObservedReadsChanged()` 追加 `invalidateDraw()`。上游其余逻辑与语义不变。
- App 自己新增的 Shader：`app/src/main/java/com/example/liquidglass/glass/GlassShaders.kt`（三段 AGSL，5/9/13 taps，含调试模式分支；SDF 工具函数改编自上游 Shaders.kt，Apache-2.0 声明保留）
- 最终 app dependency：`implementation(project(":backdrop"))`

## Glass Pipeline Audit

```
BackgroundScene（ui/BackgroundScene.kt）
    ↓ 唯一捕获源
Backdrop capture（backdrop/src/.../backdrops/LayerBackdrop.kt + layerBackdrop modifier）
    ↓ 按卡片窗口坐标采样（LayerBackdrop.drawBackdrop：localPositionOf 坐标差）
Backdrop effect pipeline（backdrop/src/.../DrawBackdropModifier.kt + BackdropEffectScope）
    ↓ 自定义入口
runtimeShaderEffect（backdrop/src/.../effects/RenderEffect.kt）
    ↓ 自定义 AGSL
coordinate transform（glass/GlassShaders.kt: local = coord + offset）
refraction（grad × circleMap(edge) × refractionOffset）
blur（高斯权重 taps，中心低/边缘高）
dispersion（edgeMask² 窄带，R/B ±dispersionOffset）
tint / transmission（tintColor×tintOpacity + materialColor×materialOpacity）
fresnel highlight / shadow（pow(edgeMask,3.2) 窄带 + 转角点光）
adaptive dimming（Shader 内亮度判断，标签区域）
    ↓
Clear foreground（ui/LiquidGlassCard.kt，Alpha 恒 1.0，Shader 之后绘制）
```

## 性能数据链 Audit

```
Window（MainActivity.kt 传入）
    ↓ addOnFrameMetricsAvailableListener（独立 HandlerThread）
FrameMetrics（performance/PerformanceMonitor.kt 回调内只复制数值）
    ↓ 700ms 聚合（后台线程）
aggregation（FPS 最近 1s 窗口 / Avg / P95 / Jank / GPU Median·P95 / Total·Issue Proxy）
    ↓
StateFlow<PerformanceSnapshot>（不可变快照）
    ↓
PerformancePanel（ui/PerformancePanel.kt，collectAsState 节流展示）
```

- FPS 来源：FrameMetrics.TOTAL_DURATION 帧计数（最近 1 秒窗口，上限 120 帧）；静止显示 IDLE
- CPU 来源：Process.getElapsedCpuTime() 增量 ÷ 墙钟增量 ÷ 逻辑核心数
- GPU Duration：FrameMetrics.GPU_DURATION，-1 时显示 N/A
- Battery：BATTERY_PROPERTY_CURRENT_NOW（µA→mA）；会话能耗 = 电流×时间积分（mAh）；不支持显示 N/A
- 刷新周期：window.decorView.display.refreshRate（不能用 applicationContext.display，Android 13+ 抛异常）

## Phase 2 视觉修复记录（真机审计驱动）

1. **重影根因**：旧版 blur 为均匀权重网格采样（小半径下 = 多份位移叠加 → 文字拉丝/重影）。
   修复：高斯权重（中心主导）+ 色散窄带化（edgeMask²）+ dispersion 降至 0.3~0.6dp。
2. **乳白/粗白边根因**：fresnel/highlight 覆盖整个 24dp 边缘带（pow 1.6 衰减慢，强度上限 0.66）。
   修复：pow(edgeMask, 3.2) 窄带高光 + 转角点光 pow(6)，强度 ≤0.26；暗边 ≤0.08。
3. **默认参数校准**（t=0.94）：transmission 0.956 / material 0.026 / tint 0.009 /
   centerBlur 2.5dp / edgeBlur 6.8dp / refraction 8.3dp / dispersion 0.58dp /
   fresnel 0.93 / highlight 0.25 / shadow 0.087。
4. **调试模式**：GlassDebugMode（FINAL/仅背景/仅折射/仅模糊/仅菲涅尔/仅色散/SDF/法线），
   通过 int uniform 切换，不重新编译 Shader。
5. **dp/px 核对**：滑块显示 "dp / px" 双值；UltraClearGlassEffect.kt 统一 dp→px 换算。
6. **底部面板**：控制面板与性能面板上下堆叠（不再重叠）；性能面板 navigationBarsPadding。
7. **崩溃修复（第一阶段遗留，真机日志定位）**：
   - applicationContext.display → UnsupportedOperationException → window.decorView.display
   - 卡片 fillMaxSize 撑满整屏 → 拖动边界倒挂 → fillMaxWidth + 空范围防御

---

# 第三阶段：Color Pipeline 结构化重构（Phase 3）

## 诊断修正

第二阶段对"重影"的归因（均匀权重 blur）经真机 REFRACTION_ONLY 验证后修正：
**主折射层自始至终是正确的（Golden Reference）**；错误视觉来自色散/模糊合成
结构的失控风险。本轮冻结折射段，仅重构颜色合成：

```
Background Capture（原始 RGB，无色散；backdrop 模块 LayerBackdrop）
    ↓
Refraction Pass（冻结：SDF → normal → 唯一 refractedCoord）
    ↓
Achromatic Blur（所有 tap 的 R/G/B 同一坐标；权重归一化；对角 0.7071 圆盘采样）
    ↓
Edge Dispersion（独立边缘探针：edgeBand(10dp) × insideMask × strength，仅 chromaDelta）
    ↓
Tint / Fresnel / Highlight / Final
```

## 关键实现

- `safeSample()`：半像素边界 clamp（layerSize = Backdrop 输入缓冲尺寸）
- `blurAchromatic5/9/13`：无色散、归一化权重（0.24+4×0.13+4×0.06=1.00）
- `sampleEdgeDispersion()`：R/B 探针 ±offset，Alpha 恒取中心采样
- `computeInsideMask / computeEdgeBand`：色散遮罩 = insideMask × edgeBand（中心≈0）
- Kotlin 侧限幅：dispersionOffset ≤1.0dp、strength 0~1
- 调试模式 6 档：BACKGROUND_ONLY / REFRACTION_ONLY / ACHROMATIC_BLUR_ONLY /
  DISPERSION_MASK（归一化显示遮罩形状）/ DISPERSION_ONLY / FINAL
- Shader key 只含画质档位（5/9/13），参数全走 uniform

## 真机二分验证记录（K-Pad 25079RPDCC, 120Hz）

| Step | 模式 | 结果 |
|---|---|---|
| 1 | BACKGROUND_ONLY | ✅ 无彩边、无处理（背景层无色散） |
| 2 | REFRACTION_ONLY | ✅ Golden Reference：纯折射、零模糊、零高光、零彩边 |
| 3 | ACHROMATIC_BLUR_ONLY | ✅ 无红蓝分离、无文字副本、RGB 轮廓一致 |
| 4 | DISPERSION_MASK | ✅ 中心全黑、边缘窄带、玻璃外全黑 |
| 5 | FINAL | ✅ 中心清透、边缘轻微色散、无重影无白蒙版 |

版本：1.2.0（versionCode 3）

---

# 第四阶段：晶体玻璃视觉 + 多形状 + 闪退修复（Phase 4）

## 修复

1. **画质档位 5t/13t 切换闪退（真机 bug）**：
   根因：main() 写死调用 blurAchromatic9，但 5t/13t 源码段只定义各自档位的模糊函数，
   切档时 AGSL 编译失败 → 运行时崩溃。
   修复：各档位定义统一入口 `applyBlur()`，main 只调用 applyBlur。
   真机验证：5t / 9t / 13t 往返切换全部存活，0 FATAL。

2. **边缘重构（去整圈白光）**：
   - 删除四角灯管点光（corner 项）；
   - 高光改为方向性镜面（pow(ndl,6) 窄化，仅光源侧）+ 顶部细线反光（pow(edgeMask,10)）；
   - 色散增强为可见晶体边（strength 0.20~0.30、带宽 12dp、偏移 ≤1.0dp）；
   - 内暗边极轻（pow(edgeMask,3) × 0.08~0.22）。
   真机确认：高光集中在左上（方向性）、右/下边缘为折射+暗边、边缘可见红/蓝彩带。

3. **多形状玻璃（GlassShape）**：
   - 圆角矩形 / 圆形 / 胶囊 / 椭圆 / 三角形（等腰三角，SDF + 数值法线）；
   - SDF 与 Compose 可见边界（drawBackdrop shape + 前景 clip）严格一致；
   - 光学边界、折射法线、色散带、圆角适配全部跟随形状；
   - 注意：AGSL 坐标为 y-down，三角形顶点必须与 Compose 裁剪坐标一致。
   真机验证：圆形/胶囊/椭圆/三角形渲染正确，无黑边无撕裂。

版本：1.3.0（versionCode 4）

---

# 第五阶段：二级控制中心 + 内容自适应 + 尺寸滑块（Phase 5）

## 新增能力

1. **二级控制中心（底部悬浮半屏面板）**：
   - 主界面默认干净：背景 + 玻璃卡片 + 右上角"控制中心"按钮；
   - 打开后为底部半屏悬浮面板（圆角、导航栏 insets 安全），**玻璃卡片保持可见，
     参数调节实时可见效果**；
   - 面板含返回按钮；性能面板收纳于面板底部（监控器生命周期不受影响）；
   - 菜单不进入 Backdrop 捕获层（根 Box 直接子级，非 BackgroundScene 内容）。

2. **前景内容随形状自适应**：
   - GlassShape 增加 contentWidthFraction（圆形 0.66 / 胶囊 0.84 / 椭圆 0.76 /
     三角形 0.80 / 圆角矩形 0.94）与 contentVerticalBias（三角形偏下 0.62）；
   - 内容区按形状收窄并偏移，文字/按钮不再溢出形状边界。

3. **玻璃尺寸滑块**：0.5~1.0 屏幕宽度比例，实时缩放主/副卡。

4. **背景内容自定义**：背景测试文字内容（TextField）、字号（20~120sp）、
   透明度（0.1~1.0）、位置（0.2~0.9）——全部进入 Backdrop 捕获层，穿过玻璃时折射。

版本：1.4.0（versionCode 5）

---

# 第六阶段：纯净玻璃收尾 + 自定义背景图片（Phase 6）

## 1. 可读性开关清理
- 移除"自适应可读性"与"增强可读性（接近磨砂预设）"两个开关（玻璃纯净后无前景文字可读，无意义）；
- adaptiveLegibility 默认 false —— 消除玻璃中上部"labelRegion 暗化横条"（真机确认暗带为背景山脉透过玻璃的天然显示）。

## 2. 自定义背景图片（SAF）
- 控制中心 → 背景内容 → "选择背景图片"（系统文件选择器 GetContent，IO 线程解码、≤2048px 采样）；
- Cover 填充 + 变焦滑块（1~3x）+ 水平/垂直偏移滑块（±0.5 视口）；
- "恢复默认背景"一键还原矢量风景；
- 图片绘制在 BackgroundScene（唯一 Backdrop 捕获源）内 —— 穿过玻璃产生折射/色散。

版本：1.6.0（versionCode 10）

## 构建产物标识与签名

| 项 | 值 |
|---|---|
| applicationId | `com.liqglass.ultraclear`（不再用 `com.example.*`，避免与 GitHub 上同类示例"包名相同、签名不同"而无法安装/并存） |
| 代码 namespace | `com.example.liquidglass`（仅代码包名，与安装标识无关） |
| 签名密钥库 | 项目根 `liquidglass-release.jks`（alias `liquidglass`，口令 `liquidglass2026`，RSA 4096，有效期 30 年） |
| 证书 | DN `CN=LiquidGlass Demo, OU=Demo, O=Demo, L=Changsha, ST=Hunan, C=CN` |
| 证书 SHA-256 | `2c9b6f0d1cc7eac0cadb54b07e42fd4c0840bf161b1a4cf08d289d2181c120ae` |
| 说明 | debug / release 均用该密钥签名，后续版本可直接覆盖安装；与其它项目并存互不影响 |

## 上游来源与致谢

| 来源 | 用途 | 许可证 |
|---|---|---|
| Kyant0/AndroidLiquidGlass（Backdrop 2.0.0, commit bebb11a） | 本地 Gradle module `:backdrop`：背景捕获层与 drawBackdrop 基础设施 | Apache-2.0 |
| **QWEA0/Liquid-Glass-Android（pandadog）** | **移植其玻璃光学模型**到本地 AGSL 管线 | MIT |
| Unsplash | 内置山水壁纸 4 张：`bg_clouds.jpg`（云海日出）· `bg_meadow.jpg`（草地）· `bg_lake.jpg`（湖泊倒影）· `bg_forest.jpg`（森林） | Unsplash License（免费使用含商用、无需署名）。**逐图出处说明**：这 4 张在使用时未记录具体照片页链接，本仓库不作追溯性链接声明（✗ 不编造 URL）；如需逐图可核验出处，可由作者补录原始链接后更新本表。 |

### 移植说明（v1.16.0，仅光学模型）

- 移植自 `liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt` 的内联 AGSL
  `LENS_AGSL`（MIT, Copyright (c) 2025-2026 pandadog）。落地在 `glass/GlassShaders.kt`：
  1. **逆幂衰减折射剖面**（引力透镜）：`slope = ((1+4t)^-p - 5^-p)/(1 - 5^-p)`，t 为厚度剖面，
     贴边弯折最剧烈、内侧留缓慢回落尾巴；沿外法线**向内采样**（iOS 一致：边缘是内侧背景的压缩镜像）。
  2. **色散**：RGB 三通道按 `slope` 缩放的分裂量分别采样，中心 slope→0 时完全无色散。
  3. **边缘光照**：`facing = dot(N, -L)`、双角度瓣 `pow(max(±facing,0), 4.5)`，
     贴边发丝亮线 + 迎光侧内辉光；无方向无关的常亮项，因此不会出现"整圈固定描边"，
     且完全由法线场驱动 —— 任何形状的四个角都自动贴合可见边界。
  4. **触点局部液态凸起**：`bump = touchAmp·exp(-r²/(σ²·0.30))`，按压时手指下方局部放大。
- 未移植：其 View 体系、NDK C++ 高斯模糊（gauss_iir/boxblur）、RenderNode 录制管线、
  `shape2` 双形状 smin 融合、`rimSoft` 三抽头柔化（保留 uniform，本轮传 0/半值）。
- 兼容开关：`lensProfile` uniform（1 = iOS 透镜剖面，0 = 本项目原有 circleMap 剖面）。
  【已按用户要求固定为开】面板里那行「iOS 透镜折射剖面」开关已删除（用户原话：关掉以后效果好诡异，
  直接删掉、默认打开即可）⇒ 消费点写死 `true`（LiquidGlassScreen）；字段/uniform 保留作 A/B 留档，
  运行时无法再关（`setUi iosLensProfile 0` 只写字段、观感零变化）。

### 形状对齐全链路（v1.16.0 修复）

- **圆角矩形**：可见边界过去是 `RoundedCornerShape(36)`（Compose 的 **百分比** 圆角 =
  短边 36% ≈ 236px），而 Shader SDF 用 36dp ≈ 101px。两者不同源 → 边缘高光沿"更小圆角"
  的轮廓走、**四个角完全没有高光**。现统一取 `GlassShape.cornerRadiusDp` 的绝对值。
- **椭圆**：可见边界过去是 `RoundedCornerShape(50)`（胶囊），与椭圆 SDF 不符，现改为真正的椭圆 Path。
- 规则：**可见边界（Compose Shape）必须与 Shader SDF 同源**，改形状参数要两处同时改。

### v1.16.2 / v1.16.3 修复（用户反馈三项）

1. **控制中心收起时"白→黑→白"闪变**
   根因：形态动画中段把玻璃整层关闭、露出不透明深色填充（`0xF00B1424`），而两端
   又是玻璃，于是出现两次硬跳变。现在玻璃透明度与填充透明度**互补交叉淡入淡出**：
   `glassAlpha` 在 morph 0.62↔0.38 区间平滑过渡，`fillAlpha = (1 - glassAlpha) * 0.62`
   且填充改为半透明（背景亮度能透过来）。实测形态轨迹（logcat）：
   `1.00 → 0.85 → 0.68 → 0.50 → 0.31 → 0.15 → 0.00 → … → 0.28 → 1.00`，无硬跳变，帧内无黑帧。
   玻璃仍只在中段关闭，展开动画帧率优化保持不变。

2. **主页面可上滑展开为「全部控制中心」**
   形态进度由一维扩展为 0..2：`0` = 底部胶囊按钮，`0..1` = 标准面板（0.55 屏高），
   `1..2` = 满高面板（0.98 屏高，露出全部区块）。用 `NestedScrollConnection`
   的 `onPostScroll` / `onPostFling` 做抽屉式嵌套滚动——列表还能滚就先滚列表，
   滚到尽头后的剩余纵向位移才带动面板高度；标题栏（把手 + 标题行）也可直接上下拖拽。
   松手按 0.5 / 1.5 阈值吸附到 收起 / 标准 / 满高。实测：展开阶段 `raw→2.00`、
   高度 `1654 → 2948px`、玻璃保持 1.00；展开后面板顶到屏幕顶部，可见区块从
   预设一路延伸到背景文字各项。

3. **横屏"没有同步拉伸"**
   界面本身是满屏的（`letterBoxed=false`），问题在背景图按 **Fit** 绘制 → 竖版壁纸
   在横屏上只占中间一条（约 39% 宽），左右留空带，观感像没拉伸。现在：当 Fit 会在
   屏幕上留下明显空带时（`fitScale < coverScale * 0.85`）自动改用 **Cover**
   （按短边铺满 + 居中裁切）；比例接近时仍保持 Fit 不裁切。
   实测 3008×1880 横屏：左右边缘亮度由 25（黑边）变为 42~105（有画面）。

### v1.19.0（本轮修复与回退）

1. **色散回调**：上一版把色散加得过猛（偏移上限 4.5dp / 强度 0.72 / 合成 ×2.2），观感偏假。
   现回调为：偏移 `lerp(1.0, 2.0, t)` dp、带宽 11dp、强度 `lerp(0.55, 0.32, t)`、
   Shader 合成系数 2.2 → 1.35，只保留一圈很淡的光谱边。
2. **按压模型（修回归）**：按压【只改变玻璃形状、不动内部画面】的正确做法是
   - 位置平移必须留在最外层节点（Backdrop 采样坐标按该节点窗口坐标映射；
     一旦把平移下移到内层，会出现「拖不动 + 折射采样跑偏」）；
   - 形状变化必须【向内收缩】：向外膨胀会越过可见边界（drawBackdrop 按 Compose Shape
     裁剪），贴边高光会被一起裁掉，表现为"点一下高光就没了"。
     现为 `shapeInflate = (-6dp, -2dp) * press`，高光在按下/松开全程保持（实测边缘最亮 255）。
3. **开关要有可见差异**：
   - 「减少动态效果」→ 关闭按压形变 + 展开/收起动画时长减半；
   - 原「前景内容形变」是无效果的（卡片里本来就没有内容）→ 改名「拖动拉伸（玻璃）」，
     控制拖动速度是否产生玻璃拉伸位移，默认开；
   - 「双卡演示」→ 改名「双卡演示（两张玻璃）」，并补一行说明。
4. **面板可读性**：透射 0.74 → 0.60、深蓝材质填充 0.40 → 0.60、模糊 20 → 26dp，
   实测底色对白字对比度 5.0 ~ 13.9 : 1（WCAG AA ≥4.5）。
5. **回退**：曾尝试用"额外画一块玻璃元素"实现两卡之间的液滴粘连，实际效果是屏幕上
   多出第三块玻璃、且自身不可交互（用户实测反馈），已完整回退。

### 待实现：液滴张力融合（two-card liquid merge）

正确的做法不是额外画元素，而是让两张卡片在 **Shader 的 SDF 内部**互相 smooth-min 联合：

1. 给卡片 Shader 增加 `shape2`（对方卡片的 rect + 圆角）与 `blendK`（融合半径）两个 uniform，
   在 `sdShape` / `gradShape` 里对本地形状与对方形状做 `sminPoly`（多项式 smooth-min）；
2. 可见边界（`drawBackdrop` 的 clip Shape）改为【两个形状的并集 + 融合半径的膨胀】——
   用 `Path.op(pathA, pathB, PathOperation.Union)` 生成 `GenericShape`，
   否则颈部（位于两形状之间的缝隙里）会被裁掉；
3. 两张卡片各自都会画出同一段颈部，且使用的是各自卡片的光学参数（同种材质 → 视觉无缝），
   颈部属于玻璃本体，因此拖动、按压、色散、边缘高光都自然跟着走。

参考实现：QWEA0/Liquid-Glass-Android 的 `GlassLensRenderer.LENS_AGSL` 里 `shape2 + blendK + sminPoly`
那一段（MIT），本轮只移植了单形状部分。

### 备注：调试期间壁纸被误改

批量点按形状按钮时，面板每次打开滚动位置不同，误触了「背景壁纸」选项（切到了雾中森林）。
已用 `pm clear` 复位为默认的「云海日出」。批量 UI 自动化务必固定滚动位置后再取坐标。

### v1.20.x（按用户反馈的四项）

1. **按压时模糊/折射层与可见形状不同步**：按压改变的是 Shader SDF 的收缩量，
   而可见边界（drawBackdrop 的 clip Shape）还停在原尺寸 → 表现为"模糊层没跟着变形状"。
   现在新增 `PressInsetShape`，按 (insetX, insetY) 逐轴内缩、圆角同步减小，
   与 Shader 的 `shapeInflate` 完全同源。实测按住时左右边界各内收 ≈17px（= 6dp × 2.8125）。
   ⚠️ 逐轴一致是硬要求：任何一轴收得比 SDF 多，都会把贴边高光裁掉。
2. **滑块外观**：面板里的滑块（玻璃尺寸/背景文字各项）改为「静止 = 纯色微透明白椭圆，
   按下/拖动 = 液态玻璃珠」；两者按按压进度交叉过渡，玻璃珠带边缘亮线、内部高光与压扁形变。
   展开按钮仍保持液态玻璃（上一版误改，已回退）。
3. **高光与背景互动**：高光不再一律纯白 —— 用边缘处的背景采样给它上色
   （暖背景出暖高光），并按背景亮度调节强度（亮背景收敛、暗背景更亮）。
4. **HDR 边缘高光**：`MainActivity` 请求 `ActivityInfo.COLOR_MODE_HDR`（本机实测支持：
   mMaxLuminance=1000nits、supportedHdrTypes=[1,2,3,4]，日志 `colorMode=2`），
   Shader 新增 `hdrBoost` uniform：HDR 生效时贴边高光可超过 SDR 白点（卡片 ×4.0 / 面板 ×3.0），
   非 HDR 设备恒为 1.0（超出部分被显示端截到纯白，观感不变）。
   注意：HDR 亮度无法用截图验证（截屏会截断），需要肉眼在真机上确认。
5. **性能看板**：只保留 内存 / GPU / CPU / 帧率压力 四项（原 FPS/P95/Jank 已移除，
   用户反馈"一个正常用户看不懂"）。帧率压力按平均帧时间相对 16.7ms 换算 低/中/高 并配色。

---

## 许可与声明

- **本项目自身代码**：Apache License 2.0，见根目录 [`LICENSE`](LICENSE)。
- **第三方组件**（逐个列明上游仓库 / 版本 / 许可证 / 版权行 / 本项目的修改说明）：
  见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)；随附的许可副本在 [`LICENSES/`](LICENSES/)。
  其中以源码模块形式随仓库分发的 `backdrop/` 来自 Kyant0/AndroidLiquidGlass（Apache-2.0，Copyright 2025 Kyant），
  该项目**不含** NOTICE 文件，故无随附 NOTICE 义务；本项目对其的修改已在源码注释与 NOTICE 文件中声明。
- **素材**：内置壁纸来自 Unsplash（Unsplash License：可免费使用含商用、无需署名）；其余图标与示意图为本项目自绘/自产。
- **商标**：「Liquid Glass」一词是 Apple Inc. 自 2025 年起使用的品牌名与设计概念；本项目仅为**技术演示**，
  与 Apple **无任何关联**、未经其授权或背书，也**不主张**任何商标权。详见 [`TRADEMARKS.md`](TRADEMARKS.md)。

【外观主题·2026-09-25】控制中心面板在【跟随系统/强制亮色/强制暗色】三档主题间自动切换
（ui/GlassTheme.kt），全部文字在 6 张内置壁纸上实测 ≥4.5:1；交棒飞行字两端同色时恒色（不再插灰）。
调试开关与回退命令见 NEXT.md「对比度修尾批 + 交棒恒色化」小节。
