package com.truelens.camera.gl

import kotlin.math.abs

/**
 * Universal "Super Steady" real-time Gyroscope Video Stabilizer.
 *
 * Works on ANY Android phone with a gyroscope (regardless of manufacturer) by separating
 * intentional smooth pans from high-frequency hand shake using a spring-damper filter,
 * then outputting counter-translation & counter-roll UV offsets inside a 1.14x overscan margin.
 */
class GyroStabilizer {

    @Volatile var offsetX: Float = 0f
        private set
    @Volatile var offsetY: Float = 0f
        private set
    @Volatile var rollRad: Float = 0f
        private set
    @Volatile var cropScale: Float = 1.0f
        private set

    private var lastTimestampNs: Long = 0L
    private var shakePitch: Float = 0f
    private var shakeYaw: Float = 0f
    private var shakeRoll: Float = 0f

    fun reset() {
        lastTimestampNs = 0L
        shakePitch = 0f
        shakeYaw = 0f
        shakeRoll = 0f
        offsetX = 0f
        offsetY = 0f
        rollRad = 0f
        cropScale = 1.0f
    }

    /**
     * Call from Sensor.TYPE_GYROSCOPE (rad/s) at SENSOR_DELAY_FASTEST or GAME.
     */
    fun onGyroSample(wx: Float, wy: Float, wz: Float, timestampNs: Long, enabled: Boolean) {
        if (!enabled) {
            if (cropScale != 1.0f) reset()
            return
        }
        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            cropScale = OVERSCAN_SCALE
            return
        }

        val dt = ((timestampNs - lastTimestampNs) / 1_000_000_000f).coerceIn(0.0005f, 0.05f)
        lastTimestampNs = timestampNs

        // Integrate angular velocity, then apply spring-damper decay so slow intentional pans
        // glide smoothly while fast hand jitter is cancelled out.
        val decay = (1.0f - SPRING_RETURN_RATE * dt).coerceIn(0.75f, 0.99f)
        shakePitch = (shakePitch + wx * dt) * decay
        shakeYaw = (shakeYaw + wy * dt) * decay
        shakeRoll = (shakeRoll + wz * dt) * decay

        // Clamp within the 1.14x overscan crop margin so black borders never appear
        val maxShift = 0.055f
        val maxRoll = 0.085f // ~5 degrees of instant roll correction

        offsetX = (-shakeYaw * 0.42f).coerceIn(-maxShift, maxShift)
        offsetY = (shakePitch * 0.42f).coerceIn(-maxShift, maxShift)
        rollRad = (-shakeRoll * 0.75f).coerceIn(-maxRoll, maxRoll)
        cropScale = OVERSCAN_SCALE
    }

    companion object {
        private const val OVERSCAN_SCALE = 1.14f
        private const val SPRING_RETURN_RATE = 4.2f
    }
}
