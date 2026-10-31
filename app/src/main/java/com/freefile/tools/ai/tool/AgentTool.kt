package com.freefile.tools.ai.tool

import android.content.Context
import com.freefile.data.repo.Repository
import com.freefile.tools.ai.data.AgentRepository
import com.freefile.tools.ai.model.AgentMode
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolProgress
import com.freefile.tools.ai.model.ToolResult
import com.freefile.tools.ai.security.ConfirmationGate
import com.freefile.tools.ai.security.UndoManager
import org.json.JSONObject

/**
 * 工具运行上下文（每次用户轮次重建，指令用于审计日志）。
 */
class ToolContext(
    val appContext: Context,
    val repository: Repository,
    val agentRepository: AgentRepository,
    val gate: ConfirmationGate,
    val undoManager: UndoManager,
    val userInstruction: String,
    val contentAuthorized: () -> Boolean,
    val onProgress: (ToolProgress) -> Unit,
    val isCancelled: () -> Boolean = { false }
)

/**
 * MCP 风格工具契约：声明 JSON Schema + 权限元数据，执行只产出 [ToolResult]。
 * 文件事实的唯一出口是工具返回，宿主负责写操作的强制确认。
 */
interface AgentTool {
    val name: String
    val description: String
    val permission: ToolPermission
    val write: Boolean
    val undoable: Boolean

    /** MVP 是否开放；false 时注册表自动返回 UNSUPPORTED，但仍向模型暴露协议。 */
    val mvp: Boolean get() = true

    /** OpenAI function.parameters JSON Schema。 */
    fun parameters(): JSONObject

    /** 组装 OpenAI tools 数组中的单个 function 描述。 */
    fun schema(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters())
        )

    suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult
}

/**
 * 工具注册表：单点查找、汇总 API schema、MVP 开关。
 */
object ToolRegistry {

    private val tools = LinkedHashMap<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AgentTool? = tools[name]

    /** 发送给模型的 tools 数组（按注册顺序）。 */
    fun openApiSchemas(): List<JSONObject> = tools.values.map { it.schema() }

    /** 二期工具的统一占位实现：协议可见，执行即 UNSUPPORTED。 */
    class Unavailable(
        override val name: String,
        private val desc: String,
        private val params: JSONObject
    ) : AgentTool {
        override val description: String = "$desc（当前版本暂不可用，请如实告知用户该能力将在后续版本提供，不要假装执行）"
        override val permission = ToolPermission.WRITE
        override val write = false
        override val undoable = false
        override val mvp = false

        override fun parameters(): JSONObject = params

        override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
            ToolResult.error(
                com.freefile.tools.ai.model.ErrorCode.UNSUPPORTED,
                "工具 $name 在当前版本暂不可用"
            )
    }
}

/** 结果构造小工具：列表统一截断，避免撑爆上下文。 */
object ToolResults {
    const val LIST_LIMIT = 100

    fun baseList(
        items: List<JSONObject>,
        total: Int,
        extra: JSONObject.() -> Unit = {}
    ): JSONObject {
        val capped = items.take(LIST_LIMIT)
        return JSONObject()
            .put("total", total)
            .put("returned", capped.size)
            .put("has_more", total > capped.size)
            .put("items", org.json.JSONArray(capped))
            .apply(extra)
    }
}
