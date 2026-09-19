package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.domain.IndexingProgress
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class IndexingScheduler(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)
    private var observer: ContentObserver? = null
    @Volatile private var onlyWhileCharging = false

    /**
     * @param restart true — поставить новый проход в очередь после текущего
     * (нужно, когда медиатека изменилась во время индексации).
     */
    fun requestIndexing(onlyWhileCharging: Boolean = this.onlyWhileCharging, restart: Boolean = false) {
        this.onlyWhileCharging = onlyWhileCharging
        val request = OneTimeWorkRequestBuilder<MediaIndexWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(onlyWhileCharging)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(
            WORK_NAME,
            if (restart) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun observeProgress(): Flow<IndexingProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: return@map IndexingProgress(isRunning = false)
            IndexingProgress(
                isRunning = true,
                processed = running.progress.getInt(MediaIndexWorker.KEY_PROCESSED, 0),
                total = running.progress.getInt(MediaIndexWorker.KEY_TOTAL, 0),
            )
        }

    /**
     * Реагировать на новые фото, пока процесс жив.
     * TODO: для фоновой реакции — WorkManager c addContentUriTrigger.
     */
    fun startWatchingMediaStore() {
        if (observer != null) return
        val handler = Handler(Looper.getMainLooper())
        val trigger = Runnable { requestIndexing(restart = true) }
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Debounce: камера и загрузчики присылают пачки уведомлений.
                handler.removeCallbacks(trigger)
                handler.postDelayed(trigger, DEBOUNCE_MS)
            }
        }.also { obs ->
            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ).forEach { context.contentResolver.registerContentObserver(it, true, obs) }
        }
    }

    private companion object {
        const val WORK_NAME = "media-index"
        const val DEBOUNCE_MS = 3_000L
    }
}
