package com.example.liquidglass.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.liquidglass.backdrop.glassPanel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.debug.DebugSwitches
import com.example.liquidglass.hdr.HdrImageSupport
import com.example.liquidglass.backdrop.BackdropAdapter
import com.example.liquidglass.ui.components.liquid.LiquidButton
import com.example.liquidglass.ui.components.liquid.LiquidSlider
import com.example.liquidglass.ui.components.liquid.LiquidToggle
import com.kyant.backdrop.Backdrop
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.liquidglass.R
import com.example.liquidglass.glass.GlassDebugMode
import com.example.liquidglass.glass.GlassParameters
import com.example.liquidglass.glass.GlassPreset
import com.example.liquidglass.glass.GlassQuality
import com.example.liquidglass.glass.GlassShape
import java.util.Locale

/**
 * 页面 UI 状态（不可变参数 + 开关）。
 *
 * 滑块写入通过 [updateParameter] 统一进入 CUSTOM 模式；
 * 宏观透明度滑块在预设模式下通过 [setTransparency] 重派生整组光学参数。
 */
class GlassUiState {

    /**
     * 当前参数（preset 字段即预设身份来源）。
     * 【玻璃风格拉杆】开关开着（默认）时冷启动 = 拉杆中位 = 均衡档；
     * 关着时逐字回到改动前 = 通透档（= 旧 chip 交互的默认）✓
     */
    var parameters by mutableStateOf(
        if (DebugSwitches.glassStyleSlider) GlassParameters.preset(GlassPreset.BALANCED)
        else GlassParameters.preset(GlassPreset.IOS26_BETA1_ULTRA_CLEAR)
    )
        private set

    /**
     * 用户选择画质（[quality] 的 getter/setter 背后的真值）。
     * 【P57】画质行/入口已按用户要求移除、生效档【固定为最清晰档】⇒ 本字段只在
     * 回退档（DebugSwitches.qualityFixedBest=false）下才是消费源。
     */
    private var qualityChoice by mutableStateOf(GlassQuality.BALANCED)

    /**
     * 生效画质档（消费点读它：LiquidGlassScreen 五处 `quality = { uiState.quality }` → BackdropAdapter）。
     *
     * 【P57·固定最清晰档 + 一行回退】
     *   · DebugSwitches.qualityFixedBest（默认开）= 恒为 [GlassQuality.QUALITY]（「高清」= 13 taps
     *     = 最清晰档）：面板上的画质 chips 行已整节移除 ⇒ 没有第二处可改它的入口（不是假下拉，
     *     是真的没有选择面）；调试桥 `setUi quality …` 写进来也【读回恒为 QUALITY】（固定语义）✓
     *   · 开关关掉 = 逐字回到改动前：读用户选择值（默认 BALANCED「标准」、chips 可点选）✓
     */
    var quality: GlassQuality
        get() = if (DebugSwitches.qualityFixedBest) GlassQuality.QUALITY else qualityChoice
        set(value) { qualityChoice = value }

    /** Shader 调试模式。 */
    var debugMode by mutableStateOf(GlassDebugMode.FINAL)

    /**
     * 玻璃形状多选（最多 2 个）：
     * - 第 1 个 = 主卡形状（[glassShape]）
     * - 双卡模式开着时最多两个；第 2 个 = 第二张玻璃（[secondShape]）
     */
    val selectedShapes = androidx.compose.runtime.mutableStateListOf(GlassShape.ROUNDED_RECT)
    val glassShape: GlassShape get() = selectedShapes.first()
    val secondShape: GlassShape? get() = selectedShapes.getOrNull(1)

    /**
     * 双卡模式开关：
     * - 关闭：只能一块玻璃，点形状即"自动切换"（单选，替换当前形状）
     * - 打开：可同时选中两块玻璃（最多两个），多出来的弹"已达玻璃上限"
     */
    var twoCardDemo by mutableStateOf(false)

    // ===== 【P06 多卡演示】卡片清单（≥3 块同屏）=====
    // 为什么是"追加"而不是重写：existing 的 selectedShapes[0]/[1] 分别被 [glassShape]/[secondShape]
    // 暴露给 P07（双卡二次折射）与 P12（邻近流体融合）等机制使用 ⇒ 这里保持它们的语义一字不动，
    // 只为索引 2、3 追加独立的形状槽（改前行为 = 这两块根本不存在 ⇒ 零回归）。
    /** 同屏玻璃块数（2..4，默认 2）。仅在 DebugSwitches.multiCardDemo 打开时生效（关掉即回到 ≤2）。 */
    var cardCount by mutableStateOf(2)

    /** 第 3、4 块玻璃的形状（索引 2、3 用；默认取两个辨识度高的形状，与默认 shape2=CIRCLE 不重复）。 */
    val extraShapes = androidx.compose.runtime.mutableStateListOf(GlassShape.HEXAGON, GlassShape.TRIANGLE)

    /** 第 i 块卡的形状（0=主卡 1=第二块 ≥2=追加块）。 */
    fun shapeOfCard(i: Int): GlassShape = when (i) {
        0 -> glassShape
        1 -> secondShape ?: glassShape
        else -> extraShapes.getOrNull(i - 2) ?: glassShape
    }

    /**
     * 设置同屏块数（面板 chip / 调试桥 `setUi cards <2|3|4>` 共用）。
     * 顺带把"追加块"要用到的形状槽补够（与 cards=2 时自动补 secondShape 同一套语义）。
     * ⚠️ 名字不能叫 setCardCount：`var cardCount` 的属性 setter 已经占了 (I)V 这个 JVM 签名。
     */
    fun applyCardCount(n: Int) {
        cardCount = n.coerceIn(2, 4)
        if (cardCount >= 2 && selectedShapes.size < 2) {
            val fallback = GlassShape.CIRCLE
            if (selectedShapes.firstOrNull() != fallback) selectedShapes.add(fallback)
        }
        // 【选中卡】块数变少时把选中卡钳回范围（否则面板会指向一块不存在的玻璃 ✗）
        if (selectedCardIndex > cardCount - 1) selectedCardIndex = cardCount - 1
    }

    // ===== 【选中卡 · 2026-09-14 用户需求①】「形状与布局」作用于【手指/当前选中的那块玻璃】=====
    // 用户原话：「当前手指放在哪块玻璃上面，就更改哪块玻璃的尺寸」；缺陷原话：「在『形状与布局』中更改
    //  玻璃尺寸时，只能更改最先选中那块玻璃，第二块玻璃无法更改 ✗」（真因：第二/三/四块的宽高比例是
    //  写死在布局里的常量（0.55 / 0.42 / 0.34），滑块只写 glassSize = 主卡的尺寸 ⇒ 对它无效 ✗）。
    // 实现：新增【当前选中卡】下标 + 每块卡的尺寸表；面板「形状与布局」的尺寸（以及追加块 ≥3 的形状）
    //  一律写到【选中卡】。默认 0 = 主卡 ⇒ 默认档下面板行为与改动前逐字一致（零回归 ✓）。
    /** 当前选中的卡（0=主卡 1=第二块 2/3=多卡追加块）。 */
    var selectedCardIndex by mutableIntStateOf(0)
        private set

    /** 点按玻璃 / 面板 chips / 调试桥：把选中卡切到 i（越界/不存在的块一律钳到仍渲染的范围 ✓）。 */
    fun selectCard(i: Int) {
        if (i < 0) return
        val v = i.coerceIn(0, maxOf(0, visibleCardCount() - 1))
        if (v != selectedCardIndex) selectedCardIndex = v
    }

    /**
     * 第 1/2/3 块（索引 1/2/3）的尺寸（占屏宽比例）。
     * 默认值 = 改动前【写死在布局里的三个比例】（第二块 0.55 / 第三块 0.42 / 第四块 0.34）
     * ⇒ 默认档下 [sizeOfCard] 与改动前的常量逐位相同 ⇒ 双卡/多卡零回归 ✓。
     * 主卡（索引 0）不进本表：它继续用 [glassSize]（P07 二次折射 / P12 融合 / 尺寸中心基点补偿
     * 等机制都读 glassSize，语义一字不动 ✓）。
     */
    val cardSizes = androidx.compose.runtime.mutableStateListOf(0.55f, 0.42f, 0.34f)

    /** 第 i 块卡的尺寸（占屏宽比例）：i ≤ 0 = 主卡 = [glassSize]（唯一真值不变）。 */
    fun sizeOfCard(i: Int): Float = if (i <= 0) glassSize else cardSizes.getOrElse(i - 1) { glassSize }

    /**
     * 写第 i 块卡的尺寸（面板「玻璃尺寸」滑块 / 调试桥共用）。
     * 钳制范围：主卡 0.2..0.5（= 改动前滑块范围，逐字不变 ✓）；其它块 0.2..max(0.5, 本块当前值)
     * —— 上界至少要覆盖本块的默认值（第二块默认 0.55 > 0.5），否则滑块手柄一上来就被顶到最右 ✗。
     */
    fun setSizeOfCard(i: Int, v: Float) {
        if (i <= 0) {
            glassSize = v.coerceIn(0.2f, 0.5f)
        } else {
            val idx = (i - 1).coerceIn(0, cardSizes.size - 1)
            cardSizes[idx] = v.coerceIn(0.2f, maxOf(0.5f, cardSizes[idx]))
        }
    }

    /** 写【当前选中卡】的尺寸（= 面板「玻璃尺寸」滑块的唯一行为）。 */
    fun setSizeOfSelectedCard(v: Float) = setSizeOfCard(selectedCardIndex, v)

    /** 尺寸滑块的上界（唯一口径，面板与桥共用；默认档 = 改动前的 0.2..0.5 ✓）。 */
    fun sizeRangeOfCard(i: Int): ClosedFloatingPointRange<Float> = 0.2f..maxOf(0.5f, sizeOfCard(i))

    /** 面板文案用：第 i 块卡的显示名（"玻璃 1" = 主卡）。 */
    fun cardDisplayName(i: Int): String = "玻璃 ${i + 1}"

    /**
     * 追加块（索引 ≥2）的形状：这些块【不参与】selectedShapes 的"最多两个"多选语义
     * （那两个槽位是 P07/P12 的既有输入 ⇒ 语义一字不动），单独写 [extraShapes] ✓。
     */
    fun setShapeOfExtraCard(i: Int, shape: GlassShape) {
        val idx = i - 2
        if (idx in extraShapes.indices) extraShapes[idx] = shape
    }

    /**
     * 【需求④·合并后的唯一多卡开关】状态 = 内部 [twoCardDemo]（口径一字不动 —— 它就是 P07 二次折射 /
     * P12 融合 / 第二块形状的既有驱动）＋ 同步写 @Volatile 的 [DebugSwitches.multiCardDemo]
     * （多卡渲染与点击置顶的总闸）。
     * 默认 = false（= 与改动前观测一致：单卡）；默认档 = 2 块（[cardCount] 默认 2 = 改动前"双卡演示"
     * 打开时的表现）✓。一行回退：面板里把这一行换回旧的「双卡演示」两行即可（内部字段没删、语义没变）。
     */
    var multiCardDemoOn: Boolean
        get() = twoCardDemo
        set(v) {
            twoCardDemo = v
            DebugSwitches.multiCardDemo = v
            // 回到单卡/双卡：选中卡必须落回仍存在的块（否则面板会指向不存在的玻璃 ✗）
            val alive = if (v) cardCount else 1
            if (selectedCardIndex > alive - 1) selectedCardIndex = alive - 1
        }

    /** 当前【实际渲染】的块数（与 LiquidGlassScreen 的卡片区同口径：未开开关=1，关多卡总闸=2）。 */
    fun visibleCardCount(): Int =
        if (!twoCardDemo) 1 else if (!DebugSwitches.multiCardDemo) 2 else cardCount.coerceIn(2, 4)

    /** 选择形状；超上限返回 false（调用方弹提示）。 */
    fun toggleShape(shape: GlassShape): Boolean {
        if (!twoCardDemo) {                     // 单选：自动切换
            if (selectedShapes.size == 1 && selectedShapes.first() == shape) return true
            selectedShapes.clear()
            selectedShapes.add(shape)
            return true
        }
        if (selectedShapes.contains(shape)) {    // 双选：再点取消
            if (selectedShapes.size > 1) selectedShapes.remove(shape)
            return true
        }
        if (selectedShapes.size >= 2) return false
        selectedShapes.add(shape)
        return true
    }

    /**
     * iOS 透镜剖面（移植自 QWEA0/Liquid-Glass-Android）：true = 逆幂衰减折射剖面（接近 iOS 观感）；
     * false = 原 circleMap 剖面（Golden Reference 对照）。
     *
     * 【已按用户要求固定为开 · 面板入口已移除】用户原话：「还有 iOS 透镜折射剖面关掉以后效果好诡异，
     * 直接把这个开关删掉吧它默认打开就可以了」⇒ 一级页面板里的那行开关已删除（运行时无从关闭）；
     * 消费点已写死常量（LiquidGlassScreen：`lensProfile = { true }`）⇒ 本字段【不再是消费源】。
     * 字段保留只为后续 A/B 与回归留档：想恢复“可关”必须【改代码重新构建】；
     * 调试用 `setUi iosLensProfile 0` 仍会写本字段（DebugBridge 回显里明确标注），但观感零变化。
     */
    var iosLensProfile by mutableStateOf(true)

    /** 玻璃尺寸（占屏幕宽度比例，0.2~0.5）。 */
    var glassSize by mutableFloatStateOf(0.35f)   // 旋转默认 0°：图层旋转会连带旋转 Backdrop 采样（背景跟着转 ✗），
                                                            // 待改为"着色器内转几何、不转图层"后再放开默认值

    // ---- 背景内容自定义 ----
    /** 背景测试文字内容。 */
    /** 自定义背景文字内容（默认空 = 不显示，背景保持纯净）。 */
    var bgText by mutableStateOf("")
    /** 背景文字字号（sp）。 */
    var bgTextSizeSp by mutableFloatStateOf(58f)
    /** 背景文字透明度（0~1）。 */
    var bgTextAlpha by mutableFloatStateOf(0.85f)
    /** 背景文字纵向位置（0~1，视口比例）。 */
    var bgTextPositionY by mutableFloatStateOf(0.62f)
    /** 背景文字旋转角度（度，-180~180）。 */
    var bgTextRotationDegrees by mutableFloatStateOf(0f)
    /** 自定义背景图片（解码后，null=使用默认矢量风景）。 */
    var bgImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /**
     * 【图片编辑】本次会话里经 SAF 选中的【原图】content:// URI。
     *
     * 用途：官方 `ACTION_EDIT` 优先把原图交给系统编辑器 —— 本项目不改 Manifest（无 FileProvider），
     * 私有目录副本是 file://、外发会被 FileUriExposedException 拒 ✗；SAF 返回的 content:// 带读授权，
     * 可以直接转授给编辑器 ✓。进程重启后为空 ⇒ 自动退回「写入相册的副本」路径 ✓。
     * 与 [bgBuiltinIndex] 同步维护：切内置壁纸 / 恢复默认背景时清空（否则会拿旧原图去编辑 ✗）
     */
    var bgImageSourceUri by mutableStateOf<android.net.Uri?>(null)

    /**
     * 内置山水壁纸选择（0..n-1 = 内置实拍照片，默认第一张）。
     * 与用户自选图片（[bgImage]）互斥：选内置会清掉自选副本，反之亦然。
     */
    var bgBuiltinIndex by mutableIntStateOf(5)   // 调试默认壁纸=彩色网格（用户要求 ✓）
    /** 背景图片缩放（Cover 基础上放大 = 变焦）。 */
    var bgImageZoom by mutableFloatStateOf(1.0f)
    /** 背景图片水平偏移（-0.5~0.5，视口宽度比例）。 */
    var bgImageOffsetX by mutableFloatStateOf(0f)
    /** 背景图片垂直偏移（-0.5~0.5，视口高度比例）。 */
    var bgImageOffsetY by mutableFloatStateOf(0f)

    // ---- 开关 ----
    var reduceMotion by mutableStateOf(false)

    /** 记录调试日志（用户提议：动画轨迹 + 手势 + 状态变化），遇到问题一键导出 ✓ */
    var debugLogEnabled by mutableStateOf(true)   // 默认开：出问题时直接导出，无需你记得去开 ✓
    var enhancedLegibility by mutableStateOf(false)
    var adaptiveLegibility by mutableStateOf(false)
    var stretchOnDrag by mutableStateOf(true)

    private var presetBeforeAccessibility: GlassPreset = GlassPreset.IOS26_BETA1_ULTRA_CLEAR

    val preset: GlassPreset get() = parameters.preset

    /** 选择预设（CUSTOM 保留当前参数，仅切换身份）。 */
    fun selectPreset(preset: GlassPreset) {
        if (enhancedLegibility) {
            // 手动选择预设时退出“增强可读性”强制模式
            enhancedLegibility = false
        }
        parameters = if (preset == GlassPreset.CUSTOM) {
            parameters.toCustom()
        } else {
            GlassParameters.preset(preset)
        }
    }

    /** 宏观透明度：只在预设模式下生效，按映射规则重派生光学参数。 */
    fun setTransparency(t: Float) {
        if (preset == GlassPreset.CUSTOM) return
        parameters = parameters.withTransparency(t)
    }

    /**
     * 【玻璃风格拉杆】按拉杆位置 s(0..1) 重派生整组参数（三档锚点与现行三预设逐字段等价）。
     * 语义与 [selectPreset] 对齐：增强可读性开着时先关掉它；
     * preset==CUSTOM 时不生效（与「玻璃透明度」滑杆的 enabled 语义一致）。
     */
    fun setGlassStyle(s: Float) {
        if (preset == GlassPreset.CUSTOM) return
        if (enhancedLegibility) {
            // 手动拖动风格拉杆时退出“增强可读性”强制模式
            enhancedLegibility = false
        }
        parameters = parameters.withStylePosition(s)
    }

    /** 独立参数修改：任何非宏观参数被调节即进入 CUSTOM 模式。 */
    fun updateParameter(transform: (GlassParameters) -> GlassParameters) {
        parameters = transform(parameters).toCustom()
    }

    /** 恢复默认背景：清掉自选图片，回到第一张内置山水壁纸。 */
    fun resetBackgroundImage(context: android.content.Context) {
        bgImage = null
        bgImageSourceUri = null   // 【图片编辑】原图 URI 与自选图同寿命：复位即作废（否则会拿旧原图去编辑 ✗）
        bgBuiltinIndex = 0
        bgImageZoom = 1f
        bgImageOffsetX = 0f
        bgImageOffsetY = 0f
        BackgroundImageStore.clear(context)
        BackgroundImageStore.saveBuiltinIndex(context, 0)
    }

    /**
     * 选中“自定义图片”背景（与内置壁纸互斥）。
     *
     * bgBuiltinIndex = -1 是“自定义图片”chip 选中态的唯一来源：
     * 之前全项目无人写 -1 → chip 永远不亮（设置类缺陷④）。本函数同时持久化，
     * 重启后 restoreBackgroundSelection 的自定义图成功分支会再次调用它。
     */
    fun selectCustomBackground(context: android.content.Context) {
        bgBuiltinIndex = -1
        BackgroundImageStore.saveBuiltinIndex(context, -1)
    }

    /** 选择内置山水壁纸（index = -1 表示不选照片，仅用于兜底）。 */
    fun selectBuiltinBackground(context: android.content.Context, index: Int) {
        bgBuiltinIndex = index
        BackgroundImageStore.saveBuiltinIndex(context, index)
        // 内置壁纸与自选图片互斥：切到内置时清掉自选副本与调整参数
        BackgroundImageStore.clear(context)
        bgImageSourceUri = null   // 【图片编辑】切内置壁纸 ⇒ 自选原图 URI 一并作废（编辑对象必须与画面一致 ✓）
        bgImageZoom = 1f
        bgImageOffsetX = 0f
        bgImageOffsetY = 0f
        if (index < 0) bgImage = null
    }

    /** 增强可读性：开启切换到 FROSTED_ACCESSIBLE，关闭恢复此前预设。 */
    fun applyEnhancedLegibility(on: Boolean) {
        enhancedLegibility = on
        if (on) {
            presetBeforeAccessibility = preset
            parameters = GlassParameters.preset(GlassPreset.FROSTED_ACCESSIBLE)
        } else {
            val restore = presetBeforeAccessibility
            parameters = if (restore == GlassPreset.CUSTOM || restore == GlassPreset.FROSTED_ACCESSIBLE) {
                GlassParameters.preset(GlassPreset.BALANCED)
            } else {
                GlassParameters.preset(restore)
            }
        }
    }

    /**
     * 恢复默认设置（用户要求）：全部可调项回初始值。
     *
     * [context] 用于一并复位背景持久化（清自选图片副本 + 内置壁纸索引写回第一张）：
     * 不复位背景时，点复位画面不变、重开面板/重启又会被 restore 写回旧背景 ✗（设置类缺陷③）。
     */
    fun resetToDefaults(context: android.content.Context) {
        // 【玻璃风格拉杆】重置 = 拉杆回到中位（均衡档）；开关关着时逐字回到改动前（通透档）✓
        parameters = if (DebugSwitches.glassStyleSlider) GlassParameters.preset(GlassPreset.BALANCED)
        else GlassParameters.preset(GlassPreset.IOS26_BETA1_ULTRA_CLEAR)
        quality = GlassQuality.BALANCED
        debugMode = GlassDebugMode.FINAL
        twoCardDemo = false
        iosLensProfile = true   // 【固定开】面板入口已按用户要求删除（见该字段注释）⇒ 复位即回到固定开状态
        glassSize = 0.35f
        bgText = ""
        bgTextSizeSp = 58f
        bgTextAlpha = 0.85f
        bgTextPositionY = 0.62f
        bgTextRotationDegrees = 0f
        // 背景一并复位：清空自选图片（bgImage + 私有副本 + 缩放/偏移）
        // 并把内置壁纸索引写回第一张（0，含 BackgroundImageStore 持久化）✓
        resetBackgroundImage(context)
        reduceMotion = false
        enhancedLegibility = false
        adaptiveLegibility = false
        stretchOnDrag = true
    }
}

/**
 * 第四层：参数面板 —— 预设 / 画质 / 开关 / 全部滑块。
 * 所有滑块显示当前数值；滑块带 contentDescription。
 */
/**
 * 控制中心里的滑块要显示【真正的 shader 玻璃】手柄（此前是 Canvas 手画的伪玻璃）。
 * 用 CompositionLocal 把渲染器和参数传下去，避免改动十余个 ParameterSlider 调用点。
 */
/** 面板自身的捕获层：面板内玻璃（滑杆手柄）采样它才能折射到面板内容，而不是只采到壁纸。 */
internal val LocalPanelBackdrop =
    androidx.compose.runtime.staticCompositionLocalOf<com.kyant.backdrop.backdrops.LayerBackdrop?> { null }

/** 面板内控件统一 G2 语言：与面板同一构造（角部向外饱满、曲率连续）。
 *  半径按控件高度取值（chip 高约 32dp → 9dp），不机械照搬面板的 28dp。 */
internal val PanelChipShape = G2RoundedShape(9.dp, 9.dp, 9.dp, 9.dp)

private val LocalGlassAdapter = androidx.compose.runtime.staticCompositionLocalOf<BackdropAdapter?> { null }
private val LocalGlassParameters = androidx.compose.runtime.staticCompositionLocalOf<() -> GlassParameters> {
    { error("LocalGlassParameters 未提供：请从 GlassControlsPanel 内部使用滑块") }
}
private val LocalGlassQuality = androidx.compose.runtime.staticCompositionLocalOf<() -> GlassQuality> {
    { GlassQuality.BALANCED }
}

@Composable
fun GlassControlsPanel(
    uiState: GlassUiState,
    /** 玻璃渲染器：供面板内滑块显示真实玻璃手柄。 */
    adapter: BackdropAdapter? = null,
    /** false = 一级基础菜单；true = 二级"更多设置"。 */
    advanced: Boolean = false,
    /**
     * 面板是否【已完全收起并静止】。
     * 用途：常驻组合（DebugSwitches.precomposeListKeepAlive）下，列表不再随"收起到底"被拆出组合，
     * 于是"每次打开都从列表顶部开始"这一原有行为需要显式复位（见下方 listScroll 的 LaunchedEffect）。
     * 关闭常驻组合时为 false，等同于旧行为（无副作用）。
     */
    atRest: Boolean = false,
    /** 一级菜单点击"更多设置"时进入二级菜单。 */
    onOpenAdvanced: () -> Unit = {},
    /** true = 二级菜单「图片编辑」（与「更多设置」同级：同一个面板、只换页）。 */
    imageEdit: Boolean = false,
    /** 一级菜单点击"图片编辑"时进入该二级菜单（与 [onOpenAdvanced] 同构）。 */
    onOpenImageEdit: () -> Unit = {},
    /** true = 二级菜单「组件演示」（与「图片编辑」「更多设置」同级：同一个面板、只换页）。 */
    componentDemo: Boolean = false,
    /** 一级菜单点击"组件演示"时进入该二级菜单（与 [onOpenImageEdit] 同构）。 */
    onOpenComponentDemo: () -> Unit = {},
    /** 【P29】true = 二级菜单「开源许可与致谢」（与「更多设置」同级：同一个面板、只换页）。 */
    licenses: Boolean = false,
    /** 【P29】「更多设置」底部「关于」节点击「开源许可与致谢 ›」时进入该二级页。 */
    onOpenLicenses: () -> Unit = {},
    /**
     * 【P46·两页拆分】二级「更多设置」当前标签页：0 = 外观设置（默认），1 = 高级设置。
     * 状态由屏幕层持有（底部玻璃底栏画在面板容器底边，与页内容共享同一份状态）。
     */
    advancedTab: Int = 0,
    /** 【P46·两页拆分】底部玻璃标签栏切页回调（LiquidBottomTabs.onTabSelected → 写回状态）。 */
    onAdvancedTabSelected: (Int) -> Unit = {},
    /**
     * 【P43】列表内容末尾的底部余量提供者（px，layout 阶段被调用；内部读 p ⇒ 组合期零订阅）。
     * 语义与推导见 DebugSwitches.listScrollPad 与 LiquidGlassScreen 的 listBottomPadPxState。
     * null / 关闭开关 ⇒ 余量恒 0 = 改动前行为（逐字回退 ✓）。
     */
    listBottomPadPx: androidx.compose.runtime.State<() -> Int>? = null,
    /** 一级菜单点击【钻石】入口时进入钻石演示页（动作抛给屏幕层）。 */
    onOpenDiamond: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassAdapter provides adapter,
        LocalGlassParameters provides { uiState.parameters },
        LocalGlassQuality provides { uiState.quality }
    ) {
        GlassPanelContent(
            // 【合并口径】全具名实参：主线侧 11 参 + 钻石侧 onOpenDiamond 全保留
            uiState = uiState,
            advanced = advanced,
            onOpenAdvanced = onOpenAdvanced,
            onOpenDiamond = onOpenDiamond,
            imageEdit = imageEdit,
            onOpenImageEdit = onOpenImageEdit,
            modifier = modifier,
            atRest = atRest,
            componentDemo = componentDemo,
            onOpenComponentDemo = onOpenComponentDemo,
            licenses = licenses,
            onOpenLicenses = onOpenLicenses,
            advancedTab = advancedTab,
            listBottomPadPx = listBottomPadPx
        )
    }
}

@Composable
private fun GlassPanelContent(
    uiState: GlassUiState,
    advanced: Boolean,
    onOpenAdvanced: () -> Unit,
    /** 二级菜单「图片编辑」（入口只在一级页 ⇒ 与 [advanced] 不会同时为 true）。 */
    imageEdit: Boolean,
    onOpenImageEdit: () -> Unit,
    onOpenDiamond: () -> Unit,
    modifier: Modifier,
    atRest: Boolean,
    /** 【P28a·批 1】二级菜单「组件演示」（与「图片编辑」同级、互斥）。 */
    componentDemo: Boolean = false,
    onOpenComponentDemo: () -> Unit = {},
    /** 【P29】二级菜单「开源许可与致谢」（与「更多设置」同级、互斥；由开关 licensesPage 总门控）。 */
    licenses: Boolean = false,
    onOpenLicenses: () -> Unit = {},
    /** 【P46·两页拆分】二级「更多设置」当前标签页（0 = 外观设置 / 1 = 高级设置；底栏在屏幕层）。 */
    advancedTab: Int = 0,
    /** 【P43】列表内容末尾的底部余量提供者（px）；null/关开关 ⇒ 恒 0 = 改动前行为。 */
    listBottomPadPx: androidx.compose.runtime.State<() -> Int>? = null
) {
    // 背景图片选择器：SAF GetContent → 拷贝到应用私有目录（重启可恢复）→ IO 线程解码
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 先持久化字节流（不依赖 SAF 授权，重启后必定可读）
                val copied = withContext(Dispatchers.IO) {
                    BackgroundImageStore.copyFromUri(context, uri)
                }
                if (copied) {
                    // 【图片编辑】记住这次 SAF 选中的原图 content:// URI（副本写成功才记 ⇒ 状态一致）：
                    // 官方 ACTION_EDIT 优先把【原图】交给系统编辑器（私有副本是 file://，不改 Manifest 发不出去 ✗）
                    uiState.bgImageSourceUri = uri
                    val bitmap = withContext(Dispatchers.IO) {
                        BackgroundImageStore.decodeStored(context, maxDim = 2048)
                    }
                    if (bitmap != null) {
                        uiState.bgImage = bitmap.asImageBitmap()
                        uiState.bgImageZoom = 1f
                        uiState.bgImageOffsetX = 0f
                        uiState.bgImageOffsetY = 0f
                        BackgroundImageStore.saveAdjustments(context, 1f, 0f, 0f)
                        // 选中“自定义图片”chip 的状态写入（bgBuiltinIndex = -1 并持久化）✓
                        uiState.selectCustomBackground(context)
                    }
                }
            }
        }
    }

    // ---- 内置山水壁纸：切换时在 IO 线程解码 drawable ----
    val applyBuiltinWallpaper: (Int, Int) -> Unit = { index, resId ->
        scope.launch {
            uiState.selectBuiltinBackground(context, index)
            if (resId != 0) {
                val bmp = withContext(Dispatchers.IO) {
                    // 【HDR 图片支持】内置壁纸也走同一个解码入口：带 gain map 且屏幕支持 HDR ⇒ HDR 输出，
                    // 其余情况 = 原来的 BitmapFactory.decodeResource（逐像素一致），见 hdr/HdrImageSupport.kt
                    HdrImageSupport.decodeWallpaperResource(context, resId)
                }
                if (bmp != null) uiState.bgImage = bmp.asImageBitmap()
            }
        }
    }

    // 面板首次打开时的兜底恢复（正常路径由屏幕层在启动时调用，见 LiquidGlassScreen）
    LaunchedEffect(Unit) {
        restoreBackgroundSelection(uiState, context)
    }

    // 面板配色由 GlassTheme 三档调色板驱动（亮/暗两档，默认跟随系统 ✓）。
    // 容器本身必须透明——液态玻璃由外层 drawBackdrop 绘制，用不透明 Surface
    // 盖住会让玻璃效果彻底不可见（v1.11.0 修正）。
    MaterialTheme(colorScheme = GlassPanelColorScheme) {
        // 【常驻组合的伴随项】主滚动位置：今天"每次收起都把列表拆出组合"会天然复位到顶部；
        // 常驻组合（DebugSwitches.precomposeListKeepAlive=true）后列表不再被拆 ⇒ 必须显式复位，
        // 才能保持"每次打开都从顶部开始"的原有行为 ✓（atRest 只在"面板已完全收起并静止"时为 true，
        // 复位发生在收起态、屏幕上看不见；该路径对旧行为是空操作）。
        val listScroll = rememberScrollState()
        LaunchedEffect(atRest) { if (atRest) listScroll.scrollTo(0) }
        // 【P46·两页拆分】点底部玻璃标签栏切页后，把列表滚回顶部：
        //   底栏常驻在面板底边 ⇒ 用户可能在页面任意滚动位置切页；新页从顶部开始读更自然 ✓
        //   只在两页模式生效（单页回退档下 advancedTab 恒 0、本效果不触发）✓
        LaunchedEffect(advancedTab) {
            if (DebugSwitches.panelTwoPageTabs && listScroll.value > 0) {
                listScroll.animateScrollTo(0)
            }
        }
        // 【P43 取证·只读】把列表的滚动状态发布给 dumpState（读 value/maxValue/viewportSize），
        // 并在滚动节点【外/内】各挂一次 onSizeChanged —— 分别拿到「滚动视口高」与「内容实际高」。
        // 三者都只写普通 @Volatile 字段（非快照状态）⇒ 零重组、零重绘、对像素与性能零影响 ✓
        androidx.compose.runtime.DisposableEffect(listScroll) {
            com.example.liquidglass.debug.ListSlotProbe.attach(listScroll)
            onDispose { com.example.liquidglass.debug.ListSlotProbe.attach(null) }
        }
        Column(
            modifier
                .fillMaxWidth()
                .heightIn(max = 470.dp)
                // verticalScroll【之外】= 滚动节点自身尺寸 = 视口高
                .onSizeChanged { com.example.liquidglass.debug.ListSlotProbe.viewportPx = it.height }
                .verticalScroll(listScroll)
                // ===== 【P43·半屏显示不全·根因修复】列表内容末尾余量（px）=====
                // 半屏（p=1）时槽高（2885px）比面板可见内容区（≈1407px）高出 1266px ⇒ 滚动视口
                // 尽头落在屏幕之外 ⇒ 内容尾部滚不到。这里把这段差值补进【可滚范围】（不改槽高、
                // 不改视口、不改面板几何）：末行在半屏与满高都停在屏幕底边，展开过程零跳变 ✓。
                // ⚠️ 只在 layout 阶段读（内部 pNow()）⇒ 组合期零订阅、零逐帧重组 ✓；
                //    开关关 / provider 为空 ⇒ pad=0 ⇒ 逐字回到改动前 ✓
                .layout { measurable, constraints ->
                    val padPx = listBottomPadPx?.value?.invoke()?.coerceAtLeast(0) ?: 0
                    // 【P43 取证】把 layout 阶段实际算出的余量写进探针（普通 @Volatile 字段，零订阅）
                    com.example.liquidglass.debug.ListSlotProbe.padPx = padPx
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height + padPx) { placeable.place(0, 0) }
                }
                // verticalScroll【之内】= 子节点尺寸 = 列表内容实际高（不含上面那段余量）
                .onSizeChanged { com.example.liquidglass.debug.ListSlotProbe.contentPx = it.height }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val params = uiState.parameters

            if (licenses && DebugSwitches.licensesPage) {
                // ==================== 二级④：开源许可与致谢（P29）====================
                // 与「更多设置 / 图片编辑 / 组件演示」同级：同一个面板、同一套页切换机制（只换内容）
                // ⇒ 页切换不碰进度 p、不碰面板几何 ⇒ 进入/返回与面板动画一致 ✓
                // 条目较多（7 条来源 + Apache-2.0 / MIT 全文）⇒ 靠本列既有的 verticalScroll 滚到底
                // （验收含"滚到底能看到最后一条"的截图与节点树证据）。
                // 入口只在「更多设置」底部「关于」节 ⇒ 开关关（licensesPage=false）时本分支恒不进 ✓
                LicensesPage()
            } else if (imageEdit && DebugSwitches.imageEditMenu) {
                // ==================== 二级②：图片编辑（官方 ACTION_EDIT）====================
                // 与「更多设置」同级：同一个面板、同一套页切换机制（只换内容）⇒ 页切换不碰进度 p、
                // 不碰面板几何 ⇒ 进入/返回与面板动画一致 ✓（逐帧证据见验收报告）
                ImageEditPage(
                    uiState = uiState,
                    context = context,
                    scope = scope,
                    onPickImage = { imagePicker.launch("image/*") },
                    applyBuiltinWallpaper = applyBuiltinWallpaper
                )
            } else if (componentDemo && DebugSwitches.liquidComponentsDemo) {
                // ==================== 二级③：组件演示（P28a·批 1）====================
                // 与「图片编辑」同级：同一个面板、同一套页切换机制（只换内容）⇒ 页切换不碰进度 p、
                // 不碰面板几何 ⇒ 进入/返回与面板动画一致 ✓
                // Backdrop 直接复用面板自己的采样源（adapter.captureLayer = 整页背景捕获层）
                // ⇒ 演示的玻璃与主界面玻璃同源同材质，无需另造演示背景层 ✓
                val demoAdapter = LocalGlassAdapter.current
                if (demoAdapter != null) {
                    com.example.liquidglass.ui.components.liquid.LiquidComponentsDemoPage(
                        backdrop = demoAdapter.captureLayer
                    )
                }
            } else if (!advanced) {
                // ==================== 一级：基础 ====================
                SectionTitle("玻璃风格")
                // 【玻璃风格拉杆·运行时 A/B 纪律】@Volatile 开关不产生订阅 ⇒ 组合期读取本开关时，
                // 必须陪读一次 DebugBridge.revision（setSwitches 会自增它），否则 adb 翻开关不重绘。
                @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                if (DebugSwitches.glassStyleSlider) {
                    // ==================== 【玻璃风格·连续拉杆（iOS 27 式跟随）】 ====================
                    // 用户原话：「把【玻璃风格】改成 iOS 27 那种跟随拉杆变化——拉杆最左=现在的通透档，
                    // 拉杆最右=现在的磨砂档，默认中间=均衡档。」
                    // 复用既有 ParameterSlider（胶囊手柄 66×34dp、按压不做任何动画）；显示值 = 由当前
                    // 参数反推位置（分段线性逆映射）⇒ 预设切换/复位后拉杆位置自动同步 ✓。
                    val stylePos = GlassParameters.stylePositionOf(params.glassTransparency)
                    ParameterSlider(
                        title = "玻璃风格",
                        value = stylePos,
                        range = 0f..1f,
                        enabled = uiState.preset != GlassPreset.CUSTOM,
                        format = { s -> GlassParameters.stylePresetOf(s).displayName },
                        onValueChange = { uiState.setGlassStyle(it) }
                    )
                    // 三档刻度标签：左「通透」、中「均衡」、右「磨砂」
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("通透", fontSize = 11.sp, color = glassPalette.hintText)
                        Text("均衡", fontSize = 11.sp, color = glassPalette.hintText)
                        Text("磨砂", fontSize = 11.sp, color = glassPalette.hintText)
                    }
                } else {
                    Row(
                        Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassPreset.entries.filter { it != GlassPreset.CUSTOM }.forEach { preset ->
                            PanelFilterChip(shape = PanelChipShape, selected = uiState.preset == preset,
                                onClick = { uiState.selectPreset(preset) },
                                label = { Text(preset.displayName, fontSize = 13.sp) },
                                colors = PanelChipColors,
                                modifier = Modifier.semantics {
                                    contentDescription = "预设：${preset.displayName}"
                                }
                            )
                        }
                    }
                }

                Text("更多光学参数、画质与调试在下方『更多设置』里", fontSize = 11.sp, color = glassPalette.hintText)

                SectionTitle("形状与布局")
                // ===== 【选中卡 · 用户需求①】本区全部作用于【当前选中的那块玻璃】=====
                // 默认选中卡 = 0（主卡）⇒ 默认档下与改动前逐字一致（零回归 ✓）。
                // 选中卡的两条切换路径：① 直接点屏幕上的那块玻璃（LiquidGlassScreen 的 onTap）；
                //   ② 这里的「1 / 2 / 3 / 4」chips。切换时屏幕上那块玻璃会显示一圈高亮描边（可见反馈 ✓）。
                val targetCard = uiState.selectedCardIndex.coerceIn(0, uiState.visibleCardCount() - 1)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "正在调整：${uiState.cardDisplayName(targetCard)}",
                        fontSize = 12.sp,
                        color = glassPalette.softCaption,
                        modifier = Modifier.semantics {
                            contentDescription = "正在调整：玻璃 ${targetCard + 1}"
                        }
                    )
                    if (uiState.visibleCardCount() > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in 0 until uiState.visibleCardCount()) {
                                PanelFilterChip(
                                    shape = PanelChipShape,
                                    selected = i == targetCard,
                                    onClick = { uiState.selectCard(i) },
                                    label = { Text("${i + 1}", fontSize = 12.sp) },
                                    colors = PanelChipColors,
                                    modifier = Modifier.semantics {
                                        contentDescription = "选择玻璃 ${i + 1}"
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassShape.entries.forEach { shape ->
                        // 选中标记 = 【当前选中卡】的形状：
                        //   · 选中追加块（≥3 块模式的第 3/4 块）→ 看它自己的形状槽；
                        //   · 选中主卡/第二块 → 沿用"最多两个"的多选语义（那两个槽位是 P07/P12 的既有输入 ✓）
                        val selected = if (targetCard >= 2) uiState.shapeOfCard(targetCard) == shape
                        else uiState.selectedShapes.contains(shape)
                        PanelFilterChip(shape = PanelChipShape, selected = selected,
                            onClick = {
                                if (targetCard >= 2) {
                                    // 追加块：直接写该块的形状槽（不参与"最多两个"上限 ⇒ 不弹上限提示）
                                    uiState.setShapeOfExtraCard(targetCard, shape)
                                } else if (!uiState.toggleShape(shape)) {
                                    android.widget.Toast.makeText(
                                        context, "已达玻璃上限", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            label = { Text(shape.displayName, fontSize = 13.sp) },
                            // 选中标记：对勾放在胶囊【内部】靠右、垂直居中。
                            // 胶囊的"右下角"是圆弧，角标无论怎么偏都会半挂在弧上（实测下沿越界 4px），
                            // 因此改用 chip 自带的 trailingIcon 位置，几何上必然贴合、居中。
                            trailingIcon = if (selected) {
                                {
                                    Text(
                                        "✓",
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontSize = 14.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                            } else null,
                            modifier = Modifier.semantics {
                                contentDescription = "玻璃形状：${shape.displayName}"
                            }
                        )
                    }
                }
                ParameterSlider(
                    // 【选中卡】尺寸作用于当前选中卡（用户需求①：手指点哪块玻璃 / chips 选哪块，滑块就改哪块 ✓）。
                    // 默认选中卡 = 主卡 ⇒ 与改动前逐字一致（值 = glassSize、范围 0.2..0.5 ✓）
                    title = "玻璃尺寸 · ${uiState.cardDisplayName(targetCard)}",
                    value = uiState.sizeOfCard(targetCard),
                    range = uiState.sizeRangeOfCard(targetCard),
                    format = { "%.2f".format2(it) },
                    onValueChange = { uiState.setSizeOfCard(targetCard, it) }
                )


                SectionTitle("背景壁纸 · 实拍山水")
                Row(
                    Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BuiltinWallpapers.forEachIndexed { index, (label, resId) ->
                        PanelFilterChip(shape = PanelChipShape, selected = uiState.bgBuiltinIndex == index,
                            onClick = { applyBuiltinWallpaper(index, resId) },
                            label = { Text(label, fontSize = 13.sp) },
                            colors = PanelChipColors,
                            modifier = Modifier.semantics { contentDescription = "背景壁纸：$label" }
                        )
                    }
                    // 自定义导入图片（用户要求：从二级菜单提到一级，与内置壁纸并列）
                    PanelFilterChip(
                        shape = PanelChipShape,
                        selected = uiState.bgImage != null && uiState.bgBuiltinIndex < 0,   // 内置壁纸生效时按"未选"显示（否则残留自定义图会让它一直蓝底 ✗）
                        onClick = { imagePicker.launch("image/*") },
                        label = { Text("自定义图片", fontSize = 13.sp) },
                        colors = PanelChipColors
                    )
                    // 【图片编辑】二级入口（与「更多设置」同级）：独立二级菜单「图片编辑」——
                    // 把「背景壁纸」的图片编辑类动作（官方编辑器 ACTION_EDIT / 内置旋转 / 写入相册 / 缩放偏移）收到一处。
                    // 为什么放在这一行：① 与它要收拢的动作同区（背景壁纸）✓ ② 本行是【横向滚动 chips 行】，
                    //   末尾追加【不改变任何既有元素的纵向位置】（行高 = chip 高 不变）⇒ 一级页既有内容零位移 ✓
                    //   （对比：追加成整块按钮会落在一级页内容槽 1619px 之外 ⇒ 必须滑动才能看到 ✗）
                    // 默认开；一行回退 = DebugSwitches.imageEditMenu = false ⇒ 本 chip 不进组合 ⇒ 与改动前逐像素一致 ✓
                    if (DebugSwitches.imageEditMenu) {
                        PanelFilterChip(
                            shape = PanelChipShape,
                            selected = false,
                            onClick = onOpenImageEdit,
                            label = { Text("图片编辑 ›", fontSize = 13.sp) },
                            colors = PanelChipColors,
                            modifier = Modifier.semantics { contentDescription = "图片编辑" }
                        )
                    }
                    // 【P28a·批 1】液态组件演示二级入口（与「图片编辑 ›」同构：同一行、同一形态）。
                    // 为什么放这里：① 与既有二级页入口同一处（用户已在此行找入口）；② 追加在【横向滚动行】
                    //   末尾 ⇒ 既有元素的纵向位置零位移（行高 = chip 高 不变）✓。
                    // 默认开；一行回退 = DebugSwitches.liquidComponentsDemo = false ⇒ 本 chip 不进组合 ⇒
                    // 与改动前逐像素一致 ✓（开关读取的订阅由本分支顶部的 DebugBridge.revision 陪读保证）
                    // 【P38·入口可达性修复】旧入口 = 本行（横向滚动行）【末尾】的 chip —— 被视口裁掉：
                    //   uiautomator 实测节点 bounds=[1736,2341][1776,2405]、文字 [1768,2353][1776,2393]
                    //   （只剩 8px 宽）⇒ 用户看不见入口 ✗（用户反馈「还是没应用成功」的直接原因）。
                    //   新入口移到【面板表头标题行右侧】（LiquidGlassScreen 的表头 Row，显式按钮，
                    //   表头不在滚动区 ⇒ 不裁、任何 p 都可见 ✓，且不动任何既有元素的坐标 ✓）。
                    //   开关 liquidDemoEntryHeader=false（默认 true）一行切回本条 ⇒ 旧形态作为对照档保留 ✓
                    if (DebugSwitches.liquidComponentsDemo && !DebugSwitches.liquidDemoEntryHeader) {
                        PanelFilterChip(
                            shape = PanelChipShape,
                            selected = false,
                            onClick = onOpenComponentDemo,
                            label = { Text("组件演示 ›", fontSize = 13.sp) },
                            colors = PanelChipColors,
                            modifier = Modifier.semantics { contentDescription = "组件演示" }
                        )
                    }

                }

                // ===== 【P46·尾巴修复】「3D 演示」入口并入「交互与动效」节标题行 =====
                // 背景（上一轮副作用）：P45 把「3D 演示」整节（节标题 60px + 入口行 96px + 两处间距）
                //   上移到此处后，「交互与动效」节被整体顶低 164px ⇒ 节内第 3 行「拖动拉伸（玻璃）」
                //   在【p=1 未滚动】时被列表可视区裁掉（uiautomator 实测节点只剩 11px 高）✗。
                // 修法（用户给的可选做法②「让入口插在『交互与动效』节标题之前但不挤掉它」）：
                //   撤销独立节标题 + 独立入口行，把钻石入口做成【与节标题同一行】右侧的 chip ——
                //   该行高 = max(节标题块 60px, chip 96px) = 96px ⇒ 整块省回 128px，
                //   「交互与动效」节三行（减少动态效果 / 多卡演示 / 拖动拉伸）全部回到 p=1 可见区 ✓
                //   （改前/改后 uiautomator bounds 数字见验收）。
                // 入口语义逐字保留：文案「钻石 · 3D 逐面折射演示」+ contentDescription「钻石演示」
                //   + onClick = onOpenDiamond()（点击行为与上一轮的入口完全一致）✓
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("交互与动效")
                    PanelFilterChip(
                        shape = PanelChipShape,
                        selected = false,
                        onClick = { onOpenDiamond() },
                        label = { Text("钻石 · 3D 逐面折射演示", fontSize = 13.sp) },
                        colors = PanelChipColors,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .semantics { contentDescription = "钻石演示" }
                    )
                }
                SwitchRow("减少动态效果（关按压形变）", uiState.reduceMotion) { uiState.reduceMotion = it }

                // ===== 【需求④：「双卡演示」+「多卡演示」完全合并成一个「多卡演示」开关 + 2/3/4 档位】=====
                // 用户原话：「将『双卡演示』和『多卡演示』完全合并成一个选项，即『多卡演示』，然后在里面
                //   再选择是 2 块、3 块还是 4 块玻璃。」
                // 实现：面板只剩【一行】开关；它的写入统一走 GlassUiState.multiCardDemoOn ——
                //   ① 内部 twoCardDemo（口径一字不动：P07 二次折射 / P12 融合 / 第二块形状的既有驱动）；
                //   ② @Volatile 的 DebugSwitches.multiCardDemo（多卡渲染 + 点击置顶的总闸）。
                // 默认 = 关（= 与改动前观测一致：单卡）；默认档 = 2 块（cardCount 默认 2 = 改动前
                //   "双卡演示"打开时的表现 ⇒ 可观测行为一致 ✓）。
                // 一行回退：把这一行换回旧的「双卡演示」两行即可（内部字段没删、语义没变 ✓）
                SwitchRow("多卡演示（2 / 3 / 4 块玻璃 · 点击置顶）", uiState.multiCardDemoOn) {
                    uiState.multiCardDemoOn = it
                    DebugBridge.revision.intValue = DebugBridge.revision.intValue + 1
                }
                if (uiState.multiCardDemoOn) {
                    // 【P62·说明文字对比度·2026-09-18】默认色 0xFFC3CFE6 在面板填充层（0xFFC6CAD0，
                    //   L=0.5880）上的 WCAG 对比度只有 ≈1.6 ✗（用户原话：「多卡演示下方的说明文字颜色
                    //   和背景过于相似导致无法阅读」）。handoffContrastFloor=true（默认）⇒ 换成面板
                    //   onSurface 深蓝 0xFF0E1626（对比 ≈7.9 ✓ ≥4.5）；false ⇒ 原色逐字回退 ✓。
                    val captionP62Color =
                        if (DebugSwitches.handoffContrastFloor || glassPalette.isDark) glassPalette.scheme.onSurface else glassPalette.softCaption
                    Text(
                        "每块玻璃各自采样背景捕获层、可独立拖动；点哪块哪块置顶（z 序）。" +
                            "「形状与布局」的尺寸/形状作用于【正在调整】的那一块。",
                        fontSize = 11.sp,
                        color = captionP62Color
                    )
                    Text(
                        "同屏玻璃块数（默认 2 块 = 原「双卡演示」的表现）：",
                        fontSize = 11.sp,
                        color = captionP62Color
                    )
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(2, 3, 4).forEach { n ->
                            PanelFilterChip(
                                shape = PanelChipShape,
                                selected = uiState.cardCount == n,
                                onClick = { uiState.applyCardCount(n) },
                                label = { Text("$n 块", fontSize = 13.sp) },
                                colors = PanelChipColors,
                                modifier = Modifier.semantics {
                                    contentDescription = "同屏玻璃块数：$n"
                                }
                            )
                        }
                    }
                    // ---- 【需求①·吸附/融合 meld：默认关 + 显式标注"实验"】----
                    // 用户原话：「并且吸附效果也做得很诡异。先把吸附效果关掉」⇒ 默认档下【没有任何吸附/
                    //   融合形变】（颈部/焊缝/细丝都不出现 ✓）：默认值 = readSwitchFile("lg_meld_on")
                    //   = 设备上没有 files/lg_meld_on 时为 false（debug/DebugSwitches.kt 的既有实现，
                    //   本次未改动 ✓）。本开关只是把它做成【面板上可见、默认关、文案标注实验】的状态，
                    //   防止误触；打开后两卡靠近时会按形状并集渲染（会形变 = 实验档 ✗ 默认不打开）。
                    SwitchRow("吸附融合 meld（实验 · 默认关）", DebugSwitches.dualCardMeld) {
                        DebugSwitches.dualCardMeld = it
                        DebugBridge.revision.intValue = DebugBridge.revision.intValue + 1
                    }
                    Text(
                        if (DebugSwitches.dualCardMeld)
                            "开（实验）：两卡靠近时按形状并集渲染成一块连续玻璃 —— 会出现吸附形变"
                        else
                            "关（默认）：完全没有吸附/融合形变 —— 颈部/焊缝/细丝都不会出现",
                        fontSize = 11.sp,
                        color = glassPalette.softCaption
                    )
                }
                SwitchRow("拖动拉伸（玻璃）", uiState.stretchOnDrag) { uiState.stretchOnDrag = it }
                // 【已删除的开关行】原「iOS 透镜折射剖面」SwitchRow 按用户要求移除（剖面行为固定【开】）：
                // 用户原话：「还有 iOS 透镜折射剖面关掉以后效果好诡异，直接把这个开关删掉吧它默认打开就可以了」
                // ⇒ 消费点 LiquidGlassScreen 写死 `lensProfile = { true }`（不再读 uiState.iosLensProfile）；
                //   剖面实现与参数未动；字段 iosLensProfile 保留仅为 A/B 留档（不再是消费源）。
                // 【入口上移·用户需求】原「3D 演示」节（SectionTitle + 钻石入口）位于一级页【最底部】，
                //   uiautomator 实测节点被滚动视口裁掉（只剩 3px 高，用户看不见）⇒ 整节【逐字】移到
                //   「背景壁纸」chips 行之后（见上方同一节），入口 y 明显上移且默认可见可点。
                //   节内代码一行未改（标题、背景/then(panelEdgeAa)/clip/clickable/padding/semantics/文案全保留）。
                //   回退 = 把那一整节原样贴回本行之后（其余代码零改动）。

            // 二级入口：形状 / 背景 / 高级光学 / 调试
            // 【P46·整行按钮 ⇒ 上游 LiquidButton】文案/回调/无障碍逐字保留；一行回退见
            //   PanelRowOutlinedButtonLegacy（= 改动前的 M3 OutlinedButton，逐字未动）✓
            PanelRowOutlinedButton(
                onClick = onOpenAdvanced,
                contentDescription = "更多设置",
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text("更多设置 · 画质 / 光学 / 形变 / 调试", fontSize = 13.sp)   // ⓓ 区块已删，文案同步 ✓
            }
            } else {
                    // ==================== 【P46】二级「更多设置」：两页拆分（默认）/ 单页回退 ====================
                    // 用户点名：把「更多设置」按语义拆成「外观设置 / 高级设置」两页，底部一条【真实上游
                    //   LiquidBottomTabs】玻璃悬浮底栏切页（底栏画在面板容器底边内侧，见 LiquidGlassScreen）。
                    //   · 两页内容 = 下方两个页块函数（MoreSettingsAppearancePage / MoreSettingsAdvancedPage），
                    //     控件调用与拆分前【逐字相同】（只换了它们出现在哪一页）；
                    //   · panelTwoPageTabs=false（一行回退）⇒ else 分支 = 拆分前的单页原样（逐字保留）✓
                    //   · @Volatile 开关不产生订阅 ⇒ 组合期陪读一次 revision（setSwitches 会自增它）
                    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
                    // 【P56·底栏两并列项】新档（默认 panelLevelTabs=true）下底栏两个项 = 一级菜单 / 更多设置
                    //   ⇒ 页内不再有第三级切页入口：「更多设置」内容 = 单页（下方 else 分支 = 拆分前原样，
                    //   外观项 + 高级项同页、滚动全可达）；两页拆分只在【旧档】（panelLevelTabs=false）
                    //   下启用 —— 那一档的底栏两个项仍是「外观设置 / 高级设置」（见 LiquidGlassScreen）✓
                    if (DebugSwitches.panelTwoPageTabs && !DebugSwitches.panelLevelTabs) {
                        if (advancedTab == 1) {
                            // -------------------- 高级设置页（tab 1）--------------------
                            MoreSettingsAdvancedPage(
                                uiState = uiState,
                                params = params,
                                onOpenLicenses = onOpenLicenses
                            )
                        } else {
                            // -------------------- 外观设置页（tab 0，默认）--------------------
                            MoreSettingsAppearancePage(
                                uiState = uiState,
                                params = params,
                                context = context,
                                imagePicker = imagePicker,
                                applyBuiltinWallpaper = applyBuiltinWallpaper
                            )
                        }
                    } else {
                    // ↓↓↓ 【一行回退】单页「更多设置」= 拆分前原样（至本 else 分支末尾逐字保留）↓↓↓
                    // 恢复默认设置（用户要求）：放在一级菜单最上方，随时可一键复位
                    // 【P46·整行按钮 ⇒ 上游 LiquidButton】文案/回调/行高（48dp，实测旧 Box 行 = 96px@2x）
                    //   逐字保留；关 DebugSwitches.liquidRealButtons 即逐字回退到改动前的 Box 行（见
                    //   PanelRowButtonLegacy —— 背景/AA/clip/clickable/padding 全在，一字未动）✓
                    PanelRowButton(
                        onClick = {
                            uiState.resetToDefaults(context)
                            // 复位后立刻把默认内置壁纸解码回画面（否则 bgImage=null 只剩深色兜底渐变 ✗）
                            applyBuiltinWallpaper(0, BuiltinWallpapers.first().second)
                        }
                    ) {
                        Text("恢复默认设置", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
                    }

                    // 调试日志（用户提议）：开关 + 一键导出到 /sdcard/Android/data/<pkg>/files/
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("记录调试日志（动画+手势）", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
                        androidx.compose.material3.Switch(
                            checked = uiState.debugLogEnabled,
                            onCheckedChange = {
                                uiState.debugLogEnabled = it
                                com.example.liquidglass.debug.AppDebugLog.enabled = it
                                com.example.liquidglass.debug.AppDebugLog.log("UI", if (it) "调试日志开启 session=" else "调试日志关闭")
                            }
                        )
                    }
                    // 注意：LocalContext 必须在组合上下文里取（不能在 clickable lambda 里取 ✗）
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    // 【P19·分享调试日志】原「导出调试日志到文件」整行按钮 ⇒ 同一行两个动作
                    //   （导出 + 分享）：行高 48dp 与改动前逐像素相同 ⇒ 零位移；
                    //   DebugSwitches.iflashare=false 时本函数内部逐字回退到改动前的单整行按钮 ✓
                    DebugLogActionsRow(ctx)
                    Spacer(Modifier.height(6.dp))
            // ==================== 二级：更多设置 ====================

            // ==================== 【深色/亮色模式·2026-09-24】外观类目：主题模式三档 ====================
            // 用户原话：「把深色与亮色模式的自动切换做了吧」⇒ 默认【跟随系统】（接入 Compose 的
            //   isSystemInDarkTheme()，系统主题一变当场跟随，含 `adb shell cmd uimode night yes|no` ✓）；
            //   另给手动两档（强制亮色 / 强制暗色）。状态落 SharedPreferences —— 与背景选择【同一套】
            //   （同一个 prefs 文件 liquid_glass_background）⇒ 重进 App / 重启进程都不丢 ✓。
            //   切换即时生效（GlassTheme.mode 是 Compose 快照状态 ⇒ 点一下整棵树重组）。
            // 一行回退：setSwitches themeAutoSwitch 0 ⇒ 恒亮色档（= 改动前行为）。
            SectionTitle("外观")
            Text("深色 / 亮色模式（默认跟随系统）", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassThemeMode.entries.forEach { m ->
                    PanelFilterChip(
                        shape = PanelChipShape,
                        selected = GlassTheme.mode == m,
                        onClick = { GlassTheme.set(context, m) },
                        label = { Text(m.label, fontSize = 13.sp) },
                        colors = PanelChipColors,
                        modifier = Modifier.semantics { contentDescription = "主题模式：${m.label}" }
                    )
                }
            }
            SectionTitle("背景内容")
            OutlinedTextField(
            value = uiState.bgText,
            onValueChange = { uiState.bgText = it },
            label = { Text("背景测试文字", fontSize = 13.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFF4F7FF),
                unfocusedTextColor = Color(0xFFF4F7FF),
                focusedBorderColor = Color(0xFFBFD4FF),
                unfocusedBorderColor = Color(0x73FFFFFF),
                focusedLabelColor = Color(0xFFBFD4FF),
                unfocusedLabelColor = Color(0xFFD3DCEC),
                cursorColor = Color(0xFFBFD4FF)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "背景测试文字内容" }
            )
            ParameterSlider("背景文字字号 (sp)", uiState.bgTextSizeSp, 20f..120f, { "%.0f".format2(it) }) {
            uiState.bgTextSizeSp = it
            }
            ParameterSlider("背景文字透明度", uiState.bgTextAlpha, 0.1f..1f, { "%.2f".format2(it) }) {
            uiState.bgTextAlpha = it
            }
            ParameterSlider("背景文字位置", uiState.bgTextPositionY, 0.2f..0.9f, { "%.2f".format2(it) }) {
            uiState.bgTextPositionY = it
            }
            ParameterSlider("背景文字旋转 (°)", uiState.bgTextRotationDegrees, -180f..180f, { "%.0f".format2(it) }) {
            uiState.bgTextRotationDegrees = it
            }

            // 背景图片：SAF 选择本地图片（进入 Backdrop 捕获层，穿过玻璃会折射）
            Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            Button(
                onClick = { imagePicker.launch("image/*") },
                modifier = Modifier.weight(1f).semantics { contentDescription = "选择背景图片" }
            ) {
                Text(if (uiState.bgImage == null) "选择背景图片" else "更换图片", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = {
                    uiState.resetBackgroundImage(context)
                    applyBuiltinWallpaper(0, BuiltinWallpapers.first().second)
                },
                enabled = uiState.bgImage != null,
                modifier = Modifier.weight(1f).semantics { contentDescription = "恢复默认背景" }
            ) {
                Text("恢复默认背景", fontSize = 13.sp)
            }
            }
            if (uiState.bgImage != null) {
            ParameterSlider("图片缩放 (变焦)", uiState.bgImageZoom, 1f..3f, { "%.2f".format2(it) }) {
                uiState.bgImageZoom = it
                BackgroundImageStore.saveAdjustments(context, it, uiState.bgImageOffsetX, uiState.bgImageOffsetY)
            }
            ParameterSlider("图片水平偏移", uiState.bgImageOffsetX, -0.5f..0.5f, { "%.2f".format2(it) }) {
                uiState.bgImageOffsetX = it
                BackgroundImageStore.saveAdjustments(context, uiState.bgImageZoom, it, uiState.bgImageOffsetY)
            }
            ParameterSlider("图片垂直偏移", uiState.bgImageOffsetY, -0.5f..0.5f, { "%.2f".format2(it) }) {
                uiState.bgImageOffsetY = it
                BackgroundImageStore.saveAdjustments(context, uiState.bgImageZoom, uiState.bgImageOffsetX, it)
            }
            }

            // 【P57·画质行移除 + 固定最清晰档（默认开）· 本份 = 单页版「更多设置」< 默认新档 panelLevelTabs=true 下可见的那一份 >】
            //   用户原话：「取消更多设置里面的画质调节
            //   选项，默认按最清晰的显示」⇒ 整节（标题 + 说明 + chips 行）不进组合；生效档固定 =
            //   GlassUiState.quality 的 getter（恒 QUALITY「高清」= 13 taps）。
            //   一行回退：setSwitches qualityFixedBest 0 ⇒ 下面整节逐字回来 ✓
            @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
            if (!DebugSwitches.qualityFixedBest) {
                        SectionTitle("画质")
            Text("画质（数值越高越柔和、越费电）", fontSize = 11.sp, color = glassPalette.softCaption)
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassQuality.entries.forEach { quality ->
                    PanelFilterChip(shape = PanelChipShape, selected = uiState.quality == quality,
                        onClick = { uiState.quality = quality },
                        label = {
                            Text("${quality.displayName} · ${quality.tapCount}t", fontSize = 13.sp)
                        },
                        colors = PanelChipColors,
                        modifier = Modifier.semantics {
                            contentDescription = "画质档位：${quality.displayName}"
                        }
                    )
                }
            }
            }

            SectionTitle("光学参数")

            // 宏观透明度：仅在预设模式下可调（CUSTOM 模式由下方独立参数完全控制）
            ParameterSlider(
                title = "玻璃透明度",
                value = params.glassTransparency,
                // 量程必须与映射窗口一致（GlassParameters.TRANSPARENCY_RANGE = 0.48..0.94，由预设派生）：
                // 映射对窗口外输入一律钳位；若滑杆仍用 0f..1f，则 0~0.48 与 0.94~1 全是死区 ✗（设置类缺陷②）
                range = GlassParameters.TRANSPARENCY_RANGE,
                enabled = uiState.preset != GlassPreset.CUSTOM,
                format = { "%.2f".format2(it) },
                onValueChange = { uiState.setTransparency(it) }
            )
            if (uiState.preset == GlassPreset.CUSTOM) {
                Text(
                    "自定义模式：宏观透明度由下方参数独立控制",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            val density = LocalDensity.current.density
            ParameterSlider("背景透射率", params.backgroundTransmission, 0.5f..1f, { "%.3f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(backgroundTransmission = it) }
            }
            ParameterSlider("材质不透明度", params.materialOpacity, 0f..0.25f, { "%.3f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(materialOpacity = it) }
            }
            ParameterSlider("色调不透明度", params.tintOpacity, 0f..0.15f, { "%.3f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(tintOpacity = it) }
            }
            ParameterSlider("中心模糊 (dp)", params.blurRadiusDp, 0f..24f, { "%.1f".format2(it) },
                pxText = { " / ${"%.1f".format2(it * density)}px" }) {
                uiState.updateParameter { p -> p.copy(blurRadiusDp = it) }
            }
            ParameterSlider("边缘模糊 (dp)", params.edgeBlurRadiusDp, 0f..28f, { "%.1f".format2(it) },
                pxText = { " / ${"%.1f".format2(it * density)}px" }) {
                uiState.updateParameter { p -> p.copy(edgeBlurRadiusDp = it) }
            }
            // 量程必须覆盖预设值 32dp（三档预设共用）：原 0f..20f 显示越界、把手钉在末端、一拖就跳变 ✗
            ParameterSlider("折射强度 (dp)", params.refractionOffsetDp, 0f..48f, { "%.1f".format2(it) },
                pxText = { " / ${"%.1f".format2(it * density)}px" }) {
                uiState.updateParameter { p -> p.copy(refractionOffsetDp = it) }
            }
            ParameterSlider("色散偏移 (dp)", params.dispersionOffsetDp, 0f..1f, { "%.2f".format2(it) },
                pxText = { " / ${"%.2f".format2(it * density)}px" }) {
                uiState.updateParameter { p -> p.copy(dispersionOffsetDp = it) }
            }
            ParameterSlider("色散强度", params.dispersionStrength, 0f..0.5f, { "%.2f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(dispersionStrength = it) }
            }
            // 量程必须覆盖预设值 1.32（三档预设共用）：原 0f..1.2f 同上越界 ✗
            ParameterSlider("菲涅尔强度", params.fresnelStrength, 0f..1.6f, { "%.2f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(fresnelStrength = it) }
            }
            ParameterSlider("边缘高光", params.edgeHighlightOpacity, 0f..0.5f, { "%.3f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(edgeHighlightOpacity = it) }
            }
            ParameterSlider("边缘暗边", params.edgeShadowOpacity, 0f..0.4f, { "%.3f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(edgeShadowOpacity = it) }
            }
            ParameterSlider("局部暗化", params.localDimmingOpacity, 0f..0.16f, { "%.3f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(localDimmingOpacity = it) }
            }
            ParameterSlider("饱和度", params.saturation, 0.8f..1.2f, { "%.2f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(saturation = it) }
            }

            SectionTitle("形变与弹簧")
            ParameterSlider("形变强度", params.deformationStrength, 0f..1.5f, { "%.2f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(deformationStrength = it) }
            }
            ParameterSlider("弹簧刚度", params.springStiffness, 100f..900f, { "%.0f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(springStiffness = it) }
            }
            ParameterSlider("阻尼比", params.springDampingRatio, 0.2f..1.2f, { "%.2f".format2(it) }) {
                uiState.updateParameter { p -> p.copy(springDampingRatio = it) }
            }

            SectionTitle("调试模式（逐层排查光学管线）")
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassDebugMode.entries.forEach { mode ->
                    PanelFilterChip(shape = PanelChipShape, selected = uiState.debugMode == mode,
                        onClick = { uiState.debugMode = mode },
                        label = { Text(mode.displayName, fontSize = 12.sp) },
                        colors = PanelChipColors,
                        modifier = Modifier.semantics {
                            contentDescription = "调试模式：${mode.displayName}"
                        }
                    )
                }
            }

            // ==================== 【P29】「关于」节（「更多设置」整页最底部）====================
            // 位置：继「调试模式」之后的最后一节（用户点名：更多设置【底部】新增一节「关于」）——
            //   · 追加在滚动列表末尾 ⇒ 「更多设置」既有元素的位置/尺寸零位移（不会把上面任何一行顶下去 ✓）
            //   · 入口未开放（开关 licensesPage=false）时整节不进组合 ⇒ 与改动前逐像素一致 ✓
            // 开关读取的订阅：先陪读一次 DebugBridge.revision（setSwitches 命令会自增它）⇒ adb 翻开关当场生效 ✓
            @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
            if (DebugSwitches.licensesPage) {
                LicenseAboutSection(onOpenLicenses = onOpenLicenses)
            }

            Spacer(Modifier.padding(bottom = 8.dp))
            }
            // 【P46】关闭「两页拆分」if 的 else（单页回退）分支（其 if 分支在上方两页调用处）
            }
        }
    }
}

/**
 * ==================== 【P46】二级「更多设置」两页拆分：页块函数 ====================
 *
 * 用户点名（2026-09-17）：把「更多设置」按语义拆成「外观设置 / 高级设置」两页，底部一条
 *   【真实上游 LiquidBottomTabs】玻璃悬浮底栏切页（底栏画在面板容器底边内侧，见 LiquidGlassScreen）。
 *
 * 拆页口径（用户给的分组 + 本页实际内容）：
 *   · 外观设置 = 恢复默认设置 / 背景内容（壁纸图片 + 背景文字）/ 画质 / 外观参数
 *     （玻璃透明度 + 背景透射率 + 材质·色调不透明度 + 饱和度 = 「透明度 / 颜色」类外观项）；
 *   · 高级设置 = 调试日志两行 / 光学参数（中心·边缘模糊 / 折射 / 色散 / 菲涅尔 / 边缘高光·暗边 /
 *     局部暗化）/ 形变与弹簧（= 本页的手势与动效类条目）/ 调试模式 / 关于（开源许可入口）。
 *
 * ⚠️ 口径说明：「多卡演示 / 手势与动效」这两个名字在本页并不存在 —— 它们是一级页「交互与动效」
 *   节里的元素（本轮 ✗ 不动一级页其它元素）⇒ 高级页里按语义对应的就是本页的「形变与弹簧」
 *   （形变强度 / 弹簧刚度 / 阻尼比 = 手势与动效手感）与「调试模式」。两个开关保持原页面原位。
 *
 * ⚠️ 所有控件调用均从单页版【原样搬入】：标题文案 / 值域 / 格式化 / 回调一字未改（逐字保留 ✓）；
 *   两页末尾的 84dp 透明垫片只是给【悬浮玻璃底栏】让位（不遮挡内容），不是内容控件。
 */

/** 【P46·外观设置页块（tab 0，默认）】在「更多设置」的滚动列内直接调用（不引入额外布局节点）。 */
@Composable
private fun MoreSettingsAppearancePage(
    uiState: GlassUiState,
    params: GlassParameters,
    context: android.content.Context,
    imagePicker: androidx.activity.result.ActivityResultLauncher<String>,
    applyBuiltinWallpaper: (Int, Int) -> Unit
) {
    // 恢复默认设置（用户要求）：放在本页最上方，随时可一键复位
    // 【P46·整行按钮 ⇒ 上游 LiquidButton】文案/回调/行高（48dp，实测旧 Box 行 = 96px@2x）
    //   逐字保留；关 DebugSwitches.liquidRealButtons 即逐字回退到改动前的 Box 行（见
    //   PanelRowButtonLegacy —— 背景/AA/clip/clickable/padding 全在，一字未动）✓
    PanelRowButton(
        onClick = {
            uiState.resetToDefaults(context)
            // 复位后立刻把默认内置壁纸解码回画面（否则 bgImage=null 只剩深色兜底渐变 ✗）
            applyBuiltinWallpaper(0, BuiltinWallpapers.first().second)
        }
    ) {
        Text("恢复默认设置", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
    }

    // ==================== 【深色/亮色模式·2026-09-24】外观类目：主题模式三档 ====================
    // 用户原话：「把深色与亮色模式的自动切换做了吧」⇒ 默认【跟随系统】（接入 Compose 的
    //   isSystemInDarkTheme()，系统主题一变当场跟随，含 `adb shell cmd uimode night yes|no` ✓）；
    //   另给手动两档（强制亮色 / 强制暗色）。状态落 SharedPreferences —— 与背景选择【同一套】
    //   （同一个 prefs 文件 liquid_glass_background）⇒ 重进 App / 重启进程都不丢 ✓。
    //   切换即时生效（GlassTheme.mode 是 Compose 快照状态 ⇒ 点一下整棵树重组）。
    // 一行回退：setSwitches themeAutoSwitch 0 ⇒ 恒亮色档（= 改动前行为）。
    SectionTitle("外观")
    Text("深色 / 亮色模式（默认跟随系统）", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassThemeMode.entries.forEach { m ->
            PanelFilterChip(
                shape = PanelChipShape,
                selected = GlassTheme.mode == m,
                onClick = { GlassTheme.set(context, m) },
                label = { Text(m.label, fontSize = 13.sp) },
                colors = PanelChipColors,
                modifier = Modifier.semantics { contentDescription = "主题模式：${m.label}" }
            )
        }
    }
    SectionTitle("背景内容")
    OutlinedTextField(
    value = uiState.bgText,
    onValueChange = { uiState.bgText = it },
    label = { Text("背景测试文字", fontSize = 13.sp) },
    singleLine = true,
    textStyle = MaterialTheme.typography.bodyMedium,
    colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFF4F7FF),
        unfocusedTextColor = Color(0xFFF4F7FF),
        focusedBorderColor = Color(0xFFBFD4FF),
        unfocusedBorderColor = Color(0x73FFFFFF),
        focusedLabelColor = Color(0xFFBFD4FF),
        unfocusedLabelColor = Color(0xFFD3DCEC),
        cursorColor = Color(0xFFBFD4FF)
    ),
    modifier = Modifier
        .fillMaxWidth()
        .semantics { contentDescription = "背景测试文字内容" }
    )
    ParameterSlider("背景文字字号 (sp)", uiState.bgTextSizeSp, 20f..120f, { "%.0f".format2(it) }) {
    uiState.bgTextSizeSp = it
    }
    ParameterSlider("背景文字透明度", uiState.bgTextAlpha, 0.1f..1f, { "%.2f".format2(it) }) {
    uiState.bgTextAlpha = it
    }
    ParameterSlider("背景文字位置", uiState.bgTextPositionY, 0.2f..0.9f, { "%.2f".format2(it) }) {
    uiState.bgTextPositionY = it
    }
    ParameterSlider("背景文字旋转 (°)", uiState.bgTextRotationDegrees, -180f..180f, { "%.0f".format2(it) }) {
    uiState.bgTextRotationDegrees = it
    }

    // 背景图片：SAF 选择本地图片（进入 Backdrop 捕获层，穿过玻璃会折射）
    Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
    Button(
        onClick = { imagePicker.launch("image/*") },
        modifier = Modifier.weight(1f).semantics { contentDescription = "选择背景图片" }
    ) {
        Text(if (uiState.bgImage == null) "选择背景图片" else "更换图片", fontSize = 13.sp)
    }
    OutlinedButton(
        onClick = {
            uiState.resetBackgroundImage(context)
            applyBuiltinWallpaper(0, BuiltinWallpapers.first().second)
        },
        enabled = uiState.bgImage != null,
        modifier = Modifier.weight(1f).semantics { contentDescription = "恢复默认背景" }
    ) {
        Text("恢复默认背景", fontSize = 13.sp)
    }
    }
    if (uiState.bgImage != null) {
    ParameterSlider("图片缩放 (变焦)", uiState.bgImageZoom, 1f..3f, { "%.2f".format2(it) }) {
        uiState.bgImageZoom = it
        BackgroundImageStore.saveAdjustments(context, it, uiState.bgImageOffsetX, uiState.bgImageOffsetY)
    }
    ParameterSlider("图片水平偏移", uiState.bgImageOffsetX, -0.5f..0.5f, { "%.2f".format2(it) }) {
        uiState.bgImageOffsetX = it
        BackgroundImageStore.saveAdjustments(context, uiState.bgImageZoom, it, uiState.bgImageOffsetY)
    }
    ParameterSlider("图片垂直偏移", uiState.bgImageOffsetY, -0.5f..0.5f, { "%.2f".format2(it) }) {
        uiState.bgImageOffsetY = it
        BackgroundImageStore.saveAdjustments(context, uiState.bgImageZoom, uiState.bgImageOffsetX, it)
    }
    }

    // 【P57·画质行移除 + 固定最清晰档（默认开）· 本份 = 「外观设置」页块（仅旧档 panelLevelTabs=false
    //   的两页拆分下可见；新默认档走单页版，那份在 GlassPanelContent 里）】整节不进组合；
    //   一行回退 setSwitches qualityFixedBest 0 ⇒ 下面整节逐字回来 ✓
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    if (!DebugSwitches.qualityFixedBest) {
    SectionTitle("画质")
    Text("画质（数值越高越柔和、越费电）", fontSize = 11.sp, color = glassPalette.softCaption)
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassQuality.entries.forEach { quality ->
            PanelFilterChip(shape = PanelChipShape, selected = uiState.quality == quality,
                onClick = { uiState.quality = quality },
                label = {
                    Text("${quality.displayName} · ${quality.tapCount}t", fontSize = 13.sp)
                },
                colors = PanelChipColors,
                modifier = Modifier.semantics {
                    contentDescription = "画质档位：${quality.displayName}"
                }
            )
        }
    }
    }

    // 【P46·拆页】原「光学参数」节里的【外观项】留在本页（透明度 / 颜色类）——
    //   标题改为「外观参数」以免与高级页的「光学参数」混淆；控件本身逐字未改。
    SectionTitle("外观参数")

    // 宏观透明度：仅在预设模式下可调（CUSTOM 模式由下方独立参数完全控制）
    ParameterSlider(
        title = "玻璃透明度",
        value = params.glassTransparency,
        // 量程必须与映射窗口一致（GlassParameters.TRANSPARENCY_RANGE = 0.48..0.94，由预设派生）：
        // 映射对窗口外输入一律钳位；若滑杆仍用 0f..1f，则 0~0.48 与 0.94~1 全是死区 ✗（设置类缺陷②）
        range = GlassParameters.TRANSPARENCY_RANGE,
        enabled = uiState.preset != GlassPreset.CUSTOM,
        format = { "%.2f".format2(it) },
        onValueChange = { uiState.setTransparency(it) }
    )
    if (uiState.preset == GlassPreset.CUSTOM) {
        Text(
            "自定义模式：宏观透明度由下方参数独立控制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }

    ParameterSlider("背景透射率", params.backgroundTransmission, 0.5f..1f, { "%.3f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(backgroundTransmission = it) }
    }
    ParameterSlider("材质不透明度", params.materialOpacity, 0f..0.25f, { "%.3f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(materialOpacity = it) }
    }
    ParameterSlider("色调不透明度", params.tintOpacity, 0f..0.15f, { "%.3f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(tintOpacity = it) }
    }
    ParameterSlider("饱和度", params.saturation, 0.8f..1.2f, { "%.2f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(saturation = it) }
    }

    Spacer(Modifier.padding(bottom = 8.dp))
    // 【P46·底栏让位】玻璃底栏（64dp 高）悬浮在面板底边内侧 ⇒ 本页末尾留一段透明垫片，
    //   滚到底时最后一行停在【底栏上方】，不会钻到玻璃底栏下面（非控件、无文案）✓
    Spacer(Modifier.height(84.dp))
}

/** 【P46·高级设置页块（tab 1）】同上：在「更多设置」的滚动列内直接调用。 */
@Composable
private fun MoreSettingsAdvancedPage(
    uiState: GlassUiState,
    params: GlassParameters,
    onOpenLicenses: () -> Unit
) {
    // 调试日志（用户提议）：开关 + 一键导出到 /sdcard/Android/data/<pkg>/files/
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("记录调试日志（动画+手势）", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
        androidx.compose.material3.Switch(
            checked = uiState.debugLogEnabled,
            onCheckedChange = {
                uiState.debugLogEnabled = it
                com.example.liquidglass.debug.AppDebugLog.enabled = it
                com.example.liquidglass.debug.AppDebugLog.log("UI", if (it) "调试日志开启 session=" else "调试日志关闭")
            }
        )
    }
    // 注意：LocalContext 必须在组合上下文里取（不能在 clickable lambda 里取 ✗）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 【P19·分享调试日志】原「导出调试日志到文件」整行按钮 ⇒ 同一行两个动作（导出 + 分享）：
    //   行高 48dp 与改动前逐像素相同 ⇒ 零位移；iflashare=false 时内部逐字回退单整行按钮 ✓
    DebugLogActionsRow(ctx)
    Spacer(Modifier.height(6.dp))

    // 【P46·拆页】光学参数（用户点名：模糊 / 折射 / 色散 / 菲涅尔 / 边缘高光 / 局部暗化）——
    //   控件逐字未改；外观类（透明度 / 颜色）已移到「外观设置」页。
    SectionTitle("光学参数")

    val density = LocalDensity.current.density
    ParameterSlider("中心模糊 (dp)", params.blurRadiusDp, 0f..24f, { "%.1f".format2(it) },
        pxText = { " / ${"%.1f".format2(it * density)}px" }) {
        uiState.updateParameter { p -> p.copy(blurRadiusDp = it) }
    }
    ParameterSlider("边缘模糊 (dp)", params.edgeBlurRadiusDp, 0f..28f, { "%.1f".format2(it) },
        pxText = { " / ${"%.1f".format2(it * density)}px" }) {
        uiState.updateParameter { p -> p.copy(edgeBlurRadiusDp = it) }
    }
    // 量程必须覆盖预设值 32dp（三档预设共用）：原 0f..20f 显示越界、把手钉在末端、一拖就跳变 ✗
    ParameterSlider("折射强度 (dp)", params.refractionOffsetDp, 0f..48f, { "%.1f".format2(it) },
        pxText = { " / ${"%.1f".format2(it * density)}px" }) {
        uiState.updateParameter { p -> p.copy(refractionOffsetDp = it) }
    }
    ParameterSlider("色散偏移 (dp)", params.dispersionOffsetDp, 0f..1f, { "%.2f".format2(it) },
        pxText = { " / ${"%.2f".format2(it * density)}px" }) {
        uiState.updateParameter { p -> p.copy(dispersionOffsetDp = it) }
    }
    ParameterSlider("色散强度", params.dispersionStrength, 0f..0.5f, { "%.2f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(dispersionStrength = it) }
    }
    // 量程必须覆盖预设值 1.32（三档预设共用）：原 0f..1.2f 同上越界 ✗
    ParameterSlider("菲涅尔强度", params.fresnelStrength, 0f..1.6f, { "%.2f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(fresnelStrength = it) }
    }
    ParameterSlider("边缘高光", params.edgeHighlightOpacity, 0f..0.5f, { "%.3f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(edgeHighlightOpacity = it) }
    }
    ParameterSlider("边缘暗边", params.edgeShadowOpacity, 0f..0.4f, { "%.3f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(edgeShadowOpacity = it) }
    }
    ParameterSlider("局部暗化", params.localDimmingOpacity, 0f..0.16f, { "%.3f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(localDimmingOpacity = it) }
    }

    SectionTitle("形变与弹簧")
    ParameterSlider("形变强度", params.deformationStrength, 0f..1.5f, { "%.2f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(deformationStrength = it) }
    }
    ParameterSlider("弹簧刚度", params.springStiffness, 100f..900f, { "%.0f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(springStiffness = it) }
    }
    ParameterSlider("阻尼比", params.springDampingRatio, 0.2f..1.2f, { "%.2f".format2(it) }) {
        uiState.updateParameter { p -> p.copy(springDampingRatio = it) }
    }

    SectionTitle("调试模式（逐层排查光学管线）")
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassDebugMode.entries.forEach { mode ->
            PanelFilterChip(shape = PanelChipShape, selected = uiState.debugMode == mode,
                onClick = { uiState.debugMode = mode },
                label = { Text(mode.displayName, fontSize = 12.sp) },
                colors = PanelChipColors,
                modifier = Modifier.semantics {
                    contentDescription = "调试模式：${mode.displayName}"
                }
            )
        }
    }

    // ==================== 【P29】「关于」节（本页最底部）====================
    // 位置：继「调试模式」之后的最后一节（用户点名：更多设置【底部】新增一节「关于」）——
    //   · 入口未开放（开关 licensesPage=false）时整节不进组合 ⇒ 与改动前逐像素一致 ✓
    // 开关读取的订阅：先陪读一次 DebugBridge.revision（setSwitches 命令会自增它）⇒ adb 翻开关当场生效 ✓
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    if (DebugSwitches.licensesPage) {
        LicenseAboutSection(onOpenLicenses = onOpenLicenses)
    }

    Spacer(Modifier.padding(bottom = 8.dp))
    // 【P46·底栏让位】同外观页：给悬浮玻璃底栏留 84dp 透明垫片（滚到底不钻到玻璃底栏下面）✓
    Spacer(Modifier.height(84.dp))
}

/**
 * ==================== 二级菜单②「图片编辑」（官方 ACTION_EDIT）====================
 *
 * 用户点名未做的需求（PLAN.md P10：「官方图片编辑(ACTION_EDIT) + 二级菜单独立入口」）：
 * 把「背景壁纸」相关的【图片编辑类动作】收出来，做成一个【与「更多设置」同级】的独立二级菜单 ——
 * 同一个控制中心面板、同一套页切换机制（只换内容，不碰进度 p 与面板几何），
 * 因此进入/返回与面板动画完全一致 ✓（逐帧证据见验收：LGLayout/LGHandoff 时间线无异常跳变）。
 *
 * 页面内容 = 「背景壁纸」的图片编辑动作集：
 *   · 选择/更换图片（SAF GetContent，沿用既有 picker，不改选图语义）
 *   · 图片编辑…（【官方接口】`Intent(ACTION_EDIT)` + `setDataAndType(uri, "image/＊")`
 *     + `FLAG_GRANT_READ_URI_PERMISSION|WRITE` ⇒ 交给系统/第三方编辑器，裁切·旋转·滤镜随它）
 *   · 内置旋转 90°（内置简单操作：无编辑器时的回退动作，也可单独用）
 *   · 写入相册（MediaStore；同时是 ACTION_EDIT 的输入 URI 来源之一）
 *   · 恢复默认背景（与「更多设置」里的同名动作同一份状态）
 *   · 图片缩放 / 水平偏移 / 垂直偏移（与「更多设置」同一批状态，值互通）
 *
 * ⚠️ 与「更多设置」的关系：本页是【新增的独立入口】——原有「更多设置 · 背景内容」保持原样
 *   （验收要求：一级页与「更多设置」的内容/布局不得变 ✗）；两处控件绑定同一份 [GlassUiState]，
 *   改任一处另一处立即同步 ✓。开关 [DebugSwitches.imageEditMenu] 默认开，一行回退。
 */
@Composable
private fun ImageEditPage(
    uiState: GlassUiState,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onPickImage: () -> Unit,
    applyBuiltinWallpaper: (Int, Int) -> Unit
) {
    // 最近一次动作的结果：屏幕上可见（截图证据）+ 每条都进 AppDebugLog（logcat tag LGImageEdit）✓
    var lastOp by remember {
        mutableStateOf("就绪：选图 →「图片编辑…」交给系统编辑器；无编辑器时自动降级为内置简单操作（旋转 90°）")
    }

    SectionTitle("图片编辑")
    Text(
        "官方编辑器：ACTION_EDIT + image/*（裁切 / 旋转 / 滤镜随系统编辑器）；" +
            "本机没有编辑器时自动降级为内置简单操作（顺时针旋转 90°），不会崩。",
        fontSize = 11.sp,
        color = glassPalette.hintText
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPickImage,
            modifier = Modifier.weight(1f).semantics { contentDescription = "图片编辑：选择背景图片" }
        ) {
            Text(if (uiState.bgImage == null) "选择背景图片" else "更换图片", fontSize = 13.sp)
        }
        Button(
            onClick = { scope.launch { ImageEditOps.editOrDegrade(context, uiState) { lastOp = it } } },
            modifier = Modifier.weight(1f).semantics { contentDescription = "图片编辑：官方编辑器" }
        ) {
            Text("图片编辑…", fontSize = 13.sp)
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { scope.launch { ImageEditOps.rotateInApp(context, uiState) { lastOp = it } } },
            modifier = Modifier.weight(1f).semantics { contentDescription = "图片编辑：内置旋转 90°" }
        ) {
            Text("内置旋转 90°", fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = { scope.launch { lastOp = ImageEditOps.exportCurrent(context, uiState) } },
            modifier = Modifier.weight(1f).semantics { contentDescription = "图片编辑：写入相册" }
        ) {
            Text("写入相册", fontSize = 13.sp)
        }
    }

    // 【P46·整行按钮 ⇒ 上游 LiquidButton】文案/回调/enabled（禁用档 = 38% 不透明 + 点不动）逐字保留；
    //   一行回退见 PanelRowOutlinedButtonLegacy（= 改动前的 M3 OutlinedButton，逐字未动）✓
    PanelRowOutlinedButton(
        onClick = {
            uiState.resetBackgroundImage(context)
            applyBuiltinWallpaper(0, BuiltinWallpapers.first().second)
            lastOp = "已恢复默认背景（自选图片副本已清掉）"
        },
        contentDescription = "图片编辑：恢复默认背景",
        enabled = uiState.bgImage != null || uiState.bgBuiltinIndex < 0
    ) {
        Text("恢复默认背景", fontSize = 13.sp)
    }

    // 内置调整：与「更多设置 · 背景内容」里的三个滑块是同一批状态（值互通、持久化同一份）
    if (uiState.bgImage != null) {
        ParameterSlider("图片缩放 (变焦)", uiState.bgImageZoom, 1f..3f, { "%.2f".format2(it) }) {
            uiState.bgImageZoom = it
            BackgroundImageStore.saveAdjustments(context, it, uiState.bgImageOffsetX, uiState.bgImageOffsetY)
        }
        ParameterSlider("图片水平偏移", uiState.bgImageOffsetX, -0.5f..0.5f, { "%.2f".format2(it) }) {
            uiState.bgImageOffsetX = it
            BackgroundImageStore.saveAdjustments(context, uiState.bgImageZoom, it, uiState.bgImageOffsetY)
        }
        ParameterSlider("图片垂直偏移", uiState.bgImageOffsetY, -0.5f..0.5f, { "%.2f".format2(it) }) {
            uiState.bgImageOffsetY = it
            BackgroundImageStore.saveAdjustments(context, uiState.bgImageZoom, uiState.bgImageOffsetX, it)
        }
    }

    val bgInfo = "当前背景：" + (uiState.bgImage?.let { "位图 ${it.width}x${it.height}" } ?: "内置矢量风景") +
        (if (uiState.bgBuiltinIndex >= 0) "（内置壁纸 #${uiState.bgBuiltinIndex}）" else "（自选图片）") +
        (if (uiState.bgImageSourceUri != null) "；原图 URI 可用（编辑器直接编辑原图）"
         else "；无原图 URI（编辑前先写入相册取副本）")
    Text(bgInfo, fontSize = 11.sp, color = glassPalette.scheme.onSurfaceVariant)
    Text("最近操作：$lastOp", fontSize = 11.sp, color = glassPalette.scheme.primary)
}

/**
 * 【官方图片编辑（ACTION_EDIT）落地实现】—— 全部是「不抛」的：任何失败都返回一句可显示的状态文本，
 * 同一句同时写进 logcat（tag `LGImageEdit`）与应用内环形缓冲（tag `IMGEDIT`，dumpState/导出可见）✓
 *
 * 证据链（模拟器 emulator-5554 实测，2026-09-14）：
 *   · 有编辑器：`pm query-activities -a android.intent.action.EDIT -t image/＊` 返回
 *     `com.google.android.apps.photos/com.google.android.apps.photos.editor.intents.EditActivity`
 *     ⇒ `startActivity(ACTION_EDIT)` 拉起相册编辑器 ✓
 *   · 无编辑器：`pm disable-user com.google.android.apps.photos` 之后 startActivity 抛
 *     ActivityNotFoundException ⇒ 走 [degrade]：日志「无可用编辑器 ⇒ 已降级」+ 内置旋转 90° ✓
 */
private object ImageEditOps {

    private const val LOGCAT_TAG = "LGImageEdit"
    private const val APP_TAG = "IMGEDIT"

    fun log(msg: String) {
        android.util.Log.i(LOGCAT_TAG, msg)
        com.example.liquidglass.debug.AppDebugLog.log(APP_TAG, msg)
    }

    private fun toast(context: android.content.Context, msg: String) {
        try {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }

    private fun bitmapOf(uiState: GlassUiState): android.graphics.Bitmap? = try {
        uiState.bgImage?.asAndroidBitmap()
    } catch (t: Throwable) {
        log("取当前背景位图失败（${t.javaClass.simpleName}: ${t.message}）")
        null
    }

    /**
     * 把位图写进【相册】（MediaStore）。API 29+ 无需任何权限（本项目 minSdk 33 ✓），
     * 用 `IS_PENDING` 标记避免半成品被相册读到；任何失败删掉占位行并返回 null（不抛）。
     */
    fun exportToGallery(
        context: android.content.Context,
        bmp: android.graphics.Bitmap,
        namePrefix: String
    ): android.net.Uri? {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                namePrefix + "_" + System.currentTimeMillis() + ".png"
            )
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            log("写入相册：insert 失败（${t.javaClass.simpleName}: ${t.message}）")
            null
        }
        if (uri == null) {
            log("写入相册：insert 返回 null（MediaStore 不可用？）")
            return null
        }
        return try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                log("写入相册：openOutputStream 返回 null ⇒ 删除占位行")
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Throwable) {
                }
                null
            } else {
                out.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                try {
                    resolver.update(uri, values, null, null)
                } catch (_: Throwable) {
                }
                log("写入相册成功：$uri（${bmp.width}x${bmp.height}）")
                uri
            }
        } catch (t: Throwable) {
            log("写入相册失败（${t.javaClass.simpleName}: ${t.message}）⇒ 删除占位行")
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            null
        }
    }

    /** 官方编辑器意图：ACTION_EDIT + `setDataAndType(uri,"image/＊")` + 读/写授权标志 ✓ */
    private fun editIntent(uri: android.net.Uri, fromActivity: Boolean): android.content.Intent {
        val i = android.content.Intent(android.content.Intent.ACTION_EDIT)
            .setDataAndType(uri, "image/*")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // 非 Activity context 起 Activity 必须带 NEW_TASK（Compose 的 LocalContext 通常是 Activity，这里只是兜底）
        if (!fromActivity) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    /**
     * 解析编辑器（"pkg/name"），解析不到返回 null。
     * ⚠️ API 30+ 的包可见性会把未在 `<queries>` 声明的应用过滤掉（本项目不改 Manifest）⇒
     * 本函数只作【日志参考】，真正的判定交给 `startActivity` 的实测结果（catch ActivityNotFoundException）✓
     */
    private fun resolveEditor(context: android.content.Context, intent: android.content.Intent): String? =
        try {
            // ⚠️ 必须用 PackageManager.resolveActivity（返回 ResolveInfo）；Intent.resolveActivity 返回的是
            // ComponentName —— 两者混在一个 if/else 里会被推断成 Any? ⇒ `.activityInfo` 解析不了 ✗（已踩）
            val ri: android.content.pm.ResolveInfo? = context.packageManager
                .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.let { "${it.packageName}/${it.name}" }
        } catch (t: Throwable) {
            null
        }

    /** 把位图写成应用私有目录的背景副本（复用 BackgroundImageStore 的原子替换路径 ✓）。 */
    private fun persistCustom(context: android.content.Context, bmp: android.graphics.Bitmap): Boolean = try {
        val tmp = java.io.File(context.cacheDir, "lg_bg_edit_tmp.png")
        java.io.FileOutputStream(tmp).use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        val ok = BackgroundImageStore.copyFromUri(context, android.net.Uri.fromFile(tmp))
        tmp.delete()
        ok
    } catch (t: Throwable) {
        log("写入私有副本失败（${t.javaClass.simpleName}: ${t.message}）")
        false
    }

    /** 内置简单操作：顺时针旋转 90°（无编辑器时的回退动作）⇒ 画面 + 私有副本 + 持久化一起更新 ✓ */
    suspend fun applyInAppRotate(
        context: android.content.Context,
        uiState: GlassUiState,
        reason: String
    ): String {
        val bmp = bitmapOf(uiState) ?: return "当前背景不是位图（先『选择背景图片』）⇒ 内置旋转跳过"
        val rotated = withContext(Dispatchers.IO) {
            try {
                android.graphics.Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height,
                    android.graphics.Matrix().apply { postRotate(90f) }, true
                )
            } catch (t: Throwable) {
                log("内置旋转失败（${t.javaClass.simpleName}: ${t.message}）")
                null
            }
        }
        if (rotated == null) return "内置旋转失败（见日志 tag LGImageEdit）"
        val ok = withContext(Dispatchers.IO) { persistCustom(context, rotated) }
        uiState.bgImage = rotated.asImageBitmap()
        uiState.bgImageZoom = 1f
        uiState.bgImageOffsetX = 0f
        uiState.bgImageOffsetY = 0f
        BackgroundImageStore.saveAdjustments(context, 1f, 0f, 0f)
        uiState.selectCustomBackground(context)
        val msg = "内置旋转 90° 已应用（$reason；${bmp.width}x${bmp.height} → " +
            "${rotated.width}x${rotated.height}，持久化=$ok）"
        log(msg)
        return msg
    }

    /** 页面上的「内置旋转 90°」按钮（人工触发）。 */
    suspend fun rotateInApp(
        context: android.content.Context,
        uiState: GlassUiState,
        onStatus: (String) -> Unit
    ) {
        onStatus(applyInAppRotate(context, uiState, "用户点按"))
    }

    /** 页面上的「写入相册」按钮：把当前背景图导出到相册（也让 ACTION_EDIT 有输入 URI ✓）。 */
    suspend fun exportCurrent(context: android.content.Context, uiState: GlassUiState): String {
        val bmp = bitmapOf(uiState)
        if (bmp == null) {
            val m = "写入相册：当前背景不是位图（先选图或选壁纸）"
            log(m)
            return m
        }
        val uri = withContext(Dispatchers.IO) { exportToGallery(context, bmp, "lg_bg_save") }
        return if (uri != null) "已写入相册：$uri（${bmp.width}x${bmp.height}）"
        else "写入相册失败（见日志 tag LGImageEdit）"
    }

    /**
     * 点「图片编辑…」的完整流程（挂起函数，由页面在 rememberCoroutineScope 里启动）：
     *   ① 选输入 URI：本次 SAF 原图（content://，可直接转授）→ 否则 ② 写入相册取副本
     *   ③ 官方编辑器：ACTION_EDIT 意图 → 解析（仅日志）→ startActivity
     *        · 成功 ⇒ 状态行写「已交给系统编辑器：pkg/name」✓
     *        · 无处理者（ActivityNotFoundException）/ 任何 Throwable / 拿不到 URI
     *          ⇒ 【降级】：日志「无可用编辑器 ⇒ 已降级」+ Toast + 内置旋转 90°（绝不崩 ✓）
     */
    suspend fun editOrDegrade(
        context: android.content.Context,
        uiState: GlassUiState,
        onStatus: (String) -> Unit
    ) {
        val bmp = bitmapOf(uiState)
        if (bmp == null) {
            val msg = "当前背景不是可编辑的位图（先『选择背景图片』或选一张内置壁纸）"
            log("跳过：$msg")
            onStatus(msg)
            toast(context, msg)
            return
        }
        var uri = uiState.bgImageSourceUri
        var how = "本次选图的 SAF 原图"
        if (uri == null) {
            uri = withContext(Dispatchers.IO) { exportToGallery(context, bmp, "lg_bg_edit") }
            how = "写入相册的副本"
        }
        if (uri == null) {
            log("拿不到可交给编辑器的 content:// URI ⇒ 无可用编辑器 ⇒ 已降级（改用内置简单操作）")
            onStatus(degrade(context, uiState, "拿不到可外发的 content:// URI"))
            return
        }
        val intent = editIntent(uri, context is android.app.Activity)
        val editor = resolveEditor(context, intent)
        log(
            "图片编辑：ACTION_EDIT 输入=$uri（$how）；resolveActivity=" +
                (editor ?: "null（注意：API30+ 包可见性可能过滤；判定以 startActivity 实测为准）")
        )
        try {
            context.startActivity(intent)
            log("已启动系统编辑器：${editor ?: "（解析为 null，但 startActivity 成功 ⇒ 系统已找到处理者）"}")
            onStatus("已交给系统编辑器：${editor ?: "系统默认编辑器"}（输入＝$how）")
        } catch (e: android.content.ActivityNotFoundException) {
            log("无可用编辑器（ActivityNotFoundException: ${e.message}）⇒ 已降级")
            onStatus(degrade(context, uiState, "无可用编辑器"))
        } catch (t: Throwable) {
            log("启动编辑器失败（${t.javaClass.simpleName}: ${t.message}）⇒ 已降级")
            onStatus(degrade(context, uiState, "启动失败（${t.javaClass.simpleName}）"))
        }
    }

    /** 降级：日志 + Toast + 内置简单操作（顺时针旋转 90°），返回可显示的状态文本 ✓ */
    private suspend fun degrade(
        context: android.content.Context,
        uiState: GlassUiState,
        why: String
    ): String {
        val msg = applyInAppRotate(context, uiState, "降级原因：$why")
        val shown = "无可用编辑器 ⇒ 已降级（内置简单操作：顺时针旋转 90°）；$msg"
        log(shown)
        toast(context, "无可用编辑器 ⇒ 已降级：内置旋转 90°")
        return shown
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

/**
 * 【P45·真实控件 · 开关行】面板【真实在用】的开关行：默认走上游移植的 [LiquidToggle]，
 * 一行回退（`DebugSwitches.liquidRealControls = false`）走改动前的 [SwitchRowLegacy]（逐字未动）。
 *
 * 调用点【一行都没改】—— 改的是这里的分派 ⇒ 标题文案 / 行高 / 无障碍 / 状态绑定天然逐字保留 ✓
 * 采样源：`adapter.panelFillLayer`（= 面板填充层，控件脚下真正可见的材质；不含控件自身 ⇒ 无自引用），
 * 对照档 `liquidRealControlsSamplePanel=false` 可切回 `adapter.captureLayer`（P34 判负的那一档）。
 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val adapter = LocalGlassAdapter.current
    // @Volatile 开关不产生订阅 ⇒ 组合期读取时必须陪读一次 revision（setSwitches 会自增它），
    // 否则 adb 翻开关不重绘（本项目已两次踩坑）。
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    if (DebugSwitches.liquidRealControls && adapter != null) {
        SwitchRowLiquid(
            title = title,
            checked = checked,
            backdrop = if (DebugSwitches.liquidRealControlsSamplePanel) adapter.panelFillLayer
            else adapter.captureLayer,
            onCheckedChange = onCheckedChange
        )
    } else {
        SwitchRowLegacy(title, checked, onCheckedChange)
    }
}

@Composable
private fun SwitchRowLegacy(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFFFFFF),
                checkedTrackColor = glassPalette.chipSelectedContainer,
                uncheckedThumbColor = Color(0xFFFFFFFF),
                uncheckedTrackColor = glassPalette.toggleUncheckedTrack,
                uncheckedBorderColor = glassPalette.toggleUncheckedBorder
            ),
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

/**
 * 【P45·真实控件】开关行 —— 上游移植 [LiquidToggle] 版（默认档）。
 *
 * 换的东西：Material3 `Switch`（可视 52×32dp；官方 `minimumInteractiveComponentSize` 把占位撑到
 *   48dp 高）⇒ 上游 `LiquidToggle`（轨 64×28dp / 旋钮 40×24dp；未按 = 白胶囊，按住 = 非线性展开
 *   成跟随拖动形变的玻璃胶囊）。
 * 保留的东西（逐字不变）：
 *   · 标题文案 = 同一个 `title`；文本样式/字号/颜色沿用外层 `MaterialTheme`（未改）；
 *   · 行高：`size(64.dp, 48.dp)` = 旧 Switch 的实测占位高度 ⇒ 行高零位移 ✓
 *     （宽度按上游轨道取 64dp；`SpaceBetween` 下控件右边缘仍贴行右边缘，左侧文本不受影响 ✓）
 *   · 无障碍：`contentDescription = title` + `role = Role.Switch` + `toggleableState = checked`
 *     （与旧 Switch 的节点语义等价：可勾选 + 勾选态，uiautomator dump 的 checked 可回读 ✓）；
 *   · 状态绑定 / 回调：`selected = { checked }`、`onSelect = onCheckedChange`（同一个 (Boolean)->Unit）✓
 * 手感：不传即 `LiquidHandFeel.current()`（默认 = 本工程基准；`liquidDampingUpstream` 一行切上游原值）
 *   —— ✗ 不在这里另搬一份上游硬编码弹簧。
 */
@Composable
private fun SwitchRowLiquid(
    title: String,
    checked: Boolean,
    backdrop: Backdrop,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp)
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            backdrop = backdrop,
            modifier = Modifier
                // 容器 = 64×48dp：48 = 旧 Switch 的占位高（最小交互尺寸）⇒ 行高不变；
                // 水平 Start 对齐（与上游 Toggle 自己的 Box 对齐方式一致）⇒ 旋钮行程 2dp..22dp 不变 ✓
                .size(width = 64.dp, height = 48.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = title
                    role = Role.Switch
                    toggleableState = ToggleableState(checked)
                },
            // 【取证】非空即接入逐帧打点（总开关 DebugSwitches.liquidHandFeelTrace，默认关 = 零日志）
            traceTag = "PanelSwitch#$title"
        )
    }
}

/**
 * 玻璃拉杆（Glassy Slider）
 *
 * 视觉/交互参考 iOS 26 的液态玻璃控件：轨道是一条被磨出的半透明玻璃凹槽，
 * 滑块是一颗玻璃珠——按下时被指腹"压扁"（垂直压缩明显多于水平展开），
 * 松手用低阻尼弹簧回弹；珠子自带边缘亮线、内部径向高光与底部反光，
 * 因此读起来是一块会形变的玻璃，而不是一个实心圆点。
 *
 * 交互自绘（不用 Material Slider）：按住即可拖动、点哪跳哪，
 * 全程只更新数值状态，形变在绘制层完成，不触发面板重排。
 *
 * 【P45】以上描述的是【回退档】那套旧实现 —— 它现在叫 `ParameterSliderLegacy`（逐字未动）；
 *   默认档（`DebugSwitches.liquidRealControls=true`）走下面的分派 → `ParameterSliderLiquid`。
 */
/**
 * 【P45·真实控件 · 滑杆】面板【真实在用】的参数滑杆：默认走上游移植的 [LiquidSlider]，
 * 一行回退（`DebugSwitches.liquidRealControls = false`）走改动前的 [ParameterSliderLegacy]（逐字未动）。
 *
 * 调用点【一行都没改】（30 处：一级页 / 更多设置 / 图片编辑三页）—— 改的是这里的分派
 * ⇒ 标题文案 / 值域 / 格式化串 / pxText / enabled / 回调天然逐字保留 ✓
 * 采样源同 [SwitchRow]（`adapter.panelFillLayer` = 控件脚下真正可见的材质，不含控件自身）。
 */
@Composable
private fun ParameterSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    enabled: Boolean = true,
    pxText: (Float) -> String? = { null },
    onValueChange: (Float) -> Unit
) {
    val adapter = LocalGlassAdapter.current
    // @Volatile 开关不产生订阅 ⇒ 组合期读取时必须陪读一次 revision，否则 adb 翻开关不重绘。
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    if (DebugSwitches.liquidRealControls && adapter != null) {
        ParameterSliderLiquid(
            title, value, range, format, enabled, pxText,
            backdrop = if (DebugSwitches.liquidRealControlsSamplePanel) adapter.panelFillLayer
            else adapter.captureLayer,
            onValueChange = onValueChange
        )
    } else {
        ParameterSliderLegacy(title, value, range, format, enabled, pxText, onValueChange)
    }
}

/** 【P45·回退档】改动前的「玻璃拉杆」实现（自绘轨道 + 66×34dp 白色胶囊手柄）。逐字未动 ✓ */
@Composable
private fun ParameterSliderLegacy(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    enabled: Boolean = true,
    /** 附加的 px 显示（如 "/ 25.2px"），用于核对 dp→px 换算。 */
    pxText: (Float) -> String? = { null },
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
            Text(
                format(value) + (pxText(value) ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp
            )
        }

        // iOS 26 滑杆式横向胶囊滑块：66×34dp（手指接触面约 23dp，所以玻璃始终比手大）
        val thumbSizePx = with(LocalDensity.current) { 66.dp.toPx() }
        val thumbHeightPx = with(LocalDensity.current) { 34.dp.toPx() }
        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        var pressed by remember { mutableStateOf(false) }

        // 按压形变：1 = 被压扁（垂直压缩 > 水平展开），弹簧回弹
        // 保留 State 对象：手柄光晕要在 draw 阶段按最新值绘制（避免每帧重组）
        // 用户拍板：滑块按压【完全不做动画】——静态白色胶囊，形状/颜色/透明度/位置都不变。
        // 原 animateFloatAsState(spring) 已移除（含过冲/回弹）；squash 恒 0。
        val squash = 0f

        val fraction = if (range.endInclusive > range.start) {
            ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        } else {
            0f
        }

        // 玻璃滑杆手柄的"悬浮感"：淡白晕（暗背景可见）+ 极淡深阴影（亮背景可见）。
        // 用户明确指出这个光晕属于【滑杆手柄】，不是控制中心按钮。
        val thumbWRestPx = with(LocalDensity.current) { 66.dp.toPx() }
        val thumbHRestPx = with(LocalDensity.current) { 34.dp.toPx() }
        val haloDensity = LocalDensity.current.density

        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)      // 容纳放大后的玻璃手柄（按下 56dp）
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .drawBehind {
                    val sMix = 0f   // 按压不做动画：光晕保持静态
                    val tw = thumbWRestPx * (1f + 0.76f * sMix)
                    val th = thumbHRestPx * (1f + 0.65f * sMix)
                    val cxp = thumbWRestPx / 2f + fraction * (size.width - thumbWRestPx)
                    val cyp = size.height / 2f
                    // 减档：5 层 α6% → 3 层 α2.2%（用户反馈"阴影层级太明显、不自然"）
                    for (k in 1..3) {
                        val off = k * 1.7f * haloDensity * (1f + 0.35f * sMix)
                        drawRoundRect(
                            color = Color(0xFF091120).copy(alpha = 0.022f * (1f - (k - 1f) / 3f)),
                            topLeft = Offset(cxp - tw / 2f - off, cyp - th / 2f - off * 0.8f),
                            size = Size(tw + off * 2f, th + off * 1.6f),
                            cornerRadius = CornerRadius(th / 2f + off, th / 2f + off),
                            style = Stroke(width = off)
                        )
                    }
                    for (k in 1..2) {
                        val off = (3 + k) * 1.7f * haloDensity * (1f + 0.35f * sMix)
                        drawRoundRect(
                            color = Color(0xFFFFFFFF).copy(alpha = 0.022f * (1f - (k - 1f) / 2f)),
                            topLeft = Offset(cxp - tw / 2f - off, cyp - th / 2f - off * 0.85f),
                            size = Size(tw + off * 2f, th + off * 1.7f),
                            cornerRadius = CornerRadius(th / 2f + off, th / 2f + off),
                            style = Stroke(width = off * 0.5f)
                        )
                    }
                }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    val usable = (trackWidthPx - thumbSizePx).coerceAtLeast(1f)
                    fun update(x: Float) {
                        val f = ((x - thumbSizePx / 2f) / usable).coerceIn(0f, 1f)
                        onValueChange(range.start + f * (range.endInclusive - range.start))
                    }
                    awaitEachGesture {
                        // 防误触（用户反馈：面板内滑杆太容易误触）：
                        // 按下【不再立即取值】，先累积位移判定方向 —— 横向才接管滑杆，
                        // 纵向则完全不消费事件、交还给面板滚动。
                        val down = awaitFirstDown()
                        pressed = true
                        var started = false
                        var acc = androidx.compose.ui.geometry.Offset.Zero
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) {
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change == null) break
                            if (!started) {
                                acc += change.position - change.previousPosition   // 手动求位移（positionChange() API 版本差异）
                                if (acc.getDistance() > 14f) {
                                    if (kotlin.math.abs(acc.x) <= kotlin.math.abs(acc.y)) {
                                        pressed = false
                                        return@awaitEachGesture     // 纵向 → 让给滚动
                                    }
                                    started = true                  // 横向 → 接管
                                    update(change.position.x)
                                    change.consume()
                                }
                            } else {
                                update(change.position.x)
                                change.consume()
                            }
                            event = awaitPointerEvent()
                        }
                        pressed = false
                    }
                }
                .semantics {
                    contentDescription = title
                    stateDescription = format(value)
                }
        ) {
            // ===== 按下/拖动：不再叠加任何玻璃手柄 =====
            // 用户看过效果后拍板：滑块按压不做任何视觉变化，保持静态白色胶囊即可。
            // 原"真玻璃手柄"整块（放大 1.76×、折射/色散放大、按压形变）已全部移除；
            // 轨道绘制与拖拽逻辑不受影响。

            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val trackH = 10.dp.toPx()
                val r = trackH / 2f
                val left = thumbSizePx / 2f
                val right = size.width - thumbSizePx / 2f
                val cx = left + (right - left) * fraction

                // 凹槽：半透明深底 + 上半高光 + 亮边 —— 玻璃被磨出的一条沟槽
                drawRoundRect(
                    color = Color(0x2E121D33),
                    topLeft = Offset(left - r, cy - r),
                    size = Size((right - left) + trackH, trackH),
                    cornerRadius = CornerRadius(r, r)
                )
                drawRoundRect(
                    color = Color(0x1AFFFFFF),
                    topLeft = Offset(left - r, cy - r),
                    size = Size((right - left) + trackH, trackH * 0.5f),
                    cornerRadius = CornerRadius(r * 0.9f, r * 0.9f)
                )
                drawRoundRect(
                    color = Color(0x40121D33),
                    topLeft = Offset(left - r, cy - r),
                    size = Size((right - left) + trackH, trackH),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.dp.toPx())
                )
                // 已选段：亮蓝玻璃
                if (cx > left + 0.5f) {
                    drawRoundRect(
                        color = if (enabled) Color(0xFF3C74C8) else Color(0x553C74C8),
                        topLeft = Offset(left - r, cy - r),
                        size = Size(cx - left + trackH, trackH),
                        cornerRadius = CornerRadius(r, r)
                    )
                }

                // 滑块：静止 = 纯色微透明白椭圆；按下/拖动 = 液态玻璃珠
                // （用户要求：白色椭圆底，按下去变玻璃；两者按按压进度交叉过渡）
                val beadW = thumbSizePx * 0.96f
                val beadH = thumbHeightPx * 0.92f
                val center = Offset(cx, cy)
                val glassMix = 0f   // 按压不做动画：手柄外观恒定
                val beadR = beadH / 2f
                val tl = Offset(center.x - beadW / 2f, center.y - beadH / 2f)
                val sz = Size(beadW, beadH)
                withTransform({
                    scale(1f + 0.06f * glassMix, 1f - 0.16f * glassMix, pivot = center)
                }) {
                    // 静止态：纯色微透明白【横向胶囊】（原来是小圆珠，比手指还小）
                    drawRoundRect(
                        color = Color(0xFFFFFFFF).copy(alpha = 0.98f * (1f - glassMix)),
                        topLeft = tl, size = sz, cornerRadius = CornerRadius(beadR, beadR)
                    )
                    // （原此处有一圈深色描边，用户要求去掉：手柄只靠悬浮光晕与面板分离）
                    if (glassMix > 0.02f) {
                        val a = glassMix
                        // 按下/拖动态：胶囊玻璃——水平渐变（左亮→中透→右暗）
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xE6FFFFFF).copy(alpha = 0.90f * a),
                                    Color(0x77C6DBFF).copy(alpha = 0.42f * a),
                                    Color(0x40212C46).copy(alpha = 0.30f * a)
                                ),
                                start = Offset(tl.x, center.y),
                                end = Offset(tl.x + beadW, center.y)
                            ),
                            topLeft = tl, size = sz, cornerRadius = CornerRadius(beadR, beadR)
                        )
                        // 边缘亮线（玻璃厚度感）
                        drawRoundRect(
                            color = Color(0xB3FFFFFF).copy(alpha = 0.72f * a),
                            topLeft = tl, size = sz, cornerRadius = CornerRadius(beadR, beadR),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                        // 左端内高光（原先是左上角小圆点，胶囊上改为左端亮块）
                        drawRoundRect(
                            color = Color(0xF2FFFFFF).copy(alpha = 0.92f * a),
                            topLeft = Offset(tl.x + beadW * 0.14f, tl.y + beadH * 0.20f),
                            size = Size(beadW * 0.24f, beadH * 0.26f),
                            cornerRadius = CornerRadius(beadH * 0.13f, beadH * 0.13f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 【P45·真实控件】参数滑杆 —— 上游移植 [LiquidSlider] 版（默认档）。
 *
 * 换的东西：自绘「玻璃拉杆」（10dp 凹槽 + 66×34dp 白胶囊手柄、按压恒定不变形）
 *   ⇒ 上游 `LiquidSlider`（轨 6dp Capsule + 40×24dp 旋钮；未按 = 微透明椭圆，按住 = 非线性展开
 *   成玻璃胶囊，折射/色散/Ambient 高光/内阴影随按压进度淡入）。
 * 保留的东西（逐字不变）：
 *   · 头部（标题 + 数值 + pxText）整行【逐字照搬旧实现】⇒ 文案 / 数值格式串 / px 提示零变化 ✓
 *   · 值域 `range`、回调 `onValueChange`、`enabled` 语义（禁用档写入被拦下）；
 *   · 行高：轨道容器仍是 `fillMaxWidth().height(64.dp)`（与旧手柄容器同高）⇒ 面板行高零位移 ✓
 *     （LiquidSlider 自身只有 24dp 高，在 64dp 容器内垂直居中）；
 *   · 无障碍：`contentDescription = title` + `stateDescription = format(value)`（与旧实现一字不差）✓
 * 手感：不传即 `LiquidHandFeel.current()`（默认 = 本工程基准），✗ 不另搬上游硬编码弹簧。
 * 步进/精度：`visibilityThreshold` = 值域的 0.1%（弹簧可见性阈值，不是步进）—— 与演示页的
 *   固定 0.01 不同，因为本面板的滑杆值域跨 0.15~1800（0.01 在 100..900 的量程上会退化成"无数步"）。
 */
@Composable
private fun ParameterSliderLiquid(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    enabled: Boolean,
    pxText: (Float) -> String?,
    backdrop: Backdrop,
    onValueChange: (Float) -> Unit
) {
    Column {
        // ===== 【P45 关键修复】值回灌必须走【快照状态】，且写入要【即刻】生效 =====
        // LiquidSlider 内部用 `LaunchedEffect(dampedDragAnimation) { snapshotFlow { value() } }` 把外部状态
        // 回灌成内部值（该 effect 的键不含 value ⇒ 捕获的是首次组合那个 lambda），而它的拖动模型是
        // **增量式**的：每次 onDrag 写入 `targetValue + delta`。于是"写入 → snapshotFlow 重发 →
        // updateValue 推进 targetValue"这条回路必须是【每次事件前都已完成】的，否则中间事件的增量会被
        // 下一次写入覆盖掉 ⇒ 拖动只走出 1/N 的位移。
        //  · 若 value() 直接返回一个普通 Float 参数（本面板调用点给的就是 `params.x` / `uiState.x` 这类
        //    一次性读数）⇒ 读不到任何快照状态 ⇒ flow 永不重发 ⇒ targetValue 恒为旧值
        //    （实测：拖 296px，value/target 恒 0.3500，卡片尺寸 0 变化 ✗）；
        //  · 若只把参数包成 rememberUpdatedState ⇒ 那个 State 要等【重组】才写入 ⇒ 回路慢于一帧、
        //    事件快时增量被覆盖（实测：拖 273px 只走出 38%，值 0.35→0.3317，滞后 -168px ✗）；
        //  · 正解（= 演示页那条通路的写法）= 一个【本地镜像 State】：onValueChange 里先写镜像（同一快照
        //    即刻可见）再往外写 ⇒ snapshotFlow 立即重发 ⇒ targetValue 事件级推进 ✓（实测拖 273px、
        //    卡片 644x1057→610x1002 逐像素变化 ✓）；外部改值（预设/复位/另一处写入）再同步回镜像。
        val mirrored = remember { mutableFloatStateOf(value) }
        LaunchedEffect(value) { mirrored.floatValue = value }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
            Text(
                format(value) + (pxText(value) ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp
            )
        }

        // 轨道容器：与旧实现同高（64dp）⇒ 面板行高零位移 ✓
        // 语义（contentDescription / stateDescription）挂在【容器】上：节点 bounds 与旧实现逐字相同
        // （[64,339][1776,467] = 64dp 高的整行轨道区）⇒ 无障碍触控目标大小不变 ✓
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .semantics {
                    contentDescription = title
                    stateDescription = format(value)
                },
            contentAlignment = Alignment.Center
        ) {
            LiquidSlider(
                value = { mirrored.floatValue },
                // enabled=false 时把写入拦下（旧实现里是指针手势直接不接管）—— 视觉与手势仍在新组件上，
                // 但【值不会变】⇒ 语义等价（本面板只有「玻璃风格 / 玻璃透明度」在 CUSTOM 档下禁用）
                onValueChange = { v ->
                    // 先写镜像（快照即刻可见 ⇒ 内部回灌回路每事件都推进），再往外写（真正的状态源）
                    mirrored.floatValue = v
                    if (enabled) onValueChange(v)
                },
                valueRange = range,
                visibilityThreshold = ((range.endInclusive - range.start) * 0.001f)
                    .coerceAtLeast(1e-6f),
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                // 【取证】非空即接入逐帧打点（总开关 DebugSwitches.liquidHandFeelTrace，默认关 = 零日志）
                traceTag = "PanelSlider#$title"
            )
        }
    }
}

private fun String.format2(value: Float): String =
    String.format(Locale.US, this, value)

// 【深色/亮色模式·2026-09-24】面板配色不再固定浅色：改为【当前调色板驱动】——
//   GlassPanelColorScheme / PanelChipColors 已移到 ui/GlassTheme.kt（亮色档 = 下面这组取值逐字冻结；
//   深色档 = DarkGlassPalette 新定的一套）。面板浮在任意背景之上这一点没变，只是现在有【两套】
//   各自内部高对比的固定配色，由「更多设置 › 外观设置 › 外观」的三档开关选择（默认跟随系统）。
//   一行回退：DebugSwitches.themeAutoSwitch = false ⇒ 恒亮色档（= 改动前行为 ✓）。

// ============================================================================================
// 【P46·面板 chips / 整行按钮 换上游 LiquidButton（批 3：接 P45 的两个真实控件之后）】
//
// 换的东西：面板里【全部 FilterChip 调用点】（玻璃风格预设 / 选中卡 / 玻璃形状 / 背景壁纸 /
//   自定义图片 / 图片编辑 › / 组件演示 › / 钻石 · 3D 演示入口 chip / 同屏块数 / 画质 / 调试模式；
//   其中画质与调试模式在「外观设置」「高级设置」两页各有一份、单页回退档另有一份）
//   ＋【全部整行按钮】（恢复默认设置 / 导出调试日志到文件 / 更多设置 / 图片编辑页的恢复默认背景）
//   ⇒ 上游移植的 [LiquidButton]。
//
// 保留的东西（逐字，见每个调用点的 diff）：
//   · 文案：调用点的 `label = { Text(...) }` / `Text("恢复默认设置")` 等字符串与字号一字未改；
//   · 选中态：`selected = <同一个表达式>`（选中色也仍然取自同一个 PanelChipColors 的
//     containerColor / selectedContainerColor / labelColor / selectedLabelColor 四个取值）；
//   · 回调：`onClick = { … }` 的 lambda 体一字未改（含 Toast / 复位 / 壁纸解码等副作用）；
//   · 几何（关键）：旧控件的【占位】本就与上游 LiquidButton 的默认形态逐字对位 ——
//     FilterChip 视觉 32dp 但占位 = Material 最小交互尺寸 48dp（uiautomator 实测 clickable 节点
//     96px@2x = 48dp）；整行 Box 行与 M3 Button 行同样是 96px = 48dp ⇒ 新控件【一行都不动布局】，
//     行高/行距/每一行 y 坐标逐字不变（验收：换前/换后 uiautomator bounds 对照）。
//   · 无障碍：调用点自带的 `contentDescription` 语义原样保留（另 mergeDescendants 让节点合并成一个，
//     与旧 chip 的单节点行为一致）；chips 的 `✓` trailingIcon 仍作为独立节点渲染（uiautomator 可回读）。
//
// 一行回退（默认开）：`DebugSwitches.liquidRealButtons = false`
//   ⇒ 三个分派全部走 `*Legacy`（= 改动前的实现，逐字未动）⇒ 与改动前逐像素一致 ✓
//   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
//       --es name liquidRealButtons --ei value 0 -p com.liqglass.ultraclear
//
// 采样源：与 P45 的两个真实控件同源 —— `adapter.panelFillLayer`（= 控件脚下真正可见的面板填充层，
//   不含控件自身 ⇒ 无自引用）。⚠ 该层只在 P45 总开关 `liquidRealControls` 打开时被逐帧录制
//   （LiquidGlassScreen 的填充 Box 上挂 layerBackdrop），故这里一并判断；关着时退化为
//   `adapter.captureLayer`（整页背景捕获层，P34 判负档）而不是采样一个不再更新的空层。
// 【范围】✗ 不动二级页拆分（P46 的「外观设置 / 高级设置」两页布局原样保留，本层只在两页内换控件）、
//   ✗ 不动底栏（LiquidBottomTabs 在屏幕层）、✗ 不动面板几何与胶囊交棒、✗ 不动
//   半行 M3 按钮（选择背景图片 / 更换图片 / 图片编辑… / 内置旋转 / 写入相册 —— 它们是 2 列半行，
//   不在“整行按钮”范围内，保持原样以免一列玻璃一列 Material 更割裂）。
// ============================================================================================

/** 【P46】面板内新建控件（LiquidButton 档）的采样源：面板填充层优先，见上方注释。 */
private fun panelButtonBackdrop(adapter: BackdropAdapter): Backdrop =
    if (DebugSwitches.liquidRealControls && DebugSwitches.liquidRealControlsSamplePanel) adapter.panelFillLayer
    else adapter.captureLayer

/**
 * 【P46·chips】面板里所有 FilterChip 的统一入口。
 * 调用点只改了一个标识符（FilterChip → PanelFilterChip），其余形参/实参【一字未动】。
 *
 * 分派：`DebugSwitches.liquidRealButtons`（默认开）
 *   true  → [PanelFilterChipLiquid]：上游 [LiquidButton]（默认 48dp 高 —— 与旧 chip 的 48dp 占位
 *           逐字对位，不另造“紧凑形态”）。
 *   false → [PanelFilterChipLegacy]：改动前的 Material3 [FilterChip] 调用（逐字未动）✓
 */
@Composable
private fun PanelFilterChip(
    shape: Shape,
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    /** 默认 = M3 FilterChip 自己的默认色（`FilterChipDefaults.filterChipColors()`，与旧调用点逐字一致：
     *  面板里「玻璃形状」那一行【没有传 colors】⇒ 这里必须保留同一个默认值，否则回退档会变色 ✗）。 */
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val adapter = LocalGlassAdapter.current
    // @Volatile 开关不产生订阅 ⇒ 组合期读取时必须陪读一次 revision（setSwitches 会自增它），
    // 否则 adb 翻开关不重绘（与 SwitchRow / ParameterSlider 同一套纪律）。
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    if (DebugSwitches.liquidRealButtons && adapter != null) {
        PanelFilterChipLiquid(
            selected = selected,
            onClick = onClick,
            label = label,
            colors = colors,
            modifier = modifier,
            trailingIcon = trailingIcon,
            backdrop = panelButtonBackdrop(adapter)
        )
    } else {
        PanelFilterChipLegacy(shape, selected, onClick, label, colors, modifier, trailingIcon)
    }
}

/** 【P46·回退档】改动前的 FilterChip 调用（逐字未动 ✓）。 */
@Composable
private fun PanelFilterChipLegacy(
    shape: Shape,
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    FilterChip(
        shape = shape,
        selected = selected,
        onClick = onClick,
        label = label,
        colors = colors,
        modifier = modifier,
        trailingIcon = trailingIcon
    )
}

/**
 * 【P46·Liquid 档的四色】旧 chip 的 `SelectableChipColors` 在 M3 1.4.0 里字段全私有
 * （`containerColor(enabled, selected)` 还是 `$material3` internal，外部读不到 ✗）——因此这里用
 * 【与旧渲染逐值同源】的方式还原这四个实色：
 *
 * ① `colors == PanelChipColors`（值相等，`SelectableChipColors` 有 equals —— 已核对 1.4.0 字节码）
 *    ⇒ 直接取本文件 PanelChipColors 的四个构造常量（逐字同源 ✓）；
 * ② 否则 = M3 的默认 chip 色（面板里只有「玻璃形状」那一行用默认色），按 M3 token 的色板角色取：
 *    未选 container = Transparent、label = onSurfaceVariant；选中 container = secondaryContainer、
 *    label = onSecondaryContainer —— 与面板 GlassPanelColorScheme（onSurfaceVariant=0x23304A、
 *    secondaryContainer=0x1B4A8F、onSecondaryContainer=0xFFFFFF）逐色一致，且已用【换前截图反推】验证：
 *    形状 chip 选中像素 =(27,74,143)=0x1B4A8F、未选内部 =(184,189,197)=面板底色(container=Transparent)、
 *    未选文字 =(35,48,74)=0x23304A ✓（见交付报告对照图）。
 */
private data class PanelChipLiquidColors(
    val containerColor: Color,
    val selectedContainerColor: Color,
    val labelColor: Color,
    val selectedLabelColor: Color
)

@Composable
private fun panelChipLiquidColors(colors: SelectableChipColors): PanelChipLiquidColors {
    val scheme = MaterialTheme.colorScheme
    return if (colors == PanelChipColors) {
        PanelChipLiquidColors(
            containerColor = Color(0x140E1626),
            selectedContainerColor = Color(0xFF1B4A8F),
            labelColor = Color(0xFF12213D),
            selectedLabelColor = Color(0xFFFFFFFF)
        )
    } else {
        PanelChipLiquidColors(
            containerColor = Color.Transparent,
            selectedContainerColor = scheme.secondaryContainer,
            labelColor = scheme.onSurfaceVariant,
            selectedLabelColor = scheme.onSecondaryContainer
        )
    }
}

/**
 * 【P46·chips】上游 [LiquidButton] 档。
 *
 * 选中态映射（取值与旧 chip 完全同源，不改任何颜色常量）：
 *   · 底色：selected ? colors.selectedContainerColor(0xFF1B4A8F) : colors.containerColor(0x140E1626)
 *     → 上游按钮的 `surfaceColor`（在胶囊形状内绘制 ⇒ 与旧 chip 的底色逐色相同）；
 *   · 文字：selected ? colors.selectedLabelColor(#FFFFFF) : colors.labelColor(#12213D)
 *     → 通过 LocalContentColor 注入（与 M3 chip 给 label 注入颜色的方式一致）；
 *   · trailingIcon（如「✓」）仍逐字渲染，位置从 chip 的尾随槽变成按钮 Row 的第二个子项
 *     （水平居中布局 ⇒ 仍贴标签右侧，间距 = 上游 `spacedBy(8.dp)`）。
 *
 * `traceTag`：按压逐帧打点（仅在 `DebugSwitches.liquidHandFeelTrace` 打开时才有日志，默认零开销）。
 */
@Composable
private fun PanelFilterChipLiquid(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    colors: SelectableChipColors,
    modifier: Modifier,
    trailingIcon: (@Composable () -> Unit)?,
    backdrop: Backdrop
) {
    val palette = panelChipLiquidColors(colors)
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.semantics(mergeDescendants = true) { role = Role.Button },
        surfaceColor = if (selected) palette.selectedContainerColor else palette.containerColor,
        // 【P54·点按放大安全区】默认开：chips 的按压放大同样钳到 0 越界（面板里没有给放大留余量，
        // 放大必然压到内容区/面板边界上）⇒ 溢出/被裁像素 = 0；一行回退关 DebugSwitches.liquidPressSafeArea。
        pressContained = DebugSwitches.liquidPressSafeArea,
        traceTag = "PanelChip"
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) palette.selectedLabelColor else palette.labelColor
        ) { label() }
        trailingIcon?.invoke()
    }
}

/**
 * 【P46·整行按钮】面板里【整行】动作按钮的统一入口（Box 形态那几行）：
 *   恢复默认设置（外观设置页 tab0 / 单页回退档）/ 导出调试日志到文件（高级设置页 tab1 / 单页回退档）。
 *
 * 分派：`DebugSwitches.liquidRealButtons`（默认开）
 *   true  → [LiquidButton]（默认 48dp 高 = 旧 Box 行的实测占位 96px@2x ⇒ 零位移）；
 *   false → [PanelRowButtonLegacy]：改动前的 Box 行（背景 / then(panelEdgeAa) / clip / clickable /
 *           padding(vertical = 10.dp) / contentAlignment = Center【逐字未动】）✓
 * 文案与回调由调用点原样传入（`content` / `onClick`），本函数不碰 ✓
 */
@Composable
private fun PanelRowButton(
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val adapter = LocalGlassAdapter.current
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    if (DebugSwitches.liquidRealButtons && adapter != null) {
        LiquidButton(
            onClick = onClick,
            backdrop = panelButtonBackdrop(adapter),
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
                    } else Modifier
                ),
            isInteractive = true,
            // 【P54·点按放大安全区】整行按钮是面板里最宽的控件（1712px）⇒ 上游放大单侧 +71px，
            // 溢出内容区并撞面板边界；默认档钳到 0 越界（尺寸/位置/轮廓一字未改）。一行回退见开关注释。
            pressContained = DebugSwitches.liquidPressSafeArea,
            traceTag = "PanelRowButton",
            content = { content() }
        )
    } else {
        PanelRowButtonLegacy(onClick, contentDescription, modifier, content)
    }
}

/** 【P46·回退档】改动前的整行 Box 按钮（逐字未动 ✓，多处调用点原来共用同一段代码）。 */
@Composable
private fun PanelRowButtonLegacy(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            // 【边缘抗锯齿】背景改成【按形状绘制】（Skia 路径绘制自带 AA ✓），
            // clip 留在其后、只负责裁 ripple/内容 —— 旧顺序（clip 在前 + 矩形背景）会让
            // 背景边界 100% 由无 AA 的路径裁剪给出（9dp 小半径弧线上就是阶梯 ✗）。
            // 回退：DebugSwitches.panelEdgeAa = false → 回到"clip 在前 + 矩形背景"。
            .then(
                if (DebugSwitches.panelEdgeAa) {
                    Modifier.background(Color(0x1A0E1626), shape = PanelChipShape)
                } else {
                    Modifier.background(Color(0x1A0E1626))
                }
            )
            .clip(PanelChipShape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * 【P19·分享调试日志】「导出调试日志到文件」+「分享调试日志」= 面板里【同一行】的两个动作。
 *
 * 为什么合并成一行（主控给出的两个允许形态之一，另一个是"旁边新增一行"）：
 *   · 两个动作各自的占位都是 48dp（[PanelRowButton] 默认档 = 上游 LiquidButton 的默认高；
 *     回退档 = 旧 Box 行实测 96px@2x 同样 48dp）⇒ 本行【总高与改动前逐像素相同】⇒
 *     面板标题 / 行高 / 其它按钮【零位移】✓（新增一整行会把下方所有元素推下去 ✗）；
 *   · 文案、字号（13sp）、颜色、无障碍语义与改动前逐字一致，只有宽度从"整行"变"半行"。
 *
 * 行为：
 *   · 「导出调试日志到文件」= 改动前原回调，一字未改（导出到 app 私有外部目录 + Toast 实际路径）；
 *   · 「分享调试日志」= [com.example.liquidglass.debug.AppDebugLog.share]：
 *     先跑一次现有导出 → FileProvider 的 content:// URI → 系统分享面板
 *     （ACTION_SEND + text/plain + createChooser，标题带 App 名）；
 *     无接收方 / 取 URI 失败 / 拉起失败 ⇒【优雅降级】回退到导出并在 Toast 上显示实际路径（tag LGShare）。
 *
 * 回退：`DebugSwitches.iflashare = false` ⇒ 走 [DebugLogActionsRow] 内的单整行分支
 * （= 改动前的调用形态，逐字保留）✓
 */
@Composable
private fun DebugLogActionsRow(ctx: android.content.Context) {
    // @Volatile 开关不产生订阅 ⇒ 组合期陪读一次 revision（setSwitches 会自增它），翻开关立即生效
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue

    // 「导出调试日志到文件」= 改动前的回调（逐字保留：AppDebugLog.export + Toast 实际路径）
    val exportClick: () -> Unit = {
        val f = com.example.liquidglass.debug.AppDebugLog.export(ctx)
        android.widget.Toast.makeText(
            ctx,
            if (f != null) "已导出：${f.absolutePath}" else "导出失败",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    if (!com.example.liquidglass.debug.DebugSwitches.iflashare) {
        // ↓↓↓ 【一行回退】改动前的单个整行按钮（逐字未动）↓↓↓
        PanelRowButton(onClick = exportClick) {
            Text("导出调试日志到文件", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
        }
        return
    }

    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PanelRowButton(modifier = Modifier.weight(1f), onClick = exportClick) {
            Text("导出调试日志到文件", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
        }
        PanelRowButton(
            modifier = Modifier.weight(1f),
            onClick = {
                val r = com.example.liquidglass.debug.AppDebugLog.share(ctx)
                if (!r.launched) {
                    // 【优雅降级】面板上显示【实际路径】（主控口径③）；导出失败时附原因（日志同源 tag LGShare）
                    val msg = when (r.reason) {
                        "export_fail" -> "导出失败，无法分享（详见日志）"
                        else -> "分享不可用（${r.reason}），日志已导出到文件：${r.path ?: "-"}"
                    }
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                }
                // 成功 = 系统分享面板已弹出（本身就是反馈，不再叠 Toast 挡住面板）
            }
        ) {
            Text("分享调试日志", fontSize = 13.sp, color = GlassPanelColorScheme.onSurface)
        }
    }
}

/**
 * 【P46·整行按钮】面板里【整行】M3 按钮的统一入口（更多设置 / 图片编辑页的恢复默认背景）。
 *
 * 分派：`DebugSwitches.liquidRealButtons`（默认开）
 *   true  → [LiquidButton]（默认 48dp 高 = M3 Button 的 48dp 最小交互占位 ⇒ 零位移）；
 *           `enabled = false` 时不上交互（`isInteractive = false` + 点击吞掉）并按 Material 的
 *           禁用观感降到 38% 不透明度 ⇒ 与旧按钮“点不动”的功能语义一致 ✓
 *   false → [PanelRowOutlinedButtonLegacy]：改动前的 M3 [OutlinedButton]（fillMaxWidth /
 *           enabled / semantics【逐字未动】）✓
 */
@Composable
private fun PanelRowOutlinedButton(
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val adapter = LocalGlassAdapter.current
    @Suppress("UNUSED_EXPRESSION") DebugBridge.revision.intValue
    if (DebugSwitches.liquidRealButtons && adapter != null) {
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = panelButtonBackdrop(adapter),
            modifier = modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.38f)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
                    } else Modifier
                ),
            isInteractive = enabled,
            // 【P54·点按放大安全区】图片编辑页「恢复默认背景」等整行按钮：上游放大单侧 +71px
            // ⇒ 溢出内容区、撞面板边界被裁；默认档钳到 0 越界（尺寸/位置/轮廓一字未改，disabled 语义不变）。
            pressContained = DebugSwitches.liquidPressSafeArea,
            traceTag = "PanelRowOutlinedButton",
            content = { content() }
        )
    } else {
        PanelRowOutlinedButtonLegacy(onClick, contentDescription, modifier, enabled, content)
    }
}

/** 【P46·回退档】改动前的整行 M3 OutlinedButton（逐字未动 ✓）。 */
@Composable
private fun PanelRowOutlinedButtonLegacy(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier
            )
    ) { content() }
}

/**
 * 内置山水壁纸（列表顺序即 [GlassUiState.bgBuiltinIndex] 的取值）。
 * 素材来自 Unsplash，Unsplash License：可免费使用（含商用）、无需署名。
 * 逐图出处：使用这 4 张图时未记录具体照片页链接，故本仓库不作追溯性链接声明（✗ 不编造 URL）；如作者补录可再更新 README 素材表。
 */
private val BuiltinWallpapers = listOf(
    "云海日出" to R.drawable.bg_clouds,
    "高山草甸" to R.drawable.bg_meadow,
    "湖泊倒影" to R.drawable.bg_lake,
    "雾中森林" to R.drawable.bg_forest,
    "网格 · 调试图" to R.drawable.debug_grid,
    "彩色网格 · 调试图" to R.drawable.debug_colorgrid,
)

/**
 * 恢复上次选择的背景：用户自选图片优先，否则回到内置山水壁纸（默认第一张）。
 *
 * 必须由【屏幕层】在启动时调用：面板内容只在打开面板时才组合，
 * 若恢复逻辑只挂在面板里，重启后会退回默认背景（v1.14.1 已修）。
 */
internal suspend fun restoreBackgroundSelection(
    uiState: GlassUiState,
    context: android.content.Context
) {
    // 【HDR 图片支持】启动时打一次环境/能力日志（sdk / 屏幕 HDR / 开关档），
    // 并在出现标志文件 `files/lg_hdr_selftest` 时跑一次性自检（解码器能力实测 + 公式数值验证 +
    // 运行时构造 gain map；全部 try/catch，失败无副作用）。见 hdr/HdrImageSupport.kt
    HdrImageSupport.logEnvironmentOnce(context)
    if (java.io.File(context.filesDir, "lg_hdr_selftest").exists()) {
        withContext(Dispatchers.IO) {
            HdrImageSupport.maybeRunSelfTest(
                context,
                java.io.File(context.filesDir, "lg_hdr_test.jpg")
            )
        }
    }
    if (BackgroundImageStore.hasStoredImage(context)) {
        val bitmap = withContext(Dispatchers.IO) {
            BackgroundImageStore.decodeStored(context, maxDim = 2048)
        }
        if (bitmap != null) {
            val (zoom, ox, oy) = BackgroundImageStore.loadAdjustments(context)
            uiState.bgImage = bitmap.asImageBitmap()
            uiState.bgImageZoom = zoom
            uiState.bgImageOffsetX = ox
            uiState.bgImageOffsetY = oy
            // 自定义图成功恢复 → 写入“自定义图片”chip 的选中态（bgBuiltinIndex = -1 并持久化）✓
            uiState.selectCustomBackground(context)
            return
        }
        // 副本损坏（升级/清理导致）：清掉残留，回退内置壁纸
        BackgroundImageStore.clear(context)
    }

    // 内置壁纸：没有记录或记录越界时都回到第一张（v1.15.0 起默认就是照片）
    val stored = BackgroundImageStore.loadBuiltinIndex(context)
    val index = stored.takeIf { it in BuiltinWallpapers.indices } ?: 0
    val resId = BuiltinWallpapers[index].second
    val bmp = withContext(Dispatchers.IO) {
        // 【HDR 图片支持】见 hdr/HdrImageSupport.kt：带 gain map + HDR 屏 ⇒ HDR，其余 = 旧解码路径
        HdrImageSupport.decodeWallpaperResource(context, resId)
    }
    uiState.bgBuiltinIndex = index
    if (bmp != null) uiState.bgImage = bmp.asImageBitmap()
}
