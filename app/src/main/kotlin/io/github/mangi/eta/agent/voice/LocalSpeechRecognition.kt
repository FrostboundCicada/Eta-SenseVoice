package io.github.mangi.eta.agent.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import io.github.mangi.eta.data.model.LocalAsrConfig
import io.github.mangi.eta.data.model.SpeechSettings
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 本地（离线）语音识别：用 sherpa-onnx 在设备上跑 SenseVoice。
 *
 * 对外保持与流式识别一致的回调形态（就绪、预览、断句），所以上层 [SpeechInputController] 不需要为
 * 「流式」和「本地」写两套状态机：音频仍然由 [SpeechRecorder] 录制，这里只负责边收边预览、
 * 判断是否说完、最后整段解码。整段音频不出设备。
 */
internal suspend fun recognizeLocalSpeech(
    context: Context,
    settings: SpeechSettings,
    frames: ReceiveChannel<ByteArray>,
    onReady: () -> Unit,
    onPreview: (String) -> Unit,
    onEndpoint: () -> Unit,
    onProgress: (String) -> Unit,
    endpointSilenceMs: Int,
): String = withTimeout(90_000) {
    // 首次加载要读 228 MB 模型（约 1～2 秒），必须在开始录音之前完成，否则录音通道会被写满。
    onProgress("正在加载本地模型")
    val engine = withContext(Dispatchers.Default) { LocalSenseVoiceEngines.acquire(context, settings) }
    val gate = LocalEndpointGate(endpointSilenceMs)
    val buffer = PcmBuffer()
    var preview: Job? = null
    var lastPreviewAt = 0L
    var endpointSent = false
    try {
        onReady()
        for (bytes in frames) {
            buffer.append(bytes)
            if (!endpointSent && gate.accept(bytes)) {
                endpointSent = true
                onEndpoint()
            }
            val now = SystemClock.elapsedRealtime()
            if (!endpointSent && preview?.isActive != true && now - lastPreviewAt >= PREVIEW_INTERVAL_MS &&
                buffer.durationMs >= PREVIEW_MIN_MS
            ) {
                lastPreviewAt = now
                val snapshot = buffer.samples(PREVIEW_WINDOW_SAMPLES)
                preview = launch(Dispatchers.Default) {
                    val text = engine.preview(snapshot) ?: return@launch
                    if (text.isNotBlank()) onPreview(text)
                }
            }
        }
    } finally {
        // 预览被取消时若正在解码，原生调用仍会跑完；最终解码排在同一个互斥量后面，不会交错。
        preview?.cancel()
    }
    // 不做「没检测到语音」的提前失败：门槛是启发式的，安静麦克风容易误判；交给解码结果决定。
    onProgress("正在识别")
    val text = engine.transcribe(buffer.samples())
    if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
    text
}

/** SenseVoice 只认这几个语言标签，其它取值（含留空）都交给模型自动判断。 */
internal fun localSpeechLanguage(value: String): String {
    val tag = value.trim().lowercase().substringBefore('-').substringBefore('_')
    return tag.takeIf { it in LocalAsrConfig.LANGUAGES } ?: "auto"
}

/**
 * 常驻的 SenseVoice 识别器。模型加载开销远大于单次解码，因此进程内只保留一个实例；
 * 设置变化（模型路径、线程数、量化格式、语言）时替换，旧实例立即释放。
 */
internal class LocalSenseVoiceEngine private constructor(private val recognizer: OfflineRecognizer) {
    private val mutex = Mutex()

    /** 整段解码，等待正在进行的预览结束，保证结果对应完整音频。 */
    suspend fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        return mutex.withLock { withContext(Dispatchers.Default) { decode(samples) } }
    }

    /** 边录边识别：识别器忙时直接放弃这一轮预览，避免排队把最终结果拖到超时。 */
    suspend fun preview(samples: FloatArray): String? {
        if (samples.isEmpty() || !mutex.tryLock()) return null
        return try {
            withContext(Dispatchers.Default) { decode(samples) }
        } finally {
            mutex.unlock()
        }
    }

    fun close() {
        runCatching { recognizer.release() }
    }

    private fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SpeechRecorder.RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        fun open(model: File, tokens: File, config: LocalAsrConfig, language: String): LocalSenseVoiceEngine {
            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SpeechRecorder.RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = model.absolutePath,
                        language = language,
                        useInverseTextNormalization = config.useInverseTextNormalization,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = config.numThreads,
                    provider = "cpu",
                    debug = false,
                ),
                decodingMethod = "greedy_search",
            )
            val recognizer = try {
                OfflineRecognizer(config = recognizerConfig)
            } catch (error: Throwable) {
                // 缺少原生库、模型与 sherpa-onnx 版本不匹配都在这里暴露，用可读信息替代 UnsatisfiedLinkError。
                throw SpeechFailure(
                    SpeechErrorCode.CONFIGURATION,
                    "本地模型加载失败，请确认模型文件完整：${error.message ?: error.javaClass.simpleName}",
                )
            }
            return LocalSenseVoiceEngine(recognizer)
        }
    }
}

/** 进程内唯一的识别器缓存；同一时刻只允许一路录音，配置变化时直接释放旧实例。 */
internal object LocalSenseVoiceEngines {
    private data class Key(
        val model: String,
        val modelLength: Long,
        val modelModified: Long,
        val tokens: String,
        val tokensLength: Long,
        val tokensModified: Long,
        val threads: Int,
        val normalize: Boolean,
        val language: String,
    )

    private var cachedKey: Key? = null
    private var cachedEngine: LocalSenseVoiceEngine? = null

    @Synchronized
    fun acquire(context: Context, settings: SpeechSettings): LocalSenseVoiceEngine {
        val config = settings.localAsr
        val dir = LocalAsrModelStore.directory(context, config)
        val model = LocalAsrModelStore.modelFile(dir) ?: throw SpeechFailure(
            SpeechErrorCode.CONFIGURATION,
            "未找到 ${LocalAsrConfig.MODEL_FILE}，请先在「本地模型」中下载或导入模型",
        )
        val tokens = LocalAsrModelStore.tokensFile(dir)
        if (!tokens.isFile || tokens.length() <= 0) throw SpeechFailure(
            SpeechErrorCode.CONFIGURATION,
            "未找到 ${LocalAsrConfig.TOKENS_FILE}，请先在「本地模型」中下载或导入词表",
        )
        val language = localSpeechLanguage(settings.language)
        val key = Key(
            model = model.absolutePath,
            modelLength = model.length(),
            modelModified = model.lastModified(),
            tokens = tokens.absolutePath,
            tokensLength = tokens.length(),
            tokensModified = tokens.lastModified(),
            threads = config.numThreads,
            normalize = config.useInverseTextNormalization,
            language = language,
        )
        if (cachedKey == key) cachedEngine?.let { return it }
        cachedEngine?.close()
        cachedEngine = null
        cachedKey = null
        val engine = LocalSenseVoiceEngine.open(model, tokens, config, language)
        cachedKey = key
        cachedEngine = engine
        return engine
    }
}

/** 与录音帧同格式（16 kHz、16 bit、单声道、小端）的整段缓冲区，供预览与最终解码取快照。 */
private class PcmBuffer {
    private val out = ByteArrayOutputStream()

    val durationMs: Int get() = out.size() / BYTES_PER_MS

    fun append(bytes: ByteArray) {
        if (out.size() + bytes.size > SpeechRecorder.MAX_BYTES) {
            throw SpeechFailure(SpeechErrorCode.AUDIO, "录音超过长度限制")
        }
        out.write(bytes)
    }

    fun samples(limit: Int = Int.MAX_VALUE): FloatArray {
        val bytes = out.toByteArray()
        val available = bytes.size / 2
        val count = minOf(available, limit)
        val offset = (available - count) * 2
        return FloatArray(count) { index ->
            val low = bytes[offset + index * 2].toInt() and 255
            val high = bytes[offset + index * 2 + 1].toInt()
            ((low or (high shl 8)).toShort() / 32768f)
        }
    }
}

/** SenseVoice 的 fbank 特征维度。 */
private const val FEATURE_DIM = 80
private const val BYTES_PER_MS = 32
/** 预览重解码间隔：过于频繁会把 CPU 全占满，最终结果反而更慢。 */
private const val PREVIEW_INTERVAL_MS = 1_200L
/** 太短的音频解码没有意义，等积累到 400 ms 再开始预览。 */
private const val PREVIEW_MIN_MS = 400
/** 预览只看最近 8 秒：int8 模型在手机上约 0.2 倍实时，窗口再长就会一直占满 CPU。 */
private const val PREVIEW_WINDOW_SAMPLES = SpeechRecorder.RATE * 8
