package com.example.liquidglass.glass

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import com.example.liquidglass.ui.G2RoundedShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * 玻璃形状：SDF 与 Compose 可见边界必须一致。
 *
 * Shader 侧（GlassShaders.sdShape/gradShape）与 Compose 侧（toComposeShape）
 * 使用同一枚举序数，形状切换后光学边界、折射法线、色散带、点击裁切同步跟随。
 *
 * 序号即 Shader uniform shapeType 的 int 值，新形状只能追加在末尾。
 */
enum class GlassShape(val displayName: String) {

    /** 圆角矩形（默认）。 */
    ROUNDED_RECT("圆角矩形"),

    /** 正圆。 */
    CIRCLE("圆形"),

    /** 胶囊形（长圆）。 */
    CAPSULE("胶囊"),

    /** 椭圆。 */
    ELLIPSE("椭圆"),

    /** 等腰三角形（上窄下宽）。 */
    TRIANGLE("三角形"),

    /** 六边形（点顶朝上，可扩展多边形代表）。 */
    HEXAGON("六边形"),

    /** 超椭圆 / 方圆（Squircle，n=4）。 */
    SUPERELLIPSE("超椭圆");

    /**
     * Compose 可见边界（drawBackdrop 的 shape 裁剪 + 前景 clip）。
     *
     * 铁律：可见边界必须与 Shader 侧 SDF 完全同源，否则边缘高光会"错位"——
     * 圆角矩形曾写成 RoundedCornerShape(36)（= 短边 36% ≈ 236px），而 SDF 用的是
     * 36dp ≈ 101px，导致亮线沿一个"更小圆角"的轮廓走、四个角完全没有高光。
     * 现在一律使用 [cornerRadiusDp] 的绝对值，与 UltraClearGlassEffect.cornerRadiusPx
     * （min(cornerRadiusDp*density, 短边/2)）语义一致。
     */
    fun toComposeShape(): Shape = when (this) {
        // G2 连续圆角：与 Shader 侧 sdContinuousRect 同源（角部向外饱满、曲率连续）
        ROUNDED_RECT -> G2RoundedShape(cornerRadiusDp.dp, cornerRadiusDp.dp, cornerRadiusDp.dp, cornerRadiusDp.dp)
        CIRCLE -> CircleShape
        CAPSULE -> RoundedCornerShape(50)
        ELLIPSE -> EllipseShape
        TRIANGLE -> TriangleShape
        HEXAGON -> HexagonShape
        SUPERELLIPSE -> SuperellipseShape
    }

    /**
     * 卡片宽高比 w/h（0 = 不约束）。
     *
     * 【②B·胶囊/椭圆改【横向】2026-09-14 用户选定】CAPSULE 0.52 → **1.92**、ELLIPSE 0.72 → **1.39**
     * —— 两个新值都是旧值的【倒数】（1/0.52 = 1.923、1/0.72 = 1.389）⇒ 形状本身没变，只是旋转 90°。
     *
     * 为什么必须与 ③B 的【按长边】尺寸口径（DebugSwitches.sizeLongEdge）同时生效：
     *   旧口径 w = glassSize×屏宽 ⇒ 竖置时宽是【短边】、横置时宽是【长边】
     *   ⇒ 单单把 0.52 换成 1.92，长边会从 1.92×glassSize×屏宽 缩到 1×glassSize×屏宽（小 1.92 倍 ✗）。
     *   按长边口径后：长边 = max(a,1/a) × glassSize × 屏宽
     *   ⇒ 竖置(0.52) 与 横置(1.92) 算出的是【同一个矩形转置】（644×1237 ↔ 1237×644 @glassSize=0.35/1840 屏）
     *   ⇒ 横竖切换大小感一致 ✓（这正是 ③B 的验收口径）。
     *
     * ROUNDED_RECT = 0（不约束，走 fillMaxWidth + fillMaxHeight 旧分支）✓；
     * CIRCLE / TRIANGLE / HEXAGON / SUPERELLIPSE = 1 未动 ✓（a = 1 时两套口径解析等价 ⇒ 逐像素不变 ✓）。
     */
    val aspectRatio: Float
        get() = when (this) {
            ROUNDED_RECT -> 0f
            CIRCLE -> 1f
            CAPSULE -> 1.92f
            ELLIPSE -> 1.39f
            TRIANGLE -> 1f
            HEXAGON -> 1f
            SUPERELLIPSE -> 1f
        }

    /** 前景内容区宽度占卡片比例（内容随形状自适应收窄，避免被裁剪）。 */
    val contentWidthFraction: Float
        get() = when (this) {
            ROUNDED_RECT -> 0.94f
            CIRCLE -> 0.66f
            CAPSULE -> 0.84f
            ELLIPSE -> 0.76f
            TRIANGLE -> 0.80f
            HEXAGON -> 0.68f
            SUPERELLIPSE -> 0.72f
        }

    /** 前景内容垂直偏置（0=顶，0.5=中，1=底；三角形底部宽，内容偏下）。 */
    val contentVerticalBias: Float
        get() = when (this) {
            TRIANGLE -> 0.62f
            else -> 0.5f
        }

    /** 圆角半径 dp（圆角矩形用）。 */
    val cornerRadiusDp: Float
        get() = when (this) {
            ROUNDED_RECT -> 36f
            else -> 0f
        }
}

/**
 * 按压收缩时的可见边界：在元素内部按 (insetX, insetY) 内缩的圆角矩形。
 *
 * 必须与 Shader 侧 `shapeInflate` 的收缩量【逐轴一致】，否则又会出现
 * "可见边界切掉贴边高光"的问题（向外膨胀时踩过这个坑，向内收缩同样要同源）。
 * 圆角半径同步减去收缩量：内缩后的圆角矩形在几何上等价于原形状缩小后的轮廓。
 */
class PressInsetShape(
    private val insetX: Float,
    private val insetY: Float,
    private val radiusPx: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val left = insetX
        val top = insetY
        val right = (size.width - insetX).coerceAtLeast(left + 1f)
        val bottom = (size.height - insetY).coerceAtLeast(top + 1f)
        val r = (radiusPx - maxOf(insetX, insetY)).coerceAtLeast(0f)
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(left, top, right, bottom),
                    cornerRadius = CornerRadius(r, r)
                )
            )
        }
        return Outline.Generic(path)
    }
}

/**
 * 椭圆可见边界（与 AGSL shapeType 3 的归一化圆 SDF 一致）：
 * x²/a² + y²/b² = 1，内接于卡片尺寸。
 * 旧实现用 RoundedCornerShape(50)（= 胶囊），与椭圆 SDF 不符，
 * 边缘高光会沿椭圆轮廓切进可见边界内部（v1.16.0 修）。
 */
private object EllipseShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply { addOval(Rect(Offset.Zero, size)) }
        return Outline.Generic(path)
    }
}

/**
 * 三角形可见边界（与 AGSL sdTriangleShape 顶点一致）：
 * 顶 (0.5w, 0.14h)，底 (±0.84w, 0.92h)。
 */
private object TriangleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.14f)
            lineTo(size.width * 0.92f, size.height * 0.92f)
            lineTo(size.width * 0.08f, size.height * 0.92f)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * 正六边形可见边界（点顶朝上，与 AGSL sdHexagonShape 顶点同源）。
 *
 * 【形状缺陷修复·六边形不是正六边形】旧实现用「卡片比例顶点」：
 * (0.50,0.01)(0.90,0.26)(0.90,0.74)(0.50,0.99)(0.10,0.74)(0.10,0.26)，
 * 按轴独立给比例（x 居中 ±0.40w ↔ y 居中 ±0.49h）⇒ 与 60° 顶点角不自洽：
 * 644×644 卡片上解析得 边长 303.79 / 309.14（极差 5.35px，应全等）、
 * 顶点半径 300.41 / 315.56（极差 15.15px，顶点不共圆）、
 * 内角 115.989° / 122.005°（应恒 120°，±2.01°）✗。
 * 旧 shader 顶点表与 Compose 侧同为 ±0.80·halfW（仅底顶点差 0.01·hy）：解析得
 * 顶点半径极差 18.37px、内角 114.965° / 122.518°，且与 Compose 可见边界最大偏差 3.22px
 * （x=±0.80·halfW 相同，底顶点 y 一个 0.98·hy 一个 0.99·hy）✗ —— 同源铁律差点被漏掉。
 *
 * 修复：**极坐标参数化**（单一半径 + 等角步长）——
 *   angle_k = ROTATION + k·(π/3),  v_k = center + R·(cos angle_k, sin angle_k), k = 0..5
 * 单一 R ⇒ 六顶点严格共圆（半径 R）、六边严格等长（= R）、六内角严格 120° ✓。
 * R = FILL_FACTOR × min(h/2, w/√3)：正六边形 高 = 2R、宽 = √3R，两个方向都要放进卡片；
 * 正方卡片（HEXAGON 的 aspectRatio = 1）时 min(h/2, w/√3) = minDimension/2 ⇒
 * 即「R = size.minDimension/2 × fillFactor」；非正方卡片下本式比 minDimension/2 更贴边
 * （宽向受 √3R ≤ w 约束而不是 w/2）。
 * FILL_FACTOR = 0.98 = 2% 内缩余量：与旧实现 0.01/0.99 的余量同量级，
 * 保证贴边高光/羽化不被卡片裁剪切掉。
 *
 * 几何自检（float32 复算 Compose/shader 两侧公式，见交付报告）：
 *   644×644 卡片：边长极差 0.0002px、顶点半径极差 0.00003px、内角 120.0000°×6（极差 1.4e-5°），
 *   两侧顶点最大偏差 0.00007px ⇒ 同源 ✓（旧：极差 5.35px / 15.15px / ±2.01°、偏差 3.22px ✗）
 */
private object HexagonShape : Shape {
    /** cos30° = √3/2：正六边形（点顶朝上）半宽 / 外接圆半径。 */
    private const val COS30 = 0.8660254f

    /** 顶点角步长 = 60° = π/3：正六边形的六个顶点等角分布。 */
    private const val STEP_RAD = (Math.PI / 3.0).toFloat()

    /** 起始角 = −90°：点顶朝上（屏幕坐标 y 向下 ⇒ 负角在视觉上方）。 */
    private const val ROTATION_RAD = (-Math.PI / 2.0).toFloat()

    /** 内缩余量（0.98 = 2%），与旧实现 0.01/0.99 同量级。 */
    private const val FILL_FACTOR = 0.98f

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        // 单一外接圆半径（绝不按轴各自取半径 —— 那正是「拉伸」的来源 ✗）
        // 高 = 2R ≤ h、宽 = √3R ≤ w（√3 = 2·cos30°）
        val r = FILL_FACTOR * minOf(h * 0.5f, w * 0.5f / COS30)
        val path = Path()
        for (k in 0 until 6) {
            val angle = ROTATION_RAD + k * STEP_RAD
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * 超椭圆可见边界（n=4 Squircle，与 AGSL sdSuperellipse 隐式方程一致）：
 * |x/halfW|^4 + |y/halfY|^4 = 1，参数化采样 64 点。
 */
private object SuperellipseShape : Shape {
    private const val N = 4f

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val ax = w * 0.5f
        val ay = h * 0.5f
        val exp = 2f / N
        val path = Path()
        val steps = 64
        for (i in 0 until steps) {
            val t = (i.toFloat() / steps) * 2.0 * Math.PI
            val ct = cos(t).toFloat()
            val st = sin(t).toFloat()
            val x = cx + ax * sign(ct) * abs(ct).powLocal(exp)
            val y = cy + ay * sign(st) * abs(st).powLocal(exp)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private fun Float.powLocal(e: Float): Float =
        if (this == 0f) 0f else Math.pow(this.toDouble(), e.toDouble()).toFloat()
}
