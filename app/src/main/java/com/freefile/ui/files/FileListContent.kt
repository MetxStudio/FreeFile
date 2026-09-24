package com.freefile.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freefile.R
import com.freefile.util.Format
import com.freefile.vfs.VfsEntry

/** 按扩展名/类型挑选图标（与 FileTypes.categoryOf 对应）。 */
fun entryIcon(entry: VfsEntry): ImageVector = when (FileTypes.categoryOf(entry)) {
    FileTypes.IMAGE -> Icons.Rounded.Image
    FileTypes.VIDEO -> Icons.Rounded.Movie
    FileTypes.AUDIO -> Icons.Rounded.LibraryMusic
    FileTypes.ARCHIVE -> Icons.Rounded.Archive
    FileTypes.DOC -> Icons.Rounded.Description
    FileTypes.CODE -> Icons.Rounded.Code
    FileTypes.APK -> Icons.Rounded.Android
    else -> if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile
}

@Composable
fun entryIconTint(entry: VfsEntry): Color = when (FileTypes.categoryOf(entry)) {
    FileTypes.IMAGE -> Color(0xFF8BC34A)
    FileTypes.VIDEO -> Color(0xFFFF7043)
    FileTypes.AUDIO -> Color(0xFFAB47BC)
    FileTypes.ARCHIVE -> Color(0xFF8D6E63)
    FileTypes.APK -> Color(0xFF3DDC84)
    FileTypes.CODE -> Color(0xFF42A5F5)
    else -> if (entry.isDirectory) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 文件内容区：列表/网格/空态/加载态/错误态。
 * 需求 7：非根目录固定首项 ".."（上级目录），错误态/空态也显示。
 */
@Composable
fun FileListContent(
    state: FileViewModel.UiState,
    onOpen: (VfsEntry) -> Unit,
    onLongPress: (VfsEntry) -> Unit,
    onGoUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val handleLongPress: (VfsEntry) -> Unit = remember(onLongPress) {
        { entry ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongPress(entry)
        }
    }

    // ".." 仅在非根目录且非搜索结果模式显示
    // remote:// 位置根无上级，不显示 ".."
    val isRemoteRoot = state.path.startsWith("remote://") &&
        !state.path.removePrefix("remote://").trimEnd('/').contains('/')
    val showParentItem = state.path.isNotBlank() &&
        state.path.trimEnd('/') != "" &&
        state.path != "/" &&
        !state.searchMode &&
        !isRemoteRoot

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.error != null -> {
                // 错误态仍显示 ".."（需求 7：无权限也能返回上级）
                Column(modifier = Modifier.fillMaxSize()) {
                    if (showParentItem) ParentRow(onGoUp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(R.string.cannot_access),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            state.entries.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (showParentItem) ParentRow(onGoUp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            when {
                                state.searchQuery.isNotBlank() -> stringResource(R.string.no_search_result)
                                state.filterMode != FilterMode.ALL ||
                                    state.typeFilters.isNotEmpty() -> stringResource(R.string.no_filter_result)
                                else -> stringResource(R.string.empty_dir)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            state.viewMode == ViewMode.LIST -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (showParentItem) item(key = "..", contentType = { "parent" }) {
                        ParentRow(onGoUp)
                    }
                    itemsIndexed(
                        state.entries,
                        key = { _, e -> e.path },
                        contentType = { _, e -> FileTypes.categoryOf(e) }
                    ) { _, entry ->
                        FileRow(
                            entry = entry,
                            selected = state.selection.contains(entry.path),
                            showSizes = state.showSizes,
                            onOpen = onOpen,
                            onLongPress = handleLongPress
                        )
                    }
                }
            }
            else -> {
                // 大图标模式使用更大的 cell；普通网格用 96dp
                val cellMin = if (state.viewMode == ViewMode.LARGE_ICON) 144.dp else 96.dp
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = cellMin),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (showParentItem) item(key = "..", contentType = { "parent" }) {
                        ParentGridCell(onGoUp)
                    }
                    itemsIndexed(
                        state.entries,
                        key = { _, e -> e.path },
                        contentType = { _, e -> FileTypes.categoryOf(e) }
                    ) { _, entry ->
                        FileGridCell(
                            entry = entry,
                            selected = state.selection.contains(entry.path),
                            showSizes = state.showSizes,
                            onOpen = onOpen,
                            onLongPress = handleLongPress
                        )
                    }
                }
            }
        }
    }
}

/** ".." 上级条目（列表样式）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentRow(onGoUp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onGoUp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.ArrowUpward,
            contentDescription = stringResource(R.string.go_up),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.size(12.dp))
        Text(
            "..",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.go_up),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** ".." 上级条目（网格样式）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentGridCell(onGoUp: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onGoUp)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.go_up),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(44.dp)
            )
        }
        Text(
            "..",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: VfsEntry,
    selected: Boolean,
    showSizes: Boolean,
    onOpen: (VfsEntry) -> Unit,
    onLongPress: (VfsEntry) -> Unit
) {
    val icon = remember(entry.path) { entryIcon(entry) }
    val folderLabel = stringResource(R.string.folder_label)
    val subtitle = remember(entry.path, entry.size, entry.lastModified, showSizes, folderLabel) {
        // 需求 8：仅当设置开启"计算文件大小"时才显示大小；目录只显示类型
        when {
            entry.isDirectory -> folderLabel
            showSizes -> "${Format.size(entry.size)} · ${Format.time(entry.lastModified)}"
            else -> Format.time(entry.lastModified)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = { onOpen(entry) },
                onLongClick = { onLongPress(entry) }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = entryIconTint(entry),
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (entry.isHidden) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(22.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    entry: VfsEntry,
    selected: Boolean,
    showSizes: Boolean,
    onOpen: (VfsEntry) -> Unit,
    onLongPress: (VfsEntry) -> Unit
) {
    val icon = remember(entry.path) { entryIcon(entry) }
    val sizeText = remember(entry.path, entry.size, showSizes) {
        if (entry.isDirectory || !showSizes) "" else Format.size(entry.size)
    }

    Column(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = { onOpen(entry) },
                onLongClick = { onLongPress(entry) }
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = entryIconTint(entry),
                modifier = Modifier.size(44.dp)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                )
            }
        }
        Text(
            text = entry.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (sizeText.isNotEmpty()) {
            Text(
                text = sizeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
