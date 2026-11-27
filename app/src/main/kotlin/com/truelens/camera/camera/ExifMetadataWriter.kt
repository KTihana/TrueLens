package com.truelens.camera.camera

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes full hardware optical EXIF metadata (ISO, Shutter, Aperture, Focal Length,
 * Device Make/Model, Timestamp, Orientation) onto processed JPEGs so TrueLens photos
 * retain complete camera telemetry in Google Photos, Lightroom, and EXIF viewers.
 */
object ExifMetadataWriter {

    private const val TAG = "TrueLensExif"

    fun writeToMediaStoreUri(
        context: Context,
        uri: Uri,
        iso: Int,
        exposureTimeNs: Long,
        aperture: Float,
        focalLengthMm: Float,
        timestampMs: Long = System.currentTimeMillis(),
        manualWhiteBalance: Boolean = false,
        softwareTag: String = "TrueLens Optical"
    ) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(timestampMs))

                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, softwareTag)
                exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())

                if (iso > 0) {
                    @Suppress("DEPRECATION")
                    exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
                }
                if (exposureTimeNs > 0L) {
                    val sec = exposureTimeNs.toDouble() / 1_000_000_000.0
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.6f", sec))
                }
                if (aperture > 0f) {
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.2f", aperture))
                }
                if (focalLengthMm > 0f) {
                    val num = (focalLengthMm * 100f + 0.5f).toInt().coerceAtLeast(1)
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "$num/100")
                }
                // EXIF spec WhiteBalance tag: 0 = Auto, 1 = Manual
                exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, if (manualWhiteBalance) "1" else "0")
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal EXIF write skipped: ${e.message}")
        }
    }
}
