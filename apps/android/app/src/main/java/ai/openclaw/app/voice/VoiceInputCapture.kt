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
  // Optional ASR callback: receives transcripts as they arrive during capture
  private val onAsrFeed: (suspend (ByteArray) -> Unit)? = null,
) {
  init {
    pcmFeed = onAsrFeed
  }
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
  private var asrFeedJob: Job? = null
  private var pcmFeed: (suspend (ByteArray) -> Unit)? = null

  // PCM buffer for ASR (converted from 16-bit to float)
  private val asrBuffer = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()

  /**
   * Start audio capture. Returns true if capture started successfully.
   * If false is returned, the device may not support audio recording.
   */
  fun startCapture(): Boolean {
    if (_isCapturing.value) return true
    if (!hasMicPermission()) {
      Log.e(tag, "[FALLBACK] no mic permission")
      return false
    }

    // Initialize AudioRecord BEFORE setting _isCapturing = true
    // This way we can return false if initialization fails
    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    Log.d(tag, "[FALLBACK] getMinBufferSize returned: $minBufferSize")
    if (minBufferSize <= 0) {
      Log.e(tag, "[FALLBACK] Invalid buffer size: $minBufferSize, audio hardware may be unavailable")
      return false
    }

    val effectiveBufferSize = (minBufferSize * 2).coerceAtLeast(frameSize * 4)

    val record: AudioRecord
    try {
      Log.d(tag, "[FALLBACK] Creating AudioRecord with buffer size: $effectiveBufferSize")
      record = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        audioFormat,
        effectiveBufferSize,
      )
    } catch (err: Throwable) {
      Log.e(tag, "[FALLBACK] AudioRecord creation failed: ${err.message}")
      return false
    }


    // Check if AudioRecord was successfully initialized
    Log.d(tag, "[FALLBACK] AudioRecord state: ${record.state} (STATE_INITIALIZED=${AudioRecord.STATE_INITIALIZED})")
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(tag, "[FALLBACK] AudioRecord not initialized, state=${record.state}")
      try { record.release() } catch (_: Throwable) { }
      return false
    }

    audioRecord = record
    _isCapturing.value = true

    captureJob?.cancel()
    captureJob = scope.launch(Dispatchers.Default) {
      try {
        record.startRecording()
        val buffer = ShortArray(frameSize)
        val floatFrame = FloatArray(frameSize)

        while (_isCapturing.value) {
          val numRead = record.read(buffer, 0, frameSize)
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
          // Buffer for ASR feed
          asrBuffer.offer(floatFrame.copyOfRange(0, numRead))
          asrFeedJob?.cancel()
          asrFeedJob = scope.launch(Dispatchers.Default) {
            drainAsrBuffer()
          }
        }
      } catch (err: Throwable) {
        Log.e(tag, "capture error: ${err.message}")
      } finally {
        _isCapturing.value = false
        try {
          record.stop()
          record.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
      }
    }

    return true
  }

  private suspend fun drainAsrBuffer() {
    val batch = mutableListOf<FloatArray>()
    asrBuffer.drainTo(batch)
    if (batch.isEmpty()) return
    val totalSamples = batch.sumOf { it.size }
    val pcmBytes = ByteArray(totalSamples * 2)
    var offset = 0
    for (frame in batch) {
      for (sample in frame) {
        val s = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        pcmBytes[offset++] = (s.toInt() and 0xFF).toByte()
        pcmBytes[offset++] = ((s.toInt() shr 8) and 0xFF).toByte()
      }
    }
    pcmFeed?.invoke(pcmBytes)
  }

  fun stopCapture() {
    _isCapturing.value = false
    captureJob?.cancel()
    captureJob = null
    asrFeedJob?.cancel()
    asrFeedJob = null
    try {
      audioRecord?.stop()
      audioRecord?.release()
    } catch (_: Throwable) { }
    audioRecord = null
  }

  private fun hasMicPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }
}