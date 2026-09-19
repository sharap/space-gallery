package ai.recommend.spacegallery.ml.onnx

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Файл модели в манифесте загрузки (assets/models_manifest.json, см. models/build_manifest.py). */
@Serializable
data class ModelFile(val path: String, val group: String, val size: Long, val sha256: String)

@Serializable
data class ModelManifest(val version: Int, val files: List<ModelFile>)

/** Откуда модель: скачана (проверена по SHA-256), встроена в debug-APK или отсутствует. */
enum class ModelFileState { DOWNLOADED, BUNDLED, MISSING }

/**
 * Что должно лежать в `filesDir/models` для AI-функций и что уже есть. Скачанный файл
 * считается установленным, если рядом лежит `<файл>.sha256` с хэшем из манифеста: так
 * обновлённая в манифесте модель будет скачана заново, а хэш не пересчитывается при каждом запуске.
 */
class ModelCatalog(private val context: Context) {

    val manifest: ModelManifest by lazy {
        context.assets.open(MANIFEST).bufferedReader().use { Json.decodeFromString(ModelManifest.serializer(), it.readText()) }
    }

    val dir: File get() = File(context.filesDir, MODELS_DIR)

    fun file(entry: ModelFile) = File(dir, entry.path)

    fun state(entry: ModelFile): ModelFileState = when {
        isDownloaded(entry) -> ModelFileState.DOWNLOADED
        isBundled(entry) -> ModelFileState.BUNDLED
        else -> ModelFileState.MISSING
    }

    fun isDownloaded(entry: ModelFile): Boolean {
        val f = file(entry)
        val stamp = File(f.path + STAMP)
        return f.length() == entry.size && stamp.exists() && stamp.readText().trim() == entry.sha256
    }

    /** Файлы, которые нужно скачать (нет ни проверенной загрузки, ни копии в APK). */
    fun missing(): List<ModelFile> = manifest.files.filter { state(it) == ModelFileState.MISSING }

    fun markDownloaded(entry: ModelFile) {
        File(file(entry).path + STAMP).writeText(entry.sha256)
    }

    private fun isBundled(entry: ModelFile): Boolean =
        runCatching { context.assets.open("$MODELS_DIR/${entry.path}").close() }.isSuccess

    companion object {
        const val MANIFEST = "models_manifest.json"
        const val MODELS_DIR = "models"
        const val STAMP = ".sha256"
        const val PART = ".part"
    }
}
