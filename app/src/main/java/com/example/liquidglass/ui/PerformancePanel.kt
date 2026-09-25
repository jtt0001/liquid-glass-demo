package com.example.liquidglass.ui

import android.content.Context
import android.os.Debug
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.debug.DebugSwitches
import com.example.liquidglass.performance.PerformanceMonitor
import com.example.liquidglass.performance.PerformanceSnapshot
import com.example.liquidglass.ui.components.liquid.LiquidHandFeel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// 【P47 · 全屏可拖 / 左右边缘自动吸附收起 / 可从边缘拉出 的性能看板】
//
// 用户点名需求（原话）：
//   「把右上角性能监控看板改造成【全屏可拖 + 左右边缘自动吸附成简洁形态 + 可从边缘拉出】的悬浮看板」
//   「参考 Scene 性能看板悬浮在屏幕最顶端：优先把看板做成分屏最顶端的一条细浮条」
//
// 口径与出处（每个数字都能在仓库里核对）：
//   · 跟手 1:1：拖动期间每个指针事件直接 snapTo 位置（= 本工程卡片 LiquidGlassCard.cardGestures
//     写 offsetX/offsetY 的语义、= 上游 DampedDragAnimation 在 handFeel.directDragFollow=true 时
//     的 updateValue() 语义）⇒ 手指位移 = 看板位移，无滞后。
//   · 松手惯性/回弹：位置 Animatable 用 LiquidHandFeel.current().valueSpec(= 本工程基准
//     spring(d=0.78, k=380) = GlassParameters BALANCED / 卡片回弹同参) 收口，并把 VelocityTracker
//     的释放速度当 initialVelocity 传进去（限幅 ±2400px/s，= LiquidGlassCard.state.setVelocity 同值）
//     ⇒ 甩一下会继续滑行再回弹（惯性），轻放则原地吸附（回弹）。
//   · 按压缩放：按下 press→1 / 松手 press→0 用 handFeel.pressSpec，缩放 1.00→0.96 用 handFeel.scaleSpec
//     （= 上游 DampedDragAnimation 的 pressProgressAnimation/scaleXAnimation 同一套弹簧参数口径，
//       缩放幅度取克制值 0.96 —— 上游 catalog 的按钮用 1.5，看板是信息件不适用放大）。
//   · 吸附阈值：看板矩形距左/右边缘 ≤ 24dp（HUD_SNAP_EDGE_DP）⇒ 吸附贴边 + 收起成
//     「状态点 + 帧率」一行数字（Scene 式简洁形态）。
//   · 拉出阈值：贴边收起态下【横向朝屏内拖出 ≥ 40dp】（HUD_PULL_OUT_DP）⇒ 还原成完整看板。
//     40dp > 24dp 是刻意的：拉出后看板与边缘的距离必然 > 吸附阈值 ⇒ 松手不会立刻又被吸回去。
//   · 宽度变化动画：展开/收起用同一个 valueSpec 弹簧在【测量期】插值宽高（Layout 里 lerp 两个形态
//     的自然尺寸），内容不重排、不重建；两侧内容按 expand 值交叉淡入淡出（alpha 走绘制期读值）。
//   · 持久化：SharedPreferences("perf_hud") 存 x/y（看板左上角，px）+ edge(-1/0/+1)，
//     进程重启后按容器夹取恢复；冷启动无存档 = 屏幕最顶端居中一条细浮条（Scene 观感）。
//   · 回退：DebugSwitches.perfHudDraggable（默认开）关掉 ⇒ 逐字回到右上角固定位
//     （原 CompactPerformancePanel 的渲染代码一字未改，只是读取层抽成共用的 rememberPerfHudReadings，
//      取值来源与数值完全一致）。
//   · 【H2·2026-09-17】置顶：zIndex(11f)（> 面板 z=10）——浮动容器 + 调用点 modifier（回退档）各一处；
//     看板是纯 UI 层（不建/不采样 LayerBackdrop）⇒ 置顶不产生任何采样/分层副作用 ✓。
//
// ✗ 不碰：glass 包的折射默认参数、控制中心胶囊/面板几何、交棒区、手势区、卡片手势
//        （本组件自带手势只挂在看板自身节点上，全屏只有一个 fillMaxSize 空 Box 作为定位容器，
//         该 Box 不挂任何 pointerInput ⇒ 不吃其它区域的点击）。
// ============================================================================

// ============================================================================
// 【看板 G2 连续圆角 · 2026-09-17】看板四角 = 与项目其它元素【同一套】G2 连续圆角。
//
// 口径（先看现实现，再换）：
//   · 改前：本文件两处 `RoundedCornerShape(14.dp)`（浮动档 PerfHudFloating + 回退档
//     CompactPerformancePanel）—— 普通圆角（圆弧 + 两侧直线相切）；
//   · 项目那套：`ui/G2RoundedShape.kt`（内部类，与 Shader 侧 sdContinuousRect 同一套归一化定义：
//     角部沿两边各延伸 E = 1.5286651·r、曲线为 n = 3 的超椭圆）。面板 / 胶囊 / 卡片裁切都在用它
//     （GlassControlsPanel.PanelChipShape、LiquidGlassScreen.PanelClipShapeCache、glass/GlassShape）。
// 本次改动（只动【浮动档 = 默认可见的看板】一处；回退档 CompactPerformancePanel 保持逐字不动 ——
//   它的契约是「perfHudDraggable=0 时逐字回到改动前」）：
//   · clip / background / border 三处共用【同一个 G2RoundedShape 实例】⇒ 内圆角与外轮廓同源，
//     不存在第二套半径估算；半径仍 = 14dp（与改前同值 ⇒ 尺寸/位置/文字/读数零变化）；
//   · 形状实例用 remember(g2On) 缓存（同 PanelClipShapeCache 的思路：官方图层按实例是否相等判
//     Outline 是否重建 ⇒ 不在每帧/每次重组新建）。
//
// 【开关·默认开】+ 一行回退（跨进程文件标志，与 lg_meld_on / lg_drag_yshield_off 同一套先例）：
//   回退：adb shell run-as com.liqglass.ultraclear sh -c 'mkdir -p files; echo 1 > files/lg_perf_hud_g2_off'
//   恢复：adb shell run-as com.liqglass.ultraclear sh -c 'rm -f files/lg_perf_hud_g2_off'
//   ⇒ 关掉后逐字回到 RoundedCornerShape(14.dp)（同一半径 ⇒ 只有角部曲线风格不同）。
//   【运行时即时生效】标志文件在每次 DebugBridge.revision 变化时重读（任意 setSwitches 广播即可触发，
//   无需重启）：`am broadcast ... --es cmd setSwitches --es name perfHudTrace --ei value 0` 之后
//   看板当场按新标志重建形状 ✓；重启 App 也同理（进程内首次读取）。
// ============================================================================

/** 看板四角半径（dp）—— 与改动前 RoundedCornerShape(14.dp) 同值（只换圆角风格，不动几何量级）。 */
private const val HUD_CORNER_DP = 14

/** 【开关】G2 连续圆角。默认开；源码级一行回退 = 把这里改成 false（等价于标志文件被写上 1）。 */
private const val PERF_HUD_G2_CORNERS_DEFAULT = true

/** 跨进程回退标志文件（内容 "1" = 关 G2 圆角 = 回退到改动前的普通圆角）。 */
private const val PERF_HUD_G2_OFF_FILE = "lg_perf_hud_g2_off"

/** 开关缓存（按 DebugBridge.revision 失效重读；普通 volatile，不产生订阅）。 */
@Volatile private var perfHudG2CacheRev: Int = Int.MIN_VALUE
@Volatile private var perfHudG2CacheVal: Boolean = PERF_HUD_G2_CORNERS_DEFAULT

/**
 * 看板是否用 G2 连续圆角（默认开）。
 *
 * [revision] = DebugBridge.revision 快照值：只要它变了就重读一次标志文件
 * ⇒ 设备上「写标志文件 + 任意 setSwitches 广播」当场生效（无需重启）✓
 */
private fun perfHudG2Enabled(revision: Int): Boolean {
    if (revision == perfHudG2CacheRev) return perfHudG2CacheVal
    val v = PERF_HUD_G2_CORNERS_DEFAULT && !runCatching {
        java.io.File("/data/data/com.liqglass.ultraclear/files/$PERF_HUD_G2_OFF_FILE")
            .readText().trim() == "1"
    }.getOrDefault(false)
    perfHudG2CacheRev = revision
    perfHudG2CacheVal = v
    return v
}

/**
 * 看板轮廓形状（**唯一来源**：clip / background / border / 展开态 / 收起态全部用它）。
 *
 * G2 档 = [G2RoundedShape]（= 项目那套，E=1.5286651·r / n=3 超椭圆，与 Shader 侧 sdContinuousRect 同源）；
 * 回退档 = 改动前的 [RoundedCornerShape]（逐字同表达式）。
 */
private fun perfHudShape(g2: Boolean): Shape =
    if (g2) G2RoundedShape(HUD_CORNER_DP.dp, HUD_CORNER_DP.dp, HUD_CORNER_DP.dp, HUD_CORNER_DP.dp)
    else RoundedCornerShape(HUD_CORNER_DP.dp)

/** 收起态贴左边缘。 */
private const val HUD_EDGE_LEFT = -1
/** 自由态（未贴边）。 */
private const val HUD_EDGE_NONE = 0
/** 收起态贴右边缘。 */
private const val HUD_EDGE_RIGHT = 1

/** 吸附阈值：看板矩形距左右边缘 ≤ 该值 ⇒ 自动吸附贴边。 */
private val HUD_SNAP_EDGE_DP = 24.dp
/** 拉出阈值：贴边收起态横向朝屏内拖出 ≥ 该值 ⇒ 还原成完整看板。 */
private val HUD_PULL_OUT_DP = 40.dp
/** 按压缩放（按下时缩到该比例；1.0 = 不缩）。 */
private const val HUD_PRESSED_SCALE = 0.96f
/** 释放速度限幅（px/s），与 LiquidGlassCard.state.setVelocity 同值。 */
private const val HUD_VELOCITY_CLAMP = 2400f

/** 【H2·看板置顶】看板浮层 z 序 = 11（> 面板 z=10）⇒ 悬在最顶层；纯 UI 层、不采样 ⇒ 无分层副作用 ✓。
 *  与 LiquidGlassScreen.kt 调用点 modifier 上的 zIndex(11f) 同值（那一处覆盖"右上角固定位"回退档）。 */
private const val HUD_Z_INDEX = 11f

/** 看板读数（内存 / 功耗 / 帧率三路轮询 + 帧快照），供展开态与收起态共用（只订阅一次）。 */
private class PerfHudReadings {
    var memMb by mutableIntStateOf(0)
    var powerText by mutableStateOf("--")
    var snapshot by mutableStateOf(PerformanceSnapshot.idle())
}

/** 持久化的看板位置与折叠态（进程重启后从这里恢复）。 */
private data class PerfHudSaved(val x: Float, val y: Float, val edge: Int)

/** SharedPreferences 存储（文件 = /data/data/<pkg>/shared_prefs/perf_hud.xml，重启不丢）。 */
private class PerfHudStore(context: Context) {
    private val prefs = context.getSharedPreferences("perf_hud", Context.MODE_PRIVATE)

    fun load(): PerfHudSaved? =
        if (!prefs.contains(KEY_X)) null
        else PerfHudSaved(
            prefs.getFloat(KEY_X, 0f),
            prefs.getFloat(KEY_Y, 0f),
            prefs.getInt(KEY_EDGE, HUD_EDGE_NONE)
        )

    fun save(x: Float, y: Float, edge: Int) {
        prefs.edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).putInt(KEY_EDGE, edge).apply()
    }

    private companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_EDGE = "edge"
    }
}

/** 【P47 取证】逐帧打点（logcat tag = LGHud）：默认 false = 零日志零开销。 */
private object PerfHudTrace {
    fun enabled(): Boolean = DebugSwitches.perfHudTrace

    fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    fun log(msg: String) {
        if (!enabled()) return
        android.util.Log.i("LGHud", msg)
    }
}

/**
 * 【P47】性能看板入口（唯一调用点）。开关关 = 右上角固定位（改动前的实现）。
 *
 * 读一次 [DebugBridge.revision]（快照状态）⇒ `setSwitches perfHudDraggable 0|1` 当场生效（无需重启）。
 */
@Composable
fun PerformanceHud(
    monitor: PerformanceMonitor,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    PerfHudTrace.log(
        "PerformanceHud compose: perfHudDraggable=${DebugSwitches.perfHudDraggable} revision=${DebugBridge.revision.intValue}"
    )
    if (DebugSwitches.perfHudDraggable) {
        PerfHudFloating(monitor)
    } else {
        // 回退档：逐字回到改动前（右上角固定位、原有实现）
        CompactPerformancePanel(monitor, modifier)
    }
}

/**
 * 【P47】全屏可拖 / 边缘吸附 / 可拉出的悬浮看板。
 *
 * 布局：一个 fillMaxSize 的空 Box 作为定位容器（不挂任何手势 ⇒ 不吃其它区域的点击），
 * 看板自身用 `Modifier.offset{}` 摆到目标位置（放置期读值 ⇒ 拖动不重组、命中区域跟着走）。
 *
 * 坐标口径（anchor）由 [edge] 决定，切换时用 [anchorFor] 换算 ⇒ 视觉连续、无跳变：
 *   · 贴左：anchor = 看板【左】边到屏幕左边的距离（贴边 = 0）
 *   · 贴右：anchor = 看板【右】边到屏幕右边的距离（贴边 = 0，宽度动画时自动保持贴边）
 *   · 自由：anchor = 看板左上角 x
 */
@Composable
private fun PerfHudFloating(monitor: PerformanceMonitor) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val readings = rememberPerfHudReadings(monitor)
    val store = remember { PerfHudStore(context) }
    val saved = remember { store.load() }
    val statusTopRawPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    // 冷启动默认位的 y 下限：标准状态栏高度 24dp。
    // 为什么需要下限：组合期读 WindowInsets 在【首帧】可能仍是 0（真实值下一帧才到），
    // 而"恢复位置"是一次性效果 ⇒ 没有下限时默认位会落到状态栏里（实测 y=12px 压在状态栏上 ✗）。
    // 真机/平板里实测值 ≥ 24dp 时一律用实测值 ✓
    val statusTopPx = maxOf(statusTopRawPx, with(density) { 24.dp.toPx() })
    val snapPx = with(density) { HUD_SNAP_EDGE_DP.toPx() }
    val pullPx = with(density) { HUD_PULL_OUT_DP.toPx() }
    val topPadPx = with(density) { 6.dp.toPx() }
    // 【G2 连续圆角】形状唯一来源 = perfHudShape(g2)；g2 标志按 DebugBridge.revision 重读
    // （读一次 revision = 快照状态订阅 ⇒ 任意 setSwitches 广播后当场重建形状，无需重启 ✓）。
    // remember(g2On)：只在开关变化时新建实例（官方图层按实例相等判 Outline 重建，见文件头注释）。
    val g2On = perfHudG2Enabled(DebugBridge.revision.intValue)
    val shape = remember(g2On) { perfHudShape(g2On) }

    var container by remember { mutableStateOf(IntSize.Zero) }
    var hudSize by remember { mutableStateOf(IntSize.Zero) }
    var expandedSize by remember { mutableStateOf(IntSize.Zero) }
    var collapsedSize by remember { mutableStateOf(IntSize.Zero) }

    val initEdge = saved?.edge ?: HUD_EDGE_NONE
    var edge by remember { mutableIntStateOf(initEdge) }
    var expanded by remember { mutableStateOf(initEdge == HUD_EDGE_NONE) }
    /** 位置（px）：anchor 语义见 [PerfHudFloating] 头注释；y = 看板顶边。 */
    val anchorX = remember { Animatable(saved?.x ?: 0f) }
    val posY = remember { Animatable(saved?.y ?: 0f) }
    /** 展开度 0（收起态：状态点 + 帧率一行）..1（完整看板）。 */
    val expand = remember { Animatable(if (initEdge == HUD_EDGE_NONE) 1f else 0f) }
    /** 按压进度 0..1 与按压缩放（= DampedDragAnimation 的 press/scale 两条弹簧）。 */
    val press = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    /** 手势世代：每次落下 +1；收尾动画据此判断"已被新手势接管"（避免写过期的落盘值）。 */
    var gestureEpoch by remember { mutableIntStateOf(0) }

    /** y 的可用窗口：上界避开状态栏（状态栏窗口在应用窗口【之上】⇒ 落在里面的触摸归 SystemUI，
     *  看板拖进去会既被压住又点不着 ✗ —— 本机实测踩过：pill 停在 y=0 后手势完全无响应）。
     *  下界 = 容器底边。 */
    fun clampTop(y: Float, h: Float): Float {
        val maxTop = (container.height - h).coerceAtLeast(0f)
        val lo = statusTopPx.coerceAtMost(maxTop)
        return y.coerceIn(lo, maxTop.coerceAtLeast(lo))
    }

    fun currentWidthPx(): Float = hudSize.width.toFloat().coerceAtLeast(1f)

    fun placedLeft(): Float = when (edge) {
        HUD_EDGE_LEFT -> anchorX.value
        HUD_EDGE_RIGHT -> container.width - currentWidthPx() - anchorX.value
        else -> anchorX.value
    }

    fun anchorFor(left: Float): Float = when (edge) {
        HUD_EDGE_LEFT -> left
        HUD_EDGE_RIGHT -> container.width - currentWidthPx() - left
        else -> left
    }

    /**
     * 手势期写入：直接 snapTo（1:1 跟手；会打断正在跑的收尾动画 = 手指接管，与卡片 dragEpoch 同义）。
     *
     * ⚠️ 必须是【非 suspend】的：`awaitEachGesture{}` 是 @RestrictsSuspension 受限协程作用域，
     * 在它内部只能调它自己的挂起函数 ⇒ 用 scope.launch（= 上游 DampedDragAnimation 在
     * directDragFollow 分支里 updateValue() 的写法：每帧 launch 一个 snapTo）✓
     */
    fun setAnchor(x: Float, y: Float) {
        scope.launch {
            anchorX.snapTo(x)
            posY.snapTo(y)
        }
    }

    // ---- 首次布局（两形态自然尺寸都量到后）：夹进屏幕 + 冷启动默认位（最顶端居中细浮条，Scene 观感）----
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(container, expandedSize.width, collapsedSize.width) {
        if (restored) return@LaunchedEffect
        if (container.width == 0 || container.height == 0) return@LaunchedEffect
        val w = (if (expanded) expandedSize else collapsedSize).width.toFloat()
        val h = (if (expanded) expandedSize else collapsedSize).height.toFloat()
        if (w <= 0f || h <= 0f) return@LaunchedEffect
        restored = true
        val maxLeft = (container.width - w).coerceAtLeast(0f)
        val targetLeft = when {
            edge == HUD_EDGE_LEFT -> 0f
            edge == HUD_EDGE_RIGHT -> maxLeft
            saved == null -> maxLeft / 2f                       // 冷启动：屏幕最顶端居中
            else -> placedLeft().coerceIn(0f, maxLeft)
        }
        val targetTop = if (saved == null) (statusTopPx + topPadPx) else posY.value
        val clampedTop = clampTop(targetTop, h)
        anchorX.snapTo(anchorFor(targetLeft))
        posY.snapTo(clampedTop)
        store.save(targetLeft, clampedTop, edge)
        PerfHudTrace.log(
            "restore container=%dx%d saved=%s w=%.0f h=%.0f → left=%.0f top=%.0f edge=%d expanded=%b"
                .format(container.width, container.height, saved, w, h, targetLeft, clampedTop, edge, expanded)
        )
    }

    // ---- 【取证】位置/展开度逐帧读数（默认关；开关 = DebugSwitches.perfHudTrace）----
    // snapshotFlow 只在值真的变化时发一帧 ⇒ 这一串就是【显示端逐帧轨迹】：与手势循环里的
    // `ev t=.. finger=` 行按 t 对齐，即可算出"手指位移 vs 看板位移"、"吸附/折叠何时开始、多久收口"。
    // ⚠️ key 用 DebugBridge.revision（setSwitches 会自增）：这样【运行中翻开关】就能立刻开始/停止打点 ✓
    LaunchedEffect(DebugBridge.revision.intValue) {
        if (!PerfHudTrace.enabled()) return@LaunchedEffect
        PerfHudTrace.log(
            "trace on: left=%.1f top=%.1f edge=%s expanded=%b（冷启动/重启后这一行 = 从 SharedPreferences 恢复的落点）"
                .format(placedLeft(), posY.value, edgeName(edge), expanded)
        )
        snapshotFlow { Triple(anchorX.value, posY.value, expand.value) }.collect { (ax, ay, e) ->
            // left 报【摆放后的实际左坐标】（含边界夹取），与屏幕上看到的一致
            val placed = placedLeft().coerceIn(
                0f, (container.width - currentWidthPx()).coerceAtLeast(0f)
            )
            PerfHudTrace.log(
                "frame anchor=%.1f top=%.1f left=%.1f hud=%dx%d expand=%.3f edge=%s"
                    .format(ax, ay, placed, hudSize.width, hudSize.height, e, edgeName(edge))
            )
        }
    }

    /** 松手收尾：吸附判定 + 惯性/回弹 + 宽度折叠（两轴并行）+ 落盘。 */
    suspend fun settle(velocity: Offset, epoch: Int) {
        if (container.width == 0 || container.height == 0) return
        val hf = LiquidHandFeel.current()
        val cw = container.width.toFloat()
        val ch = container.height.toFloat()
        val wNow = currentWidthPx()
        val hNow = hudSize.height.toFloat().coerceAtLeast(1f)
        val wTarget = ((if (expanded) expandedSize else collapsedSize).width.toFloat())
            .takeIf { it > 0f } ?: wNow
        val hTarget = ((if (expanded) expandedSize else collapsedSize).height.toFloat())
            .takeIf { it > 0f } ?: hNow
        val left = placedLeft().coerceIn(0f, (cw - wNow).coerceAtLeast(0f))
        val top = clampTop(posY.value, hNow)
        val distLeft = left
        val distRight = cw - (left + wNow)
        val snapped = when {
            distLeft <= snapPx -> HUD_EDGE_LEFT
            distRight <= snapPx -> HUD_EDGE_RIGHT
            else -> HUD_EDGE_NONE
        }
        PerfHudTrace.log(
            ("release v=(%.0f,%.0f)px/s left=%.1f top=%.1f w=%.0f distL=%.1f distR=%.1f " +
                "snapThr=%.0f pullThr=%.0f → edge=%s expanded=%b 尺寸(展开=%dx%d 收起=%dx%d)")
                .format(
                    velocity.x, velocity.y, left, top, wNow, distLeft, distRight,
                    snapPx, pullPx, edgeName(snapped), snapped == HUD_EDGE_NONE,
                    expandedSize.width, expandedSize.height, collapsedSize.width, collapsedSize.height
                )
        )
        // 换约定（贴右 ↔ 自由）：先记下"当前视觉左坐标"，换完 edge 再换算 anchor ⇒ 视觉不变
        val visLeft = left
        val anchorVx = if (edge == HUD_EDGE_RIGHT) -velocity.x else velocity.x
        edge = snapped
        anchorX.snapTo(anchorFor(visLeft))
        expanded = (snapped == HUD_EDGE_NONE)
        val targetAnchor = when (snapped) {
            HUD_EDGE_LEFT -> 0f
            HUD_EDGE_RIGHT -> 0f                          // 贴右：anchor = 与右缘的距离 ⇒ 目标恒 0
            else -> visLeft.coerceIn(0f, (cw - wTarget).coerceAtLeast(0f))
        }
        val targetTop = clampTop(top, hTarget)
        val expandTarget = if (snapped == HUD_EDGE_NONE) 1f else 0f
        val t0 = PerfHudTrace.nowMs()
        // 位置（惯性 + 回弹）与宽度（折叠/展开）【并行】：吸附与收起同时发生
        val posJob = scope.launch {
            anchorX.animateTo(targetAnchor, hf.valueSpec(0.5f), initialVelocity = anchorVx)
            posY.animateTo(targetTop, hf.valueSpec(0.5f), initialVelocity = velocity.y)
            PerfHudTrace.log(
                "pos settle done dur=%dms anchor=%.1f y=%.1f"
                    .format(PerfHudTrace.nowMs() - t0, anchorX.value, posY.value)
            )
        }
        val sizeJob = scope.launch {
            val t = PerfHudTrace.nowMs()
            expand.animateTo(expandTarget, hf.valueSpec(0.001f))
            PerfHudTrace.log(
                "width %s done dur=%dms expand=%.3f hud=%dx%d"
                    .format(
                        if (expandTarget == 1f) "expand" else "collapse",
                        PerfHudTrace.nowMs() - t, expand.value, hudSize.width, hudSize.height
                    )
            )
        }
        posJob.join()
        sizeJob.join()
        if (gestureEpoch != epoch) {
            PerfHudTrace.log("settle superseded by new gesture（不落盘，等新手势收尾）")
            return
        }
        val finalW = hudSize.width.toFloat().coerceAtLeast(1f)
        val finalH = hudSize.height.toFloat().coerceAtLeast(1f)
        val finalLeft = placedLeft().coerceIn(0f, (cw - finalW).coerceAtLeast(0f))
        val finalTop = clampTop(posY.value, finalH)
        anchorX.snapTo(anchorFor(finalLeft))
        posY.snapTo(finalTop)
        store.save(finalLeft, finalTop, snapped)
        PerfHudTrace.log(
            "persist left=%.1f top=%.1f edge=%s (SharedPreferences perf_hud)"
                .format(finalLeft, finalTop, edgeName(snapped))
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            // 【H2·置顶】浮动档的 zIndex 必须挂在本容器上：调用点 modifier（含 align/statusBarsPadding/
            //   padding，回退档专用）不能整体挂进来（会推移 fillMaxSize 的坐标系 ⇒ 看板落点/存档全偏 ✗）。
            .zIndex(HUD_Z_INDEX)
            .onSizeChanged { container = it }
    ) {
        Layout(
            content = {
                // 展开态：完整看板（自然尺寸测量；父级裁剪 + 交叉淡入）
                Box(Modifier.onSizeChanged { expandedSize = it }) {
                    PerfHudExpandedBody(readings, Modifier.graphicsLayer { alpha = expand.value })
                }
                // 收起态：一行关键数字（状态点 + 帧率）
                Box(Modifier.onSizeChanged { collapsedSize = it }) {
                    PerfHudCollapsedBody(readings, Modifier.graphicsLayer { alpha = 1f - expand.value })
                }
            },
            modifier = Modifier
                .offset {
                    val w = currentWidthPx()
                    val h = hudSize.height.toFloat().coerceAtLeast(1f)
                    val left = placedLeft().coerceIn(0f, (container.width - w).coerceAtLeast(0f))
                    val top = clampTop(posY.value, h)
                    IntOffset(left.roundToInt(), top.roundToInt())
                }
                .onSizeChanged { hudSize = it }
                .graphicsLayer {
                    // 按压缩放（参考 DampedDragAnimation 的 scaleX/scaleY）
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(shape)
                .background(Color(0xCC0B1220))
                .border(1.dp, Color(0x33FFFFFF), shape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        gestureEpoch++
                        val myEpoch = gestureEpoch
                        val hf = LiquidHandFeel.current()
                        val t0 = PerfHudTrace.nowMs()
                        scope.launch { press.animateTo(1f, hf.pressSpec(0.001f)) }
                        scope.launch { scale.animateTo(HUD_PRESSED_SCALE, hf.scaleSpec(0.001f)) }
                        val edgeAtDown = edge
                        val expandedAtDown = expanded
                        val baseAnchor = anchorX.value
                        val baseTop = posY.value
                        val tracker = VelocityTracker()
                        var finger = Offset.Zero
                        var lastLogMs = 0L
                        var ev = awaitPointerEvent()
                        while (true) {
                            val change = ev.changes.firstOrNull { it.id == down.id }
                            if (change != null) {
                                val amount = change.position - change.previousPosition
                                if (amount != Offset.Zero) {
                                    change.consume()
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    finger += amount
                                    val anchorNow: Float
                                    if (edgeAtDown != HUD_EDGE_NONE && !expandedAtDown) {
                                        // 贴边收起态：横向"朝屏内"的位移 = 拉出距离（另一侧仍钉在边缘上）。
                                        // out 已是"朝屏内"的正量；两侧的 anchor 语义都是【与贴边那一侧的距离】
                                        // ⇒ 两侧都直接 += out（贴左：anchor = 左坐标；贴右：anchor = 距右缘距离）✓
                                        val out = (if (edgeAtDown == HUD_EDGE_LEFT) finger.x else -finger.x)
                                            .coerceAtLeast(0f)
                                        anchorNow = baseAnchor + out
                                        // 拉出超过阈值 ⇒ 还原成完整看板（宽度动画与拖动并行）
                                        if (!expanded && out >= pullPx) {
                                            expanded = true
                                            scope.launch { expand.animateTo(1f, hf.valueSpec(0.001f)) }
                                            PerfHudTrace.log(
                                                "pull-out trigger t=%dms pull=%.1fpx thr=%.1fpx edge=%s"
                                                    .format(
                                                        PerfHudTrace.nowMs() - t0, out, pullPx,
                                                        edgeName(edgeAtDown)
                                                    )
                                            )
                                        }
                                    } else {
                                        anchorNow = baseAnchor + finger.x
                                    }
                                    val topNow = clampTop(baseTop + finger.y, hudSize.height.toFloat())
                                    setAnchor(anchorNow, topNow)
                                    val now = PerfHudTrace.nowMs()
                                    if (PerfHudTrace.enabled() && now - lastLogMs >= 16L) {
                                        lastLogMs = now
                                        PerfHudTrace.log(
                                            ("ev t=%dms finger=(%+.1f,%+.1f) write=(anchor=%.1f,top=%.1f) " +
                                                "edge=%s pull=%.1f")
                                                .format(
                                                    now - t0, finger.x, finger.y,
                                                    anchorNow, topNow,
                                                    edgeName(if (edgeAtDown == HUD_EDGE_NONE) edge else edgeAtDown),
                                                    (if (edgeAtDown == HUD_EDGE_LEFT) finger.x else -finger.x)
                                                        .coerceAtLeast(0f)
                                                )
                                        )
                                    }
                                }
                            }
                            if (!ev.changes.any { it.id == down.id && it.pressed }) break
                            ev = awaitPointerEvent()
                        }
                        // 松手：按压回弹（与位置收尾并行）+ 吸附/惯性收尾
                        val v = tracker.calculateVelocity()
                        val clamped = Offset(
                            v.x.coerceIn(-HUD_VELOCITY_CLAMP, HUD_VELOCITY_CLAMP),
                            v.y.coerceIn(-HUD_VELOCITY_CLAMP, HUD_VELOCITY_CLAMP)
                        )
                        scope.launch { press.animateTo(0f, hf.pressSpec(0.001f)) }
                        scope.launch { scale.animateTo(1f, hf.scaleSpec(0.001f)) }
                        PerfHudTrace.log("up t=%dms finger=(%+.1f,%+.1f)".format(PerfHudTrace.nowMs() - t0, finger.x, finger.y))
                        scope.launch { settle(clamped, myEpoch) }
                    }
                }
        ) { measurables, _ ->
            // 两形态都按【自然尺寸】测量，父级在测量期插值宽高 ⇒ 动画期间内容零重排
            val expandedPlaceable = measurables[0].measure(androidx.compose.ui.unit.Constraints())
            val collapsedPlaceable = measurables[1].measure(androidx.compose.ui.unit.Constraints())
            val t = expand.value.coerceIn(0f, 1f)
            val width = (collapsedPlaceable.width +
                (expandedPlaceable.width - collapsedPlaceable.width) * t).roundToInt().coerceAtLeast(1)
            val height = (collapsedPlaceable.height +
                (expandedPlaceable.height - collapsedPlaceable.height) * t).roundToInt().coerceAtLeast(1)
            layout(width, height) {
                // 贴右/自由靠右时内容贴右缘摆放（收起时从右缘"缩进去"），否则贴左缘
                val rightAligned = edge == HUD_EDGE_RIGHT
                expandedPlaceable.place(
                    if (rightAligned) width - expandedPlaceable.width else 0,
                    (height - expandedPlaceable.height) / 2
                )
                collapsedPlaceable.place(
                    if (rightAligned) width - collapsedPlaceable.width else 0,
                    (height - collapsedPlaceable.height) / 2
                )
            }
        }
    }
}

private fun edgeName(edge: Int): String = when (edge) {
    HUD_EDGE_LEFT -> "LEFT"
    HUD_EDGE_RIGHT -> "RIGHT"
    else -> "NONE"
}

/** 读数的唯一订阅点（内存 / 功耗 / 帧快照三路），写进稳定引用的 [PerfHudReadings] ⇒ 不触发上层重组。 */
@Composable
private fun rememberPerfHudReadings(monitor: PerformanceMonitor): PerfHudReadings {
    val context = LocalContext.current
    val readings = remember { PerfHudReadings() }

    LaunchedEffect(monitor) {
        monitor.snapshot.collect { readings.snapshot = it }
    }

    // 内存：读自身 PSS。原实现把 Debug.getMemoryInfo（系统调用）放在主线程轮询，
    // 每秒一次阻塞 UI 线程 → 静置场景实测约 4 帧/秒且多数被判 slow UI（真机 janky 42~45% ✗）。
    // 优化：读取移到 Dispatchers.IO，轮询降到 2s（只改成本，不改任何观感）。
    LaunchedEffect(Unit) {
        while (true) {
            readings.memMb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val info = Debug.MemoryInfo()
                    Debug.getMemoryInfo(info)
                    info.totalPss / 1024
                }.getOrDefault(0)
            }
            delay(2000)
        }
    }

    // 功耗（用户要求）：采用【电荷计数器差分法】—— 与"单电芯/双电芯"无关 ✓✓
    // 背景：本机是双电芯（bms + bms_slave），BATTERY_PROPERTY_CURRENT_NOW 在双芯机型上
    // 语义不统一（有的给总电流、有的只给单芯）→ 用它可能少算一半 ✗。
    // CHARGE_COUNTER 是【整包总电荷】(µAh) → |ΔCharge/Δt| × 电压 = 平均总功率 ✓，
    // 只用框架 API、无需权限、电芯数无关 ✓。窗口 2s，取不到时回退瞬时电流法 ✓。
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        var lastCtr = Long.MIN_VALUE
        var lastMs = 0L
        while (true) {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val batt = context.registerReceiver(
                        null,
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    )
                    val voltMv = batt?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                    val volt = if (voltMv > 0) voltMv / 1000f else 4.0f
                    val now = android.os.SystemClock.elapsedRealtime()
                    val ctr = bm?.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1L
                    var watt = -1f
                    if (ctr > 0 && lastCtr > 0 && now > lastMs) {
                        val dAh = (ctr - lastCtr).toFloat() / 1_000_000f      // µAh → Ah
                        val dtH = (now - lastMs) / 3_600_000f                  // ms → h
                        if (dtH > 0f) watt = kotlin.math.abs(dAh / dtH) * volt // Ah/h = A → ×V = W
                    }
                    if (ctr > 0) { lastCtr = ctr; lastMs = now }
                    if (watt < 0.05f || watt > 40f) {
                        // 回退：瞬时电流法（本机单芯时也大致可用 ✓）
                        val curRaw = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                            ?: Int.MIN_VALUE
                        if (curRaw != Int.MIN_VALUE && curRaw != 0) {
                            val ma = if (kotlin.math.abs(curRaw) > 20000) curRaw / 1000f else curRaw.toFloat()
                            watt = kotlin.math.abs(ma) * volt / 1000f
                        }
                    }
                    watt
                }.getOrDefault(-1f)
            }
            readings.powerText = if (res >= 0.05f) {
                if (res >= 1f) "%.1fW".format(res) else "%.0fmW".format(res * 1000f)
            } else "--"
            delay(2000)
        }
    }

    return readings
}

/** 展开态内容：与改动前的紧凑看板逐字同构（四项指标 + 帧率压力行；内边距 10dp/7dp 与原实现相同）。 */
@Composable
private fun PerfHudExpandedBody(readings: PerfHudReadings, modifier: Modifier = Modifier) {
    val snapshot = readings.snapshot
    val avg = snapshot.avgFrameMs
    val pressure = when {
        avg == null -> "采样中"
        avg <= 16.7f -> "低"
        avg <= 33f -> "中"
        else -> "高"
    }
    val pressureColor = when {
        avg == null -> Color(0xFF9FB2D6)
        avg <= 16.7f -> Color(0xFF7BE0A4)
        avg <= 33f -> Color(0xFFFFD37A)
        else -> Color(0xFFFF8F8F)
    }
    val fpsVal = snapshot.fps
    val fpsTxt = if (fpsVal != null) "%.0ffps".fmt(fpsVal) else "--"
    val memMb = readings.memMb

    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            CompactMetric("内存", if (memMb > 0) "${memMb}MB" else "--", 74.dp)
            CompactMetric(
                "GPU 占用",
                // 用户要"占用压力"而不是毫秒：GPU 帧耗时占 60fps 帧预算(16.7ms)的比例
                // >100% 会被读成"数值错了"（用户反馈 GPU 占用 102% 异常）→ 收敛到 0..100，
                // 真实过载仍由"帧率压力"那一格表达。
                snapshot.gpuMedianMs?.let { "%.0f%%".fmt((it / 16.7f * 100f).coerceIn(0f, 100f)) } ?: "N/A",
                74.dp
            )
            CompactMetric("CPU", snapshot.appCpuPercent?.let { "%.0f%%".fmt(it) } ?: "--", 74.dp)
            CompactMetric("功耗", readings.powerText, 74.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("帧率监控", fontSize = 10.sp, color = Color(0xFF9FB2D6))
            Text(
                (if (avg != null) "$pressure · $fpsTxt · %.1fms".fmt(avg) else "$pressure · $fpsTxt"),
                color = pressureColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 收起态内容：一行关键数字（状态点 + 帧率），高度 ≈ 24dp 的细条。 */
@Composable
private fun PerfHudCollapsedBody(readings: PerfHudReadings, modifier: Modifier = Modifier) {
    val snapshot = readings.snapshot
    val avg = snapshot.avgFrameMs
    val pressureColor = when {
        avg == null -> Color(0xFF9FB2D6)
        avg <= 16.7f -> Color(0xFF7BE0A4)
        avg <= 33f -> Color(0xFFFFD37A)
        else -> Color(0xFFFF8F8F)
    }
    val fpsTxt = snapshot.fps?.let { "%.0ffps".fmt(it) } ?: "--"
    Row(
        modifier = modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(pressureColor)
        )
        Text(
            fpsTxt,
            fontSize = 11.sp,
            color = pressureColor,
            fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun CompactMetric(label: String, value: String, width: Dp = 74.dp) {
    // 等宽单元格 + 标签在上、数值在下：三组的空隙恒定，数值再变也不会横向跳动
    //（此前标签与数值同排、各自宽度不同 → 视觉空隙时宽时窄，用户报"文字间隔有问题"）
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            label, fontSize = 10.sp, color = Color(0xFF9FB2D6),
            maxLines = 1, softWrap = false
        )
        Text(
            value, fontSize = 12.sp, color = Color(0xFFF4F7FF),
            fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            maxLines = 1, softWrap = false
        )
    }
}

/**
 * 右上角常驻的紧凑性能看板（【P47】回退档：`setSwitches perfHudDraggable 0` 逐字回到本实现）。
 *
 * 只显示普通用户看得懂的四项：内存 / GPU / CPU / 帧率压力。
 * —— 用户反馈"FPS4、P95、Jank 一个正常用户看不懂"，原来那套专业指标已移除；
 * 帧率压力由平均帧时间相对 16.7ms（60Hz 一帧预算）换算成 低/中/高 三档并配色。
 * 【2026-09-25 注释修正】HUD 自带半透明深色底衬 ⇒ 高对比配色两档主题下都可读，
 * 本看板不接入 GlassTheme 调色板（行为不变，仅注释更新）。
 *
 * 【P47】读数来源抽到 [rememberPerfHudReadings]（与可拖看板同一份，数值逐字同源）、
 * 内边距 10dp/7dp 移进 [PerfHudExpandedBody]（与可拖看板共用同一个内容组件）；
 * 形状/底衬/边框/间距/字号/文案/配色与改动前逐字一致。
 */
@Composable
fun CompactPerformancePanel(
    monitor: PerformanceMonitor,
    modifier: Modifier = Modifier
) {
    val readings = rememberPerfHudReadings(monitor)
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier
            .clip(shape)
            .background(Color(0xCC0B1220))
            .border(1.dp, Color(0x33FFFFFF), shape),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        PerfHudExpandedBody(readings)
    }
}

private fun String.fmt(vararg args: Any): String = String.format(Locale.US, this, *args)
