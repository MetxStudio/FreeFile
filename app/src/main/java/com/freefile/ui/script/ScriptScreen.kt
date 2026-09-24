package com.freefile.ui.script

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.freefile.R
import com.freefile.script.LuaEngine
import com.freefile.script.PythonEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.widget.Toast

/**
 * 问题 7 辅助：从 SAF 返回的 Uri 读取文本内容，最大 2 MB，脚本文件足够用。
 */
private fun readTextFromUri(context: Context, uri: Uri, maxBytes: Int = 2 * 1024 * 1024): String {
    return context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readBytes().take(maxBytes).toByteArray()
        String(bytes, Charsets.UTF_8)
    }.orEmpty()
}

/**
 * 问题 7 辅助：根据后缀名判断脚本类型（不区分大小写）。
 * @return "lua" / "py" / null（未知）
 */
private fun inferScriptTypeFromUri(context: Context, uri: Uri): String? {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && c.moveToFirst()) c.getString(nameIdx) else null
    }.orEmpty().ifEmpty { uri.lastPathSegment.orEmpty() }
    return name.substringAfterLast('.', "").lowercase().let { ext ->
        if (ext == "lua") "lua"
        else if (ext == "py" || ext == "pyw") "py"
        else null
    }
}

/**
 * 脚本页（Sprint 5）：Lua（内嵌 luaj）/ Python（调用设备解释器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tabIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 状态必须在 launcher 之前声明，launcher lambda 捕获当前 MutableState 代理。
    // Lua 代码
    var luaCode by remember {
        mutableStateOf(
            "-- FreeFile Lua 示例\n" +
                "local files = freefile.list('/storage/emulated/0')\n" +
                "for i, f in ipairs(files) do\n" +
                "  local tag = f.isdir and '[目录]' or ('[' .. f.size .. 'B]')\n" +
                "  freefile.log(tag .. ' ' .. f.name)\n" +
                "end\n"
        )
    }
    // Python 脚本路径
    var pyPath by remember { mutableStateOf("/storage/emulated/0/script.py") }
    // 输出
    val output = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

    // 问题 7：SAF 文件选择导入器（任意 .lua / .py 文本脚本）
    var importError by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val kind = inferScriptTypeFromUri(ctx, uri)
                val text = try {
                    readTextFromUri(ctx, uri)
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        importError = "读取脚本失败：${t.message ?: t::class.simpleName}"
                    }
                    return@launch
                }
                when {
                    kind == null -> withContext(Dispatchers.Main) {
                        importError = "仅支持 .lua / .py 后缀的脚本文件"
                    }
                    kind == "lua" -> withContext(Dispatchers.Main) {
                        tabIndex = 0
                        luaCode = text
                        // 持久化副本到 app scripts/ 目录，方便用户再次编辑
                        runCatching {
                            val dir = File(ctx.filesDir, "scripts").apply { mkdirs() }
                            val safeName = (uri.lastPathSegment?.substringAfterLast('/') ?: "imported.lua")
                                .replace(Regex("[^\\w.\\-]"), "_")
                                .let { if (it.endsWith(".lua", ignoreCase = true)) it else "${it}.lua" }
                            File(dir, safeName).writeText(text)
                        }
                    }
                    kind == "py" -> {
                        // Python 按原先设计：保存到 scripts/ 目录，然后把路径填入 pyPath
                        // 同时把内容持久化，避免用户因为权限读不到源路径
                        runCatching {
                            val dir = File(ctx.filesDir, "scripts").apply { mkdirs() }
                            val safeName = (uri.lastPathSegment?.substringAfterLast('/') ?: "imported.py")
                                .replace(Regex("[^\\w.\\-]"), "_")
                                .let { if (it.endsWith(".py", ignoreCase = true) || it.endsWith(".pyw", ignoreCase = true)) it else "${it}.py" }
                            val dest = File(dir, safeName)
                            dest.writeText(text)
                            dest.absolutePath
                        }.onSuccess { path ->
                            withContext(Dispatchers.Main) {
                                tabIndex = 1
                                pyPath = path
                            }
                        }.onFailure { t ->
                            withContext(Dispatchers.Main) {
                                importError = "保存 .py 失败：${t.message ?: t::class.simpleName}"
                            }
                        }
                    }
                }
            }
        }
    )

    // 输出回调来自 IO 线程；mutableStateListOf 支持跨线程更新
    fun append(line: String) {
        output.add(line)
        if (output.size > 500) output.removeAt(0)
    }

    // 导入错误提示 toast
    importError?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importError = null },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text("OK")
                }
            },
            title = { Text(stringResource(R.string.script_title)) },
            text = { Text(msg) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.script_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                actions = {
                    // 问题 7：从本地导入脚本（SAF OpenDocument，MIME text/*，
                    //   接受所有文档再在结果中校验后缀 .lua / .py）
                    IconButton(onClick = {
                        runCatching {
                            importLauncher.launch(
                                // text/* 已覆盖 .lua/.py，但系统 DocumentsProvider 有时
                                // 会用 application/octet-stream；这里传入 */*，后缀校验在结果中做。
                                arrayOf("*/*")
                            )
                        }
                    }) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = "Import local script"
                        )
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
            importError?.takeIf { tabIndex == 0 && !it.startsWith("仅支持") }?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = {
                    Text("Lua")
                })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = {
                    Text("Python")
                })
            }

            when (tabIndex) {
                0 -> Column(Modifier.padding(12.dp)) {
                    // 固定常用脚本：一键载入到编辑器
                    Text(
                        stringResource(R.string.script_common),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CommonScripts.forEach { cs ->
                            AssistChip(
                                onClick = {
                                    luaCode = cs.code
                                    Toast.makeText(
                                        ctx,
                                        ctx.getString(R.string.script_common_load, ctx.getString(cs.nameRes)),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                label = { Text(stringResource(cs.nameRes), style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = luaCode,
                        onValueChange = { luaCode = it },
                        label = { Text(stringResource(R.string.script_lua_code)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { luaCode = "" }) {
                            Text(stringResource(R.string.clear_selection))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = !running,
                            onClick = {
                                running = true
                                output.clear()
                                // 显式在 IO 线程执行脚本，避免阻塞主线程
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        LuaEngine.run(luaCode) { append(it) }
                                    } finally {
                                        withContext(Dispatchers.Main) { running = false }
                                    }
                                }
                            }
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.script_run))
                        }
                    }
                }

                1 -> Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = pyPath,
                        onValueChange = { pyPath = it },
                        label = { Text(stringResource(R.string.script_py_path)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        stringResource(R.string.script_py_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            enabled = !running,
                            onClick = {
                                running = true
                                output.clear()
                                // 显式在 IO 线程执行脚本，避免阻塞主线程
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val f = File(pyPath.trim())
                                        if (!f.exists()) {
                                            append("[文件不存在] $pyPath")
                                        } else {
                                            PythonEngine.runScript(f) { append(it) }
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) { running = false }
                                    }
                                }
                            }
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.script_run))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.script_output),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            // 加载提示横幅：脚本运行中显示进度条与状态文案
            if (running) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.script_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (output.isEmpty() && !running) {
                    Text(
                        stringResource(R.string.script_output_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    output.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
