package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.search.people.Person
import ai.recommend.spacegallery.ui.people.PersonAvatar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** «Кто на фото»: чипы узнанных людей над кнопками просмотрщика; тап — все фото с человеком. */
@Composable
fun PeopleOnPhotoRow(people: List<Person>, onOpenPerson: (Long) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(people, key = { it.id }) { person ->
            val name = person.name ?: stringResource(R.string.person_unnamed)
            val description = stringResource(R.string.person_open_photos, name)
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { onOpenPerson(person.id) }
                    .semantics { contentDescription = description }
                    .padding(start = 2.dp, top = 2.dp, bottom = 2.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonAvatar(person, size = 28.dp)
                Text(
                    name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).widthIn(max = 140.dp),
                )
            }
        }
    }
}
