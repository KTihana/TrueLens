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
    frameScale: Pair<Float, Float> = 1f to 1f,
    modifier: Modifier = Modifier
) {
    val targetRatio = mask.widthOverHeight ?: return
    Canvas(modifier = modifier.fillMaxSize()) {
        val frameW = size.width * frameScale.first.coerceIn(0.1f, 1f)
        val frameH = size.height * frameScale.second.coerceIn(0.1f, 1f)
        val left = (size.width - frameW) / 2f
        val top = (size.height - frameH) / 2f
        val frameRatio = frameW / frameH
        val matteColor = Color.Black.copy(alpha = 0.68f)
        val borderColor = Color.White.copy(alpha = 0.45f)
        val stroke = 1.dp.toPx()

        if (targetRatio > frameRatio) {
            // Letterbox top & bottom inside active camera frame
            val cropH = frameW / targetRatio
            val barH = ((frameH - cropH) / 2f).coerceAtLeast(0f)
            drawRect(matteColor, Offset(left, top), Size(frameW, barH))
            drawRect(matteColor, Offset(left, top + frameH - barH), Size(frameW, barH))
            drawLine(borderColor, Offset(left, top + barH), Offset(left + frameW, top + barH), stroke)
            drawLine(borderColor, Offset(left, top + frameH - barH), Offset(left + frameW, top + frameH - barH), stroke)
        } else {
            // Pillarbox left & right inside active camera frame
            val cropW = frameH * targetRatio
            val barW = ((frameW - cropW) / 2f).coerceAtLeast(0f)
            drawRect(matteColor, Offset(left, top), Size(barW, frameH))
            drawRect(matteColor, Offset(left + frameW - barW, top), Size(barW, frameH))
            drawLine(borderColor, Offset(left + barW, top), Offset(left + barW, top + frameH), stroke)
            drawLine(borderColor, Offset(left + frameW - barW, top), Offset(left + frameW - barW, top + frameH), stroke)
        }
    }
}

@Composable
fun CompositionGridOverlay(
    grid: GridOverlay,
    frameScale: Pair<Float, Float> = 1f to 1f,
    modifier: Modifier = Modifier
) {
    if (grid == GridOverlay.NONE) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val frameW = size.width * frameScale.first.coerceIn(0.1f, 1f)
        val frameH = size.height * frameScale.second.coerceIn(0.1f, 1f)
        val left = (size.width - frameW) / 2f
        val top = (size.height - frameH) / 2f
        val lineColor = Color.White.copy(alpha = 0.28f)
        val stroke = 0.9.dp.toPx()
        when (grid) {
            GridOverlay.RULE_OF_THIRDS -> {
                for (i in 1..2) {
                    val x = left + frameW * i / 3f
                    val y = top + frameH * i / 3f
                    drawLine(lineColor, Offset(x, top), Offset(x, top + frameH), stroke)
                    drawLine(lineColor, Offset(left, y), Offset(left + frameW, y), stroke)
                }
            }
            GridOverlay.GOLDEN_RATIO -> {
                val r1 = 0.382f
                val r2 = 0.618f
                drawLine(lineColor, Offset(left + frameW * r1, top), Offset(left + frameW * r1, top + frameH), stroke)
                drawLine(lineColor, Offset(left + frameW * r2, top), Offset(left + frameW * r2, top + frameH), stroke)
                drawLine(lineColor, Offset(left, top + frameH * r1), Offset(left + frameW, top + frameH * r1), stroke)
                drawLine(lineColor, Offset(left, top + frameH * r2), Offset(left + frameW, top + frameH * r2), stroke)
            }
            GridOverlay.CROSSHAIR -> {
                val cx = left + frameW / 2f
                val cy = top + frameH / 2f
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
    isFlatLay: Boolean = false,
    flatTiltX: Float = 0f,
    flatTiltY: Float = 0f,
    modifier: Modifier = Modifier
) {
    if (isFlatLay) {
        // Feature #6: Top-Down Flat-Lay Dual Crosshair Level (food, desk flat-lays, documents)
        val dist = kotlin.math.hypot(flatTiltX.toDouble(), flatTiltY.toDouble()).toFloat()
        val isLevel = dist < 0.35f
        val activeColor = if (isLevel) Color(0xFF34D399) else Color(0xFFFFD60A)
        Canvas(modifier = modifier.width(120.dp).height(120.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val arm = 18.dp.toPx()
            val stroke = 2.dp.toPx()

            // Fixed center reference crosshair
            val refColor = if (isLevel) Color(0xFF34D399) else Color.White.copy(alpha = 0.72f)
            drawLine(refColor, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke)
            drawLine(refColor, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke)

            // Dynamic tilt crosshair (moves toward center and locks green when perfectly flat!)
            val maxShift = 38.dp.toPx()
            val ox = (flatTiltX * 10.dp.toPx()).coerceIn(-maxShift, maxShift)
            val oy = (flatTiltY * 10.dp.toPx()).coerceIn(-maxShift, maxShift)
            val dx = if (isLevel) cx else cx + ox
            val dy = if (isLevel) cy else cy + oy
            val dynArm = 14.dp.toPx()
            drawLine(activeColor, Offset(dx - dynArm, dy), Offset(dx + dynArm, dy), stroke)
            drawLine(activeColor, Offset(dx, dy - dynArm), Offset(dx, dy + dynArm), stroke)
            if (isLevel) {
                drawCircle(
                    color = Color(0xFF34D399).copy(alpha = 0.85f),
                    radius = 24.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
        return
    }

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

/**
 * iPhone-style 4 animated golden corner brackets locked directly onto a detected QR code in the viewfinder.
 */
@Composable
fun QrBracketsOverlay(
    qr: com.truelens.camera.modes.DetectedQrCode?,
    modifier: Modifier = Modifier
) {
    val box = qr?.normalizedBox ?: return
    Canvas(modifier = modifier.fillMaxSize()) {
        val left = (box.left * size.width).coerceIn(0f, size.width)
        val top = (box.top * size.height).coerceIn(0f, size.height)
        val right = (box.right * size.width).coerceIn(left + 12f, size.width)
        val bottom = (box.bottom * size.height).coerceIn(top + 12f, size.height)
        val bw = right - left
        val bh = bottom - top
        val corner = (minOf(bw, bh) * 0.24f).coerceIn(14.dp.toPx(), 32.dp.toPx())
        val stroke = 3.dp.toPx()
        val col = Color(0xFFFFD60A)

        // Top-left
        drawLine(col, Offset(left, top), Offset(left + corner, top), stroke)
        drawLine(col, Offset(left, top), Offset(left, top + corner), stroke)
        // Top-right
        drawLine(col, Offset(right, top), Offset(right - corner, top), stroke)
        drawLine(col, Offset(right, top), Offset(right, top + corner), stroke)
        // Bottom-left
        drawLine(col, Offset(left, bottom), Offset(left + corner, bottom), stroke)
        drawLine(col, Offset(left, bottom), Offset(left, bottom - corner), stroke)
        // Bottom-right
        drawLine(col, Offset(right, bottom), Offset(right - corner, bottom), stroke)
        drawLine(col, Offset(right, bottom), Offset(right, bottom - corner), stroke)
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
        val color = if (isLocked) Color(0xFF34D399) else TrueLensPalette.AccentViolet
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
