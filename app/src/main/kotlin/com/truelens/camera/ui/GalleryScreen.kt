package com.truelens.camera.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class GalleryPhotoItem(
    val uri: Uri,
    val displayName: String,
    val dateAddedSec: Long,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L
)

private enum class GalleryFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos")
}

@Composable
fun GalleryScreen(
    onClose: () -> Unit,
    onSelectPhotoForDarkroom: (Uri, String) -> Unit,
    onMediaDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeFilter by remember { mutableStateOf(GalleryFilter.ALL) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var playingVideo by remember { mutableStateOf<GalleryPhotoItem?>(null) }

    BackHandler {
        if (playingVideo != null) {
            playingVideo = null
        } else {
            onClose()
        }
    }

    val allMedia by produceState<List<GalleryPhotoItem>>(initialValue = emptyList(), refreshKey) {
        value = queryTrueLensGallery(context, includeVideos = true)
    }

    val filtered = remember(allMedia, activeFilter) {
        when (activeFilter) {
            GalleryFilter.ALL -> allMedia
            GalleryFilter.PHOTOS -> allMedia.filter { !it.isVideo }
            GalleryFilter.VIDEOS -> allMedia.filter { it.isVideo }
        }
    }

    val photoCount = remember(allMedia) { allMedia.count { !it.isVideo } }
    val videoCount = remember(allMedia) { allMedia.count { it.isVideo } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08080A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
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
                            text = "Gallery",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$photoCount photo(s) • $videoCount video(s)",
                            color = TrueLensPalette.MutedGray,
                            fontSize = 12.sp
                        )
                    }
                }

                // Clean Filter Pills (All | Photos | Videos)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    GalleryFilter.entries.forEach { f ->
                        val sel = activeFilter == f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (sel) TrueLensPalette.AccentViolet else Color(0x18FFFFFF))
                                .clickable { activeFilter = f }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = f.label,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when (activeFilter) {
                                GalleryFilter.VIDEOS -> "No TrueLens videos yet"
                                GalleryFilter.PHOTOS -> "No TrueLens photos yet"
                                GalleryFilter.ALL -> "No photos or videos yet"
                            },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Capture a photo, video, slow-mo, panorama, or time-lapse and it will appear right here.",
                            color = Color(0xFFA0A4AE),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filtered, key = { it.uri.toString() }) { item ->
                        GalleryTile(
                            item = item,
                            onClick = {
                                if (item.isVideo) {
                                    playingVideo = item
                                } else {
                                    onSelectPhotoForDarkroom(item.uri, item.displayName)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Full-Screen In-App Video Player Overlay
        val activeVideo = playingVideo
        if (activeVideo != null) {
            GalleryVideoPlayerOverlay(
                item = activeVideo,
                onBack = { playingVideo = null },
                onShare = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, activeVideo.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
                },
                onDelete = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.delete(activeVideo.uri, null, null)
                            } catch (_: Exception) {
                            }
                        }
                        playingVideo = null
                        refreshKey++
                        onMediaDeleted()
                    }
                }
            )
        }
    }
}

@Composable
private fun GalleryVideoPlayerOverlay(
    item: GalleryPhotoItem,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x22FFFFFF))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = item.displayName.removeSuffix(".mp4"),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (item.durationMs > 0L) {
                        Text(
                            text = formatDurationMs(item.durationMs),
                            color = TrueLensPalette.MutedGray,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x22FFFFFF))
                        .clickable { onShare() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Video",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x22FFFFFF))
                        .clickable { showDeleteConfirm = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Video",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(item.uri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                onRelease = { vv ->
                    try {
                        vv.stopPlayback()
                    } catch (_: Exception) {
                    }
                }
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = TrueLensPalette.SurfaceDark,
            title = { Text("Delete Video?", color = Color.White, fontWeight = FontWeight.SemiBold) },
            text = { Text("This will permanently remove this video from your device.", color = TrueLensPalette.MutedGray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
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
}

@Composable
private fun GalleryTile(
    item: GalleryPhotoItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = loadMediaThumbnail(context, item.uri, item.isVideo, 360)
    }

    val badge = when {
        item.isVideo && item.displayName.contains("SlowMo", ignoreCase = true) -> "SLOW-MO"
        item.isVideo && item.displayName.contains("StopMo", ignoreCase = true) -> "STOP-MO"
        item.isVideo && item.displayName.contains("TimeLapse", ignoreCase = true) -> "TIMELAPSE"
        item.isVideo -> "VIDEO"
        item.displayName.contains("Pano", ignoreCase = true) -> "PANORAMA"
        item.displayName.contains("Dual", ignoreCase = true) -> "DUAL"
        item.displayName.contains("Scan", ignoreCase = true) -> "DOCUMENT"
        item.displayName.contains("LongExp", ignoreCase = true) -> "LONG EXP"
        item.displayName.contains("Edit", ignoreCase = true) -> "EDIT"
        else -> "PHOTO"
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF14161C))
            .border(0.75.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (item.isVideo) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.Center).size(30.dp)
            )
        }

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Video",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = badge,
                color = when {
                    item.isVideo -> TrueLensPalette.AccentViolet
                    badge == "PANORAMA" -> Color(0xFFFFB300)
                    else -> Color.White
                },
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (item.isVideo && item.durationMs > 0L) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatDurationMs(item.durationMs),
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun formatDurationMs(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}

internal suspend fun loadMediaThumbnail(
    context: Context,
    uri: Uri,
    isVideo: Boolean,
    maxDim: Int = 360
): Bitmap? = withContext(Dispatchers.IO) {
    if (!isVideo) {
        return@withContext com.truelens.camera.camera.ExifBitmapLoader.loadScaled(context, uri, maxDim)
    }
    try {
        context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
    } catch (_: Exception) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val raw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (raw != null && maxOf(raw.width, raw.height) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(raw.width, raw.height).toFloat()
                val w = (raw.width * scale).toInt().coerceAtLeast(1)
                val h = (raw.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(raw, w, h, true).also {
                    if (it !== raw) raw.recycle()
                }
            } else {
                raw
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}

internal suspend fun queryTrueLensGallery(
    context: Context,
    includeVideos: Boolean = true
): List<GalleryPhotoItem> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<GalleryPhotoItem>()

        // 1. Query TrueLens Photos (Pictures/TrueLens)
        val imgProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val imgSelection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val imgArgs = arrayOf("%${Environment.DIRECTORY_PICTURES}/TrueLens%")
        val imgSort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imgProjection,
                imgSelection,
                imgArgs,
                imgSort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "TrueLens.jpg"
                    if (name.endsWith(".dng", ignoreCase = true)) continue
                    val date = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    result.add(GalleryPhotoItem(uri, name, date, isVideo = false))
                }
            }
        } catch (_: Exception) {
        }

        // 2. Query TrueLens Videos (Movies/TrueLens — normal Video, Slow-Mo, Stop-Motion, Time-Lapse)
        if (includeVideos) {
            val vidProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.RELATIVE_PATH
            )
            val vidSelection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val vidArgs = arrayOf("%${Environment.DIRECTORY_MOVIES}/TrueLens%")
            val vidSort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            try {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    vidProjection,
                    vidSelection,
                    vidArgs,
                    vidSort
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "TrueLens_Video.mp4"
                        val date = cursor.getLong(dateCol)
                        val dur = if (durCol >= 0) cursor.getLong(durCol) else 0L
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        result.add(
                            GalleryPhotoItem(
                                uri = uri,
                                displayName = name,
                                dateAddedSec = date,
                                isVideo = true,
                                durationMs = dur
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        result.sortByDescending { it.dateAddedSec }
        result
    }
