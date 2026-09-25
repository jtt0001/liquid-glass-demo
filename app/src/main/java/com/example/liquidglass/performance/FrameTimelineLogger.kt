package com.example.liquidglass.performance

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.WindowManager
import com.example.liquidglass.debug.AppDebugLog
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.debug.DebugSwitches
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 【逐帧时间戳日志】（帧率分析仪）—— 供所有"流畅性/掉帧"类验收使用的唯一帧数据来源。
 *
 * 为什么需要它（其他"乐器"的不足，均为本项目实测结论）：
 *  · `LGLayout` 只在【布局真的跑】且 (w,h) 变化时打一行 ⇒ 不动布局的帧没有记录 ⇒ 算不出真实 FPS ✗；
 *  · 应用内看板的 `PerformanceMonitor` 是【窗口快照】（FrameMetrics 聚合，且受 700ms 发布节流）
 *    ⇒ 同一工作量会出现 fps=7 / 25 两种读数（审计实测）⇒ 不能作为验收依据 ✗。
 *
 * 本仪器 = Choreographer.postFrameCallback 逐帧采集，逐帧写进【预分配环形缓冲】：
 *   · 每帧记录：`frameTimeNanos`（Choreographer 给的 vsync 时间戳）、与上一帧的间隔、是否超 vsync、
 *     以及当时的控制中心进度 p（`DebugBridge.panel.currentP()`，拿不到记 NaN）；
 *   · 环形缓冲容量 [CAP]=1200 帧（≈120Hz 下 10s / 60Hz 下 20s），写满覆盖最旧 ⇒ 长时间采集内存恒定；
 *   · **热路径零分配**：只有 LongArray/FloatArray 的写入 + 少量 volatile 读，不建 List、不装箱、
 *     不做字符串格式化（字符串只在“导出/异常帧打点”这两条非热路径上产生）；
 *   · 由 [DebugSwitches.frameTimeline]（默认 **关**）总门控；采集途中把它置 false ⇒ 下一帧自动停；
 *   · 导出 = `files/frametimeline_<session>.csv`（adb pull 免权限，列定义见 [exportCsv] 注释）。
 *
 * 线程模型（重要）：Choreographer 回调、[start]/[stop]/[exportCsv] 全都在 **主线程** 执行
 * （adb 广播 onReceive 在主线程），因此热路径上的缓冲区字段不需要任何锁；跨线程只读 [isRunning] 等
 * volatile 字段。若从非主线程调 [start] 会直接拒绝（避免拿到没有 Looper 的 Choreographer）。
 *
 * 使用（adb，见 debug/DebugBridge.kt 的 frameTimeline 命令）：
 * ```
 * adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name frameTimeline --ei value 1 -p com.liqglass.ultraclear
 * adb shell am broadcast -a com.liqglass.DEBUG --es cmd frameTimeline --es action start  -p com.liqglass.ultraclear
 * ...（做要测的操作：开合控制中心等）...
 * adb shell am broadcast -a com.liqglass.DEBUG --es cmd frameTimeline --es action stop   -p com.liqglass.ultraclear
 * adb shell am broadcast -a com.liqglass.DEBUG --es cmd frameTimeline --es action export -p com.liqglass.ultraclear
 * adb pull /sdcard/Android/data/com.liqglass.ultraclear/files/frametimeline_<session>.csv
 * ```
 *
 * 口径约定（与 dumpsys gfxinfo 的"janky frame"同源）：
 *  · `targetFrameNs` = 1e9 / 当前物理屏刷新率（WindowManager.defaultDisplay.refreshRate，
 *    取不到时退回 60Hz）⇒ 120Hz 屏阈值 8.333ms、90Hz 11.111ms、60Hz 16.667ms（与命令里写的
 *    16.67 / 11.11 / 8.33ms 一致）；
 *  · `overVsync = 1` ⇔ 本帧与上一帧的间隔 > 一个 vsync 周期（= 没在下一个 vsync 前交付下一帧）；
 *  · 间隔 ≥ 1.5×周期 ⇒ 至少丢了一整个 vsync（分析脚本里单列为"丢帧档"）。
 *  · 首帧（会话第一帧）没有前一帧 ⇒ 间隔记 0、overVsync 记 0，分析时按"无意义"跳过。
 *
 * 诚实边界：
 *  · Choreographer 回调是"每个 vsync 一次" ⇒ 本仪器开启时会持续请求 vsync（空闲时也在收帧），
 *    这本身是逐帧测量的必要条件（否则量不到"该画的帧没画"），但它会带来一个常驻的空转回调
 *    （每条 ≈ 几微秒 + 一次数组写入），不再有其它副作用（不 invalidate 视图、不触发绘制）；
 *  · 若 UI 线程被长任务占死，回调会被推迟到线程空闲的那个 vsync ⇒ 间隔会"整周期跳变"
 *    （这正是掉帧的真实形态 ✓，但无法分辨"一帧干了 30ms"与"两帧各干了 15ms"）。
 */
object FrameTimelineLogger {

    /** 日志通道 tag（AppDebugLog / logcat 均用它）。 */
    const val TAG = "FT"

    /** 环形缓冲容量（帧）：120Hz ≈ 10s，60Hz ≈ 20s。 */
    const val CAP = 1200

    /** 导出目录里只保留最近这么多份 frametimeline_*.csv（与 AppDebugLog 同策略，防长跑堆文件）。 */
    private const val MAX_EXPORT_FILES = 5

    // ---------------- 会话状态 ----------------
    @Volatile private var running: Boolean = false
    @Volatile private var sessionId: String = ""          // 当前/最近一次采集的会话号（文件名用）
    @Volatile private var lastCsvFile: File? = null       // 最近一次成功导出的 CSV
    private var refreshHz: Float = 60f
    private var targetFrameNs: Long = 16_666_666L
    private var startUptimeMs = 0L
    private var stopUptimeMs = 0L
    private var startWall = ""
    private var stopWall = ""

    private val wallFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val idFmt = SimpleDateFormat("MMdd-HHmmss", Locale.US)

    private var choreographer: Choreographer? = null
    private var prevFrameNs = 0L
    private var frameTotal = 0L        // 本会话记录到的总帧数（含已被覆盖的）

    // ---------------- 零分配环形缓冲（只在主线程读写） ----------------
    private val bufFrameNs = LongArray(CAP)    // vsync 时间戳（Choreographer 回调参数）
    private val bufDeltaNs = LongArray(CAP)    // 与上一帧的间隔（首帧 = 0 = 无意义）
    private val bufP = FloatArray(CAP)         // 该帧时刻的控制中心进度 p（拿不到 = NaN）
    private var head = 0                       // 下一个写入位置
    private var size = 0                       // 缓冲区里的有效帧数（≤ CAP）

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // 运行时把总开关置 false ⇒ 立即自停（不再重投回调，避免留下无法停止的空转）
            if (!DebugSwitches.frameTimeline) {
                stop()
                return
            }
            record(frameTimeNanos)
            choreographer?.postFrameCallback(this)
        }
    }

    fun isRunning(): Boolean = running

    /** 最近一次导出成功 CSV 的绝对路径（未导出过 = ""）。 */
    fun lastCsvPath(): String = lastCsvFile?.absolutePath ?: ""

    fun currentSession(): String = sessionId

    // ---------------- 采集控制 ----------------

    /**
     * 开始采集。必须在主线程调用（Choreographer 归属该线程）。
     * 返回一行结果（OK … / FAILED …），由调用方（DebugBridge）写日志。
     */
    fun start(context: Context): String {
        if (running) return "ALREADY running session=$sessionId（先 stop，或直接 export 取当前缓冲）"
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return "FAILED：必须在主线程调用（Choreographer 逐帧回调绑定调用线程；adb 广播本身就在主线程）"
        }
        val ch = runCatching { Choreographer.getInstance() }.getOrNull()
            ?: return "FAILED：主线程 Choreographer 不可用"
        // 刷新率决定 vsync 阈值（16.67/11.11/8.33ms…）；取不到就按 60Hz，绝不因此不发采集
        val hz = runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.refreshRate
        }.getOrNull()?.takeIf { it.isFinite() && it >= 10f } ?: 60f

        sessionId = idFmt.format(Date())
        refreshHz = hz
        targetFrameNs = (1_000_000_000.0 / hz).roundToLong()
        head = 0
        size = 0
        frameTotal = 0L
        prevFrameNs = 0L
        startUptimeMs = SystemClock.elapsedRealtime()
        stopUptimeMs = 0L
        startWall = wallFmt.format(Date())
        stopWall = ""
        choreographer = ch
        running = true
        AppDebugLog.log(TAG, "start session=$sessionId refreshHz=%.2f target=%.3fms cap=%d".format(Locale.US, hz, targetFrameNs / 1e6, CAP))
        ch.postFrameCallback(frameCallback)
        return "OK action=start session=$sessionId refreshHz=%.2f targetFrameMs=%.3f cap=%d（导出文件将写入 files/frametimeline_$sessionId.csv）".format(
            Locale.US, hz, targetFrameNs / 1e6, CAP
        )
    }

    /** 停止采集（缓冲区保留，可继续 export）。 */
    fun stop(): String {
        if (!running) return "NOT running（最近会话=$sessionId frames=$frameTotal）"
        running = false
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
        stopUptimeMs = SystemClock.elapsedRealtime()
        stopWall = wallFmt.format(Date())
        val durMs = (stopUptimeMs - startUptimeMs).coerceAtLeast(1L)
        val fps = frameTotal * 1000.0 / durMs
        AppDebugLog.log(
            TAG,
            "stop session=$sessionId frames=$frameTotal durMs=$durMs fps=%.2f buffer=%d".format(Locale.US, fps, size)
        )
        return "OK action=stop session=$sessionId frames=$frameTotal durationMs=$durMs fps=%.2f（缓冲保留最近 %d 帧；用 --es action export 导 CSV）".format(
            Locale.US, fps, size
        )
    }

    /** 状态一行（采集中也安全）。 */
    fun status(): String {
        val ch = if (running) "running" else "idle"
        return "state=$ch session=$sessionId frames=$frameTotal buffer=$size/$CAP refreshHz=%.2f targetFrameMs=%.3f lastCsv=%s".format(
            Locale.US, refreshHz, targetFrameNs / 1e6, if (lastCsvFile == null) "<none>" else lastCsvFile!!.name
        )
    }

    // ---------------- 热路径（每帧一次，零分配） ----------------

    private fun record(frameTimeNanos: Long) {
        val d = if (prevFrameNs == 0L) 0L else frameTimeNanos - prevFrameNs
        prevFrameNs = frameTimeNanos
        val p = sampleP()
        bufFrameNs[head] = frameTimeNanos
        bufDeltaNs[head] = d
        bufP[head] = p
        head += 1
        if (head == CAP) head = 0
        if (size < CAP) size += 1
        frameTotal += 1
        // 【可选通道·默认关】异常帧实时打点：只有在 frameTimelineLogJank=true 时才产生字符串/日志
        // （高刷屏上"超一个 vsync"可能很密 ⇒ 默认关，保证既不影响测量、也不刷屏）
        if (DebugSwitches.frameTimelineLogJank && d > targetFrameNs) {
            val pTxt = if (p.isNaN()) "-" else "%.3f".format(Locale.US, p)
            AppDebugLog.log(
                TAG,
                "jank idx=%d deltaMs=%.2f targetMs=%.2f p=%s".format(
                    Locale.US, frameTotal - 1, d / 1e6, targetFrameNs / 1e6, pTxt
                )
            )
        }
    }

    /**
     * 当前控制中心进度 p（拿不到 = NaN）。走 [DebugBridge.panel] 桥（与 dumpState 完全同一条接线），
     * 同在主线程、只读锚点状态 —— 无锁无分配；p 桥未注册（UI 未运行）或 requireOffset 抛异常都记 NaN。
     */
    private fun sampleP(): Float {
        val bridge = DebugBridge.panel ?: return Float.NaN
        return try {
            bridge.currentP()
        } catch (t: Throwable) {
            Float.NaN
        }
    }

    // ---------------- 导出 CSV ----------------

    /**
     * 把缓冲区里的帧导出为 `files/frametimeline_<session>.csv`（采集中也允许，导的是当前快照）。
     *
     * 文件结构（`#` 开头 = 元数据行；其后是标准 CSV：一行表头 + 数据行）：
     * ```
     * # LiquidGlass FrameTimeline CSV
     * # session=0914-123456                  采集会话号（= 文件名后缀）
     * # started_at/stopped_at                设备本地墙钟（与 `adb logcat -v time` 的时间列可直接对表）
     * # started_uptime_ms/stopped_uptime_ms  SystemClock.elapsedRealtime()（跨会话可换算）
     * # duration_ms, frames_total            会话总时长 / 记录到的总帧数（含被环形缓冲覆盖掉的）
     * # refresh_hz, target_frame_ms          物理屏刷新率 / 一个 vsync 周期（超 vsync 判据）
     * # over_vsync_rule=deltaMs>target_frame_ms              （列 overVsync=1 的定义）
     * # dropped_rule=deltaMs>=1.5*target_frame_ms            （分析脚本另单列的"丢帧档"）
     * # frames_in_buffer, capacity, dropped_from_buffer      当前可导出的行数 / 容量 / 被覆盖帧数
     * # p_at_export=…                       导出时刻的控制中心进度 p（拿不到 = -）
     * # switches_at_export=name=val,…        导出时刻全部 DebugSwitches 布尔开关快照
     * index,frameTimeNs,deltaMs,overVsync,targetP
     * 0,123456789012345,0.000,0,0.0000
     * …
     * ```
     * 列定义：
     *  · index       会话内帧序号（从 0 起；环形缓冲覆盖后仍保持绝对序号）
     *  · frameTimeNs Choreographer 回调参数（vsync 时间戳，System.nanoTime 时基）
     *  · deltaMs     与上一帧的间隔（ms，3 位小数；会话首帧 = 0.000 无意义）
     *  · overVsync   0/1：间隔是否 > 一个 vsync 周期（阈值 = 1000/refresh_hz）
     *  · targetP     该帧时刻控制中心进度 p（nan 记空字段；对齐"掉帧 ↔ p 进度"用）
     *
     * 返回一行结果；导出成功时 [lastCsvPath] 给出绝对路径。
     */
    fun exportCsv(context: Context): String {
        val sid = sessionId
        if (sid.isEmpty() || (frameTotal == 0L && size == 0)) {
            return "FAILED：还没有任何采集数据（先 --es action start，跑一段，再 --es action stop/export）"
        }
        val dir = context.getExternalFilesDir(null) ?: return "FAILED：外部私有目录不可用（getExternalFilesDir == null）"
        val nowUptime = SystemClock.elapsedRealtime()
        val effStopUptime = if (running) nowUptime else stopUptimeMs
        val effStopWall = if (running) wallFmt.format(Date()) else stopWall
        val durMs = (if (effStopUptime > 0L) effStopUptime - startUptimeMs else 0L).coerceAtLeast(0L)

        val sb = StringBuilder(96 * 1024)
        sb.append("# LiquidGlass FrameTimeline CSV\n")
        sb.append("# session=").append(sid).append('\n')
        sb.append("# started_at=").append(startWall).append('\n')
        sb.append("# stopped_at=").append(if (running) "$effStopWall（导出时仍在采集）" else effStopWall).append('\n')
        sb.append("# started_uptime_ms=").append(startUptimeMs).append('\n')
        sb.append("# stopped_uptime_ms=").append(effStopUptime).append('\n')
        sb.append("# duration_ms=").append(durMs).append('\n')
        sb.append("# frames_total=").append(frameTotal).append('\n')
        sb.append("# refresh_hz=").append("%.2f".format(Locale.US, refreshHz)).append('\n')
        sb.append("# target_frame_ms=").append("%.3f".format(Locale.US, targetFrameNs / 1e6)).append('\n')
        sb.append("# over_vsync_rule=deltaMs>target_frame_ms\n")
        sb.append("# dropped_rule=deltaMs>=1.5*target_frame_ms\n")
        sb.append("# frames_in_buffer=").append(size).append('\n')
        sb.append("# capacity=").append(CAP).append('\n')
        sb.append("# dropped_from_buffer=").append((frameTotal - size).coerceAtLeast(0L)).append('\n')
        val pNow = sampleP()
        sb.append("# p_at_export=").append(if (pNow.isNaN()) "-" else "%.4f".format(Locale.US, pNow)).append('\n')
        sb.append("# switches_at_export=")
        sb.append(DebugBridge.switchNames().joinToString(",") { "$it=${DebugBridge.getSwitch(it)}" })
        sb.append('\n')
        sb.append("index,frameTimeNs,deltaMs,overVsync,targetP\n")

        val base = frameTotal - size        // 缓冲里最旧一帧的绝对序号
        for (i in 0 until size) {
            var k = head - size + i
            if (k < 0) k += CAP
            val dNs = bufDeltaNs[k]
            val p = bufP[k]
            sb.append(base + i).append(',')
                .append(bufFrameNs[k]).append(',')
                .append("%.3f".format(Locale.US, dNs / 1e6)).append(',')
                .append(if (dNs > 0L && dNs > targetFrameNs) 1 else 0).append(',')
            if (p.isNaN()) sb.append('\n') else sb.append("%.4f".format(Locale.US, p)).append('\n')
        }

        val f = File(dir, "frametimeline_$sid.csv")
        val err = runCatching { f.writeText(sb.toString()) }.exceptionOrNull()
        if (err != null) return "FAILED：写盘异常 ${err.javaClass.simpleName}: ${err.message}"
        lastCsvFile = f
        pruneOldCsv(dir, f)
        return "OK action=export csv=${f.absolutePath} rows=$size（帧号 index ${base}..${frameTotal - 1}；p_at_export=${if (pNow.isNaN()) "-" else "%.4f".format(Locale.US, pNow)}）"
    }

    /** 只保留最近 [MAX_EXPORT_FILES] 份 frametimeline_*.csv（[keep] 永不删）。尽力而为，不抛。 */
    private fun pruneOldCsv(dir: File, keep: File) {
        runCatching {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("frametimeline_") && f.name.endsWith(".csv")
            } ?: return@runCatching
            files.asSequence()
                .filter { it.absolutePath != keep.absolutePath }
                .sortedByDescending { it.lastModified() }
                .drop(MAX_EXPORT_FILES - 1)
                .forEach { it.delete() }
        }.onFailure { t ->
            android.util.Log.w("LiquidGlass", "frameTimeline pruneOldCsv failed", t)
        }
    }
}
