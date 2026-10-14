package com.example.liquidglass

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import com.example.liquidglass.debug.AppDebugLog
import com.example.liquidglass.debug.DebugBridge
import com.example.liquidglass.debug.DebugSwitches
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.example.liquidglass.performance.JankStatsMonitor
import com.example.liquidglass.performance.PerformanceMonitor
import com.example.liquidglass.performance.RefreshRateController
import com.example.liquidglass.ui.LiquidGlassScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var monitor: PerformanceMonitor? = null

    /**
     * 调试日志自动导出的后台作用域：onStop 只投递任务，写盘在 IO 线程。
     * 刻意不在 onDestroy 里 cancel —— 这次导出的用途正是"App 被杀前留下现场"，
     * 取消它等于取消导出本身；任务只写几十 KB 且即发即完，无需生命周期托管。
     */
    private val logExportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 【深色/亮色模式·2026-09-24】启动即恢复主题档位（SharedPreferences，与背景选择同一套文件）
        // ⇒ 切到强制暗档后重进 App 仍是暗档 ✓。默认 FOLLOW_SYSTEM（首次安装 / 没存过时）。
        com.example.liquidglass.ui.GlassTheme.load(this)

        // HDR 边缘高光：本机屏幕支持 HDR（实测 mMaxLuminance=1000nits、
        // supportedHdrTypes=[1,2,3,4]）。请求 HDR 色彩模式后，Shader 输出可以超过
        // SDR 白点（1.0）——贴边高光就能真正"发光"而不是被截断成纯白。
        // 不支持 HDR 的设备/窗口会自动忽略该请求，高光照旧（值被截到 1.0），无副作用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.attributes = window.attributes.apply {
                colorMode = ActivityInfo.COLOR_MODE_HDR
            }
        }
        val caps = window.decorView.display?.hdrCapabilities
        Log.i(
            "LGHdr",
            "colorMode=${window.attributes.colorMode} hdrTypes=${caps?.supportedHdrTypes?.joinToString()} " +
                "maxLum=${caps?.desiredMaxLuminance}"
        )

        // 性能监控：独立 HandlerThread + StateFlow，随 Activity 生命周期启停
        monitor = PerformanceMonitor(window, applicationContext)

        // 【AI 调试接口】把帧统计（PerformanceMonitor 快照）发布给广播调试桥（见 debug/DebugBridge.kt）。
        // 仅在 debug 构建注册（release 下 receiver 直接 return，永不读取）；只捕获 monitor 实例
        //（不捕获 Activity），onDestroy 注销 —— 不引入任何常驻引用。
        if (DebugBridge.isDebugBuild(this)) {
            val m = monitor
            DebugBridge.frameStats = { m?.snapshot?.value }
            // 【P65 修复③·取证通道】perfMonitor --es action <stop|start|restart|status> 驱动
            // PerformanceMonitor.start()/stop()（与 LiquidGlassScreen DisposableEffect 同一对入口）
            // ⇒ stop→start 可重复使用可在机上验证（restart 后 dumpState frames 行继续更新）。
            DebugBridge.perfControl = { action ->
                when (action) {
                    "stop" -> { m?.stop(); "stopped（frames 冻结；再 --es action start 看是否恢复）" }
                    "start" -> { m?.start(); "started（perfRestartable=1 重建线程 ✓ / =0 则=改动前：静默不恢复）" }
                    "restart" -> { m?.stop(); m?.start(); "restarted（同一实例 stop→start）" }
                    else -> "started=${m?.isStarted()}"
                }
            }
        }

        setContent {
            // 【主动请求 120Hz】订阅 DebugBridge.revision：setSwitches 翻转 refresh120Hz 后自增 ⇒ 立即重设请求。
            // （DebugSwitches 是 @Volatile 普通字段、不产生订阅 ⇒ 必须陪读修订号，否则翻转后停在旧档；
            //   这是本项目已两次踩过的"混合态"坑，见 DebugSwitches.kt 的运行时 A/B 必读注释。）
            val refreshRevision = DebugBridge.revision.intValue
            LaunchedEffect(refreshRevision) {
                RefreshRateController.applyToWindow(window, window.decorView)
                // P18：官方 JankStats 监视器同步点（幂等）——jankStatsMonitor=true → start；false → stop。
                // 订阅同一个 revision ⇒ setSwitches 翻转后立即生效（无需重启）；默认 false = 零开销。
                syncJankStatsMonitor()
            }
            LiquidGlassScreen(monitor!!)
        }
    }

    /**
     * 【P18 官方 JankStats 同步点】（幂等）jankStatsMonitor=true → start；false → stop。
     * 由 setContent 里订阅 DebugBridge.revision 的 LaunchedEffect 调用（setSwitches 后 revision 自增）；
     * onDestroy 兜底再 stop 一次（幂等，防 listener 泄漏）。
     */
    private fun syncJankStatsMonitor() {
        val want = DebugSwitches.jankStatsMonitor
        if (want == JankStatsMonitor.isRunning()) return
        val line = if (want) JankStatsMonitor.start(window) else JankStatsMonitor.stop()
        AppDebugLog.log("JankStats", "switch=" + (if (want) "on" else "off") + " " + line)
        Log.i("JankStats", "switch=" + (if (want) "on" else "off") + " " + line)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 【主动请求 120Hz】获得焦点时重申一次：部分设备在失焦/切后台后会丢掉 app 的帧率请求
        // （applyToWindow 幂等；属性值未变时不会触发多余 relayout）
        if (hasFocus) RefreshRateController.applyToWindow(window, window.decorView)
    }

    override fun onDestroy() {
        // 【AI 调试接口】注销帧统计来源（避免调试桥持有已销毁的 monitor）
        DebugBridge.frameStats = null
        DebugBridge.perfControl = null
        // 注销 FrameMetrics 监听并安全退出 HandlerThread（与组合内 DisposableEffect 双保险）
        monitor?.stop()
        monitor = null
        // P18：官方 JankStats 监视器兜底停（幂等：未开时返回 NOT running，不报错；防 listener 泄漏）
        JankStatsMonitor.stop()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // 用户便利：切到后台即自动导出一次调试日志（环形缓冲很小，开销可忽略）。
        // ① 只在调试日志开启时执行：关闭时零开销（不开协程、不碰磁盘）；
        // ② 写盘交给 IO 线程（原来在主线程直接 export → 阻塞主线程 + 无异常兜底）；
        // ③ 历史文件不再堆积：AppDebugLog 内部只保留最近 5 个 debug_*.txt。
        if (!AppDebugLog.enabled) return
        logExportScope.launch { AppDebugLog.exportAsync(this@MainActivity) }
    }
}
