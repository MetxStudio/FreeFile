package com.freefile.tools.ai.llm

/** 服务商预设：选择后自动填充 Base URL 与推荐模型，密钥始终由用户自填。 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String
) {
    companion object {
        val PRESETS = listOf(
            ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            ProviderPreset("deepseek", "DeepSeek 深度求索", "https://api.deepseek.com", "deepseek-chat"),
            ProviderPreset("moonshot", "Moonshot Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            ProviderPreset(
                "qwen",
                "通义千问（DashScope 兼容模式）",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus"
            ),
            ProviderPreset("custom", "自定义 OpenAI 兼容端点", "", "")
        )

        fun byId(id: String): ProviderPreset =
            PRESETS.firstOrNull { it.id == id } ?: PRESETS.last()
    }
}
