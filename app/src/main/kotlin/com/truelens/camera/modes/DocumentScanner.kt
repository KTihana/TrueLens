package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.truelens.camera.camera.DocumentScanStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class ScannedDocumentResult(
    val pdfUri: Uri?,
    val jpegUri: Uri?,
    val style: DocumentScanStyle
)

/**
 * Pure-math Document Scanner (zero AI text hallucination).
 * Crops to the viewfinder document frame, normalizes white paper illumination,
 * boosts ink/text contrast (Color Doc or Crisp B&W), and saves BOTH a real PDF
 * in Documents/TrueLens and a crisp JPEG in Pictures/TrueLens.
 */
object DocumentScanner {

    suspend fun enhanceAndSave(
        context: Context,
        source: Bitmap,
        style: DocumentScanStyle
    ): ScannedDocumentResult = withContext(Dispatchers.Default) {
        // Crop to the 84% central document alignment guide region
        val marginX = (source.width * 0.08f).toInt()
        val marginY = (source.height * 0.10f).toInt()
        val cropW = (source.width - marginX * 2).coerceAtLeast(100)
        val cropH = (source.height - marginY * 2).coerceAtLeast(100)
        val cropped = Bitmap.createBitmap(source, marginX, marginY, cropW, cropH)

        val enhanced = enhanceDocumentBitmap(cropped, style)
        if (cropped !== source) cropped.recycle()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pdfUri = saveAsPdf(context, enhanced, "TrueLens_Scan_$stamp.pdf")
        val jpegUri = saveAsJpeg(context, enhanced, "TrueLens_Scan_$stamp.jpg")
        enhanced.recycle()

        ScannedDocumentResult(pdfUri = pdfUri, jpegUri = jpegUri, style = style)
    }

    private fun enhanceDocumentBitmap(src: Bitmap, style: DocumentScanStyle): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Estimate paper white point (92nd percentile luminance) & ink black point (5th percentile)
        val hist = IntArray(256)
        for (i in pixels.indices step 4) {
            val c = pixels[i]
            val l = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
            hist[l]++
        }
        val total = hist.sum().coerceAtLeast(1)
        var acc = 0
        var blackPt = 25
        var whitePt = 220
        for (i in 0..255) {
            acc += hist[i]
            if (acc <= total * 0.05f) blackPt = i
            if (acc <= total * 0.90f) whitePt = i
        }
        val span = max(40, whitePt - blackPt).toFloat()

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF

            if (style == DocumentScanStyle.CRISP_BW) {
                val luma = (r * 77 + g * 150 + b * 29) ushr 8
                val norm = ((luma - blackPt) / span).coerceIn(0f, 1f)
                // Crisp S-curve for razor-sharp ink on pure white paper
                val crisp = when {
                    norm > 0.72f -> 1f
                    norm < 0.25f -> norm * 0.4f
                    else -> ((norm - 0.25f) / 0.47f)
                }
                val v = (crisp * 255f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                // Color Document: brighten paper to clean white while preserving colored ink/stamps
                val nr = (((r - blackPt) / span).coerceIn(0f, 1f) * 1.06f).coerceIn(0f, 1f)
                val ng = (((g - blackPt) / span).coerceIn(0f, 1f) * 1.06f).coerceIn(0f, 1f)
                val nb = (((b - blackPt) / span).coerceIn(0f, 1f) * 1.06f).coerceIn(0f, 1f)
                // Gentle midtone contrast boost
                val cr = ((nr - 0.5f) * 1.18f + 0.52f).coerceIn(0f, 1f)
                val cg = ((ng - 0.5f) * 1.18f + 0.52f).coerceIn(0f, 1f)
                val cb = ((nb - 0.5f) * 1.18f + 0.52f).coerceIn(0f, 1f)
                val ri = (cr * 255f).toInt()
                val gi = (cg * 255f).toInt()
                val bi = (cb * 255f).toInt()
                pixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun saveAsPdf(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val pdf = PdfDocument()
            // Standard A4-proportion page scaled to crisp 1240px width
            val pageW = 1240
            val pageH = (pageW.toFloat() * bitmap.height / bitmap.width.coerceAtLeast(1)).toInt().coerceAtLeast(100)
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            canvas.drawBitmap(bitmap, null, Rect(0, 0, pageW, pageH), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/TrueLens")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                values
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    pdf.writeTo(out)
                }
            }
            pdf.close()
            uri
        } catch (_: Exception) {
            null
        }
    }

    private fun saveAsJpeg(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(out.toByteArray()) }
            uri
        } catch (_: Exception) {
            null
        }
    }
}
