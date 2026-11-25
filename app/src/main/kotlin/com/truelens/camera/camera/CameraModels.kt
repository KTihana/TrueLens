package com.truelens.camera.camera

import android.graphics.Rect
import android.net.Uri
import android.util.Range
import android.util.Rational
import android.util.Size

enum class ExposureMode(val shortLabel: String, val displayName: String) {
    AUTO_P("P", "Program Auto"),
    SHUTTER_TV("Tv", "Shutter Priority"),
    MANUAL_M("M", "Full Manual")
}

enum class CaptureFormat(val badge: String, val description: String) {
    RAW_PLUS_JPG("RAW+JPG", "12/14-bit Bayer DNG + Graded JPEG"),
    RAW_ONLY("RAW", "Pure Sensor Bayer DNG Only"),
    CLEAN_JPG("CLEAN", "Zero-AI Graded JPEG")
}

enum class ShootingMode(val label: String) {
    PHOTO("PHOTO"),
    VIDEO("VIDEO"),
    SLOW_MO("SLOW-MO"),
    STOP_MO("STOP-MO"),
    LONG_EXP("BULB"),
    SUPER_MACRO("MACRO"),
    PANORAMA("PANO"),
    TIMELAPSE("TIMELAPSE"),
    SCAN_DOC("SCAN")
}

enum class StabilizerMode(val label: String, val description: String) {
    OFF("STAB OFF", "No stabilization"),
    STANDARD("OIS+EIS", "Hardware optical/electronic stabilization"),
    SUPER_STEADY("SUPER STEADY", "Universal Gyro + OIS/EIS Super Steady")
}

enum class AebMode(val label: String, val evOffsetSteps: Int) {
    OFF("AEB OFF", 0),
    EV_1("AEB ±1", 3),
    EV_2("AEB ±2", 6)
}

enum class DocumentScanStyle(val label: String) {
    COLOR_DOC("Color Doc"),
    CRISP_BW("Crisp B&W")
}

enum class TimerDuration(val seconds: Int, val label: String) {
    OFF(0, "OFF"),
    THREE_S(3, "3s"),
    FIVE_S(5, "5s"),
    TEN_S(10, "10s")
}

enum class AspectRatioMask(val label: String, val widthOverHeight: Float?) {
    FULL("FULL", null),
    PORTRAIT_4_5("4:5", 4f / 5f),
    DSLR_3_2("3:2", 2f / 3f),
    CINEMA_16_9("16:9", 9f / 16f),
    XPAN("XPAN", 65f / 24f)
}

enum class SoloBurstCount(val count: Int, val label: String) {
    SINGLE(1, "1×"),
    BURST_3(3, "3×"),
    BURST_5(5, "5×")
}

enum class GridOverlay(val label: String) {
    NONE("Off"),
    RULE_OF_THIRDS("3×3"),
    GOLDEN_RATIO("Phi"),
    CROSSHAIR("Cross")
}

data class LensInfo(
    val cameraId: String,
    val isFront: Boolean,
    val focalLengthMm: Float,
    val aperture: Float,
    val minFocusDistanceDiopters: Float,
    val supportsRawSensor: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val isoRange: Range<Int>,
    val exposureTimeRangeNs: Range<Long>,
    val aeCompensationRange: Range<Int>,
    val aeCompensationStep: Rational,
    val activeArraySize: Rect,
    val sensorOrientation: Int,
    val previewSize: Size,
    val maxRawSize: Size?,
    val maxJpegSize: Size,
    val displayLabel: String
)

data class ManualSettings(
    val exposureMode: ExposureMode = ExposureMode.AUTO_P,
    val captureFormat: CaptureFormat = CaptureFormat.RAW_PLUS_JPG,
    val shootingMode: ShootingMode = ShootingMode.PHOTO,
    val iso: Int? = null,
    val shutterSpeedNs: Long? = null,
    val evCompensationSteps: Int = 0,
    val focusDistanceDiopters: Float? = null,
    val whiteBalanceKelvin: Int? = null,
    val zeroAiBypass: Boolean = true,
    val trueLightAuto: Boolean = true,
    val aeAfLocked: Boolean = false,
    val focusPeaking: Boolean = false,
    val zebras: Boolean = false,
    val histogram: Boolean = true,
    val levelGauge: Boolean = true,
    val gridOverlay: GridOverlay = GridOverlay.RULE_OF_THIRDS,
    val timerDuration: TimerDuration = TimerDuration.OFF,
    val soloBurstCount: SoloBurstCount = SoloBurstCount.SINGLE,
    val aspectRatioMask: AspectRatioMask = AspectRatioMask.FULL,
    val stabilizerMode: StabilizerMode = StabilizerMode.SUPER_STEADY,
    val aebMode: AebMode = AebMode.OFF,
    val scanStyle: DocumentScanStyle = DocumentScanStyle.COLOR_DOC,
    val mirrorFrontSave: Boolean = false,
    val macroFocusStackEnabled: Boolean = false,
    val splitScreenEnabled: Boolean = false,
    val friendMode: Boolean = false
)

data class LiveTelemetry(
    val iso: Int = 100,
    val shutterSpeedNs: Long = 16_666_666L,
    val aperture: Float = 1.8f,
    val focusDistanceDiopters: Float = 0f,
    val isRawActive: Boolean = false,
    val isAfLocked: Boolean = false
) {
    fun formattedShutter(): String {
        if (shutterSpeedNs <= 0L) return "AUTO"
        val seconds = shutterSpeedNs / 1_000_000_000.0
        return if (seconds >= 0.5) {
            String.format("%.1fs", seconds)
        } else {
            val denom = (1.0 / seconds).toInt().coerceAtLeast(1)
            "1/${denom}s"
        }
    }

    fun formattedAperture(): String = String.format("f/%.1f", aperture)
}

data class CapturedPhoto(
    val jpegUri: Uri?,
    val dngUri: Uri?,
    val filePath: String?,
    val timestampMs: Long,
    val iso: Int,
    val shutterSpeedNs: Long,
    val aperture: Float,
    val lensLabel: String,
    val zeroAiBypass: Boolean,
    val formatBadge: String
)
