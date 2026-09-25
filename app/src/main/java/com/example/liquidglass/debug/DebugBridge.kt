package com.example.liquidglass.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.example.liquidglass.diamond.DiamondBridgeApi
import com.example.liquidglass.glass.GlassDebugMode
import com.example.liquidglass.glass.GlassParameters
import com.example.liquidglass.glass.GlassPreset
import com.example.liquidglass.glass.GlassQuality
import com.example.liquidglass.glass.GlassShape
import com.example.liquidglass.glass.ProximityFusion
import com.example.liquidglass.performance.FrameTimelineLogger
import com.example.liquidglass.performance.JankStatsMonitor
import com.example.liquidglass.performance.PerformanceSnapshot
import com.example.liquidglass.ui.GlassUiState
import com.example.liquidglass.ui.PINCH_BRIDGE_REF_SPAN_PX
import com.example.liquidglass.ui.applyPinchResizeForCard
import com.example.liquidglass.ui.restoreBackgroundSelection
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 【AI 调试接口】adb 驱动的调试桥 —— 让助手不依赖图形界面、不靠盲点坐标地驱动与读取应用状态。
 *
 * 命令入口 = [DebugBridgeReceiver]（AndroidManifest.xml 里以 action=[ACTION] 声明，exported=true）。
 *
 * 白名单命令（全部只作用于本进程内存状态，不提供任意文件读写/执行能力）：
 *   setDebugMode     --ei value <0..5>                  切换 Shader 调试模式（逐层排查光学管线）
 *   setSwitches      --es name <开关名> --ei value 0/1   改 DebugSwitches 的布尔开关（反射枚举全部字段）
 *   setPanelP        --ef value <0..2>                  把控制中心面板设到指定进度 p（0..2）
 *   setUi            --es key <白名单键> [--ei ivalue <n> | --ef fvalue <x> | --es svalue <s>]
 *                                                       改 uiState 白名单字段（主线程写入 + 回读打印）
 *                                                       key=pinchScale（【双指捏合改尺寸】的注入通道：
 *                                                       --ef fvalue <跨度比>，如 1.2 = 双指距离放大 1.2 倍；
 *                                                       与手指捏合走【同一个函数】applyPinchResizeForCard，
 *                                                       因为 adb 合成 pinch 触发不了真手势 ✗）
 *   setPos           --ei card <0..3> --ef x <px> --ef y <px>
 *                                                       把第 N 块卡的 offsetX/offsetY 直接写成指定值
 *                                                       （把玻璃摆到指定距离/重叠度做 A/B；0=主卡 1=第二块 2/3=多卡追加块）
 *   cardTop          --ei card <0..3>                    【P06】把第 N 块卡置顶（移到 z 序末尾 = 最上层）
 *                                                       （与手指点击走同一实现；z 序见 dumpState 的 cardZ 行）
 *   dumpState                                           把关键状态写进 AppDebugLog 并立即导出到 files/
 *   dumpFrameStats                                      打印当前帧统计摘要（PerformanceMonitor 快照）
 *   frameTimeline    --es action <start|stop|export|status>
 *                                                       逐帧时间戳日志（帧率分析仪，performance/FrameTimelineLogger）：
 *                                                       start=开始逐帧采集 / stop=停止 / export=把环形缓冲
 *                                                       导出成 files/frametimeline_<session>.csv / status=看一眼状态。
 *                                                       总开关 = DebugSwitches.frameTimeline（默认 false，
 *                                                       先 setSwitches 打开；采集途中置 false 会自动停）。
 *   diamond          --es action <open|close|set|spin|params|dump>  [--ef yaw/--ef pitch/--ef speed/
 *                                                                 [--ei bounces/--ef dispersion/--ef ior/
 *                                                                  --es cut/--ei debug]
 *                                                       【3D 钻石演示】打开/关闭演示页 · 定格姿态 · 设转速 ·
 *                                                       改光学参数 · 状态一行（桥 = DebugBridge.diamond）
 *
 * 【安全 / 门控】仅 debug 构建生效，见 [isDebugBuild]（release 下 receiver 第一行就 return，
 * 不解析参数、不反射、不落盘）。
 *
 * 结果读取：logcat（tag = [TAG]）+ 应用内调试日志（dumpState / dumpFrameStats 会同步导出一次到
 * /sdcard/Android/data/com.liqglass.ultraclear/files/debug_<session>.txt，adb pull 可读）。
 */
object DebugBridge {

    /** 广播 action（与 AndroidManifest.xml 中声明一致）。 */
    const val ACTION = "com.liqglass.DEBUG"

    /** logcat tag：`adb logcat -s AIDebug`。 */
    const val TAG = "AIDebug"

    // ---------------- 由应用侧发布句柄（UI 不在运行时为 null；全部在主线程读写） ----------------

    /** 当前存活的页面状态（debugMode 等）。由 LiquidGlassScreen 在组合期注册 / 卸载。 */
    @Volatile
    var uiState: GlassUiState? = null

    /** 面板桥（进度 p 读写 + 状态转储）。由 LiquidGlassScreen 在组合期注册 / 卸载。 */
    @Volatile
    var panel: Panel? = null

    /** 帧统计来源（PerformanceMonitor 快照）。由 MainActivity 注册 / 卸载。 */
    @Volatile
    var frameStats: (() -> PerformanceSnapshot?)? = null

    /**
     * 【P65 修复③·取证通道】性能监控 start/stop 驱动（perfMonitor 命令；先例 = cardTop 的程序化取证通道）。
     * 由 MainActivity 注册 / 卸载；入参 action = "stop" / "start" / "restart" / "status"，返回一行结果。
     * 用途：机上复现 DisposableEffect 的 stop→start 语义，验证 stop→start 后监控确实恢复（frames 行继续更新）。
     */
    @Volatile
    var perfControl: ((String) -> String)? = null

    /** 面板二级页（“更多设置”）开关桥：由 LiquidGlassScreen 在组合期注册（setUi key=advanced 读写）。 */
    @Volatile
    var advanced: AdvancedToggle? = null

    /** 玻璃卡位置桥：由 LiquidGlassScreen 在组合期注册（setPos / dumpState 的 card 行读写）。 */
    @Volatile
    var cards: Cards? = null

    /**
     * 【3D 钻石演示】调试桥：由 ui/LiquidGlassScreen.kt 在组合期注册（cmd=diamond）。
     * null = 演示模块未接线（命令返回 FAILED 一行，不影响任何既有命令）。
     */
    @Volatile
    var diamond: DiamondBridgeApi? = null

    /** 面板二级页开关（get = 回读；set = 写入；实现方保证写的是 Compose 状态）。 */
    interface AdvancedToggle {
        fun get(): Boolean
        fun set(value: Boolean)
    }

    /** 玻璃卡几何/位置桥（card 0=主卡、1=第二块卡；坐标均为窗口 px）。 */
    interface Cards {
        /** 当前实际渲染的卡片数量（1 / 2 / 3 / 4；≥3 需要 multiCardDemo=true 且 uiState.cardCount）。 */
        fun count(): Int

        /** 第 n 块卡的窗口 rect [left, top, w, h]（px）；索引无效返回 null。 */
        fun rect(card: Int): FloatArray?

        /** 第 n 块卡的当前 offset（相对默认位置，px）；索引无效返回 null。 */
        fun currentOffset(card: Int): Pair<Float, Float>?

        /** 把第 n 块卡的 offset 直接写成指定值（绕过手势钳制）；返回写入后回读值。 */
        fun setOffset(card: Int, x: Float, y: Float): Pair<Float, Float>?

        /**
         * 【P06 点击置顶】当前 z 序（**绘制顺序**，列表尾 = 最上层），元素是卡片索引。
         * 只列出当前实际渲染的卡片（默认 2 卡时 = [0, 1]，与改动前的兄弟绘制顺序逐位相同）。
         */
        fun zOrder(): List<Int>

        /**
         * 【P06 点击置顶】把第 n 块卡移到 z 序末尾（最上层）；索引无效返回 null，
         * 否则返回移动后的 z 序字符串（如 "0,2,1"）供命令回读。
         */
        fun raiseToFront(card: Int): String?
    }

    /**
     * 调试修订号（Compose 状态）：setSwitches 命令写入开关后自增 → 触发订阅它的组合作用域
     * 重组一次，让 DebugSwitches 的 volatile 开关（在组合阶段被读取的那些）立即生效
     * （普通 @Volatile 字段本身不产生任何订阅，不递增修订号就要等下一次自然重组）。
     */
    val revision = mutableIntStateOf(0)

    /** 面板控制接口（由屏幕实现；p 的单位与语义见 ui/LiquidGlassScreen.kt 的 pOf/pNow）。 */
    interface Panel {
        /** 当前进度 p（0=收起胶囊，1=标准控制中心，2=满高）。 */
        fun currentP(): Float

        /** 写进度 p（0..2，越界钳制）；返回写入后的实际 p。 */
        fun setP(p: Float): Float

        /** 面板几何/透明度/门控转储（多行）。 */
        fun dumpState(): String
    }

    // ---------------- 命令名（白名单） ----------------

    private const val CMD_SET_DEBUG_MODE = "setDebugMode"
    private const val CMD_SET_SWITCHES = "setSwitches"
    private const val CMD_SET_PANEL_P = "setPanelP"
    private const val CMD_SET_UI = "setUi"
    private const val CMD_SET_POS = "setPos"
    private const val CMD_DUMP_STATE = "dumpState"
    private const val CMD_DUMP_FRAME_STATS = "dumpFrameStats"
    private const val CMD_FRAME_TIMELINE = "frameTimeline"
    private const val CMD_JANK_STATS = "jankStats"
    private const val CMD_SET_FUSION = "setFusion"
    /** 【P06 点击置顶】程序化置顶（人工点击之外的取证/复现通道）。 */
    private const val CMD_CARD_TOP = "cardTop"
    /** 【P44】贴边发丝带支撑宽度标定通道（float ⇒ 不能走 setSwitches，单独一条命令）。 */
    private const val CMD_SET_EDGE_HAIR_SUPPORT = "setEdgeHairSupport"
    /** 【P65 修复③】性能监控 start/stop/status 取证通道（stop→start 可重复使用验证）。 */
    private const val CMD_PERF_MONITOR = "perfMonitor"
    /** 【3D 钻石演示·钻石分支】open/close/set/spin/params/dump（桥 = DebugBridge.diamond）。 */
    private const val CMD_DIAMOND = "diamond"
    /**
     * 【深色/亮色模式·2026-09-24】主题档位切换（--ei value 0|1|2 = 跟随系统/强制亮色/强制暗色）。
     * 与面板「更多设置 › 外观设置 › 外观」里的三档 chips 走【同一个入口】GlassTheme.set（落盘 SharedPreferences）
     * ⇒ adb 通道与 UI 通道行为完全一致 ✓。
     */
    private const val CMD_SET_THEME = "setTheme"

    // 【合并口径】白名单 = 主线侧 12 条（含 P44 setEdgeHairSupport）+ P65 perfMonitor + 钻石侧 diamond = 14 条，
    // ✗ 一条都不删（-X ours/theirs 会把另一边的命令整条吃掉，所以逐条并）。
    private val ALL_COMMANDS = listOf(
        CMD_SET_DEBUG_MODE, CMD_SET_SWITCHES, CMD_SET_PANEL_P, CMD_SET_UI, CMD_SET_POS,
        CMD_DUMP_STATE, CMD_DUMP_FRAME_STATS, CMD_FRAME_TIMELINE, CMD_JANK_STATS, CMD_SET_FUSION,
        CMD_CARD_TOP, CMD_SET_EDGE_HAIR_SUPPORT, CMD_PERF_MONITOR, CMD_DIAMOND, CMD_SET_THEME
    )

    /** 立即把日志导出到 files/ 的命令（其余命令只进 logcat + 内存缓冲）。 */
    private val EXPORT_COMMANDS = setOf(CMD_DUMP_STATE, CMD_DUMP_FRAME_STATS)

    fun commandList(): String = ALL_COMMANDS.joinToString(" / ")

    fun shouldExport(cmd: String): Boolean = cmd in EXPORT_COMMANDS

    // ---------------- 门控 ----------------

    /**
     * 【仅 debug 构建生效】本模块未开启 buildFeatures.buildConfig（AGP 9 默认关闭 buildConfig，
     * 项目未声明 → 无法引用 BuildConfig.DEBUG），因此用**语义等价**的判据：
     * debug buildType 会在合并清单里注入 android:debuggable="true"（实测
     * intermediates/merged_manifest/debug/…=true），而 release 不设置该属性（=false）。
     */
    fun isDebugBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // ---------------- 执行入口 ----------------

    /** 执行一条白名单命令，返回要写进日志的行（可能多行）。 */
    fun execute(context: Context, cmd: String, intent: Intent): List<String> {
        // 双保险：release 下任何命令都不执行（receiver 已先门控一次）
        if (!isDebugBuild(context)) return emptyList()
        return try {
            when (cmd) {
                CMD_SET_DEBUG_MODE -> setDebugMode(intent)
                CMD_SET_SWITCHES -> setSwitches(intent)
                CMD_SET_PANEL_P -> setPanelP(intent)
                CMD_SET_UI -> setUi(context, intent)
                CMD_SET_POS -> setPos(intent)
                CMD_DUMP_STATE -> dumpState(context)
                CMD_DUMP_FRAME_STATS -> dumpFrameStats()
                CMD_FRAME_TIMELINE -> frameTimeline(context, intent)
                CMD_JANK_STATS -> jankStats(intent)
                CMD_SET_FUSION -> setFusion(intent)
                CMD_CARD_TOP -> cardTop(intent)
                CMD_SET_EDGE_HAIR_SUPPORT -> setEdgeHairSupport(intent)
                CMD_PERF_MONITOR -> perfMonitor(intent)
                CMD_DIAMOND -> diamond(intent)
                CMD_SET_THEME -> setTheme(context, intent)
                else -> listOf("ERROR unknown cmd='$cmd'；可用命令：${commandList()}")
            }
        } catch (t: Throwable) {
            listOf("ERROR cmd=$cmd ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------- 1) setDebugMode ----------------

    private fun setDebugMode(intent: Intent): List<String> {
        val v = intArg(intent, "value")
            ?: return listOf("setDebugMode MISSING：需要 --ei value <0..5>；可用：" + debugModeList())
        val mode = GlassDebugMode.entries.firstOrNull { it.shaderCode == v }
            ?: return listOf("setDebugMode INVALID value=$v；可用：${debugModeList()}")
        val ui = uiState
            ?: return listOf("setDebugMode FAILED：应用 UI 未运行（uiState 未注册）→ 先 am start 启动应用再发命令")
        ui.debugMode = mode
        return listOf("setDebugMode OK value=$v → ${mode.name}('${mode.displayName}')；uiState.debugMode=${ui.debugMode.name}")
    }

    private fun debugModeList(): String =
        GlassDebugMode.entries.joinToString(" ") { "${it.shaderCode}=${it.name}" }

    // ---------------- 2) setSwitches ----------------
    // ---------------- 1c) setTheme（【深色/亮色模式】档位切换；纯新增，不改任何既有命令行为） ----------------

    /**
     * `setTheme --ei value <0|1|2>`：0=跟随系统（isSystemInDarkTheme）/ 1=强制亮色 / 2=强制暗色。
     * 落盘 SharedPreferences（与背景选择同一套文件）⇒ 重进 App 仍生效 ✓；立即回读确认。
     */
    private fun setTheme(context: Context, intent: Intent): List<String> {
        val v = intArg(intent, "value")
            ?: return listOf("setTheme MISSING：需要 --ei value <0|1|2>（0=跟随系统 1=强制亮色 2=强制暗色）")
        val m = com.example.liquidglass.ui.GlassThemeMode.entries.firstOrNull { it.id == v }
            ?: return listOf("setTheme INVALID value=$v；可用：" +
                com.example.liquidglass.ui.GlassThemeMode.entries.joinToString(" ") { "${it.id}=${it.label}" })
        com.example.liquidglass.ui.GlassTheme.setFromBridge(context.applicationContext, v)
        val stored = context.getSharedPreferences(
            com.example.liquidglass.ui.GlassTheme.PREFS_NAME, Context.MODE_PRIVATE
        ).getInt("theme_mode", -1)
        return listOf(
            "setTheme OK value=$v → ${m.label}；GlassTheme.mode=${com.example.liquidglass.ui.GlassTheme.mode.label}" +
                "；已落盘 theme_mode=$stored（prefs=${com.example.liquidglass.ui.GlassTheme.PREFS_NAME}）"
        )
    }

    // ---------------- 1b) diamond（【3D 钻石演示】桥；纯新增，不改变任何既有命令行为） ----------------

    /**
     * 钻石演示命令：`--es cmd diamond --es action <open|close|set|spin|params|dump>`
     *   open / close                                打开 / 关闭演示页
     *   set    --ef yaw <deg> --ef pitch <deg>      定格姿态（度）
     *   spin   --ef speed <degPerSec>               转速（0 = 停）
     *   params [--ei bounces 0..4] [--ef dispersion <x>] [--ef ior <x>] [--es cut <NAME>] [--ei debug 0..4]
     *          [--ei envModel 0|1] [--ei trappedFix 0|1] [--ei envYFix 0|1]
     *   dump                                        状态一行
     * 数值入口一律先挡 NaN/Inf（Float.coerceIn 对 NaN 放行，本项目已踩过：写 NaN 会把几何报废）。
     */
    private fun diamond(intent: Intent): List<String> {
        val bridge = diamond
            ?: return listOf("diamond FAILED：演示桥未注册（应用 UI 未运行或本构建未接线）→ 先 am start 启动应用再发命令")
        val action = intent.getStringExtra("action") ?: "dump"
        return try {
            when (action) {
                "open" -> listOf(bridge.setVisible(true))
                "close" -> listOf(bridge.setVisible(false))
                "set" -> {
                    val yaw = floatArg(intent, "yaw")
                    val pitch = floatArg(intent, "pitch")
                    if (yaw == null || pitch == null) {
                        listOf("diamond set MISSING：需要 --ef yaw <deg> --ef pitch <deg>")
                    } else if (!yaw.isFinite() || !pitch.isFinite()) {
                        listOf("diamond set INVALID：NaN/Inf 被拒（yaw=$yaw pitch=$pitch）")
                    } else listOf(bridge.setRotation(yaw, pitch))
                }
                "spin" -> {
                    val s = floatArg(intent, "speed")
                    if (s == null) listOf("diamond spin MISSING：需要 --ef speed <degPerSec>（0 = 停）")
                    else if (!s.isFinite()) listOf("diamond spin INVALID：NaN/Inf 被拒（speed=$s）")
                    else listOf(bridge.setSpin(s))
                }
                "params" -> {
                    val disp = floatArg(intent, "dispersion")
                    val ior = floatArg(intent, "ior")
                    if ((disp != null && !disp.isFinite()) || (ior != null && !ior.isFinite())) {
                        listOf("diamond params INVALID：NaN/Inf 被拒（dispersion=$disp ior=$ior）")
                    } else {
                        listOf(
                            bridge.setParams(
                                bounces = intArg(intent, "bounces"),
                                dispersion = disp,
                                ior = ior,
                                cutName = intent.getStringExtra("cut"),
                                debugMode = intArg(intent, "debug"),
                                envModel = intArg(intent, "envModel"),
                                trappedFix = intArg(intent, "trappedFix"),
                                envYFix = intArg(intent, "envYFix")
                            )
                        )
                    }
                }
                "dump" -> listOf(bridge.dump())
                else -> listOf("diamond ERROR unknown action='$action'；可用：open / close / set / spin / params / dump")
            }
        } catch (t: Throwable) {
            listOf("ERROR cmd=diamond action=$action ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun setSwitches(intent: Intent): List<String> {
        val names = switchNames()
        val current = names.joinToString(", ") { "$it=${getSwitch(it)}" }
        if (names.isEmpty()) return listOf("setSwitches: DebugSwitches 里没有 Boolean 字段（契约见 DebugSwitches 注释）")
        val name = intent.getStringExtra("name")
            ?: return listOf("setSwitches MISSING --es name；全部可用开关：$names", "current: $current")
        val v = intArg(intent, "value")
            ?: return listOf("setSwitches MISSING --ei value 0/1；全部可用开关：$names", "current: $current")
        if (name !in names) return listOf("setSwitches UNKNOWN name='$name'；全部可用开关：$names", "current: $current")
        setSwitch(name, v != 0)
        revision.intValue = revision.intValue + 1   // 触发一次重组，让 volatile 开关立即生效
        return listOf(
            "setSwitches OK：$name=${getSwitch(name)}（收到 value=$v）",
            "全部开关当前值：${switchNames().joinToString(", ") { "$it=${getSwitch(it)}" }}"
        )
    }

    // ---------------- 3) setPanelP ----------------

    private fun setPanelP(intent: Intent): List<String> {
        val raw = floatArg(intent, "value")
            ?: return listOf("setPanelP MISSING：需要 --ef value <0..2>")
        // 【审计修复】coerceIn 对 NaN 放行 ✗ ⇒ 会写出 NaN offset，之后每帧读 offset 都抛异常（面板报废）
        if (!raw.isFinite()) return listOf("setPanelP INVALID（只接受有限数 0..2；NaN/Inf 会让面板报废 ✗）")
        val target = raw.coerceIn(0f, 2f)
        val bridge = panel
            ?: return listOf("setPanelP FAILED：面板桥未注册（应用 UI 未运行）→ 先 am start 启动应用再发命令")
        val before = bridge.currentP()
        val after = bridge.setP(target)
        val clamped = if (raw != target) "，已钳制到 0..2" else ""
        return listOf(
            "setPanelP OK p %.4f → %.4f（target=%.4f%s；直接写 p，停在中途，不受松手兜底影响）"
                .format(Locale.US, before, after, target, clamped)
        )
    }

    // ---------------- 4) setUi（白名单字段 → uiState / 面板二级页） ----------------

    /**
     * setUi 白名单键（--es key <键名>；值走 --ei ivalue / --ef fvalue，字符串走 --es svalue）。
     *
     * 契约：写的是 GlassUiState 的字段（以及 'advanced' 的面板二级页开关桥）；
     * **全部 post 到主线程执行**（Compose 快照状态禁止跨线程写 → 并发写会抛 SnapshotApplyConflict ✗），
     * 写完自增 [DebugBridge.revision] 触发一次重组，并把新值回读打印成 `setUi OK: key=值`。
     */
    private val UI_KEYS = listOf(
        "glassSize", "selectedCard", "shape", "shape2", "shape3", "shape4", "cards", "twoCardDemo", "advanced",
        "quality", "bgBuiltinIndex", "bgText", "iosLensProfile", "reduceMotion",
        "stretchOnDrag", "adaptiveLegibility", "debugLogEnabled",
        "stylePos", "preset",   // 【玻璃风格拉杆】stylePos(0..1) / preset(0..2 预设序数)
        "pinchScale"            // 【双指捏合改尺寸】跨度比注入（与手势同一个函数；合成 pinch 不可靠 ✗）
    )

    private fun setUi(context: Context, intent: Intent): List<String> {
        val ui = uiState
            ?: return listOf("setUi FAILED：应用 UI 未运行（uiState 未注册）→ 先 am start 启动应用再发命令")
        val key = intent.getStringExtra("key")
            ?: return listOf("setUi MISSING --es key；可用键：${UI_KEYS.joinToString(" / ")}")
        if (key !in UI_KEYS) {
            return listOf("setUi UNKNOWN key='$key'；可用键：${UI_KEYS.joinToString(" / ")}")
        }
        val iv = intArg(intent, "ivalue")
        val fv = floatArg(intent, "fvalue")
        val sv = intent.getStringExtra("svalue")
        if (iv == null && fv == null && sv == null) {
            return listOf("setUi MISSING 值：需要 --ei ivalue <n> 或 --ef fvalue <x>（key=$key）")
        }
        // 【主线程写入】统一 post 到主线程队列执行（广播通常已在主线程，但这样即使未来从别处调用
        // 也不会跨线程写快照状态）。结果行在写入后自行打点（logcat 里是 [setUi] setUi OK: …）。
        Handler(Looper.getMainLooper()).post {
            val line = runCatching { applyUiKey(context, ui, key, iv, fv, sv) }
                .getOrElse { t -> "setUi ERROR ${t.javaClass.simpleName}: ${t.message}" }
            Log.i(TAG, "[setUi] $line")
            AppDebugLog.log("AIDBG", "[setUi] $line")
            revision.intValue = revision.intValue + 1   // 写后触发一次重组（与 setSwitches 同机制）
        }
        return listOf("setUi 已提交主线程（key=$key ivalue=$iv fvalue=$fv svalue=$sv）；写入结果见下一条 [setUi OK:…]")
    }

    /** 白名单键的实际写入（**必须在主线程调用**）；返回要记录的一行结果。 */
    private fun applyUiKey(
        context: Context, ui: GlassUiState, key: String, iv: Int?, fv: Float?, sv: String?
    ): String {
        fun needValue(hint: String) = "setUi FAILED: key=$key 缺有效值（$hint）"
        return when (key) {
            "glassSize" -> {
                val target = fv ?: iv?.toFloat() ?: return needValue("需要 --ef fvalue <0.2..0.5>")
                // 【选中卡·用户需求①】与面板「玻璃尺寸」滑块【同口径】：写【当前选中卡】的尺寸。
                // 默认选中卡 = 0 = 主卡 ⇒ 与改动前逐字一致（写的就是 ui.glassSize ✓，范围也还是 0.2..0.5）。
                val i = ui.selectedCardIndex
                val old = ui.sizeOfCard(i)
                ui.setSizeOfCard(i, target)
                val r = ui.sizeRangeOfCard(i)
                "setUi OK: %s 尺寸=%.2f（old=%.2f；选中卡 index=%d；范围 %.2f..%.2f 同面板滑块）".format(
                    ui.cardDisplayName(i), ui.sizeOfCard(i), old, i, r.start, r.endInclusive
                )
            }
            "pinchScale" -> {
                // 【双指捏合改尺寸】设备取证/复现通道：**与手指捏合调用同一个函数**
                // （ui/CardPinchResize.kt 的 applyPinchResizeForCard ⇒ 换算 + 夹取 + setSizeOfCard + 选中该卡）。
                // 为什么需要它：adb 合成双指手势触发不了真 pinch（项目已知 ✗），而"捏合 → 尺寸"这条链
                // 在手指路径与命令路径里必须是同一段代码，否则量出来的数字证明不了手指那条路 ✓。
                val s = fv ?: iv?.toFloat()
                    ?: return needValue("需要 --ef fvalue <跨度比>，如 1.2 = 双指距离放大 1.2 倍（0.8 = 缩小）")
                if (!s.isFinite()) return "setUi FAILED: key=pinchScale 只接受有限数（NaN/Inf 一律拒绝 ✗）"
                val i = ui.selectedCardIndex
                val old = ui.sizeOfCard(i)
                val r = ui.sizeRangeOfCard(i)
                val next = applyPinchResizeForCard(
                    ui, i, PINCH_BRIDGE_REF_SPAN_PX, PINCH_BRIDGE_REF_SPAN_PX * s, source = "bridge"
                )
                val clamped = when {
                    next <= r.start + 1e-4f -> "已夹在【下限】"
                    next >= r.endInclusive - 1e-4f -> "已夹在【上限】"
                    else -> "未夹取"
                }
                // ⚠️ Kotlin 里 `"a" + "b".format(…)` 的 `.format` 只作用于第二个字面量（坑了一次：
                // IllegalFormatConversionException: d != Float）⇒ 必须先把整条格式串拼进一个 val。
                val okFmt = "setUi OK: 捏合 pinchScale=%.3f（参考跨度 %.0fpx → %.0fpx）%s 尺寸 %.4f→%.4f（old=%.4f；" +
                    "选中卡 index=%d；范围 %.2f..%.2f 同面板滑块；%s；与手指捏合同一函数）"
                okFmt.format(
                    Locale.US, s, PINCH_BRIDGE_REF_SPAN_PX, PINCH_BRIDGE_REF_SPAN_PX * s,
                    ui.cardDisplayName(i), ui.sizeOfCard(i), next, old, i, r.start, r.endInclusive, clamped
                )
            }
            "shape" -> {
                val shape = shapeArg(iv, fv)
                    ?: return needValue("需要 --ei ivalue <0..${GlassShape.entries.size - 1}>（GlassShape 序数）")
                val old = ui.selectedShapes.firstOrNull()
                if (ui.selectedShapes.isEmpty()) ui.selectedShapes.add(shape) else ui.selectedShapes[0] = shape
                "setUi OK: shape=${shape.name}（ivalue=${shape.ordinal}，displayName='${shape.displayName}'，" +
                    "old=${old?.name}；uiState.glassShape=${ui.glassShape.name}）"
            }
            "shape2" -> {
                val shape = shapeArg(iv, fv)
                    ?: return needValue("需要 --ei ivalue <0..${GlassShape.entries.size - 1}>（GlassShape 序数）")
                if (ui.selectedShapes.size < 2) ui.selectedShapes.add(shape) else ui.selectedShapes[1] = shape
                val note = if (!ui.twoCardDemo) "；twoCardDemo=false → 第二块卡不显示（先 setUi twoCardDemo ivalue 1）" else ""
                "setUi OK: shape2=${shape.name}（uiState.secondShape=${ui.secondShape?.name}）$note"
            }
            "cards" -> {
                val n = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue <2|3|4>")
                if (n !in 2..4) {
                    "setUi NOTE: cards=$n 超出范围（当前支持 2~4）。2=双卡（twoCardDemo=true），" +
                        "3/4=多卡演示（需 DebugSwitches.multiCardDemo=true，默认开）✓"
                } else {
                    ui.twoCardDemo = true
                    // 【需求④】合并后的「多卡演示」开关 = twoCardDemo ＋ 多卡总闸；桥命令与面板同口径
                    // （否则面板关掉总闸后、桥把档位调上去会"看不见" ✗）
                    DebugSwitches.multiCardDemo = true
                    val added = if (ui.selectedShapes.size < 2) {
                        ui.selectedShapes.add(GlassShape.CIRCLE)
                        "，已自动追加 shape2=${GlassShape.CIRCLE.name}"
                    } else ""
                    ui.applyCardCount(n)
                    "setUi OK: cards=${ui.cardCount}（twoCardDemo=true$added；multiCardDemo=" +
                        "${DebugSwitches.multiCardDemo}；多卡关掉时按 2 块渲染 ⇒ 回退 = setUi cards 2 或 " +
                        "setSwitches multiCardDemo 0）"
                }
            }
            "selectedCard" -> {
                val i = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue <0..3>")
                if (i !in 0..3) return "setUi FAILED: selectedCard ivalue=$i 越界（0..3）"
                ui.selectCard(i)
                "setUi OK: selectedCard=%d（%s；当前渲染 %d 块；面板「形状与布局」的尺寸/形状作用于它）".format(
                    ui.selectedCardIndex, ui.cardDisplayName(ui.selectedCardIndex), ui.visibleCardCount()
                )
            }
            "shape3" -> {
                val shape = shapeArg(iv, fv)
                    ?: return needValue("需要 --ei ivalue <0..${GlassShape.entries.size - 1}>（GlassShape 序数）")
                while (ui.extraShapes.size < 1) ui.extraShapes.add(GlassShape.CIRCLE)
                ui.extraShapes[0] = shape
                "setUi OK: shape3=${shape.name}（第 3 块卡形状；uiState.shapeOfCard(2)=${ui.shapeOfCard(2).name}；" +
                    "需要 cards>=3 才可见）"
            }
            "shape4" -> {
                val shape = shapeArg(iv, fv)
                    ?: return needValue("需要 --ei ivalue <0..${GlassShape.entries.size - 1}>（GlassShape 序数）")
                while (ui.extraShapes.size < 2) ui.extraShapes.add(GlassShape.TRIANGLE)
                ui.extraShapes[1] = shape
                "setUi OK: shape4=${shape.name}（第 4 块卡形状；uiState.shapeOfCard(3)=${ui.shapeOfCard(3).name}；" +
                    "需要 cards>=4 才可见）"
            }
            "twoCardDemo" -> {
                val v = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue 0/1")
                ui.twoCardDemo = v != 0
                val note = if (ui.twoCardDemo && ui.secondShape == null)
                    "；secondShape=null → 第二块卡仍不显示（setUi shape2 <0..${GlassShape.entries.size - 1}> 或 cards 2）" else ""
                "setUi OK: twoCardDemo=${ui.twoCardDemo}$note"
            }
            "advanced" -> {
                val v = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue 0/1")
                val hook = advanced ?: return "setUi FAILED: key=advanced 键桥未注册（应用 UI 未运行？）"
                hook.set(v != 0)
                "setUi OK: advanced=${hook.get()}（面板二级页『更多设置』）"
            }
            "quality" -> {
                val q = when {
                    sv != null -> GlassQuality.entries.firstOrNull {
                        it.name.equals(sv.trim(), ignoreCase = true) || it.displayName == sv.trim()
                    }
                    iv != null -> GlassQuality.entries.getOrNull(iv)
                    fv != null -> GlassQuality.entries.getOrNull(fv.toInt())
                    else -> null
                } ?: return needValue("需要 --ei ivalue <0..${GlassQuality.entries.size - 1}> 或 --es svalue <PERFORMANCE|BALANCED|QUALITY>")
                ui.quality = q
                "setUi OK: quality=${q.name}（displayName='${q.displayName}'，taps=${q.tapCount}）"
            }
            "bgBuiltinIndex" -> {
                val n = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue <0..5>（内置壁纸序号）")
                if (n !in 0..5) return "setUi FAILED: key=bgBuiltinIndex ivalue=$n 越界（内置壁纸共 6 张：0..5，见 GlassControlsPanel.BuiltinWallpapers）"
                ui.selectBuiltinBackground(context, n)
                // 画面上的壁纸位图与面板 chip 同路径刷新（复用 restoreBackgroundSelection：按持久化的
                // index 解码 drawable → bgImage）；解码在 IO 线程，完成后回主线程写入
                CoroutineScope(Dispatchers.Main).launch {
                    runCatching { restoreBackgroundSelection(ui, context) }
                        .onFailure { t -> Log.w(TAG, "[setUi] 壁纸解码失败：${t.javaClass.simpleName}: ${t.message}") }
                    Log.i(TAG, "[setUi] setUi OK: bgBuiltinIndex=${ui.bgBuiltinIndex}（壁纸位图已按新 index 刷新）")
                    AppDebugLog.log("AIDBG", "[setUi] setUi OK: bgBuiltinIndex=${ui.bgBuiltinIndex}（位图已刷新）")
                }
                "setUi OK: bgBuiltinIndex=${ui.bgBuiltinIndex}（已持久化；位图解码完成后另打一行 OK）"
            }
            "bgText" -> {
                if (sv == null) return needValue("需要 --es svalue <背景文字>")
                ui.bgText = sv
                "setUi OK: bgText='${ui.bgText}'（注：中文经 am broadcast --es 传输可能需转义）"
            }
            "iosLensProfile" -> {
                // 【固定开】面板入口已按用户要求删除 ⇒ 渲染消费点已写死常量
                // （LiquidGlassScreen：`lensProfile = { true }`）⇒ 本字段【不再是消费源】。
                // 这里仍照旧写入（保留调试桥“写什么报什么”的语义 + dumpState 可读性），
                // 但写入【不改变观感】—— 回显里明确标注，避免后来者误判为“开关还能关”。
                val v = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue 0/1")
                ui.iosLensProfile = v != 0
                "setUi OK: iosLensProfile=${ui.iosLensProfile}（注意：iOS 透镜剖面已固定【开】、面板入口已删 ⇒ 本字段不再是消费源，本写入不影响观感）"
            }
            "reduceMotion" -> {
                val v = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue 0/1")
                ui.reduceMotion = v != 0
                "setUi OK: reduceMotion=${ui.reduceMotion}"
            }
            "stretchOnDrag" -> {
                val v = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue 0/1")
                ui.stretchOnDrag = v != 0
                "setUi OK: stretchOnDrag=${ui.stretchOnDrag}"
            }
            "adaptiveLegibility" -> {
                val v = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue 0/1")
                ui.adaptiveLegibility = v != 0
                "setUi OK: adaptiveLegibility=${ui.adaptiveLegibility}"
            }
            "debugLogEnabled" -> {
                val v = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue 0/1")
                ui.debugLogEnabled = v != 0
                AppDebugLog.enabled = ui.debugLogEnabled   // 面板外没有 LaunchedEffect 同步 → 这里补一次
                "setUi OK: debugLogEnabled=${ui.debugLogEnabled}"
            }
            // ==================== 【玻璃风格拉杆】====================
            "stylePos" -> {
                val v = fv ?: iv?.toFloat() ?: return needValue("需要 --ef fvalue <0..1>")
                // 防 NaN：coerceIn 对 NaN 放行（历史事故）⇒ 先显式判有限，非有限一律拒绝、不写入
                if (!v.isFinite()) return "setUi FAILED: stylePos 非有限值"
                ui.setGlassStyle(v.coerceIn(0f, 1f))
                val s = GlassParameters.stylePositionOf(ui.parameters.glassTransparency)
                val customNote = if (ui.preset == GlassPreset.CUSTOM)
                    "（preset=CUSTOM：与『玻璃透明度』同语义，写入不生效）" else ""
                "setUi OK: stylePos=%.3f tier=%s glassTransparency=%.3f blur=%.2fdp%s".format(
                    Locale.US, s, GlassParameters.stylePresetOf(s).displayName,
                    ui.parameters.glassTransparency, ui.parameters.blurRadiusDp, customNote
                )
            }
            "preset" -> {
                val idx = iv ?: fv?.toInt() ?: return needValue("需要 --ei ivalue <0..2>")
                if (idx !in 0..2) return "setUi FAILED: preset ivalue=$idx 越界（0=通透 1=均衡 2=磨砂）"
                val preset = GlassPreset.entries[idx]
                ui.selectPreset(preset)
                val s = GlassParameters.stylePositionOf(ui.parameters.glassTransparency)
                "setUi OK: preset=%s（%s）glassTransparency=%.3f blur=%.2fdp stylePos=%.3f".format(
                    Locale.US, preset.name, preset.displayName,
                    ui.parameters.glassTransparency, ui.parameters.blurRadiusDp, s
                )
            }
            else -> "setUi UNKNOWN key='$key'"   // 不可达（进入前已按白名单校验）
        }
    }

    /** shape 参数解析：ivalue/fvalue 均按 GlassShape 序数；越界返回 null。 */
    private fun shapeArg(iv: Int?, fv: Float?): GlassShape? =
        (iv ?: fv?.toInt())?.let { GlassShape.entries.getOrNull(it) }

    // ---------------- 5) setPos（指定卡片 → 指定 offset） ----------------

    private fun setPos(intent: Intent): List<String> {
        val card = intArg(intent, "card")
            ?: return listOf("setPos MISSING --ei card <0..3>（0=主卡，1=第二块卡，2/3=多卡追加块）")
        val x = floatArg(intent, "x") ?: return listOf("setPos MISSING --ef x <px>")
        val y = floatArg(intent, "y") ?: return listOf("setPos MISSING --ef y <px>")
        if (card !in 0..3) return listOf("setPos FAILED：card=$card 越界（支持 0=主卡 / 1=第二块 / 2、3=多卡追加块）")
        // 【主线程写入】与 setUi 同规则：offsetX/offsetY 是 Compose 快照状态，只能主线程写
        Handler(Looper.getMainLooper()).post {
            val line = runCatching {
                val bridge = cards
                    ?: return@runCatching "setPos FAILED：卡片桥未注册（应用 UI 未运行）→ 先 am start 启动应用再发命令"
                if (card >= bridge.count()) {
                    "setPos FAILED：card=$card 不存在（当前卡片数=${bridge.count()}；card=1 需要 twoCardDemo=true " +
                        "且已选第二块形状 → 先 setUi cards ivalue 2 或 setUi shape2 <0..6>）"
                } else {
                    val before = bridge.currentOffset(card)
                    val after = bridge.setOffset(card, x, y)
                    "setPos OK: card[$card] offset ${fmtPair(before)} → ${fmtPair(after)}" +
                        "（直接写 offsetX/offsetY，绕过手势钳制；graphicsLayer 下一帧生效）"
                }
            }.getOrElse { t -> "setPos ERROR ${t.javaClass.simpleName}: ${t.message}" }
            Log.i(TAG, "[setPos] $line")
            AppDebugLog.log("AIDBG", "[setPos] $line")
        }
        return listOf("setPos 已提交主线程（card=$card x=$x y=$y）；写入结果见下一条 [setPos OK:…]")
    }

    private fun fmtPair(v: Pair<Float, Float>?): String =
        if (v == null) "<null>" else "(%.1f, %.1f)".format(v.first, v.second)

    // ---------------- 5b) cardTop（【P06】把第 n 块卡置顶 = z 序末尾） ----------------

    /**
     * 【P06 多卡·点击置顶】的程序化通道（与手指点击走同一个实现：ui/LiquidGlassScreen.kt 的
     * raiseCardToFront）。用途：复现走查与取证——手指点击证明"点了会置顶"，本命令证明
     * "z 序确实按卡片索引变化"（dumpState 的 multicard 行会回读 z=…）。
     * 用法：`am broadcast -a com.liqglass.DEBUG --es cmd cardTop --ei card 0 -p com.liqglass.ultraclear`
     */
    private fun cardTop(intent: Intent): List<String> {
        val card = intArg(intent, "card")
            ?: return listOf("cardTop MISSING --ei card <0..3>（0=主卡，1=第二块卡，2/3=多卡追加块）")
        if (card !in 0..3) return listOf("cardTop FAILED：card=$card 越界（支持 0..3）")
        Handler(Looper.getMainLooper()).post {
            val line = runCatching {
                val bridge = cards
                    ?: return@runCatching "cardTop FAILED：卡片桥未注册（应用 UI 未运行）→ 先 am start 启动应用"
                val before = bridge.zOrder()
                val after = bridge.raiseToFront(card)
                    ?: return@runCatching "cardTop FAILED：card=$card 不存在（当前卡片数=${bridge.count()}）"
                "cardTop OK: card[$card] 置顶 z: ${before.joinToString(",")} → $after" +
                    "（列表尾 = 最上层；绘制顺序下一帧生效）"
            }.getOrElse { t -> "cardTop ERROR ${t.javaClass.simpleName}: ${t.message}" }
            Log.i(TAG, "[cardTop] $line")
            AppDebugLog.log("AIDBG", "[cardTop] $line")
        }
        return listOf("cardTop 已提交主线程（card=$card）；写入结果见下一条 [cardTop OK:…]")
    }

    // ---------------- 6) dumpState ----------------

    /** 【深色/亮色模式】系统当前是否深色（= Compose isSystemInDarkTheme 的同一判据）。 */
    private fun systemInDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dumpState(context: Context): List<String> {
        val out = ArrayList<String>(24)
        out += "===== AI 调试接口 · dumpState ====="
        // 【深色/亮色模式·2026-09-24】主题档位 + 解析结果（跟随系统时会显示系统当前是否深色）
        out += "theme      = mode=${com.example.liquidglass.ui.GlassTheme.mode.label}" +
            " (id=${com.example.liquidglass.ui.GlassTheme.mode.id})；themeAutoSwitch=" +
            "${DebugSwitches.themeAutoSwitch}；系统深色=${systemInDark(context)}"
        val ui = uiState
        out += if (ui == null) "debugMode  = <未注册：应用 UI 未运行>"
        else "debugMode  = ${ui.debugMode.name}('${ui.debugMode.displayName}') shaderCode=${ui.debugMode.shaderCode}"
        out += if (ui == null) "uiState    = <未注册：应用 UI 未运行>"
        else "uiState    = glassSize=%.2f shapes=%s twoCardDemo=%b quality=%s bgBuiltin=%d lensProfile=%b advanced=%s".format(
            Locale.US, ui.glassSize, ui.selectedShapes.joinToString(",") { it.name }, ui.twoCardDemo,
            ui.quality.name, ui.bgBuiltinIndex, ui.iosLensProfile,
            advanced?.get()?.toString() ?: "<未注册>"
        )
        // 【P06 多卡】档位 / 追加块形状 / z 序（点击置顶的唯一真值）——全部现读现算（不捕获组合期快照）
        out += if (ui == null) "multicard  = <未注册：应用 UI 未运行>"
        else "multicard  = cardCount=%d multiCardDemo=%b extraShapes=%s".format(
            Locale.US, ui.cardCount, DebugSwitches.multiCardDemo,
            ui.extraShapes.joinToString(",") { it.name }
        )
        // 【玻璃风格拉杆】style 锚点自检：现场计算 withStylePosition(锚点) == preset(对应档)（data class 逐字段相等）
        out += if (ui == null) "style      = <未注册：应用 UI 未运行>"
        else {
            val pClear = GlassParameters.preset(GlassPreset.IOS26_BETA1_ULTRA_CLEAR)
            val pBalanced = GlassParameters.preset(GlassPreset.BALANCED)
            val pFrosted = GlassParameters.preset(GlassPreset.FROSTED_ACCESSIBLE)
            val pos = GlassParameters.stylePositionOf(ui.parameters.glassTransparency)
            "style      = 锚点自检 s=0:%s s=0.5:%s s=1:%s | stylePos=%.3f tier=%s glassTransparency=%.3f preset=%s".format(
                Locale.US,
                if (pClear.withStylePosition(0f) == pClear) "✓" else "✗",
                if (pBalanced.withStylePosition(0.5f) == pBalanced) "✓" else "✗",
                if (pFrosted.withStylePosition(1f) == pFrosted) "✓" else "✗",
                pos, GlassParameters.stylePresetOf(pos).displayName,
                ui.parameters.glassTransparency, ui.parameters.preset.displayName
            )
        }
        out += "switches   = " + switchNames().joinToString(", ") { "$it=${getSwitch(it)}" }
        val dm = context.resources.displayMetrics
        out += "screen     = %dx%d density=%.2f dpi=%d model=%s android=%s (sdk %d)".format(
            Locale.US, dm.widthPixels, dm.heightPixels, dm.density, dm.densityDpi,
            Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT
        )
        val bridge = panel
        if (bridge == null) out += "panel      = <未注册：应用 UI 未运行>"
        else bridge.dumpState().trimEnd().split("\n").forEach { out += it }
        val c = cards
        if (c == null) out += "cards      = <未注册：应用 UI 未运行>"
        else {
            out += "cards      = n=${c.count()}（card 0=主卡 1=第二块卡；rect=窗口坐标 [left,top wxh] px）"
            // 【P06 点击置顶】z 序现读（列表尾 = 最上层）——人工点击 / `cardTop` 命令后本行即刻变化 ✓
            out += "cardZ      = ${c.zOrder().joinToString(",")}（绘制顺序，最后一个 = 最上层）"
            for (i in 0 until c.count()) {
                val r = c.rect(i)
                val o = c.currentOffset(i)
                out += if (r == null) "card[$i]    = <null>"
                else "card[$i]    = rect=[%.0f,%.0f %.0fx%.0f] offset=(%.1f,%.1f)".format(
                    Locale.US, r[0], r[1], r[2], r[3], o?.first ?: Float.NaN, o?.second ?: Float.NaN
                )
            }
        }
        frameStatsSummary().let { out += it }
        out += "session    = ${AppDebugLog.session}"
        return out
    }

    // ---------------- 7) dumpFrameStats ----------------

    private fun dumpFrameStats(): List<String> = listOf(
        "===== AI 调试接口 · dumpFrameStats =====",
        frameStatsSummary()
    )

    // ---------------- 8) frameTimeline（逐帧时间戳日志 = 帧率分析仪） ----------------

    /**
     * 逐帧时间戳日志（performance/FrameTimelineLogger）的 adb 接线。
     *
     * 用法：
     * ```
     * # ① 打开总开关（默认关；反射写 DebugSwitches.frameTimeline）
     * adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name frameTimeline --ei value 1 -p com.liqglass.ultraclear
     * # ② 开始采集 → 做被测操作 → 停止 → 导出
     * adb shell am broadcast -a com.liqglass.DEBUG --es cmd frameTimeline --es action start  -p com.liqglass.ultraclear
     * adb shell am broadcast -a com.liqglass.DEBUG --es cmd frameTimeline --es action stop   -p com.liqglass.ultraclear
     * adb shell am broadcast -a com.liqglass.DEBUG --es cmd frameTimeline --es action export -p com.liqglass.ultraclear
     * adb pull /sdcard/Android/data/com.liqglass.ultraclear/files/frametimeline_<session>.csv
     * ```
     * 语义要点（与 FrameTimelineLogger 注释同源）：
     *  · 全程在主线程执行（广播 onReceive 就在主线程；Choreographer 回调绑定主线程）；
     *  · start 只在总开关 frameTimeline=true 时被接受（否则直接拒绝并给出下一步命令）——
     *    避免"以为在采、其实没采"；stop/export/status 任何时候都可查；
     *  · export 采集中也允许（导当前缓冲快照），文件写完才返回 ⇒ 广播返回后 adb pull 立刻可读。
     */
    private fun frameTimeline(context: Context, intent: Intent): List<String> {
        val action = (intent.getStringExtra("action") ?: "status").trim().lowercase(Locale.US)
        return when (action) {
            "start" -> {
                if (!DebugSwitches.frameTimeline) {
                    listOf(
                        "frameTimeline start REFUSED：总开关 frameTimeline=false（默认关）。先执行 " +
                            "setSwitches --es name frameTimeline --ei value 1 -p com.liqglass.ultraclear" +
                            "（全部开关：${switchNames()}）"
                    )
                } else {
                    listOf("frameTimeline " + FrameTimelineLogger.start(context))
                }
            }
            "stop" -> listOf("frameTimeline " + FrameTimelineLogger.stop())
            "export" -> {
                val line = "frameTimeline " + FrameTimelineLogger.exportCsv(context)
                val path = FrameTimelineLogger.lastCsvPath()
                if (path.isNotEmpty()) {
                    listOf(line, "frameTimeline csv=$path（adb pull \"$path\" 即可取回）")
                } else {
                    listOf(line)
                }
            }
            "status" -> listOf("frameTimeline status " + FrameTimelineLogger.status())
            else -> listOf(
                "frameTimeline UNKNOWN action='$action'；可用：start / stop / export / status" +
                    "（用法：--es cmd frameTimeline --es action <start|stop|export|status>）"
            )
        }
    }

    // ---------------- 8b) jankStats（P18·官方 JankStats 监视器读数） ----------------

    /**
     * `jankStats --es action <dump|reset|status>`（缺省 dump）——读官方 androidx.metrics JankStats 的聚合计数。
     *
     * 与自写 frameTimeline 并存：本命令只读数、不启停采集；开/关监视器走 setSwitches：
     *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name jankStatsMonitor --ei value 1 -p com.liqglass.ultraclear
     * dump（一行）：frames / jank(官方判据 uiDuration>expected×2.0) / maxUiMs（最长帧）/ meanUiMs / spanFps（=(frames-1)/跨度）。
     * reset：计数清零（用于把"面板开合×5"的测量窗口与 frameTimeline 会话对齐）。
     */
    private fun jankStats(intent: Intent): List<String> {
        return when (val action = intent.getStringExtra("action") ?: "dump") {
            "dump", "status" -> listOf(JankStatsMonitor.dumpLine())
            "reset" -> listOf(JankStatsMonitor.reset())
            else -> listOf(
                "jankStats UNKNOWN action='$action'；可用：dump / reset / status" +
                    "（开/关监视器：setSwitches --es name jankStatsMonitor --ei value 0|1；start 由开关驱动）"
            )
        }
    }

    /** 帧统计摘要（PerformanceMonitor 快照；字段口径见 PerformanceSnapshot 注释，N/A 即不可用）。 */
    fun frameStatsSummary(): String {
        val provider = frameStats ?: return "frames     = <未注册：MainActivity 不在运行>"
        val s = provider() ?: return "frames     = <无快照>"
        fun num(v: Float?): String = v?.let { "%.1f".format(Locale.US, it) } ?: "N/A"
        val ageMs = android.os.SystemClock.elapsedRealtime() - s.timestampMs
        // ⚠️ Kotlin 里 `"a" + "b".format(...)` 的 .format 只作用于第二个字面量 ⇒ 先拼好整条格式串再 format。
        val base = ("frames     = fps=%s avg=%s p95=%s jank=%d gpuMed=%s gpuP95=%s totalMed=%s issueMed=%s " +
            "appCpu=%s battery=%s charging=%s snapAge=%dms").format(
            Locale.US,
            s.fps?.let { "%.1f".format(Locale.US, it) } ?: "IDLE",
            num(s.avgFrameMs), num(s.p95FrameMs), s.jankCount,
            num(s.gpuMedianMs), num(s.gpuP95Ms), num(s.totalMedianMs), num(s.issueMedianMs),
            num(s.appCpuPercent), num(s.batteryCurrentMa), s.charging?.toString(), ageMs
        )
        return base + jankThrInfo(s)
    }

    /**
     * 【P65 修复①回读】本次判定采用的 jank 阈值与刷新率（dumpState / dumpFrameStats 可见）。
     * 采用值 ≠ Display 实测值时标注【模拟值】（模拟器 60Hz 单模式下 120 档常见；真机待平板回线）。
     */
    private fun jankThrInfo(s: PerformanceSnapshot): String {
        val thr = s.jankThresholdMs ?: return " jankThr=N/A（旧快照无阈值字段）"
        val hz = s.refreshHz ?: return " jankThr=%.2fms（刷新率 N/A）".format(Locale.US, thr)
        val disp = s.displayHz
        val sim = if (disp != null && kotlin.math.abs(hz - disp) > 1f)
            "（模拟值：物理显示 %.1fHz）".format(Locale.US, disp) else ""
        return " jankThr=%.2fms@%.0fHz%s".format(Locale.US, thr, hz, sim)
    }

    // ---------------- 12) perfMonitor（P65 修复③·性能监控 start/stop 取证通道） ----------------

    /**
     * `perfMonitor --es action <status|stop|start|restart>`（缺省 status）——驱动 PerformanceMonitor
     * 的 start()/stop()（与 LiquidGlassScreen 的 DisposableEffect 同一对入口），用于机上验证
     * 【stop→start 可重复使用】：restart 后连续 dumpState 的 frames 行继续更新（fps/snapAge 前进）✓。
     * 回执同时带 frames 行 = 现场读数。改动前对照：perfRestartable=0 时 restart 后 frames 行冻结。
     */
    private fun perfMonitor(intent: Intent): List<String> {
        val action = (intent.getStringExtra("action") ?: "status").trim().lowercase(Locale.US)
        val ctl = perfControl ?: return listOf("perfMonitor <未注册：MainActivity 不在运行>")
        val known = setOf("status", "stop", "start", "restart")
        if (action !in known) {
            return listOf("perfMonitor UNKNOWN action='$action'；可用：status / stop / start / restart")
        }
        return listOf("perfMonitor $action → " + ctl(action), frameStatsSummary())
    }

    // ---------------- DebugSwitches 反射访问 ----------------

    /**
     * DebugSwitches 的全部布尔开关字段（反射枚举 → 新增开关无需改这里，符合"列出全部开关名"的要求）。
     * 契约：开关 = object 的【Boolean 字段】（Kotlin 顶层 var，带 @Volatile），字段名即命令里的名字。
     */
    private val switchFields: List<java.lang.reflect.Field> by lazy {
        DebugSwitches::class.java.declaredFields
            .filter { it.type == java.lang.Boolean.TYPE }
            .onEach { runCatching { it.isAccessible = true } }
    }

    /** 全部开关名（供日志/补全）。 */
    fun switchNames(): List<String> = switchFields.map { it.name }

    /** 读取开关当前值（名字不存在返回 null）。 */
    fun getSwitch(name: String): Boolean? =
        switchFields.firstOrNull { it.name == name }
            ?.let { runCatching { it.getBoolean(DebugSwitches) }.getOrNull() }

    /** 写入开关（名字不存在返回 false）。 */
    fun setSwitch(name: String, value: Boolean): Boolean {
        val f = switchFields.firstOrNull { it.name == name } ?: return false
        return runCatching { f.setBoolean(DebugSwitches, value); true }.getOrDefault(false)
    }

    // ---------------- 参数读取（容忍 --ei/--ef/--es/--el 各种传法） ----------------

    private fun intArg(intent: Intent, key: String): Int? {
        if (!intent.hasExtra(key)) return null
        return when (val v = intent.extras?.get(key)) {
            is Int -> v
            is Long -> v.toInt()
            is Float -> v.toInt()
            is Double -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    private fun floatArg(intent: Intent, key: String): Float? {
        if (!intent.hasExtra(key)) return null
        return when (val v = intent.extras?.get(key)) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            is Long -> v.toFloat()
            is String -> v.trim().toFloatOrNull()
            else -> null
        }
    }

    // ---------------- 9) setFusion（P12·邻近流体融合三参数） ----------------

    /**
     * `setFusion --ef threshold <8..180> --ef tension <0.1..3> --ef filament <80..4000> [--ei persist 0|1]`
     *
     * 三参数是 float ⇒ 不能走 setSwitches（只支持 --ei 整数），单独开一条命令；
     * 运行中即时生效（ProximityFusion 的 @Volatile 字段）；persist=1 时同时落盘
     * （files/lg_fusion_* ；重启后仍生效，读取先例照 DebugSwitches.readSwitchFile）。
     */
    private fun setFusion(intent: Intent): List<String> {
        val thr = floatArg(intent, "threshold")
        val ten = floatArg(intent, "tension")
        val fil = floatArg(intent, "filament")
        if (thr == null && ten == null && fil == null) {
            return listOf(
                "setFusion MISSING：至少给一个 --ef threshold <px> / --ef tension <0.1..3> / " +
                    "--ef filament <ms>；当前 " + ProximityFusion.describe()
            )
        }
        val before = ProximityFusion.describe()
        if (thr != null) ProximityFusion.setThreshold(thr)
        if (ten != null) ProximityFusion.setTension(ten)
        if (fil != null) ProximityFusion.setFilamentMs(fil)
        val persist = (intArg(intent, "persist") ?: 0) != 0
        val persisted = if (persist) ProximityFusion.persistOverrides(thr, ten, fil)
        else "（未落盘；重启回默认或上次落盘值）"
        return listOf(
            "setFusion OK：$before → ${ProximityFusion.describe()} $persisted" +
                "（主开关 = setSwitches proximityFusion 0|1；覆写文件 files/" + ProximityFusion.FILE_THRESHOLD +
                " | " + ProximityFusion.FILE_TENSION + " | " + ProximityFusion.FILE_FILAMENT + "）"
        )
    }

    // ---------------- 12) setEdgeHairSupport（P44·贴边发丝带支撑宽度标定） ----------------

    /**
     * `setEdgeHairSupport --ef value <0.5..8.0>`
     *
     * 【用途】玻璃边沿「糊」修复的候选档扫掠：支撑半宽倍率是 float ⇒ 不能走 setSwitches（只支持整数），
     * 且烙进 AGSL 源码时每个候选值都要重新构建/装机。改成运行时 uniform（GlassUniforms.EDGE_HAIR_SUPPORT）
     * 后，本命令可在**同一构建**上逐档切换并量化「过渡带宽度 × 硬台阶幅度」两个指标 ✓。
     *
     * 【语义】等于「如果 DebugSwitches.edgeHairBandSharp = true，发丝带支撑半宽取多少」；
     * 关掉 edgeHairBandSharp 时统一回退到 HAIR_BAND_SUPPORT_MUL_LEGACY（5.0，改动前的值）。
     * 【落点】DebugSwitches.edgeHairSupportMul（@Volatile）→ 卡片录制 lambda 每帧下发 → 立即重绘。
     * 【回读】返回行里同时打印「写入后的值 + 开关状态」，用作「命令真的落盘且生效」的证据。
     */
    private fun setEdgeHairSupport(intent: Intent): List<String> {
        val raw = floatArg(intent, "value")
            ?: return listOf(
                "setEdgeHairSupport MISSING：需要 --ef value <0.5..8.0>（当前 " +
                    "edgeHairSupportMul=${DebugSwitches.edgeHairSupportMul}, " +
                    "edgeHairBandSharp=${DebugSwitches.edgeHairBandSharp}）"
            )
        if (!raw.isFinite()) {
            return listOf("setEdgeHairSupport INVALID：只接受有限数 0.5..8.0（NaN/Inf 一律拒绝 ✗）")
        }
        val target = raw.coerceIn(0.5f, 8.0f)
        val before = DebugSwitches.edgeHairSupportMul
        DebugSwitches.edgeHairSupportMul = target
        revision.intValue = revision.intValue + 1   // 触发重绘（volatile 字段本身不产生订阅）
        val clamped = if (raw != target) "，已钳制到 0.5..8.0" else ""
        // ⚠️ Kotlin 里 `"a" + "b".format(...)` 的 `.format` 只作用于第二个字面量（本项目踩过两次：
        // IllegalFormatConversionException / 回读行整条错位）⇒ 必须先把整条格式串拼进一个 val 再 format。
        val okFmt = "setEdgeHairSupport OK：edgeHairSupportMul %.3f → %.3f（收到 value=%.3f%s）；" +
            "edgeHairBandSharp=%s ⇒ 实际下发=%s"
        val effective = if (DebugSwitches.edgeHairBandSharp)
            "%.3f（新档）".format(Locale.US, target) else "5.000（旧档·回退中）"
        return listOf(
            okFmt.format(
                Locale.US, before, DebugSwitches.edgeHairSupportMul, raw, clamped,
                DebugSwitches.edgeHairBandSharp.toString(), effective
            )
        )
    }
}

/**
 * 【AI 调试接口】adb 广播入口。
 *
 * 声明（AndroidManifest.xml）：action = com.liqglass.DEBUG，exported = true。
 * 用法：`adb shell am broadcast -a com.liqglass.DEBUG --es cmd dumpState -p com.liqglass.ultraclear`
 *
 * 【门控】仅 debug 构建生效：onReceive 第一条语句检查 FLAG_DEBUGGABLE（debug=true / release=false），
 * release 下直接 return —— 不解析参数、不反射、不写日志文件（见 DebugBridge.isDebugBuild 注释）。
 */
class DebugBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 【硬门控】release 构建完全无效（不读参数、不反射、不落盘）
        if (!DebugBridge.isDebugBuild(context)) return
        if (intent.action != DebugBridge.ACTION) return

        val cmd = intent.getStringExtra("cmd")
        if (cmd.isNullOrBlank()) {
            Log.i(DebugBridge.TAG, "[no-cmd] 需要 --es cmd；可用命令：${DebugBridge.commandList()}")
            return
        }

        // 【P65 修复②】改动前这里是无条件 `AppDebugLog.enabled = true`：任意调试广播都强开日志、
        // 且不回写 ui.debugLogEnabled ⇒ 面板开关显示"关"而实际全开、关着也每次切后台落盘 ✗。
        // 现在【不改变用户的日志开关状态】：回执恒进 logcat（Log.i 无门控 ⇒ 调试桥始终可用 ✓），
        // 应用内环形缓冲由 AppDebugLog.log 按用户开关自行门控；✗ 不偷偷打开日志 ✗。
        // 一行回退（恢复改动前的强开行为）：
        //   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches --es name debugBridgeForceLog --ei value 1 -p com.liqglass.ultraclear
        if (DebugSwitches.debugBridgeForceLog) {
            AppDebugLog.enabled = true   // 仅回退档生效（= 改前行为，同样不回写 ui.debugLogEnabled）
        }
        val lines = DebugBridge.execute(context, cmd, intent)
        for (line in lines) {
            Log.i(DebugBridge.TAG, "[$cmd] $line")
            AppDebugLog.log("AIDBG", "[$cmd] $line")
        }
        // 回执尾如实报告当前日志开关值（与设置面板同一真值：ui.debugLogEnabled ↔ AppDebugLog.enabled）
        val swLine = "logSwitch  = AppDebugLog.enabled=${AppDebugLog.enabled} ui.debugLogEnabled=${DebugBridge.uiState?.debugLogEnabled}" +
            if (DebugSwitches.debugBridgeForceLog) "（回退档 debugBridgeForceLog=1：本次广播强开了日志）"
            else "（本次广播未改变日志开关）"
        Log.i(DebugBridge.TAG, "[$cmd] $swLine")
        AppDebugLog.log("AIDBG", "[$cmd] $swLine")

        // dump* ：同步导出一次日志到 files/，保证 am broadcast 返回时文件已写好（adb pull 立即可读）
        if (DebugBridge.shouldExport(cmd)) {
            val file = AppDebugLog.export(context)
            Log.i(
                DebugBridge.TAG,
                "[$cmd] " + (if (file != null) "log exported → ${file.absolutePath}" else "log export FAILED")
            )
        }
    }
}
