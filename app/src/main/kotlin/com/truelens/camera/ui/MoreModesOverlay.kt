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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truelens.camera.camera.LensInfo
import com.truelens.camera.camera.ShootingMode
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

/**
 * Floating translucent smoked zoom / lens switcher capsule (0.6 | 1x | 2 | 3)
 * with iPhone-style Smooth Zoom Wheel / Dial when dragged horizontally!
 */
@Composable
fun FloatingLensZoomCapsule(
    activeLens: LensInfo?,
    zoomRatio: Float,
    hasUltrawide: Boolean,
    onSelectStop: (Float) -> Unit,
    onExactZoom: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (activeLens?.isFront == true) return

    val effectiveZoom = when {
        activeLens?.displayLabel?.startsWith("0.") == true -> 0.6f * zoomRatio
        activeLens?.displayLabel == "2x" -> 2.0f * zoomRatio
        activeLens?.displayLabel == "3x" -> 3.0f * zoomRatio
        activeLens?.displayLabel == "5x" -> 5.0f * zoomRatio
        else -> zoomRatio
    }

    val supportsUltrawide = hasUltrawide || (activeLens?.minZoomRatio ?: 1.0f) < 0.92f
    val minDialZoom = if (supportsUltrawide) 0.6f else 1.0f
    val maxDialZoom = (activeLens?.maxZoomRatio ?: 8.0f).coerceIn(3.0f, 10.0f)
    val stops = if (supportsUltrawide) listOf(0.6f, 1.0f, 2.0f, 3.0f) else listOf(1.0f, 2.0f, 3.0f)
    val closestStop = stops.minByOrNull { abs(it - effectiveZoom) } ?: 1.0f

    var showSmoothDial by remember { mutableStateOf(false) }
    var dialZoom by remember(effectiveZoom) { mutableFloatStateOf(effectiveZoom) }
    var lastDialInteractionMs by remember { mutableStateOf(0L) }

    LaunchedEffect(showSmoothDial, lastDialInteractionMs) {
        if (showSmoothDial) {
            delay(1800)
            if (System.currentTimeMillis() - lastDialInteractionMs >= 1700L) {
                showSmoothDial = false
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Feature #4: Expandable Tactile Smooth Zoom Wheel / Dial
        if (showSmoothDial) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .width(236.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xBB0C0D12))
                    .border(0.75.dp, TrueLensPalette.AccentViolet.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                    .pointerInput(minDialZoom, maxDialZoom) {
                        detectHorizontalDragGestures(
                            onDragStart = { lastDialInteractionMs = System.currentTimeMillis() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                lastDialInteractionMs = System.currentTimeMillis()
                                val delta = (-dragAmount / 130f) * (1f + dialZoom * 0.45f)
                                val next = (dialZoom + delta).coerceIn(minDialZoom, maxDialZoom)
                                dialZoom = next
                                onExactZoom(next)
                            }
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fx", dialZoom),
                    color = TrueLensPalette.AccentViolet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                    val tickCount = 31
                    val centerX = size.width / 2f
                    val stepPx = size.width / tickCount
                    val fractionalOffset = ((dialZoom * 5f) % 1f) * stepPx
                    for (i in 0..tickCount) {
                        val x = i * stepPx - fractionalOffset
                        if (x < 0f || x > size.width) continue
                        val distNorm = abs(x - centerX) / (size.width / 2f)
                        val alpha = (1f - distNorm * 0.85f).coerceIn(0.12f, 0.9f)
                        val isMajor = i % 5 == 0
                        val h = if (isMajor) size.height * 0.82f else size.height * 0.46f
                        drawLine(
                            color = Color.White.copy(alpha = alpha),
                            start = Offset(x, size.height - h),
                            end = Offset(x, size.height),
                            strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
                        )
                    }
                    // Center active needle
                    drawLine(
                        color = TrueLensPalette.AccentViolet,
                        start = Offset(centerX, 0f),
                        end = Offset(centerX, size.height),
                        strokeWidth = 2.2.dp.toPx()
                    )
                }
            }
        }

        // Standard compact capsule — tap for instant stop, drag horizontally to open Smooth Zoom Dial!
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x5E000000))
                .pointerInput(minDialZoom, maxDialZoom) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dialZoom = effectiveZoom
                            showSmoothDial = true
                            lastDialInteractionMs = System.currentTimeMillis()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            lastDialInteractionMs = System.currentTimeMillis()
                            val delta = (-dragAmount / 130f) * (1f + dialZoom * 0.45f)
                            val next = (dialZoom + delta).coerceIn(minDialZoom, maxDialZoom)
                            dialZoom = next
                            onExactZoom(next)
                        }
                    )
                }
                .padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            stops.forEach { stop ->
                val selected = stop == closestStop
                val label = when {
                    selected -> {
                        if (abs(effectiveZoom - stop) > 0.15f) String.format(Locale.US, "%.1fx", effectiveZoom)
                        else if (stop == 0.6f) "0.6x" else "${stop.toInt()}x"
                    }
                    stop == 0.6f -> "0.6"
                    else -> "${stop.toInt()}"
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color(0x99000000) else Color.Transparent)
                        .clickable {
                            showSmoothDial = false
                            onSelectStop(stop)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.92f),
                        fontSize = if (selected) 11.5.sp else 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Airy, translucent full-viewfinder "MORE" mode launcher (including Portrait & Cinematic!).
 */
@Composable
fun MoreModesOverlay(
    activeMode: ShootingMode,
    isProDeckOpen: Boolean,
    onSelectPro: () -> Unit,
    onSelectMode: (ShootingMode) -> Unit,
    onSelectFriendMode: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xAE08080B))
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: PRO, ASTRO SKY, PORTRAIT, CINEMATIC
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MoreModeTile(
                    icon = Icons.Default.Tune,
                    label = "PRO",
                    active = isProDeckOpen,
                    onClick = { onSelectPro() },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.NightsStay,
                    label = "ASTRO SKY",
                    active = activeMode == ShootingMode.ASTRO,
                    onClick = { onSelectMode(ShootingMode.ASTRO) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.Face,
                    label = "PORTRAIT",
                    active = activeMode == ShootingMode.PORTRAIT,
                    onClick = { onSelectMode(ShootingMode.PORTRAIT) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.Theaters,
                    label = "CINEMATIC",
                    active = activeMode == ShootingMode.CINEMATIC,
                    onClick = { onSelectMode(ShootingMode.CINEMATIC) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2: DOUBLE EXP, ACTION PAN, LONG EXP, PANORAMA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MoreModeTile(
                    icon = Icons.Default.Layers,
                    label = "DOUBLE EXP",
                    active = activeMode == ShootingMode.DOUBLE_EXP,
                    onClick = { onSelectMode(ShootingMode.DOUBLE_EXP) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.Speed,
                    label = "ACTION PAN",
                    active = activeMode == ShootingMode.ACTION_PAN,
                    onClick = { onSelectMode(ShootingMode.ACTION_PAN) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.BlurOn,
                    label = "LONG EXPOSURE",
                    active = activeMode == ShootingMode.LONG_EXP,
                    onClick = { onSelectMode(ShootingMode.LONG_EXP) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.Panorama,
                    label = "PANORAMA",
                    active = activeMode == ShootingMode.PANORAMA,
                    onClick = { onSelectMode(ShootingMode.PANORAMA) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 3: SLOW-MO, TIME-LAPSE, STOP MOTION, SUPER MACRO
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MoreModeTile(
                    icon = Icons.Default.SlowMotionVideo,
                    label = "SLOW-MO",
                    active = activeMode == ShootingMode.SLOW_MO,
                    onClick = { onSelectMode(ShootingMode.SLOW_MO) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.Timelapse,
                    label = "TIME-LAPSE",
                    active = activeMode == ShootingMode.TIMELAPSE,
                    onClick = { onSelectMode(ShootingMode.TIMELAPSE) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.MovieCreation,
                    label = "STOP MOTION",
                    active = activeMode == ShootingMode.STOP_MO,
                    onClick = { onSelectMode(ShootingMode.STOP_MO) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.FilterVintage,
                    label = "SUPER MACRO",
                    active = activeMode == ShootingMode.SUPER_MACRO,
                    onClick = { onSelectMode(ShootingMode.SUPER_MACRO) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 4: SCAN DOCUMENT, FRIEND MODE
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MoreModeTile(
                    icon = Icons.Default.DocumentScanner,
                    label = "SCAN DOC",
                    active = activeMode == ShootingMode.SCAN_DOC,
                    onClick = { onSelectMode(ShootingMode.SCAN_DOC) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.AccessibilityNew,
                    label = "FRIEND MODE",
                    active = false,
                    onClick = { onSelectFriendMode() },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MoreModeTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = if (active) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.90f),
            fontSize = 10.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 0.5.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
