package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.data.db.MediaAnalysisEntity
import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.IndexingPhase
import ai.recommend.spacegallery.ml.onnx.ModelGroup
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.OnnxRuntimeHolder
import ai.recommend.spacegallery.perf.PerfStats
import ai.recommend.spacegallery.search.people.FaceIndexer
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
import androidx.work.WorkManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    /** До какого момента текущий этап имеет право работать (см. [IndexingPace.stageBudgetMs]). */
    private var stageDeadline = Long.MAX_VALUE

    /** Пора заканчивать этап — но не обязательно весь проход: дальше идут другие этапы. */
    private fun stageHalted(): Boolean = halted() || SystemClock.elapsedRealtime() > stageDeadline

    private fun startStage() {
        val budget = pace.stageBudgetMs
        stageDeadline = if (budget == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.elapsedRealtime() + budget
    }

    override suspend fun doWork(): Result {
        val c = (applicationContext as SpaceGalleryApp).container
        // Ночной проход по расписанию и обычный — один и тот же воркер; вдвоём им на
        // процессоре делать нечего.
        if (!running.compareAndSet(false, true)) {
            if (!inputData.getBoolean(KEY_FULL_PACE, false)) {
                Log.i(TAG, "Индексация уже идёт — этот запуск подождёт")
                return Result.retry()
            }
            // Ночной проход не должен уступать дневному: тот работает окнами и уходит в
            // растущий backoff, а условия «зарядка и простой» выпадают редко. Поэтому
            // просим дневной закончить и занимаем его место.
            Log.i(TAG, "Ночной проход просит дневной уступить")
            WorkManager.getInstance(applicationContext).cancelUniqueWork(IndexingScheduler.WORK_NAME)
            val until = SystemClock.elapsedRealtime() + TAKEOVER_WAIT_MS
            while (running.get() && SystemClock.elapsedRealtime() < until) delay(500)
            if (!running.compareAndSet(false, true)) {
                Log.w(TAG, "Дневной проход не уступил за ${TAKEOVER_WAIT_MS / 1000} с")
                return Result.retry()
            }
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
            isStopped = { stageHalted() },
        )
        OnnxRuntimeHolder.intraOpThreads = pace.mode.threads
        Log.i(TAG, "Темп индексации: ${pace.describe()}")
        logWorkStates()
        foregroundBlocked = System.currentTimeMillis() < c.settings.foregroundBlockedUntil()
        if (foregroundBlocked) Log.i(TAG, "Foreground-сервис временно недоступен — работаем частями в фоне")
        // Пока обязательных моделей нет, разбирать медиатеку нечем: любой проход сейчас —
        // это работа, которую придётся повторить после загрузки (кадр декодируется заново),
        // плюс конкуренция за процессор и батарею с самой загрузкой. Поэтому этапы ждут
        // моделей целиком, а не каждый решает за себя.
        val waitingForModels = c.modelDownloads.isConfigured && c.modelCatalog.missing(ModelGroup.required).isNotEmpty()
        // Разрешение на запуск сервиса действует лишь первые секунды работы задачи (expedited),
        // поэтому просим передний план сразу, а не когда дойдём до тяжёлого этапа: иначе система
        // отказывает («Background started FGS: Disallowed»), и задачу душат окнами по 15 секунд.
        if (!foregroundBlocked && !waitingForModels && hasPendingWork(c)) tryStartForeground(0, 0)
        // Список снимков обновляем всегда: галерея должна показывать фото и без всякого AI.
        PerfStats.measure("sync.mediastore") { c.mediaRepository.syncWithMediaStore() }
        if (waitingForModels) {
            Log.i(TAG, "Обязательные модели ещё не скачаны — анализ отложен до конца загрузки")
            return Result.success()
        }

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
        if (halted()) {
            Log.i(TAG, if (pace.exhausted()) "Окно тихого прохода вышло — продолжим в следующий раз" else "Проход остановлен системой")
            return checkStop(c)
        }
        Log.i(TAG, "Проход завершён: работы больше нет")
        return Result.success()
    }

    /**
     * Состояние обеих задач индексации в журнал: без этого не видно, почему ночной проход
     * не случился — ждёт ли он условий, откладывается ли системой или давно отработал.
     */
    private suspend fun logWorkStates() {
        val wm = WorkManager.getInstance(applicationContext)
        for (name in listOf(IndexingScheduler.WORK_NAME, IndexingScheduler.NIGHT_WORK_NAME)) {
            val states = runCatching { wm.getWorkInfosForUniqueWorkFlow(name).first() }
                .getOrDefault(emptyList())
                .joinToString { "${it.state}${if (it.runAttemptCount > 0) " (попыток ${it.runAttemptCount})" else ""}" }
            Log.i(TAG, "Задача $name: ${states.ifEmpty { "нет" }}")
        }
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
        startStage()
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
                    if (stageHalted()) break
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
        c.locationIndexer.run(isStopped = { stageHalted() }) { processed ->
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
            val (_, found) = c.textIndexer.run(isStopped = { stageHalted() }) { processed ->
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
        c.qualityIndexer.run(isStopped = { stageHalted() }) { processed ->
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
            c.faceReembedder.run(isStopped = { stageHalted() }) { processed ->
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
            // Кандидатов на пересмотр людных кадров отбираем до моделей лиц: отбор идёт по
            // CLIP, а вместе с ArcFace они в память не помещаются.
            val crowdIds = crowdCandidates(c)
            if (crowdIds.isNotEmpty()) {
                c.models.release(ModelId.CLIP_TEXT, ModelId.CLIP_TEXT_MULTILINGUAL, ModelId.CLIP_IMAGE)
            }
            val (_, found) = c.faceIndexer.run(isStopped = { stageHalted() }) { processed ->
                reportProgress(processed, total)
                if (processed - reportedAt >= PERF_REPORT_EVERY) {
                    logPerf(processed - reportedAt, SystemClock.elapsedRealtime() - windowStart, processed, total, "faces.total")
                    reportedAt = processed
                    windowStart = SystemClock.elapsedRealtime()
                }
                pace.tick()
            }
            // Пересмотр людных кадров — пока модели лиц ещё загружены.
            val recrowded = crowdPass(c, crowdIds)
            // Модели лиц (ArcFace ~174 МБ) освобождаются до проверки через CLIP: вместе они
            // не помещаются — система убивала процесс.
            c.models.release(ModelId.FACE_DETECT, ModelId.FACE_EMBED, ModelId.FACE_EMBED_HQ)
            val removed = c.faceIndexer.verifyExisting(isStopped = { stageHalted() })
            if (removed > 0) Log.i(TAG, "Удалено ложных срабатываний лиц: $removed")
            return found + removed + recrowded
        } finally {
            c.models.release(ModelId.FACE_DETECT, ModelId.FACE_EMBED, ModelId.FACE_EMBED_HQ, ModelId.CLIP_IMAGE, ModelId.CLIP_TEXT)
        }
    }

    /**
     * Разовый пересмотр людных кадров: на них лица ищутся ещё и по частям кадра, и потолок
     * лиц на фото поднят. Кандидаты — кадры, где лиц уже много, плюс кадры, которые CLIP
     * считает групповыми (там детектор как раз мог найти одно-два лица из тридцати).
     *
     * Идёт по возрастанию id с сохранением курсора, поэтому прерывание не теряет работу.
     */
    private suspend fun crowdPass(c: AppContainer, candidates: List<Long>): Int {
        if (candidates.isEmpty()) return 0
        Log.i(TAG, "Пересмотр людных кадров: ${candidates.size} шт.")
        // Своя подпись этапа: иначе в уведомлении остаётся счёт от обычного поиска лиц.
        enterPhase(IndexingPhase.FACES, candidates.size, foreground = candidates.size >= FOREGROUND_THRESHOLD)
        val (processed, found) = c.faceIndexer.rescan(candidates, isStopped = { stageHalted() }) { done, lastId ->
            reportProgress(done, candidates.size)
            // Курсор — по пройденным id: после перезапуска пересмотр продолжится отсюда.
            c.settings.setCrowdPassCursor(lastId)
            pace.tick()
        }
        if (!halted()) c.settings.setCrowdPassDone(FaceIndexer.CROWD_PASS_VERSION)
        Log.i(TAG, "Пересмотрено людных кадров: $processed, лиц на них: $found")
        return found
    }

    /**
     * Кадры, которые стоит пересмотреть: с множеством лиц и похожие на групповые по CLIP.
     * Пусто, если пересмотр уже сделан или начатый дошёл до конца — тогда же отмечаем версию.
     */
    private suspend fun crowdCandidates(c: AppContainer): List<Long> {
        if (c.settings.crowdPassVersion() >= FaceIndexer.CROWD_PASS_VERSION) return emptyList()
        val cursor = c.settings.crowdPassCursor()
        val crowded = c.faceIndexer.crowdedMedia().toMutableSet()
        val query = runCatching { c.textEmbedder.embed(CROWD_QUERY) }.getOrNull()
        if (query != null) {
            crowded += c.embeddingIndex.search(query, limit = CROWD_CANDIDATES).map { it.first }
        }
        val rest = crowded.filter { it > cursor }.sorted()
        if (rest.isEmpty()) c.settings.setCrowdPassDone(FaceIndexer.CROWD_PASS_VERSION)
        return rest
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
        startStage()
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

        /**
         * Проход поставлен ночным расписанием. Темп он не задаёт — его решает
         * [IndexingPace] по состоянию телефона; признак нужен лишь для того, чтобы такой
         * проход мог занять место дневного, а не ушёл в очередь.
         */
        const val KEY_FULL_PACE = "full_pace"
        private const val BATCH_SIZE = 16
        private const val PAGE_SIZE = 64

        /** Сколько подготовленных кадров держать впереди инференса (~0.6 МБ битмапа на кадр). */
        private const val PREFETCH = 4
        private const val FOREGROUND_THRESHOLD = 32
        private const val NOTIFICATION_THROTTLE_MS = 1_000L
        private const val PERF_TAG = "IndexPerf"
        private const val PERF_REPORT_EVERY = 64

        /** Описание групповой фотографии для CLIP: по нему отбираются кадры на пересмотр. */
        private const val CROWD_QUERY = "групповая фотография, много людей вместе"

        /** Сколько самых «групповых» кадров пересматривать: работа должна быть ограничена. */
        private const val CROWD_CANDIDATES = 1500

        /** Суточный лимит сервиса сбрасывается раз в сутки — столько и ждём. */
        private const val QUOTA_BACKOFF_MS = 6 * 60 * 60 * 1000L

        /** Сколько ночной проход ждёт, пока дневной освободит место. */
        private const val TAKEOVER_WAIT_MS = 30_000L

        /** Отказ из-за состояния приложения (экран заблокирован) проходит сам — ждём недолго. */
        private const val DENIED_BACKOFF_MS = 5 * 60 * 1000L
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_PHASE = "phase"
    }
}
