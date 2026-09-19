package ai.recommend.spacegallery.ui

import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.di.AppContainer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Фабрика ViewModel поверх ручного DI: `viewModel(factory = appViewModelFactory { c, h -> ... })`. */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: (container: AppContainer, handle: SavedStateHandle) -> VM,
) = viewModelFactory {
    initializer {
        val app = this[APPLICATION_KEY] as SpaceGalleryApp
        create(app.container, createSavedStateHandle())
    }
}
