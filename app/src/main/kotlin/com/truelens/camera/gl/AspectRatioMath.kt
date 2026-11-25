package com.truelens.camera.gl

import android.util.Size
import kotlin.math.abs

/**
 * Pure mathematical geometry helpers guaranteeing 1:1 true pixel aspect ratio
 * (zero horizontal squishing, zero vertical stretching) across every phone screen
 * and every camera sensor orientation / resolution.
 */
object AspectRatioMath {

    data class QuadScale(val scaleX: Float, val scaleY: Float)

    /**
     * Computes exact NDC vertex scale (scaleX, scaleY) so that a camera buffer of
     * [bufferWidth] x [bufferHeight] (rotated by [sensorOrientationDeg]) renders with
     * an exact 1.000000 pixel aspect ratio inside a [viewportWidth] x [viewportHeight] screen.
     */
    fun computeExactFitScale(
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDeg: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ): QuadScale {
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return QuadScale(1f, 1f)
        }
        val rotated = (sensorOrientationDeg % 180) != 0
        val camDispW = if (rotated) bufferHeight.toFloat() else bufferWidth.toFloat()
        val camDispH = if (rotated) bufferWidth.toFloat() else bufferHeight.toFloat()

        val camRatio = camDispW / camDispH
        val viewRatio = viewportWidth.toFloat() / viewportHeight.toFloat()

        return if (camRatio > viewRatio) {
            // Camera frame is wider than viewport (e.g., 3:4 camera on 9:20 phone)
            // Keep full width (scaleX = 1), shrink height proportionally so pixel ratio == 1.0
            QuadScale(scaleX = 1.0f, scaleY = (viewRatio / camRatio).coerceIn(0.1f, 1.0f))
        } else {
            // Camera frame is taller/narrower than viewport
            // Keep full height (scaleY = 1), shrink width proportionally so pixel ratio == 1.0
            QuadScale(scaleX = (camRatio / viewRatio).coerceIn(0.1f, 1.0f), scaleY = 1.0f)
        }
    }

    /**
     * Picks the best supported preview size whose aspect ratio matches the still JPEG
     * capture size (WYSIWYG framing) while staying <= 1920px on the long edge for 60fps GL.
     */
    fun chooseMatchingPreviewSize(
        supportedPreviewSizes: Array<Size>?,
        targetStillSize: Size
    ): Size {
        if (supportedPreviewSizes.isNullOrEmpty()) return Size(1440, 1080)
        val targetRatio = targetStillSize.width.toFloat() / targetStillSize.height.coerceAtLeast(1).toFloat()

        val candidates = supportedPreviewSizes.filter {
            maxOf(it.width, it.height) <= 1920 && minOf(it.width, it.height) >= 480
        }.ifEmpty { supportedPreviewSizes.toList() }

        // First prefer sizes matching the exact still capture aspect ratio (within 2%)
        val sameRatio = candidates.filter {
            val r = it.width.toFloat() / it.height.coerceAtLeast(1).toFloat()
            abs(r - targetRatio) < 0.025f
        }

        return if (sameRatio.isNotEmpty()) {
            sameRatio.maxByOrNull { it.width.toLong() * it.height.toLong() }!!
        } else {
            candidates.minByOrNull {
                val r = it.width.toFloat() / it.height.coerceAtLeast(1).toFloat()
                abs(r - targetRatio)
            } ?: Size(1440, 1080)
        }
    }
}
