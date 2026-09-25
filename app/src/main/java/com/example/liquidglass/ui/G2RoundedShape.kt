package com.example.liquidglass.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * G2 连续圆角矩形 —— **与 Shader 侧 sdContinuousRect 同一套归一化定义**（铁律：可见边界与 SDF 同源）。
 *
 * 角部沿两边各延伸 E = [EXTENDED_FRACTION] * r；角部曲线为超椭圆 u^n + v^n = 1（同 n）。
 * 坐标系：Compose 要求 `createOutline` 的路径建在 (0,0)..(size.width, size.height)，
 * 之前写成以原点为中心（-hw..+hw）导致裁剪只剩左半 —— 这里显式用 0..size。
 */
internal class G2RoundedShape(
    private val topLeft: Dp = 0.dp,
    private val topRight: Dp = 0.dp,
    private val bottomRight: Dp = 0.dp,
    private val bottomLeft: Dp = 0.dp
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val lim = min(w, h) * 0.5f
        fun px(dp: Dp) = with(density) { dp.toPx() }.coerceIn(0f, lim)

        val tl = px(topLeft); val tr = px(topRight); val br = px(bottomRight); val bl = px(bottomLeft)
        val path = Path()

        // 上边（从左到右，止于右上角起点）
        path.moveTo(0f + startInset(tl, w, h), 0f)
        path.lineTo(w - startInset(tr, w, h), 0f)
        corner(path, w, 0f, tr, -1f, +1f, false, w, h)   // 右上角：内方向 (-x, +y)
        path.lineTo(w, h - startInset(br, w, h))
        corner(path, w, h, br, -1f, -1f, true, w, h)     // 右下角：内方向 (-x, -y)
        path.lineTo(0f + startInset(bl, w, h), h)
        corner(path, 0f, h, bl, +1f, -1f, false, w, h)   // 左下角：内方向 (+x, -y)
        path.lineTo(0f, 0f + startInset(tl, w, h))
        corner(path, 0f, 0f, tl, +1f, +1f, true, w, h)   // 左上角：内方向 (+x, +y)
        path.close()
        return Outline.Generic(path)
    }

    private fun startInset(rPx: Float, w: Float, h: Float): Float {
        if (rPx <= 0.01f) return 0f
        val e = rPx * EXTENDED_FRACTION
        return if (e > min(w, h) * 0.5f * 0.95f) rPx else e
    }

    /**
     * 一个角。(cx, cy) = 该角的外角点（如右上角 = (w, 0)）；(sx, sy) 指向形状内部；
     * [swap] 决定 u/v 与 X/Y 的对应（相邻角交替，保证路径方向连续）。
     * 点 = (cx + sx*e*X, cy + sy*e*Y)，X/Y 取超椭圆 u^n+v^n=1 的参数化 —— 与 SDF 同源。
     */
    private fun corner(
        path: Path, cx: Float, cy: Float, rPx: Float,
        sx: Float, sy: Float, swap: Boolean, w: Float, h: Float
    ) {
        if (rPx <= 0.01f) { path.lineTo(cx, cy); return }
        val lim = min(w, h) * 0.5f
        val e = rPx * EXTENDED_FRACTION
        if (e > lim * 0.95f) {   // 胶囊/极小控件：回退普通圆角（与 SDF 回退条件一致）
            val steps = 48   // 回退分支同样提高
            for (i in 0..steps) {
                val t = 1.0 - i.toDouble() / steps
                val a = Math.PI * 0.5 * t
                // 与 G2 分支同族（等价 n=2）：u = 1-cos, v = 1-sin，保证同向同起点
                val u = 1f - cos(a).toFloat(); val v = 1f - sin(a).toFloat()
                val X = if (swap) v else u
                val Y = if (swap) u else v
                path.lineTo(cx + sx * rPx * X, cy + sy * rPx * Y)
            }
            return
        }
        val steps = 96   // 原 18 段 → 90px 圆角上每段约 5px，肉眼可见多边形锯齿 ✗；96 段≈1px 级
        for (i in 0..steps) {
            // 与 SDF 同源：(1-u)^n + (1-v)^n = 1 的参数化 → u = 1 - cos^(2/n), v = 1 - sin^(2/n)
            // 起点侧（u=1,v=0）在"入射边"的终点，终点侧（u=0,v=1）在"出射边"的起点；
            // 相邻角交替方向，故按 swap 决定 θ 的扫描方向。
            val t = 1.0 - i.toDouble() / steps   // 统一 1→0：起点接"入射边"终点、终点接"出射边"起点
            val th = Math.PI * 0.5 * t
            val u = 1f - cos(th).coerceAtLeast(0.0).pow(2.0 / SUPERELLIPSE_N).toFloat()
            val v = 1f - sin(th).coerceAtLeast(0.0).pow(2.0 / SUPERELLIPSE_N).toFloat()
            val X = if (swap) v else u
            val Y = if (swap) u else v
            path.lineTo(cx + sx * e * X, cy + sy * e * Y)
        }
    }

    private companion object {
        const val EXTENDED_FRACTION = 1.5286651f
        const val SUPERELLIPSE_N = 3.0
    }
}
