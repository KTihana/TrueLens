package com.truelens.camera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truelens.camera.grading.GradePreset
import com.truelens.camera.grading.LightroomGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun DarkroomScreen(
    state: CameraUiState,
    onClose: () -> Unit,
    onSelectFilmstripUri: (Uri) -> Unit,
    onUpdateGrade: ((LightroomGrade) -> LightroomGrade) -> Unit,
    onUpdateKeystone: (vertical: Float, horizontal: Float, rotationDeg: Float) -> Unit,
    onApplyPreset: (GradePreset) -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    var showOriginal by remember { mutableStateOf(false) }
    val displayBitmap = if (showOriginal) state.darkroomSourceBitmap else state.darkroomGradedBitmap
    val photo = state.lastCapturedPhoto
    val recentShots by produceState<List<GalleryPhotoItem>>(initialValue = emptyList()) {
        value = queryTrueLensGallery(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08080A))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Darkroom Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Camera",
                        tint = Color.White
                    )
                }
                Column {
                    Text(
                        text = "DARKROOM",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (photo != null) {
                        Text(
                            text = "${photo.formatBadge} • f/${String.format(Locale.US, "%.1f", photo.aperture)} • ${formatShutterNs(photo.shutterSpeedNs)} • ISO ${photo.iso}",
                            color = Color(0xFFA0A4AE),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFFB300))
                    .clickable(enabled = !state.isExportingDarkroom) { onExport() }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                if (state.isExportingDarkroom) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(16.dp)
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export",
                            tint = Color.Black,
                            modifier = Modifier.height(15.dp)
                        )
                        Text(
                            text = " EXPORT",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Photo Preview (Hold to compare original)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = "Captured Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (showOriginal) "ORIGINAL" else "HOLD TO COMPARE",
                    color = if (showOriginal) Color(0xFFFFB300) else Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Quick Gallery Filmstrip (switch between recent shots without leaving Darkroom)
        if (recentShots.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(recentShots.take(20), key = { it.uri.toString() }) { item ->
                    val isCurrent = item.uri == (state.darkroomSourceUri ?: state.lastCapturedPhoto?.jpegUri)
                    val thumb by produceState<Bitmap?>(initialValue = null, item.uri) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 6 }
                                context.contentResolver.openInputStream(item.uri)?.use {
                                    BitmapFactory.decodeStream(it, null, opts)
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF14161C))
                            .border(
                                if (isCurrent) 2.dp else 0.75.dp,
                                if (isCurrent) Color(0xFFFFB300) else Color.White.copy(alpha = 0.16f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectFilmstripUri(item.uri) }
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb!!.asImageBitmap(),
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Optical Perspective / Keystone Straightener compact row (pure geometry, zero AI)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("V-TILT", color = Color(0xFFA0A4AE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = state.darkroomVerticalTilt,
                onValueChange = { onUpdateKeystone(it, state.darkroomHorizontalTilt, state.darkroomRotationDeg) },
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFFB300))
            )
            Text("ROT", color = Color(0xFFA0A4AE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = state.darkroomRotationDeg,
                onValueChange = { onUpdateKeystone(state.darkroomVerticalTilt, state.darkroomHorizontalTilt, it) },
                valueRange = -15f..15f,
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFFB300))
            )
        }

        // Full Lightroom Grading Deck
        LightroomLiveDeck(
            grade = state.darkroomGrade,
            activePresetId = "custom",
            customPresets = state.customPresets,
            onUpdateGrade = onUpdateGrade,
            onSelectPreset = onApplyPreset,
            onSaveCustomPreset = onSaveCustomPreset,
            onDeleteCustomPreset = onDeleteCustomPreset,
            onReset = { onUpdateGrade { LightroomGrade.NEUTRAL } }
        )
    }
}
