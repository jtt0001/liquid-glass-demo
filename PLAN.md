```json
{
 "priority_order": [
  "T1-先做(用户睁眼第一眼就看得见的『错』)：P01 正六边形、P02 三角形小尺寸畸变、P11 卡片弧线AA收尾(顺带,同型修复)",
  "T2-每次交互都被感知的体验：P04 展开动画后半段掉帧、P05 控制中心四个文字无缝交棒(用户明确点名,与『下滑回胶囊』同级)",
  "T3-双卡家族(用户给的两个选项+新效果)：P06 多卡演示>=3(含『点击置顶』子行为)、P07 双卡二次折射、P08 最近距离提亮",
  "T4-功能完整性(点得到、看得见)：P09 更多设置不再被面板挡住、P10 官方图片编辑(ACTION_EDIT)+二级菜单独立入口",
  "T5-感知校准(风险最高,先测量后调参)：P03 折射正确性(Golden 基准比对 + 四角色散/直边基线 A/B)",
  "P00 调试接口补齐是 P01/P02/P06/P07/P08/P09 验收的前置设施，必须先做(约半天)"
 ],
 "plans": [
  {
   "id": "P00-INFRA",
   "requirement": "前置设施(非用户 12 条之一)：让 P01/P02/P06/P07/P08/P09 的验收可确定性执行",
   "approach": "① 新增 adb 命令 setUi(--es key --ei ivalue --ef fvalue)：白名单键 = glassSize / shape / shape2 / cards(2|3|4) / twoCardDemo / advanced / quality / bgBuiltinIndex / handoffLabel / freezeContentAlpha；写 uiState 必须在 Handler(Looper.getMainLooper()).post{} 里写(避免快照并发写)，随后 DebugBridge.revision++ 触发重组。② 新增 setPos(--ei card --ef x --ef y)：把第 N 块卡的 offsetX/offsetY 直接写成指定值，用于把两块玻璃摆到指定距离/重叠度做 A/B。③ 【必须修 dumpState 的几何行】现有实现把 panelBridge 建在 DisposableEffect(Unit) 里，其方法捕获了首次组合的 val(fullWidthPx/expandedHeightPx 等)，实测 geom(p) 恒为 w=464 h=1 fullWidth=464，而同一时刻 LGLayout 打的是 p=1.000 w=1776 h=1619(01:01 与 01:07 两个进程都复现)——按 NEXT.md『精确几何来源：dumpState 直接读，别再算』去做裁切会再次踩『裁错地方』的坑。改法：dumpState 内部重算几何(只调用读 rootSizePx 状态 wOf/hOf)或把 panelBridge 改成 remember{} 的对象。",
   "file_hints": "debug/DebugBridge.kt:82-84(命令白名单) / :107-122(execute) / :178-196(dumpState)；ui/LiquidGlassScreen.kt:755-811(panelBridge 与 dumpState 实现，冻结值来源) / :43-45(uiState 发布)；ui/GlassControlsPanel.kt:100-296(GlassUiState 全部字段=setUi 的写入目标)",
   "acceptance": "am broadcast -a com.liqglass.DEBUG --es cmd setUi --es key shape --ei ivalue 5 -p com.liqglass.ultraclear → logcat -s AIDebug 出现 setUi OK: shape=HEXAGON；setUi cards 3 后 dumpState 报 3 组卡片 rect；一致性硬门：setPanelP 1.0 后 dumpState 的 geom 行 w/h/fullWidth 必须与同一时刻 logcat -d -s LGLayout:* | tail -1 的 w/h 逐字段相等(今天实测矛盾：464/1 vs 1776/1619 ✗)",
   "risk_and_switch": "只影响 debug 构建(receiver 已按 FLAG_DEBUGGABLE 门控，release 不解析参数)；写 uiState 不在主线程会触发快照并发写异常 → 必须 post 主线程。回退：删白名单命令即可，不触碰任何光学/布局代码。"
  },
  {
   "id": "P01-HEX",
   "requirement": "需求2：六边形不是正六边形",
   "approach": "把两处顶点表改成『尖顶朝上、外接圆半径 R=min(halfW,halfH)』的正六边形：Compose 侧(局部比例) (0.5,0) (0.9330127,0.25) (0.9330127,0.75) (0.5,1) (0.0669873,0.75) (0.0669873,0.25)；Shader 侧 v0=(0,-R) v1=(0.8660254R,-0.5R) v2=(0.8660254R,0.5R) v3=(0,R) v4=(-0.8660254R,0.5R) v5=(-0.8660254R,-0.5R)，R 取 min(halfSize.x,halfSize.y) 保证非正方形卡也正。现状量化(density=2、glassSize=0.5、卡 920x920)：顶点半径 450.8/429.2(差 21.6px=4.8%)、边长 434.0/441.6(比 1.0176)、内角 116/122(应 120)；修完应为 460.0±2 全等、内角 120±1。同时把 HEXAGON 的 contentWidthFraction 0.68→0.60(正六边形上半部更窄，防内容越界)。只动这两处顶点表+一个比例，不碰折射/高光/模糊/色散。",
   "file_hints": "glass/GlassShape.kt:188-207(HexagonShape 顶点 0.50/0.90/0.10 与 0.01/0.26/0.74/0.99) / :74-83(aspectRatio=1f) / :85-95(contentWidthFraction)；glass/GlassShaders.kt:153-203(sdHexagonShape 的 v0..v5 与射线交叉符号段) / :320-326(六边形法线数值梯度 eps=1px)",
   "acceptance": "P00 的 setUi shape 5 + setPanelP 0(收起态看到整块卡) → adb exec-out screencap -p → Python 提取六顶点(近白+低饱和阈值：min(RGB)>0.80 且 max-min<0.10，彩色网格壁纸下已验证可用) → 断言六条边长极差<=2px、内角 120±1、外接圆半径极差<=2px；再对 6 个顶点各出 4x 裁剪图给用户眼验。取证目录 ~/Downloads/LG-shape-fix/",
   "risk_and_switch": "顶点比例变化会改变『边缘高光沿边界的走向』与可见面积 → 必须出旧/新并排图(python3 ~/.hermes/scripts/glass_baseline.py compare + 4x 图)；若用户觉得『变窄太多』可只改 x 比例保留 y。回退：还原两处顶点表(两文件各一段 diff)，无副作用。"
  },
  {
   "id": "P02-TRI",
   "requirement": "需求1：三角形玻璃调节过小产生畸变(用户推测高光导致)",
   "approach": "先做单变量归因(不许直接改)：固定 shape=TRIANGLE、彩色网格壁纸，分别在 glassSize 0.20/0.35/0.50 拍 FINAL 与 REFRACTION_ONLY(setDebugMode 2) —— REFRACTION_ONLY 就畸变 ⇒ 几何/折射位移问题；只有 FINAL 畸变 ⇒ 高光/光照段问题。已算出的失配证据(density=2)：refractionOffset 恒 64px，而 glassSize=0.2 时 shapeHalf=168px(位移=半宽 38%)；EDGE_ZONE=24dp*2.1=100.8px 占半宽 60%；refrHeight=edgeZonePx*0.48 也按 px 固定 ⇒ 整个小三角形都在『边缘带』里。修法：引入光学尺度因子 sScale=clamp(min(shapeHalf.x,shapeHalf.y)/REF_HALF,0,1)(REF_HALF=默认卡半宽≈306px ⇒ 默认尺寸 sScale=1，观感零变化)，乘到所有随尺寸失配的量：edgeZonePx、refractionOffset、blurRadius/edgeBlur、dispersion 偏移与带宽，以及 shader 内 hair/glow/bandW/refrHeight；三角形法线数值梯度 eps 从固定 1.0px 改成 max(1.0, 0.004*min(shapeHalf))(尖角噪声源)。用 DebugSwitches.sizeAdaptiveOptics(默认 true)一键回退，参数集中在 UltraClearGlassEffect.compute 与 Adapter 上传点。",
   "file_hints": "glass/UltraClearGlassEffect.kt:39-66(compute：edgeZonePx=24dp 固定、refractionHeightPx=max(2.5*refr, edgeBlur+16))；backdrop/BackdropAdapter.kt:283(EDGE_ZONE=24dp*2.1) / :297-301(blur/refraction uniform) / :253-261(各形状 cornerRadiusPx)；glass/GlassShaders.kt:521-535(coverage/edgeMask/sd) / :562-573(refrHeight=edgeZone*0.48) / :752-799(hair=1.5px、glow bandW=clamp(edgeZone*0.30,3,18)、lobe 指数 1.2) / :313-319(三角形法线 eps)；ui/LiquidGlassScreen.kt:445-458(卡片尺寸=glassSize*屏宽)",
   "acceptance": "① 回归硬门(最重要)：glassSize 回 0.35 + ROUNDED_RECT，与 ~/Downloads/LiquidGlass-baseline/devices/<serial>/ 比对应为 0.00%/0.00%(sScale=1 不失真)；② 三尺寸并排 4x 图：三角形顶点/边上不得出现糊团、彩边、破洞；③ 量化代理：边缘带内网格线间距比(玻璃内/屏外)<=1.6，且 REFRACTION_ONLY 与 FINAL 的差异区域面积占比 <8%(高光不再主导形状内部)；④ setSwitches sizeAdaptiveOptics 0|1 同状态两张图必须可见差异(证明开关在位)",
   "risk_and_switch": "改动的是『用户已认可的默认观感』相关量 → 用 REF_HALF=默认卡半宽保证默认 sScale=1，并把『0.35 逐像素 0.00%』写进验收硬门；回退：sizeAdaptiveOptics=false 全部回到现值。"
  },
  {
   "id": "P03-REFRACT",
   "requirement": "需求12：玻璃折射『正确』(用户感知；历史参考：直边基线 0.85、圆角 1.0、折射带 14dp、色散只在四角)",
   "approach": "先把『正确』变成可测基线，再谈调参。事实核查(今天读码)：① 卡片色散当前为 0 —— GlassParameters.mapping 里 dispersionOffsetDp=0f、dispersionStrength=0f(隔离实验遗留，注释自述『彻底关掉卡片色散』) ⇒ 用户的『色散只在四角』在当前代码里完全不存在；② shader 色散强度只跟 lensSlope 走(dispScale=0.25+1.75*lensSlope)，没有历史提交 455f465 的四角加权 (|x||y|)/(hx*hy)；③ 折射带历史值 14dp(ad8789d)已被替换为 refrHeight=edgeZonePx*0.48≈24dp；④ DebugSwitches.mirrorRefraction 声明了但全工程无人读取(死开关)，不能拿它做 A/B；⑤ Adapter 里 PANEL_RIM_DARKEN 被连续写两次(0f 后 0.85f，前者是死代码，可无损修正)。做法：新增两个布尔开关 goldenRefraction(历史 Golden 档：折射带 14dp + 直边权重 0.85/圆角 1.0 混合)与 cornerDispersion(四角加权色散 0.20 强度 × 8dp 带)，【默认关闭】，先只出 A/B 图，等用户拍板再定默认值。",
   "file_hints": "glass/GlassShaders.kt:642-682(色散段 dispScale/dispVec，无四角加权) / :732-840(光照 rim/饱和度/输出) / :533-573(折射带 refrHeight)；glass/GlassParameters.kt:110-142(mapping：色散被置 0) / :134-136(色散历史注释)；backdrop/BackdropAdapter.kt:300 与 :504-505(PANEL_RIM_DARKEN 双写) / :344(RIM_SOFT)；debug/DebugSwitches.kt:15(mirrorRefraction 死开关)",
   "acceptance": "固定彩色网格 + glassSize 0.35 + ROUNDED_RECT：对 4 个圆角与 4 条直边各出 3x 裁剪(卡片真实 rect 由 LGLayout/dumpState 给出) → glass_baseline.py compare(判据：直边带差异 <=1%、圆角带 <=3%) + 4 张 A/B 并排图交用户眼验；色散量化：setDebugMode 4(DISPERSION_ONLY) 图里四角 chromaDelta 峰值/直边中段峰值 >=2，且 FINAL 下直边不得出现可见彩边(RGB 分离像素占比 <0.5%)",
   "risk_and_switch": "这是唯一会改动『用户已认可观感』的项 → 新档默认关闭，任何默认值变更必须先有 A/B 图 + 用户口头确认；回退：goldenRefraction=false 与 cornerDispersion=false 两行。"
  },
  {
   "id": "P04-JANK",
   "requirement": "需求11：控制中心展开动画后半段不掉帧(流畅)",
   "approach": "按证据链三步走，每步单变量+开关，且先测再改：今天实测复现基线(模拟器 emulator-5554，一次点击展开)：gfxinfo 19 帧 / 50th 24ms / Janky 42%(8/19) / Slow issue draw commands 5 帧，HISTOGRAM 集中在 17ms、23-24ms、32ms、40-42ms、150ms 桶 ⇒ 与 NEXT.md『瓶颈在每帧提交绘制命令』一致。头号待验证假设：内容层 graphicsLayer{ alpha = contentAlphaOf(pNow()) } 在 p∈(0.42,1.0) 区间逐帧改 alpha ⇒ 整棵设置面板每帧重新合成/提交 ⇒ 正对应用户说的『后半段(80%→100%)最卡』(alpha 到 1.0 恰在 p=1)。步骤：① 新增 DebugSwitches.freezeContentAlpha(默认 false)：p>0.42 后 alpha 恒 1.0，可见性改由容器裁剪的『揭幕』承担 → 先量它能省多少(issueMed / Slow issue draw 计数)；② 揭幕式 2.0：修掉上次回滚的真因(GlassControlsPanel.kt:405-406 的 heightIn(max=470.dp) 让 advanced 页高度与面板高度不匹配 → 下半屏空白)，改成内容高度=面板高度、由外层裁剪+内部滚动承担，再打开已有 revealContentMeasure；③ 容器 clip 半径动画期量化到 1dp 档、面板玻璃层只在其 alpha>0 时组合(已有 gatePanelGlassByAlpha)以减少 outline 重建。",
   "file_hints": "ui/LiquidGlassScreen.kt:674(contentAlphaOf) / :1121-1151(内容门控 + 揭幕式 layout + 内容层 alpha) / :933-939(容器 clip 每帧变形状) / :640-661(hOf/contentHeightPxOf)；debug/DebugSwitches.kt:23-36(gatePanelGlassByAlpha / revealContentMeasure 与其回滚原因)；ui/GlassControlsPanel.kt:405-406(470dp 上限=回滚真因)；performance/PerformanceMonitor.kt:134-145(1 秒窗口口径，dumpFrameStats 读数含义)",
   "acceptance": "① 累计判据：dumpsys gfxinfo com.liqglass.ultraclear reset → 5 次『setPanelP 0 后 input tap 胶囊中心』(间隔 1.5s) → dumpsys gfxinfo：Slow issue draw commands 占比 <5%、99th <=25ms、HISTOGRAM 20~40ms 桶归零(平板另按 NEXT.md 目标：中位<=12ms)；② 逐帧判据：dumpFrameStats 在动画进行中读(注意 1s 窗口，命令要在点击后 ~0.5s 内发出，snapAge<300ms)，对比 avg/p95/issueMed；③ A/B：setSwitches freezeContentAlpha 0|1 同状态各跑 5 次；④ 观感：screenrecord 3s + ffmpeg 提帧，核 p∈(0.42,0.60) 段内容不是『啪地出现』",
   "risk_and_switch": "冻结内容 alpha 会改变内容出现的方式(可能露出硬边)；揭幕式上次已因 advanced 页失败一次 → 必须先修高度匹配再开开关。回退：freezeContentAlpha=false、revealContentMeasure=false(两个开关都已存在/将存在)。"
  },
  {
   "id": "P05-HANDOFF",
   "requirement": "需求10：控制中心四个文字从胶囊内无缝转移到面板左上角(用户：与『下滑回胶囊』同优先级)",
   "approach": "单一文字实例(『控制中心』)放屏幕级 Box，用 draw/layout 阶段读 p 做位置插值：起点=胶囊中心(公式 (W/2, H-navBar-49d)；1840x2944 d=2 navBar=64 → (920,2782)，与今日 LGLayout(w=464 h=124 l_100) 及 NEXT.md 实测点击区中心一致 ✓)；终点=面板左上角标题槽位(x=面板左边距16dp+Row padding4dp+文本基线，y=面板顶+22dp 把手+titleLarge 基线)；面板顶 = H - liftOf(p) - hOf(p)。位移/缩放(25sp→22sp 用 scale)全部在 graphicsLayer lambda 里读 p(组合期不订阅)，两端目标位置写进【非快照普通字段】(历史坑：panelCenterRoot/titleCenterRoot 因『布局期写快照状态』被当死代码删除，实现时必须用普通字段承载)。面板原有标题在 p<0.9 时隐藏，由同一个实例落位，避免两个『控制中心』同时出现。",
   "file_hints": "ui/LiquidGlassScreen.kt:1092-1111(胶囊内标签 Text + labelAlphaOf) / :675-677(labelAlphaOf) / :1210-1237(面板标题 Text 与已删的 onGloballyPositioned 注释) / :417-421(panelCenterRoot/titleCenterRoot 遗留说明) / :662-673(liftOf/topROf 等纯函数可复用)；历史：提交 40f92fa(回退点)",
   "acceptance": "adb shell screenrecord --time-limit 3 /sdcard/h.mp4(先 setPanelP 0，点击胶囊后录) → pull + ffmpeg -i h.mp4 -vf fps=30 frames/%03d.png → 逐帧核：① 任一帧只出现一份『控制中心』(无重影/闪烁)；② 文字中心轨迹单调(无回弹)，首帧与 (920,2782) 差 <=3px、末帧与面板标题槽位差 <=3px；③ 收起方向(点面板『← 关闭』或 setPanelP 0)同样连续。取证目录 ~/Downloads/LG-handoff/",
   "risk_and_switch": "会改变收起/展开态的文字位置(用户看过旧观感) → 字号/颜色/字体/阴影一律不变，只做位置连续；回退：DebugSwitches.handoffLabel=false 回到现行 labelAlphaOf 淡出路径。"
  },
  {
   "id": "P06-MULTICARD",
   "requirement": "需求5：把『双卡演示』改成『多卡演示』(>=3 块)；需求4：以当前点击的玻璃为最上层图层(做成多卡的子行为)",
   "approach": "把『两块卡』泛化成 cards: List<CardSpec>(id/shape/state/size/defaultTopLeft/offsetBounds，上限 4)，选中形状列表上限从 2 → 4(GlassUiState.toggleShape 的 >=2 判定)，默认布局改阶梯错位(index*6% 屏宽 / 8% 屏高)避免完全重叠；每块卡独立 GlassCardState + 复用现有 cardOffsetBounds(已按卡参数化)与避让/回弹规则。z 序用 zOrder: List<Int>，Box 内按 zOrder 顺序 emplace，被点中的卡移到末尾(最上层)；点击判定加进 LiquidGlassCard 的手势循环：结束时总位移 < touchSlop 且时长 <300ms ⇒ onTap(cardId)，不新增手势节点、不影响拖动。UI：开关文案改『多卡演示（3/4 块）』+ 数量 chip；『已达玻璃上限』文案同步。",
   "file_hints": "ui/GlassControlsPanel.kt:110-142(selectedShapes/twoCardDemo/toggleShape/上限) / :507-520(双卡开关+说明文案)；ui/LiquidGlassScreen.kt:153-156(两个 state) / :159-195(默认位置与 bounds) / :431-511(主卡与第二块卡组合) / :334-377(面板避让) / :614-662(卡片手势循环，加 tap 判定)",
   "acceptance": "setUi cards 3 → 截屏数出 3 块玻璃(每块都有描边高光+折射)；setPos 把 A、B 摆到重叠/相邻两种构型各截一张；点击置顶：input tap 打在 B 的可见区域 → 再截屏，断言『B 的描边/高光完整覆盖重叠区』(A 的边线在重叠区内被遮断，用边缘像素差 >150 的位置分布判定)；性能门：3 卡静止 dumpFrameStats 的 avg/gpuMed 相对 2 卡劣化 <=30%(今天 2 卡静止 totalMed 17.5ms/gpuMed 10.6ms 可作模拟器基线)",
   "risk_and_switch": "每多一块玻璃=多一次全屏 shader 采样+背景捕获，帧时间线性恶化(模拟器 2 卡展开期已 24ms)；点击置顶会改变层级观感。回退：DebugSwitches.multiCardDemo=false + 计数状态，完全保留今天的两卡路径(代码不动)。"
  },
  {
   "id": "P07-DUAL-REFRACT",
   "requirement": "需求3：双卡模式两块玻璃叠加不能很好地显示二次折射",
   "approach": "真相：今天两块卡都采同一个『只含背景』的捕获层(BackgroundScene.capture)，架构上不可能互相折射(README 已写『Backdrop 架构不允许两卡共享同一材质 Shader』)。路径 A(真二次采样，首选)：加第二捕获层 layerDual，结构 = Box(全屏){ BackgroundScene.capture(layerBG) + CardB } .capture(layerDual)，CardA 的 glass(...) 传 backdrop=layerDual.captureLayer(库已支持该参数) ⇒ A 采到『背景 + 已被 B 折射过的画面』= 真二次折射；B 仍采 layerBG(无环)；绘制顺序由同一 Box 内子节点顺序保证(layerDual 先录、A 后画)。路径 B(兜底/融合观感)：启用已在库里但从未被调用的 glassBridge(液态张力桥) + README『待实现：液滴张力融合』的 sminPoly(shape2+blendK) 让颈部属玻璃本体。两条各留独立开关。",
   "file_hints": "backdrop/BackdropAdapter.kt:141-159(adapter/captureLayer) / :183-238(glass 的 backdrop 参数) / :536-617(glassBridge 全文，现成未用)；库：backdrops/LayerBackdropModifier.kt:51-69(捕获=drawContent+recordLayer) / DrawBackdropModifier.kt:258-269(layoutLayerBlock:clip+offscreen) / :331-338(exportedBackdrop 录玻璃自身输出)；README.md『待实现：液滴张力融合』段(shape2+blendK+sminPoly 方案)；upstream-reference/(QWEA0/Liquid-Glass-Android)",
   "acceptance": "彩色网格 + setPos 让 B 部分压在 A 上 → 对 A 的折射带做 2x/4x 裁剪：① 真二次判据：B 的边缘高光/暗边应在 A 内出现『第二条弧线』，且随 B 移动而移动 —— setPos 两组 B 坐标各拍一张，A 带内差异像素占比 >=2% 且差异结构位置随 B 偏移(全 ~0% ⇒ A 没采到 B，失败)；② 回归：单卡/无重叠时与旧基准逐像素 0.00%；③ 性能：dumpFrameStats gpuMed 相对单卡 <=2 倍",
   "risk_and_switch": "同型历史事故(捕获层 dirty 门控 → 采到旧内容『玻璃炸了』3f27575；面板自采样 → 循环采样闪退) ⇒ 硬约束：B 绝不采 layerDual(无环)、layerDual 必须在 A 之前录、采样层几何与现有全屏捕获一致；性能上多一个全屏 GraphicsLayer 录制。回退：DebugSwitches.dualCaptureRefraction=false → A 回 layerBG(一行)。"
  },
  {
   "id": "P08-NEARGLOW",
   "requirement": "需求6：两块玻璃靠近时，在两者最近距离处产生额外的提亮效果",
   "approach": "先算几何(两卡都是无旋转圆角矩形 ⇒ 矩形最近点对 = 把对方 rect clamp 进自身 rect)：gap=|P_A-P_B|；当 gap < 阈值 thresh(建议 64dp*d，可调) 时，在【卡片之下的父层】画一条 P_A→P_B 的提亮：线性渐变 + 两端径向辉光的胶囊，强度随 gap 平滑衰减 intensity=smoothstep(1-gap/thresh)，用 BlendMode.Plus 或暖白高 alpha 让它读作『能量传递』。不要用第三块 AGSL 玻璃(历史已证：用户实测判为『多出第三块玻璃』已回退 ✗)。4 个可调项(阈值/强度/宽度/颜色)进面板。与 P06 共用父层与卡片 rect ⇒ 串行改动。",
   "file_hints": "ui/LiquidGlassScreen.kt:379-385(根 Box) / :120-131(分层注释) / :474-511(卡片 rect/尺寸来源)；backdrop/BackdropAdapter.kt:536-617(glassBridge 可作提亮载体/对照)；ui/LiquidGlassCard.kt:471-483(卡片 offset 与尺寸)",
   "acceptance": "setPos 造 gap=200/60/20px 三档各截一张：Python 量最近点连线中点的 sRGB 均值(或 L*)随 gap 单调上升，20px 档比 200px 档 >= +6 灰阶；阈值外(gap=200px 或关闭开关)与『关闭态』逐像素 0.00%(零副作用硬门)；再小步移动 8 帧确认无闪烁/跳变(与 P05 同用 ffmpeg 提帧)",
   "risk_and_switch": "提亮可能被读成脏点/漏光 → 阈值与强度需用户眼验后再定默认；回退：nearGlow=false(一条开关，绘制层整段不进组合)。"
  },
  {
   "id": "P09-SETTINGS-VISIBLE",
   "requirement": "需求7：『更多设置』被控制面板整块挡住 → 调参时看不到实时效果",
   "approach": "已定位机制(今天读码)：卡片避让的 LaunchedEffect 只以 showControlCenter 为 key，且用的 panelTopPx 是固定 H*(1-0.55)=45% 屏高；而 p 推到 2 时面板高=98%*H、顶边升到 y≈59px ⇒ 卡片被完全埋掉，避让不再重算 ⇒ 正是『更多设置(advanced 页)一打开就看不见效果』。修法三件套：① 避让跟随 p：key 改成面板顶边(由 hOf(p) 派生的量化值，跨 1dp 才变)，卡片目标位按当前面板顶边算(沿用 isProgrammaticMove 规则，不与手指抢)；② 二级菜单『预览档』：进入 advanced 时把 p 收到 ~0.62H(面板顶≈38% 屏高)，卡片上移到可见区；面板顶部加『预览模式』chip 在 0.62/0.98 间一键切；③ 预览模式下 fillAlphaOf 由 0.90 降到 ~0.62(半透明)。三项都用现有 p/alpha 函数，不新增节点。",
   "file_hints": "ui/LiquidGlassScreen.kt:334-377(避让 LaunchedEffect 与固定 panelTopPx) / :227-229(panelHeightFraction / panelFullHeightFraction=0.98) / :682-696(glassAlphaOf/fillAlphaOf) / :655-661(hOf)；ui/GlassControlsPanel.kt:405-406(heightIn 470dp 上限) / :523-531(更多设置入口) / :532-605(advanced 页结构)",
   "acceptance": "setUi advanced true → 截屏：面板顶 y=H-lift-h(由 LGLayout 的 w/h/lift 与 root 算) 必须大于卡片底边(卡片 rect 由 setPos/dumpState 给出) ⇒ 断言 cardBottom <= panelTop-14dp；input swipe 拖动任一滑块时再截屏：非面板区域存在卡片高光描边特征像素(证明实时可见)；关掉『预览模式』对照截图应回到今天『卡片不可见』(证明开关有效)",
   "risk_and_switch": "面板变矮会让 advanced 列表更依赖滚动(与 470dp 上限同源) → 顺带把上限改成随面板高度(与 P04 的揭幕式同修更省事)；程序化上移与手动拖动冲突需沿用 isProgrammaticMove 规则。回退：previewMode=false 回到今天行为。"
  },
  {
   "id": "P10-IMAGE-EDIT",
   "requirement": "需求8：接入官方图片编辑工具(系统图片编辑器)；需求9：再开一个二级菜单把『图片编辑』单独列出来",
   "approach": "事实核查：① 今天的选择器仍是 ActivityResultContracts.GetContent(不是 NEXT.md 写的 PickVisualMedia) ⇒ 顺手换成官方 PickVisualMedia(ImageOnly)；② 工程里没有任何 ACTION_EDIT/FileProvider(manifest 只有 MainActivity 与 DebugBridgeReceiver) ⇒ 需新增 provider + res/xml/file_paths.xml；③ 官方通路就是 ACTION_EDIT：把图复制到 app 私有目录 → FileProvider.getUriForFile → Intent(ACTION_EDIT).setDataAndType(uri,image/*).addFlags(GRANT_READ|GRANT_WRITE) → 回来后重读同一 URI 覆盖并刷新背景；resolveActivity 为空则按钮置灰 + Toast『未安装系统图片编辑器』。实测两台设备都有 handler(模拟器：Google Photos editor intent.EditActivity + Google Markup；平板 MIUI：com.miui.gallery.editor.photo.app.ExternalPhotoEditor + com.miui.mediaeditor.PhotoShopApp)✓。④ SDK 核查：android-37 全量 6440 个类里不存在 EXTRA_PICK_IMAGES_EDITED / PICK_IMAGES_EDITED 常量 ⇒ 『照片选择器内编辑』不是公开 API，不要按它设计。⑤ 二级菜单新增 SectionTitle『图片』：选择图片 / 编辑当前图片 两个入口(从『背景内容』独立出来)。",
   "file_hints": "ui/GlassControlsPanel.kt:349-377(GetContent 选择器) / :496-503(自定义图片 chip) / :642-677(背景图片区：选择/更换/恢复默认 + 缩放/偏移滑块) / :609-641(二级菜单『背景内容』段，新入口放这里)；AndroidManifest.xml(需加 FileProvider)；ui/BackgroundImageStore.kt(私有副本/解码/持久化)；app/build.gradle.kts 与 gradle/libs.versions.toml(PickVisualMedia 依赖)",
   "acceptance": "① 预检：adb shell pm query-activities -a android.intent.action.EDIT -t image/* 有 >=1 handler；② 点『图片编辑』→ dumpsys activity activities | grep ResumedActivity 应变为编辑器(package 名) → BACK 回来后截图：背景图与编辑前逐像素不同、差异集中在下半屏图片区；③ 无编辑器场景：pm disable 掉编辑器后按钮置灰 + Toast 文案(截图留证)；④ 选图路径与旧版一致：同一文件走 PickVisualMedia 选入后背景渲染与 GetContent 路径 0.00% 差异",
   "risk_and_switch": "FileProvider 是新暴露面(只暴露 filesDir 下子目录，禁 expose 整个存储)；ACTION_EDIT 写回语义厂商不同(MIUI/AOSP/Google Photos 三态：写回原 URI / 必须 EXTRA_OUTPUT / 只读返回) ⇒ 必须实现『回来重读同一 URI，内容未变则提示未保存』并在平板上手验一次。回退：入口按钮隐藏(常量) + 保留 GetContent 老路径。"
  },
  {
   "id": "P11-CARD-AA",
   "requirement": "EXTRA(NEXT.md 待办，用户曾反馈)：卡片弧线是否也需要同一套边缘 AA",
   "approach": "今天读库得到机制(候选④已确认)：卡片可见边界的真正硬裁来源是库的 layoutLayerBlock = { clip=true; shape=shapeProvider.shape; compositingStrategy=Offscreen }，而卡片传的 shapeProvider 返回的是 Path 形状(G2/三角/六边/超椭圆) ⇒ 无 AA 的 Path 硬裁；面板之所以修好，是因为 panelEdgeAa=true 时把 shape 传成 RectangleShape + 容器裁剪外扩 1.5px(已 3x 视觉复核证实有效)。同法搬到卡片：新增 cardEdgeAa(默认 true)，两种实现任选：① shapeProvider 返回的 Path 外扩 1.5px(半径同步 +1.5px)；② 最省 = 不动 Compose 路径，把 shader 静止 SHAPE_INFLATE 从 (-8dp,-19dp) 改成 (-8dp+1.5px, -19dp+1.5px)，让 SDF 形状比裁剪路径小 1.5px，可见边界完全交给已有 coverage 羽化(coverage=clamp(0.5-sd/5,0,1) 就是 AA)。",
   "file_hints": "backdrop/BackdropAdapter.kt:518(卡片 shape=RectangleShape，注意真正生效的是 shapeProvider) / :228(shapeProvider 参数) / :335-340(静止 SHAPE_INFLATE -8dp/-19dp) / :497-507(玻璃层比卡大 8dp/19dp)；库 DrawBackdropModifier.kt:258-262(layoutLayerBlock clip=shapeProvider.shape)；glass/GlassShaders.kt:521-525(coverage 羽化与早退) / :854-862(alpha×coverage 输出)；debug/DebugSwitches.kt:38-59(panelEdgeAa 机制说明可直接复用)",
   "acceptance": "卡片弧线 4x 裁剪 A/B(同一状态、只切 cardEdgeAa)：整屏差异应呈『细边带』形态(最大差 >150、变化像素占比 0.02%~0.2%)——这正是 panelEdgeAa 的通过判据(当时整屏 0.01%、最大差 203 ✓)；再 3x 视觉复核『阶梯消失』+ glass_baseline.py compare 防回归",
   "risk_and_switch": "外扩 1.5px 会让卡片内容/ripple 最多外溢 1.5px(≈0.5dp，面板已论证肉眼不可见)；走实现②则完全不改几何、只把可见边界内缩 1.5px。回退：cardEdgeAa=false。"
  }
 ],
 "dependencies": "【必须先做】P00-INFRA 是所有验收的前置(否则 setUi/setPos 不可用，且 dumpState 几何行会给出错误裁切坐标——今天实测它报 w=464 h=1 而 LGLayout 同刻报 1776/1619)。\n【串行·同文件同状态机】P06 多卡+z 序 → P07 二次折射 → P08 近距离提亮：三项都在 ui/LiquidGlassScreen.kt 的卡片层与卡片状态上，且 P07 依赖 P06 的多卡结构(若时间紧，P07/P08 可先在双卡上做，但代码结构要按多卡写以免二次返工)。\n【串行·同 shader 段】P01(顶点表) / P02(sScale) / P03(折射档) 都会改 glass/GlassShaders.kt 的折射/光照段 → 必须一个补丁一个 A/B 地串行，且 P03 的历史档参数必须也乘 P02 的 sScale(否则小尺寸下历史档又畸变)。\n【可并行编辑但必须串行上机】P04(内容层/容器 clip) 与 P05(标签层) 在同一文件不同区域；建议先定 P04 的动画曲线再定 P05 的文字轨迹，否则文字位置与动画时长互相返工。\n【独立可并行】P10(图片编辑) 只碰 GlassControlsPanel/Manifest/Store；P11(卡片 AA) 只碰 Adapter 与一个开关。\n【构建纪律】一次只能有一个构建产物 ⇒ 所有项共享同一条『补丁→回读→assembleDebug + compileDebugKotlin --rerun-tasks→装机→取证』流水线；每项必须带独立开关，验证不过立刻关开关回退，不许把未验证的默认值留在主线。\n【会改变观感、必须单独出 A/B 图(用户眼验)的项】P01、P02、P03、P04、P05、P07、P08、P11 共 8 项，建议攒成 4 组并排图一次性给用户拍板(六边+三角 / 折射档 / 动画+文字 / 双卡家族)。",
 "hard_problems": [
  {
   "problem": "P04 展开动画后半段的『Slow issue draw commands』真因可能不是内容 alpha（它只是最强嫌疑：逐帧改 alpha 会让整棵设置面板每帧重新合成）。若改完仍不掉帧，瓶颈可能落在库的每帧 recordLayer(捕获层)、面板超大面积 shader 采样、面板容器每帧改 clip 三处之一。",
   "fallbacks": [
    "单变量逐个关(开关先全做出来，每关一个跑一次 5-tap gfxinfo)：freezeContentAlpha / 冻结容器 clip 档位 / 摘掉面板玻璃层(已有 gatePanelGlassByAlpha) / 面板采样档位 13→9→5 taps 各测一次 —— 用 Slow issue draw 计数 + issueMed 定位到具体某一层",
    "换官方归因工具：接 JankStats(NEXT.md 待办 #6 已列)，或用 gfxinfo framestats 导出逐帧 COMMAND_ISSUE_DURATION，把『哪一段 p 区间、哪一帧』量化，停止靠猜",
    "平台侧换实现：动画期间冻结玻璃那一帧(静态 RenderEffect 缓存)只驱动形状，动画结束再重新采样背景",
    "把展开动画拆两段(形状先到位 → 内容再淡入)压低单帧峰值；观感必须用户认可后才落",
    "兜底降幅：panelFullHeightFraction 0.98 → 0.85(面板更小=每帧绘制命令更少)，先把『卡』压到用户看不出，再谈彻底优化"
   ]
  },
  {
   "problem": "P07 双卡真二次折射可能撞上游架构限制：Backdrop 每帧只录一次捕获层、采样层与玻璃必须同帧有序、历史上『玻璃采样到旧内容』与『循环采样闪退』各炸过一次。",
   "fallbacks": [
    "改用库自带 exportedBackdrop(DrawBackdropModifier.kt:331-338 已实现『把玻璃自身输出录进一个 LayerBackdrop』)：让 A 采样 B 的 exported layer，再在 A 的 shader 内按 B 的 SDF 区域把『背景采样』与『B 的玻璃输出』混合 ⇒ 纯 shader 合成，无递归风险",
    "改用库自带 effects/Lens.kt(已存在但未接)，把二次折射做成第二级 Lens/RenderEffect 而不是第二个采样层",
    "Skia 侧链式叠加：RenderEffect.createRuntimeShaderEffect 支持多级串联，把两层玻璃做成两级 RenderEffect(改动集中在 Adapter 的 effects 块)",
    "降级为『视觉近似』：启用现成但从未被调用的 glassBridge(BackdropAdapter.kt:547-617) + README 的 sminPoly(shape2+blendK) 让颈部属玻璃本体，并在 UI/README 明确标注『近似，非真两遍采样』，请用户拍板",
    "外部检索(按优先级)：① kyant0/backdrop 仓库的示例与 issues(是否有多玻璃互采样示例) ② upstream-reference/ 里 QWEA0/Liquid-Glass-Android 的 sminPoly 实现 ③ GitHub 搜 liquid glass two layers refraction AGSL / double refraction shader two glass ④ 百度/知乎搜 AGSL 玻璃 二次折射 两个采样 —— 注意本项目已有结论：不要用第三个玻璃元素硬凑(用户实测否定过)"
   ]
  },
  {
   "problem": "P06 多卡 >=3 的性能墙 + P08 提亮『看起来不对劲』：每多一块玻璃就多一次全屏采样与背景捕获；模拟器今天 2 卡静止 totalMed 17.5ms、展开期 50th 24ms，平板(1880x3008@450dpi)面积更大、余量更小。",
   "fallbacks": [
    "分级降档：第 3/4 块玻璃强制走 PERFORMANCE(5 taps) + 关闭该块色散与边缘模糊，焦点卡(被点击置顶的那块)保持完整档",
    "静止免刷：非焦点卡在两两距离不变且无动画时跳过每帧重绘(已有先例：GlassCardTimeDriver 只在交互期推进 time uniform)",
    "P08 提亮只在两卡都静止后计算一次(移动期间不更新)，把最近点/SDF 计算从每帧降到一次性",
    "上限策略化：3 块默认、4 块标注『实验』，并在右上角看板显示『玻璃数 × 每帧成本』让用户看见代价",
    "外部检索：GitHub 搜 Liquid Glass Android / AGSL multi backdrop performance；备选思路是用离屏位图缓存『背景+已完成的玻璃』，把 N 块玻璃从 N 次重采样降为 1 次链式合成(需验证 AGSL 是否允许链式 Canvas 输入)"
   ]
  }
 ],
 "acceptance_script": "#!/usr/bin/env bash\n# LiquidGlassDemo 醒来一口气验完（只读 adb 调试命令；不改仓库文件、不 commit）\n# 用法: LG_SERIAL=\"emulator-5554\" bash lg_acceptance.sh\nset -u\nADB=$HOME/Library/Android/sdk/platform-tools/adb\nPKG=com.liqglass.ultraclear\nSER=${LG_SERIAL:-${1:-emulator-5554}}\nOUT=$HOME/Downloads/LG-accept-$(date +%m%d-%H%M); mkdir -p \"$OUT\"\nA(){ \"$ADB\" -s \"$SER\" \"$@\"; }\nBC(){ A shell am broadcast -a com.liqglass.DEBUG \"$@\" -p $PKG >/dev/null; }\nshot(){ A exec-out screencap -p > \"$OUT/$1.png\"; echo \"  -> $OUT/$1.png\"; }\n\n# ---------- 0. 预检 ----------\necho \"[0] 设备与版本\"; A get-state || exit 1\nA shell dumpsys package $PKG | grep -E \"versionName|lastUpdateTime\" | head -2\nA shell am start -n $PKG/com.example.liquidglass.MainActivity >/dev/null; sleep 3\nPID0=$(A shell pidof $PKG | tr -d '\\r')\nBC --es cmd dumpState; sleep 1\nSESS=$(A logcat -d -s AIDebug:* | grep -o 'session    = .*' | tail -1 | awk '{print $3}')\necho \"  pid=$PID0 session=$SESS  （任何一步 pid 变了 → 该组数据作废重跑：今天已观察到外部 force-stop 重启）\"\nA logcat -d -s AIDebug:* | grep -E \"switches|debugMode|alpha\\(p\\)|gates\" | tail -4\n\n# 胶囊中心：(W/2, H - navBar - 49*d)，由 LGLayout 的 root/navBar/dens 反推\nread W H NB D < <(A logcat -d -s LGLayout:* | tail -1 | sed -n 's/.*root=\\([0-9]*\\)x\\([0-9]*\\) navBar=\\([0-9]*\\) dens=\\([0-9.]*\\).*/\\1 \\2 \\3 \\4/p')\nCAPX=$((W/2)); CAPY=$(python3 -c \"print(int($H-$NB-49*$D))\")\necho \"[0] 胶囊中心=($CAPX,$CAPY)   [1840x2944/320dpi 应得 (920,2782)]\"\n\n# ---------- 1. P00 调试接口 ----------\necho \"[1] P00 setUi/setPos + dumpState 几何修复\"\nBC --es cmd setUi --es key shape --ei ivalue 5; sleep 0.5\nA logcat -d -s AIDebug:* | grep -E \"setUi OK|ERROR|UNKNOWN\" | tail -2\nBC --es cmd setPanelP --ef value 1.0; sleep 1.2; BC --es cmd dumpState; sleep 1\nA logcat -d -s AIDebug:* | grep \"geom(p)\" | tail -1     # 期望 w/h 与下一行 LGLayout 相等\nA logcat -d -s LGLayout:* | tail -1\n\n# ---------- 2. P01 正六边形 ----------\necho \"[2] P01 六边形规整性\"\nBC --es cmd setUi --es key shape --ei ivalue 5; BC --es cmd setUi --es key glassSize --ef fvalue 0.5\nBC --es cmd setPanelP --ef value 0; sleep 1.5; shot p01_hex\nA logcat -d -s LGLayout:* | tail -1     # 收起态 w=464 h=124 l_100 → 卡 rect 可推\n\n# ---------- 3. P02 三角形小尺寸 ----------\necho \"[3] P02 三角形 0.20/0.35/0.50（回归硬门最重要）\"\nfor s in 0.20 0.35 0.50; do BC --es cmd setUi --es key shape --ei ivalue 4; BC --es cmd setUi --es key glassSize --ef fvalue $s; sleep 1.2; shot p02_tri_$s; done\nBC --es cmd setDebugMode --ei value 2; sleep 1; shot p02_tri_020_refr_only\nBC --es cmd setDebugMode --ei value 0\nBC --es cmd setUi --es key shape --ei ivalue 0; BC --es cmd setUi --es key glassSize --ef fvalue 0.35; sleep 1.5; shot p02_regression_035\nLG_SERIAL=\"$SER\" python3 $HOME/.hermes/scripts/glass_baseline.py compare     # 硬门：0.00%/0.00%\n\n# ---------- 4. P03 折射正确性 A/B ----------\necho \"[4] P03 折射档 A/B\"\nBC --es cmd setUi --es key bgBuiltinIndex --ei ivalue 5; sleep 2      # 彩色网格\nfor sw in goldenRefraction cornerDispersion; do\n  for v in 0 1; do BC --es cmd setSwitches --es name $sw --ei value $v; sleep 1; shot p03_${sw}_$v; done\ndone\nBC --es cmd setDebugMode --ei value 4; sleep 1; shot p03_dispersion_only; BC --es cmd setDebugMode --ei value 0\n\n# ---------- 5. P04 展开动画掉帧 ----------\necho \"[5] P04 展开动画（5 次点击累计）\"\nfor i in 1 2 3 4 5; do\n  BC --es cmd setPanelP --ef value 0; sleep 1.2\n  A shell dumpsys gfxinfo $PKG reset >/dev/null\n  A shell input tap $CAPX $CAPY; sleep 2.0\n  A shell dumpsys gfxinfo $PKG | grep -E \"Slow issue|Janky frames|50th percentile|99th percentile\"\ndone\nBC --es cmd setPanelP --ef value 0; sleep 1.2; A shell dumpsys gfxinfo $PKG reset >/dev/null\nA shell input tap $CAPX $CAPY; sleep 0.6; BC --es cmd dumpFrameStats; sleep 1\nA logcat -d -s AIDebug:* | grep \"frames  \" | tail -1      # 期望 avg/p95/issueMed 优于基线（今天 50th=24ms、Slow issue 5/19 帧）\n\n# ---------- 6. P05 文字交棒 ----------\necho \"[6] P05 四文字无缝交棒（录屏→提帧人工核）\"\nBC --es cmd setPanelP --ef value 0; sleep 1.2\nA shell screenrecord --time-limit 3 /sdcard/h.mp4 & A shell input tap $CAPX $CAPY; sleep 4\nA pull /sdcard/h.mp4 \"$OUT/handoff.mp4\" >/dev/null; mkdir -p \"$OUT/handoff_frames\"\nffmpeg -loglevel error -y -i \"$OUT/handoff.mp4\" -vf fps=30 \"$OUT/handoff_frames/%03d.png\"\necho \"  判据：任一帧只有一份『控制中心』；中心轨迹单调；首帧≈($CAPX,$CAPY)、末帧=面板标题槽位；帧目录 $OUT/handoff_frames\"\n\n# ---------- 7. P06/P07/P08 双卡家族 ----------\necho \"[7] P06 多卡 / P07 二次折射 / P08 最近点提亮\"\nBC --es cmd setUi --es key cards --ei ivalue 3; sleep 2; shot p06_cards3\nBC --es cmd setPos --ei card 1 --ef x -260 --ef y 120; sleep 1.5; shot p07_overlap_A\nBC --es cmd setPos --ei card 1 --ef x -120 --ef y 40;  sleep 1.5; shot p07_overlap_B\nBC --es cmd setPos --ei card 1 --ef x -420 --ef y 320; sleep 1.5; shot p08_gap_far\nBC --es cmd setPos --ei card 1 --ef x -60  --ef y 30;  sleep 1.5; shot p08_gap_near\npython3 - <<'PY' \"$OUT\"\nimport sys, numpy as np\nfrom PIL import Image\no=sys.argv[1]\ndef mid(p):\n    a=np.asarray(Image.open(p).convert('L')).astype(float); h,w=a.shape\n    return a[int(h*0.30):int(h*0.45), int(w*0.25):int(w*0.75)].mean()\nf,n=mid(o+'/p08_gap_far.png'), mid(o+'/p08_gap_near.png')\nprint('  gap_far L=%.1f  gap_near L=%.1f  判据：near-far >= +6 灰阶'%(f,n))\nPY\nBC --es cmd setSwitches --es name multiCardDemo --ei value 0\nBC --es cmd setSwitches --es name dualCaptureRefraction --ei value 0\nBC --es cmd setSwitches --es name nearGlow --ei value 0\n\n# ---------- 8. P09 更多设置不被挡 ----------\necho \"[8] P09 调参时能看到卡片\"\nBC --es cmd setUi --es key advanced --ei ivalue 1; sleep 1.5; shot p09_advanced_preview\nA logcat -d -s LGLayout:* | tail -1   # 面板顶 y = H - lift - h；断言 cardBottom <= panelTop-14dp\n\n# ---------- 9. P10 官方图片编辑 ----------\necho \"[9] P10 官方图片编辑器\"\nA shell pm query-activities -a android.intent.action.EDIT -t 'image/*' | grep -c \"Activity #\"   # 期望 >=1\n# 手点『图片编辑』入口后：\nA shell dumpsys activity activities | grep ResumedActivity | head -1   # 应显示编辑器；BACK 回来背景图应已变\n\n# ---------- 10. P11 卡片弧线 AA ----------\necho \"[10] P11 cardEdgeAa A/B\"\nBC --es cmd setSwitches --es name cardEdgeAa --ei value 1; sleep 1; shot p11_cardaa_on\nBC --es cmd setSwitches --es name cardEdgeAa --ei value 0; sleep 1; shot p11_cardaa_off\npython3 - <<'PY' \"$OUT\"\nimport sys, numpy as np\nfrom PIL import Image\no=sys.argv[1]\na=np.asarray(Image.open(o+'/p11_cardaa_on.png').convert('RGB')).astype(int)\nb=np.asarray(Image.open(o+'/p11_cardaa_off.png').convert('RGB')).astype(int)\nd=np.abs(a-b).max(2)\nprint('  判据：最大差>150、变化像素占比 0.02%%~0.2%%（同 panelEdgeAa 通过时的形态）；实测 max=%d 占比=%.3f%%'%(d.max(),(d>8).mean()*100))\nPY\n\n# ---------- 11. 收尾与复位 ----------\necho \"[11] 复位并交证据\"\nBC --es cmd setUi --es key cards --ei ivalue 2; BC --es cmd setUi --es key shape --ei ivalue 0\nBC --es cmd setUi --es key glassSize --ef fvalue 0.35; BC --es cmd setPanelP --ef value 0; BC --es cmd dumpState; sleep 1\necho \"证据目录：$OUT（PID 变化=数据作废；平板另跑一次并重抓基准：LG_SERIAL=<平板> python3 ~/.hermes/scripts/glass_baseline.py baseline）\"\necho \"不可自动化的项：手感/拖动（adb input swipe 触发不了真拖动）、『像不像玻璃』的主观判断 —— 必须用户手指与眼验\"\n"
}
```