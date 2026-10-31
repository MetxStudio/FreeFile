package com.freefile.tools.ai.tool

import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolProgress
import com.freefile.tools.ai.model.ToolResult
import com.freefile.vfs.EngineSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.freefile.tools.ai.tool.ToolArgs.bool
import com.freefile.tools.ai.tool.ToolArgs.int
import com.freefile.tools.ai.tool.ToolArgs.str
import com.freefile.tools.ai.tool.ToolArgs.strList

/**
 * 内容读写工具：批量读取、内容检索（grep）、追加、局部替换。
 * 读取类需用户授权发送文件内容；写入类走确认卡。
 */

private const val TEXT_MAX_BYTES = 2L * 1024 * 1024

// ---------------------------------------------------------- read_multiple_files

class ReadMultipleFilesTool : AgentTool {
    override val name = "read_multiple_files"
    override val description =
        "一次读取多个文本文件（最多 10 个，单个 ≤256KB），适合批量摘要配置、日志。需要用户已授权读取文件内容。"
    override val permission = ToolPermission.READ_SENSITIVE
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("max_chars", JSONObject().put("type", "integer").put("description", "每个文件最多字符数，默认 4000"))
        )
        .put("required", JSONArray(listOf("paths")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            if (!ctx.contentAuthorized()) {
                return@withContext ToolResult.error(
                    ErrorCode.CONTENT_NOT_AUTHORIZED,
                    "用户尚未授权读取文件内容。请提示用户在 Agent 设置中开启「允许读取文件内容」。"
                )
            }
            val paths = args.strList("paths").distinct()
            if (paths.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 paths")
            val perLimit = args.int("max_chars", 4000).coerceIn(200, 50_000)
            val files = JSONArray()
            val failed = JSONArray()
            paths.take(10).forEach { raw ->
                val check = PathGuard.checkRead(ctx.appContext, raw)
                if (!check.allowed) {
                    failed.put(JSONObject().put("path", raw).put("reason", check.reason ?: "不可访问"))
                    return@forEach
                }
                try {
                    val engine = EngineSelector.select(check.normalized)
                    val entry = engine.stat(check.normalized)
                    if (entry == null || entry.isDirectory) {
                        failed.put(JSONObject().put("path", raw).put("reason", "不存在或不是文件"))
                        return@forEach
                    }
                    if (entry.size > 256 * 1024) {
                        failed.put(JSONObject().put("path", raw).put("reason", "文件过大，请单独用 read_text_file"))
                        return@forEach
                    }
                    val text = engine.openInputStream(check.normalized).use { it.readBytes() }
                        .toString(Charsets.UTF_8)
                    files.put(
                        JSONObject()
                            .put("path", check.normalized)
                            .put("size", entry.size)
                            .put("truncated", text.length > perLimit)
                            .put("content", text.take(perLimit))
                    )
                } catch (t: Throwable) {
                    failed.put(JSONObject().put("path", raw).put("reason", t.message ?: "读取失败"))
                }
            }
            ToolResult.ok(
                JSONObject()
                    .put("files", files)
                    .put("failed", failed)
                    .put("requested", paths.size)
            )
        }
}

// --------------------------------------------------------------- grep_content

class GrepContentTool : AgentTool {
    override val name = "grep_content"
    override val description =
        "在目录内按关键词/正则搜索文件内容，返回命中的文件、行号与该行文本。需要用户已授权读取文件内容。" +
            "适合在日志、代码、笔记中定位信息；命中上限 100 条。"
    override val permission = ToolPermission.READ_SENSITIVE
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("scope", JSONObject().put("type", "string").put("description", "搜索根目录，默认主存储"))
                .put("pattern", JSONObject().put("type", "string").put("description", "关键词或正则"))
                .put("regex", JSONObject().put("type", "boolean").put("description", "是否按正则解释，默认 false"))
                .put("case_sensitive", JSONObject().put("type", "boolean").put("description", "默认 false"))
                .put("ext", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("max_depth", JSONObject().put("type", "integer").put("description", "默认 6"))
                .put("max_files", JSONObject().put("type", "integer").put("description", "最多扫描文件数，默认 2000"))
        )
        .put("required", JSONArray(listOf("pattern")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            if (!ctx.contentAuthorized()) {
                return@withContext ToolResult.error(
                    ErrorCode.CONTENT_NOT_AUTHORIZED,
                    "用户尚未授权读取文件内容。请提示用户在 Agent 设置中开启「允许读取文件内容」。"
                )
            }
            val pattern = args.str("pattern")
            if (pattern.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 pattern")
            val rawScope = args.str("scope", "/storage/emulated/0")
            val check = PathGuard.checkRead(ctx.appContext, rawScope)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")

            val caseSensitive = args.bool("case_sensitive")
            val matcher: (String) -> Boolean
            if (args.bool("regex")) {
                val re = try {
                    if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                } catch (t: Throwable) {
                    return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "正则无效：${t.message}")
                }
                matcher = fun(s: String): Boolean = re.containsMatchIn(s)
            } else if (caseSensitive) {
                matcher = fun(s: String): Boolean = s.contains(pattern)
            } else {
                val lowerPattern = pattern.lowercase()
                matcher = fun(s: String): Boolean = s.lowercase().contains(lowerPattern)
            }

            val exts = args.strList("ext").map { it.trimStart('.').lowercase() }.toSet()
            val depth = args.int("max_depth", 6).coerceIn(1, 20)
            val maxFiles = args.int("max_files", 2000).coerceIn(50, 10000)
            val engine = EngineSelector.select(check.normalized)
            val entries = FsWalk.collect(engine, check.normalized, depth)
            val candidates = entries.filter {
                !it.isDirectory && it.size in 1..(512 * 1024) &&
                    (exts.isEmpty() || it.extension in exts)
            }.take(maxFiles)

            val matches = ArrayList<JSONObject>()
            var scanned = 0
            for (e in candidates) {
                if (matches.size >= 100 || ctx.isCancelled()) break
                scanned++
                ctx.onProgress(ToolProgress(name, scanned, candidates.size, current = e.path))
                val text = try {
                    engine.openInputStream(e.path).use { it.readBytes() }.toString(Charsets.UTF_8)
                } catch (_: Throwable) {
                    continue
                }
                if (text.indexOf('\u0000') >= 0 || text.count { it == '\uFFFD' } > text.length / 20) {
                    continue // 二进制文件跳过
                }
                text.lineSequence().forEachIndexed { idx, line ->
                    if (matches.size < 100 && matcher(line)) {
                        matches.add(
                            JSONObject()
                                .put("path", e.path)
                                .put("line", idx + 1)
                                .put("text", line.take(300))
                        )
                    }
                }
            }
            ToolResult.ok(
                ToolResults.baseList(matches, matches.size) {
                    put("scope", check.normalized)
                    put("pattern", pattern)
                    put("scanned_files", scanned)
                    put("truncated", matches.size >= 100)
                },
                if (matches.isEmpty()) "未找到匹配内容" else "找到 ${matches.size} 处匹配"
            )
        }
}

// ------------------------------------------------------------ append_to_file

class AppendToFileTool : AgentTool {
    override val name = "append_to_file"
    override val description =
        "在文本文件末尾追加内容（不存在则创建）。写操作，会先展示预览等待确认。适合累积日志或保存生成结果。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))
                .put("create_if_missing", JSONObject().put("type", "boolean").put("description", "默认 true"))
        )
        .put("required", JSONArray(listOf("path", "text")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            val text = args.optString("text", "")
            if (raw.isEmpty() || text.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 path 与 text")
            }
            val check = PathGuard.checkWrite(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val path = check.normalized
            val engine = EngineSelector.select(path)
            val existing = try {
                engine.stat(path)
            } catch (_: Throwable) {
                null
            }
            if (existing == null && !args.bool("create_if_missing", true)) {
                return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "文件不存在：$path")
            }
            if (existing != null && existing.size > TEXT_MAX_BYTES) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "文件过大（>2MB），不支持追加")
            }

            val entry = existing ?: com.freefile.vfs.VfsEntry(
                path = path, name = path.substringAfterLast('/'), isDirectory = false
            )
            val preview = WriteSupport.preview(
                toolName = name,
                title = "追加内容到文件",
                entries = listOf(entry),
                destination = path,
                destinationEditable = false,
                destinationLabel = "目标文件",
                conflicts = emptyList()
            )
            val approval = ctx.gate.request(preview) ?: return@withContext ToolResult.cancelledByUser()
            if (!approval.selectedPaths.contains(entry.path)) return@withContext ToolResult.cancelledByUser()

            try {
                val old = if (existing != null) {
                    engine.openInputStream(path).use { it.readBytes() }
                } else ByteArray(0)
                val suffix = if (old.isNotEmpty() && !old.toString(Charsets.UTF_8).endsWith("\n")) {
                    "\n$text"
                } else text
                engine.openOutputStream(path).use { out ->
                    out.write(old)
                    out.write(suffix.toByteArray(Charsets.UTF_8))
                }
                WriteSupport.notifyDirs(dirOf(path))
                ctx.undoManager.record(
                    ctx.agentRepository, ctx.userInstruction, name,
                    "追加到 $path（${suffix.length} 字符）",
                    "成功", 1, 0, 0, false, "", dirOf(path)
                )
                ToolResult.ok(
                    JSONObject().put("path", path).put("appended_chars", suffix.length),
                    "已追加到 ${path.substringAfterLast('/')}"
                )
            } catch (t: Throwable) {
                ToolResult.error(ErrorCode.IO_ERROR, "追加失败：${t.message}")
            }
        }
}

// ----------------------------------------------------------- replace_in_file

class ReplaceInFileTool : AgentTool {
    override val name = "replace_in_file"
    override val description =
        "在单个文本文件内查找并替换文本（count=0 全部替换，count=1 只替换第一处）。比全文重写更省 token。" +
            "写操作，会先展示预览等待确认，且不可撤销，请提醒用户。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("find", JSONObject().put("type", "string"))
                .put("replace", JSONObject().put("type", "string"))
                .put("count", JSONObject().put("type", "integer").put("description", "0 表示全部，默认 0"))
        )
        .put("required", JSONArray(listOf("path", "find", "replace")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            val find = args.optString("find", "")
            if (raw.isEmpty() || find.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 path 与 find")
            }
            val replace = args.optString("replace", "")
            val count = args.int("count", 0)
            val check = PathGuard.checkWrite(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val path = check.normalized
            val engine = EngineSelector.select(path)
            val entry = try {
                engine.stat(path)
            } catch (_: Throwable) {
                null
            } ?: return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "文件不存在：$path")
            if (entry.isDirectory) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "目标是文件夹")
            if (entry.size > TEXT_MAX_BYTES) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "文件过大（>2MB）")
            }

            val original = try {
                engine.openInputStream(path).use { it.readBytes() }.toString(Charsets.UTF_8)
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, "读取失败：${t.message}")
            }
            val occurrences = original.split(find).size - 1
            if (occurrences == 0) {
                return@withContext ToolResult.ok(
                    JSONObject().put("occurrences", 0),
                    "文件中没有找到「$find」，未做修改"
                )
            }
            val updated = if (count <= 0) original.replace(find, replace)
            else original.replaceFirst(find, replace)

            val preview = WriteSupport.preview(
                toolName = name,
                title = "替换文件内容",
                entries = listOf(entry),
                destination = path,
                destinationEditable = false,
                destinationLabel = "目标文件",
                conflicts = emptyList(),
                destructive = true,
                undoable = false
            )
            val approval = ctx.gate.request(preview) ?: return@withContext ToolResult.cancelledByUser()
            if (!approval.selectedPaths.contains(entry.path)) return@withContext ToolResult.cancelledByUser()

            try {
                engine.openOutputStream(path).use { it.write(updated.toByteArray(Charsets.UTF_8)) }
                WriteSupport.notifyDirs(dirOf(path))
                ctx.undoManager.record(
                    ctx.agentRepository, ctx.userInstruction, name,
                    "替换 $path：$find → $replace",
                    "成功", 1, 0, 0, false, "", dirOf(path)
                )
                ToolResult.ok(
                    JSONObject()
                        .put("path", path)
                        .put("replaced", if (count <= 0) occurrences else 1)
                        .put("total_occurrences", occurrences),
                    "已替换 ${if (count <= 0) occurrences else 1} 处"
                )
            } catch (t: Throwable) {
                ToolResult.error(ErrorCode.IO_ERROR, "写入失败：${t.message}")
            }
        }
}
