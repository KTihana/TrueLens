package com.truelens.camera.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.truelens.camera.pose.PoseBlendMode
import com.truelens.camera.pose.PoseOverlayState

private val OverlayAmber = TrueLensPalette.AccentViolet
private val OverlayCyan = Color(0xFFFFFFFF)

/**
 * Interactive full-screen pose guide layer rendered over the live camera preview.
 * Supports two-finger pinch-to-zoom, drag-to-move, and rotate for BOTH full photos
 * and Subject Cutouts/Outlines. When locked, all touch input passes through to the camera.
 */
@Composable
fun PoseOverlayCanvas(
    poseState: PoseOverlayState,
    onTransform: (panX: Float, panY: Float, zoomDelta: Float, rotationDelta: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val bmp = poseState.currentBitmap ?: return
    if (poseState.isHidden) return

    val gestureModifier = if (!poseState.isLocked) {
        Modifier.pointerInput(poseState.activeItem?.id, poseState.isLocked) {
            detectTransformGestures { _, pan, zoom, rotation ->
                onTransform(pan.x, pan.y, zoom, rotation)
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Pose Overlay Guide",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = poseState.opacity.coerceIn(0f, 1f)
                    scaleX = poseState.scale * if (poseState.isMirrored) -1f else 1f
                    scaleY = poseState.scale
                    translationX = poseState.offsetX
                    translationY = poseState.offsetY
                    rotationZ = poseState.rotationDegrees
                }
        )

        // Subtle dashed alignment frame when unlocked so the user knows pinch/drag is active
        if (!poseState.isLocked) {
            Canvas(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                drawRect(
                    color = Color.White.copy(alpha = 0.45f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)
                    )
                )
            }
        }
    }
}

/**
 * Compact floating Pose Overlay HUD bar (Opacity slider, Ghost / Cutout / Outline toggle,
 * Mirror, Lock/Unlock, Reset Transform, Hide/Show, Close).
 */
@Composable
fun PoseOverlayControlBar(
    poseState: PoseOverlayState,
    onOpacityChange: (Float) -> Unit,
    onBlendModeChange: (PoseBlendMode) -> Unit,
    onToggleMirror: () -> Unit,
    onToggleLock: () -> Unit,
    onResetTransform: () -> Unit,
    onToggleHide: () -> Unit,
    onClearPose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC0E1014))
            .border(0.75.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        // Row 1: Opacity Slider + Percentage + Quick Hide & Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "POSE",
                color = OverlayAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Slider(
                value = poseState.opacity,
                onValueChange = onOpacityChange,
                valueRange = 0.05f..1.0f,
                modifier = Modifier.weight(1f).height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = OverlayAmber,
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )
            Text(
                text = "${(poseState.opacity * 100).toInt()}%",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(34.dp)
            )
            PoseMiniIcon(
                icon = if (poseState.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                active = !poseState.isHidden,
                onClick = onToggleHide
            )
            Spacer(modifier = Modifier.width(4.dp))
            PoseMiniIcon(
                icon = Icons.Default.Close,
                active = false,
                onClick = onClearPose
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        // Row 2: Blend Modes (Ghost / Cutout / Outline) + Mirror / Lock / Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PoseBlendMode.entries.forEach { mode ->
                    val selected = poseState.blendMode == mode
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (selected) OverlayAmber else Color(0x1EFFFFFF))
                            .clickable { onBlendModeChange(mode) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (mode == PoseBlendMode.SUBJECT_CUTOUT && poseState.isProcessingCutout) {
                            CircularProgressIndicator(
                                color = if (selected) Color.Black else OverlayAmber,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = mode.label,
                            color = if (selected) Color.Black else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PoseMiniIcon(
                    icon = Icons.Default.Flip,
                    active = poseState.isMirrored,
                    onClick = onToggleMirror
                )
                PoseMiniIcon(
                    icon = Icons.Default.Refresh,
                    active = false,
                    onClick = onResetTransform
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (poseState.isLocked) OverlayCyan.copy(alpha = 0.25f) else Color(0x1EFFFFFF))
                        .border(
                            0.75.dp,
                            if (poseState.isLocked) OverlayCyan else Color.Transparent,
                            RoundedCornerShape(7.dp)
                        )
                        .clickable { onToggleLock() }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (poseState.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Lock Pose Overlay",
                        tint = if (poseState.isLocked) OverlayCyan else Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (poseState.isLocked) "LOCKED" else "MOVE",
                        color = if (poseState.isLocked) OverlayCyan else Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun PoseMiniIcon(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) OverlayAmber.copy(alpha = 0.24f) else Color(0x1EFFFFFF))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) OverlayAmber else Color.White,
            modifier = Modifier.size(15.dp)
        )
    }
}
