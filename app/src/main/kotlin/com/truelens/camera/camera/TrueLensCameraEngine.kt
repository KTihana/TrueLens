package com.truelens.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import java.io.ByteArrayInputStream
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import com.truelens.camera.gl.AspectRatioMath
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.ComputationalDenoiser
import com.truelens.camera.grading.CubeLut
import com.truelens.camera.grading.LightroomGrade
import com.truelens.camera.grading.OpticalHdrEngine
import com.truelens.camera.grading.UltraHdrEncoder
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure Camera2 engine engineered to bypass OEM AI filters, beauty smoothing, and
 * aggressive sharpening by requesting RAW_SENSOR (DNG) + clean manual sensor pipelines.
 */
class TrueLensCameraEngine(
    private val context: Context,
    private val onTelemetry: (LiveTelemetry) -> Unit,
    private val onHistogram: (FloatArray) -> Unit,
    private val onPhotoCaptured: (CapturedPhoto) -> Unit,
    private val onError: (String) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var jpegReader: ImageReader? = null
    @Volatile private var rawReader: ImageReader? = null

    @Volatile private var activeLens: LensInfo? = null
    @Volatile private var activeCharacteristics: CameraCharacteristics? = null
    private var supportedEdgeModes: IntArray = IntArray(0)
    private var supportedNrModes: IntArray = IntArray(0)
    private var supportsOis: Boolean = false
    private var supportedAberrationModes: IntArray = IntArray(0)
    private var supportedDistortionModes: IntArray = IntArray(0)
    private var supportedShadingModes: IntArray = IntArray(0)
    private var supportedAntibandingModes: IntArray = IntArray(0)
    private var supportedHotPixelModes: IntArray = IntArray(0)
    private var currentSettings: ManualSettings = ManualSettings()
    private var currentGrade: LightroomGrade = LightroomGrade.NEUTRAL
    private var zoomRatio: Float = 1.0f

    private var lastTelemetryTime = 0L
    @Volatile private var lastLiveIso: Int = 200
    @Volatile private var lastLiveExpNs: Long = 16_666_666L
    @Volatile private var lastAfState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE
    @Volatile private var latestGyroSpeedRadSec: Float = 0f

    // Thread-safe FIFO Capture Queue (mutated EXCLUSIVELY on backgroundHandler — ZERO dropped clicks!)
    private data class PendingCaptureJob(
        val id: Long,
        var expectedJpegCount: Int,
        var expectRaw: Boolean,
        val isCleanBurst: Boolean,
        val isOpticalHdr: Boolean = false,
        val grade: LightroomGrade,
        val lut: CubeLut?,
        val settings: ManualSettings,
        val lens: LensInfo,
        val characteristics: CameraCharacteristics?,
        val jpegFrames: MutableList<ByteArray> = mutableListOf(),
        val jpegResults: MutableList<TotalCaptureResult> = mutableListOf(),
        val frameShakeScores: MutableList<Float> = mutableListOf(),
        var rawImage: Image? = null,
        var rawResult: TotalCaptureResult? = null,
        var retryCount: Int = 0,
        var finalized: Boolean = false,
        var watchdogRunnable: Runnable? = null
    )

    private data class MemoryFrameJob(
        val id: Long,
        val callback: (Bitmap?) -> Unit,
        var timeoutRunnable: Runnable? = null
    )

    private data class QueuedShutterRequest(
        val grade: LightroomGrade,
        val settings: ManualSettings,
        val lut: CubeLut?,
        val queuedAtMs: Long
    )

    private var sessionHasRawOutput: Boolean = false
    private val captureIdCounter = AtomicLong(1L)
    private val fileSeqCounter = AtomicLong(0L)
    private val activeCaptureJobs = ArrayDeque<PendingCaptureJob>()
    private val deferredCaptureQueue = ArrayDeque<QueuedShutterRequest>()
    private val pendingMemoryJobs = ArrayDeque<MemoryFrameJob>()
    private var queuedShutterRequest: QueuedShutterRequest? = null
    private var queuedShutterTimeout: Runnable? = null

    // Video & Slow-Mo Recording state
    private var mediaRecorder: MediaRecorder? = null
    private var recordingSurface: Surface? = null
    private var recordingPfd: ParcelFileDescriptor? = null
    private var recordingUri: Uri? = null
    private var isRecordingVideo: Boolean = false

    fun enumerateLenses(): List<LensInfo> {
        val lenses = mutableListOf<LensInfo>()
        try {
            val ids = cameraManager.cameraIdList
            val rearFocals = mutableListOf<Pair<String, Float>>()

            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focals?.firstOrNull() ?: 4.0f
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rearFocals.add(id to focal)
                }
            }

            // Pick the primary "1x" reference focal length: prefer default back camera ("0" / first rear ID)
            // rather than minimum focal length, so ultrawide lenses (<3mm) don't get mislabeled as "1x"!
            val refFocal = rearFocals.firstOrNull { it.first == "0" }?.second
                ?: rearFocals.firstOrNull()?.second
                ?: 4.0f

            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing != CameraCharacteristics.LENS_FACING_BACK &&
                    facing != CameraCharacteristics.LENS_FACING_FRONT
                ) continue

                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 4.0f
                val aperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull() ?: 1.8f
                val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                val supportsManual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                val supportsPostProc = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

                val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?: Range(50, 3200)
                val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?: Range(100_000L, 1_000_000_000L)
                val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    ?: Range(-12, 12)
                val aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    ?: Rational(1, 3)
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?: Rect(0, 0, 1920, 1080)
                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                val maxRaw = rawSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
                val maxJpeg = jpegSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: Size(1920, 1080)
                val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)
                val matchedPreview = AspectRatioMath.chooseMatchingPreviewSize(previewSizes, maxJpeg)

                val label = if (isFront) {
                    "Front"
                } else {
                    val ratio = focal / refFocal
                    when {
                        ratio < 0.75f -> "0.6x"
                        ratio < 1.45f -> "1x"
                        ratio < 2.6f -> "2x"
                        else -> String.format(Locale.US, "%.0fx", ratio)
                    }
                }

                val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null
                val minZoom = (zoomRange?.lower ?: 1.0f).coerceAtLeast(0.5f)
                val maxZoom = (zoomRange?.upper ?: 8.0f).coerceIn(1.0f, 12.0f)

                lenses.add(
                    LensInfo(
                        cameraId = id,
                        isFront = isFront,
                        focalLengthMm = focal,
                        aperture = aperture,
                        minFocusDistanceDiopters = minFocus,
                        supportsRawSensor = supportsRaw && maxRaw != null,
                        supportsManualSensor = supportsManual,
                        supportsManualPostProcessing = supportsPostProc,
                        isoRange = isoRange,
                        exposureTimeRangeNs = expRange,
                        aeCompensationRange = aeRange,
                        aeCompensationStep = aeStep,
                        activeArraySize = activeArray,
                        sensorOrientation = sensorOrientation,
                        previewSize = matchedPreview,
                        maxRawSize = maxRaw,
                        maxJpegSize = maxJpeg,
                        displayLabel = label,
                        minZoomRatio = minZoom,
                        maxZoomRatio = maxZoom
                    )
                )
            }
            // Rear lenses sorted wide -> tele, then front camera
            lenses.sortWith(compareBy<LensInfo> { it.isFront }.thenBy { it.focalLengthMm })
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating lenses", e)
            onError("Could not enumerate cameras: ${e.message}")
        }
        return lenses
    }

    fun startThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("TrueLensCameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stop() {
        try {
            if (isRecordingVideo) {
                stopVideoRecording {}
            }
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            jpegReader?.close()
            jpegReader = null
            rawReader?.close()
            rawReader = null
            sessionHasRawOutput = false
            clearPendingCaptureQueues(notifyMemoryNull = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun clearPendingCaptureQueues(notifyMemoryNull: Boolean) {
        val h = backgroundHandler
        queuedShutterTimeout?.let { h?.removeCallbacks(it) }
        queuedShutterTimeout = null
        queuedShutterRequest = null
        deferredCaptureQueue.clear()

        while (activeCaptureJobs.isNotEmpty()) {
            val job = activeCaptureJobs.removeFirst()
            job.watchdogRunnable?.let { h?.removeCallbacks(it) }
            job.rawImage?.close()
            job.rawImage = null
            job.finalized = true
        }
        while (pendingMemoryJobs.isNotEmpty()) {
            val mem = pendingMemoryJobs.removeFirst()
            mem.timeoutRunnable?.let { h?.removeCallbacks(it) }
            if (notifyMemoryNull) {
                try { mem.callback(null) } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(lens: LensInfo, surface: Surface, settings: ManualSettings, grade: LightroomGrade) {
        startThread()
        this.activeLens = lens
        this.previewSurface = surface
        this.currentSettings = settings
        this.currentGrade = grade
        this.zoomRatio = 1.0f

        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null

            val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
            activeCharacteristics = chars
            supportedEdgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
            supportedNrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
            supportsOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            supportedAberrationModes = chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: IntArray(0)
            supportedDistortionModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: IntArray(0)
            } else IntArray(0)
            supportedShadingModes = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: IntArray(0)
            supportedAntibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: IntArray(0)
            supportedHotPixelModes = chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: IntArray(0)

            cameraManager.openCamera(
                lens.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        onError("Camera hardware error ($error)")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            onError("Failed to open camera: ${e.message}")
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val lens = activeLens ?: return
        val handler = backgroundHandler ?: return

        try {
            captureSession?.close()
            captureSession = null
            jpegReader?.close()
            rawReader?.close()

            val outputs = mutableListOf<Surface>()
            outputs.add(surface)

            // High-capacity JPEG reader (maxImages = 10 + acquireNextImage drain loop):
            // NEVER drops rapid shutter clicks or 5-frame Clean Look HDR+ bursts!
            jpegReader = ImageReader.newInstance(
                lens.maxJpegSize.width,
                lens.maxJpegSize.height,
                ImageFormat.JPEG,
                10
            ).apply {
                setOnImageAvailableListener({ reader ->
                    while (true) {
                        val image = try {
                            reader.acquireNextImage()
                        } catch (_: Exception) {
                            null
                        } ?: break
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            dispatchIncomingJpegOnHandler(bytes, lens)
                        } finally {
                            image.close()
                        }
                    }
                }, handler)
            }
            outputs.add(jpegReader!!.surface)

            // Only configure RAW_SENSOR output when user explicitly chooses RAW or RAW+JPG.
            // Keeping RAW out of the session in default CLEAN mode ensures Android HALs never
            // throttle or disable full hardware high-quality noise reduction on JPEGs!
            val rawSize = lens.maxRawSize
            val includeRawOutput = lens.supportsRawSensor &&
                rawSize != null &&
                currentSettings.captureFormat != CaptureFormat.CLEAN_JPG
            sessionHasRawOutput = includeRawOutput
            if (includeRawOutput && rawSize != null) {
                rawReader = ImageReader.newInstance(
                    rawSize.width,
                    rawSize.height,
                    ImageFormat.RAW_SENSOR,
                    4
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        while (true) {
                            val image = try {
                                reader.acquireNextImage()
                            } catch (_: Exception) {
                                null
                            } ?: break
                            dispatchIncomingRawOnHandler(image)
                        }
                    }, handler)
                }
                outputs.add(rawReader!!.surface)
            } else {
                rawReader = null
            }

            val outputConfigs = outputs.map { OutputConfiguration(it) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                context.mainExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updateRepeatingPreview()
                        // If the user pressed shutter while the session was opening/reconfiguring,
                        // fire the queued capture IMMEDIATELY so NO shutter click is ever dropped!
                        backgroundHandler?.post {
                            val queued = queuedShutterRequest
                            if (queued != null) {
                                queuedShutterTimeout?.let { backgroundHandler?.removeCallbacks(it) }
                                queuedShutterTimeout = null
                                queuedShutterRequest = null
                                if (System.currentTimeMillis() - queued.queuedAtMs <= 3500L) {
                                    submitPhotoCaptureOnHandler(queued.grade, queued.settings, queued.lut)
                                }
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("Failed to configure camera session")
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            onError("Capture session error: ${e.message}")
        }
    }

    fun updateSettings(settings: ManualSettings, grade: LightroomGrade) {
        this.currentSettings = settings
        this.currentGrade = grade
        val lens = activeLens
        val wantRawOutput = lens != null &&
            lens.supportsRawSensor &&
            lens.maxRawSize != null &&
            settings.captureFormat != CaptureFormat.CLEAN_JPG
        if (!isRecordingVideo && cameraDevice != null && previewSurface?.isValid == true && wantRawOutput != sessionHasRawOutput) {
            createCaptureSession()
        } else {
            updateRepeatingPreview()
        }
    }

    fun setZoom(ratio: Float) {
        val minZ = activeLens?.minZoomRatio ?: 1.0f
        val maxZ = activeLens?.maxZoomRatio ?: 8.0f
        this.zoomRatio = ratio.coerceIn(minZ, maxZ)
        updateRepeatingPreview()
    }

    /** Feeds live gyroscope angular speed (rad/s) for gyro-weighted Lucky Burst frame selection. */
    fun updateGyroSpeed(radPerSec: Float) {
        latestGyroSpeedRadSec = radPerSec
    }

    fun triggerTapToFocus(normX: Float, normY: Float) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val lens = activeLens ?: return
        val surface = previewSurface ?: return

        try {
            // Map portrait screen (normX, normY) into exact rotated sensor activeArraySize coordinates
            val nx = normX.coerceIn(0f, 1f)
            val ny = normY.coerceIn(0f, 1f)
            val adjX = if (lens.isFront) 1f - nx else nx
            val (sensorNormX, sensorNormY) = when (lens.sensorOrientation) {
                90 -> ny to (1f - adjX)
                180 -> (1f - adjX) to (1f - ny)
                270 -> (1f - ny) to adjX
                else -> adjX to ny
            }

            val activeRect = lens.activeArraySize
            val cx = (sensorNormX.coerceIn(0f, 1f) * activeRect.width()).toInt()
            val cy = (sensorNormY.coerceIn(0f, 1f) * activeRect.height()).toInt()
            val halfW = (activeRect.width() * 0.08f).toInt()
            val halfH = (activeRect.height() * 0.08f).toInt()
            val left = (cx - halfW).coerceIn(0, (activeRect.width() - 2).coerceAtLeast(0))
            val top = (cy - halfH).coerceIn(0, (activeRect.height() - 2).coerceAtLeast(0))
            val w = (halfW * 2).coerceAtMost(activeRect.width() - left).coerceAtLeast(1)
            val h = (halfH * 2).coerceAtMost(activeRect.height() - top).coerceAtLeast(1)
            val region = MeteringRectangle(left, top, w, h, MeteringRectangle.METERING_WEIGHT_MAX)

            val template = if (isRecordingVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = device.createCaptureRequest(template)
            builder.addTarget(surface)
            if (isRecordingVideo) {
                recordingSurface?.takeIf { it.isValid }?.let { builder.addTarget(it) }
            }
            applyRequestParameters(builder, currentSettings)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            if (currentSettings.focusDistanceDiopters == null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            session.capture(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Tap to focus failed", e)
        }
    }

    fun startVideoRecording(
        isSlowMo: Boolean,
        settings: ManualSettings,
        hasAudioPermission: Boolean,
        onStarted: (fpsLabel: String) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val device = cameraDevice ?: return onFailed("Camera not ready")
        val preview = previewSurface ?: return onFailed("Preview not ready")
        val lens = activeLens ?: return onFailed("No active lens")
        val chars = activeCharacteristics

        try {
            // Pick a hardware-supported MediaRecorder size matching chosen VideoQuality (graceful fallback)
            var captureFps = settings.videoQuality.targetFps
            var videoWidth = 1280
            var videoHeight = 720
            val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val recorderSizes = map?.getOutputSizes(MediaRecorder::class.java)
            val requestedW = settings.videoQuality.targetWidth
            val requestedH = settings.videoQuality.targetHeight
            val standardChosen = recorderSizes?.firstOrNull { it.width == requestedW && it.height == requestedH }
                ?: recorderSizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: recorderSizes?.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: recorderSizes?.filter { it.width <= 1920 && it.height <= 1080 }
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            if (standardChosen != null) {
                videoWidth = standardChosen.width
                videoHeight = standardChosen.height
            }
            // Validate target FPS (24fps cinema or 60fps smooth) against hardware AE target FPS ranges
            var chosenFpsRange: Range<Int>? = null
            if (!isSlowMo) {
                val fpsRanges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                if (captureFps == 24) {
                    val exact24 = fpsRanges?.firstOrNull { it.lower == 24 && it.upper == 24 }
                        ?: fpsRanges?.firstOrNull { it.lower <= 24 && it.upper == 24 }
                    if (exact24 != null) {
                        chosenFpsRange = exact24
                    } else {
                        // Hardware doesn't expose 24fps AE range: still encode smooth 24fps container!
                        chosenFpsRange = fpsRanges?.firstOrNull { it.upper == 30 }
                    }
                } else if (captureFps > 30) {
                    val supports60 = fpsRanges?.any { it.upper >= 60 } == true
                    if (!supports60) captureFps = 30
                    else chosenFpsRange = fpsRanges?.firstOrNull { it.upper >= 60 }
                }
            }

            if (isSlowMo && map != null) {
                val hsRanges = map.highSpeedVideoFpsRanges
                val bestRange = hsRanges?.maxByOrNull { it.upper }
                if (bestRange != null && bestRange.upper >= 120) {
                    captureFps = bestRange.upper.coerceAtMost(240)
                    val hsSizes = map.getHighSpeedVideoSizesFor(bestRange)
                    val chosen = hsSizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
                        ?: hsSizes?.firstOrNull { it.width == 1280 && it.height == 720 }
                        ?: hsSizes?.firstOrNull()
                    if (chosen != null) {
                        videoWidth = chosen.width
                        videoHeight = chosen.height
                    }
                } else {
                    captureFps = 60
                }
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix = if (isSlowMo) "TrueLens_SlowMo" else "TrueLens_Video"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${prefix}_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TrueLens")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return onFailed("Could not create video file")
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: return onFailed("Could not open video output")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            val audioMode = if (settings.micMuted) VideoAudioMode.MUTED else settings.videoAudioMode
            val recordAudio = hasAudioPermission && !isSlowMo && audioMode != VideoAudioMode.MUTED
            if (recordAudio) {
                // WIND_CUT uses VOICE_RECOGNITION (DSP wind & background noise suppression);
                // STEREO uses CAMCORDER (or MIC fallback) for rich stereo capture without grabbing music focus!
                val source = when (audioMode) {
                    VideoAudioMode.WIND_CUT -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                    else -> MediaRecorder.AudioSource.CAMCORDER
                }
                try {
                    recorder.setAudioSource(source)
                } catch (_: Exception) {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                }
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (recordAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                if (audioMode == VideoAudioMode.STEREO) {
                    recorder.setAudioChannels(2)
                    recorder.setAudioEncodingBitRate(192_000)
                    recorder.setAudioSamplingRate(48_000)
                } else {
                    recorder.setAudioChannels(1)
                    recorder.setAudioEncodingBitRate(128_000)
                    recorder.setAudioSamplingRate(48_000)
                }
            }
            val bitrate = if (isSlowMo) 24_000_000 else settings.videoQuality.bitRate
            recorder.setVideoEncodingBitRate(bitrate)
            recorder.setVideoSize(videoWidth, videoHeight)
            recorder.setVideoFrameRate(if (isSlowMo) 30 else captureFps)
            if (isSlowMo) {
                recorder.setCaptureRate(captureFps.toDouble())
            }
            recorder.setOrientationHint(lens.sensorOrientation)
            recorder.prepare()

            val recSurface = recorder.surface
            mediaRecorder = recorder
            recordingSurface = recSurface
            recordingPfd = pfd
            recordingUri = uri

            captureSession?.close()
            captureSession = null

            // Include jpegReader surface so user can snap crisp still photos mid-video (Feature #3)!
            val videoOutputs = mutableListOf(OutputConfiguration(preview), OutputConfiguration(recSurface))
            jpegReader?.surface?.takeIf { it.isValid }?.let { videoOutputs.add(OutputConfiguration(it)) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                videoOutputs,
                context.mainExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            req.addTarget(preview)
                            req.addTarget(recSurface)
                            applyRequestParameters(req, settings)
                            if (chosenFpsRange != null) {
                                req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosenFpsRange)
                            }
                            // Cinema 180° Shutter Angle Rule: lock shutter to 1/(2 * fps) (e.g. 1/48s at 24fps!)
                            if (!isSlowMo && settings.cinema180Shutter && lens.supportsManualSensor) {
                                val cinemaShutterNs = (1_000_000_000L / (2L * captureFps.coerceAtLeast(24)))
                                    .coerceIn(lens.exposureTimeRangeNs.lower, lens.exposureTimeRangeNs.upper)
                                req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, cinemaShutterNs)
                                req.set(
                                    CaptureRequest.SENSOR_SENSITIVITY,
                                    (settings.iso ?: 250).coerceIn(lens.isoRange.lower, lens.isoRange.upper)
                                )
                            }
                            // Enable hardware OIS + EIS stabilization whenever Stabilizer != OFF
                            if (settings.stabilizerMode != StabilizerMode.OFF) {
                                req.set(
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                                )
                                req.set(
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                                    } else {
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                    }
                                )
                            }
                            session.setRepeatingRequest(req.build(), null, backgroundHandler)
                            recorder.start()
                            isRecordingVideo = true
                            val resLabel = if (videoWidth >= 3800) "4K" else "${videoHeight}p"
                            val angleTag = if (!isSlowMo && settings.cinema180Shutter) " • 180°" else ""
                            val label = if (isSlowMo) "${captureFps}fps → 30fps" else "$resLabel ${captureFps}fps$angleTag"
                            onStarted(label)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start MediaRecorder", e)
                            cleanupRecorder()
                            createCaptureSession()
                            onFailed("Video start error: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cleanupRecorder()
                        createCaptureSession()
                        onFailed("Could not configure video session")
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Video setup error", e)
            cleanupRecorder()
            createCaptureSession()
            onFailed("Video error: ${e.message}")
        }
    }

    fun pauseVideoRecording(): Boolean {
        if (!isRecordingVideo) return false
        return try {
            mediaRecorder?.pause()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Pause video failed", e)
            false
        }
    }

    fun resumeVideoRecording(): Boolean {
        if (!isRecordingVideo) return false
        return try {
            mediaRecorder?.resume()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Resume video failed", e)
            false
        }
    }

    /** Returns live 0..32767 mic amplitude from active MediaRecorder for dB level bars while filming. */
    fun getRecordingMaxAmplitude(): Int {
        if (!isRecordingVideo) return 0
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** Captures a still JPEG snapshot without interrupting an active video recording! */
    fun captureVideoSnapshot(grade: LightroomGrade, settings: ManualSettings, lut: CubeLut? = null) {
        if (!isRecordingVideo) return
        startThread()
        val handler = backgroundHandler ?: return
        handler.post {
            val device = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val reader = jpegReader ?: return@post
            val lens = activeLens ?: return@post

            val job = PendingCaptureJob(
                id = captureIdCounter.getAndIncrement(),
                expectedJpegCount = 1,
                expectRaw = false,
                isCleanBurst = false,
                grade = grade,
                lut = lut,
                settings = settings,
                lens = lens,
                characteristics = activeCharacteristics
            )
            activeCaptureJobs.addLast(job)
            scheduleJobWatchdogOnHandler(job)

            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
                builder.addTarget(reader.surface)
                builder.setTag("JPEG_${job.id}")
                applyRequestParameters(builder, settings)
                builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                builder.set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)
                session.capture(builder.build(), createJobCaptureCallback(job), handler)
            } catch (e: Exception) {
                Log.w(TAG, "Mid-video snapshot failed", e)
                failAndRemoveJobOnHandler(job, "Video snapshot unavailable on this lens")
            }
        }
    }

    fun stopVideoRecording(onSaved: (Uri?) -> Unit) {
        val savedUri = recordingUri
        var stopSucceeded = false
        if (isRecordingVideo) {
            try {
                captureSession?.stopRepeating()
            } catch (_: Exception) {
            }
            try {
                mediaRecorder?.stop()
                stopSucceeded = true
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder stop warning (too short?)", e)
                stopSucceeded = false
            }
        }
        cleanupRecorder()
        val finalUri = if (savedUri != null && stopSucceeded) {
            try {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(savedUri, done, null, null)
            } catch (_: Exception) {
            }
            savedUri
        } else {
            if (savedUri != null) {
                try {
                    context.contentResolver.delete(savedUri, null, null)
                } catch (_: Exception) {
                }
            }
            null
        }
        createCaptureSession()
        onSaved(finalUri)
    }

    private fun cleanupRecorder() {
        isRecordingVideo = false
        recordingSurface = null
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        try {
            recordingPfd?.close()
        } catch (_: Exception) {
        }
        recordingPfd = null
        recordingUri = null
    }

    fun capturePanoramaFrame(settings: ManualSettings, onFrame: (Bitmap?) -> Unit) {
        startThread()
        val handler = backgroundHandler ?: return onFrame(null)
        handler.post {
            val device = cameraDevice
            val session = captureSession
            val reader = jpegReader
            val lens = activeLens
            if (device == null || session == null || reader == null || lens == null) {
                onFrame(null)
                return@post
            }

            val memJob = MemoryFrameJob(id = captureIdCounter.getAndIncrement(), callback = onFrame)
            // 3.5s safety timeout guarantees a failed memory frame NEVER lingers to hijack a future real photo!
            val timeout = Runnable {
                if (pendingMemoryJobs.remove(memJob)) {
                    Log.w(TAG, "Memory frame #${memJob.id} timed out — releasing callback cleanly")
                    onFrame(null)
                }
            }
            memJob.timeoutRunnable = timeout
            pendingMemoryJobs.addLast(memJob)
            handler.postDelayed(timeout, 3500L)

            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                builder.setTag("MEM_${memJob.id}")
                applyRequestParameters(builder, settings)
                builder.set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                builder.set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)
                session.capture(
                    builder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure
                        ) {
                            if (pendingMemoryJobs.remove(memJob)) {
                                memJob.timeoutRunnable?.let { handler.removeCallbacks(it) }
                                onFrame(null)
                            }
                        }
                    },
                    handler
                )
            } catch (e: Exception) {
                if (pendingMemoryJobs.remove(memJob)) {
                    memJob.timeoutRunnable?.let { handler.removeCallbacks(it) }
                }
                Log.e(TAG, "Panorama frame capture failed", e)
                onFrame(null)
            }
        }
    }

    /**
     * Zero-Drop Photo Capture:
     * - Runs all state transitions exclusively on [backgroundHandler] inside a FIFO queue.
     * - If the session is currently opening/reconfiguring, queues the click and fires immediately onConfigured().
     * - When Clean Look (EnhanceMode.CLEAN) is active and flash isn't forced ON, shoots a rapid 5-frame
     *   HDR+ burst (aligned patch-Wiener temporal merge + multi-scale wavelet denoiser) for native-camera-grade
     *   noise removal!
     * - Every job has a 3.5s watchdog + 1x automatic retry so a shutter click is NEVER silently dropped.
     */
    fun capturePhoto(grade: LightroomGrade, settings: ManualSettings, lut: CubeLut? = null) {
        startThread()
        val handler = backgroundHandler
        if (handler == null) {
            onError("Camera engine not ready — tap again")
            return
        }
        handler.post {
            submitPhotoCaptureOnHandler(grade, settings, lut)
        }
    }

    private fun submitPhotoCaptureOnHandler(
        grade: LightroomGrade,
        settings: ManualSettings,
        lut: CubeLut?,
        afWaitElapsedMs: Long = 0L
    ) {
        val handler = backgroundHandler ?: return
        val device = cameraDevice
        val session = captureSession
        val lens = activeLens

        // Session currently opening/reconfiguring? Queue shutter click and auto-fire onConfigured()!
        if (device == null || session == null || lens == null) {
            queuedShutterTimeout?.let { handler.removeCallbacks(it) }
            val req = QueuedShutterRequest(grade, settings, lut, System.currentTimeMillis())
            queuedShutterRequest = req
            val timeout = Runnable {
                if (queuedShutterRequest === req) {
                    queuedShutterRequest = null
                    queuedShutterTimeout = null
                    onError("Camera is still starting — tap again")
                }
            }
            queuedShutterTimeout = timeout
            handler.postDelayed(timeout, 3000L)
            return
        }

        // Smart PDAF Focus-Ready Gate (Tier 3):
        // 0ms delay when focus is already locked/converged or in Manual Focus; if PDAF is mid-sweep,
        // pause up to 180ms max so focus lands razor-sharp instead of firing mid-blur!
        val isAutoAf = settings.focusDistanceDiopters == null && settings.shootingMode != ShootingMode.SUPER_MACRO
        val afMidSweep = lastAfState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
            lastAfState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        if (isAutoAf && afMidSweep && afWaitElapsedMs < 180L && activeCaptureJobs.isEmpty()) {
            handler.postDelayed({
                submitPhotoCaptureOnHandler(grade, settings, lut, afWaitElapsedMs + 30L)
            }, 30L)
            return
        }

        // Serialize HAL capture submission (max 1 active capture/burst in-flight at the HAL) so we
        // NEVER overflow Android's REQUEST_PIPELINE_MAX_DEPTH on any phone! Extra rapid taps queue
        // in FIFO order and fire the exact millisecond the current HAL acquisition completes.
        if (activeCaptureJobs.isNotEmpty()) {
            if (deferredCaptureQueue.size < 8) {
                deferredCaptureQueue.addLast(QueuedShutterRequest(grade, settings, lut, System.currentTimeMillis()))
            }
            return
        }

        val reader = jpegReader
        val wantRaw = lens.supportsRawSensor && rawReader != null &&
            (settings.captureFormat == CaptureFormat.RAW_PLUS_JPG ||
                settings.captureFormat == CaptureFormat.RAW_ONLY)
        val wantJpeg = (!wantRaw || settings.captureFormat != CaptureFormat.RAW_ONLY) && reader != null
        if (!wantJpeg && !wantRaw) {
            onError("Camera output surface not ready — tap again")
            return
        }

        val isManualExposure = lens.supportsManualSensor &&
            (settings.exposureMode == ExposureMode.MANUAL_M ||
                (settings.exposureMode == ExposureMode.SHUTTER_TV && settings.shutterSpeedNs != null))
        val isSlowManualShutter = settings.shutterSpeedNs != null && settings.shutterSpeedNs >= 100_000_000L

        // Tier 2: Mertens 3-Frame Optical Exposure Fusion HDR (-1.3 EV, 0 EV, +1.0 EV)
        val useOpticalHdr = wantJpeg &&
            settings.opticalHdrEnabled &&
            !isManualExposure &&
            settings.flashMode != FlashMode.ON &&
            lens.aeCompensationRange.lower <= -2 &&
            lens.aeCompensationRange.upper >= 2

        // Tier 2/3: Clean Look 4-frame Sub-Pixel HDR+ Burst when not in Optical HDR bracket
        val useCleanBurst = wantJpeg &&
            !useOpticalHdr &&
            settings.enhanceMode == EnhanceMode.CLEAN &&
            settings.flashMode != FlashMode.ON &&
            !isSlowManualShutter

        val jpegCount = when {
            useOpticalHdr -> 3
            useCleanBurst -> CLEAN_BURST_FRAMES
            wantJpeg -> 1
            else -> 0
        }

        val job = PendingCaptureJob(
            id = captureIdCounter.getAndIncrement(),
            expectedJpegCount = jpegCount,
            expectRaw = wantRaw,
            isCleanBurst = useCleanBurst,
            isOpticalHdr = useOpticalHdr,
            grade = grade,
            lut = lut,
            settings = settings,
            lens = lens,
            characteristics = activeCharacteristics
        )
        activeCaptureJobs.addLast(job)
        scheduleJobWatchdogOnHandler(job)

        submitRequestsForJobOnHandler(job, device, session)
    }

    private fun submitRequestsForJobOnHandler(
        job: PendingCaptureJob,
        device: CameraDevice,
        session: CameraCaptureSession
    ) {
        val handler = backgroundHandler ?: return
        val callback = createJobCaptureCallback(job)
        try {
            val requests = ArrayList<CaptureRequest>(job.expectedJpegCount + if (job.expectRaw) 1 else 0)
            val jpegSurface = jpegReader?.surface
            if (job.expectedJpegCount > 0 && jpegSurface != null) {
                val baseEv = job.settings.evCompensationSteps
                val evStepSize = job.lens.aeCompensationStep.toFloat().coerceAtLeast(0.1f)
                val hdrOffsets = if (job.isOpticalHdr) {
                    val underSteps = (-1.35f / evStepSize).toInt().coerceAtMost(-2)
                    val overSteps = (1.0f / evStepSize).toInt().coerceAtLeast(2)
                    intArrayOf(underSteps, 0, overSteps)
                } else null

                for (i in 0 until job.expectedJpegCount) {
                    val frameSettings = if (hdrOffsets != null && i < hdrOffsets.size) {
                        val targetEv = (baseEv + hdrOffsets[i]).coerceIn(
                            job.lens.aeCompensationRange.lower,
                            job.lens.aeCompensationRange.upper
                        )
                        job.settings.copy(evCompensationSteps = targetEv)
                    } else job.settings

                    val jb = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(jpegSurface)
                        setTag("JPEG_${job.id}")
                        applyRequestParameters(this, frameSettings, isStillCapture = true)
                        // For multi-frame burst: FAST NR runs at ~30fps burst speed (~130ms total!) AND
                        // preserves real fine texture so our sub-pixel temporal Wiener merge beats single-frame HQ NR!
                        if (job.isCleanBurst || job.isOpticalHdr) {
                            if (supportedNrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                            }
                            if (supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_OFF)) {
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            }
                        }
                        set(CaptureRequest.JPEG_QUALITY, if (job.isCleanBurst || job.isOpticalHdr) 96.toByte() else 98.toByte())
                        set(CaptureRequest.JPEG_ORIENTATION, job.lens.sensorOrientation)
                    }
                    requests.add(jb.build())
                }
            }
            val rawSurface = rawReader?.surface
            if (job.expectRaw && rawSurface != null) {
                val rb = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(rawSurface)
                    setTag("RAW_${job.id}")
                    applyRequestParameters(this, job.settings, isStillCapture = true)
                }
                requests.add(rb.build())
            }

            if (requests.isEmpty()) {
                failAndRemoveJobOnHandler(job, "Capture target unavailable")
                return
            }
            if (requests.size == 1) {
                session.capture(requests[0], callback, handler)
            } else {
                session.captureBurst(requests, callback, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture submission failed for job #${job.id}", e)
            retryOrFailJobOnHandler(job, "Capture failed: ${e.message}")
        }
    }

    private fun createJobCaptureCallback(job: PendingCaptureJob): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                val tag = request.tag as? String ?: ""
                if (tag.startsWith("JPEG_")) {
                    // Snapshot live gyroscope angular velocity right as the shutter opens for Lucky Burst weighting!
                    job.frameShakeScores.add(latestGyroSpeedRadSec)
                }
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (job.finalized) return
                val tag = request.tag as? String ?: ""
                if (tag.startsWith("RAW_")) {
                    job.rawResult = result
                } else {
                    job.jpegResults.add(result)
                }
                tryFinalizeJobOnHandler(job, forcePartial = false)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                if (job.finalized) return
                val tag = request.tag as? String ?: ""
                Log.w(TAG, "Capture request failed (tag=$tag, reason=${failure.reason})")
                if (tag.startsWith("RAW_")) {
                    job.expectRaw = false
                    job.rawImage?.close()
                    job.rawImage = null
                    tryFinalizeJobOnHandler(job, forcePartial = false)
                } else {
                    if (job.expectedJpegCount > 1) {
                        job.expectedJpegCount = (job.expectedJpegCount - 1).coerceAtLeast(1)
                        tryFinalizeJobOnHandler(job, forcePartial = false)
                    } else if (job.jpegFrames.isNotEmpty()) {
                        tryFinalizeJobOnHandler(job, forcePartial = true)
                    } else {
                        retryOrFailJobOnHandler(job, "Capture interrupted — try again")
                    }
                }
            }
        }
    }

    private fun scheduleJobWatchdogOnHandler(job: PendingCaptureJob) {
        val handler = backgroundHandler ?: return
        job.watchdogRunnable?.let { handler.removeCallbacks(it) }
        val timeoutMs = if (job.isCleanBurst) 4500L else 3200L
        val watchdog = Runnable {
            if (job.finalized) return@Runnable
            if (job.jpegFrames.isNotEmpty() || job.rawImage != null) {
                // We ALREADY have photo data! Save immediately even if a burst frame or TotalCaptureResult stalled!
                Log.w(TAG, "Job #${job.id} watchdog saving ${job.jpegFrames.size}/${job.expectedJpegCount} frame(s)")
                tryFinalizeJobOnHandler(job, forcePartial = true)
            } else {
                Log.w(TAG, "Job #${job.id} watchdog triggered with 0 frames — attempting automatic retry")
                retryOrFailJobOnHandler(job, "Photo capture timed out — please tap again")
            }
        }
        job.watchdogRunnable = watchdog
        handler.postDelayed(watchdog, timeoutMs)
    }

    private fun retryOrFailJobOnHandler(job: PendingCaptureJob, errorMsg: String) {
        if (job.finalized) return
        val device = cameraDevice
        val session = captureSession
        if (job.retryCount < 1 && device != null && session != null && jpegReader != null) {
            job.retryCount++
            // Fallback retry as a clean single-frame capture for maximum reliability
            job.expectedJpegCount = 1
            job.expectRaw = false
            job.jpegFrames.clear()
            job.jpegResults.clear()
            Log.i(TAG, "Auto-retrying photo capture job #${job.id} (single-frame safe retry)")
            scheduleJobWatchdogOnHandler(job)
            submitRequestsForJobOnHandler(job, device, session)
        } else {
            failAndRemoveJobOnHandler(job, errorMsg)
        }
    }

    private fun failAndRemoveJobOnHandler(job: PendingCaptureJob, errorMsg: String) {
        if (job.finalized) return
        job.finalized = true
        job.watchdogRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        job.rawImage?.close()
        job.rawImage = null
        activeCaptureJobs.remove(job)
        onError(errorMsg)
        drainDeferredCaptureOnHandler()
    }

    private fun drainDeferredCaptureOnHandler() {
        if (activeCaptureJobs.isNotEmpty()) return
        val next = deferredCaptureQueue.removeFirstOrNull() ?: return
        submitPhotoCaptureOnHandler(next.grade, next.settings, next.lut)
    }

    private fun dispatchIncomingJpegOnHandler(bytes: ByteArray, fallbackLens: LensInfo) {
        // Route to oldest pending memory-frame request ONLY if a memory-frame capture is actively waiting
        val memJob = pendingMemoryJobs.firstOrNull()
        if (memJob != null && activeCaptureJobs.isEmpty()) {
            pendingMemoryJobs.removeFirst()
            memJob.timeoutRunnable?.let { backgroundHandler?.removeCallbacks(it) }
            val mirrorFront = fallbackLens.isFront && currentSettings.mirrorFrontSave
            ioExecutor.execute {
                val bmp = decodeJpegWithExifOrientation(bytes, mirrorHorizontal = mirrorFront)
                memJob.callback(bmp)
            }
            return
        }

        // Route to the oldest active photo job that still needs JPEG frames
        val targetJob = activeCaptureJobs.firstOrNull { !it.finalized && it.jpegFrames.size < it.expectedJpegCount }
        if (targetJob != null) {
            targetJob.jpegFrames.add(bytes)
            tryFinalizeJobOnHandler(targetJob, forcePartial = false)
            return
        }

        // Fallback: if a memory job was queued concurrently with a finished photo job, deliver to it
        if (memJob != null) {
            pendingMemoryJobs.removeFirst()
            memJob.timeoutRunnable?.let { backgroundHandler?.removeCallbacks(it) }
            val mirrorFront = fallbackLens.isFront && currentSettings.mirrorFrontSave
            ioExecutor.execute {
                val bmp = decodeJpegWithExifOrientation(bytes, mirrorHorizontal = mirrorFront)
                memJob.callback(bmp)
            }
        }
    }

    private fun dispatchIncomingRawOnHandler(image: Image) {
        val targetJob = activeCaptureJobs.firstOrNull { !it.finalized && it.expectRaw && it.rawImage == null }
        if (targetJob != null) {
            targetJob.rawImage = image
            tryFinalizeJobOnHandler(targetJob, forcePartial = false)
        } else {
            image.close()
        }
    }

    private fun updateRepeatingPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            val template = if (isRecordingVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = device.createCaptureRequest(template)
            builder.addTarget(surface)
            if (isRecordingVideo) {
                recordingSurface?.takeIf { it.isValid }?.let { builder.addTarget(it) }
            }
            applyRequestParameters(builder, currentSettings)

            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // Update AF state on EVERY preview frame so our Smart PDAF Ready-Gate has zero lag!
                        result.get(CaptureResult.CONTROL_AF_STATE)?.let { lastAfState = it }
                        val now = System.currentTimeMillis()
                        if (now - lastTelemetryTime >= 150L) {
                            lastTelemetryTime = now
                            val lens = activeLens ?: return
                            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_666_666L
                            lastLiveIso = iso
                            lastLiveExpNs = expNs
                            val focusD = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                            val afLocked = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                            onTelemetry(
                                LiveTelemetry(
                                    iso = iso,
                                    shutterSpeedNs = expNs,
                                    aperture = lens.aperture,
                                    focusDistanceDiopters = focusD,
                                    isRawActive = lens.supportsRawSensor &&
                                        currentSettings.captureFormat != CaptureFormat.CLEAN_JPG,
                                    isAfLocked = afLocked
                                )
                            )
                        }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update repeating request", e)
        }
    }

    private fun applyRequestParameters(
        builder: CaptureRequest.Builder,
        settings: ManualSettings,
        isStillCapture: Boolean = false
    ) {
        val lens = activeLens ?: return

        // Always keep CONTROL_MODE_AUTO so individual AE/AF/AWB settings are honored by the HAL
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        // --- TIER 1: HARDWARE OPTICAL FIDELITY PACK (Zero-AI, Real Sensor & Lens Physics!) ---
        // 1. Physical Hardware OIS on ALL still captures & preview (dramatically sharper handheld shots!)
        if (supportsOis && !isRecordingVideo) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }
        // 2. Indoor 50/60Hz LED & fluorescent anti-banding (eliminates dark rolling flicker stripes!)
        if (supportedAntibandingModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
        // 3. Full-quality optical aberration, lens distortion & corner shading corrections on still captures!
        if (isStillCapture) {
            if (supportedAberrationModes.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                )
            } else if (supportedAberrationModes.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)) {
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (supportedDistortionModes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)) {
                    builder.set(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                    )
                } else if (supportedDistortionModes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)) {
                    builder.set(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
                    )
                }
            }
            if (supportedShadingModes.contains(CaptureRequest.SHADING_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
            } else if (supportedShadingModes.contains(CaptureRequest.SHADING_MODE_FAST)) {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)
            }
        }

        // --- ZERO-AI PURE OPTICS (DEFAULT) vs OPTIONAL CLEAN ENHANCE ---
        val useCleanEnhance = settings.enhanceMode == EnhanceMode.CLEAN
        if (settings.zeroAiBypass) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            // Always disable OEM beauty/skin-smoothing face triggers
            builder.set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            )
            if (useCleanEnhance) {
                // Clean Enhance: hardware HQ noise reduction + EDGE_MODE_OFF (prevents OEM grain-amplifying sharpening halos;
                // our scientific YCbCr chroma + wavelet + PSF deconvolution pass handles crispness cleanly!)
                if (supportedNrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                } else if (supportedNrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
                if (supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_OFF)) {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                }
            } else {
                // Default Pure Zero-AI: zero artificial sharpening & minimal noise smearing
                if (supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_OFF)) {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                } else if (supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_FAST)) {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                }
                if (supportedNrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
                } else if (supportedNrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                }
            }
            val hotPixelMode = if (isStillCapture && supportedHotPixelModes.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)) {
                CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.HOT_PIXEL_MODE_FAST
            }
            builder.set(CaptureRequest.HOT_PIXEL_MODE, hotPixelMode)
        }

        // --- CANON-STYLE EXPOSURE MODES (P / Tv / M) ---
        val isManualExposure = lens.supportsManualSensor &&
            (settings.exposureMode == ExposureMode.MANUAL_M ||
                (settings.exposureMode == ExposureMode.SHUTTER_TV && settings.shutterSpeedNs != null))

        val hasFlashUnit = activeCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (isManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val targetIso = (settings.iso ?: 200).coerceIn(lens.isoRange.lower, lens.isoRange.upper)
            val targetExp = (settings.shutterSpeedNs ?: 16_666_666L)
                .coerceIn(lens.exposureTimeRangeNs.lower, lens.exposureTimeRangeNs.upper)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExp)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(targetExp, 16_666_666L))
            if (hasFlashUnit) {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (settings.flashMode == FlashMode.TORCH) CaptureRequest.FLASH_MODE_TORCH
                    else if (settings.flashMode == FlashMode.ON) CaptureRequest.FLASH_MODE_SINGLE
                    else CaptureRequest.FLASH_MODE_OFF
                )
            }
        } else {
            val aeMode = if (hasFlashUnit) {
                when (settings.flashMode) {
                    FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    FlashMode.TORCH, FlashMode.OFF -> CaptureRequest.CONTROL_AE_MODE_ON
                }
            } else {
                CaptureRequest.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            if (hasFlashUnit) {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (settings.flashMode == FlashMode.TORCH) CaptureRequest.FLASH_MODE_TORCH
                    else CaptureRequest.FLASH_MODE_OFF
                )
            }
            builder.set(CaptureRequest.CONTROL_AE_LOCK, settings.aeAfLocked)
            val clampedEv = settings.evCompensationSteps.coerceIn(
                lens.aeCompensationRange.lower,
                lens.aeCompensationRange.upper
            )
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEv)
        }

        // --- FOCUS CONTROL (AF vs Manual Focus / Super Macro Lock) ---
        val targetFocus = when {
            settings.focusDistanceDiopters != null -> settings.focusDistanceDiopters
            settings.shootingMode == ShootingMode.SUPER_MACRO && lens.minFocusDistanceDiopters > 0f ->
                lens.minFocusDistanceDiopters * 0.85f
            else -> null
        }

        if (targetFocus != null && lens.minFocusDistanceDiopters > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                targetFocus.coerceIn(0f, lens.minFocusDistanceDiopters)
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        // --- WHITE BALANCE (AUTO vs MANUAL KELVIN vs VIDEO WB LOCK) ---
        if (settings.whiteBalanceKelvin != null && lens.supportsManualPostProcessing) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggbGains(settings.whiteBalanceKelvin)
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, settings.videoWbLocked)
            if (isStillCapture) {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
            }
        }

        // --- ZOOM ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
    }

    private fun tryFinalizeJobOnHandler(job: PendingCaptureJob, forcePartial: Boolean) {
        if (job.finalized) return
        if (!forcePartial) {
            if (job.expectRaw && (job.rawImage == null || job.rawResult == null)) return
            if (job.expectedJpegCount > 0 && (job.jpegFrames.size < job.expectedJpegCount || job.jpegResults.isEmpty())) return
        } else {
            if (job.jpegFrames.isEmpty() && job.rawImage == null) return
        }

        job.finalized = true
        job.watchdogRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        job.watchdogRunnable = null
        activeCaptureJobs.remove(job)
        // Immediately kick off the next queued rapid-fire shutter tap at the HAL while this one saves on ioExecutor!
        drainDeferredCaptureOnHandler()

        val rawImage = job.rawImage
        job.rawImage = null
        val rawResult = job.rawResult
        val primaryResult = job.jpegResults.getOrNull(if (job.isOpticalHdr && job.jpegResults.size >= 2) 1 else 0)
            ?: job.jpegResults.firstOrNull()
            ?: rawResult
        val jpegFramesCopy = ArrayList(job.jpegFrames)
        val shakeScoresCopy = if (job.frameShakeScores.isNotEmpty()) job.frameShakeScores.toFloatArray() else null
        val isOpticalHdrJob = job.isOpticalHdr
        val chars = job.characteristics
        val lens = job.lens
        val grade = job.grade
        val lut = job.lut
        val settings = job.settings
        val fallbackIso = lastLiveIso
        val fallbackExp = lastLiveExpNs

        ioExecutor.execute {
            val timestamp = System.currentTimeMillis()
            val seq = fileSeqCounter.getAndIncrement() % 1000L
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp)) +
                if (seq > 0L) "_${seq}" else ""
            var savedDngUri: Uri? = null
            var savedJpegUri: Uri? = null

            try {
                // 1. Save pure 12/14-bit Bayer RAW DNG if captured (paired with its exact rawResult!)
                if (rawImage != null && chars != null && rawResult != null) {
                    try {
                        savedDngUri = saveDngToMediaStore(rawImage, chars, rawResult, "TrueLens_$stamp.dng")
                    } finally {
                        rawImage.close()
                    }
                } else {
                    rawImage?.close()
                }

                val iso = primaryResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: fallbackIso
                val exp = primaryResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: fallbackExp
                // 2. Save Clean / Optical HDR / Multi-Frame Sub-Pixel Burst / Ultra HDR JPEG + Full EXIF
                val jpegDisplayName = "TrueLens_$stamp.jpg"
                if (jpegFramesCopy.isNotEmpty()) {
                    val hasDateStamp = settings.dateStampStyle != com.truelens.camera.grading.DateStampStyle.OFF
                    val useClean = settings.enhanceMode == EnhanceMode.CLEAN
                    val mirrorFront = lens.isFront && settings.mirrorFrontSave
                    val needTransform = isOpticalHdrJob || jpegFramesCopy.size > 1 || !grade.isNeutral ||
                        lut != null || useClean || mirrorFront || hasDateStamp

                    var wasReencoded = false
                    val finalBytes = if (needTransform) {
                        try {
                            val baseBitmap: Bitmap? = when {
                                isOpticalHdrJob && jpegFramesCopy.size >= 2 -> {
                                    // Tier 2: Mertens 3-Frame Optical Exposure Fusion + AgX Highlight Roll-Off!
                                    val fused = OpticalHdrEngine.fuseLazyBracket(
                                        frameCount = jpegFramesCopy.size,
                                        refIndex = jpegFramesCopy.size / 2,
                                        decodeFullFrame = { idx ->
                                            decodeJpegWithExifOrientation(jpegFramesCopy[idx], mirrorHorizontal = mirrorFront)
                                        }
                                    )
                                    if (fused != null && useClean) {
                                        ComputationalDenoiser.denoiseSingle(
                                            fused,
                                            iso = iso,
                                            mergedFrameCount = jpegFramesCopy.size
                                        ).also { if (it !== fused) fused.recycle() }
                                    } else {
                                        fused
                                    }
                                }
                                useClean && jpegFramesCopy.size > 1 -> {
                                    // Tier 2/3/5: Sub-Pixel Super-Res + Lucky Gyro Burst + 3-Octave Wavelets + PSF Deconvolution!
                                    ComputationalDenoiser.processLazyBurst(
                                        frameCount = jpegFramesCopy.size,
                                        iso = iso,
                                        frameShakeScores = shakeScoresCopy,
                                        decodeSharpnessThumb = { idx ->
                                            decodeJpegScaledWithExifOrientation(jpegFramesCopy[idx], sampleSize = 4, mirrorHorizontal = mirrorFront)
                                        },
                                        decodeFullFrame = { idx ->
                                            decodeJpegWithExifOrientation(jpegFramesCopy[idx], mirrorHorizontal = mirrorFront)
                                        }
                                    )
                                }
                                else -> {
                                    val oriented = decodeJpegWithExifOrientation(jpegFramesCopy[0], mirrorHorizontal = mirrorFront)
                                    if (oriented != null && useClean) {
                                        ComputationalDenoiser.denoiseSingle(oriented, iso = iso, mergedFrameCount = 1).also {
                                            if (it !== oriented) oriented.recycle()
                                        }
                                    } else {
                                        oriented
                                    }
                                }
                            }

                            if (baseBitmap != null) {
                                val graded = if (!grade.isNeutral || lut != null) {
                                    BitmapGrader.applyGrade(baseBitmap, grade, lut).also {
                                        if (it !== baseBitmap) baseBitmap.recycle()
                                    }
                                } else baseBitmap
                                val stamped = if (hasDateStamp) {
                                    com.truelens.camera.grading.PhotoFrameRenderer.applyDateStamp(
                                        graded, settings.dateStampStyle, timestamp
                                    ).also { if (it !== graded) graded.recycle() }
                                } else {
                                    graded
                                }
                                // Tier 4: Android 14+ ISO 21496-1 Ultra HDR Gainmap (backwards-compatible JPEG!)
                                val encoded = UltraHdrEncoder.compressJpegWithOptionalGainmap(
                                    bitmap = stamped,
                                    quality = 96,
                                    enableUltraHdr = settings.ultraHdrEnabled,
                                    maxHdrBoost = if (isOpticalHdrJob) 3.0f else 2.2f
                                )
                                stamped.recycle()
                                wasReencoded = true
                                encoded
                            } else {
                                jpegFramesCopy[0]
                            }
                        } catch (oom: OutOfMemoryError) {
                            Log.w(TAG, "Low memory during optical processing — saving primary JPEG cleanly", oom)
                            jpegFramesCopy[0]
                        }
                    } else {
                        jpegFramesCopy[0]
                    }

                    val softwareTag = when {
                        isOpticalHdrJob -> "TrueLens Optical HDR"
                        useClean && jpegFramesCopy.size > 1 -> "TrueLens Clean ${jpegFramesCopy.size}x"
                        useClean -> "TrueLens Clean"
                        else -> "TrueLens Pure Optics"
                    }
                    savedJpegUri = saveJpegToMediaStore(
                        bytes = finalBytes,
                        displayName = jpegDisplayName,
                        writeExif = wasReencoded,
                        iso = iso,
                        exposureTimeNs = exp,
                        aperture = lens.aperture,
                        focalLengthMm = lens.focalLengthMm,
                        timestampMs = timestamp,
                        manualWb = settings.whiteBalanceKelvin != null,
                        softwareTag = softwareTag
                    )
                }

                val formatBadge = when {
                    savedDngUri != null && savedJpegUri != null ->
                        if (isOpticalHdrJob) "RAW+HDR"
                        else if (settings.enhanceMode == EnhanceMode.CLEAN) "RAW+CLEAN"
                        else "RAW+JPG"
                    savedDngUri != null -> "DNG RAW"
                    isOpticalHdrJob -> "OPTICAL HDR"
                    settings.enhanceMode == EnhanceMode.CLEAN && jpegFramesCopy.size > 1 ->
                        "CLEAN ${jpegFramesCopy.size}×"
                    settings.enhanceMode == EnhanceMode.CLEAN -> "CLEAN+"
                    else -> "CLEAN JPG"
                }

                onPhotoCaptured(
                    CapturedPhoto(
                        jpegUri = savedJpegUri,
                        dngUri = savedDngUri,
                        filePath = if (savedJpegUri != null) jpegDisplayName else null,
                        timestampMs = timestamp,
                        iso = iso,
                        shutterSpeedNs = exp,
                        aperture = lens.aperture,
                        lensLabel = lens.displayLabel,
                        zeroAiBypass = settings.zeroAiBypass,
                        formatBadge = formatBadge
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving capture", e)
                onError("Save failed: ${e.message}")
            }
        }
    }

    private fun decodeJpegScaledWithExifOrientation(
        bytes: ByteArray,
        sampleSize: Int,
        mirrorHorizontal: Boolean
    ): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return applyExifOrientationAndMirror(decoded, bytes, mirrorHorizontal)
    }

    private fun decodeJpegWithExifOrientation(bytes: ByteArray, mirrorHorizontal: Boolean): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return applyExifOrientationAndMirror(decoded, bytes, mirrorHorizontal)
    }

    private fun applyExifOrientationAndMirror(
        decoded: Bitmap,
        bytes: ByteArray,
        mirrorHorizontal: Boolean
    ): Bitmap {
        val exifRot = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        val m = Matrix()
        if (exifRot != 0f) m.postRotate(exifRot)
        if (mirrorHorizontal) m.postScale(-1f, 1f)
        return if (!m.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
                decoded.recycle()
            }
        } else {
            decoded
        }
    }

    private fun saveDngToMediaStore(
        rawImage: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        displayName: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens/RAW")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        cr.openOutputStream(uri)?.use { stream ->
            DngCreator(characteristics, captureResult).use { dngCreator ->
                val exifOrient = when (activeLens?.sensorOrientation ?: 90) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                dngCreator.setOrientation(exifOrient)
                dngCreator.writeImage(stream, rawImage)
            }
            stream.flush()
        }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        cr.update(uri, done, null, null)
        return uri
    }

    private fun saveJpegToMediaStore(
        bytes: ByteArray,
        displayName: String,
        writeExif: Boolean = false,
        iso: Int = 0,
        exposureTimeNs: Long = 0L,
        aperture: Float = 0f,
        focalLengthMm: Float = 0f,
        timestampMs: Long = System.currentTimeMillis(),
        manualWb: Boolean = false,
        softwareTag: String = "TrueLens Optical"
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        cr.openOutputStream(uri)?.use { stream ->
            stream.write(bytes)
            stream.flush()
        }
        // Tier 1: Preserve full real hardware EXIF metadata on every processed JPEG before releasing IS_PENDING!
        if (writeExif) {
            ExifMetadataWriter.writeToMediaStoreUri(
                context = context,
                uri = uri,
                iso = iso,
                exposureTimeNs = exposureTimeNs,
                aperture = aperture,
                focalLengthMm = focalLengthMm,
                timestampMs = timestampMs,
                manualWhiteBalance = manualWb,
                softwareTag = softwareTag
            )
        }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        cr.update(uri, done, null, null)
        return uri
    }

    private fun computeHistogram(image: Image): FloatArray {
        val bins = IntArray(64)
        val yPlane = image.planes[0]
        val buf = yPlane.buffer
        val step = 4
        var maxCount = 1
        var i = 0
        val limit = buf.remaining()
        while (i < limit) {
            val luma = buf.get(i).toInt() and 0xFF
            val bin = luma ushr 2
            val count = ++bins[bin]
            if (count > maxCount) maxCount = count
            i += step
        }
        return FloatArray(64) { idx -> bins[idx].toFloat() / maxCount.toFloat() }
    }

    private fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = (kelvin.coerceIn(2000, 10000)) / 100.0
        val r: Double
        val g: Double
        val b: Double
        if (temp <= 66.0) {
            r = 255.0
            g = (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
            b = if (temp <= 19.0) 0.0 else (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        } else {
            r = (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
            g = (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
            b = 255.0
        }
        // Normalize against green so overall exposure doesn't jump
        val normG = (g / 255.0).coerceAtLeast(0.2)
        val rf = ((r / 255.0) / normG).toFloat().coerceIn(0.5f, 3.0f)
        val bf = ((b / 255.0) / normG).toFloat().coerceIn(0.5f, 3.0f)
        return RggbChannelVector(rf, 1.0f, 1.0f, bf)
    }

    companion object {
        private const val TAG = "TrueLensCameraEngine"
        private const val CLEAN_BURST_FRAMES = 4
    }
}
