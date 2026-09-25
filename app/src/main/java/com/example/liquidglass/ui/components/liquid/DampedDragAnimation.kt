/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE）
 *   源文件：app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动（适配差异清单里的 D1/D2/D3）：
 *   D1 包名：com.kyant.backdrop.catalog.utils → com.example.liquidglass.ui.components.liquid
 *   D2 手感参数外置：5 条硬编码 spring(...) 改读 [handFeel]（默认 = 本工程基准，非上游原值）；
 *      handFeel.directDragFollow=true 时 updateValue() 用 snapTo（1:1 跟手）替代逐帧 animateTo
 *      —— 这是为了复刻本工程卡片/面板的拖动语义（见 LiquidHandFeel 头注释）。
 *   D3 取证：新增可选 traceTag，把每帧拖动/回弹读数打进 logcat（tag=LGLiq），由
 *      DebugSwitches.liquidHandFeelTrace 总控（默认关 ⇒ 零日志零开销）。
 * 其余逻辑（press/release/animateToValue/velocityTracker/阈值语义）逐字保留。
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    /**
     * 【P28a 手感标定】弹簧参数组。默认 = [LiquidHandFeel.Baseline]（本工程现状基准）。
     * 上游原值 = [LiquidHandFeel.Upstream]，可由 DebugSwitches.liquidDampingUpstream 一行切换。
     */
    val handFeel: LiquidHandFeel = LiquidHandFeel.Baseline,
    /** 【P28a 取证】非空且 DebugSwitches.liquidHandFeelTrace=true 时逐帧打点（logcat tag=LGLiq）。 */
    val traceTag: String? = null,
    /**
     * 【P28a 取证】拖动宽度（px，= 调用方 onDrag 里用来把 px 换算成值的那把尺）。
     * 传入后，逐帧日志会多一个【跟手滞后(px)】= 手指累计位移 − 旋钮实际位移 —— 两只手感的
     * 差别（上游"弹簧追手指" vs 本工程"1:1 跟手"）就是这一列数字。0（默认）= 不计算。
     */
    val traceDragWidthPx: Float = 0f
) {

    private val valueAnimationSpec =
        handFeel.valueSpec(visibilityThreshold)
    private val velocityAnimationSpec =
        handFeel.velocitySpec(visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        handFeel.pressSpec(0.001f)
    private val scaleXAnimationSpec =
        handFeel.scaleSpec(0.001f)
    private val scaleYAnimationSpec =
        handFeel.scaleSpec(0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    /** 取证用：本轮手势的起点（elapsedRealtime ms）、手指累计位移（px）、起始值。 */
    private var traceT0Ms = 0L
    private var traceFingerPx = 0f
    private var traceStartValue = 0f

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                traceT0Ms = android.os.SystemClock.elapsedRealtime()
                traceFingerPx = 0f
                traceStartValue = value
                trace("down")
                press()
            },
            onDragEnd = {
                onDragStopped()
                trace("up")
                release()
            },
            onDragCancel = {
                onDragStopped()
                trace("cancel")
                release()
            }
        ) { change, dragAmount ->
            traceFingerPx += dragAmount.x
            trace("drag")
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            if (handFeel.directDragFollow) {
                // 【P28a 基准】拖动 = 1:1 跟手：值直接跟随手指（本工程卡片/面板的拖动语义），
                // 弹簧只作用于"点击/程序切换"（animateToValue）与松手后的按压回弹 ⇒ 无滞后。
                valueAnimation.snapTo(targetValue)
                updateVelocity()
            } else {
                // 上游语义：每一帧把值当作弹簧目标 animateTo ⇒ 手指越快，滞后越大（"阻尼感"来源）。
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
            }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch {
                    // 【P28a 适配】上游 animateTo 的逐帧块是"无参 receiver lambda"
                    // （`Animatable<T,V>.() -> Unit`），因此这里不带参数、直接在块内读 this.value
                    // （= 同一时刻的动画值），语义与上游 `{ updateVelocity() }` 完全一致 ✓
                    valueAnimation.animateTo(targetValue, valueAnimationSpec) {
                        trace("settle")
                    }
                }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }

    /** 【P28a 取证】逐帧读数（默认关；总开关 DebugSwitches.liquidHandFeelTrace）。 */
    private fun trace(phase: String) {
        val tag = traceTag ?: return
        if (!com.example.liquidglass.debug.DebugSwitches.liquidHandFeelTrace) return
        val t = android.os.SystemClock.elapsedRealtime() - traceT0Ms
        // 跟手滞后（px）= 手指累计位移 − 旋钮实际位移（旋钮位移 = Δ值 × 拖动宽度）
        val lagPx =
            if (traceDragWidthPx > 0f) traceFingerPx - (value - traceStartValue) * traceDragWidthPx
            else Float.NaN
        android.util.Log.i(
            "LGLiq",
            "%s phase=%s t=%dms finger=%+.1fpx value=%.4f target=%.4f lag=%.1fpx vel=%.3f press=%.3f scale=%.3f/%.3f 参数=%s"
                .format(
                    tag, phase, t, traceFingerPx, value, targetValue, lagPx,
                    velocity, pressProgress, scaleX, scaleY, handFeel.label
                )
        )
    }
}
