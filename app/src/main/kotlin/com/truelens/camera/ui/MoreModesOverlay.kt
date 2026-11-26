package com.truelens.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truelens.camera.camera.LensInfo
import com.truelens.camera.camera.ShootingMode
import kotlin.math.abs

/**
 * Floating translucent smoked zoom / lens switcher capsule (0.6 | 1x | 2 | 3)
 * modeled directly on the user's reference screenshot.
 */
@Composable
fun FloatingLensZoomCapsule(
    activeLens: LensInfo?,
    zoomRatio: Float,
    hasUltrawide: Boolean,
    onSelectStop: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeLens?.isFront == true) return

    // Effective displayed zoom combining physical lens focal multiplier + digital zoom
    val effectiveZoom = when {
        activeLens?.displayLabel?.startsWith("0.") == true -> 0.6f * zoomRatio
        activeLens?.displayLabel == "2x" -> 2.0f * zoomRatio
        activeLens?.displayLabel == "3x" -> 3.0f * zoomRatio
        activeLens?.displayLabel == "5x" -> 5.0f * zoomRatio
        else -> zoomRatio
    }

    val stops = if (hasUltrawide) listOf(0.6f, 1.0f, 2.0f, 3.0f) else listOf(0.6f, 1.0f, 2.0f, 3.0f)
    val closestStop = stops.minByOrNull { abs(it - effectiveZoom) } ?: 1.0f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x5E000000))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stops.forEach { stop ->
            val selected = stop == closestStop
            val label = when {
                selected -> {
                    if (abs(effectiveZoom - stop) > 0.15f) String.format("%.1fx", effectiveZoom)
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
                    .clickable { onSelectStop(stop) },
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

/**
 * Airy, translucent full-viewfinder "MORE" mode launcher (matching the user's reference image #1).
 * Keeps the main bottom mode bar ultra-clean (PHOTO | VIDEO | DUAL | MORE).
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
            .background(Color(0xA608080B))
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: PRO, SLOW-MO, PANORAMA
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
                    icon = Icons.Default.SlowMotionVideo,
                    label = "SLOW-MO",
                    active = activeMode == ShootingMode.SLOW_MO,
                    onClick = { onSelectMode(ShootingMode.SLOW_MO) },
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

            // Row 2: LONG EXPOSURE, TIME-LAPSE, STOP MOTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MoreModeTile(
                    icon = Icons.Default.BlurOn,
                    label = "LONG EXPOSURE",
                    active = activeMode == ShootingMode.LONG_EXP,
                    onClick = { onSelectMode(ShootingMode.LONG_EXP) },
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
            }

            // Row 3: SUPER MACRO, SCAN DOCUMENT, FRIEND MODE
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MoreModeTile(
                    icon = Icons.Default.FilterVintage,
                    label = "SUPER MACRO",
                    active = activeMode == ShootingMode.SUPER_MACRO,
                    onClick = { onSelectMode(ShootingMode.SUPER_MACRO) },
                    modifier = Modifier.weight(1f)
                )
                MoreModeTile(
                    icon = Icons.Default.DocumentScanner,
                    label = "SCAN DOCUMENT",
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
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(30.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = label,
            color = if (active) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.90f),
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 0.5.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
