package com.truelens.camera.ui

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.truelens.camera.camera.AebMode
import com.truelens.camera.camera.AspectRatioMask
import com.truelens.camera.camera.AstroStyle
import com.truelens.camera.camera.CaptureFormat
import com.truelens.camera.camera.CapturedPhoto
import com.truelens.camera.camera.DocumentScanStyle
import com.truelens.camera.camera.DoubleExpBlendMode
import com.truelens.camera.camera.ExposureMode
import com.truelens.camera.camera.GridOverlay
import com.truelens.camera.camera.LensInfo
import com.truelens.camera.camera.LiveTelemetry
import com.truelens.camera.camera.ManualSettings
import com.truelens.camera.camera.ShootingMode
import com.truelens.camera.camera.SkyPreset
import com.truelens.camera.camera.SoloBurstCount
import com.truelens.camera.camera.StabilizerMode
import com.truelens.camera.camera.TimerDuration
import com.truelens.camera.camera.TrueLensCameraEngine
import com.truelens.camera.camera.TrueLightAnalysis
import com.truelens.camera.camera.TrueLightAutoMeter
import com.truelens.camera.camera.VideoAudioMode
import com.truelens.camera.camera.VideoQuality
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
    val initialGalleryVideoUri: Uri? = null,
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
    val isVideoPaused: Boolean = false,
    val videoElapsedSec: Int = 0,
    val videoFpsBadge: String = "1080p",
    val isScanningDocument: Boolean = false,
    // Automatic QR Scanner, Live Text, Night Mode, Live Photos, Portrait & Cinematic Rack Focus
    val detectedQr: com.truelens.camera.modes.DetectedQrCode? = null,
    val stickyQr: com.truelens.camera.modes.DetectedQrCode? = null,
    val detectedLiveText: com.truelens.camera.modes.DetectedLiveText? = null,
    val isLiveTextSheetOpen: Boolean = false,
    val isNightStacking: Boolean = false,
    val nightStackProgress: Float = 0f,
    val isDarkLowLightScene: Boolean = false,
    val isPortraitProcessing: Boolean = false,
    val rackFocusNormX: Float = 0.5f,
    val rackFocusNormY: Float = 0.45f,
    val darkroomLiveVideoFile: java.io.File? = null,
    val savedCanonSlots: Set<String> = emptySet(),
    val stopMotionState: StopMotionState = StopMotionState(),
    val longExposureState: LongExposureState = LongExposureState(),
    val isFocusStacking: Boolean = false,
    val activeLut: CubeLut? = null,
    val splitScreenPosition: Float = 0.5f,
    // Audio dB meter, Floating shutter, Double Exposure, Astrophotography, Action Pan, VHS bake
    val audioLevel01: Float = 0f,
    val audioPeakDb: Float = -60f,
    val floatingShutterX: Float = 0.80f,
    val floatingShutterY: Float = 0.62f,
    val doubleExpFirstShot: Bitmap? = null,
    val doubleExpWeight: Float = 0.5f,
    val isProcessingDoubleExp: Boolean = false,
    val isAstroCapturing: Boolean = false,
    val astroPhaseLabel: String = "",
    val astroProgress: Float = 0f,
    val astroKeptFrames: Int = 0,
    val astroDroppedFrames: Int = 0,
    val isPhoneSteady: Boolean = true,
    val isActionPanProcessing: Boolean = false,
    val isBakingVhs: Boolean = false,
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
    private val livePhotoController = com.truelens.camera.modes.LivePhotoController(application)
    private var pendingLivePhotoName: String? = null
    private var stickyQrClearJob: Job? = null
    private val viewfinderScanner = com.truelens.camera.modes.ViewfinderScannerController(
        onQrDetected = { qr ->
            if (qr != null) {
                stickyQrClearJob?.cancel()
                _uiState.update { it.copy(detectedQr = qr, stickyQr = qr) }
                stickyQrClearJob = viewModelScope.launch {
                    delay(3500)
                    _uiState.update { cur ->
                        if (cur.detectedQr == null) cur.copy(stickyQr = null) else cur
                    }
                }
            } else {
                _uiState.update { it.copy(detectedQr = null) }
            }
        },
        onTextDetected = { txt ->
            _uiState.update { cur ->
                if (cur.isLiveTextSheetOpen) cur else cur.copy(detectedLiveText = txt)
            }
        }
    )
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
    private var videoMeterJob: Job? = null
    private var astroJob: Job? = null
    private var bannerJob: Job? = null
    private var darkroomPreviewJob: Job? = null
    private var darkroomLoadJob: Job? = null
    private var openedDarkroomFromGallery: Boolean = false
    private val canonSlotPrefs = application.getSharedPreferences("truelens_canon_slots", Context.MODE_PRIVATE)
    private val appPrefs = application.getSharedPreferences("truelens_prefs", Context.MODE_PRIVATE)
    private val actionSound: MediaActionSound? = runCatching {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }.getOrNull()
    private val audioMeterController = com.truelens.camera.modes.AudioMeterController(viewModelScope) { norm, db ->
        _uiState.update { it.copy(audioLevel01 = norm, audioPeakDb = db) }
    }
    @Volatile private var latestGyroMag: Float = 0f
    @Volatile private var peakGyroDuringCapture: Float = 0f
    private var lastGyroUiUpdateMs: Long = 0L

    init {
        loadPersistedSettings()
        loadPoseFolders()
        refreshLatestThumbnail()
        viewModelScope.launch {
            val saved = customPresetRepo.loadCustomPresets()
            val slots = setOf("C1", "C2", "C3").filter { canonSlotPrefs.contains("${it}_mode") }.toSet()
            _uiState.update { it.copy(customPresets = saved, savedCanonSlots = slots) }
        }
    }

    private fun loadPersistedSettings() {
        val grid = runCatching {
            GridOverlay.valueOf(appPrefs.getString("gridOverlay", GridOverlay.RULE_OF_THIRDS.name)!!)
        }.getOrDefault(GridOverlay.RULE_OF_THIRDS)
        val capFmt = runCatching {
            CaptureFormat.valueOf(appPrefs.getString("captureFormat", CaptureFormat.CLEAN_JPG.name)!!)
        }.getOrDefault(CaptureFormat.CLEAN_JPG)
        val vq = runCatching {
            VideoQuality.valueOf(appPrefs.getString("videoQuality", VideoQuality.FHD_30.name)!!)
        }.getOrDefault(VideoQuality.FHD_30)
        val va = runCatching {
            VideoAudioMode.valueOf(appPrefs.getString("videoAudioMode", VideoAudioMode.STEREO.name)!!)
        }.getOrDefault(VideoAudioMode.STEREO)
        val stab = runCatching {
            StabilizerMode.valueOf(appPrefs.getString("stabilizerMode", StabilizerMode.SUPER_STEADY.name)!!)
        }.getOrDefault(StabilizerMode.SUPER_STEADY)
        val ds = runCatching {
            com.truelens.camera.grading.DateStampStyle.valueOf(
                appPrefs.getString("dateStampStyle", com.truelens.camera.grading.DateStampStyle.OFF.name)!!
            )
        }.getOrDefault(com.truelens.camera.grading.DateStampStyle.OFF)
        val deBlend = runCatching {
            DoubleExpBlendMode.valueOf(appPrefs.getString("doubleExpBlend", DoubleExpBlendMode.SCREEN.name)!!)
        }.getOrDefault(DoubleExpBlendMode.SCREEN)
        val astroStyle = runCatching {
            AstroStyle.valueOf(appPrefs.getString("astroStyle", AstroStyle.PINPOINT_STARS.name)!!)
        }.getOrDefault(AstroStyle.PINPOINT_STARS)

        val liveOn = appPrefs.getBoolean("livePhotoEnabled", false)
        livePhotoController.isEnabled = liveOn

        _uiState.update { state ->
            state.copy(
                floatingShutterX = appPrefs.getFloat("floatingShutterX", 0.80f).coerceIn(0.08f, 0.92f),
                floatingShutterY = appPrefs.getFloat("floatingShutterY", 0.62f).coerceIn(0.12f, 0.88f),
                settings = state.settings.copy(
                    captureFormat = capFmt,
                    gridOverlay = grid,
                    trueLightAuto = appPrefs.getBoolean("trueLightAuto", false),
                    autoNightPromptEnabled = appPrefs.getBoolean("autoNightPromptEnabled", true),
                    qrScannerEnabled = appPrefs.getBoolean("qrScannerEnabled", true),
                    liveTextEnabled = appPrefs.getBoolean("liveTextEnabled", true),
                    livePhotoEnabled = liveOn,
                    shutterSoundEnabled = appPrefs.getBoolean("shutterSoundEnabled", true),
                    audioMeterEnabled = appPrefs.getBoolean("audioMeterEnabled", false),
                    floatingShutterEnabled = appPrefs.getBoolean("floatingShutterEnabled", false),
                    keepMusicPlaying = appPrefs.getBoolean("keepMusicPlaying", true),
                    mirrorFrontSave = appPrefs.getBoolean("mirrorFrontSave", false),
                    videoQuality = vq,
                    videoAudioMode = va,
                    micMuted = va == VideoAudioMode.MUTED,
                    stabilizerMode = stab,
                    cinema180Shutter = appPrefs.getBoolean("cinema180Shutter", false),
                    dateStampStyle = ds,
                    astroDurationSec = appPrefs.getInt("astroDurationSec", 30).coerceIn(15, 60),
                    astroStyle = astroStyle,
                    astroLightPollutionClean = appPrefs.getBoolean("astroLightPollutionClean", true),
                    astroHotPixelClean = appPrefs.getBoolean("astroHotPixelClean", true),
                    doubleExpBlend = deBlend,
                    actionPanStrength = appPrefs.getFloat("actionPanStrength", 0.65f).coerceIn(0.2f, 1.0f)
                )
            )
        }
    }

    private fun savePersistedSettings(s: ManualSettings) {
        appPrefs.edit()
            .putString("captureFormat", s.captureFormat.name)
            .putString("gridOverlay", s.gridOverlay.name)
            .putBoolean("trueLightAuto", s.trueLightAuto)
            .putBoolean("autoNightPromptEnabled", s.autoNightPromptEnabled)
            .putBoolean("qrScannerEnabled", s.qrScannerEnabled)
            .putBoolean("liveTextEnabled", s.liveTextEnabled)
            .putBoolean("livePhotoEnabled", s.livePhotoEnabled)
            .putBoolean("shutterSoundEnabled", s.shutterSoundEnabled)
            .putBoolean("audioMeterEnabled", s.audioMeterEnabled)
            .putBoolean("floatingShutterEnabled", s.floatingShutterEnabled)
            .putBoolean("keepMusicPlaying", s.keepMusicPlaying)
            .putBoolean("mirrorFrontSave", s.mirrorFrontSave)
            .putString("videoQuality", s.videoQuality.name)
            .putString("videoAudioMode", s.videoAudioMode.name)
            .putString("stabilizerMode", s.stabilizerMode.name)
            .putBoolean("cinema180Shutter", s.cinema180Shutter)
            .putString("dateStampStyle", s.dateStampStyle.name)
            .putInt("astroDurationSec", s.astroDurationSec)
            .putString("astroStyle", s.astroStyle.name)
            .putBoolean("astroLightPollutionClean", s.astroLightPollutionClean)
            .putBoolean("astroHotPixelClean", s.astroHotPixelClean)
            .putString("doubleExpBlend", s.doubleExpBlend.name)
            .putFloat("actionPanStrength", s.actionPanStrength)
            .apply()
    }

    private fun playShutterSound() {
        if (_uiState.value.settings.shutterSoundEnabled) {
            runCatching { actionSound?.play(MediaActionSound.SHUTTER_CLICK) }
        }
    }

    private fun playVideoStartSound() {
        if (_uiState.value.settings.shutterSoundEnabled) {
            runCatching { actionSound?.play(MediaActionSound.START_VIDEO_RECORDING) }
        }
    }

    private fun playVideoStopSound() {
        if (_uiState.value.settings.shutterSoundEnabled) {
            runCatching { actionSound?.play(MediaActionSound.STOP_VIDEO_RECORDING) }
        }
    }

    fun syncAudioMeter() {
        val state = _uiState.value
        val isVideoMode = state.settings.shootingMode == ShootingMode.VIDEO ||
            state.settings.shootingMode == ShootingMode.SLOW_MO ||
            state.settings.shootingMode == ShootingMode.CINEMATIC
        val hasAudio = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (state.settings.audioMeterEnabled && isVideoMode && !state.isVideoRecording && !state.settings.micMuted) {
            audioMeterController.start(hasAudio)
        } else {
            audioMeterController.stop()
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
                if (photo.filePath != null) {
                    pendingLivePhotoName = photo.filePath
                }
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
            // Default is CLEAN (1 crisp JPEG per shot). Preserve user's RAW choice only if lens supports RAW!
            val resolvedFormat = if (defaultLens?.supportsRawSensor == true) {
                state.settings.captureFormat
            } else {
                CaptureFormat.CLEAN_JPG
            }
            state.copy(
                lenses = lenses,
                activeLens = defaultLens,
                settings = state.settings.copy(captureFormat = resolvedFormat),
                telemetry = state.telemetry.copy(
                    aperture = defaultLens?.aperture ?: 1.8f,
                    isRawActive = defaultLens?.supportsRawSensor == true && resolvedFormat != CaptureFormat.CLEAN_JPG
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
        syncAudioMeter()
        val surface = previewSurface ?: return
        if (!surface.isValid) return
        val lens = _uiState.value.activeLens ?: return
        openLens(lens, surface)
    }

    fun stopCamera() {
        dualCameraController.stopSecondary()
        handsFreeController.setVoiceEnabled(false, false)
        handsFreeController.setPalmEnabled(false)
        audioMeterController.stop()
        videoMeterJob?.cancel()
        cameraEngine.stop()
    }

    override fun onCleared() {
        super.onCleared()
        dualCameraController.release()
        handsFreeController.release()
        viewfinderScanner.release()
        livePhotoController.clearBuffer()
        audioMeterController.stop()
        videoMeterJob?.cancel()
        astroJob?.cancel()
        runCatching { actionSound?.release() }
        _uiState.value.doubleExpFirstShot?.recycle()
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
        if (_uiState.value.isAstroCapturing) cancelAstroCapture()
        if (mode != ShootingMode.DUAL) {
            dualCameraController.stopSecondary()
            _uiState.update { it.copy(isDualConcurrentLive = false) }
        }
        if (mode != ShootingMode.DOUBLE_EXP && _uiState.value.doubleExpFirstShot != null) {
            _uiState.value.doubleExpFirstShot?.recycle()
            _uiState.update { it.copy(doubleExpFirstShot = null) }
        }

        updateSettings { s ->
            when (mode) {
                ShootingMode.SUPER_MACRO -> s.copy(
                    shootingMode = mode,
                    focusPeaking = true,
                    astroRedUiEnabled = false
                )
                ShootingMode.ASTRO -> s.copy(
                    shootingMode = mode,
                    focusDistanceDiopters = 0f, // Auto-lock focus to Infinity (∞) for sharp stars!
                    focusPeaking = false
                )
                else -> s.copy(
                    shootingMode = mode,
                    focusDistanceDiopters = null,
                    focusPeaking = if (s.shootingMode == ShootingMode.SUPER_MACRO) false else s.focusPeaking,
                    astroRedUiEnabled = false,
                    astroStarLoupeEnabled = false
                )
            }
        }
        syncAudioMeter()
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
            ShootingMode.PORTRAIT -> {
                if (!state.isPortraitProcessing) {
                    capturePortraitBokehPhoto()
                }
            }
            ShootingMode.ASTRO -> {
                if (state.isAstroCapturing) {
                    cancelAstroCapture()
                } else {
                    captureAstroSkyStack()
                }
            }
            ShootingMode.DOUBLE_EXP -> {
                if (!state.isProcessingDoubleExp) {
                    captureDoubleExposureStep()
                }
            }
            ShootingMode.ACTION_PAN -> {
                if (!state.isActionPanProcessing) {
                    captureActionPanPhoto()
                }
            }
            ShootingMode.VIDEO, ShootingMode.SLOW_MO, ShootingMode.CINEMATIC -> {
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
                        if (state.settings.nightModeDuration.seconds > 0 && burstCount == 1) {
                            captureNightModeStack()
                        } else {
                            for (shot in 1..burstCount) {
                                capturePhoto()
                                if (shot < burstCount) {
                                    delay(650)
                                }
                            }
                        }
                    }
                } else if (state.settings.nightModeDuration.seconds > 0) {
                    captureNightModeStack()
                } else {
                    capturePhoto()
                }
            }
        }
    }

    private fun startVideoRecording(isSlowMo: Boolean) {
        audioMeterController.stop()
        val hasAudio = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        cameraEngine.startVideoRecording(
            isSlowMo = isSlowMo,
            settings = _uiState.value.settings,
            hasAudioPermission = hasAudio,
            onStarted = { fpsLabel ->
                playVideoStartSound()
                _uiState.update {
                    it.copy(isVideoRecording = true, isVideoPaused = false, videoElapsedSec = 0, videoFpsBadge = fpsLabel)
                }
                videoTimerJob?.cancel()
                videoTimerJob = viewModelScope.launch {
                    var s = 0
                    while (_uiState.value.isVideoRecording) {
                        delay(1000)
                        if (!_uiState.value.isVideoPaused) {
                            s++
                            _uiState.update { it.copy(videoElapsedSec = s) }
                        }
                    }
                }
                videoMeterJob?.cancel()
                videoMeterJob = viewModelScope.launch {
                    var smoothed = 0f
                    while (_uiState.value.isVideoRecording) {
                        val st = _uiState.value
                        if (st.settings.audioMeterEnabled && !st.isVideoPaused && !st.settings.micMuted) {
                            val amp = cameraEngine.getRecordingMaxAmplitude()
                            val norm = if (amp > 0) {
                                val db = (20.0 * kotlin.math.log10((amp / 32767.0).coerceAtLeast(1e-4))).toFloat().coerceIn(-60f, 0f)
                                val raw = ((db + 54f) / 54f).coerceIn(0f, 1f)
                                smoothed = smoothed * 0.4f + raw * 0.6f
                                _uiState.update { it.copy(audioLevel01 = smoothed, audioPeakDb = db) }
                                smoothed
                            } else {
                                smoothed *= 0.7f
                                _uiState.update { it.copy(audioLevel01 = smoothed, audioPeakDb = -60f) }
                                smoothed
                            }
                            @Suppress("UNUSED_EXPRESSION") norm
                        }
                        delay(75)
                    }
                }
            },
            onFailed = { msg ->
                syncAudioMeter()
                showBanner(msg)
            }
        )
    }

    fun togglePauseVideoRecording() {
        val state = _uiState.value
        if (!state.isVideoRecording) return
        if (state.isVideoPaused) {
            if (cameraEngine.resumeVideoRecording()) {
                _uiState.update { it.copy(isVideoPaused = false) }
                showBanner("Recording resumed")
            }
        } else {
            if (cameraEngine.pauseVideoRecording()) {
                _uiState.update { it.copy(isVideoPaused = true) }
                showBanner("Recording paused")
            }
        }
    }

    fun captureMidVideoSnapshot() {
        val state = _uiState.value
        if (!state.isVideoRecording) return
        playShutterSound()
        viewModelScope.launch {
            _uiState.update { it.copy(showShutterFlash = true) }
            cameraEngine.captureVideoSnapshot(state.grade, state.settings, state.activeLut)
            delay(110)
            _uiState.update { it.copy(showShutterFlash = false) }
        }
    }

    private fun stopVideoRecording() {
        val elapsed = _uiState.value.videoElapsedSec
        val vhsRetroActive = _uiState.value.grade.vhsRetro > 0.01f
        videoTimerJob?.cancel()
        videoTimerJob = null
        videoMeterJob?.cancel()
        videoMeterJob = null
        cameraEngine.stopVideoRecording { uri ->
            playVideoStopSound()
            _uiState.update {
                it.copy(
                    isVideoRecording = false,
                    isVideoPaused = false,
                    videoElapsedSec = 0,
                    audioLevel01 = 0f,
                    audioPeakDb = -60f
                )
            }
            syncAudioMeter()
            if (uri != null) {
                viewModelScope.launch {
                    delay(180)
                    if (vhsRetroActive) {
                        _uiState.update { it.copy(isBakingVhs = true) }
                        showBanner("Baking 90s VHS tape look...")
                        val vhsUri = com.truelens.camera.modes.VideoTrimExporter.bakeVhsLookToMp4(
                            context = getApplication(),
                            sourceUri = uri
                        )
                        _uiState.update { it.copy(isBakingVhs = false) }
                        val finalUri = vhsUri ?: uri
                        val thumb = loadMediaThumbnail(getApplication(), finalUri, isVideo = true, maxDim = 240)
                        if (thumb != null) {
                            _uiState.update { it.copy(lastThumbnail = thumb, lastThumbnailIsVideo = true) }
                        } else {
                            refreshLatestThumbnail()
                        }
                        showBanner(if (vhsUri != null) "Saved VHS '94 MP4 to Movies/TrueLens" else "Saved MP4 to Movies/TrueLens")
                    } else {
                        val thumb = loadMediaThumbnail(getApplication(), uri, isVideo = true, maxDim = 240)
                        if (thumb != null) {
                            _uiState.update { it.copy(lastThumbnail = thumb, lastThumbnailIsVideo = true) }
                        } else {
                            refreshLatestThumbnail()
                        }
                        showBanner("Saved MP4 to Movies/TrueLens")
                    }
                }
            } else {
                showBanner(if (elapsed < 1) "Record at least 1 second to save video" else "Video save failed")
            }
        }
    }

    private fun scanDocumentNow() {
        if (_uiState.value.isScanningDocument) return
        val style = _uiState.value.settings.scanStyle
        playShutterSound()
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

    fun onGyroAngularSpeed(radPerSec: Float) {
        latestGyroMag = latestGyroMag * 0.45f + radPerSec * 0.55f
        cameraEngine.updateGyroSpeed(latestGyroMag)
        if (radPerSec > peakGyroDuringCapture) {
            peakGyroDuringCapture = radPerSec
        }
        val now = System.currentTimeMillis()
        if (now - lastGyroUiUpdateMs >= 140L) {
            lastGyroUiUpdateMs = now
            val steady = latestGyroMag < 0.08f
            if (_uiState.value.isPhoneSteady != steady) {
                _uiState.update { it.copy(isPhoneSteady = steady) }
            }
        }
    }

    private fun startLongExposure() {
        val le = _uiState.value.longExposureState
        val totalFrames = (le.durationSec * 3).coerceIn(6, 36)
        viewModelScope.launch {
            _uiState.update {
                it.copy(longExposureState = le.copy(isCapturing = true, progress = 0f, accumulatedFrames = 0))
            }
            // Anti-shake tap-settle gate: wait briefly if user's tap vibrated the tripod/hands
            if (latestGyroMag > 0.07f) {
                showBanner("Settling tripod vibration...")
                var waited = 0
                while (latestGyroMag > 0.06f && waited < 1500) {
                    delay(100)
                    waited += 100
                }
            }
            val collected = mutableListOf<Bitmap>()
            var droppedShaken = 0
            val delayPerFrameMs = (le.durationSec * 1000L) / totalFrames
            for (i in 1..totalFrames) {
                peakGyroDuringCapture = 0f
                val frame = kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont ->
                    cameraEngine.capturePanoramaFrame(_uiState.value.settings) { bmp ->
                        if (cont.isActive) cont.resume(bmp) {}
                    }
                }
                if (frame != null) {
                    // Reject sharp wind gusts / tripod bumps cleanly if we already have base frames
                    if (peakGyroDuringCapture > 0.30f && collected.isNotEmpty()) {
                        frame.recycle()
                        droppedShaken++
                    } else {
                        collected.add(frame)
                    }
                }
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
                    lastThumbnail = thumb ?: it.lastThumbnail,
                    lastThumbnailIsVideo = if (thumb != null) false else it.lastThumbnailIsVideo
                )
            }
            val shakeNote = if (droppedShaken > 0) " • $droppedShaken shaken skipped" else ""
            showBanner(if (uri != null) "Long Exposure (${le.durationSec}s ${le.style.label}$shakeNote) saved" else "Long Exposure failed")
        }
    }

    // --- ASTROPHOTOGRAPHY / SKY MODE (15s-60s Optical Stacking + Tripod Anti-Shake + Sky Presets) ---

    fun setAstroDuration(seconds: Int) {
        updateSettings { it.copy(astroDurationSec = seconds.coerceIn(15, 60)) }
    }

    fun toggleAstroStyle() {
        updateSettings { s ->
            val next = if (s.astroStyle == AstroStyle.PINPOINT_STARS) AstroStyle.STAR_TRAILS else AstroStyle.PINPOINT_STARS
            s.copy(astroStyle = next)
        }
        showBanner("Astro: ${_uiState.value.settings.astroStyle.label}")
    }

    fun toggleAstroRedUi() {
        updateSettings { it.copy(astroRedUiEnabled = !it.astroRedUiEnabled) }
        showBanner(if (_uiState.value.settings.astroRedUiEnabled) "Night-Vision Red UI ON" else "Standard UI")
    }

    fun toggleAstroStarLoupe() {
        updateSettings { it.copy(astroStarLoupeEnabled = !it.astroStarLoupeEnabled) }
        showBanner(if (_uiState.value.settings.astroStarLoupeEnabled) "4× Star Focus Magnifier ON" else "Star Magnifier OFF")
    }

    fun toggleAstroLightPollution() {
        updateSettings { it.copy(astroLightPollutionClean = !it.astroLightPollutionClean) }
        showBanner(if (_uiState.value.settings.astroLightPollutionClean) "Light-Pollution Gradient Cleaner ON" else "Raw Sky Gradient")
    }

    fun toggleAstroHotPixels() {
        updateSettings { it.copy(astroHotPixelClean = !it.astroHotPixelClean) }
        showBanner(if (_uiState.value.settings.astroHotPixelClean) "Hot-Pixel Removal ON" else "Hot-Pixel Removal OFF")
    }

    fun lockInfinityFocus() {
        updateSettings { it.copy(focusDistanceDiopters = 0f) }
        showBanner("Focus locked to Infinity (∞) for crisp stars")
    }

    fun applySkyPreset(preset: SkyPreset) {
        when (preset) {
            SkyPreset.MILKY_WAY -> {
                updateSettings {
                    it.copy(
                        astroDurationSec = 30,
                        astroStyle = AstroStyle.PINPOINT_STARS,
                        astroLightPollutionClean = true,
                        astroHotPixelClean = true,
                        astroStarLoupeEnabled = false,
                        focusDistanceDiopters = 0f,
                        exposureMode = ExposureMode.AUTO_P,
                        evCompensationSteps = 2
                    )
                }
                showBanner("Milky Way: 30s Pinpoint • ∞ Focus • LP Clean ON")
            }
            SkyPreset.STAR_TRAILS -> {
                updateSettings {
                    it.copy(
                        astroDurationSec = 60,
                        astroStyle = AstroStyle.STAR_TRAILS,
                        astroLightPollutionClean = false,
                        astroHotPixelClean = true,
                        astroStarLoupeEnabled = false,
                        focusDistanceDiopters = 0f,
                        exposureMode = ExposureMode.AUTO_P,
                        evCompensationSteps = 1
                    )
                }
                showBanner("Star Trails: 60s Lighten Stack • ∞ Focus")
            }
            SkyPreset.FULL_MOON -> {
                updateSettings {
                    it.copy(
                        astroDurationSec = 15,
                        astroStyle = AstroStyle.PINPOINT_STARS,
                        astroLightPollutionClean = false,
                        astroHotPixelClean = true,
                        astroStarLoupeEnabled = true,
                        focusDistanceDiopters = 0f,
                        exposureMode = ExposureMode.AUTO_P,
                        evCompensationSteps = -4
                    )
                }
                showBanner("Full Moon: EV -4 • ∞ Focus • 4× Moon Loupe ON")
            }
            SkyPreset.SUNSET_SKY -> {
                updateSettings {
                    it.copy(
                        astroDurationSec = 15,
                        astroStyle = AstroStyle.PINPOINT_STARS,
                        astroLightPollutionClean = false,
                        astroHotPixelClean = false,
                        astroStarLoupeEnabled = false,
                        focusDistanceDiopters = 0f,
                        exposureMode = ExposureMode.AUTO_P,
                        evCompensationSteps = -3
                    )
                }
                showBanner("Sunset Sky: EV -3 • Protects rich clouds & glow")
            }
        }
    }

    private fun cancelAstroCapture() {
        astroJob?.cancel()
        astroJob = null
        _uiState.update {
            it.copy(isAstroCapturing = false, astroPhaseLabel = "", astroProgress = 0f)
        }
        showBanner("Astro capture stopped")
    }

    private fun captureAstroSkyStack() {
        if (_uiState.value.isAstroCapturing) return
        val s = _uiState.value.settings
        val durSec = s.astroDurationSec.coerceIn(15, 60)
        // Bounded frame count (10..18) evenly paced over durSec so RAM stays light while stacking clean optical light
        val totalFrames = when {
            durSec <= 15 -> 10
            durSec <= 30 -> 14
            else -> 18
        }
        val collected = mutableListOf<Bitmap>()
        astroJob?.cancel()
        astroJob = viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isAstroCapturing = true,
                        astroPhaseLabel = "Settling tripod vibration...",
                        astroProgress = 0f,
                        astroKeptFrames = 0,
                        astroDroppedFrames = 0
                    )
                }
                // Phase 1: Tap-Settle Stability Gate (wait up to 2.5s for finger tap vibration to die out)
                var waitedMs = 0
                delay(450)
                while (latestGyroMag > 0.06f && waitedMs < 2500) {
                    delay(120)
                    waitedMs += 120
                }

                // Phase 2: Timed optical star-sky capture with live wind-shake rejection
                var dropped = 0
                val intervalMs = ((durSec * 1000L) / totalFrames).coerceAtLeast(350L)
                for (i in 1..totalFrames) {
                    _uiState.update {
                        it.copy(
                            astroPhaseLabel = "Capturing ${s.astroStyle.label} ($i/$totalFrames)...",
                            astroProgress = (i - 1).toFloat() / totalFrames.toFloat()
                        )
                    }
                    peakGyroDuringCapture = 0f
                    val frame = captureSingleMemoryBitmap()
                    if (frame != null) {
                        if (peakGyroDuringCapture > 0.16f && collected.isNotEmpty()) {
                            frame.recycle()
                            dropped++
                        } else {
                            collected.add(frame)
                        }
                    }
                    _uiState.update {
                        it.copy(
                            astroProgress = i.toFloat() / totalFrames.toFloat(),
                            astroKeptFrames = collected.size,
                            astroDroppedFrames = dropped
                        )
                    }
                    if (i < totalFrames) {
                        delay(intervalMs)
                    }
                }

                // Phase 3: Align, clean hot pixels & light pollution, save!
                _uiState.update { it.copy(astroPhaseLabel = "Aligning stars & cleaning sky...") }
                val cur = _uiState.value
                val uri = com.truelens.camera.modes.AstroSkyEngine.stackAstroAndSave(
                    context = getApplication(),
                    frames = collected,
                    style = cur.settings.astroStyle,
                    cleanLightPollution = cur.settings.astroLightPollutionClean,
                    cleanHotPixels = cur.settings.astroHotPixelClean,
                    grade = cur.grade,
                    lut = cur.activeLut,
                    dateStampStyle = cur.settings.dateStampStyle
                )
                val thumb = uri?.let { loadScaledBitmap(it, 240) }
                _uiState.update {
                    it.copy(
                        isAstroCapturing = false,
                        astroPhaseLabel = "",
                        astroProgress = 0f,
                        lastThumbnail = thumb ?: it.lastThumbnail,
                        lastThumbnailIsVideo = if (thumb != null) false else it.lastThumbnailIsVideo
                    )
                }
                val dropTxt = if (dropped > 0) " • $dropped shaken skipped" else ""
                showBanner(if (uri != null) "Astro ${cur.settings.astroStyle.label} (${collected.size}f$dropTxt) saved" else "Astro capture failed")
            } finally {
                collected.forEach { if (!it.isRecycled) it.recycle() }
                _uiState.update { it.copy(isAstroCapturing = false, astroPhaseLabel = "", astroProgress = 0f) }
            }
        }
    }

    // --- DOUBLE EXPOSURE MODE ---

    fun setDoubleExpBlend(mode: DoubleExpBlendMode) {
        updateSettings { it.copy(doubleExpBlend = mode) }
    }

    fun setDoubleExpWeight(weight: Float) {
        _uiState.update { it.copy(doubleExpWeight = weight.coerceIn(0.15f, 0.85f)) }
    }

    fun retakeDoubleExposureFirst() {
        _uiState.value.doubleExpFirstShot?.recycle()
        _uiState.update { it.copy(doubleExpFirstShot = null) }
        showBanner("Cleared Shot 1 — frame your first exposure")
    }

    private fun captureDoubleExposureStep() {
        val state = _uiState.value
        playShutterSound()
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingDoubleExp = true, showShutterFlash = true) }
            delay(100)
            _uiState.update { it.copy(showShutterFlash = false) }
            val captured = captureSingleMemoryBitmap()
            if (captured == null) {
                _uiState.update { it.copy(isProcessingDoubleExp = false) }
                showBanner("Capture unavailable")
                return@launch
            }
            val first = _uiState.value.doubleExpFirstShot
            if (first == null) {
                _uiState.update {
                    it.copy(doubleExpFirstShot = captured, isProcessingDoubleExp = false)
                }
                showBanner("Shot 1 locked • Frame Shot 2 & tap shutter to blend")
            } else {
                val cur = _uiState.value
                val uri = com.truelens.camera.modes.DoubleExposureEngine.blendAndSave(
                    context = getApplication(),
                    shot1 = first,
                    shot2 = captured,
                    blendMode = cur.settings.doubleExpBlend,
                    weightSecond = cur.doubleExpWeight,
                    grade = cur.grade,
                    lut = cur.activeLut,
                    dateStampStyle = cur.settings.dateStampStyle
                )
                first.recycle()
                captured.recycle()
                val thumb = uri?.let { loadScaledBitmap(it, 240) }
                _uiState.update {
                    it.copy(
                        doubleExpFirstShot = null,
                        isProcessingDoubleExp = false,
                        lastThumbnail = thumb ?: it.lastThumbnail,
                        lastThumbnailIsVideo = if (thumb != null) false else it.lastThumbnailIsVideo
                    )
                }
                showBanner(if (uri != null) "Double Exposure (${cur.settings.doubleExpBlend.label}) saved" else "Double Exposure failed")
            }
        }
    }

    // --- QUICK MOTION / ACTION PAN MODE ---

    fun setActionPanStrength(strength: Float) {
        updateSettings { it.copy(actionPanStrength = strength.coerceIn(0.2f, 1.0f)) }
    }

    private fun captureActionPanPhoto() {
        val state = _uiState.value
        playShutterSound()
        viewModelScope.launch {
            _uiState.update { it.copy(isActionPanProcessing = true, showShutterFlash = true) }
            delay(100)
            _uiState.update { it.copy(showShutterFlash = false) }
            val frame = captureSingleMemoryBitmap()
            if (frame != null) {
                val uri = com.truelens.camera.modes.ActionPanEngine.renderAndSaveActionPan(
                    context = getApplication(),
                    source = frame,
                    streakStrength = state.settings.actionPanStrength,
                    grade = state.grade,
                    lut = state.activeLut,
                    dateStampStyle = state.settings.dateStampStyle
                )
                frame.recycle()
                val thumb = uri?.let { loadScaledBitmap(it, 240) }
                _uiState.update {
                    it.copy(
                        isActionPanProcessing = false,
                        lastThumbnail = thumb ?: it.lastThumbnail,
                        lastThumbnailIsVideo = if (thumb != null) false else it.lastThumbnailIsVideo
                    )
                }
                showBanner(if (uri != null) "Action Pan motion photo saved" else "Action Pan failed")
            } else {
                _uiState.update { it.copy(isActionPanProcessing = false) }
                showBanner("Action Pan unavailable")
            }
        }
    }

    // --- NEW TOGGLES (Shutter Sound, Audio dB Meter, Auto Night Prompt, Floating Shutter, Quick VHS) ---

    fun toggleShutterSound() {
        val next = !_uiState.value.settings.shutterSoundEnabled
        updateSettings { it.copy(shutterSoundEnabled = next) }
        showBanner(if (next) "Shutter Sound ON" else "Silent Shutter ON")
    }

    fun toggleAudioMeter() {
        val next = !_uiState.value.settings.audioMeterEnabled
        updateSettings { it.copy(audioMeterEnabled = next) }
        syncAudioMeter()
        showBanner(if (next) "Live Audio dB Meter ON" else "Audio dB Meter OFF")
    }

    fun toggleAutoNightPrompt() {
        val next = !_uiState.value.settings.autoNightPromptEnabled
        updateSettings { it.copy(autoNightPromptEnabled = next) }
        showBanner(if (next) "Auto Night Indicator ON" else "Auto Night Indicator OFF")
    }

    fun toggleFloatingShutter() {
        val next = !_uiState.value.settings.floatingShutterEnabled
        updateSettings { it.copy(floatingShutterEnabled = next) }
        showBanner(if (next) "Floating Shutter ON (Hold & drag to move)" else "Floating Shutter OFF")
    }

    fun updateFloatingShutterPos(normX: Float, normY: Float) {
        val cx = normX.coerceIn(0.08f, 0.92f)
        val cy = normY.coerceIn(0.12f, 0.88f)
        _uiState.update { it.copy(floatingShutterX = cx, floatingShutterY = cy) }
        appPrefs.edit().putFloat("floatingShutterX", cx).putFloat("floatingShutterY", cy).apply()
    }

    fun toggleVhsRetroQuick() {
        val cur = _uiState.value.grade.vhsRetro
        if (cur > 0.01f) {
            _uiState.update {
                it.copy(grade = it.grade.copy(vhsRetro = 0f), activePresetId = "true_zero")
            }
            showBanner("VHS '94 Look OFF")
        } else {
            val vhsPreset = BuiltInPresets.ALL.firstOrNull { it.id == "vhs_94" }
            if (vhsPreset != null) {
                applyPreset(vhsPreset)
            } else {
                updateGrade { it.copy(vhsRetro = 0.85f) }
            }
            showBanner("VHS '94 Camcorder Look ON (bakes into recorded video)")
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

    // --- FULL IN-APP GALLERY & DIRECT LATEST MEDIA VIEWER ---
    fun openGallery() {
        _uiState.update { it.copy(isGalleryOpen = true, initialGalleryVideoUri = null) }
    }

    fun closeGallery() {
        _uiState.update { it.copy(isGalleryOpen = false, initialGalleryVideoUri = null) }
    }

    fun clearInitialGalleryVideo() {
        _uiState.update { it.copy(initialGalleryVideoUri = null) }
    }

    /**
     * Tapping the camera's bottom-left thumbnail opens the exact latest photo (or video)
     * directly first — pressing Back from it then goes to the Gallery grid!
     */
    fun openLatestMedia() {
        viewModelScope.launch {
            val latest = queryTrueLensGallery(getApplication(), includeVideos = true).firstOrNull()
            when {
                latest == null -> _uiState.update { it.copy(isGalleryOpen = true, initialGalleryVideoUri = null) }
                latest.isVideo -> _uiState.update { it.copy(isGalleryOpen = true, initialGalleryVideoUri = latest.uri) }
                else -> openDarkroomForUri(latest.uri)
            }
        }
    }

    fun openDarkroomForUri(uri: Uri) {
        if (_uiState.value.isGalleryOpen) {
            openedDarkroomFromGallery = true
        }
        darkroomUndoStack.clear()
        darkroomRedoStack.clear()
        darkroomPreviewJob?.cancel()
        darkroomLoadJob?.cancel()
        darkroomLoadJob = viewModelScope.launch {
            val bmp = loadScaledBitmap(uri, 1280) ?: return@launch
            val exif = withContext(Dispatchers.IO) {
                com.truelens.camera.grading.PhotoFrameRenderer.readExifSummary(getApplication(), uri)
            }
            val liveFile = withContext(Dispatchers.IO) {
                val displayName = resolveDisplayNameForUri(uri)
                if (displayName != null) {
                    val f = com.truelens.camera.modes.LivePhotoController.sidecarFileForName(getApplication(), displayName)
                    if (f.exists() && f.length() > 1024L) f else null
                } else null
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
                    darkroomLiveVideoFile = liveFile,
                    canUndoDarkroom = false,
                    canRedoDarkroom = false
                )
            }
        }
    }

    private fun resolveDisplayNameForUri(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveDarkroomKeyPhoto(frame: Bitmap) {
        viewModelScope.launch {
            val savedUri = com.truelens.camera.modes.LivePhotoController.saveKeyPhotoFrame(getApplication(), frame)
            if (savedUri != null) {
                refreshLatestThumbnail()
                showBanner("Saved Key Photo to Pictures/TrueLens")
                openDarkroomForUri(savedUri)
            } else {
                showBanner("Could not save Key Photo")
            }
        }
    }

    fun exportDarkroomLiveVideo() {
        val sidecar = _uiState.value.darkroomLiveVideoFile ?: return
        viewModelScope.launch {
            val savedUri = com.truelens.camera.modes.LivePhotoController.exportSidecarToMovies(getApplication(), sidecar)
            if (savedUri != null) {
                refreshLatestThumbnail()
                showBanner("Saved Live Photo video to Movies/TrueLens")
            } else {
                showBanner("Could not export Live Photo video")
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
        _uiState.update { it.copy(rackFocusNormX = normX.coerceIn(0.05f, 0.95f), rackFocusNormY = normY.coerceIn(0.05f, 0.95f)) }
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
        val isDark = latestAmbientLux < 18f || analysis.scene == com.truelens.camera.camera.LightingScene.LOW_LIGHT
        _uiState.update { it.copy(lightAnalysis = analysis, isDarkLowLightScene = isDark) }

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

    /** Exact zoom setter for the Smooth Zoom Wheel / Dial (e.g., 1.3x, 2.4x, 5.2x). */
    fun setExactZoom(targetZoom: Float) {
        val state = _uiState.value
        val rearLenses = state.lenses.filter { !it.isFront }
        val active = state.activeLens ?: return
        if (active.isFront) {
            val z = targetZoom.coerceIn(active.minZoomRatio, active.maxZoomRatio)
            _uiState.update { it.copy(zoomRatio = z) }
            cameraEngine.setZoom(z)
            return
        }
        val mainLens = rearLenses.firstOrNull { it.displayLabel == "1x" } ?: rearLenses.firstOrNull() ?: active
        val ultraLens = rearLenses.firstOrNull { it.displayLabel.startsWith("0.") }
        if (targetZoom < 0.92f && ultraLens != null) {
            if (active.cameraId != ultraLens.cameraId) selectLens(ultraLens)
            val z = (targetZoom / 0.6f).coerceIn(ultraLens.minZoomRatio, ultraLens.maxZoomRatio)
            _uiState.update { it.copy(zoomRatio = z) }
            cameraEngine.setZoom(z)
        } else {
            if (active.cameraId != mainLens.cameraId) selectLens(mainLens)
            val z = targetZoom.coerceIn(mainLens.minZoomRatio, mainLens.maxZoomRatio)
            _uiState.update { it.copy(zoomRatio = z) }
            cameraEngine.setZoom(z)
        }
    }

    /** Swipe Left (-1) or Right (+1) across viewfinder/bottom bar to smoothly switch primary modes. */
    fun swipePrimaryMode(delta: Int) {
        val modes = listOf(ShootingMode.PHOTO, ShootingMode.VIDEO, ShootingMode.PORTRAIT, ShootingMode.DUAL)
        val cur = _uiState.value.settings.shootingMode
        val idx = modes.indexOf(cur).let { if (it < 0) 0 else it }
        val nextIdx = (idx + delta).coerceIn(0, modes.lastIndex)
        if (nextIdx != idx || cur !in modes) {
            setShootingMode(modes[nextIdx])
        }
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
        cycleVideoAudioMode()
    }

    fun cycleVideoAudioMode() {
        val entries = com.truelens.camera.camera.VideoAudioMode.entries
        updateSettings { s ->
            val next = entries[(entries.indexOf(s.videoAudioMode) + 1) % entries.size]
            s.copy(videoAudioMode = next, micMuted = next == com.truelens.camera.camera.VideoAudioMode.MUTED)
        }
        syncAudioMeter()
        val m = _uiState.value.settings.videoAudioMode
        showBanner(
            when (m) {
                com.truelens.camera.camera.VideoAudioMode.STEREO -> "Audio: Stereo Mic (48kHz)"
                com.truelens.camera.camera.VideoAudioMode.WIND_CUT -> "Audio: Wind & Noise Cut"
                com.truelens.camera.camera.VideoAudioMode.MUTED -> "Audio: Muted"
            }
        )
    }

    fun toggleVideoWbLock() {
        updateSettings { it.copy(videoWbLocked = !it.videoWbLocked) }
        showBanner(if (_uiState.value.settings.videoWbLocked) "Video WB Locked (No Color Shift)" else "Video WB Auto")
    }

    fun toggleKeepMusicPlaying() {
        updateSettings { it.copy(keepMusicPlaying = !it.keepMusicPlaying) }
        showBanner(
            if (_uiState.value.settings.keepMusicPlaying) "Music Stays Playing While Filming"
            else "Pause Music While Filming"
        )
    }

    fun toggleEnhanceMode() {
        updateSettings { s ->
            val next = if (s.enhanceMode == com.truelens.camera.camera.EnhanceMode.OFF) {
                com.truelens.camera.camera.EnhanceMode.CLEAN
            } else {
                com.truelens.camera.camera.EnhanceMode.OFF
            }
            s.copy(enhanceMode = next)
        }
        val on = _uiState.value.settings.enhanceMode == com.truelens.camera.camera.EnhanceMode.CLEAN
        showBanner(if (on) "Clean Look ON (4-Frame Sub-Pixel Burst & Wavelet Denoise)" else "Pure Zero-AI Optics (Default)")
    }

    fun toggleOpticalHdr() {
        val next = !_uiState.value.settings.opticalHdrEnabled
        updateSettings { it.copy(opticalHdrEnabled = next) }
        showBanner(
            if (next) "Optical HDR ON (3-Frame Mertens Exposure Fusion + AgX)"
            else "Optical HDR OFF"
        )
    }

    fun toggleUltraHdr() {
        val next = !_uiState.value.settings.ultraHdrEnabled
        updateSettings { it.copy(ultraHdrEnabled = next) }
        showBanner(
            if (next) "Ultra HDR Display Headroom ON (ISO 21496-1 Gainmap)"
            else "Ultra HDR OFF (Standard SDR JPEG)"
        )
    }

    fun toggleQrScanner() {
        val next = !_uiState.value.settings.qrScannerEnabled
        updateSettings { it.copy(qrScannerEnabled = next) }
        if (!next) _uiState.update { it.copy(detectedQr = null, stickyQr = null) }
        showBanner(if (next) "Auto QR Scanner ON" else "Auto QR Scanner OFF")
    }

    fun toggleLiveText() {
        val next = !_uiState.value.settings.liveTextEnabled
        updateSettings { it.copy(liveTextEnabled = next) }
        if (!next) _uiState.update { it.copy(detectedLiveText = null, isLiveTextSheetOpen = false) }
        showBanner(if (next) "Live Text ON" else "Live Text OFF")
    }

    fun toggleLivePhoto() {
        val next = !_uiState.value.settings.livePhotoEnabled
        livePhotoController.isEnabled = next
        if (!next) livePhotoController.clearBuffer()
        updateSettings { it.copy(livePhotoEnabled = next) }
        showBanner(if (next) "Live Photos ON (1.5s Before & After)" else "Live Photos OFF")
    }

    fun cycleNightModeDuration() {
        val entries = com.truelens.camera.camera.NightModeDuration.entries
        updateSettings { s ->
            val next = entries[(entries.indexOf(s.nightModeDuration) + 1) % entries.size]
            s.copy(nightModeDuration = next)
        }
        val d = _uiState.value.settings.nightModeDuration
        showBanner(if (d.seconds == 0) "Night Stack OFF" else "Optical Night Stack: ${d.label} (Hold Steady)")
    }

    fun setPortraitAperture(fStop: Float) {
        updateSettings { it.copy(portraitApertureF = fStop.coerceIn(1.4f, 16.0f)) }
    }

    fun dismissQr() {
        stickyQrClearJob?.cancel()
        _uiState.update { it.copy(detectedQr = null, stickyQr = null) }
    }

    fun openLiveTextSheet() {
        if (_uiState.value.detectedLiveText != null) {
            _uiState.update { it.copy(isLiveTextSheetOpen = true) }
        }
    }

    fun closeLiveTextSheet() {
        _uiState.update { it.copy(isLiveTextSheetOpen = false) }
    }

    fun onScannerPreviewSample(bitmap: Bitmap) {
        val state = _uiState.value
        val allowScan = state.settings.shootingMode == ShootingMode.PHOTO &&
            !state.isVideoRecording &&
            !state.isGalleryOpen &&
            !state.isDarkroomOpen &&
            !state.isPoseInsposOpen &&
            !state.isLiveTextSheetOpen
        if (!allowScan) {
            bitmap.recycle()
            return
        }
        viewfinderScanner.analyzeFrame(
            bitmap = bitmap,
            scanQr = state.settings.qrScannerEnabled,
            scanText = state.settings.liveTextEnabled
        )
    }

    fun onLivePhotoPreviewSample(bitmap: Bitmap) {
        val state = _uiState.value
        val active = state.settings.livePhotoEnabled &&
            (state.settings.shootingMode == ShootingMode.PHOTO || state.settings.shootingMode == ShootingMode.PORTRAIT) &&
            !state.isGalleryOpen &&
            !state.isDarkroomOpen &&
            !state.isPoseInsposOpen
        livePhotoController.isEnabled = active
        if (!active) {
            bitmap.recycle()
            return
        }
        livePhotoController.pushPreviewFrame(bitmap)
    }

    private fun captureNightModeStack() {
        if (_uiState.value.isNightStacking) return
        val durSec = _uiState.value.settings.nightModeDuration.seconds.coerceIn(1, 3)
        val totalFrames = (durSec * 3).coerceIn(4, 9)
        viewModelScope.launch {
            _uiState.update { it.copy(isNightStacking = true, nightStackProgress = 0f) }
            showBanner("Optical Night Stack (${durSec}s) — hold steady...")
            val collected = mutableListOf<Bitmap>()
            val delayPerFrameMs = (durSec * 1000L) / totalFrames
            for (i in 1..totalFrames) {
                val frame = captureSingleMemoryBitmap()
                if (frame != null) collected.add(frame)
                _uiState.update { it.copy(nightStackProgress = i.toFloat() / totalFrames.toFloat()) }
                if (i < totalFrames) delay(delayPerFrameMs.coerceAtLeast(140L))
            }
            val s = _uiState.value
            val uri = com.truelens.camera.modes.NightModeEngine.stackAndSave(
                context = getApplication(),
                frames = collected,
                grade = s.grade,
                lut = s.activeLut,
                dateStampStyle = s.settings.dateStampStyle
            )
            collected.forEach { it.recycle() }
            val thumb = uri?.let { loadScaledBitmap(it, 240) }
            _uiState.update {
                it.copy(
                    isNightStacking = false,
                    nightStackProgress = 0f,
                    lastThumbnail = thumb ?: it.lastThumbnail,
                    lastThumbnailIsVideo = if (thumb != null) false else it.lastThumbnailIsVideo
                )
            }
            showBanner(if (uri != null) "Night Stack (${durSec}s Pure Optical) saved" else "Night Stack failed")
        }
    }

    private fun capturePortraitBokehPhoto() {
        if (_uiState.value.isPortraitProcessing) return
        val state = _uiState.value
        playShutterSound()
        viewModelScope.launch {
            _uiState.update { it.copy(isPortraitProcessing = true, showShutterFlash = true) }
            delay(100)
            _uiState.update { it.copy(showShutterFlash = false) }
            val rawFrame = captureSingleMemoryBitmap()
            if (rawFrame != null) {
                val uri = com.truelens.camera.modes.PortraitBokehEngine.renderAndSavePortrait(
                    context = getApplication(),
                    source = rawFrame,
                    apertureF = state.settings.portraitApertureF,
                    grade = state.grade,
                    lut = state.activeLut,
                    dateStampStyle = state.settings.dateStampStyle
                )
                rawFrame.recycle()
                val thumb = uri?.let { loadScaledBitmap(it, 240) }
                _uiState.update {
                    it.copy(
                        isPortraitProcessing = false,
                        lastThumbnail = thumb ?: it.lastThumbnail,
                        lastThumbnailIsVideo = if (thumb != null) false else it.lastThumbnailIsVideo
                    )
                }
                showBanner(
                    if (uri != null) String.format(Locale.US, "Portrait f/%.1f saved", state.settings.portraitApertureF)
                    else "Portrait capture failed"
                )
            } else {
                _uiState.update { it.copy(isPortraitProcessing = false) }
                showBanner("Portrait capture unavailable")
            }
        }
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

        playShutterSound()
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturingDual = true, showShutterFlash = true) }
            delay(90)
            _uiState.update { it.copy(showShutterFlash = false) }

            val origLens = _uiState.value.activeLens
            // Ensure primary capture comes from Rear camera (if user happened to flip to Front before entering Dual)
            if (origLens?.isFront == true) {
                previewSurface?.takeIf { it.isValid }?.let { surf ->
                    openLens(rearLens, surf)
                    delay(650)
                }
            }
            // Step 1: Capture primary Rear frame
            val backBmp = captureSingleMemoryBitmap()

            // Step 2: Grab Front frame (either simultaneously via hardware concurrent TextureView,
            // or seamlessly via BeReal-style quick Front lens flip on single-stream hardware!)
            var frontBmp = if (dualCameraController.isConcurrentLive) {
                dualCameraController.grabLiveFrontBitmap(mirrorHorizontal = state.settings.mirrorFrontSave)
            } else null

            if (frontBmp == null && frontLens != null) {
                val frontSurf = previewSurface?.takeIf { it.isValid }
                if (frontSurf != null) {
                    showBanner("Capturing reaction...")
                    openLens(frontLens, frontSurf)
                    delay(750)
                    frontBmp = captureSingleMemoryBitmap()
                    if (origLens != null && origLens.cameraId != frontLens.cameraId) {
                        previewSurface?.takeIf { it.isValid }?.let { openLens(origLens, it) }
                    }
                }
            } else if (origLens != null && origLens.isFront) {
                previewSurface?.takeIf { it.isValid }?.let { openLens(origLens, it) }
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
        playShutterSound()
        val useScreenFlash = state.activeLens?.isFront == true &&
            (state.settings.flashMode == com.truelens.camera.camera.FlashMode.ON ||
                (state.settings.flashMode == com.truelens.camera.camera.FlashMode.AUTO && latestAmbientLux < 60f))
        val captureLive = state.settings.livePhotoEnabled && state.settings.shootingMode == ShootingMode.PHOTO
        if (captureLive) {
            pendingLivePhotoName = null
            livePhotoController.triggerCaptureSequence { frames ->
                viewModelScope.launch {
                    // Wait briefly for the hero JPEG filename to arrive from Camera2
                    var targetName: String? = null
                    repeat(25) {
                        targetName = pendingLivePhotoName
                        if (targetName != null) return@repeat
                        delay(80)
                    }
                    val finalName = targetName ?: return@launch
                    val outFile = com.truelens.camera.modes.LivePhotoController.sidecarFileForName(
                        getApplication(),
                        finalName
                    )
                    val ok = livePhotoController.encodeSidecarMp4(frames, outFile)
                    if (ok) {
                        showBanner("LIVE moment captured (3s)")
                    }
                }
            }
        }
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
        openedDarkroomFromGallery = false
        _uiState.update { it.copy(isDarkroomOpen = false, isGalleryOpen = true) }
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
        savePersistedSettings(updated)
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
