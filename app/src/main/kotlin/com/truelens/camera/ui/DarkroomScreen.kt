package com.truelens.camera.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch
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

    val scope = rememberCoroutineScope()
    val displayBitmap = if (showOriginal) state.darkroomSourceBitmap else state.darkroomGradedBitmap
    // Load gallery list once per Darkroom session so swiping never stalls on MediaStore IPC
    val recentShots by produceState<List<GalleryPhotoItem>>(initialValue = emptyList(), Unit) {
        value = queryTrueLensGallery(context, includeVideos = false)
    }
    val currentUri = state.darkroomSourceUri ?: state.lastCapturedPhoto?.jpegUri
    val galleryUris = remember(recentShots) { recentShots.map { it.uri } }
    val pagerUris = remember(galleryUris, currentUri) {
        if (galleryUris.isNotEmpty() && currentUri != null && currentUri in galleryUris) {
            galleryUris
        } else {
            listOfNotNull(currentUri)
        }
    }

    // Pre-decoded in-memory page cache so adjacent photos are already in RAM before you swipe
    val pageBitmapCache = remember { LruCache<String, Bitmap>(7) }
    if (currentUri != null) {
        val srcBmp = state.darkroomSourceBitmap
        if (srcBmp != null && !srcBmp.isRecycled && pageBitmapCache.get(currentUri.toString()) == null) {
            pageBitmapCache.put(currentUri.toString(), srcBmp)
        }
    }

    var photoZoom by remember { mutableFloatStateOf(1f) }
    var photoPanX by remember { mutableFloatStateOf(0f) }
    var photoPanY by remember { mutableFloatStateOf(0f) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { pagerUris.size.coerceAtLeast(1) }
    )
    var syncedPagerUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Position pager immediately on currentUri once the gallery list arrives (no jump animation)
    LaunchedEffect(pagerUris) {
        val targetIdx = if (currentUri != null) pagerUris.indexOf(currentUri) else -1
        if (targetIdx >= 0 && pagerState.currentPage != targetIdx) {
            pagerState.scrollToPage(targetIdx)
        }
        syncedPagerUris = pagerUris
    }

    // Only sync ViewModel editing state after the pager settles — zero mid-swipe jank or flicker!
    LaunchedEffect(pagerState.settledPage, syncedPagerUris) {
        if (syncedPagerUris !== pagerUris) return@LaunchedEffect
        val settledUri = pagerUris.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        if (settledUri != currentUri) {
            photoZoom = 1f
            photoPanX = 0f
            photoPanY = 0f
            onSelectFilmstripUri(settledUri)
        }
    }

    val filmstripListState = rememberLazyListState()
    LaunchedEffect(pagerState.currentPage, pagerUris.size) {
        if (pagerUris.size > 1) {
            filmstripListState.animateScrollToItem((pagerState.currentPage - 2).coerceAtLeast(0))
        }
    }

    // Small true-aspect base thumbnail for real-time graded preset preview tiles (NEVER stretches faces!)
    val tinyBaseBitmap by produceState<Bitmap?>(initialValue = null, state.darkroomSourceBitmap) {
        value = withContext(Dispatchers.Default) {
            val src = state.darkroomSourceBitmap ?: return@withContext null
            val maxDim = 180f
            val scale = (maxDim / maxOf(src.width, src.height).coerceAtLeast(1)).coerceAtMost(1f)
            val tw = (src.width * scale).toInt().coerceAtLeast(1)
            val th = (src.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, tw, th, true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrueLensPalette.Obsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Bar: Compact circular icons + single-line "Export" pill (NEVER wraps/breaks words!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x1EFFFFFF))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Photos",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(31.dp)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF))
                        .clickable(enabled = state.canUndoDarkroom) { onUndo() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (state.canUndoDarkroom) Color.White else Color.White.copy(alpha = 0.28f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(31.dp)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF))
                        .clickable(enabled = state.canRedoDarkroom) { onRedo() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (state.canRedoDarkroom) Color.White else Color.White.copy(alpha = 0.28f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeUri = state.darkroomSourceUri ?: state.lastCapturedPhoto?.jpegUri
                if (activeUri != null) {
                    val isFav = activeUri.toString() in favoriteSet
                    Box(
                        modifier = Modifier
                            .size(31.dp)
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
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(31.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .clickable { showExifDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Photo EXIF Info",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(31.dp)
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
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(31.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .clickable { showDeleteConfirm = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Photo",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .clickable(enabled = !state.isExportingDarkroom) { onExport() }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isExportingDarkroom) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp)
                        )
                    } else {
                        Text(
                            text = "Export",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Rounded Photo Card (Native HorizontalPager + Preloaded Neighbors + Pinch-to-Zoom 1x-6x + Double-Tap!)
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
            } else if (activeKeyFrame != null && !activeKeyFrame.isRecycled) {
                Image(
                    bitmap = activeKeyFrame.asImageBitmap(),
                    contentDescription = "Selected Key Frame",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    pageSpacing = 12.dp,
                    userScrollEnabled = photoZoom <= 1.03f && !showKeyPhotoPicker && !isPlayingLiveMotion && pagerUris.size > 1,
                    key = { idx -> pagerUris.getOrNull(idx)?.toString() ?: "page_$idx" },
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    val pageUri = pagerUris.getOrNull(pageIdx)
                    val isActiveEditedPage = pageUri != null && pageUri == currentUri
                    val cachedPageBitmap by produceState<Bitmap?>(
                        initialValue = pageUri?.let { pageBitmapCache.get(it.toString()) },
                        pageUri
                    ) {
                        if (pageUri == null) {
                            value = null
                            return@produceState
                        }
                        val cacheKey = pageUri.toString()
                        val existing = pageBitmapCache.get(cacheKey)
                        if (existing != null && !existing.isRecycled) {
                            value = existing
                        } else {
                            val decoded = withContext(Dispatchers.IO) {
                                ExifBitmapLoader.loadScaled(context, pageUri, 1280)
                            }
                            if (decoded != null && !decoded.isRecycled) {
                                pageBitmapCache.put(cacheKey, decoded)
                            }
                            value = decoded
                        }
                    }

                    val pageBmp = if (isActiveEditedPage && displayBitmap != null && !displayBitmap.isRecycled) {
                        displayBitmap
                    } else {
                        cachedPageBitmap
                    }
                    val isCurrentPage = pageIdx == pagerState.currentPage

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(pageUri, showKeyPhotoPicker) {
                                if (!showKeyPhotoPicker) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (photoZoom > 1.05f) {
                                                photoZoom = 1f
                                                photoPanX = 0f
                                                photoPanY = 0f
                                            } else {
                                                photoZoom = 2.5f
                                                photoPanX = 0f
                                                photoPanY = 0f
                                            }
                                        }
                                    )
                                }
                            }
                            .pointerInput(pageUri, showKeyPhotoPicker) {
                                if (!showKeyPhotoPicker) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var isMultiTouch = false
                                        do {
                                            val event = awaitPointerEvent()
                                            val pressedCount = event.changes.count { it.pressed }
                                            if (pressedCount >= 2) {
                                                isMultiTouch = true
                                                val zoomChange = event.calculateZoom()
                                                val panChange = event.calculatePan()
                                                val nextZoom = (photoZoom * zoomChange).coerceIn(1f, 6f)
                                                photoZoom = nextZoom
                                                if (nextZoom <= 1.02f) {
                                                    photoZoom = 1f
                                                    photoPanX = 0f
                                                    photoPanY = 0f
                                                } else {
                                                    val maxPanX = size.width * (nextZoom - 1f) * 0.5f
                                                    val maxPanY = size.height * (nextZoom - 1f) * 0.5f
                                                    photoPanX = (photoPanX + panChange.x).coerceIn(-maxPanX, maxPanX)
                                                    photoPanY = (photoPanY + panChange.y).coerceIn(-maxPanY, maxPanY)
                                                }
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            } else if (pressedCount == 1 && !isMultiTouch && photoZoom > 1.03f) {
                                                // Zoomed in: pan smoothly inside the photo (leave 1x drags unconsumed for HorizontalPager!)
                                                val panChange = event.calculatePan()
                                                val maxPanX = size.width * (photoZoom - 1f) * 0.5f
                                                val maxPanY = size.height * (photoZoom - 1f) * 0.5f
                                                photoPanX = (photoPanX + panChange.x).coerceIn(-maxPanX, maxPanX)
                                                photoPanY = (photoPanY + panChange.y).coerceIn(-maxPanY, maxPanY)
                                                if (panChange.x != 0f || panChange.y != 0f) {
                                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (pageBmp != null && !pageBmp.isRecycled) {
                            Image(
                                bitmap = pageBmp.asImageBitmap(),
                                contentDescription = "Photo Preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        if (isCurrentPage && photoZoom > 1.001f) {
                                            scaleX = photoZoom
                                            scaleY = photoZoom
                                            translationX = photoPanX
                                            translationY = photoPanY
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            // Zoom Level Pill (visible when pinched/zoomed in — tap to reset to 1x!)
            if (photoZoom > 1.05f && !showKeyPhotoPicker) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xBB0D0E12))
                        .border(0.75.dp, TrueLensPalette.AccentViolet.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .clickable {
                            photoZoom = 1f
                            photoPanX = 0f
                            photoPanY = 0f
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f× • Reset", photoZoom),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Subtle photo index indicator when browsing multiple photos (updates live as you glide!)
            if (pagerUris.size > 1 && !showKeyPhotoPicker) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x990D0E12))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pagerUris.size}",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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

        // Quick Gallery Filmstrip (auto-tracks swipe position across all photos!)
        if (recentShots.size > 1) {
            LazyRow(
                state = filmstripListState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(recentShots, key = { _, it -> it.uri.toString() }) { idx, item ->
                    val isCurrent = if (pagerUris.size == recentShots.size) {
                        idx == pagerState.currentPage
                    } else {
                        item.uri == currentUri
                    }
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
                            .clickable {
                                photoZoom = 1f
                                photoPanX = 0f
                                photoPanY = 0f
                                if (pagerUris.size == recentShots.size && idx in pagerUris.indices) {
                                    scope.launch { pagerState.animateScrollToPage(idx) }
                                } else {
                                    onSelectFilmstripUri(item.uri)
                                }
                            }
                    ) {
                        val t = thumb
                        if (t != null && !t.isRecycled) {
                            Image(
                                bitmap = t.asImageBitmap(),
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
                val srcAspect = remember(state.darkroomSourceBitmap) {
                    val b = state.darkroomSourceBitmap
                    if (b != null && b.height > 0) (b.width.toFloat() / b.height.toFloat()).coerceIn(0.62f, 1.45f)
                    else 0.78f
                }
                val cardHeightDp = 96.dp
                val cardWidthDp = (96f * srcAspect).dp
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(102.dp)
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
                                .width(cardWidthDp)
                                .height(cardHeightDp)
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
                            val pBmp = previewBmp
                            if (pBmp != null && !pBmp.isRecycled) {
                                Image(
                                    bitmap = pBmp.asImageBitmap(),
                                    contentDescription = preset.name,
                                    contentScale = ContentScale.Fit,
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
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Border & Specs",
                            color = TrueLensPalette.MutedGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.truelens.camera.grading.ExportFrameStyle.entries.forEach { style ->
                                val sel = state.darkroomFrameStyle == style
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) Color.White else Color(0x1EFFFFFF))
                                        .clickable { onSelectFrameStyle(style) }
                                        .padding(horizontal = 6.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = style.label,
                                        color = if (sel) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Date Stamp",
                            color = TrueLensPalette.MutedGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.truelens.camera.grading.DateStampStyle.entries.forEach { stamp ->
                                val sel = state.darkroomDateStamp == stamp
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                sel && stamp == com.truelens.camera.grading.DateStampStyle.ANALOG_98 -> Color(0xFFFF7024)
                                                sel -> TrueLensPalette.AccentViolet
                                                else -> Color(0x1EFFFFFF)
                                            }
                                        )
                                        .clickable { onSelectDateStamp(stamp) }
                                        .padding(horizontal = 6.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stamp.label,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
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
