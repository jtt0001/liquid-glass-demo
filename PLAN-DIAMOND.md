# PLAN-DIAMOND.md —— 3D 钻石演示（分支 feat/diamond-demo）

> 分支：`feat/diamond-demo`（从 `integrate/cc-fixed` HEAD 5e5e0ae 切出）
> 工作树：`/Users/jt/Projects/lg-diamond`（**独立 worktree**；主树 /Users/jt/Downloads/LiquidGlassDemo 保持 integrate/cc-fixed 不动）
> 目标：新增【3D 钻石演示】——每面独立折射（物理正确实体折射：入面 Snell → 内部 TIR → 出射面再折射 → 采样背景），可自由旋转。
> 硬约束：**保留现有全部功能**（不是精简演示 App）；钻石只是控制中心一级菜单里的一个新入口；APK 名 = 钻石。

## 需求（用户原话要点）
1. 一级菜单（与「玻璃风格/形状与布局/玻璃尺寸/背景壁纸/交互与动效/更多设置」同级）新增【钻石】入口 → 进入独立演示页/模式。
2. APK 名直接叫【钻石】（本分支改 `res/values/strings.xml` 的 `app_name`），✗ 不影响 main 的现有名称。
3. 3D 钻石：物理正确实体折射（每面独立），可自由旋转。

## 阶段（每阶段：落盘 → 构建 → 设备验证 → 本分支 commit）
- **P1 独立 AGSL 模块**（新文件，✗ 不碰 glass/**、✗ 不碰 backdrop/**）：固定姿态 + 单次折射（+Fresnel 反射项）。验收：4× 放大图能看出切面 + 折射位移；用现有 `frameTimeline` 仪器量帧成本（连续重绘下每帧耗时）。
- **P2 旋转**：拖动 + 惯性（四元数/欧拉 → 3×float3 行向量 uniform ⇒ 不触发重组/重录）。验收：逐帧日志证明旋转期无掉帧（`frameTimeline` CSV + 应用侧节流转速日志对表）。
- **P3 色散 + TIR**：RGB 三条射线 + 内部弹射 2~4 次。验收：帧耗时 + 每加一次弹射的边际成本（bounces=0/1/2/3/4 各测一次）。
- **P4 接入形态**：一级菜单「钻石」入口 + 独立演示页 + 开关 + 尺寸跟 `glassSize`。
- **P5 调优**：分辨率档 / AA / 性能预设 + 真机逐帧验收（本轮以 emulator-5554 为准，平板不测）。

## 关键约束
- 简化切工 **16~24 面平面表**（本项目取 17 面：1 台面 + 8 冠部面 + 8 亭部面）。
- 只对**包围盒内**算 + 盒外 early-out（盒外直接 `return content.eval(coord)`）。
- 采样越界：**限幅 + 边缘延展**（G2 切点溢线教训）。
- 切面边缘 AA 提前设计（`uAA` 宽度 + coverage 接进输出颜色）。
- TIR 掠射角：阈值 + 退化处理（`k < 0` 即全反射，不许出现 NaN）。
- ✗ 不动 main 与现有行为；✗ 不碰控制中心几何/折射参数（铁律）。

## 冻结契约（三个代理按此并行写不同文件；父会话统一构建/装机/提交）
- **包**：`com.example.liquidglass.diamond`
- A 名下：`diamond/DiamondUniforms.kt`、`diamond/DiamondModel.kt`、`diamond/DiamondAgsl.kt`、`tools/verify_diamond_geometry.py`
- B 名下：`diamond/DiamondDemoState.kt`、`diamond/DiamondDemoPage.kt`、`diamond/DiamondDemoBridge.kt`
- C 名下：`ui/GlassControlsPanel.kt`（**只加**入口与回调）
- 父会话名下：`diamond/DiamondBridgeApi.kt`、`ui/LiquidGlassScreen.kt`、`debug/DebugBridge.kt`、`debug/DebugSwitches.kt`、`res/values/strings.xml`、`app/build.gradle.kts`

### 几何（模型半径 = 1，+z = 台面方向，八边形顶点角 22.5° + 45°k）
- 腰棱环：r = 1，z = 0（本切工腰厚 = 0，即腰棱为锐边）
- 台面：八边形外接半径 r_t = 0.58，位于 z = +0.288（冠高）
- 尖底（culet）：(0,0,-0.86)（亭深）
- 面表 17 面（法线朝外、单位长；内部 = dot(n,p) ≤ d）：
  - 台面 1：n=(0,0,1)，d=0.288
  - 冠部 8：过腰棱边 [v_k, v_(k+1)]（z=0）与台面边 [t_k, t_(k+1)]（z=0.288）的平面
  - 亭部 8：过腰棱边 [v_k, v_(k+1)]（z=0）与尖底点的平面
- 相机：位于 (0,0,camZ)，camZ = 3.6；z=0 平面按恒等映射到"半径单位"屏幕坐标（焦距 = camZ）
- 背景平面：z = -depth，depth = 6.0；出射射线与该平面求交后**逆投影**回屏幕坐标采样

### uniform（AGSL `uniform` 名 = `DiamondUniforms` 常量；✗ 不得用内置名 resolution/time/frame/date）
`content`(shader) / `uRes`(float2) / `uCenter`(float2) / `uRadius`(float) / `uCamZ` / `uDepth` / `uRow0`(float3) / `uRow1` / `uRow2` / `uIor` / `uDispersion` / `uBounces`(&lt;=4, float) / `uDebug`(0..4) / `uAA`(px) / `uFresnel`

## 进度台账
- 2026-09-14 18:0x 建 worktree/分支 + 基线 `assembleDebug` BUILD SUCCESSFUL（缓存命中）；派 A/B/C 三代理并行写文件（不跑 gradle、不碰设备）。
