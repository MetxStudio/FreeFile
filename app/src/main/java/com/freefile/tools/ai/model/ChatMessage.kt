package com.freefile.tools.ai.model

import org.json.JSONArray
import org.json.JSONObject

/** OpenAI 兼容的函数调用。 */
data class ToolCall(
    val id: String,
    val name: String,
    /** 模型给出的原始参数 JSON 字符串（可能为空/不合法，执行前校验）。 */
    val arguments: String
) {
    fun toJson(): JSONObject {
        val fn = JSONObject()
            .put("name", name)
            .put("arguments", arguments)
        return JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", fn)
    }

    companion object {
        /** 解析流式聚合后的单个 tool_call（function.arguments 已拼完整）。 */
        fun from(o: JSONObject): ToolCall? {
            val fn = o.optJSONObject("function") ?: return null
            val id = o.optString("id")
            val name = fn.optString("name")
            if (id.isBlank() || name.isBlank()) return null
            return ToolCall(id, name, fn.optString("arguments", ""))
        }
    }
}

/** OpenAI Chat Completions 消息。 */
data class ChatMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String = "",
    val name: String = ""
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("role", role)
        if (role == "assistant" && !toolCalls.isNullOrEmpty()) {
            val arr = JSONArray()
            toolCalls.forEach { arr.put(it.toJson()) }
            o.put("content", content)
            o.put("tool_calls", arr)
        } else {
            o.put("content", content)
        }
        if (role == "tool") o.put("tool_call_id", toolCallId)
        if (name.isNotBlank()) o.put("name", name)
        return o
    }

    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        fun system(content: String) = ChatMessage(ROLE_SYSTEM, content)
        fun user(content: String) = ChatMessage(ROLE_USER, content)
    }
}

/** 一次模型回复：文本增量、深度思考与工具调用。 */
data class AssistantTurn(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String,
    /** 深度思考文本（reasoning_content 或 <think> 标签内），为空表示无。 */
    val thinking: String = ""
)

object ThinkText {

    const val OPEN = "<think>"
    const val CLOSE = "</think>"

    /** 拆分 thinking 与正文（持久化格式：<think>…</think>正文）。 */
    fun split(content: String): Pair<String, String> {
        val open = content.indexOf(OPEN)
        if (open < 0) return "" to content
        val close = content.indexOf(CLOSE, open)
        val think = if (close > open) content.substring(open + OPEN.length, close).trim()
        else content.substring(open + OPEN.length).trim()
        val rest = if (close > open) content.substring(close + CLOSE.length).trim() else ""
        return think to rest
    }

    /** 发送 API 前剥离思考段，节省 token 且避免部分模型混淆。 */
    fun strip(content: String): String {
        if (!content.contains(OPEN)) return content
        return split(content).second
    }
}
