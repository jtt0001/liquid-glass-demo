/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE）
 *   源文件：app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动（适配差异清单 D1/D2/D4）：
 *   D1 包名：com.kyant.backdrop.catalog.components → com.example.liquidglass.ui.components.liquid
 *      （同包 ⇒ 上游的 `import ...catalog.utils.InteractiveHighlight` 一并删除，无需改调用）
 *   D2 新增 handFeel 参数（默认 = 本工程基准）并透传给 InteractiveHighlight —— 弹簧参数可 A/B。
 *   D4 依赖：com.kyant.shapes.Capsule 由 io.github.kyant0:shapes 提供（批 1 已在 app 模块接入
 *      1.2.1，与上游 libs.versions.toml 同版本 ✓，未 vendor 源码 ✓）。
 *   drawBackdrop 的 highlight/shadow/innerShadow/layerBlock/onDrawSurface 重载与参数名与本工程
 *   vendored backdrop（2.0.0 @ bebb11a）逐字一致（已核对 DrawBackdropModifier.kt）⇒ 无适配 ✓
 * 其余逻辑逐字保留。
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    /** 【P28a 手感标定】触点高光/形变弹簧参数组（默认 = 本工程基准，非上游原值）。 */
    handFeel: LiquidHandFeel = LiquidHandFeel.Baseline,
    /** 【P38 取证】非空且 DebugSwitches.liquidHandFeelTrace=true 时按下期间逐帧打点（logcat LGLiq）。 */
    traceTag: String? = null,
    /**
     * 【P54·点按放大安全区】true = 按压放大不得越出按钮自身范围（含拖动分量），
     * 面板里的【真实控件】走这一档：改前按住整行按钮时玻璃会横向长大 ~8%（实测 48dp 高、
     * 1712px 宽的整行按钮 = 单侧 +71px），越过内容区并撞到面板边界被硬裁 ✗；
     * 这一档把放大预算钳到【0 越界】⇒ 溢出像素 = 0、被裁像素 = 0，按钮轮廓/尺寸/位置逐像素不变 ✓
     * （按压反馈仍在：触点高光 + 点击回调本身）。
     * false（默认）= 上游原样（放大到 4dp/高度，允许越出自身范围）—— 演示场用这一档
     * （演示场另有安全边距给放大留出空间，见 LiquidComponentsDemo 里的 liquidPressSafeArea 分支）。
     */
    pressContained: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()

    val interactiveHighlight = remember(animationScope, handFeel) {
        InteractiveHighlight(
            animationScope = animationScope,
            handFeel = handFeel,
            traceTag = traceTag
        )
    }

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        // 【P54·点按放大安全区】pressContained=true ⇒ 放大预算 = 0（单侧不得越出按钮自身范围）；
                        //   默认（上游档）预算 = 4dp（按高度归一化，宽按钮的横向放大因此是同比例的 = 会外溢）。
                        // 【修 R9·放大预算钳到距屏幕边剩余空间（统一口径见 glass/EdgeClamp.kt）】
                        //   上游 4dp 预算只看按钮自身 ⇒ 贴屏幕边（下/右）按下时可见外扩越过物理边被
                        //   硬裁 ✗。修法：growBudgetPx = min(原预算, 按钮四边剩余空间 − SAFE_GAP_PX)
                        //   的直径向均匀版本；离边远时 clamp 不触发 ⇒ 逐位 = 改前（对照臂）✓。
                        //   一行回退：DebugSwitches.edgeClampOn=false。
                        val rawGrowBudget = if (pressContained) 0f else 4f.dp.toPx()
                        val growBudgetPx =
                            if (!pressContained &&
                                com.example.liquidglass.debug.DebugSwitches.edgeClampOn
                            ) {
                                // 按钮窗口位置：drawBackdrop 内可拿 size（本节点大小）；窗口原点由
                                // 全局快照（EdgeClamp.storedInsetSnapshot）提供屏幕口径。演示场按钮
                                // 都在【面板/容器内、距屏幕边 ≥ 一屏 20%】 ⇒ 统一近似为"按钮中心
                                // 与屏幕中心重合"的 conservative gap = 屏幕 gap − halfSize/2：
                                // 屏 gap ≥ 原预算 4dp ⇒ 不钳（多数按钮贴边远 ⇒ 零回归 ✓）。
                                val snap = com.example.liquidglass.glass.EdgeClamp.edgeGapPx(
                                    com.example.liquidglass.glass.EdgeClamp.storedInsetSnapshot,
                                    size.width.toFloat() / 2f, size.height.toFloat() / 2f,
                                    size.width.toFloat(), size.height.toFloat()
                                )
                                val conservativeW = (minOf(snap[0] - size.width / 2, snap[2] - size.width / 2)).toInt()
                                val conservativeH = (minOf(snap[1] - size.height / 2, snap[3] - size.height / 2)).toInt()
                                minOf(
                                    rawGrowBudget,
                                    com.example.liquidglass.glass.EdgeClamp.clampSymmetricGrowth(
                                        rawGrowBudget, conservativeW, conservativeW
                                    ),
                                    com.example.liquidglass.glass.EdgeClamp.clampSymmetricGrowth(
                                        rawGrowBudget, conservativeH, conservativeH
                                    )
                                )
                            } else rawGrowBudget
                        val scale = lerp(1f, 1f + growBudgetPx / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = growBudgetPx / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX =
                            scale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                    (width / height).fastCoerceAtMost(1f)
                        scaleY =
                            scale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                    (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .clickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(48f.dp)
            .padding(horizontal = 16f.dp),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
