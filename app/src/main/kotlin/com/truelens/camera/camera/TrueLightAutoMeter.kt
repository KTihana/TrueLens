package com.truelens.camera.camera

/**
 * Pure optical auto-metering assistant ("True Light Auto").
 *
 * Uses the hardware ambient light sensor (lux) + live 64-bin luminance histogram
 * to recommend clean, highlight-safe, shadow-visible EV compensation steps
 * without applying any AI HDR, beauty filter, or artificial tone-mapping.
 */
enum class LightingScene(val label: String) {
    BRIGHT_SUN("SUNLIGHT"),
    DAYLIGHT("DAYLIGHT"),
    SHADE("SHADE"),
    INDOOR("INDOOR"),
    LOW_LIGHT("LOW LIGHT")
}

data class TrueLightAnalysis(
    val scene: LightingScene = LightingScene.DAYLIGHT,
    val ambientLux: Float = 500f,
    val clippedHighlightRatio: Float = 0f,
    val crushedShadowRatio: Float = 0f,
    val recommendedEvSteps: Int = 0
)

object TrueLightAutoMeter {

    fun classifyLux(lux: Float): LightingScene = when {
        lux >= 15_000f -> LightingScene.BRIGHT_SUN
        lux >= 2_000f -> LightingScene.DAYLIGHT
        lux >= 350f -> LightingScene.SHADE
        lux >= 40f -> LightingScene.INDOOR
        else -> LightingScene.LOW_LIGHT
    }

    /**
     * Evaluates the live 64-bin luminance histogram and ambient lux to keep
     * the sensor exposure true-to-life and clearly visible:
     * - Prevents highlight clipping in bright outdoor sunlight (ETTR-safe)
     * - Lifts underexposed scenes in shade/indoors/low-light so subjects stay visible
     * - Uses hysteresis so exposure never hunts or flickers
     */
    fun analyze(
        histogram: FloatArray,
        ambientLux: Float,
        currentEvSteps: Int,
        minEvSteps: Int,
        maxEvSteps: Int
    ): TrueLightAnalysis {
        val scene = classifyLux(ambientLux)
        if (histogram.size < 64) {
            return TrueLightAnalysis(scene = scene, ambientLux = ambientLux, recommendedEvSteps = currentEvSteps)
        }

        var totalWeight = 0f
        var highlightWeight = 0f
        var shadowWeight = 0f
        var weightedSum = 0f

        for (i in 0 until 64) {
            val v = histogram[i]
            totalWeight += v
            weightedSum += v * (i / 63f)
            if (i >= 60) highlightWeight += v // top ~6% (near clipping)
            if (i <= 7) shadowWeight += v     // bottom ~12% (deep shadows)
        }

        if (totalWeight <= 0.001f) {
            return TrueLightAnalysis(scene = scene, ambientLux = ambientLux, recommendedEvSteps = currentEvSteps)
        }

        val meanLuma = weightedSum / totalWeight
        val clippedRatio = highlightWeight / totalWeight
        val crushedRatio = shadowWeight / totalWeight

        // Target natural mid-tone brightness adjusted slightly by scene
        val targetMean = when (scene) {
            LightingScene.BRIGHT_SUN -> 0.44f  // Protect bright skies & highlights
            LightingScene.DAYLIGHT -> 0.47f
            LightingScene.SHADE -> 0.50f
            LightingScene.INDOOR -> 0.50f
            LightingScene.LOW_LIGHT -> 0.42f   // Keep night visible without over-boosting noise
        }

        var nextEv = currentEvSteps
        // Strong highlight protection: pull down gently if >14% of pixels are clipping
        if (clippedRatio > 0.14f && meanLuma > 0.42f) {
            nextEv = (currentEvSteps - 1).coerceAtLeast(minEvSteps)
        } else if (crushedRatio > 0.35f && clippedRatio < 0.05f && meanLuma < targetMean - 0.10f) {
            // Scene is too dark and highlights have headroom: lift exposure for visibility
            nextEv = (currentEvSteps + 1).coerceAtMost(maxEvSteps)
        } else if (meanLuma > targetMean + 0.14f) {
            nextEv = (currentEvSteps - 1).coerceAtLeast(minEvSteps)
        } else if (meanLuma < targetMean - 0.14f && clippedRatio < 0.08f) {
            nextEv = (currentEvSteps + 1).coerceAtMost(maxEvSteps)
        }

        // Clamp auto-assist to a safe ±3 steps (±1.0 EV) so it never over-darkens a photo
        if (meanLuma < 0.03f) {
            return TrueLightAnalysis(scene = scene, ambientLux = ambientLux, recommendedEvSteps = 0)
        }
        val clamped = nextEv.coerceIn(maxOf(minEvSteps, -3), minOf(maxEvSteps, 3))

        return TrueLightAnalysis(
            scene = scene,
            ambientLux = ambientLux,
            clippedHighlightRatio = clippedRatio,
            crushedShadowRatio = crushedRatio,
            recommendedEvSteps = clamped
        )
    }
}
