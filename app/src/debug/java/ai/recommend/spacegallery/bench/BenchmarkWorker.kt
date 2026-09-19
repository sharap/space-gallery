package ai.recommend.spacegallery.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.recommend.spacegallery.SpaceGalleryApp
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Запуск (debug):
 *   adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver \
 *     --es models "clip_image_int8.onnx,nsfw_int8.onnx" --es threads "1,2,4,5,8" \
 *     --es eps "cpu,xnnpack" --es batches "1" --ei iters 20
 * Модели кладутся во внутреннее хранилище приложения (файлы, залитые adb в Android/data,
 * приложению не читаются):
 *   adb exec-in run-as ai.recommend.spacegallery sh -c 'mkdir -p files/bench && cat > files/bench/<name>.onnx' < <file>
 * Результаты: adb logcat -s OrtBench (прогон заканчивается строкой "=== done <run>").
 * Прогоны выполняются строго по очереди.
 *
 * Отменить все поставленные прогоны бенчмарка:
 *   adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action cancel
 *
 * Проверка лиц через CLIP (P «это лицо» у узнанных и неузнанных лиц):
 *   adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action faceverify
 *
 * Диагностика детекции лиц (только числа в лог):
 *   adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action facediag --ei sample 300
 *   adb shell am broadcast ... --es action facediag --es names "IMG_1.jpg,IMG_2.jpg"
 *
 * Пересчитать умные альбомы (приложение должно быть на экране — иначе фоновые ядра):
 *   adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action smart
 *
 * Переиндексировать всю медиатеку (для замеров конвейера):
 *   adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action reindex
 */
class BenchmarkReceiver : BroadcastReceiver() {

    private companion object {
        const val UNIQUE_NAME = "ort-bench"
        const val TAG_BENCH = "ort-bench"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("action") == "cancel") {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(UNIQUE_NAME)
            wm.cancelAllWorkByTag(TAG_BENCH)
            // Прогоны из ранних версий без имени и тега — отменяем по классу воркера.
            wm.cancelAllWorkByTag(BenchmarkWorker::class.java.name)
            OrtBenchmark.log("=== cancelled")
            return
        }
        if (intent.getStringExtra("action") == "smart") {
            // Пересчитать умные альбомы сразу (без ожидания индексации) и вывести замеры.
            val pending = goAsync()
            val container = (context.applicationContext as SpaceGalleryApp).container
            container.appScope.launch {
                val start = android.os.SystemClock.elapsedRealtime()
                try {
                    container.smartAlbumBuilder.rebuild()
                    OrtBenchmark.log("=== smart rebuilt in ${android.os.SystemClock.elapsedRealtime() - start} ms")
                } catch (e: Exception) {
                    OrtBenchmark.log("=== smart FAILED: $e")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.getStringExtra("action") == "faceartifact") {
            val request = OneTimeWorkRequestBuilder<FaceArtifactWorker>()
                .setInputData(workDataOf("name" to (intent.getStringExtra("name") ?: "кружка")))
                .addTag(TAG_BENCH)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return
        }
        if (intent.getStringExtra("action") == "faceverify") {
            val request = OneTimeWorkRequestBuilder<FaceVerifyWorker>()
                .setInputData(workDataOf("names" to (intent.getStringExtra("names") ?: "")))
                .addTag(TAG_BENCH)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return
        }
        if (intent.getStringExtra("action") == "facediag") {
            // Долгая работа — в WorkManager: удерживать ресивер через goAsync дольше ~10 с нельзя (ANR).
            val request = OneTimeWorkRequestBuilder<FaceDiagnosticsWorker>()
                .setInputData(
                    workDataOf(
                        "sample" to intent.getIntExtra("sample", 300),
                        "names" to (intent.getStringExtra("names") ?: ""),
                    )
                )
                .addTag(TAG_BENCH)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return
        }
        if (intent.getStringExtra("action") == "people") {
            val pending = goAsync()
            val container = (context.applicationContext as SpaceGalleryApp).container
            container.appScope.launch {
                val start = android.os.SystemClock.elapsedRealtime()
                try {
                    container.peopleBuilder.rebuild()
                    OrtBenchmark.log("=== people rebuilt in ${android.os.SystemClock.elapsedRealtime() - start} ms")
                } catch (e: Exception) {
                    OrtBenchmark.log("=== people FAILED: $e")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.getStringExtra("action") == "reindex") {
            val pending = goAsync()
            val container = (context.applicationContext as SpaceGalleryApp).container
            container.appScope.launch {
                container.database.analysisDao().clear()
                container.embeddingIndex.invalidate()
                container.indexingScheduler.ensureIndexingNow(onlyWhileCharging = false)
                OrtBenchmark.log("=== reindex requested")
                pending.finish()
            }
            return
        }
        val request = OneTimeWorkRequestBuilder<BenchmarkWorker>()
            .setInputData(
                workDataOf(
                    "models" to (intent.getStringExtra("models") ?: ""),
                    "threads" to (intent.getStringExtra("threads") ?: "4"),
                    "eps" to (intent.getStringExtra("eps") ?: "cpu"),
                    "batches" to (intent.getStringExtra("batches") ?: "1"),
                    "iters" to intent.getIntExtra("iters", 20),
                    "warmup" to intent.getIntExtra("warmup", 3),
                    "maxHeadroom" to intent.getFloatExtra("maxHeadroom", 0.5f),
                    "run" to (intent.getStringExtra("run") ?: "bench"),
                )
            )
            .addTag(TAG_BENCH)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class BenchmarkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private companion object {
        const val INDEX_WORK_NAME = "media-index"
        const val MAX_COOLDOWN_MS = 5 * 60_000L
    }

    /** Запас до термотроттлинга: 0 — холодный, 1.0 — начинается троттлинг (Android 11+). */
    private var lastHeadroomAt = 0L

    /** Система возвращает NaN, если спрашивать чаще раза в секунду, — выдерживаем паузу. */
    private suspend fun thermalHeadroom(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        val wait = 1_100 - (SystemClock.elapsedRealtime() - lastHeadroomAt)
        if (wait > 0) delay(wait)
        lastHeadroomAt = SystemClock.elapsedRealtime()
        return applicationContext.getSystemService(PowerManager::class.java).getThermalHeadroom(0)
    }

    /** Ждёт, пока телефон остынет до [maxHeadroom], чтобы конфигурации сравнивались честно. */
    private suspend fun coolDown(maxHeadroom: Float): Float {
        val start = SystemClock.elapsedRealtime()
        var h = thermalHeadroom()
        while (!h.isNaN() && h > maxHeadroom && SystemClock.elapsedRealtime() - start < MAX_COOLDOWN_MS) {
            delay(5_000)
            h = thermalHeadroom()
        }
        return h
    }

    override suspend fun doWork(): Result {
        val dir = File(applicationContext.filesDir, "bench")
        // Индексация делит с бенчмарком CPU — останавливаем её на время замеров.
        WorkManager.getInstance(applicationContext).cancelUniqueWork(INDEX_WORK_NAME)
        fun csv(key: String) = inputData.getString(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val models = csv("models").ifEmpty { dir.list()?.filter { it.endsWith(".onnx") }?.sorted().orEmpty() }
        val iters = inputData.getInt("iters", 20)
        val warmup = inputData.getInt("warmup", 3)
        OrtBenchmark.log("=== start: ${models.size} models, iters=$iters, warmup=$warmup")
        for (model in models) {
            for (ep in csv("eps")) for (threads in csv("threads").map(String::toInt)) for (batch in csv("batches").map(String::toInt)) {
                if (isStopped) return Result.success()
                val config = OrtBenchmark.Config(File(dir, model), threads, ep, batch)
                val headroom = coolDown(inputData.getFloat("maxHeadroom", 0.5f))
                runCatching { OrtBenchmark.run(config, warmup, iters) }
                    .onSuccess { OrtBenchmark.log("$it | headroom before %.2f after %.2f".format(headroom, thermalHeadroom())) }
                    .onFailure { OrtBenchmark.log("${config.model.name} ep=$ep thr=$threads batch=$batch FAILED: ${it.message?.take(200)}") }
            }
        }
        OrtBenchmark.log("=== done ${inputData.getString("run")}")
        return Result.success()
    }
}

class FaceDiagnosticsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SpaceGalleryApp).container
        val names = inputData.getString("names").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        try {
            FaceDiagnostics(container).run(inputData.getInt("sample", 300), names)
        } catch (e: Exception) {
            OrtBenchmark.log("=== facediag FAILED: $e")
        }
        return Result.success()
    }
}

class FaceVerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SpaceGalleryApp).container
        val names = inputData.getString("names").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        try {
            FaceVerifyDiagnostics(container).run(names)
        } catch (e: Exception) {
            OrtBenchmark.log("=== faceverify FAILED: $e")
        }
        return Result.success()
    }
}

class FaceArtifactWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SpaceGalleryApp).container
        try {
            FaceArtifactDiagnostics(container).run(inputData.getString("name") ?: "кружка")
        } catch (e: Exception) {
            OrtBenchmark.log("=== faceartifact FAILED: $e")
        }
        return Result.success()
    }
}
