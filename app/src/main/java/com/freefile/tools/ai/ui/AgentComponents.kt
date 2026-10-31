package com.freefile.tools.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.freefile.R
import com.freefile.tools.ai.data.AgentMessageEntity
import com.freefile.tools.ai.model.CallStatus
import com.freefile.tools.ai.model.ToolProgress
import com.freefile.tools.ai.model.WritePreview
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------- 格式化

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
fun formatTime(ts: Long): String =
    if (ts <= 0) "" else timeFmt.format(Date(ts))

// ------------------------------------------------------------- 消息气泡

@Composable
fun ChatMessageItem(msg: AgentMessageEntity, showDetails: Boolean, showThinking: Boolean = true) {
    when (msg.role) {
        "user" -> Bubble(
            alignment = Alignment.End,
            container = MaterialTheme.colorScheme.primaryContainer,
            textColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            SelectionContainer {
                Text(msg.content, style = MaterialTheme.typography.bodyMedium)
            }
        }

        "tool" -> if (showDetails) ToolResultCard(msg)

        else -> SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.Start
            ) {
                val (think, visible) = remember(msg.content) {
                    com.freefile.tools.ai.model.ThinkText.split(msg.content)
                }
                if (think.isNotEmpty() && showThinking) ThinkingBlock(think)
                if (visible.isNotEmpty()) {
                    Bubble(
                        alignment = Alignment.Start,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        MarkdownText(
                            markdown = visible,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                if (msg.toolCallsJson.isNotEmpty()) ToolCallsChipRow(msg.toolCallsJson)
            }
        }
    }
}

/**
 * 可折叠灰色深度思考块。
 * 注意：展开状态不能用文本内容做 remember key —— 流式输出时文本每 60ms 变化一次，
 * 会把用户刚点开的展开状态反复重置为折叠。
 */
@Composable
fun ThinkingBlock(text: String, expandedDefault: Boolean = false) {
    var expanded by remember { mutableStateOf(expandedDefault) }
    Column(
        Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 2.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            if (expanded) "▼ 深度思考" else "▶ 深度思考",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * 纵向滚动条：按可见项高度估算内容总高，显示可拖动的滑块。
 * 长对话下提供进度感知，并可按住滑块快速拖动定位。
 */
@Composable
fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thickness: Dp = 5.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    if (total <= 0 || visible.isEmpty() || viewport <= 0) return
    val avgItem = visible.sumOf { it.size }.toFloat() / visible.size
    val contentHeight = (avgItem * total).coerceAtLeast(viewport.toFloat())
    if (contentHeight <= viewport + 1f) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val thumbFraction = (viewport / contentHeight).coerceIn(0.06f, 1f)
    val maxScroll = (contentHeight - viewport).coerceAtLeast(1f)
    val first = visible.first()
    val scrolled = (first.index * avgItem - first.offset).coerceIn(0f, maxScroll)
    val ratio = (scrolled / maxScroll).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier
            .width(thickness)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor)
    ) {
        val trackPx = with(density) { maxHeight.toPx() }
        val minThumbPx = with(density) { 28.dp.toPx() }
        val thumbPx = (trackPx * thumbFraction).coerceAtLeast(minThumbPx)
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        Box(
            Modifier
                .width(thickness)
                .height(with(density) { thumbPx.toDp() })
                .offset(y = with(density) { (travel * ratio).toDp() })
                .background(thumbColor, RoundedCornerShape(percent = 50))
                .pointerInput(state) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val i = state.layoutInfo
                        val vp = (i.viewportEndOffset - i.viewportStartOffset).coerceAtLeast(1)
                        val vis = i.visibleItemsInfo
                        if (vis.isEmpty()) return@detectVerticalDragGestures
                        val avg = vis.sumOf { it.size }.toFloat() / vis.size
                        val content = (avg * i.totalItemsCount).coerceAtLeast(vp.toFloat())
                        val th = (trackPx * (vp.toFloat() / content)).coerceAtLeast(minThumbPx)
                        val tv = (trackPx - th).coerceAtLeast(1f)
                        val factor = ((content - vp) / tv).coerceAtLeast(0.1f)
                        scope.launch { state.scrollBy(dragAmount * factor) }
                    }
                }
        )
    }
}

@Composable
private fun Bubble(
    alignment: Alignment.Horizontal,
    container: Color,
    textColor: Color,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = container, contentColor = textColor),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCallsChipRow(toolCallsJson: String) {
    val names = remember(toolCallsJson) {
        runCatching {
            val arr = org.json.JSONArray(toolCallsJson)
            (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") }
        }.getOrDefault(emptyList())
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEach { n ->
            AssistChip(
                onClick = {},
                enabled = false,
                leadingIcon = {
                    Icon(Icons.Rounded.Build, null, Modifier.size(14.dp))
                },
                label = { Text(n, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun ToolResultCard(msg: AgentMessageEntity) {
    val parsed = remember(msg.content) { runCatching { JSONObject(msg.content) }.getOrNull() }
    val status = CallStatus.from(msg.callStatus)
    val (statusIcon, tint) = when (status) {
        CallStatus.SUCCESS -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        CallStatus.AWAITING, CallStatus.RUNNING -> Icons.Rounded.Schedule to MaterialTheme.colorScheme.tertiary
        CallStatus.CANCELLED -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.outline
        CallStatus.UNSUPPORTED -> Icons.Rounded.Error to MaterialTheme.colorScheme.outline
        CallStatus.FAILED -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(0.92f).padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    msg.toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                val message = parsed?.optString("message").orEmpty()
                if (message.isNotEmpty()) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
                val code = parsed?.optString("error_code").orEmpty()
                if (code.isNotEmpty() && code != "null") {
                    Text(
                        code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------- 工具进度

@Composable
fun ProgressStrip(progress: ToolProgress?) {
    if (progress == null) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${progress.toolName} · ${progress.filesDone}/${progress.totalFiles.coerceAtLeast(progress.filesDone)}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (progress.current.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    progress.current,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))
            val pct = if (progress.totalBytes > 0)
                (progress.bytesDone.toFloat() / progress.totalBytes).coerceIn(0f, 1f) else 0f
            if (pct > 0f) LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------- 确认卡

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmPlanCard(
    preview: WritePreview,
    onApprove: (selected: List<String>, destination: String, conflict: String, overwrite: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val selected = remember(preview.token) {
        mutableStateOf(preview.items.filter { it.selected }.map { it.path }.toSet())
    }
    var destination by remember(preview.token) { mutableStateOf(preview.destination) }
    var conflict by remember(preview.token) { mutableStateOf(preview.conflictPolicy) }
    var overwrite by remember(preview.token) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(preview.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // 全选 / 反选 / 前 N 个
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    selected.value = preview.items.map { it.path }.toSet()
                }) { Text(stringResource(R.string.ai_select_all)) }
                TextButton(onClick = {
                    selected.value = preview.items.map { it.path }.toSet() - selected.value
                }) { Text(stringResource(R.string.ai_invert_selection)) }
                listOf(5, 10).forEach { n ->
                    TextButton(onClick = {
                        selected.value = preview.items.take(n).map { it.path }.toSet()
                    }) { Text(stringResource(R.string.ai_first_n, n)) }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
            ) {
                items(preview.items, key = { it.path }) { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected.value.contains(item.path),
                            onCheckedChange = { checked ->
                                selected.value = if (checked) selected.value + item.path
                                else selected.value - item.path
                            }
                        )
                        Icon(
                            if (item.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                item.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                        if (!item.isDirectory && item.size > 0) {
                            Text(
                                formatSize(item.size),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            if (preview.destination.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    preview.destinationLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    enabled = preview.destinationEditable,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    preview.destinationLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (preview.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ai_conflict_policy),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConflictRadio(WritePreview.CONFLICT_RENAME, conflict) { conflict = it }
                    ConflictRadio(WritePreview.CONFLICT_SKIP, conflict) { conflict = it }
                    ConflictRadio(WritePreview.CONFLICT_OVERWRITE, conflict) { conflict = it }
                }
                if (conflict == WritePreview.CONFLICT_OVERWRITE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                        Text(
                            stringResource(R.string.ai_overwrite_warn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 警示行
            val warnings = buildList {
                if (preview.destructive) add(stringResource(R.string.ai_delete_warn))
                if (preview.bulk) add(
                    stringResource(
                        R.string.ai_bulk_warn,
                        preview.items.size,
                        com.freefile.tools.ai.data.AgentSettings.state.value.batchThreshold
                    )
                )
                if (preview.privileged) {
                    add(
                        if (preview.toolName == "run_plugin_action") stringResource(R.string.ai_external_warn)
                        else stringResource(R.string.ai_privileged_warn)
                    )
                }
                if (!preview.undoable) add(stringResource(R.string.ai_undo_unavailable))
            }
            warnings.forEach { w ->
                Text(
                    "⚠ $w",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_confirm_cancel))
                }
                Button(
                    onClick = {
                        onApprove(
                            selected.value.toList(),
                            destination.trim(),
                            conflict,
                            overwrite
                        )
                    },
                    enabled = selected.value.isNotEmpty() &&
                        (conflict != WritePreview.CONFLICT_OVERWRITE || overwrite),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ai_confirm_execute))
                }
            }
        }
    }
}

@Composable
private fun ConflictRadio(value: String, current: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current == value, onClick = { onSelect(value) })
        Text(
            when (value) {
                WritePreview.CONFLICT_SKIP -> stringResource(R.string.ai_conflict_skip)
                WritePreview.CONFLICT_OVERWRITE -> stringResource(R.string.ai_conflict_overwrite)
                else -> stringResource(R.string.ai_conflict_rename)
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ------------------------------------------------------------- 引导弹窗

@Composable
fun OnboardingDialog(onAccept: () -> Unit, onGoSetup: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.ai_onboard_later)) }
        },
        dismissButton = {
            TextButton(onClick = onGoSetup) { Text(stringResource(R.string.ai_onboard_go_setup)) }
        },
        title = { Text(stringResource(R.string.ai_onboard_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ai_onboard_intro), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ai_onboard_capabilities),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
                listOf(
                    R.string.ai_onboard_capability_1,
                    R.string.ai_onboard_capability_2,
                    R.string.ai_onboard_capability_3
                ).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ai_onboard_privacy),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
                listOf(
                    R.string.ai_onboard_privacy_1,
                    R.string.ai_onboard_privacy_2,
                    R.string.ai_onboard_privacy_3
                ).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

// ------------------------------------------------------------- 模式切换

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeTabs(current: com.freefile.tools.ai.model.AgentMode, onSelect: (com.freefile.tools.ai.model.AgentMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.freefile.tools.ai.model.AgentMode.entries.forEach { m ->
            val label = when (m) {
                com.freefile.tools.ai.model.AgentMode.AUTO -> R.string.ai_mode_auto
                com.freefile.tools.ai.model.AgentMode.FILE -> R.string.ai_mode_file
                com.freefile.tools.ai.model.AgentMode.GENERAL -> R.string.ai_mode_general
            }
            FilterChip(selected = current == m, onClick = { onSelect(m) }, label = { Text(stringResource(label)) })
        }
    }
}

// ------------------------------------------------------------- 会话管理

/** 多会话列表：切换 / 重命名 / 删除，每个会话独立语境。 */
@Composable
fun ConversationListDialog(
    conversations: List<com.freefile.tools.ai.data.AgentConversationEntity>,
    currentId: String,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_confirm_cancel)) }
        },
        title = { Text(stringResource(R.string.ai_conv_list_title)) },
        text = {
            Column {
                if (conversations.isEmpty()) {
                    Text(stringResource(R.string.ai_conv_empty), style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (conv.id != currentId) onSelect(conv.id)
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (conv.id == currentId) Icons.Rounded.CheckCircle else Icons.Rounded.InsertDriveFile,
                                null,
                                Modifier.size(18.dp),
                                tint = if (conv.id == currentId) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conv.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (conv.id == currentId) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                                Text(
                                    formatTime(conv.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            TextButton(onClick = {
                                renamingId = conv.id
                                renameText = conv.title
                            }) { Text(stringResource(R.string.ai_conv_rename)) }
                            TextButton(onClick = { deletingId = conv.id }) {
                                Text(
                                    stringResource(R.string.ai_conv_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    renamingId?.let { id ->
        var input by remember(id) { mutableStateOf(renameText) }
        AlertDialog(
            onDismissRequest = { renamingId = null },
            confirmButton = {
                TextButton(onClick = {
                    onRename(id, input)
                    renamingId = null
                }) { Text(stringResource(R.string.ai_conv_rename_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingId = null }) {
                    Text(stringResource(R.string.ai_confirm_cancel))
                }
            },
            title = { Text(stringResource(R.string.ai_conv_rename)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    deletingId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    deletingId = null
                }) {
                    Text(stringResource(R.string.ai_conv_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) {
                    Text(stringResource(R.string.ai_confirm_cancel))
                }
            },
            title = { Text(stringResource(R.string.ai_conv_delete)) },
            text = { Text(stringResource(R.string.ai_conv_delete_confirm)) }
        )
    }
}
