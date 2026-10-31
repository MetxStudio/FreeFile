package com.freefile.tools.ai.mcp

import com.freefile.tools.ai.llm.HttpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MCP 服务器配置（以 JSON 字符串形式存于 DataStore stringSet）。
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("enabled", enabled).toString()

    companion object {
        fun from(s: String): McpServerConfig? = runCatching {
            val o = JSONObject(s)
            McpServerConfig(
                id = o.optString("id"),
                name = o.optString("name"),
                url = o.optString("url"),
                enabled = o.optBoolean("enabled", true)
            )
        }.getOrNull()?.takeIf { it.id.isNotEmpty() && it.url.isNotBlank() }
    }
}

/**
 * 极简 MCP 客户端（Streamable HTTP / JSON-RPC 2.0）：
 * initialize → tools/list 缓存 → tools/call。
 * 兼容返回 application/json 或 text/event-stream 的服务器。
 */
class McpClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    class McpException(message: String) : Exception(message)

    /** 会话级状态：session id 由 initialize 响应头携带。 */
    private data class Session(val sessionId: String?)

    private suspend fun rpc(
        server: McpServerConfig,
        session: Session?,
        method: String,
        params: JSONObject?,
        isNotification: Boolean = false
    ): JSONObject? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        body.put("id", nextId())
        if (params != null) body.put("params", params)
        val request = Request.Builder()
            .url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .apply { session?.sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        HttpLog.request("MCP:${server.name}", "POST", server.url, body.toString())
        val started = System.currentTimeMillis()
        val resp = try {
            http.newCall(request).execute()
        } catch (t: Throwable) {
            HttpLog.response("MCP:${server.name}", "POST", server.url, "ERR:${t.message ?: t.javaClass.simpleName}",
                System.currentTimeMillis() - started, null)
            throw McpException("无法连接 MCP 服务器：${t.message ?: t.javaClass.simpleName}")
        }
        resp.use { r ->
            val raw = r.body?.string().orEmpty()
            val sid = r.header("Mcp-Session-Id")
            HttpLog.response("MCP:${server.name}", "POST", server.url, "HTTP ${r.code}",
                System.currentTimeMillis() - started, raw)
            if (!r.isSuccessful) {
                throw McpException("MCP HTTP ${r.code}：${raw.take(200)}")
            }
            if (isNotification) return@use null
            val payload = extractJson(raw)
                ?: throw McpException("MCP 响应格式无法解析：${raw.take(200)}")
            if (payload.optJSONObject("error") != null) {
                throw McpException("MCP 错误：" + payload.optJSONObject("error")?.optString("message"))
            }
            if (sid != null) payload.put("_session", sid)
            payload
        }
    }

    /** 兼容纯 JSON 与 SSE（data: 行）两种响应封装。 */
    private fun extractJson(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
        // SSE：取最后一条 data: JSON
        var found: JSONObject? = null
        trimmed.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.startsWith("data:")) {
                val data = l.removePrefix("data:").trim()
                if (data.isNotEmpty() && data != "[DONE]") {
                    runCatching { JSONObject(data) }.getOrNull()?.let { found = it }
                }
            }
        }
        return found
    }

    private var idCounter = 0L
    @Synchronized
    private fun nextId(): Long = ++idCounter

    // ------------------------------------------------------- 对外能力

    /** 工具清单（name/description/inputSchema）。 */
    suspend fun listTools(server: McpServerConfig): List<JSONObject> {
        val initParams = JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", "FreeFile-Agent").put("version", "1.0")
            )
        val initResp = rpc(server, null, "initialize", initParams) ?: return emptyList()
        val session = Session(initResp.optString("_session").ifEmpty { null })
        runCatching { rpc(server, session, "notifications/initialized", null, isNotification = true) }

        val resp = rpc(server, session, "tools/list", JSONObject()) ?: return emptyList()
        val tools = resp.optJSONObject("result")?.optJSONArray("tools") ?: return emptyList()
        return (0 until tools.length()).mapNotNull { tools.optJSONObject(it) }
    }

    /** 调用远端工具，返回文本聚合结果。 */
    suspend fun callTool(server: McpServerConfig, toolName: String, args: JSONObject): String {
        val params = JSONObject()
            .put("name", toolName)
            .put("arguments", args)
        val resp = rpc(server, null, "tools/call", params)
            ?: throw McpException("MCP 无响应")
        val result = resp.optJSONObject("result") ?: throw McpException("MCP 结果缺失")
        if (result.optBoolean("isError", false)) {
            throw McpException("MCP 工具执行失败：" + result.optJSONArray("content")?.optJSONObject(0)
                ?.optString("text", "").orEmpty().take(300))
        }
        val content = result.optJSONArray("content") ?: return result.toString()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.optString("type", "text")) {
                "text" -> sb.append(c.optString("text")).append('\n')
                "resource" -> c.optJSONObject("resource")?.let {
                    sb.append(it.optString("text", it.optString("uri"))).append('\n')
                }
            }
        }
        return sb.toString().trim().ifEmpty { result.toString() }
    }

    /** 连通性测试：成功返回工具数量。 */
    suspend fun test(server: McpServerConfig): Int = listTools(server).size
}

/**
 * MCP 工具桥：把各服务器的工具映射为 mcp_<safe>_<tool> 暴露给模型，调用时路由回 MCP。
 */
object McpBridge {

    private const val PREFIX = "mcp_"

    data class Entry(val schema: JSONObject, val server: McpServerConfig, val remoteTool: String)

    /** key: 桥接工具名。 */
    @Volatile
    private var cache: Map<String, Entry> = emptyMap()

    /** 刷新所有启用服务器的工具缓存；返回加载摘要（name → 工具数/错误）。 */
    suspend fun refresh(servers: List<McpServerConfig>, client: McpClient): Map<String, String> {
        val summary = LinkedHashMap<String, String>()
        val map = LinkedHashMap<String, Entry>()
        val client2 = client
        servers.filter { it.enabled }.forEach { s ->
            try {
                val tools = client2.listTools(s)
                tools.forEach { t ->
                    val remote = t.optString("name")
                    if (remote.isEmpty()) return@forEach
                    val bridged = bridgedName(s, remote)
                    map[bridged] = Entry(wrapSchema(bridged, s, t), s, remote)
                }
                summary[s.name] = "已连接 ${tools.size} 个工具"
            } catch (t: Throwable) {
                summary[s.name] = "连接失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
        cache = map
        return summary
    }

    fun schemas(): List<JSONObject> = cache.values.map { it.schema }

    fun get(bridgedName: String): Entry? = cache[bridgedName]

    fun isBridged(name: String): Boolean = name.startsWith(PREFIX) && cache.containsKey(name)

    private fun bridgedName(s: McpServerConfig, remote: String): String {
        val safeServer = s.name.map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")
        val safeTool = remote.map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }
            .joinToString("")
        return (PREFIX + safeServer + "_" + safeTool).take(64)
    }

    /** 桥接描述注明来源服务器，schema 直接复用远端 inputSchema。 */
    private fun wrapSchema(bridged: String, s: McpServerConfig, remoteTool: JSONObject): JSONObject {
        val desc = "[MCP:${s.name}] " + remoteTool.optString("description", "MCP 服务器工具")
        val params = remoteTool.optJSONObject("inputSchema")
            ?: JSONObject().put("type", "object").put("properties", JSONObject())
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", bridged)
                    .put("description", desc)
                    .put("parameters", params)
            )
    }
}
