package ai.recommend.spacegallery.ml.text

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Токенизатор текстового энкодера. Реализация зависит от модели (CLIP BPE, BERT WordPiece и т.п.). */
interface Tokenizer {
    /** Максимальная длина последовательности, включая спецтокены. */
    val contextLength: Int

    /** input_ids и attention_mask одинаковой длины (≤ [contextLength], возможно с паддингом). */
    fun encode(text: String): Encoding
}

class Encoding(val inputIds: LongArray, val attentionMask: LongArray)

fun interface TokenizerProvider {
    suspend fun get(): Tokenizer?

    companion object {
        private const val TAG = "TokenizerProvider"

        /** Загружает токенизатор один раз при первом обращении; null — если файлов словаря нет. */
        fun lazy(name: String, load: () -> Tokenizer): TokenizerProvider {
            val mutex = Mutex()
            var loaded: Result<Tokenizer>? = null
            return TokenizerProvider {
                mutex.withLock {
                    val result = loaded ?: withContext(Dispatchers.IO) {
                        runCatching(load).onFailure { Log.w(TAG, "Токенизатор $name не найден", it) }
                    }.also { loaded = it }
                    result.getOrNull()
                }
            }
        }
    }
}

/**
 * Файл словаря из `filesDir/<path>` (скачан вместе с моделью), иначе из assets (debug-сборка).
 */
internal fun openModelFile(context: Context, path: String): InputStream {
    val downloaded = File(context.filesDir, path)
    return if (downloaded.exists()) downloaded.inputStream() else context.assets.open(path)
}
