package ai.recommend.spacegallery.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MediaEntity::class, MediaAnalysisEntity::class, SmartAlbumEntity::class, SmartAlbumMemberEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: media.relativePath (для управления альбомами-папками).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: умные альбомы (кластеры DBSCAN).
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun smartAlbumDao(): SmartAlbumDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "space_gallery.db")
                // TODO: заменить на реальные миграции перед первым релизом.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
