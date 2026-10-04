package com.freefile.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.freefile.R
import com.freefile.operations.Conflict
import com.freefile.tools.unzip.CompressionEngine
import com.freefile.operations.ConflictAction
import com.freefile.operations.ConflictResolution
import com.freefile.operations.FileOperations
import com.freefile.operations.TransferProgress
import com.freefile.search.SizeOp
import com.freefile.ui.files.entryIcon
import com.freefile.ui.files.entryIconTint
import com.freefile.util.Format
import com.freefile.vfs.VfsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserAgreementDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.user_agreement_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.user_agreement_content))
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.user_agreement_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.user_agreement_later))
            }
        }
    )
}

// ==================== 通用文本输入 ====================

/** 通用文本输入对话框：新建/重命名共用。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmText: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text(confirmText ?: stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ==================== 新建节点（文件/文件夹选择） ====================

/**
 * 新建对话框：选择类型（文件/文件夹）+ 输入名称。
 * 满足需求：将"新建文件夹"改为"新建"，点击后弹出输入框让用户选择新建文件或文件夹。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewNodeDialog(
    onConfirm: (isDir: Boolean, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var isDir by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_new_node_title)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isDir,
                        onClick = { isDir = true },
                        label = { Text(stringResource(R.string.new_folder)) }
                    )
                    FilterChip(
                        selected = !isDir,
                        onClick = { isDir = false },
                        label = { Text(stringResource(R.string.new_file)) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = {
                        Text(
                            if (isDir) stringResource(R.string.dlg_new_folder_label)
                            else stringResource(R.string.dlg_new_file_label)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(isDir, name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ==================== 删除确认 ====================

/** 删除确认：默认移入回收站（可恢复），可选永久删除。 */
@Composable
fun ConfirmDeleteDialog(
    entries: List<VfsEntry>,
    onTrash: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entries.size == 1) stringResource(R.string.dlg_delete_name, entries.first().name) else stringResource(R.string.dlg_delete_count, entries.size)) },
        text = {
            Text(
                if (entries.any { it.isDirectory })
                    stringResource(R.string.dlg_delete_desc_dir)
                else stringResource(R.string.dlg_delete_desc)
            )
        },
        confirmButton = {
            TextButton(onClick = onTrash) { Text(stringResource(R.string.dlg_delete_to_trash)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.dlg_delete_permanent), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

// ==================== 文件属性 ====================

/** 属性对话框：基本信息 + 按需计算 MD5/SHA-256。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesDialog(
    entry: VfsEntry,
    entryCount: Pair<Int, Int>? = null, // 目录: (文件数, 子目录数)
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 使用 entry.path 作为 key，防止对话框重建时丢失哈希结果（解决问题 1：结果一闪而逝）
    var md5 by remember(entry.path) { mutableStateOf<String?>(null) }
    var sha256 by remember(entry.path) { mutableStateOf<String?>(null) }
    var computing by remember(entry.path) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_properties_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PropertyRow(stringResource(R.string.prop_name), entry.name)
                PropertyRow(stringResource(R.string.prop_path), entry.path)
                PropertyRow(stringResource(R.string.prop_type), if (entry.isDirectory) stringResource(R.string.folder_label) else entry.extension.ifEmpty { stringResource(R.string.file_label) })
                PropertyRow(
                    stringResource(R.string.prop_size),
                    if (entry.isDirectory) entryCount?.let { stringResource(R.string.prop_dir_stats, it.first, it.second) } ?: stringResource(R.string.computing)
                    else Format.size(entry.size)
                )
                PropertyRow(stringResource(R.string.prop_modified), Format.time(entry.lastModified))
                if (!entry.isDirectory) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.hash_label), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(12.dp))
                        if (computing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.computing),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            TextButton(onClick = {
                                computing = true
                                scope.launch(Dispatchers.IO) {
                                    val newMd5 = try {
                                        FileOperations.hash(entry.path, "MD5")
                                    } catch (e: Throwable) {
                                        context.getString(R.string.hash_failed)
                                    }
                                    val newSha = try {
                                        FileOperations.hash(entry.path, "SHA-256")
                                    } catch (e: Throwable) {
                                        context.getString(R.string.hash_failed)
                                    }
                                    withContext(Dispatchers.Main) {
                                        md5 = newMd5
                                        sha256 = newSha
                                        computing = false
                                    }
                                }
                            }) { Text(stringResource(R.string.compute_hash)) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    md5?.let {
                        val ctx = LocalContext.current
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "MD5",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(70.dp)
                            )
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MD5", it))
                                android.widget.Toast.makeText(ctx, R.string.hash_copied, android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.copy)) }
                        }
                    }
                    sha256?.let {
                        val ctx = LocalContext.current
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "SHA-256",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(70.dp)
                            )
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 3,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", it))
                                android.widget.Toast.makeText(ctx, R.string.hash_copied, android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.copy)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== 批量重命名 ====================

private enum class BatchMode { NUMBERED, REPLACE, AFFIX }

/** 批量重命名：序号 / 查找替换 / 前后缀，实时预览。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BatchRenameDialog(
    entries: List<VfsEntry>,
    onApply: (List<Pair<VfsEntry, String>>) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(BatchMode.NUMBERED) }
    var template by remember { mutableStateOf("file_") }
    var startIndex by remember { mutableStateOf("1") }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }

    val preview: List<Pair<VfsEntry, String>> = remember(mode, template, startIndex, findText, replaceText, prefix, suffix, entries) {
        entries.mapIndexed { index, e ->
            val dot = e.name.lastIndexOf('.')
            val base = if (dot > 0) e.name.substring(0, dot) else e.name
            val ext = if (dot > 0) e.name.substring(dot) else ""
            val newName = when (mode) {
                BatchMode.NUMBERED -> {
                    val n = (startIndex.toIntOrNull() ?: 1) + index
                    "$template${n.toString().padStart(3, '0')}$ext"
                }
                BatchMode.REPLACE ->
                    if (findText.isEmpty()) e.name
                    else e.name.replace(findText, replaceText)
                BatchMode.AFFIX -> "$prefix$base$suffix$ext"
            }
            e to newName
        }.filter { (e, n) -> n != e.name }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_batch_rename_count, entries.size)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == BatchMode.NUMBERED,
                        onClick = { mode = BatchMode.NUMBERED },
                        label = { Text(stringResource(R.string.br_numbering)) }
                    )
                    FilterChip(
                        selected = mode == BatchMode.REPLACE,
                        onClick = { mode = BatchMode.REPLACE },
                        label = { Text(stringResource(R.string.br_replace)) }
                    )
                    FilterChip(
                        selected = mode == BatchMode.AFFIX,
                        onClick = { mode = BatchMode.AFFIX },
                        label = { Text(stringResource(R.string.br_affix)) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (mode) {
                    BatchMode.NUMBERED -> {
                        OutlinedTextField(
                            value = template, onValueChange = { template = it },
                            label = { Text(stringResource(R.string.br_template)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = startIndex, onValueChange = { startIndex = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.br_start)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BatchMode.REPLACE -> {
                        OutlinedTextField(
                            value = findText, onValueChange = { findText = it },
                            label = { Text(stringResource(R.string.br_find)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = replaceText, onValueChange = { replaceText = it },
                            label = { Text(stringResource(R.string.br_to)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BatchMode.AFFIX -> {
                        OutlinedTextField(
                            value = prefix, onValueChange = { prefix = it },
                            label = { Text(stringResource(R.string.br_prefix)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = suffix, onValueChange = { suffix = it },
                            label = { Text(stringResource(R.string.br_suffix)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.br_preview, preview.size), style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(preview) { (e, n) ->
                        Text(
                            "${e.name} → $n",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(preview) },
                enabled = preview.isNotEmpty()
            ) { Text(stringResource(R.string.dlg_batch_rename_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ==================== 目录跳转 ====================

/** 目录跳转：直接输入路径打开。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JumpToPathDialog(
    initialPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_jump_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.dlg_jump_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.dlg_jump_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ==================== 搜索设置 Dialog ====================

/**
 * 搜索设置对话框（新规格）。
 *
 * 基础区域：标题"搜索"、文件名输入框（提示"文件名通配符表达式 (* 和 ?)"，默认 .db）。
 * 基础选项：复选框"搜索子目录"（默认勾选）、"高级搜索"（默认未勾选）。
 * 高级搜索区域（条件渲染，勾选"高级搜索"时展开）：
 *  - 按文件大小过滤（≥/≤/>/</= 操作符 + 数值+单位）
 *  - 按修改时间过滤（任意时间/指定时间段，日期选择器）
 *  - 文件中包含内容（文本输入框）
 *  - 区分大小写（复选框，默认未勾选）
 *  - 正则表达式（复选框，默认未勾选；勾选后输入框按正则解析）
 *
 * 条件之间为 AND 关系。点击"确定"触发搜索，"取消"关闭并清空条件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSettingsDialog(
    initialQuery: String,
    initialOptions: com.freefile.search.SearchOptions,
    indexEnabled: Boolean,
    indexState: com.freefile.search.IndexState,
    onBuildIndex: () -> Unit,
    onConfirm: (query: String, opts: com.freefile.search.SearchOptions) -> Unit,
    onDismiss: () -> Unit
) {
    // 基础
    var query by remember { mutableStateOf(initialQuery.ifBlank { ".db" }) }
    var searchSubDirs by remember { mutableStateOf(initialOptions.searchDepth != 0) }
    var advanced by remember { mutableStateOf(initialOptions.advanced) }
    // 高级
    var regex by remember { mutableStateOf(initialOptions.regex) }
    var caseSensitive by remember { mutableStateOf(initialOptions.caseSensitive) }
    var sizeOp by remember { mutableStateOf(initialOptions.sizeOp) }
    var sizeValue by remember { mutableStateOf(
        if (initialOptions.sizeBytes > 0) (initialOptions.sizeBytes / 1024).toString() else "512"
    ) }
    var sizeUnit by remember { mutableStateOf("KB") } // B/KB/MB/GB
    var timeFrom by remember { mutableStateOf(initialOptions.timeFrom) }
    var timeTo by remember { mutableStateOf(initialOptions.timeTo) }
    var contentPattern by remember { mutableStateOf(initialOptions.contentPattern) }
    var useIndex by remember { mutableStateOf(initialOptions.useIndex) }
    var showTimeFromPicker by remember { mutableStateOf(false) }
    var showTimeToPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_settings_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ---- 基础区域：文件名通配符输入框 ----
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_query_label)) },
                    placeholder = { Text(stringResource(R.string.search_query_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                // ---- 基础选项 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = searchSubDirs,
                        onCheckedChange = { searchSubDirs = it }
                    )
                    Text(
                        stringResource(R.string.search_subdirs),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = advanced,
                        onCheckedChange = { advanced = it }
                    )
                    Text(
                        stringResource(R.string.search_advanced),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // ---- 高级搜索区域（条件渲染） ----
                if (advanced) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // 大小过滤
                    Text(
                        stringResource(R.string.search_size_filter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = sizeOp == SizeOp.NONE,
                            onClick = { sizeOp = SizeOp.NONE },
                            label = { Text(stringResource(R.string.search_size_any)) }
                        )
                        listOf(
                            SizeOp.GE to stringResource(R.string.search_size_ge),
                            SizeOp.LE to stringResource(R.string.search_size_le),
                            SizeOp.GT to stringResource(R.string.search_size_gt),
                            SizeOp.LT to stringResource(R.string.search_size_lt),
                            SizeOp.EQ to stringResource(R.string.search_size_eq)
                        ).forEach { (op, label) ->
                            FilterChip(
                                selected = sizeOp == op,
                                onClick = { sizeOp = op },
                                label = { Text(label) }
                            )
                        }
                    }
                    if (sizeOp != SizeOp.NONE) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = sizeValue,
                                onValueChange = { sizeValue = it.filter { ch -> ch.isDigit() } },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            listOf("B", "KB", "MB", "GB").forEach { u ->
                                FilterChip(
                                    selected = sizeUnit == u,
                                    onClick = { sizeUnit = u },
                                    label = { Text(u) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    // 修改时间过滤
                    Text(
                        stringResource(R.string.search_time_filter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (timeFrom > 0) timeFrom = 0 else showTimeFromPicker = true
                            }
                        ) {
                            Text(
                                if (timeFrom > 0) Format.time(timeFrom)
                                else stringResource(R.string.search_time_any)
                            )
                        }
                        Text("—", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = {
                                if (timeTo > 0) timeTo = 0 else showTimeToPicker = true
                            }
                        ) {
                            Text(
                                if (timeTo > 0) Format.time(timeTo)
                                else stringResource(R.string.search_time_any)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    // 文件内容
                    OutlinedTextField(
                        value = contentPattern,
                        onValueChange = { contentPattern = it },
                        label = { Text(stringResource(R.string.search_content_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    // 区分大小写 + 正则表达式
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = caseSensitive,
                            onCheckedChange = { caseSensitive = it }
                        )
                        Text(
                            stringResource(R.string.search_case_sensitive),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(16.dp))
                        Checkbox(
                            checked = regex,
                            onCheckedChange = { regex = it }
                        )
                        Text(
                            stringResource(R.string.search_use_regex),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // 使用索引（仅高级模式下可选；索引未就绪时提示）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useIndex,
                            onCheckedChange = { useIndex = it }
                        )
                        Text(
                            stringResource(R.string.search_use_index),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                indexState is com.freefile.search.IndexState.Building ->
                                    stringResource(R.string.search_index_building)
                                indexState is com.freefile.search.IndexState.Ready ->
                                    stringResource(R.string.search_index_ready_hint)
                                else -> stringResource(R.string.search_index_not_ready)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    // 本次搜索的索引状态提示
                    Text(
                        when {
                            !useIndex ->
                                stringResource(R.string.search_live_hint)
                            contentPattern.isNotBlank() ->
                                stringResource(R.string.search_index_content_fallback)
                            indexState is com.freefile.search.IndexState.Ready ->
                                stringResource(R.string.search_index_hint)
                            indexState is com.freefile.search.IndexState.Building ->
                                stringResource(R.string.search_index_building)
                            else -> stringResource(R.string.search_live_hint)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (indexEnabled && indexState !is com.freefile.search.IndexState.Building) {
                        TextButton(onClick = onBuildIndex) {
                            Text(stringResource(R.string.search_index_build))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 计算大小字节数
                    val szNum = sizeValue.toLongOrNull() ?: 0L
                    val szBytes = when (sizeUnit) {
                        "B" -> szNum
                        "KB" -> szNum * 1024
                        "MB" -> szNum * 1024 * 1024
                        "GB" -> szNum * 1024 * 1024 * 1024
                        else -> szNum
                    }
                    onConfirm(
                        query,
                        com.freefile.search.SearchOptions(
                            searchDepth = if (searchSubDirs) -1 else 0,
                            advanced = advanced,
                            regex = regex,
                            caseSensitive = caseSensitive,
                            sizeOp = sizeOp,
                            sizeBytes = szBytes,
                            timeFrom = timeFrom,
                            timeTo = timeTo,
                            contentPattern = contentPattern,
                            useIndex = useIndex
                        )
                    )
                },
                enabled = query.isNotBlank()
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )

    // 日期选择器：起始（使用 Android 原生 DatePickerDialog，无 Compose 版本依赖）
    if (showTimeFromPicker) {
        val context = LocalContext.current
        val cal = java.util.Calendar.getInstance().apply {
            if (timeFrom > 0) timeInMillis = timeFrom
        }
        LaunchedEffect(showTimeFromPicker) {
            android.app.DatePickerDialog(
                context,
                { _, year, month, day ->
                    val c = java.util.Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    timeFrom = c.timeInMillis
                    showTimeFromPicker = false
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).apply {
                setOnCancelListener { showTimeFromPicker = false }
                show()
            }
        }
    }
    // 日期选择器：结束
    if (showTimeToPicker) {
        val context = LocalContext.current
        val cal = java.util.Calendar.getInstance().apply {
            if (timeTo > 0) timeInMillis = timeTo
        }
        LaunchedEffect(showTimeToPicker) {
            android.app.DatePickerDialog(
                context,
                { _, year, month, day ->
                    val c = java.util.Calendar.getInstance().apply {
                        set(year, month, day, 23, 59, 59)
                        set(java.util.Calendar.MILLISECOND, 999)
                    }
                    timeTo = c.timeInMillis
                    showTimeToPicker = false
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).apply {
                setOnCancelListener { showTimeToPicker = false }
                show()
            }
        }
    }
}

/** 简易水平分隔线（避免引入额外 import）。 */
@Composable
private fun HorizontalDivider() {
    androidx.compose.material3.Divider(
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

// ==================== 解压确认 ====================

/** 解压确认：展示并允许修改目标目录（默认压缩包同级同名文件夹），可选输入密码。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConfirmExtractDialog(
    archiveName: String,
    defaultTargetDir: String,
    onConfirm: (targetDir: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(defaultTargetDir) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_extract_name, archiveName)) },
        text = {
            Column {
                Text(stringResource(R.string.dlg_extract_to), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dlg_extract_target_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dlg_extract_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim(), password) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.dlg_extract_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** 创建压缩包：选择格式、压缩级别、密码（仅 ZIP）、分卷大小（仅 ZIP）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressDialog(
    count: Int,
    defaultDestination: String,
    onConfirm: (
        destination: String,
        format: CompressionEngine.Format,
        level: CompressionEngine.Level,
        password: String,
        splitMb: Long
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(defaultDestination) }
    var password by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(CompressionEngine.Level.NORMAL) }
    var format by remember { mutableStateOf(CompressionEngine.Format.ZIP) }
    var splitMbText by remember { mutableStateOf("0") }
    var formatExpanded by remember { mutableStateOf(false) }
    var levelExpanded by remember { mutableStateOf(false) }

    val isZip = format == CompressionEngine.Format.ZIP

    // 切换格式时同步更新目标路径扩展名
    fun updateExtension(newFormat: CompressionEngine.Format) {
        val path = text
        val dotIdx = path.lastIndexOf('.')
        val base = if (dotIdx > 0) path.substring(0, dotIdx) else path
        text = "$base.${newFormat.extension}"
    }

    val splitMb = splitMbText.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_compress_name, count)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dlg_compress_target_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                // 格式下拉
                Text(stringResource(R.string.dlg_compress_format_label), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = it }
                ) {
                    OutlinedTextField(
                        value = format.name,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        CompressionEngine.Format.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name) },
                                onClick = {
                                    if (candidate != format) {
                                        format = candidate
                                        updateExtension(candidate)
                                    }
                                    formatExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 压缩级别下拉
                Text(stringResource(R.string.dlg_compress_level_label), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                ExposedDropdownMenuBox(
                    expanded = levelExpanded,
                    onExpandedChange = { levelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = level.name,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = levelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = levelExpanded,
                        onDismissRequest = { levelExpanded = false }
                    ) {
                        CompressionEngine.Level.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name) },
                                onClick = { level = candidate; levelExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 密码（仅 ZIP）
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dlg_compress_password_label)) },
                    singleLine = true,
                    enabled = isZip,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isZip) {
                    Text(
                        stringResource(R.string.dlg_compress_password_zip_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 分卷大小（仅 ZIP）
                OutlinedTextField(
                    value = splitMbText,
                    onValueChange = { if (it.all { c -> c.isDigit() }) splitMbText = it },
                    label = { Text(stringResource(R.string.dlg_compress_split_label)) },
                    singleLine = true,
                    enabled = isZip,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.dlg_compress_split_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(
                            text.trim(),
                            format,
                            level,
                            password,
                            splitMb
                        )
                    }
                },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.dlg_compress_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ConfirmOverwriteDialog(
    path: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_compress_overwrite_title)) },
        text = { Text(stringResource(R.string.dlg_compress_overwrite_message, path)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.dlg_compress_overwrite_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ==================== 冲突处理 ====================

/** 冲突对话框：全局策略 + 逐条决策。 */
@Composable
fun ConflictDialog(
    kindLabel: String,
    conflicts: List<Conflict>,
    onResolve: (ConflictResolution) -> Unit,
    onDismiss: () -> Unit
) {
    val decisions = remember { androidx.compose.runtime.mutableStateMapOf<String, ConflictAction>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_detected, conflicts.size)) },
        text = {
            Column {
                Text(stringResource(R.string.conflict_desc, kindLabel))
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(conflicts.take(10)) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                entryIcon(c.source),
                                contentDescription = null,
                                tint = entryIconTint(c.source),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                c.source.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            listOf(
                                ConflictAction.OVERWRITE to stringResource(R.string.action_overwrite),
                                ConflictAction.SKIP to stringResource(R.string.action_skip),
                                ConflictAction.RENAME to stringResource(R.string.action_rename)
                            ).forEach { (action, label) ->
                                val selected = decisions[c.source.path] == action
                                Text(
                                    label,
                                    modifier = Modifier
                                        .clickable { decisions[c.source.path] = action }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (conflicts.size > 10) {
                        item { Text(stringResource(R.string.conflict_more, conflicts.size), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onResolve(ConflictResolution.OverwriteAll) }) { Text(stringResource(R.string.resolve_overwrite_all)) }
                TextButton(onClick = { onResolve(ConflictResolution.RenameAll) }) { Text(stringResource(R.string.resolve_rename_all)) }
                TextButton(onClick = {
                    onResolve(ConflictResolution.PerFile(decisions.toMap()))
                }) { Text(stringResource(R.string.resolve_selected)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onResolve(ConflictResolution.SkipAll) }) { Text(stringResource(R.string.resolve_skip_all)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

// ==================== 任务进度 ====================

/** 底部任务进度卡片：显示当前任务进度，支持取消。 */
@Composable
fun TaskProgressCard(
    task: com.freefile.operations.TransferManager.Task,
    onCancel: (Long) -> Unit,
    onDismiss: (Long) -> Unit
) {
    val p = task.progress
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                when (p.state) {
                    com.freefile.operations.TaskState.RUNNING -> IconButton(
                        onClick = { onCancel(p.taskId) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(16.dp))
                    }
                    else -> IconButton(
                        onClick = { onDismiss(p.taskId) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            when (p.state) {
                com.freefile.operations.TaskState.RUNNING -> {
                    if (p.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = p.percent / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${p.filesDone}/${p.totalFiles} · ${Format.size(p.bytesDone)}/${Format.size(p.totalBytes)} · ${p.currentFile.substringAfterLast('/')}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "${p.filesDone}/${p.totalFiles} · ${p.currentFile.substringAfterLast('/')}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                com.freefile.operations.TaskState.DONE -> Text(
                    stringResource(R.string.status_done),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
                com.freefile.operations.TaskState.CANCELLED -> Text(
                    stringResource(R.string.status_cancelled),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
                com.freefile.operations.TaskState.FAILED -> Text(
                    stringResource(R.string.status_failed, p.error ?: "-"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2
                )
            }
        }
    }
}
