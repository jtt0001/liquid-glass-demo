package com.example.liquidglass.glass

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 【P12·邻近融合的「融合形态」唯一算法】（本文件 = P12 runtime 与 P07 meld 的共用口径）。
 *
 * ==================== 用户口径（逐字，2026-09-14 看完关键帧后）====================
 *  ① 『两条平行边应该是一整条边都吸附进去』⇒ 融合必须沿【整条边】发生（连续焊缝/腰线，
 *     ✗ 不是最近点之间的局部颈部/点桥）；✗ 无尖锐 cusp（角对角尖对尖除外）。
 *  ② 『两块玻璃已经叠到一起了则没有玻璃吸附效果』⇒ 两卡二维重叠（gap ≤ 0）时【吸附融合权重 = 0】，
 *     叠放态不出现任何吸附/融合形变（颈部、细丝、浸润凸包都不出现 ✗），按最接近 iOS 的叠放渲染
 *     （形状并集 + 共享采样 + 归属划分，无变形）。
 *  ③ 融合只在 0 < gap < proximityThreshold 区间生效，两端连续过渡（无跳变）。
 *
 * ==================== 为什么「整条边」要靠 smooth-min（而不是「喉部条」）====================
 * 旧实现（`FusionShaders` 里的圆角矩形「颈部条」+ P12/P07 各自算的半径 r）在【逼近期】只能
 * 在最近点之间架起一段宽度 2r 的桥 ⇒ r 从 0 长起来的那段全程是【局部颈部/点桥】✗（用户看到的就是它）。
 * 现在改为让【smooth-min 联合场】（AGSL 里既有的 `fuseK` 段，k→0 严格退化为 min(a,b)）单独承担逼近期：
 *
 *  · 两条【平行对边】之间的最短距离沿【整条边】处处相同 ⇒ 并集场里 h = clamp(0.5 + 0.5·(b−a)/k)
 *    在整条边上同时开始生效 —— 融合区不是一个点，而是【整条接触边】（拉长到边两端的角区才平滑收口）。
 *  · 缝的解析式（Gap 通道内，a = g/2+x、b = g/2−x、|x| ≤ k/2）：
 *        sd(x) = g/2 − k/4 + 3x²/k        ⇒  sd(0) < 0  ⟺  k > 2g（出现缝）
 *    即：k 越过 2g 的瞬间，缝以【零厚度】先出现在整条边的中线上（面积连续 ⇒ 无跳变 ✓），
 *    随后 k 继续长大 ⇒ 缝从间缝中线向两侧增厚（越贴近越大 ✓），k ≥ 4g 时间隙被完全充填
 *    （外轮廓连成一条 = 「一整条边吸附进去」✓✓）。
 *  · 断面上限：k ≤ SEAM_CAP_MULT × gap（= 2.4）⇒ 缝厚 ≤ 0.57×gap（缝永远不伸出两卡轮廓之外 ✗ 无凸包），
 *    且 gap → 0 时 k → 0（贴合态不残留任何吸附形变 ✓ 与②的叠放态归零同一条极限 ✓ 连续 ✓）。
 *
 * ⚠️ 与旧口径的差异（对 P07-meld 的接口变更，必须显式说明 ✗ 不许偷偷改）：
 *  · 旧 `K_POP_CAP_MULT / POP_CAP_MULT = 1.85` 的本意是「防整条直边瞬间连通」——但 1.85 < 2
 *    ⇒ 缝在数学上【永远不出现】（k 永远到不了 2g）⇒ 逼近期只剩「局部颈部」，正是用户否定的形态 ✗。
 *    现改为 2.4：缝以【零厚度】出现（面积/轮廓连续 ✓），「瞬间连通」的是【覆盖率百分比】这一个
 *    旧报告量，而肉眼可见的【面积】是从 0 连续长起来的 —— 用户口径优先（整条边 ✓）。
 *  · 逼近期不再产出「喉部条」（fuseNeckR = 0）⇒ P07-meld 的 `widthPx` 报告量改为【缝的横断厚度】。
 *    「喉部条」仍保留给它自己的旧用途：P12 runtime 的【贴合 / 拉丝】相（官方水丝档，默认被 meld 取代）。
 */
object FusionWeld {

    /**
     * 缝半径 k 的「断面」上限系数：k ≤ 本值 × gap。
     *
     * = 2.4 ⇒ 缝厚 ≤ 0.57×gap（不会出现伸出两卡轮廓的凸包）；> 2 是「缝能出现」的必要条件
     * （见文件头 sd(x) 的推导：k > 2g 才有缝），2.4 给「缝 → 完全充填（k ≥ 4g）」留出连续区间。
     */
    const val SEAM_CAP_MULT = 2.4f

    /**
     * 缝半径（smooth-min 半径）k，px。
     *
     * @param gapPx      两可见矩形间距（px；≤ 0 = 已贴合/二维重叠 ⇒ 返回 0，见②）
     * @param proximity  接近度 0..1（0 = 阈值外/刚好在阈值上；1 = 贴合）——由调用方用同一套
     *                   阈值（`ProximityFusion.thresholdPx`）算好，且必须自身连续（smoothstep 等）
     * @param kMaxPx     缝半径上限（px；由调用方按卡片尺寸/张力给出）
     * @return k ∈ [0, kMaxPx]；gap ≤ 0 或 proximity ≤ 0 时【严格 0】（未触发 ⇒ 逐像素退化）
     */
    fun seamK(gapPx: Float, proximity: Float, kMaxPx: Float): Float {
        if (gapPx <= 0f) return 0f          // ② 叠放/贴合：吸附融合权重 = 0（严格）
        if (kMaxPx <= 0f) return 0f
        val s = proximity
        if (s <= 0f) return 0f              // ③ 阈值外：严格 0（不得留亚像素残量）
        return min(kMaxPx * min(s, 1f), SEAM_CAP_MULT * gapPx)
    }

    /**
     * 缝的【横断厚度】px（跨越间隙方向的可见厚度；= 缝沿间缝中线充填的宽度）。
     *
     * 解析解：sd(x) = g/2 − k/4 + 3x²/k < 0 ⟺ |x| < √( k(k−2g)/12 ) ⇒ 厚度 = 2·√( k(k−2g)/12 )，
     * 且不超过 gap 本身（缝不可能比间隙还厚）。k ≤ 2g 时 = 0（缝还没出现）⇒ 阈值端与贴合端都连续 ✓
     * 该量用于逐帧日志 / CSV 曲线（「融合宽度随 gap 单调平滑」的判据），✗ 不用来看图。
     */
    fun seamWidth(gapPx: Float, k: Float): Float {
        if (gapPx <= 0f || k <= 0f) return 0f
        val halfW2 = k * (k - 2f * gapPx) / 12f
        if (halfW2 <= 0f) return 0f
        return min(2f * sqrt(halfW2), gapPx)
    }

    /**
     * 融合带沿【整条接触边】的可用半长（px）——两条对边的重叠长度的一半（投影到边方向）。
     *
     * 融合区沿边的自然边界：超出这个长度就没有「对边」可吸附（角落/外侧），所以「喉部条」类
     * 部件一律用它做上限（✗ 不许伸出去 ⇒ 轮廓上不许出现小凸包/台阶）。
     */
    fun edgeHalfSpan(ovx: Float, ovy: Float, axisX: Float, axisY: Float, fallbackHalf: Float): Float {
        val span = kotlin.math.abs(axisY) * max(ovx, 0f) + kotlin.math.abs(axisX) * max(ovy, 0f)
        return (if (span < 2f) max(fallbackHalf, 1f) else span * 0.5f)
    }

    /** 供日志/自检用的单行描述。 */
    fun describe(): String =
        "weld=wholeEdge seamCap=${SEAM_CAP_MULT}x gap k>2g=seam k>=4g=full 0/overlap=>0"
}
