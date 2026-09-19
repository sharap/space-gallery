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
 */
class ModelProvider(
    private val context: Context,
    private val onnx: OnnxRuntimeHolder,
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<ModelId, OrtSession>()
    private val failed = mutableSetOf<ModelId>()

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

    /** Освобождает нативную память (например, после окончания индексации). */
    suspend fun release(vararg ids: ModelId) = mutex.withLock {
        ids.forEach { sessions.remove(it)?.close() }
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
     * Скачанная модель используется как есть. Модель из assets (только debug) один раз копируется
     * во внутреннее хранилище: ORT создаёт сессию из файла.
     * TODO: загрузчик моделей для релиза (WorkManager + проверка SHA-256) пишет в [userModelFile].
     */
    private fun resolveFile(id: ModelId): File? {
        userModelFile(id).takeIf { it.exists() }?.let { return it }
        if (!assetExists(id)) return null
        val cached = File(File(context.noBackupFilesDir, MODELS_DIR), id.fileName)
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
