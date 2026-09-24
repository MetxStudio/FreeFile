package com.freefile.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// ==================== 实体 ====================

/** 标签页会话（退出时保存，启动恢复）。 */
@Entity(tableName = "tab_sessions")
data class TabSession(
    @PrimaryKey val id: String,
    val pane: Int,            // 0=左, 1=右
    val tabIndex: Int,        // 窗格内的顺序
    val path: String,
    val sortBy: String,
    val sortAsc: Boolean,
    val viewMode: String
)

/** 书签（pane = -1 全局）。 */
@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val pane: Int = -1,
    val order: Int = 0
)

/** 目录访问历史。 */
@Entity(tableName = "history")
data class History(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val timestamp: Long
)

/** 回收站条目。 */
@Entity(tableName = "trash")
data class TrashItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val fileName: String,
    val fileSize: Long,
    val isDirectory: Boolean,
    val trashPath: String,    // 回收站内的实际路径
    val deletedAt: Long
)

/** 网络位置（Sprint 5：WebDAV/FTP/SMB/S3 连接配置）。 */
@Entity(tableName = "network_location")
data class NetworkLocation(
    @PrimaryKey val id: String,           // UUID
    val name: String,
    val type: String,                     // WEBDAV / FTP / SMB / S3
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val basePath: String,                 // WebDAV 前缀 / SMB 共享名 / S3 bucket
    val useSsl: Boolean = false,
    val extras: String = ""               // S3: region / endpoint / prefix（key=value 换行分隔）
) {
    companion object {
        /** 从 extras 解析 key=value 集。 */
        fun parseExtras(extras: String): Map<String, String> =
            extras.lineSequence()
                .map { it.trim() }
                .filter { it.contains('=') }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }

    fun extrasValue(key: String): String = parseExtras(extras)[key] ?: ""
}

/** 最近访问文件。 */
@Entity(tableName = "recent_files")
data class RecentFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val accessedAt: Long
)

/** 搜索索引缓存（后台全量扫描构建，秒级搜索）。 */
@Entity(tableName = "search_index")
data class SearchIndexEntry(
    @PrimaryKey val path: String,
    val name: String,
    val parent: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

// ==================== DAO ====================

@Dao
interface TabSessionDao {
    @Query("SELECT * FROM tab_sessions ORDER BY pane, tabIndex")
    fun observeAll(): Flow<List<TabSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<TabSession>)

    @Query("DELETE FROM tab_sessions")
    suspend fun clear()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY `order`")
    fun observeAll(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bookmarks: List<Bookmark>)

    @Query("DELETE FROM bookmarks WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) > 0 FROM bookmarks WHERE path = :path")
    suspend fun existsByPath(path: String): Boolean

    @Query("UPDATE bookmarks SET `order` = :order WHERE id = :id")
    suspend fun updateOrder(id: Long, order: Int)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<History>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: History)

    @Query("DELETE FROM history WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashItem): Long

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM trash WHERE originalPath = :path LIMIT 1")
    suspend fun findByOriginalPath(path: String): TrashItem?

    @Query("DELETE FROM trash")
    suspend fun clear()
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recent_files ORDER BY accessedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<RecentFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recent: RecentFile)

    @Query("DELETE FROM recent_files WHERE path = :path")
    suspend fun deleteByPath(path: String)
}

@Dao
interface SearchIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SearchIndexEntry>)

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun count(): Int

    /** 模糊匹配（LIKE），限制返回量避免卡顿。 */
    @Query("SELECT * FROM search_index WHERE name LIKE '%' || :name || '%' ORDER BY isDirectory DESC, name LIMIT 500")
    suspend fun searchFuzzy(name: String): List<SearchIndexEntry>

    /** 完全匹配。 */
    @Query("SELECT * FROM search_index WHERE name = :name ORDER BY isDirectory DESC LIMIT 500")
    suspend fun searchExact(name: String): List<SearchIndexEntry>

    /** 取全量（供正则等内存匹配，限制 5 万条）。 */
    @Query("SELECT * FROM search_index LIMIT 50000")
    suspend fun loadAll(): List<SearchIndexEntry>

    @Query("DELETE FROM search_index")
    suspend fun clear()
}

@Dao
interface NetworkLocationDao {
    @Query("SELECT * FROM network_location ORDER BY name")
    fun observeAll(): Flow<List<NetworkLocation>>

    @Query("SELECT * FROM network_location WHERE id = :id")
    suspend fun get(id: String): NetworkLocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(location: NetworkLocation)

    @Query("DELETE FROM network_location WHERE id = :id")
    suspend fun delete(id: String)
}

// ==================== Database ====================

@Database(
    entities = [
        TabSession::class, Bookmark::class, History::class,
        TrashItem::class, RecentFile::class, SearchIndexEntry::class,
        NetworkLocation::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FreeFileDatabase : RoomDatabase() {
    abstract fun tabSessionDao(): TabSessionDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun trashDao(): TrashDao
    abstract fun recentDao(): RecentDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun networkLocationDao(): NetworkLocationDao

    companion object {
        @Volatile private var instance: FreeFileDatabase? = null

        fun get(context: Context): FreeFileDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    FreeFileDatabase::class.java,
                    "freefile.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
