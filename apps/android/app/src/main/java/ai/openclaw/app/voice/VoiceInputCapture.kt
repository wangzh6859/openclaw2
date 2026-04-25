package ai.openclaw.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Direct audio input capture, bypassing Android's broken SpeechRecognizer.
 */
class VoiceInputCapture(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onAudioFrame: suspend (FloatArray) -> Unit = {},
  private val onLevelChanged: (Float) -> Unit = {},
) {
  companion object {
    private const val tag = "VoiceInputCapture"
    private const val sampleRate = 16_000
    private const val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private const val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private const val frameSize = 160 // 10ms @ 16kHz
    private const val silenceThresholdDb = -40f
  }

  private val _isCapturing = MutableStateFlow(false)
  val isCapturing: StateFlow<Boolean> = _isCapturing

  private var audioRecord: AudioRecord? = null
  private var captureJob: Job? = null

  fun startCapture() {
    if (_isCapturing.value) return
    if (!hasMicPermission()) {
      Log.w(tag, "no mic permission")
      return
    }

    _isCapturing.value = true
    captureJob?.cancel()
    captureJob =
      scope.launch(Dispatchers.Default) {
        try {
          val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
          val effectiveBufferSize = (minBufferSize * 2).coerceAtLeast(frameSize * 4)

          audioRecord =
            AudioRecord(
              MediaRecorder.AudioSource.MIC,
              sampleRate,
              channelConfig,
              audioFormat,
              effectiveBufferSize,
            )

          audioRecord?.startRecording()
          val buffer = ShortArray(frameSize)
          val floatFrame = FloatArray(frameSize)

          while (_isCapturing.value) {
            val numRead = audioRecord?.read(buffer, 0, frameSize) ?: break
            if (numRead <= 0) {
              delay(5)
              continue
            }

            // Convert to float and compute RMS
            var sumSq = 0.0
            for (i in 0 until numRead) {
              val normalized = buffer[i] / 32768f
              floatFrame[i] = normalized
              sumSq += normalized * normalized
            }

            val rms = sqrt(sumSq / numRead)
            val levelDb = (20.0 * kotlin.math.log10((rms + 1e-6).coerceAtLeast(1e-6))).toFloat()
            val normalizedLevel = ((levelDb - silenceThresholdDb) / (-silenceThresholdDb)).coerceIn(0f, 1f)
            onLevelChanged(normalizedLevel)

            // Send frame
            onAudioFrame(floatFrame.copyOfRange(0, numRead))
          }
        } catch (err: Throwable) {
          Log.e(tag, "capture error: ${err.message}")
        } finally {
          try {
            audioRecord?.stop()
            audioRecord?.release()
          } catch (_: Throwable) {
          }
          audioRecord = null
          _isCapturing.value = false
        }
      }
  }

  fun stopCapture() {
    _isCapturing.value = false
    captureJob?.cancel()
    captureJob = null
    try {
      audioRecord?.stop()
      audioRecord?.release()
    } catch (_: Throwable) {
    }
    audioRecord = null
  }

  private fun hasMicPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }
}
