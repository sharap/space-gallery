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
    ],
    version = 5,
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
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun smartAlbumDao(): SmartAlbumDao
    abstract fun faceDao(): FaceDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "space_gallery.db")
                // TODO: заменить на реальные миграции перед первым релизом.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
