package com.example.liquidglass.hdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Half
import android.util.Log
import com.example.liquidglass.debug.DebugSwitches
import java.io.File
import java.nio.ShortBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 【HDR 图片支持 · Ultra HDR JPEG（SDR 底图 + gain map）】—— 背景/壁纸管线的解码入口。
 *
 * 目的：让 app 内正确显示 Ultra HDR 图片（Android 14 / API 34 起的 `android.graphics.Gainmap`）。
 * 集成点：背景/壁纸管线 —— 内置实拍壁纸（`res/drawable-nodpi/bg_*.jpg`）与用户自选图片，
 * 解码后交给既有「HDR 窗口（`ActivityInfo.COLOR_MODE_HDR`，见 MainActivity）+ 自绘 AGSL 玻璃管线」。
 *
 * 【为什么是"手动套用"】我们的渲染是自绘 shader（AGSL/RuntimeShader 采样背景捕获层），
 * 官方在 `Gainmap` 类 javadoc 的《Applying a gainmap manually》一节专门给出 OpenGL ES / Vulkan
 * 等自绘场景的公式：
 * ```
 *   W = clamp((ln(H) - ln(minDisplayRatioForHdrTransition)) /
 *             (ln(displayRatioForFullHdr) - ln(minDisplayRatioForHdrTransition)), 0, 1)
 *   L = mix(ln(ratioMin), ln(ratioMax), pow(G, gamma))
 *   D = (B + epsilonSdr) * exp(L * W) - epsilonHdr
 * ```
 * （H = 屏幕当前 HDR/SDR 亮度比；B = 底图线性值；G = gain map 取值 0..1；D = 线性 HDR 输出。）
 * 该公式与本项目 `hdrImageSupport` 的实现逐字对应，见 [applyChannel] / [gainMapWeight]。
 * 官方另有一句（同一份 javadoc / Ultra HDR 规范）：硬件加速 Canvas 在 COLOR_MODE_HDR 的
 * Activity 里、且 HDR 余量足够时会【自动】套用 gain map —— 那是"直接把带 gainmap 的 Bitmap
 * 交给系统绘制"的路线；本项目自绘管线不依赖它，改为显式手动套用（两条路线互斥，见下方"防双套"）。
 *
 * 【降级策略（任何一条不满足都自动回 SDR，且逐像素与旧行为一致）】
 *   ① 开关关（[DebugSwitches.hdrImageWallpaper] = false）→ 旧解码路径；
 *   ② 系统 < API 34（无 Gainmap API，如 Android 13 及以下）→ 旧解码路径；
 *   ③ 图片没有 gain map（普通 JPEG/PNG/HEIC）→ 探测结果丢弃 → 旧解码路径；
 *   ④ 有 gain map 但屏幕无 HDR 能力（`Display.isHdr()==false`，如模拟器）→ 旧解码路径（只显示底图）；
 *   ⑤ 手动套用过程任何异常 / 超像素上限 → 回底图（绝不崩、绝不空白）。
 * 只有 ①~⑤ 全通过（有 gainmap + HDR 屏 + API≥34 + 开关开）才产出 HDR 位图。
 *
 * 【防双套】手动套用后返回的是**新建的** RGBA_F16 EXTENDED_SRGB 位图（不带 gainmap）；
 * 原底图（带 gainmap 的 Bitmap）被回收 → 系统"自动套用"路径不可能再叠加一次（两路线互斥）。
 *
 * 【HEIF/HEIC】（用户要求「以防万一」兜底）：
 *   · 底线：HEIC 永远能按 SDR 显示、绝不报错 —— 本类全部逻辑都在 try/catch 里，探测失败即落回
 *     旧路径（`BitmapFactory`，API 28+ 平台自带 HEIF 解码）；旧路径本身就是"能显示 HEIC"的那条。
 *   · 若平台解码能把 HEIC 里的 gain map 带出来（Android 15+ 起支持 ISO 21496-1）→ 走同一套
 *     手动套用逻辑（无需额外代码，`Bitmap.hasGainmap()` 判据同 JPEG）；
 *   · 带不出来 = 如实记录（见 NEXT.md）：官方只对 JPEG 承诺 Ultra HDR；备选是 libultrahdr v2.0。
 *
 * 【调试】两条运行时通路：
 *   · `adb ... setSwitches hdrImageWallpaper 0|1`（下一次壁纸解码生效）；
 *   · 私有目录标志文件（跨进程/重启仍有效）：`files/lg_hdr_image_off`、`files/lg_hdr_image_force`。
 *   全部决策都会打一行 `LGHdr` 日志（"HDR 通路生效 / SDR 降级 + 原因"），并记录在 [lastSummary]。
 */
object HdrImageSupport {

    const val TAG = "LGHdr"

    /** 调试：屏幕无 HDR 时 [DebugSwitches.hdrImageForceOn] 用的模拟 HDR/SDR 亮度比。 */
    private const val FORCE_DISPLAY_BOOST = 4f

    /** 手动套用的像素上限：F16 输出 = 8 字节/像素（600 万像素 ≈ 48MB，防 OOM）。 */
    private const val MAX_MANUAL_PIXELS = 6_000_000L

    /** 最近一次解码决策的一句话摘要（日志同款；供 dump/自检）。 */
    @Volatile
    var lastSummary: String = "未运行"
        private set

    @Volatile
    private var envLogged = false

    @Volatile
    private var selfTestRan = false

    // ==================== 环境与能力 ====================

    /** API 34（Upside Down Cake）= `android.graphics.Gainmap` 起始版本。 */
    fun gainmapApiAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** 当前显示面板是否具备 HDR 显示能力（真机平板实测 hdrTypes=[1,2,3,4] ✓；模拟器 false）。 */
    fun displayIsHdr(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        context.display?.isHdr ?: false
    }.getOrDefault(false)

    /**
     * 屏幕当前的 HDR/SDR 亮度比（公式里的 H）。SDR 屏/API<34/取值异常时返回 1.0
     * （= 公式权重 W 自动落到 0 = T 只显示底图，SDR 恒等，符合官方语义）。
     */
    fun displayBoost(context: Context): Float = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 1f
        val d = context.display ?: return 1f
        if (!d.isHdr) return 1f
        val r = if (d.isHdrSdrRatioAvailable) d.hdrSdrRatio else d.highestHdrSdrRatio
        if (r.isNaN() || r.isInfinite() || r < 1f || r > 1000f) 1f else r
    }.getOrDefault(1f)

    /** 双通道日志：logcat（tag LGHdr）+ 应用内调试日志（AppDebugLog，tag LGHDR）。
     *  为什么两条都写：本机是【多代理共用设备】，别的代理会 logcat -c 清缓冲 ✗ ——
     *  应用自己的日志文件（随 dumpState 导出为 debug_*.txt）才是存活到最后的证据（项目纪律）。 */
    private fun logLine(msg: String) {
        Log.i(TAG, msg)
        runCatching { com.example.liquidglass.debug.AppDebugLog.log("LGHDR", msg) }
    }

    private fun logWarn(msg: String) {
        Log.w(TAG, msg)
        runCatching { com.example.liquidglass.debug.AppDebugLog.log("LGHDR", "WARN $msg") }
    }

    /** 启动时打一次环境日志（能力自检：sdk / 屏幕 HDR / 当前开关档）。 */
    fun logEnvironmentOnce(context: Context) {
        if (envLogged) return
        envLogged = true
        runCatching {
            val caps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.hdrCapabilities
            } else null
            logLine(
                "env: sdk=${Build.VERSION.SDK_INT} gainmapApi=${gainmapApiAvailable()} " +
                    "display.isHdr=${displayIsHdr(context)} hdrTypes=[${caps?.supportedHdrTypes?.joinToString() ?: ""}] " +
                    "maxLum=${caps?.desiredMaxLuminance ?: 0f} displayBoost=${"%.3f".format(displayBoost(context))} " +
                    "switch(hdrImageWallpaper)=${DebugSwitches.hdrImageWallpaper} forceOn=${DebugSwitches.hdrImageForceOn}"
            )
        }
    }

    // ==================== 解码入口（背景/壁纸管线） ====================

    /** 内置壁纸（drawable 资源）。旧路径 = `BitmapFactory.decodeResource`（与改动前逐字一致）。 */
    fun decodeWallpaperResource(context: Context, resId: Int): Bitmap? {
        val legacy = { BitmapFactory.decodeResource(context.resources, resId) }
        val hdr: (() -> Bitmap)? = if (gainmapApiAvailable()) {
            { hdrDecode(ImageDecoder.createSource(context.resources, resId), targetSample = 1) }
        } else null
        return decode(context, "res:$resId", legacy, hdr)
    }

    /**
     * 用户自选图片（私有目录文件），最长边 ≤ [maxDim]（采样方式与改动前逐字一致：
     * 先 `inJustDecodeBounds` 探尺寸，再按 2 的幂 inSampleSize 解码）。
     */
    fun decodeWallpaperFile(context: Context, file: File, maxDim: Int = 2048): Bitmap? {
        val legacy = {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        }
        val hdr: (() -> Bitmap)? = if (gainmapApiAvailable()) {
            {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
                    (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim)
                ) {
                    sample *= 2
                }
                hdrDecode(ImageDecoder.createSource(file), targetSample = sample)
            }
        } else null
        return decode(context, "file:${file.name}", legacy, hdr)
    }

    /**
     * 公共决策：先探测 HDR（ImageDecoder + gainmap），不满足任一条件即回 [legacy]（旧行为，逐像素不变）。
     * 绝不抛异常、绝不返回"半成品"。
     */
    private fun decode(
        context: Context,
        label: String,
        legacy: () -> Bitmap?,
        hdr: (() -> Bitmap)?
    ): Bitmap? {
        logEnvironmentOnce(context)

        if (hdr == null) {
            return finish(label, "SDR: API ${Build.VERSION.SDK_INT} < 34（无 Gainmap API）→ 旧解码路径", legacy)
        }
        if (!DebugSwitches.hdrImageWallpaper) {
            return finish(label, "SDR: 开关 hdrImageWallpaper=false → 旧解码路径", legacy)
        }

        val probe = try {
            hdr()
        } catch (t: Throwable) {
            // 老系统/不支持格式/损坏文件：探测只是"顺带"，失败无关紧要（底线：解码不能因它失败）
            logWarn("$label: HDR 探测解码失败 → 旧解码路径（${t.javaClass.simpleName}: ${t.message}）")
            null
        }
        if (probe == null) {
            return finish(label, "SDR: HDR 探测解码失败 → 旧解码路径", legacy)
        }

        val gm: Gainmap? = try {
            probe.gainmap
        } catch (t: Throwable) {
            null
        }
        if (gm == null) {
            probe.recycle()
            return finish(label, "SDR: 无 gain map（普通 SDR 图）→ 旧解码路径（逐像素不变）", legacy)
        }

        val meta = describe(gm)
        val hdrScreen = displayIsHdr(context)
        val forced = DebugSwitches.hdrImageForceOn
        if (!hdrScreen && !forced) {
            probe.recycle()
            return finish(
                label,
                "SDR 降级: 检测到 gain map（$meta）但屏幕无 HDR 能力 → 旧解码路径（只显示底图）",
                legacy
            )
        }

        val boost = if (hdrScreen) displayBoost(context) else FORCE_DISPLAY_BOOST
        val applied = try {
            applyGainMapManually(probe, gm, boost)
        } catch (t: Throwable) {
            logWarn("$label: 手动套用 gain map 失败（${t.javaClass.simpleName}: ${t.message}）")
            null
        }
        if (applied != null) {
            probe.recycle()
            return finish(
                label,
                "HDR 通路生效: gain map 已手动套用（$meta；displayBoost=" +
                    "${"%.2f".format(boost)}${if (hdrScreen) "" else " 模拟(=forceOn)"}）" +
                    " → RGBA_F16/EXTENDED_SRGB → 既有 HDR 窗口 + 自绘 shader 管线",
                { applied }
            )
        }
        // 套用失败：显示底图（带 gainmap 的 Bitmap 原样交给管线；真机 HDR 屏上系统还可能自动套用一次）
        return finish(label, "SDR 降级: 有 gain map 但手动套用失败 → 显示底图（不崩）", { probe })
    }

    private fun finish(label: String, summary: String, produce: () -> Bitmap?): Bitmap? {
        lastSummary = summary
        logLine("$label → $summary")
        return try {
            produce()
        } catch (t: Throwable) {
            Log.e(TAG, "$label: 旧解码路径失败（${t.javaClass.simpleName}: ${t.message}）", t)
            runCatching {
                com.example.liquidglass.debug.AppDebugLog.log(
                    "LGHDR", "$label: 旧解码路径失败（${t.javaClass.simpleName}: ${t.message}）"
                )
            }
            null
        }
    }

    /** 探测解码：软件分配（HARDWARE Bitmap 上 `getGainmap()/getPixels()` 不可用）+ 可选目标采样。 */
    private fun hdrDecode(source: ImageDecoder.Source, targetSample: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, _info, _src ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (targetSample > 1) decoder.setTargetSampleSize(targetSample)
        }

    // ==================== gain map：官方公式（手动套用） ====================

    /**
     * 权重 W：`clamp((ln(H) - ln(minRatio)) / (ln(fullRatio) - ln(minRatio)), 0, 1)`；
     * `GAINMAP_DIRECTION_HDR_TO_SDR`（底图本身是 HDR、gain map 用于降回 SDR）时取 `1 - W`（规范同款）。
     */
    internal fun gainMapWeight(displayBoost: Float, minRatio: Float, fullRatio: Float): Float {
        val h = displayBoost.coerceAtLeast(1f)
        val lo = minRatio.coerceAtLeast(1f)
        val hi = fullRatio.coerceAtLeast(lo + 1e-4f)
        val lh = ln(h)
        val w = (lh - ln(lo)) / (ln(hi) - ln(lo))
        return w.coerceIn(0f, 1f)
    }

    /**
     * 单通道公式（与 `Gainmap` javadoc《Applying a gainmap manually》逐字对应）：
     * `D = (B + epsilonSdr) * exp(mix(ln(ratioMin), ln(ratioMax), pow(G, gamma)) * W) - epsilonHdr`
     *
     * @param sdrLinear 底图线性值（与 gainmap 同一组 primaries）
     * @param gain gain map 归一化取值 G ∈ [0,1]
     * @param weight [gainMapWeight] 的结果（HDR_TO_SDR 反向已折进 weight）
     */
    internal fun applyChannel(
        sdrLinear: Float,
        gain: Float,
        ratioMin: Float,
        ratioMax: Float,
        gamma: Float,
        epsilonSdr: Float,
        epsilonHdr: Float,
        weight: Float
    ): Float {
        val g = gain.coerceIn(0f, 1f)
        val gm = if (gamma > 0f && gamma != 1f) g.pow(gamma) else g
        val logRecovery = ln(ratioMin.coerceAtLeast(1e-6f)) +
            (ln(ratioMax.coerceAtLeast(1e-6f)) - ln(ratioMin.coerceAtLeast(1e-6f))) * gm
        return (sdrLinear + epsilonSdr) * exp(logRecovery * weight) - epsilonHdr
    }

    /** 扩展 sRGB 传递函数（OETF，>1.0 继续外推 —— 与 EXTENDED_SRGB 色域语义一致）。 */
    private fun encodeExtendedSrgb(x: Float): Float =
        if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

    /**
     * [applyChannel] 的等价快速版（预取 ln 的版本，供逐像素循环用）：
     * `D = (B + epsSdr) * exp((lnMin + lnSpan·pow(G,gamma)) · W) − epsHdr`。
     * 自检 ② 会核对两版数值一致（见 [selfTestFormula]），防止两处公式漂移。
     */
    internal fun applyChannelFast(
        sdrLinear: Float,
        gainPow: Float,
        lnRatioMin: Float,
        lnRatioSpan: Float,
        epsilonSdr: Float,
        epsilonHdr: Float,
        weight: Float
    ): Float = (sdrLinear + epsilonSdr) * exp((lnRatioMin + lnRatioSpan * gainPow) * weight) - epsilonHdr

    /**
     * 手动套用 gain map → 返回**新建的** RGBA_F16 / EXTENDED_SRGB 位图（可承载 >1.0 的 HDR 值）。
     * 失败/权重为 0（屏幕余量不足 = 官方语义下的 SDR 恒等）→ 返回 null（调用方回底图）。
     *
     * 细节：
     *   · gain map 可与底图不同分辨率（常见 1/4）→ 双线性缩放到同尺寸（规范要求 bilinear or better）；
     *   · 1 通道（`ALPHA_8`，用 A）或 3 通道（`ARGB_8888`，忽略 A）都支持；
     *   · 底图 → 线性：走底图自己的 `ColorSpace`（sRGB / Display P3 均正确）；
     *   · 线性 → 输出编码：走 `EXTENDED_SRGB` 的 `fromLinear`（扩展色域允许 >1.0，HDR 高光靠它）。
     */
    fun applyGainMapManually(base: Bitmap, gainmap: Gainmap, displayBoost: Float): Bitmap? {
        val w = base.width
        val hgt = base.height
        if (w <= 0 || hgt <= 0) return null
        if (w.toLong() * hgt.toLong() > MAX_MANUAL_PIXELS) {
            logWarn("跳过手动套用：${w}x$hgt 超过上限 $MAX_MANUAL_PIXELS 像素")
            return null
        }

        val ratioMin = gainmap.ratioMin
        val ratioMax = gainmap.ratioMax
        val gamma = gainmap.gamma
        val epsSdr = gainmap.epsilonSdr
        val epsHdr = gainmap.epsilonHdr
        val fullRatio = gainmap.displayRatioForFullHdr
        if (!fullRatio.isFinite() || fullRatio <= 1f) {
            logWarn("跳过手动套用：displayRatioForFullHdr=$fullRatio 不合法")
            return null
        }
        val minRatio = gainmap.minDisplayRatioForHdrTransition
            .takeIf { it.isFinite() && it >= 1f }?.coerceAtMost(fullRatio) ?: 1f
        var weight = gainMapWeight(displayBoost, minRatio, fullRatio)
        if (gainmap.gainmapDirection == Gainmap.GAINMAP_DIRECTION_HDR_TO_SDR) {
            weight = 1f - weight
        }
        if (weight <= 1e-4f) {
            logLine(
                "手动套用跳过：权重 W=$weight（displayBoost=${"%.3f".format(displayBoost)} < " +
                    "minDisplayRatio=$minRatio）→ 官方语义下等同底图（SDR 恒等）"
            )
            return null
        }

        val baseCs = (base.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)) as? ColorSpace.Rgb
            ?: return null
        val outCs = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB) as? ColorSpace.Rgb
            ?: return null

        val contents = gainmap.gainmapContents
        val gmFull = if (contents.width != w || contents.height != hgt) {
            Bitmap.createScaledBitmap(contents, w, hgt, true)
        } else {
            contents
        }
        val singleChannel = contents.config == Bitmap.Config.ALPHA_8

        val n = w * hgt
        val basePx = IntArray(n)
        val gmPx = IntArray(n)
        base.getPixels(basePx, 0, w, 0, 0, w, hgt)
        gmFull.getPixels(gmPx, 0, w, 0, 0, w, hgt)

        val outBits = ShortArray(n * 4)
        val enc = FloatArray(3)
        // 【性能】底图是 8bit ⇒ 线性化每通道只有 256 个可能取值 → 预计算 LUT。
        // 为什么必须做：底图的 ColorSpace 是从 ICC 解出来的，逐像素调 toLinear 极慢
        // （模拟器实测 1024x768 直算要 3.7s；真机也会明显卡一下）。
        // 只查表不改数学：LUT[v] 就是 toLinear 对"该通道取 v、其余通道取 0"的响应 ⇒ 数值与直算一致。
        val baseLut = Array(3) { c ->
            FloatArray(256) { v ->
                val x = v / 255f
                when (c) {
                    0 -> baseCs.toLinear(x, 0f, 0f)[0]
                    1 -> baseCs.toLinear(0f, x, 0f)[1]
                    else -> baseCs.toLinear(0f, 0f, x)[2]
                }
            }
        }
        // 增益图也是 8bit：pow(G, gamma) 同样是 256 值 LUT（gamma=1 时恒等）
        val gainLut = Array(3) { c ->
            val g = gamma[c]
            if (g > 0f && g != 1f) FloatArray(256) { v -> (v / 255f).pow(g) } else null
        }
        // 每通道 ln 只算一次（原来每像素 2 次 ln）
        val lnMin = FloatArray(3) { ln(ratioMin[it].coerceAtLeast(1e-6f)) }
        val lnSpan = FloatArray(3) { ln(ratioMax[it].coerceAtLeast(1e-6f)) - lnMin[it] }
        val t0 = System.nanoTime()
        for (i in 0 until n) {
            val bp = basePx[i]
            val gp = gmPx[i]
            val g0 = if (singleChannel) (gp ushr 24) and 0xFF else (gp ushr 16) and 0xFF
            val g1 = if (singleChannel) g0 else (gp ushr 8) and 0xFF
            val g2 = if (singleChannel) g0 else gp and 0xFF

            // 底图 → 线性（查表；见上面 LUT 说明）
            val lr = baseLut[0][(bp ushr 16) and 0xFF]
            val lg = baseLut[1][(bp ushr 8) and 0xFF]
            val lb = baseLut[2][bp and 0xFF]
            val hr = applyChannelFast(
                lr, gainLut[0]?.get(g0) ?: (g0 / 255f), lnMin[0], lnSpan[0], epsSdr[0], epsHdr[0], weight
            )
            val hg = applyChannelFast(
                lg, gainLut[1]?.get(g1) ?: (g1 / 255f), lnMin[1], lnSpan[1], epsSdr[1], epsHdr[1], weight
            )
            val hb = applyChannelFast(
                lb, gainLut[2]?.get(g2) ?: (g2 / 255f), lnMin[2], lnSpan[2], epsSdr[2], epsHdr[2], weight
            )
            // 线性 → EXTENDED_SRGB 编码（>1.0 保持 >1.0 = HDR 余量）
            enc[0] = if (hr.isFinite()) hr.coerceAtLeast(0f) else lr
            enc[1] = if (hg.isFinite()) hg.coerceAtLeast(0f) else lg
            enc[2] = if (hb.isFinite()) hb.coerceAtLeast(0f) else lb
            val encOut = outCs.fromLinear(enc)
            var e0 = encOut[0]
            var e1 = encOut[1]
            var e2 = encOut[2]
            // 兜底：若该 ColorSpace 实现把 >1 的线性值截断（HDR 余量丢失），改用扩展 sRGB 传递函数自算
            if (enc[0] > 1.0f && e0 <= 1.0f) e0 = encodeExtendedSrgb(enc[0])
            if (enc[1] > 1.0f && e1 <= 1.0f) e1 = encodeExtendedSrgb(enc[1])
            if (enc[2] > 1.0f && e2 <= 1.0f) e2 = encodeExtendedSrgb(enc[2])
            val o = i * 4
            outBits[o] = Half.toHalf(e0)
            outBits[o + 1] = Half.toHalf(e1)
            outBits[o + 2] = Half.toHalf(e2)
            outBits[o + 3] = Half.toHalf(1f)
        }

        val out = Bitmap.createBitmap(w, hgt, Bitmap.Config.RGBA_F16, false, outCs)
        out.copyPixelsFromBuffer(ShortBuffer.wrap(outBits))
        if (gmFull !== contents) gmFull.recycle()
        logLine(
            "手动套用完成: ${w}x$hgt weight=${"%.3f".format(weight)} boost=${"%.2f".format(displayBoost)} " +
                "singleChannel=$singleChannel 耗时=${(System.nanoTime() - t0) / 1_000_000}ms"
        )
        return out
    }

    /** gain map 元数据摘要（日志用）。 */
    fun describe(gainmap: Gainmap): String = runCatching {
        "ratioMin=${gainmap.ratioMin.joinToString(",") { "%.3f".format(it) }} " +
            "ratioMax=${gainmap.ratioMax.joinToString(",") { "%.3f".format(it) }} " +
            "gamma=${gainmap.gamma.joinToString(",") { "%.3f".format(it) }} " +
            "epsSdr=${gainmap.epsilonSdr.joinToString(",") { "%.4f".format(it) }} " +
            "epsHdr=${gainmap.epsilonHdr.joinToString(",") { "%.4f".format(it) }} " +
            "fullRatio=${"%.3f".format(gainmap.displayRatioForFullHdr)} " +
            "minRatio=${"%.3f".format(gainmap.minDisplayRatioForHdrTransition)} " +
            "dir=${gainmap.gainmapDirection}"
    }.getOrDefault("(元数据读取失败)")

    // ==================== 自检（调试用，标志文件触发） ====================

    /**
     * 一次性自检（只在私有目录出现标志文件 `lg_hdr_selftest` 时执行；全部 try/catch，失败无副作用）：
     *   ① 解码器能力实测：同一张图分别用 ImageDecoder / BitmapFactory 解码，打印 `hasGainmap`
     *      → 回答"本平台哪条解码路径能带出 gain map"（官方只承诺 JPEG：Ultra HDR）；
     *   ② 公式数值验证：合成输入（B=0.5, G=1.0, ratioMin=1, ratioMax=4, gamma=1, boost=4）
     *      期望 D=2.0 —— 校验实现与官方 javadoc 公式一致（模拟器/平板都能跑）；
     *   ③ 运行时构造 Gainmap + `setGainmap`（代码内造测试图，不必依赖真 Ultra HDR 样本）：
     *      `hasGainmap()` 必须为 true；再手动套用 → 输出像素 >1.0（证明 HDR 余量真的进了 F16 位图）。
     *
     * @param context 应用上下文
     * @param testFile 可选的真实 Ultra HDR 测试图（push 到私有目录；null 时 ① 跳过）
     */
    fun maybeRunSelfTest(context: Context, testFile: File?) {
        if (selfTestRan) return
        selfTestRan = true
        try {
            logLine("SELFTEST 开始（标志文件 lg_hdr_selftest）")
            selfTestDecoders(testFile)
            selfTestFormula()
            selfTestSyntheticGainmap(context)
            logLine("SELFTEST 结束 ✓")
        } catch (t: Throwable) {
            logWarn("SELFTEST 异常（不影响任何用户路径）：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** ① 两个平台解码器对"图里有没有 gain map"的实测结果。 */
    private fun selfTestDecoders(testFile: File?) {
        if (testFile == null || !testFile.exists()) {
            logLine("SELFTEST ① 跳过（无测试图文件）")
            return
        }
        if (gainmapApiAvailable()) {
            try {
                val bmp = hdrDecode(ImageDecoder.createSource(testFile), targetSample = 1)
                logLine(
                    "SELFTEST ① ImageDecoder: hasGainmap=${bmp.hasGainmap()} config=${bmp.config} " +
                        "cs=${bmp.colorSpace?.name} ${if (bmp.hasGainmap()) "[" + describe(bmp.gainmap!!) + "]" else ""}" +
                        " (${testFile.name})"
                )
                bmp.recycle()
            } catch (t: Throwable) {
                logWarn("SELFTEST ① ImageDecoder 失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }
        try {
            val bf = BitmapFactory.decodeFile(testFile.absolutePath)
            logLine(
                "SELFTEST ① BitmapFactory: hasGainmap=${bf?.hasGainmap()} config=${bf?.config} " +
                    "cs=${bf?.colorSpace?.name} (${testFile.name})"
            )
            bf?.recycle()
        } catch (t: Throwable) {
            logWarn("SELFTEST ① BitmapFactory 失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** ② 纯公式数值验证（不碰位图）。 */
    private fun selfTestFormula() {
        val w = gainMapWeight(displayBoost = 4f, minRatio = 1f, fullRatio = 4f)
        val d = applyChannel(
            sdrLinear = 0.5f, gain = 1f, ratioMin = 1f, ratioMax = 4f, gamma = 1f,
            epsilonSdr = 0f, epsilonHdr = 0f, weight = w
        )
        val dSdr = applyChannel(
            sdrLinear = 0.5f, gain = 1f, ratioMin = 1f, ratioMax = 4f, gamma = 1f,
            epsilonSdr = 0f, epsilonHdr = 0f, weight = gainMapWeight(1f, 1f, 4f)
        )
        logLine(
            "SELFTEST ② 公式: W(boost=4)=${"%.4f".format(w)}（期望 1.0）D(B=0.5,G=1,max=4)=${"%.4f".format(d)}（期望 2.0）; " +
                "W(boost=1)=${"%.4f".format(gainMapWeight(1f, 1f, 4f))}（期望 0.0）D=${"%.4f".format(dSdr)}（期望 0.5=SDR 恒等）"
        )
        // 两版实现一致性（applyChannel = 官方公式直译；applyChannelFast = 逐像素循环用的预取版）
        var maxDelta = 0f
        for (gi in intArrayOf(0, 64, 128, 255)) {
            val g = gi / 255f
            for (b in floatArrayOf(0.05f, 0.3f, 0.8f, 1f)) {
                val ref = applyChannel(b, g, 1f, 4f, 1f, 0f, 0f, w)
                val fast = applyChannelFast(b, g, ln(1f), ln(4f) - ln(1f), 0f, 0f, w)
                maxDelta = kotlin.math.max(maxDelta, kotlin.math.abs(ref - fast))
            }
        }
        logLine("SELFTEST ② 两版公式一致性: max|applyChannel − applyChannelFast| = ${"%.9f".format(maxDelta)}（期望 0）")
    }

    /** ③ 运行时构造 gain map（代码内造测试图）+ 手动套用 → 输出必须 >1.0。 */
    private fun selfTestSyntheticGainmap(context: Context) {
        if (!gainmapApiAvailable()) {
            logLine("SELFTEST ③ 跳过（API < 34）")
            return
        }
        val base = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xFFC8C8C8.toInt()) // 线性 ≈ 0.578（200/255 sRGB）
        val gmBmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ALPHA_8)
        gmBmp.eraseColor(0xFFFFFFFF.toInt()) // G = 1.0（满增益）
        val gm = Gainmap(gmBmp).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(4f, 4f, 4f)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setDisplayRatioForFullHdr(4f)
            setMinDisplayRatioForHdrTransition(1f)
            setGainmapDirection(Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR)
        }
        base.setGainmap(gm)
        logLine("SELFTEST ③ 运行时构造: hasGainmap=${base.hasGainmap()}（期望 true）${describe(base.gainmap!!)}")
        val out = applyGainMapManually(base, gm, 4f)
        if (out == null) {
            logWarn("SELFTEST ③ 手动套用返回 null（不应发生）")
        } else {
            val bits = ShortArray(8 * 8 * 4)
            out.copyPixelsToBuffer(ShortBuffer.wrap(bits))
            val r = Half.toFloat(bits[0])
            // 注意：toLinear 定义在 ColorSpace.Rgb 上（不在 ColorSpace 基类）→ 必须显式强转
            val outRgbCs = out.colorSpace as? ColorSpace.Rgb
            val linBack: Float = if (outRgbCs != null) {
                val v = outRgbCs.toLinear(r, r, r)
                if (v != null && v.size >= 3) v[0] else Float.NaN
            } else {
                Float.NaN
            }
            logLine(
                "SELFTEST ③ 输出: config=${out.config} cs=${out.colorSpace?.name} hasGainmap=${out.hasGainmap()} " +
                    "enc(R)=${"%.4f".format(r)}（>1.0 ⇒ HDR 余量）线性回读=${"%.4f".format(linBack)}（期望 ≈2.31 = 0.578×4）"
            )
            out.recycle()
        }
        base.recycle()
    }
}
