/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE / LICENSES/Backdrop-APACHE-2.0.txt）
 *   源文件：app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt（288 行）
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动（适配差异清单 D1/D2/D3/D4/D5）：
 *   D1 包名：com.kyant.backdrop.catalog.components → com.example.liquidglass.ui.components.liquid
 *      （同包 ⇒ 上游的 `import ...catalog.utils.DampedDragAnimation` / `InteractiveHighlight` 删除）
 *   D2 新增 handFeel 参数（默认 = LiquidHandFeel.current() = 本工程基准），同时透传给
 *      DampedDragAnimation（拖动/按压/缩放弹簧）与 InteractiveHighlight（触点高光回中弹簧）
 *      ⇒ 与批 1 的 Button/Toggle 同一套手感标定；基准档下拖动是 1:1 跟手（snapTo），
 *      松手后的"吸附到最近标签"仍走弹簧（animateToValue）——**非上游原值**。
 *   D3 新增 traceTag（默认 null）：透传给 DampedDragAnimation（logcat tag=LGLiq）；
 *      traceDragWidthPx = tabWidth（= 每个标签索引对应的 px ⇒ 滞后列与 Slider/Toggle 同量纲）。
 *   D4 色散 A/B：旋钮 lens 的 chromaticAberration 由 DebugSwitches.liquidSliderNoDispersion 一行切换
 *      （默认 false = 上游原值 = 带色散）。
 *   D5 拖动屏蔽 Y 轴（P50·真机反馈「拖底栏旋钮时控制中心跟着变小」）：旋钮拖动节点最外层加
 *      `Modifier.liquidDragYShield()`（DragYShield.kt）——拖动期间把位移标记为已消费，
 *      阻断垂向分量上抛到祖先（面板容器/滚动链路）。位置排在本节点的
 *      interactiveHighlight.gestureModifier 与 dampedDragAnimation.modifier【之前】⇒ 两者的
 *      事件序与行为一字未动，本节点只在 Main 传递最后跑、只影响祖先 ✓
 *      一行回退：DebugSwitches.liquidDragYShield=false（默认开）。
 * 其余逐字保留：容器 64dp + 指示层 56dp 双层玻璃（alpha(0) 那层专门录进 tabsBackdrop 供旋钮采样）、
 *   vibrancy()+blur(8dp)+lens(24dp) 容器效果、旋钮 lens(10dp,14dp,色散)+Highlight+Shadow+InnerShadow、
 *   layerBlock 的 velocity/10 拉伸 ±0.2、panelOffset 的 4dp 橡皮筋（EaseOut·±1 限幅）、
 *   tabsCount 与 tabWidth=(maxWidth-8dp)/count 的几何、ContentLocal 的 1→1.2 按压缩放、
 *   onDragStopped 里的 spring(1f, 300f, 0.5f) 回中（该条属面板偏移回弹，非拖动阻尼主链 ⇒ 保持上游原值 ✗ 不改）。
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.example.liquidglass.debug.DebugSwitches
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    /** 【P28a 手感标定】弹簧参数组（默认 = 本工程基准；见 LiquidHandFeel）。 */
    handFeel: LiquidHandFeel = LiquidHandFeel.current(),
    /** 【P28a 取证】非空时逐帧打点（logcat tag=LGLiq，总开关 DebugSwitches.liquidHandFeelTrace）。 */
    traceTag: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        val dampedDragAnimation = remember(animationScope, handFeel) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
                handFeel = handFeel,
                traceTag = traceTag,
                traceDragWidthPx = tabWidth
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope, handFeel) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                },
                handFeel = handFeel
            )
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                // 【P50·拖动屏蔽 Y 轴·D5】最外层：必须排在下面两个既有指针节点【之前】
                //   ⇒ Main 传递里本节点最后执行 —— 旋钮拖动（dampedDragAnimation.modifier）与
                //   触点高光跟随（interactiveHighlight.gestureModifier）拿到的事件与行为一字未改，
                //   被消费的位移只对【祖先】（面板容器 / 滚动链路）生效 ⇒ 拖旋钮时面板 p 不再跟着变小 ✓
                //   只消费移动事件（按下/抬起放行）⇒ 底栏点按切页语义不变 ✓
                //   一行回退：DebugSwitches.liquidDragYShield=false（默认开，见 DragYShield.kt 文件头）
                .liquidDragYShield()
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = !DebugSwitches.liquidSliderNoDispersion
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
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
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
