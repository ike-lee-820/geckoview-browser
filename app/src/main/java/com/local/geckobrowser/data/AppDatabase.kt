package com.local.geckobrowser.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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

        /**
         * 【修复②】内置搜索引擎以前只是 SearchEngineManager 里的静态 list，
         * 从未写入 Room，导致 `getDefaultEngine()` 永远返回 null：
         * 搜索固定走 Google，用户也无法切换/添加/删除引擎。
         * 这里在数据库每次打开时用 INSERT OR IGNORE 幂等补齐内置行，
         * 用户自定义的默认标记与自定义行都不会被覆盖。
         */
        private val BUILT_IN_ENGINES = listOf(
            Triple("google", "Google", "https://www.google.com/search?q=%s"),
            Triple("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
            Triple("bing", "Bing", "https://www.bing.com/search?q=%s"),
            Triple("baidu", "百度", "https://www.baidu.com/s?wd=%s")
        )

        private val seedCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // seed 失败绝不能让整个数据库打不开；搜索引擎会由 SearchEngineManager
                // 回退到内置静态 list，功能仍可用。
                runCatching {
                    BUILT_IN_ENGINES.forEachIndexed { index, (id, name, template) ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO search_engines " +
                                "(id, name, template, isDefault, isCustom) VALUES (?, ?, ?, ?, 0)",
                            arrayOf<Any?>(id, name, template, if (index == 0) 1 else 0)
                        )
                    }
                    // 若默认标记被清空（例如用户删除了默认引擎），恢复一个内置默认。
                    db.execSQL(
                        "UPDATE search_engines SET isDefault = 1 WHERE id = 'google' " +
                            "AND NOT EXISTS (SELECT 1 FROM search_engines WHERE isDefault = 1)"
                    )
                }
            }
        }

        fun build(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "local_browser.db"
            )
                .fallbackToDestructiveMigration()
                .addCallback(seedCallback)
                .build().also { instance = it }
        }
    }
}
