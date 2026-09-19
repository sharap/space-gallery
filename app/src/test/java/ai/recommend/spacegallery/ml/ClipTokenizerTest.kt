package ai.recommend.spacegallery.ml

import ai.recommend.spacegallery.ml.text.ClipTokenizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ClipTokenizerTest {

    private val tokenizer = ClipTokenizer(
        encoder = mapOf(
            "<|startoftext|>" to 1,
            "<|endoftext|>" to 2,
            "c" to 3, "a" to 4, "t</w>" to 5,
            "ca" to 6, "cat</w>" to 7,
        ),
        bpeRanks = mapOf(("c" to "a") to 0, ("ca" to "t</w>") to 1),
        contextLength = 6,
    )

    @Test
    fun mergesIntoWholeWordAndPads() {
        val encoding = tokenizer.encode("  CAT ")
        assertEquals(listOf(1L, 7L, 2L, 2L, 2L, 2L), encoding.inputIds.toList())
        assertEquals(listOf(1L, 1L, 1L, 0L, 0L, 0L), encoding.attentionMask.toList())
    }

    @Test
    fun truncatesToContextLengthKeepingEot() {
        val encoding = tokenizer.encode("cat cat cat cat cat cat")
        assertEquals(6, encoding.inputIds.size)
        assertEquals(2L, encoding.inputIds.last())
        assertEquals(1L, encoding.attentionMask.last())
    }

    /** Сверка с HF tokenizers на настоящем словаре (если модели установлены через models/install_models.sh). */
    @Test
    fun matchesHuggingFaceOnRealVocab() {
        val dir = File("src/debug/assets/models/clip_tokenizer")
        assumeTrue(File(dir, "vocab.json").exists())
        val tokenizer = realTokenizer(dir)
        mapOf(
            "a photo of a cat" to listOf(49406, 320, 1125, 539, 320, 2368, 49407),
            "Sunset over the SEA!" to listOf(49406, 3424, 962, 518, 2102, 256, 49407),
            "dog's toy, 2 balls" to listOf(49406, 1929, 568, 5988, 267, 273, 6927, 49407),
        ).forEach { (text, expected) ->
            val ids = tokenizer.encode(text).inputIds.map { it.toInt() }
            assertEquals(text, expected, ids.take(expected.size))
        }
    }

    private fun realTokenizer(dir: File): ClipTokenizer {
        val json = JSONObject(File(dir, "vocab.json").readText())
        val vocab = json.keys().asSequence().associateWith { json.getInt(it) }
        val ranks = File(dir, "merges.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#version") }
            .withIndex()
            .associate { (i, line) -> line.split(' ', limit = 2).let { (a, b) -> (a to b) to i } }
        return ClipTokenizer(vocab, ranks)
    }
}
