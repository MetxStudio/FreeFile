package com.freefile.vfs

import java.io.InputStream
import java.io.OutputStream

/**
 * 文件引擎统一接口：列出/读写/增删改等基础能力。
 * 实现：LocalEngine(java.io.File)、ShellEngine(su / Shizuku shell)。
 */
interface FileEngine {

    /** 引擎标识。 */
    val id: String

    /** 该引擎当前是否可用（权限/服务就绪）。 */
    fun isAvailable(): Boolean

    /** 列出目录内容；目录不存在或不可访问时抛 [VfsException]。 */
    suspend fun list(path: String): List<VfsEntry>

    /** 获取单个条目信息；不存在时返回 null。 */
    suspend fun stat(path: String): VfsEntry?

    /** 判断路径是否存在。 */
    suspend fun exists(path: String): Boolean

    suspend fun mkdir(path: String): Boolean

    /** 递归创建目录。 */
    suspend fun mkdirs(path: String): Boolean

    suspend fun rename(oldPath: String, newPath: String): Boolean

    suspend fun delete(path: String, recursive: Boolean = false): Boolean

    /** 打开输入流读取文件。 */
    fun openInputStream(path: String): InputStream

    /** 打开输出流写入文件（覆盖）。 */
    fun openOutputStream(path: String): OutputStream

    /** 计算目录大小（字节，递归）。 */
    suspend fun sizeOf(path: String): Long

    /** 判断该引擎能否直接访问此路径（无需 shell 开销）。 */
    fun canAccess(path: String): Boolean
}

/** VFS 层统一异常。 */
class VfsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 工具：规范化路径拼接。 */
fun joinPath(parent: String, child: String): String {
    if (parent == "/") return "/$child"
    return "${parent.trimEnd('/')}/$child"
}

/** 工具：文件名提取。 */
fun pathName(path: String): String {
    val t = path.trimEnd('/')
    return t.substringAfterLast('/').ifEmpty { path }
}
