package com.truelens.camera.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truelens.camera.pose.PinterestBoardSummary
import com.truelens.camera.pose.PoseFolder
import com.truelens.camera.pose.PoseInspoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val InspoBg = Color(0xFF08080A)
private val InspoCard = Color(0xFF14161C)
private val InspoAmber = Color(0xFFFFB300)
private val PinterestRed = Color(0xFFE60023)

@Composable
fun PoseInsposScreen(
    folders: List<PoseFolder>,
    selectedFolderId: String?,
    isImporting: Boolean,
    importStatus: String?,
    pinterestBoards: List<PinterestBoardSummary>,
    isPinterestConnected: Boolean,
    savedPinterestClientId: String,
    onClose: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onImportGalleryUris: (String, List<Uri>) -> Unit,
    onImportPublicPinterestUrl: (String) -> Unit,
    onSavePinterestToken: (clientId: String, token: String) -> Unit,
    onLaunchPinterestOAuth: (clientId: String) -> Unit,
    onSyncPinterestBoard: (PinterestBoardSummary) -> Unit,
    onSelectPoseForOverlay: (PoseInspoItem) -> Unit,
    onDeletePoseItem: (folderId: String, itemId: String) -> Unit
) {
    val activeFolder = folders.firstOrNull { it.id == selectedFolderId } ?: folders.firstOrNull()
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showPinterestDialog by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty() && activeFolder != null) {
            onImportGalleryUris(activeFolder.id, uris)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InspoBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Camera",
                        tint = Color.White
                    )
                }
                Column {
                    Text(
                        text = "POSE INSPOS",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Text(
                        text = "Tap any pose to overlay",
                        color = Color(0xFFA0A4AE),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                // Gallery Multi-Import
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(InspoAmber)
                        .clickable {
                            galleryPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .padding(horizontal = 9.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Gallery",
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = " GALLERY",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Pinterest Import / Connect
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(PinterestRed)
                        .clickable { showPinterestDialog = true }
                        .padding(horizontal = 9.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Pinterest",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = " PINTEREST",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Folder Chips Strip + New Folder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            folders.forEach { folder ->
                val selected = folder.id == activeFolder?.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) Color.White else InspoCard)
                        .border(
                            1.dp,
                            if (selected) Color.White else Color.White.copy(alpha = 0.14f),
                            RoundedCornerShape(18.dp)
                        )
                        .clickable { onSelectFolder(folder.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${folder.name} (${folder.items.size})",
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x22FFB300))
                    .border(1.dp, InspoAmber.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                    .clickable { showNewFolderDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Folder",
                    tint = InspoAmber,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = " Folder",
                    color = InspoAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Import progress banner
        if (isImporting || importStatus != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1D26))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        color = InspoAmber,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = importStatus ?: "Importing pose inspos...",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Pose Grid or Empty State
        val items = activeFolder?.items ?: emptyList()
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No poses in \"${activeFolder?.name ?: "Folder"}\" yet",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap GALLERY to add photos, tap PINTEREST to sync a board, or use Share → TrueLens directly inside the Pinterest app.",
                        color = Color(0xFFA0A4AE),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
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
                items(items, key = { it.id }) { item ->
                    PoseThumbnailTile(
                        item = item,
                        onSelect = { onSelectPoseForOverlay(item) },
                        onDelete = {
                            if (activeFolder != null) {
                                onDeletePoseItem(activeFolder.id, item.id)
                            }
                        }
                    )
                }
            }
        }
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            containerColor = InspoCard,
            title = { Text("New Pose Inspo Folder", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = { Text("e.g. Coffee Shop, Couples, Beach", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = InspoAmber
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            onCreateFolder(folderName.trim())
                            showNewFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InspoAmber, contentColor = Color.Black)
                ) {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // Pinterest Import Dialog (Public Board URL + Official API v5 Account Sync)
    if (showPinterestDialog) {
        PinterestImportDialog(
            boards = pinterestBoards,
            isConnected = isPinterestConnected,
            savedClientId = savedPinterestClientId,
            onDismiss = { showPinterestDialog = false },
            onImportPublicUrl = { url ->
                showPinterestDialog = false
                onImportPublicPinterestUrl(url)
            },
            onSaveToken = onSavePinterestToken,
            onLaunchOAuth = onLaunchPinterestOAuth,
            onSyncBoard = { board ->
                showPinterestDialog = false
                onSyncPinterestBoard(board)
            }
        )
    }
}

@Composable
private fun PoseThumbnailTile(
    item: PoseInspoItem,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val bmp by produceState<Bitmap?>(initialValue = null, item.filePath) {
        value = withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(item.filePath, opts)
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(12.dp))
            .background(InspoCard)
            .border(0.75.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable { onSelect() }
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Source badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = item.source.uppercase(),
                color = if (item.source == "Pinterest") Color(0xFFFF6B81) else Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Delete icon
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun PinterestImportDialog(
    boards: List<PinterestBoardSummary>,
    isConnected: Boolean,
    savedClientId: String,
    onDismiss: () -> Unit,
    onImportPublicUrl: (String) -> Unit,
    onSaveToken: (clientId: String, token: String) -> Unit,
    onLaunchOAuth: (clientId: String) -> Unit,
    onSyncBoard: (PinterestBoardSummary) -> Unit
) {
    var tab by remember { mutableStateOf(0) } // 0 = Public URL, 1 = Account/Secret Boards
    var publicUrl by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf(savedClientId) }
    var accessToken by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InspoCard,
        title = {
            Column {
                Text("Pinterest Board Import", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Public Board URL", "Account / Secret").forEachIndexed { idx, label ->
                        val active = tab == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) PinterestRed else Color(0x1EFFFFFF))
                                .clickable { tab = idx }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        text = {
            if (tab == 0) {
                Column {
                    Text(
                        text = "Paste any public Pinterest board URL (or pin.it link). No login required:",
                        color = Color(0xFFB5B9C4),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = publicUrl,
                        onValueChange = { publicUrl = it },
                        placeholder = { Text("https://pinterest.com/user/poses/", color = Color.Gray, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PinterestRed
                        )
                    )
                }
            } else {
                Column {
                    if (isConnected && boards.isNotEmpty()) {
                        Text(
                            text = "Select a board to sync into Pose Inspos:",
                            color = Color(0xFFB5B9C4),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        boards.take(8).forEach { board ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x18FFFFFF))
                                    .clickable { onSyncBoard(board) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (board.isSecret) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Secret",
                                            tint = InspoAmber,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                    }
                                    Text(
                                        text = "${board.name} (${board.pinCount})",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = "Sync",
                                    tint = InspoAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Connect via Pinterest API v5 to sync Private & Secret boards. Paste a Developer Access Token (or enter Client ID to sign in via OAuth):",
                            color = Color(0xFFB5B9C4),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = accessToken,
                            onValueChange = { accessToken = it },
                            placeholder = { Text("Pinterest v5 Access Token", color = Color.Gray, fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = InspoAmber
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            placeholder = { Text("Or Pinterest App Client ID (OAuth)", color = Color.Gray, fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = InspoAmber
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (tab == 0) {
                Button(
                    onClick = { if (publicUrl.isNotBlank()) onImportPublicUrl(publicUrl) },
                    colors = ButtonDefaults.buttonColors(containerColor = PinterestRed, contentColor = Color.White)
                ) {
                    Text("Import Board", fontWeight = FontWeight.Bold)
                }
            } else if (!isConnected || boards.isEmpty()) {
                Button(
                    onClick = {
                        if (accessToken.isNotBlank()) {
                            onSaveToken(clientId, accessToken)
                        } else if (clientId.isNotBlank()) {
                            onLaunchOAuth(clientId)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InspoAmber, contentColor = Color.Black)
                ) {
                    Text(if (accessToken.isNotBlank()) "Connect Token" else "Sign In OAuth", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Gray)
            }
        }
    )
}
