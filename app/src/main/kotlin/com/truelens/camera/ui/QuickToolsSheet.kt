package com.truelens.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RawOn
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truelens.camera.camera.AspectRatioMask
import com.truelens.camera.camera.EnhanceMode
import com.truelens.camera.camera.GridOverlay
import com.truelens.camera.camera.HandsFreeMode
import com.truelens.camera.camera.ManualSettings
import com.truelens.camera.camera.NightModeDuration
import com.truelens.camera.grading.DateStampStyle

/**
 * Translucent 4-column Camera Tools grid (4 balanced rows, 16 clean options).
 * Pure Zero-AI optics is ALWAYS default; optional Clean Enhance gives gentle denoise & clarity!
 */
@Composable
fun QuickToolsSheet(
    settings: ManualSettings,
    lightingSceneLabel: String,
    onCycleFormat: () -> Unit,
    onCycleTimer: () -> Unit,
    onCycleRatio: () -> Unit,
    onCycleGrid: () -> Unit,
    onCycleBurst: () -> Unit,
    onToggleTrueLightAuto: () -> Unit,
    onToggleMirrorFront: () -> Unit,
    onToggleLevel: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleFocusEdges: () -> Unit,
    onCycleDateStamp: () -> Unit,
    onCycleHandsFree: () -> Unit,
    onToggleEnhanceMode: () -> Unit,
    onToggleLivePhoto: () -> Unit,
    onCycleNightMode: () -> Unit,
    onToggleQrScanner: () -> Unit,
    onToggleLiveText: () -> Unit,
    onToggleShutterSound: () -> Unit = {},
    onToggleFloatingShutter: () -> Unit = {},
    onToggleAudioMeter: () -> Unit = {},
    onToggleAutoNightPrompt: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 450.dp)
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(TrueLensPalette.GlassPanel)
            .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(22.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // Row 1: Clean Enhance, Live Photo, Night Stack, Format
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GlassGridTile(
                icon = Icons.Default.AutoAwesome,
                label = "Clean Look",
                valueText = settings.enhanceMode.label,
                active = settings.enhanceMode == EnhanceMode.CLEAN,
                onClick = onToggleEnhanceMode,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.MotionPhotosOn,
                label = "Live Photo",
                valueText = if (settings.livePhotoEnabled) "On" else "Off",
                active = settings.livePhotoEnabled,
                onClick = onToggleLivePhoto,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.NightsStay,
                label = "Night",
                valueText = settings.nightModeDuration.label,
                active = settings.nightModeDuration != NightModeDuration.OFF,
                onClick = onCycleNightMode,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.RawOn,
                label = "Format",
                valueText = settings.captureFormat.badge,
                active = true,
                onClick = onCycleFormat,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 2: Ratio, Timer, Grid, Level (Horizon + Flat-Lay)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GlassGridTile(
                icon = Icons.Default.AspectRatio,
                label = "Ratio",
                valueText = settings.aspectRatioMask.label,
                active = settings.aspectRatioMask != AspectRatioMask.FULL,
                onClick = onCycleRatio,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.Timer,
                label = "Timer",
                valueText = settings.timerDuration.label,
                active = settings.timerDuration.seconds > 0,
                onClick = onCycleTimer,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.GridOn,
                label = "Grid",
                valueText = settings.gridOverlay.label,
                active = settings.gridOverlay != GridOverlay.NONE,
                onClick = onCycleGrid,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.Straighten,
                label = "Level",
                valueText = if (settings.levelGauge) "On" else "Off",
                active = settings.levelGauge,
                onClick = onToggleLevel,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 3: QR Scan, Live Text, Hands-Free, Date Stamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GlassGridTile(
                icon = Icons.Default.QrCodeScanner,
                label = "QR Scan",
                valueText = if (settings.qrScannerEnabled) "Auto" else "Off",
                active = settings.qrScannerEnabled,
                onClick = onToggleQrScanner,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.TextFields,
                label = "Live Text",
                valueText = if (settings.liveTextEnabled) "Auto" else "Off",
                active = settings.liveTextEnabled,
                onClick = onToggleLiveText,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.PanTool,
                label = "Hands-Free",
                valueText = settings.handsFreeMode.label,
                active = settings.handsFreeMode != HandsFreeMode.OFF,
                onClick = onCycleHandsFree,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.CalendarToday,
                label = "Date Stamp",
                valueText = settings.dateStampStyle.label,
                active = settings.dateStampStyle != DateStampStyle.OFF,
                onClick = onCycleDateStamp,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 4: Burst, Selfie Mirror, Auto Light, Graph / Focus Edge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GlassGridTile(
                icon = Icons.Default.BurstMode,
                label = "Burst",
                valueText = if (settings.soloBurstCount.count == 1) "1×" else "${settings.soloBurstCount.count}×",
                active = settings.soloBurstCount.count > 1,
                onClick = onCycleBurst,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.Flip,
                label = "Selfie",
                valueText = if (settings.mirrorFrontSave) "Mirror" else "True",
                active = settings.mirrorFrontSave,
                onClick = onToggleMirrorFront,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.WbSunny,
                label = "Auto Light",
                valueText = if (settings.trueLightAuto) lightingSceneLabel else "Off",
                active = settings.trueLightAuto,
                onClick = onToggleTrueLightAuto,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.CenterFocusStrong,
                label = "Focus Edge",
                valueText = if (settings.focusPeaking) "On" else if (settings.histogram) "Graph" else "Off",
                active = settings.focusPeaking || settings.histogram,
                onClick = onToggleFocusEdges,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 5: Shutter Sound, Floating Shutter, Audio dB Meter, Auto Night Light Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GlassGridTile(
                icon = if (settings.shutterSoundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                label = "Shutter",
                valueText = if (settings.shutterSoundEnabled) "Sound" else "Silent",
                active = settings.shutterSoundEnabled,
                onClick = onToggleShutterSound,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.RadioButtonChecked,
                label = "Float Btn",
                valueText = if (settings.floatingShutterEnabled) "On" else "Off",
                active = settings.floatingShutterEnabled,
                onClick = onToggleFloatingShutter,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.GraphicEq,
                label = "dB Meter",
                valueText = if (settings.audioMeterEnabled) "On" else "Off",
                active = settings.audioMeterEnabled,
                onClick = onToggleAudioMeter,
                modifier = Modifier.weight(1f)
            )
            GlassGridTile(
                icon = Icons.Default.NightsStay,
                label = "Auto Night",
                valueText = if (settings.autoNightPromptEnabled) "On" else "Off",
                active = settings.autoNightPromptEnabled,
                onClick = onToggleAutoNightPrompt,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
