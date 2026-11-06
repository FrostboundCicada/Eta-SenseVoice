package io.github.mangi.eta.agent.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 本地识别的断句判定。
 *
 * 流式接口由服务端 VAD 断句，本地离线模型只能在整段音频上解码，因此这里用「能量阈值 + 连续静音时长」
 * 模拟同样的语义：已经开始说话、并且静音持续到用户设置的停顿时长时才回调结束录音。
 * 阈值跟随环境噪声缓慢自适应，安静房间更容易判停，嘈杂环境自动抬高门槛。
 */
internal class LocalEndpointGate(
    /** 连续静音达到该时长即认为一句话说完；0 表示不做断句，只在用户点击完成或松手时结束。 */
    silenceLimitMs: Int,
) {
    private val limit = silenceLimitMs.coerceAtLeast(0)
    private var noiseFloorDb = INITIAL_NOISE_FLOOR_DB
    private var speechMs = 0
    private var silenceMs = 0

    /** 已经积累到最短语音长度；没有语音时 [accept] 始终返回 false，底噪不会被当成一整句话。 */
    var heardSpeech = false
        private set

    /** 依次喂入录音帧（16 kHz、16 bit、单声道小端），返回此刻是否应该结束录音。 */
    fun accept(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        val frameMs = pcm.size / BYTES_PER_MS
        val level = levelDb(pcm)
        if (level >= noiseFloorDb + MARGIN_DB) {
            speechMs += frameMs
            silenceMs = 0
            if (speechMs >= MIN_SPEECH_MS) heardSpeech = true
        } else {
            // 只在非语音帧上跟踪底噪：语音帧参与平均会把门槛一步步顶高，越说越难判停。
            noiseFloorDb = (noiseFloorDb + (level - noiseFloorDb) * NOISE_FLOOR_ALPHA)
                .coerceIn(MIN_NOISE_FLOOR_DB, MAX_NOISE_FLOOR_DB)
            if (heardSpeech) silenceMs += frameMs
        }
        return limit > 0 && heardSpeech && silenceMs >= limit
    }

    private fun levelDb(pcm: ByteArray): Float {
        var energy = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toDouble()
            energy += sample * sample
            count++
            index += 2
        }
        if (count == 0) return MIN_NOISE_FLOOR_DB
        val rms = sqrt(energy / count)
        if (rms < 1.0) return MIN_NOISE_FLOOR_DB
        return (20 * log10(rms / Short.MAX_VALUE)).toFloat()
    }

    companion object {
        /** 16 kHz、16 bit 单声道：每毫秒 32 字节。 */
        private const val BYTES_PER_MS = 32
        private const val INITIAL_NOISE_FLOOR_DB = -55f
        private const val MIN_NOISE_FLOOR_DB = -75f
        private const val MAX_NOISE_FLOOR_DB = -30f
        private const val MARGIN_DB = 9f
        private const val NOISE_FLOOR_ALPHA = 0.05f
        /** 短于 300 ms 的能量突增多半是咳嗽、敲击或开关噪声，不算说过话。 */
        private const val MIN_SPEECH_MS = 300
    }
}
