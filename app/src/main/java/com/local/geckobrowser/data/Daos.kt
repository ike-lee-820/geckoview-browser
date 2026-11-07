package com.local.geckobrowser.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY position ASC, createdAt DESC")
    fun all(): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<BookmarkEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: BookmarkEntity)
    @Update suspend fun update(item: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM bookmarks") suspend fun exportAll(): List<BookmarkEntity>
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    fun all(): Flow<List<HistoryEntity>>
    @Query("SELECT url FROM history") suspend fun allUrls(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM history WHERE url LIKE '%' || :host || '%' AND visitedAt BETWEEN :a AND :b)")
    suspend fun hostVisitedSince(host: String, a: Long, b: Long): Boolean
    @Query("SELECT * FROM history WHERE title LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%' ORDER BY visitedAt DESC")
    fun search(q: String): Flow<List<HistoryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: HistoryEntity)
    @Query("DELETE FROM history WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM history WHERE visitedAt BETWEEN :a AND :b") suspend fun clearRange(a: Long, b: Long)
    @Query("DELETE FROM history") suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") fun all(): Flow<List<DownloadEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: DownloadEntity)
    @Update suspend fun update(item: DownloadEntity)
    @Query("UPDATE downloads SET bytesReceived=:received, speedBytesPerSec=:speed, etaSeconds=:eta, status=:status WHERE id=:id")
    suspend fun updateProgress(id: String, received: Long, speed: Long, eta: Long, status: String)
    @Query("UPDATE downloads SET status=:status, localPath=:path WHERE id=:id")
    suspend fun finish(id: String, status: String, path: String?)
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun byId(id: String): DownloadEntity?
    @Query("DELETE FROM downloads WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM downloads") suspend fun clearAll()
}

@Dao
interface TabGroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertGroup(group: TabGroupEntity)
    @Query("SELECT * FROM tab_groups") suspend fun allGroups(): List<TabGroupEntity>
    @Query("DELETE FROM tab_groups WHERE id = :id") suspend fun deleteGroup(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTabSnapshot(tab: TabSnapshotEntity)
    @Query("SELECT * FROM tab_snapshots ORDER BY lastUsed DESC") suspend fun tabSnapshots(): List<TabSnapshotEntity>
    @Query("DELETE FROM tab_snapshots WHERE id = :id") suspend fun deleteTabSnapshot(id: String)
    @Query("UPDATE tab_snapshots SET isActive = 0") suspend fun clearActive()
    @Query("UPDATE tab_snapshots SET isActive = 1 WHERE id = :id") suspend fun markActive(id: String)
}

@Dao
interface SiteSettingDao {
    @Query("SELECT * FROM site_settings") fun all(): Flow<List<SiteSettingEntity>>
    @Query("SELECT * FROM site_settings WHERE origin = :origin") suspend fun byOrigin(origin: String): SiteSettingEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: SiteSettingEntity)
    @Query("DELETE FROM site_settings WHERE origin = :origin") suspend fun delete(origin: String)
    @Query("DELETE FROM site_settings") suspend fun clearAll()
}

@Dao
interface LoginDao {
    @Query("SELECT * FROM logins") fun all(): Flow<List<LoginEntity>>
    @Query("SELECT * FROM logins WHERE origin LIKE '%' || :domain || '%'") suspend fun byDomain(domain: String): List<LoginEntity>
    @Query("SELECT * FROM logins WHERE username LIKE '%' || :q || '%' OR origin LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<LoginEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: LoginEntity)
    @Update suspend fun update(item: LoginEntity)
    @Query("DELETE FROM logins WHERE guid = :guid") suspend fun delete(guid: String)
}

@Dao
interface RecentClosedDao {
    @Query("SELECT * FROM recent_closed ORDER BY closedAt DESC") fun all(): Flow<List<RecentClosedTabEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: RecentClosedTabEntity)
    /** 写入一条关闭记录并裁剪到最近 limit 条。 */
    @Query("DELETE FROM recent_closed WHERE id IN (SELECT id FROM recent_closed ORDER BY closedAt DESC LIMIT -1 OFFSET :limit)")
    suspend fun trim(limit: Int)
    @Query("DELETE FROM recent_closed") suspend fun clearAll()
}

@Dao
interface SearchEngineDao {
    @Query("SELECT * FROM search_engines") fun all(): Flow<List<SearchEngineEntity>>
    // 方法名不能叫 default：Java 关键字会让 Room/KSP 生成的实现类无法覆盖该 suspend 方法。
    @Query("SELECT * FROM search_engines WHERE isDefault = 1 LIMIT 1") suspend fun getDefaultEngine(): SearchEngineEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: SearchEngineEntity)
    @Query("UPDATE search_engines SET isDefault = 0") suspend fun clearDefault()
    @Query("UPDATE search_engines SET isDefault = 1 WHERE id = :id") suspend fun setDefault(id: String)
    @Query("DELETE FROM search_engines WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface TranslationModelDao {
    @Query("SELECT * FROM translation_models") fun all(): Flow<List<TranslationModelEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: TranslationModelEntity)
    @Query("DELETE FROM translation_models WHERE languagePair = :pair") suspend fun delete(pair: String)
}

@Dao
interface CrashDao {
    @Query("SELECT * FROM crash_logs ORDER BY timestamp DESC") fun all(): Flow<List<CrashEntity>>
    @Insert suspend fun insert(item: CrashEntity)
    @Query("DELETE FROM crash_logs") suspend fun clearAll()
}
