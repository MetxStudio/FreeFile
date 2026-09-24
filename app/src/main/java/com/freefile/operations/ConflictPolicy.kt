package com.freefile.operations

import com.freefile.vfs.VfsEntry

/** 单个冲突的处置方式。 */
enum class ConflictAction { OVERWRITE, SKIP, RENAME }

/** 冲突解析结果：全局策略 或 逐条指定。 */
sealed class ConflictResolution {
    object OverwriteAll : ConflictResolution()
    object SkipAll : ConflictResolution()
    object RenameAll : ConflictResolution()
    /** 逐条：key = 源条目路径。未指定的默认跳过。 */
    data class PerFile(val decisions: Map<String, ConflictAction>) : ConflictResolution()
}

/** 预扫描发现的冲突。 */
data class Conflict(
    val source: VfsEntry,
    val target: VfsEntry
)

/** 操作种类。 */
enum class OpKind { COPY, MOVE, DELETE, EXTRACT }

/** 任务状态。 */
enum class TaskState { RUNNING, DONE, CANCELLED, FAILED }

/** 进度数据（UI 渲染用）。 */
data class TransferProgress(
    val taskId: Long,
    val kind: OpKind,
    val state: TaskState,
    val currentFile: String,
    val filesDone: Int,
    val totalFiles: Int,
    val bytesDone: Long,
    val totalBytes: Long,
    val error: String? = null
) {
    val percent: Int
        get() = if (totalBytes > 0) ((bytesDone * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}
