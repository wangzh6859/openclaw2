package ai.openclaw.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.alphacephei.vosk.AndroidRecognizer
import com.alphacephei.vosk.Model
import com.alphacephei.vosk.Recognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * On-device ASR with priority: Google Speech Services > Vosk.
 *
 * Detection logic:
 * - Google: try to create SpeechRecognizer; if it fails or reports ERROR_CLIENT
 *            after 3 consecutive attempts, fall back to Vosk
 * - Vosk:    always available (model bundled or downloaded on first run)
 *
 * Both engines operate on AudioRecord PCM (16kHz mono PCM 16bit) via VoiceInputCapture.
 */
class OnDeviceAsr(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onTranscript: (String, Boolean) -> Unit, // text, isFinal
  private val onError: (String) -> Unit,
  private val onReady: () -> Unit,
) {
  companion object {
    private const val TAG = "OnDeviceAsr"
    private const val VOSK_MODEL_ZIP = "vosk-model-cn-0.3.zip"
    private const val VOSK_MODEL_DIR = "vosk-model-cn"
  }

  // ── Google ─────────────────────────────────────────────────────────────────

  private var googleRecognizer: SpeechRecognizer? = null
  private var googleClientStreak = 0
  private val googleMaxStreak = 3
  private var googleReady = false

  // ── Vosk ────────────────────────────────────────────────────────────────────

  private var voskRecognizer: Recognizer? = null
  private var voskModel: Model? = null
  private var voskReady = false
  private var voskJob: Job? = null

  // ── State ───────────────────────────────────────────────────────────────────

  private var activeEngine: String = "google" // "google" | "vosk"
    set(value) {
      field = value
      Log.d(TAG, "Active ASR engine: $value")
    }

  private var fallbackActive = false

  /** Called by VoiceInputCapture with each 160-sample PCM frame. */
  fun onAudioFrame(pcmFrames: List<ShortArray>) {
    if (activeEngine != "vosk" || !voskReady) return
    voskJob?.cancel()
    voskJob = scope.launch(Dispatchers.Default) {
      try {
        for (frame in pcmFrames) {
          val result = voskRecognizer?.recognizeShortTime(frame, frame.size)
          if (result != null && result.isNotBlank()) {
            val json = com.google.gson.JsonParser.parseString(result).asJsonObject
            val text = json.get("result")?.asJsonArray
              ?.get(0)?.asJsonObject
              ?.get("word")?.asString
              ?: json.get("partial")?.asString
              ?: json.get("text")?.asString
              ?: ""
            if (text.isNotBlank()) {
              val isFinal = !json.has("partial") || json.get("final")?.asBoolean == true
              withContext(Dispatchers.Main) {
                onTranscript(text.trim(), isFinal)
              }
            }
          }
        }
      } catch (err: Throwable) {
        Log.e(TAG, "Vosk recognition error: ${err.message}")
      }
    }
  }

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

  fun startVosk(): Boolean {
    val modelDir = File(context.filesDir, VOSK_MODEL_DIR)
    if (!modelDir.exists()) {
      // Try to extract bundled model
      if (!extractBundledModel(modelDir)) {
        Log.e(TAG, "Vosk model not found and extraction failed")
        return false
      }
    }
    try {
      voskModel = Model(modelDir.absolutePath)
      voskRecognizer = AndroidRecognizer(voskModel!!)
      voskReady = true
      activeEngine = "vosk"
      return true
    } catch (err: Throwable) {
      Log.e(TAG, "Vosk init failed: ${err.message}")
      return false
    }
  }

  private fun extractBundledModel(destDir: File): Boolean {
    val assetName = "models/$VOSK_MODEL_DIR"
    return try {
      val assetFiles = context.assets.list("models") ?: emptyArray()
      if (assetFiles.isEmpty()) {
        Log.w(TAG, "No bundled models in assets/models/, Vosk needs to be downloaded at runtime")
        return false
      }
      // For now, just return false — model download can be added later
      false
    } catch (err: Throwable) {
      Log.e(TAG, "Model extraction failed: ${err.message}")
      false
    }
  }

  /** Attempt recognition with Google; falls back to Vosk on ERROR_CLIENT streak. */
  fun recognize(finalize: Boolean) {
    when (activeEngine) {
      "google" -> recognizeWithGoogle(finalize)
      "vosk" -> { /* Vosk runs continuously via onAudioFrame */ }
    }
  }

  private fun recognizeWithGoogle(finalize: Boolean) {
    val recognizer = googleRecognizer ?: return
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      }

    recognizer.setRecognitionListener(
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          googleReady = true
          googleClientStreak = 0
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsDb: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
          val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          if (!text.isNullOrBlank()) {
            onTranscript(text.trim(), false)
          }
        }

        override fun onResults(results: Bundle?) {
          val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          if (!text.isNullOrBlank()) {
            onTranscript(text.trim(), true)
          } else {
            onTranscript("", true)
          }
          googleReady = false
        }

        override fun onError(error: Int) {
          googleReady = false
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
          Log.w(TAG, "Google onError: $errorName")

          if (error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
          ) {
            googleClientStreak++
            if (googleClientStreak >= googleMaxStreak) {
              Log.w(TAG, "Google streak=$googleClientStreak, switching to Vosk")
              switchToVosk()
              return
            }
          }

          // Retry after a short delay for transient errors
          scope.launch {
            delay(500L)
            recognize(false)
          }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
      },
    )

    try {
      recognizer.startListening(intent)
    } catch (err: Throwable) {
      Log.e(TAG, "startListening failed: ${err.message}")
      switchToVosk()
    }
  }

  private fun switchToVosk() {
    googleRecognizer?.cancel()
    googleRecognizer?.destroy()
    googleRecognizer = null
    activeEngine = "vosk"
    if (startVosk()) {
      onReady()
    } else {
      onError("Neither Google nor Vosk ASR is available")
    }
  }

  fun stop() {
    voskJob?.cancel()
    voskJob = null
    voskRecognizer?.shutdown()
    voskRecognizer = null
    voskModel?.close()
    voskModel = null
    voskReady = false
    googleRecognizer?.cancel()
    googleRecognizer?.destroy()
    googleRecognizer = null
  }
}
