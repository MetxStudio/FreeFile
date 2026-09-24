package com.freefile.vfs.remote

import com.freefile.data.db.NetworkLocation
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.InputStream
import java.io.OutputStream

/**
 * FTP 客户端（Sprint 5）：基于 Apache Commons Net。
 * 被动模式 + UTF-8 + 二进制传输。
 */
class FtpClient(private val loc: NetworkLocation) : RemoteClient {

    private fun connect(): FTPClient = FTPClient().apply {
        connectTimeout = 15000
        connect(loc.host, loc.port)
        enterLocalPassiveMode()
        setControlEncoding("UTF-8")
        if (!login(loc.username.ifEmpty { "anonymous" }, loc.password)) {
            throw VfsException("FTP 登录失败: ${replyString}")
        }
        try { sendCommand("OPTS UTF8 ON") } catch (_: Exception) {}
        setFileType(FTP.BINARY_FILE_TYPE)
    }

    private suspend fun <T> withFtp(block: suspend (FTPClient) -> T): T = withContext(Dispatchers.IO) {
        val ftp = connect()
        try { block(ftp) } finally {
            try { ftp.logout() } catch (_: Exception) {}
            try { ftp.disconnect() } catch (_: Exception) {}
        }
    }

    private fun remotePath(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return p
    }

    private fun FTPFile.toEntry(path: String, name: String) = VfsEntry(
        path = RemoteEngine.locationPath(loc.id, joinSub(path, name)),
        name = name,
        isDirectory = isDirectory,
        size = size,
        lastModified = timestamp?.timeInMillis ?: 0,
        isHidden = name.startsWith(".")
    )

    private fun joinSub(dir: String, name: String): String =
        (if (dir == "/") "" else dir.trimEnd('/')) + "/" + name

    override suspend fun list(dir: String): List<VfsEntry> = withFtp { ftp ->
        val files: Array<FTPFile> = ftp.listFiles(remotePath(dir))
            ?: throw VfsException("FTP 列目录失败: ${ftp.replyString}")
        files.filter { it.name != "." && it.name != ".." }
            .map { it.toEntry(dir, it.name) }
    }

    override suspend fun stat(path: String): VfsEntry? = withFtp { ftp ->
        val files = ftp.listFiles(remotePath(path)) ?: return@withFtp null
        val name = path.trimEnd('/').substringAfterLast('/')
        files.firstOrNull { it.name == name }?.toEntry(path.substringBeforeLast('/', "/"), name)
    }

    override suspend fun mkdir(path: String) = withFtp { ftp ->
        if (!ftp.makeDirectory(remotePath(path))) {
            throw VfsException("FTP 建目录失败: ${ftp.replyString}")
        }
    }

    override suspend fun delete(path: String, recursive: Boolean): Unit = withFtp { ftp ->
        val rp = remotePath(path)
        // 目录探测：尝试 removeDirectory，失败则 deleteFile
        if (recursive) {
            val children = ftp.listFiles(rp) ?: emptyArray()
            for (c in children) {
                if (c.name == "." || c.name == "..") continue
                delete(joinSub(path, c.name), true)
            }
        }
        val removedDir = try { ftp.removeDirectory(rp) } catch (_: Exception) { false }
        if (!removedDir && !ftp.deleteFile(rp)) {
            throw VfsException("FTP 删除失败: ${ftp.replyString}")
        }
    }

    override suspend fun rename(oldPath: String, newPath: String) = withFtp { ftp ->
        if (!ftp.rename(remotePath(oldPath), remotePath(newPath))) {
            throw VfsException("FTP 重命名失败: ${ftp.replyString}")
        }
    }

    override fun read(path: String): InputStream {
        val ftp = connect()
        val stream = ftp.retrieveFileStream(remotePath(path))
            ?: run {
                try { ftp.logout(); ftp.disconnect() } catch (_: Exception) {}
                throw VfsException("FTP 打开文件失败: ${ftp.replyString}")
            }
        // 包装：流关闭时补 completePendingCommand 并断开
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                try { stream.close() } catch (_: Exception) {}
                try { ftp.completePendingCommand() } catch (_: Exception) {}
                try { ftp.logout() } catch (_: Exception) {}
                try { ftp.disconnect() } catch (_: Exception) {}
            }
        }
    }

    override fun write(path: String): OutputStream {
        val ftp = connect()
        val stream = ftp.storeFileStream(remotePath(path))
            ?: run {
                try { ftp.logout(); ftp.disconnect() } catch (_: Exception) {}
                throw VfsException("FTP 写入失败: ${ftp.replyString}")
            }
        return object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun flush() = stream.flush()
            override fun close() {
                try { stream.close() } catch (_: Exception) {}
                try { ftp.completePendingCommand() } catch (_: Exception) {}
                try { ftp.logout() } catch (_: Exception) {}
                try { ftp.disconnect() } catch (_: Exception) {}
            }
        }
    }

    override fun close() {}
}
