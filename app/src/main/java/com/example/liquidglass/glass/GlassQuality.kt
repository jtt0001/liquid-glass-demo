package com.example.liquidglass.glass

/**
 * 画质档位：决定 AGSL 模糊采样点数。
 *
 * 三个档位对应三份预编译 AGSL 源码（采样点固定 5 / 9 / 13 taps），
 * 通过 Backdrop 的 RuntimeShaderCache（按 key 缓存）分别编译并缓存，
 * 切换档位时直接选用已缓存 Shader，不会重新编译。
 */
enum class GlassQuality(val displayName: String, val tapCount: Int) {

    /** 5 taps：优先保帧率，保留边缘折射与高光。 */
    PERFORMANCE("流畅", 5),

    /** 9 taps：默认档位，画质与性能平衡。 */
    BALANCED("标准", 9),

    /** 13 taps：最高画质，模糊更平滑。 */
    QUALITY("高清", 13)
}
