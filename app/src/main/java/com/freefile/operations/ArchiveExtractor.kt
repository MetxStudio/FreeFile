package com.freefile.operations

import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.VfsException
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import com.github.junrar.Archive as JunrarArchive

/**
 * 压缩包解压：支持 zip(zip4j) / 7z / tar 系列 / rar(junrar) / 单文件压缩。
 * 进度按字节汇报；支持取消（抛 CancellationException 由 TransferManager 统一处理）。
 */
object ArchiveExtractor {

    /** zip-slip 防护：目标路径必须落在 targetDir 内。 */
    private fun safeTarget(targetDir: File, logicalPath: String): File {
        val f = File(targetDir, logicalPath)
        val targetRoot = targetDir.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(targetRoot)) {
            throw SecurityException("压缩包内含非法路径: $logicalPath")
        }
        return f
    }

    private fun checkCancel(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("已取消")
    }

    fun extract(
        archivePath: String,
        targetDir: String,
        onProgress: (filesDone: Int, totalFiles: Int, bytesDone: Long, totalBytes: Long, currentFile: String) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val f = File(archivePath)
        if (!f.isFile) throw VfsException("压缩包不存在: $archivePath")
        val dest = File(targetDir).apply { mkdirs() }
        val name = f.name.lowercase()

        val entries = ArchiveEngine.entriesOf(archivePath)
        val totalFiles = entries.count { !it.isDirectory }
        val totalBytes = entries.sumOf { it.size }
        var doneFiles = 0
        var doneBytes = 0L
        fun report(current: String) = onProgress(doneFiles, totalFiles, doneBytes, totalBytes, current)

        report("")

        when {
            name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".apk") -> {
                val zf = ZipFile(f)
                if (zf.isEncrypted) {
                    throw VfsException("该压缩包已加密，暂不支持加密解压")
                }
                for (h in zf.fileHeaders) {
                    checkCancel(isCancelled)
                    val lp = h.fileName.replace('\\', '/').trim('/')
                    if (lp.isEmpty()) continue
                    if (h.isDirectory) {
                        safeTarget(dest, lp).mkdirs()
                        continue
                    }
                    zf.extractFile(h, dest.absolutePath)
                    doneFiles++
                    doneBytes += h.uncompressedSize
                    report(lp)
                }
            }
            name.endsWith(".7z") -> {
                SevenZFile(f).use { z ->
                    for (e in z.entries) {
                        checkCancel(isCancelled)
                        val lp = (e.name ?: continue).replace('\\', '/').trim('/')
                        if (lp.isEmpty()) continue
                        val out = safeTarget(dest, lp)
                        if (e.isDirectory) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        z.getInputStream(e).use { input ->
                            BufferedOutputStream(FileOutputStream(out)).use { input.copyTo(it, 128 * 1024) }
                        }
                        doneFiles++
                        doneBytes += e.size
                        report(lp)
                    }
                }
            }
            name.endsWith(".rar") -> {
                JunrarArchive(f).use { a ->
                    for (h in a.fileHeaders) {
                        checkCancel(isCancelled)
                        val lp = h.fileName.replace('\\', '/').trim('/')
                        if (lp.isEmpty()) continue
                        val out = safeTarget(dest, lp)
                        if (h.isDirectory) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(out)).use { a.extractFile(h, it) }
                        doneFiles++
                        doneBytes += h.fullUnpackSize
                        report(lp)
                    }
                }
            }
            name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
                name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ||
                name.endsWith(".tar.xz") || name.endsWith(".txz") -> {
                val raw = BufferedInputStream(f.inputStream())
                val tin = when {
                    name.endsWith(".tar") -> TarArchiveInputStream(raw)
                    name.endsWith(".tar.gz") || name.endsWith(".tgz") -> TarArchiveInputStream(GzipCompressorInputStream(raw))
                    name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> TarArchiveInputStream(BZip2CompressorInputStream(raw))
                    else -> TarArchiveInputStream(XZCompressorInputStream(raw))
                }
                tin.use { stream ->
                    var e = stream.nextTarEntry
                    while (e != null) {
                        checkCancel(isCancelled)
                        val lp = e.name.replace('\\', '/').trim('/')
                        if (lp.isNotEmpty()) {
                            val out = safeTarget(dest, lp)
                            if (e.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                BufferedOutputStream(FileOutputStream(out)).use { stream.copyTo(it, 128 * 1024) }
                                doneFiles++
                                doneBytes += e.size
                                report(lp)
                            }
                        }
                        e = stream.nextTarEntry
                    }
                }
            }
            name.endsWith(".gz") || name.endsWith(".bz2") || name.endsWith(".xz") -> {
                // 单文件压缩：还原为去扩展名的单个文件
                checkCancel(isCancelled)
                val outName = ArchiveEngine.run {
                    // 复用单文件名推导
                    listOf(".tar.gz" to 7, ".tar.bz2" to 8, ".tar.xz" to 7, ".gz" to 3, ".bz2" to 4, ".xz" to 3)
                        .firstOrNull { name.endsWith(it.first) }
                        ?.let { f.name.dropLast(it.second) }
                        ?: "${f.name}.out"
                }
                val raw = BufferedInputStream(f.inputStream())
                val input = when {
                    name.endsWith(".gz") -> GzipCompressorInputStream(raw)
                    name.endsWith(".bz2") -> BZip2CompressorInputStream(raw)
                    else -> XZCompressorInputStream(raw)
                }
                input.use { i ->
                    val out = File(dest, outName)
                    BufferedOutputStream(FileOutputStream(out)).use { i.copyTo(it, 128 * 1024) }
                    doneFiles = 1
                    report(outName)
                }
            }
            else -> throw VfsException("不支持的压缩格式: ${f.name}")
        }
    }
}
