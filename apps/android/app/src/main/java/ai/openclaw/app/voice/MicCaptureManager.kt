package ai.openclaw.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class VoiceConversationRole {
  User,
  Assistant,
}

data class VoiceConversationEntry(
  val id: String,
  val role: VoiceConversationRole,
  val text: String,
  val isStreaming: Boolean = false,
)

class MicCaptureManager(
  private val context: Context,
  private val scope: CoroutineScope,
  /**
   * Send [message] to the gateway and return the run ID.
   * [onRunIdKnown] is called with the idempotency key *before* the network
   * round-trip so [pendingRunId] is set before any chat events can arrive.
   */
  private val sendToGateway: suspend (message: String, onRunIdKnown: (String) -> Unit) -> String?,
  private val speakAssistantReply: suspend (String) -> Unit = {},
) {
  companion object {
    private const val tag = "MicCapture"
    private const val speechMinSessionMs = 30_000L
    private const val speechCompleteSilenceMs = 1_500L
    private const val speechPossibleSilenceMs = 900L
    private const val transcriptIdleFlushMs = 1_600L
    private const val maxConversationEntries = 40
    private const val pendingRunTimeoutMs = 45_000L
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val json = Json { ignoreUnknownKeys = true }

  private val _micEnabled = MutableStateFlow(false)
  val micEnabled: StateFlow<Boolean> = _micEnabled

  private val _micCooldown = MutableStateFlow(false)
  val micCooldown: StateFlow<Boolean> = _micCooldown

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _statusText = MutableStateFlow("Mic off")
  val statusText: StateFlow<String> = _statusText

  private val _liveTranscript = MutableStateFlow<String?>(null)
  val liveTranscript: StateFlow<String?> = _liveTranscript

  private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
  val queuedMessages: StateFlow<List<String>> = _queuedMessages

  private val _conversation = MutableStateFlow<List<VoiceConversationEntry>>(emptyList())
  val conversation: StateFlow<List<VoiceConversationEntry>> = _conversation

  private val _inputLevel = MutableStateFlow(0f)
  val inputLevel: StateFlow<Float> = _inputLevel

  private val _isSending = MutableStateFlow(false)
  val isSending: StateFlow<Boolean> = _isSending

  private val _speechDetected = MutableStateFlow(false)
  val speechDetected: StateFlow<Boolean> = _speechDetected

  private val _diagnosticsText = MutableStateFlow("diag: idle")
  val diagnosticsText: StateFlow<String> = _diagnosticsText

  private val messageQueue = ArrayDeque<String>()
  private val messageQueueLock = Any()
  private var flushedPartialTranscript: String? = null
  private var pendingRunId: String? = null
  private var pendingAssistantEntryId: String? = null
  private var gatewayConnected = false

  private var recognizer: SpeechRecognizer? = null
  private var voiceInputCapture: VoiceInputCapture? = null
  private var fallbackActive = false
  private var fallbackAudioFrames = mutableListOf<FloatArray>()
  private var fallbackSilenceCounter = 0
  private val fallbackSilenceDuration = 1200
  private var restartJob: Job? = null
  private var drainJob: Job? = null
  private var transcriptFlushJob: Job? = null
  private var pendingRunTimeoutJob: Job? = null
  private var stopRequested = false
  private var speechDecayJob: Job? = null
  private val ttsPauseLock = Any()
  private var ttsPauseDepth = 0
  private var resumeMicAfterTts = false

  private var diagReadyCount = 0
  private var diagBeginCount = 0
  private var diagPartialCount = 0
  private var diagFinalCount = 0
  private var diagErrorCount = 0
  private var diagRestartCount = 0
  private var diagLastError = "none"
  private var diagLastEvent = "idle"
  private var recognizerBusyStreak = 0
  private var recognizerClientStreak = 0

  private fun refreshDiagnostics(event: String) {
    diagLastEvent = event
    val partialLen = _liveTranscript.value?.length ?: 0
    val queueSize = queuedMessageCount()
    _diagnosticsText.value =
      "event=$diagLastEvent | speech=${_speechDetected.value} | level=${"%.2f".format(_inputLevel.value)}\n" +
        "ready=$diagReadyCount begin=$diagBeginCount partial=$diagPartialCount final=$diagFinalCount error=$diagErrorCount restart=$diagRestartCount busyStreak=$recognizerBusyStreak clientStreak=$recognizerClientStreak\n" +
        "listening=${_isListening.value} sending=${_isSending.value} queued=$queueSize partialLen=$partialLen\n" +
        "status=${_statusText.value} | lastError=$diagLastError"
  }

  private fun markSpeechDetected(source: String) {
    _speechDetected.value = true
    speechDecayJob?.cancel()
    speechDecayJob =
      scope.launch {
        delay(1100L)
        _speechDetected.value = false
        refreshDiagnostics("speech-decay:$source")
      }
    refreshDiagnostics(source)
  }

  private fun clearSpeechDetected(source: String) {
    speechDecayJob?.cancel()
    speechDecayJob = null
    _speechDetected.value = false
    refreshDiagnostics(source)
  }

  private fun enqueueMessage(message: String) {
    synchronized(messageQueueLock) {
      messageQueue.addLast(message)
    }
  }

  private fun snapshotMessageQueue(): List<String> {
    return synchronized(messageQueueLock) {
      messageQueue.toList()
    }
  }

  private fun hasQueuedMessages(): Boolean {
    return synchronized(messageQueueLock) {
      messageQueue.isNotEmpty()
    }
  }

  private fun firstQueuedMessage(): String? {
    return synchronized(messageQueueLock) {
      messageQueue.firstOrNull()
    }
  }

  private fun removeFirstQueuedMessage(): String? {
    return synchronized(messageQueueLock) {
      if (messageQueue.isEmpty()) null else messageQueue.removeFirst()
    }
  }

  private fun queuedMessageCount(): Int {
    return synchronized(messageQueueLock) {
      messageQueue.size
    }
  }

  fun setMicEnabled(enabled: Boolean) {
    if (_micEnabled.value == enabled) return
    refreshDiagnostics(if (enabled) "mic-on" else "mic-off")
    _micEnabled.value = enabled
    if (enabled) {
      val pausedForTts =
        synchronized(ttsPauseLock) {
          if (ttsPauseDepth > 0) {
            resumeMicAfterTts = true
            true
          } else {
            false
          }
        }
      if (pausedForTts) {
        _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
        return
      }
      start()
      sendQueuedIfIdle()
    } else {
      // Give the recognizer time to finish processing buffered audio.
      // Cancel any prior drain to prevent duplicate sends on rapid toggle.
      drainJob?.cancel()
      _micCooldown.value = true
      drainJob = scope.launch {
        delay(2000L)
        stop()
        // Capture any partial transcript that didn't get a final result from the recognizer
        val partial = _liveTranscript.value?.trim().orEmpty()
        if (partial.isNotEmpty()) {
          queueRecognizedMessage(partial)
        }
        drainJob = null
        _micCooldown.value = false
        sendQueuedIfIdle()
      }
    }
  }

  fun submitManualTranscript(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    queueRecognizedMessage(trimmed)
    sendQueuedIfIdle()
    refreshDiagnostics("manual-submit")
  }

  suspend fun pauseForTts() {
    val shouldPause =
      synchronized(ttsPauseLock) {
        ttsPauseDepth += 1
        if (ttsPauseDepth > 1) return@synchronized false
        resumeMicAfterTts = _micEnabled.value
        val active = resumeMicAfterTts || recognizer != null || _isListening.value
        if (!active) return@synchronized false
        stopRequested = true
        restartJob?.cancel()
        restartJob = null
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        _isListening.value = false
        _inputLevel.value = 0f
        _liveTranscript.value = null
        _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
        true
      }
    if (!shouldPause) return
    withContext(Dispatchers.Main) {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  suspend fun resumeAfterTts() {
    val shouldResume =
      synchronized(ttsPauseLock) {
        if (ttsPauseDepth == 0) return@synchronized false
        ttsPauseDepth -= 1
        if (ttsPauseDepth > 0) return@synchronized false
        val resume = resumeMicAfterTts && _micEnabled.value
        resumeMicAfterTts = false
        if (!resume) {
          _statusText.value =
            when {
              _micEnabled.value && _isSending.value -> "Listening · sending queued voice"
              _micEnabled.value -> "Listening"
              _isSending.value -> "Mic off · sending…"
              else -> "Mic off"
            }
        }
        resume
      }
    if (!shouldResume) return
    stopRequested = false
    start()
    sendQueuedIfIdle()
  }

  fun onGatewayConnectionChanged(connected: Boolean) {
    gatewayConnected = connected
    if (connected) {
      sendQueuedIfIdle()
      return
    }
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    pendingRunId = null
    pendingAssistantEntryId = null
    _isSending.value = false
    if (hasQueuedMessages()) {
      _statusText.value = queuedWaitingStatus()
    }
  }

  fun handleGatewayEvent(event: String, payloadJson: String?) {
    if (event != "chat") return
    if (payloadJson.isNullOrBlank()) return
    val payload =
      try {
        json.parseToJsonElement(payloadJson).asObjectOrNull()
      } catch (_: Throwable) {
        null
      } ?: return

    val runId = pendingRunId ?: run { Log.d("MicCapture", "no pendingRunId — drop"); return }
    val eventRunId = payload["runId"].asStringOrNull() ?: return
    if (eventRunId != runId) { Log.d("MicCapture", "runId mismatch: event=$eventRunId pending=$runId"); return }

    when (payload["state"].asStringOrNull()) {
      "delta" -> {
        val deltaText = parseAssistantText(payload)
        if (!deltaText.isNullOrBlank()) {
          upsertPendingAssistant(text = deltaText.trim(), isStreaming = true)
        }
      }
      "final" -> {
        val finalText = parseAssistantText(payload)?.trim().orEmpty()
        if (finalText.isNotEmpty()) {
          upsertPendingAssistant(text = finalText, isStreaming = false)
          playAssistantReplyAsync(finalText)
        } else if (pendingAssistantEntryId != null) {
          updateConversationEntry(pendingAssistantEntryId!!, text = null, isStreaming = false)
        }
        completePendingTurn()
      }
      "error" -> {
        val errorMessage = payload["errorMessage"].asStringOrNull()?.trim().orEmpty().ifEmpty { "Voice request failed" }
        upsertPendingAssistant(text = errorMessage, isStreaming = false)
        completePendingTurn()
      }
      "aborted" -> {
        upsertPendingAssistant(text = "Response aborted", isStreaming = false)
        completePendingTurn()
      }
    }
  }

    init {
    // Initialize Vosk ASR in background (downloads model on first run)
    ai.openclaw.app.voice.VoskRecognizer.init(
      context = context,
      scope = scope,
      onReady = {
        Log.d("MicCapture", "[VOSK] Recognizer ready")
      },
    )
  }

  private fun start() {
        stopRequested = false
        recognizerBusyStreak = 0
        refreshDiagnostics("start")

        // Check system STT availability first
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("MicCapture", "System STT unavailable, using fallback")
            startFallbackVoiceCapture()
            return
        }

        if (!hasMicPermission()) {
            // Don't reset _micEnabled here - just show error and let user handle permission
            _statusText.value = "Mic off (permission needed)"
            // Instead of resetting, we should keep the state as-is and let user grant permission
            // But to avoid confusion, we do reset - just don't call start() again
            return
        }

        mainHandler.post {
            try {
                // Always recreate on mic start to avoid stale/busy recognizer instances
                try { recognizer?.cancel() } catch (_: Throwable) { }
                try { recognizer?.destroy() } catch (_: Throwable) { }

                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(listener)
                }
                startListeningSession()
                
                // Force immediate fallback to test AudioRecord
                scope.launch {
                    delay(500L)
                    if (!_isListening.value) {
                        Log.w("MicCapture", "[FALLBACK] Recognizer not listening after 500ms, forcing fallback")
                        startFallbackVoiceCapture()
                    }
                }
            } catch (err: Throwable) {
                Log.w("MicCapture", "System STT failed, switching to fallback: ${err.message}")
                // System STT failed, switch to fallback immediately
                startFallbackVoiceCapture()
            }
        }
    }


  private fun stop() {
    stopRequested = true
    clearSpeechDetected("stop")
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _isListening.value = false
    _statusText.value = if (_isSending.value) "Mic off · sending…" else "Mic off"
    _inputLevel.value = 0f
    if (fallbackActive) {
      stopFallbackVoiceCapture()
    }
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun startListeningSession() {
    val recognizerInstance = recognizer ?: return
    refreshDiagnostics("start-listening")
    val localeTag = Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, speechMinSessionMs)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, speechCompleteSilenceMs)
        putExtra(
          RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
          speechPossibleSilenceMs,
        )
      }
    _statusText.value = "Starting recognizer…"
    _isListening.value = false
    recognizerInstance.startListening(intent)
  }

  private fun recreateRecognizer(reason: String) {
    mainHandler.post {
      if (stopRequested || !_micEnabled.value) return@post
      try {
        recognizer?.cancel()
      } catch (_: Throwable) {
      }
      try {
        recognizer?.destroy()
      } catch (_: Throwable) {
      }
      recognizer = null
      try {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
      } catch (_: Throwable) {
      }
      refreshDiagnostics("recreate:$reason")
    }
  }

  private fun scheduleRestart(delayMs: Long = 300L) {
    if (stopRequested) return
    if (!_micEnabled.value) return
    restartJob?.cancel()
    restartJob =
      scope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested || !_micEnabled.value) return@post
          try {
            diagRestartCount += 1
            refreshDiagnostics("restart:$delayMs")
            startListeningSession()
          } catch (_: Throwable) {
            // retry through onError
          }
        }
      }
  }

  private fun startFallbackVoiceCapture() {
    if (fallbackActive) return
    Log.d("MicCapture", "[FALLBACK] Starting fallback voice capture")
    fallbackActive = true
    fallbackAudioFrames.clear()
    fallbackSilenceCounter = 0
    _statusText.value = "Listening (backup mode)"
    _isListening.value = true
    diagReadyCount += 1
    refreshDiagnostics("fallback-start")

    val capture =
      VoiceInputCapture(
        context = context,
        scope = scope,
        onAudioFrame = { frame ->
          fallbackAudioFrames.add(frame.copyOf())
          if (fallbackAudioFrames.size > 16_000) {
            fallbackAudioFrames.removeAt(0)
          }
        },
        onLevelChanged = { level ->
          _inputLevel.value = level
          if (level > 0.08f) {
            fallbackSilenceCounter = 0
            markSpeechDetected("fallback-audio")
          } else {
            fallbackSilenceCounter += 1
          }
        },
        onAsrFeed = { pcmBytes ->
          // Feed raw PCM to Vosk recognizer
          ai.openclaw.app.voice.VoskRecognizer.feedPcm(pcmBytes)
        },
      )

    // Wire up Vosk transcript handler
    ai.openclaw.app.voice.VoskRecognizer.onTranscript = { text, isFinal ->
      if (isFinal && text.isNotBlank()) {
        queueRecognizedMessage(text)
        sendQueuedIfIdle()
        diagFinalCount += 1
        refreshDiagnostics("vosk-final")
      }
    }

    // Start capture and check result
    if (!capture.startCapture()) {
      Log.e("MicCapture", "[FALLBACK] startCapture() returned false — AudioRecord init failed")
      _statusText.value = "Mic unavailable"
      _isListening.value = false
      _micEnabled.value = false
      fallbackActive = false
      return
    }
    Log.d("MicCapture", "[FALLBACK] startCapture() returned true — capture is running")
    voiceInputCapture = capture
  }

  private fun stopFallbackVoiceCapture() {
    if (!fallbackActive) return
    fallbackActive = false
    voiceInputCapture?.stopCapture()
    voiceInputCapture = null
    _inputLevel.value = 0f

    // Flush Vosk to get any remaining final result
    ai.openclaw.app.voice.VoskRecognizer.flush()

    fallbackAudioFrames.clear()
    fallbackSilenceCounter = 0
    clearSpeechDetected("fallback-stop")
  }

  private fun queueRecognizedMessage(text: String) {
    val message = text.trim()
    _liveTranscript.value = null
    if (message.isEmpty()) return
    appendConversation(
      role = VoiceConversationRole.User,
      text = message,
    )
    enqueueMessage(message)
    publishQueue()
  }

  private fun scheduleTranscriptFlush(expectedText: String) {
    transcriptFlushJob?.cancel()
    transcriptFlushJob =
      scope.launch {
        delay(transcriptIdleFlushMs)
        if (!_micEnabled.value || _isSending.value) return@launch
        val current = _liveTranscript.value?.trim().orEmpty()
        if (current.isEmpty() || current != expectedText) return@launch
        flushedPartialTranscript = current
        queueRecognizedMessage(current)
        sendQueuedIfIdle()
      }
  }

  private fun publishQueue() {
    _queuedMessages.value = snapshotMessageQueue()
  }

  private fun sendQueuedIfIdle() {
    if (_isSending.value) return
    if (!hasQueuedMessages()) {
      if (!_micEnabled.value) {
        _statusText.value = "Mic off"
      }
      // Keep current diagnostic/listening/error status while mic is enabled.
      return
    }
    if (!gatewayConnected) {
      _statusText.value = queuedWaitingStatus()
      return
    }

    val next = firstQueuedMessage() ?: return
    _isSending.value = true
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    _statusText.value = if (_micEnabled.value) "Listening · sending queued voice" else "Sending queued voice"

    scope.launch {
      try {
        val runId = sendToGateway(next) { earlyRunId ->
          // Called with the idempotency key before chat.send fires so that
          // pendingRunId is populated before any chat events can arrive.
          pendingRunId = earlyRunId
        }
        // Update to the real runId if the gateway returned a different one.
        if (runId != null && runId != pendingRunId) pendingRunId = runId
        if (runId == null) {
          pendingRunTimeoutJob?.cancel()
          pendingRunTimeoutJob = null
          removeFirstQueuedMessage()
          publishQueue()
          _isSending.value = false
          pendingAssistantEntryId = null
          sendQueuedIfIdle()
        } else {
          armPendingRunTimeout(runId)
        }
      } catch (err: Throwable) {
        pendingRunTimeoutJob?.cancel()
        pendingRunTimeoutJob = null
        _isSending.value = false
        pendingRunId = null
        pendingAssistantEntryId = null
        _statusText.value =
          if (!gatewayConnected) {
            queuedWaitingStatus()
          } else {
            "Send failed: ${err.message ?: err::class.simpleName}"
          }
      }
    }
  }

  private fun armPendingRunTimeout(runId: String) {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob =
      scope.launch {
        delay(pendingRunTimeoutMs)
        if (pendingRunId != runId) return@launch
        pendingRunId = null
        pendingAssistantEntryId = null
        _isSending.value = false
        _statusText.value =
          if (gatewayConnected) {
            "Voice reply timed out; retrying queued turn"
          } else {
            queuedWaitingStatus()
          }
        sendQueuedIfIdle()
      }
  }

  private fun completePendingTurn() {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    if (removeFirstQueuedMessage() != null) {
      publishQueue()
    }
    pendingRunId = null
    pendingAssistantEntryId = null
    _isSending.value = false
    sendQueuedIfIdle()
  }

  private fun queuedWaitingStatus(): String {
    return "${queuedMessageCount()} queued · waiting for gateway"
  }

  private fun appendConversation(
    role: VoiceConversationRole,
    text: String,
    isStreaming: Boolean = false,
  ): String {
    val id = UUID.randomUUID().toString()
    _conversation.value =
      (_conversation.value + VoiceConversationEntry(id = id, role = role, text = text, isStreaming = isStreaming))
        .takeLast(maxConversationEntries)
    return id
  }

  private fun updateConversationEntry(id: String, text: String?, isStreaming: Boolean) {
    val current = _conversation.value
    if (current.isEmpty()) return

    val targetIndex =
      when {
        current[current.lastIndex].id == id -> current.lastIndex
        else -> current.indexOfFirst { it.id == id }
      }
    if (targetIndex < 0) return

    val entry = current[targetIndex]
    val updatedText = text ?: entry.text
    if (updatedText == entry.text && entry.isStreaming == isStreaming) return
    val updated = current.toMutableList()
    updated[targetIndex] = entry.copy(text = updatedText, isStreaming = isStreaming)
    _conversation.value = updated
  }

  private fun upsertPendingAssistant(text: String, isStreaming: Boolean) {
    val currentId = pendingAssistantEntryId
    if (currentId == null) {
      pendingAssistantEntryId =
        appendConversation(
          role = VoiceConversationRole.Assistant,
          text = text,
          isStreaming = isStreaming,
        )
      return
    }
    updateConversationEntry(id = currentId, text = text, isStreaming = isStreaming)
  }

  private fun playAssistantReplyAsync(text: String) {
    val spoken = text.trim()
    if (spoken.isEmpty()) return
    scope.launch {
      try {
        speakAssistantReply(spoken)
      } catch (err: Throwable) {
        Log.w(tag, "assistant speech failed: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private fun disableMic(status: String) {
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _micEnabled.value = false
    _isListening.value = false
    _inputLevel.value = 0f
    _statusText.value = status
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun hasMicPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  private fun parseAssistantText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"] as? JsonArray ?: return null

    val parts =
      content.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        if (obj["type"].asStringOrNull() != "text") return@mapNotNull null
        obj["text"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
      }
    if (parts.isEmpty()) return null
    return parts.joinToString("\n")
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        _isListening.value = true
        recognizerBusyStreak = 0
        recognizerClientStreak = 0
        _statusText.value =
          when {
            _isSending.value -> "Listening · sending queued voice"
            hasQueuedMessages() -> "Listening · ${queuedMessageCount()} queued"
            else -> "Listening"
          }
        diagReadyCount += 1
        refreshDiagnostics("ready")
      }

      override fun onBeginningOfSpeech() {
        diagBeginCount += 1
        if (_micEnabled.value) {
          _statusText.value = "Hearing voice"
          markSpeechDetected("begin-speech")
        } else {
          refreshDiagnostics("begin-speech-while-disabled")
        }
      }

      override fun onRmsChanged(rmsdB: Float) {
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        _inputLevel.value = level
        if (_micEnabled.value && level > 0.08f) {
          markSpeechDetected("rms")
        }
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        _inputLevel.value = 0f
        if (_micEnabled.value) {
          _statusText.value = "Processing voice…"
          refreshDiagnostics("end-speech")
        }
      }

      override fun onError(error: Int) {
        if (stopRequested) return
        _isListening.value = false
        _inputLevel.value = 0f
        val status =
          when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> {
              recognizerClientStreak += 1
              "Client error (#$recognizerClientStreak)"
            }
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "Listening"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              recognizerBusyStreak += 1
              "Recognizer busy (#$recognizerBusyStreak)"
            }
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported on this device"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable on this device"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech requests limited; retrying"
            else -> "Speech error ($error)"
          }
        _statusText.value = status
        clearSpeechDetected("error")

        if (
          error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        ) {
          disableMic(status)
          return
        }

        diagErrorCount += 1
        diagLastError = "code=$error:$status"
        refreshDiagnostics("error")

        val restartDelayMs =
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> 1_200L
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2_500L
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> (1500L + recognizerBusyStreak * 1200L).coerceAtMost(10_000L)
            SpeechRecognizer.ERROR_CLIENT -> (1800L + recognizerClientStreak * 1200L).coerceAtMost(10_000L)
            else -> 600L
          }

        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
          _statusText.value = "Recognizer busy, retrying…"
          recreateRecognizer("busy")
        }
        if (error == SpeechRecognizer.ERROR_CLIENT) {
          _statusText.value = "Recognizer client error, switching to fallback…"
          recreateRecognizer("client")
          if (recognizerClientStreak >= 3) {
            scope.launch {
              delay(2000L)
              if (fallbackActive || !_micEnabled.value) return@launch
              startFallbackVoiceCapture()
            }
          }
        }

        scheduleRestart(delayMs = restartDelayMs)
      }

      override fun onResults(results: Bundle?) {
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          val trimmed = text.trim()
          if (trimmed != flushedPartialTranscript) {
            queueRecognizedMessage(trimmed)
            sendQueuedIfIdle()
            diagFinalCount += 1
            refreshDiagnostics("final")
          } else {
            flushedPartialTranscript = null
            _liveTranscript.value = null
            refreshDiagnostics("final-duplicate")
          }
        } else {
          refreshDiagnostics("final-empty")
        }
        clearSpeechDetected("final-done")
        scheduleRestart()
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          val trimmed = text.trim()
          _liveTranscript.value = trimmed
          diagPartialCount += 1
          if (_micEnabled.value) {
            _statusText.value = "Hearing voice"
            markSpeechDetected("partial")
          } else {
            refreshDiagnostics("partial-while-disabled")
          }
          scheduleTranscriptFlush(trimmed)
        } else {
          refreshDiagnostics("partial-empty")
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? =
  this as? JsonObject

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content
