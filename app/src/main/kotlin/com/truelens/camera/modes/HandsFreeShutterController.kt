package com.truelens.camera.modes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.Locale
import kotlin.math.hypot

/**
 * Hands-Free Shutter Controller supporting:
 * 1) Voice Trigger ("Capture", "Cheese", "Photo", "Snap", "Shoot", "Smile") via Android SpeechRecognizer.
 * 2) Offline Open-Hand / Raised-Palm Gesture Detection via ML Kit Pose Detection.
 */
class HandsFreeShutterController(
    private val context: Context,
    private val onTriggered: (reason: String) -> Unit,
    private val onPalmProgress: (progress: Float) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceActive = false
    private var palmActive = false
    private var lastTriggerMs = 0L

    private val poseDetector: PoseDetector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    @Volatile
    private var isProcessingPalmFrame = false
    private var palmHoldStartMs = 0L

    companion object {
        private const val TAG = "TrueLensHandsFree"
        private const val TRIGGER_COOLDOWN_MS = 3200L
        private const val PALM_HOLD_MS = 550L
        private val TRIGGER_WORDS = listOf("capture", "cheese", "photo", "snap", "shoot", "smile", "picture")

        fun containsTriggerWord(transcript: String): Boolean {
            val normalized = transcript.lowercase(Locale.US)
            return TRIGGER_WORDS.any { normalized.contains(it) }
        }
    }

    fun setVoiceEnabled(enabled: Boolean, hasAudioPermission: Boolean) {
        if (enabled && !hasAudioPermission) {
            voiceActive = false
            stopVoiceInternal()
            return
        }
        if (voiceActive == enabled) return
        voiceActive = enabled
        mainHandler.post {
            if (enabled) startVoiceInternal() else stopVoiceInternal()
        }
    }

    fun setPalmEnabled(enabled: Boolean) {
        palmActive = enabled
        if (!enabled) {
            palmHoldStartMs = 0L
            onPalmProgress(0f)
        }
    }

    private fun startVoiceInternal() {
        if (!voiceActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer unavailable on this device")
            return
        }
        try {
            stopVoiceInternal()
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    checkMatches(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                }

                override fun onResults(results: Bundle?) {
                    checkMatches(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                    scheduleRestart(350L)
                }

                override fun onError(error: Int) {
                    scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 550L)
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer = sr
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer start error", e)
        }
    }

    private fun checkMatches(matches: List<String>?) {
        if (!voiceActive || matches.isNullOrEmpty()) return
        val hit = matches.firstOrNull { containsTriggerWord(it) }
        if (hit != null) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs >= TRIGGER_COOLDOWN_MS) {
                lastTriggerMs = now
                onTriggered("Voice: \"Capture\"")
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!voiceActive) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (voiceActive) startVoiceInternal()
        }, delayMs)
    }

    private fun stopVoiceInternal() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    /**
     * Analyzes a sampled preview bitmap for a raised open hand (wrist + fingers raised clearly
     * above shoulder or extended open palm). Safe to call ~3–4 times per second.
     */
    fun analyzePreviewFrameForPalm(bitmap: Bitmap) {
        if (!palmActive || isProcessingPalmFrame) {
            bitmap.recycle()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < TRIGGER_COOLDOWN_MS) {
            bitmap.recycle()
            return
        }
        isProcessingPalmFrame = true
        val input = InputImage.fromBitmap(bitmap, 0)
        poseDetector.process(input)
            .addOnSuccessListener { pose ->
                val raised = isOpenHandRaised(
                    wristL = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST),
                    indexL = pose.getPoseLandmark(PoseLandmark.LEFT_INDEX),
                    pinkyL = pose.getPoseLandmark(PoseLandmark.LEFT_PINKY),
                    shoulderL = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER),
                    wristR = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST),
                    indexR = pose.getPoseLandmark(PoseLandmark.RIGHT_INDEX),
                    pinkyR = pose.getPoseLandmark(PoseLandmark.RIGHT_PINKY),
                    shoulderR = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                )
                val ts = System.currentTimeMillis()
                if (raised) {
                    if (palmHoldStartMs == 0L) palmHoldStartMs = ts
                    val progress = ((ts - palmHoldStartMs).toFloat() / PALM_HOLD_MS).coerceIn(0f, 1f)
                    onPalmProgress(progress)
                    if (progress >= 1f) {
                        palmHoldStartMs = 0L
                        lastTriggerMs = ts
                        onPalmProgress(0f)
                        onTriggered("Open Hand Detected")
                    }
                } else {
                    if (palmHoldStartMs != 0L) {
                        palmHoldStartMs = 0L
                        onPalmProgress(0f)
                    }
                }
            }
            .addOnCompleteListener {
                bitmap.recycle()
                isProcessingPalmFrame = false
            }
    }

    private fun isOpenHandRaised(
        wristL: PoseLandmark?,
        indexL: PoseLandmark?,
        pinkyL: PoseLandmark?,
        shoulderL: PoseLandmark?,
        wristR: PoseLandmark?,
        indexR: PoseLandmark?,
        pinkyR: PoseLandmark?,
        shoulderR: PoseLandmark?
    ): Boolean {
        fun checkSide(w: PoseLandmark?, idx: PoseLandmark?, pnk: PoseLandmark?, sh: PoseLandmark?): Boolean {
            if (w == null || sh == null) return false
            if (w.inFrameLikelihood < 0.55f || sh.inFrameLikelihood < 0.45f) return false
            // Hand raised above shoulder (smaller y is higher on screen)
            val handAboveShoulder = w.position.y < sh.position.y - 12f
            if (!handAboveShoulder) return false
            // If finger landmarks are visible, verify open-palm orientation (index above wrist or finger spread)
            if (idx != null && pnk != null && idx.inFrameLikelihood > 0.45f) {
                val fingersUp = idx.position.y <= w.position.y + 8f
                val spread = hypot((idx.position.x - pnk.position.x).toDouble(), (idx.position.y - pnk.position.y).toDouble())
                return fingersUp && spread > 4.0
            }
            return true
        }
        return checkSide(wristL, indexL, pinkyL, shoulderL) || checkSide(wristR, indexR, pinkyR, shoulderR)
    }

    fun release() {
        voiceActive = false
        palmActive = false
        mainHandler.post { stopVoiceInternal() }
        try {
            poseDetector.close()
        } catch (_: Exception) {
        }
    }
}
