package ai.openclaw.app.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * Singleton on-device ASR using Vosk.
 *
 * Architecture:
 * - VoiceInputCapture captures PCM via AudioRecord (16kHz mono)
 * - PCM bytes are piped to VoskRecognizer via feedPcm()
 * - Recognized text is emitted via onTranscript callback
 *
 * The Vosk Chinese model (vosk-model-cn-0.3, ~50MB) is downloaded
 * at runtime from alphacephei.com on first use.
 */
object VoskRecognizer {
  private const val TAG = "VoskAsr"
  private const val VOSK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-cn-0.3.zip"
  private const val VOSK_MODEL_DIR = "vosk-model-cn-0.3"
  private const val VOSK_SAMPLE_RATE = 16000f

  private var model: Model? = null
  private var recognizer: Recognizer? = null
  private var ready = false
  private var downloadJobStarted = false

  // Text callback — wired by callers (MicCaptureManager)
  var onTranscript: ((String, Boolean) -> Unit)? = null

  /**
   * Feed 16-bit mono PCM samples (from AudioRecord) into Vosk.
   * pcmBytes must be little-endian 16-bit PCM at 16kHz.
   */
  fun feedPcm(pcmBytes: ByteArray) {
    val rec = recognizer ?: return
    if (pcmBytes.isEmpty()) return

    // Convert 16-bit PCM bytes to float samples
    val numSamples = pcmBytes.size / 2
    val floatSamples = FloatArray(numSamples)
    for (i in 0 until numSamples) {
      val low = pcmBytes[i * 2].toInt() and 0xFF
      val high = pcmBytes[i * 2 + 1].toInt()
      val sample = (low or (high shl 8)).toShort().toInt()
      floatSamples[i] = sample / 32768f
    }

    // Feed to Vosk recognizer
    val gotResult = rec.acceptWaveForm(floatSamples, floatSamples.size)
    if (gotResult) {
      val result = rec.result
      Log.d(TAG, "[VOSK] final result: $result")
      parseAndEmit(result, isFinal = true)
    } else {
      val partial = rec.partialResult
      Log.d(TAG, "[VOSK] partial: $partial")
      parseAndEmit(partial, isFinal = false)
    }
  }

  /** Flush remaining audio to get final result (call on stop). */
  fun flush() {
    val rec = recognizer ?: return
    try {
      // Feed ~200ms of silence to flush buffered audio
      val silence = FloatArray((VOSK_SAMPLE_RATE * 0.2).toInt())
      val gotResult = rec.acceptWaveForm(silence, silence.size)
      if (gotResult) {
        val result = rec.result
        Log.d(TAG, "[VOSK] flush final: $result")
        parseAndEmit(result, isFinal = true)
      }
      rec.reset()  // Reset so next session starts clean
    } catch (e: Throwable) {
      Log.w(TAG, "flush error: ${e.message}")
    }
  }

  /** Reset recognizer state (call before new session). */
  fun reset() {
    try { recognizer?.reset() } catch (_: Throwable) { }
  }

  private fun parseAndEmit(jsonResult: String, isFinal: Boolean) {
    if (jsonResult.isBlank()) return
    try {
      val text = extractText(jsonResult)
      Log.d(TAG, "[VOSK] parseAndEmit isFinal=$isFinal text=[$text]")
      if (text.isNotBlank()) {
        onTranscript?.invoke(text, isFinal)
      }
    } catch (e: Throwable) {
      Log.w(TAG, "parse error: ${e.message}")
    }
  }

  private fun extractText(json: String): String {
    // Vosk final: {"result": [...], "text": "hello"}
    val textMatch = Regex(""""text"\s*:\s*"([^"]*)"""").find(json)
    if (textMatch != null) {
      val value = textMatch.groupValues[1]
      if (value.isNotBlank()) return value
    }
    // Vosk partial: {"partial": "he"}
    val partialMatch = Regex(""""partial"\s*:\s*"([^"]*)"""").find(json)
    if (partialMatch != null) {
      val value = partialMatch.groupValues[1]
      if (value.isNotBlank()) return value
    }
    return ""
  }

  /**
   * Initialize Vosk recognizer from pre-downloaded model.
   * Call once at app startup.
   */
  fun init(context: Context, scope: CoroutineScope, onReady: () -> Unit) {
    if (ready) {
      onReady()
      return
    }
    if (downloadJobStarted) return
    downloadJobStarted = true

    val modelDir = File(context.filesDir, VOSK_MODEL_DIR)

    if (!modelDir.exists()) {
      Log.d(TAG, "Vosk model not found — downloading in background")
      downloadModel(modelDir, scope) {
        initModel(modelDir, onReady)
      }
    } else {
      Log.d(TAG, "Vosk model found at ${modelDir.absolutePath}")
      initModel(modelDir, onReady)
    }
  }

  private fun initModel(modelDir: File, onReady: () -> Unit) {
    try {
      model = Model(modelDir.absolutePath)
      recognizer = Recognizer(model!!, VOSK_SAMPLE_RATE).apply {
        setMaxAlternatives(0)
        setWords(false)
      }
      ready = true
      onReady()
      Log.d(TAG, "Vosk recognizer ready")
    } catch (e: Throwable) {
      Log.e(TAG, "Vosk init failed: ${e.message}")
    }
  }

  private fun downloadModel(modelDir: File, scope: CoroutineScope, onDone: () -> Unit) {
    scope.launch(Dispatchers.IO) {
      try {
        val url = java.net.URL(VOSK_MODEL_URL)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.doInput = true
        conn.connect()

        val input = conn.inputStream
        val zipInput = java.util.zip.ZipInputStream(input)
        var entry = zipInput.nextEntry

        while (entry != null) {
          if (!entry.name.endsWith("/")) {
            // Strip model dir prefix if present
            val safeName = entry.name.substringAfter("$VOSK_MODEL_DIR/", entry.name)
            val outFile = File(modelDir.parentFile, safeName)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
              zipInput.copyTo(fos)
            }
          }
          zipInput.closeEntry()
          entry = zipInput.nextEntry
        }
        zipInput.close()

        Log.d(TAG, "Vosk model downloaded to ${modelDir.absolutePath}")
        withContext(Dispatchers.Main) {
          onDone()
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Vosk model download failed: ${e.message}")
      }
    }
  }

  fun isReady() = ready
}