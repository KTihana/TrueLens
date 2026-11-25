package com.truelens.camera.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
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
import com.truelens.camera.gl.CameraGlSurfaceView
import com.truelens.camera.gl.GyroStabilizer
import kotlinx.coroutines.delay
import kotlin.math.atan2

private val TrueBlack = Color(0xFF060608)
private val CanonAmber = Color(0xFFFFB300)
private val CanonRed = Color(0xFFE53935)
private val ZeroAiGreen = Color(0xFF34D399)

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
            onSelectPhotoForDarkroom = { uri, _ -> viewModel.openDarkroomForUri(uri) }
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
            onApplyPreset = { viewModel.applyDarkroomPreset(it) },
            onSaveCustomPreset = { name -> viewModel.saveCustomPreset(name, state.darkroomGrade) },
            onDeleteCustomPreset = { id -> viewModel.deleteCustomPreset(id) },
            onExport = { viewModel.exportDarkroomCopy() }
        )
        return
    }

    var focusTapPos by remember { mutableStateOf<Offset?>(null) }
    var rollDegrees by remember { mutableFloatStateOf(0f) }
    val gyroStabilizer = remember { GyroStabilizer() }
    var glSurfaceRef by remember { mutableStateOf<CameraGlSurfaceView?>(null) }

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
        // 1. LIVE 60FPS OPENGL LIGHTROOM VIEWFINDER (Tap-to-Focus + Pinch-to-Zoom + Hold to compare)
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            viewModel.updateCameraZoom(zoom)
                        }
                    }
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
                CameraGlSurfaceView(ctx) { surface ->
                    viewModel.onPreviewSurfaceReady(surface)
                }.also { glSurfaceRef = it }
            },
            update = { glView ->
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
                glView.releaseRenderer()
            }
        )

        // 2. INTERACTIVE POSE OVERLAY GUIDE (Ghost / Subject Cutout / Neon Outline + Pinch/Drag/Rotate)
        if (state.poseOverlay.isActive) {
            PoseOverlayCanvas(
                poseState = state.poseOverlay,
                onTransform = { dx, dy, zoom, rot ->
                    viewModel.updatePoseTransform(dx, dy, zoom, rot)
                }
            )
        }

        // 2B. STOP MOTION ONION-SKIN LAST FRAME OVERLAY (semi-transparent guide over live preview)
        if (state.settings.shootingMode == ShootingMode.STOP_MO &&
            state.stopMotionState.onionSkinEnabled &&
            state.stopMotionState.lastFrameBitmap != null
        ) {
            Image(
                bitmap = state.stopMotionState.lastFrameBitmap!!.asImageBitmap(),
                contentDescription = "Stop Motion Onion Skin",
                contentScale = ContentScale.Crop,
                alpha = state.stopMotionState.onionSkinOpacity,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2C. LIVE SPLIT-SCREEN BEFORE/AFTER DRAG HANDLER
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

        // 3. VIEWFINDER OVERLAYS (Aspect Ratio Mask, Document Frame, Grid, Tap-to-Focus, Horizon)
        AspectRatioMaskOverlay(mask = state.settings.aspectRatioMask)
        if (state.settings.shootingMode == ShootingMode.SCAN_DOC) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val mx = size.width * 0.08f
                val my = size.height * 0.14f
                val w = size.width - mx * 2
                val h = size.height - my * 2.2f
                val corner = 26.dp.toPx()
                val stroke = 2.5.dp.toPx()
                val col = Color(0xFF34D399)
                // Top-left
                drawLine(col, Offset(mx, my), Offset(mx + corner, my), stroke)
                drawLine(col, Offset(mx, my), Offset(mx, my + corner), stroke)
                // Top-right
                drawLine(col, Offset(mx + w, my), Offset(mx + w - corner, my), stroke)
                drawLine(col, Offset(mx + w, my), Offset(mx + w, my + corner), stroke)
                // Bottom-left
                drawLine(col, Offset(mx, my + h), Offset(mx + corner, my + h), stroke)
                drawLine(col, Offset(mx, my + h), Offset(mx, my + h - corner), stroke)
                // Bottom-right
                drawLine(col, Offset(mx + w, my + h), Offset(mx + w - corner, my + h), stroke)
                drawLine(col, Offset(mx + w, my + h), Offset(mx + w, my + h - corner), stroke)
            }
        }
        CompositionGridOverlay(grid = state.settings.gridOverlay)
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

        // Shutter capture flash
        AnimatedVisibility(
            visible = state.showShutterFlash,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.55f)))
        }

        // 3. TOP TELEMETRY & QUICK TOOLS HUD (Simplified in Friend Mode)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (state.settings.friendMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HudPill(
                        text = "FRIEND MODE • ALIGN POSE & SHOOT",
                        accent = ZeroAiGreen,
                        filled = true,
                        onClick = { viewModel.toggleFriendMode() }
                    )
                    HudPill(
                        text = "EXIT",
                        accent = CanonAmber,
                        filled = false,
                        onClick = { viewModel.toggleFriendMode() }
                    )
                }
            } else {
                // Row 1: ZERO-AI Badge, Capture Format, True Light Auto, Canon Live Telemetry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudPill(
                            text = if (state.settings.zeroAiBypass) "ZERO AI" else "STD",
                            accent = if (state.settings.zeroAiBypass) ZeroAiGreen else Color.Gray,
                            filled = state.settings.zeroAiBypass,
                            onClick = { viewModel.toggleZeroAiBypass() }
                        )
                        HudPill(
                            text = state.settings.captureFormat.badge,
                            accent = CanonAmber,
                            filled = false,
                            onClick = { viewModel.cycleCaptureFormat() }
                        )
                        HudPill(
                            text = if (state.settings.trueLightAuto) {
                                state.lightAnalysis.scene.label
                            } else {
                                "MAN EV"
                            },
                            accent = if (state.settings.trueLightAuto) Color(0xFF60A5FA) else Color.Gray,
                            filled = state.settings.trueLightAuto,
                            onClick = { viewModel.toggleTrueLightAuto() }
                        )
                    }

                    // Live DSLR telemetry strip
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(0.75.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = state.settings.exposureMode.shortLabel,
                            color = CanonAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = state.telemetry.formattedAperture(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = state.telemetry.formattedShutter(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "ISO ${state.telemetry.iso}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 2: Live Histogram + Pro Overlay Toggles (Timer, Friend, Peak, Zebra, Grid)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    if (state.settings.histogram) {
                        LiveHistogramView(bins = state.histogram)
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OverlayIconButton(
                            icon = Icons.Default.Timer,
                            label = state.settings.timerDuration.label,
                            active = state.settings.timerDuration.seconds > 0,
                            activeColor = CanonAmber,
                            onClick = { viewModel.cycleTimerDuration() }
                        )
                        OverlayIconButton(
                            icon = Icons.Default.CenterFocusStrong,
                            label = "PEAK",
                            active = state.settings.focusPeaking,
                            activeColor = Color(0xFF00E5FF),
                            onClick = { viewModel.toggleFocusPeaking() }
                        )
                        OverlayIconButton(
                            icon = Icons.Default.WarningAmber,
                            label = "ZEBRA",
                            active = state.settings.zebras,
                            activeColor = CanonRed,
                            onClick = { viewModel.toggleZebras() }
                        )
                        OverlayIconButton(
                            icon = Icons.Default.GridOn,
                            label = state.settings.gridOverlay.label,
                            active = state.settings.gridOverlay != com.truelens.camera.camera.GridOverlay.NONE,
                            activeColor = CanonAmber,
                            onClick = { viewModel.cycleGridOverlay() }
                        )
                        OverlayIconButton(
                            icon = Icons.Default.AccessibilityNew,
                            label = "FRIEND",
                            active = state.settings.friendMode,
                            activeColor = ZeroAiGreen,
                            onClick = { viewModel.toggleFriendMode() }
                        )
                    }
                }
            }

            // Floating Pose Overlay Control Bar (Opacity, Ghost/Cutout/Outline, Mirror, Lock, Reset)
            if (state.poseOverlay.isActive) {
                Spacer(modifier = Modifier.height(8.dp))
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

            // Quick Tap-to-Focus Exposure Slider + AF/AE Lock bar (appears when user taps viewfinder)
            if (focusTapPos != null) {
                Spacer(modifier = Modifier.height(6.dp))
                val minEv = state.activeLens?.aeCompensationRange?.lower ?: -12
                val maxEv = state.activeLens?.aeCompensationRange?.upper ?: 12
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
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

            // Status toast / Live Original bypass badge
            if (state.bypassLiveGrade) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
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
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
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

        // 4. BOTTOM CONTROL AREA (Pro/Lightroom Deck + Lens Pills + Modes + Shutter Dock)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f), TrueBlack)
                    )
                )
                .navigationBarsPadding()
        ) {
            // Slide-up Pro Manual Deck or Lightroom Live Grading Deck
            AnimatedVisibility(
                visible = state.activeDeck == ActiveDeck.MANUAL,
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
                visible = state.activeDeck == ActiveDeck.LIGHTROOM,
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

            Spacer(modifier = Modifier.height(8.dp))

            // Lens selector pills (0.6x, 1x, 2x) + Dedicated 1-Tap Front ↔ Back Camera Flip
            if (state.lenses.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        state.lenses.forEach { lens ->
                            val selected = lens.cameraId == state.activeLens?.cameraId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (selected) CanonAmber else Color.Transparent)
                                    .clickable { viewModel.selectLens(lens) }
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (selected && state.zoomRatio > 1.05f) {
                                        String.format("%.1fx", state.zoomRatio)
                                    } else {
                                        lens.displayLabel
                                    },
                                    color = if (selected) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Dedicated 1-tap Front ↔ Back Camera Flip (keeps Pose Overlay active!)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .border(0.75.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                            .clickable { viewModel.flipFrontBackCamera() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Flip Front/Back Camera",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    if (!state.settings.friendMode && state.activeLens?.isFront == true) {
                        Spacer(modifier = Modifier.width(6.dp))
                        HudPill(
                            text = if (state.settings.mirrorFrontSave) "MIRROR" else "TRUE",
                            accent = if (state.settings.mirrorFrontSave) CanonAmber else ZeroAiGreen,
                            filled = state.settings.mirrorFrontSave,
                            onClick = { viewModel.toggleMirrorFrontSave() }
                        )
                    }

                    if (!state.settings.friendMode) {
                        Spacer(modifier = Modifier.width(8.dp))
                        // DSLR Aspect Ratio Mask Pill (FULL / 4:5 / 3:2 / 16:9 / XPAN)
                        HudPill(
                            text = state.settings.aspectRatioMask.label,
                            accent = if (state.settings.aspectRatioMask == com.truelens.camera.camera.AspectRatioMask.FULL) {
                                Color.White.copy(alpha = 0.75f)
                            } else {
                                CanonAmber
                            },
                            filled = state.settings.aspectRatioMask != com.truelens.camera.camera.AspectRatioMask.FULL,
                            onClick = { viewModel.cycleAspectRatioMask() }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Hands-Free Solo Burst Pill (1x / 3x / 5x)
                        HudPill(
                            text = state.settings.soloBurstCount.label,
                            accent = if (state.settings.soloBurstCount.count > 1) ZeroAiGreen else Color.White.copy(alpha = 0.75f),
                            filled = state.settings.soloBurstCount.count > 1,
                            onClick = { viewModel.cycleSoloBurstCount() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                            color = CanonRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            text = if (state.settings.shootingMode == ShootingMode.SLOW_MO) {
                                "SLOW-MO • 120fps / 4×"
                            } else {
                                "VIDEO • 1080p MP4"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
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
                                text = if (sm.onionSkinEnabled) "ONION ON" else "ONION OFF",
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
                            text = "PANO • ${state.panoramaState.frameCount} FRAMES • ${state.panoramaState.accumulatedYawDeg.toInt()}°",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
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

            // Shooting Mode Strip (PHOTO, VIDEO, SLOW-MO, MACRO, PANO, TIMELAPSE, SCAN)
            if (!state.settings.friendMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShootingMode.entries.forEach { mode ->
                        val selected = state.settings.shootingMode == mode
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { viewModel.setShootingMode(mode) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = mode.label,
                                color = if (selected) CanonAmber else Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                            )
                            if (selected) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(CanonAmber)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main Tactile Shutter Dock
            val isRecordingMode = state.isVideoRecording || state.panoramaState.isCapturing || state.timeLapseState.isRunning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Full In-App Gallery Thumbnail
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1C22))
                        .border(
                            1.25.dp,
                            if (state.lastThumbnail != null) Color.White.copy(alpha = 0.7f) else Color(0x33FFFFFF),
                            RoundedCornerShape(12.dp)
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
                    } else {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (!state.settings.friendMode) {
                    DeckToggleButton(
                        icon = Icons.Default.Tune,
                        label = "PRO",
                        active = state.activeDeck == ActiveDeck.MANUAL,
                        onClick = { viewModel.setActiveDeck(ActiveDeck.MANUAL) }
                    )
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Mechanical Shutter Button (turns Red Stop Square during Pano/TimeLapse capture)
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.dp, if (isRecordingMode) CanonRed else Color.White, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isRecordingMode -> CanonRed
                                state.settings.zeroAiBypass -> Color.White
                                else -> CanonAmber
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
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color.White)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color.Black.copy(alpha = 0.18f), CircleShape)
                        )
                    }
                }

                if (!state.settings.friendMode) {
                    DeckToggleButton(
                        icon = Icons.Default.Palette,
                        label = if (state.grade.isNeutral) "GRADE" else "GRADED",
                        active = state.activeDeck == ActiveDeck.LIGHTROOM || !state.grade.isNeutral,
                        onClick = { viewModel.setActiveDeck(ActiveDeck.LIGHTROOM) }
                    )
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Pose Inspos & Overlay Library Toggle
                DeckToggleButton(
                    icon = Icons.Default.AccessibilityNew,
                    label = if (state.poseOverlay.isActive) "POSE ON" else "POSE",
                    active = state.poseOverlay.isActive,
                    onClick = { viewModel.openPoseInspos() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
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
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) CanonAmber.copy(alpha = 0.22f) else Color(0x18FFFFFF))
            .border(
                1.dp,
                if (active) CanonAmber else Color.White.copy(alpha = 0.16f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) CanonAmber else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = if (active) CanonAmber else Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
