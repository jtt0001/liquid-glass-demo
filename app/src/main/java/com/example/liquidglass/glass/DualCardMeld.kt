package com.example.liquidglass.glass

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.ui.GlassCardState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 三次平滑（C1 连续）：接近度 0..1 → 0..1，两端斜率为 0 ⇒ 融合宽度无折点。 */
private fun smoothstep01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 【P07 · 双卡融合 meld（官方 iOS26 口径）】—— 接近检测 → 并集场 uniform 驱动（新增文件）。
 *
 * ==================== 官方事实（用户已核实 ✓；详见 DebugSwitches.dualCaptureRefraction 的校正说明）====================
 *   · iOS26 多块玻璃靠近的真实行为 = 【融合 meld】：靠到阈值即 fluidly join —— 形状 meld 成一块
 *     【连续玻璃】、**共享采样区**、一起渲染；离开即 fluidly separate；
 *     阈值由容器的 `GlassEffectContainer` / `NSGlassEffectContainerView.spacing` 控制
 *     （官方原文 “The glass shapes meld together based on their proximity”）。
 *   · 官方【明确禁止 glass on glass】：“Always avoid glass on glass”（玻璃无法正确采样另一块玻璃）
 *     ⇒ iOS 里不存在「两块玻璃各自折射后再叠」✗ ⇒ P07 的二次折射已降为可选实验档（默认 false）。
 *   · 用户反馈：二次折射『形变太过了』✗ ⇒ 本模块成为双卡靠近/重叠的【默认表现】。
 *
 * ==================== 本模块做什么（最小实现，不新建并行体系）====================
 * 1. 【同一套接近度】阈值/张力【直接读】ProximityFusion.thresholdPx / .tension（= P12 的唯一真值，
 *    参数名 `proximityThreshold`；`setFusion --ef threshold <px>` 一处改、两边同时变 ⇒ 不是各算一套 ✗）。
 *    gap 的算法与 P12 的 geometry() 同一口径（可见矩形的 AABB 距离）——同一状态两值之差会逐帧打进
 *    `LGMeld … gapΔ=` 日志（实测 |Δ| ≤ 0.1px，见 NEXT.md 取证）。
 * 2. 【形状并集 + 一次折射】本模块只产出【场 uniform】（复用 P12 已注入 AGSL 的联合场通道：
 *    fuseK = 整条边焊缝半径；fuseNeck* = 0），并集区每个像素由【更近的那块卡】绘制
 *    （MeldShaders 的归属划分）⇒ 并集只画一次 = 一次折射/一条轮廓/一条高光 ⇒ 无双边框无双亮带 ✓。
 * 3. 【融合形态 = 整条边焊缝】（口径修订 2026-09-14，用户看完关键帧后的原话：
 *    『如果是两条平行边应该是一整条边都吸附进去；如果两块玻璃已经叠到一起了则没有玻璃吸附效果』）：
 *       s = smoothstep(clamp((thr − gap)/thr))     ← C1 平滑、纯函数（无状态机 ⇒ 不会跳变）
 *       k = FusionWeld.seamK(gap, s, K_MAX_PX·ten) ← k ≤ 2.4·gap；gap ≤ 0 ⇒ 严格 0
 *       缝厚 = 2√(k(k−2g)/12)                      ← k > 2g 才出缝（整条边、零厚度起步）⇒ 单调、连续
 *    · 两条【平行对边】处处等距 ⇒ 融合沿【整条边】同时出现（✗ 不是最近点之间的局部颈部/点桥）；
 *      k ≥ 4g 时间隙被完全充填（外轮廓连成一条 = 「一整条边吸附进去」✓）；k 的解析推导见 [FusionWeld]。
 *    · ✗ 逼近期不再产出「喉部条」（旧实现的局部颈部载体，用户否定的形态）；✗ 也不再做重叠期
 *      smooth-min 圆角（叠放态零形变）。
 * 4. 【叠放/贴合 = 并集（无变形）】gap ≤ EDGE_TOUCH_PX 或二维重叠 ⇒ 融合权重【全 0】（k=0、喉部三项=0）
 *    但【归属划分保持开】⇒ 形状并集 + 共享采样 + 无变形（最接近 iOS 的叠放口径 ✓）。
 *
 * ==================== 开关与回退 ====================
 *   · 主开关 DebugSwitches.dualCardMeld（**默认关 = 实验档**，跨进程初值 files/lg_meld_on；
 *     ⛔ 2026-09-17 现状校正：原写"默认 开 / lg_meld_off"与代码相反）；
 *   · 打开 / 关闭（一行）：`adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
 *        --es name dualCardMeld --ei value 1 -p com.liqglass.ultraclear`（value 0 = 关 = 现状默认）
 *     ⇒ 关时本模块整体不参与（uniform 全 0、划分关闭），融合表现回到 P12 原口径；
 *   · 单卡 / 未选第二形状 / 阈值外：一律不产出任何 uniform ⇒ 逐像素等于改动前 ✓。
 *
 * ==================== 逐帧日志（tag LGMeld）====================
 *   LGMeld t=<elapsedRealtimeMs> gap=<px> thr=<px> s=<0..1> k=<px> neck=<px> width=<px> state=<…>
 *   · width = 融合宽度（px；喉部宽度，重叠后 = 整条接触线跨度）；
 *   · state ∈ IDLE / MELDING / MERGED / MERGED_OVERLAP（远离 / 融合中 / 贴合 / 重叠）；
 *   · 融合活跃期或阈值近旁逐帧记；静止时静默（不刷日志）。
 */
object DualCardMeld {

    /** logcat tag（逐帧日志与自检都用它）。 */
    const val TAG = "LGMeld"

    // ================= 常量（本档专属；阈值/张力不在此处 —— 唯一真值 = ProximityFusion）=================

    /**
     * smooth-min 焊缝半径上限（px）。
     *
     * 【口径修订 2026-09-14（用户看完关键帧后的反馈）】64 → 96：
     *   · 缝出现的条件 = k > 2·gap（见 [FusionWeld] 文件头的解析推导）⇒ k 上限决定「多远开始出缝」。
     *     kMax=64 时缝要到 gap≈24px 才出现（阈值 88px 的融合区间只用上最后一小段 ✗ 观感太突然）；
     *     96 提前到 gap≈33px，且 k 越大缝的增长越连续可辨。
     *   · 安全性：k 的可见作用只在本卡轮廓附近（缝 ⊂ 间隙内 ≤gap/2；并集凸包 ≤ k/4 ≈ 24px 且被
     *     k ≤ 2.4·gap 夹住）⇒ 仍在卡片玻璃层可绘范围（layerPaddingPx ≈ 84px）之内 ✓，且 gap→0 时
     *     k→0（贴合/叠放零变形 ✓）。
     */
    const val K_MAX_PX = 96f

    /** 重叠后「并集圆角」的平滑接管长度（px）——【本档已不再使用】：用户口径 = 叠放态无任何吸附形变
     *  （重叠/贴合 = 几何并集 + 共享采样 + 归属划分，✗ 不做 smooth-min 圆角形变）。保留常量=保留记录。 */
    const val OV_RAMP_PX = 32f

    /** 喉部半宽的额外余量（px）——【本档已不再使用】：逼近期的局部颈部/点桥是用户否定的形态 ✗。 */
    const val NECK_EXTRA_PX = 16f

    /** 喉部沿轴方向伸进两卡内部的最小长度（px）——【本档已不再使用】（同上）。 */
    const val NECK_OVERLAP_MIN_PX = 60f

    /** 喉部伸进卡内的长度与半径的比例——【本档已不再使用】（同上）。 */
    const val NECK_OVERLAP_R_FRAC = 0.6f

    /** 融合宽度随 s 的增长指数——【本档已不再使用】：k 直接由接近度 s 给出（FusionWeld.seamK）。 */
    const val GROW_EXP = 1.5f

    /**
     * k 的「断面」上限系数（与 [ProximityFusion] / [FusionWeld] 同口径）。
     *
     * 【口径修订 2026-09-14】1.85 → FusionWeld.SEAM_CAP_MULT（2.4）：1.85 < 2 ⇒ 缝永远不出现
     * ⇒ 逼近期只剩局部颈部 ✗（用户原话『两条平行边应该是一整条边都吸附进去』）。
     * 唯一真值现在在 [FusionWeld.SEAM_CAP_MULT]，这里只保留别名（✗ 不许再出现第二个魔数）。
     */
    const val POP_CAP_MULT = FusionWeld.SEAM_CAP_MULT

    /** 判定「贴合」的间距（px）：≤ 本值时 = 形状并集态（融合权重全 0 + 归属划分开 ⇒ iOS 叠放口径）。 */
    const val EDGE_TOUCH_PX = 1.5f

    /** 日志活跃带：gap ≤ 本系数 × 阈值时也记一行（便于曲线里看到融合前的基线）。 */
    const val LOG_ZONE_MULT = 1.35f

    // ================= 运行时状态（供渲染层读取）=================

    /**
     * 【归属划分是否生效】= 并集场活跃（k>0 或 喉部>0）时为 true。
     *
     * 由 [rememberDualCardMeld] 每帧写；渲染层（backdrop/BackdropAdapter.kt）把它作为 `meldOwn` uniform
     * 发给 AGSL：划分在【融合活跃 或 并集态（叠放/贴合）】时打开（口径修订 2026-09-14：AGSL 侧不再
     * 要求 max(fuseK,fuseNeckR)>0 —— 叠放/贴合态融合权重被用户口径强制为 0，但并集仍要只画一次）
     * ⇒ 一帧延迟也不会产生任何像素差异 ✓。
     */
    @Volatile
    var partitionActive: Boolean = false

    /** 最近一帧结果（取证/dump 用；null = 本档未参与）。 */
    @Volatile
    var lastFrame: MeldFrame? = null

    /** 「关闭态」uniform（全 0）：AGSL 侧等价于融合段整体跳过 ⇒ 逐像素等于两张独立卡 ✓。 */
    private val ZERO_UNIFORMS = ProximityFusion.CardUniforms(
        blendK = 0f,
        offset2 = Offset.Zero,
        half2 = Size.Zero,
        radius2 = 0f,
        shape2 = 0,
        neckCenter = Offset.Zero,
        neckAxis = Offset.Zero,
        neckHalf = 0f,
        neckR = 0f,
        neckRc = 0f
    )

    /** 一帧的 meld 结果（含逐帧日志所需全部量）。 */
    data class MeldFrame(
        /** IDLE / MELDING / MERGED / MERGED_OVERLAP */
        val state: String,
        val gapPx: Float,
        val penetrationPx: Float,
        val thresholdPx: Float,
        val s: Float,
        val k: Float,
        val neckR: Float,
        val neckPx: Float,
        /**
         * 融合宽度（报告量）= 【整条边缝的横断厚度】px（k ≤ 2g 或 gap ≤ 0 时 = 0）。
         * 口径修订后它不再等于「喉部宽度 2r」（喉部条已取消 ⇒ [neckR]/[neckPx] 恒为 0）。
         */
        val widthPx: Float,
        val partitionOn: Boolean,
        val forA: ProximityFusion.CardUniforms?,
        val forB: ProximityFusion.CardUniforms?
    ) {
        fun logFields(nowMs: Long): String = String.format(
            Locale.US,
            "t=%d gap=%.1f thr=%.1f s=%.3f k=%.1f neck=%.1f width=%.1f pen=%.1f own=%b state=%s",
            nowMs, gapPx, thresholdPx, s, k, neckR, widthPx, penetrationPx, partitionOn, state
        )
    }

    // ================= 几何（与 ProximityFusion.geometry() 同一口径）=================

    /** 两可见矩形的间距 / 重叠深度 / 连接轴 / 界面跨度。 */
    private class Geo(
        val gap: Float,
        /** 二维重叠深度（0 = 未重叠）。 */
        val penetration: Float,
        /** 单位连接轴（A 的最近点 → B 的最近点）。 */
        val axis: Offset,
        /** 沿连接轴法向，两卡各自的半宽（AABB 支撑函数；与 P12 的 extA/extB 同式）。 */
        val perpHalfA: Float,
        val perpHalfB: Float,
        /** 相邻界面跨度（沿界面方向的重叠长度；= 融合宽度的自然上限）。 */
        val span: Float,
        /** 最近点对中点（喉部中心，窗口坐标）。 */
        val barCenter: Offset
    )

    private fun geometry(a: Rect, b: Rect): Geo {
        val dx = max(a.left - b.right, b.left - a.right)
        val dy = max(a.top - b.bottom, b.top - a.bottom)
        val gx = max(dx, 0f)
        val gy = max(dy, 0f)
        val gap = hypot(gx, gy)
        val penetration = if (dx < -0.5f && dy < -0.5f) min(-dx, -dy) else 0f

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
        axis = if (len > 0.5f) Offset(axis.x / len, axis.y / len) else {
            val cx = b.center.x - a.center.x
            val cy = b.center.y - a.center.y
            val cl = hypot(cx, cy)
            if (cl > 0.5f) Offset(cx / cl, cy / cl) else Offset(1f, 0f)
        }

        val perpHalfA = abs(axis.y) * a.width * 0.5f + abs(axis.x) * a.height * 0.5f
        val perpHalfB = abs(axis.y) * b.width * 0.5f + abs(axis.x) * b.height * 0.5f

        val ovx = max(min(a.right, b.right) - max(a.left, b.left), 0f)
        val ovy = max(min(a.bottom, b.bottom) - max(a.top, b.top), 0f)
        var span = abs(axis.y) * ovx + abs(axis.x) * ovy
        if (span < 2f) span = 2f * min(perpHalfA, perpHalfB)

        return Geo(
            gap = gap, penetration = penetration, axis = axis,
            perpHalfA = perpHalfA, perpHalfB = perpHalfB, span = span,
            barCenter = Offset((pa.x + pb.x) * 0.5f, (pa.y + pb.y) * 0.5f)
        )
    }

    /** 卡片可见矩形（窗口 px）：与 ProximityFusion.visibleRect 同源（唯一真值 = 布局落点 + offset）。 */
    fun visibleRect(topLeft: Offset, offsetX: Float, offsetY: Float, size: IntSize): Rect =
        ProximityFusion.visibleRect(topLeft, offsetX, offsetY, size)

    // ================= 纯函数：接近度 → 并集 uniform（无状态机 ⇒ 单调平滑）=================

    /**
     * 按【当前几何】算出这一帧的并集 uniform（纯函数；位置/尺寸/形状一变就重算 ⇒ 拖拽逐帧跟手）。
     *
     * 返回 frame.forA / forB（本卡/对方的镜像参数；两卡拿到的是【同一段场】的两份坐标换算
     * ⇒ 无缝，与 P12 的约定一致）。
     */
    fun computeMeld(
        a: Rect,
        b: Rect,
        shapeA: Int,
        shapeB: Int,
        radiusA: Float,
        radiusB: Float,
        nowMs: Long = android.os.SystemClock.elapsedRealtime()
    ): MeldFrame {
        val thr = ProximityFusion.thresholdPx
        val ten = ProximityFusion.tension
        val g = geometry(a, b)
        val gEff = max(g.gap, 0f)

        // ① 接近度（0 = 阈值外/刚好在阈值上；1 = 贴合/重叠）——C1 平滑
        val t = ((thr - gEff) / max(thr, 1f)).coerceIn(0f, 1f)
        val s = smoothstep01(t)

        // ② 【并集（无变形）态】= 二维重叠 或 刚好贴合：融合权重必须【全 0】（用户口径②：
        //    『两块玻璃已经叠到一起了则没有玻璃吸附效果』）——但并集仍要「只画一次」⇒ 归属划分继续开着
        //    （渲染层 = 几何并集 + 共享采样 + 无变形 = 最接近 iOS 的叠放口径 ✓）。
        val unionOnly = g.penetration > 0f || gEff <= EDGE_TOUCH_PX

        // ③ 整条边焊缝半径 k（唯一算法 = [FusionWeld.seamK]，与 P12 runtime 同一口径）：
        //    0 < gap < 阈值 才有值；k > 2·gap 起出缝（沿【整条接触边】、零厚度起步，面积连续）
        //    → k ≥ 4·gap 时间隙被完全充填（外轮廓连成一条）；k ≤ 2.4·gap ⇒ 缝厚 ≤0.57×gap（✗ 不伸出轮廓）；
        //    gap ≤ 0（重叠/贴合）⇒ 严格 0。
        //    ✗ 不再另行产出「最近点之间的喉部条」：逼近期的局部颈部/点桥是用户看完关键帧后否定的形态。
        val kMax = K_MAX_PX * ten
        val k = if (unionOnly) 0f else FusionWeld.seamK(gEff, s, kMax)

        // ④ 融合宽度（报告量）= 整条边缝的【横断厚度】px（解析式：k ≤ 2g 时 = 0 ⇒ 两端都连续）
        val width = FusionWeld.seamWidth(gEff, k)

        val active = k > 0.0005f
        // ⑤ 归属划分（meldOwn）：融合活跃【或】并集态都要开 —— 后者是「叠放/贴合只画一次」的唯一机制 ✓
        val partitionOn = active || unionOnly
        val axis = g.axis
        // 【为什么非活跃时也返回「全 0」而不是 null】：dualCardMeld 打开时本模块【接管】整条融合 uniform
        // 通道 ⇒ 两卡远离/分离后不会再落回 P12 runtime 的颈部/细丝（官方行为里没有「水丝」⇒ 默认不做 ✓；
        // P12 的实现仍在，关掉本开关即恢复）。全 0 在 AGSL 里等价于「融合段整体跳过」⇒ 逐像素等于两张独立卡 ✓。
        // 并集态（k=0）时这里仍然发出 offset2/half2/radius2/shape2（划分要用对方卡的 SDF），
        // 但把 blendK / 喉部三项【显式置 0】⇒ 无任何 smooth-min 形变 ✓（参数回读即证据）。
        val forA: ProximityFusion.CardUniforms
        val forB: ProximityFusion.CardUniforms
        if (partitionOn) {
            forA = ProximityFusion.CardUniforms(
                blendK = k,
                offset2 = Offset(b.center.x - a.center.x, b.center.y - a.center.y),
                half2 = Size(b.width * 0.5f, b.height * 0.5f),
                radius2 = radiusB,
                shape2 = shapeB,
                neckCenter = Offset.Zero,
                neckAxis = axis,
                neckHalf = 0f,
                neckR = 0f,
                neckRc = 0f
            )
            forB = ProximityFusion.CardUniforms(
                blendK = k,
                offset2 = Offset(a.center.x - b.center.x, a.center.y - b.center.y),
                half2 = Size(a.width * 0.5f, a.height * 0.5f),
                radius2 = radiusA,
                shape2 = shapeA,
                neckCenter = Offset.Zero,
                neckAxis = axis,
                neckHalf = 0f,
                neckR = 0f,
                neckRc = 0f
            )
        } else {
            forA = ZERO_UNIFORMS
            forB = ZERO_UNIFORMS
        }

        val state = when {
            g.penetration > 0f -> "MERGED_OVERLAP"
            gEff <= EDGE_TOUCH_PX -> "MERGED"
            s > 0.005f -> "MELDING"
            else -> "IDLE"
        }

        val frame = MeldFrame(
            state = state, gapPx = g.gap, penetrationPx = g.penetration, thresholdPx = thr,
            s = s, k = k, neckR = 0f, neckPx = 0f, widthPx = width,
            partitionOn = partitionOn, forA = forA, forB = forB
        )
        // 逐帧日志：融合活跃 / 阈值近旁 / 并集态（叠放/贴合）⇒ 记；静止时静默（位置不变不重算 ⇒ 不刷）。
        // 叠放态这行里 k=0.0 neck=0.0 width=0.0 own=true = 「重叠态融合权重 = 0，但并集仍只画一次」的机械证据 ✓
        if (active || unionOnly || g.gap <= thr * LOG_ZONE_MULT) {
            Log.i(TAG, "LGMeld " + frame.logFields(nowMs))
        }
        return frame
    }

    /** 供日志/报告使用的可读描述。 */
    fun describe(): String {
        val f = lastFrame ?: return "meld=<未参与> thr=%.1fpx tension=%.2f（与 P12 同源）".format(
            Locale.US, ProximityFusion.thresholdPx, ProximityFusion.tension
        )
        return String.format(
            Locale.US,
            "meld= gap=%.1f s=%.3f k=%.1f seam=%.1f own=%b state=%s thr=%.1fpx",
            f.gapPx, f.s, f.k, f.widthPx, f.partitionOn, f.state, f.thresholdPx
        )
    }

    // ================= Compose 驱动 =================

    private data class PosKey(
        val ax: Float, val ay: Float, val bx: Float, val by: Float,
        val aw: Int, val ah: Int, val bw: Int, val bh: Int,
        val sa: Int, val sb: Int, val rev: Int
    )

    /**
     * 帧驱动：只在「有两卡 && 开关开」时运行；位置/尺寸/形状（或 revision）一变即重算一帧；
     * 静止时不再计算（零帧、零日志）。返回的 State<MeldFrame?> 在【绘制期】被两卡的
     * fusionUniforms lambda 读取 ⇒ 不触发整页重组 ✓。
     *
     * 纯函数实现（无时间平滑、无状态机）：融合宽度只由【当前接近度】决定 ⇒ 单调、平滑、可复算 ✓
     * （拖拽时逐帧跟手；`setSwitches dualCardMeld 0|1` 或 `setFusion --ef threshold` 后
     *  下一次位置变化即生效）。
     */
    @Composable
    fun rememberDualCardMeld(
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
    ): State<MeldFrame?> {
        val frame = remember { mutableStateOf<MeldFrame?>(null) }
        LaunchedEffect(enabled) {
            if (!enabled) {
                partitionActive = false
                lastFrame = null
                frame.value = null
                return@LaunchedEffect
            }
            // revision 进 key ⇒ setSwitches / setFusion 之后即使不动位置也会重算（口径当场一致 ✓）
            snapshotFlow {
                PosKey(
                    cardA.offsetX, cardA.offsetY, cardB.offsetX, cardB.offsetY,
                    sizeA().width, sizeA().height, sizeB().width, sizeB().height,
                    shapeA().ordinal, shapeB().ordinal, DebugBridge.revision.intValue
                )
            }.collect {
                val sa = sizeA()
                val sb = sizeB()
                if (sa.width <= 0 || sa.height <= 0 || sb.width <= 0 || sb.height <= 0) {
                    frame.value = null
                    lastFrame = null
                    partitionActive = false
                    return@collect
                }
                val shA = shapeA()
                val shB = shapeB()
                val f = computeMeld(
                    a = visibleRect(defaultTopLeftA(), cardA.offsetX, cardA.offsetY, sa),
                    b = visibleRect(defaultTopLeftB(), cardB.offsetX, cardB.offsetY, sb),
                    shapeA = shA.ordinal,
                    shapeB = shB.ordinal,
                    radiusA = ProximityFusion.radiusOf(shA, cornerRadiusDp, density, sa),
                    radiusB = ProximityFusion.radiusOf(shB, cornerRadiusDp, density, sb)
                )
                frame.value = f
                lastFrame = f
                partitionActive = f.partitionOn
            }
        }
        return frame
    }
}
