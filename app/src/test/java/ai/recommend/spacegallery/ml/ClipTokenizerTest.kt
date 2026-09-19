package ai.recommend.spacegallery.ml

import ai.recommend.spacegallery.ml.text.ClipTokenizer
import org.junit.Assert.assertEquals
import org.junit.Test

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
        val ids = tokenizer.encode("  CAT ").toList()
        assertEquals(listOf(1L, 7L, 2L, 0L, 0L, 0L), ids)
    }

    @Test
    fun truncatesToContextLengthKeepingEot() {
        val ids = tokenizer.encode("cat cat cat cat cat cat")
        assertEquals(6, ids.size)
        assertEquals(2L, ids.last())
    }
}
