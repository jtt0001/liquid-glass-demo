/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE / LICENSES/Backdrop-APACHE-2.0.txt）
 *   源文件：app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt（49 行）
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动（适配差异清单 D1）：仅包名
 *   com.kyant.backdrop.catalog.components → com.example.liquidglass.ui.components.liquid
 * （LocalLiquidBottomTabScale 是 internal ⇒ 与 LiquidBottomTabs 同包即可，无需公开）
 * 其余逐字保留：Column + clip(Capsule) + clickable(role = Role.Tab, 无涟漪) + fillMaxHeight +
 *   weight(1f) + graphicsLayer{ scaleX/scaleY = scale() }（scale 由 LiquidBottomTabs 按压进度提供）。
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
