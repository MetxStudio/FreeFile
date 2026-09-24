package com.freefile.vfs.remote

import com.freefile.FreeFileApp
import com.freefile.data.db.FreeFileDatabase
import com.freefile.data.db.NetworkLocation
import com.freefile.vfs.FileEngine
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.VfsException
import java.io.InputStream
import java.io.OutputStream

/**
 * 网络位置引擎（Sprint 5）。
 * 路径语法：remote://<locationId>/<位置内子路径>（如 remote://a1b2/Docs/file.txt）。
 * 按 location.type 分发到 WebDav/Ftp/Smb/S3 客户端。
 */
object RemoteEngine : FileEngine {

    override val id = "remote"

    const val SCHEME = "remote://"

    private val dao get() = FreeFileDatabase.get(FreeFileApp.instance).networkLocationDao()

    /** 构造 remote 路径。 */
    fun locationPath(locationId: String, subPath: String): String {
        val sub = if (subPath.isEmpty() || subPath == "/") "" else "/${subPath.trim('/')}"
        return "$SCHEME$locationId$sub"
    }

    fun isRemotePath(path: String): Boolean = path.startsWith(SCHEME)

    /** 提取 locationId。 */
    fun locationIdOf(path: String): String =
        path.removePrefix(SCHEME).substringBefore('/')

    /** 提取位置内子路径（"/" 开头）。 */
    fun subPathOf(path: String): String {
        val rest = path.removePrefix(SCHEME)
        val sub = rest.substringAfter('/', "")
        return if (sub.isEmpty()) "/" else "/$sub".replace("//", "/")
    }

    private suspend fun locationOf(path: String): NetworkLocation {
        val loc = dao.get(locationIdOf(path))
        if (loc == null) throw VfsException("网络位置不存在（可能已被删除）")
        return loc
    }

    private suspend fun clientFor(path: String): RemoteClient = when (val t = locationOf(path).type) {
        RemoteTypes.WEBDAV -> WebDavClient(locationOf(path))
        RemoteTypes.FTP -> FtpClient(locationOf(path))
        RemoteTypes.SMB -> SmbClient(locationOf(path))
        RemoteTypes.S3 -> S3Client(locationOf(path))
        else -> throw VfsException("不支持的网络类型: $t")
    }

    // ---------- FileEngine 实现 ----------

    override fun isAvailable(): Boolean = true

    override fun canAccess(path: String): Boolean = isRemotePath(path)

    override suspend fun list(path: String): List<VfsEntry> =
        clientFor(path).use { it.list(subPathOf(path)) }

    override suspend fun stat(path: String): VfsEntry? =
        clientFor(path).use { it.stat(subPathOf(path)) }

    override suspend fun exists(path: String): Boolean =
        try { stat(path) != null } catch (_: Exception) { false }

    override suspend fun mkdir(path: String): Boolean {
        clientFor(path).use { it.mkdir(subPathOf(path)) }
        return true
    }

    override suspend fun mkdirs(path: String): Boolean {
        // 逐级创建
        clientFor(path).use { client ->
            val sub = subPathOf(path)
            var cur = ""
            for (seg in sub.trim('/').split('/').filter { it.isNotEmpty() }) {
                cur = "$cur/$seg"
                try { client.mkdir(cur) } catch (_: Exception) { /* 已存在则忽略 */ }
            }
        }
        return true
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean {
        require(isRemotePath(newPath)) { "rename 目标必须是 remote:// 路径" }
        clientFor(oldPath).use { it.rename(subPathOf(oldPath), subPathOf(newPath)) }
        return true
    }

    override suspend fun delete(path: String, recursive: Boolean): Boolean {
        clientFor(path).use { it.delete(subPathOf(path), recursive) }
        return true
    }

    override fun openInputStream(path: String): InputStream {
        // 同步接口：内部仍走阻塞 IO（调用方已在 IO 上下文）
        return blockingClient(path).use { it.read(subPathOf(path)) }
    }

    override fun openOutputStream(path: String): OutputStream =
        blockingClient(path).use { it.write(subPathOf(path)) }

    private fun blockingClient(path: String): RemoteClient {
        val loc = kotlinx.coroutines.runBlocking { dao.get(locationIdOf(path)) }
            ?: throw VfsException("网络位置不存在")
        return when (loc.type) {
            RemoteTypes.WEBDAV -> WebDavClient(loc)
            RemoteTypes.FTP -> FtpClient(loc)
            RemoteTypes.SMB -> SmbClient(loc)
            RemoteTypes.S3 -> S3Client(loc)
            else -> throw VfsException("不支持的网络类型: ${loc.type}")
        }
    }

    /** 网络目录大小不递归计算（避免流量消耗），返回 0。 */
    override suspend fun sizeOf(path: String): Long = 0

    // ---------- 工具 ----------

    private inline fun <T> RemoteClient.use(block: (RemoteClient) -> T): T =
        try { block(this) } finally { close() }
}
