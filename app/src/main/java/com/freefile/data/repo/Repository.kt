package com.freefile.data.repo

import android.content.Context
import com.freefile.data.db.Bookmark
import com.freefile.data.db.FreeFileDatabase
import com.freefile.data.db.History
import com.freefile.data.db.NetworkLocation
import com.freefile.data.db.RecentFile
import com.freefile.data.db.TrashItem
import com.freefile.operations.FileOperations
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.joinPath
import com.freefile.vfs.pathName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 数据仓库：书签/历史/最近/回收站 + 标签会话的统一访问层。
 * 回收站实现：把条目移动到应用外部私有目录 Trash/ 下，Room 记录原始路径。
 */
class Repository(context: Context) {

    private val db = FreeFileDatabase.get(context)
    private val trashDir = java.io.File(
        context.getExternalFilesDir(null) ?: context.filesDir, "Trash"
    ).apply { mkdirs() }

    // ---------- 网络位置（Sprint 5） ----------

    val networkLocations: Flow<List<NetworkLocation>> = db.networkLocationDao().observeAll()

    suspend fun addNetworkLocation(location: NetworkLocation) {
        db.networkLocationDao().upsert(location)
    }

    suspend fun removeNetworkLocation(id: String) = db.networkLocationDao().delete(id)

    // ---------- 书签 ----------

    val bookmarks: Flow<List<Bookmark>> = db.bookmarkDao().observeAll()

    suspend fun addBookmark(name: String, path: String) {
        db.bookmarkDao().insert(Bookmark(name = name, path = path, order = System.currentTimeMillis().toInt()))
    }

    suspend fun removeBookmark(path: String) = db.bookmarkDao().deleteByPath(path)

    suspend fun hasBookmark(path: String): Boolean = db.bookmarkDao().existsByPath(path)

    // ---------- 历史 / 最近 ----------

    val history: Flow<List<History>> = db.historyDao().observeRecent()

    suspend fun recordHistory(path: String) {
        if (path.isNotBlank()) {
            db.historyDao().deleteByPath(path)
            db.historyDao().insert(History(path = path, timestamp = System.currentTimeMillis()))
        }
    }

    val recents: Flow<List<RecentFile>> = db.recentDao().observeRecent()

    /**
     * 记录最近文件（Item 3：去重）。
     *
     * 先删除同路径旧记录再插入新记录，保证同一文件在历史列表中只出现一项，
     * 且始终按最近访问时间排序。
     */
    suspend fun recordRecent(path: String) {
        if (path.isNotBlank()) {
            db.recentDao().deleteByPath(path)
            db.recentDao().insert(RecentFile(path = path, accessedAt = System.currentTimeMillis()))
        }
    }

    // ---------- 回收站 ----------

    val trash: Flow<List<TrashItem>> = db.trashDao().observeAll()

    /** 移入回收站：移动到 Trash 目录并记录。返回成功与否。 */
    suspend fun moveToTrash(entries: List<VfsEntry>): Boolean = withContext(Dispatchers.IO) {
        var allOk = true
        for (entry in entries) {
            try {
                val engine = EngineSelector.select(entry.path)
                val dest = joinPath(trashDir.absolutePath, uniqueTrashName(entry.name))
                val ok = try {
                    engine.rename(entry.path, dest)
                } catch (e: Exception) {
                    // 跨设备 rename 失败 → 复制后删除
                    try {
                        if (entry.isDirectory) {
                            engine.list(entry.path) // 触发确认可读
                            copyDirDeep(entry.path, dest)
                            engine.delete(entry.path, recursive = true)
                        } else {
                            FileOperations.streamCopy(
                                engine.openInputStream(entry.path),
                                EngineSelector.select(dest).openOutputStream(dest)
                            )
                            engine.delete(entry.path, recursive = false)
                        }
                        true
                    } catch (e2: Exception) {
                        false
                    }
                }
                if (ok) {
                    db.trashDao().insert(
                        TrashItem(
                            originalPath = entry.path,
                            fileName = entry.name,
                            fileSize = entry.size,
                            isDirectory = entry.isDirectory,
                            trashPath = dest,
                            deletedAt = System.currentTimeMillis()
                        )
                    )
                } else allOk = false
            } catch (e: Exception) {
                allOk = false
            }
        }
        allOk
    }

    /** 从回收站恢复到原位置。 */
    suspend fun restore(item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val engine = EngineSelector.select(item.trashPath)
            val parent = item.originalPath.substringBeforeLast('/')
            val dest = joinPath(parent, pathName(item.originalPath))
            // 目标父目录不存在则创建
            if (!engine.exists(parent)) engine.mkdirs(parent)
            val ok = try {
                engine.rename(item.trashPath, dest)
            } catch (e: Exception) {
                false
            }
            if (ok) db.trashDao().deleteById(item.id)
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** 永久删除回收站条目。 */
    suspend fun purge(item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val engine = EngineSelector.select(item.trashPath)
            engine.delete(item.trashPath, recursive = true)
            db.trashDao().deleteById(item.id)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun clearTrash(): Boolean = withContext(Dispatchers.IO) {
        try {
            EngineSelector.select(trashDir.absolutePath).delete(trashDir.absolutePath, recursive = true)
            trashDir.mkdirs()
            db.trashDao().clear()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun uniqueTrashName(name: String): String {
        var candidate = name
        var i = 1
        while (java.io.File(trashDir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            candidate = "$base ($i)$ext"
            i++
        }
        return candidate
    }

    private suspend fun copyDirDeep(src: String, dst: String) {
        val engine = EngineSelector.select(src)
        EngineSelector.select(dst).mkdirs(dst)
        for (child in engine.list(src)) {
            val cDst = joinPath(dst, child.name)
            if (child.isDirectory) copyDirDeep(child.path, cDst)
            else FileOperations.streamCopy(
                engine.openInputStream(child.path),
                EngineSelector.select(cDst).openOutputStream(cDst)
            )
        }
    }
}
