/*
 * 【P38·独立演示场】液态组件演示页（控制中心二级页，与「图片编辑」「更多设置」同级）。
 *
 * == 本页为什么被重写（用户反馈原话：「上游的玻璃控制组件还是没有应用成功」）==
 * 逐条取证（emulator-5554/5556，uiautomator dump + 整屏截图 + 拖动试验）：
 *   ① 入口根本看不见（主因）：入口是「背景壁纸」chips 行【末尾】的 chip，而那一行是
 *      【横向滚动】行 ⇒ 末尾 chip 被视口裁掉。实测节点 bounds=[1736,2341][1776,2405]、
 *      文字节点=[1768,2353][1776,2393]（只剩 8px 宽的一条）✗
 *      ⇒ 用户看不到入口 ⇒「没应用成功」✗（入口已改到面板表头，见 LiquidGlassScreen + P38 注释）；
 *   ② 形态不像上游：上游 catalog 用的是 BackdropDemoScaffold = 【一整块干净底图 + 居中一列组件】；
 *      我们此前是「面板里的长列表 + 大段说明文字 + 六段色带/24dp 网格调试图底」⇒ 玻璃感被冲淡 ✗；
 *   ③ 半屏（p=1）时页首被裁：实测二级页内容槽高 = 2885px（p=2 档面板高），而 p=1 面板只有 1619px，
 *      【长页面】的页首（标题 + 两个 LiquidButton 段）整段落进面板上缘之外、且拖上/拖下都无位移 ⇒
 *      永久看不到 ✗（dump：页内第一个可见节点是 LiquidToggle@y1356）。
 *
 * == 现在的做法（开关 liquidDemoStage，默认 true）==
 * 把本页做成【独立演示场】：一块干净渐变底座（自建 rememberLayerBackdrop + Modifier.layerBackdrop，
 * 仍复用既有 vendored backdrop 模块 —— ✗ 未另造渲染体系）+ 上游同款尺寸/间距的组件列，紧凑到
 * p=1 半屏就能【整场看到】（不依赖面板滚动 ⇒ 页首不会被裁 ✓）。
 * 组件尺寸与上游逐项同源：按钮 height=48dp、水平内边距 16dp（LiquidButton.kt 逐字移植）；
 * 滑杆轨 6dp、旋钮 40×24dp；开关 64×28dp；标签栏 64dp。布局同上游 = 竖向排开、间距 16dp、整体居中。
 * 开关 liquidDemoStage=false 一行切回改动前的长列表页（LiquidComponentsDemoLegacy，保留作 A/B 对照）。
 *
 * 抓手（沿用 P34 的结论）：面板自己的捕获层只录 BackgroundScene（面板不进捕获层），
 * 组件画在面板里再采面板捕获层 ⇒ 按下看不出玻璃 ✗。所以本页自建一块【可采样的演示底】
 * 并把这一层传给各组件 ⇒ 按下/拖动看得见折射/模糊/高光/内阴影与旋钮的椭圆→胶囊形变 ✓
 * （开关 liquidDemoBackdrop=false 一行回到旧行为，见 DebugSwitches）。
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.debug.DebugSwitches
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 演示页内容（渲染在控制中心面板的二级页槽里）。
 *
 * @param backdrop 旧采样源（面板的 adapter.captureLayer = 整页背景捕获层）；**只在
 *   开关 liquidDemoBackdrop=false（回退档）时使用**，默认档用本页自建的演示底（见下）。
 */
@Composable
fun LiquidComponentsDemoPage(
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    // 【纪律】@Volatile 开关不产生快照订阅 ⇒ 组合期读开关必须陪读一次 DebugBridge.revision
    // （setSwitches 会自增它），否则 adb 翻开关不重绘。
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue

    if (DebugSwitches.liquidDemoStage) {
        LiquidComponentsDemoStage(backdrop = backdrop, modifier = modifier)
    } else {
        LiquidComponentsDemoLegacyList(backdrop = backdrop, modifier = modifier)
    }
}

// =====================================================================================
//  默认档：独立演示场（P38）
// =====================================================================================

/**
 * 独立演示场：干净渐变底座 + 上游同款尺寸/间距的组件列（居中）。
 * 紧凑到 p=1 半屏即可整场看到 ⇒ 不依赖面板滚动、页首不会被裁 ✓
 */
@Composable
private fun LiquidComponentsDemoStage(
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    // ===== 【本次修复·页内开关的实时反馈（用户报「手感按钮无法点击」）】=====
    // 根因（emulator-5554 + 真机 turner 双端实测）：本页读的开关（liquidDampingUpstream /
    //   liquidDemoBackdrop，经 LiquidHandFeel.current() 等）都是 @Volatile —— 不产生快照订阅。
    //   页级 LiquidComponentsDemoPage 里那份 revision 陪读只订阅了【页函数自己】：点开关后
    //   revision 自增 → 页函数重组 → 调到本函数时参数（backdrop/modifier）逐实例相同 +
    //   强跳过模式 ⇒ 【本函数体被跳过、根本不重跑】⇒ 体内 volatile 全部不重读 ⇒ 后台把值
    //   写了，但开关外观 / 文字 / 组件手感都停在旧值（用户看到的就是「点了没反应 = 无法点击」）。
    // 修法：把 revision 陪读【下沉到本函数自己的作用域】——状态一变直接失效本作用域、函数体重跑，
    //   体内 volatile 全部重读 ⇒ 开关即时变蓝、选中行文字同步、组件手感立即切换 ✓
    //   （顺带修好 adb setSwitches 翻 liquidDemoBackdrop 等页内开关不重绘的同源问题）。
    // 回退：DebugSwitches.liquidDemoLiveFeedback=false（一行，见 DebugSwitches 注释；翻档后
    //   重进本页 = 改动前行为：值照写、界面不跟随）。
    if (DebugSwitches.liquidDemoLiveFeedback) {
        @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    }

    val handFeel = LiquidHandFeel.current()

    var clicks by remember { mutableIntStateOf(0) }
    var toggleOn by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0.4f) }
    var tabIndex by remember { mutableIntStateOf(0) }

    // ===== 采样源（照上游 catalog 演示页 BackdropDemoScaffold 的做法）=====
    // 自建一块【可被采样的演示底】，对它打 Modifier.layerBackdrop(层)，再把这层传给各组件 ✓
    // 开关 liquidDemoBackdrop=false ⇒ 逐像素回到旧行为（直传面板捕获层）—— 既是 A/B 对照，
    // 也是本改动的一行回退。
    val demoBackdrop = rememberLayerBackdrop()
    val compBackdrop: Backdrop =
        if (DebugSwitches.liquidDemoBackdrop) demoBackdrop else backdrop

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .semantics { contentDescription = "液态组件演示场" }
        ) {
            // 演示底（组件采样的就是它）：干净的三段柔和渐变 —— 上游演示底也是一整块底图 + 居中组件。
            // 不透明底 ⇒ 采样内容与页面自身【逐像素同源】，玻璃里外不会「双涂」发白 ✓；
            // 渐变的色相/明度过渡给了折射（边界处色带位移）与模糊可见的对比 ✓
            if (DebugSwitches.liquidDemoBackdrop) {
                Canvas(
                    Modifier
                        .matchParentSize()
                        .layerBackdrop(demoBackdrop)
                ) { drawStageBase() }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    // 【P54·点按放大安全区】演示场走【安全边距】一档（保留上游放大，与面板里的钳零档不同）：
                    //   演示场按钮宽 ~1632px（密度 2.0）⇒ 上游放大单侧 68px（34dp）> 原 20dp 内边距
                    //   ⇒ 放大压到演示场自己的圆角显示边界（Box.clip(RoundedCornerShape(26.dp))）上被硬切 ✗。
                    //   开关开（默认）⇒ 左右内边距 20dp → 40dp：放大被完整容纳（68px < 40dp=80px），
                    //   行高/行距/组件尺寸口径【一字未改】✓（✗ 不动 vertical 18dp）。
                    //   关（一行回退）= 20dp，逐字回到改动前（放大仍会被显示边界裁掉 ✗）。
                    .padding(
                        horizontal = if (DebugSwitches.liquidPressSafeArea) 40.dp else 20.dp,
                        vertical = 18.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ---- 组 1：LiquidButton（上游 ButtonsContent 同款三态：透明 / 表面白 / 染色）----
                LiquidButton(
                    onClick = { clicks++ },
                    backdrop = compBackdrop,
                    handFeel = handFeel,
                    traceTag = "Button#1",
                    modifier = Modifier.semantics { contentDescription = "液态按钮" }
                ) {
                    Text(
                        "透明玻璃按钮",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = STAGE_INK
                    )
                }
                LiquidButton(
                    onClick = { clicks++ },
                    backdrop = compBackdrop,
                    surfaceColor = Color.White.copy(0.3f),
                    handFeel = handFeel,
                    traceTag = "Button#2",
                    modifier = Modifier.semantics { contentDescription = "液态按钮（表面白）" }
                ) {
                    Text("表面白按钮", fontSize = 15.sp, color = STAGE_INK)
                }
                LiquidButton(
                    onClick = { clicks++ },
                    backdrop = compBackdrop,
                    tint = Color(0xFF0088FF),
                    handFeel = handFeel,
                    traceTag = "Button#3",
                    modifier = Modifier.semantics { contentDescription = "液态按钮（染色）" }
                ) {
                    Text("染色按钮", fontSize = 15.sp, color = Color.White)
                }

                // ---- 组 2：LiquidToggle（开关 64×28dp、旋钮 40×24dp）----
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    LiquidToggle(
                        selected = { toggleOn },
                        onSelect = { toggleOn = it },
                        backdrop = compBackdrop,
                        handFeel = handFeel,
                        traceTag = "Toggle#1",
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .semantics { contentDescription = "液态开关" }
                    )
                    Text(
                        if (toggleOn) "开" else "关",
                        fontSize = 13.sp,
                        color = STAGE_INK
                    )
                }

                // ---- 组 3：LiquidSlider（轨 6dp、旋钮 40×24dp；未按椭圆 ↔ 按住展开成 1:1 胶囊）----
                LiquidSlider(
                    value = { sliderValue },
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.01f,
                    backdrop = compBackdrop,
                    handFeel = handFeel,
                    traceTag = "Slider#1",
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "液态滑杆" }
                )
                Text(
                    "值 = %.2f（拖动旋钮 / 点轨；按住时旋钮非线性展开成玻璃胶囊）".format(sliderValue),
                    fontSize = 11.sp,
                    color = STAGE_INK_SOFT
                )

                // ---- 组 4：LiquidBottomTabs + LiquidBottomTab（容器 64dp、指示层 56dp）----
                LiquidBottomTabs(
                    selectedTabIndex = { tabIndex },
                    onTabSelected = { tabIndex = it },
                    backdrop = compBackdrop,
                    tabsCount = 3,
                    handFeel = handFeel,
                    traceTag = "Tabs#1",
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "液态标签栏" }
                ) {
                    repeat(3) {
                        LiquidBottomTab({ tabIndex = it }) {
                            Text(
                                TAB_GLYPHS[it],
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                color = STAGE_INK
                            )
                            Text(TAB_LABELS[it], fontSize = 11.sp, color = STAGE_INK)
                        }
                    }
                }

                // 单行状态回读（演示页唯一的状态文字：点得到、看得见变化）
                Text(
                    "点击 %d 次 ｜ 开关 %s ｜ 滑杆 %.2f ｜ 标签 %d".format(
                        clicks, if (toggleOn) "开" else "关", sliderValue, tabIndex + 1
                    ),
                    fontSize = 11.sp,
                    color = STAGE_INK_SOFT
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 场外：手感（阻尼）A/B —— 用户口径「现状刚好」，此处可一行切到上游原值 =====
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 【本次修复·选中反馈口径修正】原文案固定写死「拖动 1:1 跟手 = 本工程基准」——切到
            // 上游档时会自相矛盾（上游是「弹簧追手指」）。改为随 handFeel.directDragFollow 出文案：
            // 基准档输出与原文逐字相同 ✓（零像素差异），上游档给出正确的「弹簧追手指 = 上游原值」。
            Text(
                "手感：${handFeel.label}（拖动 ${
                    if (handFeel.directDragFollow) "1:1 跟手 = 本工程基准" else "弹簧追手指 = 上游原值"
                }）",
                fontSize = 11.sp,
                color = Color(0xFF4A5A78)
            )
            Switch(
                checked = DebugSwitches.liquidDampingUpstream,
                onCheckedChange = {
                    DebugSwitches.liquidDampingUpstream = it
                    DebugBridge.revision.intValue = DebugBridge.revision.intValue + 1
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFFFFFFF),
                    checkedTrackColor = Color(0xFF1B4A8F),
                    uncheckedThumbColor = Color(0xFFFFFFFF),
                    uncheckedTrackColor = Color(0x24121D33),
                    uncheckedBorderColor = Color(0x40121D33)
                ),
                modifier = Modifier.semantics { contentDescription = "阻尼用上游原值" }
            )
        }
        Text(
            "上游原值：${LiquidHandFeel.summaryOf(LiquidHandFeel.Upstream)}",
            fontSize = 11.sp,
            color = Color(0xFF6B7A96)
        )
    }
}

/**
 * 【P38】演示场的干净底座：三段柔和竖向渐变（无网格、无色带 —— 上游演示底同样是"一整块底"）。
 * 为什么要干净底：液态玻璃的折射/模糊/色散要有【大尺度色相过渡】才显得干净、像真玻璃；
 * 改动前的六段色带 + 24dp 网格是"调试仪表盘"的观感，正是用户说"不像上游"的一部分 ✗
 * （严格地说：网格给高频、渐变给低频；两种都能看折射 —— 但演示场要的是"像上游"的低频底 ✓）。
 */
private fun DrawScope.drawStageBase() {
    val colors = STAGE_BASE_COLORS
    drawRect(
        Brush.verticalGradient(
            // 四段：蓝 → 紫 → 粉 → 暖黄（比"近白"更显色 ⇒ 折射/模糊在玻璃边缘看得见色相位移 ✓）
            colorStops = arrayOf(
                0f to colors[0],
                0.34f to colors[1],
                0.68f to colors[2],
                1f to colors[3]
            ),
            startY = 0f,
            endY = size.height
        )
    )
}

/** 演示场底色四段（柔和、不透明：深色文字可读；采样层不透明 ⇒ 玻璃里外不会「双涂」发白）。 */
private val STAGE_BASE_COLORS = listOf(
    Color(0xFFD8E6FF),
    Color(0xFFE9DAFF),
    Color(0xFFFFDCE9),
    Color(0xFFFFEFD2)
)

/** 演示场里的文字墨色（深色，压在浅色渐变上仍可读）。 */
private val STAGE_INK = Color(0xFF16233A)
private val STAGE_INK_SOFT = Color(0xFF41506B)

/** 标签栏三个标签的字形与文案（演示用；上游 catalog 用的是 28dp 矢量图标 + "Tab N"）。 */
private val TAB_GLYPHS = listOf("①", "②", "③")
private val TAB_LABELS = listOf("首页", "发现", "我的")

// =====================================================================================
//  回退档：改动前的长列表页（liquidDemoStage=false；保留作 A/B 对照）
// =====================================================================================

/**
 * 改动前的长列表页（逐字保留）。
 *
 * ⚠️ 已知缺陷（P38 取证）：二级页内容槽高 = p=2 档（2885px），p=1 半屏只有 1619px ⇒ 本页
 * 【页首】（标题 + 两个 LiquidButton 段）会落到面板上缘之外且拖不回来 ✗。
 * 只在 liquidDemoStage=false 时使用（对照档），默认档走上面的 [LiquidComponentsDemoStage] ✓
 */
@Composable
private fun LiquidComponentsDemoLegacyList(
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    val handFeel = LiquidHandFeel.current()
    val demoBackdrop = rememberLayerBackdrop()
    val componentsBackdrop: Backdrop =
        if (DebugSwitches.liquidDemoBackdrop) demoBackdrop else backdrop

    Box(modifier.fillMaxWidth()) {
        if (DebugSwitches.liquidDemoBackdrop) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .layerBackdrop(demoBackdrop)
            ) { drawDemoChart() }
        }
        LegacyListBody(componentsBackdrop, handFeel)
    }
}

@Composable
private fun LegacyListBody(componentsBackdrop: Backdrop, handFeel: LiquidHandFeel) {
    // 【本次修复·页内开关的实时反馈】与 LiquidComponentsDemoStage 同一根因同一修法：本函数体里
    // 读的 liquidDampingUpstream（开关）/@Volatile 开关必须在本函数自己的作用域里陪读一次
    // revision，否则点击后界面不跟随（回退：DebugSwitches.liquidDemoLiveFeedback=false）。
    if (DebugSwitches.liquidDemoLiveFeedback) {
        @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    }

    var clicks by remember { mutableIntStateOf(0) }
    var toggleOn by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0.4f) }
    var tabIndex by remember { mutableIntStateOf(0) }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        DemoSectionTitle("液态组件 · 批 1 + 批 2（官方 catalog 移植）")
        Text(
            "批 1：LiquidButton / LiquidToggle；批 2：LiquidSlider / LiquidBottomTabs(+LiquidBottomTab)；" +
                "共用 utils（DampedDragAnimation / InteractiveHighlight）；shapes 依赖 = io.github.kyant0:shapes:1.2.1（与上游同版本）。",
            fontSize = 11.sp,
            color = Color(0xFF4A5A78)
        )

        Spacer(Modifier.height(10.dp))

        LiquidButton(
            onClick = { clicks++ },
            backdrop = componentsBackdrop,
            handFeel = handFeel,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "液态按钮" }
        ) {
            Text(
                "液态按钮",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF10203A)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "按住：触点高光跟随指腹 + 整体微胀；拖动：玻璃随手指形变。点击次数 = $clicks",
            fontSize = 11.sp,
            color = Color(0xFF4A5A78)
        )

        Spacer(Modifier.height(10.dp))

        LiquidButton(
            onClick = {},
            backdrop = componentsBackdrop,
            isInteractive = false,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "液态按钮（非交互对照）" }
        ) {
            Text("非交互对照（无触点高光 / 无形变）", fontSize = 13.sp, color = Color(0xFF10203A))
        }

        Spacer(Modifier.height(14.dp))

        DemoSectionTitle("LiquidToggle")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiquidToggle(
                selected = { toggleOn },
                onSelect = { toggleOn = it },
                backdrop = componentsBackdrop,
                handFeel = handFeel,
                traceTag = "Toggle#1",
                modifier = Modifier.semantics { contentDescription = "液态开关" }
            )
            Text(
                if (toggleOn) "开（可点按 / 可拖动旋钮）" else "关（点一下或拖动旋钮）",
                fontSize = 13.sp,
                color = Color(0xFF12213D)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "旋钮是一小块真玻璃：按下时旋钮放大 1.5× 并在轨道上『融』开一片形变区" +
                "（blur→lens 由按压进度驱动）；拖动时旋钮按速度做拉伸。",
            fontSize = 11.sp,
            color = Color(0xFF4A5A78)
        )

        Spacer(Modifier.height(14.dp))

        DemoSectionTitle("液态滑杆（LiquidSlider · 批 2）")
        LiquidSlider(
            value = { sliderValue },
            onValueChange = { sliderValue = it },
            valueRange = 0f..1f,
            visibilityThreshold = 0.01f,
            backdrop = componentsBackdrop,
            handFeel = handFeel,
            traceTag = "Slider#1",
            modifier = Modifier.semantics { contentDescription = "液态滑杆" }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "值 = %.3f（拖动旋钮或直接点滑轨；点轨 = 弹簧落到该处并回调）".format(sliderValue),
            fontSize = 13.sp,
            color = Color(0xFF12213D)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "旋钮 = 一小块真玻璃：未按时是【微透明扁椭圆】（blur 为主）；按住时按按压进度" +
                "【非线性展开】成 1:1 的玻璃胶囊（scaleX 2/3→1、scaleY 0→1，弹簧驱动）并带色散 lens；" +
                "拖动时按速度拉伸（±0.2 限幅），拖到两端由 translationX 限幅停住。",
            fontSize = 11.sp,
            color = Color(0xFF4A5A78)
        )

        Spacer(Modifier.height(14.dp))

        DemoSectionTitle("液态底部标签栏（LiquidBottomTabs · 批 2）")
        LiquidBottomTabs(
            selectedTabIndex = { tabIndex },
            onTabSelected = { tabIndex = it },
            backdrop = componentsBackdrop,
            tabsCount = 3,
            handFeel = handFeel,
            traceTag = "Tabs#1",
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "液态标签栏" }
        ) {
            repeat(3) { index ->
                LiquidBottomTab({ tabIndex = index }) {
                    Text(
                        TAB_GLYPHS[index],
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF10203A)
                    )
                    Text(
                        TAB_LABELS[index],
                        fontSize = 11.sp,
                        color = Color(0xFF10203A)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "当前选中：第 ${tabIndex + 1} 个（${TAB_LABELS[tabIndex]}）｜" +
                "点标签切换；按住整条可拖动，松手吸附到最近标签。",
            fontSize = 13.sp,
            color = Color(0xFF12213D)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "整条 = 双层玻璃（容器 64dp + 指示层 56dp，指示层录进 backdrop 供旋钮二次采样）；" +
                "拖动时整条做 4dp 橡皮筋位移，指示胶囊带色散 lens + 高光 + 内外阴影。",
            fontSize = 11.sp,
            color = Color(0xFF4A5A78)
        )

        Spacer(Modifier.height(14.dp))

        DemoSectionTitle("手感（阻尼）对照 · 用户口径：现状刚好")
        Text(
            "当前生效：" + LiquidHandFeel.summaryOf(handFeel),
            fontSize = 11.sp,
            color = Color(0xFF12213D)
        )
        Text(
            "上游原值：" + LiquidHandFeel.summaryOf(LiquidHandFeel.Upstream),
            fontSize = 11.sp,
            color = Color(0xFF4A5A78)
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("阻尼用上游原值（默认关 = 我们基准）", style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp)
            Switch(
                checked = DebugSwitches.liquidDampingUpstream,
                onCheckedChange = {
                    DebugSwitches.liquidDampingUpstream = it
                    DebugBridge.revision.intValue = DebugBridge.revision.intValue + 1
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFFFFFFF),
                    checkedTrackColor = Color(0xFF1B4A8F),
                    uncheckedThumbColor = Color(0xFFFFFFFF),
                    uncheckedTrackColor = Color(0x24121D33),
                    uncheckedBorderColor = Color(0x40121D33)
                ),
                modifier = Modifier.semantics { contentDescription = "阻尼用上游原值" }
            )
        }
    }
}

/** 回退档的调试图底（六段柔和色带 + 24dp 校准网格）—— 逐字保留，仅回退档使用。 */
private fun DrawScope.drawDemoChart() {
    val seg = size.width / DEMO_CHART_COLORS.size
    DEMO_CHART_COLORS.forEachIndexed { i, color ->
        drawRect(
            color = color,
            topLeft = Offset(seg * i, 0f),
            size = Size(seg + 1f, size.height)
        )
    }
    val step = 24.dp.toPx()
    val ink = Color(0x14002244)
    val stroke = 1.dp.toPx()
    var x = 0f
    while (x <= size.width + 0.5f) {
        drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        x += step
    }
    var y = 0f
    while (y <= size.height + 0.5f) {
        drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        y += step
    }
}

private val DEMO_CHART_COLORS = listOf(
    Color(0xFFF7E7CF),
    Color(0xFFF6DCE6),
    Color(0xFFDFE3FB),
    Color(0xFFD8F1EC),
    Color(0xFFE6F4D9),
    Color(0xFFFBF0CE)
)

/** 与 GlassControlsPanel 的 SectionTitle 同款视觉（那边是 private，回退档自带一份）。 */
@Composable
private fun DemoSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}
