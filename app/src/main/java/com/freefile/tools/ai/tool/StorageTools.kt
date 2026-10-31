package com.freefile.tools.ai.tool

import android.os.Environment
import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.PreviewItem
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolProgress
import com.freefile.tools.ai.model.ToolResult
import com.freefile.tools.ai.model.WritePreview
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.FileEngine
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.joinPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.freefile.tools.ai.tool.ToolArgs.int
import com.freefile.tools.ai.tool.ToolArgs.long
import com.freefile.tools.ai.tool.ToolArgs.str

/**
 * 存储类工具：根目录、目录树、目录大小、存储分析、重复文件、空目录清理、权限检查。
 * 全部为只读（delete_empty_dirs 除外），不修改文件内容。
 */

// ------------------------------------------------------------------ 遍历工具

internal object FsWalk {

    /** 递归遍历上限，防止超大目录拖死 Agent。 */
    const val ENTRY_LIMIT = 30_000

    suspend fun collect(
        engine: FileEngine,
        root: String,
        maxDepth: Int,
        limit: Int = ENTRY_LIMIT,
        includeHidden: Boolean = false
    ): List<VfsEntry> {
        val out = ArrayList<VfsEntry>()
        walk(engine, root, 0, maxDepth, limit, includeHidden, out)
        return out
    }

    private suspend fun walk(
        engine: FileEngine,
        dir: String,
        depth: Int,
        maxDepth: Int,
        limit: Int,
        includeHidden: Boolean,
        out: MutableList<VfsEntry>
    ) {
        if (out.size >= limit) return
        val children = try {
            engine.list(dir)
        } catch (_: Throwable) {
            return
        }
        for (e in children) {
            if (out.size >= limit) return
            if (!includeHidden && e.isHidden) continue
            out.add(e)
            if (e.isDirectory && depth < maxDepth) {
                walk(engine, e.path, depth + 1, maxDepth, limit, includeHidden, out)
            }
        }
    }
}

// -------------------------------------------------------------- 存储根目录

class GetStorageRootsTool : AgentTool {
    override val name = "get_storage_roots"
    override val description =
        "返回当前 Agent 可访问的存储根目录（内部共享存储、外置 SD 卡等）及其可读写状态。不确定路径时先调用本工具。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val roots = ArrayList<JSONObject>()
            fun addRoot(file: File, label: String) {
                if (!file.exists()) return
                roots.add(
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("label", label)
                        .put("readable", file.canRead())
                        .put("writable", file.canWrite())
                        .put("free_bytes", file.usableSpace)
                        .put("total_bytes", file.totalSpace)
                )
            }
            addRoot(Environment.getExternalStorageDirectory(), "内部共享存储")
            File("/storage").listFiles()?.sortedBy { it.name }?.forEach { vol ->
                if (vol.absolutePath == Environment.getExternalStorageDirectory().absolutePath) return@forEach
                if (!vol.isDirectory) return@forEach
                if (vol.name in setOf("emulated", "self", "sdcard0")) return@forEach
                addRoot(vol, "外置存储 ${vol.name}")
            }
            addRoot(File("/storage/emulated/0/Download"), "下载目录")
            ToolResult.ok(JSONObject().put("roots", JSONArray(roots)), "共 ${roots.size} 个可访问根目录")
        }
}

// ------------------------------------------------------------------ 目录树

class GetFileTreeTool : AgentTool {
    override val name = "get_file_tree"
    override val description =
        "以扁平列表返回目录树（含层级 depth），用于快速了解整体结构。默认深度 2、最多 300 条，避免撑爆上下文。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("depth", JSONObject().put("type", "integer").put("description", "递归深度，默认 2，最大 5"))
                .put("limit", JSONObject().put("type", "integer").put("description", "最多返回条数，默认 300"))
        )
        .put("required", JSONArray(listOf("path")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path", "/storage/emulated/0")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val depth = args.int("depth", 2).coerceIn(0, 5)
            val limit = args.int("limit", 300).coerceIn(1, 1000)
            val engine = EngineSelector.select(check.normalized)
            val all = FsWalk.collect(engine, check.normalized, depth, limit)
            val rootPrefix = check.normalized.trimEnd('/')
            val items = all.map { e ->
                val rel = e.path.removePrefix(rootPrefix).trimStart('/')
                val level = if (rel.isEmpty()) 0 else rel.count { it == '/' } + 1
                e.toJson().put("depth", level)
            }
            ToolResult.ok(
                ToolResults.baseList(items, items.size.takeIf { all.size < limit } ?: (items.size + 1)) {
                    put("root", check.normalized)
                    put("truncated", all.size >= limit)
                },
                if (items.isEmpty()) "目录为空" else "列出 ${items.size} 个条目"
            )
        }
}

// -------------------------------------------------------------- 目录大小

class GetDirectorySizeTool : AgentTool {
    override val name = "get_directory_size"
    override val description = "递归统计目录总大小、文件数与子目录数（类似 du）。大目录较慢，可指定 max_depth 限制。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("max_depth", JSONObject().put("type", "integer").put("description", "默认 -1 表示不限"))
        )
        .put("required", JSONArray(listOf("path")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("path")
            if (raw.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 path")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val engine = EngineSelector.select(check.normalized)
            if (engine.stat(check.normalized) == null) {
                return@withContext ToolResult.error(ErrorCode.NOT_FOUND, "目录不存在：${check.normalized}")
            }
            val depth = args.int("max_depth", -1).let { if (it < 0) 64 else it.coerceAtMost(64) }
            val entries = FsWalk.collect(engine, check.normalized, depth)
            var bytes = 0L
            var files = 0
            var dirs = 0
            entries.forEach { e ->
                if (e.isDirectory) dirs++ else { files++; bytes += e.size }
            }
            ToolResult.ok(
                JSONObject()
                    .put("path", check.normalized)
                    .put("total_bytes", bytes)
                    .put("file_count", files)
                    .put("dir_count", dirs)
                    .put("truncated", entries.size >= FsWalk.ENTRY_LIMIT)
            )
        }
}

// -------------------------------------------------------------- 存储分析

class AnalyzeStorageTool : AgentTool {
    override val name = "analyze_storage"
    override val description =
        "存储分析报告：总占用、按扩展名的空间占比、最大的文件 Top-N、最大的子目录 Top-N。用于回答「什么最占空间」，不会删除任何文件。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("scope", JSONObject().put("type", "string").put("description", "分析根目录，默认主存储"))
                .put("top_n", JSONObject().put("type", "integer").put("description", "Top N，默认 15"))
                .put("max_depth", JSONObject().put("type", "integer").put("description", "默认 8"))
        )

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("scope", "/storage/emulated/0")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val topN = args.int("top_n", 15).coerceIn(1, 50)
            val depth = args.int("max_depth", 8).coerceIn(1, 20)
            val engine = EngineSelector.select(check.normalized)
            val entries = FsWalk.collect(engine, check.normalized, depth)
            ctx.onProgress(ToolProgress(name, entries.size, entries.size.coerceAtLeast(1), current = "分析中"))

            var total = 0L
            var files = 0
            val byExtBytes = HashMap<String, Long>()
            val byExtCount = HashMap<String, Int>()
            val largest = ArrayList<VfsEntry>()
            val dirBytes = HashMap<String, Long>()

            entries.forEach { e ->
                if (e.isDirectory) return@forEach
                files++
                total += e.size
                val ext = e.extension.ifEmpty { "(无扩展名)" }
                byExtBytes[ext] = (byExtBytes[ext] ?: 0L) + e.size
                byExtCount[ext] = (byExtCount[ext] ?: 0) + 1
                largest.add(e)
                val parent = e.path.substringBeforeLast('/').ifEmpty { "/" }
                dirBytes[parent] = (dirBytes[parent] ?: 0L) + e.size
            }

            val extArr = JSONArray()
            byExtBytes.entries.sortedByDescending { it.value }.take(topN).forEach { (ext, bytes) ->
                extArr.put(
                    JSONObject()
                        .put("extension", ext)
                        .put("bytes", bytes)
                        .put("count", byExtCount[ext] ?: 0)
                        .put("percent", if (total > 0) (bytes * 100.0 / total).round1() else 0.0)
                )
            }
            val largestArr = JSONArray()
            largest.sortedByDescending { it.size }.take(topN).forEach {
                largestArr.put(it.toJson())
            }
            val dirArr = JSONArray()
            dirBytes.entries.sortedByDescending { it.value }.take(topN).forEach { (dir, bytes) ->
                dirArr.put(JSONObject().put("path", dir).put("bytes", bytes))
            }

            ToolResult.ok(
                JSONObject()
                    .put("scope", check.normalized)
                    .put("total_bytes", total)
                    .put("file_count", files)
                    .put("scanned_entries", entries.size)
                    .put("truncated", entries.size >= FsWalk.ENTRY_LIMIT)
                    .put("by_extension", extArr)
                    .put("largest_files", largestArr)
                    .put("largest_dirs", dirArr)
                    .put("hint", "如需清理，请基于以上真实路径提出计划，经用户确认后再调用写工具。")
            )
        }
}

private fun Double.round1(): Double = Math.round(this * 10.0) / 10.0

// -------------------------------------------------------------- 重复文件

class ScanDuplicatesTool : AgentTool {
    override val name = "scan_duplicates"
    override val description =
        "扫描重复文件：先按大小分桶，再对同大小文件计算 MD5，返回重复组（每组含大小与全部路径）。只做报告，不删除。" +
            "删除请让用户确认后调用 delete_to_trash，建议每组保留一个。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("scope", JSONObject().put("type", "string").put("description", "扫描根目录，默认主存储"))
                .put("min_bytes", JSONObject().put("type", "integer").put("description", "忽略小于该大小的文件，默认 1024"))
                .put("max_depth", JSONObject().put("type", "integer").put("description", "默认 8"))
                .put("max_files", JSONObject().put("type", "integer").put("description", "最多扫描文件数，默认 5000"))
        )

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("scope", "/storage/emulated/0")
            val check = PathGuard.checkRead(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val minBytes = args.long("min_bytes", 1024L).coerceAtLeast(0L)
            val depth = args.int("max_depth", 8).coerceIn(1, 20)
            val maxFiles = args.int("max_files", 5000).coerceIn(50, 20000)
            val engine = EngineSelector.select(check.normalized)
            val entries = FsWalk.collect(engine, check.normalized, depth)
            val files = entries.filter { !it.isDirectory && it.size >= minBytes && it.size > 0 }
                .take(maxFiles)
            ctx.onProgress(ToolProgress(name, 0, files.size, current = "按大小分组"))

            val bySize = files.groupBy { it.size }.filterValues { it.size > 1 }
            val groups = ArrayList<JSONObject>()
            var hashed = 0
            bySize.forEach { (size, sameSize) ->
                if (ctx.isCancelled()) return@forEach
                val byHash = HashMap<String, MutableList<VfsEntry>>()
                sameSize.forEach { e ->
                    if (ctx.isCancelled()) return@forEach
                    hashed++
                    val digest = try {
                        com.freefile.operations.FileOperations.hash(e.path, "MD5")
                    } catch (_: Throwable) {
                        null
                    } ?: return@forEach
                    byHash.getOrPut(digest) { ArrayList() }.add(e)
                }
                ctx.onProgress(ToolProgress(name, hashed, files.size, current = "计算哈希"))
                byHash.entries.filter { it.value.size > 1 }.forEach { (hash, dup) ->
                    if (groups.size >= 50) return@forEach
                    val waste = size * (dup.size - 1)
                    groups.add(
                        JSONObject()
                            .put("md5", hash)
                            .put("size", size)
                            .put("count", dup.size)
                            .put("wasted_bytes", waste)
                            .put("paths", JSONArray(dup.map { it.path }))
                    )
                }
            }
            groups.sortByDescending { it.optLong("wasted_bytes") }
            val totalWaste = groups.sumOf { it.optLong("wasted_bytes") }
            ToolResult.ok(
                JSONObject()
                    .put("scope", check.normalized)
                    .put("scanned_files", files.size)
                    .put("hashed_files", hashed)
                    .put("group_count", groups.size)
                    .put("wasted_bytes", totalWaste)
                    .put("groups", JSONArray(groups))
                    .put("truncated", entries.size >= FsWalk.ENTRY_LIMIT || files.size >= maxFiles)
            )
        }
}

// -------------------------------------------------------------- 空目录清理

class DeleteEmptyDirsTool : AgentTool {
    override val name = "delete_empty_dirs"
    override val description =
        "递归找出并删除空目录（自底向上，父目录因此变空也会被删除）。会先展示预览并等待确认。此操作不可撤销，请提示用户。"
    override val permission = ToolPermission.WRITE
    override val write = true
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("scope", JSONObject().put("type", "string"))
                .put("max_depth", JSONObject().put("type", "integer").put("description", "默认 8"))
                .put("limit", JSONObject().put("type", "integer").put("description", "最多删除数量，默认 100"))
        )
        .put("required", JSONArray(listOf("scope")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.str("scope")
            if (raw.isEmpty()) return@withContext ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 scope")
            val check = PathGuard.checkWrite(ctx.appContext, raw)
            if (!check.allowed) return@withContext ToolResult.error(ErrorCode.OUT_OF_SCOPE, check.reason ?: "")
            val depth = args.int("max_depth", 8).coerceIn(1, 20)
            val limit = args.int("limit", 100).coerceIn(1, 500)
            val engine = EngineSelector.select(check.normalized)
            val entries = FsWalk.collect(engine, check.normalized, depth, includeHidden = true)

            // 自底向上：子目录先处理
            val dirs = entries.filter { it.isDirectory }
                .sortedByDescending { it.path.length }
            val empty = ArrayList<VfsEntry>()
            val logicallyDeleted = HashSet<String>()
            dirs.forEach { d ->
                val children = try {
                    engine.list(d.path)
                } catch (_: Throwable) {
                    null
                } ?: return@forEach
                val remaining = children.filter { child ->
                    !(child.isDirectory && (child.path in logicallyDeleted ||
                        empty.any { it.path == child.path }))
                }
                if (remaining.isEmpty()) {
                    empty.add(d)
                    logicallyDeleted.add(d.path)
                }
                if (empty.size >= limit) return@forEach
            }
            if (empty.isEmpty()) {
                return@withContext ToolResult.ok(
                    JSONObject().put("deleted", 0).put("scope", check.normalized),
                    "没有发现空目录"
                )
            }

            val preview = WriteSupport.preview(
                toolName = name,
                title = "删除空目录",
                entries = empty,
                destination = "",
                destinationEditable = false,
                destinationLabel = "不可撤销",
                conflicts = emptyList(),
                destructive = true,
                undoable = false
            )
            val approval = ctx.gate.request(preview) ?: return@withContext ToolResult.cancelledByUser()
            val targets = empty.filter { approval.selectedPaths.contains(it.path) }
            if (targets.isEmpty()) return@withContext ToolResult.cancelledByUser()

            val ok = ArrayList<String>()
            val failed = ArrayList<Pair<String, String>>()
            targets.forEach { d ->
                try {
                    if (engine.delete(d.path, recursive = false)) ok.add(d.path)
                    else failed.add(d.path to "删除失败")
                } catch (t: Throwable) {
                    failed.add(d.path to (t.message ?: "IO 错误"))
                }
            }
            WriteSupport.notifyDirs(check.normalized)
            ctx.undoManager.record(
                ctx.agentRepository, ctx.userInstruction, name,
                "删除空目录：${ok.joinToString(";").take(500)}",
                "成功 ${ok.size}，失败 ${failed.size}",
                ok.size, failed.size, 0,
                false, "", check.normalized
            )
            val failArr = JSONArray()
            failed.forEach { (p, r) -> failArr.put(JSONObject().put("path", p).put("reason", r)) }
            ToolResult.ok(
                JSONObject()
                    .put("success", ok.size)
                    .put("failed", failed.size)
                    .put("paths", JSONArray(ok))
                    .put("failures", failArr),
                "已删除 ${ok.size} 个空目录"
            )
        }
}

// -------------------------------------------------------------- 权限检查

class CheckPermissionTool : AgentTool {
    override val name = "check_permission"
    override val description =
        "检查某个路径是否存在、可读、可写，以及由哪个引擎（本地/SMB/WebDAV/压缩包）处理。在执行写操作前可先调用，避免反复失败。"
    override val permission = ToolPermission.READ
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
            val read = PathGuard.checkRead(ctx.appContext, raw)
            val write = PathGuard.checkWrite(ctx.appContext, raw)
            val engine = try {
                EngineSelector.select(read.normalized ?: raw)
            } catch (_: Throwable) {
                null
            }
            val entry = try {
                engine?.stat(read.normalized ?: raw)
            } catch (_: Throwable) {
                null
            }
            ToolResult.ok(
                JSONObject()
                    .put("input", raw)
                    .put("normalized", read.normalized ?: "")
                    .put("engine", engine?.id ?: "")
                    .put("read_allowed", read.allowed)
                    .put("read_reason", read.reason ?: "")
                    .put("write_allowed", write.allowed)
                    .put("write_reason", write.reason ?: "")
                    .put("exists", entry != null)
                    .put("is_directory", entry?.isDirectory ?: false)
                    .put("can_read", entry?.canRead ?: false)
                    .put("can_write", entry?.canWrite ?: false)
            )
        }
}

/** 分享与媒体查询等需要文件 URI 的公共助手。 */
internal object ShareSupport {
    fun uriOf(ctx: android.content.Context, path: String): android.net.Uri? = try {
        androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", File(path)
        )
    } catch (_: Throwable) {
        null
    }
}

/** 供其他工具复用的空目录判定。 */
internal suspend fun isEmptyDir(engine: FileEngine, path: String): Boolean = try {
    engine.list(path).isEmpty()
} catch (_: Throwable) {
    false
}

internal fun previewItemsOf(entries: List<VfsEntry>): List<PreviewItem> =
    entries.map { PreviewItem(path = it.path, name = it.name, size = it.size, isDirectory = it.isDirectory) }

internal fun dirOf(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

internal fun childPath(dir: String, name: String): String = joinPath(dir, name)
