package ai.recommend.spacegallery.search.text

import ai.recommend.spacegallery.data.db.MediaCodeEntity
import ai.recommend.spacegallery.data.db.TextDao
import ai.recommend.spacegallery.data.db.TextLineEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Что нашлось на снимке: строки текста и коды. */
class PhotoText(val lines: List<TextLineEntity>, val codes: List<MediaCodeEntity>) {
    val isEmpty: Boolean get() = lines.isEmpty() && codes.isEmpty()
    val text: String get() = lines.joinToString("\n") { it.text }
}

class TextRepository(private val dao: TextDao) {

    fun observe(mediaId: Long): Flow<PhotoText> =
        combine(dao.observeLines(mediaId), dao.observeCodes(mediaId)) { lines, codes -> PhotoText(lines, codes) }

    /**
     * Снимки, где встречается запрос. Каждое слово ищется по началу (`парол*` находит «пароль»),
     * кавычки и служебные символы FTS убираются, иначе запрос вида «?» уронит поиск.
     */
    suspend fun search(query: String, limit: Int = 300): List<Long> {
        val terms = query.split(Regex("\\s+"))
            .map { it.filter(Char::isLetterOrDigit) }
            .filter { it.length >= MIN_TERM }
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" ") { "$it*" }
        return runCatching { dao.search(match, limit) }.getOrDefault(emptyList())
    }

    suspend fun stats(): Pair<Int, Int> = dao.countWithText() to dao.countCodes()

    private companion object {
        const val MIN_TERM = 2
    }
}
