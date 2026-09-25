package com.example.liquidglass.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.debug.DebugSwitches
import com.example.liquidglass.R
import com.example.liquidglass.backdrop.BackdropAdapter
import com.example.liquidglass.backdrop.glass
import com.example.liquidglass.debug.AppDebugLog
import com.example.liquidglass.glass.GlassDebugMode
import com.example.liquidglass.glass.GlassParameters
import com.example.liquidglass.glass.GlassQuality
import com.example.liquidglass.glass.GlassShape
import com.example.liquidglass.glass.PressInsetShape
import kotlinx.coroutines.flow.first
import kotlin.math.max

/**
 * 玻璃卡片状态：位置 / 按压 / 速度 / 时间，全部为高频状态，
 * 在绘制层读取（graphicsLayer / uniform），不触发整页重组。
 *
 * 说明：手势回调（awaitEachGesture / drag）是受限协程作用域，
 * 不能在其中调用 suspend 动画；因此位置与按压使用普通快照状态，
 * 回弹动画通过 [settleNonce] 事件交给独立的 LaunchedEffect 执行。
 */
class GlassCardState {

    /**
     * 【P37 取证】这块卡的标签（"0".."3"），只被 [DebugSwitches.dualCardTrace] 的逐帧探针读取：
     * eff / move 两行都带上它 ⇒ N 块卡能直接按【卡号】统计失效计数（不必靠 identityHashCode 反推，
     * 之前只有 id ⇒ 多卡场景下无法一眼看出"哪块静止卡停更了"）。
     * 普通字段（非快照）：写一次不再变，不参与组合/失效，零行为影响。
     */
    var traceLabel: String = ""

    /** 卡片位置偏移（相对默认位置，px）。 */
    var offsetX by mutableFloatStateOf(0f)
        internal set
    var offsetY by mutableFloatStateOf(0f)
        internal set

    /**
     * 卡片当前尺寸（px，由 LiquidGlassCard 的 onSizeChanged 写入）。
     * 只在调用方 bounds 不可用（未测量哨兵 / 非有限值）时用于兜底沙箱：
     * 保证"这一轴还能动"且"卡片不会被拖到屏幕外找不回来"。
     */
    var cardSizePx by mutableStateOf(IntSize.Zero)
        internal set

    /**
     * 拖动世代：手指每按下一次 +1。
     * 位置的唯一写入者规则 —— 拖动期间是手势，其余时间才是回弹动画：
     * 回弹动画据此判断"手指已接管"，立刻停写 offsetX/offsetY（单轴锁死的根因修复）。
     */
    var dragEpoch by mutableIntStateOf(0)
        private set

    /** 按压进度 0..1。 */
    var pressProgress by mutableFloatStateOf(0f)
        internal set

    /** 触点卡片局部坐标（px）。 */
    var pressPositionPx by mutableStateOf(Offset.Zero)
        private set

    /** 最近一次拖动速度（px/s，已限幅到 ±2400）。 */
    var dragVelocityPx by mutableStateOf(Offset.Zero)
        private set

    /** 拖动方向（归一化，无速度时为 Zero）。 */
    var stretchDirection by mutableStateOf(Offset.Zero)
        private set

    /** 交互动画时间（秒）：仅在按压 / 拖动 / 回弹期间推进，静止归零。 */
    var timeSeconds by mutableFloatStateOf(0f)
        internal set

    /** 是否正在拖动。 */
    var isDragging by mutableStateOf(false)
        private set

    /**
     * 卡片当前是否处于【程序化位移】（面板开合避让动画）状态。
     * 与用户拖动严格区分：程序化位移结束后，面板关闭时仍可接管归位；
     * 用户亲手拖动过则此标记清除，自动逻辑彻底不再干预（防"拖到 1/3 被拉回"）。
     */
    var isProgrammaticMove by mutableStateOf(false)
        internal set

    /** 是否正在回弹。 */
    var isSettling by mutableStateOf(false)
        internal set

    /** 是否有活跃动画（驱动 time uniform 的帧循环开关）。 */
    var animationActive by mutableStateOf(false)
        internal set

    // ---- 手势 → 动画 事件通道（非 suspend，可安全在受限作用域调用）----
    /** 按压事件计数：递增触发按压进动画。 */
    var pressDownNonce by mutableIntStateOf(0)
        private set

    /** 回弹事件计数：递增触发弹簧回弹动画。 */
    var settleNonce by mutableIntStateOf(0)
        private set

    /** 回弹初速度（px/s）。 */
    var settleVelocityPx by mutableStateOf(Offset.Zero)
        private set

    fun setPressPosition(position: Offset) {
        pressPositionPx = position
    }

    fun updateDragging(dragging: Boolean) {
        isDragging = dragging
        if (dragging) {
            // 【单轴锁死·根因修复】手指按下 = 位置写入权归手势（唯一写入者）：
            //  - dragEpoch +1：正在跑的回弹动画据此立刻停写 offsetX/offsetY
            //   （它每帧写一次，晚于触摸事件、早于绘制，会把手指位移盖掉、
            //    并把下一帧的累积基准重置回"松手时"的位置 → 该轴完全不动）；
            //  - isSettling 归零：回弹已被拖动接管，不再处于"回弹中"。
            dragEpoch++
            isSettling = false
            animationActive = true
            // 用户一旦亲手拖动，程序化位移即告结束——面板关闭不再接管位置
            isProgrammaticMove = false
        }
    }

    fun setVelocity(velocity: Offset) {
        val clamped = Offset(
            velocity.x.coerceIn(-2400f, 2400f),
            velocity.y.coerceIn(-2400f, 2400f)
        )
        dragVelocityPx = clamped
        val lenSquared = clamped.getDistanceSquared()
        stretchDirection = if (lenSquared > 2500f) {
            Offset(clamped.x / kotlin.math.sqrt(lenSquared), clamped.y / kotlin.math.sqrt(lenSquared))
        } else {
            Offset.Zero
        }
        if (lenSquared > 2500f) animationActive = true
    }

    /**
     * 拖动中更新位置（受限作用域内可安全调用：非 suspend）。
     *
     * 【单轴锁死·根治】两轴【各自独立】解析成"可动窗口"再钳制，入口 bounds 再脏也不会锁死某一轴：
     *  - 反序（max<min）/ 退化（max==min）/ 超小 / 超大（未测量时调用方给 ±1e4 哨兵）/ 非有限（NaN、±Inf）
     *    全部在本函数内归一化，策略见 [resolveOffsetWindow]；
     *  - 目标值非有限时保持原值：绝不让 NaN/Inf 写进 graphicsLayer；
     *  - 任何输入都不抛异常（旧实现直接 coerceIn(rawLo, rawHi)，min>max 时会抛
     *    IllegalArgumentException，而退化轴又被撑成 ±1px ≈ 观感上"这一轴完全不动"）。
     */
    fun updatePosition(targetX: Float, targetY: Float, bounds: Rect) {
        val win = windowScratch
        val fixX = resolveOffsetWindow(bounds.left, bounds.right, cardSizePx.width.toFloat(), win)
        val loX = win[0]; val hiX = win[1]
        val fixY = resolveOffsetWindow(bounds.top, bounds.bottom, cardSizePx.height.toFloat(), win)
        val loY = win[0]; val hiY = win[1]
        // 【P32 取证】拖动写入侧打点（只读入参 + 卡标签，不读任何快照状态 ⇒ 零订阅影响）
        if (DebugSwitches.dualCardTrace) {
            android.util.Log.i(
                "P32",
                "move id=" + System.identityHashCode(this) +
                    " card=" + traceLabel +
                    " tx=" + targetX + " ty=" + targetY +
                    " t=" + android.os.SystemClock.elapsedRealtime()
            )
        }
        offsetX = if (targetX.isFinite()) targetX.coerceIn(loX, hiX) else offsetX
        offsetY = if (targetY.isFinite()) targetY.coerceIn(loY, hiY) else offsetY
        if (fixX != null || fixY != null) logWindowFix(bounds, fixX, loX, hiX, fixY, loY, hiY)
    }

    /** 拖动热路径的窗口暂存：每个指针事件解析两次窗口，复用同一个数组（不新增分配）。 */
    private val windowScratch = FloatArray(2)

    /** 已上报过的窗口修复原因组合（每种原因只报一次：不刷屏，也不会漏掉不同类型）。 */
    private val windowFixLogged = HashSet<String>()

    /**
     * 一次性上报"入口 bounds 被本文件修复"的完整数据：原始四边、卡片尺寸、修复后两轴窗口、当前偏移。
     * 同时走 [AppDebugLog]（用户可一键导出）与 Logcat，便于下次导日志时直接判断
     * 问题是否出在调用方 bounds（若是，日志里会出现 raw= 退化/反序/哨兵 的原始值）。
     */
    private fun logWindowFix(
        bounds: Rect, fixX: String?, loX: Float, hiX: Float, fixY: String?, loY: Float, hiY: Float
    ) {
        if (!windowFixLogged.add("${fixX ?: "-"}|${fixY ?: "-"}")) return
        val msg = "OFFSET WINDOW FIX x=$fixX y=$fixY" +
            " raw=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]" +
            " card=${cardSizePx.width}x${cardSizePx.height}" +
            " winX=[$loX,$hiX] winY=[$loY,$hiY] offset=($offsetX,$offsetY)"
        android.util.Log.w("LiquidGlass", msg)
        AppDebugLog.log("GESTURE", msg)
    }

    /** 触发按压进动画（快速凹陷）。 */
    fun requestPress() {
        pressDownNonce++
        animationActive = true
    }

    /** 触发回弹动画（按压归零 + 位置弹簧）。 */
    fun requestSettle(velocity: Offset) {
        settleVelocityPx = velocity
        settleNonce++
        animationActive = true
    }

    fun markInteraction() {
        animationActive = true
    }

    /** 所有动画结束后停止 time 推进。 */
    fun markIdleIfDone() {
        if (!isDragging && !isSettling) {
            animationActive = false
            timeSeconds = 0f
        }
    }
}

// ---- 拖动窗口解析（纯函数：无副作用、可独立验证；策略集中在这里）----------------------

/**
 * 单轴"可动窗口"的最小跨度（px）。
 * 退化窗口撑不到这个量级，观感就是"这一轴完全不动"（用户反复反馈的单轴锁死）。
 * 360px ≈ 竖屏 1/3 宽 ≈ 一次拖动的典型行程，保证撑开后手指一划就能看见位移。
 */
private const val MIN_LIVE_SPAN_PX = 360f

/** 兜底窗口最小半宽（px）：卡片尺寸还不可用（首帧）时用它托底。 */
private const val MIN_FALLBACK_HALF_PX = 180f

/** 单轴窗口跨度超过它即判定为"调用方还没测量"的哨兵（Screen 侧给的是 ±10000）。 */
private const val SENTINEL_SPAN_PX = 6000f

/**
 * 解析单轴可动窗口：输入调用方给的 (a,b) = (left/right 或 top/bottom)，
 * 结果写进 [out]（out[0]=lo、out[1]=hi；复用数组，拖动热路径不新增分配）。
 *
 * 保证：结果有限、lo <= hi、跨度 >= [MIN_LIVE_SPAN_PX]，且任何输入都不抛异常。
 * 返回触发过的修复原因（null = 原始窗口可直接用），供一次性日志定位上游问题：
 *  - "degenerate" 退化/近退化（max==min 或跨度极小）→ 绕中心撑开；
 *  - "sentinel"   跨度 > [SENTINEL_SPAN_PX]（调用方还没测量）→ 收敛到卡片自身尺度的沙箱；
 *  - "nonfinite"  NaN / ±Inf → 同样兜底，绝不让 NaN 流过。
 * 兜底沙箱以"默认位"（offset 0）为心、半宽 = max(0.6 × 卡片该轴尺寸, [MIN_FALLBACK_HALF_PX])：
 * 卡片默认位置必然在屏内，故 |offset| <= 0.6×卡片尺寸 ⇒ 卡片始终与其默认位置大面积重叠，
 * 不可能被甩到屏幕外找不回来。
 */
internal fun resolveOffsetWindow(a: Float, b: Float, cardSpan: Float, out: FloatArray): String? {
    var lo = minOf(a, b)          // 反序（max<min）一律翻转——旧实现只在整条 Rect 反序时才归一化
    var hi = maxOf(a, b)
    var reason: String? = null
    val fallbackHalf = (if (cardSpan.isFinite()) cardSpan * 0.6f else 0f)
        .coerceAtLeast(MIN_FALLBACK_HALF_PX)
    if (!lo.isFinite() || !hi.isFinite()) {
        lo = -fallbackHalf; hi = fallbackHalf; reason = "nonfinite"
    } else if (hi - lo > SENTINEL_SPAN_PX) {
        lo = -fallbackHalf; hi = fallbackHalf; reason = "sentinel"
    }
    if (hi - lo < MIN_LIVE_SPAN_PX) {
        // 退化 / 超小窗口：绕【原中心】撑到最小可动跨度。
        // 旧实现（ⓐ-1）只把这里撑到 ±1px → 该轴依旧是"完全不动"（2px 行程肉眼不可见），
        // 再往前的版本更是把退化轴钉死在 left==right 上 —— 这就是历史回归点。
        val center = (lo + hi) * 0.5f
        lo = center - MIN_LIVE_SPAN_PX * 0.5f
        hi = center + MIN_LIVE_SPAN_PX * 0.5f
        reason = if (reason == null) "degenerate" else "$reason+degenerate"
    }
    out[0] = lo
    out[1] = hi
    return reason
}

/**
 * time uniform 驱动：仅在交互/回弹期间以帧时钟推进，静止后完全停止，
 * 不为保持 Shader 活跃而永久每帧重绘。
 */
@Composable
private fun GlassCardTimeDriver(state: GlassCardState, reduceMotion: Boolean) {
    LaunchedEffect(state, reduceMotion) {
        while (true) {
            // 等待动画激活（快照流，空闲时挂起，零开销）
            snapshotFlow { state.animationActive && !reduceMotion }.first { it }
            val startNanos = withFrameNanos { it }
            while (state.animationActive && !reduceMotion) {
                withFrameNanos { now -> state.timeSeconds = (now - startNanos) / 1_000_000_000f }
                state.markIdleIfDone()
            }
            state.timeSeconds = 0f
            state.animationActive = false
        }
    }
}

/**
 * 按压进 / 回弹动画驱动器：由事件计数触发，在普通协程作用域执行。
 */
@Composable
private fun GlassCardAnimations(
    state: GlassCardState,
    parameters: () -> GlassParameters,
    reduceMotion: Boolean
) {
    val currentParameters by rememberUpdatedState(parameters)

    // 【并发修复·单一持有者】pressProgress 只由这一个 Animatable 写入：
    // Animatable.animateTo 内部用 MutatorMutex 串行化 —— 后发起的那次动画会打断/取消前一次，
    // 所以「按压进（0→1）」与「回弹归零（→0）」不可能同帧并发写同一个状态。
    // 修复前：两个 LaunchedEffect 各自 animate 到同一个 state.pressProgress，
    // 松手那几十毫秒里两边每帧交替覆盖 → 快速点按时进度在 1 与 0 之间来回跳（"反弹/跳动" ✗）。
    val pressAnim = remember(state) { Animatable(0f) }

    // 按压进：快速到位（100ms，避免按下瞬间放大动画造成滞后感）
    LaunchedEffect(state.pressDownNonce) {
        if (state.pressDownNonce == 0) return@LaunchedEffect
        // 从 Animatable 的【当前值】续跑：快速连点不会先跳回 0 再冲到 1
        pressAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 100)
        ) { state.pressProgress = value }
    }

    // 回弹：按压归零 + 位置带初速度弹簧
    LaunchedEffect(state.settleNonce) {
        if (state.settleNonce == 0) return@LaunchedEffect
        // 本次回弹所属的拖动世代：手指中途按下（dragEpoch 变）后，本协程立即停写位置
        val epoch = state.dragEpoch
        state.isSettling = true
        try {
            val p = currentParameters()
            val damping = if (reduceMotion) max(p.springDampingRatio, 0.9f) else p.springDampingRatio
            val spec = spring<Float>(
                dampingRatio = damping,
                stiffness = p.springStiffness
            )
            // 按压进度回零（刚度提高：松手后按压放大快速消失，位置回弹跟手）。
            // 这一次 animateTo 会取消仍在跑的「按压进」动画 → 回弹期间 pressProgress
            // 只有一个写入者，松手瞬间不再出现"1 与 0 互相覆盖"的跳动。
            pressAnim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = damping,
                    stiffness = p.springStiffness * 1.5f
                )
            ) { state.pressProgress = value }
            // 位置回弹：目标=当前位置（拖动时已限幅），带初速度做弹性衰减
            //
            // 【单轴锁死·根因修复】回弹动画与拖动【不能同时写 offset】：
            //   本协程每帧写一次 offsetX/offsetY，而帧回调排在触摸事件之后、绘制之前 →
            //   用户若在上一段回弹还没结束时就再次按下拖动（连续拖动是常态），动画的写入会
            //   盖掉手指位移、并把下一帧拖动累积的基准重置回"松手时"的位置 → 该轴完全不动；
            //   两轴是【串行】弹簧（先 X 后 Y），任一时刻恰好一轴被盖住
            //   → 观感正是"有时只动 X、有时只动 Y"（用户反复反馈的那一类）。
            //   实测（d=0.78 k=380，与预设一致）：单轴写窗口 96~656ms，两轴串起来 ≈1.5s，
            //   正好覆盖一次连续拖动的时长 —— 所以这个缺陷能反复复现。
            // 规则：手指按下（dragEpoch 变 / isDragging）即由拖动手势独占位置写入权，
            //   回弹动画立即停写（动画自身跑完自然结束，不再碰 offset）。
            val startX = state.offsetX
            val startY = state.offsetY
            val velocity = state.settleVelocityPx
            if (!state.isDragging) {
                animate(
                    initialValue = startX,
                    targetValue = startX,
                    initialVelocity = velocity.x,
                    animationSpec = spec
                ) { value, _ ->
                    if (state.dragEpoch == epoch && !state.isDragging) state.offsetX = value
                }
            }
            if (!state.isDragging) {
                animate(
                    initialValue = startY,
                    targetValue = startY,
                    initialVelocity = velocity.y,
                    animationSpec = spec
                ) { value, _ ->
                    if (state.dragEpoch == epoch && !state.isDragging) state.offsetY = value
                }
            }
        } finally {
            state.isSettling = false
            state.markIdleIfDone()
        }
    }
}

// ==================== 【按压形变·按形状区分 2026-09-14】====================
// 用户选定：正方形系形状（圆角矩形/圆/三角/六边/超椭圆）按下【等比不变形】；
// 细长形状（胶囊/椭圆）保留【克制的各向异性】（横胀纵缩，幅度逐轴 ≤ 旧版）。
// 总开关：DebugSwitches.cardPressProportional（false = 全部回到旧行为，一行回退 ✓）。
//
// 【杠杆在哪（先读，否则会白改 ✗）】卡片按下时"形状长多大"由两处共同决定：
//   ① 本文件：玻璃层比卡片大 (pressOvX, pressOvY) 每侧 —— 同时就是"按下能外扩多少"；
//   ② backdrop/BackdropAdapter.kt：SHAPE_INFLATE = (-8dp, -19dp)·(1-press) —— 固定常数。
//   静止态：② 的负膨胀恰好抵消 ① 的余量 ⇒ 可见形状严格 = 卡片矩形（硬门逐像素保证 ✓）；
//   按下态：可见外扩量 = ① 的 (pressOvX, pressOvY)（逐轴、原实现与形状无关 ✗ ⇒
//   "按下会不会变形"完全由 ① 决定）。⇒ 要在 ui/ 内按形状区分，唯一杠杆就是 ①；
//   而 x(0)=8dp、y(0)=19dp 是与 ② 的常数逐轴相等的唯一静止态安全值
//   ⇒ press=0 时这两个数不许动 ✗（动了就是"不按压也变了"的回归）。
//   ※ 另一个可选位置是 ②（把常数改成随形状变化），那属 backdrop/** 的改动，不在本文件。
//
// 【按下时每侧外扩（可见量）】growth(p) = pressOv(p) − 常数·(1−p)
//   ⇒ 取 pressOv(p) = 常数 + (目标 − 常数)·p 即得 growth(p) = 目标·p（线性、两端精确 ✓）：
//     正方形系：目标 = 2.5% 该轴卡片尺寸（x 取宽、y 取高）⇒ 等比放大（整体 +5%），
//               方形/圆形/六边形/三角形/超椭圆全部【等比不变形】：两轴同比例 ⇒
//               宽高比逐像素不变（含圆角矩形：它是非正方卡，按轴等比才不会走形 ✓）；
//               横向与旧版 8dp 在默认卡（644px）上等价（2.5%×644 = 16.1px ≈ 16px ✓）
//               且随卡片尺寸自适应（旧版在 glassSize 0.2 时外扩占比会翻倍 ✗，这里不会 ✓）
//     细长形状：目标 = (+1.25% 宽, −0.625% 高) ⇒ 横胀纵缩、相对幅度 2:1（≈ 旧稿 0.010/−0.005 ✓），
//               并再夹在旧版逐轴幅度以内（x ≤ 8dp、y ≤ 19dp）⇒ "幅度 ≤ 旧版" 对任意尺寸都成立 ✓
//     旧行为（开关关）：(8dp, 19dp) 恒定 ⇒ 与改动前逐像素一致 ✓
private const val PRESS_GROW_FRACTION = 0.025f        // 正方形系每侧 = 2.5% 该轴尺寸
private const val PRESS_ELONG_X_FRACTION = 0.0125f    // 细长形状：横胀 = 1.25% 宽（再夹 ≤ 8dp）
private const val PRESS_ELONG_Y_FRACTION = 0.00625f   // 细长形状：纵缩 = 0.625% 高（再夹 ≤ 19dp）
private const val PRESS_PAD_X_DP = 8f               // 与 ② 的 x 常数相等（press=0 安全值，不可改）
private const val PRESS_PAD_Y_LEGACY_DP = 19f       // 与 ② 的 y 常数相等（press=0 安全值，不可改）

/** 正方形系（等比、不变形）vs 细长形状（保留克制的各向异性）。 */
private val GlassShape.pressIsSquareFamily: Boolean
    get() = when (this) {
        GlassShape.ROUNDED_RECT, GlassShape.CIRCLE, GlassShape.TRIANGLE,
        GlassShape.HEXAGON, GlassShape.SUPERELLIPSE -> true
        GlassShape.CAPSULE, GlassShape.ELLIPSE -> false
    }

/** 玻璃层 x 向每侧余量（px）。press=0 恒等于 8dp（静止态安全值）✓ */
private fun pressPadXPx(
    shape: GlassShape, press: Float, proportional: Boolean, cardW: Float, density: Float
): Int {
    // 【必须陪读 revision】DebugSwitches 是 @Volatile，不产生快照订阅 ✗ ⇒
    // 少读这一行，adb setSwitches 翻开关就不会让布局/绘制失效（会拍到"混合态"，NEXT.md 已两次踩）
    DebugBridge.revision.intValue
    val base = PRESS_PAD_X_DP * density
    if (!proportional) return base.toInt()      // 旧行为
    val target =
        if (shape.pressIsSquareFamily) PRESS_GROW_FRACTION * cardW
        // 细长形状：横胀小一档，并夹在旧版 x 幅度（8dp）以内 ⇒ 逐轴幅度 ≤ 旧版 ✓
        else (PRESS_ELONG_X_FRACTION * cardW).coerceAtMost(base)
    return (base + (target - base) * press).toInt()
}

/** 玻璃层 y 向每侧余量（px）。press=0 恒等于 19dp（静止态安全值）✓ */
private fun pressPadYPx(
    shape: GlassShape, press: Float, proportional: Boolean, cardH: Float, density: Float
): Int {
    DebugBridge.revision.intValue
    val base = PRESS_PAD_Y_LEGACY_DP * density
    if (!proportional) return base.toInt()      // 旧行为
    val limit = base                            // 旧版 y 幅度（逐轴上限，保证 "幅度 ≤ 旧版" ✓）
    val target =
        if (shape.pressIsSquareFamily) (PRESS_GROW_FRACTION * cardH).coerceAtMost(limit)
        else (-PRESS_ELONG_Y_FRACTION * cardH).coerceAtLeast(-limit)
    return (base + (target - base) * press).toInt()
}

// ==================== 【P22·玻璃矩形对齐 2026-09-14】====================
// 真 bug（像素实测）：玻璃【可见矩形】= 卡片盒 − (8dp,19dp)（@320dpi = (16,38)px）——
//   玻璃元素整体比卡片盒左上偏 (16,38)px；而 dumpState 的 card rect 与布局盒都按"卡片盒"报
//   ⇒ 报出来的盒子与屏幕上的玻璃差 (16,38)px（P06 多卡重叠/精确对齐会立刻露馅 ✗）。
// 机制（NEXT.md 已写，勿重推）：玻璃层 = constraints + pressOv*2 且 place(−ovX,−ovY)，
//   而 Shader 的可见形状锚在【玻璃层自己的原点】⇒ 可见矩形 = 玻璃层原点 + 卡片尺寸 = 卡片盒 − (ovX,ovY)。
// 修法（ui/ 侧把坐标系对齐回来，最小侵入）：
//   ① 布局落点（ui/LiquidGlassScreen.kt 的 main/second/extraDefaultTopLeft）整体减 (8dp,19dp)
//      ⇒ 布局盒 / dumpState 报出的 card rect 搬到"玻璃可见矩形"上；
//   ② 本文件把玻璃层 place(restX−ovX, restY−ovY)（静止态 ov==rest ⇒ 顶左正好落在卡片盒顶左 ✓）
//      并且本盒的内容框整体 .offset(−restX)（① 的互补）⇒ 玻璃在屏幕上的位置【逐帧逐像素不变】✓
//      （press 态：place(rest−ov) 的屏幕位置 = 改前 place(−ov) + rest 的屏幕位置 − rest ⇒ 恒等 ✓）；
//   ③ 内容层补 (+restX,+restY)、CARD_ORIGIN 补 (+restX,+restY) ⇒ 内容框与光照角口径都不变 ✓。
// 回退：DebugSwitches.glassRectAlign=false（默认 true）⇒ restX/restY 恒 0 ⇒ 三处全部退回改动前 ✓。
// 【唯一真值】rest 值 = PRESS_PAD_X_DP / PRESS_PAD_Y_LEGACY_DP × density（= pressPad 的静止态基值，
//   与 backdrop/BackdropAdapter.kt 的 SHAPE_INFLATE·(1−press) 同源；不复制常数 ✗）。
// 【必须陪读 revision】与 pressPadXPx/Y 同理：@Volatile 开关不产生快照订阅 ✗，少读这一行
//   ⇒ `setSwitches glassRectAlign 0|1` 不会让布局/放置失效（会拍到"混合态" ✗，NEXT.md 已两次踩）。

/** 【P22】静止态玻璃层 x 向余量（px）：开关开 = 8dp×density；关 = 0（一行回退 ✓）。 */
internal fun glassRectRestInsetXPx(density: Float): Float {
    DebugBridge.revision.intValue
    return if (DebugSwitches.glassRectAlign) PRESS_PAD_X_DP * density else 0f
}

/** 【P22】静止态玻璃层 y 向余量（px）：开关开 = 19dp×density；关 = 0（一行回退 ✓）。 */
internal fun glassRectRestInsetYPx(density: Float): Float {
    DebugBridge.revision.intValue
    return if (DebugSwitches.glassRectAlign) PRESS_PAD_Y_LEGACY_DP * density else 0f
}

/**
 * 第二层：LiquidGlassCard —— 可拖动、可按压形变、弹簧回弹的玻璃卡片。
 *
 * 前景内容在 Shader 之后绘制、Alpha 恒为 1.0、保持清晰；
 * 内容层本身不做形变：按压/拖动的形变全部由玻璃本体承担（Shader + 轮廓膨胀）
 * （Shader 无法影响前景层，此为文档明确的近似手段）。
 */
@Composable
fun LiquidGlassCard(
    adapter: BackdropAdapter,
    state: GlassCardState,
    parameters: () -> GlassParameters,
    quality: () -> GlassQuality,
    defaultTopLeftPx: () -> Offset,
    offsetBounds: () -> Rect,
    adaptiveLegibility: Boolean,
    reduceMotion: Boolean,
    /** 拖动拉伸（玻璃本体，不再是内容形变）。 */
    stretchOnDrag: Boolean,
    /** HDR 高光增益（窗口处于 HDR 色彩模式时 > 1）。 */
    hdrBoost: () -> Float = { 1f },
    debugMode: () -> GlassDebugMode,
    shapeType: () -> GlassShape,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Float = 36f,
    /** 面板 morph 动画期间传 true：跳过玻璃渲染，避免同帧多次背景捕获。 */
    suppressGlass: Boolean = false,
    /** iOS 透镜剖面开关（移植自 QWEA0/Liquid-Glass-Android）。 */
    lensProfile: () -> Boolean = { true },
    /**
     * 【P07 双卡二次折射】采样源覆盖：
     * 上层卡传 `rememberCombinedBackdrop(背景捕获层, 下层卡的离屏输出层)` ⇒ 采到"已被下层玻璃
     * 折射过的画面"（真二次折射）；null（默认）= 库默认的背景捕获层 = 改动前行为。
     */
    backdrop: com.kyant.backdrop.Backdrop? = null,
    /** 【P07】下层卡：把本卡渲染结果录进这个离屏层，供上层卡采样；null = 不录（零额外成本）。 */
    exportedBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
    /** 【P07】effects 内的额外快照订阅点（上层卡订阅下层卡的位置，防采到旧内容）。 */
    extraObservedReads: () -> Unit = {},
    /**
     * 【P32·跨卡失效直连】对方卡的【位置读取器】（null = 不接，行为同改前）。
     *
     * 为什么要有它：`extraObservedReads` 走的是库内 `observeReads` 通道，实测在拖动中段
     * 【不投递】—— 两块玻璃互相重叠拖动时，静止那块连续 ~1.3s 零失效（改前基线
     * 见 /tmp/p32_M2.log 的逐段表），于是它的玻璃层与离屏录制层整段不重录，
     * 采样内容（对方玻璃的折射）冻结 = 用户报的"静止卡折射卡住" ✗。
     * 这里改用【绘制期读】：与本文件最外层 `graphicsLayer { translationX = state.offsetX }`
     * 同一类失效路径（工程内已被反复验证会投递 ✓）⇒ 对方一动，本卡当帧重绘 +
     * 重录玻璃层/离屏层 ✓。开关 = DebugSwitches.dualPairObserve（默认开；关 = 不接）。
     */
    crossObserveOther: (() -> Unit)? = null,
    /**
     * 【P37 取证】这块卡的标签（"0".."3"）：只被 dualCardTrace 的逐帧探针日志读取（`card=` 字段），
     * 让 N 块卡的 eff 计数能直接按卡号统计。默认 ""（未接线时日志里 card= 为空 ⇒ 与旧格式兼容）。
     */
    traceCard: String = "",
    /**
     * 【P12·邻近流体融合】融合 uniform 提供者（绘制期读取；null（默认）= 不融合
     * ⇒ uniform 全 0、逐像素等于改动前）。
     */
    fusionUniforms: (() -> com.example.liquidglass.glass.ProximityFusion.CardUniforms?)? = null,
    /**
     * 【P06 多卡·点击置顶】"点按"（按下 → 抬起，期间累计位移 ≤ touchSlop 且时长 ≤ 350ms）时回调。
     * 默认 null = 不接（逐像素/逐事件等于改动前 ✓）。判定与拖动/回弹共用同一段手势循环，
     * 不新增第二个 pointerInput ⇒ 不会与拖动抢事件。
     */
    onTap: (() -> Unit)? = null,
    /**
     * 【双指捏合改尺寸】捏合期间每帧回调：(上一帧两指跨度 px, 本帧跨度 px)。
     * 由调用方接成"第 i 块卡的尺寸"（LiquidGlassScreen：`applyPinchResizeForCard(uiState, i, prev, now)`，
     * 与面板「玻璃尺寸」滑块同一个 [GlassUiState.setSizeOfCard] 函数 ⇒ 双向同步 ✓）；
     * null（默认）= 不接 ⇒ 捏合分支不存在，逐字等于改动前 ✓。
     * 总开关 = [DebugSwitches.cardPinchResize]（默认开；关掉 = 完全无 pinch 行为 ✓）。
     * 注意：本 lambda 会在 pointerInput 里被长期持有 ⇒ 实现必须"现读状态"（捕获稳定对象 + 现算），
     * 不要按值捕获会变的量（与 onTap 同一条纪律）。
     */
    onPinchSize: ((prevSpanPx: Float, nowSpanPx: Float) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val currentParameters by rememberUpdatedState(parameters)
    val currentBounds by rememberUpdatedState(offsetBounds)

    var cardSizePx by remember { mutableStateOf(IntSize.Zero) }
    // 供 shapeProvider 使用（不在 composable 上下文里读 LocalDensity）
    val densityScale = androidx.compose.ui.platform.LocalDensity.current.density

    GlassCardTimeDriver(state, reduceMotion)
    GlassCardAnimations(state, currentParameters, currentReduceMotion)

    Box(
        modifier
            // 位置平移必须留在【最外层】：Backdrop 采样坐标按该节点的窗口坐标映射，
            // 把平移放到内层会让库读到错位的坐标（拖不动 + 折射采样跑偏）。
            .graphicsLayer {
                // 【铁律】平移必须且只能留在最外层：Backdrop 采样按本节点窗口坐标映射，
                // 一旦在这层叠加旋转/其它变换，库会读到错位坐标 → 拖不动 + 折射跑偏 ✗
                // （实测：加了 rotationZ 后拖动位移 dx=dy=0、玻璃内背景跟着旋转 ✗）
                translationX = state.offsetX
                translationY = state.offsetY
            }
            // 【P32·跨卡失效直连】绘制期读一次【对方卡的位置】⇒ 对方一动本卡当帧重绘
            //（重录玻璃层 + exportedBackdrop 离屏层）。关掉开关 = 不加这个 modifier（改前行为）✓
            .then(
                crossObserveOther?.let { observe ->
                    if (DebugSwitches.dualPairObserve) {
                        Modifier.drawWithContent { observe(); drawContent() }
                    } else {
                        Modifier
                    }
                } ?: Modifier
            )
            // 【P22·玻璃矩形对齐】布局落点整体左移 restX（与玻璃层的 place(+restX) 互补 ⇒ 玻璃屏幕位置不变 ✓）：
            //   开关开 ⇒ 本盒的"内容框"= 玻璃【可见矩形】⇒ 布局/手势区/dumpState 的 card rect 与屏幕玻璃重合 ✓；
            //   开关关 ⇒ 0 ⇒ 逐像素等于改动前 ✓。读一次 revision ⇒ `setSwitches glassRectAlign 0|1` 当场重排 ✓
            .offset {
                @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                IntOffset(-glassRectRestInsetXPx(densityScale).toInt(), 0)
            }
            .onSizeChanged {
                cardSizePx = it
                // 同步给 state：拖动窗口在"调用方 bounds 不可用"时要用卡片尺寸做兜底沙箱
                state.cardSizePx = it
            }
            .cardGestures(state, currentBounds, onTap, onPinchSize)
    ) {
        // ---- 玻璃层（独立一层）----
        // 位置平移与玻璃光学同层，保证 Backdrop 采样坐标随卡片移动。
        // 注意：这里【不做图层缩放】——按压只通过 Shader 的 shapeInflate 膨胀轮廓几何，
        // 采样仍按屏幕坐标 1:1，因此玻璃内部看到的画面不会被放大
        //（用户要求：手指放上去只改变玻璃的形状，不直接影响玻璃里的内容）。
        // 玻璃层比卡片【大一圈】：图的 RenderNode 内容不能超出自身边界 ✗，
        // 因此"按下变大"必须让层本身就有余量；静止时用 SDF 负膨胀把可见形状收回卡片尺寸。
        // 【按压形变·按形状区分】每侧余量 (x,y) 随形状与按压进度变化（推导见文件上方
        // PRESS_GROW_FRACTION 一节）：press=0 时恒为 (8dp,19dp) = 静止态安全值 ✓；
        // press=1 时 = 该形状的目标外扩量（正方形系等比 / 细长形状横胀纵缩）。
        // 【与"减少动态效果"保持一致】adapter 在 reduceMotion 时把 shapeInflate 恒置 0（= 不做按压形变 ✓）
        // ⇒ 本层余量必须同步按 press=0 处理，否则只有本层在长、SDF 不跟随 ⇒ 开启减少动态效果时
        //   形状仍会随按下变大 ✗（与既有的"开关要有可见差异"语义冲突）
        val pressForPad = { if (currentReduceMotion) 0f else state.pressProgress }
        val pressOvX = {
            pressPadXPx(
                shapeType(),
                pressForPad(),
                DebugSwitches.cardPressProportional,
                cardSizePx.width.toFloat(),
                densityScale
            )
        }
        val pressOvY = {
            pressPadYPx(
                shapeType(),
                pressForPad(),
                DebugSwitches.cardPressProportional,
                cardSizePx.height.toFloat(),
                densityScale
            )
        }
        Box(
            Modifier
                // 关键：子节点（玻璃层）必须【按放大后的约束】测量，否则它的 RenderNode
                // 仍是卡片尺寸 → 膨胀出去的绘制照样被裁 ✗（实测外环带变化全为 0 ✗）。
                .layout { measurable, constraints ->
                    // 【布局期读取】pressProgress（快照状态、逐帧变化 = 按下时形状生长的唯一来源）
                    // 与 DebugSwitches（@Volatile，靠 helper 内陪读的 DebugBridge.revision 订阅）
                    // 都在这里生效 ⇒ 按形状分支会随形状切换、逐帧重算 ✓
                    val ovX = pressOvX()
                    val ovY = pressOvY()
                    val w = constraints.maxWidth + ovX * 2
                    val h = constraints.maxHeight + ovY * 2
                    val placeable = measurable.measure(
                        androidx.compose.ui.unit.Constraints(
                            minWidth = w, maxWidth = w, minHeight = h, maxHeight = h
                        )
                    )
                    // 【P22·玻璃矩形对齐】玻璃层顶左相对卡片盒的静止态余量 restX/restY
                    //（开关关 ⇒ 0 ⇒ 下面一行逐字符等于改动前 ✓）：
                    //   开 ⇒ place(restX-ovX, restY-ovY) —— ov 是"按下要多长"的余量，
                    //   静止态 ov == rest ⇒ 玻璃层顶左【正好落在卡片盒顶左】（偏移归零 ✓）；
                    //   按下态 = place(负数) 与改前同值（屏幕位置逐帧不变 ✓，见下方外层 .offset 的互补）。
                    val restX = glassRectRestInsetXPx(densityScale).toInt()
                    val restY = glassRectRestInsetYPx(densityScale).toInt()
                    layout(w, h) { placeable.place(restX - ovX, restY - ovY) }
                }
                // Backdrop 光学层
                .then(
                    if (suppressGlass) Modifier
                    else Modifier.glass(
                    adapter = adapter,
                    // 裁剪形状必须与 SDF 可见边界同源：按压时 SDF 轮廓外扩，
                    // 若这里仍用静止态形状 → 外扩出去的高光被硬裁（用户反馈 ✗）。
                    // 【抗锯齿修复】不再用路径裁剪切形状 —— Android 的 path clip 不做抗锯齿 ✗，
                    // 1px 方块台阶就出在这里（换成圆的判定实验已证实：边界随圆走 ✓）。
                    // 形状完全交给 Shader 的 SDF（下方覆盖率会乘进 alpha 做渐隐 = 真抗锯齿 ✓）。
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    cornerRadiusDp = cornerRadiusDp,
                    parameters = parameters,
                    quality = quality,
                    // 【P22·玻璃矩形对齐】CARD_ORIGIN 只喂 Shader 的光照角（lightAngle = f(cardOrigin)）——
                    // 落点整体左移 (restX,restY) 后必须补回，否则光照角动 0.0014 rad（高光位相会动 ✗）
                    cardOriginPx = {
                        // 【P32 取证】本行所在 lambda 由 effects 执行期调用 ⇒ 每次重跑打一行：
                        // 读的都是本 lambda 原本就读的量（defaultTopLeftPx / 卡自身 offset），
                        // 不新增任何快照订阅、不改变失效行为 ✓（开关默认关 = 零日志）
                        // 【P37】补 card= 标签 ⇒ N 块卡按卡号统计（多卡场景直接看哪块停了）
                        if (DebugSwitches.dualCardTrace) {
                            android.util.Log.i(
                                "P32",
                                "eff id=" + System.identityHashCode(state) +
                                    " card=" + traceCard +
                                    " x=" + state.offsetX + " y=" + state.offsetY +
                                    " t=" + android.os.SystemClock.elapsedRealtime()
                            )
                        }
                        defaultTopLeftPx() + Offset(
                            glassRectRestInsetXPx(densityScale), glassRectRestInsetYPx(densityScale)
                        ) + Offset(state.offsetX, state.offsetY)
                    },
                    pressProgress = { state.pressProgress },
                    // 【坐标空间】下面这两个 uniform 会被 Shader 直接和它自己的局部坐标比对
                    //（GlassShaders：float2 local = coord + offset，offset = -layerPadding
                    //  → 消费的是【玻璃节点】局部坐标）。而本玻璃节点比卡片大
                    // (8dp, 19dp) 并且 place(-pressOvX, -pressOvY)，
                    // 于是：节点局部坐标 = 卡片局部坐标 + (pressOvX, pressOvY)。
                    // state.pressPositionPx 与 cardLabelRegion(...) 给的都是【卡片局部坐标】，
                    // 因此在这里必须补上这一层偏移，否则按压凸起与文字区暗化会恒定
                    // 错位 (pressOvX, pressOvY)（左下方向偏 8dp/19dp）。
                    pressPositionPx = {
                        state.pressPositionPx + Offset(pressOvX().toFloat(), pressOvY().toFloat())
                    },
                    dragVelocityPx = { state.dragVelocityPx },
                    stretchDirection = { state.stretchDirection },
                    timeSeconds = { state.timeSeconds },
                    adaptiveLegibility = { adaptiveLegibility },
                    // 同上：labelRegion 也是卡片局部 px，同样补 (pressOvX, pressOvY)
                    labelRegion = {
                        cardLabelRegion(cardSizePx)?.translate(
                            pressOvX().toFloat(),
                            pressOvY().toFloat()
                        )
                    },
                    debugMode = debugMode,
                    shapeType = shapeType,
                    lensProfile = lensProfile,
                    // 按压时可见边界与 SDF 同步【向外膨胀】（x/y 逐轴一致，6:2）
                    // 用户要求按压由小变大：传入负 inset = 外扩（类内部 left=inset、right=w-inset）。
                    shapeProvider = {
                        val pressNow = state.pressProgress
                        if (pressNow > 0.01f && shapeType() == GlassShape.ROUNDED_RECT) {
                            PressInsetShape(
                                insetX = -pressNow * 6f * densityScale,
                                insetY = -pressNow * 2f * densityScale,
                                radiusPx = cornerRadiusDp * densityScale
                            )
                        } else {
                            shapeType().toComposeShape()
                        }
                    },
                    reduceMotion = { reduceMotion },
                    stretchEnabled = { stretchOnDrag },
                    hdrBoost = hdrBoost,
                    // 【P07】双卡二次折射：采样源覆盖（上层卡）/ 离屏输出（下层卡）+ 额外订阅点
                    backdrop = backdrop,
                    exportedBackdrop = exportedBackdrop,
                    extraObservedReads = extraObservedReads,
                    // 【P12·邻近流体融合】融合 uniform（默认 null ⇒ 全 0 ⇒ 零回归）
                    fusionUniforms = fusionUniforms
                )
                )
        )

        // ---- 内容层：只跟随位置平移，不做任何按压/拖动形变 ----
        // 这一层【负责给外层撑出尺寸】：不能用 matchParentSize（两层都 matchParentSize
        // 会让外层高度塌成 0，手势区消失、按压根不响应——真实 bug，已修）。
        // 注意：只能 fillMaxWidth，不能用 fillMaxSize——fillMaxSize 会把卡片撑到父约束
        // 最大高度（整屏），导致拖动边界范围倒挂
        val shapeKind = shapeType()
        Box(
            Modifier
                // 【P22·玻璃矩形对齐】内容框补回 (restX,restY) ⇒ 内容层与改前【同一屏幕位置】✓
                //（当前主/副卡内容 = 不可见占位 Spacer，仍补：口径一致，将来放真内容不会漂 ✓）
                .offset {
                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                    IntOffset(
                        glassRectRestInsetXPx(densityScale).toInt(),
                        glassRectRestInsetYPx(densityScale).toInt()
                    )
                }
                .fillMaxWidth()
                .padding(22.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shapeKind.contentWidthFraction)
                    .clip(shapeKind.toComposeShape()),
                // 内容随形状自适应：三角形底部宽 → 内容偏下，圆形/椭圆 → 居中收窄
                contentAlignment = Alignment { container, content, _ ->
                    IntOffset(
                        0,
                        ((container.height - content.height) * (shapeKind.contentVerticalBias - 0.5f) * 2f).toInt()
                    )
                }
            ) {
                content()
            }
        }
    }
}

/** 前景文字标签区域（卡片局部 px），供 Shader 内做局部暗化/提亮。 */
private fun cardLabelRegion(cardSizePx: IntSize): Rect? {
    if (cardSizePx.width <= 0 || cardSizePx.height <= 0) return null
    return Rect(18f, 14f, (cardSizePx.width - 18f).coerceAtLeast(1f), 150f)
}

/**
 * 点按判定的最长时长（ms）：超过即视为长按（不触发 [cardGestures] 的 onTap）。
 */
private const val CARD_TAP_TIMEOUT_MS = 350L

/**
 * 按压 / 拖动手势（受限协程作用域：只做非 suspend 状态更新，
 * 动画全部通过事件交给 GlassCardAnimations）。
 *
 * - 按下 → requestPress()（按压进动画：快速凹陷）；
 * - 拖动 → 位置跟随手指，速度记录进 VelocityTracker；
 * - 松开 → requestSettle()（按压回零 + 位置弹簧回弹）；
 * - 【P06 点击置顶】按下→抬起期间累计位移 ≤ touchSlop 且时长 ≤ [CARD_TAP_TIMEOUT_MS] ⇒ 调 onTap()
 *   （判定与拖动共用同一段循环：有位移就整段交给拖动，不会被误判成点按 ✓）。
 * - 【双指捏合改尺寸】本事件里出现 ≥2 根按下的指针 ⇒ 本段手势切成"捏合"：
 *     ① 位置停止跟随（不再拖动，卡片停在原地）；② 每帧回调 [onPinchSize]
 *     （= 改【该卡】尺寸，见 ui/CardPinchResize.kt —— 与面板滑块同一个 setSizeOfCard 函数）；
 *     ③ 整段不再触发 onTap（捏合不是点按）；④ 两指抬到只剩一根即结束本段手势
 *     （不残留"捏合后单指继续拖"这种跳变 ✗）；⑤ 收尾用零速度（捏合期间位置不跟手，
 *       留着上一段拖动的速度会让卡片莫名弹一下 ✗）。
 *   开关关（[DebugSwitches.cardPinchResize]=false）或未接线（onPinchSize=null）时本分支【整体不存在】
 *   ⇒ 逐字等于改动前（多指期间沿用旧行为：循环只跟踪第一根手指）✓。
 *   没有新增第二个 pointerInput/手势检测器 ⇒ 不与既有的拖动/点按抢事件 ✓。
 */
private fun Modifier.cardGestures(
    state: GlassCardState,
    bounds: () -> Rect,
    onTap: (() -> Unit)? = null,
    onPinchSize: ((prevSpanPx: Float, nowSpanPx: Float) -> Unit)? = null
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.markInteraction()
        state.setPressPosition(down.position)
        state.requestPress()

        val tracker = VelocityTracker()
        // 【P06 点击置顶】点按判定：触摸容差（沿用系统手势阈值）+ 累计位移
        val tapSlop = viewConfiguration.touchSlop
        var tapTravel = 0f
        state.updateDragging(true)
        // 【双指捏合】上一帧两指跨度（px）与"本段手势是否捏合过"
        var pinchPrevSpan = 0f
        var pinchUsed = false
        try {
            // 手写拖动循环：不依赖 drag() 的重载签名，逐事件累积位移
            var event = awaitPointerEvent()
            while (true) {
                // 【双指捏合】本事件里有 ≥2 根按下的指针 ⇒ 归捏合（跨度 > 0）。
                // 开关关 / 未接线时该判断恒不成立 ⇒ 与改动前逐字一致（多指期间只跟踪 down.id）✓
                if (onPinchSize != null && DebugSwitches.cardPinchResize) {
                    val span = pinchSpanPx(event)
                    if (span > 0f) {
                        if (!pinchUsed) {
                            pinchUsed = true
                            // 首帧：跨度比 = 1（尺寸不动），只把【该卡】设为"正在调整"的那块
                            // （= 用户"手指在哪块改哪块"的既有语义；选中描边/面板标题一起切 ✓）
                            onPinchSize(span, span)
                        } else {
                            // 逐帧增量：跨度比 → 尺寸（换算与夹取都在 ui/CardPinchResize.kt，滑块同一函数）
                            onPinchSize(pinchPrevSpan, span)
                        }
                        // 跨度每次都记录（含过小值）：过小值时写入被跳过，但比值基准不许跳，
                        // 否则"两指几乎重合后再拉开"会在恢复的第一帧产生跳变 ✗
                        pinchPrevSpan = span
                        event.changes.forEach { if (it.pressed) it.consume() }
                        if (!event.changes.any { it.pressed }) break
                        event = awaitPointerEvent()
                        continue
                    }
                }
                // 捏合之后掉回单指（或跨度不可用）⇒ 本段手势立即收尾：不再拖动、不判点按
                if (pinchUsed) break
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change != null) {
                    // Compose 1.12：positionChange 为 Boolean 属性，位移需自行相减
                    val dragAmount = change.position - change.previousPosition
                    if (dragAmount != Offset.Zero) {
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        state.setPressPosition(change.position)
                        // 【P06】累计位移（点按判定；拖动路径不受影响 —— 它只累加、不做任何决策）
                        tapTravel += dragAmount.getDistance()
                        val b = bounds()
                        state.updatePosition(
                            state.offsetX + dragAmount.x,
                            state.offsetY + dragAmount.y,
                            b
                        )
                    }
                }
                if (!event.changes.any { it.id == down.id && it.pressed }) break
                event = awaitPointerEvent()
            }
        } finally {
            // 无论正常抬起还是手势取消，都触发回弹
            val velocity = tracker.calculateVelocity()
            state.updateDragging(false)
            // 【双指捏合】捏合段收尾用【零速度】：捏合期间位置没跟手，测速器里的速度属于上一段拖动，
            // 留着会让卡片在捏合结束那一刻莫名弹一下 ✗（未捏合 = 原样使用拖动速度 ✓，逐字不变）
            state.setVelocity(if (pinchUsed) Offset.Zero else Offset(velocity.x, velocity.y))
            // 【审查修复·手感】回弹原来用 VelocityTracker 原始速度（限幅 2400 只写进了 shader 用的 dragVelocityPx ✗）
            //   → 快速甩动过冲可达 200~400px，真的把卡片推出边界/屏幕外再弹回 ✓（用户反馈的"突然跳回"高度吻合 ✓）
            val vClamped = Offset(
                velocity.x.coerceIn(-2400f, 2400f),
                velocity.y.coerceIn(-2400f, 2400f)
            )
            state.requestSettle(if (pinchUsed) Offset.Zero else vClamped)
            // 【P06 点击置顶】点按判定放在 finally 之后执行（见下），保证"手势已完全收尾"再改 z 序
        }
        // 【P06 点击置顶】"点一下"= 位移没超过触摸容差 且 时长不超过阈值 ⇒ 把这块卡置顶。
        // 放在手势循环之外调用：此刻拖动已结束、回弹已启动，改 z 序不会打断任何位置写入者 ✓。
        // 【双指捏合】pinchUsed 时一律不判点按（捏合不是点按 ✗）。
        if (!pinchUsed && onTap != null && tapTravel <= tapSlop &&
            android.os.SystemClock.uptimeMillis() - down.uptimeMillis <= CARD_TAP_TIMEOUT_MS
        ) {
            onTap()
        }
    }
}

/**
 * 【双指捏合】本事件里【前两根按下指针】的距离（px）；不足两根返回 0f（= 不是捏合帧）。
 * 只读位置、不写任何状态（纯函数，便于核对）；3 根及以上时按事件里的前两根算。
 */
private fun pinchSpanPx(event: PointerEvent): Float {
    var first: Offset? = null
    for (c in event.changes) {
        if (!c.pressed) continue
        val f = first
        if (f == null) {
            first = c.position
            continue
        }
        return (c.position - f).getDistance()
    }
    return 0f
}

/**
 * 前景内容形变（Compose 层近似）：
 * 以触点为中心做极轻微缩放/旋转，拖速带来轻微拉伸；
 * 默认关闭，开启时保持可读性。
 * 说明：AGSL Shader 只作用于背景采样层，无法影响前景内容；
 * 该形变是绘制层变换近似，README 中有明确说明。
 */
private fun Modifier.foregroundDistortion(
    state: GlassCardState,
    enabled: Boolean,
    cardSizePx: IntSize,
    /** 当前卡片形状：决定按压是【等比】还是【保留克制的各向异性】（见 PRESS_GROW_FRACTION 一节）。 */
    shape: GlassShape = GlassShape.ROUNDED_RECT
): Modifier = this.then(
    if (enabled) {
        Modifier.graphicsLayer {
            val press = state.pressProgress
            val velocity = state.dragVelocityPx
            val stretch = (velocity.getDistance() / 2400f).coerceIn(0f, 1f)
            val widthPx = cardSizePx.width.toFloat().coerceAtLeast(1f)
            val heightPx = cardSizePx.height.toFloat().coerceAtLeast(1f)
            transformOrigin = TransformOrigin(
                (state.pressPositionPx.x / widthPx).coerceIn(0f, 1f),
                (state.pressPositionPx.y / heightPx).coerceIn(0f, 1f)
            )
            // 【按压形变·按形状区分】正方形系 = 等比（x/y 同一倍率 ⇒ 圆/六边/三角/超椭圆/圆角矩形不变形 ✓）；
            // 细长形状（胶囊/椭圆）= 保留克制的各向异性（横胀纵缩，幅度 ≤ 旧版 0.012/0.006）✓。
            // 开关关闭 = 旧行为（scaleX 1+0.012p+0.006s / scaleY 1-0.006p，所有形状一个样）✓
            // ⚠ 本函数当前【未被调用】（全工程 grep 只有这一处定义；卡片按下真正生效的形变是
            //   【玻璃层每侧余量】= pressPadXPx/pressPadYPx，见文件上方 PRESS_GROW_FRACTION 一节）。
            //   这里同步成同一套形状语义，是为了它一旦被接线时不会又退回"捏扁"✗。
            scaleX = when {
                !DebugSwitches.cardPressProportional -> 1f + 0.012f * press + 0.006f * stretch
                shape.pressIsSquareFamily -> 1f + 0.006f * press + 0.003f * stretch
                else -> 1f + 0.010f * press + 0.005f * stretch
            }
            scaleY = when {
                !DebugSwitches.cardPressProportional -> 1f - 0.006f * press
                shape.pressIsSquareFamily -> 1f + 0.006f * press
                else -> 1f - 0.005f * press
            }
            rotationZ = 0.25f * press * (if (velocity.x >= 0f) 1f else -1f) + 0.12f * stretch
            translationX = -velocity.x.coerceIn(-20f, 20f) * 0.02f
        }
    } else {
        Modifier
    }
)

/**
 * 主卡片默认内容：玻璃纯净模式 —— 不放置任何文字/图片/按钮元素，
 * 仅保留固定高度维持卡片尺寸（光学效果由 Shader 独立呈现）。
 */
@Composable
fun MainCardContent(shapeType: GlassShape = GlassShape.ROUNDED_RECT) {
    Spacer(Modifier.height(340.dp))
}

/** 第二张卡片内容（双卡演示）：玻璃纯净模式，无元素。 */
@Composable
fun SecondCardContent() {
    Spacer(Modifier.height(200.dp))
}
