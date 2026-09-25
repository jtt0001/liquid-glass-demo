/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE）
 *   源文件：app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动（适配差异清单 D1/D2）：
 *   D1 包名：com.kyant.backdrop.catalog.utils → com.example.liquidglass.ui.components.liquid
 *   D2 手感参数外置：pressProgressSpec/positionSpec 由 [handFeel] 提供（默认 = 本工程基准，
 *      取值出处见 LiquidHandFeel 头注释：pressed 弹簧 = 收起按钮 blockPressProgress 的 d=0.42/k=820）。
 *      上游原值 spring(0.5, 300) × 2 仍在 LiquidHandFeel.Upstream 里可一行切回。
 *   AGSL 光照 shader（RuntimeShader / asComposeShader / isRuntimeShaderSupported）逐字保留
 *   —— 这三个 API 在本工程 vendored backdrop 模块里同名同签名（已核对 backdrop/src/commonMain/
 *   kotlin/com/kyant/backdrop/RuntimeShader.kt + Platform.kt），因此无需适配 ✓
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
    /** 【P28a 手感标定】按压/回中弹簧参数组（默认 = 本工程基准，非上游原值）。 */
    val handFeel: LiquidHandFeel = LiquidHandFeel.Baseline,
    /**
     * 【P38 取证】非空且 DebugSwitches.liquidHandFeelTrace=true 时，按下期间【每帧】打一行
     * （logcat tag=LGLiq）：press=按压进度 · off=触点偏移 · r=高光半径。
     * 与批 2 两个组件同一套 D3 取证约定（默认 null + 总开关关 ⇒ 不采样、零日志、零开销 ✓）。
     */
    val traceTag: String? = null
) {

    private val pressProgressAnimationSpec =
        handFeel.pressSpec(0.001f)
    private val positionAnimationSpec =
        handFeel.positionSpec()

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader =
        if (isRuntimeShaderSupported()) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            // 【P38 取证】按下期间逐帧一行（默认关：traceTag=null 或总开关关 ⇒ 连字符串都不拼 ✓）
            // 读数与下面真正驱动高光/形变的读数【同源】（同一个 pressProgress / position）✓
            if (traceTag != null && progress > 0f &&
                com.example.liquidglass.debug.DebugSwitches.liquidHandFeelTrace
            ) {
                android.util.Log.i(
                    "LGLiq",
                    "%s frame=draw press=%.3f off=(%+.1f,%+.1f) r=%.1f size=%.0fx%.0f 参数=%s"
                        .format(
                            traceTag, progress, offset.x, offset.y,
                            size.minDimension * 1.5f, size.width, size.height, handFeel.label
                        )
                )
            }
            if (progress > 0f) {
                if (shader != null) {
                    drawRect(
                        Color.White.copy(0.08f * progress),
                        blendMode = BlendMode.Plus
                    )
                    shader.apply {
                        val position = position(size, positionAnimation.value)
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", Color.White.copy(0.15f * progress))
                        setFloatUniform("radius", size.minDimension * 1.5f)
                        setFloatUniform(
                            "position",
                            position.x.fastCoerceIn(0f, size.width),
                            position.y.fastCoerceIn(0f, size.height)
                        )
                    }
                    drawRect(
                        ShaderBrush(shader.asComposeShader()),
                        blendMode = BlendMode.Plus
                    )
                } else {
                    drawRect(
                        Color.White.copy(0.25f * progress),
                        blendMode = BlendMode.Plus
                    )
                }
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                }
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}
