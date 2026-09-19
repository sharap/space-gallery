package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.people.FaceBox
import ai.recommend.spacegallery.search.people.Person
import ai.recommend.spacegallery.search.people.PersonOnPhoto
import ai.recommend.spacegallery.search.people.PhotoTags
import ai.recommend.spacegallery.ui.people.PersonChoice
import ai.recommend.spacegallery.search.people.PeopleRepository
import ai.recommend.spacegallery.search.smart.SmartAlbumRepository
import ai.recommend.spacegallery.ui.navigation.ViewerQueue
import ai.recommend.spacegallery.ui.navigation.ViewerRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModel(
    private val repository: MediaRepository,
    private val smartAlbums: SmartAlbumRepository,
    private val people: PeopleRepository,
    handle: SavedStateHandle,
) : ViewModel() {

    private val route = handle.toRoute<ViewerRoute>()
    val initialMediaId: Long = route.mediaId

    /**
     * Очередь пролистывания — та, из которой открыли просмотрщик (лента, альбом, результаты
     * поиска, похожие...). Если открытого элемента в ней изначально нет (например, деликатное
     * фото при включённом фильтре) — показываем только его. Решение принимается один раз:
     * если элемент потом скрыть или удалить, очередь не схлопывается.
     */
    val items: StateFlow<List<MediaItem>?> = flow {
        val queue = queueFlow()
        if (queue.first().any { it.id == route.mediaId }) {
            emitAll(queue)
        } else {
            emitAll(repository.observeByIds(listOf(route.mediaId)))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Открыто из экрана человека — рамки его лиц на каждом фото (подсветка вместе с кнопками). */
    val faceBoxes: StateFlow<Map<Long, List<FaceBox>>> =
        (if (route.queue == ViewerQueue.PERSON) people.observeFaceBoxes(route.albumId) else flowOf(emptyMap()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val currentMediaId = MutableStateFlow<Long?>(null)

    /** Узнанные люди на текущем фото — чипы над кнопками и рамки лиц. */
    val peopleOnCurrent: StateFlow<List<PersonOnPhoto>> = currentMediaId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else people.observePeopleOnMedia(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Лица и ручные отметки на текущем фото — для шторки «Люди на фото». */
    val tagsOnCurrent: StateFlow<PhotoTags> = currentMediaId
        .flatMapLatest { id -> if (id == null) flowOf(EMPTY_TAGS) else people.observePhotoTags(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_TAGS)

    val allPeople: StateFlow<List<Person>> = people.observePeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun resolve(choice: PersonChoice): Long = when (choice) {
        is PersonChoice.Existing -> choice.person.id
        is PersonChoice.New -> people.createPerson(choice.name)
    }

    fun assignFace(faceId: Long, choice: PersonChoice) = viewModelScope.launch {
        people.assignFace(faceId, resolve(choice))
    }

    fun markNotAFace(faceId: Long) = viewModelScope.launch { people.markFaceNotFace(faceId) }

    fun tagPerson(mediaId: Long, choice: PersonChoice) = viewModelScope.launch {
        people.tagPerson(mediaId, resolve(choice))
    }

    fun untagPerson(mediaId: Long, personId: Long) = viewModelScope.launch { people.untagPerson(mediaId, personId) }

    /** Какое фото сейчас на экране (страница, на которой остановился пейджер). */
    fun onCurrentMedia(mediaId: Long?) {
        currentMediaId.value = mediaId
    }

    /** Открыто со страницы человека — подсвечиваем только его лицо. */
    val highlightsOnlyPerson: Boolean get() = route.queue == ViewerQueue.PERSON

    private fun queueFlow(): Flow<List<MediaItem>> = when (route.queue) {
        ViewerQueue.TIMELINE -> repository.observeTimeline()
        ViewerQueue.ALBUM -> repository.observeTimeline(route.albumId)
        ViewerQueue.FAVORITES -> repository.observeFavorites()
        ViewerQueue.HIDDEN -> repository.observeHidden()
        ViewerQueue.LIST -> repository.observeByIds(route.ids)
        ViewerQueue.SMART_ALBUM -> smartAlbums.observeItems(route.albumId)
        ViewerQueue.PERSON -> people.observeMedia(route.albumId)
    }

    private companion object {
        val EMPTY_TAGS = PhotoTags(emptyList(), emptyList())
    }

    fun toggleFavorite(item: MediaItem) = viewModelScope.launch {
        repository.setFavorite(item.id, !item.isFavorite)
    }

    fun toggleHidden(item: MediaItem) = viewModelScope.launch {
        repository.setHidden(listOf(item.id), !item.isHiddenByUser)
    }

    suspend fun trash(item: MediaItem): DeleteResult = repository.moveToTrash(listOf(item))

    fun onTrashConfirmed(item: MediaItem) = viewModelScope.launch {
        repository.onTrashed(listOf(item))
    }
}
