package com.freefile.tools.unzip

import kotlinx.coroutines.CancellationException
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/** 本地归档创建引擎。所有方法均为阻塞方法，应在 IO 调度器中调用。 */
object CompressionEngine {
	enum class Format(val extension: String) {
		ZIP("zip"), SEVEN_Z("7z"), TAR("tar"), TAR_GZ("tar.gz"), TAR_BZ2("tar.bz2"), TAR_XZ("tar.xz")
	}

	enum class Level { STORE, FAST, NORMAL, MAXIMUM }

	data class Options(
		val format: Format = Format.ZIP,
		val level: Level = Level.NORMAL,
		val password: String? = null,
		val splitMb: Long = 0,
		val deleteSource: Boolean = false,
		val compressEach: Boolean = false
	)

	data class Progress(
		val filesDone: Int,
		val totalFiles: Int,
		val bytesDone: Long,
		val totalBytes: Long,
		val currentFile: String
	)

	fun compress(
		sources: List<File>,
		destination: File,
		options: Options = Options(),
		onProgress: (Progress) -> Unit = {},
		isCancelled: () -> Boolean = { false }
	) {
		require(sources.isNotEmpty()) { "压缩源不能为空" }
		require(sources.all { it.exists() }) { "压缩源不存在" }
		require(options.format == Format.ZIP || options.password.isNullOrBlank()) {
			"只有 ZIP 格式支持密码加密"
		}
		require(options.format == Format.ZIP || options.splitMb == 0L) {
			"分卷仅支持 ZIP 格式"
		}

		val sources = sources.map { it.absoluteFile }
		val output = destination.absoluteFile
		val fileList = sources.flatMap(::collectFiles)
		require(fileList.none { it == output }) { "目标压缩包不能位于压缩源内" }
		val totalBytes = fileList.sumOf { it.length() }
		val totalFiles = fileList.size
		var doneFiles = 0
		var doneBytes = 0L

		fun checkCancelled() {
			if (isCancelled()) throw CancellationException("已取消")
		}
		fun report(name: String) = onProgress(Progress(doneFiles, totalFiles, doneBytes, totalBytes, name))
		fun completed(file: File) {
			doneFiles++
			doneBytes += file.length()
			report(file.name)
		}

		val jobs: List<Pair<List<File>, File>> = if (options.compressEach) {
			sources.map { source ->
				val base = output.absolutePath.substringBeforeLast('.', output.absolutePath)
				listOf(source) to File("${base}_${source.name}.${options.format.extension}")
			}
		} else listOf(sources to output)

		for ((jobSources, target) in jobs) {
			checkCancelled()
			target.parentFile?.mkdirs()
			if (target.exists()) target.delete()
			when (options.format) {
				Format.ZIP -> writeZip(jobSources, target, options, ::checkCancelled, ::completed)
				Format.SEVEN_Z -> writeSevenZip(jobSources, target, ::checkCancelled, ::completed)
				Format.TAR, Format.TAR_GZ, Format.TAR_BZ2, Format.TAR_XZ ->
					writeTar(jobSources, target, options.format, ::checkCancelled, ::completed)
			}
			if (options.deleteSource) jobSources.forEach { it.deleteRecursively() }
		}
	}

	private fun collectFiles(source: File): List<File> =
		if (source.isFile) listOf(source) else source.walkTopDown().filter { it.isFile }.toList()

	private fun relativePath(root: File, file: File): String =
		if (root.isFile) root.name else file.relativeTo(root).path.replace(File.separatorChar, '/')

	private fun zipParameters(options: Options) = ZipParameters().apply {
		compressionMethod = if (options.level == Level.STORE) CompressionMethod.STORE else CompressionMethod.DEFLATE
		compressionLevel = when (options.level) {
			Level.STORE -> CompressionLevel.NO_COMPRESSION
			Level.FAST -> CompressionLevel.FASTEST
			Level.NORMAL -> CompressionLevel.NORMAL
			Level.MAXIMUM -> CompressionLevel.MAXIMUM
		}
		if (!options.password.isNullOrBlank()) {
			encryptionMethod = EncryptionMethod.AES
			aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
		}
	}

	private fun writeZip(
		sources: List<File>, target: File, options: Options,
		checkCancelled: () -> Unit, completed: (File) -> Unit
	) {
		val zip = ZipFile(target, options.password?.toCharArray())
		val parameters = zipParameters(options)
		val split = options.splitMb > 0
		val splitLength = options.splitMb * 1024L * 1024L
		for (source in sources) {
			checkCancelled()
			if (source.isFile) {
				if (split) {
					zip.createSplitZipFile(listOf(source), parameters, true, splitLength)
				} else {
					parameters.fileNameInZip = source.name
					zip.addFile(source, parameters)
				}
				completed(source)
			} else {
				if (split) {
					zip.createSplitZipFileFromFolder(source, parameters, true, splitLength)
				} else {
					zip.addFolder(source, parameters)
				}
				source.walkTopDown().filter { it.isFile }.forEach { checkCancelled(); completed(it) }
			}
		}
		// 压缩完成后验证密码确实生效（仅当设置了密码时）：
		// 重新以密码打开归档，确认已加密且能用密码读取首个条目，避免密码未传入/未生效的静默失败。
		if (!options.password.isNullOrBlank()) {
			ZipFile(target, options.password.toCharArray()).use { verify ->
				check(verify.isEncrypted) { "压缩包未加密，密码设置可能未生效" }
				verify.fileHeaders.firstOrNull { !it.isDirectory }?.let { h ->
					verify.getInputStream(h).use { it.read() }
				}
			}
		}
	}

	private fun writeSevenZip(
		sources: List<File>, target: File,
		checkCancelled: () -> Unit, completed: (File) -> Unit
	) {
		SevenZOutputFile(target).use { output ->
			for (source in sources) {
				source.walkTopDown().forEach { file ->
					checkCancelled()
					val entry = output.createArchiveEntry(
						file,
						relativePath(source, file) + if (file.isDirectory) "/" else ""
					)
					output.putArchiveEntry(entry)
					if (file.isFile) {
						FileInputStream(file).use { output.write(it) }
						completed(file)
					}
					output.closeArchiveEntry()
				}
			}
		}
	}

	private fun writeTar(
		sources: List<File>, target: File, format: Format,
		checkCancelled: () -> Unit, completed: (File) -> Unit
	) {
		BufferedOutputStream(FileOutputStream(target)).use { fileOutput ->
			compressor(fileOutput, format).use { compressed ->
				TarArchiveOutputStream(compressed).use { tar ->
					tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
					for (source in sources) {
						source.walkTopDown().forEach { file ->
							checkCancelled()
							val entry = TarArchiveEntry(file, relativePath(source, file))
							tar.putArchiveEntry(entry)
							if (file.isFile) {
								FileInputStream(file).use { it.copyTo(tar, 128 * 1024) }
								completed(file)
							}
							tar.closeArchiveEntry()
						}
					}
					tar.finish()
				}
			}
		}
	}

	private fun compressor(output: OutputStream, format: Format): OutputStream = when (format) {
		Format.TAR -> output
		Format.TAR_GZ -> GzipCompressorOutputStream(output)
		Format.TAR_BZ2 -> BZip2CompressorOutputStream(output)
		Format.TAR_XZ -> XZCompressorOutputStream(output)
		else -> error("不是 TAR 格式")
	}
}
