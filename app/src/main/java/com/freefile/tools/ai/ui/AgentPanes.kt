package com.freefile.tools.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.freefile.R
import com.freefile.tools.ai.data.AgentOpLogEntity
import com.freefile.tools.ai.data.AgentSettingsData
import com.freefile.tools.ai.llm.ProviderPreset
import com.freefile.tools.ai.mcp.McpServerConfig
import com.freefile.tools.ai.security.UndoManager
import java.util.UUID

// ------------------------------------------------------------- 设置页

/** 「始终允许」候选工具（跳过确认卡）。 */
private val ALWAYS_ALLOWED_TOOLS = listOf(
    "mkdir", "rename", "move", "copy", "delete_to_trash", "restore_from_trash",
    "compress", "extract", "write_text_file", "run_plugin_action", "open_url"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSettingsPane(vm: AgentViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val mcpStatus by vm.mcpStatus.collectAsState()

    var providerId by remember(settings.providerId) {
        mutableStateOf(settings.providerId.ifEmpty { "custom" })
    }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    val threshold by remember(settings.batchThreshold) {
        mutableFloatStateOf(settings.batchThreshold.toFloat())
    }

    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionTitle(stringResource(R.string.ai_settings_section_connection))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderPreset.PRESETS.forEach { p ->
                    FilterChip(
                        selected = providerId == p.id,
                        onClick = {
                            providerId = p.id
                            if (p.id != "custom") {
                                baseUrl = p.baseUrl
                                model = p.defaultModel
                            }
                        },
                        label = { Text(providerDisplayName(p.id)) }
                    )
                }
            }

            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it; saved = false },
                label = { Text(stringResource(R.string.ai_settings_base_url)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it; saved = false },
                label = { Text(stringResource(R.string.ai_settings_api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it; saved = false },
                label = { Text(stringResource(R.string.ai_settings_model)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        vm.saveConnection(providerId, baseUrl, apiKey, model) {
                            saved = true
                        }
                    },
                    enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.ai_settings_save)) }
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        vm.testConnection(
                            AgentSettingsData(
                                providerId = providerId,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                model = model
                            )
                        ) { err ->
                            testing = false
                            testResult = err
                        }
                    },
                    enabled = !testing && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (testing) stringResource(R.string.ai_settings_testing)
                    else stringResource(R.string.ai_settings_test))
                }
            }
            when {
                testing -> Text("…", style = MaterialTheme.typography.bodySmall)
                testResult == null && saved ->
                    Text(stringResource(R.string.ai_settings_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                testResult == null -> {}
                testResult!!.isEmpty() ->
                    Text(stringResource(R.string.ai_settings_test_ok),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                else -> Text(
                    stringResource(R.string.ai_settings_test_fail, testResult.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ---- MCP 服务器 ----
        item {
            McpSection(vm, mcpStatus)
        }

        // ---- 权限设置 ----
        item {
            SectionTitle(stringResource(R.string.ai_settings_section_permissions))
            Text(
                stringResource(R.string.ai_settings_perm_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        items(ALWAYS_ALLOWED_TOOLS) { tool ->
            PermissionRow(
                toolName = tool,
                checked = settings.alwaysAllowed.contains(tool),
                onToggle = { vm.toggleAlwaysAllowed(tool, it) }
            )
        }

        item {
            SectionTitle(stringResource(R.string.ai_settings_section_privacy))
            ToggleRow(stringResource(R.string.ai_settings_entry_visible), settings.entryVisible, vm::setEntryVisible)
            ToggleRow(stringResource(R.string.ai_settings_allow_content), settings.contentAuthorized, vm::setContentAuthorized)
            ToggleRow(stringResource(R.string.ai_settings_show_details), settings.showToolDetails, vm::setShowDetails)
            ToggleRow(stringResource(R.string.ai_settings_show_thinking), settings.showThinking, vm::setShowThinking)
            ToggleRow(stringResource(R.string.ai_settings_use_stream), settings.useStream, vm::setUseStream)
            ToggleRow(stringResource(R.string.ai_settings_http_log), settings.httpLogEnabled, vm::setHttpLogEnabled)

            Text(
                stringResource(R.string.ai_settings_batch_threshold) + "：${threshold.toInt()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = threshold,
                onValueChange = { vm.setBatchThreshold(it.toInt()) },
                valueRange = 1f..100f,
                steps = 98
            )

            OutlinedButton(onClick = { showOnboarding = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_settings_intro_again))
            }
            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.ai_settings_clear_data),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    vm.clearAllData { }
                }) { Text(stringResource(R.string.ai_confirm_execute)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.ai_confirm_cancel))
                }
            },
            text = { Text(stringResource(R.string.ai_settings_clear_confirm)) }
        )
    }
    if (showOnboarding) {
        OnboardingDialog(
            onAccept = { showOnboarding = false },
            onGoSetup = { showOnboarding = false }
        )
    }
}

// ------------------------------------------------------------- MCP 区块

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpSection(vm: AgentViewModel, mcpStatus: Map<String, String>) {
    val settings by vm.settings.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var testTarget by remember { mutableStateOf<McpServerConfig?>(null) }
    var testResult by remember { mutableStateOf("") }

    SectionTitle(stringResource(R.string.ai_settings_section_mcp))
    Text(
        stringResource(R.string.ai_mcp_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )

    settings.mcpServers.forEach { s ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { en ->
                            vm.saveMcpServers(
                                settings.mcpServers.map {
                                    if (it.id == s.id) it.copy(enabled = en) else it
                                }
                            )
                        }
                    )
                }
                Text(
                    s.url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                mcpStatus[s.name]?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        testTarget = s
                        testResult = "…"
                        vm.testMcp(s) { testResult = it }
                    }) { Text(stringResource(R.string.ai_mcp_test)) }
                    TextButton(onClick = {
                        vm.saveMcpServers(settings.mcpServers.filter { it.id != s.id })
                    }) {
                        Text(
                            stringResource(R.string.ai_mcp_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (testTarget?.id == s.id && testResult.isNotEmpty()) {
                    Text(
                        testResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (testResult.startsWith("失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ai_mcp_add))
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        vm.saveMcpServers(
                            settings.mcpServers + McpServerConfig(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                url = url.trim()
                            )
                        )
                    }
                    showAdd = false
                }) { Text(stringResource(R.string.ai_confirm_execute)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(R.string.ai_confirm_cancel))
                }
            },
            title = { Text(stringResource(R.string.ai_mcp_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.ai_mcp_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.ai_mcp_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }
}

// ------------------------------------------------------------- 权限行

@Composable
private fun PermissionRow(toolName: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(toolName, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                toolDisplayName(toolName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun toolDisplayName(tool: String): String = when (tool) {
    "mkdir" -> "新建文件夹"
    "rename" -> "重命名"
    "move" -> "移动"
    "copy" -> "复制"
    "delete_to_trash" -> "删除到回收站"
    "restore_from_trash" -> "从回收站恢复"
    "compress" -> "压缩"
    "extract" -> "解压"
    "write_text_file" -> "保存文本文件"
    "run_plugin_action" -> "调用插件动作"
    "open_url" -> "打开网页链接"
    else -> tool
}

// ------------------------------------------------------------- 通用小组件

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun providerDisplayName(id: String): String {
    val res = when (id) {
        "openai" -> R.string.ai_provider_openai
        "deepseek" -> R.string.ai_provider_deepseek
        "moonshot" -> R.string.ai_provider_moonshot
        "qwen" -> R.string.ai_provider_qwen
        else -> R.string.ai_provider_custom
    }
    return stringResource(res)
}

// ------------------------------------------------------------- 日志页（操作日志 / HTTP 日志）

private enum class LogTab { OP, HTTP }

@Composable
fun OpLogPane(vm: AgentViewModel) {
    var tab by remember { mutableStateOf(LogTab.OP) }
    var refreshKey by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = tab == LogTab.OP,
                onClick = { tab = LogTab.OP },
                label = { Text(stringResource(R.string.ai_log_tab_op)) }
            )
            FilterChip(
                selected = tab == LogTab.HTTP,
                onClick = { tab = LogTab.HTTP },
                label = { Text(stringResource(R.string.ai_log_tab_http)) }
            )
            Spacer(Modifier.weight(1f))
            when (tab) {
                LogTab.OP -> ClearButton {
                    vm.clearLogs { refreshKey++ }
                }
                LogTab.HTTP -> ClearButton {
                    vm.clearHttpLogs { refreshKey++ }
                }
            }
        }
        when (tab) {
            LogTab.OP -> OpLogList(vm, refreshKey)
            LogTab.HTTP -> HttpLogList(vm, refreshKey)
        }
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.ai_log_clear)) }
}

@Composable
private fun OpLogList(vm: AgentViewModel, refreshKey: Int) {
    var logs by remember { mutableStateOf<List<AgentOpLogEntity>>(emptyList()) }
    LaunchedEffect(refreshKey) {
        vm.loadLogs { logs = it }
    }
    if (logs.isEmpty()) {
        Text(
            stringResource(R.string.ai_log_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(24.dp)
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs, key = { it.id }) { log -> OpLogCard(log) }
        }
    }
}

@Composable
private fun HttpLogList(vm: AgentViewModel, refreshKey: Int) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(refreshKey) {
        vm.loadHttpLogs { lines = it }
    }
    if (lines.isEmpty()) {
        Text(
            stringResource(R.string.ai_log_http_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(24.dp)
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(lines.size) { i ->
                Text(
                    lines[i],
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun OpLogCard(log: AgentOpLogEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(log.toolName, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(formatTime(log.ts), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                stringResource(R.string.ai_log_instruction) + "：" + log.userInstruction,
                style = MaterialTheme.typography.bodySmall
            )
            if (log.paramsSummary.isNotEmpty()) {
                Text(
                    stringResource(R.string.ai_log_tool) + "：" + log.paramsSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                stringResource(R.string.ai_log_result) + "：" + log.resultSummary,
                style = MaterialTheme.typography.bodySmall
            )
            val stateRes = when (log.undoState) {
                UndoManager.STATE_AVAILABLE -> R.string.ai_log_st_available
                UndoManager.STATE_UNDONE -> R.string.ai_log_st_undone
                UndoManager.STATE_FAILED -> R.string.ai_log_st_failed
                else -> R.string.ai_log_st_none
            }
            Text(
                stringResource(R.string.ai_log_undo_state, stringResource(stateRes)),
                style = MaterialTheme.typography.labelSmall,
                color = if (log.undoState == UndoManager.STATE_FAILED)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
            )
        }
    }
}
