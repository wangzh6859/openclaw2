package ai.openclaw.app.voice

import android.content.Context
import android.util.Log
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Hybrid voice capture manager.
 * Primary: VoiceInputCapture + Backend ASR (bypasses broken system STT)
 * Fallback: System SpeechRecognizer (if available)
 *
 * This is the Voice V2 implementation that fixes ERROR_CLIENT (code=5) issues.
 */
class HybridVoiceCapture(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: GatewaySession?,
    private val onTranscription: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLevelChanged: (Float) -> Unit = {},
) {
    companion object {
        private const val TAG = "HybridVoiceCapture"
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _statusText = MutableStateFlow("Mic off")
    val statusText: StateFlow<String> = _statusText

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private var voiceInputCapture: VoiceInputCapture? = null
    private var vadBuffer: VadAwareBuffer? = null
    private var asrClient: BackendAsrClient? = null
    private var useDirectCapture = true // Default to direct capture (Voice V2)

    private var pendingTranscription: String? = null

    fun start() {
        Log.d(TAG, "start() - useDirectCapture=$useDirectCapture")

        if (useDirectCapture) {
            startDirectCapture()
        } else {
            // Fallback to system recognizer (VoiceWakeManager)
            // This would be implemented by delegating to VoiceWakeManager
            onStatus("System STT fallback not implemented yet")
        }
    }

    fun stop() {
        Log.d(TAG, "stop()")
        voiceInputCapture?.stopCapture()
        voiceInputCapture = null
        vadBuffer?.reset()
        vadBuffer = null
        _isListening.value = false
        _statusText.value = "Mic off"
        _inputLevel.value = 0f
    }

    private fun startDirectCapture() {
        try {
            _isListening.value = true
            _statusText.value = "Listening (Direct Capture)"

            // Create VAD buffer with callbacks
            vadBuffer = VadAwareBuffer(
                scope = scope,
                onSpeechStart = {
                    Log.d(TAG, "Speech started")
                    _statusText.value = "Listening..."
                },
                onSpeechEnd = { frames ->
                    Log.d(TAG, "Speech ended, ${frames.size} frames")
                    scope.launch {
                        processSpeech(frames)
                    }
                }
            )

            // Create audio capture
            voiceInputCapture = VoiceInputCapture(
                context = context,
                scope = scope,
                onAudioFrame = { frame ->
                    vadBuffer?.processFrame(frame, _inputLevel.value)
                },
                onLevelChanged = { level ->
                    _inputLevel.value = level
                    onLevelChanged(level)
                }
            )

            // Create ASR client with GatewaySession
            asrClient = BackendAsrClient(
                scope = scope,
                session = session,
                onTranscription = { text ->
                    pendingTranscription = text
                    onTranscription(text)
                },
                onStatus = { status ->
                    _statusText.value = status
                }
            )

            // Start capture
            voiceInputCapture?.startCapture()

        } catch (err: Throwable) {
            Log.e(TAG, "Failed to start direct capture: ${err.message}")
            _statusText.value = "Direct capture failed: ${err.message}"
            _isListening.value = false
        }
    }

    private suspend fun processSpeech(frames: List<FloatArray>) {
        Log.d(TAG, "Processing speech: ${frames.size} frames")
        _isSending.value = true
        _statusText.value = "Processing..."

        try {
            // Send to backend ASR
            asrClient?.transcribe(frames)

            // Note: The actual transcription will come via onTranscription callback
            // when the backend responds

        } catch (err: Throwable) {
            Log.e(TAG, "Speech processing error: ${err.message}")
            _statusText.value = "Processing failed"
        } finally {
            _isSending.value = false
        }
    }

    /**
     * Set whether to use direct capture (Voice V2) or system recognizer.
     * Direct capture bypasses the broken system STT.
     */
    fun setUseDirectCapture(useDirect: Boolean) {
        useDirectCapture = useDirect
        Log.d(TAG, "setUseDirectCapture($useDirect)")
    }
}
