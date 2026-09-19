package ai.recommend.spacegallery.ml.text

import ai.recommend.spacegallery.ml.onnx.ModelSpecs
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Byte-level BPE токенизатор OpenAI CLIP.
 * Словарь: `assets/models/clip_tokenizer/vocab.json` и `merges.txt` (из HF openai/clip-vit-base-patch32).
 *
 * Важно: оригинальный CLIP обучен на английском. Для запросов на русском нужен
 * мультиязычный текстовый энкодер (см. models/README.md).
 */
class ClipTokenizer(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    override val contextLength: Int = ModelSpecs.CLIP_CONTEXT_LENGTH,
) : Tokenizer {

    private val sot = encoder.getValue("<|startoftext|>")
    private val eot = encoder.getValue("<|endoftext|>")
    private val cache = HashMap<String, List<String>>()

    override fun encode(text: String): LongArray {
        val clean = text.trim().replace(WHITESPACE, " ").lowercase()
        val ids = ArrayList<Int>(contextLength)
        ids += sot
        for (match in PATTERN.findAll(clean)) {
            val token = match.value.toByteArray(Charsets.UTF_8)
                .joinToString("") { BYTE_ENCODER[it.toInt() and 0xFF].toString() }
            bpe(token).mapNotNullTo(ids) { encoder[it] }
        }
        val truncated = ids.take(contextLength - 1) + eot
        return LongArray(contextLength) { i -> truncated.getOrNull(i)?.toLong() ?: 0L }
    }

    private fun bpe(token: String): List<String> = synchronized(cache) {
        cache.getOrPut(token) {
            var word = token.map { it.toString() }.toMutableList()
            word[word.lastIndex] = word.last() + "</w>"
            while (word.size > 1) {
                val best = word.zipWithNext()
                    .minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE }
                    ?.takeIf { it in bpeRanks } ?: break
                val merged = ArrayList<String>(word.size)
                var i = 0
                while (i < word.size) {
                    if (i < word.lastIndex && word[i] == best.first && word[i + 1] == best.second) {
                        merged += best.first + best.second
                        i += 2
                    } else {
                        merged += word[i]
                        i++
                    }
                }
                word = merged
            }
            word
        }
    }

    companion object {
        private const val TAG = "ClipTokenizer"
        private const val DIR = "models/clip_tokenizer"

        private val WHITESPACE = Regex("\\s+")
        private val PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|\p{L}+|\p{N}|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE,
        )

        /** Отображение байт -> печатные unicode-символы (как в GPT-2/CLIP). */
        private val BYTE_ENCODER: CharArray = run {
            val bs = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toMutableList()
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            CharArray(256).also { out -> bs.forEachIndexed { i, b -> out[b] = cs[i].toChar() } }
        }

        fun load(context: Context): ClipTokenizer {
            val vocabJson = context.assets.open("$DIR/vocab.json").bufferedReader().use { it.readText() }
            val vocab = JSONObject(vocabJson).let { json ->
                json.keys().asSequence().associateWith { json.getInt(it) }
            }
            val ranks = HashMap<Pair<String, String>, Int>()
            context.assets.open("$DIR/merges.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#version") }
                    .forEachIndexed { rank, line ->
                        val (a, b) = line.split(' ', limit = 2)
                        ranks[a to b] = rank
                    }
            }
            return ClipTokenizer(vocab, ranks)
        }

        /** Загружает словарь один раз при первом обращении; null — если файлов нет. */
        fun lazyFromAssets(context: Context): TokenizerProvider {
            val mutex = Mutex()
            var loaded: Result<Tokenizer>? = null
            return TokenizerProvider {
                mutex.withLock {
                    val result = loaded ?: withContext(Dispatchers.IO) {
                        runCatching<Tokenizer> { load(context) }
                            .onFailure { Log.w(TAG, "CLIP tokenizer не найден в assets/$DIR", it) }
                    }.also { loaded = it }
                    result.getOrNull()
                }
            }
        }
    }
}
