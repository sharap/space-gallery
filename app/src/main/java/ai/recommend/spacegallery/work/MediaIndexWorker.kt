package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.OnnxRuntimeHolder
import ai.recommend.spacegallery.perf.PerfStats
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Фоновая индексация: синхронизация с MediaStore и AI-анализ новых/изменённых файлов.
 *
 * Большие проходы выполняются как foreground service (уведомление с прогрессом):
 * так система не убивает процесс и не действует 10-минутный лимит WorkManager.
 * Прогресс сохраняется в БД после каждого батча, поэтому прерывание безопасно.
 */
class MediaIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /** Удалось ли перевести воркер в foreground (на Android 12+ может быть запрещено из фона). */
    private var isForeground = false
    private var lastNotificationAt = 0L

    override suspend fun doWork(): Result {
        val c = (applicationContext as SpaceGalleryApp).container
        val analysisDao = c.database.analysisDao()

        PerfStats.measure("sync.mediastore") { c.mediaRepository.syncWithMediaStore() }

        val total = analysisDao.countPending(
            MediaAnalyzer.PIPELINE_VERSION,
            c.imageEmbedder.isAvailable,
            c.sensitiveClassifier.isAvailable,
        )
        if (total == 0) return Result.success()

        // Пара новых фото обрабатывается за секунды — не показываем ради них уведомление.
        if (total >= FOREGROUND_THRESHOLD) tryStartForeground(0, total)

        var processed = 0
        var reportedAt = 0
        var windowStart = SystemClock.elapsedRealtime()
        reportProgress(processed, total)
        try {
            while (!isStopped && processed < total) {
                val batch = PerfStats.measure("db.getPending") {
                    analysisDao.getPending(
                        MediaAnalyzer.PIPELINE_VERSION,
                        c.imageEmbedder.isAvailable,
                        c.sensitiveClassifier.isAvailable,
                        BATCH_SIZE,
                    )
                }
                if (batch.isEmpty()) break
                val results = batch.map { c.mediaAnalyzer.analyze(it) }
                PerfStats.measure("db.upsert") { analysisDao.upsertAll(results) }
                processed += batch.size
                reportProgress(processed, total)
                if (processed - reportedAt >= PERF_REPORT_EVERY) {
                    logPerf(processed - reportedAt, SystemClock.elapsedRealtime() - windowStart, processed, total)
                    reportedAt = processed
                    windowStart = SystemClock.elapsedRealtime()
                }
            }
        } catch (e: CancellationException) {
            throw e // остановка воркера — не ошибка
        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed", e)
            return Result.retry()
        } finally {
            if (processed > 0) c.embeddingIndex.invalidate()
            // Модели занимают сотни МБ нативной памяти — освобождаем после прохода.
            c.models.release(ModelId.CLIP_IMAGE, ModelId.NSFW, ModelId.NSFW_CLIP)
        }
        return if (isStopped) Result.retry() else Result.success()
    }

    /** Используется WorkManager, если воркер запущен как expedited на Android < 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        IndexingNotifications.foregroundInfo(applicationContext, id, 0, 0)

    private suspend fun tryStartForeground(processed: Int, total: Int) {
        try {
            setForeground(IndexingNotifications.foregroundInfo(applicationContext, id, processed, total))
            isForeground = true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+, запуск из фона) или
            // исчерпан лимит времени FGS (Android 15). Продолжаем как обычный воркер —
            // если система остановит его, WorkManager перезапустит, прогресс сохранён в БД.
            Log.w(TAG, "Foreground service недоступен, индексируем в фоне", e)
        }
    }

    /** Сводка по этапам за окно из [items] файлов — `adb logcat -s IndexPerf`. */
    private fun logPerf(items: Int, wallMs: Long, processed: Int, total: Int) {
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        val header = String.format(
            Locale.ROOT,
            "%d/%d: %d files in %.1fs = %.0f ms/file | importance=%d fgService=%b ortThreads=%d cpus=%d",
            processed, total, items, wallMs / 1000.0, wallMs.toDouble() / items,
            state.importance, isForeground, OnnxRuntimeHolder.intraOpThreads, Runtime.getRuntime().availableProcessors(),
        )
        Log.i(PERF_TAG, header + "\n" + PerfStats.reportAndReset(MediaAnalyzer.STAGE_TOTAL))
    }

    private suspend fun reportProgress(processed: Int, total: Int) {
        setProgress(workDataOf(KEY_PROCESSED to processed, KEY_TOTAL to total))
        val now = SystemClock.elapsedRealtime()
        if (isForeground && now - lastNotificationAt >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationAt = now
            tryStartForeground(processed, total)
        }
    }

    companion object {
        private const val TAG = "MediaIndexWorker"
        private const val BATCH_SIZE = 16
        private const val FOREGROUND_THRESHOLD = 32
        private const val NOTIFICATION_THROTTLE_MS = 1_000L
        private const val PERF_TAG = "IndexPerf"
        private const val PERF_REPORT_EVERY = 64
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
    }
}
