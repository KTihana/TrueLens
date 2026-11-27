package com.truelens.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truelens.camera.camera.AstroStyle
import com.truelens.camera.camera.DocumentScanStyle
import com.truelens.camera.camera.DoubleExpBlendMode
import com.truelens.camera.camera.ShootingMode
import com.truelens.camera.camera.SkyPreset
import com.truelens.camera.camera.StabilizerMode
import com.truelens.camera.modes.LongExposureState
import com.truelens.camera.modes.LongExposureStyle
import com.truelens.camera.modes.StopMotionState
import com.truelens.camera.modes.TimeLapseState
import kotlin.math.roundToInt

private val ModeAmber = TrueLensPalette.AccentViolet
private val ModeRed = TrueLensPalette.RecordCoral
private val ModeGreen = TrueLensPalette.AccentViolet

@Composable
internal fun CameraModeControlBars(
    state: CameraUiState,
    viewModel: CameraViewModel,
    isVideoOrSlowMo: Boolean,
    astroRed: Boolean,
    hudAccent: Color
) {
            // Portrait / Cinematic Adjustable Depth Bokeh Aperture Slider (f/1.4 - f/16)
            if (!state.settings.friendMode &&
                (state.settings.shootingMode == ShootingMode.PORTRAIT ||
                    (state.settings.shootingMode == ShootingMode.CINEMATIC && !state.isVideoRecording))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TrueLensPalette.GlassPanel)
                        .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "f/%.1f", state.settings.portraitApertureF),
                        color = TrueLensPalette.AccentViolet,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(42.dp)
                    )
                    Slider(
                        value = state.settings.portraitApertureF,
                        onValueChange = { viewModel.setPortraitAperture(it) },
                        valueRange = 1.4f..16.0f,
                        modifier = Modifier.weight(1f).height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = TrueLensPalette.AccentViolet,
                            inactiveTrackColor = Color(0x33FFFFFF)
                        )
                    )
                    Text(
                        text = if (state.settings.shootingMode == ShootingMode.CINEMATIC) "Tap to rack" else "Blur",
                        color = TrueLensPalette.MutedGray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            // Video, Slow-Mo & Cinematic Control Bar (Quality, Audio Mode, Live dB Meter, WB Lock, 180°, VHS '94, Music Keep, Super Steady)
            if (!state.settings.friendMode && isVideoOrSlowMo) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0E1014))
                        .border(
                            0.75.dp,
                            when {
                                state.isVideoPaused -> Color(0xFFFFB300)
                                state.isVideoRecording -> ModeRed
                                else -> Color.White.copy(alpha = 0.18f)
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Toggleable Live Audio Level Meter (dB Bars — visible before & during recording!)
                    if (state.settings.audioMeterEnabled) {
                        LiveAudioLevelMeterRow(
                            level01 = state.audioLevel01,
                            peakDb = state.audioPeakDb,
                            muted = state.settings.micMuted
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.isVideoRecording) {
                            val mm = state.videoElapsedSec / 60
                            val ss = state.videoElapsedSec % 60
                            val prefix = if (state.isVideoPaused) "⏸ PAUSED" else "● REC"
                            Text(
                                text = String.format(java.util.Locale.US, "%s  %02d:%02d  •  %s", prefix, mm, ss, state.videoFpsBadge),
                                color = if (state.isVideoPaused) Color(0xFFFFB300) else TrueLensPalette.RecordCoral,
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
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x22FFFFFF))
                                        .clickable { viewModel.cycleVideoQuality() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = state.settings.videoQuality.label,
                                        color = Color.White,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when (state.settings.videoAudioMode) {
                                                com.truelens.camera.camera.VideoAudioMode.MUTED -> TrueLensPalette.RecordCoral.copy(alpha = 0.22f)
                                                com.truelens.camera.camera.VideoAudioMode.WIND_CUT -> TrueLensPalette.AccentVioletSoft
                                                else -> Color(0x22FFFFFF)
                                            }
                                        )
                                        .clickable { viewModel.cycleVideoAudioMode() }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (state.settings.videoAudioMode == com.truelens.camera.camera.VideoAudioMode.MUTED) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = "Audio Mode",
                                        tint = if (state.settings.videoAudioMode == com.truelens.camera.camera.VideoAudioMode.MUTED) TrueLensPalette.RecordCoral else Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = state.settings.videoAudioMode.label,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (state.settings.audioMeterEnabled) TrueLensPalette.AccentVioletSoft else Color(0x22FFFFFF))
                                        .clickable { viewModel.toggleAudioMeter() }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "dB",
                                        color = if (state.settings.audioMeterEnabled) TrueLensPalette.AccentViolet else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (state.grade.vhsRetro > 0.01f) Color(0x44FF7024) else Color(0x22FFFFFF))
                                        .border(
                                            0.75.dp,
                                            if (state.grade.vhsRetro > 0.01f) Color(0xFFFF7024) else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { viewModel.toggleVhsRetroQuick() }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "VHS",
                                        color = if (state.grade.vhsRetro > 0.01f) Color(0xFFFF9F43) else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (state.settings.videoWbLocked) TrueLensPalette.AccentVioletSoft else Color(0x22FFFFFF))
                                        .border(
                                            0.75.dp,
                                            if (state.settings.videoWbLocked) TrueLensPalette.AccentViolet else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { viewModel.toggleVideoWbLock() }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (state.settings.videoWbLocked) "WB Lock" else "WB",
                                        color = if (state.settings.videoWbLocked) TrueLensPalette.AccentViolet else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (state.settings.cinema180Shutter) TrueLensPalette.AccentVioletSoft else Color(0x22FFFFFF))
                                        .clickable { viewModel.toggleCinema180Shutter() }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "180°",
                                        color = if (state.settings.cinema180Shutter) TrueLensPalette.AccentViolet else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (state.settings.keepMusicPlaying) TrueLensPalette.AccentVioletSoft else Color(0x22FFFFFF))
                                        .clickable { viewModel.toggleKeepMusicPlaying() }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = "Keep Music Playing",
                                        tint = if (state.settings.keepMusicPlaying) TrueLensPalette.AccentViolet else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        HudPill(
                            text = state.settings.stabilizerMode.label,
                            accent = when (state.settings.stabilizerMode) {
                                StabilizerMode.SUPER_STEADY -> ModeGreen
                                StabilizerMode.STANDARD -> ModeAmber
                                StabilizerMode.OFF -> Color.Gray
                            },
                            filled = state.settings.stabilizerMode != StabilizerMode.OFF,
                            onClick = { viewModel.cycleStabilizerMode() }
                        )
                    }
                }
            }

            // ASTROPHOTOGRAPHY / SKY MODE BAR (Sky Presets, 15s-60s, Pinpoint/Trails, ∞ Focus, 4× Star Loupe, LP Clean, Hot Px, Red UI!)
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.ASTRO) {
                val astroBorder = if (astroRed) Color(0xFFDC2626).copy(alpha = 0.7f) else TrueLensPalette.AccentViolet.copy(alpha = 0.6f)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (astroRed) Color(0xDD1A0505) else Color(0xCC0D0F16))
                        .border(0.75.dp, astroBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Row 1: Stability + Red Night-Vision UI + 1-Tap Sky Presets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (state.isPhoneSteady) Color(0x3322C55E) else Color(0x33F59E0B)
                                )
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (state.isPhoneSteady) "● Steady" else "● Shake",
                                color = if (state.isPhoneSteady) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (astroRed) Color(0xFFDC2626) else Color(0x28DC2626))
                                .border(0.75.dp, Color(0xFFDC2626).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .clickable { viewModel.toggleAstroRedUi() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Red UI",
                                color = if (astroRed) Color.White else Color(0xFFFCA5A5),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        SkyPreset.entries.forEach { preset ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (astroRed) Color(0x33DC2626) else Color(0x22FFFFFF))
                                    .clickable { viewModel.applySkyPreset(preset) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = preset.label,
                                    color = if (astroRed) Color(0xFFFCA5A5) else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Row 2: Duration (15s/30s/60s), Style, ∞ Focus, 4× Star Loupe, LP Clean, Hot Px
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(15, 30, 60).forEach { sec ->
                            val sel = state.settings.astroDurationSec == sec
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (sel) hudAccent else Color(0x1EFFFFFF))
                                    .clickable { viewModel.setAstroDuration(sec) }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${sec}s",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(hudAccent.copy(alpha = 0.28f))
                                .clickable { viewModel.toggleAstroStyle() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (state.settings.astroStyle == AstroStyle.PINPOINT_STARS) "Stars" else "Trails",
                                color = if (astroRed) Color(0xFFFCA5A5) else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        val infLocked = state.settings.focusDistanceDiopters == 0f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (infLocked) hudAccent else Color(0x1EFFFFFF))
                                .clickable { viewModel.lockInfinityFocus() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text("∞", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (state.settings.astroStarLoupeEnabled) hudAccent else Color(0x1EFFFFFF))
                                .clickable { viewModel.toggleAstroStarLoupe() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text("4× Loupe", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (state.settings.astroLightPollutionClean) hudAccent else Color(0x1EFFFFFF))
                                .clickable { viewModel.toggleAstroLightPollution() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text("LP Clean", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (state.settings.astroHotPixelClean) hudAccent else Color(0x1EFFFFFF))
                                .clickable { viewModel.toggleAstroHotPixels() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text("Hot Px", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // DOUBLE EXPOSURE MODE BAR (Blend Modes + Shot 1/2 Status + Retake #1)
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.DOUBLE_EXP) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TrueLensPalette.GlassPanel)
                        .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.doubleExpFirstShot == null) "Shot 1/2" else "Shot 2/2",
                            color = TrueLensPalette.AccentViolet,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        DoubleExpBlendMode.entries.forEach { m ->
                            val sel = state.settings.doubleExpBlend == m
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) TrueLensPalette.AccentViolet else Color(0x1EFFFFFF))
                                    .clickable { viewModel.setDoubleExpBlend(m) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(m.label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (state.doubleExpFirstShot != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrueLensPalette.RecordCoral.copy(alpha = 0.28f))
                                .clickable { viewModel.retakeDoubleExposureFirst() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Retake #1", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // QUICK MOTION / ACTION PAN MODE BAR (Motion Streak Strength Slider)
            if (!state.settings.friendMode && state.settings.shootingMode == ShootingMode.ACTION_PAN) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TrueLensPalette.GlassPanel)
                        .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Action Pan",
                        color = TrueLensPalette.AccentViolet,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = state.settings.actionPanStrength,
                        onValueChange = { viewModel.setActionPanStrength(it) },
                        valueRange = 0.2f..1.0f,
                        modifier = Modifier.weight(1f).height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = TrueLensPalette.AccentViolet,
                            inactiveTrackColor = Color(0x33FFFFFF)
                        )
                    )
                    Text(
                        text = "${(state.settings.actionPanStrength * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(34.dp)
                    )
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
                        .border(0.75.dp, ModeAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    if (le.isCapturing) {
                        Text(
                            text = "EXPOSING ${le.durationSec}s (${(le.progress * 100).toInt()}%) • HOLD STEADY",
                            color = ModeAmber,
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
                                            .background(if (sel) ModeAmber else Color(0x1EFFFFFF))
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
                        accent = if (state.settings.macroFocusStackEnabled) ModeGreen else Color.White,
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
                        .border(0.75.dp, ModeAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    if (sm.isExporting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = ModeAmber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "EXPORTING STOP-MOTION MP4 (${sm.frameCount} FRAMES)...",
                                color = ModeAmber,
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
                                accent = if (sm.onionSkinEnabled) ModeAmber else Color.Gray,
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
                                    activeTrackColor = ModeAmber,
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
                                text = String.format(java.util.Locale.US, "%df • %.1fs", sm.frameCount, sm.durationSec),
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
                                        .background(if (sm.frameCount >= 2) ModeGreen else Color(0x18FFFFFF))
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
                        .border(0.75.dp, ModeGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isScanningDocument) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = ModeGreen,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ENHANCING & SAVING PDF...",
                                color = ModeGreen,
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
                                        .background(if (sel) ModeGreen else Color(0x1EFFFFFF))
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
                        .border(0.75.dp, ModeAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.panoramaState.isStitching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = ModeAmber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "STITCHING PANORAMA...",
                                color = ModeAmber,
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
                                .background(ModeAmber)
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
                        .border(0.75.dp, if (tl.isRunning) ModeRed else Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (tl.isRunning) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "REC • ${tl.formattedElapsed()}",
                                color = ModeRed,
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
                                            .background(if (sel) ModeAmber else Color(0x1EFFFFFF))
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

}
