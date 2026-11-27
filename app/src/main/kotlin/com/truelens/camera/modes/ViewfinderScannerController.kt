package com.truelens.camera.modes

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

enum class QrActionType {
    URL, WIFI, PHONE, EMAIL, SMS, TEXT
}

data class DetectedQrCode(
    val rawValue: String,
    val displayLabel: String,
    val actionType: QrActionType,
    val normalizedBox: RectF?, // 0..1 coordinates inside viewfinder
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val detectedAtMs: Long = System.currentTimeMillis()
)

data class DetectedLiveText(
    val fullText: String,
    val firstUrl: String? = null,
    val firstPhone: String? = null,
    val firstEmail: String? = null,
    val lineCount: Int = 0,
    val detectedAtMs: Long = System.currentTimeMillis()
)

/**
 * Lightweight on-device viewfinder analyzer powering both:
 * 1) iPhone-style Automatic QR Code Scanner (bounding brackets + compact suggestion pill)
 * 2) Live Text in Viewfinder (subtle corner [T] icon to copy/call/open/translate printed text)
 *
 * Uses Google Play Services dynamic ML Kit modules (~40 KB APK overhead, zero saved-photo AI).
 */
class ViewfinderScannerController(
    private val onQrDetected: (DetectedQrCode?) -> Unit,
    private val onTextDetected: (DetectedLiveText?) -> Unit
) {
    private val barcodeScanner by lazy {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(opts)
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    @Volatile private var busyQr = false
    @Volatile private var busyText = false
    private var frameTick = 0

    companion object {
        private const val TAG = "TrueLensScanner"
        private val URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
        private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        private val PHONE_REGEX = Regex("""(\+?\d[\d\s\-()]{6,}\d)""")

        fun formatUrlHost(rawUrl: String): String {
            return try {
                val parsed = Uri.parse(if (rawUrl.contains("://")) rawUrl else "https://$rawUrl")
                (parsed.host ?: rawUrl).removePrefix("www.").take(26)
            } catch (_: Exception) {
                rawUrl.take(26)
            }
        }

        fun parseQrPayload(
            rawValue: String,
            valueType: Int,
            normalizedBox: RectF?,
            wifiSsid: String? = null,
            wifiPassword: String? = null
        ): DetectedQrCode {
            val trimmed = rawValue.trim()
            return when {
                valueType == Barcode.TYPE_WIFI || trimmed.startsWith("WIFI:", ignoreCase = true) -> {
                    val ssid = wifiSsid ?: Regex("S:([^;]+)").find(trimmed)?.groupValues?.getOrNull(1) ?: "Wi-Fi"
                    val pass = wifiPassword ?: Regex("P:([^;]+)").find(trimmed)?.groupValues?.getOrNull(1)
                    DetectedQrCode(
                        rawValue = trimmed,
                        displayLabel = "Join $ssid",
                        actionType = QrActionType.WIFI,
                        normalizedBox = normalizedBox,
                        wifiSsid = ssid,
                        wifiPassword = pass
                    )
                }
                valueType == Barcode.TYPE_URL ||
                    trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> {
                    DetectedQrCode(
                        rawValue = trimmed,
                        displayLabel = formatUrlHost(trimmed),
                        actionType = QrActionType.URL,
                        normalizedBox = normalizedBox
                    )
                }
                valueType == Barcode.TYPE_PHONE || trimmed.startsWith("tel:", ignoreCase = true) -> {
                    val num = trimmed.removePrefix("tel:").removePrefix("TEL:").trim()
                    DetectedQrCode(
                        rawValue = num,
                        displayLabel = "Call ${num.take(18)}",
                        actionType = QrActionType.PHONE,
                        normalizedBox = normalizedBox
                    )
                }
                valueType == Barcode.TYPE_EMAIL || trimmed.startsWith("mailto:", ignoreCase = true) -> {
                    val mail = trimmed.removePrefix("mailto:").removePrefix("MAILTO:").substringBefore("?").trim()
                    DetectedQrCode(
                        rawValue = mail,
                        displayLabel = mail.take(24),
                        actionType = QrActionType.EMAIL,
                        normalizedBox = normalizedBox
                    )
                }
                valueType == Barcode.TYPE_SMS || trimmed.startsWith("smsto:", ignoreCase = true) -> {
                    val target = trimmed.removePrefix("smsto:").removePrefix("SMSTO:").substringBefore(":").trim()
                    DetectedQrCode(
                        rawValue = trimmed,
                        displayLabel = "Message ${target.take(16)}",
                        actionType = QrActionType.SMS,
                        normalizedBox = normalizedBox
                    )
                }
                else -> {
                    DetectedQrCode(
                        rawValue = trimmed,
                        displayLabel = trimmed.replace('\n', ' ').take(24),
                        actionType = QrActionType.TEXT,
                        normalizedBox = normalizedBox
                    )
                }
            }
        }

        fun extractLiveTextInfo(rawText: String): DetectedLiveText? {
            val cleaned = rawText.trim()
            if (cleaned.length < 4) return null
            val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            return DetectedLiveText(
                fullText = lines.joinToString("\n"),
                firstUrl = URL_REGEX.find(cleaned)?.value,
                firstEmail = EMAIL_REGEX.find(cleaned)?.value,
                firstPhone = PHONE_REGEX.find(cleaned)?.value?.trim(),
                lineCount = lines.size
            )
        }
    }

    /**
     * Processes a sampled preview frame. Takes ownership of [bitmap] and recycles it cleanly when done.
     */
    fun analyzeFrame(
        bitmap: Bitmap,
        scanQr: Boolean,
        scanText: Boolean
    ) {
        if (!scanQr && !scanText) {
            bitmap.recycle()
            return
        }
        frameTick++
        // Alternate / throttle Live Text to ~1/2 rate of QR so CPU stays ice-cold
        val runQr = scanQr && !busyQr
        val runText = scanText && !busyText && (frameTick % 2 == 0)

        if (!runQr && !runText) {
            bitmap.recycle()
            return
        }

        val w = bitmap.width.coerceAtLeast(1).toFloat()
        val h = bitmap.height.coerceAtLeast(1).toFloat()
        val input = InputImage.fromBitmap(bitmap, 0)
        var pending = (if (runQr) 1 else 0) + (if (runText) 1 else 0)
        val lock = Any()

        fun releaseOne() {
            synchronized(lock) {
                pending--
                if (pending <= 0) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }

        if (runQr) {
            busyQr = true
            barcodeScanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val qr = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    if (qr != null) {
                        val box = qr.boundingBox?.let { r ->
                            RectF(
                                (r.left / w).coerceIn(0f, 1f),
                                (r.top / h).coerceIn(0f, 1f),
                                (r.right / w).coerceIn(0f, 1f),
                                (r.bottom / h).coerceIn(0f, 1f)
                            )
                        }
                        onQrDetected(
                            parseQrPayload(
                                rawValue = qr.rawValue!!,
                                valueType = qr.valueType,
                                normalizedBox = box,
                                wifiSsid = qr.wifi?.ssid,
                                wifiPassword = qr.wifi?.password
                            )
                        )
                    } else {
                        onQrDetected(null)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "QR scan skipped (module may still be loading): ${e.message}")
                }
                .addOnCompleteListener {
                    busyQr = false
                    releaseOne()
                }
        }

        if (runText) {
            busyText = true
            textRecognizer.process(input)
                .addOnSuccessListener { visionText ->
                    onTextDetected(extractLiveTextInfo(visionText.text))
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Live Text skipped: ${e.message}")
                }
                .addOnCompleteListener {
                    busyText = false
                    releaseOne()
                }
        }
    }

    fun release() {
        try { barcodeScanner.close() } catch (_: Exception) {}
        try { textRecognizer.close() } catch (_: Exception) {}
    }
}
