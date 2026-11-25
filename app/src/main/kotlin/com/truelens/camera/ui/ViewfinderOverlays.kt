package com.truelens.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.truelens.camera.camera.AspectRatioMask
import com.truelens.camera.camera.GridOverlay
import kotlin.math.abs

@Composable
fun AspectRatioMaskOverlay(
    mask: AspectRatioMask,
    modifier: Modifier = Modifier
) {
    val targetRatio = mask.widthOverHeight ?: return
    Canvas(modifier = modifier.fillMaxSize()) {
        val screenRatio = size.width / size.height
        val matteColor = Color.Black.copy(alpha = 0.68f)
        val borderColor = Color.White.copy(alpha = 0.45f)
        val stroke = 1.dp.toPx()

        if (targetRatio > screenRatio) {
            // Letterbox top & bottom (e.g. 4:5, 3:2, XPAN)
            val cropH = size.width / targetRatio
            val barH = ((size.height - cropH) / 2f).coerceAtLeast(0f)
            drawRect(matteColor, Offset.Zero, Size(size.width, barH))
            drawRect(matteColor, Offset(0f, size.height - barH), Size(size.width, barH))
            drawLine(borderColor, Offset(0f, barH), Offset(size.width, barH), stroke)
            drawLine(borderColor, Offset(0f, size.height - barH), Offset(size.width, size.height - barH), stroke)
        } else {
            // Pillarbox left & right
            val cropW = size.height * targetRatio
            val barW = ((size.width - cropW) / 2f).coerceAtLeast(0f)
            drawRect(matteColor, Offset.Zero, Size(barW, size.height))
            drawRect(matteColor, Offset(size.width - barW, 0f), Size(barW, size.height))
            drawLine(borderColor, Offset(barW, 0f), Offset(barW, size.height), stroke)
            drawLine(borderColor, Offset(size.width - barW, 0f), Offset(size.width - barW, size.height), stroke)
        }
    }
}

@Composable
fun CompositionGridOverlay(
    grid: GridOverlay,
    modifier: Modifier = Modifier
) {
    if (grid == GridOverlay.NONE) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.28f)
        val stroke = 1.dp.toPx()
        when (grid) {
            GridOverlay.RULE_OF_THIRDS -> {
                for (i in 1..2) {
                    val x = size.width * i / 3f
                    val y = size.height * i / 3f
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                }
            }
            GridOverlay.GOLDEN_RATIO -> {
                val r1 = 0.382f
                val r2 = 0.618f
                drawLine(lineColor, Offset(size.width * r1, 0f), Offset(size.width * r1, size.height), stroke)
                drawLine(lineColor, Offset(size.width * r2, 0f), Offset(size.width * r2, size.height), stroke)
                drawLine(lineColor, Offset(0f, size.height * r1), Offset(size.width, size.height * r1), stroke)
                drawLine(lineColor, Offset(0f, size.height * r2), Offset(size.width, size.height * r2), stroke)
            }
            GridOverlay.CROSSHAIR -> {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val arm = 22.dp.toPx()
                drawLine(lineColor, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke * 1.5f)
                drawLine(lineColor, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke * 1.5f)
            }
            GridOverlay.NONE -> Unit
        }
    }
}

@Composable
fun LiveHistogramView(
    bins: FloatArray,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(124.dp)
            .height(48.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .border(0.75.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (bins.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height
            val step = w / (bins.size - 1).coerceAtLeast(1)

            // Subtle quarter vertical guides
            val guideColor = Color.White.copy(alpha = 0.12f)
            for (q in 1..3) {
                val gx = w * q / 4f
                drawLine(guideColor, Offset(gx, 0f), Offset(gx, h), 1f)
            }

            val fillPath = Path().apply {
                moveTo(0f, h)
                bins.forEachIndexed { idx, v ->
                    val x = idx * step
                    val y = h - (v.coerceIn(0f, 1f) * h)
                    lineTo(x, y)
                }
                lineTo(w, h)
                close()
            }
            drawPath(fillPath, color = Color(0xFFEAEAEA).copy(alpha = 0.45f))

            val strokePath = Path().apply {
                bins.forEachIndexed { idx, v ->
                    val x = idx * step
                    val y = h - (v.coerceIn(0f, 1f) * h)
                    if (idx == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                strokePath,
                color = Color.White.copy(alpha = 0.9f),
                style = Stroke(width = 1.25.dp.toPx())
            )
        }
    }
}

@Composable
fun HorizonLevelOverlay(
    rollDegrees: Float,
    modifier: Modifier = Modifier
) {
    val isLevel = abs(rollDegrees) < 1.5f
    val color = if (isLevel) Color(0xFF34D399) else Color.White.copy(alpha = 0.65f)
    Canvas(modifier = modifier.width(120.dp).height(24.dp)) {
        val cy = size.height / 2f
        val cx = size.width / 2f
        // Fixed side ticks
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, cy), Offset(18.dp.toPx(), cy), 1.5.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width - 18.dp.toPx(), cy), Offset(size.width, cy), 1.5.dp.toPx())

        // Rotating center bar
        val half = 28.dp.toPx()
        val rad = Math.toRadians(rollDegrees.coerceIn(-35f, 35f).toDouble())
        val dx = (half * kotlin.math.cos(rad)).toFloat()
        val dy = (half * kotlin.math.sin(rad)).toFloat()
        drawLine(
            color = color,
            start = Offset(cx - dx, cy - dy),
            end = Offset(cx + dx, cy + dy),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
fun TapFocusReticle(
    normalizedPosition: Offset?,
    isLocked: Boolean,
    modifier: Modifier = Modifier
) {
    if (normalizedPosition == null) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = normalizedPosition.x * size.width
        val cy = normalizedPosition.y * size.height
        val r = 28.dp.toPx()
        val color = if (isLocked) Color(0xFF34D399) else Color(0xFFFFB300)
        val stroke = 1.8.dp.toPx()
        val corner = 9.dp.toPx()

        // Canon-style AF corner brackets
        // Top-left
        drawLine(color, Offset(cx - r, cy - r), Offset(cx - r + corner, cy - r), stroke)
        drawLine(color, Offset(cx - r, cy - r), Offset(cx - r, cy - r + corner), stroke)
        // Top-right
        drawLine(color, Offset(cx + r, cy - r), Offset(cx + r - corner, cy - r), stroke)
        drawLine(color, Offset(cx + r, cy - r), Offset(cx + r, cy - r + corner), stroke)
        // Bottom-left
        drawLine(color, Offset(cx - r, cy + r), Offset(cx - r + corner, cy + r), stroke)
        drawLine(color, Offset(cx - r, cy + r), Offset(cx - r, cy + r - corner), stroke)
        // Bottom-right
        drawLine(color, Offset(cx + r, cy + r), Offset(cx + r - corner, cy + r), stroke)
        drawLine(color, Offset(cx + r, cy + r), Offset(cx + r, cy + r - corner), stroke)

        drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
    }
}
