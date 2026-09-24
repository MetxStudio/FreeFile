package com.freefile.vfs

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import net.lingala.zip4j.ZipFile
import com.github.junrar.Archive as JunrarArchive

/**
 * 压缩包虚拟文件系统引擎（只读）。
 *
 * 路径语法：<归档路径>#<归档内相对路径>，如 /sdcard/test.zip#/docs/a.txt。
 * 支持：zip/jar/apk(zip4j)、7z、tar/tar.gz/tgz/tar.bz2/tbz2/tar.xz/txz、
 *      rar4/rar5(junrar)、单文件压缩 gz/bz2/xz。
 */
object ArchiveEngine : FileEngine {

    override val id = "archive"

    private const val SEP = "#"

    // ---------- 路径语法 ----------

    fun isArchivePath(path: String): Boolean = path.contains(SEP)

    /** 归档文件本身的路径。 */
    fun archiveOf(path: String): String = path.substringBefore(SEP)

    /** 归档内相对路径（'/' 分隔，无首尾 '/'；空表示归档根）。 */
    fun innerOf(path: String): String = path.substringAfter(SEP, "").trim('/')

    fun buildPath(archive: String, inner: String): String =
        if (inner.isEmpty()) "$archive$SEP" else "$archive$SEP/$inner"

    /** 文件名是否为可浏览的压缩格式。 */
    fun isArchiveFile(name: String): Boolean = kindOf(name) != null

    private enum class Kind { ZIP, SEVEN_Z, RAR, TAR, COMPRESSED }

    private fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz") ||
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") ||
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TAR
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") -> Kind.ZIP
            n.endsWith(".7z") -> Kind.SEVEN_Z
            n.endsWith(".rar") -> Kind.RAR
            n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".xz") -> Kind.COMPRESSED
            else -> null
        }
    }

    // ---------- 条目模型与缓存 ----------

    data class EntryInfo(
        val logicalPath: String,   // 归档内相对路径，'/' 分隔，无尾 '/'
        val isDirectory: Boolean,
        val size: Long
    )

    private val cache = HashMap<String, Pair<Long, List<EntryInfo>>>()

    /** 读取（带缓存）归档完整条目列表；缓存按文件修改时间失效。 */
    fun entriesOf(archivePath: String): List<EntryInfo> {
        val f = File(archivePath)
        if (!f.isFile) throw VfsException("压缩包不存在: $archivePath")
        val stamp = f.lastModified()
        synchronized(cache) {
            cache[archivePath]?.let { if (it.first == stamp) return it.second }
        }
        val entries = readEntries(f)
        synchronized(cache) { cache[archivePath] = stamp to entries }
        return entries
    }

    private fun readEntries(f: File): List<EntryInfo> = when (kindOf(f.name)) {
        Kind.ZIP -> zipEntries(f)
        Kind.SEVEN_Z -> sevenZEntries(f)
        Kind.RAR -> rarEntries(f)
        Kind.TAR -> tarEntries(f)
        Kind.COMPRESSED -> listOf(EntryInfo(singleFileName(f.name), false, 0))
        null -> throw VfsException("不支持的压缩格式: ${f.name}")
    }

    private fun singleFileName(archiveName: String): String {
        val n = archiveName.trim()
        return when {
            n.endsWith(".tar.gz") -> n.dropLast(7)
            n.endsWith(".tar.bz2") -> n.dropLast(8)
            n.endsWith(".tar.xz") -> n.dropLast(7)
            n.endsWith(".gz") -> n.dropLast(3)
            n.endsWith(".bz2") -> n.dropLast(4)
            n.endsWith(".xz") -> n.dropLast(3)
            else -> "$n.out"
        }.ifEmpty { "$n.out" }
    }

    private fun zipEntries(f: File): List<EntryInfo> =
        ZipFile(f).fileHeaders.mapNotNull { h ->
            val lp = h.fileName.replace('\\', '/').trim('/')
            if (lp.isEmpty()) null
            else EntryInfo(lp, h.isDirectory, if (h.isDirectory) 0 else h.uncompressedSize)
        }

    private fun sevenZEntries(f: File): List<EntryInfo> =
        SevenZFile(f).use { z ->
            z.entries.mapNotNull { e ->
                val lp = (e.name ?: return@mapNotNull null).replace('\\', '/').trim('/')
                if (lp.isEmpty()) null
                else EntryInfo(lp, e.isDirectory, if (e.isDirectory) 0 else e.size)
            }
        }

    private fun rarEntries(f: File): List<EntryInfo> =
        JunrarArchive(f).use { a ->
            a.fileHeaders.mapNotNull { h ->
                val lp = h.fileName.replace('\\', '/').trim('/')
                if (lp.isEmpty()) null
                else EntryInfo(lp, h.isDirectory, if (h.isDirectory) 0 else h.fullUnpackSize)
            }
        }

    private fun tarEntries(f: File): List<EntryInfo> {
        val result = mutableListOf<EntryInfo>()
        openTarStream(f).use { tin ->
            var e = tin.nextTarEntry
            while (e != null) {
                val lp = e.name.replace('\\', '/').trim('/')
                if (lp.isNotEmpty()) {
                    result.add(EntryInfo(lp, e.isDirectory, if (e.isDirectory) 0 else e.size))
                }
                e = tin.nextTarEntry
            }
        }
        return result
    }

    private fun openTarStream(f: File): TarArchiveInputStream {
        val n = f.name.lowercase()
        val raw = BufferedInputStream(f.inputStream())
        return when {
            n.endsWith(".tar") -> TarArchiveInputStream(raw)
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TarArchiveInputStream(GzipCompressorInputStream(raw))
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> TarArchiveInputStream(BZip2CompressorInputStream(raw))
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> TarArchiveInputStream(XZCompressorInputStream(raw))
            else -> {
                raw.close()
                throw VfsException("不支持的归档: ${f.name}")
            }
        }
    }

    private fun openCompressorStream(f: File): InputStream {
        val raw = BufferedInputStream(f.inputStream())
        val n = f.name.lowercase()
        return when {
            n.endsWith(".gz") -> GzipCompressorInputStream(raw)
            n.endsWith(".bz2") -> BZip2CompressorInputStream(raw)
            else -> XZCompressorInputStream(raw)
        }
    }

    // ---------- FileEngine 实现（只读） ----------

    override fun isAvailable(): Boolean = true

    override fun canAccess(path: String): Boolean = isArchivePath(path)

    override suspend fun list(path: String): List<VfsEntry> {
        val archive = archiveOf(path)
        val inner = innerOf(path)
        val prefix = if (inner.isEmpty()) "" else "$inner/"
        val result = LinkedHashMap<String, VfsEntry>()
        for (e in entriesOf(archive)) {
            if (!e.logicalPath.startsWith(prefix) || e.logicalPath == inner) continue
            val rel = e.logicalPath.removePrefix(prefix)
            if (rel.isEmpty()) continue
            val top = rel.substringBefore('/')
            val isDir = e.isDirectory || rel.contains('/')
            // 合并：文件条目与隐式目录条目统一（目录优先）
            val existing = result[top]
            if (existing != null && !isDir) continue
            val childInner = if (inner.isEmpty()) top else "$inner/$top"
            result[top] = VfsEntry(
                path = buildPath(archive, childInner),
                name = top,
                isDirectory = isDir,
                size = if (isDir) 0 else e.size,
                lastModified = File(archive).lastModified()
            )
        }
        return result.values.toList()
    }

    override suspend fun stat(path: String): VfsEntry? {
        val archive = archiveOf(path)
        val inner = innerOf(path)
        if (inner.isEmpty()) {
            val f = File(archive)
            if (!f.isFile) return null
            return VfsEntry(path, pathName(archive), isDirectory = true, size = 0, lastModified = f.lastModified())
        }
        val e = entriesOf(archive).firstOrNull { it.logicalPath == inner } ?: return null
        return VfsEntry(
            path = path,
            name = pathName(inner),
            isDirectory = e.isDirectory,
            size = e.size,
            lastModified = File(archive).lastModified()
        )
    }

    override suspend fun exists(path: String): Boolean {
        val archive = archiveOf(path)
        if (!File(archive).isFile) return false
        val inner = innerOf(path)
        if (inner.isEmpty()) return true
        return entriesOf(archive).any { it.logicalPath == inner }
    }

    override suspend fun mkdir(path: String): Boolean =
        throw VfsException("压缩包为只读，请先解压后再修改")

    override suspend fun mkdirs(path: String): Boolean =
        throw VfsException("压缩包为只读，请先解压后再修改")

    override suspend fun rename(oldPath: String, newPath: String): Boolean =
        throw VfsException("压缩包为只读，请先解压后再修改")

    override suspend fun delete(path: String, recursive: Boolean): Boolean =
        throw VfsException("压缩包为只读，请先解压后再修改")

    override fun openOutputStream(path: String): java.io.OutputStream =
        throw VfsException("压缩包为只读，请先解压后再修改")

    /** 打开归档内文件的读取流（已解压内容）。 */
    override fun openInputStream(path: String): InputStream {
        val archive = archiveOf(path)
        val inner = innerOf(path)
        if (inner.isEmpty()) throw VfsException("无法打开目录: $path")
        val f = File(archive)
        if (!f.isFile) throw VfsException("压缩包不存在: $archive")
        return when (kindOf(f.name)) {
            Kind.ZIP -> openZipEntry(f, inner)
            Kind.SEVEN_Z -> openSevenZEntry(f, inner)
            Kind.RAR -> openRarEntry(f, inner)
            Kind.TAR -> openTarEntry(f, inner)
            Kind.COMPRESSED -> openCompressorStream(f)
            null -> throw VfsException("不支持的压缩格式: ${f.name}")
        }
    }

    private fun openZipEntry(f: File, inner: String): InputStream {
        val zf = ZipFile(f)
        val header = zf.getFileHeader(inner) ?: throw VfsException("压缩包内不存在: $inner")
        if (header.isDirectory) throw VfsException("目标是目录: $inner")
        return zf.getInputStream(header)
    }

    private fun openSevenZEntry(f: File, inner: String): InputStream {
        val z = SevenZFile(f)
        val e = z.entries.firstOrNull {
            (it.name ?: "").replace('\\', '/').trim('/') == inner && !it.isDirectory
        }
        if (e == null) {
            z.close()
            throw VfsException("压缩包内不存在: $inner")
        }
        val raw = z.getInputStream(e)
        // 读完内容后释放 SevenZFile
        return object : FilterInputStream(raw) {
            override fun close() {
                super.close()
                try { z.close() } catch (_: Exception) {}
            }
        }
    }

    private fun openTarEntry(f: File, inner: String): InputStream {
        val tin = openTarStream(f)
        var e = tin.nextTarEntry
        while (e != null) {
            val lp = e.name.replace('\\', '/').trim('/')
            if (lp == inner && !e.isDirectory) {
                // 命中目标条目：包装流并在关闭时释放底层 tar 流
                return object : FilterInputStream(tin) {
                    override fun close() {
                        super.close()
                        try { tin.close() } catch (_: Exception) {}
                    }
                }
            }
            e = tin.nextTarEntry
        }
        tin.close()
        throw VfsException("压缩包内不存在: $inner")
    }

    /** rar 无随机读取流：后台线程 extractFile 到管道，主线程读。 */
    private fun openRarEntry(f: File, inner: String): InputStream {
        val pipedIn = PipedInputStream(256 * 1024)
        val pipedOut = PipedOutputStream(pipedIn)
        val t = Thread {
            var a: JunrarArchive? = null
            try {
                a = JunrarArchive(f)
                val h = a.fileHeaders.firstOrNull {
                    it.fileName.replace('\\', '/').trim('/') == inner && !it.isDirectory
                }
                if (h != null) a.extractFile(h, pipedOut) else throw VfsException("压缩包内不存在: $inner")
            } catch (_: Exception) {
                // 管道端直接关闭 → 读取方得到 EOF（rar 流式读取的固有限制）
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
                try { a?.close() } catch (_: Exception) {}
            }
        }
        t.isDaemon = true
        t.start()
        return pipedIn
    }

    override suspend fun sizeOf(path: String): Long {
        val archive = archiveOf(path)
        val inner = innerOf(path)
        if (inner.isEmpty()) return 0
        val entries = entriesOf(archive)
        return entries
            .filter { it.logicalPath == inner || it.logicalPath.startsWith("$inner/") }
            .sumOf { it.size }
    }

    /** 去除缓存（归档被修改/删除后调用）。 */
    fun invalidate(archivePath: String) {
        synchronized(cache) { cache.remove(archivePath) }
    }
}
