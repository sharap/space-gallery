package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.BuildConfig
import ai.recommend.spacegallery.ml.onnx.ModelCatalog
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Состояние загрузки моделей для экрана «AI-модели». */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    /** Ждёт сети (или Wi-Fi) либо повтора после ошибки. */
    data object Waiting : ModelDownloadState
    data class Running(val done: Long, val total: Long, val file: String?) : ModelDownloadState
    data class Failed(val error: String?) : ModelDownloadState
}

class ModelDownloads(context: Context, private val catalog: ModelCatalog) {
    private val workManager = WorkManager.getInstance(context)

    /** Источник моделей задан в этой сборке. */
    val isConfigured: Boolean get() = BuildConfig.MODELS_BASE_URL.isNotEmpty()

    /**
     * @param groups какие группы функций качать; пусто — все из манифеста.
     * @param force только для отладки: скачать и модели, встроенные в debug-APK.
     */
    fun start(wifiOnly: Boolean, groups: Set<String> = emptySet(), force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_FORCE to force,
                    ModelDownloadWorker.KEY_GROUPS to groups.toTypedArray(),
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE: смена «только Wi-Fi» перезапускает с новыми условиями; .part сохраняются.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    fun observe(): Flow<ModelDownloadState> = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        val info = infos.lastOrNull() ?: return@map ModelDownloadState.Idle
        when (info.state) {
            WorkInfo.State.RUNNING -> ModelDownloadState.Running(
                done = info.progress.getLong(ModelDownloadWorker.KEY_DONE, 0),
                total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 0),
                file = info.progress.getString(ModelDownloadWorker.KEY_FILE),
            )
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownloadState.Waiting
            WorkInfo.State.FAILED -> ModelDownloadState.Failed(info.outputData.getString(ModelDownloadWorker.KEY_ERROR))
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> ModelDownloadState.Idle
        }
    }

    private companion object {
        const val WORK_NAME = "model-download"
    }
}
