package com.freefile.vfs

/**
 * 统一文件条目模型：所有引擎（Local/Root/Shizuku/压缩包/网络）的输出载体。
 * 与 java.io.File 解耦，便于跨引擎操作。
 */
data class VfsEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    val isHidden: Boolean = false
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    /** 父目录路径；根路径返回 null。remote:// 路径的根为自身。 */
    val parentPath: String?
        get() {
            // remote://<id>/sub 的父级：remote://<id>（无 sub 时返回 null）
            if (path.startsWith("remote://")) {
                val rest = path.removePrefix("remote://")
                return if (rest.contains('/')) "remote://" + rest.substringBefore('/') + "/" else null
            }
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return when {
                idx <= 0 -> if (path.startsWith("/")) "/" else null
                else -> trimmed.substring(0, idx)
            }
        }
}

/** 排序方式。 */
enum class SortBy { NAME, SIZE, TYPE, MODIFIED }

/** 对条目排序：文件夹优先置顶，再按指定维度。 */
fun List<VfsEntry>.sortedForDisplay(sortBy: SortBy, asc: Boolean): List<VfsEntry> {
    val comparator = Comparator<VfsEntry> { a, b ->
        if (a.isDirectory != b.isDirectory) {
            return@Comparator if (a.isDirectory) -1 else 1
        }
        val r = when (sortBy) {
            SortBy.NAME -> a.name.lowercase().compareTo(b.name.lowercase(), ignoreCase = true)
            SortBy.SIZE -> a.size.compareTo(b.size)
            SortBy.TYPE -> a.extension.compareTo(b.extension).let { if (it == 0) a.name.compareTo(b.name) else it }
            SortBy.MODIFIED -> a.lastModified.compareTo(b.lastModified)
        }
        if (asc) r else -r
    }
    return sortedWith(comparator)
}
