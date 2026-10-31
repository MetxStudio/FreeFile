package com.freefile.tools.ai.tool

import android.content.Context
import com.freefile.vfs.ArchiveEngine
import java.io.File

/**
 * 路径安全闸：规范化、目录穿越拦截、授权范围校验、敏感目录黑名单。
 *
 * - 所有工具入参路径必须先过 [checkRead] / [checkWrite]；
 * - 纯词法归一 [lexicalNormalize] 不依赖文件系统，可直接单元测试；
 * - `remote://`、压缩包 `#` 虚拟路径 MVP 仅允许只读；
 * - 写操作限定在主存储与外接存储内；系统目录与应用私有目录一律拒绝。
 */
object PathGuard {

    const val REMOTE_PREFIX = "remote://"

    /** 主存储可写根（canonical 后 /sdcard 一般落在 /storage/emulated/0）。 */
    private val PRIMARY_WRITE_ROOTS = listOf(
        "/storage/emulated/0",
        "/storage/self/primary"
    )

    /** 规范化结果。 */
    data class Check(
        val allowed: Boolean,
        val normalized: String,
        val reason: String? = null,
        val virtual: Boolean = false
    )

    fun isRemote(path: String): Boolean = path.startsWith(REMOTE_PREFIX)

    fun isArchive(path: String): Boolean = ArchiveEngine.isArchivePath(path)

    fun isVirtual(path: String): Boolean = isRemote(path) || isArchive(path)

    /**
     * 纯词法归一：拒绝相对路径与逃逸根目录的 `..`。
     * 虚拟路径保留 scheme 部分。非法返回 null。
     */
    fun lexicalNormalize(raw: String): String? {
        val p = raw.trim().trimEnd('/')
        if (p.isEmpty()) return null
        if (isRemote(p)) {
            val rest = p.removePrefix(REMOTE_PREFIX)
            val slash = rest.indexOf('/')
            val id = if (slash >= 0) rest.substring(0, slash) else rest
            val sub = if (slash >= 0) rest.substring(slash) else "/"
            val normSub = normalizeAbsolute(sub) ?: return null
            return if (normSub == "/") "$REMOTE_PREFIX$id/" else "$REMOTE_PREFIX$id$normSub"
        }
        return normalizeAbsolute(p)
    }

    private fun normalizeAbsolute(p: String): String? {
        if (!p.startsWith("/")) return null
        val stack = ArrayDeque<String>()
        for (seg in p.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                if (stack.isEmpty()) return null
                stack.removeLast()
            } else {
                stack.addLast(seg)
            }
        }
        return "/" + stack.joinToString("/")
    }

    /** 基于真实文件系统做 canonical 归一（解析 /sdcard 符号链接），失败回退词法结果。 */
    fun canonicalize(raw: String): String? {
        val norm = lexicalNormalize(raw) ?: return null
        if (isVirtual(norm)) return norm
        return try {
            val f = File(norm)
            when {
                f.exists() -> f.canonicalPath
                f.parentFile?.exists() == true ->
                    File(f.parentFile.canonicalPath, f.name).absolutePath
                else -> norm
            }
        } catch (_: Exception) {
            norm
        }
    }

    /** 敏感目录：应用私有数据、保险箱等。 */
    fun isSensitive(context: Context, canonical: String): Boolean {
        if (canonical.split('/').any { it == ".safe" || it == ".freefile_safe" }) return true
        if (canonical.startsWith("/data/data/") || canonical.startsWith("/data/user/")) return true
        val protectedDirs = listOfNotNull(
            context.filesDir?.absolutePath,
            context.cacheDir?.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
            context.noBackupFilesDir?.absolutePath
        )
        return protectedDirs.any { canonical == it || canonical.startsWith("$it/") }
    }

    /** 外接存储根：/storage/<卷名>（排除 emulated/self）。 */
    private fun isRemovableRoot(canonical: String): Boolean {
        if (!canonical.startsWith("/storage/")) return false
        val rest = canonical.removePrefix("/storage/")
        val volume = rest.substringBefore('/')
        return volume.isNotEmpty() &&
            volume != "emulated" &&
            volume != "self" &&
            volume != "sdcard"
    }

    private fun underPrimary(canonical: String): Boolean =
        PRIMARY_WRITE_ROOTS.any { canonical == it || canonical.startsWith("$it/") }

    suspend fun checkRead(context: Context, rawPath: String): Check {
        val path = rawPath.trim()
        if (path.isEmpty()) return Check(false, "", "路径为空")
        val normalized = if (isVirtual(path)) lexicalNormalize(path) ?: path
        else canonicalize(path) ?: lexicalNormalize(path)
        if (normalized == null) return Check(false, path, "非法路径：目录穿越被拦截")
        if (isVirtual(normalized)) return Check(true, normalized, virtual = true)
        if (isSensitive(context, normalized)) {
            return Check(false, normalized, "敏感目录默认不可访问：$normalized")
        }
        return Check(true, normalized)
    }

    suspend fun checkWrite(context: Context, rawPath: String): Check {
        val path = rawPath.trim()
        if (path.isEmpty()) return Check(false, "", "路径为空")
        val normalized = canonicalize(path) ?: lexicalNormalize(path)
        if (normalized == null) return Check(false, path, "非法路径：目录穿越被拦截")
        if (isVirtual(normalized)) {
            return Check(false, normalized, "暂不支持对远程位置或压缩包内路径执行写操作")
        }
        if (isSensitive(context, normalized)) {
            return Check(false, normalized, "敏感目录禁止写入：$normalized")
        }
        val allowed = underPrimary(normalized) || isRemovableRoot(normalized)
        if (!allowed) {
            return Check(false, normalized, "越权拦截：写操作仅限共享存储范围内：$normalized")
        }
        return Check(true, normalized)
    }
}
