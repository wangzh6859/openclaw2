package ai.openclaw.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * On-device ASR engine switcher.
 *
 * Strategy:
 * 1. Google Speech Services (via system SpeechRecognizer) — tried first
 * 2. Vosk offline Chinese model — fallback after 3 consecutive Google CLIENT errors
 *
 * Both engines accept AudioRecord PCM via [onAudioFrame].  The Google engine uses the
 * standard Android listening flow; Vosk (not yet fully integrated) is a stub for now.
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
    // Threshold of consecutive CLIENT errors before switching to Vosk
    private const val GOOGLE_ERROR_STREAK_THRESHOLD = 3
  }

  // ── State ────────────────────────────────────────────────────────────────────

  private var recognizer: SpeechRecognizer? = null
  private var clientErrorStreak = 0
  private var voskActive = false

  // ── Public API ──────────────────────────────────────────────────────────────

  /** Returns true if the Google recognizer started successfully. */
  fun startGoogle(): Boolean {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      Log.w(TAG, "Google SpeechRecognizer not available on this device")
      return false
    }
    try {
      recognizer?.destroy()
      recognizer = SpeechRecognizer.createSpeechRecognizer(context)
      return true
    } catch (err: Throwable) {
      Log.e(TAG, "createSpeechRecognizer failed: ${err.message}")
      return false
    }
  }

  /**
   * Start continuous listening.
   *
   * For Google: starts a listening session and keeps re-starting after each result.
   * For Vosk: a no-op stub (Vosk integration is TODO).
   */
  fun startListening() {
    if (voskActive) {
      Log.w(TAG, "Vosk listening is a stub — nothing to do")
      return
    }
    startGoogleListening()
  }

  fun stop() {
    try {
      recognizer?.stopListening()
      recognizer?.cancel()
      recognizer?.destroy()
    } catch (_: Throwable) { }
    recognizer = null
  }

  // ── Google implementation ───────────────────────────────────────────────────

  private fun startGoogleListening() {
    val rec = recognizer ?: run {
      if (!startGoogle()) {
        onError("Google SpeechRecognizer unavailable")
        return
      }
      recognizer!!
    }

    val locale = java.util.Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Request offline if available
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      }

    rec.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        clientErrorStreak = 0
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
        clientErrorStreak = 0
        // Re-start for next utterance
        if (!voskActive) {
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
        Log.w(TAG, "Google onError: $errorName (streak=$clientErrorStreak)")

        val isClientError =
          error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

        if (isClientError) {
          clientErrorStreak++
          if (clientErrorStreak >= GOOGLE_ERROR_STREAK_THRESHOLD) {
            Log.w(TAG, "Google error streak reached $GOOGLE_ERROR_STREAK_THRESHOLD — switching to Vosk")
            switchToVosk()
            return
          }
        }

        // Retry after transient errors
        scope.launch {
          delay(800L)
          startGoogleListening()
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

  private fun switchToVosk() {
    recognizer?.cancel()
    recognizer?.destroy()
    recognizer = null
    voskActive = true
    onError("Vosk ASR is not yet integrated — using Google Speech Services only")
    onReady()
  }
}
