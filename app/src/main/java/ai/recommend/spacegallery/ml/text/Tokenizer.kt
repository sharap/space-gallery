package ai.recommend.spacegallery.ml.text

/** Токенизатор текстового энкодера. Реализация зависит от выбранной модели (CLIP BPE, SentencePiece и т.п.). */
interface Tokenizer {
    val contextLength: Int

    /** Возвращает input_ids фиксированной длины [contextLength] (с паддингом). */
    fun encode(text: String): LongArray
}

fun interface TokenizerProvider {
    suspend fun get(): Tokenizer?
}
