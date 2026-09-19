package ai.recommend.spacegallery.search

import java.util.Calendar

/** Что распознано в тексте запроса: люди, год, месяцы/сезон — и оставшийся текст для CLIP. */
data class ParsedQuery(
    val text: String,
    val personIds: Set<Long> = emptySet(),
    val year: Int? = null,
    /** Месяцы года (Calendar.JANUARY = 0 …): «в мае», «летом». */
    val months: Set<Int> = emptySet(),
    /** Города (id GeoNames): «в Сочи», «Турция» — все города страны. */
    val placeCityIds: Set<Long> = emptySet(),
    /** Распознанные слова (как в запросе) — для чипов и отключения. */
    val tokens: List<RecognizedToken> = emptyList(),
)

sealed interface RecognizedToken {
    val word: String

    data class PersonToken(override val word: String, val personId: Long, val name: String) : RecognizedToken
    data class YearToken(override val word: String, val year: Int) : RecognizedToken
    data class MonthsToken(override val word: String, val months: Set<Int>) : RecognizedToken
    data class PlaceToken(override val word: String, val name: String, val cityIds: Set<Long>) : RecognizedToken
}

/** Место для распознавания в запросе: город или страна и её города. */
data class PlaceName(val name: String, val cityIds: Set<Long>)

/**
 * Разбор запроса вида «Маша на море летом 2023»: имена людей (в любом падеже), год, месяц или
 * сезон становятся фильтрами, остальное ищется по смыслу. Работает без морфологического
 * словаря: имя совпадает, если слово = основа имени + типичное падежное окончание.
 */
object QueryParser {

    /** [ignored] — слова, распознавание которых пользователь отключил (ищутся как текст). */
    fun parse(
        query: String,
        people: Map<Long, String>,
        ignored: Set<String> = emptySet(),
        places: List<PlaceName> = emptyList(),
    ): ParsedQuery {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val recognized = arrayOfNulls<RecognizedToken>(words.size)
        for ((i, raw) in words.withIndex()) {
            val word = raw.trim(',', '.', '!', '?', ';', ':')
            if (word.lowercase() in ignored) continue
            recognized[i] = matchYear(word) ?: matchMonths(word) ?: matchPerson(word, people) ?: matchPlace(word, places)
        }
        // Предлог перед распознанным словом («с Машей», «в мае») тоже убираем из текста.
        val dropped = BooleanArray(words.size) { recognized[it] != null }
        for (i in 1 until words.size) {
            if (recognized[i] != null && words[i - 1].lowercase() in PREPOSITIONS) dropped[i - 1] = true
        }
        val tokens = recognized.filterNotNull()
        return ParsedQuery(
            text = words.filterIndexed { i, _ -> !dropped[i] }.joinToString(" "),
            personIds = tokens.filterIsInstance<RecognizedToken.PersonToken>().mapTo(LinkedHashSet()) { it.personId },
            year = tokens.filterIsInstance<RecognizedToken.YearToken>().firstOrNull()?.year,
            months = tokens.filterIsInstance<RecognizedToken.MonthsToken>().flatMapTo(HashSet()) { it.months },
            placeCityIds = tokens.filterIsInstance<RecognizedToken.PlaceToken>().flatMapTo(HashSet()) { it.cityIds },
            tokens = tokens,
        )
    }

    private val YEAR = Regex("^((?:19|20)\\d\\d)(?:г\\.?|года|году|-?м|-?го)?$", RegexOption.IGNORE_CASE)

    private fun matchYear(word: String): RecognizedToken? {
        val year = YEAR.matchEntire(word)?.groupValues?.get(1)?.toInt() ?: return null
        val now = Calendar.getInstance().get(Calendar.YEAR)
        return if (year in 1990..now + 1) RecognizedToken.YearToken(word, year) else null
    }

    private fun matchMonths(word: String): RecognizedToken? {
        val w = word.lowercase()
        val months = MONTH_WORDS.firstOrNull { (forms, _) -> w in forms }?.second ?: return null
        return RecognizedToken.MonthsToken(word, months)
    }

    private fun matchPerson(word: String, people: Map<Long, String>): RecognizedToken? {
        val w = word.lowercase()
        if (w.length < 3) return null
        for ((id, name) in people) {
            for (part in name.lowercase().split(' ', '-').filter { it.length >= 3 }) {
                if (w == part || nameForm(w, part)) return RecognizedToken.PersonToken(word, id, name)
            }
        }
        return null
    }

    private fun matchPlace(word: String, places: List<PlaceName>): RecognizedToken? {
        val w = word.lowercase()
        if (w.length < 3) return null
        for (place in places) {
            for (part in place.name.lowercase().split(' ', '-').filter { it.length >= 3 }) {
                if (w == part || nameForm(w, part)) return RecognizedToken.PlaceToken(word, place.name, place.cityIds)
            }
        }
        return null
    }

    /** «машей» — форма «маша»: основа (без конечной гласной) + падежное окончание. */
    private fun nameForm(word: String, name: String): Boolean {
        val stem = if (name.last() in VOWELS) name.dropLast(1) else name
        if (stem.length < 2 || !word.startsWith(stem)) return false
        return word.substring(stem.length) in ENDINGS
    }

    private const val VOWELS = "аяоеёиыуюэй"
    /** Только падежные окончания: «машей» — Маша, а «машина» — нет. */
    private val ENDINGS = setOf("", "а", "я", "у", "ю", "е", "и", "ы", "ой", "ей", "ом", "ем", "ём", "ою", "ею")

    private val PREPOSITIONS = setOf("с", "со", "в", "во", "у", "и", "на", "из", "with", "in", "and", "at")

    private fun m(vararg forms: String) = forms.toSet()

    private val MONTH_WORDS: List<Pair<Set<String>, Set<Int>>> = listOf(
        m("январь", "января", "январе", "january", "jan") to setOf(Calendar.JANUARY),
        m("февраль", "февраля", "феврале", "february", "feb") to setOf(Calendar.FEBRUARY),
        m("март", "марта", "марте", "march") to setOf(Calendar.MARCH),
        m("апрель", "апреля", "апреле", "april") to setOf(Calendar.APRIL),
        m("май", "мая", "мае", "may") to setOf(Calendar.MAY),
        m("июнь", "июня", "июне", "june") to setOf(Calendar.JUNE),
        m("июль", "июля", "июле", "july") to setOf(Calendar.JULY),
        m("август", "августа", "августе", "august") to setOf(Calendar.AUGUST),
        m("сентябрь", "сентября", "сентябре", "september") to setOf(Calendar.SEPTEMBER),
        m("октябрь", "октября", "октябре", "october") to setOf(Calendar.OCTOBER),
        m("ноябрь", "ноября", "ноябре", "november") to setOf(Calendar.NOVEMBER),
        m("декабрь", "декабря", "декабре", "december") to setOf(Calendar.DECEMBER),
        m("зима", "зимой", "зимы", "зиму", "winter") to setOf(Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY),
        m("весна", "весной", "весны", "весну", "spring") to setOf(Calendar.MARCH, Calendar.APRIL, Calendar.MAY),
        m("лето", "летом", "лета", "summer") to setOf(Calendar.JUNE, Calendar.JULY, Calendar.AUGUST),
        m("осень", "осенью", "осени", "autumn", "fall") to setOf(Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER),
    )
}
