package com.example.liquidglass.diamond

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 【3D 钻石演示 · 状态】yaw / pitch 姿态 + 光学参数 + 惯性，供演示页（DiamondDemoPage）与调试桥
 * （DiamondDemoBridge）共用。
 *
 * 设计要点（本项目铁律）：
 *  · 所有会被 Shader 每帧读的量（yaw/pitch/bounces/dispersion/ior/fresnel/debugMode/cut）都是
 *    Compose 状态 ⇒ 演示页在【绘制阶段（effects）】读它们 ⇒ 快照订阅自动触发图层重录，
 *    不需要任何 invalidate，也【不会】因为姿态变化触发组合阶段重组 ✓；
 *  · [rotRows] 只往调用方给的缓冲里写（✗ 不新建数组）——effects 里每帧都会调它；
 *  · 帧循环只在"真的在动"（自动旋转 或 惯性）时被唤醒（见 [animating]），静止期零开销。
 *
 * 姿态约定（与 AGSL 端 uRow0/uRow1/uRow2 对接；世界系 = x 右、y 上、z 朝相机 ——
 * 与 A 的 DiamondAgsl 同一套：模型 +y = 屏幕向上、相机 (0,0,uCamZ)）：
 * R = Rx(pitch) · Ry(yaw)（两个都是标准右手矩阵），行向量即模型→世界的三个基：
 * ```
 * row0 = (  cos y,           0,     sin y         )
 * row1 = (  sin p · sin y,   cos p, -sin p · cos y )
 * row2 = ( -cos p · sin y,   sin p,  cos p · cos y )
 * ```
 *  · yaw = pitch = 0 时是【单位矩阵】（= 台面正对相机，零位姿态由 AGSL 侧相机决定）；
 *  · 拖右 → 物体正面朝右转（= 手拨地球仪）；拖下 → 正面朝下转 = 顶面转向观察者（标准 trackball 手感）；
 *  · 语义上：yaw = 方位角（> 0 物体正面右转）、pitch = 抬升角（> 0 从上方俯视）；
 *  · pitch 钳制在 ±[PITCH_LIMIT_DEG]，yaw 归一化到 (-180, 180]。
 */
class DiamondDemoState {

    // ===================== 契约字段（父会话按此接线） =====================

    /** 演示页是否显示（屏幕层据此决定是否把演示页放进组合）。 */
    var visible: Boolean by mutableStateOf(false)

    /** 切工（AGSL 源码按它缓存：见 DiamondAgsl.source / DiamondAgsl.cacheKey）。 */
    var cut: DiamondCut by mutableStateOf(DiamondCut.entries.first())

    /** 内部弹射次数 0..4（0 = 只折一次）。 */
    var bounces: Int by mutableIntStateOf(DEFAULT_BOUNCES)

    /** 色散强度（0 = 关）。 */
    var dispersion: Float by mutableFloatStateOf(DEFAULT_DISPERSION)

    /** 折射率（钻石 = 2.417）。 */
    var ior: Float by mutableFloatStateOf(DEFAULT_IOR)

    /** 菲涅尔强度 0..1。 */
    var fresnel: Float by mutableFloatStateOf(DEFAULT_FRESNEL)

    /**
     * 环境采样模型（【本轮修复开关】）：
     *  · 1 = 几何一致环境（默认）—— 出射/反射射线与「背景平面 z=−uDepth / 它的 z=0 镜像平面」求交 + 逆投影，
     *        透射路径按 (1−R_内) 加权；表面反射 = 背景的真实镜像（唯一合法镜面）。
     *  · 0 = 旧版回退（744aef1 行为：朝上/退化射线用「方向×0.5」软投影、透射路径不打折）。
     *  调试桥 `setParams(envModel=0|1)` 可在真机上逐像素 A/B。
     */
    var envModel: Int by mutableIntStateOf(DEFAULT_ENV_MODEL)

    /**
     * 【H3 弹射用尽·物理收尾】开关（【本轮修复开关】）：
     *  · 1 = 补链 + 真界面透射率（默认）—— 弹射用尽后继续把光路走到真出射面，收尾能量用最后界面的
     *        (1−R) 而不是固定 0.5（消除"用尽/出射"边界上 ≈0.33 的亮度硬台阶 = 用户看到的横向分层带）。
     *  · 0 = 旧口径（744aef1/8100724 逐字行为：预算 = uBounces、用尽 ⇒ 0.5）。
     *  调试桥 `setParams(trappedFix=0|1)` 可在真机上逐像素 A/B。
     */
    var trappedFix: Int by mutableIntStateOf(DEFAULT_TRAPPED_FIX)

    /**
     * 【H4 逆投影 y 符号】开关（【本轮修复开关】）：
     *  · 1 = 世界点→屏幕的正确针孔投影（默认）—— py = CY − P.y·k·R，与 primaryDir 互为逆映射，
     *        也是 744aef1 平面口径/软投影的同号口径 ⇒ 内部回光与表面镜面不再上下颠倒。
     *  · 0 = H1 原样（y 双重取反：采样点关于屏幕水平中线镜像 —— 内部内容上下颠倒）。
     *  调试桥 `setParams(envYFix=0|1)` 可在真机上逐像素 A/B。
     */
    var envYFix: Int by mutableIntStateOf(DEFAULT_ENV_Y_FIX)

    /** 调试模式：0 = FINAL / 1 = 面法线 / 2 = 面 ID / 3 = 仅折射 / 4 = 背景直通。 */
    var debugMode: Int by mutableIntStateOf(0)

    /** 自动旋转开关。 */
    var autoSpin: Boolean by mutableStateOf(true)

    /** 自动旋转转速（度/秒，0 = 停）。 */
    var spinDegPerSec: Float by mutableFloatStateOf(DEFAULT_SPIN_DEG_PER_SEC)

    /**
     * 腰棱半径（px）：由演示页在【绘制阶段】每帧写入。
     * 刻意是普通 @Volatile 字段（✗ 非 Compose 状态）：写入不触发重绘/重组，只给 [dumpLine] 读数用。
     */
    @Volatile
    var lastRadiusPx: Float = 0f

    // ===================== 姿态（拖动 / 惯性 / 自动旋转） =====================

    /** 偏航角（度）：左右拖动改变它（绕屏幕竖直轴）。 */
    var yawDeg: Float by mutableFloatStateOf(0f)
        private set

    /** 俯仰角（度）：上下拖动改变它（绕屏幕水平轴），钳制 ±[PITCH_LIMIT_DEG]。 */
    var pitchDeg: Float by mutableFloatStateOf(0f)
        private set

    /** 惯性是否在作用中（Compose 状态：帧驱动的 snapshotFlow 靠它唤醒）。 */
    var inertiaActive: Boolean by mutableStateOf(false)
        private set

    /**
     * 空闲判定：自动旋转在转 或 惯性未衰减完 ⇒ 帧驱动需要逐帧推进。
     * 两路径都是 Compose 状态 ⇒ 帧驱动可以用 snapshotFlow 在空闲时挂起（零开销，本项目既有做法）。
     */
    val animating: Boolean
        get() = inertiaActive || (autoSpin && spinDegPerSec != 0f)

    /** 当前惯性角速度大小（度/秒）：只给日志 / [dumpLine] 用，不参与渲染。 */
    val inertiaSpeedDegPerSec: Float
        get() = hypot(inertiaYawPerSec, inertiaPitchPerSec)

    /** 拖动中：拖动期间不再叠加自动旋转（否则手指与自转互相打架）。非状态（拖动期不为此重组）。 */
    private var dragging: Boolean = false

    /** 惯性角速度（度/秒）：[endDrag] 后由 [tick] 积分并指数衰减。 */
    private var inertiaYawPerSec: Float = 0f
    private var inertiaPitchPerSec: Float = 0f

    /** 上一次拖动事件时刻（ns，单调时钟）：把"连续两次事件的位移"换算成度/秒。
     *  用 nanoTime 而不是 elapsedRealtime：后者只有 ms 分辨率，同毫秒内的两个事件会算出 dt=0。 */
    private var lastDragNanos: Long = 0L

    // ===================== 交互 =====================

    /**
     * 拖动：像素位移 → yaw / pitch 变化（k = [DEG_PER_PX] 度/px），同时记录瞬时速度供 [endDrag] 用。
     * 立即写姿态（不等下一帧）⇒ 手指与画面同帧跟手。
     */
    fun drag(dxPx: Float, dyPx: Float) {
        if (!dxPx.isFinite() || !dyPx.isFinite()) return          // NaN/Inf 直接忽略：别把姿态写坏
        dragging = true
        val dYaw = dxPx * DEG_PER_PX
        val dPitch = dyPx * DEG_PER_PX
        yawDeg = wrapDegrees(yawDeg + dYaw)
        pitchDeg = (pitchDeg + dPitch).coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG)

        // ---- 瞬时速度：指数平滑（单帧抖动不进惯性；极端甩动被限幅）----
        val now = System.nanoTime()
        val dtSeconds = if (lastDragNanos == 0L) Float.NaN else (now - lastDragNanos) / 1_000_000_000f
        lastDragNanos = now
        when {
            // 正常的事件间隔（1ms ~ 120ms）：平滑更新
            dtSeconds.isFinite() && dtSeconds in 0.001f..0.12f -> {
                val instYaw = dYaw / dtSeconds
                val instPitch = dPitch / dtSeconds
                inertiaYawPerSec =
                    (inertiaYawPerSec * 0.6f + instYaw * 0.4f).coerceIn(-MAX_INERTIA_DEG_PER_SEC, MAX_INERTIA_DEG_PER_SEC)
                inertiaPitchPerSec =
                    (inertiaPitchPerSec * 0.6f + instPitch * 0.4f).coerceIn(-MAX_INERTIA_DEG_PER_SEC, MAX_INERTIA_DEG_PER_SEC)
            }
            // 同一瞬间的多个事件（dt ≈ 0）：保留上一次估计（✗ 不把速度清零：否则甩完立刻松手会没惯性）
            dtSeconds.isFinite() && dtSeconds < 0.001f -> Unit
            // 首次事件 / 停顿（> 120ms）后重新开始：不带入停顿前的速度，否则"停一下再拖"会突然甩出去
            else -> {
                inertiaYawPerSec = 0f
                inertiaPitchPerSec = 0f
            }
        }
        // 拖动期间不做惯性积分（速度只在松手后生效）
        inertiaActive = false
    }

    /** 松手：按当前速度进入惯性；速度很小则不动。 */
    fun endDrag() {
        dragging = false
        lastDragNanos = 0L
        if (inertiaSpeedDegPerSec < MIN_INERTIA_DEG_PER_SEC) {
            inertiaYawPerSec = 0f
            inertiaPitchPerSec = 0f
            inertiaActive = false
        } else {
            inertiaActive = true
        }
    }

    /** 直接定格姿态（清速度）：给调试桥的 setRotation 用（固定姿态抓图 / A-B 对照）。 */
    fun setRotation(yawDeg: Float, pitchDeg: Float) {
        if (!yawDeg.isFinite() || !pitchDeg.isFinite()) return
        this.yawDeg = wrapDegrees(yawDeg)
        this.pitchDeg = pitchDeg.coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG)
        inertiaYawPerSec = 0f
        inertiaPitchPerSec = 0f
        inertiaActive = false
        dragging = false
        lastDragNanos = 0L
    }

    /** 设自动旋转转速（度/秒；0 = 停）。 */
    fun setSpin(degPerSec: Float) {
        if (!degPerSec.isFinite()) return
        spinDegPerSec = degPerSec.coerceIn(-DIAMOND_MAX_SPIN_DEG_PER_SEC, DIAMOND_MAX_SPIN_DEG_PER_SEC)
    }

    // ===================== 每帧 =====================

    /**
     * 填 out[0..8] = 旋转矩阵的三个【行向量】（world = (dot(row0, v), dot(row1, v), dot(row2, v))）。
     *
     * 调用方复用缓冲：本函数 ✗ 不新建数组、✗ 不分配对象 —— 它在绘制阶段（effects）每帧被调用。
     * 这里读 yawDeg / pitchDeg（Compose 状态）⇒ 调用点（effects）自动订阅，姿态变化即重录图层。
     */
    fun rotRows(out: FloatArray) {
        require(out.size >= 9) { "rotRows 需要至少 9 个元素（调用方复用缓冲；见 DiamondDemoPage）" }
        val yaw = yawDeg * DEG_TO_RAD
        val pitch = pitchDeg * DEG_TO_RAD
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        out[0] = cy
        out[1] = 0f
        out[2] = sy
        out[3] = sp * sy
        out[4] = cp
        out[5] = -sp * cy
        out[6] = -cp * sy
        out[7] = sp
        out[8] = cp * cy
    }

    /**
     * 每帧调用：惯性衰减 + 自动旋转（内部更新 yaw / pitch）。
     * dt 用真实帧间隔（调用方已 clamp ≤ 0.05s，这里再兜一层保险）。
     */
    fun tick(dtSeconds: Float) {
        if (!dtSeconds.isFinite() || dtSeconds <= 0f) return
        val dt = dtSeconds.coerceAtMost(MAX_TICK_DT_SECONDS)

        // ---- 惯性：积分 + 指数衰减（τ = INERTIA_TAU_SECONDS）----
        if (inertiaActive) {
            yawDeg = wrapDegrees(yawDeg + inertiaYawPerSec * dt)
            val nextPitch = pitchDeg + inertiaPitchPerSec * dt
            if (nextPitch > PITCH_LIMIT_DEG) {
                pitchDeg = PITCH_LIMIT_DEG
                inertiaPitchPerSec = 0f
            } else if (nextPitch < -PITCH_LIMIT_DEG) {
                pitchDeg = -PITCH_LIMIT_DEG
                inertiaPitchPerSec = 0f
            } else {
                pitchDeg = nextPitch
            }
            val decay = exp(-dt / INERTIA_TAU_SECONDS)
            inertiaYawPerSec *= decay
            inertiaPitchPerSec *= decay
            if (inertiaSpeedDegPerSec < MIN_INERTIA_DEG_PER_SEC) {
                inertiaYawPerSec = 0f
                inertiaPitchPerSec = 0f
                inertiaActive = false
            }
        }

        // ---- 自动旋转（拖动期间不叠加）----
        if (autoSpin && !dragging) {
            yawDeg = wrapDegrees(yawDeg + spinDegPerSec * dt)
        }
    }

    /** 状态一行（visible / yaw / pitch / spin / 惯性速度 / bounces / dispersion / ior / envModel / 切工 / 面数 / 半径 px）。 */
    fun dumpLine(): String = String.format(
        Locale.US,
        "visible=%s yaw=%.2f pitch=%.2f spin=%.1f inertia=%.1f bounces=%d dispersion=%.4f ior=%.3f env=%d trapped=%d envY=%d cut=%s faces=%d radius=%.1fpx",
        if (visible) "on" else "off",
        yawDeg,
        pitchDeg,
        spinDegPerSec,
        inertiaSpeedDegPerSec,
        bounces,
        dispersion,
        ior,
        envModel,
        trappedFix,
        envYFix,
        cut.name,
        cut.facetCount,
        lastRadiusPx
    )
}

/** 弹射次数上限（AGSL 侧 uBounces ≤ 4）。 */
const val DIAMOND_MAX_BOUNCES: Int = 4

/** 调试模式上限（0..4）。 */
const val DIAMOND_MAX_DEBUG_MODE: Int = 4

/** 自动旋转转速上限（度/秒）：setSpin 与调试桥的 coerceIn 边界。 */
const val DIAMOND_MAX_SPIN_DEG_PER_SEC: Float = 360f

/** 色散上限（调试桥的 coerceIn 边界；默认值 0.012 的 4 倍）。 */
const val DIAMOND_MAX_DISPERSION: Float = 0.05f

/** 折射率范围（调试桥的 coerceIn 边界；钻石 2.417 居中）。 */
const val DIAMOND_MIN_IOR: Float = 1f
const val DIAMOND_MAX_IOR: Float = 3f

// ---- 默认值（契约：弹射 2 / 色散 0.012 / 折射率 2.417 / 菲涅尔 1 / 自动旋转 开·24°/s）----
private const val DEFAULT_BOUNCES = 2
private const val DEFAULT_DISPERSION = 0.012f
private const val DEFAULT_IOR = 2.417f
private const val DEFAULT_FRESNEL = 1f
private const val DEFAULT_SPIN_DEG_PER_SEC = 24f

/** 环境采样模型默认值 = 1（几何一致环境；【修复开关】0 = 旧软投影回退）。 */
private const val DEFAULT_ENV_MODEL = 1

/** 【H3】弹射用尽物理收尾默认值 = 1（补链 + 真界面透射率；0 = 旧 0.5 回退）。 */
private const val DEFAULT_TRAPPED_FIX = 1

/** 【H4】逆投影 y 符号默认值 = 1（正确投影；0 = H1 原样 y 取反回退）。 */
private const val DEFAULT_ENV_Y_FIX = 1

/** 拖动灵敏度：度/像素（契约建议值 0.35）。 */
private const val DEG_PER_PX = 0.35f

/** 俯仰限幅（度）：到边界即停，避免拖出"翻过去"的观感（也不让参数化在 ±90° 退化）。 */
private const val PITCH_LIMIT_DEG = 89f

/** 惯性衰减时间常数（秒）：exp(-dt/τ)，τ 越大滑得越久。 */
private const val INERTIA_TAU_SECONDS = 0.45f

/** 松手后低于这个角速度就不进入惯性（"速度很小则不动"）。 */
private const val MIN_INERTIA_DEG_PER_SEC = 12f

/** 惯性角速度上限（度/秒）：挡住极端甩动一帧跳几百度。 */
private const val MAX_INERTIA_DEG_PER_SEC = 2400f

/** 单帧最大步长（秒）：调用方一般已 clamp（0.05s），这里再兜一层。 */
private const val MAX_TICK_DT_SECONDS = 0.05f

/** 度 → 弧度。 */
private val DEG_TO_RAD: Float = (PI / 180.0).toFloat()

/** 把角度归一化到 (-180, 180]：长时间自动旋转也不会让 float 精度退化。 */
private fun wrapDegrees(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** 屏幕层持有：`val state = rememberDiamondDemoState()`。 */
@Composable
fun rememberDiamondDemoState(): DiamondDemoState = remember { DiamondDemoState() }
