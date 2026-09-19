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
                onnx.createSession(file.absolutePath).also { sessions[id] = it }
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

    private fun userModelFile(id: ModelId) = File(File(context.filesDir, MODELS_DIR), id.fileName)

    private fun assetExists(id: ModelId): Boolean =
        runCatching { context.assets.list(MODELS_DIR)?.contains(id.fileName) == true }.getOrDefault(false)

    /** ORT на Android создаёт сессию из файла, поэтому модель из assets один раз копируется во внутреннее хранилище. */
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
