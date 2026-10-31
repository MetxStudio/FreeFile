package com.freefile.operations

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局文件变更事件总线。
 *
 * 任何创建/修改/删除文件的操作（压缩、解压、加密、解密、复制、移动、删除等）
 * 在完成后调用 [notify] 广播受影响的目录路径；UI（MainScreen）收集此流并刷新
 * 正在展示该目录的窗格，确保新文件立即可见。
 *
 * 使用 SharedFlow（非 StateFlow）：同一目录的多次变更都应触发刷新，
 * 且无需保留"最后一次"状态。
 */
object FileChangeBus {

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    /** 通知某个目录下的文件发生了变化，需要刷新。 */
    fun notify(dirPath: String) {
        if (dirPath.isNotBlank()) _changes.tryEmit(dirPath)
    }
}
