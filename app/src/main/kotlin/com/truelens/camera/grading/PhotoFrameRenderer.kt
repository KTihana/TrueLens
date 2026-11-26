package com.truelens.camera.grading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class ExportFrameStyle(val label: String) {
    NONE("None"),
    POLAROID_SPECS("Polaroid"),
    MUSEUM_WHITE("Museum"),
    CINEMA_DARK("Cinema")
}

enum class DateStampStyle(val label: String) {
    OFF("Off"),
    ANALOG_98("'98 Film"),
    CLEAN("Minimal")
}

enum class DualCameraLayout(val label: String) {
    SPLIT("Split"),
    PIP("Call PiP")
}

data class PhotoExifSummary(
    val focalMm: String? = null,
    val aperture: String? = null,
    val shutter: String? = null,
    val iso: String? = null,
    val dateTakenMs: Long = System.currentTimeMillis()
) {
    fun formattedSpecLine(): String {
        val parts = buildList {
            focalMm?.let { add(it) }
            aperture?.let { add(it) }
            shutter?.let { add(it) }
            iso?.let { add(it) }
        }
        return if (parts.isNotEmpty()) {
            parts.joinToString("  •  ")
        } else {
            "24mm  •  f/1.8  •  True Optics"
        }
    }
}

object PhotoFrameRenderer {

    fun readExifSummary(context: Context, uri: Uri?): PhotoExifSummary {
        if (uri == null) return PhotoExifSummary()
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val focalRaw = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                val focalStr = if (focalRaw > 0.0) {
                    val eq35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                    if (eq35 > 0) "${eq35}mm" else "${focalRaw.roundToInt()}mm"
                } else null

                val fNum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
                val apertureStr = if (fNum > 0.0) String.format(Locale.US, "f/%.1f", fNum) else null

                val expSec = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
                val shutterStr = if (expSec > 0.0) {
                    if (expSec >= 0.5) String.format(Locale.US, "%.1fs", expSec)
                    else "1/${(1.0 / expSec).roundToInt().coerceAtLeast(1)}s"
                } else null

                val isoRaw = exif.getAttribute("PhotographicSensitivity")
                    ?: exif.getAttribute("ISOSpeedRatings")
                val isoStr = isoRaw?.toIntOrNull()?.let { "ISO $it" }

                val dateAttr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val parsedMs = dateAttr?.let {
                    runCatching {
                        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time
                    }.getOrNull()
                } ?: System.currentTimeMillis()

                PhotoExifSummary(
                    focalMm = focalStr,
                    aperture = apertureStr,
                    shutter = shutterStr,
                    iso = isoStr,
                    dateTakenMs = parsedMs
                )
            } ?: PhotoExifSummary()
        } catch (_: Exception) {
            PhotoExifSummary()
        }
    }

    /**
     * Stamps an authentic glowing orange quartz analog film date ('26 11 26) or clean minimal date
     * onto a copy of [src]. Returns [src] untouched if [style] == DateStampStyle.OFF.
     */
    fun applyDateStamp(
        src: Bitmap,
        style: DateStampStyle,
        dateMs: Long = System.currentTimeMillis()
    ): Bitmap {
        if (style == DateStampStyle.OFF) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val minDim = minOf(out.width, out.height).toFloat()

        when (style) {
            DateStampStyle.ANALOG_98 -> {
                val stampText = SimpleDateFormat("''yy  MM  dd", Locale.US).format(Date(dateMs))
                val textSizePx = (minDim * 0.042f).coerceIn(18f, 110f)
                val marginX = out.width * 0.05f
                val marginY = out.height * 0.05f

                // Outer red-orange film halation glow
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(175, 255, 62, 18)
                    textSize = textSizePx
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textAlign = Paint.Align.RIGHT
                    letterSpacing = 0.08f
                    maskFilter = BlurMaskFilter((textSizePx * 0.22f).coerceAtLeast(3f), BlurMaskFilter.Blur.NORMAL)
                }
                // Warm glowing quartz amber core
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(242, 255, 134, 42)
                    textSize = textSizePx
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textAlign = Paint.Align.RIGHT
                    letterSpacing = 0.08f
                }
                val x = out.width - marginX
                val y = out.height - marginY
                canvas.drawText(stampText, x, y, glowPaint)
                canvas.drawText(stampText, x, y, corePaint)
            }
            DateStampStyle.CLEAN -> {
                val stampText = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(dateMs))
                val textSizePx = (minDim * 0.030f).coerceIn(14f, 80f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(220, 255, 255, 255)
                    textSize = textSizePx
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                    textAlign = Paint.Align.RIGHT
                    letterSpacing = 0.12f
                    setShadowLayer(textSizePx * 0.18f, 0f, 2f, Color.argb(140, 0, 0, 0))
                }
                canvas.drawText(stampText, out.width * 0.955f, out.height * 0.955f, paint)
            }
            DateStampStyle.OFF -> Unit
        }
        return out
    }

    /**
     * Wraps [src] in a Polaroid with EXIF camera specs, a Museum White gallery mat, or Cinema Dark border.
     */
    fun applyExportFrame(
        src: Bitmap,
        frameStyle: ExportFrameStyle,
        exif: PhotoExifSummary
    ): Bitmap {
        if (frameStyle == ExportFrameStyle.NONE) return src

        val w = src.width
        val h = src.height
        val sidePad: Int
        val topPad: Int
        val bottomPad: Int
        val bgColor: Int
        val primaryTextColor: Int
        val secondaryTextColor: Int
        val innerBorderColor: Int

        when (frameStyle) {
            ExportFrameStyle.POLAROID_SPECS -> {
                sidePad = (w * 0.06f).roundToInt().coerceAtLeast(16)
                topPad = sidePad
                bottomPad = (maxOf(w, h) * 0.19f).roundToInt().coerceAtLeast(54)
                bgColor = Color.rgb(250, 248, 244) // Warm instant-film paper white
                primaryTextColor = Color.rgb(24, 24, 28)
                secondaryTextColor = Color.rgb(112, 114, 122)
                innerBorderColor = Color.argb(26, 0, 0, 0)
            }
            ExportFrameStyle.MUSEUM_WHITE -> {
                sidePad = (w * 0.048f).roundToInt().coerceAtLeast(14)
                topPad = sidePad
                bottomPad = (maxOf(w, h) * 0.125f).roundToInt().coerceAtLeast(42)
                bgColor = Color.WHITE
                primaryTextColor = Color.rgb(20, 20, 24)
                secondaryTextColor = Color.rgb(118, 120, 128)
                innerBorderColor = Color.argb(18, 0, 0, 0)
            }
            ExportFrameStyle.CINEMA_DARK -> {
                sidePad = (w * 0.045f).roundToInt().coerceAtLeast(14)
                topPad = sidePad
                bottomPad = (maxOf(w, h) * 0.125f).roundToInt().coerceAtLeast(42)
                bgColor = Color.rgb(11, 11, 14)
                primaryTextColor = Color.rgb(244, 244, 248)
                secondaryTextColor = Color.rgb(154, 156, 168)
                innerBorderColor = Color.argb(38, 255, 255, 255)
            }
            ExportFrameStyle.NONE -> return src
        }

        val outW = w + sidePad * 2
        val outH = h + topPad + bottomPad
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(bgColor)

        // Draw photo cleanly at 1:1 untouched aspect ratio
        canvas.drawBitmap(src, sidePad.toFloat(), topPad.toFloat(), null)

        // Subtle recessed photo hairline
        val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = innerBorderColor
            style = Paint.Style.STROKE
            strokeWidth = (outW * 0.0018f).coerceAtLeast(1f)
        }
        canvas.drawRect(
            sidePad.toFloat(),
            topPad.toFloat(),
            (sidePad + w).toFloat(),
            (topPad + h).toFloat(),
            hairline
        )

        // Typography inside bottom chin
        val chinCenterY = topPad + h + bottomPad * 0.50f
        val brandSize = (bottomPad * 0.175f).coerceIn(13f, 76f)
        val specSize = (bottomPad * 0.145f).coerceIn(11f, 62f)
        val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(exif.dateTakenMs))

        if (frameStyle == ExportFrameStyle.POLAROID_SPECS) {
            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryTextColor
                textSize = brandSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.14f
                textAlign = Paint.Align.LEFT
            }
            val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryTextColor
                textSize = specSize
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
            }
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryTextColor
                textSize = specSize * 0.95f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }
            val leftX = sidePad.toFloat()
            val rightX = (sidePad + w).toFloat()
            canvas.drawText("TRUELENS", leftX, chinCenterY - brandSize * 0.25f, brandPaint)
            canvas.drawText(exif.formattedSpecLine(), leftX, chinCenterY + specSize * 1.15f, specPaint)
            canvas.drawText(dateStr, rightX, chinCenterY - brandSize * 0.25f, datePaint)
        } else {
            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryTextColor
                textSize = brandSize * 1.05f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.16f
                textAlign = Paint.Align.CENTER
            }
            val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryTextColor
                textSize = specSize
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            val cx = outW / 2f
            canvas.drawText("TRUELENS", cx, chinCenterY - brandSize * 0.15f, brandPaint)
            canvas.drawText("${exif.formattedSpecLine()}  •  $dateStr", cx, chinCenterY + specSize * 1.25f, specPaint)
        }

        return out
    }

    /**
     * Composites a Back + Front Dual-Camera Reaction photo in either:
     * - [DualCameraLayout.SPLIT]: Top/Bottom split at exact user-dragged [splitRatio] (0.20..0.80)
     * - [DualCameraLayout.PIP]: Full-frame back photo + floating rounded phone-call front PiP bubble
     *   at normalized ([pipX], [pipY]) with clean white outline and soft shadow.
     */
    fun composeDualPhoto(
        backBitmap: Bitmap,
        frontBitmap: Bitmap,
        layout: DualCameraLayout,
        splitRatio: Float = 0.5f,
        pipX: Float = 0.68f,
        pipY: Float = 0.06f,
        swapCameras: Boolean = false
    ): Bitmap {
        val primary = if (swapCameras) frontBitmap else backBitmap
        val secondary = if (swapCameras) backBitmap else frontBitmap
        val outW = primary.width.coerceAtLeast(1080)
        val outH = primary.height.coerceAtLeast(1440)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        when (layout) {
            DualCameraLayout.SPLIT -> {
                val ratio = splitRatio.coerceIn(0.20f, 0.80f)
                val splitY = (outH * ratio).roundToInt()
                drawCenterCrop(canvas, primary, RectF(0f, 0f, outW.toFloat(), splitY.toFloat()))
                drawCenterCrop(canvas, secondary, RectF(0f, splitY.toFloat(), outW.toFloat(), outH.toFloat()))
                // Crisp subtle divider hairline
                val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(220, 255, 255, 255)
                    strokeWidth = (outH * 0.003f).coerceAtLeast(3f)
                }
                canvas.drawLine(0f, splitY.toFloat(), outW.toFloat(), splitY.toFloat(), divPaint)
            }
            DualCameraLayout.PIP -> {
                drawCenterCrop(canvas, primary, RectF(0f, 0f, outW.toFloat(), outH.toFloat()))
                val pipW = outW * 0.29f
                val pipH = pipW * (4f / 3f)
                val left = (pipX.coerceIn(0.03f, 0.68f) * outW)
                val top = (pipY.coerceIn(0.03f, 0.72f) * outH)
                val pipRect = RectF(left, top, left + pipW, top + pipH)
                val corner = pipW * 0.14f

                // Soft shadow behind floating phone-call PiP card
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(140, 0, 0, 0)
                    maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(pipRect, corner, corner, shadowPaint)

                // Clipped rounded PiP photo
                canvas.save()
                val clipPath = Path().apply { addRoundRect(pipRect, corner, corner, Path.Direction.CW) }
                canvas.clipPath(clipPath)
                drawCenterCrop(canvas, secondary, pipRect)
                canvas.restore()

                // Crisp white border matching modern video-call PiP cards
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = (outW * 0.0045f).coerceAtLeast(3f)
                }
                canvas.drawRoundRect(pipRect, corner, corner, borderPaint)
            }
        }
        return out
    }

    private fun drawCenterCrop(canvas: Canvas, src: Bitmap, dst: RectF) {
        if (dst.width() <= 0f || dst.height() <= 0f) return
        val srcRatio = src.width.toFloat() / src.height.coerceAtLeast(1).toFloat()
        val dstRatio = dst.width() / dst.height()
        val matrix = Matrix()
        val scale: Float
        var dx = 0f
        var dy = 0f
        if (srcRatio > dstRatio) {
            scale = dst.height() / src.height.toFloat()
            dx = (dst.width() - src.width * scale) * 0.5f
        } else {
            scale = dst.width() / src.width.toFloat()
            dy = (dst.height() - src.height * scale) * 0.5f
        }
        matrix.setScale(scale, scale)
        matrix.postTranslate(dst.left + dx, dst.top + dy)

        canvas.save()
        canvas.clipRect(dst)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
    }
}
