package com.example.liquidglass.glass

/**
 * 玻璃视觉预设。
 *
 * 视觉基准对应 iOS 26 Developer Beta 1 / WWDC25 初期的高透明 Liquid Glass 表现
 * （而非 Beta 3 之后明显增磨砂的版本）。
 */
enum class GlassPreset(val displayName: String, val description: String) {

    /** 默认预设：高背景透过率、极低白色填充、中心接近清透、边缘折射明显。 */
    IOS26_BETA1_ULTRA_CLEAR("通透", "高透、低白雾、边缘折射为主"),

    /** 日常可读性与视觉效果平衡。 */
    BALANCED("均衡", "可读性与玻璃感平衡"),

    /** 高对比度 / 减少透明效果，用于可访问性。 */
    FROSTED_ACCESSIBLE("磨砂", "高对比度、低透明度"),

    /** 用户独立调节每个参数。 */
    CUSTOM("自定义", "独立调节全部参数")
}
