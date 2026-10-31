package com.freefile.tools.ai.llm

import android.content.Context
import com.freefile.FreeFileApp
import com.freefile.tools.ai.data.AgentRepository
import com.freefile.tools.ai.data.AgentSettings
import com.freefile.tools.ai.model.AgentMode
import com.freefile.tools.ai.model.AgentPhase
import com.freefile.tools.ai.model.CallStatus
import com.freefile.tools.ai.model.ChatMessage
import com.freefile.tools.ai.model.ThinkText
import com.freefile.tools.ai.model.ToolCall
import com.freefile.tools.ai.model.ToolProgress
import com.freefile.tools.ai.model.ToolResult
import com.freefile.tools.ai.mcp.McpBridge
import com.freefile.tools.ai.mcp.McpClient
import com.freefile.tools.ai.security.ConfirmationGate
import com.freefile.tools.ai.security.UndoManager
import com.freefile.tools.ai.security.UndoReport
import com.freefile.tools.ai.tool.ToolContext
import com.freefile.tools.ai.tool.ToolInstaller
import com.freefile.tools.ai.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Agent 编排器：理解 → 工具循环（≤8 轮）→ 汇总。
 * 写工具的确认/取消由 [ConfirmationGate] 挂起承载，UI 观察 gate.pending 渲染确认卡。
 */
class AgentOrchestrator(context: Context) {

    private val appContext = context.applicationContext
    val agentRepository = AgentRepository(appContext)
    val gate = ConfirmationGate()
    val undoManager = UndoManager(appContext)
    private val mcpClient = McpClient()

    private val scope: CoroutineScope = FreeFileApp.instance.mainScope
    private val llm: LlmClient = OpenAiCompatibleClient()

    private val _phase = MutableStateFlow(AgentPhase.BOOTSTRAPPING)
    val phase: StateFlow<AgentPhase> = _phase.asStateFlow()

    private val _progress = MutableStateFlow<ToolProgress?>(null)
    val progress: StateFlow<ToolProgress?> = _progress.asStateFlow()

    private val _mode = MutableStateFlow(AgentMode.AUTO)
    val mode: StateFlow<AgentMode> = _mode.asStateFlow()

    /** 流式回复中的实时正文。 */
    private val _liveDelta = MutableStateFlow("")
    val liveDelta: StateFlow<String> = _liveDelta.asStateFlow()

    /** 流式回复中的实时深度思考。 */
    private val _liveThinking = MutableStateFlow("")
    val liveThinking: StateFlow<String> = _liveThinking.asStateFlow()

    /** 本轮开始流式的时间（elapsedRealtime），用于展示"思考中 · Xs"。 */
    private val _streamStartedAt = MutableStateFlow(0L)
    val streamStartedAt: StateFlow<Long> = _streamStartedAt.asStateFlow()

    /** MCP 加载摘要（name → 状态），供设置页展示。 */
    private val _mcpStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val mcpStatus: StateFlow<Map<String, String>> = _mcpStatus.asStateFlow()

    private var activeJob: Job? = null
    private var lastInstruction = ""

    val isBusy: Boolean get() = activeJob?.isActive == true

    init {
        ToolInstaller.install()
        AgentSettings.init(appContext)
        HttpLog.init(File(appContext.filesDir, "agent_http.log"))
        HttpLog.enabled = AgentSettings.state.value.httpLogEnabled
        agentRepository.onStopRequested = { stopInternal() }
        // 「始终允许」白名单：破坏性操作永不自动放行
        gate.autoAllow = { preview ->
            !preview.destructive &&
                AgentSettings.state.value.alwaysAllowed.contains(preview.toolName)
        }
        scope.launch {
            gate.pending.collect { p ->
                if (p != null) {
                    _phase.value = AgentPhase.AWAITING_CONFIRMATION
                } else if (_phase.value == AgentPhase.AWAITING_CONFIRMATION) {
                    _phase.value =
                        if (activeJob?.isActive == true) AgentPhase.EXECUTING_TOOL else AgentPhase.IDLE
                }
            }
        }
        scope.launch { refreshMcp() }
    }

    // ------------------------------------------------------------- 生命周期

    /** 恢复/创建会话；可带入口选中文件与初始指令。 */
    fun bootstrap(initialPaths: List<String> = emptyList(), initialPrompt: String? = null) {
        scope.launch {
            agentRepository.ensureConversation()
            _phase.value =
                if (AgentSettings.state.value.isConfigured) AgentPhase.IDLE else AgentPhase.NEEDS_SETUP
            if (!initialPrompt.isNullOrBlank()) {
                send(initialPrompt, initialPaths)
            }
        }
    }

    fun newChat() {
        scope.launch {
            stopInternal()
            agentRepository.newConversation()
            _phase.value = phaseAfterConfig()
        }
    }

    /** 切换到指定会话（独立语境）。 */
    fun switchConversation(convId: String, onDone: (Boolean) -> Unit = {}) {
        scope.launch { onDone(agentRepository.switchConversation(convId)) }
    }

    fun deleteConversation(convId: String) {
        scope.launch { agentRepository.deleteConversation(convId) }
    }

    fun renameConversation(convId: String, title: String) {
        scope.launch { agentRepository.renameConversationById(convId, title) }
    }

    fun setMode(mode: AgentMode) {
        _mode.value = mode
    }

    private fun stopInternal() {
        gate.cancelCurrent()
        activeJob?.cancel()
        activeJob = null
        _progress.value = null
        _liveDelta.value = ""
        _liveThinking.value = ""
        _streamStartedAt.value = 0L
    }

    fun stop() {
        stopInternal()
        _phase.value = phaseAfterConfig()
    }

    /** 设置页测试连接。 */
    suspend fun testConnection(cfg: com.freefile.tools.ai.data.AgentSettingsData): String? =
        llm.testConnection(cfg)

    /** 配置保存后从设置页返回时调用。 */
    fun onConfigMaybeChanged() {
        if (!isBusy && _phase.value == AgentPhase.NEEDS_SETUP &&
            AgentSettings.state.value.isConfigured
        ) {
            _phase.value = AgentPhase.IDLE
        }
    }

    private fun phaseAfterConfig(): AgentPhase =
        if (AgentSettings.state.value.isConfigured) AgentPhase.IDLE else AgentPhase.NEEDS_SETUP

    // --------------------------------------------------------------- MCP

    /** 刷新 MCP 服务器工具缓存（设置页保存后 / 启动时调用）。 */
    fun refreshMcp(onDone: (Map<String, String>) -> Unit = {}) {
        scope.launch {
            val servers = AgentSettings.state.value.mcpServers
            val summary = if (servers.isEmpty()) {
                emptyMap()
            } else {
                McpBridge.refresh(servers, mcpClient)
            }
            _mcpStatus.value = summary
            onDone(summary)
        }
    }

    // --------------------------------------------------------------- 对话

    fun send(text: String, attachedPaths: List<String> = emptyList()) {
        val content = text.trim()
        if (content.isEmpty() || isBusy) return
        if (!AgentSettings.state.value.isConfigured) {
            _phase.value = AgentPhase.NEEDS_SETUP
            return
        }
        lastInstruction = content
        val userContent = if (attachedPaths.isEmpty()) content else buildString {
            append(content)
            append("\n\n[用户附加的文件路径/链接（真实信息，可直接对这些 path 调用工具）]")
            attachedPaths.forEach { append("\n- ").append(it) }
        }
        activeJob = scope.launch {
            try {
                agentRepository.appendMessage(ChatMessage.ROLE_USER, userContent)
                agentRepository.autoTitleIfNeeded(content)
                runLoop()
            } finally {
                _progress.value = null
                _liveDelta.value = ""
                _liveThinking.value = ""
                _streamStartedAt.value = 0L
                if (_phase.value != AgentPhase.NEEDS_SETUP) _phase.value = AgentPhase.IDLE
            }
        }
    }

    private suspend fun runLoop() {
        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            val cfg = AgentSettings.state.value
            if (!cfg.isConfigured) {
                _phase.value = AgentPhase.NEEDS_SETUP
                return
            }
            _phase.value = AgentPhase.STREAMING
            _liveDelta.value = ""
            _liveThinking.value = ""
            _streamStartedAt.value = android.os.SystemClock.elapsedRealtime()

            // 增量合并：SSE 每个 token 都回调，若逐条写 StateFlow 会触发过多重组，
            // 导致界面在长时间推理中"卡住"、结束后一次性跳出。这里按 ~60ms 节流。
            val textBuf = StringBuilder()
            val thinkBuf = StringBuilder()
            val lock = Any()
            var lastEmit = 0L
            fun publish(force: Boolean = false) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!force && now - lastEmit < 60L) return
                lastEmit = now
                val text: String
                val think: String
                synchronized(lock) {
                    text = textBuf.toString()
                    think = thinkBuf.toString()
                }
                if (_liveDelta.value != text) _liveDelta.value = text
                if (_liveThinking.value != think) _liveThinking.value = think
            }

            val history = buildApiMessages(_mode.value, cfg.contentAuthorized)
            val turn = try {
                llm.complete(
                    cfg, history, allToolSchemas(),
                    onDelta = { d ->
                        synchronized(lock) { textBuf.append(d) }
                        publish()
                    },
                    onThinking = { t ->
                        synchronized(lock) { thinkBuf.append(t) }
                        publish()
                    }
                )
            } catch (e: LlmException) {
                agentRepository.appendMessage(ChatMessage.ROLE_ASSISTANT, userMessageFor(e))
                return
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                agentRepository.appendMessage(
                    ChatMessage.ROLE_ASSISTANT,
                    "请求 AI 服务时出错：${t.message ?: "未知错误"}。可在设置中关闭流式回复后重试。"
                )
                return
            }

            _liveDelta.value = ""
            _liveThinking.value = ""
            // 持久化格式：<think>思考</think>正文
            val stored = if (turn.thinking.isBlank()) turn.content
            else "${ThinkText.OPEN}${turn.thinking}${ThinkText.CLOSE}${turn.content}"
            val callsJson = JSONArray()
            turn.toolCalls.forEach { callsJson.put(it.toJson()) }
            agentRepository.appendMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = stored,
                toolCallsJson = if (turn.toolCalls.isEmpty()) "" else callsJson.toString()
            )

            if (turn.toolCalls.isEmpty()) return // 纯文本回复，轮次结束

            _phase.value = AgentPhase.EXECUTING_TOOL
            turn.toolCalls.forEach { call -> executeCall(call) }
            round++
        }
        agentRepository.appendMessage(
            ChatMessage.ROLE_ASSISTANT,
            "工具调用轮次已达上限。请确认以上结果，或把需求拆小后继续告诉我。"
        )
    }

    /** 本地注册表 + MCP 桥接工具的完整 schema 列表。 */
    private fun allToolSchemas(): List<JSONObject> =
        ToolRegistry.openApiSchemas() + McpBridge.schemas()

    private suspend fun executeCall(call: ToolCall) {
        // MCP 桥接路由
        if (McpBridge.isBridged(call.name)) {
            executeMcpCall(call)
            return
        }
        val tool = ToolRegistry.get(call.name)
        if (tool == null) {
            appendToolResult(
                call, CallStatus.FAILED,
                ToolResult.error(com.freefile.tools.ai.model.ErrorCode.INVALID_ARGS, "未知工具：${call.name}")
            )
            return
        }
        val args = try {
            JSONObject(call.arguments.ifEmpty { "{}" })
        } catch (t: Throwable) {
            appendToolResult(
                call, CallStatus.FAILED,
                ToolResult.error(com.freefile.tools.ai.model.ErrorCode.INVALID_ARGS, "参数不是合法 JSON：${t.message}")
            )
            return
        }

        val ctx = ToolContext(
            appContext = appContext,
            repository = FreeFileApp.instance.repository,
            agentRepository = agentRepository,
            gate = gate,
            undoManager = undoManager,
            userInstruction = lastInstruction,
            contentAuthorized = { AgentSettings.state.value.contentAuthorized },
            onProgress = { _progress.value = it },
            isCancelled = { activeJob?.isCancelled == true }
        )

        val result = try {
            tool.dispatch(args, ctx)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            ToolResult.error(com.freefile.tools.ai.model.ErrorCode.IO_ERROR, "工具执行异常：${t.message}")
        }
        val status = when {
            result.ok -> CallStatus.SUCCESS
            result.code == "CANCELLED" -> CallStatus.CANCELLED
            result.code == "UNSUPPORTED" -> CallStatus.UNSUPPORTED
            else -> CallStatus.FAILED
        }
        appendToolResult(call, status, result)
    }

    private suspend fun executeMcpCall(call: ToolCall) {
        val entry = McpBridge.get(call.name)
        if (entry == null) {
            appendToolResult(
                call, CallStatus.FAILED,
                ToolResult.error(com.freefile.tools.ai.model.ErrorCode.NOT_FOUND, "MCP 工具不可用：${call.name}")
            )
            return
        }
        val result = try {
            val args = JSONObject(call.arguments.ifEmpty { "{}" })
            val text = mcpClient.callTool(entry.server, entry.remoteTool, args)
            ToolResult.ok(JSONObject().put("text", text.take(8000)), text.take(200))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            ToolResult.error(com.freefile.tools.ai.model.ErrorCode.PLUGIN_FAILED, "MCP 调用失败：${t.message}")
        }
        appendToolResult(
            call,
            if (result.ok) CallStatus.SUCCESS else CallStatus.FAILED,
            result
        )
    }

    private suspend fun appendToolResult(call: ToolCall, status: CallStatus, result: ToolResult) {
        agentRepository.appendMessage(
            role = ChatMessage.ROLE_TOOL,
            content = result.toJson().toString(),
            toolCallId = call.id,
            toolName = call.name,
            callStatus = status.key
        )
    }

    // --------------------------------------------------------------- 撤销

    fun undo(onDone: (UndoReport?) -> Unit = {}) {
        scope.launch {
            val report = undoManager.undoLatest(
                FreeFileApp.instance.repository,
                agentRepository
            )
            if (report != null && report.affectedDir.isNotEmpty()) {
                com.freefile.tools.ai.tool.WriteSupport.notifyDirs(report.affectedDir)
            }
            onDone(report)
        }
    }

    // ------------------------------------------------------------- 消息装配

    /** 从持久化会话装配 API 消息；system 置顶、剥离思考段、避免孤儿 tool 消息。 */
    private suspend fun buildApiMessages(mode: AgentMode, contentAuthorized: Boolean): List<JSONObject> {
        val raw = agentRepository.messages.first()
        val retained = raw.takeLast(HISTORY_LIMIT).toMutableList()
        // 去掉开头孤儿：role=tool 没有对应 assistant；或 assistant 带 tool_calls 但首个结果丢失
        while (retained.isNotEmpty()) {
            val first = retained.first()
            when {
                first.role == ChatMessage.ROLE_TOOL -> retained.removeAt(0)
                first.role == ChatMessage.ROLE_ASSISTANT &&
                    first.toolCallsJson.isNotEmpty() &&
                    (retained.size == 1 || retained[1].role != ChatMessage.ROLE_TOOL) ->
                    retained.removeAt(0)
                else -> break
            }
        }

        val out = ArrayList<JSONObject>()
        out.add(ChatMessage.system(Prompts.systemPrompt(mode, contentAuthorized)).toJson())
        retained.forEach { e ->
            when (e.role) {
                ChatMessage.ROLE_ASSISTANT -> {
                    val calls = parseToolCalls(e.toolCallsJson)
                    out.add(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = ThinkText.strip(e.content),
                            toolCalls = calls
                        ).toJson()
                    )
                }
                ChatMessage.ROLE_TOOL -> out.add(
                    ChatMessage(
                        role = ChatMessage.ROLE_TOOL,
                        content = e.content,
                        toolCallId = e.toolCallId,
                        name = e.toolName
                    ).toJson()
                )
                else -> out.add(ChatMessage(e.role, e.content).toJson())
            }
        }
        return out
    }

    private fun parseToolCalls(json: String): List<ToolCall>? {
        if (json.isEmpty()) return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { ToolCall.from(arr.optJSONObject(it) ?: return@mapNotNull null) }
                .ifEmpty { null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun userMessageFor(e: LlmException): String = when (e.kind) {
        LlmException.Kind.AUTH ->
            "API Key 无效或未授权（401/403）。请到 Agent 设置检查服务商、API 地址与密钥。"
        LlmException.Kind.RATE_LIMITED ->
            "请求被限流或账户额度不足（429），请稍后再试或更换账户。"
        LlmException.Kind.SERVER ->
            "AI 服务暂时不可用（5xx），请稍后重试。"
        LlmException.Kind.NETWORK ->
            "网络连接失败：${e.userMessage}。请检查网络与 API 地址。"
        LlmException.Kind.BAD_RESPONSE ->
            "AI 服务返回格式异常：${e.userMessage}。可尝试在设置中关闭流式回复。"
        LlmException.Kind.CANCELLED -> "已取消。"
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 8
        private const val HISTORY_LIMIT = 40
    }
}
