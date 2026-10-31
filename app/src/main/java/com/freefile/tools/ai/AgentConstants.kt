package com.freefile.tools.ai

/**
 * Agent 模块统一常量：日志 TAG、Intent Action/Extra、命名空间前缀。
 * 路由、深层链接、日志 TAG、配置项均使用 com.freefile.tools.ai 前缀。
 */
object AgentConstants {
    const val TAG = "FreeFileAI"

    /** 启动 Agent 聊天页的 Action（预留深层链接/外部调用）。 */
    const val ACTION_CHAT = "com.freefile.tools.ai.action.CHAT"

    /** 入参：预选文件/目录路径（ArrayList<String>）。 */
    const val EXTRA_PATHS = "com.freefile.tools.ai.extra.PATHS"

    /** 入参：预填指令（String）。 */
    const val EXTRA_PROMPT = "com.freefile.tools.ai.extra.PROMPT"

    /** 独立 Room 数据库名（不与 freefile.db 混用）。 */
    const val AGENT_DB_NAME = "freefile_agent.db"

    /** Agent 设置 DataStore 名。 */
    const val AGENT_SETTINGS_NAME = "freefile_agent_settings"
}
