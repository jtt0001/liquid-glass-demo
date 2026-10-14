package com.example.liquidglass.performance

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import com.example.liquidglass.debug.DebugSwitches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 性能监控器。
 *
 * 线程模型：
 * - FrameMetrics 监听注册在独立 HandlerThread 上，回调内只复制必要数值；
 * - 聚合计算（中位数 / P95 / FPS / CPU / 电池积分）在后台线程完成；
 * - 快照通过 StateFlow 发布，UI 更新频率限制为 700ms 一次；
 * - Activity 销毁时注销监听并安全退出 HandlerThread，不泄漏 Window。
 *
 * 指标口径：
 * - FPS：最近 1 秒内帧数（窗口上限 120 帧）；静止无新帧时显示 IDLE/最近值。
 * - App CPU：ΔProcess.getElapsedCpuTime()【毫秒】/ (ΔSystemClock.elapsedRealtime() × 逻辑核心数)，
 *   按全部逻辑核心归一化为百分比；不使用 /proc/stat 的整机 CPU。
 * - GPU 帧耗时：FrameMetrics.GPU_DURATION；getMetric() 返回 -1 时显示 N/A。
 *   TOTAL_DURATION / COMMAND_ISSUE_DURATION 仅作为 Proxy 附加展示，
 *   不得伪装成真实 GPU 占用。
 * - 电池：BATTERY_PROPERTY_CURRENT_NOW（µA → mA，正=充电，负=放电）；
 *   BATTERY_PROPERTY_CHARGE_COUNTER（µAh）仅用于校验支持与否；
 *   会话能耗 = 电流 × 时间积分（mAh）；不支持时显示 N/A，不显示 0。
 */
class PerformanceMonitor(
    private val window: Window,
    private val context: Context
) {

    /**
     * FrameMetrics 专用线程：回调与聚合都在此线程串行执行。
     * 【P65 修复③】stop() 会 quitSafely 本线程并把三个槽位置空 ⇒ start() 重建线程/作用域，
     * stop→start 可重复使用 ✓（改动前：字段为 val，quit 后 start() 对已 quit 的 Looper post
     * 被静默丢弃 ⇒ 监控静默死亡、快照冻结 ✗）。
     * 一行回退（改动前语义：stop 后 start 不重建 ⇒ 静默不恢复）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name perfRestartable --ei value 0 -p com.liqglass.ultraclear
     */
    private var handlerThread: HandlerThread? = HandlerThread("LiquidGlassFrameMetrics").also { it.start() }
    private var frameHandler: Handler? = Handler(handlerThread!!.looper)

    private var scope: CoroutineScope? = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(PerformanceSnapshot.idle())
    val snapshot: StateFlow<PerformanceSnapshot> = _snapshot

    // ---- 帧数据窗口（最近 120 帧 / 1 秒）----
    private class FrameSample(
        val frameNs: Long,
        val gpuNs: Long,
        val totalNs: Long,
        val issueNs: Long,
        val timestampNs: Long
    )

    private val samples = ArrayDeque<FrameSample>()
    private var lastUiTickMs = 0L

    /**
     * 刷新周期（ns）：按实际刷新率计算 jank 阈值。
     * 注意：不能用 applicationContext.display（非视觉 Context 在 Android 13+ 会抛
     * UnsupportedOperationException），改用 Window 的 DecorView Display。
     *
     * 【P65 修复①】阈值改为【按当前实际刷新率动态计算】——RefreshRateController 会运行时切
     * 60/120Hz（setSwitches refresh120Hz 0/1），构造期写死会让 jankCount 系统性错判
     *（120Hz 下 10~16ms 的 jank 帧全部漏判 / 60Hz 下 ~16.7ms 帧被误判）✗。
     * 下面 fixedRefreshHz/fixedRefreshPeriodNs 仅是【一行回退档】（jankDynamicThreshold=false =
     * 逐字改动前行为：构造期一次性取值，运行时切档不再影响判定）：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name jankDynamicThreshold --ei value 0 -p com.liqglass.ultraclear
     * 【范围】只影响 jankCount 判定与 dumpState/dumpFrameStats 的 jankThr 回读行，✗ 不动其它帧统计口径。
     */
    private val fixedRefreshHz: Float = window.decorView.display?.refreshRate ?: 60f
    private val fixedRefreshPeriodNs: Long = (1_000_000_000.0 / fixedRefreshHz).toLong()

    /**
     * 当前刷新率（Hz）。数据源：RefreshRateController 当前档位值（进程内现成值，切档即生效）→
     * 兜底 Window DecorView 的 Display.getRefreshRate() → 最后兜底 60Hz。
     * ✗ 不读 dumpsys ✗（只在 700ms 一次的聚合里读，非每帧）。
     * ⚠️ 模拟器只有 60Hz 单模式：120 档读数 =【模拟值】（物理显示仍 60Hz），
     *    dumpState/dumpFrameStats 的 jankThr 行会标注；真实 120 节拍待平板回线实测。
     */
    private fun effectiveRefreshHz(): Float {
        val ctrl = runCatching { RefreshRateController.targetHz() }.getOrNull() ?: 0f
        if (ctrl > 0f) return ctrl
        return runCatching { window.decorView.display?.refreshRate }.getOrNull()?.takeIf { it > 0f } ?: 60f
    }

    /** 当前 jank 阈值（ns）：jankDynamicThreshold=false（一行回退）= 改动前的构造期写死值。 */
    private fun currentRefreshPeriodNs(effHz: Float): Long =
        if (DebugSwitches.jankDynamicThreshold) (1_000_000_000.0 / effHz).toLong() else fixedRefreshPeriodNs
    // ---- CPU 采样 ----
    private val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var lastCpuMs = Process.getElapsedCpuTime()   // 注意：返回毫秒
    private var lastWallMs = SystemClock.elapsedRealtime()

    // ---- 电池采样 ----
    private var lastBatteryReadMs = 0L
    private var lastCurrentMa = 0f
    private var sessionEnergyMah = 0f

    private val uiTickRunnable = object : Runnable {
        override fun run() {
            publishSnapshot()
            // 线程已随 stop() 销毁时不再续期（P65 修复③：槽位可空）
            frameHandler?.postDelayed(this, UI_TICK_INTERVAL_MS)
        }
    }

    /** FrameMetrics 回调：只复制必要数值，不做任何聚合。 */
    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        // API 30+ 使用 TOTAL_DURATION 作为整帧耗时（FRAME_DURATION 已被移除）
        val frameNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (frameNs > 0L) {
            samples.addLast(
                FrameSample(
                    frameNs = frameNs,
                    gpuNs = metrics.getMetric(FrameMetrics.GPU_DURATION),
                    totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    issueNs = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                    timestampNs = System.nanoTime()
                )
            )
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }
    }

    fun start() {
        if (started) return
        started = true
        // 【P65 修复③】stop() 已 quit 线程 / cancel 作用域并置空 ⇒ 这里重建线程与作用域，
        // 否则对已 quit 的 Looper post 被静默丢弃、快照永远冻结在旧值（改动前的静默死亡 ✗）。
        val fh: Handler
        if (handlerThread == null) {
            if (!DebugSwitches.perfRestartable) {
                // 一行回退档（改动前语义）：stop 后 start 不重建 ⇒ 监听/快照静默不恢复
                return
            }
            handlerThread = HandlerThread("LiquidGlassFrameMetrics").also { it.start() }
            fh = Handler(handlerThread!!.looper)
            frameHandler = fh
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        } else {
            fh = frameHandler ?: return
        }
        window.addOnFrameMetricsAvailableListener(frameMetricsListener, fh)
        lastBatteryReadMs = SystemClock.elapsedRealtime()
        lastCurrentMa = readCurrentMa() ?: 0f
        fh.post(uiTickRunnable)
    }

    fun stop() {
        if (started) {
            started = false
            window.removeOnFrameMetricsAvailableListener(frameMetricsListener)
            frameHandler?.removeCallbacks(uiTickRunnable)
            handlerThread?.quitSafely()
            scope?.cancel()
            // 置空 ⇒ 下一次 start() 知道必须重建（改动前字段为 val、quit 后无法重建 ✗）
            handlerThread = null
            frameHandler = null
            scope = null
        }
    }

    /** 【P65】监控是否在运行（perfMonitor 取证命令的 status 回读用）。 */
    fun isStarted(): Boolean = started

    private var started = false

    /** 聚合 + 发布快照（运行在 FrameMetrics HandlerThread 上）。 */
    private fun publishSnapshot() {
        val nowMs = SystemClock.elapsedRealtime()

        // ---- FPS / 帧耗时 / Jank ----
        val windowStartNs = System.nanoTime() - 1_000_000_000L
        val recent = samples.filter { it.timestampNs >= windowStartNs }
        val fps: Float? = if (recent.isNotEmpty()) recent.size.toFloat() else null
        val avgFrameMs = recent.map { it.frameNs / 1e6f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val p95FrameMs = percentileNs(recent.map { it.frameNs }, 0.95)?.let { it / 1e6f }
        // 【P65 修复①】阈值按当前刷新率动态取（每次聚合算一次，非每帧；数据源见 effectiveRefreshHz）
        val effHz = if (DebugSwitches.jankDynamicThreshold) effectiveRefreshHz() else fixedRefreshHz
        val refreshPeriodNs = currentRefreshPeriodNs(effHz)
        val displayHzNow = runCatching { window.decorView.display?.refreshRate }.getOrNull()
        val jank = recent.count { it.frameNs > refreshPeriodNs }

        val gpuValues = recent.map { it.gpuNs }.filter { it > 0L }
        val gpuMedianMs = percentileNs(gpuValues, 0.50)?.let { it / 1e6f }
        val gpuP95Ms = percentileNs(gpuValues, 0.95)?.let { it / 1e6f }
        val totalMedianMs = percentileNs(recent.map { it.totalNs }.filter { it > 0L }, 0.50)?.let { it / 1e6f }
        val issueMedianMs = percentileNs(recent.map { it.issueNs }.filter { it > 0L }, 0.50)?.let { it / 1e6f }

        // ---- App CPU：进程 CPU 时间增量 / (墙钟增量 × 核心数) ----
        // Process.getElapsedCpuTime() 的单位是【毫秒】不是纳秒——旧代码按纳秒除以 1e9，
        // 让增量小了 1e6 倍，读数恒为 0%（用户早已指出"应该是 API 读错了"）。
        val cpuMs = Process.getElapsedCpuTime()
        val wallMs = SystemClock.elapsedRealtime()
        val cpuDeltaS = (cpuMs - lastCpuMs) / 1000.0
        val wallDeltaS = (wallMs - lastWallMs) / 1000.0
        val appCpuPercent: Float? =
            if (wallDeltaS > 0.5) (cpuDeltaS / wallDeltaS / cpuCores * 100.0).toFloat() else null
        lastCpuMs = cpuMs
        lastWallMs = wallMs

        // ---- 电池 ----
        val currentMa = readCurrentMa()
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val charging = batteryManager?.isCharging
        if (currentMa != null && lastCurrentMa != 0f && lastBatteryReadMs > 0L) {
            val dtHours = (nowMs - lastBatteryReadMs) / 3_600_000f
            sessionEnergyMah += abs(currentMa) * dtHours
        }
        lastCurrentMa = currentMa ?: 0f
        lastBatteryReadMs = nowMs

        val snapshot = PerformanceSnapshot(
            fps = fps,
            avgFrameMs = avgFrameMs,
            p95FrameMs = p95FrameMs,
            jankCount = jank,
            appCpuPercent = appCpuPercent,
            gpuMedianMs = gpuMedianMs,
            gpuP95Ms = gpuP95Ms,
            totalMedianMs = totalMedianMs,
            issueMedianMs = issueMedianMs,
            batteryCurrentMa = currentMa,
            sessionEnergyMah = if (currentMa != null) sessionEnergyMah else null,
            charging = charging,
            // 【P65 修复①回读】本次判定采用的阈值/刷新率 + Display 实测值（dumpState/dumpFrameStats 输出）
            jankThresholdMs = refreshPeriodNs / 1e6f,
            refreshHz = effHz,
            displayHz = displayHzNow,
            timestampMs = nowMs
        )
        // 只发布不可变快照；UI 侧 collectAsState 节流展示
        scope?.launch { _snapshot.value = snapshot }
    }

    /** CURRENT_NOW：µA → mA；不支持（Int.MIN_VALUE）返回 null。 */
    private fun readCurrentMa(): Float? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val microAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (microAmps != Int.MIN_VALUE) microAmps / 1000f else null
    }

    /** 计算 P 分位（ns 列表），空列表返回 null。 */
    private fun percentileNs(values: List<Long>, percentile: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private companion object {
        const val UI_TICK_INTERVAL_MS = 700L
        const val MAX_SAMPLES = 120
    }
}
