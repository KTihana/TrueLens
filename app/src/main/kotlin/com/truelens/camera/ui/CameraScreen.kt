package com.truelens.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.truelens.camera.modes.LongExposureState
import com.truelens.camera.modes.LongExposureStyle
import com.truelens.camera.modes.StopMotionState
import com.truelens.camera.modes.TimeLapseState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.truelens.camera.camera.AstroStyle
import com.truelens.camera.camera.DocumentScanStyle
import com.truelens.camera.camera.DoubleExpBlendMode
import com.truelens.camera.camera.ShootingMode
import com.truelens.camera.camera.SkyPreset
import com.truelens.camera.camera.StabilizerMode
import com.truelens.camera.gl.AspectRatioMath
import com.truelens.camera.gl.CameraGlSurfaceView
import com.truelens.camera.gl.GyroStabilizer
import kotlinx.coroutines.delay
import kotlin.math.atan2

private val TrueBlack = TrueLensPalette.Obsidian
private val CanonAmber = TrueLensPalette.AccentViolet
private val CanonRed = TrueLensPalette.RecordCoral
private val ZeroAiGreen = TrueLensPalette.AccentViolet

@Composable
fun CameraScreen(
    viewModel: CameraViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val lutFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importCubeLut(uri)
    }

    if (state.isPoseInsposOpen) {
        PoseInsposScreen(
            folders = state.poseFolders,
            selectedFolderId = state.selectedPoseFolderId,
            isImporting = state.isImportingPoses,
            importStatus = state.poseImportStatus,
            pinterestBoards = state.pinterestBoards,
            isPinterestConnected = state.isPinterestConnected,
            savedPinterestClientId = state.savedPinterestClientId,
            onClose = { viewModel.closePoseInspos() },
            onSelectFolder = { viewModel.selectPoseFolder(it) },
            onCreateFolder = { viewModel.createPoseFolder(it) },
            onImportGalleryUris = { folderId, uris -> viewModel.importGalleryUris(folderId, uris) },
            onImportPublicPinterestUrl = { url -> viewModel.importPublicPinterestUrl(url) },
            onSavePinterestToken = { cid, tok -> viewModel.savePinterestToken(cid, tok) },
            onLaunchPinterestOAuth = { cid ->
                val url = viewModel.pinterestImporter.buildOAuthAuthorizeUrl(cid)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            onSyncPinterestBoard = { board -> viewModel.syncPinterestBoard(board) },
            onSelectPoseForOverlay = { item -> viewModel.selectPoseForOverlay(item) },
            onDeletePoseItem = { fid, iid -> viewModel.deletePoseItem(fid, iid) }
        )
        return
    }

    if (state.isGalleryOpen) {
        GalleryScreen(
            onClose = { viewModel.closeGallery() },
            onSelectPhotoForDarkroom = { uri, _ -> viewModel.openDarkroomForUri(uri) },
            onMediaDeleted = { viewModel.refreshLatestThumbnail() }
        )
        return
    }

    if (state.isDarkroomOpen) {
        DarkroomScreen(
            state = state,
            onClose = { viewModel.closeDarkroom() },
            onSelectFilmstripUri = { uri -> viewModel.openDarkroomForUri(uri) },
            onUpdateGrade = { viewModel.updateDarkroomGrade(it) },
            onUpdateKeystone = { v, h, r -> viewModel.updateDarkroomKeystone(v, h, r) },
            onSelectFrameStyle = { viewModel.setDarkroomFrameStyle(it) },
            onSelectDateStamp = { viewModel.setDarkroomDateStamp(it) },
            onApplyPreset = { viewModel.applyDarkroomPreset(it) },
            onSaveCustomPreset = { name -> viewModel.saveCustomPreset(name, state.darkroomGrade) },
            onDeleteCustomPreset = { id -> viewModel.deleteCustomPreset(id) },
            onUndo = { viewModel.undoDarkroom() },
            onRedo = { viewModel.redoDarkroom() },
            onDeletePhoto = { viewModel.deleteDarkroomPhoto() },
            onSaveKeyPhoto = { frame -> viewModel.saveDarkroomKeyPhoto(frame) },
            onExportLiveVideo = { viewModel.exportDarkroomLiveVideo() },
            onExport = { viewModel.exportDarkroomCopy() }
        )
        return
    }

    var focusTapPos by remember { mutableStateOf<Offset?>(null) }
    var rollDegrees by remember { mutableFloatStateOf(0f) }
    var isFlatLay by remember { mutableStateOf(false) }
    var flatTiltX by remember { mutableFloatStateOf(0f) }
    var flatTiltY by remember { mutableFloatStateOf(0f) }
    var showQuickTools by remember { mutableStateOf(false) }
    var showMoreModes by remember { mutableStateOf(false) }
    var swipeEvVisible by remember { mutableStateOf(false) }
    var swipeEvAccum by remember { mutableFloatStateOf(0f) }
    var lastPinchMs by remember { mutableStateOf(0L) }
    var modeSwipeAccum by remember { mutableFloatStateOf(0f) }
    val gyroStabilizer = remember { GyroStabilizer() }
    var glSurfaceRef by remember { mutableStateOf<CameraGlSurfaceView?>(null) }

    LaunchedEffect(swipeEvVisible, state.settings.evCompensationSteps) {
        if (swipeEvVisible) {
            delay(1300)
            swipeEvVisible = false
        }
    }

    BackHandler(
        enabled = showMoreModes || showQuickTools || state.activeDeck != ActiveDeck.NONE || state.isLiveTextSheetOpen
    ) {
        if (state.isLiveTextSheetOpen) {
            viewModel.closeLiveTextSheet()
        } else {
            showMoreModes = false
            showQuickTools = false
            viewModel.setActiveDeck(ActiveDeck.NONE)
        }
    }

    val isVideoOrSlowMo = state.settings.shootingMode == ShootingMode.VIDEO ||
        state.settings.shootingMode == ShootingMode.SLOW_MO ||
        state.settings.shootingMode == ShootingMode.CINEMATIC
    val superSteadyActive = isVideoOrSlowMo &&
        state.settings.stabilizerMode == StabilizerMode.SUPER_STEADY
    val needAccelLevel = state.settings.levelGauge || state.settings.shootingMode == ShootingMode.SCAN_DOC
    val needGyroStability = state.settings.shootingMode == ShootingMode.ASTRO ||
        state.settings.shootingMode == ShootingMode.LONG_EXP
    val astroRed = state.settings.astroRedUiEnabled
    val hudAccent = if (astroRed) Color(0xFFDC2626) else TrueLensPalette.AccentViolet

    // Accelerometer (Horizon + Flat-Lay Crosshair Level) + Ambient Light (True Auto & Night) + Gyroscope
    DisposableEffect(needAccelLevel, state.panoramaState.isCapturing, superSteadyActive, needGyroStability) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var lastGyroTs = 0L
        if (!superSteadyActive) {
            gyroStabilizer.reset()
            glSurfaceRef?.updateStabilizer(0f, 0f, 0f, 1.0f)
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (needAccelLevel) {
                            val ax = event.values[0]
                            val ay = event.values[1]
                            val az = event.values[2]
                            val flat = kotlin.math.abs(az) > 7.8f
                            isFlatLay = flat
                            if (flat) {
                                flatTiltX = flatTiltX * 0.75f + ax * 0.25f
                                flatTiltY = flatTiltY * 0.75f + ay * 0.25f
                            } else {
                                val angle = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
                                rollDegrees = rollDegrees * 0.8f + angle * 0.2f
                            }
                        }
                    }
                    Sensor.TYPE_LIGHT -> {
                        viewModel.updateAmbientLux(event.values[0])
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        if (needGyroStability) {
                            val wx = event.values[0]
                            val wy = event.values[1]
                            val wz = event.values[2]
                            val mag = kotlin.math.sqrt(wx * wx + wy * wy + wz * wz)
                            viewModel.onGyroAngularSpeed(mag)
                        }
                        if (state.panoramaState.isCapturing) {
                            if (lastGyroTs != 0L) {
                                val dt = (event.timestamp - lastGyroTs) / 1_000_000_000f
                                val yawDeg = Math.toDegrees((event.values[1] * dt).toDouble()).toFloat()
                                viewModel.onGyroYawDelta(yawDeg)
                            }
                            lastGyroTs = event.timestamp
                        }
                        if (superSteadyActive) {
                            gyroStabilizer.onGyroSample(
                                wx = event.values[0],
                                wy = event.values[1],
                                wz = event.values[2],
                                timestampNs = event.timestamp,
                                enabled = true
                            )
                            glSurfaceRef?.updateStabilizer(
                                offsetX = gyroStabilizer.offsetX,
                                offsetY = gyroStabilizer.offsetY,
                                rollRad = gyroStabilizer.rollRad,
                                scale = gyroStabilizer.cropScale
                            )
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (accel != null && needAccelLevel) {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        }
        if (light != null) {
            sm.registerListener(listener, light, SensorManager.SENSOR_DELAY_UI)
        }
        if (gyro != null && (state.panoramaState.isCapturing || superSteadyActive || needGyroStability)) {
            sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sm.unregisterListener(listener) }
    }

    // Auto-hide tap-to-focus reticle (stays visible while AF/AE is locked)
    LaunchedEffect(focusTapPos, state.settings.aeAfLocked) {
        if (focusTapPos != null && !state.settings.aeAfLocked) {
            delay(3200)
            focusTapPos = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrueBlack)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. COMPACT TOP ICON BAR (Zero wasted vertical gap — viewfinder starts immediately below!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TrueBlack)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.settings.friendMode) {
                    Text(
                        text = "Friend Mode",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    GlassChip(
                        text = "Exit",
                        accent = Color.White,
                        active = false,
                        onClick = { viewModel.toggleFriendMode() }
                    )
                } else {
                    MinimalTopIconButton(
                        icon = Icons.Default.AccessibilityNew,
                        contentDescription = "Pose Inspos",
                        active = state.poseOverlay.isActive,
                        onClick = { viewModel.openPoseInspos() }
                    )
                    MinimalTopIconButton(
                        icon = Icons.Default.Palette,
                        contentDescription = "Live Filters & Grading",
                        active = state.activeDeck == ActiveDeck.LIGHTROOM,
                        onClick = {
                            showQuickTools = false
                            showMoreModes = false
                            viewModel.setActiveDeck(ActiveDeck.LIGHTROOM)
                        }
                    )
                    MinimalTopIconButton(
                        icon = Icons.Default.Tune,
                        contentDescription = "Pro Manual Controls",
                        active = state.activeDeck == ActiveDeck.MANUAL,
                        onClick = {
                            showQuickTools = false
                            showMoreModes = false
                            viewModel.setActiveDeck(ActiveDeck.MANUAL)
                        }
                    )
                    val flashIcon = when (state.settings.flashMode) {
                        com.truelens.camera.camera.FlashMode.OFF -> Icons.Default.FlashOff
                        com.truelens.camera.camera.FlashMode.AUTO -> Icons.Default.FlashAuto
                        com.truelens.camera.camera.FlashMode.ON -> Icons.Default.FlashOn
                        com.truelens.camera.camera.FlashMode.TORCH -> Icons.Default.FlashlightOn
                    }
                    MinimalTopIconButton(
                        icon = flashIcon,
                        contentDescription = "Flash Mode",
                        active = state.settings.flashMode != com.truelens.camera.camera.FlashMode.OFF,
                        onClick = { viewModel.cycleFlashMode() }
                    )
                    MinimalTopIconButton(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = "Camera Tools",
                        active = showQuickTools,
                        onClick = {
                            showMoreModes = false
                            if (!showQuickTools) viewModel.setActiveDeck(ActiveDeck.NONE)
                            showQuickTools = !showQuickTools
                        }
                    )
                }
            }

            // 2. TOP-ALIGNED 1:1 TRUE-ASPECT VIEWFINDER REGION (Positioned high, right beneath top bar!)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TrueBlack)
            ) {
                val liveFrameScale = remember(
                    constraints.maxWidth,
                    constraints.maxHeight,
                    state.activeLens?.previewSize,
                    state.activeLens?.sensorOrientation
                ) {
                    val lens = state.activeLens
                    if (lens != null && constraints.maxWidth > 0 && constraints.maxHeight > 0) {
                        val qs = AspectRatioMath.computeExactFitScale(
                            bufferWidth = lens.previewSize.width,
                            bufferHeight = lens.previewSize.height,
                            sensorOrientationDeg = lens.sensorOrientation,
                            viewportWidth = constraints.maxWidth,
                            viewportHeight = constraints.maxHeight
                        )
                        qs.scaleX to qs.scaleY
                    } else {
                        1f to 1f
                    }
                }
                val frameWidthDp = maxWidth * liveFrameScale.first.coerceIn(0.1f, 1f)
                val frameHeightDp = maxHeight * liveFrameScale.second.coerceIn(0.1f, 1f)
                val bottomGapDp = (maxHeight - frameHeightDp).coerceAtLeast(0.dp)
                val rearLenses = state.lenses.filter { !it.isFront }
                val hasUltrawide = rearLenses.any { it.displayLabel.startsWith("0.") }

                // Exact 1:1 Camera Frame Box pinned to Alignment.TopCenter (matches flagship stock camera height!)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(width = frameWidthDp, height = frameHeightDp)
                ) {
                    // LIVE 60FPS OPENGL LIGHTROOM VIEWFINDER (Tap-to-Focus + Pinch-to-Zoom + Gestural Exposure Swipe + Hold to compare)
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    if (zoom != 1f) {
                                        lastPinchMs = System.currentTimeMillis()
                                        viewModel.updateCameraZoom(zoom)
                                    }
                                }
                            }
                            .pointerInput(state.activeLens?.cameraId) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        if (System.currentTimeMillis() - lastPinchMs > 280L) {
                                            swipeEvAccum = state.settings.evCompensationSteps.toFloat()
                                            swipeEvVisible = true
                                        }
                                    },
                                    onVerticalDrag = { _, dragAmount ->
                                        if (System.currentTimeMillis() - lastPinchMs <= 280L) return@detectVerticalDragGestures
                                        val minEv = state.activeLens?.aeCompensationRange?.lower ?: -12
                                        val maxEv = state.activeLens?.aeCompensationRange?.upper ?: 12
                                        // Dragging UP brightens (+EV), dragging DOWN darkens (-EV)
                                        swipeEvAccum = (swipeEvAccum - dragAmount / 28f).coerceIn(minEv.toFloat(), maxEv.toFloat())
                                        val nextSteps = swipeEvAccum.roundToInt()
                                        if (nextSteps != state.settings.evCompensationSteps) {
                                            viewModel.setEvSteps(nextSteps)
                                        }
                                        swipeEvVisible = true
                                    }
                                )
                            }
                            .pointerInput(state.settings.splitScreenEnabled, showMoreModes, state.isVideoRecording) {
                                if (!state.settings.splitScreenEnabled && !showMoreModes && !state.isVideoRecording) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { modeSwipeAccum = 0f },
                                        onHorizontalDrag = { _, dragAmount ->
                                            if (System.currentTimeMillis() - lastPinchMs > 280L) {
                                                modeSwipeAccum += dragAmount
                                            }
                                        },
                                        onDragEnd = {
                                            if (modeSwipeAccum < -110f) {
                                                viewModel.swipePrimaryMode(+1)
                                            } else if (modeSwipeAccum > 110f) {
                                                viewModel.swipePrimaryMode(-1)
                                            }
                                            modeSwipeAccum = 0f
                                        }
                                    )
                                }
                            }
                            .pointerInput(showQuickTools, state.activeDeck) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        if (showQuickTools || state.activeDeck != ActiveDeck.NONE) {
                                            showQuickTools = false
                                            viewModel.setActiveDeck(ActiveDeck.NONE)
                                            return@detectTapGestures
                                        }
                                        val nx = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        val ny = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                        focusTapPos = Offset(nx, ny)
                                        viewModel.triggerTapToFocus(nx, ny)
                                    },
                                    onLongPress = {
                                        viewModel.setBypassLiveGrade(true)
                                    },
                                    onPress = {
                                        tryAwaitRelease()
                                        viewModel.setBypassLiveGrade(false)
                                    }
                                )
                            },
                        factory = { ctx ->
                            CameraGlSurfaceView(
                                context = ctx,
                                onSurfaceReady = { surface ->
                                    viewModel.onPreviewSurfaceReady(surface)
                                },
                                onHistogramSampled = { bins ->
                                    viewModel.onLiveHistogramSampled(bins)
                                },
                                onPalmSampled = { bmp ->
                                    viewModel.onPalmPreviewSample(bmp)
                                },
                                onScannerSampled = { bmp ->
                                    viewModel.onScannerPreviewSample(bmp)
                                },
                                onLivePhotoSampled = { bmp ->
                                    viewModel.onLivePhotoPreviewSample(bmp)
                                }
                            ).also { glSurfaceRef = it }
                        },
                        update = { glView ->
                            state.activeLens?.let { lens ->
                                glView.configureCameraGeometry(
                                    width = lens.previewSize.width,
                                    height = lens.previewSize.height,
                                    sensorOrientation = lens.sensorOrientation
                                )
                            }
                            glView.renderer.histogramSamplingEnabled = state.settings.histogram || state.settings.trueLightAuto
                            glView.renderer.palmSamplingEnabled =
                                state.settings.handsFreeMode == com.truelens.camera.camera.HandsFreeMode.HAND ||
                                    state.settings.handsFreeMode == com.truelens.camera.camera.HandsFreeMode.BOTH
                            glView.renderer.scannerSamplingEnabled =
                                state.settings.shootingMode == ShootingMode.PHOTO &&
                                    !state.isVideoRecording &&
                                    (state.settings.qrScannerEnabled || state.settings.liveTextEnabled)
                            glView.renderer.livePhotoSamplingEnabled =
                                state.settings.livePhotoEnabled &&
                                    state.settings.shootingMode == ShootingMode.PHOTO &&
                                    !state.isVideoRecording
                            val bokehMode = state.settings.shootingMode == ShootingMode.PORTRAIT ||
                                state.settings.shootingMode == ShootingMode.CINEMATIC
                            val bokehStr = if (bokehMode) {
                                ((16.0f - state.settings.portraitApertureF) / 14.6f).coerceIn(0.05f, 1.0f)
                            } else 0f
                            glView.updateBokeh(bokehStr, state.rackFocusNormX, state.rackFocusNormY)
                            glView.updateGrade(
                                grade = state.grade,
                                focusPeaking = state.settings.focusPeaking,
                                zebras = state.settings.zebras,
                                macroLoupe = state.settings.shootingMode == ShootingMode.SUPER_MACRO ||
                                    (state.settings.shootingMode == ShootingMode.ASTRO && state.settings.astroStarLoupeEnabled),
                                splitScreenPos = if (state.settings.splitScreenEnabled) state.splitScreenPosition else -1f,
                                bypass = state.bypassLiveGrade
                            )
                        },
                        onRelease = { glView ->
                            viewModel.onPreviewSurfaceDestroyed()
                            glView.releaseRenderer()
                        }
                    )

                    // INTERACTIVE POSE OVERLAY GUIDE (Ghost / Subject Cutout / Neon Outline + Pinch/Drag/Rotate)
                    if (state.poseOverlay.isActive) {
                        PoseOverlayCanvas(
                            poseState = state.poseOverlay,
                            onTransform = { dx, dy, zoom, rot ->
                                viewModel.updatePoseTransform(dx, dy, zoom, rot)
                            }
                        )
                    }

                    // STOP MOTION ONION-SKIN LAST FRAME OVERLAY (1:1 ContentScale.Fit aligned pixel-for-pixel with viewfinder)
                    val smBmp = state.stopMotionState.lastFrameBitmap
                    if (state.settings.shootingMode == ShootingMode.STOP_MO &&
                        state.stopMotionState.onionSkinEnabled &&
                        smBmp != null && !smBmp.isRecycled
                    ) {
                        Image(
                            bitmap = smBmp.asImageBitmap(),
                            contentDescription = "Stop Motion Onion Skin",
                            contentScale = ContentScale.Fit,
                            alpha = state.stopMotionState.onionSkinOpacity,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // DOUBLE EXPOSURE SHOT #1 GHOST OVERLAY (frame Shot #2 directly on top of Shot #1!)
                    val ghostBmp = state.doubleExpFirstShot
                    if (state.settings.shootingMode == ShootingMode.DOUBLE_EXP &&
                        ghostBmp != null &&
                        !ghostBmp.isRecycled
                    ) {
                        Image(
                            bitmap = ghostBmp.asImageBitmap(),
                            contentDescription = "Double Exposure Shot 1 Ghost",
                            contentScale = ContentScale.Fit,
                            alpha = 0.45f,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // LIVE 90s VHS CAMCORDER VIEWFINDER OSD (only when VHS look is explicitly chosen!)
                    if (state.grade.vhsRetro > 0.01f && !state.bypassLiveGrade) {
                        Text(
                            text = if (state.isVideoRecording) "● REC  SP" else "PLAY ▶",
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 14.dp, top = 46.dp)
                        )
                        Text(
                            text = "SLP  '98",
                            color = Color(0xFFFFD88A).copy(alpha = 0.90f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 14.dp, bottom = 74.dp)
                        )
                    }

                    // LIVE SPLIT-SCREEN BEFORE/AFTER DRAG HANDLER
                    if (state.settings.splitScreenEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { change, _ ->
                                        val nx = (change.position.x / size.width.toFloat()).coerceIn(0.05f, 0.95f)
                                        viewModel.updateSplitScreenPosition(nx)
                                    }
                                }
                        )
                    }

                    // DUAL-CAMERA REACTION MODE OVERLAY (Draggable Split Divider + Floating Phone-Call PiP!)
                    if (state.settings.shootingMode == ShootingMode.DUAL) {
                        if (state.settings.dualLayout == com.truelens.camera.grading.DualCameraLayout.SPLIT) {
                            val splitTopDp = frameHeightDp * state.dualSplitRatio
                            val bottomHeightDp = frameHeightDp * (1f - state.dualSplitRatio)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(bottomHeightDp)
                                    .background(Color(0xD812131A))
                            ) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        android.view.TextureView(ctx).also { tv ->
                                            viewModel.attachDualFrontTextureView(tv)
                                        }
                                    },
                                    onRelease = { viewModel.detachDualFrontTextureView() }
                                )
                                if (!state.isDualConcurrentLive) {
                                    Column(
                                        modifier = Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cameraswitch,
                                            contentDescription = "Front Reaction",
                                            tint = TrueLensPalette.AccentViolet,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = if (state.dualSwapped) "Back Camera (${((1f - state.dualSplitRatio) * 100).toInt()}%)"
                                            else "Front Reaction (${((1f - state.dualSplitRatio) * 100).toInt()}%)",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Drag line to resize • Snap captures both",
                                            color = TrueLensPalette.MutedGray,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            // Click-and-Drag Dividing Line Handle!
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = splitTopDp - 18.dp)
                                    .height(36.dp)
                                    .pointerInput(frameHeightDp) {
                                        detectVerticalDragGestures { change, dragAmount ->
                                            change.consume()
                                            val framePx = frameHeightDp.toPx().coerceAtLeast(1f)
                                            val deltaRatio = dragAmount / framePx
                                            viewModel.updateDualSplitRatio(state.dualSplitRatio + deltaRatio)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(Color.White.copy(alpha = 0.88f))
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xEE181820))
                                        .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${(state.dualSplitRatio * 100).toInt()} / ${((1f - state.dualSplitRatio) * 100).toInt()}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        } else {
                            // Phone-Call Style Floating Rounded PiP Card (draggable to any corner!)
                            val pipWidthDp = frameWidthDp * 0.30f
                            val pipHeightDp = pipWidthDp * 1.33f
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (state.dualPipX * frameWidthDp.toPx()).roundToInt(),
                                            (state.dualPipY * frameHeightDp.toPx()).roundToInt()
                                        )
                                    }
                                    .size(width = pipWidthDp, height = pipHeightDp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xE014151D))
                                    .border(2.dp, Color.White, RoundedCornerShape(16.dp))
                                    .pointerInput(frameWidthDp, frameHeightDp) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val dx = dragAmount.x / frameWidthDp.toPx().coerceAtLeast(1f)
                                            val dy = dragAmount.y / frameHeightDp.toPx().coerceAtLeast(1f)
                                            viewModel.updateDualPipPosition(state.dualPipX + dx, state.dualPipY + dy)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        android.view.TextureView(ctx).also { tv ->
                                            viewModel.attachDualFrontTextureView(tv)
                                        }
                                    },
                                    onRelease = { viewModel.detachDualFrontTextureView() }
                                )
                                if (!state.isDualConcurrentLive) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cameraswitch,
                                            contentDescription = "PiP Front",
                                            tint = TrueLensPalette.AccentViolet,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = if (state.dualSwapped) "Back" else "Front",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // VIEWFINDER OVERLAYS (100% bounded inside exact 1:1 live camera frame!)
                    AspectRatioMaskOverlay(
                        mask = state.settings.aspectRatioMask,
                        frameScale = 1f to 1f
                    )
                    if (state.settings.shootingMode == ShootingMode.SCAN_DOC) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val mx = size.width * 0.08f
                            val my = size.height * 0.08f
                            val w = size.width * 0.84f
                            val h = size.height * 0.84f
                            val corner = 26.dp.toPx()
                            val stroke = 2.5.dp.toPx()
                            val col = Color(0xFF34D399)
                            drawLine(col, Offset(mx, my), Offset(mx + corner, my), stroke)
                            drawLine(col, Offset(mx, my), Offset(mx, my + corner), stroke)
                            drawLine(col, Offset(mx + w, my), Offset(mx + w - corner, my), stroke)
                            drawLine(col, Offset(mx + w, my), Offset(mx + w, my + corner), stroke)
                            drawLine(col, Offset(mx, my + h), Offset(mx + corner, my + h), stroke)
                            drawLine(col, Offset(mx, my + h), Offset(mx, my + h - corner), stroke)
                            drawLine(col, Offset(mx + w, my + h), Offset(mx + w - corner, my + h), stroke)
                            drawLine(col, Offset(mx + w, my + h), Offset(mx + w, my + h - corner), stroke)
                        }
                    }
                    CompositionGridOverlay(
                        grid = state.settings.gridOverlay,
                        frameScale = 1f to 1f
                    )
                    TapFocusReticle(
                        normalizedPosition = focusTapPos,
                        isLocked = state.telemetry.isAfLocked
                    )

                    // Automatic QR Code Corner Brackets (iPhone-style yellow brackets locked onto QR code!)
                    if (state.settings.qrScannerEnabled && state.settings.shootingMode == ShootingMode.PHOTO) {
                        QrBracketsOverlay(qr = state.detectedQr)
                    }

                    if (needAccelLevel) {
                        HorizonLevelOverlay(
                            rollDegrees = rollDegrees,
                            isFlatLay = isFlatLay,
                            flatTiltX = flatTiltX,
                            flatTiltY = flatTiltY,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    // Quick Top-Left Auto Low-Light Moon Pill (Toggleable via Auto Night!) & Top-Right LIVE Photo Pill
                    if (!state.settings.friendMode && !showMoreModes && state.settings.shootingMode == ShootingMode.PHOTO) {
                        if ((state.settings.autoNightPromptEnabled && state.isDarkLowLightScene) ||
                            state.settings.nightModeDuration != com.truelens.camera.camera.NightModeDuration.OFF
                        ) {
                            val nightActive = state.settings.nightModeDuration != com.truelens.camera.camera.NightModeDuration.OFF
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (nightActive) Color(0xDDFFD60A) else Color(0x88000000))
                                    .clickable { viewModel.cycleNightModeDuration() }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NightsStay,
                                    contentDescription = "Night Mode",
                                    tint = if (nightActive) Color.Black else Color(0xFFFFD60A),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (nightActive) state.settings.nightModeDuration.label else "Night",
                                    color = if (nightActive) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Quick 1-tap LIVE Photo Badge (amber ring when ON!)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (state.settings.livePhotoEnabled) Color(0xCC1C1808) else Color(0x66000000))
                                .border(
                                    0.75.dp,
                                    if (state.settings.livePhotoEnabled) Color(0xFFFFB300) else Color.White.copy(alpha = 0.22f),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.toggleLivePhoto() }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MotionPhotosOn,
                                contentDescription = "Live Photos",
                                tint = if (state.settings.livePhotoEnabled) Color(0xFFFFB300) else Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "LIVE",
                                color = if (state.settings.livePhotoEnabled) Color(0xFFFFB300) else Color.White.copy(alpha = 0.75f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Multi-Frame Optical Night Mode Progress Ring
                    if (state.isNightStacking) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(104.dp)
                                .clip(CircleShape)
                                .background(Color(0xBB0C0C10)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { state.nightStackProgress },
                                color = Color(0xFFFFD60A),
                                strokeWidth = 4.dp,
                                modifier = Modifier.size(90.dp)
                            )
                            Text(
                                text = "NIGHT",
                                color = Color(0xFFFFD60A),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Portrait / Action Pan / Double Exp / VHS Bake Processing Spinner
                    if (state.isPortraitProcessing || state.isActionPanProcessing || state.isProcessingDoubleExp || state.isBakingVhs) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xCC0C0C10))
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            CircularProgressIndicator(
                                color = hudAccent,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(32.dp)
                            )
                            val procLabel = when {
                                state.isBakingVhs -> "Baking VHS '94..."
                                state.isActionPanProcessing -> "Action Pan..."
                                state.isProcessingDoubleExp -> "Blending 2×..."
                                else -> "Portrait..."
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(procLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Astrophotography Optical Stacking Progress Ring + Anti-Shake Status
                    if (state.isAstroCapturing) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xDD0A0A0E))
                                .border(1.dp, hudAccent.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { state.astroProgress },
                                    color = hudAccent,
                                    strokeWidth = 4.dp,
                                    modifier = Modifier.size(76.dp)
                                )
                                Text(
                                    text = "${(state.astroProgress * 100).toInt()}%",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.astroPhaseLabel.ifBlank { "Stacking Stars..." },
                                color = if (astroRed) Color(0xFFEF4444) else Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Kept ${state.astroKeptFrames} • Skipped ${state.astroDroppedFrames} shaken",
                                color = TrueLensPalette.MutedGray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Self-Timer Countdown Overlay
                    if (state.countdownRemainingSec > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .border(2.dp, CanonAmber, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${state.countdownRemainingSec}",
                                color = CanonAmber,
                                fontSize = 54.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Hands-Free Open-Palm Hold Progress Ring
                    if (state.palmHoldProgress > 0.05f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(Color(0xAA0C0C10)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { state.palmHoldProgress },
                                color = TrueLensPalette.AccentViolet,
                                strokeWidth = 4.dp,
                                modifier = Modifier.size(84.dp)
                            )
                            Text(
                                text = "PALM",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Gestural Exposure Swipe Indicator Pill (Halide-style, right edge, auto-fades!)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = swipeEvVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 14.dp)
                    ) {
                        val evDisplay = state.settings.evCompensationSteps / 3f
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xCC121218))
                                .border(0.75.dp, TrueLensPalette.AccentViolet.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WbSunny,
                                contentDescription = "Exposure",
                                tint = TrueLensPalette.AccentViolet,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format(java.util.Locale.US, "%+.1f", evDisplay),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Floating Translucent 0.6 | 1x | 2 | 3 Lens & Smooth Zoom Wheel + Automatic QR Suggestion Pill!
                    val hasActiveModeBar = isVideoOrSlowMo ||
                        state.settings.shootingMode == ShootingMode.PORTRAIT ||
                        state.settings.shootingMode == ShootingMode.ASTRO ||
                        state.settings.shootingMode == ShootingMode.DOUBLE_EXP ||
                        state.settings.shootingMode == ShootingMode.ACTION_PAN ||
                        state.settings.shootingMode == ShootingMode.DUAL ||
                        state.settings.shootingMode == ShootingMode.LONG_EXP ||
                        state.settings.shootingMode == ShootingMode.SUPER_MACRO ||
                        state.settings.shootingMode == ShootingMode.STOP_MO ||
                        state.settings.shootingMode == ShootingMode.SCAN_DOC ||
                        state.settings.shootingMode == ShootingMode.PANORAMA ||
                        state.settings.shootingMode == ShootingMode.TIMELAPSE
                    val barOverlapDp = when {
                        state.settings.shootingMode == ShootingMode.ASTRO -> (108.dp - bottomGapDp).coerceAtLeast(0.dp)
                        hasActiveModeBar -> (68.dp - bottomGapDp).coerceAtLeast(0.dp)
                        else -> 0.dp
                    }

                    // Compact iPhone-Style QR Suggestion Pill (floats cleanly right above zoom bar!)
                    val activeQr = state.detectedQr
                    if (activeQr != null && !showMoreModes && !showQuickTools && state.settings.shootingMode == ShootingMode.PHOTO) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 62.dp + barOverlapDp, start = 20.dp, end = 20.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xEEFFD60A))
                                .clickable {
                                    handleQrCodeAction(context, activeQr)
                                    viewModel.dismissQr()
                                }
                                .padding(horizontal = 13.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = when (activeQr.actionType) {
                                    com.truelens.camera.modes.QrActionType.WIFI -> Icons.Default.Wifi
                                    com.truelens.camera.modes.QrActionType.URL -> Icons.Default.Link
                                    else -> Icons.Default.QrCode2
                                },
                                contentDescription = "QR Action",
                                tint = Color.Black,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeQr.displayLabel,
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss QR",
                                tint = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { viewModel.dismissQr() }
                            )
                        }
                    }

                    // Bottom-Right Quick Corner Icons: Sticky QR button + Live Text [T] button (iPhone one-hand reach!)
                    if (!showMoreModes && !showQuickTools && state.activeDeck == ActiveDeck.NONE && state.settings.shootingMode == ShootingMode.PHOTO) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 14.dp + barOverlapDp)
                        ) {
                            val stickyQr = state.stickyQr
                            if (stickyQr != null) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xDDFFD60A))
                                        .clickable {
                                            handleQrCodeAction(context, stickyQr)
                                            viewModel.dismissQr()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode2,
                                        contentDescription = "Open Scanned QR",
                                        tint = Color.Black,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            if (state.detectedLiveText != null && state.settings.liveTextEnabled) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xAA101218))
                                        .border(1.dp, Color(0xFFFFD60A).copy(alpha = 0.85f), CircleShape)
                                        .clickable { viewModel.openLiveTextSheet() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TextFields,
                                        contentDescription = "Live Text Detected",
                                        tint = Color(0xFFFFD60A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (!showMoreModes &&
                        !showQuickTools &&
                        state.activeDeck == ActiveDeck.NONE &&
                        state.activeLens?.isFront != true &&
                        state.settings.shootingMode != ShootingMode.DUAL
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 14.dp + barOverlapDp)
                        ) {
                            FloatingLensZoomCapsule(
                                activeLens = state.activeLens,
                                zoomRatio = state.zoomRatio,
                                hasUltrawide = hasUltrawide,
                                onSelectStop = { stop -> viewModel.selectZoomStop(stop) },
                                onExactZoom = { z -> viewModel.setExactZoom(z) }
                            )
                        }
                    }

                    // Floating Draggable Second Shutter Button (Feature #9: Tap to shoot • Long-press & drag to move!)
                    if (state.settings.floatingShutterEnabled && !showMoreModes) {
                        var isDraggingFloat by remember { mutableStateOf(false) }
                        var dragNormX by remember(state.floatingShutterX) { mutableFloatStateOf(state.floatingShutterX) }
                        var dragNormY by remember(state.floatingShutterY) { mutableFloatStateOf(state.floatingShutterY) }
                        Box(
                            modifier = Modifier
                                .offset {
                                    val pxX = (dragNormX * frameWidthDp.toPx()).roundToInt() - 29.dp.roundToPx()
                                    val pxY = (dragNormY * frameHeightDp.toPx()).roundToInt() - 29.dp.roundToPx()
                                    IntOffset(pxX, pxY)
                                }
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(if (isDraggingFloat) hudAccent.copy(alpha = 0.45f) else Color(0x66000000))
                                .border(
                                    width = if (isDraggingFloat) 2.5.dp else 2.dp,
                                    color = if (isDraggingFloat) hudAccent else Color.White.copy(alpha = 0.88f),
                                    shape = CircleShape
                                )
                                .pointerInput(frameWidthDp, frameHeightDp) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            isDraggingFloat = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = {
                                            isDraggingFloat = false
                                            viewModel.updateFloatingShutterPos(dragNormX, dragNormY)
                                        },
                                        onDragCancel = {
                                            isDraggingFloat = false
                                            viewModel.updateFloatingShutterPos(dragNormX, dragNormY)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val wPx = frameWidthDp.toPx().coerceAtLeast(1f)
                                            val hPx = frameHeightDp.toPx().coerceAtLeast(1f)
                                            dragNormX = (dragNormX + dragAmount.x / wPx).coerceIn(0.08f, 0.92f)
                                            dragNormY = (dragNormY + dragAmount.y / hPx).coerceIn(0.12f, 0.88f)
                                        }
                                    )
                                }
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.onShutterPressed()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isVideoOrSlowMo || state.isVideoRecording) TrueLensPalette.RecordCoral.copy(alpha = 0.85f)
                                        else Color.White.copy(alpha = 0.88f)
                                    )
                            )
                        }
                    }
                }

                // Floating Top-of-Viewfinder HUDs (Histogram, Pose Bar, Tap-to-Focus EV Slider, Status Banner)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!state.settings.friendMode && state.settings.histogram) {
                        LiveHistogramView(bins = state.histogram)
                    }

                    if (state.poseOverlay.isActive) {
                        Spacer(modifier = Modifier.height(6.dp))
                        PoseOverlayControlBar(
                            poseState = state.poseOverlay,
                            onOpacityChange = { viewModel.setPoseOpacity(it) },
                            onBlendModeChange = { viewModel.setPoseBlendMode(it) },
                            onToggleMirror = { viewModel.togglePoseMirror() },
                            onToggleLock = { viewModel.togglePoseLock() },
                            onResetTransform = { viewModel.resetPoseTransform() },
                            onToggleHide = { viewModel.togglePoseHide() },
                            onClearPose = { viewModel.clearPoseOverlay() }
                        )
                    }

                    if (focusTapPos != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val minEv = state.activeLens?.aeCompensationRange?.lower ?: -12
                        val maxEv = state.activeLens?.aeCompensationRange?.upper ?: 12
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xCC0E1014))
                                .border(0.75.dp, CanonAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WbSunny,
                                contentDescription = "Exposure",
                                tint = CanonAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Slider(
                                value = state.settings.evCompensationSteps.toFloat(),
                                onValueChange = { viewModel.setEvSteps(it.toInt()) },
                                valueRange = minEv.toFloat()..maxEv.toFloat(),
                                modifier = Modifier.weight(1f).height(26.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = CanonAmber,
                                    activeTrackColor = CanonAmber,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                )
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (state.settings.aeAfLocked) CanonAmber else Color(0x22FFFFFF))
                                    .clickable { viewModel.toggleAeAfLock() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.settings.aeAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "AF/AE Lock",
                                    tint = if (state.settings.aeAfLocked) Color.Black else Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (state.settings.aeAfLocked) " AF/AE" else " LOCK",
                                    color = if (state.settings.aeAfLocked) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    if (state.bypassLiveGrade) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "RAW SENSOR PREVIEW (UNGRADED)",
                                color = CanonAmber,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (state.statusBanner != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.78f))
                                .border(0.75.dp, CanonAmber.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = state.statusBanner!!,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Airy Translucent "MORE" Mode Grid Overlay (floats over viewfinder, leaving bottom mode strip visible!)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showMoreModes && !state.settings.friendMode,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    MoreModesOverlay(
                        activeMode = state.settings.shootingMode,
                        isProDeckOpen = state.activeDeck == ActiveDeck.MANUAL,
                        onSelectPro = {
                            showMoreModes = false
                            showQuickTools = false
                            viewModel.setShootingMode(ShootingMode.PHOTO)
                            viewModel.setActiveDeck(ActiveDeck.MANUAL)
                        },
                        onSelectMode = { mode ->
                            showMoreModes = false
                            showQuickTools = false
                            viewModel.setActiveDeck(ActiveDeck.NONE)
                            viewModel.setShootingMode(mode)
                        },
                        onSelectFriendMode = {
                            showMoreModes = false
                            showQuickTools = false
                            viewModel.setActiveDeck(ActiveDeck.NONE)
                            viewModel.toggleFriendMode()
                        },
                        onDismiss = { showMoreModes = false }
                    )
                }

                // Floating Translucent Panels & Mode Bars anchored at Bottom of Viewfinder Region
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                ) {
                    // Floating 4-Column Translucent Camera Tools Panel
                    AnimatedVisibility(
                        visible = showQuickTools && !state.settings.friendMode && !showMoreModes,
                        enter = slideInVertically { it / 2 } + fadeIn(),
                        exit = slideOutVertically { it / 2 } + fadeOut()
                    ) {
                        QuickToolsSheet(
                            settings = state.settings,
                            lightingSceneLabel = state.lightAnalysis.scene.label.lowercase().replaceFirstChar { it.uppercase() },
                            onCycleFormat = { viewModel.cycleCaptureFormat() },
                            onCycleTimer = { viewModel.cycleTimerDuration() },
                            onCycleRatio = { viewModel.cycleAspectRatioMask() },
                            onCycleGrid = { viewModel.cycleGridOverlay() },
                            onCycleBurst = { viewModel.cycleSoloBurstCount() },
                            onToggleTrueLightAuto = { viewModel.toggleTrueLightAuto() },
                            onToggleMirrorFront = { viewModel.toggleMirrorFrontSave() },
                            onToggleLevel = { viewModel.toggleLevelGauge() },
                            onToggleHistogram = { viewModel.toggleHistogram() },
                            onToggleFocusEdges = { viewModel.toggleFocusPeaking() },
                            onCycleDateStamp = { viewModel.cycleDateStampStyle() },
                            onCycleHandsFree = { viewModel.cycleHandsFreeMode() },
                            onToggleEnhanceMode = { viewModel.toggleEnhanceMode() },
                            onToggleLivePhoto = { viewModel.toggleLivePhoto() },
                            onCycleNightMode = { viewModel.cycleNightModeDuration() },
                            onToggleQrScanner = { viewModel.toggleQrScanner() },
                            onToggleLiveText = { viewModel.toggleLiveText() },
                            onToggleShutterSound = { viewModel.toggleShutterSound() },
                            onToggleFloatingShutter = { viewModel.toggleFloatingShutter() },
                            onToggleAudioMeter = { viewModel.toggleAudioMeter() },
                            onToggleAutoNightPrompt = { viewModel.toggleAutoNightPrompt() }
                        )
                    }

                    // Slide-up Pro Manual Deck or Lightroom Live Grading Deck
                    AnimatedVisibility(
                        visible = state.activeDeck == ActiveDeck.MANUAL && !showMoreModes,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        CanonManualDeck(
                            lens = state.activeLens,
                            settings = state.settings,
                            savedCanonSlots = state.savedCanonSlots,
                            onExposureMode = { viewModel.setExposureMode(it) },
                            onIso = { viewModel.setIso(it) },
                            onShutterNs = { viewModel.setShutterSpeedNs(it) },
                            onEvSteps = { viewModel.setEvSteps(it) },
                            onFocusDiopters = { viewModel.setFocusDiopters(it) },
                            onWhiteBalanceKelvin = { viewModel.setWhiteBalanceKelvin(it) },
                            onCycleAeb = { viewModel.cycleAebMode() },
                            onLoadCanonSlot = { viewModel.loadCanonSlot(it) },
                            onSaveCanonSlot = { viewModel.saveCanonSlot(it) }
                        )
                    }

                    AnimatedVisibility(
                        visible = state.activeDeck == ActiveDeck.LIGHTROOM && !showMoreModes,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        LightroomLiveDeck(
                            grade = state.grade,
                            activePresetId = state.activePresetId,
                            customPresets = state.customPresets,
                            activeLutName = state.activeLut?.name,
                            splitScreenEnabled = state.settings.splitScreenEnabled,
                            onToggleSplitScreen = { viewModel.toggleSplitScreen() },
                            onImportCubeLut = { lutFilePicker.launch(arrayOf("*/*")) },
                            onClearCubeLut = { viewModel.clearCubeLut() },
                            onUpdateGrade = { viewModel.updateGrade(it) },
                            onSelectPreset = { viewModel.applyPreset(it) },
                            onSaveCustomPreset = { name -> viewModel.saveCustomPreset(name, state.grade) },
                            onDeleteCustomPreset = { id -> viewModel.deleteCustomPreset(id) },
                            onReset = { viewModel.resetGrade() }
                        )
                    }

                    if (!showMoreModes) {
                        CameraModeControlBars(
                            state = state,
                            viewModel = viewModel,
                            isVideoOrSlowMo = isVideoOrSlowMo,
                            astroRed = astroRed,
                            hudAccent = hudAccent
                        )
                    }
                } // end Bottom-of-Viewfinder Panels Column
            } // end Top-Aligned 1:1 Viewfinder BoxWithConstraints

            // 3. SOLID BLACK BOTTOM CONTROL DOCK (Chevron + Mode Strip + Circular Gallery / Shutter / Flip)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TrueBlack)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
            ) {
                // Subtle Chevron Handle (tap to toggle Camera Tools — sits right beneath viewfinder!)
                if (!state.settings.friendMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showMoreModes = false
                                if (!showQuickTools) viewModel.setActiveDeck(ActiveDeck.NONE)
                                showQuickTools = !showQuickTools
                            }
                            .padding(top = 6.dp, bottom = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showQuickTools) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = "Camera Tools",
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Clean Primary Mode Strip ABOVE Shutter: PHOTO | VIDEO | PORTRAIT | DUAL | MORE (Swipeable!)
                    val primaryModes = listOf(ShootingMode.PHOTO, ShootingMode.VIDEO, ShootingMode.PORTRAIT, ShootingMode.DUAL)
                    val isExtraModeActive = state.settings.shootingMode !in primaryModes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(state.isVideoRecording) {
                                if (!state.isVideoRecording) {
                                    var stripAccum = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { stripAccum = 0f },
                                        onHorizontalDrag = { _, dx -> stripAccum += dx },
                                        onDragEnd = {
                                            if (stripAccum < -60f) viewModel.swipePrimaryMode(+1)
                                            else if (stripAccum > 60f) viewModel.swipePrimaryMode(-1)
                                        }
                                    )
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        primaryModes.forEach { mode ->
                            val selected = !showMoreModes && state.settings.shootingMode == mode
                            Text(
                                text = mode.label,
                                color = if (selected) hudAccent else if (astroRed) Color(0x99EF4444) else Color.White.copy(alpha = 0.88f),
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.6.sp,
                                modifier = Modifier
                                    .clickable {
                                        showMoreModes = false
                                        showQuickTools = false
                                        viewModel.setActiveDeck(ActiveDeck.NONE)
                                        viewModel.setShootingMode(mode)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                        if (isExtraModeActive && !showMoreModes) {
                            Text(
                                text = state.settings.shootingMode.label,
                                color = hudAccent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .clickable { showMoreModes = true }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = "MORE",
                            color = if (showMoreModes) hudAccent else if (astroRed) Color(0x99EF4444) else Color.White.copy(alpha = 0.88f),
                            fontSize = 11.5.sp,
                            fontWeight = if (showMoreModes) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier
                                .clickable {
                                    showQuickTools = false
                                    viewModel.setActiveDeck(ActiveDeck.NONE)
                                    showMoreModes = !showMoreModes
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }

                // Ultra-Clean 3-Button Shutter Dock:
                // Idle: Gallery | Shutter | Flip
                // Recording Video: Snap Photo Mid-Video | Stop | Pause/Resume!
                val isRecordingMode = state.isVideoRecording || state.panoramaState.isCapturing || state.timeLapseState.isRunning || state.isAstroCapturing
                val isVideoRedShutter = state.settings.shootingMode == ShootingMode.VIDEO ||
                    state.settings.shootingMode == ShootingMode.SLOW_MO ||
                    state.settings.shootingMode == ShootingMode.CINEMATIC
                if (!showMoreModes) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 38.dp, end = 38.dp, top = 8.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: While filming -> Snap Still Photo button; otherwise Circular Gallery Thumbnail!
                        if (state.isVideoRecording) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.captureMidVideoSnapshot()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Snap Photo While Recording",
                                    tint = Color.Black,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1A1B22))
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = if (state.lastThumbnail != null) 0.55f else 0.18f),
                                        CircleShape
                                    )
                                    .clickable { viewModel.openGallery() },
                                contentAlignment = Alignment.Center
                            ) {
                                val thumbBmp = state.lastThumbnail
                                if (thumbBmp != null && !thumbBmp.isRecycled) {
                                    Image(
                                        bitmap = thumbBmp.asImageBitmap(),
                                        contentDescription = "Open Gallery",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (state.lastThumbnailIsVideo) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.58f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Latest Video",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PhotoLibrary,
                                        contentDescription = "Gallery",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }
                        }

                        // Center: Clean Mechanical Shutter (White in Photo, Coral Red in Video, Deep Crimson in Astro Red UI!)
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .border(3.dp, if (astroRed) Color(0xFFDC2626) else Color.White, CircleShape)
                                .padding(5.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        astroRed -> Color(0xFF991B1B)
                                        isRecordingMode || isVideoRedShutter -> TrueLensPalette.RecordCoral
                                        else -> Color.White
                                    }
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.onShutterPressed()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRecordingMode) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White)
                                )
                            }
                        }

                        // Right: While filming -> Pause / Resume button; otherwise Front ↔ Back Camera Flip!
                        if (state.isVideoRecording) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isVideoPaused) Color(0xFFFFB300) else Color(0xFF1A1B22))
                                    .border(
                                        1.dp,
                                        if (state.isVideoPaused) Color(0xFFFFB300) else Color.White.copy(alpha = 0.25f),
                                        CircleShape
                                    )
                                    .clickable { viewModel.togglePauseVideoRecording() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.isVideoPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (state.isVideoPaused) "Resume Recording" else "Pause Recording",
                                    tint = if (state.isVideoPaused) Color.Black else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1A1B22))
                                    .border(0.75.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                                    .clickable { viewModel.flipFrontBackCamera() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = "Flip Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }
        }

        // Feature #8: Translucent Live Text Inspector Sheet (Copy / Call / Open Link / Email / Translate)
        val liveText = state.detectedLiveText
        if (state.isLiveTextSheetOpen && liveText != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xA6000000))
                    .pointerInput(Unit) { detectTapGestures { viewModel.closeLiveTextSheet() } },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xF212131A))
                        .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(24.dp))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.TextFields,
                                contentDescription = null,
                                tint = Color(0xFFFFD60A),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Text (${liveText.lineCount} lines)",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier
                                .size(22.dp)
                                .clickable { viewModel.closeLiveTextSheet() }
                        )
                    }

                    SelectionContainer {
                        Text(
                            text = liveText.fullText,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0A0B0F))
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassChip(
                            text = "Copy All",
                            accent = Color(0xFFFFD60A),
                            active = true,
                            onClick = {
                                copyToClipboard(context, "Live Text", liveText.fullText)
                                viewModel.closeLiveTextSheet()
                            }
                        )
                        if (liveText.firstUrl != null) {
                            GlassChip(
                                text = "Open Link",
                                accent = TrueLensPalette.AccentViolet,
                                active = true,
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(liveText.firstUrl)))
                                    }
                                    viewModel.closeLiveTextSheet()
                                }
                            )
                        }
                        if (liveText.firstPhone != null) {
                            GlassChip(
                                text = "Call",
                                accent = Color(0xFF34D399),
                                active = true,
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${liveText.firstPhone}")))
                                    }
                                    viewModel.closeLiveTextSheet()
                                }
                            )
                        }
                        if (liveText.firstEmail != null) {
                            GlassChip(
                                text = "Email",
                                accent = Color.White,
                                active = false,
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${liveText.firstEmail}")))
                                    }
                                    viewModel.closeLiveTextSheet()
                                }
                            )
                        }
                        GlassChip(
                            text = "Translate",
                            accent = Color.White,
                            active = false,
                            onClick = {
                                val encoded = Uri.encode(liveText.fullText.take(400))
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=en&text=$encoded"))
                                    )
                                }
                                viewModel.closeLiveTextSheet()
                            }
                        )
                    }
                }
            }
        }

        // Full-Screen Shutter Capture Flash + Front Camera Warm Soft-Ring Screen Flash
        AnimatedVisibility(
            visible = state.showShutterFlash,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.55f)))
        }
        AnimatedVisibility(
            visible = state.showFrontScreenFlash,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFFF8EE)))
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun handleQrCodeAction(context: Context, qr: com.truelens.camera.modes.DetectedQrCode) {
    try {
        when (qr.actionType) {
            com.truelens.camera.modes.QrActionType.URL -> {
                val url = if (qr.rawValue.contains("://")) qr.rawValue else "https://${qr.rawValue}"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            com.truelens.camera.modes.QrActionType.PHONE -> {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${qr.rawValue}")))
            }
            com.truelens.camera.modes.QrActionType.EMAIL -> {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${qr.rawValue}")))
            }
            com.truelens.camera.modes.QrActionType.SMS -> {
                val target = qr.rawValue.removePrefix("smsto:").removePrefix("SMSTO:").substringBefore(":")
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target")))
            }
            com.truelens.camera.modes.QrActionType.WIFI -> {
                if (!qr.wifiPassword.isNullOrBlank()) {
                    copyToClipboard(context, "Wi-Fi Password", qr.wifiPassword)
                    Toast.makeText(context, "Copied Wi-Fi password for ${qr.wifiSsid}", Toast.LENGTH_LONG).show()
                }
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            com.truelens.camera.modes.QrActionType.TEXT -> {
                copyToClipboard(context, "QR Code", qr.rawValue)
            }
        }
    } catch (_: Exception) {
        copyToClipboard(context, "QR Code", qr.rawValue)
    }
}

@Composable
private fun GlassChip(
    text: String,
    accent: Color,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) accent.copy(alpha = 0.22f) else Color(0x9914161C))
            .border(0.75.dp, if (active) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (active) accent else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    badge: String?,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) activeColor.copy(alpha = 0.22f) else Color(0x9914161C))
            .border(
                0.75.dp,
                if (active) activeColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f),
                RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = if (badge != null) 9.dp else 7.dp, vertical = 7.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) activeColor else Color.White,
            modifier = Modifier.size(16.dp)
        )
        if (badge != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = badge,
                color = activeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun HudPill(
    text: String,
    accent: Color,
    filled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) accent.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.55f))
            .border(1.dp, accent.copy(alpha = if (filled) 0.9f else 0.55f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.45f))
            .border(
                0.75.dp,
                if (active) activeColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) activeColor else Color.White.copy(alpha = 0.65f),
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            color = if (active) activeColor else Color.White.copy(alpha = 0.65f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DeckToggleButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) CanonAmber.copy(alpha = 0.22f) else Color(0x8816181F))
            .border(
                0.75.dp,
                if (active) CanonAmber.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) CanonAmber else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label.lowercase().replaceFirstChar { it.uppercase() },
            color = if (active) CanonAmber else Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Sleek segmented Real-Time Audio Level Meter (dB Bars) for Video / Cinematic mode.
 * Green (-54..-16 dB) -> Amber (-16..-6 dB) -> Red clipping (-6..0 dB).
 */
@Composable
internal fun LiveAudioLevelMeterRow(
    level01: Float,
    peakDb: Float,
    muted: Boolean
) {
    val segCount = 20
    val litCount = if (muted) 0 else (level01 * segCount).roundToInt().coerceIn(0, segCount)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "MIC",
            color = if (muted) TrueLensPalette.RecordCoral else TrueLensPalette.MutedGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Row(
            modifier = Modifier.weight(1f).height(8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until segCount) {
                val active = i < litCount
                val segColor = when {
                    !active -> Color(0x22FFFFFF)
                    i >= 17 -> Color(0xFFEF4444) // Clipping red
                    i >= 13 -> Color(0xFFF59E0B) // Hot amber
                    else -> Color(0xFF22C55E)    // Healthy green
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(segColor)
                )
            }
        }
        Text(
            text = if (muted) "MUTED" else String.format(java.util.Locale.US, "%+.0fdB", peakDb.coerceIn(-60f, 0f)),
            color = if (muted || peakDb > -6f) Color(0xFFEF4444) else Color.White.copy(alpha = 0.85f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
