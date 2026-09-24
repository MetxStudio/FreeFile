package com.freefile.tools.richeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.StrikethroughS
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.freefile.R
import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.EngineSelector
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 判断文件是否为 HTML（按扩展名）。 */
private fun isHtmlFile(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext in setOf("html", "htm")
}

/**
 * 富文本编辑器（基于 richeditor-compose 1.0.0-rc05）。
 *
 * 特性：
 *  - 加粗 / 斜体 / 下划线 / 删除线
 *  - 有序列表 / 无序列表
 *  - 行内代码
 *  - 超链接
 *  - HTML 导入导出（.html/.htm 文件）；其余文件以纯文本读写
 *  - 只读检测（归档内 / 远程路径只读）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun RichEditorScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val richState = rememberRichTextState()
    var loading by remember { mutableStateOf(true) }
    var readOnly by remember { mutableStateOf(false) }
    var originalHtml by remember { mutableStateOf("") }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    val isHtml = isHtmlFile(filePath)
    val title = filePath.substringAfterLast('/')

    // 当前光标处的样式（用于工具栏高亮）
    val currentSpan = richState.currentSpanStyle
    val isBold = currentSpan.fontWeight == FontWeight.Bold
    val isItalic = currentSpan.fontStyle == FontStyle.Italic
    val isUnderline = currentSpan.textDecoration == TextDecoration.Underline
    val isStrike = currentSpan.textDecoration == TextDecoration.LineThrough

    // 是否已修改（按 HTML 输出比较）
    val modified = remember(richState.annotatedString) {
        richState.toHtml() != originalHtml
    }

    // 加载文件
    LaunchedEffect(filePath) {
        loading = true
        try {
            val (text, ro) = withContext(Dispatchers.IO) {
                val engine = EngineSelector.select(filePath)
                val ro = ArchiveEngine.isArchivePath(filePath) ||
                    !filePath.startsWith("/") ||
                    filePath.startsWith("remote://")
                val raw = engine.openInputStream(filePath).use { it.readBytes() }
                val text = String(raw, Charsets.UTF_8)
                text to ro
            }
            if (isHtml) {
                richState.setHtml(text)
            } else {
                // 纯文本：清空后插入，避免带入旧格式
                val escaped = text
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>")
                richState.setHtml("<p>$escaped</p>")
            }
            originalHtml = richState.toHtml()
            readOnly = ro
            if (readOnly) {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.rich_editor_read_only)) }
            }
        } catch (e: Throwable) {
            scope.launch {
                snackbar.showSnackbar(context.getString(R.string.rich_editor_load_failed, e.message ?: ""))
            }
        } finally {
            loading = false
        }
    }

    // 保存
    fun save() {
        scope.launch {
            try {
                val outText = if (isHtml) richState.toHtml() else richState.annotatedString.text
                withContext(Dispatchers.IO) {
                    val engine = EngineSelector.select(filePath)
                    engine.openOutputStream(filePath).use { out ->
                        out.write(outText.toByteArray(Charsets.UTF_8))
                    }
                }
                originalHtml = richState.toHtml()
                snackbar.showSnackbar(context.getString(R.string.rich_editor_saved))
            } catch (e: Throwable) {
                snackbar.showSnackbar(context.getString(R.string.rich_editor_save_failed, e.message ?: ""))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, maxLines = 1)
                        if (modified) {
                            Text(
                                " *",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { save() },
                        enabled = !loading && !readOnly
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.rich_editor_save))
                    }
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rich_editor_insert_link)) },
                                onClick = {
                                    moreMenuExpanded = false
                                    showLinkDialog = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 格式化工具栏
            FormatToolbar(
                richState = richState,
                isBold = isBold,
                isItalic = isItalic,
                isUnderline = isUnderline,
                isStrike = isStrike,
                onInsertLink = { showLinkDialog = true }
            )

            // 状态栏：行数 / 字符数 / 格式
            val text = richState.annotatedString.text
            val lineCount = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.rich_editor_line_count, lineCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.rich_editor_char_count, text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        if (isHtml) R.string.rich_editor_format_html
                        else R.string.rich_editor_format_plain
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // 编辑区
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                RichTextEditor(
                    state = richState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    readOnly = readOnly,
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    // 插入超链接对话框
    if (showLinkDialog) {
        var urlInput by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text(stringResource(R.string.rich_editor_insert_link)) },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.rich_editor_link_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            richState.addLinkToSelection(urlInput.trim())
                        }
                        showLinkDialog = false
                    },
                    enabled = urlInput.isNotBlank()
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// ==================== 格式化工具栏 ====================

/**
 * 富文本格式化工具栏：加粗 / 斜体 / 下划线 / 删除线 / 列表 / 代码 / 链接。
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun FormatToolbar(
    richState: RichTextState,
    isBold: Boolean,
    isItalic: Boolean,
    isUnderline: Boolean,
    isStrike: Boolean,
    onInsertLink: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            ToolbarIcon(
                icon = Icons.Rounded.FormatBold,
                selected = isBold,
                onClick = { richState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
            )
            ToolbarIcon(
                icon = Icons.Rounded.FormatItalic,
                selected = isItalic,
                onClick = { richState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
            )
            ToolbarIcon(
                icon = Icons.Rounded.FormatUnderlined,
                selected = isUnderline,
                onClick = { richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
            )
            ToolbarIcon(
                icon = Icons.Rounded.StrikethroughS,
                selected = isStrike,
                onClick = { richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
            )
            ToolbarDivider()
            ToolbarIcon(
                icon = Icons.Rounded.FormatListBulleted,
                onClick = { richState.toggleUnorderedList() }
            )
            ToolbarIcon(
                icon = Icons.Rounded.FormatListNumbered,
                onClick = { richState.toggleOrderedList() }
            )
            ToolbarIcon(
                icon = Icons.Rounded.Code,
                onClick = { richState.toggleCodeSpan() }
            )
            ToolbarIcon(
                icon = Icons.Rounded.Link,
                onClick = onInsertLink
            )
        }
    }
}

/** 工具栏单个按钮。 */
@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 工具栏分隔线。 */
@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .size(24.dp)
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
    )
}
