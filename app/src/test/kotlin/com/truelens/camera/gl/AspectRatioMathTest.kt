package com.truelens.camera.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class AspectRatioMathTest {

    @Test
    fun `4_3 rear sensor rotated 90deg on 20_9 phone has exact 1_0 pixel aspect ratio`() {
        val viewW = 1080
        val viewH = 2400
        val scale = AspectRatioMath.computeExactFitScale(
            bufferWidth = 1440,
            bufferHeight = 1080,
            sensorOrientationDeg = 90,
            viewportWidth = viewW,
            viewportHeight = viewH
        )
        val renderedW = scale.scaleX * viewW
        val renderedH = scale.scaleY * viewH
        val renderedRatio = renderedW / renderedH
        val expectedRatio = 1080f / 1440f // 3:4 portrait
        assertEquals(expectedRatio, renderedRatio, 0.0001f)
    }

    @Test
    fun `4_3 front sensor rotated 270deg on 19_5_9 phone has exact 1_0 pixel aspect ratio`() {
        val viewW = 1080
        val viewH = 2340
        val scale = AspectRatioMath.computeExactFitScale(
            bufferWidth = 1920,
            bufferHeight = 1440,
            sensorOrientationDeg = 270,
            viewportWidth = viewW,
            viewportHeight = viewH
        )
        val renderedRatio = (scale.scaleX * viewW) / (scale.scaleY * viewH)
        assertEquals(1440f / 1920f, renderedRatio, 0.0001f)
    }

    @Test
    fun `16_9 sensor rotated 90deg on 20_9 phone has exact 9_16 ratio without squish`() {
        val viewW = 1080
        val viewH = 2400
        val scale = AspectRatioMath.computeExactFitScale(
            bufferWidth = 1920,
            bufferHeight = 1080,
            sensorOrientationDeg = 90,
            viewportWidth = viewW,
            viewportHeight = viewH
        )
        val renderedRatio = (scale.scaleX * viewW) / (scale.scaleY * viewH)
        assertEquals(1080f / 1920f, renderedRatio, 0.0001f)
    }

    @Test
    fun `square tablet viewport scales width down instead of stretching`() {
        val viewW = 1200
        val viewH = 1200
        val scale = AspectRatioMath.computeExactFitScale(
            bufferWidth = 1440,
            bufferHeight = 1080,
            sensorOrientationDeg = 90,
            viewportWidth = viewW,
            viewportHeight = viewH
        )
        val renderedRatio = (scale.scaleX * viewW) / (scale.scaleY * viewH)
        assertEquals(0.75f, renderedRatio, 0.0001f)
        assertEquals(1.0f, scale.scaleY, 0.0001f)
    }
}
