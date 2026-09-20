package ai.recommend.spacegallery.ml.onnx

import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Лениво создаёт и кеширует ONNX-сессии.
 * Если модели нет на устройстве — возвращает null, и соответствующая AI-функция
 * деградирует мягко (галерея продолжает работать без неё).
 *
 * Сессию нельзя закрывать, пока по ней идёт инференс: нативный код падает по SIGABRT
 * («pthread_mutex_lock called on a destroyed mutex»). Поэтому работа с сессией — только через
 * [use], а [release] ждёт, пока текущие вычисления закончатся.
 */
class ModelProvider(
    private val context: Context,
    private val onnx: OnnxRuntimeHolder,
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<ModelId, OrtSession>()
    private val failed = mutableSetOf<ModelId>()

    /** Сколько вычислений сейчас идёт по каждой сессии: пока > 0, закрывать её нельзя. */
    private val inUse = mutableMapOf<ModelId, Int>()
    private val idle = kotlinx.coroutines.sync.Semaphore(1)

    fun isAvailable(id: ModelId): Boolean =
        id !in failed && (userModelFile(id).exists() || assetExists(id))

    suspend fun session(id: ModelId): OrtSession? = mutex.withLock {
        sessions[id]?.let { return it }
        if (id in failed) return null
        withContext(Dispatchers.IO) {
            runCatching {
                val file = resolveFile(id) ?: return@withContext null
                val started = System.nanoTime()
                onnx.createSession(file.absolutePath).also {
                    sessions[id] = it
                    Log.i(TAG, "Сессия ${id.fileName} создана за ${(System.nanoTime() - started) / 1_000_000} мс")
                }
            }.onFailure {
                Log.e(TAG, "Не удалось загрузить модель ${id.fileName}", it)
                failed += id
            }.getOrNull()
        }
    }

    val env get() = onnx.env

    /**
     * Выполняет [block] на сессии модели, не давая закрыть её в это время.
     * null — модели нет (функция просто отключается).
     */
    suspend fun <T> use(id: ModelId, block: suspend (OrtSession) -> T): T? {
        val session = session(id) ?: return null
        mutex.withLock { inUse[id] = (inUse[id] ?: 0) + 1 }
        try {
            return block(session)
        } finally {
            mutex.withLock { inUse[id] = (inUse[id] ?: 1) - 1 }
        }
    }

    /**
     * Освобождает нативную память (например, после окончания индексации). Сессии, по которым
     * прямо сейчас идут вычисления, не закрываются — иначе процесс падает в нативном коде.
     */
    suspend fun release(vararg ids: ModelId) = mutex.withLock {
        for (id in ids) {
            if ((inUse[id] ?: 0) > 0) {
                Log.i(TAG, "Модель ${id.fileName} сейчас используется — не выгружаем")
                continue
            }
            sessions.remove(id)?.close()
        }
    }

    /**
     * Отпечаток файла модели (размер, дата) — ключ для кешей производных данных,
     * например эмбеддингов тем умных альбомов. null — модели нет.
     */
    fun fingerprint(id: ModelId): String? {
        userModelFile(id).takeIf { it.exists() }?.let { return "u:${it.length()}:${it.lastModified()}" }
        if (!assetExists(id)) return null
        // Модели в assets не сжимаются (noCompress), поэтому длина доступна через openFd.
        return runCatching { context.assets.openFd("$MODELS_DIR/${id.fileName}").use { "a:${it.length}" } }.getOrNull()
    }

    private fun userModelFile(id: ModelId) = File(File(context.filesDir, MODELS_DIR), id.fileName)

    private fun assetExists(id: ModelId): Boolean =
        runCatching { context.assets.list(MODELS_DIR)?.contains(id.fileName) == true }.getOrDefault(false)

    /**
     * Скачанная модель используется как есть. Модель из assets (только debug) копируется во
     * внутреннее хранилище: ORT создаёт сессию из файла. Копия обновляется, если модель в assets
     * заменили (иначе после смены модели приложение продолжило бы считать старой).
     */
    private fun resolveFile(id: ModelId): File? {
        userModelFile(id).takeIf { it.exists() }?.let { return it }
        if (!assetExists(id)) return null
        val cached = File(File(context.noBackupFilesDir, MODELS_DIR), id.fileName)
        val assetLength = runCatching { context.assets.openFd("$MODELS_DIR/${id.fileName}").use { it.length } }.getOrNull()
        if (cached.exists() && assetLength != null && cached.length() != assetLength) {
            Log.i(TAG, "Модель ${id.fileName} в assets изменилась — обновляем копию")
            cached.delete()
        }
        if (!cached.exists()) {
            cached.parentFile?.mkdirs()
            val tmp = File(cached.path + ".tmp")
            context.assets.open("$MODELS_DIR/${id.fileName}").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.renameTo(cached)
        }
        return cached
    }

    private companion object {
        const val TAG = "ModelProvider"
        const val MODELS_DIR = "models"
    }
}
