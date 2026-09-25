package ai.recommend.spacegallery.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaEntity::class,
        MediaAnalysisEntity::class,
        SmartAlbumEntity::class,
        SmartAlbumMemberEntity::class,
        FaceEntity::class,
        PersonEntity::class,
        FaceRejectionEntity::class,
        PersonPairDismissalEntity::class,
        MediaPersonTagEntity::class,
        MediaTextEntity::class,
        MediaTextFts::class,
        TextLineEntity::class,
        MediaCodeEntity::class,
    ],
    version = 13,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: media.relativePath (для управления альбомами-папками).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: умные альбомы (кластеры DBSCAN).
        AutoMigration(from = 2, to = 3),
        // 3 -> 4: лица и люди + media_analysis.facesVersion.
        AutoMigration(from = 3, to = 4),
        // 4 -> 5: аватар человека из оригинала.
        AutoMigration(from = 4, to = 5),
        // 5 -> 6: ручные правки людей (подтверждённые лица, «это не он», отклонённые подсказки).
        AutoMigration(from = 5, to = 6),
        // 6 -> 7: артефакты («это не лицо») и отметка проверки CLIP.
        AutoMigration(from = 6, to = 7),
        // 7 -> 8: ручные отметки людей на фото без рамки лица.
        AutoMigration(from = 7, to = 8),
        // 8 -> 9: оценка качества кадра (резкость, яркость) для очистки.
        AutoMigration(from = 8, to = 9),
        // 9 -> 10: геометки фото и видео (поиск по местам).
        AutoMigration(from = 9, to = 10),
        // 10 -> 11: ключевые точки лица и версия модели векторов (смена SFace -> ArcFace r50).
        AutoMigration(from = 10, to = 11),
        // 11 -> 12: распознанный текст (с полнотекстовым поиском), строки и коды на снимках.
        AutoMigration(from = 11, to = 12),
        // 12 -> 13: своя версия у поиска кодов — иначе проход без моделей стирал текст.
        AutoMigration(from = 12, to = 13),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun smartAlbumDao(): SmartAlbumDao
    abstract fun faceDao(): FaceDao
    abstract fun textDao(): TextDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "space_gallery.db")
                // TODO: заменить на реальные миграции перед первым релизом.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
