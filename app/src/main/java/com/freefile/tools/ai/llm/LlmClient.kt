package com.freefile.tools.ai.llm

import com.freefile.tools.ai.data.AgentSettingsData
import com.freefile.tools.ai.model.AssistantTurn
import com.freefile.tools.ai.model.ThinkText
import com.freefile.tools.ai.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** LLM 调用失败，[userMessage] 为可直接展示的本地化错误。 */
class LlmException(
    val kind: Kind,
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause) {
    enum class Kind { AUTH, RATE_LIMITED, SERVER, NETWORK, BAD_RESPONSE, CANCELLED }
}

/** OpenAI Chat Completions 兼容客户端（含深度思考与 HTTP 日志）。 */
interface LlmClient {

    /** 轻量鉴权测试：GET {baseUrl}/models。成功返回 null，失败返回可展示的错误信息。 */
    suspend fun testConnection(cfg: AgentSettingsData): String?

    /**
     * @param onDelta 正文增量回调（非流式时回调一次完整内容）。
     * @param onThinking 深度思考增量回调。
     */
    suspend fun complete(
        cfg: AgentSettingsData,
        messages: List<JSONObject>,
        tools: List<JSONObject>?,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {}
    ): AssistantTurn
}

class OpenAiCompatibleClient : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun errText(t: Throwable): String = t.message ?: t.javaClass.simpleName

    override suspend fun testConnection(cfg: AgentSettingsData): String? =
        withContext(Dispatchers.IO) {
            val url = cfg.baseUrl.trim().trimEnd('/') + "/models"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .get()
                .build()
            HttpLog.request("LLM", "GET", url, null)
            val started = System.currentTimeMillis()
            val response = try {
                http.newCall(request).execute()
            } catch (t: Throwable) {
                HttpLog.response("LLM", "GET", url, "ERR:${errText(t)}", elapsed(started), null)
                return@withContext "网络错误：${errText(t)}"
            }
            response.use {
                val body = runCatching { it.body?.string() }.getOrNull().orEmpty()
                HttpLog.response("LLM", "GET", url, "HTTP ${it.code}", elapsed(started), body)
                if (it.isSuccessful) return@use null
                val serverMsg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                serverMsg.ifEmpty { "HTTP ${it.code}" }
            }
        }

    override suspend fun complete(
        cfg: AgentSettingsData,
        messages: List<JSONObject>,
        tools: List<JSONObject>?,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit
    ): AssistantTurn {
        val endpoint = cfg.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val payload = JSONObject()
            .put("model", cfg.model)
            .put("messages", JSONArray(messages))
            .put("temperature", 0.3)
            .put("stream", cfg.useStream)
        if (!tools.isNullOrEmpty()) {
            payload.put("tools", JSONArray(tools))
            payload.put("tool_choice", "auto")
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Accept", if (cfg.useStream) "text/event-stream" else "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        HttpLog.request("LLM", "POST", endpoint, payload.toString())

        return if (cfg.useStream) {
            runCatching { stream(request, onDelta, onThinking) }.getOrElse { e ->
                if (e is LlmException) throw e
                // 流式失败（部分网关 SSE 兼容性差）→ 降级非流式一次
                if (e is kotlinx.coroutines.CancellationException) throw e
                nonStream(request, onDelta, onThinking)
            }
        } else {
            nonStream(request, onDelta, onThinking)
        }
    }

    private fun elapsed(started: Long): Long = System.currentTimeMillis() - started

    // ------------------------------------------------------------- 非流式

    private suspend fun nonStream(
        request: Request,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit
    ): AssistantTurn = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val response = try {
            http.newCall(request).execute()
        } catch (t: Throwable) {
            HttpLog.response("LLM", "POST", request.url.toString(), "ERR:${errText(t)}", elapsed(started), null)
            throw LlmException(LlmException.Kind.NETWORK, errText(t), t)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            HttpLog.response("LLM", "POST", request.url.toString(), "HTTP ${it.code}", elapsed(started), body)
            ensureOk(it, body)
            try {
                parseMessage(JSONObject(body), onDelta, onThinking)
            } catch (e: LlmException) {
                throw e
            } catch (t: Throwable) {
                throw LlmException(LlmException.Kind.BAD_RESPONSE, errText(t), t)
            }
        }
    }

    // --------------------------------------------------------------- 流式

    private suspend fun stream(
        request: Request,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit
    ): AssistantTurn = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        // tool_calls 按 index 聚合
        val ids = HashMap<Int, String>()
        val names = HashMap<Int, String>()
        val args = HashMap<Int, StringBuilder>()
        val content = StringBuilder()
        val thinking = StringBuilder()
        var inThink = false
        var pendingTag = StringBuilder()
        var finishReason = ""
        var completed = false
        val started = System.currentTimeMillis()
        val url = request.url.toString()

        fun feed(text: String, sink: StringBuilder, cb: (String) -> Unit) {
            sink.append(text)
            cb(text)
        }

        /** 防止 <think> 标签被拆分：末尾最多缓存 7 字符（"<think>" 长度）。 */
        fun thinkHoldback(s: String): Int {
            val maxLen = ThinkText.OPEN.length
            if (s.length >= maxLen) return 0
            // 末尾片段是否可能成为 "<think>" 或 "</think>" 的前缀
            for (k in 1..s.length.coerceAtMost(maxLen)) {
                val tail = s.substring(s.length - k)
                if ("<think>".startsWith(tail) || ThinkText.CLOSE.startsWith(tail)) return k
            }
            return 0
        }

        /** 把正文增量按 <think>/<\/think> 状态机分流到 thinking / content。 */
        fun route(delta: String) {
            var rest = delta
            // 处理跨 chunk 拆分的标签
            while (rest.isNotEmpty()) {
                if (pendingTag.isNotEmpty()) {
                    pendingTag.append(rest)
                    rest = pendingTag.toString()
                    pendingTag = StringBuilder()
                }
                if (inThink) {
                    val close = rest.indexOf(ThinkText.CLOSE)
                    if (close >= 0) {
                        feed(rest.substring(0, close), thinking, onThinking)
                        rest = rest.substring(close + ThinkText.CLOSE.length)
                        inThink = false
                    } else {
                        // 可能是半个闭合标签，缓存最后 len-1 个字符
                        val keep = thinkHoldback(rest)
                        val emit = rest.substring(0, rest.length - keep)
                        if (emit.isNotEmpty()) feed(emit, thinking, onThinking)
                        pendingTag = StringBuilder(rest.substring(rest.length - keep))
                        return
                    }
                } else {
                    val open = rest.indexOf(ThinkText.OPEN)
                    if (open >= 0) {
                        if (open > 0) feed(rest.substring(0, open), content, onDelta)
                        rest = rest.substring(open + ThinkText.OPEN.length)
                        inThink = true
                    } else {
                        val keep = thinkHoldback(rest)
                        val emit = rest.substring(0, rest.length - keep)
                        if (emit.isNotEmpty()) feed(emit, content, onDelta)
                        pendingTag = StringBuilder(rest.substring(rest.length - keep))
                        return
                    }
                }
            }
        }

        fun buildTurn(): AssistantTurn {
            // 流结束时把可能残留的标签片段按当前状态归位，避免最后几个字符丢失
            if (pendingTag.isNotEmpty()) {
                val tail = pendingTag.toString()
                pendingTag = StringBuilder()
                if (inThink) feed(tail, thinking, onThinking) else feed(tail, content, onDelta)
            }
            val calls = ids.keys.sorted().mapNotNull { idx ->
                val id = ids[idx] ?: return@mapNotNull null
                val name = names[idx] ?: return@mapNotNull null
                ToolCall(id, name, args[idx]?.toString().orEmpty())
            }
            return AssistantTurn(content.toString(), calls, finishReason, thinking.toString())
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.trim() == "[DONE]") {
                    if (!completed) {
                        completed = true
                        HttpLog.response("LLM", "POST", url, "SSE done", elapsed(started), "stream closed")
                        if (cont.isActive) cont.resume(buildTurn())
                    }
                    eventSource.cancel()
                    return
                }
                try {
                    val chunk = JSONObject(data)
                    val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return
                    choice.optString("finish_reason").takeIf { it.isNotEmpty() }?.let { finishReason = it }
                    val delta = choice.optJSONObject("delta") ?: return

                    // 推理模型的独立思考通道：兼容 reasoning_content / reasoning / thinking 三种字段
                    val rc = delta.optString("reasoning_content", "")
                        .ifEmpty { delta.optString("reasoning", "") }
                        .ifEmpty { delta.optString("thinking", "") }
                    if (rc.isNotEmpty()) feed(rc, thinking, onThinking)
                    // 通用 <think> 标签通道
                    delta.optString("content", "").takeIf { it.isNotEmpty() }?.let { route(it) }

                    val tcs = delta.optJSONArray("tool_calls")
                    if (tcs != null) {
                        for (i in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(i) ?: continue
                            val idx = tc.optInt("index", i)
                            tc.optString("id").takeIf { it.isNotEmpty() }?.let { ids[idx] = it }
                            tc.optJSONObject("function")?.let { fn ->
                                fn.optString("name").takeIf { it.isNotEmpty() }?.let { names[idx] = it }
                                fn.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                                    args.getOrPut(idx) { StringBuilder() }.append(it)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // 单个 chunk 解析失败不中断，等待后续或 [DONE]
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed && cont.isActive) {
                    completed = true
                    HttpLog.response("LLM", "POST", url, "SSE closed", elapsed(started), "stream closed")
                    cont.resume(buildTurn())
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed || !cont.isActive) return
                completed = true
                if (t is kotlinx.coroutines.CancellationException) {
                    HttpLog.response("LLM", "POST", url, "CANCELLED", elapsed(started), null)
                    cont.resumeWithException(LlmException(LlmException.Kind.CANCELLED, "cancelled"))
                    return
                }
                val body = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                HttpLog.response("LLM", "POST", url, "ERR:${t?.message ?: response?.code}", elapsed(started), body)
                val mapped = response?.let { mapHttpError(it.code, body, t) }
                cont.resumeWithException(
                    mapped ?: LlmException(LlmException.Kind.NETWORK, t?.message ?: "stream error", t)
                )
            }
        }

        val source = EventSources.createFactory(http).newEventSource(request, listener)
        cont.invokeOnCancellation { source.cancel() }
    }

    // --------------------------------------------------------------- 解析

    private fun parseMessage(
        body: JSONObject,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit
    ): AssistantTurn {
        val choice = body.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "choices 缺失")
        val msg = choice.optJSONObject("message")
            ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "message 缺失")
        var think = msg.optString("reasoning_content", "")
            .ifEmpty { msg.optString("reasoning", "") }
            .ifEmpty { msg.optString("thinking", "") }
        val raw = msg.optString("content", "")
        val (tagThink, visible) = ThinkText.split(raw)
        if (think.isEmpty()) think = tagThink
        if (visible.isNotEmpty()) onDelta(visible)
        if (think.isNotEmpty()) onThinking(think)
        val calls = ArrayList<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                ToolCall.from(arr.optJSONObject(i) ?: continue)?.let { calls.add(it) }
            }
        }
        return AssistantTurn(visible, calls, choice.optString("finish_reason"), think)
    }

    private fun ensureOk(response: Response, body: String) {
        if (response.isSuccessful) return
        throw mapHttpError(response.code, body, null)
            ?: LlmException(LlmException.Kind.SERVER, "HTTP ${response.code}")
    }

    private fun mapHttpError(code: Int, body: String, cause: Throwable?): LlmException? {
        // 尝试读取服务商错误信息
        val serverMsg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return when {
            code == 401 || code == 403 ->
                LlmException(LlmException.Kind.AUTH, serverMsg.ifEmpty { "HTTP 401" }, cause)
            code == 429 ->
                LlmException(LlmException.Kind.RATE_LIMITED, serverMsg.ifEmpty { "HTTP 429" }, cause)
            code in 500..599 ->
                LlmException(LlmException.Kind.SERVER, serverMsg.ifEmpty { "HTTP $code" }, cause)
            code in 200..299 -> null
            else -> LlmException(LlmException.Kind.BAD_RESPONSE, serverMsg.ifEmpty { "HTTP $code" }, cause)
        }
    }
}
