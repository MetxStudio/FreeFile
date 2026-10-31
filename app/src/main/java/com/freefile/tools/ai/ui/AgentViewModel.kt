package com.freefile.tools.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freefile.tools.ai.data.AgentConversationEntity
import com.freefile.tools.ai.data.AgentMessageEntity
import com.freefile.tools.ai.data.AgentOpLogEntity
import com.freefile.tools.ai.data.AgentSettings
import com.freefile.tools.ai.data.AgentSettingsData
import com.freefile.tools.ai.llm.AgentOrchestrator
import com.freefile.tools.ai.llm.HttpLog
import com.freefile.tools.ai.mcp.McpClient
import com.freefile.tools.ai.mcp.McpServerConfig
import com.freefile.tools.ai.model.AgentMode
import com.freefile.tools.ai.model.AgentPhase
import com.freefile.tools.ai.model.ToolProgress
import com.freefile.tools.ai.model.WritePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    val orchestrator = AgentOrchestrator(app)

    val messages: StateFlow<List<AgentMessageEntity>> =
        orchestrator.agentRepository.messages.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyList()
        )

    val conversations: StateFlow<List<AgentConversationEntity>> =
        orchestrator.agentRepository.conversations.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyList()
        )

    val phase: StateFlow<AgentPhase> = orchestrator.phase
    val mode: StateFlow<AgentMode> = orchestrator.mode
    val progress: StateFlow<ToolProgress?> = orchestrator.progress
    val liveDelta: StateFlow<String> = orchestrator.liveDelta
    val liveThinking: StateFlow<String> = orchestrator.liveThinking
    val pendingPreview: StateFlow<WritePreview?> = orchestrator.gate.pending
    val settings: StateFlow<AgentSettingsData> = AgentSettings.state
    val mcpStatus: StateFlow<Map<String, String>> = orchestrator.mcpStatus

    val currentConvId: String get() = orchestrator.agentRepository.currentConversationId

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    /** 向 UI 发一条 snackbar 提示。 */
    fun notify(msg: String) = viewModelScope.launch { _events.emit(msg) }

    fun bootstrap(paths: List<String>, prompt: String?) =
        orchestrator.bootstrap(paths, prompt)

    fun send(text: String, attachedPaths: List<String> = emptyList()) =
        orchestrator.send(text, attachedPaths)

    fun stop() = orchestrator.stop()
    fun newChat() = orchestrator.newChat()
    fun setMode(mode: AgentMode) = orchestrator.setMode(mode)

    fun switchConversation(convId: String) =
        orchestrator.switchConversation(convId) { ok ->
            viewModelScope.launch { if (!ok) _events.emit("会话不存在或已被删除") }
        }

    fun deleteConversation(convId: String) = orchestrator.deleteConversation(convId)

    fun renameConversation(convId: String, title: String) =
        orchestrator.renameConversation(convId, title)

    fun approve(
        preview: WritePreview,
        selectedPaths: List<String>,
        destination: String,
        conflictPolicy: String,
        overwriteAcknowledged: Boolean
    ) = orchestrator.gate.approve(
        preview.token, selectedPaths, destination, conflictPolicy, overwriteAcknowledged
    )

    fun cancel(preview: WritePreview) = orchestrator.gate.cancel(preview.token)

    fun undo() = orchestrator.undo { report ->
        viewModelScope.launch {
            if (report == null) {
                _events.emit("没有可撤销的操作")
            } else {
                _events.emit("已撤销 ${report.undone} 项，失败 ${report.failed} 项")
            }
        }
    }

    // ---- 设置 ----

    fun saveConnection(providerId: String, baseUrl: String, apiKey: String, model: String,
                       after: () -> Unit) {
        viewModelScope.launch {
            AgentSettings.updateConnection(providerId, baseUrl, apiKey, model)
            orchestrator.onConfigMaybeChanged()
            after()
        }
    }

    fun testConnection(cfg: AgentSettingsData, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(orchestrator.testConnection(cfg)) }
    }

    fun setEntryVisible(v: Boolean) = viewModelScope.launch { AgentSettings.setEntryVisible(v) }
    fun setContentAuthorized(v: Boolean) = viewModelScope.launch { AgentSettings.setContentAuthorized(v) }
    fun setShowDetails(v: Boolean) = viewModelScope.launch { AgentSettings.setShowToolDetails(v) }
    fun setUseStream(v: Boolean) = viewModelScope.launch { AgentSettings.setUseStream(v) }
    fun setBatchThreshold(v: Int) = viewModelScope.launch { AgentSettings.setBatchThreshold(v) }
    fun setHttpLogEnabled(v: Boolean) = viewModelScope.launch { AgentSettings.setHttpLogEnabled(v) }
    fun setShowThinking(v: Boolean) = viewModelScope.launch { AgentSettings.setShowThinking(v) }
    fun acceptOnboarding() = viewModelScope.launch { AgentSettings.setOnboardingAccepted(true) }

    fun toggleAlwaysAllowed(toolName: String, allowed: Boolean) =
        viewModelScope.launch { AgentSettings.toggleAlwaysAllowed(toolName, allowed) }

    // ---- MCP ----

    fun saveMcpServers(servers: List<McpServerConfig>) = viewModelScope.launch {
        AgentSettings.setMcpServers(servers)
        orchestrator.refreshMcp()
    }

    fun testMcp(server: McpServerConfig, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(try {
                val n = McpClient().test(server)
                "连接成功，共 $n 个工具"
            } catch (t: Throwable) {
                "失败：${t.message ?: t.javaClass.simpleName}"
            })
        }
    }

    // ---- 会话数据 ----

    fun clearAllData(onDone: () -> Unit) {
        viewModelScope.launch {
            orchestrator.agentRepository.clearAllData()
            onDone()
        }
    }

    fun loadLogs(onLoaded: (List<AgentOpLogEntity>) -> Unit) {
        viewModelScope.launch { onLoaded(orchestrator.agentRepository.recentOpLogs()) }
    }

    fun clearLogs(onDone: () -> Unit) {
        viewModelScope.launch {
            orchestrator.agentRepository.clearLogs()
            onDone()
        }
    }

    // ---- HTTP 日志 ----

    fun loadHttpLogs(onLoaded: (List<String>) -> Unit) {
        viewModelScope.launch {
            onLoaded(withContext(Dispatchers.IO) { HttpLog.readFile(400) })
        }
    }

    fun clearHttpLogs(onDone: () -> Unit) {
        viewModelScope.launch {
            HttpLog.clear()
            onDone()
        }
    }

    // ---- 附件 ----

    /**
     * 校验并读取附件：本地文本文件（≤64KB 且已授权）返回「路径+内容」，
     * 链接原样返回，其它类型只返回路径。供 UI 添加附件时预检。
     */
    fun prepareAttachment(raw: String, onResult: (String?, String) -> Unit) {
        val input = raw.trim()
        if (input.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (input.startsWith("http://") || input.startsWith("https://")) {
                withContext(Dispatchers.Main) { onResult(input, "链接") }
                return@launch
            }
            val f = File(input)
            if (!f.exists() || !f.isFile) {
                // 不是本机可读文件时仍按路径附加，交由 Agent 用工具自行判断
                withContext(Dispatchers.Main) { onResult(input, "仅附加路径") }
                return@launch
            }
            if (!AgentSettings.state.value.contentAuthorized || f.length() > 64 * 1024) {
                withContext(Dispatchers.Main) { onResult(input, "仅附加路径") }
                return@launch
            }
            val text = runCatching { f.readText() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (text == null) onResult(input, "仅附加路径")
                else onResult("$input\n[附件内容]\n$text", "已附加内容")
            }
        }
    }
}
