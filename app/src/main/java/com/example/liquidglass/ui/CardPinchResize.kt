package com.example.liquidglass.ui

import android.os.SystemClock
import android.util.Log
import com.example.liquidglass.debug.AppDebugLog
import java.util.Locale
import kotlin.math.abs

/**
 * 【双指捏合(pinch)改玻璃尺寸 · 2026-09-14 用户需求】
 * 用户原话：「把软件里的玻璃尺寸从控制中心控制同时兼容双指放大调整大小」。
 *
 * 设计（三条硬约束，逐条对应验收）：
 *  ① 【同一写入路径】捏合改的是【该卡】的尺寸，写入走 [GlassUiState.setSizeOfCard]
 *     （= 控制中心「玻璃尺寸」滑块与调试桥 `setUi glassSize` 的同一个函数）⇒ 与滑块【天然双向同步】
 *     （同一状态源：滑块读 [GlassUiState.sizeOfCard]，捏合写它）；
 *  ② 【同一代码路径可注入】本文件把「跨度比 → 新尺寸」的换算与写入收成**一个函数**
 *     [applyPinchResizeForCard]：手势（ui/LiquidGlassCard.kt 的 cardGestures 双指分支）与调试桥
 *     `setUi pinchScale <跨度比>` **调用的是同一个函数** ⇒ 设备上能用命令行量出与手指同一路径的数字
 *     （项目已知：adb 合成 pinch 触发不了真手势 ✗ —— 见 NEXT.md / 技能 android-compose-development）；
 *  ③ 【不改任何既有手势】单指拖动 / 点按置顶 / 按压形变全部走原代码路径不动：双指分支只在
 *     [com.example.liquidglass.debug.DebugSwitches.cardPinchResize] 打开（默认开）且调用方接线
 *     （onPinchSize != null）时才存在。
 *
 * 回退（一行）：`setSwitches --es name cardPinchResize --ei value 0`
 *   ⇒ 手势里的双指分支整体不存在，多指期间沿用旧行为（只跟踪第一根手指拖动）⇒ 与改动前逐字一致 ✓。
 */

/** 两指跨度（px）下限：小于它不参与换算（两点几乎重合时比值不稳、易跳变）。 */
internal const val PINCH_MIN_SPAN_PX = 40f

/**
 * 单帧跨度比上限（防病态跳变）：正常触摸事件之间跨度变化远小于 2×，
 * 该夹取只在"手指几乎重合后突然拉开"这类异常输入上生效 ⇒ 不影响正常手感。
 */
private const val PINCH_MAX_STEP_RATIO = 2f

/** 调试桥 `setUi pinchScale <s>` 的参考跨度（px）：s 即「双指距离放大 s 倍」。 */
internal const val PINCH_BRIDGE_REF_SPAN_PX = 400f

/** 手势侧日志节流（ms）：捏合每帧回调，不节流会刷屏。 */
private const val PINCH_LOG_MIN_INTERVAL_MS = 150L

/**
 * 【纯函数】捏合跨度比 → 新的尺寸（不写任何状态，便于离线复算/自检）。
 *
 * @param cur        该卡当前尺寸（占屏宽比例）
 * @param prevSpanPx 上一帧两指跨度（px）
 * @param nowSpanPx  本帧两指跨度（px）
 * @param range      该卡的尺寸范围（唯一口径 = [GlassUiState.sizeRangeOfCard]）
 * @return 新尺寸 = **逐帧增量**（cur × 跨度比）后夹在 range 内。
 *
 * 为什么是"逐帧增量"而不是"以捏合起点为基准的总量"：
 *  · 增量 ⇒ 手指一反向立刻跟手（总量法要先越过越界量才回弹，用户读作"粘住/不动"）；
 *  · 夹取 ⇒ 到位就不再缩小/放大（到上下限即停 ✓），不越界、不报错、不闪跳；
 *  · 所有非法输入（NaN/Inf、跨度过小、退化 range）一律原样返回 cur（静止，不抛异常）。
 */
internal fun pinchTargetSize(
    cur: Float,
    prevSpanPx: Float,
    nowSpanPx: Float,
    range: ClosedFloatingPointRange<Float>
): Float {
    if (!cur.isFinite() || !prevSpanPx.isFinite() || !nowSpanPx.isFinite()) return cur
    if (prevSpanPx < PINCH_MIN_SPAN_PX || nowSpanPx < PINCH_MIN_SPAN_PX) return cur
    // 空范围归一化：coerceIn(lo, hi) 在 hi < lo 时抛 IllegalArgumentException
    // （技能记录：coerceIn 空范围是启动/触摸闪退高发区）⇒ 先归一；真退化时返回下界（等价于保持最小）。
    val lo = minOf(range.start, range.endInclusive)
    val hi = maxOf(range.start, range.endInclusive)
    if (!(hi > lo)) return lo
    val step = (nowSpanPx / prevSpanPx).coerceIn(1f / PINCH_MAX_STEP_RATIO, PINCH_MAX_STEP_RATIO)
    val raw = cur * step
    if (!raw.isFinite()) return cur
    return raw.coerceIn(lo, hi)
}

/**
 * 【唯一写入函数】把一次捏合（两指跨度 prev → now）应用到第 [cardIndex] 块卡的尺寸上。
 *
 * 两个调用点（**同一条代码路径**，验收要求）：
 *  ① 手势：ui/LiquidGlassCard.kt 的 cardGestures 双指分支 →（LiquidGlassScreen 的接线 lambda）
 *     `applyPinchResizeForCard(uiState, i, prevSpan, nowSpan)`；
 *  ② 设备取证/复现：debug/DebugBridge.kt 的 `setUi pinchScale <跨度比>`
 *     `applyPinchResizeForCard(ui, i, 参考跨度, 参考跨度×s, source = "bridge")`。
 *
 * 副作用（两条都与面板滑块同源）：
 *  · [GlassUiState.setSizeOfCard]：写尺寸（夹在 [GlassUiState.sizeRangeOfCard] 内 = 与滑块同一范围）；
 *  · [GlassUiState.selectCard]：捏合【该卡】即把它设为"正在调整"的那块 —— 与既有的
 *    "手指点哪块玻璃就改哪块"同一语义（点按置顶也是一条路），面板的「正在调整：玻璃 N」、
 *    滑块标题/位置、屏幕上的选中描边会一起跟着切 ✓。幂等（已是选中卡时无操作）。
 *
 * @return 写入后的该卡尺寸（供调用方回读/打印）。
 */
internal fun applyPinchResizeForCard(
    ui: GlassUiState,
    cardIndex: Int,
    prevSpanPx: Float,
    nowSpanPx: Float,
    source: String = "pinch"
): Float {
    val i = if (cardIndex < 0) 0 else cardIndex
    val old = ui.sizeOfCard(i)
    val range = ui.sizeRangeOfCard(i)
    val next = pinchTargetSize(old, prevSpanPx, nowSpanPx, range)
    ui.setSizeOfCard(i, next)
    ui.selectCard(i)
    logPinch(i, old, next, prevSpanPx, nowSpanPx, range, source)
    return next
}

// ---- 证据日志（logcat tag=LiquidGlass + 应用内调试日志 GESTURE 频道）--------------------------

/** 上一次捏合日志的时刻（ms，节流用）。 */
private var pinchLogLastMs = 0L

/** 上一次捏合的夹取状态（""/"下限"/"上限"）：状态翻转的那一帧必打（边界手感的唯一证据）。 */
private var pinchLogLastBand = ""

/**
 * 捏合的一行证据日志：`PINCH[来源] 玻璃 N 尺寸 old→new span a→b 比 s 范围 lo..hi 夹取=x`。
 * 节流规则： 手势侧最多每 [PINCH_LOG_MIN_INTERVAL_MS] 一行，但【进入/离开上下限】那一帧必打；
 * 调试桥（source="bridge"）不节流、必打（命令行取证要每次都能读到数字 ✓）。
 */
private fun logPinch(
    i: Int,
    old: Float,
    next: Float,
    prevSpan: Float,
    nowSpan: Float,
    range: ClosedFloatingPointRange<Float>,
    source: String
) {
    val band = when {
        next <= range.start + 1e-4f -> "下限"
        next >= range.endInclusive - 1e-4f -> "上限"
        else -> "-"
    }
    val prevBand = pinchLogLastBand
    val now = SystemClock.elapsedRealtime()
    if (source != "pinch" || band != prevBand || now - pinchLogLastMs >= PINCH_LOG_MIN_INTERVAL_MS) {
        pinchLogLastMs = now
        val msg = "PINCH[%s] %s 尺寸 %.4f→%.4f span %.1f→%.1f 比 %.3f 范围 %.2f..%.2f 夹取=%s".format(
            Locale.US, source, "玻璃 ${i + 1}", old, next, prevSpan, nowSpan,
            if (prevSpan >= PINCH_MIN_SPAN_PX) nowSpan / prevSpan else 1f,
            range.start, range.endInclusive, band
        )
        Log.i("LiquidGlass", msg)
        AppDebugLog.log("GESTURE", msg)
    }
    pinchLogLastBand = band
    // 让"尺寸真的动了"这件事在同一行里可判：零变化（越界或跨度比 1）时补一个显式标记（仅调试桥）
    if (source != "pinch" && abs(next - old) <= 1e-4f) {
        val line = "PINCH[%s] 尺寸未变（old=new=%.4f；夹取=%s；跨度下限=%.0fpx）".format(
            Locale.US, source, next, band, PINCH_MIN_SPAN_PX
        )
        Log.i("LiquidGlass", line)
        AppDebugLog.log("GESTURE", line)
    }
}
