package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.shouldRecordLayer
import com.kyant.backdrop.BackdropRecordPolicy
import com.kyant.backdrop.internal.recordLayer

/**
 * 【③ 内容版本门控·2026-10-14】新增可选参数 [contentVersion]：
 *   · 不传（默认 null）= 逐字老行为（被采样的层每帧重录）⇒ 零陈旧风险 ✓
 *   · 传了 = 只有【版本号变了】才重录；版本没变 ⇒ 跳过（层保留上一帧内容 = 逐像素等价）
 *     ⇒ 大面积静态玻璃（整屏背景捕获层）静止时几乎免费 ✓
 *   版本 lambda 在【绘制期】调用 ⇒ 它读到的 Compose 状态会被本节点观察（变了就重录）✓
 *   另有安全网：每 BackdropRecordPolicy.safetyRecordIntervalFrames 帧强制重录一次（漏信号自愈）。
 */
fun Modifier.layerBackdrop(
    backdrop: LayerBackdrop,
    contentVersion: (() -> Long)? = null
): Modifier = this then LayerBackdropElement(backdrop, contentVersion)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val contentVersion: (() -> Long)?
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, contentVersion)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.contentVersion = contentVersion
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
        properties["contentVersion"] = if (contentVersion == null) "null(每帧重录)" else "提供"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var contentVersion: (() -> Long)?
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        drawContent()
        // 【分层架构·录制策略②】没人采样的层不做离屏录制（首帧先录一次，避免空层；见 BackdropRecordPolicy）
        if (shouldRecordLayer(backdrop.everRecorded, backdrop.sampleSeq > 0L)) {
            val tick = ++BackdropRecordPolicy.frameTick
            val versionFn = contentVersion
            if (versionFn == null || !BackdropRecordPolicy.contentGate) {
                // 老行为：被采样 ⇒ 每帧重录
                recordLayer(backdrop.graphicsLayer, tag = "capture") { backdrop.onDraw(this@draw) }
                backdrop.everRecorded = true
                backdrop.lastRecordTick = tick
            } else {
                // 【③ 内容版本门控】版本没变 ⇒ 跳过重录（层内容保留；逐像素等价）
                val v = versionFn()
                val safety = BackdropRecordPolicy.safetyRecordIntervalFrames
                val stale = safety > 0 &&
                    backdrop.lastRecordTick != Long.MIN_VALUE &&
                    (tick - backdrop.lastRecordTick) >= safety
                if (backdrop.lastRecordedVersion == Long.MIN_VALUE ||
                    v != backdrop.lastRecordedVersion ||
                    stale
                ) {
                    recordLayer(backdrop.graphicsLayer, tag = "capture") { backdrop.onDraw(this@draw) }
                    backdrop.everRecorded = true
                    backdrop.lastRecordedVersion = v
                    backdrop.lastRecordTick = tick
                } else {
                    BackdropRecordPolicy.skipGate++
                }
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
