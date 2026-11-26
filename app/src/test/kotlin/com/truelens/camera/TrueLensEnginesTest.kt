package com.truelens.camera

import com.truelens.camera.camera.LightingScene
import com.truelens.camera.camera.TrueLightAutoMeter
import com.truelens.camera.gl.GyroStabilizer
import com.truelens.camera.grading.BuiltInPresets
import com.truelens.camera.grading.CubeLut
import com.truelens.camera.grading.LightroomGrade
import com.truelens.camera.modes.StopMotionState
import com.truelens.camera.modes.TimeLapseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrueLensEnginesTest {

    @Test
    fun `TrueLightAutoMeter classifies lux scenes accurately`() {
        assertEquals(LightingScene.BRIGHT_SUN, TrueLightAutoMeter.classifyLux(25_000f))
        assertEquals(LightingScene.DAYLIGHT, TrueLightAutoMeter.classifyLux(5_000f))
        assertEquals(LightingScene.SHADE, TrueLightAutoMeter.classifyLux(800f))
        assertEquals(LightingScene.INDOOR, TrueLightAutoMeter.classifyLux(120f))
        assertEquals(LightingScene.LOW_LIGHT, TrueLightAutoMeter.classifyLux(10f))
    }

    @Test
    fun `TrueLightAutoMeter pulls EV down when highlights clip and never exceeds safe clamp`() {
        val clippedHist = FloatArray(64) { if (it >= 61) 1.0f else 0.05f }
        val result = TrueLightAutoMeter.analyze(
            histogram = clippedHist,
            ambientLux = 20_000f,
            currentEvSteps = 0,
            minEvSteps = -12,
            maxEvSteps = 12
        )
        assertTrue("Expected negative EV recommendation to protect clipped highlights", result.recommendedEvSteps < 0)
        assertTrue("Expected safe clamp >= -3", result.recommendedEvSteps >= -3)
    }

    @Test
    fun `TrueLightAutoMeter never darkens pitch-black uninitialized frames`() {
        val darkHist = FloatArray(64) { if (it == 0) 1.0f else 0f }
        val result = TrueLightAutoMeter.analyze(
            histogram = darkHist,
            ambientLux = 50f,
            currentEvSteps = 0,
            minEvSteps = -12,
            maxEvSteps = 12
        )
        assertEquals(0, result.recommendedEvSteps)
    }

    @Test
    fun `GyroStabilizer stays within overscan bounds and resets cleanly when disabled`() {
        val stab = GyroStabilizer()
        stab.onGyroSample(0.5f, -0.8f, 0.3f, 1_000_000_000L, enabled = true)
        stab.onGyroSample(2.0f, -2.5f, 1.5f, 1_020_000_000L, enabled = true)
        assertTrue("Crop scale should engage overscan", stab.cropScale > 1.1f)
        assertTrue("OffsetX stays safely within margin", kotlin.math.abs(stab.offsetX) <= 0.06f)
        assertTrue("OffsetY stays safely within margin", kotlin.math.abs(stab.offsetY) <= 0.06f)
        assertTrue("Roll stays safely within margin", kotlin.math.abs(stab.rollRad) <= 0.09f)

        stab.onGyroSample(0f, 0f, 0f, 1_040_000_000L, enabled = false)
        assertEquals(1.0f, stab.cropScale, 0.0001f)
        assertEquals(0f, stab.offsetX, 0.0001f)
    }

    @Test
    fun `CubeLut identity 2x2x2 LUT preserves exact RGB input via trilinear interpolation`() {
        // 2x2x2 identity LUT (r fastest, then g, then b)
        val data = floatArrayOf(
            0f, 0f, 0f,   1f, 0f, 0f,
            0f, 1f, 0f,   1f, 1f, 0f,
            0f, 0f, 1f,   1f, 0f, 1f,
            0f, 1f, 1f,   1f, 1f, 1f
        )
        val lut = CubeLut("Identity", 2, data)
        val out = FloatArray(3)
        lut.lookup(0.25f, 0.50f, 0.75f, out)
        assertEquals(0.25f, out[0], 0.001f)
        assertEquals(0.50f, out[1], 0.001f)
        assertEquals(0.75f, out[2], 0.001f)
    }

    @Test
    fun `Curated presets have unique IDs and True Zero is strictly neutral`() {
        val ids = BuiltInPresets.ALL.map { it.id }.toSet()
        assertEquals(BuiltInPresets.ALL.size, ids.size)
        val trueZero = BuiltInPresets.ALL.first { it.id == "true_zero" }
        assertTrue(trueZero.grade.isNeutral)
        assertFalse(LightroomGrade(hslSkinLum = 0.2f).isNeutral)
        assertFalse(LightroomGrade(curveMidtones = 0.1f).isNeutral)
    }

    @Test
    fun `StopMotionState and TimeLapseState compute duration and progress accurately`() {
        val sm = StopMotionState(frameCount = 24, fps = 12)
        assertEquals(2.0f, sm.durationSec, 0.001f)

        val tl = TimeLapseState(isRunning = true, targetFrames = 30, capturedFrames = 15, elapsedSeconds = 65)
        assertEquals(0.5f, tl.progress, 0.001f)
        assertEquals("01:05", tl.formattedElapsed())
    }

    @Test
    fun `HandsFreeShutterController matches voice trigger keywords cleanly`() {
        assertTrue(com.truelens.camera.modes.HandsFreeShutterController.containsTriggerWord("Capture"))
        assertTrue(com.truelens.camera.modes.HandsFreeShutterController.containsTriggerWord("ok say cheese now"))
        assertTrue(com.truelens.camera.modes.HandsFreeShutterController.containsTriggerWord("take a photo"))
        assertFalse(com.truelens.camera.modes.HandsFreeShutterController.containsTriggerWord("hello world"))
    }

    @Test
    fun `PhotoExifSummary formats Polaroid camera spec line cleanly`() {
        val full = com.truelens.camera.grading.PhotoExifSummary(
            focalMm = "24mm",
            aperture = "f/1.8",
            shutter = "1/250s",
            iso = "ISO 100"
        )
        assertEquals("24mm  •  f/1.8  •  1/250s  •  ISO 100", full.formattedSpecLine())

        val fallback = com.truelens.camera.grading.PhotoExifSummary()
        assertTrue(fallback.formattedSpecLine().contains("True Optics"))
    }
}
