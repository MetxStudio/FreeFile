package com.freefile.tools.ai.model

/**
 * Agent 模式（对用户可见，不静默切换）：
 * - [AUTO] 文件管理优先，明确通用请求时自然扩展；
 * - [FILE] 聚焦文件操作，通用问题简短回答并引导回文件；
 * - [GENERAL] 允许完整聊天/创作/问答，但文件写操作规则不变。
 */
enum class AgentMode(val key: String) {
    AUTO("auto"),
    FILE("file"),
    GENERAL("general");

    companion object {
        fun from(key: String?): AgentMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}
