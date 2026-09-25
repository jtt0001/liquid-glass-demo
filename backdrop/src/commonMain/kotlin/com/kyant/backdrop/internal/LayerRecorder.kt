package com.kyant.backdrop.internal

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.BackdropRecordProbe
import androidx.compose.ui.unit.toIntSize

context(node: DelegatableNode)
internal fun DrawScope.recordLayer(
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),
    tag: String = "layer",
    block: DrawScope.() -> Unit
) {
    // 【分层架构·录制探针】默认关（一次 volatile 读）；开启后逐层统计次数/尺寸，见 BackdropRecordProbe
    BackdropRecordProbe.onRecord(layer, tag, size.width, size.height)
    val density = node.requireDensity()
    layer.record(size) {
        val prevDensity = drawContext.density
        drawContext.density = density
        try {
            this.block()
        } finally {
            drawContext.density = prevDensity
        }
    }
}
