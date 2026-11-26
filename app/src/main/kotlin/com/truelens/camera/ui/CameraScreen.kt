package com.truelens.camera.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
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
import com.truelens.camera.camera.DocumentScanStyle
import com.truelens.camera.camera.ShootingMode
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
            onExport = { viewModel.exportDarkroomCopy() }
        )
        return
    }

    var focusTapPos by remember { mutableStateOf<Offset?>(null) }
    var rollDegrees by remember { mutableFloatStateOf(0f) }
    var showQuickTools by remember { mutableStateOf(false) }
    var showMoreModes by remember { mutableStateOf(false) }
    var swipeEvVisible by remember { mutableStateOf(false) }
    var swipeEvAccum by remember { mutableFloatStateOf(0f) }
    var lastPinchMs by remember { mutableStateOf(0L) }
    val gyroStabilizer = remember { GyroStabilizer() }
    var glSurfaceRef by remember { mutableStateOf<CameraGlSurfaceView?>(null) }

    LaunchedEffect(swipeEvVisible, state.settings.evCompensationSteps) {
        if (swipeEvVisible) {
            delay(1300)
            swipeEvVisible = false
        }
    }

    BackHandler(enabled = showMoreModes || showQuickTools || state.activeDeck != ActiveDeck.NONE) {
        showMoreModes = false
        showQuickTools = false
        viewModel.setActiveDeck(ActiveDeck.NONE)
    }

    val isVideoOrSlowMo = state.settings.shootingMode == ShootingMode.VIDEO ||
        state.settings.shootingMode == ShootingMode.SLOW_MO
    val superSteadyActive = isVideoOrSlowMo &&
        state.settings.stabilizerMode == StabilizerMode.SUPER_STEADY

    // Accelerometer (Horizon Level) + Ambient Light (True Auto) + Gyroscope (Panorama & Super Steady)
    DisposableEffect(state.settings.levelGauge, state.panoramaState.isCapturing, superSteadyActive) {
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
                        if (state.settings.levelGauge) {
                            val ax = event.values[0]
                            val ay = event.values[1]
                            val angle = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
                            rollDegrees = rollDegrees * 0.8f + angle * 0.2f
                        }
                    }
                    Sensor.TYPE_LIGHT -> {
                        viewModel.updateAmbientLux(event.values[0])
                    }
                    Sensor.TYPE_GYROSCOPE -> {
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
        if (accel != null && state.settings.levelGauge) {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        }
        if (light != null) {
            sm.registerListener(listener, light, SensorManager.SENSOR_DELAY_UI)
        }
        if (gyro != null && (state.panoramaState.isCapturing || superSteadyActive)) {
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
                        active = state.activeDeck == ActiveDeck.LIGHTROOM || !state.grade.isNeutral,
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
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { offset ->
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
                            glView.updateGrade(
                                grade = state.grade,
                                focusPeaking = state.settings.focusPeaking,
                                zebras = state.settings.zebras,
                                macroLoupe = state.settings.shootingMode == ShootingMode.SUPER_MACRO,
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
                    if (state.settings.shootingMode == ShootingMode.STOP_MO &&
                        state.stopMotionState.onionSkinEnabled &&
                        state.stopMotionState.lastFrameBitmap != null
                    ) {
                        Image(
                            bitmap = state.stopMotionState.lastFrameBitmap!!.asImageBitmap(),
                            contentDescription = "Stop Motion Onion Skin",
                            contentScale = ContentScale.Fit,
                            alpha = state.stopMotionState.onionSkinOpacity,
                            modifier = Modifier.fillMaxSize()
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

                    if (state.settings.levelGauge) {
                        HorizonLevelOverlay(
                            rollDegrees = rollDegrees,
                            modifier = Modifier.align(Alignment.Center)
                        )
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
                                text = String.format("%+.1f", evDisplay),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Floating Translucent 0.6 | 1x | 2 | 3 Lens & Zoom Capsule right inside bottom of viewfinder!
                    val hasActiveModeBar = isVideoOrSlowMo ||
                        state.settings.shootingMode == ShootingMode.DUAL ||
                        state.settings.shootingMode == ShootingMode.LONG_EXP ||
                        state.settings.shootingMode == ShootingMode.SUPER_MACRO ||
                        state.settings.shootingMode == ShootingMode.STOP_MO ||
                        state.settings.shootingMode == ShootingMode.SCAN_DOC ||
                        state.settings.shootingMode == ShootingMode.PANORAMA ||
                        state.settings.shootingMode == ShootingMode.TIMELAPSE
                    if (!showMoreModes &&
                        !showQuickTools &&
                        state.activeDeck == ActiveDeck.NONE &&
                        state.activeLens?.isFront != true &&
                        state.settings.shootingMode != ShootingMode.DUAL
                    ) {
                        val barOverlapDp = if (hasActiveModeBar) (62.dp - bottomGapDp).coerceAtLeast(0.dp) else 0.dp
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 14.dp + barOverlapDp)
                        ) {
                            FloatingLensZoomCapsule(
                                activeLens = state.activeLens,
                                zoomRatio = state.zoomRatio,
                                hasUltrawide = hasUltrawide,
                                onSelectStop = { stop -> viewModel.selectZoomStop(stop) }
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
                            onCycleHandsFree = { viewModel.cycleHandsFreeMode() }
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

            // Video & Slow-Mo Super Steady Stabilizer Bar
            if (!state.settings.friendMode && isVideoOrSlowMo) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(
                            0.75.dp,
                            if (state.isVideoRecording) CanonRed else Color.White.copy(alpha = 0.18f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isVideoRecording) {
                        val mm = state.videoElapsedSec / 60
                        val ss = state.videoElapsedSec % 60
                        Text(
                            text = String.format("● REC  %02d:%02d  •  %s", mm, ss, state.videoFpsBadge),
                            color = TrueLensPalette.RecordCoral,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (state.settings.shootingMode == ShootingMode.SLOW_MO) {
                        Text(
                            text = "Slow-Mo • 120fps",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22FFFFFF))
                                    .clickable { viewModel.cycleVideoQuality() }
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = state.settings.videoQuality.label,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (state.settings.micMuted) TrueLensPalette.RecordCoral.copy(alpha = 0.22f) else Color(0x22FFFFFF))
                                    .clickable { viewModel.toggleMicMuted() }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.settings.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Toggle Mic",
                                    tint = if (state.settings.micMuted) TrueLensPalette.RecordCoral else Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.settings.shootingMode == ShootingMode.VIDEO && !state.isVideoRecording) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (state.settings.cinema180Shutter) TrueLensPalette.AccentVioletSoft
                                        else Color(0x22FFFFFF)
                                    )
                                    .border(
                                        0.75.dp,
                                        if (state.settings.cinema180Shutter) TrueLensPalette.AccentViolet
                                        else Color.White.copy(alpha = 0.16f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { viewModel.toggleCinema180Shutter() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "180°",
                                    color = if (state.settings.cinema180Shutter) TrueLensPalette.AccentViolet else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        HudPill(
                            text = state.settings.stabilizerMode.label,
                            accent = when (state.settings.stabilizerMode) {
                                StabilizerMode.SUPER_STEADY -> ZeroAiGreen
                                StabilizerMode.STANDARD -> CanonAmber
                                StabilizerMode.OFF -> Color.Gray
                            },
                            filled = state.settings.stabilizerMode != StabilizerMode.OFF,
                            onClick = { viewModel.cycleStabilizerMode() }
                        )
                    }
                }
            }

            // Dual-Camera Reaction Mode Bar (Split vs Call PiP + Swap!)
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.DUAL) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TrueLensPalette.GlassPanel)
                        .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.truelens.camera.grading.DualCameraLayout.entries.forEach { layout ->
                            val sel = state.settings.dualLayout == layout
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (sel) TrueLensPalette.AccentViolet else Color(0x1EFFFFFF))
                                    .clickable {
                                        if (!sel) viewModel.toggleDualLayout()
                                    }
                                    .padding(horizontal = 11.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = layout.label,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0x22FFFFFF))
                            .clickable { viewModel.swapDualCameras() }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "Swap",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Long Exposure / Bulb Control Bar
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.LONG_EXP) {
                val le = state.longExposureState
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(0.75.dp, CanonAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    if (le.isCapturing) {
                        Text(
                            text = "EXPOSING ${le.durationSec}s (${(le.progress * 100).toInt()}%) • HOLD STEADY",
                            color = CanonAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                LongExposureStyle.entries.forEach { st ->
                                    val sel = le.style == st
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) CanonAmber else Color(0x1EFFFFFF))
                                            .clickable { viewModel.setLongExposureStyle(st) }
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = st.label,
                                            color = if (sel) Color.Black else Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                LongExposureState.DURATION_OPTIONS.forEach { sec ->
                                    val sel = le.durationSec == sec
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) Color.White else Color(0x1EFFFFFF))
                                            .clickable { viewModel.setLongExposureDuration(sec) }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${sec}s",
                                            color = if (sel) Color.Black else Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Super Macro Focus Stacking Toggle Bar
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.SUPER_MACRO) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(0.75.dp, Color(0xFF00E5FF).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.isFocusStacking) "STACKING 5 FOCAL PLANES..." else "SUPER MACRO • 4× LOUPE",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    HudPill(
                        text = if (state.settings.macroFocusStackEnabled) "FOCUS STACK ON" else "SINGLE SHOT",
                        accent = if (state.settings.macroFocusStackEnabled) ZeroAiGreen else Color.White,
                        filled = state.settings.macroFocusStackEnabled,
                        onClick = { viewModel.toggleMacroFocusStack() }
                    )
                }
            }

            // Stop Motion Onion-Skin Studio Bar
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.STOP_MO) {
                val sm = state.stopMotionState
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(0.75.dp, CanonAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    if (sm.isExporting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = CanonAmber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "EXPORTING STOP-MOTION MP4 (${sm.frameCount} FRAMES)...",
                                color = CanonAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        // Row 1: Onion-Skin Toggle + Opacity Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HudPill(
                                text = if (sm.onionSkinEnabled) "Ghost On" else "Ghost Off",
                                accent = if (sm.onionSkinEnabled) CanonAmber else Color.Gray,
                                filled = sm.onionSkinEnabled,
                                onClick = { viewModel.toggleStopMotionOnionSkin() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Slider(
                                value = sm.onionSkinOpacity,
                                onValueChange = { viewModel.setStopMotionOpacity(it) },
                                valueRange = 0.05f..0.95f,
                                modifier = Modifier.weight(1f).height(24.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = CanonAmber,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                )
                            )
                            Text(
                                text = "${(sm.onionSkinOpacity * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(34.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(5.dp))

                        // Row 2: Frame counter + FPS picker + UNDO + EXPORT MP4
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format("%df • %.1fs", sm.frameCount, sm.durationSec),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                StopMotionState.FPS_OPTIONS.forEach { fps ->
                                    val sel = sm.fps == fps
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(if (sel) Color.White else Color(0x1EFFFFFF))
                                            .clickable { viewModel.setStopMotionFps(fps) }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${fps}p",
                                            color = if (sel) Color.Black else Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (sm.frameCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0x22FFFFFF))
                                            .clickable { viewModel.undoStopMotionFrame() }
                                            .padding(horizontal = 7.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "UNDO",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (sm.frameCount >= 2) ZeroAiGreen else Color(0x18FFFFFF))
                                        .clickable(enabled = sm.frameCount >= 2) { viewModel.exportStopMotionVideo() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "EXPORT",
                                        color = if (sm.frameCount >= 2) Color.Black else Color.Gray,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Document Scanner Control Bar
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.SCAN_DOC) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(0.75.dp, ZeroAiGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isScanningDocument) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = ZeroAiGreen,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ENHANCING & SAVING PDF...",
                                color = ZeroAiGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DocumentScanStyle.entries.forEach { style ->
                                val sel = state.settings.scanStyle == style
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (sel) ZeroAiGreen else Color(0x1EFFFFFF))
                                        .clickable { viewModel.setScanStyle(style) }
                                        .padding(horizontal = 9.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = style.label,
                                        color = if (sel) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        Text(
                            text = "PDF + JPG",
                            color = Color(0xFFA0A4AE),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Panorama Sweep Guide Bar
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.PANORAMA) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(0.75.dp, CanonAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.panoramaState.isStitching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = CanonAmber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "STITCHING PANORAMA...",
                                color = CanonAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else if (state.panoramaState.isCapturing) {
                        Text(
                            text = "PANORAMA • ${state.panoramaState.frameCount} FRAMES • ${state.panoramaState.accumulatedYawDeg.toInt()}°",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CanonAmber)
                                .clickable { viewModel.captureNextPanoramaFrame() }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "+ FRAME",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Text(
                            text = "PANORAMA • Tap shutter to start sweep (AE/WB locked)",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Time-Lapse Intervalometer Control Bar
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.TIMELAPSE) {
                val tl = state.timeLapseState
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(0.75.dp, if (tl.isRunning) CanonRed else Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (tl.isRunning) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "REC • ${tl.formattedElapsed()}",
                                color = CanonRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (tl.targetFrames > 0) "${tl.capturedFrames}/${tl.targetFrames} FRAMES" else "${tl.capturedFrames} FRAMES",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("INTERVAL", color = Color(0xFFA0A4AE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TimeLapseState.INTERVAL_OPTIONS.forEach { sec ->
                                    val sel = tl.intervalSeconds == sec
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) CanonAmber else Color(0x1EFFFFFF))
                                            .clickable { viewModel.setTimeLapseInterval(sec) }
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text("${sec}s", color = if (sel) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("FRAMES", color = Color(0xFFA0A4AE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TimeLapseState.FRAME_OPTIONS.forEach { cnt ->
                                    val sel = tl.targetFrames == cnt
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) Color.White else Color(0x1EFFFFFF))
                                            .clickable { viewModel.setTimeLapseTargetFrames(cnt) }
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text(if (cnt == 0) "∞" else "$cnt", color = if (sel) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
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

                    // Clean Primary Mode Strip ABOVE Shutter: PHOTO | VIDEO | DUAL | (active More mode) | MORE
                    val primaryModes = listOf(ShootingMode.PHOTO, ShootingMode.VIDEO, ShootingMode.DUAL)
                    val isExtraModeActive = state.settings.shootingMode !in primaryModes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        primaryModes.forEach { mode ->
                            val selected = !showMoreModes && state.settings.shootingMode == mode
                            Text(
                                text = mode.label,
                                color = if (selected) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.88f),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier
                                    .clickable {
                                        showMoreModes = false
                                        viewModel.setShootingMode(mode)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        if (isExtraModeActive && !showMoreModes) {
                            Text(
                                text = state.settings.shootingMode.label,
                                color = TrueLensPalette.AccentViolet,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .clickable { showMoreModes = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = "MORE",
                            color = if (showMoreModes) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.88f),
                            fontSize = 12.sp,
                            fontWeight = if (showMoreModes) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier
                                .clickable {
                                    showQuickTools = false
                                    viewModel.setActiveDeck(ActiveDeck.NONE)
                                    showMoreModes = !showMoreModes
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Ultra-Clean 3-Button Shutter Dock (Circular Gallery | Shutter | Flip Camera)
                val isRecordingMode = state.isVideoRecording || state.panoramaState.isCapturing || state.timeLapseState.isRunning
                val isVideoRedShutter = state.settings.shootingMode == ShootingMode.VIDEO ||
                    state.settings.shootingMode == ShootingMode.SLOW_MO
                if (!showMoreModes) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 38.dp, end = 38.dp, top = 8.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Circular Gallery Thumbnail (Photos & Videos — shows subtle play badge for Videos!)
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
                            if (state.lastThumbnail != null) {
                                Image(
                                    bitmap = state.lastThumbnail!!.asImageBitmap(),
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

                        // Center: Clean Mechanical Shutter (White in Photo, Coral Red in Video/Slow-Mo!)
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .border(3.dp, Color.White, CircleShape)
                                .padding(5.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
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

                        // Right: Clean Circular Front ↔ Back Camera Flip Button
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
                } else {
                    Spacer(modifier = Modifier.height(18.dp))
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
private fun HudPill(
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
