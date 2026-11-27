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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truelens.camera.camera.ExposureMode
import com.truelens.camera.camera.LensInfo
import com.truelens.camera.camera.ManualSettings
import com.truelens.camera.grading.BuiltInPresets
import com.truelens.camera.grading.GradePreset
import com.truelens.camera.grading.LightroomGrade
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

private enum class ProControlTool(val label: String, val icon: ImageVector) {
    EV("Exposure", Icons.Default.Exposure),
    SHUTTER("Shutter", Icons.Default.ShutterSpeed),
    ISO("ISO", Icons.Default.Iso),
    FOCUS("Focus", Icons.Default.CenterFocusWeak),
    WB("White Bal", Icons.Default.Thermostat)
}

/**
 * Modern Canon-inspired Pro Manual Deck using a single PrecisionTickRuler
 * + horizontal icon tool bar (no stacked sliders!).
 */
@Composable
fun CanonManualDeck(
    lens: LensInfo?,
    settings: ManualSettings,
    savedCanonSlots: Set<String> = emptySet(),
    onExposureMode: (ExposureMode) -> Unit,
    onIso: (Int?) -> Unit,
    onShutterNs: (Long?) -> Unit,
    onEvSteps: (Int) -> Unit,
    onFocusDiopters: (Float?) -> Unit,
    onWhiteBalanceKelvin: (Int?) -> Unit,
    onCycleAeb: () -> Unit = {},
    onLoadCanonSlot: (String) -> Unit = {},
    onSaveCanonSlot: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTool by remember { mutableStateOf(ProControlTool.ISO) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(TrueLensPalette.GlassPanel)
            .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Row 1: Compact Mode Pills (Auto / Tv / M) + Canon C1-C3 + AEB
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExposureMode.entries.forEach { mode ->
                val sel = settings.exposureMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Color.White else Color(0x18FFFFFF))
                        .clickable { onExposureMode(mode) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = mode.shortLabel,
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            listOf("C1", "C2", "C3").forEach { slot ->
                val saved = savedCanonSlots.contains(slot)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (saved) TrueLensPalette.AccentVioletSoft else Color(0x16FFFFFF))
                        .clickable { onLoadCanonSlot(slot) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (saved) slot else "$slot+",
                        color = if (saved) TrueLensPalette.AccentViolet else TrueLensPalette.MutedGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (settings.aebMode.evOffsetSteps > 0) TrueLensPalette.AccentViolet else Color(0x16FFFFFF))
                    .clickable { onCycleAeb() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = settings.aebMode.label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Row 2: Single Precision Tick-Ruler for the currently active Pro control
        when (selectedTool) {
            ProControlTool.EV -> {
                val minEv = lens?.aeCompensationRange?.lower ?: -12
                val maxEv = lens?.aeCompensationRange?.upper ?: 12
                val step = lens?.aeCompensationStep?.toFloat() ?: (1f / 3f)
                val evVal = settings.evCompensationSteps * step
                PrecisionTickRuler(
                    value = settings.evCompensationSteps.toFloat(),
                    valueRange = minEv.toFloat()..maxEv.toFloat(),
                    valueLabel = String.format(Locale.US, "%+.1f EV", evVal),
                    defaultValue = 0f,
                    onValueChange = { onEvSteps(it.toInt()) }
                )
            }
            ProControlTool.SHUTTER -> {
                val minNs = (lens?.exposureTimeRangeNs?.lower ?: 100_000L).coerceAtLeast(100_000L)
                val maxNs = (lens?.exposureTimeRangeNs?.upper ?: 1_000_000_000L).coerceAtMost(2_000_000_000L)
                val logMin = ln(minNs.toDouble()).toFloat()
                val logMax = ln(maxNs.toDouble()).toFloat()
                val curNs = settings.shutterSpeedNs ?: 16_666_666L
                val curLog = ln(curNs.coerceIn(minNs, maxNs).toDouble()).toFloat()
                PrecisionTickRuler(
                    value = curLog,
                    valueRange = logMin..logMax,
                    valueLabel = if (settings.shutterSpeedNs == null) "Auto Shutter" else formatShutterNs(curNs),
                    defaultValue = ln(16_666_666.0).toFloat(),
                    onValueChange = { v -> onShutterNs(exp(v.toDouble()).toLong()) }
                )
            }
            ProControlTool.ISO -> {
                val minIso = lens?.isoRange?.lower ?: 50
                val maxIso = (lens?.isoRange?.upper ?: 3200).coerceAtMost(6400)
                val curIso = settings.iso ?: 200
                PrecisionTickRuler(
                    value = curIso.coerceIn(minIso, maxIso).toFloat(),
                    valueRange = minIso.toFloat()..maxIso.toFloat(),
                    valueLabel = if (settings.iso == null) "Auto ISO" else "ISO $curIso",
                    defaultValue = minIso.toFloat(),
                    onValueChange = { onIso(it.toInt()) }
                )
            }
            ProControlTool.FOCUS -> {
                val maxFocus = (lens?.minFocusDistanceDiopters ?: 10f).coerceAtLeast(1f)
                val curFocus = settings.focusDistanceDiopters ?: 0f
                val focusText = when {
                    settings.focusDistanceDiopters == null -> "Auto Focus"
                    curFocus < 0.1f -> "Infinity ∞"
                    else -> String.format(Locale.US, "%.2fm", 1f / curFocus)
                }
                PrecisionTickRuler(
                    value = curFocus.coerceIn(0f, maxFocus),
                    valueRange = 0f..maxFocus,
                    valueLabel = focusText,
                    defaultValue = 0f,
                    onValueChange = { onFocusDiopters(it) }
                )
            }
            ProControlTool.WB -> {
                val curKelvin = settings.whiteBalanceKelvin ?: 5500
                PrecisionTickRuler(
                    value = curKelvin.toFloat(),
                    valueRange = 2500f..9500f,
                    valueLabel = if (settings.whiteBalanceKelvin == null) "Auto WB" else "${curKelvin}K",
                    defaultValue = 5500f,
                    onValueChange = { onWhiteBalanceKelvin((it.toInt() / 50) * 50) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 3: Horizontal Pro Tool Icon Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ProControlTool.entries.forEach { tool ->
                val adjusted = when (tool) {
                    ProControlTool.EV -> settings.evCompensationSteps != 0
                    ProControlTool.SHUTTER -> settings.shutterSpeedNs != null
                    ProControlTool.ISO -> settings.iso != null
                    ProControlTool.FOCUS -> settings.focusDistanceDiopters != null
                    ProControlTool.WB -> settings.whiteBalanceKelvin != null
                }
                val badge = when (tool) {
                    ProControlTool.EV -> String.format(Locale.US, "%+.1f", settings.evCompensationSteps / 3f)
                    ProControlTool.SHUTTER -> settings.shutterSpeedNs?.let { formatShutterNs(it) }
                    ProControlTool.ISO -> settings.iso?.toString()
                    ProControlTool.FOCUS -> settings.focusDistanceDiopters?.let { if (it < 0.1f) "∞" else String.format(Locale.US, "%.1fm", 1f / it) }
                    ProControlTool.WB -> settings.whiteBalanceKelvin?.let { "${it}K" }
                }
                ToolCarouselItem(
                    icon = tool.icon,
                    label = tool.label,
                    valueBadge = badge,
                    selected = selectedTool == tool,
                    hasAdjustment = adjusted,
                    onClick = {
                        if (selectedTool == tool && adjusted) {
                            // Second tap resets to Auto cleanly
                            when (tool) {
                                ProControlTool.EV -> onEvSteps(0)
                                ProControlTool.SHUTTER -> onShutterNs(null)
                                ProControlTool.ISO -> onIso(null)
                                ProControlTool.FOCUS -> onFocusDiopters(null)
                                ProControlTool.WB -> onWhiteBalanceKelvin(null)
                            }
                        } else {
                            selectedTool = tool
                        }
                    }
                )
            }
        }
    }
}

enum class GradeTool(
    val label: String,
    val icon: ImageVector,
    val range: ClosedFloatingPointRange<Float>,
    val isEv: Boolean = false
) {
    EXPOSURE("Exposure", Icons.Default.Brightness6, -2.5f..2.5f, isEv = true),
    CONTRAST("Contrast", Icons.Default.Contrast, -1f..1f),
    HIGHLIGHTS("Highlights", Icons.Default.WbSunny, -1f..1f),
    SHADOWS("Shadows", Icons.Default.Tonality, -1f..1f),
    WHITES("Whites", Icons.Default.Brightness6, -1f..1f),
    BLACKS("Blacks", Icons.Default.Tonality, -1f..1f),
    TEMP("Warmth", Icons.Default.Thermostat, -1f..1f),
    TINT("Tint", Icons.Default.ColorLens, -1f..1f),
    VIBRANCE("Vibrance", Icons.Default.AutoAwesome, -1f..1f),
    SATURATION("Saturation", Icons.Default.FilterBAndW, -1f..1f),
    CLARITY("Clarity", Icons.Default.Tune, -1f..1f),
    SKIN("Skin Glow", Icons.Default.WbSunny, -1f..1f),
    REDS("Reds", Icons.Default.ColorLens, -1f..1f),
    GREENS("Greens", Icons.Default.ColorLens, -1f..1f),
    BLUES("Blues", Icons.Default.ColorLens, -1f..1f),
    SKY("Sky Depth", Icons.Default.BlurOn, -1f..1f),
    CURVE_SHADOWS("Curve Dark", Icons.Default.Tonality, -1f..1f),
    CURVE_MID("Curve Mid", Icons.Default.Contrast, -1f..1f),
    CURVE_HIGH("Curve Light", Icons.Default.WbSunny, -1f..1f),
    VIGNETTE("Vignette", Icons.Default.Vignette, 0f..1f),
    GRAIN("Grain", Icons.Default.Grain, 0f..1f),
    HALATION("Halation", Icons.Default.WbSunny, 0f..1f),
    LIGHT_LEAK("Light Leak", Icons.Default.AutoAwesome, 0f..1f),
    VHS_RETRO("VHS '94", Icons.Default.BlurOn, 0f..1f)
}

/**
 * Sleek Lightroom-style Editor Deck matching Reference Image #1 Left & Image #2:
 * Toggle cleanly between "Presets" (curated aesthetic looks) and "Adjust" (single tick-ruler + icon row).
 */
@Composable
fun LightroomLiveDeck(
    grade: LightroomGrade,
    activePresetId: String,
    customPresets: List<GradePreset> = emptyList(),
    activeLutName: String? = null,
    splitScreenEnabled: Boolean = false,
    showModeTabs: Boolean = true,
    onToggleSplitScreen: (() -> Unit)? = null,
    onImportCubeLut: (() -> Unit)? = null,
    onClearCubeLut: (() -> Unit)? = null,
    onUpdateGrade: ((LightroomGrade) -> LightroomGrade) -> Unit,
    onSelectPreset: (GradePreset) -> Unit,
    onSaveCustomPreset: ((String) -> Unit)? = null,
    onDeleteCustomPreset: ((String) -> Unit)? = null,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var modeTab by remember { mutableStateOf(0) } // 0 = Presets, 1 = Adjust (Tick Ruler)
    var selectedTool by remember { mutableStateOf(GradeTool.EXPOSURE) }
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(TrueLensPalette.GlassPanel)
            .border(0.75.dp, TrueLensPalette.GlassBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Header row: Presets / Adjust tabs (optional) + Split / +LUT / +Save / Reset
        if (showModeTabs || !grade.isNeutral || onToggleSplitScreen != null || onImportCubeLut != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showModeTabs) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf("Presets", "Adjust").forEachIndexed { idx, title ->
                            val active = modeTab == idx
                            Text(
                                text = title,
                                color = if (active) Color.White else TrueLensPalette.MutedGray,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.clickable { modeTab = idx }
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onToggleSplitScreen != null) {
                    Text(
                        text = "Split",
                        color = if (splitScreenEnabled) TrueLensPalette.AccentViolet else TrueLensPalette.MutedGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onToggleSplitScreen() }
                    )
                }
                if (onImportCubeLut != null) {
                    Text(
                        text = activeLutName ?: "+LUT",
                        color = if (activeLutName != null) TrueLensPalette.AccentViolet else TrueLensPalette.MutedGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            if (activeLutName != null && onClearCubeLut != null) onClearCubeLut()
                            else onImportCubeLut()
                        }
                    )
                }
                if (!grade.isNeutral && onSaveCustomPreset != null) {
                    Text(
                        text = "Save",
                        color = TrueLensPalette.AccentViolet,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { showSaveDialog = true }
                    )
                }
                if (!grade.isNeutral) {
                    Text(
                        text = "Reset",
                        color = TrueLensPalette.RecordCoral,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onReset() }
                    )
                }
            }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (!showModeTabs || modeTab == 1) {
            // Multiple Light Leak Style Selector Row (5 unique analog leak styles when Light Leak is active!)
            if (selectedTool == GradeTool.LIGHT_LEAK) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LightroomGrade.LIGHT_LEAK_LABELS.forEachIndexed { styleIdx, styleName ->
                        val sel = grade.lightLeakStyle == styleIdx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Color(0xFFFF6B35) else Color(0x1EFFFFFF))
                                .clickable {
                                    onUpdateGrade { g ->
                                        g.copy(
                                            lightLeakStyle = styleIdx,
                                            lightLeakIntensity = if (styleIdx == 0) 0f else g.lightLeakIntensity.coerceAtLeast(0.55f)
                                        )
                                    }
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = styleName,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Single Precision Tick-Ruler for active adjustment tool
            val curVal = getGradeValue(grade, selectedTool)
            val labelText = if (selectedTool.isEv) {
                String.format(Locale.US, "%+.2f EV", curVal)
            } else {
                String.format(Locale.US, "%+d", (curVal * 100).toInt())
            }
            PrecisionTickRuler(
                value = curVal,
                valueRange = selectedTool.range,
                valueLabel = "${selectedTool.label}  $labelText",
                defaultValue = 0f,
                onValueChange = { v -> onUpdateGrade { setGradeValue(it, selectedTool, v) } }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Horizontal Icon Tool Carousel (matching Image #1 Left)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                GradeTool.entries.forEach { tool ->
                    val v = getGradeValue(grade, tool)
                    val changed = abs(v) > 0.01f
                    val badge = if (tool.isEv) String.format(Locale.US, "%+.1f", v) else "${(v * 100).toInt()}"
                    ToolCarouselItem(
                        icon = tool.icon,
                        label = tool.label,
                        valueBadge = badge,
                        selected = selectedTool == tool,
                        hasAdjustment = changed,
                        onClick = { selectedTool = tool }
                    )
                }
            }
        } else {
            // Clean Aesthetic Preset Cards
            val allPresets = customPresets + BuiltInPresets.ALL
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                allPresets.forEach { preset ->
                    val selected = preset.id == activePresetId
                    Column(
                        modifier = Modifier
                            .width(120.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) TrueLensPalette.AccentVioletSoft else Color(0x16FFFFFF))
                            .border(
                                if (selected) 1.5.dp else 0.75.dp,
                                if (selected) Color.White else TrueLensPalette.GlassBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectPreset(preset) }
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = preset.category,
                                color = TrueLensPalette.AccentViolet,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (preset.isCustom && onDeleteCustomPreset != null) {
                                Text(
                                    text = "×",
                                    color = TrueLensPalette.MutedGray,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable { onDeleteCustomPreset(preset.id) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = preset.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = preset.subtitle,
                            color = TrueLensPalette.MutedGray,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }

    if (showSaveDialog && onSaveCustomPreset != null) {
        var recipeName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = TrueLensPalette.SurfaceDark,
            title = { Text("Save Preset", color = Color.White, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    placeholder = { Text("e.g. Portofino, Paris Rain", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TrueLensPalette.AccentViolet
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (recipeName.isNotBlank()) {
                            onSaveCustomPreset(recipeName.trim())
                            showSaveDialog = false
                            modeTab = 0
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

private fun getGradeValue(g: LightroomGrade, t: GradeTool): Float = when (t) {
    GradeTool.EXPOSURE -> g.exposure
    GradeTool.CONTRAST -> g.contrast
    GradeTool.HIGHLIGHTS -> g.highlights
    GradeTool.SHADOWS -> g.shadows
    GradeTool.WHITES -> g.whites
    GradeTool.BLACKS -> g.blacks
    GradeTool.TEMP -> g.temperature
    GradeTool.TINT -> g.tint
    GradeTool.VIBRANCE -> g.vibrance
    GradeTool.SATURATION -> g.saturation
    GradeTool.CLARITY -> g.clarity
    GradeTool.SKIN -> g.hslSkinLum
    GradeTool.REDS -> g.hslRedSat
    GradeTool.GREENS -> g.hslGreenSat
    GradeTool.BLUES -> g.hslBlueSat
    GradeTool.SKY -> g.hslBlueLum
    GradeTool.CURVE_SHADOWS -> g.curveShadows
    GradeTool.CURVE_MID -> g.curveMidtones
    GradeTool.CURVE_HIGH -> g.curveHighlights
    GradeTool.VIGNETTE -> g.vignette
    GradeTool.GRAIN -> g.grain
    GradeTool.HALATION -> g.halation
    GradeTool.LIGHT_LEAK -> g.lightLeakIntensity
    GradeTool.VHS_RETRO -> g.vhsRetro
}

private fun setGradeValue(g: LightroomGrade, t: GradeTool, v: Float): LightroomGrade = when (t) {
    GradeTool.EXPOSURE -> g.copy(exposure = v)
    GradeTool.CONTRAST -> g.copy(contrast = v)
    GradeTool.HIGHLIGHTS -> g.copy(highlights = v)
    GradeTool.SHADOWS -> g.copy(shadows = v)
    GradeTool.WHITES -> g.copy(whites = v)
    GradeTool.BLACKS -> g.copy(blacks = v)
    GradeTool.TEMP -> g.copy(temperature = v)
    GradeTool.TINT -> g.copy(tint = v)
    GradeTool.VIBRANCE -> g.copy(vibrance = v)
    GradeTool.SATURATION -> g.copy(saturation = v)
    GradeTool.CLARITY -> g.copy(clarity = v)
    GradeTool.SKIN -> g.copy(hslSkinLum = v)
    GradeTool.REDS -> g.copy(hslRedSat = v)
    GradeTool.GREENS -> g.copy(hslGreenSat = v)
    GradeTool.BLUES -> g.copy(hslBlueSat = v)
    GradeTool.SKY -> g.copy(hslBlueLum = v)
    GradeTool.CURVE_SHADOWS -> g.copy(curveShadows = v)
    GradeTool.CURVE_MID -> g.copy(curveMidtones = v)
    GradeTool.CURVE_HIGH -> g.copy(curveHighlights = v)
    GradeTool.VIGNETTE -> g.copy(vignette = v)
    GradeTool.GRAIN -> g.copy(grain = v)
    GradeTool.HALATION -> g.copy(halation = v)
    GradeTool.LIGHT_LEAK -> g.copy(
        lightLeakStyle = if (v > 0.01f && g.lightLeakStyle == 0) 1 else if (v <= 0.01f) 0 else g.lightLeakStyle,
        lightLeakIntensity = v
    )
    GradeTool.VHS_RETRO -> g.copy(vhsRetro = v)
}

fun formatShutterNs(ns: Long): String {
    val sec = ns / 1_000_000_000.0
    return if (sec >= 0.5) {
        String.format(Locale.US, "%.1fs", sec)
    } else {
        "1/${(1.0 / sec).toInt().coerceAtLeast(1)}"
    }
}
