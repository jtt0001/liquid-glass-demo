/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE）
 *   源文件：app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/ProgressConverter.kt
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动：仅包名（com.kyant.backdrop.catalog.utils → com.example.liquidglass.ui.components.liquid）。
 * 说明：批 1 的 Button/Toggle 目前未用到它，随 utils 一并搬入，供批 2（Slider 的 DampedDragAnimation
 *      取值曲线）/批 3 复用（避免届时再动一遍 utils 目录）。
 */
package com.example.liquidglass.ui.components.liquid

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

fun interface ProgressConverter {

    fun convert(progress: Float): Float

    companion object {

        val Default: ProgressConverter =
            ProgressConverter { progress ->
                (1f - exp(-abs(progress))) * progress.sign
            }
    }
}
