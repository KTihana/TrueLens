package com.truelens.camera.modes

data class TimeLapseState(
    val isRunning: Boolean = false,
    val intervalSeconds: Int = 2,
    val targetFrames: Int = 30, // 0 = unlimited
    val capturedFrames: Int = 0,
    val elapsedSeconds: Int = 0
) {
    val progress: Float
        get() = if (targetFrames <= 0) 0f else (capturedFrames.toFloat() / targetFrames.toFloat()).coerceIn(0f, 1f)

    fun formattedElapsed(): String {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    companion object {
        val INTERVAL_OPTIONS = listOf(1, 2, 5, 10, 30)
        val FRAME_OPTIONS = listOf(15, 30, 60, 120, 0) // 0 = Unlimited
    }
}
