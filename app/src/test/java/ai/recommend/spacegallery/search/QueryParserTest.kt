package ai.recommend.spacegallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QueryParserTest {
    private val people = mapOf(1L to "Маша", 2L to "Иван Петров", 3L to "Мария")

    @Test
    fun personInAnyCase() {
        val q = QueryParser.parse("с Машей на море", people)
        assertEquals(setOf(1L), q.personIds)
        assertEquals("на море", q.text)
        assertEquals(setOf(2L), QueryParser.parse("Иваном", people).personIds)
        assertEquals(setOf(3L), QueryParser.parse("Марии", people).personIds)
        assertEquals(setOf(2L), QueryParser.parse("петров", people).personIds)
    }

    @Test
    fun wordsThatOnlyLookLikeNames() {
        assertTrue(QueryParser.parse("машина", people).personIds.isEmpty())
        assertTrue(QueryParser.parse("иванушка", people).personIds.isEmpty())
    }

    @Test
    fun yearAndSeason() {
        val q = QueryParser.parse("море летом 2023", people)
        assertEquals(2023, q.year)
        assertEquals(setOf(Calendar.JUNE, Calendar.JULY, Calendar.AUGUST), q.months)
        assertEquals("море", q.text)
        assertEquals(setOf(Calendar.MAY), QueryParser.parse("в мае", people).months)
        assertEquals("", QueryParser.parse("в мае", people).text)
        assertNull(QueryParser.parse("1500 лет", people).year)
        assertEquals(2021, QueryParser.parse("2021г", people).year)
    }

    @Test
    fun ignoredWordsStayText() {
        val q = QueryParser.parse("Маша", people, ignored = setOf("маша"))
        assertTrue(q.personIds.isEmpty())
        assertEquals("Маша", q.text)
    }
}

class QueryParserPlacesTest {
    private val places = listOf(PlaceName("Москва", setOf(1L)), PlaceName("Санкт-Петербург", setOf(2L)), PlaceName("Турция", setOf(3L, 4L)), PlaceName("Сочи", setOf(5L)))

    @Test
    fun placesInAnyCase() {
        assertEquals(setOf(1L), QueryParser.parse("закат в Москве", emptyMap(), places = places).placeCityIds)
        assertEquals("закат", QueryParser.parse("закат в Москве", emptyMap(), places = places).text)
        assertEquals(setOf(2L), QueryParser.parse("Петербурге", emptyMap(), places = places).placeCityIds)
        assertEquals(setOf(3L, 4L), QueryParser.parse("море в Турции", emptyMap(), places = places).placeCityIds)
        assertEquals(setOf(5L), QueryParser.parse("Сочи", emptyMap(), places = places).placeCityIds)
    }
}
