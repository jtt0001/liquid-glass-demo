package com.example.liquidglass.diamond

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.liquidglass.debug.AppDebugLog
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.runtimeShaderEffect
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.flow.first

/**
 * 【3D 钻石演示页】全屏演示页：一张全屏节点挂 [drawBackdrop] 跑钻石 AGSL（逐面折射 / 色散 / TIR），
 * 上层是可点的控制 UI。
 *
 * 结构（契约要求）：
 *  根 Box（fillMaxSize + 黑底兜底 + 整页 pointerInput 拖动）
 *   ① 全屏节点：Modifier.fillMaxSize().drawBackdrop(...) —— ✗ 这一层【不加】graphicsLayer 旋转/缩放
 *      （本项目踩过"采样坐标对不上"的坑：旋转/缩放会让背景采样坐标与节点坐标不一致）；
 *   ② UI 层：返回 / 标题 / 提示 / 参数开关 / 读数。UI 层自身不挂 pointerInput（只有控件各自消化
 *      自己的手势）⇒ 页面的拖动不被 UI 挡住；返回按钮只吃它自己的点击。
 *
 * 每帧纪律（本项目性能教训）：
 *  · 姿态（yaw/pitch）与其余动画量只在【绘制阶段】读（[drawBackdrop] 的 effects 里）⇒ 快照订阅
 *    自动重录图层，不进组合阶段；✗ 它们绝不出现在 .size() / .padding() / .clip() 里；
 *  · 旋转矩阵缓冲 [FloatArray] 用 remember 跨帧复用（✗ effects 里不新建数组），
 *    也不在 effects 里新建字符串；
 *  · 逐帧验收日志（tag `LGDiamond`）按 ≈200ms 节流（✗ 不每帧打）。
 *
 * 尺寸：钻石腰棱半径 = sizeFraction × min(节点宽, 节点高) × [GIRDLE_RADIUS_RATIO]（= uiState.glassSize ∈ 0.2..0.5）。
 */
@Composable
fun DiamondDemoPage(
    state: DiamondDemoState,
    backdrop: LayerBackdrop,
    sizeFraction: Float,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 旋转矩阵的跨帧复用缓冲（effects 每帧调 state.rotRows(它)，绝不新建数组）
    val rotationRows = remember { FloatArray(9) }

    // 帧驱动：真实帧间隔 → state.tick（惯性 + 自动旋转）+ 节流日志
    DiamondFrameDriver(state = state)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)   // 兜底：Shader 不可用/未执行时是纯黑，不是花屏
            .pointerInput(state) {
                detectDragGestures(
                    onDragEnd = { state.endDrag() },
                    onDragCancel = { state.endDrag() }
                ) { change, dragAmount ->
                    change.consume()
                    state.drag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // ---------------- ① 全屏钻石层（唯一 drawBackdrop 调用点） ----------------
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    // 关掉库自带高光/阴影：边缘完全交给钻石 AGSL
                    highlight = null,
                    shadow = null,
                    effects = {
                        padding = 0f
                        val w = size.width
                        val h = size.height
                        val radiusPx = sizeFraction.coerceIn(MIN_SIZE_FRACTION, 1f) * min(w, h) * GIRDLE_RADIUS_RATIO
                        // dump 读数用（普通 @Volatile 字段：写入不触发重绘/重组）
                        state.lastRadiusPx = radiusPx

                        runtimeShaderEffect(
                            // 源码/key 只随切工变（✗ 别每帧变 key：否则每帧重编译 shader）。
                            // key 走【预生成表】：A 的 cacheKey 是 `+` 拼接，每帧调用会新建一个 String
                            //（本项目 BackdropAdapter 已有同样的教训：缓存 key 也要预生成）。
                            key = ShaderCacheKeys.getValue(state.cut),
                            shaderString = DiamondAgsl.source(state.cut),
                            // 背景内容绑定到 AGSL 的 content uniform shader
                            uniformShaderName = DiamondUniforms.INPUT
                        ) {
                            setFloatUniform(DiamondUniforms.RES, w, h)
                            setFloatUniform(DiamondUniforms.CENTER, w * 0.5f, h * 0.5f)
                            setFloatUniform(DiamondUniforms.RADIUS, radiusPx)
                            setFloatUniform(DiamondUniforms.CAM_Z, DiamondModel.CAM_Z)
                            setFloatUniform(DiamondUniforms.DEPTH, DiamondModel.DEPTH)
                            // 姿态：三个行向量（缓冲复用；读 yaw/pitch 状态 ⇒ 本 effects 自动订阅）
                            state.rotRows(rotationRows)
                            setFloatUniform(DiamondUniforms.ROW0, rotationRows[0], rotationRows[1], rotationRows[2])
                            setFloatUniform(DiamondUniforms.ROW1, rotationRows[3], rotationRows[4], rotationRows[5])
                            setFloatUniform(DiamondUniforms.ROW2, rotationRows[6], rotationRows[7], rotationRows[8])
                            setFloatUniform(DiamondUniforms.IOR, state.ior)
                            setFloatUniform(DiamondUniforms.DISPERSION, state.dispersion)
                            setFloatUniform(DiamondUniforms.BOUNCES, state.bounces.toFloat())
                            setFloatUniform(DiamondUniforms.FRESNEL, state.fresnel)
                            // 环境采样模型（修复开关）：1 = 几何一致环境（默认）/ 0 = 旧软投影回退
                            setFloatUniform(DiamondUniforms.ENV_MODEL, state.envModel.toFloat())
                            // 弹射用尽物理收尾（修复开关）：1 = 补链 + 真界面透射率（默认）/ 0 = 旧 0.5
                            setFloatUniform(DiamondUniforms.TRAPPED_FIX, state.trappedFix.toFloat())
                            // 逆投影 y 符号（修复开关）：1 = 世界点→屏幕的正确投影（默认）/ 0 = H1 原样（y 取反）
                            setFloatUniform(DiamondUniforms.ENV_Y_FIX, state.envYFix.toFloat())
                            // 切面边缘 AA 宽度（px）
                            setFloatUniform(DiamondUniforms.AA, AA_WIDTH_DP * density)
                            // 调试模式 0..4：AGSL 侧为 float uniform（与 uBounces 同一风格）；
                            // 若 A 改成 `uniform int uDebug`，只需把本行换成 setIntUniform(DiamondUniforms.DEBUG, state.debugMode)
                            setFloatUniform(DiamondUniforms.DEBUG, state.debugMode.toFloat())
                        }
                    }
                )
        )

        // ---------------- ② UI 层（不挂 pointerInput：不吃页面拖动） ----------------
        DiamondOverlay(state = state, onBack = onBack)
    }
}

/**
 * 帧驱动：每帧把【真实帧间隔】交给 [DiamondDemoState.tick]（惯性衰减 + 自动旋转），
 * 并按 ≈[FRAME_LOG_INTERVAL_MS] 节流打一行逐帧验收日志（tag `LGDiamond`）。
 *
 * 空闲（自动旋转关 + 无惯性）时用 snapshotFlow 挂起：不为保持 Shader 活跃而永久每帧重绘
 * ——本项目既有做法（见 ui/LiquidGlassCard.kt 的 GlassCardTimeDriver），静止期零开销。
 */
@Composable
private fun DiamondFrameDriver(state: DiamondDemoState) {
    LaunchedEffect(state) {
        var lastFrameNanos = 0L
        var lastLogMs = SystemClock.elapsedRealtime()
        while (true) {
            snapshotFlow { state.animating }.first { it }
            lastFrameNanos = 0L
            while (state.animating) {
                val frameNanos = withFrameNanos { it }
                val dt = if (lastFrameNanos == 0L) {
                    0f
                } else {
                    // 真实帧间隔；clamp ≤ 0.05s（长停顿后不跳帧/不甩飞）
                    ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(MAX_FRAME_DT_SECONDS)
                }
                lastFrameNanos = frameNanos
                if (dt > 0f) state.tick(dt)
                lastLogMs = logDiamondFrame(state, lastLogMs)
            }
        }
    }
}

/**
 * 节流日志：≈[FRAME_LOG_INTERVAL_MS] 一行 `yaw=.. pitch=.. spin=.. inertia=.. t=..`（t = elapsedRealtime ms）。
 * [AppDebugLog] 关时零开销：不取时间、不拼字符串（✗ 不每帧打）。
 */
private fun logDiamondFrame(state: DiamondDemoState, lastLogMs: Long): Long {
    if (!AppDebugLog.enabled) return lastLogMs
    val now = SystemClock.elapsedRealtime()
    if (now - lastLogMs < FRAME_LOG_INTERVAL_MS) return lastLogMs
    AppDebugLog.log(
        "LGDiamond",
        "yaw=%.2f pitch=%.2f spin=%.2f inertia=%.2f t=%d".format(
            Locale.US,
            state.yawDeg,
            state.pitchDeg,
            state.spinDegPerSec,
            state.inertiaSpeedDegPerSec,
            now
        )
    )
    return now
}

// ===========================================================================
// UI 层（大白话文案 + 深色半透明胶囊底 + 白字；交互控件自己消化自己的手势）
// ===========================================================================

@Composable
private fun DiamondOverlay(
    state: DiamondDemoState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {

        // 左上：返回（clip + clickable 都在它自己身上 ⇒ 只吃自己的点击，不吃页面拖动）
        DiamondCapsule(
            text = "← 返回",
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 12.dp)
                .clip(DiamondCapsuleShape)
                .clickable { onBack() }
                .semantics { contentDescription = "返回" }
        )

        // 顶部居中：标题 + 提示
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DiamondCapsule(text = "3D 钻石", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            DiamondCapsule(text = "拖动旋转 · 松手惯性", fontSize = 12.sp)
        }

        // 底部：参数开关 + 读数
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .fillMaxWidth()
                .background(PanelBackground, DiamondPanelShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 自动旋转
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("自动旋转", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = state.autoSpin,
                    onCheckedChange = { state.autoSpin = it },
                    colors = DiamondSwitchColors,
                    modifier = Modifier.semantics { contentDescription = "自动旋转" }
                )
            }

            // 弹射次数（0 = 只折一次）
            Text("弹射次数（0 = 只折一次）", color = LabelColor, fontSize = 11.sp)
            DiamondChipRow(
                labels = BounceChoiceLabels,
                selectedIndex = state.bounces,
                a11yPrefix = "弹射次数",
                onSelect = { state.bounces = it }
            )

            // 色散
            Text("色散（彩边分光）", color = LabelColor, fontSize = 11.sp)
            DiamondChipRow(
                labels = DispersionChoiceLabels,
                selectedIndex = DispersionChoices.indexOfFirst { it.second == state.dispersion },
                a11yPrefix = "色散",
                onSelect = { state.dispersion = DispersionChoices[it].second }
            )

            // 底部小字读数（面数 / 弹射 / 色散）
            Text(
                text = "面数 ${state.cut.facetCount} · 弹射 ${state.bounces} 次 · 色散 " +
                    (if (state.dispersion > 0f) "开（%.3f）".format(Locale.US, state.dispersion) else "关"),
                color = ReadoutColor,
                fontSize = 11.sp
            )
        }
    }
}

/** 深色半透明胶囊 + 白字（页面所有文案的统一底板）。 */
@Composable
private fun DiamondCapsule(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight? = null
) {
    Box(
        modifier
            .background(CapsuleBackground, DiamondCapsuleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = fontSize, fontWeight = fontWeight)
    }
}

/** 一排可点选项（与项目风格一致的 FilterChip；横向可滑，不撑破窄屏）。 */
@Composable
private fun DiamondChipRow(
    labels: List<String>,
    selectedIndex: Int,
    a11yPrefix: String,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                shape = DiamondChipShape,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label, fontSize = 12.sp) },
                colors = DiamondChipColors,
                modifier = Modifier.semantics { contentDescription = "$a11yPrefix：$label" }
            )
        }
    }
}

// ===========================================================================
// 常量 / 配色 / 形状（顶层私有，组合期只读）
// ===========================================================================

/** 腰棱半径 = sizeFraction × min(节点宽, 节点高) × 本比例（契约值 0.55）。 */
private const val GIRDLE_RADIUS_RATIO = 0.55f

/** 尺寸比例下限：父会话传的是 uiState.glassSize ∈ 0.2..0.5，这里只做防御性钳制。 */
private const val MIN_SIZE_FRACTION = 0.05f

/** 单帧步长上限（秒）：卡顿/长停后不跳帧（契约：clamp ≤ 0.05s）。 */
private const val MAX_FRAME_DT_SECONDS = 0.05f

/** 逐帧验收日志的节流间隔（ms）。 */
private const val FRAME_LOG_INTERVAL_MS = 200L

/** 切面边缘 AA 宽度（dp → px 后作为 uAA 上传）。 */
private const val AA_WIDTH_DP = 1f

/** 胶囊底（深色半透明，白字高对比）。 */
private val CapsuleBackground = Color(0xB3000000)

/** 底部面板底（更淡一些，不抢钻石）。 */
private val PanelBackground = Color(0x8C000000)

/** 小标题色与读数色。 */
private val LabelColor = Color(0xFFE8EEF9)
private val ReadoutColor = Color(0xCCFFFFFF)

/** 胶囊 / 面板 / 选项形状（与项目一致的圆角观感；只用 androidx.compose 的形状，✗ 不跨文件引用内部类型）。 */
private val DiamondCapsuleShape = RoundedCornerShape(percent = 50)
private val DiamondPanelShape = RoundedCornerShape(18.dp)
private val DiamondChipShape = RoundedCornerShape(9.dp)

/** 深色页面上的选项配色（选中 = 蓝底白字）。 */
private val DiamondChipColors: SelectableChipColors
    @Composable get() = FilterChipDefaults.filterChipColors(
        containerColor = Color(0x33FFFFFF),
        labelColor = Color(0xFFF1F5FF),
        selectedContainerColor = Color(0xFF3D7BE0),
        selectedLabelColor = Color(0xFFFFFFFF)
    )

/** 深色页面上的开关配色。 */
private val DiamondSwitchColors: SwitchColors
    @Composable get() = SwitchDefaults.colors(
        checkedThumbColor = Color(0xFFFFFFFF),
        checkedTrackColor = Color(0xFF3D7BE0),
        uncheckedThumbColor = Color(0xFFFFFFFF),
        uncheckedTrackColor = Color(0x24000000),
        uncheckedBorderColor = Color(0x66FFFFFF)
    )

/** 弹射次数选项（0 = 只折一次）。 */
private val BounceChoiceLabels: List<String> = listOf("0", "1", "2", "3", "4")

/**
 * 每个切工的 shader 缓存 key（类初始化时预生成一次）。
 * effects 每帧都要用 key，而 [DiamondAgsl.cacheKey] 是字符串 `+` 拼接 ⇒ 直接调它会每帧新建一个 String。
 * 与 A 的 key 取值完全一致（调的就是同一个函数），只是把调用时机从"每帧"提前到"一次"。
 */
private val ShaderCacheKeys: Map<DiamondCut, String> =
    DiamondCut.entries.associateWith { DiamondAgsl.cacheKey(it) }

/** 色散档位（大白话标签 ↔ 强度；0 = 关）。 */
private val DispersionChoices: List<Pair<String, Float>> = listOf(
    "关" to 0f,
    "低" to 0.006f,
    "中" to 0.012f,
    "高" to 0.024f
)

private val DispersionChoiceLabels: List<String> = DispersionChoices.map { it.first }
