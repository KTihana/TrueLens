package com.truelens.camera.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropRotate
import android.widget.VideoView
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truelens.camera.camera.ExifBitmapLoader
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.BuiltInPresets
import com.truelens.camera.grading.GradePreset
import com.truelens.camera.grading.LightroomGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class DarkroomTab(val label: String, val icon: ImageVector) {
    PRESETS("Filters", Icons.Default.AutoAwesome),
    ADJUST("Adjust", Icons.Default.Tune),
    FRAME("Frame", Icons.Default.CropFree),
    STRAIGHTEN("Straighten", Icons.Default.CropRotate)
}

/**
 * Editorial Darkroom Editor inspired by Reference Image #2:
 * Clean white "Export" pill, rounded photo card, live graded photo-thumbnail preset strip,
 * single PrecisionTickRuler adjust bar, and pure-geometry Straighten controls.
 */
@Composable
fun DarkroomScreen(
    state: CameraUiState,
    onClose: () -> Unit,
    onSelectFilmstripUri: (Uri) -> Unit,
    onUpdateGrade: ((LightroomGrade) -> LightroomGrade) -> Unit,
    onUpdateKeystone: (vertical: Float, horizontal: Float, rotationDeg: Float) -> Unit,
    onSelectFrameStyle: (com.truelens.camera.grading.ExportFrameStyle) -> Unit,
    onSelectDateStamp: (com.truelens.camera.grading.DateStampStyle) -> Unit,
    onApplyPreset: (GradePreset) -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeletePhoto: () -> Unit,
    onSaveKeyPhoto: (Bitmap) -> Unit = {},
    onExportLiveVideo: () -> Unit = {},
    onExport: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var showOriginal by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showExifDialog by remember { mutableStateOf(false) }
    var favoriteSet by remember(state.darkroomSourceUri) { mutableStateOf(GalleryFavoritesStore.load(context)) }
    var activeTab by remember { mutableStateOf(DarkroomTab.PRESETS) }
    var selectedPresetId by remember(state.darkroomSourceUri) { mutableStateOf("true_zero") }
    var straightenAxis by remember { mutableStateOf(0) } // 0 = Rotate, 1 = Vertical Tilt, 2 = Horizontal Tilt
    var isPlayingLiveMotion by remember(state.darkroomSourceUri) { mutableStateOf(false) }
    var showKeyPhotoPicker by remember(state.darkroomSourceUri) { mutableStateOf(false) }
    var selectedKeyFrameIdx by remember(state.darkroomSourceUri) { mutableStateOf(0) }
    val liveKeyFrames by produceState<List<Pair<Long, Bitmap>>>(
        initialValue = emptyList(),
        state.darkroomLiveVideoFile,
        showKeyPhotoPicker
    ) {
        val f = state.darkroomLiveVideoFile
        value = if (showKeyPhotoPicker && f != null) {
            com.truelens.camera.modes.LivePhotoController.extractKeyPhotoFrames(f, maxFrames = 12)
        } else {
            emptyList()
        }
    }

    val displayBitmap = if (showOriginal) state.darkroomSourceBitmap else state.darkroomGradedBitmap
    val recentShots by produceState<List<GalleryPhotoItem>>(initialValue = emptyList(), state.darkroomSourceUri) {
        value = queryTrueLensGallery(context, includeVideos = false)
    }

    // Small base thumbnail for fast real-time graded preset preview tiles
    val tinyBaseBitmap by produceState<Bitmap?>(initialValue = null, state.darkroomSourceBitmap) {
        value = withContext(Dispatchers.Default) {
            val src = state.darkroomSourceBitmap ?: return@withContext null
            Bitmap.createScaledBitmap(src, 140, 140, true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrueLensPalette.Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Bar: Circular Back + Undo/Redo + Share/Delete + White "Export" Pill (matching Reference Image #2!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x1EFFFFFF))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF))
                        .clickable(enabled = state.canUndoDarkroom) { onUndo() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (state.canUndoDarkroom) Color.White else Color.White.copy(alpha = 0.28f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF))
                        .clickable(enabled = state.canRedoDarkroom) { onRedo() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (state.canRedoDarkroom) Color.White else Color.White.copy(alpha = 0.28f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeUri = state.darkroomSourceUri ?: state.lastCapturedPhoto?.jpegUri
                if (activeUri != null) {
                    val isFav = activeUri.toString() in favoriteSet
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isFav) TrueLensPalette.RecordCoral.copy(alpha = 0.25f) else Color(0x14FFFFFF))
                            .clickable {
                                favoriteSet = GalleryFavoritesStore.toggle(context, activeUri)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite Photo",
                            tint = if (isFav) TrueLensPalette.RecordCoral else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .clickable { showExifDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Photo EXIF Info",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .clickable {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, activeUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Photo"))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Photo",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .clickable { showDeleteConfirm = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Photo",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .clickable(enabled = !state.isExportingDarkroom) { onExport() }
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isExportingDarkroom) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = "Export",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Rounded Photo Card (matching Reference Image #2)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TrueLensPalette.SurfaceDark),
            contentAlignment = Alignment.Center
        ) {
            val activeKeyFrame = if (showKeyPhotoPicker && liveKeyFrames.isNotEmpty()) {
                liveKeyFrames[selectedKeyFrameIdx.coerceIn(0, liveKeyFrames.lastIndex)].second
            } else null

            if (isPlayingLiveMotion && state.darkroomLiveVideoFile != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(state.darkroomLiveVideoFile.absolutePath)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                            setOnClickListener { isPlayingLiveMotion = false }
                        }
                    },
                    onRelease = { vv ->
                        try { vv.stopPlayback() } catch (_: Exception) {}
                    }
                )
            } else if (activeKeyFrame != null) {
                Image(
                    bitmap = activeKeyFrame.asImageBitmap(),
                    contentDescription = "Selected Key Frame",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = "Photo Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // LIVE PHOTO Badge + Replay + Key Photo Picker Bar (when photo has a 3s Live Motion clip!)
            if (state.darkroomLiveVideoFile != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isPlayingLiveMotion) Color(0xFFFFB300) else Color(0xCC121218))
                            .border(0.75.dp, Color(0xFFFFB300).copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                            .clickable {
                                showKeyPhotoPicker = false
                                isPlayingLiveMotion = !isPlayingLiveMotion
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MotionPhotosOn,
                            contentDescription = "Play Live Photo",
                            tint = if (isPlayingLiveMotion) Color.Black else Color(0xFFFFB300),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPlayingLiveMotion) "STOP LIVE" else "LIVE • PLAY",
                            color = if (isPlayingLiveMotion) Color.Black else Color(0xFFFFB300),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (showKeyPhotoPicker) TrueLensPalette.AccentViolet else Color(0xCC121218))
                            .border(0.75.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                            .clickable {
                                isPlayingLiveMotion = false
                                showKeyPhotoPicker = !showKeyPhotoPicker
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "Key Photo",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xCC121218))
                            .border(0.75.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                            .clickable { onExportLiveVideo() }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = "Save Video",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "MP4",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Key Photo Scrubber Overlay at Bottom of Card when picking the sharpest Live frame!
            if (showKeyPhotoPicker && liveKeyFrames.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xDD101118))
                        .border(0.75.dp, TrueLensPalette.AccentViolet.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pick Sharpest Key Photo (${selectedKeyFrameIdx + 1}/${liveKeyFrames.size})",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .clickable {
                                    val chosen = liveKeyFrames[selectedKeyFrameIdx.coerceIn(0, liveKeyFrames.lastIndex)].second
                                    showKeyPhotoPicker = false
                                    onSaveKeyPhoto(chosen)
                                }
                                .padding(horizontal = 11.dp, vertical = 5.dp)
                        ) {
                            Text("Save Key Photo", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(liveKeyFrames.indices.toList()) { idx ->
                            val isSel = idx == selectedKeyFrameIdx
                            Image(
                                bitmap = liveKeyFrames[idx].second.asImageBitmap(),
                                contentDescription = "Key Frame $idx",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (isSel) 2.dp else 0.75.dp,
                                        color = if (isSel) Color(0xFFFFB300) else Color.White.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedKeyFrameIdx = idx }
                            )
                        }
                    }
                }
            }

            // Floating Before/After Compare Button at bottom-right of photo (hidden during Live playback / Key Photo scrubber)
            if (!showKeyPhotoPicker && !isPlayingLiveMotion) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (showOriginal) TrueLensPalette.AccentViolet else Color(0xAA141418))
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
                    Icon(
                        imageVector = Icons.Default.Compare,
                        contentDescription = "Hold to Compare Original",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Gallery Filmstrip (compact pill thumbs)
        if (recentShots.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(recentShots.take(16), key = { it.uri.toString() }) { item ->
                    val isCurrent = item.uri == (state.darkroomSourceUri ?: state.lastCapturedPhoto?.jpegUri)
                    val thumb by produceState<Bitmap?>(initialValue = null, item.uri) {
                        value = withContext(Dispatchers.IO) {
                            ExifBitmapLoader.loadScaled(context, item.uri, 160)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TrueLensPalette.SurfaceDark)
                            .border(
                                if (isCurrent) 2.dp else 0.75.dp,
                                if (isCurrent) Color.White else TrueLensPalette.GlassBorder,
                                RoundedCornerShape(10.dp)
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
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Active Editor Panel (Filters Photo Cards / Adjust Tick-Ruler / Straighten Tick-Ruler)
        when (activeTab) {
            DarkroomTab.PRESETS -> {
                val allPresets = state.customPresets + BuiltInPresets.ALL
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(98.dp)
                ) {
                    items(allPresets, key = { it.id }) { preset ->
                        val selected = preset.id == selectedPresetId
                        val previewBmp by produceState<Bitmap?>(initialValue = null, tinyBaseBitmap, preset.id) {
                            value = withContext(Dispatchers.Default) {
                                val base = tinyBaseBitmap ?: return@withContext null
                                BitmapGrader.applyGrade(base, preset.grade)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(78.dp)
                                .height(92.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(TrueLensPalette.SurfaceDark)
                                .border(
                                    width = if (selected) 2.dp else 0.75.dp,
                                    color = if (selected) Color.White else TrueLensPalette.GlassBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    selectedPresetId = preset.id
                                    onApplyPreset(preset)
                                }
                        ) {
                            if (previewBmp != null) {
                                Image(
                                    bitmap = previewBmp!!.asImageBitmap(),
                                    contentDescription = preset.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                        )
                                    )
                                    .padding(horizontal = 4.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val shortLabel = when {
                                    preset.id == "true_zero" -> "Original"
                                    preset.id.contains("portra") -> "Portra"
                                    preset.id.contains("cuban") -> "Cuban Neg"
                                    preset.id.contains("gold") -> "Kodak Gold"
                                    preset.id.contains("cinestill") -> "CineStill"
                                    preset.id.contains("chrome") -> "Chrome"
                                    preset.id.contains("editorial") -> "Editorial"
                                    preset.id.contains("academia") -> "Academia"
                                    preset.id.contains("hp5") -> "HP5 B&W"
                                    preset.id == "halation_800t" -> "800T Halo"
                                    preset.id == "leak_warm" -> "Warm Leak"
                                    preset.id == "leak_amber" -> "Amber '89"
                                    preset.id == "leak_rose" -> "Rose Prism"
                                    preset.id == "leak_sunset" -> "Sunset"
                                    preset.id == "vhs_94" -> "VHS '94"
                                    else -> preset.name.take(10)
                                }
                                Text(
                                    text = shortLabel,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            DarkroomTab.ADJUST -> {
                LightroomLiveDeck(
                    grade = state.darkroomGrade,
                    activePresetId = selectedPresetId,
                    customPresets = state.customPresets,
                    showModeTabs = false,
                    onUpdateGrade = onUpdateGrade,
                    onSelectPreset = {
                        selectedPresetId = it.id
                        onApplyPreset(it)
                    },
                    onSaveCustomPreset = onSaveCustomPreset,
                    onDeleteCustomPreset = onDeleteCustomPreset,
                    onReset = {
                        selectedPresetId = "true_zero"
                        onUpdateGrade { LightroomGrade.NEUTRAL }
                    }
                )
            }
            DarkroomTab.FRAME -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(TrueLensPalette.GlassPanel)
                        .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(20.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Border & Specs",
                            color = TrueLensPalette.MutedGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            com.truelens.camera.grading.ExportFrameStyle.entries.forEach { style ->
                                val sel = state.darkroomFrameStyle == style
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) Color.White else Color(0x1EFFFFFF))
                                        .clickable { onSelectFrameStyle(style) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = style.label,
                                        color = if (sel) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Date Stamp",
                            color = TrueLensPalette.MutedGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            com.truelens.camera.grading.DateStampStyle.entries.forEach { stamp ->
                                val sel = state.darkroomDateStamp == stamp
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                sel && stamp == com.truelens.camera.grading.DateStampStyle.ANALOG_98 -> Color(0xFFFF7024)
                                                sel -> TrueLensPalette.AccentViolet
                                                else -> Color(0x1EFFFFFF)
                                            }
                                        )
                                        .clickable { onSelectDateStamp(stamp) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = stamp.label,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
            DarkroomTab.STRAIGHTEN -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(TrueLensPalette.GlassPanel)
                        .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(20.dp))
                        .padding(14.dp)
                ) {
                    when (straightenAxis) {
                        0 -> PrecisionTickRuler(
                            value = state.darkroomRotationDeg,
                            valueRange = -15f..15f,
                            valueLabel = String.format(Locale.US, "Rotate  %+.1f°", state.darkroomRotationDeg),
                            defaultValue = 0f,
                            onValueChange = { onUpdateKeystone(state.darkroomVerticalTilt, state.darkroomHorizontalTilt, it) }
                        )
                        1 -> PrecisionTickRuler(
                            value = state.darkroomVerticalTilt,
                            valueRange = -1f..1f,
                            valueLabel = String.format(Locale.US, "Vertical Tilt  %+d", (state.darkroomVerticalTilt * 100).toInt()),
                            defaultValue = 0f,
                            onValueChange = { onUpdateKeystone(it, state.darkroomHorizontalTilt, state.darkroomRotationDeg) }
                        )
                        else -> PrecisionTickRuler(
                            value = state.darkroomHorizontalTilt,
                            valueRange = -1f..1f,
                            valueLabel = String.format(Locale.US, "Horizontal Tilt  %+d", (state.darkroomHorizontalTilt * 100).toInt()),
                            defaultValue = 0f,
                            onValueChange = { onUpdateKeystone(state.darkroomVerticalTilt, it, state.darkroomRotationDeg) }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        listOf("Rotate", "V-Tilt", "H-Tilt").forEachIndexed { idx, label ->
                            val sel = straightenAxis == idx
                            Text(
                                text = label,
                                color = if (sel) TrueLensPalette.AccentViolet else TrueLensPalette.MutedGray,
                                fontSize = 12.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier
                                    .clickable { straightenAxis = idx }
                                    .padding(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Editorial Tab Strip (Filters | Adjust | Straighten — matching Reference Image #2!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DarkroomTab.entries.forEach { tab ->
                val sel = activeTab == tab
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { activeTab = tab }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (sel) Color.White else TrueLensPalette.MutedGray,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        color = if (sel) Color.White else TrueLensPalette.MutedGray,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = TrueLensPalette.SurfaceDark,
            title = { Text("Delete Photo?", color = Color.White, fontWeight = FontWeight.SemiBold) },
            text = { Text("This will permanently remove this photo from your device.", color = TrueLensPalette.MutedGray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeletePhoto()
                    }
                ) {
                    Text("Delete", color = TrueLensPalette.RecordCoral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    val exifUri = state.darkroomSourceUri ?: state.lastCapturedPhoto?.jpegUri
    if (showExifDialog && exifUri != null) {
        MediaInfoSheetDialog(
            uri = exifUri,
            isVideo = false,
            fallbackName = "TrueLens Photo",
            onDismiss = { showExifDialog = false }
        )
    }
}
