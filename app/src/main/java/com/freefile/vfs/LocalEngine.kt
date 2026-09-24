package com.freefile.vfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 本地引擎：基于 java.io.File。
 * 配合 MANAGE_EXTERNAL_STORAGE（所有文件访问权限）可直接读写公共存储，
 * 性能远高于 SAF/ContentResolver 路线，是普通路径的首选引擎。
 */
object LocalEngine : FileEngine {

    override val id: String = "local"

    override fun isAvailable(): Boolean = true

    override suspend fun list(path: String): List<VfsEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.isDirectory) throw VfsException("不是目录: $path")
        dir.listFiles()?.map { it.toEntry() } ?: throw VfsException("无法访问: $path")
    }

    override suspend fun stat(path: String): VfsEntry? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.exists()) f.toEntry() else null
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).exists()
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).mkdir()
    }

    override suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).mkdirs()
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val src = File(oldPath)
        val dst = File(newPath)
        if (dst.exists()) throw VfsException("目标已存在: $newPath")
        src.renameTo(dst)
    }

    override suspend fun delete(path: String, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.exists()) return@withContext true
        if (f.isDirectory && recursive) f.deleteRecursively() else f.delete()
    }

    override fun openInputStream(path: String): InputStream = FileInputStream(File(path))

    override fun openOutputStream(path: String): OutputStream = FileOutputStream(File(path), false)

    override suspend fun sizeOf(path: String): Long = withContext(Dispatchers.IO) {
        val f = File(path)
        when {
            f.isFile -> f.length()
            f.isDirectory -> f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            else -> 0L
        }
    }

    override fun canAccess(path: String): Boolean {
        val f = File(path)
        return f.canRead()
    }

    private fun File.toEntry() = VfsEntry(
        path = absolutePath,
        name = name.ifEmpty { absolutePath },
        isDirectory = isDirectory,
        size = length(),
        lastModified = lastModified(),
        canRead = canRead(),
        canWrite = canWrite(),
        isHidden = isHidden
    )
}
