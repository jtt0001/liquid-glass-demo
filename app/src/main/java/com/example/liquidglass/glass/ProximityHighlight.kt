package com.example.liquidglass.glass

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 【P08 · 邻近提亮（能量传递）】—— 新增文件（未入库前不依赖任何其它在改文件）。
 *
 * 用户原话（NEXT.md 175-190 第 6 条）：
 *   『新增效果：两块玻璃离得够近时，在两者最近距离处产生【额外的提亮效果】（类似"能量传递"）。』
 *
 * 机制（最小侵入，自成一档）：
 *   · 几何：两卡【可见矩形】之间的最近点对（与 DebugBridge/dumpState 的 card rect 同一真值源：
 *     默认落点 + offset + cardSizePx；P22 开启时该矩形 = 屏幕上玻璃的可见矩形 ✓）。
 *     最近点 pa∈A、pb∈B ⇒ 提亮中心 = 连线中点 mid（这就是"两者最近距离处"）。
 *   · 提亮：以 mid 为圆心的柔和白色径向渐变（半径 radiusPx = 【最大作用半径】，峰值 alpha = maxAlpha × intensity），
 *     画在两卡【之上】（不参与玻璃采样管线 ⇒ 绝不影响折射/菲涅尔/背景捕获/P07/P12 的任何取值）。
 *   · 强度：intensity = ((threshold - gap) / threshold)^1.2 ∈ [0,1] ⇒ 间距越小越亮（单调递增）；
 *     gap ≥ threshold ⇒ 恒 0（阈值外【不绘制任何东西】⇒ 无提亮 ✓）；
 *     两卡二维相交（真穿透/叠放）时按穿透深度 0→24px 淡出到 0（与 P12 的 PEN_FADE_PX 同口径）：
 *     贴合/重叠 ⇒ 提亮收敛到 0，形态交给 P07/P12 的 meld / 融合渲染 ⇒ 【不会把重叠区糊成一片白】✓。
 *
 * 【总开关】DebugSwitches.nearGlow —— 【默认开】（P08 交付口径）；enabled=false 时本模块不进入绘制，
 *   逐像素、零成本与改动前一致（单卡 / 双卡远离 / 双卡靠近三种情形都是）✓。
 *   一行回退：`adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
 *     --es name nearGlow --ei value 0 -p com.liqglass.ultraclear`
 *   （跨进程：冷启动初值读文件 `files/lg_near_glow_off`（内容 "1" = 关；无此文件 = 默认开）。）
 *
 * 【逐帧取证开关】DebugSwitches.nearGlowTrace（默认关）：
 *   · 关（默认）：只在【开始提亮 / 停止提亮】两种状态跳变时各打一行（日常零刷屏 ✓）；
 *   · 开：每帧一行（含阈值外/idle 帧的 gap）⇒ gap 扫描可逐点读数。
 *   一行开：`adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
 *     --es name nearGlowTrace --ei value 1 -p com.liqglass.ultraclear`（tag = LGNearGlow）。
 *
 * 三个 float 参数（照 DebugSwitches.readSwitchFile 先例：常量默认值 + 应用私有目录文件覆写）：
 *   · thresholdPx 【无覆写文件时 = 跟随 P12 的 ProximityFusion.thresholdPx】（默认 88px ⇒ 与 P12 默认档
 *     逐值相同）—— 两卡"够近"只有一套口径：`setFusion --ef threshold <px>` 一处改，P07/P08/P12 一起变 ✓；
 *     要单独给提亮换阈值时才写覆写文件 `files/lg_near_glow_threshold`；
 *   · maxAlpha    默认 0.20（gap→0 时的峰值不透明度）—— 覆写文件 `files/lg_near_glow_alpha`；
 *   · radiusPx    默认 140px（柔光半径）—— 覆写文件 `files/lg_near_glow_radius`。
 *   覆写文件在【每次 DebugBridge.revision 变化时】重读（＝发一条 setUi/setPos/setSwitches 命令即生效，
 *   ✗ 不需要重启）：`adb shell run-as com.liqglass.ultraclear sh -c 'echo 0.3 > files/lg_near_glow_alpha'`。
 *
 * 逐帧日志（tag LGNearGlow；只在开关开 且 提亮活跃时打印，且限流：间距变化 ≥0.5px 或 ≥1s 一行）：
 *   `LGNearGlow t=… gap=… pen=… i=… alpha=… thr=… r=… mid=(x,y) pa=… pb=…`
 */
object ProximityHighlight {

    /** logcat tag（逐帧日志与自检都用它）。 */
    const val TAG = "LGNearGlow"

    // ================= 参数：常量默认值 + 可选落盘覆写 =================

    private const val PKG_FILES = "/data/data/com.liqglass.ultraclear/files"
    const val FILE_THRESHOLD = "lg_near_glow_threshold"
    const val FILE_ALPHA = "lg_near_glow_alpha"
    const val FILE_RADIUS = "lg_near_glow_radius"

    /** 触发提亮的间距阈值默认值（px）：与 P12 proximityThreshold 默认值同口径。 */
    const val DEFAULT_THRESHOLD_PX = 88f

    /** gap → 0 时的峰值不透明度默认值（0..1）。 */
    const val DEFAULT_MAX_ALPHA = 0.20f

    /** 柔光半径默认值（px）。 */
    const val DEFAULT_RADIUS_PX = 140f

    /** 径向衰减指数：alpha(t) = peak × (1-t)^exp（t = r / radius）。 */
    const val FALLOFF_EXP = 1.7f

    /** 强度曲线指数：intensity = ((thr-gap)/thr)^exp。 */
    const val INTENSITY_EXP = 1.2f

    /** 二维相交（真穿透）时的淡出距离（px，与 P12 的 PEN_FADE_PX 同口径）。 */
    const val PEN_FADE_PX = 24f

    /** 低于本强度视为"不提亮"（不绘制、不打印）。 */
    const val MIN_DRAW_INTENSITY = 0.002f

    const val THRESHOLD_MIN = 8f
    const val THRESHOLD_MAX = 400f
    const val ALPHA_MIN = 0.005f
    const val ALPHA_MAX = 0.6f
    const val RADIUS_MIN = 24f
    const val RADIUS_MAX = 400f

    private fun readFloatFile(name: String, def: Float): Float = runCatching {
        java.io.File("$PKG_FILES/$name").readText().trim().toFloat()
    }.getOrDefault(def)

    /** 同 [readFloatFile]，但文件不存在/非法 ⇒ null（= 未覆写，用于"跟随 P12"的阈值）。 */
    private fun readFloatFileOrNull(name: String): Float? = runCatching {
        val f = java.io.File("$PKG_FILES/$name")
        if (!f.exists()) null else f.readText().trim().toFloat()
    }.getOrNull()

    /** 落盘覆写值（null = 无覆写 ⇒ 阈值跟随 P12）。 */
    @Volatile
    private var overrideThresholdPx: Float? = null

    /**
     * 提亮触发间距（px）：有覆写文件 ⇒ 用文件值；否则【跟随 P12】[ProximityFusion.thresholdPx]
     * （唯一真值；默认 88px ⇒ 与 P12 默认档逐值相同）⇒ 两卡"够近"不各算一套 ✓。
     */
    val thresholdPx: Float get() = overrideThresholdPx ?: ProximityFusion.thresholdPx

    /** 阈值来源（日志/取证用："file" = 本模块覆写文件；"p12" = 跟随 ProximityFusion.thresholdPx）。 */
    val thresholdSource: String get() = if (overrideThresholdPx != null) "file" else "p12"

    /** 峰值不透明度（0..1）。 */
    @Volatile
    var maxAlpha: Float = DEFAULT_MAX_ALPHA
        private set

    /** 柔光半径（px）= 最大作用半径。 */
    @Volatile
    var radiusPx: Float = DEFAULT_RADIUS_PX
        private set

    /** 重读落盘覆写（应用启动 + 每次 DebugBridge.revision 变化时调用；文件不存在 ⇒ 保持常量默认值）。 */
    fun reloadOverrides() {
        overrideThresholdPx = readFloatFileOrNull(FILE_THRESHOLD)?.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        maxAlpha = readFloatFile(FILE_ALPHA, DEFAULT_MAX_ALPHA).coerceIn(ALPHA_MIN, ALPHA_MAX)
        radiusPx = readFloatFile(FILE_RADIUS, DEFAULT_RADIUS_PX).coerceIn(RADIUS_MIN, RADIUS_MAX)
    }

    fun describe(): String = String.format(
        Locale.US, "nearGlow threshold=%.1fpx(源=%s) alpha=%.3f radius=%.1fpx",
        thresholdPx, thresholdSource, maxAlpha, radiusPx
    )

    init {
        reloadOverrides()
        Log.i(TAG, "init：${describe()}")
    }

    // ================= 几何：两卡可见矩形之间的最近点对 =================

    /**
     * 最近点对结果（窗口 px 坐标）。
     *  · gap  = 两矩形最近点距离（对角分离时 = 角点距离，与视觉一致）；
     *  · pa/pb = 最近点（pa ∈ A、pb ∈ B）；
     *  · mid  = 连线中点 = 提亮中心（"两者最近距离处"）；
     *  · pen  = 二维相交（真穿透）深度 px，0 = 不穿透。
     */
    data class Nearest(
        val gap: Float,
        val pa: Offset,
        val pb: Offset,
        val mid: Offset,
        val pen: Float
    )

    /** 卡片可见矩形（窗口 px）：默认左上角 + 偏移 + 尺寸 —— 与 DebugBridge.Cards.rect 同源。 */
    fun rectOf(defaultTopLeft: Offset, offsetX: Float, offsetY: Float, size: IntSize): Rect? {
        if (size.width <= 0 || size.height <= 0) return null
        val l = defaultTopLeft.x + offsetX
        val t = defaultTopLeft.y + offsetY
        return Rect(l, t, l + size.width, t + size.height)
    }

    /**
     * 两矩形的最近点对 + 中心（与 P12 geometry() 同一套 AABB 口径：轴向重叠段取覆盖区间中点）。
     * 退化（尺寸 ≤0 / 非有限）⇒ null。
     */
    fun nearestPointPair(a: Rect, b: Rect): Nearest? {
        if (a.width <= 0f || a.height <= 0f || b.width <= 0f || b.height <= 0f) return null
        if (!a.left.isFinite() || !a.top.isFinite() || !b.left.isFinite() || !b.top.isFinite()) return null

        val dx = max(a.left - b.right, b.left - a.right)
        val dy = max(a.top - b.bottom, b.top - a.bottom)
        val gap = hypot(max(dx, 0f), max(dy, 0f))

        val bRightOfA = b.left >= a.right
        val aRightOfB = a.left >= b.right
        val pax = when {
            bRightOfA -> a.right
            aRightOfB -> a.left
            else -> (max(a.left, b.left) + min(a.right, b.right)) * 0.5f
        }
        val pbx = when {
            bRightOfA -> b.left
            aRightOfB -> b.right
            else -> pax
        }
        val bBelowA = b.top >= a.bottom
        val aBelowB = a.top >= b.bottom
        val pay = when {
            bBelowA -> a.bottom
            aBelowB -> a.top
            else -> (max(a.top, b.top) + min(a.bottom, b.bottom)) * 0.5f
        }
        val pby = when {
            bBelowA -> b.top
            aBelowB -> b.bottom
            else -> pay
        }

        val pa = Offset(pax, pay)
        val pb = Offset(pbx, pby)
        val pen = if (dx < 0f && dy < 0f) min(-dx, -dy) else 0f
        return Nearest(gap, pa, pb, Offset((pax + pbx) * 0.5f, (pay + pby) * 0.5f), pen)
    }

    /** 强度 0..1：间距越小越亮（单调）；≥ 阈值 或 二维相交穿透 ≥ PEN_FADE_PX ⇒ 0。 */
    fun intensity(gap: Float, pen: Float): Float {
        val thr = thresholdPx
        if (thr <= 0f) return 0f
        val u = ((thr - gap) / thr).coerceIn(0f, 1f)
        if (u <= 0f) return 0f
        val fade = 1f - (pen / PEN_FADE_PX).coerceIn(0f, 1f)
        if (fade <= 0f) return 0f
        return u.pow(INTENSITY_EXP) * fade
    }

    /** 一帧的提亮参数（供日志/取证；不参与绘制决策之外的事）。 */
    data class GlowFrame(
        val gap: Float,
        val pen: Float,
        val intensity: Float,
        val peakAlpha: Float,
        val radius: Float,
        val mid: Offset,
        val pa: Offset,
        val pb: Offset
    )

    /** 由最近点对现算一帧 ⇒ null 表示该帧不提亮（阈值外 / 退化 / 强度低于门限）。 */
    fun frameOf(n: Nearest?): GlowFrame? {
        if (n == null) return null
        val i = intensity(n.gap, n.pen)
        if (i <= MIN_DRAW_INTENSITY) return null
        return GlowFrame(n.gap, n.pen, i, maxAlpha * i, radiusPx, n.mid, n.pa, n.pb)
    }

    /** 现算一帧（窗口 px 坐标下）⇒ null 表示该帧不提亮。 */
    fun frame(a: Rect, b: Rect): GlowFrame? = frameOf(nearestPointPair(a, b))

    /** 径向渐变颜色停靠点：peak × (1-t)^FALLOFF_EXP（t = r / radius）。 */
    fun gradientStops(peakAlpha: Float, steps: Int = 6): Array<Pair<Float, Color>> =
        Array(steps + 1) { k ->
            val t = k.toFloat() / steps
            t to Color.White.copy(alpha = (peakAlpha * (1f - t).pow(FALLOFF_EXP)).coerceIn(0f, 1f))
        }

    // ================= 日志（默认只在状态跳变时打点；逐帧档 = nearGlowTrace） =================

    private var lastActive = false
    private var lastTrace = false

    /**
     * 日志：
     *  · trace=false（默认）：只在【开始提亮 / 停止提亮】两种状态跳变时各打一行 ⇒ 日常零刷屏 ✓；
     *  · trace=true（`setSwitches nearGlowTrace 1`，逐帧取证档）：每帧一行（含阈值外/idle 帧的 gap）
     *    ⇒ gap 扫描可逐点读数。
     * 行格式（tag = [TAG]，可直接 grep；idle 与 frame 两种首词）：
     *  `frame t=<ms> gap=.. pen=.. i=.. alpha=.. thr=..(源=..) r=.. mid=(x,y) pa=(x,y) pb=(x,y)`
     *  `idle  t=<ms> gap=.. pen=.. thr=..(源=..) r=.. trace=..`
     */
    fun logFrame(f: GlowFrame?, n: Nearest?, trace: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) {
        val active = f != null
        val transition = active != lastActive
        val backToDefault = lastTrace && !trace          // 逐帧档 → 默认档时补打一次当前状态
        lastActive = active
        lastTrace = trace
        if (!trace && !transition && !backToDefault) return
        if (f == null) {
            Log.i(
                TAG,
                String.format(
                    Locale.US, "idle t=%d gap=%.1f pen=%.1f thr=%.1f(源=%s) r=%.1f trace=%s",
                    nowMs, n?.gap ?: -1f, n?.pen ?: -1f, thresholdPx, thresholdSource, radiusPx, trace
                )
            )
            return
        }
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "frame t=%d gap=%.1f pen=%.1f i=%.3f alpha=%.4f thr=%.1f(源=%s) r=%.1f " +
                    "mid=(%.1f,%.1f) pa=(%.1f,%.1f) pb=(%.1f,%.1f)",
                nowMs, f.gap, f.pen, f.intensity, f.peakAlpha, thresholdPx, thresholdSource, f.radius,
                f.mid.x, f.mid.y, f.pa.x, f.pa.y, f.pb.x, f.pb.y
            )
        )
    }

    // ================= 绘制（唯一新 UI：一层柔光，画在两卡之上） =================

    /**
     * 邻近提亮叠层：填满父容器，在【最近点连线中点】画一层柔和白光。
     *
     * @param enabled  总开关（DebugSwitches.nearGlow【默认开】 && 双卡）；false ⇒ 本组合完全不进入组合树 ✓（零回归）
     * @param trace    逐帧取证档（DebugSwitches.nearGlowTrace，默认关）：false ⇒ 只在状态跳变时打点；true ⇒ 每帧一行
     * @param revision DebugBridge.revision：变化时重读落盘覆写（发一条 debug 命令即生效，无需重启）
     * @param rectA/rectB 两卡可见矩形（【绘制期】读取 ⇒ 拖动时只重绘、不重组）
     */
    @Composable
    fun NearGlowOverlay(
        enabled: Boolean,
        trace: Boolean,
        revision: Int,
        rectA: () -> Rect?,
        rectB: () -> Rect?,
        modifier: Modifier = Modifier
    ) {
        if (!enabled) return
        remember(revision) { reloadOverrides() }
        Canvas(modifier = modifier.fillMaxSize()) {
            val a = rectA() ?: return@Canvas
            val b = rectB() ?: return@Canvas
            val n = nearestPointPair(a, b)
            val f = frameOf(n)
            logFrame(f, n, trace)
            if (f == null) return@Canvas
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = gradientStops(f.peakAlpha),
                    center = f.mid,
                    radius = f.radius
                ),
                radius = f.radius,
                center = f.mid
            )
        }
    }
}
