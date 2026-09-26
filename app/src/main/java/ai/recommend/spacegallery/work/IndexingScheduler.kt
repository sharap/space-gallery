package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.domain.IndexingPhase
import ai.recommend.spacegallery.domain.IndexingProgress
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
            // Линейная задержка вместо экспоненциальной: после нескольких прерванных
            // проходов экспонента уводит следующий запуск на часы, и индексация замирает
            // до перезапуска приложения.
            .setBackoffCriteria(BackoffPolicy.LINEAR, MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            WORK_NAME,
            if (restart) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        scheduleNightPass()
    }

    /**
     * Продолжить проход после того, как он отработал своё окно.
     *
     * Окончание по собственному окну — не ошибка, поэтому воркер возвращает успех, а
     * следующий кусок ставится обычной задачей с небольшой паузой. Через `Result.retry()`
     * так делать нельзя: WorkManager считает попытки и разводит задержку до часов.
     */
    fun scheduleNextChunk(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<MediaIndexWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(onlyWhileCharging)
                    .build()
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // APPEND_OR_REPLACE, а не REPLACE: продолжение ставит сам работающий воркер, и
        // REPLACE отменил бы его самого. Задача встаёт следом за текущей и стартует после
        // паузы, а счётчик попыток у неё свой — задержка не копится.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Ночной проход: система сама запустит его, когда телефон поставят на зарядку и он
     * начнёт простаивать. Только такой проход идёт в полный темп без ограничения по времени —
     * дневные работают тихо и окнами (см. [IndexingPace]).
     *
     * Задача ждёт своих условий сколько угодно, поэтому ставится один раз (KEEP) и просто
     * висит в очереди; когда она отработает, следующий запрос индексации поставит новую.
     */
    private fun scheduleNightPass() {
        val request = OneTimeWorkRequestBuilder<MediaIndexWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(workDataOf(MediaIndexWorker.KEY_FULL_PACE to true))
            // Задержку повторов здесь задавать нельзя: WorkManager запрещает её задачам с
            // условием «системный простой» и бросает IllegalArgumentException при сборке.
            .build()
        workManager.enqueueUniqueWork(NIGHT_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Вызывается, когда приложение на экране: запустить индексацию немедленно.
     *
     * Если воркер уже работает — ничего не делаем. Если он ждёт в очереди (например, после
     * убийства процесса WorkManager ждёт backoff, а JobScheduler может откладывать ещё дольше) —
     * заменяем его expedited-задачей: она стартует сразу, а воркер сам переходит в foreground service.
     */
    suspend fun ensureIndexingNow(onlyWhileCharging: Boolean) {
        this.onlyWhileCharging = onlyWhileCharging
        scheduleNightPass()
        val running = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
            .any { it.state == WorkInfo.State.RUNNING }
        if (running) return
        if (onlyWhileCharging) {
            // Expedited-задачи не поддерживают условие зарядки — ставим обычную.
            requestIndexing(onlyWhileCharging = true, restart = true)
            return
        }
        val request = OneTimeWorkRequestBuilder<MediaIndexWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun observeProgress(): Flow<IndexingProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: return@map IndexingProgress(isRunning = false)
            IndexingProgress(
                isRunning = true,
                processed = running.progress.getInt(MediaIndexWorker.KEY_PROCESSED, 0),
                total = running.progress.getInt(MediaIndexWorker.KEY_TOTAL, 0),
                phase = running.progress.getString(MediaIndexWorker.KEY_PHASE)
                    ?.let { name -> IndexingPhase.entries.firstOrNull { it.name == name } }
                    ?: IndexingPhase.ANALYSIS,
            )
        }

    /**
     * Реагировать на новые фото, пока процесс жив.
     *
     * Идущую индексацию перезапускать нельзя: MediaStore меняется и от наших же обращений
     * (система сохраняет превью, которые мы запрашиваем), и перезапуск «с нуля» зацикливал
     * проход — он работал 10–20 секунд и начинался заново. Поэтому здесь только KEEP: новые
     * файлы подхватит текущий проход (он читает очередь страницами) или следующий запуск.
     *
     * TODO: для фоновой реакции — WorkManager c addContentUriTrigger.
     */
    fun startWatchingMediaStore() {
        if (observer != null) return
        val handler = Handler(Looper.getMainLooper())
        val trigger = Runnable { requestIndexing(restart = false) }
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

    companion object {
        const val WORK_NAME = "media-index"

        /** Отдельное имя: ночной проход ждёт своих условий, не мешая дневным запускам. */
        const val NIGHT_WORK_NAME = "media-index-night"

        /** Минимум, который разрешает WorkManager (10 секунд). */
        const val MIN_BACKOFF_MILLIS = androidx.work.WorkRequest.MIN_BACKOFF_MILLIS
        /** Камера и загрузчики присылают пачки уведомлений; индексация — ещё и свои. */
        const val DEBOUNCE_MS = 10_000L
    }
}
