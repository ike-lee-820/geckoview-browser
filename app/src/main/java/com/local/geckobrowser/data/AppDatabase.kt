package com.local.geckobrowser.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookmarkEntity::class, HistoryEntity::class, DownloadEntity::class,
        TabGroupEntity::class, TabSnapshotEntity::class, SiteSettingEntity::class,
        LoginEntity::class, RecentClosedTabEntity::class, SearchEngineEntity::class,
        TranslationModelEntity::class, CrashEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun tabGroupDao(): TabGroupDao
    abstract fun siteSettingDao(): SiteSettingDao
    abstract fun loginDao(): LoginDao
    abstract fun recentClosedDao(): RecentClosedDao
    abstract fun searchEngineDao(): SearchEngineDao
    abstract fun translationModelDao(): TranslationModelDao
    abstract fun crashDao(): CrashDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun build(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "local_browser.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
