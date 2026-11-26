package com.truelens.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Hardware Concurrent Secondary Camera Controller for Dual-Camera Reaction Mode.
 * - On Android 11+ (API 30+) devices supporting true concurrent Rear + Front Camera2 streaming,
 *   opens the secondary Front camera onto a live [TextureView] alongside the main OpenGL rear stream.
 * - On single-stream devices (or emulators without concurrent HAL support), cleanly reports
 *   [supportsConcurrentDual] = false so TrueLens uses seamless BeReal-style Rear -> Front dual capture
 *   without ever crashing or locking up the main camera.
 */
class DualCameraController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var secondaryDevice: CameraDevice? = null
    private var secondarySession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var activeTextureView: TextureView? = null
    private var activeSecondaryId: String? = null

    @Volatile
    var isConcurrentLive: Boolean = false
        private set

    companion object {
        private const val TAG = "TrueLensDualCam"
    }

    /**
     * Checks hardware concurrent camera capabilities (API 30+) and returns a (rearId, frontId) pair
     * if this phone can stream both front & back cameras simultaneously.
     */
    fun findConcurrentRearFrontPair(): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val sets = cameraManager.concurrentCameraIds
            for (combo in sets) {
                var rear: String? = null
                var front: String? = null
                for (id in combo) {
                    val facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK && rear == null) rear = id
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id
                }
                if (rear != null && front != null) return rear to front
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Concurrent camera query unavailable", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun startSecondaryLive(
        frontCameraId: String,
        textureView: TextureView,
        onStateChanged: (live: Boolean) -> Unit
    ) {
        stopSecondary()
        activeTextureView = textureView
        activeSecondaryId = frontCameraId
        ensureThread()

        val st = textureView.surfaceTexture
        if (st == null) {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openSecondaryDevice(frontCameraId, surface, width, height, onStateChanged)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    stopSecondary()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        } else {
            openSecondaryDevice(frontCameraId, st, textureView.width, textureView.height, onStateChanged)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSecondaryDevice(
        cameraId: String,
        surfaceTexture: SurfaceTexture,
        viewW: Int,
        viewH: Int,
        onStateChanged: (live: Boolean) -> Unit
    ) {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val chosen = sizes?.firstOrNull { it.width == 1280 && it.height == 960 }
                ?: sizes?.firstOrNull { it.width == 960 && it.height == 720 }
                ?: sizes?.filter { it.width <= 1280 && it.height <= 960 }?.maxByOrNull { it.width * it.height }
                ?: Size(640, 480)
            surfaceTexture.setDefaultBufferSize(chosen.width, chosen.height)
            previewSurface = Surface(surfaceTexture)

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        secondaryDevice = camera
                        val surf = previewSurface ?: return
                        try {
                            val cfg = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(OutputConfiguration(surf)),
                                context.mainExecutor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        secondarySession = session
                                        try {
                                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            req.addTarget(surf)
                                            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                            session.setRepeatingRequest(req.build(), null, handler)
                                            isConcurrentLive = true
                                            onStateChanged(true)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Secondary repeating failed", e)
                                            stopSecondary()
                                            onStateChanged(false)
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        stopSecondary()
                                        onStateChanged(false)
                                    }
                                }
                            )
                            camera.createCaptureSession(cfg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Secondary session failed", e)
                            stopSecondary()
                            onStateChanged(false)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        secondaryDevice = null
                        isConcurrentLive = false
                        onStateChanged(false)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.w(TAG, "Secondary camera error=$error (falling back to sequential dual capture)")
                        camera.close()
                        secondaryDevice = null
                        isConcurrentLive = false
                        onStateChanged(false)
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open concurrent front camera", e)
            isConcurrentLive = false
            onStateChanged(false)
        }
    }

    /** Captures a crisp snapshot directly from the live concurrent secondary front TextureView. */
    fun grabLiveFrontBitmap(mirrorHorizontal: Boolean = true): Bitmap? {
        val tv = activeTextureView ?: return null
        if (!isConcurrentLive || !tv.isAvailable) return null
        val raw = tv.bitmap ?: return null
        if (!mirrorHorizontal) return raw
        val matrix = Matrix().apply { postScale(-1f, 1f, raw.width / 2f, raw.height / 2f) }
        val mirrored = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        raw.recycle()
        return mirrored
    }

    fun stopSecondary() {
        isConcurrentLive = false
        try {
            secondarySession?.close()
        } catch (_: Exception) {
        }
        secondarySession = null
        try {
            secondaryDevice?.close()
        } catch (_: Exception) {
        }
        secondaryDevice = null
        try {
            previewSurface?.release()
        } catch (_: Exception) {
        }
        previewSurface = null
    }

    private fun ensureThread() {
        if (thread == null) {
            thread = HandlerThread("TrueLensDualCam").apply { start() }
            handler = Handler(thread!!.looper)
        }
    }

    fun release() {
        stopSecondary()
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
