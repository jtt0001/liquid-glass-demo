package com.example.liquidglass.performance

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.AttachedSurfaceControl
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import com.example.liquidglass.debug.AppDebugLog
import com.example.liquidglass.debug.DebugSwitches
import java.util.Locale

/**
 * 【主动请求 120Hz】(2026-09-14 用户要求：应用自己主动请求 120Hz，而不是等系统给)
 *
 * 目标：把"本窗口希望以 120Hz 渲染"这件事以【平台认可的通道】告知系统，并留下
 * 「请求值 vs 实际节拍」的日志，供用户在平板上自行 A/B 60/120 两档。
 *
 * ============ 通道（三条同时下发；各自 try/catch，互不阻断） ============
 * ① 窗口/视图层（官方·首选）：`View#setRequestedFrameRate(float)`
 *    —— 本机 SDK 实证：`platforms/android-37/data/api-versions.xml` 里
 *       `<method name="setRequestedFrameRate(F)V" since="35"/>` ⇒ **since=35**（不是 34 ✗，勿凭记忆）；
 *       故 minSdk 33 下必须 `Build.VERSION.SDK_INT >= 35` 守卫 + `@RequiresApi(35)`。
 * ② 表面层（双保险）：`Surface#setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)`
 *    —— api-versions.xml：`setFrameRate(FI)V since="30"`（minSdk 33 ⇒ 无需守卫）。
 *       拿不到句柄时退回 `SurfaceControl.Transaction#setFrameRate(sc, fps, compat)`（since=30）。
 *       句柄来源：本工程没有 SurfaceView/TextureView ⇒ 公开 API 拿不到窗口 Surface
 *       （`View#getRootSurfaceControl()` 返回的 `AttachedSurfaceControl` 不暴露底层句柄 ✗）
 *       ⇒ 只能反射 `ViewRootImpl.mSurface` / `mSurfaceControl`（隐藏 API，可能被平台策略拒绝，
 *       故单独 try/catch：失败只记一行，不影响 ①/③）。
 * ③ 窗口属性（最老、最稳·全版本）：`WindowManager.LayoutParams.preferredRefreshRate = fps`
 *    —— api-versions.xml：`<field name="preferredRefreshRate" since="21"/>`（公开字段 ⇒ 无需守卫）。
 *       语义 = "本窗口希望显示模式取与 fps 最接近者"（DisplayModeDirector 的 appRequest 票）
 *       ⇒ 这是 API 33/34 设备（①不可用）上唯一能用公开 API 表达的"选 120Hz 模式"通道 ✓。
 *
 * ============ 语义（开关两档都是【显式请求】） ============
 * `DebugSwitches.refresh120Hz=true`（默认）⇒ 请求 120Hz；`false` ⇒ 请求 60Hz。
 * 两档都写实值（不是"清除请求"）——因为用户要的是在同一开关上真看出 60/120 的差别
 * （若 60 档只"不请求"，系统可能本来就跑在高刷上 ⇒ 看不出差别 ✗）。
 *
 * ============ 日志（用户要求：能看到「请求值 vs 实际节拍」） ============
 * 每次应用/切换后打两行（logcat tag `LGRefresh` + 应用内调试日志，由 AppDebugLog 门控）：
 * ```
 * LGRefresh requested=120.0 switch=120Hz display=60.0 mode=60.0 supported=[60] view=ok surface=ok sc=ok attrs=ok
 * LGRefresh cadence requested=120.0 switch=120Hz display=60.0 cadenceFps=59.9 frames=72 durMs=1198 maxDeltaMs=16.9
 * ```
 * · `requested` = 我们请求的帧率；`display/mode/supported` = 系统此刻的物理读数；
 * · `cadenceFps` = 用 Choreographer 逐帧时间戳实测的【真实节拍】（fps = (n-1)/Δt）；
 *   （只在 AppDebugLog 打开时采样 ⇒ 关闭时零开销；换档后旧采样自动作废，防混合态读数）
 * · 模拟器只有 60Hz 单模式 ⇒ `requested=120.0 display=60.0 cadenceFps≈60` 是**正确**读数
 *   （它恰好证明"请求"与"实际"是两回事、日志能分开）；真实 120 节拍只能在 120Hz 设备上看到。
 *
 * ============ 调用点 ============
 * `MainActivity`：① `setContent` 里读 `DebugBridge.revision` 的 `LaunchedEffect`（setSwitches 自增 ⇒
 * 翻开关立即重设）；② `onWindowFocusChanged(true)` 重申一次（部分设备失焦后会丢请求）。
 */
object RefreshRateController {

    /** logcat tag（`adb logcat -s LGRefresh`）。 */
    const val TAG = "LGRefresh"

    /** 高刷档目标帧率（默认档）。 */
    const val HZ_HIGH = 120f

    /** 基准档目标帧率（开关置 0）。 */
    const val HZ_BASE = 60f

    /**
     * `View#setRequestedFrameRate(float)` 的最低 API 级别。
     * 实证（不是记忆）：`~/Library/Android/sdk/platforms/android-37/data/api-versions.xml`
     *   `<method name="setRequestedFrameRate(F)V" since="35"/>`
     */
    private const val API_VIEW_REQUESTED_FRAME_RATE = 35

    /** 节奏采样：帧数上限（120Hz ≈ 1.0s / 60Hz ≈ 2.0s 的稳定窗口）。 */
    private const val CADENCE_FRAMES = 120

    /** 节奏采样：时间上限（慢设备兜底，防采不完）。 */
    private const val CADENCE_MAX_NS = 3_000_000_000L

    /**
     * 发请求后等系统落地、且等应用自身启动完成，再开始测节拍。
     * （实测教训：0.5s 就开测会测到启动风暴——首帧 583ms 把 cadenceFps 拉成 17.1 ✗ 不能当"实际节拍"用）
     */
    private const val CADENCE_START_DELAY_MS = 2500L

    /** 最近一次应用的摘要（复读/排查用）。 */
    @Volatile
    var lastLine: String = "<尚未应用>"
        private set

    private val main = Handler(Looper.getMainLooper())

    /** 代次：每次 apply 自增 ⇒ 旧的节奏采样自动作废（防"换档途中测到混合态"✗）。 */
    private var generation = 0

    /** 当前档位的目标帧率（日志里的 `requested`）。 */
    fun targetHz(): Float = if (DebugSwitches.refresh120Hz) HZ_HIGH else HZ_BASE

    private fun switchLabel(): String = if (DebugSwitches.refresh120Hz) "120Hz" else "60Hz"

    /**
     * 把当前档位的帧率请求应用到窗口（幂等；**必须在主线程**调用）。
     * 返回一行结果（同时写 logcat 与应用内日志）。任何通道失败都不抛、不阻断其它通道。
     */
    fun applyToWindow(window: Window?, view: View?): String {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return "SKIP 非主线程（${Thread.currentThread().name}）：帧率请求必须在主线程发出"
        }
        val fps = targetHz()
        val v = view ?: runCatching { window?.decorView }.getOrNull()
        generation += 1
        val gen = generation

        val viewRes = applyViewChannel(v, fps)
        val surfaceRes = applySurfaceChannel(v, fps)
        val attrsRes = applyWindowAttrs(window, fps)

        val d = runCatching { v?.display }.getOrNull()
        val displayHz = runCatching { d?.refreshRate }.getOrNull() ?: Float.NaN
        val modeHz = runCatching { d?.mode?.refreshRate }.getOrNull() ?: Float.NaN
        val supported = runCatching {
            d?.supportedRefreshRates?.joinToString("/") { "%.0f".format(Locale.US, it) }
        }.getOrNull()

        val line = ("requested=%.1f switch=%s display=%.1f mode=%.1f supported=[%s] %s %s %s").format(
            Locale.US, fps, switchLabel(), displayHz, modeHz, supported ?: "?", viewRes, surfaceRes, attrsRes
        )
        lastLine = line
        Log.i(TAG, line)
        AppDebugLog.log(TAG, line)

        // 请求落地后测一段真实节拍（AppDebugLog 关闭时不采样 = 零开销；代次不符自动作废）
        main.postDelayed({ startCadenceSample(v, fps, gen) }, CADENCE_START_DELAY_MS)
        return line
    }

    // ---------------- ① 窗口/视图层：View#setRequestedFrameRate（API 35+） ----------------

    private fun applyViewChannel(v: View?, fps: Float): String {
        if (v == null) return "view=skip(no-view)"
        if (Build.VERSION.SDK_INT < API_VIEW_REQUESTED_FRAME_RATE) {
            return "view=skip(sdk<${API_VIEW_REQUESTED_FRAME_RATE}→本机 ${Build.VERSION.SDK_INT})"
        }
        return runCatching { setViewRequestedFrameRate(v, fps); "view=ok" }
            .getOrElse { t -> "view=err(${t.javaClass.simpleName}:${brief(t)})" }
    }

    /** 单独的 @RequiresApi 函数：把版本守卫收敛在这一处（lint 可静态验证）。 */
    @RequiresApi(API_VIEW_REQUESTED_FRAME_RATE)
    private fun setViewRequestedFrameRate(v: View, fps: Float) {
        v.setRequestedFrameRate(fps)
    }

    // ---------------- ② 表面层：Surface#setFrameRate（API 30+，句柄反射） ----------------

    /**
     * 尽力而为地设置【窗口自己的 Surface】的帧率。
     * 返回简短结果串：`surface=ok` / `surface=ok` + `sc=ok` / `surface=no-root(… )` 等。
     * 隐藏 API 反射被拒绝（NoSuchFieldException / IllegalAccessException / SecurityException）只记结果，不抛。
     */
    private fun applySurfaceChannel(v: View?, fps: Float): String {
        if (v == null) return "surface=skip(no-view)"
        // getRootSurfaceControl() 在视图未 attach 时会抛 IllegalStateException，且 SDK 标注为 @Nullable
        //（视图不在窗口中时为 null）⇒ 两条路径都当作"拿不到根表面"处理
        val asc: AttachedSurfaceControl = runCatching { v.rootSurfaceControl }
            .getOrElse { return "surface=no-root(${it.javaClass.simpleName})" }
            ?: return "surface=no-root(null未attach)"
        // 2a) Surface#setFrameRate(fps, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)（api-versions.xml: since=30）
        val sRes = runCatching {
            val f = asc.javaClass.getDeclaredField("mSurface")
            f.isAccessible = true
            val s = f.get(asc) as? Surface
            if (s == null) "surface=null-field" else {
                s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                "surface=ok"
            }
        }.getOrElse { t -> "surface=err(${t.javaClass.simpleName})" }
        if (sRes == "surface=ok") return sRes
        // 2b) 退回 SurfaceControl.Transaction#setFrameRate(sc, fps, FIXED_SOURCE)（since=30）
        val scRes = runCatching {
            val f = asc.javaClass.getDeclaredField("mSurfaceControl")
            f.isAccessible = true
            val sc = f.get(asc) as? SurfaceControl
            if (sc == null) "sc=null-field" else {
                val t = SurfaceControl.Transaction()
                try {
                    t.setFrameRate(sc, fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE).apply()
                } finally {
                    t.close()
                }
                "sc=ok"
            }
        }.getOrElse { t -> "sc=err(${t.javaClass.simpleName})" }
        return "$sRes $scRes"
    }

    // ---------------- ③ 窗口属性：preferredRefreshRate（公开字段 since=21） ----------------

    /**
     * 写 `WindowManager.LayoutParams.preferredRefreshRate`（= "希望显示模式取与 fps 最接近者"）。
     * 值未变则不重设（避免无谓 relayout）。写实值：120 档写 120、60 档写 60（见类注释的语义说明）。
     */
    private fun applyWindowAttrs(window: Window?, fps: Float): String {
        if (window == null) return "attrs=skip(no-window)"
        return runCatching {
            val attrs = window.attributes
            if (attrs.preferredRefreshRate == fps) return@runCatching "attrs=ok(unchanged)"
            attrs.preferredRefreshRate = fps
            window.attributes = attrs      // 触发 relayout，把新票送给 WM/DisplayModeDirector
            "attrs=ok"
        }.getOrElse { t -> "attrs=err(${t.javaClass.simpleName})" }
    }

    // ---------------- 节奏采样（请求值 vs 实际节拍） ----------------

    /**
     * 用 Choreographer 逐帧时间戳实测一段真实节拍（帧数/时长双上限，主线程）：
     *   · `cadenceFps` = 1e9 / **中位**帧间隔 —— 抗"启动/偶发慢帧"的稳态口径（均值会被一两帧 200ms 拖垮 ✗，已实测）；
     *   · `meanFps`    = 区间均值（= 区间内帧数 / 时长，与 FrameTimelineLogger.stop() 同口径）。
     * 与 FrameTimelineLogger 同源（Choreographer 逐帧 vsync 时间戳）。
     * 只在 AppDebugLog 打开时采样；`gen != generation`（已换档）立即退出 ⇒ 不会留下混合态读数。
     */
    private fun startCadenceSample(v: View?, fps: Float, gen: Int) {
        if (!AppDebugLog.enabled) return
        if (gen != generation) return
        val ch = runCatching { Choreographer.getInstance() }.getOrNull() ?: return
        val deltas = LongArray(CADENCE_FRAMES)
        var frames = 0
        var deltasN = 0
        var firstNs = 0L
        var prevNs = 0L
        var maxDelta = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (gen != generation) return
                if (frames == 0) {
                    firstNs = frameTimeNanos
                } else {
                    val d = frameTimeNanos - prevNs
                    if (d > maxDelta) maxDelta = d
                    if (deltasN < CADENCE_FRAMES) deltas[deltasN] = d
                    deltasN += 1
                }
                frames += 1
                prevNs = frameTimeNanos
                if (deltasN >= CADENCE_FRAMES || (frameTimeNanos - firstNs) >= CADENCE_MAX_NS) {
                    emitCadence(v, fps, deltasN, firstNs, frameTimeNanos, maxDelta, deltas)
                    return
                }
                ch.postFrameCallback(this)
            }
        }
        ch.postFrameCallback(cb)
    }

    private fun emitCadence(
        v: View?, fps: Float, deltasN: Int, firstNs: Long, lastNs: Long, maxDeltaNs: Long, deltas: LongArray
    ) {
        val span = lastNs - firstNs
        val n = deltasN.coerceAtMost(deltas.size)
        val sorted = deltas.copyOf(n).sortedArray()
        val medianNs = if (n > 0) sorted[n / 2] else 0L
        val cadence = if (medianNs > 0L) 1e9 / medianNs else Float.NaN      // 稳态（中位）
        val meanFps = if (n > 0 && span > 0L) n * 1e9 / span else Float.NaN // 区间均值
        val displayHz = runCatching { v?.display?.refreshRate }.getOrNull() ?: Float.NaN
        val line = ("cadence requested=%.1f switch=%s display=%.1f cadenceFps=%.1f meanFps=%.1f " +
            "frames=%d durMs=%.0f maxDeltaMs=%.2f").format(
            Locale.US, fps, switchLabel(), displayHz, cadence, meanFps, deltasN + 1, span / 1e6, maxDeltaNs / 1e6
        )
        Log.i(TAG, line)
        AppDebugLog.log(TAG, line)
    }

    /** 异常消息截断（日志一行内可读）。 */
    private fun brief(t: Throwable): String {
        val m = t.message ?: ""
        return m.take(60).replace('\n', ' ')
    }
}
