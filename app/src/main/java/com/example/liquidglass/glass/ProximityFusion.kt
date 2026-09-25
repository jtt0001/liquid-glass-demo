package com.example.liquidglass.glass

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.example.liquidglass.ui.GlassCardState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.flow.collectLatest

/**
 * 【P12 · 邻近流体融合（备选方案 B）】—— 邻近检测 + 边缘流体形变模块（新增文件）。
 *
 * 用户需求（逐条落实）：
 *  1. 距离逻辑：间距缩小到 proximityThreshold 内 ⇒ 接触面边缘互相拉扯 / 浸润融合（融合区随贴近变大）；
 *     完全贴合 ⇒ 两块玻璃轮廓连成一体；互相拉开 ⇒ 中间短暂拉出细水丝、随后断裂分离。
 *  2. 光学保持：融合区【仍然是玻璃本体】—— 本模块只把「颈部/细丝」并入 Shader 的 SDF 与法线场
 *     （见 FusionShaders 的注入片段），折射 / 菲涅尔 / 背景采样 / 边缘高光全部由现有管线原样复用，
 *     绝不填充纯色；薄水膜照样有轻微光学畸变（其法线来自联合场，折射自动跟随）。
 *  3. 形态：两主体仍是固体玻璃（形状 / 尺寸 / 交互一律不动），只有接触面边缘的场被改写；不做水滴。
 *  4. 参数：proximityThreshold / surfaceTensionStrength / filamentLifetime —— 常量默认值 + 可选落盘覆写
 *     （照 DebugSwitches.readSwitchFile 先例）；总开关 = DebugSwitches.proximityFusion（默认开）。
 *  5. 动画：帧率无关的指数平滑（p += (target-p)(1-exp(-dt/tau))，dt 上限 25ms ⇒ 单帧最大步进 ≤15% 全量程），
 *     位置瞬变（setPos / 手势）也不会让融合区跳变。
 *
 * 为什么除了 smooth-min 还要一个「颈部条」（以及 2026-09-14 的口径修订）：
 *   · 逼近相（0 < gap < 阈值）：融合形态【全部由 smooth-min 联合场 k 承担】——两条【平行对边】之间
 *     最短距离处处相同 ⇒ 融合沿【整条边】同时出现（先零厚度缝、再连续增厚；k>2g 出缝、k≥4g 完全充填、
 *     k≤2.4g 保证不伸出轮廓之外）。✗ 不再在逼近期架「最近点之间的局部颈部/点桥」（用户看完关键帧
 *     后的原话：『两条平行边应该是一整条边都吸附进去』）。算法唯一真值 = [FusionWeld]（与 P07 meld 共用）。
 *   · 贴合相（gap ≤ CONTACT_EPS_PX）：喉部条（圆角矩形 SDF，复用 sdRoundedRect）参与 —— 它的沿边半长
 *     被夹到真正的「对边」半长（geo.span/2）⇒ 轮廓正好连成一条、不伸出小凸包；半径随融合进度平滑增长
 *     （0 → 对边半长），拉开时半径按 filamentLifetime 收缩到 0 ⇒ 断裂（细水丝相，官方口径里没有，
 *     默认被 P07 meld 取代）。
 *   · 叠放/贴合（gap ≤ 0，含二维重叠）：吸附融合权重【严格 0】—— k / 喉部 r / 细丝一律当场归零
 *     （不留残量：旧的「静止保持」分支会把残余颈部留在屏幕上 = 吸附形变 ✗）；轮廓由几何并集给出，
 *     渲染层按 iOS 叠放口径绘制（见 DualCardMeld 的并集 + 归属划分）。
 *   两者都并入同一个 SDF/法线场 ⇒ 融合区是玻璃本体的一段，光学零新增。
 *
 * 逐帧日志（tag LGFusion；融合活跃期逐帧、静止时静默；可导 CSV 算缝厚/丝宽曲线）：
 *   LGFusion t=<elapsedRealtimeMs> gap=<px> thr=<px> fuse=<0..1> k=<px> seam=<px> neck=<px> strand=<px> state=<…>
 *   · k = 整条边焊缝的 smooth-min 半径；seam = 缝的横断厚度 px（= 2√(k(k−2g)/12)，k≤2g 时 0）；
 *   · neck = 2×喉部半径（贴合相轮廓连接宽度）；strand = 2×丝半径（仅 STRETCH 期 >0）；
 *   · gap ≤ 0（叠放/贴合）行也在日志里给（k/seam/neck/strand 全 0 = 「叠放态权重=0」的机械证据 ✓）；
 *   · state ∈ IDLE / APPROACH / MERGED / STRETCH / BROKEN（定义见 [FuseState]）。
 */
object ProximityFusion {

    /** logcat tag（逐帧日志与注入自检都用它）。 */
    const val TAG = "LGFusion"

    // ================= 三参数：常量默认值 + 可选落盘覆写 =================

    /** 落盘覆写目录（应用私有目录；与 DebugSwitches.readSwitchFile 同一先例）。 */
    private const val PKG_FILES = "/data/data/com.liqglass.ultraclear/files"
    const val FILE_THRESHOLD = "lg_fusion_threshold"
    const val FILE_TENSION = "lg_fusion_tension"
    const val FILE_FILAMENT = "lg_fusion_filament_ms"

    /** proximityThreshold 默认值（px；触发吸附融合的间距阈值）。 */
    const val DEFAULT_THRESHOLD_PX = 88f

    /** surfaceTensionStrength 默认值（1.0 = 标准；缩放融合区厚度与弯月面强度）。 */
    const val DEFAULT_TENSION = 1.0f

    /** filamentLifetime 默认值（ms；分离后水丝持续时间）。 */
    const val DEFAULT_FILAMENT_MS = 800f

    const val THRESHOLD_MIN = 8f
    const val THRESHOLD_MAX = 180f
    const val TENSION_MIN = 0.1f
    const val TENSION_MAX = 3f
    const val FILAMENT_MIN = 80f
    const val FILAMENT_MAX = 4000f

    private fun readFloatFile(name: String, def: Float): Float = runCatching {
        java.io.File("$PKG_FILES/$name").readText().trim().toFloat()
    }.getOrDefault(def)

    /** 触发吸附融合的距离阈值（px）。 */
    @Volatile
    var thresholdPx: Float =
        readFloatFile(FILE_THRESHOLD, DEFAULT_THRESHOLD_PX).coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        private set

    /** 表面张力吸附强度（1.0 = 标准）。 */
    @Volatile
    var tension: Float =
        readFloatFile(FILE_TENSION, DEFAULT_TENSION).coerceIn(TENSION_MIN, TENSION_MAX)
        private set

    /** 分离时水丝持续时长（ms）。 */
    @Volatile
    var filamentMs: Float =
        readFloatFile(FILE_FILAMENT, DEFAULT_FILAMENT_MS).coerceIn(FILAMENT_MIN, FILAMENT_MAX)
        private set

    /** 运行时改参（debug 命令 setFusion；立刻生效，无需重启）。 */
    fun setThreshold(v: Float) {
        thresholdPx = v.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
    }

    fun setTension(v: Float) {
        tension = v.coerceIn(TENSION_MIN, TENSION_MAX)
    }

    fun setFilamentMs(v: Float) {
        filamentMs = v.coerceIn(FILAMENT_MIN, FILAMENT_MAX)
    }

    /** 把当前三参数落盘（重启后仍生效）；返回写入结果说明。 */
    fun persistOverrides(threshold: Float?, tensionValue: Float?, filament: Float?): String {
        val dir = java.io.File(PKG_FILES)
        val wrote = ArrayList<String>(3)
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            if (threshold != null) {
                java.io.File(dir, FILE_THRESHOLD).writeText(threshold.toString())
                wrote.add("$FILE_THRESHOLD=${java.io.File(dir, FILE_THRESHOLD).readText().trim()}")
            }
            if (tensionValue != null) {
                java.io.File(dir, FILE_TENSION).writeText(tensionValue.toString())
                wrote.add("$FILE_TENSION=${java.io.File(dir, FILE_TENSION).readText().trim()}")
            }
            if (filament != null) {
                java.io.File(dir, FILE_FILAMENT).writeText(filament.toString())
                wrote.add("$FILE_FILAMENT=${java.io.File(dir, FILE_FILAMENT).readText().trim()}")
            }
        }.onFailure { wrote.add("落盘失败：${it.javaClass.simpleName}: ${it.message}") }
        return if (wrote.isEmpty()) "（未落盘）" else wrote.joinToString(" ")
    }

    fun describe(): String = String.format(
        Locale.US,
        "threshold=%.1fpx tension=%.2f filament=%.0fms",
        thresholdPx, tension, filamentMs
    )

    // ================= 视觉/物理常量（模拟器实测标定） =================

    /** 判定「完全贴合」的间距（px）。 */
    private const val CONTACT_EPS_PX = 3f

    /** 指数平滑时间常数（ms）：帧率无关，dt 上限见 DT_STEP_MAX_MS。 */
    private const val SMOOTH_TAU_MS = 150f

    /** 单帧有效 dt 上限（ms）：保证 α = 1-exp(-dt/tau) ≤ 15.4% ⇒ 单帧步进 ≤ 全量程 15.4%（验收要求 ≤25%）。 */
    private const val DT_STEP_MAX_MS = 25f

    /** 判定「在靠近 / 在拉开」的间距速度阈值（px/s）。 */
    private const val VEL_EPS_PX_S = 8f

    /** 颈部半径随融合进度的增长指数 —— 【已废止】逼近期不再产出喉部条（形态改由 FusionWeld 的整条边缝承担；
     *  喉部条只在贴合/拉丝相按 rMax 定值出现）。常量已删除，避免留下"看起来还在用"的死值。 */

    /** 全贴合时颈部半径的额外余量（px）⇒ 轮廓连成一体、不留 1~2px 台阶。 */
    private const val NECK_R_EXTRA_PX = 10f

    /** 颈部条两端伸进卡内的最小长度（px）。 */
    private const val NECK_OVERLAP_MIN_PX = 60f

    /** 颈部条两端伸进卡内的长度与颈部半径的比例。 */
    private const val NECK_OVERLAP_R_FRAC = 0.6f

    /** fuseK 上限 = 比例 × 较短卡的横向半高（再 clamp 到 [K_MIN,K_MAX]）。 */
    private const val K_MAX_FRAC = 0.30f
    private const val K_MIN_PX = 40f
    private const val K_MAX_PX = 200f

    /** fuseK 的增长指数。 */
    private const val K_GROW_EXP = 0.85f

    /**
     * 【断面系数（旧常量·已并入 FusionWeld）】fuseK ≤ 系数 × 间距；唯一真值 = [FusionWeld.SEAM_CAP_MULT]。
     *
     * 【口径修订 2026-09-14（用户看完关键帧后的反馈）】旧值 1.85 的本意是"防止整条直边瞬间连通"，
     * 但 1.85 < 2 ⇒ 缝在数学上【永远不出现】（见 FusionWeld 文件头的 sd(x) 推导）⇒ 逼近期只剩
     * 「局部颈部」✗ —— 正是用户否定的形态（『两条平行边应该是一整条边都吸附进去』）。
     * 现为 2.4：缝以【零厚度】先在整条边中线上出现（面积/轮廓连续 ⇒ 无跳变 ✓），再随接近度增厚；
     * 这个系数只需保证缝不伸出两卡轮廓之外（≤0.57×gap）✓。常量本体已删（避免"看起来还在用"的死值）。
     */

    /** 细丝半径随时间的衰减指数（R ∝ (1-u)^P；P=4 ⇒ 前段快速变细、中段维持细丝、末端柔和收尾）。 */
    private const val STRAND_DECAY_EXP = 4.0f

    /** 起丝的最小半径（px）：小于它就不断丝（避免远处微融合也拉丝）。 */
    private const val STRAND_MIN_R_PX = 4f

    /** 丝的最大可拉长 = 本系数 × 阈值（超过直接断）。 */
    private const val STRAND_GAP_MAX_MULT = 1.7f

    /**
     * 【叠放/穿透淡出距离】px。
     *
     * 两卡【二维相交】（真穿透，如 P07 默认布局的叠放）不是「接触面融合」——它们是叠在一起的两块玻璃，
     * 融合必须整体关闭（否则颈部条会伸到联合轮廓之外 = 可见凸包 ✗，且会改动既有叠放布局的观感 ✗）。
     * 判定：dx<0 且 dy<0（严格相交；刚好贴合 gap=0 时 dx=0，不算穿透 ✓）。
     * 淡出：穿透深度 0→PEN_FADE_PX 之间把融合按比例收回（配合 R 的指数平滑 ⇒ 无跳变），
     * 深度 ≥ 本值后融合完全关闭且状态回 IDLE（静止时零日志）。
     */
    private const val PEN_FADE_PX = 24f

    /** 断裂后 BROKEN 状态的保持时长（ms）。 */
    private const val BROKEN_HOLD_MS = 250L

    /** 日志活跃带：间距 ≤ 本系数 × 阈值时也逐帧记录（便于 CSV 里看到融合前的基线）。 */
    private const val LOG_ZONE_MULT = 1.35f

    // ================= 数据结构 =================

    /** 融合状态（逐帧日志的 state 字段）。 */
    enum class FuseState {
        /** 无融合（间距 > 阈值且无丝）。 */
        IDLE,

        /** 间距进入阈值：边缘拉扯 / 浸润融合进行中。 */
        APPROACH,

        /** 完全贴合（轮廓已连成一体）。 */
        MERGED,

        /** 拉开中：细水丝仍在（宽度随时间收缩）。 */
        STRETCH,

        /** 水丝刚断（保持约 250ms 后回到 IDLE / APPROACH）。 */
        BROKEN
    }

    /**
     * 单卡 Shader 需要的融合 uniform 值（对方卡几何 + 融合半径 + 颈部条）。
     * 全部是【本卡节点局部坐标 px】（原点 = 卡片中心，与 shader 的 centered 同源）。
     */
    data class CardUniforms(
        val blendK: Float,
        val offset2: Offset,
        val half2: Size,
        val radius2: Float,
        val shape2: Int,
        val neckCenter: Offset,
        val neckAxis: Offset,
        val neckHalf: Float,
        val neckR: Float,
        val neckRc: Float
    )

    /** 一帧的融合结果（含逐帧日志所需全部量）。 */
    data class FusionFrame(
        val state: FuseState,
        val gapPx: Float,
        val thresholdPx: Float,
        val fuse: Float,
        val blendK: Float,
        /** 【整条边缝】的横断厚度 px（= FusionWeld.seamWidth；k ≤ 2g 或 gap ≤ 0 时 0）。 */
        val seamPx: Float,
        val neckPx: Float,
        val strandPx: Float,
        val forA: CardUniforms?,
        val forB: CardUniforms?
    ) {
        /** `LGFusion t=… gap=… thr=… fuse=… k=… seam=… neck=… strand=… state=…` */
        fun logFields(nowMs: Long): String = String.format(
            Locale.US,
            "t=%d gap=%.1f thr=%.1f fuse=%.3f k=%.1f seam=%.1f neck=%.1f strand=%.1f state=%s",
            nowMs, gapPx, thresholdPx, fuse, blendK, seamPx, neckPx, strandPx, state.name
        )
    }

    // ================= 几何 =================

    private class Geo(
        val gap: Float,
        val pa: Offset,
        val pb: Offset,
        val axis: Offset,
        val extA: Float,
        val extB: Float,
        /** 带符号的轴向分离量（>0 = 该轴上有间距；<0 = 该轴上有重叠）。 */
        val dx: Float,
        val dy: Float,
        /**
         * 【整条边】沿接触边方向的重叠长度（px）—— 两条对边真正"面对面"的那一段的长度。
         * 喉部条的沿边半长以它的一半为上限（✗ 不许伸出去 ⇒ 轮廓上不许出现小凸包/台阶）。
         */
        val span: Float
    )

    /**
     * 两【可见矩形】的间距 + 最近点对 + 连接轴 + 两卡沿法向的半高。
     *
     * gap 用 AABB 距离（对角分离时 = 角点距离，与视觉一致）；最近点在竖直/水平重叠段取覆盖区间中点。
     */
    private fun geometry(a: Rect, b: Rect): Geo {
        val dx = max(a.left - b.right, b.left - a.right)
        val dy = max(a.top - b.bottom, b.top - a.bottom)
        val gx = max(dx, 0f)
        val gy = max(dy, 0f)
        val gap = hypot(gx, gy)

        val leftToRight = b.left >= a.right
        val pax = if (dx > 0f) (if (leftToRight) a.right else a.left) else
            (max(a.left, b.left) + min(a.right, b.right)) * 0.5f
        val pbx = if (dx > 0f) (if (leftToRight) b.left else b.right) else pax
        val topToBottom = b.top >= a.bottom
        val pay = if (dy > 0f) (if (topToBottom) a.bottom else a.top) else
            (max(a.top, b.top) + min(a.bottom, b.bottom)) * 0.5f
        val pby = if (dy > 0f) (if (topToBottom) b.top else b.bottom) else pay

        val pa = Offset(pax, pay)
        val pb = Offset(pbx, pby)
        var axis = Offset(pb.x - pa.x, pb.y - pa.y)
        val len = hypot(axis.x, axis.y)
        axis = if (len > 0.5f) {
            Offset(axis.x / len, axis.y / len)
        } else {
            val cx = b.center.x - a.center.x
            val cy = b.center.y - a.center.y
            val cl = hypot(cx, cy)
            if (cl > 0.5f) Offset(cx / cl, cy / cl) else Offset(1f, 0f)
        }
        // 沿连接轴的法向，两卡各自的半高（AABB 支撑函数：|n.x|·w/2 + |n.y|·h/2）
        val nx = abs(axis.y)
        val ny = abs(axis.x)
        val extA = nx * a.width * 0.5f + ny * a.height * 0.5f
        val extB = nx * b.width * 0.5f + ny * b.height * 0.5f
        // 【整条边】沿接触边方向的重叠长度（与 DualCardMeld.geometry 的 span 同一口径）：
        // 两卡错位时 = 真正"面对面"的那一段（✗ 不是整卡高/宽）⇒ 融合区不会伸到没有对边的地方去。
        val ovx = max(min(a.right, b.right) - max(a.left, b.left), 0f)
        val ovy = max(min(a.bottom, b.bottom) - max(a.top, b.top), 0f)
        val span = FusionWeld.edgeHalfSpan(ovx, ovy, axis.x, axis.y, min(extA, extB)) * 2f
        return Geo(gap, pa, pb, axis, extA, extB, dx, dy, span)
    }

    /** 卡片可见矩形（窗口 px）：默认左上角 + 偏移 + 尺寸 —— 与 DebugBridge.Cards.rect 同源。 */
    fun visibleRect(topLeft: Offset, offsetX: Float, offsetY: Float, size: IntSize): Rect = Rect(
        topLeft.x + offsetX,
        topLeft.y + offsetY,
        topLeft.x + offsetX + size.width,
        topLeft.y + offsetY + size.height
    )

    /** 形状 → 圆角半径 px（与 BackdropAdapter.glass 的映射逐分支一致）。 */
    fun radiusOf(shape: GlassShape, cornerRadiusDp: Float, density: Float, size: IntSize): Float {
        val maxR = min(size.width, size.height) / 2f
        return when (shape) {
            GlassShape.ROUNDED_RECT ->
                UltraClearGlassEffect.cornerRadiusPx(
                    cornerRadiusDp, density, size.width.toFloat(), size.height.toFloat()
                )
            GlassShape.CIRCLE, GlassShape.CAPSULE -> maxR
            GlassShape.ELLIPSE -> maxR * 0.72f
            GlassShape.TRIANGLE -> maxR * 0.03f
            GlassShape.HEXAGON -> maxR * 0.03f
            GlassShape.SUPERELLIPSE -> maxR * 0.04f
        }
    }

    // ================= 逐帧状态机 =================

    /**
     * 帧率无关的融合运行时（一个实例管一对卡片；由 [rememberProximityFusion] 驱动）。
     */
    class Runtime {

        private var lastMs = 0L
        private var lastGap = Float.NaN
        private var lastVel = 0f
        private var fuseS = 0f
        private var rNow = 0f
        private var rTarget = 0f
        private var rMaxNow = 1f
        private var strandOn = false
        private var strandT0 = 0L
        private var strandR0 = 0f
        private var brokenUntil = 0L
        private var stateNow = FuseState.IDLE

        /** 静止（可以停止帧循环）判据：无丝 + 平滑量均已收敛。 */
        val settled: Boolean
            get() = !strandOn &&
                abs(lastTargetFuse - fuseS) < 0.0025f &&
                abs(rTarget - rNow) < 0.2f &&
                abs(lastVel) < 2f

        private var lastTargetFuse = 0f

        fun reset() {
            lastMs = 0L
            lastGap = Float.NaN
            lastVel = 0f
            fuseS = 0f
            rNow = 0f
            rTarget = 0f
            strandOn = false
            brokenUntil = 0L
            stateNow = FuseState.IDLE
            lastTargetFuse = 0f
        }

        /** 推进一帧；返回本帧结果（含逐帧日志字段）。 */
        fun step(
            nowMs: Long,
            a: Rect,
            b: Rect,
            shapeA: Int,
            shapeB: Int,
            radiusA: Float,
            radiusB: Float
        ): FusionFrame {
            val dtRaw = if (lastMs == 0L) 0f else (nowMs - lastMs).coerceIn(0L, 100L).toFloat()
            val dt = if (dtRaw <= 0f) 0f else min(dtRaw, DT_STEP_MAX_MS)
            lastMs = nowMs

            val geo = geometry(a, b)
            // 【叠放判定】两卡二维严格相交 = 叠放（非接触面融合）⇒ 融合整体关闭（见 PEN_FADE_PX）
            val stacked = geo.dx < -0.5f && geo.dy < -0.5f
            val penetration = if (stacked) min(-geo.dx, -geo.dy) else 0f
            val penFade = if (stacked) (1f - penetration / PEN_FADE_PX).coerceIn(0f, 1f) else 1f
            val gap = if (stacked) -penetration else geo.gap
            val vel = if (lastGap.isNaN() || dtRaw <= 0f) 0f else (gap - lastGap) / (dtRaw / 1000f)
            lastGap = gap
            lastVel = vel

            // 【② 叠放/贴合 = 吸附融合权重严格 0】gap ≤ 0（二维重叠或刚好贴合）时，融合半径 k /
            // 喉部 r / 细丝一律【当场】归零：不留残量（旧实现的「静止保持」分支会把残余颈部留在
            // 屏幕上 = 叠放态仍有吸附形变 ✗）。连续性：gap → 0⁺ 时 k = min(…, 2.4·gap) → 0、
            // 缝厚 2√(k(k−2g)/12) → 0（消失的正是厚度本来就在 →0 的那层薄膜 ⇒ 无可见跳变 ✓）。
            if (gap <= 0f) {
                rNow = 0f
                rTarget = 0f
                strandOn = false
                strandR0 = 0f
            }

            val thr = thresholdPx
            val ten = tension
            val target = if (stacked) 0f else ((thr - gap) / thr).coerceIn(0f, 1f)
            lastTargetFuse = target
            val alpha = if (dt <= 0f) 0f else 1f - exp(-dt / SMOOTH_TAU_MS)
            fuseS += (target - fuseS) * alpha
            if (fuseS < 0.0005f && target <= 0f) fuseS = 0f

            val contact = !stacked && gap <= CONTACT_EPS_PX
            val approaching = vel < -VEL_EPS_PX_S
            val separating = vel > VEL_EPS_PX_S

            // 喉部条沿边半长 ≤ 真正的「对边」半长（geo.span/2）⇒ 贴合时轮廓正好连成一条、
            // ✗ 不向两侧伸出小凸包（旧实现用「较短卡半高 + 10px」，在正方形状卡上会超出对边 10px）。
            rMaxNow = min(
                (min(geo.extA, geo.extB) + NECK_R_EXTRA_PX) * ten,
                max(geo.span * 0.5f, 8f)
            ) * penFade

            // ---- 颈部（融合）↔ 细丝（分离）状态机 ----
            if (strandOn && (approaching || contact)) {
                cancelStrand()
            }
            if (strandOn) {
                val u = ((nowMs - strandT0).toFloat() / max(filamentMs, 1f)).coerceIn(0f, 1f)
                rNow = strandR0 * (1f - u).pow(STRAND_DECAY_EXP)
                rTarget = rNow
                if (u >= 1f || gap > thr * STRAND_GAP_MAX_MULT) {
                    strandOn = false
                    rNow = 0f
                    rTarget = 0f
                    brokenUntil = nowMs + BROKEN_HOLD_MS
                }
            } else {
                when {
                    contact -> {
                        rTarget = rMaxNow
                        rNow += (rTarget - rNow) * alpha
                    }

                    // 【① 整条边】逼近期【不再】产出「喉部条」：在最近点之间架一条宽度 2r 的桥 ——
                    // r 从 0 长起来的整段都是【局部颈部/点桥】✗（用户否定的形态）。
                    // 逼近期的融合形态 = k 的 smooth-min 联合场：两条平行对边处处等距 ⇒ 融合沿
                    // 【整条边】同时出现（先零厚度缝 → 连续增厚；见 FusionWeld 文件头的解析推导）。
                    // 喉部条只在【贴合/拉丝】相出现（相变由 alpha 平滑 + 面积在 gap→0 时本就 →0 ⇒ 无跳变 ✓）。
                    approaching -> {
                        rTarget = 0f
                        rNow += (rTarget - rNow) * alpha
                    }

                    separating -> {
                        rTarget = rNow
                        if (rNow > STRAND_MIN_R_PX && !stacked) {
                            strandOn = true
                            strandT0 = nowMs
                            strandR0 = rNow
                        }
                    }

                    else -> rTarget = rNow // 静止：保持（走近后停住 ⇒ 融合区停在原尺寸；断裂后停住 ⇒ 保持断开）
                }
            }

            // ---- 融合半径 fuseK = 【整条边焊缝】半径（唯一算法 = [FusionWeld.seamK]）----
            // 逼近期形态全由它决定：k > 2·gap 起出缝（整条边、零厚度起步）→ k ≥ 4·gap 时间隙被
            // 完全充填（外轮廓连成一条）；k ≤ 2.4·gap ⇒ 缝厚 ≤0.57×gap（✗ 不伸出轮廓）；gap ≤ 0 ⇒ 严格 0。
            val kMax = (K_MAX_FRAC * min(geo.extA, geo.extB) * ten).coerceIn(K_MIN_PX, K_MAX_PX)
            val kOut = FusionWeld.seamK(gap, fuseS.pow(K_GROW_EXP), kMax)

            // ---- 状态 ----
            stateNow = when {
                strandOn -> FuseState.STRETCH
                nowMs < brokenUntil -> FuseState.BROKEN
                contact && rNow >= rMaxNow * 0.45f -> FuseState.MERGED
                fuseS > 0.005f || rNow > 0.5f -> FuseState.APPROACH
                else -> FuseState.IDLE
            }

            // ---- 单卡 uniform（两卡各画同一段颈部：同源参数 ⇒ 无缝；各自换算到自己的局部坐标系）----
            val active = kOut > 0.0005f || rNow > 0.05f
            val barCenter = Offset((geo.pa.x + geo.pb.x) * 0.5f, (geo.pa.y + geo.pb.y) * 0.5f)
            val overlapIn = max(NECK_OVERLAP_MIN_PX, rNow * NECK_OVERLAP_R_FRAC)
            val neckHalf = gap * 0.5f + overlapIn
            val neckRc = min(rNow, neckHalf * 0.98f)
            val uA: CardUniforms?
            val uB: CardUniforms?
            if (active) {
                uA = CardUniforms(
                    blendK = kOut,
                    offset2 = Offset(b.center.x - a.center.x, b.center.y - a.center.y),
                    half2 = Size(b.width * 0.5f, b.height * 0.5f),
                    radius2 = radiusB,
                    shape2 = shapeB,
                    neckCenter = Offset(barCenter.x - a.center.x, barCenter.y - a.center.y),
                    neckAxis = geo.axis,
                    neckHalf = neckHalf,
                    neckR = rNow,
                    neckRc = neckRc
                )
                uB = CardUniforms(
                    blendK = kOut,
                    offset2 = Offset(a.center.x - b.center.x, a.center.y - b.center.y),
                    half2 = Size(a.width * 0.5f, a.height * 0.5f),
                    radius2 = radiusA,
                    shape2 = shapeA,
                    neckCenter = Offset(barCenter.x - b.center.x, barCenter.y - b.center.y),
                    neckAxis = geo.axis,
                    neckHalf = neckHalf,
                    neckR = rNow,
                    neckRc = neckRc
                )
            } else {
                uA = null
                uB = null
            }

            val neckPx = if (rNow > 0.05f) 2f * rNow else 0f
            val strandPx = if (strandOn) 2f * rNow else 0f
            val frame = FusionFrame(
                state = stateNow,
                gapPx = gap,
                thresholdPx = thr,
                fuse = fuseS,
                blendK = kOut,
                seamPx = FusionWeld.seamWidth(gap, kOut),
                neckPx = neckPx,
                strandPx = strandPx,
                forA = uA,
                forB = uB
            )
            // 逐帧日志：融合活跃期 or 进入阈值近旁 ⇒ 记；否则静默（静止时零日志）。
            // 【②证据】叠放/贴合（gap ≤ 0）时也记一行：这行里 k/seam/neck/strand 全 0 = 「叠放态
            // 吸附融合权重 = 0」的直接机械证据（✗ 不是靠看图）。日志量仍受控：帧循环收敛即停
            // （静止时零帧 ⇒ 叠放态静止后不再打印）。
            if (stateNow != FuseState.IDLE || gap <= thr * LOG_ZONE_MULT) {
                Log.i(TAG, "LGFusion " + frame.logFields(nowMs))
            }
            return frame
        }

        private fun cancelStrand() {
            strandOn = false
            rNow = 0f
            rTarget = 0f
        }
    }

    // ================= Compose 驱动 =================

    private data class PosKey(
        val ax: Float, val ay: Float, val bx: Float, val by: Float,
        val aw: Int, val ah: Int, val bw: Int, val bh: Int
    )

    /**
     * 帧驱动：只在「有两卡 && 开关开」时运行；位置/尺寸一变即（重新）起一帧循环，
     * 收敛后自动停机（静止时零帧、零日志）；融合关闭时直接把结果置空（uniform 全 0）。
     *
     * 返回的 State<FusionFrame?> 在【绘制期】被两卡的 fusionUniforms lambda 读取 ⇒ 不触发整页重组。
     */
    @Composable
    fun rememberProximityFusion(
        enabled: Boolean,
        cardA: GlassCardState,
        cardB: GlassCardState,
        defaultTopLeftA: () -> Offset,
        defaultTopLeftB: () -> Offset,
        sizeA: () -> IntSize,
        sizeB: () -> IntSize,
        shapeA: () -> GlassShape,
        shapeB: () -> GlassShape,
        cornerRadiusDp: Float,
        density: Float
    ): State<FusionFrame?> {
        val frame = remember { mutableStateOf<FusionFrame?>(null) }
        val runtime = remember { Runtime() }
        LaunchedEffect(enabled) {
            if (!enabled) {
                runtime.reset()
                frame.value = null
                return@LaunchedEffect
            }
            snapshotFlow {
                PosKey(
                    cardA.offsetX, cardA.offsetY, cardB.offsetX, cardB.offsetY,
                    sizeA().width, sizeA().height, sizeB().width, sizeB().height
                )
            }.collectLatest {
                var quiet = 0
                while (true) {
                    withFrameNanos { }
                    val sa = sizeA()
                    val sb = sizeB()
                    if (sa.width <= 0 || sa.height <= 0 || sb.width <= 0 || sb.height <= 0) {
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    frame.value = runtime.step(
                        nowMs = now,
                        a = visibleRect(defaultTopLeftA(), cardA.offsetX, cardA.offsetY, sa),
                        b = visibleRect(defaultTopLeftB(), cardB.offsetX, cardB.offsetY, sb),
                        shapeA = shapeA().ordinal,
                        shapeB = shapeB().ordinal,
                        radiusA = radiusOf(shapeA(), cornerRadiusDp, density, sa),
                        radiusB = radiusOf(shapeB(), cornerRadiusDp, density, sb)
                    )
                    if (runtime.settled) {
                        quiet++
                        if (quiet >= 2) break
                    } else {
                        quiet = 0
                    }
                }
            }
        }
        return frame
    }
}
