package ai.recommend.spacegallery.ui.search

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.search.ParsedQuery
import ai.recommend.spacegallery.search.RecognizedToken
import ai.recommend.spacegallery.search.people.Person
import ai.recommend.spacegallery.search.places.Place
import androidx.compose.material3.MaterialTheme
import ai.recommend.spacegallery.ui.people.PersonAvatar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Фильтры поиска: распознанное в запросе (со значком AI, можно отключить) и чипы людей, даты,
 * типа и избранного.
 */
@Composable
fun SearchFilterBar(
    parsed: ParsedQuery,
    filters: SearchFilters,
    people: List<Person>,
    years: List<Int>,
    places: List<Place>,
    onPlaces: (cityIds: Set<Long>, label: String?) -> Unit,
    onIgnoreWord: (String) -> Unit,
    onPeople: (Set<Long>) -> Unit,
    onDate: (DatePreset?) -> Unit,
    onType: (MediaType?) -> Unit,
    onFavorites: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    var pickingPeople by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingPlace by remember { mutableStateOf(false) }
    val selectedPeople = people.filter { it.id in filters.personIds }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        items(parsed.tokens, key = { "token-${it.word}" }) { token ->
            InputChip(
                selected = true,
                onClick = { onIgnoreWord(token.word) },
                label = { Text(tokenLabel(token)) },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_filter_ignore), modifier = Modifier.size(18.dp))
                },
            )
        }
        item(key = "people") {
            FilterChip(
                selected = selectedPeople.isNotEmpty(),
                onClick = { pickingPeople = true },
                label = {
                    Text(
                        when (selectedPeople.size) {
                            0 -> stringResource(R.string.search_filter_people)
                            1 -> selectedPeople.first().name ?: stringResource(R.string.person_unnamed)
                            else -> stringResource(R.string.search_filter_people_n, selectedPeople.size)
                        }
                    )
                },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            )
        }
        item(key = "date") {
            FilterChip(
                selected = filters.date != null,
                onClick = { pickingDate = true },
                label = { Text(filters.date?.let { dateLabel(it) } ?: stringResource(R.string.search_filter_date)) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            )
        }
        if (places.isNotEmpty()) {
            item(key = "place") {
                FilterChip(
                    selected = filters.placeCityIds.isNotEmpty(),
                    onClick = { pickingPlace = true },
                    label = { Text(filters.placeLabel ?: stringResource(R.string.search_filter_place)) },
                    trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
                )
            }
        }
        item(key = "photos") {
            FilterChip(
                selected = filters.type == MediaType.IMAGE,
                onClick = { onType(if (filters.type == MediaType.IMAGE) null else MediaType.IMAGE) },
                label = { Text(stringResource(R.string.search_filter_photos)) },
            )
        }
        item(key = "videos") {
            FilterChip(
                selected = filters.type == MediaType.VIDEO,
                onClick = { onType(if (filters.type == MediaType.VIDEO) null else MediaType.VIDEO) },
                label = { Text(stringResource(R.string.search_filter_videos)) },
            )
        }
        item(key = "favorites") {
            FilterChip(
                selected = filters.favoritesOnly,
                onClick = { onFavorites(!filters.favoritesOnly) },
                label = { Text(stringResource(R.string.favorites)) },
            )
        }
        if (!filters.isEmpty) {
            item(key = "clear") {
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_filter_clear)) }
            }
        }
    }

    if (pickingPeople) {
        PeopleFilterSheet(people, filters.personIds, onApply = {
            pickingPeople = false
            onPeople(it)
        })
    }
    if (pickingPlace) {
        PlaceFilterSheet(places, onDismiss = { pickingPlace = false }, onPick = { ids, label ->
            pickingPlace = false
            onPlaces(ids, label)
        })
    }
    if (pickingDate) {
        DateFilterSheet(years, onDismiss = { pickingDate = false }, onPick = {
            pickingDate = false
            onDate(it)
        })
    }
}

@Composable
private fun tokenLabel(token: RecognizedToken): String = when (token) {
    is RecognizedToken.PersonToken -> token.name
    is RecognizedToken.YearToken -> token.year.toString()
    is RecognizedToken.MonthsToken -> token.word.lowercase()
    is RecognizedToken.PlaceToken -> token.name
}

/** Места: страны (все их города) и города — по числу снимков. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceFilterSheet(places: List<Place>, onDismiss: () -> Unit, onPick: (Set<Long>, String?) -> Unit) {
    val countries = remember(places) {
        places.groupBy { it.countryName }.toList().sortedByDescending { (_, list) -> list.sumOf { it.count } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.search_filter_place), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn {
            item { DateOption(stringResource(R.string.search_place_any)) { onPick(emptySet(), null) } }
            countries.forEach { (country, cities) ->
                item(key = "country-$country") {
                    ListItem(
                        headlineContent = { Text(country, style = MaterialTheme.typography.titleMedium) },
                        trailingContent = { Text(cities.sumOf { it.count }.toString()) },
                        modifier = Modifier.clickable { onPick(cities.mapTo(HashSet()) { it.city.id }, country) },
                    )
                }
                items(cities, key = { it.city.id }) { place ->
                    ListItem(
                        headlineContent = { Text(place.city.name) },
                        trailingContent = { Text(place.count.toString()) },
                        modifier = Modifier.padding(start = 16.dp).clickable { onPick(setOf(place.city.id), place.city.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun dateLabel(preset: DatePreset): String = when (preset) {
    DatePreset.ThisMonth -> stringResource(R.string.search_date_this_month)
    DatePreset.ThisYear -> stringResource(R.string.search_date_this_year)
    is DatePreset.Year -> preset.year.toString()
    is DatePreset.Range -> {
        val f = DateFormat.getDateInstance(DateFormat.SHORT)
        "${f.format(Date(preset.from))} – ${f.format(Date(preset.to - 1))}"
    }
}

/** Выбор людей: на фото должны быть все отмеченные. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleFilterSheet(people: List<Person>, initial: Set<Long>, onApply: (Set<Long>) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    // Закрытие шторки применяет выбор. Названные люди — в начале списка (так сортирует observePeople).
    ModalBottomSheet(onDismissRequest = { onApply(selected) }) {
        Text(stringResource(R.string.search_filter_people_title), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn {
            items(people, key = { it.id }) { person ->
                val checked = person.id in selected
                ListItem(
                    leadingContent = { PersonAvatar(person, size = 40.dp) },
                    headlineContent = { Text(person.name ?: stringResource(R.string.person_unnamed)) },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier = Modifier.clickable { selected = if (checked) selected - person.id else selected + person.id },
                )
            }
        }
    }
}

/** Период: быстрые варианты, годы с фото и свой период в календаре. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterSheet(years: List<Int>, onDismiss: () -> Unit, onPick: (DatePreset?) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis ?: return@TextButton
                        val end = state.selectedEndDateMillis ?: start
                        // Календарь отдаёт полночь UTC — переводим в полночь местного времени.
                        val offset = TimeZone.getDefault().getOffset(start)
                        onPick(DatePreset.Range(start - offset, end - offset + DAY_MS))
                    },
                ) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DateRangePicker(state, modifier = Modifier.weight(1f))
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.search_filter_date), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn {
            item { DateOption(stringResource(R.string.search_date_any)) { onPick(null) } }
            item { DateOption(stringResource(R.string.search_date_this_month)) { onPick(DatePreset.ThisMonth) } }
            item { DateOption(stringResource(R.string.search_date_this_year)) { onPick(DatePreset.ThisYear) } }
            items(years, key = { it }) { year -> DateOption(year.toString()) { onPick(DatePreset.Year(year)) } }
            item { DateOption(stringResource(R.string.search_date_custom)) { custom = true } }
        }
    }
}

@Composable
private fun DateOption(text: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text) }, modifier = Modifier.clickable(onClick = onClick))
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
