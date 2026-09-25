// ---------------------------------------------------------------------------
// 移植声明 / Porting notice
// 本文件部分代码移植/改编自 QWEA0/Liquid-Glass-Android（https://github.com/QWEA0/Liquid-Glass-Android），
// 以 MIT License 授权，版权行逐字如下：
//   Copyright (c) 2025-2026 pandadog
// 许可全文见本仓库 LICENSES/ 与 THIRD_PARTY_NOTICES.md；MIT 要求版权声明与许可声明随副本一并提供，已满足。
// ---------------------------------------------------------------------------
package com.example.liquidglass.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.example.liquidglass.glass.GlassDebugMode
import com.example.liquidglass.glass.GlassParameters
import com.example.liquidglass.glass.GlassQuality
import com.example.liquidglass.glass.GlassShaders
import com.example.liquidglass.glass.GlassShape
import com.example.liquidglass.glass.GlassUniforms
import com.example.liquidglass.glass.UltraClearGlassEffect
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import kotlin.math.max
import kotlin.math.min

internal var lgGPProbeMs = 0L

// ---------------------------------------------------------------------------
// effects 热路径缓存：消除每帧重复的字符串拼接与小对象分配
// ---------------------------------------------------------------------------

/**
 * 档位 → 已拼好的 AGSL 源码 + 三个调用点各自的缓存 key。
 *
 * 为什么必须缓存：[runtimeShaderEffect] 内部是 `getOrPut(key) { RuntimeShader(string) }`
 * —— key 命中时 shaderString 根本不会被读取，但它作为实参在调用点【先求值】，
 * 所以每次 effects 执行都会白拼一次 ~31KB 源码字符串（StringBuilder + 三段拷贝，
 * 拼完立刻变垃圾）；cacheKey / panelCacheKey / bridgeCacheKey 同样每帧新建 String。
 *
 * 控制中心展开动画（900ms）期间，面板节点的 effects 会因为尺寸/形状动画每帧重跑
 * （gfxinfo：中位 10ms / 99th 48ms，GPU 只有 5ms → 瓶颈在 CPU/RenderThread 侧）。
 *
 * 现在每个档位只拼一次，之后复用同一个 String 实例（内容与原先逐字节一致）。
 */
private class CachedProgram(
    val source: String,
    val cardKey: String,
    val panelKey: String,
    val bridgeKey: String,
    /**
     * 卡片专用档：把 shader 的最终输出行换成【预乘 alpha】（其余部分与 [source] 逐字符相同）。
     * 机理与证据见 DebugSwitches.cardPremultipliedOutput；面板/胶囊/张力桥仍用 [source]（不受影响）。
     */
    val cardPremultipliedSource: String,
    val cardPremultipliedKey: String,
    /**
     * 【直边→圆角切点·溢出直线】A/B 对照档 = 切线区修复【关】的同一份源码（其余与 [source] /
     * [cardPremultipliedSource] 逐字符相同，只有角区早退条件那一行是旧版）。
     * 机理与证据见 DebugSwitches.g2TangentLeakFix；两版都预生成 ⇒ setSwitches 可即刻 A/B。
     */
    val cardLeakOffSource: String,
    val cardPremultipliedLeakOffSource: String,
    val cardLeakOffKey: String,
    val cardPremultipliedLeakOffKey: String
)

/** shader 源码里"最终输出"那一行（直通 alpha 版；预乘档替换的就是它，整行锚点）。 */
private const val STRAIGHT_ALPHA_RETURN =
    "return half4(rgb, clamp(glass.a, 0.0, 1.0) * clamp(coverage, 0.0, 1.0));"

/**
 * 预乘档的替换行。
 * 合成器按【预乘 alpha】语义消费 render-effect 的输出（Skia 契约）：effect 必须返回 rgb*α，
 * 否则实际合成 = rgb + (1-α)·bg（α 不再衰减颜色）⇒ 卡片边缘的 coverage 羽化失效 ⇒ 255 硬台阶。
 */
private val PREMULTIPLIED_RETURN: String = listOf(
    "    half lgOutAlpha = clamp(glass.a, 0.0, 1.0) * clamp(coverage, 0.0, 1.0);",
    "    return half4(rgb * lgOutAlpha, lgOutAlpha);"
).joinToString("\n")

/**
 * 生成【卡片】的预乘输出源码：只把最终 return 行替换掉。
 * 锚点整行匹配、找不到就原样返回并打点（绝不静默产出半截 shader）。
 */
private fun premultipliedSourceOf(src: String): String {
    val i = src.lastIndexOf(STRAIGHT_ALPHA_RETURN)
    if (i < 0) {
        android.util.Log.w(
            "LGProbe",
            "cardPremultipliedOutput: 未在源码里命中最终 return 锚点，本档退回直通 alpha（观感 = 旧行为）"
        )
        return src
    }
    return src.substring(0, i) + PREMULTIPLIED_RETURN + src.substring(i + STRAIGHT_ALPHA_RETURN.length)
}

private val programCache = HashMap<GlassQuality, CachedProgram>(4)

/**
 * 【P30·玻璃放大后偏糊】尺寸归一化柔化系数的参考短边 px。
 *
 * 取 720px = 【默认档 glassSize=0.35 各形状玻璃层（= 卡片 + 按压余量 8dp/19dp × density）短边的最大值】：
 *   圆角矩形 / 圆 / 六边 / 三角 / 超椭圆：676 × 720 ⇒ 短边 676
 *   胶囊（1.92）/ 椭圆（1.39）：1269 × 720 / 928 × 720 ⇒ 短边 720
 * ⇒ 默认尺寸下 clamp(720/短边, …) 对【所有形状】都恒为 1.000 ⇒ 上传的 uniform 与改动前逐值相同（零回归 ✓）。
 */
private const val BLUR_SIZE_REF_PX = 720f

/** 尺寸归一化系数的下限：极大幅面下仍保留玻璃的基本柔化（不变成纯清窗）。 */
private const val BLUR_SIZE_MIN_SCALE = 0.55f

/**
 * 【P30·玻璃放大后偏糊】玻璃柔化系数（平台 blur + 边缘模糊 + RIM_SOFT 共用）。
 *
 * 机理与证据见 [com.example.liquidglass.debug.DebugSwitches.blurSizeNormalized]：
 *   ① 尺寸归一化（默认开）：clamp(720 / 玻璃层短边, 0.55, 1.0) —— 默认档恒 1.000、放大后 < 1；
 *   ② 档位系数（默认都关）：细腻 ×0.75 / 浓郁 ×1.25（供用户拍板，互斥，细腻优先）。
 * 本函数在 effects 录制 lambda 内被调用，且陪读一次 DebugBridge.revision ⇒
 * `setSwitches blurSizeNormalized 0` 等命令翻转后当场重录生效（拍不到混合态）。
 */
internal fun lgGlassBlurScale(widthPx: Float, heightPx: Float): Float {
    com.example.liquidglass.debug.DebugBridge.revision.intValue
    val tier = when {
        com.example.liquidglass.debug.DebugSwitches.blurTierFine -> 0.75f
        com.example.liquidglass.debug.DebugSwitches.blurTierRich -> 1.25f
        else -> 1.00f
    }
    if (!com.example.liquidglass.debug.DebugSwitches.blurSizeNormalized) return tier
    val shortSide = min(max(min(widthPx, heightPx), 1f), 1.0e6f)
    return tier * (BLUR_SIZE_REF_PX / shortSide).coerceIn(BLUR_SIZE_MIN_SCALE, 1f)
}

private fun programOf(quality: GlassQuality): CachedProgram = programCache.getOrPut(quality) {
    val src = GlassShaders.source(quality)
    // 【切线区溢出直线】A/B 对照档：同一份源码，只有角区早退条件那一行是旧版（逐字符等价）
    val srcLeakOff = GlassShaders.source(quality, tangentLeakFix = false)
    CachedProgram(
        source = src,
        cardKey = GlassShaders.cacheKey(quality),
        panelKey = GlassShaders.panelCacheKey(quality),
        bridgeKey = GlassShaders.bridgeCacheKey(quality),
        cardPremultipliedSource = premultipliedSourceOf(src),
        cardPremultipliedKey = GlassShaders.cacheKey(quality) + "#premul",
        cardLeakOffSource = srcLeakOff,
        cardPremultipliedLeakOffSource = premultipliedSourceOf(srcLeakOff),
        cardLeakOffKey = GlassShaders.cacheKey(quality) + "#leakoff",
        cardPremultipliedLeakOffKey = GlassShaders.cacheKey(quality) + "#premul#leakoff"
    )
}

/**
 * CORNER_RADII 的跨帧复用缓冲。
 *
 * `setFloatUniform(name, FloatArray)` 在 Android 端会立刻把数组内容拷进 RuntimeShader，
 * 因此复用同一个数组实例是安全的；只有半径真的变化时才重写内容。
 */
private class CornerRadiiBuffer {
    private var lastRadiusPx = Float.NaN
    val array = FloatArray(4)

    fun write(radiusPx: Float): FloatArray {
        if (radiusPx != lastRadiusPx) {
            lastRadiusPx = radiusPx
            array.fill(radiusPx)
        }
        return array
    }
}

/**
 * 卡片 dp→px 换算结果（[UltraClearGlassEffect.ShaderValues]）的跨帧缓存：
 * 参数与尺寸都没变时复用同一个对象，不再每帧新建。
 * 只做缓存，不参与数值计算 —— 与每次调用 compute() 的结果完全一致。
 */
private class ShaderValuesCache {
    private var lastParameters: GlassParameters? = null
    private var lastWidthPx = Float.NaN
    private var lastHeightPx = Float.NaN
    private var lastDensity = Float.NaN
    private var cached: UltraClearGlassEffect.ShaderValues? = null

    fun get(
        parameters: GlassParameters,
        density: Float,
        widthPx: Float,
        heightPx: Float
    ): UltraClearGlassEffect.ShaderValues {
        val hit = cached
        if (hit != null &&
            lastDensity == density &&
            lastWidthPx == widthPx &&
            lastHeightPx == heightPx &&
            lastParameters == parameters
        ) {
            return hit
        }
        val fresh = UltraClearGlassEffect.compute(parameters, density, widthPx, heightPx)
        cached = fresh
        lastParameters = parameters
        lastDensity = density
        lastWidthPx = widthPx
        lastHeightPx = heightPx
        return fresh
    }
}

/**
 * Backdrop 2.0.0 的唯一封装层。
 *
 * 项目中所有 Backdrop 相关调用（捕获层注册、drawBackdrop、effects、
 * runtimeShaderEffect、uniform 设置）都集中在本文件，禁止散落于其他代码。
 *
 * 架构（【② 分层修复】后：三层各司其职，采样层里有什么 = 明确的语义）：
 *   BackgroundScene ──Modifier.capture()──▶ captureLayer（背景捕获层：只录 App 背景那一层）
 *   控制中心面板容器 ──Modifier.layerBackdrop()──▶ panelCaptureLayer（面板离屏层：录面板已渲染结果）
 *   LiquidGlassCard ──Modifier.glass()──▶ 按卡片窗口坐标采样上面的层（组合见 rememberCombinedBackdrop）
 *
 * 为什么必须有 panelCaptureLayer：修复前卡的采样层里【从来没有面板】✗ —— 卡压在展开的面板上时，
 *   看到的仍是"面板下面的背景"，观感 = 透过控制中心看到它下面的内容 ✗（分层语义含混）。
 *   卡要"在面板之上"就必须能采到面板（DebugSwitches.cardOverPanel=true，默认档）✓。
 */
@Composable
fun rememberBackdropAdapter(): BackdropAdapter {
    // LayerBackdrop：捕获源。场景内容在被绘制后记录进其 GraphicsLayer，
    // 玻璃卡片通过坐标差采样正确区域。整个页面只创建一次。
    val captureLayer = rememberLayerBackdrop()
    // 【P07】离屏层：下层玻璃（主卡）把【自己渲染后的结果】录进这里，供【上层玻璃】采样
    // ⇒ 上层玻璃看到的是"已被下层玻璃折射过的画面"，而不是同一张原始背景（真二次折射）。
    val dualCaptureLayer = rememberLayerBackdrop()
    // 【② 分层】面板离屏层：控制中心面板容器把【自己渲染后的结果】录进这里。
    // 卡片在面板之上时用它做采样源（连同背景层）⇒ 卡折射的是【面板本身】✓
    // （改动前卡的采样层里没有面板 ⇒ 视觉上"透过面板看到面板下面的内容" ✗）。
    val panelCaptureLayer = rememberLayerBackdrop()
    // 【P45·真实控件·采样源】面板【填充层】离屏层：由 ui/LiquidGlassScreen.kt 给面板填充 Box
    //   挂 `Modifier.layerBackdrop(adapter.panelFillLayer)`（只在该 Box 的那一层）逐帧录制 ⇒
    //   面板内【真实控件】（LiquidToggle / LiquidSlider）的采样源 = 控件脚下真正可见的材质 ✓
    //   为什么不能直接用 captureLayer / panelCaptureLayer：
    //     · captureLayer = 整页背景捕获层（面板不进层）⇒ 控件会映出"面板背后的壁纸"= P34 用户判负 ✗；
    //     · panelCaptureLayer = 面板【含内容】的完整结果 ⇒ 控件采样它 = 采到自己（自引用）✗。
    //   本层只录面板填充层（不含任何控件）⇒ 无自引用 ✓，且它就是控件脚下的可见材质 ✓
    val panelFillLayer = rememberLayerBackdrop()
    // 【P37 多卡·逐层采样链】每块玻璃一块离屏层（索引 = 卡索引，共 4 = 多卡演示的最大卡数）。
    //   卡 i 把自己【渲染后的结果】录进 cardCaptureLayers[i]；z 序在它之上的每一块卡都把
    //   它算进自己的采样源 ⇒ 第 N 块的采样 = 背景 +（面板）+ 第 0..N-1 块的已渲染结果 ✓
    // 索引 0 = 上面那个 dualCaptureLayer（P07 双卡路径的对象与语义逐字不变 ✓）。
    // 取舍（N-1 个层 vs 一个累积层）见 BackdropAdapter.cardCaptureLayers 的 KDoc。
    val cardCaptureLayers = buildList {
        add(dualCaptureLayer)
        repeat(BACKDROP_CARD_LAYER_COUNT - 1) { add(rememberLayerBackdrop()) }
    }
    return remember {
        BackdropAdapter(captureLayer, cardCaptureLayers, panelCaptureLayer, panelFillLayer)
    }
}

/** 多卡演示的最大卡数（= ui/LiquidGlassScreen.kt 的 maxCards）。离屏层数 = 本值（每卡一块）。 */
private const val BACKDROP_CARD_LAYER_COUNT = 4

class BackdropAdapter internal constructor(
    /** 场景捕获层：只含 App 背景那一层（面板/卡片都【不】进这一层）。 */
    val captureLayer: LayerBackdrop,
    /**
     * 【P37 多卡·逐层采样链】每块卡各自的离屏层（索引 = 卡索引）。
     *
     * 语义 = 该卡【已渲染结果】的录制目标（库自带 `exportedBackdrop` 通路逐帧 recordLayer），
     * 供 z 序在它之上的每一块卡采样 ⇒ 采样链自下而上逐层累积：
     *   卡 0（最底）：采样 背景 +（面板）；输出录进 cardCaptureLayers[0]
     *   卡 1：       采样 背景 +（面板）+ 层[0]；输出录进 cardCaptureLayers[1]
     *   卡 2：       采样 背景 +（面板）+ 层[0] + 层[1]；…
     * ⇒ 第 N 块的 shader 输入里含【每一块下层玻璃折射后的画面】= 多块玻璃的逐层实时二次折射 ✓
     *
     * 【为什么是 N-1 个离屏层，而不是一个"累积层"】
     *  · 累积层的硬伤（结构性，非性能偏好）：录制层不能同时被自己采样 —— 累积层既是卡的采样源、
     *    又是"累积 + 自己"的录制目标 ⇒ 必须双缓冲 A/B 交替（每帧换 buffer，且缓存要在两帧间翻转），
     *    且累积层必须覆盖所有卡的并集（实测卡阵占据 12.5%~49.6% 屏面积，见 P06 记录）⇒
     *    每块卡都要【再画一遍累积层 + 自己】：N=4 时每帧多 3 次整屏级录制 + 重复绘制。
     *  · N-1 个层是"每卡一块、只录自己"：每块卡只多一次【自己尺寸】的离屏录制，
     *    且与库的 exportedBackdrop 通路一一对应（P07 已是 N=2 的特例 ⇒ 双卡路径零改动的语义等价）。
     *  · 代价（如实）：卡 i 的 effects 输入里要 blit (2 + i) 块层（含背景与面板）⇒
     *    层 blit 次数 = Σ(2+i) = N·(N+3)/2（N=2:5 / N=3:9 / N=4:14 次），实测帧耗时见交付报告表格。
     *  ⚠️ 使用约束：同一层【只有一个写入者】（卡 i）且写入必须发生在读取它的卡之前 ⇒
     *    绘制顺序 = z 序（ui/LiquidGlassScreen.kt 的 zIndex），本文件的调用方负责保证。
     */
    val cardCaptureLayers: List<LayerBackdrop>,
    /**
     * 【② 分层修复】控制中心面板的离屏层：由面板容器（ui/LiquidGlassScreen.kt 的面板最外层 Box）
     * 的 `Modifier.layerBackdrop(...)` 逐帧录制 ⇒ 面板【已渲染结果】进入卡的采样层 ✓
     * （DebugSwitches.cardOverPanel=true 时，卡的 backdrop = CombinedBackdrop(捕获层, 本层)）。
     */
    val panelCaptureLayer: LayerBackdrop,
    /**
     * 【P45·真实控件】面板【填充层】的离屏层：由面板填充 Box（ui/LiquidGlassScreen.kt 的
     * `Box(Modifier.matchParentSize().graphicsLayer{alpha=fillAlphaOf(p)}…)`）挂
     * `Modifier.layerBackdrop(...)` 逐帧录制（开关 `DebugSwitches.liquidRealControls` 门控）。
     *
     * 用途：面板内【真实控件】（`LiquidToggle` / `LiquidSlider`）的采样源 —— 采样源必须
     * 与"控件脚下真正可见的材质"同源，且【不含控件自身】（否则自引用）。
     * 对照档（`liquidRealControlsSamplePanel=false`）仍可用 [captureLayer]（= P34 判负的那一档）。
     */
    val panelFillLayer: LayerBackdrop
) {
    /**
     * 【P07 兼容入口】双卡二次折射用的那一个离屏层 = [cardCaptureLayers] 的第 0 块
     * （= 主卡/最下面那块卡的输出层）。保留这个名字 ⇒ 既有调用点与文档一字不用改 ✓
     */
    val dualCaptureLayer: LayerBackdrop get() = cardCaptureLayers[0]
    /**
     * 形态插值：0 = 收起态胶囊按钮（无色透明液态玻璃），1 = 展开态面板（白色磨砂底）。
     *
     * 收起按钮与展开面板共用同一个玻璃元素，因此光学参数必须按形态插值：
     * 否则给面板加的“白色磨砂底”会把收起的按钮一起染成不透明白色——玻璃感被冲掉，
     * 浅色标签也跟着看不见（用户反馈“字呢 / 不是说要玻璃效果吗”）。
     */
    var panelExpansion: () -> Float = { 1f }

    /**
     * 收起按钮的按压进度 0..1：0 = 静止（白底胶囊），1 = 按下（跟随手指放大的液态玻璃）。
     * 与 [panelExpansion] 正交：面板态只看 panelExpansion，按钮态看这个值插值材质。
     */
    var buttonPress: () -> Float = { 0f }
}

/** 给背景场景注册捕获层（玻璃卡片是兄弟节点，不会捕获自身）。 */
fun Modifier.capture(adapter: BackdropAdapter): Modifier = this.layerBackdrop(adapter.captureLayer)

/**
 * 【P37 多卡·逐层采样链】把 N 块层按【给定顺序】依次画进同一份 effect 输入
 * （= 库内 `CombinedBackdrops` 的等价实现；语义：后画的层压在前面的层之上）。
 *
 * ⚠️ 为什么不用库的 `rememberCombinedBackdrop(vararg backdrops)`：
 *   它的 remember 键 = 层数组本身 ⇒ 键槽数随【链长】变化。而链长会在两次组合之间变
 *   （例：卡 0 被置顶后 below 从 [] 变成 [1,2]；或 2 卡/3 卡切换；或首帧尚未测量时链为空）⇒
 *   在这类"外层 composable 内部槽位结构变化"的场景里 Compose 不会检测 ⇒ 槽位错位，
 *   把一枚键（LayerBackdrop）当成 remember 的值读出来 ⇒
 *   实测崩溃：`java.lang.ClassCastException: com.kyant.backdrop.backdrops.LayerBackdrop
 *   cannot be cast to com.kyant.backdrop.backdrops.CombinedBackdrops`（CombinedBackdrop.kt:35，
 *   am_crash 记录 00:37:18）。= 用户点置顶那一刻闪退。
 *
 *   本类配【恒定结构】的 remember（单键 = 层列表的标签 String）⇒ 槽位数固定，
 *   链长/顺序变化只改键的内容（String.equals）⇒ 不会错位 ✓。
 */
@Immutable
class OrderedBackdropChain(private val layers: List<Backdrop>) : Backdrop {

    override val isCoordinatesDependent: Boolean = layers.any { it.isCoordinatesDependent }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        layers.forEach { backdrop ->
            with(backdrop) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}

/**
 * 给玻璃卡片应用 Liquid Glass 光学层（Backdrop API 唯一调用点）。
 *
 * @param adapter Backdrop 适配器（提供捕获层）。
 * @param shape 卡片圆角形状（与 [cornerRadiusDp] 保持一致，用于裁剪）。
 * @param cornerRadiusDp 四角统一圆角半径（dp），作为 SDF 输入，与 shape 匹配。
 * @param parameters 参数提供者；effects 执行时读取，快照状态变化会自动触发
 *                  uniform 更新（不触发整页重组，不重新编译 Shader）。
 * @param quality 画质档位提供者；key 变化时切换预缓存 Shader。
 * @param cardOriginPx 卡片窗口坐标（px），驱动环境光微变。
 * @param pressProgress 按压进度 0..1。
 * @param pressPositionPx 触点卡片局部坐标（px）。
 * @param dragVelocityPx 拖动速度（px/s，已限幅）。
 * @param stretchDirection 拖动方向（归一化）。
 * @param timeSeconds 交互期间动画时间（秒），静止时为 0。
 * @param adaptiveLegibility 内容感知可读性开关。
 * @param labelRegion 前景文字区域（卡片局部 px，null 表示禁用自适应）。
 * @param debugMode Shader 调试模式（FINAL 为最终效果）。
 */
fun Modifier.glass(
    adapter: BackdropAdapter,
    shape: Shape,
    cornerRadiusDp: Float,
    shapeType: () -> GlassShape,
    parameters: () -> GlassParameters,
    quality: () -> GlassQuality,
    cardOriginPx: () -> Offset,
    pressProgress: () -> Float,
    pressPositionPx: () -> Offset,
    dragVelocityPx: () -> Offset,
    stretchDirection: () -> Offset,
    timeSeconds: () -> Float,
    adaptiveLegibility: () -> Boolean,
    labelRegion: () -> Rect?,
    debugMode: () -> GlassDebugMode,
    /** iOS 透镜剖面（移植自 QWEA0/Liquid-Glass-Android）：true = 逆幂折射剖面，false = 原 Golden 剖面。 */
    lensProfile: () -> Boolean = {
    /**
     * 形态插值：0 = 收起态胶囊按钮（无色透明液态玻璃），1 = 展开态面板（白色磨砂底）。
     * 按钮与面板**共用同一个玻璃元素**，光学参数必须按形态插值——否则为面板加的
     * "白色磨砂底"会把收起的按钮一起染成不透明白色：玻璃感被冲掉，浅色标签也随之
     * 看不见（用户反馈"字呢 / 不是说要玻璃效果吗"）。
     */
    var panelExpansion: () -> Float = { 1f }

    /**
     * 形态插值：0 = 收起态胶囊按钮（无色透明液态玻璃），1 = 展开态面板（白色磨砂底）。
     * 按钮与面板**共用同一个玻璃元素**，光学参数必须按形态插值——否则为面板加的
     * "白色磨砂底"会把收起的按钮一起染成不透明白色：玻璃感被冲掉，浅色标签也随之
     * 看不见（用户反馈"字呢 / 不是说要玻璃效果吗"）。
     */
 true },
    /** 减少动态效果：true 时关闭按压形变与触点凸起。 */
    reduceMotion: () -> Boolean = { false },
    /** 拖动拉伸：true 时拖动速度才产生玻璃拉伸位移。 */
    stretchEnabled: () -> Boolean = { true },
    /** 采样源：默认整页背景捕获层；传面板自身的捕获层可让面板内玻璃采到面板内容。 */
    backdrop: com.kyant.backdrop.Backdrop? = null,
    /**
     * 【P07】把本卡【渲染后的结果】录进这个离屏层（库自带通路：DrawBackdropNode.draw 里
     * `exportedBackdrop.graphicsLayer` 的 recordLayer）⇒ 另一块（上层）玻璃可以采样它 = 二次折射。
     * null（默认）= 不做任何额外录制 ⇒ 与改动前逐像素一致、零额外成本 ✓
     */
    exportedBackdrop: LayerBackdrop? = null,
    /**
     * 【P07】effects 内的额外快照订阅点：本卡（上层卡）需要在【对象卡移动】时同帧重录，
     * 否则离屏层的相对偏移会停在旧值（历史事故：玻璃采到旧内容）。
     */
    extraObservedReads: () -> Unit = {},
    /** HDR 高光增益（窗口处于 HDR 色彩模式时 > 1）。 */
    hdrBoost: () -> Float = { 1f },
    /**
     * 动态可见边界：按压收缩时可见边界要跟着 Shader SDF 逐轴收，
     * 否则模糊/折射层与可见形状不同步（用户反馈"按下去模糊层没更新形状"）。
     */
    shapeProvider: (() -> Shape)? = null,
    /**
     * 【P12·邻近流体融合】本卡这一帧的融合 uniform（对方卡几何 + fuseK + 颈部/细丝），
     * 由 ProximityFusion 逐帧驱动；null（默认）或返回 null ⇒ 全部融合 uniform 置 0
     * ⇒ AGSL 里融合段整体跳过（逐像素等于改动前）。
     */
    fusionUniforms: (() -> com.example.liquidglass.glass.ProximityFusion.CardUniforms?)? = null
): Modifier {
    // 跨帧复用的小对象（每帧新建 = GC 压力）：只在数值变化时重建，数值语义不变。
    val valuesCache = ShaderValuesCache()
    val cornerRadiiBuffer = CornerRadiiBuffer()
    return this.drawBackdrop(
    backdrop = backdrop ?: adapter.captureLayer,
        shape = shapeProvider ?: { shape },
        // 关闭库自带的高光与阴影：边缘光学完全由自定义 AGSL 控制
        highlight = null,
        shadow = null,
        // 【P07】下层卡把自己的玻璃输出录进离屏层（null = 不动，逐像素等于改动前）
        exportedBackdrop = exportedBackdrop,
        effects = {
            // 【P07】上层卡：订阅对象卡的位置等外部状态 ⇒ 它一动本卡同帧重录（防采样旧内容）
            extraObservedReads()
            // ---- effects 内所有参数读取都会被 observeReads 追踪 ----
            val p = parameters()
            val q = quality()

            // dp → px 统一换算（UltraClearGlassEffect 集中管理）
            // 换算结果按 (参数, 尺寸) 缓存：不变时复用同一对象，不再每帧新建 ShaderValues
            val values = valuesCache.get(p, density, size.width, size.height)
            padding = values.layerPaddingPx

            val layerSize = Size(size.width + padding * 2f, size.height + padding * 2f)
            // 圆角适配：圆形/胶囊取短边一半，椭圆/三角形取小比例，圆角矩形取配置值
            val shapeKind = shapeType()
            val maxR = min(size.width, size.height) / 2f
            val cornerRadiusPx = when (shapeKind) {
                GlassShape.ROUNDED_RECT -> UltraClearGlassEffect.cornerRadiusPx(cornerRadiusDp, density, size.width, size.height)
                GlassShape.CIRCLE, GlassShape.CAPSULE -> maxR
                GlassShape.ELLIPSE -> maxR * 0.72f
                // 三角形仅微小圆角（过大圆角会把尖角抹平）
                GlassShape.TRIANGLE -> maxR * 0.03f
                GlassShape.HEXAGON -> maxR * 0.03f
                GlassShape.SUPERELLIPSE -> maxR * 0.04f
            }
            // 复用同一个数组：半径不变时连写入都省掉（setFloatUniform 会立刻拷进 shader）
            val cornerRadii = cornerRadiiBuffer.write(cornerRadiusPx)

            // 源码/key 走档位缓存：不再每次 effects 重拼 ~31KB AGSL 字符串
            val program = programOf(q)
            // 【卡片边缘 255 硬台阶·根因修复】卡片输出改【预乘 alpha】（详见 DebugSwitches.cardPremultipliedOutput）：
            // effect 的返回值被合成器按预乘语义消费 ⇒ 直通 alpha 会让 coverage 羽化失效（= 硬台阶）。
            // 下面这一行同时是 effects 录制 lambda 内的【快照订阅点】：adb setSwitches 翻转开关后立即重录，
            // 拍不到"混合态"（否则 A/B 会给出自相矛盾的结论，见 DebugSwitches 里 panelEdgeAa 的教训）。
            val premultiplied = com.example.liquidglass.debug.DebugSwitches.cardPremultipliedOutput
            // 【切线区溢出直线】修复档 / 对照档（两版源码都预生成 ⇒ 运行时 A/B 即刻生效）
            val tangentFix = com.example.liquidglass.debug.DebugSwitches.g2TangentLeakFix
            com.example.liquidglass.debug.DebugBridge.revision.intValue
            // 四个组合（预乘 × 切线修复）各自独立 key：任何一处翻转都不会串档，也拍不到"混合态"
            val shaderKey: String
            val shaderText: String
            if (premultiplied) {
                shaderKey = if (tangentFix) program.cardPremultipliedKey else program.cardPremultipliedLeakOffKey
                shaderText = if (tangentFix) program.cardPremultipliedSource else program.cardPremultipliedLeakOffSource
            } else {
                shaderKey = if (tangentFix) program.cardKey else program.cardLeakOffKey
                shaderText = if (tangentFix) program.source else program.cardLeakOffSource
            }
            runtimeShaderEffect(
                key = shaderKey,
                shaderString = shaderText,
                uniformShaderName = GlassUniforms.SHADER_INPUT
            ) {
                setFloatUniform(GlassUniforms.HDR_CEIL, 4.0f)   // 卡片：保留玻璃 HDR 增益
                setFloatUniform(GlassUniforms.FINAL_DESAT, 0.0f)
            setFloatUniform(GlassUniforms.HDR_BOOST, 1f)   // 【审查修复①】桥同样漏传 → 高光整段失效 ✓
                // ---- 几何（全部为 px）----
                setFloatUniform(GlassUniforms.RESOLUTION, layerSize.width, layerSize.height)
                setFloatUniform(GlassUniforms.OFFSET, -padding, -padding)
                val origin = cardOriginPx()
                setFloatUniform(GlassUniforms.CARD_ORIGIN, origin.x, origin.y)
                setFloatUniform(GlassUniforms.CARD_SIZE, size.width, size.height)
                setFloatUniform(GlassUniforms.CORNER_RADII, cornerRadii)
                setIntUniform(GlassUniforms.SHAPE_TYPE, shapeKind.ordinal)
                // ---- 【P12·邻近流体融合】（默认全 0 = 不融合 ⇒ 逐像素等于改动前）----
                val fuseU = fusionUniforms?.invoke()
                setFloatUniform(GlassUniforms.FUSE_BLEND_K, fuseU?.blendK ?: 0f)
                setFloatUniform(
                    GlassUniforms.FUSE_OFFSET2,
                    fuseU?.offset2?.x ?: 0f, fuseU?.offset2?.y ?: 0f
                )
                setFloatUniform(
                    GlassUniforms.FUSE_HALF2,
                    fuseU?.half2?.width ?: 0f, fuseU?.half2?.height ?: 0f
                )
                setFloatUniform(GlassUniforms.FUSE_RADIUS2, fuseU?.radius2 ?: 0f)
                setIntUniform(GlassUniforms.FUSE_SHAPE2, fuseU?.shape2 ?: 0)
                setFloatUniform(
                    GlassUniforms.FUSE_NECK_CENTER,
                    fuseU?.neckCenter?.x ?: 0f, fuseU?.neckCenter?.y ?: 0f
                )
                setFloatUniform(
                    GlassUniforms.FUSE_NECK_AXIS,
                    fuseU?.neckAxis?.x ?: 1f, fuseU?.neckAxis?.y ?: 0f
                )
                setFloatUniform(GlassUniforms.FUSE_NECK_HALF, fuseU?.neckHalf ?: 0f)
                setFloatUniform(GlassUniforms.FUSE_NECK_R, fuseU?.neckR ?: 0f)
                setFloatUniform(GlassUniforms.FUSE_NECK_RC, fuseU?.neckRc ?: 0f)
                // 【P07·融合 meld】并集「归属划分」开关（DualCardMeld 每帧写；见 glass/MeldShaders.kt）：
                //   1 = 并集区只由更近的那块卡绘制 ⇒ 并集只画一次 = 一次折射/一条轮廓（无双边框 ✗）
                //   0 = 不划分（单卡/两卡远离/dualCardMeld=false ⇒ 与改动前逐像素一致 ✓）
                setFloatUniform(
                    GlassUniforms.MELD_OWN,
                    if (com.example.liquidglass.glass.DualCardMeld.partitionActive) 1f else 0f
                )
                setFloatUniform(GlassUniforms.EDGE_ZONE, values.edgeZonePx * 2.1f)   // 加宽：参考图那种宽幅边缘折射   // 加厚：倒角带更宽 = 边缘更像厚玻璃

                // ---- 光学参数（dp 已全部转换为 px）----
                setFloatUniform(GlassUniforms.BACKGROUND_TRANSMISSION, p.backgroundTransmission)
                setFloatUniform(GlassUniforms.MATERIAL_OPACITY, p.materialOpacity)
                setFloatUniform(GlassUniforms.TINT_OPACITY, p.tintOpacity)
                // 档位差异可见化：5t 更锐利（0.8x）、9t 标准、13t 更柔和（1.35x 模糊半径）
                val qualityBlurFactor = when (q) {
                    GlassQuality.PERFORMANCE -> 0.80f
                    GlassQuality.BALANCED -> 1.00f
                    GlassQuality.QUALITY -> 1.35f
                }
                // 【谷歌官方 API】平台真高斯模糊（BlurEffect，硬件加速、无采样瑕疵）
                // 替代我们 shader 里手写的 N 抽头螺旋采样（粗糙/摩尔纹的根源 ✗）
                // 【P30·玻璃放大后偏糊】尺寸归一化柔化系数：默认档（短边 ≤ 720px）恒为 1.000
                // ⇒ 上传 uniform 与改动前【逐值相同】（零回归 ✓）；放大后按 720/短边 收敛
                // （0.50 档 952px 短边 ⇒ 0.756）。开关 / 档位见 DebugSwitches.blurSizeNormalized。
                val blurScale = lgGlassBlurScale(size.width, size.height)
                blur(values.blurRadiusPx * blurScale)
                setFloatUniform(GlassUniforms.BLUR_RADIUS, values.blurRadiusPx * blurScale * 0.05f)   // 卡片：shader 侧保留少量半径
                setFloatUniform(GlassUniforms.EDGE_BLUR_RADIUS, values.edgeBlurRadiusPx * blurScale)   // 卡片：边缘模糊
setFloatUniform(GlassUniforms.PANEL_RIM_DARKEN, 0.85f)   // 卡片：厚暗外圈（原来误写进面板块 → 卡片只有亮线 ✗）
                                setFloatUniform(GlassUniforms.REFRACTION_OFFSET, values.refractionOffsetPx)
                setFloatUniform(GlassUniforms.REFRACTION_HEIGHT, values.refractionHeightPx)
                // 色散：偏移限幅 ≤2.5dp（远小于主折射位移），强度限幅 0~1
                val maxDispersionPx = 3.0f * density
                // 【P03·四角色散】开关 = DebugSwitches.cornerDispersion（默认 false ⇒ 下面各行取原表达式 ⇒ 与改动前逐像素一致 ✓）：
                //   开 = 强度 0.20 / 偏移 1.6dp（"四角加权色散 0.20 × 8dp 带"；带 8dp 由 p.dispersionEdgeWidthDp 提供）；
                //   cornerDispersionGain 交给 AGSL 把色散权重收到四角（直边/中心恒 0）；
                //   本块是录制 lambda 内的快照订阅点（同上方 premultiplied/tangentFix）⇒ setSwitches 翻转直接重录生效 ✓
                val cornerDispOn = com.example.liquidglass.debug.DebugSwitches.cornerDispersion
                setFloatUniform(
                    GlassUniforms.DISPERSION_OFFSET,
                    if (cornerDispOn) (1.6f * density).coerceIn(0f, maxDispersionPx)
                    else values.dispersionOffsetPx.coerceIn(0f, maxDispersionPx)
                )
                setFloatUniform(GlassUniforms.DISPERSION_EDGE_WIDTH, p.dispersionEdgeWidthDp * density)
                setFloatUniform(
                    GlassUniforms.DISPERSION_STRENGTH,
                    if (cornerDispOn) 0.20f else p.dispersionStrength.coerceIn(0f, 1f)
                )
                // 【P03·四角色散】四角权重门控（1 = 开；0 ⇒ shader 内权重退化为 1.0 = 不影响）
                setFloatUniform(GlassUniforms.CORNER_DISPERSION_GAIN, if (cornerDispOn) 1f else 0f)
                setFloatUniform(GlassUniforms.FRESNEL_STRENGTH, p.fresnelStrength)
                // ---- iOS 透镜模型（移植自 QWEA0/Liquid-Glass-Android, MIT）----
                // falloff = 2：逆幂衰减（引力透镜剖面），贴边弯折最剧烈
                setFloatUniform(GlassUniforms.LENS_FALLOFF, 2.0f)
                // 按压：只膨胀玻璃轮廓（向外摊开，水平多于垂直），不做图层缩放——
                // 缩放会把玻璃内部看到的画面一起放大，那正是"直接影响玻璃里的内容"
                // 减少动态效果开启时：不做任何按压形变（开关要有可见差异）
                // 按压只改变玻璃【形状】，且必须向内压缩：
                // 向外膨胀会越过可见边界（drawBackdrop 按 Compose Shape 裁剪），
                // 贴边高光带会一起被裁掉——表现为"点一下高光就没了"。向内收缩则始终在
                // 可见边界以内，高光完整保留；内部画面采样仍按屏幕坐标 1:1，不受影响。
                // 按压进度只读一次，SHAPE_INFLATE 与 PRESS_PROGRESS 共用同一个值（同一帧内等价）
                val pressProgressValue = pressProgress()
                val pressValue = if (reduceMotion()) 0f else pressProgressValue
                setFloatUniform(
                    GlassUniforms.SHAPE_INFLATE,
                    // 用户要求：按压反馈【由小变大】——原来这里是负值（向内收缩 ✗），
                    // 现在改为正值向外膨胀；x/y 逐轴比例保持 6:2（与 Compose 侧同源）。
                    // 【恢复 SDF 外扩】此前放弃它是因为轮廓超出玻璃层被 path clip 硬裁 ✗
                    //（高光大片缺失）。现在裁剪已改为矩形、形状完全交给 SDF，不再有硬裁 ✗，
                    // 且 alpha×coverage 提供抗锯齿 ✓ → 外扩可安全启用：按下由小变大且不重采样 ✓
                    // 玻璃层比卡片大 8dp/19dp（按下余量）→ 静止时用【负膨胀】把可见形状
                    // 收回卡片尺寸 ✓；按下时膨胀归零，形状长满整层 = 由小变大 ✓✓
                    // （这正是渲染侧唯一正确的做法：不缩放图层 ✗、不超出 RenderNode 边界 ✗）
                    -8f * density * (1f - pressValue),
                    -19f * density * (1f - pressValue)
                )
                // 触点局部液态凸起关闭（它会把玻璃内部的画面做局部变形）
                setFloatUniform(GlassUniforms.TOUCH_AMP, 0f)
                // 边缘柔化：折射带内沿法线方向抹匀，把"一条硬线"变成一段渐变
                // 【P30】同一系数：默认档恒 1.000（逐值等于改动前），放大后随尺寸收敛更清晰
                setFloatUniform(GlassUniforms.RIM_SOFT, values.edgeBlurRadiusPx * 0.5f * blurScale)
                // 卡片的玻璃本体色用【有色介质吸收模型】（保留背景层次），
                // 并开启逐像素自适应染色（跨明暗背景不整体翻转）
                setFloatUniform(GlassUniforms.TINT_MODEL, 1f)
                setFloatUniform(
                    GlassUniforms.ADAPTIVE_TINT,
                    if (p.backgroundTransmission < 0.70f) 1f else 0f
                )
                setFloatUniform(
                    GlassUniforms.LENS_PROFILE,
                    if (lensProfile()) 1f else 0f
                )
                setFloatUniform(GlassUniforms.HDR_BOOST, hdrBoost())
                setFloatUniform(GlassUniforms.EDGE_HIGHLIGHT_OPACITY, p.edgeHighlightOpacity)
                // 【P44·玻璃边沿「糊」修复】贴边发丝高光带的支撑半宽倍率（× hairScale，见 GlassUniforms.EDGE_HAIR_SUPPORT）：
                //   默认开（DebugSwitches.edgeHairBandSharp=true）= 新档（更锋利，GlassParameters.HAIR_BAND_SUPPORT_MUL）；
                //   关（一行回退）= 旧档 5.0 = 改动前的观感（逐像素等价）。
                //   下面两行同时是【effects 录制 lambda 内的快照订阅点】：read revision ⇒ setSwitches /
                //   setEdgeHairSupport 后立即重录生效（拍不到"混合态" ✗，见本文件上方 premultiplied 的教训）。
                val hairSharpOn = com.example.liquidglass.debug.DebugSwitches.edgeHairBandSharp
                val hairSupportMul = com.example.liquidglass.debug.DebugSwitches.edgeHairSupportMul
                com.example.liquidglass.debug.DebugBridge.revision.intValue
                setFloatUniform(
                    GlassUniforms.EDGE_HAIR_SUPPORT,
                    if (hairSharpOn) hairSupportMul else GlassParameters.HAIR_BAND_SUPPORT_MUL_LEGACY
                )
                setFloatUniform(GlassUniforms.EDGE_SHADOW_OPACITY, p.edgeShadowOpacity)
                setFloatUniform(GlassUniforms.LOCAL_DIMMING_OPACITY, p.localDimmingOpacity)
                setFloatUniform(GlassUniforms.SATURATION, p.saturation)

                // ---- 形变 ----
                setFloatUniform(GlassUniforms.PRESS_PROGRESS, pressProgressValue)
                val press = pressPositionPx()
                setFloatUniform(GlassUniforms.PRESS_POSITION, press.x, press.y)
                val velocity = dragVelocityPx()
                setFloatUniform(GlassUniforms.DRAG_VELOCITY, velocity.x, velocity.y)
                val stretch = stretchDirection()
                setFloatUniform(GlassUniforms.STRETCH_DIRECTION, stretch.x, stretch.y)
                // 拖动拉伸开关（面板里的"拖动拉伸（玻璃）"）：关闭时不再施加拉伸位移
                setFloatUniform(
                    GlassUniforms.DEFORMATION_STRENGTH,
                    if (stretchEnabled()) p.deformationStrength else 0f
                )
                setFloatUniform(GlassUniforms.TIME, timeSeconds())

                // ---- 可读性 ----
                setFloatUniform(GlassUniforms.ADAPTIVE_LEGIBILITY, if (adaptiveLegibility()) 1f else 0f)
                val region = labelRegion()
                if (region != null) {
                    setFloatUniform(
                        GlassUniforms.LABEL_REGION,
                        region.left, region.top, region.width, region.height
                    )
                } else {
                    setFloatUniform(GlassUniforms.LABEL_REGION, -1f, 0f, 0f, 0f)
                }
                // ---- 调试模式 ----
                setIntUniform(GlassUniforms.DEBUG_MODE, debugMode().shaderCode)
                // 冷色环境色调与暖白材质填充（alpha 1，强度由对应 uniform 控制）
                setColorUniform(GlassUniforms.TINT_COLOR, Color(0xFFBCC8E8))
                setColorUniform(GlassUniforms.MATERIAL_COLOR, Color(0xFFB9C0CC))   // 调优：烟灰减淡、保留清透   // 新分支：烟灰中性（参考图的"烟灰玻璃"）
            }
        }
    )
}

/**
 * 控制中心面板（含收起态椭圆按钮）的液态玻璃。
 *
 * 与卡片同源 AGSL Shader，但使用【独立缓存 key / 独立 uniform 实例】——
 * 两边同时存在于屏幕上，共用实例会互相覆写 uniform。
 * 采样同一个捕获层（背景场景）；面板自身绝不进入捕获层。
 *
 * 参数取向：厚磨砂 + 弱折射 + 弱色散，保证面板文字可读且保留玻璃质感。
 * 形状由调用方以动画驱动（椭圆 → 面板），本函数只负责光学。
 */
fun Modifier.glassPanel(
    adapter: BackdropAdapter,
    shape: () -> Shape,
    cornerRadiiPx: () -> FloatArray,
    quality: () -> GlassQuality,
    /** 按压进度 0..1：收起态胶囊被按下时驱动玻璃局部形变。 */
    pressProgress: () -> Float = { 0f },
    /** 触点位置（元素局部 px）：玻璃跟随手指位置产生液态凸起。 */
    pressPositionPx: () -> Offset = { Offset.Zero },
    /** 触点凸起/凹陷强度（0 = 关闭）。 */
    deformationStrength: Float = 0f,
    /** 采样源：默认整页背景捕获层；传面板自身捕获层可让面板内玻璃采到面板内容（滑杆手柄）。 */
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
    /** HDR 高光增益（窗口处于 HDR 色彩模式时 > 1）。 */
    hdrBoost: () -> Float = { 1f },
    /**
     * 卡片那一套光学参数。收起按钮按下时直接复用它们，保证"按钮的液态玻璃"和
     * 主界面卡片的液态玻璃是同一种效果（此前按钮走的是手写参数块，观感不一样）。
     */
    parameters: () -> GlassParameters
): Modifier = this.drawBackdrop(
    backdrop = backdrop ?: adapter.captureLayer,
    shape = shape,
    highlight = null,
    shadow = null,
    effects = {
        // 探针：glassPanel 每次实际绘制都会打点（带尺寸），用来判定滑杆手柄的玻璃到底有没有执行。
        if (android.os.SystemClock.elapsedRealtime() - lgGPProbeMs > 1200L) {
            lgGPProbeMs = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("LGProbe", "glassPanel draw size=" + size.width.toInt() + "x" + size.height.toInt())
        }
        // 面板面积大（整屏下半），固定用 5t 档以降低 GPU 成本；
        // 面板本身是厚磨砂，采样档位的视觉差异几乎不可见。
        // 面板模糊档位：PERFORMANCE(5 抽头) 配 30dp 半径会出现环形伪影 ✗（"模糊不干净"的根因）
        // → 提到 BALANCED(9 抽头)。真机基线：面板滚动 0% 掉帧、Slow draw 0 ✓，有余量 ✓
        // 档位常量：源码/key 只取一次档位缓存（每档只拼一次），不进每帧路径
        val program = programOf(GlassQuality.QUALITY)   // 面板模糊档再提一级(13 抽头)：消"块状/网格"观感 ✓
        val d = density
        val qualityBlurFactor = 1.0f
        val refractionPx = 6f * d
        val dispersionPx = 1.3f * d
        val pad = max(refractionPx, dispersionPx) + 2f
        // 过采样必须覆盖"按压外扩"量，否则外扩后的边缘高光超出层边界被裁 ✗
        val pressInflatePad = 19f * d * pressProgress()   // 与纵向膨胀同量级，保证外扩绘制不被裁
        val pad2 = pad + pressInflatePad
        padding = pad

        runtimeShaderEffect(
            key = program.panelKey,
            shaderString = program.source,
            uniformShaderName = GlassUniforms.SHADER_INPUT
        ) {
            setFloatUniform(GlassUniforms.HDR_CEIL, 1.0f)   // 控制中心：压回 SDR 白点，彻底关掉 HDR 观感
            setFloatUniform(GlassUniforms.FINAL_DESAT, 0.6f)
            // ---- 几何 ----
            setFloatUniform(GlassUniforms.RESOLUTION, size.width + pad * 2f, size.height + pad * 2f)
            setFloatUniform(GlassUniforms.OFFSET, -pad, -pad)
            setFloatUniform(GlassUniforms.CARD_ORIGIN, 0f, 0f)
            setFloatUniform(GlassUniforms.CARD_SIZE, size.width, size.height)
            setFloatUniform(GlassUniforms.CORNER_RADII, cornerRadiiPx())
            setIntUniform(GlassUniforms.SHAPE_TYPE, 0)
            setFloatUniform(GlassUniforms.EDGE_ZONE, 34f * d)
            // ---- 光学：可看清背景的液态玻璃 ----
            // 面板要承载浅色文字，仍需自带一层暗底（背景若是亮天空，纯透明会让白字
            // 对比度崩塌）；因此保留较厚的深色材质层，但把"玻璃感"交给边缘：
            // 加宽的边缘区 + 更强的菲涅尔/边缘高光 + 明显的色散窄带 + 更清晰的模糊。
            // v1.18.0：用户反馈"透明度太高、看不清下面的字" → 加厚磨砂暗底：
            // 透射降到 0.60、深蓝材质填充提到 0.60、模糊提到 26dp，
            // 玻璃感仍由边缘（宽边缘区 + 强菲涅尔 + 色散窄带）承担
            // v1.23.1：用户要"白底 + 更不透明" → 透射降到 0.45、白色材质填充提到 0.74，
            // 面板变成一块明亮的磨砂白玻璃（文字改用深色，见 LightPanel 配色）
            val pe = adapter.panelExpansion().coerceIn(0f, 1f)
            val bp = adapter.buttonPress().coerceIn(0f, 1f)
            // 按钮态：静止=白底胶囊（材质 0.58）、按下=透明液态玻璃（材质 0.04、透射 0.96）；
            // 面板态沿用白磨砂底。两层插值用 pe 在末端混合。
            // 收起态 = 透明液态玻璃（用户明确："没有展开的时候就是一个透明的玻璃就行"）；
            // 按下时略微更透、色散更强。展开态（pe→1）保持当前白磨砂底，用户已确认满意。
            // 按钮态直接采用【演示卡片那一套】光学参数（同一个玻璃配方）：
            // 用户明确要求"按钮做回玻璃效果"，而卡片才是他认可的玻璃基准。
            val cp = parameters()
            setFloatUniform(GlassUniforms.BACKGROUND_TRANSMISSION, lerp(lerp(cp.backgroundTransmission, 0.97f, bp), 0.30f, pe))
            setFloatUniform(GlassUniforms.MATERIAL_OPACITY, lerp(lerp(cp.materialOpacity, cp.materialOpacity * 0.7f, bp), 0.05f, pe))
            // 染色同样按形态插值：收起态不叠色（浅蓝白 10% 是"奶白感"的主要来源之一，
            // 实测胶囊内部比同高度背景亮 +31，其中约 +20 来自这层染色）
            setFloatUniform(GlassUniforms.TINT_OPACITY, lerp(lerp(cp.tintOpacity, 0f, bp), 0f, pe))
            // 模糊半径也按形态插值：26dp 的模糊对"面板"刚好，对底部这么小的胶囊就太重，
            // 会把它糊成奶白雾面（视觉模型判为"偏雾面、不清透"）。收起态用 7dp 轻模糊，
            // 让背景纹理透过来 + 边缘折射/色散承担玻璃感。
            // 按钮态模糊对齐卡片的"高透明档"(2.5~3dp)：7dp 的模糊叠加薄白材质，会把亮而高对比的
            // 背景糊成奶白块（实测胶囊内部比两侧亮 +104，视觉判为"发白、接近纯白"）。
            // 【谷歌官方 API】平台真高斯模糊（BlurEffect，硬件加速、无采样瑕疵）
            // 替代我们 shader 里手写的 N 抽头螺旋采样（粗糙/摩尔纹的根源 ✗）
            blur(lerp(lerp(cp.blurRadiusDp, cp.blurRadiusDp, bp), 30f, pe) * d)
            setFloatUniform(GlassUniforms.BLUR_RADIUS, 30f * d * 0.05f)   // 面板：shader 侧少量半径
            // 【P64·动画期模糊降档】面板展开/收起动画期（pe∈(0,1)）13t 模糊跳过外环 4 抽头（默认开）。
            // 静止态 pe=0/1 ⇒ 0 = 原 13t 逐像素一致 ✓。回退：setSwitches animBlurLean 0（或 DebugSwitches 一行）。
            setFloatUniform(
                GlassUniforms.BLUR_LEAN,
                if (com.example.liquidglass.debug.DebugSwitches.animBlurLean && pe > 0.001f && pe < 0.999f) 1f else 0f
            )
            // 白底勾边强度按形态插值（仅面板态）：白高光在白底上无对比度 → 补一圈浅灰细线
            setFloatUniform(GlassUniforms.PANEL_RIM_DARKEN, 0f)   // 上游无 rim darken
            // 新分支：卡片也启用厚暗外圈（原来 panelRimDarken 只对面板态生效 → 卡片等于没加 ✗）
            setFloatUniform(GlassUniforms.PANEL_RIM_DARKEN, 0.85f)
            // 【审查修复①】面板此前从未上传 HDR_BOOST → Android 语义下未 set 的 primitive uniform = 0
            //   → shader 的 spec 被 max(hdrBoost,0)=0 乘成 0 → 面板贴边高光【整段消失】只剩压暗 ✓
            setFloatUniform(GlassUniforms.HDR_BOOST, hdrBoost())
            setFloatUniform(GlassUniforms.REFRACTION_OFFSET, refractionPx)
            setFloatUniform(GlassUniforms.REFRACTION_HEIGHT, lerp(50f, 95f, pe) * d)   // 同步夸张：50→95dp   // 加厚：原 26→50dp
            setFloatUniform(GlassUniforms.DISPERSION_OFFSET, dispersionPx)
            setFloatUniform(GlassUniforms.DISPERSION_EDGE_WIDTH, 12f * d)
            // 收起的小胶囊要"像玻璃"：色散更强、折射带更贴边
            setFloatUniform(GlassUniforms.DISPERSION_STRENGTH, lerp(lerp(cp.dispersionStrength, 0f, bp), 0f, pe))
            // 【P03·四角色散】面板路径【固定 gain=0 ⇒ 权重=1.0】= 色散逐像素不变（四角加权只属卡片）
            setFloatUniform(GlassUniforms.CORNER_DISPERSION_GAIN, 0f)
            setFloatUniform(GlassUniforms.FRESNEL_STRENGTH, lerp(cp.fresnelStrength, 0.95f, pe))
            setFloatUniform(GlassUniforms.EDGE_HIGHLIGHT_OPACITY, lerp(cp.edgeHighlightOpacity, 0.58f, pe))
            // ---- iOS 透镜模型（面板不参与按压形变，touchAmp/rimSoft 取 0）----
            setFloatUniform(GlassUniforms.LENS_FALLOFF, 2.0f)
            setFloatUniform(GlassUniforms.TOUCH_AMP, 0f)
            setFloatUniform(GlassUniforms.RIM_SOFT, 0f)
            setFloatUniform(GlassUniforms.LENS_PROFILE, 1f)
            // 面板继续用【加性材质填充】做承载浅色文字的稳定暗底，且不做自适应染色
            setFloatUniform(GlassUniforms.TINT_MODEL, 0f)
            setFloatUniform(GlassUniforms.ADAPTIVE_TINT, 0f)
            setFloatUniform(GlassUniforms.ADAPTIVE_LEGIBILITY, 0f)
            setFloatUniform(GlassUniforms.SATURATION, lerp(1f, 0.35f, pe))   // 面板降饱和：底下的湖蓝透上来会显蓝，降到 0.5 才是"偏灰"的磨砂
            setFloatUniform(GlassUniforms.LABEL_REGION, -1f, 0f, 0f, 0f)
            setIntUniform(GlassUniforms.DEBUG_MODE, 0)
            // 面板材质/色调：深蓝（承载浅色文字的稳定暗底）
            setColorUniform(GlassUniforms.TINT_COLOR, Color(0xFFECEDEF))   // 中性灰，不要再偏蓝
            setColorUniform(GlassUniforms.MATERIAL_COLOR, Color(0xFFC9CDD3))   // 中性偏灰：0xE0E2E5 实测面板均值 194 仍偏亮
        }
    }
)

/**
 * 液态张力桥：两张玻璃靠近时，在两卡之间的缝隙里出现的一段"真玻璃"。
 *
 * 与卡片/面板同源 AGSL（同一 program 文本，独立缓存 key → 独立 uniform 实例）。
 * 形状为胶囊（圆角 = 短边半径），由调用方按两卡位置给出长度/厚度/旋转；
 * 旋转走 graphicsLayer，不进 Shader。绘制顺序在两张卡片【之下】，
 * 因此只有两卡之间的缝隙会露出这段玻璃 —— 观感就是两块玻璃被液体张力粘住。
 *
 * 参数取向：与主卡一致的超透材质（透射 0.92 / 白色材质 0.03），
 * 保证桥与卡片看起来是同一种玻璃；色散同样克制。
 */
fun Modifier.glassBridge(
    adapter: BackdropAdapter,
    quality: () -> GlassQuality
): Modifier {
    // 跨帧复用的小对象：CORNER_RADII 数组只在半径变化时重写
    val cornerRadiiBuffer = CornerRadiiBuffer()
    return this.drawBackdrop(
    backdrop = adapter.captureLayer,
    shape = { RoundedCornerShape(50) },
    highlight = null,
    shadow = null,
    effects = {
        val program = programOf(GlassQuality.PERFORMANCE)
        val d = density
        val pad = 8f * d
        padding = pad
        runtimeShaderEffect(
            key = program.bridgeKey,
            shaderString = program.source,
            uniformShaderName = GlassUniforms.SHADER_INPUT
        ) {
            setFloatUniform(GlassUniforms.HDR_CEIL, 1.0f)   // 非演示玻璃：关掉 HDR
            setFloatUniform(GlassUniforms.FINAL_DESAT, 0.6f)
            setFloatUniform(GlassUniforms.FINAL_DESAT, 0.0f)
            setFloatUniform(GlassUniforms.RESOLUTION, size.width + pad * 2f, size.height + pad * 2f)
            setFloatUniform(GlassUniforms.OFFSET, -pad, -pad)
            setFloatUniform(GlassUniforms.CARD_ORIGIN, 0f, 0f)
            setFloatUniform(GlassUniforms.CARD_SIZE, size.width, size.height)
            // 复用缓冲：半径不变时不重写数组（setFloatUniform 会立刻拷进 shader）
            val r = size.height / 2f
            setFloatUniform(GlassUniforms.CORNER_RADII, cornerRadiiBuffer.write(r))
            setIntUniform(GlassUniforms.SHAPE_TYPE, 0)
            setFloatUniform(GlassUniforms.EDGE_ZONE, 16f * d)
            setFloatUniform(GlassUniforms.BACKGROUND_TRANSMISSION, 0.92f)
            setFloatUniform(GlassUniforms.MATERIAL_OPACITY, 0.03f)
            setFloatUniform(GlassUniforms.TINT_OPACITY, 0.0f)
            // 【谷歌官方 API】平台真高斯模糊（BlurEffect，硬件加速、无采样瑕疵）
            // 替代我们 shader 里手写的 N 抽头螺旋采样（粗糙/摩尔纹的根源 ✗）
            blur(10f * d)
            setFloatUniform(GlassUniforms.BLUR_RADIUS, 30f * d * 0.05f)   // 面板/桥：shader 侧保留少量半径
            setFloatUniform(GlassUniforms.REFRACTION_OFFSET, 9f * d)
            setFloatUniform(GlassUniforms.REFRACTION_HEIGHT, 54f * d)
            setFloatUniform(GlassUniforms.DISPERSION_OFFSET, 1.6f * d)
            setFloatUniform(GlassUniforms.DISPERSION_EDGE_WIDTH, 12f * d)
            setFloatUniform(GlassUniforms.DISPERSION_STRENGTH, 0.34f)
            // 【P03·四角色散】张力桥路径【固定 gain=0 ⇒ 权重=1.0】= 色散逐像素不变
            setFloatUniform(GlassUniforms.CORNER_DISPERSION_GAIN, 0f)
            setFloatUniform(GlassUniforms.FRESNEL_STRENGTH, 0.85f)
            setFloatUniform(GlassUniforms.EDGE_HIGHLIGHT_OPACITY, 0.26f)
            setFloatUniform(GlassUniforms.EDGE_SHADOW_OPACITY, 0.10f)
            setFloatUniform(GlassUniforms.LOCAL_DIMMING_OPACITY, 0.03f)
            setFloatUniform(GlassUniforms.SATURATION, 1.02f)
            setFloatUniform(GlassUniforms.LENS_FALLOFF, 2.0f)
            setFloatUniform(GlassUniforms.TOUCH_AMP, 0f)
            setFloatUniform(GlassUniforms.RIM_SOFT, 8f * d)
            setFloatUniform(GlassUniforms.TINT_MODEL, 1f)
            setFloatUniform(GlassUniforms.ADAPTIVE_TINT, 0f)
            setFloatUniform(GlassUniforms.SHAPE_INFLATE, 0f, 0f)
            setFloatUniform(GlassUniforms.LENS_PROFILE, 1f)
            setFloatUniform(GlassUniforms.PRESS_PROGRESS, 0f)
            setFloatUniform(GlassUniforms.PRESS_POSITION, 0f, 0f)
            setFloatUniform(GlassUniforms.DRAG_VELOCITY, 0f, 0f)
            setFloatUniform(GlassUniforms.STRETCH_DIRECTION, 0f, 0f)
            setFloatUniform(GlassUniforms.DEFORMATION_STRENGTH, 0f)
            setFloatUniform(GlassUniforms.TIME, 0f)
            setFloatUniform(GlassUniforms.ADAPTIVE_LEGIBILITY, 0f)
            setFloatUniform(GlassUniforms.LABEL_REGION, -1f, 0f, 0f, 0f)
            setIntUniform(GlassUniforms.DEBUG_MODE, 0)
            setColorUniform(GlassUniforms.TINT_COLOR, Color(0xFFBCC8E8))
            setColorUniform(GlassUniforms.MATERIAL_COLOR, Color(0xFFFFFBF5))
        }
    }
)
}
