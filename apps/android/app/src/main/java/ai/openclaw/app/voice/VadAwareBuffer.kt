package ai.openclaw.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Voice Activity Detection aware audio buffer.
 * Collects PCM frames and detects speech start/end based on energy levels.
 * Buffers audio for backend ASR upload.
 */
class VadAwareBuffer(
    private val scope: CoroutineScope,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: suspend (List<FloatArray>) -> Unit = {},
    private val speechStartThresholdMs: Int = 3, // frames below threshold to trigger start
    private val speechEndThresholdMs: Int = 15,  // frames above threshold to trigger end
    private val preBufferFrames: Int = 50,       // Keep ~500ms pre-roll
) {
    companion object {
        private const val frameSizeMs = 10 // 10ms per frame at 16kHz
    }

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _bufferLevel = MutableStateFlow(0f)
    val bufferLevel: StateFlow<Float> = _bufferLevel

    private val rollingBuffer = ArrayDeque<FloatArray>(preBufferFrames)
    private var speechFrameCount = 0
    private var silenceFrameCount = 0

    /**
     * Process an audio frame and update VAD state.
     * [level] should be normalized 0-1 (0 = silence, 1 = loud).
     */
    fun processFrame(frame: FloatArray, level: Float) {
        // Keep rolling buffer
        if (rollingBuffer.size >= preBufferFrames) {
            rollingBuffer.removeFirst()
        }
        rollingBuffer.addLast(frame)

        _bufferLevel.value = level

        // Simple energy-based VAD
        val speechThreshold = 0.15f // Adjust based on testing

        if (!_isSpeaking.value) {
            // Looking for speech start
            if (level > speechThreshold) {
                speechFrameCount++
                if (speechFrameCount >= speechStartThresholdMs) {
                    _isSpeaking.value = true
                    onSpeechStart()
                }
            } else {
                speechFrameCount = 0
            }
        } else {
            // Speech detected, looking for end
            if (level < speechThreshold) {
                silenceFrameCount++
                if (silenceFrameCount >= speechEndThresholdMs) {
                    // Speech ended
                    _isSpeaking.value = false
                    speechFrameCount = 0
                    silenceFrameCount = 0

                    // Send buffered audio
                    scope.launch {
                        try {
                            onSpeechEnd(rollingBuffer.toList())
                        } catch (_: Exception) { }
                    }
                    rollingBuffer.clear()
                }
            } else {
                silenceFrameCount = 0
            }
        }
    }

    fun reset() {
        rollingBuffer.clear()
        speechFrameCount = 0
        silenceFrameCount = 0
        _isSpeaking.value = false
        _bufferLevel.value = 0f
    }
}
