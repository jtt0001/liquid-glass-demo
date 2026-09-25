/*
 * 【P28a·批 1 手感标定】液态组件的手感参数组（弹簧 / 阈值 / 拖动跟随方式）。
 *
 * 为什么有这个文件：上游 catalog 的组件把弹簧参数**硬编码**在组件内部
 *   上游 DampedDragAnimation（utils/DampedDragAnimation.kt）：
 *     valueSpec              = spring(1f, 1000f, visibilityThreshold)   // 值跟随（拖动中逐帧 animateTo）
 *     velocitySpec           = spring(0.5f, 300f, threshold * 10f)      // 速度读数动画
 *     pressProgressSpec      = spring(1f, 1000f, 0.001f)                // 按压进度
 *     scaleXSpec             = spring(0.6f, 250f, 0.001f)               // 按压横向缩放
 *     scaleYSpec             = spring(0.7f, 250f, 0.001f)               // 按压纵向缩放
 *     （LiquidToggle 另传 initialScale = 1f / pressedScale = 1.5f / visibilityThreshold = 0.001f）
 *   上游 InteractiveHighlight（utils/InteractiveHighlight.kt）：
 *     pressProgressSpec      = spring(0.5f, 300f, 0.001f)
 *     positionSpec           = spring(0.5f, 300f, Offset.VisibilityThreshold)
 *
 * 用户口径（2026-09-14）：「他这个拖动阻尼有点高，现在我们这个就刚好」
 *   ⇒ **阻尼手感已按我们的基准标定，非上游原值**（本句为交付清单中的固定结论句）。
 *   ⇒ 默认 = [Baseline]（本工程现状的实测常量），上游原值 = [Upstream]，两者可在【组件演示】页
 *      用一行开关（DebugSwitches.liquidDampingUpstream）即时 A/B，用户可亲自对比 ✓。
 *
 * [Baseline] 每个数字的出处（本工程仓库内可逐条核对）：
 *   · valueSpec     = spring(0.78, 380)  ← GlassParameters：BALANCED 预设 springDampingRatio 0.78 /
 *                                          springStiffness 380（LiquidGlassCard 的回弹弹簧，逐字同参）
 *   · velocitySpec  = spring(0.78, 380)  ← 同上（速度读数用同一组，不再单独发明参数）
 *   · pressSpec     = spring(0.42, 820)  ← LiquidGlassScreen「blockPressProgress」收起按钮按压进度弹簧
 *   · scaleSpec     = spring(0.40, 950)  ← LiquidGlassScreen「blockPressScale」收起按钮按压缩放弹簧
 *   · dragFollowDirect = true            ← 本工程卡片/面板的拖动语义 = 手指位移 1:1 跟手
 *                                          （LiquidGlassCard 拖动直接写 offsetX/offsetY；面板走官方
 *                                           AnchoredDraggable 的 1:1 位移），而不是上游"弹簧追手指"
 *   · 速度限幅      = ±2400 px/s          ← LiquidGlassCard.state.setVelocity 的 coerceIn(±2400)
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import com.example.liquidglass.debug.DebugSwitches

/**
 * 一组手感参数（弹簧 + 拖动跟随方式）。
 *
 * @param label 中文标签，用于演示页显示与逐帧日志（logcat tag = LGLiq）。
 * @param directDragFollow true = 拖动中值 1:1 直接跟随手指（本工程现状语义）；
 *                         false = 上游语义（每一帧把值当作弹簧目标 animateTo，手指越快滞后越大）。
 */
data class LiquidHandFeel(
    val label: String,
    val valueDampingRatio: Float,
    val valueStiffness: Float,
    val velocityDampingRatio: Float,
    val velocityStiffness: Float,
    val pressDampingRatio: Float,
    val pressStiffness: Float,
    val scaleDampingRatio: Float,
    val scaleStiffness: Float,
    val directDragFollow: Boolean
) {

    /** 值/速度/按压/缩放弹簧（阈值沿用上游的透传方式，见各自调用点）。 */
    fun valueSpec(visibilityThreshold: Float) =
        spring(valueDampingRatio, valueStiffness, visibilityThreshold)

    fun velocitySpec(visibilityThreshold: Float) =
        spring(velocityDampingRatio, velocityStiffness, visibilityThreshold)

    fun pressSpec(visibilityThreshold: Float) =
        spring(pressDampingRatio, pressStiffness, visibilityThreshold)

    fun scaleSpec(visibilityThreshold: Float) =
        spring(scaleDampingRatio, scaleStiffness, visibilityThreshold)

    /** InteractiveHighlight 的触点回中弹簧（上游 positionSpec = spring(0.5, 300, VisibilityThreshold)）。 */
    fun positionSpec(): androidx.compose.animation.core.SpringSpec<Offset> =
        spring(pressDampingRatio, pressStiffness, Offset.VisibilityThreshold)

    companion object {

        /** 上游 catalog 原值（用于 A/B 对照，逐字取自 utils/DampedDragAnimation.kt）。 */
        val Upstream: LiquidHandFeel = LiquidHandFeel(
            label = "上游原值",
            valueDampingRatio = 1.00f,
            valueStiffness = 1000f,
            velocityDampingRatio = 0.50f,
            velocityStiffness = 300f,
            pressDampingRatio = 1.00f,
            pressStiffness = 1000f,
            scaleDampingRatio = 0.60f,   // scaleX 上游 0.6 / scaleY 上游 0.7 → 对照档取 X 值（演示页注明）
            scaleStiffness = 250f,
            directDragFollow = false
        )

        /** 本工程现状基准（默认；出处见文件头注释，每个数字都能在仓库里找到对应常量）。 */
        val Baseline: LiquidHandFeel = LiquidHandFeel(
            label = "我们基准",
            valueDampingRatio = 0.78f,
            valueStiffness = 380f,
            velocityDampingRatio = 0.78f,
            velocityStiffness = 380f,
            pressDampingRatio = 0.42f,
            pressStiffness = 820f,
            scaleDampingRatio = 0.40f,
            scaleStiffness = 950f,
            directDragFollow = true
        )

        /** 当前生效的参数组：由 DebugSwitches.liquidDampingUpstream 一行切换（默认 = 我们基准 ✓）。 */
        fun current(): LiquidHandFeel =
            if (DebugSwitches.liquidDampingUpstream) Upstream else Baseline

        /** 供演示页显示两组的可读摘要。 */
        fun summaryOf(f: LiquidHandFeel): String =
            "值 spring(d=%.2f, k=%.0f)%s · 按压 d=%.2f/%.0f · 缩放 d=%.2f/%.0f"
                .format(
                    f.valueDampingRatio, f.valueStiffness,
                    if (f.directDragFollow) "（拖动 1:1 跟手）" else "（拖动弹簧追手指）",
                    f.pressDampingRatio, f.pressStiffness,
                    f.scaleDampingRatio, f.scaleStiffness
                )
    }
}

/** 速度读数弹簧用的阈值（上游 velocityAnimation = Animatable(0f, 5f) + threshold*10）。 */
internal const val VELOCITY_VISIBILITY_THRESHOLD = 0.01f
