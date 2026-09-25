package com.example.liquidglass.glass

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra Clear 玻璃效果的 Shader 值计算（dp → px 统一换算）。
 *
 * 所有 dp 光学参数必须在此转换为 px 后才能进入 AGSL，
 * 否则在 xxhdpi / xxxhdpi 设备上会产生完全不同的光学表现。
 */
object UltraClearGlassEffect {

    /** Shader 所需的全部像素值。 */
    data class ShaderValues(
        /** 捕获层外扩边距 px（覆盖折射 + 模糊 + 色散的采样范围，防黑边）。 */
        val layerPaddingPx: Float,
        /** 边缘光学作用带 px。 */
        val edgeZonePx: Float,
        /** 折射作用带高度 px。 */
        val refractionHeightPx: Float,
        /** 中心模糊半径 px。 */
        val blurRadiusPx: Float,
        /** 边缘模糊半径 px。 */
        val edgeBlurRadiusPx: Float,
        /** 边缘折射采样偏移 px。 */
        val refractionOffsetPx: Float,
        /** 色散偏移 px（必须远小于主折射偏移，否则产生 RGB 重影）。 */
        val dispersionOffsetPx: Float
    )

    /**
     * 由玻璃参数与设备密度计算 Shader 像素值。
     *
     * @param density 设备密度（每 dp 的 px 数）。
     * @param cardWidthPx / cardHeightPx 卡片尺寸 px（用于折射带高度限制）。
     */
    fun compute(
        parameters: GlassParameters,
        density: Float,
        cardWidthPx: Float,
        cardHeightPx: Float
    ): ShaderValues {
        // 光学安全边距：折射 + 模糊 + 色散 + 8px 余量
        val paddingPx = ceil(
            (parameters.refractionOffsetDp + parameters.blurRadiusDp +
                parameters.edgeBlurRadiusDp + parameters.dispersionOffsetDp) * density + 8f
        )
        // 边缘作用带：固定 24dp
        val edgeZonePx = 24f * density
        // 折射带高度：主折射偏移的 2.5 倍，且不小于边缘模糊带
        val refractionHeightPx = max(
            parameters.refractionOffsetDp * 2.5f,
            parameters.edgeBlurRadiusDp + 16f
        ) * density
        return ShaderValues(
            layerPaddingPx = paddingPx,
            edgeZonePx = edgeZonePx,
            refractionHeightPx = refractionHeightPx,
            blurRadiusPx = parameters.blurRadiusDp * density,
            edgeBlurRadiusPx = parameters.edgeBlurRadiusDp * density,
            refractionOffsetPx = parameters.refractionOffsetDp * density,
            dispersionOffsetPx = parameters.dispersionOffsetDp * density
        )
    }

    /** 圆角半径 px（四角统一，不超过卡片短边一半）。 */
    fun cornerRadiusPx(cornerRadiusDp: Float, density: Float, cardWidthPx: Float, cardHeightPx: Float): Float {
        return min(cornerRadiusDp * density, min(cardWidthPx, cardHeightPx) / 2f)
    }
}
