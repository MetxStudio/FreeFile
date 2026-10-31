package com.freefile.tools.ai.tool

import android.content.Intent
import com.freefile.data.settings.AppSettings
import com.freefile.search.SearchEngine
import com.freefile.search.SearchOptions
import com.freefile.search.SizeOp
import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolResult
import com.freefile.operations.FileOperations
import com.freefile.ui.OpenWithActivity
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.sortedForDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.freefile.tools.ai.tool.ToolArgs.bool
import com.freefile.tools.ai.tool.ToolArgs.int
import com.freefile.tools.ai.tool.ToolArgs.long
import com.freefile.tools.ai.tool.ToolArgs.str
import com.freefile.tools.ai.tool.ToolArgs.strList
import com.freefile.tools.ai.tool.ToolArgs.strOrNull
import kotlinx.coroutines.Dispatchers

/**
 * 只读工具集合：search_files / list_dir / get_file_info / list_trash /
 * read_text_file（敏感，受开关）/ open_with（外部打开）。
 */

// ---------------------------------------------------------------- search_files

class SearchFilesTool : AgentTool {
    override val name = "search_files"
    override val description =
        "在本机指定目录范围内查找文件/文件夹。支持名称通配符（* ?）、扩展名、类型、大小、修改时间、内容关键词、递归深度。" +
            "scope 传 \"trash\" 表示搜索回收站。结果只包含真实存在的文件，不要编造文件。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("query", JSONObject().put("type", "string").put("description", "文件名通配符，如 *.pdf；为空表示不限制名称"))
                .put("ext", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "扩展名列表，不带点，如 [\"pdf\",\"docx\"]"))
                .put("is_dir", JSONObject().put("type", "boolean").put("description", "true 只要文件夹，false 只要文件，不传则不限"))
                .put("size_op", JSONObject().put("type", "string").put("enum", JSONArray(listOf("none", "ge", "le", "gt", "lt", "eq"))))
                .put("size_bytes", JSONObject().put("type", "integer").put("description", "大小阈值（字节），10MB=10485760"))
                .put("time_from", JSONObject().put("type", "integer").put("description", "修改时间不早于（epoch 毫秒）"))
                .put("time_to", JSONObject().put("type", "integer").put("description", "修改时间不晚于（epoch 毫秒）"))
                .put("scope", JSONObject().put("type", "string").put("description", "搜索根目录绝对路径，默认主存储；传 trash 搜索回收站"))
                .put("content", JSONObject().put("type", "string").put("description", "文本文件内容关键词（需用户已授权发送文件内容）"))
                .put("depth", JSONObject().put("type", "integer").put("description", "递归深度：0 仅当前目录，-1 无限（默认），N 最多 N 层"))
                .put("use_index", JSONObject().put("type", "boolean").put("description", "是否优先使用全文索引缓存（秒出，可能不是最新）"))
        )

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val scope = args.str("scope", "/storage/emulated/0")
            if (scope.equals("trash", ignoreCase = true)) {
                return@withContext searchTrash(args, ctx)
            }
            val check = PathGuard.checkRead(ctx.appContext, scope)
            if (!check.allowed) {
                return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "范围不可访问")
            }
            if (args.str("content").isNotEmpty() && !ctx.contentAuthorized()) {
                return@withContext ToolResult.error(
                    ErrorCode.CONTENT_NOT_AUTHORIZED,
                    "用户尚未授权读取文件内容，不能按内容关键词搜索。请先请用户在 Agent 设置中开启\"允许读取文件内容\"。"
                )
            }
            val sizeOp = when (args.str("size_op", "none").lowercase()) {
                "ge" -> SizeOp.GE; "le" -> SizeOp.LE; "gt" -> SizeOp.GT
                "lt" -> SizeOp.LT; "eq" -> SizeOp.EQ; else -> SizeOp.NONE
            }
            val opts = SearchOptions(
                searchDepth = args.int("depth", -1),
                advanced = sizeOp != SizeOp.NONE ||
                    args.long("time_from") > 0 || args.long("time_to") > 0 ||
                    args.str("content").isNotEmpty(),
                regex = false,
                sizeOp = sizeOp,
                sizeBytes = args.long("size_bytes"),
                timeFrom = args.long("time_from"),
                timeTo = args.long("time_to"),
                contentPattern = args.str("content"),
                useIndex = args.bool("use_index")
            )
            val query = args.str("query")
            val exts = args.strList("ext").map { it.trimStart('.').lowercase() }.toSet()
            val onlyDir = if (args.has("is_dir") && !args.isNull("is_dir")) args.bool("is_dir") else null

            val entries = try {
                if (opts.useIndex && AppSettings.state.value.indexEnabled && opts.contentPattern.isEmpty()) {
                    SearchEngine.searchIndexed(ctx.appContext, query, opts)
                } else {
                    SearchEngine.searchLive(check.normalized, query, opts)
                }
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, "搜索失败：${t.message}")
            }

            val filtered = entries.filter { e ->
                (onlyDir == null || e.isDirectory == onlyDir) &&
                    (exts.isEmpty() || !e.isDirectory && e.extension in exts)
            }
            val items = filtered.map { it.toJson() }
            ToolResult.ok(
                ToolResults.baseList(items, filtered.size) {
                    put("scope", check.normalized)
                    put("hint", "如需进一步操作，请在后续工具中直接使用条目的 path。")
                },
                message = if (filtered.isEmpty()) "没有找到符合条件的文件" else "找到 ${filtered.size} 个结果"
            )
        }

    private suspend fun searchTrash(args: JSONObject, ctx: ToolContext): ToolResult {
        val kw = args.str("query").lowercase()
        val exts = args.strList("ext").map { it.trimStart('.').lowercase() }.toSet()
        val from = args.long("time_from")
        val to = args.long("time_to")
        val all = ctx.repository.trash.first().filter { item ->
            (kw.isEmpty() || item.fileName.lowercase().contains(kw.replace("*", ""))) &&
                (exts.isEmpty() || item.fileName.substringAfterLast('.', "").lowercase() in exts) &&
                (from == 0L || item.deletedAt >= from) &&
                (to == 0L || item.deletedAt <= to)
        }
        val items = all.map {
            JSONObject()
                .put("trash_id", it.id)
                .put("original_path", it.originalPath)
                .put("name", it.fileName)
                .put("size", it.fileSize)
                .put("is_directory", it.isDirectory)
                .put("deleted_at", it.deletedAt)
        }
        return ToolResult.ok(
            ToolResults.baseList(items, all.size) { put("scope", "trash") },
            message = if (all.isEmpty()) "回收站中没有符合条件的条目" else "回收站中找到 ${all.size} 个条目"
        )
    }
}

// ------------------------------------------------------------------ list_dir

class ListDirTool : AgentTool {
    override val name = "list_dir"
    override val description = "列出某个目录的直接子项（含文件与文件夹、大小、修改时间）。每次只列一层，递归查找请用 search_files。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "path",
                JSONObject().put("type", "string").put("description", "目录绝对路径；始终包含 \".\" 与 \"..\" 语义由调用方自行处理")
            )
        )
        .put("required", JSONArray(listOf("path")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            if (raw.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 path")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val engine = EngineSelector.select(check.normalized)
            val entries = try {
                engine.list(check.normalized)
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, "列目录失败：${t.message}")
            }
            val visible = if (AppSettings.state.value.showHidden) entries
            else entries.filter { !it.isHidden }
            val sorted = visible.sortedForDisplay(com.freefile.vfs.SortBy.NAME, true)
            val items = sorted.map { it.toJson() }
            ToolResult.ok(
                ToolResults.baseList(items, sorted.size) {
                    put("path", check.normalized)
                    put("count", sorted.size)
                }
            )
        }
}

// --------------------------------------------------------------- get_file_info

class GetFileInfoTool : AgentTool {
    override val name = "get_file_info"
    override val description = "获取单个文件/文件夹的真实信息：大小、类型、修改时间、权限、扩展名；可选计算 md5/sha1/sha256 哈希（大文件较慢）。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("hash", JSONObject().put("type", "string").put("enum", JSONArray(listOf("none", "md5", "sha1", "sha256"))))
        )
        .put("required", JSONArray(listOf("path")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            if (raw.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 path")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val engine = EngineSelector.select(check.normalized)
            val entry = try {
                engine.stat(check.normalized)
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, t.message ?: "")
            } ?: return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "文件不存在：${check.normalized}")

            val data = entry.toJson()
            data.put("parent", entry.parentPath ?: "")
            val hashAlgo = args.str("hash", "none").uppercase()
            if (hashAlgo in setOf("MD5", "SHA1", "SHA256") && !entry.isDirectory) {
                try {
                    data.put("hash_algorithm", hashAlgo)
                    data.put("hash", FileOperations.hash(entry.path, hashAlgo))
                } catch (t: Throwable) {
                    data.put("hash_error", t.message)
                }
            }
            ToolResult.ok(data)
        }
}

// ----------------------------------------------------------------- list_trash

class ListTrashTool : AgentTool {
    override val name = "list_trash"
    override val description = "列出回收站条目（原路径、大小、删除时间），可按名称关键词与删除时间过滤，供恢复前确认。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("name_keyword", JSONObject().put("type", "string"))
                .put("deleted_from", JSONObject().put("type", "integer").put("description", "删除时间不早于（epoch 毫秒）"))
                .put("deleted_to", JSONObject().put("type", "integer"))
        )

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val kw = args.str("name_keyword").lowercase()
            val from = args.long("deleted_from")
            val to = args.long("deleted_to")
            val all = ctx.repository.trash.first().filter {
                (kw.isEmpty() || it.fileName.lowercase().contains(kw)) &&
                    (from == 0L || it.deletedAt >= from) &&
                    (to == 0L || it.deletedAt <= to)
            }
            val items = all.map {
                JSONObject()
                    .put("trash_id", it.id)
                    .put("original_path", it.originalPath)
                    .put("name", it.fileName)
                    .put("size", it.fileSize)
                    .put("is_directory", it.isDirectory)
                    .put("deleted_at", it.deletedAt)
            }
            ToolResult.ok(ToolResults.baseList(items, all.size) { put("scope", "trash") })
        }
}

// -------------------------------------------------------------- read_text_file

class ReadTextFileTool : AgentTool {
    override val name = "read_text_file"
    override val description =
        "读取文本文件内容（代码、笔记、日志等），用于摘要、解释、问答。文件内容会发送到用户配置的 AI 端点，" +
            "仅在用户明确授权后可用。仅支持 1MB 以内的文本。"
    override val permission = ToolPermission.READ_SENSITIVE
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("max_chars", JSONObject().put("type", "integer").put("description", "最多返回字符数，默认 12000"))
        )
        .put("required", JSONArray(listOf("path")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            if (!ctx.contentAuthorized()) {
                return@withContext ToolResult.error(
                    ErrorCode.CONTENT_NOT_AUTHORIZED,
                    "用户尚未授权读取文件内容。请告知用户：如需让 Agent 阅读/总结该文件，请在 Agent 设置中开启\"允许读取文件内容\"后重试。"
                )
            }
            val raw = args.str("path")
            if (raw.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 path")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val engine = EngineSelector.select(check.normalized)
            val entry = engine.stat(check.normalized)
                ?: return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "文件不存在：${check.normalized}")
            if (entry.isDirectory) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "不能读取文件夹内容")
            if (entry.size > MAX_BYTES) {
                return@withContext ToolResult.error(
                    ErrorCode.INVALID_ARGS,
                    "文件过大（${entry.size} 字节），read_text_file 仅支持 1MB 以内文本"
                )
            }
            val full = try {
                engine.openInputStream(check.normalized).use { it.readBytes() }
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, "读取失败：${t.message}")
            }
            val limit = args.int("max_chars", DEFAULT_MAX_CHARS).coerceIn(200, 100_000)
            val text = full.toString(Charsets.UTF_8)
            val truncated = text.length > limit
            ToolResult.ok(
                JSONObject()
                    .put("path", check.normalized)
                    .put("size_bytes", entry.size)
                    .put("truncated", truncated)
                    .put("content", if (truncated) text.take(limit) else text)
            )
        }

    companion object {
        private const val MAX_BYTES = 1L * 1024 * 1024
        private const val DEFAULT_MAX_CHARS = 12_000
    }
}

// -------------------------------------------------------------------- open_with

class OpenWithTool : AgentTool {
    override val name = "open_with"
    override val description =
        "在设备上打开文件：弹出本应用的\"打开方式\"面板（系统打开、分享、文本编辑、终端、工具托盘、解压等）。" +
            "target 可留空或传 builtin。"
    override val permission = ToolPermission.EXTERNAL_READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put("path", JSONObject().put("type", "string"))
        )
        .put("required", JSONArray(listOf("path")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            if (raw.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 path")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val exists = EngineSelector.select(check.normalized).stat(check.normalized) != null
            if (!exists) return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "文件不存在：${check.normalized}")
            try {
                val intent = Intent(ctx.appContext, OpenWithActivity::class.java)
                    .putStringArrayListExtra(
                        OpenWithActivity.EXTRA_FILE_PATHS,
                        arrayListOf(check.normalized)
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.appContext.startActivity(intent)
                ToolResult.ok(JSONObject().put("path", check.normalized), "已打开\"打开方式\"面板")
            } catch (t: Throwable) {
                ToolResult.error(ErrorCode.IO_ERROR, "无法打开：${t.message}")
            }
        }
}
