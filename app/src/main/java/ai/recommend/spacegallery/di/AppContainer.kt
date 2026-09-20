package ai.recommend.spacegallery.di

import ai.recommend.spacegallery.data.db.AppDatabase
import ai.recommend.spacegallery.data.media.MediaDeleter
import ai.recommend.spacegallery.data.media.MediaMover
import ai.recommend.spacegallery.data.media.MediaStoreSource
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.ml.hash.PerceptualHasher
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.ml.image.FaceCropLoader
import ai.recommend.spacegallery.ml.image.ImageEmbedder
import ai.recommend.spacegallery.ml.image.SensitiveContentClassifier
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.OnnxRuntimeHolder
import ai.recommend.spacegallery.ml.text.ClipTokenizer
import ai.recommend.spacegallery.ml.text.TextEmbedder
import ai.recommend.spacegallery.ml.text.TextEncoderBackend
import ai.recommend.spacegallery.ml.text.WordPieceTokenizer
import ai.recommend.spacegallery.search.cleanup.CleanupFinder
import ai.recommend.spacegallery.search.cleanup.CleanupRepository
import ai.recommend.spacegallery.search.cleanup.QualityIndexer
import ai.recommend.spacegallery.search.places.LocationIndexer
import ai.recommend.spacegallery.ml.onnx.ModelCatalog
import ai.recommend.spacegallery.work.ModelDownloads
import ai.recommend.spacegallery.search.places.PlaceIndex
import ai.recommend.spacegallery.search.places.PlacesRepository
import ai.recommend.spacegallery.search.EmbeddingIndex
import ai.recommend.spacegallery.search.FilteredSearch
import ai.recommend.spacegallery.search.SemanticSearchEngine
import ai.recommend.spacegallery.search.SimilarMediaFinder
import ai.recommend.spacegallery.ml.face.FaceDetector
import ai.recommend.spacegallery.ml.face.FaceEmbedder
import ai.recommend.spacegallery.ml.face.FaceVerifier
import ai.recommend.spacegallery.search.people.AvatarRenderer
import ai.recommend.spacegallery.search.people.FaceIndexer
import ai.recommend.spacegallery.search.people.FaceReembedder
import ai.recommend.spacegallery.search.people.PeopleBuilder
import ai.recommend.spacegallery.search.people.PeopleRepository
import ai.recommend.spacegallery.search.smart.SmartAlbumBuilder
import ai.recommend.spacegallery.search.smart.SmartAlbumRepository
import ai.recommend.spacegallery.work.MediaAnalyzer
import ai.recommend.spacegallery.work.IndexingScheduler
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Ручной DI: единая точка сборки графа зависимостей.
 * При разрастании проекта можно заменить на Hilt/Koin без изменения остального кода.
 */
class AppContainer(context: Context) {

    val appContext: android.content.Context = context.applicationContext

    /** Скоуп процесса — для кешей, которые живут дольше экранов (индекс эмбеддингов). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    // --- data ---
    val mediaStoreSource: MediaStoreSource by lazy { MediaStoreSource(appContext.contentResolver) }
    val mediaDeleter: MediaDeleter by lazy { MediaDeleter(appContext.contentResolver) }
    val mediaMover: MediaMover by lazy { MediaMover(appContext.contentResolver) }
    val mediaRepository: MediaRepository by lazy {
        MediaRepository(database, mediaStoreSource, mediaDeleter, mediaMover, settings)
    }

    // --- ml ---
    val onnx: OnnxRuntimeHolder by lazy { OnnxRuntimeHolder() }
    val models: ModelProvider by lazy { ModelProvider(appContext, onnx) }
    val faceCropLoader: FaceCropLoader by lazy { FaceCropLoader(appContext.contentResolver) }
    val bitmapLoader: BitmapLoader by lazy { BitmapLoader(appContext.contentResolver) }
    val imageEmbedder: ImageEmbedder by lazy { ImageEmbedder(models) }
    val textEmbedder: TextEmbedder by lazy {
        TextEmbedder(
            models = models,
            english = TextEncoderBackend(ModelId.CLIP_TEXT, ClipTokenizer.provider(appContext)),
            multilingual = TextEncoderBackend(ModelId.CLIP_TEXT_MULTILINGUAL, WordPieceTokenizer.provider(appContext)),
        )
    }
    val sensitiveClassifier: SensitiveContentClassifier by lazy { SensitiveContentClassifier(models) }
    val perceptualHasher: PerceptualHasher by lazy { PerceptualHasher() }

    // --- search ---
    val embeddingIndex: EmbeddingIndex by lazy { EmbeddingIndex(database.analysisDao()) }
    val semanticSearch: SemanticSearchEngine by lazy {
        SemanticSearchEngine(textEmbedder, embeddingIndex, mediaRepository)
    }
    val filteredSearch: FilteredSearch by lazy { FilteredSearch(semanticSearch, mediaRepository, database.faceDao(), places) }
    val similarFinder: SimilarMediaFinder by lazy { SimilarMediaFinder(embeddingIndex, mediaRepository, settings) }
    val smartAlbumBuilder: SmartAlbumBuilder by lazy {
        SmartAlbumBuilder(
            database.smartAlbumDao(),
            settings,
            textEmbedder,
            models,
            topicCacheFile = File(appContext.noBackupFilesDir, "smart_album_topics.bin"),
        )
    }
    val faceDetector: FaceDetector by lazy { FaceDetector(models) }
    val faceEmbedder: FaceEmbedder by lazy { FaceEmbedder(models) }
    val faceVerifier: FaceVerifier by lazy { FaceVerifier(imageEmbedder, textEmbedder) }
    val faceIndexer: FaceIndexer by lazy {
        FaceIndexer(bitmapLoader, faceCropLoader, faceDetector, faceEmbedder, faceVerifier, database.faceDao(), settings)
    }
    val peopleBuilder: PeopleBuilder by lazy {
        PeopleBuilder(database, settings, AvatarRenderer(appContext.contentResolver))
    }
    val people: PeopleRepository by lazy { PeopleRepository(database, mediaRepository, peopleBuilder, appScope) }
    val smartAlbums: SmartAlbumRepository by lazy { SmartAlbumRepository(database.smartAlbumDao(), mediaRepository) }
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(appContext) }
    val modelDownloads: ModelDownloads by lazy { ModelDownloads(appContext, modelCatalog) }
    val locationIndexer: LocationIndexer by lazy { LocationIndexer(appContext, database) }
    val placeIndex: PlaceIndex by lazy { PlaceIndex(appContext.assets) }
    val places: PlacesRepository by lazy { PlacesRepository(database.analysisDao(), placeIndex, mediaRepository) }
    val faceReembedder: FaceReembedder by lazy { FaceReembedder(database, faceCropLoader, faceDetector, faceEmbedder, settings) }
    val qualityIndexer: QualityIndexer by lazy { QualityIndexer(database, bitmapLoader) }
    val cleanupFinder: CleanupFinder by lazy { CleanupFinder(database.analysisDao(), embeddingIndex, mediaRepository) }
    val cleanup: CleanupRepository by lazy { CleanupRepository(cleanupFinder, mediaRepository, appScope) }

    // --- background ---
    val mediaAnalyzer: MediaAnalyzer by lazy {
        MediaAnalyzer(
            bitmapLoader = bitmapLoader,
            hasher = perceptualHasher,
            embedder = imageEmbedder,
            classifier = sensitiveClassifier,
        )
    }
    val indexingScheduler: IndexingScheduler by lazy { IndexingScheduler(appContext) }
}
