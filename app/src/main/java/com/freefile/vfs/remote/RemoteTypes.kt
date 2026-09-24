package com.freefile.vfs.remote

import com.freefile.vfs.VfsEntry
import java.io.InputStream
import java.io.OutputStream

/** 远程位置类型。 */
object RemoteTypes {
    const val WEBDAV = "WEBDAV"
    const val FTP = "FTP"
    const val SMB = "SMB"
    const val S3 = "S3"   // 同时覆盖 OSS 等 S3 兼容端点

    val ALL = listOf(WEBDAV, FTP, SMB, S3)

    /** 各类型默认端口。 */
    fun defaultPort(type: String, ssl: Boolean): Int = when (type) {
        WEBDAV -> if (ssl) 443 else 80
        FTP -> 21
        SMB -> 445
        S3 -> if (ssl) 443 else 80
        else -> 80
    }
}

/**
 * 远程文件客户端统一接口（子路径均为以 "/" 开头的位置内绝对路径）。
 * 各协议（WebDAV/FTP/SMB/S3）各实现一份，由 [RemoteEngine] 分发。
 */
interface RemoteClient {
    suspend fun list(dir: String): List<VfsEntry>
    suspend fun stat(path: String): VfsEntry?
    suspend fun mkdir(path: String)
    suspend fun delete(path: String, recursive: Boolean)
    suspend fun rename(oldPath: String, newPath: String)
    fun read(path: String): InputStream
    fun write(path: String): OutputStream
    fun close()
}
