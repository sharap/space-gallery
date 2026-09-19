package ai.recommend.spacegallery.ml

import ai.recommend.spacegallery.ml.text.WordPieceTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WordPieceTokenizerTest {

    private val tiny = WordPieceTokenizer(
        vocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "кот", "##ик", "на", ",", "!")
            .withIndex().associate { (i, t) -> t to i },
        contextLength = 6,
    )

    @Test
    fun splitsSubwordsAndPunctuation() {
        assertEquals(listOf(2L, 4L, 5L, 7L, 6L, 3L), tiny.encode("котик, на").inputIds.toList())
    }

    @Test
    fun unknownWordBecomesUnk() {
        assertEquals(listOf(2L, 1L, 8L, 3L), tiny.encode("пёс!").inputIds.toList())
    }

    @Test
    fun truncatesKeepingSep() {
        val ids = tiny.encode("кот кот кот кот кот кот").inputIds
        assertEquals(6, ids.size)
        assertEquals(3L, ids.last())
    }

    /** Сверка с HF BertTokenizer на настоящем словаре (если модели установлены через models/install_models.sh). */
    @Test
    fun matchesHuggingFaceOnRealVocab() {
        val file = File("src/debug/assets/models/mclip_tokenizer/vocab.txt")
        assumeTrue(file.exists())
        val tokenizer = WordPieceTokenizer(file.readLines().withIndex().associate { (i, t) -> t to i })
        mapOf(
            "красный квадрат" to listOf(101, 551, 56680, 11092, 69055, 20004, 23444, 102),
            "синий круг" to listOf(101, 21536, 11550, 78217, 102),
            "кот на подоконнике" to listOf(101, 59781, 10351, 10122, 11429, 53503, 60267, 11557, 102),
            "Ёжик в тумане, 2 шт." to listOf(101, 495, 14974, 10510, 543, 37298, 14405, 10205, 117, 123, 565, 10351, 119, 102),
            "dog's toy — Café" to listOf(101, 17835, 112, 187, 99584, 100, 37065, 102),
            "東京タワー 夜景" to listOf(101, 4506, 2172, 2014, 91872, 3192, 4408, 102),
            "Selfie\twith  friends!!" to listOf(101, 34039, 10400, 10169, 21997, 106, 106, 102),
        ).forEach { (text, expected) ->
            assertEquals(text, expected, tokenizer.encode(text).inputIds.map { it.toInt() })
        }
    }
}
