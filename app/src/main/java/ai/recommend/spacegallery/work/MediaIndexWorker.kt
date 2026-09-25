package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.data.db.MediaAnalysisEntity
import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.IndexingPhase
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.OnnxRuntimeHolder
import ai.recommend.spacegallery.perf.PerfStats
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Фоновая индексация по этапам:
 * 1. синхронизация с MediaStore и AI-анализ новых/изменённых файлов (CLIP, NSFW, dHash);
 * 2. умные альбомы (DBSCAN по CLIP), если накопилось достаточно изменений;
 * 3. текст на снимках и QR-коды;
 *    геометки (EXIF, метаданные видео) — для поиска по местам;
 *    оценка качества (резкость, яркость) — для очистки;
 * 4. поиск лиц (YuNet + SFace) на фото, где их ещё не искали;
 * 5. люди (средняя связь по лицам), если нашлись новые лица.
 *
 * Большие проходы выполняются как foreground service (уведомление с прогрессом): так система
 * не убивает процесс, не действует 10-минутный лимит WorkManager и доступны все ядра.
 * Результаты сохраняются в БД пачками, поэтому прерывание безопасно.
 */
class MediaIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /** Удалось ли перевести воркер в foreground (на Android 12+ может быть запрещено из фона). */
    private var isForeground = false

    /** Foreground временно недоступен: исчерпан суточный лимит сервиса «обработка медиа». */
    private var foregroundBlocked = false
    private var lastNotificationAt = 0L
    private var phase = IndexingPhase.ANALYSIS
    private lateinit var pace: IndexingPace

    /** Остановиться пора: либо система забирает воркер, либо у тихого прохода вышло окно. */
    private fun halted(): Boolean = isStopped || pace.exhausted()

    override suspend fun doWork(): Result {
        val c = (applicationContext as SpaceGalleryApp).container
        // Ночной проход по расписанию и обычный — один и тот же воркер; вдвоём им на
        // процессоре делать нечего.
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "Индексация уже идёт — этот запуск подождёт")
            return Result.retry()
        }
        return try {
            index(c)
        } finally {
            OnnxRuntimeHolder.resetThreads()
            running.set(false)
        }
    }

    private suspend fun index(c: AppContainer): Result {
        pace = IndexingPace(
            applicationContext,
            alwaysQuiet = c.settings.current().quietIndexing,
            forceFull = inputData.getBoolean(KEY_FULL_PACE, false),
            isStopped = { halted() },
        )
        OnnxRuntimeHolder.intraOpThreads = pace.mode.threads
        Log.i(TAG, "Темп индексации: ${pace.describe()}")
        foregroundBlocked = System.currentTimeMillis() < c.settings.foregroundBlockedUntil()
        if (foregroundBlocked) Log.i(TAG, "Foreground-сервис временно недоступен — работаем частями в фоне")
        // Разрешение на запуск сервиса действует лишь первые секунды работы задачи (expedited),
        // поэтому просим передний план сразу, а не когда дойдём до тяжёлого этапа: иначе система
        // отказывает («Background started FGS: Disallowed»), и задачу душат окнами по 15 секунд.
        if (!foregroundBlocked && hasPendingWork(c)) tryStartForeground(0, 0)
        PerfStats.measure("sync.mediastore") { c.mediaRepository.syncWithMediaStore() }

        val analyzed = try {
            analyzeMedia(c)
        } catch (e: CancellationException) {
            throw e // остановка воркера — не ошибка
        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed", e)
            return Result.retry()
        }
        if (halted()) return Result.retry()

        // Пока воркер в foreground и доступны все ядра — производные данные.
        if (c.imageEmbedder.isAvailable && c.smartAlbumBuilder.shouldRebuild(newlyAnalyzed = analyzed)) {
            enterPhase(IndexingPhase.GROUPING, total = 0, foreground = true) // ~3–10 с работы CPU
            rebuildSmartAlbums(c)
        }
        if (halted()) return Result.retry()

        try {
            readLocations(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Location reading failed", e)
        }
        if (halted()) return Result.retry()

        try {
            readText(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Text recognition failed", e)
        }
        if (halted()) return checkStop(c)

        try {
            assessQuality(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Quality assessment failed", e) // очистка подождёт, не повод перезапускать
        }
        if (halted()) return checkStop(c)

        if (c.faceIndexer.isAvailable()) {
            val facesFound = try {
                // Сменилась модель векторов — пересчитываем по сохранённым точкам, без поиска лиц.
                reembedFaces(c) + indexFaces(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Face indexing failed", e)
                return Result.retry()
            }
            if (halted()) return checkStop(c)
            if (facesFound > 0 || c.peopleBuilder.needsRebuild()) {
                // Лиц — тысячи, векторы короткие: доли секунды, уведомление не нужно.
                enterPhase(IndexingPhase.GROUPING, total = 0, foreground = false)
                rebuildPeople(c)
            }
        }
        return if (halted()) checkStop(c) else Result.success()
    }

    /** Остановка по лимиту foreground-сервиса — запомнить и продолжить в фоне. */
    private suspend fun checkStop(c: AppContainer): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
            Log.w(TAG, "Сервис остановлен по суточному лимиту — продолжим в фоне")
            blockForeground(QUOTA_BACKOFF_MS)
        }
        return Result.retry()
    }

    /** Есть ли вообще работа: если нет, передний план и уведомление не нужны. */
    private suspend fun hasPendingWork(c: AppContainer): Boolean {
        val analysis = c.database.analysisDao().countPending(
            MediaAnalyzer.PIPELINE_VERSION,
            c.imageEmbedder.isAvailable,
            c.sensitiveClassifier.isAvailable,
        )
        return analysis > 0 || c.textIndexer.countPending() > 0 || c.qualityIndexer.countPending() > 0 ||
            c.locationIndexer.countPending() > 0 || c.faceIndexer.countPending() > 0 ||
            c.faceReembedder.countPending() > 0
    }

    /** Этап 1: CLIP/NSFW/dHash. Возвращает число проанализированных файлов. */
    private suspend fun analyzeMedia(c: AppContainer): Int {
        val analysisDao = c.database.analysisDao()
        val total = analysisDao.countPending(
            MediaAnalyzer.PIPELINE_VERSION,
            c.imageEmbedder.isAvailable,
            c.sensitiveClassifier.isAvailable,
        )
        if (total == 0) return 0
        phase = IndexingPhase.ANALYSIS
        // Пара новых фото обрабатывается за секунды — не показываем ради них уведомление.
        if (total >= FOREGROUND_THRESHOLD) tryStartForeground(0, total)

        var processed = 0
        var reportedAt = 0
        var windowStart = SystemClock.elapsedRealtime()
        reportProgress(processed, total)
        try {
            coroutineScope {
                // Производитель: читает очередь страницами и готовит кадры (декодирование, dHash,
                // тензор CLIP) на отдельном потоке, пока потребитель занят инференсом.
                val prepared = Channel<PreparedMedia>(capacity = PREFETCH)
                val producer = launch(Dispatchers.IO) {
                    var afterDate = Long.MAX_VALUE
                    var afterId = Long.MAX_VALUE
                    while (true) {
                        val page = PerfStats.measure("db.getPending") {
                            analysisDao.getPendingPage(
                                MediaAnalyzer.PIPELINE_VERSION,
                                c.imageEmbedder.isAvailable,
                                c.sensitiveClassifier.isAvailable,
                                afterDate,
                                afterId,
                                PAGE_SIZE,
                            )
                        }
                        if (page.isEmpty()) break
                        for (media in page) prepared.send(c.mediaAnalyzer.prepare(media))
                        afterDate = page.last().dateTaken
                        afterId = page.last().id
                    }
                    prepared.close()
                }

                // Потребитель: инференс по одному кадру, запись в БД батчами.
                val results = ArrayList<MediaAnalysisEntity>(BATCH_SIZE)
                suspend fun flush() {
                    if (results.isEmpty()) return
                    PerfStats.measure("db.upsert") { analysisDao.upsertAll(results) }
                    processed += results.size
                    results.clear()
                    reportProgress(processed, total)
                    if (processed - reportedAt >= PERF_REPORT_EVERY) {
                        logPerf(processed - reportedAt, SystemClock.elapsedRealtime() - windowStart, processed, total, MediaAnalyzer.STAGE_TOTAL)
                        reportedAt = processed
                        windowStart = SystemClock.elapsedRealtime()
                    }
                }
                for (item in prepared) {
                    if (halted()) break
                    results += c.mediaAnalyzer.analyze(item)
                    if (results.size >= BATCH_SIZE) flush()
                    pace.tick()
                }
                flush()
                producer.cancel()
            }
        } finally {
            if (processed > 0) c.embeddingIndex.invalidate()
            // Модели занимают сотни МБ нативной памяти — освобождаем после прохода.
            c.models.release(ModelId.CLIP_IMAGE, ModelId.NSFW, ModelId.NSFW_CLIP)
        }
        return processed
    }

    /** Геометки из EXIF и метаданных видео (для поиска по местам). */
    private suspend fun readLocations(c: AppContainer) {
        val total = c.locationIndexer.countPending()
        if (total == 0) return
        enterPhase(IndexingPhase.LOCATION, total, foreground = total >= FOREGROUND_THRESHOLD)
        c.locationIndexer.run(isStopped = { halted() }) { processed ->
            reportProgress(processed, total)
            pace.tick()
        }
    }

    /** Распознавание текста и поиск QR-кодов. */
    private suspend fun readText(c: AppContainer) {
        if (!c.textIndexer.isAvailable) return
        val total = c.textIndexer.countPending()
        if (total == 0) return
        enterPhase(IndexingPhase.TEXT, total, foreground = total >= FOREGROUND_THRESHOLD)
        var reportedAt = 0
        var windowStart = SystemClock.elapsedRealtime()
        try {
            val (_, found) = c.textIndexer.run(isStopped = { halted() }) { processed ->
                reportProgress(processed, total)
                if (processed - reportedAt >= PERF_REPORT_EVERY) {
                    logPerf(processed - reportedAt, SystemClock.elapsedRealtime() - windowStart, processed, total, "text.total")
                    reportedAt = processed
                    windowStart = SystemClock.elapsedRealtime()
                }
                pace.tick()
            }
            if (found > 0) Log.i(TAG, "Текст или коды найдены на $found снимках")
        } finally {
            c.models.release(ModelId.TEXT_DETECT, ModelId.TEXT_RECOGNIZE, ModelId.TEXT_RECOGNIZE_KO)
        }
    }

    /** Оценка резкости и яркости фото (для очистки). */
    private suspend fun assessQuality(c: AppContainer) {
        val total = c.qualityIndexer.countPending()
        if (total == 0) return
        enterPhase(IndexingPhase.QUALITY, total, foreground = total >= FOREGROUND_THRESHOLD)
        var reportedAt = 0
        var windowStart = SystemClock.elapsedRealtime()
        c.qualityIndexer.run(isStopped = { halted() }) { processed ->
            reportProgress(processed, total)
            if (processed - reportedAt >= PERF_REPORT_EVERY * 4) {
                logPerf(processed - reportedAt, SystemClock.elapsedRealtime() - windowStart, processed, total, "quality.total")
                reportedAt = processed
                windowStart = SystemClock.elapsedRealtime()
            }
            pace.tick()
        }
    }

    /** Пересчёт векторов лиц после смены модели (лица не ищутся заново). */
    private suspend fun reembedFaces(c: AppContainer): Int {
        val total = c.faceReembedder.countPending()
        if (total == 0) return 0
        enterPhase(IndexingPhase.FACES, total, foreground = total >= FOREGROUND_THRESHOLD)
        Log.i(TAG, "Пересчёт векторов лиц новой моделью: $total")
        var reportedAt = 0
        var windowStart = SystemClock.elapsedRealtime()
        return try {
            c.faceReembedder.run(isStopped = { halted() }) { processed ->
                reportProgress(processed, total)
                if (processed - reportedAt >= PERF_REPORT_EVERY) {
                    logPerf(processed - reportedAt, SystemClock.elapsedRealtime() - windowStart, processed, total, "faces.embed")
                    reportedAt = processed
                    windowStart = SystemClock.elapsedRealtime()
                }
                pace.tick()
            }
        } finally {
            c.models.release(ModelId.FACE_EMBED, ModelId.FACE_EMBED_HQ)
        }
    }

    /** Этап 3: поиск лиц. Возвращает число найденных лиц. */
    private suspend fun indexFaces(c: AppContainer): Int {
        val total = c.faceIndexer.countPending()
        enterPhase(IndexingPhase.FACES, total, foreground = total >= FOREGROUND_THRESHOLD)
        var reportedAt = 0
        var windowStart = SystemClock.elapsedRealtime()
        try {
            val (_, found) = c.faceIndexer.run(isStopped = { halted() }) { processed ->
                reportProgress(processed, total)
                if (processed - reportedAt >= PERF_REPORT_EVERY) {
                    logPerf(processed - reportedAt, SystemClock.elapsedRealtime() - windowStart, processed, total, "faces.total")
                    reportedAt = processed
                    windowStart = SystemClock.elapsedRealtime()
                }
                pace.tick()
            }
            // Модели лиц (ArcFace ~174 МБ) освобождаются до проверки через CLIP: вместе они
            // не помещаются — система убивала процесс.
            c.models.release(ModelId.FACE_DETECT, ModelId.FACE_EMBED, ModelId.FACE_EMBED_HQ)
            val removed = c.faceIndexer.verifyExisting(isStopped = { halted() })
            if (removed > 0) Log.i(TAG, "Удалено ложных срабатываний лиц: $removed")
            return found + removed
        } finally {
            c.models.release(ModelId.FACE_DETECT, ModelId.FACE_EMBED, ModelId.FACE_EMBED_HQ, ModelId.CLIP_IMAGE, ModelId.CLIP_TEXT)
        }
    }

    private suspend fun rebuildSmartAlbums(c: AppContainer) {
        try {
            PerfStats.measure("smart.total") { c.smartAlbumBuilder.rebuild() }
            Log.i(PERF_TAG, "smart albums:\n" + PerfStats.reportAndReset("smart.total"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Smart albums failed", e) // не повод перезапускать индексацию
        } finally {
            c.models.release(ModelId.CLIP_TEXT, ModelId.CLIP_TEXT_MULTILINGUAL)
        }
    }

    private suspend fun rebuildPeople(c: AppContainer) {
        try {
            c.peopleBuilder.rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "People clustering failed", e)
        }
    }

    /** Новый этап: подпись в уведомлении/ленте; тяжёлые этапы — в foreground. */
    private suspend fun enterPhase(newPhase: IndexingPhase, total: Int, foreground: Boolean) {
        phase = newPhase
        // Модели этапа ещё не загружены — самое время применить число потоков текущего темпа.
        OnnxRuntimeHolder.intraOpThreads = pace.mode.threads
        lastNotificationAt = 0L
        if (foreground && !isForeground) tryStartForeground(0, total)
        reportProgress(0, total)
    }

    /** Используется WorkManager, если воркер запущен как expedited на Android < 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        IndexingNotifications.foregroundInfo(applicationContext, id, phase, 0, 0)

    private suspend fun tryStartForeground(processed: Int, total: Int) {
        if (foregroundBlocked) return
        try {
            setForeground(IndexingNotifications.foregroundInfo(applicationContext, id, phase, processed, total))
            isForeground = true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+, запуск из фона) или
            // исчерпан лимит времени FGS (Android 15). Продолжаем как обычный воркер —
            // если система остановит его, WorkManager перезапустит, прогресс сохранён в БД.
            // Отказ «из фона» (экран заблокирован) — временный: пробуем снова через несколько минут.
            Log.w(TAG, "Foreground service недоступен, индексируем в фоне", e)
            blockForeground(DENIED_BACKOFF_MS)
        }
    }

    /**
     * Система остановила сервис по суточному лимиту: дальше работаем обычным воркером
     * (частями по 10 минут), иначе каждый запуск будет убиваться на том же месте.
     */
    private suspend fun blockForeground(backoff: Long) {
        foregroundBlocked = true
        isForeground = false
        val c = (applicationContext as SpaceGalleryApp).container
        c.settings.blockForeground(System.currentTimeMillis() + backoff)
    }

    /** Сводка по этапам за окно из [items] файлов — `adb logcat -s IndexPerf`. */
    private fun logPerf(items: Int, wallMs: Long, processed: Int, total: Int, totalStage: String) {
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        val header = String.format(
            Locale.ROOT,
            "%s %d/%d: %d files in %.1fs = %.0f ms/file | pace=%s importance=%d fgService=%b ortThreads=%d cpus=%d",
            phase, processed, total, items, wallMs / 1000.0, wallMs.toDouble() / items,
            pace.mode, state.importance, isForeground, OnnxRuntimeHolder.intraOpThreads,
            Runtime.getRuntime().availableProcessors(),
        )
        Log.i(PERF_TAG, header + "\n" + PerfStats.reportAndReset(totalStage))
    }

    private suspend fun reportProgress(processed: Int, total: Int) {
        setProgress(workDataOf(KEY_PROCESSED to processed, KEY_TOTAL to total, KEY_PHASE to phase.name))
        val now = SystemClock.elapsedRealtime()
        if (isForeground && now - lastNotificationAt >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationAt = now
            tryStartForeground(processed, total)
        }
    }

    companion object {
        private const val TAG = "MediaIndexWorker"

        /** Один проход на процесс: ночной по расписанию и обычный не должны идти вдвоём. */
        private val running = AtomicBoolean(false)

        /** Проход запущен по ночному расписанию — темп полный, окно не ограничено. */
        const val KEY_FULL_PACE = "full_pace"
        private const val BATCH_SIZE = 16
        private const val PAGE_SIZE = 64

        /** Сколько подготовленных кадров держать впереди инференса (~0.6 МБ битмапа на кадр). */
        private const val PREFETCH = 4
        private const val FOREGROUND_THRESHOLD = 32
        private const val NOTIFICATION_THROTTLE_MS = 1_000L
        private const val PERF_TAG = "IndexPerf"
        private const val PERF_REPORT_EVERY = 64

        /** Суточный лимит сервиса сбрасывается раз в сутки — столько и ждём. */
        private const val QUOTA_BACKOFF_MS = 6 * 60 * 60 * 1000L

        /** Отказ из-за состояния приложения (экран заблокирован) проходит сам — ждём недолго. */
        private const val DENIED_BACKOFF_MS = 5 * 60 * 1000L
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_PHASE = "phase"
    }
}
