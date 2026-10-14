package com.example.liquidglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * 第一层：BackgroundScene —— 唯一的 Backdrop 采样源。
 *
 * - 背景为实拍山水照片（内置壁纸 / 用户自选图片），全部本地资源，不联网；
 * - 自定义背景文字（内容 / 字号 / 透明度 / 位置 / 旋转）可选，默认关闭；
 * - 整个场景进入 Backdrop 捕获层，玻璃卡片与面板都从这一层取样折射。
 *
 * v1.15.0 变更：移除原先本地矢量绘制的"测试风景"（天空渐变 / 太阳 / 假山 /
 * 湖面反光 / 彩色光斑）与写死的 REFRACTION TEST、ULTRA CLEAR GLASS 文案——
 * 观感廉价，改为内置实拍山水照片作为默认背景；照片解码失败时用中性深色
 * 渐变兜底，不再回退到矢量插画。
 */
@Composable
fun BackgroundScene(
    bgText: String = "",
    bgTextSizeSp: Float = 58f,
    bgTextAlpha: Float = 0.85f,
    bgTextPositionY: Float = 0.62f,
    bgTextRotationDegrees: Float = 0f,
    bgImage: ImageBitmap? = null,
    bgImageZoom: Float = 1f,
    bgImageOffsetX: Float = 0f,
    bgImageOffsetY: Float = 0f,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier.fillMaxSize()
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawBackground(
                widthPx = size.width,
                heightPx = size.height,
                textMeasurer = textMeasurer,
                bgText = bgText,
                bgTextSizeSp = bgTextSizeSp,
                bgTextAlpha = bgTextAlpha,
                bgTextPositionY = bgTextPositionY,
                bgTextRotationDegrees = bgTextRotationDegrees,
                bgImage = bgImage,
                bgImageZoom = bgImageZoom,
                bgImageOffsetX = bgImageOffsetX,
                bgImageOffsetY = bgImageOffsetY
            )
        }
    }
}

/** 绘制背景照片（Fit + 变焦 + 偏移）与可选的自定义文字。 */
private fun DrawScope.drawBackground(
    widthPx: Float,
    heightPx: Float,
    textMeasurer: TextMeasurer,
    bgText: String,
    bgTextSizeSp: Float,
    bgTextAlpha: Float,
    bgTextPositionY: Float,
    bgTextRotationDegrees: Float,
    bgImage: ImageBitmap?,
    bgImageZoom: Float,
    bgImageOffsetX: Float,
    bgImageOffsetY: Float
) {
    val w = widthPx
    val h = heightPx

    val img = bgImage
    if (img != null) {
        val iw = img.width.toFloat()
        val ih = img.height.toFloat()
        // 填充策略（v1.16.2）：
        // 1) Fit —— 完整显示整张图片（不裁切），默认行为；
        // 2) Cover —— 当 Fit 会在屏幕上留出明显空带时（典型：竖版壁纸放到横屏上，
        //    或图片比例与屏幕差得远）改用按短边铺满 + 居中裁切，避免"中间一条竖图
        //    + 左右大黑边"的观感（用户反馈横屏像没有同步拉伸）。
        val fitScale = min(w / iw, h / ih)
        val coverScale = max(w / iw, h / ih)
        val useCover = fitScale < coverScale * 0.85f
        val scaleBase = if (useCover) coverScale else fitScale
        val drawScale = scaleBase * bgImageZoom
        val dw = iw * drawScale
        val dh = ih * drawScale
        val ox = (w - dw) * 0.5f + bgImageOffsetX * w
        val oy = (h - dh) * 0.5f + bgImageOffsetY * h
        drawImage(
            image = img,
            dstOffset = androidx.compose.ui.unit.IntOffset(ox.toInt(), oy.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt())
        )
    } else {
        // 兜底：中性深色渐变（照片解码失败等极端情况）
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF0E1219), Color(0xFF1A2130), Color(0xFF090C12)),
                startY = 0f,
                endY = h
            )
        )
    }

    // 自定义背景文字：默认空字符串（不显示内容）。
    // 文字绘制在捕获层内，会被玻璃真实折射/模糊；白描边 + 深色正文保证可读。
    if (bgText.isNotBlank()) {
        val dark = Color(0xFF14213D)
        val light = Color(0xFFF8FAFF)
        val mainStyle = TextStyle(fontSize = bgTextSizeSp.sp, fontWeight = FontWeight.Bold)
        val measured = textMeasurer.measure(bgText, mainStyle)
        val center = Offset(w * 0.5f, h * bgTextPositionY)
        withTransform({
            if (bgTextRotationDegrees != 0f) {
                rotate(degrees = bgTextRotationDegrees, pivot = center)
            }
        }) {
            drawText(
                textMeasurer = textMeasurer,
                text = bgText,
                topLeft = Offset(center.x - measured.size.width / 2f + 2f, center.y + 2f),
                style = mainStyle.copy(color = light.copy(alpha = bgTextAlpha))
            )
            drawText(
                textMeasurer = textMeasurer,
                text = bgText,
                topLeft = Offset(center.x - measured.size.width / 2f, center.y),
                style = mainStyle.copy(color = dark.copy(alpha = bgTextAlpha))
            )
        }
    }
}
