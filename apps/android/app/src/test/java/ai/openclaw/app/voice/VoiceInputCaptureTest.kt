package ai.openclaw.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for Voice V2 components.
 * Note: These are basic tests. Full integration testing requires Android instrumentation.
 */
class VoiceV2ComponentsTest {

    @Test
    fun testVadAwareBuffer_basic() = runTest {
        var speechStarted = false
        var speechEnded = false
        var framesReceived = 0

        val vadBuffer = VadAwareBuffer(
            scope = this,
            onSpeechStart = {
                speechStarted = true
            },
            onSpeechEnd = { frames ->
                speechEnded = true
                framesReceived = frames.size
            },
            speechStartThresholdMs = 2,  // Faster for testing
            speechEndThresholdMs = 3,    // Faster for testing
            preBufferFrames = 10
        )

        // Simulate silence
        val silenceFrame = FloatArray(160) { 0.01f }
        for (i in 0..5) {
            vadBuffer.processFrame(silenceFrame, 0.01f)
        }
        assertTrue(!speechStarted, "Should not start on silence")

        // Simulate speech (high energy)
        val speechFrame = FloatArray(160) { 0.5f }
        for (i in 0..5) {
            vadBuffer.processFrame(speechFrame, 0.8f)
        }
        assertTrue(speechStarted, "Should start on speech")

        // Simulate silence again to trigger end
        for (i in 0..5) {
            vadBuffer.processFrame(silenceFrame, 0.01f)
        }

        // Give coroutine time to process
        delay(100)

        assertTrue(speechEnded, "Should end after silence")
        assertTrue(framesReceived > 0, "Should receive frames")
    }

    @Test
    fun testVadAwareBuffer_reset() = runTest {
        var speechStarted = false
        val vadBuffer = VadAwareBuffer(
            scope = this,
            onSpeechStart = { speechStarted = true },
            onSpeechEnd = { _ -> },
            speechStartThresholdMs = 2,
            speechEndThresholdMs = 3,
            preBufferFrames = 10
        )

        // Trigger speech start
        val speechFrame = FloatArray(160) { 0.5f }
        for (i in 0..5) {
            vadBuffer.processFrame(speechFrame, 0.8f)
        }
        assertTrue(speechStarted, "Should start on speech")

        // Reset
        vadBuffer.reset()

        // Should be able to start again
        speechStarted = false
        for (i in 0..5) {
            vadBuffer.processFrame(speechFrame, 0.8f)
        }
        assertTrue(speechStarted, "Should start again after reset")
    }

    @Test
    fun testVoiceInputCapture_constants() {
        // Verify audio constants
        assertEquals(16_000, VoiceInputCapture.sampleRate, "Sample rate should be 16kHz")
        assertEquals(160, VoiceInputCapture.frameSize, "Frame size should be 160 samples (10ms)")
    }
}
