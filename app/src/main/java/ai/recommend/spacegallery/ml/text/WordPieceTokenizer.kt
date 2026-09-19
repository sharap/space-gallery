package ai.recommend.spacegallery.ml.text

import android.content.Context

/**
 * BERT WordPiece-токенизатор (эквивалент HF BertTokenizer с do_lower_case = false,
 * strip_accents = None, tokenize_chinese_chars = true).
 * Используется многоязычным текстовым энкодером clip-ViT-B-32-multilingual-v1.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    override val contextLength: Int = 128,
    private val lowercase: Boolean = false,
) : Tokenizer {

    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")
    private val unk = vocab.getValue("[UNK]")

    override fun encode(text: String): Encoding {
        val ids = ArrayList<Int>()
        ids += cls
        for (word in basicTokenize(text)) {
            wordPiece(word).forEach { ids += it }
            if (ids.size >= contextLength - 1) break
        }
        val truncated = ids.take(contextLength - 1) + sep
        // Без паддинга: энкодер использует mean pooling по маске, лишние токены только тратят время.
        return Encoding(
            inputIds = LongArray(truncated.size) { truncated[it].toLong() },
            attentionMask = LongArray(truncated.size) { 1L },
        )
    }

    /** Очистка, разбиение по пробелам, пунктуации и CJK-иероглифам. */
    private fun basicTokenize(text: String): List<String> {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> sb.append(' ')
                isCjk(cp) || isPunctuation(cp) -> sb.append(' ').appendCodePoint(cp).append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        val cleaned = if (lowercase) sb.toString().lowercase() else sb.toString()
        return cleaned.split(' ').filter { it.isNotEmpty() }
    }

    /** Жадный поиск самого длинного префикса в словаре; продолжения помечаются «##». */
    private fun wordPiece(word: String): List<Int> {
        if (word.codePointCount(0, word.length) > MAX_CHARS_PER_WORD) return listOf(unk)
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                found = vocab[sub]
                if (found != null) break
                end = word.offsetByCodePoints(end, -1)
            }
            if (found == null) return listOf(unk)
            pieces += found
            start = end
        }
        return pieces
    }

    companion object {
        private const val DIR = "models/mclip_tokenizer"
        private const val MAX_CHARS_PER_WORD = 100

        fun load(context: Context): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(128_000)
            openModelFile(context, "$DIR/vocab.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, token -> vocab[token] = index }
            }
            return WordPieceTokenizer(vocab)
        }

        fun provider(context: Context): TokenizerProvider =
            TokenizerProvider.lazy("WordPiece") { load(context) }

        private fun isWhitespace(cp: Int) =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            val t = Character.getType(cp)
            return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()
        }

        private fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        private fun isCjk(cp: Int) =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
                cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
                cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}
