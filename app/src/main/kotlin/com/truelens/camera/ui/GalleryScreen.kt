package com.truelens.camera.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.key
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    val durationMs: Long = 0L,
    val isLivePhoto: Boolean = false
)

object GalleryFavoritesStore {
    private const val PREFS_NAME = "truelens_favorites"
    private const val KEY_URIS = "favorite_uris"

    fun load(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_URIS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun toggle(context: Context, uri: Uri): Set<String> {
        val key = uri.toString()
        val cur = load(context).toMutableSet()
        if (key in cur) cur.remove(key) else cur.add(key)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_URIS, cur)
            .apply()
        return cur
    }

    fun setFavoriteBatch(context: Context, uris: Collection<Uri>, favorite: Boolean): Set<String> {
        val cur = load(context).toMutableSet()
        for (u in uris) {
            if (favorite) cur.add(u.toString()) else cur.remove(u.toString())
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_URIS, cur)
            .apply()
        return cur
    }
}

private enum class GalleryFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    FAVORITES("♥")
}

@Composable
fun GalleryScreen(
    initialVideoUri: Uri? = null,
    onConsumedInitialVideo: () -> Unit = {},
    onClose: () -> Unit,
    onSelectPhotoForDarkroom: (Uri, String) -> Unit,
    onMediaDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeFilter by remember { mutableStateOf(GalleryFilter.ALL) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var playingVideo by remember { mutableStateOf<GalleryPhotoItem?>(null) }
    var favorites by remember { mutableStateOf(GalleryFavoritesStore.load(context)) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // Import Any Phone Photo directly into Darkroom (Feature #4)
    val importPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            onSelectPhotoForDarkroom(pickedUri, "Imported Photo")
        }
    }

    BackHandler {
        when {
            playingVideo != null -> playingVideo = null
            selectionMode -> {
                selectionMode = false
                selectedUris = emptySet()
            }
            else -> onClose()
        }
    }

    val allMedia by produceState<List<GalleryPhotoItem>>(initialValue = emptyList(), refreshKey) {
        value = queryTrueLensGallery(context, includeVideos = true)
    }

    LaunchedEffect(allMedia, initialVideoUri) {
        if (initialVideoUri != null && allMedia.isNotEmpty()) {
            val match = allMedia.firstOrNull { it.uri == initialVideoUri }
            if (match != null) {
                playingVideo = match
            }
            onConsumedInitialVideo()
        }
    }

    val filtered = remember(allMedia, activeFilter, favorites) {
        when (activeFilter) {
            GalleryFilter.ALL -> allMedia
            GalleryFilter.PHOTOS -> allMedia.filter { !it.isVideo }
            GalleryFilter.VIDEOS -> allMedia.filter { it.isVideo }
            GalleryFilter.FAVORITES -> allMedia.filter { it.uri.toString() in favorites }
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
                    IconButton(
                        onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedUris = emptySet()
                            } else {
                                onClose()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) "Cancel Selection" else "Back to Camera",
                            tint = Color.White
                        )
                    }
                    Column {
                        Text(
                            text = if (selectionMode) "${selectedUris.size} Selected" else "Gallery",
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

                // Import Phone Photo + Multi-Select toggle buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    if (!selectionMode) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(TrueLensPalette.AccentViolet.copy(alpha = 0.25f))
                                .border(0.75.dp, TrueLensPalette.AccentViolet.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                .clickable { importPhotoLauncher.launch("image/*") }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Import Photo into Darkroom",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Import",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (selectionMode) TrueLensPalette.AccentViolet else Color(0x18FFFFFF))
                            .clickable {
                                if (selectionMode) {
                                    selectionMode = false
                                    selectedUris = emptySet()
                                } else {
                                    selectionMode = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Checklist,
                            contentDescription = "Multi-Select",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // Clean Filter Pills Row (All | Photos | Videos | ♥)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                GalleryFilter.entries.forEach { f ->
                    val sel = activeFilter == f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    sel && f == GalleryFilter.FAVORITES -> TrueLensPalette.RecordCoral
                                    sel -> TrueLensPalette.AccentViolet
                                    else -> Color(0x18FFFFFF)
                                }
                            )
                            .clickable { activeFilter = f }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (f == GalleryFilter.FAVORITES) "♥ Favorites" else f.label,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                        )
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
                                GalleryFilter.FAVORITES -> "No favorites yet"
                                GalleryFilter.ALL -> "No photos or videos yet"
                            },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (activeFilter == GalleryFilter.FAVORITES) {
                                "Tap ♥ on any photo or video, or long-press tiles to star your favorites."
                            } else {
                                "Capture media or tap Import above to edit any photo from your phone in Darkroom."
                            },
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
                        val keyStr = item.uri.toString()
                        val isSel = keyStr in selectedUris
                        val isFav = keyStr in favorites
                        GalleryTile(
                            item = item,
                            isFavorite = isFav,
                            selectionMode = selectionMode,
                            isSelected = isSel,
                            onLongClick = {
                                selectionMode = true
                                selectedUris = selectedUris + keyStr
                            },
                            onClick = {
                                if (selectionMode) {
                                    selectedUris = if (isSel) selectedUris - keyStr else selectedUris + keyStr
                                    if (selectedUris.isEmpty()) selectionMode = false
                                } else if (item.isVideo) {
                                    playingVideo = item
                                } else {
                                    onSelectPhotoForDarkroom(item.uri, item.displayName)
                                }
                            }
                        )
                    }
                }
            }

            // Multi-Select Action Bar (Share • Favorite • Delete)
            if (selectionMode && selectedUris.isNotEmpty()) {
                val selectedItems = filtered.filter { it.uri.toString() in selectedUris }
                val allFav = selectedItems.isNotEmpty() && selectedItems.all { it.uri.toString() in favorites }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF12131A))
                        .border(0.75.dp, Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x1EFFFFFF))
                            .clickable {
                                val uris = ArrayList(selectedItems.map { it.uri })
                                val intent = if (uris.size == 1) {
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = if (selectedItems.first().isVideo) "video/mp4" else "image/jpeg"
                                        putExtra(Intent.EXTRA_STREAM, uris.first())
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                } else {
                                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "*/*"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                }
                                context.startActivity(Intent.createChooser(intent, "Share ${uris.size} item(s)"))
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Share, "Share", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (allFav) TrueLensPalette.RecordCoral.copy(alpha = 0.25f) else Color(0x1EFFFFFF))
                            .clickable {
                                favorites = GalleryFavoritesStore.setFavoriteBatch(
                                    context,
                                    selectedItems.map { it.uri },
                                    favorite = !allFav
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (allFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (allFav) TrueLensPalette.RecordCoral else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (allFav) "Unstar" else "Favorite", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(TrueLensPalette.RecordCoral.copy(alpha = 0.22f))
                            .clickable { showBatchDeleteConfirm = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Delete, "Delete", tint = TrueLensPalette.RecordCoral, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete", color = TrueLensPalette.RecordCoral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (showBatchDeleteConfirm) {
            val targets = allMedia.filter { it.uri.toString() in selectedUris }
            AlertDialog(
                onDismissRequest = { showBatchDeleteConfirm = false },
                containerColor = TrueLensPalette.SurfaceDark,
                title = { Text("Delete ${targets.size} item(s)?", color = Color.White, fontWeight = FontWeight.SemiBold) },
                text = { Text("These photos/videos will be permanently removed from your device.", color = TrueLensPalette.MutedGray) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showBatchDeleteConfirm = false
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    targets.forEach { t ->
                                        try { context.contentResolver.delete(t.uri, null, null) } catch (_: Exception) {}
                                    }
                                }
                                selectedUris = emptySet()
                                selectionMode = false
                                refreshKey++
                                onMediaDeleted()
                            }
                        }
                    ) {
                        Text("Delete All", color = TrueLensPalette.RecordCoral, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDeleteConfirm = false }) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }

        // Full-Screen In-App Video Player Overlay (Swipe left/right between videos!)
        val activeVideo = playingVideo
        if (activeVideo != null) {
            val videoList = remember(filtered) { filtered.filter { it.isVideo } }
            val vIdx = videoList.indexOfFirst { it.uri == activeVideo.uri }
            key(activeVideo.uri) {
                GalleryVideoPlayerOverlay(
                    item = activeVideo,
                    isFavorite = activeVideo.uri.toString() in favorites,
                    onToggleFavorite = {
                        favorites = GalleryFavoritesStore.toggle(context, activeVideo.uri)
                    },
                    onSwipeNext = {
                        if (vIdx in 0 until videoList.lastIndex) playingVideo = videoList[vIdx + 1]
                    },
                    onSwipePrev = {
                        if (vIdx > 0) playingVideo = videoList[vIdx - 1]
                    },
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
                },
                    onExportedNewMedia = {
                        refreshKey++
                        onMediaDeleted()
                    }
                )
            }
        }
    }
}

@Composable
private fun GalleryVideoPlayerOverlay(
    item: GalleryPhotoItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSwipeNext: () -> Unit = {},
    onSwipePrev: () -> Unit = {},
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onExportedNewMedia: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showTrimPanel by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(item.durationMs.coerceAtLeast(1000L)) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var trimStartFrac by remember { mutableFloatStateOf(0f) }
    var trimEndFrac by remember { mutableFloatStateOf(1f) }
    var muteExportAudio by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    LaunchedEffect(statusMsg) {
        if (statusMsg != null) {
            delay(2500)
            statusMsg = null
        }
    }

    LaunchedEffect(videoViewRef, isPlaying) {
        while (true) {
            val vv = videoViewRef
            if (vv != null) {
                try {
                    if (vv.duration > 0) durationMs = vv.duration.toLong()
                    positionMs = vv.currentPosition.toLong().coerceAtLeast(0L)
                } catch (_: Exception) {
                }
            }
            delay(120)
        }
    }

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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                    Text(
                        text = "${formatDurationMs(positionMs)} / ${formatDurationMs(durationMs)}",
                        color = TrueLensPalette.MutedGray,
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isFavorite) TrueLensPalette.RecordCoral.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                        .clickable { onToggleFavorite() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite Video",
                        tint = if (isFavorite) TrueLensPalette.RecordCoral else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x22FFFFFF))
                        .clickable { showInfoDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Video Info",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
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
                .fillMaxWidth()
                .pointerInput(item.uri) {
                    var dragAccum = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onHorizontalDrag = { _, dragAmount -> dragAccum += dragAmount },
                        onDragEnd = {
                            if (dragAccum < -90f) onSwipeNext()
                            else if (dragAccum > 90f) onSwipePrev()
                            dragAccum = 0f
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(item.uri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            if (mp.duration > 0) durationMs = mp.duration.toLong()
                            start()
                            isPlaying = true
                        }
                    }.also { videoViewRef = it }
                },
                onRelease = { vv ->
                    videoViewRef = null
                    try {
                        vv.stopPlayback()
                    } catch (_: Exception) {
                    }
                }
            )

            if (statusMsg != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xDD14161D))
                        .border(0.75.dp, TrueLensPalette.AccentViolet.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = statusMsg!!, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Trim & Audio Mute Panel (Feature #15)
        if (showTrimPanel) {
            val startMs = (trimStartFrac * durationMs).toLong()
            val endMs = (trimEndFrac * durationMs).toLong()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(TrueLensPalette.GlassPanel)
                    .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(18.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trim: ${formatDurationMs(startMs)} → ${formatDurationMs(endMs)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (muteExportAudio) TrueLensPalette.RecordCoral.copy(alpha = 0.25f) else Color(0x20FFFFFF))
                                .clickable { muteExportAudio = !muteExportAudio }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = if (muteExportAudio) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Mute Audio",
                                tint = if (muteExportAudio) TrueLensPalette.RecordCoral else Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (muteExportAudio) "Muted" else "Audio",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .clickable(enabled = !isProcessing) {
                                    scope.launch {
                                        isProcessing = true
                                        val saved = com.truelens.camera.modes.VideoTrimExporter.trimVideo(
                                            context = context,
                                            sourceUri = item.uri,
                                            startMs = startMs,
                                            endMs = endMs,
                                            muteAudio = muteExportAudio
                                        )
                                        isProcessing = false
                                        if (saved != null) {
                                            statusMsg = "Trimmed video saved to Movies/TrueLens"
                                            showTrimPanel = false
                                            onExportedNewMedia()
                                        } else {
                                            statusMsg = "Trim failed (select > 0.3s)"
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            } else {
                                Text("Save Trim", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("In", color = TrueLensPalette.MutedGray, fontSize = 10.sp, modifier = Modifier.width(26.dp))
                    Slider(
                        value = trimStartFrac,
                        onValueChange = { v ->
                            trimStartFrac = v.coerceAtMost((trimEndFrac - 0.08f).coerceAtLeast(0f))
                            videoViewRef?.seekTo((trimStartFrac * durationMs).toInt())
                        },
                        valueRange = 0f..0.95f,
                        modifier = Modifier.weight(1f).height(24.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = TrueLensPalette.AccentViolet)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Out", color = TrueLensPalette.MutedGray, fontSize = 10.sp, modifier = Modifier.width(26.dp))
                    Slider(
                        value = trimEndFrac,
                        onValueChange = { v ->
                            trimEndFrac = v.coerceAtLeast((trimStartFrac + 0.08f).coerceAtMost(1f))
                            videoViewRef?.seekTo((trimEndFrac * durationMs).toInt())
                        },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f).height(24.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = TrueLensPalette.AccentViolet)
                    )
                }
            }
        }

        // Bottom Playback Scrubber + Frame Grab & Trim Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A0E))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Slider(
                value = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                onValueChange = { frac ->
                    val target = (frac * durationMs).toLong()
                    positionMs = target
                    videoViewRef?.seekTo(target.toInt())
                },
                modifier = Modifier.fillMaxWidth().height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = TrueLensPalette.AccentViolet,
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Pause
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x1EFFFFFF))
                        .clickable {
                            val vv = videoViewRef ?: return@clickable
                            if (vv.isPlaying) {
                                vv.pause()
                                isPlaying = false
                            } else {
                                vv.start()
                                isPlaying = true
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPlaying) "Pause" else "Play", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                // Grab Current Frame as High-Res Photo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x1EFFFFFF))
                        .clickable(enabled = !isProcessing) {
                            scope.launch {
                                isProcessing = true
                                val saved = com.truelens.camera.modes.VideoTrimExporter.grabFrameToGallery(
                                    context = context,
                                    videoUri = item.uri,
                                    positionMs = positionMs
                                )
                                isProcessing = false
                                if (saved != null) {
                                    statusMsg = "Saved frame photo to Pictures/TrueLens"
                                    onExportedNewMedia()
                                } else {
                                    statusMsg = "Could not capture frame"
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Grab Frame",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Grab Frame", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                // Trim & Mute Panel Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (showTrimPanel) TrueLensPalette.AccentViolet else Color(0x1EFFFFFF))
                        .clickable { showTrimPanel = !showTrimPanel }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = "Trim Video",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Trim", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
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
    if (showInfoDialog) {
        MediaInfoSheetDialog(
            uri = item.uri,
            isVideo = true,
            fallbackName = item.displayName,
            onDismiss = { showInfoDialog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryTile(
    item: GalleryPhotoItem,
    isFavorite: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = loadMediaThumbnail(context, item.uri, item.isVideo, 360)
    }

    val badge = when {
        item.isVideo && item.displayName.contains("VHS", ignoreCase = true) -> "VHS '94"
        item.isVideo && item.displayName.contains("SlowMo", ignoreCase = true) -> "SLOW-MO"
        item.isVideo && item.displayName.contains("StopMo", ignoreCase = true) -> "STOP-MO"
        item.isVideo && item.displayName.contains("TimeLapse", ignoreCase = true) -> "TIMELAPSE"
        item.isVideo && item.displayName.contains("Trim", ignoreCase = true) -> "TRIM"
        item.isVideo -> "VIDEO"
        item.displayName.contains("StarTrails", ignoreCase = true) -> "TRAILS"
        item.displayName.contains("Astro", ignoreCase = true) -> "ASTRO"
        item.displayName.contains("DoubleExp", ignoreCase = true) -> "2× EXP"
        item.displayName.contains("ActionPan", ignoreCase = true) -> "ACTION"
        item.displayName.contains("Portrait", ignoreCase = true) -> "PORTRAIT"
        item.displayName.contains("Night", ignoreCase = true) -> "NIGHT"
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
            .border(
                width = if (isSelected) 2.5.dp else 0.75.dp,
                color = if (isSelected) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        val tileBmp = bmp
        if (tileBmp != null && !tileBmp.isRecycled) {
            Image(
                bitmap = tileBmp.asImageBitmap(),
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

        if (isFavorite && !selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.60f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Favorite",
                    tint = TrueLensPalette.RecordCoral,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) TrueLensPalette.AccentViolet else Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        if (item.isLivePhoto && !item.isVideo) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MotionPhotosOn,
                    contentDescription = "Live Photo",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "LIVE",
                    color = Color(0xFFFFB300),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
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
        val livePhotoBases = com.truelens.camera.modes.LivePhotoController.getAllLivePhotoBaseNames(context)

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
                    val base = name.substringBeforeLast('.')
                    result.add(GalleryPhotoItem(uri, name, date, isVideo = false, isLivePhoto = base in livePhotoBases))
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

data class MediaExifDetails(
    val fileName: String = "Unknown",
    val fileSizeLabel: String = "—",
    val dimensions: String = "—",
    val dateLabel: String = "—",
    val cameraModel: String = "—",
    val lensFocal: String = "—",
    val aperture: String = "—",
    val shutter: String = "—",
    val iso: String = "—",
    val duration: String? = null
)

internal suspend fun loadFullMediaMetadata(
    context: Context,
    uri: Uri,
    isVideo: Boolean,
    fallbackName: String = "Media"
): MediaExifDetails = withContext(Dispatchers.IO) {
    var fileName = fallbackName
    var sizeBytes = 0L
    try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nIdx >= 0 && !c.isNull(nIdx)) fileName = c.getString(nIdx)
                if (sIdx >= 0 && !c.isNull(sIdx)) sizeBytes = c.getLong(sIdx)
            }
        }
    } catch (_: Exception) {}

    val sizeLabel = when {
        sizeBytes >= 1_048_576L -> String.format(Locale.US, "%.2f MB", sizeBytes / 1_048_576.0)
        sizeBytes >= 1024L -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
        sizeBytes > 0L -> "$sizeBytes B"
        else -> "—"
    }

    if (isVideo) {
        val mmr = MediaMetadataRetriever()
        var dims = "—"
        var dur: String? = null
        var dateStr = "—"
        try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w != null && h != null) dims = "$w × $h px"
            val dMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (dMs != null) dur = formatDurationMs(dMs)
            dateStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: "—"
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        return@withContext MediaExifDetails(
            fileName = fileName,
            fileSizeLabel = sizeLabel,
            dimensions = dims,
            dateLabel = dateStr,
            cameraModel = android.os.Build.MODEL,
            duration = dur
        )
    }

    var dims = "—"
    var dateStr = "—"
    var model = android.os.Build.MODEL
    var focal = "—"
    var aperture = "—"
    var shutter = "—"
    var iso = "—"
    try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val exif = ExifInterface(ins)
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) dims = "$w × $h px"
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { dateStr = it }
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)?.let { dateStr = it }
            val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
            val mod = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
            if (mod.isNotBlank()) model = if (make.isNotBlank() && !mod.startsWith(make, true)) "$make $mod" else mod
            val fl = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
            if (fl > 0) focal = String.format(Locale.US, "%.1f mm", fl)
            val fNum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
            if (fNum > 0) aperture = String.format(Locale.US, "f/%.1f", fNum)
            val exp = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
            if (exp > 0) {
                shutter = if (exp >= 0.5) String.format(Locale.US, "%.1fs", exp)
                else "1/${(1.0 / exp).toInt().coerceAtLeast(1)}s"
            }
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { iso = "ISO $it" }
        }
    } catch (_: Exception) {}

    if (dims == "—") {
        try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                dims = "${opts.outWidth} × ${opts.outHeight} px"
            }
        } catch (_: Exception) {}
    }

    if (dateStr == "—") {
        dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }

    MediaExifDetails(
        fileName = fileName,
        fileSizeLabel = sizeLabel,
        dimensions = dims,
        dateLabel = dateStr,
        cameraModel = model,
        lensFocal = focal,
        aperture = aperture,
        shutter = shutter,
        iso = iso
    )
}

@Composable
fun MediaInfoSheetDialog(
    uri: Uri,
    isVideo: Boolean,
    fallbackName: String = "Photo",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val details by produceState(initialValue = MediaExifDetails(fileName = fallbackName), uri) {
        value = loadFullMediaMetadata(context, uri, isVideo, fallbackName)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13151C),
        title = {
            Text(
                text = if (isVideo) "Video Details" else "EXIF & Photo Specs",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ExifRow("File", details.fileName)
                ExifRow("Resolution", details.dimensions)
                ExifRow("Size", details.fileSizeLabel)
                if (details.duration != null) ExifRow("Duration", details.duration!!)
                ExifRow("Date", details.dateLabel)
                ExifRow("Device", details.cameraModel)
                if (!isVideo) {
                    ExifRow("Lens", details.lensFocal)
                    ExifRow("Aperture", details.aperture)
                    ExifRow("Shutter", details.shutter)
                    ExifRow("ISO", details.iso)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TrueLensPalette.AccentViolet, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
private fun ExifRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TrueLensPalette.MutedGray, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}
