package com.example.liquidglass.debug

/**
 * 调试开关（用户要求：新效果先在 shader 里加开关，可一键切回，不再做不可逆实验）。
 * mirrorRefraction: true = 真·镜像折射（沿边界压缩倒映内侧内容）；false = 直线位移（当前基线）
 *
 * 【AI 调试接口·契约】下面每个【Boolean 字段】都可以在设备上用一条 adb 命令直接改写
 * （debug/DebugBridge.kt 的 setSwitches 用反射枚举全部 Boolean 字段，新增开关无需改桥）：
 *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
 *       --es name panelEdgeAa --ei value 0 -p com.liqglass.ultraclear
 * 约定：开关保持为 object 的顶层 var（@Volatile、非 private）；字段名 = 命令里的 name。
 * 改完桥会递增 DebugBridge.revision → 触发一次重组，让改动立即生效。
 */
object DebugSwitches {
    @Volatile var mirrorRefraction: Boolean = true   // 用户选 A：默认开启真·镜像折射

    /**
     * 【P07 双卡二次折射】上层玻璃采样【下层玻璃已渲染的结果】，而不是只采同一个背景捕获层。
     *
     * 现状（false 的语义 = 改动前行为）：两块卡都从同一个"只含背景"的捕获层采样 ⇒
     *   重叠区里上层玻璃只是"把原始背景再折射一遍"，看不见"透过上卡看下卡"的二次折射 ✗。
     *
     * true（默认，新）= 两阶段真二次采样（无环）：
     *   ① 下层卡（主卡，兄弟序在前 ⇒ 先绘制）把【自己那一层玻璃的输出】录进离屏层
     *      （库自带 exportedBackdrop 通路：recordLayer 录 onDrawBehind + 玻璃层 + surface + front）；
     *   ② 上层卡（第二块卡）的采样源改成 CombinedBackdrop(背景捕获层, 上面的离屏层) ⇒
     *      它的 shader 输入 = 背景 + 已被下层玻璃折射过的画面 = 真二次折射 ✓。
     *   几何/顺序硬约束：上层卡绝不进下层卡的采样源（无环）；离屏层每帧在下层卡绘制时重录，
     *   兄弟顺序保证"先录后采"；上层卡的 effects 里额外读一次下层卡的位置（extraObservedReads）
     *   ⇒ 下层卡移动时上层卡同帧重录（防"采到旧内容"的历史事故）。
     *
     * 代价：多一层全屏 GraphicsLayer 录制 + 下层玻璃的那次绘制会被再合成一次（帧成本见取证数字）。
     * 回退：`setSwitches dualCaptureRefraction 0` → 上层卡回背景捕获层（一行，逐像素回到改动前 ✓）。
     * 单卡模式（twoCardDemo=false / 无第二形状）一律走原路径 ⇒ 单卡逐像素 0 回归 ✓。
     *
     * ================= 【2026-09-17 · 现状口径校正：本档默认 = true（用户亲定的二次折射）】=================
     * 现状（以代码为准 ✓）：`dualCaptureRefraction = true`（默认开）—— 用户亲定的默认表现就是本档
     *   【二次折射】；下面 2026-09-14 的「降为实验档、默认 false」【未落地】（代码从未改过：
     *   `= true` 自 P07 引入提交 2e9c299 起延续至今）⇒ 旧口径作废，只保留官方事实作背景。
     * 官方事实（用户已核实 ✓；解释「为什么还保留 meld 这一档」）：
     *   · WWDC25 S219/S310 + 官方 API 文档：iOS26 多块玻璃靠近的真实行为是【融合 meld】——
     *     靠到阈值即 fluidly join：形状 meld 成一块【连续玻璃】、**共享采样区**、一起渲染；离开即 fluidly separate；
     *     阈值由容器的 `GlassEffectContainer` / `NSGlassEffectContainerView.spacing` 控制
     *     （官方原文 “The glass shapes meld together based on their proximity”）。
     *   · 官方【明确禁止 glass on glass】：“Always avoid glass on glass”（玻璃无法正确采样另一块玻璃）
     *     ⇒ iOS 里根本不存在「两层玻璃叠加二次折射」这种表现 ✗；用户也反馈本档『形变太过了』✗。
     * ⇒ 【融合 meld】保留为**实验档**（见 DualCardMeld / dualCardMeld 开关：默认关，
     *   需 files/lg_meld_on / setSwitches / 面板开关 一行打开），不接管默认表现。
     *
     * 回退 / 对照（都是一行）：
     *   · 关掉本档（关掉后可看 meld 实验档接管）：
     *     adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *         --es name dualCaptureRefraction --ei value 0 -p com.liqglass.ultraclear
     *   · 打开 meld 实验档（拍「融合」对照）：
     *     adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *         --es name dualCardMeld --ei value 1 -p com.liqglass.ultraclear
     * （提示：本档默认开 + meld 默认关 ⇒ 默认档的重叠/靠近区就是【纯二次折射】；两档同时打开时，
     *   重叠/靠近区归 meld 管辖。）
     */
    @Volatile var dualCaptureRefraction: Boolean = true

    /**
     * 【P32 · 「双卡重叠时静止那块玻璃的折射卡住/不更新」修复开关】（默认开；一行回退）
     *
     * 实测（emulator-5554 + 逐帧探针，真实 input swipe 拖动两块玻璃）：
     *   拖动中段【静止的那块卡】的 effects 重跑次数掉到 0（连续 ~1.3s 零失效）⇒
     *   它的玻璃层 + 离屏录制层整段不重录 ⇒ 采样内容（对方玻璃的折射）冻结 = 用户报的"卡住" ✗
     * 根因：跨卡失效订阅是【单向】的 —— 只有「下卡动 ⇒ 上卡重录」（上层卡的 extraObservedReads）；
     *   反向「上卡动 ⇒ 下卡（含其离屏录制层）重录」缺失 ✗；而唯一还能同时覆盖两块卡的通道是
     *   P12 的 fusionUniforms frame.value —— 它一旦收敛（两卡重叠 ⇒ 融合权重归零 ⇒ 帧值恒等
     *   ⇒ 结构相等不再通知）就整段静默 ⇒ 静止卡彻底停更 ✗✗
     * true（默认）= 两块卡【互相】订阅对方的拖动位置（成对订阅）⇒ 任一块移动，两块卡同帧重录 ✓
     * false（一行回退）= 改前行为（只有单向订阅）
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name dualPairObserve --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var dualPairObserve: Boolean = true

    /**
     * 【P37 · 三块及以上玻璃的折射不是实时渲染 —— 多卡逐层采样链 + 全对全跨卡订阅】（默认开；一行回退）
     *
     * 用户反馈（原话）：『两块以上的玻璃折射依然不是实时渲染，区分好每块玻璃的图层，两块以上的玻璃
     * 移动的时候就露馅了』。
     *
     * 实测缺口（emulator-5554 + 逐帧探针，真实 input swipe 拖动，逐段表见交付报告）：
     *   · 图层侧：库里只有【一块】离屏层被录制（`adapter.dualCaptureLayer`，卡 0 的输出）⇒
     *     第 3/4 块卡既没有自己的离屏层、也没把它算进采样源 ⇒ 它们的"采样链"里完全没有
     *     其它玻璃（每块卡都只看到 背景 + 面板）⇒ 多块玻璃重叠时看不到逐层折射 ✗；
     *   · 订阅侧：跨卡失效订阅只接了 卡 0 ↔ 卡 1 这一对（P32 的 extraObservedReads + 绘制期直连
     *     crossObserveOther 两处都是硬编码的 secondCardState/mainCardState）⇒
     *     拖动第 3/4 块时，卡 0/1/2 的 effects 计数【全段为 0】（改前基线：拖卡 2 时 卡0=0/卡1=0，
     *     逐段 5 段全是 0）⇒ 静止的玻璃整块不重录 = 用户说的"一移动就露馅" ✗✗
     *
     * true（默认）= 两块以上时改为【按 z 序自下而上的逐层采样链 + 全对全订阅】：
     *   ① 每块卡各有一块离屏层（BackdropAdapter.cardCaptureLayers[卡索引]），把自己【已渲染结果】
     *      录进去（库自带 exportedBackdrop 通路，公共 API 未改）；
     *   ② 每块卡的采样源 = 背景 +（面板，按 cardSampleWithPanel）+ 所有【z 序在它之下】的卡的层
     *      ⇒ 上层玻璃看到的是"已被下层每一块玻璃折射过的画面"（P07 的多卡推广）；
     *   ③ 跨卡失效订阅扩到【全对全】：每一块卡都订阅其它所有卡的拖动位置（effects 内
     *      extraObservedReads + 绘制期直连 crossObserveOther 两条通道，仍是 P32 验证过的那两条）
     *      ⇒ 任意一块移动 ⇒ 其余每块的玻璃层与离屏层当帧重录 ✓；
     *   ④ 点击置顶（z 序变化）时【按新 z 序动态重算配对】（谁采谁随 zIndex 每帧重算）。
     * false（一行回退）= 完全回到改动前的接线：卡 0 ↔ 卡 1 的 P07 单层二次折射 + 成对订阅，
     *   卡 2/3 不录不采不订阅（= 本次修复前的实测行为）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name multiCardRefraction --ei value 0 -p com.liqglass.ultraclear
     *
     * 与既有开关的关系：本开关是【多卡】的总开关；[dualCaptureRefraction]（P07 单层二次折射）
     * 关掉时本链也整体不生效（尊重"不要 glass on glass"的回退意图）；[dualPairObserve]（P32）
     * 只管 effects 内那条订阅通道的开关，两条通道一起生效才能保证拖动中段不静默。
     */
    @Volatile var multiCardRefraction: Boolean = true

    /**
     * 【P07 · 双卡融合 meld（官方 iOS26 口径）】两块玻璃靠近/重叠时，按【形状并集】渲染成
     * 一块【连续玻璃】+ **一次**折射/高光/边缘 —— 对应官方 `GlassEffectContainer.spacing` 的
     * 「The glass shapes meld together based on their proximity」。
     *
     * 为什么（官方 + 用户反馈，详见上面 dualCaptureRefraction 的校正说明）：
     *   · 官方明确禁止 glass on glass ⇒ 不存在「两块玻璃各自折射后再叠」的表现 ✗；
     *   · 用户反馈二次折射『形变太过了』✗ ⇒ 本档曾拟成为默认表现，但【未落地】：
     *     现状（2026-09-17 校正）= 二次折射默认开（用户亲定）、本档默认关 = 实验档。
     *
     * true = 两卡距离 < proximityThreshold 时（【实验档·非默认】：需 files/lg_meld_on /
     *   setSwitches / 面板「吸附融合 meld（实验 · 默认关）」一行打开）：
     *   ① 场上取【两个形状的并集】：smooth-min 并集 `smin(sdSelf, sdOther, k)`，k 由接近程度驱动
     *      （0 → k_max；接近度来自与 P12 同一套阈值 ProximityFusion.thresholdPx ⇒ 不是各算一套 ✓）；
     *   ② 过渡期额外并入一段【喉部】（两卡最近点之间的圆角矩形条，宽度 = 融合宽度，随接近度连续增长）
     *      ⇒ 融合宽度单调平滑、无「整条直边瞬间连通」的跳变 ✓；
     *   ③ 【归属划分】并集区每个像素只由【更近的那块卡】绘制 ⇒ 并集只画一次 = 一次折射、一条轮廓、
     *      一条高光 ⇒ 重叠/相邻处【无双边框、无双亮带】✓（P12 的颈部是两卡各画一次 ⇒ 会叠两次）。
     *
     * false（默认）= 完全不跑 meld：uniform 全 0、划分关闭 ⇒ 两块卡各自独立渲染，
     *   融合表现回到 P12 原口径（颈部/拉丝由 ProximityFusion 的 runtime 驱动）—— 这就是现状默认档。
     *   （打开本实验档 = 一行：）
     *   `adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name dualCardMeld --ei value 1 -p com.liqglass.ultraclear`
     *
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_meld_on`（内容 "1" = 开；
     *   无此文件 = 默认关 = 实验档）。
     */
    @Volatile var dualCardMeld: Boolean = readSwitchFile("lg_meld_on")

    /**
     * 【P06 多卡演示】≥3 块玻璃同屏 + 「点击置顶」（z 序）。
     *
     * true（默认，新）= 允许同屏 3~4 块玻璃（档位 = uiState.cardCount，2..4，面板可选）：
     *   · 每块卡各自一份 GlassCardState（可独立拖动），第 3/4 块用的是【追加】出来的状态，
     *     索引 0/1 的状态对象与 P07（双卡二次折射：0=离屏录制方、1=采样方）和 P12（邻近流体融合
     *     cardA/cardB 联合场）的引用一字不动 ⇒ 这两套机制的行为不变 ✓；
     *   · 「点击置顶」= 点哪块、哪块移到 z 序末尾（最上层）；实现用 Modifier.zIndex（只改绘制与命中
     *     顺序，不改布局/采样坐标 ⇒ 对 Backdrop 的坐标映射零影响）。默认 z 序 [0,1,2,3] 与改动前的
     *     兄弟绘制顺序逐位相同 ⇒ 默认（2 卡）状态逐像素零回归 ✓；
     *   · 唯一与既有机制的交叉点：当【第二块卡不再是 z 序最上层】时（用户点了主卡置顶），
     *     P07 的"下层录制 / 上层采样"配对不再成立（绘制顺序反了 ⇒ 会采到上一帧的离屏层）⇒
     *     此时自动停用 dualCaptureRefraction 的组合层路径（两块卡都回背景捕获层）。默认 z 序下
     *     该条件恒不成立 ⇒ P07 默认行为不变 ✓。
     * false（回退）= 一律按 ≤2 块卡渲染（= 改动前的双卡语义）、且不响应点击置顶（一行回退 ✓）。
     */
    @Volatile var multiCardDemo: Boolean = true

    /**
     * 面板玻璃层的组合门控（性能）：
     * true  = 仅当 glassAlphaOf(p) > 0.001 时才把【面板玻璃层】放进组合
     *         （alpha 恒为 0 的区间 p≥0.30 像素上完全不可见；摘出组合可省掉每帧重录重提的最大一层）；
     * false = 旧行为（玻璃层始终组合、仅由绘制期 alpha 控制可见性）—— 一行回退。
     */
    @Volatile var gatePanelGlassByAlpha: Boolean = true

    /**
     * 【揭幕式内容测量】面板内容子树的测量尺寸是否恒定为【终态尺寸】(性能)：
     * true  = 内容按恒定终态尺寸布局（开合阶段本来就是终态尺寸；上滑展开阶段不再随面板长高）→
     *         子树 constraints 恒定 ⇒ Compose 跳过每帧重测（MeasurePassDelegate：constraints
     *         相等且无 pending 即不重测）→ 展开期每帧只改外层裁剪（"揭开"），观感逐帧等价 ✓
     * false = 旧行为（内容高度随 p 逐帧增长 → 展开期每帧重测/重排整棵内容子树）—— 一行回退。
     */
    @Volatile var revealContentMeasure: Boolean = false
    // ⚠️【已确认关闭·勿打开】实测打开后「更多设置」页只显示上半屏、下半屏空白 ✗
    //   （内容测量尺寸固定为终态后，advanced 页更高的列表与面板高度不再匹配 → 放置尺寸算不到一起）
    //   收益只是毫秒级（审计定论），代价是可见的显示缺陷 → 保持 false。
    //   若要重做，必须先解决 advanced 页（GlassControlsPanel advanced=true）的列表高度与面板高度对齐。
    //   【H3·优先级】本档仅当 contentSlotAdaptivePage=false ∧ contentFixedSlot=false 时才【可达】
    //   （此前两分支把它吞掉：默认档下翻它零效果 = 死分支）。

    /**
     * 面板/胶囊（控制中心）边缘的【抗锯齿】开关 —— 一键回退。
     *
     * 实测（真机 3~4× 放大）：直边平滑 ✓、**圆角弧线上有 1~2px 阶梯 ✗**（右下弧最明显）→
     * 典型的「Path 裁剪不做 AA」形态（直线与像素栅格对齐看不出来，弧线暴露）。
     * 可见的硬边来自【内容层的路径裁剪】，不是玻璃 SDF（玻璃自身的 5px coverage 羽化是有 AA 的）。
     *
     * true（新，默认）= 让硬裁剪退到玻璃 AA 边之外 + 把可见边界交给"会 AA 的机制"：
     *   ① 容器裁剪形状【外扩 1.5px】（半径同步 +1.5px）→ 那条无 AA 的硬边落到玻璃 SDF 羽化带
     *      之外（那里 coverage≈0.2，肉眼不可见）→ 弧线上看到的就是玻璃的 AA 边 ✓；
     *      外扩量 1.5px 远小于玻璃层相对内容的余量（本项目历史事实：玻璃层比卡片大 8dp/19dp；
     *      面板玻璃层的过采样 pad ≈18.5px）→ 内容不会在直边露出 ✓；
     *   ② 填充层（原来是矩形，自身没有 AA）改成按【同一个 G2 形状绘制】（Skia 路径绘制自带 AA ✓）；
     *      几何与旧版容器裁剪逐像素一致，只是边界改由绘制承担 = 抗锯齿 ✓；
     *   ③ 面板玻璃层那一层多余的 Path 裁剪去掉（形状改矩形）→ 玻璃边界完全由 Shader 的 SDF
     *      覆盖率羽化决定（与演示卡片同机制；卡片在 f72f5fe 就是这么修的）✓。
     * false（旧）= 容器用 G2 Path 硬裁剪（无 AA）+ 填充层矩形 + 玻璃层再叠一层 Path 裁剪。
     *
     * 副作用自查：外扩 1.5px 使"被裁剪的 ripple"最多外溢 1.5px（≈0.55dp，肉眼不可见）；
     * 形状/尺寸/半径/颜色/模糊/亮度/高光/透明度全部未改（可见几何逐像素不变）。
     */
    @Volatile var panelEdgeAa: Boolean = true

    // ================= 【单变量排查】panelEdgeAa 的三处改动拆分（永久保留，非临时） =================
    // 目的：panelEdgeAa 一次改了三处 → "改了没效果也不知道是谁的问题"。
    // 拆成三个子开关后，可在同一构建上用 adb 逐项 A/B（每次只开一处）：
    //   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name aaFixFillPath --ei value 0 -p com.liqglass.ultraclear
    // 语义：仅当 panelEdgeAa=true 且对应子开关=true 时，该处才走"抗锯齿行为"；
    //       全部子开关=0 就等价于 panelEdgeAa=false（旧行为）→ 可作为统一的对照基线。
    //
    // 【实测结论·模拟器 1840×2944@320dpi，p=1.0 面板静止态，5 个冷启动状态逐像素互比】
    //   图与原始数据：~/Downloads/LiquidGlass-aa-sweep/m_p1_S0..S4/、matrix_TL_arc.png
    //   · S4（全开）vs S0（全关）只差 4 个像素（max 13/255）⇒ 面板静止态的弧线【本来就有
    //     AA】：边界像素带 9%~93% 的覆盖率过渡 —— 即本机上"容器 Path 裁剪"是带 AA 的，
    //     与"Compose 对 Path outline 一律不做 AA"的旧假设不符 ✗（该假设可能只在部分 GPU/驱动成立）。
    //   · S1（只开①外扩）是唯一有可见效果的一项：它把可见边界整体【外推 1.5px】
    //     （TL 角 0.50%/2.01%/max114）—— 是几何位移，不是抗锯齿收益。
    //   · S2/S3 在面板静止态几乎零像素影响（该状态玻璃 alpha=0，玻璃层不参与绘制）。
    //   ⇒ ① 保留为"GPU 不做 clipPath AA 的设备"的保险（真机待验）；若真机确认也 AA，可置 false
    //     （那一行就是纯几何位移，会与 SDF 形状错位 1.5px）。
    /** ① 容器裁剪形状外扩 1.5px（把无 AA 的硬裁剪边退到玻璃 SDF 羽化带之外）。 */
    @Volatile var aaFixContainerInflate: Boolean = true
    /** ② 填充层改由【AA 路径绘制】给出边界（原来是一个矩形，边界 100% 来自硬裁剪）。 */
    @Volatile var aaFixFillPath: Boolean = true
    /** ③ 面板玻璃层不再自叠一层 Path 裁剪（改 RectangleShape）→ 边界交给 Shader 的 SDF 覆盖率。
     *  实测（p=0 胶囊态，玻璃参与绘制）：翻转本项会在胶囊两端弧上产生差异 —— 说明
     *  `DrawBackdropModifier.layoutLayerBlock{clip=true;shape=…}` 那层库内 Path 裁剪
     *  确实在硬切 SDF 羽化（第三机制），关掉它是对的 ✓  */
    @Volatile var aaFixGlassRect: Boolean = true

    /**
     * 【边缘抗锯齿·真机硬化】容器裁剪在【玻璃层可见档位】（glassAlphaOf(p)>0.001，p ≲ 0.2956）
     * 改用更大的外扩量（PANEL_CLIP_CLEAR_FEATHER_PX = 6px），让那条"GPU 做了不做 AA 取决于平台"
     * 的 Path 硬裁剪边【彻底】离开 Shader 的 SDF 覆盖率羽化带。
     *
     * 依据（模拟器实测，本轮）：
     *   · ①的 1.5px 只够退出【填充层路径绘制】的 AA 窄带（≈1px）；胶囊态（p=0，玻璃真的参与绘制）
     *     的可见边界是 SDF 羽化带，实测边界外侧仍有 ~2.5px 非零 coverage（胶囊左端弧每行 2~4 个
     *     "中间覆盖率"像素，24/30 行有中间像素）⇒ 1.5px 仍落在羽化带内部，会把外侧那圈切掉。
     *   · ① 开/关在胶囊端点弧改变 620 像素、最大差 59（bbox 688,2720–1151,2843）—— 那圈就是被切的。
     *   · 6px ≥ 羽化带外沿 ⇒ 可见边界完全交给 SDF 羽化（AGSL 覆盖率在【所有】设备上都做 AA ✓）。
     * 安全边界：该档位 contentAlphaOf(p)≡0（内容 p>0.42 才淡入）⇒ 外扩不会露出内容 ✓；
     *   6px ≪ 玻璃层余量（面板过采样 pad ≈18.5px）✓；【默认 false】⇒ 与今天逐像素一致（硬门安全）。
     * 真机 A/B：`--es cmd setSwitches --es name aaFixContainerClearFeather --ei value 1`
     */
    @Volatile var aaFixContainerClearFeather: Boolean = false
    // ⚠️【运行时 A/B 必读】容器裁剪的 shape 读在 graphicsLayer block 里、玻璃层 shape 读在库的录制
    //   lambda 里 —— 这两处都不是组合期读取。DebugSwitches 是普通 volatile（不产生订阅），
    //   所以翻开关后若这两处没被 invalidate，会继续用【旧形状】渲染 → A/B 拍到"混合态"，
    //   给出自相矛盾的结论（上一轮 panelEdgeAa "有效/无效"反复摇摆的真正原因 ✗）。
    //   已在 LiquidGlassScreen 的这两处读一次 DebugBridge.revision（setSwitches 会自增）⇒ 翻开关即生效 ✓
    //   实测：修复后同一条 A/B 命令，ON vs OFF 的 TL 角差异 = 0.002%/max13；修复前 = 0.558%/2.19%/max114。

    /**
     * 【卡片边缘 255 硬台阶 · 根因修复】玻璃层 effect 的输出改为【预乘 alpha】。
     *
     * 根因（机上探针实证，2026-09-14 09:xx，模拟器 emulator-5554）：
     *   AGSL render-effect 的输出被合成器按【预乘】语义使用（Skia 契约），而本项目 shader 返回的是
     *   【直通 alpha】：`return half4(rgb, a);` ⇒ 实际合成 = `rgb + (1-a)*bg`，
     *   而不是 `a*rgb + (1-a)*bg` ⇒ **边缘 coverage 羽化（0.2/0.4/0.6/0.8）不再衰减颜色** ⇒
     *   最外 1 个"上漆"像素（a≈0.2）就把高光本体（rgb≈0.6~1.0，HDR 时更高）整份叠上去 ⇒
     *   与相邻背景一步跳 82~91 灰阶 = 用户看到的"锯齿/硬台阶"。
     *
     * 三组探针（同一状态、只切一个变量）的判决性数据：
     *   · dm4「最终 rgb（alpha 强制 1）」：x=580..583 = 0.612 / 0.710 / 1.000 / 0.953
     *   · dm0 FINAL（真 alpha，coverage=0.2/0.4/0.6/0.8）：同四像素 = 1.000 / 1.000 / 1.000 / 1.000
     *   · 模型对账：`rgb + (1-a)*bg` ⇒ 1.154 / 1.117 / 1.273 / 1.089 → 全部 ≥1 → 显示端饱和 = 255 ✓✓
     *                `a*rgb + (1-a)*bg` ⇒ 0.665 / 0.691 / 0.871 / 0.892 → 与实测 1.0 不符 ✗
     *   ⇒ 合成公式确定为前者（= shader 少了预乘）。
     *   （历史上两次"黑玻璃/红玻璃探针"都误判为"alpha 正常"：黑玻璃 rgb=0 时两公式同值 143/108/73/38，
     *     红玻璃只吃 R 通道 ⇒ G 通道两公式也同值 138/104/… ⇒ 陷阱正是"探针颜色恰好落在两公式重合处"。）
     *
     * 修法：只改【卡片】这一条调用路径的 AGSL 文本（面板/胶囊/张力桥一律不动 ⇒ tintModel=0 那套观感逐像素不变）：
     *   `return half4(rgb, a);` → `half o = a; return half4(rgb * o, o);`
     *   ⇒ 合成 = a*rgb + (1-a)*bg ⇒ **可见边界重新由 SDF 的 5px coverage 羽化决定**（α 真正衰减颜色）。
     *   内部像素 a=1 ⇒ 逐像素不变 ✓（HDR 高光在玻璃内部的表现完全保留）。
     *
     * true（默认，修复生效）；false = 旧行为（直通 alpha）—— 一行切换，可在同一构建上做 A/B。
     * 【A/B 必读】本开关在 effects 录制 lambda 里读取（非组合期）：已同时读一次 DebugBridge.revision
     * （快照状态）⇒ 用 adb setSwitches 翻转后立即失效重录，拍不到"混合态"。
     */
    @Volatile var cardPremultipliedOutput: Boolean = true

    /**
     * 【直边→圆角切点接缝 · 同源法线】形状法线（sd 的数值梯度，驱动高光双瓣 / 折射位移 / 内暗边）的
     * 来源半径是否与【可见形状】同一个半径。
     *
     * 定位（模拟器 emulator-5554，glassSize=0.5、p=0、卡片可见矩形 (444,374) 920×1510，半径 72px）：
     *   · 可见边界 = SDF(radius=72) 零集；而渐变场用的是
     *     `gradRadius = min(radius*1.5, min(shapeHalf)) = 108px`（G2 外延 E'=1.5287×108=165px vs 可见 110px）
     *     ⇒ 两套几何：法线场来自【比可见形状更钝】的圆角矩形。
     *   · 沿可见边界实测两者夹角（解析复算，numpy 重实现同一份 sdContinuousRect）：
     *     切点处（deg 0°/90°）**18.3°**、diag 45° 处 0°、直边 165px 外 0° ⇒ 误差**恰好集中在切点**。
     *   · 图上后果（机上抓图 + 模型对账）：高光带亮度 ∝ (lobeF+lobeB) = |dot(N, -L)|^1.2，
     *     而 N 用的是那套偏了 18° 的法线 ⇒ 亮度零点的位置被挪走（实测 TL 角在 deg≈33° 而不是几何对角 45°），
     *     切点两侧的「高光-暗角过渡」与可见边界【不同源】= 用户看到的「边缘过渡的法线」✓
     *     （实测 TL 角：直边 253 灰阶 → 弧上 162（=背景）→ 另一侧直边 253；TR 角无零点则全程 254）
     *   · 同一处还叠加一条【暗缺口】（在弧上 10~14px 内侧，145 灰阶 vs 背景 163，约 15×10px）
     *     —— 即暗 rim 在高光被压暗处裸露出来。
     *
     * true = 法线与可见边界**同源**（gradRadius = radius ⇒ 渐变场与 SDF 零集是同一套几何，
     *        切点处法线连续、与直边自然 C1 衔接 —— 符合项目铁律「可见边界与 SDF 同源」）；
     * false（默认，与今天逐像素一致）= 旧行为（法线来自 radius×1.5 的近似场）。
     *
     * ⚠️【运行时 A/B 必读】本开关改的是 AGSL 【源文本】（不是 uniform）⇒ 只在 shader 程序构建时读一次
     *   （backdrop/BackdropAdapter.kt 的 programCache 每档位每进程缓存一次）⇒ **翻开关后必须
     *   force-stop + 重启应用才生效** ✗；同一进程内翻转不会重编译（key 不变、源码也不重建）＝**假 A/B** ✗。
     *   （对照：cardPremultipliedOutput 能「翻开关即生效」，是因为 adapter 把那一档的两份源码都预先备好了；
     *     本开关没有那条通路 —— 若将来要真·运行时切换，需要在 adapter 里同样预生成两版源码。）
     *   【偏偏 @Volatile 字段本身【不跨进程】】⇒ 光靠 setSwitches 翻它、再重启，重启后字段又回到默认值 ✗。
     *   因此下面这个初值额外从【应用私有目录里的一个标志文件】读一次（debug 包可用
     *   `adb shell run-as com.liqglass.ultraclear sh -c 'echo 1 > files/lg_same_source_normal'` 写）：
     *     开：adb shell run-as PKG sh -c 'mkdir -p files; echo 1 > files/lg_same_source_normal'
     *     关：adb shell run-as PKG sh -c 'rm -f files/lg_same_source_normal'
     *     然后 am force-stop + am start（新进程会在建 AGSL 源时读它）✓
     *   构建期留痕可核对哪一份源码真的进了 AGSL：logcat 里的
     *   `GlassShaders.source(<档位>): shapeNormalSameSource=…`。
     */
    @Volatile var shapeNormalSameSource: Boolean = readSwitchFile("lg_same_source_normal")

    /**
     * 【直边→圆角切点 · 溢出直线修复】（2026-09-14，用户给的定位线索：「玻璃模块和控制中心的 G2 圆角
     * 对比一下看看相对于控制中心哪里有多的线」）。
     *
     * 缺陷（机上定位，emulator-5554，glassSize=0.5、p=0、卡片可见矩形 (444,374) 920×1510、r=72px）：
     *   卡片【顶边切点区】（节点局部 X∈[62,113] = 普通圆角切点 r=72 与 G2 外延切点 E=1.5287r=110 之间、
     *   贴在边线 Y≈0 上的那一段【直线】）多出一条 1px 直线：
     *     · `setDebugMode 2`（仅折射）剖面：y=30、x∈[508,556] 整段 <100（峰值 **22** vs 背景 143）= 采样被甩到屏外；
     *     · FINAL 同处亮带被切成【双峰】：y=29 峰 179 / **y=30 谷 156** / y=32 峰 250；
     *     · 控制中心（面板 G2 路径边界、胶囊）同角度**只有单峰**、无暗线 ⇒ 用户说的"多的线" ✓
     *
     * 真因（源码级，glass/GlassShaders.kt 的 sdContinuousRect）：角区早退条件
     * `if (u <= 0.0 || u >= 1.0 || v <= 0.0 || v >= 1.0) return dBox;` 里的 `u<=0 / v<=0` 是【边线外侧】——
     * 那里直接返回【普通圆角矩形】的距离 dBox，而边线另一侧返回 `max(dBox, dCut)`（G2 切角），
     * 于是场在边线上【不连续】（节点局部 X=86：外侧 0.80 → 内侧 5.83，跳 5px）。
     * `gradShape()`（数值梯度 eps=1px）跨这条不连续线时法线【整根翻转】(0,-0.98)→(0,+1.00)，
     * 折射位移 `dispBase = lensNormal·arcMap·64px` 随之 **-62.7px → +61.0px（跳 124px）**，
     * 采样坐标被甩出屏幕 ⇒ 那条"溢出的直线"（numpy 逐像素复刻的预测与机上 dm2 实测一致 ✓）。
     *
     * true（默认）= 修复：早退只保留"角区背后"（`u>=1 || v>=1`），边线外侧继续走角区公式
     *   ⇒ 场在边线两侧连续 ⇒ 法线不折返 ⇒ 折射不跳变（实测 dm2 暗线 52px → 0px、剖面谷值 156 → 回到包络）。
     * false = 与修改前【逐字符等价】的对照档（旧条件行）。
     *
     * 【只对卡片生效】AGSL 内以 tintModel 门控（卡片=1 / 面板·胶囊=0）⇒ 控制中心逐像素不变 ✓；
     * 张力桥 radius=半高，走既有的 `E > 0.95·min(halfSize)` 回退分支 ⇒ 也逐像素不变 ✓；
     * 其余形状（圆/胶囊/椭圆/三角/六边/超椭圆）不经过 sdContinuousRect ⇒ 零回归 ✓。
     *
     * 【运行时 A/B（无需重启）】adapter 预生成两版源码（cardPremultipliedSource / …LeakOffSource）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name g2TangentLeakFix --ei value 0 -p com.liqglass.ultraclear   # 回退旧行为
     *   （0 = 旧行为 / 1 = 修复；读点在卡片 effects 录制 lambda，同时陪读 DebugBridge.revision）
     */
    @Volatile var g2TangentLeakFix: Boolean = true

    /**
     * 【P63·系统返回先收回展开的控制中心】（2026-09-23）true（默认）= 面板【展开态】(p≥0.5) 或
     * 二级页（更多设置/图片编辑/组件演示/许可）时，系统返回（返回键 / 侧边返回手势 / Android 13+
     * 预测式返回）先按【表头「‹ 基础 / ← 返回」按钮同一条优先级链】退页/收面板到 p=0，✗ 不退出应用；
     * 已收起（p<0.5）时【不拦截】= 改动前原样（返回照旧交给系统，允许退出）。
     * false（一行回退）= 改动前行为：系统返回从不碰面板（展开态也直接退出应用）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name backCollapsePanel --ei value 0 -p com.liqglass.ultraclear
     * 读取点在 PredictiveBackHandler 的 enabled（组合期）⇒ 陪读 DebugBridge.revision，翻开关即重组生效。
     */
    @Volatile var backCollapsePanel: Boolean = true

    /**
     * 【跨进程开关·读取】应用私有 files/ 目录里的标志文件（内容 "1" = 开）。
     * 为什么需要它：本开关决定 AGSL 源文本，而源文本只在【进程内 shader 程序构建时】读一次 ⇒
     * 翻开关必须重启应用；而 @Volatile 字段不跨进程 ⇒ 必须有个落在磁盘上的初值来源。
     * 只读、失败即 false（release 包 run-as 不可用 ⇒ 文件不存在 ⇒ 恒默认 ✓ 行为与今天一致）。
     */
    private fun readSwitchFile(name: String): Boolean = runCatching {
        java.io.File("/data/data/com.liqglass.ultraclear/files/$name").readText().trim() == "1"
    }.getOrDefault(false)

    // ================= 【控制中心展开后半段卡顿·修复开关】 =================

    /**
     * 【主因修复·内容槽恒高】面板内容容器的【布局尺寸】在整段 p∈[0,2] 恒为常量 =
     * `contentHeightPxOf(0f)`（= 屏高×panelHeightFraction = p=1 档终态高度），
     * 由 p 决定的相对位移改到【绘制期】`graphicsLayer.translationY`
     * （RenderNode 属性变更不使 display list 失效 ⇒ 不重录子树）。
     *
     * 为什么必须连【本节点的尺寸】一起钉死（只钉子项 constraints 无效，实测已证）：
     *   p∈(1,2] 时旧实现返回的槽高 = hOf(p) 逐帧长高 ⇒ 本节点每帧 size 变化 ⇒ 其图层被 invalidate
     *   ⇒ 整棵设置面板内容（几十个控件，自身没有图层）每帧重录 = 4.5~4.7ms/帧（实测），
     *   而"只把子项 constraints 钉死"（revealContentMeasure）对 RT 耗时【零差别】。
     * 观感为什么不变：实测同一状态三档（p=1.0 / 1.5 / 2.0）面板内"深色内容行"相对【面板顶边】
     *   的位置逐行完全一致（无位移、无拉伸）⇒ p>1 多出来的那截面板高度在旧实现里本来就是空白。
     * true（新，默认）；false = 旧行为（槽高/摆放随 p 逐帧变）—— 一行回退。
     * 【H3·优先级】仅在 contentSlotAdaptivePage=false 时才是决策项；此时它吞掉 revealContentMeasure
     *   （when 首命中）。
     */
    @Volatile var contentFixedSlot: Boolean = true

    /**
     * 【回归修复·内容槽随当前页自适应】内容槽的恒定高度【按当前页】取常量（而不是一律取 p=1 档高度）。
     *
     * 背景（模拟器实测，2026-09-14）：contentFixedSlot=true 把槽高钉成 contentHeightPxOf(0f)
     * （= 屏高×0.55 ≈ 1619px = p=1 档面板高）⇒ 面板上滑到满高（p→2，2885px）后，
     * 内容只铺到"面板顶+1619px"，下面 1266px 全空 ✗ = 用户报的「更多设置只显示上半屏」✗✓
     * （一级页内容本来就短，看不出；二级「更多设置」列表长，最显眼）
     * 旧次因参考：`GlassControlsPanel` 的 `.heightIn(max = 470.dp)` —— 实测在本项目【不生效】
     * （父级 weight(1f) 给的是【紧约束】，SizeNode 把它 coerce 回固定值 ⇒ 上限被吞掉；
     *  证据：p=1 时列表内容一直画到容器底 2880px，且 2757~2845px 的「更多设置」按钮可点中）✗
     *
     * 语义：槽高 = 「当前页需要的终态高度」常量 ——
     *   · 一级基础菜单 → contentHeightPxOf(0f)（与修复前逐像素一致，零回归面）
     *   · 二级更多设置 → fullHeightPx（= 屏高×0.98 = p=2 档面板高）
     * ⇒ 面板长到多高，内容就有多高，p∈[1,2] 不再出现空白下半屏 ✓
     * 位移语义（contentSlotShiftYOf）保持"内容顶边钉在 p=1 面板顶边"这一锚点：
     *   p≤1 底边贴当前面板底边（自下而上揭开）、p≥1 顶边贴面板顶边 —— 与修复前在【一级页】
     *   上逐像素等价，在【二级页】上 p=1 的可见像素也不变（槽只向下变长）✓
     *
     * ⚠️ P04 的命根子（必须保住）：槽高在【p∈[0,2] 内恒定】—— 只随"页"这一离散事件变化
     *   （点按进入/返回时重排重录一次），动画期间一帧都不变 ⇒ 内容子树不重测、本节点图层不失效 ✓
     * true（新，默认）；false = 旧行为（两级都用 contentHeightPxOf(0f)）—— 一行回退。
     * 【H3·优先级】本项（默认开）最高：`contentSlotHeightPxOf` 的 when 第一分支 ⇒ contentFixedSlot 与
     *   revealContentMeasure 在下游【永不被查询】（死分支）。
     */
    @Volatile var contentSlotAdaptivePage: Boolean = true

    /**
     * 【P55·全屏状态下只显示一半（一级页）】内容槽的"随页自适应"是否覆盖【一级基础页】（控制中心首页）。
     *
     * 背景（emulator 1840×2944@320 实测）：`contentPageSlotPx()` 的满高槽只覆盖二级页
     * （更多设置两 tab / 图片编辑 / 许可）与非紧凑档的组件演示 ⇒ 一级基础页仍取
     * `contentHeightPxOf(0f)`（= p=1 档面板高 1619px）。面板上滑到满高（p=2 → 2885px）后：
     *   · 内容槽 1619 / 面板 2885 ⇒ 内容只铺到"面板顶+1619px"，下面 1266px（44%）全空 ✗；
     *   · 列表滚动视口 1407 < 内容实高 1512~1648 ⇒ 末尾 105~241px（'拖动拉伸（玻璃）' 与
     *     '更多设置·画质/光学…' 两行）停在可视下沿之外 ⇒ 用户要在满屏面板里滚动才看得见它们 ✗
     *   = 用户报的「全屏状态下只显示一半」✗（与 2026-09-14「更多设置只显示上半屏」同源；
     *     那次修复显式排除了本页 —— 2fa852d「一级页维持 612/588 不裁」，当时一级页内容确实更短）。
     *
     * 修法（一行）：一级基础页并入满高槽分支 ⇒ p=2 时列表视口 1407→2673 ⇒ 全部行可见
     *   （实测 maxScroll 105→0），内容区不再"只铺上半截"✓
     *   ⚠️ 半屏 p=1 零回归的依据：满高槽页面的容器顶由既有 `panelSlotCenterFix` 补偿钉在面板顶，
     *   而 p=1 面板高只有 1619px ⇒ 可视部分 = 槽的上 1619px，与改动前（槽 1619）重合
     *   （本单验收：y∈[120,2880) 逐像素 0px）✓
     * false = 旧行为（一级页槽高 = contentHeightPxOf(0f)）—— 一行回退 ✓
     */
    @Volatile var contentSlotBasePage: Boolean = true

    /**
     * 【P43·半屏显示不全修复】列表内容末尾按"半屏可见高度差"补余量（默认 true）。
     *
     * 背景（模拟器 1840×2944 实测，见 dumpState 的 listslot 行）：二级页槽高 = 2885px（= p=2 档
     *   面板高），列表滚动视口 = 槽高 - 表头 - 导航栏内边距 = 2673px；而半屏 p=1 时面板只有
     *   1619px ⇒ 可见内容区 ≈1407px ⇒ 视口比可见区高出 1266px。后果：滚动到底时内容最后一行
     *   停在屏幕外（y≈4098，屏底 2944）⇒ **半屏下永远看不到页面最后 1154px** ✗（用户报的
     *   「拉到半屏的时候显示不全」；一级基础菜单槽高 = 1619 ⇒ 不受影响）。
     *
     * 修法（不改槽高、不改视口、不改任何面板几何）：给列表内容末尾加一段余量
     *   pad(p) = max(0, 槽高 - max(面板高, p=1 档面板高)) —— 半屏 1266px、满高 0px；
     *   p∈(1,2] 连续 ⇒ 滚动到底时最后一行【屏幕 y 恒 = 2880】⇒ 半屏展开到满高零跳变 ✓；
     *   p≤1 冻结为常量 ⇒ 开合动画期间零额外重排（P04 性能前提不破 ✓）；
     *   一级页 pad ≡ 0 ⇒ 逐像素零回归 ✓。
     * false = 一行回退（余量恒 0，逐字回到改动前：半屏滚不到底）。
     */
    @Volatile var listScrollPad: Boolean = true

    /**
     * 【P43·半屏页首被裁修复】满高槽页面的"内容容器垂直居中"补偿（默认 true）。
     *
     * 实测（应用自身 P38L 日志）：二级页内容容器的摆放顶 = 面板顶 + (面板高 - 槽高)/2（被居中 ✗），
     *   半屏 p=1 时容器顶落在 692（面板顶 1325 之上 633px）⇒ 面板表头（把手/标题/返回键）与页面
     *   最前面 ~633px 内容落在面板上缘之外，且列表已在顶部 ⇒ 永远看不到 ✗（用户「半屏显示不全」）。
     * 修法：绘制期把 (槽高-面板高)/2 加回 translationY（零重排/零重录 ✓）：
     *   · p=1 → +633（表头 + 页首可见 ✓）；p=2 → 0（不变 ✓）；p∈(1,2] 连续（零跳变 ✓）；
     *   · 一级基础菜单（非满高槽）恒 0 ⇒ 逐像素零回归 ✓。
     * false = 一行回退（comp ≡ 0，逐字回到改动前：半屏看不见表头/页首）。
     */
    @Volatile var panelSlotCenterFix: Boolean = true

    /**
     * 【主因修复的伴随项·空白区拖拽承接层】内容容器尺寸恒定后，面板底部"内容以下"的空白区
     * 不再落进列表的 viewport（旧实现里那段是被列表 viewport 覆盖的、内容装得下因此不可滚动的区域，
     * 拖动时它会走嵌套滚动的 post-scroll 把面板收起）。这里补一层【不可滚动的滚动容器】
     * （maxValue=0，透明、不绘制），把剩余滑动量交给【同一个 sheetConnection】：
     *   onPostScroll → anchorState.dispatchRawDelta；onPostFling → settle（与列表路径逐字相同）
     * ⇒"在面板任意位置下滑都能收回胶囊"的语义不变 ✓。z 序在内容层【之下】⇒ 列表/手柄照旧优先命中 ✓
     * true（新，默认）；false = 只保留内容容器范围内的拖拽。
     */
    @Volatile var contentStripDrag: Boolean = true

    /**
     * 【次因修复·设置列表提前进入组合】打开方向（showControlCenter = 目标已指向打开）时，
     * 列表的入组合阈值从 p>0.42 提前到 p>LIST_EARLY_COMPOSE_P(0.10)：这一次性组合开销
     * （实测单帧 42~67ms）落在【动画开头·面板最小·高度变化最慢】处，不再砸在 p≈0.42 的加速段。
     * 观感不变：内容 alpha = contentAlphaOf(p) 在 p≤0.42 恒为 0 ⇒ 列表此时完全不可见、不绘制 ✓
     * 安全语义不变：关闭方向（目标已回 0）仍用原阈值 0.42；且打开方向下收起态胶囊本来就没有
     * clickable（showControlCenter=true 时那层 clickable 被摘掉）⇒ 不存在"不可见列表替胶囊吃点击"。
     * true（新，默认）；false = 旧阈值（0.42）。
     * 【H3·优先级】gate 的第三项：仅当 ①precomposeListKeepAlive 未生效 ∧ ②deferListComposeUntilSettled
     *   关闭 时才是决策项（②开时本项被吞 ✗；三者默认全开 ⇒ 正常路径下本项同样让位）。
     */
    @Volatile var listComposeEarly: Boolean = true

    // ================= 【"刚打开控制中心时卡一下"·首次组合开销的两种移走方案】 =================
    // 背景（真机 + 模拟器实测）：设置列表【首次组合】是一记单帧 42~67ms 的重击。
    // P04 的 listComposeEarly 把它的发生点从 p≈0.42（加速段）提前到了 p≈0.10（动画开头）——
    // 那只是【换了位置】，开销本身还在动画里 ⇒ 用户反馈"刚打开控制中心时卡一下"✗。
    // 判据（本轮）：42~67ms 的单帧在【动画进行中】= 肉眼可见的卡；在【静止窗口】= 几乎不可察觉
    //   （没有位移参照）。因此下面两个开关的目标都是把这一帧移出动画区间。
    // 两者独立可回退；两个都置 false = 完全回到今天的行为（listComposeEarly 的 p≈0.10 门控）。

    /**
     * 【方案A·列表预组合常驻】（默认 true —— 优先方案）
     *
     * true = 取"下一次打开完全零开销"：
     *   ① 冷启动首帧之后（等 2 帧，不与首帧抢预算）把列表【预热组合一次】；
     *   ② 之后【永不拆组合】——原来每次收起到底（p<0.42）都会把列表摘出组合 ⇒ 每次打开都要
     *      重付一次 42~67ms ✗；现在这笔开销只在启动时的静止窗口里出现一次 ✓；
     *   ③ 打开动画期间列表【本来就在】⇒ 观感逐帧与今天完全一致（不会"啪地出现"）✓✓，
     *      且整段动画里没有任何首次组合/首次录制 ⇒ 目标是 Slow UI / Slow issue draw = 0 ✓。
     * 【输入隔离（必须）】常驻组合会让内容子树在收起态也参与命中测试（历史坑：不可见的列表
     *   替胶囊吃掉点击 → 点不开面板 ✗）。因此收起到底（panelEngaged=false）时把内容整体
     *   【挪到屏幕外】（layout 的 place 偏移）——子树不在任何触摸坐标里 ⇒ 胶囊的点击/拖动
     *   语义与"内容未组合"逐字相同 ✓；且 p=0 时内容 alpha 恒为 0、被容器裁掉 ⇒ 挪动不可见 ✓。
     * false = 不预热、不常驻：回到"列表随门控组合/拆解"的旧行为（配合下面的方案B使用）。
     * 【H3·优先级】本项（默认开）预热完成后使 gate 恒真 ⇒ 方案B / listComposeEarly 两档在下游【让位】
     *   （死分支）：要拍它们的 A/B 必须先关本开关 ✗ 否则假阴性。
     */
    @Volatile var precomposeListKeepAlive: Boolean = true

    /**
     * 【方案B·把首次组合推迟到"面板已静止"】（默认 true —— 覆盖预热完成前的冷启动窗口）
     *
     * true = 打开方向（showControlCenter=true）把列表入组合的阈值从 p>0.10 改成
     *   【面板彻底静止之后】（进度 offset 连续两次采样 48ms 完全相同、±0.5px 贴住锚点、且 p≥0.98）。
     *   这样做：动画全程不组合 ⇒ 那记 42~67ms 的重击落在面板已定格、无位移参照的静止窗口 ✓。
     *   ⚠️ 不能用 anchorState.isAnimationRunning 判"动画已停"：手柄松手后的官方收口走
     *     【无 target 的 anchoredDrag{performFling}】→ 该标志恒为 false ✗（NEXT.md 已记）。
     *   ⚠️ 兜底：判定若因任何原因失效（动画被打断/停在中间），LIST_DEFER_TIMEOUT_MS 后强制组合
     *     ⇒ 列表一定出现，绝不会留下空面板 ✗。
     *   ⚠️ 诚实边界：单独使用本方案时，列表是在"内容淡入已结束处"才出现的 ⇒ 会有一次
     *     "内容补齐"的可见跳变（面板已定格，无位移参照，但能看到内容一次性补上）。
     *     方案A（默认开）把这个窗口缩到"冷启动后头几帧"，从根上避免该跳变 ✓。
     *   ⚠️ 只在【打开方向】生效；关闭方向仍用原语义（contentVisible=0.42 → 收起到底即拆组合，
     *     保证"不可见列表不替胶囊吃点击"）。
     * false = 旧阈值（p>0.10，与 listComposeEarly 同源）。
     * 【H3·优先级】只在 keepAlive（方案A）尚未生效的窗口里是决策项；与 listComposeEarly 互斥
     *   （后者的 gate 项自带 !本开关）⇒ 本开关开且生效时 listComposeEarly 被吞。
     */
    @Volatile var deferListComposeUntilSettled: Boolean = true

    /** 【P05·无缝交棒】true（默认）= 胶囊里的「控制中心」平滑移到/放大到半屏面板左上角标题位（单一文字实例）；
     *  false = 旧路径（胶囊内标签淡出 + 面板标题常显）。
     *  实测两端逐像素等价：p=0 与 p=1 在开关 ON/OFF 下差异均为 0.000%（最大通道差 0）。 */
    @Volatile var handoffLabel: Boolean = true

    /**
     * 【P05·抛物线 + 面板内框约束（2026-09-14 真机反馈②③）】true（默认）= 飞行文字走
     * 【直线插值 + 抛物线抬升 + 框内回弹 + 面板内框约束】：
     *   · 抬升：y -= A·4t(1-t)（A = 32dp ⇒ 320dpi 上 64px），两端恒 0 ⇒ 端点不变 ✓；
     *   · 回弹：tEff = t + 4%·bump(t)（bump 峰值在 t=0.75，两端恒 0 ✓）= 框内阻尼过冲（灵动 ✓）；
     *   · 约束：任意一帧（含过冲帧）中心都在【面板可视区 − 16px】内框里 ⇒ 越界帧数 = 0 ✓
     *     （内框由 wOf/hOf/liftOf 纯函数算出，与面板自身同源 ✓）。
     * false = 修复前行为（纯直线插值、无抬升、无约束）；一行回退（setSwitches handoffArc 0）✓。
     */
    @Volatile var handoffArc: Boolean = true

    /**
     * 【P05·颜色过渡（2026-09-14 真机反馈①）】true（默认）= 飞行文字的颜色在落位前
     * 由【白】插值过渡到【面板标题的实测色】（0xFF000000）：
     *   · 乘性上色（Modulate）在绘制期由 p 驱动（组合期不订阅 p ✓）；tint=白 ⇒ 恒等 ⇒ p<0.86 逐像素不变 ✓；
     *   · 插值窗口 p∈[0.86, 0.975]，0.975 = 交棒交叉淡出窗口起点 ⇒ 切换那一刻两边同色（白字跳黑 ✗ 消失 ✓）。
     * false = 修复前行为（整段纯白字）。一行回退（setSwitches handoffColorBlend 0）✓
     */
    @Volatile var handoffColorBlend: Boolean = true

    /**
     * 【P05·①灰阶过渡（2026-09-14 用户反馈②）】true（默认）= 交棒文字的「白 → 黑」改走
     * 【白 → 中灰(0xFF949494) → 黑】两段路径（经过灰色 ✓），且插值窗口从 p∈[0.86,0.975]
     * 提前/拉长到 p∈[0.45,0.975]（窗口时间跨度 76ms → ≈410ms，降变化率 ✓）。
     *   · 逐帧实测（emulator-5554，900ms 点击展开，同一条动画）：改前单帧最大通道跳变 = 131/255
     *     （F1F4FC → 737579 一帧跨掉一半亮度 = 用户说的"白变黑还是有点突傅"）；
     *     改后同一动画的 tc= 序列里出现一段 R=G=B 的纯灰（0x949494 → 0x000000），
     *     单帧最大跳变 ≈ 40/255 量级（逐帧实测见交付报告的颜色序列表）✓
     *   · 端点不变：p<0.45 ⇒ 颜色 = 胶囊白 0xF4F7FF（逐像素不变 ✓）；p≥0.975（交叉淡出起点）
     *     ⇒ 颜色 = 面板标题色 0x000000（切换那一刻两边同色，色差 0/255 ✓）
     * false = 改动前行为（白→黑单段直线 + 旧窗口 p∈[0.86,0.975]）。一行回退：
     *   `adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *        --es name handoffColorViaGray --ei value 0 -p com.liqglass.ultraclear` ✓
     */
    @Volatile var handoffColorViaGray: Boolean = true

    /**
     * 【P05·②灰阶过渡·窗口长度三档（2026-09-14 用户追加要求「色阶越细腻越好」）】
     * 过渡窗口 = p∈[窗口起点, 0.975]（终点 0.975 是交棒交叉淡出的起点，硬约束）。
     * 起点越早 ⇒ 窗口时间越长、单帧步进越小、灰阶越细腻；但文字越早开始离开纯白。
     *   · 默认（两个开关都关）= 细腻档 p0=0.10：窗口 ≈556ms（占 900ms 动画 62%）
     *   · `handoffColorWindowMid=1` = 均衡档 p0=0.25：窗口 ≈480ms
     *   · `handoffColorWindowSafe=1` = 保守档 p0=0.45：窗口 ≈408ms（改前是 p0=0.86 ⇒ 仅 ≈76ms）
     * 三者都【不改动画时长/几何端点】⇒ 总时长代价 0ms ✓；只是"从多早开始变灰"。
     * 运行时切换（无需重启，读点在绘制期）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name handoffColorWindowSafe --ei value 1 -p com.liqglass.ultraclear
     */
    @Volatile var handoffColorWindowMid: Boolean = false
    @Volatile var handoffColorWindowSafe: Boolean = false

    /** 当前生效的过渡窗口起点 p（见上；两开关冲突时取更保守的一档）。 */
    val handoffColorP0: Float
        get() = when {
            handoffColorWindowSafe -> 0.45f
            handoffColorWindowMid -> 0.25f
            else -> 0.10f
        }

    /**
     * 【P62·交棒/多卡对比度·2026-09-18】true（默认）= 灰阶途经的【中灰停靠点】压到当前
     * 选定档位的可读下限：对比度最差的那几档（实测 p=0.30~0.40 只有 1.03~1.05 ✗）抬到
     * ≥ 档位门槛（默认 handoffContrastFloorHard=4.5:1 → 灰 0x55）。
     * 白端点 F4F7FF 与黑端点 000000 都不变 ⇒ 端点色差 0/255 ✓（用户既定口径），灰渐变仍在 ✓。
     * false = 改动前路径（中灰 0x808080，最差 1.03）。一行回退：
     *   `adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *        --es name handoffContrastFloor --ei value 0 -p com.liqglass.ultraclear` ✓
     * 同一开关联动应用于【多卡演示说明文字】：handoffContrastFloor=true 时把说明文字颜色从
     * 0xFFC3CFE6（对比实测 1.6 ✗）换成深蓝 0xFF0E1626（面板 onSurface，≥ 7:1 ✗→✓）；
     * off = 原色 0xFFC3CFE6 逐字回退。
     */
    @Volatile var handoffContrastFloor: Boolean = true

    /** 【P62】4.5:1 档（默认开）：下限灰 0x55。false 时若 handoffContrastFloor=true 用 3:1 档 0x70。 */
    @Volatile var handoffContrastFloorHard: Boolean = true

    /**
     * 【收起态（胶囊）标签文字改黑·2026-09-23】用户：「控制中心胶囊形态字也换成黑色的」。
     *
     * true（默认，新）= 收起态（胶囊形态、p==0 静置）的「控制中心」四字渲染为纯黑 0xFF000000
     *   （与展开末态面板标题 HANDOFF_TITLE_COLOR 同口径）：
     *   · 回退档胶囊标签（ui/LiquidGlassScreen.kt 的收起态 Text）：color 直接换 0xFF000000；
     *   · 交棒实例（默认档可见的那份）实测在玻璃【之下】（字形芯 = 0.459×源色 + 0.541×玻璃，
     *     纯黑也只到 (70,72,74) 达不到 0/0/0）⇒ p==0 时它 alpha=0【让位】，纯黑由玻璃之上的
     *     收起态 Text 承担（同字形同位置）；p>0 交棒照旧（la / 白→灰→黑路径逐字未动 ✓）。
     * ✗ 展开动画（p>0）逐帧未动：handoffTintOf / handoffTimeU / 白→灰→黑路径 / handoffContrastFloor
     *   对比度下限口径 / 轨迹·几何·圆角 / 玻璃材质与背景白 全部逐字不动（改动只在 p==0 这一帧的着色）。
     * 一行回退（广播，立即生效——两处着色都陪读 DebugBridge.revision）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name capsuleLabelBlack --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var capsuleLabelBlack: Boolean = true

    /**
     * 【可读性·标签光晕·2026-09-24→2026-09-25 默认已关】false（默认）= 【不画任何光晕/衬底】： 
     * 用户原话「你现在这个黑色字底下又要加一个白色的底面，特别丑，特别不协调」✗ ⇒ 白光晕、
     *   白径向衬底、暗档深色圆盘【默认路径全部不再绘制】（代码保留在本开关后面供回退）。
     * 为什么现在可以不画：收起态胶囊填充已改成【纯灰实底】（见 capsuleSolidFill，不透明
     *   #C6CAD0/#333A45）⇒ 字形紧邻背景不再是"透出壁纸的玻璃"，而是固定中性灰，
     *   黑字/白字标称对比 ≈12.8:1 / ≈11.5:1，与壁纸无关 ⇒ 原先为补对比度加的补光层
     *   既无必要、又是用户点名的"丑"来源 ✓。实测（全 6 壁纸 × 两档）见本轮交付报告。
     * true = 旧行为（亮档：零偏移白光晕 Shadow 0xF2FFFFFF/blur 7 + 标签盒白色径向衬底
     *   0x2EFFFFFF/1.35×对角；暗档：字形深色零偏移光晕 blur 18 + 标签盒深色圆盘 0x44000000；
     *   交棒飞行字亮档也画白光晕 —— 同一开关）。
     * 一行回退（打开旧补光档；已开就不必发）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name capsuleLabelHalo --ei value 1 -p com.liqglass.ultraclear
     */
    @Volatile var capsuleLabelHalo: Boolean = false

    /**
     * 【① 胶囊实底灰化·2026-09-25】true（默认）= 收起态（p→0）控制中心胶囊的填充层
     * 不透明度 = 1.00（【纯灰实底】，灰值 = 本档 panelFill：亮 0xFFC6CAD0 / 暗 0xFF333A45），
     * 随形态平滑收到改动前的展开态端点（亮 0.90 / 暗 0.92）⇒ 只改【收起段的填充不透明度】，
     * 展开态面板端点逐值未变 ✓。
     * false = 改动前：收起态 0.35（亮）/ 0.78（暗）的半透明"白透顶"。
     * 一行回退：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name capsuleSolidFill --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var capsuleSolidFill: Boolean = true

    /**
     * 【⑤ 返回手势加速度上限·2026-09-25】true（默认）= 预测式返回手势驱动面板收起时，
     * 对 p 的每帧增量加【速度上限】BACK_GESTURE_MAX_DP_PER_SEC（= 3.0 p/s，按事件实测 dt 折算
     * 每帧上限）⇒ 极快甩不再出现"一帧吃掉半个 p"的无上限跳变 ✓；
     * 手势语义不变（仍是"手势进度 → p 联动、松手继续收口到 p=0"，✗ 不需要拖到底 ✓），
     * 慢拖不触发上限（逐帧 Δp 远小于上限）⇒ 手感不变 ✓。
     * false = 改动前：progress 直通 p（无任何限速）⇒ A/B 取证用这一档（同一次手势跑两遍）。
     * 一行回退：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name backGestureRateCap --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var backGestureRateCap: Boolean = true

    /**
     * 【起点黑化 → 恒色化·2026-09-25 口径更新】本开关在【恒色化】后对默认路径【已无效果】：
     * 胶囊字与面板标题在亮/暗两档都已同色（黑→黑 / 白→白），交棒字色路径现为
     * 「起点 = 调色板 handoffStart（= 当前标签色），终点 = handoffTitle」⇒ 同色端点下
     * 插值逐帧退化为恒色（不再插灰停靠点，用户原话「中间变灰色完全多此一举」✓）。
     * 本开关现在只影响【capsuleLabelBlack=false 的回退路径】（那条路径起点仍读它）。
     * 字段名保留（删除会连带广播协议变化）；一行回退：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name handoffStartBlack --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var handoffStartBlack: Boolean = true

    /**
     * 【深色/亮色模式自动切换·2026-09-24】true（默认）= 主题档位由 ui/GlassTheme.kt 的三档决定
     * （跟随系统 / 强制亮色 / 强制暗色；默认跟随系统，接入 Compose 的 isSystemInDarkTheme()）。
     * false = 恒走【亮色档】= 改动前的全部硬编码亮色取值（逐像素回到改动前 ✓）。
     *
     * 亮色档零回归的依据：亮色调色板（GlassTheme.LightGlassPalette）的每个字段都等于改动前的
     *   硬编码常量（0xFFC6CAD0 面板填充 / 0xFF0E1626 根底 / 0xFF000000 胶囊字 / 0xF2FFFFFF 光晕 /
     *   0xFFF4F7FF·0x808080·0xFF000000 交棒三点 / GlassPanelColorScheme 全量 / 0xFF4A5A78 提示行 …）
     *   ⇒ 亮色档下这些读取点算出的颜色与改动前逐比特相同。
     * 一行回退：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name themeAutoSwitch --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var themeAutoSwitch: Boolean = true

    /**
     * 【按压形变·按形状区分·总开关】true（默认）= 按形状区分：
     *   · 正方形系（圆角矩形/圆/三角/六边/超椭圆）→ 等比：每侧外扩 2.5% 该轴卡片尺寸
     *     （x/y 同一比例 ⇒ 方形/圆形/正六边形不变形 ✓；横向外扩与旧版 8dp 逐像素等价 ✓）；
     *   · 细长形状（胶囊/椭圆）→ 保留克制的各向异性：横胀 1.25% 宽 / 纵缩 0.625% 高
     *     （相对幅度 2:1 ≈ 旧稿 0.010/−0.005 ✓，并夹在旧版逐轴幅度内 x ≤ 8dp、y ≤ 19dp ✓）。
     * false = 旧行为（所有形状一律 x +8dp、y +19dp 的"横少纵多"外扩 ⇒ 方形被拉成
     *   高一点的矩形 ✗）。一行回退（布局与绘制一起失效，无需重启）：
     *   `adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *        --es name cardPressProportional --ei value 0 -p com.liqglass.ultraclear` ✓
     *
     * 实现位置：`ui/LiquidGlassCard.kt` 的 pressPadXPx / pressPadYPx —— 玻璃层每侧余量
     * （静止态 = (8dp,19dp) 抵消 backdrop 的 SHAPE_INFLATE 负膨胀；按下态 = 可见外扩量本身）。
     * 两个读取点（布局期 layout{} + 绘制期 effects{}）都已陪读 DebugBridge.revision
     * （@Volatile 开关必须订阅快照状态，否则翻开关不重算 → 拍到混合态，NEXT.md 已两次踩）✓
     *
     * 注：`foregroundDistortion()` 里的 scaleX/scaleY 是【旧的死代码路径】（全工程无调用 ✗），
     * 卡片按下时真正改变形状的是上面的每侧余量；两者已统一成同一套形状语义 ✓
     */
    @Volatile var cardPressProportional: Boolean = true

    /**
     * 【③B·玻璃尺寸口径 = 按【长边】】(2026-09-14 用户选定，默认 true)
     *
     * true（新口径）= 卡片【短边 = glassSize × 屏宽】恒定，【长边 = max(a, 1/a) × glassSize × 屏宽】
     *   （a = 形状宽高比 GlassShape.aspectRatio）：
     *   · a ≥ 1（横置：胶囊 1.92 / 椭圆 1.39）：宽 = a×glassSize×屏宽（长边）、高 = glassSize×屏宽；
     *   · 0 < a < 1（竖置）：高 = (glassSize/a)×屏宽（长边）、宽 = glassSize×屏宽；
     *   · a = 1（圆/六边/三角/超椭圆）：两套口径解析等价 ⇒ 代码仍走旧表达式（保证逐像素严格一致）✓；
     *   · a = 0（圆角矩形，不约束）：沿用 fillMaxWidth + fillMaxHeight 旧分支，逐像素不变 ✓。
     *   ⇒ 同一个形状旋转 90°（如胶囊 0.52 ↔ 1.92）算出的是【同一个矩形转置】= 横竖切换大小感一致 ✓
     *     （实测 @glassSize=0.35/1840×2944：横胶囊 1237×644；翻转前的竖胶囊正是 644×1237 ✓）
     * false（旧口径，一行回退）= 一律 w = glassSize × 屏宽、h = w / a
     *   ⇒ 细长形状旋转后长边按 1/a 变小（胶囊横置后会小 1.92 倍 ✗）。
     *
     * 【运行时 A/B】读点在组合期（ui/LiquidGlassScreen.kt 主卡 modifiers 的尺寸块），并已在该处
     * 读一次 DebugBridge.revision（快照状态）⇒ `setSwitches sizeLongEdge 0|1` 后立即重组生效 ✓（无需重启）。
     * A/B 命令：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name sizeLongEdge --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var sizeLongEdge: Boolean = true

    /**
     * 【尺寸缩放·中心基点】(2026-09-14 微信转达的用户需求：「调整玻璃尺寸时，缩放的基点应为图形的中心点，
     * 而不是最上方」；1cadb73 只把它记进了 NEXT.md 待办，本轮实现，默认 **开**)。
     *
     * true（默认，新）= 调 glassSize 时【图形几何中心 y 不动】：卡片顶边 = 0.14×屏高 − Δh/2
     *   （Δh = 当前卡片高 − 0.35 档卡片高）⇒ 四周同时扩张/收缩（围着中心长大/缩小）✓；
     *   gs = 0.35（默认值）时补偿量恒为 0 ⇒ 默认尺寸下与改前【逐像素相同】✓；
     *   gs = 0.2/0.35/0.5 实测中心 y（dumpState card rect）：圆角矩形 941.0/940.5/941.0（±0.5px，
     *   改前 714/940.5/1167）、圆与超椭圆 734.0/734.0/734.0（±0.0px，改前 596/734/872）✓。
     * false（旧行为，一行回退）= 顶边恒钉在 0.14×屏高 ⇒ 尺寸变大时上边不动、向下长（基点在最上方 ✗）。
     *
     * 实现位置：ui/LiquidGlassScreen.kt 的 sizeAnchorCenterDyPx（主卡；副卡不参与，其宽度与 glassSize 无关）。
     * 【运行时 A/B（无需重启）】开关在【放置期】与 mainDefaultTopLeft 里【现读】（不捕获预先算好的值），
     *   且放置期陪读一次 DebugBridge.revision（快照状态）⇒ 翻开关即使不改尺寸也当场重排、dump 与布局同源 ✓：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name sizeAnchorCenter --ei value 0 -p com.liqglass.ultraclear   # 回退旧行为
     */
    @Volatile var sizeAnchorCenter: Boolean = true

    /**
     * 【双指捏合(pinch)改玻璃尺寸 · 2026-09-14 用户需求】总开关，**默认开**。
     *
     * 用户原话：「把软件里的玻璃尺寸从控制中心控制同时兼容双指放大调整大小」。
     * 语义：true = 卡片上【双指捏合】实时改该卡尺寸（与面板「玻璃尺寸」滑块写的是同一个
     * [com.example.liquidglass.ui.GlassUiState.setSizeOfCard] ⇒ 天然双向同步；换算/夹取/顺带选中该卡见
     * `ui/CardPinchResize.kt`）；false = 捏合分支整体不存在 ⇒ 多指期间沿用旧行为（只跟踪第一根手指拖动）
     * ⇒ **与改动前逐字一致**（回退一行 ✓）。
     *
     * 【读取点】只在手势回调（ui/LiquidGlassCard.kt 的 cardGestures，非组合期）里现读
     * ⇒ 不需要 DebugBridge.revision 陪读：翻开关后【下一个触摸事件】即生效（没有任何渲染依赖它 ✓）。
     * A/B 命令：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name cardPinchResize --ei value 0 -p com.liqglass.ultraclear   # 关（= 改动前）
     *
     * 【设备取证】adb 合成 pinch 触发不了真手势（项目已知 ✗）⇒ 用调试桥走【同一函数】注入：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setUi \
     *       --es key pinchScale --ef fvalue 1.2 -p com.liqglass.ultraclear    # 双指距离放大 1.2 倍
     */
    @Volatile var cardPinchResize: Boolean = true

    // ============ 【控制中心「半屏→全屏」收口时序预设·2026-09-14 用户反馈专项】 ============
    // 用户原话：「控制中心由半屏切换到全屏的动画有点太快了」✗
    //
    // 【先确认它是哪一段】日志实测（emulator-5554，逐帧 LGLayout 时间线，p=进度 0..2）：
    //   · 点胶囊 → p:0→1 = 胶囊 → 半屏（高 = 屏高×0.55）—— 不是用户说的那段 ✓
    //   · 继续上滑/滚到底 → p:1→2 = 半屏 → 全屏（高 = 屏高×0.98）—— 用户说的就是这一段 ✓
    //   · 手指跟手部分（拖动 dispatchRawDelta）是 1:1 的，不受本预设影响 ✗；
    //     这一段里唯一的【自动插值】= 松手后的收口（官方 settle：手柄 fling 320ms / 列表 onPostFling 340ms）
    //     ⇒ 观感"太快"就发生在这里 ✓
    //
    // 【硬约束】本档位【只改时间与曲线】：p=1（半屏）、p=2（全屏）的几何端点
    //   （wOf/hOf/liftOf/topROf/bottomROf/contentHeightPxOf 的端点值）一律不动 ✓；
    //   其它收口方向（→0 收起 / 半途→1 / 全屏→半屏）也一律沿用原时长原曲线 ✓（不动已认可的手感）。
    //
    // 【切档】两个 Boolean 字段 → 设备上 setSwitches 直接可写（回现状 = 两个都置 0）：
    //   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //       --es name panelTimingStandard --ei value 1 -p com.liqglass.ultraclear   # 标准档
    //   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //       --es name panelTimingRelaxed  --ei value 1 -p com.liqglass.ultraclear   # 舒缓档
    //   （两个都为 1 时按"舒缓"取——取更慢的一档，语义可预期 ✓）
    //   ⚠️ 档位读取点在动画 spec 的 vectorize() 里（每次动画开始时求值、非组合期）⇒ 不需要重组，
    //      setSwitches 后【下一次收口】即生效 ✓

    /**
     * 「半屏→全屏」(p:1→2) 收口时序预设。
     * @param label 显示名（报告/日志用）
     * @param settleMs 收口动画时长（ms）
     * @param cx1..cy2 三次贝塞尔（CubicBezierEasing）控制点；现状档 = (0.42,0.05,0.22,1) 与本项目一致
     */
    enum class PanelTimingPreset(
        val label: String,
        val settleMs: Int,
        val cx1: Float, val cy1: Float, val cx2: Float, val cy2: Float,
    ) {
        /** 现状（默认，未被选中时生效）：320ms + CubicBezier(0.42,0.05,0.22,1) —— 用户说"太快"的那档，保留作对照基线 ✓ */
        CURRENT("现状", 320, 0.42f, 0.05f, 0.22f, 1f),
        /** 标准（推荐）：时长 1.5×（480ms）、曲线不变 —— 最保守的"只是慢下来"，手感形状一致 ✓ */
        STANDARD("标准", 480, 0.42f, 0.05f, 0.22f, 1f),
        /** 舒缓：时长 2.1×（680ms）+ 尾部更缓（起手更轻、落地更柔） */
        RELAXED("舒缓", 680, 0.33f, 0.00f, 0.15f, 1f),
    }

    @Volatile var panelTimingStandard: Boolean = false
    @Volatile var panelTimingRelaxed: Boolean = false

    // ================= 【动画一致性（2026-09-14 用户反馈①「展开和关闭的动画在手感上不是很一致」）】 =================
    // 逐帧日志实测（emulator-5554；LGLayout 的 p 序列 + LGSettle 的"选用参数"行；改前默认档）：
    //   · 点击展开 0→1（行程 1495px）= 900ms + CubicBezier(0.42,0.05,0.22,1)  ← 用户认可的基准
    //   · 按钮关闭 1→0（行程 1495px，同一条路反向）= 620ms + 同一条曲线 ⇒ 快 1.45× ✗
    //   · 按钮关闭 2→0（行程 2761px）= 620ms             ⇒ 快 4.4× ✗
    //   · 松手收口·把手 1→2（1266px）/ 2→1 / →0 一律 320ms（时长不随行程变 ⇒ 速度随方向变 ✗）
    //   · 松手收口·列表 →1 = 340ms（与把手同行程不同时长 ⇒ 快慢不一致 ✗）
    // ⇒ 修法（「同一套手感」= 同一条曲线 + 同一条速度 ms/px，时长按本次【实际行程】等比缩放）：
    //   ① 程序化开合（点胶囊 / 点「← 关闭」）= 900ms × 行程 ÷「0→1 跨度」（0→1 与 1→0 的行程
    //      完全相同 ⇒ 时长严格相等，展开基准 900ms 一字不动 ✓）；
    //   ② 松手收口（把手 fling / 列表 onPostFling、两个方向、两条路径同一入口）
    //      = 档位时长 × 行程 ÷「半屏→全屏跨度」；统一模式下档位默认 = STANDARD(480ms)（最佳档）。
    // 关掉本开关 = 逐字回到改动前（900/620/320 把手/340 列表、预设只作用于「半屏→全屏」）✓
    //   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //       --es name panelTimingUnified --ei value 0 -p com.liqglass.ultraclear   # 一行回退
    @Volatile var panelTimingUnified: Boolean = true

    /**
     * 【跟手取证·拖拽轨迹日志】把"手指移动事件 → p 变化"逐事件打出来（tag `LGDrag`）：
     *   t=<自手放下起的 ms> dy=<本次事件的 y 位移> y=<手指 y> p=<当时的进度>
     * 用途：量化"手指动了 → 面板跟着动了"的帧数差（跟手/延迟），两条拖拽路径都覆盖：
     *   ① 顶部把手（官方 anchoredDraggable）—— 由指针观察者打点；
     *   ② 内容滚动/空白区（嵌套滚动 onPostScroll）—— 在 dispatchRawDelta 处打点。
     * 默认 false（既不污染 logcat 也不占 IO）；打开方式：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name dragTrace --ei value 1 -p com.liqglass.ultraclear
     * ⚠️ 同时要求 AppDebugLog.enabled=true（P65 起调试广播不再强开日志 ⇒ 先 setUi debugLogEnabled 1 显式打开）。
     * ⚠️ 合成手势（adb input swipe）能驱动面板 p（实测 ✓），但它的事件时间与真手指不同 ⇒
     *    "真人手指跟手"仍须用户手指终验；本日志就是给那次终验准备的量尺 ✓
     */
    @Volatile var dragTrace: Boolean = false

    /**
     * 【P32 双卡重叠·静止卡冻结取证】逐帧打点开关（默认 false = 零日志、零行为改动）。
     *
     * 打开后每块卡的 effects lambda（= 该卡采样/离屏录制的刷新点）每次重跑打一行
     *   `P32 eff id=<state 身份哈希> x=<卡自身 offsetX> y=<offsetY> t=<ms>`
     * 拖动写入侧每个指针事件打一行 `P32 move id=… tx=… ty=…`。
     * 两行用 `id` 对齐即可判定：另一块卡移动时，这块卡有没有同帧刷新（= 订阅是否成对）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name dualCardTrace --ei value 1 -p com.liqglass.ultraclear
     */
    @Volatile var dualCardTrace: Boolean = false
    // ================= 【P13 列表→面板手势交接·门槛/阻尼（更多设置二级页）】 =================
    // 用户原话：「修【更多设置二级页的手势阻尼】两个问题——① 在该页【上滑展开到全屏面板】时阻尼太轻
    //   （不够"重"、手感虚）；② 从该页【下拉回到顶部】时太容易把【整个面板】拉回胶囊形态（误触收回）。」
    // 口径：上滑阻尼要变重且带【阈值 + 可调曲线】；下拉只有【列表到顶且继续下拉超过阈值/速度】才交接到
    //   面板收回，否则只滚列表；带默认开的回退开关。
    //
    // 【唯一交接点】ui/LiquidGlassScreen.kt 的 sheetConnection（列表 + 空白承接层共用的嵌套滚动连接）：
    //   改前：onPostScroll 里 `dispatchRawDelta(-available.y / DRAG_SENSITIVITY)`（1:1、无条件）——
    //     上滑 = 面板立刻 1:1 长高（没有阻力段 = 症状①"手感虚"）；下拉 = 面板 1:1 收缩、零阈值
    //     （轻微过冲就收回 = 症状②）；onPostFling 里无条件 settle ⇒ 列表到顶时手指残速/惯性直接进入
    //     面板收口判定（速度够就落到 0f 胶囊 = 症状②的第二条路径）。
    //   改后（本开关组；默认只在二级页生效）：
    //     ① 死区阈值：结余位移先吃掉阈值（上滑 8dp / 下拉 20dp），阈值内【面板不动、列表也不滚】= 抵抗感；
    //     ② 阻尼段：越过死区后按 k(已越过位移) 交接 —— 起始 k0（上滑 0.40 / 下拉 0.55）在爬升长度
    //        （160dp / 120dp）内平滑爬回 1.0 ⇒ 起步"重"，但保证一路拖得到 p=2 / 回得到 p=0（不饱和 ✗）；
    //     ③ 惯性不交接：手指已抬起、由 fling 驱动的结余位移（source == SideEffect）一律丢弃；
    //     ④ 松手门控：本次手势面板真被动过 ⇒ 照旧 settle；没动过 ⇒ 只有残速 ≥ 阈值才交接。
    // 作用域：默认只作用于【更多设置二级页】（panelAdvanced / panelImageEdit）；一级页（用户已认可的
    //   手感）逐字不变 ✓ —— 需要放开时用 panelEdgeGateAllPages 一键扩到所有页。
    //
    // 一行回退（逐字回到 1:1 无条件交接 + 无条件 settle）：
    //   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name panelEdgeGate --ei value 0 -p com.liqglass.ultraclear

    /**
     * 【P13 主开关】列表 → 面板手势交接的门槛/阻尼（默认 true = 新行为）。
     * false = 逐字回到改动前：onPostScroll 1:1 无条件交接 + onPostFling 无条件 settle（一行回退单元 ✓）。
     * 读取点在 sheetConnection 的回调内（非组合期）⇒ setSwitches 之后【下一次滚动 / 下一次松手】即生效。
     */
    @Volatile var panelEdgeGate: Boolean = true

    /**
     * 【P13 档位·轻】= 死区更小 / 起始移交率更高 / 爬升更短（阻尼最轻，最接近改动前手感）。
     * 两个档位开关都置 0（默认）= 标准档；都置 1 时按【重】档取（取更重的一档，语义可预期）。
     *   adb ... --es name panelEdgeGateLight --ei value 1   # 切轻档
     */
    @Volatile var panelEdgeGateLight: Boolean = false

    /**
     * 【P13 档位·重】= 死区更大 / 起始移交率更低 / 爬升更长（阻尼最重，"手感最实"）。
     *   adb ... --es name panelEdgeGateHeavy --ei value 1   # 切重档
     */
    @Volatile var panelEdgeGateHeavy: Boolean = false

    /**
     * 【P13 作用域放开】true = 门槛/阻尼对所有页生效（含一级基础菜单页）。
     * 默认 false = 只作用于【更多设置二级页】（panelAdvanced / panelImageEdit）—— 一级页（用户已认可的
     * 手感）逐字不变 ✓。放开范围只用这一行：
     *   adb ... --es name panelEdgeGateAllPages --ei value 1
     */
    @Volatile var panelEdgeGateAllPages: Boolean = false

    /**
     * 【P13 惯性不交接】true（默认）= 手指已抬起、由 fling 驱动的结余位移
     * （onPostScroll 的 source == NestedScrollSource.SideEffect；拖动 = UserInput —— 两个常量名按本工程
     * Compose foundation 1.12.0 的 jar 字节码核对过）一律不交接：列表惯性冲到顶/底时剩余位移被丢弃
     * ⇒ 面板不再被惯性拽走（= 用户说的"误触收回"的惯性来源）。
     * false = 惯性位移照旧走同一套门槛 / 阻尼（不再单独丢弃）。仅当 panelEdgeGate=true 时有效。
     *   adb ... --es name panelEdgeFlingNoHandover --ei value 0   # 一行回退
     */
    @Volatile var panelEdgeFlingNoHandover: Boolean = true

    /**
     * 【P13 档位表】门槛 / 阻尼曲线 / 残速阈值的唯一参数入口（集中在枚举，每个值写清出处）。
     *
     * 默认档 = STANDARD（两个档位开关都关）—— 参数出处 = 主控 Task P13 口径：
     *   · 上滑死区 ≈8dp、起始移交率 k≈0.40、约 160dp 爬回 1.0（口径②"上滑阻尼要变重"，默认值取口径值）；
     *   · 下拉死区 ≈20dp、起始 k≈0.55、约 120dp 爬回 1.0（口径③"下拉先过阈值 / 阻尼"，默认值取口径值）；
     *   · 松手残速阈值 ≈600px/s、上 / 下分开（口径⑤"故意甩一下要留住 / 轻碰就收回要杀掉"，默认取口径值）。
     * 量纲：dp 值 × 屏幕密度 → px（emulator-5554 = 320dpi ⇒ density=2.0 ⇒ ×2.0）。
     * 轻 / 重两档是围绕标准档的对称微调（更接近改动前 / 更重），供用户实机选档。
     * 曲线形状（三档共用）：deadZone 内 k=0；之后 t=clamp(over/ramp,0,1)、k = k0 + (1-k0)·smoothstep(t)
     *   （起步平缓、到爬升末尾平滑接回 1.0 ⇒ 不会出现斜率突跳）。
     * 终点可达性：爬升长度有界 ⇒ k 必然回到 1.0 ⇒ 仍能一路拖到 p=2 / 回得到 p=0 ✓（不饱和 ✗）。
     */
    enum class PanelEdgeGatePreset(
        val label: String,
        /** 上滑（展开方向）死区阈值（dp）：结余位移先吃掉这么多，面板才开始动。 */
        val expandDeadZoneDp: Float,
        /** 上滑阻尼段起始移交率 k0（0..1）：刚越过死区时只有 k0 的手指位移交给面板。 */
        val expandRate0: Float,
        /** 上滑阻尼段爬升长度（dp）：越过死区后再走这么远，移交率平滑爬回 1.0。 */
        val expandRampDp: Float,
        /** 下拉（收回方向）死区阈值（dp）。 */
        val collapseDeadZoneDp: Float,
        /** 下拉阻尼段起始移交率 k0。 */
        val collapseRate0: Float,
        /** 下拉阻尼段爬升长度（dp）。 */
        val collapseRampDp: Float,
        /** 展开方向（上滑）松手残速阈值（px/s）：未 arm 时只有 ≥ 该速度才交接。 */
        val expandFlingThresholdPxPerSec: Float,
        /** 收回方向（下拉）松手残速阈值（px/s）。 */
        val collapseFlingThresholdPxPerSec: Float,
    ) {
        LIGHT("轻", 4f, 0.60f, 100f, 12f, 0.75f, 80f, 500f, 500f),
        STANDARD("标准", 8f, 0.40f, 160f, 20f, 0.55f, 120f, 600f, 600f),
        HEAVY("重", 14f, 0.25f, 220f, 30f, 0.40f, 160f, 800f, 700f),

        // ==================== 【P43 中间档·默认】 ====================
        // 用户真机反馈：「更多设置里面下拉阻尼过大」——P13 标准档的【下拉】侧（死区 20dp +
        //   起始移交率 0.55 + 120dp 才爬回 1:1）在真机上被感受为"发硬、拉不动"。
        // 修法：只放松【下拉（收回）方向】的三项；【上滑（展开）方向】逐字保持 P13 标准档
        //   （那一侧是 P13 依用户口径"上滑阻尼要变重"调过的，本轮不动 ✗）。
        //   · 死区 20dp → 12dp（320dpi 只吃 24px；仍保留"微过冲不动面板"的抗误触语义）；
        //   · 起始移交率 0.55 → 0.72（起步就跟手：实际拖 100px 面板走 ~76px，改前只走 ~59px）；
        //   · 爬升长度 120dp → 100dp（12+100=112dp 回到 1:1，改前是 140dp）。
        // 数值位置：短拖移交比 ≈0.90（P13 标准 0.72 / 改动前 1.00 的正中间；见报告移交比表）。
        // 抗误触的【真正主力】一条没动：① 惯性位移不交接（panelEdgeFlingNoHandover）；
        //   ② 松手门控（未 armed 时只有残速 ≥ 600px/s 才交接）；③ 死区仍在（12dp）。
        MIDDLE("中间（P43 默认）", 8f, 0.40f, 160f, 12f, 0.72f, 100f, 600f, 600f),

        // ==================== 【P56②·全屏段·中间档轻端】 ====================
        // 用户口径：『更多设置全屏态下拉阻力过大（与半屏态对齐到中间档）』+ 父会话验收口径
        //   「after 的 rates 起手必须到 0.85~0.95 一带、与改前明显不同」。
        // 现状实测（逐帧 LGDrag，全屏 p=2 起手的收回）：与半屏段同走 MIDDLE 档 ⇒ 起手
        //   rate 只有 0.72/0.78（改前逐值 [0.72,0.78,0.88,0.97,1.0…]）——用户在全屏态仍嫌重。
        // 本档（只被 panelEdgeGateFullscreenMid 用于「全屏段起手的收回方向」）：
        //   · 死区 12dp → 8dp（320dpi 吃 16px；抗误触语义保留）；
        //   · 起始移交率 0.72 → 0.85（起手一跳进 0.85~0.95 带：实测首事件 0.88、次 0.91）；
        //   · 爬升长度 100dp → 80dp（8+80=88dp 回到 1:1）。
        // 半屏段 / 上滑（展开）方向 / 松手阈值 / 惯性不交接 —— 一字不动 ✓。
        MIDDLE_FS("中间（P43 默认）·全屏轻端", 8f, 0.40f, 160f, 8f, 0.85f, 80f, 600f, 600f),
    }

    /**
     * 【P43】true = 显式切回 P13 标准档（下拉死区 20dp / k0 0.55 / 120dp）——给 A/B 与回退用。
     * 默认 false ⇒ 生效档 = MIDDLE（中间档）。与 Light/Heavy 三者的优先级见 [panelEdgeGatePreset]。
     */
    @Volatile var panelEdgeGateStd: Boolean = false

    /**
     * 当前生效档位（【P43】默认 = 中间档 MIDDLE；Light/Heavy 显式选档优先，两个都置 1 取更重的；
     * `panelEdgeGateStd=true` 一键切回 P13 标准档 —— A/B 与回退用）。
     */
    val panelEdgeGatePreset: PanelEdgeGatePreset
        get() = when {
            panelEdgeGateHeavy -> PanelEdgeGatePreset.HEAVY
            panelEdgeGateLight -> PanelEdgeGatePreset.LIGHT
            panelEdgeGateStd -> PanelEdgeGatePreset.STANDARD
            else -> PanelEdgeGatePreset.MIDDLE
        }

    // ================= 【收回速度限幅·2026-09-14 用户批准追加】 =================
    // 用户原话：「控制中心下拉收回的时候，如果手指运动轨迹非常快，还是会有概率丢动画。你排查一下，
    //   要不要给控制中心的回缩速度加个上限。」→ 主控排查后用户答复：「加，你协调一下」✓
    //
    // 机制（主控已定位，勿重推）：手柄松手后的收口走 Compose 官方 performFling ✓ 它直接拿【手指原始速度】
    //   决定落点与位移轨迹 ✗ ⇒ 极大速度下衰减段一帧就跨掉大部分距离 ⇒ 1~2 帧跑完 = 肉眼"跳回去" ✗✓
    // 修法：在 fling 入口对传入 performFling 的速度做【限幅】——只限【收回方向】（速度 < 0，
    //   即 p 变小/下拉），开启方向不限（否则"用力上滑直达全屏"的手感会变 ✗）。
    // 档位：setSwitches 只支持 Boolean（浮点开关不行 ✗）⇒ 用两个布尔选三档 ✓
    //   · 标准（默认，用户要求启用）：1500 dp/s
    //   · 宽松（更接近原手感）：3000 dp/s
    //   · 保守（最不容易丢动画）：800 dp/s
    // 标定：`LGVel` 日志会打出【原始速度 / 限幅后速度 / 起始 p】，以及收口结束的【落点 p / 耗时 ms】⇒
    //   可用真人手指的真实速度分布来收紧/放宽档位 ✓（合成手势拖得动但速度分布与真人不同 ✗）

    /** 收回方向（下拉）的速度上限档位；`dpPerSec` 会按屏幕密度换算成 px/s 再限幅。
     *  OFF = 不限幅（原始行为，最小回退单元 ✓；用 coerce 到 Float.MAX_VALUE 实现 ⇒ 等价于不作用 ✓）。 */
    enum class PanelCloseVelocityCap(val label: String, val dpPerSec: Float) {
        STANDARD("标准", 1500f),
        LOOSE("宽松", 3000f),
        CONSERVATIVE("保守", 800f),
        OFF("关（原始行为）", Float.MAX_VALUE),
    }

    @Volatile var panelCloseCapLoose: Boolean = false
    @Volatile var panelCloseCapConservative: Boolean = false
    /** true = 完全关闭限幅（回退到改动前的原始行为）。 */
    @Volatile var panelCloseCapOff: Boolean = false

    /** 当前生效的回缩限幅档（默认 = 标准档 = 启用限幅 ✓，用户已批准；`panelCloseCapOff=true` 一键回退 ✓）。 */
    val panelCloseVelocityCap: PanelCloseVelocityCap
        get() = when {
            panelCloseCapOff -> PanelCloseVelocityCap.OFF
            panelCloseCapConservative -> PanelCloseVelocityCap.CONSERVATIVE
            panelCloseCapLoose -> PanelCloseVelocityCap.LOOSE
            else -> PanelCloseVelocityCap.STANDARD
        }

    /** 当前生效档位（两个开关都关 = 现状 ✓ —— 默认保持现状，代理不擅自改默认 ✗）。 */
    val panelTimingPreset: PanelTimingPreset
        get() = when {
            panelTimingRelaxed -> PanelTimingPreset.RELAXED
            panelTimingStandard -> PanelTimingPreset.STANDARD
            else -> PanelTimingPreset.CURRENT
        }

    /**
     * 【动画一致性】统一模式下的生效档位：两个档位开关都关时默认 = STANDARD（标准档）——
     * 依据：用户反馈「半屏→全屏的动画有点太快了」⇒ 现状档（320ms）不是最佳档，
     * 逐帧实测（LGLayout 时间线：320ms 走完 1266px = 2.38× 已认可的点击展开速率，
     * 480ms = 1.59× ⇒ 最接近开场节奏、又不至于让收口比开场还慢）。
     * `panelTimingUnified=false` 时本 getter 返回现状档（320ms）⇒ 与 `panelTimingPreset` 逐字相同，
     * 即"一行关掉一致性开关 = 逐字回到改动前"✓（不需要单独改档位开关）。
     */
    val panelTimingPresetUnified: PanelTimingPreset
        get() = when {
            panelTimingRelaxed -> PanelTimingPreset.RELAXED
            panelTimingStandard -> PanelTimingPreset.STANDARD
            panelTimingUnified -> PanelTimingPreset.STANDARD
            else -> PanelTimingPreset.CURRENT
        }

    // ================= 【逐帧时间戳日志（帧率分析仪）】 =================
    // 依据（NEXT.md 方法铁律）：动画/时序类验收要用【逐帧日志】而不是录屏提帧；且现有两个"乐器"不够用
    //   · LGLayout 只在布局跑且 (w,h) 变化时打一行 ⇒ 不动布局的帧没记录 ⇒ 算不出真实 FPS ✗
    //   · 应用内看板 PerformanceMonitor 是窗口快照（同一工作量给 fps=7 / 25 两种读数）✗
    // 实现：performance/FrameTimelineLogger.kt（Choreographer.postFrameCallback + 预分配环形缓冲 CAP=1200）
    // 用法：DebugBridge 的 frameTimeline 命令（start / stop / export / status，导出 files/frametimeline_<session>.csv）

    /**
     * 【帧率分析仪·总开关】默认 **false**（关闭时零开销：没有回调、没有数组写入）。
     *
     * true 之后用 `frameTimeline --es action start` 开始采集；采集途中把它置回 false ⇒ 下一帧自动停止。
     * 开关本身走 setSwitches 反射写入（新增字段无需改桥）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name frameTimeline --ei value 1 -p com.liqglass.ultraclear
     */
    @Volatile var frameTimeline: Boolean = false

    /**
     * 【帧率分析仪·异常帧实时打点】默认 **false**（"可选只写异常帧"通道）。
     *
     * true = 每出现一个"间隔 > 一个 vsync 周期"的帧，就往 AppDebugLog 写一行
     * `FT  jank idx=… deltaMs=… targetMs=… p=…`（便于边跑边在 logcat/导出的应用日志里看掉帧）。
     * 为什么默认关：高刷屏（120/165Hz）的一个 vsync 周期只有 8.33/6.06ms，重负载动画期间
     * "超一个 vsync"可能**每帧都成立** ⇒ 打开会逐帧做字符串格式化 + 环形缓冲写入，既刷屏又轻微扰动
     * 被测对象本身。完整数据永远在 CSV 里（export 时全量落盘）⇒ 需要边跑边看时才打开它。
     */
    @Volatile var frameTimelineLogJank: Boolean = false

    // ================= 【P18 · 官方 JankStats 监视器】 =================
    // 方向依据：NEXT.md【JankStats（官方掉帧库）】；本波只做**并存**（不替换自写逐帧日志 ✗）。
    // 实现：performance/JankStatsMonitor.kt（JankStats.createAndTrack(window, listener)）。
    // 与自写逐帧日志（frameTimeline）并存不冲突：两者互不引用；唯一共用点 = DebugBridge 命令。
    // 口径（1.0.0 官方源码核实）：isJank = UI 时长 > 期望时长 × 2.0；API31+ 期望 = FrameMetrics.DEADLINE。
    // 开 = 立即 start（MainActivity 订阅 DebugBridge.revision 后同步）、关 = stop（摘 listener，不泄漏）。
    // 默认 **false** ⇒ 一键回退：零开销（无 listener、无线程、无回调）。
    @Volatile var jankStatsMonitor: Boolean = false

    // ================= 【主动请求 120Hz · 运行时 60/120 开关】(2026-09-14 用户要求) =================
    // 实现在 performance/RefreshRateController.kt（三条通道：View#setRequestedFrameRate API 35+ /
    // 窗口 Surface 的 Surface#setFrameRate API 30+ / LayoutParams.preferredRefreshRate 全版本）；
    // 调用点 = MainActivity：setContent 的 LaunchedEffect（订阅 DebugBridge.revision）+ onWindowFocusChanged。

    /**
     * 【120Hz 请求开关】默认 **120Hz**（用户明确指定）；置 0 = 请求 60Hz（A/B 对照档）。
     *
     * 两档都是【显式请求】(120 或 60)，不是"清除请求" —— 用户要在同一开关上真看出两档差别；
     * 若 60 档只"不请求"，系统自身可能就停在高刷上 ⇒ 看不出差别 ✗。
     *
     * 【日志（请求值 vs 实际节拍）】每次应用/翻转后打两行，tag `LGRefresh`（logcat 与应用内日志同源）：
     *   `LGRefresh requested=120.0 switch=120Hz display=60.0 mode=60.0 supported=[60] view=ok surface=ok attrs=ok`
     *   `LGRefresh cadence requested=120.0 switch=120Hz display=60.0 cadenceFps=60.0 meanFps=50.0 frames=121 durMs=2400 maxDeltaMs=150.00`
     *   （cadenceFps = 中位帧间隔的倒数 = 稳态节拍；meanFps = 区间均值；两者都会受 App 自身渲染能力限制 ——
     *   模拟器上玻璃管线本身只有 13~30fps ⇒ 那才是"实际节拍"的真实读数 ✓）
     *   模拟器只有 60Hz 单模式 ⇒ `requested=120.0 …` 是正确读数（证明两值可分）；
     *   真实 120 节拍只能在 120Hz 设备（平板）上看到 —— 平板实测待用户回来。
     *
     * 【翻转（一行；无需重启）】
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name refresh120Hz --ei value 0 -p com.liqglass.ultraclear   # 0=请求60 / 1=请求120
     *   （翻转后 DebugBridge.revision 自增 → MainActivity 的 LaunchedEffect 立即重设请求 ✓）
     * 【跨进程初值·锁定 60 档】@Volatile 开关不跨进程；模拟器上冷启动期广播还可能丢/被并发 force-stop
     *   打断 ⇒ 60 档另有一条【落盘初值】通路（与 shapeNormalSameSource 同一套 readSwitchFile 机制）：
     *     锁定 60：run-as com.liqglass.ultraclear sh -c 'mkdir -p files; echo 1 > files/lg_refresh_60'
     *     恢复 120：run-as com.liqglass.ultraclear sh -c 'rm -f files/lg_refresh_60'
     *   （之后 force-stop + am start；文件不存在时默认 = 120Hz ✓ = 用户要求）
     * 【回退（彻底回到改动前）】工程层面 = 撤掉 MainActivity 的两处调用（setContent 的 LaunchedEffect +
     *   onWindowFocusChanged）即恢复"从不请求"的旧行为；运行层面 = value 0 即 60Hz 档。
     */
    @Volatile var refresh120Hz: Boolean = !readSwitchFile("lg_refresh_60")

    // ================= 【HDR 图片支持（Ultra HDR JPEG / SDR 底图 + gain map）· 2026-09-14】 =================
    // 集成点：背景/壁纸管线（内置实拍壁纸 bg_*.jpg + 用户自选图片）→ 既有「HDR 窗口
    //   (ActivityInfo.COLOR_MODE_HDR，见 MainActivity) + 自绘 AGSL 玻璃管线」。实现与自检见
    //   hdr/HdrImageSupport.kt；官方依据/降级矩阵/回退方法见 NEXT.md「HDR 图片支持」一节。
    //
    // 语义（任何一条不满足 ⇒ 自动 SDR 降级 = 旧解码路径，逐像素不变、绝不崩）：
    //   · 开关 hdrImageWallpaper=false；· 系统 < API 34（无 android.graphics.Gainmap）；
    //   · 图里没有 gain map（普通 JPEG/PNG/HEIC）；· 屏幕无 HDR 能力（Display.isHdr()==false，如模拟器）；
    //   · 手动套用异常/超像素上限 → 回底图。

    /**
     * 【HDR 图片·主开关】背景/壁纸是否启用「Ultra HDR 解码 → gain map → HDR 输出」（默认 true）。
     *
     * true = API≥34 且屏幕具备 HDR 能力时，用 ImageDecoder 取 gain map，按 `Gainmap` 类 javadoc
     *   《Applying a gainmap manually》的官方公式手动套用 → 输出 RGBA_F16/EXTENDED_SRGB 位图
     *   （带 >1.0 的 HDR 余量）交给既有管线（官方另注：硬件加速 Canvas 在 COLOR_MODE_HDR 窗口里
     *   会【自动】套用 gain map —— 那是另一条路线；本项目自绘管线用显式手动套用，两条互斥不叠加）。
     * false（一行回退）= 完全旧行为（BitmapFactory 解码，忽略 gain map）。
     *
     * 【跨进程】@Volatile 字段不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_hdr_image_off`（内容 "1" = 关），
     * 用于「改开关 + 重启」式 A/B；运行中直接翻：`--es cmd setSwitches --es name hdrImageWallpaper --ei value 0|1`
     * （下一次壁纸解码生效：重启应用，或用 setUi bgBuiltinIndex 触发重新解码）。
     */
    @Volatile var hdrImageWallpaper: Boolean = !readSwitchFile("lg_hdr_image_off")

    /**
     * 【调试·强制 HDR 通路（模拟 HDR 屏）】默认 false（文件标志 `files/lg_hdr_image_force`）。
     *
     * true = 即使屏幕不具备 HDR 能力（模拟器等 SDR 屏）也走一遍 HDR 通路，用【模拟 HDR/SDR 亮度比 4×】
     * 计算公式权重（真机不需要它）。用途：在模拟器上验证「gainmap 检测 → 手动套用公式 → F16 输出」
     * 会真的改变像素（截图 A/B 可判），以及日志打出「HDR 通路生效」。
     * 注意：SDR 屏上这属于调试行为（官方语义下无 HDR 余量时应恒等 = 底图），真机 HDR 屏不受影响。
     */
    @Volatile var hdrImageForceOn: Boolean = readSwitchFile("lg_hdr_image_force")

    // ================= 【图片编辑二级菜单（官方 ACTION_EDIT）· 2026-09-14】 =================
    // 集成点：ui/GlassControlsPanel.kt 的二级页「图片编辑」（与「更多设置」同级）+ ui/LiquidGlassScreen.kt
    //   的面板二级页接线（panelImageEdit）。把「背景壁纸」的图片编辑类动作收进一个独立二级菜单：
    //   选择/更换图片 · 图片编辑…（官方 `Intent(ACTION_EDIT)` + `setDataAndType(uri,"image/＊")` +
    //   FLAG_GRANT_READ_URI_PERMISSION|WRITE）· 内置简单操作（顺时针旋转 90°）· 写入相册 · 缩放/偏移。
    //
    // 【降级矩阵】任何一条不满足 ⇒ 走内置简单操作，绝不崩，日志走 logcat/应用日志 tag `LGImageEdit`：
    //   · 开关 imageEditMenu=false（一级页不出现入口，面板二级页只剩「更多设置」）；
    //   · 无 ACTION_EDIT 处理者（模拟器实测：Google 相册 EditActivity 在 ⇒ 官方编辑器；
    //     把它 `pm disable-user` 掉之后 startActivity 抛 ActivityNotFoundException ⇒ 降级 ✓）；
    //   · API 30+ 包可见性过滤（本应用不改 Manifest ⇒ 无 <queries>）会污染 resolveActivity 的结果
    //     ⇒ 解析行只作参考，判定以 startActivity 的实测结果为准；
    //   · 拿不到可外发的 content:// URI（既无本次 SAF 原图、写入相册也失败）。
    // 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_image_edit_off`（内容 "1" = 关）。
    // 【回退】一行：默认值改 false；运行中：`--es cmd setSwitches --es name imageEditMenu --ei value 0`
    //   ⇒ 与改动前逐像素一致（入口按钮不进组合）✓

    /** 【图片编辑二级菜单·主开关】默认 true（用户点名要的功能，默认开 ✓）。 */
    @Volatile var imageEditMenu: Boolean = !readSwitchFile("lg_image_edit_off")

    // ================= 【P12·邻近流体融合（备选方案 B）· 2026-09-14】 =================

    /**
     * 【邻近流体融合·总开关】默认 true（用户点名要的功能，默认开 ✓）。
     *
     * true = 两块玻璃卡靠近时按 ProximityFusion 的三参数做「浸润融合 / 拉丝断裂」
     *   （融合区仍是玻璃本体：折射/菲涅尔/背景采样全部复用现有管线）；
     * false = 完全回退：不跑融合驱动、融合 uniform 全 0（AGSL 里融合段整体跳过）
     *   ⇒ 单卡、两卡远离、两卡靠近三种情形都与改动前逐像素一致。
     *
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_fusion_off`（内容 "1" = 关）。
     * 【一行回退】运行中：`--es cmd setSwitches --es name proximityFusion --ei value 0`；
     *   或写 `files/lg_fusion_off`=1 后重启。三参数（float）见 ProximityFusion + `setFusion` 命令。
     */
    @Volatile var proximityFusion: Boolean = !readSwitchFile("lg_fusion_off")

    // ================= 【玻璃风格拉杆（iOS 27 式跟随拉杆）· 2026-09-14】 =================
    // 用户原话：「把【玻璃风格】改成 iOS 27 那种跟随拉杆变化——拉杆最左=现在的通透档，
    //   拉杆最右=现在的磨砂档，默认中间=均衡档。」
    // true（默认）= 一级页「玻璃风格」= 一根连续拉杆（复用既有 ParameterSlider，胶囊手柄 66×34dp、
    //   按压不做任何动画）+ 左中右三档刻度标签「通透/均衡/磨砂」；默认位置恒 = 中位（均衡档）。
    // false（一行回退）= 一级页恢复【三个预设 chip】旧交互，且冷启动回「通透」档（= 改动前行为）。
    // 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_glass_style_slider_off`
    //   （内容 "1" = 关；与 imageEditMenu / refresh120Hz 同一套 readSwitchFile 机制）。
    // 【运行中翻转】`adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches
    //   --es name glassStyleSlider --ei value 0 -p com.liqglass.ultraclear`
    //   （一级页在组合期读取本开关，并陪读 DebugBridge.revision ⇒ 翻开关立即重绘 ✓）
    @Volatile var glassStyleSlider: Boolean = !readSwitchFile("lg_glass_style_slider_off")

    // ================= 【P22·玻璃矩形对齐（卡片盒 = 玻璃可见矩形）· 2026-09-14】 =================
    // 真 bug（像素实测，见 P22 取证）：玻璃【可见矩形】= 卡片盒 - (8dp,19dp)（@320dpi = 16,38px）——
    //   玻璃元素整体比卡片盒左上偏 (16,38)px，而 dumpState 的 card rect / 布局盒都按"卡片盒"报，
    //   ⇒ 报出来的盒子与屏幕上的玻璃差 (16,38)px（P06 多卡重叠/精确对齐会立刻露馅 ✗）。
    // 机制（勿重推）：ui/LiquidGlassCard.kt 里玻璃层 = constraints + pressOv*2 且 place(-ovX,-ovY)，
    //   而 Shader 的可见形状锚在【玻璃层自己的原点】⇒ 可见矩形 = 玻璃层原点 + 卡片尺寸 = 卡片盒 - (ovX,ovY)。
    // true（默认，新）= 把坐标系在 ui/ 侧对齐回来：布局落点/Bridge 报出的矩形整体搬到"玻璃可见矩形"
    //   （mainDefaultTopLeft/secondDefaultTopLeft/extraDefaultTopLeft 各减 (8dp,19dp)），
    //   同时卡片内部把玻璃层与内容层各补回同样的量（玻璃层 place(ovX_rest-ovX, ovY_rest-ovY)、
    //   内容层 +（8dp,19dp)）⇒ 屏幕上玻璃的位置/大小与折射/按压观感【逐像素不变】✓
    //   ⇒ 修后静止态：dumpState rect == 像素实测玻璃边界（四边偏差 ≤2px ✓✓）。
    // false（一行回退）= 全部退回改动前行为：落点 = 原 0.14×屏高 口径、玻璃层 place(-ovX,-ovY)、
    //   内容层不补、CARD_ORIGIN 不补 ⇒ 逐像素等于改动前 ✓（运行中翻转即生效：布局/放置期都陪读
    //   DebugBridge.revision ✓）。
    /** 【P22·玻璃矩形对齐】开关（默认开）。 */
    @Volatile var glassRectAlign: Boolean = true

    // ================= 【P03·四角色散（折射正确性）· 2026-09-14】 =================
    // 背景：卡片色散被隔离实验写死为 0（GlassParameters.mapping）⇒ 用户记忆里的"色散只在四角"在代码里不存在。
    //   本开关是【补实现】（历史四角公式溯源：提交 455f465 的 |x·y|/(hw·hh) 变体）。
    // false（默认）＝ 一键回退：色散参数保持现值（0）⇒ 逐像素与改动前一致 ✓。
    // true ＝ 卡片启用【四角加权色散】：强度 0.20 × 带 8dp（偏移 1.6dp）。
    //   注入点①参数派生 = GlassParameters.mapping；②uniform 行值 = BackdropAdapter.Modifier.glass 录制块
    //   （录制块内读本开关 + 陪读 revision ⇒ setSwitches 翻转即刻重录生效，无需重启）。
    //   AGSL 侧四角权重由 cornerDispersionGain 门控（glass/GlassShaders.kt 色散段）；
    //   面板/折叠胶囊/张力桥路径固定 gain=0 ⇒ 那些元素逐像素不变 ✓。
    //   【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 files/lg_corner_dispersion_on（内容 "1" = 开，
    //     照 readSwitchFile 先例；默认无此文件 ⇒ 冷启动恒为 false）。Golden compare 的 ON 态靠它保持
    //     （compare 脚本会 force-stop 重启 App 多轮，@Volatile 会丢 —— 见 ~/.hermes/scripts/glass_baseline.py）。
    @Volatile var cornerDispersion: Boolean = readSwitchFile("lg_corner_dispersion_on")

    // ================= 【P28a·批 1 液态组件演示（Button / Toggle）· 2026-09-14】 =================

    /**
     * 【P28a·组件演示·主开关】默认 true（用户点名要的功能，默认开 ✓）。
     *
     * true  = 控制中心一级页「背景壁纸」chips 行末尾追加一个「组件演示 ›」chip
     *         （与「图片编辑 ›」同构：入口只在一级页），进入二级页后渲染上游 catalog 移植的
     *         LiquidButton / LiquidToggle（batch 1），可点、可按、可拖。
     * false = 该 chip 与二级页内容【都不进组合】/ 状态复位 ⇒ 与改动前逐像素一致（一行回退 ✓）。
     *
     * 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name liquidComponentsDemo --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var liquidComponentsDemo: Boolean = true

    /**
     * 【P28a·手感 A/B 开关】默认 false = 用【本工程基准】弹簧参数（用户口径：现状手感刚好）。
     *
     * false（默认）= LiquidHandFeel.Baseline：值 d=0.78/k=380（= BALANCED 卡片回弹弹簧）、
     *   按压 d=0.42/k=820（= 收起按钮 blockPressProgress）、缩放 d=0.40/k=950（= blockPressScale）、
     *   拖动 1:1 跟手（= 本工程卡片/面板拖动语义）。
     * true = LiquidHandFeel.Upstream：上游 catalog 原值（值 d=1.00/k=1000 且拖动为"弹簧追手指"、
     *   按压 d=1.00/k=1000、缩放 d=0.60/0.70@k=250）。
     * ⇒ 用户在【组件演示】页可即时切换、亲手对比两组手感（同一开关也作用于批 2 的 Slider/BottomTabs）。
     *
     * 【切换命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name liquidDampingUpstream --ei value 1 -p com.liqglass.ultraclear
     * 【注意】本开关只影响【新移植的液态组件】，不影响本工程既有卡片/面板/控制中心手感 ✗。
     */
    @Volatile var liquidDampingUpstream: Boolean = false

    /**
     * 【P28a·手感取证】逐帧打点（logcat tag = LGLiq），默认 false（零日志零开销）。
     *
     * 打开后，移植组件的每一帧拖动/回弹都会打印：
     *   参数组 · 阶段(down/drag/up/cancel/settle) · t(ms) · 手指累计位移(px) · value · target ·
     *   跟手滞后(px) · 速度(值/秒) · 按压进度 · 缩放 X/Y
     * ⇒ 同一手势在「本工程基准」与「上游原值」下各跑一次，即可得到逐帧位移/滞后序列（验收数据）。
     */
    @Volatile var liquidHandFeelTrace: Boolean = false

    /**
     * 【P28b·批 2 色散 A/B 对照档】只作用于【批 2 新移植的两个组件】（LiquidSlider 旋钮、
     * LiquidBottomTabs 指示胶囊）里那一句 `lens(…, chromaticAberration = true)`。
     *
     * false（默认）= 上游原值：lens 带色散（chromaticAberration = true）——与上游 catalog 逐字一致 ✓
     * true（对照档，默认关）= 关掉色散（纯 lens 无 RGB 分离）⇒ 做【同一状态、只切这一个开关】的
     *   像素 A/B，用来给"色散存在性"出量化证据（差异只应落在旋钮/指示胶囊的折射带内）。
     *
     * 为什么用"关"的开关而不是"开"的开关：默认值必须 = 上游行为（色散本来就是上游原值的一部分），
     *   开关本身默认关 ⇒ 翻它才有意义（不翻 = 逐像素上游行为）。
     *
     * 【切换命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name liquidSliderNoDispersion --ei value 1 -p com.liqglass.ultraclear
     * 【范围】✗ 不影响批 1 的 Button/Toggle（它们的 lens 调用一字未动），✗ 不影响本工程卡片/面板。
     */
    @Volatile var liquidSliderNoDispersion: Boolean = false

    // ================= 【P50 · 控件拖动屏蔽 Y 轴（真机反馈：拖控件时控制中心跟着变小）】 =================
    /**
     * 【P50·控件拖动屏蔽 Y 轴】Liquid 控件（滑杆旋钮 / 开关旋钮 / 底栏旋钮）拖动期间，
     * 把指针位移标记为「已消费」，阻断垂向分量上抛到面板与列表。
     *
     * 用户真机反馈：「拖动玻璃风格拉杆 / 底栏旋钮时，控制中心（面板或胶囊）会同步变小」
     *   —— 用户更正方向：「应该是摇杆滑动的时候没有屏蔽 y 轴操作」。
     * 根因：P45/P46 换成上游移植组件后，控件的拖动循环【只喂自己、从不 consume】⇒ 手指的垂向
     *   分量对祖先依旧可用 ⇒ 列表滚到尽头后剩余位移沿 nestedScroll 交给面板 sheetConnection
     *   ⇒ dispatchRawDelta 改面板 offset ⇒ p 变小。改前的自绘滑杆（ParameterSliderLegacy）
     *   当年就 `change.consume()`（防误触注释）⇒ 这是换组件时丢掉的保护。
     * 实现：`ui/components/liquid/DragYShield.kt` 的 `Modifier.liquidDragYShield()`，
     *   挂在 LiquidSlider / LiquidToggle / LiquidBottomTabs 的拖动节点【最外层】。
     *
     * true（默认）= 消费（Y 轴屏蔽生效）；false（一行回退）= 本节点整段不消费
     *   ⇒ 逐字回到改动前（Y 轴照旧漏给面板/列表）✓
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidDragYShield --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_drag_yshield_off`
     *   （内容 "1" = 关；与 glassStyleSlider / liquidRealControls 同一套 readSwitchFile 机制）。
     * 【范围】✗ 不动 glass 包的折射默认参数、✗ 不动面板几何（p 驱动的那套）、✗ 不动交棒区，
     *   ✗ 不改上游 DragGestureInspector / DampedDragAnimation / InteractiveHighlight 的逻辑。
     */
    @Volatile var liquidDragYShield: Boolean = !readSwitchFile("lg_drag_yshield_off")

    /**
     * 【P34·组件演示页的采样源开关】用户真机反馈：「开关和滑杆按上去都没有变成玻璃」
     * —— 根因：演示页此前把【面板自己的捕获层】（adapter.captureLayer = 整页背景捕获层）
     * 直接透传给组件当采样源；面板是捕获层的【下游兄弟】（面板不进捕获层），组件又画在面板里
     * ⇒ 组件采到的是"面板背后那张壁纸"，与组件下面真正可见的东西（面板表面 + 演示页背景）
     * 不是一回事：按下时只有形状/白底透明度变化，看不见"背景被扭出边缘"的折射感 ✗。
     *
     * true（默认）= 演示页自建一块【可采样的演示背景层】（色带 + 校准网格，见
     *   LiquidComponentsDemo.drawDemoChart），组件采样它 ⇒ 按下/拖动时看得见折射、
     *   模糊、Ambient 高光、内阴影与滑杆旋钮的椭圆→胶囊形变（与上游 catalog 演示页
     *   用 rememberLayerBackdrop() + Modifier.layerBackdrop(backdrop) 包住背景的做法同构）。
     * false = 逐像素回到旧行为（直传面板捕获层）⇒ 同一状态、只切这一个开关即可做
     *   「玻璃有没有出来」的 A/B 像素对照，也是本改动的一行回退。
     *
     * 【切换命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name liquidDemoBackdrop --ei value 0 -p com.liqglass.ultraclear
     * 【范围】只作用于「组件演示」二级页；✗ 不动 vendored backdrop 模块（其全部文件）、✗ 不动面板/胶囊几何、
     *   ✗ 不动其它二级页。总开关 liquidComponentsDemo=0 时本页不进组合 ⇒ 与本开关无关。
     */
    @Volatile var liquidDemoBackdrop: Boolean = true

    // ================= 【P38·「组件演示」入口可达性 + 演示场形态 · 用户反馈「还是没应用成功」】 =================
    // 用户真机反馈原话：「上游的玻璃控制组件还是没有应用成功」。
    // 逐条取证后的判定（emulator-5554/5556，uiautomator dump + 整屏截图 + 拖动试验 + 布局探针）：
    //   ① 入口根本看不见（主因）：旧入口是「背景壁纸」chips 行【末尾】的 chip，而本行是【横向
    //      滚动】行 ⇒ 末尾 chip 被视口裁掉，实测节点 bounds=[1736,2341][1776,2405]、文字节点
    //      =[1768,2353][1776,2393]（只剩 8px 宽的一条）✗ —— 用户看不到入口 ⇒「没应用成功」✗；
    //   ② 页面形态不像上游：上游 catalog 演示页（BackdropDemoScaffold）是【一整块干净底 + 居中
    //      一列组件】；我们此前是「面板里的长列表 + 大段说明 + 六段色带/24dp 网格调试图底」✗；
    //   ③ 半屏 p=1 时【页首被裁】：二级页内容槽高被钉在 p=2 档（2885px），而 p=1 面板只有 1619px
    //      ⇒ 内容容器比面板高时被【垂直居中】摆放（P38L 探针实测 contentBox top=692 = 面板中心
    //      2134.5 − 2885/2）⇒ 页首 633px（表头 + 页面第一屏）落到面板上缘之外、且拖不回来 ✗
    //      （演示页表现为「两个 LiquidButton 段整段不见」；对照：一级页槽高 1619 = 面板高 ⇒
    //       实测 top=1325 = 面板顶 ✓ 完全可见）。
    // 修法（三个默认开的开关，各自一行可关）：
    //   · liquidDemoEntryHeader=true ⇒ 入口从「横向滚动 chips 行末尾」移到【面板表头标题行右侧
    //     空白处】（显式按钮；表头不在滚动区、不裁、不动任何既有元素坐标 ✓）；
    //   · liquidDemoStage=true       ⇒ 演示页改为【独立演示场】：干净渐变底座 + 上游同款尺寸/间距的
    //     居中组件列，紧凑到 p=1 半屏即可整场看到（不靠面板滚动）✓；
    //   · liquidDemoStageSlot=true   ⇒ 本页内容槽高取 p=1 档（与一级页同档）⇒ 槽 ≤ 面板 ⇒ 顶边
    //     贴面板顶边、页首完全可见 ✓（只作用于本页；其它二级页槽高一字不动）。
    // 三者都关 = 逐字回到改动前（旧 chip + 旧长列表页 + fullHeightPx 槽）⇒ 同构建 A/B ✓

    /**
     * 【P38·入口位置】true（默认，新）= 「组件演示」入口是【面板表头】标题行右侧的显式按钮
     * （一级页专属；表头固定、不参与滚动 ⇒ 任何 p 都可见、不会被裁 ✓）。
     * false（一行回退）= 回到改动前的旧入口：挂在「背景壁纸」chips 行末尾（该行横向滚动，
     * 末尾 chip 会被视口裁到只剩 ~8px 文字 ⇒ 就是用户「找不到/没应用成功」的原始形态，留作对照档）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidDemoEntryHeader --ei value 0 -p com.liqglass.ultraclear
     */
    @Volatile var liquidDemoEntryHeader: Boolean = true

    /**
     * 【P38·演示页形态】true（默认，新）= 「组件演示」二级页 = 【独立演示场】：一块干净的
     * 渐变底座（自建 rememberLayerBackdrop + Modifier.layerBackdrop，仍复用既有 backdrop 模块，
     * 未另造渲染体系 ✓）+ 上游 catalog 同款尺寸/间距的组件列（按钮 3 种、开关、滑杆、标签栏），
     * 紧凑到 p=1 半屏即可【整场看到】⇒ 不依赖面板滚动、页首不会被裁 ✓。
     * false（一行回退）= 改动前的长列表页（每节带大段中文说明 + 六段色带/24dp 网格调试图底）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidDemoStage --ei value 0 -p com.liqglass.ultraclear
     * 【范围】只作用于「组件演示」二级页与它的入口；✗ 不动 vendored backdrop 模块、✗ 不动面板/胶囊几何、
     *   ✗ 不动其它二级页与交棒/手势区。总开关 liquidComponentsDemo=0 ⇒ 入口与本页都不进组合 ⇒ 零回归 ✓
     */
    @Volatile var liquidDemoStage: Boolean = true

    /**
     * 【P38·演示页槽高】true（默认）= 「组件演示」页的内容槽高取【p=1 档】（= 面板半屏高），
     * 与一级页同档 ⇒ 槽 ≤ 面板 ⇒ 内容容器顶边贴面板顶边、页首完全可见 ✓。
     *
     * 根因（本轮实测，clean-room 构建 + 布局探针 P38L 的读数）：
     *   1) 二级页槽高曾被钉在 p=2 档（fullHeightPx = 屏高×0.98 = 2885px），而 p=1 面板只有 1619px；
     *   2) 内容容器（2885px）比面板高时会被【垂直居中】摆放 —— 实测 `contentBox top=692.0 h=2885`
     *      （692 = 面板中心 2134.5 − 2885/2）✗；
     *   3) 于是页首 633px（表头 + 页面第一屏）落到面板上缘之外，且列表滚不动（内容装得下视口）
     *      ⇒ 用户永远看不到页首 ✗。
     *   对照：一级页槽高 = contentHeightPxOf(0f) = 1619 = 面板高 ⇒ 实测 top=1325 = 面板顶 ✓。
     *
     * false（一行回退）= 演示页回到 fullHeightPx 槽高（页首被裁的旧行为，留作 A/B 对照档）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidDemoStageSlot --ei value 0 -p com.liqglass.ultraclear
     * 【范围】只在 panelComponentDemo（本页）分支生效 ⇒ 其它二级页/一级页槽高逐字不变 ✓
     *   （⚠️ 其它二级页在 p=1 同样存在「页首被居中溢出裁掉」的系统性现象，本开关【不】改它们 ✗，
     *     留给面板内容槽机制单独处理。）
     */
    @Volatile var liquidDemoStageSlot: Boolean = true

    /**
     * 【本次修复·演示页页内开关的实时反馈】true（默认）= 「组件演示」页里的开关（手感「阻尼用上游原值」
     * 等）点击后【界面立即跟随】：开关变蓝、选中行文字同步、各组件手感立即切换。
     *
     * 用户原话：『组件演示内的手感按钮无法点击』。
     * 根因（emulator-5554 + 真机 turner 双端实测：uiautomator 节点树 + dumpState + 截图像素）：
     *   是「开关点了没反应」而不是热区无效 —— 节点 clickable=true、点击也真的翻转了
     *   DebugSwitches.liquidDampingUpstream，但【界面不跟随】：
     *   页内读的每枚开关都是 @Volatile（不产生快照订阅）；页级 LiquidComponentsDemoPage 里的
     *   revision 陪读只订阅了页函数自己 → 点开关后页函数确实重组，但调到
     *   LiquidComponentsDemoStage(...) 时参数（backdrop/modifier）逐实例相同 + 强跳过模式 ⇒
     *   舞台函数体被跳过、不重跑 ⇒ 体内 volatile 全部不重读 ⇒ 开关外观/文字/手感停在旧值。
     * true  = 把 revision 陪读下沉到【舞台函数 / 回退列表函数自己的作用域】⇒ 值一变直接失效该
     *   作用域、函数体重跑、体内 volatile 全部重读 ⇒ 点击即时可见（同修 adb setSwitches 翻
     *   liquidDemoBackdrop 等页内开关不重绘的同源问题）。
     * false（一行回退）= 回到改动前行为：值照写、界面不跟随（重进页面才刷新）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *     --es name liquidDemoLiveFeedback --ei value 0 -p com.liqglass.ultraclear
     * 【范围】只作用于「组件演示」二级页的两个页体（舞台档 liquidDemoStage=true / 回退档=false）；
     *   ✗ 不动面板几何、✗ 不动其它页与其它开关、✗ 不动组件本身。
     */
    @Volatile var liquidDemoLiveFeedback: Boolean = true

    // ================= 【P08·邻近提亮（能量传递）· 2026-09-14；默认改开 2026-09-17】 =================
    // 用户原话（NEXT.md 175-190 第 6 条）：『两块玻璃离得够近时，在两者最近距离处产生【额外的提亮效果】
    //   （类似"能量传递"）。』
    // true（【默认】）= 两卡最近点连线中点处叠一层柔和白光（几何/强度/半径见 glass/ProximityHighlight.kt）；
    // false = 完全不进入组合树（叠层节点不存在、不读几何、不绘制）⇒ 单卡 / 双卡远离 /
    //   双卡靠近三种情形都与改动前【逐像素一致】（零回归 ✓）。
    // 【一行回退】运行中：`--es cmd setSwitches --es name nearGlow --ei value 0`（或 value 1 打开）。
    // 【跨进程】@Volatile 不跨进程 ⇒ 冷启动初值读应用私有目录文件 `files/lg_near_glow_off`（内容 "1" = 关，
    //   照 readSwitchFile 先例；默认无此文件 ⇒ 冷启动恒为 true）。
    // 三个 float（thresholdPx 默认【跟随 P12 的 ProximityFusion.thresholdPx】= 88 / maxAlpha=0.20 /
    //   radiusPx=140）走 ProximityHighlight 的常量默认值 + 落盘覆写（files/lg_near_glow_{threshold,alpha,radius}）。
    @Volatile var nearGlow: Boolean = !readSwitchFile("lg_near_glow_off")

    /**
     * 【P08 逐帧取证开关（默认关）】
     * true  = 提亮叠层【每帧】打印一行（tag LGNearGlow，含阈值外/idle 帧的 gap/pen）⇒ `setPos` 做 gap 扫描时
     *   可逐点读数；false = 【默认】只在「开始提亮 / 停止提亮」两种状态跳变时各打一行（日常零刷屏 ✓）。
     * 一行开（运行中即时生效）：`--es cmd setSwitches --es name nearGlowTrace --ei value 1`。
     * 【跨进程】初值读文件 `files/lg_near_glow_trace`（内容 "1" = 开；无此文件 = 关）。
     */
    @Volatile var nearGlowTrace: Boolean = readSwitchFile("lg_near_glow_trace")

    // ================= 【P29·开源许可与致谢页 · 2026-09-14】 =================
    // 集成点：ui/LicensePage.kt（新文件：页面 + 入口节 + 逐字 LICENSE 文本）+ ui/GlassControlsPanel.kt
    //   （「更多设置」底部新增一节「关于」+ 二级页分支）+ ui/LiquidGlassScreen.kt（面板二级页接线
    //   panelLicenses，与 panelAdvanced / panelImageEdit / panelComponentDemo 同一套页机制）。
    //
    // 用户点名需求：控制中心 →「更多设置」底部一节「关于」（App 名 + 版本 + 一行
    //   「开源许可与致谢 ›」）→ 第三个二级页：逐条列出第三方来源的【项目名 + 版权行 + 许可证名 + 链接】
    //   （Kyant0/AndroidLiquidGlass、Kyant0/Shapes、QWEA0/Liquid-Glass-Android、AndroidX/Compose/
    //   Kotlin/CMP/AGP），版权行与许可文本【逐字照录】上游 LICENSE（见 ui/LicenseTexts.kt）。
    //
    // true（默认，新增）= 「更多设置」底部出现「关于」节与「开源许可与致谢 ›」入口；
    //   因入口在【设置页最底部】（在既有 1619px 内容槽之外 ⇒ 需滚到「更多设置」末尾才能看到），
    //   且新页面只在自己的分页里渲染 ⇒ 控制中心一级页 / 玻璃 / 其余设置页逐像素不变 ✓
    // false（一行回退）= 入口与二级页【都不进组合】/ 状态复位 ⇒ 与改动前逐像素一致 ✓
    //
    // 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //             --es name licensesPage --ei value 0 -p com.liqglass.ultraclear
    /** 【P29·开源许可/致谢页】开关（默认开）。 */
    @Volatile var licensesPage: Boolean = !readSwitchFile("lg_licenses_page_off")

    // ================= 【P46·二级「更多设置」拆两页 + 上游玻璃底栏 · 2026-09-17】 =================
    // 集成点：ui/GlassControlsPanel.kt（两页内容 = 页块函数）+ ui/LiquidGlassScreen.kt
    //   （底栏 = 面板容器 Box 的最后一个子项，align(BottomCenter) 锚面板底边 ⇒ 逐帧跟随 p）。
    //
    // 用户点名需求：「更多设置」页按语义拆成「外观设置 / 高级设置」两页，底部一条
    //   【真实上游 LiquidBottomTabs】玻璃悬浮底栏切页（按压形变 / 二次折射旋钮 / 色散 lens 都是
    //   上游组件自带，逐字移植在 ui/components/liquid/LiquidBottomTabs.kt）。
    //   底栏采样源 = adapter.panelFillLayer（与 P45 真实控件同口径：控件脚下真正可见的面板填充层）。
    //
    // true（默认，新增）= 二级「更多设置」= 两页 + 底部玻璃标签栏；
    // false（一行回退）= 逐字回到【拆分前】的单页「更多设置」（原整页代码原样保留在 else 分支，
    //   底栏同时不进组合）✓
    //
    // 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //             --es name panelTwoPageTabs --ei value 0 -p com.liqglass.ultraclear
    /** 【P46·二级页两页拆分 + 玻璃悬浮底栏】开关（默认开）。 */
    @Volatile var panelTwoPageTabs: Boolean = !readSwitchFile("lg_panel_two_page_off")

    // ================= 【P56·底栏两个并列项 = 一级菜单 / 更多设置（编号按 NEXT.md 代号表）】 =================
    // 集成点：ui/LiquidGlassScreen.kt（底栏门控 + 两个项的内容/回调）+ ui/GlassControlsPanel.kt
    //   （二级页内容：本开关打开时「更多设置」= 单页 = 拆分前原样 ⇒ 页内不再需要第三级切页入口）。
    //
    // 用户点名需求：悬浮底栏改成【两个并列项 = 一级菜单 / 更多设置】—— 点「一级菜单」显示控制中心
    //   一级页内容、点「更多设置」进二级页；选中态 / 切换动画 / 点击跟手一律沿用真 LiquidBottomTabs
    //   （控件与其内部实现【一字未动】✓：按压形变 / 二次折射旋钮 / 色散 lens / 拖动跟手全是上游件自带）。
    //
    // true（默认，新增）= 底栏两个并列项 = 一级菜单 / 更多设置：
    //   · 一级页也常驻底栏（面板被唤起即组合）⇒ 两个页面靠底栏【并列互切】（一级页 ⟷ 二级页）；
    //   · 二级「更多设置」内容 = 单页（= 拆分前原样：外观项 + 高级项同页，滚动可达）；
    //   · panelTwoPageTabs 的两页拆分代码原样保留（本开关关掉时逐字启用）。
    // false（一行回退）= 逐像素回到【旧两 tab 行为】：底栏只在二级页出现、两个项 = 外观设置 / 高级设置
    //   （页内两页拆分与 panelTwoPageTabs 语义完全不变）✓
    //
    // 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //             --es name panelLevelTabs --ei value 0 -p com.liqglass.ultraclear
    /** 【P56·底栏两个并列项=一级菜单/更多设置】开关（默认开）。 */
    @Volatile var panelLevelTabs: Boolean = !readSwitchFile("lg_panel_level_tabs_off")

    // ================= 【P57·画质行移除 + 固定最清晰档（编号按 NEXT.md 代号表）】 =================
    // 集成点：ui/GlassControlsPanel.kt（两处画质 chips 行 / 整节的【条件渲染】）+ GlassUiState.quality
    //   （生效值固定 = 最清晰档）。
    //
    // 用户原话：「同时取消更多设置里面的画质调节选项，默认按最清晰的显示」。
    // true（默认，新增）= 「更多设置」里的画质行（流畅 / 标准 / 高清 chips）整节移除 +
    //   生效画质【固定】= GlassQuality.QUALITY（displayName「高清」= 13 taps = 最清晰档）。
    //   消费点一字未改（BackdropAdapter：模糊采样抽头数 / Shader 程序缓存 key / 放大模糊档）——只是
    //   取值来源从「用户选择」变成「恒定值」。
    // false（一行回退）= 画质行回来 + 行为原状（默认 BALANCED「标准」、chips 可点选）✓
    //
    // 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //             --es name qualityFixedBest --ei value 0 -p com.liqglass.ultraclear
    /** 【P57·画质行移除 + 固定最清晰档】开关（默认开）。 */
    @Volatile var qualityFixedBest: Boolean = !readSwitchFile("lg_quality_row_off")

    // ================= 【② 卡与面板的分层（采样层）修复 · 2026-09-14】 =================
    /**
     * 【卡片 vs 控制中心面板：谁在谁之上，以及【采样层】里有没有面板】
     *
     * 缺陷（用户定位 ✓）：背景捕获层 captureLayer 只录【App 背景那一层】——面板自身【从不进入卡的
     *   采样层】✗。于是"卡压在展开的面板上面"时，卡画出来的仍然是"面板下面的背景" ⇒ 观感上就是
     *   「透过控制中心看到控制中心下面的内容」✗ = 分层语义含混（卡到底在面板之上还是之下没有定义）。
     *
     * 本开关把两件事【成对】定死（z 序与采样层必须一致，这才是修复点）：
     *   true（默认 · B 档：卡在面板【之上】）：
     *     · z 序：每个卡片 z = 1 + （P06 z 序）⇒ 都在面板（z=0）之上 ✓
     *     · 采样层：卡的 backdrop = CombinedBackdrop(背景捕获层, 面板离屏层[panelCaptureLayer])
     *       ⇒ 面板【已渲染结果】真正进入卡的采样层 ⇒ 卡折射的是【面板本身】✓（玻璃叠玻璃，与 iOS 观感一致；
     *       也是"第二块玻璃应该折射控制中心的内容"的正确版 —— 它折射面板，而不是面板下面的背景）。
     *     · 成本：多录一层（面板尺寸 1776×1619 px，p=1 时）——数字见报告；p=0（收起胶囊）时层只有 464×124。
     *   false（一行回退 · A 档：卡在面板【之下】）：
     *     · z 序：面板 z=10（压在所有卡之上）⇒ 与面板重叠的部分【就该看不见】✓（靠 z 序遮挡，不靠采样）
     *     · 采样层：卡的 backdrop = 背景捕获层（= 改动前）——被面板遮住的部分看不见 ⇒ 采样层里没有面板
     *       也【不自相矛盾】✓（这正是修复前的问题：卡在面板之上却采样不到面板 ⇒ 像穿透 ✗）
     *
     * 【切换命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name cardOverPanel --ei value 0 -p com.liqglass.ultraclear
     * 【范围】✗ 不改折射默认参数、✗ 不改面板几何/胶囊、✗ 不动交棒区与手势区。
     *   默认档（单卡 + 收起态）下卡与面板不重叠 ⇒ 两档都【逐像素 = 改动前】✓
     */
    @Volatile var cardOverPanel: Boolean = false

    // ============ 【A档遮挡 · 「面板没能 100% 遮住卡片」修复 · 2026-09-17】 ============
    /**
     * 【A 档（卡在面板之下）时，把【面板形状以内】的卡像素整段不画（"洞裁剪"）】
     *
     * 缺陷（实测复现，见 ~/Downloads/... 报告）：「A 档下面板没能 100% 遮住卡片」——
     *   A 档的遮挡完全依赖【面板自己的不透明度】，而面板是【半透明玻璃材质】：
     *   展开态填充层 alpha=0.90（`fillAlphaOf(p=1)=0.9`）⇒ 仍有 **10% 透射**。
     *   卡的高光边/暗边比它背后的背景亮/暗 ~100+ 灰阶 ⇒ 透射后仍有 |Δ|≈12~22 灰阶
     *   （> 8 的可见门限）⇒ 「卡从面板边缘漏出来一点」：卡的左右高光边在面板内【全程可见】
     *   + 卡的底边高光带在面板内呈现为一条横向残差带（实测卡底边带 y1937~2151 一类）。
     *   逐像素取证（改前）：卡∩面板区 |Δ|>8 的像素占 1.91%（素色壁纸）/ 7.08%（彩色网格壁纸，
     *   卡内折射与背景差异更大），max|Δ| = 22~23 —— 与 z 序无关（面板确实最后画：
     *   把卡移走对照，面板区像素变化 = 0.1×(卡-背景) ⇒ 就是 10% 透射）。
     *
     * 修法（最小侵入、只在 A 档、只删面板以内的像素）：把每块卡（+ 选中卡描边层）放进一个
     *   `matchParentSize()` 的包装盒（= root 坐标空间），绘制期用 `clipPath(面板形状, ClipOp.Difference)`
     *   把【面板形状以内】的像素整段扔掉 ⇒ 遮挡由【几何】给出，与材质透明度无关 ✓。
     *   · 面板矩形【以外】的卡像素逐像素不变（洞只删面板以内的像素）✓
     *   · 面板压在卡上的观感 = "卡被面板切断"（正是 A 档语义：与面板重叠的部分就该看不见）✓
     *   · 洞的几何【与面板自己用的那份同源】：同一个 `PanelClipShapeCache.shapeOf(...)` +
     *     同一个 `wOf/hOf/liftOf`，由面板自己的 layout 块每帧写入 ⇒ 不会漂、不会滞后
     *     （layout 先于同一帧的所有绘制）。
     *
     * true（默认，新档）= A 档 + 面板可见时启用洞裁剪；
     * false（一行回退）= 不加这个修饰符 ⇒ 逐像素回到改动前（仍是"10% 透射"的旧观感）✓
     *
     * 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name cardHiddenUnderPanel --ei value 0 -p com.liqglass.ultraclear
     * 恢复默认：把 value 改成 1。
     *
     * 【范围】✗ 不动折射默认参数、✗ 不动面板几何/胶囊、✗ 不动交棒区与手势区（纯绘制期裁剪，
     *   不改布局/命中测试）；B 档（[cardOverPanel]=true）下本开关【完全不生效】
     *   （卡本来就在面板之上，必须看得见）✓
     */
    @Volatile var cardHiddenUnderPanel: Boolean = true

    // ==================== 【② 证据轴 · 卡采样层里【要不要】面板】 ====================
    /**
     * 【② 证据轴 · 卡采样层里【要不要】面板】—— 与 [cardOverPanel] 正交（它是 z 序语义），
     * 单独一个开关才能拍出用户要求的那组对照：
     *   面板关/开 × 卡采样层【含面板】/【不含面板】 四张图（见报告）。
     *
     * true（默认，与 cardOverPanel=true 的 B 档配套）= 卡采样层 = 背景 + 面板离屏层 ✓
     * false（对照档 —— 就是【修复前】的采样层）= 卡采样层只有背景 ⇒ 卡压在展开的面板上时会
     *   画出"面板下面的背景" ⇒ 观感 = 透过控制中心看到它下面的内容 ✗（缺陷复现档）。
     *
     * ⚠️ 只有 (cardOverPanel=true, cardSamplePanel=false) 这个组合是"语义不自洽"的 ——
     *   它正是修复前那一档，保留下来专门用于 A/B 取证与回归复现 ✓
     *   （cardOverPanel=false 时本开关无意义：面板压在卡上，卡采不采面板都看不见）。
     */
    @Volatile var cardSamplePanel: Boolean = true

    // ================= 【P44 · 玻璃边沿「糊」修复】贴边发丝高光带支撑宽度 =================

    /**
     * 【默认开回退开关】玻璃边沿「糊」修复：贴边发丝高光带的【支撑半宽倍率】档位选择。
     *
     * 缺陷（用户真机反馈）：「玻璃边沿还是有点糊」= 上一次「锯齿修复③」的代价 —— 为了消掉 255 灰阶硬台阶，
     * 把贴边高光从「1~2px 硬线」改成了【15px 总宽的平滑裙摆】(SUPPORT 1.4→5.0) ⇒ 锯齿没了，但边缘变糊。
     *
     * true（默认，新档）= 支撑半宽 = [edgeHairSupportMul]（= GlassParameters.HAIR_BAND_SUPPORT_MUL，
     *   「过渡带更窄 / 相邻像素跳变仍在 ≤16 灰阶硬门内」的最锋利档，数字见 NEXT.md/REPORT）；
     * false（一行回退）= 支撑半宽 = GlassParameters.HAIR_BAND_SUPPORT_MUL_LEGACY（= 5.0 = 改动前的值）
     *   ⇒ 与改动前【逐像素一致】（同一份 AGSL：只有这一个 uniform 的取值不同）。
     *
     * 【回退命令】（运行时生效，✗ 不需要重启/重建）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name edgeHairBandSharp --ei value 0 -p com.liqglass.ultraclear
     * 恢复默认：把 value 改成 1。
     *
     * 【范围】只动「发丝高光带的支撑宽度」一个量：峰值/峰位/亮度/颜色/光照方向/折射/模糊全部不动；
     *   可见边界仍来自同一个 SDF（sd）+ 覆盖率羽化 ⇒ ✗ 不引入第二套边界、✗ 不动折射默认参数。
     *   面板/胶囊取 hairOld（lgCardOn=0）⇒ 本开关对它们零影响（硬门应报 0.00%）。
     */
    @Volatile var edgeHairBandSharp: Boolean = true

    /**
     * 贴边发丝带支撑半宽倍率（标定通道的值；默认取 GlassParameters.HAIR_BAND_SUPPORT_MUL）。
     *
     * 【为什么放在这里】让「糊 vs 锯齿」的候选档能在**同一构建**上逐档扫掠 + 量化：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setEdgeHairSupport \
     *       --ef value 2.5 -p com.liqglass.ultraclear
     * 值域钳制 0.5..8.0；NaN/Inf 一律拒绝（见 setPanelP 的 NaN 教训）。
     * ⚠️ 标定完成后的"新默认"要写回 GlassParameters.HAIR_BAND_SUPPORT_MUL（本字段只是运行时覆盖）。
     */
    @Volatile var edgeHairSupportMul: Float =
        com.example.liquidglass.glass.GlassParameters.HAIR_BAND_SUPPORT_MUL

    // ================= 【P45 · 应用【真实控件】换上游移植的液态玻璃组件（第一阶段：开关 / 滑杆）】 =================
    /**
     * 【P45·真实控件】面板里【真实在用的控件】是否换成上游移植组件（默认开）。
     *
     * 用户原话：「这个 liquid glass 组件演示功能你都做进去了为什么应用里面有真实功能的组件没有同步演示」
     * ⇒ 演示页只是展示，真正的控件（面板开关 / 滑杆）还是旧样式 ✗。本开关把【面板内真实控件】换成
     * 已移植的上游组件（`ui/components/liquid/`）：
     *   · `ui/GlassControlsPanel.kt` 的 `SwitchRow`   ⇒ `LiquidToggle`（轨 64×28 / 旋钮 40×24）
     *   · 同文件的 `ParameterSlider`                 ⇒ `LiquidSlider`（轨 6dp / 旋钮 40×24）
     * 保留：标题/数值文案、行高（开关行 48dp、滑杆行 64dp）、无障碍（contentDescription +
     *   stateDescription + toggleableState）、值域 / 格式化串 / 回调【逐字不变】（调用点一行未改，
     *   改的是两个函数体开头的分派）✓
     * 手感：一律跟 [LiquidHandFeel.current()]（默认 = 本工程基准；`liquidDampingUpstream` 一行切上游原值）✓
     *
     * true（默认）= 新控件；false（一行回退，源码级）= 两个分派各自走回 `SwitchRowLegacy` /
     *   `ParameterSliderLegacy`（= 改动前的实现，逐字未动），且面板填充层的录制修饰符不进链
     *   ⇒ 与改动前逐像素一致 ✓
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidRealControls --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_real_controls_off`（内容 "1" = 关）。
     * 【范围】✗ 不动 glass 包的折射默认参数、✗ 不动控制中心胶囊/面板几何、✗ 不动交棒区与手势区。
     */
    @Volatile var liquidRealControls: Boolean = !readSwitchFile("lg_real_controls_off")

    /**
     * 【P45·真实控件的采样源】面板内真实控件（LiquidToggle / LiquidSlider）用哪个 Backdrop 当采样源。
     *
     * true（默认）= 【面板填充层】`adapter.panelFillLayer` —— 它是面板【填充层】这一层的录制
     *   （`LiquidGlassScreen` 里给面板填充 Box 挂 `Modifier.layerBackdrop(adapter.panelFillLayer)`）。
     *   选它的证据（不是照搬演示页的常量）：
     *     ① 语义上它就是控件脚下真正可见的材质（面板中位 ~164 的灰白磨砂底），采样源 = 脚下内容 ✓；
     *     ② 该节点【不含任何控件】⇒ 无自引用（"捕获窗口必须排除玻璃元素自身"是本项目铁律）✓；
     *     ③ 它【不进】卡的采样层、也不是 `adapter.captureLayer`（那一层在 P34 已被用户真机判负：
     *        组件采"面板背后的壁纸" ⇒ 按下时看不出玻璃 ✗，见 `liquidDemoBackdrop` 的 KDoc）。
     * false（A/B 对照档，= P34 判负的那一档）= 直传 `adapter.captureLayer`（整页背景捕获层）
     *   ⇒ 可拍出「采样源脱节：玻璃里映的是面板背后的壁纸」的对照图（量化见交付报告）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidRealControlsSamplePanel --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】初值额外读 `files/lg_real_controls_sample_bg`（内容 "1" = 用背景捕获层 = 对照档）。
     */
    @Volatile var liquidRealControlsSamplePanel: Boolean = !readSwitchFile("lg_real_controls_sample_bg")

    // ================= 【P30·玻璃放大后偏糊 · 尺寸归一化模糊 + 档位系数】 =================
    // 用户原话（真机反馈）：「另外现在这些玻璃确实没有锯齿了但是放太大总感觉很模糊」。
    //
    // 根因（代码级判定 + 三档量测见 P30 报告；H1~H5 全部不成立）：
    //   整条渲染链上【没有任何一处】把模糊半径由元素尺寸推导 ——
    //   · 平台真高斯模糊（BackdropAdapter 的 `blur(values.blurRadiusPx)`）半径 = blurRadiusDp × density，
    //     与尺寸无关（默认均衡档 = 1.53dp × 2 = 3.06px，三档完全相同）；
    //   · shader 侧 `lgBlurRadius = min(blurRadius × lgScale, lgBandCeil)`，而
    //     `lgScale = clamp(短边/640, 0.30, 1.0)` 上限就是 1.0 ⇒ 0.35 / 0.50 档恒为 1.0（逐值相同），
    //     0.20 档反而是 0.575（更小 = 更清晰）；
    //   · 采样层 LayerBackdrop 按场景 size 逐像素录制（1:1、无降采样、无 renderScale 参数）；
    //     折射位移、羽化带宽（lgFeather ≤ 5px）、发丝带、合成层同样与尺寸无关。
    //   ⇒「放大后更糊」不是模糊量随尺寸增长，而是【固定 px 的柔化铺在越来越大的面积上】：
    //     镜片环（折射 / 色散 / 高光）带宽固定 ⇒ 大尺寸下占比变小，观感被"一片均匀柔化的面"吃掉。
    //
    // 修法（最小侵入 / 可回退 / 默认档零回归）：
    //   ① blurSizeNormalized（默认 true）：玻璃柔化（平台 blur + 边缘模糊 + 边缘柔化 RIM_SOFT）按
    //      `clamp(720 / 玻璃层短边, 0.55, 1.0)` 归一化。参考短边 720px = 【默认档 0.35 各形状玻璃层
    //      短边的最大值】（圆角矩形/圆/六边/三角/超椭圆 676px，胶囊/椭圆 720px）⇒ 默认尺寸下系数恒为
    //      1.000 ⇒ 上传 uniform 与改动前【逐值相同】✓（零回归），只有放大后才收敛
    //      （0.50 档短边 952px ⇒ 0.756；胶囊 996px ⇒ 0.723）。
    //   ② blurTierFine / blurTierRich（默认 false）：给用户拍板用的档位系数 ——
    //      细腻 = 中心模糊与边缘柔化 ×0.75、浓郁 = ×1.25、都不开 = 标准 ×1.00（等同改动前）。
    //      与「玻璃风格拉杆」正交：拉杆改 dp 值本身（0.8 / 1.53 / 2.9dp），这里是在同一 dp 值上叠系数。
    //
    // 【一行回退】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
    //   --es name blurSizeNormalized --ei value 0 -p com.liqglass.ultraclear
    //   ⇒ 系数恒 1.000 ⇒ 与改动前逐值一致（读取点在 effects 录制 lambda 内 + 陪读
    //   DebugBridge.revision ⇒ 翻开关当场生效，无需重启）。
    // ⚠ 不碰折射默认参数（REFRACTION_OFFSET / REFRACTION_HEIGHT / 色散一律不动）、不碰控制中心几何。
    /** 【P30·玻璃放大后偏糊】尺寸归一化柔化（默认开；关 = 系数恒 1.000 = 改动前逐值一致）。 */
    @Volatile var blurSizeNormalized: Boolean = true

    /** 【P30·柔化档位】细腻：中心模糊 + 边缘柔化 ×0.75（默认关 = 标准）。 */
    @Volatile var blurTierFine: Boolean = false

    /** 【P30·柔化档位】浓郁：中心模糊 + 边缘柔化 ×1.25（默认关 = 标准）。 */
    @Volatile var blurTierRich: Boolean = false

    /**
     * 【P47·可拖拽性能看板 HUD】默认 **true**（用户点名要的功能，默认开 ✓）。
     *
     * true  = 性能看板 = 全屏可拖的悬浮 HUD（ui/PerformancePanel.kt 的 PerfHudFloating）：
     *         · 手指在【全屏范围】拖动看板（1:1 跟手；松手带惯性/回弹，弹簧 = LiquidHandFeel 本工程基准）；
     *         · 拖到屏幕左/右边缘 24dp 内 ⇒ 自动吸附贴边 + 收起成「状态点 + 帧率」一行数字；
     *         · 贴边收起态下横向朝屏内拖出 ≥40dp ⇒ 还原成完整看板（宽度动画与拖动并行）；
     *         · 位置与折叠态落盘 SharedPreferences("perf_hud")，进程重启后保持；
     *         · 冷启动无存档 = 屏幕最顶端居中一条细浮条（参考 Scene 性能看板观感）。
     * false（一行回退）= 逐字回到改动前：右上角固定位、不可拖、不落盘（原渲染实现原样保留在
     *         CompactPerformancePanel 里）。
     *
     * 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name perfHudDraggable --ei value 0 -p com.liqglass.ultraclear
     * 【运行时 A/B】读取点在 Composition（ui/PerformancePanel.kt 的 PerformanceHud，并陪读
     *   DebugBridge.revision）⇒ 翻开关当场切换，无需重启 ✓
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_perf_hud_off`（内容 "1" = 关）。
     * 【范围】✗ 不动 glass 包的折射参数、✗ 不动控制中心胶囊/面板几何、✗ 不动交棒区/手势区/卡片手势
     *   （HUD 自带手势只挂在看板自身节点上；定位用的空 Box 不挂任何手势 ⇒ 不吃其它区域的点击）。
     */
    @Volatile var perfHudDraggable: Boolean = !readSwitchFile("lg_perf_hud_off")

    /**
     * 【P47·可拖看板逐帧取证】默认 **false**（零日志零开销）。
     *
     * true ⇒ HUD 的每个指针事件 / 吸附判定 / 展开度逐帧值都打进 logcat（tag = `LGHud`）：
     *   `drag t=..ms finger=(+dx,+dy) hud=(+dx,+dy) lag=(..) anchor=.. hudLeft=.. w=.. pull=..`
     *   `release v=(..) left=.. distL=.. distR=.. snapThr=48px pullThr=80px → edge=LEFT|RIGHT|NONE`
     *   `frame expand=.. hud=WxH left=.. top=.. edge=..`（折叠/展开动画逐帧）
     *   `pos settle done dur=..ms` / `width collapse done dur=..ms`（吸附与折叠动画时长）
     *   `persist left=.. top=.. edge=..`（落盘证据）
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name perfHudTrace --ei value 1 -p com.liqglass.ultraclear
     */
    @Volatile var perfHudTrace: Boolean = false

    /**
     * 【P46·面板 chips / 整行按钮 换上游 LiquidButton】总开关（默认开）。
     *
     * true（默认）= 面板里全部 FilterChip（一级页 / 外观设置页 / 高级设置页 / 单页回退档）与
     *   全部整行按钮走【上游移植的 LiquidButton】：
     *   · chips：`GlassControlsPanel.PanelFilterChip` → `PanelFilterChipLiquid`
     *     （默认 48dp 高 = 旧 chip 的 48dp 占位 ⇒ 行高/行距/文案/选中态/回调逐字不变）；
     *   · 整行 Box 行（恢复默认设置 / 导出调试日志到文件，两页各一处 + 单页回退档各一处）：
     *     `PanelRowButton` → `LiquidButton`；
     *   · 整行 M3 按钮（更多设置 / 图片编辑页的恢复默认背景）：`PanelRowOutlinedButton` → `LiquidButton`。
     * false（一行回退，源码级）= 三个分派各自走回 `*Legacy`（= 改动前的实现，逐字未动）
     *   ⇒ 与改动前逐像素一致 ✓
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidRealButtons --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_real_buttons_off`（内容 "1" = 关）。
     * 【采样源】与 P45 的两个真实控件同源（`adapter.panelFillLayer`，见 GlassControlsPanel.panelButtonBackdrop）：
     *   该层只在 `liquidRealControls` 打开时被逐帧录制 ⇒ 本开关默认档下两者一起工作；
     *   关 `liquidRealControls` 而开本开关时，LiquidButton 退化为 `captureLayer` 采样（不采样空层）✓
     * 【范围】✗ 不动二级页拆分 / 底栏、✗ 不动面板几何与胶囊交棒、✗ 不动半行 M3 按钮。
     */
    @Volatile var liquidRealButtons: Boolean = !readSwitchFile("lg_real_buttons_off")

    /**
     * 【P54·上游按钮三毛病·点按放大安全区】面板里的上游 LiquidButton 按压放大不得越出按钮自身范围
     * （默认 **开**；一行回退）。
     *
     * 用户口径（本批任务书）：② 「恢复默认背景」按钮点按放大溢出（溢出像素 → 0）；
     * ③ 控制中心二级显示边界把边缘按钮按下放大裁切（被裁像素 → 0，留安全区，✗ 不改行高口径）。
     *
     * 根因（代码定位 + 几何计算）：上游 `LiquidButton` 的按压形变走 `layerBlock = { scale = lerp(1f,
     * 1f + 4dp/height, progress) }`（`ui/components/liquid/LiquidButton.kt:88` 一带）——4dp 是按【高度】
     * 归一化的，宽按钮的横向放大因此被同比例放大：
     *   · 48dp 高 × 1712px 宽的整行按钮（恢复默认背景）= 单侧 +71px（密度 2.8125）/ +68px（密度 2.0）；
     *   · 内容列的两侧余量只有 16dp(45px)/32px ⇒ 放大【越过内容区】并继续撞到面板容器的圆角硬裁剪
     *     （`ui/LiquidGlassScreen.kt` 面板容器 `graphicsLayer{ shape = …; clip = true }`，实测裁剪线 =
     *     面板左右边 x=45/1835（平板）、32/1808（模拟器））⇒ 被硬切 ✗
     *   · 演示场（组件演示二级页）另有它自己的显示边界（`LiquidComponentsDemo.kt` 的
     *     `Box.clip(RoundedCornerShape(26.dp))`）⇒ 同样把边缘按钮的放大硬切 ✗
     *
     * true（默认）= 面板里的【真实控件】（chips 13 + 整行按钮 6 + 图片编辑页 1）的放大预算钳到 0：
     *   0 越界 ⇒ 溢出像素 = 0、被裁像素 = 0；按钮自身的尺寸/位置/轮廓/文案/回调/无障碍【一字未改】✓
     *   （按压反馈保留：触点高光 `InteractiveHighlight` 仍在，点击回调本身即主反馈）。
     *   演示场（`LiquidComponentsDemo` 的演示场底座/组件列）走【安全边距】一档：保留上游放大，
     *   但把内容列的左右内边距 20dp → 40dp（行高/行距/组件尺寸口径不动）⇒ 放大被完整容纳、
     *   不再压到演示场的圆角显示边界上 ✗✓
     * false（一行回退）= 逐字回到改动前：面板按钮按上游原样放大（会溢出/被裁）、演示场 20dp。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name liquidPressSafeArea --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_press_safe_off`（内容 "1" = 关）。
     * 【范围】✗ 不动 glass 包折射默认参数、✗ 不动面板几何/交棒区/手势区、✗ 不动 chips 与按钮的
     *   尺寸/位置/行高（本开关只改按压期的形变预算与演示场的横向内边距）。
     */
    @Volatile var liquidPressSafeArea: Boolean = !readSwitchFile("lg_press_safe_off")

    // ================= 【P19 · App 内分享调试日志（系统分享面板）· 2026-09-17】 =================
    /**
     * 【P19·分享调试日志】面板里「导出调试日志到文件」旁边新增「分享调试日志」入口的总开关
     * （默认 **开**；一行回退）。
     *
     * 用户口径（原话）：『能让用户在 App 内直接分享日志到微信文件传输助手，不必接 adb。』
     *
     * true（默认，新增）= 「导出调试日志到文件」+「分享调试日志」= 面板里【同一行】两个动作：
     *   · 点「分享调试日志」⇒ 先跑一次现有导出（同一个 AppDebugLog.exportAsync）→ AndroidX FileProvider
     *     的 content:// URI → ACTION_SEND + type=text/plain + EXTRA_STREAM +
     *     FLAG_GRANT_READ_URI_PERMISSION + Intent.createChooser（标题带 App 名：分享调试日志 · Liquid Glass）
     *     ⇒ 微信 / 文件传输助手 / 邮件 / 短信 / 蓝牙… 都能直接接收，用户不必接 adb；
     *   · 无任何 App 可接收（queryIntentActivities 数到 0）或 FileProvider 取 URI 失败 / startActivity 失败
     *     ⇒【优雅降级】：回退到「导出到文件」的老行为，面板上 Toast 显示【实际路径】+ 日志 tag `LGShare`；
     *     全程 runCatching/try-catch 兜底 ⇒ ✗ 绝不崩。
     *   · 【几何】两个动作同一行（各自 48dp 高）⇒ 本行总高与改动前逐像素相同 ⇒ 面板标题/行高/
     *     其它按钮【零位移】✓（"合并成一行带两个动作"是主控给出的两个允许形态之一）。
     * false（一行回退）= 逐字回到改动前：那一行仍是单个整行宽的「导出调试日志到文件」
     *   （分享入口不进组合、分享代码路径不被执行）✓
     *
     * 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name iflashare --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_iflashare_off`（内容 "1" = 关，
     *   照 readSwitchFile 先例；默认无此文件 ⇒ 冷启动恒为 true = 用户点名要的功能默认开 ✓）。
     * 【范围】✗ 不动 glass 包的折射默认参数、✗ 不动控制中心几何/交棒区/手势区/底栏；
     *   ✗ 不改「导出调试日志到文件」的文案/回调/路径（两者共用同一个 export 实现）。
     */
    @Volatile var iflashare: Boolean = !readSwitchFile("lg_iflashare_off")

    // ================= 【钻石演示页 z 序（真机反馈 2026-09-17 修复）】 =================
    /**
     * 【钻石演示页 z 序】默认开：钻石页 `zIndex(DIAMOND_PAGE_Z_INDEX = 10.5f)`
     * （> 控制中心面板容器 z=10f ⇒ 钻石页盖住 面板/收起态胶囊/卡片/交棒文字；
     *   < 性能看板 z=11f ⇒ 看板照旧悬在最顶，用户口径「像 Scene 那样」不变）。
     *
     * 缺陷（用户原话）：「展开钻石的控制面板后控制中心的胶囊不会消失」= 打开钻石页后，
     *   控制中心收起态胶囊仍画在钻石页之上（面板容器带 zIndex(10f)，同父兄弟 zIndex 优先于组合顺序
     *   ⇒ 组合在末位的钻石页实际被面板压住）。修法 = 给钻石页显式 zIndex（本开关）。
     * false（一行回退）= zIndex 恒 0f ⇒ 逐像素回到改动前（钻石页被面板压住）✓
     *
     * 【回退命令】adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *             --es name diamondPageOnTop --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值读应用私有目录文件 `files/lg_diamond_on_top_off`
     *   （内容 "1" = 关；无此文件 = 默认开 ✓，照 readSwitchFile 先例）。
     * 【范围】✗ 不动 glass 折射默认参数、✗ 不动控制中心几何/交棒区/手势区/底栏 ✗ 不改钻石页自身内容。
     */
    @Volatile var diamondPageOnTop: Boolean = !readSwitchFile("lg_diamond_on_top_off")

    // ================= 【钻石演示页 UI 并入上游玻璃控件 · 2026-09-17】 =================
    /**
     * 【钻石演示页 UI ⇒ 上游玻璃控件】总开关（默认开）。
     *
     * 用户口径（原话）：「我的这个 APP 里面的所有组件，能直接套用上游形式的玻璃组件」
     *   ⇒ 钻石页的可交互件不再用 M3 / 自绘旧样式。
     *
     * true（默认）= 钻石页全部可交互件走上游移植件（`ui/components/liquid/`）：
     *   · 返回「← 返回」：自绘胶囊 ⇒ `LiquidButton`；
     *   · 「自动旋转」开关：Material3 `Switch` ⇒ `LiquidToggle`（容器 64×48dp = 旧占位高 ⇒ 行高不变）；
     *   · 弹射次数 chips 0..4 / 色散 chips 关·低·中·高：Material3 `FilterChip` ⇒ `LiquidButton` 行
     *     （选中态 = surfaceColor，取值与旧 chip 的四个构造常量逐值同源）；
     *   · 采样源 = 页内自建两块可采样层（`DiamondDemoPage` 的 pageBackdrop = 黑底+钻石层、
     *     panelFillBackdrop = 底部参数面板的填充层）⇒ 控件采「脚下真正可见的材质」（与 P45
     *     同一套口径：不含控件自身 ⇒ 无自引用）✓；✗ 不用控制中心面板的 captureLayer /
     *     panelFillLayer（钻石页控件画在钻石之上，采面板层会「隔山取景」）。
     *   · 手感 = `LiquidHandFeel.current()`（默认 = 本工程基准；`liquidDampingUpstream` 一行切上游原值）。
     * false（一行回退，源码级）= 逐字回到改动前的旧样式（M3 Switch / FilterChip / 自绘胶囊），
     *   且两块录制层【不进链】（不建层、不录制）⇒ 与改动前逐像素一致 ✓
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name diamondLiquidControls --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值额外读应用私有目录文件 `files/lg_diamond_liquid_off`
     *   （内容 "1" = 关；无此文件 = 默认开 ✓，照 readSwitchFile 先例）。
     * 【范围】✗ 不动 glass 折射默认参数、✗ 不动控制中心面板/底栏/看板、✗ 不动钻石 AGSL 光路与
     *   页面几何（标题/提示/小节标签/读数文案与配色一字未动）。
     */
    @Volatile var diamondLiquidControls: Boolean = !readSwitchFile("lg_diamond_liquid_off")

    // ================= 【P56·面板几何三条 · 2026-09-18】 =================
    // （登记号 P56：批内工作快照 ~/Downloads/LG-p53；草稿期曾按 P53 记载，P53 已被「钻石独立版退役」占用 ⇒ 交回时登记为 P56）

    /**
     * 【P56①·面板拉通到屏幕同宽】面板 p≥1 的全宽从「屏宽 − 2×16dp」改为「屏宽」（去左右 32px 边）。
     *
     * 用户口径（本单原话）：『面板宽度拉到与屏幕同宽（去左右 32px）』。
     * 现状（模拟器 1840×2944@320 实测）：面板盒 x=[32,1808]（w=1776）、y=[1325,2944]。
     *   原 16dp 侧边距的来历：满宽时左右圆角正好压在屏幕边缘被裁 ⇒ 历史反馈
     *   「右边框像被切掉一点」⇒ 留 16dp 让圆角完整可见（见 LiquidGlassScreen 面板几何注释）。
     *   本轮用户点名去掉 ⇒ 面板 x=[0,屏宽]（w=1840）。
     * ✗ 不动圆角半径口径（topR/bottomR 推导一字不动）、✗ 不动胶囊宽度（232dp 收起态）、
     *   ✗ 不动二级页与底栏内部比例（底栏 16dp 侧边距按「相对面板」语义保持 ⇒ 屏幕边缘 16dp，
     *   见 LiquidGlassScreen 底栏注释；底栏自身尺寸/缩放/折射一字不动）。
     * 连带重标定（实测口径见 REPORT）：内容槽宽（1776→1840）、交棒四字横向落点（随面板左缘
     *   −32px）、底栏 bounds（跟随面板 +32px/侧）、看板（屏幕级悬浮，预期零变化）。
     * true（默认，新）= 侧边距 0；false（一行回退）= 侧边距 16dp（逐字回到改动前）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name panelEdgeToEdge --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值读 files/lg_panel_edge2edge_off（"1"=关；无文件=默认开）。
     */
    @Volatile var panelEdgeToEdge: Boolean = !readSwitchFile("lg_panel_edge2edge_off")

    /**
     * 【P56③·组件演示页：页级最大高度跟随内容】面板在「组件演示」页把可达上限收在 p=1（半屏）。
     *
     * 用户口径（本单原话）：『组件演示页半屏就够显示 ⇒ 页级最大高度跟随内容（不留空白区；
     *   到顶即止不抖不跳、到顶后不得误触其它行为）』。
     * 现状：本页内容槽已按 P38 取「本页需要的终态高度」= contentHeightPxOf(0f)（半屏 1619px），
     *   但面板仍可上滑到 p=2（2885px）⇒ 本页内容（约 1.5k px）上方顶格、下方留 ~1.3k px 空白玻璃 ✗。
     * 语义：本页锚点集合 = {0f, 1f}（无 2f）—— 上滑到 p=1 即止（dispatchRawDelta 在锚点上限处
     *   自然饱和，结余位移按既有门槛语义丢弃）；松手就近收口（集合里没有 2f ⇒ 不可能弹回满高）。
     *   锚点集合按「当前页」重建（SideEffect updateAnchors 既有通路）；其它页锚点一字不动 ✓。
     * true（默认，新）= 演示页上限 p=1；false（一行回退）= 三锚点照旧（逐字回到改动前）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name demoPageMaxFitsContent --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值读 files/lg_demo_maxfit_off（"1"=关；无文件=默认开）。
     * 【范围】✗ 不动其它页/其它几何；✗ 不动演示页自身内容与组件。
     */
    @Volatile var demoPageMaxFitsContent: Boolean = !readSwitchFile("lg_demo_maxfit_off")

    /**
     * 【P56②·全屏段下拉对齐中间档（轻端）】手势【从全屏段起手（p>1.5f）的收回方向（下拉）】
     * 用 MIDDLE_FS 档（= 中间档族·轻端）：死区 8dp / 起始移交率 0.85 / 爬升 80dp。
     *
     * 用户口径（本单原话）：『更多设置全屏态下拉阻力过大（与半屏态对齐到中间档）』；
     *   父会话验收口径：「after 的 rates 起手必须到 0.85~0.95 一带、与改前明显不同」。
     * 改前实测（逐帧 LGDrag，全屏段起手收回，三组速度）：起手 rate 只有 0.72/0.78
     *   （逐值 [0.72,0.78,0.88,0.97,1.0…]）= 与半屏段同一 MIDDLE 档 ⇒ 全屏态仍偏重 ✗。
     * 本开关（默认开）= 全屏段起手的收回改走 MIDDLE_FS；半屏段起手的收回 / 上滑（展开）方向 /
     *   松手阈值 / 惯性不交接 一字不动 ✓。
     * 改后预期与实测：起手 0.88 / 0.91（落在 0.85~0.95 带），逐值 [0.88,0.91,0.97,1.0…]；
     *   同 484px 下拉保留率 0.942 → 0.977（详见报告保留率表）。
     * 语义细节：档位在【该方向的门槛段起点】锁存（sheetGateFromFull），段内不因 p 跨过 1.5f 改档；
     *   设备若被切到 轻/重/标准 档（panelEdgeGateLight/Heavy/Std），全屏段仍按本档收（"对齐"语义）。
     * true（默认，新）；false（一行回退）= 逐字回到按当前档位选择（默认档下 = MIDDLE ⇒ 改前逐值）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name panelEdgeGateFullscreenMid --ei value 0 -p com.liqglass.ultraclear
     * 【跨进程】@Volatile 不跨进程 ⇒ 初值读 files/lg_fs_pull_mid_off（"1"=关；无文件=默认开）。
     * 【范围】✗ 不动上滑（展开）方向参数、✗ 不动交棒/几何/其它手势开关。
     */
    @Volatile var panelEdgeGateFullscreenMid: Boolean = !readSwitchFile("lg_fs_pull_mid_off")

    /** 【P56·装机身份标记】dumpState 的 geom 行尾输出（+ dex 可检索字符串）：改前构建 0 命中、改后构建命中。 */
    const val P56_MARKER: String = "p56geom-v1"

    /**
     * 【P59·动画期主动请求 120Hz】
     * 用户口径：面板展开/收起动画那一小段没有 120 帧 ✗（平均 fps 高不等于动画段真的逐帧 8.33ms）。
     * 真因（取证见 P58 报告）：`RefreshRateController` 只在 [启动/失焦回归] 时下发三通道帧率请求；
     * 部分设备（平板 id4=120 可用）在 [动画开始] 时被系统把 App 请求降回 60 档（DisplayModeDirector
     * 的默认帧率投票），动画期间收不到新的 120Hz 票 ⇒ 动画帧全部落在 16.67ms 档 ✗。
     * 修法 = 动画 begin/end 各显式下发一次请求（begin 请求 120 / end 恢复 120 保持原开关语义），
     * 通过 [RefreshRateController.applyToWindow]（与既有路径同一实现，幂等、三通道同下）。
     * 语义：true（默认开，新）= 每次 animatePanelTo 开始/结束时 applyToWindow；
     *   false（一行回退）= 逐字回到改动前（只在启动/焦点时请求）✓。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name refreshDuringPanelAnim --ei value 0 -p com.liqglass.ultraclear
     * 【范围】✗ 不动玻璃折射默认参数 / 面板几何 / 交棒参数 / 手势区；✗ 不动面板动画时长与缓动。
     */
    @Volatile var refreshDuringPanelAnim: Boolean = !readSwitchFile("lg_refresh_anim_off")

    /**
     * 【P64·动画期模糊降档】控制中心展开/收起动画期（面板形变进度 pe∈(0,1)）玻璃 13t 模糊
     * 跳过 2.0× 外环 4 抽头（13→9 抽头，GPU 采样 −31%）并按 1/0.86 重归一（亮度/对比度口径不变）。
     * 真因（逐帧取证，模拟器 60Hz）：gpuTotal(Issue→GpuDone) 中位 24.1ms > 16.67ms 预算，
     * 动画期 33.3ms 档帧占 35%（jank>25ms 占 41%）——每帧渲染成本超预算，重心在玻璃采样。
     * 语义：true（默认开）= 仅动画期降档；静止态（pe=0/1）恒走原 13t 路径 ⇒ 静止观感逐像素不变 ✓。
     *   false（一行回退）= 全程 13t，逐字回到改动前 ✓。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name animBlurLean --ei value 0 -p com.liqglass.ultraclear
     * 【范围】✗ 不动阻尼曲线/轨迹/端点颜色/几何口径（p(t) 与形状 uniform 一行未动）。
     */
    // 【2026-09-24 默认值改为 false】平板真机反序 A/B 实测（各 1200 帧、含热身剔除）：
    //   13 抽头（关）≤8.5ms = 99.8%；9 抽头（开）≤8.5ms = 100.0% ⇒ 两者在真机上都是满 120fps ✓
    //   ⇒ 降档在目标设备上无收益 ✗（其收益只在模拟器软件渲染上存在，每帧 24.1ms ✗）
    //   ⇒ 默认关 = 保留动画期完整 13 抽头画质 ✓；需要时可 setSwitches animBlurLean 1 打开 ✓
    @Volatile var animBlurLean: Boolean = false

    // ================= 【P65·三缺陷修复开关（2026-09-23，依据 LG-bughunt/REPORT.md #1/#2/#4）】 =================

    /**
     * 【P65 修复①·jank 阈值按当前刷新率动态计算】true（默认开）= 每次帧聚合按
     * RefreshRateController 当前档位值（Display.getRefreshRate() 兜底）重算 jank 阈值：
     * 60Hz=16.67ms / 120Hz=8.33ms，随 setSwitches refresh120Hz 运行时切换（模拟器 60Hz 单模式下
     * 120 档 = 模拟值，dumpState 的 jankThr 行标注；真机待平板回线）。
     * false（一行回退）= 改动前行为：阈值构造期写死（≈16.67ms），运行时切档不再改变判定。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name jankDynamicThreshold --ei value 0 -p com.liqglass.ultraclear
     * 【范围】只影响 jankCount 判定与 dumpState/dumpFrameStats 的 jankThr 回读行；✗ 不动其它帧统计口径/渲染。
     */
    @Volatile var jankDynamicThreshold: Boolean = !readSwitchFile("lg_jank_dyn_thr_off")

    /**
     * 【P65 修复②·调试广播不再强开日志】false（默认关 = 修复生效）= 调试广播【不改变】用户的日志
     * 开关状态（AppDebugLog.enabled / ui.debugLogEnabled 保持用户设置；回执恒进 logcat = 调试桥始终可用，
     * 回执尾如实回报当前开关值）；✗ 不偷偷打开日志 ✗。
     * true（一行回退）= 改动前行为：任意调试广播强制 AppDebugLog.enabled=true 且不回写 UI 开关
     *（开关显示与实际失步、关着也每次切后台落盘）。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name debugBridgeForceLog --ei value 1 -p com.liqglass.ultraclear
     */
    @Volatile var debugBridgeForceLog: Boolean = readSwitchFile("lg_dbg_force_log_on")

    /**
     * 【P65 修复③·PerformanceMonitor stop→start 可重启】true（默认开）= stop() 销毁
     * HandlerThread/协程作用域后，start() 重建线程/作用域（DisposableEffect 的 stop→start 可重复使用 ✓）。
     * false（一行回退）= 改动前行为：stop() quit 线程后 start() 不重建 ⇒ 监控静默死亡、快照冻结在旧值。
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
     *       --es name perfRestartable --ei value 0 -p com.liqglass.ultraclear
     * 配套取证通道（stop→start 机上复现，先例 cardTop）：adb shell am broadcast -a com.liqglass.DEBUG \
     *       --es cmd perfMonitor --es action restart -p com.liqglass.ultraclear
     */
    @Volatile var perfRestartable: Boolean = !readSwitchFile("lg_perf_restart_off")
}
