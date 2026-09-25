/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE / LICENSES/Backdrop-APACHE-2.0.txt）
 *   源文件：app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidSlider.kt（212 行）
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动（适配差异清单 D1/D2/D3/D4）：
 *   D1 包名：com.kyant.backdrop.catalog.components → com.example.liquidglass.ui.components.liquid
 *      （同包 ⇒ 上游的 `import ...catalog.utils.DampedDragAnimation` 删除）
 *   D2 新增 handFeel 参数（默认 = LiquidHandFeel.current() = 本工程基准）并透传给 DampedDragAnimation；
 *      基准档下 DampedDragAnimation.updateValue 用 snapTo（1:1 跟手）而非逐帧 animateTo ⇒ 拖动不再
 *      "弹簧追手指"，松手/点击滑轨仍走弹簧 —— **阻尼手感已按我们的基准标定，非上游原值**。
 *      px↔value 映射与批 1 的 Button/Toggle 同一套语义：delta = 值跨度 × (dragAmount.x / trackWidth)
 *      （上游逐字公式，未改 ✗）——所以追踪口径与 Toggle 的 dragWidth=20dp 完全一致，可直接对比。
 *   D3 新增 traceTag（默认 null）：非空时逐帧打点（logcat tag=LGLiq，总开关 DebugSwitches.liquidHandFeelTrace），
 *      traceDragWidthPx = trackWidth / 值跨度 = 【每 1 个 value 单位对应的 px】⇒ 滞后列与 Toggle 同量纲。
 *   D4 新增【逐帧形变取证】：按下期间用 withFrameNanos 每帧打一行 `tag frame=… press=… shX=… shY=…`
 *      （shX/shY = 旋钮里那段 drawBackdrop 的 scaleX 2/3→1、scaleY 0→1，与绘制式逐字同源）
 *      ⇒ 验收要的"按下时旋钮由椭圆非线性展开"有逐帧数字，而不是只靠肉眼。
 *   D5 色散 A/B：chromaticAberration 由 DebugSwitches.liquidSliderNoDispersion 一行切换（默认 false
 *      = 上游原值 = 带色散）⇒ 色散存在性可用【同一状态、只切这一个开关】的像素差做证据。
 *   D6 拖动屏蔽 Y 轴（P50·真机反馈「拖控件时控制中心跟着变小」）：旋钮拖动节点最外层加
 *      `Modifier.liquidDragYShield()`（DragYShield.kt）——拖动期间把位移标记为已消费，
 *      阻断垂向分量上抛到 列表 verticalScroll → nestedScroll → 面板 sheetConnection。
 *      一行回退：DebugSwitches.liquidDragYShield=false（默认开）。
 * 其余逻辑（滑轨 6dp Capsule + accent 宽随 progress、点滑轨 detectTapGestures 换算 + animateToValue、
 *   旋钮 40×24dp、translationX 限幅 [-w/4, trackWidth-3w/4]、LTR/RTL 分符、blur(8dp×(1-press))、
 *   lens(10dp×press, 14dp×press, 色散)、Highlight.Ambient/1.5、Shadow(4dp, black 5%)、
 *   InnerShadow(4dp×press)、velocity/10 拉伸 ±0.2、onDrawSurface 白 alpha=1-press）逐字保留。
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.example.liquidglass.debug.DebugSwitches
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** 【D4 取证】逐帧读数的可变槽（普通字段，不参与组合 ⇒ 不触发重组/重绘）。 */
private class SliderTraceState {
    /** 手指累计位移（px，onDrag 里累加；DampedDragAnimation 内部那份是 private，故这里自记一份）。 */
    var fingerPx = 0f

    /** 本次手势的起始值（down 时采）。 */
    var startValue = 0f

    /** 逐帧序号与起始帧时间。 */
    var frame = 0
    var t0Ns = 0L
}

@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /** 【P28a 手感标定】弹簧参数组（默认 = 本工程基准；见 LiquidHandFeel）。 */
    handFeel: LiquidHandFeel = LiquidHandFeel.current(),
    /** 【P28a 取证】非空时逐帧/逐事件打点（logcat tag=LGLiq，总开关 DebugSwitches.liquidHandFeelTrace）。 */
    traceTag: String? = null
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        /** 【D3】每 1 个 value 单位对应的 px（= 拖动那把尺；滞后列与 Toggle 同量纲）。 */
        val dragWidthPerValue =
            trackWidth / (valueRange.endInclusive - valueRange.start)
        /** 【D4】逐帧形变/跟手读数槽。 */
        val traceState = remember { SliderTraceState() }
        val dampedDragAnimation = remember(animationScope, handFeel) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {
                    traceState.fingerPx = 0f
                    // 【注意】外层有个同名参数 `value: () -> Float` ⇒ 这里必须显式写 this.value
                    // （= DampedDragAnimation 的值）才不会解析到那个函数参数 ✗（编译期已抓到一次）
                    traceState.startValue = this.value
                },
                onDragStopped = {
                    if (didDrag) {
                        onValueChange(targetValue)
                    }
                },
                onDrag = { _, dragAmount ->
                    traceState.fingerPx += dragAmount.x
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount.x / trackWidth)
                    onValueChange(
                        if (isLtr) (targetValue + delta).coerceIn(valueRange)
                        else (targetValue - delta).coerceIn(valueRange)
                    )
                },
                handFeel = handFeel,
                traceTag = traceTag,
                traceDragWidthPx = dragWidthPerValue
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { value() }
                .collectLatest { value ->
                    if (dampedDragAnimation.targetValue != value) {
                        dampedDragAnimation.updateValue(value)
                    }
                }
        }
        // 【D4 取证·逐帧形变】按下期间每帧一行（默认关：traceTag=null 或总开关关 ⇒ 不采样、零日志零开销）。
        // 覆盖的正是上游那句「rememberBackdrop(trackBackdrop){ scale(lerp(2/3,1,p), lerp(0,1,p)) }」
        // ⇒ 椭圆(2/3 宽 × 0 高) 非线性展开成 1:1 胶囊的逐帧数字（p 本身是弹簧 ⇒ 时间上非线性）。
        if (traceTag != null) {
            LaunchedEffect(traceTag, dampedDragAnimation) {
                while (true) {
                    snapshotFlow { dampedDragAnimation.pressProgress }
                        .first { it > 0f && DebugSwitches.liquidHandFeelTrace }
                    traceState.frame = 0
                    traceState.t0Ns = 0L
                    while (true) {
                        withFrameNanos { nowNs ->
                            if (traceState.t0Ns == 0L) traceState.t0Ns = nowNs
                            val press = dampedDragAnimation.pressProgress
                            // 与下面 drawBackdrop 里的式子【逐字同源】（改一处必须改两处）
                            val shX = lerp(2f / 3f, 1f, press)
                            val shY = lerp(0f, 1f, press)
                            val lagPx =
                                traceState.fingerPx -
                                    (dampedDragAnimation.value - traceState.startValue) * dragWidthPerValue
                            android.util.Log.i(
                                "LGLiq",
                                "%s frame=%d t=%dms press=%.3f shX=%.3f shY=%.3f kX=%.3f kY=%.3f value=%.4f target=%.4f lag=%.1fpx 参数=%s"
                                    .format(
                                        traceTag, traceState.frame,
                                        (nowNs - traceState.t0Ns) / 1_000_000,
                                        press, shX, shY,
                                        dampedDragAnimation.scaleX, dampedDragAnimation.scaleY,
                                        dampedDragAnimation.value, dampedDragAnimation.targetValue,
                                        lagPx, handFeel.label
                                    )
                            )
                            traceState.frame++
                        }
                        if (dampedDragAnimation.pressProgress <= 0.0005f && traceState.frame > 1) break
                    }
                }
            }
        }

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .pointerInput(animationScope) {
                        detectTapGestures { position ->
                            val delta = (valueRange.endInclusive - valueRange.start) * (position.x / trackWidth)
                            val targetValue =
                                (if (isLtr) valueRange.start + delta
                                else valueRange.endInclusive - delta)
                                    .coerceIn(valueRange)
                            dampedDragAnimation.animateToValue(targetValue)
                            onValueChange(targetValue)
                        }
                    }
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                // 【P50·拖动屏蔽 Y 轴·D6】最外层（排在下面的拖动节点之前）⇒ 同节点手势先跑、本节点最后跑，
                //   只把位移对【祖先】（列表 verticalScroll → nestedScroll → 面板 sheetConnection）标成已消费
                //   ⇒ 拖旋钮时面板 p 不再跟着变小；改前行为（拖动循环从不 consume）逐字保留在
                //   上游 DragGestureInspector/DampedDragAnimation 里，本行之外一字未动 ✓
                //   一行回退：DebugSwitches.liquidDragYShield=false（默认开，见 DragYShield.kt 文件头）
                .liquidDragYShield()
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = !DebugSwitches.liquidSliderNoDispersion
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}
