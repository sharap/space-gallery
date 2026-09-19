package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.ml.onnx.ModelId
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Фоновая индексация: синхронизация с MediaStore и AI-анализ новых/изменённых файлов.
 * Прогресс сохраняется в БД после каждого батча, поэтому прерывание безопасно.
 */
class MediaIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as SpaceGalleryApp).container
        val analysisDao = c.database.analysisDao()

        c.mediaRepository.syncWithMediaStore()

        val total = analysisDao.countPending(
            MediaAnalyzer.PIPELINE_VERSION,
            c.imageEmbedder.isAvailable,
            c.sensitiveClassifier.isAvailable,
        )
        var processed = 0
        reportProgress(processed, total)

        try {
            while (!isStopped && processed < total) {
                val batch = analysisDao.getPending(
                    MediaAnalyzer.PIPELINE_VERSION,
                    c.imageEmbedder.isAvailable,
                    c.sensitiveClassifier.isAvailable,
                    BATCH_SIZE,
                )
                if (batch.isEmpty()) break
                analysisDao.upsertAll(batch.map { c.mediaAnalyzer.analyze(it) })
                processed += batch.size
                reportProgress(processed, total)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed", e)
            return Result.retry()
        } finally {
            if (processed > 0) c.embeddingIndex.invalidate()
            // Модели занимают сотни МБ нативной памяти — освобождаем после прохода.
            c.models.release(ModelId.CLIP_IMAGE, ModelId.NSFW)
        }
        return if (isStopped) Result.retry() else Result.success()
    }

    private suspend fun reportProgress(processed: Int, total: Int) {
        setProgress(workDataOf(KEY_PROCESSED to processed, KEY_TOTAL to total))
    }

    companion object {
        private const val TAG = "MediaIndexWorker"
        private const val BATCH_SIZE = 16
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
    }
}
