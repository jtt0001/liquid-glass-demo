package com.example.liquidglass.performance

import android.os.SystemClock
import android.view.Window
import androidx.metrics.performance.JankStats
import java.util.Locale

/**
 * 【P18·官方 JankStats 监视器】—— 与自写逐帧日志 [FrameTimelineLogger] **并存不冲突**（两者互不引用）。
 *
 * 依赖：androidx.metrics:metrics-performance:1.0.0（版本已对阿里云镜像 maven-metadata.xml 核实；
 * 本文件全部 API 签名取自实解析 AAR 的 javap 输出 + 官方 sources.jar，非凭记忆 ✗）。
 *
 * 真实 API（1.0.0 核实，证据在 ~/Downloads/LG-p18/）：
 *  · `JankStats.createAndTrack(window: Window, listener: OnFrameListener): JankStats`（静态工厂）；
 *  · `fun interface OnFrameListener { fun onFrame(frameData: FrameData) }`（Kotlin fun interface ⇒ 可 SAM lambda）；
 *  · `FrameData`：`isJank: Boolean` / `frameStartNanos: Long` / `frameDurationUiNanos: Long` / `states`；
 *  · **1.0.0 没有 stop() 方法**（javap 已核对）⇒ 停止 = `isTrackingEnabled = false`（@UiThread）。
 *    置 false 时官方会把本实例的 listener 委托从 window 上摘下
 *    （DelegatingFrameMetricsListener.removeDelegateFromWindow）⇒ listener 不泄漏。
 *
 * 判据（1.0.0 官方源码核实，与逐帧日志的"超 vsync"是**两套口径**）：
 *  · `isJank = uiDuration > expectedFrameDuration × jankHeuristicMultiplier(默认 2.0)`；
 *  · `uiDuration` = UNKNOWN_DELAY + INPUT_HANDLING + ANIMATION + LAYOUT_MEASURE + DRAW + SYNC；
 *  · `expectedFrameDuration`：API31+ = `FrameMetrics.DEADLINE`（系统给的本帧截止）；
 *    API24~30 = 1e9/刷新率（30..200Hz 外退回 60Hz）；
 *    ⇒ 模拟器（API31+ 路径）jank 阈值 ≈ 2×deadline ≈ 2 个 vsync 周期（比逐帧日志的 1× 严格度低）。
 *  · 回调线程 = FrameMetrics 专用 HandlerThread（**非主线程**）⇒ 本类计数只做 @Volatile 读写、零分配。
 *  · 只统计"真的画了帧"的记录（FrameMetrics 逐帧；空闲不绘制 ⇒ 无回调），
 *    与逐帧日志"挂着 Choreographer 每个 vsync 都收"在方法学上不同（见 REPORT 对比表注）。
 *
 * 开关：DebugSwitches.jankStatsMonitor（默认 **false**，一键回退：无 listener、无线程、零回调开销）。
 * 开/关点 = MainActivity 订阅 DebugBridge.revision 的 LaunchedEffect（setSwitches 后 revision 自增 ⇒ 无需重启）；
 * onDestroy 兜底 stop。
 * 读数：`am broadcast … --es cmd jankStats --es action dump|reset|status`（见 debug/DebugBridge.kt）。
 */
object JankStatsMonitor {

    const val TAG = "JankStats"

    /** 运行中的 JankStats 实例（null = 未运行）。 */
    @Volatile private var instance: JankStats? = null

    // ---------------- 聚合计数（回调线程写 / 主线程读；volatile 即可，读侧允许极轻度跨线程撕裂） ----------------
    @Volatile private var framesTotal = 0L     // 收到的帧回调数（= 本窗口渲染出的帧）
    @Volatile private var framesJank = 0L      // isJank=true 的帧数（官方判据）
    @Volatile private var maxUiNs = 0L         // 最长 UI 时长（"最长帧"）
    @Volatile private var lastUiNs = 0L        // 最近一帧 UI 时长
    @Volatile private var sumUiNs = 0L         // UI 时长累计（算均值）
    @Volatile private var firstStartNs = 0L    // 首帧 frameStartNanos（算跨度）
    @Volatile private var lastStartNs = 0L     // 末帧 frameStartNanos
    @Volatile private var enabledUptimeMs = 0L // start/reset 时刻（SystemClock.elapsedRealtime）

    /** 单例 listener：每帧一次；**零分配**（只写 volatile 计数，不建对象、不格式化字符串）。 */
    private val listener = JankStats.OnFrameListener { f ->
        framesTotal += 1
        if (f.isJank) framesJank += 1
        val ui = f.frameDurationUiNanos
        if (ui > maxUiNs) maxUiNs = ui
        lastUiNs = ui
        sumUiNs += ui
        val s = f.frameStartNanos
        if (firstStartNs == 0L || s < firstStartNs) firstStartNs = s
        if (s > lastStartNs) lastStartNs = s
    }

    fun isRunning(): Boolean = instance != null

    private fun resetCounters() {
        framesTotal = 0L; framesJank = 0L; maxUiNs = 0L; sumUiNs = 0L
        lastUiNs = 0L; firstStartNs = 0L; lastStartNs = 0L
    }

    /**
     * 开始跟踪（必须在主线程调用，官方 @UiThread）。
     * window.decorView 为空时官方会抛异常 ⇒ 这里 catch 成一行失败原因返回（不崩）。
     * 返回一行结果（OK … / FAILED …），由调用方写日志。
     */
    fun start(window: Window): String {
        instance?.takeIf { it.isTrackingEnabled }?.let {
            return "ALREADY running frames=$framesTotal jank=$framesJank（先关开关 jankStatsMonitor=0 再开，或直接 dump）"
        }
        return try {
            resetCounters()
            val js = JankStats.createAndTrack(window, listener)
            instance = js
            enabledUptimeMs = SystemClock.elapsedRealtime()
            "OK action=start（judge=uiDuration>expected×2.0；expected@API31+=FrameMetrics.DEADLINE；计数已清零）"
        } catch (t: Throwable) {
            "FAILED action=start ${t.javaClass.simpleName}: ${t.message}（window.decorView 为空 / 非主线程时官方会拒绝）"
        }
    }

    /** 停止跟踪：把本实例的 listener 委托从 window 摘下（不泄漏）；计数保留，仍可 dump 最后一段。 */
    fun stop(): String {
        val js = instance
            ?: return "NOT running frames=$framesTotal（jankStatsMonitor 未开？默认 false）"
        instance = null
        return try {
            js.isTrackingEnabled = false   // @UiThread：官方在此 removeDelegateFromWindow
            "OK action=stop（listener 已摘下）frames=$framesTotal jank=$framesJank"
        } catch (t: Throwable) {
            "FAILED action=stop ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 计数清零（running 状态不变；用于把"面板开合×5"窗口与逐帧日志对齐）。 */
    fun reset(): String {
        resetCounters()
        enabledUptimeMs = SystemClock.elapsedRealtime()
        return "OK action=reset（计数清零，running=${isRunning()}）"
    }

    /**
     * 一行读数（dump/status 用）：JankStats 口径的帧数 / jank 数 / 最长帧(UI) / 均值 / 跨度 FPS。
     * 说明：spanFps = (frames-1)/跨度 ⇒ 与逐帧日志的 meanFps 可直接对比（两者都是"帧数/时长"）。
     */
    fun dumpLine(): String {
        val total = framesTotal
        val jank = framesJank
        val spanMs =
            if (firstStartNs != 0L && lastStartNs > firstStartNs) (lastStartNs - firstStartNs) / 1e6 else 0.0
        val spanFps = if (spanMs > 1.0 && total > 1) (total - 1) * 1000.0 / spanMs else 0.0
        val meanUiMs = if (total > 0) sumUiNs.toDouble() / total / 1e6 else 0.0
        val jankPct = if (total > 0) jank * 100.0 / total else 0.0
        return ("JankStats running=%d frames=%d jank=%d(%.2f%%) maxUiMs=%.3f meanUiMs=%.3f lastUiMs=%.3f " +
            "firstStartNs=%d lastStartNs=%d spanMs=%.1f spanFps=%.2f enabledAtUptimeMs=%d")
            .format(
                Locale.US, if (isRunning()) 1 else 0, total, jank, jankPct,
                maxUiNs / 1e6, meanUiMs, lastUiNs / 1e6,
                firstStartNs, lastStartNs, spanMs, spanFps, enabledUptimeMs
            )
    }
}
