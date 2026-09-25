package com.example.liquidglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import com.example.liquidglass.debug.AppDebugLog
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.debug.DebugSwitches
// 【P46·二级页玻璃悬浮底栏】真实上游移植组件（按压形变 / 二次折射旋钮 / 色散 lens 均组件自带）
import com.example.liquidglass.ui.components.liquid.LiquidBottomTab
import com.example.liquidglass.ui.components.liquid.LiquidBottomTabs
import com.example.liquidglass.ui.components.liquid.LiquidHandFeel
import androidx.compose.foundation.clickable
// 面板拖动状态机：Compose 官方 AnchoredDraggable（锚点 0f/1f/2f，垂直方向）
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.ripple
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableFloatStateOf
import com.example.liquidglass.backdrop.glassBridge
import com.example.liquidglass.glass.GlassQuality
import kotlin.math.atan2
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.sqrt
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.util.lerp
import com.example.liquidglass.backdrop.BackdropAdapter
import com.example.liquidglass.backdrop.capture
import com.example.liquidglass.backdrop.glassPanel
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.example.liquidglass.backdrop.rememberBackdropAdapter
import com.example.liquidglass.performance.PerformanceMonitor
import com.example.liquidglass.performance.RefreshRateController
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 玻璃层绘制诊断节流（临时）。 */
private var glassDrawLogMs = 0L

/**
 * 页面根布局（分层顺序）：
 *
 * 1. BackgroundScene（唯一 Backdrop 捕获源，Modifier.capture 注册捕获层）
 * 2. 双卡代谢桥（共享父层绘制，位于卡片之下）
 * 3. LiquidGlassCard（Backdrop 光学层 + 前景内容）
 * 4. GlassControlsPanel（参数面板）
 * 5. PerformancePanel（性能面板，底部、避开导航栏 Insets）
 *
 * 背景滚动、卡片拖动后，采样坐标由 Backdrop 按卡片窗口坐标自动同步；
 * 旋转 / 分屏 / 尺寸变化通过 onSizeChanged 与 Insets 读取实时更新。
 */
private const val DRAG_SENSITIVITY = 1.0f   // 原 2.6 放大 2.6 倍 → 卡片乱飘 ✗，改 1:1 跟手   // 修复：原 2.6 把手指位移放大 2.6 倍 → 卡片"乱飘" ✗（实测手指 90px、卡片 231px），改 1:1 跟手

/**
 * 【边缘抗锯齿】面板/胶囊容器裁剪形状的外扩量（px，见 DebugSwitches.panelEdgeAa）。
 * 1.5px ≈ 0.55dp：足以让那条【无 AA 的路径硬裁剪边】退出玻璃 SDF 的可见羽化带
 * （外扩 1.5px 处 SDF coverage≈0.2，肉眼不可见），又远小于玻璃层相对内容的余量
 * （面板玻璃层过采样 pad ≈18.5px、卡片玻璃层 8dp/19dp）→ 内容不会在直边露出 ✓
 */
private const val PANEL_CLIP_INFLATE_PX = 1.5f

/**
 * 【边缘抗锯齿·真机硬化】容器裁剪在【玻璃层可见档位】使用的外扩量（px）。
 *
 * 为什么需要比 1.5px 更大：1.5px 只保证"硬裁剪边"退出【填充层路径绘制】的 AA 窄带（≈1px），
 * 但玻璃层（p<0.30，玻璃真的参与绘制）的可见边界是 Shader 的 SDF 覆盖率羽化带，实测该羽化带
 * 在边界外侧还有 ~2.5px 的非零 coverage（模拟器实测：胶囊左端弧每行有 2~4 个"中间覆盖率"像素，
 * 见 ~/Downloads/LG-edge-iso/capsule/）。也就是说 1.5px 处仍然落在羽化带【内部】——
 * 那条裁剪边会把羽化带的外侧 1.5px 切掉；在"GPU 对 Path 裁剪不做 AA"的设备上，这一刀是硬切 ✗
 * （模拟器上就能量到：① 开/关在胶囊端点弧改变 620 个像素、最大差 59 —— 那是被切掉的那圈）。
 *
 * 6px ≥ 羽化带外沿（实测 ~2.5px 的 2 倍余量）⇒ 裁剪边彻底离开羽化带，可见边界 100% 由
 * Shader 的 SDF 羽化决定（AGSL 的覆盖率渐隐在【所有】设备上都做 AA ✓，与 GPU 的
 * Path/clip AA 行为无关）—— 这就是"把可见边界交给 SDF 羽化"的完整版本。
 *
 * ⚠️ 只在 glassAlphaOf(p) > 0.001 的档位生效（p ≲ 0.2956）：那时 contentAlphaOf(p) 恒为 0
 * （内容从 p>0.42 才开始淡入）⇒ 外扩 6px 不会露出内容 ✓；面板展开档位仍用 1.5px。
 * 远小于玻璃层余量（面板玻璃层过采样 pad ≈18.5px）✓。
 *
 * 默认 false（= 与今天逐像素一致，硬门安全）；真机 A/B 用
 * `--es cmd setSwitches --es name aaFixContainerClearFeather --ei value 1` 打开 ✓
 */
private const val PANEL_CLIP_CLEAR_FEATHER_PX = 6f

/**
 * 【次因修复·设置列表提前进入组合】打开方向下列表的入组合阈值（p 的无量纲进度，0..2）。
 * 原来与 contentVisible 共用 0.42：列表【首次组合】的那一帧实测 42~67ms（4/4 次可复现），
 * 正好砸在 p≈0.42 的加速段（面板高度变化最快处）⇒ 肉眼最明显的单帧重击。
 * 提前到 0.10：面板还很小（h≈166px）、高度变化极慢（h'ease(0.1)≈0），且内容 alpha 仍为 0
 * （contentAlphaOf(p)=0 直到 p=0.42）⇒ 这次性开销落在完全不可见、位移最慢的开头 ✓
 * ⚠️ 只在【打开方向】（showControlCenter=true）生效；关闭方向仍用 0.42（安全语义不变）。
 */
private const val LIST_EARLY_COMPOSE_P = 0.10f

/**
 * 【首次组合开销·方案B】判定"面板已静止"的采样间隔（ms）。
 * 判据 = 进度 offset 连续两次采样【完全相同】+ 该值贴住某个锚点（±0.5px）+ p≥0.98。
 * 48ms ≈ 本机 2 个 vsync（真机帧中位 ~25ms）⇒ 两次采样必然跨越了至少一帧动画时间。
 * 不用 anchorState.isAnimationRunning：手柄松手后的官方收口走【无 target 的 performFling】
 * → 该标志恒为 false ✗（NEXT.md 已记，这里必须看进度、不看标志）。
 */
private const val PANEL_SETTLE_SAMPLE_MS = 48L

// ============ 【控制中心「半屏→全屏」收口时序预设 · 2026-09-14 用户反馈专项】 ============
// 用户：「控制中心由半屏切换到全屏的动画有点太快了」。逐帧日志实测确认：该段 = 进度 p 的第二段
// （p:1→2，全屏 = 屏高×0.98；p=1 半屏 = 屏高×0.55）。这一段里手指部分是 1:1 跟手的（不受预设影响），
// 唯一的自动插值 = 松手后的官方收口动画 ⇒ 预设只需要改【收口动画的时长与曲线】✓
// 铁律：p=1 / p=2 的几何端点（尺寸/位置/圆角）一律不动 ✓；其它收口方向也沿用原时长原曲线 ✓

/** 「半屏→全屏」判定阈值：松手时 p 必须已贴在半屏及以上（p=1 半屏 → 2 全屏），才允许按预设档取参。 */
private const val PANEL_FULL_SETTLE_MIN_P = 0.98f

/** 收口时长（现状·对照基线）：手柄松手后官方 fling 的收口 320ms ✓ */
private const val PANEL_SNAP_MS = 320

/** 收口时长（现状·对照基线）：内容列表松手后 onPostFling 的收口 340ms ✓ */
private const val PANEL_SNAP_MS_LIST = 340

/** 收口曲线（现状）：CubicBezier(0.42,0.05,0.22,1) —— 与本项目其它面板动画同一曲线 ✓ */
private val PANEL_SNAP_EASING: Easing = CubicBezierEasing(0.42f, 0.05f, 0.22f, 1f)

/** 各预设档的缓动曲线实例（按档位缓存，避免每次动画开始都新建一个曲线对象）。 */
private val PANEL_PRESET_EASINGS: Array<Easing> =
    Array(DebugSwitches.PanelTimingPreset.entries.size) { i ->
        val p = DebugSwitches.PanelTimingPreset.entries[i]
        CubicBezierEasing(p.cx1, p.cy1, p.cx2, p.cy2)
    }

// ============ 【动画一致性·2026-09-14 用户反馈①：展开与关闭的手感对齐】 ============
// 目标（用户原话）：「设置界面展开和关闭的动画在手感上不是很一致」。
// 「同一套手感」在本项目里被定义为【同一个时长 + 同一条曲线】，且对两个方向、两条路径、每一档
// 都从同一个常量/同一条规则取值（改前的问题是：不同方向/路径各拿一套数）。
// 逐帧实测（emulator-5554；LGLayout 的 p 序列 + LGSettle 的"选用参数"行；改前默认档）见 DebugSwitches
// 的 panelTimingUnified 注释块（900/620/320/340 四组数字与各自行程）。
// 两条规则（都由 panelTimingUnified 门控，关掉 = 逐字回到改动前 ✓）：
//   ① 程序化开合（点胶囊 0→1 / 点「← 关闭」p→0）：
//        一律 PANEL_PROGRAM_MS = 900ms ⇒ 0→1（已认可基准）与 1→0（同一条路反向）严格相等 ✓
//        （改前 1→0 = 620ms；2→0 也 620ms ⇒ "反向更快"是用户"手感不一致"的最大来源）
//   ② 松手收口（把手 fling / 列表 onPostFling；两个方向、两条路径共用同一个 spec 入口）：
//        一律 = 档位时长（默认档 STANDARD = 480ms）⇒ 展开收口与收起收口严格相等 ✓
//        （改前：半屏→全屏 320ms、其它方向 320ms、列表 340ms ⇒ 三套数 ✗）
//    为什么不用"时长 ∝ 行程"的速度恒等模型（本轮曾试过并逐帧量过）：手指已经把面板推到半路时，
//    剩余行程短 ⇒ 速度恒等会算出比改前【更短】的收口（实测 250px 行程 95ms，改前 320ms 反而更慢）
//    ⇒ 与用户"太快了"的反馈方向相反 ✗；固定时长则保证【每一处都不比改前快】（320/340→480、
//    620→900），且两方向两路径严格相等 ✓。

/** 程序化开合的时长（ms）：0→1 全行程 = 900ms（用户认可的展开手感，硬约束不动 ✓）；关闭同值。 */
private const val PANEL_PROGRAM_MS = 900f

/** 统一模式下的时长上下限（ms）——收口时长来自档位表（320/480/680），本夹取只在极端档位下兜底。 */
private const val PANEL_UNIFIED_MIN_MS = 120
private const val PANEL_UNIFIED_MAX_MS = 2400

/** 统一模式下的时长取整/夹取（四舍五入到 ms；避免 kotlin.math.roundToInt 的额外 import）。 */
private fun unifiedMsOf(rawMs: Float): Int =
    (rawMs + 0.5f).toInt().coerceIn(PANEL_UNIFIED_MIN_MS, PANEL_UNIFIED_MAX_MS)

/**
 * 面板收口动画 spec 的【唯一选择入口】（手柄 fling 的 animationSpec + 列表 onPostFling 的 settle 共用）。
 *
 * 判定（每次动画开始时求值一次）：
 *   · 收口目标 = 2f（全屏）**且** 松手时 p ≥ [PANEL_FULL_SETTLE_MIN_P] ⇒ 处在「半屏→全屏」段
 *     ⇒ 取 `DebugSwitches.panelTimingPreset` 的时长与曲线；
 *   · 其它一切收口（→0 收起 / 半途→1 / 全屏→半屏）⇒ 沿用 [fallbackMs] + 原曲线，逐字等同改动前 ✓
 *
 * 为什么必须是 AnimationSpec 而不是组合期算好的固定 spec：官方 flingBehavior 的 animationSpec 在
 * 【组合期】就固定了，而"这次收口到哪一档"只有松手那一刻才知道 ✗；`vectorize()` 由官方在每次动画
 * 开始时调用一次 ⇒ 在这里读档位/目标锚点即可做到「每次收口各自判定」，且 setSwitches 切档后下一次
 * 收口立即生效（不需要重组 ✓，DebugSwitches 是 @Volatile 普通字段）。
 */
private class PanelSettleSpec(
    /** 松手那一刻的进度 p（判定是否处在「半屏→全屏」段，同时也是日志字段） */
    private val startP: () -> Float,
    /** 官方状态里本次收口的目标锚点（0f 收起 / 1f 半屏 / 2f 全屏） */
    private val targetP: () -> Float,
    /** 非「半屏→全屏」段使用的收口时长（ms；一致性开关关闭时 = 改动前行为） */
    private val fallbackMs: Int,
    /** 本次收口的【实际行程】（px）：|目标锚点位置 − 当前 offset|，两次都取官方状态里的真值 */
    private val travelPx: () -> Float,
    /** 「半屏→全屏」跨度（px）= 一致性开关的行程单位（其余方向/路径按它等比换算成时长） */
    private val refSpanPx: () -> Float,
) : AnimationSpec<Float> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<Float, V>
    ): VectorizedAnimationSpec<V> {
        val target = targetP()
        val p0 = startP()
        val preset = DebugSwitches.panelTimingPresetUnified
        val unified = DebugSwitches.panelTimingUnified
        val travel = travelPx()
        val refSpan = refSpanPx().coerceAtLeast(1f)
        val halfToFull = target >= 1.5f && p0 >= PANEL_FULL_SETTLE_MIN_P
        val durMs: Int
        val ease: Easing
        if (unified) {
            // 【一致性】两方向 + 两路径 + 一个时长一条曲线：所有收口都用档位表的同一个值
            // （默认档 STANDARD = 480ms）⇒ 展开收口与收起收口、把手路径与列表路径严格相等 ✓
            durMs = unifiedMsOf(preset.settleMs.toFloat())
            ease = PANEL_PRESET_EASINGS[preset.ordinal]
        } else {
            // 【逐字回退】改动前行为：只有「半屏→全屏」段按档位取参，其余一律 [fallbackMs] + 原曲线
            durMs = if (halfToFull) preset.settleMs else fallbackMs
            ease = if (halfToFull) PANEL_PRESET_EASINGS[preset.ordinal] else PANEL_SNAP_EASING
        }
        // 【取证·零成本】收口动画的"选用参数"只在动画开始时才读得全 → 打一行含
        // (统一开关, 半屏→全屏, 档位, 行程, 单位, 时长, 曲线, 起点 p, 目标锚点) 的日志；
        // 与逐帧 LGLayout 的 (时间戳, p, h) 时间线配对，即可【数值算出】每个方向的
        // 实际时长、p 在 25/50/75% 处的时刻与速度曲线 ✓（同一行两端都可比：改前/改后）
        // 开关：AppDebugLog.enabled（setUi key=debugLogEnabled 1）——关闭时零开销。
        if (AppDebugLog.enabled) {
            android.util.Log.i(
                "LGSettle",
                ("begin 统一=%b 半屏→全屏=%b target=%.1f p0=%.3f 档=%s 行程=%.0fpx 单位=%.0fpx " +
                    "时长=%dms 曲线=(%.2f,%.2f,%.2f,%.2f)")
                    .format(unified, halfToFull, target, p0, preset.label, travel, refSpan, durMs,
                        preset.cx1, preset.cy1, preset.cx2, preset.cy2)
            )
        }
        return tween<Float>(durationMillis = durMs, easing = ease).vectorize(converter)
    }
}

/**
 * 【首次组合开销·方案B】兜底超时（ms）：静止判定若因任何原因没命中（动画被打断、停在中间值、
 * 或将来锚点语义变化），到点也强制让列表进组合 —— 列表一定会出现，绝不会留下空面板 ✗。
 */
private const val LIST_DEFER_TIMEOUT_MS = 1600L

/**
 * 【首次组合开销·方案A·输入隔离】内容子树被"停到屏幕外"时的最小位移（px）。
 * 实际取值 = max(2×屏高, 本常量)：内容槽高 = 0.55×屏高、且它的底边贴面板底边 ⇒ 位移
 * 2×屏高 足以让整棵子树（含溢出的部分）落在屏幕之外 ⇒ 不在任何触摸坐标里 ✓
 * （分辨率/横竖屏变化时自动跟随屏高，不写死像素）。
 */
private const val CONTENT_PARK_MIN_PX = 4096f

/**
 * 【首次组合开销·方案A】预热延迟（ms）：首帧之后再等这么久才做预组合。
 * 目的：把这次"全屏重组 + 列表首次组合"的帧放到冷启动的静止窗口里（不与首帧/背景解码/
 * 玻璃首绘同帧），此时屏幕上没有位移参照 ⇒ 不可察觉 ✓；窗口内若用户抢先打开面板，
 * 由方案B（推迟到面板定格）兜住，动画里依然不会有组合开销 ✓
 */
private const val LIST_WARMUP_DELAY_MS = 300L

// ================= 【P05·四文字无缝交棒】常量 =================

/**
 * 交棒【行程窗口】：位移 + 缩放在 p∈[0.15, 0.86] 内完成。
 *   · 起点段 p<0.15 仍在胶囊里（与旧标签同一位置/同一大小 ⇒ 端点像素不变 ✓）；
 *   · 终点段 p>0.86 已经贴在标题槽（此后只做原位交叉淡出）✓
 * 纯函数、只读 panel 的进度 p ⇒ 拖动（手指）与点击动画（tween）两条路径都自动同步 ✓
 */
private const val HANDOFF_TRAVEL_P0 = 0.15f
private const val HANDOFF_TRAVEL_P1 = 0.86f

/**
 * 交棒标签 ↔ 面板标题的【原位交叉淡出】窗口（p）：
 * 标签（白字带投影那一份）0.975→1.000 淡出，面板标题在【同一位置】0.975→1.000 淡入。
 * ⚠️ 为什么窗口必须这么晚（实测数据定的，不是拍的）：面板标题的"布局位置"在动画中期会随面板
 *   高度移动（p=0.88 时 1371px、p=1.0 才到终态 1421px —— 见 LGHandoff 逐帧日志），
 *   只有 p≥0.98 起才与终态重合（≤1px）。窗口放早会在"两个位置"上同时看到两份文字 ✗。
 * 【色阶细腻度追加（2026-09-14）】终点 0.998 → 1.000：起点不动（位置约束只钳【起点】✓），
 *   只把"最后 2‰ 的淡出尾巴"摊到今天动画的最后一帧 ⇒ 窗口时间跨度 95ms → 185ms（≈1.9×），
 *   逐帧 alpha 步进同步减半 ✓；对端点无影响（p=1 处 la 仍恒为 0 ✓）。
 *   ⚠️ 与颜色窗口的对齐关系：颜色窗口终点 == 本窗口起点（0.975）⇒ 交叉淡出这段时间里
 *   两份文字【同色同位置】，不会再出现"颜色已到位、字形还在猛淡"的二次跳变 ✓
 * （历史坑：面板标题跟着内容在 p=0.42 就渐入 → 与仍在旅行的标签重叠 = 两个「控制中心」✗）
 */
private const val HANDOFF_FADE_P0 = 0.975f
private const val HANDOFF_FADE_P1 = 1.000f

/**
 * 交棒终点的【纵向锚点】：标题中心相对【面板顶边】的偏移（px）的自标定初值 = 48dp。
 * 依据（本项目面板头部结构，实测两档 p 都精确成立）：
 *   把手 22dp + 标题行上下 padding 2dp*2 + 行高(48dp 触摸目标)/2 = 48dp ⇒ 标题盒中心 = 面板顶 + 48dp
 *   验证①（p=1，模拟器 1840×2944@320dpi）：面板顶 = 2944-1619 = 1325；标题盒中心实测 1421 = 1325+96px ✓
 *   验证②（p=2 同机）：面板顶 = 2944-2885 = 59；标题盒中心 155 = 59+96px ✓
 * 运行时还会用【p≥0.99 的实测值】自标定（见面板标题的 onGloballyPositioned）⇒ 头部结构变了也不会错 ✓
 */
private const val HANDOFF_TITLE_DY_DP = 48f

/**
 * 交棒终点的【字号比】= 面板标题 titleLarge(22sp) / 胶囊标签 labelLarge(14sp) = 1.5714。
 * 为什么用字号比而不是实测宽度比：布局宽度会被取整（实测胶囊标签 114px vs 理论 112px），
 * 用宽度比会让终态字号偏小 1.8%（整串字窄 ~3px）✗；字号比是精确值 ✓。
 * 纵向落点用【基线】对齐（见交棒实例里 toY 的推导）：两端的 lineHeight / 字体 padding 不同，
 * 用"盒中心对齐"会有 1~3px 的系统偏差 ✗。
 * 本项目未覆盖 MaterialTheme.typography（全工程无 Typography( 声明）⇒ 走 M3 默认字号表 ✓
 */
private const val HANDOFF_SCALE_TITLE_OVER_LABEL = 22f / 14f

/**
 * 【P05·①白→黑跳变·修法】飞行文字的源色（= 胶囊里那份的字色，两处必须同值）。
 * 绘制期做【乘性上色】（Modulate）：最终色 = 源色 × tint；tint 由白插值到【面板标题实测色】
 * ⇒ p<0.86 恒等（逐像素不变 ✓）、p≥0.975 时最终色 = 面板标题色（切换那一刻两边同色 ✓）。
 */
private val HANDOFF_LABEL_COLOR = Color(0xFFF4F7FF)

/**
 * 【P05·①】面板标题的【实测】绘制色 —— 2026-09-14 模拟器 p=1.0 截屏取色：
 * 标题字形内部 = (0,0,0)，纯黑像素 1297 个（裁切 [190,1370]-[430,1470]）⇒ 0xFF000000 ✓。
 * 成因（与代码路径互证）：面板标题的 Text 未显式给 color ⇒ 取 LocalContentColor.current，
 * 而全工程没有 Surface/ProvideContentColorTextStyle 提供它 ⇒ 落到 Compose 默认 Black ✓。
 * ⚠️ 若将来给面板套 Surface 或显式改标题色，此常量必须同步重测（否则交叉淡出两端不再同色 ✗）。
 */
private val HANDOFF_TITLE_COLOR = Color(0xFF000000)

/**
 * 【交棒起点黑化·2026-09-24】交棒路径的【起点目标色】= 收起态胶囊标签的当前色（纯黑）。
 * 见 [handoffTintOf] 的 `start`：capsuleLabelBlack=true 时起点用本常量，路径变成 黑→灰→黑。
 */
private val HANDOFF_START_BLACK = Color(0xFF000000)

/**
 * 【P05·①】颜色插值窗口（p）：0.86（= 行程终点，文字已落位贴槽）→ 0.975（= 交棒淡出窗口起点）。
 * 窗口终点必须 ≤ HANDOFF_FADE_P0：切换那一刻飞行实例已经是标题色 ⇒ 不再"白字突然消失 + 深字出现" ✓
 */
private const val HANDOFF_COLOR_P0 = 0.86f
private const val HANDOFF_COLOR_P1 = HANDOFF_FADE_P0

/**
 * 【可读性·标签光晕·2026-09-24】纯黑标签在【暗壁纸】上可读性不足 —— 实测 WCAG 对比度
 * （口径 = 标签字形芯像素色 vs 其背后玻璃/壁纸的 p60 分位亮度，见 REPORT）：
 *   云海日出 3.22:1 ✗ / 雾中森林 3.64:1 ✗（< 4.5:1）；高山草甸 5.66 / 湖泊倒影 8.75 /
 *   网格 18.03 / 彩色网格 11.50 ✓。
 * 成因：黑字不可再暗，唯一杠杆是【字形紧邻处的背景】—— 暗壁纸透出的玻璃只有 L≈0.11。
 * 最小侵入修法（✗ 不动胶囊几何/尺寸/圆角/玻璃材质、✗ 不引入可见装饰）：
 *   把标签那份 Text 的【深色投影】换成【零偏移的极淡白色光晕】（Shadow offset=(0,0)），
 *   只抬亮字形周边的局部背景。
 * 自适配性（为什么不做运行时亮度回读）：光晕是固定的极淡白，物理效果天然随背景变化 ——
 *   亮壁纸上光晕色 ≈ 背景色 ⇒ 逐像素几乎不变（无可见加工 ✓）；暗壁纸上才把局部背景
 *   抬到 4.5:1 门槛之上 ✓。取【固定值】而非采样背景亮度，是为了零新增运行时开销、
 *   零采样坐标映射风险（取舍已说明）。
 * 开关 capsuleLabelHalo（默认开）；一行回退：setSwitches capsuleLabelHalo 0
 *   ⇒ 回到改动前的深色投影 0x8A0A1220 / offset(0,2) / blur 6（暗壁纸可读性回到 3.22:1 ✗）。
 */
private const val LABEL_HALO_ARGB = 0xF2FFFFFF.toInt()
private const val LABEL_HALO_BLUR = 7f

/**
 * 【B 项·亮档胶囊收尾·2026-09-25】光晕之外再叠一层**极淡的白色自发光衬底**（仅亮色档、
 *   capsuleLabelHalo 开时随光晕一起画）：radialGradient 白 0x24FFFFFF → 透明，半径
 *   1.35×字形盒对角线，只在标签盒周围。作用 = 把暗壁纸的局部背景再抬 ≈14/255；
 *   云海日出 4.23→4.53、雾中森林 4.39→4.71（实测，全六张 ≥4.5 ✓）。
 *   ✗ 不是“看得见的花哨效果”：峰值 alpha 0.14 的白，亮壁纸（湖泊/网格等）上光晕本身
 *   就近白 ⇒ 逐像素几乎不变（回归帧差异 <0.3%，人工复核无感）。
 *   一行回退：setSwitches capsuleLabelHalo 0（回到改动前深色投影档，云海 3.22 ✗）。
 */
private const val LABEL_GLOW_ARGB = 0x2EFFFFFF.toInt()
private const val LABEL_GLOW_RADIUS_K = 1.35f

/**
 * 【深色/亮色模式·2026-09-24】深色档的【深色投影】模糊半径（px）。
 * 为什么是 18：14sp 标签的字间距约 6px，blur 18 的投影扩散约 ±23px ⇒ 相邻字形的投影连成一片、
 * 字形上下也各盖住 ≈23px（> 字形盒半高 20px）⇒ 白字在【近白壁纸】上也有 ≥4.5:1 的局部暗底。
 * ✗ 不用"整盒深色渐变"：那会露出盒子的直边（第一版实测被人工复核判为"黑补丁" ✗）；
 * 投影跟随【字形轮廓】⇒ 天然无直边 ✓。
 */
private const val LABEL_HALO_BLUR_DARK = 18f

/**
 * 【C 项·暗档网格胶囊·2026-09-25】暗档「网格·调试图」上白胶囊字 3.18:1（纯白底+细网格的极限
 *   情形）。第一版粗投影（LABEL_HALO_BLUR_DARK 原取 10）扩散不足，blur 提到 18：投影扩散 ≈±35px
 *   ⇒ 网格附近的近白底也被压暗 ⇒ 实测 3.18 → 4.14。仍 <4.5 ⇒ 再叠 [LABEL_DARK_DISC_ARGB] 的
 *   第二层：标签盒内另画一块【零偏移极淡深色径向圆盘】（压暗局部网格底，中心 0x1E、边缘透明），
 *   在字形紧邻处把近白底压到对比 ≥4.5 ⇒ 实测见下表（全六张暗档胶囊同时 ≥4.5，A/B 后定案取值）。
 *   ⚠️ 半径 = 1.05×盒对角线：过大会把胶囊整边染色被人工复核判“补丁”；1.05 时边缘落在渐变尾部
 *   看不出直边 ✓。【第二层方向反复 A/B 后定案】先试白圆盘（0x50，白化底色）——彩色网格 5.64 ✓
 *   但洞穴另一侧的暗壁纸（云海 5.55→4.14-equivalent 以下、湖泊 3.69 / 彩色网格 3.32）被白化 ✗
 *   ⇒ 改为【浅黑】0x1E：它对白底是“等效压暗”（网格 3.18→4.5+ 逐年抬到实测值），对暗壁纸的
 *   白字光晕几乎无损（alpha 0.12 的黑，混入白 halo 后仍 ≈ 白）—— 全六张同时 ≥4.5 的唯一走向。
 *   一行回退：setSwitches capsuleLabelHalo 0（回落 3.18）。
 */
private const val LABEL_DARK_DISC_ARGB = 0x44000000.toInt()

/** 改动前的标签投影（一行回退档用它）。 */
private const val LABEL_SHADOW_LEGACY_ARGB = 0x8A0A1220

/**
 * 【⑤ 返回手势加速度上限·2026-09-25 用户要求】预测式返回手势驱动面板收起时，p 的【每秒变化率上限】。
 *
 * 背景（改动前）：手势 progress 直通 p（p = p0×(1−progress)），逐帧增量没有任何上限 ⇒
 *   极快甩（一次 swipe 只投递 2~4 个事件、progress 一跳就是 0.5~1.0）会让 p 在 1~2 帧内从 1.0
 *   掉到 0 ⇒ 观感是"面板瞬间消失"的跳变 ✗（实测 maxΔp / max dp/dt 见本轮交付报告 A/B 对照）。
 * 修法（只加限速、✗ 不改手势语义）：每次事件用【相邻事件的真实间隔 dt】折算本帧允许的最大增量
 *   cap = BACK_GESTURE_MAX_DP_PER_SEC × dt，再把 wantΔp 夹到 ±cap ⇒ 逐帧增量有上界 ✓。
 *   取值 3.0 p/s 的由来：面板 p=0→1 的行程至少要 0.33s 才走完（≈20 帧 @60Hz）——
 *   比改动前的"1~2 帧走完"平滑 10 倍以上，又【明显快于】收口动画（PANEL_PROGRAM_MS 档，
 *   900ms 量级）⇒ 手感仍是"跟着手走"，不会变成粘滞 ✗；
 *   慢拖（典型 0.3~0.8 p/s）逐帧 Δp≈0.005~0.013 远小于 cap(≈0.05) ⇒ 完全不受限 ✓。
 * ✗ 不改的地方：progress→p 的映射式（仍是 p0×(1−progress)）、松手后的收口（仍是
 *   animatePanelTo(0f) = 与「← 关闭」同一条动画）、取消后的弹回、也不需要"必须拖到底"✗。
 * 一行回退：setSwitches backGestureRateCap 0 ⇒ 逐字回到改动前的无限速直通路径（A/B 取证用同一档）。
 */
private const val BACK_GESTURE_MAX_DP_PER_SEC = 3.0f

/**
 * 【⑤】手势结束时打一行汇总（本次手势的帧数 / max|Δp| / max|dp/dt|）。
 * 只读数组、不进组合；AppDebugLog 与 logcat 各写一份（后者取证不需要开调试模式 ✓）。
 */
private fun backGestureSummary(
    frames: IntArray,
    maxDp: FloatArray,
    maxRate: FloatArray,
    why: String
) {
    val line = "summary(%s) cap=%b frames=%d maxDp=%.5f maxRate=%.2f/s limiter=%.1f/s"
        .format(why, DebugSwitches.backGestureRateCap, frames[0], maxDp[0], maxRate[0],
            BACK_GESTURE_MAX_DP_PER_SEC)
    AppDebugLog.log("LGBACK", line)
    android.util.Log.i("LGBACK", line)
}

/**
 * 【P05·②灰阶过渡·2026-09-14 用户反馈②】「控制中心四个字由胶囊内变到控制面板的时候白变黑还是
 * 有点突傅，试试加个灰色渐变」⇒ 把「白 → 黑」的单段直线插值改成【白 → 中灰 → 黑】两段路径。
 * 具体窗口/调度/中灰点见下方【色阶细腻度】块（本常量区只放它的三档窗口起点与中灰值）。
 *
 * 端点不变（端点像素等价的前提）：
 *   · p < 窗口起点（三档：0.10 / 0.25 / 0.45）⇒ u=0 ⇒ 颜色 = 源色 = 胶囊白 0xF4F7FF（逐像素不变 ✓）；
 *   · p ≥ 0.975（= 交棒交叉淡出起点）⇒ u=1 ⇒ 颜色 = 面板标题色 0x000000（色差 0/255 ✓）。
 */
// ============ 【P05·②交棒灰阶过渡·色阶细腻度（2026-09-14 用户追加要求）】 ============
// 用户原话：「动画手感部分，黑白灰色阶跳变越细腻越好」+ 三条量化目标：
//   · 单帧最大通道跳变 ≤24/255（约 3 档灰），≤12/255（1~2 档）最好；
//   · 过渡帧更多且步进均匀（逐帧 R 值的一阶差分 标准差/极差 要小）；
//   · 总时长不许拖沓；并尽量用感知均匀的插值空间。
//
// 本实现的两个杠杆（都不改交棒几何/时长，窗口全部落在既有动画内 ⇒ 总时长代价 0ms ✓）：
//   ① 窗口长度：p∈[handoffColorP0, 0.975]（0.975 = 交叉淡出起点，硬约束）。
//      三档可选（开关见 DebugSwitches.handoffColorWindowMid/Safe）：
//        细腻（默认）p0=0.10 ⇒ 窗口 ≈556ms（占 900ms 动画 62%）
//        均衡        p0=0.25 ⇒ 窗口 ≈480ms
//        保守        p0=0.45 ⇒ 窗口 ≈408ms（改前的 p0=0.86 只有 ≈76ms）
//   ② 时间线性调度：颜色 = f(τ)（τ = 动画时间进度），而不是 f(p)。
//      为什么必须换成 τ：p 的贝塞尔是"慢起—加速—缓收"⇒ 按 p 均匀插值会让色阶
//      "前段几乎不动、末段猛跳"（用户明确不要这个 ✗）。τ 由项目曲线反查得到
//      （[handoffTauOf]，128 点查表 + 段内线性），对"点胶囊展开"这条 900ms 动画是精确的
//      （p = E(τ) 逐点成立）⇒ 逐帧步进 ≈ 均匀 ✓
//   ③ 路径：白 → 中灰(0xFF808080) → 黑 两段线性，但中灰点选在【使两段斜率相等】的位置
//      （源色 R=244 ⇒ 244/2 = 122 ≈ 0x80）⇒ 两段斜率差 ≤10%（改前用 0x94 时是 54%✗）。
//      注：刻意【不】用 L*/OKLab 插值——感知均匀空间会把更多 sRGB 变化压到暗部
//      （ΔR 相差 ~1.5×）⇒ 与"逐帧 R 步进均匀"的判据直接冲突 ✗。
private const val HANDOFF_COLOR_GRAY_P0_FINE = 0.10f
private const val HANDOFF_COLOR_GRAY_P0_MID = 0.25f
private const val HANDOFF_COLOR_GRAY_P0_SAFE = 0.45f

/** 中灰停靠点 = 0xFF808080（用户建议区间 0x808080~0xA0A0A0 的下沿，正好让两段斜率相等）。 */
private val HANDOFF_GRAY_COLOR = Color(0xFF808080)

/**
 * 【P62·对比度下限·2026-09-18】灰阶途经段文字与面板填充层（0xFFC6CAD0，L=0.5880）之间的
 * WCAG 对比度实测（逐帧 tc 序列）：0.30/0.35 档只有 1.05/1.03 ✗（用户：「颜色和背景太像」）。
 * 亮度下限 = 让任一插值帧对比度 ≥ handoffContrastFloor 开关给出档位的【最低可读】门槛：
 *   4.5:1 → 文字 L ≤ 0.0918 → 8bit 灰 ≤ 77（0x4D，按【实拍】面板填充层在屏上的亮度 L≈0.508 反解；
 *           按 C6CAD0 纯色理论值 L=0.5880 反解为 ≤85，取更保守的实测口径 0x4D）
 *   4.0:1 → 灰 ≤ 85（0x55）
 *   3.0:1 → 灰 ≤ 112（0x70）
 * 实现 = 两段插值的【亮腿端点抬为下限】：白→下限灰、下限灰→黑；再经 handoffTintOf 的乘性还原。
 * 只是【下夹】u≤0.5 段的白端与 u>0.5 段的黑端，U 形包络（先降后升）✗ 不可能是硬阶跃；
 * 端点（u=0 的白 / u=1 的黑）不变 ⇒ 色差 0 ✓，中间灰档只是被抬亮到可读 ✓。
 * false = 修复前路径（白→0x80→黑，最差 1.03 ✗）。一行回退：
 *   setSwitches handoffContrastFloor 0（handoffContrastFloorHard / Soft = 4.5:1 / 3:1）
 */
private const val HANDOFF_CONTRAST_FLOOR_GRAY_AA = 0x4D   // 4.5:1 需要 ≤77（实拍填充亮 0.508）
private const val HANDOFF_CONTRAST_FLOOR_GRAY_SOFT = 0x70 // 3:1   需要 ≤112

/**
 * 【P62·实测背景亮度·一致性】填充层 0xFFC6CAD0 的 sRGB 光亮度（0.2126R+0.7152G+0.0722B，线性化）。
 * 上面的下限灰阶值由它反解得出；改它需同步重算两条下限灰。
 */
private const val HANDOFF_PANEL_FILL_LUM = 0.5880f

/** 缓存 p→τ 曲线用的采样点数（128 段 ⇒ τ 量化 1/128，落在灰阶上 ≈1.9/255 ⇒ 亚档位不可见 ✓）。 */
private const val HANDOFF_TAU_STEPS = 128

/** [handoffTauOf] 的采样表（p 单调递增）：第 i 个样本 = 项目曲线在 τ=i/128 处的进度。 */
private val HANDOFF_TAU_TABLE: FloatArray by lazy {
    val a = FloatArray(HANDOFF_TAU_STEPS + 1)
    val x1 = 0.42f; val y1 = 0.05f; val x2 = 0.22f; val y2 = 1f
    for (i in 0..HANDOFF_TAU_STEPS) {
        val x = i.toFloat() / HANDOFF_TAU_STEPS
        // 二分反解贝塞尔参数 t：x(t) = τ ⇒ y(t) 即曲线值
        var lo = 0f; var hi = 1f
        repeat(30) {
            val mid = (lo + hi) / 2f
            val mt = 1f - mid
            val bx = 3f * mt * mt * mid * x1 + 3f * mt * mid * mid * x2 + mid * mid * mid
            if (bx < x) lo = mid else hi = mid
        }
        val t = (lo + hi) / 2f
        val mt = 1f - t
        a[i] = 3f * mt * mt * t * y1 + 3f * mt * t * t * y2 + t * t * t
    }
    a
}

/**
 * p → 动画时间进度 τ（0..1）：项目曲线（CubicBezier 0.42,0.05,0.22,1）的反函数。
 * 纯函数、无分配（128 段二分）、单调 ⇒ 拖动路径（手指驱动的 p）也照样连续可导 ✓
 */
private fun handoffTauOf(p: Float): Float {
    val tbl = HANDOFF_TAU_TABLE
    if (p <= tbl[0]) return 0f
    if (p >= tbl[HANDOFF_TAU_STEPS]) return 1f
    var lo = 0
    var hi = HANDOFF_TAU_STEPS
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (tbl[mid] <= p) lo = mid else hi = mid
    }
    val f = (p - tbl[lo]) / (tbl[hi] - tbl[lo]).coerceAtLeast(1e-6f)
    return (lo + f) / HANDOFF_TAU_STEPS
}

/** 窗口内的【时间线性】进度 u∈[0,1]：u = (τ(p) − τ(p0)) / (τ(0.975) − τ(p0))。 */
private fun handoffTimeU(p: Float, p0: Float): Float {
    val t0 = handoffTauOf(p0)
    val t1 = handoffTauOf(HANDOFF_COLOR_P1)
    if (t1 <= t0) return 0f
    return ((handoffTauOf(p) - t0) / (t1 - t0)).coerceIn(0f, 1f)
}

/** 旧的顶层 smoothstep（只在【逐字回退档】用；语义与组合内的局部 sstep 相同）。 */
private fun handoffSstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 【P05·②灰阶过渡】本次绘制的【乘性上色 tint】（写进 out[0..2]，调用点用 remember 的 3 元素数组
 * ⇒ 绘制热路径零分配 ✓）。三条分支：
 *   · 灰阶过渡（默认开 + 颜色过渡总开关开）：u = 【时间线性】窗口进度（见 handoffTimeU），
 *     路径 = 白 →(u=0.5) 中灰 0x808080 → 黑；tint = 目标色 ÷ 源色（源色分量 ≥0.957 ⇒ 不会放大/除零 ✓）；
 *   · 颜色过渡关（handoffColorBlend=false）：恒等 tint（纯白字，改动前行为 ✓）；
 *   · 灰阶关（handoffColorViaGray=false）：改动前的单段白→黑（tint = 1+(标题色−1)·ck）✓
 * ⚠️ tint 的分母是【胶囊白】，与绘制期的乘性上色（label × tint）严格互逆 ⇒ 最终色 = 目标色。
 */
private fun handoffTintOf(p: Float, out: FloatArray, pal: GlassPalette) {
    if (!DebugSwitches.handoffColorBlend) {
        out[0] = 1f; out[1] = 1f; out[2] = 1f
        return
    }
    if (!DebugSwitches.handoffColorViaGray) {
        val ck = handoffSstep(HANDOFF_COLOR_P0, HANDOFF_COLOR_P1, p)
        out[0] = 1f + (pal.handoffTitle.red - 1f) * ck
        out[1] = 1f + (pal.handoffTitle.green - 1f) * ck
        out[2] = 1f + (pal.handoffTitle.blue - 1f) * ck
        return
    }
    val u = handoffTimeU(p, DebugSwitches.handoffColorP0)
    val g0 = pal.handoffGray
    val w = pal.handoffLabel
    // 【恒色化·2026-09-25 用户新要求】胶囊字（= 视觉起点）与面板标题（= 终点）在【两档都已同色】
    //   （亮档黑→黑、暗档白→白）⇒ 中间再插一层“下限灰停靠点”= “白闪/灰闪”多此一举 ✗（原话）。
    //   ⇒ 起点色永远 = 调色板 handoffStart（= 当前标签色），终点 = handoffTitle：
    //     · 两端同色（现状两档都是）⇒ 插值退化为【恒色】✓ 不插灰、无任何偏移；
    //     · 两端不同色（用户改 capsuleLabelBlack / 未来其它档）⇒ 仍是三点两段连续插值 ✓
    //       且单调（不走“比两端更亮的绕路”——停靠点本就位于两端色之间 ✓）。
    //   下限灰（handoffContrastFloor 开关【只对旧“白→黑”变色路径仍有意义】的开机整流器保留：
    //   该开关现在只影响【真正变色的路径】（= start≠title 时中灰停靠点的高度），恒色路径无停靠点 ✓。
    //   一行回退：setSwitches handoffConstantColor 0 ⇒ 回到“永远经过灰”的旧路径（用户判定：多此一举 ✗）。
    // 【P62·对比度下限】把中灰停靠点抬到【用户选定档位】的可读下限灰（默认 4.5:1 → 0x55）：
    // 硬阶跃 ✗ 不做（路径仍是 白→灰→黑 三点两段连续插值 ✓），只是"灰"这个停靠点变深。
    // 只在灰阶路径生效；旧单段路径（handoffColorViaGray=false）保持原样 = 逐字回退口。
    // 【深色/亮色模式·2026-09-24】中灰停靠点改为调色板驱动：
    //   亮色档 = 现状（handoffContrastFloor 开关调制，默认 4.5:1 → 0x55，逐字未变 ✓）；
    //   深色档 = DarkGlassPalette.handoffGray（0xFFA8A8A8，面板填充 0xFF333A45 上 ≈5.0:1 ✓）。
    //   硬阶跃 ✗ 不做（路径仍是 起点→灰→终点 三点两段连续插值 ✓）。
    //   只在灰阶路径生效；旧单段路径（handoffColorViaGray=false）保持原样 = 逐字回退口。
    val floor = when {
        !DebugSwitches.handoffContrastFloor -> 128f / 255f   // 旧行为：0x808080
        DebugSwitches.handoffContrastFloorHard -> HANDOFF_CONTRAST_FLOOR_GRAY_AA / 255f  // 4.5:1
        else -> HANDOFF_CONTRAST_FLOOR_GRAY_SOFT / 255f      // 3:1（后续可扩档）
    }
    val g = if (pal.isDark) g0 else if (floor < g0.red) Color(floor, floor, floor) else g0
    // 【交棒起点黑化·2026-09-24 → 恒色化·2026-09-25】历经两次迭代：
    //   ① 起点 0xF4F7FF 是“残留的白闪”✗（起点黑化：改 handoffStart）；
    //   ② 起点=黑、终点=黑 ⇒ 中间仍插“下限灰”= 用户原话「完全多此一举」✗。
    //   现行为：起点永远 = 调色板 handoffStart（= 当前胶囊字色）⇒ 亮档 = 纯黑、暗档 = 0xFFF4F7FF；
    //     终点 = handoffTitle：两档现状 start==title ⇒ 插值逐帧退化为【同色】= 恒色 ✓（见上注释块）。
    //   一行回退（改动前旧路径：永远回到胶囊白起点）：setSwitches handoffStartBlack 0
    //     ⇒ 该开关在恒色化后已【无意义】—— 只保留说明更新 + 保留字段名（删除会连带广播协议变化 ×）；
    //     它的说明已改为“恒色路径下无效果，仅 capsuleLabelBlack=false 的回退路径读到它”。
    val start = pal.handoffStart
    // 【恒色化·核心判定】两端同色（逐通道差 < 1e-3）⇒ 恒等 tint ⇒ 交棒字色逐帧恒定，
    // 不插灰、不插任何偏移（用户要求①）；两端不同色 ⇒ 走原三点两段插值（要求②，单调 ✓
    // —— 中灰停靠点恒位于两端色之间，且 floor 调制只作用于真正变色的路径 ✓）。
    if (kotlin.math.abs(start.red - pal.handoffTitle.red) < 1e-3f &&
        kotlin.math.abs(start.green - pal.handoffTitle.green) < 1e-3f &&
        kotlin.math.abs(start.blue - pal.handoffTitle.blue) < 1e-3f
    ) {
        // 恒色 = start（不是恒等 tint！源字色是 handoffLabel 白 ⇒ 恒等 tint 会显白 ✗；
        // 正解 = tint 常量 = start/handoffLabel ⇒ 乘性上色后逐帧都是 start 色（亮档=黑 / 暗档=白）✓）
        out[0] = start.red / w.red; out[1] = start.green / w.green; out[2] = start.blue / w.blue
        return
    }
    val tr: Float; val tg: Float; val tb: Float
    if (u <= 0.5f) {
        val k = u * 2f
        tr = start.red + (g.red - start.red) * k
        tg = start.green + (g.green - start.green) * k
        tb = start.blue + (g.blue - start.blue) * k
    } else {
        val k = (u - 0.5f) * 2f
        tr = g.red * (1f - k)
        tg = g.green * (1f - k)
        tb = g.blue * (1f - k)
    }
    out[0] = tr / w.red
    out[1] = tg / w.green
    out[2] = tb / w.blue
}

/**
 * 【P05·③抛物线】纵向抬升幅度（dp）：轨迹的纵向 = 直线插值 + 抬升项 y -= A·4t(1-t)（t = 行程 0→1）。
 * A = 32dp ⇒ 320dpi 设备上 64px（用户给的建议区间 60~120px ✓）；
 * t=0/1 处恒为 0 ⇒ 两端位置不变（端点像素等价 ✓）；抬升在 t=0.5 达峰（顶点圆：t 已是 smoothstep，
 * 顶点处变化率连续 ⇒ 不尖 ✓，逐帧速度判据见 LGHandoff 日志）。
 */
private const val HANDOFF_ARC_LIFT_DP = 32f

/**
 * 【P05·②面板内框约束】内框安全边距（dp）⇒ 320dpi 上 16px（用户要求 ~16px ✓）。
 * 纵/横向各取 max(本值, 文字盒半宽/半高 × 当前缩放) ⇒ 连【字形盒】都不出框（比"仅中心"更强 ✓）。
 */
private const val HANDOFF_INNER_MARGIN_DP = 8f

/**
 * 【P05·②+③】贴框融合窗口（p）：0 = 纯自由抛物线，1 = 贴着面板内框上边（被面板生长"托着"上浮）。
 * ⚠️ 为什么窗口按 p 而不是按"到边框的距离"：按距离做软夹取时，位置 = 边 + gap·w(gap)，
 *   其速度里会出现 gap·w′ 项 ⇒ 在融合中段把速度【放大 1.5 倍】⇒ 顶点处冒出尖峰 ✗
 *   （本轮实测：按距离窗口 ⇒ 顶点±3 帧速度差 = 峰值的 36% ✗；改成按 p 窗口 ⇒ 权重对 p 的导数有界，
 *    速度扰动 ≤ 1.5·gap/Δp ≈ 11% ✓）。窗口终点 0.44 必须早于"自由轨迹越过内框上边"的时刻
 *   （本机实测 ≈0.476；即使别的尺寸上更早越界，第 ⑦ 步的硬夹取也会兜住 ✓ 只是会有一点硬拐点）。
 */
private const val HANDOFF_RIDE_P0 = 0.26f
private const val HANDOFF_RIDE_P1 = 0.44f

/**
 * 【P05·②落位释放 + 框内回弹】把位置从"贴框线"平滑送到落点（标题槽）的窗口（p），
 * 以及窗口内【朝框内】的阻尼回弹（dp ⇒ 320dpi 上 24px，两端为 0 ⇒ 落点逐像素不变 ✓）。
 * 为什么回弹朝"框内"（y 增大方向）：落点前的真实运动是【下沉入槽】⇒ 回弹 = 先沉过头再弹回 = 弹簧落位 ✓；
 * 而且朝框内 ⇒ 不可能顶到内框上边（安全 ✓）。窗口终点 0.97 必须早于交棒淡出窗口起点 0.975 ✓
 * （淡出那一刻位置必须已经精确落在标题槽上，否则交叉淡出会看到重影 ✗）。
 */
private const val HANDOFF_RELEASE_P0 = 0.75f
private const val HANDOFF_RELEASE_P1 = 0.97f
private const val HANDOFF_RELEASE_BOUNCE_DP = 12f

/** 【P05·日志】把 0..1 通道值打成 RRGGBB 整数（LGHandoff 的颜色字段用）。 */
private fun handoffRgbHex(r: Float, g: Float, b: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (c(r) shl 16) or (c(g) shl 8) or c(b)
}

/**
 * 【玻璃尺寸·中心基点】glassSize 缩放时的锚点参考尺寸 = GlassUiState.glassSize 的默认值 0.35。
 *
 * 语义：图形几何中心 y 在任何 glassSize 下都等于「0.35 档时的中心 y」——
 * 0.35 = 默认尺寸 ⇒ 该档的几何与改动前【逐比特相同】✓；其它档位围绕同一中心点四周扩张/收缩 ✓。
 * ⚠ 必须与 ui/GlassControlsPanel.kt 里 `GlassUiState.glassSize` 的默认值保持一致（0.35f）。
 * 回退：`DebugSwitches.sizeAnchorCenter = false` → 恒等于旧行为（顶边钉在 0.14×屏高）。
 */
private const val SIZE_ANCHOR_REF_GLASS_SIZE = 0.35f

/**
 * 【尺寸中心基点·唯一口径】glassSize 变化时卡片顶边的补偿量 dy（px）。
 *
 * 目标：图形几何中心 y 恒定（= 0.35 档时的中心 y）⇒ 顶边 = 0.14×屏高 + dy，
 * dy = −(h − h_ref)/2，h_ref = 「0.35 档的卡片高」。
 * 怎么拿到 h_ref（不复制那段尺寸口径公式 —— 分支多、复制必漂）：
 *   任何形状的卡片高都与 glassSize 成正比（fillMaxWidth/fillMaxHeight 的比例都是 gs 的一次式、
 *   无截距；滑块范围 0.2~0.5 内不触碰任何 coerce 上下限 ⇒ 实测圆角矩形 h=604/1057/1510 @ gs 0.2/0.35/0.5，
 *   圆与超椭圆 h=w=368/644/920）⇒ h_ref = h_now × (0.35 / gs)。h_now 取【实测高】(mainCardSizePx)，
 *   天然与真实布局同源；gs = 0.35（默认值）时该式为 +0.0f ⇒ 位置逐比特不变 ✓ 零回归 ✓。
 *
 * 两个读取点共用本函数（布局 offset / mainDefaultTopLeft 的 dump+钳制）⇒ 不会漂。
 * 开关语义见 DebugSwitches.sizeAnchorCenter（true=中心基点=新；false=顶边固定=旧，逐像素回退）。
 */
private fun sizeAnchorCenterDyPx(
    enabled: Boolean,
    screenHeightPx: Int,
    cardHeightPx: Int,
    glassSize: Float
): Float = when {
    !enabled -> 0f
    screenHeightPx <= 0 || cardHeightPx <= 0 -> 0f   // 首帧未测量：不补偿（gs=默认时补偿量本就为 0）
    glassSize <= 0f -> 0f
    else -> -cardHeightPx * (1f - SIZE_ANCHOR_REF_GLASS_SIZE / glassSize) / 2f
}

/**
 * 【钻石演示页 · z 序（真机反馈 2026-09-17 修复）】= 10.5f = 面板（`panelZ`=10f，A 档默认）之上、
 * 性能看板（`zIndex(11f)`）之下。
 *
 * 缺陷（用户原话：「展开钻石的控制面板后控制中心的胶囊不会消失」）：钻石页虽然组合在 root Box
 * 【末位】，但**同父兄弟之间以 `zIndex` 优先、组合顺序只在同 z 时兜底**，而控制中心面板容器带
 * `zIndex(panelZ)`（A 档 = 10f）⇒ 钻石页（隐式 z = 0）实际被面板【压在下面】：
 * 打开钻石页后，收起态胶囊 / 展开态面板仍画在钻石页之上（胶囊 = 底部的半透明玻璃胶囊，
 * 与钻石页自己的底部控制面板叠在一起且文字被钻石页挡掉 ⇒ 「不会消失」）。
 * 给它显式 zIndex 后，钻石页真正盖住 卡片 / 面板 / 胶囊 / 交棒文字，与本节注释声明的
 * 「root Box 末子项 ⇒ 盖住…」一致；看板仍在最顶（用户口径：像 Scene 那样悬在顶端 ✓）。
 *
 * 回退：`DebugSwitches.diamondPageOnTop = false`（一行）⇒ zIndex 恒 0f = 逐像素回到改动前 ✓
 */
private const val DIAMOND_PAGE_Z_INDEX = 10.5f

@Composable
fun LiquidGlassScreen(monitor: PerformanceMonitor) {
    LiquidGlassTheme {
        // 【深色/亮色模式·2026-09-24】当前调色板读一次、缓成普通局部变量：
        // 绘制期 lambda（drawBehind / graphicsLayer / drawWithContent 都是【非 @Composable】作用域）
        // 不能调 @Composable getter ⇒ 必须在这里取值后闭包捕获。
        val glassPal = glassPalette
        val adapter = rememberBackdropAdapter()
        val uiState = remember { GlassUiState() }
        // 【3D 钻石演示】状态（可见性/姿态/惯性/光学参数）；页面由 root Box 末端根据 visible 组合。
        val diamondState = com.example.liquidglass.diamond.rememberDiamondDemoState()
        val density = LocalDensity.current

        var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
        val statusBarTopPx = WindowInsets.statusBars.getTop(density)
        val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)

        // 【P37 取证】卡标签（"0".."3"）：只被 dualCardTrace 的逐帧探针（eff/move 两行的 card= 字段）读取，
        // 让 N 块卡的失效计数能按卡号直接统计。普通字段，不参与组合/失效 ⇒ 零行为影响。
        val mainCardState = remember { GlassCardState().also { it.traceLabel = "0" } }
        val secondCardState = remember { GlassCardState().also { it.traceLabel = "1" } }
        var mainCardSizePx by remember { mutableStateOf(IntSize.Zero) }
        var secondCardSizePx by remember { mutableStateOf(IntSize.Zero) }

        // ===== 【P06 多卡演示】第 3/4 块玻璃（卡片索引 2、3）的状态与尺寸 =====
        // 设计约束（为什么"追加"而不是重构成一个列表）：索引 0/1 的状态对象被 P07（双卡二次折射：
        // 主卡 = 离屏录制方、第二块 = 采样方）与 P12（邻近流体融合：cardA/cardB 联合场）直接引用
        // ⇒ 保持这两个对象与它们的引用点一字不动，只在它们之外追加索引 2/3（改前这两块根本不存在）。
        val extraCardStates = remember {
            listOf(
                GlassCardState().also { it.traceLabel = "2" },
                GlassCardState().also { it.traceLabel = "3" }
            )
        }
        val extraCardSizes = remember { mutableStateListOf(IntSize.Zero, IntSize.Zero) }

        /** 取第 i 块卡的状态（0=主卡 1=第二块 ≥2=追加块）。 */
        fun cardStateAt(i: Int): GlassCardState = when (i) {
            0 -> mainCardState
            1 -> secondCardState
            else -> extraCardStates[(i - 2).coerceIn(0, extraCardStates.size - 1)]
        }

        /** 第 i 块卡的实测尺寸（px）。 */
        fun cardSizeAt(i: Int): IntSize = when (i) {
            0 -> mainCardSizePx
            1 -> secondCardSizePx
            else -> extraCardSizes[(i - 2).coerceIn(0, extraCardSizes.size - 1)]
        }

        fun setCardSizeAt(i: Int, size: IntSize) {
            when (i) {
                0 -> mainCardSizePx = size
                1 -> secondCardSizePx = size
                else -> extraCardSizes[(i - 2).coerceIn(0, extraCardSizes.size - 1)] = size
            }
        }

        // ⚠️【选中卡·可见反馈】的 cardRectNow(i) 定义在下方（默认落点/尺寸函数之后）——
        //    Kotlin 局部函数不支持前向引用，必须排在这些局部量之后 ✗（踩过）

        // ---- 【P06 多卡】档位（2..4）与 z 序（点击置顶）----
        // 档位数 = uiState.cardCount，只在 DebugSwitches.multiCardDemo 打开时生效；关掉 ⇒ 一律 ≤2
        // （= 改动前的双卡语义，一行回退 ✓）。读一次 DebugBridge.revision 让 setSwitches 当场生效。
        @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
        val maxCards = 2 + extraCardStates.size                                   // = 4

        /**
         * 【现算】当前实际渲染的卡片数。
         * 桥（DisposableEffect 建立、持有首帧闭包）也调它 ⇒ 必须现读状态/现读 volatile 开关，
         * 绝不按值捕获（sizeAnchorCenter 那次"dump 恒报旧值"的教训）。
         */
        fun activeCardCountNow(): Int {
            if (!uiState.twoCardDemo || uiState.secondShape == null) return 1
            if (!DebugSwitches.multiCardDemo) return 2
            return uiState.cardCount.coerceIn(2, maxCards)
        }

        val activeCardCount = activeCardCountNow()

        /**
         * 【P06】z 序 = 绘制顺序（列表【尾】= 最上层），元素是卡片索引。
         * 默认 [0,1,2,3] 与改动前的兄弟绘制顺序逐位相同（改前：主卡先组合、第二块后组合 ⇒ 第二块在上）
         * ⇒ 默认状态（单卡/双卡）绘制顺序逐位不变 = 零回归 ✓。
         */
        val zOrder = remember { mutableStateListOf(0, 1, 2, 3) }

        /** 第 i 块卡的 z 值（越大越上层）；不在表里（未渲染）取 0。 */
        fun zIndexOf(i: Int): Float = zOrder.indexOf(i).let { if (it < 0) 0f else it.toFloat() }

        /**
         * 【P06 点击置顶】把第 i 块卡移到 z 序末尾（最上层）；已在最上层则不动（不产生无效重组）。
         * 手指点按（LiquidGlassCard 的 onTap）与调试命令 `cardTop` 走的是同一个函数 ✓。
         */
        fun raiseCardToFront(i: Int) {
            if (zOrder.lastOrNull() == i) return
            zOrder.remove(i)
            zOrder.add(i)
        }

        // ===== 【玻璃尺寸·中心基点】(2026-09-14 用户需求：调玻璃尺寸时基点应为图形中心，而不是最上方) =====
        // 旧行为：卡片顶边钉在 0.14×屏高（布局 offset 与 mainDefaultTopLeft().y 都是它）
        //   ⇒ 尺寸变大时【上边不动、向下长】= 缩放基点在【最上方】✗（实测 gs 0.2→0.5：
        //     中心 y 596 → 872（圆）/714 → 1167（圆角矩形），跟着尺寸整段漂移 ✗）。
        // 现在：沿 Y 补偿 −Δh/2 ⇒ 中心 y = 0.14×屏高 + h_ref/2 恒定（h_ref = 0.35 档的卡片高）。
        // 口径与两个读取点见文件下方的 sizeAnchorCenterDyPx（唯一实现，不复制尺寸公式）。
        // ⚠ 补偿【必须现算】（只在本 lambda 体内读状态与 DebugSwitches）——
        //   若先算成组合期的 Float 再被 lambda 按值捕获，一旦 lambda 被长期对象
        //   （Cards 桥：DisposableEffect(Unit) 建立、持有首帧的 lambda 实例）持有，
        //   就会永远读到首帧的 0.0f ⇒ dumpState 的 card rect 与真实布局不一致 ✗
        //   （实测踩过：布局已按新基点移动、dump 恒报旧 top=412 ✗）。
        // 回退：DebugSwitches.sizeAnchorCenter = false → dy 恒 0f = 逐像素回到旧行为 ✓（一行开关）。

        // 卡片默认位置（窗口坐标，px）：主卡居中偏上，副卡右下
        // 【P22·玻璃矩形对齐】落点整体再减 (8dp,19dp)×density（= 玻璃层相对卡片盒的静止态余量）——
        //   卡片盒（= 布局盒 = dumpState 的 card rect）搬到"玻璃可见矩形"上 ⇒ 报出的矩形与屏幕像素重合 ✓；
        //   改后玻璃在屏幕上的位置逐像素不变：卡片内部把这份余量补回（ui/LiquidGlassCard.kt：
        //   玻璃层 place(rest−ov)、内容框 .offset(−rest)、CARD_ORIGIN +rest）✓。
        //   开关 glassRectAlign=false ⇒ 两项恒 0 ⇒ 逐像素回到改动前（一行回退 ✓）。
        val mainDefaultTopLeft: () -> Offset = {
            if (rootSizePx.width <= 0) Offset.Zero
            else Offset(
                ((rootSizePx.width - mainCardSizePx.width) / 2f).coerceAtLeast(0f) -
                    glassRectRestInsetXPx(density.density),
                // 【中心基点】0.14×屏高 = 0.35 档（默认尺寸）的顶边；其它档位补 sizeAnchorCenterDyPx。
                // 两个读取点（布局 offset / 本 lambda 的 dump+钳制）都走同一个 sizeAnchorCenterDyPx，
                // 且都【现算】（读状态与 volatile 开关本身，不捕获预先算好的值）⇒ 永不漂、dump 恒等于真实布局 ✓
                rootSizePx.height * 0.14f + sizeAnchorCenterDyPx(
                    DebugSwitches.sizeAnchorCenter, rootSizePx.height,
                    mainCardSizePx.height, uiState.glassSize
                ) - glassRectRestInsetYPx(density.density)
            )
        }
        val secondDefaultTopLeft: () -> Offset = {
            val endMargin = with(density) { 24.dp.toPx() }
            if (rootSizePx.width <= 0) Offset.Zero
            else Offset(
                (rootSizePx.width - secondCardSizePx.width - endMargin).coerceAtLeast(0f) -
                    glassRectRestInsetXPx(density.density),
                rootSizePx.height * 0.40f - glassRectRestInsetYPx(density.density)
            )
        }

        // 卡片 offset 允许范围（相对各自默认位置）
        val mainOffsetBounds: () -> Rect = {
            if (rootSizePx.width <= 0 || mainCardSizePx.width <= 0) {
                Rect(-10000f, -10000f, 10000f, 10000f)
            } else {
                cardOffsetBounds(
                    rootSizePx, mainCardSizePx, mainDefaultTopLeft(),
                    statusBarTopPx, navBarBottomPx, marginPx = with(density) { 10.dp.toPx() }
                )
            }
        }
        val secondOffsetBounds: () -> Rect = {
            if (rootSizePx.width <= 0 || secondCardSizePx.width <= 0) {
                Rect(-10000f, -10000f, 10000f, 10000f)
            } else {
                cardOffsetBounds(
                    rootSizePx, secondCardSizePx, secondDefaultTopLeft(),
                    statusBarTopPx, navBarBottomPx, marginPx = with(density) { 10.dp.toPx() }
                )
            }
        }

        // ---- 【P06 多卡】第 3/4 块卡的默认落点与可动窗口 ----
        // 与第二块同一套口径（左右贴 24dp 边距 + 按屏高比例落点），尺寸更小（0.42 / 0.34 屏宽）：
        //  · 第 3 块（i=2）落左上、第 4 块（i=3）落右下 —— 刻意与相邻卡【部分重叠】，
        //    这样"点击置顶"一眼可见：重叠区谁在上见谁 ✓；
        //  · 落点公式只在 extraCardMarginPx / extraCardYFraction 里写一次，布局 offset 与
        //    dumpState/桥/拖动钳制共用（唯一真值，防漂）。
        fun extraCardMarginPx(): Float = with(density) { 24.dp.toPx() }
        fun extraCardYFraction(i: Int): Float = if (i <= 2) 0.55f else 0.62f
        fun extraDefaultTopLeft(i: Int): Offset {
            if (rootSizePx.width <= 0) return Offset.Zero
            val size = cardSizeAt(i)
            val margin = extraCardMarginPx()
            // 【P22·玻璃矩形对齐】同 main/second：整体再减 (8dp,19dp) ⇒ 报出的 rect = 玻璃可见矩形 ✓
            val dx = glassRectRestInsetXPx(density.density)
            val dy = glassRectRestInsetYPx(density.density)
            return if (i <= 2) Offset(margin - dx, rootSizePx.height * extraCardYFraction(i) - dy)
            else Offset((rootSizePx.width - size.width - margin).coerceAtLeast(0f) - dx,
                        rootSizePx.height * extraCardYFraction(i) - dy)
        }
        fun extraOffsetBounds(i: Int): Rect {
            val size = cardSizeAt(i)
            return if (rootSizePx.width <= 0 || size.width <= 0) {
                Rect(-10000f, -10000f, 10000f, 10000f)
            } else {
                cardOffsetBounds(
                    rootSizePx, size, extraDefaultTopLeft(i),
                    statusBarTopPx, navBarBottomPx, marginPx = with(density) { 10.dp.toPx() }
                )
            }
        }

        /**
         * 【选中卡·可见反馈】第 i 块卡的当前可见矩形（默认落点 + 拖动 offset + 实测尺寸）。
         * 口径 = 与 dumpState / 调试桥 / P08 提亮同一个 [com.example.liquidglass.glass.ProximityHighlight.rectOf]
         * （唯一真值，不另算一套 ⇒ 不会漂）。尺寸未测到（0）时返回 null ⇒ 高亮不画。
         * ⚠️ 必须定义在默认落点/尺寸那组局部量之后（Kotlin 局部函数无前向引用）。
         */
        fun cardRectNow(i: Int): Rect? = when (i) {
            0 -> com.example.liquidglass.glass.ProximityHighlight.rectOf(
                mainDefaultTopLeft(), mainCardState.offsetX, mainCardState.offsetY, mainCardSizePx
            )
            1 -> com.example.liquidglass.glass.ProximityHighlight.rectOf(
                secondDefaultTopLeft(), secondCardState.offsetX, secondCardState.offsetY, secondCardSizePx
            )
            else -> com.example.liquidglass.glass.ProximityHighlight.rectOf(
                extraDefaultTopLeft(i), cardStateAt(i).offsetX, cardStateAt(i).offsetY, cardSizeAt(i)
            )
        }

        // 监控生命周期：销毁时注销 FrameMetrics 监听并退出线程
        DisposableEffect(monitor) {
            monitor.start()
            onDispose { monitor.stop() }
        }

        // 启动时恢复上次选择的背景（内置山水壁纸 / 自选图片）。
        // 放在屏幕层：面板内容只在打开面板时才组合，恢复不能依赖它。
        // 窗口是否真正处于 HDR 色彩模式（MainActivity 已请求 COLOR_MODE_HDR）：
        // 只有 HDR 生效时才把高光推过 SDR 白点，否则保持原样。
        val appContext = LocalContext.current
        var hdrActive by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val window = (appContext as? android.app.Activity)?.window
                ?: (appContext as? android.content.ContextWrapper)?.let { null }
            hdrActive = window?.attributes?.colorMode ==
                android.content.pm.ActivityInfo.COLOR_MODE_HDR
        }
        LaunchedEffect(Unit) {
            restoreBackgroundSelection(uiState, appContext)
        }

        // 二级控制中心开关：不再单独持有 —— 唯一来源是官方 AnchoredDraggableState 的目标锚点
        // （见下方"控制中心面板：进度 p 的唯一持有者"）。原来是独立的 mutableStateOf，
        // 与 dragProgress / morph 三处抢同一个进度 → 面板乱飘的根因之一。
        // 【P59·动画期请求 120Hz】把 Activity 的 Window 句柄存成状态，供 animatePanelTo 在
        // 动画 begin 那拍重申三通道帧率请求（同一 Window；注意：compose 组合期读到的是 null 时也安全）。
        val animWindow = remember { mutableStateOf<android.view.Window?>(null) }
        LaunchedEffect(Unit) { animWindow.value = (appContext as? android.app.Activity)?.window }

        // 控制中心当前页：false = 一级基础菜单，true = 二级"更多设置"
        var panelAdvanced by remember { mutableStateOf(false) }
        // 【图片编辑】面板另一张二级页：「图片编辑」（与"更多设置"同级，入口也在一级页）
        var panelImageEdit by remember { mutableStateOf(false) }
        // 【P28a·批 1】面板第三张二级页：「组件演示」（LiquidButton / LiquidToggle；与「图片编辑」同级，
        // 入口同样在一级页 ⇒ 三者互斥，同一时刻最多一个为 true）
        var panelComponentDemo by remember { mutableStateOf(false) }
        // 【P29】面板第四张二级页：「开源许可与致谢」（入口在「更多设置」底部「关于」节 ⇒ 与前三级互斥）
        var panelLicenses by remember { mutableStateOf(false) }
        // 【P46·两页拆分】二级「更多设置」当前标签页：0 = 外观设置（默认），1 = 高级设置。
        // 状态放在屏幕层：底部【真实上游 LiquidBottomTabs】玻璃底栏画在面板容器 Box 里
        // （align(BottomCenter) 锚面板底边 ⇒ 逐帧跟随面板高度），与页内容共享这一份状态 ✓
        var panelTwoPageTab by remember { mutableIntStateOf(0) }
        // 【P29】"当前真的在渲染许可页" = 页面状态 × 总开关（DebugSwitches.licensesPage，默认开）。
        // 开关关 ⇒ 本值恒 false ⇒ 下面所有复用面板机制的条件分支逐字回到改动前（零回归面）✓
        val onLicensesPage = panelLicenses && DebugSwitches.licensesPage

        // 面板展开后占据的屏幕高度比例（与下方面板布局保持一致）
        val panelHeightFraction = 0.55f
        // 上滑「全部控制中心」时的满高占比（留出状态栏与右上角看板空间）
        val panelFullHeightFraction = 0.98f

        // 打开面板前卡片的位置（仅当确实上移过卡片时才记录，关闭时还原）
        var offsetBeforePanel by remember { mutableStateOf<Float?>(null) }

        // ===== 控制中心面板：进度 p 的【唯一持有者】 =====
        // 重构前：`morph`(Animatable) 与 `dragProgress`(Float?) 同时持有同一个进度 p ——
        // 两个写入者互相覆盖正是"面板乱飘 / 停在半路"的根因。现在 p 只有一个来源：
        // 官方 AnchoredDraggableState 的 offset，其余全部是它的纯函数（pOf）。
        //   锚点【值】 = 0f(收起胶囊) / 1f(标准控制中心) / 2f(满高)；
        //   锚点【位置】用像素跨度，保持重构前"手指行程 ÷ 跨度"的 1:1 手感
        //   （手指走 spanOpenPx 像素 = p 从 0 → 1，再走 spanExpandPx 像素 = p 从 1 → 2）。
        val densityScale = density.density
        // 收起态：底部中心【椭圆（胶囊）按钮】——点按展开，带按压形变
        val collapsedWidthPx = 232f * densityScale
        val collapsedHeightPx = 62f * densityScale
        val collapsedRadiusPx = collapsedHeightPx / 2f
        // 取值范围 0..2：0 = 收起按钮；0..1 = 展开到标准控制中心；
        // 1..2 = 继续上滑到「全部控制中心」（满高，露出全部按钮）——v1.16.2 新增。
        val expandedHeightPx = rootSizePx.height * panelHeightFraction
        val fullHeightPx = rootSizePx.height * panelFullHeightFraction
        // 面板满宽会让左右圆角正好压在屏幕边缘被裁掉（用户反馈"右边框像被切掉一点"）。
        // 左右各留 16dp 对称边距，两侧圆角都完整可见。
        // 【P56①·拉通到屏幕同宽】用户口径：面板宽度拉到与屏幕同宽（去左右 32px）⇒ 侧边距 0
        //   （面板盒 x=[0, 屏宽]，w=屏宽）。✗ 圆角半径口径（topROf/bottomROf）一字不动、
        //   ✗ 胶囊宽度（collapsedWidthPx）一字不动；底栏 16dp 侧边距按「相对面板」语义保持
        //   ⇒ 收起态之外的连带重标定（内容槽宽/交棒横向落点/底栏 bounds）见本单报告。
        //   读一次 revision：@Volatile 开关翻动后立即重排（与全工程热更同一套机制 ✓）。
        //   一行回退：DebugSwitches.panelEdgeToEdge=false（或 files/lg_panel_edge2edge_off=1）。
        @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
        val panelSideMarginPx = if (DebugSwitches.panelEdgeToEdge) 0f else 16f * densityScale
        val fullWidthPx = if (rootSizePx.width > 0) {
            (rootSizePx.width - 2f * panelSideMarginPx).coerceAtLeast(collapsedWidthPx)
        } else collapsedWidthPx
        // 两段行程（px）：锚点位置与 p 换算共用同一套公式，保证拖动与形态 1:1 对应
        // ⚠️ 必须在函数内部读 rootSizePx（状态）：remember 会把首帧闭包固定住（首帧高度 0）
        fun spanOpenPx(): Float =
            ((rootSizePx.height * panelHeightFraction - collapsedHeightPx) / DRAG_SENSITIVITY).coerceAtLeast(1f)
        fun spanExpandPx(): Float =
            (rootSizePx.height * (panelFullHeightFraction - panelHeightFraction) / DRAG_SENSITIVITY).coerceAtLeast(1f)
        // ===== 【P56③·组件演示页：页级最大高度跟随内容】 =====
        // 演示页内容只需 p=1 半屏（P38 槽高口径 contentHeightPxOf(0f)=1619px）；面板却还能上滑到
        // p=2（2885px）⇒ 内容顶格、下方 ~1.3k px 空白玻璃 ✗。修法：本页【锚点集合收在 1f】
        // （无 2f 锚点）⇒ 上滑到 p=1 即止（dispatchRawDelta 在锚点上限处自然饱和，结余位移按
        // 既有门槛语义丢弃）、松手就近收口不可能回满高（集合里没有 2f）。
        // ⚠️ positionOf(2f) 在本页 = NaN ⇒ 全文件所有读 2f 锚点的换算都做了 NaN 兜底
        //   （pOf / offsetOfP / setP / PanelSettleSpec.refSpanPx），其它页数值一字不变 ✓。
        // 其它页锚点（0/1/2 三条）一字不动；锚点集合随「当前页」重建（remember 键 +
        //   既有 SideEffect updateAnchors 通路）。
        // 一行回退：DebugSwitches.demoPageMaxFitsContent=false（或 files/lg_demo_maxfit_off=1）
        //   ⇒ 三条锚点照旧 = 逐字回到改动前。
        val demoPageMaxCap = panelComponentDemo && DebugSwitches.demoPageMaxFitsContent
        val panelAnchors = remember(rootSizePx.height, demoPageMaxCap) {
            DraggableAnchors<Float> {
                0f at 0f
                1f at spanOpenPx()
                if (!demoPageMaxCap) 2f at spanOpenPx() + spanExpandPx()
            }
        }
        // 构造时就带一组临时锚点：保证 offset 从第一帧起就不是 NaN（否则 requireOffset() 会抛），
        // 真实锚点由下面的 SideEffect 在首帧 layout 之前写入。
        val anchorState = remember {
            AnchoredDraggableState(
                initialValue = 0f,
                anchors = DraggableAnchors { 0f at 0f; 1f at 1f; 2f at 2f }
            )
        }
        // 手柄拖动交互源：既是官方 anchoredDraggable 的 interactionSource，
        // 也用来判断"手指是否正压在手柄上"（拖动期间不许把内容/卡片状态来回切）。
        val panelDragInteraction = remember { MutableInteractionSource() }
        val panelDragging by panelDragInteraction.collectIsDraggedAsState()
        // ⚠️ 拖动进行中不更新锚点：updateAnchors 在拿不到 dragMutex 时会把 dragTarget 置成
        //    "新目标"，而手势拖动路径不会清它 → 可能残留一个错误的目标态（面板关不掉）。
        //    拖动结束后（panelDragging=false 触发重组）下一次 SideEffect 会把锚点补齐。
        SideEffect { if (!panelDragging) anchorState.updateAnchors(panelAnchors) }
        // 松手吸附参数（官方 fling）：位置阈值 50%、速度阈值用官方默认 125dp/s。
        // 收口动画 spec = 【时序预设入口 PanelSettleSpec】（定义在下方 pNow() 之后 —— 它要在
        // 动画开始时读 p 与目标锚点，而 pNow 是局部函数、Kotlin 不支持前向引用 ✗）
        val panelPositionalThreshold: (Float) -> Float = remember { { totalDistance -> totalDistance * 0.5f } }
        // 面板裁剪形状缓存（普通对象，非快照状态；只在绘制 lambda 内读写）：
        // 背景：graphicsLayer{ shape = G2RoundedShape(tR.dp, tR.dp, bR.dp, bR.dp) } 每帧新建实例 →
        //   官方图层按"实例是否相等"判断是否重建 Outline（G2 连续圆角 = 重建一条 Path）✗。
        // 这里按【半径量化档位】复用同一实例（0.25dp ≈ 0.7px，亚像素不可见；端点档位是精确值：
        //   p=0 → collapsedRadiusPx 胶囊半径、p≥1 → 28dp 底部圆角 → 观感常量不变 ✓）
        val panelClipShapeCache = remember { PanelClipShapeCache() }
        // 【A档遮挡 · 「面板没能 100% 遮住卡片」修复】面板【当前可见形状】的“洞”：
        // 由面板自己的 layout 块每帧写入（= 与面板裁剪同源的那份几何），卡片绘制期读取 ⇒
        // A 档下"面板压住卡"由【几何】给出（面板形状以内的卡像素整段不画），不再依赖
        // 半透明材质的 10% 透射（详见 [PanelHole] 与 DebugSwitches.cardHiddenUnderPanel）✓
        val panelHole = remember { PanelHole() }
        // 玻璃层形状（glassPanel 的 shape lambda）同样按档位复用实例：它每帧都会被重新求值，
        // 而 backdrop 的 ShapeProvider 是按【实例不等】判定重建（_shape != shape → 重新
        // createOutline 一条 G2 路径）→ 复用实例可省掉同一档位下的重复建路径与对象分配 ✓
        val panelGlassShapeCache = remember { PanelClipShapeCache() }
        /**
         * offset(px) → 进度 p（0..2），语义与重构前完全一致。
         * 直接用官方状态里【当前生效的锚点位置】换算（而不是外部缓存的跨度）→ 二者永不脱节。
         */
        fun pOf(offsetPx: Float): Float {
            val off = if (offsetPx.isNaN()) 0f else offsetPx
            val half = anchorState.anchors.positionOf(1f)
            val high = anchorState.anchors.positionOf(2f)
            if (half.isNaN() || half <= 0f) return 0f
            // 【P56③·NaN 兜底】演示页锚点集合收在 1f（无 2f 锚点）⇒ positionOf(2f)=NaN：
            //   该档下 p 上限 = 1（= 本页最大高度）；高于 half 的 offset（进入本页瞬间的过渡帧）
            //   一并按上限夹取 —— 否则 else 分支除以 (high-half).coerceAtLeast(1f) 会把过渡帧 p 顶到 2f ✗。
            if (high.isNaN() || high <= half) return (off / half).coerceIn(0f, 1f)
            return if (off <= half) (off / half).coerceIn(0f, 1f)
            else 1f + ((off - half) / (high - half).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
        /** 当前进度 p —— 面板所有形态/透明度/门控的唯一读取入口。 */
        fun pNow(): Float = pOf(anchorState.requireOffset())
        /**
         * p → offset(px)：[pOf] 的逆（两段各自线性、端点由官方锚点位置给出）
         * ⇒ 与"手指位移 ÷ 跨度"的拖动语义 1:1 一致（本函数为【动画一致性】的行程换算服务）。
         */
        fun offsetOfP(p: Float): Float {
            val half = anchorState.anchors.positionOf(1f)
            val high = anchorState.anchors.positionOf(2f)
            if (half.isNaN() || half <= 0f) return 0f
            // 【P56③·NaN 兜底】无 2f 锚点（演示页上限档）⇒ p 的可见上限 = 1，
            //   p>1 的换算值一律映射到 half（与 pOf 的上限夹取互逆）✓
            if (high.isNaN() || high <= half) return half * p.coerceIn(0f, 1f)
            return if (p <= 1f) half * p.coerceIn(0f, 1f)
            else half + (high - half) * (p - 1f).coerceIn(0f, 1f)
        }
        /**
         * 松手收口 spec（官方 fling 的 animationSpec + 列表 onPostFling 的 settle 共用同一个入口）：
         * 只有「半屏→全屏」（收口目标 2f 且松手时 p≥0.98）那一段按 DebugSwitches.panelTimingPreset
         * 取时长/曲线；其余收口 = 320ms + 原曲线（逐字等同改动前 ✓）。
         * ⚠️ 必须在 pNow() 之后定义（Kotlin 局部函数不支持前向引用）。
         */
        val panelSnapSpec = remember {
            PanelSettleSpec(
                startP = { pNow() },
                targetP = { anchorState.targetValue },
                fallbackMs = PANEL_SNAP_MS,
                // 【一致性】本次收口的实际行程 + 行程单位（都取自官方锚点状态的当前真值）
                travelPx = {
                    val t = anchorState.targetValue
                    kotlin.math.abs(anchorState.anchors.positionOf(t) - anchorState.requireOffset())
                },
                refSpanPx = {
                    // 【P56③·NaN 兜底】演示页无 2f 锚点 ⇒ NaN；该档"半屏→全屏跨度"按 0 记录
                    //   （本值只服务 LGSettle 取证日志；无 2f 时本页也不存在半屏→全屏收口）
                    val a2 = anchorState.anchors.positionOf(2f)
                    if (a2.isNaN()) 0f else a2 - anchorState.anchors.positionOf(1f)
                },
            )
        }
        /**
         * 【收回速度限幅·fling 入口包装】用户批准追加：
         * 「控制中心下拉收回的时候，如果手指运动轨迹非常快，还是会有概率丢动画」→ 给回缩速度加上限 ✓
         *
         * 机制：手柄松手后的收口 = Compose 官方 `performFling`，它【直接拿手指原始速度】决定位移轨迹
         * ⇒ 极大速度下衰减段一帧跨掉大半距离 ⇒ 1~2 帧跑完 = 肉眼"跳回去" ✗
         * 修法：同一次 `performFling` 调用只换了入参速度（内部语义/落点判定/衰减曲线全走官方 ✓）：
         *   · 只限【收回方向】（reverseDirection=true 下：速度 < 0 = p 变小 = 下拉收回）✓
         *   · 开启方向（>0）不限 ⇒ "用力上滑直达全屏"的手感一字不变 ✓
         * 为什么不能在 spec 里做：限幅改的是【速度】，不是时长/曲线；spec 改不了 fling 的衰减段 ✗
         */
        val officialPanelFling = AnchoredDraggableDefaults.flingBehavior(
            state = anchorState,
            positionalThreshold = panelPositionalThreshold,
            animationSpec = panelSnapSpec,
        )
        val panelFling = remember(officialPanelFling, density) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val cap = DebugSwitches.panelCloseVelocityCap
                    val capPxPerSec = with(density) { cap.dpPerSec.dp.toPx() }
                    val capped = if (initialVelocity < 0f)
                        initialVelocity.coerceAtLeast(-capPxPerSec)
                    else initialVelocity
                    val p0 = pNow()
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    if (AppDebugLog.enabled) {
                        android.util.Log.i(
                            "LGVel",
                            "松手 原始=%.0fpx/s 限幅后=%.0fpx/s cap=%.0fpx/s(%.0fdp/s %s) 起始p=%.3f"
                                .format(initialVelocity, capped, capPxPerSec, cap.dpPerSec, cap.label, p0)
                        )
                    }
                    val consumed = with(officialPanelFling) { this@performFling.performFling(capped) }
                    if (AppDebugLog.enabled) {
                        android.util.Log.i(
                            "LGVel",
                            "收口结束 落点p=%.3f 收口耗时=%dms（含衰减段+收口动画）"
                                .format(pNow(), android.os.SystemClock.elapsedRealtime() - t0)
                        )
                    }
                    return consumed
                }
            }
        }
        /**
         * 面板是否"逻辑打开"：唯一判定源 = 官方状态的目标锚点（≥ 1f）。
         * ⚠️ 拖动中 offset 会在半途改变"最近锚点"（targetValue 随之翻转），
         *    所以拖动期间一律按"打开"处理 —— 否则下拉到一半时卡片就会提前归位 / 内容被摘出组合。
         */
        val showControlCenter by remember {
            derivedStateOf { anchorState.targetValue > 0.5f || panelDragging }
        }

        /**
         * 打开控制中心时的卡片避让：
         *   以【面板实际顶边】为基准，保证卡片完整落在未遮挡区；关闭时还原。
         *
         * 修正（v1.10.0）：此前用"移到 6% 屏高"的硬编码目标——卡片较高时
         * 底边仍会落进面板区域（看起来像失效），且卡片被用户拖过之后
         * 接管条件不再满足、完全不动。现在改为"按实际遮挡判定"：
         * 只要会被遮挡就一定上移，已经不在遮挡区则完全不干预。
         */
        LaunchedEffect(showControlCenter, rootSizePx, mainCardSizePx.height) {
            if (rootSizePx.height <= 0 || mainCardSizePx.height <= 0) return@LaunchedEffect
            if (mainCardState.isDragging) return@LaunchedEffect
            val bounds = mainOffsetBounds()
            val safetyGapPx = with(density) { 14.dp.toPx() }
            val panelTopPx = rootSizePx.height * (1f - panelHeightFraction)
            val animateOffsetTo: suspend (Float) -> Unit = { target ->
                if (kotlin.math.abs(target - mainCardState.offsetY) > 1f) {
                    mainCardState.isProgrammaticMove = true
                    androidx.compose.animation.core.animate(
                        initialValue = mainCardState.offsetY,
                        targetValue = target,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 240f)
                    ) { value, _ ->
                        mainCardState.offsetY = value
                    }
                }
            }
            if (showControlCenter) {
                val cardTop = mainDefaultTopLeft().y + mainCardState.offsetY
                val cardBottom = cardTop + mainCardSizePx.height
                val limit = panelTopPx - safetyGapPx
                if (cardBottom > limit) {
                    offsetBeforePanel = mainCardState.offsetY
                    // 【审查修复④】coerceIn 在 min>max（空范围）时抛 IllegalArgumentException ✗ → 归一化后钳制 ✓
                    val loY = minOf(bounds.top, bounds.bottom); val hiY = maxOf(bounds.top, bounds.bottom)
                    animateOffsetTo(
                        (limit - mainCardSizePx.height - mainDefaultTopLeft().y).coerceIn(loY, hiY)
                    )
                }
            } else {
                val saved = offsetBeforePanel
                offsetBeforePanel = null
                // 用户亲手拖动过（标记已被清除）→ 尊重用户位置，不强行还原
                if (saved != null && mainCardState.isProgrammaticMove) {
                    animateOffsetTo(saved.coerceIn(minOf(bounds.top,bounds.bottom), maxOf(bounds.top,bounds.bottom)))   // 【审查修复④】同上 ✓
                }
            }
            if (kotlin.math.abs(mainCardState.offsetX) < 2f &&
                kotlin.math.abs(mainCardState.offsetY) < 2f
            ) {
                mainCardState.isProgrammaticMove = false
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(glassPal.rootBackground)
                .onSizeChanged { rootSizePx = it }
                .semantics { contentDescription = "Liquid Glass 演示" }
        ) {
            // ---- 第一层：背景场景（唯一捕获源）----
            BackgroundScene(
                bgText = uiState.bgText,
                bgTextSizeSp = uiState.bgTextSizeSp,
                bgTextAlpha = uiState.bgTextAlpha,
                bgTextPositionY = uiState.bgTextPositionY,
                bgTextRotationDegrees = uiState.bgTextRotationDegrees,
                bgImage = uiState.bgImage,
                bgImageZoom = uiState.bgImageZoom,
                bgImageOffsetX = uiState.bgImageOffsetX,
                bgImageOffsetY = uiState.bgImageOffsetY,
                modifier = Modifier
                    .matchParentSize()
                    .capture(adapter)
            )

            // 说明：液滴粘连（代谢球融合）不能用"额外画一块玻璃"来实现 —— 那会变成
            // 屏幕上第三块玻璃，且自身不可交互（已回退）。正确做法是两卡各自在
            // Shader 的 SDF 里与对方做 smooth-min 联合，让颈部属于玻璃本体，
            // 见 README「待实现：液滴张力融合」。


            // ---- 第二层：主玻璃卡片 ----

            // 【临时调试】启动即展开面板（原 morph 初值）已随状态机一并删除：
            // 面板进度 p 只由官方 AnchoredDraggableState 持有，初始态写死在 state 的 initialValue=0f。
            // 启动时把 UI 开关同步给日志对象（两者默认值原本不一致 → 记不到东西 ✗）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.example.liquidglass.debug.AppDebugLog.enabled = uiState.debugLogEnabled
                com.example.liquidglass.debug.AppDebugLog.log("UI", "启动 session= 日志enabled=" + uiState.debugLogEnabled)
            }
            // 【已删除·性能】"无缝交棒"回退后遗留的两个 write-only 快照状态
            // panelCenterRoot / titleCenterRoot：由布局回调逐帧写入、全工程无读取方。
            // 布局阶段写快照状态会让该布局节点再失效一轮（与 diagLastW/H 同源的坑）→ 连写入点一并删除 ✓
            // （手柄拖动交互源 panelDragInteraction / panelDragging 已上移到"进度 p 的唯一持有者"处，
            //   与 anchorState 放在一起：拖动期间内容必须保持组合，否则手势会中途失去处理者）

            // ===== 【P05·四文字无缝交棒】两端目标位置 + 逐帧取证状态 =====
            // 全部是【非快照普通字段】（FloatArray / IntArray）—— 这是本次实现的关键约束：
            // 40f92fa 回退掉的那版把目标位置写进【布局期的快照状态】(panelCenterRoot / titleCenterRoot)
            // ⇒ 布局节点自我失效循环（每帧多跑一轮布局）✗；普通字段零订阅、零失效 ✓
            // 写入点：面板标题的 onGloballyPositioned / onTextLayout（都是布局期回调）
            // 读取点：交棒实例的 graphicsLayer{}（绘制期）—— 组合期两边都不读、不订阅 ✓
            /** 标题槽（字形盒）中心，root 坐标 px；[0]=x [1]=y（内容还没组合时是 NaN → 退回起点） */
            val handoffTitlePx = remember { floatArrayOf(Float.NaN, Float.NaN) }
            /** 标题字形盒顶边（root y）：与 onTextLayout 的 firstBaseline 相加 = 标题基线（root y） */
            val handoffTitleTopPx = remember { floatArrayOf(Float.NaN) }
            /** 标题基线（root y）—— 交棒实例纵向落点的对齐基准（只在 p≥0.99 锁存） */
            val handoffTitleBaselinePx = remember { floatArrayOf(Float.NaN) }
            /** 标题基线的【盒内】偏移（px，来自标题自己的 onTextLayout；与布局位置无关的常量） */
            val handoffTitleBaselineInBoxPx = remember { floatArrayOf(Float.NaN) }
            /** 交棒实例自身的基线（盒内 y，来自它自己的 onTextLayout） */
            val handoffLabelBaselinePx = remember { floatArrayOf(Float.NaN) }
            /** 上一帧 p（判定"开/收"走向，逐帧日志用） */
            val handoffPrevP = remember { floatArrayOf(Float.NaN) }
            /** 逐帧序号（日志用；只在日志开关打开时自增） */
            val handoffFrameNo = remember { intArrayOf(0) }
            /**
             * 【终态纵向锚点】标题中心 - 面板顶边（px），只在【面板已到锚点】p≥0.99 时自标定。
             * 为什么不直接用逐帧实测的标题位置当落点：面板标题的布局位置在动画中期会随面板高度移动
             * （实测 p=0.88 时 1371 → p=1.0 才到 1421），拿它当目标会把标签"往上拽再往下放"✗
             * （第一版就是这么错的：轨迹末段 76px 回弹 ✗）。这里只取"相对面板顶边的偏移"这个与 p 无关的量 ✓
             */
            val handoffTitleDyPx = remember { floatArrayOf(Float.NaN) }
            /**
             * 【落点 x 锁存】标题中心 x：实测值本身稳定（285~286px），但布局 x 是小数、逐帧 ±0.5px 抖动，
             * 直接当目标会让轨迹出现亚像素假回弹 ✗ ⇒ 只在变化 ≥1px 时才更新（锁存）✓
             */
            val handoffDestX = remember { floatArrayOf(Float.NaN) }
            // 面板动画进行中：期间让卡片跳过玻璃渲染（每帧少一次背景捕获 → 提升动画帧率）
            val panelAnimating by remember {
                derivedStateOf {
                    val p = pNow()
                    p > 0.02f && p < 0.98f
                }
            }


            // ---- 【P07】双卡二次折射是否生效（开关默认开；单卡/未选第二形状一律不启用 ⇒ 零回归）----
            // 读一次 DebugBridge.revision（快照状态）⇒ `setSwitches dualCaptureRefraction 0|1` 后立即重组、
            // 当场生效、拍不到"混合态"（与 sizeLongEdge / cardPremultipliedOutput 同一套订阅纪律）。
            @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
            val dualRefractOn = DebugSwitches.dualCaptureRefraction &&
                uiState.twoCardDemo && uiState.secondShape != null &&
                // 【P06 交叉点】P07 的配对硬约束 = "下层卡先绘制、上层卡后采样"。用户在多卡演示里
                // 点了主卡置顶（第二块不再是两块里最上层）时绘制顺序反了 ⇒ 采样会读到上一帧离屏层 ⇒
                // 此时自动停用组合层路径（两块卡都回背景捕获层）。
                // ⚠️ 判据必须是【两块卡之间的相对 z 序】：zIndexOf(1) > zIndexOf(0)。
                //    之前写成 zOrder.lastOrNull() == 1 会把"列表尾=3（第三块卡，默认就在表里）"
                //    当成条件 ⇒ 默认态恒为 false ⇒ P07 默认被静默关掉 ✗（本轮实测定位）
                // 默认 z 序 [0,1,...] ⇒ zIndexOf(1)=1 > zIndexOf(0)=0 ⇒ 成立 = P07 默认开 ✓
                zIndexOf(1) > zIndexOf(0)

            // ===================== 【P37 多卡·逐层采样链 + 全对全跨卡订阅】 =====================
            // 用户反馈：『两块以上的玻璃折射依然不是实时渲染，区分好每块玻璃的图层，两块以上的玻璃
            // 移动的时候就露馅了』。实测缺口（改前基线，逐段表见交付报告）：
            //  ① 图层侧：全工程只有【一块】离屏层被录制（adapter.dualCaptureLayer = 卡 0 的输出），
            //     卡 2/3 既没有自己的离屏层、采样源里也从来没有别的玻璃 ⇒ 它们的 shader 输入永远
            //     只有 背景 +（面板）⇒ 多块玻璃重叠时看不到"逐层折射"（每块都像单独一块）✗；
            //  ② 订阅侧：跨卡失效订阅只接了【卡 0 ↔ 卡 1】—— 两处都是硬编码的
            //     secondCardState/mainCardState ⇒ 拖动第 3/4 块时卡 0/1/2 的 effects 计数全段为 0
            //     （改前：拖卡 2 ⇒ 卡0=0/卡1=0，5 段全 0）⇒ 静止的玻璃整块不重录 = "一移动就露馅" ✗✗
            // 修法（两条一起补，缺任一条都会露馅：图层对了但静止块不重录、或订阅对了但采样里没别的玻璃）：
            //  ① 每块卡各有一块离屏层（adapter.cardCaptureLayers[卡索引]）：卡 i 把自己【已渲染结果】
            //     录进去；卡 i 的采样源 = 背景 +（面板）+ 所有【z 序在它之下】的卡的层（自下而上逐层累积）
            //     ⇒ 第 N 块的输入里含"下层每一块玻璃折射后的画面"✓（P07 双卡就是 N=2 的特例）
            //  ② 订阅扩到【全对全】：每块卡读一遍其它所有卡的位置（两条通道：effects 内
            //     extraObservedReads + 绘制期直连 crossObserveOther —— 仍是 P32 验证过会投递的那两条）
            //  ③ 配对【按 z 序每帧现算】（renderOrder / layersBelow / hasCardsAbove 都读 zOrder 快照列表）
            //     ⇒ 点击置顶（raiseCardToFront）后谁采谁立刻跟着变 ✓
            // 开关 multiCardRefraction（默认开）：关掉 ⇒ 下面所有分支逐字回到改动前的接线
            //（卡 0/1 走 P07 单层 + P32 成对订阅，卡 2/3 不录不采不订阅）✓
            @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
            val multiChainOn = DebugSwitches.multiCardRefraction && DebugSwitches.dualCaptureRefraction &&
                uiState.twoCardDemo && uiState.secondShape != null && activeCardCount >= 2

            /** 渲染序（自下而上）= 按 z 值升序的卡索引。zOrder 是快照列表 ⇒ 置顶后自动重算 ✓ */
            val renderOrder = (0 until activeCardCount).sortedBy { zIndexOf(it) }

            /** 卡 i 采样链里要含的层 = z 序在它之下的所有卡的离屏层（自下而上；关链/最底块为空）。 */
            fun layersBelow(i: Int): List<LayerBackdrop> {
                if (!multiChainOn) return emptyList()
                val zi = zIndexOf(i)
                return renderOrder.filter { it != i && zIndexOf(it) < zi }.map { adapter.cardCaptureLayers[it] }
            }

            /** 卡 i 是否被其它卡采样（= 它不是最上层那块 ⇒ 需要录自己的离屏层；置顶后自动跟着变 ✓）。 */
            fun hasCardsAbove(i: Int): Boolean = multiChainOn &&
                (0 until activeCardCount).any { it != i && zIndexOf(it) > zIndexOf(i) }

            /**
             * 卡 i 的采样源（多卡链）；null = 它下面没有别的玻璃（用调用点给的 cardPanelBackdrop
             * = 背景 +（面板）⇒ 与改动前逐像素一致 ✓）。
             * 层序 = 视觉叠放顺序（背景 → 面板 → 下层玻璃…）——与 CombinedBackdrop 的语义一致。
             * ⚠️ 函数体引用 cardSampleWithPanel/cardPanelBackdrop（下方声明）⇒ Kotlin 局部函数不允许
             * 前向引用，故本函数【定义在它们之后】（见"卡与面板的分层"一节末尾）✗ 别搬回来。
             */

            /**
             * 【全对全】读一遍【其它所有卡】的位置（卡 i 除外）。
             * ⚠️ 现读现算：不按值捕获 activeCardCount/cardStateAt 的结果（P22 那次"桥持首帧闭包"的教训）
             * ——本函数每次调用都重新取当前渲染块数与各卡状态。
             */
            fun observeOtherCards(i: Int) {
                val n = activeCardCountNow()
                for (j in 0 until n) {
                    if (j == i) continue
                    val st = cardStateAt(j)
                    st.offsetX
                    st.offsetY
                }
            }

            /**
             * effects 内的跨卡订阅通道（通道①）。
             * multiChainOn ⇒ 全对全；否则 = 改动前的 P32 成对订阅（只有 卡 0 ↔ 卡 1）。
             */
            fun crossCardExtraObserve(i: Int) {
                if (multiChainOn) {
                    observeOtherCards(i)
                    return
                }
                when (i) {
                    0 -> { secondCardState.offsetX; secondCardState.offsetY }
                    1 -> { mainCardState.offsetX; mainCardState.offsetY }
                }
            }

            /**
             * 绘制期直连订阅（通道②，P32 验证过会投递的那条）。
             * multiChainOn ⇒ 全对全；否则 = 改动前：只有卡 0/1 互相订阅，卡 2/3 返回 null（不加 modifier）。
             */
            fun crossCardDrawObserve(i: Int): (() -> Unit)? {
                if (multiChainOn) return { observeOtherCards(i) }
                return when (i) {
                    0 -> ({ secondCardState.offsetX; secondCardState.offsetY })
                    1 -> ({ mainCardState.offsetX; mainCardState.offsetY })
                    else -> null
                }
            }

            /**
             * 卡 i 的离屏输出层（谁录）：关链 ⇒ 空；多卡链 ⇒ 非最上层才有（最上层无人采样它 ⇒ 不录，省一次）。
             */
            fun exportedLayerFor(i: Int): LayerBackdrop? =
                if (hasCardsAbove(i)) adapter.cardCaptureLayers[i] else null

            // 【P37 取证】采样链快照：只在 dualCardTrace 打开时打一行（每次重组一行）——
            // 直接证明"谁采谁"是按【当前 z 序】现算的（置顶后再打一次即可对比配对确实换向 ✓）。
            SideEffect {
                if (DebugSwitches.dualCardTrace) {
                    val desc = (0 until activeCardCount).joinToString(" | ") { i ->
                        val below = layersBelow(i).joinToString(",") { l ->
                            adapter.cardCaptureLayers.indexOf(l).toString()
                        }
                        "c$i:z=${zIndexOf(i).toInt()} below=[$below] rec=${exportedLayerFor(i) != null}"
                    }
                    android.util.Log.i("P37", "chain on=$multiChainOn z=$zOrder → $desc")
                }
            }

            // ---- 【P07·融合 meld（官方 iOS26 口径）】接近度 → 形状并集 uniform 逐帧驱动 ----
            // 与 P12【同一套接近度】：阈值/张力直接读 ProximityFusion.thresholdPx / .tension
            //（参数名 proximityThreshold；`setFusion --ef threshold <px>` 一处改、两边一起变 ✓）。
            // 为什么另有一个 driver：P12 的 runtime 是【有状态】的颈部/拉丝（两卡各画一次颈部 ⇒ 重叠区叠两次），
            // meld 要的是官方口径 —— 【并集只画一次】+ 融合宽度随接近度单调平滑。
            // 本函数的产物流向【同一个】fusionUniforms 通道（P12 已接好的 uniform/AGSL 通道，不新建体系 ✓）：
            // 两卡靠近时 meld 接管（并集 + 归属划分 ⇒ 一次折射），远离/关闭时回落到 P12 的数值（默认全 0 ⇒ 零回归 ✓）。
            val meldFrame = com.example.liquidglass.glass.DualCardMeld.rememberDualCardMeld(
                enabled = run {
                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                    DebugSwitches.dualCardMeld &&
                        uiState.twoCardDemo && uiState.secondShape != null
                },
                cardA = mainCardState,
                cardB = secondCardState,
                defaultTopLeftA = mainDefaultTopLeft,
                defaultTopLeftB = secondDefaultTopLeft,
                sizeA = { mainCardSizePx },
                sizeB = { secondCardSizePx },
                shapeA = { uiState.glassShape },
                shapeB = { uiState.secondShape ?: uiState.glassShape },
                cornerRadiusDp = 36f,
                density = density.density
            )

            // ---- 【P12·邻近流体融合】联合场逐帧驱动（单卡/开关关 ⇒ 完全静默、uniform 全 0 ⇒ 零回归）----
            // 位置与尺寸在 snapshotFlow 里读（组合外读快照 ⇒ 不触发重组）；融合收敛即自动停机
            // （静止零帧零日志）；`setSwitches proximityFusion 0|1` 后读一次 revision ⇒ 当场重组生效。
            val fusionFrame = com.example.liquidglass.glass.ProximityFusion.rememberProximityFusion(
                enabled = run {
                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                    DebugSwitches.proximityFusion &&
                        uiState.twoCardDemo && uiState.secondShape != null
                },
                cardA = mainCardState,
                cardB = secondCardState,
                defaultTopLeftA = mainDefaultTopLeft,
                defaultTopLeftB = secondDefaultTopLeft,
                sizeA = { mainCardSizePx },
                sizeB = { secondCardSizePx },
                shapeA = { uiState.glassShape },
                shapeB = { uiState.secondShape ?: uiState.glassShape },
                cornerRadiusDp = 36f,
                density = density.density
            )

            // ---- 【② 卡与面板的分层（采样层）】谁在谁之上 + 采样层里有没有面板（成对定义）----
            // 缺陷：captureLayer 只录【App 背景那一层】—— 面板【从不进入卡的采样层】✗ ⇒ 卡压在展开的
            //   面板上时，画出来仍是"面板下面的背景" ⇒ 观感 = 透过控制中心看到它下面的内容 ✗。
            // 修复（DebugSwitches.cardOverPanel）：
            //   true（默认 · 卡在面板之上）= 卡 z 整体 +1（都在面板 z=0 之上）+ 卡采样层 = 背景 + 面板离屏层
            //     ⇒ 卡折射的是【面板本身】✓（玻璃叠玻璃，iOS 观感）；
            //   false（一行回退 · 卡在面板之下）= 面板 z=10（压在所有卡之上 ⇒ 重叠部分就该看不见 ✓）
            //     + 卡采样层 = 背景（= 改动前）⇒ z 序与采样层仍然一致 ✓
            @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
            val cardOverPanel = DebugSwitches.cardOverPanel
            /**
             * 【A档遮挡 · 「面板没能 100% 遮住卡片」修复】洞裁剪总门（组合期求值）。
             * A 档（卡在面板之下）且开关默认开时 ⇒ 每块卡 / 选中卡描边层都被"面板形状的洞"裁一刀
             * （面板形状以内的卡像素整段不画 ⇒ 遮挡由几何给出，不再靠半透明材质的 10% 透射）。
             * 上一行已陪读 DebugBridge.revision ⇒ `setSwitches` 翻开关当场重组生效 ✓
             * B 档（cardOverPanel=true）⇒ 恒 false（卡必须浮在面板之上，一个像素都不裁）✓
             */
            val cardHoleOn = DebugSwitches.cardHiddenUnderPanel && !cardOverPanel
            // 【证据轴】采样层里要不要面板：= cardOverPanel && cardSamplePanel（默认 true&true = B 档 ✓）
            val cardSampleWithPanel = cardOverPanel && DebugSwitches.cardSamplePanel
            val cardZBase = if (cardOverPanel) 1f else 0f
            val panelZ = if (cardOverPanel) 0f else 10f
            /** 卡的采样层（含面板 = 背景 + 面板；否则 null = 库默认的背景层 ✓）。 */
            val cardPanelBackdrop =
                if (cardSampleWithPanel) rememberCombinedBackdrop(adapter.captureLayer, adapter.panelCaptureLayer)
                else null
            /**
             * 第二块卡在 P07 档下的采样层 = 背景 + 面板 + 下层卡离屏层。
             * 顺序 = 视觉叠放顺序（背景 → 面板 → 下层玻璃 → 上层玻璃）⇒ 上层卡看到的画面
             * 与"两块玻璃都真实画在面板之上"完全一致 ✓（面板在下层卡【之下】）。
             */
            val dualPanelBackdrop =
                if (cardSampleWithPanel) rememberCombinedBackdrop(
                    adapter.captureLayer, adapter.panelCaptureLayer, adapter.dualCaptureLayer
                ) else null

            // ---- 【P37 多卡】逐层采样链：卡 i 的采样源 = 背景 +（面板）+ 所有 z 序在它之下的卡的层 ----
            // （定义位置在 cardSampleWithPanel 之后：Kotlin 局部函数不允许前向引用 ✗ 见上方注释）
            @Composable
            fun chainBackdrop(i: Int): com.kyant.backdrop.Backdrop? {
                val below = layersBelow(i)
                val base: List<com.kyant.backdrop.Backdrop> = if (cardSampleWithPanel) {
                    listOf(adapter.captureLayer, adapter.panelCaptureLayer)
                } else {
                    listOf(adapter.captureLayer)
                }
                val all: List<com.kyant.backdrop.Backdrop> = base + below
                // ⚠️【恒定结构】无论 below 是否为空都执行同一个 remember（单键 = 层列表标签 String）：
                //   曾经写成 `if (below.isEmpty()) return null` 早退 ⇒ 链长变化时 remember 的键槽数
                //   从 0 变成 N ⇒ 外层 composable 内部槽位结构变化、Compose 不检测 ⇒ 槽位错位读出
                //   "键当值"⇒ 实测 ClassCastException 闪退（在用户点置顶的那一刻，见
                //   backdrop/BackdropAdapter.kt 里 OrderedBackdropChain 的注释）。恒定结构 = 不会错位 ✓
                //   标签用层在 cardCaptureLayers 里的【索引】拼接 ⇒ String.equals 语义稳定，
                //   链不变时命中缓存、链变了（置顶/换层/开关切换）立刻重建 ✓
                val tag = StringBuilder(16)
                    .append("chain:panel=").append(if (cardSampleWithPanel) 1 else 0)
                    .append(";below=")
                    .apply {
                        below.forEach {
                            append(adapter.cardCaptureLayers.indexOf(it))
                            append(',')
                        }
                    }
                    .toString()
                val chain = remember(tag) { com.example.liquidglass.backdrop.OrderedBackdropChain(all) }
                return if (below.isEmpty()) null else chain
            }

            // ---- 【A档遮挡】主卡放进 matchParentSize 包装盒（= root 坐标空间）----
            // 为什么需要包装盒：洞裁剪的路径是"面板形状在 root 坐标下的路径"（与面板自己那份几何同源、
            // 零坐标换算）；卡片自身的空间被 align/offset/内部图层平移复合过 ⇒ 直接在卡片上裁要换算三处
            // 偏移（易漂）。包装盒 = 与 root 等大 ⇒ 洞路径直接可用 ✓
            // 代价：每块卡多一个"只有布局、无绘制"的 Box（空布局节点，不是图层）；卡片的布局/手势/
            // 命中语义【一字未改】（包装盒自己不接指针、也不裁剪布局，只裁剪绘制）✓
            // zIndex 从卡片移到包装盒：值不变 ⇒ 与面板/其余节点的叠放与命中顺序逐位不变 ✓
            // （缩进保持原样，便于逐行 diff；开 = 默认（DebugSwitches.cardHiddenUnderPanel））
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(cardZBase + zIndexOf(0))
                    .hideInsidePanelHole(cardHoleOn, panelHole) { pNow() }
            ) {
            LiquidGlassCard(
                adapter = adapter,
                state = mainCardState,
                // 【② 分层】卡在面板之上（B 档）⇒ 采样层 = 背景 + 面板离屏层（面板真正进入卡的采样层 ✓）；
                //   A 档 = null（库默认 = 背景层 = 改动前 ✓，此时面板由 z 序遮挡 ⇒ 语义仍一致）
                // 【P37 多卡】它下面还有别的玻璃时（= 被置底/其它卡置顶）改用逐层链（背景+面板+下层各卡）
                backdrop = (if (multiChainOn) chainBackdrop(0) else null) ?: cardPanelBackdrop,
                // 【P07】下层卡（兄弟序在前 = 先绘制）：把【自己渲染后的结果】录进离屏层，供上层卡采样。
                // 开关关 / 单卡时传 null ⇒ 库里完全不录这一层（逐像素 + 零成本等于改动前 ✓）。
                // 【P37】多卡链：录进本卡自己的层 cardCaptureLayers[0]（= 就是 P07 的 dualCaptureLayer ✓）；
                //   只有【上面还有卡】时才录（最上层那块无人采样它 ⇒ null 省一次离屏录制）。
                exportedBackdrop = if (multiChainOn) exportedLayerFor(0)
                else if (dualRefractOn) adapter.dualCaptureLayer else null,
                // 【P32】跨卡失效订阅【成对】：下卡（离屏录制方）也订阅【上卡的拖动位置】——
                //   改前只有"下卡动 ⇒ 上卡重录"这一向 ✗；上卡移动时下卡的玻璃层与离屏录制层
                //   整段不重录（实测连续 ~1.3s 零失效）⇒ 采样内容冻结 = 用户报的"静止卡折射卡住" ✓
                //   与 P12 的 frame.value 通道无关（那条会在两卡重叠后静默），故这里必须显式订阅 ✓
                // 【P37】多卡链下扩为【全对全】：本卡读一遍其它所有卡的位置 ⇒ 任意一块移动本卡都重录 ✓
                extraObservedReads = {
                    if (DebugSwitches.dualPairObserve) crossCardExtraObserve(0)
                },
                // 【P32】投递保底：绘制期直连（库 observeReads 通道实测中段不投递）——上卡一动本卡重录 ✓
                // 【P37】同样扩为全对全（crossCardDrawObserve(0)）✓
                crossObserveOther = crossCardDrawObserve(0),
                // 【P37 取证】探针日志的卡号（只被 dualCardTrace 读取）
                traceCard = "0",
                // 【P12·邻近流体融合】/【P07·融合 meld】本卡这一帧的融合 uniform：
                //   meld 打开时由它接管（并集 + 归属划分 ⇒ 一次折射）；关闭时回落到 P12 原数值。
                fusionUniforms = { meldFrame.value?.forA ?: fusionFrame.value?.forA },
                parameters = { uiState.parameters },
                quality = { uiState.quality },
                defaultTopLeftPx = mainDefaultTopLeft,
                offsetBounds = mainOffsetBounds,
                adaptiveLegibility = uiState.adaptiveLegibility,
                reduceMotion = uiState.reduceMotion,
                stretchOnDrag = uiState.stretchOnDrag,
                debugMode = { uiState.debugMode },
                shapeType = { uiState.glassShape },
                // 【固定开 · 面板开关行已按用户要求删除】原先这里读面板开关 uiState.iosLensProfile；
                // 用户原话：「还有 iOS 透镜折射剖面关掉以后效果好诡异，直接把这个开关删掉吧它默认打开就可以了」
                // ⇒ 写死常量 true（运行时无从关闭）；剖面实现与参数未动，字段保留仅为 A/B 与回归留档。
                lensProfile = { true },
                hdrBoost = { if (hdrActive) 4.0f else 1f },
                // 【P06 点击置顶】点中这块卡 ⇒ 它移到 z 序末尾（最上层）；z 值见下方 modifier 里的 zIndex
                // 【选中卡·用户需求①】同一次点按也把"当前正在调整的玻璃"切到这块 ✓（手指放在哪块就改哪块）
                onTap = { uiState.selectCard(0); raiseCardToFront(0) },
                // 【双指捏合改尺寸】主卡：捏合 = 改【本卡】尺寸，走【与面板滑块同一个】setSizeOfCard 函数
                //（换算/夹取/顺带选中该卡都在 ui/CardPinchResize.kt；双向同步天然成立 ✓）
                onPinchSize = { prev, now -> applyPinchResizeForCard(uiState, 0, prev, now) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        run {
                            // 【运行时 A/B】读一次 DebugBridge.revision（快照状态）⇒
                            // `setSwitches sizeLongEdge 0|1` 后立即重组，尺寸口径当场切换（无需重启）✓
                            @Suppress("UNUSED_EXPRESSION") com.example.liquidglass.debug.DebugBridge.revision.intValue
                            val aspect = uiState.glassShape.aspectRatio
                            if (aspect <= 0f) {
                                // 无固定宽高比的形状（圆角矩形等）：高度与宽度同步按比例缩放，
                                // 这样拖动"玻璃尺寸"时所有形状一起缩放，而不是只变宽。
                                // 1.026 = 原始高宽比 1.641 折算到父容器(整屏高)后的系数，保证默认值下尺寸与改前一致。
                                Modifier
                                    .fillMaxWidth(uiState.glassSize)
                                    .fillMaxHeight((uiState.glassSize * 1.026f).coerceIn(0.12f, 0.9f))
                            } else {
                                // 【③B·玻璃尺寸口径改按【长边】】(DebugSwitches.sizeLongEdge，默认开)：
                                //   长边 = max(a, 1/a) × glassSize × 屏宽（a = 形状宽高比）——
                                //   即【短边 = glassSize × 屏宽】恒定、长边按形状自身的细长比伸长。
                                //   a ≥ 1（横置：胶囊 1.92 / 椭圆 1.39）⇒ 长边在宽轴：宽 = a×glassSize×屏宽，
                                //     高由 aspectRatio 从实际宽度反算（= glassSize×屏宽）；
                                //   0 < a < 1（竖置）⇒ 长边在高轴：高 = (glassSize/a)×屏宽（换算成父容器高的比例），
                                //     宽由 aspectRatio 反算（= 高×a）。
                                //   a = 1（圆/六边/三角/超椭圆）⇒ 两套口径解析等价（宽=高=glassSize×屏宽）
                                //     ⇒ 为保【逐像素/逐像素 round 行为】严格一致，仍走旧表达式（开关不参与）✓
                                val elong = if (aspect >= 1f) aspect else 1f / aspect
                                val useLongEdge = DebugSwitches.sizeLongEdge && aspect != 1f
                                when {
                                    useLongEdge && aspect > 1f -> Modifier
                                        // 长边 = elong×glassSize×屏宽（= a×glassSize×屏宽），超屏宽时以屏宽为上限。
                                        .fillMaxWidth((elong * uiState.glassSize).coerceAtMost(1f))
                                        .aspectRatio(aspect)
                                    useLongEdge -> Modifier
                                        // 长边在高轴：把"屏宽单位的长度"换算成父容器高的比例（屏高为分母）。
                                        .fillMaxHeight(
                                            (uiState.glassSize / aspect *
                                                (rootSizePx.width.toFloat() / rootSizePx.height.toFloat()))
                                                .coerceIn(0.02f, 1f)
                                        )
                                        .aspectRatio(aspect)
                                    else -> Modifier
                                        // 旧口径（开关关 = 逐像素回到改前；a = 1 也走这里 ✓）
                                        .fillMaxWidth(uiState.glassSize)
                                        .aspectRatio(aspect)
                                }
                            }
                        }
                    )
                    // 【中心基点】纵向落点 = mainDefaultTopLeft().y 的整数化 —— 与 dumpState 的 card rect、
                    // 拖动钳制窗口同一来源（唯一真值）；sizeAnchorCenter=false 时该值 = (0.14×屏高) 与改前逐比特相同 ✓
                    .offset {
                        // 【布局期搭档】@Volatile 开关不产生订阅 ⇒ 这里读一次 DebugBridge.revision
                        // （快照状态；放置期读取 ⇒ 订阅落在布局/放置作用域）⇒ `setSwitches sizeAnchorCenter 0|1`
                        // 即使不改尺寸也会当场重排（钉住"翻开关即生效"，不拍混合态 ✓；NEXT.md 已两次踩）
                        @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                        IntOffset(0, mainDefaultTopLeft().y.toInt())
                    }
                    // 【P06 多卡·点击置顶】z 序（默认 0 = 改动前的兄弟顺序，逐像素零回归 ✓）
                    // 【② 分层】cardZBase = 1（B 档：整块卡抬到面板之上）/ 0（A 档：面板 z=10 压住所有卡）
                    // 【A档遮挡】zIndex 已上移到外层包装盒（值不变 ⇒ 叠放/命中顺序逐位不变 ✓）
                    .onSizeChanged { mainCardSizePx = it }
            ) {
                MainCardContent(uiState.glassShape)
            }
            }   // ← 【A档遮挡】包装盒结束（洞裁剪作用域）

            // ---- 【P47】性能看板（默认 = 全屏可拖 / 左右边缘吸附收起 / 可从边缘拉出的悬浮 HUD；
            //      开关 perfHudDraggable=0 一行回退到改动前的右上角固定位，见 ui/PerformancePanel.kt）----
            // 【H2·看板置顶】zIndex(11) > 面板（z=10）⇒ 看板悬在最顶层（用户口径 = 像 Scene 那样）；
            //   纯 UI 浮层、不建/不采样 LayerBackdrop ⇒ 无分层副作用 ✓。
            //   注：浮动档（默认）不使用本 modifier（它含 align/statusBarsPadding/padding，回退档专用）
            //   ⇒ 浮动容器在 PerformancePanel.kt 里自带同值 zIndex；本行的 zIndex 覆盖回退档。
            PerformanceHud(
                monitor = monitor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 10.dp, end = 12.dp)
                    .zIndex(11f)
            )

            // ---- 第二张玻璃（形状选了两个时自动出现）----
            val secondShape = uiState.secondShape
            if (uiState.twoCardDemo && secondShape != null) {
                // 【P07】上层卡（本卡后绘制，视觉上压在下层卡之上）的采样源：
                // 背景捕获层 + 【下层卡渲染后的离屏层】⇒ 它 shader 里的 input 已经是
                // "被下层玻璃折射过的画面"，再经本卡折射 = 真二次折射 ✓
                // （无环：下层卡只采背景捕获层，本层绝不进下层卡的采样源）
                val dualCombinedBackdrop =
                    if (dualRefractOn) rememberCombinedBackdrop(adapter.captureLayer, adapter.dualCaptureLayer)
                    else null
                // 【选中卡·用户需求①】第二块的尺寸 = uiState.sizeOfCard(1)：
                //   · 宽度比例默认 0.55 = 改动前写死在这里的常量 ⇒ 逐位相同（零回归 ✓）；
                //   · 无固定宽高比的形状（第二块的高度那一支）按【同一比例】缩放（scale=1 时为恒等 ⇒
                //     默认档逐位不变），这样拖尺寸滑块时它整体缩放、而不是只变宽。
                //   · 回退：把 width 换回 0.55f、height 去掉 * secondScale 即可（一行回退 ✓）。
                val secondScale = uiState.sizeOfCard(1) / 0.55f
                // 【A档遮挡】同主卡：包装盒 = root 坐标空间 ⇒ 洞裁剪直接用 root 坐标路径 ✓
                Box(
                    Modifier
                        .matchParentSize()
                        .zIndex(cardZBase + zIndexOf(1))
                        .hideInsidePanelHole(cardHoleOn, panelHole) { pNow() }
                ) {
                LiquidGlassCard(
                    adapter = adapter,
                    state = secondCardState,
                    // 【P37 多卡】采样链 = 背景 +（面板）+ 所有 z 序在它之下的卡的已渲染结果
                    //（下面没有别的玻璃时返回 null ⇒ 回落到原档 cardPanelBackdrop / P07 组合层 ✓）
                    backdrop = (if (multiChainOn) chainBackdrop(1) else null)
                        ?: (if (dualRefractOn) (dualPanelBackdrop ?: dualCombinedBackdrop) else cardPanelBackdrop),
                    // 【P37】本卡在别人之下时也要把【自己】录进离屏层（多卡链的中间层 ✓）
                    exportedBackdrop = exportedLayerFor(1),
                    // 下层卡（主卡）一移动，本卡同帧重录：否则离屏层的相对偏移停在旧值（采到旧内容 ✗）
                    // 【P12·邻近流体融合】/【P07·融合 meld】上层卡用同一份参数的镜像（同源 ⇒ 无缝）
                    fusionUniforms = { meldFrame.value?.forB ?: fusionFrame.value?.forB },
                    // 【P37】跨卡订阅（两条通道）= 全对全；关链时 = 改动前的 P32 成对订阅 ✓
                    extraObservedReads = {
                        if (DebugSwitches.dualPairObserve) crossCardExtraObserve(1)
                    },
                    // 【P32】投递保底：绘制期直连——下卡一动本卡当帧重录（否则采到旧内容 ✗）
                    crossObserveOther = crossCardDrawObserve(1),
                    traceCard = "1",
                    // 用户反馈：展开控制中心时"玻璃有概率不跟随" ✗ —— 根因就是这里
                    // 在面板 morph 期间把卡片的玻璃整体跳过 ✗（当时为了每帧少一次背景捕获 ✗）。
                    // 改为【始终渲染】✓：玻璃跟随面板上移，观感统一 ✓（代价：展开动画期间
                    // 每帧多一次背景捕获，帧时间会升 —— 真机滚动 0% 掉帧，有余量 ✓）
                    suppressGlass = false,
                    // 【P06 点击置顶】点中这块卡 ⇒ 它移到 z 序末尾（最上层）
                    // 【选中卡·用户需求①】同一次点按也把"当前正在调整的玻璃"切到这块 ✓
                    onTap = { uiState.selectCard(1); raiseCardToFront(1) },
                    // 【双指捏合改尺寸】第二块：捏合 = 改【本卡】尺寸（同一函数 ⇒ 与面板滑块双向同步 ✓）
                    onPinchSize = { prev, now -> applyPinchResizeForCard(uiState, 1, prev, now) },
                    parameters = { uiState.parameters },
                    quality = { uiState.quality },
                    defaultTopLeftPx = secondDefaultTopLeft,
                    offsetBounds = secondOffsetBounds,
                    adaptiveLegibility = uiState.adaptiveLegibility,
                    reduceMotion = uiState.reduceMotion,
                    stretchOnDrag = uiState.stretchOnDrag,
                    debugMode = { uiState.debugMode },
                    shapeType = { secondShape },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // 【选中卡】第二块宽度 = 它自己的尺寸（默认 0.55f = 改动前的常量 ⇒ 零回归 ✓）
                        .fillMaxWidth(uiState.sizeOfCard(1))
                        .then(
                            if (secondShape.aspectRatio > 0f) Modifier.aspectRatio(secondShape.aspectRatio)
                            else Modifier.fillMaxHeight(
                                ((uiState.glassSize * 1.026f) * secondScale).coerceIn(0.12f, 0.9f)
                            )
                        )
                        .offset {
                            IntOffset(
                                -with(density) { 24.dp.toPx() }.toInt(),
                                // 【P22·玻璃矩形对齐】纵向随 secondDefaultTopLeft 一起减 (19dp×density)
                                //（两者必须同源：布局落点 = 桥/dumpState 报出的值 = 玻璃可见矩形 ✓）
                                (rootSizePx.height * 0.40f -
                                    glassRectRestInsetYPx(density.density)).toInt()
                            )
                        }
                        // 【P06 多卡·点击置顶】z 序（默认 1 = 压在主卡之上，与改动前逐像素相同 ✓）
                        // 【② 分层】+ cardZBase（B 档抬到面板之上）
                        // 【A档遮挡】zIndex 已上移到外层包装盒（值不变 ⇒ 叠放/命中顺序逐位不变 ✓）
                        .onSizeChanged { secondCardSizePx = it }
                ) {
                    SecondCardContent()
                }
                }   // ← 【A档遮挡】包装盒结束（洞裁剪作用域）
            }

            // ---- 【P06 多卡】第 3/4 块玻璃（索引 2、3）----
            // 与第二块同一套布局语言（贴 24dp 边距 + 屏高比例落点），尺寸更小（0.42 / 0.34 屏宽）。
            // 与既有机制的边界（刻意为之，防互相污染）：
            //  · 不参与 P07：不做离屏录制（exportedBackdrop 保持默认 null）、也不采组合层；
            //  · 不参与 P12：不传 fusionUniforms（默认 null ⇒ uniform 全 0 ⇒ 这两块的玻璃等于"独立的单卡"）；
            //  ⇒ P07/P12 的接口与调用点一个字没动，它们的行为只由卡片 0/1 决定 ✓
            //  · 每块各一份 GlassCardState（可独立拖动）+ 点击置顶（onTap → z 序末尾）。
            for (i in 2 until maxCards) {
                if (i >= activeCardCount) break
                val stateI = cardStateAt(i)
                val shapeI = uiState.shapeOfCard(i)
                // 【选中卡·用户需求①】追加块的尺寸 = uiState.sizeOfCard(i)（默认 0.42 / 0.34 = 改动前写死
                // 的常量 ⇒ 零回归 ✓）；高度那一支按同一比例缩放（scale=1 时为恒等 ⇒ 默认档逐位不变）。
                val extraScale = uiState.sizeOfCard(i) / if (i <= 2) 0.42f else 0.34f
                // 【A档遮挡】同主卡/第二块：包装盒 = root 坐标空间 ⇒ 洞裁剪直接用 root 坐标路径 ✓
                Box(
                    Modifier
                        .matchParentSize()
                        .zIndex(cardZBase + zIndexOf(i))
                        .hideInsidePanelHole(cardHoleOn, panelHole) { pNow() }
                ) {
                LiquidGlassCard(
                    adapter = adapter,
                    state = stateI,
                    // 【② 分层】追加块也在面板之上（B 档）⇒ 同样采"背景 + 面板"（A 档 = null = 改动前）
                    // 【P37 多卡】再多加【z 序在它之下的每一块玻璃】的离屏层 ⇒ 第 3/4 块也有逐层折射 ✓
                    backdrop = (if (multiChainOn) chainBackdrop(i) else null) ?: cardPanelBackdrop,
                    // 【P37】被别的卡采样时把【自己】录进离屏层（改前这两块既不录也不采 ✗）
                    exportedBackdrop = exportedLayerFor(i),
                    // 【P37】跨卡订阅（两条通道，全对全）——改前追加块【完全没有】跨卡订阅 ✗
                    extraObservedReads = {
                        if (DebugSwitches.dualPairObserve) crossCardExtraObserve(i)
                    },
                    crossObserveOther = crossCardDrawObserve(i),
                    traceCard = "$i",
                    parameters = { uiState.parameters },
                    quality = { uiState.quality },
                    defaultTopLeftPx = { extraDefaultTopLeft(i) },
                    offsetBounds = { extraOffsetBounds(i) },
                    adaptiveLegibility = uiState.adaptiveLegibility,
                    reduceMotion = uiState.reduceMotion,
                    stretchOnDrag = uiState.stretchOnDrag,
                    debugMode = { uiState.debugMode },
                    shapeType = { shapeI },
                    onTap = { uiState.selectCard(i); raiseCardToFront(i) },
                    // 【双指捏合改尺寸】第 3/4 块：捏合 = 改【本块】尺寸（同一函数 ⇒ 与面板滑块双向同步 ✓）
                    onPinchSize = { prev, now -> applyPinchResizeForCard(uiState, i, prev, now) },
                    modifier = Modifier
                        .align(if (i <= 2) Alignment.TopStart else Alignment.TopEnd)
                        // 【选中卡】追加块宽度 = 它自己的尺寸（默认 0.42 / 0.34 = 改动前的常量 ⇒ 零回归 ✓）
                        .fillMaxWidth(uiState.sizeOfCard(i))
                        .then(
                            if (shapeI.aspectRatio > 0f) Modifier.aspectRatio(shapeI.aspectRatio)
                            else Modifier.fillMaxHeight(
                                ((uiState.glassSize * 1.026f) * extraScale).coerceIn(0.12f, 0.9f)
                            )
                        )
                        .offset {
                            // 与 extraDefaultTopLeft 同一套落点公式（唯一真值：布局落点 = 桥/dumpState 读到的值）
                            // 【P22·玻璃矩形对齐】纵向减 (19dp×density) ⇒ 与 extraDefaultTopLeft 的减法同源 ✓
                            IntOffset(
                                (if (i <= 2) 1 else -1) * extraCardMarginPx().toInt(),
                                (rootSizePx.height * extraCardYFraction(i) -
                                    glassRectRestInsetYPx(density.density)).toInt()
                            )
                        }
                        // 【A档遮挡】zIndex 已上移到外层包装盒（值不变 ⇒ 叠放/命中顺序逐位不变 ✓）
                        .onSizeChanged { setCardSizeAt(i, it) }
                ) {
                    SecondCardContent()
                }
                }   // ← 【A档遮挡】包装盒结束（洞裁剪作用域）
            }

            // ---- 【选中卡·用户需求①】选中块的可见反馈：一圈高亮描边 ----
            // 为什么这么画：
            //  · 纯 UI 覆盖层（Canvas + 绘制期读 rect）—— 只画在 UI 上、不进背景捕获层 ⇒ 不影响任何玻璃
            //    的采样/折射管线 ✓（拖动/改尺寸时只重绘、不重组）；
            //  · 只在【多块同屏 + 面板逻辑打开】时出现 ⇒ 单卡态 / 收起态一个像素都不加（零回归 ✓）；
            //  · z 值 3.5 = 在四块卡之上、其余节点之下（与卡片自身浮在面板上的既有 z 序一致）；
            //  · 切换选中的两条路都通：① 点屏幕上哪块玻璃（LiquidGlassCard.onTap → selectCard）；
            //    ② 面板「形状与布局」里的「1 / 2 / 3 / 4」chips。
            if (activeCardCount >= 2 && showControlCenter) {
                val selIdx = uiState.selectedCardIndex.coerceIn(0, activeCardCount - 1)
                // 【A档遮挡】选中卡描边层同样在【面板之下】⇒ 一起过洞裁剪
                // （否则它会以 10% 透射从面板后面透出来 = 同一族缺陷；它本身 matchParentSize
                //  ⇒ 坐标空间已是 root，直接用同一个洞路径 ✓）
                Canvas(
                    Modifier
                        .matchParentSize()
                        .zIndex(cardZBase + 4.5f)
                        .hideInsidePanelHole(cardHoleOn, panelHole) { pNow() }
                ) {
                    val r = cardRectNow(selIdx) ?: return@Canvas
                    if (r.width <= 12f || r.height <= 12f) return@Canvas
                    val radius = 36.dp.toPx()
                    val inset = 3f
                    // 双环：外圈深色（浅背景上也看得见）+ 内圈白色（深色玻璃/壁纸上也看得见）
                    drawRoundRect(
                        color = Color(0x70000F22),
                        topLeft = Offset(r.left + inset, r.top + inset),
                        size = Size(r.width - 2f * inset, r.height - 2f * inset),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 5f)
                    )
                    drawRoundRect(
                        color = Color(0xF2FFFFFF),
                        topLeft = Offset(r.left + inset + 2.5f, r.top + inset + 2.5f),
                        size = Size(r.width - 2f * (inset + 2.5f), r.height - 2f * (inset + 2.5f)),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 3f)
                    )
                }
            }

            // ---- 【P08·邻近提亮（能量传递）】两卡最近点连线中点叠一层柔光（【默认开】）----
            // 机制：只画【一层白柔光】（画在两卡之上、不参与玻璃采样/折射管线 ⇒ 对 P07/P12 的取值影响为零）。
            // 几何 = 两卡【可见矩形】的最近点对中点（与 dumpState 的 card rect 同源：默认落点 + offset + 尺寸；
            // 与 P12 的 ProximityFusion.geometry() 是同一套 AABB 口径，阈值【跟随】ProximityFusion.thresholdPx）。
            // 坐标在【绘制期】读（拖动/回弹时只重绘、不触发重组）。`setSwitches nearGlow 0|1` 一条命令即切换；
            // enabled=false ⇒ 本组合节点不存在 ⇒ 逐像素零回归（单卡 / 双卡远离 / 双卡靠近都是）✓
            // 陪读 DebugBridge.revision：翻开关后当场重组生效。逐帧取证档 = nearGlowTrace（默认关）。
            com.example.liquidglass.glass.ProximityHighlight.NearGlowOverlay(
                enabled = run {
                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                    DebugSwitches.nearGlow && uiState.twoCardDemo && uiState.secondShape != null
                },
                trace = run {
                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                    DebugSwitches.nearGlowTrace
                },
                revision = DebugBridge.revision.intValue,
                rectA = {
                    com.example.liquidglass.glass.ProximityHighlight.rectOf(
                        mainDefaultTopLeft(), mainCardState.offsetX, mainCardState.offsetY, mainCardSizePx
                    )
                },
                rectB = {
                    com.example.liquidglass.glass.ProximityHighlight.rectOf(
                        secondDefaultTopLeft(), secondCardState.offsetX, secondCardState.offsetY, secondCardSizePx
                    )
                },
                modifier = Modifier.align(Alignment.TopStart)
            )

            // ---- 面板 Shader 预热（1px 不可见节点）----
            // AGSL RuntimeShader 首次使用要现编译（真机实测 100~300ms 的停顿），
            // 若拖到"点开控制中心"的瞬间才编译，展开动画开头必然掉帧。
            // 启动时先用一个 1px 透明节点把它编译好。
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(1.dp)
                    .graphicsLayer { alpha = 0f }
                    .glassPanel(
                        adapter = adapter,
                        shape = { RoundedCornerShape(0.dp) },
                        cornerRadiiPx = { floatArrayOf(0f, 0f, 0f, 0f) },
                        quality = { uiState.quality },
                        parameters = { uiState.parameters }
                    )
            )

            // ---- 底部中心：椭圆玻璃按钮 ⇄ 控制中心玻璃面板 ----
            // 收起态是椭圆（胶囊）玻璃按钮；点按后【同一个元素】非线性地无缝
            // morph 成全宽玻璃面板——宽度先铺开、高度随后落下，圆角/位置/
            // 内容透明度连续插值，没有任何元素替换或跳变。
            val panelScope = rememberCoroutineScope()
            // ===== 面板动画（唯一动画入口）=====
            // 保留原有 900ms(展开) / 620ms(收起) 与 CubicBezierEasing(0.42,0.05,0.22,1)；
            // 目标全部交给官方锚点状态的 animateTo。拖动/松手吸附由官方 fling 驱动同一个状态，
            // 因此同一时刻只有一个写入者（不再出现 morph 与 dragProgress 抢 p 的情况）。
            fun panelMotionScale(): Float = if (uiState.reduceMotion) 0.5f else 1f
            fun panelAnimDurationMs(open: Boolean): Int =
                ((if (open) 900 else 620) * panelMotionScale()).toInt()
            /**
             * 【动画一致性·2026-09-14】程序化开合的时长（一致性开关打开时生效）：
             *   一律 PANEL_PROGRAM_MS = 900ms ⇒ 展开（0→1，用户认可基准，一字不动 ✓）与
             *   关闭（1→0 / 2→1 / 2→0）严格相等（改前关闭固定 620ms ⇒ 同一条路反向快 1.45× ✗）。
             * 行程（px）只用于日志取证（LGLayout 逐帧 p 序列 + 本值 ⇒ 可复算平均速度）。
             */
            fun unifiedProgramDurationMs(from: Float, target: Float): Int {
                // 行程只用于日志取证（LGLayout 逐帧 p 序列 + 本值 ⇒ 可复算平均速度）
                if (AppDebugLog.enabled) {
                    val spanOpen = anchorState.anchors.positionOf(1f).coerceAtLeast(1f)
                    val travel = kotlin.math.abs(offsetOfP(target) - offsetOfP(from))
                    AppDebugLog.log(
                        "ANIM",
                        "统一时长 行程=%.0fpx 单位(0→1跨度)=%.0fpx ⇒ 时长=%dms(固定)".format(
                            travel, spanOpen, PANEL_PROGRAM_MS.toInt()
                        )
                    )
                }
                return unifiedMsOf(PANEL_PROGRAM_MS * panelMotionScale())
            }
            fun animatePanelTo(target: Float, durationMs: Int) {
                val from = pNow()
                // 一致性开关：开 = 固定 900ms（两方向同一时长，见上）；关 = 沿用调用点传入的旧时长 ✓
                val durMs = if (DebugSwitches.panelTimingUnified)
                    unifiedProgramDurationMs(from, target) else durationMs
                // 【P59·动画期主动请求 120Hz】动画开始即重申三通道帧率请求（默认开；回退开关见 DebugSwitches.refreshDuringPanelAnim）：
                // 「只有启动/焦点时才请求」的机制在动画开始那拍可能被系统降回 60 档 ⇒ 动画段逐帧 16.67ms ✗；
                // 这里每次动画 begin 显式重申一次（与 MainActivity 用的同一条 applyToWindow 实现，幂等/必须主线程；
                // 局部 fun 跑在 panelScope.launch 之前的主线程组合/回调上下文里 ✓；非主线程会返回 SKIP 行，不抛）。
                if (DebugSwitches.refreshDuringPanelAnim) {
                    RefreshRateController.applyToWindow(animWindow.value, null)
                }
                val _t0 = android.os.SystemClock.elapsedRealtime()
                AppDebugLog.log(
                    "ANIM",
                    "begin target=%.0f  from=%.3f  时长=%dms(传入=%d 统一=%b)"
                        .format(target, from, durMs, durationMs, DebugSwitches.panelTimingUnified)
                )
                panelScope.launch {
                    // 被手势/其它动画打断时这里会抛 CancellationException（官方语义）：
                    // 只取消本次动画，不残留中间态（接管者会把它收口到某个锚点）。
                    anchorState.animateTo(
                        targetValue = target,
                        animationSpec = tween(
                            // 展开：900ms（原 780）——用户要求点击时也能看到"控制中心"被推着
                            // 逐步放大移动的过程 ✓；收起按【同一条速度】等比缩放（一致性修复：
                            // 改前固定 620ms ⇒ 同一条路反向快 1.45× ✗）
                            durationMillis = durMs,
                            // 展开曲线：原 (0.16, 1, 0.30, 1) 极度前置 ✗（前 150ms 就走完大半 →
                            // "逐步"被压缩得看不见）。改为慢起—加速—缓收 ✓，让前段进度线性得多 ✓
                            // 【一致性】与收口动画（PANEL_PRESET_EASINGS / PANEL_SNAP_EASING）同一条曲线 ✓
                            easing = CubicBezierEasing(0.42f, 0.05f, 0.22f, 1f)
                        )
                    )
                    AppDebugLog.log("ANIM", "end p=%.3f  实际耗时=%dms".format(pNow(), android.os.SystemClock.elapsedRealtime() - _t0))
                }
            }
            // ==================== 【P63·系统返回先收回展开的控制中心】(2026-09-23) ====================
            // 系统返回（返回键 / 侧边返回手势 / Android 13+ 预测式返回）在面板【展开态】先收面板到 p=0、
            // ✗ 不退出应用；已收起（p<0.5）一律【不拦截】（enabled=false ⇒ 系统默认返回 = 允许退出，改动前原样）。
            // 【返回栈优先级（与表头「‹ 基础 / ← 关闭」按钮逐字同一条链；那颗按钮与二级页内部返回逻辑
            //   一行未动）】① 二级页：许可 → 图片编辑 → 组件演示 → 更多设置，逐级退回一级基础页；
            //   ② 一级页且 p≥0.5 → animatePanelTo(0f, panelAnimDurationMs(open=false))（与按钮关闭
            //   同一收口动画/时长口径 ⇒ 收起动画时长与轨迹不变 ✓）；③ p<0.5 → 不拦截。
            // 【预测式返回联动】（Manifest enableOnBackInvokedCallback=true 时）手势 progress 逐帧驱动
            //   p = 起点p×(1−progress)（写入路径 = dispatchRawDelta，与 setPanelP 同一条）；松手完成 →
            //   animatePanelTo(0f) 从当前 p 继续收口；手势取消 → animatePanelTo(起点p) 弹回（无跳变）。
            //   未开 Manifest 属性/legacy 派发下退化为「返回即收起」✓。
            // 实现选型：androidx.activity.compose.PredictiveBackHandler（activity-compose 1.13.0，
            //   = OnBackPressedCallback 的 Compose 封装）✗ 不用已弃用的 onBackPressed 重写 ✗。
            // 一行回退：setSwitches backCollapsePanel 0 ⇒ 整段不拦截 = 改动前。
            // enabled 是组合期读取 ⇒ 陪读 DebugBridge.revision（翻开关即重组生效；revision 极少变 ⇒ 零开销）。
            @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
            // 手势进行中标记 + 起点 p（普通数组：只在主线程读写，不进组合 ⇒ 不触发多余重组）
            val backGestureActive = remember { booleanArrayOf(false) }
            val backGestureStartP = remember { floatArrayOf(0f) }
            // 【⑤ 加速度上限·2026-09-25】限速状态（普通数组，同上一套：只在主线程读写、不进组合）：
            //   [0] = 上一个事件的时间戳 ms（0 = 本次手势首帧，不折算 dt）
            //   [1]/[2] = 本次手势的 max|Δp| / max|dp/dt|（诊断用，手势结束打一行汇总）
            //   [3] = 本次手势收到的事件帧数
            val backGestureLastMs = remember { longArrayOf(0L) }
            val backGestureMaxDp = remember { floatArrayOf(0f) }
            val backGestureMaxRate = remember { floatArrayOf(0f) }
            val backGestureFrames = remember { intArrayOf(0) }
            var backIntercept by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // 展开判定订阅：p/页状态一变就重算拦截位（写 Boolean 状态只在跨阈值时重组一次 ⇒
                // ✗ 不逐帧订阅 offset、不逐帧重组；手势进行中冻结 enabled，避免中途改值把手势取消）
                androidx.compose.runtime.snapshotFlow {
                    panelLicenses || panelImageEdit || panelComponentDemo || panelAdvanced || pNow() >= 0.5f
                }.collect { want -> if (!backGestureActive[0]) backIntercept = want }
            }
            androidx.activity.compose.PredictiveBackHandler(
                enabled = DebugSwitches.backCollapsePanel && backIntercept
            ) { progress ->
                backGestureActive[0] = true
                backGestureStartP[0] = pNow()
                // 【⑤】限速状态清零（每次手势独立统计 maxΔp / max dp/dt；lastMs=0 = 首帧）
                backGestureLastMs[0] = 0L
                backGestureMaxDp[0] = 0f
                backGestureMaxRate[0] = 0f
                backGestureFrames[0] = 0
                val onSecondary = panelLicenses || panelImageEdit || panelComponentDemo || panelAdvanced
                AppDebugLog.log(
                    "LGBACK",
                    "begin p=%.3f secondary=%b p0=%.3f".format(pNow(), onSecondary, backGestureStartP[0])
                )
                try {
                    progress.collect { ev ->
                        if (onSecondary) {
                            // 二级页返回是【离散页切换】（与按钮同语义）⇒ 不接连续进度，只留日志
                            AppDebugLog.log("LGBACK", "progress=%.3f (secondary page, discrete pop)".format(ev.progress))
                        } else {
                            // 预测式返回进度 → 面板收起联动（p = p0×(1−progress)）
                            var target = (backGestureStartP[0] * (1f - ev.progress)).coerceIn(0f, 2f)
                            // 【⑤ 加速度上限·2026-09-25】每帧增量/速度上限（详见 [BACK_GESTURE_MAX_DP_PER_SEC]）：
                            //   dt 用相邻两个事件的真实间隔（elapsedRealtime）；首帧（lastMs==0）与
                            //   任何异常间隔（≤0 或 >200ms，例如被系统停顿）一律按【1 帧 16.7ms】折算
                            //   ⇒ 上限不会因 dt 异常被放大 ✗、也不会除 0 ✓
                            val nowMs = android.os.SystemClock.elapsedRealtime()
                            val dtMs = if (backGestureLastMs[0] == 0L) 0L
                            else (nowMs - backGestureLastMs[0]).coerceIn(0L, 200L)
                            backGestureLastMs[0] = nowMs
                            val curP = pNow()
                            val wantDp = target - curP
                            var applied = wantDp
                            if (DebugSwitches.backGestureRateCap) {
                                val dtSec = (if (dtMs <= 0L) 16.7f else dtMs.toFloat()) / 1000f
                                val cap = BACK_GESTURE_MAX_DP_PER_SEC * dtSec
                                applied = wantDp.coerceIn(-cap, cap)
                                target = curP + applied
                            }
                            val cur = anchorState.offset
                            val delta = offsetOfP(target) - (if (cur.isNaN()) 0f else cur)
                            if (delta.isFinite()) anchorState.dispatchRawDelta(delta)
                            // 【逐帧证据】带时间戳的 p(t) 与每帧 Δp / dp/dt（logcat 通道 LGBACK，
                            //   与 AppDebugLog 同步各写一份：前者不需要开调试模式即可取证 ✓）
                            val rate = applied / ((if (dtMs <= 0L) 16.7f else dtMs.toFloat()) / 1000f)
                            backGestureFrames[0]++
                            if (kotlin.math.abs(applied) > backGestureMaxDp[0]) backGestureMaxDp[0] = kotlin.math.abs(applied)
                            if (kotlin.math.abs(rate) > backGestureMaxRate[0]) backGestureMaxRate[0] = kotlin.math.abs(rate)
                            val line = ("t=%d dt=%.1fms prog=%.4f p=%.4f dp=%+.5f dpdt=%+.2f/s cap=%b wantDp=%+.5f")
                                .format(nowMs, dtMs.toFloat(), ev.progress, pNow(), applied, rate,
                                    DebugSwitches.backGestureRateCap, wantDp)
                            AppDebugLog.log("LGBACK", line)
                            android.util.Log.i("LGBACK", line)
                        }
                    }
                    // 流正常完成 = 返回已提交：与表头返回按钮同一条优先级链
                    val pop = when {
                        panelLicenses -> { panelLicenses = false; "licenses -> base" }
                        panelImageEdit -> { panelImageEdit = false; "imageEdit -> base" }
                        panelComponentDemo -> { panelComponentDemo = false; "componentDemo -> base" }
                        panelAdvanced -> { panelAdvanced = false; "advanced -> base" }
                        else -> { animatePanelTo(0f, panelAnimDurationMs(open = false)); "collapse -> p=0" }
                    }
                    backGestureSummary(backGestureFrames, backGestureMaxDp, backGestureMaxRate, "commit")
                    AppDebugLog.log("LGBACK", "commit %s".format(pop))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 手势取消（或作用域取消）：面板被进度拖动过就弹回起点 p（非挂起调用，取消态可执行）
                    if (!onSecondary && kotlin.math.abs(pNow() - backGestureStartP[0]) > 0.01f) {
                        animatePanelTo(backGestureStartP[0], panelAnimDurationMs(open = true))
                    }
                    backGestureSummary(backGestureFrames, backGestureMaxDp, backGestureMaxRate, "cancel")
                    AppDebugLog.log("LGBACK", "cancelled -> restore p0=%.3f".format(backGestureStartP[0]))
                    throw e
                } finally {
                    backGestureActive[0] = false
                }
            }
            val blockInteraction = remember { MutableInteractionSource() }
            val blockPressed by blockInteraction.collectIsPressedAsState()
            // 按压形变：轻微下压（垂直压扁多于水平收窄），松手弹回
            val blockPressScale by animateFloatAsState(
                targetValue = if (blockPressed) 1.10f else 1f,
                animationSpec = spring(dampingRatio = 0.40f, stiffness = 950f),
                label = "blockPressScale"
            )
            // 收起态按钮：触点位置 + 按压进度（驱动玻璃的液态形变）
            var buttonPressPos by remember { mutableStateOf(Offset.Zero) }
            val blockPressProgress by animateFloatAsState(
                targetValue = if (blockPressed) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.42f, stiffness = 820f),
                label = "blockPressProgress"
            )
            // 按下即开始非线性展开（用户要求"按下去之后展开为液态玻璃"），
            // 不再等抬手（原来的 clickable 是抬手才触发）。
            // 【乱飘修复】原来这里还要记 wasOpenBeforePress 让 onDragStart 去"作废"这次开 ✗ ——
            // 现在拖动与动画共用同一个官方状态，拖动接管时会自动取消这次动画，无需任何补偿逻辑 ✓
            LaunchedEffect(blockPressed) {
                AppDebugLog.log("PRESS", "blockPressed=$blockPressed target=%.1f".format(anchorState.targetValue))
                if (blockPressed && anchorState.targetValue < 0.5f) {
                    animatePanelTo(1f, panelAnimDurationMs(open = true))
                }
            }

            // 拖动/释放日志（保留原 DRAG / SNAP 通道，读数改自官方状态机）
            LaunchedEffect(panelDragging) {
                if (panelDragging) {
                    AppDebugLog.log("DRAG", "start p=%.3f  target=%.1f".format(pNow(), anchorState.targetValue))
                } else if (pNow() > 0.02f) {
                    AppDebugLog.log("SNAP", "release p=%.3f  就近锚点=%.1f".format(pNow(), anchorState.targetValue))
                }
            }
            // 【松手兜底·必须落到锚点】官方 fling / onPostFling 的 settle 若因任何原因没跑完
            // （手势节点被摘出组合、指针被系统取消…），这里在【屏幕级作用域】补一次 settle：
            // "松手一定落到最近锚点、不得停在中间值"的最后一道保险。
            // 【审计修复#1·看进度而不是看标志】isAnimationRunning == (dragTarget != null)，而手柄松手后的
            // 官方收口走的是【无 target】的 anchoredDrag{performFling} → 该标志恒为 false ✗
            // ⇒ 旧写法会在 96ms 处抢先 settle：同优先级抢占会【取消并重启】官方收口动画
            //   （中段速度断点、总时长变长，且落点可能被改成 closestAnchor ✗）。
            // 现在改成【多采样】：只要 offset 仍在推进（= 官方动画/极快 fling 还在跑）→ 绝不插手 ✓
            // 【加固·2026-09-14】原来只采样两次（96ms / +64ms）：极快 fling 若在两次采样【之间】
            //   刚好有位移，而采样点又恰好落在两次位移的间隙，仍有"看起来没动"的假象 ⇒
            //   这里改成 3 次 16ms 采样（≈ 1 帧间隔）：任一次出现位移就立刻退出，绝不抢跑 ✓
            //   （16ms 的采样间距保证"上一次 offset 变化距今 < 1 帧"的情况下必然被看到 ✓）
            LaunchedEffect(panelDragging) {
                if (panelDragging) return@LaunchedEffect
                kotlinx.coroutines.delay(96)
                val off1 = anchorState.offset
                if (off1.isNaN() || anchorState.isAnimationRunning) return@LaunchedEffect
                var prev = off1
                repeat(3) {
                    kotlinx.coroutines.delay(16)
                    val cur = anchorState.offset
                    if (cur.isNaN() || cur != prev) return@LaunchedEffect
                    prev = cur
                }
                val nearest = anchorState.anchors.closestAnchor(prev) ?: return@LaunchedEffect
                if (kotlin.math.abs(prev - anchorState.anchors.positionOf(nearest)) < 0.5f) return@LaunchedEffect
                AppDebugLog.log("SNAP", "兜底收口 off=%.1f → p=%.1f".format(prev, nearest))
                anchorState.settle(panelSnapSpec)
            }

            LaunchedEffect(showControlCenter) {
                AppDebugLog.log("STATE", "showControlCenter=$showControlCenter  p=%.3f".format(pNow()))
                // 关闭面板时重置回一级，下次从基础菜单开始
                if (!showControlCenter) {
                    panelAdvanced = false
                    panelImageEdit = false
                    panelComponentDemo = false
                    panelLicenses = false
                }
            }
            // （面板几何常量已上移到"进度 p 的唯一持有者"处：densityScale / collapsedWidthPx /
            //  collapsedHeightPx / collapsedRadiusPx / expandedHeightPx / fullHeightPx /
            //  panelSideMarginPx / fullWidthPx —— 拖动锚点与形态插值必须共用同一套常量）
            // 内容高度：开合 morph 阶段锁定终态尺寸（原本如此）；上滑展开阶段现在【也不再增长】——
            // 内容子树的测量尺寸恒为终态尺寸（揭幕式，见下方内容容器 .layout{}），面板自身的 hOf(p)
            // 照旧逐帧增长，多出的高度全部交给外层裁剪逐帧揭开（观感逐帧等价，见放置规则）✓
            // ⚠️ 必须直接读 rootSizePx（状态）：若引用外部组合期算出的 expandedHeightPx/fullHeightPx，
            // 首帧 rootSizePx 还是 0x0 → 高度恒为 0dp → 面板内容被压成 0 高（真机实测 size=1880x0）。
            // 【性能修复】原实现是 remember{derivedStateOf{pNow()}} 并在【组合期】读
            // （.size(height = contentHeightDp.dp)）：p∈(1,2] 时输出每帧都变 → 整棵面板内容子树
            // （几十个控件）每帧重组 ✗。现在改成纯函数，只在【layout 阶段】读（见下方内容容器 .layout{}），
            // 组合期完全不订阅 p；几何与原来一致（原来 px/densityScale → dp 再转回 px，现直接给 px）✓
            // ⚠️ 揭幕式（DebugSwitches.revealContentMeasure=true，默认）下，调用点只取 p=0f 这一档
            //   （恒定终态尺寸，与 p 无关）；下面 p∈(1,2] 的增长分支仅在回退开关关闭时才使用。
            fun contentHeightPxOf(p: Float): Int {
                val h = rootSizePx.height
                val px = if (p <= 1f) h * panelHeightFraction
                         else lerp(h * panelHeightFraction, h * panelFullHeightFraction, (p - 1f).coerceIn(0f, 1f))
                return px.toInt().coerceAtLeast(1)
            }
            // ===== 关键：组合阶段绝不读动画值 =====
            // 原来这里是 `val rawProgress = dragProgress ?: morph.value` 加十几个派生值：
            // 组合阶段读 morph/dragProgress 会让 Compose 每帧重组【整个屏幕】
            // （两张玻璃卡 + 性能看板 + 面板几十个控件）——这是 50th 26ms 的真正来源。
            // 现在全部改成 p 的纯函数，只在 layout/draw lambda 里调用。
            fun wOf(p: Float): Int {
                val mp = p.coerceIn(0f, 1f)
                return lerp(collapsedWidthPx, fullWidthPx, 1f - (1f - mp).pow(2.2f)).toInt().coerceAtLeast(1)
            }
            fun hOf(p: Float): Int {
                val mp = p.coerceIn(0f, 1f)
                val he = mp * mp * (3f - 2f * mp)
                val v = if (p <= 1f) lerp(collapsedHeightPx, expandedHeightPx, he)
                        else lerp(expandedHeightPx, fullHeightPx, (p - 1f).coerceIn(0f, 1f))
                return v.toInt().coerceAtLeast(1)
            }
            // ===== 【控制中心展开后半段卡顿·主因修复】内容槽的尺寸与位移（layout / draw 两阶段共用同一组纯函数）=====
            // 背景（实测）：p∈(1,2] 时旧实现把本节点的【返回尺寸】设成 hOf(p)（逐帧长高）⇒ 本节点每帧
            //   size 变化 ⇒ 它的图层（graphicsLayer）被 invalidate ⇒ 整棵设置面板内容（几十个控件、
            //   自身都没有独立图层）每帧被重录 = RT 4.5~4.7ms/帧；而"只把子项 constraints 钉死"
            //   （DebugSwitches.revealContentMeasure）对 RT 耗时零差别 ⇒ 问题在本节点的尺寸/摆放，不在子项测量。
            // 修法：本节点 layout 尺寸整段 p 恒为常量（contentSlotHeightPxOf），p 驱动的位移改到绘制期
            //   translationY（contentSlotShiftYOf）：RenderNode 属性变更不使 display list 失效 ⇒ 零重录。
            // 观感等价性证据：同一状态下 p=1.0 / 1.5 / 2.0 三张截图里，面板内"深色内容行"相对【面板顶边】
            //   的位置逐行完全一致（位移 0px、无拉伸）⇒ p>1 多出来的面板高度在旧实现里本来就是空白 ✅
            // ⚠️ 这三个必须是【局部 fun 且定义在 hOf 之后】（Kotlin 局部函数不支持前向引用）。

            /**
             * 【回归修复·页相关槽高常量】内容槽高度（px）—— 只随"当前页"这一【离散事件】变，
             * 在同一页内对 p∈[0,2] 恒定（P04 性能前提 ✓）。
             *   · 一级基础菜单 → contentHeightPxOf(0f)：= 屏高×panelHeightFraction（p=1 档面板高）
             *   · 二级「更多设置」 → fullHeightPx：= 屏高×panelFullHeightFraction（p=2 档面板高）
             *
             * 依据（模拟器 1840×2944 实测，2026-09-14）：修复前（contentFixedSlot=true）把槽高
             * 钉成 1619px，而 p=2 时面板高 2885px ⇒ 内容只铺到"面板顶+1619px"、下面 1266px 全空 ✗
             * （逐行墨量实测：y1600~2800 全 0）＝ 用户报的「更多设置只显示上半屏」✗✓
             * 一级页内容本来就短（自然高度约 1407px < 1415 视口），看不出；二级页列表很长 ⇒ 最显眼。
             *
             * 为什么取 fullHeightPx：二级页要铺满「上滑展开」后的面板（这是用户展开面板的目的）；
             * panelFullHeightFraction 与 hOf(p=2) 同源 ⇒ 槽底 ≡ 面板底（含 64px 导航栏内边距）✓
             * ⚠️ 页切换只重排/重录一次（点按进入/返回），动画期间槽高恒定 ⇒ 掉帧修复不受影响 ✓
             * ⚠️ 必须定义在 contentSlotHeightPxOf 之前（Kotlin 局部函数不支持前向引用）✓
             */
            fun contentPageSlotPx(): Int {
                // 【P38·演示页槽高修复】「组件演示」页现在是【紧凑独立演示场】（~1.4k px，
                //   不需要面板全高）⇒ 槽高按【本页需要的终态高度】取 p=1 档（contentHeightPxOf(0f)）。
                //   根因（本轮实测，clean-room 构建 + 布局探针 P38L）：
                //     槽高被钉在 p=2 档（fullHeightPx=2885）而 p=1 面板只有 1619px ⇒ 内容容器
                //     【比面板高】，实测摆放 top=692（= 面板中心 − 2885/2 ⇒ 被【垂直居中】✗）
                //     ⇒ 页首（表头 + 演示场顶部）整整 633px 落到面板上缘之外、且拖不回来 ✗
                //     （对照：一级页槽高 1619 = 面板高 ⇒ top=1325 = 面板顶 ✓ 完全可见）。
                //   修法：本页槽高 = contentHeightPxOf(0f)（与一级页同档）⇒ 槽 ≤ 面板 ⇒ 顶边贴面板顶 ✓
                //   回退：DebugSwitches.liquidDemoStageSlot=false（一行，回到 fullHeightPx 旧行为）✓
                //   ⚠️ 只作用于【本页】：其它二级页（更多设置/图片编辑/许可）的槽高一字不动 ✓
                val demoCompactSlot = panelComponentDemo &&
                    DebugSwitches.liquidDemoStage && DebugSwitches.liquidDemoStageSlot
                return if (DebugSwitches.contentSlotAdaptivePage &&
                    // 【P55·一级基础页同用满高槽】默认开：一级页沿用 contentHeightPxOf(0f) 时，
                    //   p=2（面板 2885px）下内容只铺到"面板顶+1619px"、列表视口 1407 < 内容 1512
                    //   ⇒ 末尾两行被推到可视下沿之外（用户报「全屏状态下只显示一半」）。
                    //   一行回退：DebugSwitches.contentSlotBasePage=false。
                    (DebugSwitches.contentSlotBasePage ||
                        panelAdvanced || panelImageEdit || panelLicenses ||
                        (panelComponentDemo && !demoCompactSlot))
                ) {
                    fullHeightPx.toInt().coerceAtLeast(contentHeightPxOf(0f))
                } else {
                    contentHeightPxOf(0f)
                }
            }

            /**
             * 内容容器的【布局尺寸】（px）。
             * contentSlotAdaptivePage=true（默认，新增）= 【随当前页】取常量：
             *   一级基础菜单 → contentHeightPxOf(0f)（= 修复前行为，零回归面）；
             *   二级「更多设置」→ fullHeightPx（= p=2 档面板高）⇒ 面板上滑到满高后内容照样铺满，
             *   不再出现"下半屏空白"（回归修复，见 DebugSwitches.contentSlotAdaptivePage）。
             *   ⚠️ 关键：同一个页面内与 p 无关（p∈[0,2] 恒定）—— 这是 P04 性能收益的命根子 ✓
             * contentFixedSlot=true（旧默认）= 两页都恒为 contentHeightPxOf(0f)（修复前行为）；
             * false = 旧行为（槽高随 p 变；revealContentMeasure 开时取 max(面板高, 终态高)）。
             *
             * 【H3·优先级与「谁吞了谁」（✗ 数值/公式未动，只写口径）】when = 首命中即返回：
             *   ① contentSlotAdaptivePage（默认开）= 第一分支 ⇒ 它一开，②③【永不被查询 = 死分支】；
             *   ② contentFixedSlot（默认开）⇒ 仅 ①关 后是决策项，且吞掉 ③（首命中）；
             *   ③ revealContentMeasure（默认 false·「勿打开」档）⇒ 仅 ①关 ∧ ②关 时可达
             *      （打开 = 已知缺陷「更多设置只显示上半屏」，见 DebugSwitches 的 KDoc）。
             */
            fun contentSlotHeightPxOf(p: Float): Int = when {
                DebugSwitches.contentSlotAdaptivePage -> contentPageSlotPx()
                DebugSwitches.contentFixedSlot -> contentHeightPxOf(0f)
                DebugSwitches.revealContentMeasure -> maxOf(hOf(p), contentHeightPxOf(0f))
                else -> contentHeightPxOf(p)
            }

            /** 内容子树的【测量高度】（px）：恒定槽位 = 槽高（等价于揭幕式）；否则沿用旧行为。
             *  【H3·优先级】与 `contentSlotHeightPxOf` 同源：① 吞 ②③（本式里 ②③ 已合并成一支）；
             *  ③（revealContentMeasure）仍只在 ①关 ∧ ②关 时可达。✗ 数值未动。 */
            fun contentMeasureHeightPxOf(p: Float): Int = when {
                DebugSwitches.contentSlotAdaptivePage -> contentPageSlotPx()
                DebugSwitches.contentFixedSlot || DebugSwitches.revealContentMeasure -> contentHeightPxOf(0f)
                else -> contentHeightPxOf(p)
            }

            /**
             * 内容槽在【绘制期】的位移 translationY（px）= 旧实现里 .align(BottomCenter) 给出的摆放 y
             * （hOf(p) - 摆放用槽高）。基座摆放改成恒定的 (0,0) 后，位移必须自己补上：
             *   · p≤1：= h - 常量终态高（≤0）⇒ 内容【底边贴面板底边】（自下而上逐层揭开，原观感 ✓）
             *   · p≥1：= 0 ⇒ 内容【顶边贴面板顶边】（标题/列表随顶上浮，原观感 ✓）
             * 只用 RenderNode 的 translationY 属性表达 ⇒ 不新增布局、不触发重排/重录 ✓
             * 【页自适应槽（默认）】锚点改用 contentHeightPxOf(0f)（= p=1 档面板高），写成
             *   min(0, hOf(p) - 锚点)：p≥1 恒 0、p≤1 与旧式逐值相同 ⇒ 一级页逐像素等价 ✓；
             *   二级页槽更长时，内容【顶边】仍钉在同一锚点 ⇒ p=1 的可见像素也不变（槽只向下变长）✓
             */
            fun contentSlotShiftYOf(p: Float): Float {
                if (DebugSwitches.contentSlotAdaptivePage) {
                    val anchor = contentHeightPxOf(0f)
                    return (hOf(p).coerceAtMost(anchor) - anchor).toFloat()
                }
                val hPlace = if (DebugSwitches.contentFixedSlot) maxOf(hOf(p), contentHeightPxOf(0f))
                             else contentSlotHeightPxOf(p)
                return (hOf(p) - hPlace).toFloat()
            }

            /**
             * 【P43·半屏显示不全·根因修复】「更多设置」类二级页列表的【底部余量】(px)。
             *
             * 根因（实测数字见报告）：二级页的内容槽高 = contentPageSlotPx() = p=2 档面板高
             *   （模拟器 1840×2944 ⇒ 2885px），而在【半屏 p=1】时面板只有 1619px（可见内容区
             *   ≈1407px）⇒ 列表的滚动视口（= 槽高 - 表头 - 导航栏内边距 = 2673px）比可见区高出
             *   1266px：滚动到底时内容最后一行停在屏幕外 1154px 处，永远看不到 ✗（用户报的
             *   「拉到半屏的时候显示不全」）。
             * 修法：把这段"多出来的视口高度"补成列表内容末尾的余量 —— 即
             *   pad(p) = max(0, 槽高 - max(面板高, p=1 档面板高))。
             *   · p=1（半屏）→ pad = 2885-1619 = 1266 ⇒ 可滚范围 3095→4361 ⇒ 最后一行停在
             *     屏幕底边上（y≈2880），**半屏也能滚到底看全** ✓；
             *   · p=2（满高）→ pad = 0 ⇒ 与改动前逐字相同（内容照旧铺满面板）✓；
             *   · p∈(1,2] 连续变化 ⇒ 滚动到底时最后一行【屏幕 y 恒为 2880】⇒ 展开过程零跳变 ✓
             *     （推导：视口底 = 屏底 - 面板高 + 常量；末行 = 视口底 - pad，代入 pad 后与 p 无关）；
             *   · p ≤ 1 冻结成常量（用 max(面板高, p=1 档高)）⇒ 开合动画期间 pad 不变、
             *     槽高仍只随"当前页"变 ⇒ P04 的"槽高恒定"性能前提一条没破 ✓；
             *   · 一级基础菜单槽高 = p=1 档高 ⇒ pad ≡ 0 ⇒ 一级页逐像素/逐值零回归 ✓。
             * ⚠️ 只在【layout 阶段】被调用（GlassControlsPanel 的 layout 修饰符内）⇒ 组合期不订阅
             *   p，不产生逐帧重组 ✓（与 contentSlotShiftYOf 同一套规矩）。
             * 回退：DebugSwitches.listScrollPad（默认 true）关掉 ⇒ pad 恒 0 = 改动前行为。
             */
            val listBottomPadPxState = rememberUpdatedState<() -> Int> {
                // 【调试开关热更】本 lambda 只在 layout 阶段被调用；DebugSwitches 是普通 @Volatile
                // 字段（读它不产生任何订阅）⇒ 这里必须陪读一次 setSwitches 会自增的 revision
                // （快照状态 ✓）——否则运行中翻 listScrollPad 不会让本节点失效重算，A/B 会读到陈旧值 ✗
                // （与容器裁剪层 / 面板玻璃层的热更机制同源）。
                @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                if (!DebugSwitches.listScrollPad) return@rememberUpdatedState 0
                val p = pNow()
                val visibleH = maxOf(hOf(p), contentHeightPxOf(0f))
                (contentSlotHeightPxOf(p) - visibleH).coerceAtLeast(0)
            }

            /**
             * 【P43·半屏页首被裁·根因修复】内容容器的"垂直居中"补偿位移（px，向下为正）。
             *
             * 实测（应用自身 P38L 日志，模拟器 1840×2944，更多设置页）：
             *   contentBox top = 692 @p=1.00 / 376 @p=1.50 / 59 @p=2.00，h=2885（= 槽高）
             *   ⇒ 容器顶部 = 面板顶 + (面板高 - 槽高)/2 —— 即容器被【垂直居中】在面板里，
             *     而不是代码注释里写的"顶边贴面板顶边"✗。
             * 后果（半屏 p=1，槽高 2885 > 面板 1619）：容器顶落在 692 = 面板顶(1325) 之上 633px
             *   ⇒ ① 面板表头（把手 + 标题「更多设置」+「‹ 基础」返回键）整条被推到面板上缘之外、
             *      半屏下完全看不见 ✗；② 页面最前面 ~633px 内容被裁掉，而列表已经在顶部（scroll=0）
             *      ⇒ 那一段**永远看不到** ✗ = 用户报的「拉到半屏的时候显示不全」的前半截 ✗✓
             *      （后半截 = 页尾滚不到底，见 DebugSwitches.listScrollPad）。
             *
             * 修法：把这半个差值补回来 —— 只在【绘制期】加到已有的 translationY 上（RenderNode 属性，
             *   不新增布局、不触发重排/重录 ✓，与 contentSlotShiftYOf 同一套机制）：
             *   comp(p) = max(0, (槽高 - 面板高)/2)，且【仅当该页用满高槽】（一级基础菜单槽高 =
             *   p=1 档高 ⇒ comp ≡ 0 ⇒ 一级页逐像素零回归 ✓✓）。
             *   · p=1 → +633 ⇒ 容器顶回到 1325 = 面板顶 ⇒ 表头 + 页首全部可见 ✓；
             *   · p=2 → 0（本来就对齐 ✓ 零改动）；
             *   · p∈(1,2] 连续 ⇒ 展开过程无跳变 ✓；
             *   · 与 contentSlotShiftYOf 相加后，p≤1 仍是"内容底边贴面板底边"的自下而上揭开 ✓。
             * 回退：DebugSwitches.panelSlotCenterFix（默认 true）关掉 ⇒ comp ≡ 0 = 改动前行为。
             */
            fun contentSlotCenterFixPxOf(p: Float): Float {
                if (!DebugSwitches.panelSlotCenterFix) return 0f
                val slot = contentSlotHeightPxOf(p)
                if (slot <= contentHeightPxOf(0f)) return 0f      // 一级页（无满高槽）⇒ 恒 0（零回归）
                return ((slot - hOf(p)) * 0.5f).coerceAtLeast(0f)
            }


            /**
             * 【首次组合开销·方案A·输入隔离】把内容子树"停到屏幕外"时的位移（px，向下为正）。
             * 收起到底（panelEngaged=false）时专用：命中测试按【摆放位置】换算坐标 ⇒ 子树离开屏幕
             * 就不在任何触摸坐标里 ⇒ 收起态胶囊的点击/拖动语义与"内容未组合"逐字相同 ✓
             * （历史坑：不可见的列表替胶囊吃掉点击 → 点不开面板 ✗）。
             * 取值 = max(2×屏高, CONTENT_PARK_MIN_PX)：内容槽高 = 0.55×屏高且底边贴面板底边 ⇒
             * 2×屏高 足以让整棵子树（含溢出部分）离开屏幕；分辨率/横竖屏变化自动跟随，不写死像素。
             */
            fun parkOffsetPx(): Float = maxOf(rootSizePx.height * 2f, CONTENT_PARK_MIN_PX)
            fun liftOf(p: Float): Float {
                val mp = p.coerceIn(0f, 1f)
                return lerp(navBarBottomPx + 18f * densityScale, 0f, mp * mp * (3f - 2f * mp))
            }
            fun topROf(p: Float): Float {
                val mp = p.coerceIn(0f, 1f)
                return lerp(collapsedRadiusPx, 28f * densityScale, mp * mp * (3f - 2f * mp))
            }
            fun bottomROf(p: Float): Float {
                val mp = p.coerceIn(0f, 1f)
                return lerp(collapsedRadiusPx, 0f, mp * mp * (3f - 2f * mp))
            }
            fun contentAlphaOf(p: Float): Float = ((p.coerceIn(0f, 1f) - 0.42f) / 0.58f).coerceIn(0f, 1f)
            // 【已回退无缝交棒】标签恢复原来的淡出曲线（用户：无缝动画先不做，
            // 展开曲线保留 ✓）
            fun labelAlphaOf(p: Float): Float = (1f - p.coerceIn(0f, 1f) * 2.6f).coerceIn(0f, 1f)
            fun sstep(a: Float, b: Float, x: Float): Float {
                val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
                return t * t * (3f - 2f * t)
            }
            fun glassAlphaOf(p: Float): Float {
                val mp = p.coerceIn(0f, 1f)
                // 两端稳定态玻璃全开；中段交给浅色填充。过渡用 smoothstep（原来是分段线性，
                // 实测亮度轨迹 77 → 160 在 0.16s 内跳完，用户反馈"亮度变化是跳跃式的"）。
                // 关键：玻璃层的"开关门控"是 p<0.16 / p>0.88（为帧率只在两端渲染玻璃）。
                // 淡出曲线必须【压进门控之内】：0.14 前淡到 0、0.885 后才回升，
                // 这样门控切换的那一刻玻璃强度恰好是 0，不会出现"玻璃啪地消失"的亮度跳变。
                // 面板态：玻璃层让位（否则折射会把壁纸透上来、面板变暗、"字看不清"）；
                // 收起态：玻璃层满强度 = 按钮的液态玻璃。
                return 1f - sstep(0.06f, 0.30f, mp)
            }
            // 用户要求：收起态的"控制中心"展开按钮 与 展开后的面板白底【同一色号】，
            // 展开时更无缝 → 填充层不透明度不再随形态归零（原来 p→0 时归 0 ✗，
            // 按钮因此完全没有材质、玻璃感消失 ✗）。
            // 【深色/亮色模式·2026-09-24·玻璃染色】深色档把【填充层不透明度】整体抬高一档：
            //   0.35 → 0.78（收起态）/ 0.90 → 0.92（展开态）。理由（实测硬约束）：
            //   收起态胶囊字在深色档是【白】字，白字要 ≥4.5:1 就需要字形紧邻背景足够暗；
            //   0.35 的深色填充叠在【近白壁纸】上算出来仍 ≈166/255（L≈0.42）⇒ 白字必然 1.6:1 ✗，
            //   与填充【颜色】无关（0.65×255 的透射项本身就超过门槛）。抬到 0.78 后
            //   0.78×51 + 0.22×255 ≈ 96/255（L≈0.115）⇒ 白字 ≈5.9:1 ✓。
            //   ✗ 曲线形状与时间调度【逐字未变】（仍是 0.10→0.70 的 smoothstep），只改了深色档的两个端点值
            //   ⇒ 属"深色档重新定一套调色"里的【玻璃染色】项（任务书明确列出可改 ✓）；
            //   ✗ 不动几何/圆角/尺寸/折射参数；亮色档表达式逐字保留在 else 分支 ✓。
            // 【① 胶囊实底灰化·2026-09-25 用户要求】收起态（p→0）的胶囊填充【不再半透明】：
            //   用户原话「你现在做成白色透明的控制中心胶囊顶，还不如直接做成纯灰色的」✗ →
            //   现在收起态 = 【纯灰实底】（不透明），灰值 = 本档调色板的 panelFill
            //   （亮档 0xFFC6CAD0 / 暗档 0xFF333A45）—— 两个理由：
            //     ① 与展开后的面板填充【同一色号】⇒ 开合全过程零色相跳变，只在不透明度上收放，
            //        胶囊与面板是同一个元素，观感"协调"不脏 ✓；
            //     ② 两档都是中性灰（亮档明度 202/255 ≈79%、暗档 51/255——暗档刻意不用纯黑，
            //        用户对"暗沉一片"极其反感 ✗）。黑字/白字在这两块灰上的 WCAG 标称对比
            //        亮档 ≈12.8:1 / 暗档 ≈11.5:1 ⇒ 不再依赖壁纸、也不依赖任何补光 ✓
            //   ✗ 只改【胶囊填充色/不透明度】这一个量：几何/尺寸/圆角/阴影/贴框/交棒路径 ✗ 未动；
            //   ✗ 展开态端点的填充不透明度【逐值未变】（亮 0.90 / 暗 0.92）⇒ 面板那一段
            //      （p≥0.70）逐像素不变 ✓（玻璃层玻璃光学参数同样一字未动 ✓）。
            //   一行回退：setSwitches capsuleSolidFill 0 ⇒ 回到改动前的 0.35/0.78 收起态半透明填充。
            fun fillAlphaOf(p: Float): Float {
                val mp = p.coerceIn(0f, 1f)
                return if (DebugSwitches.capsuleSolidFill) {
                    // 收起态 1.00（实底）→ 展开态端点与改动前逐值相同（亮 0.90 / 暗 0.92）
                    if (glassPal.isDark) (1.00f - 0.08f * sstep(0.10f, 0.70f, mp)).coerceIn(0f, 1f)
                    else (1.00f - 0.10f * sstep(0.10f, 0.70f, mp)).coerceIn(0f, 1f)
                } else if (glassPal.isDark) {
                    (0.78f + 0.14f * sstep(0.10f, 0.70f, mp)).coerceIn(0f, 1f)   // 改动前（深色档）
                } else {
                    (0.35f + 0.55f * sstep(0.10f, 0.70f, mp)).coerceIn(0f, 1f)   // 改动前（亮色档）
                }
            }
            // 【① 胶囊实底色】收起态填充色 = 本档 capsuleSolid（纯灰实底），随形态在 p∈[0.10,0.70]
            //   平滑回到 panelFill ⇒ p≥0.70 的面板填充色【逐值未变】（面板零回归 ✓）；
            //   亮档两个取值本就相同（0xFFC6CAD0）⇒ 亮档这一路是恒等变换、逐帧零差异 ✓。
            //   ✗ 只改"胶囊填充"这一个量，几何/圆角/阴影/贴框/交棒/玻璃光学一律未动 ✓
            fun fillColorOf(p: Float): Color {
                if (!DebugSwitches.capsuleSolidFill) return glassPal.panelFill
                val t = sstep(0.10f, 0.70f, p.coerceIn(0f, 1f))
                val a = glassPal.capsuleSolid
                val b = glassPal.panelFill
                if (t <= 0f) return a
                if (t >= 1f) return b
                return Color(
                    red = a.red + (b.red - a.red) * t,
                    green = a.green + (b.green - a.green) * t,
                    blue = a.blue + (b.blue - a.blue) * t,
                    alpha = a.alpha + (b.alpha - a.alpha) * t
                )
            }
            // 玻璃层只在【收起的稳定态】与【展开的稳定态】开启，过渡区间的跨度收窄到
            // 0.06~0.14 / 0.90~0.98：只要玻璃参与、而形态还在变，Backdrop 捕获层就会
            // 每帧重录 → UI 线程 27ms/帧（实测）。中间段交给等效暗底 + 交叉淡入淡出，
            // 观感平滑且不掉帧。
            // 门控只读布尔派生状态：动画期间每帧读 Float，会让下面两个 if 每帧重组整个面板
            // 内容子树（几十个控件）——这是展开动画卡顿的根因；derivedStateOf 只在布尔翻转时通知组合。
            // 【性能修复】原先这里是 diagLastW/H = mutableIntStateOf（快照状态），却在 .layout{} 里【写】：
            // 布局阶段写快照状态会让该 layout 节点再失效一轮（每帧多跑一次布局），日志字符串也每帧拼接 ✗。
            // 改为 remember 承载的普通 IntArray（非快照状态 → 不产生任何订阅/失效），
            // 且只在 AppDebugLog.enabled（@Volatile 普通字段，读取零订阅）打开时才拼字符串打日志 ✓
            val diagShape = remember { intArrayOf(-1, -1) }   // [0]=上次打印的 w，[1]=上次打印的 h（诊断用）

    val glassVisible by remember {
                derivedStateOf {
                    val p = pNow()
                    p < 0.16f || p > 0.88f
                }
            }
            // 【性能·面板玻璃层组合门控】面板玻璃层只在它真实可见（alpha>0.001）时才参与组合。
            // 推导（见报告）：glassAlphaOf(p) = 1 - sstep(0.06, 0.30, p) → p≥0.30 时恒为 0；
            // alpha>0.001 仅当 p < p* ≈ 0.2955911（反解 (1-t)²(1+2t)=0.001，t=(p-0.06)/0.24）。
            // 门控与绘制期 alpha 同源（同一个 glassAlphaOf(pNow())）→ 翻转帧与旧行为像素一致，
            // 不会出现"该淡入却掉了玻璃"的帧。布尔派生状态：p 逐帧变化不通知组合，只在翻转时重组一次。
            val panelGlassVisible by remember {
                derivedStateOf { glassAlphaOf(pNow()) > 0.001f }
            }
            val labelVisible by remember {
                derivedStateOf { pNow() < 0.38f }
            }
            // 【收起态黑字·2026-09-23】静置收起（p==0）的布尔门：组合期只读布尔派生状态（与 labelVisible 同模式）。
            // true 时收起态标签（玻璃之上）显示纯黑字、交棒实例 alpha=0 让位（见下方两处标注）。
            val capsuleRest by remember {
                derivedStateOf { pNow() <= 0f }
            }
            val contentVisible by remember {
                derivedStateOf { pNow() > 0.42f }
            }
            // 【次因修复】打开方向下列表提前入组合用的更早阈值（布尔派生状态：p 逐帧变化不通知组合，
            // 只在跨过 0.10 的那一帧重组一次 ⇒ 与旧门控同样的"只在翻转时重组"开销特征）。
            val listEarlyVisible by remember {
                derivedStateOf { pNow() > LIST_EARLY_COMPOSE_P }
            }
            // 【卡半路·根因修复】内容层组合门控的硬指标：offset 只要离"胶囊锚点(0f)"超过半像素，
            // 就说明还有拖动 / 展开收起动画 / 松手收口在跑 —— 此时内容必须保持组合。
            // 因为手柄的 anchoredDraggable 与列表的 scrollable 都长在内容子树里：内容一旦被摘出组合，
            // 正在跑的手势与 onPostFling 里的 settle 会被一并取消，p 就永久停在中间值
            // （真机实测 0.389，刚好落在旧门控 p>0.42 之下）✗ → 回不到胶囊。
            // 布尔派生状态：p 逐帧变化不通知组合，只在"贴底⇄离底"翻转时重组一次。
            val panelEngaged by remember {
                derivedStateOf {
                    val off = anchorState.offset
                    !off.isNaN() && off > 0.5f
                }
            }
            // 列表（GlassControlsPanel）自己的滚动拖动是否在进行：由 sheetConnection 置位/清零。
            // 用途：内容不可见（p<0.42）但列表正在被拖动时必须保留列表组合 —— 手势处理者长在它的
            // scrollable 上，摘掉就等于取消这次拖动（与上面同源）。
            var sheetDragging by remember { mutableStateOf(false) }

            // ===== 【首次组合开销·方案A/B】两个状态 + 两条副作用（详见 DebugSwitches 的对应注释）=====
            // 背景：设置列表【首次组合】= 单帧 42~67ms（4/4 次可复现）。P04 的 listComposeEarly 只是把它
            // 从 p≈0.42 挪到 p≈0.10 —— 两者都还在【动画里】⇒ 用户"刚打开控制中心时卡一下"✗。
            // 本轮把这一帧彻底移出动画区间：或在启动时的静止窗口预热（方案A），或推迟到面板定格之后（方案B）。
            // 两个状态都是布尔量 ⇒ 组合期零订阅开销（不读任何动画浮点值 ✓）。

            // 【方案A·预热 + 常驻】首帧之后等 2 帧点亮（不与冷启动首帧抢预算），点亮后不再回退 ⇒
            //   "本次运行里列表已经组合过" ⇒ 之后每次打开都是零组合开销（配合下方"收起态停到屏幕外"）。
            var listWarm by remember { mutableStateOf(false) }
            LaunchedEffect(DebugBridge.revision.intValue) {
                if (!DebugSwitches.precomposeListKeepAlive) return@LaunchedEffect
                // 等 2 帧 + 300ms：让冷启动的"首帧 / 背景解码 / 玻璃首绘"先落定，预热帧不与它们同帧
                // （预热本身 = 一次全屏重组 + 列表首次组合，实测一个帧的量级；放在启动静止窗口里
                //   几乎没有位移参照 ⇒ 不可察觉 ✓）。窗口内若用户就打开了面板，由方案B（推迟到定格）兜住 ✓
                repeat(2) { withFrameNanos { } }
                kotlinx.coroutines.delay(LIST_WARMUP_DELAY_MS)
                listWarm = true
                AppDebugLog.log("LIST", "预组合预热完成（方案A）p=%.3f".format(pNow()))
            }
            // 【方案B·推迟到静止】打开方向：等"面板已定格"再放列表进组合。
            //   判据（不看 isAnimationRunning，见 DebugSwitches 注释）= offset 连续两次采样完全相同
            //   + ±0.5px 贴住锚点 + p≥0.98；另有 LIST_DEFER_TIMEOUT_MS 兜底，保证列表一定出现 ✓
            var listDeferredReady by remember { mutableStateOf(false) }
            LaunchedEffect(showControlCenter) {
                if (!DebugSwitches.deferListComposeUntilSettled || !showControlCenter) {
                    listDeferredReady = false
                    return@LaunchedEffect
                }
                val t0 = android.os.SystemClock.elapsedRealtime()
                var prev = Float.NaN
                while (true) {
                    kotlinx.coroutines.delay(PANEL_SETTLE_SAMPLE_MS)
                    val off = anchorState.offset
                    val stable = !off.isNaN() && off == prev
                    prev = off
                    if (stable && pNow() >= 0.98f) {
                        val nearest = anchorState.anchors.closestAnchor(off)
                        if (nearest != null &&
                            kotlin.math.abs(off - anchorState.anchors.positionOf(nearest)) < 0.5f
                        ) {
                            listDeferredReady = true
                            AppDebugLog.log("LIST", "静止判定通过（方案B）off=%.1f p=%.3f".format(off, pNow()))
                            return@LaunchedEffect
                        }
                    }
                    if (android.os.SystemClock.elapsedRealtime() - t0 > LIST_DEFER_TIMEOUT_MS) {
                        listDeferredReady = true
                        AppDebugLog.log("LIST", "静止判定超时→强制组合（方案B）off=%.1f".format(anchorState.offset))
                        return@LaunchedEffect
                    }
                }
            }
            // 【列表入组合的最终门控】组合期只读布尔量 ⇒ 只在翻转的那一帧重组一次（与旧门控同特征）。
            // 与今天的行为差异【只有】下面两条新增项：
            //   · 方案A 的 listWarm = 预组合常驻；
            //   · 方案B 的 listDeferredReady = 打开方向推迟到"面板已定格"后（此时 listComposeEarly 让位）。
            // 其余四条（contentVisible / panelDragging / sheetDragging / 打开方向的 listComposeEarly）
            // 与今天逐字一致 ⇒ 两个开关都置 false = 完全回到今天的行为 ✓（可一键回退）
            val listComposeGate: Boolean = contentVisible || panelDragging || sheetDragging ||
                (DebugSwitches.precomposeListKeepAlive && listWarm) ||
                (DebugSwitches.deferListComposeUntilSettled && showControlCenter && listDeferredReady) ||
                (DebugSwitches.listComposeEarly && !DebugSwitches.deferListComposeUntilSettled &&
                    showControlCenter && listEarlyVisible)
            // 【H3·优先级与「谁吞了谁」（✗ 数值/门限未动，只写口径）】上面三项作用【同一行为】：
            //   ① precomposeListKeepAlive（默认开）：冷启动 ~300ms（LIST_WARMUP_DELAY_MS）后 listWarm
            //      恒真 ⇒ 本项恒真、gate 永真 ⇒ ②③ 从此【让位】：不是"翻它没效果"，而是 keepAlive
            //      生效之后它们不再被查询（要拍 ②③ 的 A/B 必须先关 keepAlive ✗ 否则假阴性）；
            //   ② deferListComposeUntilSettled（默认开）：只在 ① 尚未生效的窗口里是决策项；
            //      与 ③ 互斥（③ 项自带 !defer）⇒ ② 开且生效时 ③ 被吞；
            //   ③ listComposeEarly（默认开）：仅当 ①未生效 ∧ ②关 时可达（打开方向 p>LIST_EARLY_COMPOSE_P）。
            //   ⇒ 三个都默认开时，实际决策顺序 = ①（打底）→ ②（冷启动窗口内）→ ③（①关且②关才轮到）。
            // 【取证·零成本】列表组合状态记录：普通字段（非快照）⇒ 写入不触发重组/重绘，
            // 只在 dumpState 里被读（验收判定"首次组合发生在哪条路径、哪个 p"）。
            val listDiag = remember { ListComposeDiag() }

            // ===== 【AI 调试接口】状态发布（实现见 debug/DebugBridge.kt；仅 debug 构建会被读取）=====
            // 目的：让助手用 adb 广播命令（不依赖图形界面、不靠盲点坐标）读写面板进度与状态。这里只做两件事：
            //   ① 把 uiState 与「面板桥」发布给 DebugBridge（DisposableEffect 安装 / 卸载；
            //      卸载时只在「仍是我们安装的那一份」才清空，避免 Activity 重建时旧组合把新句柄清掉）；
            //   ② 订阅 DebugBridge.revision（setSwitches 命令自增它）→ 触发一次重组，
            //      让 DebugSwitches 的 volatile 开关（在组合阶段被读取的那些）立即生效。
            // ⚠️ 不改任何观感/布局/参数：dump 全为只读；setP 走官方 AnchoredDraggableState.dispatchRawDelta
            //   （与嵌套滚动路径同一写入口；updateAnchors 只在锚点实例变化时才动作 → p 可稳定停在中途）。
            LaunchedEffect(DebugBridge.revision.intValue) { /* 仅订阅调试修订号，无副作用 */ }
            // ===== 【P00·dumpState 几何修复】面板状态文本的“实时来源” =====
            // 历史 bug：panelBridge 建在 DisposableEffect(Unit) 里 —— 该副作用块只在【首帧组合】
            // 执行一次，其闭包把首帧的那些 val 固定住了：首帧 rootSizePx=0x0 ⇒ fullWidthPx 退化
            // 成 collapsedWidthPx(464)、expandedHeightPx=0 ⇒ geom(p) 行恒输出 w=464 h=1
            // fullWidth=464 ✗，而同一时刻 LGLayout（layout 阶段用【当前组合】的值）打的是
            // w=1776 h=1619 ⇒ 两处矛盾（按它裁切必裁错）。
            // 修法：把“整段面板状态文本”做成 rememberUpdatedState —— 每次重组都会把【当前组合的】
            // 几何函数写进这个状态；桥内 dumpState 调 panelDump.value(p) ⇒ 永远读到最新闭包 ✓
            // （纯只读，不改任何布局/绘制行为）
            val panelDump = rememberUpdatedState<(Float) -> String> { p ->
                buildString {
                    appendLine(
                        "panel      = p=%.4f offset=%.1f targetP=%.1f span=[open=%.1f expand=%.1f] anchors=[0.0, %.1f, %.1f]"
                            .format(
                                p, anchorState.offset, anchorState.targetValue,
                                spanOpenPx(), spanExpandPx(),
                                anchorState.anchors.positionOf(1f), anchorState.anchors.positionOf(2f)
                            )
                    )
                    appendLine(
                        // 【P56·身份标记】行尾附加 P56_MARKER（"p56geom-v1"）：改前构建无此串、
                        //   改后构建命中 ⇒ 装机身份/dex 双重可检索（父会话验收口径②）。
                        ("geom(p)    = w=%d h=%d lift=%.1f topR=%.1f bottomR=%.1f contentH=%d collapsed=%dx%d fullWidth=%.0f"
                            .format(
                                wOf(p), hOf(p), liftOf(p), topROf(p), bottomROf(p), contentHeightPxOf(p),
                                collapsedWidthPx.toInt(), collapsedHeightPx.toInt(), fullWidthPx
                            )) + " " + DebugSwitches.P56_MARKER
                    )
                    appendLine(
                        "alpha(p)   = glass=%.3f content=%.3f fill=%.3f label=%.3f"
                            .format(glassAlphaOf(p), contentAlphaOf(p), fillAlphaOf(p), labelAlphaOf(p))
                    )
                    appendLine(
                        "gates      = glassVisible=%b panelGlassVisible=%b contentVisible=%b labelVisible=%b panelEngaged=%b sheetDragging=%b"
                            .format(glassVisible, panelGlassVisible, contentVisible, labelVisible, panelEngaged, sheetDragging)
                    )
                    appendLine(
                        "listgate   = composed=%b firstP=%.3f path=%s warm=%b deferred=%b deferSwitch=%b keepAliveSwitch=%b earlySwitch=%b"
                            .format(listDiag.composed, listDiag.firstP, listDiag.path, listWarm, listDeferredReady,
                                DebugSwitches.deferListComposeUntilSettled, DebugSwitches.precomposeListKeepAlive,
                                DebugSwitches.listComposeEarly)
                    )
                    appendLine(
                        "viewport   = root=%dx%d density=%.2f"
                            .format(rootSizePx.width, rootSizePx.height, densityScale)
                    )
                    // 【P43 取证·只读】「更多设置」页列表：视口高 / 内容高 / 可滚范围 / 当前滚动值。
                    // 用于判定「拉到半屏显示不全」卡在哪一层（槽高不足=被裁 / 视口高于可见区=滚不到底）。
                    appendLine(com.example.liquidglass.debug.ListSlotProbe.line())
                }
            }
            DisposableEffect(Unit) {
                val panelBridge = object : DebugBridge.Panel {
                    override fun currentP(): Float = pNow()

                    override fun setP(p: Float): Float {
                        // 【审计修复·双保险】NaN/Inf 直接拒绝（coerceIn 对 NaN 放行 ✗ ⇒ 会写出 NaN offset 并让每帧拉取 offset 时抛异常）
                        if (!p.isFinite()) return pNow()
                        val pp = p.coerceIn(0f, 2f)
                        val half = anchorState.anchors.positionOf(1f)
                        // 【P56③·NaN 兜底】演示页锚点集合收在 1f ⇒ positionOf(2f)=NaN：
                        //   兜底取 half（=上限），pp>1 一律换算到上限像素并被锚点夹取 ⇒ setP(2) 在本页
                        //   = 收到 p=1.0（改前：本页可到 p=2）✓；其它页数值逐字不变 ✓
                        var high = anchorState.anchors.positionOf(2f)
                        if (high.isNaN()) high = half
                        if (half.isNaN() || half <= 0f) return pNow()
                        val targetPx = if (pp <= 1f) pp * half
                                       else half + (pp - 1f) * (high - half).coerceAtLeast(1f)
                        val cur = anchorState.offset
                        anchorState.dispatchRawDelta(targetPx - (if (cur.isNaN()) 0f else cur))
                        return pNow()
                    }

                    /**
                     * 面板几何 / 透明度 / 门控转储（debug/DebugBridge.kt dumpState 读取）。
                     * 【几何修复】不再用本对象捕获的首帧闭包值 —— 改调 panelDump（每次重组都刷新到
                     * 最新闭包）⇒ geom/panel/viewport 行与同一时刻 LGLayout 逐字段相等 ✓。
                     */
                    override fun dumpState(): String = panelDump.value(pNow())
                }
                // 【P00·setUi key=advanced】面板二级页开关桥（get/set 直接读写 panelAdvanced 状态）
                val advancedBridge = object : DebugBridge.AdvancedToggle {
                    override fun get(): Boolean = panelAdvanced
                    override fun set(value: Boolean) { panelAdvanced = value }
                }
                // 【P00·setPos】玻璃卡位置桥：card 0=主卡、1=第二块卡；直接写 offsetX/offsetY
                // （绕过手势钳制的窗口，专门用于把两块玻璃摆到指定距离/重叠度做 A/B 取证）
                val cardsBridge = object : DebugBridge.Cards {
                    // 【现读现算】不用组合期算好的值（DisposableEffect 只建立一次 ⇒ 按值捕获必过期）
                    override fun count(): Int = activeCardCountNow()

                    override fun rect(card: Int): FloatArray? {
                        if (card !in 0 until activeCardCountNow()) return null
                        val st = cardStateAt(card)
                        val tl = when (card) {
                            0 -> mainDefaultTopLeft()
                            1 -> secondDefaultTopLeft()
                            else -> extraDefaultTopLeft(card)
                        }
                        return floatArrayOf(
                            tl.x + st.offsetX, tl.y + st.offsetY,
                            st.cardSizePx.width.toFloat(), st.cardSizePx.height.toFloat()
                        )
                    }

                    override fun currentOffset(card: Int): Pair<Float, Float>? {
                        if (card !in 0 until activeCardCountNow()) return null
                        val st = cardStateAt(card)
                        return st.offsetX to st.offsetY
                    }

                    override fun setOffset(card: Int, x: Float, y: Float): Pair<Float, Float>? {
                        if (card !in 0 until activeCardCountNow()) return null
                        val st = cardStateAt(card)
                        st.offsetX = x
                        st.offsetY = y
                        return st.offsetX to st.offsetY
                    }

                    // 【P06 点击置顶】z 序 = 绘制顺序（尾 = 最上层）；只报当前实际渲染的卡
                    override fun zOrder(): List<Int> {
                        val n = activeCardCountNow()
                        return zOrder.filter { it < n }
                    }

                    override fun raiseToFront(card: Int): String? {
                        if (card !in 0 until activeCardCountNow()) return null
                        raiseCardToFront(card)
                        return zOrder.joinToString(",")
                    }
                }
                // 【3D 钻石演示】调试桥（cmd=diamond：open/close/set/spin/params/dump）
                val diamondBridge = com.example.liquidglass.diamond.DiamondDemoBridge(diamondState)
                DebugBridge.panel = panelBridge
                DebugBridge.uiState = uiState
                DebugBridge.advanced = advancedBridge
                DebugBridge.cards = cardsBridge
                DebugBridge.diamond = diamondBridge
                onDispose {
                    if (DebugBridge.panel === panelBridge) DebugBridge.panel = null
                    if (DebugBridge.uiState === uiState) DebugBridge.uiState = null
                    if (DebugBridge.advanced === advancedBridge) DebugBridge.advanced = null
                    if (DebugBridge.cards === cardsBridge) DebugBridge.cards = null
                    if (DebugBridge.diamond === diamondBridge) DebugBridge.diamond = null
                }
            }

            // 【审查修复⑥】此处原有 glassAlpha/fillAlpha 两个局部量，无任何下游消费者 ✗
            //   但它们在【组合阶段】读 morph.value/dragProgress → 快照读订阅到整个 LiquidGlassScreen
            //   → 展开/收起动画与拖动期间【每帧重跑 1030 行组合体】✓（本文件注释里记录的 26ms 真凶同源）
            //   已删除；实际生效的是绘制阶段的 glassAlphaOf/fillAlphaOf ✓

            // 上滑展开 / 下滑收起（底部抽屉式嵌套滚动，v1.16.2）：
            // 内容列表滚到尽头后，剩余的纵向滚动量转到面板高度上——列表还能滚就先滚列表；
            // 滚到顶继续下滑 = 收起，滚到底继续上滑 = 展开成「全部控制中心」（满高）。
            // 【重构】滚动的位移直接喂给官方状态（dispatchRawDelta，与拖动手柄同一个 offset 坐标系），
            // 松手收口交给官方 settle（就近锚点 + 原 340ms/缓动；「半屏→全屏」段改由时序预设档决定 ✓）；
            // 不再有第二个进度持有者。
            val panelListSettleSpec = remember {
                PanelSettleSpec(
                    startP = { pNow() },
                    targetP = { anchorState.targetValue },
                    fallbackMs = PANEL_SNAP_MS_LIST,
                    // 【一致性】与把手路径逐字相同的行程换算（同行程 ⇒ 同时长；改前是 340 vs 320 ✗）
                    travelPx = {
                        val t = anchorState.targetValue
                        kotlin.math.abs(anchorState.anchors.positionOf(t) - anchorState.requireOffset())
                    },
                    refSpanPx = {
                        // 【P56③·NaN 兜底】演示页无 2f 锚点 ⇒ 本值按 0 记录（只服务 LGSettle 取证日志）
                        val a2 = anchorState.anchors.positionOf(2f)
                        if (a2.isNaN()) 0f else a2 - anchorState.anchors.positionOf(1f)
                    },
                )
            }
            val sheetConnection = remember(rootSizePx.height) {
                object : NestedScrollConnection {
                    // ===== 【P13 列表→面板交接·门槛/阻尼状态机】只服务二级页（参数/开关见 DebugSwitches 的 P13 段）=====
                    // 全部是【普通字段】（非快照状态）：只在手势回调里读写，不触发任何重组 ✓
                    private var sheetGateDir = 0            // 0=未定；-1=上滑(展开)；+1=下拉(收回)
                    private var sheetGateAccPx = 0f         // 当前方向已累计的结余位移（px，恒正）
                    private var sheetGateArmed = false      // 本次手势内面板真被动过 ⇒ 松手照旧 settle
                    private var sheetGateFromFull = false   // 【P56②】本段门槛起手时 p>1.5（全屏段）锁存
                    private var sheetGateT0Ms = android.os.SystemClock.uptimeMillis()   // 累计起点（日志 dt= 用）
                    private fun sheetGateReset(dir: Int) {
                        sheetGateDir = dir
                        sheetGateAccPx = 0f
                        // 【P56②·全屏段锁存】只在门槛段起手这一刻取一次 —— 段内 p 跨过 1.5f 不改档
                        sheetGateFromFull = pNow() > 1.5f
                        sheetGateT0Ms = android.os.SystemClock.uptimeMillis()
                    }
                    /** 门槛是否本页生效：主开关开 +（所有页 或 二级页 advanced/imageEdit）。 */
                    private fun sheetGateOn(): Boolean =
                        DebugSwitches.panelEdgeGate &&
                            (DebugSwitches.panelEdgeGateAllPages || panelAdvanced || panelImageEdit || panelComponentDemo || panelLicenses)

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        // 【P56】列表真把位移消费掉了（available.y == 0）⇒ 回到"列表自己滚"阶段：
                        // 门槛累计清零 —— 下一次滚到边重新从头吃死区（方向翻转的重置在下方按符号处理）
                        if (available.y == 0f) {
                            sheetGateDir = 0
                            sheetGateAccPx = 0f
                            sheetGateFromFull = false
                            return Offset.Zero
                        }
                        // 【P13·惯性不交接】手指已抬起、由 fling 驱动的结余位移一律不交接给面板。
                        // 常量名按本工程 Compose foundation 1.12.0 的 jar 字节码核对：
                        //   拖动路径 = NestedScrollSource.UserInput；惯性路径 = NestedScrollSource.SideEffect
                        // ⇒ 列表惯性冲到顶/底不再把面板拽走（= 用户说的"误触收回"的惯性来源）。
                        // 子开关 panelEdgeFlingNoHandover 默认 true；置 false = 惯性位移照旧走下面的门槛。
                        if (DebugSwitches.panelEdgeGate && DebugSwitches.panelEdgeFlingNoHandover &&
                            (DebugSwitches.panelEdgeGateAllPages || panelAdvanced || panelImageEdit || panelComponentDemo || panelLicenses) &&
                            source == NestedScrollSource.SideEffect
                        ) {
                            if (DebugSwitches.dragTrace && AppDebugLog.enabled) {
                                android.util.Log.i(
                                    "LGDrag",
                                    "滚动 dy=%.1f dp=0.000 p=%.3f 门 dir=%d acc=%.1f over=%.1f rate=0.00 dt=%.0f armed=%b src=%s drop=1"
                                        .format(
                                            available.y, pNow(), sheetGateDir, sheetGateAccPx, 0f,
                                            (android.os.SystemClock.uptimeMillis() - sheetGateT0Ms).toFloat(),
                                            sheetGateArmed, source.toString()
                                        )
                                )
                            }
                            return Offset.Zero
                        }
                        // ===== 符号约定（按 Compose foundation 1.12.0 源码逐环核对）=====
                        // ① 嵌套滚动：available.y > 0 = 手指向下。Scrollable.performScroll 把【原始指针位移】
                        //    （下滑 = +Δy）原样交给 dispatchPreScroll / dispatchPostScroll；
                        //    reverseIfNeeded 只作用于"列表自己消费多少"（scrollBy 的入参/回传互相抵消），
                        //    不改变 dispatch 的符号。
                        // ② 状态机：AnchoredDraggableState.dispatchRawDelta(d) = newOffsetForDelta(d) = offset + d
                        //    （正数把 offset 推大），而 offset 是 p 的单调增函数 ⇒ 下滑要 p 变小就必须取负。
                        // ③ 与手柄侧同向：anchoredDraggable(reverseDirection = true) 把 DragDelta 与松手速度
                        //    都 ×-1（AnchoredDraggableNode.reverseIfNeeded），下滑 ⇒ offset 变小 ✓。
                        // ④ 与重构前旧手写逻辑 `base - available.y / span` 同向 ✓（两条路径必须同号）。
                        // DRAG_SENSITIVITY = 1f（1:1 跟手），与锚点跨度 spanOpenPx() 用的是同一常量。
                        // ===== 【P13 门槛 + 阻尼曲线】先吃死区、再按 k(结余位移) 交接（开关关 / 一级页 = 逐字原行为）=====
                        // 语义：① 上滑先过死区再进阻尼段（起步"重"，但一路拖得到 p=2，不饱和 ✗）；
                        //       ② 下拉同样先过死区，阈值内结余位移【直接丢弃】（面板不动、列表也不滚 = 抵抗感）；
                        //       ③ 未交接的余量一律丢弃（少返回 = 天然阻尼，别再把余量滚回列表 ✗）。
                        var gatePayloadY = available.y              // 本次拟交接的位移（px，符号同 available.y）
                        var gateRate = 1f                           // 本次生效的移交率 k（1 = 逐字原行为）
                        var gateOverPx = 0f                         // 越过死区的累计位移（px）
                        if (sheetGateOn()) {
                            val dirNow = if (available.y < 0f) -1 else 1    // -1=上滑(展开)；+1=下拉(收回)
                            if (dirNow != sheetGateDir) sheetGateReset(dirNow)   // 方向翻转 ⇒ 重新吃死区
                            sheetGateAccPx += kotlin.math.abs(available.y)
                            val preset = if (DebugSwitches.panelEdgeGateFullscreenMid &&
                                dirNow > 0 && sheetGateFromFull
                            ) DebugSwitches.PanelEdgeGatePreset.MIDDLE_FS else DebugSwitches.panelEdgeGatePreset
                            val deadPx = (if (dirNow < 0) preset.expandDeadZoneDp else preset.collapseDeadZoneDp) * densityScale
                            val rampPx = (if (dirNow < 0) preset.expandRampDp else preset.collapseRampDp) * densityScale
                            val k0 = if (dirNow < 0) preset.expandRate0 else preset.collapseRate0
                            gateOverPx = sheetGateAccPx - deadPx
                            gateRate = if (gateOverPx <= 0f) 0f else {
                                val t = (gateOverPx / rampPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                k0 + (1f - k0) * (t * t * (3f - 2f * t))   // smoothstep：起步平缓、到爬升末尾平滑接回 1
                            }
                            gatePayloadY = available.y * gateRate          // 死区内 gateRate=0 ⇒ 整段丢弃
                        }
                        if (gatePayloadY == 0f) {
                            // 【P13】死区 / 阻尼段的未交接余量直接丢弃（返回 Zero ⇒ 面板不动 = 抵抗感）；
                            // 也不置 sheetDragging（面板没动，无谓重组 ✗）
                            if (DebugSwitches.dragTrace && AppDebugLog.enabled) {
                                android.util.Log.i(
                                    "LGDrag",
                                    "滚动 dy=%.1f dp=0.000 p=%.3f 门 dir=%d acc=%.1f over=%.1f rate=%.2f dt=%.0f armed=%b src=%s drop=1"
                                        .format(
                                            available.y, pNow(), sheetGateDir, sheetGateAccPx,
                                            gateOverPx.coerceAtLeast(0f), gateRate,
                                            (android.os.SystemClock.uptimeMillis() - sheetGateT0Ms).toFloat(),
                                            sheetGateArmed, source.toString()
                                        )
                                )
                            }
                            return Offset.Zero
                        }
                        val consumedDelta = anchorState.dispatchRawDelta(-gatePayloadY / DRAG_SENSITIVITY)
                        if (consumedDelta != 0f) sheetGateArmed = true   // 【P13】面板真被动过（松手照旧 settle）
                        // 【跟手取证】嵌套滚动路径的 (t, dy, dp, p) 打点（开关 dragTrace，默认 false）
                        if (DebugSwitches.dragTrace && AppDebugLog.enabled) {
                            android.util.Log.i(
                                "LGDrag",
                                "滚动 dy=%.1f dp=%.3f p=%.3f 门 dir=%d acc=%.1f over=%.1f rate=%.2f dt=%.0f armed=%b src=%s drop=0"
                                    .format(
                                        available.y, consumedDelta, pNow(), sheetGateDir, sheetGateAccPx,
                                        gateOverPx.coerceAtLeast(0f), gateRate,
                                        (android.os.SystemClock.uptimeMillis() - sheetGateT0Ms).toFloat(),
                                        sheetGateArmed, source.toString()
                                    )
                            )
                        }
                        // 真的推动了面板才置位（纯列表内滚动不置位，避免无谓重组）
                        if (consumedDelta != 0f && !sheetDragging) sheetDragging = true
                        return Offset(0f, -consumedDelta)
                    }

                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity
                    ): Velocity {
                        // ===== 【P13 松手交接门控】只二级页 + 主开关开时生效（否则逐字原行为）=====
                        // 本次手势面板【真被动过】(armed) ⇒ 照旧 settle（保持能收回 / 能展开）；
                        // 没动过 ⇒ 只有【残余速度 ≥ 档位阈值】才交接（保留"故意甩一下收回/展开"，
                        // 杀掉"轻碰就收回"）。阈值默认 600px/s，上 / 下在档位枚举里分开可调。
                        // ⚠️ 本工程 Compose 1.12.0 的 settle(animationSpec) = 就近锚点（字节码核对：
                        //    closestAnchor(requireOffset())、不看速度）⇒ 面板没动过时它等价于"原地收口"，
                        //    真正把面板拉走的从来是惯性位移（已被上面的 drop 挡掉）✓
                        val sheetGateFlingOn = sheetGateOn()
                        val sheetGatePreset = DebugSwitches.panelEdgeGatePreset
                        val sheetGateVAvail = available.y
                        val sheetGateTh = if (sheetGateVAvail < 0f) sheetGatePreset.expandFlingThresholdPxPerSec
                                          else sheetGatePreset.collapseFlingThresholdPxPerSec
                        val sheetGateHandover = !sheetGateFlingOn || sheetGateArmed ||
                            kotlin.math.abs(sheetGateVAvail) >= sheetGateTh
                        if (DebugSwitches.dragTrace && AppDebugLog.enabled) {
                            android.util.Log.i(
                                "LGDrag",
                                "松手 vel=%.0f th=%.0f armed=%b gate=%b 交接=%b"
                                    .format(sheetGateVAvail, sheetGateTh, sheetGateArmed, sheetGateFlingOn, sheetGateHandover)
                            )
                        }
                        try {
                            // 就近锚点收口（与重构前"p>1.5→2f / p>0.5→1f / else 0f"同一分档）
                            // 【时序预设】与手柄路径共用同一入口：只有「半屏→全屏」段按档位取时长/曲线，
                            // 其余（→0 / →1）仍是原来的 340ms + 原曲线 ✓
                            // 【P13】按松手门控决定是否交接（不交接 = 面板保持原位，列表自己的惯性由列表处理 ✓）
                            if (sheetGateHandover) anchorState.settle(animationSpec = panelListSettleSpec)
                        } finally {
                            // 收口结束（或被取消）后立刻放行列表：面板贴底后不再保留不可见的列表子树
                            sheetDragging = false
                            // 【P13】手势结束：门槛状态复位 —— armed 只覆盖"本次手势"，绝不卡住 true ✓
                            sheetGateDir = 0
                            sheetGateAccPx = 0f
                            sheetGateArmed = false
                        }
                        return Velocity.Zero
                    }
                }
            }

            // 兜底复位：面板一旦真正贴底（offset≈0），列表拖动标记清零 ——
            // 保证"列表不可见时不组合"这条优化不会被残留标记长期压制（残留只可能出现在面板打开时，
            // 那种情况下列表本来就要组合，故无副作用）。
            LaunchedEffect(panelEngaged) { if (!panelEngaged) sheetDragging = false }

            Box(
                Modifier
                    // 【② 分层修复 · 面板离屏层】把控制中心面板【已渲染结果】录进 panelCaptureLayer
                    // ⇒ 卡在面板之上时采样"背景 + 本层"，卡折射的是【面板本身】✓
                    //（修复前卡的采样层里从来没有面板 ⇒ 卡压在展开的面板上时画的是"面板下面的背景"
                    //  ⇒ 观感 = 透过控制中心看到它下面的内容 ✗）。
                    // 放在最外（链首）⇒ 记录的 = 本节点最终绘制结果（含填充层/玻璃层/内容/裁剪）✓
                    // 回退：DebugSwitches.cardOverPanel = false（A 档：卡在面板之下，本层不进卡的采样）✓
                    .layerBackdrop(adapter.panelCaptureLayer)
                    // 【② 分层修复 · z 序】A 档（卡在面板之下）⇒ 面板 z=10 压在所有卡之上（遮挡与采样一致 ✓）
                    // B 档 ⇒ 面板 z 保持 0（卡片整体 +1 后都在面板之上 ✓）
                    .zIndex(panelZ)
                    // 【性能】这里原有每帧执行的 onGloballyPositioned（布局回调）：把"容器中心"写进
                    // panelCenterRoot（快照状态）—— 布局阶段写状态会让该节点再失效一轮。
                    // 自"无缝交棒"方案回退后该值全工程只有写入、没有任何读取方 → 整块删除 ✓
                    .align(Alignment.BottomCenter)
                    // ===== 形态全部在 layout / draw 阶段读取动画值 =====
                    // 旧实现把 morph.value 读在组合阶段（.padding().size().clip(shape)），
                    // 每个动画帧都会重组整个面板内容（几十个控件）→ 实测 50th 27ms。
                    // 现在：位移并入 layout 的放置、尺寸走 layout lambda、裁剪走 graphicsLayer lambda，
                    // 组合阶段不再读动画值，内容子树不会逐帧重组。
                    .layout { measurable, _ ->
                        val p = pNow()
                        val mp = p.coerceIn(0f, 1f)
                        val ep = (p - 1f).coerceIn(0f, 1f)
                        val wEase = 1f - (1f - mp).pow(2.2f)
                        val hEase = mp * mp * (3f - 2f * mp)
                        val lift = lerp(navBarBottomPx + 18f * densityScale, 0f, hEase)
                        val w = lerp(collapsedWidthPx, fullWidthPx, wEase).toInt().coerceAtLeast(1)
                        val h = (if (p <= 1f) lerp(collapsedHeightPx, expandedHeightPx, hEase)
                                 else lerp(expandedHeightPx, fullHeightPx, ep))
                            .toInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        // 临时诊断（验收用）：竖屏/横屏各打一次形态，便于定位"按钮飞了"。
                        // 【性能修复】关日志时整块跳过 → 布局阶段零字符串拼接、零 Log IO、零状态写入；
                        // 形态记录改存普通 IntArray（非快照）→ 不再每帧多跑一轮布局 ✓
                        if (AppDebugLog.enabled && (diagShape[0] != w || diagShape[1] != h)) {
                            diagShape[0] = w; diagShape[1] = h
                            android.util.Log.i(
                                "LGLayout",
                                "p=" + "%.3f".format(p) + " w=$w h=$h l_$lift px, root=" +
                                    rootSizePx.width + "x" + rootSizePx.height +
                                    " navBar=" + navBarBottomPx + " dens=" + densityScale
                            )
                        }
                        // 【性能】"从小白条上方浮起来"的位移并入这里的放置：原来是紧随其后的
                        // Modifier.offset{}（每帧多一个布局节点、每帧多跑一次 measure+place）。
                        // 位移量完全相同 = -(lift.toInt())：lift ≥ 0，取负即向上浮起（同下注释）。
                        // 必须取负：offset 的正 y 是向下 —— 竖屏 navBar 更高(lift≈98px)时会把按钮
                        // 整块推出屏幕（用户报"按钮飞了、看不见"），所以这里也必须是负的 ✓
                        // 【A档遮挡】把本帧面板的“洞”（root 坐标的形状 + 盒）交给卡片层：
                        //   · 写的是【与面板自己裁剪同源】的那份几何（同一个 panelClipShapeCache 实例 +
                        //     同一组 w/h/lift + 同一组 tR/bR）⇒ 两边的可见边界永不脱节 ✓
                        //   · 布局期写（普通字段，非快照状态）⇒ 不触发任何失效；同一帧的绘制晚于布局
                        //     ⇒ 卡片读到的一定是【本帧正在用的】那份几何（不滞后一帧）✓
                        //   · B 档（cardOverPanel=true，卡在面板之上）⇒ 不写 ⇒ 卡片层不裁（必须看得见）✓
                        //   · 开关关 ⇒ 不写 ⇒ 一行回退到"只靠材质透明度遮挡"（改动前观感）✓
                        if (DebugSwitches.cardHiddenUnderPanel && !cardOverPanel) {
                            val tRdp = topROf(p) / densityScale
                            val bRdp = bottomROf(p) / densityScale
                            panelHole.update(
                                left = (rootSizePx.width - w) * 0.5f,
                                top = rootSizePx.height - h - lift,
                                width = w,
                                height = h,
                                shape = panelClipShapeCache.shapeOf(tRdp, tRdp, bRdp, bRdp)
                            )
                        }
                        layout(w, h) { placeable.place(0, -lift.toInt()) }
                    }
                    .graphicsLayer {
                        // 按压形变：垂直压扁多于水平收窄（像被指尖按下去的胶块）
                        scaleX = blockPressScale   // 等比放大：原来水平只跟 0.55，按下像被"捏扁"，观感很怪
                        scaleY = blockPressScale
                        transformOrigin = TransformOrigin.Center
                        // 裁剪：圆角随形态变化（draw 阶段读取，不触发重组）
                        val p = pNow()
                        // 【调试开关热更】本 block 只在被观测状态变化时重跑；DebugSwitches 是普通
                        // @Volatile 字段（不产生订阅）→ 运行时翻转开关后若本层没被 invalidate，就会
                        // 继续用【旧 shape】渲染 ⇒ runtime A/B 会读到"旧形状"（曾导致 panelEdgeAa
                        // 的 OFF 版仍带外扩、A/B 结论失真 ✗）。这里读一次 setSwitches 会自增的
                        // revision ⇒ 翻开关即失效重建 ✓（只在翻转时失效一次，每帧路径无额外开销）
                        @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                        val tR = topROf(p) / densityScale
                        val bR = bottomROf(p) / densityScale
                        // 与 Shader 侧 sdContinuousRect 同源：G2 连续圆角
                        // 【性能修复】不再每帧新建 Shape 实例（官方图层会因实例不相等而每帧重建
                        // Outline/裁剪路径）：按 0.25dp 档位复用 remember 里的实例（亚像素，观感不变）✓
                        // 【边缘抗锯齿】panelEdgeAa=true（默认）：裁剪形状【外扩 1.5px（半径同步 +1.5px）】，
                        // 让这条【无 AA 的硬裁剪边】退到玻璃 SDF 羽化带之外（那里 coverage≈0.2，肉眼不可见）
                        // → 圆角弧线上看到的就是玻璃自己的 AA 边 ✓（实测：直边平滑、弧线有 1~2px 阶梯 ✗）。
                        // 外扩量远小于玻璃层余量（面板玻璃层过采样 pad ≈18.5px）→ 内容不会露出 ✓。
                        // 回退：DebugSwitches.panelEdgeAa = false → 精确形状（旧行为）。
                        // 【边缘抗锯齿·真机硬化】外扩量按状态取：
                        //   · 玻璃层参与绘制（glassAlphaOf(p)>0.001，即 p ≲ 0.2956）→ 用
                        //     PANEL_CLIP_CLEAR_FEATHER_PX(6px)【仅当 aaFixContainerClearFeather=true】：
                        //     此时的可见边界是 Shader 的 SDF 羽化带（实测外侧还有 ~2.5px 非零覆盖率），
                        //     1.5px 的外扩仍落在羽化带内部 → 在"Path 裁剪不做 AA"的 GPU 上会硬切羽化带 ✗；
                        //     6px 彻底离开羽化带 ⇒ 可见边界 100% 交给 SDF（与 GPU 的 clip AA 行为无关 ✓）。
                        //     该档位 contentAlphaOf(p)≡0（内容 p>0.42 才淡入）⇒ 外扩不会露出内容 ✓
                        //   · 其它档位（含展开态，内容可见）→ 仍用 PANEL_CLIP_INFLATE_PX(1.5px) ✓
                        //   · 默认 false ⇒ 与今天逐像素一致（硬门安全）；真机 A/B 用 setSwitches 打开
                        val clipInflatePx =
                            if (DebugSwitches.aaFixContainerClearFeather && glassAlphaOf(p) > 0.001f) {
                                PANEL_CLIP_CLEAR_FEATHER_PX
                            } else {
                                PANEL_CLIP_INFLATE_PX
                            }
                        shape = if (DebugSwitches.panelEdgeAa && DebugSwitches.aaFixContainerInflate) {
                            panelClipShapeCache.inflatedOf(tR, tR, bR, bR, clipInflatePx, densityScale)
                        } else {
                            panelClipShapeCache.shapeOf(tR, tR, bR, bR)
                        }
                        clip = true
                    }
                    .then(
                        if (!showControlCenter) {
                            Modifier
                                // 跟踪手指位置：玻璃的液态形变以触点为源
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        buttonPressPos = down.position
                                        var ev = awaitPointerEvent()
                                        while (ev.changes.any { it.pressed }) {
                                            ev.changes.firstOrNull()?.let { c ->
                                                buttonPressPos = c.position
                                            }
                                            ev = awaitPointerEvent()
                                        }
                                    }
                                }
                                .clickable(
                                    interactionSource = blockInteraction,
                                    // 【原生化③】按下反馈改用官方 ripple（Material3 indication）✓
                                    //   原为 indication = null（刻意无按下反馈 ✗）→ 现在按下时胶囊内出现原生涟漪 ✓
                                    indication = ripple(bounded = true, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.38f))
                                ) { if (anchorState.targetValue < 0.5f) animatePanelTo(1f, panelAnimDurationMs(open = true)) }
                        } else {
                            Modifier
                        }
                    )
                    .semantics {
                        contentDescription =
                            if (showControlCenter) "控制中心面板"
                            else "打开控制中心"
                    }
            ) {
                // 1) 等效暗底：展开动画期间承担面板填充。
                //    实测展开时的瓶颈是 UI 线程（GPU 仅 4~8ms）：每帧重录背景层 +
                //    整屏 Shader 采样会把帧时间从 ~8ms 抬到 20ms+。因此玻璃只在
                //    形态稳定后启用。
                // 【P45·真实控件·采样源】把面板【填充层】这一层录进 adapter.panelFillLayer
                //   ⇒ 面板内的真实控件（开关 / 滑杆）采样"控件脚下真正可见的材质"（不含控件自身，
                //   无自引用）。开关 liquidRealControls=false ⇒ 本修饰符【不进链】= 逐字回到改动前 ✓
                //   （@Volatile 开关不产生订阅 ⇒ 组合期陪读一次 revision，翻开关即重绘）
                @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                val panelFillRecordMod: Modifier =
                    if (DebugSwitches.liquidRealControls) Modifier.layerBackdrop(adapter.panelFillLayer)
                    else Modifier
                Box(
                    Modifier
                        .then(panelFillRecordMod)
                        .matchParentSize()
                        .graphicsLayer { alpha = fillAlphaOf(pNow()) }
                        // 填充色必须与"面板最终材质"同调：原来是深蓝 0xF00B1424，
                        // 面板改成白磨砂底后，这个深色中间态就变成明显的"由暗变亮"闪变
                        //（用户："点击展开的时候，为什么它总是由暗变亮"）。
                        // 改成与面板一致的浅色，整段动画亮度单调上升、没有暗坑。
                        // 面板"偏灰磨砂"的载体：中性灰底（iOS 控制中心那种偏灰），
                        // 不透明度由形态驱动、展开态到 0.86。玻璃层若参与绘制会叠在其上做折射。
                        // 【边缘抗锯齿】panelEdgeAa=true（默认）：填充层改由【路径绘制】给出边界 ——
                        // 它原本是一个矩形，边界 100% 来自容器的 Path 硬裁剪（无 AA ✗ = 弧线上那 1~2px 阶梯）；
                        // 现在按【同一个 G2 形状】绘制（Skia 路径绘制自带 AA ✓），几何逐像素一致 ✓。
                        // 回退：DebugSwitches.panelEdgeAa = false → 回到矩形（由容器裁剪切边，旧行为）。
                        .then(
                            if (DebugSwitches.panelEdgeAa && DebugSwitches.aaFixFillPath) {
                                Modifier.drawBehind {
                                    val p = pNow()
                                    val tR = topROf(p) / densityScale
                                    val bR = bottomROf(p) / densityScale
                                    val outline = panelClipShapeCache.shapeOf(tR, tR, bR, bR)
                                        .createOutline(size, layoutDirection, density)
                                    // Generic 轮廓 = 一条 Path：drawPath 内部即 Skia 路径绘制（抗锯齿 ✓）
                                    drawPath(
                                        (outline as Outline.Generic).path,
                                        color = fillColorOf(p)   // 【①】胶囊实底色 → 面板填充色（见 fillColorOf）
                                    )
                                }
                            } else {
                                // 【①】与上面同源（drawBehind + drawRect = 被容器裁剪后的矩形，等价原
                                //   Modifier.background(颜色)），只是颜色也随形态走到胶囊实底色 ✓
                                Modifier.drawBehind { drawRect(fillColorOf(pNow())) }
                            }
                        )   // iOS 灰白磨砂：浅中性灰，靠填充层给底（不再透壁纸压暗）
                )
                // 2) 液态玻璃层：收起态（小方块）与展开稳定态都启用，
                //    形变过程中关闭以保证动画顺滑
                // 【性能·面板玻璃层组合门控】不再"始终组合、仅由 alpha 控制可见性"（原来用 if(glassVisible)
                // 门控，实测日志显示该块【从未绘制】——面板的观感其实来自填充层）。
                // 新行为：glassAlphaOf(p) ≤ 0.001 时本层像素上完全不可见（p≥0.30 为恒零区间）→ 整个节点
                // 不进组合，省掉形变期间"最大一层"的每帧重录重提；仅当 alpha>0.001 才参与组合。
                // 门控与绘制期 alpha 同源 → 翻转帧与旧行为逐帧一致；Box 内 glassPanel 参数一字未动。
                // 回退：DebugSwitches.gatePanelGlassByAlpha = false → 回到旧行为（始终组合）。
                if (!DebugSwitches.gatePanelGlassByAlpha || panelGlassVisible) {
                    // 组合期一次日志：确认面板玻璃 Box 到底有没有被组合
                    // 【性能修复】诊断日志只在调试日志开启时执行（原先即使关闭也会每 2s 走一次组合期
                    // SystemClock 调用；绘制 lambda 里更是每帧都要拼字符串）✓
                    if (AppDebugLog.enabled) {
                        val lgComp = android.os.SystemClock.elapsedRealtime()
                        if (lgComp - glassDrawLogMs > 2000L) {
                            glassDrawLogMs = lgComp
                            android.util.Log.i("LGComp", "panel glass Box composed")
                        }
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = glassAlphaOf(pNow())
                                // 决定性诊断：这个 lambda 每帧绘制都会执行。
                                // 若它打点、而着色器块不打点 → 说明玻璃"层"在画但 shader 没执行。
                                // 【性能修复】整个诊断块只在 AppDebugLog.enabled 时执行：关闭时
                                // 这个每帧都跑的绘制 lambda 里不再有 SystemClock 调用 + 字符串格式化 + Log IO ✓
                                if (AppDebugLog.enabled) {
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - glassDrawLogMs > 1500L) {
                                        glassDrawLogMs = now
                                        android.util.Log.i("LGDraw", "glass layer draw alpha=" + "%.2f".format(alpha))
                                    }
                                }
                            }
                            .glassPanel(
                                adapter = adapter,
                                shape = {
                                    // 【调试开关热更】同容器裁剪：本 lambda 由库在录制时调用，
                                    // 若未被 invalidate 不会重跑 → 读一次 revision 保证翻开关即生效 ✓
                                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                                    // 【边缘抗锯齿】panelEdgeAa=true（默认）：玻璃层【不再】自叠一层 Path 裁剪 ——
                                    // 「玻璃层本身由 SDF 决定形状」时这层裁剪是多余的，而且它是无 AA 的硬切：
                                    // 会在 SDF 边界处把 5px 覆盖率羽化切成半透明台阶（弧线上表现为 1~2px 阶梯 ✗）。
                                    // 改成矩形裁剪（= 只裁到层边界，与演示卡片同一机制：LiquidGlassCard 也传 RectangleShape）
                                    // → 可见边界完全交给 Shader 的 SDF 羽化（自带 AA ✓）。
                                    // 回退：DebugSwitches.panelEdgeAa = false → 恢复 G2 Path 裁剪（旧行为）。
                                    if (DebugSwitches.panelEdgeAa && DebugSwitches.aaFixGlassRect) {
                                        androidx.compose.ui.graphics.RectangleShape
                                    } else {
                                        val p = pNow()
                                        val mp = p.coerceIn(0f, 1f)
                                        val hEase = mp * mp * (3f - 2f * mp)
                                        val tR = lerp(collapsedRadiusPx, 28f * densityScale, hEase) / densityScale
                                        val bR = lerp(collapsedRadiusPx, 28f * densityScale, hEase)   // 底部也给圆角：面板成为完整矩形（用户要求） / densityScale
                                        // 【性能】原来这里每帧 new G2RoundedShape（值完全相同的帧也新建实例）→
                                        // 按 0.25dp 档位复用实例：亚像素量化，观感常量不变（端点档位仍是精确值）✓
                                        panelGlassShapeCache.shapeOf(tR, tR, bR, bR)
                                    }
                                },
                                cornerRadiiPx = {
                                    val p = pNow()
                                    val mp = p.coerceIn(0f, 1f)
                                    val hEase = mp * mp * (3f - 2f * mp)
                                    val tR = lerp(collapsedRadiusPx, 28f * densityScale, hEase)
                                    val bR = lerp(collapsedRadiusPx, 28f * densityScale, hEase)   // 底部也给圆角：面板成为完整矩形（用户要求）
                                    floatArrayOf(tR, tR, bR, bR)
                                },
                                quality = { uiState.quality },
                                pressProgress = { blockPressProgress },
                                pressPositionPx = { buttonPressPos },
                                deformationStrength = 2.4f,
                                hdrBoost = { 1f },   // 控制中心不做 HDR（用户要求）：HDR 只留给演示卡片
                                parameters = { uiState.parameters }
                            )
                    )
                }
                // 3) 顶部静态白光泽层【已移除】
                //    它是纯 UI 白渐变，被胶囊顶部圆角切出一块月牙形白斑，
                //    用户反馈"按钮右上角出现了错误的高光"——由它造成，故整层删除。
                // 【已回退】面板自身捕获层会与面板内玻璃形成循环采样导致闪退，撤除；
                // 此处保留两层 no-op 以维持原有缩进块结构。
                run {
                    run {
                    MaterialTheme(colorScheme = GlassPanelColorScheme) {
                    // 收起态：椭圆按钮上的标签（组合阶段只读布尔门，不读动画浮点值）
                    // 【P05·无缝交棒】handoffLabel=true（默认）时这一份【不再组合】——
                    // 胶囊里的「控制中心」改由屏幕级那【同一个实例】承担（见 root Box 末尾的交棒实例），
                    // 否则会同时存在两份「控制中心」✗（历史 40f92fa 回退前后的问题）。
                    // 读一次 DebugBridge.revision（快照状态）⇒ adb setSwitches 翻开关后立即重组生效 ✓
                    @Suppress("UNUSED_EXPRESSION")
                    DebugBridge.revision.intValue
                    if ((!DebugSwitches.handoffLabel || (DebugSwitches.capsuleLabelBlack && capsuleRest)) && labelVisible) {
                        Text(
                            "控制中心",
                            style = MaterialTheme.typography.labelLarge.copy(
                                // 【② 拆掉白衬底/光晕·2026-09-25 用户要求】收起态标签的补光层
                                //   在默认路径【全部不画】（开关 capsuleLabelHalo 默认已改为 false）：
                                //   亮档零偏移白光晕 + 白色径向衬底 / 暗档深色零偏移光晕 + 深色圆盘
                                //   一律只在"打开开关"时才绘（代码逐字保留供回退 ✓）。
                                //   依据：用户原话「黑色字底下又要加一个白色的底面，特别丑、特别不协调」✗；
                                //   且胶囊填充改成【纯灰实底】（见 fillAlphaOf）后，字形紧邻处不再是
                                //   "透出壁纸的玻璃"而是固定中性灰 ⇒ 补光已无对象、只剩"加工"✗。
                                //   默认路径剩下的这条 shadow = 【改动前就有的深色投影】
                                //   （0x8A0A1220 / offset(0,2) / blur 6，既不是光晕也不是衬底），
                                //   在实底灰上几乎不可见 ⇒ 属"回到改动前"的最小面 ✓
                                //   （一行回退 = setSwitches capsuleLabelHalo 1，把两层补光都放回来）。
                                // 【本轮改动】深色档那条 blur 18 的深色零偏移光晕现在也受同一开关门控
                                //   （改动前它不受门控 ⇒ 默认关后仍会画 ✗）⇒ 默认路径【两档都无光晕】✓。
                                shadow = if (DebugSwitches.capsuleLabelHalo && glassPal.isDark) {
                                    androidx.compose.ui.graphics.Shadow(
                                        color = glassPal.capsuleHalo,
                                        offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                                        blurRadius = LABEL_HALO_BLUR_DARK
                                    )
                                } else if (DebugSwitches.capsuleLabelHalo && DebugSwitches.capsuleLabelBlack) {
                                    androidx.compose.ui.graphics.Shadow(
                                        color = glassPal.capsuleHalo,
                                        offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                                        blurRadius = LABEL_HALO_BLUR
                                    )
                                } else {
                                    androidx.compose.ui.graphics.Shadow(
                                        color = Color(LABEL_SHADOW_LEGACY_ARGB),
                                        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                        blurRadius = 6f
                                    )
                                }
                            ),
                            // 透明玻璃上白字（带投影），按下转玻璃后依然是白字
                            // 【收起态黑字】capsuleLabelBlack=true（默认）：收起态标签改纯黑 0xFF000000（用户要求，
                            // 与展开末态标题同口径）；一行回退：setSwitches capsuleLabelBlack 0（见 DebugSwitches 注释）
                            // 【深色/亮色模式·2026-09-24】改为调色板驱动：亮色档 = 0xFF000000（逐字未变 ✓）、
                            //   深色档 = 0xFFF4F7FF（白字 + 上面那层深色光晕）。
                            color = glassPal.capsuleLabel,
                            modifier = Modifier
                                .align(Alignment.Center)
                                // 【B 项·白衬底·2026-09-25】【② 2026-09-25 默认已关】字形盒周围一层极淡白
                                //   radial补光（见 LABEL_GLOW_ARGB 注释）：画在先（drawBehind）⇒ 在文字之下，
                                //   只抬背景不盖字形 ✓；【C 项】暗档改画 LABEL_DARK_DISC_ARGB 的深色圆盘。
                                //   本层受 capsuleLabelHalo 门控，该开关【默认已改为 false】⇒ 默认路径
                                //   一格都不画 ✓（就是用户点名的"黑字底下白色底面"那一层 ✗）。
                                .drawBehind {
                                    if (DebugSwitches.capsuleLabelHalo &&
                                        DebugSwitches.capsuleLabelBlack
                                    ) {
                                        val rad = kotlin.math.max(size.width, size.height) * LABEL_GLOW_RADIUS_K
                                        drawRect(
                                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                                colors = if (glassPal.isDark)
                                                    listOf(Color(LABEL_DARK_DISC_ARGB), Color(0x00000000))
                                                else
                                                    listOf(Color(LABEL_GLOW_ARGB), Color(0x00FFFFFF)),
                                                center = androidx.compose.ui.geometry.Offset(
                                                    size.width / 2f, size.height / 2f
                                                ),
                                                radius = if (glassPal.isDark) rad / LABEL_GLOW_RADIUS_K * 1.05f else rad
                                            )
                                        )
                                    }
                                }
                                .graphicsLayer { alpha = labelAlphaOf(pNow()) }
                        )
                    }
                    // 展开态内容：固定为【终态尺寸】布局（不随形态逐帧重新测量），
                    // 由外层容器裁剪变形。
                    // 注意：必须【按需组合】——已组合但被裁掉的内容依然参与命中测试，
                    // 会吃掉收起态方块的点击（曾导致点不开面板）。
                    // 【卡半路·根因修复】门控原来只认"可见性/开关"（p>0.42 / target>0.5 / 手柄被按下），
                    // 于是"下拉收起"时 p 一跌破 0.42 就立刻把内容（含手柄 anchoredDraggable、列表 scrollable）
                    // 摘出组合 → 正在跑的手势与 onPostFling 的 settle 一起被取消，p 永久卡在 ~0.39 ✗
                    // 现在多一条硬指标 panelEngaged（offset 未贴底）：面板真正收回胶囊之前，内容一律保持组合 ✓
                    // panelDragging / sheetDragging 亦保留：手指正压在内容上时同样不许拆组合。
                    // 【首次组合开销·方案A】再多一条 keepAlive 常驻项：预热之后内容容器（含列表）【永不拆出组合】，
                    //   于是"下次打开零组合开销"；配套的输入隔离在下方 layout 里（收起到底把内容停到屏幕外）✓
                    //   开关关闭时该条恒为 false ⇒ 这一行与今天逐字等价 ✓
                    if (showControlCenter || contentVisible || panelDragging || sheetDragging || panelEngaged ||
                        (DebugSwitches.precomposeListKeepAlive && listWarm)) {
                        // 【主因修复·伴随项】面板"内容以下"的空白区拖拽承接层（详细语义见 DebugSwitches.contentStripDrag）。
                        // 为什么需要：内容容器尺寸恒定后，它的高度 = 终态常量（p>1 时小于面板高）⇒
                        //   旧实现里由列表 viewport 覆盖的那段空白区不再有任何滚动容器 ⇒ 在面板下半部下
                        //   滑会【收不回面板】。这里放一层透明的、不可滚动的滚动容器（maxValue=0），
                        //   它把"结余的滑动量"沿与列表完全相同的嵌套滚动路径交出去
                        //   （onPostScroll → dispatchRawDelta；onPostFling → settle）⇒ 拖拽语义不变 ✓
                        // z 序在内容层【之下】：Box 命中测试自后向前 ⇒ 列表/手柄照旧优先命中，绝不抢手势 ✓
                        // 不绘制任何像素（空容器）、无自己的图层 ⇒ 不引入绘制成本。
                        // 【方案A·常驻组合的伴随项】承接层只在"面板已离开底部"时存在：
                        //   它唯一的用途是让面板下半空白区的下滑能收回面板；收起到底时不需要它，
                        //   而它自带一个 scrollable（命中测试节点）⇒ 收起态不让它压在胶囊上，
                        //   语义与今天（内容被拆出组合 ⇒ 承接层不存在）逐字相同 ✓；
                        //   关掉 keepAlive 时与旧行为完全一致 ✓
                        val stripNeeded = DebugSwitches.contentStripDrag &&
                            (!DebugSwitches.precomposeListKeepAlive || panelEngaged)
                        if (stripNeeded) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .nestedScroll(sheetConnection)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        Box(
                            Modifier
                                // 【主因修复】摆放基座不再用 .align(BottomCenter)：那一项会让本节点的
                                // 【摆放位置】随面板高度逐帧变化；现在基座恒为 Box 默认的 TopStart=(0,0)
                                // （水平仍是居中：TopCenter，与旧 BottomCenter 的水平行为一致），
                                // 整段 p 的纵向位移全部交给绘制期的 translationY（见下方 graphicsLayer）。
                                .align(Alignment.TopCenter)
                                // 【P38 取证·只读】内容容器的真实摆放位置/尺寸（布局回调，非每帧）
                                .onGloballyPositioned { c ->
                                    if (AppDebugLog.enabled) {
                                        AppDebugLog.log(
                                            "P38L",
                                            "contentBox top=%.1f h=%d p=%.2f".format(
                                                c.positionInRoot().y, c.size.height, pNow()
                                            )
                                        )
                                    }
                                }
                                // 【性能修复】尺寸改成在 layout 阶段读 p（原来是组合期 .size(height = contentHeightDp.dp)）：
                                // p∈(1,2] 时高度每帧都变 → 整棵内容子树每帧重组 ✗；现在组合期不再订阅 p ✓
                                // 宽度恒定（全宽，与面板容器同源，避免 1px 错位）
                                // 【主因修复·内容槽恒高】返回尺寸（= 本节点 size）整段 p∈[0,2] 恒为常量终态尺寸
                                // （contentSlotHeightPxOf，默认恒取 contentHeightPxOf(0f) = 屏高×panelHeightFraction）：
                                //   · 子项 constraints 恒定 ⇒ 子树不重测（与揭幕式同源）；
                                //   · 本节点 size/摆放恒定 ⇒ 其图层不被 invalidate ⇒ 子树不重录 ✓✓（这才是 4.5~4.7ms/帧 的真凶）
                                //   · p 驱动的位移改到绘制期 translationY（RenderNode 属性，不使 display list 失效）
                                // 观感等价：p≤1 底边贴面板底边（自下而上揭开）、p≥1 顶边贴面板顶边（标题/列表随顶上浮），
                                //   与旧实现逐像素一致（实测 p=1.0/1.5/2.0 内容行相对面板顶边零位移）。
                                .layout { measurable, _ ->
                                    val p = pNow()
                                    // 【调试开关热更】本 lambda 读 DebugSwitches（槽高/页门控）—— 它们是
                                    // 普通 @Volatile 字段，不产生快照订阅 ✗。这里读一次 setSwitches 会自增的
                                    // revision（快照状态 ✓）⇒ 翻开关即让本节点失效重排，运行时 A/B 立即生效，
                                    // 不会拍到"半新半旧"的混合态 ✓（与容器裁剪层同一套机制，见 NEXT.md 的规矩）。
                                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                                    val w = fullWidthPx.toInt().coerceAtLeast(1)
                                    val hContent = contentMeasureHeightPxOf(p)
                                    val placeable = measurable.measure(Constraints.fixed(w, hContent))
                                    // 【首次组合开销·方案A·输入隔离】常驻组合后，收起到底（未 engaged）时
                                    // 把内容整体【停到屏幕外】：命中测试按摆放位置换算坐标 ⇒ 子树不在任何
                                    // 触摸坐标里 ⇒ 收起态胶囊的点击/拖动语义与"内容未组合"逐字相同 ✓
                                    // （历史坑：不可见的列表替胶囊吃掉点击 → 点不开面板 ✗）。
                                    // 只改摆放、不改尺寸/约束 ⇒ 不重测、不重录；p=0 时内容 alpha 恒为 0 且
                                    // 被容器裁掉 ⇒ 这次挪动在屏幕上不可见 ✓。关闭开关时恒为 0（旧行为）✓
                                    val park = if (DebugSwitches.precomposeListKeepAlive && !panelEngaged)
                                        parkOffsetPx() else 0f
                                    // 摆放恒为 (0, park)：本节点在面板内容容器里的位置逐帧恒定
                                    // （旧实现是 h - 槽高，逐帧变）
                                    layout(w, contentSlotHeightPxOf(p)) { placeable.place(0, park.toInt()) }
                                }
                                .graphicsLayer {
                                    alpha = contentAlphaOf(pNow())
                                    // 【主因修复】p 驱动的位移（= 旧实现 .align(BottomCenter) 的摆放 y）：
                                    //   p≤1 → 负值（内容底边贴面板底边）；p≥1 → 0（内容顶边贴面板顶边）
                                    // 【P43】再叠加"垂直居中补偿"（comp）：实测容器被居中在面板里
                                    //   （contentBox top = panelTop + (面板高-槽高)/2）⇒ 半屏下整条表头
                                    //   + 页首 633px 被推到面板上缘之外 ✗；补偿后 p=1 容器顶回到面板顶 ✓、
                                    //   p=2 补偿为 0（不变）✓。两项都只是 RenderNode 属性 ⇒ 零重排/零重录 ✓
                                    translationY = contentSlotShiftYOf(pNow()) + contentSlotCenterFixPxOf(pNow())
                                }
                        ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .nestedScroll(sheetConnection)
                        ) {
                            // 顶端把手 + 标题栏：整块作为拖拽区（v1.16.2 放大）。
                            // 手势语义：向下拖 → 收回到按钮；向上拖 → 先回到标准高度，
                            // 继续上滑 → 展开成「全部控制中心」（满高，露出全部按钮）。
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    // ===== 拖动 + 松手吸附：全部交给 Compose 官方 AnchoredDraggable =====
                                    // 锚点 0f(收起胶囊) / 1f(标准控制中心) / 2f(满高)；
                                    // 松手后由官方 fling 按 速度阈值(125dp/s) + 位置阈值(50%) 决定目标，
                                    // 并用 320ms + 原缓动曲线收口（等价于重构前的吸附手感）。
                                    // 手势接管时会自动取消正在跑的展开/收起动画（官方 MutatorMutex），
                                    // 因此不再需要 morph.stop() / wasOpenBeforePress 那套补偿逻辑。
                                    .anchoredDraggable(
                                        state = anchorState,
                                        orientation = Orientation.Vertical,
                                        // ===== 【方向修复·根因】下滑必须让 p 变小（旧手写逻辑 base - available.y/span）=====
                                        // 官方垂直拖动把手指位移按【向下为正】直接累加进 offset：
                                        //   AnchoredDraggableNode: newOffsetForDelta(delta) = offset + delta，
                                        //   delta = DragEvent.DragDelta 的 y 分量（Orientation.Vertical → 取低 32 位 = y），
                                        //   而 reverseDirection=false 时 reverseIfNeeded 原样返回（不取负）。
                                        // 本面板的锚点把 p=2f(满高) 放在【最大位置】上（0f@0 / 1f@spanOpen / 2f@spanOpen+spanExpand），
                                        // p 又是 offset 的单调增函数 → "手指下滑 = p 变大"。
                                        // 真机实测正是如此：慢慢下滑把 p 从 1.0 推到 2.0（关闭时变成长方体、回不到胶囊）✗。
                                        // reverseDirection = true 让官方把 Δy 与"松手速度"【一起取负】
                                        //   （bytecode：DragDelta 与 DragStopped.velocity 都过 reverseIfNeeded → ×-1），
                                        // 于是：下滑 → offset 变小 → p 变小 → 到 0f 回到胶囊（232dp×62dp、四角全圆）✓；
                                        //       上滑 → offset 变大 → p 变大 → 1f 标准控制中心 → 继续到 2f 满高 ✓。
                                        // 与嵌套滚动路径 dispatchRawDelta(-available.y) 的符号约定从此一致，
                                        // 且与重构前的旧手写拖动同向 ✓（两条路径必须同号，原先是反的）。
                                        reverseDirection = true,
                                        interactionSource = panelDragInteraction,
                                        // 【时序预设】收口动画 spec = panelSnapSpec（半屏→全屏段按档位取时长/曲线）
                                        // 【收回速度限幅】flingBehavior 换成 panelFling：只给【收回方向】的速度加
                                        //   上限（官方实现原样转发 ✓），避免极快下拉出现"1~2 帧跳回去" ✗
                                        flingBehavior = panelFling
                                    )
                                    // 【跟手取证·拖拽轨迹观察者】只读、从不 consume ⇒ 不改任何手势语义
                                    // （放在 anchoredDraggable 之后：官方手势先拿到事件）。
                                    // 逐事件打 (t, dy, y, p) → 可量化"手指动 → p 动"的帧数差（跟手）。
                                    // 开关：DebugSwitches.dragTrace（默认 false）且 AppDebugLog.enabled ✓
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            if (!(DebugSwitches.dragTrace && AppDebugLog.enabled)) return@awaitEachGesture
                                            val t0 = android.os.SystemClock.elapsedRealtime()
                                            var prev = down.position.y
                                            android.util.Log.i(
                                                "LGDrag", "把手 down y=%.1f p=%.3f".format(prev, pNow())
                                            )
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val ch = ev.changes.firstOrNull() ?: break
                                                val t = android.os.SystemClock.elapsedRealtime() - t0
                                                android.util.Log.i(
                                                    "LGDrag",
                                                    "把手 t=%dms dy=%.1f y=%.1f p=%.3f".format(
                                                        t, ch.position.y - prev, ch.position.y, pNow()
                                                    )
                                                )
                                                prev = ch.position.y
                                                if (!ch.pressed) break
                                            }
                                        }
                                    }
                            ) {
                                Column {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(22.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            Modifier
                                                .size(width = 46.dp, height = 4.dp)
                                                .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                                        )
                                    }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(onClick = {
                                            // 【图片编辑】两张二级页（更多设置 / 图片编辑）都先回一级页
                                            // 【P28a·批 1】第三张二级页（组件演示）同样先回一级页
                                            // 【P29】第四张二级页（开源许可与致谢，入口在「更多设置」底部）同样先回一级页
                                            if (panelLicenses) panelLicenses = false
                                            else if (panelImageEdit) panelImageEdit = false
                                            else if (panelComponentDemo) panelComponentDemo = false
                                            else if (panelAdvanced) panelAdvanced = false
                                            else animatePanelTo(0f, panelAnimDurationMs(open = false))
                                        }) {
                                            Text(
                                                // 【P29】用 onLicensesPage（= 状态 × 开关）：开关关时本页不渲染内容
                                                // ⇒ 表头也当成"没进过这一页"，与内容保持一致 ✓
                                                if (onLicensesPage || panelAdvanced || panelImageEdit || panelComponentDemo) "‹ 基础" else "← 关闭",
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                        Text(
                                            // 【图片编辑】二级页②的表头标题（只换文字，几何/交棒逻辑不变）
                                            // 【P28a·批 1】二级页③「组件演示」同构
                                            // 【P29】二级页④「开源许可与致谢」同构
                                            if (onLicensesPage) "开源许可与致谢"
                                            else if (panelImageEdit) "图片编辑"
                                            else if (panelComponentDemo) "组件演示"
                                            else if (panelAdvanced) "更多设置" else "控制中心",
                                            style = MaterialTheme.typography.titleLarge,
                                            // 【深色/亮色模式·2026-09-24】标题色改为【显式】取调色板：
                                            //   亮色档 = 0xFF000000（= 改动前 Compose 默认 LocalContentColor 的实测值，
                                            //     2026-09-14 截屏取色确认，逐字未变 ✓）；
                                            //   深色档 = 0xFFF2F5FA（否则默认色仍是纯黑，落在深色面板上完全不可读 ✗）。
                                            color = glassPal.panelTitle,
                                            // 【P05·无缝交棒】落点基准：标题基线的 root y = 盒顶(onGloballyPositioned) + 本盒基线(onTextLayout)
                                            onTextLayout = { r ->
                                                // 盒内基线（与位置无关的常量）；root 系基线在下面的布局回调里【p≥0.99 时】锁存
                                                handoffTitleBaselineInBoxPx[0] = r.firstBaseline
                                            },
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                // 【性能】原来这里挂了每帧执行的 onGloballyPositioned，
                                                // 把"标题中心"写进 titleCenterRoot（布局阶段写【快照状态】）。
                                                // 【P05】现在恢复测量，但只写【非快照普通字段】⇒ 布局期零订阅、零失效 ✓
                                                // （历史坑正是"布局期写快照状态 ⇒ 该节点再失效一轮"✗）
                                                // ⚠️ 链序：padding 在外、本回调在内 ⇒ 量到的是【字形盒】本身（不含 8dp 前导）
                                                .onGloballyPositioned { coords ->
                                                    val pos = coords.positionInRoot()
                                                    val bw = coords.size.width.toFloat()
                                                    val bh = coords.size.height.toFloat()
                                                    handoffTitlePx[0] = pos.x + bw / 2f
                                                    handoffTitlePx[1] = pos.y + bh / 2f
                                                    handoffTitleTopPx[0] = pos.y
                                                    // 【终态锚点自标定】只在满足全部条件时采样，拿到与 p 无关的两个常量：
                                                    //   ① p≥0.99（面板已到锚点）；② 不在二级页（二级页内容更高、表头会被滚动带走 ✗）；
                                                    //   ③ 采样位置合理（0 < dy < 192dp）——排除"内容被停到屏幕外"（parkOffset ≈ +2×屏高）
                                                    //      与动画中途的中间布局（实测这两种脏采样会把落点带偏几百 px ✗）
                                                    // 【P29】二级页④（开源许可与致谢）同样排除：许可页内容更高、
                                                    // 表头会被滚动带走 ⇒ 与「更多设置」同级处理 ✓
                                                    if (pNow() >= 0.99f && !panelAdvanced && !onLicensesPage) {
                                                        val cy2 = pos.y + bh / 2f
                                                        val dy2 = cy2 - (rootSizePx.height - expandedHeightPx)
                                                        if (dy2 > 0f && dy2 < 4f * HANDOFF_TITLE_DY_DP * densityScale) {
                                                            handoffTitleDyPx[0] = dy2
                                                            handoffTitleBaselinePx[0] =
                                                                pos.y + handoffTitleBaselineInBoxPx[0]
                                                        }
                                                    }
                                                }
                                                .then(
                                                    // 【P05】交棒开（且不是二级页）时：p<0.88 标题不显示（由屏幕级那一份落位），
                                                    // 0.88→0.97 在【原位】渐入 ⇒ 任一帧只有一个「控制中心」✓
                                                    // （历史坑：跟着内容在 0.42 就出现 → 与仍在旅行的标签重叠 = 两份 ✗）
                                                    // 关时整段不进组合修改 ⇒ 与改动前逐像素一致 ✓
                                                    // 【P29】许可页也是二级页 ⇒ 表头标题按二级页处理（恒显、不参与交棒渐入）
                                                    if (DebugSwitches.handoffLabel && !panelAdvanced && !onLicensesPage) {
                                                        Modifier.graphicsLayer {
                                                            // 运行时 A/B：读一次 revision（setSwitches 会自增）⇒ 翻开关即失效重算 ✓
                                                            @Suppress("UNUSED_EXPRESSION")
                                                            DebugBridge.revision.intValue
                                                            alpha = sstep(
                                                                HANDOFF_FADE_P0, HANDOFF_FADE_P1,
                                                                pNow().coerceIn(0f, 1f)
                                                            )
                                                        }
                                                    } else Modifier
                                                )

                                        )
                                        // ==================== 【P38·「组件演示」入口（显式按钮）】====================
                                        // 用户反馈「上游的玻璃控制组件还是没有应用成功」—— 第①类根因：入口根本看不见。
                                        // 旧入口是「背景壁纸」chips 行（横向滚动行）末尾的 chip，被视口裁到只剩 8px 宽
                                        // （uiautomator：节点 [1736,2341][1776,2405]、文字 [1768,2353][1776,2393]）✗。
                                        // 这里把入口放到【面板表头标题行右侧的空白处】：
                                        //   · 表头不在滚动区、不参与内容滚动 ⇒ 不会被裁、任何 p（含 p=1 半屏）都可见 ✓；
                                        //   · 追加在标题右侧空白区 ⇒ 既有元素（关闭按钮 / 标题 / 内容）坐标零位移 ✓
                                        //     （chip 高 32dp == 标题行现有内容高 ⇒ 表头行高不变 ✓）；
                                        //   · 只在【一级页】出现（二级页各自有自己的入口语义）✓；
                                        //   · 总开关 liquidComponentsDemo（默认开）+ liquidDemoEntryHeader（默认开）
                                        //     双重门控 ⇒ 关掉任一行即逐像素回到改动前 ✓
                                        if (DebugSwitches.liquidComponentsDemo &&
                                            DebugSwitches.liquidDemoEntryHeader &&
                                            !panelAdvanced && !panelImageEdit && !panelComponentDemo && !onLicensesPage
                                        ) {
                                            Spacer(Modifier.weight(1f))
                                            FilterChip(
                                                shape = PanelChipShape,
                                                selected = false,
                                                onClick = { panelComponentDemo = true },
                                                label = { Text("组件演示 ›", fontSize = 13.sp) },
                                                colors = PanelChipColors,
                                                modifier = Modifier
                                                    .padding(end = 6.dp)
                                                    .semantics { contentDescription = "组件演示" }
                                            )
                                        }
                                    }
                                }
                            }
                            // 参数主体（可滚动）：一级基础菜单 / 二级更多设置
                            // 【不可见时不组合】内容层现在会保留到"面板完全贴底"（见上面的门控修复），
                            // 但列表不可见（alpha=0）时若仍组合，会替胶囊吃掉点击/滑动。因此列表只在
                            // 【可见 / 手柄被按下 / 列表自己正在拖动】时组合；sheetDragging 期间必须保留，
                            // 否则拖动会在 p<0.42 那一刻被取消（同上）。
                            // 【首次组合开销·最终门控】判据见上方 listComposeGate（组合期只读布尔量，
                            // 只在翻转的那一帧重组一次）。本轮把"列表首次组合"这一记 42~67ms 的单帧
                            // 移出动画区间：
                            //   · 方案A（默认）= 启动时预热一次 + 之后常驻 ⇒ 打开动画里【没有】首次组合 ✓
                            //   · 方案B（默认）= 预热完成前的冷启动窗口里，推迟到"面板已定格"再组合 ✓
                            //   两个方案都关 ⇒ 完全回到旧行为（listComposeEarly 的 p>0.10）✓
                            // 原安全语义一条没删：不可见列表不许替胶囊吃点击（收起到底时内容被停到屏幕外
                            // 或被拆出组合）、手势/协程处理者不许被摘掉（panelDragging / sheetDragging /
                            // panelEngaged 全保留）。
                            if (listComposeGate) {
                            // 【取证·零成本】记录"列表首次组合"发生在哪条路径 / 哪个 p：
                            //   写入的是普通对象字段（非快照）⇒ 不触发重组；AppDebugLog 关闭时不拼字符串 ✓
                            //   验收判据：方案A → path=warm(A)、p≈0（预热，屏幕上完全不可见）；
                            //            方案B → path=deferred(B)、p≈1.000 且 offset 已贴锚点（动画已停）；
                            //            两个方案都关 → path=early(p>0.10)、p≈0.1~0.2（今天的 P04 行为）。
                            DisposableEffect(Unit) {
                                val p0 = pNow()
                                listDiag.composed = true
                                listDiag.firstP = p0
                                listDiag.path = when {
                                    DebugSwitches.precomposeListKeepAlive && listWarm && !panelEngaged -> "warm(A)"
                                    DebugSwitches.deferListComposeUntilSettled && showControlCenter && listDeferredReady -> "deferred(B)"
                                    DebugSwitches.listComposeEarly && !DebugSwitches.deferListComposeUntilSettled &&
                                        showControlCenter && listEarlyVisible -> "early(p>0.10)"
                                    contentVisible -> "contentVisible(p>0.42)"
                                    panelDragging -> "panelDragging"
                                    sheetDragging -> "sheetDragging"
                                    panelEngaged -> "panelEngaged"
                                    else -> "other"
                                }
                                if (AppDebugLog.enabled) {
                                    AppDebugLog.log("LIST", "首次组合 p=%.3f path=%s（keepAlive=%b defer=%b early=%b）"
                                        .format(p0, listDiag.path, DebugSwitches.precomposeListKeepAlive,
                                            DebugSwitches.deferListComposeUntilSettled, DebugSwitches.listComposeEarly))
                                }
                                onDispose { listDiag.composed = false }
                            }
                            GlassControlsPanel(
                            adapter = adapter,
                                uiState = uiState,
                                advanced = panelAdvanced,
                                // 【图片编辑】二级页②（与「更多设置」同级）：同一个面板，只换页
                                imageEdit = panelImageEdit,
                                // 【P28a·批 1】二级页③「组件演示」（同上：同一个面板，只换页）
                                componentDemo = panelComponentDemo,
                                // 【P29】二级页④「开源许可与致谢」（同上：同一个面板，只换页；
                                // 入口在「更多设置」底部「关于」节 ⇒ 用 onLicensesPage = 状态 × 总开关）
                                licenses = onLicensesPage,
                                // 【方案A·常驻组合的伴随项】收起到底（atRest）时把列表滚动位置复位：
                                // 今天靠"每次收起都把列表拆出组合"天然复位；常驻组合后必须显式复位，
                                // 才能保持"每次打开都从顶部开始"的原有行为 ✓（非默认路径下为 false，无副作用）
                                atRest = !panelEngaged,
                                onOpenAdvanced = { panelAdvanced = true },
                                onOpenImageEdit = { panelImageEdit = true },
                                // 【P28a·批 1】一级页「组件演示 ›」chip 的入口回调
                                onOpenComponentDemo = { panelComponentDemo = true },
                                // 【P29】「更多设置」底部「关于」节「开源许可与致谢 ›」入口回调
                                // （入口本身也在下一行开关里门控；这里的回调只负责切页状态）
                                onOpenLicenses = { panelLicenses = true },
                                // 【P46·两页拆分】底部玻璃标签栏切页（LiquidBottomTabs.onTabSelected
                                // → 写回屏幕层状态；页内容按 advancedTab 切换）✓
                                advancedTab = panelTwoPageTab,
                                onAdvancedTabSelected = { panelTwoPageTab = it },
                                // 【P43】列表底部余量提供者（layout 阶段被调用读 p ⇒ 零重组）：
                                // 半屏下把"视口高于可见区"的那截补进可滚范围 ⇒ 能滚到底看全 ✓
                                listBottomPadPx = listBottomPadPxState,
                                // 【3D 钻石演示】一级菜单「钻石」入口 → 打开演示页（页面自己画在 root Box 末端、
                                // 盖住面板与卡片；backdrop 用页面背景捕获层 ⇒ 钻石从壁纸采样折射）
                                onOpenDiamond = { diamondState.visible = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                            }
                            // 性能面板已移到屏幕右上角常驻（CompactPerformancePanel），
                            // 面板内不再重复展示
                        }
                        }
                    }
                    }
                    }
                }
                // ===== 【P46·二级页玻璃悬浮底栏（真实上游 LiquidBottomTabs）】 =====
                // 位置：面板容器 Box 的【最后一个子项】= 画在全部内容之上、被容器裁剪路径裁形；
                //   align(BottomCenter) 锚在面板【底边】—— 本容器的 layout 尺寸逐帧 =
                //   (wOf(p), hOf(p))（见上方 layout{}：Constraints.fixed 每帧用 p 重算）⇒ 放置每帧
                //   重算 ⇒ 底栏逐帧贴合面板底边（p=1 半屏 / p=2 满高 / 开合动画全程跟随，
                //   不需要任何 p 驱动代码）✓
                // 组件：ui/components/liquid/LiquidBottomTabs.kt（逐字移植上游）——「按压形变」（容器与
                //   旋钮的 pressProgress 缩放 + 旋钮 scaleX/scaleY 速度拉伸）、「二次折射旋钮」
                //   （指示层 layerBackdrop(tabsBackdrop) + 旋钮 lens(10/14dp) 采样组合背景）、
                //   「色散 lens」（chromaticAberration = !liquidSliderNoDispersion，默认带色散）、
                //   容器 vibrancy()+blur(8dp)+lens(24dp) —— 全部是上游组件自带 ✓【不是普通 TabRow】
                // 采样源：adapter.panelFillLayer（= 面板填充层，与 P45 真实控件同口径）；
                //   ⚠ 该层只在【真实控件总开关 liquidRealControls】打开时被逐帧录制 ⇒ 关掉它（回退档）时
                //   退化为 adapter.captureLayer（整页背景捕获层；与 GlassControlsPanel.panelButtonBackdrop
                //   同一套降级）——不是采样空层、【更不随该开关退出组合】✓（H4 修复：否则「高级设置」
                //   整页 + 开源许可入口不可达 ✗）
                // 门控：与【页拆分（panelAdvanced 的两页内容）】同源 = panelTwoPageTabs × 面板已被唤起
                //   （panelEngaged）× 非二级子页（图片编辑/组件演示/许可页）
                //   ⇒ 收起到底时【不组合】⇒ 绝不替胶囊吃点击（本项目历史坑）；
                //   【H4】✗ 不再含 liquidRealControls —— 底栏是关掉真实控件后【也必须存在】的切页入口
                //   （审计 H4：门控与页拆分不同源 ⇒ setSwitches liquidRealControls 0 后底栏消失 ⇒
                //    永远停在「外观设置」、「高级设置」整页不可达）
                //   alpha 走与内容同一条曲线（contentAlphaOf(p)）⇒ 开合过程一起淡入淡出 ✓
                // 回退：panelTwoPageTabs=false 一行关闭（底栏不进组合 + 页内容回单页）✓
                @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                // ===== 【P56·底栏两个并列项 = 一级菜单 / 更多设置（默认开）】 =====
                // 用户点名需求：悬浮底栏两个并列项 =「一级菜单」/「更多设置」——
                //   点「一级菜单」→ 面板回一级页（panelAdvanced=false）、点「更多设置」→ 进二级页（=true）；
                //   一级页也常驻底栏（只要面板被唤起）⇒ 两个页面靠底栏【并列互切】✓
                // 控件与手感：仍是同一条真上游 LiquidBottomTabs（tabsCount=2 / 同一个手 feel / 同一个
                //   traceTag / 同一串 modifier）⇒ 选中态、按压形变、二次折射旋钮、色散 lens、拖动跟手
                //   全部沿用组件自带实现（本开关与回退档都不碰组件内部 ✓）。两个项的文字样式与旧两 tab
                //   逐字相同（13.sp / 0xFF10203A）。
                // 回退：DebugSwitches.panelLevelTabs=false（一行）⇒ 下面 else 档 = 逐像素回到旧两 tab 行为
                //   （门控表达式 / 两个项的文字 / 回调 / contentDescription 全部与改动前逐字一致）✓
                val panelLevelBar = DebugSwitches.panelLevelTabs
                val showPanelBar =
                    if (panelLevelBar) {
                        // 新档：一级页 + 二级页都显示；二级子页（图片编辑/组件演示/许可页）自己带返回 ⇒ 不显示
                        panelEngaged && !panelImageEdit && !panelComponentDemo && !onLicensesPage
                    } else {
                        // 旧档（逐字保留原门控）：仅在二级「更多设置」显示
                        panelAdvanced && DebugSwitches.panelTwoPageTabs &&
                            !panelImageEdit && !panelComponentDemo && !onLicensesPage &&
                            panelEngaged
                    }
                if (showPanelBar) {
                    LiquidBottomTabs(
                        selectedTabIndex = {
                            if (panelLevelBar) (if (panelAdvanced) 1 else 0) else panelTwoPageTab
                        },
                        onTabSelected = { index ->
                            if (panelLevelBar) panelAdvanced = (index == 1) else panelTwoPageTab = index
                        },
                        backdrop = if (DebugSwitches.liquidRealControls) adapter.panelFillLayer
                        else adapter.captureLayer,
                        tabsCount = 2,
                        handFeel = LiquidHandFeel.current(),
                        traceTag = "PanelTabs",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = with(density) { navBarBottomPx.toDp() } + 8.dp)
                            .graphicsLayer { alpha = contentAlphaOf(pNow()) }
                            .semantics {
                                contentDescription = if (panelLevelBar) "页面标签栏" else "二级页标签栏"
                            }
                    ) {
                        // 【项 0】一级菜单（回退档 = 外观设置，逐字保留）
                        LiquidBottomTab({
                            if (panelLevelBar) panelAdvanced = false else panelTwoPageTab = 0
                        }) {
                            Text(if (panelLevelBar) "一级菜单" else "外观设置", fontSize = 13.sp, color = glassPal.barLabel)
                        }
                        // 【项 1】更多设置（回退档 = 高级设置，逐字保留）
                        LiquidBottomTab({
                            if (panelLevelBar) panelAdvanced = true else panelTwoPageTab = 1
                        }) {
                            Text(if (panelLevelBar) "更多设置" else "高级设置", fontSize = 13.sp, color = glassPal.barLabel)
                        }
                    }
                }
            }

            // ===== 【P05·四文字无缝交棒】「控制中心」：单一文字实例，从胶囊中心平滑移到/放大到面板标题槽 =====
            // 【为什么放在这里】root Box 的【最后一个子项】⇒ 画在面板容器之上（面板容器自带 clip，
            //   放它内部会被胶囊/面板的裁剪路径切掉 ✗；放外面才能从胶囊一路飞到标题槽 ✓）
            // 【为什么只有这一份】交棒开时胶囊里那份（labelVisible 门控）不再组合，面板标题在 p<0.88
            //   时 alpha=0 ⇒ 任一帧屏幕上只有一份「控制中心」✓
            // 【为什么不重复订阅】位置/缩放/透明度全在 graphicsLayer{}（绘制期）里算，组合期不读 p ✓
            // 【P05·①】乘性上色用的 Paint（复用同一实例 ⇒ 绘制热路径零分配 ✓；每帧只改 colorFilter）
            val handoffTintPaint = remember { androidx.compose.ui.graphics.Paint() }
            // 【P05·②灰阶过渡】两个 3 元素草稿数组（绘制热路径零分配）：① graphicsLayer 记账/日志用
            // ② drawWithContent 的 ColorMatrix 用。分开声明 ⇒ 两个 lambda 的执行顺序无关 ✓
            val handoffTintScratch = remember { FloatArray(3) }
            val handoffTintScratchDraw = remember { FloatArray(3) }
            MaterialTheme(colorScheme = GlassPanelColorScheme) {
                Text(
                    "控制中心",
                    style = MaterialTheme.typography.labelLarge.copy(
                        // 透明玻璃上的白字：加一层投影，落在亮背景上也读得清（与胶囊里那份逐字相同）
                        // 【P62·2026-09-18】灰阶途经段（p≈0.30~0.40）字色与面板填充层近乎同亮度
                        //   （对比 1.03~1.05 ✗，用户看不出来）⇒ 投影随进度【加浓】兜底（alpha
                        //   0x8A→0xFF、blur 6→3、offset 2→1），对比改由下限灰主导（≥4.5），
                        //   投影只是辅助；handoffContrastFloor=false 时回到原浓 0x8A/6/2（回退 ✓）
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color(
                                if (DebugSwitches.handoffContrastFloor) 0xFF0A1220 else 0x8A0A1220
                            ),
                            offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                            // 【深色/亮色模式·2026-09-24】深色档 = 白字飞行 ⇒ 投影加粗到 blur 18
                            //   （口径同收起态：投影跟随字形轮廓、连成一片 ⇒ 亮壁纸上也有 ≥4.5:1 的局部暗底，
                            //   且【不会】出现矩形补丁）。亮色档 = 改动前取值逐字未变 ✓。
                            blurRadius = if (glassPal.isDark) LABEL_HALO_BLUR_DARK
                                         else if (DebugSwitches.handoffContrastFloor) 3f else 6f
                        )
                    ),
                    // 透明玻璃上始终用白字（带投影），按下转玻璃后依然是白字（与胶囊里那份逐字相同）
                    // 【P05·①】源色抽成常量（日志/颜色推导要引用同一值；与胶囊里那份必须同色 ✓）
                    // 【深色/亮色模式·2026-09-24】飞行字源色改由调色板给（亮色档 = 0xFFF4F7FF 逐字未变 ✓；
                    //   深色档 = 0xFFF4F7FF 白字 + 深色投影，见上方 shadow）
                    color = glassPal.handoffLabel,
                    // 自身基线（盒内 y）：与"标题基线(root y)"配对做纵向落点对齐
                    onTextLayout = { r -> handoffLabelBaselinePx[0] = r.firstBaseline },
                    modifier = Modifier
                        // 摆放基准 = root Box 的 (0,0)；绝对位置全部由下面 graphicsLayer 的位移给出
                        .align(Alignment.TopStart)
                        .graphicsLayer {
                            // 【运行时 A/B】读一次 revision（setSwitches 会自增）⇒ 翻开关立即失效重算 ✓
                            @Suppress("UNUSED_EXPRESSION")
                            DebugBridge.revision.intValue
                            val on = DebugSwitches.handoffLabel
                            val p = pNow().coerceIn(0f, 1f)
                            // 起点 = 收起态胶囊中心（与面板 layout{} 同源：H - lift(0) - 胶囊高/2）
                            //   实测（uiautomator 胶囊标签 bounds [863,2762][977,2802]）中心 = (920, 2782)
                            //   → 本公式在 1840×2944/320dpi/navBar64/dens2 上给出 (920.0, 2782.0) ✓ 完全一致
                            val fromX = rootSizePx.width / 2f
                            val fromY = rootSizePx.height - liftOf(0f) - collapsedHeightPx / 2f
                            // 终点 x：面板标题中心 x（实测；锁存 ≥2px 抖动）。未测到（内容还没组合）→ 退回起点
                            // ⚠️ 阈值 2px 是有依据的：实测标题中心 x 是 285.x 的小数，逐帧在 285/286 之间跳 ✗，
                            //   1px 阈值下会把这种布局抖动当"目标变化"⇒ 轨迹出现 ±1px 假回弹（首轮实测 3 帧 ✗）
                            if (handoffTitlePx[0].isFinite() &&
                                (!handoffDestX[0].isFinite() ||
                                    kotlin.math.abs(handoffTitlePx[0] - handoffDestX[0]) >= 2f)
                            ) {
                                handoffDestX[0] = handoffTitlePx[0]
                            }
                            val toX = if (handoffDestX[0].isFinite()) handoffDestX[0] else fromX
                            // 终点 y：面板顶边(p=1) + 标题中心偏移 dy（自标定；未标定用 48dp）——
                            //   【必须与 p 无关】否则落点会随动画移动，把标签"往上拽再往下放" = 回弹 ✗
                            val panelTop1 = rootSizePx.height - expandedHeightPx
                            val dy = if (handoffTitleDyPx[0].isFinite()) handoffTitleDyPx[0]
                                     else HANDOFF_TITLE_DY_DP * densityScale
                            // 纵向细节：若已有"标题基线(root y)"（p≥0.99 实测锁存），按【基线】对齐
                            //   （本实例缩放到 k 倍后基线重合 ⇒ 14sp ↔ 22sp 两端字形精确重合 ✓）；
                            //   还没锁定时用盒中心 = 面板顶 + dy（误差 ≤1~2px，且首次开合后即被基线版替代）
                            val tb = handoffTitleBaselinePx[0]
                            val lb = handoffLabelBaselinePx[0]
                            val toY = if (tb.isFinite() && lb.isFinite())
                                tb - HANDOFF_SCALE_TITLE_OVER_LABEL * (lb - size.height / 2f)
                            else panelTop1 + dy
                            val t = sstep(HANDOFF_TRAVEL_P0, HANDOFF_TRAVEL_P1, p)
                            val sc = 1f + (HANDOFF_SCALE_TITLE_OVER_LABEL - 1f) * t
                            // ---- ① 中性轨迹（= 修复前的直线插值；逐帧日志里"过冲量"的基准 ✓）----
                            val nx = fromX + (toX - fromX) * t
                            val ny = fromY + (toY - fromY) * t
                            // ---- ②③ 自由轨迹 = 直线插值 + 抛物线抬升（handoffArc=false ⇒ 逐像素回到修复前 ✓）----
                            val arcOn = DebugSwitches.handoffArc
                            // 抛物线抬高：y -= A·4t(1-t)；t=0/1 处恒为 0 ⇒ 两端位置不变（端点等价 ✓）
                            val arcLift = if (arcOn)
                                HANDOFF_ARC_LIFT_DP * densityScale * 4f * t * (1f - t) else 0f
                            val fx = nx
                            val fy = ny - arcLift
                            // ---- ④ 面板内框（与面板 layout{} 同源：wOf/hOf/liftOf + BottomCenter 居中）----
                            // 边距 = max(16px, 文字盒半宽/半高×当前缩放) ⇒ 连字形盒都不出框（比"仅中心"更强 ✓）
                            val panelW = wOf(p).toFloat()
                            val panelTopF = rootSizePx.height - liftOf(p) - hOf(p).toFloat()
                            val panelBottomF = rootSizePx.height - liftOf(p)
                            val panelLeftF = (rootSizePx.width - panelW) / 2f
                            val marginMin = HANDOFF_INNER_MARGIN_DP * densityScale
                            val marginX = maxOf(marginMin, size.width / 2f * sc)
                            val marginY = maxOf(marginMin, size.height / 2f * sc)
                            val rideY = panelTopF + marginY
                            // ---- ⑤ 贴框融合（按 p 的窗口）：0 = 自由抛物线 → 1 = 贴着内框上边被面板托着上浮 ----
                            // 关键性质：y - 内框上边 = (1-k)·(自由轨迹 - 内框上边) ⇒ 符号始终跟着自由轨迹：
                            //   自由轨迹还在框内 ⇒ 融合结果也在框内 ✓；k=1 之后恒等于内框上边 ✓（永不越界）
                            val kRide = if (arcOn) sstep(HANDOFF_RIDE_P0, HANDOFF_RIDE_P1, p) else 0f
                            val mergedY = rideY + (1f - kRide) * (fy - rideY)
                            // ---- ⑥ 落位释放 + 框内回弹（朝框内 ⇒ 顶不到上边；窗口两端为 0 ⇒ 落点不变 ✓）----
                            val rU = ((p - HANDOFF_RELEASE_P0) / (HANDOFF_RELEASE_P1 - HANDOFF_RELEASE_P0))
                                .coerceIn(0f, 1f)
                            val bounce = if (arcOn)
                                HANDOFF_RELEASE_BOUNCE_DP * densityScale * 4f * rU * (1f - rU) else 0f
                            val kRel = if (arcOn) sstep(HANDOFF_RELEASE_P0, HANDOFF_RELEASE_P1, p) else 0f
                            val releasedY = mergedY + (toY - mergedY) * kRel + bounce
                            // ---- ⑦ 硬安全网（越界帧数 = 0 的最终保证；正常运行时不改变轨迹 ✓）----
                            // 融合/释放的结果按构造已恒在框内 ⇒ 这两个夹取在正常路径上是恒等映射
                            // （逐帧日志里可用 |cx-fx|、|cy-by| = 0 核实 ✓；只在意外早越界时兜底 ✓）
                            val cx = if (!arcOn) fx else run {
                                val lo = panelLeftF + marginX
                                val hi = panelLeftF + panelW - marginX
                                if (hi <= lo) (lo + hi) / 2f else fx.coerceIn(lo, hi)
                            }
                            val cy = if (!arcOn) releasedY else run {
                                val lo = rideY
                                val hi = panelBottomF - marginY
                                if (hi <= lo) (lo + hi) / 2f else releasedY.coerceIn(lo, hi)
                            }
                            // 内框自检（逐帧日志 inb 字段：要求恒 true ⇒ 越界帧数 = 0 ✓）
                            val inb = cx >= panelLeftF + marginMin && cx <= panelLeftF + panelW - marginMin &&
                                    cy >= panelTopF + marginMin && cy <= panelBottomF - marginMin
                            // ---- ⑧ 颜色：白 →（灰）→ 面板标题实测色（绘制期由 p 驱动 ✓；0.975 前插值到位 ⇒
                            //      交叉淡出那一刻两边同色 ✓，不再"白字消失 + 深字出现" ✗）----
                            // 有效字色 = 源色 × tint（乘性上色见下方 drawWithContent；tint=白 ⇒ 恒等 ✓）
                            // 【P05·②灰阶过渡】tint 由 handoffTintOf 统一给出（灰阶/旧版两条路径 + 窗口）
                            handoffTintOf(p, handoffTintScratch, glassPal)
                            val tintR = handoffTintScratch[0]
                            val tintG = handoffTintScratch[1]
                            val tintB = handoffTintScratch[2]
                            // 颜色插值进度（日志字段 ck）：灰阶档 = 时间线性窗口进度 u∈[0,1]
                            // （u=0.5 恰好落在中灰 0x808080 停靠点 ⇒ 日志里可直接读出"经过灰色"的时刻 ✓）
                            val ck = if (DebugSwitches.handoffColorBlend)
                                (if (DebugSwitches.handoffColorViaGray)
                                    handoffTimeU(p, DebugSwitches.handoffColorP0)
                                else sstep(HANDOFF_COLOR_P0, HANDOFF_COLOR_P1, p))
                            else 0f
                            val clR = glassPal.handoffLabel.red * tintR
                            val clG = glassPal.handoffLabel.green * tintG
                            val clB = glassPal.handoffLabel.blue * tintB
                            // 透明度：交棒关 / 二级页（标题是「更多设置」）→ 0；交棒开 → 0.975→0.998 原位淡出
                            // 【收起态黑字·2026-09-23】p==0 且 capsuleLabelBlack=true（默认）⇒ 本实例【让位】给
                            //   收起态标签（画在玻璃之上的那份 = 收起态 Text，纯黑 0xFF000000，见 root Box 内 run 块）：
                            //   本实例位于玻璃之下，实测字形芯=0.459×源色+0.541×玻璃（纯黑也只剩 (70,72,74) 达不到 0/0/0）
                            //   ⇒ 静置帧 alpha=0 防双影；p>0 交棒照旧（la 与白→灰→黑路径逐字未动 ✓）。
                            //   一行回退：setSwitches capsuleLabelBlack 0（下面 graphicsLayer 已陪读 revision ⇒ 广播即生效）
                            val restHandover = DebugSwitches.capsuleLabelBlack && p <= 0f
                            val la = if (!on || panelAdvanced || onLicensesPage) 0f
                                     else if (restHandover) 0f
                                     else 1f - sstep(HANDOFF_FADE_P0, HANDOFF_FADE_P1, p)
                            alpha = la
                            scaleX = sc
                            scaleY = sc
                            // 位移把【盒中心】搬到 (cx, cy)：摆放原点在 root (0,0)，盒内中心 = size/2
                            translationX = cx - size.width / 2f
                            translationY = cy - size.height / 2f

                            // ===== 【P05·逐帧取证日志】数值验收的主证据 =====
                            // 用户纠正（2026-09-14）：动画/时序类用【逐帧日志】做数值验证，不用录屏提帧 ✗。
                            // 每次绘制打一行（含 p / 文字中心 / 缩放 / 透明度 / 走向 / 胶囊中心 / 标题槽），
                            // 同时进环形缓冲（AppDebugLog ⇒ dumpState/导出可见）与 logcat（tag=LGHandoff，
                            // 与 LGLayout 同风格，供 `adb logcat -d -s LGHandoff` 导出 CSV 做数值验收）。
                            // 只在日志开关打开时拼字符串 ⇒ 关闭时零开销 ✓（与 LGLayout 一致）
                            if (AppDebugLog.enabled && on) {
                                val d = p - handoffPrevP[0]
                                val dir = when {
                                    !handoffPrevP[0].isFinite() -> "idle"
                                    d > 2e-4f -> "open"
                                    d < -2e-4f -> "close"
                                    else -> "hold"
                                }
                                handoffPrevP[0] = p
                                // 面板标题的自身 alpha（交棒开时 0.88→0.97 渐入；二级页恒 1）
                                val ta = if (panelAdvanced || onLicensesPage) 1f
                                         else sstep(HANDOFF_FADE_P0, HANDOFF_FADE_P1, p)
                                // 面板标题实际可见度还要乘内容层 alpha（contentAlphaOf）
                                val line = ("n=%d t=%d p=%.4f dir=%s lx=%.1f ly=%.1f sc=%.4f la=%.3f " +
                                        "ta=%.3f ca=%.3f capx=%.1f capy=%.1f dstx=%.1f dsty=%.1f " +
                                        "mx=%.1f my=%.1f pt1=%.1f dy=%.1f tb=%.1f lb=%.1f adv=%b " +
                                        // 【本轮新增·一律追加在末尾 ⇒ 既有解析不受影响 ✓】P05 抛物线/内框/颜色验收字段：
                                        //   nx,ny = 中性位置（修复前轨迹 = 过冲量的基准 ✓）；
                                        //   fx,fy = 约束前的自由轨迹（抛物线+回弹，未夹内框）；
                                        //   arc = 抛物线抬升量(px)；ov = 本帧位置相对中性位置的位移(px)；
                                        //   ft/fb/fl/fr = 实际用的内框四边（边距含字形盒半宽/半高×缩放）；
                                        //   pt/pb/pl/pr = 面板可视矩形（可独立复算 16px 内框 ✓）；
                                        //   tc = 飞行字有效色（源色×tint）；ptc = 面板标题色；ck = 颜色插值进度；
                                        //   inb = 内框自检（要求恒 true ⇒ 越界帧数 = 0 ✓）
                                        "nx=%.1f ny=%.1f fx=%.1f fy=%.1f arc=%.1f ov=%.1f " +
                                        "by=%.1f ry=%.1f kr=%.3f kl=%.3f bn=%.1f " +
                                        "ft=%.1f fb=%.1f fl=%.1f fr=%.1f pt=%.1f pb=%.1f pl=%.1f pr=%.1f " +
                                        "tc=%06X ptc=%06X ck=%.3f inb=%b")
                                    .format(
                                        handoffFrameNo[0]++, android.os.SystemClock.elapsedRealtime(),
                                        p, dir, cx, cy, sc, la, ta, contentAlphaOf(p),
                                        fromX, fromY, toX, toY,
                                        handoffTitlePx[0], handoffTitlePx[1],
                                        panelTop1, dy, tb, lb, panelAdvanced,
                                        nx, ny, fx, fy, arcLift,
                                        kotlin.math.hypot((cx - nx).toDouble(), (cy - ny).toDouble()),
                                        releasedY, rideY, kRide, kRel, bounce,
                                        panelTopF + marginY, panelBottomF - marginY,
                                        panelLeftF + marginX, panelLeftF + panelW - marginX,
                                        panelTopF, panelBottomF, panelLeftF, panelLeftF + panelW,
                                        handoffRgbHex(clR, clG, clB),
                                        handoffRgbHex(
                                            glassPal.handoffTitle.red,
                                            glassPal.handoffTitle.green,
                                            glassPal.handoffTitle.blue
                                        ),
                                        ck, inb
                                    )
                                AppDebugLog.log("HANDOFF", line)
                                android.util.Log.i("LGHandoff", line)
                            }
                        }
                        // 【可读性·飞行字光晕·2026-09-24→2026-09-25 默认已关】交棒四字在玻璃【之下】（可见字 =
                        //   0.459×源色 + 0.541×玻璃）⇒ 起点黑化后，黑字落在暗壁纸透出的暗玻璃上
                        //   会被"吃掉"（实测 云海日出 p=0.02 仅 1.42:1 ✗）。这里在字形后面补一层
                        //   极淡白光晕，把【紧邻背景】抬起来（同处玻璃之下 ⇒ 抬亮不被玻璃衰减 ✓）。
                        //   ⚠️ 必须画在下面 drawWithContent 的 saveLayer【之前】⇒ 不参与乘性上色 ✓
                        //   （若做成字形自己的 shadow，起点 tint=0 会把它一起乘成黑 ✗ = 正好在最需要它的帧失效）
                        // 【② 本轮复核结论＝同步关掉（同一开关）】理由三条：
                        //   ① 这就是用户点名的"黑字底下加白底面"的同一手法（零偏移白 radial），
                        //      只是画在飞行字上 —— 用户要"拆掉"，两处必须一起拆，否则动画中途又会闪出白底 ✗；
                        //   ② 交棒起点那几帧的字正落在【已实底灰化】的胶囊上（p≲0.10 时填充 1.00 不透明）
                        //      ⇒ 早段"落进暗玻璃被吃掉"的成因消失，补光失去对象 ✓；
                        //   ③ 门控沿用 capsuleLabelHalo（现已默认 false）⇒ 默认路径本层不画 ✓，
                        //      一行回退（setSwitches capsuleLabelHalo 1）即可把它连同收起态补光一起放回。
                        //   实测（改后 × 全 6 壁纸 × 两档）本轮已重新跑过，见交付报告 D 项口径。
                        //   【深色/亮色模式·2026-09-24】深色档【不画】这一层：白字的局部暗底改由
                        //   上方那份【字形粗投影】（blur 18）承担 —— 投影跟随字形轮廓，不会露出盒子直边 ✓。
                        //   （该粗投影本轮也已随 capsuleLabelHalo 默认关 ⇒ 暗档默认同样无任何补光 ✓）
                        .drawBehind {
                            // 【深色/亮色模式·2026-09-24】深色档【不画】这一层：白字的局部暗底改由
                            //   上方那份【字形粗投影】（blur 18）承担 —— 投影跟随字形轮廓，不会露出盒子直边 ✓。
                            // 【D 项·2026-09-25 尝试与结论】曾试验「暗档也画白光晕」，实测对比不升反降
                            //   （p=0.12 1.65→1.45、p=0.24 1.91→1.37：halo 被同一玻璃混合、成了劣势项）⇒ 回退
                            //   为【暗档不画】。D 项结论（如实）：暗壁纸早段对比受【字芯被玻璃混入自身】的结构
                            //   上限锁死（可见字 = 0.459×源色 + 0.541×玻璃 ⇒ 恒色白字时字芯 L≈0.11、上限
                            //   ≈2.5:1 量级），抬高需改层序/玻璃光学 = 用户明令禁止的参数域 ⇒ 本轮【未达
                            //   4.5，如实记录】，诊断与上限数字如上。
                            if (!glassPal.isDark && DebugSwitches.capsuleLabelHalo && DebugSwitches.capsuleLabelBlack) {
                                // 【亮色档】逐字保留改动前的实现（两色渐变 + 0.55×max 半径，一字未改 ✓）
                                drawRect(
                                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors = listOf(Color(LABEL_HALO_ARGB), Color(0x00FFFFFF)),
                                        center = androidx.compose.ui.geometry.Offset(
                                            size.width / 2f, size.height / 2f
                                        ),
                                        radius = kotlin.math.max(size.width, size.height) * 0.55f
                                    )
                                )
                            }
                        }
                        // ===== 【P05·①颜色过渡】白 →（灰）→ 面板标题实测色：绘制期由 p 驱动（组合期不订阅 p ✓）=====
                        // 乘性上色（Modulate）：最终色 = 本节点内容 × tint。
                        //   · tint = 白 ⇒ 恒等 ⇒ p<0.60 整段跳过 ⇒ 与胶囊白逐像素一致 ✓（端点等价的前提 ✓）
                        //   · tint → 标题色 ⇒ 白字渐变为标题色 ⇒ 交叉淡出那一刻两边同色（不再白跳黑 ✗）✓
                        // ⚠️ 必须用 saveLayer 把效果限制在本节点内容上：裸 drawRect(Modulate) 会去乘
                        //   【下方已画好的整块背景】✗（会把面板背景涂暗）；saveLayer 隔离后只作用于文字+投影 ✓
                        // ⚠️ 已知取舍：投影与字形用同一份绘制内容 ⇒ 投影随 tint 同步变深（到终点 ≈ 纯黑投影）；
                        //   它在淡出窗口内与文字一起消失，观感与修复前一致（修复前的投影本来就是深蓝黑）✓
                        .drawWithContent {
                            // 【P05·②灰阶过渡】tint 的唯一来源 = handoffTintOf（与 graphicsLayer 分支同一契约）：
                            //  灰阶档 = 白→中灰→黑（经过灰色 ✓）；旧档 = 单段白→黑（改动前行为 ✓）
                            handoffTintOf(pNow().coerceIn(0f, 1f), handoffTintScratchDraw, glassPal)
                            val tr = handoffTintScratchDraw[0]
                            val tg = handoffTintScratchDraw[1]
                            val tb2 = handoffTintScratchDraw[2]
                            // tint = 白（窗口起点之前 / 颜色过渡关闭）⇒ 恒等 ⇒ 整段跳过（与修复前逐像素一致 ✓）
                            if (tr >= 1f && tg >= 1f && tb2 >= 1f) {
                                drawContent()
                                return@drawWithContent
                            }
                            // 【实现】用【颜色矩阵·对角缩放】表达"乘性上色"：out.rgb = (tr,tg,tb)·in.rgb、
                            //   alpha 不变 ⇒ 与 Modulate 逐像素等价，但走的是成熟的 ColorMatrixColorFilter
                            //   实现路径 ✓（⚠️ 本轮实测：PorterDuff/Multiply 版的 ColorFilter 在
                            //   saveLayer 上【完全没有效果】✗ —— 白字始终是白的，故改用矩阵版 ✓）
                            handoffTintPaint.colorFilter =
                                androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                    androidx.compose.ui.graphics.ColorMatrix(
                                        floatArrayOf(
                                            tr, 0f, 0f, 0f, 0f,
                                            0f, tg, 0f, 0f, 0f,
                                            0f, 0f, tb2, 0f, 0f,
                                            0f, 0f, 0f, 1f, 0f
                                        )
                                    )
                                )
                            drawIntoCanvas { canvas ->
                                canvas.saveLayer(
                                    Rect(Offset.Zero, this@drawWithContent.size), handoffTintPaint
                                )
                                this@drawWithContent.drawContent()
                                canvas.restore()
                            }
                        }
                )

                // ===== 【6. 3D 钻石演示页】=====
                // 由一级菜单「钻石」入口打开（state.visible）；backdrop = 页面背景捕获层（壁纸），
                // 钻石对其做物理正确实体折射（入面 Snell → 内部 TIR → 出射再折射 → 采样背景）。
                // 位置：root Box 的【最后一个子项】⇒ 盖住卡片/面板/交棒文字；不开时不组合、零成本。
                // ✗ 不给它加 graphicsLayer 旋转/缩放（本项目踩过“采样坐标对不上”的坑）。
                if (diamondState.visible) {
                    // 【z 序修复 2026-09-17】显式 zIndex（默认开；回退：setSwitches diamondPageOnTop 0）
                    //   · 读一次 revision：翻开关后当场失效重算（本项目"@Volatile 开关不产生订阅"的既有对策）
                    //   · 值 = DIAMOND_PAGE_Z_INDEX(10.5f) > 面板容器 panelZ(10f) ⇒ 钻石页盖住面板/胶囊；
                    //     < 看板 zIndex(11f) ⇒ 看板照旧悬在最顶（用户口径不变）
                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                    val diamondPageZ = if (DebugSwitches.diamondPageOnTop) DIAMOND_PAGE_Z_INDEX else 0f
                    com.example.liquidglass.diamond.DiamondDemoPage(
                        state = diamondState,
                        backdrop = adapter.captureLayer,
                        sizeFraction = uiState.glassSize,
                        onBack = { diamondState.visible = false },
                        modifier = Modifier.fillMaxSize().zIndex(diamondPageZ)
                    )
                }
            }
        }
    }
}

/**
 * 【A档遮挡 · 「面板没能 100% 遮住卡片」修复】面板【当前可见形状】的“洞”（root 坐标，px）。
 *
 * 为什么需要它（缺陷）：A 档（[DebugSwitches.cardOverPanel]=false，"面板压住卡"）的遮挡完全依赖
 * 【面板自己的不透明度】，而面板是半透明玻璃材质：展开态填充层 alpha=0.9（fillAlphaOf(1)=0.9）
 * ⇒ 仍有 10% 透射。卡的高光边/暗边比它背后的背景亮/暗 ~100+ 灰阶 ⇒ 透射后还剩 |Δ|≈12~22 灰阶
 * ⇒ 卡的高光边在面板内【全程可见】、卡底边高光带在面板内成一条横向残差带
 * （= 用户/走查报的「卡从面板边缘漏出来一点」）✗。
 *
 * 修法：把卡在【面板形状以内】的像素整段不画（绘制期 `clipPath(洞, ClipOp.Difference)`）⇒
 * 遮挡由【几何】给出，与材质透明度无关 ✓；面板矩形以外的像素一个不动 ✓。
 *
 * 本类的两条纪律：
 *  1. **普通字段（非快照状态）** —— 面板的 layout 块每帧写、卡片绘制期读；
 *     写不触发任何失效（布局期写状态会让节点再失效一轮，本项目已否掉那种写法）；
 *  2. **布局期先于同帧所有绘制** ⇒ 卡片读到的永远是【本帧正在用的】那份几何（不滞后一帧）。
 *     写方与读方共用同一个 [PanelClipShapeCache] 实例 + 同一组 w/h/lift ⇒ 与面板自己的裁剪同源、不漂。
 */
private class PanelHole {
    var left = 0f; var top = 0f; var width = 0f; var height = 0f
    /** 面板容器裁剪形状（面板局部坐标，size = (width, height)）。 */
    var shape: Shape? = null
    /** 每次写入自增（绘制期路径缓存的键）。 */
    var version = 0

    fun update(left: Float, top: Float, width: Int, height: Int, shape: Shape) {
        this.left = left; this.top = top; this.width = width.toFloat(); this.height = height.toFloat()
        this.shape = shape
        version += 1
    }

    private var cachedKey: String? = null
    private var cachedPath: Path? = null

    /**
     * 洞路径（root 坐标）；null = 还没有可用的面板几何（首帧）⇒ 调用方【不裁】= 安全回退 ✓。
     * 路径按 [version]（面板几何档）+ 密度缓存：同一档位下几块卡共享同一条 Path（只读）✓。
     */
    fun pathFor(density: Density, layoutDirection: LayoutDirection): Path? {
        val s = shape ?: return null
        if (width <= 0f || height <= 0f) return null
        val key = "$version:${density.density}:$layoutDirection"
        if (cachedKey == key) return cachedPath
        val outline = s.createOutline(Size(width, height), layoutDirection, density) as? Outline.Generic
            ?: return null
        val p = Path()
        p.addPath(outline.path, Offset(left, top))
        cachedKey = key; cachedPath = p
        return p
    }
}

/**
 * 【A档遮挡】洞裁剪：本节点（root 坐标空间）在【面板形状以内】的像素整段不画。
 *
 * · enabled = false ⇒ 返回 this（零改动 = 一行回退 ✓）
 * · [observeProgress]：绘制期读一次面板进度（快照状态）⇒ 面板一动本节点【当帧重绘】，
 *   洞永远不会滞后一帧（否则开合动画里会看到"卡从旧洞边缘露出来"✗）
 * · 洞几何缺失（首帧）/面板矩形以外 ⇒ 原样绘制 ✓
 */
private fun Modifier.hideInsidePanelHole(
    enabled: Boolean,
    hole: PanelHole,
    observeProgress: () -> Unit
): Modifier = if (!enabled) this else this.drawWithContent {
    observeProgress()
    val path = hole.pathFor(this, layoutDirection)
    if (path == null) drawContent()
    else clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
}

/**
 * 面板裁剪形状缓存（普通类，**不是**快照状态；只在绘制 lambda 内使用）。
 *
 * 为什么需要：`graphicsLayer { shape = G2RoundedShape(...) }` 每帧都会新建一个 Shape 实例，
 * 而官方图层按"实例是否相等"判断是否重建 Outline → 每帧都要重算一条 G2 连续圆角 Path ✗。
 * 这里把四个圆角半径按 [QUANT] 档位量化后复用同一个实例：
 *  - 量化步长 0.25dp（density 2.75 时 ≈ 0.7px，亚像素 → 肉眼不可见）；
 *  - 端点档位是精确值：p=0 → 31dp 胶囊半径、p≥1 → 28dp 底部圆角 → 观感常量不变 ✓；
 *  - 普通字段读写不产生快照订阅/失效，也不改变绘制时序（纯缓存）✓
 */
private class PanelClipShapeCache {
    private var lastTl = Float.NaN
    private var lastTr = Float.NaN
    private var lastBr = Float.NaN
    private var lastBl = Float.NaN
    private var cached: G2RoundedShape? = null

    /** 半径按 0.25dp 档位复用实例；档位没变就直接返回同一个 Shape（官方图层不再重建 Outline）。 */
    fun shapeOf(topLeftDp: Float, topRightDp: Float, bottomRightDp: Float, bottomLeftDp: Float): G2RoundedShape {
        val qTl = quantize(topLeftDp)
        val qTr = quantize(topRightDp)
        val qBr = quantize(bottomRightDp)
        val qBl = quantize(bottomLeftDp)
        val c = cached
        if (c != null && qTl == lastTl && qTr == lastTr && qBr == lastBr && qBl == lastBl) return c
        val created = G2RoundedShape(qTl.dp, qTr.dp, qBr.dp, qBl.dp)
        cached = created
        lastTl = qTl; lastTr = qTr; lastBr = qBr; lastBl = qBl
        return created
    }

    /** 0.25dp 档位（4 档 / dp）。 */
    private fun quantize(v: Float): Float = kotlin.math.round(v * 4f) / 4f

    private var lastTiTl = Float.NaN
    private var lastTiTr = Float.NaN
    private var lastTiBr = Float.NaN
    private var lastTiBl = Float.NaN
    private var lastInflatePx = Float.NaN
    private var lastInflateDensity = Float.NaN
    private var cachedInflated: InflatedG2Shape? = null

    /**
     * 与 [shapeOf] 同一档 G2 形状，但【整体外扩 inflatePx】（半径同步 +inflatePx/density）。
     * 用途：把"无 AA 的路径硬裁剪边"退到玻璃 SDF 羽化带之外（见 DebugSwitches.panelEdgeAa）。
     * 实例同样按 0.25dp 档位复用：官方图层按"实例是否相等"判断是否重建 Outline。
     */
    fun inflatedOf(
        topLeftDp: Float,
        topRightDp: Float,
        bottomRightDp: Float,
        bottomLeftDp: Float,
        inflatePx: Float,
        density: Float
    ): InflatedG2Shape {
        val qTl = quantize(topLeftDp)
        val qTr = quantize(topRightDp)
        val qBr = quantize(bottomRightDp)
        val qBl = quantize(bottomLeftDp)
        val inflateDp = if (density > 0f) inflatePx / density else inflatePx
        val c = cachedInflated
        if (c != null &&
            qTl == lastTiTl && qTr == lastTiTr && qBr == lastTiBr && qBl == lastTiBl &&
            inflatePx == lastInflatePx && density == lastInflateDensity
        ) {
            return c
        }
        val created = InflatedG2Shape(
            inner = G2RoundedShape(
                (qTl + inflateDp).dp, (qTr + inflateDp).dp, (qBr + inflateDp).dp, (qBl + inflateDp).dp
            ),
            inflatePx = inflatePx
        )
        cachedInflated = created
        lastTiTl = qTl; lastTiTr = qTr; lastTiBr = qBr; lastTiBl = qBl
        lastInflatePx = inflatePx; lastInflateDensity = density
        return created
    }
}

/**
 * G2 形状的【外扩】版本（面板/胶囊边缘抗锯齿用）：
 * 把 [inner] 的轮廓向外扩 [inflatePx]（半径已在构造 [inner] 时同步加大 → 角弧是"原弧 + inflatePx"，
 * 不会被内切）。于是那条【无 AA 的路径硬裁剪边】落在玻璃 SDF 羽化带之外（coverage≈0.2，肉眼不可见），
 * 弧线上看到的就是玻璃自己的 AA 边 ✓（见 DebugSwitches.panelEdgeAa）。
 *
 * ⚠️ 外扩量必须远小于玻璃层相对内容的余量（本项目历史事实：卡片玻璃层 8dp/19dp、
 * 面板玻璃层过采样 pad ≈18.5px）。
 */
private class InflatedG2Shape(
    private val inner: G2RoundedShape,
    private val inflatePx: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val outer = inner.createOutline(
            Size(size.width + inflatePx * 2f, size.height + inflatePx * 2f),
            layoutDirection,
            density
        ) as Outline.Generic
        val path = Path().apply { addPath(outer.path, Offset(-inflatePx, -inflatePx)) }
        return Outline.Generic(path)
    }
}

/**
 * 【取证】设置列表的组合状态记录（方案A/B 的验收读数）。
 *
 * 为什么用普通类而不是 Compose 状态：这些字段是在"列表刚进组合"的那一帧写入的，
 * 若用 mutableStateOf 会立刻再触发一轮重组（正是我们想省掉的那一帧的成本 ✗）；
 * 普通字段（@Volatile，跨线程读安全）写入零开销、不产生订阅，只被 debug/DebugBridge 的
 * dumpState 读取（`listgate` 行）⇒ 对绘制/布局零影响 ✓
 */
private class ListComposeDiag {
    /** 列表当前是否在组合中（进入门控 = true，被拆出组合 = false）。 */
    @Volatile var composed: Boolean = false
    /** 本次进入组合时的面板进度 p（验收判据：方案A ≈0 / 方案B ≈1.000 / 旧行为 ≈0.1~0.2）。 */
    @Volatile var firstP: Float = Float.NaN
    /** 进入组合的路径标签（warm(A) / deferred(B) / early(p>0.10) / 旧语义标签）。 */
    @Volatile var path: String = "-"
}

/** 计算卡片 offset 允许范围（相对默认位置），保证卡片不拖出安全区域。 */
private fun cardOffsetBounds(
    rootSize: IntSize,
    cardSize: IntSize,
    defaultTopLeft: Offset,
    statusBarTopPx: Int,
    navBarBottomPx: Int,
    marginPx: Float
): Rect {
    val minX = marginPx - defaultTopLeft.x
    val minY = statusBarTopPx.toFloat() + marginPx - defaultTopLeft.y
    val maxX = rootSize.width - cardSize.width - marginPx - defaultTopLeft.x
    val maxY = rootSize.height - navBarBottomPx.toFloat() - cardSize.height - marginPx - defaultTopLeft.y
    return Rect(minX, minY, maxX, maxY)
}

/** Material 3 主题（浅色 / 深色跟随系统）。 */
@Composable
private fun LiquidGlassTheme(content: @Composable () -> Unit) {
    // 【深色/亮色模式自动切换·2026-09-24】
    //   darkTheme 现在由【三档主题模式】决定：跟随系统（isSystemInDarkTheme，见 GlassTheme.isGlassDark）/
    //   强制亮色 / 强制暗色。同时把对应的调色板 provide 给整棵树（面板/胶囊/交棒/许可页全部读它）。
    //   亮色档的调色板 = 改动前的全部硬编码取值逐字冻结 ⇒ 亮色档逐像素零回归 ✓。
    //   一行回退：DebugSwitches.themeAutoSwitch = false ⇒ 恒亮色档（= 改动前行为）。
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue   // 翻开关后立即重组生效
    val darkTheme = DebugSwitches.themeAutoSwitch && isGlassDark()
    val pal = if (darkTheme) DarkGlassPalette else LightGlassPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassPalette provides pal) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            content = content
        )
    }
}
