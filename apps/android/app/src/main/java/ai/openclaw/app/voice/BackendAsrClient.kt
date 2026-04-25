package ai.openclaw.app.voice

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer

/**
 * Backend ASR client - sends audio to gateway for speech-to-text.
 * Uses GatewaySession.request() to call voice.asr RPC method.
 */
class BackendAsrClient(
    private val scope: CoroutineScope,
    private val session: ai.openclaw.app.gateway.GatewaySession?,
    private val onTranscription: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val TAG = "BackendAsrClient"
        private const val ASR_TIMEOUT_MS = 30_000L
    }

    /**
     * Send audio frames to backend for transcription.
     * [frames] is a list of FloatArray PCM 16-bit samples normalized to [-1, 1].
     */
    fun transcribe(frames: List<FloatArray>) {
        scope.launch {
            try {
                onStatus("Sending audio to ASR...")

                // Convert FloatArray frames to PCM 16-bit bytes
                val audioBytes = framesToPcm16(frames)

                // Encode to base64
                val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

                Log.d(TAG, "Sending ${audioBytes.size} bytes to ASR")

                // Call gateway RPC
                val result = performRequest(
                    method = "voice.asr",
                    paramsJson = buildJsonObject {
                        put("audio_base64", audioBase64)
                        put("sample_rate", 16000)
                        put("encoding", "pcm16")
                        put("language", "zh-CN")
                    }.toString(),
                    timeoutMs = ASR_TIMEOUT_MS
                )

                if (result.ok) {
                    Log.d(TAG, "ASR success: ${result.payloadJson}")
                    // Parse transcription from response
                    val transcription = parseTranscription(result.payloadJson)
                    onStatus("Transcribed: $transcription")
                    onTranscription(transcription)
                } else {
                    Log.e(TAG, "ASR failed: ${result.error?.message}")
                    onStatus("ASR failed: ${result.error?.message}")
                }

            } catch (err: Throwable) {
                Log.e(TAG, "ASR error: ${err.message}")
                onStatus("ASR error: ${err.message}")
            }
        }
    }

    private fun parseTranscription(payloadJson: String?): String {
        if (payloadJson.isNullOrBlank()) return ""
        try {
            // Expected response format: { "text": "transcribed text", ... }
            val lines = payloadJson.lines()
            for (line in lines) {
                if (line.trim().startsWith("{")) {
                    // Try to parse as JSON
                    if (line.contains("\"text\"")) {
                        // Simple extraction - in production use proper JSON parser
                        val textMatch = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").find(line)
                        return textMatch?.groupValues?.get(1) ?: ""
                    }
                }
            }
        } catch (_: Exception) { }
        return ""
    }

    private suspend fun performRequest(
        method: String,
        paramsJson: String,
        timeoutMs: Long,
    ): ai.openclaw.app.gateway.GatewaySession.RpcResult {
        val activeSession = session
            ?: throw IllegalStateException("GatewaySession not available")

        return activeSession.request(method, paramsJson, timeoutMs)
    }

    private fun framesToPcm16(frames: List<FloatArray>): ByteArray {
        val totalSamples = frames.sumOf { it.size }
        val buffer = ByteBuffer.allocate(totalSamples * 2) // 2 bytes per sample
        for (frame in frames) {
            for (sample in frame) {
                // Clamp to [-1, 1]
                val clamped = sample.coerceIn(-1f, 1f)
                // Convert to 16-bit signed integer
                val pcm16 = (clamped * 32767f).toInt().coerceIn(-32768, 32767)
                buffer.putShort(pcm16.toShort())
            }
        }
        return buffer.array()
    }
}
