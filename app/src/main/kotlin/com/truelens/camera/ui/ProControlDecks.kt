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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.ln
import kotlin.math.exp

private val DeckSurface = Color(0xEE111216)
private val DeckBorder = Color(0x28FFFFFF)
private val CanonAmber = Color(0xFFFFB300)
private val CanonRed = Color(0xFFE53935)
private val SubtleText = Color(0xFFA0A4AE)

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(DeckSurface)
            .border(1.dp, DeckBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(max = 290.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Canon Mode Dial Row (P / Tv / M) + Hardware Aperture readout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposureMode.entries.forEach { mode ->
                    val selected = settings.exposureMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) CanonAmber else Color(0x22FFFFFF))
                            .clickable { onExposureMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${mode.shortLabel}  ${mode.displayName.split(" ").first()}",
                            color = if (selected) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Text(
                text = String.format(Locale.US, "f/%.1f FIXED", lens?.aperture ?: 1.8f),
                color = SubtleText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Canon C1 / C2 / C3 Custom Presets + AEB Exposure Bracketing Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("C1", "C2", "C3").forEach { slot ->
                    val saved = savedCanonSlots.contains(slot)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (saved) Color(0x2834D399) else Color(0x18FFFFFF))
                            .border(
                                0.75.dp,
                                if (saved) Color(0xFF34D399) else Color.White.copy(alpha = 0.16f),
                                RoundedCornerShape(7.dp)
                            )
                            .clickable { onLoadCanonSlot(slot) }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (saved) slot else "$slot+",
                            color = if (saved) Color(0xFF34D399) else SubtleText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (settings.aebMode.evOffsetSteps > 0) CanonAmber else Color(0x1EFFFFFF))
                    .clickable { onCycleAeb() }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = settings.aebMode.label,
                    color = if (settings.aebMode.evOffsetSteps > 0) Color.Black else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Exposure Compensation (EV) when in P mode
        if (settings.exposureMode == ExposureMode.AUTO_P) {
            val minEv = lens?.aeCompensationRange?.lower ?: -12
            val maxEv = lens?.aeCompensationRange?.upper ?: 12
            val step = lens?.aeCompensationStep?.toFloat() ?: (1f / 3f)
            val evVal = settings.evCompensationSteps * step
            ProSliderRow(
                label = "EV COMP",
                valueText = String.format(Locale.US, "%+.1f EV", evVal),
                value = settings.evCompensationSteps.toFloat(),
                valueRange = minEv.toFloat()..maxEv.toFloat(),
                isAuto = settings.evCompensationSteps == 0,
                onAutoClick = { onEvSteps(0) },
                onValueChange = { onEvSteps(it.toInt()) }
            )
        }

        // Shutter Speed (Tv or M)
        if (settings.exposureMode != ExposureMode.AUTO_P) {
            val minNs = (lens?.exposureTimeRangeNs?.lower ?: 100_000L).coerceAtLeast(100_000L)
            val maxNs = (lens?.exposureTimeRangeNs?.upper ?: 1_000_000_000L).coerceAtMost(2_000_000_000L)
            val logMin = ln(minNs.toDouble()).toFloat()
            val logMax = ln(maxNs.toDouble()).toFloat()
            val curNs = settings.shutterSpeedNs ?: 16_666_666L
            val curLog = ln(curNs.coerceIn(minNs, maxNs).toDouble()).toFloat()
            val shutterText = formatShutterNs(curNs)

            ProSliderRow(
                label = "SHUTTER",
                valueText = if (settings.shutterSpeedNs == null) "AUTO" else shutterText,
                value = curLog,
                valueRange = logMin..logMax,
                isAuto = settings.shutterSpeedNs == null,
                onAutoClick = { onShutterNs(null) },
                onValueChange = { v -> onShutterNs(exp(v.toDouble()).toLong()) }
            )
        }

        // ISO (Manual M)
        if (settings.exposureMode == ExposureMode.MANUAL_M) {
            val minIso = lens?.isoRange?.lower ?: 50
            val maxIso = (lens?.isoRange?.upper ?: 3200).coerceAtMost(6400)
            val curIso = settings.iso ?: 200
            ProSliderRow(
                label = "ISO",
                valueText = if (settings.iso == null) "AUTO" else "$curIso",
                value = curIso.coerceIn(minIso, maxIso).toFloat(),
                valueRange = minIso.toFloat()..maxIso.toFloat(),
                isAuto = settings.iso == null,
                onAutoClick = { onIso(null) },
                onValueChange = { onIso(it.toInt()) }
            )
        }

        // Manual Focus (Diopters: 0 = infinity, max = closest macro)
        val maxFocus = (lens?.minFocusDistanceDiopters ?: 10f).coerceAtLeast(1f)
        val curFocus = settings.focusDistanceDiopters ?: 0f
        ProSliderRow(
            label = "FOCUS",
            valueText = when {
                settings.focusDistanceDiopters == null -> "AF-C"
                curFocus < 0.1f -> "∞"
                else -> String.format(Locale.US, "%.2fm", 1f / curFocus)
            },
            value = curFocus.coerceIn(0f, maxFocus),
            valueRange = 0f..maxFocus,
            isAuto = settings.focusDistanceDiopters == null,
            onAutoClick = { onFocusDiopters(null) },
            onValueChange = { onFocusDiopters(it) }
        )

        // Manual White Balance (Kelvin)
        val curKelvin = settings.whiteBalanceKelvin ?: 5500
        ProSliderRow(
            label = "WB",
            valueText = if (settings.whiteBalanceKelvin == null) "AWB" else "${curKelvin}K",
            value = curKelvin.toFloat(),
            valueRange = 2500f..9500f,
            isAuto = settings.whiteBalanceKelvin == null,
            onAutoClick = { onWhiteBalanceKelvin(null) },
            onValueChange = { onWhiteBalanceKelvin((it.toInt() / 50) * 50) }
        )
    }
}

enum class GradeTab(val label: String) {
    LIGHT("Light"),
    COLOR("Color"),
    HSL_CURVE("HSL"),
    OPTICS("Optics"),
    PRESETS("Presets")
}

@Composable
fun LightroomLiveDeck(
    grade: LightroomGrade,
    activePresetId: String,
    customPresets: List<GradePreset> = emptyList(),
    activeLutName: String? = null,
    splitScreenEnabled: Boolean = false,
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
    var selectedTab by remember { mutableStateOf(GradeTab.LIGHT) }
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(DeckSurface)
            .border(1.dp, DeckBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(max = 310.dp)
    ) {
        // Tab bar + Save Recipe + Reset to True Zero
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GradeTab.entries.forEach { tab ->
                    val active = tab == selectedTab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Color.White else Color(0x1CFFFFFF))
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = tab.label,
                            color = if (active) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (onToggleSplitScreen != null) {
                    Text(
                        text = "SPLIT",
                        color = if (splitScreenEnabled) Color.Black else Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (splitScreenEnabled) CanonAmber else Color(0x1EFFFFFF))
                            .clickable { onToggleSplitScreen() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                if (!grade.isNeutral && onSaveCustomPreset != null) {
                    Text(
                        text = "+SAVE",
                        color = CanonAmber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x22FFB300))
                            .clickable { showSaveDialog = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                if (!grade.isNeutral) {
                    Text(
                        text = "RST",
                        color = CanonRed,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onReset() }
                            .padding(horizontal = 5.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (selectedTab) {
            GradeTab.LIGHT -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GradeSlider("Exposure", grade.exposure, -2.5f..2.5f, isEv = true) { v ->
                        onUpdateGrade { it.copy(exposure = v) }
                    }
                    GradeSlider("Contrast", grade.contrast, -1f..1f) { v ->
                        onUpdateGrade { it.copy(contrast = v) }
                    }
                    GradeSlider("Highlights", grade.highlights, -1f..1f) { v ->
                        onUpdateGrade { it.copy(highlights = v) }
                    }
                    GradeSlider("Shadows", grade.shadows, -1f..1f) { v ->
                        onUpdateGrade { it.copy(shadows = v) }
                    }
                    GradeSlider("Whites", grade.whites, -1f..1f) { v ->
                        onUpdateGrade { it.copy(whites = v) }
                    }
                    GradeSlider("Blacks", grade.blacks, -1f..1f) { v ->
                        onUpdateGrade { it.copy(blacks = v) }
                    }
                }
            }
            GradeTab.COLOR -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GradeSlider("Temp", grade.temperature, -1f..1f) { v ->
                        onUpdateGrade { it.copy(temperature = v) }
                    }
                    GradeSlider("Tint", grade.tint, -1f..1f) { v ->
                        onUpdateGrade { it.copy(tint = v) }
                    }
                    GradeSlider("Vibrance", grade.vibrance, -1f..1f) { v ->
                        onUpdateGrade { it.copy(vibrance = v) }
                    }
                    GradeSlider("Saturation", grade.saturation, -1f..1f) { v ->
                        onUpdateGrade { it.copy(saturation = v) }
                    }
                }
            }
            GradeTab.HSL_CURVE -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GradeSlider("Red Sat", grade.hslRedSat, -1f..1f) { v ->
                        onUpdateGrade { it.copy(hslRedSat = v) }
                    }
                    GradeSlider("Skin Lum", grade.hslSkinLum, -1f..1f) { v ->
                        onUpdateGrade { it.copy(hslSkinLum = v) }
                    }
                    GradeSlider("Green Sat", grade.hslGreenSat, -1f..1f) { v ->
                        onUpdateGrade { it.copy(hslGreenSat = v) }
                    }
                    GradeSlider("Sky Sat", grade.hslBlueSat, -1f..1f) { v ->
                        onUpdateGrade { it.copy(hslBlueSat = v) }
                    }
                    GradeSlider("Sky Depth", grade.hslBlueLum, -1f..1f) { v ->
                        onUpdateGrade { it.copy(hslBlueLum = v) }
                    }
                    GradeSlider("Curve Shd", grade.curveShadows, -0.5f..0.5f) { v ->
                        onUpdateGrade { it.copy(curveShadows = v) }
                    }
                    GradeSlider("Curve Mid", grade.curveMidtones, -0.5f..0.5f) { v ->
                        onUpdateGrade { it.copy(curveMidtones = v) }
                    }
                    GradeSlider("Curve Hi", grade.curveHighlights, -0.5f..0.5f) { v ->
                        onUpdateGrade { it.copy(curveHighlights = v) }
                    }
                }
            }
            GradeTab.OPTICS -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GradeSlider("Clarity", grade.clarity, -1f..1f) { v ->
                        onUpdateGrade { it.copy(clarity = v) }
                    }
                    GradeSlider("Vignette", grade.vignette, 0f..1f) { v ->
                        onUpdateGrade { it.copy(vignette = v) }
                    }
                    GradeSlider("Film Grain", grade.grain, 0f..1f) { v ->
                        onUpdateGrade { it.copy(grain = v) }
                    }
                    if (onImportCubeLut != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeLutName?.let { "LUT: $it" } ?: "3D LUT (.cube)",
                                color = if (activeLutName != null) Color(0xFF34D399) else SubtleText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (activeLutName != null && onClearCubeLut != null) {
                                    Text(
                                        text = "CLEAR",
                                        color = CanonRed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onClearCubeLut() }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                                Text(
                                    text = "+ IMPORT .CUBE",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CanonAmber)
                                        .clickable { onImportCubeLut() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            GradeTab.PRESETS -> {
                val allPresets = customPresets + BuiltInPresets.ALL
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    allPresets.forEach { preset ->
                        val selected = preset.id == activePresetId
                        Column(
                            modifier = Modifier
                                .width(142.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Color(0x33FFB300) else Color(0x18FFFFFF))
                                .border(
                                    1.dp,
                                    if (selected) CanonAmber else Color(0x22FFFFFF),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelectPreset(preset) }
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = preset.category,
                                    color = if (preset.isCustom) Color(0xFF34D399) else CanonAmber,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (preset.isCustom && onDeleteCustomPreset != null) {
                                    Text(
                                        text = "×",
                                        color = SubtleText,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onDeleteCustomPreset(preset.id) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = preset.name,
                                color = if (selected) CanonAmber else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = preset.subtitle,
                                color = SubtleText,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog && onSaveCustomPreset != null) {
        var recipeName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = Color(0xFF14161C),
            title = { Text("Save Custom Recipe", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    placeholder = { Text("e.g. Paris Rain, Golden Skin", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CanonAmber
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (recipeName.isNotBlank()) {
                            onSaveCustomPreset(recipeName.trim())
                            showSaveDialog = false
                            selectedTab = GradeTab.PRESETS
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CanonAmber, contentColor = Color.Black)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
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

@Composable
private fun ProSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    isAuto: Boolean,
    onAutoClick: () -> Unit,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = SubtleText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(62.dp)
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f).height(30.dp),
            colors = SliderDefaults.colors(
                thumbColor = CanonAmber,
                activeTrackColor = CanonAmber,
                inactiveTrackColor = Color(0x33FFFFFF)
            )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = valueText,
            color = if (isAuto) SubtleText else Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isAuto) Color(0x18FFFFFF) else Color(0x28FFB300))
                .clickable { onAutoClick() }
                .padding(horizontal = 4.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun GradeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    isEv: Boolean = false,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFFD5D8E0),
            fontSize = 11.sp,
            modifier = Modifier.width(72.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = CanonAmber,
                inactiveTrackColor = Color(0x33FFFFFF)
            )
        )
        Text(
            text = if (isEv) {
                String.format(Locale.US, "%+.2f", value)
            } else {
                String.format(Locale.US, "%+d", (value * 100).toInt())
            },
            color = if (value == 0f) SubtleText else CanonAmber,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(46.dp)
                .clickable { onChange(0f) }
                .padding(start = 6.dp)
        )
    }
}

fun formatShutterNs(ns: Long): String {
    val sec = ns / 1_000_000_000.0
    return if (sec >= 0.5) {
        String.format(Locale.US, "%.1fs", sec)
    } else {
        "1/${(1.0 / sec).toInt().coerceAtLeast(1)}"
    }
}
