package com.truelens.camera.grading

data class LightroomGrade(
    // Light
    val exposure: Float = 0f,      // -3.0 .. +3.0 EV
    val contrast: Float = 0f,      // -1.0 .. +1.0
    val highlights: Float = 0f,    // -1.0 .. +1.0
    val shadows: Float = 0f,       // -1.0 .. +1.0
    val whites: Float = 0f,        // -1.0 .. +1.0
    val blacks: Float = 0f,        // -1.0 .. +1.0
    // Color
    val temperature: Float = 0f,   // -1.0 (Cool) .. +1.0 (Warm)
    val tint: Float = 0f,          // -1.0 (Green) .. +1.0 (Magenta)
    val vibrance: Float = 0f,      // -1.0 .. +1.0
    val saturation: Float = 0f,    // -1.0 .. +1.0
    // Detail & Optics
    val clarity: Float = 0f,       // -1.0 .. +1.0
    val vignette: Float = 0f,      //  0.0 ..  1.0
    val grain: Float = 0f,         //  0.0 ..  1.0
    // HSL Color Mixer (Skin / Foliage / Sky control)
    val hslRedSat: Float = 0f,     // -1.0 .. +1.0
    val hslSkinLum: Float = 0f,    // -1.0 .. +1.0 (Orange/Skin luminance)
    val hslGreenSat: Float = 0f,   // -1.0 .. +1.0 (Foliage saturation)
    val hslBlueSat: Float = 0f,    // -1.0 .. +1.0 (Sky saturation)
    val hslBlueLum: Float = 0f,    // -1.0 .. +1.0 (Sky depth)
    // 3-Point Tone Curve
    val curveShadows: Float = 0f,  // -0.5 .. +0.5
    val curveMidtones: Float = 0f, // -0.5 .. +0.5
    val curveHighlights: Float = 0f // -0.5 .. +0.5
) {
    val isNeutral: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && temperature == 0f && tint == 0f &&
            vibrance == 0f && saturation == 0f && clarity == 0f && vignette == 0f && grain == 0f &&
            hslRedSat == 0f && hslSkinLum == 0f && hslGreenSat == 0f && hslBlueSat == 0f &&
            hslBlueLum == 0f && curveShadows == 0f && curveMidtones == 0f && curveHighlights == 0f

    companion object {
        val NEUTRAL = LightroomGrade()
    }
}

data class GradePreset(
    val id: String,
    val name: String,
    val category: String,
    val subtitle: String,
    val grade: LightroomGrade,
    val isCustom: Boolean = false
)

/**
 * Curated film & editorial presets modeled after the most-used real-world
 * Fujifilm recipes and Lightroom profiles — subtle, refined, never over-cooked.
 */
object BuiltInPresets {
    val ALL: List<GradePreset> = listOf(
        GradePreset(
            id = "true_zero",
            name = "True Zero",
            category = "PURE",
            subtitle = "Untouched sensor, zero grading",
            grade = LightroomGrade.NEUTRAL
        ),
        GradePreset(
            id = "reggies_portra",
            name = "Reggie's Portra",
            category = "PORTRAIT",
            subtitle = "Creamy skin tones & soft pastel highlights",
            grade = LightroomGrade(
                exposure = 0.10f,
                contrast = -0.05f,
                highlights = -0.26f,
                shadows = 0.20f,
                blacks = 0.06f,
                temperature = 0.16f,
                tint = 0.05f,
                vibrance = 0.10f,
                saturation = -0.06f,
                grain = 0.20f
            )
        ),
        GradePreset(
            id = "classic_cuban",
            name = "Classic Cuban Neg",
            category = "STREET",
            subtitle = "Sun-drenched vintage contrast & warmth",
            grade = LightroomGrade(
                exposure = -0.05f,
                contrast = 0.24f,
                highlights = -0.22f,
                shadows = -0.08f,
                blacks = -0.06f,
                temperature = 0.24f,
                tint = 0.04f,
                vibrance = 0.14f,
                saturation = -0.08f,
                clarity = 0.14f,
                vignette = 0.14f,
                grain = 0.24f
            )
        ),
        GradePreset(
            id = "kodak_gold_200",
            name = "Kodak Gold 200",
            category = "SUMMER",
            subtitle = "Nostalgic warm afternoon analog glow",
            grade = LightroomGrade(
                exposure = 0.08f,
                contrast = 0.10f,
                highlights = -0.18f,
                shadows = 0.16f,
                temperature = 0.28f,
                tint = 0.06f,
                vibrance = 0.18f,
                saturation = -0.02f,
                grain = 0.18f
            )
        ),
        GradePreset(
            id = "cinestill_800t",
            name = "CineStill 800T",
            category = "NIGHT",
            subtitle = "Cool tungsten cinema & matte shadows",
            grade = LightroomGrade(
                contrast = 0.16f,
                highlights = -0.16f,
                shadows = 0.14f,
                blacks = 0.10f,
                temperature = -0.28f,
                tint = 0.06f,
                vibrance = 0.14f,
                saturation = -0.06f,
                vignette = 0.20f,
                grain = 0.26f
            )
        ),
        GradePreset(
            id = "classic_chrome",
            name = "Classic Chrome",
            category = "DOCUMENTARY",
            subtitle = "Refined muted color & crisp midtones",
            grade = LightroomGrade(
                contrast = 0.20f,
                highlights = -0.18f,
                shadows = -0.10f,
                blacks = -0.06f,
                temperature = -0.04f,
                vibrance = -0.14f,
                saturation = -0.18f,
                clarity = 0.14f,
                vignette = 0.14f,
                grain = 0.14f
            )
        ),
        GradePreset(
            id = "editorial_clean",
            name = "Editorial Clean",
            category = "FASHION",
            subtitle = "Crisp neutral whites & true skin tones",
            grade = LightroomGrade(
                exposure = 0.12f,
                contrast = 0.12f,
                highlights = -0.14f,
                shadows = 0.18f,
                whites = 0.12f,
                temperature = 0.02f,
                vibrance = 0.08f,
                saturation = -0.05f,
                clarity = 0.10f
            )
        ),
        GradePreset(
            id = "dark_academia",
            name = "Dark Academia",
            category = "MOODY",
            subtitle = "Rich European cafe shadows & soft tone",
            grade = LightroomGrade(
                exposure = -0.16f,
                contrast = 0.22f,
                highlights = -0.28f,
                shadows = -0.14f,
                blacks = -0.10f,
                temperature = 0.10f,
                tint = -0.04f,
                vibrance = -0.10f,
                saturation = -0.16f,
                clarity = 0.12f,
                vignette = 0.26f,
                grain = 0.18f
            )
        ),
        GradePreset(
            id = "ilford_hp5",
            name = "Ilford HP5 400",
            category = "B&W",
            subtitle = "Classic 35mm silver tonal range & grain",
            grade = LightroomGrade(
                contrast = 0.28f,
                highlights = -0.12f,
                shadows = 0.08f,
                whites = 0.14f,
                blacks = -0.10f,
                saturation = -1.0f,
                clarity = 0.20f,
                vignette = 0.18f,
                grain = 0.28f
            )
        )
    )
}
