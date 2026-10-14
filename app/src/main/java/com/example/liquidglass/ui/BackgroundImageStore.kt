package com.example.liquidglass.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 自定义背景图片持久化：
 *
 * 选择图片时把原始字节拷贝进应用私有目录（filesDir），重启后必定可读，
 * 不依赖 SAF 授权持久化（GetContent 返回的授权在重启后可能失效）；
 * 缩放/偏移调整保存在 SharedPreferences。
 */
object BackgroundImageStore {

    private const val PREFS_NAME = "liquid_glass_background"
    private const val KEY_ZOOM = "image_zoom"
    private const val KEY_OFFSET_X = "image_offset_x"
    private const val KEY_OFFSET_Y = "image_offset_y"
    private const val KEY_BUILTIN_INDEX = "builtin_wallpaper_index"

    private const val DEFAULT_ZOOM = 1f
    private const val DEFAULT_OFFSET = 0f

    /** 应用私有目录内的图片副本。 */
    fun imageFile(context: Context): File = File(context.filesDir, "bg_custom_image")

    /**
     * 把所选图片字节流拷贝到私有目录。返回是否成功。
     *
     * 【原子替换】不能直接往 [imageFile] 上覆盖写：写入中途失败（源流读错 / 空间不足 /
     * 进程被杀）会留下一个被截断的文件，而 [hasStoredImage] 只判 length>0，
     * 于是重启后"看起来有图、解码却是 null"= 静默丢图。
     * 所以改为：同目录写临时文件 → 校验（字节数 > 0 且可解码）→ renameTo 原子替换
     * （同分区 rename 是原子操作，旧副本要么完整保留、要么被完整替换）。
     * 任一步失败：删掉临时文件、保留旧副本、返回 false。
     */
    fun copyFromUri(context: Context, uri: Uri): Boolean {
        val dst = imageFile(context)
        // 临时文件与目标同目录（同分区），保证 renameTo 是原子替换
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        return try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                false
            } else {
                tmp.delete()   // 清掉上一次失败可能留下的残片
                input.use { stream ->
                    tmp.outputStream().use { output -> stream.copyTo(output) }
                }
                // 校验在 rename 之前：字节数为 0 或解不出尺寸的一律不算成功
                if (tmp.length() > 0L && isDecodableImage(tmp) && tmp.renameTo(dst)) {
                    true
                } else {
                    tmp.delete()
                    false
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("LiquidGlass", "copyFromUri failed", t)
            try {
                tmp.delete()
            } catch (_: Throwable) {
            }
            false
        }
    }

    /** 只读尺寸探测确认文件确实是一张可解码的图片（不分配像素内存）。 */
    private fun isDecodableImage(file: File): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0
    } catch (t: Throwable) {
        false
    }

    /** 删除私有目录副本并清空调整参数。 */
    fun clear(context: Context) {
        try {
            imageFile(context).delete()
        } catch (_: Throwable) {
        }
        saveAdjustments(context, DEFAULT_ZOOM, DEFAULT_OFFSET, DEFAULT_OFFSET)
    }

    fun hasStoredImage(context: Context): Boolean = imageFile(context).length() > 0

    /**
     * 从私有目录解码（最长边 ≤ [maxDim] px），失败返回 null 并清理坏文件。
     *
     * 【HDR 图片支持】解码走 [com.example.liquidglass.hdr.HdrImageSupport]：
     * 图里带 gain map（Ultra HDR JPEG）且屏幕支持 HDR 时输出 HDR 位图；
     * 其余全部情况（无 gainmap / 无 HDR 屏 / API<34 / 开关关 / 任何异常）都走原来的
     * BitmapFactory 路径 = 与改动前逐像素一致。采样逻辑逐字沿用原实现。
     */
    fun decodeStored(context: Context, maxDim: Int = 2048): Bitmap? {
        val file = imageFile(context)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            com.example.liquidglass.hdr.HdrImageSupport.decodeWallpaperFile(context, file, maxDim)
        } catch (t: Throwable) {
            android.util.Log.e("LiquidGlass", "decodeStored failed", t)
            null
        }
    }

    /** 保存内置壁纸选择（-1 = 默认矢量场景）。 */
    fun saveBuiltinIndex(context: Context, index: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BUILTIN_INDEX, index)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /** 读取内置壁纸选择，未设置返回 -1。 */
    fun loadBuiltinIndex(context: Context): Int = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BUILTIN_INDEX, -1)
    } catch (_: Throwable) {
        -1
    }

    /** 保存缩放/偏移（异步写盘）。 */
    fun saveAdjustments(context: Context, zoom: Float, offsetX: Float, offsetY: Float) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_ZOOM, zoom)
                .putFloat(KEY_OFFSET_X, offsetX)
                .putFloat(KEY_OFFSET_Y, offsetY)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /** 读取缩放/偏移（first=zoom, second=x, third=y）。 */
    fun loadAdjustments(context: Context): Triple<Float, Float, Float> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Triple(
                prefs.getFloat(KEY_ZOOM, DEFAULT_ZOOM),
                prefs.getFloat(KEY_OFFSET_X, DEFAULT_OFFSET),
                prefs.getFloat(KEY_OFFSET_Y, DEFAULT_OFFSET)
            )
        } catch (_: Throwable) {
            Triple(DEFAULT_ZOOM, DEFAULT_OFFSET, DEFAULT_OFFSET)
        }
    }
}
