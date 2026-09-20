package ai.recommend.spacegallery.ui.people

import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.search.people.Person
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Круглый аватар человека — миниатюра его типичного лица (JPEG из БД). Картинка запрашивается
 * при появлении на экране: списком их тянуть нельзя, при сотнях людей курсор переполняется.
 */
@Composable
fun PersonAvatar(person: Person, size: Dp, modifier: Modifier = Modifier) {
    val people = (LocalContext.current.applicationContext as SpaceGalleryApp).container.people
    val avatar by produceState<ByteArray?>(initialValue = null, person.id) { value = people.avatarOf(person.id) }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.padding(size / 5).fillMaxSize())
        }
    }
}
