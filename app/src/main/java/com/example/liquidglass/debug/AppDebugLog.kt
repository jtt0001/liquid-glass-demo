package com.example.liquidglass.debug

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 应用内调试日志（用户提议）：把"动画轨迹 + 手势 + 状态变化 + 渲染决策"记进内存环形缓冲，
 * 用户遇到问题时一键导出到 adb 可读目录，开发者按时间线排查。
 *
 * 设计要点：
 * - 关闭时零开销（每个入口先判 enabled）
 * - 环形缓冲上限 4000 条，避免长跑占内存
 * - 节流写入由调用方控制（动画轨迹只在 p 变化 >0.02 或间隔 >60ms 时记）
 */
object AppDebugLog {

    @Volatile var enabled: Boolean = false

    private const val CAP = 4000

    /** 导出文件上限：目录里只保留最近这么多个 debug_*.txt（防每次切后台都堆一个文件）。 */
    private const val MAX_EXPORT_FILES = 5
    private val buf = ArrayDeque<String>(CAP)
    private val t0 = SystemClock.elapsedRealtime()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 会话号：每次进程启动递增一个，便于区分"这次运行"。 */
    val session: String = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())

    fun log(tag: String, msg: String) {
        if (!enabled) return
        val t = SystemClock.elapsedRealtime() - t0
        val line = "%7.1fms  %-10s %s".format(t.toFloat(), tag, msg)   // t 是 Long → %f 会抛 IllegalFormatConversionException ✗
        synchronized(buf) {
            if (buf.size >= CAP) buf.removeFirst()
            buf.addLast(line)
        }
    }

    /** 供 UI 显示条目数（不返回内容，避免大字符串进组合）。 */
    fun size(): Int = synchronized(buf) { buf.size }

    fun clear() = synchronized(buf) { buf.clear() }

    /**
     * 导出到 app 私有外部目录（/sdcard/Android/data/<pkg>/files/），adb pull 无需权限。
     *
     * - 写盘在 [Dispatchers.IO] 上执行：调用方（生命周期回调 / 组合）不再被磁盘 IO 阻塞；
     * - 整个过程由 [runCatching] 兜住：目录不可用、写盘异常都只返回 null，不向上抛
     *  （调用点已有"导出失败"分支）；
     * - 导出成功后清理旧文件，目录里最多保留 [MAX_EXPORT_FILES] 个 debug_*.txt。
     */
    suspend fun exportAsync(context: Context): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching null
            val f = File(dir, "debug_${session}.txt")
            val header = buildString {
                appendLine("=== LiquidGlass 调试日志 ===")
                appendLine("session   : $session")
                appendLine("exported  : ${fmt.format(Date())}")
                appendLine("entries   : ${size()}")
                appendLine("设备      : ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                appendLine("========================================")
            }
            f.writeText(header + synchronized(buf) { buf.joinToString("\n") })
            // 写盘成功后再清理旧文件：keep 传本次文件，任何情况下都不会被删掉；
            // 写盘失败时也不会白删历史日志。清理失败只记日志，不影响导出结果。
            pruneOldExports(dir, keep = f)
            f
        }.onFailure { t ->
            android.util.Log.e("LiquidGlass", "export failed", t)
        }.getOrNull()
    }

    /**
     * 只保留最近 [MAX_EXPORT_FILES] 个 debug_*.txt（[keep] = 本次刚写好的文件，永不删）。
     * 尽力而为：失败只记一条日志，不影响导出结果。
     */
    private fun pruneOldExports(dir: File, keep: File) {
        runCatching {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("debug_") && f.name.endsWith(".txt")
            } ?: return@runCatching
            files.asSequence()
                // 本次文件不参与排序，避免 mtime 相同（同一秒内）时被误删
                .filter { it.absolutePath != keep.absolutePath }
                .sortedByDescending { it.lastModified() }
                .drop(MAX_EXPORT_FILES - 1)   // keep + (MAX-1) 个历史文件 = 硬上限 MAX
                .forEach { it.delete() }
        }.onFailure { t ->
            android.util.Log.w("LiquidGlass", "pruneOldExports failed", t)
        }
    }

    /**
     * 兼容旧调用点（控制面板"导出调试日志到文件"按钮：非 suspend 的 clickable 回调）
     * —— 复用同一个实现，实际写盘仍在 IO 线程，但调用线程会阻塞等待结果。
     * 新代码请用 [exportAsync]（例如 MainActivity.onStop 的自动导出）。
     */
    fun export(context: Context): File? = runBlocking(Dispatchers.IO) { exportAsync(context) }

    // ==================== 【P19 · App 内分享调试日志（系统分享面板）】 ====================

    /** 分享链路的 logcat tag（与应用内日志同批行）：`adb logcat -s LGShare`。 */
    const val SHARE_TAG = "LGShare"

    /** [shareAsync] 的【模拟降级】参数（调试桥/取证用）：只在决策点替换输入，判定逻辑本身是真实的那套。 */
    const val SIM_NO_TARGETS = "none"        // 假装枚举到 0 个可接收 App ⇒ no_targets 降级
    const val SIM_URI_FAIL = "uri_fail"      // 假装 FileProvider 取 URI 失败 ⇒ uri_fail 降级
    const val SIM_LAUNCH_FAIL = "launch_fail" // 假装 startActivity 抛异常 ⇒ launch_fail 降级

    /**
     * 分享结果：UI 据此决定提示文案（降级时显示实际路径）；调试桥/报告据此出证据。
     *
     * @param launched 是否真的拉起了系统分享面板（false = 走了降级路径）
     * @param uri      本次分享的 content:// URI（成功拿到时非空）
     * @param path     导出文件的绝对路径（降级提示"实际路径"用）
     * @param targets  枚举到的可接收 App 数（0 或负 = 无接收方）
     * @param reason   降级原因：export_fail / uri_fail / no_targets / launch_fail；成功为 null
     * @param title    分享面板标题（带 App 名）
     */
    data class ShareResult(
        val launched: Boolean,
        val uri: String?,
        val path: String?,
        val targets: Int,
        val reason: String?,
        val title: String
    )

    /**
     * 【P19】分享调试日志 = 「先跑一次现有导出」→ FileProvider 的 content:// URI →
     * 系统分享面板（ACTION_SEND + type=text/plain + EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION +
     * Intent.createChooser，标题带 App 名）⇒ 微信 / 文件传输助手 / 邮件 / 短信… 直接可收，
     * 用户不必接 adb 就能把日志发出来。
     *
     * 【绝不崩】整条链路三层兜底，任何一层失败都回退到「导出到文件」的老行为并记日志（tag [SHARE_TAG]）：
     *   ① 导出失败（[exportAsync] 返回 null）      ⇒ reason=export_fail（此时也没有文件可分享）；
     *   ② FileProvider.getUriForFile 抛异常/拿不到 ⇒ reason=uri_fail；
     *   ③ 无任何 App 可接收（queryIntentActivities 数到 0）⇒ reason=no_targets（不拉起空面板）；
     *   ④ startActivity 抛异常（ActivityNotFound 等）⇒ reason=launch_fail。
     *   降级时返回非 null 的 [ShareResult.path] ⇒ 调用点（面板按钮）把【实际路径】显示给用户。
     *
     * @param simulate 见 [SIM_NO_TARGETS] / [SIM_URI_FAIL] / [SIM_LAUNCH_FAIL]（null = 真实流程）
     */
    suspend fun shareAsync(
        context: Context,
        simulate: String? = null,
        chooserTitle: String? = null
    ): ShareResult = withContext(Dispatchers.IO) {
        val title = chooserTitle ?: "分享调试日志 · ${appLabel(context)}"

        // ① 先跑一次【现有导出】（同一个函数逐字复用）—— 分享的永远是刚写好的 debug_<session>.txt
        val file = exportAsync(context)
            ?: return@withContext degrade(null, null, 0, "export_fail", title)
        val path = file.absolutePath
        android.util.Log.i(SHARE_TAG, "export ok path=$path bytes=${file.length()}")
        log("SHARE", "export ok path=$path bytes=${file.length()}")

        // ② FileProvider：私有文件 → content://（authority = 包名 + ".fileprovider"，与 Manifest 对齐）
        val uriStr: String? = if (simulate == SIM_URI_FAIL) {
            android.util.Log.w(SHARE_TAG, "simulate=uri_fail：跳过 FileProvider（取证档）")
            null
        } else try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (t: Throwable) {
            android.util.Log.e(SHARE_TAG, "FileProvider.getUriForFile FAILED file=$path", t)
            null
        }
        if (uriStr == null) return@withContext degrade(null, path, 0, "uri_fail", title)
        log("SHARE", "uri=$uriStr")

        // ③ 系统分享面板的标准 Intent（type=text/plain + content:// URI + 读授权）
        val uri = Uri.parse(uriStr)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Liquid Glass 调试日志：${file.name}（${size()} 条）")
            // clipData 是授权第二条通路（部分 App 只从 clipData 取流）；FLAG 与所选目录同时生效
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // ④ 可接收 App 枚举（Manifest 已声明 <queries> ⇒ API30+ 包可见性不会把它清空）
        val targets = if (simulate == SIM_NO_TARGETS) {
            android.util.Log.w(SHARE_TAG, "simulate=none：假装 0 个可接收 App（取证档）")
            0
        } else try {
            context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).size
        } catch (t: Throwable) {
            android.util.Log.e(SHARE_TAG, "queryIntentActivities FAILED", t)
            0
        }
        if (targets <= 0) return@withContext degrade(uriStr, path, targets, "no_targets", title)

        // ⑤ 拉起系统分享面板（标题带 App 名）；非 Activity 上下文补 NEW_TASK
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = if (simulate == SIM_LAUNCH_FAIL) {
            android.util.Log.w(SHARE_TAG, "simulate=launch_fail：不真拉起面板（取证档）")
            false
        } else try {
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            android.util.Log.e(SHARE_TAG, "startActivity(chooser) FAILED", t)
            false
        }
        if (!launched) return@withContext degrade(uriStr, path, targets, "launch_fail", title)

        val ok = "share ok title=\"$title\" uri=$uriStr targets=$targets"
        android.util.Log.i(SHARE_TAG, ok)
        log("SHARE", ok)
        ShareResult(true, uriStr, path, targets, null, title)
    }

    /** 兼容非 suspend 的 clickable 回调（与 [export] 同一套语义）。 */
    fun share(context: Context, simulate: String? = null, chooserTitle: String? = null): ShareResult =
        runBlocking(Dispatchers.IO) { shareAsync(context, simulate, chooserTitle) }

    /**
     * 降级：日志（logcat + 应用内环形缓冲）+ 返回带实际路径的结果 —— 调用点据此在面板上显示路径。
     * 绝不抛异常（本函数无任何可能失败的操作）。
     */
    private fun degrade(uri: String?, path: String?, targets: Int, reason: String, title: String): ShareResult {
        val msg = "degrade reason=$reason targets=$targets fallback=export path=${path ?: "-"} uri=${uri ?: "-"}"
        android.util.Log.w(SHARE_TAG, msg)
        log("SHARE", msg)
        return ShareResult(false, uri, path, targets, reason, title)
    }

    /** 分享面板标题用的 App 名（失败回落固定串，绝不抛）。 */
    private fun appLabel(context: Context): String = runCatching {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }.getOrDefault("Liquid Glass")
}
