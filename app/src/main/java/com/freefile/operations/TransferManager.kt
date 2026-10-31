package com.freefile.operations

import com.freefile.FreeFileApp
import com.freefile.R
import com.freefile.tools.unzip.CompressionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 传输任务管理器：全局单例，负责后台执行复制/移动/删除任务，
 * 向 UI 暴露进度列表，支持取消单个任务。
 */
object TransferManager {

    data class Task(
        val progress: TransferProgress,
        val kind: OpKind,
        val title: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idGen = AtomicLong(1)

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val jobs = HashMap<Long, Job>()

    /** 入队复制任务。 */
    fun enqueueCopy(
        entries: List<com.freefile.vfs.VfsEntry>,
        targetDir: String,
        conflicts: List<Conflict> = emptyList(),
        resolution: ConflictResolution = ConflictResolution.OverwriteAll,
        onCompleted: (ok: Boolean, message: String) -> Unit = { _, _ -> }
    ): Long = enqueue(
        OpKind.COPY,
        FreeFileApp.instance.getString(R.string.task_copy, entries.size, targetDir)
    ) { progress, cancelled ->
        FileOperations.copy(
            entries, targetDir, conflicts, resolution,
            onProgress = { f, t, b, tb, cur ->
                progress(TransferProgress(0, OpKind.COPY, TaskState.RUNNING, cur, f, t, b, tb))
            },
            isCancelled = cancelled
        )
    }.also { taskId ->
        attachCallback(taskId, onCompleted)
    }

    /** 入队移动任务。 */
    fun enqueueMove(
        entries: List<com.freefile.vfs.VfsEntry>,
        targetDir: String,
        conflicts: List<Conflict> = emptyList(),
        resolution: ConflictResolution = ConflictResolution.OverwriteAll,
        onCompleted: (ok: Boolean, message: String) -> Unit = { _, _ -> }
    ): Long = enqueue(
        OpKind.MOVE,
        FreeFileApp.instance.getString(R.string.task_move, entries.size, targetDir)
    ) { progress, cancelled ->
        FileOperations.move(
            entries, targetDir, conflicts, resolution,
            onProgress = { f, t, b, tb, cur ->
                progress(TransferProgress(0, OpKind.MOVE, TaskState.RUNNING, cur, f, t, b, tb))
            },
            isCancelled = cancelled
        )
    }.also { taskId -> attachCallback(taskId, onCompleted) }

    /** 入队删除任务。 */
    fun enqueueDelete(
        entries: List<com.freefile.vfs.VfsEntry>,
        onCompleted: (ok: Boolean, message: String) -> Unit = { _, _ -> }
    ): Long = enqueue(
        OpKind.DELETE,
        FreeFileApp.instance.getString(R.string.task_delete, entries.size)
    ) { progress, cancelled ->
        FileOperations.delete(
            entries,
            onProgress = { f, t, b, tb, cur ->
                progress(TransferProgress(0, OpKind.DELETE, TaskState.RUNNING, cur, f, t, b, tb))
            },
            isCancelled = cancelled
        )
    }.also { taskId -> attachCallback(taskId, onCompleted) }

    /** 入队解压任务（Sprint 3）。 */
    fun enqueueExtract(
        archivePath: String,
        targetDir: String,
        onCompleted: (ok: Boolean, message: String) -> Unit = { _, _ -> }
    ): Long = enqueue(
        OpKind.EXTRACT,
        FreeFileApp.instance.getString(
            R.string.task_extract, com.freefile.vfs.pathName(archivePath), targetDir
        )
    ) { progress, cancelled ->
        ArchiveExtractor.extract(
            archivePath, targetDir,
            onProgress = { f, t, b, tb, cur ->
                progress(TransferProgress(0, OpKind.EXTRACT, TaskState.RUNNING, cur, f, t, b, tb))
            },
            isCancelled = cancelled
        )
        0
    }.also { taskId -> attachCallback(taskId, onCompleted) }

    /** 入队 ZIP 压缩任务。 */
    fun enqueueCompress(
        entries: List<com.freefile.vfs.VfsEntry>,
        destination: String,
        options: CompressionEngine.Options = CompressionEngine.Options(format = CompressionEngine.Format.ZIP),
        onCompleted: (ok: Boolean, message: String) -> Unit = { _, _ -> }
    ): Long = enqueue(
        OpKind.COMPRESS,
        FreeFileApp.instance.getString(R.string.task_compress, entries.size, destination)
    ) { progress, cancelled ->
        CompressionEngine.compress(
            sources = entries.map { File(it.path) },
            destination = File(destination),
            options = options,
            onProgress = { p ->
                progress(
                    TransferProgress(
                        0, OpKind.COMPRESS, TaskState.RUNNING, p.currentFile,
                        p.filesDone, p.totalFiles, p.bytesDone, p.totalBytes
                    )
                )
            },
            isCancelled = cancelled
        )
        0
    }.also { taskId -> attachCallback(taskId, onCompleted) }

    /** 取消任务。 */
    fun cancel(taskId: Long) {
        jobs[taskId]?.cancel()
    }

    /** 清理已结束任务的展示。 */
    fun dismiss(taskId: Long) {
        _tasks.update { list -> list.filterNot { it.progress.taskId == taskId } }
    }

    fun dismissFinished() {
        _tasks.update { list ->
            list.filter { it.progress.state == TaskState.RUNNING }
        }
    }

    // ---------- 内部 ----------

    private fun attachCallback(taskId: Long, onCompleted: (ok: Boolean, message: String) -> Unit) {
        scope.launch {
            // 等待任务结束（简单轮询；任务量小可接受）
            while (true) {
                val t = _tasks.value.firstOrNull { it.progress.taskId == taskId } ?: break
                if (t.progress.state != TaskState.RUNNING) {
                    onCompleted(
                        t.progress.state == TaskState.DONE,
                        t.progress.error ?: t.progress.state.name
                    )
                    break
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private fun enqueue(
        kind: OpKind,
        title: String,
        block: suspend (progress: (TransferProgress) -> Unit, cancelled: () -> Boolean) -> Int
    ): Long {
        val taskId = idGen.getAndIncrement()
        val task = Task(
            progress = TransferProgress(taskId, kind, TaskState.RUNNING, "", 0, 0, 0, 0),
            kind = kind,
            title = title
        )
        _tasks.update { it + task }

        var activeJob: Job? = null
        val job = scope.launch {
            try {
                block(
                    { p -> patch(taskId) { it.copy(progress = p.copy(taskId = taskId)) } },
                    { activeJob?.isActive != true }
                )
                patch(taskId) {
                    it.copy(progress = it.progress.copy(state = TaskState.DONE, currentFile = ""))
                }
            } catch (e: CancellationException) {
                patch(taskId) {
                    it.copy(progress = it.progress.copy(state = TaskState.CANCELLED, error = null))
                }
            } catch (e: Exception) {
                patch(taskId) {
                    it.copy(
                        progress = it.progress.copy(
                            state = TaskState.FAILED,
                            error = e.message ?: ""
                        )
                    )
                }
            } finally {
                jobs.remove(taskId)
            }
        }
        activeJob = job
        jobs[taskId] = job
        return taskId
    }

    private fun patch(taskId: Long, transform: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.progress.taskId == taskId) transform(it) else it } }
    }
}
