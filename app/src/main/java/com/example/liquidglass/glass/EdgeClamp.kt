package com.example.liquidglass.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 【修 R9 · 贴边玻璃按下/放大被屏幕边裁切（2026-10-11）】
 *
 * 症状（用户反馈）：玻璃贴近屏幕【下/右】边时按下/放大，可见边缘直接越出屏幕被裁
 * （左/上仍留有状态栏方向的既有余量 ⇒ 只有下/右贴边时才露馅）。
 *
 * 根因：放大预算原本按【元素自身尺寸的固定比例】计算（卡片 SHAPE_INFLATE 按下外扩、
 * 收起按钮 blockPressScale、LiquidButton growBudgetPx、LiquidBottomTabs 16dp 基准），
 * 完全不看该边到屏幕的剩余空间 ⇒ 贴边时算出的可见外扩直接越过物理屏幕边缘被系统硬裁。
 *
 * 统一口径（所有修复点共用，注释同源）：
 *   该轴每侧的按下外扩预算 = min(原预算, 该轴距边较近一侧的剩余空间 − SAFE_GAP_PX)；
 *   距屏幕该边的剩余空间 = max(0, 窗口 inset 相应位 − 元素该边到屏幕该物理边的距离)，
 *   快照口径（组合期查询 + 写入，绘制帧内只做纯 px 运算 → 热路径零开销）。
 *   剩余空间 ≥ 原预算时（离边远）逐位返回原值 ⇒ 远离边的元素逐像素零回归（对照臂）。
 *   总门 = DebugSwitches.edgeClampOn（默认 true；false = 一行回退 = 改前行为）。
 *
 * 【gap 快照通路】gap 由 Compose 组合期写入下面的 @Volatile 四联（EdgeClamp.setGaps），
 * 绘制点读 currentGap{Left,Top,Right,Bottom}Px —— 组合期写 / 绘制期读、同一口径。
 * 未接线时（MAX_VALUE）= 该边不约束 ⇒ 逐位等于改前（兜底，不会被误触发）。
 */
object EdgeClamp {

    /** 钳制后的安全余量（px）：玻璃仍可贴近屏幕边，但不再被屏幕硬裁。 */
    const val SAFE_GAP_PX: Int = 2

    // —— 绘制路径读的 gap 四联快照（组合期 set，绘制期 get；@Volatile ⇒ 跨线程读安全）
    @Volatile var currentGapLeftPx: Int = Int.MAX_VALUE
        private set
    @Volatile var currentGapTopPx: Int = Int.MAX_VALUE
        private set
    @Volatile var currentGapRightPx: Int = Int.MAX_VALUE
        private set
    @Volatile var currentGapBottomPx: Int = Int.MAX_VALUE
        private set

    /**
     * 全局 inset+屏幕尺寸快照（绘制侧也用它做"按元素位置现算 gap"）。
     * 未接线时 NULL_SNAP：edgeGapPx 返回 MAX_VALUE 四联 = 不约束 = 改前行为。
     */
    @Volatile var storedInsetSnapshot: WindowInsetSnapshot = WindowInsetSnapshot.NULL
        private set

    /** 组合期写入（快照与 gap 四联一次整体写 ⇒ 无撕裂窗口）。 */
    fun setGaps(l: Int, t: Int, r: Int, b: Int) {
        currentGapLeftPx = l; currentGapTopPx = t; currentGapRightPx = r; currentGapBottomPx = b
    }

    /** 组合期写快照（只由 LiquidGlassScreen 组合作用域调用一次）。 */
    fun setStoredInsetSnapshot(s: WindowInsetSnapshot) {
        storedInsetSnapshot = s
    }

    /**
     * 逐轴【两侧对称】的按下外扩钳制（Shader 外扩 / 卡片层余量都用它）：
     * baseGrowthPerSide = 原预算（px，每侧）；gapSide1/gapSide2 = 该轴两端的剩余空间
     * （x 轴送 left,right；y 轴送 top,bottom；远端送 MAX_VALUE = 不约束）。
     * 对称生长只受较紧的一端约束 ⇒ 取 min；free ≤ 0 ⇒ 完全不外扩 = 0。
     */
    fun clampSymmetricGrowth(baseGrowthPerSide: Float, gapSide1: Int, gapSide2: Int): Float {
        val free = minOf(gapSide1, gapSide2) - SAFE_GAP_PX
        return if (free <= 0) 0f else minOf(baseGrowthPerSide, free.toFloat())
    }

    /**
     * 等比 graphicsLayer 缩放钳制（收起按钮 blockPressScale 用）：
     * halfW/halfH = 元素中心到边的半尺寸（px）；gap* = 中心对齐下的每边剩余空间
     *（= 该边剩余空间 − 元素半尺寸，可为负；远端送 MAX_VALUE = 不约束）。
     * 缩放是等比的：x/y 外扩 = (scale−1)·半尺寸，要求每边外扩后仍留 ≥ SAFE_GAP_PX。
     * 返回多项式前 [xMaxExtraHalf, yMaxExtraHalf，maxScale整体]（maxScale = x 与 y 取小）。
     */
    fun clampUniformScale(
        halfW: Float, halfH: Float,
        gapLeft: Int, gapTop: Int, gapRight: Int, gapBottom: Int
    ): FloatArray {
        val hw = halfW.coerceAtLeast(1f)
        val hh = halfH.coerceAtLeast(1f)
        val freeX = (minOf(gapLeft, gapRight) - SAFE_GAP_PX).coerceAtLeast(0).toFloat()
        val freeY = (minOf(gapTop, gapBottom) - SAFE_GAP_PX).coerceAtLeast(0).toFloat()
        val scaleX = 1f + freeX / hw
        val scaleY = 1f + freeY / hh
        val s = minOf(scaleX, scaleY).coerceAtLeast(1f)
        return floatArrayOf((s - 1f) * hw, (s - 1f) * hh, s)
    }

    // ==================== 【窗口 inset 快照】（composable 上下文用） ====================

    /** 屏幕四边窗口 inset + 屏幕尺寸快照（px）。取一次、闭包持有。 */
    class WindowInsetSnapshot(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val screenW: Int, val screenH: Int
    ) {
        companion object {
            /** 兜底：不可用时全 0（各 gap = 纯屏幕物理边判据 = 仍然有效）。 */
            val ZERO = WindowInsetSnapshot(0, 0, 0, 0, 0, 0)

            /** 未接线哨兵：四边都置超大值 ⇒ edgeGapPx 永远"不约束"（= 改前行为）。 */
            val NULL = WindowInsetSnapshot(
                Int.MAX_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2,
                1080, 2400
            )
        }
    }

    /**
     * 【composable 上下文】取一次窗口 inset + 屏幕尺寸快照（LocalView.rootWindowInsets +
     * displayMetrics；不在绘制帧内重新查询）。fallback：不可用时 LOW = 全 0（仍能按
     * 物理屏幕边钳制，不会"钳了一个错值"）。
     */
    @Composable
    fun rememberWindowInsetSnapshot(): WindowInsetSnapshot {
        val view = LocalView.current
        return remember(view) {
            val ins = view.rootWindowInsets
            // systemWindowInset* 系 deprecated，但给出的正是"窗口四边的系统栏 inset"，
            // 与 LiquidGlassScreen 在用的 statusBars/navigationBars 兼容同一口径。
            @Suppress("DEPRECATION")
            val snap = if (ins == null) WindowInsetSnapshot.ZERO else WindowInsetSnapshot(
                left = ins.systemWindowInsetLeft.coerceAtLeast(0),
                top = ins.systemWindowInsetTop.coerceAtLeast(0),
                right = ins.systemWindowInsetRight.coerceAtLeast(0),
                bottom = ins.systemWindowInsetBottom.coerceAtLeast(0),
                screenW = view.context.resources.displayMetrics.widthPixels,
                screenH = view.context.resources.displayMetrics.heightPixels
            )
            if (snap.screenW <= 0 || snap.screenH <= 0)
                WindowInsetSnapshot(snap.left, snap.top, snap.right, snap.bottom, 1080, 2400)
            else snap
        }
    }

    /** [left, top, right, bottom] 元素矩形（窗口坐标）到【屏幕四边（含 inset）】的剩余空间（px）：
     *   剩余 = inset 相应位 − (元素该边到屏幕该物理边的距离)；负值 = 已越入 inset 区 = 0 预算。
     */
    fun edgeGapPx(
        snap: WindowInsetSnapshot,
        x: Float, y: Float, w: Float, h: Float
    ): IntArray {
        val l = ((x - snap.left).toInt()).coerceAtLeast(0)
        val t = ((y - snap.top).toInt()).coerceAtLeast(0)
        val r = ((snap.screenW - snap.right - (x + w)).toInt()).coerceAtLeast(0)
        val b = ((snap.screenH - snap.bottom - (y + h)).toInt()).coerceAtLeast(0)
        return intArrayOf(l, t, r, b)
    }
}
