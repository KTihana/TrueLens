package com.truelens.camera.modes

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight Real-Time Audio Level Meter (dB Bars) for Video Mode.
 * Runs ONLY when toggled ON by the user and RECORD_AUDIO permission is granted.
 */
class AudioMeterController(
    private val scope: CoroutineScope,
    private val onLevel: (normalized01: Float, db: Float) -> Unit
) {
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(hasAudioPermission: Boolean) {
        if (!hasAudioPermission || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    return@launch
                }
                recorder.startRecording()
                val buf = ShortArray(minBuf / 2)
                var smoothed = 0f
                while (isActive) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val v = buf[i].toDouble() / 32768.0
                            sumSq += v * v
                        }
                        val rms = sqrt(sumSq / read).coerceAtLeast(1e-5)
                        val db = (20.0 * log10(rms)).toFloat().coerceIn(-60f, 0f)
                        val norm = ((db + 54f) / 54f).coerceIn(0f, 1f)
                        smoothed = smoothed * 0.45f + norm * 0.55f
                        onLevel(smoothed, db)
                    }
                }
            } catch (_: Exception) {
                onLevel(0f, -60f)
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        onLevel(0f, -60f)
    }
}
