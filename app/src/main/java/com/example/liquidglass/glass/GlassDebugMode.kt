package com.example.liquidglass.glass

/**
 * Shader 调试模式：按二分排查顺序定义。
 *
 * 通过 int uniform 传入 AGSL。[shaderCode] 是与 Shader 分支号一一对应的
 * 显式编码——不要依赖 enum.ordinal（重排枚举会导致分支静默错位，
 * v1.8.5 曾因此导致"仅色散/纯模糊对照"点击无效）。
 *
 * 排查顺序：BACKGROUND_ONLY（背景无彩边？）
 *           → REFRACTION_ONLY（Golden Reference 回归）
 *           → ACHROMATIC_BLUR_ONLY（模糊无色散？）
 *           → DISPERSION_ONLY（chromaDelta 量级）
 *           → FINAL
 *
 * 注：原 DISPERSION_MASK（色散遮罩灰度可视化）已移除——
 * 它只是形状 SDF 遮罩的显示，不采样背景、不参与最终渲染，
 * 且与玻璃可见形状的观感不一致，容易误导排查。
 */
enum class GlassDebugMode(val displayName: String, val shaderCode: Int) {

    /** 最终效果：全部光学层叠加。 */
    FINAL("最终", 0),

    /** 完全原始背景采样（无折射、无模糊、无色散）。 */
    BACKGROUND_ONLY("仅背景", 1),

    /** Golden Reference：正确的折射结果，关闭 Blur/Dispersion/Tint/Fresnel。 */
    REFRACTION_ONLY("仅折射", 2),

    /** 正确 refractedCoord + 无色散模糊（验证 taps 无 RGB 分离）。 */
    ACHROMATIC_BLUR_ONLY("仅模糊", 3),

    /** 放大 chromaDelta 显示（正式输出恢复 FINAL）。 */
    DISPERSION_ONLY("仅色散", 4),

    /** 纯高斯模糊对照：保持图形不变、无折射/色散/高光，仅背景模糊（对比用）。 */
    BLUR_COMPARE("纯模糊对照", 5)
}
