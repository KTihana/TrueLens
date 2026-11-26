package com.truelens.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

object TrueLensPalette {
    val Obsidian = Color(0xFF09090B)
    val SurfaceDark = Color(0xFF131318)
    val GlassPanel = Color(0x880D0D12)
    val GlassBorder = Color(0x2AFFFFFF)
    val AccentViolet = Color(0xFF8B5CF6)
    val AccentVioletSoft = Color(0x338B5CF6)
    val RecordCoral = Color(0xFFEF4444)
    val PureWhite = Color(0xFFFFFFFF)
    val MutedGray = Color(0xFFB4B9C4)
    val SubtleWhite = Color(0x22FFFFFF)
}

/**
 * Tactile precision tick-mark ruler slider (inspired by Leica / VSCO / modern camera UIs).
 * Supports smooth drag + tap-to-jump + double-tap-to-reset.
 */
@Composable
fun PrecisionTickRuler(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    accentColor: Color = TrueLensPalette.AccentViolet,
    defaultValue: Float = 0f,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val norm = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val zeroNorm = ((defaultValue - valueRange.start) / span).coerceIn(0f, 1f)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = valueLabel,
            color = if (abs(value - defaultValue) > span * 0.01f) accentColor else Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onValueChange(defaultValue) }
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(valueRange) {
                    detectTapGestures(
                        onDoubleTap = { onValueChange(defaultValue) },
                        onTap = { pos ->
                            val frac = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + frac * span)
                        }
                    )
                }
                .pointerInput(valueRange) {
                    detectHorizontalDragGestures { change, _ ->
                        val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + frac * span)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
                val w = size.width
                val cy = size.height / 2f
                val ticks = 41
                val activeX = norm * w
                val zeroX = zeroNorm * w

                for (i in 0 until ticks) {
                    val t = i.toFloat() / (ticks - 1)
                    val x = t * w
                    val isMajor = i % 5 == 0
                    val halfH = if (isMajor) 9.dp.toPx() else 5.dp.toPx()
                    val inRange = (x >= minOf(zeroX, activeX) && x <= maxOf(zeroX, activeX))
                    val tickColor = when {
                        inRange && abs(value - defaultValue) > span * 0.01f -> accentColor
                        isMajor -> Color.White.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.22f)
                    }
                    drawLine(
                        color = tickColor,
                        start = Offset(x, cy - halfH),
                        end = Offset(x, cy + halfH),
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Crisp vertical center needle at current value
                drawLine(
                    color = Color.White,
                    start = Offset(activeX, cy - 13.dp.toPx()),
                    end = Offset(activeX, cy + 13.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Clean minimal icon button for the top bar (no bulky border box unless active).
 */
@Composable
fun MinimalTopIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) TrueLensPalette.AccentVioletSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = if (badgeText != null) 8.dp else 9.dp, vertical = 9.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) TrueLensPalette.AccentViolet else Color.White,
            modifier = Modifier.size(21.dp)
        )
        if (badgeText != null) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = badgeText,
                color = if (active) TrueLensPalette.AccentViolet else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Single tile inside the 4-column floating frosted-glass Camera Tools grid.
 */
@Composable
fun GlassGridTile(
    icon: ImageVector,
    label: String,
    valueText: String? = null,
    active: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) TrueLensPalette.AccentVioletSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) TrueLensPalette.AccentViolet else Color.White,
            modifier = Modifier.size(21.dp)
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = label,
            color = if (active) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        if (valueText != null) {
            Text(
                text = valueText,
                color = if (active) TrueLensPalette.AccentViolet else TrueLensPalette.MutedGray,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * Sleek bottom icon+label selector item for Pro Manual & Lightroom Editor tool carousels.
 */
@Composable
fun ToolCarouselItem(
    icon: ImageVector,
    label: String,
    valueBadge: String? = null,
    selected: Boolean,
    hasAdjustment: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    ) {
        if (valueBadge != null && hasAdjustment) {
            Text(
                text = valueBadge,
                color = TrueLensPalette.AccentViolet,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    selected -> TrueLensPalette.AccentViolet
                    hasAdjustment -> Color.White
                    else -> Color.White.copy(alpha = 0.65f)
                },
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (selected) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
