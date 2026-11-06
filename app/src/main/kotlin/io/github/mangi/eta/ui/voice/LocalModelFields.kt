package io.github.mangi.eta.ui.voice

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.LocalAsrModelStore
import io.github.mangi.eta.agent.voice.speechFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地模型的准备界面：下载、从文件夹导入、删除。
 *
 * 模型和音频都不离开设备，这里只负责把文件放到位；识别参数沿用页面上的语言设置。
 * 导入用 SAF 直接读原始文件，因此不需要申请全盘存储权限。
 */
@Composable
internal fun LocalModelSection(store: SpeechSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = store.settings.localAsr
    var status by remember(config.modelDir) { mutableStateOf(LocalAsrModelStore.inspect(context, config)) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    val busy = job?.isActive == true

    // 下载和导入都要写 229 MB，操作期间不再叠加任务，避免两个写入者抢同一个目标文件。
    suspend fun write(block: suspend (onProgress: (String) -> Unit) -> Unit) {
        failure = null
        progress = ""
        try {
            block { progress = it }
        } catch (error: Exception) {
            failure = error.speechFailure().userMessage
        } finally {
            progress = ""
            status = LocalAsrModelStore.inspect(context, config)
        }
    }

    fun start(block: suspend (onProgress: (String) -> Unit) -> Unit) {
        if (job?.isActive != true) {
            job = scope.launch { write(block) }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            start { onProgress -> LocalAsrModelStore.import(context, uri, config, onProgress) }
        }
    }

    Column {
        SmallTitle(stringResource(R.string.speech_local_group))
        Text(
            text = when {
                status.ready -> stringResource(R.string.speech_local_status_ready, megabytes(status.bytes))
                status.missing.isEmpty() -> stringResource(R.string.speech_local_status_empty)
                else -> stringResource(R.string.speech_local_status_missing, status.missing.joinToString("、"))
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            ArrowPreference(
                title = stringResource(R.string.speech_local_download),
                summary = stringResource(R.string.speech_local_download_summary),
                enabled = !busy,
                endActions = {
                    if (busy) InfiniteProgressIndicator(size = 20.dp)
                },
                onClick = { start { onProgress -> LocalAsrModelStore.download(context, config, onProgress) } },
            )
            ArrowPreference(
                title = stringResource(R.string.speech_local_import),
                summary = stringResource(R.string.speech_local_import_summary),
                enabled = !busy,
                onClick = { importer.launch(null) },
            )
            if (status.bytes > 0) {
                ArrowPreference(
                    title = stringResource(R.string.speech_local_delete),
                    summary = stringResource(R.string.speech_local_delete_summary),
                    enabled = !busy,
                    onClick = {
                        failure = null
                        runCatching { LocalAsrModelStore.delete(context, config) }
                            .onFailure { failure = it.speechFailure().userMessage }
                        status = LocalAsrModelStore.inspect(context, config)
                    },
                )
            }
        }
        val message = failure
        if (message != null) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 8.dp),
            )
        }
        SpeechNote(if (progress.isNotBlank()) progress else stringResource(R.string.speech_local_note))
    }
}

/** 与文件管理器一致的十进制取整，避免 239 MB 显示成 228 MB 造成困惑。 */
private fun megabytes(bytes: Long): String = "${(bytes + 512 * 1024) / (1024 * 1024)} MB"
