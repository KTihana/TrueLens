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

    @Test
    fun `ViewfinderScannerController parses QR URL, WiFi, Phone, and Live Text cleanly`() {
        val urlQr = com.truelens.camera.modes.ViewfinderScannerController.parseQrPayload(
            rawValue = "https://www.github.com/KTihana/TrueLens",
            valueType = 8, // TYPE_URL
            normalizedBox = null
        )
        assertEquals(com.truelens.camera.modes.QrActionType.URL, urlQr.actionType)

        val wifiQr = com.truelens.camera.modes.ViewfinderScannerController.parseQrPayload(
            rawValue = "WIFI:T:WPA;S:CafeGuest;P:secret123;;",
            valueType = 9, // TYPE_WIFI
            normalizedBox = null
        )
        assertEquals(com.truelens.camera.modes.QrActionType.WIFI, wifiQr.actionType)
        assertEquals("CafeGuest", wifiQr.wifiSsid)
        assertEquals("secret123", wifiQr.wifiPassword)

        val liveText = com.truelens.camera.modes.ViewfinderScannerController.extractLiveTextInfo(
            "Visit https://example.com or email hi@example.com\nCall +1 555-234-5678"
        )
        assertEquals("https://example.com", liveText?.firstUrl)
        assertEquals("hi@example.com", liveText?.firstEmail)
        assertTrue(liveText?.firstPhone?.contains("555") == true)
    }

    @Test
    fun `PortraitBokehEngine scales optical blur inversely with f-stop aperture`() {
        val wideOpen = com.truelens.camera.modes.PortraitBokehEngine.apertureToBlurRadius(1.4f)
        val medium = com.truelens.camera.modes.PortraitBokehEngine.apertureToBlurRadius(4.0f)
        val stoppedDown = com.truelens.camera.modes.PortraitBokehEngine.apertureToBlurRadius(16.0f)
        assertTrue(wideOpen > medium)
        assertTrue(medium > stoppedDown)
    }

    @Test
    fun `AstroSkyEngine removes isolated hot pixels and reduces orange light pollution while keeping stars`() {
        val w = 32
        val h = 32
        val darkSky = (0xFF shl 24) or (60 shl 16) or (40 shl 8) or 30
        val px = IntArray(w * h) { darkSky }
        // Inject a 1-pixel stuck hot red pixel at (10, 10)
        val hotIdx = 10 * w + 10
        px[hotIdx] = (0xFF shl 24) or (255 shl 16) or (20 shl 8) or 20
        com.truelens.camera.modes.AstroSkyEngine.removeHotPixelsInPlace(px, w, h)
        val fixedR = (px[hotIdx] shr 16) and 0xFF
        assertTrue("Expected isolated hot pixel red spike to be cleaned (got $fixedR)", fixedR < 100)

        // Inject a 2x2 real bright star at (20, 20) and run light pollution gradient cleaner
        val star = (0xFF shl 24) or (250 shl 16) or (250 shl 8) or 255
        px[20 * w + 20] = star
        px[20 * w + 21] = star
        px[21 * w + 20] = star
        px[21 * w + 21] = star
        com.truelens.camera.modes.AstroSkyEngine.subtractLightPollutionGradientInPlace(px, w, h)
        val skyAfterR = (px[5 * w + 5] shr 16) and 0xFF
        val starAfterR = (px[20 * w + 20] shr 16) and 0xFF
        assertTrue("Sky orange glow should darken (was 60, now $skyAfterR)", skyAfterR < 50)
        assertTrue("Real star should remain bright (now $starAfterR)", starAfterR > 200)
    }

    @Test
    fun `Light leaks, halation, VHS are OFF by default and true 24fps cinema presets exist`() {
        val neutral = LightroomGrade.NEUTRAL
        assertTrue(neutral.isNeutral)
        assertEquals(0f, neutral.halation, 0.0001f)
        assertEquals(0, neutral.lightLeakStyle)
        assertEquals(0f, neutral.vhsRetro, 0.0001f)

        assertFalse(LightroomGrade(halation = 0.5f).isNeutral)
        assertFalse(LightroomGrade(lightLeakStyle = 2, lightLeakIntensity = 0.6f).isNeutral)
        assertFalse(LightroomGrade(vhsRetro = 0.8f).isNeutral)

        val leakStylesInPresets = BuiltInPresets.ALL.map { it.grade.lightLeakStyle }.filter { it > 0 }.toSet()
        assertTrue("Expected multiple distinct light leak styles in presets", leakStylesInPresets.size >= 4)
        assertTrue(BuiltInPresets.ALL.any { it.grade.vhsRetro > 0.5f })

        val qualities = com.truelens.camera.camera.VideoQuality.entries
        assertTrue(qualities.any { it.targetFps == 24 && it.targetHeight == 1080 })
        assertTrue(qualities.any { it.targetFps == 24 && it.targetHeight == 2160 })
    }

    @Test
    fun `Scientific YCbCr denoiser reduces chroma and luminance grain while preserving sharp edges`() {
        val w = 32
        val h = 32
        val px = IntArray(w * h)
        // Left half: dark noisy wall (gray 70 +/- 18 color+luma speckle), Right half: bright clean edge (220)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < w / 2) {
                    val sign = if ((x + y) % 2 == 0) 1 else -1
                    val r = (70 + sign * 22).coerceIn(0, 255)
                    val g = (70 - sign * 18).coerceIn(0, 255)
                    val b = (70 + sign * 20).coerceIn(0, 255)
                    px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    px[y * w + x] = (0xFF shl 24) or (220 shl 16) or (220 shl 8) or 220
                }
            }
        }

        fun leftChromaSpread(arr: IntArray): Double {
            var acc = 0.0; var n = 0
            for (y in 4 until h - 4) {
                for (x in 4 until w / 2 - 4) {
                    val p = arr[y * w + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    acc += kotlin.math.abs(r - g)
                    n++
                }
            }
            return acc / n
        }

        val spreadBefore = leftChromaSpread(px)
        com.truelens.camera.grading.BitmapGrader.denoisePixelsInPlace(px, w, h, iso = 1200)
        val spreadAfter = leftChromaSpread(px)

        // Chroma noise spread should drop dramatically (>70% reduction!)
        assertTrue("Expected huge chroma noise reduction (before=$spreadBefore, after=$spreadAfter)", spreadAfter < spreadBefore * 0.30)

        // Sharp edge across x = 15 -> 16 must remain crisp (contrast > 110)
        val leftEdge = (px[16 * w + 15] shr 16) and 0xFF
        val rightEdge = (px[16 * w + 16] shr 16) and 0xFF
        assertTrue("Expected crisp optical edge preservation (left=$leftEdge, right=$rightEdge)", (rightEdge - leftEdge) > 110)
    }
}
