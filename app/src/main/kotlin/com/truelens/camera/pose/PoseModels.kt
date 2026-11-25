package com.truelens.camera.pose

import android.graphics.Bitmap

enum class PoseBlendMode(val label: String) {
    GHOST_PHOTO("Ghost"),
    SUBJECT_CUTOUT("Cutout"),
    NEON_OUTLINE("Outline")
}

data class PoseInspoItem(
    val id: String,
    val folderId: String,
    val filePath: String,
    val title: String = "",
    val source: String = "Gallery", // "Gallery", "Share", "Pinterest"
    val addedAtMs: Long = System.currentTimeMillis()
)

data class PoseFolder(
    val id: String,
    val name: String,
    val sourceUrl: String? = null,
    val isPinterestBoard: Boolean = false,
    val items: List<PoseInspoItem> = emptyList()
)

data class PoseOverlayState(
    val activeItem: PoseInspoItem? = null,
    val fullBitmap: Bitmap? = null,
    val cutoutBitmap: Bitmap? = null,
    val outlineBitmap: Bitmap? = null,
    val blendMode: PoseBlendMode = PoseBlendMode.GHOST_PHOTO,
    val opacity: Float = 0.48f,
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val isMirrored: Boolean = false,
    val isLocked: Boolean = false,
    val isHidden: Boolean = false,
    val isProcessingCutout: Boolean = false
) {
    val isActive: Boolean get() = activeItem != null && fullBitmap != null

    val currentBitmap: Bitmap?
        get() = when (blendMode) {
            PoseBlendMode.GHOST_PHOTO -> fullBitmap
            PoseBlendMode.SUBJECT_CUTOUT -> cutoutBitmap ?: fullBitmap
            PoseBlendMode.NEON_OUTLINE -> outlineBitmap ?: fullBitmap
        }
}
