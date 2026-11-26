package com.truelens.camera.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.Surface
import com.truelens.camera.grading.LightroomGrade

class CameraGlSurfaceView(
    context: Context,
    onSurfaceReady: (Surface) -> Unit,
    onHistogramSampled: ((FloatArray) -> Unit)? = null,
    onPalmSampled: ((Bitmap) -> Unit)? = null
) : GLSurfaceView(context) {

    val renderer: LiveGradingRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = LiveGradingRenderer(
            onSurfaceReady = onSurfaceReady,
            requestRenderCallback = { requestRender() },
            onHistogramSampled = onHistogramSampled,
            onPalmSampled = onPalmSampled
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun configureCameraGeometry(width: Int, height: Int, sensorOrientation: Int) {
        renderer.configureCameraGeometry(width, height, sensorOrientation)
    }

    fun updateGrade(
        grade: LightroomGrade,
        focusPeaking: Boolean,
        zebras: Boolean,
        macroLoupe: Boolean,
        splitScreenPos: Float,
        bypass: Boolean
    ) {
        renderer.grade = grade
        renderer.focusPeakingEnabled = focusPeaking
        renderer.zebrasEnabled = zebras
        renderer.macroLoupeEnabled = macroLoupe
        renderer.splitScreenPos = splitScreenPos
        renderer.bypassGrading = bypass
        requestRender()
    }

    fun updateStabilizer(offsetX: Float, offsetY: Float, rollRad: Float, scale: Float) {
        renderer.stabOffsetX = offsetX
        renderer.stabOffsetY = offsetY
        renderer.stabRollRad = rollRad
        renderer.stabScale = scale
        requestRender()
    }

    fun releaseRenderer() {
        renderer.release()
    }
}
