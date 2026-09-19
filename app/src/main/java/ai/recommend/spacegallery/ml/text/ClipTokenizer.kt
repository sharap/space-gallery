package ai.recommend.spacegallery.ml.text

import ai.recommend.spacegallery.ml.onnx.ModelSpecs
import android.content.Context
import org.json.JSONObject

/**
 * Byte-level BPE токенизатор OpenAI CLIP.
 * Словарь `models/clip_tokenizer/{vocab.json,merges.txt}` скачивается вместе с моделью в `filesDir`;
 * в debug-сборке он также лежит в assets.
 *
 * Оригинальный CLIP обучен на английском; запросы на других языках обрабатывает
 * [WordPieceTokenizer] + многоязычный энкодер (см. [TextEmbedder]).
 */
class ClipTokenizer(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    override val contextLength: Int = ModelSpecs.CLIP_CONTEXT_LENGTH,
) : Tokenizer {

    private val sot = encoder.getValue("<|startoftext|>")
    private val eot = encoder.getValue("<|endoftext|>")
    private val cache = HashMap<String, List<String>>()

    override fun encode(text: String): Encoding {
        val clean = text.trim().replace(WHITESPACE, " ").lowercase()
        val ids = ArrayList<Int>(contextLength)
        ids += sot
        for (match in PATTERN.findAll(clean)) {
            val token = match.value.toByteArray(Charsets.UTF_8)
                .joinToString("") { BYTE_ENCODER[it.toInt() and 0xFF].toString() }
            bpe(token).mapNotNullTo(ids) { encoder[it] }
        }
        val truncated = ids.take(contextLength - 1) + eot
        // Как в HF CLIPTokenizer: pad_token = <|endoftext|>.
        return Encoding(
            inputIds = LongArray(contextLength) { i -> (truncated.getOrNull(i) ?: eot).toLong() },
            attentionMask = LongArray(contextLength) { i -> if (i < truncated.size) 1L else 0L },
        )
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
            val vocabJson = openModelFile(context, "$DIR/vocab.json").bufferedReader().use { it.readText() }
            val vocab = JSONObject(vocabJson).let { json ->
                json.keys().asSequence().associateWith { json.getInt(it) }
            }
            val ranks = HashMap<Pair<String, String>, Int>()
            openModelFile(context, "$DIR/merges.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#version") }
                    .forEachIndexed { rank, line ->
                        val (a, b) = line.split(' ', limit = 2)
                        ranks[a to b] = rank
                    }
            }
            return ClipTokenizer(vocab, ranks)
        }

        fun provider(context: Context): TokenizerProvider =
            TokenizerProvider.lazy("CLIP") { load(context) }
    }
}
