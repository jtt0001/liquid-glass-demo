# 分层架构（LAYERING）—— 背景 → 卡0…卡N → 面板 → 看板

> 本文件是【分层架构】的文档化结论：当前真实分层图（含每层录制次数实测）、与目标态的差异、
> 本轮的实现（最小侵入 + 默认开开关 + 一行回退）、以及验收证据（数字 / 图 / 逐像素 / 双构建）。
> 代码落点：`backdrop/**`（库内录制策略，本项目私有扩展 `BackdropRecordPolicy.kt`）+ `debug/DebugBridge.kt`（运行时命令 `layerStack`）。
> 测量设备：emulator-5554（1840×2944@320dpi）。测量窗口内多次被其它代理装机打断 ⇒ 全部数据按
> 「同窗口 + 探针自检 + dumpState 指纹」三重门采集（见 §5）。

---

## 1. 目标态（层栈定义）

```
（屏幕坐标，自下而上 = 绘制顺序）
  ① 背景层        captureLayer          ← BackgroundScene（壁纸/渐变那一层）
  ② 卡 0          卡玻璃层 + cardCaptureLayers[0]
  ③ 卡 1 … 卡 N   卡玻璃层 + cardCaptureLayers[i]（N = activeCardCount，最多 4）
  ④ 面板层        面板玻璃层（+ 填充子层 panelFillLayer）
  ⑤ 看板          纯 UI（性能 HUD），不创建/不采样任何 LayerBackdrop
```

硬规则（本单的目标 + 断言口径）：

1. **每层每帧最多录制一次**（同一份内容不得在同一帧里被录两次）。
2. **采样源严格是【它下面那一层或合规组合】**：卡 i 只采 背景 + z 序在它之下的卡；面板只采它下面的层。
3. **✗ 无自引用**：任何层不得把自己的输出（或其自身子树）纳入自己的采样源。
4. **✗ 无重复录制**。
5. **✗ 无跨层直接读**：不得越过中间层去读"上一层"或"隔着几层"的层。

---

## 2. 当前真实分层图（改前 = HEAD `f240002` + 本单之前的接线；A 档默认 `cardOverPanel=false`）

| # | 层（LayerBackdrop 对象） | 录什么 | 谁采样它 | 每帧录制次数（实测，改前） | 一致性 |
|---|---|---|---|---|---|
| ① | `adapter.captureLayer`（全屏 1840×2944） | BackgroundScene 的绘制结果（只含 App 背景） | 卡 0..N、面板玻璃、面板预热节点（1px）、钻石页 | **1**（节点绘制时） | ✓ |
| ② | 卡 i 的**玻璃层**（库内 `DrawBackdropNode.graphicsLayer`，如 840×1297） | 该卡的 AGSL 光学结果 | 该卡自身绘制（+ 导出重放） | **2**（同一次 draw 里被录两次）| ✗ 违规则 1/4 |
| ② | 卡 i 的**导出层** `cardCaptureLayers[i]`（676×1133） | 卡的"已渲染结果"（backdrop 结果+surface+front，不含内容层） | z 序在它之上的卡（`chainBackdrop`） | 1（仅当有卡在它之上） | ✓ |
| ③ | 面板容器离屏层 `panelCaptureLayer`（1776×1619） | 面板容器**最终绘制结果**（填充+玻璃+内容+裁剪） | **默认档下无人采样**（只有 B 档 `cardOverPanel=true` 时卡会采它） | **1**（= 面板每次绘制都录 ⇒ 白录）| ✗ 违反规则 4（无消费者仍录制） |
| ④ | 面板填充层 `panelFillLayer`（1776×1619） | 面板**填充层**（不含任何控件） | 面板内真实控件（LiquidToggle / LiquidSlider / 底栏） | 1（控件绘制时） | ✓（子层，自洽） |
| ⑤ | 组件内子层（`LiquidToggle`/`LiquidSlider`/`LiquidBottomTabs` 各自的 `rememberLayerBackdrop`，如 128×56 / 1712×12） | 控件轨道自身 | 该控件的旋钮 lens | 1 | ✓（子层，自洽） |
| — | 看板（`CompactPerformancePanel`，纯 Canvas/图形） | —（不建层） | — | 0 | ⚠ z 序见 §3-④ |

**采样源图（谁读谁）**

```
captureLayer(背景) ──► 卡0 玻璃层 ──(输出)──► cardCaptureLayers[0] ──┐
      │                                             │              │
      │                                             ▼              ▼
      │                                       卡1 玻璃层 ──► cardCaptureLayers[1] ──► 卡2 …（自下而上逐层累积）
      │
      ├──► 面板玻璃层（仅背景；不含卡层 ✗ 见 §3-③）
      └──► 面板填充层（Box）──► 面板内控件（Toggle/Slider/底栏）
```

**实测：单卡 + 面板 p 往返 ×3 + 主卡拖动 ×4（pass4，109 帧渲染）**

| 层 | 角色 | 尺寸 | 次数 | 次/帧 |
|---|---|---|---|---|
| 背景层 | capture | 1840×2944 | 73 | 0.67 |
| **面板容器离屏层** | capture | 1776×1619 | **73** | **0.67** |
| 面板填充层 | capture | 1776×1619 | 5 | 0.05 |
| **卡 0 玻璃层** | glass | 840×1297 | **194** | **1.78** |
| 卡 0 导出层 | export | 676×1133 | 97 | 0.89 |
| 合计 | | | 573 | 5.26 |

自归一化判据（免疫两臂窗口长度不同的干扰）：**卡玻璃层 ÷ 卡导出层 = 194/97 = 2.00**（= 同一次绘制里玻璃层被录 2 次）。

---

## 3. 与目标不一致处（共 5 条）

| # | 不一致 | 证据 | 处置 |
|---|---|---|---|
| ① | **重复录制**：卡玻璃层在同一次 `draw()` 里被录两次 —— `DrawBackdropNode.draw()` 先 `drawBackdropLayer()`（录制+绘制），随后在 `exportedBackdrop` 重放块里**又**调一次 `drawBackdropLayer()`（把同一份内容重新录进同一个 GraphicsLayer） | 实测玻璃层/导出层 = **2.00**（pass2: 48/24、pass4: 194/97） | ✅ 本轮修复（策略①） |
| ② | **无消费者仍录制**：默认档（面板在卡之上）卡的采样源里没有面板 ⇒ `panelCaptureLayer` 全工程无人采样，但面板容器每帧照录（1776×1619 的整面板离屏录制） | 实测面板容器层 0.67 次/帧且无人采样（pass4）；判别实验：`liquidRealControls=0` 时改前仍有 6 次/窗口、改后 0 次（pass3b D2/D3）；B 档（卡开始采它）时改后重新恢复录制 7 次（pass3b D4） | ✅ 本轮修复（策略②） |
| ③ | **采样组合不完整**：面板玻璃的采样源 = `captureLayer`（只背景），而卡层在它之下 ⇒ 面板边缘折射带在"压住卡"的位置显示的是背景而不是卡 | `Modifier.glassPanel(...)` 调用点未传 `backdrop`（默认 `adapter.captureLayer`）；面板玻璃在 A 档是最高玻璃层 | ⏸ 未改：调用点在被占用的 `ui/LiquidGlassScreen.kt`（改法见 §6-①，一行） |
| ④ | **看板 z 序**：性能看板是纯 UI 层（不采样），但未设 `zIndex`（默认 0）⇒ 被卡 1..N（z=1..3）与面板（z=10）压住；目标态要求它是最上层 | `ui/LiquidGlassScreen.kt` 的卡/面板 zIndex；看板文件无 zIndex | ✅ 2026-09-17 已改（审计 H2 落地）：zIndex(11f)——调用点 + 看板浮动容器两处（§6-② 的一行改法已实施） |
| ⑤ | （说明项）组件内子层（track/fill）也在录制 —— 它们是**层的子层**，自录自采、无自引用、有消费者，符合目标 | pass1/pass4 探针：均为 1 次/绘制 | 保留（不属不一致） |

---

## 4. 实现（最小侵入 + 默认开开关 + 一行回退）

只改 **库**（`backdrop/**`，无人占用）+ 一个**运行时命令**（`debug/DebugBridge.kt`），
✗ 不动 `glass/**` 折射默认参数、✗ 不动控制中心胶囊几何、✗ 不动交棒区 / 手势区、✗ 不动 `ui/**`。

| 文件 | 改法 |
|---|---|
| `backdrop/src/commonMain/kotlin/com/kyant/backdrop/BackdropRecordPolicy.kt`（**新增**） | 策略对象 `BackdropRecordPolicy`（两个默认开的开关）+ 探针 `BackdropRecordProbe`（逐层计数/尺寸/角色）+ `shouldRecordLayer(...)` |
| `backdrop/.../internal/LayerRecorder.kt` | 录制漏斗加 `tag`（capture/glass/export）+ 探针打点（默认关，一次 volatile 读） |
| `backdrop/.../backdrops/LayerBackdrop.kt` | 加 `sampleSeq`（被采样计数）与 `everRecorded` |
| `backdrop/.../backdrops/LayerBackdropModifier.kt` | 录制前判 `shouldRecordLayer(everRecorded, sampleSeq>0)`；首帧必录（防"空层/黑玻璃"） |
| `backdrop/.../DrawBackdropModifier.kt` | 新增 `drawGlassLayerOnly`（只画不录）；`exportedBackdrop` 重放块默认改用它（策略①） |
| `debug/DebugBridge.kt` | 新增命令 `layerStack`（翻转策略 / 开关探针 / 清零计数 / dump 逐层统计） |
| `app/build.gradle.kts` | versionCode 291→292、`2.45.0-diaentry`→`2.46.0-layerstack` |

**开关（默认全开 = 目标态）与一行回退**

```bash
# 关「单次录制」⇒ 逐字回到改前：exportedBackdrop 重放块里重新录制玻璃层（重复录制）
adb shell am broadcast -a com.liqglass.DEBUG --es cmd layerStack --ei single 0 -p com.liqglass.ultraclear
# 关「按需录制」⇒ 逐字回到改前：无人采样的层也每帧录制
adb shell am broadcast -a com.liqglass.DEBUG --es cmd layerStack --ei sampled 0 -p com.liqglass.ultraclear
# 恢复默认（两条都开）：
adb shell am broadcast -a com.liqglass.DEBUG --es cmd layerStack --ei single 1 --ei sampled 1 -p com.liqglass.ultraclear
```

**像素等价性（为什么默认档观感零变化）**
- 策略①：同一帧内两次录制的输入（uniform / 采样层内容 / 尺寸）完全一致 ⇒ 输出逐像素相同；
  重放块原本需要的是"把玻璃层画进去"这个动作，不需要再录一遍。
- 策略②：只跳过"没人读的层的写入"；被跳过的层本来也没有任何读取方 ⇒ 屏幕像素不变。
  首帧必录 ⇒ 采样方不会看到"从未有过内容"的空层（本项目"黑卡"坑的防线）。

---

## 5. 验收

### 5.1 每帧各层录制次数 / 尺寸（改前 vs 改后，同一构建的两个运行档）

同一窗口、同一操作序列（面板 p 往返 ×3 + 主卡拖动 ×4；帧数取 `dumpsys gfxinfo` 的
`Total frames rendered`；每层计数取 `layerStack` 探针）：

| 层（尺寸） | 改前 次数 / 次每帧 | 改后 次数 / 次每帧 | 判定 |
|---|---|---|---|
| 背景层 1840×2944 | 73 / 0.67 | 29 / 0.45 | 不变（有消费者）|
| **面板容器层 1776×1619** | **73 / 0.67** | **0 / 0.00** | ✅ 无人采样 ⇒ 不再录 |
| 面板填充层 1776×1619 | 5 / 0.05 | 5 / 0.08 | 不变（有消费者：面板内控件）|
| **卡 0 玻璃层 840×1297** | **194 / 1.78** | **43 / 0.67** | ✅ 去重（÷导出层：2.00→1.00）|
| 卡 0 导出层 676×1133 | 97 / 0.89 | 43 / 0.67 | 同帧自归一 ⇒ **2.00 → 1.00** |
| 窗口合计 | 573 / 5.26 | 173 / 2.70 | — |
| 渲染帧数（同窗口） | 109 | 64 | 窗口长度不同 ⇒ 只看"同帧内比值"与"每层有无录制" |

第二组（2 卡、只拖主卡、面板收起；pass2）：卡玻璃层/导出层 = **48/24 = 2.00** → **7/7 = 1.00**；
面板容器层 16 次/窗口 → **0**；面板填充层 2 → 2（不变）。

层身份判别（pass3b，每步清 logcat + 自检）：

| 步 | 档位 | 面板尺寸层（1776×1619）录制次数 | 结论 |
|---|---|---|---|
| D1 | 改后 + 默认控件 | 容器 **0**、填充 6 | 容器无人采样 ⇒ 不录 |
| D2 | 改后 + `liquidRealControls=0`（摘掉填充层录制修饰符）| **0**（两类都没有）| D1 里那 6 次属于【填充层】，容器确实是 0 |
| D3 | 改前 + `liquidRealControls=0` | 容器 **6** | 改前 = 无条件录制（无人采样也录）|
| D4 | 改后 + `cardOverPanel=1`（B 档：卡开始采面板）| 容器 **7**、填充 6 | 策略②是"消费者驱动"：有消费者 ⇒ 立刻恢复录制 |

### 5.2 四种场景干净图（1840×2944，无标注）

每个场景两拍：**面板收起**（全部卡可见，= 层栈"卡层"部分）+ **面板展开**（p=1，= 卡层与面板层的叠放）。

| 图（主拍：面板收起）| 场景 | 内容 |
|---|---|---|
| `/tmp/LG-layer-S1_不重叠_2卡_收起.png`（源 `/tmp/lg-layer/pass5b/S1_不重叠_2卡_收起.png`）| 2 卡不重叠（主卡左移、副卡原位）| 两张玻璃卡全可见 + 收起胶囊 |
| `/tmp/LG-layer-S2_重叠_2卡_收起.png` | 2 卡相互重叠（并集、无双边框）| 同上 |
| `/tmp/LG-layer-S3_三卡_收起.png` | 3 卡（上移摊开）| 三块不同形状玻璃 |
| `/tmp/LG-layer-S4_四卡_收起.png` | 4 卡（上移摊开）| 四块不同形状玻璃（圆角矩形/圆/六边形/三角形）|

面板展开变体：同名 `…_展开.png`（`/tmp/lg-layer/pass5b/`）。
拍前状态（同批 dumpState 指纹）：`card[0]=[582,240 644x1057]`、`card[1]=[614,380 1012x1012]`、
`card[2]=[32,631 773x773]`、`card[3]=[1150,737 626x626]`；抓图时构建 = `2.46.0-layerstack`（vc292）。
（早一批 pass5 的 `S1..S4` 同样有效，只是 3/4 卡场景里部分卡被展开面板挡住。）

### 5.3 逐像素回归（同一状态，只切录制策略）

判据：`dumpState` 指纹（panel / card[0] / listslot / alpha(p) / gates）逐字相同才取用；
差异阈值沿用项目口径「>8 灰阶」。

| 对 | 面板区域内 | 面板外（屏蔽时钟+看板动态读数带）| 说明 |
|---|---|---|---|
| 改前 vs 改前（同状态连拍 = 噪声底）| **0 px** | **0 px** | 静态下该 App 的噪声底就是 0（指纹一致时）|
| **改前 vs 改后（只切策略）** | **0 px** | **0 px** | ✅ 逐像素中性（全屏仅剩 App 自带动态读数）|
| 改前(第二拍) vs 改后 | **0 px** | **0 px** | 同上 |

> ⚠️ 反例提醒（写进纪律）：**不核对指纹的跨拍比对会给出假差异**。本轮早期几拍（pass6/pass7）
> 曾出现"面板区 60~85 万像素差异"，事后用指纹/版本号查明是**并发代理的广播（改写 p / 滚动 / 换页）
> 或覆盖装机**造成的状态漂移；同状态 ± 秒级连拍 + 指纹核对后，差异恒为 0。
> 这也解释了项目旧记录里"面板区同状态连拍 559K~950K px 固有抖动"——**先查指纹，再谈抖动**。

### 5.4 双构建（必过）

```
[隔离树]（git archive HEAD(f240002) + 本单 7 个文件）:app:compileDebugKotlin --rerun-tasks → BUILD SUCCESSFUL (11/11 executed)
[隔离树] …:app:assembleDebug --rerun-tasks           → BUILD SUCCESSFUL (48/48 executed)
[主树]  :app:compileDebugKotlin --rerun-tasks        → BUILD SUCCESSFUL (11/11 executed)
[主树]  :app:assembleDebug --rerun-tasks             → BUILD SUCCESSFUL (48/48 executed)
```
（主树第一次构建曾因**其它代理的半成品**失败：`BackdropAdapter.kt:135 Unresolved reference 'blurTierRich'`；
该字段随后由其作者补回，重跑即通过 —— 与本单改动无关；本单改动在隔离树上先行验证通过。）

产物：`/tmp/lg-layer/lg-layerstack.apk`（sha256 `a449072a…684c4`，versionCode 292 / `2.46.0-layerstack`）。

---

## 6. 未改项与精确改法（留给占用 ui/** 的文件主）

**① 面板层采样源补上"卡层"**（`ui/LiquidGlassScreen.kt`，面板玻璃调用点 ≈2829 行）
```kotlin
// 现在：不传 backdrop ⇒ 库默认 adapter.captureLayer（只有背景）
backdrop = adapter.captureLayer,                       // ← 旧
// 目标：背景 +（z 序在面板之下的）所有卡的导出层（合规组合：全部在它下面）
// 注：需要同时把 exportedLayerFor(i) 的"有人采样我"判据扩为【有卡在它之上】∨【面板在它之上】，
//     否则最上层卡不会录自己的导出层（面板就没有可采的内容）。
backdrop = rememberCombinedBackdrop(
    adapter.captureLayer, *adapter.cardCaptureLayers.take(activeCardCount).toTypedArray()
),
```
风险：会改变面板【边缘折射带】在"压住卡"处的像素（面板填充层 0.90 不透明度以内基本不可见）；
建议先出 A/B 图给用户。

**② 看板置顶**（`ui/LiquidGlassScreen.kt` 看板调用点，或看板自身 modifier）
```kotlin
Modifier.zIndex(11f)   // > 面板 z=10 ⇒ 看板真正在最上层（纯 UI 层，不参与采样 ⇒ 无层级副作用）
```

**③ 若要"面板层也进卡的可视链"以外的扩展**（例如把看板做成玻璃件）——不在本单范围。

---

## 7. 残留 / 不确定（诚实清单）

1. **帧数口径**：两臂窗口的渲染帧数不同（109 vs 64，并发抢占导致驱动步长漂移）⇒ 表中
   "次/帧"只能同层横向比趋势；**硬判据用"同帧内自归一比值"**（玻璃÷导出 = 2.00→1.00；
   容器"有/无录制"）。
2. **策略②的边界语义**：目前是"**曾经被采样过**就继续录"（不是"当前有采样方"）。
   选择理由：避免"采样恢复那一帧读到旧内容"的窗口（本项目"玻璃采到旧内容"的教训）；
   代价：一个层若曾用过但再也不被采样（如 B 档用完切回 A 档的 `panelCaptureLayer`），仍会继续录。
   更激进的"当前无采样方就停"需要配套"采样方请求补录"的失效通路，本轮**不做**。
3. **真机（平板）未验**：全部数据来自 emulator-5554（host GPU）。录制次数是 CPU 侧行为（与 GPU 无关），
   但**帧时间收益**（少一次 644×1057 的 AGSL 录制 / 少一次 1776×1619 的离屏录制）需在平板上复测。
4. **④ 看板 z 序 / ③ 面板采样源**：未改（文件占用），已在 §6 给出精确改法和风险。
5. 早前几拍的"面板区大差异"已被判为**并发干扰**（指纹不一致），不作为结论；如需第三方复核，
   复现命令与脚本在 `/tmp/lg-layer/*.sh`（`pass4.sh` / `pass8.sh` 最关键）。
