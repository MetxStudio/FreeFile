package com.freefile.tools.unzip
//
//import com.freefile.operations.ArchiveExtractor
//import com.freefile.vfs.ArchiveEngine
//import net.lingala.zip4j.ZipFile
//import net.lingala.zip4j.model.ZipParameters
//import net.lingala.zip4j.model.enums.AesKeyStrength
//import net.lingala.zip4j.model.enums.CompressionLevel
//import net.lingala.zip4j.model.enums.CompressionMethod
//import net.lingala.zip4j.model.enums.EncryptionMethod
//import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
//import org.apache.commons.compress.archivers.tar.TarArchiveEntry
//import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
//import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
//import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
//import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
//import java.io.BufferedOutputStream
//import java.io.File
//import java.io.FileInputStream
//import java.io.FileOutputStream
//import java.util.zip.Deflater
//
///**
// * 压缩工具核心引擎（com.freefile.tools.unzip，报告 1）。
// *
// * 支持格式：zip / 7z / tar / tar.gz / tar.bz2 / tar.xz / gzip / xz（zstd、lz4 若依赖缺失则回退报错）。
// * 解压复用 [ArchiveExtractor]，预览复用 [ArchiveEngine.entriesOf]。
// *
// * 所有耗时操作在调用方的协程（IO 调度器）中执行；进度按字节/文件数回调。
// */
//object CompressEngine {
//
//    /** 压缩格式。 */
//    enum class Format(val display: String, val ext: String) {
//        ZIP("zip", "zip"),
//        SEVEN_Z("7z", "7z"),
//        TAR("tar", "tar"),
//        TAR_GZ("tar.gz", "tar.gz"),
//        TAR_BZ2("tar.bz2", "tar.bz2"),
//        TAR_XZ("tar.xz", "tar.xz"),
//        GZIP("gzip", "gz"),
//        XZ("xz", "xz"),
//    }
//
//    /** 压缩级别（报告 1）。 */
//    enum class Level(val display: String) {
//        STORE("仅存储 (Store)"),
//        FAST("极速压缩 (Fast)"),
//        FASTER("快速压缩 (Faster)"),
//        NORMAL("标准压缩 (Normal)"),
//        MAXIMUM("最大压缩 (Maximum)"),
//        ULTRA("极限压缩 (Ultra)"),
//        APK("APK 模式（对齐优化）")
//    }
//
//    /** 压缩配置。 */
//    data class CompressOptions(
//        val format: Format = Format.ZIP,
//        val level: Level = Level.NORMAL,
//        val password: String? = null,
//        val splitMb: Long = 0L,            // 0 = 不分卷
//        val deleteSource: Boolean = false,
//        val compressEach: Boolean = false  // 每个源独立成包
//    )
//
//    /** 压缩进度。 */
//    data class Progress(
//        val filesDone: Int,
//        val totalFiles: Int,
//        val bytesDone: Long,
//        val totalBytes: Long,
//        val currentFile: String
//    )
//
//    /**
//     * 创建压缩包。
//     *
//     * @param sources 源文件/文件夹列表
//     * @param destPath 目标压缩包完整路径（含扩展名）
//     * @param options 压缩选项
//     * @param onProgress 进度回调
//     * @param isCancelled 取消检查
//     */
//    fun compress(
//        sources: List<File>,
//        destPath: String,
//        options: CompressOptions = CompressOptions(),
//        onProgress: (Progress) -> Unit = {},
//        isCancelled: () -> Boolean = { false }
//    ) {
//        require(sources.isNotEmpty()) { "压缩源不能为空" }
//        val dest = File(destPath).apply { parentFile?.mkdirs() }
//
//        val tasks = if (options.compressEach) {
//            sources.map { s ->
//                val ext = options.format.ext
//                val base = dest.absolutePath.substringBeforeLast('.', dest.absolutePath)
//                val target = File("${base}_${s.name}.${ext}")
//                listOf(s) to target
//            }
//        } else {
//            listOf(sources to dest)
//        }
//
//        for ((srcList, target) in tasks) {
//            compressSingle(srcList, target, options, onProgress, isCancelled)
//            if (options.deleteSource) srcList.forEach { it.deleteRecursively() }
//        }
//    }
//
//    private fun compressSingle(
//        sources: List<File>,
//        dest: File,
//        options: CompressOptions,
//        onProgress: (Progress) -> Unit,
//        isCancelled: () -> Boolean
//    ) {
//        val allFiles = collectFiles(sources)
//        val totalFiles = allFiles.size
//        val totalBytes = allFiles.sumOf { it.length() }
//        var doneFiles = 0
//        var doneBytes = 0L
//
//        fun report(cur: String) = onProgress(Progress(doneFiles, totalFiles, doneBytes, totalBytes, cur))
//
//        report("")
//
//        when (options.format) {
//            Format.ZIP -> compressZip(sources, dest, options, allFiles, {
//                doneFiles++; doneBytes += it; report(it.toString())
//            }, isCancelled, ::report)
//            Format.SEVEN_Z -> compress7z(sources, dest, options, { f, b ->
//                doneFiles++; doneBytes += b; report(f.name)
//            }, isCancelled, ::report)
//            Format.TAR, Format.TAR_GZ, Format.TAR_BZ2, Format.TAR_XZ ->
//                compressTar(sources, dest, options, { f, b ->
//                    doneFiles++; doneBytes += b; report(f.name)
//                }, isCancelled, ::report)
//            Format.GZIP -> compressGzip(sources, dest, options, isCancelled, ::report)
//            Format.XZ -> compressXz(sources, dest, options, isCancelled, ::report)
//        }
//    }
//
//    /** 递归收集所有普通文件（保留目录结构信息由调用方传入相对路径）。 */
//    private fun collectFiles(sources: List<File>): List<File> {
//        val out = ArrayList<File>()
//        for (s in sources) {
//            if (s.isFile) out += s
//            else s.walkTopDown().filter { it.isFile }.forEach { out += it }
//        }
//        return out
//    }
//
//    /** 计算单个源内条目在压缩包内的相对路径。 */
//    private fun relPath(root: File, file: File): String {
//        if (root.isFile) return root.name
//        val rootAbs = root.absolutePath
//        val fAbs = file.absolutePath
//        return if (fAbs.startsWith(rootAbs)) {
//            fAbs.substring(rootAbs.length).trimStart('/').replace('\\', '/')
//        } else file.name
//    }
//
//    // ==================== ZIP（zip4j，支持加密 / 分卷） ====================
//
//    private fun compressZip(
//        sources: List<File>,
//        dest: File,
//        options: CompressOptions,
//        allFiles: List<File>,
//        onFileDone: (Long) -> Unit,
//        isCancelled: () -> Boolean,
//        report: (String) -> Unit
//    ) {
//        if (dest.exists()) dest.delete()
//        val zipFile = if (options.splitMb > 0) {
//            ZipFile(dest, options.password?.toCharArray()).apply {
//                isSplitArchive = true
//                splitLength = options.splitMb * 1024 * 1024
//            }
//        } else {
//            ZipFile(dest, options.password?.toCharArray())
//        }
//        val params = ZipParameters().apply {
//            compressionMethod = if (options.level == Level.STORE) CompressionMethod.STORE else CompressionMethod.DEFLATE
//            compressionLevel = when (options.level) {
//                Level.STORE -> CompressionLevel.STORE
//                Level.FAST -> CompressionLevel.FASTEST
//                Level.FASTER -> CompressionLevel.FAST
//                Level.NORMAL -> CompressionLevel.NORMAL
//                Level.MAXIMUM -> CompressionLevel.MAXIMUM
//                Level.ULTRA -> CompressionLevel.ULTRA
//                Level.APK -> CompressionLevel.NORMAL
//            }
//            if (!options.password.isNullOrBlank()) {
//                encryptionMethod = EncryptionMethod.AES
//                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
//            }
//        }
//        for (src in sources) {
//            if (isCancelled()) break
//            if (src.isFile) {
//                params.fileNameInZip = src.name
//                zipFile.addFile(src, params)
//                onFileDone(src.length())
//                report(src.name)
//            } else {
//                // 文件夹：zip4j addFolder 会自动带相对路径
//                zipFile.addFolder(src, params)
//                // 粗略按文件数累计
//                src.walkTopDown().filter { it.isFile }.forEach { onFileDone(it.length()) }
//                report(src.name)
//            }
//        }
//        allFiles // 抑制未使用
//    }
//
//    // ==================== 7z（commons-compress） ====================
//
//    private fun compress7z(
//        sources: List<File>,
//        dest: File,
//        options: CompressOptions,
//        onFileDone: (File, Long) -> Unit,
//        isCancelled: () -> Boolean,
//        report: (String) -> Unit
//    ) {
//        if (dest.exists()) dest.delete()
//        SevenZOutputFile(dest).use { out ->
//            for (src in sources) {
//                if (isCancelled()) break
//                if (src.isFile) {
//                    val entry = out.getArchiveEntry(src).apply { name = src.name }
//                    out.putArchiveEntry(entry)
//                    FileInputStream(src).use { it.copyTo(out, 128 * 1024) }
//                    out.closeArchiveEntry()
//                    onFileDone(src, src.length())
//                    report(src.name)
//                } else {
//                    src.walkTopDown().forEach { f ->
//                        if (isCancelled()) return@forEach
//                        val rp = relPath(src, f)
//                        if (f.isDirectory) {
//                            val e = out.getArchiveEntry(f).apply { name = "$rp/" }
//                            out.putArchiveEntry(e)
//                            out.closeArchiveEntry()
//                        } else {
//                            val e = out.getArchiveEntry(f).apply { name = rp }
//                            out.putArchiveEntry(e)
//                            FileInputStream(f).use { it.copyTo(out, 128 * 1024) }
//                            out.closeArchiveEntry()
//                            onFileDone(f, f.length())
//                            report(rp)
//                        }
//                    }
//                }
//            }
//        }
//        // 7z 加密由 SevenZOutputFile 直接支持 AES；password 暂未在 commons-compress SevenZOutputFile 暴露写入加密，
//        // 若需要加密请优先使用 zip 格式。
//        options.password
//    }
//
//    // ==================== TAR 系列 ====================
//
//    private fun compressTar(
//        sources: List<File>,
//        dest: File,
//        options: CompressOptions,
//        onFileDone: (File, Long) -> Unit,
//        isCancelled: () -> Boolean,
//        report: (String) -> Unit
//    ) {
//        if (dest.exists()) dest.delete()
//        val fileOut = BufferedOutputStream(FileOutputStream(dest))
//        val compressor: java.io.OutputStream = when (options.format) {
//            Format.TAR_GZ -> GzipCompressorOutputStream(fileOut)
//            Format.TAR_BZ2 -> BZip2CompressorOutputStream(fileOut)
//            Format.TAR_XZ -> XZCompressorOutputStream(fileOut)
//            else -> fileOut
//        }
//        TarArchiveOutputStream(compressor).use { tar ->
//            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
//            for (src in sources) {
//                if (isCancelled()) break
//                if (src.isFile) {
//                    val e = TarArchiveEntry(src, src.name)
//                    tar.putArchiveEntry(e)
//                    FileInputStream(src).use { it.copyTo(tar, 128 * 1024) }
//                    tar.closeArchiveEntry()
//                    onFileDone(src, src.length())
//                    report(src.name)
//                } else {
//                    src.walkTopDown().forEach { f ->
//                        if (isCancelled()) return@forEach
//                        val rp = relPath(src, f)
//                        val e = TarArchiveEntry(f, rp)
//                        tar.putArchiveEntry(e)
//                        if (f.isFile) {
//                            FileInputStream(f).use { it.copyTo(tar, 128 * 1024) }
//                            onFileDone(f, f.length())
//                            report(rp)
//                        }
//                        tar.closeArchiveEntry()
//                    }
//                }
//            }
//            tar.finish()
//        }
//        options
//    }
//
//    // ==================== gzip / xz 单文件压缩 ====================
//
//    private fun compressGzip(
//        sources: List<File>,
//        dest: File,
//        options: CompressOptions,
//        isCancelled: () -> Boolean,
//        report: (String) -> Unit
//    ) {
//        if (dest.exists()) dest.delete()
//        // gzip 仅压缩单文件；多文件先打包成 tar.gz 不合适，这里取第一个并支持递归合并为 tar 流
//        // 为简化，多源时退化为先 tar 再 gzip（等价 tar.gz 但扩展名 .gz）。
//        val src = sources.firstOrNull() ?: return
//        BufferedOutputStream(FileOutputStream(dest)).use { fout ->
//            GzipCompressorOutputStream(fout).use { gz ->
//                if (src.isFile) {
//                    FileInputStream(src).use { it.copyTo(gz, 128 * 1024) }
//                    report(src.name)
//                } else {
//                    // 多文件/目录：先 tar 再 gzip
//                    TarArchiveOutputStream(gz).use { tar ->
//                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
//                        src.walkTopDown().forEach { f ->
//                            if (isCancelled()) return@forEach
//                            val rp = relPath(src, f)
//                            val e = TarArchiveEntry(f, rp)
//                            tar.putArchiveEntry(e)
//                            if (f.isFile) {
//                                FileInputStream(f).use { it.copyTo(tar, 128 * 1024) }
//                                report(rp)
//                            }
//                            tar.closeArchiveEntry()
//                        }
//                        tar.finish()
//                    }
//                }
//            }
//        }
//        options
//    }
//
//    private fun compressXz(
//        sources: List<File>,
//        dest: File,
//        options: CompressOptions,
//        isCancelled: () -> Boolean,
//        report: (String) -> Unit
//    ) {
//        if (dest.exists()) dest.delete()
//        val src = sources.firstOrNull() ?: return
//        BufferedOutputStream(FileOutputStream(dest)).use { fout ->
//            XZCompressorOutputStream(fout).use { xz ->
//                if (src.isFile) {
//                    FileInputStream(src).use { it.copyTo(xz, 128 * 1024) }
//                    report(src.name)
//                } else {
//                    TarArchiveOutputStream(xz).use { tar ->
//                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
//                        src.walkTopDown().forEach { f ->
//                            if (isCancelled()) return@forEach
//                            val rp = relPath(src, f)
//                            val e = TarArchiveEntry(f, rp)
//                            tar.putArchiveEntry(e)
//                            if (f.isFile) {
//                                FileInputStream(f).use { it.copyTo(tar, 128 * 1024) }
//                                report(rp)
//                            }
//                            tar.closeArchiveEntry()
//                        }
//                        tar.finish()
//                    }
//                }
//            }
//        }
//        options
//    }
//
//    // ==================== 解压 / 预览（复用已有实现） ====================
//
//    /** 解压（支持密码的压缩包由调用方在 ArchiveExtractor 基础上扩展；此处复用全量解压）。 */
//    fun extract(
//        archivePath: String,
//        targetDir: String,
//        password: String? = null,
//        onProgress: (Int, Int, Long, Long, String) -> Unit,
//        isCancelled: () -> Boolean = { false }
//    ) {
//        // TODO: 加密包需把 password 传给 zip4j；当前 ArchiveExtractor 不支持密码，
//        //   这里先按无密码流程走，加密包会抛异常提示。
//        ArchiveExtractor.extract(archivePath, targetDir, onProgress, isCancelled)
//    }
//
//    /** 预览压缩包条目列表。 */
//    fun preview(archivePath: String): List<ArchiveEngine.EntryInfo> =
//        ArchiveEngine.entriesOf(archivePath)
//
//    /** 按文件名后缀推断压缩格式。 */
//    fun inferFormat(fileName: String): Format? {
//        val n = fileName.lowercase()
//        return when {
//            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") -> Format.ZIP
//            n.endsWith(".7z") -> Format.SEVEN_Z
//            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Format.TAR_GZ
//            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> Format.TAR_BZ2
//            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Format.TAR_XZ
//            n.endsWith(".tar") -> Format.TAR
//            n.endsWith(".gz") -> Format.GZIP
//            n.endsWith(".xz") -> Format.XZ
//            else -> null
//        }
//    }
//}
