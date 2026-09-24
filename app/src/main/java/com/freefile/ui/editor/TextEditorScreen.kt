package com.freefile.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freefile.R
import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.EngineSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** 简易编码探测：优先 BOM，否则按 UTF-8 尝试。 */
private fun detectEncoding(raw: ByteArray): String {
    return when {
        raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte() -> "UTF-8 (BOM)"
        raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte() -> "UTF-16 LE"
        raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte() -> "UTF-16 BE"
        else -> "UTF-8"
    }
}

/** 按 BOM 剥离后用指定编码解码文本。 */
private fun decodeText(raw: ByteArray): Pair<String, String> {
    val enc = detectEncoding(raw)
    val bomStripped = when {
        enc == "UTF-8 (BOM)" -> raw.copyOfRange(3, raw.size)
        enc == "UTF-16 LE" -> raw.copyOfRange(2, raw.size)
        enc == "UTF-16 BE" -> raw.copyOfRange(2, raw.size)
        else -> raw
    }
    val charset = when (enc) {
        "UTF-16 LE" -> charset("UTF-16LE")
        "UTF-16 BE" -> charset("UTF-16BE")
        else -> Charsets.UTF_8
    }
    val text = runCatching { String(bomStripped, charset) }
        .getOrElse { String(raw, Charsets.UTF_8) }
    return text to enc
}

/**
 * 内置文本编辑器（按 报告.txt 增强）。
 *
 * 已实现：
 *  - UTF-8 编码自动检测（含 BOM 识别）
 *  - 行号显示（左侧固定栏，可开关）
 *  - 自动换行（可开关）
 *  - 字号可调（默认 14sp，范围 10-28）
 *  - 搜索/替换（支持正则、大小写、全词匹配）
 *  - 跳转到指定行
 *  - 未保存修改标记
 *  - 大文件流畅模式（>1MB 禁用部分功能）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(filePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var original by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var readOnly by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(filePath.substringAfterLast('/')) }
    var encoding by remember { mutableStateOf("UTF-8") }
    var fileTooLarge by remember { mutableStateOf(false) }

    // 编辑器设置
    var showLineNumbers by remember { mutableStateOf(true) }
    var wordWrap by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(14) }
    var showSettings by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var showGoToLineDialog by remember { mutableStateOf(false) }

    // 搜索/替换
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var searchRegex by remember { mutableStateOf(false) }
    var searchCaseSensitive by remember { mutableStateOf(false) }
    var searchWholeWord by remember { mutableStateOf(false) }
    var matchCount by remember { mutableStateOf(0) }
    var currentMatchIndex by remember { mutableStateOf(-1) }

    val content = fieldValue.text
    val modified = content != original
    val lineCount = if (content.isEmpty()) 1 else content.count { it == '\n' } + 1

    // 加载文件
    LaunchedEffect(filePath) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                val engine = EngineSelector.select(filePath)
                val ro = ArchiveEngine.isArchivePath(filePath) ||
                    !filePath.startsWith("/") ||
                    filePath.startsWith("remote://")
                val raw = engine.openInputStream(filePath).use { it.readBytes() }
                val large = raw.size > 1 * 1024 * 1024
                val limited = if (large) raw.copyOf(min(raw.size, 1 * 1024 * 1024)) else raw
                val (text, enc) = decodeText(limited)
                Triple(text, ro, enc to large)
            }
            fieldValue = TextFieldValue(result.first)
            original = result.first
            readOnly = result.second
            encoding = result.third.first
            fileTooLarge = result.third.second
            if (readOnly) scope.launch { snackbar.showSnackbar(context.getString(R.string.text_editor_read_only)) }
            if (fileTooLarge) scope.launch { snackbar.showSnackbar(context.getString(R.string.text_editor_too_large)) }
        } catch (e: Exception) {
            fieldValue = TextFieldValue("")
            scope.launch {
                snackbar.showSnackbar(context.getString(R.string.text_editor_save_failed, e.message ?: ""))
            }
        } finally {
            loading = false
        }
    }

    // 搜索匹配列表（每次搜索内容变化时重算）
    val matches: List<IntRange> = remember(content, searchQuery, searchRegex, searchCaseSensitive, searchWholeWord) {
        if (searchQuery.isEmpty()) emptyList()
        else computeMatches(content, searchQuery, searchRegex, searchCaseSensitive, searchWholeWord)
    }

    LaunchedEffect(matches) {
        matchCount = matches.size
        if (matches.isNotEmpty() && (currentMatchIndex < 0 || currentMatchIndex >= matches.size)) {
            currentMatchIndex = 0
        } else if (matches.isEmpty()) {
            currentMatchIndex = -1
        }
    }

    fun selectMatch(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        currentMatchIndex = i
        val r = matches[i]
        fieldValue = fieldValue.copy(selection = TextRange(r.first, r.last + 1))
    }

    fun findNext() = selectMatch(currentMatchIndex + 1)
    fun findPrev() = selectMatch(currentMatchIndex - 1)

    fun replaceCurrent() {
        if (matches.isEmpty() || currentMatchIndex < 0) return
        val r = matches[currentMatchIndex]
        val newText = content.substring(0, r.first) + replaceQuery + content.substring(r.last + 1)
        fieldValue = TextFieldValue(newText, selection = TextRange(r.first + replaceQuery.length))
    }

    fun replaceAll() {
        if (matches.isEmpty() || searchQuery.isEmpty()) return
        val sb = StringBuilder()
        var last = 0
        for (r in matches) {
            sb.append(content, last, r.first)
            sb.append(replaceQuery)
            last = r.last + 1
        }
        sb.append(content, last, content.length)
        fieldValue = TextFieldValue(sb.toString())
    }

    fun goToLine(target: Int) {
        if (target <= 0) return
        var line = 1
        var idx = 0
        while (line < target && idx < content.length) {
            if (content[idx] == '\n') line++
            idx++
        }
        if (line == target) {
            fieldValue = fieldValue.copy(selection = TextRange(idx))
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
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.text_editor_search))
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val engine = EngineSelector.select(filePath)
                                        engine.openOutputStream(filePath).use { out ->
                                            out.write(content.toByteArray(Charsets.UTF_8))
                                        }
                                    }
                                    original = content
                                    snackbar.showSnackbar(context.getString(R.string.text_editor_saved))
                                } catch (e: Exception) {
                                    snackbar.showSnackbar(context.getString(R.string.text_editor_save_failed, e.message ?: ""))
                                }
                            }
                        },
                        enabled = !loading && !readOnly && modified
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.text_editor_save))
                    }
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.text_editor_go_to_line)) },
                                onClick = {
                                    moreMenuExpanded = false
                                    showGoToLineDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.text_editor_settings)) },
                                onClick = {
                                    moreMenuExpanded = false
                                    showSettings = true
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
            // 状态栏：编码 / 行数 / 大小
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.text_editor_encoding, encoding),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.text_editor_line_count, lineCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (fileTooLarge) {
                    Text(
                        stringResource(R.string.text_editor_smooth_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // 搜索/替换栏
            if (showSearchBar) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it; currentMatchIndex = -1 },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.text_editor_search_placeholder)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Text(stringResource(R.string.text_editor_match_count, matchCount),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Spacer(Modifier.padding(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { findPrev() }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.text_editor_find_prev))
                        }
                        IconButton(onClick = { findNext() }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.text_editor_find_next))
                        }
                        AssistChip(
                            onClick = { searchRegex = !searchRegex },
                            label = { Text(stringResource(R.string.text_editor_regex)) },
                            modifier = Modifier.heightIn(min = 24.dp)
                        )
                        AssistChip(
                            onClick = { searchCaseSensitive = !searchCaseSensitive },
                            label = { Text(stringResource(R.string.text_editor_case_sensitive)) },
                            modifier = Modifier.heightIn(min = 24.dp)
                        )
                        AssistChip(
                            onClick = { searchWholeWord = !searchWholeWord },
                            label = { Text(stringResource(R.string.text_editor_whole_word)) },
                            modifier = Modifier.heightIn(min = 24.dp)
                        )
                    }
                    // 替换行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = replaceQuery,
                            onValueChange = { replaceQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.text_editor_replace_placeholder)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { replaceCurrent() }, enabled = matches.isNotEmpty()) {
                            Text(stringResource(R.string.text_editor_replace))
                        }
                        TextButton(onClick = { replaceAll() }, enabled = matches.isNotEmpty()) {
                            Text(stringResource(R.string.text_editor_replace_all))
                        }
                    }
                }
            }

            // 编辑区：行号 + 文本
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                ) {
                    if (showLineNumbers) {
                        val lineNumbersText = (1..lineCount).joinToString("\n")
                        Text(
                            text = lineNumbersText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(40.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, end = 4.dp)
                        )
                    }
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp
                        ),
                        readOnly = readOnly,
                        singleLine = false,
                        maxLines = if (wordWrap) Int.MAX_VALUE else 1,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                }
            }
        }
    }

    // 跳行对话框
    if (showGoToLineDialog) {
        var lineInput by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGoToLineDialog = false },
            title = { Text(stringResource(R.string.text_editor_go_to_line)) },
            text = {
                OutlinedTextField(
                    value = lineInput,
                    onValueChange = { lineInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.text_editor_line_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    lineInput.toIntOrNull()?.let { goToLine(it) }
                    showGoToLineDialog = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showGoToLineDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 设置对话框
    if (showSettings) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.text_editor_settings)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showLineNumbers, onCheckedChange = { showLineNumbers = it })
                        Text(stringResource(R.string.text_editor_show_line_numbers))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wordWrap, onCheckedChange = { wordWrap = it })
                        Text(stringResource(R.string.text_editor_word_wrap))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FormatSize, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.text_editor_font_size, fontSize),
                            modifier = Modifier.width(60.dp))
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { fontSize = it.toInt() },
                            valueRange = 10f..28f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}

/** 计算所有匹配区间。 */
private fun computeMatches(
    text: String,
    query: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    wholeWord: Boolean
): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<IntRange>()
    try {
        if (useRegex) {
            val opts = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
            val regex = Regex(query, opts)
            regex.findAll(text).forEach { result.add(it.range) }
        } else {
            val needle = if (caseSensitive) query else query.lowercase()
            val haystack = if (caseSensitive) text else text.lowercase()
            var idx = 0
            while (idx <= haystack.length - needle.length) {
                val found = haystack.indexOf(needle, idx)
                if (found < 0) break
                val range = found until found + needle.length
                if (wholeWord && !isWholeWord(text, range)) {
                    idx = found + 1
                    continue
                }
                result.add(range)
                idx = found + needle.length.coerceAtLeast(1)
            }
        }
    } catch (_: Exception) { /* invalid regex */ }
    return result
}

private fun isWholeWord(text: String, range: IntRange): Boolean {
    val beforeOk = range.first == 0 || !text[range.first - 1].isLetterOrDigit()
    val afterOk = range.last + 1 >= text.length || !text[range.last + 1].isLetterOrDigit()
    return beforeOk && afterOk
}
