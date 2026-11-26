package com.truelens.camera.ui

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.truelens.camera.camera.AebMode
import com.truelens.camera.camera.AspectRatioMask
import com.truelens.camera.camera.CaptureFormat
import com.truelens.camera.camera.CapturedPhoto
import com.truelens.camera.camera.DocumentScanStyle
import com.truelens.camera.camera.ExposureMode
import com.truelens.camera.camera.GridOverlay
import com.truelens.camera.camera.LensInfo
import com.truelens.camera.camera.LiveTelemetry
import com.truelens.camera.camera.ManualSettings
import com.truelens.camera.camera.ShootingMode
import com.truelens.camera.camera.SoloBurstCount
import com.truelens.camera.camera.StabilizerMode
import com.truelens.camera.camera.TimerDuration
import com.truelens.camera.camera.TrueLensCameraEngine
import com.truelens.camera.camera.TrueLightAnalysis
import com.truelens.camera.camera.TrueLightAutoMeter
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.BuiltInPresets
import com.truelens.camera.grading.CubeLut
import com.truelens.camera.grading.CubeLutParser
import com.truelens.camera.grading.CustomPresetRepository
import com.truelens.camera.grading.GradePreset
import com.truelens.camera.grading.LightroomGrade
import com.truelens.camera.modes.DocumentScanner
import com.truelens.camera.modes.FocusStacker
import com.truelens.camera.modes.LongExposureEngine
import com.truelens.camera.modes.LongExposureState
import com.truelens.camera.modes.LongExposureStyle
import com.truelens.camera.modes.PanoramaState
import com.truelens.camera.modes.PanoramaStitcher
import com.truelens.camera.modes.StopMotionExporter
import com.truelens.camera.modes.StopMotionState
import com.truelens.camera.modes.TimeLapseState
import kotlinx.coroutines.Job
import com.truelens.camera.pose.PinterestBoardSummary
import com.truelens.camera.pose.PinterestImporter
import com.truelens.camera.pose.PoseBlendMode
import com.truelens.camera.pose.PoseFolder
import com.truelens.camera.pose.PoseInspoItem
import com.truelens.camera.pose.PoseInspoRepository
import com.truelens.camera.pose.PoseOverlayProcessor
import com.truelens.camera.pose.PoseOverlayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ActiveDeck { NONE, MANUAL, LIGHTROOM }

data class CameraUiState(
    val lenses: List<LensInfo> = emptyList(),
    val activeLens: LensInfo? = null,
    val settings: ManualSettings = ManualSettings(),
    val grade: LightroomGrade = LightroomGrade.NEUTRAL,
    val activePresetId: String = "true_zero",
    val customPresets: List<GradePreset> = emptyList(),
    val telemetry: LiveTelemetry = LiveTelemetry(),
    val histogram: FloatArray = FloatArray(64),
    val lightAnalysis: TrueLightAnalysis = TrueLightAnalysis(),
    val zoomRatio: Float = 1.0f,
    val activeDeck: ActiveDeck = ActiveDeck.NONE,
    val bypassLiveGrade: Boolean = false,
    val lastCapturedPhoto: CapturedPhoto? = null,
    val lastThumbnail: Bitmap? = null,
    val lastThumbnailIsVideo: Boolean = false,
    val showShutterFlash: Boolean = false,
    val showFrontScreenFlash: Boolean = false,
    val statusBanner: String? = null,
    val isDarkroomOpen: Boolean = false,
    val isGalleryOpen: Boolean = false,
    val darkroomSourceUri: Uri? = null,
    val darkroomSourceBitmap: Bitmap? = null,
    val darkroomGradedBitmap: Bitmap? = null,
    val darkroomGrade: LightroomGrade = LightroomGrade.NEUTRAL,
    val darkroomVerticalTilt: Float = 0f,
    val darkroomHorizontalTilt: Float = 0f,
    val darkroomRotationDeg: Float = 0f,
    val darkroomFrameStyle: com.truelens.camera.grading.ExportFrameStyle = com.truelens.camera.grading.ExportFrameStyle.NONE,
    val darkroomDateStamp: com.truelens.camera.grading.DateStampStyle = com.truelens.camera.grading.DateStampStyle.OFF,
    val darkroomExif: com.truelens.camera.grading.PhotoExifSummary = com.truelens.camera.grading.PhotoExifSummary(),
    val canUndoDarkroom: Boolean = false,
    val canRedoDarkroom: Boolean = false,
    val isExportingDarkroom: Boolean = false,
    // Hands-Free Voice + Palm Shutter & Dual-Camera Reaction Mode
    val palmHoldProgress: Float = 0f,
    val dualSplitRatio: Float = 0.5f,
    val dualPipX: Float = 0.67f,
    val dualPipY: Float = 0.08f,
    val dualSwapped: Boolean = false,
    val isDualConcurrentLive: Boolean = false,
    val isCapturingDual: Boolean = false,
    // Phase 2 Modes & Self-Timer
    val panoramaState: PanoramaState = PanoramaState(),
    val timeLapseState: TimeLapseState = TimeLapseState(),
    val countdownRemainingSec: Int = 0,
    // Video / Slow-Mo / Document Scanner / Canon C1-C3 / Stop-Mo / Bulb / LUT / Focus Stack
    val isVideoRecording: Boolean = false,
    val videoElapsedSec: Int = 0,
    val videoFpsBadge: String = "1080p",
    val isScanningDocument: Boolean = false,
    val savedCanonSlots: Set<String> = emptySet(),
    val stopMotionState: StopMotionState = StopMotionState(),
    val longExposureState: LongExposureState = LongExposureState(),
    val isFocusStacking: Boolean = false,
    val activeLut: CubeLut? = null,
    val splitScreenPosition: Float = 0.5f,
    // Pose Overlay & Pose Inspos Library
    val isPoseInsposOpen: Boolean = false,
    val poseFolders: List<PoseFolder> = emptyList(),
    val selectedPoseFolderId: String? = null,
    val poseOverlay: PoseOverlayState = PoseOverlayState(),
    val isImportingPoses: Boolean = false,
    val poseImportStatus: String? = null,
    val pinterestBoards: List<PinterestBoardSummary> = emptyList(),
    val isPinterestConnected: Boolean = false,
    val savedPinterestClientId: String = ""
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val poseRepo = PoseInspoRepository(application)
    private val customPresetRepo = CustomPresetRepository(application)
    val pinterestImporter = PinterestImporter(application)
    private val dualCameraController = com.truelens.camera.camera.DualCameraController(application)
    private val handsFreeController = com.truelens.camera.modes.HandsFreeShutterController(
        context = application,
        onTriggered = { reason -> onHandsFreeTriggered(reason) },
        onPalmProgress = { progress -> _uiState.update { it.copy(palmHoldProgress = progress) } }
    )

    private var previewSurface: Surface? = null
    private var engineInitialized = false
    private var lastAutoMeterAdjustMs = 0L
    private var latestAmbientLux = 500f
    private val panoramaFrames = mutableListOf<android.graphics.Bitmap>()
    private val stopMotionFrames = mutableListOf<android.graphics.Bitmap>()
    private val timeLapseFrames = mutableListOf<android.graphics.Bitmap>()
    private data class DarkroomSnapshot(
        val grade: LightroomGrade,
        val vTilt: Float,
        val hTilt: Float,
        val rotDeg: Float,
        val frameStyle: com.truelens.camera.grading.ExportFrameStyle,
        val dateStamp: com.truelens.camera.grading.DateStampStyle
    )
    private val darkroomUndoStack = ArrayDeque<DarkroomSnapshot>()
    private val darkroomRedoStack = ArrayDeque<DarkroomSnapshot>()
    private var lastUndoPushMs = 0L
    private var lastPanoYawAtCapture = 0f
    private var timeLapseJob: Job? = null
    private var timerJob: Job? = null
    private var videoTimerJob: Job? = null
    private var bannerJob: Job? = null
    private var darkroomPreviewJob: Job? = null
    private val canonSlotPrefs = application.getSharedPreferences("truelens_canon_slots", Context.MODE_PRIVATE)

    init {
        loadPoseFolders()
        refreshLatestThumbnail()
        viewModelScope.launch {
            val saved = customPresetRepo.loadCustomPresets()
            val slots = setOf("C1", "C2", "C3").filter { canonSlotPrefs.contains("${it}_mode") }.toSet()
            _uiState.update { it.copy(customPresets = saved, savedCanonSlots = slots) }
        }
    }

    fun refreshLatestThumbnail() {
        viewModelScope.launch {
            val latest = queryTrueLensGallery(getApplication(), includeVideos = true).firstOrNull()
            if (latest == null) {
                _uiState.update { it.copy(lastThumbnail = null, lastThumbnailIsVideo = false) }
            } else {
                val thumb = loadMediaThumbnail(getApplication(), latest.uri, latest.isVideo, 240)
                _uiState.update {
                    it.copy(
                        lastThumbnail = thumb ?: it.lastThumbnail,
                        lastThumbnailIsVideo = latest.isVideo
                    )
                }
            }
        }
    }

    private val cameraEngine = TrueLensCameraEngine(
        context = application,
        onTelemetry = { telemetry ->
            _uiState.update { it.copy(telemetry = telemetry) }
        },
        onHistogram = { bins ->
            _uiState.update { it.copy(histogram = bins) }
            evaluateTrueLightAuto(bins)
        },
        onPhotoCaptured = { photo ->
            viewModelScope.launch {
                val thumb = photo.jpegUri?.let { loadScaledBitmap(it, 240) }
                _uiState.update {
                    it.copy(
                        lastCapturedPhoto = photo,
                        lastThumbnail = thumb ?: it.lastThumbnail,
                        lastThumbnailIsVideo = if (thumb != null) false else it.lastThumbnailIsVideo
                    )
                }
                showBanner("Saved ${photo.formatBadge} • ISO ${photo.iso}")
            }
        },
        onError = { msg ->
            showBanner(msg)
        }
    )

    fun initializeCameras() {
        if (engineInitialized) return
        val lenses = cameraEngine.enumerateLenses()
        val defaultLens = lenses.firstOrNull { !it.isFront && it.displayLabel == "1x" }
            ?: lenses.firstOrNull { !it.isFront }
            ?: lenses.firstOrNull()

        engineInitialized = true
        _uiState.update { state ->
            val defaultFormat = if (defaultLens?.supportsRawSensor == true) {
                CaptureFormat.RAW_PLUS_JPG
            } else {
                CaptureFormat.CLEAN_JPG
            }
            state.copy(
                lenses = lenses,
                activeLens = defaultLens,
                settings = state.settings.copy(captureFormat = defaultFormat),
                telemetry = state.telemetry.copy(
                    aperture = defaultLens?.aperture ?: 1.8f,
                    isRawActive = defaultLens?.supportsRawSensor == true
                )
            )
        }
        previewSurface?.let { surface ->
            defaultLens?.let { openLens(it, surface) }
        }
    }

    fun onPreviewSurfaceReady(surface: Surface) {
        previewSurface = surface
        val lens = _uiState.value.activeLens
        if (lens != null) {
            openLens(lens, surface)
        } else {
            initializeCameras()
        }
    }

    fun onPreviewSurfaceDestroyed() {
        previewSurface = null
        cameraEngine.stop()
    }

    fun resumeCameraIfNeeded() {
        syncHandsFreeControllers()
        val surface = previewSurface ?: return
        if (!surface.isValid) return
        val lens = _uiState.value.activeLens ?: return
        openLens(lens, surface)
    }

    fun stopCamera() {
        dualCameraController.stopSecondary()
        handsFreeController.setVoiceEnabled(false, false)
        handsFreeController.setPalmEnabled(false)
        cameraEngine.stop()
    }

    override fun onCleared() {
        super.onCleared()
        dualCameraController.release()
        handsFreeController.release()
        cameraEngine.stop()
    }

    private fun openLens(lens: LensInfo, surface: Surface) {
        val state = _uiState.value
        cameraEngine.openCamera(lens, surface, state.settings, state.grade)
    }

    fun selectLens(lens: LensInfo) {
        if (lens.cameraId == _uiState.value.activeLens?.cameraId) return
        _uiState.update { state ->
            val format = if (!lens.supportsRawSensor && state.settings.captureFormat != CaptureFormat.CLEAN_JPG) {
                CaptureFormat.CLEAN_JPG
            } else {
                state.settings.captureFormat
            }
            state.copy(
                activeLens = lens,
                settings = state.settings.copy(
                    captureFormat = format,
                    focusDistanceDiopters = null
                )
            )
        }
        previewSurface?.let { openLens(lens, it) }
    }

    fun setExposureMode(mode: ExposureMode) {
        updateSettings { s ->
            when (mode) {
                ExposureMode.AUTO_P -> s.copy(exposureMode = mode, iso = null, shutterSpeedNs = null)
                ExposureMode.SHUTTER_TV -> s.copy(
                    exposureMode = mode,
                    shutterSpeedNs = s.shutterSpeedNs ?: _uiState.value.telemetry.shutterSpeedNs
                )
                ExposureMode.MANUAL_M -> s.copy(
                    exposureMode = mode,
                    iso = s.iso ?: _uiState.value.telemetry.iso,
                    shutterSpeedNs = s.shutterSpeedNs ?: _uiState.value.telemetry.shutterSpeedNs
                )
            }
        }
    }

    fun setShootingMode(mode: ShootingMode) {
        if (_uiState.value.isVideoRecording) stopVideoRecording()
        if (_uiState.value.timeLapseState.isRunning) stopTimeLapse()
        if (_uiState.value.panoramaState.isCapturing) cancelPanorama()
        if (mode != ShootingMode.DUAL) {
            dualCameraController.stopSecondary()
            _uiState.update { it.copy(isDualConcurrentLive = false) }
        }

        updateSettings { s ->
            when (mode) {
                ShootingMode.SUPER_MACRO -> s.copy(
                    shootingMode = mode,
                    focusPeaking = true
                )
                else -> s.copy(
                    shootingMode = mode,
                    focusDistanceDiopters = null,
                    focusPeaking = if (s.shootingMode == ShootingMode.SUPER_MACRO) false else s.focusPeaking
                )
            }
        }
    }

    fun cycleTimerDuration() {
        updateSettings { s ->
            val values = TimerDuration.entries
            val next = values[(s.timerDuration.ordinal + 1) % values.size]
            s.copy(timerDuration = next)
        }
        val t = _uiState.value.settings.timerDuration
        showBanner(if (t == TimerDuration.OFF) "Self-Timer OFF" else "Self-Timer ${t.label}")
    }

    fun setAspectRatioMask(mask: AspectRatioMask) {
        updateSettings { it.copy(aspectRatioMask = mask) }
    }

    fun setSoloBurstCount(burst: SoloBurstCount) {
        updateSettings { it.copy(soloBurstCount = burst) }
    }

    fun setGridOverlay(grid: GridOverlay) {
        updateSettings { it.copy(gridOverlay = grid) }
    }

    fun cycleAspectRatioMask() {
        updateSettings { s ->
            val values = AspectRatioMask.entries
            val next = values[(s.aspectRatioMask.ordinal + 1) % values.size]
            s.copy(aspectRatioMask = next)
        }
        val mask = _uiState.value.settings.aspectRatioMask
        showBanner("Framing Guide: ${mask.label} (full RAW still saved)")
    }

    fun cycleSoloBurstCount() {
        updateSettings { s ->
            val values = SoloBurstCount.entries
            val next = values[(s.soloBurstCount.ordinal + 1) % values.size]
            s.copy(soloBurstCount = next)
        }
        val b = _uiState.value.settings.soloBurstCount
        showBanner(if (b.count == 1) "Single Shot" else "Solo Burst: ${b.count} rapid shots")
    }

    fun saveCustomPreset(name: String, grade: LightroomGrade) {
        viewModelScope.launch {
            val updated = customPresetRepo.saveCustomPreset(name, grade)
            _uiState.update {
                it.copy(
                    customPresets = updated,
                    activePresetId = updated.firstOrNull()?.id ?: it.activePresetId
                )
            }
            showBanner("Saved custom recipe \"$name\"")
        }
    }

    fun deleteCustomPreset(id: String) {
        viewModelScope.launch {
            val updated = customPresetRepo.deleteCustomPreset(id)
            _uiState.update { it.copy(customPresets = updated) }
        }
    }

    fun toggleFriendMode() {
        updateSettings { it.copy(friendMode = !it.friendMode) }
        val active = _uiState.value.settings.friendMode
        if (active) {
            _uiState.update { it.copy(activeDeck = ActiveDeck.NONE) }
        }
    }

    fun cycleStabilizerMode() {
        updateSettings { s ->
            val values = StabilizerMode.entries
            val next = values[(s.stabilizerMode.ordinal + 1) % values.size]
            s.copy(stabilizerMode = next)
        }
        val m = _uiState.value.settings.stabilizerMode
        showBanner("${m.label} — ${m.description}")
    }

    fun cycleAebMode() {
        updateSettings { s ->
            val values = AebMode.entries
            val next = values[(s.aebMode.ordinal + 1) % values.size]
            s.copy(aebMode = next)
        }
        val aeb = _uiState.value.settings.aebMode
        showBanner(if (aeb == AebMode.OFF) "Exposure Bracketing OFF" else "Canon ${aeb.label} (3-shot HDR burst)")
    }

    fun setScanStyle(style: DocumentScanStyle) {
        updateSettings { it.copy(scanStyle = style) }
    }

    fun saveCanonSlot(slot: String) {
        val s = _uiState.value.settings
        canonSlotPrefs.edit()
            .putString("${slot}_mode", s.exposureMode.name)
            .putInt("${slot}_iso", s.iso ?: -1)
            .putLong("${slot}_shutter", s.shutterSpeedNs ?: -1L)
            .putInt("${slot}_wb", s.whiteBalanceKelvin ?: -1)
            .putInt("${slot}_ev", s.evCompensationSteps)
            .apply()
        _uiState.update { it.copy(savedCanonSlots = it.savedCanonSlots + slot) }
        showBanner("Saved current setup to Canon $slot")
    }

    fun loadCanonSlot(slot: String) {
        if (!canonSlotPrefs.contains("${slot}_mode")) {
            saveCanonSlot(slot)
            return
        }
        val modeName = canonSlotPrefs.getString("${slot}_mode", ExposureMode.MANUAL_M.name)
        val mode = runCatching { ExposureMode.valueOf(modeName!!) }.getOrDefault(ExposureMode.MANUAL_M)
        val iso = canonSlotPrefs.getInt("${slot}_iso", -1).takeIf { it > 0 }
        val shutter = canonSlotPrefs.getLong("${slot}_shutter", -1L).takeIf { it > 0L }
        val wb = canonSlotPrefs.getInt("${slot}_wb", -1).takeIf { it > 0 }
        val ev = canonSlotPrefs.getInt("${slot}_ev", 0)
        updateSettings {
            it.copy(
                exposureMode = mode,
                iso = iso,
                shutterSpeedNs = shutter,
                whiteBalanceKelvin = wb,
                evCompensationSteps = ev
            )
        }
        showBanner("Recalled Canon $slot (${mode.shortLabel})")
    }

    fun onShutterPressed() {
        val state = _uiState.value
        when (state.settings.shootingMode) {
            ShootingMode.DUAL -> {
                if (!state.isCapturingDual) {
                    captureDualReactionPhoto()
                }
            }
            ShootingMode.VIDEO, ShootingMode.SLOW_MO -> {
                if (state.isVideoRecording) {
                    stopVideoRecording()
                } else {
                    startVideoRecording(isSlowMo = state.settings.shootingMode == ShootingMode.SLOW_MO)
                }
            }
            ShootingMode.SCAN_DOC -> {
                scanDocumentNow()
            }
            ShootingMode.STOP_MO -> {
                captureStopMotionFrame()
            }
            ShootingMode.LONG_EXP -> {
                if (!state.longExposureState.isCapturing) {
                    startLongExposure()
                }
            }
            ShootingMode.SUPER_MACRO -> {
                if (state.settings.macroFocusStackEnabled) {
                    captureSuperMacroFocusStack()
                } else {
                    capturePhoto()
                }
            }
            ShootingMode.PANORAMA -> {
                if (state.panoramaState.isCapturing) {
                    finishAndStitchPanorama()
                } else {
                    startPanorama()
                }
            }
            ShootingMode.TIMELAPSE -> {
                if (state.timeLapseState.isRunning) {
                    stopTimeLapse()
                } else {
                    startTimeLapse()
                }
            }
            else -> {
                if (state.countdownRemainingSec > 0) {
                    timerJob?.cancel()
                    _uiState.update { it.copy(countdownRemainingSec = 0) }
                    return
                }
                val sec = state.settings.timerDuration.seconds
                val burstCount = state.settings.soloBurstCount.count
                val aeb = state.settings.aebMode

                if (aeb != AebMode.OFF) {
                    // Canon 3-shot Auto Exposure Bracketing (-EV, 0 EV, +EV)
                    timerJob?.cancel()
                    timerJob = viewModelScope.launch {
                        for (t in sec downTo 1) {
                            _uiState.update { it.copy(countdownRemainingSec = t) }
                            delay(1000)
                        }
                        _uiState.update { it.copy(countdownRemainingSec = 0) }
                        val baseEv = _uiState.value.settings.evCompensationSteps
                        val offsets = listOf(-aeb.evOffsetSteps, 0, aeb.evOffsetSteps)
                        for (offset in offsets) {
                            updateSettings { it.copy(evCompensationSteps = baseEv + offset) }
                            delay(220)
                            capturePhoto()
                            delay(550)
                        }
                        updateSettings { it.copy(evCompensationSteps = baseEv) }
                        showBanner("Canon ${aeb.label} 3-shot bracket saved")
                    }
                    return
                }

                if (sec > 0 || burstCount > 1) {
                    timerJob?.cancel()
                    timerJob = viewModelScope.launch {
                        for (t in sec downTo 1) {
                            _uiState.update { it.copy(countdownRemainingSec = t) }
                            delay(1000)
                        }
                        _uiState.update { it.copy(countdownRemainingSec = 0) }
                        for (shot in 1..burstCount) {
                            capturePhoto()
                            if (shot < burstCount) {
                                delay(650)
                            }
                        }
                    }
                } else {
                    capturePhoto()
                }
            }
        }
    }

    private fun startVideoRecording(isSlowMo: Boolean) {
        val hasAudio = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        cameraEngine.startVideoRecording(
            isSlowMo = isSlowMo,
            settings = _uiState.value.settings,
            hasAudioPermission = hasAudio,
            onStarted = { fpsLabel ->
                _uiState.update {
                    it.copy(isVideoRecording = true, videoElapsedSec = 0, videoFpsBadge = fpsLabel)
                }
                videoTimerJob?.cancel()
                videoTimerJob = viewModelScope.launch {
                    var s = 0
                    while (_uiState.value.isVideoRecording) {
                        delay(1000)
                        s++
                        _uiState.update { it.copy(videoElapsedSec = s) }
                    }
                }
            },
            onFailed = { msg -> showBanner(msg) }
        )
    }

    private fun stopVideoRecording() {
        val elapsed = _uiState.value.videoElapsedSec
        videoTimerJob?.cancel()
        videoTimerJob = null
        cameraEngine.stopVideoRecording { uri ->
            _uiState.update { it.copy(isVideoRecording = false, videoElapsedSec = 0) }
            if (uri != null) {
                viewModelScope.launch {
                    // Brief settle delay so MediaStore finalizes video metadata before thumbnail load
                    delay(180)
                    val thumb = loadMediaThumbnail(getApplication(), uri, isVideo = true, maxDim = 240)
                    if (thumb != null) {
                        _uiState.update { it.copy(lastThumbnail = thumb, lastThumbnailIsVideo = true) }
                    } else {
                        refreshLatestThumbnail()
                    }
                }
                showBanner("Saved MP4 to Movies/TrueLens")
            } else {
                showBanner(if (elapsed < 1) "Record at least 1 second to save video" else "Video save failed")
            }
        }
    }

    private fun scanDocumentNow() {
        if (_uiState.value.isScanningDocument) return
        val style = _uiState.value.settings.scanStyle
        _uiState.update { it.copy(isScanningDocument = true, showShutterFlash = true) }
        cameraEngine.capturePanoramaFrame(_uiState.value.settings) { bmp ->
            viewModelScope.launch {
                delay(120)
                _uiState.update { it.copy(showShutterFlash = false) }
                if (bmp == null) {
                    _uiState.update { it.copy(isScanningDocument = false) }
                    showBanner("Document capture failed")
                    return@launch
                }
                val result = DocumentScanner.enhanceAndSave(getApplication(), bmp, style)
                bmp.recycle()
                val thumb = result.jpegUri?.let { loadScaledBitmap(it, 240) }
                _uiState.update {
                    it.copy(
                        isScanningDocument = false,
                        lastThumbnail = thumb ?: it.lastThumbnail
                    )
                }
                showBanner(
                    if (result.pdfUri != null) "Scanned PDF (Documents/TrueLens) + JPG saved"
                    else "Scanned Document JPG saved"
                )
            }
        }
    }

    // --- .CUBE 3D LUT, SPLIT SCREEN, MIRROR-SAVE, BULB & FOCUS STACKING ---
    fun importCubeLut(uri: Uri) {
        viewModelScope.launch {
            val lut = CubeLutParser.parseFromUri(getApplication(), uri)
            if (lut != null) {
                _uiState.update { it.copy(activeLut = lut) }
                showBanner("Loaded 3D LUT: ${lut.name} (${lut.size}³)")
            } else {
                showBanner("Invalid .cube 3D LUT file")
            }
        }
    }

    fun clearCubeLut() {
        _uiState.update { it.copy(activeLut = null) }
        showBanner("Cleared 3D LUT")
    }

    fun toggleSplitScreen() {
        updateSettings { it.copy(splitScreenEnabled = !it.splitScreenEnabled) }
    }

    fun updateSplitScreenPosition(normX: Float) {
        _uiState.update { it.copy(splitScreenPosition = normX.coerceIn(0.05f, 0.95f)) }
    }

    fun toggleMirrorFrontSave() {
        updateSettings { it.copy(mirrorFrontSave = !it.mirrorFrontSave) }
        val on = _uiState.value.settings.mirrorFrontSave
        showBanner(if (on) "Front Camera Mirror-Save ON" else "Front Camera True-to-Life Save (Unmirrored)")
    }

    fun toggleMacroFocusStack() {
        updateSettings { it.copy(macroFocusStackEnabled = !it.macroFocusStackEnabled) }
        val on = _uiState.value.settings.macroFocusStackEnabled
        showBanner(if (on) "Super Macro 5-Plane Focus Stacking ON" else "Single Macro Shot")
    }

    fun setLongExposureDuration(seconds: Int) {
        _uiState.update { it.copy(longExposureState = it.longExposureState.copy(durationSec = seconds)) }
    }

    fun setLongExposureStyle(style: LongExposureStyle) {
        _uiState.update { it.copy(longExposureState = it.longExposureState.copy(style = style)) }
    }

    private fun startLongExposure() {
        val le = _uiState.value.longExposureState
        val totalFrames = (le.durationSec * 3).coerceIn(6, 36)
        viewModelScope.launch {
            _uiState.update {
                it.copy(longExposureState = le.copy(isCapturing = true, progress = 0f, accumulatedFrames = 0))
            }
            val collected = mutableListOf<Bitmap>()
            val delayPerFrameMs = (le.durationSec * 1000L) / totalFrames
            for (i in 1..totalFrames) {
                val frame = kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont ->
                    cameraEngine.capturePanoramaFrame(_uiState.value.settings) { bmp ->
                        if (cont.isActive) cont.resume(bmp) {}
                    }
                }
                if (frame != null) collected.add(frame)
                _uiState.update {
                    it.copy(
                        longExposureState = it.longExposureState.copy(
                            progress = i.toFloat() / totalFrames.toFloat(),
                            accumulatedFrames = collected.size
                        )
                    )
                }
                delay(delayPerFrameMs.coerceAtLeast(120L))
            }
            val uri = LongExposureEngine.blendAndSave(
                context = getApplication(),
                frames = collected,
                style = le.style,
                grade = _uiState.value.grade,
                lut = _uiState.value.activeLut
            )
            collected.forEach { it.recycle() }
            val thumb = uri?.let { loadScaledBitmap(it, 240) }
            _uiState.update {
                it.copy(
                    longExposureState = it.longExposureState.copy(isCapturing = false, progress = 0f),
                    lastThumbnail = thumb ?: it.lastThumbnail
                )
            }
            showBanner(if (uri != null) "Long Exposure (${le.durationSec}s ${le.style.label}) saved" else "Long Exposure failed")
        }
    }

    private fun captureSuperMacroFocusStack() {
        if (_uiState.value.isFocusStacking) return
        val lens = _uiState.value.activeLens ?: return
        val maxD = lens.minFocusDistanceDiopters.coerceAtLeast(2f)
        viewModelScope.launch {
            _uiState.update { it.copy(isFocusStacking = true) }
            showBanner("Focus Stacking 5 focal planes — hold steady...")
            val planes = listOf(0.50f, 0.62f, 0.74f, 0.85f, 0.95f)
            val collected = mutableListOf<Bitmap>()
            for (fraction in planes) {
                updateSettings { it.copy(focusDistanceDiopters = maxD * fraction) }
                delay(200)
                val frame = kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont ->
                    cameraEngine.capturePanoramaFrame(_uiState.value.settings) { bmp ->
                        if (cont.isActive) cont.resume(bmp) {}
                    }
                }
                if (frame != null) collected.add(frame)
            }
            val uri = FocusStacker.stackAndSave(
                context = getApplication(),
                frames = collected,
                grade = _uiState.value.grade,
                lut = _uiState.value.activeLut
            )
            collected.forEach { it.recycle() }
            val thumb = uri?.let { loadScaledBitmap(it, 240) }
            _uiState.update {
                it.copy(isFocusStacking = false, lastThumbnail = thumb ?: it.lastThumbnail)
            }
            showBanner(if (uri != null) "Macro Focus Stack saved to Pictures/TrueLens" else "Focus Stack failed")
        }
    }

    // --- STOP MOTION WITH ONION SKINNING ---
    fun captureStopMotionFrame() {
        if (_uiState.value.stopMotionState.isExporting) return
        cameraEngine.capturePanoramaFrame(_uiState.value.settings) { bmp ->
            if (bmp != null) {
                stopMotionFrames.add(bmp)
                _uiState.update {
                    it.copy(
                        stopMotionState = it.stopMotionState.copy(
                            frameCount = stopMotionFrames.size,
                            lastFrameBitmap = bmp
                        ),
                        showShutterFlash = true
                    )
                }
                viewModelScope.launch {
                    delay(90)
                    _uiState.update { it.copy(showShutterFlash = false) }
                }
            }
        }
    }

    fun setStopMotionOpacity(opacity: Float) {
        _uiState.update {
            it.copy(stopMotionState = it.stopMotionState.copy(onionSkinOpacity = opacity.coerceIn(0f, 1f)))
        }
    }

    fun toggleStopMotionOnionSkin() {
        _uiState.update {
            it.copy(stopMotionState = it.stopMotionState.copy(onionSkinEnabled = !it.stopMotionState.onionSkinEnabled))
        }
    }

    fun setStopMotionFps(fps: Int) {
        _uiState.update {
            it.copy(stopMotionState = it.stopMotionState.copy(fps = fps))
        }
    }

    fun undoStopMotionFrame() {
        if (stopMotionFrames.isEmpty()) return
        val removed = stopMotionFrames.removeAt(stopMotionFrames.lastIndex)
        removed.recycle()
        _uiState.update {
            it.copy(
                stopMotionState = it.stopMotionState.copy(
                    frameCount = stopMotionFrames.size,
                    lastFrameBitmap = stopMotionFrames.lastOrNull()
                )
            )
        }
        showBanner("Removed last frame (${stopMotionFrames.size} left)")
    }

    fun exportStopMotionVideo() {
        val framesCopy = stopMotionFrames.toList()
        if (framesCopy.size < 2) {
            showBanner("Capture at least 2 frames to export Stop Motion")
            return
        }
        val fps = _uiState.value.stopMotionState.fps
        val grade = _uiState.value.grade
        viewModelScope.launch {
            _uiState.update { it.copy(stopMotionState = it.stopMotionState.copy(isExporting = true)) }
            val uri = StopMotionExporter.exportToMp4(getApplication(), framesCopy, fps, grade)
            if (uri != null) {
                stopMotionFrames.forEach { it.recycle() }
                stopMotionFrames.clear()
                _uiState.update {
                    it.copy(
                        stopMotionState = StopMotionState(
                            onionSkinOpacity = it.stopMotionState.onionSkinOpacity,
                            onionSkinEnabled = it.stopMotionState.onionSkinEnabled,
                            fps = fps
                        )
                    )
                }
                val thumb = loadMediaThumbnail(getApplication(), uri, isVideo = true, maxDim = 240)
                if (thumb != null) {
                    _uiState.update { it.copy(lastThumbnail = thumb, lastThumbnailIsVideo = true) }
                } else {
                    refreshLatestThumbnail()
                }
                showBanner("Stop Motion MP4 (${framesCopy.size}f @ ${fps}fps) saved to Movies/TrueLens")
            } else {
                _uiState.update { it.copy(stopMotionState = it.stopMotionState.copy(isExporting = false)) }
                showBanner("Stop Motion export failed")
            }
        }
    }

    // --- PANORAMA ---
    private fun startPanorama() {
        panoramaFrames.forEach { it.recycle() }
        panoramaFrames.clear()
        lastPanoYawAtCapture = 0f
        updateSettings { it.copy(aeAfLocked = true) }
        _uiState.update { it.copy(panoramaState = PanoramaState(isCapturing = true, frameCount = 0)) }
        captureNextPanoramaFrame()
        showBanner("Pan slowly right • Exposure locked • Tap shutter to stitch")
    }

    fun captureNextPanoramaFrame() {
        if (!_uiState.value.panoramaState.isCapturing) return
        if (panoramaFrames.size >= 10) {
            finishAndStitchPanorama()
            return
        }
        val settings = _uiState.value.settings
        cameraEngine.capturePanoramaFrame(settings) { bmp ->
            if (bmp != null) {
                panoramaFrames.add(bmp)
                lastPanoYawAtCapture = _uiState.value.panoramaState.accumulatedYawDeg
                _uiState.update {
                    it.copy(
                        panoramaState = it.panoramaState.copy(frameCount = panoramaFrames.size),
                        showShutterFlash = true
                    )
                }
                viewModelScope.launch {
                    delay(90)
                    _uiState.update { it.copy(showShutterFlash = false) }
                }
            }
        }
    }

    fun onGyroYawDelta(deltaDegrees: Float) {
        val pano = _uiState.value.panoramaState
        if (!pano.isCapturing || pano.isStitching) return
        val updatedYaw = pano.accumulatedYawDeg + deltaDegrees
        _uiState.update { it.copy(panoramaState = it.panoramaState.copy(accumulatedYawDeg = updatedYaw)) }
        if (kotlin.math.abs(updatedYaw - lastPanoYawAtCapture) >= 18f) {
            lastPanoYawAtCapture = updatedYaw
            captureNextPanoramaFrame()
        }
    }

    fun finishAndStitchPanorama() {
        val framesCopy = panoramaFrames.toList()
        panoramaFrames.clear()
        updateSettings { it.copy(aeAfLocked = false) }
        if (framesCopy.isEmpty()) {
            _uiState.update { it.copy(panoramaState = PanoramaState()) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(panoramaState = it.panoramaState.copy(isCapturing = false, isStitching = true)) }
            val uri = PanoramaStitcher.stitchAndSave(
                context = getApplication(),
                frames = framesCopy,
                grade = _uiState.value.grade
            )
            framesCopy.forEach { it.recycle() }
            val thumb = uri?.let { loadScaledBitmap(it, 240) }
            _uiState.update {
                it.copy(
                    panoramaState = PanoramaState(),
                    lastThumbnail = thumb ?: it.lastThumbnail
                )
            }
            showBanner(if (uri != null) "Panorama stitched & saved to Pictures/TrueLens" else "Panorama stitch failed")
        }
    }

    private fun cancelPanorama() {
        panoramaFrames.forEach { it.recycle() }
        panoramaFrames.clear()
        updateSettings { it.copy(aeAfLocked = false) }
        _uiState.update { it.copy(panoramaState = PanoramaState()) }
    }

    // --- TIME-LAPSE INTERVALOMETER ---
    fun setTimeLapseInterval(seconds: Int) {
        _uiState.update { it.copy(timeLapseState = it.timeLapseState.copy(intervalSeconds = seconds)) }
    }

    fun setTimeLapseTargetFrames(frames: Int) {
        _uiState.update { it.copy(timeLapseState = it.timeLapseState.copy(targetFrames = frames)) }
    }

    private fun startTimeLapse() {
        val tl = _uiState.value.timeLapseState
        timeLapseFrames.forEach { it.recycle() }
        timeLapseFrames.clear()
        updateSettings { it.copy(aeAfLocked = true) }
        _uiState.update {
            it.copy(timeLapseState = tl.copy(isRunning = true, capturedFrames = 0, elapsedSeconds = 0))
        }
        timeLapseJob?.cancel()
        timeLapseJob = viewModelScope.launch {
            var elapsed = 0
            var captured = 0
            while (_uiState.value.timeLapseState.isRunning) {
                if (elapsed % tl.intervalSeconds == 0) {
                    val frame = kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont ->
                        cameraEngine.capturePanoramaFrame(_uiState.value.settings) { bmp ->
                            if (cont.isActive) cont.resume(bmp) {}
                        }
                    }
                    if (frame != null) timeLapseFrames.add(frame)
                    captured++
                    _uiState.update {
                        it.copy(timeLapseState = it.timeLapseState.copy(capturedFrames = captured))
                    }
                    if (tl.targetFrames > 0 && captured >= tl.targetFrames) {
                        stopTimeLapse()
                        break
                    }
                }
                delay(1000)
                elapsed++
                _uiState.update {
                    it.copy(timeLapseState = it.timeLapseState.copy(elapsedSeconds = elapsed))
                }
            }
        }
        showBanner("Time-Lapse started (${tl.intervalSeconds}s interval • Auto-compiles MP4)")
    }

    fun stopTimeLapse() {
        timeLapseJob?.cancel()
        timeLapseJob = null
        val framesCopy = timeLapseFrames.toList()
        timeLapseFrames.clear()
        updateSettings { it.copy(aeAfLocked = false) }
        _uiState.update { it.copy(timeLapseState = it.timeLapseState.copy(isRunning = false)) }
        if (framesCopy.size >= 2) {
            val grade = _uiState.value.grade
            viewModelScope.launch {
                showBanner("Compiling Time-Lapse MP4 (${framesCopy.size} frames @ 24fps)...")
                val uri = StopMotionExporter.exportToMp4(getApplication(), framesCopy, 24, grade)
                framesCopy.forEach { it.recycle() }
                if (uri != null) {
                    val thumb = loadMediaThumbnail(getApplication(), uri, isVideo = true, maxDim = 240)
                    if (thumb != null) {
                        _uiState.update { it.copy(lastThumbnail = thumb, lastThumbnailIsVideo = true) }
                    } else {
                        refreshLatestThumbnail()
                    }
                    showBanner("Time-Lapse MP4 saved to Movies/TrueLens")
                } else {
                    showBanner("Time-Lapse MP4 compile failed")
                }
            }
        } else {
            framesCopy.forEach { it.recycle() }
        }
    }

    // --- FULL IN-APP GALLERY ---
    fun openGallery() {
        _uiState.update { it.copy(isGalleryOpen = true) }
    }

    fun closeGallery() {
        _uiState.update { it.copy(isGalleryOpen = false) }
    }

    fun openDarkroomForUri(uri: Uri) {
        darkroomUndoStack.clear()
        darkroomRedoStack.clear()
        viewModelScope.launch {
            val bmp = loadScaledBitmap(uri, 1280) ?: return@launch
            val exif = withContext(Dispatchers.IO) {
                com.truelens.camera.grading.PhotoFrameRenderer.readExifSummary(getApplication(), uri)
            }
            _uiState.update {
                it.copy(
                    isGalleryOpen = false,
                    isDarkroomOpen = true,
                    darkroomSourceUri = uri,
                    darkroomSourceBitmap = bmp,
                    darkroomGradedBitmap = bmp,
                    darkroomGrade = LightroomGrade.NEUTRAL,
                    darkroomVerticalTilt = 0f,
                    darkroomHorizontalTilt = 0f,
                    darkroomRotationDeg = 0f,
                    darkroomFrameStyle = com.truelens.camera.grading.ExportFrameStyle.NONE,
                    darkroomDateStamp = com.truelens.camera.grading.DateStampStyle.OFF,
                    darkroomExif = exif,
                    canUndoDarkroom = false,
                    canRedoDarkroom = false
                )
            }
        }
    }

    fun cycleCaptureFormat() {
        val supportsRaw = _uiState.value.activeLens?.supportsRawSensor == true
        if (!supportsRaw) {
            showBanner("Sensor supports Clean Zero-AI JPEG (RAW_SENSOR unavailable on this device)")
            return
        }
        updateSettings { s ->
            val next = when (s.captureFormat) {
                CaptureFormat.RAW_PLUS_JPG -> CaptureFormat.RAW_ONLY
                CaptureFormat.RAW_ONLY -> CaptureFormat.CLEAN_JPG
                CaptureFormat.CLEAN_JPG -> CaptureFormat.RAW_PLUS_JPG
            }
            s.copy(captureFormat = next)
        }
    }

    fun toggleZeroAiBypass() {
        updateSettings { it.copy(zeroAiBypass = !it.zeroAiBypass) }
        val enabled = _uiState.value.settings.zeroAiBypass
        showBanner(if (enabled) "Zero-AI Pure Sensor Pipeline ON" else "Standard Sensor Pipeline")
    }

    fun toggleFocusPeaking() = updateSettings { it.copy(focusPeaking = !it.focusPeaking) }
    fun toggleZebras() = updateSettings { it.copy(zebras = !it.zebras) }
    fun toggleHistogram() = updateSettings { it.copy(histogram = !it.histogram) }
    fun toggleLevelGauge() = updateSettings { it.copy(levelGauge = !it.levelGauge) }

    fun cycleGridOverlay() {
        updateSettings { s ->
            val values = GridOverlay.entries
            val next = values[(s.gridOverlay.ordinal + 1) % values.size]
            s.copy(gridOverlay = next)
        }
    }

    fun setIso(iso: Int?) = updateSettings {
        it.copy(
            iso = iso,
            exposureMode = if (iso != null) ExposureMode.MANUAL_M else it.exposureMode
        )
    }

    fun setShutterSpeedNs(ns: Long?) = updateSettings {
        it.copy(
            shutterSpeedNs = ns,
            exposureMode = if (ns != null && it.exposureMode == ExposureMode.AUTO_P) {
                ExposureMode.SHUTTER_TV
            } else {
                it.exposureMode
            }
        )
    }

    fun setEvSteps(steps: Int) = updateSettings { it.copy(evCompensationSteps = steps) }
    fun setFocusDiopters(diopters: Float?) = updateSettings { it.copy(focusDistanceDiopters = diopters) }
    fun setWhiteBalanceKelvin(kelvin: Int?) = updateSettings { it.copy(whiteBalanceKelvin = kelvin) }

    fun setActiveDeck(deck: ActiveDeck) {
        _uiState.update { state ->
            state.copy(activeDeck = if (state.activeDeck == deck) ActiveDeck.NONE else deck)
        }
    }

    fun setBypassLiveGrade(bypass: Boolean) {
        _uiState.update { it.copy(bypassLiveGrade = bypass) }
    }

    fun updateGrade(transform: (LightroomGrade) -> LightroomGrade) {
        _uiState.update { state ->
            state.copy(grade = transform(state.grade), activePresetId = "custom")
        }
    }

    fun applyPreset(preset: GradePreset) {
        _uiState.update { state ->
            state.copy(grade = preset.grade, activePresetId = preset.id)
        }
    }

    fun resetGrade() {
        _uiState.update { state ->
            state.copy(grade = LightroomGrade.NEUTRAL, activePresetId = BuiltInPresets.ALL.first().id)
        }
    }

    fun triggerTapToFocus(normX: Float, normY: Float) {
        if (_uiState.value.settings.aeAfLocked) {
            updateSettings { it.copy(aeAfLocked = false) }
        }
        cameraEngine.triggerTapToFocus(normX, normY)
    }

    fun toggleAeAfLock() {
        val next = !_uiState.value.settings.aeAfLocked
        updateSettings { it.copy(aeAfLocked = next) }
        showBanner(if (next) "AF / AE LOCKED" else "AF / AE UNLOCKED")
    }

    fun toggleTrueLightAuto() {
        val next = !_uiState.value.settings.trueLightAuto
        updateSettings { it.copy(trueLightAuto = next) }
        showBanner(
            if (next) "True Light Auto ON — optical exposure adapts to light"
            else "True Light Auto OFF — manual EV control"
        )
    }

    fun onLiveHistogramSampled(bins: FloatArray) {
        _uiState.update { it.copy(histogram = bins) }
        evaluateTrueLightAuto(bins)
    }

    fun updateAmbientLux(lux: Float) {
        latestAmbientLux = lux
    }

    private fun evaluateTrueLightAuto(histogram: FloatArray) {
        val state = _uiState.value
        val lens = state.activeLens ?: return
        val analysis = TrueLightAutoMeter.analyze(
            histogram = histogram,
            ambientLux = latestAmbientLux,
            currentEvSteps = state.settings.evCompensationSteps,
            minEvSteps = lens.aeCompensationRange.lower,
            maxEvSteps = lens.aeCompensationRange.upper
        )
        _uiState.update { it.copy(lightAnalysis = analysis) }

        val now = System.currentTimeMillis()
        if (state.settings.trueLightAuto &&
            state.settings.exposureMode == ExposureMode.AUTO_P &&
            !state.settings.aeAfLocked &&
            now - lastAutoMeterAdjustMs >= 750L &&
            analysis.recommendedEvSteps != state.settings.evCompensationSteps
        ) {
            lastAutoMeterAdjustMs = now
            updateSettings { it.copy(evCompensationSteps = analysis.recommendedEvSteps) }
        }
    }

    fun flipFrontBackCamera() {
        if (_uiState.value.isVideoRecording) stopVideoRecording()
        val state = _uiState.value
        val active = state.activeLens ?: return
        val target = if (active.isFront) {
            state.lenses.firstOrNull { !it.isFront && it.displayLabel == "1x" }
                ?: state.lenses.firstOrNull { !it.isFront }
        } else {
            state.lenses.firstOrNull { it.isFront }
        }
        if (target != null) {
            selectLens(target)
            _uiState.update { it.copy(zoomRatio = 1.0f) }
            showBanner(if (target.isFront) "Front Camera" else "Rear Camera (${target.displayLabel})")
        } else {
            showBanner("Only one camera available on this device")
        }
    }

    fun updateCameraZoom(zoomFactorDelta: Float) {
        val lens = _uiState.value.activeLens
        val minZ = lens?.minZoomRatio ?: 1.0f
        val maxZ = lens?.maxZoomRatio ?: 8.0f
        val next = (_uiState.value.zoomRatio * zoomFactorDelta).coerceIn(minZ, maxZ)
        _uiState.update { it.copy(zoomRatio = next) }
        cameraEngine.setZoom(next)
    }

    /**
     * One-tap floating lens / zoom switcher (0.6, 1x, 2, 3 — matching modern flagship camera bars).
     * Automatically switches to a real physical Ultrawide/Main/Telephoto lens when present on the phone,
     * OR uses Camera2 logical multi-camera CONTROL_ZOOM_RATIO (e.g. 0.6x ultrawide on API 30+ phones).
     */
    fun selectZoomStop(targetZoom: Float) {
        val state = _uiState.value
        val rearLenses = state.lenses.filter { !it.isFront }
        if (rearLenses.isEmpty()) return

        val mainLens = rearLenses.firstOrNull { it.displayLabel == "1x" } ?: rearLenses.first()
        val matchedPhysical = when {
            targetZoom < 0.9f -> rearLenses.firstOrNull { it.displayLabel.startsWith("0.") }
            targetZoom in 0.9f..1.2f -> mainLens
            targetZoom in 1.8f..2.3f -> rearLenses.firstOrNull { it.displayLabel == "2x" }
            targetZoom >= 2.8f -> rearLenses.firstOrNull { it.displayLabel == "3x" || it.displayLabel == "5x" }
            else -> null
        }

        if (matchedPhysical != null) {
            if (matchedPhysical.cameraId != state.activeLens?.cameraId) {
                selectLens(matchedPhysical)
            }
            _uiState.update { it.copy(zoomRatio = 1.0f) }
            cameraEngine.setZoom(1.0f)
        } else {
            // Switch to main 1x lens and apply optical/logical zoom (supports 0.6x if mainLens.minZoomRatio <= 0.6f!)
            if (mainLens.cameraId != state.activeLens?.cameraId) {
                selectLens(mainLens)
            }
            val safeZoom = targetZoom.coerceIn(mainLens.minZoomRatio, mainLens.maxZoomRatio)
            _uiState.update { it.copy(zoomRatio = safeZoom) }
            cameraEngine.setZoom(safeZoom)
        }
    }

    fun cycleFlashMode() {
        val entries = com.truelens.camera.camera.FlashMode.entries
        updateSettings { s ->
            val next = entries[(entries.indexOf(s.flashMode) + 1) % entries.size]
            s.copy(flashMode = next)
        }
        val label = _uiState.value.settings.flashMode.label
        showBanner("Flash: $label")
    }

    fun cycleVideoQuality() {
        val entries = com.truelens.camera.camera.VideoQuality.entries
        updateSettings { s ->
            val next = entries[(entries.indexOf(s.videoQuality) + 1) % entries.size]
            s.copy(videoQuality = next)
        }
        showBanner("Video: ${_uiState.value.settings.videoQuality.label}")
    }

    fun toggleMicMuted() {
        updateSettings { it.copy(micMuted = !it.micMuted) }
        showBanner(if (_uiState.value.settings.micMuted) "Video Mic Muted" else "Video Mic On")
    }

    fun toggleCinema180Shutter() {
        updateSettings { it.copy(cinema180Shutter = !it.cinema180Shutter) }
        val on = _uiState.value.settings.cinema180Shutter
        showBanner(if (on) "Cinema 180° Shutter ON (1/(2×FPS))" else "Cinema 180° Shutter OFF")
    }

    fun cycleDateStampStyle() {
        val entries = com.truelens.camera.grading.DateStampStyle.entries
        updateSettings { s ->
            val next = entries[(entries.indexOf(s.dateStampStyle) + 1) % entries.size]
            s.copy(dateStampStyle = next)
        }
        showBanner("Date Stamp: ${_uiState.value.settings.dateStampStyle.label}")
    }

    fun cycleHandsFreeMode() {
        val entries = com.truelens.camera.camera.HandsFreeMode.entries
        val next = entries[(entries.indexOf(_uiState.value.settings.handsFreeMode) + 1) % entries.size]
        updateSettings { it.copy(handsFreeMode = next) }
        syncHandsFreeControllers()
        val hint = when (next) {
            com.truelens.camera.camera.HandsFreeMode.OFF -> "Hands-Free Off"
            com.truelens.camera.camera.HandsFreeMode.VOICE -> "Voice ON • Say \"Capture\" or \"Cheese\""
            com.truelens.camera.camera.HandsFreeMode.HAND -> "Palm ON • Raise open hand to snap"
            com.truelens.camera.camera.HandsFreeMode.BOTH -> "Voice + Palm ON • Say \"Capture\" or raise hand"
        }
        showBanner(hint)
    }

    fun syncHandsFreeControllers() {
        val mode = _uiState.value.settings.handsFreeMode
        val hasAudio = androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val wantVoice = mode == com.truelens.camera.camera.HandsFreeMode.VOICE ||
            mode == com.truelens.camera.camera.HandsFreeMode.BOTH
        val wantPalm = mode == com.truelens.camera.camera.HandsFreeMode.HAND ||
            mode == com.truelens.camera.camera.HandsFreeMode.BOTH
        handsFreeController.setVoiceEnabled(wantVoice, hasAudio)
        handsFreeController.setPalmEnabled(wantPalm)
    }

    fun onPalmPreviewSample(bitmap: Bitmap) {
        val mode = _uiState.value.settings.handsFreeMode
        val wantPalm = mode == com.truelens.camera.camera.HandsFreeMode.HAND ||
            mode == com.truelens.camera.camera.HandsFreeMode.BOTH
        if (!wantPalm) {
            bitmap.recycle()
            return
        }
        handsFreeController.analyzePreviewFrameForPalm(bitmap)
    }

    private fun onHandsFreeTriggered(reason: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val state = _uiState.value
            if (state.isGalleryOpen || state.isDarkroomOpen || state.isPoseInsposOpen) return@launch
            showBanner(reason)
            if (state.settings.timerDuration.seconds == 0 && reason.contains("Hand", ignoreCase = true)) {
                // Brief 2s grace countdown so the user can lower their hand cleanly before the shot snaps
                timerJob?.cancel()
                timerJob = viewModelScope.launch {
                    for (sec in 2 downTo 1) {
                        _uiState.update { it.copy(countdownRemainingSec = sec) }
                        delay(1000)
                    }
                    _uiState.update { it.copy(countdownRemainingSec = 0) }
                    capturePhoto()
                }
            } else {
                onShutterPressed()
            }
        }
    }

    // --- DUAL-CAMERA REACTION MODE (Draggable Split Divider + Floating Phone-Call PiP) ---
    fun toggleDualLayout() {
        val next = if (_uiState.value.settings.dualLayout == com.truelens.camera.grading.DualCameraLayout.SPLIT) {
            com.truelens.camera.grading.DualCameraLayout.PIP
        } else {
            com.truelens.camera.grading.DualCameraLayout.SPLIT
        }
        updateSettings { it.copy(dualLayout = next) }
        showBanner(if (next == com.truelens.camera.grading.DualCameraLayout.SPLIT) "Dual: Draggable Split" else "Dual: Phone-Call PiP")
    }

    fun updateDualSplitRatio(ratio: Float) {
        _uiState.update { it.copy(dualSplitRatio = ratio.coerceIn(0.20f, 0.80f)) }
    }

    fun updateDualPipPosition(normX: Float, normY: Float) {
        _uiState.update {
            it.copy(
                dualPipX = normX.coerceIn(0.03f, 0.68f),
                dualPipY = normY.coerceIn(0.03f, 0.72f)
            )
        }
    }

    fun swapDualCameras() {
        _uiState.update { it.copy(dualSwapped = !it.dualSwapped) }
    }

    fun attachDualFrontTextureView(textureView: android.view.TextureView) {
        val pair = dualCameraController.findConcurrentRearFrontPair()
        val frontId = pair?.second
        if (frontId != null) {
            dualCameraController.startSecondaryLive(frontId, textureView) { live ->
                _uiState.update { it.copy(isDualConcurrentLive = live) }
            }
        } else {
            _uiState.update { it.copy(isDualConcurrentLive = false) }
        }
    }

    fun detachDualFrontTextureView() {
        dualCameraController.stopSecondary()
        _uiState.update { it.copy(isDualConcurrentLive = false) }
    }

    fun captureDualReactionPhoto() {
        val state = _uiState.value
        val rearLens = state.lenses.firstOrNull { !it.isFront && it.displayLabel == "1x" }
            ?: state.lenses.firstOrNull { !it.isFront }
        val frontLens = state.lenses.firstOrNull { it.isFront }
        if (rearLens == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCapturingDual = true, showShutterFlash = true) }
            delay(90)
            _uiState.update { it.copy(showShutterFlash = false) }

            val origLens = _uiState.value.activeLens
            // Ensure primary capture comes from Rear camera (if user happened to flip to Front before entering Dual)
            if (origLens?.isFront == true && previewSurface != null) {
                openLens(rearLens, previewSurface!!)
                delay(650)
            }
            // Step 1: Capture primary Rear frame
            val backBmp = captureSingleMemoryBitmap()

            // Step 2: Grab Front frame (either simultaneously via hardware concurrent TextureView,
            // or seamlessly via BeReal-style quick Front lens flip on single-stream hardware!)
            var frontBmp = if (dualCameraController.isConcurrentLive) {
                dualCameraController.grabLiveFrontBitmap(mirrorHorizontal = state.settings.mirrorFrontSave)
            } else null

            if (frontBmp == null && frontLens != null && previewSurface != null) {
                showBanner("Capturing reaction...")
                openLens(frontLens, previewSurface!!)
                delay(750)
                frontBmp = captureSingleMemoryBitmap()
                if (origLens != null && origLens.cameraId != frontLens.cameraId) {
                    openLens(origLens, previewSurface!!)
                }
            } else if (origLens != null && origLens.isFront && previewSurface != null) {
                openLens(origLens, previewSurface!!)
            }

            if (backBmp != null) {
                val secondaryBmp = frontBmp ?: backBmp.copy(Bitmap.Config.ARGB_8888, false)
                val savedUri = withContext(Dispatchers.IO) {
                    try {
                        val s = _uiState.value
                        val composed = com.truelens.camera.grading.PhotoFrameRenderer.composeDualPhoto(
                            backBitmap = backBmp,
                            frontBitmap = secondaryBmp,
                            layout = s.settings.dualLayout,
                            splitRatio = s.dualSplitRatio,
                            pipX = s.dualPipX,
                            pipY = s.dualPipY,
                            swapCameras = s.dualSwapped
                        )
                        val graded = if (!s.grade.isNeutral || s.activeLut != null) {
                            BitmapGrader.applyGrade(composed, s.grade, s.activeLut).also {
                                if (it !== composed) composed.recycle()
                            }
                        } else composed
                        val stamped = if (s.settings.dateStampStyle != com.truelens.camera.grading.DateStampStyle.OFF) {
                            com.truelens.camera.grading.PhotoFrameRenderer.applyDateStamp(
                                graded, s.settings.dateStampStyle
                            ).also { if (it !== graded) graded.recycle() }
                        } else graded

                        val out = ByteArrayOutputStream()
                        stamped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        val thumb = Bitmap.createScaledBitmap(stamped, 240, (240 * stamped.height / stamped.width.coerceAtLeast(1)).coerceAtLeast(1), true)
                        stamped.recycle()

                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_Dual_$stamp.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val cr = getApplication<Application>().contentResolver
                        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            cr.openOutputStream(uri)?.use { it.write(out.toByteArray()) }
                            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                            cr.update(uri, done, null, null)
                            _uiState.update { it.copy(lastThumbnail = thumb, lastThumbnailIsVideo = false) }
                        }
                        uri
                    } catch (_: Exception) {
                        null
                    } finally {
                        backBmp.recycle()
                        secondaryBmp.recycle()
                    }
                }
                showBanner(if (savedUri != null) "Dual Reaction photo saved" else "Dual capture failed")
            } else {
                showBanner("Dual capture unavailable")
            }
            _uiState.update { it.copy(isCapturingDual = false) }
        }
    }

    private suspend fun captureSingleMemoryBitmap(): Bitmap? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cameraEngine.capturePanoramaFrame(_uiState.value.settings) { bmp ->
                if (cont.isActive) cont.resume(bmp) {}
            }
        }

    fun capturePhoto() {
        val state = _uiState.value
        val useScreenFlash = state.activeLens?.isFront == true &&
            (state.settings.flashMode == com.truelens.camera.camera.FlashMode.ON ||
                (state.settings.flashMode == com.truelens.camera.camera.FlashMode.AUTO && latestAmbientLux < 60f))
        viewModelScope.launch {
            if (useScreenFlash) {
                _uiState.update { it.copy(showFrontScreenFlash = true) }
                delay(180)
            } else {
                _uiState.update { it.copy(showShutterFlash = true) }
            }
            cameraEngine.capturePhoto(state.grade, state.settings, state.activeLut)
            delay(if (useScreenFlash) 240 else 140)
            _uiState.update { it.copy(showShutterFlash = false, showFrontScreenFlash = false) }
        }
    }

    private fun pushDarkroomUndoSnapshot(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUndoPushMs < 350L) return
        lastUndoPushMs = now
        val s = _uiState.value
        darkroomUndoStack.addLast(
            DarkroomSnapshot(
                s.darkroomGrade,
                s.darkroomVerticalTilt,
                s.darkroomHorizontalTilt,
                s.darkroomRotationDeg,
                s.darkroomFrameStyle,
                s.darkroomDateStamp
            )
        )
        if (darkroomUndoStack.size > 30) darkroomUndoStack.removeFirst()
        darkroomRedoStack.clear()
    }

    fun undoDarkroom() {
        if (darkroomUndoStack.isEmpty()) return
        val cur = _uiState.value
        darkroomRedoStack.addLast(
            DarkroomSnapshot(
                cur.darkroomGrade,
                cur.darkroomVerticalTilt,
                cur.darkroomHorizontalTilt,
                cur.darkroomRotationDeg,
                cur.darkroomFrameStyle,
                cur.darkroomDateStamp
            )
        )
        val prev = darkroomUndoStack.removeLast()
        _uiState.update {
            it.copy(
                darkroomGrade = prev.grade,
                darkroomVerticalTilt = prev.vTilt,
                darkroomHorizontalTilt = prev.hTilt,
                darkroomRotationDeg = prev.rotDeg,
                darkroomFrameStyle = prev.frameStyle,
                darkroomDateStamp = prev.dateStamp,
                canUndoDarkroom = darkroomUndoStack.isNotEmpty(),
                canRedoDarkroom = darkroomRedoStack.isNotEmpty()
            )
        }
        recomputeDarkroomPreview()
    }

    fun redoDarkroom() {
        if (darkroomRedoStack.isEmpty()) return
        val cur = _uiState.value
        darkroomUndoStack.addLast(
            DarkroomSnapshot(
                cur.darkroomGrade,
                cur.darkroomVerticalTilt,
                cur.darkroomHorizontalTilt,
                cur.darkroomRotationDeg,
                cur.darkroomFrameStyle,
                cur.darkroomDateStamp
            )
        )
        val next = darkroomRedoStack.removeLast()
        _uiState.update {
            it.copy(
                darkroomGrade = next.grade,
                darkroomVerticalTilt = next.vTilt,
                darkroomHorizontalTilt = next.hTilt,
                darkroomRotationDeg = next.rotDeg,
                darkroomFrameStyle = next.frameStyle,
                darkroomDateStamp = next.dateStamp,
                canUndoDarkroom = darkroomUndoStack.isNotEmpty(),
                canRedoDarkroom = darkroomRedoStack.isNotEmpty()
            )
        }
        recomputeDarkroomPreview()
    }

    fun setDarkroomFrameStyle(style: com.truelens.camera.grading.ExportFrameStyle) {
        pushDarkroomUndoSnapshot(force = true)
        _uiState.update {
            it.copy(
                darkroomFrameStyle = style,
                canUndoDarkroom = darkroomUndoStack.isNotEmpty(),
                canRedoDarkroom = darkroomRedoStack.isNotEmpty()
            )
        }
        recomputeDarkroomPreview()
    }

    fun setDarkroomDateStamp(style: com.truelens.camera.grading.DateStampStyle) {
        pushDarkroomUndoSnapshot(force = true)
        _uiState.update {
            it.copy(
                darkroomDateStamp = style,
                canUndoDarkroom = darkroomUndoStack.isNotEmpty(),
                canRedoDarkroom = darkroomRedoStack.isNotEmpty()
            )
        }
        recomputeDarkroomPreview()
    }

    fun deleteDarkroomPhoto() {
        val uri = _uiState.value.darkroomSourceUri ?: _uiState.value.lastCapturedPhoto?.jpegUri ?: return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.delete(uri, null, null) > 0
                } catch (_: Exception) {
                    false
                }
            }
            if (deleted) {
                darkroomUndoStack.clear()
                darkroomRedoStack.clear()
                _uiState.update {
                    it.copy(
                        isDarkroomOpen = false,
                        darkroomSourceUri = null,
                        darkroomSourceBitmap = null,
                        darkroomGradedBitmap = null,
                        canUndoDarkroom = false,
                        canRedoDarkroom = false
                    )
                }
                showBanner("Photo deleted")
                refreshLatestThumbnail()
            } else {
                showBanner("Could not delete photo")
            }
        }
    }

    // --- BUILT-IN DARKROOM EDITOR ---
    fun openDarkroom() {
        val uri = _uiState.value.lastCapturedPhoto?.jpegUri ?: return
        openDarkroomForUri(uri)
    }

    fun closeDarkroom() {
        _uiState.update { it.copy(isDarkroomOpen = false) }
    }

    fun updateDarkroomGrade(transform: (LightroomGrade) -> LightroomGrade) {
        pushDarkroomUndoSnapshot()
        val next = transform(_uiState.value.darkroomGrade)
        _uiState.update {
            it.copy(
                darkroomGrade = next,
                canUndoDarkroom = darkroomUndoStack.isNotEmpty(),
                canRedoDarkroom = darkroomRedoStack.isNotEmpty()
            )
        }
        recomputeDarkroomPreview()
    }

    fun updateDarkroomKeystone(vertical: Float, horizontal: Float, rotationDeg: Float) {
        pushDarkroomUndoSnapshot()
        _uiState.update {
            it.copy(
                darkroomVerticalTilt = vertical,
                darkroomHorizontalTilt = horizontal,
                darkroomRotationDeg = rotationDeg,
                canUndoDarkroom = darkroomUndoStack.isNotEmpty(),
                canRedoDarkroom = darkroomRedoStack.isNotEmpty()
            )
        }
        recomputeDarkroomPreview()
    }

    private fun recomputeDarkroomPreview() {
        val state = _uiState.value
        val src = state.darkroomSourceBitmap ?: return
        darkroomPreviewJob?.cancel()
        darkroomPreviewJob = viewModelScope.launch(Dispatchers.Default) {
            val straightened = BitmapGrader.applyKeystonePerspective(
                src,
                state.darkroomVerticalTilt,
                state.darkroomHorizontalTilt,
                state.darkroomRotationDeg
            )
            val graded = BitmapGrader.applyGrade(straightened, state.darkroomGrade, state.activeLut)
            if (straightened !== src) straightened.recycle()
            val stamped = if (state.darkroomDateStamp != com.truelens.camera.grading.DateStampStyle.OFF) {
                com.truelens.camera.grading.PhotoFrameRenderer.applyDateStamp(
                    graded, state.darkroomDateStamp, state.darkroomExif.dateTakenMs
                ).also { if (it !== graded) graded.recycle() }
            } else graded
            val framed = if (state.darkroomFrameStyle != com.truelens.camera.grading.ExportFrameStyle.NONE) {
                com.truelens.camera.grading.PhotoFrameRenderer.applyExportFrame(
                    stamped, state.darkroomFrameStyle, state.darkroomExif
                ).also { if (it !== stamped) stamped.recycle() }
            } else stamped
            _uiState.update { it.copy(darkroomGradedBitmap = framed) }
        }
    }

    fun applyDarkroomPreset(preset: GradePreset) {
        updateDarkroomGrade { preset.grade }
    }

    fun exportDarkroomCopy() {
        val uri = _uiState.value.darkroomSourceUri ?: _uiState.value.lastCapturedPhoto?.jpegUri ?: return
        val grade = _uiState.value.darkroomGrade
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingDarkroom = true) }
            val success = withContext(Dispatchers.IO) {
                try {
                    val fullBmp = com.truelens.camera.camera.ExifBitmapLoader.loadFull(getApplication(), uri)
                        ?: return@withContext false
                    val s = _uiState.value
                    val straightened = BitmapGrader.applyKeystonePerspective(
                        fullBmp, s.darkroomVerticalTilt, s.darkroomHorizontalTilt, s.darkroomRotationDeg
                    )
                    if (straightened !== fullBmp) fullBmp.recycle()
                    val graded = BitmapGrader.applyGrade(straightened, grade, s.activeLut)
                    straightened.recycle()
                    val stamped = if (s.darkroomDateStamp != com.truelens.camera.grading.DateStampStyle.OFF) {
                        com.truelens.camera.grading.PhotoFrameRenderer.applyDateStamp(
                            graded, s.darkroomDateStamp, s.darkroomExif.dateTakenMs
                        ).also { if (it !== graded) graded.recycle() }
                    } else graded
                    val finalBmp = if (s.darkroomFrameStyle != com.truelens.camera.grading.ExportFrameStyle.NONE) {
                        com.truelens.camera.grading.PhotoFrameRenderer.applyExportFrame(
                            stamped, s.darkroomFrameStyle, s.darkroomExif
                        ).also { if (it !== stamped) stamped.recycle() }
                    } else stamped

                    val out = ByteArrayOutputStream()
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                    finalBmp.recycle()

                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_Edit_$stamp.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val cr = getApplication<Application>().contentResolver
                    val outUri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext false
                    cr.openOutputStream(outUri)?.use { it.write(out.toByteArray()) }
                    val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    cr.update(outUri, done, null, null)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            _uiState.update { it.copy(isExportingDarkroom = false) }
            if (success) refreshLatestThumbnail()
            showBanner(if (success) "Exported graded photo to Pictures/TrueLens" else "Export failed")
        }
    }

    private fun updateSettings(transform: (ManualSettings) -> ManualSettings) {
        val updated = transform(_uiState.value.settings)
        _uiState.update { it.copy(settings = updated) }
        cameraEngine.updateSettings(updated, _uiState.value.grade)
    }

    private fun showBanner(message: String) {
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            _uiState.update { it.copy(statusBanner = message) }
            delay(2200)
            _uiState.update { it.copy(statusBanner = null) }
        }
    }

    // --- POSE INSPOS LIBRARY & LIVE POSE OVERLAY ---

    private fun loadPoseFolders() {
        viewModelScope.launch {
            val folders = poseRepo.loadFolders()
            val hasToken = pinterestImporter.getSavedAccessToken() != null
            _uiState.update { state ->
                state.copy(
                    poseFolders = folders,
                    selectedPoseFolderId = state.selectedPoseFolderId ?: folders.firstOrNull()?.id,
                    isPinterestConnected = hasToken,
                    savedPinterestClientId = pinterestImporter.getSavedClientId()
                )
            }
            if (hasToken) {
                refreshPinterestBoards()
            }
        }
    }

    fun openPoseInspos() {
        loadPoseFolders()
        _uiState.update { it.copy(isPoseInsposOpen = true) }
    }

    fun closePoseInspos() {
        _uiState.update { it.copy(isPoseInsposOpen = false) }
    }

    fun selectPoseFolder(folderId: String) {
        _uiState.update { it.copy(selectedPoseFolderId = folderId) }
    }

    fun createPoseFolder(name: String) {
        viewModelScope.launch {
            val created = poseRepo.createFolder(name)
            val updated = poseRepo.loadFolders()
            _uiState.update {
                it.copy(poseFolders = updated, selectedPoseFolderId = created.id)
            }
        }
    }

    fun importGalleryUris(folderId: String, uris: List<Uri>, source: String = "Gallery") {
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingPoses = true, poseImportStatus = "Importing ${uris.size} photo(s)...") }
            var added = 0
            for (uri in uris) {
                if (poseRepo.importFromUri(folderId, uri, source) != null) added++
            }
            val updated = poseRepo.loadFolders()
            _uiState.update {
                it.copy(
                    poseFolders = updated,
                    isImportingPoses = false,
                    poseImportStatus = "Added $added pose(s) to folder"
                )
            }
            delay(2400)
            _uiState.update { it.copy(poseImportStatus = null) }
        }
    }

    fun handleSharedImages(uris: List<Uri>) {
        viewModelScope.launch {
            val folders = poseRepo.loadFolders()
            val targetId = _uiState.value.selectedPoseFolderId ?: folders.firstOrNull()?.id ?: return@launch
            _uiState.update { it.copy(isPoseInsposOpen = true, selectedPoseFolderId = targetId) }
            importGalleryUris(targetId, uris, source = "Share")
        }
    }

    fun handleSharedTextUrl(text: String) {
        _uiState.update { it.copy(isPoseInsposOpen = true) }
        importPublicPinterestUrl(text)
    }

    fun importPublicPinterestUrl(rawUrl: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImportingPoses = true, poseImportStatus = "Fetching Pinterest board/pin...")
            }
            val result = pinterestImporter.fetchPublicPinterestUrl(rawUrl)
            result.onSuccess { (boardName, pins) ->
                val folder = poseRepo.createFolder(
                    name = boardName,
                    sourceUrl = rawUrl,
                    isPinterestBoard = true
                )
                var count = 0
                for (pin in pins) {
                    _uiState.update {
                        it.copy(poseImportStatus = "Downloading ${count + 1}/${pins.size} pins from \"$boardName\"...")
                    }
                    val bytes = pinterestImporter.downloadImageBytes(pin.imageUrl)
                    if (bytes != null) {
                        poseRepo.importFromBytes(folder.id, bytes, pin.title, source = "Pinterest")
                        count++
                    }
                }
                val updated = poseRepo.loadFolders()
                _uiState.update {
                    it.copy(
                        poseFolders = updated,
                        selectedPoseFolderId = folder.id,
                        isImportingPoses = false,
                        poseImportStatus = "Imported $count poses from \"$boardName\""
                    )
                }
                delay(2800)
                _uiState.update { it.copy(poseImportStatus = null) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isImportingPoses = false,
                        poseImportStatus = err.message ?: "Pinterest import failed"
                    )
                }
                delay(3500)
                _uiState.update { it.copy(poseImportStatus = null) }
            }
        }
    }

    fun savePinterestToken(clientId: String, token: String) {
        pinterestImporter.saveCredentials(clientId, token)
        _uiState.update {
            it.copy(isPinterestConnected = true, savedPinterestClientId = clientId)
        }
        refreshPinterestBoards()
    }

    private fun refreshPinterestBoards() {
        viewModelScope.launch {
            pinterestImporter.fetchAuthenticatedBoards().onSuccess { boards ->
                _uiState.update { it.copy(pinterestBoards = boards) }
            }
        }
    }

    fun syncPinterestBoard(board: PinterestBoardSummary) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImportingPoses = true, poseImportStatus = "Syncing board \"${board.name}\"...")
            }
            pinterestImporter.fetchBoardPinsViaApi(board.id).onSuccess { pins ->
                val folder = poseRepo.createFolder(
                    name = board.name,
                    isPinterestBoard = true
                )
                var count = 0
                for (pin in pins) {
                    val bytes = pinterestImporter.downloadImageBytes(pin.imageUrl)
                    if (bytes != null) {
                        poseRepo.importFromBytes(folder.id, bytes, pin.title, source = "Pinterest")
                        count++
                    }
                }
                val updated = poseRepo.loadFolders()
                _uiState.update {
                    it.copy(
                        poseFolders = updated,
                        selectedPoseFolderId = folder.id,
                        isImportingPoses = false,
                        poseImportStatus = "Synced $count poses from \"${board.name}\""
                    )
                }
                delay(2800)
                _uiState.update { it.copy(poseImportStatus = null) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isImportingPoses = false, poseImportStatus = err.message ?: "Sync failed")
                }
            }
        }
    }

    fun deletePoseItem(folderId: String, itemId: String) {
        viewModelScope.launch {
            val updated = poseRepo.deleteItem(folderId, itemId)
            _uiState.update { state ->
                val clearedOverlay = if (state.poseOverlay.activeItem?.id == itemId) {
                    PoseOverlayState()
                } else {
                    state.poseOverlay
                }
                state.copy(poseFolders = updated, poseOverlay = clearedOverlay)
            }
        }
    }

    fun selectPoseForOverlay(item: PoseInspoItem) {
        viewModelScope.launch {
            val bmp = poseRepo.loadBitmap(item.filePath, maxDim = 1400) ?: return@launch
            _uiState.update { state ->
                state.copy(
                    isPoseInsposOpen = false,
                    poseOverlay = PoseOverlayState(
                        activeItem = item,
                        fullBitmap = bmp,
                        blendMode = PoseBlendMode.GHOST_PHOTO,
                        opacity = 0.48f,
                        scale = 1.0f,
                        offsetX = 0f,
                        offsetY = 0f,
                        rotationDegrees = 0f,
                        isMirrored = false,
                        isLocked = false,
                        isHidden = false
                    )
                )
            }
            showBanner("Pinch to zoom & drag pose • Tap LOCK when aligned")
        }
    }

    fun updatePoseTransform(panX: Float, panY: Float, zoomDelta: Float, rotationDelta: Float) {
        _uiState.update { state ->
            val p = state.poseOverlay
            if (!p.isActive || p.isLocked) return@update state
            state.copy(
                poseOverlay = p.copy(
                    scale = (p.scale * zoomDelta).coerceIn(0.3f, 4.0f),
                    offsetX = (p.offsetX + panX).coerceIn(-1200f, 1200f),
                    offsetY = (p.offsetY + panY).coerceIn(-1600f, 1600f),
                    rotationDegrees = p.rotationDegrees + rotationDelta
                )
            )
        }
    }

    fun setPoseOpacity(opacity: Float) {
        _uiState.update { it.copy(poseOverlay = it.poseOverlay.copy(opacity = opacity.coerceIn(0.05f, 1f))) }
    }

    fun setPoseBlendMode(mode: PoseBlendMode) {
        val current = _uiState.value.poseOverlay
        val full = current.fullBitmap ?: return
        _uiState.update { it.copy(poseOverlay = it.poseOverlay.copy(blendMode = mode)) }

        if (mode == PoseBlendMode.SUBJECT_CUTOUT && current.cutoutBitmap == null) {
            viewModelScope.launch {
                _uiState.update { it.copy(poseOverlay = it.poseOverlay.copy(isProcessingCutout = true)) }
                val cutout = PoseOverlayProcessor.extractSubjectCutout(full)
                _uiState.update { state ->
                    state.copy(
                        poseOverlay = state.poseOverlay.copy(
                            cutoutBitmap = cutout,
                            isProcessingCutout = false
                        )
                    )
                }
                if (cutout == null) {
                    showBanner("Subject Cutout model downloading via Play Services — showing Ghost")
                }
            }
        } else if (mode == PoseBlendMode.NEON_OUTLINE && current.outlineBitmap == null) {
            viewModelScope.launch {
                val outline = PoseOverlayProcessor.generateOutlineBitmap(full)
                _uiState.update { state ->
                    state.copy(poseOverlay = state.poseOverlay.copy(outlineBitmap = outline))
                }
            }
        }
    }

    fun togglePoseMirror() {
        _uiState.update { it.copy(poseOverlay = it.poseOverlay.copy(isMirrored = !it.poseOverlay.isMirrored)) }
    }

    fun togglePoseLock() {
        _uiState.update { it.copy(poseOverlay = it.poseOverlay.copy(isLocked = !it.poseOverlay.isLocked)) }
    }

    fun resetPoseTransform() {
        _uiState.update {
            it.copy(
                poseOverlay = it.poseOverlay.copy(
                    scale = 1.0f,
                    offsetX = 0f,
                    offsetY = 0f,
                    rotationDegrees = 0f,
                    isMirrored = false
                )
            )
        }
    }

    fun togglePoseHide() {
        _uiState.update { it.copy(poseOverlay = it.poseOverlay.copy(isHidden = !it.poseOverlay.isHidden)) }
    }

    fun clearPoseOverlay() {
        _uiState.update { it.copy(poseOverlay = PoseOverlayState()) }
    }

    private suspend fun loadScaledBitmap(uri: Uri, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        com.truelens.camera.camera.ExifBitmapLoader.loadScaled(getApplication(), uri, maxDim)
    }
}
