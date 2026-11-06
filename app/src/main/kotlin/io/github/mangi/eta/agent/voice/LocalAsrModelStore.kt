package io.github.mangi.eta.agent.voice

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.LocalAsrConfig
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/** 本地模型目录的现状，供设置页展示。 */
internal data class LocalModelStatus(
    val directory: File,
    val ready: Boolean,
    /** 已存在文件的合计字节数；未就绪时用于显示「下载到一半」的进度。 */
    val bytes: Long,
    val missing: List<String>,
)

/**
 * SenseVoice 模型文件的落盘管理。
 *
 * sherpa-onnx 需要真实文件路径（不能读 asset），因此模型统一放在应用私有目录，再由设置页提供两条入口：
 * 从 SAF 文件夹导入（用户自己下载的模型）或从 HuggingFace 直接下载。两种入口都写入同一目录，
 * 导入/下载完成后立刻校验，缺失就抛出可读的失败原因。
 */
internal object LocalAsrModelStore {
    /** csukuangfj 发布的 SenseVoice 多语种 int8 导出，与 sherpa-onnx 的 SenseVoice 配置一致。 */
    const val MODEL_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
    const val TOKENS_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"

    /** 官方文件大小，用于下载后校验完整性与预估所需空间。 */
    const val EXPECTED_MODEL_BYTES = 239_233_841L
    const val EXPECTED_TOKENS_BYTES = 315_894L

    fun defaultDirectory(context: Context): File = File(context.filesDir, "models/sense-voice")

    /** 设置里留空表示应用私有目录；填了绝对路径则完全尊重用户的选择。 */
    fun directory(context: Context, config: LocalAsrConfig): File {
        val custom = config.modelDir.trim()
        return if (custom.isEmpty()) defaultDirectory(context) else File(custom)
    }

    fun modelFile(dir: File): File? = listOf(LocalAsrConfig.MODEL_FILE, LocalAsrConfig.MODEL_FILE_FULL)
        .map { File(dir, it) }
        .firstOrNull { it.isFile && it.length() > 0 }

    fun tokensFile(dir: File): File = File(dir, LocalAsrConfig.TOKENS_FILE)

    fun isReady(dir: File): Boolean = modelFile(dir) != null && tokensFile(dir).let { it.isFile && it.length() > 0 }

    fun inspect(context: Context, config: LocalAsrConfig): LocalModelStatus {
        val dir = directory(context, config)
        val missing = buildList {
            if (modelFile(dir) == null) add(LocalAsrConfig.MODEL_FILE)
            if (!tokensFile(dir).isFile) add(LocalAsrConfig.TOKENS_FILE)
        }
        val bytes = (modelFile(dir)?.length() ?: 0L) + tokensFile(dir).length()
        return LocalModelStatus(dir, missing.isEmpty(), bytes, missing)
    }

    /** 保存设置与开始识别前都会经过这里，失败信息直接面向上层展示。 */
    fun validate(context: Context, config: LocalAsrConfig) {
        val status = inspect(context, config)
        if (status.ready) return
        throw SpeechFailure(
            SpeechErrorCode.CONFIGURATION,
            "本地模型尚未就绪，请先下载模型，或从本地文件夹导入 ${status.missing.joinToString(" 与 ")}",
        )
    }

    /** 从 SAF 目录导入：只拷贝 SenseVoice 需要的两个文件，其余内容一概不碰。 */
    suspend fun import(
        context: Context,
        tree: Uri,
        config: LocalAsrConfig,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = directory(context, config)
        ensureDirectory(dir, EXPECTED_MODEL_BYTES + EXPECTED_TOKENS_BYTES)
        val resolver = context.contentResolver
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: throw SpeechFailure(SpeechErrorCode.STORAGE, "无法读取所选文件夹")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        var copied = 0
        val cursor = runCatching { resolver.query(children, projection, null, null, null) }.getOrNull()
            ?: throw SpeechFailure(SpeechErrorCode.STORAGE, "无法读取所选文件夹")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                if (name !in IMPORTABLE) continue
                val size = it.getLong(2)
                val source = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                onProgress("正在导入 $name（${megabytes(size)}）")
                copy(resolver, source, File(dir, name), size) { read, total ->
                    onProgress("正在导入 $name ${percent(read, total)}")
                }
                // 两种量化格式只保留一份，避免选中的旧模型盖住新导入的版本。
                IMPORTABLE.filter { other -> other != name && other != LocalAsrConfig.TOKENS_FILE }
                    .forEach { other -> File(dir, other).delete() }
                copied++
            }
        }
        if (copied == 0) {
            throw SpeechFailure(SpeechErrorCode.STORAGE, "所选文件夹里没有 model.int8.onnx 或 tokens.txt")
        }
        validate(context, config)
    }

    /** 直接从 HuggingFace 下载官方文件；先写 .part 再改名，中断不会留下半截模型。 */
    suspend fun download(context: Context, config: LocalAsrConfig, onProgress: (String) -> Unit) {
        val dir = directory(context, config)
        withContext(Dispatchers.IO) {
            ensureDirectory(dir, EXPECTED_MODEL_BYTES + EXPECTED_TOKENS_BYTES)
        }
        onProgress("正在下载模型（约 228 MB）")
        downloadFile(MODEL_URL, File(dir, LocalAsrConfig.MODEL_FILE), EXPECTED_MODEL_BYTES) { read, total ->
            onProgress("正在下载模型 ${percent(read, total)} · ${megabytes(read)} / ${megabytes(total)}")
        }
        onProgress("正在下载词表")
        downloadFile(TOKENS_URL, tokensFile(dir), EXPECTED_TOKENS_BYTES) { read, total ->
            onProgress("正在下载词表 ${percent(read, total)}")
        }
        validate(context, config)
    }

    /** 只删除本地模型文件，不动用户自定义目录里的其它内容。 */
    fun delete(context: Context, config: LocalAsrConfig) {
        val dir = directory(context, config)
        IMPORTABLE.forEach { File(dir, it).delete() }
        IMPORTABLE.forEach { File(dir, "$it.part").delete() }
        if (dir.canonicalPath == defaultDirectory(context).canonicalPath) dir.delete()
    }

    private fun ensureDirectory(dir: File, needed: Long) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw SpeechFailure(SpeechErrorCode.STORAGE, "无法创建模型目录 ${dir.name}")
        }
        if (needed > 0 && dir.usableSpace in 1 until needed) {
            throw SpeechFailure(SpeechErrorCode.STORAGE, "存储空间不足，至少需要 ${megabytes(needed)}")
        }
    }

    private fun copy(
        resolver: ContentResolver,
        source: Uri,
        target: File,
        size: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            resolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total, size)
                    }
                }
            } ?: throw SpeechFailure(SpeechErrorCode.STORAGE, "无法读取 ${target.name}")
            if (temp.length() <= 0) throw SpeechFailure(SpeechErrorCode.STORAGE, "${target.name} 是空文件")
            publish(temp, target)
        } catch (error: Exception) {
            temp.delete()
            throw if (error is SpeechFailure) error
            else SpeechFailure(SpeechErrorCode.STORAGE, "导入 ${target.name} 失败：${error.message ?: "未知错误"}")
        }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        expected: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()
        val request = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
        try {
            val response = await(downloadClient.newCall(request))
            withContext(Dispatchers.IO) {
                response.use {
                    if (it.code !in 200..299) {
                        throw SpeechFailure(SpeechErrorCode.SERVER, "模型下载失败（HTTP ${it.code}）")
                    }
                    val total = it.body.contentLength().takeIf { length -> length > 0 } ?: expected
                    it.body.byteStream().use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var read = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                read += count
                                onProgress(read, total)
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            temp.delete()
            throw when (error) {
                is SpeechFailure -> error
                // HuggingFace 在部分网络下不可直连：提示改走「从文件夹导入」，用户手里往往已经有这两个文件。
                is IOException -> SpeechFailure(
                    SpeechErrorCode.NETWORK,
                    "模型下载失败，请检查网络后重试；也可以先下载 model.int8.onnx 与 tokens.txt，再从文件夹导入",
                )
                else -> SpeechFailure(SpeechErrorCode.STORAGE, "模型写入失败：${error.message ?: "未知错误"}")
            }
        }
        val actual = temp.length()
        if (expected > 0 && actual != expected) {
            temp.delete()
            throw SpeechFailure(SpeechErrorCode.SERVER, "模型文件不完整，请重新下载")
        }
        publish(temp, target)
    }

    private fun publish(temp: File, target: File) {
        if (temp.renameTo(target)) return
        if (!temp.copyTo(target, overwrite = true).exists()) {
            throw SpeechFailure(SpeechErrorCode.STORAGE, "无法写入 ${target.name}")
        }
        temp.delete()
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    /** HuggingFace 的 resolve 链接会 302 到 CDN，下载客户端必须跟随跳转（识别链路显式关闭了跳转）。 */
    private val downloadClient by lazy {
        AgentHttpClient.client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun percent(read: Long, total: Long): String =
        "${(read * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)}%"

    private fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

    private const val COPY_BUFFER_BYTES = 256 * 1024

    private val IMPORTABLE = listOf(
        LocalAsrConfig.MODEL_FILE,
        LocalAsrConfig.MODEL_FILE_FULL,
        LocalAsrConfig.TOKENS_FILE,
    )
}
