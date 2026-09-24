package com.freefile.ui.core

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.freefile.ui.files.FileListContent
import com.freefile.ui.files.FileViewModel
import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.VfsEntry

/**
 * 单个文件窗格（纯列表视图）。
 * - 触摸即激活：手指落在哪个窗格（含滑动浏览），哪个就是活动窗格
 * - 顶部 3dp 强调条标识活动状态
 * - 导航/搜索/过滤/排序/操作全部集中在 MainScreen 的统一顶栏与底栏（需求 1）
 */
@Composable
fun FilePane(
    vm: FileViewModel,
    isActive: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFile: (VfsEntry) -> Unit = {},
    onOpenArchive: (VfsEntry) -> Unit = {}
) {
    val ui by vm.ui.collectAsState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 触摸即激活
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onActivate()
                    }
                }
        ) {
            // 活动窗格强调条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
            )

            // ---------- 文件列表 ----------
            FileListContent(
                state = ui,
                onOpen = { entry ->
                    when {
                        // 多选模式：单击切换选中
                        ui.selection.isNotEmpty() -> vm.toggleSelect(entry.path)
                        entry.isDirectory -> vm.open(entry.path)
                        // 压缩文件 → 进入压缩包浏览（Sprint 3）
                        ArchiveEngine.isArchiveFile(entry.name) ->
                            vm.open(ArchiveEngine.buildPath(entry.path, ""))
                        else -> {
                            vm.recordRecentFile(entry.path)
                            onOpenFile(entry)
                        }
                    }
                },
                onLongPress = { entry ->
                    when {
                        // 已有选中项 → 长按 = 范围选择（锚点到此项）
                        ui.selection.isNotEmpty() -> vm.selectRangeTo(entry.path)
                        else -> vm.toggleSelect(entry.path)
                    }
                },
                onGoUp = { vm.openParent() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
