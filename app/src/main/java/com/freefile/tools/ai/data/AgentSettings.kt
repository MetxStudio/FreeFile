package com.freefile.tools.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.freefile.tools.ai.AgentConstants
import com.freefile.tools.ai.mcp.McpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.agentDataStore: DataStore<Preferences> by
    preferencesDataStore(name = AgentConstants.AGENT_SETTINGS_NAME)

/** Agent 设置快照。 */
data class AgentSettingsData(
    /** 服务商预设 id；custom 表示完全自定义。 */
    val providerId: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 侧拉栏入口可见（默认可见）。 */
    val entryVisible: Boolean = true,
    /** 允许读取文件正文并发送到用户配置的端点（默认关闭）。 */
    val contentAuthorized: Boolean = false,
    /** 聊天中显示工具调用详情卡片。 */
    val showToolDetails: Boolean = true,
    /** 使用 SSE 流式回复。 */
    val useStream: Boolean = true,
    /** 批量操作强制汇总确认阈值。 */
    val batchThreshold: Int = 10,
    /** 是否已看过能力/隐私引导。 */
    val onboardingAccepted: Boolean = false,
    /** 始终允许（跳过确认卡）的写工具名集合。 */
    val alwaysAllowed: Set<String> = emptySet(),
    /** MCP 服务器配置（JSON 字符串列表）。 */
    val mcpServers: List<McpServerConfig> = emptyList(),
    /** 记录 HTTP 请求日志（内存 + 本地文件）。 */
    val httpLogEnabled: Boolean = true,
    /** UI 显示深度思考折叠块。 */
    val showThinking: Boolean = true
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * Agent 独立设置中心（DataStore 持久化，StateFlow 即时生效）。
 * 与主 AppSettings 隔离：不改动现有设置文件，配置项统一 freefile_agent 前缀。
 */
object AgentSettings {

    private val KEY_PROVIDER = stringPreferencesKey("provider_id")
    private val KEY_BASE_URL = stringPreferencesKey("base_url")
    private val KEY_API_KEY = stringPreferencesKey("api_key")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_ENTRY_VISIBLE = booleanPreferencesKey("entry_visible")
    private val KEY_CONTENT_AUTH = booleanPreferencesKey("content_authorized")
    private val KEY_SHOW_DETAILS = booleanPreferencesKey("show_tool_details")
    private val KEY_USE_STREAM = booleanPreferencesKey("use_stream")
    private val KEY_BATCH_THRESHOLD = intPreferencesKey("batch_threshold")
    private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_accepted")
    private val KEY_ALWAYS_ALLOWED = stringSetPreferencesKey("always_allowed_tools")
    private val KEY_MCP_SERVERS = stringSetPreferencesKey("mcp_servers")
    private val KEY_HTTP_LOG = booleanPreferencesKey("http_log_enabled")
    private val KEY_SHOW_THINKING = booleanPreferencesKey("show_thinking")

    private lateinit var appContext: Context

    private val _state = MutableStateFlow(AgentSettingsData())
    val state: StateFlow<AgentSettingsData> = _state.asStateFlow()

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        runBlocking {
            _state.value = read(appContext.agentDataStore.data.first())
        }
        CoroutineScope(Dispatchers.Default).launch {
            appContext.agentDataStore.data.map { read(it) }.collect { _state.value = it }
        }
    }

    /** 未初始化时的同步兜底（主界面抽屉可能早于 Agent 页面访问可见性）。 */
    fun snapshot(context: Context): AgentSettingsData {
        if (!this::appContext.isInitialized) init(context)
        return _state.value
    }

    private fun read(p: Preferences): AgentSettingsData = AgentSettingsData(
        providerId = p[KEY_PROVIDER] ?: "",
        baseUrl = p[KEY_BASE_URL] ?: "",
        apiKey = p[KEY_API_KEY] ?: "",
        model = p[KEY_MODEL] ?: "",
        entryVisible = p[KEY_ENTRY_VISIBLE] ?: true,
        contentAuthorized = p[KEY_CONTENT_AUTH] ?: false,
        showToolDetails = p[KEY_SHOW_DETAILS] ?: true,
        useStream = p[KEY_USE_STREAM] ?: true,
        batchThreshold = (p[KEY_BATCH_THRESHOLD] ?: 10).coerceIn(1, 100),
        onboardingAccepted = p[KEY_ONBOARDING] ?: false,
        alwaysAllowed = p[KEY_ALWAYS_ALLOWED] ?: emptySet(),
        mcpServers = (p[KEY_MCP_SERVERS] ?: emptySet())
            .mapNotNull { com.freefile.tools.ai.mcp.McpServerConfig.from(it) },
        httpLogEnabled = p[KEY_HTTP_LOG] ?: true,
        showThinking = p[KEY_SHOW_THINKING] ?: true
    )

    /** 批量更新连接相关配置。 */
    suspend fun updateConnection(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ) = appContext.agentDataStore.edit { p ->
        p[KEY_PROVIDER] = providerId
        p[KEY_BASE_URL] = baseUrl.trim().trimEnd('/')
        p[KEY_API_KEY] = apiKey.trim()
        p[KEY_MODEL] = model.trim()
    }

    suspend fun setEntryVisible(v: Boolean) = put { it[KEY_ENTRY_VISIBLE] = v }
    suspend fun setContentAuthorized(v: Boolean) = put { it[KEY_CONTENT_AUTH] = v }
    suspend fun setShowToolDetails(v: Boolean) = put { it[KEY_SHOW_DETAILS] = v }
    suspend fun setUseStream(v: Boolean) = put { it[KEY_USE_STREAM] = v }
    suspend fun setBatchThreshold(v: Int) = put { it[KEY_BATCH_THRESHOLD] = v.coerceIn(1, 100) }
    suspend fun setOnboardingAccepted(v: Boolean) = put { it[KEY_ONBOARDING] = v }

    /** 切换某工具的「始终允许」状态。 */
    suspend fun toggleAlwaysAllowed(toolName: String, allowed: Boolean) = put { p ->
        val cur = p[KEY_ALWAYS_ALLOWED] ?: emptySet()
        p[KEY_ALWAYS_ALLOWED] = if (allowed) cur + toolName else cur - toolName
    }

    /** 保存 MCP 服务器列表（全量替换）。 */
    suspend fun setMcpServers(servers: List<McpServerConfig>) = put { p ->
        p[KEY_MCP_SERVERS] = servers.map { it.toJson() }.toSet()
    }

    suspend fun setHttpLogEnabled(v: Boolean) {
        enabledStateSync(v)
        put { it[KEY_HTTP_LOG] = v }
    }

    suspend fun setShowThinking(v: Boolean) = put { it[KEY_SHOW_THINKING] = v }

    /** HttpLog.enabled 是同步读取的 @Volatile 字段，保存后立即生效。 */
    private fun enabledStateSync(v: Boolean) {
        com.freefile.tools.ai.llm.HttpLog.enabled = v
    }

    private suspend inline fun put(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        appContext.agentDataStore.edit { block(it) }
    }
}
