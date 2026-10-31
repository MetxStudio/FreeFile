package com.freefile.tools.ai.model

/**
 * Agent 运行阶段（UI 据此渲染输入栏/进度/遮罩）。
 */
enum class AgentPhase {
    /** 初始化中。 */
    BOOTSTRAPPING,

    /** 未配置可用端点，展示引导设置。 */
    NEEDS_SETUP,

    /** 空闲可输入。 */
    IDLE,

    /** 模型流式回复中。 */
    STREAMING,

    /** 工具执行中（含只读查询与已批准写操作）。 */
    EXECUTING_TOOL,

    /** 存在待确认写操作，确认卡锁定。 */
    AWAITING_CONFIRMATION
}

/** 工具调用在会话中的展示状态。 */
enum class CallStatus(val key: String) {
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
    AWAITING("awaiting"),
    CANCELLED("cancelled"),
    UNSUPPORTED("unsupported");

    companion object {
        fun from(key: String?): CallStatus =
            entries.firstOrNull { it.key == key } ?: RUNNING
    }
}

/** 工具执行进度（写操作/压缩解压进度卡）。 */
data class ToolProgress(
    val toolName: String,
    val filesDone: Int = 0,
    val totalFiles: Int = 0,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val current: String = ""
)
