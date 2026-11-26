package com.truelens.camera.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import com.truelens.camera.grading.LightroomGrade
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 60fps OpenGL ES renderer that feeds Camera2 into an external OES texture and runs
 * a Lightroom-grade fragment shader + GPU Focus Peaking + Zebra highlight warnings.
 */
class LiveGradingRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val requestRenderCallback: () -> Unit,
    private val onHistogramSampled: ((FloatArray) -> Unit)? = null,
    private val onPalmSampled: ((Bitmap) -> Unit)? = null
) : GLSurfaceView.Renderer {

    @Volatile var grade: LightroomGrade = LightroomGrade.NEUTRAL
    @Volatile var focusPeakingEnabled: Boolean = false
    @Volatile var zebrasEnabled: Boolean = false
    @Volatile var macroLoupeEnabled: Boolean = false
    @Volatile var bypassGrading: Boolean = false
    @Volatile var stabOffsetX: Float = 0f
    @Volatile var stabOffsetY: Float = 0f
    @Volatile var stabRollRad: Float = 0f
    @Volatile var stabScale: Float = 1.0f
    @Volatile var splitScreenPos: Float = -1f // -1 = off, 0..1 = live vertical Before/After divider
    @Volatile var histogramSamplingEnabled: Boolean = true
    @Volatile var palmSamplingEnabled: Boolean = false

    @Volatile private var bufferWidth: Int = 1440
    @Volatile private var bufferHeight: Int = 1080
    @Volatile private var sensorOrientationDeg: Int = 90
    @Volatile private var pendingBufferResize: Boolean = false
    private var lastHistogramSampleMs: Long = 0L
    private var lastPalmSampleMs: Long = 0L
    private var histReadBuf: ByteBuffer? = null
    private var palmReadBuf: ByteBuffer? = null

    private var programId: Int = 0
    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    private val frameAvailable = AtomicBoolean(false)
    private val texMatrix = FloatArray(16)
    private var frameCounter = 0f

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    // Uniform locations
    private var uTexMatrixLoc = -1
    private var uQuadScaleLoc = -1
    private var uExposureLoc = -1
    private var uContrastLoc = -1
    private var uHighlightsLoc = -1
    private var uShadowsLoc = -1
    private var uWhitesLoc = -1
    private var uBlacksLoc = -1
    private var uTempLoc = -1
    private var uTintLoc = -1
    private var uVibranceLoc = -1
    private var uSaturationLoc = -1
    private var uClarityLoc = -1
    private var uVignetteLoc = -1
    private var uGrainLoc = -1
    private var uFocusPeakingLoc = -1
    private var uZebrasLoc = -1
    private var uMacroLoupeLoc = -1
    private var uBypassLoc = -1
    private var uTimeLoc = -1
    private var uTexelSizeLoc = -1
    private var uStabTransformLoc = -1
    private var uSplitPosLoc = -1
    private var uHslVecLoc = -1
    private var uHslBlueLumLoc = -1
    private var uCurveVecLoc = -1

    private var viewportW = 1080
    private var viewportH = 1920

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.03f, 0.04f, 1.0f)

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) {
            Log.e(TAG, "Failed to compile OpenGL shader program")
            return
        }

        uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        uQuadScaleLoc = GLES20.glGetUniformLocation(programId, "uQuadScale")
        uExposureLoc = GLES20.glGetUniformLocation(programId, "uExposure")
        uContrastLoc = GLES20.glGetUniformLocation(programId, "uContrast")
        uHighlightsLoc = GLES20.glGetUniformLocation(programId, "uHighlights")
        uShadowsLoc = GLES20.glGetUniformLocation(programId, "uShadows")
        uWhitesLoc = GLES20.glGetUniformLocation(programId, "uWhites")
        uBlacksLoc = GLES20.glGetUniformLocation(programId, "uBlacks")
        uTempLoc = GLES20.glGetUniformLocation(programId, "uTemp")
        uTintLoc = GLES20.glGetUniformLocation(programId, "uTint")
        uVibranceLoc = GLES20.glGetUniformLocation(programId, "uVibrance")
        uSaturationLoc = GLES20.glGetUniformLocation(programId, "uSaturation")
        uClarityLoc = GLES20.glGetUniformLocation(programId, "uClarity")
        uVignetteLoc = GLES20.glGetUniformLocation(programId, "uVignette")
        uGrainLoc = GLES20.glGetUniformLocation(programId, "uGrain")
        uFocusPeakingLoc = GLES20.glGetUniformLocation(programId, "uFocusPeaking")
        uZebrasLoc = GLES20.glGetUniformLocation(programId, "uZebras")
        uMacroLoupeLoc = GLES20.glGetUniformLocation(programId, "uMacroLoupe")
        uBypassLoc = GLES20.glGetUniformLocation(programId, "uBypass")
        uTimeLoc = GLES20.glGetUniformLocation(programId, "uTime")
        uTexelSizeLoc = GLES20.glGetUniformLocation(programId, "uTexelSize")
        uStabTransformLoc = GLES20.glGetUniformLocation(programId, "uStabTransform")
        uSplitPosLoc = GLES20.glGetUniformLocation(programId, "uSplitPos")
        uHslVecLoc = GLES20.glGetUniformLocation(programId, "uHslVec")
        uHslBlueLumLoc = GLES20.glGetUniformLocation(programId, "uHslBlueLum")
        uCurveVecLoc = GLES20.glGetUniformLocation(programId, "uCurveVec")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        surfaceTexture?.release()
        outputSurface?.release()

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(bufferWidth, bufferHeight)
            setOnFrameAvailableListener {
                frameAvailable.set(true)
                requestRenderCallback()
            }
        }
        outputSurface = Surface(surfaceTexture)
        onSurfaceReady(outputSurface!!)
    }

    fun configureCameraGeometry(width: Int, height: Int, sensorOrientation: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w == bufferWidth && h == bufferHeight && sensorOrientation == sensorOrientationDeg) return
        bufferWidth = w
        bufferHeight = h
        sensorOrientationDeg = sensorOrientation
        pendingBufferResize = true
        try {
            surfaceTexture?.setDefaultBufferSize(w, h)
        } catch (_: Exception) {
        }
        requestRenderCallback()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportW, viewportH)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        if (pendingBufferResize) {
            pendingBufferResize = false
            try {
                st.setDefaultBufferSize(bufferWidth, bufferHeight)
            } catch (_: Exception) {
            }
        }
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage failed", e)
                return
            }
        }
        if (programId == 0) return

        frameCounter += 0.03f
        val g = grade
        val quadScale = AspectRatioMath.computeExactFitScale(
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            sensorOrientationDeg = sensorOrientationDeg,
            viewportWidth = viewportW,
            viewportHeight = viewportH
        )

        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uQuadScaleLoc, quadScale.scaleX, quadScale.scaleY)
        GLES20.glUniform1f(uExposureLoc, g.exposure)
        GLES20.glUniform1f(uContrastLoc, g.contrast)
        GLES20.glUniform1f(uHighlightsLoc, g.highlights)
        GLES20.glUniform1f(uShadowsLoc, g.shadows)
        GLES20.glUniform1f(uWhitesLoc, g.whites)
        GLES20.glUniform1f(uBlacksLoc, g.blacks)
        GLES20.glUniform1f(uTempLoc, g.temperature)
        GLES20.glUniform1f(uTintLoc, g.tint)
        GLES20.glUniform1f(uVibranceLoc, g.vibrance)
        GLES20.glUniform1f(uSaturationLoc, g.saturation)
        GLES20.glUniform1f(uClarityLoc, g.clarity)
        GLES20.glUniform1f(uVignetteLoc, g.vignette)
        GLES20.glUniform1f(uGrainLoc, g.grain)
        GLES20.glUniform1f(uFocusPeakingLoc, if (focusPeakingEnabled) 1f else 0f)
        GLES20.glUniform1f(uZebrasLoc, if (zebrasEnabled) 1f else 0f)
        GLES20.glUniform1f(uMacroLoupeLoc, if (macroLoupeEnabled) 1f else 0f)
        GLES20.glUniform1f(uBypassLoc, if (bypassGrading) 1f else 0f)
        GLES20.glUniform1f(uTimeLoc, frameCounter)
        GLES20.glUniform2f(uTexelSizeLoc, 1.0f / viewportW, 1.0f / viewportH)
        GLES20.glUniform4f(uStabTransformLoc, stabOffsetX, stabOffsetY, stabRollRad, stabScale)
        GLES20.glUniform1f(uSplitPosLoc, splitScreenPos)
        GLES20.glUniform4f(uHslVecLoc, g.hslRedSat, g.hslSkinLum, g.hslGreenSat, g.hslBlueSat)
        GLES20.glUniform1f(uHslBlueLumLoc, g.hslBlueLum)
        GLES20.glUniform3f(uCurveVecLoc, g.curveShadows, g.curveMidtones, g.curveHighlights)

        val posLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")

        quadVertices.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)

        quadVertices.position(2)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)

        // Lightweight GPU-rendered histogram sampling every 220ms (zero extra Camera2 stream!)
        val now = System.currentTimeMillis()
        val histCb = onHistogramSampled
        if (histogramSamplingEnabled && histCb != null && now - lastHistogramSampleMs >= 220L) {
            lastHistogramSampleMs = now
            sampleRenderedHistogram(quadScale, histCb)
        }

        // Lightweight GPU-rendered palm/hand gesture sampling (~3fps) ONLY when Hands-Free Palm mode is active
        val palmCb = onPalmSampled
        if (palmSamplingEnabled && palmCb != null && now - lastPalmSampleMs >= 320L) {
            lastPalmSampleMs = now
            samplePalmBitmap(quadScale, palmCb)
        }
    }

    private fun samplePalmBitmap(
        quadScale: AspectRatioMath.QuadScale,
        callback: (Bitmap) -> Unit
    ) {
        val drawW = (viewportW * quadScale.scaleX).toInt().coerceAtLeast(1)
        val drawH = (viewportH * quadScale.scaleY).toInt().coerceAtLeast(1)
        val startX = ((viewportW - drawW) / 2).coerceAtLeast(0)
        val startY = ((viewportH - drawH) / 2).coerceAtLeast(0)
        val readW = drawW.coerceAtMost(viewportW - startX)
        val readH = drawH.coerceAtMost(viewportH - startY)
        if (readW < 64 || readH < 64) return

        // Downsample 4x on readback into a crisp ~270x360 upright bitmap for ML Kit Pose Detection
        val step = (maxOf(readW, readH) / 280).coerceAtLeast(1)
        val outW = (readW / step).coerceAtLeast(1)
        val outH = (readH / step).coerceAtLeast(1)

        val needed = readW * readH * 4
        val buf = if (palmReadBuf != null && palmReadBuf!!.capacity() >= needed) {
            palmReadBuf!!
        } else {
            ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).also { palmReadBuf = it }
        }
        buf.position(0)
        GLES20.glReadPixels(startX, startY, readW, readH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

        val pixels = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val srcY = (readH - 1 - oy * step).coerceIn(0, readH - 1)
            val rowOff = srcY * readW * 4
            for (ox in 0 until outW) {
                val off = rowOff + (ox * step).coerceIn(0, readW - 1) * 4
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                pixels[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        callback(bmp)
    }

    private fun sampleRenderedHistogram(
        quadScale: AspectRatioMath.QuadScale,
        callback: (FloatArray) -> Unit
    ) {
        val sampleW = 48
        val sampleH = 48
        val drawW = (viewportW * quadScale.scaleX).toInt().coerceAtLeast(sampleW)
        val drawH = (viewportH * quadScale.scaleY).toInt().coerceAtLeast(sampleH)
        val startX = ((viewportW - sampleW) / 2).coerceAtLeast(0)
        val startY = ((viewportH - sampleH) / 2).coerceAtLeast(0)
        if (startX + sampleW > viewportW || startY + sampleH > viewportH) return

        val buf = histReadBuf ?: ByteBuffer.allocateDirect(sampleW * sampleH * 4)
            .order(ByteOrder.nativeOrder()).also { histReadBuf = it }
        buf.position(0)
        GLES20.glReadPixels(startX, startY, sampleW, sampleH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

        val bins = IntArray(64)
        var maxCount = 1
        val totalPixels = sampleW * sampleH
        for (i in 0 until totalPixels) {
            val off = i * 4
            val r = buf.get(off).toInt() and 0xFF
            val g = buf.get(off + 1).toInt() and 0xFF
            val b = buf.get(off + 2).toInt() and 0xFF
            val luma = (r * 77 + g * 150 + b * 29) ushr 8
            val count = ++bins[luma ushr 2]
            if (count > maxCount) maxCount = count
        }
        callback(FloatArray(64) { idx -> bins[idx].toFloat() / maxCount.toFloat() })
    }

    fun release() {
        outputSurface?.release()
        outputSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "LiveGradingRenderer"

        private val QUAD_COORDS = floatArrayOf(
            // X, Y,     U, V
            -1f, -1f,    0f, 0f,
             1f, -1f,    1f, 0f,
            -1f,  1f,    0f, 1f,
             1f,  1f,    1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec2 uQuadScale;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;
            void main() {
                gl_Position = vec4(aPosition.x * uQuadScale.x, aPosition.y * uQuadScale.y, 0.0, 1.0);
                vTexCoord = (uTexMatrix * aTexCoord).xy;
                vRawUv = aTexCoord.xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;

            uniform float uExposure;
            uniform float uContrast;
            uniform float uHighlights;
            uniform float uShadows;
            uniform float uWhites;
            uniform float uBlacks;
            uniform float uTemp;
            uniform float uTint;
            uniform float uVibrance;
            uniform float uSaturation;
            uniform float uClarity;
            uniform float uVignette;
            uniform float uGrain;
            uniform float uFocusPeaking;
            uniform float uZebras;
            uniform float uMacroLoupe;
            uniform float uBypass;
            uniform float uTime;
            uniform vec2 uTexelSize;
            uniform vec4 uStabTransform; // x=offsetX, y=offsetY, z=rollRad, w=scale
            uniform float uSplitPos;     // < 0 disabled; 0..1 vertical Before/After split
            uniform vec4 uHslVec;        // x=redSat, y=skinLum, z=greenSat, w=blueSat
            uniform float uHslBlueLum;
            uniform vec3 uCurveVec;      // x=shadows, y=midtones, z=highlights

            float rand(vec2 co) {
                return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
            }

            float lumaOf(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            void main() {
                // Universal Super Steady Gyro Stabilization transform (overscan crop + counter-roll + counter-shift)
                vec2 uv = vTexCoord;
                if (uStabTransform.w > 1.001) {
                    vec2 centered = (uv - vec2(0.5)) / uStabTransform.w;
                    float c = cos(uStabTransform.z);
                    float s = sin(uStabTransform.z);
                    vec2 rotated = vec2(
                        centered.x * c - centered.y * s,
                        centered.x * s + centered.y * c
                    );
                    uv = clamp(rotated + vec2(0.5) + uStabTransform.xy, 0.0, 1.0);
                }

                vec3 col = texture2D(sTexture, uv).rgb;
                bool isLeftRawSide = (uSplitPos >= 0.0 && vRawUv.x < uSplitPos);

                if (uBypass < 0.5 && !isLeftRawSide) {
                    // 1. Exposure (EV stops)
                    col *= pow(2.0, uExposure);

                    // 2. White Balance (Temperature & Tint)
                    float tempShift = uTemp * 0.22;
                    float tintShift = uTint * 0.18;
                    col.r *= (1.0 + tempShift + tintShift * 0.25);
                    col.g *= (1.0 - tintShift * 0.50);
                    col.b *= (1.0 - tempShift + tintShift * 0.25);

                    // 3. Shadows & Highlights
                    float l = clamp(lumaOf(col), 0.0, 1.0);
                    float shadowMask = (1.0 - l) * (1.0 - l);
                    float highMask = l * l;
                    col += uShadows * 0.55 * shadowMask;
                    col += uHighlights * 0.55 * highMask;

                    // 4. Blacks & Whites
                    float blackMask = shadowMask * (1.0 - l);
                    float whiteMask = highMask * l;
                    col += uBlacks * 0.30 * blackMask;
                    col += uWhites * 0.30 * whiteMask;

                    // 5. Contrast + Clarity
                    float cFactor = 1.0 + uContrast * 0.85 + uClarity * 0.28;
                    col = (col - 0.5) * cFactor + 0.5;

                    // 6. Vibrance & Saturation
                    float curL = lumaOf(col);
                    float maxC = max(col.r, max(col.g, col.b));
                    float minC = min(col.r, min(col.g, col.b));
                    float sat = maxC - minC;
                    float vibFactor = 1.0 + uVibrance * 0.85 * (1.0 - clamp(sat, 0.0, 1.0));
                    float totalSat = max(0.0, (1.0 + uSaturation) * vibFactor);
                    col = mix(vec3(curL), col, totalSat);

                    // 6B. 3-Point Tone Curve (Shadows / Midtones / Highlights)
                    float cl = clamp(lumaOf(col), 0.0, 1.0);
                    float wS = (1.0 - cl) * (1.0 - cl);
                    float wM = 4.0 * cl * (1.0 - cl);
                    float wH = cl * cl;
                    col += (uCurveVec.x * 0.45 * wS + uCurveVec.y * 0.45 * wM + uCurveVec.z * 0.45 * wH);

                    // 6C. HSL Color Mixer (Red / Skin Orange / Green / Blue Sky)
                    float rW = max(0.0, col.r - max(col.g, col.b));
                    float skinW = max(0.0, min(col.r, col.g * 1.25) - col.b);
                    float gW = max(0.0, col.g - max(col.r, col.b));
                    float bW = max(0.0, col.b - max(col.r, col.g));
                    float hl = lumaOf(col);
                    float hslSat = max(0.0, 1.0 + rW * uHslVec.x + gW * uHslVec.z + bW * uHslVec.w);
                    col = mix(vec3(hl), col, hslSat);
                    col += (skinW * uHslVec.y * 0.45 + bW * uHslBlueLum * 0.45);

                    // 7. Optical Vignette
                    if (uVignette > 0.001) {
                        vec2 d = vRawUv - vec2(0.5);
                        float distSq = dot(d, d);
                        float vig = clamp(1.0 - uVignette * distSq * 2.2, 0.15, 1.0);
                        col *= vig;
                    }

                    // 8. Subtle Organic Film Grain
                    if (uGrain > 0.001) {
                        float n = (rand(vRawUv * 120.0 + vec2(uTime, uTime * 1.37)) - 0.5) * uGrain * 0.14;
                        col += vec3(n);
                    }
                }

                col = clamp(col, 0.0, 1.0);

                // --- GPU FOCUS PEAKING (Sobel Edge Highlight) ---
                if (uFocusPeaking > 0.5) {
                    vec2 step = uTexelSize * 2.0;
                    float tl = lumaOf(texture2D(sTexture, vTexCoord + vec2(-step.x, -step.y)).rgb);
                    float tr = lumaOf(texture2D(sTexture, vTexCoord + vec2( step.x, -step.y)).rgb);
                    float bl = lumaOf(texture2D(sTexture, vTexCoord + vec2(-step.x,  step.y)).rgb);
                    float br = lumaOf(texture2D(sTexture, vTexCoord + vec2( step.x,  step.y)).rgb);
                    float gx = (tr + br) - (tl + bl);
                    float gy = (bl + br) - (tl + tr);
                    float edge = sqrt(gx * gx + gy * gy);
                    if (edge > 0.24) {
                        col = mix(col, vec3(0.0, 0.92, 1.0), 0.78);
                    }
                }

                // --- GPU ZEBRA STRIPES (Clipped Highlight Warning > 97%) ---
                if (uZebras > 0.5) {
                    float finalL = lumaOf(col);
                    if (finalL > 0.96) {
                        float stripe = sin((vRawUv.x + vRawUv.y) * 180.0 + uTime * 6.0);
                        if (stripe > 0.0) {
                            col = vec3(1.0, 0.22, 0.22);
                        }
                    }
                }

                // --- GPU 4X SUPER MACRO FOCUS MAGNIFIER LOUPE ---
                if (uMacroLoupe > 0.5) {
                    vec2 aspect = vec2(1.0, uTexelSize.x / max(uTexelSize.y, 0.0001));
                    vec2 loupeCenter = vec2(0.76, 0.72);
                    vec2 delta = (vRawUv - loupeCenter) * aspect;
                    float dist = length(delta);
                    float radius = 0.145;
                    if (dist < radius) {
                        vec2 sampleUv = (vTexCoord - vec2(0.5)) * 0.25 + vec2(0.5);
                        vec2 zoomedUv = vec2(0.5) + (vRawUv - loupeCenter) * 0.25;
                        vec2 texDelta = (vRawUv - loupeCenter) * 0.25;
                        vec2 finalUv = clamp(vec2(0.5) + texDelta, 0.0, 1.0);
                        vec3 magCol = texture2D(sTexture, finalUv).rgb;
                        // Subtle crosshair inside loupe
                        if (abs(delta.x) < 0.0025 || abs(delta.y) < 0.0025) {
                            magCol = mix(magCol, vec3(1.0, 0.7, 0.0), 0.7);
                        }
                        col = magCol;
                    } else if (dist < radius + 0.006) {
                        // Crisp Canon Amber border ring
                        col = vec3(1.0, 0.70, 0.0);
                    }
                }

                // Live Split-Screen Divider Hairline
                if (uSplitPos >= 0.0 && abs(vRawUv.x - uSplitPos) < 0.0035) {
                    col = vec3(1.0, 0.70, 0.0);
                }

                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
