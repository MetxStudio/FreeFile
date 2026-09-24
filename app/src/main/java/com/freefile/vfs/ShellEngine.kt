package com.freefile.vfs

import com.freefile.shell.ShellProvider
import com.freefile.shell.shQuote
import com.freefile.shell.waitForCompat
import com.freefile.shell.withSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Shell 引擎：Root(su) / Shizuku 共用实现。
 * 通过 shell 命令操作任意路径（/data、/Android/data、系统分区等）。
 *
 * @param provider SuProvider 或 ShizukuProvider
 */
class ShellEngine(private val provider: ShellProvider) : FileEngine {

    override val id: String = provider.id

    override fun isAvailable(): Boolean = provider.isReady()

    override suspend fun list(path: String): List<VfsEntry> = provider.withSession {
        // 首选 epoch 时间戳格式；旧版 toybox 不支持 --time-style 时回退普通格式
        val epochOut = try {
            exec("ls -Apl --time-style=+%s ${shQuote(path)}", noThrow = true)
        } catch (e: Exception) {
            exec("ls -Apl ${shQuote(path)}", noThrow = false)
        }
        parseLs(epochOut, path)
    }

    override suspend fun stat(path: String): VfsEntry? = provider.withSession {
        val out = exec(
            "stat -c '%F|%s|%Y' ${shQuote(path)} 2>/dev/null || echo __FF_STAT_NONE__",
            noThrow = true
        ).trim()
        if (out.contains("__FF_STAT_NONE__") || out.isEmpty()) return@withSession null
        val parts = out.trim().split('|')
        VfsEntry(
            path = path,
            name = pathName(path),
            isDirectory = parts.getOrNull(0)?.startsWith("directory") == true,
            size = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
            lastModified = parts.getOrNull(2)?.toLongOrNull()?.times(1000) ?: 0L,
            canWrite = true,
            canRead = true
        )
    }

    override suspend fun exists(path: String): Boolean = provider.withSession {
        exec("test -e ${shQuote(path)} && echo 1 || echo 0", noThrow = true).trim() == "1"
    }

    override suspend fun mkdir(path: String): Boolean {
        provider.withSession { exec("mkdir ${shQuote(path)}") }
        return true
    }

    override suspend fun mkdirs(path: String): Boolean {
        provider.withSession { exec("mkdir -p ${shQuote(path)}") }
        return true
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean {
        provider.withSession { exec("mv ${shQuote(oldPath)} ${shQuote(newPath)}") }
        return true
    }

    override suspend fun delete(path: String, recursive: Boolean): Boolean {
        val cmd = if (recursive) "rm -rf" else "rm -r"
        provider.withSession { exec("$cmd ${shQuote(path)}") }
        return true
    }

    /** 读取流：独立进程执行 cat，进程 stdout 即文件内容。 */
    override fun openInputStream(path: String): InputStream {
        val p = provider.spawnProcess()
        try {
            p.outputStream.use {
                it.write("cat ${shQuote(path)}\nexit\n".toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            p.destroy()
            throw VfsException("无法打开读取流: $path", e)
        }
        return object : InputStream() {
            private val src = p.inputStream
            override fun read(): Int = src.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len)
            override fun close() {
                try { src.close() } catch (_: Exception) {}
                try { p.waitForCompat(2000) } catch (_: Throwable) {}
                p.destroy()
            }
        }
    }

    override fun openOutputStream(path: String): OutputStream {
        val p = provider.spawnProcess()
        val raw = p.outputStream
        raw.write("cat > ${shQuote(path)}\n".toByteArray(Charsets.UTF_8))
        raw.flush()
        return object : FilterOutputStream(raw) {
            override fun close() {
                try { raw.flush() } catch (_: Exception) {}
                super.close()
                try { p.waitForCompat(5000) } catch (_: Throwable) {}
                p.destroy()
            }
        }
    }

    override suspend fun sizeOf(path: String): Long = provider.withSession {
        val out = exec(
            "stat -c %s ${shQuote(path)} 2>/dev/null || du -sk ${shQuote(path)} | cut -f1",
            noThrow = true
        ).trim()
        out.toLongOrNull() ?: 0L
    }

    /** Shell 引擎不承担普通路径访问（由 LocalEngine 优先），视为不可直达。 */
    override fun canAccess(path: String): Boolean = false

    // ---------- ls 输出解析 ----------

    private fun parseLs(output: String, basePath: String): List<VfsEntry> {
        val result = mutableListOf<VfsEntry>()
        output.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("total ")) return@forEach
            val tokens = line.split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val perms = tokens.first()
            if (perms.length < 10) return@forEach

            val isDir = perms[0] == 'd' || (perms[0] == 'l' && line.trimEnd().endsWith("/"))
            // 定位文件名：兼容 epoch(7字段) 与 日期(8字段) 两种格式
            var nameIdx = -1
            var size = 0L
            var mtime = 0L
            if (tokens.size >= 7 && tokens[5].toLongOrNull() != null) {
                size = tokens[4].toLongOrNull() ?: 0L
                mtime = (tokens[5].toLongOrNull() ?: 0L) * 1000
                nameIdx = 6
            } else if (tokens.size >= 8) {
                size = tokens[4].toLongOrNull() ?: 0L
                nameIdx = 7
            }
            if (nameIdx < 0) return@forEach
            var name = tokens.subList(nameIdx, tokens.size).joinToString(" ")
            if (name.endsWith("/")) name = name.trimEnd('/')
            if (name.isEmpty() || name == "." || name == "..") return@forEach

            result.add(
                VfsEntry(
                    path = joinPath(basePath, name),
                    name = name,
                    isDirectory = isDir,
                    size = if (isDir) 0 else size,
                    lastModified = mtime,
                    canRead = true,
                    canWrite = (perms.length > 6 && perms[5] == 'w') || (perms.length > 8 && perms[8] == 'w'),
                    isHidden = name.startsWith(".")
                )
            )
        }
        return result
    }
}
