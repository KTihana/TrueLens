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
import com.truelens.camera.grading.CubeLut
import com.truelens.camera.grading.LightroomGrade
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var activeLens: LensInfo? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var supportedEdgeModes: IntArray = IntArray(0)
    private var supportedNrModes: IntArray = IntArray(0)
    private var currentSettings: ManualSettings = ManualSettings()
    private var currentGrade: LightroomGrade = LightroomGrade.NEUTRAL
    private var zoomRatio: Float = 1.0f

    private var lastTelemetryTime = 0L

    // Pending capture correlation for RAW DNG + JPEG dual-save
    private var pendingRawImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var pendingJpegBytes: ByteArray? = null
    private var pendingCaptureExpectsRaw: Boolean = false
    private var pendingCaptureExpectsJpeg: Boolean = false
    private var pendingCaptureGrade: LightroomGrade = LightroomGrade.NEUTRAL
    private var pendingCaptureLut: CubeLut? = null
    private var pendingCaptureSettings: ManualSettings = ManualSettings()
    private var pendingMemoryFrameCallback: ((Bitmap?) -> Unit)? = null

    // Video & Slow-Mo Recording state
    private var mediaRecorder: MediaRecorder? = null
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

            // Pick a reference "1x" focal length (typically median/standard rear lens)
            val sortedRear = rearFocals.sortedBy { it.second }
            val refFocal = sortedRear.firstOrNull()?.second ?: 4.0f

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
                        displayLabel = label
                    )
                )
            }
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
            pendingRawImage?.close()
            pendingRawImage = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
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
            jpegReader?.close()
            rawReader?.close()

            val outputs = mutableListOf<Surface>()
            outputs.add(surface)

            // JPEG output (clean or graded copy)
            jpegReader = ImageReader.newInstance(
                lens.maxJpegSize.width,
                lens.maxJpegSize.height,
                ImageFormat.JPEG,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val memCb = pendingMemoryFrameCallback
                        if (memCb != null) {
                            pendingMemoryFrameCallback = null
                            ioExecutor.execute {
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                memCb(bmp)
                            }
                        } else {
                            pendingJpegBytes = bytes
                            tryFinalizeCapture()
                        }
                    } finally {
                        image.close()
                    }
                }, handler)
            }
            outputs.add(jpegReader!!.surface)

            // Pure 12/14-bit Bayer RAW_SENSOR output (strictly <= 3 outputs for universal HAL compatibility)
            if (lens.supportsRawSensor && lens.maxRawSize != null) {
                rawReader = ImageReader.newInstance(
                    lens.maxRawSize.width,
                    lens.maxRawSize.height,
                    ImageFormat.RAW_SENSOR,
                    2
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        pendingRawImage?.close()
                        pendingRawImage = image
                        tryFinalizeCapture()
                    }, handler)
                }
                outputs.add(rawReader!!.surface)
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
        updateRepeatingPreview()
    }

    fun setZoom(ratio: Float) {
        this.zoomRatio = ratio.coerceIn(1.0f, 8.0f)
        updateRepeatingPreview()
    }

    fun triggerTapToFocus(normX: Float, normY: Float) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val lens = activeLens ?: return
        val surface = previewSurface ?: return

        try {
            val activeRect = lens.activeArraySize
            val cx = (normX.coerceIn(0f, 1f) * activeRect.width()).toInt()
            val cy = (normY.coerceIn(0f, 1f) * activeRect.height()).toInt()
            val halfW = (activeRect.width() * 0.08f).toInt()
            val halfH = (activeRect.height() * 0.08f).toInt()
            val region = MeteringRectangle(
                (cx - halfW).coerceAtLeast(0),
                (cy - halfH).coerceAtLeast(0),
                (halfW * 2).coerceAtMost(activeRect.width()),
                (halfH * 2).coerceAtMost(activeRect.height()),
                MeteringRectangle.METERING_WEIGHT_MAX
            )

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
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
            // Detect highest supported Slow-Mo FPS if requested (e.g. 120fps / 240fps)
            var captureFps = 30
            var videoWidth = 1920
            var videoHeight = 1080
            if (isSlowMo && chars != null) {
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val hsRanges = map?.highSpeedVideoFpsRanges
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
                    // Graceful 60->30 (2x) slow-mo fallback on devices without high-speed stream
                    captureFps = 60
                }
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix = if (isSlowMo) "TrueLens_SlowMo" else "TrueLens_Video"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${prefix}_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TrueLens")
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

            if (hasAudioPermission && !isSlowMo) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (hasAudioPermission && !isSlowMo) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44_100)
            }
            recorder.setVideoEncodingBitRate(if (isSlowMo) 24_000_000 else 16_000_000)
            recorder.setVideoSize(videoWidth, videoHeight)
            recorder.setVideoFrameRate(30) // Playback at smooth 30fps
            if (isSlowMo) {
                recorder.setCaptureRate(captureFps.toDouble())
            }
            recorder.setOrientationHint(lens.sensorOrientation)
            recorder.prepare()

            val recSurface = recorder.surface
            mediaRecorder = recorder
            recordingPfd = pfd
            recordingUri = uri

            captureSession?.close()
            captureSession = null

            val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(recSurface))
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                context.mainExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            req.addTarget(preview)
                            req.addTarget(recSurface)
                            applyRequestParameters(req, settings)
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
                            val label = if (isSlowMo) "${captureFps}fps → 30fps" else "1080p 30fps"
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

    fun stopVideoRecording(onSaved: (Uri?) -> Unit) {
        val savedUri = recordingUri
        if (isRecordingVideo) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder stop warning", e)
            }
        }
        cleanupRecorder()
        createCaptureSession()
        onSaved(savedUri)
    }

    private fun cleanupRecorder() {
        isRecordingVideo = false
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
        val device = cameraDevice ?: return onFrame(null)
        val session = captureSession ?: return onFrame(null)
        val reader = jpegReader ?: return onFrame(null)
        val lens = activeLens ?: return onFrame(null)

        pendingMemoryFrameCallback = onFrame
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyRequestParameters(builder, settings)
            builder.set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)
            session.capture(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            pendingMemoryFrameCallback = null
            Log.e(TAG, "Panorama frame capture failed", e)
            onFrame(null)
        }
    }

    fun capturePhoto(grade: LightroomGrade, settings: ManualSettings, lut: CubeLut? = null) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val lens = activeLens ?: return

        val wantRaw = lens.supportsRawSensor && rawReader != null &&
            (settings.captureFormat == CaptureFormat.RAW_PLUS_JPG ||
                settings.captureFormat == CaptureFormat.RAW_ONLY)
        val wantJpeg = !wantRaw || settings.captureFormat != CaptureFormat.RAW_ONLY

        pendingRawImage?.close()
        pendingRawImage = null
        pendingJpegBytes = null
        pendingCaptureResult = null
        pendingCaptureExpectsRaw = wantRaw
        pendingCaptureExpectsJpeg = wantJpeg
        pendingCaptureGrade = grade
        pendingCaptureLut = lut
        pendingCaptureSettings = settings

        try {
            // CRITICAL: Always use TEMPLATE_STILL_CAPTURE so CONTROL_MODE_AUTO, color matrices,
            // lens shading, and converged AE/AWB remain intact on every Android device.
            // (TEMPLATE_MANUAL defaults CONTROL_MODE=OFF and minimum shutter -> pitch black photos!)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (wantJpeg) jpegReader?.surface?.let { builder.addTarget(it) }
            if (wantRaw) rawReader?.surface?.let { builder.addTarget(it) }

            applyRequestParameters(builder, settings)
            builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        pendingCaptureResult = result
                        tryFinalizeCapture()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            onError("Capture failed: ${e.message}")
        }
    }

    private fun updateRepeatingPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyRequestParameters(builder, currentSettings)

            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastTelemetryTime >= 150L) {
                            lastTelemetryTime = now
                            val lens = activeLens ?: return
                            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_666_666L
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

    private fun applyRequestParameters(builder: CaptureRequest.Builder, settings: ManualSettings) {
        val lens = activeLens ?: return

        // Always keep CONTROL_MODE_AUTO so individual AE/AF/AWB settings are honored by the HAL
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        // --- ZERO-AI OEM BYPASS PIPELINE (strictly guarded by hardware-supported modes) ---
        if (settings.zeroAiBypass) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            // Disable face detection to prevent OEM beauty/skin-smoothing triggers
            builder.set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            )
            // Disable artificial edge sharpening & aggressive noise smearing only if HAL supports it
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
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
        }

        // --- CANON-STYLE EXPOSURE MODES (P / Tv / M) ---
        val isManualExposure = lens.supportsManualSensor &&
            (settings.exposureMode == ExposureMode.MANUAL_M ||
                (settings.exposureMode == ExposureMode.SHUTTER_TV && settings.shutterSpeedNs != null))

        if (isManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val targetIso = (settings.iso ?: 200).coerceIn(lens.isoRange.lower, lens.isoRange.upper)
            val targetExp = (settings.shutterSpeedNs ?: 16_666_666L)
                .coerceIn(lens.exposureTimeRangeNs.lower, lens.exposureTimeRangeNs.upper)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExp)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(targetExp, 16_666_666L))
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
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

        // --- WHITE BALANCE (AUTO vs MANUAL KELVIN) ---
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
        }

        // --- ZOOM ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
    }

    private fun tryFinalizeCapture() {
        val result = pendingCaptureResult ?: return
        if (pendingCaptureExpectsRaw && pendingRawImage == null) return
        if (pendingCaptureExpectsJpeg && pendingJpegBytes == null) return

        val rawImage = pendingRawImage
        val jpegBytes = pendingJpegBytes
        val chars = activeCharacteristics
        val lens = activeLens ?: return
        val grade = pendingCaptureGrade
        val settings = pendingCaptureSettings

        // Reset pending state
        pendingRawImage = null
        pendingJpegBytes = null
        pendingCaptureResult = null

        ioExecutor.execute {
            val timestamp = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
            var savedDngUri: Uri? = null
            var savedJpegUri: Uri? = null

            try {
                // 1. Save pure 12/14-bit Bayer RAW DNG if captured
                if (rawImage != null && chars != null) {
                    try {
                        savedDngUri = saveDngToMediaStore(rawImage, chars, result, "TrueLens_$stamp.dng")
                    } finally {
                        rawImage.close()
                    }
                }

                // 2. Save Clean / Graded JPEG (with optional Front-Camera Mirror-Save & .cube LUT)
                if (jpegBytes != null) {
                    val needTransform = !grade.isNeutral || pendingCaptureLut != null ||
                        (lens.isFront && settings.mirrorFrontSave)
                    val finalBytes = if (needTransform) {
                        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (decoded != null) {
                            // Respect EXIF orientation before re-encoding so graded/mirrored photos never rotate sideways
                            val exifRot = try {
                                when (ExifInterface(ByteArrayInputStream(jpegBytes)).getAttributeInt(
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
                            if (lens.isFront && settings.mirrorFrontSave) m.postScale(-1f, 1f)
                            val oriented = if (!m.isIdentity) {
                                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
                                    decoded.recycle()
                                }
                            } else {
                                decoded
                            }
                            val graded = BitmapGrader.applyGrade(oriented, grade, pendingCaptureLut)
                            oriented.recycle()
                            val out = ByteArrayOutputStream()
                            graded.compress(Bitmap.CompressFormat.JPEG, 96, out)
                            graded.recycle()
                            out.toByteArray()
                        } else {
                            jpegBytes
                        }
                    } else {
                        jpegBytes
                    }
                    savedJpegUri = saveJpegToMediaStore(finalBytes, "TrueLens_$stamp.jpg")
                }

                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_666_666L
                val formatBadge = when {
                    savedDngUri != null && savedJpegUri != null -> "RAW+JPG"
                    savedDngUri != null -> "DNG RAW"
                    else -> "CLEAN JPG"
                }

                onPhotoCaptured(
                    CapturedPhoto(
                        jpegUri = savedJpegUri,
                        dngUri = savedDngUri,
                        filePath = null,
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

    private fun saveJpegToMediaStore(bytes: ByteArray, displayName: String): Uri? {
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
    }
}
