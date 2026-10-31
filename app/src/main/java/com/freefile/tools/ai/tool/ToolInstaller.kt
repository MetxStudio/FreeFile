package com.freefile.tools.ai.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 工具的统一注册入口（进程内一次）。
 * MVP 工具真实可用；二期工具以 [ToolRegistry.Unavailable] 占位，协议可见、执行即 UNSUPPORTED。
 */
object ToolInstaller {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return

            // ---- 只读 ----
            ToolRegistry.register(SearchFilesTool())
            ToolRegistry.register(ListDirTool())
            ToolRegistry.register(GetFileInfoTool())
            ToolRegistry.register(ListTrashTool())
            ToolRegistry.register(ReadTextFileTool())
            ToolRegistry.register(OpenWithTool())
            ToolRegistry.register(ListPluginsTool())

            // ---- 写操作（确认 + 撤销）----
            ToolRegistry.register(MkdirTool())
            ToolRegistry.register(RenameTool())
            ToolRegistry.register(MoveTool(copyMode = false))
            ToolRegistry.register(MoveTool(copyMode = true))
            ToolRegistry.register(DeleteToTrashTool())
            ToolRegistry.register(RestoreFromTrashTool())

            // ---- 压缩 / 落盘 ----
            ToolRegistry.register(CompressTool())
            ToolRegistry.register(ExtractTool())
            ToolRegistry.register(WriteTextFileTool())

            // ---- 插件桥接 ----
            ToolRegistry.register(RunPluginActionTool())

            // ---- 轻量日常任务 ----
            ToolRegistry.register(CalculatorTool())
            ToolRegistry.register(GetCurrentTimeTool())
            ToolRegistry.register(OpenUrlTool())

            // ---- 存储分析（只读报告）----
            ToolRegistry.register(GetStorageRootsTool())
            ToolRegistry.register(GetFileTreeTool())
            ToolRegistry.register(GetDirectorySizeTool())
            ToolRegistry.register(AnalyzeStorageTool())
            ToolRegistry.register(ScanDuplicatesTool())
            ToolRegistry.register(CheckPermissionTool())

            // ---- 内容读写 ----
            ToolRegistry.register(ReadMultipleFilesTool())
            ToolRegistry.register(GrepContentTool())
            ToolRegistry.register(AppendToFileTool())
            ToolRegistry.register(ReplaceInFileTool())

            // ---- 组织与整理 ----
            ToolRegistry.register(TagTool())
            ToolRegistry.register(BatchRenameTool())
            ToolRegistry.register(ShareFilesTool())
            ToolRegistry.register(QueryMediaStoreTool())
            ToolRegistry.register(DeleteEmptyDirsTool())

            // ---- 二期占位（协议可见，当前不可用）----
            ToolRegistry.register(
                ToolRegistry.Unavailable(
                    "encrypt",
                    "加密文件（独立 FileCrypto 能力将在后续版本接入）",
                    strParams(listOf("paths", "method", "destination"))
                )
            )
            ToolRegistry.register(
                ToolRegistry.Unavailable(
                    "decrypt",
                    "解密文件（需要密码）",
                    strParams(listOf("paths", "method", "destination", "password"))
                )
            )
            ToolRegistry.register(
                ToolRegistry.Unavailable(
                    "create_rule",
                    "创建自动归档规则（规则引擎集成将在后续版本提供）",
                    strParams(listOf("rule_definition"))
                )
            )

            installed = true
        }
    }

    private fun stringParam(): JSONObject = JSONObject().put("type", "string")

    private fun arrStr(): JSONObject = JSONObject()
        .put("type", "array")
        .put("items", JSONObject().put("type", "string"))

    private fun strParams(keys: List<String>): JSONObject {
        val props = JSONObject()
        keys.forEach { key ->
            val schema = if (key.endsWith("paths")) arrStr() else stringParam()
            props.put(key, schema)
        }
        return JSONObject().put("type", "object").put("properties", props)
    }
}
