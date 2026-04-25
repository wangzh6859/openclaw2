package ai.openclaw.app.voice

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Direct audio input capture using AudioRecord.
 * Bypasses Android's broken SpeechRecognizer system.
 */
class VoiceInputCapture(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLevelChanged: (Float) -> Unit = {},
) {
    companion object {
        private const val TAG = "VoiceInputCapture"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 160 // 10ms @ 16kHz
        private const val SILENCE_THRESHOLD_DB = -40f
    }

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    fun startCapture(): Boolean {
        if (_isCapturing.value) {
            Log.d(TAG, "Already capturing")
            return true
        }

        if (!hasMicPermission()) {
            Log.w(TAG, "No mic permission")
            return false
        }

        // Get buffer size
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            return false
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(FRAME_SIZE * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized, state=${audioRecord?.state}")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            _isCapturing.value = true

            // Start capture loop
            captureJob?.cancel()
            captureJob = scope.launch(Dispatchers.Default) {
                captureLoop()
            }

            Log.d(TAG, "Started capturing")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
            try {
                audioRecord?.release()
            } catch (_: Exception) { }
            audioRecord = null
            return false
        }
    }

    private suspend fun captureLoop() {
        val buffer = ShortArray(FRAME_SIZE)

        while (_isCapturing.value) {
            try {
                val numRead = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: break

                if (numRead > 0) {
                    // Calculate RMS level
                    var sum = 0.0
                    for (i in 0 until numRead) {
                        val sample = buffer[i] / 32768.0
                        sum += sample * sample
                    }
                    val rms = kotlin.math.sqrt(sum / numRead)
                    val levelDb = (20.0 * kotlin.math.log10((rms + 1e-6).coerceAtLeast(1e-6))).toFloat()
                    val normalizedLevel = ((levelDb - SILENCE_THRESHOLD_DB) / (-SILENCE_THRESHOLD_DB)).coerceIn(0f, 1f)

                    onLevelChanged(normalizedLevel)
                } else if (numRead < 0) {
                    Log.w(TAG, "Read returned $numRead, stopping")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}")
                break
            }
        }
    }

    fun stopCapture() {
        Log.d(TAG, "Stopping capture")
        _isCapturing.value = false
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping: ${e.message}")
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing: ${e.message}")
        }

        audioRecord = null
        onLevelChanged(0f)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}