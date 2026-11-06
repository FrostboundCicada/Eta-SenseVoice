package io.github.mangi.eta.agent.voice

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断句判定是纯 Kotlin 逻辑（[LocalEndpointGate]），不依赖录音设备，因此可以像解码器一样单测：
 * 合成的「说话 + 静音」帧序列必须能稳定触发结束，纯静音和极短噪声必须不触发。
 */
class LocalEndpointGateTest {
    @Test fun pureSilenceNeverEndsTheRecording() {
        val gate = LocalEndpointGate(silenceLimitMs = 1_000)
        repeat(60) { assertFalse(gate.accept(silence())) }
        assertFalse(gate.heardSpeech)
    }

    @Test fun speechFollowedBySilenceEndsAfterTheConfiguredPause() {
        val gate = LocalEndpointGate(silenceLimitMs = 1_000)
        repeat(5) { assertFalse(gate.accept(speech())) }
        assertTrue(gate.heardSpeech)
        // 900 ms 静音还不足以判定说完（极限 1000 ms）。
        repeat(9) { assertFalse(gate.accept(silence())) }
        assertTrue(gate.accept(silence()))
    }

    @Test fun zeroLimitDisablesAutomaticEndpointing() {
        val gate = LocalEndpointGate(silenceLimitMs = 0)
        repeat(5) { gate.accept(speech()) }
        repeat(60) { assertFalse(gate.accept(silence())) }
        assertTrue(gate.heardSpeech)
    }

    @Test fun shortNoiseBurstDoesNotCountAsSpeech() {
        val gate = LocalEndpointGate(silenceLimitMs = 500)
        assertFalse(gate.accept(speech())) // 100 ms，短于最短语音长度
        repeat(20) { assertFalse(gate.accept(silence())) }
        assertFalse(gate.heardSpeech)
    }

    @Test fun languageTagFallsBackToAutoForUnknownValues() {
        assertEquals("zh", localSpeechLanguage("zh"))
        assertEquals("zh", localSpeechLanguage("zh-CN"))
        assertEquals("yue", localSpeechLanguage("yue_HK"))
        assertEquals("auto", localSpeechLanguage(""))
        assertEquals("auto", localSpeechLanguage("fr"))
    }

    private fun speech(): ByteArray = pcm(durationMs = 100) { index -> sin(index / 8.0) * 8_000 }

    private fun silence(): ByteArray = pcm(durationMs = 100) { 0.0 }

    private fun pcm(durationMs: Int, sample: (Int) -> Double): ByteArray {
        val count = durationMs * 16
        val bytes = ByteArray(count * 2)
        repeat(count) { index ->
            val value = sample(index).toInt().coerceIn(-32_768, 32_767)
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
