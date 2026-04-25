package ai.openclaw.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * On-device ASR with priority: Google Speech Services → Vosk offline.
 *
 * Flow:
 * 1. Try Google SpeechRecognizer first (uses Google Speech Services if available)
 * 2. On 3 consecutive CLIENT/PERMISSION/BUSY errors, switch to Vosk
 * 3. Vosk runs offline Chinese model, self-downloads on first run
 */
class OnDeviceAsr(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onTranscript: (String, isFinal: Boolean) -> Unit,
  private val onError: (String) -> Unit,
  private val onReady: () -> Unit,
) {
  companion object {
    private const val TAG = "OnDeviceAsr"
    private const val GOOGLE_ERROR_STREAK_THRESHOLD = 3
    // Vosk Chinese model — downloaded at runtime from alphacephei.com
    private const val VOSK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-cn-0.3.zip"
    private const val VOSK_MODEL_DIR = "vosk-model-cn-0.3"
    private const val VOSK_SAMPLE_RATE = 16000f
  }

  // ── Google ─────────────────────────────────────────────────────────────────

  private var googleRecognizer: SpeechRecognizer? = null
  private var googleClientStreak = 0
  private var googleActive = false

  // ── Vosk ───────────────────────────────────────────────────────────────────

  private var voskService: SpeechService? = null
  private var voskRecognizer: Recognizer? = null
  private var voskModel: Model? = null
  private var voskActive = false

  // ── Engine selection ──────────────────────────────────────────────────────

  private var activeEngine: String = "google"

  /** Start Google SpeechRecognizer. Returns true if it started. */
  fun startGoogle(): Boolean {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      Log.w(TAG, "Google SpeechRecognizer not available")
      return false
    }
    try {
      googleRecognizer?.destroy()
      googleRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
      activeEngine = "google"
      return true
    } catch (err: Throwable) {
      Log.e(TAG, "createSpeechRecognizer failed: ${err.message}")
      return false
    }
  }

  /** Start listening with current engine. */
  fun startListening() {
    when (activeEngine) {
      "google" -> startGoogleListening()
      "vosk" -> startVoskListening()
    }
  }

  fun stop() {
    try {
      googleRecognizer?.stopListening()
      googleRecognizer?.cancel()
      googleRecognizer?.destroy()
    } catch (_: Throwable) { }
    googleRecognizer = null

    try {
      voskService?.stop()
      voskService?.shutdown()
      voskService = null
      voskRecognizer?.close()
      voskRecognizer = null
      voskModel?.close()
      voskModel = null
    } catch (_: Throwable) { }
  }

  // ── Google implementation ─────────────────────────────────────────────────

  private fun startGoogleListening() {
    val rec = googleRecognizer ?: run {
      if (!startGoogle()) {
        onError("Google SpeechRecognizer unavailable")
        return
      }
      googleRecognizer!!
    }

    val locale = java.util.Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      }

    googleActive = true
    rec.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        googleClientStreak = 0
      }

      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsDb: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}

      override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
          ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull()
          ?.trim()
        if (!text.isNullOrBlank()) {
          onTranscript(text, false)
        }
      }

      override fun onResults(results: Bundle?) {
        val text = results
          ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull()
          ?.trim()
        if (!text.isNullOrBlank()) {
          onTranscript(text, true)
        }
        googleClientStreak = 0
        // Re-start for next utterance
        if (activeEngine == "google" && googleActive) {
          scope.launch {
            delay(200L)
            startGoogleListening()
          }
        }
      }

      override fun onError(error: Int) {
        val errorName = when (error) {
          SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
          SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
          SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSION"
          SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
          SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
          SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
          SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
          SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
          else -> "UNKNOWN($error)"
        }
        Log.w(TAG, "Google onError: $errorName (streak=$googleClientStreak)")

        val isFatalError =
          error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

        if (isFatalError) {
          googleClientStreak++
          if (googleClientStreak >= GOOGLE_ERROR_STREAK_THRESHOLD) {
            Log.w(TAG, "Google error streak=$googleClientStreak → switching to Vosk")
            switchToVosk()
            return
          }
        }

        scope.launch {
          delay(800L)
          if (activeEngine == "google") startGoogleListening()
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    try {
      rec.startListening(intent)
    } catch (err: Throwable) {
      Log.e(TAG, "startListening failed: ${err.message}")
      switchToVosk()
    }
  }

  // ── Vosk implementation ─────────────────────────────────────────────────────

  private fun startVoskListening() {
    val modelDir = File(context.filesDir, VOSK_MODEL_DIR)

    if (!modelDir.exists()) {
      // Download and extract model
      _downloadVoskModel(modelDir) { success ->
        if (success) {
          _initAndStartVosk(modelDir)
        } else {
          onError("Vosk model download failed")
        }
      }
    } else {
      _initAndStartVosk(modelDir)
    }
  }

  private fun _initAndStartVosk(modelDir: File) {
    try {
      val listener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(partial: String?) {
          if (!partial.isNullOrBlank()) {
            onTranscript(partial, false)
          }
        }

        override fun onResult(result: String?) {
          if (!result.isNullOrBlank()) {
            // Vosk result format: {"result" : [{"word": "你好", "conf": 0.95, "start": 0.0, "end": 1.0}], "text": "你好"}
            onTranscript(result, true)
          }
        }

        override fun onFinalResult(result: String?) {
          onResult(result)
        }

        override fun onError(e: Exception?) {
          Log.e(TAG, "Vosk error: ${e?.message}")
        }

        override fun onTimeout() {
          Log.w(TAG, "Vosk timeout")
          // Restart listening
          if (voskActive) {
            scope.launch {
              delay(500L)
              if (voskActive) startVoskListening()
            }
          }
        }
      }

      val model = Model(modelDir.absolutePath)
      val recognizer = Recognizer(model, VOSK_SAMPLE_RATE)
      voskModel = model
      voskRecognizer = recognizer
      voskService = SpeechService(recognizer, VOSK_SAMPLE_RATE)
      voskService?.startListening(listener)
      voskActive = true
      activeEngine = "vosk"
      onReady()
      Log.d(TAG, "Vosk SpeechService started")
    } catch (err: Throwable) {
      Log.e(TAG, "Vosk init failed: ${err.message}")
      onError("Vosk init failed: ${err.message}")
    }
  }

  private fun _downloadVoskModel(modelDir: File, onComplete: (Boolean) -> Unit) {
    scope.launch {
      try {
        val url = java.net.URL(VOSK_MODEL_URL)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.doInput = true

        val inputStream: InputStream = connection.inputStream
        val zipStream = ZipInputStream(inputStream)

        // Extract to filesDir
        var entry = zipStream.nextEntry
        while (entry != null) {
          val fileName = entry.name
          if (fileName.endsWith("/")) {
            // Directory entry — skip
          } else {
            // Strip model prefix if present
            val safeName = fileName.substringAfter("$VOSK_MODEL_DIR/").ifBlank { fileName }
            val outFile = File(modelDir.parentFile, safeName)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
              zipStream.copyTo(fos)
            }
          }
          zipStream.closeEntry()
          entry = zipStream.nextEntry
        }
        zipStream.close()

        withContext(Dispatchers.Main) {
          onComplete(true)
        }
      } catch (err: Throwable) {
        Log.e(TAG, "Vosk model download failed: ${err.message}")
        withContext(Dispatchers.Main) {
          onComplete(false)
        }
      }
    }
  }

  private fun switchToVosk() {
    googleActive = false
    googleRecognizer?.cancel()
    googleRecognizer?.destroy()
    googleRecognizer = null
    activeEngine = "vosk"
    onReady()
    startVoskListening()
  }
}
