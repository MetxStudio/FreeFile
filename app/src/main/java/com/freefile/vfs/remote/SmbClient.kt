package com.freefile.vfs.remote

import com.freefile.data.db.NetworkLocation
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.VfsException
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 客户端（Sprint 5）：基于 smbj。
 * loc.basePath = 共享名；子路径为共享内绝对路径（"/" 开头）。
 */
class SmbClient(private val loc: NetworkLocation) : RemoteClient {

    private val domain = loc.extrasValue("domain").ifEmpty { "" }

    private suspend fun <T> withShare(block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        try {
            val connection = try {
                client.connect(loc.host, loc.port)
            } catch (e: Exception) {
                throw VfsException("SMB 连接失败: ${e.message}", e)
            }
            try {
                val auth = AuthenticationContext.anonymous()
                val session = try {
                    connection.authenticate(
                        if (loc.username.isEmpty()) auth
                        else AuthenticationContext(loc.username, loc.password.toCharArray(), domain)
                    )
                } catch (e: Exception) {
                    throw VfsException("SMB 认证失败: ${e.message}", e)
                }
                val share = try {
                    session.connectShare(loc.basePath) as DiskShare
                } catch (e: Exception) {
                    throw VfsException("SMB 打开共享失败(${loc.basePath}): ${e.message}", e)
                }
                try { block(share) } finally {
                    try { share.close() } catch (_: Exception) {}
                }
            } finally {
                try { connection.close() } catch (_: Exception) {}
            }
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** 共享内路径 → SMB 相对路径（去掉开头 "/"）。 */
    private fun rel(path: String): String = path.trimStart('/')

    private fun entryName(info: FileIdBothDirectoryInformation): String =
        info.fileName.trimEnd('\u0000')

    private fun joinSub(dir: String, name: String): String =
        (if (dir == "/") "" else dir.trimEnd('/')) + "/" + name

    override suspend fun list(dir: String): List<VfsEntry> = withShare { share ->
        val infos = try {
            share.list(rel(dir))
        } catch (e: Exception) {
            throw VfsException("SMB 列目录失败: ${e.message}", e)
        }
        infos.filter { info ->
            val n = entryName(info)
            n != "." && n != ".."
        }.map { info ->
            val n = entryName(info)
            VfsEntry(
                path = RemoteEngine.locationPath(loc.id, joinSub(dir, n)),
                name = n,
                isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                size = info.endOfFile,
                lastModified = info.lastWriteTime.toEpochMillis(),
                isHidden = n.startsWith(".") ||
                    (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L)
            )
        }
    }

    override suspend fun stat(path: String): VfsEntry? = withShare { share ->
        try {
            val info = share.getFileInformation(rel(path))
            val isDir = info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            VfsEntry(
                path = RemoteEngine.locationPath(loc.id, path),
                name = path.trimEnd('/').substringAfterLast('/'),
                isDirectory = isDir,
                size = info.standardInformation.endOfFile,
                lastModified = info.basicInformation.lastWriteTime.toEpochMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun mkdir(path: String) = withShare { share ->
        try {
            share.mkdir(rel(path))
        } catch (e: Exception) {
            throw VfsException("SMB 建目录失败: ${e.message}", e)
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withShare { share ->
        val rp = rel(path)
        try {
            // 先按文件删，失败再按目录删（recursive=true 时 rmdir 递归删除整个目录树）
            try {
                share.rm(rp)
            } catch (_: Exception) {
                share.rmdir(rp, recursive)
            }
        } catch (e: Exception) {
            throw VfsException("SMB 删除失败: ${e.message}", e)
        }
    }

    override suspend fun rename(oldPath: String, newPath: String) = withShare { share ->
        try {
            // smbj 0.13 无 DiskShare.rename，通过 FileRenameInformation 实现
            share.setFileInformation(
                rel(oldPath),
                com.hierynomus.msfscc.fileinformation.FileRenameInformation(false, 0, rel(newPath))
            )
        } catch (e: Exception) {
            throw VfsException("SMB 重命名失败: ${e.message}", e)
        }
    }

    override fun read(path: String): InputStream {
        val config = SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).build()
        val client = SMBClient(config)
        val connection = client.connect(loc.host, loc.port)
        val session = connection.authenticate(
            if (loc.username.isEmpty()) AuthenticationContext.anonymous()
            else AuthenticationContext(loc.username, loc.password.toCharArray(), domain)
        )
        val share = session.connectShare(loc.basePath) as DiskShare
        val file = share.openFile(
            rel(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        return object : InputStream() {
            val stream = file.inputStream
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                try { stream.close() } catch (_: Exception) {}
                try { file.close() } catch (_: Exception) {}
                try { share.close() } catch (_: Exception) {}
                try { connection.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    override fun write(path: String): OutputStream {
        val config = SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).build()
        val client = SMBClient(config)
        val connection = client.connect(loc.host, loc.port)
        val session = connection.authenticate(
            if (loc.username.isEmpty()) AuthenticationContext.anonymous()
            else AuthenticationContext(loc.username, loc.password.toCharArray(), domain)
        )
        val share = session.connectShare(loc.basePath) as DiskShare
        val file = share.openFile(
            rel(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        return object : OutputStream() {
            val stream = file.outputStream
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun flush() = stream.flush()
            override fun close() {
                try { stream.close() } catch (_: Exception) {}
                try { file.close() } catch (_: Exception) {}
                try { share.close() } catch (_: Exception) {}
                try { connection.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    override fun close() {}
}
