package com.freefile.tools.ai.tool

import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolResult
import com.freefile.tools.ai.model.WritePreview
import com.freefile.tools.ai.tool.WriteSupport.Landed
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.joinPath
import com.freefile.vfs.pathName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.freefile.tools.ai.tool.ToolArgs.str
import com.freefile.tools.ai.tool.ToolArgs.strList

/** 写工具：mkdir / rename / move / copy / delete_to_trash / restore_from_trash。 */

private fun outcomeJson(
    success: Int,
    failed: Int,
    skipped: Int,
    landed: List<String>,
    failures: List<Pair<String, String>>,
    extra: JSONObject.() -> Unit = {}
): JSONObject {
    val failArr = JSONArray()
    failures.forEach { (p, r) -> failArr.put(JSONObject().put("path", p).put("reason", r)) }
    return JSONObject()
        .put("success", success)
        .put("failed", failed)
        .put("skipped", skipped)
        .put("paths", JSONArray(landed))
        .put("failures", failArr)
        .apply(extra)
}

// ---------------------------------------------------------------------- mkdir

class MkdirTool : AgentTool {
    override val name = "mkdir"
    override val description = "新建文件夹（可含多级不存在的父目录，会自动创建）。写操作，需要用户确认。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
        .put("required", JSONArray(listOf("path")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            if (raw.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 path")
            val check = PathGuard.checkWrite(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val path = check.normalized
            val engine = EngineSelector.select(path)
            if (engine.exists(path)) {
                return@withContext ToolResult.error(ErrorCode.ALREADY_EXISTS, "目标已存在：$path")
            }
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            if (!EngineSelector.select(parent).exists(parent)) {
                return@withContext ToolResult.error(
                    ErrorCode.NOT_FOUND,
                    "父目录不存在，将一并创建：$parent" // mkdirs 实际允许，但给模型明确信息
                )
            }
            val entry = VfsEntry(path = path, name = pathName(path), isDirectory = true)
            val preview = WriteSupport.preview(
                toolName = name,
                title = "新建文件夹",
                entries = listOf(entry),
                destination = parent,
                destinationEditable = false,
                destinationLabel = "所在目录",
                conflicts = emptyList()
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            if (approval.selectedPaths.isEmpty()) return@withContext ToolResult.cancelledByUser()

            try {
                engine.mkdirs(path)
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, "创建失败：${t.message}")
            }
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "新建文件夹：$path", "已创建：$path",
                1, 0, 0, true, ctx.undoManager.inverseToTrash(listOf(path)), parent
            )
            WriteSupport.notifyDirs(parent)
            ToolResult.ok(outcomeJson(1, 0, 0, listOf(path), emptyList()), "文件夹已创建")
        }
}

// --------------------------------------------------------------------- rename

class RenameTool : AgentTool {
    override val name = "rename"
    override val description = "重命名单个文件或文件夹（只改名字，不能移动到其他目录）。写操作，需要用户确认，可撤销。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("new_name", JSONObject().put("type", "string"))
        )
        .put("required", JSONArray(listOf("path", "new_name")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            val newName = args.str("new_name")
            if (raw.isEmpty() || newName.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 path 与 new_name")
            }
            if (newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "new_name 不能包含路径分隔符")
            }
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val engine = EngineSelector.select(check.normalized)
            val entry = engine.stat(check.normalized)
                ?: return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "文件不存在：${check.normalized}")
            val parent = entry.path.substringBeforeLast('/').ifEmpty { "/" }
            val newPath = joinPath(parent, newName)
            val writeCheck = PathGuard.checkWrite(ctx.appContext, newPath)
            if (!writeCheck.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, writeCheck.reason ?: "")
            if (engine.exists(newPath)) {
                return@withContext ToolResult.error(ErrorCode.ALREADY_EXISTS, "同名条目已存在：$newName")
            }
            val preview = WriteSupport.preview(
                toolName = name,
                title = "重命名",
                entries = listOf(entry),
                destination = parent,
                destinationEditable = false,
                destinationLabel = "所在目录（${entry.name} → $newName）",
                conflicts = emptyList(),
                extras = mapOf("new_name" to newName)
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            if (approval.selectedPaths.isEmpty()) return@withContext ToolResult.cancelledByUser()

            try {
                engine.rename(entry.path, newPath)
            } catch (t: Throwable) {
                return@withContext ToolResult.error(ErrorCode.IO_ERROR, "重命名失败：${t.message}")
            }
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "${entry.path} → $newPath", "重命名成功",
                1, 0, 0, true, ctx.undoManager.inverseRenameBack(newPath, entry.path), parent
            )
            WriteSupport.notifyDirs(parent)
            ToolResult.ok(outcomeJson(1, 0, 0, listOf(newPath), emptyList()), "已重命名为 $newName")
        }
}

// --------------------------------------------------------------- move / copy

class MoveTool(private val copyMode: Boolean = false) : AgentTool {
    override val name = if (copyMode) "copy" else "move"
    override val description =
        if (copyMode)
            "把多个文件/文件夹复制到目标目录。参数 conflict 控制同名冲突：rename（默认，保留两者）/skip/overwrite。写操作，需要确认，可撤销。"
        else
            "把多个文件/文件夹移动到目标目录。参数 conflict 控制同名冲突：rename（默认，保留两者）/skip/overwrite。写操作，需要确认，可撤销。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("destination", JSONObject().put("type", "string").put("description", "目标目录绝对路径"))
                .put("conflict", JSONObject().put("type", "string").put("enum", JSONArray(listOf("rename", "skip", "overwrite"))))
        )
        .put("required", JSONArray(listOf("paths", "destination")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawPaths = args.strList("paths").distinct()
            val rawDest = args.str("destination")
            val policy = when (args.str("conflict", WritePreview.CONFLICT_RENAME)) {
                WritePreview.CONFLICT_SKIP -> WritePreview.CONFLICT_SKIP
                WritePreview.CONFLICT_OVERWRITE -> WritePreview.CONFLICT_OVERWRITE
                else -> WritePreview.CONFLICT_RENAME
            }
            if (rawPaths.isEmpty() || rawDest.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 paths 与 destination")
            }
            val destCheck = PathGuard.checkWrite(ctx.appContext, rawDest)
            if (!destCheck.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, destCheck.reason ?: "")
            val destDir = destCheck.normalized

            // 源不得位于目标目录内部（防止递归自复制）
            val prepared = WriteSupport.prepareEntries(ctx, rawPaths)
            val valid = prepared.entries.filter { e ->
                !(destDir == e.path || destDir.startsWith(e.path.trimEnd('/') + "/"))
            }
            val invalidNested = prepared.entries - valid.toSet()
            val preFailures = prepared.missing +
                invalidNested.map { "${it.path}（不能把目录移动/复制到自身或其子目录内）" }

            if (valid.isEmpty()) {
                return@withContext ToolResult.error(
                    ErrorCode.INVALID_ARGS,
                    "没有可操作的源条目：${preFailures.joinToString(";")}"
                )
            }

            val conflicts = try {
                com.freefile.operations.FileOperations.detectConflicts(valid, destDir).map { it.source.name }
            } catch (_: Throwable) {
                emptyList()
            }

            val preview = WriteSupport.preview(
                toolName = name,
                title = if (copyMode) "复制到…" else "移动到…",
                entries = valid,
                destination = destDir,
                destinationEditable = true,
                destinationLabel = "目标目录",
                conflicts = conflicts
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            if (approval.selectedPaths.isEmpty()) return@withContext ToolResult.cancelledByUser()
            if (approval.conflictPolicy == WritePreview.CONFLICT_OVERWRITE && !approval.overwriteAcknowledged) {
                return@withContext ToolResult.error(ErrorCode.PERMISSION_DENIED, "覆盖操作需要用户明确勾选确认")
            }

            val chosen = valid.filter { approval.selectedPaths.contains(it.path) }
            val outcome = WriteSupport.runTransfer(
                ctx = ctx,
                isMove = !copyMode,
                entries = chosen,
                destDir = approval.destination,
                policy = approval.conflictPolicy,
                overwriteAcknowledged = approval.overwriteAcknowledged,
                toolName = name
            )
            val failures = preFailures.map { it to "预检失败" } + outcome.failed
            val sourceParents = chosen.map { it.path.substringBeforeLast('/').ifEmpty { "/" } }.distinct()
            WriteSupport.notifyDirs(*sourceParents.toTypedArray(), approval.destination)

            val inverse = if (copyMode) {
                ctx.undoManager.inverseToTrash(outcome.landed.map { it.dest })
            } else {
                ctx.undoManager.inverseMoveBack(
                    outcome.landed.map { it.dest to it.source.substringBeforeLast('/').ifEmpty { "/" } }
                )
            }
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "${chosen.size} 项 → ${approval.destination}（冲突：${approval.conflictPolicy}）",
                "成功 ${outcome.landed.size}，失败 ${outcome.failed.size}，跳过 ${outcome.skipped.size}",
                outcome.landed.size, outcome.failed.size + preFailures.size, outcome.skipped.size,
                true, inverse, approval.destination
            )
            ToolResult.ok(
                outcomeJson(
                    outcome.landed.size,
                    outcome.failed.size + preFailures.size,
                    outcome.skipped.size,
                    outcome.landed.map { it.dest },
                    failures
                ),
                if (copyMode) "复制完成" else "移动完成"
            )
        }
}

// ------------------------------------------------------------ delete_to_trash

class DeleteToTrashTool : AgentTool {
    override val name = "delete_to_trash"
    override val description =
        "把文件/文件夹删除到回收站（可恢复），不提供永久删除。批量操作必须用户确认。"
    override val permission = ToolPermission.DESTRUCTIVE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "paths",
                JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
            )
        )
        .put("required", JSONArray(listOf("paths")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawPaths = args.strList("paths").distinct()
            if (rawPaths.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 paths")
            val prepared = WriteSupport.prepareEntries(ctx, rawPaths)
            if (prepared.entries.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "条目均不存在：${prepared.missing.joinToString(";")}")
            }
            val preview = WriteSupport.preview(
                toolName = name,
                title = "删除到回收站",
                entries = prepared.entries,
                destination = "",
                destinationEditable = false,
                destinationLabel = "回收站（可恢复）",
                conflicts = emptyList(),
                destructive = true
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            val chosen = prepared.entries.filter { approval.selectedPaths.contains(it.path) }
            if (chosen.isEmpty()) return@withContext ToolResult.cancelledByUser()

            val ok = ArrayList<String>()
            val failed = ArrayList<Pair<String, String>>()
            val parents = ArrayList<String>()
            chosen.forEach { entry ->
                try {
                    val success = ctx.repository.moveToTrash(listOf(entry))
                    if (success) {
                        ok.add(entry.path)
                        parents.add(entry.path.substringBeforeLast('/').ifEmpty { "/" })
                    } else {
                        failed.add(entry.path to "移入回收站失败")
                    }
                } catch (t: Throwable) {
                    failed.add(entry.path to (t.message ?: "IO 错误"))
                }
            }
            WriteSupport.notifyDirs(*parents.distinct().toTypedArray())
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "删除到回收站：${ok.joinToString(";")}",
                "成功 ${ok.size}，失败 ${failed.size}",
                ok.size, failed.size + prepared.missing.size, 0,
                true, ctx.undoManager.inverseRestoreTrash(ok), parents.firstOrNull() ?: ""
            )
            ToolResult.ok(
                outcomeJson(
                    ok.size,
                    failed.size + prepared.missing.size,
                    0, ok,
                    failed + prepared.missing.map { it to "不存在或不可访问" }
                ),
                "已删除到回收站，可在结果卡撤销"
            )
        }
}

// ---------------------------------------------------------- restore_from_trash

class RestoreFromTrashTool : AgentTool {
    override val name = "restore_from_trash"
    override val description =
        "把回收站条目恢复到删除前的原位置（原位置父目录不存在会自动重建）。可通过 trash_ids 或 original_paths 指定。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = true

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("trash_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")))
                .put("original_paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        )

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val ids = args.optJSONArray("trash_ids")?.let { arr ->
                (0 until arr.length()).map { arr.optLong(it) }
            } ?: emptyList()
            val paths = args.strList("original_paths")
            if (ids.isEmpty() && paths.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "需要 trash_ids 或 original_paths")
            }
            val all = ctx.repository.trash.first()
            val targets = all.filter { item ->
                item.id in ids || item.originalPath in paths
            }
            if (targets.isEmpty()) {
                return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "回收站中没有匹配条目，可能已被恢复或清空")
            }
            val entries = targets.map {
                VfsEntry(
                    path = it.originalPath,
                    name = it.fileName,
                    isDirectory = it.isDirectory,
                    size = it.fileSize,
                    lastModified = it.deletedAt
                )
            }
            val firstParent = targets.first().originalPath.substringBeforeLast('/').ifEmpty { "/" }
            val preview = WriteSupport.preview(
                toolName = name,
                title = "从回收站恢复",
                entries = entries,
                destination = firstParent,
                destinationEditable = false,
                destinationLabel = "恢复到原位置",
                conflicts = emptyList()
            )
            val approval = ctx.gate.request(preview)
                ?: return@withContext ToolResult.cancelledByUser()
            val chosen = targets.filter { approval.selectedPaths.contains(it.originalPath) }
            if (chosen.isEmpty()) return@withContext ToolResult.cancelledByUser()

            val ok = ArrayList<String>()
            val failed = ArrayList<Pair<String, String>>()
            val parents = ArrayList<String>()
            chosen.forEach { item ->
                try {
                    if (ctx.repository.restore(item)) {
                        ok.add(item.originalPath)
                        parents.add(item.originalPath.substringBeforeLast('/').ifEmpty { "/" })
                    } else {
                        failed.add(item.originalPath to "恢复失败")
                    }
                } catch (t: Throwable) {
                    failed.add(item.originalPath to (t.message ?: "IO 错误"))
                }
            }
            WriteSupport.notifyDirs(*parents.distinct().toTypedArray())
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "从回收站恢复：${ok.joinToString(";")}",
                "成功 ${ok.size}，失败 ${failed.size}",
                ok.size, failed.size, 0,
                true, ctx.undoManager.inverseToTrash(ok), parents.firstOrNull() ?: ""
            )
            ToolResult.ok(
                outcomeJson(ok.size, failed.size, 0, ok, failed),
                "恢复完成"
            )
        }
}
