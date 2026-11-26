package com.truelens.camera.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Persistent local storage for "Pose Inspos" folders & reference images.
 * Stored privately in filesDir/pose_inspos so Gallery/Pinterest/Shared poses
 * stay organized and load instantly offline.
 */
class PoseInspoRepository(private val context: Context) {

    private val rootDir = File(context.filesDir, "pose_inspos").apply { mkdirs() }
    private val imagesDir = File(rootDir, "images").apply { mkdirs() }
    private val indexFile = File(rootDir, "folders.json")

    suspend fun loadFolders(): List<PoseFolder> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) {
            val defaults = listOf(
                PoseFolder(id = "portraits", name = "Portraits"),
                PoseFolder(id = "sitting_stairs", name = "Sitting & Stairs"),
                PoseFolder(id = "full_body", name = "Full Body")
            )
            saveFoldersSync(defaults)
            return@withContext defaults
        }
        try {
            val json = JSONArray(indexFile.readText())
            val result = mutableListOf<PoseFolder>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<PoseInspoItem>()
                for (j in 0 until itemsArr.length()) {
                    val itemObj = itemsArr.getJSONObject(j)
                    val path = itemObj.getString("filePath")
                    if (File(path).exists()) {
                        items.add(
                            PoseInspoItem(
                                id = itemObj.getString("id"),
                                folderId = obj.getString("id"),
                                filePath = path,
                                title = itemObj.optString("title", ""),
                                source = itemObj.optString("source", "Gallery"),
                                addedAtMs = itemObj.optLong("addedAtMs", 0L)
                            )
                        )
                    }
                }
                result.add(
                    PoseFolder(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        sourceUrl = obj.optString("sourceUrl", "").ifBlank { null },
                        isPinterestBoard = obj.optBoolean("isPinterestBoard", false),
                        items = items
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun createFolder(
        name: String,
        sourceUrl: String? = null,
        isPinterestBoard: Boolean = false
    ): PoseFolder = withContext(Dispatchers.IO) {
        val current = loadFolders().toMutableList()
        val folder = PoseFolder(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "New Folder" },
            sourceUrl = sourceUrl,
            isPinterestBoard = isPinterestBoard
        )
        current.add(0, folder)
        saveFoldersSync(current)
        folder
    }

    suspend fun deleteFolder(folderId: String): List<PoseFolder> = withContext(Dispatchers.IO) {
        val current = loadFolders().toMutableList()
        val target = current.firstOrNull { it.id == folderId }
        target?.items?.forEach { File(it.filePath).delete() }
        current.removeAll { it.id == folderId }
        saveFoldersSync(current)
        current
    }

    suspend fun importFromUri(
        folderId: String,
        uri: Uri,
        source: String = "Gallery"
    ): PoseInspoItem? = withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeScaledBitmapFromUri(uri, 1600) ?: return@withContext null
            val item = saveBitmapToFolderSync(folderId, bitmap, title = "", source = source)
            bitmap.recycle()
            item
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importFromBytes(
        folderId: String,
        bytes: ByteArray,
        title: String = "",
        source: String = "Pinterest"
    ): PoseInspoItem? = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: return@withContext null
            val item = saveBitmapToFolderSync(folderId, bitmap, title = title, source = source)
            bitmap.recycle()
            item
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteItem(folderId: String, itemId: String): List<PoseFolder> = withContext(Dispatchers.IO) {
        val current = loadFolders().map { folder ->
            if (folder.id == folderId) {
                folder.items.firstOrNull { it.id == itemId }?.let { File(it.filePath).delete() }
                folder.copy(items = folder.items.filterNot { it.id == itemId })
            } else {
                folder
            }
        }
        saveFoldersSync(current)
        current
    }

    suspend fun loadBitmap(filePath: String, maxDim: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, opts)
            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(filePath, decodeOpts)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveBitmapToFolderSync(
        folderId: String,
        bitmap: Bitmap,
        title: String,
        source: String
    ): PoseInspoItem {
        val id = UUID.randomUUID().toString()
        val file = File(imagesDir, "$id.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val item = PoseInspoItem(
            id = id,
            folderId = folderId,
            filePath = file.absolutePath,
            title = title,
            source = source
        )
        val current = readFoldersSync().map { folder ->
            if (folder.id == folderId) {
                folder.copy(items = listOf(item) + folder.items)
            } else {
                folder
            }
        }
        saveFoldersSync(current)
        return item
    }

    private fun readFoldersSync(): List<PoseFolder> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val json = JSONArray(indexFile.readText())
            List(json.length()) { i ->
                val obj = json.getJSONObject(i)
                val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                val items = List(itemsArr.length()) { j ->
                    val itemObj = itemsArr.getJSONObject(j)
                    PoseInspoItem(
                        id = itemObj.getString("id"),
                        folderId = obj.getString("id"),
                        filePath = itemObj.getString("filePath"),
                        title = itemObj.optString("title", ""),
                        source = itemObj.optString("source", "Gallery"),
                        addedAtMs = itemObj.optLong("addedAtMs", 0L)
                    )
                }.filter { File(it.filePath).exists() }
                PoseFolder(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    sourceUrl = obj.optString("sourceUrl", "").ifBlank { null },
                    isPinterestBoard = obj.optBoolean("isPinterestBoard", false),
                    items = items
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveFoldersSync(folders: List<PoseFolder>) {
        val arr = JSONArray()
        folders.forEach { folder ->
            val fObj = JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
                put("sourceUrl", folder.sourceUrl ?: "")
                put("isPinterestBoard", folder.isPinterestBoard)
                val itemsArr = JSONArray()
                folder.items.forEach { item ->
                    itemsArr.put(
                        JSONObject().apply {
                            put("id", item.id)
                            put("filePath", item.filePath)
                            put("title", item.title)
                            put("source", item.source)
                            put("addedAtMs", item.addedAtMs)
                        }
                    )
                }
                put("items", itemsArr)
            }
            arr.put(fObj)
        }
        indexFile.writeText(arr.toString())
    }

    private fun decodeScaledBitmapFromUri(uri: Uri, maxDim: Int): Bitmap? {
        return com.truelens.camera.camera.ExifBitmapLoader.loadScaled(context, uri, maxDim)
    }
}
