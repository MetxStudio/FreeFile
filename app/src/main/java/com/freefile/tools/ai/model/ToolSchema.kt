package com.freefile.tools.ai.model

import org.json.JSONObject

/** 工具权限级别（决定执行策略与确认强度）。 */
enum class ToolPermission {
    /** 只读：搜索/列目录/信息。 */
    READ,

    /** 敏感只读：读取文件正文发送到外部模型，需用户开关授权。 */
    READ_SENSITIVE,

    /** 只读但会离开应用：打开方式。 */
    EXTERNAL_READ,

    /** 普通写：预览 + 确认 + 可撤销。 */
    WRITE,

    /** 破坏性但可恢复：删除到回收站。 */
    DESTRUCTIVE,

    /** 敏感写：加密/解密（二期）。 */
    SENSITIVE,

    /** 外发：分享/拉起第三方。 */
    EXTERNAL,

    /** 特权：root/Shizuku/命令执行。 */
    PRIVILEGED
}

/** 工具执行结果，序列化为 tool 角色消息内容发回模型。 */
data class ToolResult(
    val ok: Boolean,
    val code: String? = null,
    val message: String = "",
    val data: JSONObject? = null
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("ok", ok)
        if (code != null) o.put("error_code", code)
        if (message.isNotEmpty()) o.put("message", message)
        if (data != null) o.put("data", data)
        return o
    }

    companion object {
        fun ok(data: JSONObject? = null, message: String = "") =
            ToolResult(true, null, message, data)

        fun error(code: ErrorCode, message: String, data: JSONObject? = null) =
            ToolResult(false, code.code, message, data)

        /** 写工具已进入待确认状态，占位结果。 */
        fun awaitingConfirmation(preview: WritePreview): ToolResult {
            val o = JSONObject()
            o.put("status", "awaiting_confirmation")
            o.put("tool", preview.toolName)
            o.put("title", preview.title)
            o.put("item_count", preview.items.size)
            o.put("destination", preview.destination)
            o.put("note", "操作预览已展示给用户，正在等待用户在界面上确认或取消。在收到确认结果前不要再次调用该工具，也不要声称操作已完成。")
            return ToolResult(true, null, "等待用户确认", o)
        }

        fun cancelledByUser(): ToolResult {
            val o = JSONObject()
            o.put("status", "cancelled_by_user")
            o.put("message", "用户取消了该操作，未做任何文件改动。")
            return ToolResult(false, "CANCELLED", "用户取消", o)
        }
    }
}

/** 统一错误码。 */
enum class ErrorCode(val code: String) {
    INVALID_PATH("INVALID_PATH"),
    OUT_OF_SCOPE("OUT_OF_SCOPE"),
    NOT_FOUND("NOT_FOUND"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    CONFLICT("CONFLICT"),
    ALREADY_EXISTS("ALREADY_EXISTS"),
    NO_SPACE("NO_SPACE"),
    PASSWORD_REQUIRED("PASSWORD_REQUIRED"),
    WRONG_PASSWORD("WRONG_PASSWORD"),
    IO_ERROR("IO_ERROR"),
    UNSUPPORTED("UNSUPPORTED"),
    CANCELLED("CANCELLED"),
    NOT_INSTALLED("NOT_INSTALLED"),
    CONTENT_NOT_AUTHORIZED("CONTENT_NOT_AUTHORIZED"),
    NEEDS_CONFIRMATION("NEEDS_CONFIRMATION"),
    INVALID_ARGS("INVALID_ARGS"),
    PLUGIN_NOT_FOUND("PLUGIN_NOT_FOUND"),
    PLUGIN_FAILED("PLUGIN_FAILED")
}

/** 写操作预览中的单个条目。 */
data class PreviewItem(
    val path: String,
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val selected: Boolean = true
)

/**
 * 写操作预览（确认卡数据源）。
 * [destination] 可在确认时被用户编辑；[conflicts] 为目标同名条目名列表。
 */
data class WritePreview(
    val token: String,
    val toolName: String,
    val title: String,
    val items: List<PreviewItem>,
    val destination: String,
    val destinationEditable: Boolean,
    val destinationLabel: String,
    val conflicts: List<String>,
    val conflictPolicy: String = CONFLICT_RENAME,
    val privileged: Boolean = false,
    val destructive: Boolean = false,
    val undoable: Boolean = true,
    val bulk: Boolean = false,
    /** 工具名→执行时需要的附加参数（如压缩格式/级别/写文件格式），不可在 UI 修改。 */
    val extras: Map<String, String> = emptyMap()
) {
    companion object {
        const val CONFLICT_RENAME = "rename"
        const val CONFLICT_SKIP = "skip"
        const val CONFLICT_OVERWRITE = "overwrite"
    }
}

/** 用户在确认卡上的决策。 */
data class Approval(
    val token: String,
    val approved: Boolean,
    val selectedPaths: List<String>,
    val destination: String,
    val conflictPolicy: String,
    val overwriteAcknowledged: Boolean
)
