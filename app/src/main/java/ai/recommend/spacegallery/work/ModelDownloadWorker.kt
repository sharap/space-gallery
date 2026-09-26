package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.BuildConfig
import ai.recommend.spacegallery.MainActivity
import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.ml.onnx.ModelCatalog
import ai.recommend.spacegallery.ml.onnx.ModelFile
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Скачивает недостающие AI-модели из [BuildConfig.MODELS_BASE_URL] в `filesDir/models`:
 * докачка после обрыва (HTTP Range в `<файл>.part`), проверка SHA-256 по манифесту и только
 * потом — переименование в рабочий файл. Работает как foreground service с прогрессом.
 * Ошибка сети — повтор WorkManager с задержкой; уже скачанное не теряется.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var lastNotificationAt = 0L
    private var proxyLogged = false

    override suspend fun doWork(): Result {
        val c = (applicationContext as SpaceGalleryApp).container
        val catalog = c.modelCatalog
        // force (отладка): качать и то, что встроено в debug-APK.
        // Список групп не задан — берём тот, что выбрал пользователь: качать всё подряд
        // нельзя, от корейского текста и точных лиц можно было отказаться.
        val groups = inputData.getStringArray(KEY_GROUPS)?.toSet()?.takeIf { it.isNotEmpty() }
            ?: c.settings.current().modelGroups
        val candidates =
            if (inputData.getBoolean(KEY_FORCE, false)) catalog.manifest.files.filterNot(catalog::isDownloaded)
            else catalog.missing()
        val files = if (groups.isEmpty()) candidates else candidates.filter { it.group in groups }
        if (files.isEmpty()) return Result.success()
        if (BuildConfig.MODELS_BASE_URL.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to ERROR_NO_SOURCE))

        val total = files.sumOf { it.size }
        var done = 0L
        setForegroundSafely(0, total)
        try {
            for (entry in files) {
                val before = done
                downloadFile(catalog, entry) { bytes -> report(before + bytes, total, entry.path) }
                done += entry.size
                report(done, total, entry.path, force = true)
                if (isStopped) return Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChecksumException) {
            Log.e(TAG, "Модель повреждена при загрузке", e)
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to ERROR_CHECKSUM))
        } catch (e: IOException) {
            Log.w(TAG, "Ошибка загрузки моделей (попытка ${runAttemptCount + 1})", e)
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to ERROR_NETWORK))
        }
        // Модели на месте — анализ медиатеки (поиск, лица, деликатное) можно запускать.
        c.indexingScheduler.ensureIndexingNow(c.settings.current().indexOnlyWhileCharging)
        return Result.success()
    }

    private suspend fun downloadFile(catalog: ModelCatalog, entry: ModelFile, onBytes: suspend (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val target = catalog.file(entry)
            target.parentFile?.mkdirs()
            val part = File(target.path + ModelCatalog.PART)
            if (part.length() > entry.size) part.delete()
            var offset = part.length()
            if (offset < entry.size) {
                val connection = open(URL(BuildConfig.MODELS_BASE_URL.trimEnd('/') + "/" + entry.path))
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                    val code = connection.responseCode
                    val append = when (code) {
                        HttpURLConnection.HTTP_PARTIAL -> true
                        HttpURLConnection.HTTP_OK -> false // сервер не умеет Range — качаем заново
                        else -> throw IOException("HTTP $code для ${entry.path}")
                    }
                    if (!append) offset = 0
                    connection.inputStream.use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                if (isStopped) return@withContext
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                offset += n
                                onBytes(offset)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            if (part.length() != entry.size || sha256(part) != entry.sha256) {
                part.delete()
                throw ChecksumException(entry.path)
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить ${entry.path}")
            catalog.markDownloaded(entry)
        }

    /**
     * Соединение через прокси, настроенный в системе.
     *
     * `HttpURLConnection` спрашивает `ProxySelector`, а тот на Android знает не про всякий
     * прокси: настроенный для конкретной сети Wi-Fi (и тем более PAC) до него доходит не
     * всегда, и загрузка молча идёт мимо — в сетях, где прямой выход закрыт, она просто
     * не работает. Поэтому спрашиваем систему напрямую.
     */
    private fun open(url: URL): HttpURLConnection {
        val proxy = systemProxy(url)
        return (if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpURLConnection
    }

    private fun systemProxy(url: URL): Proxy? {
        val info = applicationContext.getSystemService(ConnectivityManager::class.java)?.defaultProxy ?: return null
        // При PAC система поднимает свой прокси на localhost и отдаёт его здесь же.
        val host = info.host?.takeIf { it.isNotEmpty() } ?: return null
        val excluded = info.exclusionList.orEmpty().any { rule ->
            val suffix = rule.trim().removePrefix("*").removePrefix(".")
            suffix.isNotEmpty() && (url.host == suffix || url.host.endsWith(".$suffix"))
        }
        if (excluded) return Proxy.NO_PROXY
        if (!proxyLogged) {
            proxyLogged = true
            Log.i(TAG, "Модели качаются через системный прокси $host:${info.port}")
        }
        return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, info.port))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun report(done: Long, total: Long, file: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < PROGRESS_THROTTLE_MS) return
        lastNotificationAt = now
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_FILE to file))
        setForegroundSafely(done, total)
    }

    private suspend fun setForegroundSafely(done: Long, total: Long) {
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground service недоступен, качаем в фоне", e)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val context = applicationContext
        createChannel(context)
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_indexing)
            .setContentTitle(context.getString(R.string.models_downloading))
            .setContentText(context.getString(R.string.models_progress, done / MB, total / MB))
            .setProgress(100, percent, total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(0, context.getString(R.string.action_cancel), WorkManager.getInstance(context).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private class ChecksumException(path: String) : IOException("SHA-256 не совпал: $path")

    companion object {
        private const val TAG = "ModelDownload"
        private const val CHANNEL_ID = "models"
        private const val NOTIFICATION_ID = 1002
        private const val BUFFER = 64 * 1024
        private const val TIMEOUT_MS = 30_000
        private const val MAX_ATTEMPTS = 5
        private const val PROGRESS_THROTTLE_MS = 500L
        private const val MB = 1024 * 1024
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FILE = "file"
        const val KEY_ERROR = "error"
        const val KEY_FORCE = "force"
        const val KEY_GROUPS = "groups"
        const val ERROR_NO_SOURCE = "no_source"
        const val ERROR_CHECKSUM = "checksum"
        const val ERROR_NETWORK = "network"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.models_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
